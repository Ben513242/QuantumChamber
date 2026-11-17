package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.HomeEndpointAuthority;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseIntent;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.HomeSessionRecord.RootStop;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.HomeSessionRecord.Unselected;
import dev.quantumchamber.persistence.JournalSession;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 僅 testmod（M7 T4 Phase B1）：Windows 原生 close 矩陣的 phase 表、fixture 與斷言（M7 spec §5.3、§9.2、§10；plan Task 4 的 Windows native 固定矩陣）。
 * <p>39 列 = 三種 B 門初始狀態（{@link #DOORS}）× 13 個 cut（{@link #CUTS}，逐字沿 plan）。每列兩個 phase，各一個獨立 JVM：
 * <ul>
 * <li>{@code crash}：fresh 世界蓋 A 原艙（正式 {@code attemptArm} 登記）、物化 B 投影（COMPLETE projection ledger），經正式 journal 入口走到 HOME DORMANT，
 *     以正式維護入口 {@code setEnabled(false)} 停用 A（{@code HomeRootFence} checked latch {@code ROOT_DISABLED}），做一次原生 checkpoint 作基線，arm cut；
 *     下一個 tick 由正式 {@code HomeClosure} 推進，走到該 cut 時寫 {@code halt.json}（CREATE_NEW＋force）並 {@code Runtime.halt(77)}。</li>
 * <li>{@code reopen}：新 JVM 開同一個世界，從磁碟續做，收尾後模擬玩家自己關 B 門、觀察不被舊旅程打開，正常停服（{@code final.json}）。</li>
 * </ul>
 * 預期終局由 {@link Row} 依 cut 推導（群組 A～D，見 SDD {@code t4b1-report.md}）；runner 與 {@code m7-home-probe.ps1} 各自核對同一張表。
 */
final class M7HomeClosurePhases {
    static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m7-home");
    static final Gson JSON = new Gson();
    static final List<String> DOORS = List.of("ordinary", "sealed", "passable");
    static final List<String> CUTS = List.of("before-close", "after-close", "before-door", "after-door", "before-checkpoint", "after-checkpoint",
            "after-temp-force", "before-publish", "after-publish", "after-readback", "after-recheck", "before-remove", "after-remove");
    static final List<String> PHASES = List.of("crash", "reopen");
    /** 收據已存在時的 exact 驗證（不是 cut，只記事件）。 */
    static final String VERIFY_EXISTING = "verify-existing";
    /** A 與 B 共用的艙座標（B 是同一 Controller site 的投影）；沿 M5 collapse probe 的原艙位置。 */
    static final ChamberFrame FRAME = new ChamberFrame(new BlockPos(8, 70, 8), Direction.NORTH);
    static final int HALT_EXIT = 77, HALT_FAILED_EXIT = 78;
    static final int READY_TICKS = 200, CUT_TICKS = 200, CLOSE_TICKS = 400, QUIET_TICKS = 40;
    static final String OPEN_NONE = "OPEN/NONE";

    private M7HomeClosurePhases() {}

    /** 39 列的名稱，依門態、cut 的順序。 */
    static List<String> rows() {
        var rows = new ArrayList<String>();
        for (var door : DOORS) for (var cut : CUTS) rows.add("close-" + door + "-" + cut);
        return List.copyOf(rows);
    }

    static Row row(String name) {
        for (var door : DOORS) for (var cut : CUTS) if (name.equals("close-" + door + "-" + cut)) return new Row(name, door, cut);
        throw new IllegalStateException("M7_HOME_ROW_UNKNOWN：" + name);
    }

    /** 重開時的磁碟狀態分組（SDD t4b1-report.md 的預期終局表）。 */
    enum Group {
        /** before-close：StopReason 已持久、沒有 CLOSE；重開後重新 mint ROOT_DISABLED。 */
        A,
        /** after-close～before-publish：CLOSE 已凍結、沒有已發佈的收據；重開後從 CLOSE 續做、發佈。 */
        B,
        /** after-publish～before-remove：CLOSE 與收據都在；重開後只驗 exact、不覆寫。 */
        C,
        /** after-remove：熱列已 checked 移除；重開後什麼都不做。 */
        D
    }

    record Row(String name, String door, String cut) {
        int index() { return CUTS.indexOf(cut); }

        Group group() {
            int i = index();
            if (i == 0) return Group.A;
            if (i <= CUTS.indexOf("before-publish")) return Group.B;
            if (i <= CUTS.indexOf("before-remove")) return Group.C;
            return Group.D;
        }

        /** crash phase 走到 cut 為止的事件序列。 */
        List<String> crashEvents() { return CUTS.subList(0, index() + 1); }

        /** reopen phase 的事件序列。 */
        List<String> reopenEvents() {
            return switch (group()) {
                case A -> CUTS;
                case B -> CUTS.subList(CUTS.indexOf("before-door"), CUTS.size());
                case C -> List.of("before-door", "after-door", "before-checkpoint", "after-checkpoint", VERIFY_EXISTING, "after-recheck", "before-remove", "after-remove");
                case D -> List.of();
            };
        }

        /** B 門在磁碟上是否已是 OPEN/NONE（after-checkpoint 起）。 */
        boolean doorCheckpointed() { return index() >= CUTS.indexOf("after-checkpoint"); }

        /** reopen 第一次改門的 outcome；after-remove 不呼叫。 */
        String reopenDoorOutcome() { return group() == Group.D ? "NONE" : doorCheckpointed() ? "UNCHANGED" : "APPLIED"; }

        /** crash 當下 journal 熱列的 CloseIntent：NONE、CLOSE，或已移除（ABSENT）。 */
        String closeAtHalt() { return index() == 0 ? "NONE" : group() == Group.D ? "ABSENT" : "CLOSE"; }

        /** crash 當下收據目錄的檔案種類（dat＝{@code <sid>.dat}，tmp＝{@code publish-*.tmp}）。 */
        List<String> receiptsAtHalt() {
            int i = index();
            if (i < CUTS.indexOf("after-temp-force")) return List.of();
            if (i < CUTS.indexOf("after-publish")) return List.of("tmp");
            if (i < CUTS.indexOf("after-recheck")) return List.of("dat", "tmp");
            return List.of("dat");
        }

        /** crash 當下（記憶體）B 門是否已改為 OPEN/NONE（after-door 起）。 */
        boolean doorChangedAtHalt() { return index() >= CUTS.indexOf("after-door"); }

        ChamberDoorService.DoorState initialDoor() {
            return switch (door) {
                case "ordinary" -> ChamberDoorService.DoorState.CLOSED;
                case "sealed" -> ChamberDoorService.DoorState.sealed(FRAME.outwardFacing());
                case "passable" -> ChamberDoorService.DoorState.passable(FRAME.outwardFacing());
                default -> throw new IllegalStateException("M7_HOME_DOOR_UNKNOWN：" + door);
            };
        }

        /** 初始門態的 25 格摘要（{@link #doorSummary} 的格式）；NORTH 朝外的膜沿 X 軸。 */
        String initialDoorSummary() {
            return switch (door) {
                case "ordinary" -> "CLOSED/NONE";
                case "sealed" -> "OPEN/SEALED_X";
                case "passable" -> "OPEN/PASSABLE_X";
                default -> throw new IllegalStateException("M7_HOME_DOOR_UNKNOWN：" + door);
            };
        }
    }

    /** probe 的設定（{@code quantumchamber.m7home.*}）；全部缺席時回 null（probe 關閉），部分存在或不合法時丟出。 */
    record Configuration(String nonce, Row row, String phase, String startupNonce, Path root, Path evidence) {
        static final String PREFIX = "quantumchamber.m7home.";

        static Configuration read() {
            var keys = List.of("nonce", "phase", "startupNonce", "root");
            var values = keys.stream().map(key -> System.getProperty(PREFIX + key)).toList();
            if (values.stream().allMatch(Objects::isNull)) return null;
            require(values.stream().allMatch(value -> value != null && !value.isBlank()), "M7_HOME_CONFIG_MISSING_PROPERTY " + values);
            String nonce = values.get(0), phase = values.get(1), startup = values.get(2);
            var matcher = Pattern.compile("[0-9a-f]{12}-(close-[a-z]+-[a-z-]+)").matcher(nonce);
            require(matcher.matches(), "M7_HOME_CONFIG_NONCE " + nonce);
            var row = M7HomeClosurePhases.row(matcher.group(1));
            require(PHASES.contains(phase), "M7_HOME_CONFIG_PHASE " + phase);
            require(startup.matches("[0-9a-f]{32}"), "M7_HOME_CONFIG_STARTUP_NONCE");
            for (var property : System.getProperties().stringPropertyNames())
                require(!property.startsWith("quantumchamber.m4recovery.") && !property.startsWith("quantumchamber.m5collapse.")
                        && !property.startsWith("quantumchamber.m6universe.") && !property.startsWith("fabric-api.gametest")
                        && !property.startsWith("quantumchamber.gametest.") && !property.startsWith("quantumchamber.m7game.")
                        && !property.equals("quantumchamber.test.collapseSuspended") && !property.equals("quantumchamber.test.m7Home"),
                        "M7_HOME_CONFIG_RUNTIME_FLAGS_MIXED " + property);
            try {
                Path cwd = Path.of("").toAbsolutePath().normalize().toRealPath();
                Path given = Path.of(values.get(3));
                Path root = given.toAbsolutePath().normalize();
                require(given.isAbsolute() && root.equals(cwd) && root.toRealPath().equals(root) && !Files.isSymbolicLink(root)
                        && root.getFileName().toString().equals("m7-home-" + nonce) && root.getParent().getFileName().toString().equals("run"),
                        "M7_HOME_CONFIG_ROOT " + given);
                Path project = root.getParent().getParent();
                require(Files.isRegularFile(project.resolve("build.gradle")), "M7_HOME_CONFIG_PROJECT_MISSING");
                Path evidence = project.resolve(".superpowers/verification").resolve("m7-home-" + nonce).resolve(phase);
                require(Files.isDirectory(evidence, LinkOption.NOFOLLOW_LINKS) && evidence.toRealPath().equals(evidence), "M7_HOME_CONFIG_EVIDENCE " + evidence);
                for (var existing : List.of("final.json", "halt.json"))
                    require(!Files.exists(evidence.resolve(existing)), "M7_HOME_CONFIG_PHASE_ALREADY_USED " + evidence);
                return new Configuration(nonce, row, phase, startup, root, evidence);
            } catch (IOException failure) { throw new UncheckedIOException("M7_HOME_CONFIG_ROOT_UNREADABLE", failure); }
        }
    }

    private enum Step { SETUP, READY, AWAIT_CUT, AWAIT_CLOSE, QUIET, DONE }

    /** 一個 phase 的 runner：生命週期、cut 事件、halt／final receipt 與斷言。只在 server thread 執行（cut 事件由正式 owner 在 server thread 呼叫）。 */
    static final class Run {
        private static final Identifier AFTER = Identifier.of("quantumchamber:m7_home_after");
        private static final Identifier BEFORE = Identifier.of("quantumchamber:m7_home_before");
        final Configuration config;
        final Row row;
        private final Map<String, Object> proof = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Boolean> observed = new LinkedHashMap<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final List<Map<String, Object>> checkpoints = new ArrayList<>();
        private MinecraftServer server;
        private ServerWorld overworld, b;
        private UUID chamber, sid, participantId;
        private String participantName;
        private ConnectedGameTestPlayer participant;
        private Map<String, Object> pose;
        private Runnable releaseA = () -> { }, releaseB = () -> { };
        private Step step = Step.SETUP;
        private int stepTicks, ticks, cutHits;
        private boolean armed, finished, passed;
        private volatile boolean stoppingSeen;
        private JsonObject crash;
        private final Map<String, Object> startFiles = new LinkedHashMap<>();
        private List<String> homeChunks;
        private long reopenStartTime;
        private int eventsBeforeQuiet, checkpointsBeforeQuiet;
        private List<Map<String, Object>> receiptsBeforeQuiet;

        Run(Configuration config) {
            this.config = config;
            this.row = config.row();
        }

        private boolean crashPhase() { return config.phase().equals("crash"); }

        void start() {
            proof.put("producer", "M7HomeProbe");
            proof.put("m7ProbeSchema", 1);
            proof.put("row", row.name());
            proof.put("door", row.door());
            proof.put("cut", row.cut());
            proof.put("group", row.group().name());
            proof.put("phase", config.phase());
            proof.put("rowTable", rows());
            proof.put("nonce", config.nonce());
            proof.put("startupNonce", config.startupNonce());
            proof.put("pid", ProcessHandle.current().pid());
            proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            proof.put("root", config.root().toString());
            proof.put("runtimeScope", "testmod-present");
            proof.put("loadedModIds", FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList());
            proof.put("failures", failures);
            if (!crashPhase()) {
                // 開服之前讀 crash 留下的原件：journal 檔與收據目錄的 bytes 就是 crash 當下的狀態（還沒有任何程式碰過）。
                try {
                    crash = JsonParser.parseString(Files.readString(config.evidence().resolveSibling("crash").resolve("halt.json"))).getAsJsonObject();
                    startFiles.put("journalSha256", sha256(config.root().resolve("world/data/" + SessionRecoveryState.STATE_ID + ".dat")));
                    startFiles.put("receipts", listing(config.root().resolve("world/data/quantumchamber_home_receipts")));
                } catch (Exception | Error failure) {
                    failures.add("crashReceiptUnreadable：" + failure);
                    LOGGER.error("M7 home probe：讀不到 crash 的原件", failure);
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
            LOGGER.info("M7_HOME_BEGIN row={} phase={} startupNonce={}", row.name(), config.phase(), config.startupNonce());
        }

        // ---- 生命週期 ----

        private void started(MinecraftServer owner) {
            if (server != null) return;
            server = owner;
            overworld = owner.getOverworld();
            guarded(() -> {
                require(failures.isEmpty(), "M7_HOME_PRESTART_FAILED " + failures);
                require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world").toRealPath()), "M7_HOME_SAVE_ROOT");
                homeChunks = homeChunks();
                if (crashPhase()) setupCrash(); else setupReopen();
            });
        }

        private void tick(MinecraftServer owner) {
            if (owner != server || finished || step == Step.SETUP) return;
            guarded(() -> {
                ticks++;
                switch (step) {
                    case READY -> ready();
                    case AWAIT_CUT -> awaitCut();
                    case AWAIT_CLOSE -> awaitClose();
                    case QUIET -> quiet();
                    default -> { }
                }
            });
        }

        private void stopping(MinecraftServer owner) {
            if (owner != server) return;
            stoppingSeen = true;
            proof.put("stoppingSeen", true);
            try { if (participant != null) participant.close(); }
            catch (RuntimeException failure) { failures.add("closeParticipant：" + failure); }
        }

        private void stopped(MinecraftServer owner) {
            if (owner != server) return;
            proof.put("stoppedSeen", true);
            proof.put("ending", "STOP");
            proof.put("events", events);
            proof.put("checkpoints", checkpoints);
            proof.put("observed", observed);
            proof.put("ticks", ticks);
            proof.put("status", passed && failures.isEmpty() && stoppingSeen ? "PASS" : "FAIL");
            try { writeDurably(config.evidence().resolve("final.json"), JSON.toJson(proof)); }
            catch (IOException failure) { LOGGER.error("M7 home probe：final receipt 寫不出來", failure); }
            LOGGER.info("M7_HOME_END row={} phase={} status={}", row.name(), config.phase(), proof.get("status"));
        }

        private void stop() {
            if (finished) return;
            finished = true;
            proof.put("stopRequested", true);
            server.stop(false);
        }

        private void guarded(Checked action) {
            try { action.run(); }
            catch (Exception | Error failure) {
                failures.add(failure.toString());
                LOGGER.error("M7 home probe 失敗：row={} phase={}", row.name(), config.phase(), failure);
                if (server != null) stop();
            }
        }

        // ---- crash phase ----

        private void setupCrash() throws Exception {
            var journal = SessionRecoveryState.get(server);
            expect("freshJournal", journal.allSessions().isEmpty() && journal.allFlushedSessions().isEmpty(), () -> "journal=" + journal.allSessions().keySet());
            expect("freshCatalog", UniverseRegistryState.get(server).flushedRecords().isEmpty(), () -> "catalog 不是空的");
            expect("freshReceipts", !Files.exists(receiptsDir()), () -> "收據目錄已存在");
            // A：M5 collapse probe 的原艙寫法（基岩外殼、bulkhead 門、Controller），不供電；參與者帶 QuantumState 在艙內，以正式 attemptArm 登記。
            overworld.getChunk(FRAME.controllerPos());
            placeChamber(overworld);
            releaseA = M7HomeClosureGameTests.holdChunks(overworld, FRAME);
            participantId = UUID.randomUUID();
            participantName = "m7-" + participantId.toString().substring(0, 8);
            participant = connect();
            var player = participant.player();
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
            M5GameTestSupport.moveInside(player, FRAME);
            var controller = (ChamberControllerBlockEntity) overworld.getBlockEntity(FRAME.controllerPos());
            var arm = new ChamberActivationService().attemptArm(overworld, controller);
            expect("originRegistered", arm.accepted() && controller.chamberUuid() != null, () -> "arm=" + arm);
            chamber = controller.chamberUuid();
            M7HomeClosureGameTests.requireCandidateStores(server);
            // B：同 Chamber 已確認 COMPLETE 的投影（沿 Phase A 的 Projection fixture；正式 B 來源票到 T5 才有，以測試票保持 B 的 chunks）。
            var universe = UniverseId.of(UUID.randomUUID());
            b = M6ProfileProbePhases.fixtureSource(server, universe, "normal");
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, universe, DimensionRole.OVERWORLD, FRAME.controllerPos(),
                    FRAME.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
            index.confirmed(pending);
            releaseB = M7HomeClosureGameTests.holdChunks(b, FRAME);
            var written = ProjectionWriter.write(b, FRAME, ProjectionWriter.Mode.FIRST_WRITE);
            expect("projectionWritten", written.status() == ProjectionWriter.Status.WRITTEN, () -> "write=" + written);
            ((ChamberControllerBlockEntity) b.getBlockEntity(FRAME.controllerPos())).setInstanceKind(ChamberInstanceKind.PROJECTION);
            index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
            var entry = new EntryEndpoint(chamber, universe, b.getRegistryKey().getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), FRAME.outwardFacing(),
                    ChamberInstanceKind.PROJECTION);
            var root = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), FRAME.outwardFacing(),
                    ChamberInstanceKind.ORIGIN);
            expect("entryValid", HomeEndpointAuthority.inspectEntry(server, entry) == HomeEndpointAuthority.EntryStatus.VALID,
                    () -> "inspectEntry=" + HomeEndpointAuthority.inspectEntry(server, entry));
            var applied = new ChamberDoorService().applyTarget(b, FRAME, row.initialDoor());
            expect("bDoorInitial", applied.succeeded() && row.initialDoorSummary().equals(doorSummary(b)), () -> applied + " door=" + doorSummary(b));
            // HOME DORMANT：經正式 journal 入口（先占後寫、checked ARMING→SUPERPOSITION→MEASURED+HOME→返回 B→清租約）。
            sid = UUID.randomUUID();
            dormant(journal, root, entry);
            // 參與者已返回 B：站在 B 艙內正中（不在門口），之後每個 tick 核對零移動。
            var spot = ChamberSpaceCoordinates.position(FRAME, 3.5, 1, 3.5);
            expect("participantInB", player.teleport(b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && player.getServerWorld() == b, () -> "world=" + player.getServerWorld());
            var setup = new LinkedHashMap<String, Object>();
            setup.put("chamber", chamber.toString());
            setup.put("sid", sid.toString());
            setup.put("universe", universe.toString());
            setup.put("entryWorld", b.getRegistryKey().getValue().toString());
            setup.put("participant", Map.of("uuid", participantId.toString(), "name", participantName));
            setup.put("homeChunks", homeChunks);
            proof.put("setup", setup);
            step = Step.READY;
        }

        /** 等 A、B 的相關 chunks 與 entity storage 就緒；停用 A、做基線 checkpoint、arm cut。 */
        private void ready() throws Exception {
            var chunks = chunkSet();
            if (!ChunkSaveCheckpoint.ready(overworld, chunks) || !ChunkSaveCheckpoint.ready(b, chunks)) {
                require(++stepTicks <= READY_TICKS, "M7_HOME_CHUNKS_NOT_READY");
                return;
            }
            var aBefore = doorSummary(overworld);
            var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
            expect("rootDisabledAcked", lifecycle.setEnabled(chamber, false), () -> "setEnabled(false) 未回報成功");
            var journal = SessionRecoveryState.get(server);
            var latched = journal.allFlushedSessions().get(sid);
            expect("stopLatchedDurable", latched instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid))
                    && h.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && h.home().closeIntent() instanceof None
                    && !journal.rollForwardPending(sid) && journal.pendingSessions().isEmpty(), () -> "row=" + latched);
            var stopped = (HomeSessionRecord) latched;
            // 基線：停用、stop latch 與 B 門初始狀態全部落盤（原生 checkpoint 含 registry 等 PersistentState 與 level.dat）；playerdata 一併存下。
            ChunkSaveCheckpoint.save(server, Map.of(overworld, chunks, b, chunks));
            server.getPlayerManager().saveAllPlayerData();
            var aDoor = doorSummary(overworld);
            expect("aDoorUnchangedByDisable", aDoor.equals(aBefore), () -> aBefore + "→" + aDoor);
            pose = pose(participant.player());
            var baseline = new LinkedHashMap<String, Object>();
            baseline.put("aDoor", aDoor);
            baseline.put("bDoorInitial", doorSummary(b));
            baseline.put("stoppedAt", stopped.home().stoppedAtGameTime());
            baseline.put("stoppedRow", String.valueOf(stopped));
            baseline.put("pose", pose);
            baseline.put("journalSha256", sha256(journalFile()));
            baseline.put("receipts", listing(receiptsDir()));
            baseline.put("registryEnabled", registryEnabled());
            baseline.put("busy", gate().globalChamberBusy(server, chamber));
            baseline.put("gameTime", overworld.getTime());
            expect("baselineShape", row.initialDoorSummary().equals(baseline.get("bDoorInitial")) && listing(receiptsDir()).isEmpty()
                    && Boolean.FALSE.equals(baseline.get("registryEnabled")) && Boolean.TRUE.equals(baseline.get("busy")), () -> "baseline=" + baseline);
            proof.put("baseline", baseline);
            armed = true;
            step = Step.AWAIT_CUT;
            stepTicks = 0;
            LOGGER.info("M7_HOME_ARMED row={} cut={} sid={}", row.name(), row.cut(), sid);
        }

        private void awaitCut() {
            expect("participantStill", pose(participant.player()).equals(pose), () -> "pose=" + pose(participant.player()) + " baseline=" + pose);
            require(++stepTicks <= CUT_TICKS, "cutReached：" + CUT_TICKS + " 個 tick 內沒有走到 " + row.cut() + "；events=" + eventNames());
        }

        /** 正式 owner 的 cut 事件（{@link M7HomeProbe#reached}）。 */
        void reached(String name, UUID session, Object detail) {
            if (server == null || !server.isOnThread()) { failures.add("eventOffThread：" + name); return; }
            try {
                var record = new LinkedHashMap<String, Object>();
                record.put("event", name);
                record.put("serverTick", server.getTicks());
                record.put("gameTime", overworld.getTime());
                record.put("detail", describe(detail));
                events.add(record);
                if (sid == null || !sid.equals(session)) failures.add("foreignSession：" + name + " session=" + session);
                if (crashPhase() && !armed) failures.add("eventBeforeArm：" + name);
                if (name.equals("before-checkpoint")) checkpoints.add(checkpointObservation(detail));
                if (crashPhase() && armed && name.equals(row.cut())) {
                    cutHits++;
                    halt();
                }
            } catch (RuntimeException | Error failure) {
                failures.add("eventRecord：" + name + "：" + failure);
                LOGGER.error("M7 home probe：事件記錄失敗 {}", name, failure);
            }
        }

        /** crash：只有乾淨的 halt（第一次 hit、沒有失敗、停服尚未開始、事件序列恰為 cut 之前的前綴）以 77 結束；否則 HALT_REFUSED、78。 */
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
                halt.put("ending", "HALT");
                halt.put("events", events);
                halt.put("checkpoints", checkpoints);
                halt.put("observed", observed);
                halt.put("atHalt", snapshot());
                halt.put("haltServerTick", server.getTicks());
                halt.put("haltTimeUtc", Instant.now().toString());
                writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            } catch (Exception | Error failure) {
                LOGGER.error("M7 home probe：halt receipt 寫不出來", failure);
                exit = HALT_FAILED_EXIT;
            }
            if (exit != HALT_EXIT) LOGGER.error("M7 home probe：不是乾淨的 halt（{}），以 {} 結束", refused, exit);
            Runtime.getRuntime().halt(exit);
        }

        // ---- reopen phase ----

        private void setupReopen() throws Exception {
            require(crash != null, "M7_HOME_CRASH_RECEIPT_MISSING");
            expect("crashReceiptMatches", "HALT".equals(text(crash, "status")) && "crash".equals(text(crash, "phase")) && row.name().equals(text(crash, "row"))
                    && config.nonce().equals(text(crash, "nonce")) && !config.startupNonce().equals(text(crash, "startupNonce"))
                    && crash.getAsJsonArray("refused").isEmpty() && crash.get("hit").getAsInt() == 1, () -> "crash=" + crash);
            var setup = crash.getAsJsonObject("setup");
            var baseline = crash.getAsJsonObject("baseline");
            var atHalt = crash.getAsJsonObject("atHalt");
            chamber = UUID.fromString(text(setup, "chamber"));
            sid = UUID.fromString(text(setup, "sid"));
            participantId = UUID.fromString(text(setup.getAsJsonObject("participant"), "uuid"));
            participantName = text(setup.getAsJsonObject("participant"), "name");
            proof.put("setup", JSON.fromJson(setup, Map.class));
            expect("homeChunksStable", JSON.toJsonTree(homeChunks).equals(setup.get("homeChunks")), () -> homeChunks + " vs " + setup.get("homeChunks"));
            // 識別：開服前讀到的 bytes 等於 crash 當下（hash 與目錄清單逐項相同）。
            expect("journalBytesFromCrash", Objects.equals(startFiles.get("journalSha256"), text(atHalt, "journalSha256")),
                    () -> startFiles.get("journalSha256") + " vs " + text(atHalt, "journalSha256"));
            expect("receiptsFromCrash", JSON.toJsonTree(startFiles.get("receipts")).equals(atHalt.get("receipts")),
                    () -> startFiles.get("receipts") + " vs " + atHalt.get("receipts"));
            b = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(text(setup, "entryWorld"))));
            expect("bMaterializedAtStart", b != null, () -> "B 世界不存在：" + text(setup, "entryWorld"));
            var journal = SessionRecoveryState.get(server);
            var flushed = journal.allFlushedSessions().get(sid);
            String expectedRow = switch (row.group()) {
                case A -> text(baseline, "stoppedRow");
                case B, C -> text(atHalt.getAsJsonObject("flushed"), "text");
                case D -> null;
            };
            expect("journalRowFromCrash", expectedRow == null ? flushed == null && journal.allSessions().get(sid) == null
                    : flushed != null && expectedRow.equals(String.valueOf(flushed)) && flushed.equals(journal.allSessions().get(sid)),
                    () -> "flushed=" + flushed + " expected=" + expectedRow);
            // 兩側門格：開服時（正式 owner 還沒有推進）由磁碟載入的門。
            releaseA = M7HomeClosureGameTests.holdChunks(overworld, FRAME);
            releaseB = M7HomeClosureGameTests.holdChunks(b, FRAME);
            String diskDoor = row.doorCheckpointed() ? OPEN_NONE : row.initialDoorSummary();
            var bAtStart = doorSummary(b);
            expect("bDoorDiskState", diskDoor.equals(bAtStart) && (row.doorCheckpointed() || bAtStart.equals(text(baseline, "bDoorInitial"))),
                    () -> "B 門=" + bAtStart + " 預期=" + diskDoor);
            expect("aDoorAtStart", text(baseline, "aDoor").equals(doorSummary(overworld)), () -> "A 門=" + doorSummary(overworld));
            // 零移動：參與者以 crash 基線存下的 playerdata 登入。
            participant = connect();
            pose = pose(participant.player());
            expect("participantLoginPose", JSON.toJsonTree(pose).equals(baseline.get("pose")), () -> "login=" + pose + " baseline=" + baseline.get("pose"));
            var start = snapshot();
            expect("busyAtStart", Boolean.valueOf(row.group() != Group.D).equals(start.get("busy")), () -> "busy=" + start.get("busy"));
            proof.put("start", start);
            reopenStartTime = overworld.getTime();
            step = Step.AWAIT_CLOSE;
            stepTicks = 0;
        }

        private void awaitClose() throws Exception {
            expect("participantStill", pose(participant.player()).equals(pose), () -> "pose=" + pose(participant.player()) + " login=" + pose);
            var journal = SessionRecoveryState.get(server);
            boolean gone = !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid);
            if (row.group() == Group.D) {
                expect("removedStaysRemoved", gone && events.isEmpty() && OPEN_NONE.equals(doorSummary(b)), () -> "events=" + eventNames() + " door=" + doorSummary(b));
                if (++stepTicks < QUIET_TICKS) return;
            } else if (!gone) {
                require(++stepTicks <= CLOSE_TICKS, "closedAfterReopen：" + CLOSE_TICKS + " 個 tick 內沒有收尾；events=" + eventNames() + " row=" + journal.allSessions().get(sid));
                return;
            }
            verifyEnd();
            // 移除確認後不再修舊門：之後玩家自己把 B 門關上（模擬），舊旅程不得再打開。
            var closed = new ChamberDoorService().applyTarget(b, FRAME, ChamberDoorService.DoorState.CLOSED);
            expect("playerCloseApplied", closed.succeeded() && "CLOSED/NONE".equals(doorSummary(b)), () -> closed + " door=" + doorSummary(b));
            eventsBeforeQuiet = events.size();
            checkpointsBeforeQuiet = checkpoints.size();
            receiptsBeforeQuiet = listing(receiptsDir());
            step = Step.QUIET;
            stepTicks = 0;
        }

        private void verifyEnd() throws Exception {
            var baseline = crash.getAsJsonObject("baseline");
            var atHalt = crash.getAsJsonObject("atHalt");
            var journal = SessionRecoveryState.get(server);
            expect("activeRemoved", !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid) && !journal.pendingSessions().containsKey(sid)
                    && !journal.rollForwardPending(sid) && !journal.quarantinedSessions().contains(sid) && journal.allSessions().isEmpty(),
                    () -> "journal=" + journal.allSessions().keySet());
            expect("reopenEvents", eventNames().equals(row.reopenEvents()), () -> "events=" + eventNames() + " expected=" + row.reopenEvents());
            var doorOutcome = events.stream().filter(event -> event.get("event").equals("after-door")).map(event -> String.valueOf(event.get("detail"))).toList();
            expect("reopenDoorOutcome", row.group() == Group.D ? doorOutcome.isEmpty() : doorOutcome.equals(List.of(row.reopenDoorOutcome())), () -> "outcome=" + doorOutcome);
            expect("reopenCheckpoint", checkpoints.size() == (row.group() == Group.D ? 0 : 1) && checkpoints.stream().allMatch(call -> checkpointOk(call, row.group() == Group.C)),
                    () -> "checkpoints=" + checkpoints);
            // 收據：exact、依矩陣的原因與證據；收據已存在的群組不得覆寫。
            var store = new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT));
            var receipt = store.lookup(sid).orElseThrow(() -> new AssertionError("receiptPublished：沒有本 SID 的收據"));
            long stoppedAt = baseline.get("stoppedAt").getAsLong();
            var close = receipt.home().closeIntent();
            expect("receiptClose", close instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED && k.doorGoal() == DoorGoal.OPEN
                    && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, stoppedAt)) && k.closedAtGameTime() >= stoppedAt, () -> "close=" + close);
            var receiptClose = JSON.toJsonTree(closeFacts(close));
            expect("receiptCloseOrigin", row.group() == Group.A ? ((Close) close).closedAtGameTime() >= reopenStartTime
                    : receiptClose.equals(row.group() == Group.D ? atHalt.getAsJsonObject("receipt").get("close") : atHalt.getAsJsonObject("flushed").get("close")),
                    () -> "close=" + receiptClose + " crash=" + atHalt);
            expect("receiptSession", text(baseline, "stoppedRow").equals(String.valueOf(receipt.withClose(new None()))), () -> "receipt=" + receipt);
            var end = listing(receiptsDir());
            var crashReceipts = atHalt.getAsJsonArray("receipts");
            var dat = sid + ".dat";
            boolean receiptsOk = switch (row.group()) {
                case A -> names(end).equals(List.of(dat));
                case B -> row.receiptsAtHalt().isEmpty() ? names(end).equals(List.of(dat))
                        : end.size() == 2 && names(end).contains(dat) && crashReceipts.size() == 1
                        && end.stream().filter(file -> !file.get("name").equals(dat)).map(JSON::toJsonTree).toList().equals(List.of(crashReceipts.get(0)))
                        && end.stream().filter(file -> file.get("name").equals(dat)).findFirst().orElseThrow().get("sha256")
                        .equals(crashReceipts.get(0).getAsJsonObject().get("sha256").getAsString());
                case C, D -> JSON.toJsonTree(end).equals(crashReceipts);
            };
            expect("receiptFiles", receiptsOk, () -> "end=" + end + " crash=" + crashReceipts);
            expect("bDoorClosedOut", OPEN_NONE.equals(doorSummary(b)), () -> "B 門=" + doorSummary(b));
            expect("aDoorUntouched", text(baseline, "aDoor").equals(doorSummary(overworld)), () -> "A 門=" + doorSummary(overworld));
            expect("resourcesReleased", !gate().globalChamberBusy(server, chamber) && !rootLeasePresent(), () -> "busy=" + gate().globalChamberBusy(server, chamber));
            proof.put("end", snapshot());
        }

        private void quiet() throws Exception {
            expect("playerClosedDoorKept", "CLOSED/NONE".equals(doorSummary(b)), () -> "B 門=" + doorSummary(b));
            expect("participantStill", pose(participant.player()).equals(pose), () -> "pose=" + pose(participant.player()));
            if (++stepTicks < QUIET_TICKS) return;
            var journal = SessionRecoveryState.get(server);
            expect("noRepairAfterRemoval", events.size() == eventsBeforeQuiet && checkpoints.size() == checkpointsBeforeQuiet
                    && listing(receiptsDir()).equals(receiptsBeforeQuiet) && journal.allSessions().isEmpty(), () -> "events=" + eventNames());
            proof.put("quiet", Map.of("ticks", QUIET_TICKS, "bDoor", doorSummary(b)));
            passed = true;
            step = Step.DONE;
            stop();
        }

        // ---- fixture ----

        private ConnectedGameTestPlayer connect() {
            var connection = new ConnectedGameTestPlayer(overworld, new GameProfile(participantId, participantName));
            connection.confirmTeleport();
            connection.player().setNoGravity(true);
            return connection;
        }

        /** M5 collapse probe 的原艙寫法（{@code M5CollapseFixture.placeChamber}），不放紅石塊：A 不供電，只登記。 */
        private static void placeChamber(ServerWorld world) {
            for (int x = -1; x <= 7; x++) for (int y = 0; y <= 9; y++) for (int z = -6; z < 0; z++)
                world.setBlockState(ChamberSpaceCoordinates.block(FRAME, x, y, z), Blocks.AIR.getDefaultState(), 3);
            ChamberProtectionService.get().authorizedMutation(() -> {
                for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
                    boolean shell = x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6;
                    var block = shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                    if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) block = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                    world.setBlockState(ChamberGeometry.localToWorld(FRAME, x, y, z), block, 3);
                }
                world.setBlockState(FRAME.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, Direction.NORTH), 3);
                return null;
            });
        }

        /** 經正式 journal 入口走到已選定、全員返回、無租約的 MEASURED（HOME DORMANT）；沿 Phase A {@code Projection.settled／dormant}。 */
        private void dormant(SessionRecoveryState journal, ChamberOriginAuthority root, EntryEndpoint entry) {
            var person = new SessionRecoveryRecord.Participant(participantId, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), false);
            var lease = new SessionRecoveryRecord.SpaceLease(0, new BlockBox(610000, 64, 610000, 610006, 70, 610006));
            var empty = new Home(new Unselected(), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var arming = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.ARMING, empty);
            expect("provisionalClaimed", gate().claimProvisional(server, chamber, sid), () -> "先占失敗");
            expect("checkedArming", journal.admitHome(server, arming).committed() && gate().finishProvisional(server, chamber, sid), () -> "ARMING 未 checked");
            var active = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.SUPERPOSITION, empty);
            expect("checkedSuperposition", journal.commitHomeRestore(server, arming, active).committed(), () -> "SUPERPOSITION 未 checked");
            var selected = new Home(new Selected(new DoorKey(sid, 1, DoorKey.DoorWallSide.POSITIVE_LATERAL), participantId, overworld.getTime()), -1, -1, -1,
                    List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var measured = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.MEASURED, selected);
            expect("checkedHomeSelection", journal.commitHomeRestore(server, active, measured).committed(), () -> "HOME 選擇未 checked");
            var returned = new SessionRecoveryRecord.Participant(participantId, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), true);
            var settled = new HomeSessionRecord(sid, chamber, root, entry, List.of(returned), List.of(lease), SessionState.MEASURED, selected);
            expect("checkedReturned", journal.commitHomeRollForward(server, measured, settled).committed(), () -> "返回 B 未 checked");
            var dormant = new HomeSessionRecord(sid, chamber, root, entry, List.of(returned), List.of(), SessionState.MEASURED, selected);
            expect("checkedDormant", journal.commitHomeRollForward(server, settled, dormant).committed(), () -> "清除租約未 checked");
        }

        // ---- 觀察 ----

        private Map<String, Object> snapshot() {
            var s = new LinkedHashMap<String, Object>();
            var journal = SessionRecoveryState.get(server);
            s.put("current", rowFacts(journal.allSessions().get(sid)));
            s.put("flushed", rowFacts(journal.allFlushedSessions().get(sid)));
            s.put("pending", journal.pendingSessions().containsKey(sid));
            s.put("rollForwardPending", journal.rollForwardPending(sid));
            s.put("quarantined", journal.quarantinedSessions().contains(sid));
            s.put("journalSessions", journal.allSessions().size());
            try { s.put("journalSha256", sha256(journalFile())); } catch (IOException failure) { s.put("journalSha256", "ERROR:" + failure); }
            try { s.put("receipts", listing(receiptsDir())); } catch (IOException failure) { s.put("receipts", "ERROR:" + failure); }
            s.put("receipt", receiptFacts());
            s.put("bDoor", doorSummary(b));
            s.put("aDoor", doorSummary(overworld));
            s.put("pose", participant == null ? "NONE" : pose(participant.player()));
            s.put("busy", gate().globalChamberBusy(server, chamber));
            s.put("registryEnabled", registryEnabled());
            s.put("rootTicketLease", rootLeasePresent());
            s.put("gameTime", overworld.getTime());
            return s;
        }

        private Map<String, Object> checkpointObservation(Object detail) {
            var call = new LinkedHashMap<String, Object>();
            var worlds = new ArrayList<Map<String, Object>>();
            if (detail instanceof Map<?, ?> relevant)
                for (var entry : relevant.entrySet())
                    if (entry.getKey() instanceof ServerWorld world && entry.getValue() instanceof Set<?> chunks)
                        worlds.add(Map.of("world", world.getRegistryKey().getValue().toString(), "chunks", chunks.stream().map(chunk -> {
                            var pos = (ChunkPos) chunk;
                            return pos.x + "," + pos.z;
                        }).sorted().toList()));
            call.put("worlds", worlds);
            var flushed = SessionRecoveryState.get(server).allFlushedSessions().get(sid);
            call.put("activeClose", flushed instanceof HomeSessionRecord h && h.home().closeIntent() instanceof Close);
            call.put("bDoor", doorSummary(b));
            call.put("receiptPresent", Boolean.TRUE.equals(receiptFacts().get("present")));
            call.put("serverTick", server.getTicks());
            return call;
        }

        /** 本 phase 的正式 checkpoint：只寫 B 世界、chunk 集合等於 homeChunks、當下熱列仍在且 CLOSE 已凍結、門已是 OPEN/NONE。 */
        private boolean checkpointOk(Map<String, Object> call, boolean receiptPresent) {
            return JSON.toJsonTree(call.get("worlds")).equals(JSON.toJsonTree(List.of(Map.of("world", b.getRegistryKey().getValue().toString(), "chunks", homeChunks))))
                    && Boolean.TRUE.equals(call.get("activeClose")) && OPEN_NONE.equals(call.get("bDoor")) && Boolean.valueOf(receiptPresent).equals(call.get("receiptPresent"));
        }

        private Map<String, Object> rowFacts(JournalSession session) {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("present", session != null);
            if (session instanceof HomeSessionRecord h) {
                facts.put("state", h.state().name());
                facts.put("stopReason", h.home().stopReason().map(Enum::name).orElse("NONE"));
                facts.put("stoppedAt", h.home().stoppedAtGameTime());
                facts.put("close", closeFacts(h.home().closeIntent()));
                facts.put("text", String.valueOf(h));
            } else if (session != null) facts.put("text", String.valueOf(session));
            return facts;
        }

        private static Map<String, Object> closeFacts(CloseIntent intent) {
            var facts = new LinkedHashMap<String, Object>();
            if (intent instanceof Close close) {
                facts.put("kind", "CLOSE");
                facts.put("reason", close.reason().name());
                facts.put("closedAt", close.closedAtGameTime());
                facts.put("doorGoal", close.doorGoal().name());
                facts.put("evidence", String.valueOf(close.evidence()));
            } else facts.put("kind", "NONE");
            return facts;
        }

        private Map<String, Object> receiptFacts() {
            var facts = new LinkedHashMap<String, Object>();
            try {
                var stored = new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)).lookup(sid);
                facts.put("present", stored.isPresent());
                stored.ifPresent(row -> {
                    facts.put("close", closeFacts(row.home().closeIntent()));
                    facts.put("session", String.valueOf(row.withClose(new None())));
                });
            } catch (IOException | RuntimeException failure) { facts.put("error", failure.toString()); }
            return facts;
        }

        private boolean registryEnabled() {
            var record = ChamberRegistryState.get(server).registry().records().get(chamber);
            return record != null && record.enabled();
        }

        /** 原艙票 owner（{@code HomeRootTickets}）是否仍有本 SID 的 lease（反射讀 private 欄位，只讀）。 */
        private boolean rootLeasePresent() {
            try {
                var type = Class.forName("dev.quantumchamber.collapse.HomeRootTickets");
                var servers = (Map<?, ?>) field(type, null, "servers");
                var owner = servers.get(server);
                return owner != null && ((Map<?, ?>) field(type, owner, "leases")).containsKey(sid);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_HOME_ROOT_TICKETS_UNREADABLE", failure); }
        }

        private List<String> eventNames() { return events.stream().map(event -> (String) event.get("event")).toList(); }

        private Set<ChunkPos> chunkSet() {
            var chunks = new java.util.LinkedHashSet<ChunkPos>();
            for (var text : homeChunks) {
                var parts = text.split(",");
                chunks.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            }
            return Set.copyOf(chunks);
        }

        private Path journalFile() { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat"); }

        private Path receiptsDir() { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("quantumchamber_home_receipts"); }

        private void expect(String name, boolean condition, Supplier<String> detail) {
            if (!condition) throw new AssertionError(name + "：" + detail.get());
            observed.put(name, true);
        }
    }

    // ---- 共用 ----

    @FunctionalInterface interface Checked { void run() throws Exception; }

    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /** 正式 owner 用的 B 相關 chunks（{@code PassageTransferPolicy.homeChunks}，package 可見，反射呼叫、只讀），依 {@code x,z} 排序。 */
    private static List<String> homeChunks() {
        try {
            var method = Class.forName("dev.quantumchamber.collapse.PassageTransferPolicy").getDeclaredMethod("homeChunks", ChamberFrame.class);
            method.setAccessible(true);
            return ((Set<?>) method.invoke(null, FRAME)).stream().map(chunk -> {
                var pos = (ChunkPos) chunk;
                return pos.x + "," + pos.z;
            }).sorted().toList();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_HOME_CHUNKS_UNREADABLE", failure); }
    }

    /** 門 25 格：全部相同時是 {@code 開關/膜}，否則 {@code MIXED}（沿 Phase A {@code Projection.doorSummary}）。 */
    static String doorSummary(ServerWorld world) {
        String seen = null;
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            var state = world.getBlockState(ChamberGeometry.localToWorld(FRAME, x, y, 0));
            if (!state.isOf(ModBlocks.QUANTUM_BULKHEAD)) return "NOT_BULKHEAD";
            var cell = (state.get(QuantumBulkheadBlock.OPEN) ? "OPEN" : "CLOSED") + "/" + QuantumBulkheadBlock.membrane(state).name();
            if (seen == null) seen = cell; else if (!seen.equals(cell)) return "MIXED";
        }
        return seen;
    }

    private static Map<String, Object> pose(ServerPlayerEntity player) {
        var pose = new LinkedHashMap<String, Object>();
        pose.put("world", player.getServerWorld().getRegistryKey().getValue().toString());
        pose.put("x", Double.toString(player.getX()));
        pose.put("y", Double.toString(player.getY()));
        pose.put("z", Double.toString(player.getZ()));
        pose.put("yaw", Float.toString(player.getYaw()));
        pose.put("pitch", Float.toString(player.getPitch()));
        return pose;
    }

    private static Object describe(Object detail) {
        if (detail == null) return "NONE";
        if (detail instanceof Enum<?> value) return value.name();
        if (detail instanceof ChamberDoorService.DoorState door) return (door.open() ? "OPEN" : "CLOSED") + "/" + door.membrane().name();
        if (detail instanceof Map<?, ?> relevant) return relevant.keySet().stream().map(world -> ((ServerWorld) world).getRegistryKey().getValue().toString()).sorted().toList();
        return String.valueOf(detail);
    }

    /** 目錄內每個檔（依名稱排序）：名稱、SHA-256、大小、修改時間；目錄不存在時是空的。 */
    static List<Map<String, Object>> listing(Path dir) throws IOException {
        var files = new ArrayList<Map<String, Object>>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return files;
        try (var stream = Files.list(dir)) {
            for (var path : stream.sorted().toList()) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", path.getFileName().toString());
                entry.put("sha256", sha256(path));
                entry.put("size", Long.toString(Files.size(path)));
                entry.put("lastModified", Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toString());
                files.add(entry);
            }
        }
        return files;
    }

    private static List<String> names(List<Map<String, Object>> files) { return files.stream().map(file -> (String) file.get("name")).toList(); }

    static String sha256(Path path) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Object field(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    /** CREATE_NEW 寫出並 {@code force}（halt 之前一定落盤；已存在時丟出，不覆寫）。 */
    static void writeDurably(Path path, String text) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
