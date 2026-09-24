# M4：候選門（Candidate Doors）權威與驗證證據

驗證日期：2026-09-24。Minecraft 1.21／Yarn 1.21+build.9／Loader 0.17.2／Fabric API 0.102.0+1.21／Loom 1.7.4／Java 21／Gradle 8.8，Windows 本機。

## 狀態與範圍

M2 左右走廊的每扇完整側門，現在都有穩定的候選權威：page recycle、重啟與多人互動都不會改變它。第一個合法右鍵會以 checked `SELECTED` receipt 鎖定該門，session 同時進入 `MEASURED`。

M4 是權威與持久化里程碑：

- 門保持關閉，玩家不移動，沒有配置 Universe。
- 真正的塌縮／通道留給 M5。
- Whole-branch review 最終 Critical 0／Important 0（見「Final review」）。
- spec §15 的 M4 遊戲內人工驗收**尚未執行**。本文所有證據都來自自動 gate 與 headless／跨 JVM probe，不代表人工可見行為已驗。
- M1–M4 在 M1／M1.2／M2／M4 人工驗收記錄完成前不合併 main（除非另有明確記錄的 gate waiver）；實際合併與 tag 狀態以 `main`／tag 為準。

Commit 範圍：

- M4 code range：`ba9ca7338a855e85fa3e48161f6c7014a32634f7..752ada1b34f27685014fc3e6ec10fee77b88a86a`，共 17 commits。
- Spec／plan docs commits（`687cff95efbb2cc127083107b94f8be0f56408c4..ba9ca73`）：`fdac2311caeef058d52d1906a789a4a7cf505713` 設計、`ba9ca7338a855e85fa3e48161f6c7014a32634f7` 計畫。
- Task 10 docs commits：`eba763b840ef738b17955c8b92ab594a210bc0c7`（初版），以及其後修正 review Minor／Nit 的 docs fix commit。完整清單以 `git log -- docs/implementation-notes/2026-09-21-m4-candidate-doors.md` 為準。

| Commit | Task | 內容 |
| --- | --- | --- |
| `2e4b279` | 1 | `DoorKey` 改為 signed logical station＋牆面 |
| `5f7ba21` | 2 | save-level entropy 與 domain-separated HMAC |
| `c9fedaa` | 3 | discovery authority、policy snapshot、resolver |
| `8389262`、`f088a26` | 4＋fix1 | session journal schema 3 strict codec |
| `1b8d9d4`、`b872429` | 5＋fix1 | 單調 ledger authority 與 selection CAS（current-vs-flushed exact） |
| `3d093b5`、`ddee492` | 6＋fix1 | session 建立時凍結 context；commit-before-expose；空 schema3 envelope 的 sticky 證據 |
| `b59345d` | 7 | Bulkhead 互動、first-wins、`MEASURED` freeze |
| `74bc1e1`、`06c9981` | 8＋fix1 | `MEASURED` 保留式 recovery；native chunk／entity save 失敗閘門 |
| `8917f84`、`38d901e` | 9＋fix1 | M4 GameTest matrix、main-only／artifact oracle；per-test Universe receipts |
| `4f031ad` | whole-branch fix1 | W-I1：checked 提交失敗後回滾未 checked authority；root cause log |
| `c3cc053` | whole-branch fix1 | W-I2：DORMANT receipt 只封鎖自己的 Chamber |
| `752ada1` | whole-branch fix1 | 跨 JVM 證明未 checked 的選擇不會跨重啟存活；dormant-reentry |

下文的 `main/` 代表 `src/main/java/dev/quantumchamber/`。行號以 `752ada1` 為準。

