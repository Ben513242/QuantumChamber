package dev.quantumchamber.gametest;

import com.mojang.authlib.GameProfile;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpoint;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpointAccess;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.registry.ModEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class M2CorridorGameTests implements FabricGameTest {
    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_journal_flush_updates_only_confirmed_snapshot(TestContext context) {
        var server = context.getWorld().getServer();
        var state = SessionRecoveryState.get(server);
        var sessionUuid = UUID.randomUUID(); var chamberUuid = UUID.randomUUID();
        var origin = new ChamberOriginAuthority(chamberUuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new BlockPos(100, 70, 100), Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var participant = new SessionRecoveryRecord.Participant(UUID.randomUUID(), new Vec3d(100.5, 65, 100.5),
                Vec3d.ZERO, 90, 0, (NbtCompound) new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1234).writeNbt(), false);
        var record = new SessionRecoveryRecord(sessionUuid, chamberUuid, origin, java.util.List.of(participant),
                java.util.List.of(new SessionRecoveryRecord.SpaceLease(63, new BlockBox(10000, 0, 0, 10095, 20, 10))), SessionState.ARMING, true);
        try {
            state.put(record);
            context.assertTrue(!state.flushedRecords().containsKey(sessionUuid), "put 不冒充 durable");
            state.flush(server);
            context.assertTrue(state.flushedRecords().containsKey(sessionUuid), "checked flush 後才可發布 durable");
            context.assertTrue(SessionRecoveryState.get(server) == state, "原生 manager 快取同一 state");
            state.remove(sessionUuid);
            context.assertTrue(state.flushedRecords().containsKey(sessionUuid), "未保存的 remove 不提早釋放舊空間");
            state.flush(server);
            context.assertTrue(!state.flushedRecords().containsKey(sessionUuid), "保存移除後才更新 durable");
        } finally {
            state.remove(sessionUuid); state.flush(server);
        }
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void runtime_recovery_mutations_require_server_thread(TestContext context) {
        var state = SessionRecoveryState.get(context.getWorld().getServer());
        boolean rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { state.remove(UUID.randomUUID()); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join();
        context.assertTrue(rejected, "即使不存在的 session，離開 server thread 也不得修改 journal");
        var player = disconnected(context);
        var access = (PlayerRecoveryCheckpointAccess) player;
        var checkpoint = new PlayerRecoveryCheckpoint(UUID.randomUUID(), PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
        access.quantumchamber$setRecoveryCheckpoint(checkpoint);
        rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { access.quantumchamber$setRecoveryCheckpoint(checkpoint); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join();
        context.assertTrue(rejected, "玩家 marker setter 必須在 server thread");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void static_superposition_world_exists(TestContext context) {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD,
                Identifier.of("quantumchamber", "superposition"));
        var world = context.getWorld().getServer().getWorld(key);
        context.assertTrue(world != null, "固定Superposition世界必須真實存在");
        context.assertTrue(!world.getDimension().hasSkyLight() && world.getBottomY() == 0
                && world.getHeight() == 256, "原生 dimension codec 必須保留無天光與固定高度");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_marker_roundtrip_copy_and_save(TestContext context) throws Exception {
        var player = connect(context);
        try {
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1234));
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 3));
            var beforePosition = player.getPos();
            var beforeEffect = player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
            var beforeInventory = player.writeNbt(new NbtCompound()).get("Inventory").copy();
            context.assertTrue(player instanceof PlayerRecoveryCheckpointAccess, "原生玩家必須提供窄 checkpoint API");
            var access = (PlayerRecoveryCheckpointAccess) player;
            context.assertTrue(access.quantumchamber$getRecoveryCheckpoint().isEmpty(), "舊玩家沒有 marker");
            var legacy = disconnected(context);
            legacy.readNbt(player.writeNbt(new NbtCompound()));
            context.assertTrue(((PlayerRecoveryCheckpointAccess) legacy).quantumchamber$getRecoveryCheckpoint().isEmpty(),
                    "舊版無 marker NBT 經原生 read 仍為空");
            PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player, Optional.empty());
            var checkpoint = new PlayerRecoveryCheckpoint(UUID.randomUUID(), PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
            access.quantumchamber$setRecoveryCheckpoint(checkpoint);
            var encoded = player.writeNbt(new NbtCompound());
            var restored = disconnected(context);
            restored.readNbt(encoded);
            context.assertEquals(Optional.of(checkpoint), ((PlayerRecoveryCheckpointAccess) restored)
                    .quantumchamber$getRecoveryCheckpoint(), "完整原生 write/read 保留 marker");
            var copied = connect(context);
            try {
                copied.copyFrom(player, true);
                context.assertEquals(Optional.of(checkpoint), ((PlayerRecoveryCheckpointAccess) copied)
                        .quantumchamber$getRecoveryCheckpoint(), "原生 copyFrom 保留 marker");
            } finally { context.getWorld().getServer().getPlayerManager().remove(copied); }
            PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player, Optional.of(checkpoint));
            context.assertTrue(player.writeNbt(new NbtCompound()).contains("DataVersion", 3), "原生 snapshot 已含 DataVersion");
            boolean rejected = false;
            try {
                PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player,
                        Optional.of(new PlayerRecoveryCheckpoint(UUID.randomUUID(), checkpoint.appliedPolicy())));
            } catch (java.io.IOException expected) { rejected = true; }
            context.assertTrue(rejected, "不符合 runtime marker 的保存必須拒絕");
            context.assertEquals(beforePosition, player.getPos(), "checkpoint 原語不移動玩家");
            context.assertEquals(beforeEffect, player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(), "checkpoint 原語不消耗或改寫效果");
            context.assertEquals(beforeInventory, player.writeNbt(new NbtCompound()).get("Inventory"), "checkpoint 原語不修改背包");
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void malformed_native_marker_is_preserved_and_reported_lazily(TestContext context) {
        var player = disconnected(context);
        context.assertTrue(player instanceof PlayerRecoveryCheckpointAccess, "原生 marker API 已安裝");
        for (var raw : new net.minecraft.nbt.NbtElement[] {NbtInt.of(17), new NbtCompound()}) {
            var encoded = player.writeNbt(new NbtCompound());
            encoded.put(PlayerRecoveryCheckpoint.NBT_KEY, raw.copy());
            player.readNbt(encoded);
            var access = (PlayerRecoveryCheckpointAccess) player;
            boolean rejected = false;
            try { access.quantumchamber$getRecoveryCheckpoint(); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "getter 必須延後明確回報損壞 marker");
            context.assertEquals(raw, player.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "auto-save 保留原始未知 NBT");
            var copy = disconnected(context);
            copy.copyFrom(player, true);
            context.assertEquals(raw, copy.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "copyFrom 也保留壞 marker");
            rejected = false;
            try { ((PlayerRecoveryCheckpointAccess) copy).quantumchamber$getRecoveryCheckpoint(); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "複製後仍不可假裝健康");
            rejected = false;
            try { access.quantumchamber$setRecoveryCheckpoint(new PlayerRecoveryCheckpoint(UUID.randomUUID(),
                    PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY)); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "setter 不得覆蓋未知 marker");
            context.assertEquals(raw, player.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "拒絕後仍保留原始 marker");
        }
        context.complete();
    }

    private static ServerPlayerEntity disconnected(TestContext context) {
        UUID uuid = UUID.randomUUID();
        var data = ConnectedClientData.createDefault(new GameProfile(uuid, "m2-" + uuid.toString().substring(0, 8)), false);
        return new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(), data.gameProfile(), data.syncedOptions());
    }

    private static ServerPlayerEntity connect(TestContext context) {
        var player = disconnected(context);
        var data = ConnectedClientData.createDefault(player.getGameProfile(), false);
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        context.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        return player;
    }
}
