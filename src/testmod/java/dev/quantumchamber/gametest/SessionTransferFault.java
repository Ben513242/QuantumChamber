package dev.quantumchamber.gametest;

import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.superposition.*;
import dev.quantumchamber.registry.ModEffects;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.math.Vec3d;

/** 預設關閉的 own SID／server／world／玩家限定故障，release 沒有此 fixture。 */
public final class SessionTransferFault implements AutoCloseable {
    public enum Case { SECOND_MOVE, SECOND_MOVE_INVALID_SOURCE, PUBLISH_AFTER_FALSE }
    private static final Map<MinecraftServer,SessionTransferFault> ACTIVE=new IdentityHashMap<>();
    static { ServerLifecycleEvents.SERVER_STOPPED.register(ACTIVE::remove); }
    private final MinecraftServer server;
    private final ServerWorld source,target;
    private final SessionRecoveryRecord initial;
    private final Case faultCase;
    private final Set<UUID> people;
    private final UUID affected;
    private boolean hit;
    private int successfulMoves;
    private boolean rollbackVerified;
    private int rollbackTick=-1;
    private int returnFlushes;
    private Map<UUID,NbtCompound> rollbackEffects=Map.of();
    private Map<UUID,CorridorPageManager.PhysicalPose> rollbackPoses=Map.of();
    private boolean falseVerified;
    private NbtCompound newEffect;
    private boolean sourceInvalidated;
    private CorridorPageManager.PhysicalPose firstMovedPose;
    public SessionTransferFault(ServerWorld source,SessionRecoveryRecord initial,Case faultCase) {
        this.source=source; this.server=source.getServer(); this.initial=initial; this.faultCase=faultCase;
        target=server.getWorld(SuperpositionWorld.KEY);
        people=initial.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        affected=initial.participants().get(faultCase==Case.PUBLISH_AFTER_FALSE ? 0 : 1).playerUuid();
        if(!server.isOnThread() || server.getWorld(source.getRegistryKey())!=source
                || !initial.origin().worldKey().equals(source.getRegistryKey().getValue())
                || initial.state()!=SessionState.ARMING || ACTIVE.putIfAbsent(server,this)!=null)
            throw new IllegalStateException("fault 必須屬於 own ARMING／來源身分，且不可重複安裝");
    }
    public static boolean rejectMove(ServerPlayerEntity player,ServerWorld target,Vec3d position) {
        var fault=ACTIVE.get(target.getServer());
        if(fault==null || !fault.scope(player,target,position) || fault.faultCase==Case.PUBLISH_AFTER_FALSE || fault.hit
                || !fault.affected.equals(player.getUuid()) || fault.successfulMoves!=1) return false;
        fault.hit=true; return true;
    }
    public static void moved(ServerPlayerEntity player,ServerWorld target,Vec3d position,boolean success) {
        var fault=ACTIVE.get(target.getServer());
        if(fault==null || !fault.server.isOnThread() || !success || !fault.people.contains(player.getUuid())
                || fault.server.getPlayerManager().getPlayer(player.getUuid())!=player) return;
        if(target==fault.target && fault.scope(player,target,position) && !fault.hit) {
            fault.successfulMoves++;
            if(fault.faultCase==Case.SECOND_MOVE_INVALID_SOURCE && fault.successfulMoves==1) {
                var controller=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)fault.source.getBlockEntity(fault.initial.origin().controllerPos());
                if(controller==null || controller.getWorld()!=fault.source || controller.isRemoved()
                        || !fault.initial.chamberUuid().equals(controller.chamberUuid())) throw new AssertionError("own source identity故障安裝前已不符");
                fault.firstMovedPose=new CorridorPageManager.PhysicalPose(player.getPos(),player.getVelocity(),player.getYaw(),player.getPitch());
                controller.setChamberUuid(UUID.randomUUID()); controller.markDirty(); fault.sourceInvalidated=true;
            }
        }
    }
    public static void beforePublish(Object owner,UUID sid,Set<UUID> cohort) {
        for(var fault : ACTIVE.values()) {
            if(fault.faultCase!=Case.PUBLISH_AFTER_FALSE || fault.hit || !fault.initial.sessionUuid().equals(sid)
                    || !fault.server.isOnThread() || CorridorPageManager.forServer(fault.server)!=owner || !fault.people.equals(cohort)) continue;
            var durable=SessionRecoveryState.get(fault.server).flushedRecords().get(sid);
            if(durable==null || durable.state()!=SessionState.SUPERPOSITION || durable.restoreEntryEffectOnReturn()
                    || !durable.origin().equals(fault.initial.origin())
                    || !SessionRecoveryRecord.sameParticipantSources(fault.initial.participants(),durable.participants()))
                throw new AssertionError("publish fault 不得在 false 真提交前觸發");
            for(UUID id : cohort) {
                var player=fault.server.getPlayerManager().getPlayer(id);
                if(player==null || player.getServerWorld()!=fault.target || fault.target.getEntity(id)!=player
                        || player.hasStatusEffect(ModEffects.QUANTUM_STATE)) throw new AssertionError("publish fault 真cohort未進世界／耗藥");
            }
            fault.falseVerified=true; fault.hit=true;
            var player=fault.server.getPlayerManager().getPlayer(fault.affected);
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000,0));
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,800_000,2,true,false,false));
            fault.newEffect=(NbtCompound)player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
            throw new IllegalStateException("own native publish boundary 故障：false 已checked提交");
        }
    }
    private boolean scope(ServerPlayerEntity player,ServerWorld world,Vec3d position) {
        if(!server.isOnThread() || world!=target || server.getWorld(SuperpositionWorld.KEY)!=target
                || server.getWorld(source.getRegistryKey())!=source || !people.contains(player.getUuid())
                || player.getServer()!=server || server.getPlayerManager().getPlayer(player.getUuid())!=player) return false;
        var record=SessionRecoveryState.get(server).flushedRecords().get(initial.sessionUuid());
        return record!=null && record.state()==SessionState.ARMING && record.origin().equals(initial.origin())
                && SessionRecoveryRecord.sameParticipantSources(initial.participants(),record.participants())
                && record.spaceLeases().stream().anyMatch(lease -> {
                    var box=lease.bounds();
                    return new net.minecraft.util.math.Box(box.getMinX(),box.getMinY(),box.getMinZ(),box.getMaxX()+1,
                            box.getMaxY()+1,box.getMaxZ()+1).contains(position);
                });
    }
    /** 在 RETURNING checked flush 邊界同 tick 保存 native rollback 訊號，避免效果自然倒數污染對比。 */
    public static void afterFlush(SessionRecoveryState journal,MinecraftServer server) {
        var fault=ACTIVE.get(server);
        if(fault==null || !fault.hit || !server.isOnThread() || SessionRecoveryState.get(server)!=journal) return;
        var record=journal.flushedRecords().get(fault.initial.sessionUuid());
        if(record==null || record.state()!=SessionState.RETURNING || !record.restoreEntryEffectOnReturn()
                || fault.faultCase!=Case.SECOND_MOVE) return;
        fault.rollbackTick=server.getTicks();
        fault.returnFlushes++;
        var effects=new LinkedHashMap<UUID,NbtCompound>();
        var poses=new LinkedHashMap<UUID,CorridorPageManager.PhysicalPose>();
        for(var person : record.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player!=null) {
                poses.put(player.getUuid(),new CorridorPageManager.PhysicalPose(player.getPos(),player.getVelocity(),player.getYaw(),player.getPitch()));
                var effect=player.getStatusEffect(ModEffects.QUANTUM_STATE);
                if(effect!=null) effects.put(player.getUuid(),((NbtCompound)effect.writeNbt()).copy());
            }
        }
        fault.rollbackEffects=Map.copyOf(effects); fault.rollbackPoses=Map.copyOf(poses);
        fault.rollbackVerified=record.participants().stream().allMatch(person -> {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            return player!=null && player.getServerWorld()==fault.source && fault.source.getEntity(person.playerUuid())==player
                    && player.getPos().squaredDistanceTo(person.sourcePosition())<1e-12
                    && player.getVelocity().squaredDistanceTo(person.sourceVelocity())<1e-12
                    && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(player.getYaw()-person.yaw()))<1e-4
                    && Math.abs(player.getPitch()-person.pitch())<1e-4
                    && dev.quantumchamber.chamber.ChamberOccupantService.contains(dev.quantumchamber.chamber.ChamberGeometry.interiorBox(
                            new dev.quantumchamber.chamber.ChamberFrame(record.origin().controllerPos(),record.origin().facing())),player.getBoundingBox())
                    && player.getStatusEffect(ModEffects.QUANTUM_STATE)!=null
                    && person.quantumStateSnapshot().equals(player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt());
        });
    }
    public boolean hit() { return hit; }
    public int successfulMoves() { return successfulMoves; }
    public boolean rollbackVerified() { return rollbackVerified; }
    public int rollbackTick() { return rollbackTick; }
    public int returnFlushes() { return returnFlushes; }
    public String rollbackEvidence() { return "poses="+rollbackPoses+" effects="+rollbackEffects; }
    public boolean falseVerified() { return falseVerified; }
    public UUID affected() { return affected; }
    public boolean sourceInvalidated() { return sourceInvalidated; }
    public UUID firstMovedUuid() { return initial.participants().getFirst().playerUuid(); }
    public CorridorPageManager.PhysicalPose firstMovedPose() { return firstMovedPose; }
    public void restoreSourceIdentity() {
        if(!server.isOnThread() || server.getWorld(source.getRegistryKey())!=source) throw new IllegalStateException("own fault修復必須server thread");
        if(sourceInvalidated) {
            var controller=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)source.getBlockEntity(initial.origin().controllerPos());
            controller.setChamberUuid(initial.chamberUuid()); controller.markDirty();
        }
    }
    public NbtCompound newEffect() { return newEffect==null ? null : newEffect.copy(); }
    @Override public void close() { if(!server.isOnThread()) throw new IllegalStateException("fault cleanup 必須在 server thread"); ACTIVE.remove(server,this); }
}
