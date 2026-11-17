package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.quantumchamber.gametest.M7HomeProbe;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 僅 testmod（M7 T4 Phase B1；B2a Step 0 自 {@link M7HomeCloseWindowMixin} 拆成獨立檔，B1 審查 M6）：單 SID 收據 {@link HomeReceiptStore#publishOrVerify}
 * 內的 cut 點，呼叫前或回傳後通知 {@link M7HomeProbe#reached}：
 * <ul>
 * <li>{@code verify-existing}：收據已存在時的 exact 驗證（第 1 個 {@code verify}；不是 cut，只記事件）。</li>
 * <li>{@code after-temp-force}：完整暫存檔 {@code FileChannel.force(true)} 之後。</li>
 * <li>{@code before-publish}／{@code after-publish}：原子不覆寫發佈 {@code Files.createLink} 之前、回傳之後。</li>
 * <li>{@code after-readback}：發佈後 exact 讀回（第 2 個 {@code verify}）之後、刪暫存檔之前。</li>
 * </ul>
 * 只通知、不改參數與結果、不吞例外；probe 沒有啟用時 {@link M7HomeProbe#reached} 不做任何事。
 * <p>注入點的數量（B1 審查 M5）：每個注入點都是 {@code require=1}；沒有 ordinal 的 {@code force}、{@code createLink} 另加 {@code allow=1}，同一個方法裡多出
 * 第二處呼叫時套用 mixin 就失敗。{@code verify} 靠 ordinal 0／1 區分「收據已存在時的 exact 驗證」與「發佈後讀回」，假設 {@code publishOrVerify} 裡
 * <b>恰有 2 次</b> {@code verify}；改 {@code publishOrVerify} 時（例如在前面插入新的 {@code verify}），ordinal 會無聲位移，必須同步檢查這兩個注入點與
 * {@code m7-home-probe.ps1} 的事件序列。
 */
@Mixin(value=HomeReceiptStore.class,remap=false)
abstract class M7HomeReceiptWindowMixin {
    @WrapOperation(method="publishOrVerify",remap=false,require=1,at=@At(value="INVOKE",ordinal=0,remap=false,
            target="Ldev/quantumchamber/persistence/HomeReceiptStore;verify(Ljava/util/Optional;Lnet/minecraft/nbt/NbtCompound;)V"))
    private void quantumchamberTest$verifyExisting(Optional<?> actual,NbtCompound expected,Operation<Void> original,
            @Local(argsOnly=true) HomeSessionRecord frozen) {
        original.call(actual,expected);
        M7HomeProbe.reached("verify-existing",frozen.sessionUuid(),null);
    }

    @WrapOperation(method="publishOrVerify",remap=false,require=1,allow=1,at=@At(value="INVOKE",remap=false,target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void quantumchamberTest$force(FileChannel channel,boolean metaData,Operation<Void> original,@Local(argsOnly=true) HomeSessionRecord frozen) {
        original.call(channel,metaData);
        M7HomeProbe.reached("after-temp-force",frozen.sessionUuid(),null);
    }

    @WrapOperation(method="publishOrVerify",remap=false,require=1,allow=1,at=@At(value="INVOKE",remap=false,
            target="Ljava/nio/file/Files;createLink(Ljava/nio/file/Path;Ljava/nio/file/Path;)Ljava/nio/file/Path;"))
    private Path quantumchamberTest$publish(Path link,Path existing,Operation<Path> original,@Local(argsOnly=true) HomeSessionRecord frozen) {
        M7HomeProbe.reached("before-publish",frozen.sessionUuid(),link.getFileName().toString());
        var published=original.call(link,existing);
        M7HomeProbe.reached("after-publish",frozen.sessionUuid(),link.getFileName().toString());
        return published;
    }

    @WrapOperation(method="publishOrVerify",remap=false,require=1,at=@At(value="INVOKE",ordinal=1,remap=false,
            target="Ldev/quantumchamber/persistence/HomeReceiptStore;verify(Ljava/util/Optional;Lnet/minecraft/nbt/NbtCompound;)V"))
    private void quantumchamberTest$readback(Optional<?> actual,NbtCompound expected,Operation<Void> original,@Local(argsOnly=true) HomeSessionRecord frozen) {
        original.call(actual,expected);
        M7HomeProbe.reached("after-readback",frozen.sessionUuid(),null);
    }
}
