package dev.quantumchamber.superposition;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
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
    SuperpositionSession(ServerWorld source,ChamberControllerBlockEntity controller,ChamberFrame frame,
            QuantumEffectTransaction effects,CorridorPageManager.PreparedMappings prepared,SessionRecoveryRecord initial) {
        this.source=source; this.controller=controller; this.sourceFrame=frame;
        this.effects=effects; this.prepared=prepared; this.initial=initial;
    }
}
