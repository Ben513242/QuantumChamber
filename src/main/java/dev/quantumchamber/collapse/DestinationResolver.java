package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.collapse.CollapseAuthorities.Authority;
import dev.quantumchamber.collapse.CollapseAuthorities.AvailabilityPolicy;
import dev.quantumchamber.collapse.CollapseAuthorities.Lookup;
import dev.quantumchamber.collapse.CollapseAuthorities.WriteDirection;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * spec §8 的目的地解析（RESOLVE，F4a）：COLLAPSING、{@code Destination=UNIVERSE}、全員已返還、沒有 lease 的 record 在 PREPARE 之前確定目的地。
 * 不需要供電、不看 PREPARE 的閘門（§7.3）；只在 server thread 使用。每一步都是 idempotent，重啟之後以同一個流程重新確認（§7.3「目的地已確定」）。
 * <ul>
 * <li><b>NEW</b>（§8.2，每一步之前先 {@link CollapseAuthorities#convergeIfNeeded} 該權威）：
 *     <ol>
 *     <li>allocation ledger 的 lookup-or-create（§6.1）。entry 的 UniverseId 必須等於 {@code Destination}（提交時寫入的推導值，§7.2），不符為
 *         {@code AUTHORITY_CORRUPT}。</li>
 *     <li>catalog 的配置或驗證（§6.2）：catalog 有未確認的寫入時 {@link ResolveResult.Wait}，不 SEALED。</li>
 *     <li>經 {@link UniverseLifecycleService#materializeAllocated} 物化（§6.3）。失敗依 §8.3 以 checked
 *         {@link CollapseAuthorities#changeCatalogAvailability} 改為 {@code DISABLED}（roll-forward：寫出失敗時 current 保留 {@code DISABLED} 並維持
 *         dirty，記 ERROR），再進 {@code MATERIALIZE_FAILED}。</li>
 *     <li>discovery 追加或驗證（§6.4），game time 取 overworld 的 game time（與提交 COLLAPSING 時的 {@code CommittedAtGameTime} 同一個時鐘）。</li>
 *     </ol>
 *     catalog 中這個 UniverseId（本 session 的推導值，第 1 步已比對）是 {@code DISABLED} 時，依 spec §6 的收斂規則表等同 §8.3 已完成，與最近一次
 *     封存的原因無關（F4a fix round 1，I-1）：本 server instance 還沒用掉這個 UniverseId 的物化額度時走 §8.3 的重試——先 checked 改回
 *     {@code EAGER_ENABLED}（restore：寫出失敗時 current 改回 {@code DISABLED}、維持 dirty、不物化，{@code MATERIALIZE_FAILED}），再物化，仍失敗就再改回
 *     {@code DISABLED}；已經用掉時直接 {@code MATERIALIZE_FAILED}（不寫、不物化），等下次重啟。絕不判為 {@code AUTHORITY_CORRUPT}。物化的額度記在
 *     {@link #materializeSpent}；每個 server instance 恰好一個 resolver（{@link #forServer}），所以額度每個 instance 一份（F4a fix round 2，m-1）。
 *     lifecycle 對失敗過的 descriptor 的 {@code FAILED_BEFORE} 是同一件事的後盾。</li>
 * <li><b>EXISTING</b>（§8.1）：discovery 有這個 UniverseId 而且 {@code gameplayEligible}；flushed catalog 有這筆、是 OVERWORLD 而且
 *     {@code EAGER_ENABLED}（{@link CollapseAuthorities#catalogRecordMatches}）；{@link UniverseLifecycleService#resolveActive} 回傳 exact ACTIVE world；
 *     來源 role 是 OVERWORLD。任一不符為 {@code EXISTING_INELIGIBLE}。catalog 或 discovery 有未確認的寫入時 {@link ResolveResult.Wait}
 *     （F2c 裁示 3：未確認期間不做新決策，不判 {@code EXISTING_INELIGIBLE}），而且兩者都確認之前不從磁碟讀 discovery（F4a fix round 2，m-3）；
 *     lifecycle 沒有 ready 的 context 時也是 Wait（m-4）。discovery 讀取失敗依 §6 分類為 AUTHORITY 原因。只重驗、不寫任何權威。</li>
 * </ul>
 * catalog 的讀寫一律經 {@link CollapseAuthorities}（F4a fix round 2，m-2）：寫出失敗與收斂沿用 catalog 以權威為單位的退避、WARN 與 INFO。
 * 權威的失敗結果直接採用它帶的 SEALED 原因（F2b、F2c 的交接）：遺失、損壞、既有紀錄不符、orphan storage 為 {@code AUTHORITY_CORRUPT}，讀檔的 IO 例外為
 * {@code AUTHORITY_UNREADABLE}，寫入或讀回失敗（含未確認）為 {@code ALLOCATION_IO_FAILED}。M5 authority 初始化失敗時，第一個查詢就回初始化的失敗分類。
 * 這裡只記 §8.3 的 ERROR（帶 {@link CollapseService#LOG_MARKER} 與 {@code session=<UUID>}）；SEALED 與等待的 log 由 {@link CollapseService} 依 session 去重。
 */
public final class DestinationResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    /** 每個 server instance 的 resolver（F4a fix round 2，m-1）；{@link #release} 在 {@code SERVER_STOPPED} 移除。 */
    private static final Map<MinecraftServer, DestinationResolver> INSTANCES = Collections.synchronizedMap(new IdentityHashMap<>());

    /** lifecycle 的 port（plan 決策 9 的作法）：production 委派 {@link UniverseLifecycleService}，JUnit 注入。只在 server thread 呼叫。 */
    interface Lifecycle {
        /** {@link UniverseLifecycleService#materializeAllocated} 的結果。 */
        Materialization materialize(UniverseId id);

        /**
         * 這個 UniverseId 的 world 是否 exact ACTIVE（{@link UniverseLifecycleService#resolveActive}）；lifecycle 沒有 ready 的 context 時是
         * {@link Activity#NOT_READY}（{@link UniverseLifecycleService#ready}），與「world 不是 ACTIVE」分開（F4a fix round 2，m-4）。
         */
        Activity active(UniverseId id);
    }

    /** {@link Lifecycle#active} 的結果：exact ACTIVE、不是 ACTIVE（EXISTING_INELIGIBLE），或 lifecycle 沒有 ready 的 context（等待，不 SEALED）。 */
    enum Activity { ACTIVE, NOT_ACTIVE, NOT_READY }

    /** 物化的結果：成功（本次物化或已經 ACTIVE）、lifecycle 沒有 ready 的 context（等待，不 SEALED），或失敗（§8.3）。 */
    record Materialization(Kind kind, String detail) {
        enum Kind { ACTIVE, NOT_READY, FAILED }

        Materialization {
            Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(detail, "detail");
        }
    }

    private final CollapseAuthorities authorities;
    private final Lifecycle lifecycle;
    private final LongSupplier gameTime;
    /**
     * 本 server instance 已用掉物化額度的 UniverseId（F4a fix round 1，I-1）：本 instance 對它物化失敗過，或已經做過一次 §8.3 的重試（改回
     * {@code EAGER_ENABLED}）。只在記憶體：每個 server instance 恰好一個 resolver（{@link #forServer}），重啟之後是新的 resolver、額度是空的，所以
     * 「重啟之後、每個 instance 一次」只看這裡，不看最近一次封存的原因，也不依賴呼叫端是否快取 resolver（F4a fix round 2，m-1）。
     */
    private final Set<UniverseId> materializeSpent = new HashSet<>();

    /** package-private：JUnit 以自己的權威（catalog 經權威自己的 port）與 lifecycle port 建立。 */
    DestinationResolver(CollapseAuthorities authorities, Lifecycle lifecycle, LongSupplier gameTime) {
        this.authorities = Objects.requireNonNull(authorities, "authorities");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gameTime = Objects.requireNonNull(gameTime, "gameTime");
    }

    /**
     * 本 server instance 的 resolver：{@link CollapseAuthorities#get}、{@link UniverseLifecycleService}，overworld 的 game time。每個 server instance
     * 恰好一個（以 server 為 key 保存，與 {@link CollapseAuthorities#get} 相同；F4a fix round 2，m-1）：任何呼叫端、任何時候再取一次都拿到同一個
     * resolver 與同一份物化額度，不依賴呼叫端的快取。{@code SERVER_STOPPED} 時由 {@link #release} 移除（{@code CollapseService.initialize} 註冊）；
     * 單人遊戲在同一個 JVM 重開世界是新的 server instance，拿到新的 resolver。只能在 {@link CollapseAuthorities} 的 instance 存在期間
     * （{@code SERVER_STARTED} 之後、{@code SERVER_STOPPED} 之前）於 server thread 呼叫，否則 {@link CollapseAuthorities#get} 丟例外。
     */
    public static DestinationResolver forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        var authorities = CollapseAuthorities.get(server);
        return INSTANCES.computeIfAbsent(server, owner -> new DestinationResolver(authorities, new ServerLifecycle(owner),
                () -> owner.getOverworld().getTime()));
    }

    /** {@code SERVER_STOPPED}：移除這個 server instance 的 resolver（{@code CollapseService.initialize} 註冊）。 */
    static void release(MinecraftServer server) {
        INSTANCES.remove(server);
    }

    /**
     * 解析 {@code view} 的目的地。
     * @param view COLLAPSING、{@code Destination=UNIVERSE}、全員已返還、沒有 lease 的 collapse view（spec §7.3 的 RESOLVE）；不符時丟
     *     {@link IllegalArgumentException}
     */
    public ResolveResult resolve(SessionRecoveryRecord view) {
        Objects.requireNonNull(view, "view");
        if (view.state() != SessionState.COLLAPSING || !(view.collapse().destination() instanceof CollapseDestination.Universe universe)) {
            throw new IllegalArgumentException("RESOLVE 只處理 Destination=UNIVERSE 的 COLLAPSING：" + view.state());
        }
        if (!view.spaceLeases().isEmpty() || !view.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)) {
            throw new IllegalArgumentException("RESOLVE 在全員返還、lease 清空之後才開始（spec §7.3）");
        }
        return switch (view.selectedCandidate().orElseThrow()) {
            case QuantumCandidate.New created -> resolveNew(view, universe.id(), created.intent());
            case QuantumCandidate.Existing existing -> resolveExisting(view, universe.id());
            case QuantumCandidate.Source ignored -> throw new IllegalArgumentException("SOURCE 沒有 RESOLVE");
        };
    }

    // ---- NEW（§8.2） ----

    private ResolveResult resolveNew(SessionRecoveryRecord view, UniverseId id, NewUniverseIntent intent) {
        // 1. allocation ledger（§6.1）。
        authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
        var allocation = authorities.lookupOrAppendAllocation(intent);
        if (!allocation.succeeded()) return failed(allocation);
        var entry = allocation.value().orElseThrow();
        if (!entry.universeId().equals(id)) {
            return ResolveResult.sealed(SealedReason.AUTHORITY_CORRUPT, new IllegalStateException("Destination 的 UniverseId " + id.value()
                    + " 與 allocation entry 的 " + entry.universeId().value() + " 不符（spec §6.1：合法的 entry 一定等於推導值）"));
        }
        // 2. catalog（§6.2）：未確認時等待，不 SEALED。這個 UniverseId 是本 session 的推導值（第 1 步已比對），DISABLED 也接受（§8.3 已完成）。
        authorities.convergeIfNeeded(Authority.CATALOG);
        var verified = authorities.allocateOrVerifyCatalog(id, AvailabilityPolicy.MATERIALIZE_RETRY);
        if (verified.status() == Lookup.Status.WAIT) return ResolveResult.waiting("catalog 有未確認的寫入（§6.2）");
        if (!verified.succeeded()) return failed(verified);
        if (verified.value().orElseThrow().desiredAvailability() == DesiredAvailability.DISABLED) {
            // §6 收斂規則表：DISABLED 等同 §8.3 已完成，與最近一次封存的原因無關。本 instance 已用掉這個 UniverseId 的物化額度時等下次重啟；
            // 否則走 §8.3 的重試：先 checked 改回 EAGER_ENABLED，寫出失敗屬 restore。
            if (materializeSpent.contains(id)) {
                return ResolveResult.sealed(SealedReason.MATERIALIZE_FAILED, new IllegalStateException("catalog 的這筆是 DISABLED（§8.3 已完成），"
                        + "本 server instance 已用掉這個宇宙的物化額度（已物化失敗，或已重試過一次）：不寫、不物化，等下次重啟再重試"));
            }
            var refused = reenable(id);
            if (refused != null) return refused;
        }
        // 3. 物化（§6.3）。
        var materialized = lifecycle.materialize(id);
        switch (materialized.kind()) {
            case ACTIVE -> { }
            case NOT_READY -> { return ResolveResult.waiting("lifecycle 沒有 ready 的 context：" + materialized.detail()); }
            case FAILED -> {
                materializeSpent.add(id);
                return materializeFailed(view, id, materialized.detail());
            }
        }
        // 4. discovery（§6.4）。
        authorities.convergeIfNeeded(Authority.DISCOVERY);
        var discovered = authorities.appendDiscovery(id, gameTime.getAsLong(), selectedBy(view));
        if (!discovered.succeeded()) return failed(discovered);
        return ResolveResult.resolved();
    }

    /**
     * §8.3 的重試：經 {@link CollapseAuthorities#changeCatalogAvailability}（restore）把 {@code DISABLED} checked 改回 {@code EAGER_ENABLED}。成功回 null。
     * catalog 有未確認的寫入時等待（還沒用掉額度）；寫出或讀回失敗時屬 §6 的 restore——current 改回 {@code DISABLED}（較保守的一方），維持 dirty，
     * 下一次寫出（收斂或原生 autosave）以它覆寫磁碟——不物化，回 {@code MATERIALIZE_FAILED}。
     * <p>額度在呼叫之前就記下，只有 {@code WAIT}（沒有嘗試寫出）時才收回（F4b-1 Step 0，F4a fix round 2 spec S2-mA）：嘗試過寫出（成功、失敗，或寫出之後
     * 呼叫鏈上任何一處丟出例外而沒有回到這裡）都算用掉本 instance 的額度，同一個 instance 內不會再改回 {@code EAGER_ENABLED} 第二次。
     */
    private ResolveResult reenable(UniverseId id) {
        materializeSpent.add(id);
        var reenabled = authorities.changeCatalogAvailability(id, DesiredAvailability.EAGER_ENABLED, WriteDirection.RESTORE);
        if (reenabled.status() == Lookup.Status.WAIT) {
            materializeSpent.remove(id);
            return ResolveResult.waiting("catalog 有未確認的寫入（§8.3 的重試改回 EAGER_ENABLED 之前）");
        }
        if (reenabled.succeeded()) return null;
        if (reenabled.status() != Lookup.Status.WRITE_FAILED) return failed(reenabled);
        return ResolveResult.sealed(SealedReason.MATERIALIZE_FAILED, new IllegalStateException(
                "§8.3 重試時改回 EAGER_ENABLED 的 catalog 寫出未確認：current 已改回 DISABLED（restore），不物化", reenabled.failure().orElseThrow()));
    }

    /**
     * §8.3：物化失敗。經 {@link CollapseAuthorities#changeCatalogAvailability}（roll-forward）checked 把 catalog 的這筆改為 {@code DISABLED}（避免下次
     * 開服時 bootstrap 因這筆停服）；寫出或讀回失敗（{@code WRITE_FAILED}）時 current 保留 {@code DISABLED} 與 dirty，之後的寫出帶著它，記 ERROR。
     * 這兩種都進 {@code MATERIALIZE_FAILED}。其他失敗（初始化失敗、catalog 不健康、變更本身失敗）比照 {@link #reenable}，直接採用權威結果帶的原因與
     * root cause（{@code AUTHORITY_CORRUPT}／{@code AUTHORITY_UNREADABLE}），不當成寫出未確認（F4b-1 Step 0，F4a fix round 2 quality Q2-m2）。
     */
    private ResolveResult materializeFailed(SessionRecoveryRecord view, UniverseId id, String detail) {
        var disabled = authorities.changeCatalogAvailability(id, DesiredAvailability.DISABLED, WriteDirection.ROLL_FORWARD);
        if (!disabled.succeeded()) {
            if (disabled.status() != Lookup.Status.WRITE_FAILED) return failed(disabled);
            var failure = disabled.failure().orElseThrow();
            LOGGER.error(CollapseService.LOG_MARKER, "物化失敗之後，把 catalog 改為 DISABLED 的寫出未確認（roll-forward：current 保留 DISABLED 並維持 dirty，"
                    + "由收斂或原生 autosave 寫出；確認之前重啟，開服仍可能因這筆停止，需要管理員留意）：session={} universe={}：{}",
                    view.sessionUuid(), id.value(), FailureText.describe(failure), failure);
        }
        return ResolveResult.sealed(SealedReason.MATERIALIZE_FAILED, new IllegalStateException("物化失敗，catalog 已改為 DISABLED：" + detail));
    }

    // ---- EXISTING（§8.1） ----

    private ResolveResult resolveExisting(SessionRecoveryRecord view, UniverseId id) {
        authorities.convergeIfNeeded(Authority.DISCOVERY);
        authorities.convergeIfNeeded(Authority.CATALOG);
        // F4a fix round 2（m-3）：catalog 與 discovery 都已確認之後才從磁碟讀 discovery。catalog 的查詢只看記憶體。
        var catalogRecord = authorities.catalogRecord(id);
        if (catalogRecord.status() == Lookup.Status.WAIT) return ResolveResult.waiting("catalog 有未確認的寫入（EXISTING 不做新決策）");
        if (authorities.unconfirmed(Authority.DISCOVERY)) return ResolveResult.waiting("discovery 有未確認的寫入（EXISTING 不做新決策）");
        var discovered = authorities.discovery(id);
        if (discovered.status() == Lookup.Status.WAIT) return ResolveResult.waiting("discovery 有未確認的寫入（EXISTING 不做新決策）");
        if (!discovered.succeeded()) return failed(discovered);
        if (discovered.status() == Lookup.Status.ABSENT) return ineligible("discovery 沒有這個 UniverseId", id);
        if (!discovered.value().orElseThrow().gameplayEligible()) return ineligible("discovery record 的 gameplayEligible 為 false", id);
        if (!catalogRecord.succeeded()) return failed(catalogRecord);
        if (catalogRecord.status() == Lookup.Status.ABSENT) return ineligible("flushed catalog 沒有這筆", id);
        var record = catalogRecord.value().orElseThrow();
        if (!CollapseAuthorities.catalogRecordMatches(record, id, AvailabilityPolicy.EAGER_ONLY)) {
            return ineligible("catalog 這筆不是 EAGER_ENABLED 的 OVERWORLD（" + record.desiredAvailability() + "）", id);
        }
        switch (lifecycle.active(id)) {
            case ACTIVE -> { }
            case NOT_ACTIVE -> { return ineligible("lifecycle 沒有 exact ACTIVE world", id); }
            case NOT_READY -> { return ResolveResult.waiting("lifecycle 沒有 ready 的 context（EXISTING 不做新決策）"); }
        }
        if (view.candidateContext().orElseThrow().sourceFamilyRef().role() != DimensionRole.OVERWORLD) return ineligible("來源 role 不是 OVERWORLD", id);
        return ResolveResult.resolved();
    }

    private static ResolveResult ineligible(String why, UniverseId id) {
        return ResolveResult.sealed(SealedReason.EXISTING_INELIGIBLE, new IllegalStateException("EXISTING 重驗不通過（" + why + "）：universe=" + id.value()));
    }

    // ---- 共用 ----

    /** 權威的失敗結果直接採用它帶的 SEALED 原因與 root cause；{@code WAIT} 是等待。 */
    private static ResolveResult failed(Lookup<?> lookup) {
        if (lookup.status() == Lookup.Status.WAIT) return ResolveResult.waiting("權威有未確認的寫入");
        return ResolveResult.sealed(lookup.sealedReason().orElseThrow(), lookup.failure().orElseThrow());
    }

    private static UUID selectedBy(SessionRecoveryRecord view) {
        if (!(view.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected selected)) {
            throw new IllegalArgumentException("塌縮狀態必須是 SELECTED");
        }
        return selected.selectedBy();
    }

    /** production 的 lifecycle port。 */
    private record ServerLifecycle(MinecraftServer server) implements Lifecycle {
        @Override public Materialization materialize(UniverseId id) {
            var outcome = UniverseLifecycleService.materializeAllocated(server, id);
            var detail = outcome.status() + (outcome.backendStatus().map(status -> " backend=" + status).orElse(""))
                    + (outcome.detail().isEmpty() ? "" : "：" + outcome.detail());
            return switch (outcome.status()) {
                case MATERIALIZED, ALREADY_ACTIVE -> new Materialization(Materialization.Kind.ACTIVE, detail);
                case NOT_READY -> new Materialization(Materialization.Kind.NOT_READY, detail);
                case NOT_ELIGIBLE, FAILED_BEFORE, FAILED -> new Materialization(Materialization.Kind.FAILED, detail);
            };
        }

        @Override public Activity active(UniverseId id) {
            if (!UniverseLifecycleService.ready(server)) return Activity.NOT_READY;
            return UniverseLifecycleService.resolveActive(server, id).isPresent() ? Activity.ACTIVE : Activity.NOT_ACTIVE;
        }
    }
}
