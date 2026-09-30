# M1–M4 人工驗收清單

建立日期：2026-09-24；2026-09-29 依 M1–M4 整分支 final review 與整合修正（integration fix）更新。分支 `feature/m1-chamber`。

**程式基準（2.2 的 `$base`）：code HEAD `ba854e8`（`ba854e846482cf37bbb808bfc2df134090273969`），其後只有文件 commit。** 清單建立時的基準是 `752ada1`；integration fix rounds 1–3（`1f900f5..428f52a`）改了 production，所以基準改為 `428f52a`。2026-09-30 人工驗收途中發現「參與者朝向恰為 -0.0 時無法入場」，由 integration fix round 4（`ba854e8`，只改 `SessionRecoveryRecord.Participant`）修正，基準再改為 `ba854e8`。依 E 段「review 引起的程式變更」條款，已在 `d03bbc6` 通過的列都經 reviewer 判定未受影響，保留原 build（見 E 段簽註）。詳見附錄與 [M1–M4 整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)。基準 SHA 只由 docs commit 更新，本段、2.2 的 `$base` 與附錄必須一致。

2026-09-29 更新時，A、B、C 段的正式紀錄表都還沒有任何結果；只有 B-5 有一份在使用者自有世界做的預驗，另列成獨立表，不計入 E 段。2026-09-30，A、B、C 各子項都已記錄結果（見各表與 E 段）。

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
- [M1–M4 整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)「行為變更摘要」「對人工驗收的影響」（B-6、C-5 與各子項改寫的來源）

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
| B-6 | 整合修正：走廊內互動限制（入口正門、放置類物品、經驗球、肩上鸚鵡；另有兩個選測） | 單人 | W1 |
| C-1 | M1 多人資格判定 | 多人 | W-MP |
| C-2 | M1 多人跨程序重啟 | 多人 | W-MP |
| C-3 | M2-3 多人共享效果與離線 | 多人 | W-MP |
| C-4 | M2-6 多人 96 格頁面 seam | 多人 | W-MP |
| C-5 | （選測）整合修正：多人肩上鸚鵡顯示 | 多人 | W-MP |

世界代號：W1＝`run/client-base/saves/QC-accept-sp`、W-R＝`run/client-render/saves/QC-accept-render`、W-L＝`run/client-base/saves/QC-legacy-schema1`（m1-smoke 複本）、W-M4＝`run/client-base/saves/QC-accept-m4`、W-MP＝`run/server/QC-accept-mp`。名稱可自訂，但都必須是新建或複本，不可用既有世界。2026-09-30 實際使用的名稱：W1＝`QC-accept-W1`、W-R＝`QC-accept-WR`、W-L＝`QC-legacy-schema1-r4`（前三份複本不採計，見 B-4a）、W-M4＝`QC-accept-WM4`、W-MP＝`QC-accept-mp`（驗收後複製到 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-09-30\worlds\QC-accept-mp`）。

## 2. 驗收前準備

### 2.1 備份

先正常儲存並退出所有 Minecraft 客戶端與伺服器，再把下列資料複製到 repo 外：

- `run/client-base/saves/新的世界test (1)`：使用者自有世界，不是本清單的驗收世界。2026-09-29 使用者在其中做過 B-5 預驗，Controller 位於 `2 70 12` 的 Chamber 已留下 DORMANT receipt（見 2.5 (a)）。不得覆寫；除已記錄的 B-5 預驗外，不要拿來驗收。這裡備份到的是 B-5 預驗之後的狀態（schema3、含 DORMANT receipt）；測前沒有備份，2026-09-21 的 schema2 原狀態已無法復原。
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
    Copy-Item -Recurse -LiteralPath "$repo\run\server\logs" -Destination "$bak\server-logs" -ErrorAction Stop
    Copy-Item -Recurse -LiteralPath "$repo\run\client-base\logs" -Destination "$bak\client-base-logs" -ErrorAction Stop
    Get-FileHash -Algorithm SHA256 -LiteralPath "$bak\server-m1-smoke\data\quantumchamber_chambers.dat"
}
```

- 備份目錄已存在時會直接停止，避免之後重跑時用已被修改的檔案覆蓋備份；要重做備份請換一個新的目錄名，並同步修改後文用到的 `$bak`。
- 日誌也要先備份：`runServer`／`runClient` 每次啟動都會依 `.gradle/loom-cache/log4j.xml`（`DefaultRolloverStrategy max=5`、`OnStartupTriggeringPolicy`）輪替，刪除最舊的 debug log，驗收期間的啟動會把既有證據擠掉。
- 最後輸出的雜湊應為 `80ED28EDD4414A67945B0763130A4006154FBD9DFB01BF4683497676E6744CC9`，與 [M1 紀錄](../implementation-notes/m1-chamber.md) 的 registry SHA-256 相同。不同就先停下來確認來源。
- 後文的 `$repo`、`$bak` 都指這裡的定義；換了 PowerShell 視窗要重新設定這兩個變數（只設變數，不要重跑整段）。

`run/` 內其他目錄（`gametest*`、`m*-recovery-*`、`m12-*` 等）是自動 gate 證據，驗收期間不要開啟或修改。

### 2.2 Build 與啟動方式

確認 build（在工作區根目錄執行）：

```powershell
Set-Location 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
$base = 'ba854e8'   # 程式基準：只由 docs commit 更新，必須與本清單開頭一致
git status --short
git rev-parse --short HEAD
# production：會進 release JAR 或影響遊戲內行為的檔案
git diff --stat $base HEAD -- src/main src/client build.gradle gradle.properties settings.gradle gradle gradlew gradlew.bat start-client.bat
# test-only：只影響自動測試
git diff --stat $base HEAD -- src/test src/testmod
```

- 開始驗收前，`git status --short` 應該沒有輸出。開始填寫本清單後，只出現本清單檔案被修改屬正常；出現其他檔案就先停下來確認。
- 紀錄欄的 build 填 `git rev-parse --short HEAD` 的結果。
- production 指令沒有輸出：目前 HEAD 的 production 程式與 `$base` 相同（其後只有文件或測試 commit）。可以驗收。
- production 指令有輸出：遊戲內行為可能已變更，**停止驗收**。先在新 HEAD 重跑 automated gates，依 E 段「review 引起的程式變更」條款判斷哪些子項要重驗，再以 docs commit 更新 `$base` 與清單開頭。
- 只有 test-only 指令有輸出：只有測試碼變更，不影響遊戲內行為，不必停止驗收；在該列備註寫明（例如「test-only diff：`src/testmod/...`」）。這種情況下自動 gate 仍要在新 HEAD 重跑，但那是自動 gate 的工作，不是本清單的前提。

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
6. C-4 需要較遠的視距時，在伺服器停止時把 `view-distance` 從 3 調高（例如 10）。dedicated server 啟動時先讀取 `server.properties` 再寫回，執行中修改不會立即生效；部分指令（例如 `/whitelist on`、`/whitelist off`）也會在執行中重寫這個檔案，所以一律在停機時修改。
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

- **(a) M4 側門會永久封鎖原艙（到 M5 為止）。** 右鍵走廊側門鎖定候選後，該 session 返還時會留下 DORMANT receipt：那座原艙在該存檔裡無法再入場，也無法斷電拆除，遊戲內沒有解除方法。B-5 必須用另一座 Chamber 或另一個測試世界（建議 W-M4），並且在所有 M2 項目（A、B-1、B-2、B-6、C-3、C-4、C-5）都驗完之後才做。A、B-0～B-4、B-6、C 段全程都不要右鍵走廊兩側的側門（手上拿著物品時右鍵側門，同樣會鎖定候選）。
  - **若已在非測試世界點過側門**：
    1. 記下世界、Chamber 座標與朝向、build、日期（寫進 B-5 預驗表或該列備註）。
    2. 不得用指令或 NBT 編輯去解除封鎖；那會破壞 M5 要接手的 receipt。
    3. 之後這個世界只能用 ≥`752ada1` 的本分支 build 開啟。不可用不含 M4 的 build（例如合併前的 `main`）開啟。
    4. 立即執行 2.1 備份，並在備份目錄或備註註明「這是 B-5 之後的狀態」。
    5. 記下該世界 `data/quantumchamber_sessions.dat` 的 SHA-256，作為 M5 migration 的真實 DORMANT 樣本：`Get-FileHash -Algorithm SHA256 -LiteralPath "<世界資料夾>\data\quantumchamber_sessions.dat"`。
    6. 這座 Chamber 不得用於 A／B／C 段。
  - 目前已知的一例：使用者自有世界 `run/client-base/saves/新的世界test (1)`，Controller 位於 `2 70 12`、朝 NORTH 的 Chamber，2026-09-29 以 build `1f900f5`（code 等同 `752ada1`）點過側門（見 B-5 預驗表）。2026-09-29 由 implementer 唯讀計算：`data/quantumchamber_sessions.dat` 41144 bytes、最後寫入時間 2026-09-29 09:25:55（+08:00），SHA-256 `268F249E77CA9716F1B7DD048AFF0BFD49AF0FFA712E6B43B34F833EF1306534`。截至 2026-09-29 的清單更新，2.1 的備份目錄尚不存在；備份後請核對備份內這個檔案的 SHA-256 與上值相同。2.1 備份已於 2026-09-29 16:03 完成（`C:\Users\Ben\Documents\QC-acceptance-backup-20260924`），備份內這個檔案的 SHA-256 與上值相同。2026-09-30 10:51:33–10:51:41，這個世界在本機被以玩家 QC_A 開啟約 8 秒後退出（非驗收操作）。之後由 controller 唯讀核對：模組的四個資料檔（`quantumchamber_sessions.dat`、`_chambers.dat`、`_candidate_entropy.dat`、`_universe_discovery.dat`）的 SHA-256 都與 2.1 備份一致；被原版存檔改寫的只有 `level.dat`、`data/raids.dat`，以及新增的玩家檔 `playerdata/f69ac82e-718b-3c37-bba5-1edbfda2ad69.dat`。
- **(b) 不可降版。** 用本 build 在某個存檔建立過任何走廊 session 後，該存檔的 `data/quantumchamber_sessions.dat` 會寫成 schema3，並新增 `quantumchamber_candidate_entropy.dat` 與 `quantumchamber_universe_discovery.dat`；M4 之前的 build 讀不了 schema3，會 fail closed。Chamber registry 也會寫成 schema2，M1.2 之前的 build 讀不了。驗收用的存檔不要再用舊 build 開，也不要拿正式世界驗收。
- **(c) 返還後要等全員收尾才能開新 session。** 已返還的玩家可以正常移動與互動（見 (d)），但要等該 session 全員返還、清理完成之後，才能再從任何 Chamber 開新 session；M4 選過側門的 session 要到 DORMANT，之後只能用其他 Chamber。返還與清理階段（含 M4 的 `RETURN_PLAYERS`／`RELEASE_GEOMETRY`）開新 session 仍會被拒絕。
- **(d) 返還期間只凍結尚未返還者（2026-09-29 整合修正起）。** 已返還的玩家回到原艙後，立刻可以正常移動、開門與操作方塊，不必等待。仍未返還的參與者（例如離線者）的移動、載具與方塊互動封包會被伺服器擋下（`SessionRecoveryManager.blocks`），直到自己返還；離線者重新連線後的下一個 tick 才返還。在全員返還並清理完成之前，原艙保護與比較器 11 維持不變，已返還的玩家也不能加入新 session。喝東西等使用物品不受影響。
- **(e) 準備期中止。** READY（比較器 7）的準備期間，若資格失效（例如有人開門、有人的 Buff 失效），session 會中止並返還：只有本 session 已嘗試移動、或目前在走廊世界的成員會被拉回原位；仍在原艙內的成員保持原地，不再被拉回入場時的凍結位置。已知例外（延後 M5）：若成員在準備期用終界珍珠或歌萊果離開原艙，返還時仍會被送回原艙返還位。
- 單人測試途中不要退出世界；需要重開時，在備註記錄。

### 2.6 建議執行順序

1. 2.1 備份、2.2 確認 build。
2. W1（C1 一座艙）：B-0 → A-1 → A-2 → B-1a → B-3a → B-3c → B-1b → B-3b → B-3d → B-6a → B-6b →（選測）B-6c → B-6d → B-6e →（選測）B-6f。
3. W-MP（2.3）：C-1a → C-1b → C-3a → C-1c → C-3b → C-4 → C-2 →（選測）C-5。
4. B-2（W-R）與 B-4（W-L），順序不限。
5. 最後做 B-5（W-M4）。

## 3. 紀錄欄位說明

每個子項各記一列：

