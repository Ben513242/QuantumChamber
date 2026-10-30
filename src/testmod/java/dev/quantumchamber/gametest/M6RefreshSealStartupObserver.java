package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.quantumchamber.gametest.mixin.M6SettingsTraceHolderAccessor;
import dev.quantumchamber.universe.Minecraft121RefreshSealAccess;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.DataPackContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;

/** 只觀測一次自然成功 startup；所有 native 保留欄位均為弱引用。 */
public final class M6RefreshSealStartupObserver {
    private static final boolean ENABLED=Boolean.getBoolean("quantumchamber.gametest.m6Only");
    private static final Object MONITOR=new Object();
    private static final Gson JSON=new Gson();
    private static final List<String> EVENTS=new ArrayList<>();
    private static final String[] ORDER={"FACTORY_PIN","ARM_EXACT","REFRESH_HEAD","BEFORE_WORK","REFRESH_RETURN","REFRESH_CALL_RETURNED","HOLDER_INSTALL","GAMETEST_ASSERTED","STOPPING_DETACHED","STOPPED"};
    private static WeakReference<Object> factory=new WeakReference<>(null),resource=new WeakReference<>(null),content=new WeakReference<>(null),lookup=new WeakReference<>(null),manager=new WeakReference<>(null),server=new WeakReference<>(null),tag=new WeakReference<>(null),loot=new WeakReference<>(null);
    private static String nonce="",start="",failure="",markerJson="";
    private static long pid;
    private static boolean beforeClear,afterSealed,stopping,stopped;
    private M6RefreshSealStartupObserver() { }
    private static void check(boolean value,String code) { if(!value)throw new IllegalStateException(code); }
    /** 不留 Throwable；診斷不改變 vanilla callback 的例外語意。 */
    private static void observe(Runnable action) {
        if(!ENABLED)return;
        try { action.run(); } catch(Throwable error) {
            boolean first,cleared;
            synchronized(MONITOR) {
                first=failure.isEmpty();
                if(first)failure="S1A_"+error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()).substring(0,Math.min(160,String.valueOf(error.getMessage()).length()));
                cleared=stopped;
            }
            // 終局之後的首次錯誤也要留下新事件；不讓診斷改變 vanilla 的控制流。
            if(first)try { event("DIAGNOSTIC_FAILED",false,cleared); } catch(Throwable ignored) { }
        }
    }
    private static void event(String name,boolean predicate,boolean cleared) {
        String line;
        synchronized(MONITOR) {
            check(EVENTS.size()<32,"EVENT_LIMIT");
            int seq=EVENTS.size()+1;
            if(seq>ORDER.length||!ORDER[seq-1].equals(name)){if(failure.isEmpty())failure="EVENT_ORDER";}
            EVENTS.add(name);
            line="QC_M6_SEAL_S1A "+JSON.toJson(Map.of("startupNonce",nonce,"pid",pid,"startInstant",start,"seq",seq,"event",name,"predicate",predicate,"allWeakSlotsCleared",cleared,"failure",failure));
        }
        check(line.getBytes(StandardCharsets.UTF_8).length<=16384,"EVENT_BYTES");
        System.out.println(line);
    }
    public static String pinFactory(Object value) {
        if(!ENABLED)return null;
        observe(()->{
            String fresh=UUID.randomUUID().toString();
            long currentPid=ProcessHandle.current().pid();
            String currentStart=ProcessHandle.current().info().startInstant().orElseThrow().toString();
            synchronized(MONITOR) {check(nonce.isEmpty(),"SECOND_STARTUP");nonce=fresh;pid=currentPid;start=currentStart;factory=new WeakReference<>(value);}
            event("FACTORY_PIN",true,false);
        });
        synchronized(MONITOR){return nonce;}
    }
    public static void arm(Object f,Object rm,DataPackContents c) {
        observe(()->{
            synchronized(MONITOR){if(factory.get()!=f)return;check(content.get()==null&&!stopping,"DUPLICATE_ARM");}
            var l=c.getReloadableRegistries();var m=l.getRegistryManager();
            synchronized(MONITOR){content=new WeakReference<>(c);resource=new WeakReference<>(rm);lookup=new WeakReference<>(l);manager=new WeakReference<>(m);}
            event("ARM_EXACT",true,false);
        });
    }
    private static boolean armed(DataPackContents c) { synchronized(MONITOR){return c==content.get()&&!stopping;} }
    private static boolean sealed(DataPackContents c) {var l=c.getReloadableRegistries();return ((Minecraft121RefreshSealAccess)c).quantumchamber$isRefreshSealed(l,l.getRegistryManager());}
    public static void refresh(DataPackContents c,String phase) { observe(()->{if(armed(c))event(phase,true,false);}); }
    public static void beforeWork(DataPackContents c) {
        observe(()->{if(!armed(c))return;boolean clear=!sealed(c);synchronized(MONITOR){beforeClear=clear;}event("BEFORE_WORK",clear,false);});
    }
    /** 返回的資料只有本次 stack 暫存強引用；保留欄位仍為弱引用與純字串。 */
    private static Marker marker(DataPackContents c) {
        var m=c.getReloadableRegistries().getRegistryManager();
        var key=TagKey.of(RegistryKeys.ITEM,Identifier.of("quantumchamber:seal_marker"));
        var actualTag=m.get(RegistryKeys.ITEM).getEntryList(key).orElseThrow();
        check(actualTag.size()==1,"ACTUAL_TAG_SIZE");
        var item=actualTag.get(0);
        check(item.getKey().orElseThrow().getValue().equals(Identifier.of("minecraft:stone"))&&item.isIn(key),"ACTUAL_TAG_STONE_MEMBERSHIP");
        var actualLoot=m.get(RegistryKeys.LOOT_TABLE).getEntry(Identifier.of("quantumchamber:seal_marker")).orElseThrow().value();
        JsonElement encoded=LootTable.CODEC.encodeStart(RegistryOps.of(JsonOps.INSTANCE,m),actualLoot).getOrThrow();
        String json=encoded.toString();check(json.getBytes(StandardCharsets.UTF_8).length<=4096,"ACTUAL_LOOT_BYTES");
        check(java.util.Set.of("type","pools").containsAll(encoded.getAsJsonObject().keySet()),"ACTUAL_LOOT_ROOT_SHAPE");
        var pools=encoded.getAsJsonObject().getAsJsonArray("pools");check(pools!=null&&pools.size()==1,"ACTUAL_LOOT_POOLS");
        var pool=pools.get(0).getAsJsonObject();check(java.util.Set.of("rolls","bonus_rolls","entries").containsAll(pool.keySet())&&(!pool.has("bonus_rolls")||pool.get("bonus_rolls").getAsDouble()==0),"ACTUAL_LOOT_POOL_SHAPE");check(pool.get("rolls").getAsDouble()==1.0,"ACTUAL_LOOT_ROLLS");
        var entries=pool.getAsJsonArray("entries");check(entries.size()==1,"ACTUAL_LOOT_ENTRIES");
        var entry=entries.get(0).getAsJsonObject();check(java.util.Set.of("type","name","weight","quality").containsAll(entry.keySet())&&(!entry.has("weight")||entry.get("weight").getAsInt()==1)&&(!entry.has("quality")||entry.get("quality").getAsInt()==0),"ACTUAL_LOOT_ENTRY_SHAPE");check(entry.get("type").getAsString().equals("minecraft:item")&&entry.get("name").getAsString().equals("minecraft:stone"),"ACTUAL_LOOT_STONE");
        return new Marker(actualTag,actualLoot,json);
    }
    private record Marker(Object tag,Object loot,String json) { }
    public static void callReturned(Object f,Object rm,DataPackContents c) {
        observe(()->{
            synchronized(MONITOR){if(factory.get()!=f)return;check(content.get()==c&&resource.get()==rm,"AFTER_EXACT_CONTENT");}
            boolean seal=sealed(c);Marker actual=marker(c);
            synchronized(MONITOR){check(lookup.get()==c.getReloadableRegistries()&&manager.get()==c.getReloadableRegistries().getRegistryManager(),"AFTER_TUPLE");afterSealed=seal;tag=new WeakReference<>(actual.tag());loot=new WeakReference<>(actual.loot());markerJson=actual.json();}
            event("REFRESH_CALL_RETURNED",seal,false);
        });
    }
    public static void store(MinecraftServer s,Object holder,boolean initial) {
        observe(()->{
            synchronized(MONITOR){if(nonce.isEmpty())return;if(!initial){if(server.get()==s)throw new IllegalStateException("UNEXPECTED_RELOAD");return;}}
            var h=(M6SettingsTraceHolderAccessor)holder;var c=h.quantumchamber$traceContents();var rm=h.quantumchamber$traceResourceManager();var l=c.getReloadableRegistries();var m=l.getRegistryManager();
            synchronized(MONITOR){check(!stopping&&server.get()==null&&content.get()==c&&resource.get()==rm&&lookup.get()==l&&manager.get()==m,"HOLDER_EXACT");server=new WeakReference<>(s);}
            event("HOLDER_INSTALL",true,false);
        });
    }
    public static void assertNaturalStartup(TestContext context) {
        check(ENABLED,"M6_ONLY");var s=context.getWorld().getServer();DataPackContents c;Object rm,l,m,oldTag,oldLoot;String oldJson,error;boolean clear,seal,counts;
        synchronized(MONITOR){check(server.get()==s&&!stopping,"GAMETEST_EXACT_SERVER");c=(DataPackContents)content.get();rm=resource.get();l=lookup.get();m=manager.get();oldTag=tag.get();oldLoot=loot.get();oldJson=markerJson;error=failure;clear=beforeClear;seal=afterSealed;counts=EVENTS.equals(List.of(ORDER).subList(0,7));}
        context.assertTrue(error.isEmpty(),"naturalStartupRefreshSealsExactBoundSources: "+error);
        context.assertTrue(counts,"naturalStartupRefreshSealsExactBoundSources: GAMETEST_EVENT_COUNTS");
        context.assertTrue(clear,"naturalStartupDoesNotSealBeforeNativeWork: BEFORE_WORK 必須 false");
        context.assertTrue(c!=null&&l!=null&&m!=null&&rm!=null&&s.getResourceManager()==rm&&s.getReloadableRegistries()==l&&c.getReloadableRegistries()==l&&c.getReloadableRegistries().getRegistryManager()==m,"naturalStartupRefreshSealsExactBoundSources: exact tuple");
        context.assertTrue(seal&&sealed(c),"naturalStartupRefreshSealsExactBoundSources: AFTER seal 必須 true");
        Marker actual=marker(c);context.assertTrue(oldTag==actual.tag()&&oldLoot==actual.loot()&&oldJson.equals(actual.json()),"naturalStartupRefreshSealsExactBoundSources: actual marker identity/value");
        event("GAMETEST_ASSERTED",true,false);
    }
    private static void detach() {factory.clear();resource.clear();content.clear();lookup.clear();manager.clear();tag.clear();loot.clear();markerJson="";}
    public static void stopping(MinecraftServer s) {observe(()->{synchronized(MONITOR){if(server.get()!=s)return;check(!stopping&&!stopped,"DUPLICATE_STOPPING");stopping=true;detach();}event("STOPPING_DETACHED",true,false);});}
    public static void stopped(MinecraftServer s) {observe(()->{synchronized(MONITOR){if(server.get()!=s)return;check(stopping&&!stopped,"STOPPED_WITHOUT_STOPPING");server.clear();stopped=true;check(factory.get()==null&&resource.get()==null&&content.get()==null&&lookup.get()==null&&manager.get()==null&&tag.get()==null&&loot.get()==null,"WEAK_SLOTS_RETAINED");}event("STOPPED",true,true);});}
    public static void startupFailed(String token) {observe(()->{synchronized(MONITOR){if(!nonce.equals(token))return;failure="STARTUP_FAILED";detach();server.clear();}event("STARTUP_FAILED",false,true);});}
}
