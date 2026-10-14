package dev.quantumchamber.mixin;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.DirectionTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 方向轉換表的同時初始化（M5 spec §4 H8、§20.14；盤點與根因見 M5 implementation note 的 H8 一節）：{@code DirectionTransformation} 的 class 初始化結束時，對每個常數
 * 呼叫一次 {@code map}，讓每個常數的 {@code mappings} 在任何其他執行緒用到這個 class 之前就完整建立。
 * <p><b>原版 1.21 的 race。</b>{@code DirectionTransformation} 是 enum，所有世界與執行緒共用同一組常數。{@code map(Direction)} 以 lazy init 建立 {@code mappings}：
 * 先把空的 {@code EnumMap} 指定給欄位，再逐一 put（每次 put 都重新讀欄位）。每個世界的 worldgen 在自己的 mailbox 裡序列執行，但兩個世界的 mailbox 會在不同的 worker
 * 上同時執行。兩條 worker 同時第一次用同一個轉換時，後到的一方可能看到已指定、還沒填完的 map，{@code get} 回 null；jigsaw 方塊的 {@code rotate}／{@code mirror}
 * （{@code StructureTemplate.getInfosForBlock} → {@code JigsawBlock.rotate} → {@code mapJigsawOrientation} → {@code Orientation.byDirections}）因此丟
 * {@code NullPointerException}（{@code "facing" is null}），server crash 並卡在停服（與 H6 相同的原版行為）。M3 的 U 與來源世界共用 generator 與 seed，重啟之後兩邊同時生成
 * jigsaw 結構時會遇到；原版的 Overworld 與 Nether 同時生成 jigsaw 結構、或 server thread 的 {@code /place} 與 worker 同時第一次使用，也是同一種情況。這個修正適用於所有
 * 世界與所有呼叫端（common 的程式只經 {@code JigsawBlock}、{@code CrafterBlock} 的 rotate／mirror 呼叫 {@code map}）。
 * <p><b>為什麼在 {@code <clinit>} 的 TAIL。</b>依 JLS 12.4.2，class 的初始化在初始化鎖之下執行，完成之後其他執行緒要先經過同一個鎖（或 JVM 保證等價的 happens-before）
 * 才能使用這個 class，所以 {@code <clinit>} 裡的所有寫入（含每個 {@code EnumMap} 的內容）對之後使用這個 class 的任何執行緒都可見，不依賴特定 CPU 的記憶體模型。之後
 * {@code mappings} 都不是 null，{@code map} 的 lazy 分支不再執行，欄位與 map 都不再被寫入，只剩讀取。TAIL 時常數、{@code COMBINATIONS} 與 {@code INVERSES} 都已建立；
 * 呼叫的就是原版的 {@code map}，結果與原版相同（只是提早算）。成本是 class 初始化時多建 48 個小的 {@code EnumMap}。
 * <p><b>class 初始化的循環。</b>{@code map} 只會初始化 {@code Direction}（與它自己的依賴）、{@code Direction.Axis}、{@code Direction.AxisDirection} 與
 * {@code DirectionTransformation} 自己的 switch 表 class；原版（common 與 clientOnly）之中參照 {@code DirectionTransformation} 的 class 只有 {@code BlockRotation}、
 * {@code BlockMirror}、{@code JigsawBlock}、{@code CrafterBlock}、client 的 {@code ModelRotation} 與那個 switch 表，{@code Direction} 這一側的初始化都不會碰到它們，所以
 * 兩條執行緒各自初始化其中一個 class 時不會互等（javap 與 class 初始化的實測見 note）。
 * <p><b>不記 log。</b>這裡沒有任何例外或分支。回歸測試是 JUnit 的 {@code DirectionTransformationMappingsTest}。
 * <p><b>相容性。</b>C2ME（{@code c2me-fixes-worldgen-threading-issues} 的 {@code MixinDirectionTransformation}）在每個常數的建構子 RETURN 呼叫 {@code map(Direction.UP)}；
 * 兩者並存時 {@code mappings} 在建構時就建好，這裡的呼叫只走非 null 的分支，結果相同、不論先後。{@code @Overwrite} 了 {@code map} 的 mod 會讓這裡呼叫它的版本，
 * 不影響正確性。{@code require=1}：目標的 {@code <clinit>} 不存在時開服即失敗（M5 的基線固定 1.21），不會靜默失效。
 */
@Mixin(DirectionTransformation.class)
abstract class DirectionTransformationEagerMappingsMixin {
    @Inject(method="<clinit>",at=@At("TAIL"),require=1)
    private static void quantumchamber$buildEveryMapping(CallbackInfo callback) {
        for (DirectionTransformation transformation : DirectionTransformation.values()) transformation.map(Direction.NORTH);
    }
}