| 欄位 | 填法 |
| --- | --- |
| 日期 | `YYYY-MM-DD`（Asia/Taipei） |
| 驗收者 | 操作的人；多人項目列出所有參與者 |
| build（commit SHA） | 2.2 的 `git rev-parse --short HEAD` 結果；production 程式必須等同當時的 `$base`（目前 `ba854e8`，見 2.2）。基準因 review 引起的程式變更而更新時，未受影響的舊列依 E 段條款保留 |
| 單人／多人 | 多人時註明玩家數與是否有觀察者 |
| 結果 | `PASS`：所有預期都成立。`FAIL`：任一預期不成立，備註寫實際現象。`BLOCKED`：環境或前置條件無法完成（例如 Iris 無法載入）。`N/A`：不適用，必須寫理由；需要使用者同意的，另記入 E 段 waiver |
| 證據 | 截圖或影片的完整路徑，建議放在 repo 外（例如 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-09-24\`）。F2 截圖預設存在各 profile 的 `screenshots/`（例如 `run/client-base/screenshots/`），驗收後複製出去 |
| 備註 | 實際讀值、訊息原文、替代做法（例如以指令代替計時器）、模組與 GPU 版本 |

同一子項重驗時新增一列，不覆寫舊紀錄。

2026-09-30 的 W1 各列（B-0、A-1、A-2、B-1、B-3、B-6）由 Claude 經使用者授權代為操作：在本機以鍵盤滑鼠自動輸入驅動開發客戶端（`runClient --username QC_A --width 1280 --height 720`），用截圖與唯讀指令（`/data get`、`/execute if`）讀值，並核對 `run/client-base/logs/latest.log` 的 `[CHAT]` 與模組紀錄。世界為 `run/client-base/saves/QC-accept-W1`（超平坦、創造模式、允許作弊），C1 以 2.4 的指令建在 `0 -61 20`。`power` 一律以 `/data get block 3 -55 19 OutputSignal`（比較器輸出）讀取，部分附 F3 截圖。驗收時把客戶端的「原始輸入」（`rawMouseInput`）關閉，以便自動輸入轉動視角；這只影響滑鼠輸入方式。聲音無法由自動化確認，相關項目以指令讀值代替。證據目錄：`C:\Users\Ben\Documents\QC-acceptance-evidence\2026-09-30\`（下表只列檔名）。

## A. 目前 HEAD 快速複驗

M2-1、M2-2 使用者已回報驗過（見 D 段），但 M4 之後入場與返還路徑有改動：`start()` 在任何 reservation 之前先做跨 session 玩家預檢並凍結 candidate context，返還路徑也新增 `MEASURED` 分支（見 [M4 紀錄](../implementation-notes/2026-09-21-m4-candidate-doors.md)「Discovery authority 與 policy snapshot」「MEASURED freeze 與 retained recovery」）。2026-09-29 的整合修正又在 `start()` 加入原生 checkpoint 能力預檢，並改為返還期間只凍結尚未返還者（見 [整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)「行為變更摘要」）。因此在目前 HEAD 以精簡步驟複驗。

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
- 平台說明（2026-09-29 整合修正）：本機 Windows、playerdata 位於本機固定 NTFS 是正常路徑。若步驟 5 沒有入場、比較器停在 7，先查 `run/client-base/logs/latest.log` 有沒有「玩家原生 checkpoint 能力預檢未通過，拒絕入場」WARN。非 Windows，或 playerdata 不在本機固定 NTFS（網路磁碟機、非 NTFS、卸除式磁碟、路徑上有 junction 或其他 reparse point，例如部分雲端同步資料夾）時，`start()` 會在任何預留、效果快照或移動之前拒絕，原艙停在 READY（比較器 7），同一原因只記一次 WARN。這不是本項的預期環境：記 BLOCKED 並附 log。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-1 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `110852-a1-s1-wire.png`、`110948-a1-s3-closed.png`、`111012-a1-s4-noeffect.png`、`111845-a1r-left.png`、`111846-a1r-right.png`、`111846-a1r-back.png`、`111925-a1r-corridor-west.png`、`111926-a1r-corridor-east.png` | 步驟 1：3。步驟 3：actionbar「已供電保護；請關門、進艙並讓全員取得 QuantumState。」。步驟 4：喝前沒有效果；創造模式不消耗藥水。步驟 5：喝完約 2 秒入場（11:10:16，session `ec86f9a4`），F3 顯示 `quantumchamber:superposition`。步驟 6：第一個 session 因輸入工具轉不了視角（原始輸入），只完成 E 畫面與 F3 維度。左、右、回頭與走動改在 B-1a 的 session（`e0f7b507`）補看：入口艙左右兩側打開，走廊往東西延伸，走動與碰撞正常；效果 3207→3087 tick 持續遞減，沒有重置。3:00 由其他次飲用後 `/data` 讀到的 3566–3570 tick 佐證。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

### A-2 M2-2 單人自然到期

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 2 項；[單人無遊戲指令流程](../implementation-notes/m2-corridor.md#單人無遊戲指令流程) 第 6 步。
- 前置條件：接續 A-1，玩家在走廊、效果仍在，拉桿維持 ON。
- 操作步驟：
  1. 不補喝、不喝奶，等量子態效果自然倒數到 0（約 3 分鐘）。
  2. 效果消失後觀察 1–5 秒。
  3. 按 F3 看維度與座標；按 E 看效果；確認背包沒有多出藥水。
  4. 在室內右鍵量子艙門開門，走到艙外，F3 讀 `power`。
  5. 以創造模式左鍵一格艙體基岩，再左鍵 Controller。
- 預期結果：
  - 步驟 2：自動回到原世界、同一座原艙的室內；回到原艙後可以立即移動與開門，不必等待（見 2.5 (d)）。
  - 步驟 3：F3 維度為 `minecraft:overworld`、座標在 C1 室內；沒有量子態效果，也沒有退回藥水。
  - 步驟 4：`power` = 3（仍供電、艙內沒有合格玩家）。若返還後立刻讀值，清理完成前可能短暫仍是 11（見 2.5 (d)），幾秒內應變成 3。
  - 步驟 5：基岩不會被移除（畫面可能閃一下再恢復）；Controller 不會被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-2 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `111615-a2-s3-f3.png`、`111616-a2-s3-inv.png`、`111739-a2-s5-ctrl-clean.png`；latest.log 11:14:28 | session `ec86f9a4` 於 11:14:28 自然到期，整組返還（log「正常結束，非故障」）。F3：`minecraft:overworld`，XYZ 5.5／-60／21.5（C1 室內）。沒有效果；藥水仍是 4 瓶，沒有退款。返還後立即可開門走出。艙外 3。左鍵基岩 `0 -58 23` 未被移除。左鍵 Controller 未被拆除，actionbar「請先斷電並等待載入協調與返還完成。」。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

## B. 單人待驗項目（2026-09-30 已記錄結果）

### B-0 殼體不完整＝INVALID＝比較器 0（W1 第一個執行）

- 來源：[M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 4 項前半（Invalid shell=0、有效但條件不足=3）；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 A、B、F；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 1 項（未紅石啟動的新艙可 Creative 拆改）。
- 為什麼要在草稿階段做：已登錄的原艙只要不是已完成協調的 OFF，殼體就受保護，方塊拆不掉也放不回去；尚未登錄的草稿不受保護。玩家指引表 F 也是「另建尚未登錄的測試艙、少放一格基岩」，並提醒不要拆已受保護的艙。因此這一項要在 C1 第一次供電之前做。
- 前置條件：W1 的 C1 剛蓋好（手動施工或 2.4 指令），拉桿 OFF，從未供電；`/data get block <Controller 座標> ChamberUuid` 查不到這個欄位。
- 操作步驟：
  1. 以創造模式左鍵拆掉一格殼體基岩，例如背牆正中央（2.4 範例為 `3 -58 26`）。不要拆 Controller、量子艙門或拉桿。
  2. 拉桿扳到 ON，等 3 秒。F3 讀 `power`；輸入 `/data get block <Controller 座標> ChamberUuid`。
  3. 在同一個位置放回一格基岩，等 3 秒。F3 讀 `power`；再查一次 `ChamberUuid`。
  4. 拉桿扳到 OFF，等 3 秒，F3 讀 `power`；再輸入 `/data get block <Controller 座標> ChamberUuid`。
- 預期結果：
  - 步驟 1：基岩可以移除（草稿不受保護）。
  - 步驟 2：`power` = 0（已供電但殼體不完整，INVALID）；查不到 `ChamberUuid`（殼體無效時不會註冊）。
  - 步驟 3：`power` = 3（殼體完整、已供電、艙內無人，IDLE）；`ChamberUuid` 出現一組整數陣列（這時才註冊並開始保護）。
  - 步驟 4：`power` = 0（OFF）；`ChamberUuid` 與步驟 3 的陣列相同（斷電保留 UUID）。接著做 A-1。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-0 殼體不完整＝INVALID＝0，補回後 3 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `110201-b0-s1-broken.png`、`110717-b0-s3b-wire.png`、`110740-b0-s4-wire.png`；latest.log `[CHAT]` 讀值 | 步驟 1 拆 `3 -58 26`，可拆。輸入工具校正前的一次誤擊另拆掉 `3 -56 26`，當時沒發現。步驟 2：0，查無 `ChamberUuid`。步驟 3：只補 `3 -58 26` 時仍是 0（殼體仍缺 `3 -56 26`，符合 INVALID）；使用者看畫面指出缺口，補回後 3 秒內變成 3，`ChamberUuid` 為 `[I; -248379906, -1749663311, -1475179135, 2075138472]`。步驟 4：0，UUID 相同。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

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
  5. 在室內右鍵量子艙門開門，走出艙外，F3 讀 `power`；在 32 格內觀察艙體外側四角約 10 秒。
- B-1b 預期結果：
  - 步驟 3：自動回到 C1 室內（`minecraft:overworld`），可以立即移動與開門（見 2.5 (d)）。
  - 步驟 4：量子態效果保留剩餘時間（LOW 返還不移除、不退款）；走廊裡的鑽石出現在 C1 室內地面中央附近（若被自動撿起，背包數量會回到丟之前）。走廊裡若有經驗球，同樣會被送到原艙中央（專項見 B-6d）。
  - 步驟 5：`power` = 0（OFF）；若返還後立刻讀值，清理完成前可能短暫仍是 11，幾秒內應變成 0。艙體不再冒出新的輝光粒子。保護解除在 B-3d 步驟 3 以「可拆除」確認。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-1a HIGH 再入場 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `111820-b1a-s1-closed.png`、`112005-b1a-s3-returned.png`；latest.log 11:18:25、11:20:02 | 沒有扳拉桿，喝藥後約 1 秒再次入場（11:18:25，session `e0f7b507`）。喝牛奶後於 11:20:02 返還 C1 室內（5.5, -60, 21.5），`minecraft:overworld`，沒有效果。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-1b 走廊中 LOW 安全返還→OFF | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `113222-b1b-s2-dropped.png`、`113253-b1b-s4-f3.png`、`113420-b1b-s5-corners-t5.png`、`113424-b1b-s5-corners-t10.png`、對照 `114008-b3d-s7-powered-t3.png` | 斷電方式：(ii) `/execute in minecraft:overworld run setblock 3 -54 20 minecraft:lever[face=floor,facing=north,powered=false]`（11:32:49）。丟鑽石後背包 5→4，效果剩 2357 tick。斷電後 5 秒內查得人在 C1 室內（5.5, -60, 21.5），`minecraft:overworld`。效果保留（1716 tick）。鑽石掉落物在（3.5, -60, 23.5），即室內中央；尚未被撿起，背包仍是 4。立即可開門走出，艙外 0。在 8 格外觀察約 10 秒，沒有新輝光粒子（供電時的對照截圖可見粒子）。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

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
| B-2a 無光影日夜輝光 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `150153-QC_OBS-b2a-day-t0.png`、`150157-QC_OBS-b2a-day-t4.png`、`150201-QC_OBS-b2a-day-t8.png`、`150229-QC_OBS-b2a-night-t4.png`、`150325-QC_OBS-b2a-night-close-t2.png`、`150236-QC_OBS-b2a-night-f3.png` | W-R 為 `run/client-render/saves/QC-accept-WR`（超平坦），C-R 以 2.4 指令建於 `0 -61 20`。粒子設定為「全部」（`particles:0`）。Iris 已安裝，但 shaderPack 為空（光影未載入）。拉桿 ON 後讀值 3。白天在約 12 格處觀察 10 秒：四個直角有淡青色粒子，底部有白色亮點。夜晚在 12 格與 6 格處都看得到白點；淡青色粒子在夜晚較暗，但仍可見。艙旁 F3 Client Light：15（15 sky, 0 block）。 |
| B-2b 開光影日夜輝光 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `150458-QC_OBS-shader-on-check.png`、`150524-QC_OBS-b2b-day-t4.png`、`150537-QC_OBS-b2b-night-t4.png`、`150540-QC_OBS-b2b-night-f3.png` | 以 Iris 選單（O）啟用 ComplementaryReimagined r5.9.3。套用時 log 有數則 Iris WARN（shader 引用 `BIOME_PALE_GARDEN`、`BIOME_SULFUR_CAVES` 等 1.21 沒有的 biome 變數，無法解析 uniform；block ID map 的 `stone_slab variant`），屬光影包與版本的相容性訊息，無 ERROR，光影正常生效。白天：四角淡青色粒子與底部白點都可見，有光影陰影與霧。夜晚：底部白點清楚。艙旁 F3 Client Light：15（15 sky, 0 block），與無光影時相同。 |
| B-2c 光影下走廊與重載 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `150729-QC_OBS-b2c-front.png`、`150730-QC_OBS-b2c-left.png`、`150743-QC_OBS-b2c-walked.png`、`150808-QC_OBS-b2c-nv-materials.png`、`150817-QC_OBS-b2c-after-f3t.png`、`150825-QC_OBS-b2c-after-iris-r.png`、`150829-QC_OBS-b2c-shader-off.png`、`150837-QC_OBS-b2c-shader-on-again.png`、`150910-QC_OBS-b2c-inv-effect.png`、`151001-QC_OBS-b2c-s4-glow-t3.png` | 光影開著喝藥入場（session `91b60312`），看左右與回頭，走約 26 格；沒有崩潰或黑畫面，光影也沒有被強制關閉。走廊本身無光源，為了看材質給了夜視：量子艙門為紫色，牆與地板為基岩。F3+T（「已重新載入資源包」）、R（「已重新載入光影！」）、K 關閉（「已停用光影！」）、K 再開（「使用光影：ComplementaryReimagined_r5.9.3.zip！」）之後，材質都正常；E 畫面右側仍有量子態效果圖示，效果 1754 tick。喝牛奶後返還 `minecraft:overworld`（5.5, -60, 21.5）。走到艙外，輝光照常出現（門開著）。 |
| B-2d 自然淡出 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | 光影開：`151034-QC_OBS-b2d-on-before.png`、`151037-QC_OBS-b2d-on-t0.png`、`151038-QC_OBS-b2d-on-t1.png`、`151042-QC_OBS-b2d-on-t5.png`；光影關：`151117-QC_OBS-b2d-off-before.png`、`151120-QC_OBS-b2d-off-t1.png`、`151124-QC_OBS-b2d-off-t5.png` | 以 `/setblock 3 -54 20 minecraft:lever[face=floor,facing=north,powered=false]` 扳到 OFF，之後讀值 0。光影開、關各做一次：OFF 後 1 秒粒子明顯減少、只剩少數，5 秒時全部消失；沒有新粒子，也不是一瞬間全部清空。光影關的那次，先以 setblock 把拉桿設為 ON，等 6 秒讀值 3、粒子出現後，才扳到 OFF。 |
| B-2e GPU／模組版本紀錄 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `150236-QC_OBS-b2a-night-f3.png`；`run/client-render/logs/latest.log` | GPU／驅動／Sodium／Iris／shader：NVIDIA GeForce RTX 4090/PCIe/SSE2，OpenGL 3.2.0 NVIDIA 591.86，Windows 驅動 32.0.15.9186（2026-01-20），LWJGL 3.3.3。Sodium `sodium-fabric-0.6.13+mc1.21.1.jar`（SHA-256 `e04599514d88e41765f710eeba59e3814909833396bc11b977f48fc2e7e50353`）；Iris `iris-fabric-1.8.8+mc1.21.1.jar`（`b5ad39a6cb113ca0a1765f06689f9f4b29ccd0c81247de90b06c42f5da1092f5`）；shader `ComplementaryReimagined_r5.9.3.zip`（`fed6c879e732cf7fc7bc6570ebb1def43235aa7e96f62859fbdbdbfa0b484b38`）。三者都從 Modrinth 下載，SHA-512 與 Modrinth API 公布值相符，Modrinth 標示支援 1.21。啟動 log：`Detected optional mod compatibility: sodium=true, iris=true, immersive_portals=false`。Sodium 對 NVIDIA 驅動自動套用了 `NVIDIA_THREADED_OPTIMIZATIONS_BROKEN` workaround（WARN）。只有這一種 GPU，其他 GPU 組合記 N/A。 |

### B-3 M1.1 維護手勢（依 M1.2 現行語意）

- 來源：[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 1、5–8 項；[M1.1 契約](../implementation-notes/m1.1-contract.md) §2–§3；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 J–M 與「6. 停用、重新啟用與拆除」；[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §1、§4。
- 語意說明：M1.1 原文的「重新啟用 held-high 不直接 11；新低→高才 11」與「停用後即可 Creative 左鍵拆 Controller」已被 M1.2 取代。本項依目前程式判定：重新啟用後只要仍供電並滿足資格就自動入場；拆除必須先斷電到 OFF，停用（`Enabled=false`）不等於斷電。
- 前置條件：接續 B-1a（C1 拉桿 ON）。B-1a 返還後，在室內右鍵量子艙門開門、走到艙外，輸入 `/data get block <Controller 座標> ChamberUuid`，把輸出的整數陣列記進 B-3d 的備註（舊 UUID）。
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
| B-3a 停用、保護保留、普通門控 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `112052-b3a-s1-msg.png`、`112137-b3a-s3-click1.png`、`112152-b3a-s3-click2.png`、`112256-b3a-s4-ctrl.png`、`112347-b3a-s5-after7s.png` | 步驟 1：actionbar「原始艙體已停用；仍供電或返還中會保留保護，斷電返還完成後可拆改。」。步驟 2：0（`ChamberState` INVALID，UUID 保留）。步驟 3：兩次右鍵依序把門關上、再打開（抽查 3 格 `open` 狀態），actionbar「艙門已切換；量子功能已停用或結構無效。」。步驟 4：基岩 `0 -58 23` 未被移除；Controller 未被拆除，actionbar「請先斷電並等待載入協調與返還完成。」。步驟 5：關門、喝藥、等 7 秒，仍在 `minecraft:overworld`，門保持關閉。步驟 6：開門走出。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-3b 持物蹲下放置 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `113519-b3b-s2-msg.png` | 先確認 0，再以 `/setblock 3 -54 20 minecraft:air` 移除拉桿。手持拉桿、蹲下右鍵 Controller 頂面後，拉桿放回 `3 -54 20`（`face=floor,powered=false`）。沒有出現維護訊息；仍是 0。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-3c 重新啟用（M1.2 語意） | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `112433-b3c-s2-msg.png`、`113152-b3c-s4-closed.png`、`113156-b3c-s4-t4.png`；latest.log 11:31:52 | 步驟 1：人在艙外，拉桿 ON，0，效果約 2:20（11:23:49 為 3399 tick）。步驟 2：actionbar「原始艙體已啟用；供電後補齊關門、玩家與藥水條件即可啟動。」。步驟 3：3（IDLE）。這次讀值延後到 11:31：蹲下用的 Shift 讓注音輸入法切到中文模式，遊戲按鍵被攔截約 6 分鐘，屬輸入工具問題，與模組無關。因此效果已到期，照步驟 1 在艙外補喝一瓶。步驟 4：進艙、關門，11:31:52 自動入場（session `1f6a9ffe`），沒有扳拉桿，也沒有再喝藥。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-3d OFF 後拆除、草稿、新 UUID | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `113736-b3d-s1-close2.png`、`113759-b3d-s3-removed.png`、`113841-b3d-s4-broken.png`、`113914-b3d-s5-lever.png`、`114008-b3d-s7-powered-t3.png` | 舊 UUID：`[I; -248379906, -1749663311, -1475179135, 2075138472]`　新 UUID：`[I; 21786211, 1660506000, -1768085279, 1112635999]`。步驟 1：門原本開著；第一次瞄準偏離沒有關上（比較器模式未變），重新右鍵 Controller 後關上，actionbar「艙門已切換；目前未供電。」。步驟 2：0。步驟 3：actionbar「控制器已拆除；艙體保護已解除。」，拉桿掉落。步驟 4：基岩可拆，也可放回。步驟 5：以 `/setblock 3 -55 20 quantumchamber:chamber_controller[facing=north]` 放回 Controller，蹲下放回拉桿（OFF）。步驟 6：0，查無 `ChamberUuid`，基岩可拆可放。步驟 7：3，新 UUID 與舊的不同。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

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
     $legacy = "$repo\run\client-base\saves\QC-legacy-schema1"   # 重驗時改用新資料夾名，例如 QC-legacy-schema1-r2
     if (Test-Path -LiteralPath $legacy) {
         throw "目的資料夾已存在；重驗請改用新資料夾名（例如 QC-legacy-schema1-r2）"
     } else {
         Copy-Item -Recurse -LiteralPath "$bak\server-m1-smoke" -Destination $legacy -ErrorAction Stop
         Get-FileHash -Algorithm SHA256 -LiteralPath "$legacy\data\quantumchamber_chambers.dat"
     }
     ```

     輸出的雜湊必須是 `80ED28EDD4414A67945B0763130A4006154FBD9DFB01BF4683497676E6744CC9`；不符就停止，不要開啟這份複本。已經開過的複本會被改寫成 schema2，重驗一律用新資料夾名重新複製，不要重用舊複本。
  3. `start-client.bat` →「單人遊戲」→ 選 `m1-smoke`（清單顯示 level.dat 內的名稱；資料夾是 `QC-legacy-schema1` 或你重驗用的新名稱）。
  4. 進入後按 Esc →「在區域網路上開放」（Open to LAN）→「允許作弊」（Allow Cheats）設為開 → 開始。接著輸入 `/gamemode creative`、`/tp @s 103 106 95`，連按兩下空白鍵飛行。
  5. 在 Controller 北側擺比較器：`/setblock 103 105 99 minecraft:stone`、`/setblock 103 105 98 minecraft:stone`、`/setblock 103 106 99 minecraft:comparator[facing=south]`、`/setblock 103 106 98 minecraft:redstone_wire`；之後 F3 瞄準 `103 106 98` 讀 `power`。
