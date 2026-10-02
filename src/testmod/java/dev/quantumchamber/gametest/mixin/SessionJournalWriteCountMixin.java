package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.io.File;
import net.minecraft.registry.RegistryWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（F4a-0，F3d quality re-review m-1）：統計 journal 正式檔的每一次實際寫出。{@code SessionRecoveryState.save(File,…)} 是 journal
 * 唯一的寫出點：checked flush（{@code flush} → {@code save}）與原生存檔（autosave、{@code server.save}、停服，都經
 * {@code PersistentStateManager.save} → {@code save}）都走這裡；不 dirty 時 {@code save} 提早返回、不寫，所以掛在呼叫
 * {@code SessionJournalStore.write} 的那一點，只算真的寫出。來源與歸屬由 {@link M5TestHooks#beforeJournalWrite} 判定；沒有進行中的稽核時
 * 不做任何事，也不會丟出例外（丟出的話那次寫出會被當成落盤失敗）。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class SessionJournalWriteCountMixin {
    @Inject(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/persistence/SessionJournalStore;write(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtCompound;)V"),
            remap=false,require=1)
    private void quantumchamberTest$countWrite(File file,RegistryWrapper.WrapperLookup lookup,CallbackInfo callback) {
        M5TestHooks.beforeJournalWrite((SessionRecoveryState)(Object)this);
    }
}
