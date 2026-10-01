package dev.quantumchamber.chamber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

public final class ChamberDoorService {
    public boolean toggle(ChamberBlockView view, ChamberBlockMutator mutator,
                          ChamberMutationExecutor executor, ChamberFrame frame) {
        return toggle(view, mutator, executor, frame, ignored -> { });
    }

    public boolean toggle(ChamberBlockView view, ChamberBlockMutator mutator,
                          ChamberMutationExecutor executor, ChamberFrame frame, ControllerRefresh refresh) {
        Objects.requireNonNull(refresh, "refresh");
        List<BlockPos> positions = aperturePositions(frame);
        Boolean open = null;
        for (BlockPos pos : positions) {
            ChamberCell cell = view.cellAt(pos);
            if (cell != ChamberCell.BULKHEAD_OPEN && cell != ChamberCell.BULKHEAD_CLOSED) return false;
            boolean current = cell == ChamberCell.BULKHEAD_OPEN;
            if (open != null && open != current) return false;
            open = current;
        }
        if (open == null) return false;
        boolean original = open;
        boolean next = !original;
        boolean toggled = executor.execute(() -> mutateWithRollback(pos -> openAt(view, pos), mutator::set, positions,
                ignored -> original, next));
        if (toggled) refresh.refresh(frame.controllerPos());
        return toggled;
    }

    /**
     * 把整面 25 格門套用成 {@code target}（open＋membrane；plan 決策 10）。M5 的 F3d（SOURCE fast path 的一般開門）、
     * F4b（PREPARE 的 sealed 量子膜、門口有人時改一般開門、閘門失效）與 F5（passable 量子膜、通道結束、沒有所屬 session 的
     * 量子膜正規化）都經由這個 API 改門，不另寫一份。本 task 沒有呼叫點；既有的開關門仍走 {@link #toggle}。
     *
     * <p><b>只能在 server thread 呼叫</b>，否則丟 {@link IllegalStateException}。
     *
     * <p>交易與 {@link #toggle} 相同：只寫目前不是 target 的格（每格先記下自己的原狀態），在
     * {@link ChamberProtectionService} 的授權寫入中執行，任何一格失敗就把已改過的格逐一 rollback 回各自的原狀態；
     * Controller 的刷新延到整個交易結束之後（{@link ChamberPowerCoordinator#deferActivation}）。
     * {@code activationBlocked} 的處理也與門切換相同：成功套用時解除，rollback 失敗時設定並記 ERROR。
     *
     * @throws IllegalArgumentException target 的量子膜軸與門的朝向不符（{@link MembraneState#fits}）；不寫任何一格
     */
    public ApplyResult applyTarget(ServerWorld world, ChamberFrame frame, DoorState target) {
        return WorldChamberDoorAdapter.apply(world, frame, target);
    }