- 操作步驟與預期結果：
  - B-4a 載入與身分：世界正常載入，沒有 registry 錯誤或拒絕啟動。輸入 `/data get block 103 106 100 ChamberUuid`，預期顯示 `[I; -616379319, 1488932183, -1377398559, 266153682]`（沿用舊 UUID）。
  - B-4b 依目前供電重新核對：F3 讀 `power`，預期 3（紅石方塊仍供電、艙內無人）。以創造模式左鍵 `100 100 100` 的基岩，預期不會被移除；左鍵 Controller，預期 actionbar 顯示「請先斷電並等待載入協調與返還完成。」
  - B-4c 斷電與復電：輸入 `/setblock 103 107 100 minecraft:air` 移除紅石方塊（艙體外），預期 `power` = 0、`ChamberUuid` 不變。輸入 `/setblock 103 107 100 minecraft:redstone_block` 復電，預期 `power` = 3、`ChamberUuid` 仍不變（重新供電沿用 UUID）。再輸入 `/setblock 103 107 100 minecraft:air`，回到 `power` = 0。用指令移除，是為了避免 OFF 時左鍵誤打到 Controller 而提前拆掉它。
  - B-4d 拆除與重建：`power` = 0 時以創造模式左鍵 Controller，預期 actionbar 顯示「控制器已拆除；艙體保護已解除。」輸入 `/setblock 103 106 100 quantumchamber:chamber_controller[facing=north]` 重建，預期 `power` = 0、查不到 `ChamberUuid`。輸入 `/setblock 103 107 100 minecraft:redstone_block`，預期 `power` = 3，`ChamberUuid` 是一組與舊 UUID 不同的新陣列。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-4a 載入與舊 UUID | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `run/client-base/logs/latest.log` 15:35:02–15:35:33 | 採計複本 `QC-legacy-schema1-r4`（從 `$bak\server-m1-smoke` 複製，`chambers.dat` SHA-256 為 `80ED28ED…6744CC9`，相符）。以 `--quickPlaySingleplayer QC-legacy-schema1-r4` 開啟，正常載入，沒有 registry 錯誤；8 秒內開 LAN、允許作弊、切創造模式。`ChamberUuid` = `[I; -616379319, 1488932183, -1377398559, 266153682]`（沿用舊 UUID），`ChamberState` IDLE。不採計的複本：r1 在 B-4b 時，controller 瞄準偏差打掉 Controller 頂上的紅石方塊，導致提前斷電；r2、r3 在 controller 接手前，角色（生存模式）閒置時被史萊姆殺死（r2 另有使用者手動暫停與重進）。原因是等待條件寫錯，接手延遲了數分鐘；已改為進世界後 15 秒內接手。 |
| B-4b 供電保護重新核對 | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `153555-QC_A-b4b-f3-wire.png`、`153557-QC_A-b4b-aim-bedrock.png`、`153623-QC_A-b4b-aim-ctrl.png`、`153648-QC_A-b4b-ctrl-msg.png` | 依步驟 5 擺好比較器，F3 瞄準 `103 106 98`，讀到 `power: 3`（`/data` OutputSignal 3）。每次左鍵前，都先以 F3 確認 Targeted Block：`100 100 100` 的基岩左鍵後未被移除；`103 106 100` 的 Controller 左鍵後未被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」，紅石方塊仍在，讀值仍是 3。 |
| B-4c 斷電 OFF、復電沿用 UUID | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `153737-QC_A-b4c-final-wire.png`；latest.log 15:37:14–15:37:35 | `/setblock 103 107 100 minecraft:air`：讀 0，UUID 不變（`ChamberState` INVALID）。放回 `redstone_block`：讀 3，UUID 仍不變。再移除：讀 0，UUID 不變。 |
| B-4d 拆除與重建新 UUID | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `153749-QC_A-b4d-aim-ctrl.png`、`153817-QC_A-b4d-removed-msg.png`、`153836-QC_A-b4d-final-wire.png` | 新 UUID：`[I; 1887667975, 668026122, -1495656849, -290867793]`。讀值 0 時，以 F3 確認目標後左鍵 Controller，actionbar 顯示「控制器已拆除；艙體保護已解除。」。以 `/setblock 103 106 100 quantumchamber:chamber_controller[facing=north]` 重建：讀 0，查不到 `ChamberUuid`。放回 `redstone_block`：讀 3，出現新 UUID，與舊的不同。 |

### B-5 M4 spec §15 側門（最後執行）

