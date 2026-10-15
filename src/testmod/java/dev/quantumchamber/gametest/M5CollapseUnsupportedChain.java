package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.candidate.CandidateLedgerService;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberPowerState;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5CollapseWindows.OriginDoorState;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2e-1）：W10（spec §12 第 11 點、§11.3；交接清單 F6-2-04），M4 時期的 Nether／End 收據跨真重啟。同一批（同一份 schema 3 的 journal）有三筆收據：
 * <ul>
 * <li>{@code NEW}：Nether 的第一座原艙（{@link M5CollapseFixture#FRAME}，在 Nether）上真入場的 live session，在 operation guard 內直接經 ledger 選一扇 NEW 門（比照同 JVM 的
 *     G-NLIVE：M5 的側門互動拒絕 Nether 的新選擇，M4 沒有這條規則）：非 DORMANT 的 MEASURED（參與者在走廊、還沒有返還、有 lease），也就是 M4 時期留在 Nether 的收據。這條 chain 的
 *     fixture（session、原艙、參與者、推導出的 UniverseId）是它。</li>
 * <li>{@code SOURCE}：Nether 的第二座原艙（{@link #SOURCE_FRAME}）上的 DORMANT SOURCE 收據（參與者已返還、沒有 lease；同 G-NETHER 的寫法）。</li>
 * <li>{@code END}：End 的 DORMANT NEW 收據（同 G-NDOR 的寫法：不蓋原艙，參與者是不會連線的 UUID，已返還）。</li>
 * </ul>
 * crash phase（{@link #crash}）：三筆收據都寫入之後，以正式的寫出（全新的 {@link SessionRecoveryState}，沒有 M5 證據，{@code writtenSchema} 是 3）把 journal 換成 schema 3 寫到磁碟、讀回核對，
 * 裝上 {@code M4_RECEIPT_STEP} 的 crash 點；塌縮流程第一次處理這批收據的任何一筆之前（下一個 tick 的 {@code CollapseService.step} 開頭）halt。所以 M5 在 crash 之前從沒有處理過它們，磁碟上的
 * journal 仍是 schema 3，restart 沒有 runtime（= M4 時期的 journal（schema 3）第一次被 M5 處理；allocation、projection、discovery 三個檔已由 crash phase 的 M5 初始化建立，ledger 缺檔屬 W11）。選 halt 而不是正常停服：正常停服會存 playerdata、在 journal dirty 時重寫它（schema 4），probe 收下
 * 的連線也會在停服時斷線；halt 讓磁碟停在 schema 3 的 fixture 寫完的那一刻。
 * <p>restart phase（{@link #restart}）：開服時 journal 仍是 schema 3、三筆收據 exact、NEW 的走廊 space 由 attach 重建並已請求 M4 的保留式 recovery、沒有 runtime，什麼都還沒有配置。
 * 之後（spec §11.3、§7.1／§7.4）：END 的 DORMANT 在開服之後的第一次觀察就取消 → NEW 的參與者 JOIN 在走廊 → M4 的保留式 recovery 返還到 Nether 的原艙、清理走廊 → DORMANT → 取消
 * （從不提交 COLLAPSING），原艙解除封鎖、參與者照一般方式開門 → SOURCE 的參與者由艙外進入 → COLLAPSING（SOURCE）→ SOURCE fast path → 終局。不套用
 * {@code M5CollapseRestartChains} 的 crash 骨架與 restart builder（它們的前提是由收據提交 COLLAPSING，見 {@code M5CollapseRestartChains.CrashSetup}）。
 */
final class M5CollapseUnsupportedChain {
    /** Nether 的第二座原艙（SOURCE 收據）：Controller 在 (24,70,8)、朝北，與 Nether 的第一座（{@link M5CollapseFixture#FRAME}）相隔 16 格，兩座的石牆不重疊。 */
    static final ChamberFrame SOURCE_FRAME = new ChamberFrame(new BlockPos(24, 70, 8), Direction.NORTH);
    /** End 的 DORMANT NEW 收據的原艙位置（不蓋艙、不載入；遠離主島，同 G-NDOR 的「遠方」）。 */
    static final BlockPos END_ORIGIN = new BlockPos(5008, 70, 5008);
    /** Nether 的兩座原艙以 probe 的 ticket 同步載入的半徑（chunk，以第一座的 Controller 所在 chunk 為中心）：涵蓋兩座的石牆與 SOURCE 的 Controller。 */
    private static final int NETHER_LOAD_RADIUS = 2;
    /** END 的取消最多等幾個 tick（開服之後的第一次觀察；同 W8 的 {@code FIRST_OBSERVATION} 3）。 */
    private static final int FIRST_OBSERVATION_TICKS = 3;
    /** SOURCE 的參與者進入之後，最多等幾個 tick 到終局（同 G-NETHER 的 40）。 */
    private static final int SOURCE_WAIT_TICKS = 40;
    /**
     * NEW 的參與者 JOIN 之後，最多等幾個 tick 返還到 Nether 的原艙（step {@code returned}）。F6-2e-2（F6-2e-1 quality Minor 3）：原本是手寫等待的 {@code Step} 上限 200（到時是
     * stepBounded），改經 {@link M5CollapsePassage#awaiting}，上限同為 200（第 1～200 個 tick 判斷、第 200 個 tick 仍沒有返還時由具名斷言判 FAIL）。
     */
    private static final int RETURN_WAIT_TICKS = 200;
    /** 返還之後，最多等幾個 tick 取消（step {@code cancelled}）；同 {@link #RETURN_WAIT_TICKS}，原本是手寫等待的上限 400。 */
    private static final int CANCEL_WAIT_TICKS = 400;
    /** 三筆收據（{@link SessionRecoveryState}，schema 3）的 evidence 檔；restart 讀回比對「沒有重抽」。 */
    static final String BATCH_NBT = "w10-batch.nbt";
    private static final String CANCELLED = "不支援的來源維度，已安全取消";

    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private UUID sourcePlayer;
    private UUID sourceChamber;
    /** 這一批的收據：NEW（fixture 的 session）、SOURCE、END。crash phase 寫入時、restart 開服時讀回時設定。 */
    private SessionRecoveryRecord sourceReceipt, endReceipt;
    /** restart：NEW 的 session 是否寫出過 COLLAPSING、第一次 checked 寫成 DORMANT 的 server tick；SOURCE 第一次寫出 COLLAPSING 時的 Destination（journal 寫出的探針）。 */
    private volatile boolean newWroteCollapsing;
    private volatile int newDormantAt = -1;
    /** restart：NEW 的取消（checked 終局）寫出的 server tick：journal 寫出時 NEW 已不在 staged records、仍在 flushed 的第一次（F6-2e-3，F6-2e-2 quality Minor 2）。 */
    private volatile int newRemovedAt = -1;
    private volatile CollapseDestination sourceDestination;
    private int discoveryAtStart = -1;

    M5CollapseUnsupportedChain(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
    }

    private ServerWorld nether() { return run.server().getWorld(World.NETHER); }

    // ---- crash phase ----

    /**
     * W10 的 crash：fresh 世界在 Nether 蓋兩座原艙、連線兩位參與者（{@code build}）→ 兩座都註冊（{@code registered}）→ NEW 的參與者在第一座艙內以真實的 LOW→HIGH 供電與 Buff
     * 啟動（{@code armed}，比照 {@link M5CollapseFixture#arm}）→ SUPERPOSITION、358 扇側門、參與者在走廊（{@code superposition}）→ 經 ledger 選 NEW 門、寫入另兩筆收據、原生存檔、
     * 把 journal 換成 schema 3、裝上 crash 點（{@code receipts}）→ 下一個 tick 塌縮流程第一次處理這批收據之前 halt。
     */
    Phase crash() {
        return Phase.halt(List.of(
                new Step("build", 1, this::buildInTheNether),
                new Step("registered", 200, this::registered),
                new Step("armed", 200, this::armed),
                new Step("superposition", 400, this::superposition),
                new Step("receipts", 1, this::m4Receipts),
                new Step("halt", 5, () -> false))).started(fixture::requireFreshWorld);
    }

    /**
     * 蓋原艙：關掉生物的自然生成（{@code doMobSpawning}，存進 level.dat；Nether 的敵對生物會在石牆裡的通道生成、攻擊參與者），以 probe 的 ticket 同步載入兩座艙附近，各蓋一座以石牆
     * 圍住的原艙（{@link #buildBunker}，同 G-NETHER 的寫法，隔開岩漿與地形）與 Controller 上方的紅石塊，清掉附近的非玩家 entity（{@link M5CollapseFixture#clearAroundTheOrigin}），
     * 再連線兩位參與者、各移到自己那座艙的門外。
     */
    private boolean buildInTheNether() {
        var nether = nether();
        run.server().getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, run.server());
        run.expect("netherChambersLoaded", fixture.loadAroundTheChamberNow(nether, NETHER_LOAD_RADIUS), () -> "Nether 的兩座原艙附近沒有都載入到 FULL");
        for (var frame : List.of(FRAME, SOURCE_FRAME)) {
            buildBunker(nether, frame);
            nether.setBlockState(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        M5CollapseFixture.clearAroundTheOrigin(run, "new", nether, FRAME);
        M5CollapseFixture.clearAroundTheOrigin(run, "source", nether, SOURCE_FRAME);
        intoTheNether(fixture.connectParticipant(UUID.randomUUID()).player(), FRAME);
        sourcePlayer = UUID.randomUUID();
        intoTheNether(fixture.connect(sourcePlayer).player(), SOURCE_FRAME);
        return true;
    }

    /** 把 {@code player} 傳送到 Nether 的 {@code frame} 艙內，再移到門外（不受重力，參與者在門外等待）。 */
    private void intoTheNether(ServerPlayerEntity player, ChamberFrame frame) {
        var inside = ChamberSpaceCoordinates.position(frame, 3.5, 1, 3.5);
        player.teleport(nether(), inside.x, inside.y, inside.z, Set.of(), 0, 0);
        player.setVelocity(Vec3d.ZERO);
        M5GameTestSupport.moveOutside(player, frame);
    }

    /**
     * 以石牆圍住的原艙（比照 {@code M5GameTestSupport.buildBunkerChamber}，那裡是 private）：艙的區域座標 x −3..9、y −1..9、z −5..9 的邊界一圈石頭、其餘清空，再蓋 7×7×7 的艙
     * （基岩外殼、關著的 25 格門、朝北的 Controller）。
     */
    private static void buildBunker(ServerWorld world, ChamberFrame frame) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int x = -3; x <= 9; x++) for (int y = -1; y <= 9; y++) for (int z = -5; z <= 9; z++) {
                boolean boundary = x == -3 || x == 9 || y == -1 || y == 9 || z == -5 || z == 9;
                boolean chamber = x >= 0 && x <= 6 && y >= 0 && y <= 6 && z >= 0 && z <= 6;
                BlockState state;
                if (boundary) state = Blocks.STONE.getDefaultState();
                else if (!chamber) state = Blocks.AIR.getDefaultState();
                else if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) state = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, false);
                else state = ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                world.setBlockState(ChamberSpaceCoordinates.block(frame, x, y, z), state, Block.NOTIFY_ALL);
            }
            world.setBlockState(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing()), Block.NOTIFY_ALL);
        });
    }

    /** step：Nether 的兩座原艙都已註冊、IDLE；記下 chamber UUID（第一座記在 fixture 由 {@code armed} 的 record 帶入）。 */
    private boolean registered() {
        var nether = nether();
        if (!M5GameTestSupport.registeredIdle(nether, FRAME) || !M5GameTestSupport.registeredIdle(nether, SOURCE_FRAME)) return false;
        sourceChamber = M5GameTestSupport.controller(nether, SOURCE_FRAME).chamberUuid();
        return true;
    }

    /**
     * step：參與者移到 Nether 的第一座艙內，再以 {@link M5CollapseFixture#arm(ServerWorld, M5CollapseFixture.Who)} 啟動（拿掉紅石塊（LOW）、給 Buff，readiness 通過之後存他的 checkpoint
     * 與原生存檔，再放回紅石塊（HIGH）→ 這座艙必須立即有 checked ARMING，{@code armingFlushed}），ARMING 就是這條 chain 的 session。readiness 還沒有通過時下一個 tick 再試。
     * F6-2e-2（F6-2e-1 quality Minor 1）：原本這裡另寫一份幾乎相同的 arm（fixture 的版本寫死來源世界）；移到艙內仍在 arm 之前（同一個 tick，原本是拿掉紅石塊之後），其餘順序與斷言相同。
     */
    private boolean armed() throws Exception {
        var player = fixture.player(fixture.participants().getFirst());
        if (!M5CollapseFixture.inside(player)) M5GameTestSupport.moveInside(player, FRAME);
        var arming = fixture.arm(nether(), M5CollapseFixture.EVERYONE);
        if (arming == null) return false;
        fixture.session(arming.sessionUuid());
        return true;
    }

    /** step：SUPERPOSITION、358 扇側門都已發布、參與者在走廊。 */
    private boolean superposition() {
        var record = run.journal().flushedRecords().get(fixture.sid());
        if (record == null || record.state() != SessionState.SUPERPOSITION || fixture.pages().selectableDoors(fixture.sid()).size() != 358) return false;
        return fixture.players().stream().allMatch(player -> player.getServerWorld() == fixture.corridor());
    }

    /**
     * step {@code receipts}（一個 tick 之內，塌縮流程這個 tick 已處理過全部 session）：
     * <ol>
     * <li>NEW：繞過側門互動（它拒絕 Nether 的新選擇，spec §11.3），照它的 guard 與檢查在 operation guard 之內直接經 ledger 選一扇 NEW 門（G-NLIVE 的寫法）→ checked MEASURED、
     *     非 DORMANT（參與者還沒有返還、有 lease：{@code liveNetherNewReceipt}），還沒有請求保留式 recovery。</li>
     * <li>SOURCE 與 END 的 DORMANT 收據 put＋flush（{@code batchFlushed}：journal 恰為這三筆）。</li>
     * <li>NEW 的收據寫出 {@link M5CollapseRun#RECEIPT_NBT}（restart 以 {@code readCrashReceipt} 讀回），三筆寫出 {@link #BATCH_NBT}；原生存檔（參與者的 playerdata 在走廊與 Nether）。</li>
     * <li>journal 換成 schema 3（{@link #writeSchemaThreeJournal}），裝上 {@code M4_RECEIPT_STEP} 的 crash 點。</li>
     * </ol>
     */
    private boolean m4Receipts() throws Exception {
        var server = run.server();
        var sid = fixture.sid();
        var pages = fixture.pages();
        var corridor = fixture.corridor();
        var player = fixture.player(fixture.participants().getFirst());
        var door = M5GameTestSupport.candidateDoor(pages, corridor, sid, QuantumCandidate.New.class);
        var location = pages.candidateDoor(corridor, door.cell()).orElseThrow();
        var manager = (SuperpositionSessionManager) ChamberSessions.gateway();
        var outcome = pages.exclusiveOperation(sid, () -> {
            var selectable = pages.candidateSelectionRecord(location, player);
            if (selectable.isEmpty() || !manager.candidateSourceAuthorized(server, selectable.get())) return CandidateLedgerService.SelectionOutcome.REJECTED;
            var result = new CandidateLedgerService().trySelect(server, sid, location.key(), player.getUuid(), corridor.getTime());
            if (result == CandidateLedgerService.SelectionOutcome.SELECTED) manager.candidateMeasured(server, sid);
            return result;
        });
        var measured = run.journal().flushedRecords().get(sid);
        run.expect("liveNetherNewReceipt", outcome == CandidateLedgerService.SelectionOutcome.SELECTED && measured != null && measured.state() == SessionState.MEASURED
                && measured.selectedCandidate().orElse(null) instanceof QuantumCandidate.New && measured.origin().role() == DimensionRole.NETHER
                && MeasuredRecoveryPhase.from(measured) == MeasuredRecoveryPhase.RETURN_PLAYERS && !pages.measuredRecoveryRequested(sid),
                () -> "選門結果 " + outcome + "，record=" + M5GameTestSupport.state(run.journal(), sid) + " phase=" + (measured == null ? "-" : MeasuredRecoveryPhase.from(measured)));
        fixture.adopt(measured);
        sourceReceipt = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(sourceChamber, World.NETHER, SOURCE_FRAME.controllerPos()),
                Map.of(sourcePlayer, true), List.of(), false);
        endReceipt = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(UUID.randomUUID(), World.END, END_ORIGIN), Map.of(UUID.randomUUID(), true),
                List.of(), true);
        run.journal().put(sourceReceipt);
        run.journal().put(endReceipt);
        run.journal().flush(server);
        var batch = batch(measured);
        run.expect("batchFlushed", run.journal().flushedRecords().equals(batch), () -> "journal 不是恰為這三筆：" + run.journal().flushedRecords().keySet());
        fixture.recordReceipt();
        writeBatch(batch);
        run.expect("nativeSaveInTheCorridor", server.saveAll(false, true, true), () -> "saveAll 回 false");
        writeSchemaThreeJournal(batch);
        M5CollapseWindows.arm(run, fixture, CrashWindow.M4_RECEIPT_STEP);
        return true;
    }

    /**
     * halt receipt 的 {@value #PROOF}（F6-2e-2，F6-2e-1 quality nit：原本寫出與讀回各寫一次 6 個鍵）：三筆收據的 session、SOURCE 的參與者與原艙，以及 {@link #BATCH_NBT} 的 SHA-256。
     * 寫出經 {@link #proof}、讀回經 {@link #read}，鍵只在這裡。
     */
    record Batch(UUID newSession, UUID source, UUID end, UUID sourceParticipant, UUID sourceChamber, String sha256) {
        static final String PROOF = "batch";
        private static final String NEW = "new", SOURCE = "source", END = "end", SOURCE_PARTICIPANT = "sourceParticipant", SOURCE_CHAMBER = "sourceChamber",
                SHA256 = "batchNbtSha256";

        Map<String, Object> proof() {
            var noted = new LinkedHashMap<String, Object>();
            noted.put(NEW, newSession.toString());
            noted.put(SOURCE, source.toString());
            noted.put(END, end.toString());
            noted.put(SOURCE_PARTICIPANT, sourceParticipant.toString());
            noted.put(SOURCE_CHAMBER, sourceChamber.toString());
            noted.put(SHA256, sha256);
            return noted;
        }

        /** crash phase 的 halt receipt 裡的 {@value #PROOF}。 */
        static Batch read(com.google.gson.JsonObject halt) {
            var noted = halt.getAsJsonObject(PROOF);
            return new Batch(uuid(noted, NEW), uuid(noted, SOURCE), uuid(noted, END), uuid(noted, SOURCE_PARTICIPANT), uuid(noted, SOURCE_CHAMBER),
                    M5CollapseRun.text(noted, SHA256));
        }

        private static UUID uuid(com.google.gson.JsonObject noted, String key) {
            return UUID.fromString(M5CollapseRun.text(noted, key));
        }
    }

    /** NEW、SOURCE、END 三筆（依這個順序），以 session 為鍵。 */
    private Map<UUID, SessionRecoveryRecord> batch(SessionRecoveryRecord measured) {
        var batch = new LinkedHashMap<UUID, SessionRecoveryRecord>();
        for (var record : List.of(measured, sourceReceipt, endReceipt)) batch.put(record.sessionUuid(), record);
        return batch;
    }

    /**
     * 把磁碟上的 journal 換成 M4 時期的 schema 3：以正式的寫出建立 envelope（{@link M5CollapseDiskEvidence#writeJournal}：全新的 {@link SessionRecoveryState}，只 put 這三筆：candidate
     * 已初始化、沒有 M5 證據，{@code writtenSchema} 是 3；與 {@link M5CollapseFixture#recordReceipt} 的 receipt NBT 同一個寫法），加上本版的 DataVersion 寫到正式檔（同 legacy runner 的
     * {@code reloadEmptySchemaThree}，那裡是空的 schema 3），再以 {@link M5CollapseDiskEvidence#expectJournalOnDisk} 讀回（{@code schemaThreeJournalWritten}）。記憶體裡的 journal
     * （本 JVM 已初始化 M5 authority，寫出會是 schema 4）不再寫出：下一個 tick 就 halt，halt 之前再讀一次（窗口表的 {@code schemaThreeJournalAtHalt}）。
     */
    private void writeSchemaThreeJournal(Map<UUID, SessionRecoveryRecord> batch) throws Exception {
        M5CollapseDiskEvidence.writeJournal(run.data().resolve(M5CollapseDiskEvidence.JOURNAL), batch.values(), SharedConstants.getGameVersion().getSaveVersion().getId());
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalWritten", 3, batch);
    }

    /** 三筆收據寫成 {@link #BATCH_NBT}（{@link M5CollapseDiskEvidence#writeJournal}，與 receipt NBT 同一個寫法），SHA-256 與三個 session 記在 proof 的 {@code batch}。 */
    private void writeBatch(Map<UUID, SessionRecoveryRecord> batch) throws Exception {
        var file = run.config.evidence().resolve(BATCH_NBT);
        M5CollapseRun.require(!Files.exists(file), "batch NBT 已存在：" + file);
        M5CollapseDiskEvidence.writeJournal(file, batch.values());
        run.proof.put(Batch.PROOF, new Batch(fixture.sid(), sourceReceipt.sessionUuid(), endReceipt.sessionUuid(), sourcePlayer, sourceChamber,
                M5CollapseDiskEvidence.sha256(file)).proof());
    }

    // ---- crash 點的條件與 halt 之前的取證（窗口表的 M4_RECEIPT_STEP 一列） ----

    /** {@code M4_RECEIPT_STEP} 的條件：塌縮流程這次要處理的是 M4 時期的 Nether／End 收據（MEASURED、來源 role 不是 OVERWORLD；fresh 世界裡只有這條 chain 的這一批）。 */
    static boolean m4ReceiptStep(M5CollapseFixture fixture, Object detail) {
        return detail instanceof SessionRecoveryRecord record && record.state() == SessionState.MEASURED && record.origin().role() != DimensionRole.OVERWORLD;
    }

    /**
     * {@code M4_RECEIPT_STEP} 的 halt 之前：磁碟上的 journal 仍是 schema 3、恰為記憶體裡 flushed 的三筆（step {@code receipts} 之後沒有任何寫出，{@code schemaThreeJournalAtHalt}）；
     * NEW 的參與者的 playerdata 在走廊（{@code participantInTheCorridorOnDisk}）。
     */
    static void m4ReceiptsAtHalt(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalAtHalt", 3, run.journal().flushedRecords());
        var corridor = fixture.corridor().getRegistryKey().getValue().toString();
        for (var id : fixture.participants()) {
            var dimension = M5CollapseDiskEvidence.playerDimension(run.saveRoot(), id);
            run.expect("participantInTheCorridorOnDisk", corridor.equals(dimension), () -> "參與者的 playerdata 在 " + dimension);
        }
    }

    // ---- restart phase ----

    /**
     * W10 的 restart（見類別說明）。step：
     * <ol>
     * <li>{@code endCancelled}：END 的 DORMANT 在開服之後的第一次觀察就取消（{@code dormantCancelledOnTheFirstObservation}，最多 {@value #FIRST_OBSERVATION_TICKS} 個 tick）。</li>
     * <li>{@code join}：NEW 的參與者 JOIN 在走廊（被 recovery 攔截），SOURCE 的參與者 JOIN 在 Nether 的第二座艙外。</li>
     * <li>{@code returned}：M4 的保留式 recovery 把 NEW 的參與者返還到 Nether 的第一座艙內，record 仍是 MEASURED（{@code returnedWithoutCommitting}）。</li>
     * <li>{@code cancelled}：走廊清理完成、DORMANT 之後取消（{@code cancelledAfterTheRetainedRecovery}），原艙解除封鎖（{@code originUnblocked}），沒有人被困住
     *     （{@code nobodyTrappedAfterTheCancel}）。</li>
     * <li>{@code opened}：參與者在艙內照一般方式開門（{@code originDoorOpensNormally}）。</li>
     * <li>{@code sourceEntered}、{@code sourceCompleted}：SOURCE 的參與者由艙外進入 → COLLAPSING（SOURCE）→ SOURCE fast path → 終局（{@code sourceReceiptCompleted}、{@code sourceChamberUnblocked}、
     *     {@code nobodyTrappedAtTheSource}）。</li>
     * <li>{@code end}：spec §12 的四件事（{@link #fourThingsAtTheEnd}）。</li>
     * </ol>
     * 每個 tick：catalog 一直是空的、NEW 從沒有寫出 COLLAPSING（{@link #invariants}）。
     */
    Phase restart(String crashPhase) {
        return Phase.stop(List.of(
                        M5CollapsePassage.awaiting("endCancelled", FIRST_OBSERVATION_TICKS, () -> gone(endReceipt), this::dormantCancelledOnTheFirstObservation),
                        new Step("join", 1, this::joinBoth),
                        M5CollapsePassage.awaiting("returned", RETURN_WAIT_TICKS, this::returnedToTheNether, this::returnedWithoutCommitting),
                        M5CollapsePassage.awaiting("cancelled", CANCEL_WAIT_TICKS, () -> gone(fixture.receipt()), this::cancelledAfterTheRetainedRecovery),
                        new Step("opened", 1, this::originDoorOpensNormally),
                        new Step("sourceEntered", 1, () -> { M5GameTestSupport.moveInside(fixture.player(sourcePlayer), SOURCE_FRAME); return true; }),
                        M5CollapsePassage.awaiting("sourceCompleted", SOURCE_WAIT_TICKS, () -> gone(sourceReceipt), this::sourceReceiptCompleted),
                        new Step("end", 1, this::fourThingsAtTheEnd)))
                .started(() -> started(crashPhase)).everyTick(this::invariants);
    }

    /**
     * restart 開服時（第一個 tick 之前）：讀回 crash phase 的 halt receipt 與 NEW 的收據（{@code readCrashReceipt}：乾淨的 halt、receipt NBT、UniverseId 與參與者），再讀回三筆的
     * {@link #BATCH_NBT}（SHA-256 與 halt receipt 相同）；斷言：
     * <ul>
     * <li>{@code schemaThreeJournalAtStart}：開服時磁碟上的 journal 仍是 schema 3、恰為這三筆（這次開服讀的就是 M4 時期的 journal；之後第一次 checked 寫入才升為 schema 4，spec §12）。</li>
     * <li>{@code receiptsKeptAcrossTheRestart}：flushed 的三筆與收據 exact 相同。</li>
     * <li>{@code recoveryRequestedByTheAttach}：NEW 沒有 runtime；attach 已重建它的走廊 space，並已請求 M4 的保留式 recovery（{@code measuredRecoveryRequested}）；參與者還沒有返還、
     *     有 lease。attach 另為它取得來源 ticket（M4 的保留式 recovery 要來源 Controller 的 chunk 已載入，{@code SuperpositionSessionManager.sourceAuthority}；只記下：{@code newSourceTicketAtStart}、
     *     {@code newChamberLoadedAtStart}）。</li>
     * <li>{@code dormantReceiptsAtStart}：SOURCE 與 END 是 DORMANT。</li>
     * <li>{@code nothingAllocatedAtStart}：allocation ledger、catalog、projection ledger 都是空的，U 沒有物化。</li>
     * </ul>
     * 之後裝上 journal 寫出的探針（{@link #watchTheFlush}）。不以 probe 的 ticket 載入 Nether 的原艙：NEW 的返還靠 attach 取得的來源 ticket，SOURCE 的觸發靠參與者 JOIN 之後自己的
     * ticket（都是 production 的路徑）。
     */
    private void started(String crashPhase) throws Exception {
        var halt = fixture.readCrashReceipt(crashPhase, CrashWindow.M4_RECEIPT_STEP);
        var noted = Batch.read(halt);
        var file = run.phaseEvidence(crashPhase).resolve(BATCH_NBT);
        run.expect("batchNbtIntact", M5CollapseDiskEvidence.sha256(file).equals(noted.sha256()), () -> "batch NBT 的 SHA-256 與 halt receipt 不同");
        var decoded = SessionRecoveryState.fromNbt(NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data"));
        decoded.requireHealthy();
        var batch = decoded.flushedRecords();
        sourceReceipt = batch.get(noted.source());
        endReceipt = batch.get(noted.end());
        sourcePlayer = noted.sourceParticipant();
        sourceChamber = noted.sourceChamber();
        var sid = fixture.sid();
        run.expect("batchDecoded", batch.size() == 3 && fixture.receipt().equals(batch.get(sid)) && sourceReceipt != null && endReceipt != null,
                () -> "batch NBT 的 session：" + batch.keySet());
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalAtStart", 3, batch);
        var flushed = run.journal().flushedRecords();
        run.expect("receiptsKeptAcrossTheRestart", batch.keySet().stream().allMatch(id -> batch.get(id).equals(flushed.get(id))),
                () -> "開服時的 record：" + batch.keySet().stream().map(id -> M5GameTestSupport.state(run.journal(), id)).toList());
        var current = flushed.get(sid);
        boolean runtime = M4CandidateTestAccess.runtimeHeld(sid), ticket = M4CandidateTestAccess.sourceTicketHeld(sid);
        run.expect("recoveryRequestedByTheAttach", !runtime && fixture.spaceExists() && fixture.pages().measuredRecoveryRequested(sid)
                        && current.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty()
                        && MeasuredRecoveryPhase.from(current) == MeasuredRecoveryPhase.RETURN_PLAYERS,
                () -> "runtime=" + runtime + " 來源 ticket=" + ticket + " space=" + fixture.spaceExists() + " 已請求=" + fixture.pages().measuredRecoveryRequested(sid)
                        + " phase=" + MeasuredRecoveryPhase.from(current));
        run.expect("dormantReceiptsAtStart", MeasuredRecoveryPhase.from(sourceReceipt) == MeasuredRecoveryPhase.DORMANT
                && MeasuredRecoveryPhase.from(endReceipt) == MeasuredRecoveryPhase.DORMANT, () -> "SOURCE、END 不是 DORMANT");
        run.expect("nothingAllocatedAtStart", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty() && M5CollapseDiskEvidence.projections(run.data()).isEmpty()
                        && UniverseRegistryState.get(run.server()).flushedRecords().isEmpty() && fixture.catalogBefore() == 0 && fixture.universeWorld().isEmpty(),
                () -> "allocation=" + fixture.allocationsOrError() + " projection=" + fixture.projectionsOrError() + " catalog="
                        + UniverseRegistryState.get(run.server()).flushedRecords().keySet());
        discoveryAtStart = M5CollapseDiskEvidence.discovery(run.saveRoot()).size();
        // 不以 probe 的 ticket 載入 Nether 的原艙：NEW 的返還靠 attach 為它取得的來源 ticket（只記下），SOURCE 的觸發靠參與者 JOIN 之後自己的 ticket。
        run.proof.put("newSourceTicketAtStart", ticket);
        run.proof.put("newChamberLoadedAtStart", nether().getChunkManager().getWorldChunk(FRAME.controllerPos().getX() >> 4, FRAME.controllerPos().getZ() >> 4) != null);
        run.install(M5TestHooks.FLUSH_PROBE, this::watchTheFlush);
        run.proof.put("discoveryAtStart", discoveryAtStart);
    }

    /**
     * journal 每次寫出的開頭（{@code FLUSH_PROBE}，在 production 的寫出裡呼叫，不得丟出）：NEW 是否寫出過 COLLAPSING、第一次 checked 寫成 DORMANT 的 server tick（同 G-NLIVE 的探針）、
     * 取消（checked 終局：已不在 staged records、仍在 flushed）寫出的 server tick（F6-2e-3）；SOURCE 第一次寫出 COLLAPSING 時的 Destination。
     */
    private void watchTheFlush(SessionRecoveryState state) {
        var sid = fixture.sid();
        var staged = state.records().get(sid);
        var durable = state.flushedRecords().get(sid);
        if (staged != null) {
            if (staged.state() == SessionState.COLLAPSING) newWroteCollapsing = true;
            boolean stagedDormant = staged.state() == SessionState.MEASURED && MeasuredRecoveryPhase.from(staged) == MeasuredRecoveryPhase.DORMANT;
            boolean durableDormant = durable != null && durable.state() == SessionState.MEASURED && MeasuredRecoveryPhase.from(durable) == MeasuredRecoveryPhase.DORMANT;
            if (newDormantAt < 0 && stagedDormant && !durableDormant) newDormantAt = run.server().getTicks();
        } else if (durable != null && newRemovedAt < 0) {
            // F6-2e-3：checked 終局先從 records 移除再 flush（SessionRecoveryState.terminate），所以這次寫出就是取消的寫出。
            newRemovedAt = run.server().getTicks();
        }
        var source = sourceReceipt == null ? null : state.records().get(sourceReceipt.sessionUuid());
        if (source != null && source.state() == SessionState.COLLAPSING && sourceDestination == null) sourceDestination = source.collapse().destination();
    }

    /** 每個 tick：catalog 一直是空的（{@code catalogUnchangedEveryTick}），NEW 從沒有寫出 COLLAPSING、還在時是 MEASURED（{@code newReceiptNeverCommitted}）。 */
    private void invariants() {
        var catalog = UniverseRegistryState.get(run.server()).records();
        run.expect("catalogUnchangedEveryTick", catalog.isEmpty(), () -> "catalog=" + catalog.keySet());
        var record = run.journal().records().get(fixture.sid());
        run.expect("newReceiptNeverCommitted", !newWroteCollapsing && (record == null || record.state() == SessionState.MEASURED),
                () -> "NEW 寫出過 COLLAPSING=" + newWroteCollapsing + " state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
    }

    /** {@code receipt} 的 session 已不在 journal（flushed 與 current 都沒有）。 */
    private boolean gone(SessionRecoveryRecord receipt) {
        var sid = receipt.sessionUuid();
        return !run.journal().flushedRecords().containsKey(sid) && !run.journal().records().containsKey(sid);
    }

    /** 這個 session 的 {@code level} 而且含有 {@code fragment} 的 log 則數（{@code SERVER_STARTED} 起）。 */
    private int logged(Level level, String fragment, UUID sid) {
        return run.capture().matching(level, fragment, sid.toString()).size();
    }

    /**
     * step {@code endCancelled}：END 的 DORMANT NEW 收據在開服之後的第一次觀察就以 checked 終局取消（spec §11.3 第 2 步：「一觀察到 flushed DORMANT（開服 attach 或執行期間皆同），不必等
     * 參與者」）：journal 與磁碟上都沒有它、「不支援的來源維度，已安全取消」恰一則、從沒有提交、沒有 WARN（{@code dormantCancelledOnTheFirstObservation}）。
     */
    private void dormantCancelledOnTheFirstObservation(int tick, boolean happened) {
        var sid = endReceipt.sessionUuid();
        int cancelled = logged(Level.INFO, CANCELLED, sid), committed = logged(Level.INFO, "塌縮已提交", sid), warned = logged(Level.WARN, "", sid);
        run.expect("dormantCancelledOnTheFirstObservation", () -> happened && !M4CandidateTestAccess.diskRecords(run.server()).containsKey(sid) && cancelled == 1 && committed == 0
                        && warned == 0,
                () -> "開服之後 " + tick + " 個 tick：END 已取消=" + happened + " 取消的 INFO=" + cancelled + " 提交=" + committed + " WARN=" + warned);
        run.proof.put("endCancelledAtServerTick", run.server().getTicks());
    }

    /**
     * step {@code join}：NEW 的參與者 JOIN 在走廊（playerdata 是 crash 之前存的），被 recovery 攔截；拿掉他的 Buff（取消之後原艙仍供電、他回到艙內：沒有 Buff 就不會立刻開始新的 session，
     * 同 G-NLIVE）。SOURCE 的參與者 JOIN 在 Nether、第二座艙外。
     */
    private boolean joinBoth() {
        var id = fixture.participants().getFirst();
        var connection = fixture.connect(id);
        var player = connection.player();
        run.expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
        run.expect("joinedInTheCorridor", player.getServerWorld() == fixture.corridor(), () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
        run.expect("participantBlockedUntilReturned", SessionRecoveryManager.blocks(id, run.server()), () -> "尚未返還的參與者沒有被 recovery 攔截");
        player.removeStatusEffect(ModEffects.QUANTUM_STATE);
        var source = fixture.connect(sourcePlayer);
        run.expect("sourceNativeJoin", source.joinTick() >= 0, () -> "SOURCE 的參與者沒有觀察到 JOIN");
        var sourceAt = source.player();
        run.expect("sourceParticipantJoinedOutside", sourceAt.getServerWorld() == nether() && !insideTheSourceChamber(sourceAt) && !SessionRecoveryManager.blocks(sourcePlayer, run.server()),
                () -> "SOURCE 的參與者 JOIN 在 " + sourceAt.getServerWorld().getRegistryKey().getValue() + " " + sourceAt.getPos());
        run.proof.put("joinedAtServerTick", run.server().getTicks());
        return true;
    }

    /** SOURCE 的參與者在第二座艙內（{@link M5CollapseFixture#inside(ChamberFrame, ServerPlayerEntity)}；F6-2e-2 起不再另寫一份同樣的判斷）。 */
    private static boolean insideTheSourceChamber(ServerPlayerEntity player) {
        return M5CollapseFixture.inside(SOURCE_FRAME, player);
    }

    /**
     * step {@code returned}：M4 的保留式 recovery 把 NEW 的參與者返還到 Nether 的第一座艙內，flushed record 記為已返還；record 仍是 MEASURED，參與者不再被攔截
     * （{@code returnedWithoutCommitting}）。返還之後再拿掉一次 Buff（返還可能還原入場時的效果）。F6-2e-2（F6-2e-1 quality Minor 3）：經 {@link M5CollapsePassage#awaiting}：
     * {@link #returnedToTheNether} 只讀，{@link #RETURN_WAIT_TICKS} 個 tick 仍沒有返還時由 {@code returnedWithoutCommitting} 判 FAIL（原本是 stepBounded）。
     */
    private boolean returnedToTheNether() {
        var player = fixture.player(fixture.participants().getFirst());
        var record = run.journal().flushedRecords().get(fixture.sid());
        return player.getServerWorld() == nether() && record != null && record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
    }

    private void returnedWithoutCommitting(int tick, boolean happened) {
        var id = fixture.participants().getFirst();
        var player = fixture.player(id);
        var record = run.journal().flushedRecords().get(fixture.sid());
        boolean inside = M5CollapseFixture.inside(player), blocked = SessionRecoveryManager.blocks(id, run.server());
        run.expect("returnedWithoutCommitting", happened && record.state() == SessionState.MEASURED && inside && !blocked,
                () -> "JOIN 之後 " + tick + " 個 tick：已返還=" + happened + " state=" + (record == null ? "ABSENT" : record.state()) + " 在艙內=" + inside
                        + " 仍被攔截=" + blocked + " 位置=" + player.getPos());
        run.proof.put("buffAfterTheReturn", player.hasStatusEffect(ModEffects.QUANTUM_STATE));
        player.removeStatusEffect(ModEffects.QUANTUM_STATE);
        run.proof.put("returnedAtServerTick", run.server().getTicks());
    }

    /**
     * step {@code cancelled}（返還之後最多 {@value #CANCEL_WAIT_TICKS} 個 tick）：NEW 的 record 已不在 journal 時斷言：
     * <ul>
     * <li>{@code cancelledAfterTheRetainedRecovery}：已取消（到時仍在 journal 也是這個斷言失敗）、磁碟上也沒有它、「不支援的來源維度，已安全取消」恰一則（候選 New）、沒有「塌縮已提交」、
     *     從沒有寫出 COLLAPSING、在取消之前先 checked 寫成 DORMANT（spec §11.3 第 1、2 步）。「不提交 COLLAPSING」的 INFO（塌縮流程自己請求 recovery 時才記）的則數只記為 proof：這條路徑由
     *     attach 請求。</li>
     * <li>{@code newCancelledOnTheFirstDormantObservation}（F6-2e-2，F6-2e-1 spec M-4）：執行期間「一觀察到 flushed DORMANT 就取消」（spec §11.3 第 2 步）：取消的寫出與第一次 checked
     *     寫成 DORMANT 的寫出在同一個 server tick（兩者都由 journal 寫出的探針 {@link #watchTheFlush} 記下）。F6-2e-3（F6-2e-2 quality Minor 2）：原本取消的 tick 是這個 step 觀察到 record 不在
     *     的 tick，與探針記下的 DORMANT 不是同一種量法，所以容許差 1，分不出「晚一個 tick 才取消」；改由探針記下取消的寫出、以相等比較（<b>判斷變嚴</b>）。相等是確定的：DORMANT 由走廊與
     *     session 的 {@code END_SERVER_TICK}（保留式清理完成、space 已釋放）寫出，塌縮流程的 {@code END_SERVER_TICK} 註冊在它們之後（plan 決策 1），同一個 tick 就觀察到 flushed DORMANT 並取消；
     *     F6-2e-1、F6-2e-2 的 GREEN 與 run-all 也都是差 0。F6-3 Step 0（F6-2e-3 spec M-3）：只比寫出的 tick 時，「完成的 tick」沒有上限（{@code newRemovedAt} 記的是第一次 staged 已移除的
     *     寫出；那次終局 flush 失敗、之後才確認時仍成立），所以另要求這個 step 觀察到 NEW 已不在 flushed 與 staged 的 tick 最多晚取消的寫出 1 tick（<b>判斷變嚴</b>，名稱不變）。容許 1：
     *     這個 step 在 {@code returned} 完成的下一個 tick 才開始，取消若發生在那個 tick，最早在下一個 tick 看到；F6-2e-3 起記下取消的寫出的 run（GREEN、mutation、run-all）都是差 0。</li>
     * <li>{@code corridorSpaceReleasedAtTheCancel}（F6-2e-2，F6-2e-1 spec M-5）：§11.3 第 1 步的幾何清理：這個 session 的走廊 space 已釋放（{@link M5CollapseFixture#spaceExists}）。</li>
     * <li>{@code originUnblocked}（spec §11.3 第 3 步「原艙解除封鎖」，production 的判斷）：這座艙的 presence 是 NONE（{@code SuperpositionSessionManager.start} 拒絕新 session 的條件之一）、沒有
     *     runtime 與來源 ticket、Controller 不是 ARMED、registry 的 power state 不是 RETURNING。</li>
     * <li>{@code nobodyTrappedAfterTheCancel}：參與者在 Nether 的第一座艙內、不被攔截。</li>
     * </ul>
     * F6-2e-2（F6-2e-1 quality Minor 3）：經 {@link M5CollapsePassage#awaiting}，等待的上限仍是 400（{@link #CANCEL_WAIT_TICKS}），到時沒有取消時失敗在 {@code cancelledAfterTheRetainedRecovery}
     * （原本是 stepBounded）。
     */
    private void cancelledAfterTheRetainedRecovery(int tick, boolean happened) throws Exception {
        var sid = fixture.sid();
        var chamber = fixture.receipt().chamberUuid();
        int cancelledAt = run.server().getTicks();
        int cancelled = logged(Level.INFO, CANCELLED, sid), committed = logged(Level.INFO, "塌縮已提交", sid);
        var info = run.capture().matching(Level.INFO, CANCELLED, sid.toString());
        boolean newCandidate = info.size() == 1 && info.getFirst().message().contains("候選=New");
        run.expect("cancelledAfterTheRetainedRecovery", () -> happened && !M4CandidateTestAccess.diskRecords(run.server()).containsKey(sid) && cancelled == 1 && newCandidate
                        && committed == 0 && !newWroteCollapsing && newDormantAt >= 0,
                () -> "返還之後 " + tick + " 個 tick：已取消=" + happened + " 取消的 INFO=" + cancelled + "（候選 New=" + newCandidate + "）提交=" + committed + " 寫出過 COLLAPSING="
                        + newWroteCollapsing + " 先寫成 DORMANT 的 tick=" + newDormantAt);
        int removedAt = newRemovedAt;
        // F6-3 Step 0（F6-2e-3 spec M-3）：另要求完成的 tick（這個 step 觀察到 flushed 與 staged 都沒有 NEW）最多晚取消的寫出 1 tick（判斷變嚴）。
        run.expect("newCancelledOnTheFirstDormantObservation", newDormantAt >= 0 && removedAt == newDormantAt && cancelledAt - removedAt <= 1,
                () -> "第一次寫成 DORMANT 在 tick " + newDormantAt + "，取消的寫出在 tick " + removedAt + "，這個 step 觀察到不在的 tick " + cancelledAt + "（最多晚 1 tick）");
        boolean space = fixture.spaceExists();
        run.expect("corridorSpaceReleasedAtTheCancel", !space, () -> "取消之後這個 session 仍有走廊 space");
        var presence = ChamberSessions.gateway().presence(run.server(), chamber);
        boolean runtime = M4CandidateTestAccess.runtimeHeld(sid), ticket = M4CandidateTestAccess.sourceTicketHeld(sid);
        var state = M5GameTestSupport.controller(nether(), FRAME).chamberState();
        var power = ChamberRegistryState.get(run.server()).registry().records().get(chamber).powerState();
        run.expect("originUnblocked", presence == ChamberSessionGateway.Presence.NONE && !runtime && !ticket && state != ChamberState.ARMED && power != ChamberPowerState.RETURNING,
                () -> "presence=" + presence + " runtime=" + runtime + " 來源 ticket=" + ticket + " Controller=" + state + " power=" + power);
        var id = fixture.participants().getFirst();
        var player = fixture.player(id);
        boolean inside = player.getServerWorld() == nether() && M5CollapseFixture.inside(player), blocked = SessionRecoveryManager.blocks(id, run.server());
        run.expect("nobodyTrappedAfterTheCancel", inside && !blocked, () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue() + " " + player.getPos() + " 被攔截=" + blocked);
        run.proof.put("notCommittingInfo", logged(Level.INFO, "不支援的來源維度：不提交 COLLAPSING", sid));
        run.proof.put("newDormantAtServerTick", newDormantAt);
        run.proof.put("newCancelledAtServerTick", cancelledAt);
        run.proof.put("newRemovedAtServerTick", removedAt);
    }

    /** step {@code opened}：取消之後參與者在艙內照一般方式開門（關著的門照一般門的邏輯改為一般開門；{@code originDoorOpensNormally}）。 */
    private boolean originDoorOpensNormally() {
        var nether = nether();
        boolean closedBefore = M5CollapseDoors.is(nether, FRAME, OriginDoorState.CLOSED);
        var result = M5GameTestSupport.interact(nether, M5CollapseFixture.local(3, 3, 0), fixture.player(fixture.participants().getFirst()));
        boolean plain = M5CollapseDoors.is(nether, FRAME, OriginDoorState.PLAIN_OPEN);
        run.expect("originDoorOpensNormally", closedBefore && result.isAccepted() && plain,
                () -> "開門之前關著=" + closedBefore + " 結果=" + result + " 一般開門=" + plain + " 門正中=" + nether.getBlockState(M5CollapseFixture.local(3, 3, 0)));
        return true;
    }

    /**
     * step {@code sourceCompleted}（SOURCE 的參與者進入之後最多 {@value #SOURCE_WAIT_TICKS} 個 tick）：SOURCE 的 DORMANT 收據照 §7.1／§7.4 正常完成：journal 與磁碟上都沒有它、
     * 「塌縮已提交」與「SOURCE 塌縮完成」各恰一則、沒有取消的 INFO、寫出的 COLLAPSING 的 Destination 是 SOURCE、原艙門是一般開門（{@code sourceReceiptCompleted}）；這座艙解除封鎖
     * （{@code sourceChamberUnblocked}）；參與者在 Nether 的第二座艙內、不被攔截（{@code nobodyTrappedAtTheSource}）。
     */
    private void sourceReceiptCompleted(int tick, boolean happened) {
        var sid = sourceReceipt.sessionUuid();
        int committed = logged(Level.INFO, "塌縮已提交", sid), completed = logged(Level.INFO, "SOURCE 塌縮完成", sid), cancelled = logged(Level.INFO, "不支援的來源維度", sid);
        boolean plain = M5CollapseDoors.is(nether(), SOURCE_FRAME, OriginDoorState.PLAIN_OPEN);
        var destination = sourceDestination;
        run.expect("sourceReceiptCompleted", () -> happened && !M4CandidateTestAccess.diskRecords(run.server()).containsKey(sid) && committed == 1 && completed == 1 && cancelled == 0
                        && destination instanceof CollapseDestination.Source && plain,
                () -> "進入之後 " + tick + " 個 tick：已終局=" + happened + " 提交=" + committed + " SOURCE 完成=" + completed + " 取消=" + cancelled + " Destination=" + destination
                        + " 一般開門=" + plain);
        var presence = ChamberSessions.gateway().presence(run.server(), sourceChamber);
        boolean runtime = M4CandidateTestAccess.runtimeHeld(sid), ticket = M4CandidateTestAccess.sourceTicketHeld(sid);
        run.expect("sourceChamberUnblocked", presence == ChamberSessionGateway.Presence.NONE && !runtime && !ticket,
                () -> "presence=" + presence + " runtime=" + runtime + " 來源 ticket=" + ticket);
        var player = fixture.player(sourcePlayer);
        boolean inside = player.getServerWorld() == nether() && insideTheSourceChamber(player), blocked = SessionRecoveryManager.blocks(sourcePlayer, run.server());
        run.expect("nobodyTrappedAtTheSource", inside && !blocked, () -> "SOURCE 的參與者在 " + player.getPos() + " 被攔截=" + blocked);
        run.proof.put("sourceCompletedAtServerTick", run.server().getTicks());
    }

    /**
     * step {@code end}：spec §12 的四件事。
     * <ul>
     * <li>同一個 UniverseId：NEW 與 END 取消，不配置宇宙，所以沒有任何 UniverseId（allocation ledger、catalog、discovery、projection ledger 都與開服時相同，NEW 推導出的 U 沒有物化；
     *     {@code nothingAllocated}）；SOURCE 的目的地是來源世界（{@code sourceReceiptCompleted} 的 Destination）。</li>
     * <li>catalog 最多只多 1 筆：恰為 0（{@code catalogUnchanged}；每個 tick 也斷言 {@code catalogUnchangedEveryTick}）。</li>
     * <li>沒有重抽：開服時三筆收據 exact（{@code receiptsKeptAcrossTheRestart}），取消從不提交、不經 RESOLVE（{@code newReceiptNeverCommitted}，allocation ledger 沒有這個 token）。</li>
     * <li>沒有人被困住：NEW 的參與者在 Nether 的原艙（{@code nobodyTrappedAfterTheCancel}）、SOURCE 的參與者在它的原艙（{@code nobodyTrappedAtTheSource}）；END 的參與者不會連線（收據裡已返還），
     *     不適用。</li>
     * </ul>
     * 另外三個 session 從開服起都沒有 WARN 或 ERROR（{@code noSessionError}）。
     */
    private boolean fourThingsAtTheEnd() throws Exception {
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords();
        run.expect("catalogUnchanged", catalog.size() == fixture.catalogBefore() && catalog.isEmpty(), () -> "catalog=" + catalog.keySet());
        run.expect("nothingAllocated", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty() && M5CollapseDiskEvidence.projections(run.data()).isEmpty()
                        && M5CollapseDiskEvidence.discovery(run.saveRoot()).size() == discoveryAtStart && fixture.universeWorld().isEmpty(),
                () -> "allocation=" + fixture.allocationsOrError() + " projection=" + fixture.projectionsOrError());
        var sessions = List.of(fixture.sid(), sourceReceipt.sessionUuid(), endReceipt.sessionUuid());
        Function<UUID, Integer> errors = sid -> logged(Level.WARN, "", sid) + logged(Level.ERROR, "", sid);
        var counts = sessions.stream().collect(Collectors.toMap(sid -> sid, errors));
        run.expect("noSessionError", counts.values().stream().allMatch(count -> count == 0), () -> "WARN＋ERROR=" + counts);
        return true;
    }
}
