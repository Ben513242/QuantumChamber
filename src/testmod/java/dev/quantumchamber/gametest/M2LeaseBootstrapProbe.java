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
    private static MinecraftServer partialServer;
    private static net.minecraft.server.world.ServerChunkManager partialManager;
    private static UUID partialSid,sentinelSid;
    private static net.minecraft.server.world.ChunkTicketType<UUID> partialType;
    private static net.minecraft.util.math.ChunkPos sentinelChunk;
    private static Set<TicketWitness> otherTickets=Set.of();
    private static int successfulAdds,successfulRemoves;
    private static boolean partialHit,cleanupVerified;

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
                if("partial-acquire".equals(CASE)) {
                    requirePartial(partialHit && cleanupVerified && successfulAdds==1 && successfulRemoves==1,"部分取得必須有真 hit／membership／對稱清理");
                    LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PARTIAL_VERIFIED targetSid={} sentinelSid={} successfulAdds={} successfulRemoves={} nativeTarget=0 sentinelPreserved=true ticks={}",
                            partialSid,sentinelSid,successfulAdds,successfulRemoves,ticks);
                    partialServer=null; partialManager=null; partialType=null; otherTickets=Set.of();
                }
            } catch (Exception failure) { throw new IllegalStateException("fixture after-hash 無法確認",failure); }
        });
    }

    public static void beforeAuthorityAttach(MinecraftServer server) {
        if (CASE==null) return;
        if (!Set.of("shape","height","facing","huge","global-cap","world-border","partial-acquire").contains(CASE)) {
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
            if(CASE.equals("partial-acquire")) {
                var nonce=System.getProperty("quantumchamber.m2.nonce");
                requirePartial(nonce!=null && nonce.matches("[a-f0-9]{32}")
                        && actual.getParent().getFileName().toString().equals("m2-lease-bootstrap-partial-acquire-"+nonce),"partial own nonce root 不符");
                partialServer=server; partialManager=server.getWorld(SuperpositionWorld.KEY).getChunkManager(); partialSid=sid; sentinelSid=UUID.randomUUID();
            }
            LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PROBE_INSTALLED case={} savePath={} beforeSha256={}",CASE,path,beforeHash);
        } catch (Exception failure) { throw new IllegalStateException("[LEASE_PROBE_SETUP_FAILED] lease bootstrap fixture 安裝失敗",failure); }
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record TicketWitness(long chunk,UUID sid,int level) {}

    /** 原生集合僅讀；回傳 immutable 快照，絕不回寫 ticketsByPosition。 */
    private static Set<TicketWitness> nativeTickets() {
        var manager=partialManager.chunkLoadingManager.getTicketManager();
        var entries=((dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor)manager).quantumchamberTest$getTicketsByPosition();
        var result=new java.util.HashSet<TicketWitness>();
        for(var entry : entries.long2ObjectEntrySet()) for(var ticket : entry.getValue()) if(ticket.getType()==partialType) {
            var argument=((dev.quantumchamber.gametest.mixin.ChunkTicketArgumentTestAccessor)(Object)ticket).quantumchamberTest$getArgument();
            requirePartial(argument instanceof UUID && ticket.getLevel()==31,"own radius2 ticket 原生 SID／level 不符");
            result.add(new TicketWitness(entry.getLongKey(),(UUID)argument,ticket.getLevel()));
        }
        return Set.copyOf(result);
    }

    private static boolean partialScope(net.minecraft.server.world.ServerChunkManager manager,net.minecraft.server.world.ChunkTicketType<?> type,int radius,Object argument) {
        if(!"partial-acquire".equals(CASE) || partialServer==null || manager!=partialManager || !partialSid.equals(argument)) return false;
        requirePartial(partialServer.isOnThread() && partialServer.getWorld(SuperpositionWorld.KEY).getChunkManager()==manager && radius==2,"partial native server/world/radius scope 不符");
        if(partialType!=null) requirePartial(partialType==type,"partial ticket type identity 不符");
        return true;
    }

    @SuppressWarnings("unchecked")
    public static void beforeTicket(net.minecraft.server.world.ServerChunkManager manager,net.minecraft.server.world.ChunkTicketType<?> type,
            net.minecraft.util.math.ChunkPos chunk,int radius,Object argument) {
        if(!partialScope(manager,type,radius,argument)) return;
        if(partialType==null) {
            partialType=(net.minecraft.server.world.ChunkTicketType<UUID>)type; sentinelChunk=chunk;
            manager.addTicket(partialType,chunk,2,sentinelSid); otherTickets=nativeTickets();
            requirePartial(otherTickets.contains(new TicketWitness(chunk.toLong(),sentinelSid,31)),"sentinel 必須真 native membership");
        }
        if(successfulAdds==1) {
            requirePartial(nativeTickets().stream().filter(ticket -> ticket.sid().equals(partialSid)).count()==1,"第二 add 前目標必須已真取得第一票");
            partialHit=true;
            LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PARTIAL_HIT targetSid={} sentinelSid={} firstNativePresent=true rejectedChunk={}",partialSid,sentinelSid,chunk);
            throw new IllegalStateException("[LEASE_PARTIAL_ACQUIRE] own SID 第一票成功後第二 add 受控拒絕");
        }
    }

    public static void afterTicket(net.minecraft.server.world.ServerChunkManager manager,net.minecraft.server.world.ChunkTicketType<?> type,
            net.minecraft.util.math.ChunkPos chunk,int radius,Object argument) {
        if(!partialScope(manager,type,radius,argument)) return;
        successfulAdds++;
        requirePartial(successfulAdds==1 && nativeTickets().contains(new TicketWitness(chunk.toLong(),partialSid,31)),"成功 add 的原生 membership 不符");
    }

    public static void afterRemoveTicket(net.minecraft.server.world.ServerChunkManager manager,net.minecraft.server.world.ChunkTicketType<?> type,
            net.minecraft.util.math.ChunkPos chunk,int radius,Object argument) {
        if(!partialScope(manager,type,radius,argument)) return;
        successfulRemoves++;
        requirePartial(partialHit && successfulRemoves==successfulAdds && nativeTickets().equals(otherTickets),"原生 cleanup 必須目標零票／其他 SID 完全不變");
        requirePartial(nativeTickets().contains(new TicketWitness(sentinelChunk.toLong(),sentinelSid,31)),"目標cleanup不可誤移除同chunk sentinel");
        LoggerFactory.getLogger("quantumchamber-testmod").info("LEASE_PARTIAL_NATIVE_CLEANUP targetSid={} nativeTarget=0 sentinelPreserved=true snapshot={}",partialSid,nativeTickets());
        try { cleanupVerified=true; }
        finally { manager.removeTicket(partialType,sentinelChunk,2,sentinelSid); }
        requirePartial(nativeTickets().stream().noneMatch(ticket -> ticket.sid().equals(partialSid) || ticket.sid().equals(sentinelSid)),"fixture 最後僅移除自己的 sentinel");
    }

    private static void requirePartial(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}
