# M3-B：server-side transfer readiness 證據

> 後續狀態（2026-09-24）：M3-B whole-branch review（`81cec28..687cff9`）Critical 0／Important 0／Minor 0；M3 已推送至 `origin/feature/m1-chamber`（`687cff9`）。M4 候選門也已在同一分支完成；玩家可用的跨宇宙通道仍屬 M5。合併狀態以 `main`／tag 為準。以下為當時紀錄。

驗證日期：2026-09-20。Minecraft 1.21／Java 21／Gradle 8.8，Windows 本機。Task 3 基準為 `5df30bc7fec331e87097feb49d5cdfcc19136d6b`。

## 範圍與結論

本次已取得 production dynamic Universe 的 server-side 玩家同座標往返、缺 FULL chunk 拒絕、原生移動後失權的真回滾，以及跨 service receipt 拒絕證據。完整 JUnit／118 GameTests／build 與另一 fresh root 的 M3-A Gate A/B regression 通過。獨立 spec／quality review 與整體 M3 核定由後續 gate 處理；本文件不宣告真 client transfer 可用。

只新增 testmod probe／test access、testmod entrypoint、選用 `m3Transfer` run 與本 note。沒有修改 production transfer、backend、lifecycle、plan 或 spec，也沒有新增 command、門或自訂 client packet。

Probe 預設關閉；只有合法 `phase`、`nonce`、`startupNonce`、canonical owned root／cwd 與 fresh evidence 目錄全部成立才註冊 callbacks。正式 transfer phases 只讀取得 production `UniverseLifecycleService` 的同一個 context／backend／ACTIVE world；沒有第二個 backend owner，也沒有停用 production lifecycle。

## Fixture 與裁定

玩家由既有 `ConnectedGameTestPlayer` 經原生 `PlayerManager.onPlayerConnect` 建立，使用 embedded connection，並非 mock。fixture 明確載入必要 chunks、增加 tickets、寫入來源／目的高空地板；結束還原地板、對稱移除 tickets，並從 PlayerManager／兩個 world entity map 移除玩家。每段透過原生 `saveAllPlayerData` 落盤並讀回正式 player NBT，不用自行組裝的 NBT 冒充 native save。

R7：新 service B 沒有 A receipt 的 destination token，最早拒絕點為 `REJECTED_BEFORE_MOVE / REJECT_RECEIPT_IDENTITY`。因此 phase 名為 `stale-service-receipt`；拒絕時玩家仍在 destination，之後由原 service A 真 `RETURNED`。這不宣稱 `REJECT_SOURCE_IDENTITY` 或 `FAILED_RECOVERY_REQUIRED` 的 native 覆蓋。

R8：第一次正式 chain 的 setup 通過，接著在 fixture 的同世界初始化失敗，尚未呼叫 Universe transfer。舊正式 NBT 與 pinned 原生 bytecode 證實：同世界 teleport 套用 XYZ／rotation，但保留原 velocity。經明確授權，只在 fixture 初始化前先設定既定非零 velocity，再呼叫原生定位；原完整 velocity oracle、production code、NativeMove 與 phase expectations 均未改。focused compiled-fixture／bytecode check 為 RED→GREEN；真正 transfer 能力仍以下列 fresh runtime 為證。舊失敗 root 保留，沒有修補或重用。

## Fresh transfer chain

Nonce：`task3-r8-42634c7b147e4b8bae3b091c9fb44361`。Root：`run/m3-transfer-task3-r8-42634c7b147e4b8bae3b091c9fb44361`。setup 與四個 transfer phases 各執行一次。

| Phase | PID | Dynamic LOAD／UNLOAD | 驗證結果 |
| --- | --- | --- | --- |
| setup-catalog | 27960 | 0／0 | 空 catalog 啟動，只配置／checked-flush definition，沒有物化 world |
| success-roundtrip | 15908 | 1／1 | 真 `MOVED` → `RETURNED`，兩次原生 move |
| target-not-full | 2028 | 1／1 | `REJECTED_BEFORE_MOVE / REJECT_TARGET_NOT_FULL`，原生 move 0 次 |
| post-move-authority-loss | 13724 | 1／1 | 真原生成功後才遮蔽 ACTIVE view，真回滾為 `FAILED_ROLLED_BACK` |
| stale-service-receipt | 34616 | 1／1 | B 拒絕 receipt 且不移動；A 真 `RETURNED`，原生 move 共 2 次 |

四個 transfer JVM 都由 production lifecycle 從正式 catalog 物化 `quantumchamber:universe/63000000-0000-4000-8000-000000000063/overworld`，並核對 exact context／backend／native map／LOAD world／ACTIVE identity。

每個 phase 的來源與返回維持 velocity `[0.125, 0.03125, -0.0625]`、yaw `37.5`、pitch `-12.5`；往返全程核對同一 player object、UUID、PlayerManager 與 world entity 身分、XYZ／bbox 與完整 pose。成功與 stale phases 的正式 destination player NBT Dimension 為上述 dynamic key，返回後為 `minecraft:overworld`。

缺 FULL phase 的位置在 `x=4094.5, y=318, z=8.5`；bbox 外兩格涵蓋的目的 chunk `(256,0)`，`getChunk(FULL,false)` 在拒絕前後均為 null。沒有原生 move，也沒有讓該 chunk 變為 FULL。

失權 decorator 只作用於 transfer 的 backend view：先取得真 ACTIVE owner，真 `SessionTransferService.move` 成功且 exact destination observation 成立後才失權。production backend 仍維持 ACTIVE；service 自行真移回來源，不直接改 pose 或偽造成功。

