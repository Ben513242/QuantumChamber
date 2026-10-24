# QuantumChamber

> **M5 已整合（2026-10-24）：** main 已包含 `a2d2b40`，annotated tag `m5` 指向該版本，兩者已推送。塌縮與單向通道、F7、人工 smoke 與獨立 review 均已完成（Critical／Important 0）；純整合狀態文件依本輪裁示採計程式相同的 `f99ce66` 雙平台／同 SHA 兩次 Ubuntu CI。完整證據、已知限制與實際 refs 見 [M5 實作紀錄](docs/implementation-notes/2026-10-01-m5-collapse-passage.md)。M6 尚未開始。

> **功能分支 `feature/m1-chamber`（M1–M4）狀態，驗收分層記錄：** 以下都已實作，並完成自動 gate 與逐 task review：
>
> - M2 左右走廊、入場保留 QuantumState Buff、任一凍結參與者 Buff 失效則整組返還。
> - M3-A 動態 Universe backend 與 M3-B server-side transfer readiness。
> - M4 候選門。
>
> M1（含 M1.1）與 M1.2 的全 feature final review 已完成；M3-A、M3-B 與 M4 另各自通過 whole-branch review（Critical／Important 0）。2026-09-29 另完成 M1–M4 整分支 final review（同時作為 M2 的整分支 final review），找到的 7 項 Important 已由 integration fix rounds 1–3 修正並 re-review；2026-09-30 人工驗收途中發現的入場 bug 由 integration fix round 4（`ba854e8`）修正並 review，見 [M1–M4 整合審查紀錄](docs/implementation-notes/2026-09-29-m1-m4-integration-review.md)。M1／M1.1／M1.2／M2／M4 的人工驗收已於 2026-09-30 記錄（[M1–M4 人工驗收清單](docs/manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md) E 段：無 gate waiver，結論可合併）。記錄結果的 docs commit `9bce72e` 在 GitHub Actions run `36691210399` 的 Ubuntu 與 Windows job 都通過後，2026-09-30 以 `--ff-only` 合併 main（`ddc8b1e..9bce72e`），並建立 annotated tag `m1-m4`。實際 CI、合併與 tag 狀態以 GitHub Actions、`main`／tag 為準。M2 當時的跨 JVM 持久化與產物 gate 見 [M2 當時修訂狀態](docs/implementation-notes/2026-09-18-m2-revision-status.md)。

QuantumChamber 是一個以伺服器權威為核心的 Minecraft Fabric 模組原型；其長期設計目標是支援具持久狀態的量子疊加 Chamber 與平行 Universe。

## M2 供電走廊

功能分支 `feature/m1-chamber` 已接上左右走廊、群體換頁與安全返還：外部先供電，玩家完整入艙、關門且全員具 QuantumState 後，進入固定的 `quantumchamber:superposition` 世界，保留當前效果與自然倒數。喝藥的瓶子消耗遵循原生規則；入場不另消耗 Buff。任一凍結參與者的效果自然到期或被牛奶解除，全組返回同一原艙且不退款藥效。仍 HIGH 時原艙保持保護；全員補喝、關門並滿足資格可建立新 SID。LOW 時先完成玩家與有價物品返還、租約清理，再解除保護；離線或來源身分不符持續 pending。返還期間只凍結尚未返還的玩家：已返還者回到原艙就能正常移動與互動，但原艙保護與比較器 11 維持到全員收尾，收尾前也不能開新 session；離線者重新連線後的下一 tick 才返還。

M1–M5 的已合併版本請從主 checkout `C:\Users\Ben\Documents\minecraft QuantumChamber` 啟動 `start-client.bat`，測試新版本仍使用世界複本。M5 開發工作樹是指定退役目標，不作為日後啟動入口。完整 M2 流程與原人工驗收結果見 [M2 操作與驗證紀錄](docs/implementation-notes/m2-corridor.md)。

