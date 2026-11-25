package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.HomeDoorLayout;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.HomeSessionRecord.Unselected;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * M7 T5-4：回程走廊唯一的 HOME 選擇與保留式返回 B（plan Task 5 :321、:323、:325；spec §4.2、§6、§7、§8 :207、§9.1、§10；V05、V08）。全部是 Windows 案例
 * （入場需要原生玩家 checkpoint）；每案一個 batch、一座新的 A 與一個新的 B 宇宙（{@link M7HomeCorridorGameTests.HomeEntry}）。點門一律走真的
 * {@code QuantumBulkheadBlock.onUse}；A 的 SELECT_CHECK 票要延後 FULL 時，以 testmod 反射把票的 backend 換成可控的代理（C6，收尾隨票釋放）。
 * 選定之後的列（MEASURED／COLLAPSING）在返還與清理之後仍保留、持鎖，收尾以原艙停止 latch 交回程終局 owner 結案移除（比照 {@code M7HomeClosureGameTests}）。
 */
public final class M7HomeSelectGameTests implements FabricGameTest {
    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    // ---- 選定與保留式返還 ----

    /**
     * 真互動點門：借 SELECT_CHECK 票，FULL 之後 checked MEASURED＋HOME（選門者、DoorKey、時間），同一個 tick 釋放 SELECT_CHECK 票。之後選後藥效失效不撤銷；全員依保留式流程
     * 回 exact B、記 Returned、清走廊、HOME checkpoint（範圍不含 A）、清 lease 成 DORMANT：艙仍持鎖、玩家不再被占用、runtime 與 B 來源票釋放。RED（stub）：{@code HOME_NOT_SELECTED}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_commit_windows", tickLimit = 2400)
    public void home_select_commits_measured_and_returns_cohort_to_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "home_select_commits_measured_and_returns_cohort_to_b";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var overworld = M5GameTestSupport.installChunkCheckpointProbe(c, e.a.getRegistryKey(), 0, ignored -> "", hooks);
                var commit = watchSelectCommit(e, sid);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var measured = e.homeRow();
                    c.assertTrue(measured.state() == SessionState.MEASURED && measured.home().selection() instanceof Selected s
                            && s.doorKey().equals(HomeDoorLayout.key(sid)) && s.selectedBy().equals(player.getUuid()) && s.selectedAtGameTime() >= 0
                            && measured.spaceLeases().equals(row.spaceLeases()) && measured.participants().equals(row.participants()),
                            "checked MEASURED＋HOME：選門者、固定 DoorKey、租約與 cohort 不變：" + measured.home().selection());
                    c.assertTrue(commit[0] >= 0 && commit[1] == 0,
                            "SELECT_CHECK_NOT_RELEASED：checked MEASURED 的同一個 tick 必須釋放 SELECT_CHECK 票：commitTick=" + commit[0] + " refs=" + commit[1]);
                    // plan :323：已選後藥效失效不撤銷。
                    player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                    var selection = measured.home().selection();
                    awaitDormant(e, sid, label, () -> {
                        var dormant = e.homeRow();
                        c.assertTrue(dormant.home().selection().equals(selection), "MILK_REVOKED_SELECTION：選後藥效失效不撤銷選擇：" + dormant.home().selection());
                        c.assertTrue(e.inB(player), "保留式返還回到 exact B");
                        c.assertTrue(overworld.calls().isEmpty(), "HOME checkpoint 的範圍不含 A（T5-2 規格 M-4）：" + overworld.calls());
                        c.assertTrue(e.journal.participantsAvailable(List.of(player.getUuid())), "DORMANT_OCCUPIES_PLAYER：MEASURED DORMANT 不占玩家");
                        c.assertTrue(gate().globalChamberBusy(e.server, e.chamber), "DORMANT_RELEASED_LOCK：MEASURED DORMANT 仍持鎖");
                        e.assertNoSourceTicket(sid);
                        closeOut(e, sid, label, e::finish);
                    });
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：合法的 HOME 門互動在 300 tick 內沒有 checked MEASURED：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * 審查焦點 2（plan :88）：SELECT_CHECK 延後 FULL 期間，另一人走到下一頁觸發換頁，同一個 DoorKey 換到新的 generation、新的實體位置（舊實例退休）。之後 FULL：原請求的
     * owner／epoch 已不是 current，零 selection，只釋放自己的 SELECT_CHECK 票（B 來源票不受影響）。最後在新 generation 的門合法點門才選定。RED（stub）：{@code HOME_NOT_SELECTED}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_generation_windows", tickLimit = 4000)
    public void same_key_new_generation_or_player_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "same_key_new_generation_or_player_never_selects";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var selector = e.player(0);
                var walker = e.player(1);
                var oldView = e.pages.currentMappings(sid).instances().getFirst();
                var oldSlots = Set.of(oldView.ref().slotId());
                e.walkHome(sid, selector, 11.5);
                requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                var open = gateSelectTicket(e, sid);
                e.walkHome(sid, walker, 96.5);
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, oldSlots), e.step(() -> {
                    e.pregenerateNewLeases(sid, oldSlots, label);
                    M7HomeClosureGameTests.until(c, label + "/remapped", 600, () -> e.remappedOnce(sid, oldView), e.step(() -> {
                        open[0] = true;
                        e.hold(3, () -> { }, () -> {
                            c.assertTrue(!selected(e, sid) && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0,
                                    "STALE_GENERATION_SELECTED：同 key 換到新 generation 之後，舊請求不得選定、只釋放自己的票：row=" + e.homeRow() + " lease=" + hasSelectLease(e, sid));
                            e.assertSourceTicketOnB(sid);
                            requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                            M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                                c.assertTrue(((Selected) e.homeRow().home().selection()).selectedBy().equals(selector.getUuid()), "新 generation 的門由選門者選定");
                                awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish));
                            }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：新 generation 的合法點門沒有選定：row=" + e.homeRow()); }));
                        });
                    }), e.step(() -> { throw new GameTestException("HOME_REMAP_NOT_COMPLETED：" + e.spaceDiagnostics(sid) + " row=" + e.homeRow()); }));
                }), e.timeout(label + "/union"));
            });
        });
    }

    /**
     * plan :325「延遲中變更」：SELECT_CHECK 延後 FULL 期間玩家走離門（超出互動距離），FULL 後零 selection、只釋票；之後回到門前再點，FULL 的同一個 tick 凍結 cohort 的
     * QuantumState 已失效、gateway 尚未 tick 取消：仍零 selection，之後整組未選定返回 B。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_stale_windows", tickLimit = 2400)
    public void stale_select_requests_never_commit(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "stale_select_requests_never_commit";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var open = gateSelectTicket(e, sid);
                e.walkHome(sid, player, 40.5);
                open[0] = true;
                e.hold(3, () -> { }, () -> {
                    c.assertTrue(!selected(e, sid) && !hasSelectLease(e, sid), "STALE_SELECT_COMMITTED：走離門之後不得選定、只釋票：row=" + e.homeRow());
                    e.walkHome(sid, player, 11.5);
                    requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                    player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                    e.hold(3, () -> {
                        if (selected(e, sid)) throw new GameTestException("STALE_SELECT_COMMITTED：FULL 當下 cohort 藥效已失效（gateway 尚未取消）不得選定：row=" + e.homeRow());
                    }, () -> e.awaitReleased(sid, label + "/lapsed", null, () -> {
                        c.assertTrue(e.inB(player), "藥效失效之後整組未選定返回 exact B");
                        e.finish();
                    }));
                });
            });
        });
    }

    /** plan :325「A FULL 入隊後轉 LOW」：SELECT_CHECK FULL 的同一個 tick A 斷電，零 selection、只釋票；恢復供電後再點才選定。殺「跳過 A 二次電位」。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_root_low_windows", tickLimit = 2400)
    public void root_low_after_full_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "root_low_after_full_never_selects";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var open = gateSelectTicket(e, sid);
                e.f.power();
                c.assertTrue(!e.a.isReceivingRedstonePower(e.frame.controllerPos()), "前提：A 已斷電（LOW）");
                open[0] = true;
                e.hold(3, () -> { }, () -> {
                    c.assertTrue(!selected(e, sid) && !hasSelectLease(e, sid), "ROOT_LOW_SELECTED：A FULL 之後轉 LOW 不得選定：row=" + e.homeRow());
                    e.f.power();
                    c.assertTrue(e.a.isReceivingRedstonePower(e.frame.controllerPos()), "A 恢復供電");
                    requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                    M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid),
                            e.step(() -> awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish))),
                            e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：A 恢復供電之後的合法點門沒有選定：row=" + e.homeRow()); }));
                });
            });
        });
    }

    /** spec §10 :311：SELECT_CHECK 未 FULL 時 {@code /reload}：票與請求都釋放、零 selection；之後立即可以重新點門並選定。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_reload_windows", tickLimit = 2400)
    public void select_check_released_on_reload(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "select_check_released_on_reload";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                gateSelectTicket(e, sid);
                e.nextTick(() -> {
                    if (!(e.server.getResourceManager() instanceof net.minecraft.resource.LifecycledResourceManager resources))
                        throw new GameTestException("RELOAD_FIXTURE：resource manager 不是 LifecycledResourceManager");
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.START_DATA_PACK_RELOAD.invoker().startDataPackReload(e.server, resources);
                    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().endDataPackReload(e.server, resources, true);
                    c.assertTrue(!hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0 && !selected(e, sid), "RELOAD_KEPT_SELECT_CHECK：/reload 必須釋放 SELECT_CHECK 票");
                    var again = click(e, player, doorCell(e, sid));
                    c.assertTrue(hasSelectLease(e, sid), "RELOAD_KEPT_SELECT_REQUEST：/reload 之後的新點門必須立即受理（舊請求已取消）：" + again);
                    M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid),
                            e.step(() -> awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish))),
                            e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：/reload 之後的合法點門沒有選定：row=" + e.homeRow()); }));
                });
            });
        });
    }

    /** plan :325「旁觀」：SELECT_CHECK FULL 的同一個 tick 選門者切成旁觀：零 selection（之後 gateway 依 cohort 失效整組返回 B）。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_spectator_windows", tickLimit = 2400)
    public void spectator_selector_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "spectator_selector_never_selects";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var open = gateSelectTicket(e, sid);
                hooks.add(() -> player.changeGameMode(GameMode.SURVIVAL));
                player.changeGameMode(GameMode.SPECTATOR);
                open[0] = true;
                e.hold(3, () -> {
                    if (selected(e, sid)) throw new GameTestException("SPECTATOR_SELECTED：旁觀的選門者不得選定：row=" + e.homeRow());
                }, () -> {
                    player.changeGameMode(GameMode.SURVIVAL);
                    e.awaitReleased(sid, label + "/returned", null, () -> {
                        c.assertTrue(e.inB(player), "整組未選定返回 exact B");
                        e.finish();
                    });
                });
            });
        });
    }

    /** spec §4.2 :103：兩人同一個 tick 點門，只有一個 exact HOME（先受理者），另一人取不到票；收據不能改選門者。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_two_players_windows", tickLimit = 2400)
    public void two_players_same_tick_select_once(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "two_players_same_tick_select_once";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var first = e.player(0);
                var second = e.player(1);
                e.walkHome(sid, first, 10.5);
                e.walkHome(sid, second, 12.5);
                var door = doorCell(e, sid);
                requireAccepted(e, sid, click(e, first, door));
                var other = click(e, second, door);
                c.assertTrue(other != ActionResult.SUCCESS, "第二人同 tick 點門不受理：" + other);
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var once = e.homeRow();
                    c.assertTrue(((Selected) once.home().selection()).selectedBy().equals(first.getUuid()), "SELECTED_TWICE：只有先受理者選定：" + once.home().selection());
                    var late = click(e, second, door);
                    c.assertTrue(late != ActionResult.SUCCESS && !hasSelectLease(e, sid) && once.equals(e.homeRow()), "已選定之後再點門不改收據：" + late);
                    awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish));
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：兩人同 tick 點門沒有選定：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * plan :319「一般門不產生候選」、spec §4.2 :97：HOME Space 的門與牆格都不定位成候選門（{@code candidateDoor} 為 empty），候選互動不接手、不發布候選；
     * 整趟（入場到返回 B）不消耗 entropy、不追加 discovery（兩個檔前後逐位元相同，T5-2 規格 M-7）。RED：{@code HOME_DOOR_LOCATED_AS_CANDIDATE}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_walls_windows", tickLimit = 2000)
    public void home_corridor_walls_never_produce_candidates(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var root = e.server.getSavePath(WorldSavePath.ROOT).resolve("data");
            var entropy = bytes(root.resolve("quantumchamber_candidate_entropy.dat"));
            var discovery = bytes(root.resolve(dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME));
            e.requireStarted();
            var label = "home_corridor_walls_never_produce_candidates";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var view = e.pages.currentMappings(sid).instances().getFirst();
                var box = view.bounds();
                var located = new ArrayList<BlockPos>();
                for (var pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()))
                    if (e.pages.candidateDoor(e.corridor(), pos).isPresent()) located.add(pos.toImmutable());
                c.assertTrue(located.isEmpty(), "HOME_DOOR_LOCATED_AS_CANDIDATE：HOME Space 的門與牆格不得定位成候選門：count=" + located.size() + " " + located.stream().limit(3).toList());
                var use = new dev.quantumchamber.candidate.CandidateDoorInteraction().onBulkheadUse(e.corridor(), doorCell(e, sid), player);
                c.assertTrue(use.isEmpty(), "HOME 門不交候選互動：" + use);
                c.assertTrue(e.pages.selectableDoors(sid).isEmpty(), "HOME_CANDIDATE_PUBLISHED：HOME Space 不發布候選門");
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController), "正式取消（返回 B）開始");
                e.awaitReleased(sid, label + "/cancel", null, () -> {
                    c.assertTrue(Arrays.equals(entropy, bytes(root.resolve("quantumchamber_candidate_entropy.dat")))
                            && Arrays.equals(discovery, bytes(root.resolve(dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME))),
                            "HOME_CONSUMED_ENTROPY：HOME 旅程不得消耗 entropy、不得追加 discovery");
                    e.finish();
                });
            });
        });
    }

    /**
     * T5-3 規格 M-5、品質 M-8 第 1 點（使用者 2026-11-25「新走廊建造中不受理（建議）」）：換頁提交之後、舊實例退休完成之前（以一個掉落物壓住舊租約的退休），點舊實例的 HOME 門
     * 不取票、不選定；點 current 的門照常選定。選定之後退休停止，保留式清理一次清掉含退休中舊實例的全部 lease（掉落物隨返還送回 B），沒有永久停住。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_retiring_windows", tickLimit = 4000)
    public void retiring_old_home_door_never_selects_and_selection_cleans_all_leases(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "retiring_old_home_door_never_selects_and_selection_cleans_all_leases";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var selector = e.player(0);
                var walker = e.player(1);
                var oldView = e.pages.currentMappings(sid).instances().getFirst();
                int oldSlot = oldView.ref().slotId();
                e.walkHome(sid, selector, 11.5);
                var oldDoor = doorCell(e, sid);
                e.walkHome(sid, walker, 96.5);
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, Set.of(oldSlot)), e.step(() -> {
                    e.pregenerateNewLeases(sid, Set.of(oldSlot), label);
                    M7HomeClosureGameTests.until(c, label + "/committed", 600, () -> e.remapCommittedBeforeRetirement(sid, oldSlot), e.step(() -> {
                        var center = Vec3d.ofBottomCenter(oldView.bounds().getCenter());
                        var pin = new ItemEntity(e.corridor(), center.x, oldView.bounds().getMinY() + 1.0, center.z, new ItemStack(Items.STICK));
                        pin.setVelocity(Vec3d.ZERO);
                        c.assertTrue(e.corridor().spawnEntity(pin), "前提：掉落物放進退休中的舊實例（壓住舊租約的退休）");
                        var stale = click(e, selector, oldDoor);
                        c.assertTrue(stale != ActionResult.SUCCESS && !hasSelectLease(e, sid), "RETIRING_DOOR_ACCEPTED：退休中舊實例的 HOME 門不得受理：" + stale);
                        requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                        M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                            var measured = e.homeRow();
                            c.assertTrue(slots(measured).size() == 2 && slots(measured).contains(oldSlot), "前提：選定時舊租約仍在退休中：" + measured.spaceLeases());
                            awaitDormant(e, sid, label, () -> {
                                c.assertTrue(e.allAir(oldView.bounds()) && e.inB(selector) && e.inB(walker), "保留式清理清掉含退休中舊實例的全部 lease，全員回到 exact B");
                                closeOut(e, sid, label, e::finish);
                            });
                        }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：退休期間 current 門的合法點門沒有選定：row=" + e.homeRow()); }));
                    }), e.step(() -> { throw new GameTestException("HOME_REMAP_NOT_COMMITTED：" + e.spaceDiagnostics(sid) + " row=" + e.homeRow()); }));
                }), e.timeout(label + "/union"));
            });
        });
    }

    /** 使用者 2026-11-25「新走廊建造中不受理（建議）」：新舊租約聯集已 checked、新實例還在建造（pending）時點門不受理；換頁完成之後再點才選定。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_building_windows", tickLimit = 4000)
    public void select_during_new_instance_build_is_refused(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.settleOnFailure();
        e.guard(() -> {
            e.requireStarted();
            var label = "select_during_new_instance_build_is_refused";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var selector = e.player(0);
                var walker = e.player(1);
                var oldView = e.pages.currentMappings(sid).instances().getFirst();
                var oldSlots = Set.of(oldView.ref().slotId());
                e.walkHome(sid, selector, 11.5);
                e.walkHome(sid, walker, 96.5);
                M7HomeClosureGameTests.until(c, label + "/union", 200, () -> e.newLeaseWritten(sid, oldSlots), e.step(() -> {
                    var building = click(e, selector, doorCell(e, sid));
                    c.assertTrue(building != ActionResult.SUCCESS && !hasSelectLease(e, sid) && !selected(e, sid),
                            "SELECT_DURING_BUILD_ACCEPTED：新實例建造中點門不得受理：" + building + " " + e.spaceDiagnostics(sid));
                    e.pregenerateNewLeases(sid, oldSlots, label);
                    M7HomeClosureGameTests.until(c, label + "/remapped", 600, () -> e.remappedOnce(sid, oldView), e.step(() -> {
                        requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                        M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid),
                                e.step(() -> awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish))),
                                e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：換頁完成之後的合法點門沒有選定：row=" + e.homeRow()); }));
                    }), e.step(() -> { throw new GameTestException("HOME_REMAP_NOT_COMPLETED：" + e.spaceDiagnostics(sid) + " row=" + e.homeRow()); }));
                }), e.timeout(label + "/union"));
            });
        });
    }

    /** plan :323「依既有保留式流程回 B」、:325「A 停用前後只回 B」：選定之後、返還途中 A 停用（原艙停止 latch）：只回 exact B、清 lease，之後由回程終局 owner 結案；全程不落在 A。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_root_disabled_windows", tickLimit = 2400)
    public void root_disabled_during_measured_return_still_returns_to_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "root_disabled_during_measured_return_still_returns_to_b";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    c.assertTrue(e.homeRow().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned), "前提：選定之後尚未有人返還");
                    var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(e.server).registry());
                    lifecycle.setEnabled(e.chamber, false);
                    boolean[] inA = {false};
                    M7HomeClosureGameTests.until(c, label + "/closed", 1200, () -> {
                        inA[0] |= player.getServerWorld() == e.a;
                        return e.homeRow() == null && !gate().globalChamberBusy(e.server, e.chamber);
                    }, e.step(() -> {
                        c.assertTrue(!inA[0] && e.inB(player), "RETURNED_TO_A：A 停用之後只回 exact B：world=" + player.getServerWorld().getRegistryKey().getValue());
                        e.finish();
                    }), e.step(() -> { throw new GameTestException("MEASURED_RETURN_NOT_CLOSED：A 停用之後選定列沒有返還、清理與結案：row=" + e.homeRow()); }));
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
            });
        });
    }

    /** C12：MEASURED 以 testmod checked 提交成 COLLAPSING（尚未有人返還），由同一條返還引擎返回 exact B、清 lease（COLLAPSING 的 HOME checkpoint 範圍，T5-2 規格 M-4）。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_collapsing_windows", tickLimit = 2400)
    public void collapsing_row_uses_the_same_return_engine(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "collapsing_row_uses_the_same_return_engine";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var overworld = M5GameTestSupport.installChunkCheckpointProbe(c, e.a.getRegistryKey(), 0, ignored -> "", hooks);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var measured = e.homeRow();
                    c.assertTrue(measured.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !measured.spaceLeases().isEmpty(),
                            "前提：選定之後尚未返還：" + measured);
                    var h = measured.home();
                    var collapsing = new HomeSessionRecord(sid, measured.chamberUuid(), measured.rootAnchor(), measured.entryEndpoint(), measured.participants(),
                            measured.spaceLeases(), SessionState.COLLAPSING, new HomeSessionRecord.Home(h.selection(), e.server.getOverworld().getTime(),
                            h.passageOpenedAtGameTime(), h.passageExpiresAtGameTime(), h.crossed(), h.recoveryRequired(), h.stopReason(), h.stoppedAtGameTime(),
                            h.closeIntent(), h.sealedReason()));
                    c.assertTrue(e.pages.exclusiveOperation(sid, () -> e.journal.commitHomeRestore(e.server, measured, collapsing)).committed(), "testmod checked MEASURED→COLLAPSING");
                    M7HomeClosureGameTests.until(c, label + "/released", 600, () -> {
                        var current = e.homeRow();
                        return current != null && current.state() == SessionState.COLLAPSING && current.spaceLeases().isEmpty() && current.equals(e.journal.allSessions().get(sid))
                                && current.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && !e.pages.hasReservation(sid);
                    }, e.step(() -> {
                        c.assertTrue(e.inB(player) && overworld.calls().isEmpty(), "COLLAPSING 由同一條引擎返回 exact B；checkpoint 範圍不含 A：" + overworld.calls());
                        closeOut(e, sid, label, e::finish);
                    }), e.step(() -> { throw new GameTestException("COLLAPSING_NOT_RETURNED：同一條返還引擎沒有處理 COLLAPSING：row=" + e.homeRow() + " " + e.spaceDiagnostics(sid)); }));
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * 使用者 2026-11-25「照原版，記為已知行為（建議）」：走廊內死亡（SELECT_CHECK 請求還在）：零 selection；照原版重生之後，返還引擎把他送回 exact B、Returned 正確、列移除。
     * 重生點與走廊掉落物消失記為已知行為，不在這裡斷言。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_death_windows", tickLimit = 2400)
    public void corridor_death_returns_to_exact_b_with_returned(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "corridor_death_returns_to_exact_b_with_returned";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var uuid = player.getUuid();
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                gateSelectTicket(e, sid);
                // 原生 damage 在傳送確認之前（假連線不回確認）會被 invulnerable 擋下，所以直接以原版的死亡流程（health 0＋onDeath）。
                player.setHealth(0.0f);
                player.onDeath(player.getDamageSources().genericKill());
                c.assertTrue(!player.isAlive(), "前提：選門者在走廊內死亡");
                e.hold(5, () -> {
                    if (selected(e, sid)) throw new GameTestException("DEAD_SELECTOR_SELECTED：row=" + e.homeRow());
                }, () -> {
                    // 真客戶端的重生（使用者 2026-11-25「恢復，死亡案改用真重生（建議）」）：重生封包 onClientStatus(PERFORM_RESPAWN) 讓連線改指向新的玩家實體；
                    // 之後照真客戶端時序，以既有 confirmTeleport() 讀原生 PlayerPositionLookS2CPacket 的 teleport id 再 ACK（重生一次、返還的傳送之後每 tick 有封包就回）。
                    var connection = e.f.players.get(0);
                    player.networkHandler.onClientStatus(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
                            net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
                    var respawned = e.server.getPlayerManager().getPlayer(uuid);
                    c.assertTrue(respawned != null && respawned != player && respawned.isAlive() && respawned.networkHandler.player == respawned,
                            "前提：真重生之後是新的、活著的玩家實體，連線指向它");
                    hooks.add(() -> { if (e.server.getPlayerManager().getPlayer(uuid) == respawned) e.server.getPlayerManager().remove(respawned); });
                    ack(connection);
                    M7HomeClosureGameTests.until(c, label + "/returned", 600, () -> {
                        ack(connection);
                        return e.homeRow() == null && !gate().globalChamberBusy(e.server, e.chamber);
                    }, e.step(() -> {
                        var now = e.server.getPlayerManager().getPlayer(uuid);
                        c.assertTrue(now == respawned && now.isAlive() && e.inB(now), "DEATH_NOT_RETURNED_TO_B：死亡重生之後最終回到 exact B：world="
                                + (now == null ? null : now.getServerWorld().getRegistryKey().getValue()));
                        e.assertNoSourceTicket(sid);
                        e.finish();
                    }), e.step(() -> {
                        var now = e.server.getPlayerManager().getPlayer(uuid);
                        throw new GameTestException("DEATH_NOT_RETURNED_TO_B：死亡重生之後沒有回到 exact B 收尾：row=" + (e.homeRow() == null ? null : e.homeRow().state())
                                + " world=" + (now == null ? null : now.getServerWorld().getRegistryKey().getValue()) + " pos=" + (now == null ? null : now.getPos())
                                + " teleporting=" + (now != null && now.isInTeleportationState()));
                    }));
                });
            });
        });
    }

    // ---- 支援 ----

    /**
     * Step 0 審查 M-8：以 testmod 反射把 {@code M7HomeProbe} 的 phase 換成只觀察這個 SID 的 {@code after-readback}（{@code M7HomeReceiptWindowMixin} 的既有 cut 點）：
     * 在那個時點以形式上有效的 proof 呼叫 {@code finishHomeReturn}。回傳 {@code [reached, removed, threw]}；收尾（hooks）還原 phase。不丟出例外到正式程式。
     */
    private static boolean[] receiptWindow(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        boolean[] seen = {false, false, false};
        java.lang.reflect.Field field;
        try { field = M7HomeProbe.class.getDeclaredField("active"); field.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new GameTestException("M7HomeProbe.active 找不到：" + failure); }
        M7HomeProbe.Phase phase = new M7HomeProbe.Phase() {
            @Override public void start() { }
            @Override public void reached(String event, UUID session, Object detail) {
                if (!"after-readback".equals(event) || !sid.equals(session)) return;
                seen[0] = true;
                try {
                    if (e.journal.allSessions().get(sid) instanceof HomeSessionRecord row
                            && e.journal.finishHomeReturn(e.server, row, M7CapacityFixture.forgeReturnProof(e.server, row)).removed()) seen[1] = true;
                } catch (RuntimeException unexpected) { seen[2] = true; }
            }
        };
        try {
            if (field.get(null) != null) throw new GameTestException("M7HomeProbe 已有其他 phase，不能借用收據窗口");
            field.set(null, phase);
        } catch (IllegalAccessException failure) { throw new GameTestException("M7HomeProbe.active 不能設定：" + failure); }
        e.hooks.add(() -> {
            try { if (field.get(null) == phase) field.set(null, null); }
            catch (IllegalAccessException failure) { LOG.warn("M7HomeProbe.active 還原失敗", failure); }
        });
        return seen;
    }

