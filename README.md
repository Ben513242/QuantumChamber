# QuantumChamber

QuantumChamber 是一個以伺服器權威為核心的 Minecraft Fabric 模組原型；其長期設計目標是支援具持久狀態的量子疊加 Chamber 與平行 Universe。

## M1 Chamber Foundation

已實作 7×7×7 Chamber、25 格整面 Bulkhead、Controller 右鍵整面門控、QuantumState 藥水、Origin registry 持久化、方塊保護，以及 vanilla redstone rising edge 啟動至 `ARMED`。Comparator 狀態為 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。

目前已有 75 個 JUnit（含 6 個 Windows 啟動腳本流程測試）、28 個 Fabric GameTests、client runtime 與 dedicated-server 重啟證據；完整人工 gameplay 清單仍待驗收，M1 completion gate 尚未全部關閉。No Universe、corridor 或 teleport implementation；亦未新增動態 Dimension、Session、packet 或 renderer。啟動腳本測試在 Linux CI 明確 skip，不當作跨平台或 GPU 覆蓋。

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

M1 尚未合併 main；本機請從 `.worktrees/m1-chamber` 啟動。舊客戶端不會熱載入程式修改，請先正常儲存並退出，勿同時開同一世界。

Windows PowerShell：

```powershell
.\gradlew.bat clean build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runGameTest
```

`runClient` 使用隔離的 `run/client-base` 開發目錄；`runServer` 使用隔離的 `run/server` 目錄。首次 dedicated server 啟動會要求操作者在 `run/server/eula.txt` 明確接受 Minecraft EULA；在接受前不應啟動伺服器世界。

`runGameTest` 使用 `run/gametest`，報告位於 `build/gametest-results.xml`；它是本機 integration 驗證，現有 CI 的 `clean build` 不會自動執行 GameTests。M1 gameplay 的重現步驟、已驗證項目與人工待驗清單見下方實作紀錄。

## 設計與執行紀錄

- [設計規格](docs/quantum_superposition_chamber_design.md)
- [M0 Bootstrap 計畫](docs/plans/2026-09-15-m0-bootstrap.md)
- [M0 實作與驗證紀錄](docs/implementation-notes/m0-bootstrap.md)
- [M1 Chamber Foundation 計畫](docs/plans/2026-09-15-m1-chamber.md)
- [M1 實作與驗證紀錄](docs/implementation-notes/m1-chamber.md)
- [M1 逐步施工與玩家驗證指引](docs/implementation-notes/m1-player-build-verification.md)
- [M1 可放大施工格線圖](docs/images/m1-chamber-build-guide.svg)

## 授權

No license has been granted for this repository. All rights are reserved unless a license is added later.