走廊是有限局部頁面與外觀延伸，本身仍是固定的 `quantumchamber:superposition` 世界。main 的 M5 已把 checked 候選收據接成塌縮與單向跨宇宙通道；玩法及已完成的人工驗收見下節。

走廊世界只接受玩家、掉落物、投射物與經驗球。在走廊內使用船／箱船、礦車、盔甲架、物品展示框／螢光物品展示框、畫、生怪蛋、裝有生物的桶、終界水晶、拴繩或滯留型藥水會被拒絕，物品不扣，actionbar 顯示「量子走廊內不能放置船、盔甲架、展示框、生物等實體；物品未消耗。」。以生成方式（例如 `/summon`、蛋孵出的小雞）進入走廊的其他 entity 會被拒絕，不會出現在走廊；以跨維度傳送（傳送門、`/tp`、`/execute in … run tp`、`/spreadplayers`）送進來的會被拒絕並留在原處。這些指令被拒時，原版仍會顯示成功（已知限制）。經驗球比照掉落物跨頁面保留、返還時送到原艙中央；肩上的鸚鵡在走廊內不會被放下，返還後仍在肩上。入口艙的正面門位於走廊牆面，在走廊內保持關閉；入口 Controller 在正門正上方、兩側與內側是基岩，原版 client 瞄不到，修改版 client 送出的切換請求也一律拒絕。

M4 當時，完整側門右鍵只會 checked 鎖定候選，門保持關閉、不配置 Universe；返還後的 DORMANT 收據把原艙封鎖，等待 M5 接手。M5 沿用該收據續行，不重抽候選；M4 的歷史行為與證據見 [M4 候選門紀錄](docs/implementation-notes/2026-09-21-m4-candidate-doors.md)。

**不可降版與存檔檔案**：M4 曾把 session journal 寫成 schema 3，M4 之前的 build 不能讀。M5 第一次完成 M5 authority 初始化後的 checked 寫入會把 journal 升為 schema 4；此後 M4 build 讀取會 fail closed。M5 的 `data/quantumchamber_universe_allocations.dat` 與 `data/quantumchamber_projections.dat` 不可刪除或用舊備份覆蓋。測試新版本請用世界複本。

2026-09-30 已記錄人工驗收：單人玩法（清單 A、B-0、B-1、B-3、B-6）、近玩家 seam（C-4）、shader 與 GPU（B-2：Sodium 0.6.13、Iris 1.8.8、Complementary Reimagined r5.9.3，RTX 4090）都是 PASS；32 chunk 遠望與手持照明依使用者 2026-09-24 的回報採計（清單 D 段，沒有 build 紀錄）。合併 main 前的完整 gate 順序見 [AGENTS.md](AGENTS.md)「下一步」。

逐項人工驗收步驟、預期結果與紀錄欄位見 [M1–M4 人工驗收清單](docs/manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md)。

## M5 塌縮與單向通道

在 Overworld 側門選好候選之後，參與者先安全回到原艙；目的地準備期間門口是不可穿過的量子膜，準備完成才可穿過（比較器 15）。NEW／EXISTING 會在相同座標到達目的宇宙，畫面中央分別顯示「你已進入新的宇宙。」／「你已進入另一個宇宙。」；目的地是同 seed 的原始地形，**不是來源世界的複製**，身後的基岩投影艙不能啟動。SOURCE 候選只會塌縮並讓門一般開啟，不配置宇宙或傳送。M5 量子膜、兩軸向光影與跨越畫面已由 [人工 smoke](docs/manual-acceptance/2026-10-01-m5-manual-smoke.md) 實際驗收並經獨立 review。

通道在全員跨過、約 60 秒到期、參與者關門或斷電時結束；已跨過的人不再收到「量子通道已關閉。」。本切片沒有正規回程、目的地預覽、完整 Nether／End family 或在當前宇宙重生；非參與者與騎乘中的參與者不能藉膜傳送，物品與坐騎不跟隨。新的 Nether／End 側門選擇會被拒絕。重啟後在原艙內登入，來源艙載入完成時會照原收據續行；玩家不必為此再進出一次。`ALREADY_SELECTED` 的「候選已鎖定。」在真 client 不保證看得到，請以實際收據與後續塌縮判斷。

