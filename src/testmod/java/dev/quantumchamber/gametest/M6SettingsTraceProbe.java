package dev.quantumchamber.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.StandardOpenOption;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.DataPackContents;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;

/** 一次 startup→success→failure→recovery→正常 stop 的專用 driver。 */
public final class M6SettingsTraceProbe implements ModInitializer {
    private volatile MinecraftServer server;
    private volatile int phase;
    private CompletableFuture<Void> future;
    private volatile boolean terminal;
    private String nonce;
    private Path evidence;
    private long processStart;
    private long monotonicStart,initialAge;
    private volatile boolean startupReady;
    private boolean callerStarted,ownerQueued,bQueued,firstReleased;
    private Thread caller;
    @Override public void onInitialize(){
        if(!M6SettingsTraceObserver.enabled())return;
        processStart=ProcessHandle.current().info().startInstant()
                .orElseThrow(()->new IllegalStateException("LT_PROCESS_START_UNAVAILABLE")).toEpochMilli();
        monotonicStart=System.nanoTime();initialAge=Instant.now().toEpochMilli()-processStart;
        nonce=System.getProperty("quantumchamber.lt.nonce","");
        if(!nonce.matches("[a-f0-9]{32}"))throw new IllegalStateException("LT_INVALID_NONCE");
        evidence=Path.of(System.getProperty("quantumchamber.lt.evidence","")).toAbsolutePath().normalize();
        if(!Files.isDirectory(evidence)||!Files.isRegularFile(evidence.resolve("LAUNCH-MANIFEST.json")))throw new IllegalStateException("LT_MISSING_MANIFEST");
        M6SettingsTraceObserver.configure(evidence);
        runtimeSources();
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new M6SettingsTraceFailureListener());
        ServerLifecycleEvents.SERVER_STARTED.register(s->{server=s;});
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((s,rm)->M6SettingsTraceObserver.fabricStart());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s,rm,success)->M6SettingsTraceObserver.fabricEnd(success));
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        Thread watchdog=new Thread(this::watch,"M6-LT-watchdog");watchdog.setDaemon(false);watchdog.start();
    }
    private void runtimeSources(){
        try{
            Map<String,String> sources=new LinkedHashMap<>();
            for(Class<?> type:List.of(MinecraftServer.class,DataPackContents.class,M6SettingsTraceProbe.class,SessionRecoveryState.class)){
                String location=type.getProtectionDomain().getCodeSource().getLocation().toURI().normalize().toString();
                if(location.length()>1024)throw new IllegalStateException("LT_SOURCE_CAP");sources.put(type.getName(),location);
            }
            Map<String,Object> receipt=Map.of("scope","testmod-present","nonce",nonce,"namespace",FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace(),"sources",sources);
            Files.writeString(evidence.resolve("runtime-sources.json"),new Gson().toJson(receipt),StandardOpenOption.CREATE_NEW);
        }catch(Exception error){M6SettingsTraceObserver.fail("RUNTIME_SOURCE_FAILED");}
    }
    private long age(){return M6SettingsTraceObserver.lt2()?initialAge+(System.nanoTime()-monotonicStart)/1_000_000L:Instant.now().toEpochMilli()-processStart;}
    private void tick(MinecraftServer s){
        if(M6SettingsTraceObserver.lt2()){tickLt2(s);return;}
        if(s!=server||terminal)return;
        try{
            if(M6SettingsTraceObserver.failed()){stop(s);return;}
            if(phase==0){if(!M6SettingsTraceObserver.drain(0))return;launch(s,1);return;}
            if(future==null||!future.isDone())return;
            boolean success=true;Throwable failure=null;
            try{future.getNow(null);}catch(CompletionException e){success=false;failure=e;}
            if(success!=(phase!=2)){M6SettingsTraceObserver.fail("DRIVER_RESULT");stop(s);return;}
            if(phase==2){boolean controlled=false;for(Throwable c=failure;c!=null;c=c.getCause())if("LT_CONTROLLED_FAILURE".equals(c.getMessage()))controlled=true;if(!controlled){M6SettingsTraceObserver.fail("WRONG_FAILURE");stop(s);return;}}
            if(phase==2)M6SettingsTraceObserver.failureSnapshot(s,future);
            if(!M6SettingsTraceObserver.drain(phase))return;
            if(phase==3){stop(s);return;}launch(s,phase+1);
        }catch(RuntimeException error){M6SettingsTraceObserver.fail("DRIVER_EXCEPTION");stop(s);}
    }
    private void launch(MinecraftServer s,int token){
        phase=token;
        List<String> packs=token==3?List.of("vanilla","file/lt-a"):List.of("vanilla","file/lt-a","file/lt-b");
        future=s.reloadResources(packs);
    }
    private void stop(MinecraftServer s){if(M6SettingsTraceObserver.lt2())M6SettingsTraceObserver.cancelOwn();terminal=true;M6SettingsTraceObserver.terminal();s.stop(false);}
    private void watch(){
        if(M6SettingsTraceObserver.lt2()){watchLt2();return;}
        try{
            while(age()<240_000){
                long now=age();MinecraftServer s=M6SettingsTraceObserver.serverForStop();
                boolean cancel=false;Path request=evidence.resolve("cancel.request");
                if(Files.exists(request)){if(Files.size(request)>128)M6SettingsTraceObserver.fail("CANCEL_SIZE");else cancel=Files.readString(request).equals(nonce);}
                int p=phase;long deadline=p==0?60_000:p==1?95_000:p==2?125_000:155_000;
                if(cancel||M6SettingsTraceObserver.failed()||(!terminal&&now>=deadline)||now>=180_000){M6SettingsTraceObserver.fail("WATCHDOG_STOP");if(s!=null)stop(s);}
                M6SettingsTraceObserver.writeFinalIfReady();
                if(M6SettingsTraceObserver.finalized())return;
                Thread.sleep(200);
            }
            M6SettingsTraceObserver.fail("GRACE_EXPIRED");System.err.println("LT_GRACE_EXPIRED_OWNER_RETAINED");
        }catch(Exception error){M6SettingsTraceObserver.fail("WATCHDOG_EXCEPTION");System.err.println("LT_LATE_AFTER_FINAL_FAIL");MinecraftServer s=server;if(s!=null)stop(s);}
    }

    private void tickLt2(MinecraftServer s){
        if(s!=server||terminal)return;
        try{
            if(M6SettingsTraceObserver.failed()){stop(s);return;}
            if(!startupReady){if(!M6SettingsTraceObserver.drain(0))return;startupReady=true;if(age()>=60_000){M6SettingsTraceObserver.fail("STARTUP_DEADLINE");stop(s);return;}}
            var scenario=M6SettingsTraceObserver.scenario();
            if(phase==0&&scenario!=M6SettingsTraceObserver.Scenario.OFFTHREAD&&scenario!=M6SettingsTraceObserver.Scenario.STOP_PENDING){
                phase=1;callReload(s,1);return;
            }
            if(scenario==M6SettingsTraceObserver.Scenario.PARTIAL&&M6SettingsTraceObserver.invocationExited(1)){
                var f=M6SettingsTraceObserver.originalFuture(1);if(f!=null&&f.isDone())M6SettingsTraceObserver.partialSnapshot(s);
            }
            if(scenario!=M6SettingsTraceObserver.Scenario.STOP_PENDING&&M6SettingsTraceObserver.lt2Complete())stop(s);
        }catch(Throwable e){M6SettingsTraceObserver.fail("LT2_DRIVER_EXCEPTION");stop(s);}
    }
    private void callReload(MinecraftServer s,int token){
        if(M6SettingsTraceObserver.cancelledOrStopping())return;
        // C 類 packs 當次傳入；driver 不另存 collection 或替代原 future。
        s.reloadResources(token==1?List.of("vanilla","file/lt-a","file/lt-b"):List.of("vanilla","file/lt-a"));
    }
    private void startCaller(MinecraftServer s){
        callerStarted=true;phase=1;
        caller=new Thread(()->{try{if(!M6SettingsTraceObserver.cancelledOrStopping())callReload(s,1);}catch(Throwable e){M6SettingsTraceObserver.fail("LT2_CALLER_THROW");}finally{if(M6SettingsTraceObserver.failed())M6SettingsTraceObserver.cancelOwn();}},"M6-LT2-caller");
        caller.setDaemon(false);caller.start();
    }
    private void driveLt2(MinecraftServer s){
        var scenario=M6SettingsTraceObserver.scenario();
        if(startupReady&&!callerStarted&&(scenario==M6SettingsTraceObserver.Scenario.STOP_PENDING||(scenario==M6SettingsTraceObserver.Scenario.OFFTHREAD&&age()>=60_000)))startCaller(s);
        if(scenario==M6SettingsTraceObserver.Scenario.OFFTHREAD&&!ownerQueued&&M6SettingsTraceObserver.callerHeld()){
            ownerQueued=true;s.execute(()->{if(!M6SettingsTraceObserver.cancelledOrStopping())M6SettingsTraceObserver.ownerBeforeDispatch(s);});
        }
        if((scenario==M6SettingsTraceObserver.Scenario.OVERLAP_AB||scenario==M6SettingsTraceObserver.Scenario.OVERLAP_BA)&&!bQueued&&M6SettingsTraceObserver.canQueueB()){
            bQueued=true;s.execute(()->{if(!M6SettingsTraceObserver.cancelledOrStopping()&&M6SettingsTraceObserver.nestedOwner(s))callReload(s,2);});
        }
        if(!M6SettingsTraceObserver.gatesReached())return;
        switch(scenario){
            case OFFTHREAD,PARTIAL -> M6SettingsTraceObserver.releaseGate(1);
            case OVERLAP_AB,OVERLAP_BA -> {
                int first=scenario==M6SettingsTraceObserver.Scenario.OVERLAP_AB?1:2;
                if(!firstReleased){firstReleased=true;M6SettingsTraceObserver.releaseGate(first);}
                if(M6SettingsTraceObserver.sampleOriginalPair())M6SettingsTraceObserver.releaseGate(3-first);
            }
            case STOP_PENDING -> {
                if(M6SettingsTraceObserver.stopPendingReady()){
                    // 正常 STOP_PENDING 由真正 shutdown HEAD release gate，不提前放行。
                    terminal=true;M6SettingsTraceObserver.terminal();s.stop(false);
                }
            }
        }
    }
    private boolean phaseExpired(long now){
        if(!startupReady)return now>=60_000;
        var scenario=M6SettingsTraceObserver.scenario();
        if(scenario==M6SettingsTraceObserver.Scenario.OFFTHREAD)return !callerStarted?now>=65_000:now>=110_000&&!M6SettingsTraceObserver.lt2Complete();
        if(scenario==M6SettingsTraceObserver.Scenario.STOP_PENDING)return !terminal&&now>=90_000;
        if(scenario==M6SettingsTraceObserver.Scenario.PARTIAL)return now>=110_000&&!M6SettingsTraceObserver.lt2Complete();
        if(!M6SettingsTraceObserver.gatesReached())return now>=90_000;
        if(!M6SettingsTraceObserver.pairSample())return now>=115_000;
        return now>=140_000&&!M6SettingsTraceObserver.lt2Complete();
    }
    private void watchLt2(){
        try{
            while(age()<240_000){
                long now=age();MinecraftServer s=M6SettingsTraceObserver.serverForStop();boolean cancel=false;
                Path request=evidence.resolve("cancel.request");
                if(Files.exists(request)){if(Files.size(request)>128)M6SettingsTraceObserver.fail("CANCEL_SIZE");else cancel=Files.readString(request).equals(nonce);}
                if(cancel||M6SettingsTraceObserver.failed()||(!terminal&&phaseExpired(now))||now>=180_000){M6SettingsTraceObserver.fail("LT2_WATCHDOG_STOP");M6SettingsTraceObserver.cancelOwn();if(s!=null)stop(s);}
                else if(s!=null&&!terminal)driveLt2(s);
                M6SettingsTraceObserver.writeFinalIfReady();
                if(M6SettingsTraceObserver.finalized())return;
                Thread.sleep(20);
            }
            M6SettingsTraceObserver.fail("GRACE_EXPIRED");System.err.println("LT_GRACE_EXPIRED_OWNER_RETAINED");
        }catch(Throwable e){M6SettingsTraceObserver.fail("LT2_WATCHDOG_EXCEPTION");System.err.println("LT_LATE_AFTER_FINAL_FAIL");}
        finally{
            M6SettingsTraceObserver.cancelOwn();MinecraftServer s=M6SettingsTraceObserver.serverForStop();
            if(s!=null&&!M6SettingsTraceObserver.stoppedSeen())stop(s);
            // 不 join／清除仍在執行的 caller；其 owner 交外層 PID／lock 判定。
            if(caller!=null&&caller.isAlive())System.err.println("LT_CALLER_OWNER_RETAINED_FAIL");
        }
    }
}
