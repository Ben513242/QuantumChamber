package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M7HomeClosurePhases.FRAME;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.HALT_EXIT;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.HALT_FAILED_EXIT;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.JSON;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.LOGGER;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.doorSummary;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.field;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.gate;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.listing;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.pose;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.require;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.sha256;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.text;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.writeDurably;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberOccupantService;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.HomeEndpointAuthority;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.HomeSessionRecord.RootStop;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.JournalSession;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 僅 testmod（M7 T5-5）：Windows 原生 corridor 族——開服接手（attach）與走廊恢復（M7 spec §6、§9.2、§10；plan Task 5 :327；設計稿第 7 節）。
 * <p>10 列（{@link Row}），每列兩個 phase、各一個獨立 JVM：
 * <ul>
 * <li>{@code crash}：fresh 世界蓋 A（供電、正式 {@code attemptArm} 登記）、物化 B 的 COMPLETE 投影（關門、供電），參與者站在 B 艙內；基線 1（A＋B 的世界 checkpoint
 *     ＋{@code saveAllPlayerData}）之後以正式 {@code startHome} 入場。SUPERPOSITION 就緒之後（第 2～10 列）做基線 2（走廊租約與 B 的世界 checkpoint，不存 playerdata：
 *     登入位置由 production 入場時的 {@code saveAndVerify} 決定，N-2）。之後依列走到 cut：probe 的 END tick（AFTER phase）觀察後 halt，或由
 *     {@code mixin.M7HomeCorridorWindowMixin} 的通知（journal 讀回之前、HOME checkpoint 的 HEAD／RETURN）halt，寫 {@code halt.json} 並 {@code Runtime.halt(77)}。</li>
 * <li>{@code reopen}：新 JVM 開同一個世界；開服到登入之前不加測試票、不載入 B。參與者依列的時機登入（第 2、3 列開服即登入；其他列等正式 B 來源票載入 B 之後），
 *     觀察正式引擎把人送回 exact B、清理、checkpoint，並核對各列的終局，正常停服（{@code final.json}）。</li>
 * </ul>
 * 事件錄製規則（設計稿 7.2，定死）：{@code journal-readback} 一律不錄、只當第 4 列的 cut；{@code home-checkpoint-head／return} 只錄本 SID；close／收據事件照名稱錄，
 * 另記本 SID 的 B 來源票、B 收尾票與 B 的玩家數。runner（{@code m7-home-probe.ps1}）只比對事件名稱序列。
 */
final class M7HomeCorridorPhases {
    static final String HEAD = "home-checkpoint-head", RETURN = "home-checkpoint-return", READBACK = "journal-readback";
    static final int READY_TICKS = 200, STEP_TICKS = 800, REOPEN_TICKS = 1600, QUIET_TICKS = 40, SECOND_LOGIN_DELAY = 40;

    /** 10 列（{@code cut} 是列名去掉 {@code corridor-}），順序即 runner manifest 與 inventory 的順序。 */
    enum Row {
        ARMING("arming", 1), SUPERPOSITION("superposition", 1), SELECT_PENDING("select-pending", 1), SELECT_UNCONFIRMED("select-unconfirmed", 1),
        MEASURED("measured", 1), PARTIAL_RETURNED("partial-returned", 2), CLEANUP_BEFORE("cleanup-before", 1), CLEANUP_AFTER("cleanup-after", 1),
        COLLAPSING_PARTIAL("collapsing-partial", 2), MEASURED_STOPPED("measured-stopped", 1);

        final String cut;
        final int players;

        Row(String cut, int players) { this.cut = cut; this.players = players; }

        String rowName() { return "corridor-" + cut; }

        /** 第 3 列起要點 HOME 門。 */
        boolean selects() { return ordinal() >= SELECT_PENDING.ordinal(); }

        /** 未選定、reopen 之後整組返回 B、列移除（第 1～3 列）。 */
        boolean removedEnd() { return this == ARMING || this == SUPERPOSITION || this == SELECT_PENDING; }

        /** 選定、reopen 之後保留式返還到 DORMANT（第 4～8 列）。 */
        boolean dormantEnd() { return this == SELECT_UNCONFIRMED || this == MEASURED || this == PARTIAL_RETURNED || this == CLEANUP_BEFORE || this == CLEANUP_AFTER; }

        /** 第 2、3 列在 SERVER_STARTED 就登入（B 票取到之前，參與者在走廊）。 */
        boolean earlyLogin() { return this == SUPERPOSITION || this == SELECT_PENDING; }

        List<String> crashEvents() {
            return switch (this) {
                case CLEANUP_BEFORE -> List.of(HEAD);
                case CLEANUP_AFTER -> List.of(HEAD, RETURN);
                default -> List.of();
            };
        }

        List<String> reopenEvents() {
            var events = new ArrayList<>(List.of(HEAD, RETURN));
            if (this == MEASURED_STOPPED) events.addAll(M7HomeClosurePhases.CUTS);
            return List.copyOf(events);
        }

        static Row of(String name) {
            for (var row : values()) if (row.rowName().equals(name)) return row;
            throw new IllegalStateException("M7_HOME_ROW_UNKNOWN：" + name);
        }
    }

    static final List<String> NAMES = java.util.Arrays.stream(Row.values()).map(Row::rowName).toList();

    private enum Step { SETUP, BASELINE, AWAIT_ARMING, AWAIT_SUPERPOSITION, AWAIT_SELECT, AWAIT_PARTIAL, AWAIT_CLEANUP, REOPEN, QUIET, DONE }

    private M7HomeCorridorPhases() {}

    /** 一個 phase 的 runner。只在 server thread 執行（cut 通知由正式 owner 在 server thread 呼叫）。 */
    static final class Run implements M7HomeProbe.Phase {
        private static final Identifier AFTER = Identifier.of("quantumchamber:m7_home_corridor_after");
        private static final Identifier BEFORE = Identifier.of("quantumchamber:m7_home_corridor_before");
        final M7HomeClosurePhases.Configuration config;
        final Row row;
        private final Map<String, Object> proof = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Boolean> observed = new LinkedHashMap<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final List<Map<String, Object>> trace = new ArrayList<>();
        private final Map<String, Object> startFiles = new LinkedHashMap<>();
        private MinecraftServer server;
        private ServerWorld overworld, b;
        private UUID chamber, sid;
        private final List<UUID> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<ConnectedGameTestPlayer> participants = new ArrayList<>();
        private ChamberControllerBlockEntity bController;
        private List<String> homeChunks;
        private Step step = Step.SETUP;
        private int stepTicks, cutHits, startedTick = -1, ticketTick = -1, loginTick = -1, prevBPlayers;
        private boolean readbackArmed, finished, passed, bFullSeen, moved, fenceAtFirstTick, converted, selectedSeen, rootTicketSeen, entryTicketSeen,
                doorWitnessed, firstTick = true;
        private volatile boolean stoppingSeen;
        private JsonObject crash;
        private List<Map<String, Object>> loginPoses;
        private final Map<String, Object> lastWorld = new LinkedHashMap<>();
        private List<Map<String, Object>> endPoses;
        private int eventsBeforeQuiet;
        private String rowBeforeQuiet;
        private String secondPoseText;
        private Map<String, Object> secondPose;

        Run(M7HomeClosurePhases.Configuration config) {
            this.config = config;
            this.row = Row.of(config.row());
        }

        private boolean crashPhase() { return config.phase().equals("crash"); }

        @Override public void start() {
            proof.put("producer", "M7HomeProbe");
            proof.put("m7ProbeSchema", 1);
            proof.put("row", row.rowName());
            proof.put("door", "ordinary");
            proof.put("cut", row.cut);
            proof.put("group", "CORRIDOR");
            proof.put("phase", config.phase());
            proof.put("rowTable", M7HomeClosurePhases.rows());
            proof.put("nonce", config.nonce());
            proof.put("startupNonce", config.startupNonce());
            proof.put("pid", ProcessHandle.current().pid());
            proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            proof.put("root", config.root().toString());
            proof.put("runtimeScope", "testmod-present");
            proof.put("loadedModIds", FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList());
            proof.put("failures", failures);
            if (!crashPhase()) {
                // 開服之前讀 crash 留下的原件：journal 檔的 bytes 就是 crash 當下的狀態。
                try {
                    crash = JsonParser.parseString(Files.readString(config.evidence().resolveSibling("crash").resolve("halt.json"))).getAsJsonObject();
                    startFiles.put("journalSha256", sha256(config.root().resolve("world/data/" + SessionRecoveryState.STATE_ID + ".dat")));
                } catch (Exception | Error failure) {
                    failures.add("crashReceiptUnreadable：" + failure);
                    LOGGER.error("M7 corridor probe：讀不到 crash 的原件", failure);
                }
                proof.put("startFiles", startFiles);
            }
            ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerLifecycleEvents.SERVER_STARTED.register(AFTER, this::started);
            ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerTickEvents.END_SERVER_TICK.register(AFTER, this::tick);
            ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(BEFORE, Event.DEFAULT_PHASE);
            ServerLifecycleEvents.SERVER_STOPPING.register(BEFORE, this::stopping);
            ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerLifecycleEvents.SERVER_STOPPED.register(AFTER, this::stopped);
            LOGGER.info("M7_HOME_BEGIN row={} phase={} startupNonce={}", row.rowName(), config.phase(), config.startupNonce());
        }