    /** 照真客戶端：有原生 {@code PlayerPositionLookS2CPacket} 時以既有 {@code confirmTeleport()} ACK；沒有時回 none。 */
    private static String ack(ConnectedGameTestPlayer connection) {
        try { connection.confirmTeleport(); return "acked"; }
        catch (IllegalStateException none) { return "none"; }
    }

    /** 真互動：以 bulkhead 的 {@code onUse} 點 {@code pos}（走 {@code QuantumBulkheadBlock.onUse} 的 HOME hook）。 */
    static ActionResult click(M7HomeCorridorGameTests.HomeEntry e, ServerPlayerEntity player, BlockPos pos) {
        var state = e.corridor().getBlockState(pos);
        return state.onUse(e.corridor(), player, new BlockHitResult(Vec3d.ofCenter(pos), Direction.WEST, pos, false));
    }

    /** 點門受理：借到 SELECT_CHECK 票（stub 時以 {@code HOME_NOT_SELECTED} 失敗）。 */
    static void requireAccepted(M7HomeCorridorGameTests.HomeEntry e, UUID sid, ActionResult result) {
        e.c.assertTrue(result != ActionResult.SUCCESS && hasSelectLease(e, sid),
                "HOME_NOT_SELECTED：合法點門沒有受理（沒有借 SELECT_CHECK 票）：" + result + " row=" + e.homeRow());
    }

