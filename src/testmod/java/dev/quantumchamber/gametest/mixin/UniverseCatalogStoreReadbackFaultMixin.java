package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.universe.UniverseCatalogStore;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-2e-3，W12）：catalog 寫出的「atomic move 已完成、讀回失敗」注入。catalog 的讀回走 {@code UniverseCatalogStore.write(Path, NbtCompound, Readback)} 自己的 package-private
 * {@code Readback} port（production 是 {@code UniverseCatalogStore::read}，{@code UniverseRegistryState.save} 經它），不經 {@code CheckedNbtFile}，所以 {@code CheckedNbtFileReadFaultMixin} 注入不到。
 * 這裡注入 {@code write} 裡 {@code Readback.read} 的呼叫之前（atomic move 之後），呼叫 {@link M5TestHooks#beforeCatalogReadback}，符合條件時丟 {@link IOException}：正式檔已是新內容、讀回失敗，
 * {@code UniverseRegistryState.save} 保留 current 與 dirty。不放在 {@code read} 的開頭：開服載入 catalog 也經過它。{@code Readback} 是 package-private，handler 以 {@code @Coerce Object} 收它。
 * hook 是 {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝（{@code M5CollapseRun.install}）；沒有裝時不做任何事。{@code require=1}：方法或 INVOKE 找不到時套用 mixin 就失敗。
 */
@Mixin(value=UniverseCatalogStore.class,remap=false)
abstract class UniverseCatalogStoreReadbackFaultMixin {
    @Inject(method="write(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtCompound;Ldev/quantumchamber/universe/UniverseCatalogStore$Readback;)V",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/universe/UniverseCatalogStore$Readback;read(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtCompound;"),
            remap=false,require=1)
    private static void quantumchamberTest$readbackFault(Path target,NbtCompound wrapped,@Coerce Object readback,CallbackInfo callback) throws IOException {
        M5TestHooks.beforeCatalogReadback(target);
    }
}
