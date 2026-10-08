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
    private static void addChunks(Set<ChunkPos> chunks,BlockBox bounds) {
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++)
            for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) chunks.add(new ChunkPos(x,z));
    }
}