原始證據放在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/`（下稱 evidence owner）。`.superpowers/` 被 gitignore，是本機 scratch，不在 repo 內，也不進 release。因此本文直接寫出關鍵數字與 SHA-256；本文不能取代原始證據，交接時要以 exact commit 對照這些雜湊。

**可重現性限制**：驅動下列 runtime gate 的 harness 只存在於 evidence owner，不在 repo。包括 `task9-gates.ps1`、`task9-main-oracle.ps1`、`run-m4-recovery-probe.ps1`、wrapper `wfix1-final-green.ps1`／`wfix1-final-green2.ps1`，以及 `wfix1-build-summary.ps1`、`task10-phase2-verify.ps1` 等 build-summary／verify 腳本。Repo 內 tracked 的只有它們呼叫的 `build.gradle` run 設定（`gameTestLegacy`、`m3Universe`、`m3Transfer`、`m4Recovery`）與 `src/testmod` 的 GameTest／probe。可重用 harness 要整理到 tracked `scripts/verification/`，這是使用者已決定的獨立 task。完成之前，只要刪除 worktree 或 `.superpowers/`，這些 gate 就無法用同一套 harness 重跑，本文的 SHA-256 也無法再對照原始檔。

## Stable DoorKey 與完整門

- `DoorKey(UUID sessionUuid, long logicalStationIndex, DoorWallSide wallSide)`（`main/corridor/DoorKey.java:6-12`）：
  - `DoorWallSide` 為 `NEGATIVE_LATERAL`（local x=0）或 `POSITIVE_LATERAL`（x=6）。
  - 不含 physical position、page、slot、mapping epoch、anchor 或 facing。
- `logicalStationIndex = Math.floorDiv(blockLogicalZ, 8)`，保留 signed `long`（`DoorKey.java:14-16`）。
- 25 格 normalize：同一 station 內 `floorMod(z,8)∈[1,5]`、`y∈[1,5]`、x 為 0 或 6 的 Bulkhead 格，都對應到同一個 key（`main/corridor/CorridorGeometry.java:60-84`）。`sideDoorCells`（`CorridorGeometry.java:86-96`）回傳 25 個元素，實際上是 5 個 `LogicalAddress`（z offset 1..5）各重複 5 次，不含 y 與牆面資訊；production 沒有使用，見 deferred 清單的 quality M5＝spec m-2。
- 完整門判定（`CorridorPageManager.completeDoor`，`main/corridor/CorridorPageManager.java:372-390`）：
  - 只看目前 committed mapping 的 25 格實體 Bulkhead。
  - 與入口／返還 replica 實際重合的 exact 25 格會被排除，不會排除整個 station（`:376-382`）。
- 「定位」與「可選」是分開的：`candidateDoor`（`:252-267`）對未完成、pending、retired 與 `MEASURED` 的側門仍保有攔截權，不會落回一般開關門流程。

## Candidate entropy 與 HMAC

- 檔案：`<save>/data/quantumchamber_candidate_entropy.dat`（`main/candidate/CandidateEntropyState.java:25`）。
  - 內容是 compressed NBT：`SchemaVersion=1` 與恰好 32 bytes 的 `CandidateEntropy`。
  - 讀取時要求一般檔案（NOFOLLOW），NBT 上限 4096 bytes（`:64-76`）。
- 什麼時候會產生：只有沒有 M4 證據時，才用 `SecureRandom` 產生（`:32-33`）。
- 寫入流程：同目錄暫存檔 → `force` → `ATOMIC_MOVE` → strict 讀回，再以 `MessageDigest.isEqual` 比對（`:35-39`、`:78-91`）。
- Fail closed：
  - 已有 M4 證據但檔案缺失 → 丟出 `UnhealthyEntropyException`（`:30`）。
  - 損壞、型別錯誤或長度不符 → 同一例外，只帶固定訊息，不含 payload 或 cause（`:44-49`）。
- 兩個呼叫端：
  - session 建立時，`SuperpositionSession.freezeCandidateContext` 以「`candidateInitialized` 或 discovery 非空」作為 evidence 旗標（`main/superposition/SuperpositionSession.java:31-34`）。
  - 每個 batch 時，`CandidateLedgerService` 固定傳 `true`，所以只讀、永不重建（`main/candidate/CandidateLedgerService.java:50-53`）。
- Session 只保存 `SHA-256(entropy)` fingerprint（`CandidateEntropyState.java:52-58`），`toString` 已遮蔽（`:93`）。Resolver 會拒絕 fingerprint 不符的 policy（`main/candidate/CandidateResolver.java:23-25`）。
- HMAC-SHA256 的 canonical big-endian 輸入依序為：`int32 tag 長度`、`UTF-8 tag`、`UUID MSB`、`UUID LSB`、`long station`、`side byte（NEGATIVE=0／POSITIVE=1）`、`int32 candidateSchemaVersion`（`main/candidate/CandidateDerivation.java:39-65`）。
- Domain tags 是固定字串，不從 enum name 推導（`main/candidate/DerivationDomain.java:5-9`）：
  - `quantumchamber:candidate-id:v1`
  - `quantumchamber:bucket:v1`
  - `quantumchamber:existing-index:v1`
  - `quantumchamber:allocation-token:v1`
  - `quantumchamber:generation-seed-material:v1`
- Bucket 是完整 256-bit unsigned digest 對 100 取餘數。Existing index 使用獨立的 `EXISTING_INDEX` digest，對 pool 大小取餘數（`CandidateDerivation.java:25-37`）。這是明確的 unsigned remainder，不宣稱無偏的 rejection sampling。

## Discovery authority 與 policy snapshot

- 檔案：`<save>/data/quantumchamber_universe_discovery.dat`（`main/candidate/UniverseDiscoveryState.java:186-189`）。規格沒有指定檔名，這是 plan ruling。
- 格式：schema 1，root 欄位必須恰為 `{SchemaVersion, Records}`。Record 欄位為 `UniverseId`、`DiscoveryOrdinal`、`DiscoveredAtGameTime`、`GameplayEligible`，外加選用的 `FirstObserver`（`:28-32`、`:118-148`）。
- 驗證規則：
  - ordinal 與 game time 不可為負（`main/candidate/UniverseDiscoveryRecord.java:19-24`）。
  - 檔內 ordinal 不可倒退（`UniverseDiscoveryState.java:140-142`）。
  - UniverseId 不可重複（`:150-159`）。
- Canonical 排序：先比 ordinal，再以 unsigned 方式比較 UUID 的 MSB、LSB（`UniverseDiscoveryRecord.java:12-17`）。
- Watermark：空 state 時為 -1，否則為最後一筆的 ordinal（`UniverseDiscoveryState.java:91`）。
- Eligible pool：`gameplayEligible && ordinal ≤ watermark && id ≠ Catalog source`（`:93-99`）。
- 寫入：暫存檔 → force → 先驗暫存檔 → atomic move → 再驗正式檔（`:67-86`）。NBT 讀取上限 16 MiB（`:173`）。
- M4 沒有正式的 discovery mutation：`fromRecords` 是 package-private，只給 fixture 用（`:39-42`）；也不會從 `UniverseRegistry` 自動匯入。
- 啟動順序：
  - session 建立時先 strict 讀 discovery。已有 schema3 證據時只 `load`，全新 save 才會 `loadOrCreate`。之後才處理 entropy。
  - 只要 journal 還有 legacy record，就拒絕建立 M4 session（`SuperpositionSession.java:26-38`）。
- `CandidatePolicySnapshot` 只接受以下 exact 值（`main/candidate/CandidatePolicySnapshot.java:10-19`）：
  - `quantumchamber:m4_v1`、policy v1、candidate schema 1
  - 權重 5/20/75
  - watermark ≥ -1
  - cap 16384
- 來源固定為 `SourceFamilyRef.Vanilla(worldKey, role)`（`SuperpositionSession.java:36-37`）。`CATALOG` variant 只有 strict codec，沒有放寬動態 Origin 啟動（`main/candidate/SourceFamilyRef.java:9-29`）。
- `start()` 的順序（`main/superposition/SuperpositionSessionManager.java`）：
  1. 跨 session 玩家預檢（`:101-102`）。
  2. 凍結 context；任一 authority 不健康就回 `REJECTED`，不建立 session 或空間（`:104-106`）。
  3. `reserveInitial`（`:110-111`）。
  4. 以 `candidateAware` 建立 ARMING＋SELECTABLE record，checked 落盤（`:115-125`）。

## Candidate weights 與 fallback

`CandidateResolver.resolve`（`CandidateResolver.java:19-46`）：

| Bucket | 結果 |
| --- | --- |
| 0..4 | `SOURCE(candidateId)`，不帶 UniverseId |
| 5..24，且 eligible pool 非空 | `EXISTING(candidateId, pool[existingIndex])` |
| 5..24 但 pool 為空，或 25..99 | `NEW(candidateId, NewUniverseIntent)` |

- `NewUniverseIntent` 的內容（`main/candidate/NewUniverseIntent.java:7-16`）：
  - `ALLOCATION_TOKEN` 32 bytes
  - `VANILLA_OVERWORLD_SHARED_SEED_V1`，profile version 1
  - `GENERATION_SEED_MATERIAL` 32 bytes
- 空 pool 時一律回退 `NEW`，不回流 `SOURCE`。
- Resolver 不呼叫任何 Universe allocation 或 materialization API。

## Session journal schema 3

**Envelope**（`quantumchamber_sessions.dat`，`main/persistence/SessionRecoveryState.java:76-96`）：

- `SchemaVersion` 可為 1、2 或 3；schema 3 的 root 必須恰為 `{SchemaVersion, Records}`。
- 讀到 schema 3 後，`candidateInitialized` 變成 sticky（`:83`、`:171`），之後不接受 legacy record（`:156-158`）。
- 寫入規則（`:192`、`:261-268`）：
  - 已初始化 → 固定寫 schema 3。
  - 否則全部為 legacy → 寫 schema 2。
  - legacy 與 candidate 混裝 → fail closed。

**Record**（`main/persistence/SessionRecoveryRecord.java`）：

- schema 3 必須恰有 11 個 key：原本 8 個，加上 `CandidateContext`、`CandidateLedger`、`CandidateSelection`（`:216-219`）。
- schema 1／2 若攜帶任何候選欄位，整份拒絕（`:220-222`）。
- Ledger 必須依 station、wall side 嚴格遞增（`:253-260`）。
- schema 3 的空 list 必須使用 END held type（`:288-294`）。
- 候選 payload codec 採 exact key set，涵蓋 context、SourceFamilyRef 兩種 variant、SOURCE／EXISTING／NEW、SELECTABLE／SELECTED；unknown variant 或欄位一律拒絕（`main/persistence/CandidateJournalCodec.java:20-140`）。所有 32-byte 欄位都經 `CandidateBytes` 驗長度（`main/candidate/CandidateBytes.java:11-15`）。

**合法狀態矩陣**（`SessionRecoveryRecord.java:85-111`）：

| 類型 | 合法組合 | 其他限制 |
| --- | --- | --- |
| legacy（schema 1／2） | ARMING／SUPERPOSITION／RETURNING；context、ledger、selection 皆空 | 不可為 `MEASURED` |
| candidate-aware | ARMING／SUPERPOSITION／RETURNING + `SELECTABLE` | 必有 context 與 selection |
| candidate-aware | `MEASURED` + `SELECTED` | `SELECTED` 必須 exact 對應同 session ledger entry 的 DoorKey 與 CandidateId |
| 任何 | 空 `SpaceLeases` | 只允許全員已返還的 candidate-aware `MEASURED`（即 DORMANT） |

Ledger 內的 DoorKey 與 CandidateId 必須唯一，且都屬於同一個 session（`:94-100`）。

**單調 authority**（`SessionRecoveryRecord.sameAuthority`，`:133-146`）：

- context 不可變，next ledger 必須是 previous 的 superset。
- 一旦 previous 是 `MEASURED`：ledger 必須完全相同、返還旗標只能單調增加、leases 只能保持原樣或清空。
- Selection 只允許從 `SELECTABLE` 轉出；已經 `SELECTED` 就必須保持完全相同。

**狀態機的檢查點**：

- `put`（`SessionRecoveryState.java:152-173`）同時對 current、上一份 flushed 與本 process 的 `authorityHistory` 檢查上述條件，所以 remove 再 reinsert 同一個 UUID 也繞不過。隔離（quarantine）中的 session 一律拒絕（`:155`）。
- `remove`（`:174-183`）一律拒絕 `MEASURED`。
- `save`（`:208-231`）：write → 正式檔 readback → strict decode，NBT 與 records 都要 exact 相等，才更新 `flushed` 與 `checkedAuthority`。

**Schema 1／2 相容策略**：

- legacy 仍然 strict 讀取，只走既有的 return-only recovery，不推測或補造 candidate。
- 已有 legacy durable record 時，不建立新的 M4 session。
- 因為 schema 3 marker 在同一 JVM 內是 sticky，GameTest 以隔離的 `runGameTestLegacy`（`run/gametest-legacy`）保留一個真 live schema 2 案例。

## Batch、cap 與 commit-before-expose

1. `CorridorPageManager.tick` 每 tick 都會對 `operations==0`、不是 measured／releasing／failed 的 space 呼叫 `refreshCandidates`（`:144-152`）：
   - 沒有 build、overlay、retiring 工作時，直接呼叫（`CorridorPageManager.java:153-154`）。
   - 有工作時，若 durable 或 staged 為 RETURNING（或 staged 缺失）就在 `:158` 跳過本 tick；否則在該 tick 的建造、overlay 與 retirement 之後呼叫（`:182`）。
   - 呼叫本身不代表會提交：`refreshCandidates` 先經 `publishable`（`:394`、`:357-364`），其中 `idleMapping`（`:366-370`）要求 mapping idle，也就是沒有 pending、batch、builds、overlay、retiredViews、retiring，且 epoch 不為 0、instances 非空。不 idle 就直接返回，不提交也不發布。
2. `refreshCandidates`（`:392-419`）：
   - 先清空 selectable index，再確認 `publishable`。
   - 從 current mapping 收集完整門。同一個 DoorKey 若有兩個 current physical owner，直接 fail（`:396-403`）。
   - 取 ledger 尚未有的 key，**每個 session 每 tick 最多 256 個**（`:405-408`）。
   - 在 session operation guard 內呼叫 `commitCandidates`（`:409`）。回報 `sessionFailed` 時丟出例外，交給 `fail(space)` 安全返還（`:410`、`:1006-1027`）。
3. `commitCandidates`（`CandidateLedgerService.java:68-98`）：
   - 前置條件：flushed record 必須是 SUPERPOSITION＋SELECTABLE。
   - 輸入先去重並依 canonical 順序排序，已存在的 key 直接沿用。
   - 缺少的 key 最多 256 個（`MAX_NEW_ENTRIES`，`:24`、`:82`）。
   - 總數超過 cap 16384 時整批失敗，不刪舊候選也不重抽（`:85`）。
   - 每個 batch 只經 `commitChecked` flush 一次（`:141-159`）：`sameAuthority` → current 必須 exact 等於 expected flushed（`:170-174`）→ `put` → `flush` → 從 `flushedRecords()` exact readback。
4. 只有 readback 成功後，才以 **flushed ledger** 建立 physical selectable projection（`CorridorPageManager.java:412-418`）。
   - `publishable`（`:357-364`）要求：durable 為 SUPERPOSITION＋SELECTABLE、current 與 flushed exact 相等、mapping idle（`idleMapping`，`:366-370`）、lease 已就緒。提交後會再檢查一次（`:412`）。
   - Physical index 可以重建，本身不是持久權威。Page recycle 只清 index，不刪 ledger。

## Checked 提交失敗的收斂契約（W-I1）

Production 共有 11 處 `journal.put`（以 `git grep -n "journal.put" 752ada1 -- src/main` 核對）。其中只有兩處會建立或改動 candidate context、ledger 或 selection，而且都在同一呼叫內 put 後立刻 flush：

- `commitChecked`（`CandidateLedgerService.java:145`，方法範圍 `:141-159`）：ledger batch 與 selection 唯一的提交點。
- `start()` 的初始 ARMING（`SuperpositionSessionManager.java:120`）：以 `candidateAware` 建立新 record。

其餘 9 處都經 `withProgress`（`SessionRecoveryRecord.java:115-119`），只更新 participants、leases、state 與 restore flag，candidate 三欄位原樣保留：

- `SuperpositionSessionManager.java:274`、`:350`
- `CorridorPageManager.java:503`、`:857`、`:885`、`:1021`
- `CorridorRepositionService.java:36`
- `SessionRecoveryManager.java:158`、`:201`

其中 `CorridorPageManager.java:857` put 的 `expected` 是在 `:840` 由 `withProgress` 產生；`SessionRecoveryManager.java:158` put 的 record 來自 `returnedRecord`（`:210-218`），內部同樣呼叫 `withProgress`。

一旦 put 之後任何一步（flush、readback）失敗：

1. **CAS 還原**：`SessionRecoveryState.abortUnchecked(next)`（`SessionRecoveryState.java:122-145`）。
   - 只有 current 仍 exact 等於本次的 `next` 時才生效：current 還原為上一份 flushed record（沒有就移除），`authorityHistory` 還原為 `checkedAuthority`（最後一份經 strict load／readback 承認的 authority），並 `markDirty`，讓下一次寫出覆蓋磁碟上未確認的內容（`:139-144`）。
   - 若 `next` 已經等於 flushed（已 checked），就不倒退。
2. **Quarantine**：CAS 不符、而 current 仍含未 checked authority 時，不猜測覆寫，改為隔離該 session（`:133-137`）。
   - 隔離 session 的 `put`／`remove` 一律拒絕（`:155`、`:177`、`:243-245`）。
   - 所有寫出（checked flush、原生 autosave、stop）都經 `writable()`；隔離 session 只寫上一份 flushed authority，其他 session 照常（`:196-205`、`:214`）。
   - Reviewer 已核實 production 中 CAS 必定成立，此路徑不可達，只是防禦。
3. **`COMMIT_FAILED`**：失敗記錄 root cause log（只含例外 class／message，不含 record、candidate bytes 或 entropy；`CandidateLedgerService.java:150-157`、`:161-167`）後丟出內部 `CommitFailure`。
   - `trySelect` 回 `SelectionOutcome.COMMIT_FAILED`（`:31`、`:118-119`）；batch 回 `sessionFailed`（`:92-93`）。
   - `COMMIT_FAILED` 是 ruling 核准的、對 plan Task 5 四值介面的擴充。對玩家而言與 REJECTED 相同：FAIL、沒有訊息。
4. **`failCandidateSession`**：互動 owner 在 guard 釋放後呼叫（`main/candidate/CandidateDoorInteraction.java:46`、`:55-62`）→ `CorridorPageManager.failCandidateSession`（`CorridorPageManager.java:289-297`）→ `fail(space)`（`:1006-1027`）。
   - flushed 為 SUPERPOSITION＋SELECTABLE → 寫 checked RETURNING。
   - flushed 已是 checked `MEASURED` → 只請求保留式 recovery（`:1011-1014`）。
   - Batch 失敗走既有的 `refreshCandidates` → `fail(space)`。因為已經還原，`recoveryWritesSafe()` 為 true，RETURNING 寫得出去（`:1016`）。
5. **`abandonInitial`**：`start()` 的初始 ARMING flush 失敗時（`SuperpositionSessionManager.java:120-125`、`:133-148`）：
   - 以 CAS 移除未 checked 的 record，`cancelPrepared`＋`retire` 取消零 geometry 的 reservation，移除 runtime，回 `REJECTED`。
   - 結果：玩家未移動，沒有 journal、slot、page 或 ticket 殘留。
   - 任一清理步驟失敗，才退回既有的 `fail(runtime)`。
6. **Recovery guard 縮小**：`SessionRecoveryManager.tick` 只在 `uncheckedCandidateSessions()`（排除隔離 session）非空時暫停，並在暫停與恢復時各記一次 log（`main/persistence/SessionRecoveryManager.java:67-75`；`SessionRecoveryState.java:104-121`）。
   - 由於上面的收斂，非隔離 session 的未 checked authority 只存在於 `commitChecked`／`start()` 同一呼叫內的 put→flush 瞬間，tick 觀察不到。
   - 雙重故障的例外見 deferred q4。

**Readback 失敗但磁碟可能已有 next 的窗口**：

| 窗口 | 同 process 結果 | 重啟結果 |
| --- | --- | --- |
| W-a：`save()` 已原子寫入 next，strict readback 失敗 | CAS 還原為 flushed SELECTABLE，接著以 flushed authority 寫 checked RETURNING＋SELECTABLE，覆寫磁碟上未確認的 MEASURED | 還原後、寫 RETURNING 前 crash：磁碟是 next（MEASURED＋SELECTED）時，依 §11 走保留式返還並保留 receipt；是 previous 時，SELECTABLE 安全返還；損壞時 fail closed |
| W-b：flush 已 checked（flushed＝next），之後才回報失敗 | 無可還原；標記 failure 後請求保留式 recovery，玩家只看到 FAIL | 與同 process 相同 |

W-a 的 crash 分支經 ruling 接受，不視為 spec 偏離：§11 依 durable 狀態分列，strict load 就等於 checked readback；§10 只要求成功之前不發 actionbar。

## Selection CAS、first-wins 與互動

**入口**：

- `QuantumBulkheadBlock.onUse` 在 server 端先交給 `CandidateDoorInteraction`（`main/chamber/QuantumBulkheadBlock.java:49-52`）。Client 只回 `SUCCESS` 預測，不持有權威（`:46-48`）。
- `CandidateDoorInteraction.onBulkheadUse`（`CandidateDoorInteraction.java:19-52`）：
  - 非 Superposition world 回 empty；非 server thread 回 FAIL。
  - 定位到走廊側門後取得 `exclusiveOperation` guard（`CorridorPageManager.java:277-283`），並在 guard 內重新定位（`CandidateDoorInteraction.java:30-32`）。
- `candidateSelectionRecord`（`CorridorPageManager.java:299-318`）核對：
  - guard、idle mapping、完整門、lease ready。
  - durable 為 SUPERPOSITION 或 `MEASURED`、帶有 candidate context，且與 current exact 相等（`:307-308`）。
  - 玩家屬於凍結 participant（`:309`），而且 record 內**沒有任何** participant 已返還（`:310`）；不是只看該玩家自己是否返還。
  - DoorKey 已在 ledger 中（`:311`）；玩家在線、存活、非 spectator、在正確 world（`:313-314`）。
  - bbox 只落在唯一一個 current mapping。
  - cohort Buff 有效；SUPERPOSITION 時 physical owner 必須等於 selectable index。
- 之後再由 `candidateSourceAuthorized` 核對 runtime 與來源 Controller（`SuperpositionSessionManager.java:77-84`）。

**CAS**：`CandidateLedgerService.trySelect`（`CandidateLedgerService.java:99-124`）：

- flushed record 已是 `SELECTED` → `ALREADY_SELECTED`（`:108-110`）。
- 否則必須是 SUPERPOSITION，且 DoorKey 在 flushed ledger 中。
- 建立 `Selected(doorKey, candidateId, selectedBy, selectedAtGameTime, revision=1)`，並在同一次 `commitChecked` 內一起寫入 `MEASURED`（`:114-117`）。
- 提交失敗 → `COMMIT_FAILED`；其他例外 → `REJECTED`（`:118-123`）。

**First-wins**：互動全部在 server thread 上依序執行。第一個成功的 checked readback 之後，第二個互動讀到的 flushed record 已經是 `SELECTED`，只能得到 `ALREADY_SELECTED`。Current-vs-flushed exact 檢查（`:170-174`）會阻止 CAS 覆寫別人的 dirty 進度。

**回饋**：

- 只有 `SELECTED` 才會觸發 `candidateMeasured`（`SuperpositionSessionManager.java:86-92`）、送出 actionbar「量子候選已鎖定，等待塌縮」並回傳 `SUCCESS`（`CandidateDoorInteraction.java:37`、`:40-43`）。
- `ALREADY_SELECTED` 只顯示「候選已鎖定。」；其他情況回 FAIL，沒有成功訊息（`:44-47`）。
- 注意：如果 `candidateMeasured` 在 checked `SELECTED` 之後才丟例外，互動會以 FAIL 結束（`:48-50`），但 durable receipt 已經成立。

**互動不寫任何方塊**：25 格 Bulkhead 維持 `OPEN=false`，玩家與走廊都不移動。Comparator 仍是 `INVALID=0／IDLE=3／READY=7／ARMED=11`（`main/chamber/ChamberStatusSignal.java:7-13`，M4 未修改），不會輸出 15。

## MEASURED freeze 與 retained recovery

**Freeze**：

- 已測量的 space 在 tick 中只走 measured release（`CorridorPageManager.java:147-150`、`:320-323`）。
- remap 路徑對已測量 space 一律拒絕：`prepareRemap`、`commitRemap`、`cancelPrepared`、`retire`（`:494`、`:567`、`:588`、`:614`）。`beginRemap` 要求 durable SUPERPOSITION（`:542`）。
- `CorridorRepositionService` 只處理 SUPERPOSITION（`main/corridor/CorridorRepositionService.java:30`）；`selectableDoors`／`publishable` 對 `MEASURED` 回空。
- 既有 lease、ticket、cohort 與保護都保留，等待 M5。

**觸發 recovery**：以下情況都會呼叫 `requestMeasuredRecovery`，這個請求只改 runtime（`CorridorPageManager.java:216-225`），不會寫入 `RETURNING`：

- 來源權威或 cohort Buff 失效（`SuperpositionSessionManager.java:158-164`）。
- LOW：`returnToOrigin` 對 `MEASURED` 只請求 recovery 並回 `false`（`:299-303`）。
- 斷線（`SessionRecoveryManager.java:197-198`）。
- 空間 fault（`CorridorPageManager.java:1011-1014`）。

重啟時，`attach` 會依 durable `MEASURED` 自動標記 requested（`:89-93`）。

**Phase 推導**（`main/persistence/MeasuredRecoveryPhase.java:7-13`）：

| Phase | 條件 |
| --- | --- |
| `RETURN_PLAYERS` | 仍有人未返還 |
| `RELEASE_GEOMETRY` | 全員已返還，但仍有 lease |
| `DORMANT` | lease 為空 |

`RETAIN_RECEIPT` 雖是 enum 值，`from()` 不會回傳它（程式碼註解定義它為交易內的 checked 寫入）。

**專用 release 路徑**：

1. `SessionRecoveryManager.recover` 逐一返還玩家，每位都做 checked `put`／`flush`（`SessionRecoveryManager.java:134-159`）。
2. 全員返還後：`MEASURED` 呼叫 `releaseMeasured`，一般 session 才呼叫 `release`（`:172-175`）。
3. `tickMeasuredRelease`（`CorridorPageManager.java:831-862`）：
   - 清除幾何，並逐格確認已全部為 AIR（`:844-850`）。
   - `MeasuredWorldSaveCheckpoint.save` 強制存檔來源 Chamber 與走廊的相關 chunks。它要求原生 chunk／entity IO barrier 完成、save-failure revision 不變；失敗時重新標髒並保留 lease（`main/persistence/MeasuredWorldSaveCheckpoint.java:22-44`）。
   - 接著寫入「leases 為空、仍是 `MEASURED`」的 record，並 exact readback（`CorridorPageManager.java:854-858`），最後才釋放 ticket 與 slot。
   - 過程中不會經過一般 `tickRelease` 裡的 `journal.remove`（`:814-830`）。
4. 結果是 dormant record：候選 context、ledger 與 selection receipt 全部保留。DORMANT 在 attach 時不重建 space 或來源票（`SuperpositionSessionManager.java:60-63`、`CorridorPageManager.java:90`）。

## DORMANT 只封鎖自己的 Chamber（W-I2）

- DORMANT 的定義是 `MEASURED`＋`SELECTED`、全員 returned、沒有 lease（`SessionRecoveryState.java:246-250`）。
- `validateOwnership`（`:251-260`）的處理：
  - DORMANT 不計入跨 session 的玩家唯一性。
  - Chamber 唯一性照舊，所以 DORMANT 仍封鎖自己的 Chamber。
  - DORMANT 沒有 lease，slot 規則不受影響。
- `participantsAvailable`（`:146-151`）對 current ∪ flushed 做預檢，只略過 DORMANT；尚未 checked 移除的 flushed record 仍佔用玩家。
- `start()` 在凍結 authority 與任何 reservation 之前預檢，失敗直接 `REJECTED`、零殘留（`SuperpositionSessionManager.java:101-102`）。
- 使用者可見的後果：
  - 該座原艙的 `presence` 會回 `UNKNOWN` 或 `ACTIVE`，在該 save 內無法再開新 session（`SuperpositionSessionManager.java:65-75`、`:96`）。
  - LOW 不能讓它進入 OFF（`main/chamber/ChamberPowerCoordinator.java:116-133` 搭配 `returnToOrigin=false`），所以無法走一般 OFF 拆除流程。
  - 這是 spec §11「封鎖該 Chamber 等待 M5 接管」的刻意行為；M5 之前沒有遊戲內解除路徑。
  - 參與者要等該 session 進入 DORMANT（全員返還、幾何清理完成、lease 清空）之後，才可以使用**其他** Chamber。在 `RETURN_PLAYERS`／`RELEASE_GEOMETRY` 階段，該 record 還不是 DORMANT，`participantsAvailable` 會拒絕這些參與者開新 session。
- `SessionRecoveryManager.blocks`（`SessionRecoveryManager.java:41-50`）由網路 handler mixin 呼叫，用來擋下移動、載具移動與方塊互動封包。它在以下情況回 true：
  - 玩家在 `joinTicks`（JOIN 排隊中）或 `disconnected`（斷線 pending）中（`:45`）。
  - current ∪ flushed 中，玩家屬於 `RETURNING` session（`:46-48`）。
  - 玩家屬於 `MEASURED` session 且自己尚未返還（`:48`）。
  - 讀取 journal 時丟出例外，一律攔截（`:49`）。
- 在 `MEASURED` 的語境下，`blocks` 只攔截尚未返還的參與者。DORMANT 全員已返還，所以 DORMANT record 本身不會攔截任何人；前提是該玩家沒有落入其他條件，例如另一個 RETURNING session 或 JOIN／斷線 pending。

## 自動與跨 JVM 證據

GameTest、recovery probe 與 M3 probe 都是 **testmod-present** runtime，不是 main-only 證據：

- GameTest 的 per-test／suite boundary receipt 與 M4 recovery receipt 都記錄 `runtimeScope=testmod-present`（`M4PerTestUniverseProbe`、`M4GameTestBoundaryProbe`、`M4CandidateRecoveryProbe`）。
- M3 probe（`M3UniverseRuntimeProbe`、`M3UniverseTransferProbe`）位於 `src/testmod`，同屬 testmod-present runtime，但它們的 receipt **沒有** `runtimeScope` 欄位。

Main-only 證據另見下文。

### Final gates（HEAD `752ada1`）

- 所有 gate 都在同一 HEAD 以 fresh root 執行。
- Wrapper console `wfix1-final-green-console.log`（SHA-256 `803c194f4e1157107ebdb3e4584984beea12c390146b93964e6d2e5771c74fcb`）與 `wfix1-final-green2-console.log`（`0661e12d4b68eeacf0ad76388ec5375778d75f3a941f21edd2f521373cbb4c56`）的 START 行都是 `HEAD=752ada1b34f27685014fc3e6ec10fee77b88a86a`。
- **工作樹不是完全乾淨**：兩份 console 的 STATUS 行都顯示，當時有未提交的 `README.md` 與 `docs/implementation-notes/m2-corridor.md` 修改，也就是 Task 10 phase 1 的文件草稿。
  - STATUS 行用的是 `git status --porcelain --untracked-files=no`，不會列出 untracked 檔。當時未追蹤的文件草稿沒有被記錄，例如本 note、`AGENTS.md` 與 handoffs，它們在 `eba763b` 才加入。
  - 以上都是 `.md` 純文件。Release 與 sources JAR 都不含任何 `.md` entry（已逐一列出 `752ada1` full root 兩個 JAR 的 entries 確認），所以不影響 artifact 或 runtime 結果。
- GameTest 與 main-only 都經 `task9-gates.ps1` 的可恢復 move／exact restore，原 world 以逐檔 hash 還原。這支 harness 只在 evidence owner 內，見前文「可重現性限制」。
- Task 10 phase 2 另以唯讀腳本 `task10-phase2-verify.ps1`（同樣只在 evidence owner 內）重新讀原始 XML、JUnit、JAR、receipt、restore 與 oracle，確認下表全部數字。結果 PASS，輸出 `task10-phase2-verify.json`，SHA-256 `4d5bf32c15db3bf6580888b66612c6bfe8aad0a8e5fe24a3e8fb8942177e14eb`。

| Gate | Root（evidence owner 內） | 結果 |
| --- | --- | --- |
| `clean test runGameTest build --rerun-tasks` | `task9-wfix1-final-full-e4da3f9ff7c1470fa333402298259b25` | exit 0；13:29–13:33（+08:00）；server PID 25296 |
| `runGameTestLegacy --rerun-tasks` | `task9-wfix1-final-legacy-e3265fd65ba04c85a48ad6a76f04c25f` | exit 0；server PID 17104 |
| main-only `runServer`＋oracle | `task9-wfix1-final-main-only-452950f306d240f88ab9b733faa436aa` | PASS；server PID 22040 |
| M3 lifecycle 4＋transfer 5 | `task9-wfix1-final-m3b-211cd7cd1a654c69aa9b2555ce904016` | 9/9 PASS |
| M4 recovery 24 phases | `recovery-run-61c7e7596ceb4da68b831331c3a06112.json` | 24/24 PASS |

- **GameTest**：
  - default 135/135，沒有 failure、error 或 skip，XML SHA-256 `b23467a5ebc82c75de82597e5602feea80a4c38fc203f4e56b3b7115492c0925`。比原本多 5 個，都是 whole-branch fix1 新增的。
  - legacy 1/1，XML SHA-256 `865e2c0a4e01adea0f8e315647e5dcc3432caed9da210d7d70491da84c9ce267`。legacy test 沒有出現在 default run。
- **JUnit**：53 classes、394 tests、0 failures、0 errors、1 skipped。
  - 唯一的 skip 是既有的 `NonWindowsPlayerCheckpointStoreTest.unsupportedPlatformRejectsBeforeNativeInitializationOrFileAccess()`，屬於 Windows 上的平台 skip。
  - Windows checkpoint 三個 class（2＋2＋10）都實際執行，0 skip。
- **Suite boundary receipt**（依 server PID 過濾）：full `m4-boundary-d6a8815d8d2a4433ac4f6c6e733293d4.json` PASS；legacy `m4-boundary-dc3f583746a44566b77dbe099c6f1741.json` PASS。
- **World restore**：全部是 `exactRestored=true`、`deleted=false`。還原的原 world 路徑分別是：full `run/gametest/world`、legacy `run/gametest-legacy/world`、main-only `run/server`。
- **Main-only**（Loom `runServer`，不含 testmod source set），三層 oracle 各自通過：
  - Fabric loaded mods 不含 `quantumchamber-testmod`。
  - runtime classpath／argfile（`process-22040-*`）命中 0。
  - 該 PID 的 class-load 中，42 個 testmod-only types 命中 0（class log SHA-256 `2d50282bc594bb8e875e7f1e5d5473ade841e8d489b1c084c7f00049d71b5f5a`）。
  - 另外：stale datapack warning 0、shutdown world keys exact 四鍵、catalog 前後都是 ABSENT。
  - `main-only-oracle.json` SHA-256 `2d2f1d2a9c94fadc1d42ba3f311d081e4cd728b67cdf12ce494f0fa12495a661`。
- **M3**：create-save／reload-read／unload-replace／final-verify 與 setup-catalog／success-roundtrip／target-not-full／post-move-authority-loss／stale-service-receipt，每個 receipt 都是 PASS、`stoppedSeen=true`，evidenceOwner＝本 plan workspace。
  - 第一輪 root `task9-wfix1-final-m3-afd100e9e11b403d834459e31d6d8482` 失敗，保留未改判：原因是 harness 在 PS7 下的 `ConvertFrom-Json` DateKind 問題，不是 probe 或 production 缺陷。

18 份 M4 per-test Universe receipts：default 目錄 `m4-per-test-25296-eec94b302934408bafbfcd5bbf72f8e5/`，legacy 目錄 `m4-per-test-17104-6eaef35602f1425984de0a579fd3bb20/`。

- 每個 test 都有 `.before.json` 與 final receipt，`M4_PER_TEST_BEGIN`／`END` log 各恰 1 行。
- 18 份全為 PASS，名稱唯一，`snapshotExactUnchanged=true`、`runtimeHandlesExactUnchanged=true`，before／after canonical JSON 完全相同。
- 每份的 runtimeHandles 都是 0 筆；worldKeys 都是 overworld／the_end／the_nether／`quantumchamber:superposition`。

| Test | Run | Catalog（before=after） | Records n | Final receipt SHA-256 |
| --- | --- | --- | --- | --- |
| `busy_participant_start_rejected_before_any_reservation`（新） | default | `1c794607…` | 1 | `1e31ff75f0049d92faafe00fa04f6bb65b2fe882b72872c384ab6b666e9ea845` |
| `dormant_receipt_blocks_only_its_chamber_and_participant_enters_other_chamber`（新） | default | ABSENT | 0 | `e08ea128330df45dbeac234e70eafb589625ac5e2915529446654a29f1b08416` |
| `east_complete_doors_require_checked_ledger` | default | ABSENT | 0 | `1d2d31dd447b168c7e3b2cdec97eb26dd8857e4d6090d9e24700feac9ae0a37a` |
| `native_candidate_batch_fault_converges_to_checked_returning`（新） | default | ABSENT | 0 | `5da69119fb27c2ab3ed42901efa951f5285dc884731b55f2ab9d042ee082ff64` |
| `native_selection_all_25_cells_first_wins_and_measured_freeze` | default | ABSENT | 0 | `dea6db61572bb69876ed281e8f02db67d09ec10d671566f6397a5e19d938b389` |
| `native_selection_cannot_use_candidate_before_checked_batch_readback` | default | ABSENT | 0 | `6fc3aff120f85675adc9d55830954912c07127c1b8680243390befa631f1dd38` |
| `native_selection_fault_never_admitted_by_other_session_flush_or_native_save`（新） | default | ABSENT | 0 | `bb28f078b01a13ea805a22c41b4e8c77fdac6727b6527d290ada6c80646c5124` |
| `native_selection_flush_failure_never_sends_success` | default | `1c794607…` | 1 | `ea1039bfde75fceffc8c18ea4ffe8e803c0dbeffdd48c9764898ba36a5637f71` |
| `native_selection_readback_failure_never_sends_success` | default | ABSENT | 0 | `adf1bcacd9b7280155e2510094ac860c0aa9e3b915690cbb9ce5b73d915a6edb` |
| `native_selection_rejects_identity_bbox_cohort_source_and_incomplete` | default | ABSENT | 0 | `94db687d16c442a90665715cd25c3e14b828ebc725a9ff3f9978b967e282fffb` |
| `native_selection_rejects_pending_batch_retired_and_unready_mapping` | default | ABSENT | 0 | `f2c99be9ae10ca842fcdc9c106848359776d08e4eae051c2fedfde375f643597` |
| `native_start_freezes_candidate_context_before_geometry` | default | ABSENT | 0 | `67dde5847b819213892ed82828902278a84918762e892510c54bc28fa8e6c5e5` |
| `native_start_initial_flush_fault_rejects_without_residue`（新） | default | ABSENT | 0 | `4ea80453255c44c08133e6f9b6c1c1edb587acf83fdf22470bcb710f3f6326b7` |
| `north_complete_doors_recycle_split_merge_and_seam` | default | ABSENT | 0 | `242c67bac0f02ce6199712d2aa0ff656fe4e8e332fa791272f640a7785a1b992` |
| `south_complete_doors_require_checked_ledger` | default | ABSENT | 0 | `a9e99bb08285740c2c42e174b0db551e914b9a38665ef0fd1212642a52dc43f2` |
| `unhealthy_authorities_reject_before_reservation` | default | ABSENT | 0 | `ba66ba99c9de790a2affb68a08c7173afa4e3a6d33a80fe6d80473fe91449700` |
| `west_complete_doors_require_checked_ledger` | default | `1c794607…` | 1 | `4c043c3f52d5d1b053db1555263f9c35bfffe9d8b0730377b43ab70d66c00545` |
| `isolated_legacy_schema2_then_empty_schema3_authority_gates` | legacy | ABSENT | 0 | `9efdd86e746585c6dbfe2ebceacd102da38a6d3fd04c7e28c6ef3dd1836043c3` |

Catalog 為 `1c794607…`（1 筆）的三個 test，是在 M3 `empty_bootstrap_and_disabled_record_do_not_create_worlds` 建立 DISABLED sentinel 之後才執行的，所以它們的 baseline 本來就含那 1 筆。它們自己的 before 與 after 仍完全相同，M4 沒有新增任何 record。

GameTest 覆蓋範圍（逐名對照見 evidence owner 的 `m4-automated-gate-report.md` 與 `m4-whole-branch-fix1-report.md`）：

- **門的幾何**：四個 source facing、station -1／1／11／12、兩面牆、25 格 normalize，以及入口排除。
- **不可選的情況**：incomplete／pending／retiring／unready。
- **頁面生命週期**：page recycle／split／merge／96 格 seam 後 candidate 不變。
- **多人**：兩人同一 server tick 的 first-wins。
- **拒絕情境**：outsider、spectator、錯 world、bbox、cohort、來源、mapping epoch、RETURNING。
- **提交故障**：
  - flush 前故障：current＝flushed、durable RETURNING＋SELECTABLE、不送成功訊息。
  - readback 後故障：checked MEASURED 並請求保留式 recovery。
  - 選擇故障後，其他 session 的 flush 與 `saveAll` 都不會承認未 checked 的 SELECTED。
  - batch 故障會收斂到 checked RETURNING。
  - `start()` 的初始 flush 故障會 REJECTED 且零殘留。
- **選擇之後**：門保持 closed、remap 凍結。
- **start 與 authority**：start 在 geometry 之前就凍結 context；authority 不健康時在 reservation 之前拒絕；忙碌中的參與者在 reservation 之前就被拒絕。
- **DORMANT**：只封鎖自己的 Chamber。這個 GameTest 使用由 testmod checked 寫入的 synthetic receipt；真實流程產生的 DORMANT 由跨 JVM 的 `dormant-reentry` 證明。
- **legacy**：一個隔離的 schema 2 案例。

**M4 recovery 24 phases**（run id `61c7e7596ceb4da68b831331c3a06112`，aggregate SHA-256 `b3a9f5d6011324a01602803f2540bbcfd5b48ae7be154692517119d72633eaad`）：

- 24 個 phase 分屬 14 條 chain（14 個 nonce），共 14 個 root（`run/m4-recovery-<runId>-<chain>`）。每條 chain 有一個 fresh、nonce-owned 的 root；同一條 chain 內的 phase 依序在**同一個** world 上重啟。不是每個 phase 都有自己的 fresh root。
- 24 個 phase 的 startupNonce 全部不同；(PID, StartTime) 也 24 組全部唯一。只看 PID 是 23 個，因為 PID 24636 被 Windows 重用過一次（dirty-candidate 與 selection-flush-fault）。
- 每個 phase 都是 Gradle exit 0、normal stop、lock released，receipt SHA-256 與 launcher 記錄一致。
- 以上數字直接讀 `recovery-run-61c7e7596ceb4da68b831331c3a06112.json` 的 `phases[]` 核對。

| Chain | Phases | 驗證內容 |
| --- | --- | --- |
| chain | `select` → `recover` → `verify` → `dormant-reentry` | 358 筆 candidate。重啟時以 `selected-receipt.dat` strict 比對 context、ledger 與 selection 完全相同（`src/testmod/.../M4CandidateRecoveryProbe.java` 的 `sameReceipt`），entropy 與 catalog bytes 不變。玩家返回原艙，幾何與 ticket 釋放，receipt 保留，最後 `DORMANT`、`chamberBlocked=true`；第三個 JVM 連續 5 個 dormant ticks，沒有重建 space 或來源票。`dormant-reentry` 中，DORMANT 參與者到第二座 Chamber 真實供電，走完 ARMING→SUPERPOSITION→返還→release；receipt 不變，原 Chamber 仍封鎖，沒有資源洩漏。 |
| native-save | `select` → `native-write-fail` → `recover-after-write-fail` → `verify` | native write 失敗時，外層 save 仍正常返回，但記錄到 366 次 failure callback；lease 保留並重新標髒。下一個 JVM 重試後才清空，最後 DORMANT。 |
| low／buff／disconnect | 各 1 | 都走保留式返還，最後 DORMANT。 |
| selection-flush-fault | `selection-flush-fault` → `selection-fault-restart` | 寫入前故障：同 process durable 為 RETURNING＋SELECTABLE，原生 save 與其他 session 的 flush 都不會承認 SELECTED。重啟時 exact 等於 fault receipt，從未 SELECTED，ledger 沒有重抽，最後安全返還。 |
| readback-fault | `readback-fault` → `selection-fault-restart` | W-a 窗口、沒有 crash：readback 時磁碟確實有未確認的 SELECTED，同 process 還原後寫出 RETURNING＋SELECTABLE。重啟後從未 SELECTED。 |
| readback-crash | `readback-crash` → `recover` → `verify` | W-a 窗口、有 crash：還原後立即凍結 journal 寫入，磁碟留在 MEASURED＋SELECTED。重啟依 §11 走保留式返還，最後 DORMANT。 |
| 故障矩陣 | dirty-candidate、dirty-selection、entropy-missing、entropy-corrupt、discovery-corrupt、journal-corrupt | 收緊後的 dirty 窗口不殘留未 checked 的 candidate 或 selection，只信 flushed；其餘以受控 unhealthy 結束。沒有 transient 門或 selection，沒有配置 Universe，損壞的原始 bytes 保留。 |

**Artifact 邊界**（`752ada1` full）：

| JAR | Entries | testmod／gametest／probe 命中 | SHA-256 |
| --- | --- | --- | --- |
| `quantumchamber-0.1.0-SNAPSHOT.jar` | 293 | 0 | `dfe94f8d5441150f35a17c442d262110b07564dc2a976589e3d1315e848b74a1` |
| `quantumchamber-0.1.0-SNAPSHOT-sources.jar` | 185 | 0 | `4f7b5776b532c3f5e89da1f2e190831d184e37c1c483f520214d44a4eea4783c` |

- 唯一的 `probe` 命中是 production 的 `dev/quantumchamber/compat/ModPresenceProbe`，屬於已知的合法項目。
- Release JAR 比 `38d901e` 多 1 個 entry：`dev/quantumchamber/candidate/CandidateLedgerService$CommitFailure.class`。
- Sources JAR 的 zip 時間戳每次 build 都不同，所以整檔 SHA-256 不可跨 build 比較。

### 先前的 gate（歷史，不作為最終證據）

- **Pre-fix baseline（`38d901e`，Task 10 phase 1）**：
  - full `task9-task10-final-full-b81e34898fd1445b84e63a71135ec07f`：default 130/130、JUnit 383、release 292／sources 185、13 receipts PASS。
  - legacy `task9-task10-final-legacy-52fc6d0bf98a4e199f392dcf7c0b0c2d`：1/1。
  - `task10-final-verification-summary.json`，SHA-256 `b6c22a5c9ff91f39cf3418c1bee3a194f037dfc9a16c13a5b78b0fb4de32ef0a`。
- **Task 9 fix round 1（`38d901e`）**：per-test receipts 補到 13 份；有效 RED 為 `task9-fix1-per-test-red3-86839d3a1ce44db68dc09bbc0e94ee96`。
- **Task 9 初版（`8917f84`）**：main-only、M3、M4 recovery 16 phases 與 Windows 14/14。
- Whole-branch fix1 改了 production，因此以上 runtime 證據都已在 `752ada1` 重新跑過，不再沿用。

## M4 明確沒有做的事

- **沒有 Universe allocation 或 materialization**：
  - M4 production 沒有新增 `UniverseRegistry.allocate`、`DynamicDimensionBackend.materialize` 或 `UniverseTransferService` 的呼叫。
  - Per-test receipts、suite boundary 與 recovery receipts 都顯示 catalog、runtime handles 與 world keys 不變。
- **門仍然 closed**：選擇交易不寫方塊，25 格維持 `OPEN=false`。
- **沒有 collapse、passage、teleport、Chamber Projection、client packet 或 renderer**：M4 沒有修改 `src/client`，選擇後玩家與走廊都不移動。
- **沒有正式的 discovery mutation**，也沒有 `AllocationToken → UniverseId` mapping（屬於 M5）。
- **Comparator 不會輸出 15。**

## 人工驗收狀態（spec §15）

- **尚未執行**，目前沒有任何遊戲內人工紀錄。本文的自動證據不能代填人工結果。
- spec §15 的範圍：側門可被選擇一次、收到「候選已鎖定」訊息、其他門被拒絕，門仍關閉，玩家仍在走廊。
- 「走廊消失、回原艙、門後是新世界」屬於 M5，不得把 M4 的中間狀態回報成原需求已完成。
- 驗收要和 M2 八項分開做。選擇側門後，session 返還會留下 DORMANT receipt，封鎖該座原艙直到 M5。M2 項目請用沒有點過側門的 Chamber 或世界；M4 §15 請用另一座 Chamber 或另一個測試世界。

## M5 handoff acceptance

1. **起點**：M5 只能從 checked `MEASURED + SELECTED` 開始。必須讀 `flushedRecords()` 中 exact 的 receipt，不可採信 current dirty 狀態。
2. **依 candidate variant 解析**：
   - `SOURCE` → 同 session 凍結的 `SourceFamilyRef`。
   - `EXISTING` → exact 的 `UniverseId`。M5 在 collapse 時必須**重驗**它仍是 gameplay-eligible，不符就 fail closed（spec 只凍結了 watermark）。
   - `NEW` → 以 checked、idempotent 的 `allocationToken → UniverseId` mapping 做 lookup-or-create；同一個 token 永遠對應同一個 UniverseId。
3. **先提交 intent**：M5 在任何 allocation 之前，必須先 checked 提交自己的 `COLLAPSING` intent，之後才可以做 Projection、corridor collapse 與 passage。
4. **不得改寫 M4 receipt**：
   - 不得重新抽 candidate，不得覆寫或刪除 context、ledger、selection。
   - 只有 M5 checked 的終局，或明確的安全取消，才可以移除 dormant record。
   - M4 的 `remove` 對 `MEASURED` 一律拒絕，M5 需要自己的 checked 轉移。
5. **不可假設選擇者看過成功訊息**：以下三種情況，都會出現「玩家看到 FAIL，但 durable receipt 已成立」：
   - W-a 的 crash 分支。
   - W-b（flush 已 checked，之後才回報失敗）。
   - `candidateMeasured` 在 checked `SELECTED` 之後丟例外。
6. **start 的 W-a 窗口**：初始 ARMING 已原子寫入、readback 失敗時，`start()` 經 `abandonInitial` 回 `REJECTED`。此時磁碟可能仍有該 ARMING record；若在下一次 journal 寫出覆蓋它之前 crash，重啟會依 durable ARMING 走 return-only，把玩家拉回原艙（玩家實際上未離開原艙）。
7. **DORMANT 參與者可能同時在別的 session**：DORMANT 參與者可以同時屬於另一個活動 session。M5 任何「離開 DORMANT、重新移動玩家」的轉移，都必須再受跨 session 玩家唯一性約束。
8. **原艙封鎖由 M5 接手**：dormant `MEASURED` 造成的原艙封鎖要由 M5 處理；M4 沒有提供遊戲內解除路徑。
9. **活性風險**：`MeasuredWorldSaveCheckpoint` 使用的是全伺服器的 chunk／entity save 失敗計數（`MeasuredWorldSaveCheckpoint.java:30-38`）。無關 chunk 反覆失敗時，`MEASURED` 清理會無限重試。這是 fail-closed，但 M5 或後續 hardening 應改為只看相關 chunks。

## 已知 deferred 項目與觀察

**Whole-branch round 1**（延後或接受，依 ledger ruling）：

| 項目 | 問題 | 建議修法 |
| --- | --- | --- |
| quality M1 | `authorityHistory` 與 fix1 新增的 `checkedAuthority` 在 process 內沒有上限（`SessionRecoveryState.java:32-34`） | 在 checked remove 之後，或依 session 終局修剪；需先確認 remove→reinsert 的保護不變 |
| quality M2 | 每個 idle tick 都全量重掃（`refreshCandidates`、`completeDoor`、`publishable`、`durable`；`CorridorPageManager.java:153-155`、`:392-419`） | 先做多 session 的 MSPT 量測，再以 mapping epoch 或 ledger revision 做髒標記 |
| quality M4 | codec 嚴格度缺口：entropy root 沒檢 exact keys（`CandidateEntropyState.java:69-71`）；decode 沒強制 `ledger.size() ≤ maxCandidateEntries`；schema 3 內層的 Origin／Participants／SpaceLeases 沿用 legacy 寬鬆解析；Vanilla WorldKey 接受省略 namespace | 在 M5 schema 變更前一次收緊，並補 rejection fixtures |
| quality M5＝spec m-2 | `sideDoorCells`（`CorridorGeometry.java:86-96`）回傳 5 個不同位址各重複 5 次，不含 y 與牆面，production 沒用；`completeDoor` 重做了幾何 | 讓 `sideDoorCells` 回傳含 y／牆面的 25 格，並由 `completeDoor` 共用 |
| quality M6 | 死碼與重複：`RETAIN_RECEIPT` 永不回傳；`selectableDoor` 只有 testmod 用；candidate 三欄位相等判斷重複 3 處；`CandidateLedgerService.copy` 與 `withProgress` 重疊；每個 batch 都重讀 entropy／discovery，並呼叫 `Mac.getInstance` | 小型重構：集中 helper，並快取 per-session resolver |
| spec m-1 | 每 tick 256 個新 DoorKey 的預算是 per-session（`CorridorPageManager.java:405-408`、`CandidateLedgerService.java:24`）；多 session 同 tick 沒有全域加總 | 已接受並文件化；若量測需要，再加全域預算 |

**Whole-branch fix round 1**（全部延後，不開 fix round 2；以下多為 production 不可達的防禦路徑、雙重故障邊界，或測試證據強度）：

| 項目 | 問題 | 建議修法 |
| --- | --- | --- |
| q1 | public 的 `abortUnchecked` 在 CAS 成立時無條件回退，沒有限定只處理 unchecked candidate（`SessionRecoveryState.java:139-144`） | 加上 `!candidateChecked(expected)` 前置條件，或縮小可見性 |
| q2（＝s1） | abort 本身丟例外時，log 誤稱「已隔離」，實際沒有隔離，是 fail-open（`CandidateLedgerService.java:150-157`） | abort 例外時明確隔離，或改寫 log，並補單元測試 |
| q3（＝s1） | quarantine 沒有離開或收斂的路徑，玩家會困到重啟 | 為隔離 session 設計 checked RETURNING 的收斂路徑 |
| q4（＝s2） | 雙重故障（初始 flush 失敗＋`abandonInitial` 清理失敗＋RETURNING flush 也失敗）仍會讓 recovery 全域暫停；`SessionRecoveryManager.java:69` 的註解說得過滿 | 對殘留的 unchecked ARMING 也走隔離或 CAS 移除，並修正註解 |
| q5 | `abandonInitial` 的 log 缺 root cause，可能每秒 warn 一次（`SuperpositionSessionManager.java:142-143`） | 記錄 root cause，並依 session 去重 |
| q6 | GameTest 的 `saveAll` 在 journal 不是 dirty 時不會寫 journal，所以「native save 不承認」沒有被獨立驗證（`M4CandidateDoorGameTests.java:165-178,200-211`） | 先標髒再呼叫 `saveAll`，並比對磁碟 bytes |
| q7 | `selectionTeardown` 失敗時 rethrow 會跳過收尾，`observed` 是死碼（`M4CandidateDoorGameTests.java:389-399`） | 改用 try/finally 收尾，並刪除死碼 |
| s3 | candidate batch 的 dirty 窗口沒有 cross-JVM restart receipt | 與 selection 共用 `commitChecked`，目前由 selection-fault-restart 間接覆蓋；可補一條 batch-fault-restart chain |

**Whole-branch fix round 1 review 的 Nits**（非阻擋；原本未存檔，Task 10 docs fix 補記）：

| 項目 | 問題 | 建議修法 |
| --- | --- | --- |
| spec Nit 1 | GameTest RED root（`task9-wfix1-red-gametest-7059e314…`）與 probe RED console（`wfix1-probe-red-console.log`）只記 `HEAD=38d901e`，沒有記 `git status`；「production 未修改」是由失敗行為推定。unit 與 start 的 RED 有記 status | 之後的 RED harness 一律同時記錄 HEAD 與 `git status --porcelain` |
| spec Nit 2 | `CandidateLedgerService.java:74`（flushed 不是 SUPERPOSITION＋SELECTABLE）與 `:85`（超過 cap）直接回 `failedBatch()`，沒有 root cause log | 在這兩個出口補固定訊息的 warn（不含 record 內容），並依 session 去重 |
| quality Nit 1 | `M4LegacyGameTestFilterMixin.java:24` 的 `selected.size()!=batches.size()` 假設每個 batch 只有一個 test；只在 RED 取證的 `onlyBatches` 路徑使用 | 改為比對實際出現的 batchId 集合 |
| quality Nit 2 | `M4CandidateTestAccess.java:74` 對 `checkedAuthority` 的 `NoSuchFieldException` fallback，在 HEAD 是死碼（只為了在 base 上跑 RED） | M4 收尾後刪除 fallback，缺欄位時直接失敗 |
| quality Nit 3 | `CandidateLedgerService.describe()`（`:162-167`，迴圈在 `:165`）只防自我因果；多節點的 cause 環理論上會無限迴圈 | 以 identity set 或深度上限走訪 cause chain |

**Task 9 fix1 的 scratch harness minor**：`task9-gates.ps1` 沒有依 server PID 過濾，就把 runDir 累積的 `classload-*.log` 與 `m4-boundary-*.json` 複製進 gate root。各 summary 只採用 server PID 的 artifacts，正確性不受影響。建議：只複製 server PID 的 artifacts。

**觀察**：

- `QuantumSuperpositionMod` 沒有在 server start 時 attach candidate 或 discovery 權威。Entropy 與 discovery 在 session 建立與每個 batch 時按需 strict 讀取；server start 只做 journal healthy gate（`main/QuantumSuperpositionMod.java:37`）。
- Recovery probe manifest 的 `candidateIds` 欄位記錄的是被遮蔽的 `toString`，不是 id bytes。跨 JVM 相等性是由 `sameReceipt` 對照 `selected-receipt.dat` 證明的。
- 選擇後的原艙封鎖會影響同一 save 的 M2 人工驗收：測試 M2 項目時不要點側門。

## Final review

M4 whole-branch review 最終 **Critical 0／Important 0**，spec compliance ✅，code quality Approved。

| 輪次 | Review range | Spec | Quality |
| --- | --- | --- | --- |
| Round 1 | `ba9ca73..38d901e`（14 commits） | ❌：Critical 0／Important 2（I-1、I-2）／Minor 3 | Needs fixes：Critical 0／Important 1（＝I-1）／Minor 6／待確認 3 |
| Fix round 1 | `38d901e..752ada1`（3 commits） | ✅：Critical 0／Important 0／Minor 6／Nit 2；W-I1、W-I2、m-3、quality M3 全 CLOSED | Approved：Critical 0／Important 0／Minor 7／Nit 3 |

- 兩個 Important：
  - W-I1：checked 提交失敗後不回滾，會讓 dirty 的 SELECTED／ledger 被其他寫出承認為 durable，而且 recovery 會全域停擺。
  - W-I2：DORMANT receipt 佔用了參與者 UUID，導致另一座 Chamber 卡在 RETURNING，slot 也洩漏。
  - 兩者都在 whole-branch fix round 1 關閉，gate 全部在 `752ada1` fresh 重跑。
- Rulings：
  - W-a 的 crash 分支接受，不視為 spec 偏離。
  - `COMMIT_FAILED` 正式擴充 plan Task 5 的介面。
  - Fix round 1 的所有 Minor 延後；Task 10 final gate 採用 `752ada1` 的 wfix1 roots。
- Review 紀錄（evidence owner，gitignored）：
  - `m4-final-review.md`，SHA-256 `77adefc8b5def4321e8fa015ca41ad25159ce98dc8aa604c4809df59abf88fd0`（Task 10 docs fix 補入 fix round 1 Nits 與 Task 10 文件 review 摘要後的版本；`eba763b` 引用的舊版為 `729ac259…`）。
  - `m4-whole-branch-review-round1.md`，SHA-256 `6039bdafcef2484ccfc6c67e3db4d9c94c8dce0649eef9db99001dd6581b8954`。
  - `m4-whole-branch-fix1-review.diff`，SHA-256 `106c66524af136c773d3c4652fbf105eaaa301b9a19c2c131b5b9d606554bb22`。
  - `m4-whole-branch-fix1-report.md`，SHA-256 `4017d2e2165891d7d88208fbc384128b4e1d2c6a4fbc6303ddf24111e35301d4`。
  - `m4-whole-branch-fix1-verification-summary.json`，SHA-256 `2858b912979f2decc0fc6f08a50b7c9e21212c9f5c4b2d97042f3c6e7573d1e5`。
- 逐 task review（Tasks 1–9）都已 clean，紀錄在 `progress.md`。
- Task 10 文件 review（`eba763b`）：Approved，Critical 0／Important 0／Minor 6，另有若干 Nit。這些 Minor 與 Nit 由其後的 Task 10 docs fix commit 處理；該 commit 的 review 結果以 `progress.md` 為準。

## 參考資料

- 設計：`docs/superpowers/specs/2026-09-21-m4-candidate-doors-design.md`
- 計畫：`docs/superpowers/plans/2026-09-21-m4-candidate-doors.md`
- Evidence owner（gitignored，本機限定）：`.superpowers/sdd/2026-09-21-m4-candidate-doors/`
  - `progress.md`：SDD ledger 與所有 rulings。
  - `m4-final-review.md`、`m4-whole-branch-*`、`task10-*`、`task9-*`、`recovery-*`。
  - `m4-automated-gate-report.md`，SHA-256 `c4f981c7453fb14750f399e9917097c72f6c1395c83c5183aa771d8782a97094`。
- 前置里程碑：[M3-A backend](2026-09-19-m3a-dynamic-universe-backend.md)、[M3-B transfer readiness](2026-09-19-m3b-server-transfer-readiness.md)、[M2 走廊](m2-corridor.md)。
