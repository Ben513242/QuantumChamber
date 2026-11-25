package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.HomeSessionRecord.Unselected;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;

/**
 * M7 T5-2：從同艙已確認的 COMPLETE 投影 B 進回程走廊（{@code SuperpositionSessionManager.startHome}），以及未選定 HOME 門時的整組返回 B（M7 spec §4.1、§6、§7、
 * §9、§10，V04～V06，v1.6 A）。入場需要原生玩家 checkpoint，所以全部是 Windows 案例（batch 以 {@code _windows} 結尾；使用者 2026-11-24 Windows 優先，平台拒絕以
 * {@link CheckpointPlatformFault} 模擬）。每案各自一個 batch、一座新的 A 與一個新的 B 宇宙，成功路徑一律由正式返還收尾（HOME 列 checked 移除、runtime 與走廊釋放），
 * 不留 ARMING／SUPERPOSITION／RETURNING；改動的全域狀態（checkpoint 能力 WARN 的去重、原艙票的 backend、宇宙 lifecycle 的 ready、journal 寫出故障）都在收尾時還原。
 * 不斷言絕對世界數。正式玩家入口要到 T7 才接，這裡由 testmod 直接呼叫正式 owner。
 */
public final class M7HomeCorridorGameTests implements FabricGameTest {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod");

    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    // ---- 入場 ----