    /** current 實例 HOME 門 25 格的中央一格（實際讀世界的 home_marker）。 */
    static BlockPos doorCell(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var current = e.pages.currentMappings(sid).instances();
        var cells = e.homeMarkers(e.homeRow()).stream().filter(pos -> current.stream().anyMatch(view -> view.bounds().contains(pos)))
                .sorted(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
        e.c.assertTrue(cells.size() == 25, "current 實例恰一扇 25 格 HOME 門：" + cells.size());
        return cells.get(12);
    }

    static boolean selected(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var row = e.homeRow();
        return row != null && row.sessionUuid().equals(sid) && row.home().selection() instanceof Selected && row.equals(e.journal.allSessions().get(sid));
    }

    /** 原艙票 owner 是否持有這個 SID 的票（只讀反射）。 */
    static boolean hasSelectLease(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(HomeRootTickets.class, "servers")).get(e.server);
        return owner != null && ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).containsKey(sid);
    }

    /**
     * 把這個 SID 的 SELECT_CHECK 票換成 FULL 受控的代理（加票與移除照原生；{@code open[0]} 為假時一律未 FULL，為真時「chunk 已載入為 WorldChunk」）。只在點門受理的同一個 callback 內呼叫。
     */
    static boolean[] gateSelectTicket(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(HomeRootTickets.class, "servers")).get(e.server);
        var lease = ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).get(sid);
        e.c.assertTrue(lease != null, "SELECT_CHECK 已取得 A 的票");
        var nativeBackend = M4CandidateTestAccess.get(HomeRootTickets.class, "NATIVE");
        Class<?> type;
        try { type = Class.forName("dev.quantumchamber.collapse.HomeRootTickets$Backend", false, HomeRootTickets.class.getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new GameTestException("HomeRootTickets.Backend 找不到：" + failure); }
        boolean[] open = {false};
        var proxy = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("full")) {
                if (!open[0]) return false;
                var world = (ServerWorld) args[0];
                var pos = (ChunkPos) args[1];
                return world.getChunkManager().getWorldChunk(pos.x, pos.z) != null;
            }
            method.setAccessible(true);
            try { return method.invoke(nativeBackend, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        M4CandidateTestAccess.set(lease, "backend", proxy);
        return open;
    }

    /**
     * 每個 server tick 結束時（testmod 的 END_SERVER_TICK，排在正式 owner 之後）觀察：第一次看到 checked MEASURED＋HOME 的那個 tick，以及當下這個 SID 在 A 世界的原艙票數。
     * 回傳 {@code [commitTick, refs]}（尚未看到時 commitTick=-1）。
     */
    static int[] watchSelectCommit(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        int[] seen = {-1, -1};
        EndTick.watch(e.server, () -> {
            if (seen[0] >= 0) return true;
            if (!selected(e, sid)) return false;
            seen[0] = e.server.getTicks();
            seen[1] = HomeTicketFaultFixture.count(e.a, sid) + (hasSelectLease(e, sid) ? 1000 : 0);
            return true;
        });
        return seen;
    }

    /** 選定列的保留式清理已完成：Selected MEASURED、全員已返還、lease 清空（current＝flushed）、走廊 space 與 HOME runtime 都已釋放。 */
    static void awaitDormant(M7HomeCorridorGameTests.HomeEntry e, UUID sid, String label, Runnable then) {
        M7HomeClosureGameTests.until(e.c, label + "/dormant", 600, () -> {
            var row = e.homeRow();
            return row != null && row.state() == SessionState.MEASURED && row.home().selection() instanceof Selected && row.spaceLeases().isEmpty()
                    && row.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && row.equals(e.journal.allSessions().get(sid))
                    && !e.pages.hasReservation(sid) && M7HomeCorridorGameTests.ownerType(e.chamber) == null;
        }, e.step(then), e.step(() -> {
            throw new GameTestException("MEASURED_NOT_DORMANT：選定之後的保留式返還與清理沒有完成：row=" + e.homeRow() + " owner=" + M7HomeCorridorGameTests.ownerType(e.chamber)
                    + " " + e.spaceDiagnostics(sid));
        }));
    }

    /** 選定列的收尾：原艙停止 checked latch，交回程終局 owner 結案、改 B 門、checkpoint、發布收據並移除；之後才結束 test（比照 {@code M7HomeClosureGameTests.closeOutDormant}）。 */
    static void closeOut(M7HomeCorridorGameTests.HomeEntry e, UUID sid, String label, Runnable then) {
        var row = e.homeRow();
        e.c.assertTrue(row != null && row.home().stopReason().isEmpty(), "收尾前提：選定列仍在、尚未停止：" + row);
        // Step 0 審查 M-8：選定列（DORMANT 或已清理的 COLLAPSING）拿走廊 owner 形式上有效的 proof，未選定返還的移除也必須拒絕、列不變。
        e.c.assertTrue(!e.journal.finishHomeReturn(e.server, row, M7CapacityFixture.forgeReturnProof(e.server, row)).removed() && row.equals(e.homeRow()),
                "SELECTED_ROW_REMOVED_BY_RETURN_PROOF：選定列不能經未選定返還的移除入口移除");
        var stopped = M7HomeClosureGameTests.withStop(row, HomeSessionRecord.RootReason.ROOT_DISABLED, e.server.getOverworld().getTime());
        e.c.assertTrue(e.pages.exclusiveOperation(sid, () -> e.journal.commitHomeRollForward(e.server, row, stopped)).committed(), "收尾：原艙停止 checked latch");
        // Step 0 審查 M-8（controller：用既有 M7HomeReceiptWindowMixin 的窗口）：回程終局 owner 發布收據、exact 讀回之後、熱列移除之前（after-readback），
        // 拿未選定返還的 proof 移除 CLOSE 列必須拒絕（否則本體會依已發布的收據移除列）。
        var window = receiptWindow(e, sid);
        M7HomeClosureGameTests.until(e.c, label + "/closed", 600, () -> e.homeRow() == null && !gate().globalChamberBusy(e.server, e.chamber), e.step(() -> {
            e.c.assertTrue(window[0] && !window[1] && !window[2],
                    "CLOSE_ROW_REMOVED_BY_RETURN_PROOF：結案列在收據已發布、熱列還在的窗口不能經未選定返還的移除入口移除：reached=" + window[0] + " removed=" + window[1] + " threw=" + window[2]);
            then.run();
        }), e.step(() -> { throw new GameTestException("SELECTED_ROW_NOT_CLOSED：row=" + e.homeRow()); }));
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod");

    private static Set<Integer> slots(HomeSessionRecord row) {
        var slots = new TreeSet<Integer>();
        row.spaceLeases().forEach(lease -> slots.add(lease.slotId()));
        return slots;
    }

    private static byte[] bytes(java.nio.file.Path path) {
        try { return java.nio.file.Files.exists(path) ? java.nio.file.Files.readAllBytes(path) : new byte[0]; }
        catch (java.io.IOException failure) { throw new GameTestException("讀檔失敗：" + path + "：" + failure); }
    }

    /** testmod 的 END_SERVER_TICK 觀察者（lazy 註冊，排在正式 owner 之後）；回呼回真即移除；停服時清空。只在 server thread。 */
    private static final class EndTick {
        private static final List<Map.Entry<MinecraftServer, BooleanSupplier>> WATCHING = new ArrayList<>();
        private static boolean registered;

        static void watch(MinecraftServer server, BooleanSupplier done) {
            if (!registered) {
                registered = true;
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(EndTick::tick);
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(owner -> WATCHING.removeIf(entry -> entry.getKey() == owner));
            }
            WATCHING.add(Map.entry(server, done));
        }

        private static void tick(MinecraftServer owner) {
            for (var entry : List.copyOf(WATCHING)) {
                if (entry.getKey() != owner) continue;
                boolean finished;
                try { finished = entry.getValue().getAsBoolean(); }
                catch (RuntimeException unreadable) { finished = true; }
                if (finished) WATCHING.remove(entry);
            }
        }
    }
}
