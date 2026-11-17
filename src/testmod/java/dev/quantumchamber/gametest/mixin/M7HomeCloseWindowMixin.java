package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.HomeClosure;
import dev.quantumchamber.gametest.M7HomeProbe;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.Map;
import java.util.Set;
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
 * 收據內部的 cut 在 {@link M7HomeReceiptWindowMixin}（T4 Phase B2a Step 0 拆成獨立檔，B1 審查 M6）。只通知、不改參數與結果、不吞例外；probe 沒有啟用
 * （沒有 {@code quantumchamber.m7home.*}）時 {@link M7HomeProbe#reached} 不做任何事。
 * <p>注入點的數量（B1 審查 M5）：每個注入點都是 {@code require=1}，正式 owner 的呼叫改名、刪除或簽名改變時，套用 mixin 就失敗。沒有 ordinal 的注入點
 * （{@code latchHomeClose}、{@code ChunkSaveCheckpoint.save}、{@code finishHomeClose}）另加 {@code allow=1}：同一個方法裡多出第二處呼叫時，套用 mixin 也失敗，
 * 不會把 cut 套到每一處。{@code applyTarget} 靠 ordinal 0／1 區分「第一次改門」與「移除前再確認」，假設 {@code HomeClosure.converge} 裡
 * <b>恰有 2 次</b> {@code ChamberDoorService.applyTarget}；T5／T6 改 {@code converge} 時（例如在前面插入新的 {@code applyTarget}），ordinal 會無聲位移，
 * 必須同步檢查這兩個注入點與 {@code m7-home-probe.ps1} 的事件序列。
 */
@Mixin(value=HomeClosure.class,remap=false)
abstract class M7HomeCloseWindowMixin {
    @WrapOperation(method="latch",remap=false,require=1,allow=1,at=@At(value="INVOKE",remap=false,
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

    @WrapOperation(method="converge",remap=false,require=1,allow=1,at=@At(value="INVOKE",remap=false,
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

    @WrapOperation(method="converge",remap=false,require=1,allow=1,at=@At(value="INVOKE",remap=false,
            target="Ldev/quantumchamber/persistence/SessionRecoveryState;finishHomeClose(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/HomeSessionRecord;Ldev/quantumchamber/collapse/HomeClosure$HomeClosureProof;)Ldev/quantumchamber/persistence/SessionRecoveryState$TerminalResult;"))
    private static SessionRecoveryState.TerminalResult quantumchamberTest$remove(SessionRecoveryState journal,MinecraftServer server,HomeSessionRecord expected,
            HomeClosure.HomeClosureProof proof,Operation<SessionRecoveryState.TerminalResult> original) {
        M7HomeProbe.reached("before-remove",expected.sessionUuid(),null);
        var result=original.call(journal,server,expected,proof);
        M7HomeProbe.reached(result.removed() ? "after-remove" : "remove-rejected",expected.sessionUuid(),result.status());
        return result;
    }
}