    /**
     * 正式入場：A 檢查通過後 checked ARMING → 逐人原生移動 → checkpoint → checked SUPERPOSITION → 發布 mapping。Entry 是 exact B、參與者快照取自 B；
     * 走廊只有一扇帶 home_marker 的 HOME 門（25 格），入口 overlay（50 格）寫完後它仍完整（T5-1 審查 M-3，以真 Space 驗）；HOME 門不進候選、M4 ledger 零追加
     * （T5-1 審查 M-1）。最後以正式取消返回 B 收尾。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_entry_windows", tickLimit = 2000)
    public void home_entry_commits_superposition_with_entry_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var legacyBefore = e.journal.records();
            e.requireStarted();
            e.awaitSuperposition("home_entry_commits_superposition_with_entry_b", row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                c.assertTrue(row.entryEndpoint().equals(e.entry) && row.rootAnchor().equals(e.root) && row.chamberUuid().equals(e.chamber),
                        "ENTRY_NOT_B：HOME 列的 Entry 必須是 exact B、Root 是 A：" + row.entryEndpoint() + " / " + row.rootAnchor());
                c.assertTrue(row.home().selection() instanceof Unselected && row.home().closeIntent() instanceof HomeSessionRecord.None,
                        "入場後未選定、沒有結案");
                c.assertTrue(row.participants().size() == 1 && row.participants().getFirst().playerUuid().equals(player.getUuid())
                        && !row.participants().getFirst().returned()
                        && ChamberGeometry.interiorBox(e.frame).contains(row.participants().getFirst().sourcePosition()),
                        "SNAPSHOT_NOT_B：參與者快照取自 B 艙內：" + row.participants());
                c.assertTrue(player.getServerWorld() == e.corridor() && ChamberOccupantService.contains(
                        ChamberGeometry.interiorBox(e.pages.entrance(sid)), player.getBoundingBox()), "參與者在走廊入口 replica 內");
                // T5-1 審查 M-3：以真 Space 驗 overlay 與 HOME 門零交集——overlay 的 50 格都已寫成 AIR，HOME 門仍是完整的 25 格帶 marker 的 bulkhead。
                var markers = e.homeMarkers(row);
                c.assertTrue(markers.size() == 25, "HOME_DOOR_NOT_25：走廊內帶 home_marker 的 bulkhead 必須恰 25 格（overlay 寫完之後）：" + markers.size());
                for (var cell : e.overlayCells(sid)) {
                    c.assertTrue(e.corridor().getBlockState(cell).isAir(), "入口 overlay 已寫成 AIR：" + cell);
                    c.assertTrue(!markers.contains(cell), "OVERLAY_HITS_HOME_DOOR：" + cell);
                }
                // T5-1 審查 M-1：HOME Space 不發布候選、不寫 M4 ledger；正式 bulkhead 互動路徑也不成功、不追加。
                c.assertTrue(e.pages.selectableDoors(sid).isEmpty(), "HOME Space 不發布候選門");
                var rowsBefore = e.journal.allSessions();
                var use = new dev.quantumchamber.candidate.CandidateDoorInteraction().onBulkheadUse(e.corridor(), markers.iterator().next(), player);
                c.assertTrue(use.map(result -> result != ActionResult.SUCCESS).orElse(true), "HOME 門不走候選選擇：" + use);
                c.assertTrue(rowsBefore.equals(e.journal.allSessions()) && legacyBefore.equals(e.journal.records()),
                        "M4_LEDGER_APPENDED：HOME 門與 HOME Space 不得追加任何候選 ledger");
                c.assertTrue(gate().localPresence(e.b, e.frame.controllerPos()) == ChamberSessionGateway.Presence.ACTIVE
                        && gate().localPresence(e.a, e.frame.controllerPos()) == ChamberSessionGateway.Presence.BUSY,
                        "B 顯示 ACTIVE、A 顯示 BUSY");
                c.assertTrue(!ChamberProtectionService.get().mayMutate(e.a, e.frame.controllerPos()), "A 自暫占起受保護");
                // T5-2 審查 I-1：Entry=B 的來源票（spec §7 :203）在 B 的 Controller chunk 恰一張、A（Root）世界沒有。
                e.assertSourceTicketOnB(sid);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "正式取消（返回 B）開始");
                e.awaitReleased(sid, "home_entry_commits_superposition_with_entry_b/cancel", null, () -> {
                    c.assertTrue(e.inB(player), "取消後回到 exact B");
                    e.finish();
                });
            });
        });
    }

    /**
     * 未選定的走廊取消：經 gateway 的 {@code returnToOrigin}（對 B 的 Controller）整組返回 exact B，絕不落在 A；全員返回、走廊清除與 HOME checkpoint 之後才 checked 移除
     * 並解鎖。HOME checkpoint 的相關範圍不含 A（A 的世界沒有任何 checkpoint）。未選定的整組返還不上 B 的 latch（spec v1.6 E）、不改藥效。重啟部分屬 T5-5。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_cancel_windows", tickLimit = 2000)
    public void cancel_or_restart_returns_to_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            e.awaitSuperposition("cancel_or_restart_returns_to_b", row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var overworld = M5GameTestSupport.installChunkCheckpointProbe(c, e.a.getRegistryKey(), 0, ignored -> "", hooks);
                e.assertSourceTicketOnB(sid);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "取消當下尚未返還完成");
                var returning = e.homeRow();
                c.assertTrue(returning != null && returning.state() == SessionState.RETURNING && returning.home().selection() instanceof Unselected,
                        "取消即 checked RETURNING：" + returning);
                // T5-4 Step 0（T5-2 規格 M-2、T5-3 規格 M-2，spec §9.1）負控：同 SID 的 session operation guard 被占的 30 tick 內，返還進度、走廊清除與未選定移除都不寫，
                // 也不移動玩家；guard 釋放之後照常返還。
                var held = e.occupySessionGuard(sid);
                e.hold(30, () -> {
                    if (!returning.equals(e.homeRow()) || !returning.equals(e.journal.allSessions().get(sid)) || player.getServerWorld() != e.corridor())
                        throw new GameTestException("GUARD_HELD_BUT_WRITTEN：同 SID 的 session operation guard 被占時不得寫 journal 或移動玩家：row=" + e.homeRow()
                                + " world=" + player.getServerWorld().getRegistryKey().getValue());
                }, () -> {
                    held.run();
                    boolean[] inA = {false};
                    e.awaitReleased(sid, "cancel_or_restart_returns_to_b/returned", () -> inA[0] |= player.getServerWorld() == e.a, () -> {
                        c.assertTrue(!inA[0] && e.inB(player), "RETURNED_NOT_TO_B：取消必須回 exact B、不得經過 A：world=" + player.getServerWorld().getRegistryKey().getValue());
                        c.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE), "取消不改藥效");
                        c.assertTrue(!e.bController.rearmRequiresPlayerClose(), "未選定的整組返還不上 B 的 latch（spec v1.6 E）");
                        c.assertTrue(overworld.calls().isEmpty(), "HOME checkpoint 的範圍不含 A：" + overworld.calls());
                        c.assertTrue(gate().returnToOrigin(e.b, e.bController), "收尾之後 returnToOrigin 回報完成");
                        e.finish();
                    });
                });
            });
        });
    }

    /** v1.6 A（plan :547）：B 的 latch 為真時，startHome 在任何 claim、ticket、reservation 或 ARMING 之前拒絕，零副作用。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_latch_windows", tickLimit = 400)
    public void start_home_refuses_latched_entry(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.bController.requireRearmPlayerClose();
            var before = e.ownership();
            var pose = e.player(0).getPos();
            var result = gate().startHome(e.b, e.bController, e.ids());
            if (result != ChamberSessionGateway.StartResult.REJECTED) e.cancelOwnStart();
            c.assertTrue(result == ChamberSessionGateway.StartResult.REJECTED, "LATCHED_ENTRY_STARTED：latch 為真時 startHome 必須拒絕：" + result);
            c.assertTrue(before.equals(e.ownership()) && !gate().globalChamberBusy(e.server, e.chamber),
                    "latch 拒絕零副作用（暫占、票、reservation、journal）：" + before + " → " + e.ownership());
            c.assertTrue(e.inB(e.player(0)) && pose.equals(e.player(0).getPos()), "拒絕不移動玩家");
            e.finish();
        });
    }

    /**
     * 最後一位參與者的 checkpoint 失敗（他的恢復 marker 在 ARMING 之後、入場之前被弄壞，checkpoint 一開始就拒絕；前一位的 checkpoint 已成功）：沒有發布 SUPERPOSITION
     * 或 mapping，依 checked ARMING 的進度把已移動的人原生移回 exact B 的原位，再整組返還。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_last_checkpoint_windows", tickLimit = 3000)
    public void last_checkpoint_fails_after_native_moves(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            e.requireStarted();
            M7HomeClosureGameTests.until(c, "last_checkpoint_fails_after_native_moves/arming", 200,
                    () -> e.homeRow() != null && e.homeRow().state() == SessionState.ARMING, e.step(() -> {
                var arming = e.homeRow();
                var sid = arming.sessionUuid();
                var last = e.server.getPlayerManager().getPlayer(arming.participants().getLast().playerUuid());
                c.assertTrue(e.ids().stream().allMatch(id -> e.inB(e.server.getPlayerManager().getPlayer(id))), "ARMING 時全員仍在 B");
                // T5-2 審查 M-5：竄改之後立即登記冪等的還原（只在 marker 仍是竄改值時寫回原值），失敗路徑也會還原。
                var originalMarker = M4CandidateTestAccess.get(last, "quantumchamber$rawRecoveryMarker");
                var corruptMarker = NbtString.of("m7-t5-2-corrupt-marker");
                M4CandidateTestAccess.set(last, "quantumchamber$rawRecoveryMarker", corruptMarker);
                Runnable restoreMarker = () -> {
                    if (corruptMarker.equals(M4CandidateTestAccess.get(last, "quantumchamber$rawRecoveryMarker")))
                        M4CandidateTestAccess.set(last, "quantumchamber$rawRecoveryMarker", originalMarker);
                };
                hooks.add(restoreMarker);
                boolean[] published = {false};
                BooleanSupplier observe = () -> {
                    var current = e.homeRow();
                    published[0] |= current != null && current.state() == SessionState.SUPERPOSITION
                            || e.pages.hasReservation(sid) && e.pages.currentMappings(sid).epoch() != 0;
                    return current != null && current.state() == SessionState.RETURNING;
                };
                M7HomeClosureGameTests.until(c, "last_checkpoint_fails_after_native_moves/returning", 400, observe, e.step(() -> {
                    c.assertTrue(!published[0], "PUBLISHED_AFTER_CHECKPOINT_FAILURE：最後一人 checkpoint 失敗不得發布 SUPERPOSITION 或 mapping");
                    var failures = capture.matching(Level.WARN, "HOME 入場交易保留 RETURNING", sid.toString());
                    c.assertTrue(failures.size() == 1 && failures.getFirst().thrown() != null
                            && String.valueOf(failures.getFirst().thrown().getMessage()).contains("marker"),
                            "入場失敗原因是最後一人的 checkpoint：" + failures.stream().map(entry -> String.valueOf(entry.thrown())).toList());
                    for (var person : arming.participants()) {
                        var player = e.server.getPlayerManager().getPlayer(person.playerUuid());
                        c.assertTrue(e.inB(player) && player.getPos().squaredDistanceTo(person.sourcePosition()) < 1e-10,
                                "ROLLBACK_NOT_B：已移動的人依 ARMING 快照原生移回 exact B 原位：" + player.getPos() + " vs " + person.sourcePosition());
                    }
                    restoreMarker.run();
                    e.awaitReleased(sid, "last_checkpoint_fails_after_native_moves/returned", observe::getAsBoolean, () -> {
                        c.assertTrue(!published[0], "整段都沒有發布 SUPERPOSITION 或 mapping");
                        for (var person : arming.participants()) c.assertTrue(e.inB(e.server.getPlayerManager().getPlayer(person.playerUuid())), "全員在 B");
                        e.finish();
                    });
                }), e.timeout("last_checkpoint_fails_after_native_moves/returning"));
            }), e.timeout("last_checkpoint_fails_after_native_moves/arming"));
        });
    }

    // ---- 未選定返 B ----

    /** 一人喝牛奶（凍結 cohort 有人藥效失效）：整組返回 B，藥效不退款——喝牛奶的人回來仍沒有藥效，另一人保留剩餘時間、不重設為入場快照。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_milk_windows", tickLimit = 2000)
    public void milk_or_buff_lapse_returns_whole_cohort_to_b_without_refund(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            e.requireStarted();
            e.awaitSuperposition("milk_or_buff_lapse_returns_whole_cohort_to_b_without_refund", row -> {
                var sid = row.sessionUuid();
                var milk = e.player(0);
                var other = e.player(1);
                var snapshot = StatusEffectInstance.fromNbt(row.participants().stream().filter(person -> person.playerUuid().equals(other.getUuid()))
                        .findFirst().orElseThrow().quantumStateSnapshot());
                c.assertTrue(snapshot != null && other.hasStatusEffect(ModEffects.QUANTUM_STATE), "前提：入場快照與目前藥效");
                // testmod 的連線玩家不經網路 tick，藥效不會自己倒數：以較短的剩餘時間代表走廊中已經過的時間，才看得出返還是否把它重設成入場快照。
                other.removeStatusEffect(ModEffects.QUANTUM_STATE);
                c.assertTrue(other.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 5000)) && snapshot.getDuration() > 5000,
                        "另一人的剩餘藥效短於入場快照");
                c.assertTrue(milk.clearStatusEffects() && !milk.hasStatusEffect(ModEffects.QUANTUM_STATE), "一人喝牛奶");
                e.awaitReleased(sid, "milk_or_buff_lapse_returns_whole_cohort_to_b_without_refund", null, () -> {
                    c.assertTrue(e.inB(milk) && e.inB(other), "整組返回 exact B");
                    c.assertTrue(!milk.hasStatusEffect(ModEffects.QUANTUM_STATE), "BUFF_REFUNDED：喝牛奶的人回到 B 仍沒有藥效（不退款）");
                    var remaining = other.getStatusEffect(ModEffects.QUANTUM_STATE);
                    c.assertTrue(remaining != null && remaining.getDuration() <= 5000,
                            "BUFF_RESTORED：另一人保留剩餘藥效，不重設為入場快照：" + (remaining == null ? null : remaining.getDuration()) + " / " + snapshot.getDuration());
                    c.assertTrue(capture.matching(Level.INFO, "凍結 cohort 的 QuantumState 已失效", sid.toString()).size() == 1
                            && capture.matching(Level.WARN, "HOME 入場交易保留 RETURNING", sid.toString()).isEmpty(),
                            "藥效失效是規則內的整組返還（INFO、不是故障 WARN）");
                    c.assertTrue(!e.bController.rearmRequiresPlayerClose(), "未選定的整組返還不上 B 的 latch");
                    e.finish();
                });
            });
        });
    }

    /** 走廊中一人斷線：其他人先回 B，離線者的列保留未返還；他重新登入（JOIN）的下一個 tick 才由正式 recovery 送回 exact B，之後才收尾。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_offline_windows", tickLimit = 2000)
    public void offline_participant_returns_to_b_after_join(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            e.awaitSuperposition("offline_participant_returns_to_b_after_join", row -> {
                var sid = row.sessionUuid();
                var online = e.player(0);
                var offline = e.f.players.remove(1);
                hooks.add(offline::close);
                var absent = offline.player().getUuid();
                var profile = offline.player().getGameProfile();
                offline.disconnect();
                c.assertTrue(e.server.getPlayerManager().getPlayer(absent) == null, "原生斷線移除線上玩家");
                M7HomeClosureGameTests.until(c, "offline_participant_returns_to_b_after_join/online", 400, () -> {
                    var current = e.homeRow();
                    return current != null && current.state() == SessionState.RETURNING && current.participants().stream()
                            .anyMatch(person -> person.playerUuid().equals(online.getUuid()) && person.returned());
                }, e.step(() -> {
                    var current = e.homeRow();
                    c.assertTrue(current.participants().stream().anyMatch(person -> person.playerUuid().equals(absent) && !person.returned()),
                            "離線者不可被當作已返還");
                    c.assertTrue(e.inB(online) && SessionRecoveryManager.blocks(absent, e.server) && gate().globalChamberBusy(e.server, e.chamber),
                            "線上者先回 B；離線者仍凍結、艙仍占用");
                    var rejoined = new ConnectedGameTestPlayer(e.a, profile);
                    e.f.players.add(rejoined);
                    int joined = rejoined.joinTick();
                    c.assertTrue(joined == e.server.getTicks() && rejoined.player().getServerWorld() == e.corridor(),
                            "JOIN 只排隊，玩家照 playerdata 先回到走廊");
                    // T5-2 審查 M-4：JOIN 當 tick 的 END tick（recovery）不得返還。下一個 tick 的 test callback 早於該 tick 的 END tick，
                    // 這時重登者必須仍在走廊、列中仍未 Returned；之後才由 recovery 送回 B。
                    e.nextTick(() -> {
                        c.assertTrue(e.server.getTicks() == joined + 1, "前提：觀察點是 JOIN 的下一個 tick（早於該 tick 的 END tick）：" + e.server.getTicks() + " / " + joined);
                        var pending = e.homeRow();
                        c.assertTrue(rejoined.player().getServerWorld() == e.corridor() && pending != null && pending.participants().stream()
                                .anyMatch(person -> person.playerUuid().equals(absent) && !person.returned()),
                                "RETURNED_ON_JOIN_TICK：JOIN 當 tick 的 recovery 不得返還重登者（JOIN 只排到下一個 tick）：world="
                                        + rejoined.player().getServerWorld().getRegistryKey().getValue() + " row=" + pending);
                        e.awaitReleased(sid, "offline_participant_returns_to_b_after_join/returned", null, () -> {
                            c.assertTrue(e.inB(rejoined.player()) && e.inB(online), "重登者在 JOIN 之後回到 exact B");
                            e.finish();
                        });
                    });
                }), e.timeout("offline_participant_returns_to_b_after_join/online"));
            });
        });
    }

    /** RETURNING 期間 exact B 暫時不可用（宇宙 lifecycle 未就緒，B 端點核對為 UNKNOWN）：玩家留在走廊等待，絕不改送 A 或 spawn；B 恢復後才回 B。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_wait_b_windows", tickLimit = 2000)
    public void returning_waits_for_exact_b_without_fallback(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            e.awaitSuperposition("returning_waits_for_exact_b_without_fallback", row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var lifecycle = ((Map<?, ?>) M4CandidateTestAccess.get(UniverseLifecycleService.class, "CONTEXTS")).get(e.server);
                var ready = M4CandidateTestAccess.get(lifecycle, "ready");
                Runnable restore = () -> M4CandidateTestAccess.set(lifecycle, "ready", ready);
                hooks.add(restore);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "取消開始返還");
                M4CandidateTestAccess.set(lifecycle, "ready", false);
                c.assertTrue(HomeEndpointAuthority.inspectEntry(e.server, e.entry) == HomeEndpointAuthority.EntryStatus.UNKNOWN, "前提：B 端點暫時 UNKNOWN");
                boolean[] left = {false};
                e.hold(60, () -> left[0] |= player.getServerWorld() != e.corridor(), () -> {
                    var current = e.homeRow();
                    c.assertTrue(!left[0] && player.getServerWorld() == e.corridor(),
                            "FALLBACK_TARGET：exact B 不可用時玩家留在走廊，不得改送 A 或 spawn：" + player.getServerWorld().getRegistryKey().getValue());
                    c.assertTrue(current != null && current.state() == SessionState.RETURNING && current.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned)
                            && gate().globalChamberBusy(e.server, e.chamber) && SessionRecoveryManager.blocks(player.getUuid(), e.server),
                            "等待期間保留 RETURNING、未返還、凍結與占用：" + current);
                    restore.run();
                    e.awaitReleased(sid, "returning_waits_for_exact_b_without_fallback/returned", null, () -> {
                        c.assertTrue(e.inB(player), "B 恢復之後回到 exact B");
                        e.finish();
                    });
                });
            });
        });
    }

    /** 全員已返回、走廊已清除，但 HOME 原生 checkpoint 失敗：保留 RETURNING 列、走廊 space 與艙的占用，退避後重做；checkpoint 成功之後才 checked 移除。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_checkpoint_windows", tickLimit = 2000)
    public void returning_checkpoint_failure_keeps_row_and_lock(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            e.awaitSuperposition("returning_checkpoint_failure_keeps_row_and_lock", row -> {
                var sid = row.sessionUuid();
                var probe = M5GameTestSupport.installChunkCheckpointProbe(c, e.b.getRegistryKey(), 2, ignored -> "", hooks);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "取消開始返還");
                M7HomeClosureGameTests.until(c, "returning_checkpoint_failure_keeps_row_and_lock/failed", 400,
                        () -> !probe.calls().isEmpty() || e.homeRow() == null, e.step(() -> {
                    var current = e.homeRow();
                    c.assertTrue(current != null, "CHECKPOINT_SKIPPED：HOME 列在 checkpoint 之前就被移除");
                    c.assertTrue(probe.calls().getFirst().failed() && current.state() == SessionState.RETURNING
                            && current.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty(),
                            "checkpoint 失敗時保留全員已返還的 RETURNING 列與租約：" + current);
                    c.assertTrue(gate().globalChamberBusy(e.server, e.chamber) && e.pages.hasReservation(sid) && !ChamberProtectionService.get().mayMutate(e.a, e.frame.controllerPos()),
                            "checkpoint 失敗時保留占用、走廊 space 與 A 保護");
                    e.awaitReleased(sid, "returning_checkpoint_failure_keeps_row_and_lock/removed", null, () -> {
                        var calls = probe.calls();
                        c.assertTrue(calls.size() >= 3 && calls.get(0).failed() && calls.get(1).failed() && !calls.getLast().failed(),
                                "前兩次受控失敗、之後成功才移除：" + calls);
                        var expected = new HashSet<ChunkPos>();
                        var bounds = ChamberGeometry.bounds(e.frame);
                        for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++)
                            for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++) expected.add(new ChunkPos(x, z));
                        c.assertTrue(calls.stream().allMatch(call -> call.chunks().equals(expected)), "HOME checkpoint 在 B 的範圍恰為 B 艙體：" + calls);
                        e.finish();
                    });
                }), e.timeout("returning_checkpoint_failure_keeps_row_and_lock/failed"));
            });
        });
    }

    /**
     * 初始 ARMING 的寫出結果不明（journal 正式檔的 atomic move 失敗，pending 保留這次嘗試）：沒有 checked ARMING，不建幾何、不移動玩家；未確認的嘗試收斂之前，暫占
     * 一直持鎖（不能因 start 失敗就放行另一端）。寫出恢復、pending 收斂之後才解除占用與 reservation。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_unchecked_windows", tickLimit = 2000)
    public void unchecked_home_arming_keeps_chamber_busy_until_converged(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var player = e.player(0);
            var pose = player.getPos();
            M7NativeSaveFault.Handle fault;
            try { fault = M7NativeSaveFault.arm(e.server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat")); }
            catch (java.io.IOException failure) { throw new GameTestException("M7NativeSaveFault 武裝失敗：" + failure); }
            hooks.add(fault::close);
            e.requireStarted();
            var sid = provisionalSid(e.chamber);
            c.assertTrue(sid != null, "受理後有暫占");
            M7HomeClosureGameTests.until(c, "unchecked_home_arming_keeps_chamber_busy_until_converged/attempted", 200, () -> fault.hits() >= 1, e.step(() -> {
                c.assertTrue(!e.journal.allSessions().containsKey(sid) && !e.journal.allFlushedSessions().containsKey(sid)
                        && e.journal.pendingSessions().containsKey(sid), "ARMING 寫出結果不明：沒有 checked 列、pending 保留這次嘗試");
                e.hold(40, () -> {
                    if (!gate().globalChamberBusy(e.server, e.chamber)) throw new GameTestException("UNCHECKED_ARMING_RELEASED：未確認的 ARMING 收斂之前不得解除占用");
                }, () -> {
                    c.assertTrue(!gate().claimProvisional(e.server, e.chamber, UUID.randomUUID()), "另一端不能在收斂前暫占");
                    c.assertTrue(e.inB(player) && pose.equals(player.getPos()) && e.pages.currentMappings(sid).epoch() == 0, "沒有移動玩家、沒有發布 mapping");
                    fault.disarm();
                    M7HomeClosureGameTests.until(c, "unchecked_home_arming_keeps_chamber_busy_until_converged/converged", 600,
                            () -> !gate().globalChamberBusy(e.server, e.chamber) && !e.pages.hasReservation(sid) && e.journal.pendingSessions().isEmpty(),
                            e.step(() -> {
                                c.assertTrue(!e.journal.allSessions().containsKey(sid) && !e.journal.allFlushedSessions().containsKey(sid), "收斂後沒有殘留列");
                                c.assertTrue(e.inB(player) && pose.equals(player.getPos()), "全程未移動玩家");
                                // T5-2 品質 fix1 複審的可選建議（T5-3 Step 0）：未確認的 ARMING 不得取 Entry=B 來源票（取票只在 checked ARMING 之後）。
                                e.assertNoSourceTicket(sid);
                                e.finish();
                            }), e.timeout("unchecked_home_arming_keeps_chamber_busy_until_converged/converged"));
                });
            }), e.timeout("unchecked_home_arming_keeps_chamber_busy_until_converged/attempted"));
        });
    }

    /** A 的 START_CHECK 票與暫占：{@code /reload} 立即釋放；A 200 tick 內都沒有 FULL 時，在第 200 tick 釋放、拒絕啟動（不寫 ARMING、不動玩家）。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_start_check_windows", tickLimit = 2000)
    public void start_check_released_on_reload_and_timeout(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var player = e.player(0);
            var pose = player.getPos();
            e.requireStarted();
            var reloaded = e.delayRootTicket();
            c.assertTrue(HomeTicketFaultFixture.count(e.a, reloaded) > 0 && gate().globalChamberBusy(e.server, e.chamber), "START_CHECK 持有 A 的票與暫占");
            e.nextTick(() -> {
                c.assertTrue(reloaded.equals(provisionalSid(e.chamber)) && e.homeRow() == null, "A 未 FULL 前只等待，不寫 ARMING");
                if (!(e.server.getResourceManager() instanceof net.minecraft.resource.LifecycledResourceManager resources))
                    throw new GameTestException("RELOAD_FIXTURE：resource manager 不是 LifecycledResourceManager");
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.START_DATA_PACK_RELOAD.invoker().startDataPackReload(e.server, resources);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().endDataPackReload(e.server, resources, true);
                c.assertTrue(provisionalSid(e.chamber) == null && HomeTicketFaultFixture.count(e.a, reloaded) == 0 && !gate().globalChamberBusy(e.server, e.chamber),
                        "RELOAD_KEPT_START_CHECK：/reload 必須釋放 START_CHECK 票與暫占");
                e.requireStarted();
                var timed = e.delayRootTicket();
                int claimed = e.server.getTicks();
                boolean[] armed = {false};
                M7HomeClosureGameTests.until(c, "start_check_released_on_reload_and_timeout/timeout", 260, () -> {
                    armed[0] |= e.homeRow() != null;
                    return !gate().globalChamberBusy(e.server, e.chamber);
                }, e.step(() -> {
                    int elapsed = e.server.getTicks() - claimed;
                    c.assertTrue(elapsed >= 199 && elapsed <= 202, "START_CHECK 逾時恰在 200 tick 釋放：" + elapsed);
                    c.assertTrue(!armed[0] && HomeTicketFaultFixture.count(e.a, timed) == 0 && provisionalSid(e.chamber) == null,
                            "逾時拒絕：沒有 ARMING、票已釋放");
                    c.assertTrue(e.inB(player) && pose.equals(player.getPos()), "逾時與 reload 都不移動玩家");
                    e.finish();
                }), e.timeout("start_check_released_on_reload_and_timeout/timeout"));
            });
        });
    }

    /**
     * 平台不支援原生玩家 checkpoint（Windows 上以 {@link CheckpointPlatformFault} 模擬，使用者 2026-11-24 Windows 優先）：startHome 在任何 claim、ticket、reservation 或
     * ARMING 之前受控拒絕（v1.6 A、plan :317 的順序 latch → unsupportedReason → claim）。暫占會撤銷 A 的斷電協調結果，所以 A 的保護狀態不變就證明沒有 claim。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_platform_windows", tickLimit = 400)
    public void start_home_checkpoint_unavailable_has_no_side_effects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, false);
        e.guard(() -> {
            new ChamberPowerCoordinator().refresh(e.a, e.frame.controllerPos());
            c.assertTrue(ChamberProtectionService.get().mayMutate(e.a, e.frame.controllerPos()), "前提：A 斷電、已協調為 OFF（保護解除）");
            // checkpoint 能力 WARN 的去重是 gateway 的全域狀態：先清成沒有上一則（WARN 一定記），收尾時還原原值。
            var previousWarning = M4CandidateTestAccess.get(gate(), "checkpointWarning");
            hooks.add(() -> M4CandidateTestAccess.set(gate(), "checkpointWarning", previousWarning));
            M4CandidateTestAccess.set(gate(), "checkpointWarning", null);
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            var before = e.ownership();
            var journalBefore = List.of(e.journal.allSessions(), e.journal.allFlushedSessions(), e.journal.pendingSessions());
            var pose = e.player(0).getPos();
            ChamberSessionGateway.StartResult result;
            int consulted;
            try (var fault = CheckpointPlatformFault.simulateUnsupported(e.server)) {
                result = gate().startHome(e.b, e.bController, e.ids());
                consulted = fault.consulted();
            }
            if (result != ChamberSessionGateway.StartResult.REJECTED) e.cancelOwnStart();
            c.assertTrue(result == ChamberSessionGateway.StartResult.REJECTED && consulted > 0, "平台拒絕：result=" + result + " consulted=" + consulted);
            c.assertTrue(ChamberProtectionService.get().mayMutate(e.a, e.frame.controllerPos()),
                    "CLAIMED_BEFORE_PLATFORM_CHECK：平台預檢必須在暫占之前（暫占會撤銷 A 的斷電協調結果）");
            c.assertTrue(before.equals(e.ownership()) && journalBefore.equals(List.of(e.journal.allSessions(), e.journal.allFlushedSessions(), e.journal.pendingSessions())),
                    "平台拒絕零副作用（暫占、票、reservation、journal）");
            c.assertTrue(capture.matching(Level.WARN, "玩家原生 checkpoint 能力預檢未通過").size() == 1, "平台拒絕記一則 WARN");
            c.assertTrue(e.inB(e.player(0)) && pose.equals(e.player(0).getPos()), "拒絕不移動玩家");
            e.finish();
        });
    }

    // ---- T5-3 Step 0：T5-2 品質審查分給 T5-3 Step 0 的 Minor ----

    /**
     * 品質 M-1：checked ARMING 之後的清理丟出例外（A 的 START_CHECK 票第一次原生移除失敗）不得留下沒有 runtime 的 ARMING 列：入場照常到 SUPERPOSITION，
     * START_CHECK 票由原艙票 owner 之後續清；最後以正式取消返回 B 收尾。修正前：例外回到 {@code continueHomeStarts} 的 catch 只取消暫占，ARMING 列沒有 runtime、
     * 沒有人收斂，艙一直 BUSY（{@code HOME_START_LEAKED_AFTER_CHECKED_ARMING}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_start_fault_windows", tickLimit = 2000)
    public void home_start_cleanup_fault_after_checked_arming_keeps_runtime(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            e.requireStarted();
            var sid = provisionalSid(e.chamber);
            c.assertTrue(sid != null, "受理後有暫占");
            var faults = e.failFirstRootTicketRemoval(sid);
            var label = "home_start_cleanup_fault_after_checked_arming_keeps_runtime";
            e.awaitSuperposition(label, row -> {
                c.assertTrue(row.sessionUuid().equals(sid) && faults[0] == 1, "前提：同一個 SID、START_CHECK 票的第一次移除丟出例外：faults=" + faults[0]);
                c.assertTrue("HomeRuntime".equals(ownerType(e.chamber)), "checked ARMING 之後由 HOME runtime 持有這座艙：" + ownerType(e.chamber));
                c.assertTrue(HomeTicketFaultFixture.count(e.a, sid) == 0, "START_CHECK 票由原艙票 owner 之後續清：" + HomeTicketFaultFixture.count(e.a, sid));
                c.assertTrue(capture.matching(Level.WARN, "HOME 入場已 checked ARMING，但暫占與 START_CHECK 票的釋放丟出例外", sid.toString()).size() == 1,
                        "清理例外記一則 WARN");
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "正式取消（返回 B）開始");
                e.awaitReleased(sid, label + "/cancel", null, () -> {
                    c.assertTrue(e.inB(e.player(0)), "取消後回到 exact B");
                    e.finish();
                });
            }, e.step(() -> {
                throw new GameTestException("HOME_START_LEAKED_AFTER_CHECKED_ARMING：checked ARMING 之後的清理例外不得留下沒有 runtime、沒有收斂者的列：row="
                        + e.homeRow() + " owner=" + ownerType(e.chamber) + " faults=" + faults[0]);
            }));
        });
    }

    /**
     * T5-4 Step 0（T5-3 品質 M-6）：初始 ARMING 的寫出結果不明（{@link M7NativeSaveFault}），而未確認路徑的 {@code cancelProvisional}（釋放 START_CHECK 票）第一次丟出例外：
     * {@code settleFailedHomeStart} 的第二分支（還有走廊 reservation）保留暫占與 reservation，交 {@code finishHomeAbandoned}；收斂之前一直持鎖、不移動玩家，寫出恢復、
     * 未確認的嘗試收斂之後才解除。T5-3 Step 0 已修，所以沒有 RED；以 mutation「移除 homeAbandons.put」驗證（收斂者消失，暫占與 reservation 永久留下）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_unchecked_cleanup_fault_windows", tickLimit = 2000)
    public void unchecked_home_arming_cleanup_fault_keeps_lock_until_converged(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            var label = "unchecked_home_arming_cleanup_fault_keeps_lock_until_converged";
            var player = e.player(0);
            var pose = player.getPos();
            M7NativeSaveFault.Handle fault;
            try { fault = M7NativeSaveFault.arm(e.server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat")); }
            catch (java.io.IOException failure) { throw new GameTestException("M7NativeSaveFault 武裝失敗：" + failure); }
            hooks.add(fault::close);
            e.requireStarted();
            var sid = provisionalSid(e.chamber);
            c.assertTrue(sid != null, "受理後有暫占");
            var faults = e.failFirstRootTicketRemoval(sid);
            M7HomeClosureGameTests.until(c, label + "/attempted", 200, () -> fault.hits() >= 1 && faults[0] == 1, e.step(() -> {
                c.assertTrue(!e.journal.allSessions().containsKey(sid) && !e.journal.allFlushedSessions().containsKey(sid)
                        && e.journal.pendingSessions().containsKey(sid), "前提：ARMING 寫出結果不明（沒有 checked 列、pending 保留這次嘗試）");
                var warned = capture.matching(Level.WARN, "HOME 入場在走廊 reservation 之後丟出例外", sid.toString());
                c.assertTrue(warned.size() == 1, "前提：未確認路徑的暫占取消丟出例外，交 settleFailedHomeStart 的第二分支（記一則 WARN）：" + warned.size());
                e.hold(40, () -> {
                    if (!gate().globalChamberBusy(e.server, e.chamber) || !e.pages.hasReservation(sid))
                        throw new GameTestException("UNCHECKED_CLEANUP_FAULT_RELEASED：未確認的 ARMING 收斂之前不得解除占用或 reservation");
                }, () -> {
                    c.assertTrue(e.inB(player) && pose.equals(player.getPos()) && e.pages.currentMappings(sid).epoch() == 0, "沒有移動玩家、沒有發布 mapping");
                    fault.disarm();
                    M7HomeClosureGameTests.until(c, label + "/converged", 600,
                            () -> !gate().globalChamberBusy(e.server, e.chamber) && !e.pages.hasReservation(sid) && e.journal.pendingSessions().isEmpty(),
                            e.step(() -> {
                                c.assertTrue(!e.journal.allSessions().containsKey(sid) && !e.journal.allFlushedSessions().containsKey(sid), "收斂後沒有殘留列");
                                c.assertTrue(e.inB(player) && pose.equals(player.getPos()), "全程未移動玩家");
                                c.assertTrue(HomeTicketFaultFixture.count(e.a, sid) == 0, "START_CHECK 票已釋放");
                                e.assertNoSourceTicket(sid);
                                e.finish();
                            }), e.step(() -> {
                                throw new GameTestException("UNCHECKED_CLEANUP_FAULT_NOT_CONVERGED：寫出恢復之後未確認的 ARMING 沒有收斂（暫占或 reservation 沒有收斂者）：busy="
                                        + gate().globalChamberBusy(e.server, e.chamber) + " reservation=" + e.pages.hasReservation(sid) + " pending=" + e.journal.pendingSessions().keySet()
                                        + " owner=" + ownerType(e.chamber));
                            }));
                });
            }), e.timeout(label + "/attempted"));
        });
    }

    /**
     * 品質 M-2：全員已返回 B、走廊收尾開始之後 B 暫時不是 exact live（宇宙 lifecycle 未就緒）：收尾托管等待（spec §9.2），保留 RETURNING 列、走廊與占用，
     * 不得每個 tick 記一則 ERROR＋stack；B 恢復之後照常收尾。修正前：{@code MeasuredWorldSaveCheckpoint.ready} 每 tick 丟出、{@code failHome} 每 tick 記 ERROR
     * （{@code HOME_RELEASE_ERROR_FLOOD}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_release_wait_b_windows", tickLimit = 2000)
    public void home_release_waits_for_exact_b_without_error_flood(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "home_release_waits_for_exact_b_without_error_flood";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var capture = new LogCapture("quantumchamber");
                hooks.add(capture::close);
                var lifecycle = ((Map<?, ?>) M4CandidateTestAccess.get(UniverseLifecycleService.class, "CONTEXTS")).get(e.server);
                var ready = M4CandidateTestAccess.get(lifecycle, "ready");
                Runnable restore = () -> M4CandidateTestAccess.set(lifecycle, "ready", ready);
                hooks.add(restore);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "取消開始返還");
                M7HomeClosureGameTests.until(c, label + "/returned", 400, () -> {
                    var current = e.homeRow();
                    return current != null && current.state() == SessionState.RETURNING
                            && current.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
                }, e.step(() -> {
                    // 全員已返回的同一個 recovery tick 已交走廊收尾（releaseHome）；走廊的收尾在下一個 END tick 才執行，這裡（test 回呼）早於它。
                    c.assertTrue(e.inB(player) && e.pages.hasReservation(sid), "前提：全員已返回 B、走廊尚未收尾");
                    M4CandidateTestAccess.set(lifecycle, "ready", false);
                    c.assertTrue(HomeEndpointAuthority.inspectEntry(e.server, e.entry) == HomeEndpointAuthority.EntryStatus.UNKNOWN, "前提：B 暫時不是 exact live");
                    e.hold(30, () -> { }, () -> {
                        var errors = capture.matching(Level.ERROR, "HOME 走廊保留租約等待安全返回 B", sid.toString());
                        c.assertTrue(errors.isEmpty(), "HOME_RELEASE_ERROR_FLOOD：B 暫時不是 exact live 時走廊收尾不得每 tick 記 ERROR：count=" + errors.size());
                        var current = e.homeRow();
                        c.assertTrue(current != null && current.state() == SessionState.RETURNING && !current.spaceLeases().isEmpty()
                                        && e.pages.hasReservation(sid) && gate().globalChamberBusy(e.server, e.chamber),
                                "托管等待：保留 RETURNING 列、租約、走廊與占用：" + current);
                        restore.run();
                        e.awaitReleased(sid, label + "/released", null, () -> {
                            c.assertTrue(e.inB(player), "B 恢復之後照常收尾、玩家在 exact B");
                            e.finish();
                        });
                    });
                }), e.timeout(label + "/returned"));
            });
        });
    }

    /**
     * 品質 M-8：返還進度（Returned）的 roll-forward 寫出未確認（ROLL_FORWARD_PENDING）時記一次 journal 退避，同一個 tick 不再重寫；寫出恢復之後由 journal 退避的重試確認、
     * 照常收尾。修正前：recovery 沒有記退避，同一個 tick 的 {@code retryHomeRollForward} 立刻再寫一次（{@code HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_roll_forward_backoff_windows", tickLimit = 2000)
    public void returning_roll_forward_pending_waits_for_journal_backoff(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "returning_roll_forward_pending_waits_for_journal_backoff";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "取消開始返還");
                var returning = e.homeRow();
                c.assertTrue(returning != null && returning.state() == SessionState.RETURNING
                        && returning.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned), "前提：checked RETURNING、尚未有人返還");
                M7NativeSaveFault.Handle fault;
                try { fault = M7NativeSaveFault.arm(e.server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat")); }
                catch (java.io.IOException failure) { throw new GameTestException("M7NativeSaveFault 武裝失敗：" + failure); }
                hooks.add(fault::close);
                // 失敗路徑的收尾：解除故障之後把未確認的 roll-forward 寫出確認，避免共用 journal 的 pending 讓之後的 HOME 入場被拒（T5-3 RED 實測的連坐）。
                hooks.add(() -> {
                    fault.disarm();
                    try { if (e.journal.rollForwardPending(sid)) e.journal.flush(e.server); }
                    catch (RuntimeException flush) { LOG.warn("M7_GT_ROLL_FORWARD_CLEANUP 寫出失敗：session={}", sid, flush); }
                });
                M7HomeClosureGameTests.until(c, label + "/write", 200, () -> fault.hits() >= 1, e.step(() -> {
                    c.assertTrue(fault.hits() == 1,
                            "HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK：返還進度寫出未確認的同一個 tick 不得再重寫（等 journal 退避）：hits=" + fault.hits());
                    var current = e.journal.allSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
                    var flushed = e.homeRow();
                    c.assertTrue(e.journal.rollForwardPending(sid) && current != null && current.participants().getFirst().returned()
                                    && flushed != null && !flushed.participants().getFirst().returned(),
                            "前提：返還進度的 roll-forward 寫出未確認（current 已返還、flushed 未返還）：current=" + current + " flushed=" + flushed);
                    fault.disarm();
                    e.awaitReleased(sid, label + "/released", null, () -> {
                        c.assertTrue(e.inB(player), "寫出恢復之後照常收尾、玩家在 exact B");
                        e.finish();
                    });
                }), e.timeout(label + "/write"));
            });
        });
    }

    /**
     * 品質 M-10：HOME 的 tick 段丟出意外例外時只記一則 WARN（同段同原因去重），不從 END_SERVER_TICK 丟出；本 tick 其餘的 legacy、reposition 與 recovery 照常。
     * 以 testmod 反射放入一個不完整的 HOME runtime（沒有 B 世界與 Controller）：{@code tickHomeRuntimes} 的失敗路徑本身丟出 NPE。修正前這個例外會從
     * END_SERVER_TICK 丟出，原版 server 迴圈把它當成未預期例外而停服，所以這案沒有 RED 執行（以讀碼論證，見 T5-3 報告）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_tick_isolation_windows", tickLimit = 400)
    public void home_tick_exception_is_isolated_and_warned_once(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        try {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            var chamber = UUID.randomUUID();
            var bogus = incompleteHomeRuntime(chamber);
            @SuppressWarnings("unchecked") var sessions = (Map<Object, Object>) M4CandidateTestAccess.get(gate(), "sessions");
            // T5-4 Step 0（T5-3 品質 M-5）：觀察「本 tick 其餘段照常」——在同一個 callback 放入壞 runtime 與一筆本 tick 就到期的 JOIN 排隊（recovery 的 joinTicks，
            // 只有同一個 END tick 的 recovery 執行了才會清掉）。壞 runtime 在這個 END tick 丟出；下一個 tick 看到排隊已清，表示 HOME 段之後的 recovery 在同一個 tick 照常執行。
            @SuppressWarnings("unchecked") var joinTicks = (Map<UUID, Integer>) M4CandidateTestAccess.get(M4CandidateTestAccess.get(gate(), "recovery"), "joinTicks");
            var probe = UUID.randomUUID();
            int start = c.getWorld().getServer().getTicks();
            sessions.put(chamber, bogus);
            hooks.add(() -> sessions.remove(chamber, bogus));
            joinTicks.put(probe, start);
            hooks.add(() -> joinTicks.remove(probe));
            c.waitAndRun(1, () -> {
                try {
                    var warned = capture.matching(Level.WARN, "HOME 的 tick 段（tickHomeRuntimes）丟出意外例外");
                    c.assertTrue(warned.size() == 1 && !joinTicks.containsKey(probe),
                            "HOME_TICK_REST_SKIPPED：HOME 段丟出意外例外的同一個 tick，其後的 recovery 必須照常執行：warned=" + warned.size() + " joinQueued=" + joinTicks.containsKey(probe));
                    c.assertTrue(warned.size() == 1 && warned.getFirst().thrown() instanceof NullPointerException,
                            "HOME_TICK_EXCEPTION_NOT_ISOLATED：HOME 段的意外例外只記一則 WARN（去重）：" + warned.stream().map(entry -> String.valueOf(entry.thrown())).toList());
                    hooks.release();
                    c.complete();
                } catch (RuntimeException | Error failure) {
                    hooks.releaseAfter(failure);
                    if (failure instanceof GameTestException test) throw test;
                    var wrapped = new GameTestException("M7_HOME_CORRIDOR_CALLBACK：回呼內例外：" + failure);
                    wrapped.initCause(failure);
                    throw wrapped;
                }
            });
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * 不完整的 HOME runtime（只供 M-10 案）：B 世界、Controller、效果交易與 reservation 都是 null，凍結列是合法但不在 journal 的 ARMING 列（新 SID、新 chamber、
     * 遠處的租約）。{@code tickHomeRuntimes} 的資格檢查丟出 NPE，失敗路徑 {@code failHome} 先把狀態設為 RETURNING、再於 B 權威核對丟出 NPE——只丟一次。
     */
    private static Object incompleteHomeRuntime(UUID chamber) {
        var pos = new BlockPos(900003, 64, 900003);
        var universe = UUID.randomUUID();
        var root = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, pos, net.minecraft.util.math.Direction.NORTH,
                ChamberInstanceKind.ORIGIN);
        var entry = new EntryEndpoint(chamber, UniverseId.of(universe), net.minecraft.util.Identifier.of("quantumchamber", "universe/" + universe + "/overworld"),
                DimensionRole.OVERWORLD, pos, net.minecraft.util.math.Direction.NORTH, ChamberInstanceKind.PROJECTION);
        var person = new SessionRecoveryRecord.Participant(UUID.randomUUID(), Vec3d.ZERO, Vec3d.ZERO, 0, 0, new net.minecraft.nbt.NbtCompound(), false);
        var lease = new SessionRecoveryRecord.SpaceLease(62, new net.minecraft.util.math.BlockBox(900000, 64, 900000, 900006, 70, 900006));
        var home = new HomeSessionRecord.Home(new Unselected(), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new HomeSessionRecord.None(), Optional.empty());
        var row = new HomeSessionRecord(UUID.randomUUID(), chamber, root, entry, List.of(person), List.of(lease), SessionState.ARMING, home);
        try {
            var type = Class.forName("dev.quantumchamber.superposition.SuperpositionSessionManager$HomeRuntime");
            var constructor = type.getDeclaredConstructor(ServerWorld.class, ChamberControllerBlockEntity.class, ChamberFrame.class,
                    dev.quantumchamber.transfer.QuantumEffectTransaction.class, CorridorPageManager.PreparedMappings.class, HomeSessionRecord.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, null, new ChamberFrame(pos, net.minecraft.util.math.Direction.NORTH), null, null, row);
        } catch (ReflectiveOperationException failure) { throw new GameTestException("HOME runtime 反射建立失敗：" + failure); }
    }

    // ---- T5-3：HOME 換頁與容量 pending ----

    /**
     * 正式 reposition 為 HOME 走廊換頁（plan :319「remap／頁面伸展同一 HOME mode」、:325「換頁無第二扇」）：玩家走到 page 1，正式 tick 準備新實例、搬移、提交並退休舊實例。
     * 新實例以同一個 HOME mode 建造：帶 home_marker 的 bulkhead 恰為一扇 25 格 HOME 門，其餘 bulkhead 只有入口 replica 的正門（沒有 legacy 每站兩側的一般側門）；舊實例清空，
     * 沒有第二扇。列仍是原 SID、未選定的 SUPERPOSITION，租約只剩新實例。最後以正式取消返回 B 收尾。RED：正式 reposition 只看 legacy 列，HOME 不換頁
     * （{@code HOME_REMAP_NOT_ATTEMPTED}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_remap_door_windows", tickLimit = 3000)
    public void home_remap_builds_no_second_home_door(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "home_remap_builds_no_second_home_door";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var before = e.pages.currentMappings(sid);
                c.assertTrue(before.epoch() == 1 && before.instances().size() == 1 && row.spaceLeases().size() == 1, "前提：入場後一個實例、一筆租約：" + before);
                var oldView = before.instances().getFirst();
                e.assertSingleHomeDoor(sid, oldView, "前提（換頁前）");
                var oldSlots = Set.of(oldView.ref().slotId());
                // T5-4 Step 0（T5-2 規格 M-2、T5-3 規格 M-2，spec §9.1）：換頁聯集、退休清租約、RETURNING、返還進度與未選定移除的 journal 寫出都在這個 SID 的 guard 內。
                var guardWrites = e.guardWitness(sid);
                e.walkHome(sid, player, 96.5);
                // T5-3 fix1：正式 tick 寫出新舊租約聯集之後，先同步生成新租約周圍的走廊 chunk（只改 chunk 載入），換頁仍由正式 reposition tick 完成。
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, oldSlots), e.step(() -> {
                    e.pregenerateNewLeases(sid, oldSlots, label);
                    M7HomeClosureGameTests.until(c, label + "/remapped", 600, () -> e.remappedOnce(sid, oldView), e.step(() -> {
                        var view = e.pages.currentMappings(sid).instances().getFirst();
                        var after = e.homeRow();
                        c.assertTrue(after.sessionUuid().equals(sid) && after.state() == SessionState.SUPERPOSITION && after.home().equals(row.home())
                                && after.spaceLeases().size() == 1 && after.spaceLeases().getFirst().slotId() == view.ref().slotId(),
                                "換頁後原 SID、未變的 Selection、租約只剩新實例：" + after);
                        c.assertTrue(player.getServerWorld() == e.corridor() && Math.abs(e.logicalZ(sid, player) - 96.5) < 1e-6, "玩家以同一個邏輯座標搬到新實例");
                        e.assertSingleHomeDoor(sid, view, "SECOND_HOME_DOOR（換頁後）");
                        c.assertTrue(e.bulkheads(oldView.bounds()).isEmpty() && e.allAir(oldView.bounds()), "OLD_HOME_DOOR_LEFT：舊實例已清空，沒有第二扇 HOME 門");
                        c.assertTrue(e.pages.selectableDoors(sid).isEmpty(), "HOME 換頁後仍不發布候選門");
                        c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "正式取消（返回 B）開始");
                        e.awaitReleased(sid, label + "/cancel", null, () -> {
                            c.assertTrue(e.inB(player), "取消後回到 exact B");
                            var outside = guardWrites.stream().filter(write -> !write.endsWith(" guard=true")).toList();
                            c.assertTrue(outside.isEmpty() && guardWrites.stream().filter(write -> write.startsWith("leases:")).count() >= 2
                                    && guardWrites.stream().anyMatch(write -> write.startsWith("state:RETURNING"))
                                    && guardWrites.stream().anyMatch(write -> write.startsWith("returned"))
                                    && guardWrites.stream().anyMatch(write -> write.startsWith("remove")),
                                    "HOME_WRITE_OUTSIDE_GUARD：換頁聯集、退休清租約、RETURNING、返還進度與未選定移除的 journal 寫出都必須在 session operation guard 內："
                                            + "outside=" + outside + " all=" + guardWrites);
                            e.finish();
                        });
                    }), e.step(() -> {
                        throw new GameTestException("HOME_REMAP_NOT_COMPLETED：新舊租約聯集已 checked，但換頁沒有在 600 tick 內完成：epoch=" + e.pages.currentMappings(sid).epoch()
                                + " " + e.spaceDiagnostics(sid) + " row=" + e.homeRow());
                    }));
                }), e.step(() -> {
                    throw new GameTestException("HOME_REMAP_NOT_ATTEMPTED：正式 tick 沒有為 HOME 走廊換頁（新舊租約聯集沒有寫出）：epoch=" + e.pages.currentMappings(sid).epoch()
                            + " row=" + e.homeRow());
                }));
            });
        });
    }

    /**
     * 接近 16 MiB 的混合 journal，由正式 {@code SuperpositionSessionManager.tick → CorridorRepositionService.tick} 驅動 HOME 換頁（plan :325）：容量拒絕是可重試的 pending——
     * 不送 RETURNING、撤回本次多保留的 slot，保留原 mapping、原 SID、未變的 Selection 與 CLOSE 空間；容量恢復之後重試並完成。合法 journal 下未選定 HOME 列的換頁已含在
     * admission 的預留內（見 {@link M7CapacityFixture#inflateInMemory}），所以「共享預留不符」以 testmod 反射在記憶體暫時加大壓力列來模擬（磁碟上的 journal 全程合法）；
     * 還原之後的換頁是在恰好 16 MiB 的合法壓力下完成。RED：正式 reposition 只看 legacy 列，HOME 不換頁（{@code HOME_REMAP_NOT_ATTEMPTED}）。
     * <p>T5-4 Step 0（T5-3 品質 M-2）：玩家走回原頁結束一段 pending、再走出去是新的一段（第二則 INFO，{@code HOME_CAPACITY_INFO_NOT_RESET}）；容量恢復之後的重試要等
     * 這一段的退避，不每 tick 整份量測 journal（{@code HOME_CAPACITY_RETRY_NOT_BACKED_OFF}）。</p>
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_remap_capacity_windows", tickLimit = 3000)
    public void formal_tick_capacity_pending_preserves_home_mapping(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "formal_tick_capacity_pending_preserves_home_mapping";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var mapping = e.pages.currentMappings(sid);
                var oldView = mapping.instances().getFirst();
                var capture = new LogCapture("quantumchamber");
                hooks.add(capture::close);
                // 合法的壓力列：padding 同時扣掉壓力列與這一列 HOME 的完成預留，整份 journal 恰在 16 MiB 預留不變量的邊界上。
                // 失敗路徑的收尾排在 HomeEntry.close 最前面（先還原膨脹、再釋出壓力列），早於任何返還請求的 journal 寫出。
                var pressure = M7CapacityFixture.admitBeside(e.server, row);
                e.beforeClose(() -> M7CapacityFixture.releaseIfPresent(e.server, pressure));
                var inflation = M7CapacityFixture.inflateInMemory(e.server, pressure, 4096);
                boolean[] restored = {false};
                Runnable restore = () -> { if (!restored[0]) { restored[0] = true; inflation.run(); } };
                e.beforeClose(restore);
                var rowSlots = slots(row);
                Runnable unchanged = () -> {
                    var current = e.homeRow();
                    if (!row.equals(current) || !row.equals(e.journal.allSessions().get(sid)))
                        throw new GameTestException("CAPACITY_SENT_RETURNING：容量不足時 HOME 列必須保持原 SUPERPOSITION、原 SID、未選定與原租約：" + current);
                    var spaceSlots = e.spaceSlots(sid);
                    if (!rowSlots.equals(spaceSlots))
                        throw new GameTestException("REMAP_RESERVE_NOT_WITHDRAWN：容量拒絕必須撤回多保留的 slot：space=" + spaceSlots + " row=" + rowSlots);
                    if (!mapping.equals(e.pages.currentMappings(sid)) || player.getServerWorld() != e.corridor())
                        throw new GameTestException("容量不足時保留原 mapping、玩家留在走廊：" + e.pages.currentMappings(sid));
                };
                e.walkHome(sid, player, 96.5);
                e.hold(20, unchanged, () -> {
                    var attempts = capture.matching(Level.INFO, "HOME 換頁的 journal 預留不足", sid.toString());
                    c.assertTrue(!attempts.isEmpty(), "HOME_REMAP_NOT_ATTEMPTED：容量不足期間正式 tick 沒有嘗試 HOME 換頁（沒有容量 pending 的紀錄）");
                    c.assertTrue(attempts.size() == 1, "同一段容量 pending 只記一則 INFO：" + attempts.size());
                    // T5-3 品質 M-4：原本的「CLOSE 空間不變」以單列公式比較、在列相等之後恆真，已刪除；膨脹窗口內共享預留是刻意不符，改在窗口外以整份 journal 量測。
                    c.assertTrue(e.pages.hasReservation(sid) && gate().globalChamberBusy(e.server, e.chamber), "走廊 reservation 與占用都不變");
                    // T5-4 Step 0（T5-3 品質 M-2）：玩家走回原頁結束這段 pending，再走出去是新的一段：記第二則 INFO，第一次嘗試不受上一段的退避限制。
                    e.walkHome(sid, player, 48.5);
                    e.hold(1, unchanged, () -> {
                        e.walkHome(sid, player, 96.5);
                        e.hold(1, unchanged, () -> {
                            var problems = new ArrayList<String>();
                            int segments = capture.matching(Level.INFO, "HOME 換頁的 journal 預留不足", sid.toString()).size();
                            if (segments != 2) problems.add("HOME_CAPACITY_INFO_NOT_RESET：玩家走回原頁結束一段容量 pending 之後，新的一段要再記一則 INFO：count=" + segments);
                            // 容量恢復之後，下一次重試要等這一段的退避（第一次 20 tick），不每 tick 整份量測 journal；這段期間列、slot 與 mapping 都不變。
                            restore.run();
                            int restoredAt = (int) c.getTick();
                            e.hold(10, () -> {
                                if (e.newLeaseWritten(sid, rowSlots) && problems.stream().noneMatch(problem -> problem.startsWith("HOME_CAPACITY_RETRY_NOT_BACKED_OFF")))
                                    problems.add("HOME_CAPACITY_RETRY_NOT_BACKED_OFF：容量 pending 的重試不得每 tick 進行（恢復後 " + ((int) c.getTick() - restoredAt) + " tick 內就重試）");
                            }, () -> {
                                c.assertTrue(problems.isEmpty(), String.join("；", problems));
                                capacityRetried(c, e, label, sid, row, rowSlots, oldView, player, pressure);
                            });
                        });
                    });
                });
            });
        });
    }

    /** {@code formal_tick_capacity_pending_preserves_home_mapping} 的後半：容量恢復之後正式 tick 重試、換頁完成，再以正式取消返回 B 收尾。 */
    private static void capacityRetried(TestContext c, HomeEntry e, String label, UUID sid, HomeSessionRecord row, Set<Integer> rowSlots,
            CorridorPageManager.MappingView oldView, ServerPlayerEntity player, HomeSessionRecord pressure) {
        // T5-3 fix1：容量恢復之後由正式 tick 重試、寫出新舊租約聯集；之後先同步生成新租約周圍的走廊 chunk，換頁仍由正式 reposition tick 完成。
        M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, rowSlots), e.step(() -> {
            e.pregenerateNewLeases(sid, rowSlots, label);
            M7HomeClosureGameTests.until(c, label + "/remapped", 600, () -> e.remappedOnce(sid, oldView), e.step(() -> {
                var after = e.homeRow();
                c.assertTrue(after.sessionUuid().equals(sid) && after.state() == SessionState.SUPERPOSITION && after.home().equals(row.home()),
                        "容量恢復之後以原 SID、未變的 Selection 完成換頁：" + after);
                c.assertTrue(M7CapacityFixture.sharedReserveHolds(e.server),
                        "換頁之後整份 journal 的共享預留（含這一列 HOME 的完成預留）仍成立（16 MiB 邊界上的合法壓力）");
                c.assertTrue(Math.abs(e.logicalZ(sid, player) - 96.5) < 1e-6, "玩家以同一個邏輯座標搬到新實例");
                M7CapacityFixture.release(e.server, pressure);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "正式取消（返回 B）開始");
                e.awaitReleased(sid, label + "/cancel", null, () -> {
                    c.assertTrue(e.inB(player), "取消後回到 exact B");
                    e.finish();
                });
            }), e.step(() -> {
                throw new GameTestException("HOME_REMAP_NOT_COMPLETED：容量恢復之後新舊租約聯集已 checked，但換頁沒有在 600 tick 內完成：epoch="
                        + e.pages.currentMappings(sid).epoch() + " " + e.spaceDiagnostics(sid) + " row=" + e.homeRow());
            }));
        }), e.step(() -> {
            throw new GameTestException("HOME_REMAP_NOT_RETRIED：容量恢復之後正式 tick 沒有重試寫出新舊租約聯集：epoch=" + e.pages.currentMappings(sid).epoch()
                    + " row=" + e.homeRow() + " space=" + e.spaceSlots(sid));
        }));
    }

    /**
     * 正式 remap 進行中（HOME 列已 checked 新舊租約聯集、舊實例還沒退休），另一個 SID 的舊候選 append 嘗試侵占新舊 lease 預留（plan :325、spec §5.1、V01）。journal 恰在 16 MiB
     * 預留不變量的邊界上（壓力列的 padding 扣掉兩列 HOME 的完成預留；舊列沒有預留）：append 被拒——舊旅程留下既有 mapping、原 SID、已發布的門與原列，HOME 列可用的 CLOSE
     * 餘額（完成預留）不變；已承諾的 HOME remap 照常完成。兩趟都以正式路徑收尾（舊旅程先收）。容量釋出之後舊候選的重試由既有的
     * {@code M7HomeSpikeGameTests.candidate_and_remap_capacity_retry_keeps_published_team} 涵蓋。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_remap_reserve_windows", tickLimit = 4000)
    public void legacy_candidate_append_cannot_spend_home_remap_reserve(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            var legacy = new M2CorridorGameTests.NativeEntry(c, 1, net.minecraft.util.math.Direction.NORTH, true, new BlockPos(32, 0, 0));
            e.beforeClose(legacy::close);
            var label = "legacy_candidate_append_cannot_spend_home_remap_reserve";
            legacy.power();
            e.requireStarted();
            // 舊旅程的候選要全部發布完（候選每 tick 最多一批 256 個；發布數連續 5 次不變）才放壓力列：還有漏掉的 key 時，舊路徑每 tick 以整份 16 MiB journal 重試 append，
            // server 沒有空檔載入新租約的 chunk（T5-3 開發輪實測：HOME remap 的新實例停在載入）。
            int[] stable = {0, -1};
            M7HomeClosureGameTests.until(c, label + "/ready", 800, () -> {
                var old = legacy.record();
                if (!e.superpositionReady() || old == null || !legacy.activeReady()) return false;
                int published = e.pages.selectableDoors(old.sessionUuid()).size();
                if (published == 0 || published != stable[1]) { stable[1] = published; stable[0] = 0; return false; }
                return ++stable[0] >= 5;
            }, e.step(() -> {
                var row = e.homeRow();
                var sid = row.sessionUuid();
                var player = e.player(0);
                var old = legacy.record();
                var oldSid = old.sessionUuid();
                var oldMapping = e.pages.currentMappings(oldSid);
                var oldDoors = e.pages.selectableDoors(oldSid);
                var oldView = e.pages.currentMappings(sid).instances().getFirst();
                var key = new dev.quantumchamber.corridor.DoorKey(oldSid, 10_000L, dev.quantumchamber.corridor.DoorKey.DoorWallSide.POSITIVE_LATERAL);
                var pressure = M7CapacityFixture.admitBeside(e.server, row);
                e.beforeClose(() -> M7CapacityFixture.releaseIfPresent(e.server, pressure));
                e.walkHome(sid, player, 96.5);
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> {
                    var current = e.homeRow();
                    return current != null && current.spaceLeases().size() == 2;
                }, e.step(() -> {
                    // HOME 列已 checked 新舊租約聯集（已承諾的 remap 進行中）：另一個 SID 的舊候選 append。
                    var union = e.homeRow();
                    var journalBefore = e.journal.allSessions();
                    var append = new dev.quantumchamber.candidate.CandidateLedgerService().commitCandidates(e.server, oldSid, List.of(key));
                    c.assertTrue(!append.sessionFailed() && !append.committedKeys().contains(key),
                            "RESERVE_SPENT_BY_LEGACY_APPEND：舊候選 append 不得侵占 HOME 的新舊 lease 預留：committed=" + append.committedKeys().contains(key)
                                    + " failed=" + append.sessionFailed());
                    c.assertTrue(journalBefore.equals(e.journal.allSessions()) && old.equals(e.journal.allFlushedSessions().get(oldSid)),
                            "被拒的 append 不改 journal：舊旅程保留原 SID 與原列");
                    c.assertTrue(oldMapping.equals(e.pages.currentMappings(oldSid)) && oldDoors.equals(e.pages.selectableDoors(oldSid)),
                            "被拒的一方留下既有 mapping 與已發布的門");
                    // T5-4 Step 0（T5-3 品質 M-4）：原本以單列公式比較「CLOSE 餘額」，在列相等之後恆真；改以整份 journal 量測共享預留（含 HOME 列的完成預留）仍成立。
                    c.assertTrue(union.equals(e.homeRow()) && M7CapacityFixture.sharedReserveHolds(e.server),
                            "HOME 列不變，整份 journal 的共享預留（含 HOME 列可用的 CLOSE 完成預留）在 16 MiB 邊界上仍成立");
                    // T5-3 fix1：同步生成新租約周圍的走廊 chunk（只改 chunk 載入）。不預生成時，走廊世界同時有兩趟的玩家，新租約周圍半徑 2 的 chunk 要約 1000 tick
                    // 才全部 FULL（T5-3 開發輪實測）。被拒的舊旅程仍先以正式路徑收尾（壓力列仍在，舊列的返還與移除只會變小），再等已承諾的 HOME remap 由正式 tick 完成。
                    e.pregenerateNewLeases(sid, Set.of(oldView.ref().slotId()), label);
                    legacy.trustedTeardown((int) c.getTick() + 1, () -> M7HomeClosureGameTests.until(c, label + "/remapped", 600, () -> e.remappedOnce(sid, oldView), e.step(() -> {
                        var after = e.homeRow();
                        // T5-4 Step 0（T5-3 規格 M-4）：完成時舊旅程已收掉，journal 已不在 16 MiB 邊界上；這裡只斷言實際觀察到的事（聯集之後只剩只減不增的寫入）。
                        c.assertTrue(after.sessionUuid().equals(sid) && after.home().equals(row.home()) && M7CapacityFixture.sharedReserveHolds(e.server),
                                "聯集在 16 MiB 邊界上 checked 之後，已承諾的 HOME remap 在舊旅程收掉之後完成（原 SID、未變的 Selection、共享預留仍成立）：" + after);
                        M7CapacityFixture.release(e.server, pressure);
                        c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "HOME 正式取消（返回 B）開始");
                        e.awaitReleased(sid, label + "/home-cancel", null, () -> {
                            c.assertTrue(e.inB(player), "HOME 取消後回到 exact B");
                            e.finish();
                        });
                    }), e.step(() -> {
                        throw new GameTestException("HOME_REMAP_NOT_COMPLETED：聯集在 16 MiB 邊界上 checked 之後，已承諾的 HOME remap 在舊旅程收掉之後沒有在 600 tick 內完成：epoch=" + e.pages.currentMappings(sid).epoch()
                                + " " + e.spaceDiagnostics(sid) + " leases=" + e.homeRow().spaceLeases().stream().map(lease -> lease.slotId() + ":" + lease.bounds()).toList());
                    })));
                }), e.step(() -> {
                    throw new GameTestException("COMMITTED_REMAP_BLOCKED：16 MiB 邊界上的 HOME remap 沒有寫出新舊租約聯集（預留沒有涵蓋聯集）：row=" + e.homeRow());
                }));
            }), e.timeout(label + "/ready"));
        });
    }

    // ---- T5-3 fix2：換頁的寫出未確認（品質 I-1、M-7） ----

    /**
     * T5-3 fix2（品質 I-1，controller 採甲「寫出未確認即回 B」；品質 M-7）：換頁提交之後、舊租約移除的 checked roll-forward 寫出之前武裝 journal 寫出故障。寫出未確認
     * （ROLL_FORWARD_PENDING）時交 HOME 故障路徑（只回 B），原因記「HOME 舊租約移除的寫出未確認」，不由 gateway 的 Buff 檢查誤標成「B 身分或完整 cohort 效果失效」；
     * 同一個 tick 只寫一次（recovery 記 journal 退避，不立刻重寫）。解除故障、寫出確認之後，{@code settleConfirmedHomeRetirement} 補做舊 slot 的原生釋放，checked RETURNING
     * 照常返回 B、收尾，沒有殘留或永久 BUSY。RED：寫出未確認時原地 return，gateway 以 Buff 失效回 B（{@code HOME_RETIREMENT_PENDING_REASON}），recovery 同一個 tick 重寫
     * （{@code HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_retire_pending_windows", tickLimit = 3000)
    public void home_retirement_write_pending_returns_to_b_with_reason(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            e.requireStarted();
            var label = "home_retirement_write_pending_returns_to_b_with_reason";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var before = e.pages.currentMappings(sid);
                c.assertTrue(before.epoch() == 1 && before.instances().size() == 1 && row.spaceLeases().size() == 1, "前提：入場後一個實例、一筆租約：" + before);
                int oldSlot = before.instances().getFirst().ref().slotId();
                var oldSlots = Set.of(oldSlot);
                e.walkHome(sid, player, 96.5);
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, oldSlots), e.step(() -> {
                    e.pregenerateNewLeases(sid, oldSlots, label);
                    M7HomeClosureGameTests.until(c, label + "/committed", 600, () -> e.remapCommittedBeforeRetirement(sid, oldSlot), e.step(() -> {
                        var fault = e.armJournalFault(sid);
                        M7HomeClosureGameTests.until(c, label + "/write", 200, () -> fault.hits() >= 1, e.step(() -> {
                            int hits = fault.hits();
                            var current = e.journal.allSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
                            var flushed = e.homeRow();
                            c.assertTrue(e.journal.rollForwardPending(sid) && current != null && slots(current).size() == 1 && !slots(current).contains(oldSlot)
                                            && flushed != null && slots(flushed).size() == 2 && slots(flushed).contains(oldSlot),
                                    "前提：舊租約移除的寫出未確認（current 已移除舊 slot、flushed 仍有）：current=" + current + " flushed=" + flushed);
                            var problems = new ArrayList<String>();
                            if (hits != 1) problems.add("HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK：舊租約移除寫出未確認的同一個 tick 不得再重寫（等 journal 退避）：hits=" + hits);
                            long reasons = capture.matching(Level.ERROR, "HOME 走廊保留租約等待安全返回 B", sid.toString()).stream()
                                    .filter(entry -> thrownMentions(entry.thrown(), "HOME 舊租約移除的寫出未確認")).count();
                            var misattributed = capture.entries().stream().filter(entry -> thrownMentions(entry.thrown(), "活動中的 B 身分或完整 cohort 效果失效"))
                                    .map(entry -> entry.level() + " " + entry.message()).toList();
                            boolean failed = e.pages.homeFailed(sid);
                            if (!failed || reasons != 1 || !misattributed.isEmpty())
                                problems.add("HOME_RETIREMENT_PENDING_REASON：舊租約移除寫出未確認須交 HOME 故障路徑並記實際原因，不得以 Buff 失效誤標：homeFailed=" + failed
                                        + " reasonErrors=" + reasons + " misattributed=" + misattributed);
                            c.assertTrue(problems.isEmpty(), String.join("；", problems));
                            fault.disarm();
                            M7HomeClosureGameTests.until(c, label + "/settled", 600, () -> e.retirementSettled(sid, oldSlot), e.step(() -> {
                                e.awaitReleased(sid, label + "/released", null, () -> {
                                    c.assertTrue(e.inB(player), "寫出恢復之後照常返回 exact B");
                                    e.finish();
                                });
                            }), e.step(() -> {
                                throw new GameTestException("HOME_RETIRED_SLOT_NOT_SETTLED：移除寫出確認之後，Space 仍持有舊 slot（checked 列已沒有它）：slots=" + e.spaceSlots(sid)
                                        + " row=" + e.homeRow() + " " + e.spaceDiagnostics(sid));
                            }));
                        }), e.timeout(label + "/write"));
                    }), e.step(() -> {
                        throw new GameTestException("HOME_REMAP_NOT_COMMITTED：聯集已 checked，但換頁沒有在 600 tick 內提交：epoch=" + e.pages.currentMappings(sid).epoch()
                                + " " + e.spaceDiagnostics(sid) + " row=" + e.homeRow());
                    }));
                }), e.step(() -> {
                    throw new GameTestException("HOME_REMAP_NOT_ATTEMPTED：正式 tick 沒有為 HOME 走廊換頁（新舊租約聯集沒有寫出）：epoch=" + e.pages.currentMappings(sid).epoch()
                            + " row=" + e.homeRow());
                }));
            });
        });
    }

    /**
     * T5-3 fix2（品質 I-1(a)、M-7）：換頁的新舊租約聯集寫出未確認（ROLL_FORWARD_PENDING）。current 已帶著聯集，額外 slot 留著，補取全部租約的票之後交 HOME 故障路徑
     * （只回 B）；同一個 tick 只寫一次（recovery 記 journal 退避）。解除故障、寫出確認之後 checked RETURNING：新租約有票、進入 entity ticking，返還找得到 pin，照常返回 B、
     * 收尾，沒有殘留或永久 BUSY。RED：recovery 同一個 tick 重寫（{@code HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK}）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_corridor_union_pending_windows", tickLimit = 3000)
    public void home_remap_union_write_pending_tickets_new_lease_and_returns_to_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new HomeEntry(c, 1, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "home_remap_union_write_pending_tickets_new_lease_and_returns_to_b";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var before = e.pages.currentMappings(sid);
                c.assertTrue(before.epoch() == 1 && before.instances().size() == 1 && row.spaceLeases().size() == 1, "前提：入場後一個實例、一筆租約：" + before);
                var oldSlots = Set.of(before.instances().getFirst().ref().slotId());
                var fault = e.armJournalFault(sid);
                e.walkHome(sid, player, 96.5);
                M7HomeClosureGameTests.until(c, label + "/write", 200, () -> fault.hits() >= 1, e.step(() -> {
                    int hits = fault.hits();
                    var current = e.journal.allSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
                    var flushed = e.homeRow();
                    c.assertTrue(e.journal.rollForwardPending(sid) && current != null && current.spaceLeases().size() == 2 && slots(current).containsAll(oldSlots)
                                    && flushed != null && slots(flushed).equals(oldSlots),
                            "前提：新舊租約聯集的寫出未確認（current 帶著聯集、flushed 只有舊租約）：current=" + current + " flushed=" + flushed);
                    // 新租約的 chunk 先同步生成（只改 chunk 載入，比照 fix1），返還時新租約進入 entity ticking 不受負載拖慢。
                    e.pregenerateNewLeases(sid, oldSlots, label, current);
                    c.assertTrue(hits == 1, "HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK：聯集寫出未確認的同一個 tick 不得再重寫（等 journal 退避）：hits=" + hits);
                    c.assertTrue(e.pages.homeFailed(sid) && e.spaceSlots(sid).equals(slots(current)),
                            "聯集寫出未確認：額外 slot 留著，交 HOME 故障路徑：homeFailed=" + e.pages.homeFailed(sid) + " slots=" + e.spaceSlots(sid));
                    e.assertCorridorTickets(sid, current, oldSlots, "HOME_UNION_PENDING_TICKETS_MISSING");
                    fault.disarm();
                    e.awaitReleased(sid, label + "/released", null, () -> {
                        c.assertTrue(e.inB(player), "寫出恢復之後照常返回 exact B");
                        e.finish();
                    });
                }), e.timeout(label + "/write"));
            });
        });
    }

    /** 例外鏈（cause 與 suppressed）裡任一訊息含 {@code fragment}。 */
    private static boolean thrownMentions(Throwable failure, String fragment) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        if (failure != null) queue.add(failure);
        while (!queue.isEmpty()) {
            var node = queue.poll();
            if (!seen.add(node)) continue;
            if (node.getMessage() != null && node.getMessage().contains(fragment)) return true;
            if (node.getCause() != null) queue.add(node.getCause());
            queue.addAll(List.of(node.getSuppressed()));
        }
        return false;
    }

    private static Set<Integer> slots(HomeSessionRecord row) {
        var slots = new TreeSet<Integer>();
        row.spaceLeases().forEach(lease -> slots.add(lease.slotId()));
        return slots;
    }

    /** 這座艙在 gateway 的 owner 型別（{@code Provisional}／{@code HomeRuntime}／{@code SuperpositionSession}），沒有時為 null。只讀反射。 */
    static String ownerType(UUID chamber) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(gate(), "sessions")).get(chamber);
        return owner == null ? null : owner.getClass().getSimpleName();
    }

    // ---- fixture ----

    /** 這座艙在 gateway 的暫占（{@code Provisional}）的 SID；沒有暫占時為 null。只讀反射，不改狀態。 */
    static UUID provisionalSid(UUID chamber) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(gate(), "sessions")).get(chamber);
        if (owner == null || !owner.getClass().getSimpleName().equals("Provisional")) return null;
        return (UUID) M4CandidateTestAccess.get(owner, "sid");
    }

    /**
     * A 原艙（正式登記；{@code powerA} 時供電，艙內沒有人、不會出發）、同艙已確認 COMPLETE 的 B 投影（關門封艙、以紅石塊供電、測試票保持區塊），
     * {@code count} 位帶 QuantumState 的參與者站在 B 艙內。比照 {@code M7HomeClosureGameTests.Projection}，另支援多人與 B 的入場前提。
     */
    static final class HomeEntry implements AutoCloseable {
        final TestContext c;
        final M2CorridorGameTests.TestHooks hooks;
        final M2CorridorGameTests.NativeEntry f;
        final MinecraftServer server;
        final ServerWorld a;
        final SessionRecoveryState journal;
        final CorridorPageManager pages;
        final ServerWorld b;
        final ChamberFrame frame;
        final UUID chamber;
        final EntryEndpoint entry;
        final ChamberOriginAuthority root;
        final ChamberControllerBlockEntity bController;
        private Runnable release = () -> { };
        private final Runnable predicateCleanup;
        private boolean closed;
        /** T5-3：{@link #close} 最先執行的收尾（後登記先執行），排在任何 journal 寫出（失敗路徑的返還請求）之前；例如還原記憶體中膨脹的壓力列。 */
        private final ArrayDeque<Runnable> beforeClose = new ArrayDeque<>();

        void beforeClose(Runnable cleanup) { beforeClose.push(Objects.requireNonNull(cleanup, "cleanup")); }

        /**
         * T5-3 fix2（品質 M-8 第 2 點，只給選用的案）：失敗路徑不立即斷開假玩家。先請求正式返還（取消暫占、未選定的 ARMING／SUPERPOSITION 送返還），列還在或艙仍 busy 時
         * 交 {@link FailureSettle} 每個 server tick 核對，正式返還收尾（列移除、艙不再 busy）或逾時之後才釋放 B 的測試票並斷開；避免 RETURNING 列因假玩家離線而永久留下。
         * T5-4 Step 0（T5-3 fix2 複審 N-2）：operator 不參與返還，延後時立即斷開；停服時清空等待；換頁五案都套用。
         */
        private boolean settleOnFailure;
        /** 成功路徑（{@link #finish}）的收尾；不走失敗路徑的延後斷開。 */
        private boolean finishing;

        void settleOnFailure() { settleOnFailure = true; }

        HomeEntry(TestContext c, int count, M2CorridorGameTests.TestHooks hooks, boolean powerA) {
            this.c = c; this.hooks = hooks;
            f = new M2CorridorGameTests.NativeEntry(c, count);
            server = f.server; a = c.getWorld(); journal = SessionRecoveryState.get(server); pages = f.pages;
            predicateCleanup = M7HomeClosureGameTests.onPredicateFailure(c, failure -> {
                try { close(); }
                finally { hooks.releaseAfter(failure); }
            });
            try {
                M7HomeClosureGameTests.requireCandidateStores(server);
                var origin = f.controller();
                c.assertTrue(new ChamberActivationService().attemptArm(a, origin).accepted(), "正式 A 登記");
                chamber = origin.chamberUuid();
                frame = new ChamberFrame(origin.getPos(), origin.getCachedState().get(ChamberControllerBlock.FACING));
                var id = UniverseId.of(UUID.randomUUID());
                b = M6ProfileProbePhases.fixtureSource(server, id, "normal");
                var authorities = CollapseAuthorities.get(server);
                var index = ProjectionProtectionIndex.get(server);
                var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, frame.controllerPos(),
                        frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
                index.confirmed(pending);
                release = M7HomeClosureGameTests.holdChunks(b, frame);
                c.assertTrue(ProjectionWriter.write(b, frame, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN, "正式投影寫入");
                bController = (ChamberControllerBlockEntity) b.getBlockEntity(frame.controllerPos());
                bController.setInstanceKind(ChamberInstanceKind.PROJECTION);
                index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
                entry = new EntryEndpoint(chamber, id, b.getRegistryKey().getValue(), DimensionRole.OVERWORLD, frame.controllerPos(), frame.outwardFacing(),
                        ChamberInstanceKind.PROJECTION);
                root = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, frame.controllerPos(), frame.outwardFacing(),
                        ChamberInstanceKind.ORIGIN);
                c.assertTrue(HomeEndpointAuthority.inspectEntry(server, entry) == HomeEndpointAuthority.EntryStatus.VALID, "B 是合法投影端");
                c.assertTrue(new ChamberDoorService().applyTarget(b, frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 門關上（封艙）");
                var power = frame.controllerPos().offset(frame.outwardFacing());
                ChamberProtectionService.get().authorizedMutation(() -> b.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState(), 3));
                c.assertTrue(b.isReceivingRedstonePower(frame.controllerPos()), "B 已供電");
                for (int i = 0; i < f.players.size(); i++) {
                    var player = f.players.get(i).player();
                    var spot = Vec3d.ofBottomCenter(ChamberGeometry.localToWorld(frame, 1 + i, 1, 3));
                    c.assertTrue(player.teleport(b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && player.getServerWorld() == b, "參與者站在 B 艙內");
                    player.setVelocity(Vec3d.ZERO);
                }
                if (powerA) {
                    f.power();
                    c.assertTrue(a.isReceivingRedstonePower(frame.controllerPos()), "A 已供電（艙內沒有人，不會出發）");
                }
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        ServerPlayerEntity player(int index) { return f.players.get(index).player(); }
        List<UUID> ids() { return f.players.stream().map(connection -> connection.player().getUuid()).toList(); }
        ServerWorld corridor() { return server.getWorld(SuperpositionWorld.KEY); }

        boolean inB(ServerPlayerEntity player) {
            return player != null && player.getServerWorld() == b && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame), player.getBoundingBox());
        }

        /** 這座艙的 checked HOME 列（flushed）；沒有時為 null。 */
        HomeSessionRecord homeRow() {
            for (var row : journal.allFlushedSessions().values())
                if (row instanceof HomeSessionRecord home && home.chamberUuid().equals(chamber)) return home;
            return null;
        }

        /** 正式 startHome 受理（STAGING）。RED：腳手架一律拒絕，以 {@code HOME_START_REJECTED} 失敗。 */
        void requireStarted() {
            var result = gate().startHome(b, bController, ids());
            c.assertTrue(result == ChamberSessionGateway.StartResult.STAGING, "HOME_START_REJECTED：合法 B 的 startHome 必須受理：" + result);
        }

        /** mutation 讓 startHome 不該受理卻受理時，取消自己的暫占（test 本身照樣失敗）。 */
        void cancelOwnStart() {
            var sid = provisionalSid(chamber);
            if (sid != null) gate().cancelProvisional(server, chamber, sid);
        }

        /** 把這座艙的 START_CHECK 票換成 200 tick 內不會 FULL 的 backend（加票與移除仍是原生），回傳暫占的 SID。只在 startHome 受理的同一個 callback 內呼叫。 */
        UUID delayRootTicket() {
            var sid = provisionalSid(chamber);
            c.assertTrue(sid != null, "START_CHECK 有暫占");
            var owner = ((Map<?, ?>) M4CandidateTestAccess.get(HomeRootTickets.class, "servers")).get(server);
            var lease = ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).get(sid);
            c.assertTrue(lease != null, "START_CHECK 已取得 A 的票");
            M4CandidateTestAccess.set(lease, "backend", new HomeTicketFaultFixture.DelayedFull(server));
            return sid;
        }

        /** SUPERPOSITION 已 checked、mapping 已發布、入口 overlay 已寫完。 */
        void awaitSuperposition(String label, Consumer<HomeSessionRecord> then) {
            awaitSuperposition(label, then, timeout(label + "/superposition"));
        }

        /** 同 {@link #awaitSuperposition(String, Consumer)}，逾時改執行 {@code timeout}（具名失敗）。 */
        void awaitSuperposition(String label, Consumer<HomeSessionRecord> then, Runnable timeout) {
            M7HomeClosureGameTests.until(c, label + "/superposition", 400, this::superpositionReady, step(() -> then.accept(homeRow())), timeout);
        }

        /** SUPERPOSITION 已 checked（current 與 flushed 相同）、mapping 已發布、入口 overlay 已寫完。 */
        boolean superpositionReady() {
            var row = homeRow();
            if (row == null || row.state() != SessionState.SUPERPOSITION || !row.equals(journal.allSessions().get(row.sessionUuid()))
                    || !pages.hasReservation(row.sessionUuid()) || pages.currentMappings(row.sessionUuid()).epoch() < 1) return false;
            for (var cell : overlayCells(row.sessionUuid())) if (!corridor().getBlockState(cell).isAir()) return false;
            return true;
        }

        /**
         * T5-3 Step 0（品質 M-1）：把這座艙 START_CHECK 票的 backend 換成第一次原生移除丟出例外的代理（加票與之後的移除照原生）。FULL 判斷不照原生 FULL ticket 層級，
         * 改為「該 chunk 已載入為 WorldChunk」（{@code getWorldChunk != null}；T5-4 Step 0 更正 T5-3 品質 M-6 指出的說明不符）。回傳丟出次數的計數格。
         * 只在 startHome 受理的同一個 callback 內呼叫；票 owner 的租約是 package-private 介面，以 {@link java.lang.reflect.Proxy} 在同一個 package 建立代理。
         */
        int[] failFirstRootTicketRemoval(UUID sid) {
            var owner = ((Map<?, ?>) M4CandidateTestAccess.get(HomeRootTickets.class, "servers")).get(server);
            var lease = ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).get(sid);
            c.assertTrue(lease != null, "START_CHECK 已取得 A 的票");
            var nativeBackend = M4CandidateTestAccess.get(HomeRootTickets.class, "NATIVE");
            Class<?> type;
            try { type = Class.forName("dev.quantumchamber.collapse.HomeRootTickets$Backend", false, HomeRootTickets.class.getClassLoader()); }
            catch (ClassNotFoundException failure) { throw new GameTestException("HomeRootTickets.Backend 找不到：" + failure); }
            int[] thrown = {0};
            var proxy = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
                if (method.getName().equals("remove") && thrown[0] == 0) {
                    thrown[0]++;
                    throw new IllegalStateException("M7_T5_3_START_CHECK_RELEASE_FAULT");
                }
                if (method.getName().equals("full")) {
                    var world = (ServerWorld) args[0];
                    var pos = (ChunkPos) args[1];
                    return world.getChunkManager().getWorldChunk(pos.x, pos.z) != null;
                }
                method.setAccessible(true);
                try { return method.invoke(nativeBackend, args); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            M4CandidateTestAccess.set(lease, "backend", proxy);
            return thrown;
        }

        /**
         * 這座艙不再 busy（HOME 列 checked 移除、runtime 與暫占都已釋放），再核對沒有殘留：列、走廊 space、完成收據與 Entry=B 來源票都已清除。{@code each} 在每次判斷時先執行（監看用）。
         */
        void awaitReleased(UUID sid, String label, Runnable each, Runnable then) {
            M7HomeClosureGameTests.until(c, label + "/released", 600, () -> {
                if (each != null) each.run();
                return !gate().globalChamberBusy(server, chamber);
            }, step(() -> {
                c.assertTrue(!journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid) && !journal.pendingSessions().containsKey(sid),
                        "HOME 列已 checked 移除");
                c.assertTrue(!pages.hasReservation(sid) && !pages.releaseComplete(sid), "走廊 space 已釋放、完成收據已消費（ack）");
                c.assertTrue(gate().localPresence(b, frame.controllerPos()) == ChamberSessionGateway.Presence.NONE, "B 不再顯示旅程");
                assertNoSourceTicket(sid);
                then.run();
            }), timeout(label + "/released"));
        }

        /** 走廊 lease 內帶 home_marker 的 bulkhead（實際讀世界）。 */
        Set<BlockPos> homeMarkers(HomeSessionRecord row) {
            var cells = new LinkedHashSet<BlockPos>();
            for (var lease : row.spaceLeases()) {
                var box = lease.bounds();
                for (var pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
                    var state = corridor().getBlockState(pos);
                    if (state.isOf(ModBlocks.QUANTUM_BULKHEAD) && state.get(QuantumBulkheadBlock.HOME_MARKER)) cells.add(pos.toImmutable());
                }
            }
            return cells;
        }

        /**
         * T5-3：把玩家以原生移動推進到目前（唯一）實例的邏輯 z（走廊中心 lateral 3.5、地板上 1）；之後的換頁由正式 tick 完成。只用 {@code currentMappings}／{@code toPhysical}，
         * 不經 HOME 的 durable 核對，所以在 RED（HOME 沒有換頁分支）時也能執行。
         */
        void walkHome(UUID sid, ServerPlayerEntity player, double z) {
            var view = pages.currentMappings(sid).instances().getFirst();
            var target = pages.toPhysical(view.ref(), new CorridorPageManager.LogicalPose(3.5, 1, z, Vec3d.ZERO, 0, 0));
            var box = player.getBoundingBox().offset(target.position().subtract(player.getPos()));
            c.assertTrue(corridor().isSpaceEmpty(player, box), "HOME 走廊長走終點有完整原生 collision 空間：" + target.position());
            c.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(player, corridor(), target.position(), target.velocity(), target.yaw(), target.pitch()),
                    "真 native pose 推進，換頁由正式 tick 完成");
        }

        /** 玩家目前所在實例的邏輯 z（以 bounds 找到唯一包含玩家的實例）。 */
        double logicalZ(UUID sid, ServerPlayerEntity player) {
            for (var view : pages.currentMappings(sid).instances()) {
                var b = view.bounds();
                var p = player.getPos();
                if (p.x >= b.getMinX() && p.x < b.getMaxX() + 1 && p.y >= b.getMinY() && p.y < b.getMaxY() + 1 && p.z >= b.getMinZ() && p.z < b.getMaxZ() + 1)
                    return pages.toLogical(view.ref(), new CorridorPageManager.PhysicalPose(p, player.getVelocity(), player.getYaw(), player.getPitch())).logicalZ();
            }
            throw new GameTestException("玩家不在任何目前實例內：" + player.getPos());
        }

        /** 走廊 Space 目前持有的 slot（testmod 反射讀 {@code Space.leases}）。 */
        Set<Integer> spaceSlots(UUID sid) {
            var space = M4CandidateTestAccess.space(pages, sid);
            if (space == null) return Set.of();
            var slots = new TreeSet<Integer>();
            for (var key : ((Map<?, ?>) M4CandidateTestAccess.get(space, "leases")).keySet()) slots.add((Integer) key);
            return slots;
        }

        /** 逾時診斷（只讀反射）：Space 的旗標、建造／退休佇列與各租約 chunk 的載入狀態。只放進失敗訊息，不參與判定。 */
        String spaceDiagnostics(UUID sid) {
            try {
                var space = M4CandidateTestAccess.space(pages, sid);
                if (space == null) return "space=ABSENT";
                var line = new StringBuilder("space[");
                for (var name : List.of("queued", "failed", "releasing", "operations")) line.append(name).append('=').append(M4CandidateTestAccess.get(space, name)).append(' ');
                line.append("builds=").append(((Map<?, ?>) M4CandidateTestAccess.get(space, "builds")).keySet())
                        .append(" retiring=").append(M4CandidateTestAccess.get(space, "retiring"))
                        .append(" pending=").append(M4CandidateTestAccess.get(space, "pending") != null)
                        .append(" ticketed=").append(((Map<?, ?>) M4CandidateTestAccess.get(space, "ticketed")).keySet()).append(']');
                for (var entry : ((Map<?, ?>) M4CandidateTestAccess.get(space, "leases")).entrySet()) {
                    var box = ((SessionRecoveryRecord.SpaceLease) entry.getValue()).bounds();
                    int total = 0, full = 0, ticking = 0;
                    for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) {
                        total++;
                        if (corridor().getChunkManager().getWorldChunk(x, z) != null) full++;
                        if (corridor().shouldTick(new ChunkPos(x, z))) ticking++;
                    }
                    line.append(" lease").append(entry.getKey()).append(" chunks=").append(total).append(" full=").append(full).append(" ticking=").append(ticking);
                    int ring = 0, ringFull = 0;
                    for (int x = (box.getMinX() >> 4) - 2; x <= (box.getMaxX() >> 4) + 2; x++) for (int z = (box.getMinZ() >> 4) - 2; z <= (box.getMaxZ() >> 4) + 2; z++) {
                        ring++;
                        if (corridor().getChunkManager().getWorldChunk(x, z) != null) ringFull++;
                    }
                    line.append(" ring=").append(ringFull).append('/').append(ring);
                    try {
                        var manager = corridor().getChunkManager().chunkLoadingManager;
                        var method = manager.getClass().getDeclaredMethod("getCurrentChunkHolder", long.class);
                        method.setAccessible(true);
                        var holder = (net.minecraft.server.world.ChunkHolder) method.invoke(manager, ChunkPos.toLong(box.getMinX() >> 4, box.getMinZ() >> 4));
                        line.append(" firstHolder=").append(holder == null ? "null" : holder.getLevel() + "/" + holder.getLevelType());
                    } catch (ReflectiveOperationException | RuntimeException holderFailure) { line.append(" firstHolder=?").append(holderFailure.getClass().getSimpleName()); }
                }
                line.append(" avgTickMs=").append(server.getAverageNanosPerTick() / 1_000_000.0);
                return line.toString();
            } catch (RuntimeException failure) { return "diagnostics-failed=" + failure; }
        }

        /** 正式 tick 已以 checked 寫出新租約：checked 列（current＝flushed）帶著 {@code oldSlots} 以外的 slot。 */
        boolean newLeaseWritten(UUID sid, Set<Integer> oldSlots) {
            var row = homeRow();
            return row != null && row.sessionUuid().equals(sid) && row.equals(journal.allSessions().get(sid))
                    && row.spaceLeases().stream().anyMatch(lease -> !oldSlots.contains(lease.slotId()));
        }

        /**
         * T5-3 fix1（使用者 2026-11-25「測試先生成走廊 chunk（建議）」）：checked 列上 {@code oldSlots} 以外的每一筆新租約，把 footprint 外擴 2 個 chunk 以走廊世界的
         * {@code getChunk(x, z)} 同步生成到 FULL。新實例要 entity ticking 才算租約就緒，而 entity ticking 需要 5×5 鄰域都是 FULL；不預生成時全新 slot 的鄰域在負載下約 1000 tick
         * 才載完（T5-3 開發輪實測）。比照冷 B 案的「測試先生成 B 地形」：只改 chunk 載入，不呼叫任何 remap API，換頁仍由正式 reposition tick 完成。
         */
        void pregenerateNewLeases(UUID sid, Set<Integer> oldSlots, String label) {
            pregenerateNewLeases(sid, oldSlots, label, homeRow());
        }

        /** 同 {@link #pregenerateNewLeases(UUID, Set, String)}，新租約取自 {@code row}（fix2：聯集寫出未確認時，新租約只在 current）。 */
        void pregenerateNewLeases(UUID sid, Set<Integer> oldSlots, String label, HomeSessionRecord row) {
            c.assertTrue(row != null && row.sessionUuid().equals(sid), "預生成的前提：HOME 列仍在：" + row);
            int leases = 0;
            for (var lease : row.spaceLeases()) {
                if (oldSlots.contains(lease.slotId())) continue;
                leases++;
                var box = lease.bounds();
                long start = System.nanoTime();
                int chunks = 0, full = 0;
                for (int x = (box.getMinX() >> 4) - 2; x <= (box.getMaxX() >> 4) + 2; x++)
                    for (int z = (box.getMinZ() >> 4) - 2; z <= (box.getMaxZ() >> 4) + 2; z++) {
                        corridor().getChunk(x, z);
                        chunks++;
                        if (corridor().getChunkManager().getWorldChunk(x, z) != null) full++;
                    }
                long wallMillis = (System.nanoTime() - start) / 1_000_000;
                LOG.info("M7_T53_CORRIDOR_PREGEN case={} sid={} slot={} chunks={} full={} wallMillis={}", label, sid, lease.slotId(), chunks, full, wallMillis);
                c.assertTrue(full == chunks, "預生成之後新租約外擴 2 個 chunk 都是 FULL：" + full + "/" + chunks);
            }
            c.assertTrue(leases > 0, "預生成的前提：checked 列已有新租約：" + row.spaceLeases());
        }

        /** 一次換頁已完成：mapping 前進到 epoch 2 的唯一新實例，舊實例已退休（checked 列與 Space 都只剩新實例的租約）。 */
        boolean remappedOnce(UUID sid, CorridorPageManager.MappingView oldView) {
            var current = pages.currentMappings(sid);
            var row = homeRow();
            if (row == null || current.epoch() != 2 || current.instances().size() != 1) return false;
            var slot = current.instances().getFirst().ref().slotId();
            return slot != oldView.ref().slotId() && row.spaceLeases().size() == 1 && row.spaceLeases().getFirst().slotId() == slot
                    && row.equals(journal.allSessions().get(sid)) && spaceSlots(sid).equals(Set.of(slot));
        }

        /** fix2：換頁已提交（mapping 前進到 epoch 2 的唯一新實例），舊租約仍在 checked 列與 Space（退休中、移除尚未寫出）。 */
        boolean remapCommittedBeforeRetirement(UUID sid, int oldSlot) {
            var current = pages.currentMappings(sid);
            var row = homeRow();
            return row != null && current.epoch() == 2 && current.instances().size() == 1 && current.instances().getFirst().ref().slotId() != oldSlot
                    && slots(row).contains(oldSlot) && row.equals(journal.allSessions().get(sid)) && spaceSlots(sid).contains(oldSlot);
        }

        /** fix2：舊租約移除已 checked、列已轉 checked RETURNING，Space 也已釋放舊 slot；或已整組收尾（列與 Space 都已移除）。 */
        boolean retirementSettled(UUID sid, int oldSlot) {
            var row = homeRow();
            if (row == null) return !spaceSlots(sid).contains(oldSlot);
            return row.sessionUuid().equals(sid) && row.state() == SessionState.RETURNING && !slots(row).contains(oldSlot)
                    && row.equals(journal.allSessions().get(sid)) && !spaceSlots(sid).contains(oldSlot);
        }

        /** fix2：{@code row} 上 {@code oldSlots} 以外的新租約，footprint 的每個 chunk 都有這個 SID 的原生走廊票（{@code quantumchamber_corridor}）。 */
        void assertCorridorTickets(UUID sid, HomeSessionRecord row, Set<Integer> oldSlots, String marker) {
            var held = M5GameTestSupport.tickets(corridor(), "quantumchamber_corridor", sid);
            var missing = new ArrayList<ChunkPos>();
            int leases = 0;
            for (var lease : row.spaceLeases()) {
                if (oldSlots.contains(lease.slotId())) continue;
                leases++;
                var box = lease.bounds();
                for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++)
                    if (!held.containsKey(new ChunkPos(x, z))) missing.add(new ChunkPos(x, z));
            }
            c.assertTrue(leases > 0 && missing.isEmpty(), marker + "：新租約 footprint 的每個 chunk 都要有這個 SID 的走廊票：leases=" + leases
                    + " missing=" + missing.size() + " " + missing.stream().limit(5).toList());
        }

        /**
         * fix2：武裝 journal 正式檔的寫出故障（{@link M7NativeSaveFault}）。收尾（成功與失敗路徑都是 {@link #close} 最先）先解除故障、把這個 SID 未確認的 roll-forward 寫出確認，
         * 再由 hooks 刪除暫存目錄；避免共用 journal 的 pending 讓之後的 HOME 入場被拒（T5-3 Step 0 RED 實測的連坐）。
         */
        M7NativeSaveFault.Handle armJournalFault(UUID sid) {
            M7NativeSaveFault.Handle fault;
            try { fault = M7NativeSaveFault.arm(server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat")); }
            catch (java.io.IOException failure) { throw new GameTestException("M7NativeSaveFault 武裝失敗：" + failure); }
            hooks.add(fault::close);
            beforeClose(() -> {
                fault.disarm();
                try { if (journal.rollForwardPending(sid)) journal.flush(server); }
                catch (RuntimeException flush) { LOG.warn("M7_GT_ROLL_FORWARD_CLEANUP 寫出失敗：session={}", sid, flush); }
            });
            return fault;
        }

        /**
         * T5-4 Step 0（T5-2 規格 M-2、T5-3 規格 M-2，spec §9.1）：記下每一次帶著這個 SID 變更的 journal checked 寫出（寫出開頭 current 與 flushed 不同），以及寫出當下
         * 這個 SID 的 session operation guard 是否持有（{@code operationInProgress}）。每筆是「種類 guard=…」；種類是 remove／admit／state:&lt;狀態&gt;／leases:&lt;舊&gt;-&gt;&lt;新&gt;／
         * returned／other。探針經 hooks 移除。
         */
        List<String> guardWitness(UUID sid) {
            var writes = new ArrayList<String>();
            M5GameTestSupport.installFlushProbe(c, state -> {
                var current = state.allSessions().get(sid);
                var flushed = state.allFlushedSessions().get(sid);
                if (Objects.equals(current, flushed)) return;
                writes.add(writeKind(current, flushed) + " guard=" + pages.operationInProgress(sid));
            }, hooks);
            return writes;
        }

        private static String writeKind(JournalSession current, JournalSession flushed) {
            if (!(current instanceof HomeSessionRecord now)) return "remove";
            if (!(flushed instanceof HomeSessionRecord before)) return "admit";
            if (now.state() != before.state()) return "state:" + now.state();
            if (!now.spaceLeases().equals(before.spaceLeases())) return "leases:" + slots(before) + "->" + slots(now);
            if (!now.participants().equals(before.participants())) return "returned";
            return "other";
        }

        /**
         * T5-4 Step 0 的負控（spec §9.1）：以 testmod 反射替這個 SID 占住 session operation guard——與 {@code exclusiveOperation} 相同，{@code operating} 加入 SID、
         * {@code space.operations} 設為 1（C6）。回傳冪等的釋放；失敗路徑在 {@link #close} 最先、hooks 也會釋放。
         */
        Runnable occupySessionGuard(UUID sid) {
            @SuppressWarnings("unchecked") var operating = (Set<UUID>) M4CandidateTestAccess.get(pages, "operating");
            var space = M4CandidateTestAccess.space(pages, sid);
            c.assertTrue(space != null && !pages.operationInProgress(sid), "負控前提：走廊 space 在、guard 未被占");
            operating.add(sid);
            M4CandidateTestAccess.set(space, "operations", 1);
            boolean[] released = {false};
            Runnable release = () -> {
                if (released[0]) return;
                released[0] = true;
                M4CandidateTestAccess.set(space, "operations", 0);
                operating.remove(sid);
            };
            beforeClose(release);
            hooks.add(release);
            return release;
        }

        /** {@code bounds} 內的 bulkhead → 是否帶 home_marker（實際讀世界）。 */
        Map<BlockPos, Boolean> bulkheads(net.minecraft.util.math.BlockBox bounds) {
            var cells = new LinkedHashMap<BlockPos, Boolean>();
            for (var pos : BlockPos.iterate(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ())) {
                var state = corridor().getBlockState(pos);
                if (state.isOf(ModBlocks.QUANTUM_BULKHEAD)) cells.put(pos.toImmutable(), state.get(QuantumBulkheadBlock.HOME_MARKER));
            }
            return cells;
        }

        boolean allAir(net.minecraft.util.math.BlockBox bounds) {
            for (var pos : BlockPos.iterate(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()))
                if (!corridor().getBlockState(pos).isAir()) return false;
            return true;
        }

        /**
         * {@code view} 以 HOME mode 建造：帶 home_marker 的 bulkhead 恰 25 格（一扇 HOME 門），不帶 marker 的 bulkhead 恰為入口 replica 的正門 25 格
         * （legacy mode 會在每一站兩側都放一般 bulkhead；第二扇 HOME 門會讓 marker 變成 50 格）。
         */
        void assertSingleHomeDoor(UUID sid, CorridorPageManager.MappingView view, String label) {
            var cells = bulkheads(view.bounds());
            var markers = cells.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
            var plain = cells.entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            var entrance = pages.entrance(sid);
            var frontDoor = new HashSet<BlockPos>();
            for (int x = 1; x <= 5; x++) for (int y = 1; y <= 5; y++) frontDoor.add(ChamberSpaceCoordinates.block(entrance, x, y, 0));
            c.assertTrue(markers.size() == 25 && plain.equals(frontDoor), label + "：實例內只能有一扇 25 格 HOME 門，其他 bulkhead 只有入口正門：marker="
                    + markers.size() + " plain=" + plain.size() + " frontDoor=" + frontDoor.size() + " plainOutsideDoor=" + plain.stream().filter(pos -> !frontDoor.contains(pos)).limit(5).toList());
        }

        /** 入口 replica 兩側牆的連接開口（lateral：x 0、6，y 1..5，深度 1..5），以 production 的入口 frame 推導。 */
        List<BlockPos> overlayCells(UUID sid) {
            var entrance = pages.entrance(sid);
            var cells = new ArrayList<BlockPos>();
            for (int x : new int[]{0, 6}) for (int y = 1; y <= 5; y++) for (int n = 1; n <= 5; n++) cells.add(ChamberSpaceCoordinates.block(entrance, x, y, n));
            return cells;
        }

        /** gateway 的暫占／runtime、journal 三份 view、走廊 space 與原生原艙票（A 世界的 home_root 票總數）。 */
        List<?> ownership() {
            var gateway = gate();
            return List.of(Set.copyOf(((Map<?, ?>) M4CandidateTestAccess.get(gateway, "sessions")).keySet()),
                    Set.copyOf(((Map<?, ?>) M4CandidateTestAccess.get(pages, "spaces")).keySet()),
                    journal.allSessions(), journal.allFlushedSessions(), journal.pendingSessions(), homeRootTickets());
        }

        private int homeRootTickets() {
            var index = ((dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor)
                    a.getChunkManager().chunkLoadingManager.getTicketManager()).quantumchamberTest$getTicketsByPosition();
            int count = 0;
            for (var tickets : index.values()) for (var ticket : tickets) if (ticket.getType().toString().equals("quantumchamber_home_root")) count++;
            return count;
        }

        /** callback 的 guard：失敗時先收尾 fixture 與 hooks，再丟出原失敗。 */
        Runnable step(Runnable body) {
            return () -> {
                try { body.run(); }
                catch (RuntimeException | Error failure) { close(); hooks.releaseAfter(failure); throw failure; }
            };
        }

        void guard(Runnable body) { step(body).run(); }

        /** 之後的 {@code ticks} 個 tick 每 tick 執行一次 {@code monitor}（一次只排一筆），結束後執行 {@code then}；任一步失敗都先收尾。 */
        void hold(int ticks, Runnable monitor, Runnable then) {
            if (ticks <= 0) { step(then).run(); return; }
            nextTick(() -> { monitor.run(); hold(ticks - 1, monitor, then); });
        }

        /** 下一個 tick 執行 {@code body}（{@link #callback}）。 */
        void nextTick(Runnable body) { c.waitAndRun(1, callback(body)); }

        /**
         * {@code waitAndRun} 的 callback（T5-2 審查 M-7）：失敗時先收尾（{@link #step}），非 GameTestException 包成 GameTestException 並保留 cause——原版只把
         * GameTestException 記為單案失敗，其他例外會讓整個 test server 崩潰；與 {@code M7HomeClosureGameTests.until} 的轉換一致。
         */
        Runnable callback(Runnable body) {
            var guarded = step(body);
            return () -> {
                try { guarded.run(); }
                catch (GameTestException failure) { throw failure; }
                catch (RuntimeException | Error failure) {
                    var wrapped = new GameTestException("M7_HOME_CORRIDOR_CALLBACK：回呼內例外：" + failure);
                    wrapped.initCause(failure);
                    throw wrapped;
                }
            };
        }

        /** T5-2 審查 I-1：Entry=B 的來源票（spec §7 :203）在 B 的 Controller chunk 恰一張，A（Root）世界沒有這個 SID 的來源票。 */
        void assertSourceTicketOnB(UUID sid) {
            var onB = M5GameTestSupport.tickets(b, "quantumchamber_source", sid);
            c.assertTrue(onB.size() == 1 && onB.containsKey(new ChunkPos(frame.controllerPos())),
                    "HOME_SOURCE_TICKET_MISSING_ON_B：Entry=B 的來源票必須恰一張、在 B 的 Controller chunk：" + onB);
            var onRoot = M5GameTestSupport.tickets(a, "quantumchamber_source", sid);
            c.assertTrue(onRoot.isEmpty(), "HOME_SOURCE_TICKET_ON_ROOT：A（Root）世界不得有 HOME 的來源票：" + onRoot);
        }

        /** 收尾之後 B 與 A 都沒有這個 SID 的來源票。 */
        void assertNoSourceTicket(UUID sid) {
            var onB = M5GameTestSupport.tickets(b, "quantumchamber_source", sid);
            var onRoot = M5GameTestSupport.tickets(a, "quantumchamber_source", sid);
            c.assertTrue(onB.isEmpty() && onRoot.isEmpty(), "HOME_SOURCE_TICKET_LEAKED：收尾之後來源票必須釋放：B=" + onB + " A=" + onRoot);
        }

        Runnable timeout(String label) {
            return step(() -> { throw new GameTestException("M7_HOME_CORRIDOR_TIMEOUT " + label + " row=" + homeRow() + " provisional=" + provisionalSid(chamber)); });
        }

        /** 成功路徑的收尾：先確認沒有殘留，再結束 test。 */
        void finish() {
            c.assertTrue(homeRow() == null && provisionalSid(chamber) == null && !gate().globalChamberBusy(server, chamber), "收尾前沒有殘留");
            finishing = true;
            close();
            hooks.release();
            c.complete();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            predicateCleanup.run();
            while (!beforeClose.isEmpty()) {
                try { beforeClose.pop().run(); }
                catch (RuntimeException | Error failure) { LOG.warn("M7_GT_BEFORE_CLOSE 收尾丟出例外", failure); }
            }
            boolean deferred = false;
            try {
                if (settleOnFailure && !finishing) deferred = requestSettle();
                else closeOutResidue();
            } finally {
                if (!deferred) {
                    try { release.run(); }
                    finally { f.close(); }
                }
            }
        }

        /** 延後斷開的最後一步（{@link FailureSettle}）：釋放 B 的測試票、斷開假玩家；各自的例外只記 WARN（在 END_SERVER_TICK 內，不可丟出）。 */
        private void disconnectAfterSettle() {
            try { release.run(); }
            catch (RuntimeException failure) { LOG.warn("M7_GT_FAILURE_SETTLE 釋放 B 的測試票丟出例外：chamber={}", chamber, failure); }
            try { f.close(); }
            catch (RuntimeException failure) { LOG.warn("M7_GT_FAILURE_SETTLE 斷開假玩家丟出例外：chamber={}", chamber, failure); }
        }

        /** 失敗路徑（{@link #settleOnFailure}）：請求正式返還；列還在或艙仍 busy 時排入 {@link FailureSettle}，回傳是否已排入（之後才斷開）。 */
        private boolean requestSettle() {
            try {
                var sid = provisionalSid(chamber);
                if (sid != null) gate().cancelProvisional(server, chamber, sid);
                var row = homeRow();
                if (row != null && (row.state() == SessionState.ARMING || row.state() == SessionState.SUPERPOSITION)) gate().returnToOrigin(b, bController);
                if (homeRow() == null && !gate().globalChamberBusy(server, chamber)) return false;
                // T5-4 Step 0（T5-3 fix2 複審 N-2）：operator 不參與返還，立即斷開；只延後斷開凍結 cohort（與 B 的測試票）。
                try { f.operator.close(); }
                catch (RuntimeException failure) { LOG.warn("M7_GT_FAILURE_SETTLE 斷開 operator 丟出例外：chamber={}", chamber, failure); }
                FailureSettle.defer(this, server.getTicks() + FailureSettle.TICKS);
                LOG.warn("M7_GT_FAILURE_SETTLE chamber={} row={}：失敗路徑等正式返還收尾（最多 {} tick）之後才斷開假玩家", chamber, homeRow(), FailureSettle.TICKS);
                return true;
            } catch (RuntimeException failure) {
                LOG.warn("M7_GT_FAILURE_SETTLE 請求丟出例外，改為立即斷開：chamber={}", chamber, failure);
                return false;
            }
        }

        /** 失敗路徑：取消自己的暫占，未選定的 HOME 列只能請求返還（返還要 recovery tick），留下時記 {@code M7_GT_LEFTOVER_HOME}。 */
        private void closeOutResidue() {
            try {
                var sid = provisionalSid(chamber);
                if (sid != null) gate().cancelProvisional(server, chamber, sid);
                var row = homeRow();
                if (row == null) return;
                if (row.state() == SessionState.ARMING || row.state() == SessionState.SUPERPOSITION) gate().returnToOrigin(b, bController);
                LOG.warn("M7_GT_LEFTOVER_HOME sid={} state={}：test 結束時未選定的 HOME 列仍在（需要 recovery tick 才能返還）", row.sessionUuid(), row.state());
            } catch (RuntimeException failure) {
                LOG.warn("M7_GT_LEFTOVER_HOME 收尾丟出例外", failure);
            }
        }
    }

    /**
     * T5-3 fix2（品質 M-8 第 2 點）：失敗路徑延後斷開的 {@link HomeEntry}（只在 server thread）。test 失敗之後 TestContext 不再排程，所以改由 testmod 的 END_SERVER_TICK 每 tick
     * 核對：正式返還已收尾（列移除、艙不再 busy）或逾時，才釋放 B 的測試票並斷開假玩家；逾時仍在時記 {@code M7_GT_LEFTOVER_HOME}。只讀 journal 與 gateway，不改 production 狀態。
     */
    private static final class FailureSettle {
        static final int TICKS = 1200;
        private static final Map<HomeEntry, Integer> WAITING = new LinkedHashMap<>();
        private static boolean registered;

        static void defer(HomeEntry entry, int deadline) {
            if (!registered) {
                registered = true;
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(FailureSettle::tick);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(FailureSettle::stopping);
            }
            WAITING.put(entry, deadline);
        }

        /** T5-4 Step 0（T5-3 fix2 複審 N-2）：停服時清空這個 server 的等待（static，否則跨 server instance 留下），仍未收尾的記 {@code M7_GT_LEFTOVER_HOME} 並斷開。 */
        private static void stopping(MinecraftServer owner) {
            for (var waiting : List.copyOf(WAITING.entrySet())) {
                var entry = waiting.getKey();
                if (entry.server != owner) continue;
                WAITING.remove(entry);
                LOG.warn("M7_GT_LEFTOVER_HOME chamber={} row={}：停服時失敗路徑的正式返還仍未收尾，斷開假玩家", entry.chamber, safeRow(entry));
                entry.disconnectAfterSettle();
            }
        }

        private static void tick(MinecraftServer owner) {
            for (var waiting : List.copyOf(WAITING.entrySet())) {
                var entry = waiting.getKey();
                if (entry.server != owner) continue;
                boolean settled;
                try { settled = entry.homeRow() == null && !gate().globalChamberBusy(owner, entry.chamber); }
                catch (RuntimeException unreadable) { settled = false; }
                if (!settled && owner.getTicks() < waiting.getValue()) continue;
                WAITING.remove(entry);
                if (settled) LOG.info("M7_GT_FAILURE_SETTLED chamber={}：失敗路徑的正式返還已收尾，斷開假玩家", entry.chamber);
                else LOG.warn("M7_GT_LEFTOVER_HOME chamber={} row={}：失敗路徑等 {} tick 仍未收尾，斷開假玩家", entry.chamber, safeRow(entry), TICKS);
                entry.disconnectAfterSettle();
            }
        }

        private static Object safeRow(HomeEntry entry) {
            try { return entry.homeRow(); }
            catch (RuntimeException unreadable) { return unreadable.toString(); }
        }
    }
}