- 來源：[M4 設計](../superpowers/specs/2026-09-21-m4-candidate-doors-design.md) §10、§11、§15；[M4 紀錄](../implementation-notes/2026-09-21-m4-candidate-doors.md)「Selection CAS、first-wins 與互動」「DORMANT 只封鎖自己的 Chamber（W-I2）」「人工驗收狀態（spec §15）」。
- 範圍界線：M4 只會看到「側門可被選擇一次、收到鎖定訊息、其他門被拒絕、門仍關閉、玩家仍在走廊」。「走廊消失、回原艙、門後是新世界」屬於 M5，不得把 M4 的中間狀態回報成原需求已完成。
- 前置條件：A、B-0～B-4、B-6、C 段都已做完（見 2.5 (a)）。build 的 production 程式等同 `$base`（目前 `ba854e8`，見 2.2）。在 `run/client-base` 建立新的創造世界 W-M4（允許作弊），蓋一座艙 C-M4，準備藥水與牛奶。
- 下方「B-5 預驗」表是 2026-09-29 在使用者自有世界、以 `1f900f5` build 做的部分觀察，不是本項的正式結果；正式 B-5 仍須在 W-M4 依本節步驟完整重做（2026-09-30 已在 W-M4 完成，見下表）。
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
  - 步驟 4：門仍關閉（實心，走不進去）；伺服器端位置沒變（以 `/data get entity @s Pos` 讀取），F3 仍是 `quantumchamber:superposition`；沒有傳送、沒有新世界。選定後 `MEASURED` 會凍結尚未返還的參與者，移動封包會被直接丟棄且不送回位置修正，所以客戶端 F3 的 XYZ 可能沿牆漂移，直到返還；這是已知的操作體驗問題，列入 M5 待辦，不算本項失敗。
  - 步驟 5：沒有任何 actionbar 訊息，門仍關閉，選擇不變。依 [M4 設計](../superpowers/specs/2026-09-21-m4-candidate-doors-design.md) §10（`:241` 的 freeze all door interactions），選定後凍結所有門互動（`ServerPlayNetworkHandlerRecoveryMixin` 取消凍結中參與者的 `onPlayerInteractBlock`）；「候選已鎖定。」只會出現在同一個 tick 內的第二個互動（§10 `:245`），真實客戶端做不到，由 GameTest 涵蓋。
  - 步驟 6：被拒絕，沒有第二次鎖定：沒有任何訊息（同步驟 5，凍結），門仍關閉，選擇不變。
  - 2026-09-30 更正：步驟 4–6 原先的預期是「位置沒變（F3）」、步驟 5「再點同門顯示『候選已鎖定。』」與步驟 6「通常顯示『候選已鎖定。』」，與 M4 設計 §10（`:241`、`:245`）不一致；更正後的預期仍在 §15 的人工可見界線內。使用者決定改依設計更正預期，並把客戶端座標漂移列入 M5。依原預期記錄的 FAIL（待判讀）列保留在下表，另加依更正後預期判定的新列（同一份證據，沒有重測）。
  - 步驟 7：回到 C-M4 室內（`minecraft:overworld`）。
  - 步驟 9：不會再入場（F3 仍是 `minecraft:overworld`），原艙已被封鎖。
  - 步驟 10：`power` = 11，而且不會回到 3 或 0（依程式推導：DORMANT 的 presence 為 `UNKNOWN`，協調時維持 RETURNING 並輸出 ARMED；自動測試沒有直接斷言這個比較器值，實際不同時照實記錄並標 FAIL 待判讀）；不再冒出新的輝光粒子（RETURNING 不發光，同屬推導）。
  - 步驟 11：`power` 仍是 11，不會進入 OFF。
  - 步驟 12：Controller 不會被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」；基岩不會被移除。
  - 步驟 13：C-M4b 可以正常入場（DORMANT 只封鎖自己的 Chamber）；之後喝奶返還即可。
  - B-5d／e／f 的預期與 `752ada1` 時相同。2026-09-29 整合修正（F-CHECKPOINT）只改變「原生存檔 checkpoint 持續失敗時」的重試時序（每個 session 的走廊空間（per-space）以 20→40→…→1200 ticks 退避；WARN 同一原因只在第一次附 stack，之後只計數，原因改變時補記前一原因的重複次數），正常存檔成功時不會觀察到差異。
- 完成後：W-M4 的 C-M4 會一直封鎖到 M5。這個世界不要再拿來做 M2 項目。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-5a 選擇一次與鎖定訊息（步驟 3） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `160046-QC_A-b5c-s3-aim-D1.png`、`160047-QC_A-b5c-s3-msg.png`；另見 `154508-QC_A-b5-s3-D1-msg.png`、`155637-QC_A-b5r-s3-msg.png` | W-M4 為 `run/client-base/saves/QC-accept-WM4`。C-M4 在 `0 -61 20`，C-M4b 在 x+20，C-M4c 在 x+40。採計 C-M4c（session `4e00375b`）：入場約 30 秒後，走到入口艙西側約 13 格，以 F3 確認準星對準南牆 D1 的 `981 67 2003`（完整 5×5 側門 `979..983, 65..69, 2003`），空手、未蹲下右鍵，actionbar 立即顯示「量子候選已鎖定，等待塌縮」。C-M4、C-M4b 在同一位置各選一次，訊息相同。唯讀解碼 `data/quantumchamber_sessions.dat`：三筆 record 都是 `MEASURED`、`SelectionRevision 1`、各只有一個 SELECTED（`NEGATIVE_LATERAL`、station 2）。 |
| B-5b 同門再點與其他門被拒絕（步驟 5–6） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | FAIL（待判讀） | `160049-QC_A-b5c-s5-msg.png`、`160049-QC_A-b5c-s5-msg2.png`、`160114-QC_A-b5c-s6-aim-D2.png`、`160138-QC_A-b5c-s6-msg.png`、`160139-QC_A-b5c-s6-msg2.png`；C-M4b：`155729-QC_A-b5r-s5-aim-D1.png`、`155750-QC_A-b5r-s5-msg-a.png`、`155754-QC_A-b5r-s5-msg-retry.png` | 步驟 5（再點 D1）：C-M4b 與 C-M4c 都以 F3 確認準星對準 D1 的格子，共右鍵 3 次，都**沒有任何訊息**，與預期的「候選已鎖定。」不符。步驟 6（點北牆 D2 `981 67 1997`，以 F3 確認）：沒有訊息，門仍關閉，選擇未改變（仍是 revision 1，依預期屬於拒絕）。原因（依程式）：選定後 session 變成 `MEASURED`，而 `ServerPlayNetworkHandlerRecoveryMixin.java:25-29` 在 `SessionRecoveryManager.blocks`（`SessionRecoveryManager.java:45-54`，MEASURED 且未返還）成立時取消 `onPlayerInteractBlock`；互動在到達 `CandidateDoorInteraction.java:44-45` 的 ALREADY_SELECTED 分支之前就被擋下，所以真實客戶端的參與者看不到「候選已鎖定。」。待判讀：這是清單預期寫錯，還是程式行為需要修改。 |
| B-5b 同門再點與其他門被拒絕（步驟 5–6；依 2026-09-30 更正後預期） | 2026-09-30 | Claude 代操作（Ben 授權）；判定依使用者 2026-09-30 決定 | ba854e8 | 單人 | PASS | 同上一列 | 同一份證據，沒有重測。步驟 5：再點 D1 沒有訊息、門仍關閉、選擇不變（revision 1）。步驟 6：點 D2 沒有訊息、門仍關閉、沒有第二次鎖定。符合 M4 設計 §10（`:241`，選定後凍結所有門互動）與 §15（其他門被拒絕、門仍關閉）。 |
| B-5c 門仍關閉、玩家仍在走廊（步驟 4） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | FAIL（待判讀） | `155641-QC_A-b5r-s4-after-walk.png`、`160145-QC_A-b5c-s4-after-walk.png` | 門仍關閉（`open=false`），走不進去；沒有傳送、沒有新世界；伺服器端位置沒有變（C-M4c：`/data get entity @s Pos` 在嘗試行走前後都是 `984.346, 65, 1999.982`），維度 `quantumchamber:superposition`。但**客戶端 F3 XYZ 有變**：按住 W 後，客戶端沿著牆滑到 `978.917, 65, 1998.300`（C-M4b 為 `978.376, 65, 2002.700`），伺服器沒有把客戶端拉回，造成兩邊位置不一致，直到返還。原因（依程式）：同一個 mixin 在 `onPlayerMove` 直接取消移動封包，沒有送回位置修正。與預期的「位置沒變（F3 XYZ）」不符。 |
| B-5c 門仍關閉、玩家仍在走廊（步驟 4；依 2026-09-30 更正後預期） | 2026-09-30 | Claude 代操作（Ben 授權）；判定依使用者 2026-09-30 決定 | ba854e8 | 單人 | PASS | 同上一列 | 同一份證據，沒有重測。門仍關閉、走不進去；伺服器端位置不變；維度仍是 `quantumchamber:superposition`；沒有傳送、沒有新世界。客戶端 F3 沿牆漂移屬於更正後預期所列的已知操作體驗問題，列入 M5。 |
| B-5d 返還後原艙封鎖、無法再入場（步驟 7–9） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `155033-QC_A-b5-s9-f3.png`；latest.log 15:50:10–15:50:33、16:02:17–16:02:20 | 步驟 7：採計 C-M4c，看著地板喝牛奶，回到 C-M4c 室內（45.5, -60, 21.5），`minecraft:overworld`，沒有效果。步驟 8–9 在 C-M4 做：C-M4 當時是因為效果自然到期（約 15:47:34）才返還，不是喝牛奶；因為 controller 在選定後排查瞄準問題耗時，效果先到期了，這屬於偏差。返還約 2.5 分鐘後，在室內（門關著）喝藥（效果 3565 tick），等 10 秒，仍在 `minecraft:overworld`，沒有入場；當時 `ChamberState` 為 ARMED，讀值 11。 |
| B-5e 比較器與 LOW 不進 OFF、無法拆除（步驟 10–12） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | `155109-QC_A-b5-s10-corners-t0.png`、`155114-QC_A-b5-s10-corners-t5.png`、`155119-QC_A-b5-s10-corners-t10.png`、`155144-QC_A-b5-s11-aim-lever.png`、`155223-QC_A-b5-s12-aim-ctrl2.png`、`155257-QC_A-b5-s12-ctrl-msg.png`、`155302-QC_A-b5-s12-aim-bedrock.png` | 實際 `power`：步驟 10 為 11（出艙時與 10 秒後都是 11），艙角沒有新輝光；步驟 11 拉桿 OFF（`powered=false`），5 秒後仍是 11（ARMED），沒有進入 OFF。步驟 12：以 F3 確認目標後左鍵 Controller，未被拆，actionbar 顯示「請先斷電並等待載入協調與返還完成。」；左鍵基岩 `0 -58 23`，未被移除，讀值仍 11。 |
| B-5f （選測）另一座艙可入場（步驟 13） | 2026-09-30 | Claude 代操作（Ben 授權） | ba854e8 | 單人 | PASS | latest.log 15:55:06、16:00:15 | C-M4 為 DORMANT、讀值 11 時，C-M4b（讀 3）於 15:55:06 正常入場（session `20571672`）。之後又用 C-M4c 入場一次（16:00:15，session `4e00375b`）。偏差：C-M4b 在同一個 session 中也選了側門，之後因效果自然到期而返還（controller 計時失誤），所以 C-M4b 與 C-M4c 最後都成為 DORMANT。W-M4 的三座艙都封鎖到 M5。 |

#### B-5 預驗（非 W-M4，不計入 E 段）

2026-09-29 使用者在自有世界做了 B-5 的一部分。這不是上表的正式結果，E 段不採計；正式 B-5 仍須在 W-M4 依本節步驟完整重做（2026-09-30 已在 W-M4 完成，見上方正式表）。

- 日期：2026-09-29。驗收者：Ben（controller 依使用者在 AskUserQuestion 的回答轉錄；存檔 NBT 的唯讀解碼只作為佐證，不代替觀察）。
- build：`1f900f5`（code 等同 `752ada1`，早於 2026-09-29 的整合修正）。
- 世界：`run/client-base/saves/新的世界test (1)`（使用者自有世界，非 W-M4，測前未備份）。Chamber：Controller `2 70 12`、NORTH。
- B-5e（步驟 10–12）與 B-5f（步驟 13）沒有做，不列。
- 處置：這座 Chamber 已被 DORMANT receipt 封鎖到 M5，依 2.5 (a) 處理，不得用於 A／B／C 段。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 計入 E 段 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B-5a 選擇一次與鎖定訊息（步驟 3） | 2026-09-29 | Ben（controller 轉錄） | `1f900f5` | 單人 | 觀察到 | 否 |  | 使用者觀察到鎖定訊息 |
| B-5b 同門再點與其他門被拒絕（步驟 5–6） | 2026-09-29 | Ben（controller 轉錄） | `1f900f5` | 單人 |  | 否 |  | 只觀察到步驟 6（另一扇門被拒絕）；步驟 5（同門再點）沒有做，因此不填結果 |
| B-5c 門仍關閉、玩家仍在走廊（步驟 4） | 2026-09-29 | Ben（controller 轉錄） | `1f900f5` | 單人 | 觀察到 | 否 |  | 使用者觀察到門仍關閉、仍在走廊 |
| B-5d 返還後原艙封鎖、無法再入場（步驟 7–9） | 2026-09-29 | Ben（controller 轉錄） | `1f900f5` | 單人 | 使用者陳述＋NBT 佐證 | 否 | `data/quantumchamber_sessions.dat`（SHA-256 見 2.5 (a)） | 使用者陳述返還後再喝藥不再入場。NBT 唯讀解碼：sessions SchemaVersion=3、1 筆 record `State=MEASURED`＋`CandidateSelection.Kind=SELECTED`（DoorKey station -1、`NEGATIVE_LATERAL`）、ledger 382 筆、participant `Returned=1`、`SpaceLeases=[]`（即 DORMANT）；chambers registry `PowerState=RETURNING` |

### B-6 整合修正：走廊內互動限制（W1，在 B-3d 之後、B-5 之前）

