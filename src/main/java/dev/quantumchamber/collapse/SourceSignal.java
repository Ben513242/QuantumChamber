package dev.quantumchamber.collapse;

/**
 * 來源艙的即時訊號（{@link CollapseService#sourceSignal}）：{@code OK} 之外的每一種都表示那一個條件不成立（依檢查順序，第一個不成立的）。
 * <p>F6-2d-2 Step 0 由 {@code CollapseService} 的巢狀 enum 純搬移出來（H7 quality 的建議：{@link EntryWatch} 與 {@link PassageService} 不再反向 import
 * {@code CollapseService.SourceSignal}；值、順序與說明不變）。
 */
enum SourceSignal {
    OK,
    /** 來源世界或艙體向水平外擴 3 格的 chunk 沒有都 FULL（只查詢、不載入）。 */
    NOT_LOADED,
    /** 結構失效：Controller 的 block entity、方塊或朝向不符，或艙體驗證不通過。 */
    INVALID,
    /** 沒有供電：Controller 沒有即時紅石輸入。 */
    LOW,
    /** Origin 被停用：registry 讀不到、沒有這座原艙、已拆除，或不是 enabled。 */
    DISABLED
}
