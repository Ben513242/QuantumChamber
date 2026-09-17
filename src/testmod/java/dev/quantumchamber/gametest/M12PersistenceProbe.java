package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 僅測試模組：各階段由外部啟動獨立 JVM，成功後仍等待 console stop。 */
public final class M12PersistenceProbe implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final BlockPos CONTROLLER = new BlockPos(15, 70, 14);
    private static final BlockPos LEVER = CONTROLLER.north();
    private static final ChamberFrame FRAME = new ChamberFrame(CONTROLLER, Direction.NORTH);
    private int ticks;

    @Override
    public void onInitialize() {
        String phase = System.getProperty("quantumchamber.m12.phase", "");
        if (phase.isEmpty()) return;
        require(Set.of("powered", "off", "rearm", "removed", "corrupt").contains(phase), "未知 phase");
        String nonce = System.getProperty("quantumchamber.m12.nonce", "");
        require(!nonce.isBlank(), "獨立程序必須提供 nonce");
        LOGGER.info("M12 probe initialized phase={} javaPID={} nonce={}", phase, ProcessHandle.current().pid(), nonce);
        if (phase.equals("corrupt")) {
            ServerLifecycleEvents.SERVER_STARTING.register(M12PersistenceProbe::writeCorruptFixture);
            ServerTickEvents.START_SERVER_TICK.register(server -> {
                LOGGER.error("M12 corrupt reached normal tick: health guard failed");
                throw new AssertionError("損壞 Registry 不得進入正常 tick");
            });
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks == 1) {
                loadFixtureChunks(server.getOverworld());
                if (phase.equals("powered")) {
                    require(ChamberRegistryState.get(server).registry().records().isEmpty(), "powered 必須使用全新世界");
                    buildFixture(server.getOverworld());
                }
            }
            if (ticks != 40) return;
            require(server.isOnThread(), "探針必須在 server thread");
            UUID uuid = runPhase(server, phase);
            LOGGER.info("M12 phase assertions passed phase={} uuid={} javaPID={} nonce={}",
                    phase, uuid, ProcessHandle.current().pid(), nonce);
        });
    }

    private static UUID runPhase(MinecraftServer server, String phase) {
        var world = server.getOverworld();
        loadFixtureChunks(world);
        var state = ChamberRegistryState.get(server);
        state.requireHealthy();
        var registry = state.registry();
        UUID uuid;
        if (phase.equals("powered")) {
            require(registry.records().isEmpty(), "powered 必須使用全新世界");
            // 放置 BE 後先經過原生下一 tick 的 load-sync，再操作拉桿。
            require(controller(world).powerInitialized()
                    && !ChamberLoadSyncTestAccess.hasPending(server, controller(world)), "草稿必須先完成原生載入同步");
            require(controller(world).chamberUuid() == null, "未供電草稿不得有 UUID");
            require(!world.isReceivingRedstonePower(CONTROLLER), "草稿必須為真實低電位");
            toggleLever(world);
            uuid = controller(world).chamberUuid();
            require(uuid != null, "原生拉桿高位必須註冊空艙");
            assertOrigin(world, registry, uuid, true);
        } else {
            uuid = UUID.fromString(System.getProperty("quantumchamber.m12.expectedUuid"));
            // 重啟後先驗證已儲存的拉桿／BE／Registry，再改變原生電位。
            assertOrigin(world, registry, uuid, !phase.equals("rearm"));
            toggleLever(world);
            assertOrigin(world, registry, uuid, phase.equals("rearm"));
            if (phase.equals("removed")) {
                var previous = controller(world);
                require(world.removeBlock(CONTROLLER, false), "OFF 且 enabled=true 必須允許真實移除 Controller");
                require(world.getBlockState(CONTROLLER).isAir(), "移除後 Controller 必須為 air");
                require(world.getBlockEntity(CONTROLLER) == null && previous.isRemoved(), "移除後 BE 必須失效");
                require(registry.records().isEmpty(), "成功移除後 Registry record 必須消失");
                assertCoverage(world, registry, uuid, false, false);
                require(state.isDirty(), "移除必須標記持久狀態 dirty");
                require(world.getBlockState(new BlockPos(12, 64, 14)).isOf(Blocks.BEDROCK), "移除 C 不得刪除外殼");
            }
        }
        return uuid;
    }

    private static void assertOrigin(ServerWorld world, ChamberRegistry registry, UUID uuid, boolean powered) {
        require(registry.records().size() == 1, "必須只有同一原艙");
        var record = registry.records().get(uuid);
        require(record != null && record.enabled(), "同一 UUID 與 enabled=true 必須保留");
        require(record.powerState() == (powered ? ChamberPowerState.POWERED : ChamberPowerState.OFF), "Registry 必須反映真實電位");
        var controller = controller(world);
        require(uuid.equals(controller.chamberUuid()), "BE 與 Registry UUID 必須相同");
        require(controller.instanceKind() == ChamberInstanceKind.ORIGIN, "BE 必須保留原艙身分");
        require(world.getBlockState(LEVER).isOf(Blocks.LEVER), "必須保留原生拉桿");
        require(world.getBlockState(LEVER).get(LeverBlock.POWERED) == powered, "拉桿狀態必須持久化");
        require(world.isReceivingRedstonePower(CONTROLLER) == powered, "重讀世界實際紅石電位");
        require(controller.powerInitialized() && controller.wasPowered() == powered, "載入同步必須反映實際電位");
        require(new ChamberOccupantService().findParticipants(world, FRAME).isEmpty(), "此 fixture 為空艙");
        require(controller.chamberState() == (powered ? ChamberState.IDLE : ChamberState.INVALID), "空艙高位 IDLE，低位 INVALID");
        require(world.getBlockState(CONTROLLER).getComparatorOutput(world, CONTROLLER) == (powered ? 3 : 0), "比較器必須符合空艙供電狀態");
        assertCoverage(world, registry, uuid, true, powered);
        if (powered) require(!world.removeBlock(CONTROLLER, false), "高位必須阻擋原生拆除 C");
        else {
            var cell = new BlockPos(15, 68, 16);
            require(world.setBlockState(cell, Blocks.STONE.getDefaultState()), "OFF 必須允許真實普通修改");
            require(world.setBlockState(cell, Blocks.AIR.getDefaultState()), "恢復 fixture 空間");
        }
    }

    private static void toggleLever(ServerWorld world) {
        BlockState lever = world.getBlockState(LEVER);
        require(lever.isOf(Blocks.LEVER), "只能操作真實拉桿");
        // 原生 togglePower 包含方塊寫入與鄰居通知；不直接呼叫註冊或供電協調 helper。
        ((LeverBlock) Blocks.LEVER).togglePower(lever, world, LEVER, null);
    }

    private static void buildFixture(ServerWorld world) {
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            BlockState block = ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
            if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) {
                block = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, true);
            }
            world.setBlockState(ChamberGeometry.localToWorld(FRAME, x, y, z), block, 3);
        }
        world.setBlockState(CONTROLLER, ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                .with(ChamberControllerBlock.FACING, Direction.NORTH), 3);
        world.setBlockState(LEVER, Blocks.LEVER.getDefaultState()
                .with(LeverBlock.FACE, BlockFace.WALL).with(LeverBlock.FACING, Direction.NORTH), 3);
    }

    private static void loadFixtureChunks(ServerWorld world) {
        for (int x : new int[] {0, 1}) for (int z : new int[] {0, 1}) world.getChunk(x, z);
    }

    private static void assertCoverage(ServerWorld world, ChamberRegistry registry, UUID uuid,
                                       boolean indexed, boolean protectedOrigin) {
        ProjectionIndex index;
        try {
            var field = ChamberRegistry.class.getDeclaredField("projectionIndex");
            field.setAccessible(true);
            index = (ProjectionIndex) field.get(registry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("無法檢查真實四 chunk 索引", exception);
        }
        for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) {
            var pos = new BlockPos(x, 68, z);
            for (var role : DimensionRole.values()) {
                boolean expected = indexed && role == DimensionRole.OVERWORLD;
                require(index.candidates(role, pos).contains(uuid) == expected, "全部 role/chunk 索引必須一致");
                var found = registry.findAt(role, pos);
                require(found.isPresent() == expected, "碰撞查詢不得殘留 ghost record");
                if (expected) require(found.orElseThrow().chamberUuid().equals(uuid), "碰撞 UUID 必須相同");
            }
            require(ChamberProtectionService.get().mayMutate(world, pos) != protectedOrigin, "保護與索引必須分別核對");
        }
    }

    private static void writeCorruptFixture(MinecraftServer server) {
        try {
            Path fixture = Path.of(System.getProperty("quantumchamber.m12.fixture")).toRealPath();
            Path world = server.getSavePath(WorldSavePath.ROOT).toRealPath();
            String corruption = System.getProperty("quantumchamber.m12.corruption", "schema99");
            boolean finalFix = fixture.getParent().getFileName().toString().equals("m12-final-fix")
                    && fixture.getParent().getParent().getFileName().toString().equals("run");
            require(finalFix || fixture.getFileName().toString().startsWith("m12-corrupt")
                    && fixture.getParent().getFileName().toString().equals("run"), "損壞探針只能使用 owned fixture");
            require(world.equals(fixture.resolve("world")), "實際世界必須是指定 fixture/world");
            Path data = world.resolve("data");
            Path target = data.resolve(ChamberRegistryState.STATE_ID + ".dat");
            require(!Files.exists(target), "不得覆寫已存在的 Registry");
            Files.createDirectories(data);
            var invalid = new NbtCompound();
            invalid.putInt("SchemaVersion", 99);
            invalid.put("Records", new NbtList());
            var wrapper = new NbtCompound();
            wrapper.put("data", invalid);
            Path hashTarget = target;
            switch (corruption) {
                case "schema99" -> NbtIo.writeCompressed(wrapper, target);
                case "truncated" -> Files.write(target, new byte[] {0x1f, (byte) 0x8b, 8});
                case "invalidgzip" -> Files.write(target, new byte[] {0x1f, (byte) 0x8b, 7, 0, 0, 0, 0, 0, 0, 0});
                case "invalidnbt" -> {
                    try (var gzip = new java.util.zip.GZIPOutputStream(Files.newOutputStream(target))) {
                        gzip.write(new byte[] {99, 0, 0});
                    }
                }
                case "readfailure" -> {
                    Files.createDirectory(target);
                    hashTarget = target.resolve("preserve.txt");
                    Files.writeString(hashTarget, "讀取失敗時必須保留的原始資料");
                }
                default -> throw new AssertionError("未知損壞種類");
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(hashTarget)));
            LOGGER.info("M12 corrupt fixture written stateId={} sha256={} path={} kind={}", ChamberRegistryState.STATE_ID, hash, target, corruption);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("無法建立 fresh corrupt fixture", exception);
        }
    }

    private static ChamberControllerBlockEntity controller(ServerWorld world) {
        require(world.getBlockEntity(CONTROLLER) instanceof ChamberControllerBlockEntity, "必須載入真實 Controller BE");
        return (ChamberControllerBlockEntity) world.getBlockEntity(CONTROLLER);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