- 來源：[整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)「行為變更摘要」第 3、6 點；[M2 紀錄](../implementation-notes/m2-corridor.md)「持久化與安全界線」；[M2 設計](../superpowers/specs/2026-09-17-m2-powered-corridor-design.md) §4、§6（2026-09-29 整合修正段）；[左右走廊修訂](../superpowers/specs/2026-09-18-m2-lateral-buff-maintained-design.md) §3（2026-09-29 改寫）。
- 基準：production 程式等同 `$base`（目前 `ba854e8`，見 2.2）。這些行為是 2026-09-29 才加入的，`752ada1` 或更早 build 的結果不能沿用。
- 共同警告：
  - 全程不要右鍵走廊兩側牆上的側門，手上拿著物品也一樣（見 2.5 (a)）。需要右鍵時，一律對走廊**地板**，或 B-6a 指定的入口艙正面門。
  - 走廊內的方塊受保護，無法放置或破壞任何方塊。
- 共同前置：B-3d 結束時 C1 已重新註冊、拉桿 ON（`power` = 3），玩家在創造模式；身上有量子態藥水至少 6 瓶與牛奶 3 桶。任何時候效果剩餘不到 1 分鐘就補喝一瓶（在走廊內補喝不會中斷 session）。
- 執行順序：B-6a →（B-6b、選測 B-6c、B-6d 接續同一個 session）→ B-6e（新的 session）→（選測）B-6f。

**B-6a 走廊內右鍵入口艙正面門無反應**

- 範圍說明：左右走廊的入口艙正面門在走廊內一直是關閉的。入口 Controller 位於正門頂排中央的正上方（入口艙 local `(3,6,0)`），兩側與內側（天花板）是基岩、下方是關閉的正門，上方與外側在走廊 lease 之外，所以原版 client 在走廊內瞄不到它；對修改版 client 送出的切換請求，程式一律拒絕並顯示「左右走廊的入口正門位於走廊牆面，不開放切換。」。拒絕結果（`FAIL`、handled 且未切換）由 GameTest `trusted_lateral_geometry_{north,east,south,west}_windows`（`src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java:243-250`，斷言 `:260`、`:262`）斷言；訊息文字沒有測試斷言，依程式 `CorridorPageManager.java:460`。**不列入人工項目**。本項只驗原版 client 做得到的操作。
- 操作步驟：
  1. 進艙、關門、飲用藥水，等候入場（同 A-1 步驟 2–5）。入場後先不要離開入口艙。
  2. 面向入口艙的正面門（紫色 5×5 門，位置與原艙正面門相同）。不要蹲下，依序右鍵正面門的幾格紫色門塊，至少包含頂排中央那一格與底排任一格。
  3. 按 F3 看維度，按 E 看效果；確認正面門仍是關閉（實心）。
- 預期結果：
  - 步驟 2：門不動，也沒有 actionbar 訊息（入口艙正面門不屬於側門，入口艙也不是完整艙體，程式不處理這次點擊；依程式推導）。
  - 步驟 3：仍在 `quantumchamber:superposition`；效果仍在並持續倒數（session 不受影響，沒有返還）；正面門仍關閉。

**B-6b 放置類物品被拒、物品不消耗**

- 前置：接續 B-6a，仍在走廊。輸入 `/gamemode survival`（生存模式才看得出物品有沒有被扣），再取得下列物品：

  ```mcfunction
  /give @s minecraft:armor_stand 2
  /give @s minecraft:item_frame 2
  /give @s minecraft:glow_item_frame 2
  /give @s minecraft:painting 2
  /give @s minecraft:cow_spawn_egg 2
  /give @s minecraft:end_crystal 2
  /give @s minecraft:lead 2
  /give @s minecraft:cod_bucket
  /give @s minecraft:oak_boat
  /give @s minecraft:oak_chest_boat
  /give @s minecraft:minecart
  /give @s minecraft:lingering_potion[minecraft:potion_contents={potion:"minecraft:regeneration"}]
  ```

- 操作步驟：
  1. 按 E 記下每樣物品的數量。
  2. 走出入口艙，站在走廊上。依序手持下列每一樣物品，瞄準腳前 2–3 格的走廊地板（基岩）右鍵一次：盔甲架、物品展示框、螢光物品展示框、畫、牛生怪蛋、終界水晶、拴繩、鱈魚桶、橡木船、附箱橡木船、礦車、滯留型藥水。
  3. 滯留型藥水與橡木船，再朝走廊前方的空中（不對準任何方塊）右鍵一次。
  4. 按 E 核對每樣物品的數量；環顧走廊，看有沒有出現新的 entity 或水。
  5. 輸入 `/gamemode creative`。
- 預期結果：
  - 步驟 2、3：每一次都在 actionbar 顯示「量子走廊內不能放置船、盔甲架、展示框、生物等實體；物品未消耗。」。礦車與拴繩在原版需要鐵軌或柵欄才有作用，但這裡的拒絕發生在判斷目標方塊之前，所以同樣會顯示這則訊息。
  - 步驟 4：每樣物品的數量都與步驟 1 相同（畫面上的數量或方塊可能先閃一下再恢復）；走廊裡沒有盔甲架、展示框、畫、牛、終界水晶、拴繩結、魚、船、礦車或藥水雲，也沒有水。

**B-6c（選測）走廊內丟蛋不孵小雞**

- 前置：接續 B-6b，仍在走廊，創造模式（丟蛋不消耗）。輸入 `/give @s minecraft:egg 16`。
- 操作步驟：面向走廊的一端（不要對著側門），往前方地板連續丟 32 顆蛋；觀察 10 秒。
- 預期結果：蛋落地破裂，沒有孵出任何小雞。`run/client-base/logs/latest.log` 可能出現一則 `type=minecraft:chicken` 的「量子走廊世界拒絕非管理 entity（加入世界…）」WARN；只有原版本來會孵出小雞時才會出現，沒有這則 WARN 不算 FAIL。

**B-6d 經驗球跨頁面保留，並於返還時回到原艙中央**

- 前置：接續 B-6b（或 B-6c），仍在走廊（入口艙外），創造模式，效果剩餘至少 2 分鐘（不足就補喝）。
- 為什麼不用經驗瓶：經驗球每隔一段時間會飛向 8 格內最近的玩家（創造模式也會吸收）。以原版常數推算，經驗瓶在平地的最大落點約 8.7 格，丟出後很難讓經驗球停在 8 格外（推算、非實測）。所以本項改用指令在 12 格外生成經驗球；經驗球屬於走廊世界允許的種類，會正常生成。
- 操作步驟：
  1. 輸入 `/xp set @s 0 levels` 與 `/xp set @s 0 points`。
  2. 面向走廊延伸的方向（不要對著側門或牆面），平視（F3 的視角 pitch 約 0），輸入 `/summon minecraft:experience_orb ^ ^ ^12 {Value:10}`。確認前方約 12 格處的地上出現綠色經驗球，而且沒有飛向你；若它飛過來被吸收，先重設經驗值（步驟 1），往後退幾格再重做本步驟。
  3. 轉身往走廊另一端走至少 120 格（會跨過至少一個 96 格頁面邊界），停 10 秒，再走回來；在距離經驗球超過 8 格（建議 10 格）處停下觀察。
  4. 保持超過 8 格的距離，喝牛奶。
  5. 回到原艙後，觀察艙內地面約 5 秒；輸入 `/xp query @s points` 與 `/xp query @s levels`。
- 預期結果：
  - 步驟 3：經驗球仍在原來的位置（相對走廊不變），沒有消失，也沒有掉出走廊。
  - 步驟 4：自動回到 C1 室內（`minecraft:overworld`）。
  - 步驟 5：經驗球被送到原艙中央，並在 1–2 秒內被你吸收（聽得到拾取音效）；經驗點數或等級大於 0。若沒有被吸收，應看到經驗球留在原艙中央的地面上。

**B-6e 肩上鸚鵡在走廊內不會落下**

- 前置：B-6d 返還後，走到 C1 艙外（`minecraft:overworld`），效果不用保留。
  1. 輸入 `/summon minecraft:parrot ~2 ~ ~` 與 `/give @s minecraft:wheat_seeds 16`，手持種子右鍵鸚鵡，直到出現愛心（已馴服）。
  2. 輸入 `/gamemode survival`。站在鸚鵡旁的地面上不動（不要飛），等牠跳上你的肩膀；按 F5 切到第三人稱確認。已馴服的鸚鵡坐著時不會上肩，這時空手右鍵牠一次讓牠站起來。
  3. 帶著肩上的鸚鵡走進 C1。**不要跳、不要飛、不要受傷**，這些都會讓鸚鵡依原版落下；落下了就重做第 2 步。關門、飲用藥水，等候入場。
- 操作步驟：
  1. 入場後按 F5，確認鸚鵡仍在肩上。
  2. 在走廊原地跳 3 次，每次落地後看肩上。
  3. 輸入 `/damage @s 1`（受到半顆心傷害），看肩上。
  4. 輸入 `/gamemode creative`，連按兩下空白鍵飛起來，飛約 5 秒；在**自己的畫面**上（F5 第三人稱）看肩上。再連按兩下空白鍵落地。
  5. 環顧走廊，確認地上沒有鸚鵡。
  6. 喝牛奶返還。回到原艙後按 F5 看肩上。
  7. 在原艙內連按兩下空白鍵飛起來。
- 預期結果：
  - 入場後，以及步驟 2、3、4：鸚鵡一直在肩上。步驟 4 飛行時，自己畫面上的鸚鵡也不會消失；這是 2026-09-29 client 端修正（`a7ff817`）唯一的實機驗證。
  - 步驟 5：走廊地上沒有鸚鵡。
  - 步驟 6：回到 C1 室內，鸚鵡仍在肩上。
  - 步驟 7：鸚鵡依原版從肩上跳下，出現在原艙內（正向對照：一般世界照原版放下）。
  - 走廊內鸚鵡從肩上消失，或返還後肩上沒有鸚鵡，都記 FAIL。只有步驟 4 自己畫面上的鸚鵡消失、但步驟 6 或 7 顯示鸚鵡其實還在時，同樣記 FAIL（client 修正未生效），並在備註寫明。
- 範圍外：走廊內無法放置方塊，所以粉雪、床與碰水這三種原版觸發無法在走廊內重現，不列入；走廊內死亡時鸚鵡留在重生玩家肩上，只有 bytecode 論證，也不列入人工項目。

**B-6f（選測、需要作弊權限）指令傳送非管理 entity 進走廊世界被拒**

- 前置：在 W1 的 `minecraft:overworld`、艙體外空地，不在任何 session 中，創造模式。
- 操作步驟：
  1. 輸入 `/summon minecraft:cow ~3 ~ ~ {NoAI:1b,Tags:["qc_tp_test"]}`，記下這頭牛的位置。
  2. 輸入 `/execute in quantumchamber:superposition run tp @e[tag=qc_tp_test] 0 100 0`。
  3. 看牛是否還在原處；輸入 `/data get entity @e[tag=qc_tp_test,limit=1] Pos`。
  4. 查看 `run/client-base/logs/latest.log`。
  5. 清理：`/kill @e[tag=qc_tp_test]`。
