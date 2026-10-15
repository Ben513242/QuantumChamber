package dev.quantumchamber.gametest;

import com.google.gson.JsonObject;
import dev.quantumchamber.candidate.CandidateEntropyState;
import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2e-2）：W11（spec §12 第 12 點、§6「M5 authority 初始化與遺失判定」；交接清單 F6-2-05），M4 時期的 schema 3 存檔（discovery 非空）第一次以 M5 開服、
 * M5 authority 初始化只建立了一個 ledger 空檔就 crash 的跨 JVM 版本（同 JVM 的版本是 JUnit 的 {@code interruptedInitializationRecreatesOnlyTheMissingLedger}、
 * {@code schemaThreeSaveWithNonEmptyDiscoveryIsNotTreatedAsLost}）。不經 {@link M5CollapseRestartChains} 的 crash 骨架與 restart builder：crash 在開服時的 M5 authority 初始化
 * （{@code SERVER_STARTED} 的 default phase，早於 probe 的 {@code started} 與任何 tick），不由收據提交 COLLAPSING 觸發。四個 phase，每個一個 JVM：
 * <ol>
 * <li>{@link #setup}（{@code w11-setup}，stop）：fresh 世界蓋原艙、一位參與者在艙外；照 M4 的順序建立候選的 entropy（{@link CandidateEntropyState#loadOrCreate}）、寫入一筆 M4 時期的
 *     discovery record（{@link #M4_DISCOVERY}，以 production 的 {@link UniverseDiscoveryState#save} 寫、{@code load} 讀回），再寫 DORMANT 的 NEW 收據（它的候選 context 因此凍結了非空的
 *     discovery）、原生存檔、正常停服。M4 的建置做不出這份存檔（同一個 mod），所以這個 JVM 的 M5 初始化在 fresh 世界照常建立了兩個空 ledger、停服時把 journal 寫成 schema 4；下一個
 *     phase 開服之前把它們改回 M4 的形式。</li>
 * <li>{@link #crash}（{@code w11-crash}，halt）：開服之前（{@code onInitialize}）把存檔改成 M4 時期的形式（{@link #toTheM4Save}：journal 以 production 的寫出換成 schema 3、同一筆收據；
 *     兩個空 ledger 移到 evidence；discovery 恰為那一筆），裝上 {@code FIRST_LEDGER_CREATED} 的 crash 點。開服時 M5 authority 初始化讀到「還不是 schema 4」、兩個 ledger 都不存在
 *     （{@code NOT_CREATED}）、建立第一個（allocation，{@code CREATED}）之後、建立第二個（projection）之前，hook（{@code CollapseAuthoritiesCrashWindowMixin}，{@code examine} 裡第二次
 *     {@code createIfAbsent} 之前）交出兩個結果，條件成立時 halt；halt 之前以磁碟證據確認只有 allocation ledger（空的）、projection ledger 不存在、journal 仍是 schema 3 而且就是那一筆收據、
 *     discovery 沒有被碰過（{@link #oneLedgerAtHalt}）。</li>
 * <li>{@link #unreadable}（{@code w11-unreadable}，stop）：第一次重啟，開服之前裝上 allocation ledger 讀取的 IO 失敗（{@code CHECKED_READ_FAULT}，一次）：初始化讀既有的 allocation ledger
 *     失敗（{@code UNREADABLE}）→ 不建立缺少的 projection ledger（{@code NOT_CREATED}，implementation note「初始化有任何讀取失敗時，不建立另一個缺少的 ledger」）→ 初始化失敗、ERROR 一則、
 *     原因是 IO 不是遺失、本 instance 不 ready；既有的 ledger 沒有被重寫；停服之後 journal 仍是 schema 3（spec §6「journal 也不會因此升到 schema 4」）。所以 M5 authority 初始化的輸入
 *     （journal、兩個 ledger）與 discovery 仍是 crash 當下的樣子；{@code quantumchamber_chambers.dat}、{@code raids.dat} 等其他存檔由這次正常停服重寫，與 crash 當下不同（F6-2e-3，
 *     F6-2e-2 spec review M-1）。</li>
 * <li>{@link #restart}（{@code w11-restart}，stop）：第二次重啟，開服之前確認 M5 authority 初始化的輸入（journal、兩個 ledger）與 discovery 和 crash 當下相同（所以就 M5 的初始化而言，
 *     這次開服等同 crash 之後的第一次成功的重啟；chamber registry 與 {@code raids.dat} 是 {@code w11-unreadable} 停服時寫出的，不比對，理由見 {@link #expectDiskAsAtTheCrash}）。開服時：journal 仍是 schema 3、
 *     既有的 allocation ledger 照常讀取（{@code LOADED}、不重寫）、補建 projection ledger（{@code CREATED}）、不被判為遺失（沒有初始化失敗、沒有 {@code AUTHORITY_CORRUPT}）。之後參與者
 *     JOIN 在艙外、由艙外進入 → COLLAPSING 的 checked 提交是這次開服之後的第一次寫入，journal 在這時升為 schema 4（spec §12「存檔第一次用 M5 開啟」一列）→ RESOLVE → PASSAGE_READY →
 *     跨越 → (b) 終局（spec §12 的四件事，discovery 是 M4 的那一筆之後恰多 U 一筆）。</li>
 * </ol>
 */
final class M5CollapseInitChain {
    static final String SETUP = "w11-setup";
    static final String CRASH = "w11-crash";
    static final String UNREADABLE = "w11-unreadable";
    static final String RESTART = "w11-restart";
    /** M4 時期就有的 discovery record（fixture，同 JUnit 的 {@code M4_RECORD}：ordinal 0、沒有 firstObserver、gameplay eligible）。 */
    static final UniverseDiscoveryRecord M4_DISCOVERY =
            new UniverseDiscoveryRecord(UniverseId.of(UUID.fromString("00000000-0000-4000-8000-0000000000d1")), 0, 5, Optional.empty(), true);
    /** crash phase 開服之前移走的兩個 M5 ledger（setup 的 JVM 在 fresh 世界建立的空檔）放在 phase evidence 的這個目錄。 */
    private static final String REMOVED_LEDGERS = "m5-ledgers-before-start";
    /** halt 之前記下的 allocation ledger 的 SHA-256 與修改時間（halt receipt 的 {@code atHalt}）；之後的 phase 以它們確認既有的 ledger 沒有被重寫。 */
    static final String ALLOCATION_SHA256 = "allocationLedgerSha256", ALLOCATION_MODIFIED = "allocationLedgerModified";
    /** halt 之前記下的 journal 與 discovery 的 SHA-256（同上）。 */
    static final String JOURNAL_SHA256 = "journalSha256", DISCOVERY_SHA256 = "discoverySha256";
    /**
     * proof 的鍵（F6-2e-3，F6-2e-2 quality Minor 5：原本是字面字串或拼接）：setup 寫入的 discovery 的 SHA-256（crash 開服之前由 setup 的 receipt 讀回比對）、crash 開服之前的磁碟摘要與
     * discovery 的 SHA-256（halt 之前的取證以它比對）。值與原本的字串相同。
     */
    private static final String M4_DISCOVERY_SHA256 = "m4DiscoverySha256", M4_SAVE_BEFORE_START = "m4SaveBeforeStart",
            DISCOVERY_SHA256_BEFORE_START = DISCOVERY_SHA256 + "BeforeStart";
    /** w11-unreadable 的 watch 段的 tick 數。 */
    private static final int UNINITIALIZED_WATCH_TICKS = 20;

    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** w11-restart：開服之前（{@code beforeStart}）的 journal 正式檔的 SHA-256；之後只要它變了，schema 就必須是 4。 */
    private String journalAtStart;
    /** 兩次重啟：crash 的 halt receipt 的 {@code atHalt}（開服之前讀回，{@link #expectDiskAsAtTheCrash}）。 */
    private JsonObject atHalt;

    M5CollapseInitChain(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    // ---- setup ----

    /** {@code w11-setup}（見類別說明）：{@code build} → {@code registered} → {@code m4Discovery} → {@code receipt} → 正常停服。 */
    Phase setup() {
        return Phase.stop(List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(1); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("m4Discovery", 1, this::m4Discovery),
                new Step("receipt", 1, () -> { fixture.writeCrashReceipt(fixture.dormantNewReceipt()); return true; })))
                .started(fixture::requireFreshWorld);
    }

    /**
     * step {@code m4Discovery}：照 M4 的順序先有候選的 entropy（{@link CandidateEntropyState#loadOrCreate}，fresh 世界還沒有 M4 證據，所以建立），再以 production 的 checked 寫出寫入一份
     * 只有 {@link #M4_DISCOVERY} 的 discovery（{@link UniverseDiscoveryState#save}，同 JUnit 的寫法：以 {@code fromNbt} 建立），讀回恰為那一筆（{@code m4DiscoveryWritten}）。M4 的 discovery
     * 非空時 entropy 必須已存在（{@code candidateAware} 以 {@code mustExist} 讀它），所以 entropy 在前。
     */
    private boolean m4Discovery() throws Exception {
        var root = run.saveRoot();
        run.expect("noDiscoveryYet", !Files.exists(run.data().resolve(UniverseDiscoveryState.FILE_NAME)), () -> "fresh 世界已有 discovery");
        CandidateEntropyState.loadOrCreate(root, false);
        UniverseDiscoveryState.fromNbt(discoveryNbt(M4_DISCOVERY)).save(root);
        run.expect("m4DiscoveryWritten", () -> UniverseDiscoveryState.load(root).records().equals(List.of(M4_DISCOVERY)),
                () -> "discovery 讀回不是 M4 的那一筆");
        run.proof.put(M4_DISCOVERY_SHA256, M5CollapseDiskEvidence.sha256(run.data().resolve(UniverseDiscoveryState.FILE_NAME)));
        return true;
    }

    /** discovery 的 NBT（{@link UniverseDiscoveryState#toNbt} 的形狀，只有 {@code record} 一筆）。 */
    private static NbtCompound discoveryNbt(UniverseDiscoveryRecord record) {
        var entry = new NbtCompound();
        entry.putUuid("UniverseId", record.universeId().value());
        entry.putLong("DiscoveryOrdinal", record.discoveryOrdinal());
        entry.putLong("DiscoveredAtGameTime", record.discoveredAtGameTime());
        record.firstObserver().ifPresent(observer -> entry.putUuid("FirstObserver", observer));
        entry.putBoolean("GameplayEligible", record.gameplayEligible());
        var records = new NbtList();
        records.add(entry);
        var nbt = new NbtCompound();
        nbt.putInt("SchemaVersion", 1);
        nbt.put("Records", records);
        return nbt;
    }

    // ---- crash ----

    /**
     * {@code w11-crash}（見類別說明）：開服之前 {@link #toTheM4Save}，之後 halt 在 M5 authority 初始化裡。唯一的 step {@code initialized} 不會執行：窗口在開服時（第一個 tick 之前）就成立；
     * 沒有成立（初始化沒有在兩個 ledger 之間經過窗口）時，開服之後的第一個 tick 由它判 FAIL（{@code crashWindowReached}）。
     */
    Phase crash() {
        return Phase.halt(List.of(new Step("initialized", 1, () -> {
            throw new AssertionError("crashWindowReached：開服時的 M5 authority 初始化沒有在建立第一個 ledger 之後、建立第二個之前到達窗口；" + run.diagnostic());
        }))).beforeStart(this::toTheM4Save);
    }

    /**
     * 開服之前（{@code onInitialize}，世界還沒有開啟）把 setup 留下的存檔改成 M4 時期的形式：
     * <ol>
     * <li>讀回 setup 的 receipt 與收據（{@code readCrashReceipt}：正常停止、receipt NBT、UniverseId 與參與者）。</li>
     * <li>磁碟上的 journal 是 setup 的 M5 寫出的 schema 4、恰為這一筆收據（{@code setupJournalSchemaFour}）；記下它的 DataVersion。</li>
     * <li>兩個 ledger 都是 setup 的 M5 初始化建立的空檔（{@code setupLedgersEmpty}），移到 phase evidence 的 {@value #REMOVED_LEDGERS}（{@code m5LedgersRemovedBeforeStart}：正式檔不存在、
     *     移走的檔與原檔的 SHA-256 相同）。</li>
     * <li>journal 以 production 的寫出（{@link M5CollapseDiskEvidence#writeJournal}：全新的 {@code SessionRecoveryState} 只 put 這一筆，沒有 M5 證據，{@code writtenSchema} 是 3）換成 schema 3、
     *     同一個 DataVersion，讀回（{@code schemaThreeJournalBeforeStart}）。</li>
     * <li>discovery 恰為 M4 的那一筆、與 setup 寫入時相同（{@code m4DiscoveryBeforeStart}）；記下它的 SHA-256。</li>
     * </ol>
     * 之後依窗口表裝上 {@code FIRST_LEDGER_CREATED} 的 crash 點（開服之前就裝：窗口在 {@code SERVER_STARTED} 的 default phase，早於 probe 自己的 {@code started}）。
     */
    private void toTheM4Save() throws Exception {
        var setup = fixture.readCrashReceipt(SETUP, null);
        var receipt = fixture.receipt();
        var data = run.worldData();
        var journalFile = data.resolve(M5CollapseDiskEvidence.JOURNAL);
        // F6-2e-3（F6-2e-2 quality Minor 6）：經 expectJournalOnDisk（判斷與原本手寫的相同），DataVersion 由同一次讀取帶回（原本另讀一次檔）。
        var setupJournal = M5CollapseDiskEvidence.expectJournalOnDisk(run, data, "setupJournalSchemaFour", 4, Map.of(receipt.sessionUuid(), receipt));
        int dataVersion = setupJournal.map(M5CollapseDiskEvidence.JournalOnDisk::dataVersion).orElse(-1);
        M5CollapseRun.require(dataVersion > 0, "setup 的 journal 沒有 DataVersion");
        run.expect("setupLedgersEmpty", () -> Files.isRegularFile(data.resolve(UniverseAllocationLedger.FILE_NAME)) && Files.isRegularFile(data.resolve(ProjectionLedger.FILE_NAME))
                        && M5CollapseDiskEvidence.allocations(data).isEmpty() && M5CollapseDiskEvidence.projections(data).isEmpty(),
                () -> "setup 留下的 ledger 不是兩個空檔：" + M5CollapseDiskEvidence.summary(data.getParent(), receipt.sessionUuid()));
        var removed = run.config.evidence().resolve(REMOVED_LEDGERS);
        Files.createDirectories(removed);
        boolean moved = true;
        for (var name : List.of(UniverseAllocationLedger.FILE_NAME, ProjectionLedger.FILE_NAME)) {
            var ledger = data.resolve(name);
            var sha256 = M5CollapseDiskEvidence.sha256(ledger);
            Files.move(ledger, removed.resolve(name));
            moved &= !Files.exists(ledger) && sha256.equals(M5CollapseDiskEvidence.sha256(removed.resolve(name)));
        }
        run.expect("m5LedgersRemovedBeforeStart", moved, () -> "兩個 ledger 沒有移走：" + removed);
        M5CollapseDiskEvidence.writeJournal(journalFile, List.of(receipt), dataVersion);
        M5CollapseDiskEvidence.expectJournalOnDisk(run, data, "schemaThreeJournalBeforeStart", 3, Map.of(receipt.sessionUuid(), receipt));
        var discoverySha256 = M5CollapseDiskEvidence.sha256(data.resolve(UniverseDiscoveryState.FILE_NAME));
        run.expect("m4DiscoveryBeforeStart", () -> M5CollapseDiskEvidence.discovery(data.getParent()).equals(List.of(M4_DISCOVERY))
                        && discoverySha256.equals(M5CollapseRun.text(setup, M4_DISCOVERY_SHA256)),
                () -> "開服之前的 discovery 不是 setup 寫入的那一筆");
        run.proof.put(M4_SAVE_BEFORE_START, M5CollapseDiskEvidence.summary(data.getParent(), receipt.sessionUuid()));
        run.proof.put(DISCOVERY_SHA256_BEFORE_START, discoverySha256);
        M5CollapseWindows.arm(run, fixture, CrashWindow.FIRST_LEDGER_CREATED);
    }

    // ---- crash 點的條件與 halt 之前的取證（窗口表的 FIRST_LEDGER_CREATED 一列） ----

    /** {@code FIRST_LEDGER_CREATED} 的條件：初始化剛建立 allocation ledger（{@code CREATED}），projection ledger 還沒有建立（{@code NOT_CREATED}）。 */
    static boolean firstLedgerCreated(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.LedgerCreation creation && creation.allocation() == CollapseAuthorities.InitResult.Outcome.CREATED
                && creation.projection() == CollapseAuthorities.InitResult.Outcome.NOT_CREATED;
    }

    /**
     * {@code FIRST_LEDGER_CREATED} 的 halt 之前（開服之前就能讀的 {@link M5CollapseRun#worldData}；這時 probe 的 {@code server()} 還是 null）：磁碟上只有 allocation ledger、而且是空的
     * （{@code firstLedgerCreatedAtHalt}）；projection ledger 不存在（{@code secondLedgerAbsentAtHalt}）；journal 仍是 schema 3、恰為那一筆收據（{@code schemaThreeJournalAtHalt}）；discovery 與
     * 開服之前相同（{@code discoveryUntouchedAtHalt}）。allocation ledger 的 SHA-256 與修改時間、journal 與 discovery 的 SHA-256 記在 {@code atHalt}，之後的 phase 以它們確認沒有被重寫。
     */
    static void oneLedgerAtHalt(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var data = run.worldData();
        var allocation = data.resolve(UniverseAllocationLedger.FILE_NAME);
        run.expect("firstLedgerCreatedAtHalt", () -> Files.isRegularFile(allocation) && M5CollapseDiskEvidence.allocations(data).isEmpty(),
                () -> "halt 時 allocation ledger 不是空檔：" + M5CollapseDiskEvidence.summary(data.getParent(), fixture.sid()));
        run.expect("secondLedgerAbsentAtHalt", !Files.exists(data.resolve(ProjectionLedger.FILE_NAME)), () -> "halt 時 projection ledger 已存在");
        M5CollapseDiskEvidence.expectJournalOnDisk(run, data, "schemaThreeJournalAtHalt", 3, Map.of(fixture.sid(), fixture.receipt()));
        var discovery = M5CollapseDiskEvidence.sha256(data.resolve(UniverseDiscoveryState.FILE_NAME));
        run.expect("discoveryUntouchedAtHalt", discovery.equals(run.proof.get(DISCOVERY_SHA256_BEFORE_START)), () -> "halt 時的 discovery 與開服之前不同");
        evidence.put(ALLOCATION_SHA256, M5CollapseDiskEvidence.sha256(allocation));
        evidence.put(ALLOCATION_MODIFIED, Files.getLastModifiedTime(allocation).toString());
        evidence.put(JOURNAL_SHA256, M5CollapseDiskEvidence.sha256(data.resolve(M5CollapseDiskEvidence.JOURNAL)));
        evidence.put(DISCOVERY_SHA256, discovery);
    }

    // ---- 之後的兩次重啟共用 ----

    /**
     * 開服之前（{@code onInitialize}）：讀回 setup 的 receipt 與收據、crash 的 halt receipt（{@code readHaltReceipt}：乾淨的 halt），確認 M5 authority 初始化的輸入與 discovery 和 crash
     * 當下相同：allocation ledger 的 SHA-256 與修改時間（{@code existingLedgerKeptSinceTheCrash}）、projection ledger 不存在（{@code secondLedgerStillAbsent}）、journal 的 SHA-256 而且仍是
     * schema 3、恰為那一筆收據（{@code schemaThreeJournalBeforeStart}）、discovery 的 SHA-256（{@code m4DiscoveryKeptSinceTheCrash}）。halt receipt 的 {@code atHalt} 記在 {@link #atHalt}。
     * <p>F6-2e-3（F6-2e-2 spec review M-1）：比對的只有這幾項，不是整份磁碟。{@code quantumchamber_chambers.dat} 與 {@code raids.dat} 在 {@code w11-unreadable} 正常停服時重寫，與 crash 當下
     * 不同（三次 run 的 {@code data-hashes.json} 都是如此），所以 {@code w11-restart} 的起點是那次停服寫出的 chamber registry。不另比對它們：(1) 與 crash 當下比一定不同；(2) 與
     * {@code w11-unreadable} 停服時寫出的值比，兩個 JVM 之間沒有程序碰存檔（runner 依序跑 phase），恆成立、沒有證明力；(3) M5 authority 初始化只讀 journal 的 envelope 與兩個 ledger
     * （{@code CollapseAuthorities.initialize}），W11 的判定（{@code missingLedgerNotTreatedAsLost} 等）不依賴它們；chamber registry 由之後的 step 以 production 的路徑使用（原艙
     * 照常觸發、提交、到達 READY），內容有問題時會在那裡失敗。
     */
    private void expectDiskAsAtTheCrash() throws Exception {
        fixture.readCrashReceipt(SETUP, null);
        var receipt = fixture.receipt();
        atHalt = fixture.readHaltReceipt(CRASH, CrashWindow.FIRST_LEDGER_CREATED).getAsJsonObject("atHalt");
        var data = run.worldData();
        expectExistingLedgerKept(atHalt, "existingLedgerKeptSinceTheCrash");
        run.expect("secondLedgerStillAbsent", !Files.exists(data.resolve(ProjectionLedger.FILE_NAME)), () -> "開服之前已有 projection ledger");
        run.expect("journalKeptSinceTheCrash", M5CollapseDiskEvidence.sha256(data.resolve(M5CollapseDiskEvidence.JOURNAL)).equals(M5CollapseRun.text(atHalt, JOURNAL_SHA256)),
                () -> "開服之前的 journal 與 crash 當下不同");
        M5CollapseDiskEvidence.expectJournalOnDisk(run, data, "schemaThreeJournalBeforeStart", 3, Map.of(receipt.sessionUuid(), receipt));
        run.expect("m4DiscoveryKeptSinceTheCrash", M5CollapseDiskEvidence.sha256(data.resolve(UniverseDiscoveryState.FILE_NAME)).equals(M5CollapseRun.text(atHalt, DISCOVERY_SHA256)),
                () -> "開服之前的 discovery 與 crash 當下不同");
    }

    /** {@code name}：allocation ledger 的 SHA-256 與修改時間與 crash 的 halt 之前記下的相同（初始化照常讀取、沒有重寫它；重寫空的 ledger 時內容相同，所以另比修改時間）。 */
    private void expectExistingLedgerKept(JsonObject atHalt, String name) throws Exception {
        var allocation = run.worldData().resolve(UniverseAllocationLedger.FILE_NAME);
        var sha256 = M5CollapseDiskEvidence.sha256(allocation);
        var modified = Files.exists(allocation) ? Files.getLastModifiedTime(allocation).toString() : "ABSENT";
        run.expect(name, sha256.equals(M5CollapseRun.text(atHalt, ALLOCATION_SHA256)) && modified.equals(M5CollapseRun.text(atHalt, ALLOCATION_MODIFIED)),
                () -> "allocation ledger：SHA-256 " + sha256 + " 修改時間 " + modified + "，crash 當下是 " + M5CollapseRun.text(atHalt, ALLOCATION_SHA256) + " "
                        + M5CollapseRun.text(atHalt, ALLOCATION_MODIFIED));
    }

    /** 「M5 authority 初始化完成」／「M5 authority 初始化失敗」的 log（{@code startupLog}：{@code onInitialize} 起）。 */
    private List<String> initLog(Level level, String fragment) {
        return run.startupLog().matching(level, fragment).stream().map(LogCapture.Entry::message).toList();
    }

    // ---- w11-unreadable ----

    /**
     * {@code w11-unreadable}（見類別說明）：開服之前 {@link #expectDiskAsAtTheCrash}、裝上 allocation ledger 讀取的 IO 失敗（一次）；開服時 {@link #initializationFailedOnTheRead}；
     * 觀察 {@value #UNINITIALIZED_WATCH_TICKS} 個 tick（收據不變），正常停服；停服之後 journal 仍是 schema 3（{@code schemaThreeJournalAfterTheFailedStart}）。
     */
    Phase unreadable() {
        M5TestHooks.CheckedReadFault[] fault = {null};
        return Phase.stop(List.of(M5CollapsePassage.watch("uninitialized", UNINITIALIZED_WATCH_TICKS, tick -> {
                    var current = run.journal().flushedRecords().get(fixture.sid());
                    run.expect("receiptKeptWhileUninitialized", fixture.receipt().equals(current), () -> "第 " + tick + " 個 tick 的 record：" + M5GameTestSupport.state(run.journal(), fixture.sid()));
                })))
                .beforeStart(() -> {
                    expectDiskAsAtTheCrash();
                    fault[0] = run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseAllocationLedger.FILE_NAME, 0, 1));
                })
                .started(() -> initializationFailedOnTheRead(fault[0]))
                .afterStop(() -> M5CollapseDiskEvidence.expectJournalOnDisk(run, run.worldData(), "schemaThreeJournalAfterTheFailedStart", 3,
                        Map.of(fixture.sid(), fixture.receipt())));
    }

    /**
     * {@code w11-unreadable} 開服時（初始化之後）：
     * <ul>
     * <li>{@code existingLedgerKept}：既有的 allocation ledger 沒有被重寫（mutation「把既有的也當成缺少的、兩個都重建」失敗在這裡，所以排第一）。</li>
     * <li>{@code missingLedgerNotCreatedOnTheReadFailure}：缺少的 projection ledger 沒有被建立（初始化有任何讀取失敗時不建立；mutation「讀取失敗時仍建立」失敗在這裡）。</li>
     * <li>{@code allocationReadFailedOnce}：注入的讀取失敗恰命中一次（初始化讀既有的 allocation ledger）。</li>
     * <li>{@code initializationFailedOnTheRead}：「M5 authority 初始化失敗」的 ERROR 恰一則、是 {@code allocation=UNREADABLE，projection=NOT_CREATED}；本 instance 的 authority 沒有初始化、
     *     journal 不是 {@code m5ReadyThisInstance}。</li>
     * <li>{@code readFailureNotTreatedAsLoss}：失敗的分類是 IO（SEALED 原因 {@code AUTHORITY_UNREADABLE}），不是遺失或損壞（{@code AUTHORITY_CORRUPT}、{@code LOST}）。</li>
     * <li>{@code schemaThreeJournalAtStart}：journal 仍是 schema 3、恰為那一筆收據。</li>
     * </ul>
     */
    private void initializationFailedOnTheRead(M5TestHooks.CheckedReadFault fault) throws Exception {
        expectExistingLedgerKept(atHalt, "existingLedgerKept");
        run.expect("missingLedgerNotCreatedOnTheReadFailure", !Files.exists(run.data().resolve(ProjectionLedger.FILE_NAME)), () -> "讀取失敗時仍建立了 projection ledger");
        run.expect("allocationReadFailedOnce", fault != null && fault.hits() == 1, () -> "allocation ledger 的讀取失敗命中 " + (fault == null ? "-" : fault.hits()) + " 次");
        var failed = initLog(Level.ERROR, "M5 authority 初始化失敗");
        boolean initialized = CollapseAuthorities.get(run.server()).initialized(), ready = run.journal().m5ReadyThisInstance();
        run.expect("initializationFailedOnTheRead", failed.size() == 1 && failed.getFirst().contains("allocation=UNREADABLE，projection=NOT_CREATED") && !initialized && !ready,
                () -> "初始化的 ERROR：" + failed + " initialized=" + initialized + " m5ReadyThisInstance=" + ready);
        run.expect("readFailureNotTreatedAsLoss", failed.size() == 1 && failed.getFirst().contains("SEALED(AUTHORITY_UNREADABLE)") && !failed.getFirst().contains("LOST")
                && !failed.getFirst().contains("AUTHORITY_CORRUPT"), () -> "初始化的 ERROR：" + failed);
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalAtStart", 3, Map.of(fixture.sid(), fixture.receipt()));
    }

    // ---- w11-restart ----

    /**
     * {@code w11-restart}（見類別說明）。開服之前 {@link #expectDiskAsAtTheCrash}；開服時 {@link #recreatedWithoutLoss}；step：{@code join}（JOIN 在艙外，journal 仍是 schema 3）→
     * {@code enter} → {@code committed}（COLLAPSING 的 checked 提交，journal 在這時升為 schema 4：{@code schemaFourAtTheFirstCheckedWrite}）→ {@code M5CollapsePassage} 的 resolve、ready、
     * cross 與 (b) 終局（{@link M5CollapsePassage.End#withEarlierDiscovery}：discovery 是 M4 的那一筆之後恰多 U 一筆）。每個 tick：{@code passage.invariants}，以及 journal 正式檔只要與開服時
     * 不同就必須是 schema 4（{@code journalUpgradedOnTheFirstWrite}）。
     */
    Phase restart() {
        var steps = new ArrayList<Step>();
        steps.add(new Step("join", 1, () -> {
            fixture.joinAllInTheSourceWorld(Inside.OUTSIDE);
            M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalBeforeTheCommit", 3, Map.of(fixture.sid(), fixture.receipt()));
            return true;
        }));
        steps.add(new Step("enter", 1, () -> { fixture.moveInside(); return true; }));
        steps.add(new Step("committed", 40, this::committedAsSchemaFour));
        steps.add(passage.resolve());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> M5CollapsePassage.End.crossed(fixture).withEarlierDiscovery(List.of(M4_DISCOVERY))));
        return Phase.stop(steps)
                .beforeStart(() -> {
                    expectDiskAsAtTheCrash();
                    run.readStoppedReceipt(UNREADABLE, "unreadable");
                    journalAtStart = M5CollapseDiskEvidence.sha256(run.worldData().resolve(M5CollapseDiskEvidence.JOURNAL));
                })
                .started(this::recreatedWithoutLoss)
                .everyTick(() -> {
                    passage.invariants(null);
                    journalUpgradedOnTheFirstWrite();
                });
    }

    /**
     * {@code w11-restart} 開服時（初始化之後、第一個 tick 之前）：
     * <ul>
     * <li>{@code missingLedgerNotTreatedAsLost}（目標）：沒有「M5 authority 初始化失敗」；「M5 authority 初始化完成」恰一則、是 {@code allocation=LOADED（0 筆），projection=CREATED（0 筆），
     *     flushed journal 已是 schema 4=false}；本 instance 已初始化、journal 是 {@code m5ReadyThisInstance}。</li>
     * <li>{@code existingLedgerKept}：既有的 allocation ledger 沒有被重寫（SHA-256 與修改時間與 crash 當下相同）。</li>
     * <li>{@code missingLedgerCreated}：projection ledger 已補建、是空的。</li>
     * <li>{@code schemaThreeJournalAtStart}：journal 仍是 schema 3（初始化不寫 journal）、恰為那一筆收據。</li>
     * <li>{@code nothingAllocatedAtStart}：allocation ledger 是空的、catalog 沒有 U。</li>
     * </ul>
     */
    private void recreatedWithoutLoss() throws Exception {
        var failed = initLog(Level.ERROR, "M5 authority 初始化失敗");
        var completed = initLog(Level.INFO, "M5 authority 初始化完成");
        boolean initialized = CollapseAuthorities.get(run.server()).initialized(), ready = run.journal().m5ReadyThisInstance();
        run.expect("missingLedgerNotTreatedAsLost", failed.isEmpty() && completed.size() == 1
                        && completed.getFirst().contains("allocation=LOADED（0 筆），projection=CREATED（0 筆），flushed journal 已是 schema 4=false") && initialized && ready,
                () -> "初始化的 ERROR：" + failed + " 完成：" + completed + " initialized=" + initialized + " m5ReadyThisInstance=" + ready);
        expectExistingLedgerKept(atHalt, "existingLedgerKept");
        run.expect("missingLedgerCreated", () -> Files.isRegularFile(run.data().resolve(ProjectionLedger.FILE_NAME)) && M5CollapseDiskEvidence.projections(run.data()).isEmpty(),
                () -> "projection ledger=" + fixture.projectionsOrError());
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaThreeJournalAtStart", 3, Map.of(fixture.sid(), fixture.receipt()));
        run.expect("nothingAllocatedAtStart", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty()
                        && !UniverseRegistryState.get(run.server()).flushedRecords().containsKey(fixture.universe()) && fixture.universeWorld().isEmpty(),
                () -> "allocation=" + fixture.allocationsOrError() + " catalog=" + UniverseRegistryState.get(run.server()).flushedRecords().keySet());
        run.proof.put("startedDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), fixture.sid()));
    }

    /** 每個 tick：journal 正式檔與開服時不同（寫過）時，SchemaVersion 必須是 4（spec §12：初始化成功之後的第一次 checked 寫入升為 schema 4）。 */
    private void journalUpgradedOnTheFirstWrite() throws Exception {
        var file = run.data().resolve(M5CollapseDiskEvidence.JOURNAL);
        if (M5CollapseDiskEvidence.sha256(file).equals(journalAtStart)) return;
        int schema = M5CollapseDiskEvidence.journalSchema(run.data());
        run.expect("journalUpgradedOnTheFirstWrite", schema == 4, () -> "開服之後寫過的 journal 是 SchemaVersion=" + schema);
    }

    /**
     * step {@code committed}：COLLAPSING 已 checked 提交（{@code fixture.committed}，Destination 是推導出的 U）時，磁碟上的 journal 是 schema 4、這個 session 是 COLLAPSING
     * （{@code schemaFourAtTheFirstCheckedWrite}；{@code join} 時仍是 schema 3、恰為收據，所以這是開服之後的第一次寫入）。F6-2e-3（F6-2e-2 quality Minor 6）：經
     * {@link M5CollapseDiskEvidence#expectJournalOnDisk}，磁碟上的 record 必須與記憶體裡 flushed 的 exact 相同（{@code fixture.committed} 已確認 flushed 的這一筆是 COLLAPSING、Destination 是 U）；
     * 原本只看這一筆的 state（<b>判斷變嚴</b>）。
     */
    private boolean committedAsSchemaFour() throws Exception {
        if (!fixture.committed()) return false;
        M5CollapseDiskEvidence.expectJournalOnDisk(run, run.data(), "schemaFourAtTheFirstCheckedWrite", 4, run.journal().flushedRecords());
        return true;
    }
}
