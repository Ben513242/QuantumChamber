package dev.quantumchamber.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.DynamicDimensionBackend;
import dev.quantumchamber.universe.UniverseCatalogStore;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseTransferReceipt;
import dev.quantumchamber.universe.UniverseTransferResult;
import dev.quantumchamber.universe.UniverseTransferResult.Outcome;
import dev.quantumchamber.universe.UniverseTransferService;
import dev.quantumchamber.universe.UniverseTransferTestAccess;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Clearable;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F1a 真 client probe（M5 spec §14）：只在 {@code -Dquantumchamber.clientProbe=true} 時註冊 op 2 的 {@code /qcprobe}。
 * 流程是 allocate → 重啟（由正式 lifecycle 的 bootstrap 物化）→ enter → return；本 probe 不建立 backend，也不呼叫 materialize。
 * dedicated server 與 integrated server 都會載入這個 class，不得引用 client-only 類別。
 */
public final class M5ClientProbe implements ModInitializer {
    static final String PROPERTY = "quantumchamber.clientProbe";
    static final UUID UNIVERSE = UUID.fromString("f1a00000-0000-4000-8000-00000000f1a0");
    static final List<String> SUBCOMMANDS = List.of("allocate", "enter", "return", "status");
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m5-client-probe");
    private static final Identifier AFTER = Identifier.of("quantumchamber", "m5_client_probe_after");

