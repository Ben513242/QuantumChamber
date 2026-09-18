package dev.quantumchamber.transfer;

import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.registry.ModEffects;
import java.util.*;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;

/** 不可變 NBT 保存包含 hiddenEffect；新入場只核對，明確 legacy 才消耗或恢復。 */
public final class QuantumEffectTransaction {
    private final List<SessionRecoveryRecord.Participant> sources;
    public QuantumEffectTransaction(List<ServerPlayerEntity> players) {
        var snapshots=new ArrayList<SessionRecoveryRecord.Participant>();
        var seen=new HashSet<UUID>();
        for(var player : players) {
            if (!seen.add(player.getUuid()) || !player.getServer().isOnThread()) throw new IllegalArgumentException("cohort／執行緒不合法");
            var effect=player.getStatusEffect(ModEffects.QUANTUM_STATE);
            if (effect==null) throw new IllegalArgumentException("全員必須持有 QuantumState");
            snapshots.add(new SessionRecoveryRecord.Participant(player.getUuid(),player.getPos(),player.getVelocity(),player.getYaw(),
                    player.getPitch(),(NbtCompound)effect.writeNbt(),false));
        }
        if(snapshots.isEmpty()) throw new IllegalArgumentException("cohort 不得為空");
        sources=List.copyOf(snapshots);
    }
    public List<SessionRecoveryRecord.Participant> snapshots() { return sources; }
    /** 只驗證完整原生玩家與目前效果；不移除、重給或改寫倒數及 hidden chain。 */
    public void verifyCurrent(MinecraftServer server) {
        requireThread(server);
        for(var source : sources) {
            var player=server.getPlayerManager().getPlayer(source.playerUuid());
            if(player==null || player.getServer()!=server || !player.isAlive() || player.isRemoved() || player.isSpectator()
                    || server.getWorld(player.getServerWorld().getRegistryKey())!=player.getServerWorld()
                    || player.getServerWorld().getEntity(source.playerUuid())!=player
                    || !player.hasStatusEffect(ModEffects.QUANTUM_STATE))
                throw new IllegalStateException("完整cohort的目前QuantumState或玩家身分失效");
        }
    }
    public void commit(MinecraftServer server) {
        requireThread(server);
        for(var source : sources) {
            var player=server.getPlayerManager().getPlayer(source.playerUuid());
            if(player==null || !player.hasStatusEffect(ModEffects.QUANTUM_STATE)) throw new IllegalStateException("消耗前全員資格改變");
        }
        for(var source : sources) {
            var player=server.getPlayerManager().getPlayer(source.playerUuid());
            if(!player.removeStatusEffect(ModEffects.QUANTUM_STATE) || player.hasStatusEffect(ModEffects.QUANTUM_STATE))
                throw new IllegalStateException("整群效果消耗失敗");
        }
    }
    public boolean restore(MinecraftServer server) {
        requireThread(server); boolean complete=true;
        for(var source : sources) {
            var player=server.getPlayerManager().getPlayer(source.playerUuid());
            if(player==null) { complete=false; continue; }
            var restored=StatusEffectInstance.fromNbt(source.quantumStateSnapshot());
            if(restored==null) { complete=false; continue; }
            player.removeStatusEffect(ModEffects.QUANTUM_STATE);
            player.addStatusEffect(restored);
            var actual=player.getStatusEffect(ModEffects.QUANTUM_STATE);
            complete &= actual!=null && source.quantumStateSnapshot().equals(actual.writeNbt());
        }
        return complete;
    }
    private static void requireThread(MinecraftServer server) {
        if(!server.isOnThread()) throw new IllegalStateException("效果交易必須在 server thread");
    }
}
