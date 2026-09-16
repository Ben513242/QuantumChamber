# QuantumChamber

QuantumChamber 是一個以伺服器權威為核心的 Minecraft Fabric 模組原型；其長期設計目標是支援具持久狀態的量子疊加 Chamber 與平行 Universe。

## M1 Chamber Foundation

已實作 7×7×7 Chamber、25 格整面 Bulkhead、Controller、QuantumState 藥水、Origin registry 持久化、方塊保護，以及 vanilla redstone rising edge 啟動至 `ARMED`。Comparator 狀態為 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。

目前已有 68 個 JUnit、17 個 Fabric GameTests、client runtime 與 dedicated-server 重啟證據；完整人工 gameplay 清單仍待驗收，M1 completion gate 尚未全部關閉。No Universe、corridor 或 teleport implementation；亦未新增動態 Dimension、Session、packet 或 renderer。

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

## 授權

No license has been granted for this repository. All rights are reserved unless a license is added later.
