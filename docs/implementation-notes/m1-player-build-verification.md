# M1 玩家施工與驗證指引

本指引對應 `feature/m1-chamber` 的 M1 實作。這是操作指引與待驗清單，不是已通過人工驗收的證據。

## 啟動本分支的開發客戶端

目前 M1 尚未合併 `main`，請從 `.worktrees/m1-chamber` 工作區啟動，不要在只有 M0 基底的 main 目錄呼叫 Wrapper。

最方便的方式是在檔案總管進入該工作區，雙擊 `start-client.bat`。聊天中的檔案連結供閱讀，不會自動執行。PowerShell 也可使用：

```powershell
Set-Location 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
.\start-client.bat
```

腳本會保留明確 JAVA_HOME；未設定時嘗試已安裝 Adoptium Java 21，或由 Wrapper 判斷 PATH Java。失敗會停留並保留原始錯誤碼。腳本為 UTF-8／CRLF，請保留 Git 的單檔 eol 規則，不要另存成 LF 中文 batch。

若要明確指定本機 JDK，PowerShell 原始啟動方式：

```powershell
Set-Location 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --no-daemon runClient
```

這是 Fabric 開發客戶端，會自動編譯／載入此工作區的模組，使用 `run/client-base`，不是將 JAR 自動安裝到微軟官方 Launcher 的 mods 目錄。世界位於 `run/client-base/saves`；既有世界可繼續使用，方塊 ID 與儲存 schema 沒有改動。

若舊遊戲仍開著，先正常儲存並退出，再啟動新版本。不要同時用兩個客戶端開同一世界。

![精確施工與驗證圖](../images/m1-chamber-build-guide.png)

[可放大的格線原圖](../images/m1-chamber-build-guide.svg)｜[科幻外觀概念圖](../images/m1-chamber-concept.png)

## 1. 準備材料

請使用創造模式測試世界，並以物品名稱辨認方塊。新版 Quantum Bulkhead 是紫色面板，Chamber Controller 是青色框與正面核心，兩者仍保留基岩底層／外框；繁體中文介面請搜尋「量子艙門」與「腔室控制器」。若仍看到兩者完全像基岩，請先確認已退出舊遊戲並從 M1 工作區啟動新版本。

| 艙體方塊 | 數量 | 用途 |
| --- | ---: | --- |
| Bedrock（基岩） | 192 | 底板、側牆、後牆、外框與屋頂 |
| Quantum Bulkhead（量子艙門） | 25 | 正面中央 5×5 的整面艙門 |
| Chamber Controller（腔室控制器） | 1 | 正面最上排中央的控制器 |

額外準備 Brewing Stand、Blaze Powder、Water Bottle、Nether Wart、Echo Shard、Comparator、Lever、Redstone Dust，以及外接裝置的支撐方塊。紅石裝置不是艙體的三種材料。

紫色／青色表面只引用 Minecraft 既有材質，不代表要用紫珀方塊、混凝土或海燈籠來建造；實際放置仍只能是上表三種艙體方塊。新造型會套用到原有世界與物品圖示，不必拆掉受保護的艙體。

## 2. 從截圖的外框繼續蓋

1. 用方塊逐格確認外側寬與深都是 **7 格**；截圖的透視不能取代實際數格。
2. 指定一面當正面。以下 `y` 是相對底板高度，不是世界的絕對 Y 座標。
3. `y=0`：底板整片 7×7 都是基岩。若截圖這圈就是底板，中央草地區也要換成 5×5 基岩。
4. `y=1`：正面是 `B D D D D D B`；左牆、右牆與後牆是基岩；中央 5×5 留作室內空間。
5. 重複上一層配置，蓋到 `y=5`。累計得到 **25 個 Bulkhead**，不是普通基岩門。
6. `y=6`：屋頂封成完整 7×7；正面最上排是 `B B B C B B B`，其餘都是基岩。
7. 站在室外、面對前牆放置 Controller，使其 `facing` 朝向室外。可用 F3 瞄準 Controller 核對朝向及記下座標。
8. 最終外側 **7×7×7**，牆厚 1 格，室內 **5×5×5**；192 基岩＋25 Bulkhead＋1 Controller。

注意：完整有效艙體約在下一次 20-tick 刷新時自動登錄並預設啟用，接著整個已登錄範圍受保護。M1 沒有手動啟用按鈕或設定介面。若要事先放室內測試裝置，請在完成整座之前規劃好；不要假設完工後還能改室內方塊。

## 3. 釀造與飲用

1. Brewing Stand 左側燃料槽放 Blaze Powder。
2. 下方瓶槽放 Water Bottle，上方材料槽放 Nether Wart，先做出 Awkward Potion。
3. 下方留下 Awkward Potion，上方材料換成 Echo Shard，釀造 QuantumState Potion（量子態藥水）。
4. 飲用後檢查效果列表、HUD 圖示與倒數時間；這個效果為 3 分鐘。測試時間較長時要補喝。

實際釀造、飲用及 HUD 必須人工觀察，不能以單元測試或直接加入效果當作這一項通過。

## 4. 比較器配置與觀察分工

