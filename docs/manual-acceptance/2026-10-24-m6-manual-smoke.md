# M6 Overworld 人工 smoke 清單

狀態：2026-11-10 Task R 開工前修正裁示之待驗清單，本次唯一窄審 C0／I0／M0 已採用；2026-11-12 依 8-H 裁示新增兩列與 waiver 段；同日 Task 22 fix1 後改寫 H4 waiver，並揭露 H1／H2 範圍的 mixin 與 journal 讀回失敗的限制。2026-11-12 由 Claude 代操作（使用者授權）完成全部列，結果見下表與「本輪實際操作」節。

範圍：平行宇宙只有主世界，FamilyCapabilityVersion=0；共用原版 Nether／End。SHALLOW_V1 僅 id 層設定 fingerprint，same-seed／toggle 保證以兩端 datapack／mod 配置相同為前提。8-H 已由使用者 2026-11-12 裁定：必要項是下表的「release JAR」與「/reload」兩列，其餘 waiver 列在文末；全部舊 FAIL 不改判。

每輪記版本／code SHA、世界、操作者、時間、玩家／Universe UUID、world key、seed／profile、receipt／log 路徑與實際結果。只使用另獲授權的新測試世界，不動使用者世界。

| 輪次 | 操作 | 預期 | 實際結果／證據 |
| --- | --- | --- | --- |
| 單人 | 從艙選 NEW，依 receipt 辨認 random profile 與 seed | 進入不同 seed 的 U 主世界，UUID／key 精確 | PASS。單人世界 `QC-M6-SP-20261112`（seed 20261112）三輪 NEW：U1 `b068f3f8-ed2f-82b7-a42f-6c2e217cf2ea`（`same_seed_fresh_v1`）、U2 `38ef41b6-323f-851a-ac82-8c011cdbe096`（`same_seed_toggle_amplified_v1`，amplified）、U3 `4e89a5a7-6caa-8b94-a752-339880fa594e`（`random_vanilla_v1`，RootWorldSeed 6701660318953015129）。world key 都是 `quantumchamber:universe/<UUID>/overworld`。停服後 catalog 讀回（`readback/catalog.json`）與遊戲內 `Dimension`、`/execute in <U> run seed` 一致。三輪載入後都看到「你已進入新的宇宙。」（截圖 `135602`、`141107`、`141447`）。比較器只在第一輪跨越前讀過，值是 15；第二、三輪以 log 的 PASSAGE_READY（14:10:50、14:14:30）為準。時間 13:53–14:15，log `logs/client-base/2026-11-12-1.log.gz` |
| 單人 | 重進同一 Universe，正常存退並重啟登入 | 不重抽，仍同 UUID／key／seed 的 U | PASS。存退回標題再進：仍 U1、同位置 (66.5,63,0.5)。完全結束 Java 後重啟：開服物化三個 U（`BOOTSTRAP_READY worlds=3`），登入仍 U1 同位置；之後 release server 開服讀到 allocation 仍 3 筆，沒有重抽或新配置。時間 13:57–14:16，log `logs/client-base/2026-11-12-1.log.gz`、`logs/client-base/2026-11-12-2.log.gz` |
| 多人 | 兩玩家走正式 NEW／跟隨與重進流程，存退重啟 | 各自保存正確位置與同一 U 權威，不串 U | PASS。dedicated（`runServer`，127.0.0.1:25576，seed 20261113）QC_A、QC_B 同組入場；QC_A 選門 → U `93aef651-b6a0-8f40-923a-5bb27d7266b5`（停服後 catalog 讀回 `readback/catalog-mp.json`，來源是 server 14:41:43 存檔完成後的 `run/server/QC-M6-MP/data/quantumchamber_universes.dat`，複本 `readback/mp-quantumchamber_universes.dat` SHA256 `3bf1be70f9a6a0f23dc99b7011f4538227e0bcf2073cf2c07eae0836e3b9e524`：`random_vanilla_v1`，seed -766774716797456734，world key `quantumchamber:universe/93aef651-b6a0-8f40-923a-5bb27d7266b5/overworld`），QC_A 先跨、QC_B 跟隨，2/2 正常終局，兩人 Dimension 相同。QC_A 中斷重連回同位置；`/stop` 後重啟 server（開服物化該 U），兩人重連各回原位置 (45.497,66,-2.904)／(44.5,66,-4.169)。時間 14:32–14:38，log `logs/server/2026-11-12-1.log.gz`、`logs/server/latest.log`、`logs/client-base/2026-11-12-4.log.gz` |
| 共用維度 | 從 U 經地獄門到原版 Nether 再回程；另驗 End 出口／credits | 地獄門回程一律落在原版主世界；終界出口照原版看床，床在 U 回 U、沒床回原版主世界；與死亡分開記 | PASS。在 U1 用方塊放地獄門 → `the_nether` (10.5,51,0)；回程 → `minecraft:overworld`。End：沒有有效重生點時走出口 → credits（seenCredits 0→1）、原版提示、`minecraft:overworld`；床在 U1 時走出口 → U1 的床，沒有再播 credits。時間 14:03–14:08，log `logs/client-base/2026-11-12-1.log.gz` |
| 宇宙內重生加一輪 | U 內設床後死亡；正常存退重啟後再驗 | 既有 EAGER_ENABLED bootstrap 後同 U 床有效、登入仍同 UUID／key；不新增登入物化 | PASS。U1 設床後 `/kill` → U1 床 (69.5,63,-3.5)。完全重啟後登入仍 U1、SpawnDimension＝U1，`/kill` → 同一張床。時間 13:57、14:19，log `logs/client-base/2026-11-12-1.log.gz`、`logs/client-base/2026-11-12-2.log.gz` |
| 宇宙內重生加一輪 | 有效床／錨位於另一 U 或 canonical；在 U 死亡 | 床／錨依原版優先，不另判合法性、不改錨消耗 | PASS（canonical 與 Nether 錨）。床在原版主世界 → 死在 U1 回到那張床；錨在 Nether（4 格）→ 死在 U1 回到錨，錨剩 3 格。都沒有 FALLBACK log。床在另一個 U 的情況沒有另外驗。時間 14:01–14:03，log `logs/client-base/2026-11-12-1.log.gz` |
| 宇宙內重生加一輪 | 拆床／無有效床，在 exact live U 主世界死亡 | 只有原版的 canonical fallback 改成留在同一 U，位置照原版重生規則：在 U spawn（與原版主世界 spawn 同座標）附近依 spawnRadius 找地面；拆床時仍顯示原版「床不見了」提示。已知行為：U spawn 若在海上且附近沒有地面，會落到海底重生（使用者 2026-11-11 接受） | PASS。拆床後在 U1 死亡：log `UNIVERSE_RESPAWN_FALLBACK player=f69ac82e-718b-3c37-bba5-1edbfda2ad69 universe=b068f3f8-ed2f-82b7-a42f-6c2e217cf2ea`、原版提示「你沒有床或已充能的重生錨，或是它受到阻擋」，重生在 U1 (5.5,74,-9.5)，在原版出生點 (9.5,67,-7.5) 附近。本輪出生點在陸地，沒有觀察到落海底。時間 14:00:18，log `logs/client-base/2026-11-12-1.log.gz` |
| 宇宙內重生加一輪 | DISABLED 重啟登入；另驗 canonical／共用 Nether／End 死亡 | DISABLED 不在登入時物化，保持既有行為；其他維度死亡照原版 | PASS（受控佈置）。停服後把世界複製成 `QC-M6-SP-disabled`，只在複本把 U1 的 catalog 改成 DISABLED（使用者 2026-11-12 授權改測試世界檔）。開服只物化 U2、U3（worlds=2）；玩家原在 U1，原版 WARN「Unknown respawn dimension … defaulting to overworld」，落在原版主世界同 x/z；登入後也沒有物化 U1（14:24，log `logs/client-base/2026-11-12-3.log.gz`）。其他維度死亡是在原世界重啟後那段做的（14:20–14:21，log `logs/client-base/2026-11-12-2.log.gz`）：沒床時原版主世界、Nether、End 都回原版主世界，沒有 FALLBACK；床在 U1 時死在 Nether 回 U1 的床 |
| release JAR（8-H 必要，2026-11-12） | 先在 named 開發 runtime（`runClient` 或 `runServer`，可含 testmod）建立受控驗收世界，裡面要有 normal 與 amplified 兩種 U，並記下兩者的 world key。再把同一個世界交給 release JAR（`remapJar` 產物）加 Fabric Loader 0.17.2、Fabric API 0.102.0+1.21 的 dedicated server（不放 testmod）開啟，各進出一次 U | log 沒有 mixin 套用錯誤（例如 `InvalidInjectionException`），包括文末 H1／H2 所列、正式版沒有使用者的 mixin；兩種 U 都能開，沒有 `SETTINGS_FINGERPRINT_MISMATCH`。世界在 named 建立、在 intermediary 開啟，所以這一列同時是「兩種 mapping 的 SHALLOW_V1 指紋相同」的一次性證據。精確的 `Missing data pack quantumchamber-testmod` 只算過期存檔警告。這是一次性人工證據，不是回歸 gate | PASS。單人世界（`runClient` 建立；U1 normal `quantumchamber:universe/b068f3f8-ed2f-82b7-a42f-6c2e217cf2ea/overworld`、U2 amplified `quantumchamber:universe/38ef41b6-323f-851a-ac82-8c011cdbe096/overworld`）的複本交給 release JAR（SHA256 `3e9ecd0f…`）＋Fabric Loader 0.17.2（官方 server launcher 1.1.2）＋Fabric API 0.102.0+1.21：mod list 沒有 testmod；開服物化三個 U；mixin 錯誤、`SETTINGS_FINGERPRINT_MISMATCH`、Exception、ERROR 皆 0。開發 client 登入 U1，再以 `/execute in … tp` 進出 U2（amplified 地形）；沒有在 release JAR 上走量子艙流程。唯一非離線模式的 WARN 是 `Missing data pack fabric-gametest-api-v1`：它不在本列判定條件內；世界在開發 runtime 建立時載入了 Fabric gametest 模組，release 版 fabric-api 不含它（jar 內 gametest 命中 0），依此推定為過期存檔 metadata、不是 testmod，沒有擴大上一欄的白名單。建世界與開世界兩端的 datapack 集合因此不同；這不影響 id 層指紋相同，但 same-seed 地形保證以兩端 datapack 相同為前提，「gametest pack 不含 worldgen 內容」是推論、未驗。時間 14:45–14:48，log `release-server-out.log`（server 端；只有傳送座標）、`logs/client-base/latest.log`（14:47:20–14:47:54 的 `Dimension` 讀值：U1 → 原版主世界 → U2 → 原版主世界） |
| /reload（8-H 必要，2026-11-12） | 單人（開作弊）與 dedicated 各做一次：`/reload` 成功後再啟動一次量子艙 | 量子艙正常啟動並完成一般流程；log 沒有 lifecycle 的 WARN（開頭是「M6 設定 context 已停用到重啟」「M6 reload 沒有成功」或「M6 reload 之後讀取設定來源失敗」）。若 reload 後量子艙不能用，就保存原件並開 production 修正 task | PASS。單人：`/reload` 後啟動量子艙 → NEW U2 跨越。dedicated：`/reload` 後 QC_A 啟動 → NEW U `16363c0a-8420-89df-bf96-0a0f9e9c8e35` 跨越。兩邊 log 都沒有 lifecycle WARN。時間 14:09–14:11（log `logs/client-base/2026-11-12-1.log.gz`）、14:38–14:41（log `logs/server/latest.log`） |

