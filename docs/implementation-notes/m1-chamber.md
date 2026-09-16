# M1 Chamber Foundation 實作與驗證紀錄

驗證日期：2026-09-16（Asia/Taipei）。M1 foundation 程式與自動驗證已交付；完整人工 gameplay 與下列未覆蓋情境仍待驗收，因此不宣告整個 M1 completion gate 已完成。

## 實作邊界

- `ChamberGeometry`、`ChamberDetector`、`ChamberLocator` 與 `WorldChamberBlockView`：外部 7×7×7、內部 5×5×5；controller local `(3,6,0)`，朝向指向外側；192 格 bedrock、25 格 Bulkhead 與一格 controller。
- `QuantumBulkheadBlock.onUse` → `ChamberDoorService`：一次切換整面 25 格；寫入失敗時反向 rollback，rollback 中段再失敗仍嘗試剩餘格，最後明確拋錯。成功後同步 refresh controller。
- `ChamberRegistry`、`ProjectionIndex`、`ChamberRegistryState`：只登錄 ORIGIN，以 role/chunk 索引拒絕同 role 重疊；不同 role 可使用同 XYZ。
- `ChamberProtectionService`：`SERVER_STARTED` attach、`SERVER_STOPPED` detach；一般寫入拒絕已註冊 volume，authorization 只包住合法 door transaction。唯一 Mixin 為 `WorldSetBlockStateMixin`，target 是 `World#setBlockState(BlockPos, BlockState, int, int)`，descriptor `setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z`。
- `ChamberOccupantService` 使用真實 `ServerWorld.getPlayers`，要求完整 bounding box 位於 interior 並排除 spectator。`ChamberActivationService` fail closed 處理 registry error／重疊；任一參與者缺少 buff 或零參與者均不得 arm。
- `RisingEdgeLatch`、`ChamberRedstoneService`、`ChamberControllerBlock`：僅 rising edge 可 READY→ARMED；持續高電位不 retrigger；20 ticks 排程 refresh；comparator 固定 `0/3/7/11`。
- `ModEffects`、`ModPotions`：`quantumchamber:quantum_state` 為 beneficial effect／potion，3600 ticks、amplifier 0；Awkward Potion + Echo Shard，經 Fabric brewing callback 註冊。

Registry 與 ProjectionIndex 是 server-thread confined 的可變資料結構，不提供 thread-safe API；目前寫入來自 server lifecycle、block event 與 server service。GameTest 的 attached protection probe 亦檢查 `server.isOnThread()`。未為尚未使用的並行路徑加入鎖或重構。

沒有 UniverseRegistry、runtime dimension allocation、corridor、candidate/collapse、Superposition session、teleport、packet 或新增 renderer。`DimensionRole` 只對應三個 vanilla world keys；`PROJECTION` enum 保留型別但 M1 不物化 Projection。

## 持久化 contract

Canonical state 位於 Overworld 的 `data/quantumchamber_chambers.dat`，`SchemaVersion=1`。

- Registry record：`ChamberUuid`、`OriginWorldKey`、`OriginDimensionRole`、`AnchorPos`、`Facing`、`InstanceKind`、`Enabled`、`Destroyed`。
- Controller：`ChamberUuid`（null 時省略）、`InstanceKind`、`ChamberState`、`WasPowered`、`PowerInitialized`。
- 不支援 schema／錯誤 record 型別／非法欄位會保留 load error 並拒絕 overwrite；載入後重建 index。

## Task 9 找到並修正的 runtime 問題

首次 dedicated restart 在 `12:40:34` 被 watchdog 中止。呼叫鏈為 `ChunkSerializer → WorldChunk.setBlockEntity → BLOCK_ENTITY_LOAD → onControllerLoaded → world.getBlockEntity → getChunk`；回呼在 chunk 尚未完成 FULL 載入時查回同一 chunk，造成重入等待。

Round 1 審查後，已移除可能 same-tick 執行且捕捉 stale world／position 的 `ServerTask` 方案。`BLOCK_ENTITY_LOAD` 現在只對事件提供的 controller 設定 runtime-only `loadSyncPending`，並呼叫 `world.scheduleBlockTick(pos, ModBlocks.CHAMBER_CONTROLLER, 1)`，不查詢 world／chunk。`ChamberControllerBlock.scheduledTick` 確認目前 block、BE 類型、未 removed 且 BE world 一致後，消耗 pending 標記並同步目前電平 without edge；後續走一般 refresh 與 20-tick 排程。方塊移除／替換時由 vanilla 丟棄舊 block tick，不保留會重新查回 chunk 的獨立工作。