比較器放在 Controller **室外側、同一高度**，比較器的背面（兩支火把的一側）對著 Controller，輸出端朝外。比較器與輸出端第一格紅石粉下方都要有支撐方塊，且應維持一般比較模式；不要在第一格紅石粉旁再放其他電源。請在室外支撐方塊頂面放置比較器；若直接點 Controller 放外接元件，先蹲下，避免被當成門控右鍵。

F3 的比較器 `facing` 是朝輸入側，不是輸出側。例如 Controller 朝 `north`，比較器在其北側，比較器 `facing` 應為 `south`，紅石粉接在比較器北側。這對應目前實體 comparator GameTest 的配置。

```text
室內／前牆       室外
Controller → Comparator → Redstone Dust
               下方支撐       下方支撐
```

外接輸入拉桿與比較器輸出線分開。可在創造模式飛到屋頂，手拿 Lever、蹲下點 Controller 頂面，把拉桿放在 Controller 正上方，位於艙體範圍外，供電給 Controller。這個拉桿的實際手動操作仍列為待驗，不能把既有 redstone-block GameTest 當作拉桿驗證。

測試時由室外操作／觀察者撥桿，室內受測玩家保持在中央區域。

- 室內玩家不能是 spectator，且整個玩家碰撞箱必須在 interior 內；不要站在門檻或卡牆。
- 室外觀察者以 F3 瞄準輸出端**第一格紅石粉**，讀取 `power`。
- 唯一玩家走出去看比較器後，室內變成零人，下次刷新會回到 IDLE。不能因此判斷 READY／ARMED 壞掉。
- 單人若允許指令，可留在室內輸入 `/data get block <X> <Y> <Z> ChamberState`，將座標替換成 Controller 的實際座標。這只能檢查狀態，**不能取代實體比較器驗收**。

## 5. 待執行的人工驗證

| 步驟 | 操作 | 預期觀察 |
| --- | --- | --- |
| A | 有效艙體、關門，室內沒有玩家或玩家缺效果；輸入保持低電位 | IDLE，輸出 3 |
| B | 玩家完整站在室內、非 spectator、全員有 QuantumState、門已關 | READY，輸出 7；一般刷新最長約 1 秒 |
| C | 外部觀察者將拉桿從低電位切成供電 | ARMED，輸出 11 |
| D | 拉桿保持供電，室內玩家喝牛奶移除效果，再補喝 QuantumState | 先降到 3，再到 7；持續高電位不得自行回到 11 |
| E | 全員仍有效果，拉桿切成不供電，再重新供電 | 新的 rising edge 才重新到 11 |
| F | 另在不重疊空地建一座尚未登錄的測試艙，刻意少放一格基岩 | INVALID，輸出 0；不要拆已受保護的第一座 |
| G | 兩位室內玩家，其中一位缺效果，再給 rising edge | 不得 ARMED，輸出 3；兩人都補上效果後才 READY |
| H | 右鍵艙門或 Controller 開門，進入後右鍵 Controller 關門 | 整面 25 格開／關同步；原生 ServerWorld 互動已經過 GameTest，正常滑鼠操作仍須人工觀察 |
| I | 觀察上述全程 | 沒有傳送、走廊、新 Dimension 或 Universe 配置；M1 停在 ARMED |

兩位室內玩家的案例還需安排外部操作／觀察，或使用完工前已設置並驗證的室內控制線路。帳號或觀察條件不足時，該項保留「待驗」，不要算通過。

每項請記錄實際結果與截圖；輸出 15 不屬於 M1 的預期狀態。

## Controller 門控與客戶端重啟

2026-09-16 已依使用者核准方案新增 Controller 的右鍵門控。關閉的 Bulkhead 仍可以右鍵開門；開啟後 outline 為空，滑鼠不能再次瞄準門，請改瞄準前牆最上排中央的 Controller。

1. 先正常儲存並退出舊客戶端，再啟動新版本；正在執行的舊 JVM 不會熱載入此修正。
2. 室外可右鍵艙門或 Controller 開門，全部 25 格同步開啟。
3. 進入 interior 後靠近入口，但整個身體仍留在室內、不要站在門面上。
4. 從開口瞄準門頂中央 Controller 的底面或可見表面，再右鍵關門。若無法瞄準，先確認方向、距離與視線，勿把已開啟的空門格當目標。
5. 有 buff 且門關閉時 READY／7；給新的紅石 rising edge 才 ARMED／11。開門立即回 IDLE／3，再關門不會把 held-high 當新 edge。

這個入口保留原本的 25-block transaction、authorization 與同步刷新，不新增 GUI、renderer 或自訂 packet。原生互動自動回歸不代表上述滑鼠／視線／HUD／多人步驟已人工通過；請逐項記錄。

## 圖片說明

- 格線施工圖直接依實際幾何與狀態契約繪製；PNG 是 SVG 的轉出版本。
- 科幻概念圖使用內建圖片生成工具，附件只作為 Minecraft 場景參考。提示內容摘要：7×7×7 基岩艙體、5×5×5 剖面、5×5 艙門、頂部中央控制器、外接比較器與拉桿，使用原創青色／紫色科幻語彙，禁止傳送門、走廊與 AE2 資產複製。
- 概念圖的格數、材質與控制器細節可能藝術化；**施工以格線圖及本文件為準**。青紫色發光外觀不是目前 M1 的實際 renderer 或材質。
