package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.quantumchamber.gametest.mixin.M6SettingsTraceHolderAccessor;
import dev.quantumchamber.gametest.mixin.M6SettingsTraceTemplateAccessor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.DataPackContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelStorage;

/** 單次、有界、testmod-only 觀測；沒有 context、lease 或 publisher。 */
public final class M6SettingsTraceObserver {
    private static final Object MONITOR=new Object();
    private static final boolean ENABLED=Boolean.getBoolean("quantumchamber.lt.enabled");
    private static final String NONCE=System.getProperty("quantumchamber.lt.nonce","");
    private static final Gson JSON=new Gson();
    private static final boolean LT2=ENABLED&&!System.getProperty("quantumchamber.lt2.scenario","").isEmpty();
    public enum Scenario { OFFTHREAD, OVERLAP_AB, OVERLAP_BA, PARTIAL, STOP_PENDING }
    private static final Scenario SCENARIO=LT2?Scenario.valueOf(System.getProperty("quantumchamber.lt2.scenario").replace('-','_')):null;
    private enum FrameKind { W, COMPOSE, APPLY }
    /** B 類：三種同步 frame；pump 狀態只屬 W，不跨執行緒清除。 */
    public static final class Frame {
        private final FrameKind kind;
        private final int token,parent;
        private final long thread;
        private boolean pumpActive;
        private int pumpEntered,pumpExited;
        private Frame(FrameKind kind,int token,int parent){this.kind=kind;this.token=token;this.parent=parent;this.thread=Thread.currentThread().threadId();}
    }
    private static final ThreadLocal<ArrayDeque<Frame>> W=LT2?new ThreadLocal<>():null;
    private static final ThreadLocal<ArrayDeque<Frame>> COMPOSE=LT2?new ThreadLocal<>():null;
    private static final ThreadLocal<ArrayDeque<Frame>> APPLY=LT2?new ThreadLocal<>():null;
    private static int liveFrames;
    private static boolean cancelled,pairSample,ownerBeforeDispatch,stopRequested,lateCompletion;
    private static long stoppedNanos;
    private static final CountDownLatch CALLER_LATCH=LT2&&SCENARIO==Scenario.OFFTHREAD?new CountDownLatch(1):null;
    public static boolean lt2(){return LT2;}
    public static Scenario scenario(){return SCENARIO;}
    private static final IdentityHashMap<Object,String> IDS=new IdentityHashMap<>();
    private static final Op[] OPS={new Op(),new Op(),new Op(),new Op()};
    private static final List<Map<String,Object>> EVENTS=new ArrayList<>();
    private static int identities,call=-1,next=1,pending,activeCallbacks,endCount;
    private static boolean failed,stopping,stopSeen,finalized,late,driverTerminal,journalSeen;
    private static String firstFailure="";
    private static MinecraftServer server;
    private static Object session,templates,activeHolder;
    private static Path evidence;
    private static int lateCount;
    private static boolean lateWritten;
    private static boolean endSuccess;
    private static long lastEndSeq;

