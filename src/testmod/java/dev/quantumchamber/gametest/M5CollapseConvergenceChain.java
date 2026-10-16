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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2e-3）：W12（spec §12 第 13 點、§6「checked 寫入失敗的收斂」的 roll-forward、§6.2、§6.4；交接清單 F6-2-06）。catalog 配置與 discovery 追加的「atomic move 已完成、
 * 讀回失敗」各注入一次，兩者都在同一個 JVM 裡收斂，之後在 discovery 剛收斂的時點 crash：
 * <ul>
 * <li>{@link #crash}（{@code w12-crash}，halt）：DORMANT 的 NEW 收據、原生存檔之後裝上 catalog 的讀回失敗（{@code CATALOG_READBACK_FAULT}，一次；{@code UniverseCatalogStoreReadbackFaultMixin}
 *     注入 {@code UniverseCatalogStore.write} 裡 atomic move 之後的 {@code Readback.read}）→ 參與者由艙外進入 → COLLAPSING → RESOLVE 第 2 步的 catalog 寫出讀回失敗 → roll-forward（current 保留 U、
 *     維持 dirty；磁碟上已有 U）→ SEALED(ALLOCATION_IO_FAILED)（{@code sealedOnTheUnconfirmedCatalog}）→ 觀察 {@value M5CollapsePassage#WATCH_TICKS} 個 tick 仍未確認、沒有物化
 *     （{@code catalogStillUnconfirmed}），之後退避期到由艙外進入為止的每個 tick 也是（{@link #catalogUnconfirmedInTheBackoff}，F6-3 Step 0；F6-3c-1 起另斷言 record 與封存時相同）
 *     → 退避期過後裝上 discovery 的讀回失敗
 *     （{@code CHECKED_READ_FAULT}，放行一次：RESOLVE 第 4 步自己的讀取；失敗一次：追加之後的讀回）、由艙外進入 → 重試（基底的 {@link #retried}，{@code retriedOnTheCatalog}）→
 *     catalog 收斂（同一個 U、恰多 1 筆）→ 物化 → discovery 追加的讀回失敗 → roll-forward → 再次 SEALED(ALLOCATION_IO_FAILED)（{@code sealedOnTheUnconfirmedDiscovery}：已落地的 record 記下）→
 *     第一次的退避長度時先由艙外進入一次，這次不重試（{@link #notRetriedBeforeTheDoubledBackoff}，F6-3 Step 0；這次進入之前與離開之後到再進入為止的每個 tick 也還沒有第二次重試：
 *     {@link #notRetriedInTheDoubledBackoff}，F6-3c-1）→ 加倍的退避期過後裝上 {@code DISCOVERY_CONVERGED} 的 crash 點、
 *     由艙外進入 → 重試（{@code retriedAgainAfterTheDoubledBackoff}）→ RESOLVE 第 4 步先收斂（以 current 重寫）再查到既有的 record → {@code appendDiscovery} 回傳時 halt
 *     （{@link #convergedAtHalt}：磁碟上恰為那一筆已落地的 record；重試的 INFO 共 2 則）。</li>
 * <li>restart（{@code w12-restart}，stop）：{@code M5CollapseDormantChains.restart}（同 W5：COLLAPSING、目的地的權威都已確認、門關著），窗口表另以 {@link #landedRecordKeptAtRestart}
 *     確認 discovery 仍恰為那一筆已落地的 record；之後 RESOLVE 只查到既有的 record → PASSAGE_READY → 跨越 → (b) 終局（spec §12 的四件事，discovery 恰一筆 U）。</li>
 * </ul>
 * <p>不套用 {@link #crash(CrashSetup, List, M5CollapseRun.Checked, CrashWindow, int)} 的骨架：它在 {@code committed} 之後只等窗口 halt（step {@code halt} 是 {@code () -> false}），W12 在 halt 之前
 * 要觀察兩次 roll-forward、等兩次退避、由艙外再進入三次（F6-3c-1 依 F6-3 Step 0 quality Minor 2 更正：F6-3 Step 0 起第二次封存之後多一次進入），而且 crash 點要在第二次重試之前才裝上（之前的 {@code appendDiscovery} 回傳也會呼叫這個窗口）。前三步（{@code build}、{@code registered}、
 * 收據）與骨架共用基底的 {@link #throughTheReceipt}（{@link #dormantNewSetup}：{@code beforeWrite} 與收據的建立都照骨架；F6-3 Step 0，F6-2e-3 quality Minor 3，原本只用了
 * {@code dormantNewSetup().origin()}，收據直接寫 {@code fixture.dormantNewReceipt()}）。
 * <p>halt 選在「discovery 剛收斂」而不是「catalog 仍未確認」：roll-forward 與 restore 的差別要在同一個 JVM 的下一次寫出才看得到（restore 把 current 改回舊內容，下一次收斂的重寫才把已落地的
 * append 從磁碟移除；crash 時 current 隨 JVM 消失，重啟讀到的仍是已落地的新內容），所以兩次收斂都在 crash 之前，crash 只驗「收斂之後的磁碟跨重啟不變」。「catalog 未確認時 crash」
 * 不另寫一條（F6-3 Step 0 依 F6-2e-3 spec review M-1 改寫，原本只寫「與 W3 的重啟相同」）：與 W3 等價的是 <b>catalog 與 allocation ledger 的磁碟狀態</b>（catalog 已有 U、EAGER_ENABLED，
 * allocation 恰一筆；記憶體裡的「未確認」隨 JVM 消失，開服 bootstrap 由 EAGER_ENABLED 物化）。磁碟上的 journal 不同：W3 是 COLLAPSING，這裡是 SEALED(ALLOCATION_IO_FAILED)；
 * 「重啟之後，SEALED 第一次觸發就重試」（新的 server instance 沒有退避，spec §11.2）由 {@code w9-restart}（SEALED(MATERIALIZE_FAILED)）與 {@code unreadable-restart}
 * （SEALED(AUTHORITY_UNREADABLE)）涵蓋，重試之後的 RESOLVE 就是 W3 的重啟那一段。
 * <p>F6-3 Step 0（F6-2e-3 quality Minor 2）：兩次重試都有具名斷言，「退避加倍」也有證據。第一次重試經基底的 {@link #retried}（SEALED(ALLOCATION_IO_FAILED) 的重試 INFO 恰一則，由艙外進入之後
 * 10 tick 內）；第二次封存之後，在第一次的退避長度（{@link M5CollapseRetryChains#BACKOFF_TICKS}）時先由艙外進入一次，{@value M5CollapsePassage#WATCH_TICKS} 個 tick 都不重試
 * （{@code notRetriedBeforeTheDoubledBackoff}），離開之後在 {@link #SECOND_BACKOFF_TICKS} 時再進入，10 tick 內重試（{@code retriedAgainAfterTheDoubledBackoff}）。兩次進入在第二次
 * 封存之後第 {@link M5CollapseRetryChains#BACKOFF_TICKS}（{@link CollapseBackoff#INITIAL_TICKS}＋5）與 {@link #SECOND_BACKOFF_TICKS}（2×{@link CollapseBackoff#INITIAL_TICKS}＋5）個 tick，
 * production 在進入的下一個 tick 才觀察到邊緣，所以第二次封存的延遲 d 滿足 {@code INITIAL_TICKS}＋6 < d ≤ 2×{@code INITIAL_TICKS}＋6（F6-3c-1 依 F6-3 Step 0 spec review M-1 更正：
 * 原本寫「大於第一次的退避長度、不大於 SECOND_BACKOFF_TICKS」，少算了觀察邊緣的那一個 tick）：退避沒有加倍或 {@code retrySealed} 重設退避時失敗在前者，退避變長時失敗在後者
 * （都是具名斷言，不是 stepBounded）。
 * 第二次重試的 RESOLVE 在下一個 tick 就 halt，所以 halt 之前（{@link #convergedAtHalt}）再斷言重試的 INFO 共 2 則（{@code retriedTwiceBeforeTheHalt}）。三次由艙外進入的 server tick
 * 記在 proof（{@code reenteredAfterTheBackoffTick}、{@code reenteredBeforeTheDoubledBackoffTick}、{@code reenteredAfterTheDoubledBackoffTick}，比照 {@code M5CollapseRetryChains}）。
 * 退避的長度都由 {@link CollapseBackoff#INITIAL_TICKS} 推出。
 * <p>F6-3c-1（F6-3 Step 0 quality Minor 1）：兩段退避的每個 tick 都有具名斷言。第一段（step {@code backoff}）另斷言 record 與第一次封存時相同（{@code sealedKeptInTheBackoff}）；第二段的
 * {@code backoffEarly} 與 {@code backoffAgain} 每個 tick 斷言還沒有第二次重試（{@code notRetriedInTheDoubledBackoff}）。production 改成退避一到期就重試、不等邊緣時，分別失敗在
 * 這兩個斷言；原本第二段只有 {@code notRetriedEarly} 的 40 tick，第二次重試會在 crash 點裝上之前完成，最後是 step {@code halt} 的 stepBounded。
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
    /** 收斂完成的 INFO 的片段（F6-3 Step 0，F6-2e-3 quality Minor 5：由 {@code CATALOG_CONVERGED}、{@code DISCOVERY_CONVERGED} 改名，後者與 {@link CrashWindow#DISCOVERY_CONVERGED} 同名）。 */
    private static final String CATALOG_CONVERGED_INFO = "M5 CATALOG 收斂完成", DISCOVERY_CONVERGED_INFO = "M5 DISCOVERY 收斂完成";

    private M5TestHooks.CheckedReadFault catalogFault, discoveryFault;
    /**
     * 最近一次封存之後的 record（之後觀察它沒有變：第一次封存之後的 {@code unconfirmed}；F6-3 Step 0 起也是第二次封存之後的 {@code notRetriedEarly}；F6-3c-1 起另有兩段退避的每個 tick：
     * {@code backoff}、{@code backoffEarly}、{@code backoffAgain}）。
     */
    private SessionRecoveryRecord sealedRecord;
    /** 最近一次封存的 server tick（之後等過退避期）。 */
    private int markTick = -1;
    /** 收據已寫入（之前沒有 UniverseId，不做 {@code passage.invariants}）。 */
    private boolean receiptWritten;

    M5CollapseConvergenceChain(M5CollapseRun run) {
        super(run);
    }

    /** {@code w12-crash}（見類別說明）。每個 tick（收據之後）：{@code passage.invariants}（catalog 只有 U、最多多 1 筆、Destination 與候選不變）。 */
    Phase crash() {
        // F6-3 Step 0（F6-2e-3 quality Minor 3）：build 到 receipt 經基底的 throughTheReceipt（與 crash 骨架共用；原本只用了 dormantNewSetup 的 origin、收據另寫）。
        var steps = new ArrayList<>(throughTheReceipt(dormantNewSetup(), List.of(), () -> {
            receiptWritten = true;
            // 收據的原生存檔之後才裝：之後第一次寫出 catalog 的就是 RESOLVE 第 2 步（catalog 沒有 dirty 時 save 不寫，UniverseRegistryState.save）。
            catalogFault = run.install(M5TestHooks.CATALOG_READBACK_FAULT, new M5TestHooks.CheckedReadFault(M5CollapseDiskEvidence.CATALOG, 0, 1,
                    "M5 collapse probe 受控（W12）：" + M5CollapseDiskEvidence.CATALOG + " 寫出之後的讀回失敗（atomic move 已完成）"));
        }));
        steps.add(enter());
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("sealedOnTheCatalog", 400, this::sealedOnTheUnconfirmedCatalog));
        steps.add(M5CollapsePassage.watch("unconfirmed", M5CollapsePassage.WATCH_TICKS, this::catalogStillUnconfirmed));
        steps.add(reenterAfter("backoff", M5CollapseRetryChains.BACKOFF_TICKS, this::catalogUnconfirmedInTheBackoff, this::discoveryReadbackFails,
                "reenteredAfterTheBackoffTick"));
        // F6-3 Step 0（F6-2e-3 quality Minor 2）：第一次重試經基底的 retried（原本只在 sealedOnTheDiscovery 數「SEALED 重試」恰一則）。
        steps.add(retried("retried", "retriedOnTheCatalog", SealedReason.ALLOCATION_IO_FAILED, 1, Await.EDGE));
        steps.add(new Step("sealedOnTheDiscovery", 400, this::sealedOnTheUnconfirmedDiscovery));
        // F6-3 Step 0：「退避加倍」的證據：第一次的退避長度時先進入一次，這次不重試；觀察完離開，再等到加倍的退避期過後。
        // F6-3c-1（F6-3 Step 0 quality Minor 1）：第二次封存之後到再進入為止的每個 tick（backoffEarly、backoffAgain；notRetriedEarly 另有斷言）都還沒有第二次重試。
        steps.add(reenterAfter("backoffEarly", M5CollapseRetryChains.BACKOFF_TICKS, this::notRetriedInTheDoubledBackoff, M5CollapseRun.NOTHING,
                "reenteredBeforeTheDoubledBackoffTick"));
        steps.add(M5CollapsePassage.watch("notRetriedEarly", M5CollapsePassage.WATCH_TICKS, this::notRetriedBeforeTheDoubledBackoff, fixture::moveOutside));
        steps.add(reenterAfter("backoffAgain", SECOND_BACKOFF_TICKS, this::notRetriedInTheDoubledBackoff,
                () -> M5CollapseWindows.arm(run, fixture, CrashWindow.DISCOVERY_CONVERGED), "reenteredAfterTheDoubledBackoffTick"));
        steps.add(untilTriggered("retriedAgain", "retriedAgainAfterTheDoubledBackoff", Await.EDGE, () -> retries(run, fixture.sid()) >= 2, this::retriedTwice, null));
        steps.add(new Step("halt", 400, () -> false));
        return Phase.halt(steps).started(fixture::requireFreshWorld).everyTick(() -> {
            if (receiptWritten) passage.invariants(null);
        });
    }

    /**
     * step {@code stepName}（上限 {@code ticks}＋10）：最近一次封存之後過了 {@code ticks} 個 tick 時，先 {@code before}，再由艙外進入艙內（邊緣觸發），把進入的 server tick 記在 proof 的
     * {@code proofKey}（F6-3 Step 0，比照 {@code M5CollapseRetryChains} 的 {@code reenteredAfterTheBackoffTick}）。每個 tick（含進入的那個 tick、進入之前）先以這是這個 step 的第幾個
     * tick（由 1 起，同 {@link M5CollapsePassage#watch}）呼叫 {@code eachTick}（F6-3 Step 0：{@code backoff} 的具名斷言）。F6-3c-1（F6-3 Step 0 quality Minor 4）：{@code eachTick}
     * 改為 {@link M5CollapsePassage.Watching}，與 {@code before}（{@link M5CollapseRun.Checked}）不同型別，兩者對調不能編譯，失敗訊息也拿得到 tick；step 也改由這裡以
     * {@link M5CollapsePassage#counting} 建立（原本三個呼叫端各寫一次 {@code new Step(name, ticks + 10, ...)}，名稱、上限與每個 tick 的動作都與原本相同）。
     */
    private Step reenterAfter(String stepName, int ticks, M5CollapsePassage.Watching eachTick, M5CollapseRun.Checked before, String proofKey) {
        return M5CollapsePassage.counting(stepName, ticks + 10, tick -> {
            eachTick.tick(tick);
            if (run.server().getTicks() < markTick + ticks) return false;
            before.run();
            fixture.moveInside();
            run.proof.put(proofKey, run.server().getTicks());
            return true;
        });
    }

    /**
     * step {@code backoff} 的每個 tick（到由艙外進入的那個 tick、進入之前；F6-3 Step 0，F6-2e-3 spec M-2、quality Minor 1：原本這一段約 165 tick 沒有斷言）：仍是 roll-forward 的狀態
     * （{@code catalogRolledForwardInTheBackoff}）、沒有物化（{@code notMaterializedInTheBackoff}）、record 與第一次封存時相同（{@code sealedKeptInTheBackoff}，F6-3c-1）。失敗訊息帶 step
     * 的第幾個 tick 與 server tick（F6-3c-1）。
     */
    private void catalogUnconfirmedInTheBackoff(int tick) {
        var when = "退避期的第 " + tick + " 個 tick（server tick " + run.server().getTicks() + "）：";
        expectRolledForward("catalogRolledForwardInTheBackoff", when);
        expectNotMaterialized("notMaterializedInTheBackoff", when);
        // F6-3c-1（F6-3 Step 0 quality Minor 1）：record（flushed 與 current）也與第一次封存時相同（unconfirmed 的 sealedKept 延伸到退避期）。
        var sid = fixture.sid();
        run.expect("sealedKeptInTheBackoff", sealedRecordKept(run, sid, sealedRecord),
                () -> when + "state=" + M5GameTestSupport.state(run.journal(), sid) + " 全部的重試 INFO=" + retries(run, sid));
    }

    /**
     * step {@code backoffEarly} 與 {@code backoffAgain} 的每個 tick（第二次封存之後到加倍的退避期過後由艙外進入為止，各含進入的那個 tick、進入之前；中間的 {@code notRetriedEarly} 由
     * {@link #notRetriedBeforeTheDoubledBackoff} 斷言；F6-3c-1，F6-3 Step 0 quality Minor 1）：全部的「SEALED 重試」INFO 仍只有第一次的一則、record（flushed 與 current）與第二次封存時
     * 相同（{@code notRetriedInTheDoubledBackoff}）。原本這兩段沒有逐 tick 的斷言：production 改成退避一到期就重試、不等邊緣時，第二次重試在 crash 點裝上之前就完成，
     * {@code retriedAgain} 照樣成立，最後是 step {@code halt} 的 stepBounded；現在失敗在這個具名斷言。
     */
    private void notRetriedInTheDoubledBackoff(int tick) {
        var sid = fixture.sid();
        int all = retries(run, sid), now = run.server().getTicks();
        boolean kept = sealedRecordKept(run, sid, sealedRecord);
        run.expect("notRetriedInTheDoubledBackoff", all == 1 && kept, () -> "第二次封存之後 " + (now - markTick) + " 個 tick（step 的第 " + tick + " 個 tick，server tick " + now
                + "）：全部的重試 INFO=" + all + " record 與第二次封存時相同=" + kept + " state=" + M5GameTestSupport.state(run.journal(), sid));
    }

    /**
     * step {@code notRetriedEarly} 的每個 tick（F6-3 Step 0，F6-2e-3 quality Minor 2：「退避加倍」的證據）：第二次封存之後過了第一次的退避長度時由艙外進入（{@code backoffEarly}），這次不重試：
     * 全部的「SEALED 重試」INFO 仍只有第一次的一則、record（flushed 與 current）與第二次封存時相同、參與者在艙內（邊緣確實發生；{@code notRetriedBeforeTheDoubledBackoff}）。production
     * 在退避期內消耗這次邊緣、不重試（{@code CollapseService.sealed}）。退避沒有加倍、或 {@code retrySealed} 重設了退避時，第二次封存的延遲只有 {@link CollapseBackoff#INITIAL_TICKS}，
     * 這次進入就重試，失敗在這裡。
     */
    private void notRetriedBeforeTheDoubledBackoff(int tick) {
        var sid = fixture.sid();
        int all = retries(run, sid);
        boolean kept = sealedRecordKept(run, sid, sealedRecord);
        boolean inside = fixture.players().stream().allMatch(M5CollapseFixture::inside);
        run.expect("notRetriedBeforeTheDoubledBackoff", all == 1 && kept && inside,
                () -> "第一次的退避長度時進入之後第 " + tick + " 個 tick：全部的重試 INFO=" + all + " record 與第二次封存時相同=" + kept + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + " 在艙內=" + inside);
    }

    /**
     * step {@code retriedAgain}（基底的 {@link #untilTriggered}，{@link Await#EDGE}：由艙外進入之後最多 10 tick；到時沒有重試就失敗在 {@code retriedAgainAfterTheDoubledBackoff}，
     * 退避變長時也是）：SEALED(ALLOCATION_IO_FAILED) 的重試 INFO 共 2 則、全部的重試 INFO 也是 2（F6-3 Step 0，F6-2e-3 quality Minor 2）。
     */
    private Verdict retriedTwice(int tick) {
        return twoRetries(run, fixture.sid(), "加倍的退避期過後進入之後 " + tick + " 個 tick：");
    }

    /**
     * 兩次重試都已記下：SEALED(ALLOCATION_IO_FAILED) 的重試 INFO 共 2 則、全部的重試 INFO 也是 2（catalog、discovery 各封存一次之後各重試一次）；{@code when} 接在失敗訊息的開頭。
     * step {@code retriedAgain}（{@link #retriedTwice}）與 halt 之前的 {@code retriedTwiceBeforeTheHalt}（{@link #convergedAtHalt}）共用（F6-3c-1，F6-3 Step 0 quality Minor 3：
     * 原本兩處各寫一份相同的判斷式；判斷不變，失敗訊息合成同一句）。
     */
    private static Verdict twoRetries(M5CollapseRun run, UUID sid, String when) {
        int fromReason = retriesFrom(run, sid, SealedReason.ALLOCATION_IO_FAILED), all = retries(run, sid);
        return new Verdict(fromReason == 2 && all == 2, when + "SEALED(ALLOCATION_IO_FAILED) 的重試 INFO=" + fromReason + " 全部的重試 INFO=" + all
                + "（預期 2：catalog、discovery 各封存一次之後各重試一次）");
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
        expectRolledForward("catalogRolledForward", "封存當下：");
        expectNotMaterialized("notMaterializedBeforeTheConfirmation", "封存當下：");
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
        expectRolledForward("catalogStillRolledForward", "第 " + tick + " 個 tick：");
        expectNotMaterialized("stillNotMaterialized", "第 " + tick + " 個 tick：");
        var sid = fixture.sid();
        run.expect("sealedKept", sealedRecordKept(run, sid, sealedRecord),
                () -> "第 " + tick + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), sid));
    }

    /**
     * {@code name}：catalog 的 current 只有 U、維持 dirty，flushed 沒有 U，{@code unconfirmed(CATALOG)}（spec §6 roll-forward；restore 會把 current 改回沒有 U）。{@code when} 接在失敗訊息的
     * 開頭（F6-3c-1：每個 tick 的呼叫端帶入 tick）。
     */
    private void expectRolledForward(String name, String when) {
        var catalog = UniverseRegistryState.get(run.server());
        var current = catalog.records().keySet();
        var flushed = catalog.flushedRecords().keySet();
        boolean dirty = catalog.isDirty(), unconfirmed = CollapseAuthorities.get(run.server()).unconfirmed(Authority.CATALOG);
        run.expect(name, current.equals(Set.of(fixture.universe())) && dirty && !flushed.contains(fixture.universe()) && unconfirmed,
                () -> when + "catalog current=" + current + " flushed=" + flushed + " dirty=" + dirty + " unconfirmed=" + unconfirmed);
    }

    /** {@code name}：U 沒有物化：沒有 ACTIVE 的 world、U 的 storage 目錄（{@link #universeStorage}）不存在（沒有物化的寫入）。{@code when} 同 {@link #expectRolledForward}。 */
    private void expectNotMaterialized(String name, String when) {
        var storage = universeStorage();
        boolean world = fixture.universeWorld().isPresent(), written = Files.exists(storage);
        run.expect(name, !world && !written, () -> when + "U 已物化=" + world + " storage 目錄存在=" + written + "（" + storage + "）");
    }

    /**
     * {@link #expectNotMaterialized} 的路徑的 positive control（F6-3 Step 0，F6-2e-3 quality nit）：U 已物化之後，同一個路徑（{@link #universeStorage}）以同一個 {@code Files.exists} 為真
     * （{@code name}）。vanilla 建立 world 時（{@code ServerChunkManager} 建立 world 目錄下的 {@code data}）就建出這個目錄，所以物化之後一定存在；推導錯了（例如目錄名或層數不對）時，
     * 這裡失敗，「沒有物化」的斷言就不會因為看錯路徑而恆成立。
     */
    private void expectStorageExists(String name) {
        var storage = universeStorage();
        boolean world = fixture.universeWorld().isPresent(), written = Files.exists(storage);
        run.expect(name, world && written, () -> "U 已物化=" + world + " storage 目錄存在=" + written + "（" + storage + "）");
    }

    /** U 的 storage 目錄（推導同 {@code CollapseAuthorities.universeStorage}：{@code <save>/dimensions/<namespace>/<path>} 的上一層；F6-3 Step 0 由 {@link #expectNotMaterialized} 抽出，推導不變）。 */
    private Path universeStorage() {
        var key = UniverseKeys.world(fixture.universe(), DimensionRole.OVERWORLD).getValue();
        return run.saveRoot().resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()).normalize().getParent();
    }

    /**
     * step {@code backoff} 的 {@code before}：discovery 的讀回失敗（{@code CHECKED_READ_FAULT}：放行一次、失敗一次）。重試的 RESOLVE 第 4 步先讀一次正式檔（放行），追加之後 atomic move 完成、
     * 讀回正式檔（失敗）；暫存檔的驗證不同名、不算。緊貼在重試之前才裝：之後到追加之前沒有別的 discovery 讀取（probe 在 fault 用完之前不讀 discovery；M4 的讀取在新的 session 開始時，
     * 這時原艙被封存的 session 占著）。命中的是不是讀回，由 {@link #sealedOnTheUnconfirmedDiscovery} 的原因（讀取失敗會是 AUTHORITY_UNREADABLE）與磁碟上已有 U 判斷。
     */
    private void discoveryReadbackFails() {
        discoveryFault = run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseDiscoveryState.FILE_NAME, 1, 1,
                "M5 collapse probe 受控（W12）：" + UniverseDiscoveryState.FILE_NAME + " 追加之後的讀回失敗（atomic move 已完成）"));
    }

    /**
     * step {@code sealedOnTheDiscovery}（step {@code retried} 已斷言第一次重試，F6-3 Step 0）：重試的 RESOLVE 在第 4 步封存（第二次 SEALED(ALLOCATION_IO_FAILED)）：
     * <ul>
     * <li>{@code catalogConvergedBeforeTheMaterialization}：catalog 已收斂（flushed 只有 U、EAGER_ENABLED、沒有 dirty、不是未確認；「M5 CATALOG 收斂完成」恰一則），U 已物化；catalog 恰多 1 筆
     *     （{@code catalogExactlyOneMore}）、allocation ledger 恰一筆。確認之前沒有物化的證據（F6-3 Step 0 依 F6-2e-3 spec M-2、quality Minor 1 改寫，原本寫「之前的每個 tick 都斷言沒有物化」）：
     *     封存當下（{@code notMaterializedBeforeTheConfirmation}）、{@code unconfirmed} 的 {@value M5CollapsePassage#WATCH_TICKS} 個 tick（{@code stillNotMaterialized}）與 {@code backoff} 到由艙外
     *     進入為止的每個 tick（{@code notMaterializedInTheBackoff}）。之後只隔重試的那一個 tick（step {@code retried}；F6-3 Step 0 的 GREEN 是 tick 212）不另斷言：production 每個
     *     tick 每個 session 只推進一步（{@code CollapseService.step}），那個 tick 只做 SEALED→COLLAPSING 的 checked 寫入與取得來源 ticket，不會物化；runner 掛在 production 之後，
     *     那個 tick 的狀態 probe 看得到，只是沒有另斷言。RESOLVE（先收斂、再物化）在下一個 tick（GREEN 是 tick 213），只有這個 tick 內的先後 probe 看不到：probe 只看到收斂與物化
     *     都完成之後的狀態（F6-3c-1 依 F6-3 Step 0 spec review nit 改寫，原本寫成「同一個 tick 內的先後 probe 看不到」，像是兩個 tick 都適用）。</li>
     * <li>{@code storageExistsAfterTheMaterialization}：{@link #expectStorageExists}（「沒有物化」的路徑的 positive control，F6-3 Step 0）。</li>
     * <li>{@code discoveryReadbackFailedOnce}：discovery 的注入恰命中一次（放行的那一次已用掉）。</li>
     * <li>{@code discoveryLandedOnDisk}：磁碟上的 discovery 恰為 U 一筆（atomic move 已完成；fault 已用完才讀），{@code unconfirmed(DISCOVERY)}；這一筆記為已落地的 record。</li>
     * <li>{@code sealedOnTheUnconfirmedDiscovery}：原因仍是 ALLOCATION_IO_FAILED（spec §6.4；追加之前的讀取失敗會是 AUTHORITY_UNREADABLE）。</li>
     * </ul>
     */
    private boolean sealedOnTheUnconfirmedDiscovery() throws Exception {
        // F6-3 Step 0：重試已由 step retried 斷言（重試的 INFO 在 checked 的 SEALED→COLLAPSING 之後才記，所以這裡的 SEALED 一定是第二次封存）。
        var record = sealed();
        if (record == null) return false;
        var catalog = UniverseRegistryState.get(run.server());
        var flushed = catalog.flushedRecords();
        int converged = run.capture().matching(Level.INFO, CATALOG_CONVERGED_INFO).size();
        boolean dirty = catalog.isDirty(), unconfirmed = CollapseAuthorities.get(run.server()).unconfirmed(Authority.CATALOG);
        run.expect("catalogConvergedBeforeTheMaterialization", flushed.keySet().equals(Set.of(fixture.universe())) && M5CollapseFixture.eagerEnabled(flushed.get(fixture.universe()))
                        && !dirty && !unconfirmed && converged == 1 && fixture.universeWorld().isPresent(),
                () -> "catalog flushed=" + flushed.keySet() + " dirty=" + dirty + " unconfirmed=" + unconfirmed + " 收斂的 INFO=" + converged + " U 已物化=" + fixture.universeWorld().isPresent());
        expectStorageExists("storageExistsAfterTheMaterialization");
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
        sealedRecord = record;
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
     * <li>{@code retriedTwiceBeforeTheHalt}（F6-3 Step 0，F6-2e-3 quality Minor 2）：SEALED(ALLOCATION_IO_FAILED) 的重試 INFO 共 2 則、全部的重試 INFO 也是 2（catalog、discovery 各封存一次之後
     *     各重試一次）。第二次重試的 RESOLVE 在下一個 tick 就到這個窗口，step {@code retriedAgain} 若沒有機會判定，halt 之前仍有這個計數。</li>
     * <li>{@code landedRecordKeptByTheConvergence}（目標）：spec §6.4「保留這筆 append，絕不移除」：收斂以 current 重寫之後，{@code appendDiscovery} 查到既有的 record（FOUND，不是重新追加的
     *     APPENDED），磁碟上的 discovery 恰為已落地的那一筆（ordinal、game time、firstObserver 都相同）；discovery 已確認，「M5 DISCOVERY 收斂完成」恰一則。</li>
     * <li>{@code sameUniverseBeforeTheRestart}：catalog 的 flushed 只有 U、已確認；U 已物化。</li>
     * </ul>
     * 已落地的 record 記在 {@code atHalt}（restart 以 {@link #landedRecordKeptAtRestart} 比對）。
     */
    static void convergedAtHalt(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var twice = twoRetries(run, fixture.sid(), "");
        run.expect("retriedTwiceBeforeTheHalt", twice.holds(), twice::detail);
        var status = ((CollapseAuthorities.Lookup<?>) detail).status();
        var landed = String.valueOf(run.proof.get(LANDED_DISCOVERY));
        var disk = M5CollapseDiskEvidence.discovery(run.saveRoot());
        var authorities = CollapseAuthorities.get(run.server());
        boolean pending = authorities.unconfirmed(Authority.DISCOVERY);
        int converged = run.capture().matching(Level.INFO, DISCOVERY_CONVERGED_INFO).size();
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
