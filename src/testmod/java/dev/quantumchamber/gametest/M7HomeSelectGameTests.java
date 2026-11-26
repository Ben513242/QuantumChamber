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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
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
import org.apache.logging.log4j.Level;

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
     * T5-4 fix1：整趟（選定、返還、清 lease）的 journal 寫出都在該 SID 的 session operation guard 內（規格 M-4，spec §9.1 :249）；有真正選定的整趟不消耗 entropy、
     * 不追加 discovery，legacy 列（M4 candidate ledger 所在）前後不變（規格 M-8，spec §4.2 :97）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_commit_windows", tickLimit = 2400)
    public void home_select_commits_measured_and_returns_cohort_to_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var root = e.server.getSavePath(WorldSavePath.ROOT).resolve("data");
            var entropy = bytes(root.resolve("quantumchamber_candidate_entropy.dat"));
            var discovery = bytes(root.resolve(dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME));
            var legacy = e.journal.records();
            e.requireStarted();
            var label = "home_select_commits_measured_and_returns_cohort_to_b";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var overworld = M5GameTestSupport.installChunkCheckpointProbe(c, e.a.getRegistryKey(), 0, ignored -> "", hooks);
                var guardWrites = e.guardWitness(sid);
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
                        var writes = List.copyOf(guardWrites);
                        c.assertTrue(!writes.isEmpty() && writes.stream().allMatch(write -> write.endsWith(" guard=true"))
                                        && writes.stream().anyMatch(write -> write.equals("state:MEASURED guard=true"))
                                        && writes.stream().anyMatch(write -> write.equals("returned guard=true"))
                                        && writes.stream().anyMatch(write -> write.matches("leases:\\[[0-9, ]+\\]->\\[\\] guard=true")),
                                "HOME_WRITE_OUTSIDE_GUARD：選定、返還進度與清 lease 的 journal 寫出都必須在 session operation guard 內：" + writes);
                        c.assertTrue(Arrays.equals(entropy, bytes(root.resolve("quantumchamber_candidate_entropy.dat")))
                                        && Arrays.equals(discovery, bytes(root.resolve(dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME)))
                                        && e.journal.records().equals(legacy),
                                "HOME_SELECTION_CONSUMED_CANDIDATES：有真正選定的 HOME 旅程不得消耗 entropy、追加 discovery 或變更 legacy 列（M4 candidate ledger）");
                        closeOut(e, sid, label, e::finish);
                    });
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：合法的 HOME 門互動在 300 tick 內沒有 checked MEASURED：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * 審查焦點 2（plan :88）兩半都在這一案：
     * <ol>
     * <li>generation：SELECT_CHECK 延後 FULL 期間，另一人走到下一頁觸發換頁，同一個 DoorKey 換到新的 generation、新的實體位置（舊實例退休）。之後 FULL：原請求的
     *     owner／epoch 已不是 current，零 selection，只釋放自己的 SELECT_CHECK 票（B 來源票不受影響）。</li>
     * <li>原玩家實體被替換（T5-4 fix1，規格 I-1、V05「玩家換 instance」）：在新 generation 的門再點一次，延後 FULL 期間以原生 {@code respawnPlayer(player, true, …)}
     *     把同一個 UUID 換成新的 {@code ServerPlayerEntity}（存活複製、含 QuantumState），連線改指向它，再以原生移動送回原位置；PlayerManager 與走廊世界的 entity
     *     都一致指向新實體，gateway 不取消（{@code homeCohortHasBuff} 仍為真）。FULL 時只有「原 instance」的比對擋得住：零 selection、只釋自己的票。</li>
     * </ol>
     * 最後由新實體在新 generation 的門合法點門才選定。RED（stub）：{@code HOME_NOT_SELECTED}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_generation_windows", tickLimit = 4000)
    public void same_key_new_generation_or_player_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
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
                        open.open();
                        e.hold(3, () -> { }, () -> {
                            c.assertTrue(!selected(e, sid) && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0,
                                    "STALE_GENERATION_SELECTED：同 key 換到新 generation 之後，舊請求不得選定、只釋放自己的票：row=" + e.homeRow() + " lease=" + hasSelectLease(e, sid));
                            e.assertSourceTicketOnB(sid);
                            // 後半：原玩家實體被替換（規格 I-1）。
                            requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                            var again = gateSelectTicket(e, sid);
                            awaitGateReady(e, again, label + "/replace", () -> {
                                var uuid = selector.getUuid();
                                var owner = e.pages.homePlayerOwner(sid, selector);
                                var pose = selector.getPos();
                                var replaced = replacePlayer(e, selector);
                                hooks.add(() -> { if (e.server.getPlayerManager().getPlayer(uuid) == replaced) e.server.getPlayerManager().remove(replaced); });
                                c.assertTrue(replaced != selector && selector.isRemoved() && e.server.getPlayerManager().getPlayer(uuid) == replaced
                                                && e.corridor().getEntity(uuid) == replaced && replaced.networkHandler.player == replaced && replaced.isAlive()
                                                && !replaced.isSpectator() && replaced.hasStatusEffect(ModEffects.QUANTUM_STATE)
                                                && replaced.getPos().squaredDistanceTo(pose) < 1e-12 && e.pages.homePlayerOwner(sid, replaced).equals(owner) && owner.isPresent()
                                                && e.pages.homeCohortHasBuff(sid),
                                        "前提：同一個 UUID 已換成新的、活著的實體（PlayerManager、走廊 entity 與連線都指向它），在原位置、原 current 實例、有 QuantumState，gateway 尚未取消");
                                var requester = selectRequestPlayer(e);
                                c.assertTrue(uuid.equals(requester), "前提：原請求仍在（記的是原 instance）：" + requester);
                                again.open();
                                e.hold(3, () -> {
                                    if (selected(e, sid)) throw new GameTestException("REPLACED_PLAYER_SELECTED：原玩家實體被替換之後，舊請求不得選定：row=" + e.homeRow());
                                }, () -> {
                                    var now = e.homeRow();
                                    c.assertTrue(again.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0,
                                            "REPLACED_PLAYER_SELECTED：FULL 時原 instance 已被替換，零 selection、只釋放自己的票：full=" + again.fullTrue + " row=" + now);
                                    c.assertTrue(now != null && now.state() == SessionState.SUPERPOSITION && e.pages.homeCohortHasBuff(sid),
                                            "前提：替換之後 gateway 沒有取消（只有原 instance 的比對擋下）：row=" + now);
                                    e.assertSourceTicketOnB(sid);
                                    ack(e.f.players.get(0));
                                    requireAccepted(e, sid, click(e, replaced, doorCell(e, sid)));
                                    M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                                        c.assertTrue(((Selected) e.homeRow().home().selection()).selectedBy().equals(uuid), "新 generation 的門由選門者（新實體）選定");
                                        awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish));
                                    }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：新 generation 的合法點門沒有選定：row=" + e.homeRow()); }));
                                });
                            });
                        });
                    }), e.step(() -> { throw new GameTestException("HOME_REMAP_NOT_COMPLETED：" + e.spaceDiagnostics(sid) + " row=" + e.homeRow()); }));
                }), e.timeout(label + "/union"));
            });
        });
    }

    /**
     * plan :325「延遲中變更」、spec §4.2 :101～:102、§7 :198：一個 fixture 內依序做下列延後 FULL 期間的變更，各自零 selection、只釋票（每段都先讓 A 的 chunk 就緒，
     * 再在同一個 tick 改狀態並開閘，所以 FULL 一定發生在下一個 END tick；{@code fullTrue} 是見證）：
     * <ol>
     * <li>玩家走離門（超出互動距離）；</li>
     * <li>B 斷電（T5-4 fix1，規格 I-2）：gateway 在 SUPERPOSITION 不核 B 供電，只有 FULL 時的 {@code entryPowered} 擋得住；</li>
     * <li>B 開門（封艙失效，規格 I-2）：同上，只有 FULL 時的封艙判定擋得住；</li>
     * <li>SELECT_CHECK 200 tick 逾時（規格 I-2，spec §7 :198）：票一直沒有 FULL，200 tick 後票與請求都釋放、零 selection，之後合法點門可以再受理；</li>
     * <li>凍結 cohort 的 QuantumState 已失效、gateway 尚未 tick 取消（品質 M-7：同 tick 改狀態並開閘）：仍零 selection，之後整組未選定返回 B。</li>
     * </ol>
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_stale_windows", tickLimit = 3000)
    public void stale_select_requests_never_commit(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "stale_select_requests_never_commit";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var power = e.frame.controllerPos().offset(e.frame.outwardFacing());
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var away = gateSelectTicket(e, sid);
                awaitGateReady(e, away, label + "/away", () -> {
                    e.walkHome(sid, player, 40.5);
                    away.open();
                    e.hold(3, () -> { }, () -> {
                        c.assertTrue(away.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid), "STALE_SELECT_COMMITTED：走離門之後不得選定、只釋票：full=" + away.fullTrue + " row=" + e.homeRow());
                        e.walkHome(sid, player, 11.5);
                        requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                        var unpowered = gateSelectTicket(e, sid);
                        awaitGateReady(e, unpowered, label + "/b-unpowered", () -> {
                            ChamberProtectionService.get().authorizedMutation(() -> e.b.setBlockState(power, Blocks.AIR.getDefaultState(), 3));
                            c.assertTrue(!e.b.isReceivingRedstonePower(e.frame.controllerPos()), "前提：延後期間 B 斷電");
                            unpowered.open();
                            e.hold(3, () -> {
                                if (selected(e, sid)) throw new GameTestException("B_UNPOWERED_SELECTED：FULL 時 B 已斷電不得選定：row=" + e.homeRow());
                            }, () -> {
                                c.assertTrue(unpowered.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid) && e.homeRow().state() == SessionState.SUPERPOSITION,
                                        "B_UNPOWERED_SELECTED：FULL 時 B 已斷電，零 selection、只釋票，gateway 不取消：full=" + unpowered.fullTrue + " row=" + e.homeRow());
                                ChamberProtectionService.get().authorizedMutation(() -> e.b.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState(), 3));
                                c.assertTrue(e.b.isReceivingRedstonePower(e.frame.controllerPos()), "B 恢復供電");
                                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                                var unsealed = gateSelectTicket(e, sid);
                                awaitGateReady(e, unsealed, label + "/b-unsealed", () -> {
                                    c.assertTrue(new ChamberDoorService().applyTarget(e.b, e.frame, ChamberDoorService.DoorState.OPEN).succeeded(), "前提：延後期間 B 開門（封艙失效）");
                                    unsealed.open();
                                    e.hold(3, () -> {
                                        if (selected(e, sid)) throw new GameTestException("B_UNSEALED_SELECTED：FULL 時 B 已開門不得選定：row=" + e.homeRow());
                                    }, () -> {
                                        c.assertTrue(unsealed.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid) && e.homeRow().state() == SessionState.SUPERPOSITION,
                                                "B_UNSEALED_SELECTED：FULL 時 B 已開門，零 selection、只釋票，gateway 不取消：full=" + unsealed.fullTrue + " row=" + e.homeRow());
                                        c.assertTrue(new ChamberDoorService().applyTarget(e.b, e.frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 門關回（封艙）");
                                        requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                                        gateSelectTicket(e, sid);
                                        int accepted = e.server.getTicks();
                                        M7HomeClosureGameTests.until(c, label + "/timeout", 260, () -> e.server.getTicks() - accepted >= 203, e.step(() -> {
                                            c.assertTrue(selectRequestPlayer(e) == null && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0 && !selected(e, sid),
                                                    "SELECT_CHECK_TIMEOUT_KEPT：SELECT_CHECK 200 tick 沒有 FULL，票與請求都必須釋放、零 selection：request=" + selectRequestPlayer(e)
                                                            + " lease=" + hasSelectLease(e, sid) + " row=" + e.homeRow());
                                            requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                                            var lapse = gateSelectTicket(e, sid);
                                            awaitGateReady(e, lapse, label + "/lapse", () -> {
                                                player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                                                lapse.open();
                                                e.hold(3, () -> {
                                                    if (selected(e, sid)) throw new GameTestException("STALE_SELECT_COMMITTED：FULL 當下 cohort 藥效已失效（gateway 尚未取消）不得選定：row=" + e.homeRow());
                                                }, () -> {
                                                    c.assertTrue(lapse.fullTrue >= 1, "FULL_NOT_REACHED：藥效失效的同一個 tick 開閘，FULL 必須發生在 gateway 取消之前：full=" + lapse.fullTrue);
                                                    e.awaitReleased(sid, label + "/lapsed", null, () -> {
                                                        c.assertTrue(e.inB(player), "藥效失效之後整組未選定返回 exact B");
                                                        e.finish();
                                                    });
                                                });
                                            });
                                        }), e.timeout(label + "/timeout"));
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * plan :325「A FULL 入隊後轉 LOW」：SELECT_CHECK FULL 的同一個 tick A 斷電，零 selection、只釋票；恢復供電後再點才選定。殺「跳過 A 二次電位」。
     * T5-4 fix1（規格 I-2、設計 #9 後半「A 停用前點門」）：A 停用（registry 停用，不只斷電）之後點門：受理只核 B 與走廊（照常受理、借票），FULL 時 A 是 DISABLED，
     * 零 selection、只釋票。未選定的列不會被原艙停止 latch（{@code HomeRootFence} 只 latch 已選定的列），所以這裡沒有 stop；重新啟用之後才合法選定。
     */
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
                open.open();
                e.hold(3, () -> { }, () -> {
                    c.assertTrue(!selected(e, sid) && !hasSelectLease(e, sid), "ROOT_LOW_SELECTED：A FULL 之後轉 LOW 不得選定：row=" + e.homeRow());
                    e.f.power();
                    c.assertTrue(e.a.isReceivingRedstonePower(e.frame.controllerPos()), "A 恢復供電");
                    var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(e.server).registry());
                    hooks.add(() -> lifecycle.setEnabled(e.chamber, true));
                    c.assertTrue(lifecycle.setEnabled(e.chamber, false), "前提：A 停用（registry）");
                    var disabled = click(e, player, doorCell(e, sid));
                    c.assertTrue(disabled == ActionResult.CONSUME && hasSelectLease(e, sid),
                            "前提：A 停用時點門照常受理（受理只核 B 與走廊，A 在 FULL 時核）：" + disabled);
                    var gate = gateSelectTicket(e, sid);
                    awaitGateReady(e, gate, label + "/disabled", () -> {
                        c.assertTrue(HomeEndpointAuthority.checkRoot(e.server, e.root, true) == HomeEndpointAuthority.RootStatus.DISABLED
                                && e.homeRow().home().stopReason().isEmpty(), "前提：A 是 DISABLED、未選定的列沒有 stop");
                        gate.open();
                        e.hold(3, () -> {
                            if (selected(e, sid)) throw new GameTestException("ROOT_DISABLED_SELECTED：A 停用之後點門不得選定：row=" + e.homeRow());
                        }, () -> {
                            c.assertTrue(gate.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0,
                                    "ROOT_DISABLED_SELECTED：A 停用之後點門，FULL 時零 selection、只釋票：full=" + gate.fullTrue + " row=" + e.homeRow());
                            c.assertTrue(lifecycle.setEnabled(e.chamber, true)
                                    && HomeEndpointAuthority.checkRoot(e.server, e.root, true) == HomeEndpointAuthority.RootStatus.READY, "A 重新啟用、READY");
                            requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                            M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid),
                                    e.step(() -> awaitDormant(e, sid, label, () -> closeOut(e, sid, label, e::finish))),
                                    e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：A 恢復供電之後的合法點門沒有選定：row=" + e.homeRow()); }));
                        });
                    });
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

    /**
     * plan :325「旁觀」：先讓 A 的 chunk 就緒，同一個 tick 選門者切成旁觀並開閘（品質 M-7：FULL 一定發生在 gateway 取消之前，{@code fullTrue} 見證）：
     * 零 selection（之後 gateway 依 cohort 失效整組返回 B）。
     */
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
                awaitGateReady(e, open, label + "/spectator", () -> {
                    hooks.add(() -> player.changeGameMode(GameMode.SURVIVAL));
                    player.changeGameMode(GameMode.SPECTATOR);
                    open.open();
                    e.hold(3, () -> {
                        if (selected(e, sid)) throw new GameTestException("SPECTATOR_SELECTED：旁觀的選門者不得選定：row=" + e.homeRow());
                    }, () -> {
                        c.assertTrue(open.fullTrue >= 1, "FULL_NOT_REACHED：切成旁觀的同一個 tick 開閘，FULL 必須發生在 gateway 取消之前：full=" + open.fullTrue);
                        player.changeGameMode(GameMode.SURVIVAL);
                        e.awaitReleased(sid, label + "/returned", null, () -> {
                            c.assertTrue(e.inB(player), "整組未選定返回 exact B");
                            e.finish();
                        });
                    });
                });
            });
        });
    }

    /**
     * spec §4.2 :103：兩人同一個 tick 點門，只有一個 exact HOME（先受理者），另一人不受理（{@code FAIL}），請求記的是第一人；收據不能改選門者。
     * T5-4 fix1（品質 M-5）：SELECT_CHECK 票的第一次原生移除丟出（testmod 換 backend，C6）：選門已 checked 之後的釋票失敗，仍要完成 runtime 的 MEASURED 與 INFO，
     * 不讓 {@code continueHomeSelects} 整段被隔離（票由原艙票 owner 下一個 tick 續清）。RED：{@code SELECT_RELEASE_FAILURE_ABORTED_COMMIT}。
     */
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
                var capture = new LogCapture("quantumchamber");
                hooks.add(capture::close);
                e.walkHome(sid, first, 10.5);
                e.walkHome(sid, second, 12.5);
                var door = doorCell(e, sid);
                requireAccepted(e, sid, click(e, first, door));
                var gate = gateSelectTicket(e, sid, true, true);
                var other = click(e, second, door);
                c.assertTrue(other == ActionResult.FAIL && first.getUuid().equals(selectRequestPlayer(e)),
                        "第二人同 tick 點門不受理（FAIL），請求仍記第一人：" + other + " request=" + selectRequestPlayer(e));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var once = e.homeRow();
                    c.assertTrue(((Selected) once.home().selection()).selectedBy().equals(first.getUuid()), "SELECTED_TWICE：只有先受理者選定：" + once.home().selection());
                    var committed = capture.matching(Level.INFO, "HOME 選門已 checked（MEASURED＋HOME）", sid.toString());
                    var isolated = capture.matching(Level.WARN, "HOME 的 tick 段（continueHomeSelects）丟出意外例外");
                    c.assertTrue(gate.removeThrown == 1 && committed.size() == 1 && isolated.isEmpty() && "MEASURED".equals(String.valueOf(runtimeState(e))),
                            "SELECT_RELEASE_FAILURE_ABORTED_COMMIT：選門 checked 之後的釋票失敗不得中止 MEASURED 與 INFO：thrown=" + gate.removeThrown
                                    + " info=" + committed.size() + " isolated=" + isolated.size() + " runtime=" + runtimeState(e));
                    var late = click(e, second, door);
                    c.assertTrue(late == ActionResult.FAIL && !hasSelectLease(e, sid) && once.equals(e.homeRow()), "已選定之後再點門不受理（FAIL）、不改收據：" + late);
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
                // 品質 M-2：這一項只是防禦。現架構下 HOME Space 一律走 tickHome、refreshCandidates 不可達，selectable 恆為空；「refresh 走一般 resolver」的變異
                // 由 selectableDoors → durable() 的 fail closed（legacy 列核對丟出）殺掉，不是這一則斷言。
                c.assertTrue(e.pages.selectableDoors(sid).isEmpty(), "HOME_CANDIDATE_PUBLISHED_DEFENSIVE：HOME Space 不發布候選門（防禦性斷言）");
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
     * T5-4 fix1（規格 I-3）：走廊世界的 HOME checkpoint 範圍恰為選定列兩筆 lease（含退休中的舊實例）的 chunk，發生在清 lease 寫出之前（B 艙體的範圍見
     * {@link #selected_checkpoint_failure_keeps_selection_and_lock}；原生 checkpoint 探針一次只能裝一個世界）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_retiring_windows", tickLimit = 4000)
    public void retiring_old_home_door_never_selects_and_selection_cleans_all_leases(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "retiring_old_home_door_never_selects_and_selection_cleans_all_leases";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var selector = e.player(0);
                var walker = e.player(1);
                var oldView = e.pages.currentMappings(sid).instances().getFirst();
                int oldSlot = oldView.ref().slotId();
                var corridorProbe = M5GameTestSupport.installChunkCheckpointProbe(c, dev.quantumchamber.superposition.SuperpositionWorld.KEY, 0, ignored -> clearState(e, sid), hooks);
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
                        c.assertTrue(stale == ActionResult.FAIL && !hasSelectLease(e, sid), "RETIRING_DOOR_ACCEPTED：退休中舊實例的 HOME 門不得受理（FAIL）：" + stale);
                        requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                        M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                            var measured = e.homeRow();
                            c.assertTrue(slots(measured).size() == 2 && slots(measured).contains(oldSlot), "前提：選定時舊租約仍在退休中：" + measured.spaceLeases());
                            awaitDormant(e, sid, label, () -> {
                                c.assertTrue(e.allAir(oldView.bounds()) && e.inB(selector) && e.inB(walker), "保留式清理清掉含退休中舊實例的全部 lease，全員回到 exact B");
                                var expected = new HashSet<ChunkPos>();
                                measured.spaceLeases().forEach(lease -> addChunks(expected, lease.bounds()));
                                var calls = corridorProbe.calls();
                                c.assertTrue(!calls.isEmpty() && calls.stream().allMatch(call -> !call.failed() && call.chunks().equals(expected) && call.observed().startsWith("before-clear")),
                                        "MEASURED_CHECKPOINT_SCOPE：選定列的走廊 checkpoint 範圍恰為兩筆 lease 的 chunk、在清 lease 寫出之前：expected=" + expected.size() + " calls=" + calls);
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
                    c.assertTrue(building == ActionResult.FAIL && !hasSelectLease(e, sid) && !selected(e, sid),
                            "SELECT_DURING_BUILD_ACCEPTED：新實例建造中點門不得受理（FAIL）：" + building + " " + e.spaceDiagnostics(sid));
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
     * 重生點不在這裡斷言（重生之後以 INFO {@code M7_T54_RESPAWN_LANDING} 記一次落點，規格 M-7）；走廊內的掉落物是受管實體，由返還引擎隨返還送回 B 艙內（品質 M-8 更正）。
     * T5-4 fix1：先讓 A 的 chunk 就緒，死亡的同一個 tick 開閘（品質 M-7、規格 I-2 末列：「FULL 當下已死亡 → 零 selection」真的被執行，{@code fullTrue} 見證）；
     * testmod 的 END tick 觀察者記下列被移除前的最後一份，死亡者的 Returned 必須為真，而且在重生之後才變真（規格 M-2）。
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
                var gate = gateSelectTicket(e, sid);
                awaitGateReady(e, gate, label + "/death", () -> {
                // 原生 damage 在傳送確認之前（假連線不回確認）會被 invulnerable 擋下，所以直接以原版的死亡流程（health 0＋onDeath）。
                player.setHealth(0.0f);
                player.onDeath(player.getDamageSources().genericKill());
                c.assertTrue(!player.isAlive(), "前提：選門者在走廊內死亡");
                gate.open();
                int[] respawnTick = {-1};
                var returned = watchReturned(e, sid, uuid, respawnTick);
                e.hold(5, () -> {
                    if (selected(e, sid)) throw new GameTestException("DEAD_SELECTOR_SELECTED：row=" + e.homeRow());
                }, () -> {
                    c.assertTrue(gate.fullTrue >= 1 && !selected(e, sid), "DEAD_SELECTOR_SELECTED：死亡的同一個 tick 開閘，FULL 必須發生、零 selection：full=" + gate.fullTrue);
                    // 真客戶端的重生（使用者 2026-11-25「恢復，死亡案改用真重生（建議）」）：重生封包 onClientStatus(PERFORM_RESPAWN) 讓連線改指向新的玩家實體；
                    // 之後照真客戶端時序，以既有 confirmTeleport() 讀原生 PlayerPositionLookS2CPacket 的 teleport id 再 ACK（重生一次、返還的傳送之後每 tick 有封包就回）。
                    var connection = e.f.players.get(0);
                    player.networkHandler.onClientStatus(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
                            net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
                    var respawned = e.server.getPlayerManager().getPlayer(uuid);
                    respawnTick[0] = e.server.getTicks();
                    c.assertTrue(respawned != null && respawned != player && respawned.isAlive() && respawned.networkHandler.player == respawned,
                            "前提：真重生之後是新的、活著的玩家實體，連線指向它");
                    // 規格 M-7：只觀測重生落點（不斷言），T8 已知行為的依據。
                    LOG.info("M7_T54_RESPAWN_LANDING world={} pos={} tick={}", respawned.getServerWorld().getRegistryKey().getValue(), respawned.getPos(), respawnTick[0]);
                    hooks.add(() -> { if (e.server.getPlayerManager().getPlayer(uuid) == respawned) e.server.getPlayerManager().remove(respawned); });
                    ack(connection);
                    M7HomeClosureGameTests.until(c, label + "/returned", 600, () -> {
                        ack(connection);
                        return e.homeRow() == null && !gate().globalChamberBusy(e.server, e.chamber);
                    }, e.step(() -> {
                        var now = e.server.getPlayerManager().getPlayer(uuid);
                        c.assertTrue(now == respawned && now.isAlive() && e.inB(now), "DEATH_NOT_RETURNED_TO_B：死亡重生之後最終回到 exact B：world="
                                + (now == null ? null : now.getServerWorld().getRegistryKey().getValue()));
                        c.assertTrue(returned.last != null && returned.last.participants().stream().anyMatch(person -> person.playerUuid().equals(uuid) && person.returned())
                                        && returned.earlyReturn < 0 && returned.firstReturned >= respawnTick[0] && respawnTick[0] >= 0,
                                "DEATH_RETURNED_NOT_RECORDED：列移除前的最後一份必須記死亡者 Returned，而且在重生之後才變真：last=" + returned.last
                                        + " firstReturned=" + returned.firstReturned + " early=" + returned.earlyReturn + " respawn=" + respawnTick[0]);
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
        });
    }

    // ---- T5-4 fix1：審查補案 ----

    /**
     * 品質 I-2：選定列的清 lease 寫出未確認（ROLL_FORWARD_PENDING；testmod 在全員返還、原艙停止已 latch 之後武裝 journal 正式檔的寫出故障），之後 journal 退避的重試
     * （SSM 的 recovery）確認寫出，同一個 END tick 回程終局 owner（{@code HomeClosure}）看到 cleaned＋stop 就結案、checked 移除列，這時走廊 space 還在（testmod END tick
     * 觀察者見證）。之後走廊 space、HOME runtime 與 B 來源票都必須釋放、艙不再 busy，走廊故障的 ERROR 不得每 tick 一則。RED：{@code MEASURED_RELEASE_STUCK_AFTER_CLOSE}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_release_closed_windows", tickLimit = 3000)
    public void measured_release_confirmed_and_closed_same_tick_releases_space(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "measured_release_confirmed_and_closed_same_tick_releases_space";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var capture = new LogCapture("quantumchamber");
                hooks.add(capture::close);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(e.server).registry());
                    c.assertTrue(lifecycle.setEnabled(e.chamber, false), "前提：選定之後 A 停用（原艙停止 latch）");
                    var window = watchReleaseWindow(e, sid);
                    M7HomeClosureGameTests.until(c, label + "/pending", 600, () -> window.fault != null && window.fault.hits() >= 1, e.step(() -> {
                        window.fault.disarm();
                        var current = e.journal.allSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
                        var flushed = e.homeRow();
                        c.assertTrue(e.journal.rollForwardPending(sid) && current != null && current.spaceLeases().isEmpty() && flushed != null && !flushed.spaceLeases().isEmpty()
                                        && flushed.home().stopReason().isPresent() && e.pages.hasReservation(sid),
                                "前提：清 lease 寫出未確認（current 已清空、flushed 仍有租約）、原艙停止已 latch、走廊 space 還在：current=" + current + " flushed=" + flushed);
                        M7HomeClosureGameTests.until(c, label + "/closed", 1500, () -> window.removedTick >= 0 && e.server.getTicks() >= window.removedTick + 20, e.step(() -> {
                            var errors = capture.matching(Level.ERROR, "HOME 走廊保留租約等待安全返回 B", sid.toString()).size();
                            c.assertTrue(window.spaceAtRemoval, "SCENARIO_NOT_REACHED：列被回程終局 owner 移除的那個 END tick，走廊 space 必須還在（清 lease 的確認與結案同一個 tick）");
                            c.assertTrue(!e.pages.hasReservation(sid) && M7HomeCorridorGameTests.ownerType(e.chamber) == null && !gate().globalChamberBusy(e.server, e.chamber),
                                    "MEASURED_RELEASE_STUCK_AFTER_CLOSE：選定列在清 lease 確認的同一個 tick 被結案移除之後，走廊 space 與 HOME runtime 必須釋放、艙不再 busy：space="
                                            + e.pages.hasReservation(sid) + " owner=" + M7HomeCorridorGameTests.ownerType(e.chamber) + " errors=" + errors + " " + e.spaceDiagnostics(sid));
                            e.assertNoSourceTicket(sid);
                            c.assertTrue(errors <= 1, "HOME_FAILURE_ERROR_FLOOD：走廊故障的 ERROR 不得每 tick 一則：" + errors);
                            c.assertTrue(e.inB(player), "全員在 exact B");
                            e.finish();
                        }), e.timeout(label + "/closed"));
                    }), e.timeout(label + "/pending"));
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * 規格 I-2（spec §4.2 :102「取消…只釋放票」）、品質 M-6：SELECT_CHECK 延後 FULL 期間正式取消（B 的 Controller，{@code returnToOrigin}）：checked RETURNING 之後，下一個 tick
     * 內選門請求就丟棄、票釋放（不等滿 200 tick），零 selection；之後整組未選定返回 B。RED：{@code CANCELLED_SELECT_REQUEST_KEPT}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_cancel_windows", tickLimit = 2400)
    public void cancelled_select_request_is_dropped_promptly(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "cancelled_select_request_is_dropped_promptly";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var gate = gateSelectTicket(e, sid);
                c.assertTrue(!gate().returnToOrigin(e.b, e.bController) && e.homeRow().state() == SessionState.RETURNING, "延後期間正式取消：checked RETURNING");
                e.hold(1, () -> { }, () -> {
                    c.assertTrue(selectRequestPlayer(e) == null && !hasSelectLease(e, sid) && !selected(e, sid),
                            "CANCELLED_SELECT_REQUEST_KEPT：取消之後選門請求必須在下一個 tick 內丟棄、只釋放自己的票：request=" + selectRequestPlayer(e) + " lease=" + hasSelectLease(e, sid));
                    gate.open();
                    e.hold(3, () -> {
                        if (selected(e, sid)) throw new GameTestException("CANCELLED_SELECT_COMMITTED：取消之後不得選定：row=" + e.homeRow());
                    }, () -> e.awaitReleased(sid, label + "/returned", null, () -> {
                        c.assertTrue(e.inB(player), "取消之後整組未選定返回 exact B");
                        e.finish();
                    }));
                });
            });
        });
    }

    /**
     * 規格 I-2、品質 I-1 第 2 點（spec §4.2 :102「玩家離線…只釋放票」）：先讓 A 的 chunk 就緒，延後 FULL 期間選門者原生斷線、同一個 tick 開閘：FULL 時原 instance 已不在
     * PlayerManager，零 selection、只釋票；之後他重新登入（JOIN 下一 tick）才回 exact B，整組未選定收尾。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_offline_windows", tickLimit = 2400)
    public void offline_selector_during_select_check_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "offline_selector_during_select_check_never_selects";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                var gate = gateSelectTicket(e, sid);
                awaitGateReady(e, gate, label + "/offline", () -> {
                    var offline = e.f.players.remove(0);
                    hooks.add(offline::close);
                    var absent = offline.player().getUuid();
                    var profile = offline.player().getGameProfile();
                    offline.disconnect();
                    c.assertTrue(e.server.getPlayerManager().getPlayer(absent) == null, "前提：原生斷線移除線上玩家");
                    gate.open();
                    e.hold(3, () -> {
                        if (selected(e, sid)) throw new GameTestException("OFFLINE_SELECTOR_SELECTED：離線的選門者不得選定：row=" + e.homeRow());
                    }, () -> {
                        c.assertTrue(gate.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid) && HomeTicketFaultFixture.count(e.a, sid) == 0,
                                "OFFLINE_SELECTOR_SELECTED：FULL 時選門者已離線，零 selection、只釋放自己的票：full=" + gate.fullTrue + " row=" + e.homeRow());
                        var rejoined = new ConnectedGameTestPlayer(e.a, profile);
                        e.f.players.add(rejoined);
                        e.awaitReleased(sid, label + "/returned", null, () -> {
                            c.assertTrue(e.inB(rejoined.player()), "重登者在 JOIN 之後回到 exact B");
                            e.finish();
                        });
                    });
                });
            });
        });
    }

    /**
     * 規格 I-2（spec §4.2 :101「bbox 完整在唯一當前 mapping」）：先讓 A 的 chunk 就緒，延後 FULL 期間把選門者原生移到 current 實例的頂上（bbox 不在任何 current 實例內），
     * 互動距離仍成立、cohort 仍有效，同一個 tick 開閘：FULL 時只有 bbox 條件擋得住，零 selection、只釋票。之後正式換頁 owner 因 cohort 離開唯一映射而交 HOME 故障路徑（只回 B）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_bbox_windows", tickLimit = 2400)
    public void selector_outside_instance_bbox_never_selects(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "selector_outside_instance_bbox_never_selects";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                e.walkHome(sid, player, 11.5);
                var door = doorCell(e, sid);
                var cells = e.homeMarkers(e.homeRow());
                requireAccepted(e, sid, click(e, player, door));
                var gate = gateSelectTicket(e, sid);
                awaitGateReady(e, gate, label + "/bbox", () -> {
                    var view = e.pages.currentMappings(sid).instances().getFirst();
                    var top = new Vec3d(door.getX() + 0.5, view.bounds().getMaxY() + 1.0, door.getZ() + 0.5);
                    c.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(player, e.corridor(), top, Vec3d.ZERO, player.getYaw(), player.getPitch()),
                            "前提：原生移動到 current 實例的頂上");
                    c.assertTrue(e.pages.homePlayerOwner(sid, player).isEmpty() && cells.stream().anyMatch(cell -> player.canInteractWithBlockAt(cell, 1.0))
                                    && e.pages.homeCohortHasBuff(sid) && e.homeRow().state() == SessionState.SUPERPOSITION,
                            "前提：bbox 不在任何 current 實例內、互動距離仍成立、cohort 仍有效：pos=" + player.getPos() + " bounds=" + view.bounds());
                    gate.open();
                    e.hold(3, () -> {
                        if (selected(e, sid)) throw new GameTestException("OUT_OF_BBOX_SELECTED：bbox 不在唯一 current 實例內不得選定：row=" + e.homeRow());
                    }, () -> {
                        c.assertTrue(gate.fullTrue >= 1 && !selected(e, sid) && !hasSelectLease(e, sid),
                                "OUT_OF_BBOX_SELECTED：FULL 時 bbox 不在唯一 current 實例內，零 selection、只釋票：full=" + gate.fullTrue + " row=" + e.homeRow());
                        e.awaitReleased(sid, label + "/returned", null, () -> {
                            c.assertTrue(e.inB(player), "整組未選定返回 exact B");
                            e.finish();
                        });
                    });
                });
            });
        });
    }

    /**
     * 設計稿第 12 案、品質 I-1 第 1 點、規格 I-2（spec §8 :207「離線者等 JOIN 下一 tick」、§6 :189）：選定之後一人原生斷線。另一人回 exact B、Returned；列仍是 MEASURED
     * 持租約（不是 DORMANT），離線者仍凍結、他與艙都被占用；他重新登入（JOIN）的下一個 tick 才由返還引擎送回 exact B，之後才清 lease 成 DORMANT。全程不落 A。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_offline_selected_windows", tickLimit = 2400)
    public void offline_selected_participant_returns_after_join(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 2, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "offline_selected_participant_returns_after_join";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var selector = e.player(0);
                var ids = List.copyOf(e.ids());
                boolean[] inA = {false};
                boolean[] done = {false};
                EndTick.watch(e.server, () -> {
                    for (var id : ids) {
                        var now = e.server.getPlayerManager().getPlayer(id);
                        if (now != null && now.getServerWorld() == e.a) inA[0] = true;
                    }
                    return done[0];
                });
                hooks.add(() -> done[0] = true);
                e.walkHome(sid, selector, 11.5);
                requireAccepted(e, sid, click(e, selector, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    c.assertTrue(e.homeRow().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned), "前提：選定之後尚未有人返還");
                    var offline = e.f.players.remove(1);
                    hooks.add(offline::close);
                    var absent = offline.player().getUuid();
                    var profile = offline.player().getGameProfile();
                    offline.disconnect();
                    c.assertTrue(e.server.getPlayerManager().getPlayer(absent) == null, "前提：原生斷線移除線上玩家");
                    M7HomeClosureGameTests.until(c, label + "/online", 400, () -> e.homeRow() != null && e.homeRow().participants().stream()
                            .anyMatch(person -> person.playerUuid().equals(selector.getUuid()) && person.returned()), e.step(() -> {
                        var current = e.homeRow();
                        c.assertTrue(current.state() == SessionState.MEASURED && current.home().selection() instanceof Selected && !current.spaceLeases().isEmpty()
                                        && current.participants().stream().anyMatch(person -> person.playerUuid().equals(absent) && !person.returned()),
                                "OFFLINE_SELECTED_DORMANT_EARLY：離線者回來之前，選定列仍是持租約的 MEASURED、離線者未返還：" + current);
                        c.assertTrue(e.inB(selector) && SessionRecoveryManager.blocks(absent, e.server) && gate().globalChamberBusy(e.server, e.chamber)
                                        && !e.journal.participantsAvailable(List.of(absent)) && e.pages.hasReservation(sid),
                                "線上者先回 B；離線者仍凍結、他與艙都被占用、走廊 space 保留");
                        e.hold(20, () -> {
                            var held = e.homeRow();
                            if (held == null || held.spaceLeases().isEmpty())
                                throw new GameTestException("OFFLINE_SELECTED_DORMANT_EARLY：離線者回來之前不得清 lease 成 DORMANT：row=" + held);
                        }, () -> {
                            var rejoined = new ConnectedGameTestPlayer(e.a, profile);
                            e.f.players.add(rejoined);
                            int joined = rejoined.joinTick();
                            c.assertTrue(joined == e.server.getTicks() && rejoined.player().getServerWorld() == e.corridor(), "JOIN 只排隊，玩家照 playerdata 先回到走廊");
                            e.nextTick(() -> {
                                c.assertTrue(e.server.getTicks() == joined + 1, "前提：觀察點是 JOIN 的下一個 tick（早於該 tick 的 END tick）");
                                var pending = e.homeRow();
                                c.assertTrue(rejoined.player().getServerWorld() == e.corridor() && pending != null && !pending.spaceLeases().isEmpty() && pending.participants().stream()
                                                .anyMatch(person -> person.playerUuid().equals(absent) && !person.returned()),
                                        "RETURNED_ON_JOIN_TICK：JOIN 當 tick 的返還引擎不得返還重登者（JOIN 只排到下一個 tick）：world="
                                                + rejoined.player().getServerWorld().getRegistryKey().getValue() + " row=" + pending);
                                awaitDormant(e, sid, label, () -> {
                                    c.assertTrue(!inA[0] && e.inB(selector) && e.inB(rejoined.player()), "RETURNED_TO_A：全員只回 exact B、全程不落 A：inA=" + inA[0]);
                                    done[0] = true;
                                    closeOut(e, sid, label, e::finish);
                                });
                            });
                        });
                    }), e.timeout(label + "/online"));
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
            });
        });
    }

    /**
     * 規格 I-3（plan :323「按 checkpoint 清走廊／lease」、spec §8 :207、T5-2 規格 M-4）：選定列全員返還之後的 HOME checkpoint，在 B 世界的範圍恰為 B 艙體，發生在清 lease
     * 寫出之前；前兩次受控失敗時保留選定列、租約、走廊 space 與艙的占用，退避後重做成功才清 lease 成 DORMANT。殺「tickHomeMeasuredRelease 省略 checkpoint」。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_checkpoint_windows", tickLimit = 2400)
    public void selected_checkpoint_failure_keeps_selection_and_lock(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "selected_checkpoint_failure_keeps_selection_and_lock";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var probe = M5GameTestSupport.installChunkCheckpointProbe(c, e.b.getRegistryKey(), 2, ignored -> clearState(e, sid), hooks);
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() ->
                    M7HomeClosureGameTests.until(c, label + "/failed", 400, () -> !probe.calls().isEmpty() || dormant(e, sid), e.step(() -> {
                        c.assertTrue(!probe.calls().isEmpty(), "MEASURED_CHECKPOINT_SKIPPED：選定列在 HOME checkpoint 之前就清 lease 成 DORMANT：row=" + e.homeRow());
                        var current = e.homeRow();
                        c.assertTrue(probe.calls().getFirst().failed() && current.state() == SessionState.MEASURED && current.home().selection() instanceof Selected
                                        && current.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty()
                                        && current.equals(e.journal.allSessions().get(sid)) && e.pages.hasReservation(sid) && gate().globalChamberBusy(e.server, e.chamber),
                                "MEASURED_CHECKPOINT_FAILURE_RELEASED：checkpoint 失敗時保留選定列、租約、走廊 space 與艙的占用：" + current);
                        awaitDormant(e, sid, label, () -> {
                            var calls = probe.calls();
                            var expected = new HashSet<ChunkPos>();
                            addChunks(expected, ChamberGeometry.bounds(e.frame));
                            c.assertTrue(calls.size() >= 3 && calls.get(0).failed() && calls.get(1).failed() && !calls.get(2).failed()
                                            && calls.stream().limit(3).allMatch(call -> call.chunks().equals(expected) && call.observed().startsWith("before-clear")),
                                    "MEASURED_CHECKPOINT_SCOPE：前兩次受控失敗、之後成功才清 lease；B 的範圍恰為 B 艙體、在清 lease 寫出之前：" + calls);
                            closeOut(e, sid, label, e::finish);
                        });
                    }), e.timeout(label + "/failed"))), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
            });
        });
    }

    // ---- T5-5：覆蓋缺口（設計稿第 9 節；在 42877e8 的 production 上就應通過，不是 RED） ----

    /**
     * plan :706 前半（T5-5 設計稿第 9 節第 1 案、設計審查 M-6、第二版複審 N-5）：{@code admitHome} 走 QUARANTINED 時，ARMING 從未落盤、玩家零移動、沒有 B 票；
     * 未確認的嘗試收斂之前，暫占與走廊 reservation 一直持鎖，收斂之後才解除；WARN 帶 {@code QUARANTINED}。
     * <ul>
     * <li>注入：flush 探針（{@code M5GameTestSupport.installFlushProbe}）在第一次「current 帶本 SID 的 ARMING、尚未隔離」的寫出（{@code admitHome} 的 checked flush）HEAD
     *     呼叫 {@code journal.quarantine(sid)}。這次寫出以 flushed 取代隔離列、flushed 沒有就不寫（{@code SessionRecoveryState.writable}），讀回不等於 next，
     *     {@code finishHome} 因此走隔離分支（{@code SessionRecoveryState.java:888}）回 QUARANTINED。</li>
     * <li>觀察點：同一個 tick 內依序是 QUARANTINED（{@code continueHomeStarts}）、recovery 的收斂寫出（{@code convergeUnchecked}）、{@code finishHomeAbandoned} 解除，
     *     tick 之間看不到鎖（M-6），所以在收斂寫出的 HEAD 見證暫占（{@code globalChamberBusy}）與 reservation 仍在。收斂寫出以狀態辨識（N-5）：第一次觸發之後，flush HEAD 時
     *     本 SID 已不在隔離、也不在 current（{@code convergeQuarantined} 已以移除取代並解除隔離），不以「下一次寫出」的順序認定。收斂那個 tick 的 END tick（testmod 觀察者，
     *     排在正式 owner 之後）兩者都已解除。</li>
     * <li>與 plan :706 字面的差異：plan 寫的是「admitHome CAS 不符」；本案以預先隔離讓 {@code finishHome} 走到同一個 quarantine 分支，前提不同、終局相同。</li>
     * </ul>
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_arming_quarantined_windows", tickLimit = 2000)
    public void home_arming_quarantined_keeps_lock_until_converged(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var label = "home_arming_quarantined_keeps_lock_until_converged";
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            var player = e.player(0);
            var pose = player.getPos();
            e.requireStarted();
            var sid = M7HomeCorridorGameTests.provisionalSid(e.chamber);
            c.assertTrue(sid != null && HomeTicketFaultFixture.count(e.a, sid) > 0, "前提：startHome 受理後有暫占與 A 的 START_CHECK 票");
            var w = watchQuarantinedArming(e, sid);
            M7HomeClosureGameTests.until(c, label + "/converged", 300,
                    () -> w.endTick >= 0 || w.error != null || (w.quarantinedTick >= 0 && !w.quarantineAccepted), e.step(() -> {
                var warned = capture.matching(Level.WARN, "ARMING", "（QUARANTINED）", sid.toString());
                c.assertTrue(w.error == null && w.quarantinedTick >= 0 && w.quarantineAccepted && w.convergedTick >= w.quarantinedTick && warned.size() == 1,
                        "QUARANTINED_PATH_NOT_TAKEN：admitHome 必須走 QUARANTINED（WARN 帶 QUARANTINED 恰一則），之後由 recovery 收斂：" + w + " warned=" + warned.size());
                c.assertTrue(w.busyAtConvergence && w.reservationAtConvergence,
                        "QUARANTINED_LOCK_NOT_HELD_AT_CONVERGENCE：收斂寫出的 HEAD 暫占與走廊 reservation 都必須仍在（收斂之前一直持鎖）：" + w);
                c.assertTrue(!w.onDisk && e.homeRow() == null && !journalMentions(e.journal, sid),
                        "QUARANTINED_ARMING_ON_DISK：被隔離的 ARMING 不得落盤（flushed 與 pending 從未出現本 SID），收斂之後 journal 不留本 SID：" + w);
                c.assertTrue(e.inB(player) && pose.equals(player.getPos()) && noEntryTickets(e, sid),
                        "QUARANTINED_ARMING_ON_DISK：沒有 checked ARMING 就不得有入場副作用（玩家零移動、沒有 B 票）：pos=" + player.getPos() + " " + ticketState(e, sid));
                c.assertTrue(w.releasedAtEnd && w.endTick == w.convergedTick && !gate().globalChamberBusy(e.server, e.chamber) && !e.pages.hasReservation(sid)
                                && M7HomeCorridorGameTests.provisionalSid(e.chamber) == null && HomeTicketFaultFixture.count(e.a, sid) == 0,
                        "QUARANTINED_NOT_RELEASED：收斂那個 tick 結束時暫占、走廊 reservation 與 START_CHECK 票都必須已解除：" + w + " " + ticketState(e, sid));
                e.finish();
            }), e.step(() -> {
                throw new GameTestException((w.quarantinedTick < 0 ? "QUARANTINED_PATH_NOT_TAKEN" : "QUARANTINED_NOT_RELEASED")
                        + "：300 tick 內沒有走完 QUARANTINED、收斂與解除：" + w + " busy=" + gate().globalChamberBusy(e.server, e.chamber)
                        + " reservation=" + e.pages.hasReservation(sid) + " provisional=" + M7HomeCorridorGameTests.provisionalSid(e.chamber));
            }));
        });
    }

    /**
     * plan :706 後半（T5-5 設計稿第 9 節第 2 案）：{@code admitHome} 在寫出之前 REJECTED。注入：startHome 受理之後、A 的 START_CHECK FULL 之前（同一個 callback；FULL 最早在
     * 本 tick 的 END tick），在收據目錄放一個同 SID 的檔（{@link #occupyReceipt}），{@code HomeReceiptStore.requireAdmissible} 讀它時丟出 IOException，{@code admitHome} 在
     * {@code stageHome} 之前回 REJECTED（{@code SessionRecoveryState.java:774}、:776）。這時走廊 reservation 已經取得（{@code reserveHomeInitial} 在 {@code admitHome} 之前），
     * 所以走的是 {@code homeAbandons} 的「沒有任何殘留、立即解除」：journal 的三份與隔離從未出現本 SID、玩家零移動、沒有 B 票，WARN 之後 1 tick 內解除暫占、reservation 與
     * START_CHECK 票，不再 busy。觀察點是 testmod 的 END tick 觀察者（排在正式 owner 之後）與 flush 探針。
     * <p>與設計稿第 9 節的差異：放的是非 NBT 的位元組，{@code lookup} 讀檔時就丟出 IOException（gzip 標頭錯），不是「旅程識別碼已有歷史」那一行；兩者都在
     * {@code requireAdmissible} 內、走同一個 IOException 分支回 REJECTED（WARN 的 cause 是 {@code UncheckedIOException}，與前置條件不符的 {@code IllegalStateException} 區分）。
     * 合法的結案收據要用 package-private 的編碼器才寫得出來，testmod 不偽造。hooks 收尾刪除這個檔。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_arming_rejected_windows", tickLimit = 2000)
    public void home_arming_rejected_before_write_releases_at_once(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            var label = "home_arming_rejected_before_write_releases_at_once";
            var capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            var player = e.player(0);
            var pose = player.getPos();
            e.requireStarted();
            var sid = M7HomeCorridorGameTests.provisionalSid(e.chamber);
            c.assertTrue(sid != null && HomeTicketFaultFixture.count(e.a, sid) > 0 && gate().globalChamberBusy(e.server, e.chamber),
                    "前提：startHome 受理後有暫占與 A 的 START_CHECK 票");
            occupyReceipt(e, sid);
            var w = watchRejectedArming(e, sid, capture);
            M7HomeClosureGameTests.until(c, label + "/released", 300, () -> w.releasedTick >= 0 || w.error != null, e.step(() -> {
                var warned = capture.matching(Level.WARN, "ARMING", "（REJECTED）", sid.toString());
                c.assertTrue(w.error == null && w.warnTick >= 0 && warned.size() == 1 && warned.getFirst().thrown() instanceof java.io.UncheckedIOException,
                        "REJECTED_BEFORE_WRITE_NOT_TAKEN：admitHome 必須在寫出之前因收據 admission 的 IOException 回 REJECTED（WARN 恰一則、cause 是 UncheckedIOException）：" + w
                                + " warned=" + warned.stream().map(entry -> String.valueOf(entry.thrown())).toList());
                c.assertTrue(!w.inJournal && !journalMentions(e.journal, sid) && e.homeRow() == null,
                        "REJECTED_ARMING_RESIDUE：寫出之前被拒的 ARMING 不得出現在 journal（current、flushed、pending、隔離）：" + w);
                c.assertTrue(e.inB(player) && pose.equals(player.getPos()) && noEntryTickets(e, sid),
                        "REJECTED_ARMING_RESIDUE：寫出之前被拒不得有入場副作用（玩家零移動、沒有 B 票）：pos=" + player.getPos() + " " + ticketState(e, sid));
                c.assertTrue(w.releasedTick - w.warnTick <= 1 && !gate().globalChamberBusy(e.server, e.chamber) && !e.pages.hasReservation(sid)
                                && M7HomeCorridorGameTests.provisionalSid(e.chamber) == null && HomeTicketFaultFixture.count(e.a, sid) == 0,
                        "REJECTED_LOCK_HELD：寫出之前被拒時 1 tick 內解除暫占、走廊 reservation 與 START_CHECK 票，不再 busy：" + w + " " + ticketState(e, sid));
                e.finish();
            }), e.step(() -> {
                throw new GameTestException((w.warnTick < 0 ? "REJECTED_BEFORE_WRITE_NOT_TAKEN" : "REJECTED_LOCK_HELD") + "：300 tick 內沒有走完寫出前 REJECTED 與解除：" + w
                        + " busy=" + gate().globalChamberBusy(e.server, e.chamber) + " reservation=" + e.pages.hasReservation(sid)
                        + " provisional=" + M7HomeCorridorGameTests.provisionalSid(e.chamber) + " " + ticketState(e, sid));
            }));
        });
    }

    /**
     * T5-5 設計稿第 9 節第 3 案（F-2）：選定之後、返還之前在走廊內死亡。checked MEASURED＋HOME（尚未有人返還）的同一個 callback 以原版死亡流程讓選門者死亡；返還引擎跳過
     * 死亡中的玩家（{@code SessionRecoveryManager.recoverHome} 的 dead-skip，:518），死亡期間不記 Returned、不清 lease、選擇保留。之後照真客戶端重生
     * （{@code onClientStatus(PERFORM_RESPAWN)}，比照 {@link #corridor_death_returns_to_exact_b_with_returned}），返還引擎把新的玩家實體送回 exact B、記 Returned（在重生之後
     * 才變真），清 lease 成 DORMANT，選擇不變；收尾以原艙停止 latch 交回程終局 owner 結案（{@link #closeOut}）。與未選定的死亡案共用同一段 dead-skip。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_select_selected_death_windows", tickLimit = 2400)
    public void selected_corridor_death_returns_to_exact_b(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var e = new M7HomeCorridorGameTests.HomeEntry(c, 1, hooks, true);
        e.guard(() -> {
            e.requireStarted();
            var label = "selected_corridor_death_returns_to_exact_b";
            e.awaitSuperposition(label, row -> {
                var sid = row.sessionUuid();
                var player = e.player(0);
                var uuid = player.getUuid();
                e.walkHome(sid, player, 11.5);
                requireAccepted(e, sid, click(e, player, doorCell(e, sid)));
                M7HomeClosureGameTests.until(c, label + "/selected", 300, () -> selected(e, sid), e.step(() -> {
                    var measured = e.homeRow();
                    var selection = measured.home().selection();
                    c.assertTrue(measured.state() == SessionState.MEASURED && selection instanceof Selected s && s.selectedBy().equals(uuid) && !measured.spaceLeases().isEmpty()
                                    && measured.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && player.getServerWorld() == e.corridor(),
                            "前提：checked MEASURED＋HOME（他是選門者）、租約仍在、尚未有人返還、他仍在走廊：" + measured);
                    // 原生 damage 在傳送確認之前（假連線不回確認）會被 invulnerable 擋下，所以直接以原版的死亡流程（health 0＋onDeath），比照未選定的死亡案。
                    player.setHealth(0.0f);
                    player.onDeath(player.getDamageSources().genericKill());
                    c.assertTrue(!player.isAlive(), "前提：選門者在選定之後、返還之前於走廊內死亡");
                    int[] respawnTick = {-1};
                    var returned = watchReturned(e, sid, uuid, respawnTick);
                    e.hold(5, () -> {
                        var held = e.homeRow();
                        if (held == null || !selection.equals(held.home().selection()))
                            throw new GameTestException("SELECTED_DEATH_SELECTION_LOST：死亡期間選擇必須保留：row=" + held);
                        if (held.spaceLeases().isEmpty() || held.participants().stream().anyMatch(person -> person.playerUuid().equals(uuid) && person.returned()))
                            throw new GameTestException("SELECTED_DEATH_NOT_RETURNED：返還引擎必須跳過死亡中的玩家（死亡期間不記 Returned、不清 lease）：row=" + held);
                    }, () -> {
                        // 真客戶端的重生（比照未選定的死亡案）：重生封包讓連線改指向新的玩家實體；之後每 tick 有原生 teleport 封包就 ACK。
                        var connection = e.f.players.get(0);
                        player.networkHandler.onClientStatus(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
                                net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
                        var respawned = e.server.getPlayerManager().getPlayer(uuid);
                        respawnTick[0] = e.server.getTicks();
                        c.assertTrue(respawned != null && respawned != player && respawned.isAlive() && respawned.networkHandler.player == respawned,
                                "前提：真重生之後是新的、活著的玩家實體，連線指向它");
                        hooks.add(() -> { if (e.server.getPlayerManager().getPlayer(uuid) == respawned) e.server.getPlayerManager().remove(respawned); });
                        ack(connection);
                        M7HomeClosureGameTests.until(c, label + "/returned", 600, () -> {
                            ack(connection);
                            var current = e.homeRow();
                            return current != null && current.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
                        }, e.step(() -> awaitDormant(e, sid, label, () -> {
                            var now = e.server.getPlayerManager().getPlayer(uuid);
                            c.assertTrue(now == respawned && now.isAlive() && e.inB(now), "SELECTED_DEATH_NOT_RETURNED：選定之後死亡、重生的玩家最終回到 exact B：world="
                                    + (now == null ? null : now.getServerWorld().getRegistryKey().getValue()) + " pos=" + (now == null ? null : now.getPos()));
                            c.assertTrue(returned.last != null && returned.last.participants().stream().anyMatch(person -> person.playerUuid().equals(uuid) && person.returned())
                                            && returned.earlyReturn < 0 && returned.firstReturned >= respawnTick[0] && respawnTick[0] >= 0,
                                    "SELECTED_DEATH_NOT_RETURNED：死亡者的 Returned 必須記在列上，而且在重生之後才變真：last=" + returned.last
                                            + " firstReturned=" + returned.firstReturned + " early=" + returned.earlyReturn + " respawn=" + respawnTick[0]);
                            var dormant = e.homeRow();
                            c.assertTrue(dormant.state() == SessionState.MEASURED && selection.equals(dormant.home().selection()),
                                    "SELECTED_DEATH_SELECTION_LOST：死亡、重生與返還之後選擇保留、列仍是 MEASURED DORMANT：" + dormant.home().selection());
                            e.assertNoSourceTicket(sid);
                            closeOut(e, sid, label, e::finish);
                        })), e.step(() -> {
                            var now = e.server.getPlayerManager().getPlayer(uuid);
                            throw new GameTestException("SELECTED_DEATH_NOT_RETURNED：選定之後死亡、重生之後沒有回到 exact B 記 Returned：row=" + e.homeRow()
                                    + " world=" + (now == null ? null : now.getServerWorld().getRegistryKey().getValue()) + " pos=" + (now == null ? null : now.getPos())
                                    + " teleporting=" + (now != null && now.isInTeleportationState()));
                        }));
                    });
                }), e.step(() -> { throw new GameTestException("HOME_NOT_SELECTED：row=" + e.homeRow()); }));
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

    /**
     * 點門受理：回 {@code CONSUME}、借到 SELECT_CHECK 票（stub 時以 {@code HOME_NOT_SELECTED} 失敗）。T5-4 fix1（規格 M-1＝品質 M-1）：受理回 CONSUME（尚未選定，
     * 不宣稱成功；設計稿 §1.1 第 5 步原寫 FAIL，實作改 CONSUME，報告列為偏離），不受理回 FAIL。
     */
    static void requireAccepted(M7HomeCorridorGameTests.HomeEntry e, UUID sid, ActionResult result) {
        e.c.assertTrue(result == ActionResult.CONSUME && hasSelectLease(e, sid),
                "HOME_NOT_SELECTED：合法點門沒有受理（應回 CONSUME 並借 SELECT_CHECK 票）：" + result + " row=" + e.homeRow());
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
     * SELECT_CHECK 票的受控 backend（testmod 反射換掉票的 backend，C6；加票與移除照原生，收尾隨票釋放）。{@link #open} 之前一律未 FULL，之後「chunk 已載入為 WorldChunk」。
     * {@code fullTrue} 是 backend 回 FULL 的次數（只有 {@code continueHomeSelects} 的 {@code full()} 會問到 backend，所以它大於 0 表示 FULL 那一步確實發生，品質 M-7 的見證）；
     * {@code failFirstRemove} 時第一次原生移除丟出（品質 M-5），{@code removeThrown} 是丟出次數。
     */
    static final class Gate {
        private final Object lease;
        private boolean open;
        int fullTrue;
        int removeThrown;

        private Gate(Object lease, boolean open) { this.lease = lease; this.open = open; }

        void open() { open = true; }

        /** 這張票的 A chunk 都已載入為 WorldChunk（開閘之後的下一個 END tick 一定 FULL）。 */
        boolean ready(M7HomeCorridorGameTests.HomeEntry e) {
            @SuppressWarnings("unchecked") var chunks = (Set<ChunkPos>) M4CandidateTestAccess.get(lease, "chunks");
            return !chunks.isEmpty() && chunks.stream().allMatch(chunk -> e.a.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null);
        }
    }

    /** 把這個 SID 的 SELECT_CHECK 票換成未開閘的 {@link Gate}。只在點門受理的同一個 callback 內呼叫。 */
    static Gate gateSelectTicket(M7HomeCorridorGameTests.HomeEntry e, UUID sid) { return gateSelectTicket(e, sid, false, false); }

    static Gate gateSelectTicket(M7HomeCorridorGameTests.HomeEntry e, UUID sid, boolean openNow, boolean failFirstRemove) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(HomeRootTickets.class, "servers")).get(e.server);
        var lease = ((Map<?, ?>) M4CandidateTestAccess.get(owner, "leases")).get(sid);
        e.c.assertTrue(lease != null, "SELECT_CHECK 已取得 A 的票");
        var nativeBackend = M4CandidateTestAccess.get(HomeRootTickets.class, "NATIVE");
        Class<?> type;
        try { type = Class.forName("dev.quantumchamber.collapse.HomeRootTickets$Backend", false, HomeRootTickets.class.getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new GameTestException("HomeRootTickets.Backend 找不到：" + failure); }
        var gate = new Gate(lease, openNow);
        var proxy = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("full")) {
                if (!gate.open) return false;
                var world = (ServerWorld) args[0];
                var pos = (ChunkPos) args[1];
                boolean full = world.getChunkManager().getWorldChunk(pos.x, pos.z) != null;
                if (full) gate.fullTrue++;
                return full;
            }
            if (method.getName().equals("remove") && failFirstRemove && gate.removeThrown == 0) {
                gate.removeThrown++;
                throw new IllegalStateException("M7_T54_SELECT_CHECK_RELEASE_FAULT");
            }
            method.setAccessible(true);
            try { return method.invoke(nativeBackend, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        M4CandidateTestAccess.set(lease, "backend", proxy);
        return gate;
    }

    /** 等這張票的 A chunk 都就緒（品質 M-7：之後同一個 tick 改狀態並開閘，FULL 一定在 gateway 取消之前）；100 tick 內沒有就緒以 {@code SELECT_GATE_NOT_READY} 失敗。 */
    static void awaitGateReady(M7HomeCorridorGameTests.HomeEntry e, Gate gate, String label, Runnable then) {
        M7HomeClosureGameTests.until(e.c, label + "/gate-ready", 100, () -> gate.ready(e), e.step(then),
                e.step(() -> { throw new GameTestException("SELECT_GATE_NOT_READY：SELECT_CHECK 票的 A chunk 沒有在 100 tick 內載入：" + label); }));
    }

    /** 這座艙的 HOME runtime（只讀反射）；不是 HOME runtime 時為 null。 */
    private static Object homeRuntime(M7HomeCorridorGameTests.HomeEntry e) {
        var owner = ((Map<?, ?>) M4CandidateTestAccess.get(gate(), "sessions")).get(e.chamber);
        return owner != null && owner.getClass().getSimpleName().equals("HomeRuntime") ? owner : null;
    }

    /** HOME runtime 目前的選門請求記的玩家 UUID（只讀反射）；沒有請求時為 null。 */
    static UUID selectRequestPlayer(M7HomeCorridorGameTests.HomeEntry e) {
        var runtime = homeRuntime(e);
        var request = runtime == null ? null : M4CandidateTestAccess.get(runtime, "select");
        return request == null ? null : (UUID) M4CandidateTestAccess.get(request, "player");
    }

    /** HOME runtime 的 state（只讀反射）；沒有 runtime 時為 null。 */
    private static Object runtimeState(M7HomeCorridorGameTests.HomeEntry e) {
        var runtime = homeRuntime(e);
        return runtime == null ? null : M4CandidateTestAccess.get(runtime, "state");
    }

    /**
     * 規格 I-1：同一個 UUID 換成新的 {@code ServerPlayerEntity}。原生 {@code PlayerManager.respawnPlayer(player, true, CHANGED_DIMENSION)}（存活複製：{@code copyFrom(...,true)}
     * 帶著狀態效果，含 QuantumState；舊實體被原生移除），連線改指向新實體（{@code onClientStatus} 才會做的那一步），再以原生移動送回原位置與朝向。整段在同一個 callback
     * 內完成，gateway 的 END tick 看不到中間狀態。
     */
    static ServerPlayerEntity replacePlayer(M7HomeCorridorGameTests.HomeEntry e, ServerPlayerEntity old) {
        var pos = old.getPos();
        float yaw = old.getYaw(), pitch = old.getPitch();
        var replaced = e.server.getPlayerManager().respawnPlayer(old, true, Entity.RemovalReason.CHANGED_DIMENSION);
        replaced.networkHandler.player = replaced;
        e.c.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(replaced, e.corridor(), pos, Vec3d.ZERO, yaw, pitch),
                "前提：新實體以原生移動回到原位置：" + replaced.getServerWorld().getRegistryKey().getValue());
        return replaced;
    }

    /** 規格 M-2：END tick 觀察者看到的 HOME 列。{@code last} 是移除前最後一份；{@code firstReturned} 是第一次看到這位玩家 Returned 的 tick；在重生之前就 Returned 時記在 {@code earlyReturn}。 */
    private static final class ReturnedWatch {
        HomeSessionRecord last;
        int firstReturned = -1;
        int earlyReturn = -1;
    }

    private static ReturnedWatch watchReturned(M7HomeCorridorGameTests.HomeEntry e, UUID sid, UUID player, int[] respawnTick) {
        var watch = new ReturnedWatch();
        EndTick.watch(e.server, () -> {
            var row = e.homeRow();
            if (row == null || !row.sessionUuid().equals(sid)) return watch.last != null;
            watch.last = row;
            if (watch.firstReturned < 0 && row.participants().stream().anyMatch(person -> person.playerUuid().equals(player) && person.returned())) {
                watch.firstReturned = e.server.getTicks();
                if (respawnTick[0] < 0) watch.earlyReturn = watch.firstReturned;
            }
            return false;
        });
        return watch;
    }

    /**
     * 品質 I-2 的窗口：全員返還、原艙停止已 latch 的 END tick 武裝 journal 寫出故障；之後記下列被 checked 移除的 END tick，以及那一刻走廊 space 是否還在。
     * T5-5 Step 0（T5-4 品質 fix1 F-1）：比照 {@code offline_selected_participant_returns_after_join}，hooks 釋放時 {@code closed} 設為真，觀察者先核它就結束；
     * 案例在失敗路徑釋放 hooks 之後，觀察者不會再武裝全域的 journal 寫出故障（那時已沒有 hook 可以關掉它）。
     */
    private static final class ReleaseWindow {
        M7NativeSaveFault.Handle fault;
        int removedTick = -1;
        boolean spaceAtRemoval;
        boolean closed;
    }

    private static ReleaseWindow watchReleaseWindow(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var window = new ReleaseWindow();
        e.hooks.add(() -> window.closed = true);
        EndTick.watch(e.server, () -> {
            if (window.closed) return true;
            if (window.fault == null) {
                var row = e.homeRow();
                if (row != null && row.home().stopReason().isPresent() && !row.spaceLeases().isEmpty() && row.equals(e.journal.allSessions().get(sid))
                        && row.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)) window.fault = e.armJournalFault(sid);
                return false;
            }
            if (e.homeRow() != null || e.journal.allSessions().containsKey(sid) || e.journal.pendingSessions().containsKey(sid)) return false;
            window.removedTick = e.server.getTicks();
            window.spaceAtRemoval = e.pages.hasReservation(sid);
            return true;
        });
        return window;
    }

    /**
     * T5-5 第 1 案（{@code home_arming_quarantined_keeps_lock_until_converged}）的觀察；flush 探針與 END tick 觀察者共用，只在 server thread。
     * {@code quarantinedTick}／{@code quarantineAccepted}：第一次觸發（admitHome 的寫出 HEAD 隔離本 SID）的 tick 與 {@code quarantine} 的回傳；{@code convergedTick} 與兩個
     * {@code AtConvergence}：以狀態辨識的收斂寫出（N-5）的 tick 與那一刻的鎖；{@code endTick}／{@code releasedAtEnd}：收斂之後第一個 END tick 與那時是否已解除；
     * {@code onDisk}：任一觀察點 flushed 或 pending 帶本 SID；{@code error}：觀察本身丟出的例外（不丟進 production）；hooks 釋放時 {@code closed} 設為真。
     */
    private static final class QuarantineWatch {
        int quarantinedTick = -1;
        boolean quarantineAccepted;
        int convergedTick = -1;
        boolean busyAtConvergence;
        boolean reservationAtConvergence;
        int endTick = -1;
        boolean releasedAtEnd;
        boolean onDisk;
        String error;
        boolean closed;

        @Override public String toString() {
            return "quarantinedTick=" + quarantinedTick + " accepted=" + quarantineAccepted + " convergedTick=" + convergedTick + " busyAtConvergence=" + busyAtConvergence
                    + " reservationAtConvergence=" + reservationAtConvergence + " endTick=" + endTick + " releasedAtEnd=" + releasedAtEnd + " onDisk=" + onDisk + " error=" + error;
        }
    }

    private static QuarantineWatch watchQuarantinedArming(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var w = new QuarantineWatch();
        e.hooks.add(() -> w.closed = true);
        // flush 探針在 production 的寫出裡執行，不得丟出（丟出會讓那次寫出受控失敗）：例外只記在 error。
        M5GameTestSupport.installFlushProbe(e.c, state -> {
            if (w.closed) return;
            try {
                if (state.allFlushedSessions().containsKey(sid) || state.pendingSessions().containsKey(sid)) w.onDisk = true;
                if (w.quarantinedTick < 0) {
                    if (state.allSessions().get(sid) instanceof HomeSessionRecord row && row.state() == SessionState.ARMING && !state.quarantinedSessions().contains(sid)) {
                        w.quarantinedTick = e.server.getTicks();
                        w.quarantineAccepted = state.quarantine(sid);
                    }
                    return;
                }
                // N-5：收斂寫出以狀態辨識——第一次觸發之後，本 SID 已不在隔離、也不在 current。
                if (w.convergedTick < 0 && !state.quarantinedSessions().contains(sid) && !state.allSessions().containsKey(sid)) {
                    w.convergedTick = e.server.getTicks();
                    w.busyAtConvergence = gate().globalChamberBusy(e.server, e.chamber);
                    w.reservationAtConvergence = e.pages.hasReservation(sid);
                }
            } catch (RuntimeException failure) { if (w.error == null) w.error = String.valueOf(failure); }
        }, e.hooks);
        EndTick.watch(e.server, () -> {
            if (w.closed) return true;
            try {
                if (e.journal.allFlushedSessions().containsKey(sid) || e.journal.pendingSessions().containsKey(sid)) w.onDisk = true;
                if (w.convergedTick < 0) return false;
                w.endTick = e.server.getTicks();
                w.releasedAtEnd = !gate().globalChamberBusy(e.server, e.chamber) && !e.pages.hasReservation(sid) && M7HomeCorridorGameTests.provisionalSid(e.chamber) == null;
                return true;
            } catch (RuntimeException failure) { if (w.error == null) w.error = String.valueOf(failure); return true; }
        });
        return w;
    }

    /**
     * T5-5 第 2 案（{@code home_arming_rejected_before_write_releases_at_once}）的觀察；只在 server thread。{@code warnTick}：第一次看到 admitHome REJECTED 的 WARN 的 END tick；
     * {@code releasedTick}：之後第一個暫占、reservation 都已解除、不再 busy 的 END tick；{@code inJournal}：任一觀察點（END tick 與 flush HEAD）journal 帶本 SID。
     */
    private static final class RejectWatch {
        int warnTick = -1;
        int releasedTick = -1;
        boolean inJournal;
        String error;
        boolean closed;

        @Override public String toString() {
            return "warnTick=" + warnTick + " releasedTick=" + releasedTick + " inJournal=" + inJournal + " error=" + error;
        }
    }

    private static RejectWatch watchRejectedArming(M7HomeCorridorGameTests.HomeEntry e, UUID sid, LogCapture capture) {
        var w = new RejectWatch();
        e.hooks.add(() -> w.closed = true);
        M5GameTestSupport.installFlushProbe(e.c, state -> {
            if (w.closed) return;
            try { if (journalMentions(state, sid)) w.inJournal = true; }
            catch (RuntimeException failure) { if (w.error == null) w.error = String.valueOf(failure); }
        }, e.hooks);
        EndTick.watch(e.server, () -> {
            if (w.closed) return true;
            try {
                if (journalMentions(e.journal, sid)) w.inJournal = true;
                if (w.warnTick < 0 && !capture.matching(Level.WARN, "ARMING", "（REJECTED）", sid.toString()).isEmpty()) w.warnTick = e.server.getTicks();
                if (w.warnTick < 0 || gate().globalChamberBusy(e.server, e.chamber) || e.pages.hasReservation(sid)
                        || M7HomeCorridorGameTests.provisionalSid(e.chamber) != null) return false;
                w.releasedTick = e.server.getTicks();
                return true;
            } catch (RuntimeException failure) { if (w.error == null) w.error = String.valueOf(failure); return true; }
        });
        return w;
    }

    /**
     * 第 2 案的注入：在收據目錄放一個同 SID 的檔（{@code HomeReceiptStore.receiptPath} 的格式：{@code <save>/data/quantumchamber_home_receipts/<SID>.dat}）。內容不是 NBT，
     * {@code requireAdmissible} 的 {@code lookup} 讀它時丟出 IOException。先登記 hooks 刪除再寫，失敗路徑也會刪。
     */
    private static void occupyReceipt(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var receipt = e.server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("quantumchamber_home_receipts").resolve(sid + ".dat");
        e.hooks.add(() -> {
            try { java.nio.file.Files.deleteIfExists(receipt); }
            catch (java.io.IOException failure) { LOG.warn("M7_T55_REJECTED_RECEIPT 同 SID 的收據檔刪除失敗：{}", receipt, failure); }
        });
        try {
            java.nio.file.Files.createDirectories(receipt.getParent());
            java.nio.file.Files.write(receipt, "M7_T55_REJECTED_BEFORE_WRITE".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException failure) { throw new GameTestException("REJECTED_FIXTURE：收據目錄放不進同 SID 的檔：" + receipt + "：" + failure); }
        e.c.assertTrue(java.nio.file.Files.isRegularFile(receipt), "前提：同 SID 的收據檔已放好：" + receipt);
    }

    /** journal 的 current、flushed、pending 或隔離是否帶這個 SID。 */
    private static boolean journalMentions(SessionRecoveryState journal, UUID sid) {
        return journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid) || journal.pendingSessions().containsKey(sid)
                || journal.quarantinedSessions().contains(sid);
    }

    /** 這個 SID 在 B 沒有 Entry=B 來源票與 B 收尾票，A 也沒有來源票（只讀原生 ticket 表）。 */
    private static boolean noEntryTickets(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        return M5GameTestSupport.tickets(e.b, "quantumchamber_source", sid).isEmpty() && M5GameTestSupport.tickets(e.b, "quantumchamber_home_entry", sid).isEmpty()
                && M5GameTestSupport.tickets(e.a, "quantumchamber_source", sid).isEmpty();
    }

    /** 票的診斷（只放進失敗訊息）：B 的來源票與收尾票、A 的來源票與 START_CHECK 票數。 */
    private static String ticketState(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        return "sourceB=" + M5GameTestSupport.tickets(e.b, "quantumchamber_source", sid) + " entryB=" + M5GameTestSupport.tickets(e.b, "quantumchamber_home_entry", sid)
                + " sourceA=" + M5GameTestSupport.tickets(e.a, "quantumchamber_source", sid) + " startCheck=" + HomeTicketFaultFixture.count(e.a, sid);
    }

    /** checkpoint 當下的清 lease 狀態（給探針的 observe）：current 仍有租約、等於 flushed 是 {@code before-clear}。 */
    private static String clearState(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var current = e.journal.allSessions().get(sid);
        var flushed = e.journal.allFlushedSessions().get(sid);
        boolean before = current instanceof HomeSessionRecord now && !now.spaceLeases().isEmpty() && now.equals(flushed);
        return (before ? "before-clear" : "after-clear") + " current=" + (current instanceof HomeSessionRecord now ? now.spaceLeases().size() : -1)
                + " flushed=" + (flushed instanceof HomeSessionRecord then ? then.spaceLeases().size() : -1);
    }

    /** 與 {@code MeasuredWorldSaveCheckpoint} 相同的 chunk 範圍算法（box 覆蓋的每一個 chunk）。 */
    private static void addChunks(Set<ChunkPos> chunks, net.minecraft.util.math.BlockBox box) {
        for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++)
            for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
    }

    /** 選定列已清 lease 成 DORMANT（current＝flushed）。 */
    private static boolean dormant(M7HomeCorridorGameTests.HomeEntry e, UUID sid) {
        var row = e.homeRow();
        return row != null && row.home().selection() instanceof Selected && row.spaceLeases().isEmpty() && row.equals(e.journal.allSessions().get(sid));
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