    private static final class Op {
        Object list,rm,content,lookup,manager,holder,c,f,cResult;
        String candidateMarker;
        boolean failureSnapshot;
        boolean returned,contentDone,cDone,fDone,candidate,drained,retired,controlled;
        int callbacks;
        // B 類 DF 不配置 identity；原件與一次註冊狀態在同 token 內保存。
        CompletableFuture<DataPackContents> dfReference;
        boolean dfRegistered,dfRegistrationFailed;
        CompletableFuture<Void> gate;
        boolean gateReached,gateReleased,gateContinuation,invocationExited,originalEntered;
        boolean stored,refreshed,applied,partialFault,fSucceeded,partialCause,callHeld;
        Frame invocation;
        int[] frequency;
    }
    private record View(Object holder,ResourceManager rm,DataPackContents content,Object lookup,DynamicRegistryManager.Immutable manager) {}
    private M6SettingsTraceObserver() {}
    public static boolean enabled(){return ENABLED;}
    public static void configure(Path root){synchronized(MONITOR){evidence=root;}}
    /** 本方法只包診斷，絕不包住原生 callback。 */
    private static void observe(Runnable action){if(!ENABLED)return;try{action.run();}catch(Throwable error){fail("OBSERVER_"+error.getClass().getSimpleName());}finally{writeLate();}}
    public static void fail(String reason){if(!ENABLED)return;synchronized(MONITOR){failed=true;if(finalized)late=true;if(firstFailure.isEmpty())firstFailure=reason.substring(0,Math.min(reason.length(),64));}}
    private static void check(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    private static String id(Object value){
        check(value!=null,"NULL_IDENTITY");String result=IDS.get(value);if(result!=null)return result;
        check(identities<32,"IDENTITY_CAP");result="i"+(++identities);IDS.put(value,result);return result;
    }
    private static Map<String,String> viewIds(View v){return Map.of("holder",id(v.holder),"rm",id(v.rm),"content",id(v.content),"lookup",id(v.lookup),"manager",id(v.manager));}
    private static Map<String,String> contentIds(DataPackContents c,Object lookup,Object manager){return Map.of("content",id(c),"lookup",id(lookup),"manager",id(manager));}
    private static void emit(int token,String code,Map<String,String> ids,Map<String,Object> data,Boolean owner){
        if(finalized){late=true;lateCount++;failed=true;return;}
        check(EVENTS.size()<126,"EVENT_CAP");if(LT2)checkEvent(token,code);
        Thread thread=Thread.currentThread();String name=thread.getName();
        Map<String,Object> row=new LinkedHashMap<>();row.put("seq",EVENTS.size()+1);row.put("nanoTime",Long.toString(System.nanoTime()));
        row.put("threadId",thread.threadId());row.put("threadName",name.substring(0,Math.min(64,name.length())));
        row.put("onThread",owner);row.put("nonce",NONCE);row.put("token",token);row.put("code",code);row.put("ids",Map.copyOf(ids));row.put("data",Map.copyOf(data));EVENTS.add(Collections.unmodifiableMap(row));
    }
    private static Boolean owner(){MinecraftServer value;synchronized(MONITOR){value=server;}return value==null?null:value.isOnThread();}
    private static View view(Object h){var access=(M6SettingsTraceHolderAccessor)h;var c=access.quantumchamber$traceContents();var l=c.getReloadableRegistries();return new View(h,access.quantumchamber$traceResourceManager(),c,l,l.getRegistryManager());}
    private static void bindView(Op op,View v){
        check(op.rm==null||op.rm==v.rm,"RM_MISMATCH");check(op.content==null||op.content==v.content,"CONTENT_MISMATCH");
        check(op.lookup==null||op.lookup==v.lookup,"LOOKUP_MISMATCH");check(op.manager==null||op.manager==v.manager,"MANAGER_MISMATCH");check(op.holder==null||op.holder==v.holder,"HOLDER_MISMATCH");
        op.rm=v.rm;op.content=v.content;op.lookup=v.lookup;op.manager=v.manager;op.holder=v.holder;
    }
    private static int find(Object value,String kind){
        for(int i=0;i<4;i++){Op o=OPS[i];Object candidate=switch(kind){case "list"->o.list;case "rm"->o.rm;case "holder"->o.holder;case "content"->o.content;default->null;};if(candidate==value&&value!=null){check(!o.retired,"RETIRED_TOKEN");return i;}}
        throw new IllegalStateException("UNKNOWN_"+kind);
    }
    public static int callToken(){try{synchronized(MONITOR){return LT2?top(FrameKind.W).token:call;}}catch(Throwable error){fail("CALL_FRAME_MISSING");return -1;}}
    public static void begin(MinecraftServer s){observe(()->{boolean on=s.isOnThread();synchronized(MONITOR){check(on&&s==server&&!stopping&&call==-1&&next<=3,"RELOAD_SCOPE");check(OPS[next-1].drained,"PREVIOUS_NOT_DRAINED");call=next++;emit(call,"RELOAD_HEAD",Map.of(),Map.of(),on);}});}
    public static void composeInput(int t,Object list){observe(()->{Boolean on=owner();synchronized(MONITOR){check(t>=1&&t<4&&!OPS[t].retired,"COMPOSE_TOKEN");check(OPS[t].list==null,"DUPLICATE_COMPOSE");OPS[t].list=list;emit(t,"COMPOSE_INPUT",Map.of("list",id(list)),Map.of(),on);}});}
    public static void composeHead(Object list){observe(()->{Boolean on=owner();synchronized(MONITOR){int t=LT2?top(FrameKind.COMPOSE).token:find(list,"list");check(OPS[t].list==list,"COMPOSE_LIST_IDENTITY");emit(t,"COMPOSE_HEAD",Map.of("list",id(list)),Map.of(),on);}});}
    public static void bindResource(Object list,ResourceManager rm){observe(()->{synchronized(MONITOR){int t=LT2?top(FrameKind.COMPOSE).token:find(list,"list");check(OPS[t].list==list,"COMPOSE_LIST_IDENTITY");check(OPS[t].rm==null,"DUPLICATE_RM");if(LT2)for(int i=0;i<=maxToken();i++)check(i==t||OPS[i].rm!=rm,"CROSS_TOKEN_RM");OPS[t].rm=rm;id(rm);}});}
    public static void reloadInput(ResourceManager rm){observe(()->{Boolean on=owner();synchronized(MONITOR){int t;if(OPS[0].rm==null&&server==null){t=0;OPS[0].rm=rm;}else t=find(rm,"rm");Map<String,String> ids=new LinkedHashMap<>();ids.put("rm",id(rm));if(t>0)ids.put("list",id(OPS[t].list));emit(t,"RELOAD_INPUT",ids,Map.of(),on);}});}
    public static int resourceToken(ResourceManager rm){synchronized(MONITOR){return find(rm,"rm");}}
    public static void applied(Object manager){observe(()->{Boolean on=owner();synchronized(MONITOR){emit(-1,"APPLY_RETURN",Map.of("manager",id(manager)),Map.of(),on);}});}
    private static void registerCallback(int t){synchronized(MONITOR){check(t>=0&&t<4&&!OPS[t].retired&&!finalized,"CALLBACK_REGISTRATION");pending++;OPS[t].callbacks++;}}
    private static void callback(int t,Runnable action){synchronized(MONITOR){activeCallbacks++;if(finalized){late=true;failed=true;}}try{observe(action);}finally{synchronized(MONITOR){activeCallbacks--;pending--;OPS[t].callbacks--;if(pending<0||OPS[t].callbacks<0){failed=true;firstFailure="CALLBACK_UNDERFLOW";}}}}
    public static void contentsFuture(ResourceManager rm,CompletableFuture<DataPackContents> f){observe(()->{
        int t=resourceToken(rm);
        synchronized(MONITOR){Op o=OPS[t];check(!o.dfRegistered,"DUPLICATE_D_FUTURE");o.dfReference=f;o.dfRegistered=true;}
        registerCallback(t);
        try{f.whenComplete((c,error)->callback(t,()->{
            Object l=c==null?null:c.getReloadableRegistries();Object m=c==null?null:c.getReloadableRegistries().getRegistryManager();Boolean on=owner();
            synchronized(MONITOR){Op o=OPS[t];check(o.dfRegistered&&o.dfReference==f,"DF_CALLBACK_IDENTITY");check(!o.retired&&!o.contentDone,"CONTENT_CALLBACK_DUPLICATE");o.contentDone=true;
                Map<String,String> ids=new LinkedHashMap<>();ids.put("rm",id(rm));if(c!=null){check(o.content==null||o.content==c,"LATE_CONTENT_MISMATCH");check(o.lookup==null||o.lookup==l,"LATE_LOOKUP_MISMATCH");check(o.manager==null||o.manager==m,"LATE_MANAGER_MISMATCH");o.content=c;o.lookup=l;o.manager=m;ids.putAll(contentIds(c,l,m));}
                emit(t,"CONTENT_COMPLETE",ids,Map.of("success",error==null),on);
            }
        }));}catch(Throwable e){synchronized(MONITOR){OPS[t].dfRegistrationFailed=true;}fail("DF_REGISTRATION_FAILED");}
    });}
    public static void nativeStage(Object list,CompletionStage<?> c){observe(()->{Boolean on=owner();synchronized(MONITOR){int t=LT2?top(FrameKind.COMPOSE).token:find(list,"list");check(OPS[t].list==list,"COMPOSE_LIST_IDENTITY");check(OPS[t].c==null,"DUPLICATE_STAGE");OPS[t].c=c;emit(t,"C_RETURN",Map.of("c",id(c),"list",id(list)),Map.of(),on);}});}
    public static void stage(int t,CompletionStage<?> c){observe(()->{synchronized(MONITOR){check(OPS[t].c==c,"ORIGINAL_STAGE_CHANGED");}registerCallback(t);c.whenComplete((h,error)->callback(t,()->{Boolean on=owner();synchronized(MONITOR){check(!OPS[t].cDone&&!OPS[t].retired&&(!LT2||OPS[t].c==c),"C_CALLBACK_DUPLICATE");OPS[t].cDone=true;Map<String,String> ids=new LinkedHashMap<>();ids.put("c",id(c));if(error==null){check(h!=null,"C_RESULT_NULL");check(OPS[t].holder==null||OPS[t].holder==h,"C_RESULT_HOLDER_MISMATCH");OPS[t].cResult=h;ids.put("holder",id(h));}emit(t,"C_COMPLETE",ids,Map.of("success",error==null),on);}}));});}
    public static void future(int t,CompletableFuture<?> f){observe(()->{Boolean on=owner();synchronized(MONITOR){check(OPS[t].f==null&&(!LT2||OPS[t].c!=f),"DUPLICATE_OR_ALIAS_FUTURE");OPS[t].f=f;emit(t,"F_RETURN",Map.of("f",id(f)),Map.of(),on);}registerCallback(t);f.whenComplete((unused,error)->callback(t,()->{Boolean callbackOwner=owner();boolean controlledCause=error!=null&&partialCause(error);synchronized(MONITOR){check(!OPS[t].fDone&&!OPS[t].retired,"F_CALLBACK_DUPLICATE");check(OPS[t].f==f,"F_CALLBACK_IDENTITY");OPS[t].fDone=true;OPS[t].fSucceeded=error==null;OPS[t].partialCause=controlledCause;if(LT2&&stopping)lateCompletion=true;emit(t,"F_COMPLETE",Map.of("f",id(f)),LT2?Map.of("success",error==null,"partialCause",OPS[t].partialCause,"afterStopping",stopping):Map.of("success",error==null),callbackOwner);}}));});}
    public static void returned(CompletableFuture<?> f){observe(()->{Boolean on=owner();synchronized(MONITOR){int t=LT2?top(FrameKind.W).token:call;check(t>=1&&OPS[t].f==f&&!OPS[t].returned,"RETURN_F_CHANGED");OPS[t].returned=true;emit(t,"RELOAD_RETURN",Map.of("f",id(f)),Map.of(),on);if(!LT2)call=-1;}});}
    public static void holderInput(int t,Object h){observe(()->{View v=view(h);synchronized(MONITOR){check(!OPS[t].retired,"RETIRED_HOLDER");if(LT2){check(top(FrameKind.APPLY).token==t,"APPLY_FRAME_TOKEN");for(int i=0;i<=maxToken();i++)check(i==t||OPS[i].holder!=h,"CROSS_TOKEN_HOLDER");}check(OPS[t].cResult==null||OPS[t].cResult==h,"CONSUMER_C_RESULT_MISMATCH");bindView(OPS[t],v);}});}
    public static void applyHead(Object h){observe(()->{View v=view(h);Boolean on=owner();synchronized(MONITOR){int t=LT2?top(FrameKind.APPLY).token:find(h,"holder");check(OPS[t].holder==h,"APPLY_HOLDER_IDENTITY");check(Boolean.TRUE.equals(on),"APPLY_OFF_THREAD");bindView(OPS[t],v);emit(t,"APPLY_HEAD",viewIds(v),Map.of(),on);}});}
    public static void store(MinecraftServer s,Object h,boolean initial){observe(()->{View v=view(h);Boolean on=initial?null:s.isOnThread();synchronized(MONITOR){int t=initial?0:(LT2?top(FrameKind.APPLY).token:find(h,"holder"));if(!initial)check(OPS[t].holder==h,"STORE_HOLDER_IDENTITY");bindView(OPS[t],v);if(LT2){check(!OPS[t].stored,"DUPLICATE_STORE");OPS[t].stored=true;}activeHolder=h;emit(t,"HOLDER_INSTALL",viewIds(v),Map.of(),on);}});}
    public static void constructed(MinecraftServer s,LevelStorage.Session storage,StructureTemplateManager tm){observe(()->{Object h;synchronized(MONITOR){check(server==null,"SECOND_SERVER");server=s;session=storage;templates=tm;id(s);id(storage);id(tm);h=activeHolder;}active(s,h,tm,"CONSTRUCTOR_RETURN");});}
    public static void starting(MinecraftServer s){observe(()->{check(s.isOnThread(),"START_OFF_THREAD");Object h;StructureTemplateManager tm;synchronized(MONITOR){h=activeHolder;tm=(StructureTemplateManager)templates;}active(s,h,tm,"STARTING_CANDIDATE");candidate(s,h,tm,0);});}
    public static void active(MinecraftServer s,Object h,StructureTemplateManager tm,String code){observe(()->{View v=view(h);boolean coherent=s.getResourceManager()==v.rm&&s.getReloadableRegistries()==v.lookup&&((M6SettingsTraceTemplateAccessor)tm).quantumchamber$traceResourceManager()==v.rm;boolean on=s.isOnThread();int t;
        synchronized(MONITOR){t=find(h,"holder");check(activeHolder==h&&coherent,"ACTIVE_VIEWS_MISMATCH");bindView(OPS[t],v);emit(t,code,viewIds(v),Map.of("coherent",coherent),on);}
        if(code.equals("APPLY_EXIT")){if(LT2)synchronized(MONITOR){OPS[t].applied=true;}candidate(s,h,tm,t);}
    });}
    private static String marker(DynamicRegistryManager.Immutable m){var tag=m.get(RegistryKeys.BLOCK).getEntryList(TagKey.of(RegistryKeys.BLOCK,Identifier.of("quantumchamber:lt_marker"))).orElseThrow();check(tag.size()==1,"MARKER_SIZE");String value=tag.get(0).getKey().orElseThrow().getValue().toString();check(value.equals("minecraft:stone")||value.equals("minecraft:dirt"),"MARKER_VALUE");return value.substring(10);}
    public static void refresh(DataPackContents c,boolean end){observe(()->{var l=c.getReloadableRegistries();var m=l.getRegistryManager();String tag=end?marker(m):"";Boolean on=owner();synchronized(MONITOR){int t=-1;for(int i=0;i<4;i++)if(!OPS[i].retired&&OPS[i].content==c)t=i;if(t<0){check(server==null&&OPS[0].content==null,"UNBOUND_REFRESH");t=0;OPS[0].content=c;OPS[0].lookup=l;OPS[0].manager=m;}check(OPS[t].lookup==l&&OPS[t].manager==m,"REFRESH_IDENTITY");if(LT2&&end){check(!OPS[t].refreshed,"DUPLICATE_REFRESH");OPS[t].refreshed=true;}emit(t,end?"REFRESH_RETURN":"REFRESH_HEAD",contentIds(c,l,m),end?Map.of("marker",tag):Map.of(),on);}});}
    private static void candidate(MinecraftServer s,Object h,StructureTemplateManager tm,int t){
        View v=view(h);String tag=marker(v.manager);var registry=v.manager.get(RegistryKeys.LOOT_TABLE);
        var winner=registry.getEntry(Identifier.of("quantumchamber:lt_winner")).orElseThrow().value();
        check(registry.getEntry(Identifier.of("minecraft:empty")).isPresent(),"EMPTY_MISSING");check(registry.getEntry(Identifier.of("quantumchamber:lt_missing")).isEmpty(),"MISSING_PRESENT");
        JsonElement json=LootTable.CODEC.encodeStart(RegistryOps.of(JsonOps.INSTANCE,v.manager),winner).getOrThrow();byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);check(bytes.length<=512,"WINNER_BYTE_CAP");
        String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
        String expected=t==1?"B":"A";String type=t==1?"minecraft:chest":"minecraft:empty";check(json.isJsonObject()&&json.getAsJsonObject().size()==1&&json.getAsJsonObject().has("type")&&json.getAsJsonObject().get("type").getAsString().equals(type),"WINNER_SEMANTICS");
        boolean coherent=s.getResourceManager()==v.rm&&s.getReloadableRegistries()==v.lookup&&((M6SettingsTraceTemplateAccessor)tm).quantumchamber$traceResourceManager()==v.rm;boolean on=s.isOnThread();
        synchronized(MONITOR){check((LT2||t!=2)&&!OPS[t].candidate&&coherent,"CANDIDATE_INVALID");OPS[t].candidate=true;OPS[t].candidateMarker=tag;emit(t,"PUBLICATION_CANDIDATE",viewIds(v),Map.of("marker",tag,"winner",expected,"winnerSha256",digest,"empty",true,"missing",true,"coherent",coherent),on);}
    }
    public static void journal(MinecraftServer s){observe(()->{Object h;synchronized(MONITOR){if(journalSeen)return;journalSeen=true;h=activeHolder;}View v=view(h);boolean on=s.isOnThread();synchronized(MONITOR){check(s==server&&OPS[0].candidate&&on,"JOURNAL_BEFORE_CANDIDATE");emit(0,"JOURNAL_FIRST_GET",viewIds(v),Map.of("candidate",true),on);}});}
    public static void fabricStart(){observe(()->{Boolean on=owner();synchronized(MONITOR){emit(-1,"FABRIC_START",Map.of(),Map.of(),on);}});}
    public static void fabricEnd(boolean success){observe(()->{Boolean on=owner();synchronized(MONITOR){endCount++;endSuccess=success;emit(-1,"FABRIC_END",Map.of(),Map.of("success",success),on);lastEndSeq=EVENTS.size();}});}
    public static void controlled(ResourceManager rm){observe(()->{int t=resourceToken(rm);Boolean on=owner();synchronized(MONITOR){check(t==2&&!OPS[t].controlled,"FAULT_TOKEN");OPS[t].controlled=true;emit(t,"FAILURE_OBSERVED",Map.of("rm",id(rm)),Map.of("controlled",true),on);}});}
    /** 原 F 已完成後在 owner thread 讀實際來源，不假設失敗沒有部分套用。 */
    public static void failureSnapshot(MinecraftServer s,CompletableFuture<?> f){observe(()->{
        Object holder;
        synchronized(MONITOR){if(OPS[2].failureSnapshot)return;check(OPS[2].f==f&&!OPS[2].retired,"FAILURE_F_MISMATCH");holder=activeHolder;}
        boolean on=s.isOnThread();check(on,"FAILURE_SNAPSHOT_OFF_THREAD");
        boolean done=f.isDone(),exceptional=f.isCompletedExceptionally();
        check(done&&exceptional,"FAILURE_F_NOT_COMPLETE");
        View v=view(holder);Object rm=s.getResourceManager();var lookup=s.getReloadableRegistries();
        Object actualManager=lookup.getRegistryManager();
        Object templateRm=((M6SettingsTraceTemplateAccessor)s.getStructureTemplateManager()).quantumchamber$traceResourceManager();
        String tag=observedMarker(lookup.getRegistryManager());
        boolean coherent=rm==v.rm&&lookup==v.lookup&&actualManager==v.manager&&templateRm==v.rm;
        synchronized(MONITOR){
            check(s==server&&OPS[2].returned&&!stopping,"FAILURE_SNAPSHOT_PHASE");
            Op prior=OPS[1];boolean retained=coherent&&holder==prior.holder&&v.rm==prior.rm&&v.content==prior.content
                    &&v.lookup==prior.lookup&&v.manager==prior.manager&&Objects.equals(tag,prior.candidateMarker);
            Map<String,String> ids=new LinkedHashMap<>(viewIds(v));ids.put("f",id(f));ids.put("templateRm",id(templateRm));
            ids.put("activeRm",id(rm));ids.put("activeLookup",id(lookup));ids.put("activeManager",id(actualManager));
            emit(2,"FAILURE_ACTIVE_VIEW",ids,Map.of("futureDone",done,"futureFailed",exceptional,"marker",tag,
                    "coherent",coherent,"classification",retained?"RETAINED":"PARTIAL"),on);
            OPS[2].failureSnapshot=true;
        }
    });}
    private static String observedMarker(DynamicRegistryManager.Immutable m){
        var optional=m.get(RegistryKeys.BLOCK).getEntryList(TagKey.of(RegistryKeys.BLOCK,Identifier.of("quantumchamber:lt_marker")));
        if(optional.isEmpty())return "ABSENT";
        var entries=optional.orElseThrow();if(entries.size()==0)return "EMPTY";if(entries.size()>2)return "UNSUPPORTED";
        List<String> values=new ArrayList<>();
        for(int i=0;i<entries.size();i++){
            var key=entries.get(i).getKey();if(key.isEmpty())return "UNSUPPORTED";
            String value=key.orElseThrow().getValue().toString();
            if(!value.equals("minecraft:stone")&&!value.equals("minecraft:dirt"))return "UNSUPPORTED";
            values.add(value.substring(10));
        }
        Collections.sort(values);return String.join(",",values);
    }
    public static boolean drain(int t){if(!ENABLED)return false;if(LT2){synchronized(MONITOR){if(t!=0)return false;Op o=OPS[0];if(o.drained)return true;if(failed||!o.contentDone||o.callbacks!=0||!o.candidate||!journalSeen)return false;o.drained=true;emit(0,"DRAINED",Map.of(),Map.of(),true);return true;}}Boolean on=owner();synchronized(MONITOR){Op o=OPS[t];if(failed||o.retired)return o.drained;
        if(!o.contentDone||o.callbacks!=0||(t==0?(!o.candidate||!journalSeen):(!o.returned||!o.cDone||!o.fDone||endCount!=t||(t==2?(!o.controlled||!o.failureSnapshot):!o.candidate))))return false;
        if(t>0&&endSuccess!=(t!=2)){failed=true;firstFailure="END_RESULT";return false;}
        o.drained=true;emit(t,"DRAINED",Map.of(),t==0?Map.of():Map.of("endSeq",lastEndSeq),on);o.retired=true;purge();return true;
    }}
    /** 退役只移除引用，累積 identity counter 不減、不重用。 */
    private static void purge(){Set<Object> keep=Collections.newSetFromMap(new IdentityHashMap<>());Collections.addAll(keep,server,session,templates,activeHolder);
        if(activeHolder!=null){for(Op o:OPS)if(o.holder==activeHolder)Collections.addAll(keep,o.rm,o.content,o.lookup,o.manager);}
        for(Op o:OPS)if(!o.retired)Collections.addAll(keep,o.list,o.rm,o.content,o.lookup,o.manager,o.holder,o.c,o.f,o.cResult);
        IDS.keySet().removeIf(key->!keep.contains(key));for(Op o:OPS)if(o.retired&&o.holder!=activeHolder){o.list=null;o.rm=null;o.content=null;o.lookup=null;o.manager=null;o.holder=null;o.c=null;o.f=null;o.cResult=null;}else if(o.retired){o.list=null;o.c=null;o.f=null;o.cResult=null;}
    }
    public static MinecraftServer serverForStop(){synchronized(MONITOR){return server;}}
    public static boolean finalized(){synchronized(MONITOR){return finalized;}}
    private static void writeLate(){Path target;synchronized(MONITOR){if(!late||lateWritten||evidence==null)return;lateWritten=true;target=evidence;}try{Files.writeString(target.resolve("late-after-final.json"),"{\"failed\":true}",StandardOpenOption.CREATE_NEW);}catch(Exception error){System.err.println("LT_LATE_AFTER_FINAL_FAIL");}}
    public static boolean failed(){synchronized(MONITOR){return failed;}}
    public static void terminal(){synchronized(MONITOR){driverTerminal=true;}}
    public static void stopping(){observe(()->{Boolean on=owner();synchronized(MONITOR){check(!stopping,"DUPLICATE_STOP");stopping=true;emit(-1,"STOPPING",Map.of(),Map.of(),on);}});if(LT2)cancelOwn();}
    public static void stopped(){observe(()->{Boolean on=owner();synchronized(MONITOR){check(stopping&&!stopSeen,"STOP_ORDER");stopSeen=true;stoppedNanos=System.nanoTime();emit(-1,"STOPPED",Map.of(),Map.of(),on);}});}
    /** 回傳後仍可有晚到失敗；只有外部自然退出後能採終判。 */
    public static void writeFinalIfReady(){if(!ENABLED)return;if(LT2){writeLt2Final();return;}List<Map<String,Object>> events=null;Map<String,Object> summary=null;Path target;boolean writeLate;
        synchronized(MONITOR){target=evidence;writeLate=late;
            if(stopSeen&&!finalized&&pending==0&&activeCallbacks==0&&driverTerminal){finalized=true;events=List.copyOf(EVENTS);summary=Map.of("failed",failed,"firstFailure",firstFailure,"driverTerminal",driverTerminal,"pendingCallbacks",pending,"activeObserverCallbacks",activeCallbacks,"identityCount",identities,"lateAfterFinal",late,"scope","8B3-LT-observation","nonce",NONCE);}}
        if(target==null)return;
        try{if(events!=null){StringBuilder lines=new StringBuilder();for(var row:events){String line=JSON.toJson(row);check(line.getBytes(StandardCharsets.UTF_8).length<=1024,"LINE_BYTE_CAP");lines.append(line).append('\n');}check(lines.toString().getBytes(StandardCharsets.UTF_8).length<=131072,"TOTAL_BYTE_CAP");Files.writeString(target.resolve("events.jsonl"),lines,StandardOpenOption.CREATE_NEW);Files.writeString(target.resolve("final-candidate.json"),JSON.toJson(summary),StandardOpenOption.CREATE_NEW);}
            if(writeLate&&!Files.exists(target.resolve("late-after-final.json")))Files.writeString(target.resolve("late-after-final.json"),"{\"failed\":true}",StandardOpenOption.CREATE_NEW);
        }catch(Exception error){fail("WRITER_FAILURE");System.err.println("LT_LATE_AFTER_FINAL_FAIL");}
    }

