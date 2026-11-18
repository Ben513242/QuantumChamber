package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;

import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberPowerCoordinator;
import dev.quantumchamber.chamber.ChamberPowerState;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;

/**
 * M7 T4A（spec v1.6 D、V15）：永久封存的最小解除路徑。艙的每一列都是已 checked 的永久原因 SEALED 時，原艙停用而且實際斷電，供電協調把 power state 設為 OFF
 * （比較器 0）並照既有流程核對，之後可沿既有拆除路徑移除 Controller；封存列與各 ledger 都不改寫。
 */
public final class M7SealReleaseGameTests implements FabricGameTest {
    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /**
     * 舊列永久封存（EXISTING_INELIGIBLE）：停用、斷電 → OFF、比較器 0、艙體可寫 → 原生 setter 移除 Controller、registry 不再有這座艙；封存列在 current、flushed 與磁碟上
     * 都不變，projection／allocation／discovery ledger 與 HOME 收據目錄不變；封存列仍標記這個 UUID busy。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_dismantle", tickLimit = 600)
    public void permanent_sealed_origin_dismantles_after_disable_and_power_off(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var registry = ChamberRegistryState.get(server).registry();
            SessionRecoveryRecord sealed = M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                    Map.of(UUID.randomUUID(), true), SealedReason.EXISTING_INELIGIBLE, origin.owned);
            var sid = sealed.sessionUuid();
            var before = Evidence.of(server, sid);
            witness.put("diskRowPresent", !before.row().equals(Evidence.ABSENT));
            context.assertTrue(new ChamberLifecycleService(registry).setEnabled(chamber, false), "管理員停用原艙");
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, registered + 3, hooks, () -> {
                var record = registry.records().get(chamber);
                var power = record == null ? "ABSENT" : record.powerState().name();
                int comparator = M7RearmGameTests.comparator(world, frame);
                boolean writable = ChamberProtectionService.get().mayMutate(world, frame.controllerPos().down(3));
                if (record == null || record.powerState() != ChamberPowerState.OFF || comparator != 0 || !writable) {
                    // 訊息先組好再收尾：收尾會以終局 API 移除封存列。
                    var row = journal.flushedRecords().get(sid);
                    var failure = new GameTestException("PERMANENT_SEAL_NOT_RELEASED：永久封存的原艙停用並斷電之後仍受保護：power=" + power + " comparator=" + comparator
                            + " 可寫=" + writable + " 封存列=" + (row == null ? "ABSENT" : row.state() + "/" + row.collapse().sealedReason()));
                    hooks.releaseAfter(failure);
                    throw failure;
                }
                witness.put("dismantledByTheNativeSetter", world.setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState())
                        && !world.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER));
                witness.put("registryForgotTheChamber", !registry.records().containsKey(chamber));
                witness.put("sealedRowUnchangedInMemory", sealed.equals(journal.flushedRecords().get(sid)) && sealed.equals(journal.records().get(sid)));
                var after = Evidence.of(server, sid);
                witness.put("sealedRowAndLedgersUnchangedOnDisk", before.equals(after));
                witness.put("uuidStillBusy", gate().globalChamberBusy(server, chamber));
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "永久封存停用斷電後可拆：" + witness + " before=" + before + " after=" + after);
                context.complete();
            });
        });
    }

    /**
     * spec v1.6 D、V15：永久封存（AUTHORITY_CORRUPT）的艙只在停用而且斷電時解除。啟用中斷電、停用但仍供電都維持保護（power state RETURNING、比較器 11、艙體不可寫、
     * 原生 setter 拆不掉 Controller）；停用而且斷電才 OFF。之後重新供電、或重新啟用，都回到既有保護。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_protected", tickLimit = 600)
    public void permanent_sealed_stays_protected_while_enabled_or_powered(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer();
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var registry = ChamberRegistryState.get(server).registry();
            var lifecycle = new ChamberLifecycleService(registry);
            M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(UUID.randomUUID(), true),
                    SealedReason.AUTHORITY_CORRUPT, origin.owned);
            // 啟用中斷電。
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, registered + 3, hooks, () -> {
                protectedNow(world, frame, chamber, "enabledLow", witness);
                // 恢復供電之後停用（停用但仍供電）。
                context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                context.assertTrue(lifecycle.setEnabled(chamber, false), "管理員停用原艙");
                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                at(context, registered + 6, hooks, () -> {
                    protectedNow(world, frame, chamber, "disabledPowered", witness);
                    // 停用而且斷電：解除。
                    context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                    at(context, registered + 9, hooks, () -> {
                        witness.put("disabledLowReleased", power(registry, chamber) == ChamberPowerState.OFF && M7RearmGameTests.comparator(world, frame) == 0
                                && ChamberProtectionService.get().mayMutate(world, frame.controllerPos().down(3)));
                        // 重新供電：回到保護。
                        context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                        at(context, registered + 12, hooks, () -> {
                            protectedNow(world, frame, chamber, "repoweredProtected", witness);
                            // 再斷電解除一次，之後重新啟用：回到保護。
                            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                            at(context, registered + 15, hooks, () -> {
                                witness.put("releasedAgain", power(registry, chamber) == ChamberPowerState.OFF);
                                context.assertTrue(lifecycle.setEnabled(chamber, true), "重新啟用原艙");
                                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                                protectedNow(world, frame, chamber, "reenabledProtected", witness);
                                hooks.release();
                                context.assertTrue(!witness.containsValue(false), "永久封存在啟用或供電時仍受保護：" + witness);
                                context.complete();
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * spec v1.6 D、V15：可重試的 SEALED（PROJECTION_FAILED）與未 checked 的永久封存（COLLAPSING→SEALED(EXISTING_INELIGIBLE) 的 roll-forward 寫出失敗、尚未確認）
     * 在停用而且斷電時都不解除（RETURNING、比較器 11、艙體不可寫）。故障解除、recovery 的重試確認寫出之後，同一列才解除（OFF）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_unchecked", tickLimit = 1500)
    public void retryable_or_unchecked_seal_never_released(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var registry = ChamberRegistryState.get(server).registry();
            var lifecycle = new ChamberLifecycleService(registry);
            var authority = M4CandidateTestAccess.origin(chamber, frame.controllerPos());
            // 1. 可重試的 SEALED：停用而且斷電也不解除。
            var retryable = M5GameTestSupport.sealedFixture(server, authority, Map.of(UUID.randomUUID(), true), SealedReason.PROJECTION_FAILED, origin.owned);
            context.assertTrue(lifecycle.setEnabled(chamber, false), "管理員停用原艙");
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, registered + 3, hooks, () -> {
                protectedNow(world, frame, chamber, "retryableNotReleased", witness);
                M5GameTestSupport.cleanupFixtures(server, List.of(retryable.sessionUuid()));
                // 2. 未 checked 的永久封存：COLLAPSING（暫停推進）→SEALED 的 roll-forward 寫出在 flush 開頭受控失敗。
                context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                context.assertTrue(lifecycle.setEnabled(chamber, true), "重新啟用原艙");
                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                var collapsing = M5GameTestSupport.collapseFixtureOwnedBy(server, authority, Map.of(UUID.randomUUID(), true), List.of(), true,
                        dev.quantumchamber.superposition.SessionState.COLLAPSING, true, origin.owned);
                var sid = collapsing.sessionUuid();
                var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
                M5GameTestSupport.installFlushProbe(context, state -> { if (failing.get()) throw new IllegalStateException("M7_T4A_FLUSH_FAULT：受控的 journal 寫出失敗"); }, hooks);
                var sealed = collapsing.withCollapse(dev.quantumchamber.superposition.SessionState.SEALED, collapsing.collapse().sealed(SealedReason.EXISTING_INELIGIBLE));
                var commit = journal.commitRollForward(server, collapsing, sealed);
                witness.put("rollForwardPending", commit.status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING && journal.rollForwardPending(sid)
                        && sealed.equals(journal.records().get(sid)) && collapsing.equals(journal.flushedRecords().get(sid)));
                context.assertTrue(lifecycle.setEnabled(chamber, false), "管理員停用原艙（未 checked 的封存）");
                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                at(context, registered + 6, hooks, () -> {
                    protectedNow(world, frame, chamber, "uncheckedNotReleased", witness);
                    failing.set(false);
                    M5GameTestSupport.whenTicks(context, registered + 7, 1200, () -> sealed.equals(journal.flushedRecords().get(sid)) && !journal.rollForwardPending(sid), converged -> {
                        ChamberControllerBlock.refreshState(world, frame.controllerPos());
                        witness.put("releasedOnceChecked", power(registry, chamber) == ChamberPowerState.OFF && M7RearmGameTests.comparator(world, frame) == 0
                                && ChamberProtectionService.get().mayMutate(world, frame.controllerPos().down(3)));
                        hooks.release();
                        context.assertTrue(!witness.containsValue(false), "可重試或未 checked 的封存不解除：" + witness);
                        context.complete();
                    }, () -> "受控故障解除之後 roll-forward 沒有確認：current=" + journal.records().get(sid) + " flushed=" + journal.flushedRecords().get(sid), hooks);
                });
            });
        });
    }

    /**
     * spec v1.6 D、V15：同艙另一端的回程（HOME 列）只剩永久封存（ENTRY_AUTHORITY_INVALID）時，A 停用並斷電後協調為 OFF，原生 setter 可以拆除 A 的 Controller（拆除防線放行）。
     * 對照：同一座 A 已斷電核對為 OFF、HOME 列仍是 DORMANT（非永久）時，正式拆除入口被拆除防線拒絕。HOME 列在拆除前後都不變。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_home", tickLimit = 600)
    public void home_permanent_seal_allows_root_removal_after_disable(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new M7HomeClosureGameTests.Projection(context, hooks);
        var sid = UUID.randomUUID();
        Runnable cleanup = () -> {
            try {
                var journal = SessionRecoveryState.get(p.f.server);
                if (journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)) M4CandidateTestAccess.removeMeasuredForTeardown(p.f.server, sid);
            } finally { p.close(); }
        };
        try {
            var world = context.getWorld(); var server = p.f.server; var journal = SessionRecoveryState.get(server);
            var registry = ChamberRegistryState.get(server).registry();
            var lifecycle = new ChamberLifecycleService(registry);
            var witness = new LinkedHashMap<String, Boolean>();
            var pos = p.frame.controllerPos();
            // A 斷電核對為 OFF（還沒有任何旅程）。
            new ChamberPowerCoordinator().refresh(world, pos);
            context.assertTrue(power(registry, p.chamber) == ChamberPowerState.OFF, "前提：A 已斷電協調為 OFF");
            var dormant = p.dormant(sid, 41);
            // 對照：非永久的 HOME 列（DORMANT）時，拆除防線拒絕正式拆除入口。
            boolean removedWhileDormant = lifecycle.dismantleOrigin(p.root, () -> ChamberProtectionService.get().authorizedMutation(world, pos,
                    () -> world.removeBlock(pos, false)));
            boolean controllerKept = world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER), registeredKept = registry.records().containsKey(p.chamber);
            // T4B Step 0（T4A 審查 M-4）：對照不成立時立即以具名錯因丟出，不讓後續步驟（HOME 列的 checked 轉移）以無關的錯因失敗。
            if (removedWhileDormant || !controllerKept || !registeredKept)
                throw new GameTestException("HOME_DORMANT_ROOT_REMOVED：非永久的 HOME 列（DORMANT）時拆除防線必須拒絕正式拆除：removed=" + removedWhileDormant
                        + " Controller 保留=" + controllerKept + " 已登記=" + registeredKept);
            witness.put("dormantHomeRowKeepsTheRoot", true);
            // HOME 列 checked 進入永久封存：MEASURED→COLLAPSING（restore）→SEALED(ENTRY_AUTHORITY_INVALID)（roll-forward）。
            var h = dormant.home();
            long now = server.getOverworld().getTime();
            var collapsing = new HomeSessionRecord(sid, dormant.chamberUuid(), dormant.rootAnchor(), dormant.entryEndpoint(), dormant.participants(), List.of(),
                    dev.quantumchamber.superposition.SessionState.COLLAPSING, new HomeSessionRecord.Home(h.selection(), now, -1, -1, List.of(), List.of(), Optional.empty(), -1,
                    new HomeSessionRecord.None(), Optional.empty()));
            context.assertTrue(journal.commitHomeRestore(server, dormant, collapsing).committed(), "checked HOME COLLAPSING");
            var sealed = new HomeSessionRecord(sid, dormant.chamberUuid(), dormant.rootAnchor(), dormant.entryEndpoint(), dormant.participants(), List.of(),
                    dev.quantumchamber.superposition.SessionState.SEALED, new HomeSessionRecord.Home(h.selection(), now, -1, -1, List.of(), List.of(), Optional.empty(), -1,
                    new HomeSessionRecord.None(), Optional.of(HomeSessionRecord.Seal.ENTRY_AUTHORITY_INVALID)));
            context.assertTrue(journal.commitHomeRollForward(server, collapsing, sealed).committed(), "checked HOME SEALED(ENTRY_AUTHORITY_INVALID)");
            context.assertTrue(lifecycle.setEnabled(p.chamber, false), "管理員停用 A（永久封存不 latch 停止原因）");
            ChamberControllerBlock.refreshState(world, pos);
            witness.put("releasedToOff", power(registry, p.chamber) == ChamberPowerState.OFF && M7RearmGameTests.comparator(world, p.frame) == 0
                    && ChamberProtectionService.get().mayMutate(world, pos.down(3)));
            witness.put("dismantledByTheNativeSetter", world.setBlockState(pos, Blocks.AIR.getDefaultState()) && !world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)
                    && !registry.records().containsKey(p.chamber));
            witness.put("homeRowUnchanged", sealed.equals(journal.allSessions().get(sid)) && sealed.equals(journal.allFlushedSessions().get(sid)));
            cleanup.run();
            context.assertTrue(!witness.containsValue(false), "HOME 永久封存停用斷電後 A 可拆：" + witness);
            context.complete();
        } catch (RuntimeException | Error failure) {
            try { cleanup.run(); } catch (RuntimeException | Error suppressed) { failure.addSuppressed(suppressed); }
            hooks.releaseAfter(failure);
            throw failure;
        }
    }

    /**
     * spec v1.6 D、V15：解除並拆除之後，同位置重建的艙登記成新的身分（新 UUID），不 busy、presence NONE、比較器 3；舊封存列原樣保留，仍標記舊 UUID busy。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_rebuild", tickLimit = 600)
    public void rebuild_after_release_gets_new_identity_and_is_not_busy(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var sealed = releaseAndDismantle(context, origin, chamber, SealedReason.PROJECTION_CONFLICT, registered, hooks, witness, (dismantledAt, oldRow) -> {
                var rebuilt = rebuild(context, frame);
                witness.put("rebuiltWithANewIdentity", rebuilt != null && !rebuilt.equals(chamber));
                at(context, dismantledAt + 2, hooks, () -> {
                    witness.put("newChamberNotBusy", rebuilt != null && !gate().globalChamberBusy(server, rebuilt)
                            && gate().presence(server, rebuilt) == dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
                    witness.put("newChamberIdle", M7RearmGameTests.comparator(world, frame) == 3);
                    witness.put("oldUuidStillBusy", gate().globalChamberBusy(server, chamber));
                    witness.put("sealedRowUnchanged", oldRow.equals(journal.flushedRecords().get(oldRow.sessionUuid())) && oldRow.equals(journal.records().get(oldRow.sessionUuid())));
                    hooks.release();
                    context.assertTrue(!witness.containsValue(false), "解除並拆除之後原地重建：" + witness);
                    context.complete();
                });
            });
        });
    }

    /**
     * spec v1.6 D、V15、審查焦點 9：舊封存列 S（EXISTING_INELIGIBLE）解除、拆除並原地重建成新艙 A' 之後，S 不得以原艙座標改動 A' 的門。三個時點都要 A' 的門維持
     * 一般開門：
     * <ol>
     * <li>待套用門：模擬重啟之後 S 的第一次觀察排入了關門（CLOSE_ON_SEALED，套用在下一個 tick），排入之後、套用之前拆除並重建；</li>
     * <li>重建之後再模擬一次重啟之後的第一次觀察；</li>
     * <li>重建之後模擬 chunk 重新載入（本 instance 看過沒有載入、這次已載入）。</li>
     * </ol>
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_rebuilt_reapply", tickLimit = 600)
    public void rebuilt_chamber_door_ignores_released_seal_after_restart_reapply(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var registry = ChamberRegistryState.get(server).registry();
            var sealed = M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(UUID.randomUUID(), true),
                    SealedReason.EXISTING_INELIGIBLE, origin.owned);
            var sid = sealed.sessionUuid();
            context.assertTrue(new ChamberLifecycleService(registry).setEnabled(chamber, false), "管理員停用原艙");
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, registered + 3, hooks, () -> {
                witness.put("releasedToOff", power(registry, chamber) == ChamberPowerState.OFF);
                witness.put("doorOpened", new dev.quantumchamber.chamber.ChamberDoorService().applyTarget(world, frame,
                        dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN).succeeded());
                M5GameTestSupport.simulateDoorStartup(server, sid);
                M5GameTestSupport.whenTicks(context, registered + 4, 10, () -> M5UniverseSupport.reapplyQueuedTicks(origin.capture, sid).size() == 1, queuedAt -> {
                    // 排入之後、套用之前：拆除並原地重建。
                    witness.put("dismantled", world.setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState()) && !registry.records().containsKey(chamber));
                    var rebuilt = rebuild(context, frame);
                    witness.put("rebuilt", rebuilt != null && !rebuilt.equals(chamber));
                    at(context, queuedAt + 3, hooks, () -> {
                        witness.put("pendingCloseLeftTheRebuiltDoorOpen", M5GameTestSupport.doorAll(world, frame, true, dev.quantumchamber.chamber.MembraneState.NONE));
                        M5GameTestSupport.simulateDoorStartup(server, sid);
                        at(context, queuedAt + 6, hooks, () -> {
                            witness.put("restartReapplyLeftTheRebuiltDoorOpen", M5GameTestSupport.doorAll(world, frame, true, dev.quantumchamber.chamber.MembraneState.NONE));
                            M4CandidateTestAccess.set(track(server, sid), "doorLoaded", Boolean.FALSE);
                            at(context, queuedAt + 9, hooks, () -> {
                                witness.put("chunkReloadReapplyLeftTheRebuiltDoorOpen", M5GameTestSupport.doorAll(world, frame, true,
                                        dev.quantumchamber.chamber.MembraneState.NONE));
                                witness.put("sealedRowUnchanged", sealed.equals(journal.flushedRecords().get(sid)) && sealed.equals(journal.records().get(sid)));
                                witness.put("rebuiltNotBusy", rebuilt != null && !gate().globalChamberBusy(server, rebuilt));
                                // T4B Step 0（T4A 審查 M-8）：模擬重啟之後的開服提醒（永久原因 SEALED）恰一則，字句附上解除路徑與「封存紀錄保留」（CollapseService.PERMANENT_RELEASE_HINT）。
                                var reminders = origin.capture.matching(org.apache.logging.log4j.Level.ERROR, M5GameTestSupport.STARTUP_SEALED_REMINDER, sid.toString());
                                boolean hinted = reminders.size() == 1 && reminders.getFirst().message().contains("解除佔地：")
                                        && reminders.getFirst().message().contains("封存紀錄保留在 journal");
                                var failure = witness.containsValue(false) ? new GameTestException("REBUILT_DOOR_CHANGED_BY_RELEASED_SEAL：舊封存列以原艙座標改動了原地重建的艙門："
                                        + witness + " 排入=" + M5UniverseSupport.reapplyQueuedTicks(origin.capture, sid) + " 套用=" + M5UniverseSupport.reappliedTicks(origin.capture, sid))
                                        : !hinted ? new GameTestException("PERMANENT_RELEASE_HINT_MISSING：開服提醒沒有恰一則、或字句沒有解除路徑與「封存紀錄保留」："
                                        + reminders.stream().map(LogCapture.Entry::message).toList()) : null;
                                hooks.release();
                                if (failure != null) throw failure;
                                context.complete();
                            });
                        });
                    });
                }, () -> "模擬重啟之後 S 沒有排入重新套用：witness=" + witness, hooks);
            });
        });
    }

    /**
     * T4B Step 0（T4A 審查 M-3）：原艙座標門權威（{@code OriginDoors.ownsDoor}）的 ORIGIN 與非 destroyed 兩個子條件。舊封存列 S（EXISTING_INELIGIBLE）的艙在 registry
     * 的同一 frame 上仍是同一個 Chamber UUID，只把那一筆改成（以反射直接改 registry 的記憶體列，不經通知，模擬讀到這樣的 registry）：
     * <ol>
     * <li>{@code destroyed=true}：模擬重啟之後的第一次觀察不排入重新套用、記「原艙座標上已不是這座艙」；</li>
     * <li>{@code instanceKind=PROJECTION}：同上；</li>
     * <li>對照（還原原列）：同一個觀察排入一次重新套用。</li>
     * </ol>
     * 每一步之後還原原列；收尾也還原。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_door_authority", tickLimit = 600)
    public void door_authority_requires_origin_and_not_destroyed(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer();
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            @SuppressWarnings("unchecked")
            var rows = (Map<UUID, dev.quantumchamber.chamber.ChamberRecord>) M4CandidateTestAccess.get(ChamberRegistryState.get(server).registry(), "records");
            var original = rows.get(chamber);
            hooks.add(() -> rows.put(chamber, original));
            var sealed = M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(UUID.randomUUID(), true),
                    SealedReason.EXISTING_INELIGIBLE, origin.owned);
            var sid = sealed.sessionUuid();
            java.util.function.Supplier<Integer> queued = () -> M5UniverseSupport.reapplyQueuedTicks(origin.capture, sid).size();
            java.util.function.Supplier<Integer> notOwned = () -> origin.capture.matching(org.apache.logging.log4j.Level.INFO, "原艙座標上已不是這座艙", sid.toString()).size();
            at(context, registered + 2, hooks, () -> {
                rows.put(chamber, withRecord(original, original.instanceKind(), true));
                M5GameTestSupport.simulateDoorStartup(server, sid);
                at(context, registered + 4, hooks, () -> {
                    witness.put("destroyedRowIsNotTheDoorAuthority", queued.get() == 0 && notOwned.get() == 1);
                    rows.put(chamber, withRecord(original, dev.quantumchamber.chamber.ChamberInstanceKind.PROJECTION, false));
                    M5GameTestSupport.simulateDoorStartup(server, sid);
                    at(context, registered + 6, hooks, () -> {
                        witness.put("projectionRowIsNotTheDoorAuthority", queued.get() == 0 && notOwned.get() == 2);
                        rows.put(chamber, original);
                        M5GameTestSupport.simulateDoorStartup(server, sid);
                        at(context, registered + 8, hooks, () -> {
                            witness.put("sameOriginRowStillQueuesTheReapply", queued.get() == 1 && notOwned.get() == 2);
                            var failure = witness.containsValue(false) ? new GameTestException("DOOR_AUTHORITY_SUBCONDITION：registry 的 ORIGIN／未拆除子條件沒有守住原艙座標門權威："
                                    + witness + " 排入=" + M5UniverseSupport.reapplyQueuedTicks(origin.capture, sid) + " 不屬這座艙=" + notOwned.get()) : null;
                            hooks.release();
                            if (failure != null) throw failure;
                            context.complete();
                        });
                    });
                });
            });
        });
    }

    /** 同一筆 registry 列，只換 instanceKind 與 destroyed。 */
    private static dev.quantumchamber.chamber.ChamberRecord withRecord(dev.quantumchamber.chamber.ChamberRecord r, dev.quantumchamber.chamber.ChamberInstanceKind kind,
            boolean destroyed) {
        return new dev.quantumchamber.chamber.ChamberRecord(r.chamberUuid(), r.originWorldKey(), r.originDimensionRole(), r.anchorPos(), r.facing(), kind, r.enabled(),
                destroyed, r.powerState());
    }

