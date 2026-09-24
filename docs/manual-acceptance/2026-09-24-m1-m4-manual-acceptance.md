# M1–M4 人工驗收清單

建立日期：2026-09-24。分支 `feature/m1-chamber`；程式基準為 code HEAD `752ada1`（`752ada1b34f27685014fc3e6ec10fee77b88a86a`），其後只有文件 commit。本清單建立時，A、B、C 段所有子項都還沒有任何結果紀錄。

## 1. 目的與範圍

本清單是 M1–M4 以 `git merge --ff-only` 合併 `main` 之前的人工 gate 紀錄，只列需要親自進遊戲操作的項目。JUnit、GameTest、跨 JVM probe 等自動 gate 不在此列，也不能拿來代填本清單的結果。

來源文件：

- [AGENTS.md](../../AGENTS.md)「下一步」
- [M1 實作與驗證紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」「Dedicated server 與重啟證據」
- [M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」、[M1.1 核准契約](../implementation-notes/m1.1-contract.md) §2–§4
- [M1.2 紀錄](../implementation-notes/m1.2-powered-origin.md)「警告與人工待驗」、[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §1、§4、§7、§8
- [玩家施工與驗證指引](../implementation-notes/m1-player-build-verification.md)（表 A–N、「6. 停用、重新啟用與拆除」）
- [M2 操作與驗證](../implementation-notes/m2-corridor.md#八項人工驗收)、[M2 設計](../superpowers/specs/2026-09-17-m2-powered-corridor-design.md) §2–§4
- [M4 候選門紀錄](../implementation-notes/2026-09-21-m4-candidate-doors.md)「人工驗收狀態（spec §15）」「DORMANT 只封鎖自己的 Chamber（W-I2）」、[M4 設計](../superpowers/specs/2026-09-21-m4-candidate-doors-design.md) §10、§11、§15

| 編號 | 項目 | 模式 | 執行世界（建議） |
| --- | --- | --- | --- |
| B-0 | M1 殼體不完整＝INVALID＝比較器 0（W1 第一個執行） | 單人 | W1 |
| A-1 | M2-1 單人飲用與左右入口（目前 HEAD 快速複驗） | 單人 | W1 |
| A-2 | M2-2 單人自然到期（目前 HEAD 快速複驗） | 單人 | W1 |
| B-1 | M2-4 HIGH 再入場與 LOW 收尾 | 單人 | W1 |
| B-2 | M2-7 光影與資源重載（含 M1.2 shader／GPU／日夜粒子與自然淡出） | 單人 | W-R |
| B-3 | M1.1 維護手勢（依 M1.2 現行語意） | 單人 | W1 |
| B-4 | legacy schema1 舊房間 | 單人 | W-L |
| B-5 | M4 spec §15 側門（最後執行） | 單人 | W-M4 |
| C-1 | M1 多人資格判定 | 多人 | W-MP |
| C-2 | M1 多人跨程序重啟 | 多人 | W-MP |
| C-3 | M2-3 多人共享效果與離線 | 多人 | W-MP |
| C-4 | M2-6 多人 96 格頁面 seam | 多人 | W-MP |

世界代號：W1＝`run/client-base/saves/QC-accept-sp`、W-R＝`run/client-render/saves/QC-accept-render`、W-L＝`run/client-base/saves/QC-legacy-schema1`（m1-smoke 複本）、W-M4＝`run/client-base/saves/QC-accept-m4`、W-MP＝`run/server/QC-accept-mp`。名稱可自訂，但都必須是新建或複本，不可用既有世界。

## 2. 驗收前準備

### 2.1 備份

先正常儲存並退出所有 Minecraft 客戶端與伺服器，再把下列資料複製到 repo 外：

- `run/client-base/saves/新的世界test (1)`：目前 client-base 唯一的存檔，屬使用者資料。不得覆寫，也不要拿來驗收。
- `run/server/m1-smoke`：M1 dedicated 重啟證據，也是 B-4 唯一現成的 schema1 世界。不得直接開啟。
- `run/server/server.properties`：多人驗收時伺服器會重新寫出此檔。
- 官方 Launcher（`.minecraft`）的正式世界：本清單不使用。若要複製進開發 profile，先完整備份。

```powershell
$repo = 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
$bak  = 'C:\Users\Ben\Documents\QC-acceptance-backup-20260924'   # 必須在 repo 外
if (Test-Path -LiteralPath $bak) {
    throw "備份目錄已存在，請勿重跑；改用新目錄名"
} else {
    New-Item -ItemType Directory $bak -ErrorAction Stop | Out-Null
    Copy-Item -Recurse -LiteralPath "$repo\run\client-base\saves\新的世界test (1)" -Destination "$bak\client-base-新的世界test (1)" -ErrorAction Stop
    Copy-Item -Recurse -LiteralPath "$repo\run\server\m1-smoke" -Destination "$bak\server-m1-smoke" -ErrorAction Stop
    Copy-Item -LiteralPath "$repo\run\server\server.properties" -Destination "$bak\server.properties" -ErrorAction Stop
    Get-FileHash -Algorithm SHA256 -LiteralPath "$bak\server-m1-smoke\data\quantumchamber_chambers.dat"
}
```

- 備份目錄已存在時會直接停止，避免之後重跑時用已被修改的檔案覆蓋備份；要重做備份請換一個新的目錄名，並同步修改後文用到的 `$bak`。
- 最後輸出的雜湊應為 `80ED28EDD4414A67945B0763130A4006154FBD9DFB01BF4683497676E6744CC9`，與 [M1 紀錄](../implementation-notes/m1-chamber.md) 的 registry SHA-256 相同。不同就先停下來確認來源。
- 後文的 `$repo`、`$bak` 都指這裡的定義；換了 PowerShell 視窗要重新設定這兩個變數（只設變數，不要重跑整段）。

`run/` 內其他目錄（`gametest*`、`m*-recovery-*`、`m12-*` 等）是自動 gate 證據，驗收期間不要開啟或修改。

### 2.2 Build 與啟動方式

確認 build（在工作區根目錄執行）：

```powershell
Set-Location 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
git status --short
git rev-parse --short HEAD
git diff --stat 752ada1 HEAD -- src build.gradle gradle.properties settings.gradle gradle gradlew gradlew.bat start-client.bat
```

- 開始驗收前，`git status --short` 應該沒有輸出。開始填寫本清單後，只出現本清單檔案被修改屬正常；出現其他檔案就先停下來確認。
- 第三個指令沒有輸出，代表目前 HEAD 的程式與 `752ada1` 相同（其後只有文件 commit，包括 `9c421a7` 與本清單所在的 commit）。紀錄欄的 build 填第二個指令的結果。
- 第三個指令有輸出，表示程式已變更：停止驗收，先在新 HEAD 重跑 automated gates。

啟動方式（依 `start-client.bat` 與 `build.gradle` 的 run config）：

| 用途 | 指令（工作區根目錄） | Profile（runDir） | 存檔位置 |
| --- | --- | --- | --- |
| 單人：A、B-0、B-1、B-3、B-4、B-5 | 雙擊 `start-client.bat`，或 `.\start-client.bat`（即 `gradlew --no-daemon runClient`） | `run/client-base` | `run/client-base/saves` |
| B-2 光影 | `.\gradlew.bat --no-daemon runClientRender` | `run/client-render` | `run/client-render/saves` |
| 多人 | 見 2.3 | `run/server` 與三個 client profile | `run/server/<world>` |

- `start-client.bat` 會自動找 Java 21。直接呼叫 `gradlew.bat` 時，若找不到 Java 21，先依 [玩家指引](../implementation-notes/m1-player-build-verification.md)「啟動本分支的開發客戶端」設定 `JAVA_HOME` 與 `PATH`。
- 已開著的客戶端不會熱載入新 build，換 build 前先正常退出。不要讓兩個客戶端同時開同一個單人世界。
- 單人世界建立時請選「創造模式」並開啟「允許作弊」，後文的 `/data`、`/give`、`/time` 等指令才能使用。

### 2.3 多人本機架設

Repo 現況：`run/server/server.properties` 已設定 `online-mode=false`、`server-ip=127.0.0.1`、`server-port=25576`、`level-name=m1-smoke`、`level-type=minecraft:flat`、`view-distance=3`，`eula.txt` 已接受。M1 曾以 `runServer --args=nogui` 在這個 loopback 位址啟動（見 [M1 紀錄](../implementation-notes/m1-chamber.md)「Dedicated server 與重啟證據」）。Repo 沒有「一台電腦同時開伺服器與多個客戶端」的現成腳本，以下步驟由既有 run config 組合而成；標示「需自行確認」的地方，請把實際情況寫進備註。

資源提醒：同一台電腦要同時跑 1 個伺服器、3 個客戶端，以及各自的 Gradle JVM，記憶體與 CPU 需求都高（需自行確認）。不夠時可以把觀察者改到另一台電腦；C-4 不需要觀察者，可先關掉 OBS。

1. **絕對不要讓伺服器開到 `m1-smoke`**：那會改寫 schema1 證據世界。做兩道防護：
   - 伺服器停止時（它啟動時會重新寫出 `server.properties`），先完成 2.1 備份，再用文字編輯器（例如記事本）把 `run/server/server.properties` 的 `level-name=m1-smoke` 暫時改成 `level-name=QC-accept-mp`。改完用 `Select-String -LiteralPath "$repo\run\server\server.properties" -Pattern '^level-name='` 確認。
   - 每次啟動都同時帶原生伺服器參數 `--world`（需自行確認）：

   ```powershell
   .\gradlew.bat --no-daemon --console=plain runServer --args='nogui --world QC-accept-mp'
   ```

   第一次啟動會建立 `run/server/QC-accept-mp`（超平坦）。確認主控台的世界名稱是 `QC-accept-mp`；若看到 `m1-smoke`，立刻輸入 `stop` 並回報。`generator-settings={}` 可能讓日誌出現 `No key layers in MapLike[{}]` ERROR，這是 [M1.2 紀錄](../implementation-notes/m1.2-powered-origin.md) 已記錄的現象；若世界無法站立或無法使用，多人項目記 BLOCKED。
2. 主控台出現 `Done (…)! For help, type "help"` 後，在同一個視窗輸入 `op QC_A`、`op QC_B`、`op QC_OBS`。
3. 另開三個 PowerShell 視窗，依序啟動客戶端。前一個進到標題畫面後再開下一個：

   ```powershell
   .\gradlew.bat --no-daemon runClient --args='--username QC_A'          # 玩家 A：run/client-base
   .\gradlew.bat --no-daemon runClientLight --args='--username QC_B'     # 玩家 B：run/client-light（含選用照明模組，只影響畫面）
   .\gradlew.bat --no-daemon runClientRender --args='--username QC_OBS'  # 外部觀察者：run/client-render
   ```

   - `--args` 的用法與 M1 紀錄的 `--args='--quickPlaySingleplayer M1Smoke'` 相同；`--username` 是原生客戶端參數（需自行確認）。
   - 一定要固定使用者名稱：offline-mode 的玩家 UUID 由名稱決定。斷線或重啟後必須用同一個名稱重新連線，否則伺服器會當成另一位玩家。
   - 同一個工作區同時執行多個 Gradle 工作時會不會互等鎖，需自行確認。若後開的 Gradle 一直停在等待鎖，替代做法是在 repo 外另 clone 一份 `feature/m1-chamber`（同一 commit），從那份 clone 啟動第二、第三個客戶端（它們會使用該 clone 自己的 `run/`）。
   - 若 B-2 已在 `run/client-render/mods` 放入 Iris／Sodium，觀察者畫面會套用光影。這不影響伺服器判定，但請在備註註明。
4. 每個客戶端：「多人遊戲」→「直接連線」→ `127.0.0.1:25576`。進入後用 `/gamemode creative` 建艙與取物；創造模式不影響資格判定（只排除旁觀者）。
5. 若客戶端因安全個人資料（profile public key）相關訊息被拒絕，可在伺服器停止時把 `server.properties` 的 `enforce-secure-profile` 改成 `false`（需自行確認）。
6. C-4 需要較遠的視距時，在伺服器停止時把 `view-distance` 從 3 調高（例如 10）；伺服器執行中修改會在下次啟動時被覆蓋。
7. 停止伺服器：在主控台輸入 `stop`。C-2 重開時一律使用第 1 步的完整指令（含 `--world QC-accept-mp`）。
8. 測完：`deop QC_A`、`deop QC_B`、`deop QC_OBS`（`ops.json` 原本是 `[]`）；輸入 `stop` 停止伺服器；把 `run/server/QC-accept-mp` 複製到 repo 外保存；最後把 `server.properties` 從備份還原：`Copy-Item -LiteralPath "$bak\server.properties" -Destination "$repo\run\server\server.properties"`，並用 `Select-String` 確認 `level-name=m1-smoke` 已恢復。

不建議用單人世界「在區域網路上開放」：開發客戶端沒有正式帳號，整合伺服器通常會驗證加入者的帳號，第二個開發客戶端很可能無法加入（需自行確認）。

### 2.4 施工參考

- 手動施工：依 [玩家指引](../implementation-notes/m1-player-build-verification.md)「1. 準備材料」「2. 從截圖的外框繼續蓋」（192 基岩、25 量子艙門、1 腔室控制器），以及「4. 比較器配置與觀察分工」（比較器、紅石粉、拉桿位置與 F3 讀值）。
- 藥水：量子態藥水（Potion of Quantum State），效果 3 分鐘。釀造列在 D 段；本清單可以從創造模式物品欄拿，或輸入 `/give @s minecraft:potion[minecraft:potion_contents={potion:"quantumchamber:quantum_state"}] 8`。牛奶：`/give @s minecraft:milk_bucket`。
- 讀值方式：艙外以 F3 瞄準比較器輸出端的**第一格紅石粉**，讀 `power`。預期值只有 `0`（INVALID）、`3`（IDLE）、`7`（READY）、`11`（ARMED）；出現 15 一律記 FAIL。
- 選用的指令快速建艙（手動施工見上方玩家指引；此處指令只為節省時間）。範例是超平坦世界（地表草地 y=-61）、角落 `(0,-61,20)`、艙門朝北（z=20 那一面）。請依 F3 換成自己的座標，並確認範圍內沒有其他建築：

  ```mcfunction
  /fill 0 -61 20 6 -55 26 minecraft:bedrock hollow
  /fill 1 -60 20 5 -56 20 quantumchamber:quantum_bulkhead[open=false]
  /setblock 3 -55 20 quantumchamber:chamber_controller[facing=north]
  /setblock 3 -54 20 minecraft:lever[face=floor,facing=north]
  /setblock 3 -56 19 minecraft:stone
  /setblock 3 -56 18 minecraft:stone
  /setblock 3 -55 19 minecraft:comparator[facing=south]
  /setblock 3 -55 18 minecraft:redstone_wire
  ```

  完成後：Controller 在 `3 -55 20`，拉桿在它正上方 `3 -54 20`（艙體外），比較器在 Controller 北側 `3 -55 19`，第一格紅石粉在 `3 -55 18`。艙體的 `fill`／`setblock` 沿用 M1 dedicated smoke 的配置方式，比較器與紅石粉的位置依指引「4.」。第二座艙請整體平移（例如 x 全部加 20），不可與第一座重疊。後文用「Controller 座標」「拉桿座標」指你實際的位置。

### 2.5 警告

- **(a) M4 側門會永久封鎖原艙（到 M5 為止）。** 右鍵走廊側門鎖定候選後，該 session 返還時會留下 DORMANT receipt：那座原艙在該存檔裡無法再入場，也無法斷電拆除，遊戲內沒有解除方法。B-5 必須用另一座 Chamber 或另一個測試世界（建議 W-M4），並且在所有 M2 項目（A、B-1、B-2、C-3、C-4）都驗完之後才做。A、B-0～B-4、C 段全程都不要右鍵走廊兩側的側門。
- **(b) 不可降版。** 用本 build 在某個存檔建立過任何走廊 session 後，該存檔的 `data/quantumchamber_sessions.dat` 會寫成 schema3，並新增 `quantumchamber_candidate_entropy.dat` 與 `quantumchamber_universe_discovery.dat`；M4 之前的 build 讀不了 schema3，會 fail closed。Chamber registry 也會寫成 schema2，M1.2 之前的 build 讀不了。驗收用的存檔不要再用舊 build 開，也不要拿正式世界驗收。
- **(c) 返還後要等清理完成才能用其他 Chamber。** 已返還的玩家要等該 session 全員返還、清理完成（M4 的 DORMANT）之後，才能從其他 Chamber 入場；返還與清理階段（`RETURN_PLAYERS`／`RELEASE_GEOMETRY`）仍會被拒絕。
- **(d) 返還後先等幾秒。** 任何一次返還後，在該 session 清理完成前，參與者的移動與方塊互動會暫時被伺服器擋下（`SessionRecoveryManager.blocks`）；喝東西等使用物品不受影響。單人項目（例如 A-2、B-1a、B-1b）回到原艙後先等幾秒，再開門或操作方塊。
- 單人測試途中不要退出世界；需要重開時，在備註記錄。

### 2.6 建議執行順序

1. 2.1 備份、2.2 確認 build。
2. W1（C1 一座艙）：B-0 → A-1 → A-2 → B-1a → B-3a → B-3c → B-1b → B-3b → B-3d。
3. W-MP（2.3）：C-1a → C-1b → C-3a → C-1c → C-3b → C-4 → C-2。
4. B-2（W-R）與 B-4（W-L），順序不限。
5. 最後做 B-5（W-M4）。

## 3. 紀錄欄位說明

每個子項各記一列：

| 欄位 | 填法 |
| --- | --- |
| 日期 | `YYYY-MM-DD`（Asia/Taipei） |
| 驗收者 | 操作的人；多人項目列出所有參與者 |
| build（commit SHA） | 2.2 的 `git rev-parse --short HEAD` 結果；code 必須等同 `752ada1` |
| 單人／多人 | 多人時註明玩家數與是否有觀察者 |
| 結果 | `PASS`：所有預期都成立。`FAIL`：任一預期不成立，備註寫實際現象。`BLOCKED`：環境或前置條件無法完成（例如 Iris 無法載入）。`N/A`：不適用，必須寫理由；需要使用者同意的，另記入 E 段 waiver |
| 證據 | 截圖或影片的完整路徑，建議放在 repo 外（例如 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-09-24\`）。F2 截圖預設存在各 profile 的 `screenshots/`（例如 `run/client-base/screenshots/`），驗收後複製出去 |
| 備註 | 實際讀值、訊息原文、替代做法（例如以指令代替計時器）、模組與 GPU 版本 |

同一子項重驗時新增一列，不覆寫舊紀錄。

## A. 目前 HEAD 快速複驗

M2-1、M2-2 使用者已回報驗過（見 D 段），但 M4 之後入場與返還路徑有改動：`start()` 在任何 reservation 之前先做跨 session 玩家預檢並凍結 candidate context，返還路徑也新增 `MEASURED` 分支（見 [M4 紀錄](../implementation-notes/2026-09-21-m4-candidate-doors.md)「Discovery authority 與 policy snapshot」「MEASURED freeze 與 retained recovery」）。因此在目前 HEAD 以精簡步驟複驗。

### A-1 M2-1 單人原生飲用與左右入口

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 1 項；[單人無遊戲指令流程](../implementation-notes/m2-corridor.md#單人無遊戲指令流程) 第 2–5 步。
- 前置條件：W1 的 C1 已完成 B-0（已登錄、殼體完整），比較器與拉桿就位，拉桿 OFF；身上有量子態藥水至少 4 瓶與牛奶 2 桶；沒有量子態效果。
- 操作步驟：
  1. 在艙外把拉桿扳到 ON，F3 讀 `power`。
  2. 右鍵量子艙門（或 Controller）開門，整個人走進室內（不要站在門檻上）。
  3. 從開口瞄準門頂中央 Controller 的底面，右鍵關門。
  4. 按 E 確認沒有「量子態」效果；飲用量子態藥水；再按 E 記下剩餘時間。
  5. 最多等約 5 秒，不扳拉桿、不輸入指令。
  6. 按 F3 看維度；按 E 看剩餘時間；依序看左、右、回頭，再沿走廊走幾步。不要右鍵任何側門。
  7. 留在走廊，直接接 A-2（不要喝奶）。
- 預期結果：
  - 步驟 1：`power` = 3（已供電、空艙）。
  - 步驟 3：actionbar 顯示「已供電保護；請關門、進艙並讓全員取得 QuantumState。」
  - 步驟 4：藥水依原生規則消耗（生存模式變成玻璃瓶；創造模式不消耗）；畫面右上出現效果圖示，E 畫面顯示剩餘約 3:00。
  - 步驟 5：自動入場，F3 維度為 `quantumchamber:superposition`。
  - 步驟 6：效果仍在，剩餘時間接續遞減（沒有重置成 3:00，也沒有消失）；玩家在一間與原艙同形的入口艙內，入口艙左右兩側打開，走廊往左右延伸；地板與牆壁正常碰撞。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-1 |  |  |  | 單人 |  |  |  |

### A-2 M2-2 單人自然到期

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 2 項；[單人無遊戲指令流程](../implementation-notes/m2-corridor.md#單人無遊戲指令流程) 第 6 步。
- 前置條件：接續 A-1，玩家在走廊、效果仍在，拉桿維持 ON。
- 操作步驟：
  1. 不補喝、不喝奶，等量子態效果自然倒數到 0（約 3 分鐘）。
  2. 效果消失後觀察 1–5 秒。
  3. 按 F3 看維度與座標；按 E 看效果；確認背包沒有多出藥水。
  4. 等幾秒（見 2.5 (d)），在室內右鍵量子艙門開門，走到艙外，F3 讀 `power`。
  5. 以創造模式左鍵一格艙體基岩，再左鍵 Controller。
- 預期結果：
  - 步驟 2：自動回到原世界、同一座原艙的室內。
  - 步驟 3：F3 維度為 `minecraft:overworld`、座標在 C1 室內；沒有量子態效果，也沒有退回藥水。
  - 步驟 4：`power` = 3（仍供電、艙內沒有合格玩家）。
  - 步驟 5：基岩不會被移除（畫面可能閃一下再恢復）；Controller 不會被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-2 |  |  |  | 單人 |  |  |  |

## B. 單人待驗

### B-0 殼體不完整＝INVALID＝比較器 0（W1 第一個執行）

- 來源：[M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 4 項前半（Invalid shell=0、有效但條件不足=3）；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 A、B、F；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 1 項（未紅石啟動的新艙可 Creative 拆改）。
- 為什麼要在草稿階段做：已登錄的原艙只要不是已完成協調的 OFF，殼體就受保護，方塊拆不掉也放不回去；尚未登錄的草稿不受保護。玩家指引表 F 也是「另建尚未登錄的測試艙、少放一格基岩」，並提醒不要拆已受保護的艙。因此這一項要在 C1 第一次供電之前做。
- 前置條件：W1 的 C1 剛蓋好（手動施工或 2.4 指令），拉桿 OFF，從未供電；`/data get block <Controller 座標> ChamberUuid` 查不到這個欄位。
- 操作步驟：
  1. 以創造模式左鍵拆掉一格殼體基岩，例如背牆正中央（2.4 範例為 `3 -58 26`）。不要拆 Controller、量子艙門或拉桿。
  2. 拉桿扳到 ON，等 3 秒。F3 讀 `power`；輸入 `/data get block <Controller 座標> ChamberUuid`。
  3. 在同一個位置放回一格基岩，等 3 秒。F3 讀 `power`；再查一次 `ChamberUuid`。
  4. 拉桿扳到 OFF，等 3 秒，F3 讀 `power`。
- 預期結果：
  - 步驟 1：基岩可以移除（草稿不受保護）。
  - 步驟 2：`power` = 0（已供電但殼體不完整，INVALID）；查不到 `ChamberUuid`（殼體無效時不會註冊）。
  - 步驟 3：`power` = 3（殼體完整、已供電、艙內無人，IDLE）；`ChamberUuid` 出現一組整數陣列（這時才註冊並開始保護）。
  - 步驟 4：`power` = 0（OFF）；C1 保留 UUID。接著做 A-1。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-0 殼體不完整＝INVALID＝0，補回後 3 |  |  |  | 單人 |  |  |  |

### B-1 M2-4 HIGH 再入場與 LOW 收尾

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 4 項；[單人無遊戲指令流程](../implementation-notes/m2-corridor.md#單人無遊戲指令流程) 第 6–7 步；[M2 設計](../superpowers/specs/2026-09-17-m2-powered-corridor-design.md) §2。
- 前置條件：接續 A-2（C1 拉桿 ON，玩家沒有量子態效果）。B-1b 需要在玩家還在走廊時切斷外部供電，擇一並在備註註明：
  - (i) 入艙前自行搭好、並已空跑量過時間的外部計時斷電電路（放在 Controller 附近）；
  - (ii) 替代做法：在走廊內輸入 `/execute in minecraft:overworld run setblock <拉桿座標> minecraft:lever[face=floor,facing=north,powered=false]`，等同把拉桿扳到 OFF。
  - 不論用哪一種，B-1b 結束時 C1 都必須是 OFF（`power` = 0），因為 B-3b、B-3d 以此為前提。
- B-1a HIGH 再入場，操作步驟：
  1. 進艙、關門（同 A-1 步驟 2–3）。
  2. 飲用量子態藥水，最多等約 5 秒。
  3. 確認在走廊後喝牛奶。
- B-1a 預期結果：
  - 步驟 2：不必扳拉桿就自動再次入場（F3 `quantumchamber:superposition`），這就是新的 session。SID 在遊戲內看不到，不要求記錄。
  - 步驟 3：回到 C1 室內（`minecraft:overworld`），沒有量子態效果。
- B-1b 走廊中 LOW，操作步驟（依 2.6 順序，在 B-3c 之後進行）：
  1. 前置：玩家在走廊（B-3c 結束時的狀態），拉桿 ON，效果剩餘至少 1 分鐘。
  2. 在走廊地板丟下 1 顆鑽石（Q 鍵），記下背包的鑽石數。
  3. 以 (i) 或 (ii) 切斷外部供電，觀察 1–5 秒。
  4. 按 F3、按 E；看艙內地面與背包鑽石數。
  5. 等幾秒（見 2.5 (d)），在室內右鍵量子艙門開門，走出艙外，F3 讀 `power`；在 32 格內觀察艙體外側四角約 10 秒。
- B-1b 預期結果：
  - 步驟 3：自動回到 C1 室內（`minecraft:overworld`）。
  - 步驟 4：量子態效果保留剩餘時間（LOW 返還不移除、不退款）；走廊裡的鑽石出現在 C1 室內地面中央附近（若被自動撿起，背包數量會回到丟之前）。
  - 步驟 5：`power` = 0（OFF）；艙體不再冒出新的輝光粒子。保護解除在 B-3d 步驟 3 以「可拆除」確認。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-1a HIGH 再入場 |  |  |  | 單人 |  |  |  |
| B-1b 走廊中 LOW 安全返還→OFF |  |  |  | 單人 |  |  | 斷電方式： |

### B-2 M2-7 光影與資源重載（含 M1.2 shader／GPU／日夜粒子與自然淡出）

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 7 項；[M1.2 紀錄](../implementation-notes/m1.2-powered-origin.md)「警告與人工待驗」；[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §7（Sodium／Iris／shader 相容性須以精確版本人工驗證）。
- 前置條件：
  1. 本專案固定 Minecraft 1.21（`gradle.properties` 的 `minecraft_version=1.21`）、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21。從官方來源（例如 Modrinth）下載標明支援 Minecraft **1.21** 的 Fabric 版 Sodium 與 Iris（不要選只支援 1.21.1 的版本），放進 `run/client-render/mods`（資料夾不存在就建立）；選一個支援 1.21 的 shader pack 放進 `run/client-render/shaderpacks`。在備註記下檔名、版本與 SHA-256（`Get-FileHash`）。
  2. Loom 開發客戶端能不能載入官方 Iris／Sodium JAR，需自行確認（本專案的 `run/client-light` 以同樣方式載入官方 LambDynamicLights）。啟動後 `run/client-render/logs/latest.log` 應有 `Detected optional mod compatibility: sodium=true, iris=true, immersive_portals=false`。載入失敗時 B-2 記 BLOCKED 並附 log，不要為此修改 `build.gradle`。
  3. `.\gradlew.bat --no-daemon runClientRender`，視訊設定的「粒子」設為「全部」。
  4. 建立新的創造世界 W-R（允許作弊），蓋一座艙 C-R，拉桿 OFF；準備藥水與牛奶。
- 操作步驟（每個子項都截圖或錄影；Iris 預設 `K` 切換光影、`O` 開 shader pack 選單、`R` 重新載入 shader，以實際按鍵設定為準）：
  - B-2a 無光影、日夜：
    1. 確認光影關閉。`/time set day`，拉桿 ON，在艙外 5–20 格處觀察艙體外側四個直角與四面底部中點約 10 秒。
    2. `/time set night`，重複觀察；站在艙旁，讀 F3 左側 Client Light 的 block 值（玩家所在格），記下來。
  - B-2b 開光影、日夜：啟用 shader pack，重複 B-2a 的 1–2。
  - B-2c 光影下的走廊與重載：
    1. 光影開著，進艙、關門、喝藥入場（不要右鍵側門）。
    2. 在走廊看左右、回頭，走 20 格以上。
    3. 按 F3+T（重新載入資源）；再按 Iris 的 shader 重新載入鍵；再用切換鍵關閉、重新開啟光影。
    4. 喝牛奶返還，在原艙外再看一次輝光。
  - B-2d 自然淡出：拉桿 ON、艙外看得到輝光時扳到 OFF，觀察 5 秒。光影開、關各做一次。
  - B-2e 環境紀錄：F3 右側的 GPU 與 OpenGL 字串、顯示卡驅動版本、Sodium／Iris／shader pack 版本。只有一種 GPU 時，其他 GPU 組合記 N/A。
- 預期結果：
  - B-2a／B-2b：供電期間，艙體外側四角出現淡青色、向上飄、約 4 秒一次緩慢明滅的粒子，底部夾雜少量白色亮點；白天、夜晚都看得到（原生粒子，32 格內可見）。這只是原生粒子，艙旁的 block light 不會因輝光改變（沒有新增光源）；shader 可能讓粒子看起來較亮，但模組沒有 bloom 或 renderer。
  - B-2c：沒有崩潰、黑畫面，光影也沒有被強制關閉；走廊與艙體材質正確（量子艙門紫色、腔室控制器青色）；F3+T 與 shader 重新載入後，方塊材質與 HUD 的量子態效果圖示仍正常；返還後回到 `minecraft:overworld`。
  - B-2d：扳到 OFF、比較器變 0 後不再產生新粒子；既有粒子依原生壽命自然消失，不會一瞬間全部清空。
  - B-2e：紀錄完整。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-2a 無光影日夜輝光 |  |  |  | 單人 |  |  |  |
| B-2b 開光影日夜輝光 |  |  |  | 單人 |  |  |  |
| B-2c 光影下走廊與重載 |  |  |  | 單人 |  |  |  |
| B-2d 自然淡出 |  |  |  | 單人 |  |  |  |
| B-2e GPU／模組版本紀錄 |  |  |  | 單人 |  |  | GPU／驅動／Sodium／Iris／shader： |

### B-3 M1.1 維護手勢（依 M1.2 現行語意）

- 來源：[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 1、5–8 項；[M1.1 契約](../implementation-notes/m1.1-contract.md) §2–§3；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 J–M 與「6. 停用、重新啟用與拆除」；[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §1、§4。
- 語意說明：M1.1 原文的「重新啟用 held-high 不直接 11；新低→高才 11」與「停用後即可 Creative 左鍵拆 Controller」已被 M1.2 取代。本項依目前程式判定：重新啟用後只要仍供電並滿足資格就自動入場；拆除必須先斷電到 OFF，停用（`Enabled=false`）不等於斷電。
- 前置條件：接續 B-1a（C1 拉桿 ON）。B-1a 返還後等幾秒（見 2.5 (d)），在室內右鍵量子艙門開門、走到艙外，輸入 `/data get block <Controller 座標> ChamberUuid`，把輸出的整數陣列記進 B-3d 的備註（舊 UUID）。
- B-3a 停用（仍供電），操作步驟：
  1. 站在艙外，主手與副手都清空，按住 Shift 蹲下，右鍵 Controller 看得到的正面或頂面。
  2. F3 讀 `power`。
  3. 不蹲下，普通右鍵 Controller 兩次（開門、再關門）。
  4. 以創造模式左鍵一格艙體基岩；再左鍵 Controller。
  5. 開門進艙，從開口右鍵 Controller 關門，飲用量子態藥水，等 5 秒。
  6. 在室內右鍵量子艙門開門，走出艙外。
- B-3a 預期結果：
  - 步驟 1：actionbar 顯示「原始艙體已停用；仍供電或返還中會保留保護，斷電返還完成後可拆改。」
  - 步驟 2：`power` = 0。
  - 步驟 3：整面 25 格門照常開、關，actionbar 顯示「艙門已切換；量子功能已停用或結構無效。」
  - 步驟 4：基岩不會被移除；Controller 不會被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」
  - 步驟 5：不會入場（F3 仍是 `minecraft:overworld`）。
- B-3c 重新啟用，操作步驟（在 B-3a 之後）：
  1. 確認人在艙外、拉桿 ON、`power` = 0；量子態效果剩餘至少 1 分鐘（不足就補喝）。
  2. 雙手清空、蹲下，右鍵 Controller。
  3. F3 讀 `power`。
  4. 進艙、關門，最多等 5 秒；不扳拉桿、不再喝藥。
- B-3c 預期結果：
  - 步驟 2：actionbar 顯示「原始艙體已啟用；供電後補齊關門、玩家與藥水條件即可啟動。」
  - 步驟 3：`power` = 3（艙內無人）。
  - 步驟 4：自動入場（F3 `quantumchamber:superposition`），不需要拉桿新的低→高。留在走廊，接著做 B-1b。
- B-3b 持物蹲下不被維護攔截，操作步驟（在 B-1b 之後；此時 C1 已登錄但為 OFF，比較器 0）：
  1. 確認 `power` = 0 後，輸入 `/setblock <拉桿座標> minecraft:air` 移除 Controller 頂面的拉桿（若 B-1b 用的是計時電路，同樣把 Controller 正上方那一格清空）。**不要用創造模式左鍵去拆拉桿**：C1 此時已是 OFF，左鍵若打到 Controller 會直接把它拆掉。
  2. 手持拉桿，按住 Shift 蹲下，右鍵 Controller 頂面。
  3. 觀察 actionbar，F3 讀 `power`。
- B-3b 預期結果：拉桿放回 Controller 正上方（艙體外）；沒有出現「原始艙體已停用／已啟用」等維護訊息；`power` 仍是 0；拉桿保持 OFF。
- B-3d 斷電後拆除與重建，操作步驟（在 B-3b 之後）：
  1. 若門開著，先在艙外普通右鍵 Controller 把門關上（讓 25 格量子艙門回到可瞄準狀態）。
  2. 確認 `power` = 0。
  3. 以創造模式左鍵 Controller。頂面的拉桿會因失去支撐掉落，這是原生行為。
  4. 以創造模式左鍵一格艙體基岩（例如背牆），確認可移除後放回一格基岩。
  5. 在原位置放一個新的腔室控制器，朝向與原本相同（站在艙外正前方、面向艙體放置；或用 `/setblock <Controller 座標> quantumchamber:chamber_controller[facing=north]`，朝向依你的艙）。再手持拉桿、蹲下右鍵新 Controller 頂面，把拉桿放回去，保持 OFF。
  6. F3 讀 `power`；`/data get block <Controller 座標> ChamberUuid`；以創造模式左鍵一格基岩再放回。
  7. 拉桿扳到 ON；F3 讀 `power`；`/data get block <Controller 座標> ChamberUuid`。
- B-3d 預期結果：
  - 步驟 3：actionbar 顯示「控制器已拆除；艙體保護已解除。」，Controller 消失（這也完成 B-1b 的「OFF 後 Creative 可拆」）。
  - 步驟 4：基岩可以移除。
  - 步驟 6：`power` = 0；查詢顯示找不到 `ChamberUuid`（未註冊的草稿）；草稿可以用創造模式拆改。
  - 步驟 7：`power` = 3；`ChamberUuid` 是一組新的整數陣列，與舊 UUID 不同。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-3a 停用、保護保留、普通門控 |  |  |  | 單人 |  |  |  |
| B-3b 持物蹲下放置 |  |  |  | 單人 |  |  |  |
| B-3c 重新啟用（M1.2 語意） |  |  |  | 單人 |  |  |  |
| B-3d OFF 後拆除、草稿、新 UUID |  |  |  | 單人 |  |  | 舊 UUID：　新 UUID： |

### B-4 legacy schema1 舊房間

- 來源：[M1.1 契約](../implementation-notes/m1.1-contract.md) §4；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「核准行為」倒數第二項（`m1.1-origin-maintenance.md:13`）；[玩家指引](../implementation-notes/m1-player-build-verification.md)「6.」的「舊版世界」段；[README](../../README.md)「M1.2 基礎與歷史驗證」（schema1 讀為保守的 `UNKNOWN`）。
- 範圍：只驗 Chamber registry 為 schema1 的舊房間。M2 session journal 的舊 schema1／2 return-only 恢復已由 GameTest 與跨 JVM 證據涵蓋，要人工重現需要舊版 M2 build，不列入本項。
- 可用存檔調查（2026-09-24 唯讀檢查）：
  - `run/server/m1-smoke/data/quantumchamber_chambers.dat` 為 `SchemaVersion=1`，只有一筆 ORIGIN：anchor `103 106 100`、`NORTH`、`Enabled=1`、UUID `[-616379319, 1488932183, -1377398559, 266153682]`；SHA-256 與 M1 紀錄一致。
  - 該世界是超平坦、生存模式、未開作弊，出生點 `0 -60 0`；艙體 `100..106`（x、y、z），Controller 正上方 `103 107 100` 是紅石方塊（持續供電），門為關閉。
  - `run/client-base/saves/新的世界test (1)` 已是 schema2，不適用。
  - 結論：可以用 m1-smoke 的**複本**驗收，不需要舊版 build。複本無法使用時記 BLOCKED；若仍無法進行，由使用者決定記 N/A 或列入 E 段 waiver。
- 前置條件：
  1. 已完成 2.1 備份並核對 hash。
  2. 關閉所有客戶端與伺服器，從備份複製一份到 client-base（`$repo`、`$bak` 沿用 2.1 的定義；不要直接開 `run/server/m1-smoke`，用本 build 開過後 registry 會改寫成 schema2）：

     ```powershell
     Copy-Item -Recurse -LiteralPath "$bak\server-m1-smoke" -Destination "$repo\run\client-base\saves\QC-legacy-schema1"
     ```

  3. `start-client.bat` →「單人遊戲」→ 選 `m1-smoke`（清單顯示 level.dat 內的名稱；資料夾是 `QC-legacy-schema1`）。
  4. 進入後按 Esc →「在區域網路上開放」（Open to LAN）→「允許作弊」（Allow Cheats）設為開 → 開始。接著輸入 `/gamemode creative`、`/tp @s 103 106 95`，連按兩下空白鍵飛行。
  5. 在 Controller 北側擺比較器：`/setblock 103 105 99 minecraft:stone`、`/setblock 103 105 98 minecraft:stone`、`/setblock 103 106 99 minecraft:comparator[facing=south]`、`/setblock 103 106 98 minecraft:redstone_wire`；之後 F3 瞄準 `103 106 98` 讀 `power`。
- 操作步驟與預期結果：
  - B-4a 載入與身分：世界正常載入，沒有 registry 錯誤或拒絕啟動。輸入 `/data get block 103 106 100 ChamberUuid`，預期顯示 `[I; -616379319, 1488932183, -1377398559, 266153682]`（沿用舊 UUID）。
  - B-4b 依目前供電重新核對：F3 讀 `power`，預期 3（紅石方塊仍供電、艙內無人）。以創造模式左鍵 `100 100 100` 的基岩，預期不會被移除；左鍵 Controller，預期 actionbar 顯示「請先斷電並等待載入協調與返還完成。」
  - B-4c 斷電與復電：輸入 `/setblock 103 107 100 minecraft:air` 移除紅石方塊（艙體外），預期 `power` = 0、`ChamberUuid` 不變。輸入 `/setblock 103 107 100 minecraft:redstone_block` 復電，預期 `power` = 3、`ChamberUuid` 仍不變（重新供電沿用 UUID）。再輸入 `/setblock 103 107 100 minecraft:air`，回到 `power` = 0。用指令移除，是為了避免 OFF 時左鍵誤打到 Controller 而提前拆掉它。
  - B-4d 拆除與重建：`power` = 0 時以創造模式左鍵 Controller，預期 actionbar 顯示「控制器已拆除；艙體保護已解除。」輸入 `/setblock 103 106 100 quantumchamber:chamber_controller[facing=north]` 重建，預期 `power` = 0、查不到 `ChamberUuid`。輸入 `/setblock 103 107 100 minecraft:redstone_block`，預期 `power` = 3，`ChamberUuid` 是一組與舊 UUID 不同的新陣列。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-4a 載入與舊 UUID |  |  |  | 單人 |  |  |  |
| B-4b 供電保護重新核對 |  |  |  | 單人 |  |  |  |
| B-4c 斷電 OFF、復電沿用 UUID |  |  |  | 單人 |  |  |  |
| B-4d 拆除與重建新 UUID |  |  |  | 單人 |  |  | 新 UUID： |

### B-5 M4 spec §15 側門（最後執行）

- 來源：[M4 設計](../superpowers/specs/2026-09-21-m4-candidate-doors-design.md) §10、§11、§15；[M4 紀錄](../implementation-notes/2026-09-21-m4-candidate-doors.md)「Selection CAS、first-wins 與互動」「DORMANT 只封鎖自己的 Chamber（W-I2）」「人工驗收狀態（spec §15）」。
- 範圍界線：M4 只會看到「側門可被選擇一次、收到鎖定訊息、其他門被拒絕、門仍關閉、玩家仍在走廊」。「走廊消失、回原艙、門後是新世界」屬於 M5，不得把 M4 的中間狀態回報成原需求已完成。
- 前置條件：A、B-0～B-4、C 段都已做完（見 2.5 (a)）。在 `run/client-base` 建立新的創造世界 W-M4（允許作弊），蓋一座艙 C-M4，準備藥水與牛奶。
- 操作步驟：
  1. 拉桿 ON（`power` = 3）；進艙、關門、飲用藥水，入場後確認 F3 為 `quantumchamber:superposition`。
  2. 在走廊等至少 3 秒，再離開入口艙至少 8 格。走廊兩側牆上每 8 格有一扇 5×5 的紫色量子艙門（完整側門）；入口艙自己的正面門不是側門。
  3. 空手、不要蹲下，右鍵一扇完整側門（門 D1）的任一格。若完全沒有訊息，等 2 秒再試一次，並在備註記錄。
  4. 記錄玩家位置（F3 XYZ），試著走進 D1。
  5. 再右鍵 D1 一次。
  6. 右鍵另一扇完整側門 D2（另一面牆或下一站）。
  7. 喝牛奶。
  8. 回到原艙後等約 30 秒（清理與存檔 checkpoint，遊戲內沒有顯示）。
  9. 在室內再喝一瓶藥水（門仍關著），等 10 秒。
  10. 在室內右鍵量子艙門開門，走出艙外；F3 讀 `power`；在 32 格內觀察艙體四角約 10 秒。
  11. 拉桿扳到 OFF，等 5 秒，F3 讀 `power`。
  12. 以創造模式左鍵 Controller；再左鍵一格艙體基岩。
  13. （選測）在不重疊的位置另蓋一座艙 C-M4b，供電、進艙、關門、喝藥。
- 預期結果：
  - 步驟 3：actionbar 顯示「量子候選已鎖定，等待塌縮」。
  - 步驟 4：門仍關閉（實心，走不進去）；位置沒變，F3 仍是 `quantumchamber:superposition`；沒有傳送、沒有新世界。
  - 步驟 5：actionbar 顯示「候選已鎖定。」，門仍關閉。
  - 步驟 6：被拒絕，沒有第二次鎖定：通常顯示「候選已鎖定。」；若 D2 尚未寫入候選帳本，則沒有任何訊息（兩種都算拒絕，備註寫實際情況）。門仍關閉。
  - 步驟 7：回到 C-M4 室內（`minecraft:overworld`）。
  - 步驟 9：不會再入場（F3 仍是 `minecraft:overworld`），原艙已被封鎖。
  - 步驟 10：`power` = 11，而且不會回到 3 或 0（依程式推導：DORMANT 的 presence 為 `UNKNOWN`，協調時維持 RETURNING 並輸出 ARMED；自動測試沒有直接斷言這個比較器值，實際不同時照實記錄並標 FAIL 待判讀）；不再冒出新的輝光粒子（RETURNING 不發光，同屬推導）。
  - 步驟 11：`power` 仍是 11，不會進入 OFF。
  - 步驟 12：Controller 不會被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」；基岩不會被移除。
  - 步驟 13：C-M4b 可以正常入場（DORMANT 只封鎖自己的 Chamber）；之後喝奶返還即可。
- 完成後：W-M4 的 C-M4 會一直封鎖到 M5。這個世界不要再拿來做 M2 項目。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-5a 選擇一次與鎖定訊息（步驟 3） |  |  |  | 單人 |  |  |  |
| B-5b 同門再點與其他門被拒絕（步驟 5–6） |  |  |  | 單人 |  |  |  |
| B-5c 門仍關閉、玩家仍在走廊（步驟 4） |  |  |  | 單人 |  |  |  |
| B-5d 返還後原艙封鎖、無法再入場（步驟 7–9） |  |  |  | 單人 |  |  |  |
| B-5e 比較器與 LOW 不進 OFF、無法拆除（步驟 10–12） |  |  |  | 單人 |  |  | 實際 `power`： |
| B-5f （選測）另一座艙可入場（步驟 13） |  |  |  | 單人 |  |  |  |

## C. 多人待驗

共同前置：依 2.3 架好伺服器與 A、B、觀察者（OBS）三個客戶端；在 W-MP 蓋一座艙 C-MP，拉桿 OFF；OBS 站在艙外看得到第一格紅石粉。A、B 各有藥水至少 6 瓶與牛奶 2 桶，開始時都沒有量子態效果。全程不要右鍵側門。

### C-1 M1 多人資格判定

- 來源：[M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 4 項；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 9 項；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 G；[M2 設計](../superpowers/specs/2026-09-17-m2-powered-corridor-design.md) §3（凍結完整 cohort，不排除缺 Buff 的室內玩家）。
- C-1a 一人缺 Buff，操作步驟：
  1. OBS 把拉桿扳到 ON，讀 `power`。
  2. 先右鍵量子艙門（或 Controller）開門；A、B 進艙（碰撞箱完整在室內，都不是旁觀者），A 從開口右鍵 Controller 關門。
  3. 只有 A 喝藥，等 10 秒；OBS 讀 `power`；A、B 按 F3 看維度。
- C-1a 預期結果：步驟 1 為 3；步驟 3 仍是 3，沒有人入場（A、B 都是 `minecraft:overworld`）。
- C-1b 補齊後自動入場，操作步驟：B 喝藥（不扳拉桿、不開關門）；OBS 持續看 `power`；A、B 看 F3。
- C-1b 預期結果：幾秒內自動入場；OBS 看到 3 →（可能只短暫出現 7）→ 11；A、B 都在 `quantumchamber:superposition`，在走廊裡互相看得到。接著做 C-3a。
- C-1c 旁觀者排除，操作步驟（在 C-3a 返還後）：
  1. C-3a 返還後兩人都在艙內、門關著。B 喝牛奶清掉效果，再輸入 `/gamemode spectator`，留在艙內；A 沒有效果，也留在艙內。OBS 讀 `power`。
  2. 只有 A 喝藥，等 10 秒；OBS 讀 `power`；A、B 看 F3。
  3. A 喝牛奶返還；B 輸入 `/gamemode creative`。
- C-1c 預期結果：步驟 1 為 3；步驟 2 只有 A 入場（A 是 `quantumchamber:superposition`，B 留在 `minecraft:overworld` 的原艙內），OBS 讀 11；步驟 3 A 回到原艙。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-1a 一人缺 Buff 不入場 |  |  |  | 多人（2＋OBS） |  |  |  |
| C-1b 補齊後自動入場 |  |  |  | 多人（2＋OBS） |  |  |  |
| C-1c 旁觀者排除 |  |  |  | 多人（2＋OBS） |  |  |  |

### C-2 M1 多人跨程序重啟

- 來源：[M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 5 項、「Dedicated server 與重啟證據」最後一段；[M2 紀錄](../implementation-notes/m2-corridor.md)「持久化與安全界線」（重啟只恢復 pending 返還，不重建舊 ACTIVE；JOIN 先排隊、下一 server tick 返回）。
- 語意說明：M1 原文「保持 lever high 儲存／重開，含合格參與者時不出現假 edge」是 rising-edge 時代的語意。M1.2 起持續供電加上全員合格會直接自動入場，正常情況下不存在停在 READY 的合格狀態（只有 `start()` 回 `REJECTED` 時才會停在 READY，見 `ChamberPowerCoordinator.java:149`）。因此依目前程式改驗兩件事：(a) 持續供電但不合格的艙，跨重啟後不會自行入場；(b) 活動中的 session 跨重啟只做返還，不會在走廊重建舊 session。
- C-2a 不合格狀態跨重啟，操作步驟：
  1. C-MP 拉桿 ON；A、B 在艙內、門關。兩人先喝牛奶清掉殘留效果，再只讓 A 喝藥。OBS 讀 `power`；（選）`/data get block <Controller 座標> ChamberUuid` 記下。
  2. 主控台輸入 `stop`，等程序結束；再以完整指令重開，等到 `Done`，並確認世界名稱是 `QC-accept-mp`。**一定要帶 `--world QC-accept-mp`，不可開到 `m1-smoke`：**

     ```powershell
     .\gradlew.bat --no-daemon --console=plain runServer --args='nogui --world QC-accept-mp'
     ```

  3. **先讓 B 重新連線**（同名稱），確認 B 在艙內；之後 A、OBS 再連線。若 A 先上線而 B 還沒上線，艙內只剩合格的 A，會以 A 單人自動入場——這是正常行為，但本子項要重做。
  4. 等 10 秒；OBS 讀 `power`；（選）再查 `ChamberUuid`。
  5. B 喝藥。
- C-2a 預期結果：步驟 1、4 都是 3，重啟後沒有人入場，UUID 不變；步驟 5 不扳拉桿即自動入場，OBS 看到 3 →（7）→ 11。接著做 C-2b。
- C-2b 活動 session 跨重啟，操作步驟：
  1. A、B 在走廊（接 C-2a）；B 在走廊地上丟 1 顆鑽石。
  2. 主控台 `stop`，等程序結束；再以完整指令重開，等到 `Done`，並確認世界名稱是 `QC-accept-mp`。**一定要帶 `--world QC-accept-mp`，不可開到 `m1-smoke`：**

     ```powershell
     .\gradlew.bat --no-daemon --console=plain runServer --args='nogui --world QC-accept-mp'
     ```

  3. A 先連線，看 A 的位置與 F3；OBS 連線讀 `power`。
  4. A 喝牛奶。
  5. B 連線，看 B 的位置；OBS 讀 `power`；看艙內地面與背包。
- C-2b 預期結果：
  - 步驟 3：A 連線後的下一個伺服器 tick 被送回原艙室內（`minecraft:overworld`，可能先短暫出現在走廊），不會回到可以繼續行走的舊 session；B 還沒返還，`power` = 11；在 B 返還前，伺服器暫不接受 A 的移動與方塊互動（A 可能被拉回原位），但可以喝牛奶。
  - 步驟 4：A 的量子態效果消失。
  - 步驟 5：B 被送回原艙室內；兩人都返還並清理完成後 `power` = 3（A 沒有效果，不會自動再入場），A 恢復可以移動；鑽石出現在原艙室內（若被自動撿起，以背包數量核對）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-2a 不合格狀態跨重啟不自行入場 |  |  |  | 多人（2＋OBS） |  |  |  |
| C-2b 活動 session 跨重啟只返還 |  |  |  | 多人（2＋OBS） |  |  |  |

### C-3 M2-3 多人共享效果與離線

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 3 項；[M2 紀錄](../implementation-notes/m2-corridor.md)「持久化與安全界線」（離線者仍在凍結名單，必要租約與來源保護繼續持有）。
- C-3a 任一位喝奶即全組返還，操作步驟（接 C-1b，A、B 都在走廊）：
  1. B 在走廊地上丟 1 顆鑽石。
  2. A 喝牛奶。
  3. 看 A、B 的位置與效果；看艙內地面；OBS 讀 `power`。
- C-3a 預期結果：A、B 都在 1–2 秒內回到原艙室內（`minecraft:overworld`）；A 沒有效果；B 的量子態效果保留剩餘時間（不退款、不重置）；鑽石出現在原艙室內；清理完成後 `power` = 3（A 沒有效果）。
- C-3b 途中斷線，操作步驟（在 C-1c 之後）：
  1. A、B 都喝藥、關門，重新入場；B 在走廊丟 1 顆鑽石，記下 B 的背包內容與效果剩餘時間。
  2. B 按 Esc →「中斷連線」。
  3. 看 A 的位置；OBS 讀 `power`；A 喝牛奶（避免之後自動再入場）。
  4. 等 30 秒以上，B 用同一個名稱重新連線。
  5. 看 B 的位置、背包與效果；OBS 讀 `power`；看艙內地面。
- C-3b 預期結果：
  - 步驟 3：B 斷線本身就會觸發整組返還，A 在 1–2 秒內回到原艙室內；B 還沒返還，`power` = 11；伺服器暫不接受 A 的移動與方塊互動（A 可能被拉回原位），但可以喝牛奶。
  - 步驟 5：B 連線後的下一個 tick 被送回原艙室內（可能先短暫出現在走廊）；背包內容與斷線前相同；量子態效果保留斷線當下的剩餘時間；兩人都返還並清理後 A 恢復可以移動，`power` = 3；鑽石出現在原艙室內（或已被撿起）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-3a 喝奶全組返還 |  |  |  | 多人（2＋OBS） |  |  |  |
| C-3b 途中斷線、JOIN 完成返還 |  |  |  | 多人（2＋OBS） |  |  |  |

### C-4 M2-6 多人 96 格頁面 seam

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 6 項；[M2 設計](../superpowers/specs/2026-09-17-m2-powered-corridor-design.md) §4（96 格邏輯頁；近玩家共享映射、遠群體獨立實例；正常步行不得跌入 void 或看到端牆；有掉落物的頁面不回收）。
- 前置條件：A、B 各有藥水至少 4 瓶。任一人的效果到期都會讓整組返還，所以剩 1 分鐘左右就補喝。需要時依 2.3 第 6 步調高 `view-distance`。兩人一起入場。
- 操作步驟：
  - C-4a 近距離同群越界：兩人並肩（相距 5 格以內）沿走廊同一方向步行（不要飛）至少 250 格，每走約 100 格互看一次；再一起走回入口附近。
  - C-4b 分離與重聚：在入口附近地上丟 1 顆鑽石並記下位置；A 往一側、B 往另一側各走至少 200 格（相距 400 格以上），各自停 20 秒；再一起走回鑽石處會合。
  - C-4c 反方向：兩人一起往入口的另一側走至少 250 格，重複 C-4a 的觀察。
  - 結束：一人喝牛奶，整組返還。
- 預期結果：
  - C-4a、C-4c：地板、牆與側門外觀連續；沒有掉落、卡牆、看不見的牆或可見的端牆；對方的位置與動作同步，沒有瞬移、重疊或消失。F3 絕對座標若因實體 slot 重新定位而跳動，記錄即可，判定以畫面連續為準。
  - C-4b：會合時對方出現在正確位置，站在地板上，不懸空也不穿牆；鑽石仍在原處，可以撿起；走廊結構連續。
  - 結束：兩人都回到原艙室內（`minecraft:overworld`）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-4a 近距離同群越界 |  |  |  | 多人（2） |  |  | `view-distance`： |
| C-4b 分離與重聚 |  |  |  | 多人（2） |  |  |  |
| C-4c 反方向 |  |  |  | 多人（2） |  |  |  |

## D. 使用者回報已驗（build／日期待補）

使用者於 2026-09-24 回報下列項目已驗，但沒有記錄當時的 build 與日期。依使用者決定，這些項目不在目前 HEAD 重做；其中 M2-1、M2-2 另列入 A 段，待於目前 HEAD 快速複驗。本表只記錄「使用者回報已驗（build／日期待補）」，不代表已在目前 HEAD 通過。

| 項目 | 來源 | 使用者回報日期 | 當時 build | 備註 |
| --- | --- | --- | --- | --- |
| M2-1 單人原生飲用與左右入口 | [M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 1 項 | 2026-09-24 回報 |  | 列入 A-1，待於目前 HEAD 複驗（結果見 A-1） |
| M2-2 單人自然到期 | M2 八項第 2 項 | 2026-09-24 回報 |  | 列入 A-2，待於目前 HEAD 複驗（結果見 A-2） |
| M2-5 32 chunks／512 blocks 視距 | M2 八項第 5 項 | 2026-09-24 回報 |  | 未在目前 HEAD 重做 |
| M2-8 選用手持照明（含 M1.2 主手／副手火把） | M2 八項第 8 項；[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §7；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 N | 2026-09-24 回報 |  | 未在目前 HEAD 重做。2026-09-24 唯讀檢查：本工作區的 `run/client-light` 只有 `mods/`，沒有 `saves/`、`logs/` 與 `options.txt`；主 checkout 也沒有 `run/client-light`。補填時請確認當時是否在另一台電腦或其他 profile 驗的，並註明 profile 與世界；若不是 `start-client.bat light` 的選用照明環境，請改列 B 段重驗 |
| M1 GUI／HUD／瞄準（釀造、飲用與效果 HUD、25 格門與 Controller 瞄準開關） | [M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 1–2 項；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 2–3 項；玩家指引表 H | 2026-09-24 回報 |  | 未在目前 HEAD 重做 |
| M1 拉桿與比較器 3→7→11（held-high 不重觸發） | M1 紀錄「Client runtime 與人工驗收」第 3 項；M1.1「人工 gate」第 4 項；玩家指引表 A–C | 2026-09-24 回報 |  | 未在目前 HEAD 重做。M1 原文是 rising-edge 語意；M1.2 起持續供電加上全員合格即自動入場（玩家指引表 C） |
| M1 四向外觀與 `start-client.bat` 雙擊啟動 | M1 紀錄「2026-09-16 核准的門控與青紫造型 follow-up」「2026-09-16 Windows 啟動入口」 | 2026-09-24 回報 |  | 未在目前 HEAD 重做 |

補填參考：`run/client-base/saves/新的世界test (1)` 最後寫入時間為 2026-09-21 09:16，其 session journal 仍是 schema2（沒有在 M4 build 建立過 session）。這只能用來推估 build，不代表上表的驗收都在該世界進行。

以下舊清單條目已被後續 milestone 取代，不另外驗收：

- M1「全程沒有傳送、走廊或新 Dimension／Universe」與 M1.1「全程沒有穿越、走廊或 Universe／Dimension allocation」：M2 起就有走廊。
- M1「需新 edge 才 arm」、M1.1「重新啟用 held-high 不直接 11；新低→高才 11」：M1.2 起不需要 edge，改驗 B-3c。
- M1.1「停用後 Creative 左鍵 C 拆除」：M1.2 起必須先斷電到 OFF，改驗 B-3a 步驟 4 與 B-3d。
- M1.1「新建完工且未紅石啟動時可 Creative 拆改」併入 B-0 步驟 1 與 B-3d 步驟 6。

## E. Gate 結論

合併 `main` 前依序確認：

1. A、B、C 每個子項都是 PASS，或是 N/A 且備註寫明理由。需要使用者同意的 N/A 或 BLOCKED，記入下方 waiver 表。
2. D 段補填 build 與日期；無法補填時，由使用者決定如何處理並寫在 D 段備註。
3. 完成 [AGENTS.md](../../AGENTS.md)「下一步」第 3 步的 M2 整分支 final review，或由使用者明確記錄 waiver。
4. 記錄人工結果的同一個 docs commit，同步更新 AGENTS.md「下一步」第 2 步列出的狀態句。
5. 以上都完成後，才以 `git merge --ff-only` 合併 `main`，再依 AGENTS.md 第 4 步打 tag，並以獨立 docs commit 更新整合狀態。

任一子項 FAIL：回到對應 milestone 修正，不在本清單改判。修正後在新 HEAD 重跑 automated gates，並重驗受影響的人工項目（至少包含同一子項，以及走同一條程式路徑的子項）；新結果另起一列，不覆寫原本的 FAIL。BLOCKED 先排除環境問題再重驗；排除不了時，與 N/A 一樣由使用者決定。

Gate waiver（只有在使用者決定帶著未 PASS 的項目合併時才填）：每一項都要記錄，並依 AGENTS.md「使用者 2026-09-24 決定」第 3 項，標示 `main` 是開發快照、不適合未備份的正式世界。

| 項目 | 理由 | 日期 | 決定者 |
| --- | --- | --- | --- |
|  |  |  |  |

最終結論：

| 結論（可合併／不可合併） | 日期 | 決定者 | 依據 commit |
| --- | --- | --- | --- |
|  |  |  |  |

## 附錄：預期結果的程式依據

以 `752ada1` 為準；`main/` 代表 `src/main/java/dev/quantumchamber/`。程式變更後，預期結果要重新核對。

| 預期 | 依據 |
| --- | --- |
| 比較器只有 0／3／7／11 | `main/chamber/ChamberStatusSignal.java:7-13` |
| 供電空艙為 IDLE；停用為 INVALID；ARMING 為 READY、活動為 ARMED；返還未完成維持 ARMED | `main/chamber/ChamberPowerCoordinator.java:111-156`（`:117-126` 返還、`:139-150` 資格與入場） |
| B-0：草稿殼體無效或未供電時為 INVALID 且不註冊；殼體完整並供電才註冊（每 20 ticks 刷新）；已登錄殼體無效也是 INVALID | `main/chamber/ChamberPowerCoordinator.java:73-86`、`main/chamber/ChamberControllerBlock.java:115-122`、`main/chamber/ChamberActivationEvaluator.java:9-10` |
| B-0：未登錄位置不受保護；已登錄的殼體只有完成協調的 OFF 才可修改 | `main/chamber/ChamberProtectionService.java:94-106` |
| 正常情況下 READY 只出現在準備入場（ARMING／STAGING）；`start()` 回 `REJECTED` 時停在 READY | `main/chamber/ChamberPowerCoordinator.java:141`、`:146-150` |
| 資格：非旁觀者、碰撞箱完整在室內、全員有效果、門關 | `main/chamber/ChamberOccupantService.java:15-21`、`main/chamber/ChamberActivationEvaluator.java:9-16` |
| 入場需持續供電與玩家預檢；DORMANT 不佔用參與者 | `main/superposition/SuperpositionSessionManager.java:96-102`、`main/persistence/SessionRecoveryState.java:146-151`、`:246-250` |
| 關門訊息、停用後普通門控訊息 | `main/chamber/ChamberControllerBlock.java:85-94` |
| 空手蹲下右鍵才進維護；持物蹲下走原生放置 | `main/chamber/ChamberControllerBlock.java:73-75` |
| 停用／啟用訊息；拆除須 OFF；拆除成功訊息 | `main/chamber/ChamberMaintenanceInteraction.java:21-32`、`:55-67` |
| schema1 可讀、讀成 `UNKNOWN`；寫回 schema2 | `main/chamber/ChamberRegistryState.java:94`、`:134`、`:202-203` |
| 側門點擊由候選互動處理；鎖定與已鎖定訊息 | `main/chamber/QuantumBulkheadBlock.java:49-52`、`main/candidate/CandidateDoorInteraction.java:40-47` |
| 側門為兩側牆面、每 8 格一扇 5×5 | `main/corridor/CorridorGeometry.java:74-83` |
| DORMANT 原艙：presence 為 UNKNOWN、LOW 不能 OFF | `main/superposition/SuperpositionSessionManager.java:65-75`、`:291-303`、`main/corridor/CorridorPageManager.java:216-224` |
| 活動中效果失效即返還；MEASURED 走保留式返還 | `main/superposition/SuperpositionSessionManager.java:158-169` |
| 重啟時非 DORMANT 紀錄改為返還，不重建舊 session | `main/superposition/SuperpositionSessionManager.java:60-63`、`:345-353` |
| 斷線觸發整組返還；JOIN 下一 tick 返回；返還 pending 時擋移動與方塊互動 | `main/persistence/SessionRecoveryManager.java:41-65`、`:77-80`；`main/mixin/ServerPlayNetworkHandlerRecoveryMixin.java:17-29` |
| 走廊掉落物返還到原艙中央 | `main/persistence/SessionRecoveryManager.java:160-171`、`main/corridor/CorridorPageManager.java:994-1004` |
| 輝光只在 POWERED 時由原生粒子輸出，每 20 ticks 一次、4 秒呼吸 | `main/chamber/ChamberGlowEmitter.java:32-57`、`main/chamber/ChamberGlowPattern.java:9-39`、`main/chamber/ChamberControllerBlock.java:115-122` |
| 入口艙左右兩側開放 | `main/corridor/SessionEntranceAllocator.java:29-30` |
| 藥水效果 3600 ticks | `main/registry/ModPotions.java:15-18` |
| 建立 session 後 journal 寫成 schema3 | `main/persistence/SessionRecoveryState.java:171`、`:192` |
