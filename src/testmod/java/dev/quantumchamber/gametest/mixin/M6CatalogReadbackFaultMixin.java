package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M6CatalogProbePhases;
import dev.quantumchamber.universe.UniverseCatalogStore;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只在 M6 catalog 測試 phase 已武裝、且真正 row 完成 atomic move 時注入一次。 */
@Mixin(value=UniverseCatalogStore.class,remap=false)
abstract class M6CatalogReadbackFaultMixin {
    @Inject(method="write(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtCompound;Ldev/quantumchamber/universe/UniverseCatalogStore$Readback;)V",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/universe/UniverseCatalogStore$Readback;read(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtCompound;"),
            remap=false,require=1)
    private static void quantumchamberTest$m6CatalogReadbackFault(Path target,NbtCompound wrapped,@Coerce Object readback,CallbackInfo callback) throws IOException {
        M6CatalogProbePhases.beforeCatalogReadback(target, wrapped);
    }
}
