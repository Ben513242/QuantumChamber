package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.ChunkPos;

/**
 * M7 T4B（spec v1.6 C、V14）：回程收尾時替 B 取有界票。HOME 列的 CloseIntent=CLOSE 已 checked、exact live B world 存在，但 B 的 home chunks 沒有載入、B 無人無票時，
 * 回程終局 owner 以 ≤4 refs 的 B 收尾票有界載入 B，再照 §5.3 的順序收尾。fixture 沿 {@link M7HomeClosureGameTests.Projection}（A 原艙＋同艙 COMPLETE 的 B 投影），
 * 建好之後放掉測試票，等 B 的 home chunks 全部卸載才開始。B 收尾票 owner（{@code dev.quantumchamber.collapse.HomeEntryTickets}）以反射讀取：T4 沒有這個類，RED 時讀成「沒有票」。
 */
public final class M7HomeEntryTicketGameTests implements FabricGameTest {
    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /** B 的 home chunks 在放掉測試票之後卸載的上限。 */
    private static final int UNLOAD_TICKS = 200;
    /** CLOSE 尚未 checked（roll-forward 待確認）期間觀察「不取票」的 tick 數。 */
    private static final int UNCHECKED_TICKS = 5;
    /** 收尾的上限：B 收尾票的租期 200 tick 之內就該完成，另留 roll-forward 重試與 entity storage 載入的餘量。 */
    private static final int CLOSE_TICKS = 400;

    /**
     * RED（plan Task 4B :531）：現行（T4）回程終局 owner 在 converge 遇到 B 未載入時只靜默等待，B 無人無票就永遠 pending。v1.6 C：CLOSE checked 之後以 ≤4 refs 的
     * B 收尾票（CLOSE_CONVERGE）有界載入 B，收尾完成、A 解除 busy、票釋放；CLOSE 尚未 checked（journal 寫出受控失敗、roll-forward 待確認）期間不取票。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_close", tickLimit = 1600)
    public void close_loads_unloaded_b_with_bounded_ticket(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_loads_unloaded_b_with_bounded_ticket";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            boolean[] fail = {false};
            M5GameTestSupport.installFlushProbe(c, state -> { if (fail[0]) throw new IllegalStateException("M7_T4B_CLOSE_FLUSH_FAULT：受控的 journal 寫出失敗"); }, hooks);
            hooks.add(() -> fail[0] = false);
            whenBUnloaded(c, p, label, hooks, () -> {
                var sid = UUID.randomUUID();
                var dormant = p.dormant(sid, 39);
                var witness = new LinkedHashMap<String, Boolean>();
                long now = server.getOverworld().getTime();
                c.assertTrue(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED, now)).committed(),
                        "原艙停止原因已 checked latch");
                // 這個 tick 結束時回程終局 owner 會凍結 CLOSE：讓那次寫出失敗，CLOSE 停在 roll-forward 待確認（未 checked）。
                fail[0] = true;
                var unchecked = new ArrayList<String>();
                M7HomeClosureGameTests.until(c, label + "/unchecked", UNCHECKED_TICKS + 1, () -> {
                    unchecked.add(describe(journal, sid) + " lease=" + EntryTickets.describe(server, sid));
                    return unchecked.size() > UNCHECKED_TICKS;
                }, guard(p, hooks, () -> {
                    fail[0] = false;
                    witness.put("closeWasUncheckedDuringTheFault", unchecked.stream().skip(1).allMatch(line -> line.startsWith("current=CLOSE flushed=NONE rollForward=true")));
                    witness.put("noTicketBeforeTheCloseIsChecked", unchecked.stream().allMatch(line -> line.endsWith("lease=NONE")));
                    var seen = new ArrayList<String>();
                    var watch = RemovalWatch.start(server, sid, hooks);
                    M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, () -> {
                        var lease = EntryTickets.describe(server, sid);
                        if (!lease.equals("NONE") && !seen.contains(lease)) seen.add(lease);
                        return !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid);
                    }, guard(p, hooks, () -> {
                        try {
                            var expectedRefs = homeChunks(p.frame).size();
                            witness.put("closedWithACloseConvergeTicket", seen.size() == 1 && seen.getFirst().equals("CLOSE_CONVERGE refs=" + expectedRefs) && expectedRefs <= 4);
                            witness.put("doorOpenedForTheRootStop", "OPEN/NONE".equals(p.doorSummary()));
                            var receipt = p.receipts().lookup(sid).orElseThrow();
                            witness.put("receiptKeepsTheRootStop", receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                                    && k.doorGoal() == DoorGoal.OPEN);
                            witness.put("rootUnlocked", !gate().globalChamberBusy(server, p.chamber));
                            witness.put("ticketReleasedInTheRemovalTick", watch.removedSeen() && !watch.leaseAfterRemoval());
                            witness.put("noTicketAfterTheClose", EntryTickets.describe(server, sid).equals("NONE"));
                            var failure = witness.containsValue(false) ? new GameTestException("B_CLOSE_TICKET：B 收尾票的收尾不符：" + witness + " 票=" + seen
                                    + " unchecked=" + unchecked) : null;
                            p.close(); hooks.release();
                            if (failure != null) throw failure;
                            c.complete();
                        } catch (java.io.IOException failure) { p.close(); hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                    }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "CLOSE_PENDING_WITHOUT_B_TICKET：CLOSE 已 checked、B 未載入時 " + CLOSE_TICKS
                            + " tick 內沒有收尾（B 無人無票就一直 pending）：row=" + describe(journal, sid) + " 票=" + seen + " unchecked=" + unchecked));
                }), () -> {
                    fail[0] = false;
                    try { throw new GameTestException("UNCHECKED_WINDOW：觀察窗口沒有取滿 " + UNCHECKED_TICKS + " 個樣本：" + unchecked); }
                    finally { p.close(); hooks.release(); }
                });
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    // ---- fixture ----

    /** 回呼的失敗收尾：非預期的例外或斷言失敗時先收掉 fixture（{@code Projection.close} 與 hooks 都是冪等），再原樣丟出。 */
    static Runnable guard(M7HomeClosureGameTests.Projection p, M2CorridorGameTests.TestHooks hooks, Runnable body) {
        return () -> {
            try { body.run(); }
            catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
        };
    }

