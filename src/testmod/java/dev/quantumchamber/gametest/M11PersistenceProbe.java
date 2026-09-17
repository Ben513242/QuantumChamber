package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.LoggerFactory;

/** 僅測試模組：四個獨立 dedicated 程序共用存檔，不代替原生紅石／玩家驗收。 */
public final class M11PersistenceProbe implements ModInitializer {
    private static final BlockPos CONTROLLER = new BlockPos(15, 70, 14);
    private static final ChamberFrame FRAME = new ChamberFrame(CONTROLLER, Direction.NORTH);
    private int ticks;

    @Override
    public void onInitialize() {
        String phase = System.getProperty("quantumchamber.m11.phase", "");
        if (phase.isEmpty()) return;
        require(java.util.Set.of("prepare", "resume", "dismantle", "removed").contains(phase), "未知 phase");
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // 等待正常 Done 與載入同步；只執行一次，失敗直接讓真正 server tick 拋錯。
            if (++ticks == 1) {
                for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) server.getOverworld().getChunk(x >> 4, z >> 4);
            }
            if (ticks != 40) return;
            require(server.isOnThread(), "探針必須在 server thread");
            runPhase(server, phase);
        });
    }

    private static void runPhase(MinecraftServer server, String phase) {
        ServerWorld world = server.getOverworld();
        var state = ChamberRegistryState.get(server);
        require(state.loadError().isEmpty(), "Registry 必須健康");
        ChamberRegistry registry = state.registry();
        // fixture 跨四個 chunk，restart 必須真正從原世界載入，不重新建立 Controller。
        for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) world.getChunk(x >> 4, z >> 4);
        UUID uuid;
        if (phase.equals("prepare")) {
            require(registry.records().isEmpty(), "prepare 必須使用全新世界");
            buildTrustedFixture(world);
            var registration = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME);
            require(registration.status() == ChamberRegistrationResult.Status.CREATED, "trusted fixture 必須新註冊");
            uuid = registration.chamberUuid();
            var controller = controller(world);
            controller.setChamberUuid(uuid);
            controller.setInstanceKind(ChamberInstanceKind.ORIGIN);
            controller.markDirty();
            var service = new ChamberLifecycleService(registry);
            var original = registry.records().get(uuid);
            BlockState originalBlock = world.getBlockState(CONTROLLER);
            require(!service.dismantleOrigin(authority(uuid), () -> {
                throw new AssertionError("啟用中不得呼叫世界移除");
            }), "啟用中拆除必須拒絕");
            assertRetained(world, registry, original, originalBlock);
            require(service.setOriginEnabled(authority(uuid), false), "必須停用成功");
            var disabled = registry.records().get(uuid);
            require(!service.dismantleOrigin(authority(uuid), () -> false), "callback false 必須拒絕");
            assertRetained(world, registry, disabled, originalBlock);
            var wrongWorld = new ChamberOriginAuthority(uuid, World.NETHER.getValue(), DimensionRole.OVERWORLD,
                    CONTROLLER, Direction.NORTH, ChamberInstanceKind.ORIGIN);
            require(!service.dismantleOrigin(wrongWorld, () -> {
                throw new AssertionError("錯 world 不得呼叫世界移除");
            }), "錯 world 拆除必須拒絕");
            assertRetained(world, registry, disabled, originalBlock);
            ChamberControllerBlock.refreshState(world, CONTROLLER);
            controller.markDirty();
            require(controller.chamberState() == ChamberState.INVALID, "停用為 INVALID");
        } else if (phase.equals("removed")) {
            uuid = expectedUuid();
            require(world.getBlockState(CONTROLLER).isAir(), "重啟後 Controller 必須為 air");
            require(world.getBlockEntity(CONTROLLER) == null, "不得復活 BE");
            require(registry.records().isEmpty(), "重啟後不得自動註冊／留下紀錄");
            assertCoverage(world, registry, uuid, false);
        } else {
            require(registry.records().size() == 1, "restart 必須載入唯一原紀錄");
            uuid = registry.records().values().iterator().next().chamberUuid();
            require(uuid.equals(expectedUuid()), "persisted Registry UUID 必須與 prepare 相同");
            var controller = controller(world);
            require(uuid.equals(controller.chamberUuid()), "persisted BE UUID 必須與 Registry 相同");
            require(controller.instanceKind() == ChamberInstanceKind.ORIGIN, "BE 保留 Origin 身分");
            require(!registry.records().get(uuid).enabled(), "重啟後 enabled=false");
            require(world.isReceivingRedstonePower(CONTROLLER), "restart 世界維持 held-high");
            require(controller.wasPowered() && controller.powerInitialized(), "load sync 必須同步 held-high latch");
            world.updateNeighborsAlways(CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            ChamberControllerBlock.refreshState(world, CONTROLLER);
            require(controller.chamberState() == ChamberState.INVALID, "held-high 不得 arm disabled Origin");
            require(world.getBlockState(CONTROLLER).getComparatorOutput(world, CONTROLLER) == 0, "停用 comparator=0");
            assertCoverage(world, registry, uuid, true);
            require(!world.removeBlock(CONTROLLER, false), "普通世界移除仍被保護");
            require(world.getBlockState(CONTROLLER).isOf(ModBlocks.CHAMBER_CONTROLLER), "拒絕後真實 Controller 保留");
            if (phase.equals("dismantle")) {
                require(new ChamberLifecycleService(registry).dismantleOrigin(authority(uuid), () -> {
                    assertCoverage(world, registry, uuid, true);
                    return ChamberProtectionService.get().authorizedMutation(world, CONTROLLER,
                            () -> world.removeBlock(CONTROLLER, false));
                }), "真實世界 removeBlock=true 才能拆除提交");
                require(world.getBlockState(CONTROLLER).isAir(), "拆除後真實 Controller 為 air");
                require(controller.isRemoved(), "原 BE 必須失效");
                require(registry.records().isEmpty(), "成功後刪除 Registry record");
                assertCoverage(world, registry, uuid, false);
                require(state.isDirty(), "移除後必須標記 dirty");
                require(world.getBlockState(new BlockPos(12, 64, 14)).isOf(Blocks.BEDROCK), "拆除不自動刪 shell");
            }
        }
        LoggerFactory.getLogger("quantumchamber-testmod").info("M11 phase assertions passed phase={} uuid={} javaPID={}",
                phase, uuid, ProcessHandle.current().pid());
    }

    private static void buildTrustedFixture(ServerWorld world) {
        // 明示 trusted setup；不是 native edge，也不是人工新建艙體驗收。
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            BlockState block = ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
            if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) block = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
            world.setBlockState(ChamberGeometry.localToWorld(FRAME, x, y, z), block, 3);
        }
        world.setBlockState(CONTROLLER, ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                .with(ChamberControllerBlock.FACING, Direction.NORTH), 3);
        world.setBlockState(CONTROLLER.up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
    }

    private static void assertRetained(ServerWorld world, ChamberRegistry registry, ChamberRecord original, BlockState originalBlock) {
        require(original.equals(registry.records().get(original.chamberUuid())), "拒絕前後原 record 必須相同");
        require(world.getBlockState(CONTROLLER).equals(originalBlock), "拒絕前後真實方塊完整 BlockState 必須相同");
        require(original.chamberUuid().equals(controller(world).chamberUuid()), "拒絕前後 BE UUID 必須保留");
        assertCoverage(world, registry, original.chamberUuid(), true);
    }

    private static void assertCoverage(ServerWorld world, ChamberRegistry registry, UUID uuid, boolean present) {
        // 手算 bounds x=12..18、y=64..70、z=14..20；直接檢查四個 chunk 的真索引。
        ProjectionIndex index;
        try {
            var field = ChamberRegistry.class.getDeclaredField("projectionIndex");
            field.setAccessible(true);
            index = (ProjectionIndex) field.get(registry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("無法檢查真實 chunk 索引", exception);
        }
        for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) {
            var pos = new BlockPos(x, 68, z);
            var found = registry.findAt(DimensionRole.OVERWORLD, pos);
            require(found.isPresent() == present, "所有 chunk 的保護必須一致");
            if (present) require(found.orElseThrow().chamberUuid().equals(uuid), "保護 UUID 必須相同");
            require(index.candidates(DimensionRole.OVERWORLD, pos).contains(uuid) == present, "不得留下 ghost index");
            require(ChamberProtectionService.get().mayMutate(world, pos) != present, "真實世界保護必須一致");
        }
    }

    private static ChamberControllerBlockEntity controller(ServerWorld world) {
        require(world.getBlockEntity(CONTROLLER) instanceof ChamberControllerBlockEntity, "必須載入真實 Controller BE");
        return (ChamberControllerBlockEntity) world.getBlockEntity(CONTROLLER);
    }

    private static ChamberOriginAuthority authority(UUID uuid) {
        return new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                CONTROLLER, Direction.NORTH, ChamberInstanceKind.ORIGIN);
    }

    private static UUID expectedUuid() {
        return UUID.fromString(System.getProperty("quantumchamber.m11.expectedUuid"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
