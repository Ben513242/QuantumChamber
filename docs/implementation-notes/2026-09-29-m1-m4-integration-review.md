# M1–M4 整分支 final review 與整合修正紀錄

日期：2026-09-29（Asia/Taipei）。分支 `feature/m1-chamber`。整合修正後的 code HEAD：`428f52a79daa18ab9f5fd7a7f0f2980a34598987`（其後只有文件 commit）。

本文是 [AGENTS.md](../../AGENTS.md)「下一步」所列「M2 整分支 final review」gate 的結果紀錄。M2 過去沒有做過 whole-branch review；本輪以整條 feature branch 為範圍一次完成，同時完成 M2、M3-A／M3-B、M4 deferred Minors 的 final triage。

本文只記錄 review、程式修正與本機自動 gate，不代表下列任何一項：

- 人工驗收已通過。除 B-5 的自有世界預驗（不計入 E 段）外，人工驗收尚未開始；見 [M1–M4 人工驗收清單](../manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md)。
- GitHub Actions 已通過。本文的 gate 都是 Windows 本機結果；feature HEAD 在 Ubuntu 與 Windows job 的結果，以 GitHub Actions 上該 commit 的 run 為準。
- 已合併 `main` 或已打 tag。實際狀態以 `main`／tag 為準。

原始證據在 gitignored 的 evidence owner `.superpowers/sdd/2026-09-21-m4-candidate-doors/`（本機 scratch，不在 repo）。本文直接寫出關鍵數字與 SHA-256；gate harness 的可重現性限制與 [M4 紀錄](2026-09-21-m4-candidate-doors.md)「可重現性限制」相同，另加本輪的 `ifix*-gradle.ps1`、`ifix*-final-green.ps1`、`ifix*-build-summary.ps1` 與 `ifix2-ci-rule-check.ps1`／`ifix3-ci-rule-check.ps1`。下文 `main/` 代表 `src/main/java/dev/quantumchamber/`，行號以 `428f52a` 為準。

## 範圍與方法

- Review range：`ddc8b1e..1f900f5`（`ddc8b1e` 為 `main` 的分岔點）。`752ada1..1f900f5` 只有文件 commit，所以 code 以 `752ada1` 為準。
- 範圍由使用者 2026-09-29 以 10 類唯讀清單指定。3 位 fresh reviewer 並行唯讀審查：
  - R1：1 Git 交付、8 mixin／client-server／產物、10 文件與人工清單。
  - R2：2 M2 供電與 session、3 多人 cohort、5 走廊幾何與 page、7 M3／M4 對 M2 的回歸，以及 M2 deferred triage。
  - R3：4 persistence／schema、6 recovery／原生存檔、9 測試 oracle，以及 M3-A／M3-B／M4 deferred triage。
- Controller 以讀碼或查 CI 核實後，彙整成 `m1m4-whole-branch-final-review.md`（SHA-256 `d325a65ccad6c89a27de41fdfcee198d3d8f8d50c37d5ffd97925b07743f5ff8`）。
- 結論：Critical 0、Important 7。Ready to merge 當時為 **No**，修正後為 **With fixes**。
- 使用者決定（2026-09-29 10:05）：
  - 7 項 Important 全部在人工驗收前修正。F-PLATFORM 含 `start()` 預檢；F-ENTITY 採「走廊世界禁止玩家、掉落物、投射物以外的 entity，經驗球比照掉落物搬運與返還」。
  - R2 Minor M1–M4 本輪一併修正，其餘 Minor 延後 M5。
  - R1 M-4（PUBLIC repo 的 tracked 文件含本機絕對路徑）維持現狀。

## Findings 與處置

### Important