    /**
     * 等 B 的 home chunks 方塊與 entity storage 就緒（{@code Projection} 建好之後），放掉 fixture 的測試票，再有界等待 B 的 home chunks 全部卸載
     * （{@code getWorldChunk} 都是 null），之後才執行 {@code body}。等不到時以 {@code ENV_} 開頭的錯因失敗（測試環境，不是 owner 的失敗）。
     */
    static void whenBUnloaded(TestContext c, M7HomeClosureGameTests.Projection p, String label, M2CorridorGameTests.TestHooks hooks, Runnable body) {
        var chunks = homeChunks(p.frame);
        M7HomeClosureGameTests.until(c, label + "/entry_ready", 380, () -> dev.quantumchamber.persistence.ChunkSaveCheckpoint.ready(p.b, chunks), () -> {
            releaseHold(p);
            M7HomeClosureGameTests.until(c, label + "/b_unloaded", UNLOAD_TICKS, () -> !anyLoaded(p.b, chunks), guard(p, hooks, body), () -> {
                try { throw new GameTestException("ENV_B_NOT_UNLOADED：放掉測試票之後 " + UNLOAD_TICKS + " tick 內 B 的 home chunks 沒有卸載（有其他票或玩家）：loaded="
                        + loaded(p.b, chunks)); }
                finally { p.close(); hooks.release(); }
            });
        }, () -> {
            try { throw new GameTestException("ENV_NOT_READY：B 端在 380 tick 內沒有就緒"); }
            finally { p.close(); hooks.release(); }
        });
    }