        // ---- 生命週期 ----

        private void started(MinecraftServer owner) {
            if (server != null) return;
            server = owner;
            overworld = owner.getOverworld();
            guarded(() -> {
                require(failures.isEmpty(), "M7_HOME_PRESTART_FAILED " + failures);
                require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world").toRealPath()), "M7_HOME_SAVE_ROOT");
                homeChunks = M7HomeClosurePhases.homeChunks();
                startedTick = owner.getTicks();
                if (crashPhase()) setupCrash(); else setupReopen();
            });
        }

        private void tick(MinecraftServer owner) {
            if (owner != server || finished || step == Step.SETUP) return;
            guarded(() -> {
                stepTicks++;
                switch (step) {
                    case BASELINE -> baseline();
                    case AWAIT_ARMING -> awaitArming();
                    case AWAIT_SUPERPOSITION -> awaitSuperposition();
                    case AWAIT_SELECT -> awaitSelect();
                    case AWAIT_PARTIAL -> awaitPartial();
                    case AWAIT_CLEANUP -> require(stepTicks <= STEP_TICKS, "cleanupCutReached：" + STEP_TICKS + " 個 tick 內沒有走到 " + row.cut + "；events=" + eventNames());
                    case REOPEN -> reopenTick();
                    case QUIET -> quiet();
                    default -> { }
                }
            });
        }

        private void stopping(MinecraftServer owner) {
            if (owner != server) return;
            stoppingSeen = true;
            proof.put("stoppingSeen", true);
            for (var participant : participants) {
                try { participant.close(); }
                catch (RuntimeException failure) { failures.add("closeParticipant：" + failure); }
            }
        }

        private void stopped(MinecraftServer owner) {
            if (owner != server) return;
            proof.put("stoppedSeen", true);
            proof.put("ending", "STOP");
            proof.put("events", events);
            proof.put("trace", trace);
            proof.put("observed", observed);
            proof.put("status", passed && failures.isEmpty() && stoppingSeen ? "PASS" : "FAIL");
            try { writeDurably(config.evidence().resolve("final.json"), JSON.toJson(proof)); }
            catch (IOException failure) { LOGGER.error("M7 corridor probe：final receipt 寫不出來", failure); }
            LOGGER.info("M7_HOME_END row={} phase={} status={}", row.rowName(), config.phase(), proof.get("status"));
        }

        private void stop() {
            if (finished) return;
            finished = true;
            proof.put("stopRequested", true);
            server.stop(false);
        }

        private void guarded(M7HomeClosurePhases.Checked action) {
            try { action.run(); }
            catch (Exception | Error failure) {
                failures.add(failure.toString());
                LOGGER.error("M7 corridor probe 失敗：row={} phase={}", row.rowName(), config.phase(), failure);
                // crash phase 的失敗不 halt（沒有乾淨的 cut）：正常停服，runner 以 M7_HOME_CRASH_STOPPED 回報。
                if (server != null) stop();
            }
        }

        // ---- crash phase ----

        private void setupCrash() throws Exception {
            var journal = SessionRecoveryState.get(server);
            expect("freshJournal", journal.allSessions().isEmpty() && journal.allFlushedSessions().isEmpty(), () -> "journal=" + journal.allSessions().keySet());
            expect("freshCatalog", UniverseRegistryState.get(server).flushedRecords().isEmpty(), () -> "catalog 不是空的");
            expect("freshReceipts", !Files.exists(receiptsDir()), () -> "收據目錄已存在");
            // A：M5 collapse probe 的原艙寫法；參與者在艙內、不供電時以正式 attemptArm 登記（比照 close 列），之後才供電（艙內沒有人，不會出發）。
            overworld.getChunk(FRAME.controllerPos());
            M7HomeClosurePhases.Run.placeChamber(overworld);
            M7HomeClosureGameTests.holdChunks(overworld, FRAME);
            for (int i = 0; i < row.players; i++) {
                var id = UUID.randomUUID();
                ids.add(id);
                names.add("m7-" + id.toString().substring(0, 8));
                var connection = connect(i);
                connection.player().addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
                M5GameTestSupport.moveInside(connection.player(), FRAME);
            }
            var controller = (ChamberControllerBlockEntity) overworld.getBlockEntity(FRAME.controllerPos());
            var arm = new ChamberActivationService().attemptArm(overworld, controller);
            expect("originRegistered", arm.accepted() && controller.chamberUuid() != null, () -> "arm=" + arm);
            chamber = controller.chamberUuid();
            M7HomeClosureGameTests.requireCandidateStores(server);
            // B：同 Chamber 已確認 COMPLETE 的投影（沿 close 列與 HomeEntry），關門封艙、以紅石塊供電；crash phase 以測試票保持 B。
            var universe = UniverseId.of(UUID.randomUUID());
            b = M6ProfileProbePhases.fixtureSource(server, universe, "normal");
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, universe, DimensionRole.OVERWORLD, FRAME.controllerPos(),
                    FRAME.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
            index.confirmed(pending);
            M7HomeClosureGameTests.holdChunks(b, FRAME);
            var written = ProjectionWriter.write(b, FRAME, ProjectionWriter.Mode.FIRST_WRITE);
            expect("projectionWritten", written.status() == ProjectionWriter.Status.WRITTEN, () -> "write=" + written);
            bController = (ChamberControllerBlockEntity) b.getBlockEntity(FRAME.controllerPos());
            bController.setInstanceKind(ChamberInstanceKind.PROJECTION);
            index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
            var entry = new HomeSessionRecord.EntryEndpoint(chamber, universe, b.getRegistryKey().getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(),
                    FRAME.outwardFacing(), ChamberInstanceKind.PROJECTION);
            expect("entryValid", HomeEndpointAuthority.inspectEntry(server, entry) == HomeEndpointAuthority.EntryStatus.VALID,
                    () -> "inspectEntry=" + HomeEndpointAuthority.inspectEntry(server, entry));
            var closed = new ChamberDoorService().applyTarget(b, FRAME, ChamberDoorService.DoorState.CLOSED);
            expect("bDoorClosed", closed.succeeded() && "CLOSED/NONE".equals(doorSummary(b)), () -> closed + " door=" + doorSummary(b));
            var power = FRAME.controllerPos().offset(FRAME.outwardFacing());
            ChamberProtectionService.get().authorizedMutation(() -> {
                b.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
                overworld.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
                return null;
            });
            expect("bPowered", b.isReceivingRedstonePower(FRAME.controllerPos()) && overworld.isReceivingRedstonePower(FRAME.controllerPos()),
                    () -> "B=" + b.isReceivingRedstonePower(FRAME.controllerPos()) + " A=" + overworld.isReceivingRedstonePower(FRAME.controllerPos()));
            for (int i = 0; i < participants.size(); i++) {
                var player = participants.get(i).player();
                var spot = Vec3d.ofBottomCenter(ChamberGeometry.localToWorld(FRAME, 1 + 2 * i, 1, 3));
                require(player.teleport(b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && player.getServerWorld() == b, "participantInB：" + names.get(i));
                player.setVelocity(Vec3d.ZERO);
            }
            expect("participantsInB", participants.stream().allMatch(connection -> inB(connection.player())), () -> "poses=" + poses());
            var setup = new LinkedHashMap<String, Object>();
            setup.put("chamber", chamber.toString());
            setup.put("universe", universe.toString());
            setup.put("entryWorld", b.getRegistryKey().getValue().toString());
            var people = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < ids.size(); i++) people.add(Map.of("uuid", ids.get(i).toString(), "name", names.get(i)));
            setup.put("participants", people);
            setup.put("homeChunks", homeChunks);
            proof.put("setup", setup);
            step = Step.BASELINE;
            stepTicks = 0;
        }