| ID | 來源 | 問題（摘要） | 修正 commit | 狀態 |
| --- | --- | --- | --- | --- |
| F-CI | R1 I-1＝R3 I-2 | Ubuntu CI 自 M4 程式推上遠端起就是紅燈：run `35968503858`（`9c421a7`）與 `35975280939`（`1f900f5`），兩者 code 都等同 `752ada1`，21/135 required GameTests 失敗；最後一次綠燈是 `687cff9`。根因是 `M4CandidateDoorGameTests` 沒有 `Platform.isWindows()` gate：非 Windows 入場在 `PlayerCheckpointStore.verifyAndForce` 被拒，16 個 M4 test 失敗且沒釋放走廊容量，連帶 5 個 M2 test 失敗。文件的「default 135/135」也沒標明只是 Windows 本機數字。 | `c474338`、`b53c253`、`e20cd9d`、`512f330` | 本機 CI 規則驗證通過；GitHub Actions 結果以 feature HEAD 的 run 為準 |
| F-FREEZE | R2 I1 | RETURNING 期間 `SessionRecoveryManager.blocks` 不看 `returned`：只要一人離線不回來，其他已返還者的移動、載具與方塊互動就被永久丟棄（重登、重啟、死亡重生後都一樣）。 | `48c7dd8` | CLOSED |
| F-PLATFORM | R2 I2＝R3 m-2 | 非 Windows 或非本機 NTFS 時，玩家仍會真的進走廊；checkpoint 必定失敗，rollback 後原艙永久停在 RETURNING／比較器 11，cohort 永久凍結，重啟亦同。README 寫的「保守拒絕」與事實不符。 | `d0a14bb`、`148ba4b` | CLOSED |
| F-ENTITY | R2 I3 | pin 只認玩家、掉落物與投射物，production 沒有任何 entity 放置防護；走廊內的船（含箱船內物品）、盔甲架、展示框、生物、經驗球，會在換頁退休或返還清空時掉進 void 遺失。 | `9edf1c0`（另見 N-1） | CLOSED |
| F-CHECKPOINT | R3 I-1 | MEASURED 清理遇到持續的原生存檔失敗時沒有退避：每 tick 都做全服 `server.save` 並記一次含 stack 的 WARN，造成 20Hz 全服存檔與 log 洪水（資料面仍 fail-closed）。 | `1ca107d` | CLOSED；「revision 只看相關 chunks」仍屬 M5 hardening (2) |
| F-DOC-B5 | R1 I-2 | 使用者 09-29 在自有世界做了 B-5（非 W-M4、測前未備份）之後，清單的補填參考失真、清單與 AGENTS 對該世界的用語矛盾，預驗結果可能被誤算成已關閉 B-5，也缺少「已在非測試世界點過側門」的處置指引。 | 本文所在的文件同步 commits | 見人工驗收清單 2.5 (a)、B-5 預驗表、E 段 |
| F-DOC-GUARD | R1 I-3 | 清單 2.2 的守門指令涵蓋 `src/test`／`src/testmod`、有輸出就停止、硬性要求 code 等同 `752ada1`，與 AGENTS「只重驗受影響項」衝突；E 段沒有「review 引起程式變更」的條款。 | 本文所在的文件同步 commits | 見人工驗收清單 2.2、紀錄欄位、E 段 |
| N-1 | round 1 spec review 新增 | F-ENTITY 的 `ServerWorld.addEntity` backstop 讓肩上鸚鵡永久遺失：原版 `PlayerEntity.dropShoulderEntities` 以 `tryLoadEntity` 生成鸚鵡後忽略回傳值，接著無條件清空肩上 NBT。 | `d476ddc`（server）、`a7ff817`（client 畫面） | CLOSED；client 修正沒有以真 client 實測，由人工驗收 B-6e 補 |

### 本輪一併處理的 Minor

| 項目 | 問題 | 處置 |
| --- | --- | --- |
| R2 M1 | `SessionTransferService` 吞掉例外，沒有 cause／UUID／world；返還移動失敗沒有任何 log | `09f2470`、`f7d7c7c`：WARN 含 cause、UUID、type、來源與目的 world（同 entity 同原因一次）；只有返還嘗試後玩家仍不在原艙 interior，才記「返還移動尚未確認」 |
| R2 M2 | 正常 Buff 到期／喝奶的返還印 WARN＋完整 stack | `541295a`：改記 INFO「…（正常結束，非故障）」，不附 stack；真正故障仍 WARN＋stack |
| R2 M3 | LATERAL 模式 `toggleEntrance` 只擋 legacy，修改版 client 可以在走廊牆面開出 5×5 洞 | `24f3e11`：LATERAL 一律拒絕，actionbar「左右走廊的入口正門位於走廊牆面，不開放切換。」 |
| R2 M4 | ARMING rollback 把從未進走廊的在線成員也拉回凍結 pose | `e93696a`：只拉回本 session 嘗試移動過、或目前真的在走廊世界的成員 |
| round 1 quality q-1＝spec N-2 | `/tp` 等指令把非管理 entity 送進走廊時，原 entity 與副本都遺失 | `790100a`：`Entity.teleport(ServerWorld,…)` 開頭即拒絕，entity 留在原處 |
| round 1 quality 待確認 1 | 網路磁碟機時，預檢先對遠端 root 開 handle | `148ba4b`：先判定本機固定 NTFS，才開任何 handle |
| round 1 quality q-4 | 測試 hook 只在成功路徑移除，失敗或逾時會殘留並連鎖影響其他 test | `3e61cd5`（testmod） |
| round 1 spec N-5 | Windows job 只核對手寫清單；Ubuntu 沒有斷言唯一的非 Windows 真實入場覆蓋有執行 | `e20cd9d`、`512f330`（CI） |
| round 2 quality qm-1＝spec sm-2 | client 端本機肩上鸚鵡在飛行、睡眠、粉雪時畫面消失 | `a7ff817` |
| round 2 spec sm-1 | N-2 GameTest 的真指令 witness 空過 | `2262518`（testmod） |
| round 2 quality qm-2＝spec sm-3 | CI 原始碼掃描在宣告格式漂移時漏要求 | `512f330`（CI） |
| round 2 Nit | `TestHooks.release` 只接 `RuntimeException`，`Error` 會中斷其餘釋放 | `428f52a`（testmod） |
| R1 M-1、M-2、M-3、M-5；R3 m-1 | 狀態句漏列 M1.1；M2 review 範圍已裁定需更新；README `runServer` 會開到 `m1-smoke`；同步清單漏兩處；schema3 降版警告只寫在清單 | 本文所在的文件同步 commits |

其餘 Minor 的 triage 見下方「Deferred triage」與「延後清單」。

## 修正 commits（`git log --oneline 1f900f5..428f52a`，20 commits）

