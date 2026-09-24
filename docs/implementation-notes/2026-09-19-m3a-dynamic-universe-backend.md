# M3-A：持久動態 Universe backend 證據

> 後續狀態（2026-09-24）：M3-A whole-branch review（`d0bd235..128ed84`）Critical 0／Important 0；M3-B 其後也已完成，M3 已推送至 `origin/feature/m1-chamber`（`687cff9`）。M4 候選門也已在同一分支完成。合併狀態以 `main`／tag 為準。以下為當時紀錄。

驗證日期：2026-09-20。Minecraft 1.21／Yarn 1.21+build.9／Loader 0.17.2／Fabric API 0.102.0+1.21／Loom 1.7.4／Java 21／Gradle 8.8，Windows 本機。

## 狀態與範圍

正式 catalog、單一 alternate Overworld backend、production bootstrap、第二 JVM 原生讀回、同 key 卸載／替換及自動回歸已通過。Task 9 的獨立 spec／quality review 與 whole-branch final review 由後續 gate 核定；M3-B 玩家往返尚未實作，整體 M3 不可宣告完成。

M3-A 執行基準為 `89604bb3d2dff79f1f35e6fb06ed8b72192d67de`；本次 final 驗證基於 `7f72130a1fd55ed0b83c830fe6d23ed4d31fc20b`，只整合 testmod probe 與本文件，沒有變更 production code。驗證用 probe SHA-256：`49239229C59843AC1CDDFC1C592F3310462CE3E0A7E496BF54EDAEC4577A3E9C`。交接應使用包含本 note 與該 probe 的 exact Git commit，並核對下列 frozen receipts；不能只以此段文字取代原始證據。

## Production lifecycle 的實際驗證

testmod 在 `SERVER_STARTED` 的 custom phase（早於 DEFAULT）綁定 exact server，先證 foreign key 在 live map 與 vanilla DIMENSION registry 均不存在，再按固定 key 捕捉正式 lifecycle 的 LOAD。後續 callback 明確排在 production phase 之後。

`create-save` 從空 catalog 啟動，由 probe 配置／checked-flush 第一筆 definition，並擁有自己的 backend。`reload-read`、`unload-replace`、`final-verify` 的初始 world 都由 production `UniverseLifecycleService` 重建；probe 只讀反射取得同一 server context 的 backend，驗 exact context／backend／native map／LOAD world／ACTIVE identity，沒有另外物化第二個初始 owner，也沒有停用 production lifecycle。

replacement 的新 world 透過上述同一 production backend 建立。每次 UNLOAD 都核對 context 對 exact instance 的計數；STOPPED 必須有正式 `M3_UNIVERSE_STOPPED_VERIFIED`、context detach，以及 queue／observer／protection detach。Reflection 僅存在 testmod，沒有寫入 production 私有狀態。

## Fresh 四階段 final gate

Nonce：`task9-804f33b86e7f4457bbff3d08ae5b18c5`。Root：`run/m3-universe-task9-804f33b86e7f4457bbff3d08ae5b18c5`。四階段依序各執行一次，沒有失敗重跑。

| Phase | PID | LOAD／UNLOAD | 初始 backend owner | 結果 |
| --- | --- | --- | --- | --- |
| create-save | 3792 | 1／1 | probe；production 空 catalog | PASS |
| reload-read | 32464 | 1／1 | production lifecycle | PASS |
| unload-replace | 14708 | 2／2 | production lifecycle；replacement 同 backend | PASS |
| final-verify | 25584 | 1／1 | production lifecycle | PASS |

四組 PID／StartTime／startupNonce 皆不同；每階段皆 Done、probe 自行正常 stop、exit 0、lock 可 exclusive-open。source／classes 前後及跨 phase manifest 一致。world key 固定為 `quantumchamber:universe/60000000-0000-4000-8000-000000000006/overworld`，storage 位於 owned save 的對應 `dimensions/quantumchamber/universe/.../overworld`。

正式 catalog 的 SHA-256 全程為 `0D2E8A68A437C7DA1B518E7C80130EBDADEC0A1690CCD60DAE8BC2E887D5F60C`。不同 JVM 原生讀回 diamond sentinel `(8,100,8)`、Controller `(9,100,8)` 及原生 BE NBT；讀回階段沒有 fixture write。真 `BLOCK_ENTITY_LOAD` 先驗 exact Controller 已入列，下一 END tick 的實際 receipt 為唯一 `CONSUMED`，foreign Controller 的 Chamber origin 維持 0→0。

replacement 驗 C 與 D 為不同 Java instance、相同 key／storage、原生 NBT 相同；C 的 queue receipt 是 `CONSUMED,DISCARDED_WORLD_UNLOAD`，再次 `discardWorld(C)` 不會刪除 D 尚待處理的真 BE entry。C／D／final world 的 early unload 完整返回 `UNLOADED`，runtime／quarantine 清空，vanilla 沒有重送 UNLOAD。