        /** 基線 1（I-1）：A、B 的相關 chunks 就緒之後做世界 checkpoint 與 saveAllPlayerData，之後以正式 startHome 入場。 */
        @SuppressWarnings("unchecked")
        private void baseline() throws Exception {
            var chunks = chunkSet();
            if (!ChunkSaveCheckpoint.ready(overworld, chunks) || !ChunkSaveCheckpoint.ready(b, chunks)) {
                require(stepTicks <= READY_TICKS, "M7_HOME_CHUNKS_NOT_READY");
                return;
            }
            ChunkSaveCheckpoint.save(server, Map.of(overworld, chunks, b, chunks));
            server.getPlayerManager().saveAllPlayerData();
            var journal = SessionRecoveryState.get(server);
            var baseline = new LinkedHashMap<String, Object>();
            baseline.put("poses", poses());
            baseline.put("aDoor", doorSummary(overworld));
            baseline.put("registrySha256", fileSha(registryFile()));
            baseline.put("journalSha256", fileSha(journalFile()));
            baseline.put("receipts", listing(receiptsDir()));
            baseline.put("registryEnabled", registryEnabled());
            baseline.put("busy", gate().globalChamberBusy(server, chamber));
            baseline.put("gameTime", overworld.getTime());
            proof.put("baseline", baseline);
            expect("baselineSaved", journal.allSessions().isEmpty() && registryEnabled() && listing(receiptsDir()).isEmpty()
                    && !gate().globalChamberBusy(server, chamber) && !"ABSENT".equals(baseline.get("registrySha256")), () -> "baseline=" + baseline);
            var result = gate().startHome(b, bController, List.copyOf(ids));
            expect("homeStarted", result == ChamberSessionGateway.StartResult.STAGING, () -> "startHome=" + result);
            sid = M7HomeCorridorGameTests.provisionalSid(chamber);
            require(sid != null, "M7_CORRIDOR_NO_PROVISIONAL");
            ((Map<String, Object>) proof.get("setup")).put("sid", sid.toString());
            LOGGER.info("M7_CORRIDOR_STARTED row={} sid={}", row.rowName(), sid);
            step = row == Row.ARMING ? Step.AWAIT_ARMING : Step.AWAIT_SUPERPOSITION;
            stepTicks = 0;
        }

        /** 第 1 列：checked ARMING（current＝flushed、沒有 pending）的那個 END tick，還沒寫任何幾何、玩家仍在 B，就 halt。 */
        private void awaitArming() {
            var journal = SessionRecoveryState.get(server);
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            if (flushed != null && flushed.state() == SessionState.ARMING && flushed.equals(journal.allSessions().get(sid)) && journal.pendingSessions().isEmpty()) {
                expect("checkedArming", flushed.home().selection() instanceof HomeSessionRecord.Unselected && !flushed.spaceLeases().isEmpty()
                        && participants.stream().allMatch(connection -> inB(connection.player())) && homeTicket() != null && !rootLeasePresent(),
                        () -> "row=" + flushed + " ticket=" + homeTicket() + " root=" + rootLeasePresent() + " poses=" + poses());
                cut();
                return;
            }
            require(stepTicks <= STEP_TICKS, "armingReached：" + STEP_TICKS + " 個 tick 內沒有 checked ARMING；row=" + journal.allSessions().get(sid));
        }

        /** 第 2～10 列：SUPERPOSITION 就緒（mapping 已發布、入口 overlay 已寫完）→ 基線 2 → 依列 halt 或點門。 */
        private void awaitSuperposition() throws Exception {
            var journal = SessionRecoveryState.get(server);
            var pages = CorridorPageManager.forServer(server);
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            boolean ready = flushed != null && flushed.state() == SessionState.SUPERPOSITION && flushed.equals(journal.allSessions().get(sid))
                    && pages.hasReservation(sid) && pages.currentMappings(sid).epoch() >= 1 && overlayCleared(pages);
            if (!ready) {
                require(stepTicks <= STEP_TICKS, "superpositionReached：" + STEP_TICKS + " 個 tick 內沒有就緒；row=" + journal.allSessions().get(sid));
                return;
            }
            var leaseChunks = leaseChunks(flushed);
            if (!ChunkSaveCheckpoint.ready(corridor(), leaseChunks) || !ChunkSaveCheckpoint.ready(b, chunkSet())) {
                require(stepTicks <= STEP_TICKS, "corridorChunksReady：走廊租約或 B 的 chunks 沒有就緒");
                return;
            }
            var superposition = new LinkedHashMap<String, Object>();
            superposition.put("poses", poses());
            superposition.put("mappingEpoch", pages.currentMappings(sid).epoch());
            // 基線 2（N-2）：走廊租約與 B 的世界 checkpoint（相當於 autosave），不存 playerdata；journal 已 checked、不是 dirty，checkpoint 不改 journal。
            var before = fileSha(journalFile());
            ChunkSaveCheckpoint.save(server, Map.of(corridor(), leaseChunks, b, chunkSet()));
            var after = fileSha(journalFile());
            expect("corridorBaselineSaved", before.equals(after) && flushed.equals(journal.allFlushedSessions().get(sid)) && flushed.equals(journal.allSessions().get(sid)),
                    () -> "journal " + before + "→" + after + " row=" + journal.allSessions().get(sid));
            var marker = homeDoorCell(pages, flushed);
            var entrance = entranceBulkhead(flushed);
            superposition.put("homeDoorCell", posText(marker));
            superposition.put("entranceBulkhead", entrance == null ? "NONE" : posText(entrance));
            superposition.put("leases", leaseText(flushed));
            proof.put("superposition", superposition);
            if (row == Row.SUPERPOSITION) { cut(); return; }
            var player = participants.get(0).player();
            walkHome(pages, player, 11.5);
            var result = click(player, marker);
            expect("selectAccepted", result == ActionResult.CONSUME && rootLeasePresent(), () -> "click=" + result + " root=" + rootLeasePresent());
            if (row == Row.SELECT_PENDING) {
                var now = homeRow(journal.allFlushedSessions().get(sid));
                expect("selectPendingAtHalt", now != null && now.state() == SessionState.SUPERPOSITION && now.home().selection() instanceof HomeSessionRecord.Unselected
                        && journal.pendingSessions().isEmpty(), () -> "row=" + now);
                cut();
                return;
            }
            readbackArmed = row == Row.SELECT_UNCONFIRMED;
            step = Step.AWAIT_SELECT;
            stepTicks = 0;
        }

        /** 第 4～10 列：選門 checked（第 4 列在讀回之前已 halt，走到這裡就是失敗）。 */
        @SuppressWarnings("unchecked")
        private void awaitSelect() throws Exception {
            var journal = SessionRecoveryState.get(server);
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            boolean selected = flushed != null && flushed.home().selection() instanceof Selected && flushed.equals(journal.allSessions().get(sid));
            if (!selected) {
                require(stepTicks <= STEP_TICKS, "selectCommitted：" + STEP_TICKS + " 個 tick 內沒有 checked 選門；row=" + journal.allSessions().get(sid));
                return;
            }
            require(row != Row.SELECT_UNCONFIRMED, "selectUnconfirmedMissed：選門已讀回確認，沒有在讀回之前 halt");
            expect("selectedNoneReturned", flushed.state() == SessionState.MEASURED && flushed.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned),
                    () -> "row=" + flushed);
            switch (row) {
                case MEASURED -> cut();
                case MEASURED_STOPPED -> {
                    var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
                    expect("rootDisabledAcked", lifecycle.setEnabled(chamber, false), () -> "setEnabled(false) 未回報成功");
                    var latched = homeRow(journal.allFlushedSessions().get(sid));
                    expect("stopLatchedDurable", latched != null && latched.equals(journal.allSessions().get(sid)) && latched.state() == SessionState.MEASURED
                            && latched.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && latched.home().closeIntent() instanceof None
                            && !journal.rollForwardPending(sid) && journal.pendingSessions().isEmpty(), () -> "row=" + latched);
                    ChunkSaveCheckpoint.save(server, Map.of(overworld, chunkSet()));
                    var baseline = (Map<String, Object>) proof.get("baseline");
                    expect("registrySaved", !registryEnabled() && !fileSha(registryFile()).equals(baseline.get("registrySha256")), () -> "registry 沒有落盤");
                    baseline.put("stoppedAt", latched.home().stoppedAtGameTime());
                    cut();
                }
                case PARTIAL_RETURNED, COLLAPSING_PARTIAL -> {
                    // 選門提交的那個 tick 讓乙斷線（選定列斷線不轉 RETURNING）；斷線時原版存下乙的 playerdata（走廊）。
                    secondPose = pose(participants.get(1).player());
                    secondPoseText = JSON.toJson(secondPose);
                    participants.get(1).disconnect();
                    expect("secondDisconnected", server.getPlayerManager().getPlayer(ids.get(1)) == null, () -> "乙仍在線");
                    step = Step.AWAIT_PARTIAL;
                    stepTicks = 0;
                }
                case CLEANUP_BEFORE, CLEANUP_AFTER -> { step = Step.AWAIT_CLEANUP; stepTicks = 0; }
                default -> throw new IllegalStateException("M7_CORRIDOR_STEP：" + row);
            }
        }

