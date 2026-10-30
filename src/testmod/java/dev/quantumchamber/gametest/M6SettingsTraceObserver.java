package dev.quantumchamber.gametest;

import com.google.gson.Gson;
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
        Object list,rm,content,lookup,manager,holder,c,f;
        boolean returned,contentDone,cDone,fDone,candidate,drained,retired,controlled;
        int callbacks;
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
        check(EVENTS.size()<126,"EVENT_CAP");
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
    public static int callToken(){synchronized(MONITOR){return call;}}
    public static void begin(MinecraftServer s){observe(()->{boolean on=s.isOnThread();synchronized(MONITOR){check(on&&s==server&&!stopping&&call==-1&&next<=3,"RELOAD_SCOPE");check(OPS[next-1].drained,"PREVIOUS_NOT_DRAINED");call=next++;emit(call,"RELOAD_HEAD",Map.of(),Map.of(),on);}});}
    public static void composeInput(int t,Object list){observe(()->{Boolean on=owner();synchronized(MONITOR){check(t>=1&&t<4&&!OPS[t].retired,"COMPOSE_TOKEN");check(OPS[t].list==null,"DUPLICATE_COMPOSE");OPS[t].list=list;emit(t,"COMPOSE_INPUT",Map.of("list",id(list)),Map.of(),on);}});}
    public static void composeHead(Object list){observe(()->{Boolean on=owner();synchronized(MONITOR){int t=find(list,"list");emit(t,"COMPOSE_HEAD",Map.of("list",id(list)),Map.of(),on);}});}
    public static void bindResource(Object list,ResourceManager rm){observe(()->{synchronized(MONITOR){int t=find(list,"list");check(OPS[t].rm==null,"DUPLICATE_RM");OPS[t].rm=rm;id(rm);}});}
    public static void reloadInput(ResourceManager rm){observe(()->{Boolean on=owner();synchronized(MONITOR){int t;if(OPS[0].rm==null&&server==null){t=0;OPS[0].rm=rm;}else t=find(rm,"rm");emit(t,"RELOAD_INPUT",Map.of("rm",id(rm)),Map.of(),on);}});}
    public static int resourceToken(ResourceManager rm){synchronized(MONITOR){return find(rm,"rm");}}
    public static void applied(Object manager){observe(()->{Boolean on=owner();synchronized(MONITOR){emit(-1,"APPLY_RETURN",Map.of("manager",id(manager)),Map.of(),on);}});}
    private static void registerCallback(int t){synchronized(MONITOR){check(t>=0&&t<4&&!OPS[t].retired&&!finalized,"CALLBACK_REGISTRATION");pending++;OPS[t].callbacks++;}}
    private static void callback(int t,Runnable action){synchronized(MONITOR){activeCallbacks++;if(finalized){late=true;failed=true;}}try{observe(action);}finally{synchronized(MONITOR){activeCallbacks--;pending--;OPS[t].callbacks--;if(pending<0||OPS[t].callbacks<0){failed=true;firstFailure="CALLBACK_UNDERFLOW";}}}}
    public static void contentsFuture(ResourceManager rm,CompletableFuture<DataPackContents> f){observe(()->{int t=resourceToken(rm);registerCallback(t);f.whenComplete((c,error)->callback(t,()->{
        Object l=c==null?null:c.getReloadableRegistries();Object m=c==null?null:c.getReloadableRegistries().getRegistryManager();Boolean on=owner();
        synchronized(MONITOR){Op o=OPS[t];check(!o.retired&&!o.contentDone,"CONTENT_CALLBACK_DUPLICATE");o.contentDone=true;Map<String,String> ids=new LinkedHashMap<>();ids.put("rm",id(rm));if(c!=null){check(o.content==null||o.content==c,"LATE_CONTENT_MISMATCH");check(o.lookup==null||o.lookup==l,"LATE_LOOKUP_MISMATCH");check(o.manager==null||o.manager==m,"LATE_MANAGER_MISMATCH");o.content=c;o.lookup=l;o.manager=m;ids.putAll(contentIds(c,l,m));}emit(t,"CONTENT_COMPLETE",ids,Map.of("success",error==null),on);}
    }));});}
    public static void nativeStage(Object list,CompletionStage<?> c){observe(()->{Boolean on=owner();synchronized(MONITOR){int t=find(list,"list");check(OPS[t].c==null,"DUPLICATE_STAGE");OPS[t].c=c;emit(t,"C_RETURN",Map.of("c",id(c)),Map.of(),on);}});}
    public static void stage(int t,CompletionStage<?> c){observe(()->{synchronized(MONITOR){check(OPS[t].c==c,"ORIGINAL_STAGE_CHANGED");}registerCallback(t);c.whenComplete((h,error)->callback(t,()->{Boolean on=owner();synchronized(MONITOR){check(!OPS[t].cDone&&!OPS[t].retired,"C_CALLBACK_DUPLICATE");OPS[t].cDone=true;emit(t,"C_COMPLETE",Map.of("c",id(c)),Map.of("success",error==null),on);}}));});}
    public static void future(int t,CompletableFuture<?> f){observe(()->{Boolean on=owner();synchronized(MONITOR){check(OPS[t].f==null,"DUPLICATE_FUTURE");OPS[t].f=f;emit(t,"F_RETURN",Map.of("f",id(f)),Map.of(),on);}registerCallback(t);f.whenComplete((unused,error)->callback(t,()->{Boolean callbackOwner=owner();synchronized(MONITOR){check(!OPS[t].fDone&&!OPS[t].retired,"F_CALLBACK_DUPLICATE");OPS[t].fDone=true;emit(t,"F_COMPLETE",Map.of("f",id(f)),Map.of("success",error==null),callbackOwner);}}));});}
    public static void returned(CompletableFuture<?> f){observe(()->{Boolean on=owner();synchronized(MONITOR){check(call>=1&&OPS[call].f==f,"RETURN_F_CHANGED");OPS[call].returned=true;emit(call,"RELOAD_RETURN",Map.of("f",id(f)),Map.of(),on);call=-1;}});}
    public static void holderInput(int t,Object h){observe(()->{View v=view(h);synchronized(MONITOR){check(!OPS[t].retired,"RETIRED_HOLDER");bindView(OPS[t],v);}});}
    public static void applyHead(Object h){observe(()->{View v=view(h);Boolean on=owner();synchronized(MONITOR){int t=find(h,"holder");check(Boolean.TRUE.equals(on),"APPLY_OFF_THREAD");bindView(OPS[t],v);emit(t,"APPLY_HEAD",viewIds(v),Map.of(),on);}});}
    public static void store(MinecraftServer s,Object h,boolean initial){observe(()->{View v=view(h);Boolean on=initial?null:s.isOnThread();synchronized(MONITOR){int t=initial?0:find(h,"holder");bindView(OPS[t],v);activeHolder=h;emit(t,"HOLDER_INSTALL",viewIds(v),Map.of(),on);}});}
    public static void constructed(MinecraftServer s,LevelStorage.Session storage,StructureTemplateManager tm){observe(()->{Object h;synchronized(MONITOR){check(server==null,"SECOND_SERVER");server=s;session=storage;templates=tm;id(s);id(storage);id(tm);h=activeHolder;}active(s,h,tm,"CONSTRUCTOR_RETURN");});}
    public static void starting(MinecraftServer s){observe(()->{check(s.isOnThread(),"START_OFF_THREAD");Object h;StructureTemplateManager tm;synchronized(MONITOR){h=activeHolder;tm=(StructureTemplateManager)templates;}active(s,h,tm,"STARTING_CANDIDATE");candidate(s,h,tm,0);});}
    public static void active(MinecraftServer s,Object h,StructureTemplateManager tm,String code){observe(()->{View v=view(h);boolean coherent=s.getResourceManager()==v.rm&&s.getReloadableRegistries()==v.lookup&&((M6SettingsTraceTemplateAccessor)tm).quantumchamber$traceResourceManager()==v.rm;boolean on=s.isOnThread();int t;
        synchronized(MONITOR){t=find(h,"holder");check(activeHolder==h&&coherent,"ACTIVE_VIEWS_MISMATCH");bindView(OPS[t],v);emit(t,code,viewIds(v),Map.of("coherent",coherent),on);}
        if(code.equals("APPLY_EXIT"))candidate(s,h,tm,t);
    });}
    private static String marker(DynamicRegistryManager.Immutable m){var tag=m.get(RegistryKeys.BLOCK).getEntryList(TagKey.of(RegistryKeys.BLOCK,Identifier.of("quantumchamber:lt_marker"))).orElseThrow();check(tag.size()==1,"MARKER_SIZE");String value=tag.get(0).getKey().orElseThrow().getValue().toString();check(value.equals("minecraft:stone")||value.equals("minecraft:dirt"),"MARKER_VALUE");return value.substring(10);}
    public static void refresh(DataPackContents c,boolean end){observe(()->{var l=c.getReloadableRegistries();var m=l.getRegistryManager();String tag=end?marker(m):"";Boolean on=owner();synchronized(MONITOR){int t=-1;for(int i=0;i<4;i++)if(!OPS[i].retired&&OPS[i].content==c)t=i;if(t<0){check(server==null&&OPS[0].content==null,"UNBOUND_REFRESH");t=0;OPS[0].content=c;OPS[0].lookup=l;OPS[0].manager=m;}check(OPS[t].lookup==l&&OPS[t].manager==m,"REFRESH_IDENTITY");emit(t,end?"REFRESH_RETURN":"REFRESH_HEAD",contentIds(c,l,m),end?Map.of("marker",tag):Map.of(),on);}});}
    private static void candidate(MinecraftServer s,Object h,StructureTemplateManager tm,int t){
        View v=view(h);String tag=marker(v.manager);var registry=v.manager.get(RegistryKeys.LOOT_TABLE);
        var winner=registry.getEntry(Identifier.of("quantumchamber:lt_winner")).orElseThrow().value();
        check(registry.getEntry(Identifier.of("minecraft:empty")).isPresent(),"EMPTY_MISSING");check(registry.getEntry(Identifier.of("quantumchamber:lt_missing")).isEmpty(),"MISSING_PRESENT");
        JsonElement json=LootTable.CODEC.encodeStart(RegistryOps.of(JsonOps.INSTANCE,v.manager),winner).getOrThrow();byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);check(bytes.length<=512,"WINNER_BYTE_CAP");
        String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
        String expected=t==1?"B":"A";String type=t==1?"minecraft:chest":"minecraft:empty";check(json.isJsonObject()&&json.getAsJsonObject().size()==1&&json.getAsJsonObject().has("type")&&json.getAsJsonObject().get("type").getAsString().equals(type),"WINNER_SEMANTICS");
        boolean coherent=s.getResourceManager()==v.rm&&s.getReloadableRegistries()==v.lookup&&((M6SettingsTraceTemplateAccessor)tm).quantumchamber$traceResourceManager()==v.rm;boolean on=s.isOnThread();
        synchronized(MONITOR){check(t!=2&&!OPS[t].candidate&&coherent,"CANDIDATE_INVALID");OPS[t].candidate=true;emit(t,"PUBLICATION_CANDIDATE",viewIds(v),Map.of("marker",tag,"winner",expected,"winnerSha256",digest,"empty",true,"missing",true,"coherent",coherent),on);}
    }
    public static void journal(MinecraftServer s){observe(()->{Object h;synchronized(MONITOR){if(journalSeen)return;journalSeen=true;h=activeHolder;}View v=view(h);boolean on=s.isOnThread();synchronized(MONITOR){check(s==server&&OPS[0].candidate&&on,"JOURNAL_BEFORE_CANDIDATE");emit(0,"JOURNAL_FIRST_GET",viewIds(v),Map.of("candidate",true),on);}});}
    public static void fabricStart(){observe(()->{Boolean on=owner();synchronized(MONITOR){emit(-1,"FABRIC_START",Map.of(),Map.of(),on);}});}
    public static void fabricEnd(boolean success){observe(()->{Boolean on=owner();synchronized(MONITOR){endCount++;endSuccess=success;emit(-1,"FABRIC_END",Map.of(),Map.of("success",success),on);lastEndSeq=EVENTS.size();}});}
    public static void controlled(ResourceManager rm){observe(()->{int t=resourceToken(rm);Boolean on=owner();synchronized(MONITOR){check(t==2&&!OPS[t].controlled,"FAULT_TOKEN");OPS[t].controlled=true;emit(t,"FAILURE_OBSERVED",Map.of("rm",id(rm)),Map.of("controlled",true),on);}});}
    public static boolean drain(int t){if(!ENABLED)return false;Boolean on=owner();synchronized(MONITOR){Op o=OPS[t];if(failed||o.retired)return o.drained;
        if(!o.contentDone||o.callbacks!=0||(t==0?(!o.candidate||!journalSeen):(!o.returned||!o.cDone||!o.fDone||endCount!=t||(t==2?!o.controlled:!o.candidate))))return false;
        if(t>0&&endSuccess!=(t!=2)){failed=true;firstFailure="END_RESULT";return false;}
        o.drained=true;emit(t,"DRAINED",Map.of(),t==0?Map.of():Map.of("endSeq",lastEndSeq),on);o.retired=true;purge();return true;
    }}
    /** 退役只移除引用，累積 identity counter 不減、不重用。 */
    private static void purge(){Set<Object> keep=Collections.newSetFromMap(new IdentityHashMap<>());Collections.addAll(keep,server,session,templates,activeHolder);
        if(activeHolder!=null){for(Op o:OPS)if(o.holder==activeHolder)Collections.addAll(keep,o.rm,o.content,o.lookup,o.manager);}
        for(Op o:OPS)if(!o.retired)Collections.addAll(keep,o.list,o.rm,o.content,o.lookup,o.manager,o.holder,o.c,o.f);
        IDS.keySet().removeIf(key->!keep.contains(key));for(Op o:OPS)if(o.retired&&o.holder!=activeHolder){o.list=null;o.rm=null;o.content=null;o.lookup=null;o.manager=null;o.holder=null;o.c=null;o.f=null;}else if(o.retired){o.list=null;o.c=null;o.f=null;}
    }
    public static MinecraftServer serverForStop(){synchronized(MONITOR){return server;}}
    public static boolean finalized(){synchronized(MONITOR){return finalized;}}
    private static void writeLate(){Path target;synchronized(MONITOR){if(!late||lateWritten||evidence==null)return;lateWritten=true;target=evidence;}try{Files.writeString(target.resolve("late-after-final.json"),"{\"failed\":true}",StandardOpenOption.CREATE_NEW);}catch(Exception error){System.err.println("LT_LATE_AFTER_FINAL_FAIL");}}
    public static boolean failed(){synchronized(MONITOR){return failed;}}
    public static void terminal(){synchronized(MONITOR){driverTerminal=true;}}
    public static void stopping(){observe(()->{Boolean on=owner();synchronized(MONITOR){check(!stopping,"DUPLICATE_STOP");stopping=true;emit(-1,"STOPPING",Map.of(),Map.of(),on);}});}
    public static void stopped(){observe(()->{Boolean on=owner();synchronized(MONITOR){check(stopping&&!stopSeen,"STOP_ORDER");stopSeen=true;emit(-1,"STOPPED",Map.of(),Map.of(),on);}});}
    /** 回傳後仍可有晚到失敗；只有外部自然退出後能採終判。 */
    public static void writeFinalIfReady(){if(!ENABLED)return;List<Map<String,Object>> events=null;Map<String,Object> summary=null;Path target;boolean writeLate;
        synchronized(MONITOR){target=evidence;writeLate=late;
            if(stopSeen&&!finalized&&pending==0&&activeCallbacks==0&&driverTerminal){finalized=true;events=List.copyOf(EVENTS);summary=Map.of("failed",failed,"firstFailure",firstFailure,"driverTerminal",driverTerminal,"pendingCallbacks",pending,"activeObserverCallbacks",activeCallbacks,"identityCount",identities,"lateAfterFinal",late,"scope","8B3-LT-observation","nonce",NONCE);}}
        if(target==null)return;
        try{if(events!=null){StringBuilder lines=new StringBuilder();for(var row:events){String line=JSON.toJson(row);check(line.getBytes(StandardCharsets.UTF_8).length<=1024,"LINE_BYTE_CAP");lines.append(line).append('\n');}check(lines.toString().getBytes(StandardCharsets.UTF_8).length<=131072,"TOTAL_BYTE_CAP");Files.writeString(target.resolve("events.jsonl"),lines,StandardOpenOption.CREATE_NEW);Files.writeString(target.resolve("final-candidate.json"),JSON.toJson(summary),StandardOpenOption.CREATE_NEW);}
            if(writeLate&&!Files.exists(target.resolve("late-after-final.json")))Files.writeString(target.resolve("late-after-final.json"),"{\"failed\":true}",StandardOpenOption.CREATE_NEW);
        }catch(Exception error){fail("WRITER_FAILURE");System.err.println("LT_LATE_AFTER_FINAL_FAIL");}
    }
}