    /** RED 的收尾：重新以測試票保持 B，讓（現行的）owner 在 B 載入後自己收掉這一列，不把殘留留給之後的 batch，再以 {@code message} 失敗。 */
    private static void pendingWithoutTicket(TestContext c, M7HomeClosureGameTests.Projection p, M2CorridorGameTests.TestHooks hooks, UUID sid, String label, String message) {
        var journal = SessionRecoveryState.get(p.f.server);
        var rehold = M7HomeClosureGameTests.holdChunks(p.b, p.frame);
        var failure = new GameTestException(message + " thresholds=" + M7HomeClosureGameTests.closureThresholds(p, sid));
        M7HomeClosureGameTests.until(c, label + "/cleanup", 300, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
            try { throw failure; } finally { rehold.run(); p.close(); hooks.release(); }
        }, () -> {
            try { throw failure; } finally { rehold.run(); p.close(); hooks.release(); }
        });
    }

    /** 放掉 {@code Projection} 保持 B 的測試票（反射呼叫它的釋放動作；之後 {@code close} 再呼叫一次是無作用的移除）。 */
    static void releaseHold(M7HomeClosureGameTests.Projection p) {
        ((Runnable) M4CandidateTestAccess.get(p, "release")).run();
    }

    /** B 的 {@code PassageTransferPolicy.homeChunks}（package 可見的 static，反射呼叫、只讀）。 */
    @SuppressWarnings("unchecked")
    static Set<ChunkPos> homeChunks(ChamberFrame frame) {
        try {
            var method = Class.forName("dev.quantumchamber.collapse.PassageTransferPolicy").getDeclaredMethod("homeChunks", ChamberFrame.class);
            method.setAccessible(true);
            return (Set<ChunkPos>) method.invoke(null, frame);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("homeChunks 讀不到", failure); }
    }

    static boolean anyLoaded(ServerWorld world, Set<ChunkPos> chunks) {
        return chunks.stream().anyMatch(pos -> world.getChunkManager().getWorldChunk(pos.x, pos.z) != null);
    }

    static List<String> loaded(ServerWorld world, Set<ChunkPos> chunks) {
        return chunks.stream().filter(pos -> world.getChunkManager().getWorldChunk(pos.x, pos.z) != null).map(pos -> pos.x + "," + pos.z).toList();
    }

    /** 這個 SID 在 journal 的 current／flushed 是否已凍結 CLOSE，以及 roll-forward 是否待確認。 */
    static String describe(SessionRecoveryState journal, UUID sid) {
        return "current=" + closeKind(journal.allSessions().get(sid)) + " flushed=" + closeKind(journal.allFlushedSessions().get(sid))
                + " rollForward=" + journal.rollForwardPending(sid);
    }

    private static String closeKind(Object row) {
        if (row == null) return "ABSENT";
        return row instanceof HomeSessionRecord h && h.home().closeIntent() instanceof Close ? "CLOSE" : "NONE";
    }

    /** B 收尾票 owner（{@code HomeEntryTickets}）的反射讀取（只讀）；類別不存在（T4）時一律是「沒有票」。 */
    static final class EntryTickets {
        private EntryTickets() {}

        static Class<?> type() {
            try { return Class.forName("dev.quantumchamber.collapse.HomeEntryTickets"); }
            catch (ClassNotFoundException absent) { return null; }
        }

        static Object lease(MinecraftServer server, UUID sid) {
            var type = type();
            if (type == null) return null;
            var owner = ((Map<?, ?>) M4CandidateTestAccess.get(type, "servers")).get(server);
            return owner == null ? null : ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).get(sid);
        }

        /** {@code NONE}，或 {@code <用途> refs=<數量>}。 */
        static String describe(MinecraftServer server, UUID sid) {
            var lease = lease(server, sid);
            if (lease == null) return "NONE";
            return M4CandidateTestAccess.get(lease, "purpose") + " refs=" + ((Set<?>) M4CandidateTestAccess.get(lease, "chunks")).size();
        }
    }

    /**
     * 「checked 移除的那個 tick 結束時已經沒有票」的觀察（plan Task 4B 變異「移除後不釋票」）：在 {@code END_SERVER_TICK} 的預設 phase（回程終局 owner 的推進）之後檢查。
     * 票 owner 的 {@code START_SERVER_TICK} 會釋放不再適用的票，所以下一個 tick 的 test callback 看不到這個差別。只讀；收尾時停止觀察。
     */
    static final class RemovalWatch {
        private static final net.minecraft.util.Identifier PHASE = net.minecraft.util.Identifier.of("quantumchamber:m7_t4b_removal_watch");
        private static final Map<UUID, RemovalWatch> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
        private static boolean registered;
        private final MinecraftServer server;
        private final UUID sid;
        private volatile boolean removedSeen, leaseAfterRemoval;

        private RemovalWatch(MinecraftServer server, UUID sid) { this.server = server; this.sid = sid; }

        static synchronized RemovalWatch start(MinecraftServer server, UUID sid, M2CorridorGameTests.TestHooks hooks) {
            if (!registered) {
                registered = true;
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, PHASE);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PHASE, owner -> ACTIVE.values().forEach(watch -> watch.observe(owner)));
            }
            var watch = new RemovalWatch(server, sid);
            ACTIVE.put(sid, watch);
            hooks.add(() -> ACTIVE.remove(sid, watch));
            return watch;
        }

        private void observe(MinecraftServer owner) {
            if (owner != server || removedSeen) return;
            var journal = SessionRecoveryState.get(server);
            if (journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)) return;
            removedSeen = true;
            leaseAfterRemoval = EntryTickets.lease(server, sid) != null;
        }

        boolean removedSeen() { return removedSeen; }

        boolean leaseAfterRemoval() { return leaseAfterRemoval; }
    }
}
