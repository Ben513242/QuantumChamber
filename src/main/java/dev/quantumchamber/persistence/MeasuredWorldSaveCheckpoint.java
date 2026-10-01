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
 */
public final class MeasuredWorldSaveCheckpoint {
    private MeasuredWorldSaveCheckpoint() { }
    public static void save(MinecraftServer server,SessionRecoveryRecord record) {
        if(!server.isOnThread() || MeasuredRecoveryPhase.from(record)!=MeasuredRecoveryPhase.RELEASE_GEOMETRY)
            throw new IllegalStateException("世界 checkpoint 必須在全員返還後的 server thread");
        var source=Objects.requireNonNull(server.getWorld(RegistryKey.of(RegistryKeys.WORLD,record.origin().worldKey())));
        var corridor=Objects.requireNonNull(server.getWorld(SuperpositionWorld.KEY));
        if(source==corridor) throw new IllegalStateException("來源艙不得位於走廊世界");
        var sourceChunks=new HashSet<ChunkPos>(); addChunks(sourceChunks,ChamberGeometry.bounds(new ChamberFrame(record.origin().controllerPos(),record.origin().facing())));
        var corridorChunks=new HashSet<ChunkPos>(); record.spaceLeases().forEach(lease -> addChunks(corridorChunks,lease.bounds()));
        var relevant=new LinkedHashMap<ServerWorld,Set<ChunkPos>>(); relevant.put(source,sourceChunks); relevant.put(corridor,corridorChunks);
        ChunkSaveCheckpoint.save(server,relevant);
    }
    private static void addChunks(Set<ChunkPos> chunks,BlockBox bounds) {
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++)
            for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) chunks.add(new ChunkPos(x,z));
    }
}