    /**
     * {@link #applyTarget} 的純邏輯核心。25 格不全是 bulkhead 時回 REJECTED 且不寫任何一格；全部已是 target 時回 UNCHANGED，
     * 不呼叫 executor、不刷新；寫入失敗且 rollback 成功時回 REJECTED；rollback 失敗時丟 {@link IllegalStateException}
     * （與 {@link #toggle} 相同，由世界版本轉成 ROLLBACK_FAILED）。只有 APPLIED 會刷新 Controller。
     */
    ApplyResult.Outcome apply(DoorStateView view, DoorStateMutator mutator, ChamberMutationExecutor executor,
                              ChamberFrame frame, DoorState target, ControllerRefresh refresh) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(mutator, "mutator");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(refresh, "refresh");
        if (!target.membrane().fits(frame.outwardFacing())) {
            throw new IllegalArgumentException("量子膜的軸與門的朝向不符：" + target + "，門朝 " + frame.outwardFacing());
        }
        Map<BlockPos, DoorState> originals = new LinkedHashMap<>();
        for (BlockPos pos : aperturePositions(frame)) {
            DoorState current = view.doorAt(pos);
            if (current == null) return ApplyResult.Outcome.REJECTED;
            originals.put(pos, current);
        }
        List<BlockPos> pending = new ArrayList<>();
        originals.forEach((pos, current) -> {
            if (!current.equals(target)) pending.add(pos);
        });
        if (pending.isEmpty()) return ApplyResult.Outcome.UNCHANGED;
        boolean applied = executor.execute(() -> mutateWithRollback(view::doorAt, mutator::set, pending, originals::get, target));
        if (!applied) return ApplyResult.Outcome.REJECTED;
        refresh.refresh(frame.controllerPos());
        return ApplyResult.Outcome.APPLIED;
    }

    /**
     * 依序把每一格寫成 {@code next}；任何一格回 false 或丟例外，就把已嘗試的格逆序寫回各自的原狀態。
     * rollback 也失敗時丟 {@link IllegalStateException}，列出沒有復原的格。
     */
    private static <S> boolean mutateWithRollback(Function<BlockPos, S> read, BiPredicate<BlockPos, S> write, List<BlockPos> positions,
                                                  Function<BlockPos, S> original, S next) {
        List<BlockPos> changed = new ArrayList<>();
        for (BlockPos pos : positions) {
            changed.add(pos);
            RuntimeException mutationFailure = null;
            boolean accepted = false;
            try {
                accepted = write.test(pos, next);
            } catch (RuntimeException failure) {
                mutationFailure = failure;
            }
            if (!accepted) {
                List<BlockPos> rollbackFailures = new ArrayList<>();
                for (int index = changed.size() - 1; index >= 0; index--) {
                    BlockPos changedPos = changed.get(index);
                    S restored = original.apply(changedPos);
                    // 原生 setBlockState 對相同狀態回 false；尚未改變的失敗格不必再次寫入。
                    if (Objects.equals(read.apply(changedPos), restored)) continue;
                    try {
                        write.test(changedPos, restored);
                        if (!Objects.equals(read.apply(changedPos), restored)) rollbackFailures.add(changedPos);
                    } catch (RuntimeException failure) {
                        rollbackFailures.add(changedPos);
                        if (mutationFailure == null) mutationFailure = failure;
                        else mutationFailure.addSuppressed(failure);
                    }
                }
                if (!rollbackFailures.isEmpty()) {
                    throw new IllegalStateException("整面門 rollback 失敗 " + rollbackFailures.size()
                            + " 格：" + rollbackFailures.stream().map(BlockPos::toShortString).toList(), mutationFailure);
                }
                return false;
            }
        }
        return true;
    }

    private static @Nullable Boolean openAt(ChamberBlockView view, BlockPos pos) {
        ChamberCell cell = view.cellAt(pos);
        if (cell == ChamberCell.BULKHEAD_OPEN) return Boolean.TRUE;
        if (cell == ChamberCell.BULKHEAD_CLOSED) return Boolean.FALSE;
        return null;
    }

    private static List<BlockPos> aperturePositions(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>(25);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        }
        return positions;
    }

    @FunctionalInterface
    public interface ControllerRefresh {
        void refresh(BlockPos controllerPos);
    }

    /**
     * 門格的 open＋membrane，也是 {@link #applyTarget} 的目標。open=false 時只能是 {@link MembraneState#NONE}（spec §10.1）。
     */
    public record DoorState(boolean open, MembraneState membrane) {
        /** 關門。 */
        public static final DoorState CLOSED = new DoorState(false, MembraneState.NONE);
        /** 一般開門（沒有量子膜）。 */
        public static final DoorState OPEN = new DoorState(true, MembraneState.NONE);

        public DoorState {
            Objects.requireNonNull(membrane, "membrane");
            if (!open && membrane.isMembrane()) throw new IllegalArgumentException("關閉的門只能是 membrane=none：" + membrane.asString());
        }

        /** 朝向為 {@code outwardFacing} 的門套上 sealed 量子膜（有碰撞）。 */
        public static DoorState sealed(Direction outwardFacing) {
            return new DoorState(true, MembraneState.sealedFor(outwardFacing));
        }

        /** 朝向為 {@code outwardFacing} 的門套上 passable 量子膜（沒有碰撞）。 */
        public static DoorState passable(Direction outwardFacing) {
            return new DoorState(true, MembraneState.passableFor(outwardFacing));
        }
    }

    /**
     * {@link #applyTarget} 的結果。{@code activationBlocked} 是呼叫結束時該 Controller 的 activationBlocked
     * （Controller 不存在或所在 chunk 未載入時為 false）。
     */
    public record ApplyResult(Outcome outcome, boolean activationBlocked) {
        public ApplyResult {
            Objects.requireNonNull(outcome, "outcome");
        }

        /** 25 格已是 target（APPLIED 或 UNCHANGED）。 */
        public boolean succeeded() {
            return outcome == Outcome.APPLIED || outcome == Outcome.UNCHANGED;
        }

        public enum Outcome {
            /** 已改成 target：activationBlocked 已解除，Controller 已刷新。 */
            APPLIED,
            /** 25 格原本就是 target：沒有寫入、不刷新，activationBlocked 不變。 */
            UNCHANGED,
            /** 世界沒有改變：門板不是完整的 25 格 bulkhead，或寫入失敗且 rollback 成功。activationBlocked 不變。 */
            REJECTED,
            /** rollback 失敗，門處於混合狀態：已設 activationBlocked、刷新 Controller 並記 ERROR。 */
            ROLLBACK_FAILED
        }
    }

    /** 讀門格目前的 open＋membrane；不是 bulkhead 時回 null。 */
    @FunctionalInterface
    interface DoorStateView {
        @Nullable DoorState doorAt(BlockPos pos);
    }

    /** 把門格寫成指定的 open＋membrane；回 false 或丟例外都視為這一格失敗（與 {@link ChamberBlockMutator} 相同）。 */
    @FunctionalInterface
    interface DoorStateMutator {
        boolean set(BlockPos pos, DoorState state);
    }
}