- 預期結果：
  - 步驟 2：聊天欄仍顯示原版的傳送成功訊息。這是已知限制（傳送被拒時原版仍回報成功），不算 FAIL。
  - 步驟 3：牛仍在 `minecraft:overworld` 的原位置，沒有消失；`Pos` 與步驟 1 相同。
  - 步驟 4：有一則「量子走廊世界拒絕非管理 entity（指令跨維度傳送；…）：type=minecraft:cow」WARN。同一種 entity 同一路徑在同一次遊戲程序內只記一次，前面已記過時不會再出現。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B-6a 右鍵入口正門無反應、session 不受影響 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `114524-b6a-aim-top.png`、`114525-b6a-click-top.png`、`114533-b6a-aim-bottom.png`、`114533-b6a-click-bottom.png`、`114617-b6a-s3-inv.png` | session `c9e01165`。依序右鍵正面門的 `997 69 1997`（頂排中央）、`997 67 1997`、`995 65 1997`（底排）：門不動，沒有 actionbar 訊息。之後仍在 `quantumchamber:superposition`，效果 2913→1221 tick 持續遞減；抽查 3 格門都是 `open=false`。附帶觀察：準星對著入口門時，持藥水或牛奶右鍵喝不到，點擊被門方塊吸收（原艙門也一樣）。這不影響本項判定。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-6b 放置類物品被拒、物品不消耗 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `115054-b6b-s1-inv.png`、`115421-b6b2-aim-floor.png`、`115422-b6b2-armorstand.png`～`115427-b6b2-codbucket.png`、`115508-b6b2-boat.png`～`115510-b6b2-lingering.png`、`115516-b6b2-lingering-air.png`、`115517-b6b2-boat-air.png`、`115540-b6b2-s4-inv.png` | 未顯示訊息或被扣的物品：無。生存模式，session `4dadc632`。12 種物品各對走廊地板 `987 64 2000` 右鍵一次，滯留型藥水與橡木船另對空中各右鍵一次；每一次都顯示「量子走廊內不能放置船、盔甲架、展示框、生物等實體；物品未消耗。」。數量都與步驟 1 相同。60 格內沒有非玩家 entity，地板上方也沒有水。後 4 種物品以 `/item replace` 換進快捷列，沒有用 `/give`。前一個 session（`900d9b46`）中，有 4 次因輸入工具解析錯誤瞄到天花板 `990 70 2002`，同樣被拒並顯示訊息，不計入本項。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-6c （選測）丟蛋不孵小雞 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `115633-b6c-thrown.png`、`115638-b6c-t5.png`、`115643-b6c-t10.png`；latest.log 11:56:30 | 創造模式按住右鍵 7 秒，往前方地板連續丟蛋（約 35 顆）。10 秒內沒有孵出小雞（查無 chicken）。latest.log 有一則 `type=minecraft:chicken` 的「量子走廊世界拒絕非管理 entity（加入世界…）」WARN。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-6d 經驗球跨頁面保留並回到原艙中央 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `120138-b6d2-s2-orb.png`、`120346-b6d2-s3-orb-visible.png`、`120404-b6d2-s5-t2.png`；latest.log 12:04:01 | 返還後經驗值：1 級 3 點（共 10 點，等於經驗球的 Value）。採計第二次（session `fe81d43d`）：經驗值重設為 0 後，在前方 12 格生成經驗球（987.5, 65, 1998.5）。往東衝刺約 122 格、停 10 秒再走回；在距經驗球約 18 格處，經驗值仍是 0，經驗球仍在。它的座標隨頁面平移（987.5→795.5→2427.5），相對走廊的位置不變。喝牛奶後於 12:04:01 返還 C1（5.5, -60, 21.5），4 秒內查得 1 級 3 點，原艙範圍內已沒有經驗球。畫面太暗，看不到經驗球飛行；拾取音效無法由自動化確認。第一次（session `4dadc632`）在走回途中效果自然到期（12:00:12），先行返還，經驗值同樣變成 1 級 3 點；但不是依步驟 4 喝牛奶返還，不計入。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-6e 肩上鸚鵡走廊內不落下、返還後原版放下 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `120756-b6e-pre-shoulder.png`、`120953-b6e-s1-entry.png`、`121030-b6e-s2b-air1.png`、`121000-b6e-s3-damage.png`、`121111-b6e-s4-fly5.png`、`121252-b6e-s6-shoulder.png`、`121323-b6e-s7-fly.png` | 前置：鸚鵡一直飛走，為了瞄準而暫時加上 `NoAI:1b` 再餵種子；馴服後改回 `NoAI:0b`，讓牠自己跳上左肩。入場後、跳 3 次（跳躍統計 = 3）、`/damage @s 1`（已套用）、創造模式飛行（`flying=1b`，y=68.2）時，`ShoulderEntityLeft` 都是 parrot；飛行中的第三人稱截圖可見肩上鸚鵡。走廊世界查無 parrot entity。返還後的第三人稱截圖，肩上仍有鸚鵡。在原艙內飛起後，鸚鵡離肩，出現在艙內（原版行為）。偏差：步驟 6 第一次喝牛奶時，準星對著入口門，點擊被吸收；實際返還是效果自然到期（12:11:51）觸發的。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| B-6f （選測）指令傳送非管理 entity 被拒 | 2026-09-30 | Claude 代操作（Ben 授權） | d03bbc6 | 單人 | PASS | `121413-b6f-s3.png`；latest.log 12:14:11 | 聊天欄仍顯示原版的「已將 牛 傳送到 0.500000, 100.000000, 0.500000」（已知限制）。牛仍在 `minecraft:overworld`，`Pos` 與生成時相同（15.5, -60, 10.5）。latest.log 有一則「量子走廊世界拒絕非管理 entity（指令跨維度傳送；…）：type=minecraft:cow」WARN。已用 `/kill` 清理。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

## C. 多人待驗項目（2026-09-30 已記錄結果）

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
- C-1b 平台說明（2026-09-29 整合修正）：checkpoint 能力預檢看的是伺服器存檔的 playerdata（`run/server/QC-accept-mp/playerdata`）。本機 Windows 固定 NTFS 是正常路徑；若停在 7 不入場，先查 `run/server/logs/latest.log` 有沒有「玩家原生 checkpoint 能力預檢未通過，拒絕入場」WARN（非 Windows 或 playerdata 不在本機固定 NTFS 時，`start()` 會在任何預留之前拒絕，停在 READY／7，同一原因只記一次）。這不是本項的預期環境：記 BLOCKED 並附 log。
- C-1c 旁觀者排除，操作步驟（在 C-3a 返還後）：
  1. C-3a 返還後兩人都在艙內、門關著。B 喝牛奶清掉效果，再輸入 `/gamemode spectator`，留在艙內；A 沒有效果，也留在艙內。OBS 讀 `power`。
  2. 只有 A 喝藥，等 10 秒；OBS 讀 `power`；A、B 看 F3。
  3. A 喝牛奶返還；B 輸入 `/gamemode creative`。
- C-1c 預期結果：步驟 1 為 3；步驟 2 只有 A 入場（A 是 `quantumchamber:superposition`，B 留在 `minecraft:overworld` 的原艙內），OBS 讀 11；步驟 3 A 回到原艙。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-1a 一人缺 Buff 不入場 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `130035-QC_OBS-c1a-s1-wire.png`、`130213-QC_OBS-c1a-s3-wire.png` | 步驟 1：3。步驟 2：A 開門，A、B 走進艙內（z 23.7），A 從開口右鍵 Controller 關門。步驟 3：只有 A 喝藥，等 10 秒；仍是 3，A、B 都在 `minecraft:overworld`（A 效果 3316 tick，B 沒有效果）。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-1b 補齊後自動入場 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `130236-QC_OBS-c1b-wire.png`、`130301-QC_A-c1b-sees-b.png`、`130350-QC_B-c1b-sees-a.png`；server log 13:02:30 | B 喝藥，不扳拉桿，也沒開關門；13:02:30 入場（session `3e5bf8b1`）。OBS 的第一次讀值已是 11（7 的時間太短，沒讀到）。A、B 都在 `quantumchamber:superposition`，在走廊中互相看得到（截圖可見對方名牌與人物）。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-1c 旁觀者排除 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `130613-QC_OBS-c1c-s2-wire.png`；server log 13:05:57、13:06:29 | 步驟 1：B 喝牛奶後 `/gamemode spectator`（`playerGameType` 3），留在艙內（5.5, -60, 21.5），門關著，3。步驟 2：只有 A 喝藥，13:05:57 只有 A 入場（session `f73c22d8`）；B 留在 `minecraft:overworld` 的原艙內；OBS 讀 11。步驟 3：A 喝牛奶，回到原艙（13:06:29）；B 切回創造模式；3。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

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
  - 步驟 3：A 連線後的下一個伺服器 tick 被送回原艙室內（`minecraft:overworld`，可能先短暫出現在走廊），不會回到可以繼續行走的舊 session。A 返還後就可以正常移動與互動，不必等 B（2.5 (d)）；B 還沒返還，`power` = 11，原艙仍受保護，A 在 B 返還前也不能開新 session。
  - 步驟 4：A 的量子態效果消失。
  - 步驟 5：B 被送回原艙室內；兩人都返還並清理完成後 `power` = 3（A 沒有效果，不會自動再入場）；鑽石出現在原艙室內（若被自動撿起，以背包數量核對）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-2a 不合格狀態跨重啟不自行入場 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `140646-QC_OBS-c2a-s1-wire.png`、`140920-QC_OBS-c2a-s4-wire.png`；server log 14:07:01–14:09:45 | 步驟 1：兩人喝牛奶後只讓 A 喝藥；3；UUID `[I; -135760690, 1420381795, -1784837480, 1142255052]`。步驟 2：以 `/stop` 停機後，用完整指令（`--world QC-accept-mp`）重開，主控台顯示 `Preparing level "QC-accept-mp"`、`durable sessions=0`。步驟 3：B 先連線，位置在艙內（4.5, -60, 21.5）；之後 A、OBS 連線。步驟 4：等 10 秒，3，UUID 不變，兩人都在 `minecraft:overworld`（A 仍有效果 2189 tick）。步驟 5：B 喝藥，14:09:45 自動入場（session `2a5a98d2`），沒有扳拉桿；OBS 讀 11。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-2b 活動 session 跨重啟只返還 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `141034-QC_B-c2b-s1-dropped.png`、`141147-QC_OBS-c2b-s3-wire.png`、`141243-QC_A-c2b-s3-A-f3.png`、`141401-QC_OBS-c2b-s5-wire.png`、`141408-QC_A-c2b-s5-floor.png`；server log 14:10:48–14:13:44 | 步驟 1：B 在走廊丟下鑽石（996.86, 65, 2000.03），背包沒有鑽石了。步驟 2：14:10:48 停機，重開時主控台顯示 `durable sessions=1`。步驟 3：OBS 先上線（11）；A 於 14:12:37 以走廊座標（999.5, 65, 1998.5）登入，隨即被送回原艙（5.5, -60, 21.5），`minecraft:overworld`；B 返還前 OBS 讀 11。偏差：A 的斷線畫面按鈕位置不同，第一次重連沒送出，因此 OBS 比 A 先上線。步驟 4：A 喝牛奶，效果消失。步驟 5：B 於 14:13:44 以走廊座標（998.5, 65, 1998.5）登入，隨即被送回原艙（4.5, -60, 21.5）；OBS 讀 3；鑽石出現在原艙中央（3.5, -60, 23.5）。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

### C-3 M2-3 多人共享效果與離線