五組 PID／StartTime／startupNonce 皆不同；每個前置 JVM 完整 normal stop、exit 0、lock release 後才啟動下一個。四個 transfer phases 都有正式 `M3_UNIVERSE_STOPPED_VERIFIED`、context detach 與 player／floor／ticket 清理。source／classes 前後與跨 phase manifest 相同；catalog SHA-256 全程為 `DB2D24FB387D16814C1BB21FD3637C8A6713431EBE2B2F042AEE0CAC7375B7AE`。

Evidence owner：`.superpowers/sdd/2026-09-19-m3b-server-transfer-readiness/`。Transfer 原始 stdout／stderr、events、正式 player NBT 複本、save／region manifests 在 `transfer-final-task3-r8-42634c7b147e4b8bae3b091c9fb44361/`；aggregate receipt 在 `Transfer-task3-r8-42634c7b147e4b8bae3b091c9fb44361/chain-final.json`，SHA-256 `E68E44B2B783A1870BE6A67CEEF8F8B1DD9E09B0B1D02067CE38AABB5B9EA39A`。

驗證用 probe source SHA-256：`257D38328742FEF26285D13636C8F2C616E4A082EA0EEFAC002C4F969ABCFB3B`；test access source：`2DFAAFAF62549E20B9208C6F4F61142D23E3997A45D1A1CF65D3C094AE13C5EA`。

## Full regression 與 M3-A regression

Transfer chain 通過後執行 fresh `clean test runGameTest build --rerun-tasks --offline --console=plain`：2 分 32 秒，17 tasks 全部 executed。JUnit 298 declared／297 passed／0 failures／0 errors／1 skipped；唯一 skip 是既有 `NonWindowsPlayerCheckpointStoreTest.unsupportedPlatformRejectsBeforeNativeInitializationOrFileAccess()`。GameTests 118／118，M2／M3 shared server 維持四個原生 world，STOPPED sentinel 證明 queue／protection／context detach，新的 transfer probe 保持 default-off。

執行前檢查 exact GameTest world 路徑與 lock，保留原 world；完成後保存 fresh world 並還原原 world，before／after 逐檔 hash 完全相同。Evidence：`r8-full-b02b74bfbf5248ccbe263e7cbbca0f51/`；其 `final.json` SHA-256 為 `BA6AB6C3177B78EC1CAB7FC2F94D3C04E54D28180DB3ED19C6F0319C0122F4CA`。

再以新 nonce `l8-8b4d`、本 owner 內 `Lifecycle-l8-8b4d/fixture-project/run/m3-universe-l8-8b4d` 的 fresh root 重跑未修改的 `M3UniverseRuntimeProbe`。原 probe 固定的 evidence owner 名稱只投影在這個隔離 fixture project 中，沒有讀寫實際 sibling plan workspace。

| M3-A phase | PID | LOAD／UNLOAD | 結果 |
| --- | --- | --- | --- |
| create-save | 1152 | 1／1 | PASS；沿用原 probe-create owner，production 空 context |
| reload-read | 23668 | 1／1 | PASS；production lifecycle owner |
| unload-replace | 24480 | 2／2 | PASS；same-key 新 instance、same storage、native NBT exact |
| final-verify | 944 | 1／1 | PASS；production lifecycle owner |

四階段的 native Controller NBT、唯一 `CONSUMED` queue receipt、LOAD／UNLOAD／STOPPED、context detach、source／classes manifests 與 locks 均通過。Catalog hash 固定為 `0D2E8A68A437C7DA1B518E7C80130EBDADEC0A1690CCD60DAE8BC2E887D5F60C`。Aggregate：`Lifecycle-l8-8b4d/chain-final.json`，SHA-256 `B27F34C9351C54F229A2439BA82C46057E61D97C4202B9F924FEA63418DAC677`。

## Artifact／classpath 邊界與限制

Release JAR 的 202 classes 恰等於 main 201 加既有 client entrypoint 1；sources JAR 恰為 115 個 main／client Java sources。55 個 testmod classes、test access／probe、GameTest／JUnit 資源均未混入 artifact。201 個 common classes 的 bytecode 不引用 Minecraft client／本專案 client／testmod；最新 main runtime 的 108 項 classpath 沒有 testmod、test、client output 或 JUnit。此項為本次 artifact／classpath 靜態稽核；沒有另外執行新的 main-only dedicated smoke。

- Release JAR SHA-256：`92EDADE0406630D843B8019E9B5D40A40853BEF1D7C71ECC466EE2DBBA4AEAEF`。
- Sources JAR SHA-256：`685452FE743EEFC3025206FCE818EECC834D6C020CF5362894C77EBF72728455`。
- Audit：`r8-artifact-audit.json`，SHA-256 `6EBB96F444BDF4BA6E78EB251088B263648483227AAE16BDD2F216E545A4B76D`。

既有六個 test mixin compile warnings 與 fault tests 的預期 rollback／I/O error logging 仍存在；不宣稱日誌 warning-free。所有 raw evidence 均為本機 ignored artifacts，不進 release。

真 client registry／respawn 同步、candidate doors、collapse、Projection、Nether／End family、正式 passage 與 renderer 均未完成。Embedded connection 的 server-side 成功不替代真 client 驗證；`FAILED_RECOVERY_REQUIRED` 的不可回滾 native fault 也沒有在本次注入。沒有 push／merge／deployment。