| Round | Commit | 類別 | 內容 |
| --- | --- | --- | --- |
| 1 | `c474338` | test／CI | F-CI：18 個 M4 GameTest 加 `_windows` 後綴與平台 gate（非 Windows 在 per-test probe 開始前 `complete()`，不寫 receipt、不佔容量） |
| 1 | `48c7dd8` | fix | F-FREEZE：RETURNING 只凍結尚未返還者 |
| 1 | `d0a14bb` | fix | F-PLATFORM：`start()` 在任何 reservation 之前做 checkpoint 能力預檢；4 個 M2 staging test 改為 `_windows` |
| 1 | `9edf1c0` | fix | F-ENTITY：走廊世界的 entity 准入規則、物品使用層拒絕與兩道 backstop；經驗球納入 pin |
| 1 | `1ca107d` | fix | F-CHECKPOINT：per-space 指數退避與 WARN 去重 |
| 1 | `09f2470` | fix | R2 M1：記錄原生移動例外與返還 pending（各一次） |
| 1 | `541295a` | fix | R2 M2：正常 Buff 失效返還改記 INFO |
| 1 | `24f3e11` | fix | R2 M3：LATERAL 入口正門拒絕切換 |
| 1 | `e93696a` | fix | R2 M4：ARMING 中止只拉回已移動的成員 |
| 1 | `b53c253` | CI | Windows job 必要清單加入本輪新 test |
| 1 | `f7d7c7c` | fix | R2 M1 精修：只有返還後仍不在原艙才記 pending move WARN |
| 2 | `d476ddc` | fix | N-1：走廊世界內不放下肩上 entity |
| 2 | `790100a` | fix | N-2：拒絕把非管理 entity 以指令傳送進走廊 |
| 2 | `148ba4b` | fix | 開任何 checkpoint handle 前先確認本機 NTFS |
| 2 | `3e61cd5` | test | q-4：失敗或逾時也釋放 GameTest hook |
| 2 | `e20cd9d` | CI | N-5：所有 `_windows` 在 Windows 必須實際通過；Ubuntu 斷言兩個跨平台 test |
| 3 | `a7ff817` | fix | client 端本機玩家在走廊也保留肩上鸚鵡 |
| 3 | `2262518` | test | N-2 指令 witness 完整解析並使用獨立 entity |
| 3 | `512f330` | CI | 寬鬆掃描交叉核對 `_windows` 宣告，並一次列出所有問題 |
| 3 | `428f52a` | test | `TestHooks` 釋放時也接住 `Error` |

每個 finding 都先 RED 再 GREEN（能 RED 的項目）；RED、無效 RED 與非 RED 失敗 root 都保留不改判，清單見 `ifix1-report.md`、`ifix2-report.md`、`ifix3-report.md`。

## 行為變更摘要（`428f52a`）

1. **RETURNING 只凍結尚未返還者（F-FREEZE）**：`SessionRecoveryManager.blocks`（`main/persistence/SessionRecoveryManager.java:45-54`）對 RETURNING 與 MEASURED 都只攔 `!returned` 的參與者；JOIN 排隊與斷線 pending 的條件不變。已返還者可以正常移動與互動；離線者重連後的下一 tick 才返還。原艙保護與比較器 11 維持到全員收尾，已返還者在收尾前也不能加入新 session（`SessionRecoveryState.participantsAvailable`，`main/persistence/SessionRecoveryState.java:146-151`）。
2. **checkpoint 能力預檢（F-PLATFORM）**：`start()` 在 `participantsAvailable` 之後、凍結 candidate context、reservation、效果快照與任何移動之前呼叫 `checkpointCapable()`（`main/superposition/SuperpositionSessionManager.java:103-106`、`:141-153`）。
   - 非 Windows，或 Windows 上 playerdata 不在本機固定 NTFS（UNC／網路磁碟機、非 NTFS、卸除式磁碟、祖先目錄含 junction 或其他 reparse point、stream 或別名路徑）時 REJECTED，原艙停在 READY（比較器 7）。
   - 同一原因只記一次 WARN「玩家原生 checkpoint 能力預檢未通過，拒絕入場（不預留走廊、不改效果、不移動玩家）：…」，恢復時記一次 INFO。原因文字依情況不同，例如非 Windows 為「checkpoint 尚未驗證非 Windows 平台，保守拒絕」、網路磁碟機等非本機固定磁碟為「checkpoint 僅支援本機固定磁碟」、本機但非 NTFS 為「checkpoint 僅驗證本機 NTFS；其他檔案系統保守拒絕」、路徑上有 reparse point 時通常為「checkpoint 拒絕 reparse point」（`main/persistence/PlayerCheckpointStore.java:15`、`main/persistence/WindowsCheckpointNative.java:39`、`:45`、`main/persistence/WindowsPlayerCheckpointVerifier.java:123`）。
   - 判定重用 `WindowsPlayerCheckpointVerifier.verifyDirectory`（`main/persistence/WindowsPlayerCheckpointVerifier.java:54-82`），先 `requireLocalNtfs` 才開任何 handle（`:71`；`main/persistence/WindowsCheckpointNative.java:38-45`），不開玩家檔。
   - 已知殘留：預檢只涵蓋目錄鏈；玩家 `.dat` 本身是 reparse／hardlink 或超過 64 MiB 時，仍會在入場後的 checkpoint 才發現。