    /**
     * spec v1.6 D、V15、審查焦點 9：舊封存列 S 解除、拆除並原地重建成 A' 之後，A' 的新 session R（DORMANT NEW 收據，參與者由艙外進入）在 PREPARE 期間參與者關門，照常 checked 終局。
     * 前提：S 在 journal 的走訪順序（{@code OriginDoors.sessionAt} 依 {@code flushedRecords()}）排在 R 之前；收據的 SID 依實際順序重抽，直到成立（最多 32 次）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_rebuilt_prepare_windows", tickLimit = 3000)
    public void rebuilt_chamber_prepare_participant_close_still_cancels_windows(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            releaseAndDismantle(context, origin, chamber, SealedReason.EXISTING_INELIGIBLE, registered, hooks, witness, (dismantledAt, sealed) -> {
                var rebuilt = rebuild(context, frame);
                context.assertTrue(rebuilt != null && !rebuilt.equals(chamber), "原地重建成新的艙：" + rebuilt);
                SessionRecoveryRecord receipt = null;
                int attempts = 0;
                while (attempts++ < 32) {
                    var candidate = origin.own(origin.receipt(rebuilt, Map.of(p.getUuid(), true), true));
                    if (visitedFirst(journal, sealed.sessionUuid(), candidate.sessionUuid())) { receipt = candidate; break; }
                    origin.owned.remove(candidate.sessionUuid());
                    M4CandidateTestAccess.removeMeasuredForTeardown(server, candidate.sessionUuid());
                }
                context.assertTrue(receipt != null, "ORDER_PRECONDITION：32 次內沒有抽到走訪順序排在舊封存列之後的收據");
                var r = receipt;
                int tries = attempts;
                at(context, dismantledAt + 1, hooks, () -> M5GameTestSupport.moveInside(p, frame));
                M5GameTestSupport.whenTicks(context, dismantledAt + 2, 1200, () -> {
                    var record = journal.flushedRecords().get(r.sessionUuid());
                    return record != null && record.state() == dev.quantumchamber.superposition.SessionState.COLLAPSING && M5GameTestSupport.doorSealed(world, frame);
                }, prepareAt -> {
                    context.assertTrue(visitedFirst(journal, sealed.sessionUuid(), r.sessionUuid()), "ORDER_PRECONDITION：關門之前舊封存列仍排在新 session 之前");
                    witness.put("participantCloses", M5GameTestSupport.interact(world, dev.quantumchamber.chamber.ChamberGeometry.localToWorld(frame, 3, 3, 0), p).isAccepted());
                    witness.put("newSessionTerminated", !journal.records().containsKey(r.sessionUuid()) && !journal.flushedRecords().containsKey(r.sessionUuid()));
                    witness.put("oneTerminalInfo", origin.capture.matching(org.apache.logging.log4j.Level.INFO, "PREPARE 期間參與者關門，塌縮已結束",
                            r.sessionUuid().toString()).size() == 1);
                    witness.put("doorClosed", M5GameTestSupport.doorAll(world, frame, false, dev.quantumchamber.chamber.MembraneState.NONE));
                    witness.put("sealedRowUnchanged", sealed.equals(journal.flushedRecords().get(sealed.sessionUuid())));
                    var failure = witness.containsValue(false) ? new GameTestException("REBUILT_PREPARE_CLOSE_NOT_CANCELLED：原地重建的艙在 PREPARE 期間參與者關門沒有終局："
                            + witness + " 收據重抽次數=" + tries + " record=" + M5GameTestSupport.state(journal, r.sessionUuid())) : null;
                    hooks.release();
                    if (failure != null) throw failure;
                    context.complete();
                }, () -> "原地重建的艙沒有塌縮到 PREPARE：record=" + M5GameTestSupport.state(journal, r.sessionUuid()), hooks);
            });
        });
    }

    /** {@code first} 在 {@code journal.flushedRecords()} 的走訪順序排在 {@code second} 之前（{@code OriginDoors.sessionAt} 走訪的就是這份 map）。 */
    private static boolean visitedFirst(SessionRecoveryState journal, UUID first, UUID second) {
        for (var record : journal.flushedRecords().values()) {
            if (record.sessionUuid().equals(first)) return true;
            if (record.sessionUuid().equals(second)) return false;
        }
        return false;
    }

