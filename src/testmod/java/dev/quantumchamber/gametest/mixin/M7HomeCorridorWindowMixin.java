package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M7HomeProbe;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.MeasuredWorldSaveCheckpoint;
import dev.quantumchamber.persistence.SessionRecoveryState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M7 T5-5）：原生 corridor 族的 cut 點（設計稿 7.2）。只通知 {@link M7HomeProbe#corridorReached}，不改參數與結果、不吞例外；probe 沒有啟用時不做任何事。
 * <ul>
 * <li>{@code journal-readback}：{@code SessionRecoveryState.save} 內正式檔已原子替換、strict 讀回之前（{@code JournalReadback.read}，save 內恰一處；
 *     與 {@code SessionJournalWindowFaultMixin} 同一注入點）。只當 {@code corridor-select-unconfirmed} 的 cut，不錄成事件。</li>
 * <li>{@code home-checkpoint-head}／{@code home-checkpoint-return}：{@code MeasuredWorldSaveCheckpoint.save} 的 HOME overload 的 HEAD 與正常 RETURN（只有一個 return）。
 *     舊列的 overload 由 {@code MeasuredCheckpointFaultMixin} 以完整 descriptor 注入，這裡也以完整 descriptor 區分。</li>
 * </ul>
 * 每個注入點都是 {@code require=1}、{@code allow=1}：正式程式改名、刪除或多出第二處時，套用 mixin 就失敗。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class M7HomeCorridorWindowMixin {
    @Inject(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",remap=false,require=1,allow=1,
            at=@At(value="INVOKE",remap=false,target="Ldev/quantumchamber/persistence/SessionRecoveryState$JournalReadback;read(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtCompound;"))
    private void quantumchamberTest$corridorReadback(java.io.File file,net.minecraft.registry.RegistryWrapper.WrapperLookup lookup,CallbackInfo callback) {
        M7HomeProbe.corridorReached("journal-readback",null,file.toPath());
    }
}

/**
 * 同檔的第二個 mixin（目標類不同，一個 mixin 類只能有一個目標；以同檔的 package-private 頂層類宣告，編譯成獨立 class，mixins.json 以類名列出）：
 * {@code MeasuredWorldSaveCheckpoint} 的 HOME overload（static）的 HEAD 與正常 RETURN。
 */
@Mixin(value=MeasuredWorldSaveCheckpoint.class,remap=false)
abstract class M7HomeCorridorCheckpointMixin {
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/HomeSessionRecord;)V",at=@At("HEAD"),remap=false,require=1,allow=1)
    private static void quantumchamberTest$head(MinecraftServer server,HomeSessionRecord row,CallbackInfo callback) {
        M7HomeProbe.corridorReached("home-checkpoint-head",row.sessionUuid(),row);
    }
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/HomeSessionRecord;)V",at=@At("RETURN"),remap=false,require=1,allow=1)
    private static void quantumchamberTest$return(MinecraftServer server,HomeSessionRecord row,CallbackInfo callback) {
        M7HomeProbe.corridorReached("home-checkpoint-return",row.sessionUuid(),row);
    }
}