    @Override public void onInitialize() {
        // 沒有 property（或不是精確的 true）時不註冊任何東西。
        if (!"true".equals(System.getProperty(PROPERTY))) return;
        var probe = new Probe();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher, probe));
        // 排在正式 lifecycle（預設 phase）之後：READY 記錄與 enter 都要讀到 bootstrap 完成後的 context。
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER, probe::started);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerTickEvents.END_SERVER_TICK.register(AFTER, probe::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(probe::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(probe::stopped);
        LOGGER.info("M5_CLIENT_PROBE_ENABLED {}=true", PROPERTY);
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher, Probe probe) {
        dispatcher.register(CommandManager.literal("qcprobe").requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("universe")
                        .then(CommandManager.literal("allocate").executes(context -> probe.allocate(context.getSource())))
                        .then(CommandManager.literal("enter").executes(context -> probe.enter(context.getSource())))
                        .then(CommandManager.literal("return").executes(context -> probe.returnHome(context.getSource())))
                        .then(CommandManager.literal("status").executes(context -> probe.status(context.getSource())))));
    }

    /** 所有狀態都只在 server thread 讀寫；integrated server 可在同一個 JVM 重開，所以狀態綁定 server instance。 */
    static final class Probe {
        /** argument 是玩家 UUID：不同玩家在同一個 chunk 的 ticket 各自獨立，釋放時不會互相移除。 */
        private static final ChunkTicketType<UUID> TICKET = ChunkTicketType.create("quantumchamber_m5_client_probe", UUID::compareTo);
        /** 等待目的 chunks 到 FULL 的上限（30 秒）。 */
        private static final int TIMEOUT_TICKS = 600;
        private final Map<UUID, UniverseTransferReceipt> receipts = new HashMap<>();
        private final Map<UUID, Pending> pending = new HashMap<>();
        private MinecraftServer server;
        /** receipt 的 world token 只在建立它的 service instance 內有效，所以每個 server 只建一個。 */
        private UniverseTransferService service;
        private DynamicDimensionBackend serviceBackend;

        void started(MinecraftServer started) {
            bind(started);
            try {
                CommandNode<ServerCommandSource> root = started.getCommandManager().getDispatcher().getRoot().getChild("qcprobe");
                CommandNode<ServerCommandSource> universe = root == null ? null : root.getChild("universe");
                List<String> children = universe == null ? List.of()
                        : universe.getChildren().stream().map(CommandNode::getName).sorted().toList();
                var console = started.getCommandSource();
                boolean op1Denied = root != null && !root.getRequirement().test(console.withLevel(1));
                boolean op2Allowed = root != null && root.getRequirement().test(console.withLevel(2));
                boolean registered = children.equals(SUBCOMMANDS) && op1Denied && op2Allowed;
                String line = "M5_CLIENT_PROBE_READY server=" + kind(started) + " registered=" + registered + " subcommands=" + children
                        + " op1Denied=" + op1Denied + " op2Allowed=" + op2Allowed + " " + describeUniverse(started);
                if (registered) LOGGER.info(line); else LOGGER.error(line);
            } catch (RuntimeException failure) {
                LOGGER.error("M5_CLIENT_PROBE_READY 讀取失敗", failure);
            }
        }

        int allocate(ServerCommandSource source) {
            var current = source.getServer();
            bind(current);
            try {
                // 只在正式 lifecycle ready 且健康時配置；物化一律交給下一次啟動的 bootstrap。
                var lifecycle = UniverseTransferTestAccess.capture(current);
                var catalog = UniverseRegistryState.get(current);
                var id = UniverseId.of(UNIVERSE);
                var records = catalog.records();
                if (catalog.isDirty()) return fail(source, "ALLOCATE", "catalog 有尚未落盤的變更，拒絕配置");
                var existing = records.get(id);
                if (existing != null) {
                    boolean durable = existing.equals(catalog.flushedRecords().get(id));
                    boolean active = active(lifecycle, current, existing);
                    return ok(source, "ALLOCATE", "已配置過 " + key(existing) + " durable=" + durable + " ACTIVE=" + active
                            + (active ? "，可以 enter" : "，請重啟 server 讓 bootstrap 物化"));
                }
                if (!records.isEmpty()) {
                    return fail(source, "ALLOCATE", "catalog 已有其他 " + records.size() + " 筆 Universe；probe 只在空 catalog 配置");
                }
                var record = catalog.allocateOverworld(UNIVERSE, 0);
                catalog.flush(current);
                var path = current.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(UniverseRegistryState.STATE_ID + ".dat");
                var disk = UniverseRegistryState.fromNbt(UniverseCatalogStore.read(path).getCompound("data"));
                disk.requireHealthy();
                if (catalog.isDirty() || !disk.records().equals(catalog.records()) || !catalog.flushedRecords().equals(catalog.records())
                        || record.desiredAvailability() != DesiredAvailability.EAGER_ENABLED) {
                    return fail(source, "ALLOCATE", "checked flush 讀回不符");
                }
                return ok(source, "ALLOCATE", "已配置並 checked flush：" + key(record) + "（EAGER_ENABLED）。"
                        + "請重啟 server 讓 bootstrap 物化（單人：儲存並離開到標題畫面，再重新進入同一個世界）");
            } catch (Exception | AssertionError failure) {
                LOGGER.error("M5_CLIENT_PROBE_ALLOCATE 失敗", failure);
                return fail(source, "ALLOCATE", failure.toString());
            }
        }

        int enter(ServerCommandSource source) throws CommandSyntaxException {
            var player = source.getPlayerOrThrow();
            var current = source.getServer();
            bind(current);
            var id = player.getUuid();
            if (pending.containsKey(id)) return fail(source, "ENTER", "已有進行中的 enter／return");
            try {
                var target = activeTarget(current);
                if (!player.getServerWorld().getRegistryKey().equals(World.OVERWORLD)) {
                    return fail(source, "ENTER", "只能從原版主世界 enter；目前在 " + player.getServerWorld().getRegistryKey().getValue());
                }
                var stale = receipts.remove(id);
                String refusal = standingRefusal(player);
                if (refusal != null) return fail(source, "ENTER", refusal);
                var request = new Pending(Kind.ENTER, id, player, player.getServerWorld(), target.world(), null,
                        current.getTicks() + TIMEOUT_TICKS, new LinkedHashSet<>());
                try {
                    pending.put(id, request);
                    hold(request, requiredChunks(player.getBoundingBox()));
                } catch (RuntimeException failure) {
                    finish(request);
                    return fail(source, "ENTER", "加 chunk ticket 失敗，已釋放：" + failure);
                }
                return ok(source, "ENTER", "已在 " + target.descriptor().worldKey().getValue() + " 加 " + request.tickets().size()
                        + " 個 chunk ticket，目的 chunks 到 FULL 後自動傳送（上限 " + TIMEOUT_TICKS + " ticks）"
                        + (stale == null ? "" : "；舊 receipt 已作廢（玩家不在它的目的世界）"));
            } catch (Exception | AssertionError failure) {
                return fail(source, "ENTER", message(failure));
            }
        }

        int returnHome(ServerCommandSource source) throws CommandSyntaxException {
            var player = source.getPlayerOrThrow();
            var current = source.getServer();
            bind(current);
            var id = player.getUuid();
            if (pending.containsKey(id)) return fail(source, "RETURN", "已有進行中的 enter／return");
            var receipt = receipts.get(id);
            if (receipt == null) {
                return fail(source, "RETURN", "沒有這次 server 執行期間的 receipt（receipt 不跨重啟保留）；"
                        + "可改用原版 /execute in minecraft:overworld run tp @s <x> <y> <z> 離開");
            }
            if (!player.getServerWorld().getRegistryKey().equals(receipt.destinationKey())) {
                receipts.remove(id);
                return fail(source, "RETURN", "玩家不在 receipt 的目的世界（可能已死亡重生或以其他方式離開），receipt 已作廢");
            }
            var home = current.getWorld(receipt.source().worldKey());
            if (home == null) return fail(source, "RETURN", "receipt 的來源世界不存在：" + receipt.source().worldKey().getValue());
            var request = new Pending(Kind.RETURN, id, player, player.getServerWorld(), home, receipt,
                    current.getTicks() + TIMEOUT_TICKS, new LinkedHashSet<>());
            try {
                pending.put(id, request);
                hold(request, requiredChunks(homeBox(player, receipt)));
            } catch (RuntimeException failure) {
                finish(request);
                return fail(source, "RETURN", "加 chunk ticket 失敗，已釋放：" + failure);
            }
            return ok(source, "RETURN", "已在 " + home.getRegistryKey().getValue() + " 加 " + request.tickets().size()
                    + " 個 chunk ticket，來源 chunks 到 FULL 後自動傳回（上限 " + TIMEOUT_TICKS + " ticks）");
        }

        /** 唯讀：不配置、不落盤、不加 ticket。 */
        int status(ServerCommandSource source) {
            var current = source.getServer();
            bind(current);
            var player = source.getPlayer();
            String who = player == null ? "player=(console)"
                    : "player=" + player.getName().getString() + " world=" + player.getServerWorld().getRegistryKey().getValue()
                            + " pos=" + pos(player.getPos()) + " receipt=" + (receipts.containsKey(player.getUuid()) ? "present" : "none")
                            + " pending=" + (pending.containsKey(player.getUuid()) ? pending.get(player.getUuid()).kind() : "none");
            return ok(source, "STATUS", "server=" + kind(current) + " " + who + " " + describeUniverse(current));
        }

        void tick(MinecraftServer ticking) {
            if (ticking != server || pending.isEmpty()) return;
            for (var request : List.copyOf(pending.values())) {
                try {
                    if (advance(ticking, request)) finish(request);
                } catch (Exception | AssertionError failure) {
                    finish(request);
                    LOGGER.error("M5_CLIENT_PROBE_{} 失敗", request.kind(), failure);
                    notify(request, false, message(failure));
                }
            }
        }

        void stopping(MinecraftServer stopping) {
            if (stopping != server) return;
            int cancelled = pending.size();
            for (var request : List.copyOf(pending.values())) finish(request);
            LOGGER.info("M5_CLIENT_PROBE_STOPPING server={} pendingCancelled={} receipts={}", kind(stopping), cancelled, receipts.size());
        }

        void stopped(MinecraftServer stopped) {
            if (stopped != server) return;
            server = null;
            service = null;
            serviceBackend = null;
            receipts.clear();
            pending.clear();
        }

        /** 回傳 true 表示這個 request 已結束（成功或失敗都已回報）。 */
        private boolean advance(MinecraftServer current, Pending request) throws Exception {
            var player = current.getPlayerManager().getPlayer(request.playerId());
            if (player != request.player() || player.isRemoved()) return cancel(request, "玩家已離線或換了 entity");
            if (player.getServerWorld() != request.from()) return cancel(request, "玩家已換世界");
            // 等待期間玩家可能移動：每 tick 依目前 pose 重算需要的 chunks，補上缺的 ticket。
            Box box = request.kind() == Kind.ENTER ? player.getBoundingBox() : homeBox(player, request.receipt());
            Set<ChunkPos> needed = requiredChunks(box);
            hold(request, needed);
            String refusal = request.kind() == Kind.ENTER ? standingRefusal(player) : null;
            boolean full = needed.stream().allMatch(chunk -> request.world().getChunkManager().getWorldChunk(chunk.x, chunk.z) != null);
            if (!full || refusal != null) {
                if (current.getTicks() <= request.deadline()) return false;
                return cancel(request, refusal != null ? "逾時：" + refusal : "逾時：chunks 尚未全部 FULL " + needed);
            }
            if (request.kind() == Kind.ENTER) {
                var target = activeTarget(current);
                if (target.world() != request.world()) return cancel(request, "目的 world instance 已改變");
                var changed = prepareLanding(target.world(), box);
                var result = service(current, target).moveToUniverse(player, target.descriptor());
                if (result.outcome() == Outcome.MOVED) receipts.put(request.playerId(), result.receipt());
                report(request, result, result.outcome() == Outcome.MOVED, "landingChanges=" + changed);
            } else {
                var result = service.returnToSource(player, request.receipt());
                if (result.outcome() == Outcome.RETURNED) receipts.remove(request.playerId());
                report(request, result, result.outcome() == Outcome.RETURNED, "");
            }
            return true;
        }

        private UniverseTransferService service(MinecraftServer current, Target target) {
            var backend = target.lifecycle().backend();
            if (service == null) {
                service = new UniverseTransferService(backend, new SessionTransferService());
                serviceBackend = backend;
            } else if (serviceBackend != backend) {
                throw new Refusal("正式 lifecycle 的 backend instance 已改變（server=" + kind(current) + "）");
            }
            return service;
        }

        /** 每次都重新取得正式 lifecycle（必須 ready 且健康）與 exact ACTIVE world；不建立 backend、不物化。 */
        private static Target activeTarget(MinecraftServer current) throws ReflectiveOperationException {
            var lifecycle = UniverseTransferTestAccess.capture(current);
            var record = UniverseRegistryState.get(current).flushedRecords().get(UniverseId.of(UNIVERSE));
            if (record == null) throw new Refusal("catalog 沒有 durable 的 probe Universe；先 /qcprobe universe allocate，再重啟 server");
            var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
            var world = lifecycle.backend().resolveActive(current, descriptor).orElse(null);
            if (world == null || current.getWorld(descriptor.worldKey()) != world) {
                throw new Refusal("probe Universe 尚未由正式 lifecycle 物化（不是 ACTIVE）；請重啟 server");
            }
            return new Target(lifecycle, descriptor, world);
        }

        private String describeUniverse(MinecraftServer current) {
            var parts = new ArrayList<String>();
            UniverseTransferTestAccess.Lifecycle lifecycle = null;
            try {
                lifecycle = UniverseTransferTestAccess.capture(current);
                parts.add("lifecycle=READY");
            } catch (Exception | AssertionError failure) {
                parts.add("lifecycle=UNAVAILABLE(" + failure + ")");
            }
            try {
                var catalog = UniverseRegistryState.get(current);
                var records = catalog.records();
                parts.add("catalog=" + records.size() + " dirty=" + catalog.isDirty());
                for (var record : records.values()) {
                    boolean probeOwned = record.definition().universeId().value().equals(UNIVERSE);
                    parts.add("[" + key(record) + " " + record.desiredAvailability()
                            + " durable=" + record.equals(catalog.flushedRecords().get(record.definition().universeId()))
                            + " ACTIVE=" + (lifecycle != null && active(lifecycle, current, record)) + (probeOwned ? " probe" : "") + "]");
                }
            } catch (RuntimeException failure) {
                parts.add("catalog=UNREADABLE(" + failure + ")");
            }
            return String.join(" ", parts);
        }

        private static boolean active(UniverseTransferTestAccess.Lifecycle lifecycle, MinecraftServer current, UniverseRecord record) {
            var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
            var world = lifecycle.backend().resolveActive(current, descriptor).orElse(null);
            return world != null && current.getWorld(descriptor.worldKey()) == world;
        }

        private void bind(MinecraftServer current) {
            if (server == current) return;
            server = current;
            service = null;
            serviceBackend = null;
            receipts.clear();
            pending.clear();
        }

        private static void hold(Pending request, Set<ChunkPos> chunks) {
            for (var chunk : chunks) {
                if (request.tickets().add(chunk)) request.world().getChunkManager().addTicket(TICKET, chunk, 0, request.playerId());
            }
        }

        private void finish(Pending request) {
            for (var chunk : request.tickets()) request.world().getChunkManager().removeTicket(TICKET, chunk, 0, request.playerId());
            request.tickets().clear();
            pending.remove(request.playerId(), request);
        }

        private boolean cancel(Pending request, String reason) {
            LOGGER.warn("M5_CLIENT_PROBE_{} status=CANCELLED player={} reason={}", request.kind(), request.playerId(), reason);
            notify(request, false, "已取消：" + reason);
            return true;
        }

        private void report(Pending request, UniverseTransferResult result, boolean success, String extra) {
            String detail = "outcome=" + result.outcome() + " decision=" + result.decision() + " native=" + result.move().outcome()
                    + " rollback=" + result.rollback().outcome()
                    + (result.actual() == null ? "" : " world=" + result.actual().worldKey().getValue() + " pos=" + pos(result.actual().position()))
                    + (result.detail().isEmpty() ? "" : " detail=" + result.detail()) + (extra.isEmpty() ? "" : " " + extra);
            String next = nextStep(request, result.outcome());
            if (success) LOGGER.info("M5_CLIENT_PROBE_{} status=OK player={} {}", request.kind(), request.playerId(), detail);
            else LOGGER.error("M5_CLIENT_PROBE_{} status=FAIL player={} {} next={}", request.kind(), request.playerId(), detail, next);
            notify(request, success, next.isEmpty() ? detail : detail + "｜" + next);
        }

        /** 失敗時給操作者的下一步；成功時為空字串。 */
        private static String nextStep(Pending request, Outcome outcome) {
            return switch (outcome) {
                case MOVED, RETURNED -> "";
                case REJECTED_BEFORE_MOVE -> request.kind() == Kind.ENTER
                        ? "未移動；修正位置後可以再 enter"
                        : "未移動、receipt 保留；修正來源位置後可以再 return，或用 /execute in "
                                + request.receipt().source().worldKey().getValue() + " run tp @s "
                                + String.format(Locale.ROOT, "%.3f %.3f %.3f", request.receipt().source().position().x,
                                        request.receipt().source().position().y, request.receipt().source().position().z);
                case FAILED_ROLLED_BACK -> "已回到移動前的位置；可以再試";
                case FAILED_RECOVERY_REQUIRED -> "玩家狀態需要人工確認：依 F1b 規則停止並保留證據";
            };
        }

        /** 送給目前在線的同一位玩家（重新登入後是新的 entity）；離線時不送。 */
        private void notify(Pending request, boolean success, String text) {
            var player = server == null ? null : server.getPlayerManager().getPlayer(request.playerId());
            if (player != null) {
                player.sendMessage(Text.literal("[qcprobe] " + request.kind() + (success ? " OK " : " FAIL ") + text), false);
            }
        }

        private static int ok(ServerCommandSource source, String operation, String text) {
            LOGGER.info("M5_CLIENT_PROBE_{} status=OK by={} {}", operation, source.getName(), text);
            source.sendFeedback(() -> Text.literal("[qcprobe] " + text), false);
            return 1;
        }

        private static int fail(ServerCommandSource source, String operation, String text) {
            LOGGER.warn("M5_CLIENT_PROBE_{} status=FAIL by={} {}", operation, source.getName(), text);
            source.sendError(Text.literal("[qcprobe] " + text));
            return 0;
        }

        /** 與 UniverseTransferService 的安全檢查同一個範圍：bbox 外擴 2 格涵蓋的 chunks 都必須 FULL。 */
        static Set<ChunkPos> requiredChunks(Box box) {
            var chunks = new LinkedHashSet<ChunkPos>();
            for (int x = MathHelper.floor(box.minX - 2) >> 4; x <= MathHelper.floor(box.maxX + 2) >> 4; x++) {
                for (int z = MathHelper.floor(box.minZ - 2) >> 4; z <= MathHelper.floor(box.maxZ + 2) >> 4; z++) {
                    chunks.add(new ChunkPos(x, z));
                }
            }
            return chunks;
        }

        /**
         * enter 的受理條件，與 TransferSafety.M3_SAFE_LANDING 的位置條件相同：腳底恰在方塊頂面、bbox 下方每一欄都是
         * 完整頂面、沒有流體、isSpaceEmpty。transfer 沿用同一個 XYZ，而 return 會在同一個來源位置套用同樣的檢查，
         * 所以這裡不合格就不受理，避免 enter 成功、return 卻被 REJECT_TARGET_UNSAFE 拒絕。
         */
        private static String standingRefusal(ServerPlayerEntity player) {
            var world = player.getServerWorld();
            var box = player.getBoundingBox();
            return standingRefusal(player.hasVehicle(), player.isOnGround(), box, world, world.containsFluid(box),
                    world.isSpaceEmpty(player, box));
        }

        static String standingRefusal(boolean riding, boolean onGround, Box box, BlockView world, boolean fluid, boolean spaceEmpty) {
            if (riding) return "請先離開坐騎";
            if (!onGround) return "請站在地面上（不能在空中、飛行或跳躍中）";
            if (Math.abs(box.minY - (MathHelper.floor(box.minY - 1e-7) + 1.0)) > 1e-6) {
                return "請站在完整方塊的頂面（目前 Y=" + box.minY + "）";
            }
            if (fluid) return "請離開水或岩漿";
            for (var floor : floorColumns(box)) {
                if (!world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP)) {
                    return "腳下 " + floor.toShortString() + " 不是完整方塊頂面（例如樹葉、半磚，或站在方塊邊緣）；請站到完整方塊的中央或換個位置";
                }
            }
            if (!spaceEmpty) return "目前位置與方塊重疊；請換個位置";
            return null;
        }

        /** bbox 下方每一欄的地板位置；欄位範圍與 TransferSafety.M3_SAFE_LANDING 的地板檢查相同。 */
        static List<BlockPos> floorColumns(Box box) {
            var floors = new ArrayList<BlockPos>();
            int floorY = MathHelper.floor(box.minY - 1e-7);
            for (int x = MathHelper.floor(box.minX + 1e-7); x <= MathHelper.floor(box.maxX - 1e-7); x++) {
                for (int z = MathHelper.floor(box.minZ + 1e-7); z <= MathHelper.floor(box.maxZ - 1e-7); z++) {
                    floors.add(new BlockPos(x, floorY, z));
                }
            }
            return floors;
        }

        /**
         * 在目的 world 讓 transfer 的安全檢查成立：bbox 下方每一欄的頂面不完整時放石頭，bbox 內有碰撞或流體的方塊改成空氣。
         * 寫入方式比照原版 /setblock（plan 決策 3）。回傳 setBlockState 確實改動的位置。
         */
        private static List<String> prepareLanding(ServerWorld world, Box box) {
            var changed = new ArrayList<String>();
            int topY = MathHelper.floor(box.maxY - 1e-7);
            for (var floor : floorColumns(box)) {
                if (!world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP)
                        && replace(world, floor, Blocks.STONE.getDefaultState())) {
                    changed.add(floor.toShortString() + "=stone");
                }
                for (int y = floor.getY() + 1; y <= topY; y++) {
                    var pos = new BlockPos(floor.getX(), y, floor.getZ());
                    var state = world.getBlockState(pos);
                    if ((!state.getCollisionShape(world, pos).isEmpty() || !state.getFluidState().isEmpty())
                            && replace(world, pos, Blocks.AIR.getDefaultState())) {
                        changed.add(pos.toShortString() + "=air");
                    }
                }
            }
            return changed;
        }

        /** 先清空 block entity 的內容，再以 NOTIFY_LISTENERS｜FORCE_STATE｜SKIP_DROPS 寫入：不噴物品、不觸發鄰格更新。 */
        private static boolean replace(ServerWorld world, BlockPos pos, BlockState state) {
            Clearable.clear(world.getBlockEntity(pos));
            return world.setBlockState(pos, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
        }

        private static Box homeBox(ServerPlayerEntity player, UniverseTransferReceipt receipt) {
            return player.getBoundingBox().offset(receipt.source().position().subtract(player.getPos()));
        }

        private static String key(UniverseRecord record) {
            return record.definition().worlds().get(DimensionRole.OVERWORLD).worldKey().getValue().toString();
        }

        private static String kind(MinecraftServer current) { return current.isDedicated() ? "dedicated" : "integrated"; }

        private static String pos(Vec3d position) {
            return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", position.x, position.y, position.z);
        }

        private static String message(Throwable failure) {
            return failure instanceof Refusal ? failure.getMessage() : failure.toString();
        }
    }

    enum Kind { ENTER, RETURN }

    /** world 是要等 FULL 的世界（enter 為目的 Universe，return 為來源世界）；from 是下指令時玩家所在的世界。 */
    record Pending(Kind kind, UUID playerId, ServerPlayerEntity player, ServerWorld from, ServerWorld world,
                   UniverseTransferReceipt receipt, int deadline, Set<ChunkPos> tickets) { }

    record Target(UniverseTransferTestAccess.Lifecycle lifecycle, UniverseWorldDescriptor descriptor, ServerWorld world) { }

    /** 前置條件不成立時的拒絕；訊息直接回給玩家。 */
    static final class Refusal extends RuntimeException {
        Refusal(String message) { super(message); }
    }
}