    /** 塌縮流程對這個 session 的簿記（{@code CollapseService.Track}；反射，本 mod 自己的欄位）。 */
    @SuppressWarnings("unchecked")
    private static Object track(MinecraftServer server, UUID sid) {
        var service = ((Map<MinecraftServer, ?>) M4CandidateTestAccess.get(dev.quantumchamber.collapse.CollapseService.class, "INSTANCES")).get(server);
        var track = ((Map<UUID, ?>) M4CandidateTestAccess.get(service, "tracks")).get(sid);
        if (track == null) throw new GameTestException("塌縮流程沒有這個 session 的簿記：" + sid);
        return track;
    }

    /**
     * 啟用中或供電時的保護：power state 不是 OFF、比較器 11、艙體不可寫，原生 setter 拆不掉 Controller、registry 仍有這座艙。
     * T4B Step 0（T4A 審查 M-4）：先記下狀態再試拆；對照不成立時立即以具名錯因丟出（{@code PERMANENT_SEAL_RELEASED_WHILE_PROTECTED}：試拆之前就已不受保護；
     * {@code PERMANENT_SEAL_DISMANTLED_WHILE_PROTECTED}：試拆真的拆掉了），不讓後續步驟以無關的錯因失敗。呼叫端在 {@code at} 內，丟出時 hooks 先釋放。
     */
    private static void protectedNow(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberFrame frame, UUID chamber, String step,
            Map<String, Boolean> witness) {
        var registry = ChamberRegistryState.get(world.getServer()).registry();
        var pos = frame.controllerPos();
        var power = power(registry, chamber);
        int comparator = M7RearmGameTests.comparator(world, frame);
        boolean writable = ChamberProtectionService.get().mayMutate(world, pos.down(3));
        boolean registered = registry.records().containsKey(chamber);
        if (power == null || power == ChamberPowerState.OFF || comparator != 11 || writable || !registered)
            throw new GameTestException("PERMANENT_SEAL_RELEASED_WHILE_PROTECTED：" + step + " 應受保護，試拆之前就不成立：power=" + power + " comparator=" + comparator
                    + " 可寫=" + writable + " 已登記=" + registered + " witness=" + witness);
        boolean removed = world.setBlockState(pos, Blocks.AIR.getDefaultState()) || !world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER);
        if (removed || !registry.records().containsKey(chamber))
            throw new GameTestException("PERMANENT_SEAL_DISMANTLED_WHILE_PROTECTED：" + step + " 原生 setter 拆掉了受保護的 Controller：已登記="
                    + registry.records().containsKey(chamber) + " witness=" + witness);
        witness.put(step, true);
    }

    private static ChamberPowerState power(dev.quantumchamber.chamber.ChamberRegistry registry, UUID chamber) {
        var record = registry.records().get(chamber);
        return record == null ? null : record.powerState();
    }

    @FunctionalInterface interface Dismantled { void accept(int tick, SessionRecoveryRecord sealed); }

    /**
     * 以 {@code reason} 的永久封存 fixture 封住已登記的艙，停用並斷電，3 tick 之後（OFF 已核對）以原生 setter 拆除 Controller，交出拆除的 tick 與封存列。
     * 任一步不成立時記下 witness 並失敗。
     */
    static SessionRecoveryRecord releaseAndDismantle(TestContext context, DormantOrigin origin, UUID chamber, SealedReason reason, int registered,
            M2CorridorGameTests.TestHooks hooks, Map<String, Boolean> witness, Dismantled next) {
        var server = context.getWorld().getServer(); var world = context.getWorld(); var frame = origin.frame;
        var registry = ChamberRegistryState.get(server).registry();
        var sealed = M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(UUID.randomUUID(), true), reason,
                origin.owned);
        context.assertTrue(new ChamberLifecycleService(registry).setEnabled(chamber, false), "管理員停用原艙");
        context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
        at(context, registered + 3, hooks, () -> {
            witness.put("releasedToOff", power(registry, chamber) == ChamberPowerState.OFF);
            witness.put("dismantled", world.setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState()) && !registry.records().containsKey(chamber));
            context.assertTrue(!witness.containsValue(false), "永久封存的解除與拆除：" + witness);
            next.accept(registered + 3, sealed);
        });
        return sealed;
    }

    /** 在原處重新放 Controller（朝向同原艙）並恢復供電，以 Controller 的刷新登記成新的原艙；回傳新的 UUID（沒有登記時 null）。 */
    static UUID rebuild(TestContext context, dev.quantumchamber.chamber.ChamberFrame frame) {
        var world = context.getWorld();
        var pos = frame.controllerPos();
        context.assertTrue(world.setBlockState(pos, ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing())),
                "原地重建 Controller");
        context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
        new ChamberPowerCoordinator().refresh(world, pos);
        return world.getBlockEntity(pos) instanceof dev.quantumchamber.chamber.ChamberControllerBlockEntity controller ? controller.chamberUuid() : null;
    }

    /**
     * 封存列與各 ledger 的磁碟證據：journal 檔中這個 SID 的列（SNBT 與 SHA-256），projection／allocation／discovery 等 {@code quantumchamber_} 資料檔的 SHA-256
     * （registry 與 journal 本身除外：registry 會因 OFF 與移除而改寫，journal 只比對這一列），以及 HOME 收據目錄的檔名。
     */
    record Evidence(String row, Map<String, String> files) {
        static final String ABSENT = "ABSENT";

        static Evidence of(MinecraftServer server, UUID sid) {
            var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
            var files = new TreeMap<String, String>();
            try (Stream<Path> list = Files.list(data)) {
                for (var file : list.sorted().toList()) {
                    var name = file.getFileName().toString();
                    if (Files.isDirectory(file)) {
                        if (name.equals("quantumchamber_home_receipts")) try (Stream<Path> receipts = Files.list(file)) {
                            files.put(name + "/", String.join(",", receipts.map(entry -> entry.getFileName().toString()).sorted().toList()));
                        }
                        continue;
                    }
                    if (!name.startsWith("quantumchamber_") || name.equals("quantumchamber_chambers.dat") || name.equals(SessionRecoveryState.STATE_ID + ".dat")) continue;
                    files.put(name, sha256(Files.readAllBytes(file)));
                }
                return new Evidence(diskRow(data.resolve(SessionRecoveryState.STATE_ID + ".dat"), sid), files);
            } catch (IOException failure) { throw new IllegalStateException("讀不到磁碟證據", failure); }
        }

        private static String diskRow(Path journal, UUID sid) throws IOException {
            var rows = NbtIo.readCompressed(journal, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data").getList("Records", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.getCompound(i);
                if (row.containsUuid("SessionUuid") && row.getUuid("SessionUuid").equals(sid)) return row + " sha256=" + sha256(bytes(row));
            }
            return ABSENT;
        }

        private static byte[] bytes(NbtCompound row) throws IOException {
            var out = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(out)) { NbtIo.write(row, data); }
            return out.toByteArray();
        }

        private static String sha256(byte[] bytes) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