`loadSyncPending` 不寫入 NBT；每次 load event 都重新標記，因此即使既有 `PowerInitialized=true`，仍會在本次載入同步目前電平。Pending 期間的 neighbor update 不處理 edge，避免首次 block tick 前將落後的 persisted low 誤判成 rising edge。既有 NBT keys／持久化 contract 保留。Production 變更僅限 controller 載入排程與此 runtime 標記。

本機原始失敗證據：`run/server/crash-reports/crash-2026-09-16_12.40.34-server.txt`。初次成功記錄保留於 `run/server/m1-restart-initial.log`；Round 1 最終成功記錄為 `run/server/m1-restart.log`。

## 測試與執行環境

Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Loom 1.7.4、Gradle Wrapper 8.8、Eclipse Adoptium Java 21.0.12.1、Windows 11。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
$env:GRADLE_USER_HOME = 'C:/Users/Ben/.gradle'
.\gradlew.bat compileTestmodJava processTestmodResources --offline --console=plain
.\gradlew.bat clean build runGameTest --offline --console=plain
git diff --check
git status --short --branch --untracked-files=all
```

`--offline` 使用此機器已有的依賴快取；一般新環境需先完成依賴下載。沙箱中的初次 wrapper 執行曾遭 `Permission denied: getsockopt`，後續驗證使用明確 Java／Gradle 路徑與核准執行環境。

| 驗證 | 結果與實際範圍 |
| --- | --- |
| JUnit | 69 tests，0 failures；新增三 vanilla＋unknown key mapping、ProjectionIndex 跨 chunk remove／role 隔離、runtime load 標記不持久化且只消耗一次，並加強中段 rollback failure 後繼續寫入 |
| Fabric GameTests | 20 tests；報告 `build/gametest-results.xml`；使用 real ServerWorld、ServerPlayerEntity 與原生方塊事件，未使用 mock framework |
| Mixin/protection | registered interior／controller 的 setBlock 被拒絕；外部 setBlock 成功；爆炸以 protected glass／外部 glass 對照；流體以 protected air／外部流動對照 |
| 活塞移動 contract | Bulkhead／bedrock shell 不移動，外部 stone 可被推動；兩種 Chamber 方塊本身不可推動，因此此 case 不獨立證明 Mixin 攔截 |
| 互動與接線 | `onUse` 切換 25 格；ARMED 開門立即 IDLE，再關門 READY；真正 comparator 方塊輸出 3→7；scheduled tick、BLOCK_ENTITY_LOAD、null UUID omission |
| 玩家 | 有 buff READY；兩位不同 UUID 玩家其中一位無 buff 拒絕；真正 spectator 排除 |
| 電平 | rising edge ARMED；same-high 不 retrigger；故意載入 stale low latch 到 held-high 世界，LOAD 同步成 high 且 READY 不變 |
| 載入回歸 | load 排程後移除／換成 chest：舊 BE 不同步、替代 BE 身分不變、沒有殘留 controller refresh tick；真 controller 下一 tick sync，之後不重複 sync；持久化 initialized=true 仍可同步已變化的世界電平 |
| 配方 | runtime BrewingRecipeRegistry 確認配方存在與實際 craft 產物；未取代人工 brewing stand GUI 驗收 |
| lifecycle | testmod 在真正 SERVER_STOPPED 後記錄 `M1 lifecycle detach verified after SERVER_STOPPED`；observer 在 SERVER_STARTED 才註冊，避開 mod initializer 順序差異 |

GameTest 的原生 `createMockCreativeServerPlayerInWorld()` 會把 `isSpectator()` 固定為 false；測試改用未覆寫的 ServerPlayerEntity，透過 embedded connection 接入 PlayerManager，結束後移除玩家。

Fixture canonical 原稿保留在 `data/quantumchamber/structure/m1_empty.snbt`。Fabric API 0.102.0 的實際 SNBT provider 讀取 `data/quantumchamber/gametest/structure/m1_empty.snbt`，且採 `data`／字串 `palette` 格式，因此另提供相同 16×12×16 空間的 provider 版本。這是鎖定版本的 fixture 適配，未變更測試預期或遊戲行為。

GameTests **不是現有 CI coverage**：CI 的 `clean build` 只執行既有 build/JUnit gate，需另外執行 `runGameTest`。GameTest XML 也不包含 server 停止後的 lifecycle probe，驗收時須一併檢查上述 log marker 與沒有 `Exception stopping the server`。

## Dedicated server 與重啟證據

使用 ignored `run/server`，loopback `127.0.0.1:25576`、隔離世界 `m1-smoke`；使用者已接受 EULA。本機 smoke 採 offline-mode，僅綁定 loopback。

執行 `./gradlew.bat runServer --offline --console=plain --args=nogui`，到達 Done 後，在新測試世界送入下列 console commands：

```text
forceload add 100 100 106 106
fill 100 100 100 106 106 106 minecraft:bedrock hollow
fill 101 101 100 105 105 100 quantumchamber:quantum_bulkhead[open=false]
setblock 103 106 100 quantumchamber:chamber_controller[facing=north]
setblock 103 107 100 minecraft:redstone_block
```

等待至少 20 ticks 後執行 `data get block 103 106 100`、`save-all flush`、`stop`。重啟同一世界後再查詢 NBT、儲存與 stop。

- 初次：`12:37:59 Done`、`12:38:04` 查詢 NBT、`12:38:08 stop`、`12:38:09 All dimensions are saved`。
- 修正後重啟：`12:43:06 Done`、`12:43:11` 同一 NBT、`12:43:15 stop`，三 vanilla dimensions 正常儲存。
- Round 1 最終版本重啟同一 world：`13:29:33 Done`、`13:29:38` 同一 controller NBT、`13:29:42 stop`／所有 dimensions 儲存；沒有 watchdog，registry 解碼與 SHA-256 仍一致。
- Controller：`ChamberUuid=[-616379319,1488932183,-1377398559,266153682]`，`InstanceKind=ORIGIN`、`ChamberState=IDLE`、`WasPowered=1`、`PowerInitialized=1`，重啟前後一致。
- 直接解碼兩份壓縮 NBT，比較完整 registry record 一致：world `minecraft:overworld`、role `OVERWORLD`、anchor `[103,106,100]`、facing `NORTH`、enabled `1`、destroyed `0`。
- 兩份 registry 檔案 SHA-256 均為 `80ED28EDD4414A67945B0763130A4006154FBD9DFB01BF4683497676E6744CC9`。
- 此 dedicated run 未載入 testmod 或 client initializer，沒有 client class loading error。

Dedicated restart 沒有在線參與者，因此只證明持久化與 held-high 電平恢復；「有合格參與者的 READY 在 held-high reload 不變成 ARMED」由 GameTest 證明，尚未完成人工多人跨程序重啟驗收。

## Client runtime 與人工驗收

使用已停止的 dedicated 世界複本 `run/client-base/saves/M1Smoke`：

```powershell
.\gradlew.bat runClient --offline --console=plain --args='--quickPlaySingleplayer M1Smoke'
```

`12:44:21 QuantumChamber client initialized`，block／mob_effect atlas 成功載入；`12:44:46` integrated server 啟動、`12:44:47 Player518` 登入；`12:46:10` 玩家離線後 server 正常保存，`12:46:39 Stopping!`，Gradle exit 0。證據為 `run/client-base/logs/latest.log`。Vanilla 日誌包含 goat-horn missing sound 與 Sampler2 warning；沒有觀察到 QuantumChamber asset loading error。

這份 client runtime 記錄來自 `9aded34` 的驗證版本；Round 1 的載入排程修正另以 GameTests 與同世界 dedicated restart 回歸驗證，未補做人工 HUD／GUI 驗收。

目前沒有可用的 native GUI 自動操作／截圖能力；上述證據不代表已目視驗證貼圖、HUD 或完成手動玩法。以下保留待人工執行：

- [ ] Creative 世界搭建完整 Chamber，右鍵整面門、走入 interior，再關門。
- [ ] Brewing stand 以 Awkward Potion＋Echo Shard 釀造 QuantumState，飲用後確認 HUD、效果時間與 comparator 7。
- [ ] Lever 由 low→high 得到 11／ARMED；held-high 不重觸發；開門 3、關門 7，需新 edge 才 arm。
- [ ] Invalid shell=0、有效但條件不足=3；兩位玩家只有一位有 buff 時不得 ARMED。
- [ ] 保持 lever high 儲存／重開，含合格參與者時不出現假 edge。
- [ ] 全程沒有傳送、走廊或新 Dimension／Universe。

## 已知限制與後續邊界

- unknown-world allow 與 foreign-server allow 在本次未建立真實非 vanilla ServerWorld／第二個同 JVM server 來驗證；只確認三 vanilla＋unknown key mapping、現有分支與 attached／實際 stopped-detached 路徑。未為測試新增 runtime dimension。
- 未執行 renderer 視覺驗收、跨模組 compatibility matrix 或線上多人 GUI 驗收。
- 使用者提供 AE2 Quantum Network Bridge 僅作視覺語言參考：對稱 multiblock、中央核心與 powered 藍光可供 M2 原創 cyan-violet 設計；不複製其模型、材質或配方。M1 未新增 renderer。
- 下一步仍需獨立 M2 spec／plan；本次沒有 M2 skeleton。完整人工驗收通過前，M1 completion gate 維持未關閉。
