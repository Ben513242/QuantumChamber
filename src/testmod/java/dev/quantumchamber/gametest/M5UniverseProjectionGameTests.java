package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;
import static dev.quantumchamber.gametest.M5UniverseHold.asTestFailure;
import static dev.quantumchamber.gametest.M5UniverseHold.awaitConvergedThenComplete;
import static dev.quantumchamber.gametest.M5UniverseHold.begin;
import static dev.quantumchamber.gametest.M5UniverseSupport.PREPARE_WAIT_TICKS;
import static dev.quantumchamber.gametest.M5UniverseSupport.allocationEntries;
import static dev.quantumchamber.gametest.M5UniverseSupport.bytes;
import static dev.quantumchamber.gametest.M5UniverseSupport.collapsedToUniverse;
import static dev.quantumchamber.gametest.M5UniverseSupport.entered;
import static dev.quantumchamber.gametest.M5UniverseSupport.local;
import static dev.quantumchamber.gametest.M5UniverseSupport.newCollapse;
import static dev.quantumchamber.gametest.M5UniverseSupport.openOrigin;
import static dev.quantumchamber.gametest.M5UniverseSupport.outside;
import static dev.quantumchamber.gametest.M5UniverseSupport.passageOpened;
import static dev.quantumchamber.gametest.M5UniverseSupport.preloaded;
import static dev.quantumchamber.gametest.M5UniverseSupport.prepared;
import static dev.quantumchamber.gametest.M5UniverseSupport.releaseThenComplete;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealedWith;

import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberLoadSyncTestAccess;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.gametest.M5UniverseSupport.ResolvedUniverse;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 run 的 GameTest：投影的第一次寫入、修復、不變量 cell 的保護與 Controller 的辨識（F4b-1），以及 PREPARE（F4b-2 起：預載、投影、
 * checkpoint、衝突與寫入失敗的 SEALED、重試）。由 {@link M5UniverseGameTests} 依主題純搬移拆出（F6-3c-2，盤點 #06）；test 的方法名稱、batchId、tickLimit 與 {@code @GameTest} 的屬性都不變，寫法的規則見
 * {@link M5UniverseGameTests} 的類別說明與 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 */