原始證據位於 `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/task-9-final-task9-804f33b86e7f4457bbff3d08ae5b18c5/`：逐 phase 的 stdout／stderr、events、probe／harness final JSON、source／classes、native save／region hashes。`four-phase-final.json` SHA-256：`09A5A994FFFC57E85385D5EB328C59EA04109807FAF7B9B9BE391EA948ED1E7A`。這些為本機 ignored evidence，不進 release。

## 完整回歸與 main-only smoke

四階段 PASS 後，以全新 GameTest world 執行 `clean test runGameTest build --rerun-tasks --offline --console=plain`：2 分 20 秒，17 tasks 全部 executed。JUnit 為 286 declared／285 passed／0 failures／0 errors／1 skipped；唯一 skip 是 `NonWindowsPlayerCheckpointStoreTest.unsupportedPlatformRejectsBeforeNativeInitializationOrFileAccess()` 的 `@DisabledOnOs(OS.WINDOWS)`。GameTest 118／118、0 skipped，兩個 lifecycle HIT 各一次，STOPPED sentinel 證 queue／protection／context 全 detach。

執行前檢查 exact world 路徑、reparse、live server 與 lock，移至唯一 sibling backup；結束後另存 fresh world 並還原原 world，before／after manifest 完全相同。證據目錄：`task-9-regression-20260920T021328-4d48d03792714a4488922e6fedf32f6a/`。

接著使用最新 classes 與新匯出的 main-only runtime，各跑一次獨立 fresh root：

| Main-only phase | PID | Native worlds | Dynamic LOAD／UNLOAD | 結果 |
| --- | --- | --- | --- | --- |
| A：空 catalog | 864 | 原 4 worlds | 0／0 | PASS |
| B：複製本次合法 catalog／native save | 25752 | 自動重建第 5 world | 1／1 | PASS |

兩者均 Done＋BOOTSTRAP_READY 後 stdin stop、exit 0、STOPPED_VERIFIED、source／classes unchanged、locks released。Fabric loaded-mod list、runtime classpath、JVM class-load 三層均未載入 testmod；43 mods，testmod classes loaded＝0。B 有精確 `Missing data pack quantumchamber-testmod` 舊存檔提示，另列為 stale saved datapack warning，沒有當成 runtime testmod 載入。來源 world 逐檔保留，`run/server` 未使用或搬動，Task 8 receipts 未改寫或重用。證據目錄：`task-9-main-smoke-task9-804f33b86e7f4457bbff3d08ae5b18c5/`。

## Artifact 邊界

release JAR 的 185 classes 恰等於 main 的 184 加 split source 設計下的 1 個 client entrypoint；sources JAR 恰為 110 個正式 Java sources。46 個 testmod classes、test／JUnit／GameTest 資源均未混入。Client entrypoint 保留於正式 client 環境是既有設計，沒有宣稱 release JAR 完全不含 client code；184 個 common classes 的 bytecode 對 Minecraft client／本專案 client 類別引用為 0，main runtime 的 108 項 classpath 無 testmod／client output。

- `quantumchamber-0.1.0-SNAPSHOT.jar` SHA-256：`FFE5850FC449DC90971FB385BE333F722E9BFF816EEA03C50DA2A19ED04890B0`。
- `quantumchamber-0.1.0-SNAPSHOT-sources.jar` SHA-256：`9E0214C983BFCE83B64F6E7B5BEB1E8583E7C9404AFCA496DBCAAD870FB9EB60`。

原始清單與 metadata 在本 owner 的 `task-9-artifact-audit.json`。既有 6 個 `SessionTransferFaultMixin` 編譯 warnings 與 guard tests 的預期 rollback logging 仍存在，沒有宣稱 warning-free。

## 保留界線

- Generator profile 仍是 `VANILLA_OVERWORLD_SHARED_SEED_V1`，獨立 storage 不等於獨立 seed；獨立 time／weather 未驗證。
- 尚無 M3-B 原生玩家同座標往返／recovery，沒有真 client registry sync、packet／respawn sequence 或 player transfer 的成功聲明。
- 沒有 candidate doors、collapse、Nether／End family、lazy pool、正式 passage 或新 renderer。
- 本次 native gate 沒有注入 LOAD observer corruption、crash 或 power-loss；錯誤／quarantine 分支的純 JVM coverage 不冒稱完整 native fault-injection proof。Ubuntu／Windows CI 的外部結果不由本機測試替代。
- M2 人工八項、Task 9 獨立 review 與 whole-branch final review 仍待；沒有 push、merge、deployment 或 LICENSE 變更。