        /** 第 6、9 列：甲已 Returned、乙離線沒有返還。第 9 列以正式 journal 入口（guard 內）提交 MEASURED→COLLAPSING。 */
        private void awaitPartial() {
            var journal = SessionRecoveryState.get(server);
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            boolean partial = flushed != null && flushed.equals(journal.allSessions().get(sid)) && !journal.rollForwardPending(sid)
                    && returned(flushed, ids.get(0)) && !returned(flushed, ids.get(1));
            if (!partial) {
                require(stepTicks <= STEP_TICKS, "partialReturned：" + STEP_TICKS + " 個 tick 內甲沒有返還；row=" + journal.allSessions().get(sid));
                return;
            }
            expect("firstReturnedInB", inB(participants.get(0).player()), () -> "甲=" + pose(participants.get(0).player()));
            if (row == Row.COLLAPSING_PARTIAL) {
                var h = flushed.home();
                var collapsing = new HomeSessionRecord(sid, flushed.chamberUuid(), flushed.rootAnchor(), flushed.entryEndpoint(), flushed.participants(),
                        flushed.spaceLeases(), SessionState.COLLAPSING, new Home(h.selection(), overworld.getTime(), h.passageOpenedAtGameTime(),
                        h.passageExpiresAtGameTime(), h.crossed(), h.recoveryRequired(), h.stopReason(), h.stoppedAtGameTime(), h.closeIntent(), h.sealedReason()));
                var pages = CorridorPageManager.forServer(server);
                var result = pages.exclusiveOperation(sid, () -> journal.commitHomeRestore(server, flushed, collapsing));
                expect("checkedCollapsing", result.committed() && collapsing.equals(journal.allFlushedSessions().get(sid)) && collapsing.equals(journal.allSessions().get(sid)),
                        () -> "commit=" + result.status() + " row=" + journal.allSessions().get(sid));
            }
            cut();
        }

        /** cut：記下 crash 當下的狀態與 reopen 的預期登入位置，halt。 */
        private void cut() {
            cutHits++;
            halt();
        }

        /** crash：只有乾淨的 halt（第一次 hit、沒有失敗、停服尚未開始、事件序列恰為預定）以 77 結束；否則 HALT_REFUSED、78。 */
        private void halt() {
            var refused = new ArrayList<String>();
            if (cutHits != 1) refused.add("hit=" + cutHits);
            if (!failures.isEmpty()) refused.add("failures=" + failures);
            if (stoppingSeen) refused.add("stoppingSeen：停服已開始，不是 crash");
            if (!eventNames().equals(row.crashEvents())) refused.add("events=" + eventNames() + " expected=" + row.crashEvents());
            int exit = refused.isEmpty() ? HALT_EXIT : HALT_FAILED_EXIT;
            try {
                var halt = new LinkedHashMap<String, Object>(proof);
                halt.put("status", refused.isEmpty() ? "HALT" : "HALT_REFUSED");
                halt.put("refused", refused);
                halt.put("hit", cutHits);
                halt.put("crashPointFailure", "none");
                halt.put("ending", "HALT");
                halt.put("events", events);
                halt.put("observed", observed);
                halt.put("atHalt", haltSnapshot());
                halt.put("expectedLogin", expectedLogin());
                halt.put("haltServerTick", server.getTicks());
                halt.put("haltTimeUtc", Instant.now().toString());
                writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            } catch (Exception | Error failure) {
                LOGGER.error("M7 corridor probe：halt receipt 寫不出來", failure);
                exit = HALT_FAILED_EXIT;
            }
            if (exit != HALT_EXIT) LOGGER.error("M7 corridor probe：不是乾淨的 halt（{}），以 {} 結束", refused, exit);
            Runtime.getRuntime().halt(exit);
        }

