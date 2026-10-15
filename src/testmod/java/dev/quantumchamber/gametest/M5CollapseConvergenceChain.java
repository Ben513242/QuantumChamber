package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseAuthorities.Authority;
import dev.quantumchamber.collapse.CollapseBackoff;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2e-3）：W12（spec §12 第 13 點、§6「checked 寫入失敗的收斂」的 roll-forward、§6.2、§6.4；交接清單 F6-2-06）。catalog 配置與 discovery 追加的「atomic move 已完成、
 * 讀回失敗」各注入一次，兩者都在同一個 JVM 裡收斂，之後在 discovery 剛收斂的時點 crash：
 * <ul>
 * <li>{@link #crash}（{@code w12-crash}，halt）：DORMANT 的 NEW 收據、原生存檔之後裝上 catalog 的讀回失敗（{@code CATALOG_READBACK_FAULT}，一次；{@code UniverseCatalogStoreReadbackFaultMixin}
 *     注入 {@code UniverseCatalogStore.write} 裡 atomic move 之後的 {@code Readback.read}）→ 參與者由艙外進入 → COLLAPSING → RESOLVE 第 2 步的 catalog 寫出讀回失敗 → roll-forward（current 保留 U、
 *     維持 dirty；磁碟上已有 U）→ SEALED(ALLOCATION_IO_FAILED)（{@code sealedOnTheUnconfirmedCatalog}）→ 觀察 {@value M5CollapsePassage#WATCH_TICKS} 個 tick 仍未確認、沒有物化
 *     （{@code catalogStillUnconfirmed}）→ 退避期過後裝上 discovery 的讀回失敗（{@code CHECKED_READ_FAULT}，放行一次：RESOLVE 第 4 步自己的讀取；失敗一次：追加之後的讀回）、由艙外進入 → 重試 →
 *     catalog 收斂（同一個 U、恰多 1 筆）→ 物化 → discovery 追加的讀回失敗 → roll-forward → 再次 SEALED(ALLOCATION_IO_FAILED)（{@code sealedOnTheUnconfirmedDiscovery}：已落地的 record 記下）→
 *     加倍的退避期過後裝上 {@code DISCOVERY_CONVERGED} 的 crash 點、由艙外進入 → 重試 → RESOLVE 第 4 步先收斂（以 current 重寫）再查到既有的 record → {@code appendDiscovery} 回傳時 halt
 *     （{@link #convergedAtHalt}：磁碟上恰為那一筆已落地的 record）。</li>
 * <li>restart（{@code w12-restart}，stop）：{@code M5CollapseDormantChains.restart}（同 W5：COLLAPSING、目的地的權威都已確認、門關著），窗口表另以 {@link #landedRecordKeptAtRestart}
 *     確認 discovery 仍恰為那一筆已落地的 record；之後 RESOLVE 只查到既有的 record → PASSAGE_READY → 跨越 → (b) 終局（spec §12 的四件事，discovery 恰一筆 U）。</li>
 * </ul>
 * <p>不套用 {@link #crash(CrashSetup, List, M5CollapseRun.Checked, CrashWindow, int)} 的骨架：它在 {@code committed} 之後只等窗口 halt（step {@code halt} 是 {@code () -> false}），W12 在 halt 之前
 * 要觀察兩次 roll-forward、等兩次退避、由艙外再進入兩次，而且 crash 點要在第二次重試之前才裝上（之前的 {@code appendDiscovery} 回傳也會呼叫這個窗口）。前三步（{@code build}、{@code registered}、
 * 收據）與骨架相同（{@link #buildAndRegister}、{@link #dormantNewSetup}）。
 * <p>halt 選在「discovery 剛收斂」而不是「catalog 仍未確認」：roll-forward 與 restore 的差別要在同一個 JVM 的下一次寫出才看得到（restore 把 current 改回舊內容，下一次收斂的重寫才把已落地的
 * append 從磁碟移除；crash 時 current 隨 JVM 消失，重啟讀到的仍是已落地的新內容），所以兩次收斂都在 crash 之前，crash 只驗「收斂之後的磁碟跨重啟不變」。catalog 未確認時 crash 的重啟
 * （磁碟上已有 U）與 W3 的重啟相同（catalog 已寫出、物化之前，開服 bootstrap 由 EAGER_ENABLED 物化），不另寫一條。
 */
final class M5CollapseConvergenceChain extends M5CollapseRestartChains {
    static final String CRASH = "w12-crash";
    static final String RESTART = "w12-restart";
    /**
     * 第二次封存之後的 per-session 退避：重試的 SEALED→COLLAPSING 不重設退避（只有 RESOLVE 完成才重設，{@code CollapseService}），所以第二次封存的延遲加倍為 2×{@link CollapseBackoff#INITIAL_TICKS}；
     * 留 5 ticks 餘裕（同 {@link M5CollapseRetryChains#BACKOFF_TICKS}）。
     */
    static final int SECOND_BACKOFF_TICKS = 2 * CollapseBackoff.INITIAL_TICKS + 5;
    /** 已落地的 discovery record（{@code UniverseDiscoveryRecord.toString}）：proof 與 halt receipt 的 {@code atHalt} 的鍵。 */
    static final String LANDED_DISCOVERY = "landedDiscovery";
    private static final String CATALOG_CONVERGED = "M5 CATALOG 收斂完成", DISCOVERY_CONVERGED = "M5 DISCOVERY 收斂完成";

    private M5TestHooks.CheckedReadFault catalogFault, discoveryFault;
    /** 第一次封存之後的 record（之後觀察它沒有變）。 */
    private SessionRecoveryRecord sealedRecord;
    /** 封存的 server tick（之後等過退避期）。 */
    private int markTick = -1;
    /** 收據已寫入（之前沒有 UniverseId，不做 {@code passage.invariants}）。 */
    private boolean receiptWritten;

    M5CollapseConvergenceChain(M5CollapseRun run) {
        super(run);
    }

    /** {@code w12-crash}（見類別說明）。每個 tick（收據之後）：{@code passage.invariants}（catalog 只有 U、最多多 1 筆、Destination 與候選不變）。 */
    Phase crash() {
        var steps = new ArrayList<>(buildAndRegister(dormantNewSetup().origin()));
        steps.add(new Step("receipt", 1, () -> {
            fixture.writeCrashReceipt(fixture.dormantNewReceipt());
            receiptWritten = true;
            // 收據的原生存檔之後才裝：之後第一次寫出 catalog 的就是 RESOLVE 第 2 步（catalog 沒有 dirty 時 save 不寫，UniverseRegistryState.save）。
            catalogFault = run.install(M5TestHooks.CATALOG_READBACK_FAULT, new M5TestHooks.CheckedReadFault(M5CollapseDiskEvidence.CATALOG, 0, 1));
            return true;
        }));
        steps.add(enter());
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("sealedOnTheCatalog", 400, this::sealedOnTheUnconfirmedCatalog));
        steps.add(M5CollapsePassage.watch("unconfirmed", M5CollapsePassage.WATCH_TICKS, this::catalogStillUnconfirmed));
        steps.add(new Step("backoff", M5CollapseRetryChains.BACKOFF_TICKS + 10, () -> reenterAfter(M5CollapseRetryChains.BACKOFF_TICKS, this::discoveryReadbackFails)));
        steps.add(new Step("sealedOnTheDiscovery", 400, this::sealedOnTheUnconfirmedDiscovery));
        steps.add(new Step("backoffAgain", SECOND_BACKOFF_TICKS + 10,
                () -> reenterAfter(SECOND_BACKOFF_TICKS, () -> M5CollapseWindows.arm(run, fixture, CrashWindow.DISCOVERY_CONVERGED))));
        steps.add(new Step("halt", 400, () -> false));
        return Phase.halt(steps).started(fixture::requireFreshWorld).everyTick(() -> {
            if (receiptWritten) passage.invariants(null);
        });
    }

    /** 封存之後過了 {@code ticks} 個 tick（per-session、catalog 與 discovery 收斂的退避都已過）：先 {@code before}，再由艙外進入艙內（邊緣觸發）。 */
    private boolean reenterAfter(int ticks, M5CollapseRun.Checked before) throws Exception {
        if (run.server().getTicks() < markTick + ticks) return false;
        before.run();
        fixture.moveInside();
        return true;
    }

    /**
     * step {@code sealedOnTheCatalog}：RESOLVE 第 2 步的 catalog 寫出讀回失敗之後，SEALED(ALLOCATION_IO_FAILED)（spec §6.2「之後進 SEALED(ALLOCATION_IO_FAILED)」）：
     * <ul>
     * <li>{@code catalogReadbackFailedOnce}：注入恰命中一次（之前沒有別的 catalog 寫出）。</li>
     * <li>{@code catalogLandedOnDisk}：磁碟上的 catalog 已是 U 一筆、EAGER_ENABLED（atomic move 已完成）。</li>
     * <li>{@code catalogRolledForward}（目標）：spec §6 的 roll-forward：current 保留 U、維持 dirty，flushed 沒有 U（讀回沒有確認），{@code unconfirmed(CATALOG)}。</li>
     * <li>{@code notMaterializedBeforeTheConfirmation}（目標）：確認之前不物化（{@link #notMaterialized}）。</li>
     * <li>{@code sealedOnTheUnconfirmedCatalog}：SEALED 的原因是 ALLOCATION_IO_FAILED；allocation ledger 恰一筆 U（{@code oneAllocationForTheToken}）；discovery 沒有 U。</li>
     * </ul>
     * 之後參與者離開艙內（重試要等由艙外再進入）。
     */
    private boolean sealedOnTheUnconfirmedCatalog() throws Exception {
        var record = sealed();
        if (record == null) return false;
        run.expect("catalogReadbackFailedOnce", catalogFault.hits() == 1, () -> "catalog 的讀回失敗命中 " + catalogFault.hits() + " 次");
        run.expect("catalogLandedOnDisk", () -> {
            var disk = M5CollapseDiskEvidence.catalog(run.data());
            return disk.keySet().equals(Set.of(fixture.universe())) && M5CollapseFixture.eagerEnabled(disk.get(fixture.universe()));
        }, () -> "磁碟上的 catalog 不是 U 一筆");
        expectRolledForward("catalogRolledForward");
        expectNotMaterialized("notMaterializedBeforeTheConfirmation");
        fixture.expectOneAllocation();
        boolean discovered = discovered();
        run.expect("sealedOnTheUnconfirmedCatalog", record.collapse().sealedReason().equals(Optional.of(SealedReason.ALLOCATION_IO_FAILED)) && !discovered,
                () -> "SEALED 的原因=" + record.collapse().sealedReason() + " discovery 有 U=" + discovered);
        sealedRecord = record;
        markTick = run.server().getTicks();
        fixture.moveOutside();
        return true;
    }

    /** step {@code unconfirmed} 的每個 tick：仍是 roll-forward 的狀態（{@code catalogStillRolledForward}）、沒有物化（{@code stillNotMaterialized}）、record 與封存時相同（{@code sealedKept}）。 */
    private void catalogStillUnconfirmed(int tick) throws Exception {
        expectRolledForward("catalogStillRolledForward");
        expectNotMaterialized("stillNotMaterialized");
        var sid = fixture.sid();
        run.expect("sealedKept", sealedRecord.equals(run.journal().flushedRecords().get(sid)) && sealedRecord.equals(run.journal().records().get(sid)),
                () -> "第 " + tick + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), sid));
    }

    /** {@code name}：catalog 的 current 只有 U、維持 dirty，flushed 沒有 U，{@code unconfirmed(CATALOG)}（spec §6 roll-forward；restore 會把 current 改回沒有 U）。 */
    private void expectRolledForward(String name) {
        var catalog = UniverseRegistryState.get(run.server());
        var current = catalog.records().keySet();
        var flushed = catalog.flushedRecords().keySet();
        boolean dirty = catalog.isDirty(), unconfirmed = CollapseAuthorities.get(run.server()).unconfirmed(Authority.CATALOG);
        run.expect(name, current.equals(Set.of(fixture.universe())) && dirty && !flushed.contains(fixture.universe()) && unconfirmed,
                () -> "catalog current=" + current + " flushed=" + flushed + " dirty=" + dirty + " unconfirmed=" + unconfirmed);
    }

    /** {@code name}：U 沒有物化：沒有 ACTIVE 的 world、U 的 storage 目錄不存在（沒有物化的寫入；目錄的推導同 {@code CollapseAuthorities.universeStorage}）。 */
    private void expectNotMaterialized(String name) {
        var key = UniverseKeys.world(fixture.universe(), DimensionRole.OVERWORLD).getValue();
        var storage = run.saveRoot().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()).normalize().getParent();
        boolean world = fixture.universeWorld().isPresent(), written = Files.exists(storage);
        run.expect(name, !world && !written, () -> "U 已物化=" + world + " storage 目錄存在=" + written + "（" + storage + "）");
    }

    /**
     * step {@code backoff} 的 {@code before}：discovery 的讀回失敗（{@code CHECKED_READ_FAULT}：放行一次、失敗一次）。重試的 RESOLVE 第 4 步先讀一次正式檔（放行），追加之後 atomic move 完成、
     * 讀回正式檔（失敗）；暫存檔的驗證不同名、不算。緊貼在重試之前才裝：之後到追加之前沒有別的 discovery 讀取（probe 在 fault 用完之前不讀 discovery；M4 的讀取在新的 session 開始時，
     * 這時原艙被封存的 session 占著）。命中的是不是讀回，由 {@link #sealedOnTheUnconfirmedDiscovery} 的原因（讀取失敗會是 AUTHORITY_UNREADABLE）與磁碟上已有 U 判斷。
     */
    private void discoveryReadbackFails() {
        discoveryFault = run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseDiscoveryState.FILE_NAME, 1, 1));
    }

    /**
     * step {@code sealedOnTheDiscovery}：重試的 RESOLVE 在第 4 步封存（第二次 SEALED(ALLOCATION_IO_FAILED)）：
     * <ul>
     * <li>{@code retriedOnTheCatalog}：「SEALED 重試」恰一則。</li>
     * <li>{@code catalogConvergedBeforeTheMaterialization}：catalog 已收斂（flushed 只有 U、EAGER_ENABLED、沒有 dirty、不是未確認；「M5 CATALOG 收斂完成」恰一則），U 已物化（確認之後才物化：
     *     之前的每個 tick 都斷言沒有物化）；catalog 恰多 1 筆（{@code catalogExactlyOneMore}）、allocation ledger 恰一筆。</li>
     * <li>{@code discoveryReadbackFailedOnce}：discovery 的注入恰命中一次（放行的那一次已用掉）。</li>
     * <li>{@code discoveryLandedOnDisk}：磁碟上的 discovery 恰為 U 一筆（atomic move 已完成；fault 已用完才讀），{@code unconfirmed(DISCOVERY)}；這一筆記為已落地的 record。</li>
     * <li>{@code sealedOnTheUnconfirmedDiscovery}：原因仍是 ALLOCATION_IO_FAILED（spec §6.4；追加之前的讀取失敗會是 AUTHORITY_UNREADABLE）。</li>
     * </ul>
     */
    private boolean sealedOnTheUnconfirmedDiscovery() throws Exception {
        var sid = fixture.sid();
        var record = sealed();
        int retries = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
        if (record == null || retries == 0) return false;
        run.expect("retriedOnTheCatalog", retries == 1, () -> "「SEALED 重試」=" + retries);
        var catalog = UniverseRegistryState.get(run.server());
        var flushed = catalog.flushedRecords();
        int converged = run.capture().matching(Level.INFO, CATALOG_CONVERGED).size();
        boolean dirty = catalog.isDirty(), unconfirmed = CollapseAuthorities.get(run.server()).unconfirmed(Authority.CATALOG);
        run.expect("catalogConvergedBeforeTheMaterialization", flushed.keySet().equals(Set.of(fixture.universe())) && M5CollapseFixture.eagerEnabled(flushed.get(fixture.universe()))
                        && !dirty && !unconfirmed && converged == 1 && fixture.universeWorld().isPresent(),
                () -> "catalog flushed=" + flushed.keySet() + " dirty=" + dirty + " unconfirmed=" + unconfirmed + " 收斂的 INFO=" + converged + " U 已物化=" + fixture.universeWorld().isPresent());
        fixture.expectCatalog("catalogExactlyOneMore");
        fixture.expectOneAllocation();
        run.expect("discoveryReadbackFailedOnce", discoveryFault.hits() == 1, () -> "discovery 的讀回失敗命中 " + discoveryFault.hits() + " 次");
        var disk = M5CollapseDiskEvidence.discovery(run.saveRoot());
        boolean pending = CollapseAuthorities.get(run.server()).unconfirmed(Authority.DISCOVERY);
        run.expect("discoveryLandedOnDisk", disk.size() == 1 && disk.getFirst().universeId().equals(fixture.universe()) && disk.getFirst().gameplayEligible() && pending,
                () -> "discovery=" + disk + " unconfirmed=" + pending);
        run.expect("sealedOnTheUnconfirmedDiscovery", record.collapse().sealedReason().equals(Optional.of(SealedReason.ALLOCATION_IO_FAILED)),
                () -> "SEALED 的原因=" + record.collapse().sealedReason());
        run.proof.put(LANDED_DISCOVERY, disk.getFirst().toString());
        markTick = run.server().getTicks();
        fixture.moveOutside();
        return true;
    }

    /** 這個 session 的 flushed record 是 SEALED 時回傳它；否則 null。 */
    private SessionRecoveryRecord sealed() {
        var record = run.journal().flushedRecords().get(fixture.sid());
        return record != null && record.state() == SessionState.SEALED ? record : null;
    }

    /** discovery 已有 U。 */
    private boolean discovered() throws Exception {
        return M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().anyMatch(entry -> entry.universeId().equals(fixture.universe()));
    }

    // ---- crash 點的條件與 halt 之前的取證（窗口表的 DISCOVERY_CONVERGED 一列） ----

    /**
     * {@code DISCOVERY_CONVERGED} 的條件：{@code appendDiscovery} 為 U 回傳成功（FOUND 或 APPENDED）。crash 點在第二次重試之前才裝上，所以這是收斂之後的那一次；APPENDED（收斂把已落地的
     * record 移除、又重新追加）也成立，由 {@link #convergedAtHalt} 判 FAIL。
     */
    static boolean discoveryConverged(M5CollapseFixture fixture, Object detail) {
        return detail instanceof CollapseAuthorities.Lookup<?> lookup && (lookup.status() == CollapseAuthorities.Lookup.Status.FOUND
                || lookup.status() == CollapseAuthorities.Lookup.Status.APPENDED)
                && lookup.value().filter(value -> value instanceof UniverseDiscoveryRecord record && record.universeId().equals(fixture.universe())).isPresent();
    }

    /**
     * {@code DISCOVERY_CONVERGED} 的 halt 之前：
     * <ul>
     * <li>{@code landedRecordKeptByTheConvergence}（目標）：spec §6.4「保留這筆 append，絕不移除」：收斂以 current 重寫之後，{@code appendDiscovery} 查到既有的 record（FOUND，不是重新追加的
     *     APPENDED），磁碟上的 discovery 恰為已落地的那一筆（ordinal、game time、firstObserver 都相同）；discovery 已確認，「M5 DISCOVERY 收斂完成」恰一則。</li>
     * <li>{@code sameUniverseBeforeTheRestart}：catalog 的 flushed 只有 U、已確認；U 已物化。</li>
     * </ul>
     * 已落地的 record 記在 {@code atHalt}（restart 以 {@link #landedRecordKeptAtRestart} 比對）。
     */
    static void convergedAtHalt(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var status = ((CollapseAuthorities.Lookup<?>) detail).status();
        var landed = String.valueOf(run.proof.get(LANDED_DISCOVERY));
        var disk = M5CollapseDiskEvidence.discovery(run.saveRoot());
        var authorities = CollapseAuthorities.get(run.server());
        boolean pending = authorities.unconfirmed(Authority.DISCOVERY);
        int converged = run.capture().matching(Level.INFO, DISCOVERY_CONVERGED).size();
        run.expect("landedRecordKeptByTheConvergence", status == CollapseAuthorities.Lookup.Status.FOUND && disk.size() == 1 && disk.getFirst().toString().equals(landed)
                        && !pending && converged == 1,
                () -> "appendDiscovery=" + status + " discovery=" + disk + "（已落地的是 " + landed + "）unconfirmed=" + pending + " 收斂的 INFO=" + converged);
        var flushed = UniverseRegistryState.get(run.server()).flushedRecords().keySet();
        run.expect("sameUniverseBeforeTheRestart", flushed.equals(Set.of(fixture.universe())) && !authorities.unconfirmed(Authority.CATALOG) && fixture.universeWorld().isPresent(),
                () -> "catalog flushed=" + flushed + " U 已物化=" + fixture.universeWorld().isPresent());
        evidence.put(LANDED_DISCOVERY, landed);
    }

    /** restart 開服時（窗口表的 {@code withDisk}）：磁碟上的 discovery 仍恰為 crash 之前已落地的那一筆（{@code landedRecordKeptAcrossTheRestart}：沒有被移除、沒有重複）。 */
    static void landedRecordKeptAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var landed = M5CollapseRun.text(fixture.readHaltReceipt(CRASH, CrashWindow.DISCOVERY_CONVERGED).getAsJsonObject("atHalt"), LANDED_DISCOVERY);
        List<UniverseDiscoveryRecord> disk = M5CollapseDiskEvidence.discovery(run.saveRoot());
        run.expect("landedRecordKeptAcrossTheRestart", disk.size() == 1 && disk.getFirst().toString().equals(landed),
                () -> "開服時的 discovery=" + disk + "（crash 之前已落地的是 " + landed + "）");
    }
}