3. **走廊世界 entity 准入（F-ENTITY、N-1、N-2）**：整個 `quantumchamber:superposition` 世界只接受玩家、掉落物、投射物與經驗球（`main/corridor/SuperpositionEntityPolicy.java:37-47`）。
   - 物品使用層（`main/mixin/ItemStackSuperpositionEntityMixin.java:22-40`）：船／箱船、各式礦車、盔甲架、物品展示框／螢光物品展示框／畫、生怪蛋、裝有生物的桶、終界水晶、拴繩、滯留型藥水，在物品邏輯執行前回 FAIL，不消耗物品，actionbar「量子走廊內不能放置船、盔甲架、展示框、生物等實體；物品未消耗。」（`SuperpositionEntityPolicy.java:63-79`）。
   - backstop：`ServerWorld.addEntity`（`main/mixin/ServerWorldEntityAdmissionMixin.java:21-27`），以及 `Entity.teleportTo`、`Entity.teleport(ServerWorld,…)`（`main/mixin/EntitySuperpositionTeleportMixin.java:27-43`）；後者涵蓋 `/tp`、`/execute in … run tp`、`/spreadplayers`，非管理 entity 留在原處。拒絕時每種類、每條路徑只記一次 WARN（`SuperpositionEntityPolicy.java:81-89`）。
   - 已知限制：指令傳送被拒時，原版仍顯示成功並回傳 1；`/summon` 被拒時同樣顯示成功。蛋孵小雞、終界珍珠生蠹魚被抑制。
   - 經驗球比照掉落物：計入 pin、跨 seam 搬移、返還時送到原艙中央（`main/corridor/CorridorPageManager.java:1015-1026`、`main/corridor/CorridorRepositionService.java:88`、`main/persistence/SessionRecoveryManager.java:172-183`），也計入全域 pin 上限 256（`CorridorPageManager.java:916-920`）。
   - 肩上鸚鵡：玩家在走廊世界時略過原版放下（`main/mixin/PlayerEntityShoulderSuperpositionMixin.java:21-24`、`SuperpositionEntityPolicy.java:49-61`），server 與本機 client 畫面一致，返還後仍在肩上；走廊內死亡時留在重生玩家肩上（`ServerPlayerEntity.copyFrom` 複製肩上 NBT，僅 bytecode 論證）。client 端只依 world key 判定，連到其他也有同名維度的 server 時，本機放下時機會與原版不同。
   - 已知殘留：舊存檔 superposition 世界中已存在、由 chunk 載入的非管理 entity 不經 `addEntity`，本輪未處理；騎乘載具入場後在走廊斷線重登，載具會遺失（修正前亦然）。
4. **MEASURED checkpoint 退避（F-CHECKPOINT）**：原生存檔 checkpoint 失敗時 per-space 指數退避 20→40→…→1200 ticks，退避期間不掃描、不存檔；成功或 receipt 改變時重置（`main/corridor/CorridorPageManager.java:60`、`:845-849`、`:858-867`、`:875-883`）。WARN 同原因只在第一次附 stack，之後只計數，恢復時記 INFO（`:1032-1035`、`:1049-1059`）。
5. **log 分類（R2 M1、R2 M2）**：transfer 例外 WARN 含 cause、UUID、type 與 from／to world（`main/transfer/SessionTransferService.java:50-58`）；只有返還後玩家仍不在原艙才記「返還移動尚未確認」（`SessionRecoveryManager.java:148-153`、`:190-195`）。正常 Buff 到期或喝奶的返還記 INFO（`SuperpositionSessionManager.java:186-196`、`:369-386`）。仍記 WARN／ERROR＋stack 的正常結束（N-4，延後）：入場移動途中喝奶、remap 期間 Buff 失效、一般斷線或死亡。
6. **LATERAL 入口正門（R2 M3）**：LATERAL 模式走廊內右鍵入口 Controller 一律拒絕，門保持關閉，session 不受影響（`CorridorPageManager.java:458-460`）。這依使用者決定改寫 lateral spec §3「正面門仍由原 Controller 門交易處理」；M2 spec §4 的前門開關只適用 legacy 前後模式。
7. **ARMING 中止（R2 M4）**：rollback 只拉回本 session 已嘗試移動、或目前在走廊世界的成員，仍在原艙內的成員保持原 pose（`main/superposition/SuperpositionSession.java:25-26`、`SuperpositionSessionManager.java:286`、`:355-358`）。但 durable RETURNING 之後，`recover()` 仍會把已離開原艙 interior 的未返還成員（例如 ARMING 期間用珍珠或歌萊果離艙）送回原艙返還位（N-3，行為變更延後 M5）。
8. **schema3 不可降版**（R3 m-1，行為未變、補文件）：用本 build 建立過走廊 session 的存檔會寫成 schema3，M4 之前的 build 讀取會 fail closed。

## Gates

三輪都在 Windows 本機、fresh root、`--rerun-tasks`、未設 `onlyBatches` 下執行；每輪 production 有變更，全套 gates 都在該輪最終 HEAD 重跑。所有 GameTest 與 main-only root 的 world restore 都是 `exactRestored=true`、`deleted=false`。

