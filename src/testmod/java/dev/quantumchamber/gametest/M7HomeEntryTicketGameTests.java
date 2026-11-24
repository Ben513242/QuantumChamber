package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.collapse.CollapseBackoff;
import dev.quantumchamber.collapse.HomeEndpointAuthority;
import dev.quantumchamber.collapse.HomeEntryTickets;
import dev.quantumchamber.mixin.ServerWorldRecoveryStorageAccessor;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * M7 T4B（spec v1.6 C、V14）：回程收尾時替 B 取有界票。HOME 列的 CloseIntent=CLOSE 已 checked、exact live B world 存在，但 B 的 home chunks 沒有載入、B 無人無票時，
 * 回程終局 owner 以 ≤4 refs 的 B 收尾票有界載入 B，再照 §5.3 的順序收尾。fixture 沿 {@link M7HomeClosureGameTests.Projection}（A 原艙＋同艙 COMPLETE 的 B 投影），
 * 建好之後放掉測試票，等 B 的 home chunks 全部卸載才開始。B 收尾票 owner（{@code dev.quantumchamber.collapse.HomeEntryTickets}）以反射讀取：T4 沒有這個類，RED 時讀成「沒有票」。
 * 冷 B 案（B 未載入時由正式 B 收尾票載入 B；共 7 案，{@code close_ticket_never_full_releases_and_backs_off} 除外）在放掉測試票之前先生成 B 地形（{@link #whenPregeneratedBUnloaded}），取票當下 B 仍是未載入，正式票取得後不必在票期內現場 worldgen（各案由誰載入 B，見 {@code scripts/verification/README.md} 的冷 B 說明：部分案例另有 fixture 助載或 FakeBackend 前段）。
 */
public final class M7HomeEntryTicketGameTests implements FabricGameTest {
    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /** B 的 home chunks 在放掉測試票之後卸載的上限。 */
    private static final int UNLOAD_TICKS = 200;
    /** CLOSE 尚未 checked（roll-forward 待確認）期間觀察「不取票」的 tick 數。 */
    private static final int UNCHECKED_TICKS = 5;
    /**
     * 收尾的上限：B 收尾票的租期 200 tick 之內就該完成，另留 roll-forward 重試（受控寫出失敗之後沿 journal 的退避）與 entity storage 載入的餘量
     * （GREEN 本機實測 261 tick，大部分是 roll-forward 的重試退避）。
     */
    private static final int CLOSE_TICKS = 600;

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
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var dormant = p.dormant(sid, 39);
                var witness = new LinkedHashMap<String, Boolean>();
                // 每個 tick 結束時的觀察從凍結之前開始（只存在一個 tick 的票，test callback 看不到）。
                var watch = RemovalWatch.start(server, sid, hooks);
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
                            witness.put("noTicketWithoutACheckedCloseAtAnyTickEnd", !watch.leaseWithoutCheckedClose());
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

    /**
     * spec v1.6 C、V14：B 收尾票 200 tick 內都沒有 FULL（以反射換成「不加票、永遠不 FULL」的 backend）。租期到就釋放、
     * 記一則 WARN、沿 per-SID 退避（200 tick）；退避期間不持票。把退避推到 6400 之後再失敗一次，延遲到上限 12000：再記一則 ERROR（每 instance 每 SID 一則），WARN 仍是一則
     * （同 SID 同原因）。換回原生 backend、清掉退避之後，重取的票載入 B、收尾完成。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_never_full", tickLimit = 2400)
    public void close_ticket_never_full_releases_and_backs_off(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_never_full_releases_and_backs_off";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            var fake = FakeBackend.install(hooks);
            whenBUnloaded(c, p, label, hooks, () -> {
                var sid = UUID.randomUUID();
                var dormant = p.dormant(sid, 38);
                var witness = new LinkedHashMap<String, Boolean>();
                c.assertTrue(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())).committed(), "原艙停止原因已 checked latch");
                int[] acquiredAt = {-1};
                M7HomeClosureGameTests.until(c, label + "/first_lease", 40, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                    acquiredAt[0] = server.getTicks();
                    witness.put("firstLeaseIsCloseConverge", EntryTickets.describe(server, sid).startsWith("CLOSE_CONVERGE "));
                    var held = new ArrayList<Integer>();
                    M7HomeClosureGameTests.until(c, label + "/first_expiry", LEASE_TICKS + 20, () -> {
                        if (EntryTickets.lease(server, sid) != null) held.add(server.getTicks());
                        return EntryTickets.lease(server, sid) == null;
                    }, guard(p, hooks, () -> {
                        int releasedAt = server.getTicks();
                        witness.put("leaseHeldForTheWholeLease", held.size() >= LEASE_TICKS - 2 && releasedAt - acquiredAt[0] <= LEASE_TICKS + 1);
                        var backoff = EntryTickets.backoff(server, sid);
                        var quiet = new ArrayList<String>();
                        M7HomeClosureGameTests.until(c, label + "/backoff_quiet", 20, () -> {
                            if (EntryTickets.lease(server, sid) != null) quiet.add(server.getTicks() + ":" + EntryTickets.describe(server, sid));
                            return false;
                        }, () -> { }, guard(p, hooks, () -> {
                            witness.put("noTicketDuringTheBackoff", quiet.isEmpty() && backoff != null && (int) M4CandidateTestAccess.get(backoff, "delayTicks") == 200);
                            witness.put("oneExpiryWarn", expiryWarns(capture, sid) == 1);
                            // 退避推到 6400：下一次失敗就到上限 12000。
                            M4CandidateTestAccess.set(backoff, "delayTicks", 6400);
                            M4CandidateTestAccess.set(backoff, "retryAtTick", (long) server.getTicks());
                            M7HomeClosureGameTests.until(c, label + "/second_lease", 40, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                                // 票在 START_SERVER_TICK 釋放、owner 在同一個 tick 的 END_SERVER_TICK 才記失敗：等到退避也更新。
                                M7HomeClosureGameTests.until(c, label + "/second_expiry", LEASE_TICKS + 20, () -> EntryTickets.lease(server, sid) == null
                                        && (int) M4CandidateTestAccess.get(backoff, "delayTicks") != 6400, guard(p, hooks, () -> {
                                    witness.put("capReached", (int) M4CandidateTestAccess.get(backoff, "delayTicks") == 12000);
                                    witness.put("oneCapError", capErrors(capture, sid) == 1);
                                    witness.put("stillOneExpiryWarn", expiryWarns(capture, sid) == 1);
                                    fake.restore();
                                    M4CandidateTestAccess.set(backoff, "delayTicks", 0);
                                    M4CandidateTestAccess.set(backoff, "retryAtTick", 0L);
                                    M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, () -> !journal.allSessions().containsKey(sid)
                                            && !journal.allFlushedSessions().containsKey(sid), guard(p, hooks, () -> {
                                        witness.put("closedAfterTheRetry", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                                        witness.put("noTicketAfterTheClose", EntryTickets.lease(server, sid) == null);
                                        witness.put("errorStaysOne", capErrors(capture, sid) == 1);
                                        finish(c, p, hooks, witness, "B 收尾票租期內不 FULL：" + witness + " held=" + held.size() + " quiet=" + quiet);
                                    }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "NEVER_FULL_RETRY_NOT_CLOSED：換回原生 backend 之後沒有收尾：" + witness));
                                }), () -> fail(p, hooks, "NEVER_FULL_SECOND_NOT_RELEASED：第二份票租期到沒有釋放：" + witness));
                            }), () -> fail(p, hooks, "NEVER_FULL_NO_SECOND_LEASE：退避到期之後沒有重取：" + witness));
                        }));
                    }), () -> fail(p, hooks, "NEVER_FULL_NOT_RELEASED：租期到沒有釋放票：held=" + held.size() + " " + witness));
                }), () -> fail(p, hooks, "NEVER_FULL_NO_LEASE：CLOSE checked、B 未載入時沒有取票：" + describe(journal, sid)));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * spec v1.6 C、V14：A 票＋B 收尾票額度滿時取不到票，記一則 WARN、退避 200 tick；釋放佔用後仍須等到退避期滿才重取。
     * 核對同 SID 正式重取之後才用 fixture 提供 exact B 的載入前提，並由 owner checked 結案。原生票獨力載入由另外的 cold B 案與原生重啟列驗證。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_budget", tickLimit = 2000)
    public void close_ticket_budget_exhausted_warns_and_retries(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_budget_exhausted_warns_and_retries";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var fill = Budget.fill(server, hooks);
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var dormant = p.dormant(sid, 37);
                var witness = new LinkedHashMap<String, Boolean>();
                var watch = TickEndWatch.start(server, sid, hooks);
                var removal = RemovalWatch.start(server, sid, hooks);
                witness.put("budgetFull", fill.size() >= 1 && !Budget.free(server));
                p.committed(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())), "原艙停止原因已 checked latch");
                M7HomeClosureGameTests.until(c, label + "/warn", 40, () -> unavailableWarns(capture, sid) == 1, guard(p, hooks, () -> {
                    int rejectedAt = watch.failureTick();
                    long retryAt = watch.retryAtOf(rejectedAt);
                    witness.put("backedOff200", rejectedAt >= 0 && watch.delayOf(rejectedAt) == 200 && retryAt == rejectedAt + 200L);
                    witness.put("noTicketWhileTheBudgetIsFull", EntryTickets.lease(server, sid) == null);
                    Budget.release(server, fill);
                    if (witness.containsValue(false)) {
                        pendingBudgetClose(c, p, hooks, sid, label, "BUDGET_RETRY_PRECONDITION：" + witness);
                        return;
                    }
                    int[] assistedAt = {-1};
                    var chunks = homeChunks(p.frame);
                    M7HomeClosureGameTests.until(c, label + "/close", 400, () -> {
                        if (assistedAt[0] >= 0) return removal.removedSeen();
                        int firstLease = watch.firstLeaseTick();
                        if (firstLease < retryAt || !watch.noLeaseBetween(rejectedAt, retryAt - 1)
                                || !firstExactCloseLeaseIsHeld(p, sid, watch, chunks)) return false;
                        assistedAt[0] = server.getTicks();
                        hooks.add(M7HomeClosureGameTests.holdChunks(p.b, p.frame));
                        return false;
                    }, guard(p, hooks, () -> {
                        try {
                            int firstLease = watch.firstLeaseTick();
                            witness.put("retriedOnlyAfterTheBackoff", firstLease >= retryAt && watch.noLeaseBetween(rejectedAt, retryAt - 1));
                            witness.put("fixtureAssistedAfterTheExactLease", assistedAt[0] > firstLease);
                            witness.put("checkedRemoval", !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid));
                            witness.put("closedAfterTheRetry", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                            witness.put("noTicketAfterTheClose", !removal.leaseAfterRemoval() && EntryTickets.lease(server, sid) == null);
                            witness.put("stillOneWarn", unavailableWarns(capture, sid) == 1 && ticketWarns(capture, sid) == 1);
                            var receipt = p.receipts().lookup(sid).orElseThrow();
                            witness.put("receiptMatchesTheClose", receipt.sessionUuid().equals(sid) && receipt.entryEndpoint().equals(p.entry)
                                    && receipt.home().closeIntent() instanceof Close close && close.reason() == CloseReason.ROOT_DISABLED && close.doorGoal() == DoorGoal.OPEN);
                            finish(c, p, hooks, witness, "B 收尾票額度已滿：" + witness + " retryAt=" + retryAt + " firstLease=" + firstLease + " assistedAt=" + assistedAt[0]);
                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                    }), () -> pendingBudgetClose(c, p, hooks, sid, label, "BUDGET_RETRY_NOT_CLOSED：未見合時重取或正式收尾：" + witness
                            + " retryAt=" + retryAt + " firstLease=" + watch.firstLeaseTick() + " assistedAt=" + assistedAt[0]));
                }), () -> pendingBudgetClose(c, p, hooks, sid, label, "BUDGET_NO_WARN：額度已滿時沒有恰一則取不到票的 WARN："
                        + describe(journal, sid) + " lease=" + EntryTickets.describe(server, sid)));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * spec v1.6 C、V14：持有中的 B 收尾票（不 FULL 的 backend 讓票停在持有中）在 {@code /reload}（直接觸發 Fabric 的 START／END_DATA_PACK_RELOAD，沿 M6／T4A 的做法）
     * 當下冪等釋放（加的票全部移除、coverage 歸還），沒有 WARN、不退避，下一次推進重取；SERVER_STOPPING 的收尾（以反射呼叫票 owner 的 {@code closeAll(server, true)}，
     * 不觸發其他模組的停服）當下同樣釋放，之後停服中不再取票。還原停服旗標、換回原生 backend 之後收尾完成。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_reload_stop", tickLimit = 2000)
    public void close_ticket_released_on_reload_and_stop(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_released_on_reload_and_stop";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            var fake = FakeBackend.install(hooks);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var dormant = p.dormant(sid, 36);
                var witness = new LinkedHashMap<String, Boolean>();
                c.assertTrue(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())).committed(), "原艙停止原因已 checked latch");
                M7HomeClosureGameTests.until(c, label + "/lease", 40, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                    int refs = homeChunks(p.frame).size();
                    witness.put("heldBeforeTheReload", fake.held(sid) == refs && Budget.reserved(server, sid));
                    if (!(server.getResourceManager() instanceof net.minecraft.resource.LifecycledResourceManager resources))
                        throw new GameTestException("RELOAD_FIXTURE：server 的 resource manager 不是 LifecycledResourceManager");
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.START_DATA_PACK_RELOAD.invoker().startDataPackReload(server, resources);
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().endDataPackReload(server, resources, true);
                    witness.put("reloadReleased", EntryTickets.lease(server, sid) == null && fake.held(sid) == 0 && !Budget.reserved(server, sid));
                    M7HomeClosureGameTests.until(c, label + "/reacquire", 5, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                        witness.put("reacquiredWithoutBackoff", ticketWarns(capture, sid) == 0);
                        hooks.add(() -> EntryTickets.setStopping(server, false));
                        EntryTickets.closeAll(server, true);
                        witness.put("stopReleased", EntryTickets.lease(server, sid) == null && fake.held(sid) == 0 && !Budget.reserved(server, sid));
                        var stopped = new ArrayList<Integer>();
                        M7HomeClosureGameTests.until(c, label + "/stopping", 5, () -> {
                            if (EntryTickets.lease(server, sid) != null) stopped.add(server.getTicks());
                            return false;
                        }, () -> { }, guard(p, hooks, () -> {
                            witness.put("noTicketWhileStopping", stopped.isEmpty());
                            EntryTickets.setStopping(server, false);
                            fake.restore();
                            M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, () -> !journal.allSessions().containsKey(sid)
                                    && !journal.allFlushedSessions().containsKey(sid), guard(p, hooks, () -> {
                                witness.put("closedAfterRestore", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                                witness.put("noTicketAfterTheClose", EntryTickets.lease(server, sid) == null);
                                witness.put("fakeAddsAllRemoved", fake.held(sid) == 0);
                                finish(c, p, hooks, witness, "B 收尾票的 /reload 與停服釋放：" + witness);
                            }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "RELOAD_STOP_NOT_CLOSED：還原之後沒有收尾：" + witness));
                        }));
                    }), () -> fail(p, hooks, "RELOAD_NOT_REACQUIRED：/reload 之後沒有重取：" + witness));
                }), () -> fail(p, hooks, "RELOAD_NO_LEASE：CLOSE checked、B 未載入時沒有取票：" + describe(journal, sid)));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * spec v1.6 C 第 2 種情況：開服集合中曾 READY 的 AUTHORITY_UNREADABLE SEALED（以反射把本 SID 加進回程終局 owner 本 instance 的開服集合），B 未載入時 entry 是否恢復無法核對
     * （inspectEntry 是 UNKNOWN）。owner 取 RESTART_VERIFY 的 B 收尾票載入 B，核對 entry 已恢復後以 RESTART_CLOSED（本 instance 的開服 UUID）收尾，同一份票繼續供收尾使用，
     * 收尾後釋放。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_restart_verify", tickLimit = 1600)
    public void restart_verify_ticket_loads_b_for_unreadable_seal(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "restart_verify_ticket_loads_b_for_unreadable_seal";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                sealedAfterReady(p, p.dormant(sid, 35));
                var instance = Startup.add(server, sid, hooks);
                var witness = new LinkedHashMap<String, Boolean>();
                var seen = new ArrayList<String>();
                M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, () -> {
                    var lease = EntryTickets.describe(server, sid);
                    if (!lease.equals("NONE") && !seen.contains(lease)) seen.add(lease);
                    return !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid);
                }, guard(p, hooks, () -> {
                    try {
                        witness.put("verifiedWithARestartVerifyTicket", seen.size() == 1 && seen.getFirst().startsWith("RESTART_VERIFY "));
                        var receipt = p.receipts().lookup(sid).orElseThrow();
                        witness.put("restartClosedByThisInstance", receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.RESTART_CLOSED
                                && k.evidence() instanceof HomeSessionRecord.RestartClosed r && r.startupInstanceUuid().equals(instance));
                        witness.put("doorOpened", "OPEN/NONE".equals(p.doorSummary()));
                        witness.put("rootUnlocked", !gate().globalChamberBusy(server, p.chamber));
                        witness.put("noTicketAfterTheClose", EntryTickets.lease(server, sid) == null);
                        finish(c, p, hooks, witness, "開服收尾以 RESTART_VERIFY 票核對 B：" + witness + " 票=" + seen);
                    } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "RESTART_VERIFY_NOT_CLOSED：曾 READY 的 AUTHORITY_UNREADABLE 在 B 未載入時沒有收尾：row="
                        + describe(journal, sid) + " 票=" + seen));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 已 latch 原艙停止的開服封存，先 checked 凍結原原因，再取 CLOSE_CONVERGE；B 還未載入時就能判定取票用途。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_restart_root_stop", tickLimit = 1600)
    public void restart_seal_with_root_stop_freezes_close_before_loading_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "restart_seal_with_root_stop_freezes_close_before_loading_b";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var fake = FakeBackend.install(hooks);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var sealed = sealedAfterReady(p, p.dormant(sid, 35));
                Startup.add(server, sid, hooks);
                p.committed(journal.commitHomeRollForward(server, sealed,
                        M7HomeClosureGameTests.withStop(sealed, RootReason.ROOT_DISABLED, server.getOverworld().getTime())), "原艙停止原因已 checked latch");
                M7HomeClosureGameTests.until(c, label + "/first_lease", 40, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                    var witness = new LinkedHashMap<String, Boolean>();
                    var lease = EntryTickets.describe(server, sid);
                    var frozen = journal.allFlushedSessions().get(sid);
                    witness.put("bStillUnloaded", !anyLoaded(p.b, homeChunks(p.frame)));
                    witness.put("rootReasonAlreadyFrozen", frozen instanceof HomeSessionRecord row && row.equals(journal.allSessions().get(sid))
                            && row.home().closeIntent() instanceof Close close && close.reason() == CloseReason.ROOT_DISABLED && close.doorGoal() == DoorGoal.OPEN);
                    witness.put("firstLeaseIsCloseConverge", lease.equals("CLOSE_CONVERGE refs=" + homeChunks(p.frame).size()));
                    fake.restore();
                    hooks.add(M7HomeClosureGameTests.holdChunks(p.b, p.frame));
                    M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS,
                            () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), guard(p, hooks, () -> {
                                try {
                                    var receipt = p.receipts().lookup(sid).orElseThrow();
                                    witness.put("receiptKeepsTheRootReason", receipt.home().closeIntent() instanceof Close close && close.reason() == CloseReason.ROOT_DISABLED);
                                    witness.put("ticketReleased", EntryTickets.lease(server, sid) == null);
                                    finish(c, p, hooks, witness, "RESTART_ROOT_STOP_WRONG_TICKET：" + witness + " 首票=" + lease);
                                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                            }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "RESTART_ROOT_STOP_CLEANUP：" + witness + " 首票=" + lease));
                }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "RESTART_ROOT_STOP_NO_TICKET：" + describe(journal, sid)));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 確認有效 B 收尾票後，以 fixture 載入 exact B 並使控制器失效。決定性錯誤出現的同一 tick 必須記正確 WARN、釋票並退避。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_invalid", tickLimit = 1600)
    public void close_ticket_releases_at_full_with_invalid_entry(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_releases_at_full_with_invalid_entry";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var dormant = p.dormant(sid, 33);
                var watch = TickEndWatch.start(server, sid, hooks);
                p.committed(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())), "原艙停止原因已 checked latch");
                M7HomeClosureGameTests.until(c, label + "/lease", 40, () -> EntryTickets.lease(server, sid) != null, guard(p, hooks, () -> {
                    c.assertTrue(EntryTickets.describe(server, sid).equals("CLOSE_CONVERGE refs=" + homeChunks(p.frame).size()), "同 SID 的正式 CLOSE_CONVERGE 票");
                    hooks.add(M7HomeClosureGameTests.holdChunks(p.b, p.frame));
                    var controller = (ChamberControllerBlockEntity) p.b.getBlockEntity(p.frame.controllerPos());
                    c.assertTrue(controller != null && controller.instanceKind() == ChamberInstanceKind.PROJECTION, "B 原為 exact 投影");
                    controller.setInstanceKind(ChamberInstanceKind.ORIGIN);
                    hooks.add(() -> controller.setInstanceKind(ChamberInstanceKind.PROJECTION));
                    c.assertTrue(HomeEndpointAuthority.inspectEntry(server, p.entry) == HomeEndpointAuthority.EntryStatus.ENTRY_AUTHORITY_INVALID,
                            "FULL 後能確定 B 端點不合法");
                    int invalidAt = server.getTicks();
                    M7HomeClosureGameTests.until(c, label + "/invalid_tick_end", 40, () -> server.getTicks() > invalidAt, guard(p, hooks, () -> {
                        var witness = new LinkedHashMap<String, Boolean>();
                        witness.put("leaseWasHeldBeforeInvalid", watch.leaseAt(invalidAt - 1));
                        witness.put("leaseReleasedAtInvalidTickEnd", !watch.leaseAt(invalidAt) && EntryTickets.lease(server, sid) == null);
                        witness.put("backedOff200AtInvalidTickEnd", watch.delayOf(invalidAt) == 200 && watch.retryAtOf(invalidAt) == invalidAt + 200L);
                        witness.put("warnNamesInvalidEntry", capture.matching(org.apache.logging.log4j.Level.WARN, "收尾核對 B：B 已載入", "ENTRY_AUTHORITY_INVALID", sid.toString()).size() == 1
                                && expiryWarns(capture, sid) == 0);
                        witness.put("keptCheckedCloseAndBusy", "current=CLOSE flushed=CLOSE rollForward=false".equals(describe(journal, sid))
                                && gate().globalChamberBusy(server, p.chamber));
                        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
                        M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS,
                                () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), guard(p, hooks, () -> {
                                    witness.put("closedAfterTheEntryRecovered", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                                    finish(c, p, hooks, witness, "CLOSE_FULL_INVALID_TICKET_RETAINED：" + witness + " invalidAt=" + invalidAt);
                                }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "CLOSE_FULL_INVALID_CLEANUP：" + witness));
                    }), () -> fail(p, hooks, "CLOSE_FULL_INVALID_NO_TICK_END"));
                }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "CLOSE_FULL_INVALID_NO_TICKET"));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 共用 setup 的 checked 寫入真的失敗時，診斷保留 cause，原斷言仍失敗。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_checked_cause", tickLimit = 800)
    public void checked_entry_setup_logs_cause_and_keeps_assertion(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            M7HomeClosureGameTests.until(c, "checked_entry_setup/entry_ready", 380,
                    () -> dev.quantumchamber.persistence.ChunkSaveCheckpoint.ready(p.b, homeChunks(p.frame)), guard(p, hooks, () -> {
                        var sid = UUID.randomUUID();
                        var dormant = p.dormant(sid, 35);
                        var capture = new LogCapture("quantumchamber-testmod");
                        hooks.add(capture::close);
                        var cause = new java.io.IOException("M7_T50_FIXTURE_CONTROL_CAUSE");
                        var fault = new IllegalStateException("M7_T50_FIXTURE_CONTROL_FLUSH", cause);
                        int[] flushes = {0};
                        boolean[] armed = {true};
                        M5GameTestSupport.installFlushProbe(c, state -> {
                            if (armed[0]) { flushes[0]++; throw fault; }
                        }, hooks);
                        GameTestException assertion = null;
                        try { sealedAfterReady(p, dormant); }
                        catch (GameTestException failure) { assertion = failure; }
                        finally { armed[0] = false; }
                        var errors = capture.matching(org.apache.logging.log4j.Level.ERROR, "TESTMOD_FIXTURE_WRITE_FAILED");
                        var witness = new LinkedHashMap<String, Boolean>();
                        witness.put("checkedWriteWasAttemptedOnce", flushes[0] == 1);
                        witness.put("originalAssertionFailed", assertion != null && assertion.getMessage().contains("checked COLLAPSING"));
                        witness.put("originalRowRestored", dormant.equals(journal.allSessions().get(sid)) && dormant.equals(journal.allFlushedSessions().get(sid)));
                        witness.put("oneFixtureError", errors.size() == 1);
                        var error = errors.size() == 1 ? errors.getFirst() : null;
                        witness.put("originalThrowableAndCauseKept", error != null && error.thrown() == fault && error.thrown().getCause() == cause);
                        witness.put("exactFixtureErrorMessage", error != null && error.message().equals("TESTMOD_FIXTURE_WRITE_FAILED fixture=M7 Projection checked COLLAPSING（RESTORED）"
                                + " cause=java.lang.IllegalStateException: M7_T50_FIXTURE_CONTROL_FLUSH <- java.io.IOException: M7_T50_FIXTURE_CONTROL_CAUSE"));
                        finish(c, p, hooks, witness, "CHECKED_ENTRY_SETUP_CAUSE：" + witness + " fixtureErrors=" + errors.size());
                    }), () -> fail(p, hooks, "CHECKED_ENTRY_SETUP_ENV_NOT_READY"));
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 以正式 journal 入口建立開過通道的可恢復封存列。 */
    private static HomeSessionRecord sealedAfterReady(M7HomeClosureGameTests.Projection p, HomeSessionRecord dormant) {
        var server = p.f.server;
        var journal = SessionRecoveryState.get(server);
        var sid = dormant.sessionUuid();
        var h = dormant.home();
        long time = server.getOverworld().getTime();
        var collapse = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), dev.quantumchamber.superposition.SessionState.COLLAPSING,
                new HomeSessionRecord.Home(h.selection(), time, -1, -1, List.of(), List.of(), java.util.Optional.empty(), -1, new HomeSessionRecord.None(),
                        java.util.Optional.empty()));
        p.committed(journal.commitHomeRestore(server, dormant, collapse), "checked COLLAPSING");
        var ready = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), dev.quantumchamber.superposition.SessionState.PASSAGE_READY,
                new HomeSessionRecord.Home(h.selection(), time, time, time + 1200, List.of(), List.of(), java.util.Optional.empty(), -1, new HomeSessionRecord.None(),
                        java.util.Optional.empty()));
        p.committed(journal.commitHomeRestore(server, collapse, ready), "checked READY");
        var sealed = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), dev.quantumchamber.superposition.SessionState.SEALED,
                new HomeSessionRecord.Home(h.selection(), time, time, time + 1200, List.of(), List.of(), java.util.Optional.empty(), -1, new HomeSessionRecord.None(),
                        java.util.Optional.of(HomeSessionRecord.Seal.AUTHORITY_UNREADABLE)));
        p.committed(journal.commitHomeRollForward(server, ready, sealed), "checked SEALED(AUTHORITY_UNREADABLE)，曾 READY");
        return sealed;
    }

    /**
     * spec v1.6 C：每 SID 同時最多一份 A 票或 B 收尾票，取 B 收尾票之前先釋放同 SID 的 A 票。HOME 準備（COLLAPSING、全員返回、無租約）持有 A 票（正式
     * {@code HomeRootTickets.acquire(HOME_PREPARE_PASSAGE)}）時，原艙停止原因在 test callback（A 票的 {@code START_SERVER_TICK} 刷新之後）checked latch；同一個 tick 的
     * 回程終局推進凍結 CLOSE、發現 B 未載入時就要先放掉 A 票再取到 B 收尾票（下一個 tick 看到 B 票、沒有 A 票、沒有「取不到」的 WARN），之後收尾完成。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_root_first", tickLimit = 1600)
    public void close_ticket_releases_the_root_ticket_first(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_releases_the_root_ticket_first";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            whenPregeneratedBUnloaded(c, p, label, hooks, pregen -> {
                var sid = UUID.randomUUID();
                ColdBTimeline.start(p, sid, label, pregen, hooks);
                var dormant = p.dormant(sid, 34);
                var h = dormant.home();
                var collapse = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), dev.quantumchamber.superposition.SessionState.COLLAPSING,
                        new HomeSessionRecord.Home(h.selection(), server.getOverworld().getTime(), -1, -1, List.of(), List.of(), java.util.Optional.empty(), -1,
                                new HomeSessionRecord.None(), java.util.Optional.empty()));
                c.assertTrue(journal.commitHomeRestore(server, dormant, collapse).committed(), "checked COLLAPSING（HOME 準備）");
                var root = dev.quantumchamber.collapse.HomeRootTickets.acquire(server, sid, p.root, dev.quantumchamber.collapse.HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE);
                c.assertTrue(root.isPresent() && rootLeasePresent(server, sid) && Budget.reserved(server, sid), "HOME 準備持有 A 票");
                hooks.add(() -> root.ifPresent(dev.quantumchamber.collapse.HomeRootTickets.Lease::close));
                var witness = new LinkedHashMap<String, Boolean>();
                c.assertTrue(journal.commitHomeRollForward(server, collapse, M7HomeClosureGameTests.withStop(collapse, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())).committed(), "原艙停止原因已 checked latch（A 票仍在）");
                witness.put("rootTicketStillHeldAfterTheLatch", rootLeasePresent(server, sid));
                M7HomeClosureGameTests.until(c, label + "/next_tick", 1, () -> false, () -> { }, guard(p, hooks, () -> {
                    witness.put("entryTicketTakenInTheSameTick", EntryTickets.describe(server, sid).startsWith("CLOSE_CONVERGE "));
                    witness.put("rootTicketReleasedFirst", !rootLeasePresent(server, sid));
                    witness.put("noUnavailableWarn", unavailableWarns(capture, sid) == 0);
                    M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, () -> !journal.allSessions().containsKey(sid)
                            && !journal.allFlushedSessions().containsKey(sid), guard(p, hooks, () -> {
                        witness.put("closed", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                        witness.put("noTicketAfterTheClose", EntryTickets.lease(server, sid) == null && !rootLeasePresent(server, sid));
                        finish(c, p, hooks, witness, "取 B 收尾票之前先釋放 A 票：" + witness);
                    }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "ROOT_FIRST_NOT_CLOSED：" + witness));
                }));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T5 Step 0（T4B 審查 M-2）：CLOSE 已 checked、B 未載入時，先觀察 owner 的第一份 exact CLOSE_CONVERGE 活票，才由 fixture 提供 B 載入前提。
     * B 門改為 OPEN 的寫入受 {@link DoorWriteFault#failOpens} 阻擋。只有命中實際寫入、同 SID 的門 WARN 和該 tick 的退避樣本，才進行門失敗驗證。
     * 核失敗前一個 tick 持票、失敗當下及整個 200 tick 退避期間無票。解除故障後，由 owner 等退避到期再收尾。
     * 用來殺「{@code backOff} 不釋票」的單點變異（只拿掉 {@code backOff} 的釋放時，票要到 200 tick 租期才由票 owner 釋放）。
     * 助載的同一個 test callback 同步（不消耗 tick）把 home chunks 半徑 2 載入到 FULL（{@link #loadEntityTickingRegion}），並自檢已全部 FULL；
     * 門寫入失敗的瞬間另核仍持同一份首票（{@link DoorFaultLease}）。fixture 保持 B 載入時租期到期不經 owner 的到期 WARN，只有這個見證能排除「票已到期才寫門」。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_entry_ticket_handoff_fail", tickLimit = 2000)
    public void close_ticket_released_when_the_door_write_fails_and_backs_off(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(c, hooks);
        var label = "close_ticket_released_when_the_door_write_fails_and_backs_off";
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var capture = M5GameTestSupport.captureLogs(hooks);
            // B 門先設成普通關門：ROOT_DISABLED 的結案目標是一般開門，owner 才會寫門（門已是目標時 applyTarget 是 UNCHANGED、不寫入）。
            whenBUnloaded(c, p, label, hooks, () -> c.assertTrue(new dev.quantumchamber.chamber.ChamberDoorService().applyTarget(p.b, p.frame,
                    dev.quantumchamber.chamber.ChamberDoorService.DoorState.CLOSED).succeeded() && "CLOSED/NONE".equals(p.doorSummary()), "B 的門是普通關門"), () -> {
                var sid = UUID.randomUUID();
                var dormant = p.dormant(sid, 33);
                var witness = new LinkedHashMap<String, Boolean>();
                var fault = DoorWriteFault.failOpens(p.b, p.frame);
                hooks.add(fault::close);
                var watch = TickEndWatch.start(server, sid, hooks);
                var removal = RemovalWatch.start(server, sid, hooks);
                var chunks = homeChunks(p.frame);
                var diagnostic = new DoorHandoffDiagnostic(c, p, sid, chunks);
                hooks.add(diagnostic::emitOnce);
                watch.diagnostic = diagnostic;
                var atFault = new DoorFaultLease(server, sid, watch);
                fault.observeFirstHit(() -> {
                    atFault.sample();
                    diagnostic.atDoorFault(watch);
                });
                int[] assistedAt = {-1};
                boolean[] regionFullAtAssist = {false};
                int latchedAt = server.getTicks();
                p.committed(journal.commitHomeRollForward(server, dormant, M7HomeClosureGameTests.withStop(dormant, RootReason.ROOT_DISABLED,
                        server.getOverworld().getTime())), "原艙停止原因已 checked latch");
                M7HomeClosureGameTests.until(c, label + "/door_failure", 400, () -> {
                    if (watch.failureTick() >= 0) return true;
                    int firstLeaseTick = watch.firstLeaseTick();
                    if (assistedAt[0] < 0 && firstLeaseTick >= latchedAt && firstLeaseTick < server.getTicks()
                            && firstExactCloseLeaseIsHeld(p, sid, watch, chunks)) {
                        hooks.add(M7HomeClosureGameTests.holdChunks(p.b, p.frame));
                        loadEntityTickingRegion(p.b, chunks);
                        // 自檢用獨立的字面值 2（不讀 ENTITY_TICKING_RADIUS），常數被改時仍能抓到。
                        regionFullAtAssist[0] = regionAround(chunks, 2).stream().allMatch(pos -> p.b.getChunkManager().getWorldChunk(pos.x, pos.z) != null);
                        assistedAt[0] = server.getTicks();
                        diagnostic.afterAssist(watch);
                    }
                    return false;
                }, guard(p, hooks, () -> {
                    fault.close();
                    int failureTick = watch.failureTick();
                    long retryAt = watch.retryAtOf(failureTick);
                    witness.put("doorWriteFaultHit", fault.failures() >= 1);
                    witness.put("oneDoorWarn", doorWarns(capture, sid) == 1);
                    witness.put("observedAtTheNextTick", server.getTicks() == failureTick + 1);
                    witness.put("fixtureAssistedAfterTheExactLease", assistedAt[0] > watch.firstLeaseTick() && assistedAt[0] <= failureTick);
                    witness.put("entityRegionFullAtAssist", regionFullAtAssist[0]);
                    witness.put("leaseLiveAtTheDoorFault", atFault.live());
                    int expiryWarnCount = expiryWarns(capture, sid);
                    if (witness.containsValue(false) || expiryWarnCount != 0) {
                        String reason;
                        if (expiryWarnCount != 0) reason = "HANDOFF_FAIL_ENTRY_TICKET_EXPIRED";
                        // 審查 M-1：只有助載真的發生過，才可能是「助載時區域未 FULL」；助載前就退避落到其他分支。
                        else if (assistedAt[0] >= 0 && !regionFullAtAssist[0]) reason = "HANDOFF_FAIL_ENTITY_REGION_NOT_FULL_AT_ASSIST";
                        else if (atFault.sampled() && !atFault.live())
                            reason = atFault.deadline() >= 0 && atFault.deadline() <= atFault.faultTick() ? "HANDOFF_FAIL_LEASE_EXPIRED_BEFORE_DOOR_FAULT"
                                    : "HANDOFF_FAIL_NO_LIVE_LEASE_AT_DOOR_FAULT";
                        else reason = "HANDOFF_FAIL_OTHER_BACKOFF";
                        var warnings = capture.matching(org.apache.logging.log4j.Level.WARN, sid.toString()).stream().map(LogCapture.Entry::message).toList();
                        fail(p, hooks, reason + "：未證明在持票時因門寫入失敗而退避：" + witness + " firstLease=" + watch.firstLeaseTick()
                                + " assistedAt=" + assistedAt[0] + " failureTick=" + failureTick + " atFault=" + atFault + " warns=" + warnings);
                        return;
                    }
                    witness.put("backedOff200", watch.delayOf(failureTick) == 200 && retryAt == failureTick + 200L);
                    witness.put("leaseHeldBeforeTheFailure", watch.leaseAt(failureTick - 1));
                    witness.put("noTicketAtTheFailureTickEnd", !watch.leaseAt(failureTick));
                    M7HomeClosureGameTests.until(c, label + "/backoff", 260, () -> server.getTicks() >= retryAt, guard(p, hooks, () -> {
                        witness.put("noTicketDuringTheBackoff", watch.noLeaseBetween(failureTick, retryAt - 1));
                        if (witness.containsValue(false)) {
                            fail(p, hooks, "HANDOFF_FAIL_TICKET_HELD：收尾失敗進退避時沒有釋放 B 收尾票：" + witness + " failureTick=" + failureTick + " retryAt=" + retryAt
                                    + " leaseTicks=" + watch.leaseTicks(failureTick - 1, retryAt));
                            return;
                        }
                        M7HomeClosureGameTests.until(c, label + "/close", CLOSE_TICKS, removal::removedSeen, guard(p, hooks, () -> {
                            try {
                                witness.put("checkedRemoval", !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid));
                                witness.put("closedAfterTheRetry", "OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber));
                                witness.put("noTicketAfterTheClose", !removal.leaseAfterRemoval() && EntryTickets.lease(server, sid) == null);
                                witness.put("stillOneDoorWarn", doorWarns(capture, sid) == 1);
                                var receipt = p.receipts().lookup(sid).orElseThrow();
                                witness.put("receiptMatchesTheClose", receipt.sessionUuid().equals(sid) && receipt.entryEndpoint().equals(p.entry)
                                        && receipt.home().closeIntent() instanceof Close close && close.reason() == CloseReason.ROOT_DISABLED && close.doorGoal() == DoorGoal.OPEN);
                                finish(c, p, hooks, witness, "B 門寫入失敗進退避時釋放 B 收尾票：" + witness + " failureTick=" + failureTick + " retryAt=" + retryAt);
                            } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                        }), () -> pendingWithoutTicket(c, p, hooks, sid, label, "HANDOFF_FAIL_RETRY_NOT_CLOSED：解除故障、退避到期之後沒有收尾：" + witness));
                    }), () -> fail(p, hooks, "HANDOFF_FAIL_BACKOFF_WAIT：260 tick 內沒有到 retryAt：" + witness));
                }), () -> fail(p, hooks, "HANDOFF_FAIL_NO_FAILURE：400 tick 內沒有因 B 門寫入失敗而退避：row=" + describe(journal, sid) + " lease="
                        + EntryTickets.describe(server, sid) + " openFailures=" + fault.failures() + " doorWarns=" + doorWarns(capture, sid)
                        + " firstLease=" + watch.firstLeaseTick() + " assistedAt=" + assistedAt[0]));
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    private static int doorWarns(LogCapture capture, UUID sid) {
        return capture.matching(org.apache.logging.log4j.Level.WARN, "B 門尚未改為結案目標", sid.toString()).size();
    }

    /**
     * 1.21 {@code ServerChunkLoadingManager.makeChunkEntitiesTickable}＝{@code getRegion(holder, 2, FULL)}：正式票（level 31，ENTITY_TICKING）的 home chunk
     * 要等半徑 2 全部 FULL 才開始讀 entity。
     */
    private static final int ENTITY_TICKING_RADIUS = 2;

    /** {@code home} 每一格 Chebyshev 距離 ≤ {@code radius} 的 chunks 聯集，依 x、z 排序。 */
    private static Set<ChunkPos> regionAround(Set<ChunkPos> home, int radius) {
        var region = new java.util.TreeSet<ChunkPos>(java.util.Comparator.comparingInt((ChunkPos pos) -> pos.x).thenComparingInt(pos -> pos.z));
        for (var center : home)
            for (int x = center.x - radius; x <= center.x + radius; x++)
                for (int z = center.z - radius; z <= center.z + radius; z++) region.add(new ChunkPos(x, z));
        return java.util.Collections.unmodifiableSet(region);
    }

    /**
     * 門案 fixture 的助載與冷 B 案的先生成（{@link #pregenerate}）：同步把 home chunks 半徑 {@value #ENTITY_TICKING_RADIUS} 載入到 FULL
     * （與 {@code holdChunks} 同樣的同步 {@code getChunk}，不消耗 tick）。不加持久票：同步載入時原版加的 UNKNOWN 票是 level 33，下一個 tick 過期。
     * 門案助載時正式票已讓這個範圍的目標是 FULL，不改任何 chunk 的票等級；先生成時沒有正式票，之後這些 chunks 照常卸載。
     */
    private static void loadEntityTickingRegion(ServerWorld world, Set<ChunkPos> home) {
        for (var pos : regionAround(home, ENTITY_TICKING_RADIUS)) world.getChunk(pos.x, pos.z);
    }

    /** 冷 B 案的先生成結果（{@link #pregenerate}）；沒有執行時是 {@link Pregen#NOT_RUN}。 */
    private record Pregen(boolean ran, int regionChunks, long wallMillis) {
        static final Pregen NOT_RUN = new Pregen(false, 0, -1);
    }

    /**
     * 冷 B 案（B 未載入時由正式 B 收尾票載入 B）的 fixture：同 {@link #whenBUnloaded}，但在 B 就緒、放掉測試票之前先生成 B 地形（{@link #pregenerate}），
     * 之後照原流程等 B 的 home chunks 全部卸載才執行 {@code body}。取票當下 B 仍符合回程終局 owner 的「未載入」判定，正式票仍要自己載入 B，
     * 只是從磁碟讀、不必在票期內現場 worldgen（正式遊玩時玩家去過 B，地形通常已生成）。
     */
    private static void whenPregeneratedBUnloaded(TestContext c, M7HomeClosureGameTests.Projection p, String label, M2CorridorGameTests.TestHooks hooks,
                                                  java.util.function.Consumer<Pregen> body) {
        Pregen[] pregen = {Pregen.NOT_RUN};
        whenBUnloaded(c, p, label, hooks, () -> pregen[0] = pregenerate(p.b, homeChunks(p.frame)), () -> body.accept(pregen[0]));
    }

    /**
     * 先生成 B 地形：同步（不消耗 tick）把 B 各 home chunk 周圍半徑 {@value #ENTITY_TICKING_RADIUS} 的聯集載入到 FULL（{@link #loadEntityTickingRegion}；
     * 1.21 正式票 level 31 要等這個範圍全部 FULL 才開始讀 entity），再自檢全部 FULL，否則以 {@code PREGEN_REGION_NOT_FULL} 失敗。
     */
    private static Pregen pregenerate(ServerWorld world, Set<ChunkPos> home) {
        long start = System.nanoTime();
        loadEntityTickingRegion(world, home);
        long wallMillis = (System.nanoTime() - start) / 1_000_000L;
        // 自檢用獨立的字面值 2（不讀 ENTITY_TICKING_RADIUS），常數或載入範圍被改時仍能抓到。
        var region = regionAround(home, 2);
        var missing = region.stream().filter(pos -> world.getChunkManager().getWorldChunk(pos.x, pos.z) == null).map(pos -> pos.x + "," + pos.z).toList();
        if (!missing.isEmpty()) throw new GameTestException("PREGEN_REGION_NOT_FULL：先生成之後 B 各 home chunk 周圍半徑 2 的聯集沒有全部 FULL：missing=" + missing
                + " region=" + region.size() + " home=" + home.size());
        return new Pregen(true, region.size(), wallMillis);
    }

    /**
     * T5-0 冷 B 案的時序觀測，只記錄、不當判定。每個 tick 結束時（{@code END_SERVER_TICK} 預設 phase 的回程終局 owner 推進之後）讀這個 SID 的 B 收尾票，
     * 以及 B home chunks 的方塊（FULL）與 entity 就緒（讀法同 {@link DoorHandoffDiagnostic}：不寫 chunk cache、不呼叫 checkpoint）。收尾時輸出一行 {@value #MARKER}：
     * 先生成的結果、首票那個 tick 結束時已 FULL 的 home chunk 數（取票當下 B 未載入時是 0）、看到的每份票（用途、refs、首次看到、期限、home chunks 全部 FULL、
     * 不再持有的 tick）、checkpoint 前置（方塊＋entity）全部就緒的 tick、checked 移除的 tick，以及是否重取票。原斷言不讀取這裡；讀取失敗只記 diagnostic-error。
     */
    private static final class ColdBTimeline {
        private static final String MARKER = "M7_T50_COLD_B_V1";
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod");
        private static final net.minecraft.util.Identifier PHASE = net.minecraft.util.Identifier.of("quantumchamber:m7_t50_cold_b_timeline");
        private static final Map<UUID, ColdBTimeline> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
        private static boolean registered;

        /** 看到的一份票；{@code fullAt}／{@code goneAt} 還沒發生時是 −1。 */
        private static final class Span {
            final Object lease;
            final String purpose;
            final int refs;
            final long deadline;
            final int seenAt;
            int fullAt = -1;
            int goneAt = -1;

            Span(Object lease, String purpose, int refs, long deadline, int seenAt) {
                this.lease = lease; this.purpose = purpose; this.refs = refs; this.deadline = deadline; this.seenAt = seenAt;
            }
        }

        private final MinecraftServer server;
        private final ServerWorld world;
        private final UUID sid;
        private final String label;
        private final Pregen pregen;
        private final List<ChunkPos> chunks;
        private final int startTick;
        private final List<Span> spans = new ArrayList<>();
        private final List<String> errors = new ArrayList<>(4);
        private int errorCount;
        private Integer homeFullAtFirstLeaseEnd;
        private int checkpointReadyTick = -1;
        private int closedTick = -1;
        private boolean rowSeen;
        private boolean done;
        private boolean emitted;

        private ColdBTimeline(M7HomeClosureGameTests.Projection p, UUID sid, String label, Pregen pregen) {
            server = p.f.server;
            world = p.b;
            this.sid = sid;
            this.label = label;
            this.pregen = pregen;
            chunks = List.copyOf(homeChunks(p.frame));
            startTick = server.getTicks();
        }

        static synchronized ColdBTimeline start(M7HomeClosureGameTests.Projection p, UUID sid, String label, Pregen pregen, M2CorridorGameTests.TestHooks hooks) {
            if (!registered) {
                registered = true;
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, PHASE);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PHASE, owner -> ACTIVE.values().forEach(timeline -> timeline.observe(owner)));
            }
            var timeline = new ColdBTimeline(p, sid, label, pregen);
            ACTIVE.put(sid, timeline);
            hooks.add(timeline::emitOnce);
            hooks.add(() -> ACTIVE.remove(sid, timeline));
            return timeline;
        }

        private void observe(MinecraftServer owner) {
            if (owner != server || done) return;
            try {
                int tick = server.getTicks();
                var lease = EntryTickets.lease(server, sid);
                var last = spans.isEmpty() ? null : spans.getLast();
                if (last != null && last.goneAt < 0 && last.lease != lease) last.goneAt = tick;
                var ready = readHome();
                boolean blocks = ready[0] == chunks.size();
                if (lease != null && (last == null || last.lease != lease)) {
                    last = new Span(lease, M4CandidateTestAccess.get(lease, "purpose").toString(), ((Set<?>) M4CandidateTestAccess.get(lease, "chunks")).size(),
                            (long) M4CandidateTestAccess.get(lease, "deadline"), tick);
                    spans.add(last);
                    if (spans.size() == 1) homeFullAtFirstLeaseEnd = ready[0];
                }
                if (lease != null && last.fullAt < 0 && blocks) last.fullAt = tick;
                if (checkpointReadyTick < 0 && blocks && ready[1] == chunks.size()) checkpointReadyTick = tick;
                var journal = SessionRecoveryState.get(server);
                if (journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)) rowSeen = true;
                else if (rowSeen) { closedTick = tick; done = true; }
            } catch (RuntimeException | LinkageError failure) {
                errorCount++;
                if (errors.size() < 4) errors.add(failure.getClass().getName());
            }
        }

        /** home chunks 中方塊已 FULL 與 entity 已載入的數量（只讀 cache 與 holder，同 {@link DoorHandoffDiagnostic#worldChunkIsReady}）。 */
        private int[] readHome() {
            var manager = world.getChunkManager();
            var positions = (long[]) M4CandidateTestAccess.get(manager, "chunkPosCache");
            var statuses = (Object[]) M4CandidateTestAccess.get(manager, "chunkStatusCache");
            var cache = (Object[]) M4CandidateTestAccess.get(manager, "chunkCache");
            var holders = (Long2ObjectMap<?>) M4CandidateTestAccess.get(M4CandidateTestAccess.get(manager, "chunkLoadingManager"), "chunkHolders");
            var entities = ((ServerWorldRecoveryStorageAccessor) world).quantumchamber$entityManager();
            int blocks = 0, loaded = 0;
            for (var pos : chunks) {
                long key = pos.toLong();
                if (DoorHandoffDiagnostic.worldChunkIsReady(key, positions, statuses, cache, holders)) blocks++;
                if (entities.isLoaded(key)) loaded++;
            }
            return new int[]{blocks, loaded};
        }

        void emitOnce() {
            if (emitted) return;
            emitted = true;
            String payload;
            try {
                var data = new LinkedHashMap<String, Object>();
                data.put("case", label);
                data.put("sid", sid.toString());
                var generated = new LinkedHashMap<String, Object>();
                generated.put("ran", pregen.ran());
                generated.put("regionChunks", pregen.regionChunks());
                generated.put("wallMillis", pregen.wallMillis());
                data.put("pregen", generated);
                data.put("homeChunks", chunks.size());
                data.put("startTick", startTick);
                var first = spans.isEmpty() ? null : spans.getFirst();
                data.put("firstLeaseTick", first == null ? null : first.seenAt);
                data.put("homeFullAtFirstLeaseEnd", homeFullAtFirstLeaseEnd);
                data.put("firstLeaseFullTick", first == null || first.fullAt < 0 ? null : first.fullAt);
                data.put("checkpointReadyTick", checkpointReadyTick < 0 ? null : checkpointReadyTick);
                data.put("closedTick", closedTick < 0 ? null : closedTick);
                data.put("firstLeaseToReadyTicks", first == null || checkpointReadyTick < 0 ? null : checkpointReadyTick - first.seenAt);
                data.put("reacquired", spans.size() > 1);
                var leases = new ArrayList<Map<String, Object>>();
                for (var span : spans) {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("purpose", span.purpose);
                    value.put("refs", span.refs);
                    value.put("seenAt", span.seenAt);
                    value.put("deadline", span.deadline);
                    value.put("fullAt", span.fullAt < 0 ? null : span.fullAt);
                    value.put("goneAt", span.goneAt < 0 ? null : span.goneAt);
                    leases.add(value);
                }
                data.put("leases", leases);
                data.put("status", errorCount == 0 ? "captured" : "diagnostic-error");
                data.put("diagnosticErrorCount", errorCount);
                data.put("diagnosticErrors", List.copyOf(errors));
                payload = new com.google.gson.GsonBuilder().serializeNulls().create().toJson(data);
            } catch (RuntimeException | LinkageError failure) {
                payload = "{\"status\":\"diagnostic-error\",\"case\":\"" + label + "\",\"phase\":\"serialize\"}";
            }
            try { LOG.info("{} {}", MARKER, payload); }
            catch (RuntimeException | LinkageError ignored) { /* 日誌失敗保持原測試判定。 */ }
        }
    }

    /**
     * 門案的正式見證：B 門寫入失敗的瞬間（{@link DoorWriteFault.OpenFailure#observeFirstHit}，回程終局 owner 的 {@code applyTarget} 之內）是否仍持同一份首票、
     * 不在清理中、而且 tick 小於租期期限。取樣例外記為「無活票」並保留類別，絕不丟回 production（否則會變成交接 WARN 與另一種退避）。
     */
    private static final class DoorFaultLease {
        private final MinecraftServer server;
        private final UUID sid;
        private final TickEndWatch watch;
        private boolean sampled;
        private boolean live;
        private int faultTick = -1;
        private long deadline = -1;
        private String error;

        DoorFaultLease(MinecraftServer server, UUID sid, TickEndWatch watch) { this.server = server; this.sid = sid; this.watch = watch; }

        void sample() {
            if (sampled) return;
            sampled = true;
            try {
                faultTick = server.getTicks();
                var first = watch.firstLease();
                if (first != null) deadline = (long) M4CandidateTestAccess.get(first, "deadline");
                var lease = EntryTickets.lease(server, sid);
                live = lease != null && lease == first && !(Boolean) M4CandidateTestAccess.get(lease, "closing") && faultTick < deadline;
            } catch (RuntimeException | LinkageError failure) {
                live = false;
                error = failure.getClass().getName();
            }
        }

        boolean sampled() { return sampled; }

        boolean live() { return live; }

        int faultTick() { return faultTick; }

        long deadline() { return deadline; }

        @Override public String toString() {
            return "{sampled=" + sampled + ", live=" + live + ", faultTick=" + faultTick + ", deadline=" + deadline + ", error=" + error + "}";
        }
    }

    /** 一次性收集門案的診斷資料。只保存固定數量的時點與當時的值，原斷言不讀取這裡。 */
    private static final class DoorHandoffDiagnostic {
        private static final String MARKER = "M7_T50_DOOR_DIAG_V1";
        private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod");
        private record Clock(int serverTick, long gameTime, long caseTick) {}
        private record LeaseValue(boolean present, Boolean closing, Integer identity, Boolean sameAsFirst, Boolean sameFixtureWorld,
                                  Long acquiredTick, Long deadline, String purpose, String sid, String world, String entry, Integer refs) {}
        private record ChunkReady(String chunk, boolean block, boolean entities) {}
        private record Ready(boolean blocks, boolean entities, Boolean checkpoint, String checkpointUnknown, List<ChunkReady> chunks) {}
        private record Snapshot(Clock clock, LeaseValue live, Boolean firstLeaseClosing, Ready ready) {}
        private record EndEvidence(boolean sampleExists, Boolean held, Snapshot snapshot) {}

        private final TestContext context;
        private final MinecraftServer server;
        private final ServerWorld world;
        private final UUID sid;
        private final List<ChunkPos> chunks;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>(4);
        private int errorCount;
        private boolean emitted;
        private Snapshot lastEnd;
        private Integer faultTick;

        DoorHandoffDiagnostic(TestContext context, M7HomeClosureGameTests.Projection projection, UUID sid, Set<ChunkPos> chunks) {
            this.context = context;
            server = projection.f.server;
            world = projection.b;
            this.sid = sid;
            this.chunks = List.copyOf(chunks);
        }

        void afterAssist(TickEndWatch watch) {
            capture("assist", () -> {
                var snapshot = snapshot(EntryTickets.lease(server, sid), watch.firstLease());
                data.put("assisted", snapshot);
                rememberReady("assist", snapshot);
            });
        }

        void atDoorFault(TickEndWatch watch) {
            capture("door-fault", () -> {
                var snapshot = snapshot(EntryTickets.lease(server, sid), watch.firstLease());
                faultTick = snapshot.clock().serverTick();
                data.put("doorFault", snapshot);
                savePreviousEnd("doorFaultPreviousEnd", faultTick, watch);
                rememberReady("door-fault", snapshot);
            });
        }

        void atEnd(TickEndWatch watch) {
            if (data.containsKey("failureEnd")) return;
            capture("end", () -> {
                var sample = watch.samples.getLast();
                var first = watch.firstLease();
                var snapshot = snapshot(sample.lease(), first);
                if (sample.lease() != null && sample.lease() == first) data.putIfAbsent("firstLeaseEnd", snapshot);
                if (first != null && sample.lease() == null) data.putIfAbsent("firstEndWithoutLease", snapshot);
                rememberReady("end", snapshot);
                if (faultTick != null && sample.tick() == faultTick) data.put("doorFaultEnd", snapshot);
                if (sample.delay() > 0) {
                    savePreviousEnd("failurePreviousEnd", sample.tick(), watch);
                    data.put("failureEnd", snapshot);
                    data.put("failureDelay", sample.delay());
                    data.put("retryAt", sample.retryAt());
                }
                lastEnd = snapshot;
            });
        }

        private void savePreviousEnd(String key, int tick, TickEndWatch watch) {
            var sample = watch.at(tick - 1);
            var snapshot = lastEnd != null && lastEnd.clock().serverTick() == tick - 1 ? lastEnd : null;
            data.put(key, new EndEvidence(sample != null, sample == null ? null : sample.lease() != null, snapshot));
        }

        private void rememberReady(String phase, Snapshot snapshot) {
            var ready = snapshot.ready();
            if (ready == null) return;
            var moment = Map.of("phase", phase, "clock", snapshot.clock());
            if (ready.blocks()) data.putIfAbsent("firstBlocksReady", moment);
            if (ready.entities()) data.putIfAbsent("firstEntitiesReady", moment);
            if (Boolean.TRUE.equals(ready.checkpoint())) data.putIfAbsent("firstCheckpointReady", moment);
        }

        private Snapshot snapshot(Object live, Object first) {
            var clock = new Clock(server.getTicks(), server.getOverworld().getTime(), context.getTick());
            var lease = read("live-lease", () -> leaseValue(live, first));
            var closing = first == null ? null : read("first-lease-closing", () -> (Boolean) M4CandidateTestAccess.get(first, "closing"));
            return new Snapshot(clock, lease, closing, read("checkpoint-readiness", this::readReady));
        }

        private LeaseValue leaseValue(Object lease, Object first) {
            if (lease == null) return new LeaseValue(false, null, null, null, null, null, null, null, null, null, null, null);
            long deadline = (long) M4CandidateTestAccess.get(lease, "deadline");
            var leaseWorld = (ServerWorld) M4CandidateTestAccess.get(lease, "world");
            return new LeaseValue(true, (Boolean) M4CandidateTestAccess.get(lease, "closing"), System.identityHashCode(lease), lease == first, leaseWorld == world,
                    deadline - HomeEntryTickets.LEASE_TICKS, deadline, M4CandidateTestAccess.get(lease, "purpose").toString(),
                    M4CandidateTestAccess.get(lease, "sid").toString(), leaseWorld.getRegistryKey().getValue().toString(),
                    M4CandidateTestAccess.get(lease, "entry").toString(), ((Set<?>) M4CandidateTestAccess.get(lease, "chunks")).size());
        }

        private Ready readReady() {
            // 讀 1.21 getWorldChunk 的 cache 與 FULL holder，避開該方法本身的 profiler 與 cache 寫入。
            var manager = world.getChunkManager();
            var positions = (long[]) M4CandidateTestAccess.get(manager, "chunkPosCache");
            var statuses = (Object[]) M4CandidateTestAccess.get(manager, "chunkStatusCache");
            var cache = (Object[]) M4CandidateTestAccess.get(manager, "chunkCache");
            var loading = M4CandidateTestAccess.get(manager, "chunkLoadingManager");
            var holders = (Long2ObjectMap<?>) M4CandidateTestAccess.get(loading, "chunkHolders");
            var entities = ((ServerWorldRecoveryStorageAccessor) world).quantumchamber$entityManager();
            var observations = new ArrayList<ChunkReady>(chunks.size());
            for (var pos : chunks) {
                long key = pos.toLong();
                boolean block = worldChunkIsReady(key, positions, statuses, cache, holders);
                observations.add(new ChunkReady(pos.x + "," + pos.z, block, entities.isLoaded(key)));
            }
            boolean blocksReady = observations.stream().allMatch(ChunkReady::block);
            boolean entitiesReady = observations.stream().allMatch(ChunkReady::entities);
            // ready 的 fault mixin 會計數，探針不能呼叫它。若該 hook 有效範圍未明，標示 unknown。
            boolean checkpointHookInstalled = M5TestHooks.ENTITY_STORAGE_HOLD.get() != null;
            return new Ready(blocksReady, entitiesReady, checkpointHookInstalled ? null : !chunks.isEmpty() && blocksReady && entitiesReady,
                    checkpointHookInstalled ? "entity-storage-hold-installed" : null, List.copyOf(observations));
        }

        private static boolean worldChunkIsReady(long key, long[] positions, Object[] statuses, Object[] cache, Long2ObjectMap<?> holders) {
            for (int i = 0; i < positions.length; i++) {
                if (positions[i] == key && statuses[i] == ChunkStatus.FULL) return cache[i] instanceof WorldChunk;
            }
            return holders.get(key) instanceof ChunkHolder holder && holder.getOrNull(ChunkStatus.FULL) instanceof WorldChunk;
        }

        private <T> T read(String location, java.util.function.Supplier<T> read) {
            try { return read.get(); }
            catch (RuntimeException | LinkageError failure) { diagnosticError(location, failure); return null; }
        }

        private void capture(String location, Runnable capture) {
            try { capture.run(); }
            catch (RuntimeException | LinkageError failure) { diagnosticError(location, failure); }
        }

        private void diagnosticError(String location, Throwable failure) {
            errorCount++;
            if (errors.size() < 4) errors.add(location + ":" + failure.getClass().getName());
        }

        void emitOnce() {
            if (emitted) return;
            emitted = true;
            data.put("sid", sid.toString());
            data.put("status", errorCount == 0 ? "captured" : "diagnostic-error");
            data.put("diagnosticErrorCount", errorCount);
            data.put("diagnosticErrors", List.copyOf(errors));
            String payload;
            try { payload = new com.google.gson.GsonBuilder().serializeNulls().create().toJson(data); }
            catch (RuntimeException | LinkageError failure) { payload = "{\"status\":\"diagnostic-error\",\"sid\":\"" + sid + "\",\"phase\":\"serialize\"}"; }
            try { LOG.info("{} {}", MARKER, payload); }
            catch (RuntimeException | LinkageError ignored) { /* 日誌失敗保持原測試判定，原件缺 marker 即無法採計。 */ }
        }
    }

    /** fixture 助載前，核 tick 結束時的首票仍是這個 SID 為 exact B 取得的正式票。 */
    private static boolean firstExactCloseLeaseIsHeld(M7HomeClosureGameTests.Projection p, UUID sid, TickEndWatch watch, Set<ChunkPos> chunks) {
        var server = p.f.server;
        var lease = EntryTickets.lease(server, sid);
        return lease != null && lease == watch.firstLease() && chunks.size() <= 4
                && EntryTickets.describe(server, sid).equals("CLOSE_CONVERGE refs=" + chunks.size())
                && sid.equals(M4CandidateTestAccess.get(lease, "sid")) && p.entry.equals(M4CandidateTestAccess.get(lease, "entry"))
                && p.b == M4CandidateTestAccess.get(lease, "world") && chunks.equals(M4CandidateTestAccess.get(lease, "chunks"));
    }

    /** 原艙票 owner（{@code HomeRootTickets}）是否仍有本 SID 的 lease（反射讀 private 欄位，只讀）。 */
    private static boolean rootLeasePresent(MinecraftServer server, UUID sid) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(dev.quantumchamber.collapse.HomeRootTickets.class, "servers")).get(server);
        return owner != null && ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).containsKey(sid);
    }

    private static final int LEASE_TICKS = 200;

    /** 斷言全部成立後完成；收尾先於丟出。 */
    private static void finish(TestContext c, M7HomeClosureGameTests.Projection p, M2CorridorGameTests.TestHooks hooks, Map<String, Boolean> witness, String message) {
        var failure = witness.containsValue(false) ? new GameTestException("B_CLOSE_TICKET：" + message) : null;
        p.close(); hooks.release();
        if (failure != null) throw failure;
        c.complete();
    }

    private static void fail(M7HomeClosureGameTests.Projection p, M2CorridorGameTests.TestHooks hooks, String message) {
        try { throw new GameTestException(message); } finally { p.close(); hooks.release(); }
    }

    private static int expiryWarns(LogCapture capture, UUID sid) {
        return capture.matching(org.apache.logging.log4j.Level.WARN, "B 收尾票在 ", "內沒有就緒", sid.toString()).size();
    }

    private static int unavailableWarns(LogCapture capture, UUID sid) {
        return capture.matching(org.apache.logging.log4j.Level.WARN, "B 收尾票取不到", sid.toString()).size();
    }

    private static int ticketWarns(LogCapture capture, UUID sid) {
        return capture.matching(org.apache.logging.log4j.Level.WARN, "B 收尾票", sid.toString()).size();
    }

    private static int capErrors(LogCapture capture, UUID sid) {
        return capture.matching(org.apache.logging.log4j.Level.ERROR, "B 收尾票退避到上限", sid.toString()).size();
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
        whenBUnloaded(c, p, label, hooks, () -> { }, body);
    }

    /** 同上；{@code beforeRelease} 在 B 就緒、放掉測試票之前執行（例如先把 B 門設成普通關門）。失敗時收掉 fixture 再原樣丟出。 */
    static void whenBUnloaded(TestContext c, M7HomeClosureGameTests.Projection p, String label, M2CorridorGameTests.TestHooks hooks, Runnable beforeRelease, Runnable body) {
        var chunks = homeChunks(p.frame);
        M7HomeClosureGameTests.until(c, label + "/entry_ready", 380, () -> dev.quantumchamber.persistence.ChunkSaveCheckpoint.ready(p.b, chunks), () -> {
            guard(p, hooks, beforeRelease).run();
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
        // 先記下逾時當下的門檻（重新保持 B 之前），再保持 B 收尾殘留。
        var failure = new GameTestException(message + " thresholds=" + M7HomeClosureGameTests.closureThresholds(p, sid));
        var rehold = M7HomeClosureGameTests.holdChunks(p.b, p.frame);
        M7HomeClosureGameTests.until(c, label + "/cleanup", 300, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
            try { throw failure; } finally { rehold.run(); p.close(); hooks.release(); }
        }, () -> {
            try { throw failure; } finally { rehold.run(); p.close(); hooks.release(); }
        });
    }

    /** 保留本案原判，再以正式 owner 清理這個 fixture 已 checked 的單一 CLOSE，避免第二次退避超過清理窗口而連坐。 */
    private static void pendingBudgetClose(TestContext c, M7HomeClosureGameTests.Projection p, M2CorridorGameTests.TestHooks hooks, UUID sid, String label, String message) {
        var server = p.f.server;
        var journal = SessionRecoveryState.get(server);
        var failure = new GameTestException(message + " thresholds=" + M7HomeClosureGameTests.closureThresholds(p, sid));
        try {
            journal.requireHealthy();
            if (journal.allSessions().get(sid) instanceof HomeSessionRecord row && row.chamberUuid().equals(p.chamber)
                    && row.entryEndpoint().equals(p.entry) && row.home().closeIntent() instanceof Close
                    && row.equals(journal.allFlushedSessions().get(sid)) && !journal.rollForwardPending(sid)
                    && !journal.pendingSessions().containsKey(sid) && !journal.quarantinedSessions().contains(sid)) {
                hooks.add(M7HomeClosureGameTests.holdChunks(p.b, p.frame));
                if (EntryTickets.backoff(server, sid) instanceof CollapseBackoff backoff) backoff.reset();
            }
        } catch (RuntimeException | Error cleanup) {
            failure.addSuppressed(cleanup);
        }
        Runnable finishFailure = () -> {
            try { p.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            hooks.releaseAfter(failure);
            throw failure;
        };
        M7HomeClosureGameTests.until(c, label + "/cleanup", 300, () -> !journal.allSessions().containsKey(sid)
                && !journal.allFlushedSessions().containsKey(sid), finishFailure, finishFailure);
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

        /** 回程終局 owner（{@code HomeClosure}）本 instance 對這個 SID 的退避（沒有時 null）。 */
        static Object backoff(MinecraftServer server, UUID sid) {
            var context = ((Map<?, ?>) M4CandidateTestAccess.get(dev.quantumchamber.collapse.HomeClosure.class, "CONTEXTS")).get(server);
            return context == null ? null : ((Map<?, ?>) M4CandidateTestAccess.get(context, "backoff")).get(sid);
        }

        /** 票 owner 的 {@code closeAll(server, stop)}（private static，反射呼叫；只動 B 收尾票，不觸發其他模組的生命週期事件）。 */
        static void closeAll(MinecraftServer server, boolean stop) {
            try {
                var method = type().getDeclaredMethod("closeAll", MinecraftServer.class, boolean.class);
                method.setAccessible(true);
                method.invoke(null, server, stop);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw new GameTestException("B 收尾票 closeAll 丟出：" + failure.getCause());
            } catch (ReflectiveOperationException failure) { throw new GameTestException("B 收尾票 closeAll 讀不到：" + failure); }
        }

        /** 還原票 owner 的停服旗標（{@link #closeAll} 以 stop=true 呼叫之後）。 */
        static void setStopping(MinecraftServer server, boolean stopping) {
            M4CandidateTestAccess.set(((Map<?, ?>) M4CandidateTestAccess.get(type(), "servers")).get(server), "stopping", stopping);
        }
    }

    /**
     * 「不加票、永遠不 FULL」的 B 收尾票 backend：以 {@link java.lang.reflect.Proxy} 實作票 owner 的 package 可見介面 {@code Backend}，反射換進 {@code HomeEntryTickets.backend}
     * （只影響之後新取得的票），記下每個 SID 的加票與移除次數。收尾（或 {@link #restore}）換回原本的 backend。
     */
    static final class FakeBackend {
        private final Object previous;
        private final Map<UUID, Integer> held = new java.util.concurrent.ConcurrentHashMap<>();
        private boolean restored;

        private FakeBackend(Object previous) { this.previous = previous; }

        static FakeBackend install(M2CorridorGameTests.TestHooks hooks) {
            try {
                var type = EntryTickets.type();
                var iface = Class.forName("dev.quantumchamber.collapse.HomeEntryTickets$Backend");
                var field = type.getDeclaredField("backend");
                field.setAccessible(true);
                var fake = new FakeBackend(field.get(null));
                var proxy = java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (self, method, args) -> switch (method.getName()) {
                    case "add" -> { fake.held.merge((UUID) args[2], 1, Integer::sum); yield null; }
                    case "remove" -> { fake.held.merge((UUID) args[2], -1, Integer::sum); yield null; }
                    case "full" -> Boolean.FALSE;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "M7_T4B_NEVER_FULL_BACKEND";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
                field.set(null, proxy);
                hooks.add(fake::restore);
                return fake;
            } catch (ReflectiveOperationException failure) { throw new GameTestException("NEVER_FULL_FIXTURE：" + failure); }
        }

        void restore() {
            if (restored) return;
            restored = true;
            try {
                var field = EntryTickets.type().getDeclaredField("backend");
                field.setAccessible(true);
                field.set(null, previous);
            } catch (ReflectiveOperationException failure) { throw new GameTestException("NEVER_FULL_FIXTURE 還原失敗：" + failure); }
        }

        /** 這個 SID 目前仍掛著的（假）票數：加票次數減移除次數。 */
        int held(UUID sid) { return held.getOrDefault(sid, 0); }
    }

    /** 走廊 authority 的原艙／B 收尾票覆蓋（{@code CorridorPageManager.reserveRootCoverage}）：以遠處的測試 reservation 佔滿 64 份，收尾時釋放。 */
    static final class Budget {
        private Budget() {}

        static List<UUID> fill(MinecraftServer server, M2CorridorGameTests.TestHooks hooks) {
            var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
            var filled = new ArrayList<UUID>();
            hooks.add(() -> release(server, filled));
            for (int i = 0; i < 64; i++) {
                var dummy = UUID.randomUUID();
                if (!pages.reserveRootCoverage(dummy, net.minecraft.world.World.OVERWORLD, List.of(new ChunkPos(900_000 + i, 900_000)))) break;
                filled.add(dummy);
            }
            return filled;
        }

        static void release(MinecraftServer server, List<UUID> filled) {
            var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
            for (var dummy : filled) pages.releaseRootCoverage(dummy);
        }

        /** 還能不能再取一份（試取後立即歸還）。 */
        static boolean free(MinecraftServer server) {
            var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
            var probe = UUID.randomUUID();
            if (!pages.reserveRootCoverage(probe, net.minecraft.world.World.OVERWORLD, List.of(new ChunkPos(-900_000, -900_000)))) return false;
            pages.releaseRootCoverage(probe);
            return true;
        }

        /** 這個 SID 是否仍佔著覆蓋（反射讀 {@code SlotAllocator.rootRefs}，只讀）。 */
        static boolean reserved(MinecraftServer server, UUID sid) {
            var allocator = M4CandidateTestAccess.get(dev.quantumchamber.corridor.CorridorPageManager.forServer(server), "allocator");
            return ((Map<?, ?>) M4CandidateTestAccess.get(allocator, "rootRefs")).containsKey(sid);
        }
    }

    /** 把 SID 加進回程終局 owner 本 instance 的開服集合（反射換掉 {@code HomeClosure.Context.startup}；收尾還原），回傳本 instance 的開服 UUID。 */
    static final class Startup {
        private Startup() {}

        static UUID add(MinecraftServer server, UUID sid, M2CorridorGameTests.TestHooks hooks) {
            var context = ((Map<?, ?>) M4CandidateTestAccess.get(dev.quantumchamber.collapse.HomeClosure.class, "CONTEXTS")).get(server);
            if (context == null) throw new GameTestException("RESTART_VERIFY_FIXTURE：沒有本 instance 的回程終局 owner context");
            var before = (Set<?>) M4CandidateTestAccess.get(context, "startup");
            var with = new java.util.LinkedHashSet<Object>(before);
            with.add(sid);
            M4CandidateTestAccess.set(context, "startup", Set.copyOf(with));
            hooks.add(() -> M4CandidateTestAccess.set(context, "startup", before));
            return (UUID) M4CandidateTestAccess.get(context, "instance");
        }
    }

    /**
     * T5 Step 0：每個 tick 結束時（{@code END_SERVER_TICK} 預設 phase 的回程終局 owner 推進之後）記下這個 SID 是否持 B 收尾票，以及回程終局 owner 對它的退避
     * （{@code CollapseBackoff} 的 delayTicks／retryAtTick；沒有時為 0）。只讀；收尾時停止觀察。用來核「失敗那個 tick 結束時與退避期間都沒有票」：只存在一個 tick
     * 的票、或失敗之後才釋放的票，test callback 看不到。
     */
    static final class TickEndWatch {
        private static final net.minecraft.util.Identifier PHASE = net.minecraft.util.Identifier.of("quantumchamber:m7_t50_tick_end_watch");
        private static final Map<UUID, TickEndWatch> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
        private static boolean registered;
        private record Sample(int tick, Object lease, int delay, long retryAt) {}
        private final MinecraftServer server;
        private final UUID sid;
        private final List<Sample> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        private DoorHandoffDiagnostic diagnostic;

        private TickEndWatch(MinecraftServer server, UUID sid) { this.server = server; this.sid = sid; }

        static synchronized TickEndWatch start(MinecraftServer server, UUID sid, M2CorridorGameTests.TestHooks hooks) {
            if (!registered) {
                registered = true;
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, PHASE);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PHASE, owner -> ACTIVE.values().forEach(watch -> watch.observe(owner)));
            }
            var watch = new TickEndWatch(server, sid);
            ACTIVE.put(sid, watch);
            hooks.add(() -> ACTIVE.remove(sid, watch));
            return watch;
        }

        private void observe(MinecraftServer owner) {
            if (owner != server) return;
            var backoff = EntryTickets.backoff(server, sid) instanceof dev.quantumchamber.collapse.CollapseBackoff value ? value : null;
            samples.add(new Sample(server.getTicks(), EntryTickets.lease(server, sid), backoff == null ? 0 : backoff.delayTicks(),
                    backoff == null ? 0 : backoff.retryAtTick()));
            if (diagnostic != null) diagnostic.atEnd(this);
        }

        private Sample at(int tick) { return samples.stream().filter(sample -> sample.tick() == tick).findFirst().orElse(null); }

        /** 第一次記到退避（delayTicks＞0）的那個 tick；還沒有時為 −1。 */
        int failureTick() { return samples.stream().filter(sample -> sample.delay() > 0).mapToInt(Sample::tick).findFirst().orElse(-1); }

        int delayOf(int tick) { var sample = at(tick); return sample == null ? -1 : sample.delay(); }

        long retryAtOf(int tick) { var sample = at(tick); return sample == null ? -1 : sample.retryAt(); }

        private Sample firstLeaseSample() { return samples.stream().filter(sample -> sample.lease() != null).findFirst().orElse(null); }

        int firstLeaseTick() { var sample = firstLeaseSample(); return sample == null ? -1 : sample.tick(); }

        Object firstLease() { var sample = firstLeaseSample(); return sample == null ? null : sample.lease(); }

        /** 這個 tick 結束時是否持票；沒有這個 tick 的樣本時為假。 */
        boolean leaseAt(int tick) { var sample = at(tick); return sample != null && sample.lease() != null; }

        /** {@code from}～{@code to}（含）每個 tick 結束時都有樣本、而且都沒有票。 */
        boolean noLeaseBetween(int from, long to) {
            for (long tick = from; tick <= to; tick++) {
                var sample = at((int) tick);
                if (sample == null || sample.lease() != null) return false;
            }
            return true;
        }

        /** {@code from}～{@code to}（含）之間持票的 tick（診斷訊息用）。 */
        List<Integer> leaseTicks(int from, long to) {
            return samples.stream().filter(sample -> sample.tick() >= from && sample.tick() <= to && sample.lease() != null).map(Sample::tick).toList();
        }
    }

    /**
     * 每個 tick 結束時的觀察，在 {@code END_SERVER_TICK} 的預設 phase（回程終局 owner 的推進）之後檢查。票 owner 的 {@code START_SERVER_TICK} 會釋放不再適用的票，所以
     * 只存在一個 tick 的票，下一個 tick 的 test callback 看不到；這裡在同一個 tick 結束時看：
     * <ul>
     * <li>checked 移除的那個 tick 結束時已經沒有票（plan Task 4B 變異「移除後不釋票」）；</li>
     * <li>flushed 的列還沒有 CLOSE（CLOSE 未 checked，例如凍結的寫出待確認）時從不持有 B 收尾票（變異「CLOSE 未 checked 就取票」）。</li>
     * </ul>
     * 只讀；收尾時停止觀察。
     */
    static final class RemovalWatch {
        private static final net.minecraft.util.Identifier PHASE = net.minecraft.util.Identifier.of("quantumchamber:m7_t4b_removal_watch");
        private static final Map<UUID, RemovalWatch> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();
        private static boolean registered;
        private final MinecraftServer server;
        private final UUID sid;
        private volatile boolean removedSeen, leaseAfterRemoval, leaseWithoutCheckedClose;

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
            var flushed = journal.allFlushedSessions().get(sid);
            if (flushed != null && !"CLOSE".equals(closeKind(flushed)) && EntryTickets.lease(server, sid) != null) leaseWithoutCheckedClose = true;
            if (journal.allSessions().containsKey(sid) || flushed != null) return;
            removedSeen = true;
            leaseAfterRemoval = EntryTickets.lease(server, sid) != null;
        }

        boolean removedSeen() { return removedSeen; }

        boolean leaseAfterRemoval() { return leaseAfterRemoval; }

        boolean leaseWithoutCheckedClose() { return leaseWithoutCheckedClose; }
    }
}
