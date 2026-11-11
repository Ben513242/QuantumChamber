# M6 Overworld 人工 smoke 清單

狀態：2026-11-10 Task R 開工前修正裁示之待驗清單，本次唯一窄審 C0／I0／M0 已採用；2026-11-12 依 8-H 裁示新增兩列與 waiver 段。尚未實玩，所有結果待填。人工代操作另需使用者明確授權；不以本清單當 client 啟動授權。

範圍：平行宇宙只有主世界，FamilyCapabilityVersion=0；共用原版 Nether／End。SHALLOW_V1 僅 id 層設定 fingerprint，same-seed／toggle 保證以兩端 datapack／mod 配置相同為前提。8-H 已由使用者 2026-11-12 裁定：必要項是下表的「release JAR」與「/reload」兩列，其餘 waiver 列在文末；全部舊 FAIL 不改判。

每輪記版本／code SHA、世界、操作者、時間、玩家／Universe UUID、world key、seed／profile、receipt／log 路徑與實際結果。只使用另獲授權的新測試世界，不動使用者世界。

| 輪次 | 操作 | 預期 | 實際結果／證據 |
| --- | --- | --- | --- |
| 單人 | 從艙選 NEW，依 receipt 辨認 random profile 與 seed | 進入不同 seed 的 U 主世界，UUID／key 精確 | 未執行 |
| 單人 | 重進同一 Universe，正常存退並重啟登入 | 不重抽，仍同 UUID／key／seed 的 U | 未執行 |
| 多人 | 兩玩家走正式 NEW／跟隨與重進流程，存退重啟 | 各自保存正確位置與同一 U 權威，不串 U | 未執行 |
| 共用維度 | 從 U 經地獄門到原版 Nether 再回程；另驗 End 出口／credits | 地獄門回程一律落在原版主世界；終界出口照原版看床，床在 U 回 U、沒床回原版主世界；與死亡分開記 | 未執行 |
| 宇宙內重生加一輪 | U 內設床後死亡；正常存退重啟後再驗 | 既有 EAGER_ENABLED bootstrap 後同 U 床有效、登入仍同 UUID／key；不新增登入物化 | 未執行 |
| 宇宙內重生加一輪 | 有效床／錨位於另一 U 或 canonical；在 U 死亡 | 床／錨依原版優先，不另判合法性、不改錨消耗 | 未執行 |
| 宇宙內重生加一輪 | 拆床／無有效床，在 exact live U 主世界死亡 | 只有原版的 canonical fallback 改成留在同一 U，位置照原版重生規則：在 U spawn（與原版主世界 spawn 同座標）附近依 spawnRadius 找地面；拆床時仍顯示原版「床不見了」提示。已知行為：U spawn 若在海上且附近沒有地面，會落到海底重生（使用者 2026-11-11 接受） | 未執行 |
| 宇宙內重生加一輪 | DISABLED 重啟登入；另驗 canonical／共用 Nether／End 死亡 | DISABLED 不在登入時物化，保持既有行為；其他維度死亡照原版 | 未執行 |
| release JAR（8-H 必要，2026-11-12） | 先在 named 開發 runtime（`runClient` 或 `runServer`，可含 testmod）建立受控驗收世界，裡面要有 normal 與 amplified 兩種 U，並記下兩者的 world key。再把同一個世界交給 release JAR（`remapJar` 產物）加 Fabric Loader 0.17.2、Fabric API 0.102.0+1.21 的 dedicated server（不放 testmod）開啟，各進出一次 U | log 沒有 mixin 套用錯誤（例如 `InvalidInjectionException`）；兩種 U 都能開，沒有 `SETTINGS_FINGERPRINT_MISMATCH`。世界在 named 建立、在 intermediary 開啟，所以這一列同時是「兩種 mapping 的 SHALLOW_V1 指紋相同」的一次性證據。精確的 `Missing data pack quantumchamber-testmod` 只算過期存檔警告。這是一次性人工證據，不是回歸 gate | 未執行 |
| /reload（8-H 必要，2026-11-12） | 單人（開作弊）與 dedicated 各做一次：`/reload` 成功後再啟動一次量子艙 | 量子艙正常啟動並完成一般流程。若 reload 後量子艙不能用，就保存原件並開 production 修正 task | 未執行 |

