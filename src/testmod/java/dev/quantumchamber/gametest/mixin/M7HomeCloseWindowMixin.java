package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.HomeClosure;
import dev.quantumchamber.gametest.M7HomeProbe;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 僅 testmod（M7 T4 Phase B1）：Windows 原生 close 矩陣的 cut 點（M7 spec §5.3 :172 的交接順序）。在回程終局 owner {@link HomeClosure} 的
 * {@code latch} 與 {@code converge} 內，以 {@code @WrapOperation} 包住每一個交接呼叫，呼叫前、回傳後各通知 {@link M7HomeProbe#reached} 一次：
 * <ul>
 * <li>{@code before-close}／{@code after-close}：{@code SessionRecoveryState.latchHomeClose}（after 只在 {@code committed()} 時）。</li>
 * <li>{@code before-door}／{@code after-door}：第一次 {@code ChamberDoorService.applyTarget}（after 只在 {@code succeeded()} 時，detail 是 outcome）。</li>
 * <li>{@code before-checkpoint}／{@code after-checkpoint}：{@code ChunkSaveCheckpoint.save}（before 的 detail 是這次的世界與 chunks；after 只在正常回傳時）。</li>
 * <li>{@code after-recheck}：第二次 {@code applyTarget} 回傳 {@code UNCHANGED} 時（其他 outcome 記 {@code recheck-changed}）。</li>
 * <li>{@code before-remove}／{@code after-remove}：{@code SessionRecoveryState.finishHomeClose}（after 只在 {@code removed()} 時，否則記 {@code remove-rejected}）。</li>
 * </ul>
 * 收據內部的 cut 在同檔的 {@link M7HomeReceiptWindowMixin}。只通知、不改參數與結果、不吞例外；probe 沒有啟用（沒有 {@code quantumchamber.m7home.*}）時
 * {@link M7HomeProbe#reached} 不做任何事。每個注入點 {@code require=1}：正式 owner 的呼叫改名、刪除或簽名改變時，套用 mixin 就失敗。
 */
@Mixin(value=HomeClosure.class,remap=false)
abstract class M7HomeCloseWindowMixin {
    @WrapOperation(method="latch",remap=false,require=1,at=@At(value="INVOKE",remap=false,
            target="Ldev/quantumchamber/persistence/SessionRecoveryState;latchHomeClose(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/collapse/HomeClosure$CloseMint;)Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;"))
    private static SessionRecoveryState.CommitResult quantumchamberTest$close(SessionRecoveryState journal,MinecraftServer server,HomeClosure.CloseMint mint,
            Operation<SessionRecoveryState.CommitResult> original) {
        var sid=mint==null ? null : mint.expected().sessionUuid();
        M7HomeProbe.reached("before-close",sid,null);
        var result=original.call(journal,server,mint);
        if(result.committed()) M7HomeProbe.reached("after-close",sid,null);
        else M7HomeProbe.reached("close-not-committed",sid,result.status());
        return result;
    }

    @WrapOperation(method="converge",remap=false,require=1,at=@At(value="INVOKE",ordinal=0,remap=false,
            target="Ldev/quantumchamber/chamber/ChamberDoorService;applyTarget(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;Ldev/quantumchamber/chamber/ChamberDoorService$DoorState;)Ldev/quantumchamber/chamber/ChamberDoorService$ApplyResult;"))
    private static ChamberDoorService.ApplyResult quantumchamberTest$door(ChamberDoorService doors,ServerWorld world,ChamberFrame frame,ChamberDoorService.DoorState target,
            Operation<ChamberDoorService.ApplyResult> original,@Local(argsOnly=true) HomeSessionRecord row) {
        M7HomeProbe.reached("before-door",row.sessionUuid(),target);
        var result=original.call(doors,world,frame,target);
        M7HomeProbe.reached(result.succeeded() ? "after-door" : "door-not-applied",row.sessionUuid(),result.outcome());
        return result;
    }

    @WrapOperation(method="converge",remap=false,require=1,at=@At(value="INVOKE",remap=false,
            target="Ldev/quantumchamber/persistence/ChunkSaveCheckpoint;save(Lnet/minecraft/server/MinecraftServer;Ljava/util/Map;)V"))
    private static void quantumchamberTest$checkpoint(MinecraftServer server,Map<ServerWorld,Set<ChunkPos>> relevant,Operation<Void> original,
            @Local(argsOnly=true) HomeSessionRecord row) {
        M7HomeProbe.reached("before-checkpoint",row.sessionUuid(),relevant);
        original.call(server,relevant);
        M7HomeProbe.reached("after-checkpoint",row.sessionUuid(),null);
    }

    @WrapOperation(method="converge",remap=false,require=1,at=@At(value="INVOKE",ordinal=1,remap=false,
            target="Ldev/quantumchamber/chamber/ChamberDoorService;applyTarget(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;Ldev/quantumchamber/chamber/ChamberDoorService$DoorState;)Ldev/quantumchamber/chamber/ChamberDoorService$ApplyResult;"))
    private static ChamberDoorService.ApplyResult quantumchamberTest$recheck(ChamberDoorService doors,ServerWorld world,ChamberFrame frame,ChamberDoorService.DoorState target,
            Operation<ChamberDoorService.ApplyResult> original,@Local(argsOnly=true) HomeSessionRecord row) {
        var result=original.call(doors,world,frame,target);
        M7HomeProbe.reached(result.outcome()==ChamberDoorService.ApplyResult.Outcome.UNCHANGED ? "after-recheck" : "recheck-changed",row.sessionUuid(),result.outcome());
        return result;
    }

    @WrapOperation(method="converge",remap=false,require=1,at=@At(value="INVOKE",remap=false,
            target="Ldev/quantumchamber/persistence/SessionRecoveryState;finishHomeClose(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/HomeSessionRecord;Ldev/quantumchamber/collapse/HomeClosure$HomeClosureProof;)Ldev/quantumchamber/persistence/SessionRecoveryState$TerminalResult;"))
    private static SessionRecoveryState.TerminalResult quantumchamberTest$remove(SessionRecoveryState journal,MinecraftServer server,HomeSessionRecord expected,
            HomeClosure.HomeClosureProof proof,Operation<SessionRecoveryState.TerminalResult> original) {
        M7HomeProbe.reached("before-remove",expected.sessionUuid(),null);
        var result=original.call(journal,server,expected,proof);
        M7HomeProbe.reached(result.removed() ? "after-remove" : "remove-rejected",expected.sessionUuid(),result.status());
        return result;
    }
}

/**
 * 僅 testmod（M7 T4 Phase B1）：同上，單 SID 收據 {@link HomeReceiptStore#publishOrVerify} 內的 cut 點。mixin 一個類別只能有一個 target，所以與
 * {@link M7HomeCloseWindowMixin} 同檔、各自是 package-private 的 top-level 類別。
 * <ul>
 * <li>{@code verify-existing}：收據已存在時的 exact 驗證（第 1 個 {@code verify}；不是 cut，只記事件）。</li>
 * <li>{@code after-temp-force}：完整暫存檔 {@code FileChannel.force(true)} 之後。</li>
 * <li>{@code before-publish}／{@code after-publish}：原子不覆寫發佈 {@code Files.createLink} 之前、回傳之後。</li>
 * <li>{@code after-readback}：發佈後 exact 讀回（第 2 個 {@code verify}）之後、刪暫存檔之前。</li>
 * </ul>
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

    @WrapOperation(method="publishOrVerify",remap=false,require=1,at=@At(value="INVOKE",remap=false,target="Ljava/nio/channels/FileChannel;force(Z)V"))
    private void quantumchamberTest$force(FileChannel channel,boolean metaData,Operation<Void> original,@Local(argsOnly=true) HomeSessionRecord frozen) {
        original.call(channel,metaData);
        M7HomeProbe.reached("after-temp-force",frozen.sessionUuid(),null);
    }

    @WrapOperation(method="publishOrVerify",remap=false,require=1,at=@At(value="INVOKE",remap=false,
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
