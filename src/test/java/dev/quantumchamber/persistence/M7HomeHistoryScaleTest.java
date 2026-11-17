package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * T4 B2b-2（plan Task 4 GREEN :265、M7 spec §5.3「只按已知 SID exact lookup，不全目錄掃描、不設世界終身次數上限」、V10「>4096 趟仍可新 HOME、舊單檔可讀」）。
 * <p>超過 4096 個互異 SID 各自完成一次 HOME 回程終局：收據經正式發布（{@link HomeReceiptStore#publishOrVerify}），熱列經正式 checked 移除
 * （{@code terminateHome}，也就是 {@code finishHomeClose} 核過 owner proof 之後呼叫的同一個本體）。之後仍可新 admission，舊 SID 不能重用；抽前、中、後的
 * 舊單 SID 收據 exact 讀回，而且自發布後位元組不變。
 * <p>禁止全目錄掃描以 instrumentation 證明：測試用的收據 store 建在一個委派給預設檔案系統的 {@link ListingProbe} 上，記錄每一次目錄列舉，以及每一次碰到
 * 「不是這一趟的 SID」的收據檔（開檔、讀屬性、刪除、連結）。整段旅程（admission 的同名檔查詢與同卷探測、發布、移除前的 exact 核對、事後抽樣讀回）都經這個 store，
 * 列舉必須是 0、碰到別的 SID 的收據檔也必須是 0。正式的讀取入口只有一個（{@code read}），開服載入與存檔前核對用的 store 走的也是它。
 * <p>不虛增獨立的歷史計數：每一趟移除後 journal 檔都回到同一份空 schema6 的位元組（沒有累積任何計數或歷史），{@code data} 底下只有 journal 與收據目錄，
 * 收據目錄恰好是這 {@value #TRIPS} 個 SID 各一個 {@code .dat}（沒有索引、計數檔或暫存殘留）。這些數量都是事後從磁碟數出來的，不是迴圈自己的計數。
 * <p>限制（如實揭露）：JUnit 拿不到回程終局 owner 的一次性 mint／proof（{@code HomeClosure.CloseMint}、{@code HomeClosureProof} 的建構子是 private），所以
 * CLOSE 列以 journal fixture 放進熱 journal，B 門、原生 checkpoint 與 proof 的部分由 M7 GameTest 與 Windows 原生 46 列負責。
 * <p>分段執行（T4 B2b-3，I-1）：{@value #TRIPS} 趟依序分成 {@value #BLOCKS} 個測試方法（每段 {@value #PER_BLOCK} 趟），共用同一個存檔目錄、probe、store
 * 與累積狀態（{@link TestInstance.Lifecycle#PER_CLASS} 加上 static {@link TempDir}），最後一個方法做 >4096 之後的 admission、抽樣讀回與磁碟計數。
 * 拆段只為了讓 Gradle testLogging 在段與段之間印出事件列，「90 秒無輸出就判卡住」看得到進度（T4 B2b-5 起本類別從 M7 gate 拆出，在獨立的 M7 規模段
 * 執行，同樣是總時限 600 秒、idle 90 秒）；每一趟的步驟與斷言都和拆段前相同，
 * 也沒有減少趟數。每段開頭核對前面各段的趟數全部完成（{@code BLOCK_ORDER}），最後一個方法也先核對 {@value #TRIPS} 趟都完成：某一趟失敗之後，
 * 之後的各段與最後一段都以具名錯因失敗，不會在只完成一部分的狀態上通過。只跑其中一個方法同樣會以 {@code BLOCK_ORDER} 失敗。
 * <p>strict 趟抽樣（T4 B2b-4，B2b3-M1：M7 gate 的 600 秒總時限要留給後續 task）：admission（同名檔查詢與同卷探測）、CLOSE 列以正式 writer
 * （{@link SessionJournalStore#write}）落盤，以及開服 strict 載入（{@code SessionRecoveryState.load}，含預算與這一列自己的收據核對）只在「strict 趟」做：
 * 第 0 趟、各段的第一趟與最後一趟、{@code samples}（前、中、4096 邊界前後、最後一趟），以及每 {@value #STRICT_STRIDE} 趟（固定抽樣）。其餘趟的 CLOSE 列
 * 以同一份 data NBT 在記憶體 strict decode（{@code SessionRecoveryState.fromNbt}）放進熱 state，不落盤、不重讀。不抽樣的部分：4100 趟每一趟都走正式發布
 * （{@code publishOrVerify}）與正式 checked 移除（{@code terminateHome}），每一趟之後的斷言（REMOVED、熱列已清空、移除時寫出的 journal 位元組等於空 schema6、
 * 列舉 0、碰到別的 SID 0）也都照舊；最後一個方法另核 strict 趟恰好依規則全部執行，而且規則本身沒有被改小：各段首尾都是 strict 趟、恰為
 * {@value #STRICT_TRIPS} 趟（{@code STRICT_SAMPLE}；T4 B2b-5，B2b4-M1）。「熱 journal 檔先有這一列、移除後回到空」
 * 的磁碟層級證據只在 strict 趟；其餘趟由 REMOVED（移除的 checked 寫出 exact 讀回已無這一列）與寫出後的 journal 位元組負責。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M7HomeHistoryScaleTest {
    static final int TRIPS = 4100;
    static final int BLOCKS = 10;
    static final int PER_BLOCK = TRIPS / BLOCKS;
    /** strict 趟的固定抽樣間距（見類別說明）。 */
    static final int STRICT_STRIDE = 64;
    /**
     * strict 趟的固定數量（T4 B2b-5，B2b4-M1）：每 {@value #STRICT_STRIDE} 趟（0～4096）65 趟，各段首趟另加 9 趟（410…3690 都不是 64 的倍數），
     * 各段末趟另加 10 趟（409…4099），{@code samples} 另加 1 與 4095 兩趟，合計 86。規則被改小（例如 stride 調大、刪掉首尾條件）時以 {@code STRICT_SAMPLE} 失敗。
     */
    static final int STRICT_TRIPS = 86;
    /** static：PER_CLASS 底下非 static 的 {@code @TempDir} 欄位每個方法都會換一個新目錄，各段必須共用同一個存檔目錄。 */
    @TempDir static Path save;

    private ListingProbe probe;
    private HomeReceiptStore store;
    private Path journal;
    private Path receipts;
    // 抽樣：前（0、1）、中、4096 邊界前後（TRIPS=4100 時為第 4095、4096 趟，也就是第 4096、4097 個 SID）與最後一趟。
    private final SortedSet<Integer> samples = new TreeSet<>(List.of(0, 1, TRIPS / 2, TRIPS - 5, TRIPS - 4, TRIPS - 1));
    /** strict 趟（見類別說明）：在 setUp 依規則算出，執行前就固定。 */
    private final SortedSet<Integer> strictTrips = new TreeSet<>();
    /** 已經走完全部斷言的 strict 趟數。 */
    private int strictDone;
    private final Map<Integer, String> published = new HashMap<>();
    private byte[] emptyJournal;
    /** 已經走完全部斷言的趟數；一趟的每個斷言都通過之後才加一。 */
    private int completedTrips;
    private long tripNanos;

    @BeforeAll void setUp() throws Exception {
        net.minecraft.SharedConstants.createGameVersion();
        assertEquals(0, TRIPS % BLOCKS, "TRIPS 必須能均分成 BLOCKS 段");
        probe = new ListingProbe(save);
        store = new HomeReceiptStore(probe.root());
        journal = save.resolve("data/quantumchamber_sessions.dat");
        receipts = HomeReceiptStore.receiptPath(save.toRealPath(), sid(0)).getParent();
        for (int trip = 0; trip < TRIPS; trip++) {
            int offset = trip % PER_BLOCK;
            if (trip == 0 || offset == 0 || offset == PER_BLOCK - 1 || trip % STRICT_STRIDE == 0 || samples.contains(trip)) strictTrips.add(trip);
        }
    }

    private static UUID sid(int trip) { return UUID.nameUUIDFromBytes(("m7-t4b2b2-history-" + trip).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    /** 已結案（CLOSE 凍結）、全員返回、無租約的列，SID 換成 {@code sid}。 */
    private static HomeSessionRecord closed(UUID sid) {
        var row = HomeJournalCodec.encode(M7HomeHistoryTest.closed(31));
        row.putUuid("SessionUuid", sid);
        row.getCompound("Home").getCompound("Selection").getCompound("DoorKey").putUuid("SessionUuid", sid);
        return HomeJournalCodec.decode(row);
    }

    private static HomeSessionRecord arming(UUID sid) {
        var row = M7BudgetSpikeTest.home(1); row.putString("State", "ARMING"); row.putUuid("SessionUuid", sid);
        return HomeJournalCodec.decode(row);
    }

    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    // 第 1～10 段：每段 PER_BLOCK 趟，依序執行（@Order），共用同一份累積狀態；方法名稱同步列在 m7-inventory.json 的 scale.portable.junit（M7 規模段的 exact 集合）。
    @Order(1) @Test void tripsBlock01Of10() throws Exception { runBlock(0); }
    @Order(2) @Test void tripsBlock02Of10() throws Exception { runBlock(1); }
    @Order(3) @Test void tripsBlock03Of10() throws Exception { runBlock(2); }
    @Order(4) @Test void tripsBlock04Of10() throws Exception { runBlock(3); }
    @Order(5) @Test void tripsBlock05Of10() throws Exception { runBlock(4); }
    @Order(6) @Test void tripsBlock06Of10() throws Exception { runBlock(5); }
    @Order(7) @Test void tripsBlock07Of10() throws Exception { runBlock(6); }
    @Order(8) @Test void tripsBlock08Of10() throws Exception { runBlock(7); }
    @Order(9) @Test void tripsBlock09Of10() throws Exception { runBlock(8); }
    @Order(10) @Test void tripsBlock10Of10() throws Exception { runBlock(9); }

    /** 第 {@code block} 段（0 起算）：前面各段必須已全部完成，再逐趟走完整的步驟與斷言（與拆段前的單一迴圈相同）。 */
    private void runBlock(int block) throws Exception {
        assertTrue(block >= 0 && block < BLOCKS, "BLOCK_ORDER：段號超出範圍：" + block);
        int from = block * PER_BLOCK;
        assertEquals(from, completedTrips, "BLOCK_ORDER：第 " + (block + 1) + " 段開始時前面應已完成 " + from + " 趟，實際 " + completedTrips + " 趟（前面某一段失敗，或沒有依序執行）");
        long started = System.nanoTime();
        try {
            for (int trip = from; trip < from + PER_BLOCK; trip++) {
                oneTrip(trip);
                completedTrips++;
            }
        } finally {
            tripNanos += System.nanoTime() - started;
        }
    }

    private void oneTrip(int trip) throws Exception {
        var sid = sid(trip);
        var frozen = closed(sid);
        var wrapped = M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(frozen)));
        probe.allow(sid);
        boolean strict = strictTrips.contains(trip);
        SessionRecoveryState state;
        if (strict) {
            // strict 趟（見類別說明）。1. admission 側：新 SID 只查同名檔（不存在），並做同卷發布探測。
            store.requireAdmissible(sid);
            // 2. CLOSE 列以正式 writer 進熱 journal（fixture，見類別說明的限制），開服 strict 載入只核這一列自己的收據（尚未發布）。
            SessionJournalStore.write(journal, wrapped);
            state = SessionRecoveryState.load(journal);
        } else {
            // 其餘趟：同一份 data NBT 在記憶體 strict decode，CLOSE 列放進熱 state（不落盤、不重讀；落盤與開服載入由 strict 趟負責）。
            state = SessionRecoveryState.fromNbt(wrapped.getCompound("data"));
        }
        state.requireHealthy();
        assertEquals(Map.of(sid, frozen), state.allFlushedSessions(), "第 " + trip + " 趟：CLOSE 列在熱 journal");
        // 3. 正式發布收據；4. 正式 checked 移除熱列（移除前 exact 核對收據）。每一趟都做，不抽樣。
        store.publishOrVerify(frozen);
        if (samples.contains(trip)) published.put(trip, sha256(Files.readAllBytes(HomeReceiptStore.receiptPath(save, sid))));
        var removed = state.terminateHome(store, () -> state.checkedSave(journal.toFile(), null), frozen);
        assertEquals(SessionRecoveryState.TerminalResult.Status.REMOVED, removed.status(), "第 " + trip + " 趟：熱列移除：" + removed.failure());
        assertTrue(state.allSessions().isEmpty() && state.allFlushedSessions().isEmpty(), "第 " + trip + " 趟：熱列已移除");
        byte[] bytes = Files.readAllBytes(journal);
        if (emptyJournal == null) emptyJournal = bytes;
        else assertArrayEquals(emptyJournal, bytes, "HISTORY_IN_JOURNAL：第 " + trip + " 趟移除後 journal 不得累積任何歷史或計數");
        assertEquals(List.of(), probe.listings(), "RECEIPT_DIRECTORY_LISTED：第 " + trip + " 趟：收據只能按已知 SID exact lookup，不得列舉目錄");
        assertEquals(List.of(), probe.foreign(), "FOREIGN_RECEIPT_TOUCHED：第 " + trip + " 趟：只能碰這一趟 SID 自己的收據檔");
        if (strict) strictDone++;
    }

    // 最後一段：>4096 之後的 admission、舊收據 exact 讀回與磁碟計數（拆段前單一方法的後半，斷言不變）。
    @Order(BLOCKS + 1) @Test void moreThan4096DistinctHomeTripsKeepSingleFileHistoryWithoutDirectoryScan() throws Exception {
        assertEquals(TRIPS, completedTrips, "BLOCK_ORDER：" + TRIPS + " 趟必須全部完成才能核對 >4096 之後的狀態，實際只完成 " + completedTrips + " 趟");
        assertTrue(strictTrips.containsAll(samples), "STRICT_SAMPLE：strict 趟必須涵蓋全部抽樣趟：" + samples);
        assertEquals(strictTrips.size(), strictDone, "STRICT_SAMPLE：strict 趟必須依規則全部執行（" + strictTrips.size() + " 趟），實際 " + strictDone + " 趟");
        // B2b4-M1：鎖定規則本身——各段的首尾趟都必須是 strict 趟（磁碟層級的證據），strict 趟恰為 STRICT_TRIPS 趟。
        for (int block = 0; block < BLOCKS; block++) {
            int first = block * PER_BLOCK, last = first + PER_BLOCK - 1;
            assertTrue(strictTrips.contains(first) && strictTrips.contains(last), "STRICT_SAMPLE：第 " + (block + 1) + " 段的首趟 " + first + " 與末趟 " + last + " 都必須是 strict 趟");
        }
        assertEquals(STRICT_TRIPS, strictTrips.size(), "STRICT_SAMPLE：strict 趟必須恰為 " + STRICT_TRIPS + " 趟（stride " + STRICT_STRIDE + "、各段首尾與 samples），實際 " + strictTrips.size() + " 趟");
        double tripSeconds = tripNanos / 1e9;
        assertEquals(6, SessionJournalStore.read(journal).getCompound("data").getInt("SchemaVersion"));

        // 之後仍可新 admission；舊 SID（前、中、後）不能重用，而且是因為它自己的單檔歷史，不是 instance 內的狀態。
        var fresh = SessionRecoveryState.load(journal); fresh.requireHealthy(); fresh.markCollapseAuthoritiesInitialized();
        Runnable flush = () -> fresh.checkedSave(journal.toFile(), null);
        for (int trip : samples) {
            probe.allow(sid(trip));
            var reused = fresh.admitHome(store, flush, arming(sid(trip)));
            assertEquals(SessionRecoveryState.CommitResult.Status.REJECTED, reused.status(), "OLD_SID_REUSED：第 " + trip + " 趟的 SID 不得重用");
            assertTrue(FailureText.describe(reused.failure().orElseThrow()).contains("旅程識別碼已有歷史"),
                    "OLD_SID_REUSED：拒絕原因必須是該 SID 的歷史：" + FailureText.describe(reused.failure().orElseThrow()));
        }
        var next = sid(TRIPS);
        probe.allow(next);
        var admitted = fresh.admitHome(store, flush, arming(next));
        assertEquals(SessionRecoveryState.CommitResult.Status.COMMITTED, admitted.status(), "NEW_ADMISSION_REJECTED：" + TRIPS + " 趟之後必須仍可新 admission：" + admitted.failure());
        assertEquals(arming(next), fresh.allFlushedSessions().get(next));
        assertEquals(arming(next), SessionRecoveryState.load(journal).allFlushedSessions().get(next), "新 admission 已 checked 落盤");

        // 舊單 SID 收據 exact 讀回：內容等於結案快照、wrapper exact、位元組自發布後不變。
        for (int trip : samples) {
            var sid = sid(trip);
            probe.allow(sid);
            assertEquals(closed(sid), store.lookup(sid).orElseThrow(), "OLD_RECEIPT_UNREADABLE：第 " + trip + " 趟");
            assertDoesNotThrow(() -> store.requireExact(closed(sid)), "OLD_RECEIPT_NOT_EXACT：第 " + trip + " 趟");
            assertEquals(published.get(trip), sha256(Files.readAllBytes(HomeReceiptStore.receiptPath(save, sid))), "OLD_RECEIPT_CHANGED：第 " + trip + " 趟");
        }
        assertEquals(List.of(), probe.listings(), "RECEIPT_DIRECTORY_LISTED：admission 與讀回不得列舉目錄");
        assertEquals(List.of(), probe.foreign(), "FOREIGN_RECEIPT_TOUCHED：admission 與讀回只能碰指定 SID 的收據檔");

        // 不虛增獨立的歷史計數：事後從磁碟（預設檔案系統，不經 probe）數出來的檔案。
        List<String> data, names;
        try (var entries = Files.list(save.resolve("data"))) { data = entries.map(p -> p.getFileName().toString()).sorted().toList(); }
        assertEquals(List.of("quantumchamber_home_receipts", "quantumchamber_sessions.dat"), data, "EXTRA_HISTORY_FILE：data 底下不得有索引或計數檔：" + data);
        try (var entries = Files.list(receipts)) {
            names = entries.peek(p -> assertTrue(Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), "收據必須是一般檔案：" + p))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
        var expected = new TreeSet<String>();
        for (int trip = 0; trip < TRIPS; trip++) expected.add(sid(trip) + ".dat");
        assertEquals(TRIPS, expected.size(), "fixture 的 SID 必須互異");
        assertEquals(List.copyOf(expected), names, "RECEIPT_SET：收據目錄必須恰好是每個 SID 一個 .dat，沒有索引、計數檔或暫存殘留");
        System.out.printf("M7_T4B2B2_HISTORY trips=%d receipts=%d tripSeconds=%.1f perTripMillis=%.2f listings=%d foreign=%d samples=%s blocks=%d perBlock=%d strictTrips=%d strictStride=%d%n",
                TRIPS, names.size(), tripSeconds, tripSeconds * 1000 / TRIPS, probe.listings().size(), probe.foreign().size(), samples, BLOCKS, PER_BLOCK, strictDone, STRICT_STRIDE);
    }

    /**
     * 測試用 instrumentation：委派給預設檔案系統的檔案系統，只多記錄兩件事——目錄列舉（{@code newDirectoryStream}；{@code Files.list}、{@code walk}、
     * {@code find}、{@code walkFileTree} 都經它，{@code toFile().list()} 在非預設檔案系統會直接丟 {@code UnsupportedOperationException}），以及碰到收據目錄底下
     * 不在允許名單內的 {@code .dat}（開檔、讀屬性、存取檢查、刪除、連結、比對）。其他行為完全委派，不改語意。
     * <p>非預設檔案系統上 {@code HomeReceiptStore} 的 Windows reparse 檢查會略過（它只檢查預設檔案系統）；reparse 的守門由 {@code M7HomeHistoryTest} 負責。
     */
    static final class ListingProbe extends FileSystemProvider {
        private final FileSystemProvider base = FileSystems.getDefault().provider();
        private final ProbeFileSystem fileSystem = new ProbeFileSystem();
        private final Path root;
        private final Path receipts;
        private final List<String> listings = Collections.synchronizedList(new ArrayList<>());
        private final List<String> foreign = Collections.synchronizedList(new ArrayList<>());
        private volatile Set<String> allowed = Set.of();

        ListingProbe(Path save) throws IOException {
            this.root = save;
            this.receipts = HomeReceiptStore.receiptPath(save.toRealPath(), new UUID(0, 0)).getParent().toAbsolutePath().normalize();
        }
        Path root() { return wrap(root); }
        void allow(UUID sid) { allowed = Set.of(sid + ".dat"); }
        List<String> listings() { synchronized (listings) { return List.copyOf(listings); } }
        List<String> foreign() { synchronized (foreign) { return List.copyOf(foreign); } }

        Path wrap(Path path) { return path == null ? null : new ProbePath(this, path); }
        static Path unwrap(Path path) {
            if (path instanceof ProbePath probed) return probed.delegate;
            throw new ProviderMismatchException(String.valueOf(path));
        }
        private void touch(Path path) {
            var actual = unwrap(path).toAbsolutePath().normalize();
            var parent = actual.getParent();
            var name = actual.getFileName() == null ? "" : actual.getFileName().toString();
            if (parent != null && parent.equals(receipts) && name.endsWith(".dat") && !allowed.contains(name)) foreign.add(name);
        }

        @Override public String getScheme() { return "m7-listing-probe"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { throw new UnsupportedOperationException(); }
        @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            touch(path); return base.newByteChannel(unwrap(path), options, attrs);
        }
        @Override public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            touch(path); return base.newFileChannel(unwrap(path), options, attrs);
        }
        @Override public InputStream newInputStream(Path path, OpenOption... options) throws IOException { touch(path); return base.newInputStream(unwrap(path), options); }
        @Override public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException { touch(path); return base.newOutputStream(unwrap(path), options); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            listings.add(unwrap(dir).toString());
            var inner = base.newDirectoryStream(unwrap(dir), entry -> filter.accept(wrap(entry)));
            return new DirectoryStream<>() {
                @Override public Iterator<Path> iterator() {
                    var entries = inner.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return entries.hasNext(); }
                        @Override public Path next() { return wrap(entries.next()); }
                    };
                }
                @Override public void close() throws IOException { inner.close(); }
            };
        }
        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException { base.createDirectory(unwrap(dir), attrs); }
        @Override public void delete(Path path) throws IOException { touch(path); base.delete(unwrap(path)); }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { touch(source); touch(target); base.copy(unwrap(source), unwrap(target), options); }
        @Override public void move(Path source, Path target, CopyOption... options) throws IOException { touch(source); touch(target); base.move(unwrap(source), unwrap(target), options); }
        @Override public boolean isSameFile(Path a, Path b) throws IOException { touch(a); touch(b); return base.isSameFile(unwrap(a), unwrap(b)); }
        @Override public boolean isHidden(Path path) throws IOException { touch(path); return base.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return base.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { touch(path); base.checkAccess(unwrap(path), modes); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            touch(path); return base.getFileAttributeView(unwrap(path), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
            touch(path); return base.readAttributes(unwrap(path), type, options);
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
            touch(path); return base.readAttributes(unwrap(path), attributes, options);
        }
        @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
            touch(path); base.setAttribute(unwrap(path), attribute, value, options);
        }
        @Override public void createLink(Path link, Path existing) throws IOException { touch(link); touch(existing); base.createLink(unwrap(link), unwrap(existing)); }
        @Override public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException { base.createSymbolicLink(unwrap(link), unwrap(target), attrs); }
        @Override public Path readSymbolicLink(Path link) throws IOException { return wrap(base.readSymbolicLink(unwrap(link))); }

        private final class ProbeFileSystem extends FileSystem {
            private FileSystem base() { return FileSystems.getDefault(); }
            @Override public FileSystemProvider provider() { return ListingProbe.this; }
            @Override public void close() { throw new UnsupportedOperationException(); }
            @Override public boolean isOpen() { return true; }
            @Override public boolean isReadOnly() { return false; }
            @Override public String getSeparator() { return base().getSeparator(); }
            @Override public Iterable<Path> getRootDirectories() { var roots = new ArrayList<Path>(); for (var r : base().getRootDirectories()) roots.add(wrap(r)); return roots; }
            @Override public Iterable<FileStore> getFileStores() { return base().getFileStores(); }
            @Override public Set<String> supportedFileAttributeViews() { return base().supportedFileAttributeViews(); }
            @Override public Path getPath(String first, String... more) { return wrap(base().getPath(first, more)); }
            @Override public PathMatcher getPathMatcher(String syntaxAndPattern) { var matcher = base().getPathMatcher(syntaxAndPattern); return path -> matcher.matches(unwrap(path)); }
            @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return base().getUserPrincipalLookupService(); }
            @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
        }

        private static final class ProbePath implements Path {
            private final ListingProbe probe;
            private final Path delegate;
            ProbePath(ListingProbe probe, Path delegate) { this.probe = probe; this.delegate = delegate; }
            @Override public FileSystem getFileSystem() { return probe.fileSystem; }
            @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
            @Override public Path getRoot() { return probe.wrap(delegate.getRoot()); }
            @Override public Path getFileName() { return probe.wrap(delegate.getFileName()); }
            @Override public Path getParent() { return probe.wrap(delegate.getParent()); }
            @Override public int getNameCount() { return delegate.getNameCount(); }
            @Override public Path getName(int index) { return probe.wrap(delegate.getName(index)); }
            @Override public Path subpath(int beginIndex, int endIndex) { return probe.wrap(delegate.subpath(beginIndex, endIndex)); }
            @Override public boolean startsWith(Path other) { return other instanceof ProbePath probed && delegate.startsWith(probed.delegate); }
            @Override public boolean endsWith(Path other) { return other instanceof ProbePath probed && delegate.endsWith(probed.delegate); }
            @Override public Path normalize() { return probe.wrap(delegate.normalize()); }
            @Override public Path resolve(Path other) { return probe.wrap(delegate.resolve(unwrap(other))); }
            @Override public Path relativize(Path other) { return probe.wrap(delegate.relativize(unwrap(other))); }
            @Override public URI toUri() { return delegate.toUri(); }
            @Override public Path toAbsolutePath() { return probe.wrap(delegate.toAbsolutePath()); }
            @Override public Path toRealPath(LinkOption... options) throws IOException { return probe.wrap(delegate.toRealPath(options)); }
            @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) { throw new UnsupportedOperationException(); }
            @Override public int compareTo(Path other) { return delegate.compareTo(unwrap(other)); }
            @Override public boolean equals(Object other) { return other instanceof ProbePath probed && probed.probe == probe && probed.delegate.equals(delegate); }
            @Override public int hashCode() { return delegate.hashCode(); }
            @Override public String toString() { return delegate.toString(); }
        }
    }
}