DISABLED 重啟登入等控制只在受控驗收世界做，不改正式使用者存檔。End 出口／credits 因有效 U 床返回 U 是預期行為；與預期不符才保存原件並回報，不新增 portal 注入點。

8-H 的 Windows default 走廊相關兩項都已完成，只改測試：
- 容量兩案清理（`5ab3aa4`）；
- M2 走廊預設等待 60 秒與逾時自清（`c410975`，使用者 2026-11-11 裁示）。

不把 4096 根因當已證。最終 full 必自然 END ALL exit=0，同 pin 最多重跑一次、第二次停報；production 容量與 old FAIL 均保留。main 合併、tag、人工代操作各自另問使用者。

## 本輪實際操作（2026-11-12）

- 基準：HEAD `71f4f72`（code pin `6965301`），porcelain 空。Minecraft 1.21、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Java 21；`runClient`／`runClientRender`／`runServer` 都不帶 testmod。
- 操作者：Claude 代操作。使用者授權鍵鼠、指令、截圖，也授權在測試世界改檔（DISABLED 列）與下載 Fabric 官方 server launcher（release JAR 列）。
- 世界：單人 `run/client-base/saves/QC-M6-SP-20261112`（預設地形、seed 20261112、創造＋指令）；DISABLED 用複本 `QC-M6-SP-disabled`；多人 `run/server/QC-M6-MP`（seed 20261113）；release server 放在 repo 外。沒有開啟使用者世界。
- 佈置（記為受控，非正式遊玩）：gamerule（停日夜、停天氣、不生怪、keepInventory、立即重生）；`/tp`、`/fill` 建艙（y=120 空中平台）；走廊補夜視；長效量子態藥水（`custom_effects` 12000 ticks）；`/setblock` 放地獄門與終界門。
- 偏差（不影響判定，細節在證據筆記）：
  - 剛進世界時第一批指令被吞，已重送。
  - 補平台的 `/fill` 把艙底基岩換成石頭，艙變 INVALID；改回後恢復。
  - 量子態效果還在時一關門就入場，我在走廊內下了 `/tp`，觸發保護性安全返還（兩則 WARN），玩家安全回原艙。
  - 多人輪喝藥時準星落在門上，門被打開（M1 已知坑），重新關門後入場。
  - 注音輸入法卡在中文模式，改用 IME 控制訊息關閉；關 client 時前景一度被 VS Code 取得，工具停止送鍵。
  - 其他操作性偏差：補平台時也誤清了比較器一欄（已補回）；在艙內關門要瞄 controller 底面右鍵（點門無效）；Nether 區塊未載入時 `/fill` 失敗，改為先 tp 再放；End 出口第一次放在終界石牆前被擋住，改放腳下。
  - 其他佈置：各輪入場前在已關門、尚未入場的艙內用 `/tp` 調整位置與朝向。第三輪沒有重新喝藥，沿用第二輪仍在的量子態效果（14:12:57 以 `execute if entity` 確認），14:13:12 `/tp` 後 14:13:13 隨即入場。完全重啟前從 U3 `/tp` 回 U1（14:15）。