| Gate | Round 1（`f7d7c7c`） | Round 2（`e20cd9d`） | Round 3（`428f52a`，目前 code HEAD） |
| --- | --- | --- | --- |
| Full `clean test runGameTest build` | `task9-ifix1-final2-full-c468f1af4f44448abf0cecbfb408b0a2`：default 144/144；JUnit 397、0 failure／error、2 skip；Windows checkpoint JUnit 16；per-test receipts 26 | `task9-ifix2-final-full-187e976b50d14692ae06f05ea9a8b13b`：146/146；JUnit 398／0／2 skip；Windows checkpoint 17；receipts 28 | `task9-ifix3-final-full-caa49a0fb8364e86beeb8c5a31ca9a57`：146/146（其中 `_windows` 73）；JUnit 401、0 failure／error、2 skip；Windows checkpoint 17；receipts 28 |
| `runGameTestLegacy` | `task9-ifix1-final2-legacy-477f584dd7144ef2857c87da4bc13c32`：1/1 | `task9-ifix2-final-legacy-c3818120e0f44b06b418674ac4605899`：1/1 | `task9-ifix3-final-legacy-b9e4f9ff6f344eac9c09e5ed81c0058c`：1/1 |
| Release／sources JAR entries | 298／189 | 299／190 | 299／190 |
| main-only `runServer`＋三層 oracle | `task9-ifix1-final2-main-only-bbc0d07a83de42a18c8676ae01d8cf7e`：PASS | `task9-ifix2-final-main-only-2a1f28fa0b4b45c5aa9f1d647bf8ce06`：PASS | `task9-ifix3-final-main-only-1f05282dc84749009f6f22f1b36b02a2`：PASS |
| M3 lifecycle 4＋transfer 5 | `task9-ifix1-final2-m3-831b592fdf524e969086a426298c8842`：9/9 | `task9-ifix2-final-m3-e9e6f70ecb814ab88ab93db32ec9ce20`：9/9 | `task9-ifix3-final-m3-7b631679c3ae45a3890d9361e20b3d1c`：9/9 |
| M4 recovery 全 phases | `recovery-run-5562c04489394d268dbaaf91d45e358b.json`：24/24 | `recovery-run-5b57104426ef434a9d4e67cd878e01af.json`：24/24 | `recovery-run-c772bda1174147c9b9e2fd9a9ca51d60.json`：24/24 |
| Verification summary（SHA-256） | `ifix1-verification-summary.json`（`42f69524e11a4d280bde6d19ebe504f6b5bcb00a198011b1903f18d288632e5c`） | `ifix2-verification-summary.json`（`fc289e70f2e0b497f6f8d46033f301c8e84325cba36571afcf73ce69b0e34f50`） | `ifix3-verification-summary.json`（`7b562188772d8510c81f04d327beae81a9c538cdccd295c18185a6696626563e`） |

Round 3 細節（`428f52a`）：

- JUnit 的 2 個 skip 都是 `NonWindowsPlayerCheckpointStoreTest`（Windows 上合法略過）。Windows checkpoint JUnit 17＝`WindowsPlayerCheckpointVerifierTest` 12＋`PlayerCheckpointStoreTest` 3＋`PlayerRecoveryCheckpointTest` 2；`SuperpositionEntityPolicyTest` 3/3。
- default GameTest XML SHA-256 `fa59b5e441f7b53e40a28663533a4cf789920f637849403f33f606e15452d8ea`；legacy XML `b218808c111826f97b6b8740de9a765506db0ea191e719b0253049c444f71a40`。
- Release JAR SHA-256 `9d995afcc6c0d780815a423eaea76311808eddd49c952bc0d577c00f2b19a9ce`；兩個 JAR 的 testmod／gametest／probe 命中都是 0，唯一合法的 probe 是 production `dev/quantumchamber/compat/ModPresenceProbe`。Sources JAR 的整檔 SHA-256 不可跨 build 比較（zip 時間戳）。
- main-only（server PID 67788）：Fabric mod list 無 testmod、classpath／argfile 命中 0、47 個 testmod-only types 的 class-load 命中 0、stale datapack warning 0、shutdown world keys 為 overworld／the_end／the_nether／`quantumchamber:superposition` 四鍵；oracle SHA-256 `4d70d8f9b2446973ddb217f6c530f429210b851a430fb052eaeabdf022657165`。
- M4 recovery summary SHA-256 `3bfc86a1c34b64c23d6fbb16e5d7a1de0680d4c7865db4c6be5db085c5d71e60`。
- Per-test receipts 的逐份 SHA-256 見 [M4 紀錄](2026-09-21-m4-candidate-doors.md)「目前 gates」。
- Evidence manifest `ifix3-evidence-sha256.json`（1127 筆）SHA-256 `017b357d404430d144d4ce433c8002f09d06df4973454387bc41fae2c40cf604`。

### 測試名稱與新增測試

- M4 的 18 個 GameTest（default 17＋legacy 1）全部加上 `_windows` 後綴，receipt 名稱同步；4 個 M2 staging test 也改為 `_windows`：`native_buff_expiry_during_geometry_keeps_precommit_returning_windows`、`native_staging_disconnect_keeps_frozen_offline_participant_windows`、`native_staging_extra_occupant_rejects_without_shortening_journal_windows`、`native_staging_low_keeps_current_effect_and_source_protection_windows`。
- 新增 11 個 GameTest（135→146）：
  - 跨平台（Ubuntu 也真實執行）：`unsupported_checkpoint_platform_rejects_start_without_residue`、`unmanaged_entity_command_teleport_into_superposition_stays_at_source`。
  - Windows：`native_returned_participant_moves_while_offline_member_pending_windows`、`native_superposition_rejects_entity_placement_without_consuming_items_windows`、`native_experience_orb_crosses_seam_and_returns_before_cleanup_windows`、`native_shoulder_parrot_stays_on_shoulder_in_corridor_and_returns_windows`、`native_measured_checkpoint_failure_backs_off_then_reaches_dormant_windows`、`native_return_move_exception_logs_cause_once_then_recovers_windows`、`native_moving_player_return_logs_no_pending_move_warning_windows`、`native_buff_loss_return_logs_info_without_stack_windows`、`native_arming_abort_leaves_unentered_members_in_place_windows`。
