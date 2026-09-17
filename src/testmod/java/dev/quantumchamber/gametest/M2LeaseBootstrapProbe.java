package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.DimensionRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.LoggerFactory;

/** 僅 testmod：明確開關、canonical own nonce root 與 CREATE_NEW 的啟動拒絕 fixture。 */
public final class M2LeaseBootstrapProbe implements ModInitializer {
    private static final String CASE=System.getProperty("quantumchamber.m2.lease.case");
    private static Path journal;
    private static String beforeHash;
    private static int ticks;

    @Override public void onInitialize() {
        if (CASE==null) return;
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            ticks++;
            if (ticks==1) {
                LoggerFactory.getLogger("quantumchamber-testmod").error("LEASE_PROBE_UNEXPECTED_NORMAL_TICK case={} ticks={}",CASE,ticks);
                // 只停止此明確啟用、owned fixture JVM；不觸碰其他程序。
                server.stop(false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                String after=journal==null ? "missing" : hash(journal);
                LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PROBE_FINISH case={} ticks={} unchanged={} afterSha256={}",
                        CASE,ticks,beforeHash!=null && beforeHash.equals(after),after);
            } catch (Exception failure) { throw new IllegalStateException("fixture after-hash 無法確認",failure); }
        });
    }

    public static void beforeAuthorityAttach(MinecraftServer server) {
        if (CASE==null) return;
        if (!Set.of("shape","height","facing","huge","global-cap","world-border").contains(CASE)) {
            throw new IllegalArgumentException("未知 lease fixture case");
        }
        try {
            if (journal!=null || !server.isOnThread()) throw new IllegalStateException("fixture 只能在首次loadWorld結尾執行");
            var actual=server.getSavePath(WorldSavePath.ROOT).toRealPath();
            var expected=Path.of(System.getProperty("quantumchamber.m2.lease.root")).toRealPath();
            if (!actual.equals(expected) || !actual.getFileName().toString().equals("world")
                    || !actual.getParent().getFileName().toString().matches("m2-lease-bootstrap-[a-z0-9-]+")) {
                throw new IllegalStateException("fixture canonical owned nonce root 不符");
            }
            var players=actual.resolve("playerdata");
            if (Files.isDirectory(players)) try(var entries=Files.list(players)) {
                if (entries.findAny().isPresent()) throw new IllegalStateException("fixture 不允許既有玩家資料");
            }
            UUID sid=UUID.randomUUID(), chamber=UUID.randomUUID();
            var facing=CASE.equals("facing") ? Direction.EAST : Direction.NORTH;
            var origin=new ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                    new BlockPos(0,70,0),facing,ChamberInstanceKind.ORIGIN);
            var sourcePosition=facing==Direction.EAST ? new Vec3d(-2.5,65,.5) : new Vec3d(.5,65,3.5);
            var person=new SessionRecoveryRecord.Participant(UUID.randomUUID(),sourcePosition,Vec3d.ZERO,0,0,
                    (NbtCompound)new StatusEffectInstance(ModEffects.QUANTUM_STATE,1200).writeNbt(),false);
            var leases=new ArrayList<SessionRecoveryRecord.SpaceLease>();
            if (CASE.equals("global-cap")) {
                for(int i=0;i<9;i++) leases.add(new SessionRecoveryRecord.SpaceLease(i,new BlockBox(997+192*i,64,1328,1003+192*i,70,2767)));
            } else {
                var bounds=switch(CASE) {
                    case "shape" -> new BlockBox(997,64,1328,1005,70,2767);
                    case "height" -> new BlockBox(997,-1,1328,1003,5,2767);
                    case "huge" -> new BlockBox(997,64,0,1003,70,1_000_000);
                    default -> new BlockBox(997,64,1328,1003,70,2767);
                };
                leases.add(new SessionRecoveryRecord.SpaceLease(0,bounds));
            }
            if (CASE.equals("world-border")) {
                var world=server.getWorld(SuperpositionWorld.KEY);
                if (world==null) throw new IllegalStateException("fixture 需要真固定世界");
                world.getWorldBorder().setCenter(0,0); world.getWorldBorder().setSize(128);
            }
            var codec=new SessionRecoveryState();
            codec.put(new SessionRecoveryRecord(sid,chamber,origin,List.of(person),leases,SessionState.ARMING,true));
            var wrapped=new NbtCompound(); wrapped.put("data",codec.writeNbt(new NbtCompound())); NbtHelper.putDataVersion(wrapped);
            var path=actual.resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat");
            Files.createDirectories(path.getParent());
            try(var output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                NbtIo.writeCompressed(wrapped,output);
            }
            journal=path; beforeHash=hash(path);
            LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PROBE_INSTALLED case={} savePath={} beforeSha256={}",CASE,path,beforeHash);
        } catch (Exception failure) { throw new IllegalStateException("[LEASE_PROBE_SETUP_FAILED] lease bootstrap fixture 安裝失敗",failure); }
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