- 證據：repo 外 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\`，含 `notes.md`、截圖、各 runtime log、catalog 讀回與 release server log。`evidence-index.json` 共 177 檔、175,660,034 bytes，SHA256 `84d058e4bb1ce22888d1fd7c7d91fed985e1064ffaa7522bba75851093fac8d7`。所有 log 合計（去重後的事件數；同一事件會同時出現在 game log、debug log 與 Gradle 輸出）：`UNIVERSE_RESPAWN_FALLBACK` 1 次（拆床那次）、`KEPT_CANONICAL` 0、lifecycle WARN 0、mixin 錯誤 0、`SETTINGS_FINGERPRINT_MISMATCH` 0。

## 8-H waiver（使用者 2026-11-12 裁示）

以下項目 M6 不做，列為已知限制；題目與選項原文見 plan「Task 8-H triage」節。因此 `main` 是開發快照，不適合未備份的正式世界。retained FAIL 都保留原判。
- H1 完整資料閉包（ProviderVersion≥2）：M6 只出 SHALLOW_V1。設定指紋只比對 id、型別 id、Parameters 與 GenerateStructures，不比對被引用 registry 元素的內容；換 datapack 不會被偵測，行為與原版換 datapack 相同（spec §3.1.3 已知限制）。
- H2 正式 lifecycle 完整證明：refresh seal 不在正式路徑。單人（integrated server）的啟動，由本清單的單人輪第一次實際覆蓋。同屬 H2 範圍的還有 8C-2 的一項 production 行為：從其他 thread 送來的異常 END／dispatch 不會讓 lifecycle 永久停用（只在異常情況出現，tracked 最小契約已守）。
- H1／H2 範圍的 mixin 在正式版仍會套用，但正式路徑沒有使用者（Task 22 整分支審查 A-I1）：
  - 共 11 個：template gate 兩個（`StructureTemplateManagerSettingsGateMixin`、`StructureTemplateSettingsGateMixin`）、template accessor 兩個、noise／density 狀態六個（EndIslands、Simplex、DensityBinary、Interpolated、OctavePerlin、Perlin）與 refresh seal 一個（`DataPackContentsRefreshSealMixin`）。
  - 其中 template gate 會包住原版的 template 存取，多一層讀鎖與兩個全域 monitor；寫鎖路徑在正式版不可達。另有 `TEMPLATE_OWNER_AMBIGUOUS` 失敗路徑，要同一個 template 實例經兩個 manager 取得才會觸發；原版每個 server 只有一個 manager，推論正常遊玩不會發生。
  - 這些 mixin 在 GameTest、原生鏈、完整回歸與 CI 都一直套用著，沒有出錯；在 release JAR 是否套用無誤，由上表「release JAR」一列驗證。
- H3 三 bundle 全套（intermediary 腳本、ci-rule 負例、workflow）：只做上表「release JAR」一列，加上固定指紋值 JUnit `a9871e8`。
- H4 真 `/reload` 的完整 GameTest、重疊 reload 與失敗的 reload：真 `/reload` 只做上表「/reload」一列，只驗成功路徑。
  - 設定 context 不可用的時段：reload 進行中；失敗的 reload（例如 datapack 壞掉）之後，到下一次成功的 reload 或重啟；兩次 reload 重疊（或收到不合序的 reload 事件）之後，要重啟 server（單人就是退出重進世界）才恢復。
  - Task 22 fix1（code `6965301`）之後，這段期間進行中的 NEW 走到物化會等待，context 恢復後續行，不會停服，也不會留下 `DISABLED`／`MATERIALIZE_FAILED`。journal 寫入照常，只有包含新 snapshot 的寫入會被拒；這類寫入只來自候選 batch，該 session 會安全返還。
  - 自動驗證的範圍：batch `m6_universe_reload_materialize` 的 GameTest 直接觸發 reload 事件，在物化這一層驗證 reload 進行中與失敗的 reload 之後都回 NOT_READY、server 仍在執行、沒有物化失敗紀錄、U 不是 ACTIVE、catalog 仍是 EAGER_ENABLED；成功的 reload 之後能物化；已 ACTIVE 的 U 在 reload 進行中仍能解析。session 的等待與「不留下 `MATERIALIZE_FAILED`」由程式保證，沒有直接測；journal 由 JUnit 驗證。真 `/reload`、重疊 reload（POISONED）與讀取設定來源失敗沒有自動測試。
  - 仍有的限制：這段期間新的量子艙無法啟動，玩家看不到原因，被拒時也不記 log。reload 進行中完全沒有 log；失敗或重疊的 reload 之後，server log 才有 lifecycle 的 WARN。兩次 reload 重疊後，等待中的 NEW 會一直等到重啟（超過 600 ticks 記一次 WARN）。
- H5 保留 journal slot 的事前攔截：沒有事前保護。其他 mod 若用保留 id `quantumchamber_sessions` 呼叫 `PersistentStateManager`，本模組要到下一次讀寫才會發現並停寫；在那之前，原版 autosave 可能把 foreign state 寫進該檔、覆蓋 checked journal，進行中 session 的收尾可能做不下去（使用者 2026-11-05 已接受此風險）。
- H6 `WorldGenerationOwnerAccess` public setter：canonical 主世界、共用 Nether／End 等 owner 為空的世界，刻意使用本模組內部 API 的其他 mod 能裝上偽 owner。之後的新 chunk 會用錯 seed 與結構旗標，生成後被存檔，屬持久污染。正常原版與本模組路徑都不會觸發。
- schema5 journal 讀回遇到暫時性的 IOException（例如 Windows AccessDenied）之後，該次開服不再寫 journal，要重啟才恢復（Task 22 整分支審查 C-M3）。這比 schema≤4 嚴格，符合 2026-10-21 AccessDenied 維持 fail-closed 的裁示。
- 其他已知殘留（只影響測試診斷或 CI）：
  - 走廊測試：一般 `TrustedSpace` 案逾時不自清、ARMING 逾時沒被強制驗過、靜態登記表保留未 close 測試的引用、通過的案例留下 session；
  - Task R：log 欄位沒有直接斷言，兩項極小建議（沒被讀取的欄位、`playerIn` catch 內的 `addSuppressed`）；
  - T14 的診斷標籤取錯值；
  - Windows default 走廊 4096 coverage 根因未證；
  - 8C-0 fixture 雙重失敗時的清理、8B3-LT 的 START／END 診斷沒有記 active RM。
  - 明細見 SDD `8h-triage-inventory-20261112.md`。
- 證據缺口（不是 waiver，在整分支審查揭露）：8C-1 首輪 after-lease mutation 的 raw log／XML 被固定檔名覆寫，已無法恢復。
