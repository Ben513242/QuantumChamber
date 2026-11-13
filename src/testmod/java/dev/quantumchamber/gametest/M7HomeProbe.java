package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.universe.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

/** T1 僅驗證用原生程序；不註冊正式 HOME owner 或放寬 B activation。 */
public final class M7HomeProbe implements ModInitializer {
    private final Map<String,Object> proof=new LinkedHashMap<>();
    private Path root,evidence;
    private String phase,chain;
    private M7CloseSpikePhases close;
    private boolean finished;
    @Override public void onInitialize() {
        String nonce=System.getProperty("quantumchamber.m7home.nonce","");
        if(nonce.isEmpty()){
            if(Boolean.getBoolean("quantumchamber.test.m7Home")&&System.getProperty("fabric-api.gametest")!=null){
                ServerLifecycleEvents.SERVER_STARTED.register(server->{
                    try {
                        Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("scope","m7_home");runtime.put("head",System.getProperty("quantumchamber.m7game.head"));runtime.put("nonce",System.getProperty("quantumchamber.m7game.nonce"));runtime.put("root",Path.of("").toRealPath().toString());runtime.put("pid",ProcessHandle.current().pid());runtime.put("startTimeUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
                        runtime.put("flags",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());runtime.put("loadedMods",FabricLoader.getInstance().getAllMods().stream().map(m->m.getMetadata().getId()).sorted().toList());
                        Files.writeString(Path.of("m7-gametest-runtime.json"),new Gson().toJson(runtime),StandardOpenOption.CREATE_NEW);
                    }catch(Exception failure){throw new IllegalStateException("M7_RUNTIME_RECEIPT",failure);}
                });
            }
            return;
        }
        try {
            require(Boolean.getBoolean("quantumchamber.test.m7Home"),"M7_FLAG");
            root=Path.of(System.getProperty("quantumchamber.m7home.root")).toRealPath();
            require(root.equals(Path.of("").toRealPath()),"M7_CWD");
            require(root.getFileName().toString().equals("m7-home-"+nonce),"M7_ROOT");
            phase=System.getProperty("quantumchamber.m7home.phase");chain=System.getProperty("quantumchamber.m7home.chain");
            require(Set.of("crash","reopen").contains(phase),"M7_PHASE");
            require(chain.matches("spike-close-(ordinary|sealed|passable)-[a-z-]+"),"M7_CHAIN");
            evidence=root.resolve("evidence").resolve(phase);Files.createDirectories(evidence);
            require(com.sun.jna.Platform.isWindows(),"M7_NATIVE_WINDOWS_ONLY");proof.put("platform","Windows");
            proof.put("schema",1);proof.put("proofOfConcept",true);proof.put("runtimeScope","testmod-present");
            proof.put("head",System.getProperty("quantumchamber.m7home.head"));proof.put("nonce",nonce);proof.put("chain",chain);proof.put("phase",phase);proof.put("root",root.toString());
            proof.put("startupNonce",System.getProperty("quantumchamber.m7home.startupNonce"));
            proof.put("pid",ProcessHandle.current().pid());proof.put("startTimeUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
            proof.put("javaExecutable",ProcessHandle.current().info().command().orElseThrow());
            proof.put("loadedMods",FabricLoader.getInstance().getAllMods().stream().map(m->m.getMetadata().getId()).sorted().toList());
            durable("startup.json");
            var after=Identifier.of("quantumchamber:m7_after");ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE,after);
            ServerLifecycleEvents.SERVER_STARTED.register(after,this::started);
            ServerTickEvents.END_SERVER_TICK.register(server->{if(finished||close==null)return;try{if(close.tick(server)){finished=true;finish(server);}}catch(Throwable failure){failed(failure);}});
        } catch(Throwable failure){failed(failure);}
    }
    private void started(MinecraftServer server) {
        try {
            require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()),"M7_SAVE_ROOT");
            var catalog=UniverseRegistryState.get(server);var id=UniverseId.of(M7CloseSpikePhases.UNIVERSE);
            if(phase.equals("crash")){catalog.allocateOverworld(id.value(),0);catalog.flush(server);}
            var outcome=UniverseLifecycleService.materializeAllocated(server,id);
            var world=outcome.world().orElseThrow(()->new IllegalStateException("M7_B_WORLD "+outcome));
            require(UniverseLifecycleService.resolveActive(server,id).orElseThrow()==world,"M7_B_IDENTITY");
            close=new M7CloseSpikePhases(root,chain,phase,world,proof,()->{proof.put("status","PASS");proof.put("expectedExit",77);durable("receipt.json");Runtime.getRuntime().halt(77);});
            close.start(server);
        }catch(Throwable failure){failed(failure);}
    }
    private void finish(MinecraftServer server)throws Exception {
        proof.put("status","PASS");proof.put("expectedExit",0);durable("receipt.json");server.stop(false);
    }
    private void failed(Throwable failure) {
        failure.printStackTrace();proof.put("status","FAIL");proof.put("failure",failure.toString());
        try{if(evidence!=null)durable("failure.json");}catch(Exception secondary){failure.addSuppressed(secondary);}
        Runtime.getRuntime().halt(78);
    }
    private void durable(String file)throws java.io.IOException {
        try{publishDurable(evidence.resolve(file),new Gson().toJson(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8),ignored->{});}
        catch(java.io.IOException failure){throw failure;}catch(Exception failure){throw new java.io.IOException("M7_RECEIPT_PUBLICATION",failure);}
    }
    /** test-only：published 名稱出現前，完整 bytes 已寫入並 force；既有目標一律不覆寫。 */
    public static void publishDurable(Path target,byte[] bytes,BeforeWrite beforeWrite)throws Exception {
        Path temporary=Files.createTempFile(target.getParent(),target.getFileName()+".pending-",".tmp");
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){
            beforeWrite.run(temporary);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        Files.createLink(target,temporary);
    }
    @FunctionalInterface public interface BeforeWrite {void run(Path temporary)throws Exception;}
    static void require(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
}
