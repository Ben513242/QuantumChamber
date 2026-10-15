package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.UniverseId;
import java.util.UUID;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM probe 的 crash 窗口 W2、W3、W5、W6（spec §12）。在 {@code CollapseAuthorities} 四個 checked 寫入的每一個回傳點，把回傳值交給
 * {@link M5TestHooks#reachedCrashWindow}：
 * <ul>
 * <li>{@code lookupOrAppendAllocation} → {@code ALLOCATION_APPENDED}（W2：allocation ledger 已 checked 寫出、catalog 還沒有配置）；</li>
 * <li>{@code allocateOrVerifyCatalog} → {@code CATALOG_ALLOCATED}（W3：catalog 已 checked 寫出、還沒有物化）；</li>
 * <li>{@code appendDiscovery} → {@code DISCOVERY_APPENDED}（W5：discovery 已 checked 追加、投影還沒有開始）；</li>
 * <li>{@code writeProjection} → {@code PROJECTION_COMPLETE}（W6：projection ledger 已 checked 寫入 COMPLETE、PASSAGE_READY 還沒有提交；回傳的
 *     {@code WriteResult}，PENDING 的寫入也會呼叫）。</li>
 * </ul>
 * F6-2d-1 加的：{@code changeCatalogAvailability} → {@code CATALOG_DISABLED}（W9 的變體 (b)：§8.3 把 catalog 的這筆改為 DISABLED 已 checked 寫出、SEALED 還沒有寫入；回傳的
 * {@code Lookup}，兩個方向（改為 DISABLED、改回 EAGER_ENABLED）與任何結果都呼叫）。
 * 每個回傳點都呼叫，是否是窗口（{@code APPENDED}、寫入的是 COMPLETE、改成的是 DISABLED）由 crash 點的條件判斷。hook 是 {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝（{@code M5CollapseRun.armCrash} 經 {@code M5CollapseRun.install}；F6-2c Step 0 更正，F6-2a 拆檔起不在 {@code M5CollapseRecoveryProbe}）；沒有裝時不做任何事。
 * {@code CollapseAuthorities} 是 public，所以直接指定 class，{@code require=1}：方法改名或簽名改變時開服即失敗。
 * <p>F6-2e-2 加的：開服時的 M5 authority 初始化（private 的 {@code examine}）裡第二次 {@code createIfAbsent}（projection ledger）的呼叫之前 → {@code FIRST_LEDGER_CREATED}（W11：第一個
 * ledger 已建立、第二個還沒有）。detail 是那時兩個 ledger 的結果（MixinExtras 的 {@code @Local}，依宣告順序：allocation、projection）；{@code ordinal=1} 的 INVOKE 與兩個 local 找不到時
 * 套用 mixin 就失敗（{@code require=1}）。前面的讀取或建立失敗時不會到這裡（第二次呼叫在 {@code failures.isEmpty()} 之內）。
 */
@Mixin(value=CollapseAuthorities.class,remap=false)
abstract class CollapseAuthoritiesCrashWindowMixin {
    @Inject(method="lookupOrAppendAllocation(Ldev/quantumchamber/candidate/NewUniverseIntent;)Ldev/quantumchamber/collapse/CollapseAuthorities$Lookup;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$allocationWindow(NewUniverseIntent intent,
            CallbackInfoReturnable<CollapseAuthorities.Lookup<?>> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.ALLOCATION_APPENDED,callback.getReturnValue());
    }

    @Inject(method="allocateOrVerifyCatalog(Ldev/quantumchamber/universe/UniverseId;Ldev/quantumchamber/collapse/CollapseAuthorities$AvailabilityPolicy;)Ldev/quantumchamber/collapse/CollapseAuthorities$Lookup;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$catalogWindow(UniverseId universeId,CollapseAuthorities.AvailabilityPolicy availability,
            CallbackInfoReturnable<CollapseAuthorities.Lookup<?>> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.CATALOG_ALLOCATED,callback.getReturnValue());
    }

    @Inject(method="appendDiscovery(Ldev/quantumchamber/universe/UniverseId;JLjava/util/UUID;)Ldev/quantumchamber/collapse/CollapseAuthorities$Lookup;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$discoveryWindow(UniverseId universeId,long discoveredAtGameTime,UUID selectedBy,
            CallbackInfoReturnable<CollapseAuthorities.Lookup<?>> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.DISCOVERY_APPENDED,callback.getReturnValue());
    }

    @Inject(method="writeProjection(Ldev/quantumchamber/collapse/ProjectionLedger$Entry;)Ldev/quantumchamber/collapse/CollapseAuthorities$WriteResult;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$projectionWindow(ProjectionLedger.Entry entry,CallbackInfoReturnable<CollapseAuthorities.WriteResult> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.PROJECTION_COMPLETE,callback.getReturnValue());
    }

    @Inject(method="examine(Ljava/util/function/Supplier;)Ldev/quantumchamber/collapse/CollapseAuthorities$InitResult;",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/collapse/CollapseAuthorities;createIfAbsent(Ldev/quantumchamber/collapse/CollapseAuthorities$Slot;Ldev/quantumchamber/collapse/CollapseAuthorities$InitResult$Outcome;Ljava/util/List;)Ldev/quantumchamber/collapse/CollapseAuthorities$InitResult$Outcome;",
                    ordinal=1),
            remap=false,require=1)
    private void quantumchamberTest$firstLedgerWindow(Supplier<SessionRecoveryState> journal,CallbackInfoReturnable<CollapseAuthorities.InitResult> callback,
            @Local(ordinal=0) CollapseAuthorities.InitResult.Outcome allocation,@Local(ordinal=1) CollapseAuthorities.InitResult.Outcome projection) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.FIRST_LEDGER_CREATED,new M5TestHooks.LedgerCreation(allocation,projection));
    }

    @Inject(method="changeCatalogAvailability(Ldev/quantumchamber/universe/UniverseId;Ldev/quantumchamber/universe/DesiredAvailability;Ldev/quantumchamber/collapse/CollapseAuthorities$WriteDirection;)Ldev/quantumchamber/collapse/CollapseAuthorities$Lookup;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$catalogDisabledWindow(UniverseId universeId,DesiredAvailability availability,CollapseAuthorities.WriteDirection direction,
            CallbackInfoReturnable<CollapseAuthorities.Lookup<?>> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.CATALOG_DISABLED,callback.getReturnValue());
    }
}
