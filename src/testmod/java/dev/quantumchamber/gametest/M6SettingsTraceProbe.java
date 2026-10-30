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
    private final long processStart=ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
    @Override public void onInitialize(){
        if(!M6SettingsTraceObserver.enabled())return;
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
    private long age(){return Instant.now().toEpochMilli()-processStart;}
    private void tick(MinecraftServer s){
        if(s!=server||terminal)return;
        try{
            if(M6SettingsTraceObserver.failed()){stop(s);return;}
            if(phase==0){if(!M6SettingsTraceObserver.drain(0))return;launch(s,1);return;}
            if(future==null||!future.isDone())return;
            boolean success=true;Throwable failure=null;
            try{future.getNow(null);}catch(CompletionException e){success=false;failure=e;}
            if(success!=(phase!=2)){M6SettingsTraceObserver.fail("DRIVER_RESULT");stop(s);return;}
            if(phase==2){boolean controlled=false;for(Throwable c=failure;c!=null;c=c.getCause())if("LT_CONTROLLED_FAILURE".equals(c.getMessage()))controlled=true;if(!controlled){M6SettingsTraceObserver.fail("WRONG_FAILURE");stop(s);return;}}
            if(!M6SettingsTraceObserver.drain(phase))return;
            if(phase==3){stop(s);return;}launch(s,phase+1);
        }catch(RuntimeException error){M6SettingsTraceObserver.fail("DRIVER_EXCEPTION");stop(s);}
    }
    private void launch(MinecraftServer s,int token){
        phase=token;
        List<String> packs=token==3?List.of("vanilla","file/lt-a"):List.of("vanilla","file/lt-a","file/lt-b");
        future=s.reloadResources(packs);
    }
    private void stop(MinecraftServer s){terminal=true;M6SettingsTraceObserver.terminal();s.stop(false);}
    private void watch(){
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
}