    private static ThreadLocal<ArrayDeque<Frame>> stack(FrameKind kind){return switch(kind){case W->W;case COMPOSE->COMPOSE;case APPLY->APPLY;};}
    private static Frame top(FrameKind kind){var frames=stack(kind).get();check(frames!=null&&!frames.isEmpty(),"MISSING_"+kind);return frames.peek();}
    private static Frame push(FrameKind kind,int token){
        var local=stack(kind);var frames=local.get();if(frames==null){frames=new ArrayDeque<>();local.set(frames);}
        check(frames.size()<2,"FRAME_DEPTH");Frame result=new Frame(kind,token,frames.isEmpty()?-1:frames.peek().token);frames.push(result);liveFrames++;return result;
    }
    private static void pop(Frame frame){
        check(frame!=null&&frame.thread==Thread.currentThread().threadId(),"FRAME_THREAD");var local=stack(frame.kind);var frames=local.get();
        check(frames!=null&&frames.peek()==frame,"FRAME_IDENTITY");frames.pop();liveFrames--;if(frames.isEmpty())local.remove();
    }
    public static Frame enterInvocation(MinecraftServer s){
        if(!LT2){begin(s);return null;}
        Frame result=null;
        try{boolean on=s.isOnThread();synchronized(MONITOR){
            check(s==server&&!stopping&&!cancelled&&next<=maxToken()&&OPS[0].drained,"INVOCATION_SCOPE");
            int t=next++;result=push(FrameKind.W,t);Op o=OPS[t];o.invocation=result;o.gate=new CompletableFuture<>();
            emit(t,"RELOAD_HEAD",Map.of(),Map.of("depth",W.get().size(),"parent",result.parent),on);
            if(t==2){check(on&&result.parent==1,"FAIL_NOT_REENTRANT");var iterator=W.get().iterator();iterator.next();check(iterator.next().pumpActive,"PARENT_PUMP_NOT_ACTIVE");}
        }}catch(Throwable error){fail("INVOCATION_FRAME_FAILED");}return result;
    }
    public static void beforeOriginal(Frame frame){
        if(!LT2||frame==null)return;
        if(SCENARIO==Scenario.OFFTHREAD){
            observe(()->{Boolean on=owner();synchronized(MONITOR){check(Boolean.FALSE.equals(on)&&frame.token==1,"OFFTHREAD_CALLER");OPS[1].callHeld=true;emit(1,"CALL_HELD",Map.of(),Map.of("admission","CLOSED"),on);}});
            try{if(!CALLER_LATCH.await(2,TimeUnit.SECONDS))fail("CALLER_BARRIER_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();fail("CALLER_INTERRUPTED");}finally{CALLER_LATCH.countDown();}
        }
        observe(()->{synchronized(MONITOR){check(!cancelled&&!stopping,"ORIGINAL_AFTER_CANCEL");OPS[frame.token].originalEntered=true;}});
    }
    public static void invocationThrown(Frame frame,Throwable error){if(!LT2)return;observe(()->{Boolean on=owner();String type=boundedType(error);synchronized(MONITOR){emit(frame==null?-1:frame.token,"INVOCATION_THROW",Map.of(),Map.of("type",type),on);}});fail("SYNCHRONOUS_INVOCATION_THROW");}
    public static void exitInvocation(Frame frame,boolean normal){
        if(!LT2)return;
        observe(()->{Boolean on=owner();synchronized(MONITOR){check(frame!=null,"MISSING_W_FRAME");
            boolean paired=!frame.pumpActive&&frame.pumpEntered==frame.pumpExited;
            if(!paired)fail("PUMP_NOT_UNWOUND");
            pop(frame);OPS[frame.token].invocationExited=true;
            emit(frame.token,"INVOCATION_EXIT",Map.of(),Map.of("normal",normal,"pumpPaired",paired,"depth",W.get()==null?0:W.get().size()),on);
        }});
    }
    public static Frame enterCompose(int token){return enterFrame(FrameKind.COMPOSE,token);}
    public static Frame enterApply(int token){return enterFrame(FrameKind.APPLY,token);}
    private static Frame enterFrame(FrameKind kind,int token){if(!LT2)return null;try{synchronized(MONITOR){check(token>0&&token<=maxToken(),"FRAME_TOKEN");return push(kind,token);}}catch(Throwable e){fail("FRAME_ENTER");return null;}}
    public static void exitCompose(Frame frame){exitFrame(frame);}
    public static void exitApply(Frame frame){exitFrame(frame);}
    private static void exitFrame(Frame frame){if(LT2)observe(()->{synchronized(MONITOR){pop(frame);}});}
    public static void applyExecutor(int t,Object executor,MinecraftServer s){if(LT2)observe(()->{synchronized(MONITOR){check(executor==s&&s==server&&t>0,"APPLY_EXECUTOR_CHANGED");}});}
    public static Frame enterPump(MinecraftServer s){
        if(!LT2)return null;Frame frame=null;
        try{boolean on=s.isOnThread();synchronized(MONITOR){frame=top(FrameKind.W);check(on&&s==server&&frame.thread==Thread.currentThread().threadId()&&!frame.pumpActive&&frame.pumpEntered==0,"PUMP_SCOPE");frame.pumpActive=true;frame.pumpEntered=1;emit(frame.token,"PUMP_ENTER",Map.of(),Map.of("parent",frame.parent,"depth",W.get().size()),on);}}
        catch(Throwable e){fail("PUMP_ENTER_FAILED");}return frame;
    }
    public static void exitPump(Frame frame,boolean normal){
        if(!LT2)return;observe(()->{Boolean on=owner();synchronized(MONITOR){
            check(frame!=null&&frame.thread==Thread.currentThread().threadId(),"PUMP_EXIT_THREAD");
            var frames=W.get();boolean correct=frames!=null&&frames.peek()==frame&&frame.pumpActive&&frame.pumpEntered==1&&frame.pumpExited==0;
            frame.pumpActive=false;frame.pumpExited=1;
            if(!correct)fail("PUMP_EXIT_FRAME_MISMATCH");emit(frame.token,"PUMP_EXIT",Map.of(),Map.of("normal",normal,"exactFrame",correct),on);
        }});
    }
    public static boolean canQueueB(){synchronized(MONITOR){Op a=OPS[1];return LT2&&!cancelled&&!stopping&&next==2&&a.invocation!=null&&a.invocation.pumpActive&&a.gateReached&&!a.gateReleased;}}
    public static boolean nestedOwner(MinecraftServer s){
        try{boolean on=s.isOnThread();synchronized(MONITOR){if(cancelled||stopping)return false;Frame a=top(FrameKind.W);check(on&&s==server&&a==OPS[1].invocation&&a.pumpActive&&a.token==1,"FAIL_NOT_REENTRANT");emit(1,"NESTED_OWNER",Map.of(),Map.of("pumpActive",true),on);return true;}}
        catch(Throwable e){fail("FAIL_NOT_REENTRANT");return false;}
    }
    public static boolean callerHeld(){synchronized(MONITOR){return OPS[1].callHeld&&!ownerBeforeDispatch&&!cancelled&&!stopping;}}
    public static void ownerBeforeDispatch(MinecraftServer s){
        observe(()->{boolean on=s.isOnThread();synchronized(MONITOR){check(on&&s==server&&!cancelled&&!stopping&&OPS[1].callHeld&&!OPS[1].originalEntered&&!ownerBeforeDispatch,"OWNER_BEFORE_DISPATCH_SCOPE");ownerBeforeDispatch=true;emit(1,"OWNER_BEFORE_DISPATCH",Map.of(),Map.of("admission","CLOSED","originalEntered",false),on);}});
        if(CALLER_LATCH!=null)CALLER_LATCH.countDown();
    }
    public static CompletableFuture<Void> listenerGate(int t,ResourceManager rm){
        Boolean on=owner();synchronized(MONITOR){check(LT2&&t>0&&t<=maxToken()&&OPS[t].rm==rm&&OPS[t].gate!=null&&!OPS[t].gateReached,"GATE_SCOPE");OPS[t].gateReached=true;emit(t,"GATE_REACHED",Map.of("rm",id(rm)),Map.of(),on);return OPS[t].gate;}
    }
    public static void gateContinued(int t){observe(()->{Boolean on=owner();synchronized(MONITOR){check(OPS[t].gateReached&&OPS[t].gateReleased&&!OPS[t].gateContinuation,"GATE_CONTINUATION");OPS[t].gateContinuation=true;}});}
    public static void releaseGate(int t){CompletableFuture<Void> gate=null;
        synchronized(MONITOR){Op o=OPS[t];if(o.gate!=null&&!o.gateReleased){o.gateReleased=true;gate=o.gate;try{emit(t,"GATE_RELEASED",Map.of(),Map.of("cancelled",cancelled),null);}catch(Throwable error){fail("GATE_RELEASE_DIAGNOSTIC");}}}
        // 診斷達 cap 仍必放開本 mod 的 gate；不讓記錄錯誤阻擋原鏈收斂。
        if(gate!=null)try{gate.complete(null);}catch(Throwable error){fail("GATE_RELEASE_FAILED");}
    }
    public static void cancelOwn(){synchronized(MONITOR){cancelled=true;}for(int i=1;i<=maxToken();i++)releaseGate(i);if(CALLER_LATCH!=null)CALLER_LATCH.countDown();}
    public static boolean cancelledOrStopping(){synchronized(MONITOR){return cancelled||stopping;}}
    public static int maxToken(){return SCENARIO==Scenario.OVERLAP_AB||SCENARIO==Scenario.OVERLAP_BA?2:1;}
    public static boolean gatesReached(){synchronized(MONITOR){for(int i=1;i<=maxToken();i++)if(!OPS[i].gateReached)return false;return true;}}
    public static boolean pairSample(){synchronized(MONITOR){return pairSample;}}
    public static boolean sampleOriginalPair(){
        int first=SCENARIO==Scenario.OVERLAP_AB?1:2,second=3-first;CompletableFuture<?> firstFuture,secondFuture;
        synchronized(MONITOR){if(pairSample)return true;if(cancelled||stopping||OPS[first].f==null||OPS[second].f==null)return false;firstFuture=(CompletableFuture<?>)OPS[first].f;secondFuture=(CompletableFuture<?>)OPS[second].f;}
        boolean firstDone=firstFuture.isDone(),secondDone=secondFuture.isDone(),firstFailed=firstFuture.isCompletedExceptionally();
        if(!firstDone||secondDone||firstFailed)return false;
        synchronized(MONITOR){check(OPS[first].f==firstFuture&&OPS[second].f==secondFuture,"PAIR_F_IDENTITY");if(pairSample)return true;pairSample=true;emit(first,"PAIR_DONE_SAMPLE",Map.of("firstF",id(firstFuture),"secondF",id(secondFuture)),Map.of("firstDone",true,"secondDone",false,"firstFailed",false),null);}return true;
    }
    public static CompletableFuture<?> originalFuture(int t){synchronized(MONITOR){return (CompletableFuture<?>)OPS[t].f;}}
    public static boolean invocationExited(int t){synchronized(MONITOR){return OPS[t].invocationExited;}}
    public static boolean stopPendingReady(){
        CompletableFuture<?> f; synchronized(MONITOR){Op o=OPS[1];if(cancelled||stopping||stopRequested||!o.returned||!o.gateReached||o.gateReleased||o.stored||o.f==null)return false;f=(CompletableFuture<?>)o.f;}
        if(f.isDone())return false;
        synchronized(MONITOR){Op o=OPS[1];check(o.f==f&&!o.stored&&!o.gateReleased,"STOP_PENDING_RACE");stopRequested=true;emit(1,"STOP_REQUEST",Map.of("f",id(f)),Map.of("futureDone",false,"gateHeld",true),null);return true;}
    }
    public static boolean stoppedSeen(){synchronized(MONITOR){return stopSeen;}}
    public static boolean stopObservationDue(){synchronized(MONITOR){return stopSeen&&(lateCompletion||System.nanoTime()-stoppedNanos>=5_000_000_000L);}}
    public static void partialAfterRefresh(Object h){
        if(!LT2||SCENARIO!=Scenario.PARTIAL)return;
        boolean inject=false;
        try{synchronized(MONITOR){int t=top(FrameKind.APPLY).token;Op o=OPS[t];check(t==1&&o.holder==h&&o.stored&&o.refreshed&&!o.applied&&!o.partialFault,"PARTIAL_WINDOW");o.partialFault=true;emit(t,"PARTIAL_THROW",Map.of("holder",id(h)),Map.of("afterRefresh",true),true);inject=true;}}
        catch(Throwable e){fail("PARTIAL_WINDOW_FAILED");}
        if(inject)throw new IllegalStateException("LT2_PARTIAL_AFTER_REFRESH");
    }
    private static String boundedType(Throwable error){String type=error.getClass().getName();return type.substring(0,Math.min(type.length(),128));}
    private static boolean partialCause(Throwable error){for(int i=0;i<8&&error!=null;i++,error=error.getCause())if("LT2_PARTIAL_AFTER_REFRESH".equals(error.getMessage()))return true;return false;}
    public static void partialSnapshot(MinecraftServer s){observe(()->{
        CompletableFuture<?> f=originalFuture(1);check(f!=null&&f.isDone()&&f.isCompletedExceptionally()&&s.isOnThread(),"PARTIAL_F_STATE");
        Object h;synchronized(MONITOR){if(OPS[1].failureSnapshot)return;check(OPS[1].returned&&OPS[1].invocationExited,"PARTIAL_W_PENDING");h=activeHolder;}
        View v=view(h);Object rm=s.getResourceManager(),lookup=s.getReloadableRegistries(),manager=s.getReloadableRegistries().getRegistryManager();
        Object templateRm=((M6SettingsTraceTemplateAccessor)s.getStructureTemplateManager()).quantumchamber$traceResourceManager();String tag=observedMarker(v.manager);
        synchronized(MONITOR){Op o=OPS[1];boolean exact=s==server&&o.holder==h&&o.rm==rm&&o.lookup==lookup&&o.manager==manager&&templateRm==OPS[0].rm&&o.rm!=templateRm&&o.refreshed&&o.partialFault&&o.partialCause&&!o.applied;
            Map<String,String> ids=new LinkedHashMap<>(viewIds(v));ids.put("f",id(f));ids.put("templateRm",id(templateRm));ids.put("activeRm",id(rm));ids.put("activeLookup",id(lookup));ids.put("activeManager",id(manager));emit(1,"FAILURE_ACTIVE_VIEW",ids,Map.of("futureDone",true,"futureFailed",true,"marker",tag,"classification",exact?"PARTIAL":"WRONG_WINDOW"),true);o.failureSnapshot=true;check(exact,"FAIL_WRONG_PARTIAL_WINDOW");}
    });}
    public static boolean lt2Complete(){
        synchronized(MONITOR){if(failed||liveFrames!=0||pending!=0||activeCallbacks!=0)return false;
            for(int t=1;t<=maxToken();t++){Op o=OPS[t];if(!o.returned||!o.invocationExited||!o.contentDone||!o.cDone||!o.fDone||o.dfRegistrationFailed||!o.gateContinuation)return false;
                if(SCENARIO==Scenario.PARTIAL){if(o.fSucceeded||!o.failureSnapshot||!o.partialCause)return false;}else if(!o.fSucceeded||!o.candidate||!o.applied)return false;
            }return maxToken()==1||pairSample;
        }
    }

    private enum EventCode {
        RELOAD_HEAD,COMPOSE_INPUT,COMPOSE_HEAD,RELOAD_INPUT,APPLY_RETURN,CONTENT_COMPLETE,C_RETURN,C_COMPLETE,F_RETURN,F_COMPLETE,RELOAD_RETURN,
        APPLY_HEAD,HOLDER_INSTALL,CONSTRUCTOR_RETURN,STARTING_CANDIDATE,TEMPLATES_UPDATED,APPLY_EXIT,REFRESH_RETURN,REFRESH_HEAD,PUBLICATION_CANDIDATE,
        JOURNAL_FIRST_GET,FABRIC_START,FABRIC_END,FAILURE_ACTIVE_VIEW,DRAINED,STOPPING,STOPPED,INVOCATION_THROW,INVOCATION_EXIT,PUMP_ENTER,PUMP_EXIT,
        NESTED_OWNER,CALL_HELD,OWNER_BEFORE_DISPATCH,GATE_REACHED,GATE_RELEASED,PAIR_DONE_SAMPLE,STOP_REQUEST,PARTIAL_THROW,STOP_OBSERVATION
    }
    private static final int[] ROOT_FREQUENCY=LT2?new int[EventCode.values().length]:null;
    private static void checkEvent(int t,String code){
        EventCode event=EventCode.valueOf(code);int[] counts;
        if(t==-1)counts=ROOT_FREQUENCY;else{check(t>=0&&t<=maxToken(),"EVENT_TOKEN");if(OPS[t].frequency==null)OPS[t].frequency=new int[EventCode.values().length];counts=OPS[t].frequency;}
        int cap=t==-1&&(event==EventCode.FABRIC_START||event==EventCode.FABRIC_END)?maxToken():t==-1&&event==EventCode.APPLY_RETURN?maxToken()+1:1;
        check(++counts[event.ordinal()]<=cap,"EVENT_FREQUENCY_"+code);
    }
    private static void writeLt2Final(){
        List<Map<String,Object>> events;Map<String,Object> summary;Path target;
        synchronized(MONITOR){
            boolean stopMode=SCENARIO==Scenario.STOP_PENDING;
            if(finalized||!stopSeen||!driverTerminal)return;
            if(stopMode){if(!lateCompletion&&System.nanoTime()-stoppedNanos<5_000_000_000L)return;}
            else if(pending!=0||activeCallbacks!=0||liveFrames!=0)return;
            String status=failed?"FAIL":stopMode&&!lateCompletion?"STOP_UNPROVEN":"PASS";
            if(!stopMode&&!lt2Complete()){failed=true;status="FAIL";if(firstFailure.isEmpty())firstFailure="FINAL_INCOMPLETE";}
            if(stopMode)emit(1,"STOP_OBSERVATION",Map.of("f",id(OPS[1].f)),Map.of("lateCompletion",lateCompletion?"OBSERVED":"UNKNOWN","pendingCallbacks",pending,"activeCallbacks",activeCallbacks,"frames",liveFrames,"dfPending",!OPS[1].contentDone,"cPending",!OPS[1].cDone,"fPending",!OPS[1].fDone,"gateReleased",OPS[1].gateReleased),null);
            summary=new LinkedHashMap<>();summary.put("scope","8B3-LT2-observation");summary.put("scenario",SCENARIO.name().replace('_','-'));summary.put("nonce",NONCE);
            summary.put("status",status);summary.put("failed",failed);summary.put("firstFailure",firstFailure);summary.put("driverTerminal",driverTerminal);
            summary.put("pendingCallbacks",pending);summary.put("activeObserverCallbacks",activeCallbacks);summary.put("liveFrames",liveFrames);summary.put("identityCount",identities);
            summary.put("lateAfterFinal",late);summary.put("lateCompletion",lateCompletion?"OBSERVED":"UNKNOWN");summary.put("nativeCoveragePassed",status.equals("PASS"));
            summary.put("publisher","NOT_RUN");summary.put("leaseInvalidation","NOT_RUN");summary.put("task8Eligible",false);
            target=evidence;events=List.copyOf(EVENTS);finalized=true;
        }
        try{final Gson eventJson = new GsonBuilder().serializeNulls().create();StringBuilder lines=new StringBuilder();for(var row:events){String line=eventJson.toJson(row);check(line.getBytes(StandardCharsets.UTF_8).length<=1024,"LINE_BYTE_CAP");lines.append(line).append('\n');}
            check(lines.toString().getBytes(StandardCharsets.UTF_8).length<=131072,"TOTAL_BYTE_CAP");
            Files.writeString(target.resolve("events.jsonl"),lines,StandardOpenOption.CREATE_NEW);Files.writeString(target.resolve("final-candidate.json"),JSON.toJson(summary),StandardOpenOption.CREATE_NEW);
        }catch(Throwable e){fail("WRITER_FAILURE");System.err.println("LT_LATE_AFTER_FINAL_FAIL");}
    }
}