- 新增 JUnit：`WindowsPlayerCheckpointVerifierTest.nonLocalVolumeIsRejectedBeforeAnyRootOrDirectoryHandleOpens`、`SuperpositionEntityPolicyTest`（3 例），以及 F-PLATFORM 的預檢案例（含非 Windows 專屬的 `NonWindowsPlayerCheckpointStoreTest.startCapabilityPrecheckRejectsBeforeNativeInitializationOrDirectoryAccess`）。
- `unmanaged_entity_command_teleport_into_superposition_stays_at_source` 在 `2262518` 改為兩隻獨立的牛分別驗 API 與真指令路徑，以 `String.format(Locale.ROOT,"%.3f")` 組指令、斷言 `dispatcher.parse` 完整消耗輸入，並有同世界指令正向對照。`e20cd9d` 版本的真指令 witness 約 56% 的執行會因座標印成科學記號、解析失敗而空過，且與 API 步驟共用同一隻牛，任何一次執行都無法獨立偵測指令路徑回歸（round 3 reviewer Minor 1）。
- 完整清單以 `git grep -n '@GameTest' 428f52a -- src/testmod` 為準。

### CI 規則（`.github/workflows/build.yml`）

- Windows job（`clean build runGameTest --rerun-tasks`）：
  - 既有的手寫必要清單保留作為下限。
  - XML 內所有 `*_windows` 都不得有 failure／error／skipped。
  - `src/testmod/java/**/*GameTests.java` 宣告的每個 `_windows`（排除只在 `runGameTestLegacy` 執行的 `batchId="m4_legacy_runtime"`）都必須出現在 XML；以嚴格與寬鬆兩種掃描交叉核對，名稱集合不一致即失敗。
  - 所有問題彙整後一次失敗並列出全部名稱。
- Ubuntu job：`unsupported_checkpoint_platform_rejects_start_without_residue` 與 `unmanaged_entity_command_teleport_into_superposition_stays_at_source` 必須存在且實際通過。
- 已知限制：
  - CI 不跑 `runGameTestLegacy`，legacy 只有本機證據；`build.yml:135`、`:170` 的註解是指 legacy batch 不列入 Windows 清單，不代表 CI 有執行它。
  - 原始碼掃描只看 `*GameTests.java` 檔名；把 `@GameTest` 放在其他檔名的 class，兩種掃描都看不到。
  - `*GameTests.java` 內名稱以 `_windows` 結尾、但不是 GameTest 的 `void` helper 會被判紅（命名慣例）。
- 本機驗證：`ifix3-ci-rule-check.ps1` 逐字抽出 workflow 的 step，以 pwsh 7 對 fixture XML 執行。新規則 `final-win-green` PASS（宣告 73、XML 73）、宣告漂移／缺席等缺陷副本全部 FAIL、Ubuntu step PASS、刪掉跨平台 test 則 FAIL；舊規則對「宣告漂移＋缺席」仍 PASS，即原本的缺口。
- Ubuntu 沒有在本機實跑。Round 1 有一次 `-Dos.name=Linux` 路由模擬（`ifix1-oslinux-sim-e0c98fafd93d4e1c8d408140a6045bcc`，144/144），這不是 Linux runtime 實測。

### Review 結果

| Round | Range（review package） | Spec | Quality |
| --- | --- | --- | --- |
| 1 | `1f900f5..f7d7c7c`（`ifix1-review.diff`） | ❌：原 9 項全部 CLOSED、gates 原始數字重算相符，但新增 Important N-1（肩上鸚鵡）與 Minor N-2～N-6 | Approved：Critical 0／Important 0／Minor 6（含 Nit）／待確認 3 |
| 2 | `f7d7c7c..e20cd9d`（`ifix2-review.diff`） | ✅：Critical 0／Important 0／Minor 3／Nit 3；N-1 CLOSED | Approved：Critical 0／Important 0／Minor 2／Nit 5／待確認 4 |
| 3 | `e20cd9d..428f52a`（`ifix3-review.diff`） | 單一 reviewer 兼 spec＋quality：✅＋Approved；範圍內 7 項 CLOSED；Critical 0／Important 0／Minor 2（皆為文件措辭，已寫入本文與 M4 紀錄）／Nit 3 | 同左 |

Review package SHA-256：`ifix1-review.diff` `ce0da86ab33c2b1b9a3226503d879243c990a4026a95076368d12b64cb2134da`、`ifix2-review.diff` `0b3c9dec0b4f895b3cf54c9e0e126f95cdb21a47f12a3f58f1509710c6a6bfd5`、`ifix3-review.diff` `74ec9799e3b0b34ac265c00341fc26b64cf114d172563f89b13627eebf82b2b3`。

報告勘誤：`ifix1-report.md` 所稱 Windows 必要 M4 清單「17 個」實為 18 個；「日後 batch 數超過 192」的 HashMap resize 門檻實為 96。

流程紀錄：round 3 implementer 在 RED 還原時使用了 `git checkout --`（違反本 repo 禁令），`core.autocrlf=true` 使檔案轉成 CRLF；已以注入前快照複製回 LF 原檔並核對 SHA-256，controller 與 reviewer 都確認工作樹與 index 乾淨、內容無漂移。