永久原因的 SEALED 會讓原艙維持封鎖，管理員需看 ERROR；玩家仍可照一般方式開門離開。Windows 原子移動短暫 `AccessDeniedException` 依既有 fail-closed 與退避處理，外部持有者未查明。其他已知限制、存檔上限與模組接點見 [M5 實作紀錄](docs/implementation-notes/2026-10-01-m5-collapse-passage.md) 和 [相容性說明](docs/implementation-notes/compatibility.md)。

已採計的本機完整 gate 基準為 default 179（其中 Windows 89）、legacy 1、M5 GameTest 60、JUnit 666（2 skip）、M5 跨 JVM collapse probe 69 phase（31 HALT）、release 432 entries／385 classes、sources 226；完整 root、其他項目與本輪最終結果以 [驗證 README](scripts/verification/README.md) 為準。已知偶發包括 Windows 原子移動拒絕、`m2_task4_disconnect_io` 多一則 WARN、走廊 test 未收尾引發 `m2_pin_capacity` 連坐，以及跨午夜的 log 輪替；失敗 root 必須保留，歸屬見 [M5 handoff §6](docs/handoffs/2026-10-21-m5-wrapup-to-m6-codex.md)。

## M1.2 基礎與歷史驗證

已實作 7×7×7 Chamber、25 格整面 Bulkhead、Controller 右鍵整面門控、QuantumState 藥水、Origin registry 持久化，以及依實際紅石電位協調的原艙保護與自動 `ARMED`。Comparator 狀態為 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。

2026-09-17 初次 M1.2 非快取 `clean build runGameTest --rerun-tasks` 通過 111 個 JUnit（含 13 個 Windows 啟動腳本測試）與 52 個 Fabric GameTests，零失敗、零跳過。當時四次獨立 Java 程序驗證供電→OFF→重供電→拆除，另有不含 testmod 的 dedicated Done→stop 與 release JAR／common-server 邊界證據。損壞 schema 會被健康 guard 拒絕；原生 launcher 可能仍回傳 0，故以明確例外、沒有正常 tick、原資料雜湊不變及外部驗證器非零共同判定，不能只看 Done 或 Java 退出碼。

同日最終修正後，再以全新隔離 fixture 完整重跑上述建置，通過 112 個 JUnit 與 56 個 GameTests，零失敗、零跳過。新增原生反例涵蓋：已保存 OFF 在載入協調前仍受保護（普通拆除與創造模式攻擊均拒絕）、控制器已 FULL 但紅石輸入鄰區未 FULL 時不強載且保留重試，以及損壞 gzip／NBT／底層讀取失敗時拒絕啟動並保留資料。完整證據與人工待驗界線見 [M1.2 驗證紀錄](docs/implementation-notes/m1.2-powered-origin.md)。

新建艙體未供電時不註冊；有效空艙即使開門，也能先由外部供電取得 UUID 與保護。進艙、關門並補齊全員 QuantumState 後，持續高電位會自動進入 `ARMED`，不用再按一次拉桿。Controller 普通右鍵開關門；雙手空手蹲下右鍵切換管理用 `Enabled`，它與供電分開。斷電確認安全返還後才進入 `OFF`、輸出 0 並解除原艙保護，但 UUID 與碰撞占位保留；重新供電沿用 UUID。只有 `OFF` 的 Controller 真正成功移除後，才清除紀錄及全部索引，外殼不自動刪除；`Enabled=true` 也可在 OFF 拆除。schema1 可讀為保守的 `UNKNOWN`，schema2 保存獨立供電狀態。

