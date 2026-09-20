package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.*;
import static dev.quantumchamber.universe.UniverseTransferResult.*;

import dev.quantumchamber.transfer.SessionTransferService;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.ChunkStatus;

/** 低階、同步的 server-thread transfer gate；不註冊任何 gameplay 入口。 */
public final class UniverseTransferService {
    @FunctionalInterface
    public interface NativeMove {
        boolean move(ServerPlayerEntity player, ServerWorld target, UniverseTransferPoint point);
    }

    private final DynamicDimensionBackend backend;
    private final NativeMove nativeMove;
    private final UniverseTransferAuthority authority = new UniverseTransferAuthority();
    private final List<WorldIdentity> worldIdentities = new ArrayList<>();

    public UniverseTransferService(DynamicDimensionBackend backend, SessionTransferService transfers) {
        this(backend, adapt(Objects.requireNonNull(transfers, "transfers")));
    }

    public UniverseTransferService(DynamicDimensionBackend backend, NativeMove nativeMove) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.nativeMove = Objects.requireNonNull(nativeMove, "nativeMove");
    }

    private static NativeMove adapt(SessionTransferService transfers) {
        return (player, target, point) -> transfers.move(player, target, point.position(),
                point.velocity(), point.yaw(), point.pitch());
    }

    static Preflight preflight(boolean serverThread, Supplier<Observation> readObservation) {
        if (!serverThread) return new Preflight(REJECT_SERVER_THREAD, null, null);
        var raw = Objects.requireNonNull(readObservation.get(), "observation");
        if (!raw.finitePose()) return new Preflight(REJECT_SOURCE_NON_FINITE, null, raw);
        var source = new UniverseTransferPoint(raw.worldKey(), raw.position(), raw.velocity(), raw.yaw(), raw.pitch());
        return new Preflight(ALLOW, source, raw);
    }

    record Preflight(UniverseTransferAuthority.Decision decision, UniverseTransferPoint source, Observation observation) {
        UniverseTransferResult rejectedResult() {
            return new UniverseTransferResult(Outcome.REJECTED_BEFORE_MOVE, decision, source,
                    new MoveEvidence(NativeOutcome.NOT_ATTEMPTED, observation, "前置檢查"),
                    new RollbackEvidence(RollbackOutcome.NOT_ATTEMPTED, NativeOutcome.NOT_ATTEMPTED, observation, ""),
                    null, "前置檢查拒絕移動");
        }
    }

    public UniverseTransferResult moveToUniverse(ServerPlayerEntity player, UniverseWorldDescriptor descriptor) {
        Objects.requireNonNull(player, "player");
        var server = player.getServer();
        var preflight = preflight(server != null && server.isOnThread(), () -> observe(player, server));
        if (preflight.decision() != ALLOW) return preflight.rejectedResult();
        Objects.requireNonNull(descriptor, "descriptor");
        var sourceWorld = player.getServerWorld();
        var source = preflight.source();
        var playerId = preflight.observation().playerId();
        var common = commonAuthority(player, server, sourceWorld, source, playerId);
        if (common != ALLOW) return rejected(player, server, source, common, "來源權威不成立");

        UniverseId universeId;
        try {
            String[] path = descriptor.worldKey().getValue().getPath().split("/");
            if (path.length != 3 || descriptor.role() != DimensionRole.OVERWORLD) {
                return rejected(player, server, source, REJECT_DESCRIPTOR, "不是 schema 1 Overworld descriptor");
            }
            universeId = UniverseId.of(UUID.fromString(path[1]));
            if (!UniverseKeys.world(universeId, DimensionRole.OVERWORLD).equals(descriptor.worldKey())) {
                return rejected(player, server, source, REJECT_DESCRIPTOR, "Universe UUID 與 key 不一致");
            }
        } catch (IllegalArgumentException failure) {
            return rejected(player, server, source, REJECT_DESCRIPTOR, failure.toString());
        }

        ServerWorld target;
        try {
            target = backend.resolveActive(server, descriptor).orElse(null);
        } catch (RuntimeException failure) {
            return rejected(player, server, source, REJECT_BACKEND_FAILURE, failure.toString());
        }
        if (target == null) return rejected(player, server, source, REJECT_TARGET_NOT_ACTIVE, "backend 沒有 exact ACTIVE owner");
        if (target == sourceWorld || !exactWorld(server, target)
                || !descriptor.worldKey().equals(target.getRegistryKey())) {
            return rejected(player, server, source, REJECT_TARGET_IDENTITY, "destination 不是 exact live world");
        }
        var destination = new UniverseTransferPoint(descriptor.worldKey(), source.position(),
                source.velocity(), source.yaw(), source.pitch());
        var expectedBox = player.getBoundingBox();
        var safety = inspectSafety(target, player, expectedBox);
        if (safety.decision() != ALLOW) return rejected(player, server, source, safety.decision(), safety.detail());
        var sourceToken = worldToken(sourceWorld);
        var targetToken = worldToken(target);

        var move = attempt(player, server, target, destination);
        try {
            if (move.outcome() == NativeOutcome.REPORTED_SUCCESS
                    && exactWorld(server, sourceWorld)
                    && backend.resolveActive(server, descriptor).orElse(null) == target
                    && verified(player, server, target, destination, expectedBox, playerId)) {
                var receipt = new UniverseTransferReceipt(playerId, universeId, source, destination,
                        descriptor.worldKey(), sourceToken, targetToken);
                return success(Outcome.MOVED, source, move, receipt);
            }
        } catch (RuntimeException failure) {
            move = new MoveEvidence(move.outcome(), observe(player, server), move.detail() + "；驗證：" + failure);
        }
        return recover(player, server, sourceWorld, source, expectedBox, playerId, move);
    }

    /** source 是這次返還呼叫的 departure 快照；目標 home pose 來自 receipt.source。 */
    public UniverseTransferResult returnToSource(ServerPlayerEntity player, UniverseTransferReceipt receipt) {
        Objects.requireNonNull(player, "player");
        var server = player.getServer();
        var preflight = preflight(server != null && server.isOnThread(), () -> observe(player, server));
        if (preflight.decision() != ALLOW) return preflight.rejectedResult();
        Objects.requireNonNull(receipt, "receipt");
        var departureWorld = player.getServerWorld();
        var departure = preflight.source();
        var common = commonAuthority(player, server, departureWorld, departure, receipt.playerId());
        if (common != ALLOW) return rejected(player, server, departure, common, "返還玩家／來源權威不成立");
        if (!receipt.destinationKey().equals(departure.worldKey())
                || !matchesToken(departureWorld, receipt.destinationInstanceId())) {
            return rejected(player, server, departure, REJECT_RECEIPT_IDENTITY, "玩家已不在 receipt 的 exact destination");
        }
        var home = server.getWorld(receipt.source().worldKey());
        if (home == null || !exactWorld(server, home) || !matchesToken(home, receipt.sourceInstanceId())) {
            return rejected(player, server, departure, REJECT_SOURCE_IDENTITY, "receipt 的 exact source 已不存在");
        }
        var departureBox = player.getBoundingBox();
        var expectedBox = departureBox.offset(receipt.source().position().subtract(departure.position()));
        var safety = inspectSafety(home, player, expectedBox);
        if (safety.decision() != ALLOW) return rejected(player, server, departure, safety.decision(), safety.detail());

        var move = attempt(player, server, home, receipt.source());
        try {
            if (move.outcome() == NativeOutcome.REPORTED_SUCCESS
                    && matchesToken(home, receipt.sourceInstanceId())
                    && verified(player, server, home, receipt.source(), expectedBox, receipt.playerId())) {
                return success(Outcome.RETURNED, departure, move, null);
            }
        } catch (RuntimeException failure) {
            move = new MoveEvidence(move.outcome(), observe(player, server), move.detail() + "；返還驗證：" + failure);
        }
        return recover(player, server, departureWorld, departure, departureBox, receipt.playerId(), move);
    }

    private UniverseTransferAuthority.Decision commonAuthority(ServerPlayerEntity player, MinecraftServer server,
            ServerWorld sourceWorld, UniverseTransferPoint source, UUID expectedId) {
        boolean onThread = server.isOnThread();
        return authority.evaluate(new UniverseTransferAuthority.Snapshot(onThread, expectedId, player.getUuid(),
                onThread && exactPlayer(server, player), onThread && exactWorld(server, sourceWorld)
                        && sourceWorld.getEntity(player.getUuid()) == player,
                true, true, true, true, source));
    }

    private MoveEvidence attempt(ServerPlayerEntity player, MinecraftServer server, ServerWorld target,
            UniverseTransferPoint point) {
        try {
            boolean reported = nativeMove.move(player, target, point);
            return new MoveEvidence(reported ? NativeOutcome.REPORTED_SUCCESS : NativeOutcome.REPORTED_FAILURE,
                    observe(player, server), "");
        } catch (RuntimeException failure) {
            return new MoveEvidence(NativeOutcome.THREW, observe(player, server), failure.toString());
        }
    }

    private UniverseTransferResult recover(ServerPlayerEntity player, MinecraftServer server,
            ServerWorld sourceWorld, UniverseTransferPoint source, Box sourceBox, UUID playerId, MoveEvidence move) {
        RollbackEvidence rollback;
        if (!exactWorld(server, sourceWorld)) {
            rollback = skippedRollback(RollbackOutcome.SOURCE_IDENTITY_LOST, player, server, "source exact identity 已失效");
        } else if (!playerId.equals(player.getUuid()) || !exactPlayer(server, player)) {
            rollback = skippedRollback(RollbackOutcome.PLAYER_IDENTITY_LOST, player, server, "玩家 exact identity 已失效");
        } else {
            var safety = inspectSafety(sourceWorld, player, sourceBox);
            if (safety.decision() != ALLOW) {
                rollback = skippedRollback(RollbackOutcome.SOURCE_UNSAFE, player, server, safety.detail());
                return recoveryResult(source, move, rollback);
            }
            var rollbackMove = attempt(player, server, sourceWorld, source);
            boolean restored;
            String detail = rollbackMove.detail();
            try {
                restored = rollbackMove.outcome() == NativeOutcome.REPORTED_SUCCESS
                        && verified(player, server, sourceWorld, source, sourceBox, playerId);
            } catch (RuntimeException failure) {
                restored = false;
                detail += "；回滾驗證：" + failure;
            }
            rollback = new RollbackEvidence(restored ? RollbackOutcome.SUCCEEDED : RollbackOutcome.FAILED,
                    rollbackMove.outcome(), observe(player, server), detail);
        }
        return recoveryResult(source, move, rollback);
    }

    private static UniverseTransferResult recoveryResult(UniverseTransferPoint source, MoveEvidence move, RollbackEvidence rollback) {
        return new UniverseTransferResult(rollback.outcome() == RollbackOutcome.SUCCEEDED
                ? Outcome.FAILED_ROLLED_BACK : Outcome.FAILED_RECOVERY_REQUIRED,
                POST_MOVE_VERIFICATION_FAILED, source, move, rollback, null, "原生移動後未通過完整實際狀態驗證");
    }

    private static RollbackEvidence skippedRollback(RollbackOutcome outcome, ServerPlayerEntity player,
            MinecraftServer server, String detail) {
        return new RollbackEvidence(outcome, NativeOutcome.NOT_ATTEMPTED, observe(player, server), detail);
    }

    private static UniverseTransferResult success(Outcome outcome, UniverseTransferPoint source,
            MoveEvidence move, UniverseTransferReceipt receipt) {
        return new UniverseTransferResult(outcome, ALLOW, source, move,
                new RollbackEvidence(RollbackOutcome.NOT_ATTEMPTED, NativeOutcome.NOT_ATTEMPTED, move.actual(), ""), receipt, "");
    }

    private static UniverseTransferResult rejected(ServerPlayerEntity player, MinecraftServer server,
            UniverseTransferPoint source, UniverseTransferAuthority.Decision decision, String detail) {
        var actual = observe(player, server);
        return new UniverseTransferResult(Outcome.REJECTED_BEFORE_MOVE, decision, source,
                new MoveEvidence(NativeOutcome.NOT_ATTEMPTED, actual, ""),
                new RollbackEvidence(RollbackOutcome.NOT_ATTEMPTED, NativeOutcome.NOT_ATTEMPTED, actual, ""), null, detail);
    }

    private static boolean verified(ServerPlayerEntity player, MinecraftServer server, ServerWorld expectedWorld,
            UniverseTransferPoint expected, Box expectedBox, UUID expectedId) {
        return expectedId.equals(player.getUuid()) && player.getServerWorld() == expectedWorld
                && observe(player, server).matches(expected) && sameBox(player.getBoundingBox(), expectedBox)
                && targetSafety(expectedWorld, player, player.getBoundingBox()) == ALLOW;
    }

    private static boolean sameBox(Box actual, Box expected) {
        return Math.abs(actual.minX - expected.minX) < 1e-6 && Math.abs(actual.minY - expected.minY) < 1e-6
                && Math.abs(actual.minZ - expected.minZ) < 1e-6 && Math.abs(actual.maxX - expected.maxX) < 1e-6
                && Math.abs(actual.maxY - expected.maxY) < 1e-6 && Math.abs(actual.maxZ - expected.maxZ) < 1e-6;
    }

    private static boolean exactWorld(MinecraftServer server, ServerWorld world) {
        return server.isOnThread() && world.getServer() == server && server.getWorld(world.getRegistryKey()) == world;
    }

    private static boolean exactPlayer(MinecraftServer server, ServerPlayerEntity player) {
        return server.isOnThread() && player.getServer() == server && !player.isRemoved() && player.isAlive()
                && server.getPlayerManager().getPlayer(player.getUuid()) == player;
    }

    private static Observation observe(ServerPlayerEntity player, MinecraftServer server) {
        var world = player.getServerWorld();
        boolean worldExact = exactWorld(server, world);
        return new Observation(player.getUuid(), world.getRegistryKey(), player.getPos(), player.getVelocity(),
                player.getYaw(), player.getPitch(), exactPlayer(server, player), worldExact,
                worldExact && world.getEntity(player.getUuid()) == player);
    }

    private static SafetyCheck inspectSafety(ServerWorld world, ServerPlayerEntity player, Box box) {
        try {
            return new SafetyCheck(targetSafety(world, player, box), "位置必須位於高度／邊界內，且 FULL、無碰撞／液體及完整地板支撐");
        } catch (RuntimeException failure) {
            return new SafetyCheck(REJECT_TARGET_UNSAFE, "安全檢查失敗：" + failure);
        }
    }

    /** 碰撞迭代器含 epsilon 與一格外圈，預查兩格避免邊界讀取未知 chunk；一律 create=false。 */
    private static UniverseTransferAuthority.Decision targetSafety(ServerWorld world, ServerPlayerEntity player, Box box) {
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) return REJECT_TARGET_UNSAFE;
        int minChunkX = MathHelper.floor(box.minX - 2) >> 4;
        int maxChunkX = MathHelper.floor(box.maxX + 2) >> 4;
        int minChunkZ = MathHelper.floor(box.minZ - 2) >> 4;
        int maxChunkZ = MathHelper.floor(box.maxZ + 2) >> 4;
        if (maxChunkX - minChunkX > 4 || maxChunkZ - minChunkZ > 4) return REJECT_TARGET_UNSAFE;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (world.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false) == null) return REJECT_TARGET_NOT_FULL;
            }
        }
        var border = world.getWorldBorder();
        if (box.minY < world.getBottomY() || box.maxY > world.getTopY()
                || box.minX < border.getBoundWest() || box.maxX > border.getBoundEast()
                || box.minZ < border.getBoundNorth() || box.maxZ > border.getBoundSouth()
                || !world.isSpaceEmpty(player, box) || world.containsFluid(box)) return REJECT_TARGET_UNSAFE;
        int floorY = MathHelper.floor(box.minY - 1e-7);
        if (floorY < world.getBottomY() || Math.abs(box.minY - (floorY + 1.0)) > 1e-6) return REJECT_TARGET_UNSAFE;
        for (int x = MathHelper.floor(box.minX + 1e-7); x <= MathHelper.floor(box.maxX - 1e-7); x++) {
            for (int z = MathHelper.floor(box.minZ + 1e-7); z <= MathHelper.floor(box.maxZ - 1e-7); z++) {
                var pos = new BlockPos(x, floorY, z);
                if (!world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP)) return REJECT_TARGET_UNSAFE;
            }
        }
        return ALLOW;
    }

    private UUID worldToken(ServerWorld world) {
        worldIdentities.removeIf(identity -> identity.world().get() == null);
        for (var identity : worldIdentities) if (identity.world().get() == world) return identity.token();
        var token = UUID.randomUUID();
        worldIdentities.add(new WorldIdentity(new WeakReference<>(world), token));
        return token;
    }

    private boolean matchesToken(ServerWorld world, UUID token) {
        return worldIdentities.stream().anyMatch(identity -> identity.world().get() == world && identity.token().equals(token));
    }

    private record WorldIdentity(WeakReference<ServerWorld> world, UUID token) { }
    private record SafetyCheck(UniverseTransferAuthority.Decision decision, String detail) { }
}
