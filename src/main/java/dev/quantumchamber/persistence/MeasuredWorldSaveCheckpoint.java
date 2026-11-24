package dev.quantumchamber.persistence;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;

/**
 * 空 lease 前的原生持久化閘門：只看本交易相關 chunks（來源艙與走廊 lease）所在的 region，見 {@link ChunkSaveCheckpoint}。
 * MEASURED 與 COLLAPSING 的 RELEASE_GEOMETRY 共用（{@link MeasuredRecoveryPhase} 的同一份推導，spec §13.2）。
 * <p>{@link #ready} 與 {@link #save} 用同一份相關範圍（{@link #relevant}）：呼叫端在 save 之前先問 ready，未就緒時等下一個 tick（M5 F6-0，G-NLIVE 的根因：走廊清理只對走廊的
 * lease 問 {@code leaseReady}，來源艙的 chunks 沒有人先問，entity storage 還沒載入時 save 丟出「相關 chunk／entity storage 尚未完整載入」）。
 */
public final class MeasuredWorldSaveCheckpoint {
    private MeasuredWorldSaveCheckpoint() { }
    public static void save(MinecraftServer server,SessionRecoveryRecord record) {
        ChunkSaveCheckpoint.save(server,relevant(server,record));
    }
    /**
     * {@link #save} 的前置條件是否成立（F6-0）：相關範圍的每一個 chunk（來源艙與走廊 lease）都已載入為 WorldChunk，而且 entity storage 已載入（{@link ChunkSaveCheckpoint#ready}）。
     * 只查詢、不載入、不存檔；守門與 {@link #save} 相同（server thread、全員返還後的 RELEASE_GEOMETRY）。
     */
    public static boolean ready(MinecraftServer server,SessionRecoveryRecord record) {
        return relevant(server,record).entrySet().stream().allMatch(entry -> ChunkSaveCheckpoint.ready(entry.getKey(),entry.getValue()));
    }
    private static Map<ServerWorld,Set<ChunkPos>> relevant(MinecraftServer server,SessionRecoveryRecord record) {
        if(!server.isOnThread() || MeasuredRecoveryPhase.from(record)!=MeasuredRecoveryPhase.RELEASE_GEOMETRY)
            throw new IllegalStateException("世界 checkpoint 必須在全員返還後的 server thread");
        var source=Objects.requireNonNull(server.getWorld(RegistryKey.of(RegistryKeys.WORLD,record.origin().worldKey())));
        var corridor=Objects.requireNonNull(server.getWorld(SuperpositionWorld.KEY));
        if(source==corridor) throw new IllegalStateException("來源艙不得位於走廊世界");
        var sourceChunks=new HashSet<ChunkPos>(); addChunks(sourceChunks,ChamberGeometry.bounds(new ChamberFrame(record.origin().controllerPos(),record.origin().facing())));
        var corridorChunks=new HashSet<ChunkPos>(); record.spaceLeases().forEach(lease -> addChunks(corridorChunks,lease.bounds()));
        var relevant=new LinkedHashMap<ServerWorld,Set<ChunkPos>>(); relevant.put(source,sourceChunks); relevant.put(corridor,corridorChunks);
        return relevant;
    }
    /**
     * M7 HOME（plan Task 5 :325，T5-2）：與 {@link #save(MinecraftServer,SessionRecoveryRecord)} 相同的原生 checkpoint，相關範圍只有 exact live B 的艙體 frame 與走廊 leases；
     * A（Root）不入列。前提：server thread、CloseIntent=NONE、全員已返回 B、租約仍在（清除租約或移除熱列之前）；未選定的 RETURNING，或已選定的 MEASURED／COLLAPSING
     * （保留式清理，T5-4 起使用）。
     */
    public static void save(MinecraftServer server,HomeSessionRecord record) {
        ChunkSaveCheckpoint.save(server,relevant(server,record));
    }
    /** {@link #save(MinecraftServer,HomeSessionRecord)} 的前置條件是否成立：相關範圍的每一個 chunk 都已載入為 WorldChunk、entity storage 已載入。只查詢、不載入、不存檔。 */
    public static boolean ready(MinecraftServer server,HomeSessionRecord record) {
        return relevant(server,record).entrySet().stream().allMatch(entry -> ChunkSaveCheckpoint.ready(entry.getKey(),entry.getValue()));
    }
    private static Map<ServerWorld,Set<ChunkPos>> relevant(MinecraftServer server,HomeSessionRecord record) {
        var selected=record.home().selection() instanceof HomeSessionRecord.Selected;
        var phase=record.state()==dev.quantumchamber.superposition.SessionState.RETURNING && !selected
                || selected && (record.state()==dev.quantumchamber.superposition.SessionState.MEASURED
                        || record.state()==dev.quantumchamber.superposition.SessionState.COLLAPSING);
        if(!server.isOnThread() || !phase || !(record.home().closeIntent() instanceof HomeSessionRecord.None) || record.spaceLeases().isEmpty()
                || record.participants().stream().anyMatch(person -> !person.returned()))
            throw new IllegalStateException("HOME 世界 checkpoint 必須在全員返回 B 之後、清除租約之前的 server thread");
        var entry=record.entryEndpoint();
        var world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,entry.worldKey()));
        if(world==null || dev.quantumchamber.universe.UniverseLifecycleService.resolveActive(server,entry.universeId()).orElse(null)!=world)
            throw new IllegalStateException("HOME checkpoint 的 B 不是 exact live 世界");
        var corridor=Objects.requireNonNull(server.getWorld(SuperpositionWorld.KEY));
        if(world==corridor) throw new IllegalStateException("B 不得位於走廊世界");
        var entryChunks=new HashSet<ChunkPos>(); addChunks(entryChunks,ChamberGeometry.bounds(new ChamberFrame(entry.controllerPos(),entry.facing())));
        var corridorChunks=new HashSet<ChunkPos>(); record.spaceLeases().forEach(lease -> addChunks(corridorChunks,lease.bounds()));
        var relevant=new LinkedHashMap<ServerWorld,Set<ChunkPos>>(); relevant.put(world,entryChunks); relevant.put(corridor,corridorChunks);
        return relevant;
    }
    private static void addChunks(Set<ChunkPos> chunks,BlockBox bounds) {
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++)
            for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) chunks.add(new ChunkPos(x,z));
    }
}