public final class M5UniverseProjectionGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    // ---- F4b-1：投影元件（ProjectionWriter、投影保護索引、保護、載入辨識） ----

    /**
     * F4b-1（spec §9.2；plan 決策 3；plan Step 1 第 2 組的「清 interior、箱子不噴物品、逐格驗證」與第 6 組的動物）：在真的 RESOLVE 建立的宇宙 U、原艙的同 XYZ
     * 同朝向，以 {@code ProjectionWriter} 的第一次寫入寫出投影。寫入之前，volume 內有兩個裝了東西的箱子（interior 一個、shell cell 一個）、interior 的石頭、
     * 一隻（不受傷的）豬與一位旁觀玩家；volume 外放兩個偵測器：看著一格 shell 的觀察者（抓形狀更新，即沒有 {@code FORCE_STATE}），以及懸空在另一格 shell
     * 旁邊的紅石線（抓鄰格的方塊更新，即 {@code NOTIFY_NEIGHBORS}：它一收到就會因為沒有支撐而掉落）。寫完：WRITTEN；343 格逐格是 spec 的目標（test 自己依
     * spec 寫出）；Controller 有預設的 block entity（沒有 chamber UUID）；沒有任何掉落物；觀察者沒有排程、沒有輸出；紅石線還在；豬沒有被移動或移除；旁觀
     * 玩家不擋寫入。catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_first_write", tickLimit = 400)
    public void projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates(TestContext context) {
        begin(context, "projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            var witness = new LinkedHashMap<String, Boolean>();
            var interiorChest = local(frame, 3, 1, 3); var shellChest = local(frame, 0, 4, 3); var interiorStone = local(frame, 2, 2, 2);
            var watched = local(frame, 0, 3, 3); var observer = outside(frame, -1, 3, 3);
            var wired = local(frame, 6, 2, 3); var wire = outside(frame, 7, 2, 3); var support = outside(frame, 7, 1, 3);
            ChamberProtectionService.get().authorizedMutation(() -> {
                for (var chest : List.of(interiorChest, shellChest)) {
                    universe.setBlockState(chest, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
                    ((ChestBlockEntity) universe.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
                }
                universe.setBlockState(interiorStone, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                universe.setBlockState(observer, Blocks.OBSERVER.getDefaultState()
                        .with(ObserverBlock.FACING, toward(observer, watched)), Block.NOTIFY_ALL);
                // 懸空的紅石線：先放在支撐上，再以不送更新的寫法拿掉支撐。
                universe.setBlockState(wire, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                universe.setBlockState(support, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                universe.setBlockState(wire, Blocks.REDSTONE_WIRE.getDefaultState(), Block.NOTIFY_ALL);
                universe.setBlockState(support, Blocks.AIR.getDefaultState(),
                        Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            });
            var pig = EntityType.PIG.create(universe);
            var pigAt = ChamberSpaceCoordinates.position(frame, 2.5, 3, 4.5);
            pig.refreshPositionAndAngles(pigAt.x, pigAt.y, pigAt.z, 0, 0);
            pig.setInvulnerable(true); pig.setNoGravity(true); pig.setAiDisabled(true);
            universe.spawnEntity(pig);
            hooks.add(pig::discard);
            var spectator = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(spectator::close);
            spectator.player().changeGameMode(GameMode.SPECTATOR);
            var spectatorAt = ChamberSpaceCoordinates.position(frame, 4.5, 2, 2.5);
            spectator.player().teleport(universe, spectatorAt.x, spectatorAt.y, spectatorAt.z, Set.of(), 0, 0);
            var area = box(frame).expand(3);
            // 新產生的 chunk 的 entity storage 是非同步載入的：等到豬看得見（entity 的區段已在追蹤中），之後的掉落物檢查才有意義；同時等偵測器靜止。
            whenTicks(context, resolved + 4, 200, () -> visible(universe, pig, area)
                    && !universe.getBlockState(observer).get(ObserverBlock.POWERED)
                    && !universe.getBlockTickScheduler().isQueued(observer, Blocks.OBSERVER), settled -> {
                witness.put("fixtureSettled", universe.getBlockState(wire).isOf(Blocks.REDSTONE_WIRE) && universe.getBlockState(support).isAir()
                        && items(universe, area) == 0 && spectator.player().getServerWorld() == universe);
                var pigBefore = pig.getPos();
                var spectatorBefore = spectator.player().getPos();
                var result = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
                witness.put("written", result.status() == ProjectionWriter.Status.WRITTEN && result.written() > 0 && result.blockingPlayers().isEmpty());
                witness.put("everyCellIsTheSpecTarget", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
                witness.put("controllerHasADefaultBlockEntity", universe.getBlockEntity(frame.controllerPos())
                        instanceof ChamberControllerBlockEntity controller && controller.chamberUuid() == null);
                witness.put("chestsDroppedNothing", items(universe, area) == 0);
                witness.put("observerNotScheduled", !universe.getBlockTickScheduler().isQueued(observer, Blocks.OBSERVER));
                witness.put("floatingWireStays", universe.getBlockState(wire).isOf(Blocks.REDSTONE_WIRE));
                witness.put("pigNotMovedOrRemoved", !pig.isRemoved() && pig.getPos().equals(pigBefore) && visible(universe, pig, area));
                witness.put("spectatorNotMoved", spectator.player().getPos().equals(spectatorBefore));
                LOGGER.info("M5_PROJECTION_FIRST_WRITE settled={} status={} written={} mismatched={}", settled - resolved, result.status(), result.written(),
                        result.mismatched().size());
                at(context, settled + 4, hooks, () -> {
                    witness.put("observerNeverPulsed", !universe.getBlockState(observer).get(ObserverBlock.POWERED)
                            && !universe.getBlockTickScheduler().isQueued(observer, Blocks.OBSERVER));
                    witness.put("wireStillThere", universe.getBlockState(wire).isOf(Blocks.REDSTONE_WIRE));
                    witness.put("nothingDroppedLater", items(universe, area) == 0);
                    witness.put("pigStillThere", !pig.isRemoved() && visible(universe, pig, area));
                    hooks.release();
                    context.assertTrue(!witness.containsValue(false), "投影的第一次寫入：" + witness);
                    awaitConvergedThenComplete(context, 1, 20);
                });
            }, () -> "entity storage 沒有載入或偵測器沒有靜止：pigVisible=" + visible(universe, pig, area), hooks);
        });
    }

    /** {@code entity} 在 {@code area} 內、而且以原版的 entity 查詢看得到（它所在的區段已在追蹤中）。 */
    private static boolean visible(ServerWorld world, Entity entity, Box area) {
        return world.getEntitiesByClass(entity.getClass(), area, candidate -> candidate == entity).size() == 1;
    }

    /**
     * F4b-1（spec §9.2 的既有 entity 與兩種寫法；plan Step 1 第 2 組的「修復只動不變量 cell、保留 interior」與第 6 組的玩家）：在宇宙 U 的投影位置，
     * <ol>
     * <li>第一次寫入時，一位（創造模式、不受傷的）玩家 Q 站在 volume 內——他所在的兩格事先已清成 AIR，本來就不需要寫——仍然擋住寫入：
     *     BLOCKED_BY_PLAYER（帶 Q）、343 格一格都沒有改變（第一次寫入的檢查範圍是整個 7×7×7）；Q 移到 volume 外、Controller 的位置先放一座方塊狀態與目標
     *     相同但帶 chamber UUID 的 Controller 之後 WRITTEN：那座 Controller 仍被重寫，block entity 換成預設值（沒有 chamber UUID，不沿用既有身分）；
     *     Controller 的位置由 ABSENT 變成 PRESENT（§6.5 實體交叉檢查用的查詢）。</li>
     * <li>修復：兩格 shell 損壞（AIR、石頭）、25 格門關閉、interior 放一塊玻璃，Q 站在 interior → WRITTEN、恰好改了 27 格；不變量 cell 全部回到目標（門回到
     *     開啟），interior 的玻璃保留，Q 沒有被移動（interior 的玩家不擋修復）。</li>
     * <li>修復被擋：Q 站在一格要寫的損壞 shell 裡 → BLOCKED_BY_PLAYER，兩格損壞都沒有寫；Q 改站在已開啟的門口（門已是目標，不需要寫）→ WRITTEN（修復只看
     *     這次要寫的格）。</li>
     * <li>驗證失敗：同 X／Z、頂端超出世界高度的 frame → VERIFY_FAILED，不符的格都在世界高度之外，帶 failure。</li>
     * <li>未載入：很遠的 frame → NOT_LOADED，沒有載入那個 chunk；Controller 位置的查詢也是 NOT_LOADED。</li>
     * </ol>
     * catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_repair", tickLimit = 400)
    public void projection_writer_waits_for_players_and_repairs_only_the_invariant_cells(TestContext context) {
        begin(context, "projection_writer_waits_for_players_and_repairs_only_the_invariant_cells");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            var witness = new LinkedHashMap<String, Boolean>();
            var connection = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(connection::close);
            var q = connection.player();
            q.changeGameMode(GameMode.CREATIVE); q.setNoGravity(true);
            var air = Blocks.AIR.getDefaultState();
            // 1. 第一次寫入：Q 在 volume 內、站在已是目標（AIR）的兩格。
            universe.setBlockState(local(frame, 3, 1, 3), air); universe.setBlockState(local(frame, 3, 2, 3), air);
            standAt(q, universe, frame, 3.5, 1, 3.5);
            witness.put("controllerAbsentBefore", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
            var before = cells(universe, frame);
            var blocked = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("firstWriteBlockedByQ", blocked.status() == ProjectionWriter.Status.BLOCKED_BY_PLAYER && blocked.blockingPlayers().equals(List.of(q.getUuid()))
                    && blocked.written() == 0);
            witness.put("nothingWrittenWhileBlocked", before.equals(cells(universe, frame)));
            standAt(q, universe, frame, 3.5, 1, -4.5);
            // Controller 的位置上已有一座方塊狀態與目標相同、帶 chamber UUID 的 Controller：第一次寫入仍要重寫它，換成預設的 block entity。
            var stale = ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                    .with(ChamberControllerBlock.FACING, frame.outwardFacing());
            universe.setBlockState(frame.controllerPos(), stale);
            var staleController = Objects.requireNonNull(M5GameTestSupport.controller(universe, frame), "既有 Controller 的 block entity");
            staleController.setChamberUuid(UUID.randomUUID());
            witness.put("staleControllerHasAnIdentity", staleController.chamberUuid() != null
                    && universe.getBlockState(frame.controllerPos()).equals(M5GameTestSupport.projectionTarget(frame, 3, 6, 0)));
            var first = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("firstWriteAfterQLeft", first.status() == ProjectionWriter.Status.WRITTEN && M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
            var rewritten = M5GameTestSupport.controller(universe, frame);
            witness.put("existingControllerRewrittenWithADefaultBlockEntity", rewritten != null && rewritten != staleController && staleController.isRemoved()
                    && rewritten.chamberUuid() == null);
            witness.put("controllerPresentAfter", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.PRESENT);
            // 2. 修復：損壞兩格 shell、關門、interior 放玻璃；Q 在 interior。
            var holed = local(frame, 0, 3, 3); var stoned = local(frame, 6, 4, 2); var glass = local(frame, 2, 2, 2);
            damage(universe, frame, holed, stoned, true);
            ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(glass, Blocks.GLASS.getDefaultState()));
            standAt(q, universe, frame, 3.5, 1, 3.5);
            var qInside = q.getPos();
            var repaired = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("repairWritesExactlyTheDamagedInvariantCells", repaired.status() == ProjectionWriter.Status.WRITTEN && repaired.written() == 27);
            witness.put("invariantCellsRestoredDoorOpen", M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty()
                    && M5GameTestSupport.doorAll(universe, frame, true, MembraneState.NONE));
            witness.put("interiorKept", universe.getBlockState(glass).isOf(Blocks.GLASS));
            witness.put("interiorPlayerNotMoved", q.getPos().equals(qInside));
            // 3. 修復被擋（Q 在要寫的損壞 shell 裡），之後 Q 站在已開啟的門口也不擋。
            damage(universe, frame, holed, stoned, false);
            standAt(q, universe, frame, 0.5, 3, 3.5);
            var blockedRepair = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("repairBlockedByAPlayerInADamagedCell", blockedRepair.status() == ProjectionWriter.Status.BLOCKED_BY_PLAYER
                    && universe.getBlockState(holed).isAir() && universe.getBlockState(stoned).isOf(Blocks.STONE));
            standAt(q, universe, frame, 3.5, 1, 0.5);
            var doorway = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("doorwayPlayerDoesNotBlockTheRepair", doorway.status() == ProjectionWriter.Status.WRITTEN && doorway.written() == 2
                    && M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty());
            // 4. 驗證失敗：頂端超出世界高度。
            var high = new ChamberFrame(frame.controllerPos().withY(universe.getTopY() + 2), frame.outwardFacing());
            var overflow = ProjectionWriter.write(universe, high, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("verifyFailedAboveTheBuildLimit", overflow.status() == ProjectionWriter.Status.VERIFY_FAILED && overflow.failure().isPresent()
                    && !overflow.mismatched().isEmpty() && overflow.mismatched().stream().allMatch(pos -> pos.getY() >= universe.getTopY()));
            // 5. 未載入：很遠的 frame。
            var far = new ChamberFrame(frame.controllerPos().add(100_000, 0, 0), frame.outwardFacing());
            var unloaded = ProjectionWriter.write(universe, far, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("notLoadedWithoutLoading", unloaded.status() == ProjectionWriter.Status.NOT_LOADED
                    && ProjectionWriter.controllerPresence(universe, far) == ProjectionWriter.ControllerPresence.NOT_LOADED
                    && universe.getChunkManager().getWorldChunk(far.controllerPos().getX() >> 4, far.controllerPos().getZ() >> 4) == null);
            LOGGER.info("M5_PROJECTION_REPAIR blocked={} first={} repaired={}/{} blockedRepair={} doorway={}/{} overflow={}/{} unloaded={}", blocked.status(),
                    first.status(), repaired.status(), repaired.written(), blockedRepair.status(), doorway.status(), doorway.written(), overflow.status(),
                    overflow.mismatched().size(), unloaded.status());
            hooks.release();
            context.assertTrue(!witness.containsValue(false), "投影的寫入與修復：" + witness);
            awaitConvergedThenComplete(context, 1, 20);
        });
    }

    /**
     * F4b-1（spec §9.3 的保護；plan Step 1 第 8 組的「保護（破壞、放置、爆炸、活塞、流體）」；補充第 4 點）：宇宙 U 的投影依 F4b-2 的順序經過權威與投影保護索引——
     * checked 寫入 PENDING 並登記（這時還不受保護）、第一次寫入、checked 寫入 COMPLETE 並登記（受保護）。之後：
     * <ul>
     * <li>{@code ChamberProtectionService.mayMutate}：218 個不變量 cell 一律拒絕，125 格 interior 與 volume 外放行。</li>
     * <li>投影艙的門照既有的門邏輯開關：玩家用門板把整面門關上、再打開（既有的授權寫入）。</li>
     * <li>破壞：創造模式的玩家（直接走原版的 {@code tryBreakBlock}）打 shell、Controller 與門板都不會消失；interior 裡的石頭照常打掉。</li>
     * <li>放置：一格 shell 被授權寫入損壞成 AIR（模擬待修復的投影）之後，玩家對它放置石頭被拒、仍是 AIR；interior 照常放得下。</li>
     * <li>爆炸：一格 shell 換成玻璃（授權寫入）、相鄰的 interior 也放玻璃，在 interior 的玻璃上爆炸：interior 的玻璃消失，shell 的玻璃留著。</li>
     * <li>活塞：volume 外三台通電的活塞分別對著 shell、門板與 Controller：都推不動（不變量 cell 都是推不動的方塊，這條由既有的方塊設定擋下，
     *     關掉索引的 RED 也推不動；寫入也經過同一個保護）；對照組的活塞照常推動石頭，證明這裡的觀察看得到推動。另一台活塞把石頭推向一格損壞成 AIR 的
     *     shell：那一格的寫入被拒，仍是 AIR（關掉索引時會變成石頭）。原版 {@code PistonBlock.move} 在寫入被拒之後仍對那一格 {@code addBlockEntity}，
     *     所以這一步會有一則原版 WARN（「Trying to set block entity … does not allow it」），被推的石頭不會留下（活塞頭停在石頭原本的位置）。</li>
     * <li>流體：一格 shell 損壞成 AIR，外側相鄰放水源，水源南北兩側用石頭擋住，只能往西（損壞的 shell）與往東（對照組的空格）流，兩邊離落差一樣近：
     *     水流不進那一格；對照組的空格照常有水。</li>
     * <li>修復受保護的投影：上面四類留下的 4 格損壞（AIR、玻璃）由寫入器的修復寫回：WRITTEN、恰好 4 格，不變量 cell 全部符合、門開著，interior 放的石頭
     *     保留，之後仍受保護。寫入器若不在授權之內寫，會被這個保護拒絕而 VERIFY_FAILED。</li>
     * </ul>
     * 新 chunk 的鄰居是非同步產生的（GameTest server 不以 20 TPS 節流），活塞的方塊事件與流體的排程刻要等 chunk 開始 block ticking 才會執行，
     * 所以先等投影周圍強制載入的 chunk 都 block ticking 再佈置；活塞與流體的判定也等到對照組的活塞推動完成、水源流動過之後（有界等待），
     * 不用固定的 tick 數。catalog 增量 1（U）。
     * <p>tickLimit 600（F4b-2 Step 0，F4b-1 quality Q-m5）：有界等待最壞的總和約 486 tick（註冊 ≤43、RESOLVE ≤+22、block ticking ≤+200、
     * 對照組 ≤+200、收斂 ≤+20）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_protection", tickLimit = 600)
    public void projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids(TestContext context) {
        begin(context, "projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var chunks = M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            whenTicks(context, resolved, 200, () -> M5GameTestSupport.blockTicking(universe, chunks),
                    ticking -> projectionProtection(context, hooks, universe, frame, chamber, id, ticking),
                    () -> "投影周圍強制載入的 chunk 沒有開始 block ticking：" + chunks, hooks);
        });
    }

    /** {@link #projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids} 在投影周圍的 chunk 開始 block ticking（{@code ticking}）之後的本體。 */
    private static void projectionProtection(TestContext context, M2CorridorGameTests.TestHooks hooks, ServerWorld universe,
            ChamberFrame frame, UUID chamber, UniverseId id, int ticking) {
        var server = context.getWorld().getServer();
        var witness = new LinkedHashMap<String, Boolean>();
        var protection = ChamberProtectionService.get();
        var authorities = CollapseAuthorities.get(server);
        var index = ProjectionProtectionIndex.get(server);
        var shell = local(frame, 0, 3, 3);
        // F4b-2 的順序：PENDING 確認並登記 → 寫方塊 → COMPLETE 確認並登記。
        var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, frame.controllerPos(),
                frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
        witness.put("pendingConfirmed", pending.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(pending);
        witness.put("pendingNotProtected", protection.mayMutate(universe, shell));
        witness.put("firstWrite", ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
        var complete = authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
        witness.put("completeConfirmed", complete.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(complete);
        int denied = 0, allowed = 0;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            boolean may = protection.mayMutate(universe, local(frame, x, y, z));
            if (ChamberGeometry.isShellCell(x, y, z) && !may) denied++;
            if (!ChamberGeometry.isShellCell(x, y, z) && may) allowed++;
        }
        int deniedCells = denied, allowedCells = allowed;
        witness.put("everyInvariantCellDenied", deniedCells == 218);
        witness.put("interiorAllowed", allowedCells == 125);
        witness.put("outsideAllowed", protection.mayMutate(universe, outside(frame, 3, 2, -2)) && protection.mayMutate(universe, outside(frame, 3, 7, 3)));
        var connection = new ConnectedGameTestPlayer(context.getWorld());
        hooks.add(connection::close);
        var b = connection.player();
        b.changeGameMode(GameMode.CREATIVE); b.setNoGravity(true);
        standAt(b, universe, frame, 3.5, 1, 3.5);
        // 門：既有的門邏輯（門板的 onUse → 整面門授權切換）。
        var doorCell = local(frame, 3, 3, 0);
        witness.put("doorClosesByTheExistingLogic", interact(universe, doorCell, b).isAccepted() && doorAll(universe, frame, false, MembraneState.NONE));
        witness.put("doorOpensByTheExistingLogic", interact(universe, doorCell, b).isAccepted() && doorAll(universe, frame, true, MembraneState.NONE));
        // 破壞。
        for (var target : List.of(shell, frame.controllerPos(), doorCell)) b.interactionManager.tryBreakBlock(target);
        witness.put("breakingRefused", universe.getBlockState(shell).isOf(Blocks.BEDROCK)
                && universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                && M5GameTestSupport.doorIs(universe, doorCell, true, MembraneState.NONE));
        var interiorStone = local(frame, 2, 1, 2);
        universe.setBlockState(interiorStone, Blocks.STONE.getDefaultState());
        b.interactionManager.tryBreakBlock(interiorStone);
        witness.put("interiorBreaks", universe.getBlockState(interiorStone).isAir());
        // 放置：損壞成 AIR 的不變量 cell 不能放；interior 可以。
        protection.authorizedMutation(() -> universe.setBlockState(shell, Blocks.AIR.getDefaultState()));
        b.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.STONE, 64));
        placeOnTop(b, universe, local(frame, 0, 2, 3));
        witness.put("placingIntoAnInvariantCellRefused", universe.getBlockState(shell).isAir());
        placeOnTop(b, universe, local(frame, 2, 0, 3));
        witness.put("interiorPlacementWorks", universe.getBlockState(local(frame, 2, 1, 3)).isOf(Blocks.STONE));
        // 爆炸：shell 的玻璃留著，interior 的玻璃消失。
        var shellGlass = local(frame, 6, 3, 3); var interiorGlass = local(frame, 5, 3, 3);
        protection.authorizedMutation(() -> {
            universe.setBlockState(shellGlass, Blocks.GLASS.getDefaultState());
            universe.setBlockState(interiorGlass, Blocks.GLASS.getDefaultState());
        });
        var blast = ChamberSpaceCoordinates.position(frame, 5.5, 3.5, 3.5);
        universe.createExplosion(null, blast.x, blast.y, blast.z, 2, World.ExplosionSourceType.BLOCK);
        witness.put("explosionKeepsTheInvariantCell", universe.getBlockState(shellGlass).isOf(Blocks.GLASS));
        witness.put("explosionClearsTheInterior", universe.getBlockState(interiorGlass).isAir());
        // 活塞：對著 shell、門板與 Controller 的活塞推不動；對照組照常推動石頭。
        var pistons = new ArrayList<BlockPos>();
        pistons.add(piston(universe, outside(frame, 7, 2, 4), local(frame, 6, 2, 4), outside(frame, 8, 2, 4)));
        pistons.add(piston(universe, outside(frame, 3, 2, -1), local(frame, 3, 2, 0), outside(frame, 3, 2, -2)));
        pistons.add(piston(universe, outside(frame, 3, 7, 0), frame.controllerPos(), outside(frame, 3, 8, 0)));
        var controlStone = outside(frame, 10, 2, 3); var controlTarget = outside(frame, 9, 2, 3);
        universe.setBlockState(controlTarget, Blocks.AIR.getDefaultState());
        universe.setBlockState(controlStone, Blocks.STONE.getDefaultState());
        var controlPiston = piston(universe, outside(frame, 11, 2, 3), controlStone, outside(frame, 12, 2, 3));
        // 活塞把石頭推進損壞成 AIR 的不變量 cell：那一格的寫入被拒，仍是 AIR。
        var pushedInto = local(frame, 6, 2, 2); var pushedStone = outside(frame, 7, 2, 2);
        protection.authorizedMutation(() -> universe.setBlockState(pushedInto, Blocks.AIR.getDefaultState()));
        universe.setBlockState(pushedStone, Blocks.STONE.getDefaultState());
        var intoDamaged = piston(universe, outside(frame, 8, 2, 2), pushedStone, outside(frame, 9, 2, 2));
        // 流體：損壞成 AIR 的不變量 cell 不進水；對照組的外側空格有水。水源南北兩側擋住，只剩往西（dry）與往東（wet）兩個方向，
        // 兩邊下一格都有落差（dry 那側是爆炸清掉的 interior 玻璃，wet 那側是外面的空格），流向一樣優先。
        var dry = local(frame, 6, 4, 3); var source = outside(frame, 7, 4, 3); var wet = outside(frame, 8, 4, 3);
        protection.authorizedMutation(() -> universe.setBlockState(dry, Blocks.AIR.getDefaultState()));
        for (var solid : List.of(outside(frame, 7, 3, 3), outside(frame, 8, 3, 3), outside(frame, 7, 4, 2), outside(frame, 7, 4, 4))) {
            universe.setBlockState(solid, Blocks.STONE.getDefaultState());
        }
        universe.setBlockState(wet, Blocks.AIR.getDefaultState());
        universe.setBlockState(source, Blocks.WATER.getDefaultState());
        // 等對照組的活塞真的推動（方塊事件與 block entity 都跑過）、水源也流動過（排程刻跑過；同一次流動同時往 dry 與 wet）再判定。
        BooleanSupplier controlPistonPushed = () -> universe.getBlockState(controlPiston).get(PistonBlock.EXTENDED)
                && universe.getBlockState(controlTarget).isOf(Blocks.STONE);
        BooleanSupplier controlFluidFlowed = () -> !universe.getFluidState(wet).isEmpty();
        BooleanSupplier waterMoved = () -> controlFluidFlowed.getAsBoolean() || !universe.getFluidState(dry).isEmpty();
        // 推向損壞 cell 的活塞也伸出、移動中的方塊都已落定（沒有 MOVING_PISTON）才判定。
        BooleanSupplier pushSettled = () -> universe.getBlockState(intoDamaged).get(PistonBlock.EXTENDED)
                && !universe.getBlockState(pushedInto).isOf(Blocks.MOVING_PISTON)
                && !universe.getBlockState(pushedStone).isOf(Blocks.MOVING_PISTON);
        whenTicks(context, ticking + 1, 200, () -> controlPistonPushed.getAsBoolean() && waterMoved.getAsBoolean() && pushSettled.getAsBoolean(), settled -> {
            witness.put("pistonsCannotMoveInvariantCells", universe.getBlockState(local(frame, 6, 2, 4)).isOf(Blocks.BEDROCK)
                    && M5GameTestSupport.doorIs(universe, local(frame, 3, 2, 0), true, MembraneState.NONE)
                    && universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                    && pistons.stream().noneMatch(pos -> universe.getBlockState(pos).get(PistonBlock.EXTENDED)));
            witness.put("controlPistonPushes", controlPistonPushed.getAsBoolean());
            witness.put("pistonCannotPushIntoADamagedInvariantCell", universe.getBlockState(pushedInto).isAir());
            witness.put("fluidCannotEnterAnInvariantCell", universe.getBlockState(dry).isAir() && universe.getFluidState(dry).isEmpty());
            witness.put("controlFluidFlows", controlFluidFlowed.getAsBoolean());
            LOGGER.info("M5_PROJECTION_PROTECTION controlsSettledAfter={} denied={} allowed={} pushedInto={} pushedStone={}", settled - ticking, deniedCells,
                    allowedCells, universe.getBlockState(pushedInto), universe.getBlockState(pushedStone));
            // 修復已受保護的 COMPLETE 投影（EXISTING 再次造訪的路徑）：寫入器必須在授權之內寫，否則被自己的保護拒絕而 VERIFY_FAILED。
            // 損壞的不變量 cell：放置 test 的 local(0,3,3)、爆炸 test 的玻璃 local(6,3,3)、流體 test 的 local(6,4,3)、活塞 test 的 local(6,2,2)。
            var repair = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("repairOfTheProtectedProjectionWritten", repair.status() == ProjectionWriter.Status.WRITTEN && repair.written() == 4);
            witness.put("repairRestoresEveryInvariantCell", M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty()
                    && doorAll(universe, frame, true, MembraneState.NONE));
            witness.put("repairKeepsTheInteriorChange", universe.getBlockState(local(frame, 2, 1, 3)).isOf(Blocks.STONE));
            witness.put("stillProtectedAfterTheRepair", !protection.mayMutate(universe, shell));
            LOGGER.info("M5_PROJECTION_PROTECTED_REPAIR status={} written={} mismatched={}", repair.status(), repair.written(), repair.mismatched().size());
            hooks.release();
            context.assertTrue(!witness.containsValue(false), "投影的保護：" + witness + " denied=" + deniedCells + " allowed=" + allowedCells);
            awaitConvergedThenComplete(context, 1, 20);
        }, () -> "對照組沒有發生：controlPistonPushed=" + controlPistonPushed.getAsBoolean() + " waterMoved=" + waterMoved.getAsBoolean()
                + " piston=" + universe.getBlockState(controlPiston) + " target=" + universe.getBlockState(controlTarget) + " source=" + universe.getFluidState(source)
                + " wet=" + universe.getBlockState(wet) + " blockTicking=" + universe.shouldTickBlockPos(controlPiston) + "/" + universe.shouldTickBlockPos(wet)
                + " tickingFuture=" + universe.getChunkManager().isTickingFutureReady(ChunkPos.toLong(controlPiston))
                + "/" + universe.getChunkManager().isTickingFutureReady(ChunkPos.toLong(wet)) + " time=" + universe.getTime()
                + " witness=" + witness, hooks);
    }

    /**
     * F4b-1（spec §9.3 的載入辨識；plan Step 1 第 8 組；補充第 6 點）：動態世界的 Controller 依投影保護索引判定，不看 block entity 的 instanceKind。
     * 宇宙 U 裡三座 Controller：
     * <ul>
     * <li>A（原艙的 frame）：PENDING 確認並登記後由寫入器第一次寫入，接著丟掉 U 的 load-sync queue（{@code discardWorld}，收據
     *     DISCARDED_WORLD_UNLOAD），只剩 {@code onBlockAdded} 排的 refresh：寫入後 4 tick 還沒有 refresh 過（{@code powerInitialized=false}、ORIGIN），
     *     之後由排程的 refresh 判定為 PROJECTION。</li>
     * <li>B（沿區域 x 軸外移 32 格的 frame、另一個 chamber UUID）：PENDING 確認並登記後下一 tick 才寫入；load-sync 收據 CONSUMED，寫入後 3 tick 已判定為
     *     PROJECTION（早於它自己 20 tick 後的排程 refresh）。chunk 載入時的 {@code BLOCK_ENTITY_LOAD} 走同一個 queue。</li>
     * <li>C（外移 64 格，不在索引裡）：直接放一座 Controller → 照舊是 foreign：ORIGIN、INVALID、比較器 0。</li>
     * </ul>
     * A、B 是 INVALID、比較器 0、沒有 chamber UUID；兩座轉為 COMPLETE 並登記之後，在 Controller 上方放紅石塊供電，45 tick 後已讀到供電（{@code wasPowered}），
     * 仍是 inert 的 PROJECTION；chamber registry 沒有 U 的紀錄、journal 沒有來源在 U 的 session。整段期間 quantumchamber logger 除了塌縮 session 本身的
     * log 之外沒有 WARN／ERROR，也沒有任何 log 提到這三座 Controller 的位置（排程的 refresh 每 20 tick 一次，不會反覆記 log）。catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_recognition", tickLimit = 600)
    public void projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added(TestContext context) {
        begin(context, "projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var a = origin.frame;
        var b = new ChamberFrame(outside(a, 35, 6, 0), a.outwardFacing());
        var c = new ChamberFrame(outside(a, 67, 6, 0), a.outwardFacing());
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var chunks = new ArrayList<ChunkPos>();
            for (var frame : List.of(a, b, c)) chunks.addAll(M5GameTestSupport.forceProjectionChunks(universe, frame, hooks));
            whenTicks(context, resolved, 200, () -> M5GameTestSupport.blockTicking(universe, chunks),
                    ticking -> projectionRecognition(context, hooks, origin.capture, universe, sid, chamber, id, a, b, c, ticking),
                    () -> "投影周圍強制載入的 chunk 沒有開始 block ticking：" + chunks, hooks);
        });
    }

    /** {@link #projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added} 在 chunk 開始 block ticking（{@code ticking}）之後的本體。 */
    private static void projectionRecognition(TestContext context, M2CorridorGameTests.TestHooks hooks, LogCapture capture,
            ServerWorld universe, UUID sid, UUID chamber, UniverseId id, ChamberFrame a,
            ChamberFrame b, ChamberFrame c, int ticking) {
        var server = context.getWorld().getServer();
        var authorities = CollapseAuthorities.get(server);
        var index = ProjectionProtectionIndex.get(server);
        var witness = new LinkedHashMap<String, Boolean>();
        int mark = capture.entries().size();
        var pendingA = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, a.controllerPos(), a.outwardFacing(),
                ProjectionLedger.Entry.Status.PENDING));
        var pendingB = authorities.writeProjection(new ProjectionLedger.Entry(UUID.randomUUID(), id, DimensionRole.OVERWORLD, b.controllerPos(),
                b.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
        witness.put("pendingConfirmed", pendingA.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                && pendingB.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(pendingA);
        index.confirmed(pendingB);
        // A：寫入後丟掉 U 的 load-sync queue，只剩 onBlockAdded 排的 refresh。
        witness.put("aWritten", ProjectionWriter.write(universe, a, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
        var controllerA = Objects.requireNonNull(M5GameTestSupport.controller(universe, a), "A 的 Controller block entity");
        var receiptsA = ChamberLoadSyncTestAccess.observe(universe, controllerA);
        hooks.add(receiptsA::close);
        ChamberLoadSyncTestAccess.discardWorld(universe);
        // C：不在索引裡的 Controller。
        universe.setBlockState(c.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                .with(ChamberControllerBlock.FACING, c.outwardFacing()));
        var controllerC = Objects.requireNonNull(M5GameTestSupport.controller(universe, c), "C 的 Controller block entity");
        var receiptsC = ChamberLoadSyncTestAccess.observe(universe, controllerC);
        hooks.add(receiptsC::close);
        var consumed = ChamberLoadSyncOutcome.CONSUMED;
        var discarded = List.of(ChamberLoadSyncOutcome.DISCARDED_WORLD_UNLOAD);
        at(context, ticking + 1, hooks, () -> {
            // B：PENDING 已登記一 tick 之後才寫入。
            witness.put("bWritten", ProjectionWriter.write(universe, b, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
            var controllerB = Objects.requireNonNull(M5GameTestSupport.controller(universe, b), "B 的 Controller block entity");
            var receiptsB = ChamberLoadSyncTestAccess.observe(universe, controllerB);
            hooks.add(receiptsB::close);
            at(context, ticking + 4, hooks, () -> {
                witness.put("aNotRefreshedYet", !controllerA.powerInitialized() && controllerA.instanceKind() == ChamberInstanceKind.ORIGIN
                        && receiptsA.outcomes().equals(discarded));
                witness.put("bRecognizedByLoadSync", receiptsB.outcomes().contains(consumed) && inertProjection(universe, controllerB));
                witness.put("cForeignAfterLoadSync", receiptsC.outcomes().contains(consumed) && foreign(universe, controllerC));
                at(context, ticking + 25, hooks, () -> {
                    witness.put("aRecognizedByBlockAdded", receiptsA.outcomes().equals(discarded) && inertProjection(universe, controllerA));
                    var completeA = authorities.writeProjection(pendingA.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
                    var completeB = authorities.writeProjection(pendingB.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
                    witness.put("completeConfirmed", completeA.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                            && completeB.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
                    index.confirmed(completeA);
                    index.confirmed(completeB);
                    // 供電：Controller 上方（volume 外）放紅石塊；B 的 neighbor update 立即 refresh，A 由排程的 refresh 讀到。
                    for (var frame : List.of(a, b)) universe.setBlockState(outside(frame, 3, 7, 0), Blocks.REDSTONE_BLOCK.getDefaultState());
                    at(context, ticking + 70, hooks, () -> {
                        witness.put("poweredButInert", controllerA.wasPowered() && controllerB.wasPowered()
                                && inertProjection(universe, controllerA) && inertProjection(universe, controllerB));
                        witness.put("cStillForeign", foreign(universe, controllerC));
                        var key = universe.getRegistryKey().getValue();
                        witness.put("noRegistryRecordInU", ChamberRegistryState.get(server).registry().records().values().stream()
                                .noneMatch(record -> record.originWorldKey().equals(key)));
                        witness.put("noSessionFromU", SessionRecoveryState.get(server).records().values().stream()
                                .noneMatch(record -> record.origin().worldKey().equals(key)));
                        var entries = capture.entries();
                        var window = entries.subList(mark, entries.size());
                        var positions = List.of(a.controllerPos(), b.controllerPos(), c.controllerPos());
                        witness.put("noWarnOrErrorOutsideTheSession", window.stream().noneMatch(entry -> (entry.level() == Level.WARN
                                || entry.level() == Level.ERROR) && !entry.message().contains(sid.toString())));
                        witness.put("noLogAboutTheControllers", window.stream().noneMatch(entry -> positions.stream()
                                .anyMatch(pos -> entry.message().contains(pos.toShortString()) || entry.message().contains(pos.toString()))));
                        LOGGER.info("M5_PROJECTION_RECOGNITION a={}/{} b={}/{} c={}/{} receiptsA={} receiptsB={} receiptsC={}", controllerA.instanceKind(),
                                controllerA.chamberState(), controllerB.instanceKind(), controllerB.chamberState(), controllerC.instanceKind(),
                                controllerC.chamberState(), receiptsA.outcomes(), receiptsB.outcomes(), receiptsC.outcomes());
                        hooks.release();
                        context.assertTrue(!witness.containsValue(false), "投影 Controller 的載入辨識：" + witness);
                        awaitConvergedThenComplete(context, 1, 20);
                    });
                });
            });
        });
    }

    // ---- F4b-2：PREPARE 的目的宇宙側 ----

    /**
     * F4b-2（spec §9.1、§7.3；plan Step 1 第 1 組）：DORMANT NEW 收據 → RESOLVE 物化宇宙 U → 下一個 tick 閘門成立（P 在艙內、已供電），進入 PREPARE：
     * <ul>
     * <li>預載：塌縮流程在 U 對預載範圍（F5-1 起與傳送時的 FULL 檢查同一個推導：艙體 bbox 水平外擴 1 格、再外擴 2 格，{@code M5GameTestSupport.preloadArea}）涵蓋的每個 chunk 加上自己的 ticket（{@code quantumchamber_destination}，argument 是 session，
     *     level 33＝FULL；涵蓋範圍由 test 依 spec 自己算出）。之後以非建立模式查詢：預載完成的 INFO 記下等了幾個 tick，至少 1（同一個 tick 加 ticket、
     *     又在 server thread 上阻塞載入的話是 0），完成時每個 chunk 都是 FULL。</li>
     * <li>投影（spec §6.5、§9.2；plan Step 1 第 2 組「checkpoint 之後才 COMPLETE」；補充第 1 點第 7 步）：checked 寫入 PENDING、第一次寫入、目的 volume 的 chunks
     *     原生存檔 checkpoint、再驗不變量 cell、checked 寫入 COMPLETE。以 checkpoint 探針（{@code ChunkSaveCheckpointProbeMixin}，只看 U）記下每一次 checkpoint
     *     當下 ledger 的狀態，並在第一次 checkpoint 存檔之前把一格 shell 改成 AIR（模擬第一次寫入與 checkpoint 之間、PENDING 不受保護時的改動）：checkpoint 之後的
     *     再驗修復這一格（INFO「已修復 1 格」）、不寫 COMPLETE，下一個 tick 重做 checkpoint，之後才寫 COMPLETE。所以 checkpoint 恰好兩次、兩次都看到 PENDING，
     *     涵蓋的是 volume 的 chunks；最後 343 格都是 spec 的目標（test 自己寫出），COMPLETE 的 site 就是原艙的位置，不變量 cell 受保護、interior 不受保護，
     *     Controller 辨識為投影。</li>
     * <li>準備完成：INFO「準備完成」一則；F5-1 起同一個 tick 就 checked 提交 PASSAGE_READY（spec §10.2，取代 F4b-2 的停點與「通道尚未實作（F5）」的 WARN）：record 是
     *     PASSAGE_READY、expires＝opened＋1200，INFO「量子通道已開啟」一則；原艙門由 sealed 量子膜改為 passable 量子膜，艙內的參與者 P 收到「量子通道已開啟」；這個 session
     *     沒有任何 WARN／ERROR。之後幾個 tick 不再重做（「準備完成」仍是一則、checkpoint 仍是兩次），目的世界的 ticket 繼續持有（PASSAGE_READY 期間持有），門維持 passable。</li>
     * <li>終局之後（收尾的終局 API），下一個 tick 塌縮流程看到 session 已不在 journal，釋放目的世界的 ticket（完成條件）。</li>
     * </ul>
     * catalog 增量 1（U）。F5-1 改名（原本是 {@code prepare_new_runs_the_destination_side_then_stops_before_the_passage}，batch 不變）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_new", tickLimit = 900)
    public void prepare_new_runs_the_destination_side_then_opens_the_passage(TestContext context) {
        begin(context, "prepare_new_runs_the_destination_side_then_opens_the_passage");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var area = M5GameTestSupport.preloadArea(frame);
            var authorities = CollapseAuthorities.get(server);
            var damaged = local(frame, 6, 3, 3);
            // 每一次 checkpoint：記下 ledger 的狀態；第一次在存檔之前把一格 shell 改成 AIR。
            var probe = M5GameTestSupport.installChunkCheckpointProbe(context, universe.getRegistryKey(), 0, call -> {
                var lookup = authorities.projection(chamber, id);
                var observed = lookup.status() + lookup.value().map(entry -> " " + entry.status()).orElse("");
                if (call == 0) ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(damaged, Blocks.AIR.getDefaultState()));
                return observed;
            }, hooks);
            whenTicks(context, resolved + 1, 20, () -> !M5GameTestSupport.destinationTickets(universe, sid).isEmpty(), ticketed -> {
                var tickets = M5GameTestSupport.destinationTickets(universe, sid);
                witness.put("enteredPrepareOnce", entered(capture, sid) == 1);
                witness.put("ticketsCoverThePreloadAreaAtFull", tickets.keySet().equals(area) && tickets.values().stream().allMatch(level -> level == 33));
                whenTicks(context, ticketed, 400, () -> !prepared(capture, sid).isEmpty(), ready -> {
                    witness.put("preloadWaitedAtLeastOneTick", preloadWaitedTicks(capture, sid) >= 1);
                    witness.put("everyPreloadChunkFull", area.stream().allMatch(chunk -> universe.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null));
                    var calls = probe.calls();
                    witness.put("checkpointedTwiceBothWhilePending", calls.size() == 2 && calls.stream().allMatch(call -> call.observed().equals("FOUND PENDING")));
                    witness.put("checkpointCoversTheVolumeChunks", calls.stream().allMatch(call -> call.chunks().equals(volumeChunks(frame))));
                    witness.put("windowDamageRepairedBeforeComplete", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "已修復 1 格",
                            sid.toString()).size() == 1 && universe.getBlockState(damaged).isOf(Blocks.BEDROCK));
                    witness.put("everyCellIsTheSpecTarget", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
                    var complete = authorities.projection(chamber, id);
                    witness.put("completeAtTheOriginSite", complete.value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE
                            && entry.role() == DimensionRole.OVERWORLD && entry.controllerPos().equals(frame.controllerPos())
                            && entry.outwardFacing() == frame.outwardFacing()).isPresent());
                    var key = universe.getRegistryKey();
                    witness.put("invariantProtectedInteriorNot", ProjectionProtectionIndex.protectedCell(server, key, damaged)
                            && !ProjectionProtectionIndex.protectedCell(server, key, local(frame, 3, 3, 3)));
                    witness.put("controllerRecognized", ProjectionProtectionIndex.projectionControllerAt(server, key, frame.controllerPos()));
                    witness.put("oneReadyInfo", prepared(capture, sid).size() == 1);
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    var record = journal.flushedRecords().get(sid);
                    witness.put("readyCommittedOnce", record != null && record.state() == SessionState.PASSAGE_READY
                            && record.collapse().passageExpiresAtGameTime() == record.collapse().passageOpenedAtGameTime() + 1200
                            && passageOpened(capture, sid) == 1);
                    witness.put("openedMessageToTheParticipantInside", M5GameTestSupport.overlayKeys(origin.connection).contains(CollapseService.MESSAGE_PASSAGE_OPENED));
                    LOGGER.info("M5_PREPARE_NEW tickets={} area={} waited={} checkpoints={}", tickets.size(), area.size(), preloadWaitedTicks(capture, sid), calls);
                    witness.put("passableMembraneWhenReady", M5GameTestSupport.doorPassable(world, frame));
                    at(context, ready + 5, hooks, () -> {
                        witness.put("holdsTheTicketWhileReady", prepared(capture, sid).size() == 1 && probe.calls().size() == 2
                                && M5GameTestSupport.destinationTickets(universe, sid).keySet().equals(area)
                                && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"));
                        witness.put("passableMembraneHeld", M5GameTestSupport.doorPassable(world, frame));
                        // 收尾：終局 API 移除 record；下一個 tick 塌縮流程釋放目的世界的 ticket（完成條件）。
                        releaseThenComplete(context, hooks, witness, "PREPARE 的目的宇宙側：" + witness + " tickets=" + tickets.keySet() + " area=" + area
                                + " checkpoints=" + probe.calls(), 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    });
                }, () -> "沒有準備完成：tickets=" + M5GameTestSupport.destinationTickets(universe, sid).size() + " checkpoints=" + probe.calls()
                        + " witness=" + witness, hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-2（spec §7.3「PREPARE 進行到一半時閘門不再成立」、§7.1；plan Step 1 第 13 組的 ticket 與 WAITING）：收據有兩位原參與者 P、B（都已返還、在艙外），P 由艙外
     * 進入觸發塌縮。進入 PREPARE、預載還沒完成時，B 進艙（PREPARE 期間閘門不看邊緣，F4b-2 fix round 1，quality Q-M2），同一個 tick 斷電 → 下一個 tick 閘門失效：
     * 釋放目的世界的 ticket、退回 WAITING（INFO），record 仍是 COLLAPSING。恢復供電之後 P、B 都一直在艙內，沒有人移動，沒有邊緣觸發，不回到 PREPARE（B 在
     * PREPARE 期間的進入不算之後的邊緣：離開 PREPARE 時邊緣觸發的快照重新記下艙內的人）；P 由艙外再進入 → 回到 PREPARE，重新加上同一份 ticket、預載完成
     * （中斷的那一段沒有完成預載，所以「預載完成」只有一則），寫完投影、準備完成。RESOLVE 只有一次（「目的地已確定」恰一則），allocation entry 恰一筆，catalog
     * 增量 1（沒有第二個宇宙）。F4b-3（plan Step 1 第 13 組的門、spec §7.3 閘門失效的第 2 步）：原艙門在進入 PREPARE 的那個 tick 套用 sealed 量子膜，斷電之後改回
     * {@code open=true}＋{@code membrane=none} 的一般開門，回到 PREPARE 時重新套用。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_power", tickLimit = 900)
    public void prepare_power_loss_releases_the_destination_ticket_and_an_entry_edge_resumes_it(TestContext context) {
        begin(context, "prepare_power_loss_releases_the_destination_ticket_and_an_entry_edge_resumes_it");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var b = second.player();
        collapsedToUniverse(context, origin, hooks, List.of(b.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var area = M5GameTestSupport.preloadArea(frame);
            whenTicks(context, resolved + 1, 20, () -> !M5GameTestSupport.destinationTickets(universe, sid).isEmpty(), ticketed -> {
                witness.put("preparingWithTheTickets", entered(capture, sid) == 1 && M5GameTestSupport.destinationTickets(universe, sid).keySet().equals(area));
                witness.put("cutBeforeThePreloadCompleted", preloaded(capture, sid).isEmpty());
                // F4b-3（plan Step 1 第 13 組的門）：進入 PREPARE 的那個 tick 已套用 sealed 量子膜。
                witness.put("sealedWhilePreparing", M5GameTestSupport.doorSealed(context.getWorld(), frame));
                // PREPARE 進行到一半時 B 進艙，同一個 tick 斷電。
                moveInside(b, frame);
                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                at(context, ticketed + 1, hooks, () -> {
                    witness.put("ticketReleasedOnPowerLoss", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    // F4b-3（spec §7.3 閘門失效的第 2 步）：門由 sealed 量子膜改回 open=true＋membrane=none 的一般開門。
                    witness.put("plainOpenAfterThePowerLoss", doorAll(context.getWorld(), frame, true, MembraneState.NONE));
                    witness.put("backToWaitingWithOneInfo", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退回 WAITING",
                            "已釋放目的世界的 ticket", sid.toString()).size() == 1);
                    var record = journal.flushedRecords().get(sid);
                    witness.put("stillCollapsing", record != null && record.state() == SessionState.COLLAPSING && record.equals(journal.records().get(sid)));
                    context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                });
                at(context, ticketed + 5, hooks, () -> {
                    witness.put("noReentryWithoutAnEntryEdge", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && entered(capture, sid) == 1);
                    moveOutside(p, frame);
                });
                at(context, ticketed + 6, hooks, () -> moveInside(p, frame));
                // 回到 PREPARE 的那個 tick 重新套用 sealed（F5-1 起準備完成的同一個 tick 就提交 PASSAGE_READY、門改為 passable，所以在準備完成之前看）。
                whenTicks(context, ticketed + 7, 400, () -> entered(capture, sid) == 2, reentered -> witness.put("sealedAgainAfterTheEdge",
                        M5GameTestSupport.doorSealed(context.getWorld(), frame)),
                        () -> "邊緣觸發之後沒有回到 PREPARE：entered=" + entered(capture, sid), hooks);
                whenTicks(context, ticketed + 7, 400, () -> entered(capture, sid) == 2 && prepared(capture, sid).size() == 1, resumed -> {
                    witness.put("resumedWithTheSameTickets", M5GameTestSupport.destinationTickets(universe, sid).keySet().equals(area));
                    witness.put("onePreloadCompletedAfterTheEdge", preloaded(capture, sid).size() == 1);
                    witness.put("projectedAfterTheEdge", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty()
                            && CollapseAuthorities.get(server).projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent());
                    witness.put("resolvedOnlyOnce", capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                    witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
                    releaseThenComplete(context, hooks, witness, "PREPARE 中斷電：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                }, () -> "邊緣觸發之後沒有回到 PREPARE：record=" + M5GameTestSupport.state(journal, sid) + " entered=" + entered(capture, sid)
                        + " witness=" + witness, hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-2（spec §9.2「之後（COMPLETE；EXISTING 再次造訪）」、§6.5；補充第 5 點）：D1（DORMANT NEW）把原艙投影到宇宙 U，準備完成（COMPLETE）。之後在 U 的
     * 投影艙 interior 放一塊石頭（玩家的修改，interior 不受保護），以授權寫入把一格 shell 損壞成 AIR、把 25 格門關上（COMPLETE 的不變量 cell 受保護），再以
     * 終局 API 移除 D1。同一座原艙寫入選中 EXISTING U 的 D2，P 由艙外進入 → RESOLVE 只重驗 → PREPARE：ledger 已有本艙在 U 的 COMPLETE，PENDING 的寫入回
     * ALREADY_COMPLETE（site 已驗證）→ 修復：只寫 26 格不變量 cell（1 格 shell＋25 格門，門回到開啟），interior 的石頭保留；不寫 ledger（projection 檔的
     * bytes 不變），準備完成（INFO 記 REPAIR）。F5-1（F4b-2 spec S-M2）起修復寫了方塊，READY 之前要有一次原生存檔 checkpoint：checkpoint 恰一次、當下 D2 仍是
     * COLLAPSING，之後才提交 PASSAGE_READY（F4b-2 時修復不做 checkpoint）。D2 沒有任何 WARN／ERROR。catalog 增量 1（只有 D1 的 U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_revisit", tickLimit = 1400)
    public void existing_revisit_repairs_only_the_invariant_cells_and_keeps_the_interior(TestContext context) {
        begin(context, "existing_revisit_repairs_only_the_invariant_cells_and_keeps_the_interior");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid1, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            whenTicks(context, resolved + 1, PREPARE_WAIT_TICKS, () -> !prepared(capture, sid1).isEmpty(), first -> {
                var stone = local(frame, 2, 1, 2); var hole = local(frame, 0, 3, 3);
                universe.setBlockState(stone, Blocks.STONE.getDefaultState());
                witness.put("interiorChangeAllowed", universe.getBlockState(stone).isOf(Blocks.STONE));
                witness.put("invariantCellProtected", !ChamberProtectionService.get().mayMutate(universe, hole));
                ChamberProtectionService.get().authorizedMutation(() -> {
                    universe.setBlockState(hole, Blocks.AIR.getDefaultState());
                    for (var cell : M5GameTestSupport.door(frame)) universe.setBlockState(cell, QuantumBulkheadBlock.withOpen(universe.getBlockState(cell), false));
                });
                cleanupFixtures(server, List.of(sid1));
                moveOutside(p, frame);
                var ledgerBefore = bytes(data, ProjectionLedger.FILE_NAME);
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), id));
                var sid2 = second.sessionUuid();
                var probe = M5GameTestSupport.installChunkCheckpointProbe(context, universe.getRegistryKey(), 0, call -> M5GameTestSupport.state(journal, sid2), hooks);
                at(context, first + 1, hooks, () -> moveInside(p, frame));
                whenTicks(context, first + 2, 400, () -> !prepared(capture, sid2).isEmpty(), revisited -> {
                    witness.put("invariantCellsRepairedDoorOpen", M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty()
                            && doorAll(universe, frame, true, MembraneState.NONE));
                    witness.put("interiorKept", universe.getBlockState(stone).isOf(Blocks.STONE));
                    witness.put("repairWroteOnlyTheDamagedInvariantCells", prepared(capture, sid2).getFirst().message().contains("投影=REPAIR（寫入 26 格）"));
                    witness.put("ledgerUntouched", Arrays.equals(ledgerBefore, bytes(data, ProjectionLedger.FILE_NAME)));
                    witness.put("oneCheckpointBeforeTheReady", probe.calls().size() == 1 && probe.calls().getFirst().observed().equals("COLLAPSING")
                            && probe.calls().getFirst().chunks().equals(volumeChunks(frame)) && M5GameTestSupport.state(journal, sid2).equals("PASSAGE_READY"));
                    witness.put("completeKept", authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE
                            && entry.controllerPos().equals(frame.controllerPos()) && entry.outwardFacing() == frame.outwardFacing()).isPresent());
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid2));
                    LOGGER.info("M5_PREPARE_REVISIT ready={}", prepared(capture, sid2).getFirst().message());
                    releaseThenComplete(context, hooks, witness, "EXISTING 再次造訪：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid2).isEmpty());
                }, () -> "D2 沒有準備完成：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
            }, () -> "D1 沒有準備完成：record=" + M5GameTestSupport.state(journal, sid1), hooks);
        });
    }

    /**
     * F5-1 fix round 1（quality Q-m1；F4b-2 spec S-M2 跨 PREPARE 段落的版本）：同 {@code existing_revisit_repairs_only_the_invariant_cells_and_keeps_the_interior} 的前段（D1 把原艙投影到
     * 宇宙 U、準備完成之後，以授權寫入損壞一格 shell、關上 25 格門，再以終局 API 移除 D1；D2 選中 EXISTING U）。
     * <ul>
     * <li>第一段 PREPARE：修復寫了 26 格；第一次原生存檔 checkpoint 存檔之前（checkpoint 探針）再把那格 shell 改成 AIR，checkpoint 之後的再驗修復它（INFO「已修復 1 格」），還沒準備完成
     *     （checkpoint 存下的是損壞的那一格）。下一個 tick 斷電：閘門失效、退回 WAITING，這一段的修復沒有 checkpoint 涵蓋。</li>
     * <li>恢復供電、P 由艙外再進入 → 第二段 PREPARE：修復一格都沒有寫，但這個 session 之前寫過的方塊還沒有 checkpoint，所以 READY 之前先做一次 checkpoint（當下 D2 仍是 COLLAPSING），
     *     之後才提交 PASSAGE_READY（修正之前這一段直接提交，沒有 checkpoint）。</li>
     * </ul>
     * D2 沒有 WARN／ERROR。catalog 增量 1（只有 D1 的 U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_revisit_interrupted", tickLimit = 1700)
    public void existing_revisit_checkpoints_a_repair_interrupted_before_its_checkpoint(TestContext context) {
        begin(context, "existing_revisit_checkpoints_a_repair_interrupted_before_its_checkpoint");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid1, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, PREPARE_WAIT_TICKS, () -> !prepared(capture, sid1).isEmpty(), first -> {
                var hole = local(frame, 0, 3, 3);
                ChamberProtectionService.get().authorizedMutation(() -> {
                    universe.setBlockState(hole, Blocks.AIR.getDefaultState());
                    for (var cell : M5GameTestSupport.door(frame)) universe.setBlockState(cell, QuantumBulkheadBlock.withOpen(universe.getBlockState(cell), false));
                });
                cleanupFixtures(server, List.of(sid1));
                moveOutside(p, frame);
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), id));
                var sid2 = second.sessionUuid();
                // 第一次 checkpoint 存檔之前把那格 shell 再改成 AIR（checkpoint 存下損壞的狀態，之後的再驗修復它）；之後的 checkpoint 不改動。
                var probe = M5GameTestSupport.installChunkCheckpointProbe(context, universe.getRegistryKey(), 0, call -> {
                    if (call == 0) ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(hole, Blocks.AIR.getDefaultState()));
                    return M5GameTestSupport.state(journal, sid2);
                }, hooks);
                at(context, first + 1, hooks, () -> moveInside(p, frame));
                whenTicks(context, first + 2, 400, () -> probe.calls().size() == 1, checkpointed -> {
                    witness.put("firstSegmentRepairedAfterItsCheckpoint", repairedInfos(capture, sid2) == 1 && prepared(capture, sid2).isEmpty()
                            && M5GameTestSupport.state(journal, sid2).equals("COLLAPSING"));
                    // 這個 tick 的推導就看到斷電：閘門失效，這一段的修復沒有 checkpoint 涵蓋。
                    context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                    at(context, checkpointed + 1, hooks, () -> {
                        witness.put("backToWaitingWithoutACleanCheckpoint", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退回 WAITING",
                                sid2.toString()).size() == 1 && probe.calls().size() == 1 && prepared(capture, sid2).isEmpty());
                        context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                        moveOutside(p, frame);
                    });
                    at(context, checkpointed + 3, hooks, () -> moveInside(p, frame));
                    whenTicks(context, checkpointed + 4, 400, () -> !prepared(capture, sid2).isEmpty(), done -> {
                        var calls = probe.calls();
                        witness.put("secondSegmentRepairedNothing", entered(capture, sid2) == 2
                                && prepared(capture, sid2).getFirst().message().contains("投影=REPAIR（寫入 0 格）"));
                        witness.put("checkpointBeforeTheReadyInTheSecondSegment", calls.size() == 2 && calls.get(1).observed().equals("COLLAPSING")
                                && calls.get(1).chunks().equals(volumeChunks(frame)) && M5GameTestSupport.state(journal, sid2).equals("PASSAGE_READY"));
                        witness.put("invariantCellsAreTheTarget", M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty());
                        witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid2));
                        LOGGER.info("M5_PREPARE_REVISIT_INTERRUPTED checkpointed={} done={} checkpoints={}", checkpointed, done, calls);
                        releaseThenComplete(context, hooks, witness, "修復之後、checkpoint 之前中斷的 EXISTING 再次造訪：" + witness + " checkpoints=" + calls, 20,
                                () -> M5GameTestSupport.destinationTickets(universe, sid2).isEmpty());
                    }, () -> "第二段 PREPARE 沒有準備完成：record=" + M5GameTestSupport.state(journal, sid2) + " checkpoints=" + probe.calls() + " witness=" + witness, hooks);
                }, () -> "D2 沒有做第一次 checkpoint：record=" + M5GameTestSupport.state(journal, sid2) + " checkpoints=" + probe.calls(), hooks);
            }, () -> "D1 沒有準備完成：record=" + M5GameTestSupport.state(journal, sid1), hooks);
        });
    }

    /**
     * F4b-2（spec §9.2 的既有 entity、§9.1；plan Step 1 第 6 組的流程）：RESOLVE 之後、進入 PREPARE 之前，一位（創造模式、不受重力的）玩家 Q 站進宇宙 U 的投影
     * volume。第一次寫入每個 tick 都被 Q 擋住：一格都不寫（Controller 位置一直沒有 Controller），也不移動 Q；PENDING 已確認。從進入 PREPARE 起超過 600 ticks 時
     * 記一則 WARN（帶 session，寫明玩家擋住寫入與 Q），之前沒有；Q 再擋 5 個 tick 也不再記（去重）。Q 離開 volume 之後寫入、checkpoint、COMPLETE，準備完成，
     * WARN 仍只有一則。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_blocked", tickLimit = 1500)
    public void prepare_waits_for_a_player_in_the_destination_volume_and_warns_once_after_six_hundred_ticks(TestContext context) {
        begin(context, "prepare_waits_for_a_player_in_the_destination_volume_and_warns_once_after_six_hundred_ticks");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var connection = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(connection::close);
            var q = connection.player();
            q.changeGameMode(GameMode.CREATIVE); q.setNoGravity(true);
            standAt(q, universe, frame, 3.5, 1, 3.5);
            var qAt = q.getPos();
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, enteredAt -> {
                at(context, enteredAt + 590, hooks, () -> witness.put("noWarnBeforeSixHundredTicks", prepareWaitWarnings(capture, sid).isEmpty()
                        && prepared(capture, sid).isEmpty()));
                whenTicks(context, enteredAt + 595, 30, () -> prepareWaitWarnings(capture, sid).size() == 1, warned -> {
                    var warning = prepareWaitWarnings(capture, sid).getFirst().message();
                    witness.put("warnNamesTheBlockingPlayer", warning.contains("玩家") && warning.contains(q.getUuid().toString()));
                    witness.put("nothingWrittenWhileBlocked", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
                    witness.put("pendingConfirmed", CollapseAuthorities.get(server).projection(chamber, id).value()
                            .filter(entry -> entry.status() == ProjectionLedger.Entry.Status.PENDING).isPresent());
                    witness.put("playerNotMoved", q.getServerWorld() == universe && q.getPos().equals(qAt));
                    // Q 再擋 5 個 tick：WARN 仍只有一則（去重），之後才離開 volume。
                    at(context, warned + 5, hooks, () -> {
                        witness.put("stillOneWarnWhileBlocked", prepareWaitWarnings(capture, sid).size() == 1 && prepared(capture, sid).isEmpty()
                                && ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
                        standAt(q, universe, frame, 3.5, 1, -4.5);
                    });
                    whenTicks(context, warned + 6, 300, () -> !prepared(capture, sid).isEmpty(), ready -> {
                        witness.put("writtenAfterQLeft", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
                        witness.put("warnedOnlyOnce", prepareWaitWarnings(capture, sid).size() == 1);
                        LOGGER.info("M5_PREPARE_BLOCKED warned={} ready={} warning={}", warned - enteredAt, ready - enteredAt, warning);
                        releaseThenComplete(context, hooks, witness, "玩家擋住投影寫入：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    }, () -> "Q 離開之後沒有準備完成：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
                }, () -> "等待超過 600 ticks 沒有記 WARN：witness=" + witness, hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-2（spec §6.5 的實體交叉檢查；plan Step 1 第 3 組）：RESOLVE 之後、PREPARE 之前，宇宙 U 的投影 Controller 位置已經有一座 Controller（例如 ledger 遺失，
     * 或玩家在動態宇宙自行搭建），interior 另有一塊石頭；projection ledger 沒有這座艙在 U 的 entry。PREPARE 預載完成之後：PROJECTION_CONFLICT（永久，ERROR
     * 「需要管理員處理」一則），不寫 PENDING（ledger 的檔案 bytes 不變），不清 interior（石頭還在），不寫任何投影方塊（shell 仍是 AIR），既有的 Controller 不被覆寫；
     * 目的世界與來源的 ticket 都已釋放。永久原因：P 再次由艙外進入也不重試。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_cross_check", tickLimit = 900)
    public void prepare_seals_a_conflict_when_the_destination_already_has_a_controller_without_a_ledger_entry(TestContext context) {
        begin(context, "prepare_seals_a_conflict_when_the_destination_already_has_a_controller_without_a_ledger_entry");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            var stone = local(frame, 2, 1, 2); var shell = local(frame, 0, 3, 3);
            universe.setBlockState(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing()));
            universe.setBlockState(stone, Blocks.STONE.getDefaultState());
            var ledgerBefore = bytes(data, ProjectionLedger.FILE_NAME);
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.PROJECTION_CONFLICT), sealed -> {
                witness.put("noPendingWritten", CollapseAuthorities.get(server).projection(chamber, id).status() == CollapseAuthorities.Lookup.Status.ABSENT
                        && Arrays.equals(ledgerBefore, bytes(data, ProjectionLedger.FILE_NAME)));
                witness.put("interiorNotCleared", universe.getBlockState(stone).isOf(Blocks.STONE));
                witness.put("noProjectionBlocks", universe.getBlockState(shell).isAir());
                witness.put("existingControllerKept", universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER));
                var errors = capture.matching(Level.ERROR, sid.toString());
                witness.put("onePermanentAdminError", errors.size() == 1 && errors.getFirst().message().contains("SEALED(PROJECTION_CONFLICT)")
                        && errors.getFirst().message().contains("需要管理員處理") && errors.getFirst().message().contains("實體交叉檢查"));
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                moveOutside(p, frame);
                at(context, sealed + 2, hooks, () -> moveInside(p, frame));
                at(context, sealed + 5, hooks, () -> {
                    witness.put("permanentNeverRetried", sealedWith(journal, sid, SealedReason.PROJECTION_CONFLICT)
                            && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty() && capture.matching(Level.ERROR, sid.toString()).size() == 1);
                    releaseThenComplete(context, hooks, witness, "§6.5 的實體交叉檢查：" + witness, 20, () -> true);
                });
            }, () -> "沒有進 SEALED(PROJECTION_CONFLICT)：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-2（spec §9.2 的衝突；plan Step 1 第 7 組的「投影衝突 → PROJECTION_CONFLICT」）：RESOLVE 之後、PREPARE 之前，另一座艙（隨機的 chamber UUID）在宇宙 U
     * 已有一座 COMPLETE 投影，位置沿原艙的寬度方向偏 3 格，所以兩個 7×7×7 相交，但本艙的 Controller 位置沒有 Controller（不經 §6.5 的交叉檢查）。fixture 只經
     * 權威與索引寫入那座投影（checked PENDING→COMPLETE 並登記），不寫方塊。PREPARE 預載完成之後：PROJECTION_CONFLICT（永久，ERROR 帶那座投影的 chamber），
     * 不寫本艙的 PENDING、不覆寫（本艙的 volume 沒有任何投影方塊）。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_overlap", tickLimit = 900)
    public void prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection(TestContext context) {
        begin(context, "prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            var other = new ChamberFrame(frame.controllerPos().offset(frame.outwardFacing().rotateYCounterclockwise(), 3), frame.outwardFacing());
            var otherChamber = UUID.randomUUID();
            var pending = authorities.writeProjection(new ProjectionLedger.Entry(otherChamber, id, DimensionRole.OVERWORLD, other.controllerPos(),
                    other.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
            index.confirmed(pending);
            var complete = authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
            index.confirmed(complete);
            witness.put("fixtureOverlapsAnotherCompleteProjection", complete.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                    && ChamberGeometry.bounds(other).intersects(ChamberGeometry.bounds(frame)));
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.PROJECTION_CONFLICT), sealed -> {
                witness.put("noPendingForThisChamber", authorities.projection(chamber, id).status() == CollapseAuthorities.Lookup.Status.ABSENT);
                witness.put("notOverwritten", !universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                        && M5GameTestSupport.projectionMismatches(universe, frame, true).size() == 218);
                var errors = capture.matching(Level.ERROR, sid.toString());
                witness.put("onePermanentErrorNamingTheOtherProjection", errors.size() == 1 && errors.getFirst().message().contains("SEALED(PROJECTION_CONFLICT)")
                        && errors.getFirst().message().contains(otherChamber.toString()));
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                releaseThenComplete(context, hooks, witness, "§9.2 的衝突：" + witness + " matchedShell=" + matchedShellCells(universe, frame), 20, () -> true);
            }, () -> "沒有進 SEALED(PROJECTION_CONFLICT)：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4b-2（spec §6「既有紀錄的驗證」、§6.5；plan Step 1 第 4 組）：projection ledger 已有本艙在宇宙 U 的 COMPLETE entry，但 ControllerPos 高了一格（不是本 session
     * 的來源艙）。{@code projection()} 只看 key，找得到這筆 COMPLETE；PREPARE 不直接拿它走修復，一律先以 {@code writeProjection(PENDING)} 驗證 site → 不符：
     * AUTHORITY_CORRUPT（永久），不寫任何投影方塊（本艙的位置沒有 Controller），ledger 不改寫。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_site", tickLimit = 900)
    public void prepare_validates_the_site_of_an_existing_complete_entry_before_repairing(TestContext context) {
        begin(context, "prepare_validates_the_site_of_an_existing_complete_entry_before_repairing");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            var moved = new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, frame.controllerPos().up(), frame.outwardFacing(),
                    ProjectionLedger.Entry.Status.PENDING);
            var pending = authorities.writeProjection(moved);
            index.confirmed(pending);
            var complete = authorities.writeProjection(moved.withStatus(ProjectionLedger.Entry.Status.COMPLETE));
            index.confirmed(complete);
            witness.put("fixtureCompleteAtAnotherSite", complete.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                    && authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent());
            var ledgerBefore = bytes(data, ProjectionLedger.FILE_NAME);
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.AUTHORITY_CORRUPT), sealed -> {
                witness.put("nothingRepairedAtThisSite", !universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                        && M5GameTestSupport.projectionMismatches(universe, frame, true).size() == 218);
                witness.put("ledgerNotRewritten", Arrays.equals(ledgerBefore, bytes(data, ProjectionLedger.FILE_NAME))
                        && authorities.projection(chamber, id).value().equals(Optional.of(moved.withStatus(ProjectionLedger.Entry.Status.COMPLETE))));
                var errors = capture.matching(Level.ERROR, sid.toString());
                witness.put("onePermanentErrorAboutTheSite", errors.size() == 1 && errors.getFirst().message().contains("SEALED(AUTHORITY_CORRUPT)")
                        && errors.getFirst().message().contains("ControllerPos"));
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                releaseThenComplete(context, hooks, witness, "既有 entry 的 site 驗證：" + witness + " matchedShell=" + matchedShellCells(universe, frame), 20, () -> true);
            }, () -> "沒有進 SEALED(AUTHORITY_CORRUPT)：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4b-2（spec §6.5、§9.3 的退化、§6；plan Step 1 第 2 組「COMPLETE 之後刪除 projection ledger → 不重寫 interior」與第 5 組「退化時進 SEALED」的流程；補充第 5 點）：
     * D1（DORMANT NEW）把原艙投影到宇宙 U，準備完成（COMPLETE）；在投影艙 interior 放一塊石頭，以授權寫入把一格 shell 損壞成 AIR，以終局 API 移除 D1。之後在
     * 執行期間刪除 projection ledger 檔（journal 已是 schema 4）：下一次 checked 寫入（這裡是另一座艙在 U 的 PENDING）在前置檢查發現檔案消失 → AUTHORITY_CORRUPT、
     * 權威不健康、不重建檔案，投影保護索引退化。同一座原艙選中 EXISTING U 的 D2 由 P 觸發：RESOLVE 只重驗（不看 projection ledger）→ PREPARE 先查退化 →
     * SEALED(AUTHORITY_CORRUPT)，永久，ERROR「需要管理員處理」一則；不寫任何方塊：interior 的石頭不被清掉，損壞的 shell 也不被修復；目的世界的 ticket 已釋放。
     * 最後以 testmod 的重建 hook 寫回 ledger 檔、解除遺失與退化（README 的隔離規則；batch 的順序不固定，之後的 batch 才不受影響），權威回到健康。
     * 真正重啟之後的遺失判定（初始化時 schema 4、檔案不存在）屬 F6 的重啟矩陣。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_degraded", tickLimit = 1400)
    public void deleting_the_projection_ledger_after_complete_degrades_and_seals_without_rewriting_the_interior(TestContext context) {
        begin(context, "deleting_the_projection_ledger_after_complete_degrades_and_seals_without_rewriting_the_interior");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid1, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            whenTicks(context, resolved + 1, PREPARE_WAIT_TICKS, () -> !prepared(capture, sid1).isEmpty(), first -> {
                var stone = local(frame, 2, 1, 2); var hole = local(frame, 0, 3, 3);
                universe.setBlockState(stone, Blocks.STONE.getDefaultState());
                ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(hole, Blocks.AIR.getDefaultState()));
                cleanupFixtures(server, List.of(sid1));
                moveOutside(p, frame);
                var saved = bytes(data, ProjectionLedger.FILE_NAME);
                var rebuilt = new AtomicBoolean();
                // 隔離（README）：失敗路徑也要把 ledger 與權威、索引還原，之後的 batch 才不受影響（hooks 後進先出，早於 DormantOrigin 的收尾）。
                hooks.add(() -> { if (rebuilt.compareAndSet(false, true)) M5GameTestSupport.rebuildProjectionAuthority(server, saved); });
                try {
                    Files.delete(data.resolve(ProjectionLedger.FILE_NAME));
                } catch (IOException failure) { throw new IllegalStateException(failure); }
                var probe = authorities.writeProjection(new ProjectionLedger.Entry(UUID.randomUUID(), id, DimensionRole.OVERWORLD,
                        frame.controllerPos().add(64, 0, 0), frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
                witness.put("lossDetectedByTheNextCheckedWrite", probe.status() == CollapseAuthorities.WriteResult.Status.CORRUPT
                        && probe.sealedReason().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT))
                        && authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER).equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)));
                witness.put("indexDegraded", index.degradation().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)));
                witness.put("ledgerNotRecreated", bytes(data, ProjectionLedger.FILE_NAME).length == 0);
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), id));
                var sid2 = second.sessionUuid();
                at(context, first + 1, hooks, () -> moveInside(p, frame));
                whenTicks(context, first + 2, 60, () -> sealedWith(journal, sid2, SealedReason.AUTHORITY_CORRUPT), sealed -> {
                    witness.put("interiorNotRewritten", universe.getBlockState(stone).isOf(Blocks.STONE));
                    witness.put("invariantNotRepaired", universe.getBlockState(hole).isAir());
                    witness.put("neverPrepared", prepared(capture, sid2).isEmpty());
                    var errors = capture.matching(Level.ERROR, sid2.toString());
                    witness.put("onePermanentAdminError", errors.size() == 1 && errors.getFirst().message().contains("SEALED(AUTHORITY_CORRUPT)")
                            && errors.getFirst().message().contains("需要管理員處理") && errors.getFirst().message().contains("退化"));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid2).isEmpty());
                    M5GameTestSupport.rebuildProjectionAuthority(server, saved);
                    rebuilt.set(true);
                    witness.put("rebuiltHealthy", Arrays.equals(saved, bytes(data, ProjectionLedger.FILE_NAME))
                            && authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER).isEmpty() && index.degradation().isEmpty());
                    releaseThenComplete(context, hooks, witness, "projection ledger 遺失之後：" + witness, 20, () -> true);
                }, () -> "D2 沒有進 SEALED(AUTHORITY_CORRUPT)：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
            }, () -> "D1 沒有準備完成：record=" + M5GameTestSupport.state(journal, sid1), hooks);
        });
    }

    /**
     * F4b-2（spec §6 收斂規則第 2 點、§6.5；plan Step 1 第 9 組「投影方塊只在 PENDING 確認之後寫」）：PENDING 的 checked 寫入在 atomic move 之後讀回失敗（testmod 的
     * {@code CheckedNbtFileReadFaultMixin}，對 {@code quantumchamber_projections.dat} 注入一次）。roll-forward：current 保留 PENDING、權威未確認；PREPARE 進
     * SEALED(PROJECTION_FAILED)（可重試的 WARN 一則），沒有寫任何方塊（volume 的 343 格都是 AIR）、沒有登記到投影保護索引；目的世界的 ticket 已釋放。完成之前等
     * projection ledger 收斂（權威的退避 200 tick 之後由 helper 重寫）。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_pending_fault", tickLimit = 900)
    public void pending_write_failure_seals_without_writing_any_block(TestContext context) {
        begin(context, "pending_write_failure_seals_without_writing_any_block");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var fault = M5GameTestSupport.installCheckedReadFault(context, ProjectionLedger.FILE_NAME, 0, 1, hooks);
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.PROJECTION_FAILED), sealed -> {
                witness.put("pendingReadbackFaultedOnce", fault.hits() == 1);
                witness.put("ledgerUnconfirmed", authorities.unconfirmed(CollapseAuthorities.Authority.PROJECTION_LEDGER));
                witness.put("noBlockWritten", cells(universe, frame).stream().allMatch(BlockState::isAir));
                witness.put("notRegistered", !ProjectionProtectionIndex.projectionControllerAt(server, universe.getRegistryKey(), frame.controllerPos()));
                witness.put("oneRetryableWarn", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "SEALED(PROJECTION_FAILED)", "退避期之外",
                        sid.toString()).size() == 1 && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                releaseThenComplete(context, hooks, witness, "PENDING 落盤失敗：" + witness, 260, () -> true);
            }, () -> "沒有進 SEALED(PROJECTION_FAILED)：record=" + M5GameTestSupport.state(journal, sid) + " hits=" + fault.hits(), hooks);
        });
    }

    /**
     * F4b-2（spec §9.2「以上都成功，才 checked 改為 COMPLETE」、§9.2 的失敗；plan Step 1 第 2 組「checkpoint 之後才 COMPLETE」；補充第 1 點第 6 步）：第一次寫入之後，
     * 目的 chunks 的原生存檔 checkpoint 受控失敗一次（testmod 的 {@code ChunkSaveCheckpointProbeMixin}）→ SEALED(PROJECTION_FAILED)（可重試的 WARN 一則，cause 是那次
     * checkpoint 的例外）。投影方塊已寫完，但 ledger 仍是 PENDING（沒有寫 COMPLETE），索引只辨識 Controller、不保護不變量 cell；目的世界的 ticket 已釋放。
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_checkpoint_fault", tickLimit = 900)
    public void checkpoint_failure_seals_before_the_projection_is_complete(TestContext context) {
        begin(context, "checkpoint_failure_seals_before_the_projection_is_complete");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var probe = M5GameTestSupport.installChunkCheckpointProbe(context, universe.getRegistryKey(), 1, call -> {
                var lookup = authorities.projection(chamber, id);
                return lookup.status() + lookup.value().map(entry -> " " + entry.status()).orElse("");
            }, hooks);
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.PROJECTION_FAILED), sealed -> {
                var calls = probe.calls();
                witness.put("checkpointFailedOnceWhilePending", calls.size() == 1 && calls.getFirst().failed() && calls.getFirst().observed().equals("FOUND PENDING"));
                witness.put("stillPendingNotComplete", authorities.projection(chamber, id).value()
                        .filter(entry -> entry.status() == ProjectionLedger.Entry.Status.PENDING).isPresent());
                var key = universe.getRegistryKey();
                witness.put("writtenButNotProtected", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty()
                        && ProjectionProtectionIndex.projectionControllerAt(server, key, frame.controllerPos())
                        && !ProjectionProtectionIndex.protectedCell(server, key, local(frame, 0, 3, 3)));
                witness.put("oneRetryableWarnWithTheCheckpointCause", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "SEALED(PROJECTION_FAILED)",
                        "受控：原生存檔 checkpoint 失敗", sid.toString()).size() == 1 && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                releaseThenComplete(context, hooks, witness, "checkpoint 失敗：" + witness + " calls=" + calls, 20, () -> true);
            }, () -> "沒有進 SEALED(PROJECTION_FAILED)：record=" + M5GameTestSupport.state(journal, sid) + " calls=" + probe.calls(), hooks);
        });
    }

    /**
     * F4b-2（spec §9.2 的失敗、§11.2 的重試、§6.5；plan Step 1 第 7 組「注入寫入失敗 → PROJECTION_FAILED → 邊緣觸發＋退避重試成功」與第 3 組「有 PENDING 時照第一次
     * 寫入」）：第一次寫入真的寫完之後，testmod 的 {@code ProjectionWriteFaultMixin} 把結果換成 WRITE_FAILED（模擬寫入途中丟出例外，方塊可能已寫了一部分）→
     * SEALED(PROJECTION_FAILED)（可重試的 WARN 一則，cause 是注入的例外）。ledger 留著 PENDING，目的地已有投影的 Controller；兩種 ticket 都已釋放。退避期內 P 再次
     * 由艙外進入不重試；per-session 退避（200 tick）之後再進入 → SEALED→COLLAPSING → RESOLVE（idempotent：allocation entry 仍是一筆）→ PREPARE：已有 PENDING，不做
     * §6.5 的交叉檢查（自己寫了一部分的 Controller 不是衝突），照第一次寫入重寫 → checkpoint → COMPLETE → 準備完成。catalog 增量 1（沒有第二個宇宙）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_write_fault", tickLimit = 1200)
    public void projection_write_failure_seals_then_retries_after_the_backoff_over_its_own_pending(TestContext context) {
        begin(context, "projection_write_failure_seals_then_retries_after_the_backoff_over_its_own_pending");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var fault = M5GameTestSupport.installProjectionWriteFault(context, universe.getRegistryKey(), ProjectionWriter.Mode.FIRST_WRITE, 1, hooks);
            whenTicks(context, resolved + 1, 400, () -> sealedWith(journal, sid, SealedReason.PROJECTION_FAILED), sealed -> {
                witness.put("writeFaultedOnce", fault.hits() == 1);
                witness.put("pendingKept", authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.PENDING).isPresent());
                witness.put("controllerWrittenBeforeTheFault", universe.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER));
                witness.put("oneRetryableWarnWithTheInjectedCause", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "SEALED(PROJECTION_FAILED)",
                        "受控：投影寫入失敗", sid.toString()).size() == 1 && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                moveOutside(p, frame);
                // 退避期內的邊緣觸發：不重試。
                at(context, sealed + 2, hooks, () -> moveInside(p, frame));
                at(context, sealed + 5, hooks, () -> {
                    witness.put("noRetryWithinTheBackoff", sealedWith(journal, sid, SealedReason.PROJECTION_FAILED)
                            && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                    moveOutside(p, frame);
                });
                // 封存在 sealed-1 的 END_SERVER_TICK；per-session 退避 200 tick 之後再由艙外進入，留 5 tick 餘裕。
                at(context, sealed + 205, hooks, () -> moveInside(p, frame));
                whenTicks(context, sealed + 206, 400, () -> !prepared(capture, sid).isEmpty(), ready -> {
                    witness.put("retriedOnce", capture.matching(Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                    witness.put("rewroteOverItsOwnPending", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty()
                            && authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent()
                            && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                    witness.put("oneUniverse", allocationEntries(server, id) == 1 && capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 2);
                    witness.put("faultNotHitAgain", fault.hits() == 1);
                    releaseThenComplete(context, hooks, witness, "投影寫入失敗之後的重試：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                }, () -> "退避之後的重試沒有準備完成：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有進 SEALED(PROJECTION_FAILED)：record=" + M5GameTestSupport.state(journal, sid) + " hits=" + fault.hits(), hooks);
        });
    }

    /**
     * F4b-2 fix round 1（quality Q-I1；spec §9.1、§9.2；{@code ChunkSaveCheckpoint} 的呼叫端契約：不是每個 tick 都存檔）：第一次寫入之後，原生存檔 checkpoint
     * 只在「checkpoint 之前的再驗」乾淨時才做。D 是門板格 {@code local(3,1,0)}：
     * <ul>
     * <li>第一次 checkpoint 存檔之前（checkpoint 探針）把 D 改成 AIR：checkpoint 之後的再驗修復它、不寫 COMPLETE（INFO「已修復 1 格」）。</li>
     * <li>之後每個 test tick 都把 D 改成 AIR（模擬持續改動不變量 cell 的 1-tick 裝置），直到進入 PREPARE 之後 610 ticks：checkpoint 之前的再驗每個 tick 都修復 D、
     *     不做 checkpoint（checkpoint 仍只有第一次那一次），「已修復」的 INFO 這一段 PREPARE 只記一次；超過 600 ticks 時記一則 PREPARE 等待的 WARN（帶 session，
     *     寫明不變量 cell 一直被改動）。ledger 仍是 PENDING。</li>
     * <li>接著把 D 改成 AIR，讓（創造模式、不受重力的）玩家 Q 站在 D：再驗被 Q 擋住，5 個 tick 都不做 checkpoint、不寫 D，WARN 仍是一則。</li>
     * <li>Q 離開之後：再驗修復 D，下一個 tick 再驗乾淨才做第二次 checkpoint，之後寫 COMPLETE、準備完成；343 格都是目標，「已修復」的 INFO 仍是一則。</li>
     * </ul>
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_recheck", tickLimit = 1500)
    public void checkpoint_waits_for_a_clean_recheck_while_the_invariant_cells_keep_changing(TestContext context) {
        begin(context, "checkpoint_waits_for_a_clean_recheck_while_the_invariant_cells_keep_changing");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var d = local(frame, 3, 1, 0);
            Runnable damage = () -> ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(d, Blocks.AIR.getDefaultState()));
            var probe = M5GameTestSupport.installChunkCheckpointProbe(context, universe.getRegistryKey(), 0, call -> {
                if (call == 0) damage.run();
                var lookup = authorities.projection(chamber, id);
                return lookup.status() + lookup.value().map(entry -> " " + entry.status()).orElse("");
            }, hooks);
            var connection = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(connection::close);
            var q = connection.player();
            q.changeGameMode(GameMode.CREATIVE); q.setNoGravity(true);
            BooleanSupplier pending = () -> authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.PENDING).isPresent();
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, enteredAt -> {
                whenTicks(context, enteredAt, 400, () -> probe.calls().size() >= 1, firstCheckpoint -> {
                    witness.put("firstCheckpointWhilePending", probe.calls().size() == 1 && probe.calls().getFirst().observed().equals("FOUND PENDING"));
                    witness.put("repairedAfterTheFirstCheckpoint", repairedInfos(capture, sid) == 1 && universe.getBlockState(d).equals(
                            M5GameTestSupport.projectionTarget(frame, 3, 1, 0)));
                    // 每個 test tick（早於那個 tick 的 END_SERVER_TICK）都改動 D，直到進入 PREPARE 之後 610 ticks；從這個 tick 就開始（第一次 checkpoint 在上一個
                    // tick 的 END_SERVER_TICK，這個 tick 的 END_SERVER_TICK 就會再驗）。
                    damage.run();
                    whenTicks(context, firstCheckpoint + 1, 700, () -> {
                        damage.run();
                        return context.getTick() >= enteredAt + 610;
                    }, changing -> {
                        witness.put("noCheckpointWhileChanging", probe.calls().size() == 1);
                        witness.put("oneRepairInfoWhileChanging", repairedInfos(capture, sid) == 1);
                        witness.put("oneWaitWarnNamingTheChange", prepareWaitWarnings(capture, sid).size() == 1
                                && prepareWaitWarnings(capture, sid).getFirst().message().contains("不變量 cell"));
                        witness.put("stillPendingWhileChanging", pending.getAsBoolean() && prepared(capture, sid).isEmpty());
                        // 改動停止，但 Q 站在被改成 AIR 的 D：再驗被擋住。
                        damage.run();
                        standAt(q, universe, frame, 3.5, 1, 0.5);
                        at(context, changing + 5, hooks, () -> {
                            witness.put("noCheckpointWhileBlocked", probe.calls().size() == 1 && universe.getBlockState(d).isAir()
                                    && prepareWaitWarnings(capture, sid).size() == 1 && repairedInfos(capture, sid) == 1 && pending.getAsBoolean());
                            standAt(q, universe, frame, 3.5, 1, -4.5);
                        });
                        whenTicks(context, changing + 6, 100, () -> !prepared(capture, sid).isEmpty(), ready -> {
                            var calls = probe.calls();
                            witness.put("secondCheckpointAfterACleanRecheck", calls.size() == 2 && calls.get(1).observed().equals("FOUND PENDING"));
                            witness.put("everyCellIsTheSpecTarget", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
                            witness.put("complete", authorities.projection(chamber, id).value()
                                    .filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent());
                            witness.put("stillOneRepairInfoAndOneWaitWarn", repairedInfos(capture, sid) == 1 && prepareWaitWarnings(capture, sid).size() == 1);
                            witness.put("onlyTheWaitWarnAndNoError", capture.matching(Level.WARN, sid.toString()).size() == 1
                                    && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                            LOGGER.info("M5_PREPARE_RECHECK firstCheckpoint={} changing={} ready={} checkpoints={}", firstCheckpoint - enteredAt,
                                    changing - enteredAt, ready - enteredAt, calls);
                            releaseThenComplete(context, hooks, witness, "checkpoint 之前的再驗：" + witness + " checkpoints=" + calls, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                        }, () -> "Q 離開之後沒有準備完成：checkpoints=" + probe.calls().size() + " witness=" + witness, hooks);
                    }, () -> "改動 D 的期間逾時：checkpoints=" + probe.calls().size() + " witness=" + witness, hooks);
                }, () -> "沒有做第一次 checkpoint：record=" + M5GameTestSupport.state(journal, sid), hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-2 fix round 1（quality Q-M1，controller 裁定；spec §6 收斂規則第 2 點：未確認期間只重試收斂、不做新決策）：projection ledger 有另一座艙尚未確認的寫入時，
     * PREPARE 等待、不進 SEALED。（創造模式、不受重力的）玩家 Q 站在宇宙 U 的投影 volume 裡，擋住第一次寫入；本艙的 PENDING 確認之後，test 以 checked 寫入替另一座
     * （不存在的）艙在 U 的遠處寫一筆 PENDING，讀回受控失敗一次（testmod 的 {@code CheckedNbtFileReadFaultMixin}）→ WRITE_FAILED、權威未確認（以權威為單位退避
     * 200 tick）。這段期間本 session 的 projection 查詢回 UNCONFIRMED：不進 SEALED（沒有 SEALED 的 WARN／ERROR，record 仍是 COLLAPSING），不寫方塊。權威由塌縮
     * 流程自己的收斂（{@code convergeIfNeeded}）確認之後，Q 離開，寫入、checkpoint、COMPLETE，準備完成；另一座艙的 PENDING 留在 ledger（收斂帶著它）。只有本
     * session 自己的 checked 寫入失敗才進 SEALED（T-PF）。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_prepare_unconfirmed", tickLimit = 1200)
    public void prepare_waits_while_another_projection_write_is_unconfirmed(TestContext context) {
        begin(context, "prepare_waits_while_another_projection_write_is_unconfirmed");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            var connection = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(connection::close);
            var q = connection.player();
            q.changeGameMode(GameMode.CREATIVE); q.setNoGravity(true);
            standAt(q, universe, frame, 3.5, 1, 3.5);
            // F5-1 起準備完成的同一個 tick 就提交 PASSAGE_READY：「沒有封存」是不是 SEALED、沒有封存的 WARN／ERROR。
            BooleanSupplier noSeal = () -> !M5GameTestSupport.state(journal, sid).equals("SEALED")
                    && capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "塌縮進入 SEALED", sid.toString()).isEmpty()
                    && capture.matching(Level.ERROR, sid.toString()).isEmpty();
            whenTicks(context, resolved + 1, 400, () -> authorities.projection(chamber, id).value()
                    .filter(entry -> entry.status() == ProjectionLedger.Entry.Status.PENDING).isPresent(), pendingAt -> {
                var fault = M5GameTestSupport.installCheckedReadFault(context, ProjectionLedger.FILE_NAME, 0, 1, hooks);
                var other = new ProjectionLedger.Entry(UUID.randomUUID(), id, DimensionRole.OVERWORLD, frame.controllerPos().add(64, 0, 0), frame.outwardFacing(),
                        ProjectionLedger.Entry.Status.PENDING);
                var written = authorities.writeProjection(other);
                witness.put("otherWriteUnconfirmed", written.status() == CollapseAuthorities.WriteResult.Status.WRITE_FAILED && fault.hits() == 1
                        && authorities.unconfirmed(CollapseAuthorities.Authority.PROJECTION_LEDGER));
                witness.put("blockedBeforeTheOtherWrite", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
                at(context, pendingAt + 1, hooks, () -> witness.put("lookupUnconfirmed",
                        authorities.projection(chamber, id).status() == CollapseAuthorities.Lookup.Status.UNCONFIRMED));
                // 進了 SEALED 就不必再等收斂：在下面當場判定。
                whenTicks(context, pendingAt + 2, 300, () -> !noSeal.getAsBoolean() || !authorities.unconfirmed(CollapseAuthorities.Authority.PROJECTION_LEDGER),
                        converged -> {
                    witness.put("noSealWhileUnconfirmed", noSeal.getAsBoolean());
                    witness.put("nothingWrittenWhileUnconfirmed", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
                    witness.put("convergedAfterTheAuthorityBackoff", !authorities.unconfirmed(CollapseAuthorities.Authority.PROJECTION_LEDGER)
                            && converged - pendingAt >= 190 && fault.hits() == 1);
                    context.assertTrue(!witness.containsValue(false), "另一座艙的寫入未確認期間：" + witness + " record=" + M5GameTestSupport.state(journal, sid));
                    standAt(q, universe, frame, 3.5, 1, -4.5);
                    whenTicks(context, converged + 1, 100, () -> !prepared(capture, sid).isEmpty(), ready -> {
                        witness.put("preparedAfterTheConvergence", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty()
                                && authorities.projection(chamber, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent());
                        witness.put("otherPendingKept", authorities.projection(other.chamberUuid(), id).value().filter(other::equals).isPresent());
                        witness.put("stillNoSealAndReady", noSeal.getAsBoolean() && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"));
                        LOGGER.info("M5_PREPARE_UNCONFIRMED converged={} ready={}", converged - pendingAt, ready - pendingAt);
                        releaseThenComplete(context, hooks, witness, "另一座艙的寫入未確認時：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    }, () -> "Q 離開之後沒有準備完成：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
                }, () -> "projection ledger 沒有收斂：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "本艙的 PENDING 沒有確認：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /** 這個 session「已修復 N 格」的 INFO 數（帶 LOG_MARKER）。 */
    private static int repairedInfos(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "已修復", sid.toString()).size();
    }

    /** 投影的不變量 cell 中已經是 spec 目標的格與方塊狀態（診斷用）。 */
    private static List<String> matchedShellCells(ServerWorld world, ChamberFrame frame) {
        var result = new ArrayList<String>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            if (!ChamberGeometry.isShellCell(x, y, z)) continue;
            var pos = local(frame, x, y, z);
            if (world.getBlockState(pos).equals(M5GameTestSupport.projectionTarget(frame, x, y, z))) result.add(x + "," + y + "," + z + "=" + world.getBlockState(pos));
        }
        return result;
    }

    /** 這個 session「PREPARE 持續等待超過 600 ticks」的 WARN（帶 LOG_MARKER）。 */
    private static List<LogCapture.Entry> prepareWaitWarnings(LogCapture capture, UUID sid) {
        return capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "PREPARE 持續等待超過", sid.toString());
    }

    /** 投影的 7×7×7 涵蓋的 chunk（test 自己算，不讀 production）。 */
    private static Set<ChunkPos> volumeChunks(ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        var chunks = new HashSet<ChunkPos>();
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) chunks.add(new ChunkPos(new BlockPos(x, 0, z)));
        return Set.copyOf(chunks);
    }

    /** 第一則「目的世界預載完成」記下的等待 tick 數；沒有時為 -1。 */
    private static int preloadWaitedTicks(LogCapture capture, UUID sid) {
        var all = preloaded(capture, sid);
        if (all.isEmpty()) return -1;
        var matcher = Pattern.compile("等了 (\\d+) ticks").matcher(all.getFirst().message());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** inert 的 PROJECTION：仍在世界上、PROJECTION、INVALID、比較器 0、沒有 chamber UUID。 */
    private static boolean inertProjection(ServerWorld world, ChamberControllerBlockEntity controller) {
        return inWorld(world, controller) && controller.instanceKind() == ChamberInstanceKind.PROJECTION && inert(world, controller);
    }

    /** 未知的 foreign Controller：仍在世界上、refresh 過、ORIGIN（預設值）、INVALID、比較器 0、沒有 chamber UUID。 */
    private static boolean foreign(ServerWorld world, ChamberControllerBlockEntity controller) {
        return inWorld(world, controller) && controller.powerInitialized()
                && controller.instanceKind() == ChamberInstanceKind.ORIGIN && inert(world, controller);
    }

    private static boolean inWorld(ServerWorld world, ChamberControllerBlockEntity controller) {
        return !controller.isRemoved() && world.getBlockEntity(controller.getPos()) == controller;
    }

    private static boolean inert(ServerWorld world, ChamberControllerBlockEntity controller) {
        return controller.chamberState() == ChamberState.INVALID && controller.chamberUuid() == null
                && world.getBlockState(controller.getPos()).getComparatorOutput(world, controller.getPos()) == 0;
    }

    /** 玩家手上的方塊放在 {@code below} 的上方（原版 {@code interactBlock}，打在頂面）。 */
    private static void placeOnTop(ServerPlayerEntity player, ServerWorld world, BlockPos below) {
        var hit = new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false);
        player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
    }

    /** 在 {@code at} 放一台朝向相鄰 {@code facing} 的活塞，再在 {@code power} 放紅石塊供電；回傳活塞的位置。 */
    private static BlockPos piston(ServerWorld world, BlockPos at,
            BlockPos facing, BlockPos power) {
        world.setBlockState(at, Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, toward(at, facing)));
        world.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState());
        return at;
    }

    /**
     * F4b-1 的 fixture：原艙上的 DORMANT NEW 收據，P 由艙外進入 → 提交 COLLAPSING → RESOLVE 建立並物化宇宙 U。RESOLVE 完成之後，先以
     * {@code M5TestHooks.suspendCollapse} 暫停這個 session 的塌縮流程，再以那個 tick、U 的 world、session 與 U 呼叫 {@code ready}：F4b-2 起，RESOLVE 之後的下一個
     * tick（P 仍在艙內、已供電）閘門成立、塌縮流程自己預載並寫投影；F4b-1 的 test 自己以寫入器與權威操作同一個位置的投影，所以在第一次閘門評估之前暫停
     * （偵測 RESOLVE 完成的 callback 在它之後的 test tick 執行，早於那個 tick 的 {@code END_SERVER_TICK}）。收尾時 DormantOrigin 以終局 API 移除收據
     * （宇宙保留，catalog 增量 1）。
     */
    private static void resolvedUniverse(TestContext context, DormantOrigin origin, M2CorridorGameTests.TestHooks hooks, ResolvedUniverse ready) {
        newCollapse(context, origin, hooks, true, List.of(), ready);
    }

    /** 由 {@code from} 指向相鄰的 {@code to} 的方向。 */
    private static Direction toward(BlockPos from, BlockPos to) {
        return Objects.requireNonNull(Direction.fromVector(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ()),
                "不相鄰");
    }

    /** 投影的 7×7×7 外框（整格）。 */
    private static Box box(ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        return new Box(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1);
    }

    /** {@code area} 內的掉落物數。 */
    private static int items(ServerWorld world, Box area) {
        return world.getEntitiesByClass(ItemEntity.class, area, entity -> true).size();
    }

    /** 投影 7×7×7 的 343 格目前的方塊狀態（依區域座標順序）。 */
    private static List<BlockState> cells(ServerWorld world, ChamberFrame frame) {
        var result = new ArrayList<BlockState>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) result.add(world.getBlockState(local(frame, x, y, z)));
        return result;
    }

    /** 把玩家移到投影區域座標 (x, y, z) 的位置（同一個世界或跨世界）。 */
    private static void standAt(ServerPlayerEntity player, ServerWorld world,
            ChamberFrame frame, double x, double y, double z) {
        var at = ChamberSpaceCoordinates.position(frame, x, y, z);
        player.teleport(world, at.x, at.y, at.z, Set.of(), 0, 0);
        player.setVelocity(Vec3d.ZERO);
    }

    /** 以授權寫入損壞兩格不變量 cell（一格 AIR、一格石頭）；{@code closeDoor} 時另把 25 格門關上。 */
    private static void damage(ServerWorld world, ChamberFrame frame, BlockPos holed,
            BlockPos stoned, boolean closeDoor) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            world.setBlockState(holed, Blocks.AIR.getDefaultState());
            world.setBlockState(stoned, Blocks.STONE.getDefaultState());
            if (closeDoor) for (var cell : M5GameTestSupport.door(frame)) {
                world.setBlockState(cell, QuantumBulkheadBlock.withOpen(world.getBlockState(cell), false));
            }
        });
    }
}