- 來源：[M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 3 項；[M2 紀錄](../implementation-notes/m2-corridor.md)「持久化與安全界線」（離線者仍在凍結名單，必要租約與來源保護繼續持有）。
- C-3a 任一位喝奶即全組返還，操作步驟（接 C-1b，A、B 都在走廊）：
  1. B 在走廊地上丟 1 顆鑽石。
  2. A 喝牛奶。
  3. 看 A、B 的位置與效果；看艙內地面；OBS 讀 `power`。
- C-3a 預期結果：A、B 都在 1–2 秒內回到原艙室內（`minecraft:overworld`）；A 沒有效果；B 的量子態效果保留剩餘時間（不退款、不重置）；鑽石出現在原艙室內（走廊裡若有經驗球，同樣送到原艙中央，見 B-6d）；清理完成後 `power` = 3（A 沒有效果）。
- C-3b 途中斷線，操作步驟（在 C-1c 之後）：
  1. A、B 都喝藥、關門，重新入場；B 在走廊丟 1 顆鑽石，記下 B 的背包內容與效果剩餘時間。
  2. B 按 Esc →「中斷連線」。
  3. 看 A 的位置；OBS 讀 `power`；A 喝牛奶（避免之後自動再入場）。
  4. 等 30 秒以上，B 用同一個名稱重新連線。
  5. 看 B 的位置、背包與效果；OBS 讀 `power`；看艙內地面。
- C-3b 預期結果：
  - 步驟 3：B 斷線本身就會觸發整組返還，A 在 1–2 秒內回到原艙室內。A 返還後就可以正常移動與互動，不必等 B（2.5 (d)）；B 還沒返還，`power` = 11，原艙仍受保護，A 在 B 返還前也不能開新 session。
  - 步驟 5：B 連線後的下一個 tick 被送回原艙室內（可能先短暫出現在走廊）；背包內容與斷線前相同；量子態效果保留斷線當下的剩餘時間；兩人都返還並清理後 `power` = 3；鑽石出現在原艙室內（或已被撿起；走廊裡若有經驗球，同樣送到原艙中央）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-3a 喝奶全組返還 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `130417-QC_B-c3a-s1-dropped.png`、`130458-QC_OBS-c3a-s3-wire.png`、`130505-QC_A-c3a-s3-floor.png`；server log 13:04:23 | B 丟鑽石（5→4）；A 喝牛奶；13:04:23 整組返還（「正常結束，非故障」）。A 在（4.5, -60, 21.5），B 在（5.5, -60, 21.5），都是 `minecraft:overworld`。A 沒有效果；B 的效果保留（丟鑽石時 1375 tick，返還後 1114 tick，持續遞減，沒有重置）。鑽石在原艙中央（3.5, -60, 23.5）。OBS 讀 3。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-3b 途中斷線、JOIN 完成返還 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B、QC_OBS | d03bbc6 | 多人（2＋OBS） | PASS | `130811-QC_B-c3b-s1-dropped2.png`、`130817-QC_B-c3b-s1-inv2.png`、`131114-QC_B-c3b-s5-rejoined.png`、`131142-QC_OBS-c3b-s5-wire.png`；server log 13:08:50、13:11:09 | 步驟 1：兩人喝藥後入場（session `72a31f51`）。第一次按 Q 時，切換到鑽石格的按鍵沒有生效，丟出的是第 3 格的 1 瓶量子態藥水（截圖可見該格變空）；之後選鑽石格，再丟出 1 顆鑽石（4→3）。斷線前 B 的效果 2410 tick，背包為 5 瓶藥水、2 桶牛奶、3 顆鑽石、1 個空桶。步驟 2：13:08:50 B 中斷連線，server log 記「入場交易保留 RETURNING」WARN，並附一段 `IllegalStateException: 活動中的來源身分或完整cohort效果失效` stack trace（`SuperpositionSessionManager.java:190`，屬整組返還路徑）。步驟 3：A 回到原艙（5.5, -60, 21.5）；OBS 讀 11；A 可以移動，也能開門；A 喝牛奶。步驟 4：等約 2 分 20 秒後，B 用同名重新連線。步驟 5：B 以走廊座標（999.5, 65, 1998.5）登入，隨即被送回原艙（4.5, -60, 21.5）；效果 1555 tick（保留並遞減，沒有重置）；背包與斷線前相同；OBS 讀 3；原艙內有 2 個掉落物 entity，其中鑽石在原艙中央（3.5, -60, 23.5）。另一個沒有逐一查驗；推測是本次丟的藥水，而 C-3a 留下的鑽石與本次的鑽石已合併成一堆。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

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
  - C-4b：會合時對方出現在正確位置，站在地板上，不懸空也不穿牆；鑽石仍在原處，可以撿起；走廊結構連續。（經驗球比照掉落物保留；因為經驗球會飛向 8 格內的玩家，本項不另測，單人專項見 B-6d。）
  - 結束：兩人都回到原艙室內（`minecraft:overworld`）。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-4a 近距離同群越界 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B | d03bbc6 | 多人（2） | PASS | `133322-QC_A-c4a-100-A.png`、`133331-QC_B-c4a-100-B.png`、`134100-QC_A-c4a-w100-A.png`～`134340-QC_B-c4a-w300-B.png`、`134501-QC_A-c4a-e100-A.png`～`134740-QC_B-c4a-e300-B.png` | `view-distance`：10。採計 session `ef375f8f`：兩人並肩（相距 2.4 格）用走路鍵往西走 3 段（每段約 100 格），再往東走 3 段回到入口（x ≈ 998）。每段結束時兩人互看並截圖，每張都看到對方站在地板上，走廊的牆、地板、側門外觀連續，沒有端牆或空洞。每次讀位置，兩人的 x 都一致；跨頁時兩人被一起重新定位（例如 x 4066→992），相對位置不變。偏差：(1) session `d28620e1` 不採計：B 在 C-1c 從旁觀模式切回創造模式後，仍保持飛行狀態（`flying=1b`，原版行為），移動比 A 快，兩人因此拉開 136–159 格。將 B 的遊戲模式切換一次、確認 `flying=0b` 後重做。(2) session `6199e6a5` 往西 3 段都正常（兩人並肩、互看正常），但往回走之前效果到期、整組返還；`8400e6f3` 與 `afb5db9f`（13:28:51 入場、13:31:42 到期返還）也提早到期。這三次都不採計；session `ef375f8f` 改成每段補喝後完成。(3) 原本讓兩個視窗輪流取得焦點來走路，後來改成對兩個視窗同時送出按鍵。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-4b 分離與重聚 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B | d03bbc6 | 多人（2） | PASS | `135541-QC_B-c4b2-diamond.png`、`135753-QC_A-c4b2-apart-A.png`、`135808-QC_B-c4b2-apart-B.png`、`135952-QC_A-c4b2-meet-A.png`、`135958-QC_B-c4b2-meet-B-diamond.png` | 採計 session `837f30ef`：A 貼北牆、B 貼南牆，B 把鑽石丟到走廊中央（997.49, 65, 2001.20），兩人的拾取範圍都碰不到。A 往西、B 往東各走約 200 格（相距約 402 格），各停 20 秒以上。走回後，鑽石在（2437.49, 65, 2001.20）：頁面平移 1440 格，走廊內相對位置不變。會合時，兩人都在地板上看到對方，沒有懸空或穿牆，截圖中鑽石仍在原處；B 走過去撿起，背包 0→1。偏差：第一次（session `ef375f8f`）兩人並肩，距離太近，丟出的鑽石當場被 B 撿回，不採計。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |
| C-4c 反方向 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B | d03bbc6 | 多人（2） | PASS | `140206-QC_A-c4c-e100-A.png`～`140446-QC_B-c4c-e300-B.png` | 同一 session `837f30ef`，接在 C-4b 之後：兩人並肩往東走 3 段（約 300 格）。每段互看都正常，外觀連續；兩人 x 一致（例如 2444.8／2445.4）。結束：A 喝牛奶，14:05:37 整組返還，兩人都在原艙（5.5／4.5, -60, 21.5）；OBS 讀 3。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

### C-5（選測）多人肩上鸚鵡顯示

- 來源：[整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)「行為變更摘要」第 3 點（肩上鸚鵡）。其他玩家畫面上的肩上 entity 由伺服器同步，本項確認走廊內保留肩上鸚鵡後，其他玩家看到的也一致。基準：production 程式等同 `$base`（目前 `ba854e8`，見 2.2）。
- 前置條件：在 C-2 之後。拉桿 ON；A、B 都沒有量子態效果，各有藥水至少 2 瓶與牛奶 1 桶。A 依 B-6e 前置第 1–2 步取得馴服的鸚鵡並放上肩（`/gamemode survival`）；B 站在旁邊看得到 A。全程不要右鍵側門。
- 操作步驟：
  1. A（帶著肩上的鸚鵡，不跳、不飛）與 B 進艙，關門，兩人都喝藥，等候入場。
  2. 在走廊內，A 原地跳 3 次；再輸入 `/gamemode creative`，連按兩下空白鍵飛約 5 秒後落地。B 全程看 A 的肩上。
  3. A 喝牛奶，整組返還。B 看 A 的肩上。
  4. A 在原艙內連按兩下空白鍵飛起來。
- 預期結果：
  - 步驟 2：B 的畫面上，A 的鸚鵡一直在 A 的肩上；走廊地上沒有鸚鵡。
  - 步驟 3：兩人都回到原艙室內，B 看到 A 的鸚鵡仍在 A 的肩上。
  - 步驟 4：鸚鵡依原版從 A 的肩上跳下，B 也看得到牠出現在原艙內。

| 子項 | 日期 | 驗收者 | build（commit SHA） | 單人／多人 | 結果 | 證據 | 備註 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C-5 （選測）多人肩上鸚鵡顯示 | 2026-09-30 | Claude 代操作（Ben 授權）：QC_A、QC_B | d03bbc6 | 多人（2） | PASS | `141921-QC_B-c5-s2-B-view0.png`、`142002-QC_B-c5-s2-B-afterjumps.png`、`142034-QC_B-c5-s2-B-flying.png`、`142135-QC_B-c5-s3-B-returned.png`、`142403-QC_B-c5-s4-B-wide.png` | 前置：C-2b 之後以 `/tp` 佈置時，C-MP 仍供電，而 B 在艙內仍有效果、符合入場資格，因此意外入場兩次：`fa36438c`（14:14:26）在入場過程中 `/tp QC_B`，server log 出現 WARN「入場交易保留 RETURNING」，安全返還；`2e32d60b`（14:14:28 入場，14:15:39 效果自然到期返還）。這兩次不屬於任何子項，不採計。A 生存模式，鸚鵡先加 `NoAI:1b` 再馴服，改回 `NoAI:0b` 後自己跳上 A 的左肩。步驟 1：A 帶著鸚鵡走進 C-MP（沒有跳、飛或傳送），兩人喝藥，入場。步驟 2：A 跳 3 次（跳躍統計 = 3），之後切創造模式飛了約 12 秒（截圖流程耗時）；B 的畫面上，鸚鵡一直在 A 的肩上；走廊沒有 parrot entity。步驟 3：A 喝牛奶，整組返還；B 的畫面上，A 肩上仍有鸚鵡。步驟 4：A 在原艙內飛起後鸚鵡跳下；B 的廣角截圖中，鸚鵡在艙內地板上。（簽註：未受 `d03bbc6..ba854e8` 影響，見 E 段） |

## D. 使用者回報已驗（build／日期待補）

使用者於 2026-09-24 回報下列項目已驗（M2-4 的 LOW 返還為 2026-09-29 更正補報），但沒有記錄當時的 build 與日期。依使用者決定，這些項目不在目前 HEAD 重做；其中 M2-1、M2-2 另列入 A 段，待於目前 HEAD 快速複驗；M2-4 的 LOW 返還仍在 B-1b 於目前 HEAD 驗收。本表只記錄「使用者回報已驗（build／日期待補）」，不代表已在目前 HEAD 通過。2026-09-30 更新：A-1、A-2、B-1a、B-1b、B-2 都已在目前 HEAD 驗收通過（結果見各表）。

| 項目 | 來源 | 使用者回報日期 | 當時 build | 備註 |
| --- | --- | --- | --- | --- |
| M2-1 單人原生飲用與左右入口 | [M2 八項](../implementation-notes/m2-corridor.md#八項人工驗收) 第 1 項 | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 列入 A-1；2026-09-30 已在目前 HEAD 複驗 PASS（見 A-1） |
| M2-2 單人自然到期 | M2 八項第 2 項 | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 列入 A-2；2026-09-30 已在目前 HEAD 複驗 PASS（見 A-2） |
| M2-4 LOW 返還（走廊中 LOW 安全返還） | M2 八項第 4 項後半 | 2026-09-29 回報（更正 2026-09-24 的「未驗」） | 無法補填（見下方 2026-09-30 使用者決定） | 仍在目前 HEAD 做 B-1b：M4 起返還路徑有改動，而且 B-1b 是 B-3b、B-3d 的前置。M2-4 前半的 HIGH 再入場（B-1a）與 M2-7 光影／shader（B-2）當時未驗，不列入本表。2026-09-30：B-1a、B-1b、B-2a–e 都已在目前 HEAD 驗收 PASS |
| M2-5 32 chunks／512 blocks 視距 | M2 八項第 5 項 | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 未在目前 HEAD 重做 |
| M2-8 選用手持照明（含 M1.2 主手／副手火把） | M2 八項第 8 項；[M1.2 設計](../superpowers/specs/2026-09-17-m1.2-powered-origin-design.md) §7；[玩家指引](../implementation-notes/m1-player-build-verification.md) 表 N | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 未在目前 HEAD 重做。2026-09-24 唯讀檢查：本工作區的 `run/client-light` 只有 `mods/`，沒有 `saves/`、`logs/` 與 `options.txt`；主 checkout 也沒有 `run/client-light`。補填時請確認當時是否在另一台電腦或其他 profile 驗的，並註明 profile 與世界；若不是 `start-client.bat light` 的選用照明環境，請改列 B 段重驗（2026-09-30：依使用者決定不補填，此指引不再適用） |
| M1 GUI／HUD／瞄準（釀造、飲用與效果 HUD、25 格門與 Controller 瞄準開關） | [M1 紀錄](../implementation-notes/m1-chamber.md)「Client runtime 與人工驗收」第 1–2 項；[M1.1 紀錄](../implementation-notes/m1.1-origin-maintenance.md)「人工 gate」第 2–3 項；玩家指引表 H | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 未在目前 HEAD 重做 |
| M1 拉桿與比較器 3→7→11（held-high 不重觸發） | M1 紀錄「Client runtime 與人工驗收」第 3 項；M1.1「人工 gate」第 4 項；玩家指引表 A–C | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 未在目前 HEAD 重做。M1 原文是 rising-edge 語意；M1.2 起持續供電加上全員合格即自動入場（玩家指引表 C） |
| M1 四向外觀與 `start-client.bat` 雙擊啟動 | M1 紀錄「2026-09-16 核准的門控與青紫造型 follow-up」「2026-09-16 Windows 啟動入口」 | 2026-09-24 回報 | 無法補填（見下方 2026-09-30 使用者決定） | 未在目前 HEAD 重做 |

2026-09-30 使用者決定（E 段第 2 條）：上表「當時 build」無法補填，依使用者 2026-09-24／2026-09-29 的回報採計，不另行重驗。另外，其中部分行為已被 2026-09-30 在目前 HEAD 的正式列間接覆蓋，列在下面供參考；這些不代替原項目：
- 拉桿與比較器：B-0、A-1、C-1、C-2 讀到 0／3／11；C-1b 的 7 因為太短沒有讀到。
- 飲用與效果 HUD：A-1、B-2c 看得到效果圖示。
- 25 格門與 Controller 的開關：B-3a、B-3d。
- 走廊中效果時間的顯示：A-1。

補填參考：2026-09-24 建立本清單時，`run/client-base/saves/新的世界test (1)` 最後寫入時間為 2026-09-21 09:16，session journal 仍是 schema2（當時沒有在 M4 build 建立過 session），可用來推估上表項目的 build。2026-09-29 使用者在該世界做了 B-5 預驗之後，上述狀態已被覆蓋：該世界已被寫成 schema3，並含一份 DORMANT receipt（見 2.5 (a) 與 B-5 預驗表）；測前沒有備份，2026-09-21／schema2 的原狀態無法復原。以上都不代表上表的驗收是在該世界進行。

以下舊清單條目已被後續 milestone 取代，不另外驗收：

- M1「全程沒有傳送、走廊或新 Dimension／Universe」與 M1.1「全程沒有穿越、走廊或 Universe／Dimension allocation」：M2 起就有走廊。
- M1「需新 edge 才 arm」、M1.1「重新啟用 held-high 不直接 11；新低→高才 11」：M1.2 起不需要 edge，改驗 B-3c。
- M1.1「停用後 Creative 左鍵 C 拆除」：M1.2 起必須先斷電到 OFF，改驗 B-3a 步驟 4 與 B-3d。
- M1.1「新建完工且未紅石啟動時可 Creative 拆改」併入 B-0 步驟 1 與 B-3d 步驟 6。

## E. Gate 結論

合併 `main` 前依序確認：

1. A、B、C 每個子項，以符合前置條件（含 build 條件）的**最新一列**為準，結果都是 PASS，或是 N/A 且備註寫明理由。選測子項（B-5f、B-6c、B-6f、C-5）沒做時記 N/A 並寫「選測未做」。「B-5 預驗」表標為「計入 E 段：否」，不採計。需要使用者同意的 N/A 或 BLOCKED，記入下方 waiver 表。
2. D 段補填 build 與日期；無法補填時，由使用者決定如何處理並寫在 D 段備註。
3. [AGENTS.md](../../AGENTS.md)「下一步」的 M2 整分支 final review：已於 2026-09-29 隨 M1–M4 整分支 review 完成，findings 由 integration fix rounds 1–3 修正並 re-review，見 [整合審查紀錄](../implementation-notes/2026-09-29-m1-m4-integration-review.md)。2026-09-30 人工驗收途中發現的 -0.0 入場缺陷，由 round 4（`ba854e8`）修正並經 fresh review（見下方 2026-09-30 程式變更簽註）。
4. feature HEAD 在 GitHub Actions 的 Ubuntu 與 Windows job 都通過（以 GitHub Actions 上該 commit 的 run 為準；本機 gate 不能代替）。
5. 記錄人工結果的同一個 docs commit，同步更新 AGENTS.md「下一步」第 2 步列出的狀態句。
6. 以上都完成後，才以 `git merge --ff-only` 合併 `main`，再依 AGENTS.md「下一步」打 tag，並以獨立 docs commit 更新整合狀態。

任一子項 FAIL：回到對應 milestone 修正，不在本清單改判。修正後在新 HEAD 重跑 automated gates，並重驗受影響的人工項目（至少包含同一子項，以及走同一條程式路徑的子項）；新結果另起一列，不覆寫原本的 FAIL。BLOCKED 先排除環境問題再重驗；排除不了時，與 N/A 一樣由使用者決定。

**review 引起的程式變更**：驗收期間若因 review 或 triage 修改了程式（2.2 的 production 指令有輸出）：

1. 在新 HEAD 重跑 automated gates。
2. 依附錄「預期結果的程式依據」逐列對照 diff：預期文字需要改、或所依據的程式位置被修改的子項，視為受影響，在新 HEAD 重驗，新結果另起一列。
3. 未受影響且已 PASS 的列保留原本的 build SHA，由 reviewer 在該列備註簽註「未受 `<舊 SHA>..<新 SHA>` 影響」與依據（例如 diff 沒有觸及附錄列出的檔案）。
4. 只改 testmod、CI 或文件時，可以用 release JAR 內每個非 `META-INF` 的 class 檔，其 hash 與基準 build 的 JAR 逐一相等，證明 production 未變；這時所有已 PASS 的列都保留。
5. 以 docs commit 更新本清單開頭與 2.2 的 `$base`，並同步附錄的行號。

**2026-09-30 程式變更簽註（第 2–3 條）**：驗收途中發現「參與者 server 端 yaw／pitch 或來源座標分量恰為 -0.0 時，入場 ARMING 的 checked 落盤必定失敗」。原因是 NBT 把 ±0 都寫成 +0，而 strict readback 以 record equals 比對（`Float.compare`／`Double.compare` 會區分 ±0）。結果是 Controller 停在 READY，每秒重試並記一則 WARN，直到玩家轉動視角。Integration fix round 4 `ba854e8` 在 `SessionRecoveryRecord.Participant` 的建構子把 -0.0 正規化為 +0，另加 2 個回歸測試（RED→GREEN）。fresh reviewer：spec ✅、quality Approved。`ba854e8` 的 automated gates 全部重跑：default GameTest 146/146、JUnit 403/0 失敗、legacy 1/1、main-only oracle、M3、M4 recovery 24/24、CI 規則核對。GitHub Actions run `36680727775` 的 Ubuntu 與 Windows job 都通過。reviewer 逐列判定，所有在 `d03bbc6` 記錄的 PASS 列都未受影響，無需重驗，各列備註末尾有簽註。依據：
- R1：production 只改了附錄沒有列出的 `SessionRecoveryRecord.java`，附錄所列檔案與行號都沒有變。
- R2：該列在 `d03bbc6` 已成功入場，代表快照中沒有 -0.0；新版對同一輸入不改值，行為相同。適用 A-1、A-2、B-1a、B-1b、B-3c、B-6a–f、C-1b、C-1c、C-2a 步驟 5、C-3a、C-3b、C-4a–c、C-5。
- R3：該列沒有建立入場快照。適用 B-0、B-3a、B-3b、B-3d、C-1a、C-2a 步驟 1–4。
- R4：重啟讀回的 record 原本就是 +0。適用 C-2b、C-3b。

B-2、B-4、B-5 都在 `ba854e8` 驗收。B-5b、B-5c 的預期依使用者 2026-09-30 決定改依 M4 設計更正（見 B-5 預期結果的「2026-09-30 更正」段）。原 FAIL（待判讀）列保留；依更正後預期判定的新列為 PASS。B-5b、B-5c 不適用上方「任一子項 FAIL」條款：程式行為符合 M4 設計 §10，錯的是清單預期；依使用者決定更正的是預期文字，不是改判程式結果，也不是 waiver。

Gate waiver（只有在使用者決定帶著未 PASS 的項目合併時才填）：每一項都要記錄，並依 AGENTS.md「使用者 2026-09-24 決定」第 3 項，標示 `main` 是開發快照、不適合未備份的正式世界。

| 項目 | 理由 | 日期 | 決定者 |
| --- | --- | --- | --- |
| （無） | 所有子項的最新有效列都是 PASS；不需要 waiver | 2026-09-30 | — |

最終結論：

| 結論（可合併／不可合併） | 日期 | 決定者 | 依據 commit |
| --- | --- | --- | --- |
| 可合併（仍須本清單所在 docs commit 在 GitHub Actions 雙平台通過後，才以 `--ff-only` 合併） | 2026-09-30 | Ben（使用者於 AskUserQuestion 核可；controller 轉錄） | code `ba854e8`（人工驗收的程式基準） |

合併紀錄：本清單所在的 docs commit `9bce72e` 在 GitHub Actions run `36691210399` 的 Ubuntu `build` 與 Windows `windows-native` 都通過後，2026-09-30 以 `git merge --ff-only` 合併 `main`（`ddc8b1e..9bce72e`），並建立 annotated tag `m1-m4`。

## 附錄：預期結果的程式依據

以 `ba854e8`（清單開頭與 2.2 的 `$base`）為準（`428f52a..ba854e8` 的 production 變更只有 `main/persistence/SessionRecoveryRecord.java`，不在下表；下表所列檔案與行號不變）；`main/` 代表 `src/main/java/dev/quantumchamber/`。程式變更後，預期結果要依 E 段「review 引起的程式變更」條款重新核對。

| 預期 | 依據 |
| --- | --- |
| 比較器只有 0／3／7／11 | `main/chamber/ChamberStatusSignal.java:7-13` |
| 供電空艙為 IDLE；停用為 INVALID；ARMING 為 READY、活動為 ARMED；返還未完成維持 ARMED | `main/chamber/ChamberPowerCoordinator.java:111-156`（`:117-126` 返還、`:139-150` 資格與入場） |
| B-0：草稿殼體無效或未供電時為 INVALID 且不註冊；殼體完整並供電才註冊 | `main/chamber/ChamberPowerCoordinator.java:73-86` |
| B-0：每 20 ticks 刷新一次 | `main/chamber/ChamberControllerBlock.java:28`（`REFRESH_DELAY_TICKS = 20`）、`:115-122`、`:140-142` |
| B-0：已登錄但殼體無效也是 INVALID | `main/chamber/ChamberActivationService.java:68-71`、`main/chamber/ChamberPowerCoordinator.java:142-143` |
| B-0：未登錄位置不受保護；已登錄的殼體只有完成協調的 OFF 才可修改 | `main/chamber/ChamberProtectionService.java:94-106` |
| 正常情況下 READY 只出現在準備入場（ARMING／STAGING）；`start()` 回 `REJECTED` 時停在 READY | `main/chamber/ChamberPowerCoordinator.java:141`、`:146-150` |
| 資格：非旁觀者、碰撞箱完整在室內、全員有效果、門關 | `main/chamber/ChamberOccupantService.java:15-21`、`main/chamber/ChamberActivationEvaluator.java:9-16` |
| 入場需持續供電與玩家預檢；DORMANT 不佔用參與者；返還中的 session 仍佔用已返還者 | `main/superposition/SuperpositionSessionManager.java:97-103`、`main/persistence/SessionRecoveryState.java:146-151`、`:246-250` |
| A-1、C-1b：入場前 checkpoint 能力預檢；不支援時 REJECTED 停在 READY、同一原因只記一次 WARN、恢復記 INFO | `main/superposition/SuperpositionSessionManager.java:103-106`、`:141-153`；`main/persistence/PlayerCheckpointStore.java:15`、`:24-40`；`main/persistence/WindowsPlayerCheckpointVerifier.java:54-82`；`main/persistence/WindowsCheckpointNative.java:38-45`；`main/chamber/ChamberPowerCoordinator.java:149` |
| 關門訊息、停用後普通門控訊息 | `main/chamber/ChamberControllerBlock.java:85-94` |
| 空手蹲下右鍵才進維護；持物蹲下走原生放置 | `main/chamber/ChamberControllerBlock.java:73-75` |
| 停用／啟用訊息；拆除須 OFF；拆除成功訊息 | `main/chamber/ChamberMaintenanceInteraction.java:21-32`、`:55-67` |
| schema1 可讀、讀成 `UNKNOWN`；寫回 schema2 | `main/chamber/ChamberRegistryState.java:94`、`:134`、`:202-203` |
| 側門點擊由候選互動處理；鎖定與已鎖定訊息 | `main/chamber/QuantumBulkheadBlock.java:49-52`、`main/candidate/CandidateDoorInteraction.java:40-47` |
| 側門為兩側牆面、每 8 格一扇 5×5 | `main/corridor/CorridorGeometry.java:74-83` |
| DORMANT 原艙：presence 為 UNKNOWN、LOW 不能 OFF | `main/superposition/SuperpositionSessionManager.java:66-76`、`:323-327`、`main/corridor/CorridorPageManager.java:216-224` |
| 活動中效果失效即整組返還（正常到期或喝奶記 INFO「…（正常結束，非故障）」、不附 stack）；MEASURED 走保留式返還 | `main/superposition/SuperpositionSessionManager.java:179-196`、`:369-386` |
| 重啟時非 DORMANT 紀錄改為返還，不重建舊 session | `main/superposition/SuperpositionSessionManager.java:61-64`、`:387-395` |
| 斷線觸發整組返還；JOIN 下一 tick 返回 | `main/persistence/SessionRecoveryManager.java:57-69`、`:81-89`、`:140` |
| 2.5 (d)、A-2、B-1b、C-2b、C-3b：返還期間只擋尚未返還者的移動、載具與方塊互動，已返還者不擋；原艙保護與比較器 11 維持到全員收尾 | `main/persistence/SessionRecoveryManager.java:45-54`；`main/mixin/ServerPlayNetworkHandlerRecoveryMixin.java:17-29`；`main/chamber/ChamberPowerCoordinator.java:117-126` |
| 2.5 (e)：ARMING 中止只拉回已嘗試移動或在走廊世界的成員 | `main/superposition/SuperpositionSession.java:25-26`、`main/superposition/SuperpositionSessionManager.java:286`、`:355-358` |
| 走廊掉落物與經驗球計入 pin、跨頁面保留，返還到原艙中央（B-1b、B-6d、C-3、C-4） | `main/corridor/SuperpositionEntityPolicy.java:37-41`；`main/corridor/CorridorPageManager.java:1015-1026`；`main/corridor/CorridorRepositionService.java:88`；`main/persistence/SessionRecoveryManager.java:172-183` |
| B-6a 範圍說明（非人工項）：入口 Controller 在關閉正門的正上方、兩側與內側是基岩，原版 client 瞄不到；LATERAL 走廊內的切換請求一律拒絕並顯示訊息，拒絕結果由 GameTest `trusted_lateral_geometry_*_windows` 斷言，訊息文字依程式 | `main/corridor/SessionEntranceAllocator.java:22-32`、`main/chamber/QuantumBulkheadBlock.java:34-41`；`main/chamber/ChamberControllerBlock.java:67-72`、`main/corridor/SessionEntranceDoorService.java:10-14`、`main/corridor/CorridorPageManager.java:450-460`；`src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java:243-250`、`:260`、`:262` |
| B-6a：入口艙正面門格不是側門，也不是完整艙體，點擊不處理 | `main/corridor/CorridorPageManager.java:252-274`、`main/chamber/QuantumBulkheadBlock.java:49-60`、`main/chamber/ChamberLocator.java:14-26` |
| B-6b：走廊世界使用放置類物品即拒絕、不消耗、actionbar 訊息 | `main/mixin/ItemStackSuperpositionEntityMixin.java:22-40`、`main/corridor/SuperpositionEntityPolicy.java:43-47`、`:63-79` |
| B-6c、B-6f：非管理 entity 加入世界或跨維度（含 `/tp`）進入走廊世界即拒絕，每種類每路徑一次 WARN | `main/mixin/ServerWorldEntityAdmissionMixin.java:21-27`、`main/mixin/EntitySuperpositionTeleportMixin.java:27-43`、`main/corridor/SuperpositionEntityPolicy.java:81-89` |
| B-6e、C-5：走廊世界不放下肩上鸚鵡（server 與本機 client） | `main/mixin/PlayerEntityShoulderSuperpositionMixin.java:21-24`、`main/corridor/SuperpositionEntityPolicy.java:49-61` |
| B-6 範圍外：走廊內方塊受保護，無法放置 | `main/mixin/WorldSetBlockStateMixin.java:28-43`、`main/corridor/SessionSpaceProtection.java:16`、`:22-24` |
| B-5d／e／f：MEASURED 原生存檔 checkpoint 失敗時 per-space 退避 20→1200 ticks、WARN 去重 | `main/corridor/CorridorPageManager.java:60`、`:845-849`、`:858-867`、`:875-883`、`:1049-1059` |
| 輝光只在 POWERED 時由原生粒子輸出，每 20 ticks 一次、4 秒呼吸 | `main/chamber/ChamberGlowEmitter.java:32-57`、`main/chamber/ChamberGlowPattern.java:9-39`、`main/chamber/ChamberControllerBlock.java:115-122` |
| 入口艙左右兩側開放 | `main/corridor/SessionEntranceAllocator.java:29-30` |
| 藥水效果 3600 ticks | `main/registry/ModPotions.java:15-18` |
| 建立 session 後 journal 寫成 schema3 | `main/persistence/SessionRecoveryState.java:171`、`:192` |
