package dev.quantumchamber.superposition;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.universe.DimensionRole;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import dev.quantumchamber.transfer.QuantumEffectTransaction;
import net.minecraft.server.world.ServerWorld;

/** 僅限 attached server 的 runtime 入場交易；恢復權威仍在 checked journal。 */
public final class SuperpositionSession {
    final ServerWorld source;
    final ChamberControllerBlockEntity controller;
    final ChamberFrame sourceFrame;
    final QuantumEffectTransaction effects;
    final CorridorPageManager.PreparedMappings prepared;
    final SessionRecoveryRecord initial;
    SessionState state=SessionState.ARMING;
    /** 先驗 discovery 與 entropy，再允許任何 session／space reservation。 */
    static CandidatePolicySnapshot freezeCandidateContext(MinecraftServer server, ServerWorld source) throws IOException {
        var journal=SessionRecoveryState.get(server);
        var records=java.util.stream.Stream.concat(journal.records().values().stream(),journal.flushedRecords().values().stream()).toList();
        if(records.stream().anyMatch(record -> record.candidateContext().isEmpty()))
            throw new IllegalStateException("legacy session 尚待 return-only 清理，禁止混裝 M4 session");
        boolean candidateEvidence=records.stream().anyMatch(record -> record.candidateContext().isPresent());
        var root=server.getSavePath(WorldSavePath.ROOT);
        var discovery=candidateEvidence ? UniverseDiscoveryState.load(root) : UniverseDiscoveryState.loadOrCreate(root);
        var entropy=CandidateEntropyState.loadOrCreate(root,candidateEvidence || !discovery.records().isEmpty());
        var role=DimensionRole.fromVanillaKey(source.getRegistryKey()).orElseThrow();
        return new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"),1,1,5,20,75,discovery.watermark(),16384,
                entropy.entropyFingerprint(),new SourceFamilyRef.Vanilla(source.getRegistryKey(),role));
    }
    SuperpositionSession(ServerWorld source,ChamberControllerBlockEntity controller,ChamberFrame frame,
            QuantumEffectTransaction effects,CorridorPageManager.PreparedMappings prepared,SessionRecoveryRecord initial) {
        this.source=source; this.controller=controller; this.sourceFrame=frame;
        this.effects=effects; this.prepared=prepared; this.initial=initial;
    }
}
