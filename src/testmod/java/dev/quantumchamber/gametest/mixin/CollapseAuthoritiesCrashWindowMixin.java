package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.universe.UniverseId;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM probe 的 crash 窗口 W2、W3、W5（spec §12）。在 {@code CollapseAuthorities} 三個 checked 追加的每一個回傳點，把回傳的
 * {@code Lookup} 交給 {@link M5TestHooks#reachedCrashWindow}：
 * <ul>
 * <li>{@code lookupOrAppendAllocation} → {@code ALLOCATION_APPENDED}（W2：allocation ledger 已 checked 寫出、catalog 還沒有配置）；</li>
 * <li>{@code allocateOrVerifyCatalog} → {@code CATALOG_ALLOCATED}（W3：catalog 已 checked 寫出、還沒有物化）；</li>
 * <li>{@code appendDiscovery} → {@code DISCOVERY_APPENDED}（W5：discovery 已 checked 追加、投影還沒有開始）。</li>
 * </ul>
 * 每個回傳點都呼叫，是否是窗口（{@code APPENDED}）由 crash 點的條件判斷。hook 是 {@code M5TestHooks.Slot}，只由 {@code M5CollapseRecoveryProbe} 安裝；沒有裝時不做任何事。
 * {@code CollapseAuthorities} 是 public，所以直接指定 class，{@code require=1}：方法改名或簽名改變時開服即失敗。
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
}
