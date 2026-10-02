package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.CheckedNbtFile;
import java.io.IOException;
import java.nio.file.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F4a）：checked NBT 檔（M5 的兩個 ledger 與 M4 的 discovery）讀取時的 IO 失敗注入。{@code CollapseAuthorities} 的正式 IO port
 * 是 {@code CheckedNbtFile::readFile}：初始化的載入、discovery 的讀取、寫入前的暫存檔驗證與 atomic move 之後的讀回都經過它（M4 的 discovery
 * 讀取也是）。這裡在 {@code readFile} 的開頭呼叫 {@link M5TestHooks#beforeCheckedRead}，符合條件時丟 {@link IOException}：對正式檔注入，在寫入時
 * 就是「atomic move 完成、讀回失敗」，在讀取時就是讀檔的 IO 例外。hook 是 {@code M5TestHooks.Slot}，只由
 * {@code M5GameTestSupport.installCheckedReadFault} 安裝；沒有裝時不做任何事。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=CheckedNbtFile.class,remap=false)
abstract class CheckedNbtFileReadFaultMixin {
    @Inject(method="readFile(Ljava/nio/file/Path;)[B",at=@At("HEAD"),remap=false,require=1)
    private static void quantumchamberTest$fault(Path path,CallbackInfoReturnable<byte[]> callback) throws IOException {
        M5TestHooks.beforeCheckedRead(path);
    }
}
