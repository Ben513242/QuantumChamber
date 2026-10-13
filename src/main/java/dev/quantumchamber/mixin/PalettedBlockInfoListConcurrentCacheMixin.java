package dev.quantumchamber.mixin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.structure.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 結構範本快取的同時使用（M5 spec §4 H6、§20.12；根因見 M5 implementation note 的 H6 Step 0 一節）：{@code PalettedBlockInfoList} 建構完成時，把方塊 → 方塊資訊的快取
 * {@code blockToInfos} 換成 {@link ConcurrentHashMap}。
 * <p><b>原版 1.21 的 race。</b>{@code StructureTemplate} 由 server 共用的 {@code StructureTemplateManager} 快取，所有世界拿到同一個 instance，也就共用它的
 * {@code PalettedBlockInfoList}。{@code getAllOf} 在第一次用到某個方塊時，以 {@code HashMap.computeIfAbsent} 計算並插入（jigsaw 結構的 {@code structure_starts} 用
 * {@code JIGSAW}，{@code features} 放置 piece 時用 {@code STRUCTURE_BLOCK}）。每個世界的 worldgen 在自己的 mailbox 裡序列執行，單一世界內不會同時呼叫；但兩個世界的
 * mailbox 會在不同的 worker 上同時執行。M3 的 U 與來源世界共用 generator 與 seed，兩邊在同一個區域生成同一個 jigsaw 結構時，以同一個順序第一次用到同一批 template
 * （lockstep）：兩個執行緒同時對同一個 template 做第一次計算，先算完的一方插入，後算完的一方丟 {@code ConcurrentModificationException}，server crash 並卡在停服。不同 seed
 * 的世界仍共用 template，只是機率低很多；原版 Overworld 與 Nether 共用 ruined portal 的 template，server thread 的 {@code /place}、{@code /locate} 也可能與 worker 同時
 * 使用同一個 template，都是同一種情況。這個修正適用於所有世界與所有呼叫端。
 * <p><b>單執行緒時語意不變。</b>{@code ConcurrentHashMap.computeIfAbsent} 對同一個 key 是原子的：同一個方塊只計算一次（第二個執行緒等第一個算完，不重複計算），回傳同樣內容的
 * list。原版的 mapping function 只讀 {@code infos}、不碰這個 map（不會遞迴更新），回傳 {@code Collectors.toList()} 的結果，不會是 null；呼叫端只讀回傳的 list。唯一的差異是
 * null key 會丟 {@code NullPointerException}，原版的呼叫端只傳 {@code Blocks.JIGSAW}、{@code Blocks.STRUCTURE_BLOCK}，不會傳 null。命中時不加鎖；建構時多配置一個空的
 * {@code HashMap}（原版的初始值），可以忽略。
 * <p><b>不記 log。</b>這裡沒有任何例外或分支，只換掉欄位。回歸測試是 JUnit 的 {@code StructureTemplateCacheConcurrencyTest} 與 M5 collapse probe 的 {@code h6race} chain。
 * <p><b>相容性。</b>C2ME（{@code c2me-fixes-worldgen-threading-issues}）在同一個注入點把這個欄位換成 {@code Collections.synchronizedMap}；兩者不論哪一個在後，結果都是
 * thread-safe 的 map。{@code @Overwrite} 了 {@code getAllOf} 的 mod 會讓這個欄位不再被用到，無害；假設欄位是 {@code HashMap} 的 mod 會 {@code ClassCastException}（目前不知道
 * 有這種 mod）。{@code require=1}：目標的建構子改變時開服即失敗（M5 的基線固定 1.21），不會靜默失效。
 */
@Mixin(StructureTemplate.PalettedBlockInfoList.class)
abstract class PalettedBlockInfoListConcurrentCacheMixin {
    @Mutable @Shadow @Final private Map<Block, List<StructureTemplate.StructureBlockInfo>> blockToInfos;
    @Inject(method="<init>(Ljava/util/List;)V",at=@At("RETURN"),require=1)
    private void quantumchamber$concurrentBlockInfoCache(List<StructureTemplate.StructureBlockInfo> infos,CallbackInfo callback) {
        blockToInfos=new ConcurrentHashMap<>();
    }
}