DISABLED 重啟登入等控制只在受控驗收世界做，不改正式使用者存檔。End 出口／credits 因有效 U 床返回 U 是預期行為；與預期不符才保存原件並回報，不新增 portal 注入點。

8-H 的 Windows default 走廊相關兩項都已完成，只改測試：
- 容量兩案清理（`5ab3aa4`）；
- M2 走廊預設等待 60 秒與逾時自清（`c410975`，使用者 2026-11-11 裁示）。

不把 4096 根因當已證。最終 full 必自然 END ALL exit=0，同 pin 最多重跑一次、第二次停報；production 容量與 old FAIL 均保留。main 合併、tag、人工代操作各自另問使用者。

## 8-H waiver（使用者 2026-11-12 裁示）

以下項目 M6 不做，列為已知限制；題目與選項原文見 plan「Task 8-H triage」節。因此 `main` 是開發快照，不適合未備份的正式世界。retained FAIL 都保留原判。
- H1 完整資料閉包（ProviderVersion≥2）：M6 只出 SHALLOW_V1。設定指紋只比對 id、型別 id、Parameters 與 GenerateStructures，不比對被引用 registry 元素的內容；換 datapack 不會被偵測，行為與原版換 datapack 相同（spec §3.1.3 已知限制）。
- H2 正式 lifecycle 完整證明：refresh seal 不在正式路徑。單人（integrated server）的啟動，由本清單的單人輪第一次實際覆蓋。同屬 H2 範圍的還有 8C-2 的一項 production 行為：從其他 thread 送來的異常 END／dispatch 不會讓 lifecycle 永久停用（只在異常情況出現，tracked 最小契約已守）。
- H3 三 bundle 全套（intermediary 腳本、ci-rule 負例、workflow）：只做上表「release JAR」一列，加上固定指紋值 JUnit `a9871e8`。
- H4 完整 reload GameTest、重疊 reload 與失敗的 reload：只做上表「/reload」一列，只驗成功路徑。
  - 兩次 reload 重疊時，設定 context 會停在不可用狀態，要重啟 server（單人就是退出重進世界）才恢復。
  - 失敗的 reload（例如 datapack 壞掉）也會讓 context 不可用，直到下一次成功的 reload 或重啟。
  - 不可用期間新的量子艙都會啟動失敗，玩家看不到原因。進行中的 NEW 若在這段期間走到物化，是否會留下持久的 `DISABLED`／`MATERIALIZE_FAILED`，尚未確認。
- H5 保留 journal slot 的事前攔截：沒有事前保護。其他 mod 若用保留 id `quantumchamber_sessions` 呼叫 `PersistentStateManager`，本模組要到下一次讀寫才會發現並停寫；在那之前，原版 autosave 可能把 foreign state 寫進該檔、覆蓋 checked journal，進行中 session 的收尾可能做不下去（使用者 2026-11-05 已接受此風險）。
- H6 `WorldGenerationOwnerAccess` public setter：canonical 主世界、共用 Nether／End 等 owner 為空的世界，刻意使用本模組內部 API 的其他 mod 能裝上偽 owner。之後的新 chunk 會用錯 seed 與結構旗標，生成後被存檔，屬持久污染。正常原版與本模組路徑都不會觸發。
- 其他已知殘留（只影響測試診斷或 CI）：
  - 走廊測試：一般 `TrustedSpace` 案逾時不自清、ARMING 逾時沒被強制驗過、靜態登記表保留未 close 測試的引用、通過的案例留下 session；
  - Task R：log 欄位沒有直接斷言，兩項極小建議（沒被讀取的欄位、`playerIn` catch 內的 `addSuppressed`）；
  - T14 的診斷標籤取錯值；
  - Windows default 走廊 4096 coverage 根因未證；
  - 8C-0 fixture 雙重失敗時的清理、8B3-LT 的 START／END 診斷沒有記 active RM。
  - 明細見 SDD `8h-triage-inventory-20261112.md`。
- 證據缺口（不是 waiver，在整分支審查揭露）：8C-1 首輪 after-lease mutation 的 raw log／XML 被固定檔名覆寫，已無法恢復。
