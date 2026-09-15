# M0 Bootstrap 實作與驗證紀錄

日期：2026-09-15

## 實作範圍

M0 建立單一 Minecraft 1.21 Fabric module、Gradle Wrapper 8.8、split environment source sets，以及 common/client entrypoint。common initializer 只透過 Fabric Loader 的 mod-id 查詢記錄 Sodium、Iris 與 Immersive Portals 的存在狀態；它不載入選用模組類別。client initializer 只記錄初始化事件。

本里程碑沒有實作 Chamber、Potion、Universe、動態 Dimension、renderer、封包或遊戲玩法。

## 鎖定執行環境

- Java：Eclipse Temurin 21.0.12.1+1
- Minecraft：1.21
- Fabric Loader：0.17.2
- Fabric API：0.102.0+1.21
- Fabric Loom：1.7.4
- Gradle Wrapper：8.8

## 驗證結果

### Build 與單元測試

`./gradlew.bat clean build` 於 Java 21.0.12.1 成功完成（11 actionable tasks，exit code 0）。`test` 與 `check` 都完成，沒有測試失敗，並產生 remapped mod JAR 與 sources JAR。

### Client 啟動

第一次 `runClient` 在 `downloadAssets` 階段失敗：Loom 嘗試存取使用者共用 Gradle cache 時，遇到 `C:\Users\Ben\.gradle\caches\fabric-loom\assets\objects\e9\e9833a1512b57bcf88ac4fdcc8df4e5a7e9d701d.lock` 的 `FileAlreadyExistsException`。沒有刪除、改名或強制解鎖該 worktree 外的 cache。

同一命令以 `--stacktrace` 重跑後完成 `downloadAssets` 並啟動 Minecraft 1.21（Fabric Loader 0.17.2、Fabric API 0.102.0+1.21）。另以 worktree 內已忽略的 `.tools/gradle-user-home` 作為獨立 `GRADLE_USER_HOME`，使用 `--no-daemon runClient` 重跑；同樣到達兩個 initializer log，未重現 asset lock。這支持共用 cache 的環境性 lock 衝突，但不構成程式碼根因的證明。兩次成功啟動皆完成 renderer/resource 載入，且 common initializer 的選用模組摘要與 `QuantumChamber client initialized` 均已出現；依此 runtime 證據視為 Minecraft 主選單就緒，開發程序隨後由驗證者中止，未見 crash。可用 GUI 自動化介面沒有列出 Minecraft 視窗，因此沒有視覺截圖佐證。

### Dedicated server 啟動

首次 `runServer` 已確認 EULA gate：Minecraft 1.21 與 Fabric Loader 0.17.2 以 SERVER 環境啟動，common initializer 記錄選用模組摘要，接著 log 顯示 `You need to agree to the EULA in order to run the server.`。使用者其後明確授權在隔離的 `run/server/eula.txt` 設為 `eula=true`。

接受後的第一次啟動已到達 `Done (3.255s)`，但非互動式 Gradle session 關閉 stdin，無法傳入 `stop`；該程序經命令列與父程序鏈核對後終止，這一輪不作為正常關閉通過證據。第二次以 TTY 執行 `runServer`，在同一 session 顯示 Minecraft 1.21、Fabric Loader 0.17.2、SERVER 環境與 common initializer，然後到達 `Done (2.426s)!`。輸入 `stop` 後 log 顯示 `Saving players`、`Saving worlds`、各維度 `All chunks are saved` 及 `All dimensions are saved`；Gradle `BUILD SUCCESSFUL` 且 exit code 0。這是 dedicated server 啟動與正常停止的 runtime 證據；本輪 log 未見 client class loading 錯誤，但不據此推論更廣泛的 multiplayer 相容性。

## 已知限制

- Gradle 8.8 在本專案建置時會顯示 Gradle 9 deprecation warning；此警告目前不阻塞 M0 build，但應在升級 Gradle 前處理。
- GitHub Actions workflow 使用 `actions/checkout@v7`、`actions/setup-java@v6`、`gradle/actions/setup-gradle@v6` 與 `actions/upload-artifact@v4`；`setup-gradle` 保留其預設 Gradle Wrapper validation。