        /**
         * reopen 時每位參與者的登入位置（crash 前最後一次存下的 playerdata，N-1、N-2）：第 1 列是基線 1（在 B）；第 6、9 列的甲與第 7、8 列是返還時的 checkpoint（halt 當下，在 B）；
         * 第 6、9 列的乙是斷線存檔；其他列是 SUPERPOSITION 就緒當下的 pose（production 入場時的 saveAndVerify，在走廊）。
         */
        private List<Map<String, Object>> expectedLogin() {
            var result = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < ids.size(); i++) {
                Object pose = switch (row) {
                    case ARMING -> ((List<?>) ((Map<?, ?>) proof.get("baseline")).get("poses")).get(i);
                    case CLEANUP_BEFORE, CLEANUP_AFTER -> pose(participants.get(i).player());
                    case PARTIAL_RETURNED, COLLAPSING_PARTIAL -> i == 0 ? pose(participants.get(0).player()) : secondPose;
                    default -> ((List<?>) ((Map<?, ?>) proof.get("superposition")).get("poses")).get(i);
                };
                @SuppressWarnings("unchecked") var map = (Map<String, Object>) pose;
                result.add(map);
            }
            return result;
        }

        private Map<String, Object> haltSnapshot() {
            var s = new LinkedHashMap<String, Object>();
            var journal = SessionRecoveryState.get(server);
            s.put("current", rowFacts(journal.allSessions().get(sid)));
            s.put("flushed", rowFacts(journal.allFlushedSessions().get(sid)));
            var pendingRow = journal.pendingSessions().get(sid);
            s.put("pending", rowFacts(pendingRow));
            s.put("rollForwardPending", journal.rollForwardPending(sid));
            s.put("quarantined", journal.quarantinedSessions().contains(sid));
            s.put("journalSha256", fileSha(journalFile()));
            // 磁碟上 journal 的 strict decode（第 4 列：寫出已原子替換、讀回之前，磁碟上已是 MEASURED）。
            s.put("disk", diskFacts());
            s.put("reservation", CorridorPageManager.forServer(server).hasReservation(sid));
            s.put("homeSourceTicket", homeTicket() != null);
            s.put("rootTicket", rootLeasePresent());
            s.put("poses", participants.stream().filter(connection -> server.getPlayerManager().getPlayer(connection.player().getUuid()) == connection.player())
                    .map(connection -> pose(connection.player())).toList());
            s.put("inB", participants.stream().filter(connection -> server.getPlayerManager().getPlayer(connection.player().getUuid()) == connection.player())
                    .filter(connection -> inB(connection.player())).count());
            s.put("busy", gate().globalChamberBusy(server, chamber));
            if (row == Row.CLEANUP_BEFORE || row == Row.CLEANUP_AFTER) {
                var flushed = homeRow(journal.allFlushedSessions().get(sid));
                s.put("corridorClearedInMemory", flushed != null && leasesAir(flushed, false));
            }
            s.put("gameTime", overworld.getTime());
            return s;
        }

        private Map<String, Object> diskFacts() {
            var facts = new LinkedHashMap<String, Object>();
            try {
                var wrapped = NbtIo.readCompressed(journalFile(), NbtSizeTracker.ofUnlimitedBytes());
                var state = SessionRecoveryState.fromNbt(wrapped.getCompound("data"));
                state.requireHealthy();
                facts.putAll(rowFacts(state.allFlushedSessions().get(sid)));
                facts.put("decoded", true);
            } catch (IOException | RuntimeException failure) {
                facts.put("decoded", false);
                facts.put("error", failure.toString());
            }
            return facts;
        }

        // ---- 走廊的 cut 通知與 close／收據事件 ----

        @Override public void corridor(String name, UUID session, Object detail) {
            if (server == null || !server.isOnThread()) { failures.add("corridorEventOffThread：" + name); return; }
            try {
                if (name.equals(READBACK)) {
                    // 一律不錄（觸發次數隨 autosave 與各種 roll-forward 變動）；只在第 4 列已武裝、pending 是本 SID 選定的 MEASURED 時當 cut。
                    if (!crashPhase() || !readbackArmed || sid == null) return;
                    var pending = homeRow(SessionRecoveryState.get(server).pendingSessions().get(sid));
                    if (pending == null || pending.state() != SessionState.MEASURED || !(pending.home().selection() instanceof Selected)) return;
                    readbackArmed = false;
                    var journal = SessionRecoveryState.get(server);
                    var flushed = homeRow(journal.allFlushedSessions().get(sid));
                    expect("selectUnconfirmedAtHalt", flushed != null && flushed.state() == SessionState.SUPERPOSITION
                            && flushed.home().selection() instanceof HomeSessionRecord.Unselected && Boolean.TRUE.equals(diskFacts().get("selected")),
                            () -> "flushed=" + flushed + " disk=" + diskFacts());
                    cut();
                    return;
                }
                if (!name.equals(HEAD) && !name.equals(RETURN)) { failures.add("corridorEventUnknown：" + name); return; }
                if (sid == null || !sid.equals(session)) return;
                var row0 = detail instanceof HomeSessionRecord h ? h : null;
                var record = new LinkedHashMap<String, Object>();
                record.put("event", name);
                record.put("serverTick", server.getTicks());
                record.put("gameTime", overworld.getTime());
                record.put("detail", row0 == null ? "NONE" : row0.state().name() + "/" + (row0.home().selection() instanceof Selected ? "SELECTED" : "UNSELECTED"));
                record.put("homeSourceTicket", homeTicket() != null);
                record.put("entryTicket", entryLeasePresent());
                record.put("bPlayers", b == null ? -1 : b.getPlayers().size());
                events.add(record);
                if (crashPhase() && row0 != null && row0.home().selection() instanceof Selected
                        && (row == Row.CLEANUP_BEFORE && name.equals(HEAD) || row == Row.CLEANUP_AFTER && name.equals(RETURN))) {
                    var journal = SessionRecoveryState.get(server);
                    var flushed = homeRow(journal.allFlushedSessions().get(sid));
                    expect(row == Row.CLEANUP_BEFORE ? "cleanupBeforeAtHalt" : "cleanupAfterAtHalt", flushed != null && flushed.state() == SessionState.MEASURED
                            && !flushed.spaceLeases().isEmpty() && flushed.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)
                            && leasesAir(flushed, false), () -> "row=" + flushed);
                    cut();
                }
            } catch (RuntimeException | Error failure) {
                failures.add("corridorEventRecord：" + name + "：" + failure);
                LOGGER.error("M7 corridor probe：事件記錄失敗 {}", name, failure);
            }
        }

        @Override public void reached(String name, UUID session, Object detail) {
            if (server == null || !server.isOnThread()) { failures.add("eventOffThread：" + name); return; }
            try {
                var record = new LinkedHashMap<String, Object>();
                record.put("event", name);
                record.put("serverTick", server.getTicks());
                record.put("gameTime", overworld.getTime());
                record.put("detail", M7HomeClosurePhases.describe(detail));
                record.put("homeSourceTicket", homeTicket() != null);
                record.put("entryTicket", entryLeasePresent());
                record.put("bPlayers", b == null ? -1 : b.getPlayers().size());
                events.add(record);
                if (sid == null || !sid.equals(session)) failures.add("foreignSession：" + name + " session=" + session);
                if (crashPhase()) failures.add("closeEventInCrash：" + name);
            } catch (RuntimeException | Error failure) {
                failures.add("eventRecord：" + name + "：" + failure);
                LOGGER.error("M7 corridor probe：事件記錄失敗 {}", name, failure);
            }
        }

        // ---- reopen phase ----

        private void setupReopen() throws Exception {
            require(crash != null, "M7_HOME_CRASH_RECEIPT_MISSING");
            expect("crashReceiptMatches", "HALT".equals(text(crash, "status")) && "crash".equals(text(crash, "phase")) && row.rowName().equals(text(crash, "row"))
                    && config.nonce().equals(text(crash, "nonce")) && !config.startupNonce().equals(text(crash, "startupNonce"))
                    && crash.getAsJsonArray("refused").isEmpty() && crash.get("hit").getAsInt() == 1, () -> "crash=" + crash);
            var setup = crash.getAsJsonObject("setup");
            var atHalt = crash.getAsJsonObject("atHalt");
            chamber = UUID.fromString(text(setup, "chamber"));
            sid = UUID.fromString(text(setup, "sid"));
            for (var person : setup.getAsJsonArray("participants")) {
                ids.add(UUID.fromString(text(person.getAsJsonObject(), "uuid")));
                names.add(text(person.getAsJsonObject(), "name"));
            }
            proof.put("setup", JSON.fromJson(setup, Map.class));
            expect("journalBytesFromCrash", Objects.equals(startFiles.get("journalSha256"), text(atHalt, "journalSha256")),
                    () -> startFiles.get("journalSha256") + " vs " + text(atHalt, "journalSha256"));
            b = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(text(setup, "entryWorld"))));
            expect("bMaterializedAtStart", b != null, () -> "B 世界不存在：" + text(setup, "entryWorld"));
            var journal = SessionRecoveryState.get(server);
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            var disk = atHalt.getAsJsonObject("disk");
            expect("journalRowFromCrash", flushed != null && flushed.equals(journal.allSessions().get(sid)) && text(disk, "text").equals(String.valueOf(flushed)),
                    () -> "flushed=" + flushed + " disk=" + disk);
            if (row == Row.SELECT_UNCONFIRMED)
                expect("selectionFromDisk", flushed.state() == SessionState.MEASURED && flushed.home().selection() instanceof Selected s && s.selectedBy().equals(ids.get(0)),
                        () -> "row=" + flushed);
            var pages = CorridorPageManager.forServer(server);
            expect("reservationRestoredAtStart", pages.hasReservation(sid), () -> "開服時沒有重建走廊 space（reservation）：row=" + flushed);
            expect("corridorProtectedAtStart", protectedPosition(firstLeaseCorner(flushed)), () -> "走廊租約沒有保護：" + leaseText(flushed));
            expect("noTicketBeforeLifecycle", homeTicket() == null, () -> "SERVER_STARTED 時已有 B 票：" + homeTicket());
            expect("busyAtStart", gate().globalChamberBusy(server, chamber), () -> "開服時艙不 busy");
            expect("aPresenceBusy", gate().presence(server, chamber) == ChamberSessionGateway.Presence.BUSY, () -> "presence=" + gate().presence(server, chamber));
            var start = new LinkedHashMap<String, Object>();
            start.put("row", rowFacts(flushed));
            start.put("bLoaded", bAnyLoaded());
            start.put("tick", startedTick);
            proof.put("start", start);
            if (row.earlyLogin()) for (int i = 0; i < ids.size(); i++) login(i);
            // 第 2 列的 M-4 見證放在 SERVER_STARTED（第一個 tick 之前）：清理一定還沒開始，不受「走廊門格 chunk 載入」與「返還」誰先的時序影響。
            if (row == Row.SUPERPOSITION) doorWitness(pages, flushed);
            step = Step.REOPEN;
            stepTicks = 0;
        }

        private void login(int index) {
            var connection = connect(index);
            var pose = pose(connection.player());
            var expected = crash.getAsJsonArray("expectedLogin").get(index);
            expect("participantLoginPose", JSON.toJsonTree(pose).equals(expected), () -> names.get(index) + " login=" + pose + " expected=" + expected);
            if (index == 0) loginTick = server.getTicks();
            if (loginPoses == null) loginPoses = new ArrayList<>();
            loginPoses.add(pose);
            recordWorld(index, connection.player());
        }

        private void reopenTick() throws Exception {
            var journal = SessionRecoveryState.get(server);
            var pages = CorridorPageManager.forServer(server);
            if (firstTick) { firstTick = false; fenceAtFirstTick = fenceReady(); }
            var ticket = homeTicket();
            if (ticket != null && ticketTick < 0) {
                ticketTick = server.getTicks();
                var world = (ServerWorld) field(ticket.getClass(), ticket, "world");
                var chunk = (ChunkPos) field(ticket.getClass(), ticket, "chunk");
                expect("bTicketFormal", world == b && chunk.equals(new ChunkPos(FRAME.controllerPos())) && ticketTick > startedTick,
                        () -> "ticket world=" + world.getRegistryKey().getValue() + " chunk=" + chunk + " tick=" + ticketTick + " started=" + startedTick);
            }
            // B 的 home chunks 第一次全部 FULL 時正式 B 來源票已在，而且上一個 tick 結束時 B 沒有玩家（B 不是由玩家票載入；第 2、3 列的返還移動可能就在同一個 tick）。
            if (!bFullSeen && bAllLoaded()) {
                bFullSeen = true;
                int before = prevBPlayers;
                expect("bLoadedByFormalTicket", ticket != null && before == 0, () -> "B 載入時 ticket=" + ticket + " 上一個 tick 的 B 玩家數=" + before);
            }
            if (!row.earlyLogin()) {
                if (participants.isEmpty() && ticket != null && bAllLoaded() && b.getPlayers().isEmpty()) login(0);
                if (participants.size() == 1 && ids.size() == 2 && server.getTicks() >= loginTick + SECOND_LOGIN_DELAY) login(1);
            }
            for (int i = 0; i < participants.size(); i++) {
                var player = participants.get(i).player();
                recordWorld(i, player);
                if (!moved && !JSON.toJsonTree(pose(player)).equals(JSON.toJsonTree(loginPoses.get(i)))) {
                    moved = true;
                    expect("fenceReadyAtFirstMove", fenceReady(), () -> "第一次返還移動時 HomeRootFence 尚未就緒");
                }
            }
            if (entryLeasePresent()) entryTicketSeen = true;
            if (rootLeasePresent()) rootTicketSeen = true;
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            var current = homeRow(journal.allSessions().get(sid));
            if (flushed != null && flushed.state() == SessionState.RETURNING) converted = true;
            if (flushed != null && flushed.home().selection() instanceof Selected || current != null && current.home().selection() instanceof Selected) selectedSeen = true;
            prevBPlayers = b.getPlayers().size();
            if (endMissing(journal, pages).isEmpty()) {
                verifyEnd(journal, pages);
                return;
            }
            require(stepTicks <= REOPEN_TICKS, "M7_CORRIDOR_END_TIMEOUT：" + REOPEN_TICKS + " 個 tick 內沒有收尾；missing=" + endMissing(journal, pages)
                    + " row=" + journal.allSessions().get(sid) + " ticket=" + homeTicket() + " events=" + eventNames());
        }

        /**
         * 第 2 列的 M-4 見證（N-3）：SERVER_STARTED、參與者剛登入（第一個 tick 之前，清理一定還沒開始：reservation 仍在、列仍未全員 Returned），先讓走廊的門格 chunk 載入
         * （只對走廊；B 不在此限），格上仍是 home_marker 時，重建 space 的 HOME 門格回 {@code current=false} 的 hit，以參與者直接呼叫該格的 {@code onUse}（繞過網路層凍結）
         * 恰回 FAIL、門格不變、沒有選定列；反向見證：入口門格（marker=false）回 empty。
         */
        private void doorWitness(CorridorPageManager pages, HomeSessionRecord flushed) {
            var superposition = crash.getAsJsonObject("superposition");
            var cell = parse(text(superposition, "homeDoorCell"));
            var corridor = corridor();
            require(!participants.isEmpty(), "doorWitness：參與者尚未登入");
            corridor.getChunk(cell.getX() >> 4, cell.getZ() >> 4);
            doorWitnessed = true;
            var state = corridor.getBlockState(cell);
            expect("doorWitnessPrecondition", pages.hasReservation(sid) && flushed != null && flushed.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned)
                    && state.isOf(ModBlocks.QUANTUM_BULKHEAD) && state.get(QuantumBulkheadBlock.HOME_MARKER), () -> "row=" + flushed + " cell=" + state);
            var hit = pages.homeDoorAt(corridor, cell);
            var player = participants.get(0).player();
            var result = state.onUse(corridor, player, new BlockHitResult(Vec3d.ofCenter(cell), Direction.WEST, cell, false));
            var after = corridor.getBlockState(cell);
            var journal = SessionRecoveryState.get(server);
            expect("restoredHomeDoorConsumed", hit.isPresent() && !hit.get().current() && hit.get().sessionUuid().equals(sid) && result == ActionResult.FAIL && after.equals(state)
                    && !(homeRow(journal.allSessions().get(sid)) != null && homeRow(journal.allSessions().get(sid)).home().selection() instanceof Selected),
                    () -> "hit=" + hit + " result=" + result + " before=" + state + " after=" + after);
            var entrance = text(superposition, "entranceBulkhead");
            if ("NONE".equals(entrance)) { failures.add("entranceDoorNotHome：crash 時沒有找到入口門格（marker=false 的 bulkhead）"); return; }
            var door = parse(entrance);
            expect("entranceDoorNotHome", corridor.getBlockState(door).isOf(ModBlocks.QUANTUM_BULKHEAD) && !corridor.getBlockState(door).get(QuantumBulkheadBlock.HOME_MARKER)
                    && pages.homeDoorAt(corridor, door).isEmpty(), () -> "entrance=" + door + " hit=" + pages.homeDoorAt(corridor, door));
        }

        /** 各列終局的未成立條件（空集合表示可以核對終局）；逾時訊息列出名稱，供 mutation 的具名殺手比對。 */
        private List<String> endMissing(SessionRecoveryState journal, CorridorPageManager pages) {
            var missing = new ArrayList<String>();
            if (ticketTick < 0) missing.add("bTicketFormal");
            if (!bFullSeen) missing.add("bLoadedByFormalTicket");
            if (participants.size() != ids.size()) missing.add("loginsDone");
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            boolean present = journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid) || journal.pendingSessions().containsKey(sid);
            boolean allInB = participants.size() == ids.size() && participants.stream().allMatch(connection -> inB(connection.player()));
            if (!allInB) missing.add("returnedToB");
            if (pages.hasReservation(sid)) missing.add("reservationReleased");
            if (row.removedEnd() || row == Row.MEASURED_STOPPED) {
                if (row.removedEnd() && !converted) missing.add("convertedToReturning");
                if (present) missing.add("rowRemoved");
                if (pages.releaseComplete(sid)) missing.add("releaseAcked");
                if (homeTicket() != null) missing.add("ticketReleased");
                if (gate().globalChamberBusy(server, chamber)) missing.add("notBusy");
            } else if (row.dormantEnd()) {
                if (!(flushed != null && flushed.state() == SessionState.MEASURED && flushed.home().selection() instanceof Selected && flushed.spaceLeases().isEmpty()
                        && flushed.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && flushed.equals(journal.allSessions().get(sid))))
                    missing.add("dormantReached");
                if (homeTicket() != null) missing.add("ticketReleased");
            } else {
                if (!(flushed != null && flushed.state() == SessionState.COLLAPSING && flushed.spaceLeases().isEmpty()
                        && flushed.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned) && flushed.equals(journal.allSessions().get(sid))))
                    missing.add("collapsingSettled");
                if (homeTicket() == null) missing.add("ticketHeld");
            }
            return missing;
        }

        private void verifyEnd(SessionRecoveryState journal, CorridorPageManager pages) throws Exception {
            var atHalt = crash.getAsJsonObject("atHalt");
            var disk = atHalt.getAsJsonObject("disk");
            var flushed = homeRow(journal.allFlushedSessions().get(sid));
            expect("bTicketFormal", ticketTick > startedTick, () -> "ticketTick=" + ticketTick);
            if (!moved) expect("fenceReadyAtFirstMove", fenceAtFirstTick, () -> "第一個 tick 時 HomeRootFence 尚未就緒");
            expect("neverInA", trace.stream().noneMatch(entry -> World.OVERWORLD.getValue().toString().equals(entry.get("world"))), () -> "trace=" + trace);
            expect("returnedToB", participants.stream().allMatch(connection -> inB(connection.player())), () -> "poses=" + poses());
            expect("participantsAvailable", journal.participantsAvailable(List.copyOf(ids)), () -> "參與者仍被占用");
            if (row != Row.MEASURED_STOPPED) {
                expect("noEntryTicket", !entryTicketSeen, () -> "出現 B 收尾票");
                expect("noRootTicket", !rootTicketSeen, () -> "出現 A 票");
                expect("noCloseEvents", eventNames().stream().allMatch(name -> name.equals(HEAD) || name.equals(RETURN)), () -> "events=" + eventNames());
            }
            expect("checkpointOnce", eventNames().subList(0, Math.min(2, eventNames().size())).equals(List.of(HEAD, RETURN))
                    && eventNames().stream().filter(HEAD::equals).count() == 1 && eventNames().stream().filter(RETURN::equals).count() == 1, () -> "events=" + eventNames());
            if (row.removedEnd()) {
                expect("convertedToReturning", converted, () -> "沒有觀察到 checked RETURNING");
                expect("releaseAcked", !pages.releaseComplete(sid) && !pages.hasReservation(sid), () -> "完成收據未消費");
                expect("ticketReleased", homeTicket() == null, () -> "B 票未釋放");
                expect("notBusy", !gate().globalChamberBusy(server, chamber), () -> "仍 busy");
                if (row == Row.ARMING) expect("stayedInB", !moved, () -> "第 1 列的參與者在 B 原地，不應移動");
                if (row == Row.SELECT_PENDING) expect("noSelectionEver", !selectedSeen, () -> "journal 出現過選定列");
            } else if (row.dormantEnd()) {
                var crashSelection = String.valueOf(disk.get("selection"));
                expect("selectionKept", flushed != null && flushed.home().selection() instanceof Selected && JSON.toJsonTree(selectionFacts(flushed)).toString().equals(crashSelection),
                        () -> "end=" + (flushed == null ? null : flushed.home().selection()) + " crash=" + crashSelection);
                expect("ticketReleased", homeTicket() == null, () -> "DORMANT 之後 B 票未釋放");
                expect("dormantLockHeld", gate().globalChamberBusy(server, chamber), () -> "DORMANT 不持鎖");
            } else if (row == Row.COLLAPSING_PARTIAL) {
                expect("ticketHeld", homeTicket() != null, () -> "COLLAPSING 已清理但 B 票已釋放");
                expect("collapsingLockHeld", gate().globalChamberBusy(server, chamber), () -> "COLLAPSING 不持鎖");
                expect("noPrepare", flushed != null && flushed.state() == SessionState.COLLAPSING && flushed.home().passageOpenedAtGameTime() == -1, () -> "row=" + flushed);
            } else {
                // 第 10 列：DORMANT 那個 tick 先釋放 B 來源票，之後 HomeClosure 以 ROOT_DISABLED CLOSE、B 門 OPEN、checkpoint、發收據、移除列。
                var close = events.stream().filter(event -> !event.get("event").equals(HEAD) && !event.get("event").equals(RETURN)).toList();
                expect("sourceTicketReleasedBeforeClose", !close.isEmpty() && close.stream().allMatch(event -> Boolean.FALSE.equals(event.get("homeSourceTicket"))),
                        () -> "close=" + close);
                expect("entryTicketNeverTaken", close.stream().allMatch(event -> Boolean.FALSE.equals(event.get("entryTicket"))
                        && ((Number) event.get("bPlayers")).intValue() >= 1), () -> "close=" + close);
                var receipt = new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)).lookup(sid).orElseThrow(() -> new AssertionError("receiptPublished：沒有收據"));
                long stoppedAt = crash.getAsJsonObject("baseline").get("stoppedAt").getAsLong();
                var intent = receipt.home().closeIntent();
                expect("closedByRootDisabled", intent instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED && k.doorGoal() == DoorGoal.OPEN
                        && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, stoppedAt)), () -> "close=" + intent);
                expect("registryStillDisabled", !registryEnabled(), () -> "registry 已啟用");
                expect("aDoorUnchanged", text(crash.getAsJsonObject("baseline"), "aDoor").equals(doorSummary(overworld)), () -> "A 門=" + doorSummary(overworld));
                expect("bDoorOpen", "OPEN/NONE".equals(doorSummary(b)), () -> "B 門=" + doorSummary(b));
                expect("notBusy", !gate().globalChamberBusy(server, chamber), () -> "仍 busy");
            }
            // 結束時租約 bounds 內全部是 AIR（先讓 chunk 載入）。
            var leases = crashLeases();
            expect("corridorClearedAtEnd", leasesAirBoxes(leases, true), () -> "leases=" + leases);
            proof.put("end", endSnapshot(journal, pages));
            endPoses = poses();
            eventsBeforeQuiet = events.size();
            rowBeforeQuiet = String.valueOf(journal.allSessions().get(sid));
            step = Step.QUIET;
            stepTicks = 0;
        }

        private void quiet() {
            if (stepTicks < QUIET_TICKS) return;
            var journal = SessionRecoveryState.get(server);
            expect("quietKept", JSON.toJsonTree(poses()).equals(JSON.toJsonTree(endPoses)) && events.size() == eventsBeforeQuiet
                    && rowBeforeQuiet.equals(String.valueOf(journal.allSessions().get(sid))), () -> "poses=" + poses() + " events=" + eventNames());
            proof.put("quiet", Map.of("ticks", QUIET_TICKS));
            passed = true;
            step = Step.DONE;
            stop();
        }

        private Map<String, Object> endSnapshot(SessionRecoveryState journal, CorridorPageManager pages) {
            var s = new LinkedHashMap<String, Object>();
            s.put("current", rowFacts(journal.allSessions().get(sid)));
            s.put("flushed", rowFacts(journal.allFlushedSessions().get(sid)));
            s.put("pending", journal.pendingSessions().containsKey(sid));
            s.put("reservation", pages.hasReservation(sid));
            s.put("releaseComplete", pages.releaseComplete(sid));
            s.put("homeSourceTicket", homeTicket() != null);
            s.put("entryTicket", entryLeasePresent());
            s.put("rootTicket", rootLeasePresent());
            s.put("busy", gate().globalChamberBusy(server, chamber));
            s.put("participantsAvailable", journal.participantsAvailable(List.copyOf(ids)));
            s.put("inB", participants.stream().filter(connection -> inB(connection.player())).count());
            s.put("ticketTick", ticketTick);
            s.put("loginTick", loginTick);
            s.put("gameTime", overworld.getTime());
            return s;
        }

        // ---- fixture 與觀察 ----

        private ConnectedGameTestPlayer connect(int index) {
            var connection = new ConnectedGameTestPlayer(overworld, new GameProfile(ids.get(index), names.get(index)));
            connection.confirmTeleport();
            connection.player().setNoGravity(true);
            participants.add(connection);
            return connection;
        }

        /** 以原生移動把玩家推進到目前（唯一）實例的邏輯 z（照抄 {@code HomeEntry.walkHome}）。 */
        private void walkHome(CorridorPageManager pages, ServerPlayerEntity player, double z) {
            var view = pages.currentMappings(sid).instances().getFirst();
            var target = pages.toPhysical(view.ref(), new CorridorPageManager.LogicalPose(3.5, 1, z, Vec3d.ZERO, 0, 0));
            var box = player.getBoundingBox().offset(target.position().subtract(player.getPos()));
            require(corridor().isSpaceEmpty(player, box), "walkHome：走廊終點沒有完整 collision 空間：" + target.position());
            require(new SessionTransferService().move(player, corridor(), target.position(), target.velocity(), target.yaw(), target.pitch()), "walkHome：原生移動失敗");
        }

        /** 真互動：以 bulkhead 的 {@code onUse} 點 {@code pos}（照抄 {@code M7HomeSelectGameTests.click}）。 */
        private ActionResult click(ServerPlayerEntity player, BlockPos pos) {
            var state = corridor().getBlockState(pos);
            return state.onUse(corridor(), player, new BlockHitResult(Vec3d.ofCenter(pos), Direction.WEST, pos, false));
        }

        /** current 實例 HOME 門 25 格的中央一格（實際讀世界的 home_marker；照抄 {@code M7HomeSelectGameTests.doorCell}）。 */
        private BlockPos homeDoorCell(CorridorPageManager pages, HomeSessionRecord row0) {
            var current = pages.currentMappings(sid).instances();
            var cells = bulkheads(row0, true).stream().filter(pos -> current.stream().anyMatch(view -> view.bounds().contains(pos)))
                    .sorted(java.util.Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
            require(cells.size() == 25, "homeDoorCell：current 實例恰一扇 25 格 HOME 門：" + cells.size());
            return cells.get(12);
        }

        /** 走廊租約內第一個 marker=false 的 bulkhead（入口門格，N-3 的反向見證）；沒有時為 null。 */
        private BlockPos entranceBulkhead(HomeSessionRecord row0) {
            return bulkheads(row0, false).stream().sorted(java.util.Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
                    .findFirst().orElse(null);
        }

        private Set<BlockPos> bulkheads(HomeSessionRecord row0, boolean marker) {
            var cells = new LinkedHashSet<BlockPos>();
            for (var lease : row0.spaceLeases()) {
                var box = lease.bounds();
                for (var pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ())) {
                    var state = corridor().getBlockState(pos);
                    if (state.isOf(ModBlocks.QUANTUM_BULKHEAD) && state.get(QuantumBulkheadBlock.HOME_MARKER) == marker) cells.add(pos.toImmutable());
                }
            }
            return cells;
        }

        private boolean overlayCleared(CorridorPageManager pages) {
            var frame = pages.entrance(sid);
            for (int y = 1; y <= 5; y++) for (int n = 1; n <= 5; n++) for (int x : new int[]{0, 6})
                if (!corridor().getBlockState(ChamberSpaceCoordinates.block(frame, x, y, n)).isAir()) return false;
            return true;
        }

        private boolean leasesAir(HomeSessionRecord row0, boolean load) {
            return leasesAirBoxes(row0.spaceLeases().stream().map(SessionRecoveryRecord.SpaceLease::bounds).toList(), load);
        }

        private boolean leasesAirBoxes(List<BlockBox> boxes, boolean load) {
            var corridor = corridor();
            for (var box : boxes) {
                if (load) for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) corridor.getChunk(x, z);
                for (var pos : BlockPos.iterate(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()))
                    if (!corridor.getBlockState(pos).isAir()) return false;
            }
            return true;
        }

        /** crash 當下磁碟上那份列的租約 bounds（reopen 終局清空的核對範圍）。 */
        private List<BlockBox> crashLeases() {
            var boxes = new ArrayList<BlockBox>();
            for (var lease : crash.getAsJsonObject("atHalt").getAsJsonObject("disk").getAsJsonArray("leases")) {
                var parts = lease.getAsString().split(",");
                boxes.add(new BlockBox(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
            }
            return boxes;
        }

        private static List<String> leaseText(HomeSessionRecord row0) {
            return row0.spaceLeases().stream().map(lease -> {
                var box = lease.bounds();
                return box.getMinX() + "," + box.getMinY() + "," + box.getMinZ() + "," + box.getMaxX() + "," + box.getMaxY() + "," + box.getMaxZ();
            }).toList();
        }

        private static BlockPos firstLeaseCorner(HomeSessionRecord row0) {
            var box = row0.spaceLeases().getFirst().bounds();
            return new BlockPos(box.getMinX(), box.getMinY(), box.getMinZ());
        }

        private Set<ChunkPos> leaseChunks(HomeSessionRecord row0) {
            var chunks = new LinkedHashSet<ChunkPos>();
            for (var lease : row0.spaceLeases()) {
                var box = lease.bounds();
                for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
            }
            return Set.copyOf(chunks);
        }

        private Map<String, Object> rowFacts(JournalSession session) {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("present", session != null);
            if (session instanceof HomeSessionRecord h) {
                facts.put("state", h.state().name());
                facts.put("selected", h.home().selection() instanceof Selected);
                facts.put("selection", selectionFacts(h));
                facts.put("returned", h.participants().stream().filter(SessionRecoveryRecord.Participant::returned).count());
                facts.put("participants", h.participants().size());
                facts.put("leases", leaseText(h));
                facts.put("stopReason", h.home().stopReason().map(Enum::name).orElse("NONE"));
                facts.put("close", M7HomeClosurePhases.Run.closeFacts(h.home().closeIntent()));
                facts.put("text", String.valueOf(h));
            } else if (session != null) facts.put("text", String.valueOf(session));
            return facts;
        }

        private static Map<String, Object> selectionFacts(HomeSessionRecord h) {
            var facts = new LinkedHashMap<String, Object>();
            if (h.home().selection() instanceof Selected s) {
                facts.put("doorKey", String.valueOf(s.doorKey()));
                facts.put("selectedBy", s.selectedBy().toString());
                facts.put("selectedAt", Long.toString(s.selectedAtGameTime()));
            } else facts.put("kind", "UNSELECTED");
            return facts;
        }

        private void recordWorld(int index, ServerPlayerEntity player) {
            var world = player.getServerWorld().getRegistryKey().getValue().toString();
            var key = ids.get(index).toString();
            if (world.equals(lastWorld.get(key))) return;
            lastWorld.put(key, world);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("tick", server.getTicks());
            entry.put("player", key);
            entry.put("world", world);
            trace.add(entry);
            if (World.OVERWORLD.getValue().toString().equals(world)) failures.add("neverInA：" + names.get(index) + " 在 tick " + server.getTicks() + " 出現在主世界");
        }

        private List<Map<String, Object>> poses() { return participants.stream().map(connection -> pose(connection.player())).toList(); }

        private boolean inB(ServerPlayerEntity player) {
            return player != null && player.getServerWorld() == b && ChamberOccupantService.contains(ChamberGeometry.interiorBox(FRAME), player.getBoundingBox());
        }

        private static boolean returned(HomeSessionRecord row0, UUID player) {
            return row0.participants().stream().anyMatch(person -> person.playerUuid().equals(player) && person.returned());
        }

        private static HomeSessionRecord homeRow(JournalSession session) { return session instanceof HomeSessionRecord h ? h : null; }

        private ServerWorld corridor() { return server.getWorld(SuperpositionWorld.KEY); }

        private boolean registryEnabled() {
            var record = ChamberRegistryState.get(server).registry().records().get(chamber);
            return record != null && record.enabled();
        }

        /** SSM 的 Entry=B 來源票（{@code homeTickets} 的本 SID 值；反射讀 private 欄位，只讀）；沒有時為 null。 */
        private Object homeTicket() {
            try { return ((Map<?, ?>) field(gate().getClass(), gate(), "homeTickets")).get(sid); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_CORRIDOR_HOME_TICKETS_UNREADABLE", failure); }
        }

        private boolean rootLeasePresent() { return leasePresent("dev.quantumchamber.collapse.HomeRootTickets"); }

        private boolean entryLeasePresent() { return leasePresent("dev.quantumchamber.collapse.HomeEntryTickets"); }

        private boolean leasePresent(String type) {
            try {
                var owner = ((Map<?, ?>) field(Class.forName(type), null, "servers")).get(server);
                return owner != null && ((Map<?, ?>) field(Class.forName(type), owner, "leases")).containsKey(sid);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_CORRIDOR_TICKETS_UNREADABLE " + type, failure); }
        }

        /** {@code HomeRootFence} 的 fence 是否已就緒（反射讀 private 欄位，只讀）。 */
        private boolean fenceReady() {
            try {
                var type = Class.forName("dev.quantumchamber.collapse.HomeRootFence");
                var fence = ((Map<?, ?>) field(type, null, "FENCES")).get(server);
                return fence != null && (Boolean) field(type, fence, "ready");
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_CORRIDOR_FENCE_UNREADABLE", failure); }
        }

        /** {@code CorridorPageManager.protectedPosition}（package-private static，反射呼叫、只讀）。 */
        private boolean protectedPosition(BlockPos pos) {
            try {
                var method = CorridorPageManager.class.getDeclaredMethod("protectedPosition", ServerWorld.class, BlockPos.class);
                method.setAccessible(true);
                return (Boolean) method.invoke(null, corridor(), pos);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_CORRIDOR_PROTECTION_UNREADABLE", failure); }
        }

        private boolean bAllLoaded() {
            for (var pos : chunkSet()) if (b.getChunkManager().getWorldChunk(pos.x, pos.z) == null) return false;
            return true;
        }

        private boolean bAnyLoaded() {
            for (var pos : chunkSet()) if (b.getChunkManager().getWorldChunk(pos.x, pos.z) != null) return true;
            return false;
        }

        private Set<ChunkPos> chunkSet() {
            var chunks = new LinkedHashSet<ChunkPos>();
            for (var item : homeChunks) {
                var parts = item.split(",");
                chunks.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            }
            return Set.copyOf(chunks);
        }

        private List<String> eventNames() { return events.stream().map(event -> (String) event.get("event")).toList(); }

        private Path journalFile() { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat"); }

        private Path registryFile() { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(ChamberRegistryState.STATE_ID + ".dat"); }

        private Path receiptsDir() { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("quantumchamber_home_receipts"); }

        private static String fileSha(Path path) {
            try { return Files.exists(path) ? sha256(path) : "ABSENT"; }
            catch (IOException failure) { return "ERROR:" + failure; }
        }

        private static String posText(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

        private static BlockPos parse(String text) {
            var parts = text.split(",");
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }

        private void expect(String name, boolean condition, Supplier<String> detail) {
            if (!condition) throw new AssertionError(name + "：" + detail.get());
            observed.put(name, true);
        }
    }
}
