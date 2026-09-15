# QuantumChamber

QuantumChamber 是一個以伺服器權威為核心的 Minecraft Fabric 模組原型；其長期設計目標是支援具持久狀態的量子疊加 Chamber 與平行 Universe。

## M0 範圍

M0 僅建立可重現的 Fabric 開發工具鏈、common/client entrypoint 分離、選用模組相容性偵測邊界、單元測試與 CI build gate。它**尚未**實作 Chamber、Potion、Universe、動態 Dimension、renderer、封包或遊戲玩法。

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
```

`runClient` 使用隔離的 `run/client-base` 開發目錄；`runServer` 使用隔離的 `run/server` 目錄。首次 dedicated server 啟動會要求操作者在 `run/server/eula.txt` 明確接受 Minecraft EULA；在接受前不應啟動伺服器世界。

## 設計與執行紀錄

- [設計規格](docs/quantum_superposition_chamber_design.md)
- [M0 Bootstrap 計畫](docs/plans/2026-09-15-m0-bootstrap.md)
- [M0 實作與驗證紀錄](docs/implementation-notes/m0-bootstrap.md)

## 授權

No license has been granted for this repository. All rights are reserved unless a license is added later.
