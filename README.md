# QuantumChamber

> **新版 M2 已實作，驗收分層記錄：** 左右延伸、入場保留 QuantumState Buff、任一凍結參與者 Buff 失效則整組返還已接入原生流程；跨 JVM 持久化與產物 gate 的最新結果見 [修訂狀態](docs/implementation-notes/2026-09-18-m2-revision-status.md)。八項人工驗收與整分支 final review 留待整體收尾，尚未合併 main。

QuantumChamber 是一個以伺服器權威為核心的 Minecraft Fabric 模組原型；其長期設計目標是支援具持久狀態的量子疊加 Chamber 與平行 Universe。

## M2 供電走廊

功能分支 `feature/m1-chamber` 已接上左右走廊、群體換頁與安全返還：外部先供電，玩家完整入艙、關門且全員具 QuantumState 後，進入固定的 `quantumchamber:superposition` 世界，保留當前效果與自然倒數。喝藥的瓶子消耗遵循原生規則；入場不另消耗 Buff。任一凍結參與者的效果自然到期或被牛奶解除，全組返回同一原艙且不退款藥效。仍 HIGH 時原艙保持保護；全員補喝、關門並滿足資格可建立新 SID。LOW 時先完成玩家與有價物品返還、租約清理，再解除保護；離線或來源身分不符持續 pending。

本機請從 `C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber` 啟動 `start-client.bat`；選用照明為 `start-client.bat light`。主目錄的 `main` 較舊，不能用其客戶端驗收此功能。完整單人／多人流程、Buff 到期返還、選用外部計時斷電與八項人工待驗，見 [M2 操作與驗證紀錄](docs/implementation-notes/m2-corridor.md)。

走廊是有限局部頁面與外觀延伸，並非無限配置世界；沒有動態 Dimension、Universe 選擇或 M3 跨宇宙通道。人工單人玩法、32 chunk 遠望、近玩家 seam、照明／shader／GPU 尚待驗證，最終整體評審由 root 另行執行。此快照供遠端同步審查，尚未合併 main。

## M1.2 基礎與歷史驗證

已實作 7×7×7 Chamber、25 格整面 Bulkhead、Controller 右鍵整面門控、QuantumState 藥水、Origin registry 持久化，以及依實際紅石電位協調的原艙保護與自動 `ARMED`。Comparator 狀態為 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。

2026-09-17 初次 M1.2 非快取 `clean build runGameTest --rerun-tasks` 通過 111 個 JUnit（含 13 個 Windows 啟動腳本測試）與 52 個 Fabric GameTests，零失敗、零跳過。當時四次獨立 Java 程序驗證供電→OFF→重供電→拆除，另有不含 testmod 的 dedicated Done→stop 與 release JAR／common-server 邊界證據。損壞 schema 會被健康 guard 拒絕；原生 launcher 可能仍回傳 0，故以明確例外、沒有正常 tick、原資料雜湊不變及外部驗證器非零共同判定，不能只看 Done 或 Java 退出碼。

同日最終修正後，再以全新隔離 fixture 完整重跑上述建置，通過 112 個 JUnit 與 56 個 GameTests，零失敗、零跳過。新增原生反例涵蓋：已保存 OFF 在載入協調前仍受保護（普通拆除與創造模式攻擊均拒絕）、控制器已 FULL 但紅石輸入鄰區未 FULL 時不強載且保留重試，以及損壞 gzip／NBT／底層讀取失敗時拒絕啟動並保留資料。完整證據與人工待驗界線見 [M1.2 驗證紀錄](docs/implementation-notes/m1.2-powered-origin.md)。

新建艙體未供電時不註冊；有效空艙即使開門，也能先由外部供電取得 UUID 與保護。進艙、關門並補齊全員 QuantumState 後，持續高電位會自動進入 `ARMED`，不用再按一次拉桿。Controller 普通右鍵開關門；雙手空手蹲下右鍵切換管理用 `Enabled`，它與供電分開。斷電確認安全返還後才進入 `OFF`、輸出 0 並解除原艙保護，但 UUID 與碰撞占位保留；重新供電沿用 UUID。只有 `OFF` 的 Controller 真正成功移除後，才清除紀錄及全部索引，外殼不自動刪除；`Enabled=true` 也可在 OFF 拆除。schema1 可讀為保守的 `UNKNOWN`，schema2 保存獨立供電狀態。

上述 M1.2 歷史驗證當時使用 `NONE`／`ARMED_ONLY` adapter；目前功能分支已由 M2 真 session 接替。自動測試與歷史 client runtime 不代表本版 GUI 驗收；啟動腳本測試在 Linux CI 明確 skip，玩家 checkpoint 的原生 HANDLE 後端目前只對已驗 Windows／NTFS 條件提供成功證據，非 Windows 保守拒絕而非成功恢復。

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

M2 功能分支尚未合併 main；本機請從 `C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber` 啟動。另一台電腦直接 checkout `feature/m1-chamber` 時，在該 clone 根目錄執行即可。舊客戶端不會熱載入程式修改，請先正常儲存並退出，勿同時開同一世界。

Windows PowerShell：

```powershell
.\gradlew.bat clean build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runGameTest
```

`runClient` 使用隔離的 `run/client-base` 開發目錄；`runServer` 使用隔離的 `run/server` 目錄。首次 dedicated server 啟動會要求操作者在 `run/server/eula.txt` 明確接受 Minecraft EULA；在接受前不應啟動伺服器世界。

選用手持火把動態照明可執行 `start-client.bat light`，使用獨立的 `run/client-light` 與固定版本、SHA512 核對的 LambDynamicLights。預設 client-base、server、GameTest 與 release JAR 不安裝或內嵌它。不同 profile 不共用存檔；需要搬移時請先退出遊戲、自行備份再複製，腳本不會自動搬移玩家世界。實際光影與 shader 相容性仍待人工驗證。

`runGameTest` 使用 `run/gametest`，報告位於 `build/gametest-results.xml`；單獨 `clean build` 不包含此工作。M2 CI 與本機完整 gate 另明確執行 GameTests，不能把建置成功當成遊戲測試已跑。重現步驟與人工待驗見下方紀錄。

## 設計與執行紀錄

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

## 授權

No license has been granted for this repository. All rights are reserved unless a license is added later.
