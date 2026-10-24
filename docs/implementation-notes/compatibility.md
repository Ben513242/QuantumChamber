# M5 相容性與實測範圍

本頁記錄 M5 塌縮與單向通道的相容性證據。專案基線是 Minecraft 1.21、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Java 21；其他 Minecraft 或模組版本沒有因此取得相容性保證。M5 的實作、驗證與已知限制見 [M5 implementation note](2026-10-01-m5-collapse-passage.md) 及 [binding spec](../superpowers/specs/2026-10-01-m5-collapse-passage-design.md)。

## 已實測的 client 畫面

2026-10-01 的 F1b 真 client 驗證使用單人世界，在 Minecraft 1.21 載入 Sodium 0.6.13、Iris 1.8.8（兩者為 `+mc1.21.1` 版）與 Complementary Reimagined r5.9.3。光影關閉與開啟時，驗證用的 `enter`／`return` 均完成；開啟光影時，Iris 為動態宇宙與原世界建立 pipeline，畫面有光影效果。這證明當時的渲染組合能執行 F1b 的進出操作；F1b 本身不等同 M5 可穿越量子膜驗收。後續於2026-10-24完成 [M5 人工 smoke](../manual-acceptance/2026-10-01-m5-manual-smoke.md) 的兩軸量子膜、光影畫面與真實跨越，並經獨立結果review；範圍與原件以該清單為準。

單人世界從世界清單重新進入時，曾看到原版「使用實驗性設定的世界不受支援」警告；以 quick play 進入時沒有顯示。當時的唯讀檢查只看到 M1 起的 `quantumchamber:superposition` 在世界生成設定中；「警告來自 datapack 維度」是推論，沒有另做根因驗證。F1b 的原始觀察見 [M5 implementation note 的 F1b 段](2026-10-01-m5-collapse-passage.md)。

## 與其他模組的接點

| 接點 | 已確認的範圍 | 使用界線 |
| --- | --- | --- |
| 玩家／載具移動 | M5 對 `onPlayerMove` 與 `onVehicleMove` 分別要求注入點命中，以免位置凍結默默失效。 | 其他模組若改寫並移除其中一個注入點，啟動會 fail closed。這是保守的相容性取捨，不代表已與所有移動模組共測。 |
| chunk 卸載 | M5 的 `ServerChunkLoadingManagerStaleUnloadMixin` 接上原版 1.21 的私有 `tryUnloadChunk`，處理曾使關閉世界停在 `Saving worlds` 的 M5 觸發點。 | C2ME、Moonrise 等重寫 chunk 卸載或 holder 管理的模組可能改寫或取代目標；注入點失配時可能開服失敗，並可能與它們自己的修正重疊。尚無這些完整組合的實機相容性驗收。 |
| 結構範本快取 | H6 將 `PalettedBlockInfoList.blockToInfos` 換成 thread-safe map。C2ME 對同一欄位使用 `synchronizedMap`；依這兩個注入方式分析，無論先後，欄位仍是 thread-safe map。 | 這是定點程式分析，並非整套 C2ME 實機驗收。若別的模組假設欄位必為 `HashMap`，可能失敗；目前沒有確認這種模組。 |
| 方向轉換表 | H8 將 `DirectionTransformation` 的 mappings 在類別初始化後建好；針對 C2ME 的建構子初始化寫法做過定點 `compat-c2me` 驗證，兩者並存的結果相同。 | 此驗證不是完整 C2ME 安裝測試。若其他模組 `@Overwrite map`，呼叫會進入它的版本；該覆寫版是否避免 race，須由覆寫模組自己保證。`require=1` 不能證明語意完全相同。 |

原版 `AffineTransformation` 的 lazy 分解也有非 volatile 旗標，但既有盤點指出它不在這次 worldgen 路徑，M5 沒有為此加修正；不能把 H8 的驗證外推到它。原版同步生成步驟若由任何來源丟出例外，dedicated server 停服可能卡住；H6／H8 移除了已觀察到的 M5 觸發點，沒有修改原版通用的例外處理。另有 `StrongholdGenerator` 的 static 可變狀態競態，M5 先列已知問題，修正依既定裁示延到 M6。

本頁只陳述上列實測與定點分析；後續新增模組組合需各自驗證。人工 smoke 的光影穿越結果應填在清單，之後再更新本頁的實測範圍。
