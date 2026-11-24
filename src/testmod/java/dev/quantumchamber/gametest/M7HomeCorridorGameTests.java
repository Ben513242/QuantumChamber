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
            sessions.put(chamber, bogus);
            hooks.add(() -> sessions.remove(chamber, bogus));
            int start = c.getWorld().getServer().getTicks();
            c.waitAndRun(5, () -> {
                try {
                    var warned = capture.matching(Level.WARN, "HOME 的 tick 段（tickHomeRuntimes）丟出意外例外");
                    c.assertTrue(c.getWorld().getServer().getTicks() >= start + 5, "server 在 HOME 段例外之後照常推進");
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
            M7HomeClosureGameTests.until(c, label + "/superposition", 400, () -> {
                var row = homeRow();
                if (row == null || row.state() != SessionState.SUPERPOSITION || !row.equals(journal.allSessions().get(row.sessionUuid()))
                        || !pages.hasReservation(row.sessionUuid()) || pages.currentMappings(row.sessionUuid()).epoch() < 1) return false;
                for (var cell : overlayCells(row.sessionUuid())) if (!corridor().getBlockState(cell).isAir()) return false;
                return true;
            }, step(() -> then.accept(homeRow())), timeout);
        }

        /**
         * T5-3 Step 0（品質 M-1）：把這座艙 START_CHECK 票的 backend 換成第一次原生移除丟出例外的代理（加票、FULL 判斷與之後的移除都照原生）。回傳丟出次數的計數格。
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
            close();
            hooks.release();
            c.complete();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            predicateCleanup.run();
            try { closeOutResidue(); }
            finally {
                try { release.run(); }
                finally { f.close(); }
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
}