上述 M1.2 歷史驗證當時使用 `NONE`／`ARMED_ONLY` adapter；目前功能分支已由 M2 真 session 接替。自動測試與歷史 client runtime 不代表本版 GUI 驗收；啟動腳本測試在 Linux CI 明確 skip。玩家 checkpoint 的原生 HANDLE 後端目前只對已驗的 Windows 本機 NTFS 條件提供成功證據。非 Windows，或 Windows 上 playerdata 不在本機固定 NTFS（UNC／網路磁碟機、非 NTFS、卸除式磁碟，或路徑上有 junction 等 reparse point，例如部分雲端同步資料夾）時，`start()` 會在任何預留、效果或玩家移動之前拒絕入場：原艙停在 READY（比較器 7），同一原因只記一次 WARN，恢復時記 INFO。這是受控拒絕，不是成功恢復。已知殘留：預檢只檢查目錄鏈，玩家 `.dat` 本身是 reparse／hardlink 或超過 64 MiB 時，仍要到入場後的 checkpoint 才會發現。

量子艙門現為紫色面板，腔室控制器現為青色識別板與正面核心；兩者保留基岩底層／外框，使用一般模型與原生材質，不新增 renderer 或光源。方塊 ID 不變，既有艙體不需拆掉重建。

## 鎖定版本

- Minecraft 1.21
- Java 21
- Gradle Wrapper 8.8
- Fabric Loom 1.7.4
- Fabric Loader 0.17.2
- Fabric API 0.102.0+1.21
- Yarn 1.21+build.9

## 前置條件

請安裝 Java 21，並從專案根目錄使用隨附的 Gradle Wrapper。不要以全域安裝的 Gradle 取代 Wrapper。

## 建置與開發啟動

Windows 可在檔案總管雙擊專案根目錄的 [start-client.bat](start-client.bat)，或在 PowerShell 執行 `./start-client.bat`。腳本使用 Java 21，固定載入同一工作區的 Fabric 開發客戶端；失敗會保留錯誤與原始退出碼。

