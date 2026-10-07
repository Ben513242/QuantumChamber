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
import net.minecraft.world.chunk.ChunkStatus;

/**
 * 低階、同步的 server-thread transfer gate；不註冊任何 gameplay 入口。落點的安全政策是 {@link TransferSafety}：M3 的入口一律是
 * {@link TransferSafety#M3_SAFE_LANDING}；M5 的 passage 經 {@link #moveToUniverse(ServerPlayerEntity, UniverseWorldDescriptor, TransferSafety)} 帶自己的政策（plan 決策 4）。
 */
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

    /** M3 的移動（spec M3 §8）：以 {@link TransferSafety#M3_SAFE_LANDING} 委派給 {@link #moveToUniverse(ServerPlayerEntity, UniverseWorldDescriptor, TransferSafety)}，行為不變。 */
    public UniverseTransferResult moveToUniverse(ServerPlayerEntity player, UniverseWorldDescriptor descriptor) {
        return moveToUniverse(player, descriptor, TransferSafety.M3_SAFE_LANDING);
    }

    /**
     * 以 {@code safety} 的落點政策移動（M5 plan 決策 4）：移動之前的前置檢查、移動之後的驗證與回滾資格都用同一個 {@code safety}；exact 身分、FULL 之外的
     * 其他前置條件、原生移動、逐欄位核對位置、速度、yaw、pitch 與 bbox，以及回滾，都與 M3 相同。
     */
    public UniverseTransferResult moveToUniverse(ServerPlayerEntity player, UniverseWorldDescriptor descriptor, TransferSafety safety) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(safety, "safety");
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
        var landing = inspectSafety(safety, target, player, expectedBox);
        if (landing.decision() != ALLOW) return rejected(player, server, source, landing.decision(), landing.detail());
        var sourceToken = worldToken(sourceWorld);
        var targetToken = worldToken(target);

        var move = attempt(player, server, target, destination);
        try {
            if (move.outcome() == NativeOutcome.REPORTED_SUCCESS
                    && exactWorld(server, sourceWorld)
                    && backend.resolveActive(server, descriptor).orElse(null) == target
                    && verified(safety, player, server, target, destination, expectedBox, playerId)) {
                var receipt = new UniverseTransferReceipt(playerId, universeId, source, destination,
                        descriptor.worldKey(), sourceToken, targetToken);
                return success(Outcome.MOVED, source, move, receipt);
            }
        } catch (RuntimeException failure) {
            move = new MoveEvidence(move.outcome(), observe(player, server), move.detail() + "；驗證：" + failure);
        }
        return recover(safety, player, server, sourceWorld, source, expectedBox, playerId, move);
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
        // M3 的返還一律是 M3 的安全落點（M5 的 passage 沒有返還，plan 決策 4 只有 moveToUniverse 的多載）。
        var safety = TransferSafety.M3_SAFE_LANDING;
        var landing = inspectSafety(safety, home, player, expectedBox);
        if (landing.decision() != ALLOW) return rejected(player, server, departure, landing.decision(), landing.detail());

        var move = attempt(player, server, home, receipt.source());
        try {
            if (move.outcome() == NativeOutcome.REPORTED_SUCCESS
                    && matchesToken(home, receipt.sourceInstanceId())
                    && verified(safety, player, server, home, receipt.source(), expectedBox, receipt.playerId())) {
                return success(Outcome.RETURNED, departure, move, null);
            }
        } catch (RuntimeException failure) {
            move = new MoveEvidence(move.outcome(), observe(player, server), move.detail() + "；返還驗證：" + failure);
        }
        return recover(safety, player, server, departureWorld, departure, departureBox, receipt.playerId(), move);
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

    private UniverseTransferResult recover(TransferSafety safety, ServerPlayerEntity player, MinecraftServer server,
            ServerWorld sourceWorld, UniverseTransferPoint source, Box sourceBox, UUID playerId, MoveEvidence move) {
        RollbackEvidence rollback;
        if (!exactWorld(server, sourceWorld)) {
            rollback = skippedRollback(RollbackOutcome.SOURCE_IDENTITY_LOST, player, server, "source exact identity 已失效");
        } else if (!playerId.equals(player.getUuid()) || !exactPlayer(server, player)) {
            rollback = skippedRollback(RollbackOutcome.PLAYER_IDENTITY_LOST, player, server, "玩家 exact identity 已失效");
        } else {
            var landing = inspectSafety(safety, sourceWorld, player, sourceBox);
            if (landing.decision() != ALLOW) {
                rollback = skippedRollback(RollbackOutcome.SOURCE_UNSAFE, player, server, landing.detail());
                return recoveryResult(source, move, rollback);
            }
            var rollbackMove = attempt(player, server, sourceWorld, source);
            boolean restored;
            String detail = rollbackMove.detail();
            try {
                restored = rollbackMove.outcome() == NativeOutcome.REPORTED_SUCCESS
                        && verified(safety, player, server, sourceWorld, source, sourceBox, playerId);
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

    private static boolean verified(TransferSafety safety, ServerPlayerEntity player, MinecraftServer server, ServerWorld expectedWorld,
            UniverseTransferPoint expected, Box expectedBox, UUID expectedId) {
        return expectedId.equals(player.getUuid()) && player.getServerWorld() == expectedWorld
                && observe(player, server).matches(expected) && sameBox(player.getBoundingBox(), expectedBox)
                && safety.check(site(expectedWorld, player), player.getBoundingBox()) == ALLOW;
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

    private static SafetyCheck inspectSafety(TransferSafety safety, ServerWorld world, ServerPlayerEntity player, Box box) {
        try {
            return new SafetyCheck(safety.check(site(world, player), box), safety.requirement());
        } catch (RuntimeException failure) {
            return new SafetyCheck(REJECT_TARGET_UNSAFE, "安全檢查失敗：" + failure);
        }
    }

    /**
     * 政策讀世界的介面（{@link TransferSafety.Site}）：F5-1 之前 {@code targetSafety} 直接對 {@code world} 與 {@code player} 做的查詢，逐一對應、一律 create=false。
     * 碰撞迭代器含 epsilon 與一格外圈，所以 FULL 檢查預查兩格（{@link TransferSafety#fullChunkRange}），避免邊界讀取未知 chunk。
     */
    private static TransferSafety.Site site(ServerWorld world, ServerPlayerEntity player) {
        return new TransferSafety.Site() {
            @Override public boolean chunkFull(int chunkX, int chunkZ) { return world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null; }
            @Override public int bottomY() { return world.getBottomY(); }
            @Override public int topY() { return world.getTopY(); }
            @Override public double borderWest() { return world.getWorldBorder().getBoundWest(); }
            @Override public double borderEast() { return world.getWorldBorder().getBoundEast(); }
            @Override public double borderNorth() { return world.getWorldBorder().getBoundNorth(); }
            @Override public double borderSouth() { return world.getWorldBorder().getBoundSouth(); }
            @Override public boolean spaceEmpty(Box box) { return world.isSpaceEmpty(player, box); }
            @Override public boolean containsFluid(Box box) { return world.containsFluid(box); }
            @Override public boolean floorSupports(int x, int y, int z) {
                var pos = new BlockPos(x, y, z);
                return world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP);
            }
        };
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