### 本輪 runtime log 觀察

Round 3 full gate 的 stdout 有 47 則 WARN／ERROR，逐類比對 round 2（46 則）後唯一的差異，是 `m2_native_partial` batch teardown 時首見 1 則 `返還保留 pending，session=…: java.io.UncheckedIOException: journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot`（`SessionRecoveryManager.java:104-106`）；下一 tick 重試成功、該 test PASS、receipt 與 world restore 正常。歷來 8 個 full gate root 都沒有出現過。該 log 只記例外 class 與 message、不記 cause，無法證實根因（推測為 Windows 上 journal 暫存檔 `ATOMIC_MOVE` 遇到暫時性檔案鎖）。

## 對人工驗收的影響

人工驗收在本輪修正前除 B-5 自有世界預驗（不計入 E 段）外尚未開始，所以修正不造成實際的重驗負擔。清單已改以 `428f52a` 為程式基準，並依本輪變更改寫或新增：

- 預期文字改寫：2.5 (c)(d)、A-2、B-1b、B-3 前置條件、C-2b、C-3b（已返還者可移動）；A-1、C-1b（平台說明）；B-1b、C-3a、C-3b、C-4（經驗球）；B-5d／e／f（基準改 `428f52a`，正常路徑預期不變）。
- 新增子項：B-6（走廊內入口 Controller 拒絕、放置類物品被拒、經驗球、肩上鸚鵡，以及選測的丟蛋與指令傳送）、C-5（選測，多人肩上鸚鵡）、D 段 M2-4 LOW 返還。
- F-DOC-B5 與 F-DOC-GUARD 的修正，見清單 2.2、2.5 (a)、B-5 預驗表與 E 段。

## Deferred triage 結論

- **M2**（R2）：TransferService 診斷（＝R2 M1）與正常到期 WARN（＝R2 M2）本輪已修；6 個 mixin annotation warnings 來自 testmod `SessionTransferFaultMixin`（`require=0`），不進 release，關閉；31 項 runtime noise 延後到下一次 fresh gate 分類；rollback 預期 ERROR、編碼分類、fullgate stdout 亂碼、`m2-corridor.md:3`／plan:411、T7 旋轉敏感度，關閉；T10 restore 死碼（＝R2 M8）延後 M5 清理。
- **M3-A／M3-B**（R3）：queue observer／registration-isolated close 的專屬 regression 接受風險，M5 動到 queue 時補；discardWorld、post-LOAD quarantine 措辭、過期 owner 路徑，關閉；Task 5 預期的 IOException ERROR stack，關閉。
- **M4**（R3）：round 1 quality M1／M2／M4／M5／M6 延後 M5（其中 M4＝hardening (1) codec strictness，須在 M5 schema 變更前）；spec m-1 關閉；fix round 1 q1–q3 併入 M5 hardening (3)；q4、q5、q7 延後 M5；q6、s3 關閉（跨 JVM probe 已覆蓋）；Nits 延後或關閉；M5 handoff 第 9 點依 F-CHECKPOINT 上調並已隨本輪退避修正更新。
- **R1**：harness tracked 化維持延後（擋刪除 worktree，不擋合併）；`task9-gates.ps1` 的 PID 過濾併入同一個 task。

## 延後清單

逐項一句話與建議修法。M5 前置 hardening 的優先序由使用者 2026-09-29 指定，已同步寫入 [M4 紀錄](2026-09-21-m4-candidate-doors.md)「M5 handoff acceptance」。