功能分支 `feature/m1-chamber`（M1–M4）的人工驗收已於 2026-09-30 記錄，同日以 `--ff-only` 合併 main 並建立 tag `m1-m4`；實際合併與 tag 狀態以 `main`／tag 為準。本機從主 checkout 啟動。原 `.worktrees\m1-chamber` 的 `run/`（含世界）已備份到 `C:\Users\Ben\Documents\QC-m1-m4-worktree-backup-20261001\run\`，要繼續玩其中的世界，從那裡複製到主 checkout 的 `run/client-base/saves/`。另一台電腦直接 checkout `feature/m1-chamber` 時，在該 clone 根目錄執行即可。舊客戶端不會熱載入程式修改，請先正常儲存並退出，勿同時開同一世界。

Windows PowerShell：

```powershell
.\gradlew.bat clean build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runGameTest
```

`runClient` 使用隔離的 `run/client-base` 開發目錄；`runServer` 使用隔離的 `run/server` 目錄。首次 dedicated server 啟動會要求操作者在 `run/server/eula.txt` 明確接受 Minecraft EULA；在接受前不應啟動伺服器世界。

**`runServer` 注意**：本工作區的 `run/server/server.properties` 目前是 `level-name=m1-smoke`。`m1-smoke` 是 M1 dedicated 重啟證據，也是人工驗收 B-4 唯一的 schema1 世界；用本 build 直接執行 `runServer` 開一次，就會把它的 Chamber registry 改寫成 schema2。要開 dedicated server，先依 [M1–M4 人工驗收清單](docs/manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md) 2.1 備份，並依 2.3 改用其他世界（停機時暫改 `level-name`，啟動時帶 `--world`）。

選用手持火把動態照明可執行 `start-client.bat light`，使用獨立的 `run/client-light` 與固定版本、SHA512 核對的 LambDynamicLights。預設 client-base、server、GameTest 與 release JAR 不安裝或內嵌它。不同 profile 不共用存檔；需要搬移時請先退出遊戲、自行備份再複製，腳本不會自動搬移玩家世界。光影與 shader 相容性已於 2026-09-30 在 `run/client-render` 以 Sodium 0.6.13、Iris 1.8.8 與 Complementary Reimagined r5.9.3（RTX 4090）人工驗收（清單 B-2）；手持火把照明依使用者 2026-09-24 的回報採計（清單 D 段 M2-8）。

`runGameTest` 使用 `run/gametest`，報告位於 `build/gametest-results.xml`；單獨 `clean build` 不包含此工作。M2 CI 與本機完整 gate 另明確執行 GameTests，不能把建置成功當成遊戲測試已跑。重現步驟與人工待驗見下方紀錄。

## 設計與執行紀錄

- [M5 binding spec](docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md)
- [M5 實作與驗證紀錄](docs/implementation-notes/2026-10-01-m5-collapse-passage.md)
- [M5 相容性與實測範圍](docs/implementation-notes/compatibility.md)
- [M5 人工 smoke 清單（待驗）](docs/manual-acceptance/2026-10-01-m5-manual-smoke.md)

- [設計規格](docs/quantum_superposition_chamber_design.md)
- [M0 Bootstrap 計畫](docs/plans/2026-09-15-m0-bootstrap.md)
- [M0 實作與驗證紀錄](docs/implementation-notes/m0-bootstrap.md)
- [M1 Chamber Foundation 計畫](docs/plans/2026-09-15-m1-chamber.md)
- [M1 實作與驗證紀錄](docs/implementation-notes/m1-chamber.md)
- [M1 逐步施工與玩家驗證指引](docs/implementation-notes/m1-player-build-verification.md)
- [M1 可放大施工格線圖](docs/images/m1-chamber-build-guide.svg)
- [M1.1 核准契約](docs/implementation-notes/m1.1-contract.md)
- [M1.1 紅石提交與 Origin 維護計畫](docs/plans/2026-09-17-m1.1-origin-maintenance.md)
- [M1.1 實作、基線限制與驗證紀錄](docs/implementation-notes/m1.1-origin-maintenance.md)
- [M1.2 供電原艙計畫](docs/plans/2026-09-17-m1.2-powered-origin.md)
- [M1.2 自動證據、重啟與人工待驗](docs/implementation-notes/m1.2-powered-origin.md)
- [M2 供電走廊計畫](docs/plans/2026-09-17-m2-powered-corridor.md)
- [M2 單人操作、持久化與人工待驗](docs/implementation-notes/m2-corridor.md)
- [M2 當時修訂狀態與 M3 交接（歷史）](docs/implementation-notes/2026-09-18-m2-revision-status.md)
- [M3 動態 Universe 基底設計](docs/superpowers/specs/2026-09-19-m3-dynamic-universe-foundation-design.md)
- [M3 runtime-dimension 可行性探查](docs/implementation-notes/2026-09-19-m3-runtime-dimension-feasibility.md)
- [M3-A 動態 Universe backend 計畫](docs/superpowers/plans/2026-09-19-m3a-dynamic-universe-backend.md)
- [M3-A 實作與證據](docs/implementation-notes/2026-09-19-m3a-dynamic-universe-backend.md)
- [M3-B server-side transfer readiness 計畫](docs/superpowers/plans/2026-09-19-m3b-server-transfer-readiness.md)
- [M3-B 實作與證據](docs/implementation-notes/2026-09-19-m3b-server-transfer-readiness.md)
- [M4 候選門設計規格](docs/superpowers/specs/2026-09-21-m4-candidate-doors-design.md)
- [M4 候選門計畫](docs/superpowers/plans/2026-09-21-m4-candidate-doors.md)
- [M4 候選門權威、驗證證據與 M5 交接](docs/implementation-notes/2026-09-21-m4-candidate-doors.md)
- [M1–M4 整分支 final review 與整合修正紀錄](docs/implementation-notes/2026-09-29-m1-m4-integration-review.md)
- [M1–M4 人工驗收清單](docs/manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md)

## 授權

No license has been granted for this repository. All rights are reserved unless a license is added later.