| 項目 | 問題 | 建議 |
| --- | --- | --- |
| M5 hardening (1) codec strictness | entropy root 沒檢 exact keys、decode 沒強制 ledger 上限、schema3 內層沿用 legacy 寬鬆欄位、Vanilla WorldKey 接受省略 namespace（M4 round 1 quality M4） | 視為 M5 schema migration 的前置，一次收緊並補 rejection fixtures |
| M5 hardening (2) checkpoint revision | `MeasuredWorldSaveCheckpoint` 看的是全伺服器 save-failure 計數；無關 chunk 反覆失敗時，MEASURED 清理只能靠退避重試 | 改為只看本交易相關 chunks 的 revision |
| M5 hardening (3) `abortUnchecked` | secondary-failure 路徑不是真正 fail-closed（M4 fix round 1 q1–q3） | abort 例外時明確隔離、為隔離 session 設計 checked RETURNING 收斂路徑，並限定只回退未 checked candidate |
| 新 WARN 缺 cause | `SessionRecoveryManager.java:104-106` 的「返還保留 pending」只記 class 與 message（round 3 首見 1/68） | 比照 R2 M1，首次失敗即記 cause；若重複出現，查 `SessionJournalStore` 在 Windows 的 `ATOMIC_MOVE` 是否被其他程序鎖住 |
| 騎乘載具遺失 | 騎乘中入場、在走廊斷線重登時，載具被 backstop 拒絕而遺失（修正前也會遺失） | M5 在 `start()` 預檢拒絕騎乘者，或先讓玩家下馬 |
| 指令成功訊息 | `/tp`、`/execute in … run tp`、`/spreadplayers`、`/summon` 被拒時原版仍顯示成功（`/tp` 回傳 1） | 外觀問題；若要修，需在指令層回報失敗 |
| 舊存檔既有 entity | 舊存檔 superposition 世界中既有、由 chunk 載入的非管理 entity 不經 `addEntity` | M5 需要時加一次性掃描或 chunk 載入 hook |
| checkpoint 預檢範圍 | 預檢只涵蓋目錄鏈；玩家 `.dat` 本身是 reparse／hardlink 或超過 64 MiB 時，仍在入場後才發現 | 評估在預檢時對既有玩家檔做 metadata 檢查（不讀寫內容） |
| GameTest 順序依賴 | `Batches.createBatches` 以 HashMap 分組不排序，目前約 84 個 batch key，超過 96 會 resize；`unhealthy_authorities_reject_before_reservation_windows` 要求 journal 全空，可能受影響 | 讓 `assertEmptyAuthorities` 只查自己的 chamber，或讓留下殘留的 test 自行收尾 |
| M4 test 收尾 | M4 test 失敗路徑不清 DORMANT receipt；native test 逾時不 close fixture；`native_returned_participant_moves_while_offline_member_pending_windows` 逾時會在 testmod 靜態 `OBSERVED` 表留一筆 | 失敗與逾時路徑一律收尾（沿用 `TestHooks`） |
| `TestHooks` 自我抑制 | 同一 Throwable 被兩個 hook 丟出時，`addSuppressed` 會丟 self-suppression 例外 | 以 identity 比對略過自身 |
| testmod mod name | testmod `fabric.mod.json` 名稱仍是「QuantumChamber M1–M2 原生驗證」（程式檔，本輪不改） | 下次動 testmod 時改為「M1–M4」 |
| mixin 設定 | `quantumchamber.mixins.json` 沒有 `defaultRequire: 1` | M5 hardening 時加上，讓 injector 失配直接失敗 |
| N-3 `recover()` 行為 | durable RETURNING 後仍把已離開原艙 interior 的未返還成員送回原艙返還位 | 行為變更牽涉 checkpoint 語意，M5 決定 |
| N-4 正常結束 log 分類 | 入場移動途中喝奶、remap 期間 Buff 失效、一般斷線或死亡仍記 WARN／ERROR＋stack | 比照 R2 M2 分流為 INFO |
| R2 M5 | `ChamberPowerCoordinator.java:145` 在 `current==ARMED` 時短路（M1.2 遺留），異常狀態可能卡在 11 | 修正前需確認 B-1a、B-3c、C-1b、C-2a 的重驗範圍 |
| R2 M6 | 返還落點不檢查室內裝飾的碰撞（`ChamberReturnPlacement.java:33-37`） | 返還前檢查落點碰撞；修正需重驗 A-2、B-1b、C-3a |
| R2 M7 | 全域 pin 上限 256 會讓所有 session fail，返還也受限（經驗球也計入，N-6） | 改為 per-session 預算或分級處理 |
| R2 M8 | 死碼：restore 分支、`QuantumEffectTransaction.commit` | M5 清理 |
| R3 m-3 | M2 的 RETURNING／retirement 路徑沒有原生存檔 checkpoint；crash 窗口內 pin 物品可能留在已釋放的 slot | M5 評估是否比照 MEASURED 補 checkpoint |
| R3 m-4 | RED XML 內的中文亂碼 | 可讀性問題；下次調整 harness 時統一編碼 |
| M4 round 1 quality M1／M2／M5／M6 | `authorityHistory` 無上限、idle tick 全量重掃、`sideDoorCells` 資訊流失、死碼與重複 | 見 [M4 紀錄](2026-09-21-m4-candidate-doors.md)「已知 deferred 項目」 |
| M4 fix round 1 q4、q5、q7 | 雙重故障的全域 recovery 暫停、`abandonInitial` log 缺 root cause、`selectionTeardown` 收尾 | 同上 |
| Nits | 各輪 review 的 Nit（例如 policy test 名稱過寬、`@Unique` 缺漏、`WARNED` 為 JVM 範圍） | 延後或關閉 |
| harness tracked 化 | `task9-gates.ps1`、`task9-main-oracle.ps1`、`run-m4-recovery-probe.ps1`、`task9-runtime.init.gradle`、`task9-probe.init.gradle`、`ifix2-ci-rule-check.ps1`／`ifix3-ci-rule-check.ps1` 等只在 gitignored evidence owner | 整理到 tracked `scripts/verification/`（獨立 task＋review）；這是刪除 worktree 前的必要條件 |

## 參考資料

- Evidence owner（gitignored，本機限定）`.superpowers/sdd/2026-09-21-m4-candidate-doors/`：
  - `m1m4-whole-branch-final-review.md`（整分支 final review 彙整）
  - `ifix1-report.md`（SHA-256 `a20a9a8df307d8a41e5a744cdf30f5083d7af8cbcaaa246d49a86c34f46a3ce0`）、`ifix2-report.md`（`045c92966fc1283e29229a8da23651b005daaead64e3f1bf5c7671b73c1e7d4e`）、`ifix3-report.md`（`3219c27bec2692bf57a38ffbe93f3e3e394a58909bf0fd212a80ff238f59b908`）
  - `ifix{1,2,3}-verification-summary.json`、`ifix{1,2,3}-evidence-sha256.json`、`ifix{1,2,3}-review.diff`
  - `progress.md`：SDD ledger、各 review 條目與使用者決定。
- [M4 候選門紀錄](2026-09-21-m4-candidate-doors.md)、[M2 操作與驗證](m2-corridor.md)、[M1–M4 人工驗收清單](../manual-acceptance/2026-09-24-m1-m4-manual-acceptance.md)。
