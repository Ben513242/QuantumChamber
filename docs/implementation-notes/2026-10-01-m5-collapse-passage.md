# M5：塌縮與單向通道（實作紀錄）

- Spec：[`docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md`](../superpowers/specs/2026-10-01-m5-collapse-passage-design.md)（v5.2，`6aadb03`）
- Plan：[`docs/superpowers/plans/2026-10-01-m5-collapse-passage.md`](../superpowers/plans/2026-10-01-m5-collapse-passage.md)（`ec7229d`、`9c315f8`）
- Branch：`feature/m5-collapse`，由 `main`＝`a7e1be2` 開出。

本文件逐 task 記錄 code-truth、RED／GREEN 的 gate root 與 commit range。gate root 在 gitignored 的 `.superpowers/verification/`，詳細過程在 gitignored 的 SDD ledger `.superpowers/sdd/2026-10-01-m5-collapse-passage/progress.md`；這裡只留可核對的摘要。

狀態：實作中。M5 尚未完成、尚未 push、尚未合併 `main`。

## T0：clean-clone 基線

- 2026-10-01 12:08–12:22，HEAD `2c24d51`（code 等同 `ba854e8`：`git diff --stat ba854e8 2c24d51 -- src build.gradle settings.gradle gradle.properties .github` 為空）。
- 開始時 worktree 沒有 `run/`，也沒有 testmod 固定的 `.superpowers/sdd/2026-09-21-m4-candidate-doors/`。這是 `scripts/verification/` 第一次在 clean clone 狀態實跑；harness 自動建立了該目錄。
- `pwsh -NoProfile -File scripts/verification/run-all.ps1`：root `run-all-fc443127944f40638c4ffcf6771b8f6c`，`END ALL exit=0`。
- SUMMARY 與 [`scripts/verification/README.md`](../../scripts/verification/README.md)「預期結果」逐項相同：default GameTest 146/146（`_windows` 73）、legacy 1/1、per-test probe 28＋1、JUnit 403（0 failure／error，2 skipped）、Windows checkpoint 17、release 299 entries／256 classes、sources 190、testmod 命中 0、main-only PASS、M3 4＋5、M4 recovery 24、CI 規則 Windows／Ubuntu PASS。
- 觀察：以重導輸出的方式呼叫 `run-all.ps1` 時，run 期間啟動的 Gradle daemon 會繼承外層的輸出管線，外層要等 daemon 結束才返回。判斷完成請看 `END ALL` 行。

### M2 的 31 項 runtime noise 分類

整合審查延後到「下一次 fresh gate」的 31 項（清單：外部備份 `superpowers/sdd/2026-09-17-m2-powered-corridor/task-9-r1-runtime-noise-index.json`），以 T0 的 log 逐項對照：

| 分類 | 項數 | 說明 |
| --- | --- | --- |
| 預期 | 20 | 測試注入或驗證拒絕路徑；其中 4 項（#8、#20、#21、#29）的 log 等級屬已延後的 N-4 |
| 已修正 | 5 | `541295a`（R2 M2）已改記 INFO |
| 原版／環境 | 2 | fresh run 目錄沒有 `server.properties`、`eula.txt` |
| 已知延後項 | 4 | R2 M7 全域 pin 上限連坐，加上測試殘留（#24、#25、#27、#28） |

沒有新缺陷。以 (batch, 正規化訊息) 比對，T0 的 full gate 與 integration fix round 4 完全相同，只多上表的 2 行環境訊息。T0 新出現的其他類別都是原版環境訊息或測試注入的預期拒絕。

## T1：GameTest 順序依賴

- Commits：`2b6eae5`（範圍檢查）、`042cb29`（fix round 1，處理 review 的 Minor M-1～M-5）。Commit range `9c315f8..042cb29`，中間的 `7341794` 是 controller 的 docs commit。程式只改 `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`。
- Review：T1 的 spec review 與 code quality review 都是 Critical／Important 0；fix round 1 的 re-review 也是 Critical／Important 0（剩下兩項本 note 的文件 Minor，已在 controller 的 docs commit 修正）。T1 完成。

### Code-truth（`042cb29`）

- 問題：`Batches.createBatches` 以 HashMap 分組，batch 的執行順序會隨 batch 數量改變。`unhealthy_authorities_reject_before_reservation_windows` 原本在共用的 default run 要求整份 journal 與 pages 全空，只要較早的 batch 留下他艙殘留就會失敗。
- default run 只檢查本 test 自己的 authority（`ownAuthorityResidue`）：
  - 範圍：本 test 自建的 sid 與 chamber，加上檢查當下讀取的 fixture chamber UUID。
  - 涵蓋：journal current（`current:`）、durable（`durable:`）、`CorridorPageManager` 的 space（依 session 或 origin chamber，`space:`）、gateway 的 runtime session（`runtime:`）、來源票（`ticket:`）。
  - 本 test 的 start 全部被拒，所以只有 journal 兩類有正向對照：迴圈內寫入 journal 的自建 record（default run 的 `candidate_missing_*`、legacy 的 `legacy_*`），用完整範圍、只靠 sid、只靠 chamber 三種方式檢查都必須抓到（`:914-924`）。`corrupt_*` 與 `discovery_missing_entropy` 的 fake record 沒有寫入 journal，不做這項檢查。`space:`／`runtime:`／`ticket:` 與 fixture UUID 路徑只是防護。
- 開始時：default run 是前置斷言，要求 fixture controller 存在且 `chamberUuid()==null`（`:859-865`）。新艙尚未供電註冊，不可能已有自己的殘留。
- 結束時（`:932-950`）分兩次檢查：
  1. 「完成時」：範圍檢查必須為空。
  2. 「他艙注入後」：在 try 內以 `M4CandidateTestAccess.syntheticSession(...,10007)` put＋flush 另一座 chamber 的可移除 ARMING record，斷言它確實在 durable journal，範圍檢查仍必須為空。清理用既有的 `M2CorridorGameTests.TestHooks`，保證一定 remove＋flush；try 內已失敗時，清理例外以 `addSuppressed` 附在原失敗上。最後斷言該 record 已從 current 與 durable 移除。
- 全域版 `assertEmptyAuthorities` 只給獨立 legacy runner（`m4_legacy_runtime`：mixin 只選這一個 test，跑在獨立 fresh save）。函式開頭守門：`quantumchamber.gametest.legacyOnly` 不為 true 就丟 `IllegalStateException`，比照 `M4CandidateTestAccess.reloadEmptySchemaThree`。三個 legacy 呼叫點（`authorityRejections` 的 legacy 分支、`emptySchemaThreeMissing`、`legacySchema2Runtime`）都受保護。
- GameTest 數量不變。

### Gate 與證據

gate root 都在 `.superpowers/verification/`。「diff」是對該輪 HEAD 的 `git diff -- <test file>` SHA-256。逐輪的 state、diff 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/t1/` 與 `t1/fix1/`。

| 輪次 | HEAD／diff | Gate root | 結果 |
| --- | --- | --- | --- |
| T1 RED：結尾先放他艙 record，再跑原本的全域檢查 | `6aadb03`／`5a7f0316…`（code 等同 T1 的 BASE `9c315f8`：之間只有 plan docs commit，`git diff --stat 6aadb03 9c315f8 -- src build.gradle settings.gradle gradle.properties scripts` 為空） | Full `gate-t1-red-1e1a4d5474864dde885b2bedf9e46d40` | 預期 FAIL：146 個中只有目標 test 失敗，訊息「legacy隔離情境開始／完成都必須沒有current、durable journal或space」。fresh world 的 discovery 檔在該 batch 同一秒被迴圈改寫，可知失敗的是結尾檢查 |
| T1 GREEN | `9c315f8`／`78a95472…`（＝`2b6eae5`） | Full `gate-t1-green-full-71813a2ef13b4a59b430fa877234f6f1`；Legacy `gate-t1-green-legacy-680665df10414d44aa3eece9142124dc` | PASS |
| fix1 RED (a)：default run 呼叫 `assertEmptyAuthorities` 的暫時探針 | `7341794`／`9b6d4848…` | Full `gate-t1-fix1-red-guard-efec2489f43a4854897e9266016100f8` | 預期 FAIL：沒有守門，探針訊息「T1-FIX1-RED default run 呼叫 assertEmptyAuthorities 必須被守門拒絕」 |
| fix1 RED (b1)：拿掉 journal 過濾 `||` 的 sid 側 | `7341794`／`a46ca1fa…` | Legacy `gate-t1-fix1-red-mut-session-f659799318f64a15aed5b835a2ebfbd4` | 預期 FAIL：「legacy_current 只靠 session 過濾也必須抓到本 test 自己的殘留：[]」 |
| fix1 RED (b2)：拿掉 journal 過濾 `||` 的 chamber 側 | `7341794`／`725612eb…` | Legacy `gate-t1-fix1-red-mut-chamber-e85931fb983f4d709fc83db5a7476ad8` | 預期 FAIL：「legacy_current 只靠 chamber 過濾也必須抓到本 test 自己的殘留：[]」 |
| fix1 守門對照：最終程式碼加上同一個探針 | `7341794`／`3ef385c2…` | Full `gate-t1-fix1-guard-probe-726bcbf573604daea74c489b6162f55d` | PASS 146/146：守門丟出 `IllegalStateException`，開始時的前置斷言也成立 |
| fix1 GREEN | `7341794`／`2aeeb9dd…`（＝`042cb29`） | Full `gate-t1-fix1-green-full-7ba90c51798042be9e216c861a81d2a3`；Legacy `gate-t1-fix1-green-legacy-91f747d4c5bd4cffb745042ab8efcbc3` | PASS |

暫時探針與 mutation 都已還原：最終檔案的 SHA-256 與 mutation 前的快照相同，且 `042cb29` 的 diff 等於 fix1 GREEN 的 diff。

兩輪 GREEN 的數字相同：default GameTest 146/146（`_windows` 73），0 failure／error／skipped；boundary oracle PASS；per-test receipt 28/28 PASS；JUnit 403，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；legacy 1/1，per-test receipt 1/1 PASS。

觀察：`-Mode Legacy` 不做 clean，所以 gate root 的 `result-build/` 會帶上一輪 build 目錄留下的 `gametest-results.xml`。判讀 Legacy gate 只看 `gametest-legacy-results.xml`。

### 依賴全域狀態的其他斷言（Step 3 清查，T1 未修改）

本檔：

- 全域為空：只剩 legacy runner 內的 `assertEmptyAuthorities`，以及 `legacySchema2Runtime` 的 SchemaVersion==2（`:1058`）。兩者都在獨立 fresh save 執行，保留不改。
- 跨 tick 的全域快照（他艙殘留若仍在變動，可能誤判）：
  - `busy_participant_start_rejected_before_any_reservation_windows`（`:314-323`）：`journalUnchanged`（整份 records、flushed 與 journal 檔 bytes）和 `runtimeOwnership`，在 tick 2 取快照、tick 4 比較。
  - `dormant_receipt_blocks_only_its_chamber_and_participant_enters_other_chamber_windows`（`:280`、`:294`）：`runtimeOwnership` 從 tick 2 一直比到收尾之後。
  - `native_start_initial_flush_fault_rejects_without_residue_windows`（`:257`）：`journal.recoveryWritesSafe()` 是全域條件。同一段的其他快照都在同一 tick 內比較，不受影響。
  - `nativeSnapshot`（`:52`、`:71`、`:77`）與 `universeSnapshot`（`:632`、`:676`、`:741`、`:769`）：catalog、runtime 與 world 清單都是全域，跨 tick 比較。這屬於 M4 的邊界設計，只要 default run 不建立 Universe 就成立。
- 順序風險（不是全域為空的斷言）：`publishProbe`（`:465-468`）與 `batchFaultProbe`（`:209-212`）只在等待條件達成時清除，等待逾時就會殘留到後續 batch。

他檔：

- `M2CorridorGameTests.java:1754` 的 `NativeEntry.assertNoSession()`：world 數必須 == 4，本檔 `:909` 也會呼叫。
- `M3UniverseLifecycleGameTests.java:58`、`:60`、`:70`：catalog 必須為空、world 數 == 4。
- `M4CandidateTestAccess.java:113-114` 的 `reloadEmptySchemaThree`：要求全域為空，只在 legacy runner 使用。
- 各 `*Probe.java` 都在獨立 JVM 的 fresh world 執行，不受 batch 順序影響。

處理歸屬：

- 涉及 Universe 與 world 數的項目（`nativeSnapshot`、`universeSnapshot`、`assertNoSession`、M3 lifecycle），由 plan 決策 6 處理：會配置或物化 Universe、或留下 M5 record 的 GameTest，一律放在 `gameTestM5` run。
- 其餘項目（跨 tick 的 journal／runtime 快照、`recoveryWritesSafe`、probe 殘留）未排入 T1，留待後續 task，由 controller 指派。

## H1：codec strictness

- Commits：`e431b7f`（H1，parent `63f9496`）、`8a68630`（fix round 1，parent `b959a90`）。code 的 commit range 是 `63f9496..8a68630`，中間的 `b959a90` 是本段的 docs commit。只改 plan 列出的檔案：`CandidateEntropyState.java`、`CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`CandidateEntropyStateTest.java`，新增 `SessionRecoveryStrictnessTest.java`。`SourceFamilyRef.java` 沒有修改：vanilla WorldKey 的解析在 `CandidateJournalCodec`，不在該檔。
- Review：
  - `e431b7f` 的 spec review 為 0 finding；code quality review 為 Critical 0、Important 0、Minor 4，另有小建議。
  - fix round 1（`8a68630`）處理全部 4 項 Minor 與小建議，內容見下方「Fix round 1」。
  - fix round 1 的 re-review：Critical 0、Important 0、Minor 1（本 note 的行號標示，已在 controller 的 docs commit 修正）。H1 完成。

### Code-truth（`8a68630`）

| 規則 | 位置 | 失敗路徑 |
| --- | --- | --- |
| entropy root 恰為 `{SchemaVersion, CandidateEntropy}` | `CandidateEntropyState.read`（`:70-72`），在既有的型別與長度檢查之前 | `UnhealthyEntropyException`（固定訊息、不帶 cause）；不換發 secret、不改檔案 |
| `ledger.size() ≤ maxCandidateEntries` | `SessionRecoveryRecord.fromNbt` 的 schema 3 分支（`:271-274`）：先解出 `CandidateContext`，再拿 `CandidateLedger` 的 list 長度與 `maxCandidateEntries()`（M4 policy 固定為 16384）比對，比對通過才逐筆解碼 | `IllegalArgumentException` → `SessionRecoveryState.fromNbt` 的 failed state |
| schema 3 的 `Origin`、`Participants`、`SpaceLeases` exact key | `fromNbt` 的 `:232`、`:244-245`、`:255`，呼叫帶位置標籤的 `CandidateJournalCodec.keys(where, …)`（`:146-152`），只在 `strict`（`:221`）時執行 | 同上 |
| vanilla WorldKey 帶 namespace 且為 canonical | `CandidateJournalCodec.canonicalIdentifier(where, nbt, key)`（`:157-162`）：`Identifier.tryParse(raw)` 不為 null，且 `toString()` 等於原字串。用在 `SourceFamilyRef` 的 VANILLA（`:47`，標籤 `SourceFamily`）與 schema 3 的 Origin（`SessionRecoveryRecord.java:236`，標籤 `Origin`） | 同上 |

- `strict`：在 `fromNbt` 的版本白名單之後立即定義為 `envelopeSchema >= 3`（`:221`），函式內原本的 `envelopeSchema == 3`／`< 3` 判斷都改用它；`compounds(nbt, key, envelopeSchema)` 的空 list 規則改為 `>= 3`（`:312`）。白名單仍只接受 1–3，所以目前行為不變；之後的 schema 4 預設走嚴格路徑。`SessionRecoveryState.java:80-83`（版本白名單 `:80`、envelope exact key `:81`、`candidateInitialized` `:83`）都還寫死 schema 3，本 task 沒有修改（不在檔案清單），三處都留給 F2a（spec §13.2）。
- 型別：三個內層 compound 的每個欄位，在 H1 之前就經 `requireType`、`uuid`、`ints`、`vector`、`bool`、`compound` 以 exact NBT 型別讀取（schema 1–3 共用）。型別檢查沒有缺口，H1 只補 exact key。錯型別的 fixture 是對照組，H1 前後都會被拒絕，訊息仍是各欄位既有的訊息。
- `Origin.WorldKey` 也一併收緊。理由：record 建構子要求 `origin.worldKey()` 等於 role 對應的原版世界（`SessionRecoveryRecord.java:64-69`），所以 Origin 的 WorldKey 在語意上就是 vanilla WorldKey；寫出端是 `origin.worldKey().toString()`，一定是 canonical 形式。收緊只套用在 schema 3。
- legacy：schema 1／2 的 `strict` 為 false，解析路徑不變。Origin 的 ChamberUuid 與 WorldKey 先抽成區域變數（`:235-236`），但求值順序與原本相同（ControllerPos → ChamberUuid → WorldKey → 其餘），所以 legacy 的錯誤訊息也不變。`"overworld"`、`":overworld"` 照舊讀成 `minecraft:overworld`，內層未知 key 照舊忽略。
- NBT：沒有新增、改名或移除任何 key。寫出端（`toNbt`、`writeAtomic`、`CandidateJournalCodec` 的 `context`／`entry`／`selection`）未修改，schema 版號不變。
- 錯誤訊息：
  - entropy：固定訊息「候選 entropy 欄位缺失或含未知欄位」，不帶位置、key 名稱或內容。
  - ledger：「schema 3 ledger 超過 MaxCandidateEntries」。
  - exact key：以原句「schema 3 payload 欄位缺失或含未知欄位」開頭，附位置、缺少的 key 與未知 key 的數量，例如 `：Participants[1] missing=[Returned] unknown=1`。未知 key 的名稱來自存檔內容，只報數量、不輸出。M4 原有的 `keys` 呼叫點（envelope、record、candidate payload）不變，訊息仍是原句。
  - WorldKey：`tryParse` 回 null 時是「`<where>.WorldKey` 不是合法 identifier」，非 canonical 時是「`<where>.WorldKey` 必須帶 namespace 且為 canonical 形式」。兩者都不輸出原字串。
  - testmod 與其他 JUnit 都沒有比對這些訊息。
- `PolicyId` 維持 `Identifier.of`：policy 必須等於 `quantumchamber:m4_v1`，而非 `minecraft` namespace 沒有省略寫法，任何非 canonical 字串都會解析成不同的 identifier，然後被拒絕。

### 測試（`8a68630`）

- `CandidateEntropyStateTest.extraRootKeysFailClosedWithoutRegeneration`（新增）：兩個多 key 的 root，各以 evidence true／false 載入，以 `assertAll` 逐一判定。每個 fixture 都要丟 `UnhealthyEntropyException`，訊息等於固定訊息、不帶 cause、不洩漏內容、檔案 bytes 不變，而且目錄裡仍只有一個檔案。
- `SessionRecoveryStrictnessTest`（新增，12 個 test）。schema 3 fixture 另加第二位參與者與第二筆 lease，證明逐筆檢查，而不只看第一筆。每個 fixture 都要求 failed state，而且失敗訊息必須包含該規則的訊息（含位置標籤），避免因其他原因被拒絕而誤判 GREEN。
  - 規則：
    - `ledgerAboveMaxCandidateEntriesFailsClosed`：16385 筆合法 entry。
    - `ledgerLimitIsCheckedBeforeDecodingAnyEntry`：16385 筆空 compound，必須拿到上限錯誤，不能是欄位錯誤。
    - `originRejectsUnknownKeys`、`participantsRejectUnknownKeys`、`spaceLeasesRejectUnknownKeys`：訊息必須是 `<where> missing=[] unknown=1`，而且不含未知 key 的名稱。
    - `innerMissingRenamedAndWrongTypedKeysReportTheirLocation`：Origin、`Participants[1]`、`SpaceLeases[1]` 的每個 key 都測一次。缺 key 時報 `missing=[<key>] unknown=0`；改名時報 `missing=[<key>] unknown=1`，且不含改名後的名稱；錯型別時是該欄位既有的訊息。
    - `sourceFamilyWorldKeyMustBeNamespacedAndCanonical`、`originWorldKeyMustBeNamespacedAndCanonical`：`"overworld"`、`":overworld"`。
    - `invalidWorldKeysAreReportedAsInvalidIdentifiers`：`Minecraft:overworld`、`minecraft:Overworld`、`minecraft:overworld `，必須回報為「不是合法 identifier」。
  - 不得退步的對照：
    - `ledgerAtMaxCandidateEntriesStillRoundtripsExactly`：16384 筆 exact 讀回。
    - `validSchemaThreeFixturesStillRoundtripExactly`：4 種 variant，原 fixture 與多筆 fixture 都 exact 讀回。
    - `schemaOneAndTwoKeepLenientInnerParsing`：schema 1／2 的內層未知 key 與三種 WorldKey 寫法都照舊接受。

### Gate 與證據（H1 首輪，`e431b7f`）

gate root 都在 `.superpowers/verification/`。逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/h1/`。diff hash 是 `git diff 63f9496 e431b7f -- <path>` 輸出的 SHA-256：`src` 為 `635e4083…`，`src/test` 為 `7f0c4929…`，`src/main` 為 `fe56ef53…`。

| 輪次 | HEAD／diff | Root | 結果 |
| --- | --- | --- | --- |
| RED 1：第一版測試，迴圈遇到第一個失敗就停 | `63f9496`／未 commit 的測試（快照 `h1/red-*`） | focused JUnit（`CandidateEntropyStateTest`＋`dev.quantumchamber.persistence.*`） | 預期 FAIL：112 個 test 中 7 個失敗，就是 7 個規則 test：entropy 的 `extraRootKeysFailClosedWithoutRegeneration`，以及 `ledgerAboveMaxCandidateEntriesFailsClosed`、Origin／Participants／SpaceLeases 的未知 key、SourceFamily 與 Origin 的 WorldKey canonical |
| RED 2：規則 test 改用 `assertAll`，逐一判定每個 fixture | `63f9496`／`7f0c4929…`（測試檔與 `e431b7f` 完全相同） | 同上（`h1/red2-*`） | 預期 FAIL：112 個 test 中 7 個失敗。19 個規則 fixture 都是「nothing was thrown」（entropy 4、ledger 1、Origin 未知 key 2、Participants 4、SpaceLeases 4、SourceFamilyRef WorldKey 2、Origin WorldKey 2）。5 個對照 test 通過，名稱是 `e431b7f` 當時的：`ledgerAtMaxCandidateEntriesStillRoundtripsExactly`、`innerMissingRenamedAndWrongTypedKeysStayRejected`、`invalidWorldKeysStayRejected`、`validSchemaThreeFixturesStillRoundtripExactly`、`schemaOneAndTwoKeepLenientInnerParsing`；其中兩個在 fix round 1 改名並改為比對訊息 |
| GREEN focused | 工作樹＝`e431b7f`／`635e4083…` | 同上（`h1/green-focused-*`） | PASS：112 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`） |
| GREEN Full | 同上 | `gate-h1-green-full-c3db394595244e6a8d0b5f7077d88968` | PASS |
| GREEN Legacy | 同上 | `gate-h1-green-legacy-282aba0bec75485d8f43ee28771d1fa1` | PASS |
| GREEN M4 recovery | 同上 | `recovery-run-46bd7e10a5e54a39b74a20117337d172.json` | PASS |

gate 是在 commit 之前、以工作樹執行。執行前記錄 5 個檔案的 `git hash-object`，與 `e431b7f` 中各檔的 blob id 逐一相同（`h1/green-state.txt`、`h1/commit-hashes.txt`）。

GREEN 數字：

- default GameTest 146/146（`_windows` 73），0 failure／error／skipped；boundary oracle PASS；per-test receipt 28/28 PASS。
- JUnit 415，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 T1 的 403 多 12 個，就是本 task 新增的 1＋11 個 test。
- legacy 1/1（`isolated_legacy_schema2_then_empty_schema3_authority_gates_windows`），per-test receipt 1/1 PASS。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。

### Fix round 1（`8a68630`）

處理 code quality review 的 4 項 Minor 與小建議：

1. 失敗訊息可定位：新增帶位置標籤的 `keys(where, nbt, expected…)`，只給 H1 新增的呼叫點使用（Origin、`Participants[i]`、`SpaceLeases[i]`；兩個迴圈改為 indexed）。`canonicalIdentifier` 也加上位置標籤，並把「`tryParse` 回 null」與「非 canonical」拆成兩句訊息。M4 原有的 `keys` 呼叫點與 entropy 的固定訊息不變。
2. schema 4 預設嚴格：`strict = envelopeSchema >= 3`，在版本白名單之後立即定義，並取代函式內所有 `== 3`／`< 3` 判斷；Origin 的 ternary 抽成區域變數（見 Code-truth）。
3. 鎖住上限檢查的順序：新增 `ledgerLimitIsCheckedBeforeDecodingAnyEntry`（16385 筆空 compound），保留合法 16385 筆的規則 test 與 16384 筆的邊界 round-trip。
4. `canonicalIdentifier` 的 javadoc 改為「schema 3 的 Identifier 欄位（目前用於 SourceFamilyRef 與 Origin 的 WorldKey）」。
5. 小建議：`CandidateEntropyStateTest` 改用 import，並逐字比對 entropy 的固定訊息。

逐輪證據在 `h1/fix1/`。diff hash 是 `git diff b959a90 8a68630 -- <path>` 輸出的 SHA-256：`src` 為 `8795d82f…`，`src/test` 為 `b96640fe…`，`src/main` 為 `286b0a33…`。H1 全部 code（`git diff 63f9496 8a68630 -- src`）為 `1405dd19…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（訊息）：先改測試，比對位置標籤與拆分後的訊息 | `b959a90`；只有 2 個測試檔有變更（`h1/fix1/red-labels-*`） | focused JUnit（`CandidateEntropyStateTest`＋`dev.quantumchamber.persistence.*`） | 預期 FAIL：113 個 test 中 7 個失敗。Origin／Participants／SpaceLeases 未知 key、Origin 與 SourceFamily 的非 canonical WorldKey、無效 identifier，以及 inner test 的 30 個缺 key／改名 fixture，都因訊息沒有位置標籤而失敗；inner test 的 15 個錯型別 fixture 通過 |
| RED（順序 mutation）：在 GREEN 程式上把上限檢查暫時移到逐筆解碼之後 | `b959a90`＋GREEN 工作樹＋mutation（`h1/fix1/red-order-mutation.*`） | 同上 | 預期 FAIL：113 個 test 中只有 `ledgerLimitIsCheckedBeforeDecodingAnyEntry` 失敗，拿到的是「schema 3 payload 欄位缺失或含未知欄位」，不是上限錯誤；合法 16385 筆的 test 仍通過。mutation 已還原：還原後檔案的 SHA-256 等於 mutation 前的快照（`CBB2CCA2…`），且不含 mutation 標記 |
| GREEN focused | 工作樹＝`8a68630` | 同上（`h1/fix1/green-focused-*`） | PASS：113 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`） |
| GREEN Full | 同上 | `gate-h1-fix1-green-full-26d1863c796649a6baf0035b453b7077` | PASS |
| GREEN Legacy | 同上 | `gate-h1-fix1-green-legacy-59d60e919aed465f9e9ff213c2cb5db2` | PASS |
| GREEN M4 recovery | 同上 | `recovery-run-08a60700df91498991f24630cac72df9.json` | PASS |

gate 在 commit 之前以工作樹執行。執行前記錄 4 個檔案的 `git hash-object`，與 `8a68630` 中各檔的 blob id 逐一相同（`h1/fix1/green-state.txt`、`h1/fix1/commit-hashes.txt`）。

fix round 1 的 GREEN 數字：

- default GameTest 146/146（`_windows` 73），0 failure／error／skipped；boundary oracle PASS；per-test receipt 28/28 PASS。
- JUnit 416，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 H1 首輪多 1 個，就是新增的 `ledgerLimitIsCheckedBeforeDecodingAnyEntry`。
- legacy 1/1，per-test receipt 1/1 PASS。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。

### 觀察（未修改）

- `ChamberRegistryState.decodeRecord`（`:173`）以 `Identifier.tryParse` 讀 `OriginWorldKey`，同樣接受 `"overworld"` 這類非 canonical 寫法。它屬於 chamber registry（schema v2），不在 H1 範圍；plan 也規定 M5 不改 `ChamberRegistryState`。是否處理由 controller 決定。

## H2：只看相關 chunks 的存檔 checkpoint

- Commits：
  - `f7ac878`（H2 首輪，parent `f6be3ed`）。只改派工列出的檔案：新增 `ChunkSaveCheckpoint.java`；修改 `MeasuredWorldSaveCheckpoint.java`、`NativeSaveFailures.java`、`NativeSaveFailureAccess.java`、三個 production mixin（`MinecraftServerSaveFailureMixin`、`StorageIoWorkerSaveFailureMixin`、`EntityChunkRecoverySaveMixin`）、`NativeSaveFailuresTest.java`，以及 testmod 的 `MeasuredCheckpointFaultMixin.java` 與 `M4CandidateDoorGameTests.java`。`NativeStorageSaveAccess.java`、`EntityRecoverySaveAccess.java` 沒有修改。
  - `14fc189`（fix round 1，parent `156298a`）：改為以 region 判定，內容見下方「Fix round 1」。
  - `f362ebe`（fix round 2，parent `8172104`）：計入 region 檔快取逐出的 close 失敗，並處理 quality review 的 Minor，內容見下方「Fix round 2」。
  - H2 code 的 commit range 是 `f6be3ed..f362ebe`。中間的 commit：`1f26b67`、`a4b606c` 是本段的 docs commit，`156298a`、`8172104` 是 controller 的 spec／plan commit。
- 新增的 mixin：
  - testmod：`ChunkCheckpointScopeFaultMixin`（登記在 `quantumchamber-test.mixins.json`），理由見下方「GameTest」。M4 recovery probe 專用的 `M4RecoveryRegionWriteFaultMixin` 與兩個 drain mixin 沒有修改。
  - main（fix round 2）：`RegionBasedStorageEvictionFailureMixin`，以及兩個 accessor `StorageIoWorkerRegionStorageAccessor`、`ChunkPosKeyedStorageWorkerAccessor`，都登記在 `quantumchamber.mixins.json`。
- Review：
  - spec review 判定實作符合 plan，spec 用語由 controller 修為 v5.3（spec §20.7）。
  - fix round 1 的 spec re-review 通過，controller 另修 spec 用語（`8172104`）。
  - code quality review：Important 1、Minor 6，由 fix round 2 處理。fix round 2 的 re-review：Critical 0、Important 0、Minor 1（本 note 界線段的逐出描述與 M-1 後果說明，已在 controller 的 docs commit 修正）；M-1 的偏離（失敗 receipt 留在 `pending`）判定比原建議正確。H2 完成。

### Code-truth（`f362ebe`）

判定單位是「同一 storage 的同一個 region 檔」（spec v5.3 §4 H2）。原因：region 檔的 header 由同一 region 的 32×32 chunks 共用，`RegionFile.writeChunk` 先寫資料、再寫 header，所以無關 chunk 的 header 寫入失敗可能波及相關 chunk。

失敗記錄 `NativeSaveFailures`：

- 狀態：
  - 單調遞增的 `revision`。
  - `regionFailures`：region → 最近一次失敗時的 revision。
  - `storageFailure`：最近一次沒有 chunk 位置的失敗時的 revision。
- region key：
  - 寫法與原版 `RegionBasedStorage` 選 region 檔的方式相同：`ChunkPos.toLong(getRegionX(), getRegionZ())`，也就是 chunk 座標 `>> 5`（`:90`）。
  - 記錄直接以 region 為 key，不先記 chunk 再換算。判定只看「region 內最近一次失敗是否晚於 before」，兩種記法結果相同；以 region 為 key 比較簡單，筆數上限也降為曾經失敗的不同 region 數。
- 寫入：
  - `observe(future, chunk)` 與 `fail(chunk)`（`:38`）記到該 chunk 所在的 region。
  - `observeFlush(future)` 與 `failStorage()`（`:43`）記為整份失敗。
- observer（`track`，`:48`）：
  - 先記錄失敗，再完成並移除 receipt。
  - 記錄本身丟例外時（`:56`），receipt 以例外完成並**留在 `pending`**，之後每一次 verify 都拒絕。
  - `track` 為套件內可見，只為了測試這條路徑。
- `verify(before, chunks, source)`（`:68`）：
  1. 先等所有已受理的 observer 完成。barrier 語意與 H2 之前相同，其他 region 的 observer 也要等；receipt 以例外完成時拒絕。
  2. 接著判定：`storageFailure > before` 一律拒絕；`chunks` 所在任一 region 的失敗 revision `> before` 就拒絕；其他 region 的失敗不影響。
  3. `source` 是穩定的來源描述，會放進例外訊息，見下方「呼叫端與語意」。
- 移除不帶位置的 `observe(future)` 與 `verify(before)`，所有呼叫點都必須帶位置，或明確標為 flush。

各失敗來源都帶位置：

| 來源 | 記錄位置 | 記法 |
| --- | --- | --- |
| `MinecraftServer.onChunkSaveFailure(error, key, pos)`（含序列化失敗與 chunk、entities、poi 的非同步寫入失敗） | `MinecraftServerSaveFailureMixin` | 依 `key.dimension()` 分世界，每個世界一份 `NativeSaveFailures`，呼叫 `fail(pos)`；`pos` 為 null 時 `failStorage()`。同一世界的三種 storage 合併計入，同樣以 region 判定，比「同一 storage」更保守。新增介面方法 `NativeSaveFailureAccess.quantumchamber$saveFailures(RegistryKey<World>)` |
| `StorageIoWorker.setResult(pos, …)` | `StorageIoWorkerSaveFailureMixin` | 每個 worker 一份，`observe(future, pos)` |
| `StorageIoWorker.completeAll(sync)` | 同上 | `observeFlush`：沒有 chunk 位置，失敗時視為該 worker 全部 region 都失敗（fail-closed） |
| `EntityChunkDataAccess.handleSaveFailure(future, pos)` | `EntityChunkRecoverySaveMixin` | 每個 entity storage 一份，`observe(future, pos)` |
| `RegionBasedStorage.getRegionFile` 中，快取滿 256 個 region 檔時逐出用的 `RegionFile.close()`（fillLastSector＋`force(true)`） | `RegionBasedStorageEvictionFailureMixin` | 每個 RegionBasedStorage 一份。close 丟例外時 `failStorage()`（整份 storage 失敗，fail-closed），再原樣重拋，原版行為不變 |

- 全伺服器累計次數 `quantumchamber$saveFailureRevision()` 保留，只供診斷與既有 probe（`M4CandidateRecoveryProbe.java:569`、`:573`），checkpoint 不再使用。
- 不同世界的相同 region 座標不會混在一起：server 端依 dimension 分開，worker、entity storage 與 RegionBasedStorage 本來就是每個世界各自一份。
- region 檔快取逐出（quality review I-1）：
  - 問題：原版逐出時的 close 例外丟給正在讀寫的另一個 chunk U，被記到 U 的 region；barrier 的 `completeAll(true)` → `sync()` 又只走訪仍在快取中的 region 檔。所以相關 region R 被逐出時若 fsync 失敗，H2 fix round 1 會承認，這是 fail-open。
  - 修法：在 `getRegionFile` 唯一一個 `RegionFile.close()` INVOKE 上加 `@WrapOperation`（`require=1`）。javap 確認 1.21 Yarn build.9 的 `getRegionFile` 只有一個這種 INVOKE（offset 56），所以將來開啟 `defaultRequire: 1` 也只會命中一次。
  - 涵蓋讀取與寫入觸發的逐出。不精準到被逐出的 region，任何逐出失敗都視為整份 storage 失敗。

`ChunkSaveCheckpoint`（通用元件，F4b 的投影會再用，受同樣的界線約束）：

- 前置條件寫在類別 Javadoc：
  - 在 server thread 呼叫。
  - 每個相關 chunk 都已載入為 FULL 的 WorldChunk，而且它的 entity storage 已載入。
  - 每次呼叫都會做一次全伺服器 flush，重試與退避由呼叫端負責。
- `ready(ServerWorld, Set<ChunkPos>)`（`:37`，quality review M-5）：
  - 判斷方式與 `save` 的守門相同，共用同一個 `loaded`（`:60`）。空集合回 false。
  - F4b 可以先問 `ready` 再呼叫 `save`；未就緒時 `save` 仍丟 IllegalStateException，不做任何存檔。
  - 選這個方式而不另訂例外型別：呼叫端可以在不觸發全伺服器 flush 的情況下先等待就緒，也不改變現有 `CorridorPageManager` 對例外的處理。
- `save(MinecraftServer, Map<ServerWorld, Set<ChunkPos>>)`（`:41`）：
  1. 守門與基準：
     - 必須在 server thread；map 不得為空。
     - 每個世界必須屬於此 server，相關 chunk 集合不得為空。
     - 每個相關 chunk 與它的 entity storage 都必須已載入（`:73`）。
     - 接著取得五份記錄各自的 `before`：
       - chunk worker 與 entity storage 的寫入／flush 記錄。
       - 它們底下 RegionBasedStorage 的逐出記錄。entity 這份經 `EntityRecoverySaveAccess.quantumchamber$storageWorker()` 與兩個 accessor 取得。
       - server 端該世界的記錄。
  2. 存檔與比對：
     1. 重新標髒（`setNeedsSaving(true)`＋`markEntitiesForResave`）。
     2. `server.save(false,true,true)`，回 false 視為失敗（`:50`）。
     3. 每個世界做 barrier（`completeAll(true).join()`、`awaitAll(true)`）。
     4. 每個世界 verify 五份記錄，只看該世界相關 chunks 所在的 region。寫入與逐出記錄成對包在套件內可見的 `StorageReceipt`（`:87`），`Context.verify` 在 `:103`。
  3. 任何例外都重新標髒後重丟。

`MeasuredWorldSaveCheckpoint.save`：

- phase 守門不變，只接受 MEASURED 的 RELEASE_GEOMETRY（`:18`）。spec §13.2 的「也接受 COLLAPSING」不在 H2 範圍。
- 相關範圍不變：來源世界取 `ChamberGeometry.bounds` 涵蓋的 chunks，走廊世界取所有 lease 涵蓋的 chunks。以 `LinkedHashMap`（來源、走廊）呼叫 `ChunkSaveCheckpoint.save`（`:26`）。
- 另加 `source==corridor` 守門（`:22`）。record 建構子已保證來源是原版世界，所以實際上不會觸發；只是避免 map 合併時悄悄少檢查一個世界。

呼叫端與語意：

- `CorridorPageManager` 沒有修改：per-space 20→1200 tick 退避、WARN 去重與恢復時的 INFO 都照舊。
- 例外訊息（quality review M-4）：
  - 帶穩定的來源 label、世界 key，以及第一個失敗的相關 region 座標。多個相關 region 都失敗時取座標最小者，與集合走訪順序無關。
  - 不含 revision、tick 等每次都變的值，所以 WARN 依 class＋message 去重仍有效：同一 region、同一來源反覆失敗只記一次 WARN。
  - 來源 label：`chunk`、`entities`、`regionCache:chunk`、`regionCache:entities`、`server`。
  - 格式：
    - 「相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint：source=<label> world=<world key> region=[x, z]」
    - 「原生 storage 已回報沒有 chunk 位置的失敗（flush 或 region 檔快取逐出），視為全部 region，拒絕承認 checkpoint：source=<label> world=<world key>」
    - 「原生存檔失敗的記錄本身失敗（無法判斷是否相關），拒絕承認 checkpoint：source=<label> world=<world key>」，附 cause。
  - 其餘訊息沿用 H2 之前的文字。舊訊息「原生 storage write／flush 已回報失敗，拒絕承認幾何清理」與「原生 chunk／entity 序列化或存檔失敗，保留 MEASURED leases」已移除，沒有測試或腳本比對它們。
- 不再阻擋的：其他 region、其他世界的失敗，前提是在原版 flush 之前就已完成（例如較早受理且已完成的寫入、同步的序列化失敗、非 flush 存檔或卸載時的失敗）。
- 仍維持 fail-closed 的情況（一律拒絕並重新標髒，呼叫端保留 leases 並退避）：
  - 相關 region 的失敗，包含同一 region 內的無關 chunk。
  - `completeAll` 失敗。
  - region 檔快取逐出時的 close 失敗，不論被逐出的是哪個 region。
  - 失敗記錄本身失敗。
  - `server.save` 丟例外或回 false。
  - barrier 的 join 丟例外。
- `NativeSaveFailures` 只在上述 mixin 與 `ChunkSaveCheckpoint` 使用。玩家 checkpoint 不使用它，語意不受影響。

界線（spec v5.3 §4 H2，不繞過）：

- region header：同一 region 內任一 chunk 的失敗都視為相關（見上方 Code-truth）。這偏向 fail-closed：與相關 chunks 同 region 的無關 chunk 反覆失敗時，清理仍會依退避一直重試。
- 原版 pending 寫入：
  - 情況：寫入在原版 flush 時若仍 pending，原版會 join 到失敗，讓外層 `server.save` 直接丟例外，checkpoint 一定失敗，不論它在哪個 region、哪個世界。前一個世界的 `save` 丟例外後，後面的世界也不會存檔。
  - 原版程式：涉及 `VersionedChunkStorage.completeAll()` 的 `join` 與 `EntityChunkDataAccess.awaitAll` 的 `join`；以 javap 確認 `ServerWorld.save` 與 `MinecraftServer.save` 都沒有 catch。
  - 定位：這是原版語意的界線，不比原本的全伺服器計數更糟。要完全消除，就得改用 `server.save` 以外的存檔路徑，超出 plan 的指定範圍。
- region 檔快取逐出：以整份 storage 判定。checkpoint 取得 `before` 之後發生的 region 檔快取逐出失敗，不論被逐出的是哪個 region，都讓該次 checkpoint 拒絕；`before` 之前的逐出失敗不計，不會永久擋住。
- 記錄本身失敗（quality review M-1）：observer 記錄失敗時，receipt 以例外完成並留在 `pending`，之後該 storage 在本 JVM 內的 checkpoint 都會拒絕，直到重啟。實際只有 OOM 類 VM error 會觸發；若發生在走廊世界，所有 MEASURED 清理都會保留 leases，重啟後 receipt 仍帶 leases，可以重做。
- POI（quality review M-6）：POI storage 沒有 observer 與 barrier，它的非同步寫入失敗只在原版 callback 執行時經 server 的 `onChunkSaveFailure` 記錄，屬 best-effort。在 verify 之後才完成的 POI 失敗不會被看見。這點寫在 `ChunkSaveCheckpoint` 與 `NativeSaveFailureAccess` 的 Javadoc。
- 記錄的生命週期：
  - `regionFailures` 只在失敗時新增，key 數量上限是曾經失敗的不同 region 數，沒有修剪。
  - server 端的 `quantumchamber$worldFailures` 每個曾回報失敗或被查詢過的世界各留一份，世界卸載時不移除（quality review 的觀察項）。每份只有 region 記錄與兩個計數，數量上限是本次執行中載入過的世界數。移除需要另外掛世界卸載的時點，不屬 H2 的檔案範圍，所以未做。

### 測試（`f362ebe`）

JUnit `NativeSaveFailuresTest`：18 個（H2 前 4 個，首輪 13 個，fix round 1 15 個）。`verify` 都帶來源描述 `source=chunk world=minecraft:overworld`。chunk 一律以 `ChunkPos.toLong` 表示：

| 常數 | chunk | region |
| --- | --- | --- |
| `RELEVANT` | (3,-7) | (0,-1) |
| `OTHER_RELEVANT` | (4,-7) | (0,-1) |
| `SAME_REGION` | (31,-32) | (0,-1) |
| `OTHER_REGION` | (40,12) | (1,0) |

- 原有 4 個改為新 API，相關 chunk 照舊判定：吞掉的失敗仍使收據失效、barrier 等待延後的失敗、已完成的失敗不會遺失、上一輪失敗後的新成功可被承認。
- 其他 region 不阻擋（3 個，首輪的同名 test 改以 region 命名）：
  - `otherRegionWriteFailureDoesNotBlockRelevantChunks`：同一筆失敗若納入相關範圍，仍必須拒絕。
  - `otherRegionReportedFailureDoesNotBlockRelevantChunks`。
  - `barrierWaitsForOtherRegionAsyncFailureWithoutBlocking`：observer 完成前，barrier 不得返回。
- region 判定（fix round 1 新增 2 個）：
  - `sameRegionUnrelatedChunkFailureFailsClosed`：同 region 無關 chunk 的寫入失敗與同步回報失敗都必須拒絕。
  - `regionBoundaryMatchesVanillaRegionFiles`：5 組含負座標的邊界，先斷言 fixture 與 `ChunkPos.getRegionX／Z` 一致。擋下 2 組：(-1,5) 對 (-32,31)、(31,0) 對 (0,31)。不擋 3 組：(0,5) 對 (-1,5)、(32,31) 對 (31,31)、(5,-33) 對 (5,-32)。
- fail-closed 對照（6 個）：
  - 相關 chunk 寫入失敗與同步回報失敗。
  - flush 失敗對任何 chunk 集合（含空集合）都拒絕。
  - barrier 等待延後的 flush 失敗。
  - snapshot 之前的失敗不計，同一 chunk 之後再失敗就計入。
  - 兩份 storage 的相同 chunk 座標互不影響。
- fix round 2 新增 3 個：
  - `observerRecordingFailureFailsClosedPermanently`（M-1）：
    - 以 `track` 傳入會丟例外的記錄動作。
    - verify 必須拒絕；之後以新的 `before` 再 verify，也必須拒絕。
  - `failureMessagesNameSourceWorldAndRegionStably`（M-4）：
    - 兩個相關 region 都失敗時，訊息逐字等於取座標最小的 region。
    - 再次失敗使 revision 改變後，訊息逐字不變。
    - 沒有位置的失敗，訊息逐字帶 `source=regionCache:chunk`。
  - `regionCacheEvictionCloseFailureFailsClosed`（I-1）：在暫存目錄建立真的 `StorageIoWorker`（含 production mixin），流程如下：
    1. 先寫相關 region (0,0)，再寫 255 個其他 region，讓快取恰好 256 個且相關 region 是最久未用的。
    2. 關掉相關 region 檔的 `FileChannel`。
    3. 寫第 257 個 region 的 chunk，觸發逐出。
    4. 斷言觸發的 chunk 拿到 IOException、相關 region 已不在快取、`StorageReceipt.verify` 必須拒絕。
- JUnit 在 `fabric-loader-junit` 下會套用 mixin：先以暫時的探針 test 確認 `StorageIoWorker` 實例實作 `NativeStorageSaveAccess`，確認後移除探針，測試檔的 SHA-256 與探針前相同。

GameTest `native_measured_checkpoint_ignores_unrelated_chunk_failure_windows`（batch `m5_h2_checkpoint_scope`，`M4CandidateDoorGameTests.java:421`）。fix round 1 沿用同一個 test、不新增 GameTest，理由見「Fix round 1」。fix round 2 在同一個 test 加上：

- 前兩次的受控 write 次數各至少 2，也就是 chunk 與 entities 兩份 storage 都寫了（`:456`）。
- WARN 的 LogCapture 斷言（`:462`），比照既有退避 test：
  - 本 sid 只有 1 筆「MEASURED 保留 receipt 等待安全返還」，並附 stack。
  - thrown message 逐字等於「相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint：source=chunk world=<來源世界> region=[<controller 的 region>]」。
  - 沒有「前一失敗原因另重複」行。
  - 兩次失敗是同一 region、同一來源，所以這同時鎖住 M-4 訊息的穩定性。
- `CheckpointScopeProbe.release()`（`:578`）把解除強制載入放進 `finally`（M-2）。

- 準備：
  1. 真側門 checked SELECTED。
  2. probe 依 production 的來源艙範圍算出相關 chunks 與它們的 region，再選三個故障目標，都在來源世界：
     - 相關：controller 所在 chunk。
     - 同 region：與 controller 同 region、不在來源艙範圍、chunk 與 entity storage 都已載入的鄰近 chunk。
     - 其他 region：沿四個軸向最近、不在任何相關 region 的 chunk。原本未強制載入時由 probe 強制載入，收尾解除。
  3. 等其他 region 的 chunk 與 entity storage 都載入後，才移除 Buff 讓玩家返還。
- 三次 checkpoint 依序注入：
  1. 相關 chunk。
  2. 同 region 的無關 chunk。
  3. 其他 region 的 chunk。

  第 2、3 次由 hook 自行把目標 chunk 標髒，並撤銷它的 entity 空快取。注入都是讓原生 region write（chunk＋entities）真的失敗。
- 判定：
  - 第 1、2 次：故障確實觸發（chunk、entities 兩種）且未被承認。第 2、3 次開始前，flushed receipt 與 leases 原樣保留，runtime space 仍在。
  - 退避間隔 20 → 40 tick。
  - 第 3 次：故障確實觸發（chunk、entities），production 的 server 計數至少 +2、來源 chunk worker 計數至少 +1，checkpoint 被承認。
  - 恰好三次呼叫，之後完成 DORMANT，selection receipt 保留，玩家回到來源，probe 的強制載入已解除。
  - 收尾移除本案例自建的 DORMANT receipt。
- 故障注入 `ChunkCheckpointScopeFaultMixin`（`StorageIoWorker`）：
  - 以 `@WrapOperation` 在 `StorageIoWorker.write` 內、原生 try 範圍中的 `RegionBasedStorage.write` 前丟出 `IOException`。由 worker 自己 `completeExceptionally`，不寫入 region 檔。
  - 在 `setResult` RETURN 只對故障目標等真 IO 完成，讓失敗 entry 不會留到原版 `completeAll`，外層 `server.save` 照常回 true（見上方「界線」）。
  - 只比對 (dimension, chunk, type ∈ {chunk, entities})。
- 為什麼新增 mixin：M4 的 region write／drain mixin 由 `M4CandidateRecoveryProbe` 的靜態 fault 驅動，只用在 M4 recovery probe；派工規定不得改變它們的行為。
- `MeasuredCheckpointFaultMixin` 只多一個 RETURN hook，用來記錄 checkpoint 被承認的那一次；原 HEAD hook 與既有退避測試的行為不變。
- GameTest 只使用 HEAD 已有的 API（全伺服器計數、worker `revision()`），所以同一份測試碼可以在 HEAD 上跑 RED。

### Gate 與證據（H2 首輪，`f7ac878`）

首輪的判定單位是 chunk，fix round 1 改為 region。gate root 都在 `.superpowers/verification/`；逐輪的 state、console、diff 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/h2/`。

diff hash 是 `git diff f6be3ed f7ac878 -- <path>` 輸出的 SHA-256：

| 路徑 | SHA-256 |
| --- | --- |
| `src` | `05f9f336…` |
| `src/main` | `c484bb05…` |
| `src/test` | `444adbd1…` |
| `src/testmod` | `5e1cc4dc…` |

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（GameTest）：production 維持 HEAD，只加 testmod | `f6be3ed`；只有 4 個 testmod 檔有變更（`h2/red-gametest-state.txt`），testmod blob 與 `f7ac878` 相同 | Full `gate-h2-red-gametest-a47b67d9ac6144408fae5b5bb03792ba` | 預期 FAIL：147 個中只有新 test 失敗（JUnit 416 全過）。witness 只有 `unrelatedFailureAccepted=false` 與 `noThirdCall=false`；相關 chunk 故障、fail-closed、leases 保留、20 tick 退避、無關故障觸發，以及 production 計數（server +2、worker +1）都是 true。第二次呼叫被拒絕，第三次在 +40 tick 重試；HEAD 的 WARN 原因是「原生 storage write／flush 已回報失敗，拒絕承認幾何清理」 |
| RED（JUnit）：新測試＋暫時 adapter（新 API 委派到舊的整份計數語意） | `f6be3ed`；`NativeSaveFailures.java`、`NativeSaveFailuresTest.java` 加上上一列的 testmod 變更（`h2/red-junit-state.txt`） | focused JUnit `NativeSaveFailuresTest` | 預期 FAIL：13 個中 3 個失敗，就是 3 個 H2 規則 test，都丟出舊訊息「原生 storage write／flush 已回報失敗…」；10 個對照 test 通過。GREEN 移除 adapter |
| GREEN focused | 工作樹＝`f7ac878` | `compileTestmodJava`＋`dev.quantumchamber.persistence.*` | PASS：115 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`）；`NativeSaveFailuresTest` 13/13 |
| GREEN Full | 同上 | `gate-h2-green-full-1a04c539069941b5bae315fe7181a55e` | PASS |
| GREEN Windows | 同上 | `gate-h2-green-windows-72d0b3f99f6247cab40254e9b28138dd` | PASS：3 個 Windows checkpoint class 共 17 個，0 failure |
| GREEN Main＋oracle | 同上 | `gate-h2-green-main-81af73957bd844d090a57e41fc6ee63d` | PASS：main-only oracle 三層 testmod 命中 0、world keys exact、正常停止、exact restore |
| GREEN M4 recovery | 同上 | `recovery-run-5933d69b350941f19eaf73c79cf4733c.json` | PASS |

gate 在 commit 之前以工作樹執行。執行前記錄 12 個檔案的 `git hash-object`（`h2/green-state.txt`），與 `f7ac878` 中各檔的 blob id 逐一相同（`h2/commit-hashes.txt`）。

GREEN 數字：

- default GameTest 147/147（`_windows` 74），0 failure／error／skipped。比 H1 多 1 個，就是本 task 新增的 GameTest。boundary oracle PASS；per-test receipt 29/29 PASS。
- JUnit 425，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 H1 的 416 多 9 個，就是 `NativeSaveFailuresTest` 新增的 9 個。
- JAR：release 300 entries（257 classes），sources 191。比 H1 各多 1：新增 `ChunkSaveCheckpoint`（含 `$Context`），移除 `MeasuredWorldSaveCheckpoint$Context`。testmod 命中只有合法的 `ModPresenceProbe`。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。`native-write-fail` 照舊 PASS：外層 save 正常返回、366 次真 region 寫入失敗、storage 類型涵蓋 superposition 與 overworld 的 chunk 與 entities，leases 與資源保留。

### Fix round 1（`14fc189`）

依 spec v5.3 §4 H2 與 §20.7（controller commit `156298a`）處理：

1. 判定單位改為「同一 storage 的同一個 region 檔」。記錄也一併改以 region 為 key，理由見 Code-truth。
2. 界線維持：原版 flush 時仍 pending 的寫入失敗由原版 join 傳出，仍 fail-closed，不繞過。
3. GameTest 改為三次呼叫，順序是相關 → 同 region 的無關 chunk → 其他 region。
   - 被承認的 checkpoint 會結束 MEASURED 清理，所以會擋下的情形必須排在被承認之前。
   - 同一個 MEASURED session 內就能依序證明三個性質，也順便驗到連續兩次失敗的退避 20 → 40。
   - 共用同一套 probe 與收尾，不新增 batch。GameTest 數量不變。
4. 依判定單位的改動同步更新 javadoc 與註解（`ChunkSaveCheckpoint`、`MeasuredWorldSaveCheckpoint`、`NativeSaveFailureAccess`、兩個 mixin），沒有其他行為變更。`MeasuredCheckpointFaultMixin`、`ChunkCheckpointScopeFaultMixin`、`EntityChunkRecoverySaveMixin` 沒有修改。

逐輪證據在 `h2/fix1/`。diff hash 是 `git diff 156298a 14fc189 -- <path>` 輸出的 SHA-256：

| 路徑 | SHA-256 |
| --- | --- |
| `src` | `f642a651…` |
| `src/main` | `4ccd5b26…` |
| `src/test` | `0b70cd5e…` |
| `src/testmod` | `aa2b2c08…`（與 GameTest RED 的 diff 相同） |

H2 全部 code（`git diff f6be3ed 14fc189 -- src`）為 `dd0a1e3d…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（JUnit）：新增 region 測試，production 維持 `f7ac878` 的 chunk 判定 | `156298a`；只有 `NativeSaveFailuresTest.java` 與 `M4CandidateDoorGameTests.java` 有變更（`h2/fix1/red-state.txt`） | focused JUnit `NativeSaveFailuresTest` | 預期 FAIL：15 個中 2 個失敗，都是「Expected IllegalStateException … nothing was thrown」。一個是 `sameRegionUnrelatedChunkFailureFailsClosed`，另一個是 `regionBoundaryMatchesVanillaRegionFiles` 中會擋下的 2 組；不擋的 3 組與其餘 13 個 test 都通過 |
| RED（GameTest）：production 維持 `f7ac878`，只改 testmod | `156298a`；只有 `M4CandidateDoorGameTests.java` 有變更（`h2/fix1/red-gametest-state.txt`）。`NativeSaveFailuresTest.java` 暫時回到 HEAD 內容，讓 `test` 先通過；fix1 版本另存，SHA-256 `9b264467…`，跑完逐位元組放回 | Full `gate-h2-fix1-red-gametest-fe64e1e513804134bf42ea65f9062ca9` | 預期 FAIL：147 個中只有本 test 失敗（JUnit 425 全過）。第 2 次（同 region 的無關 chunk）被承認：`sameRegionFailureFailedClosed=false`，第 3 次不存在，所以其他 region 相關的 witness 也是 false。第 1 次的相關故障、fail-closed，以及第 2 次開始前的 leases 保留都成立。同 region 目標 [766825, 835548]、相關 [766826, 835549]、其他 region [766826, 835552]，最後一個由 probe 強制載入 |
| GREEN focused | 工作樹＝`14fc189` | `compileTestmodJava`＋`dev.quantumchamber.persistence.*` | PASS：117 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`）；`NativeSaveFailuresTest` 15/15 |
| GREEN Full | 同上 | `gate-h2-fix1-green-full-8543616baa81464a8ded9ea37c999cf2` | PASS。log 中本 test 的 WARN 原因是新訊息「相關 region 的原生序列化或存檔已回報失敗」，恢復時記「先前連續失敗 2 次」 |
| GREEN Windows | 同上 | `gate-h2-fix1-green-windows-07205b3634ab4db8a4414bb906089880` | PASS：17 個，0 failure |
| GREEN Main＋oracle | 同上 | `gate-h2-fix1-green-main-8cc79d9cc7b94fadabddaa48cc5197c1` | PASS：main-only oracle 三層 testmod 命中 0、world keys exact、正常停止、exact restore |
| GREEN M4 recovery | 同上 | `recovery-run-87cdd5b2aa084bd98aa17d513ead8c5d.json` | PASS |

gate 在 commit 之前以工作樹執行。執行前記錄 8 個檔案的 `git hash-object`（`h2/fix1/green-state.txt`），與 `14fc189` 中各檔的 blob id 逐一相同（`h2/fix1/commit-hashes.txt`）。

fix round 1 的 GREEN 數字：

- default GameTest 147/147（`_windows` 74），0 failure／error／skipped，與首輪相同。boundary oracle PASS；per-test receipt 29/29 PASS。
- JUnit 427，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比首輪的 425 多 2 個，就是新增的 2 個 region 判定 test。
- JAR：release 300 entries（257 classes）、sources 191，與首輪相同；testmod 命中只有合法的 `ModPresenceProbe`。
- M4 recovery 24/24 phases，24 個獨立 JVM，Gradle exit 0，正常停止，lock 已釋放。`native-write-fail` 照舊 PASS：366 次真 region 寫入失敗，涵蓋 superposition 與 overworld 的 chunk 與 entities，leases 與資源保留。

### Fix round 2（`f362ebe`）

處理 code quality review 的 Important 1、Minor 6，以及一個觀察項：

| 項目 | 處理 |
| --- | --- |
| I-1 region 檔快取逐出的 close 失敗歸錯 region | 新增 main mixin `RegionBasedStorageEvictionFailureMixin`，逐出失敗記為整份 storage 失敗；新增兩個 accessor，由 `ChunkSaveCheckpoint` 的 `StorageReceipt` 一併比對 chunk 與 entity storage 底下的這份記錄（見 Code-truth） |
| M-1 記錄失敗 | 記錄失敗時 receipt 以例外完成，verify 的 join 拒絕。與 reviewer 建議的寫法有一處不同：失敗的 receipt **不從 `pending` 移除**。若照建議在 `finally` 移除，receipt 在 verify 取快照之前就完成並移除時（checkpoint 的常見順序：失敗先完成，barrier 之後才 verify），verify 看不到它，遺失的失敗會被承認。`observerRecordingFailureFailsClosedPermanently` 鎖住這個情況 |
| M-2 收尾 | `CheckpointScopeProbe.release()` 把解除強制載入放進 `finally` |
| M-3 鎖住失敗原因 | GameTest 加 LogCapture 與受控 write 次數的斷言（見「測試」） |
| M-4 訊息 | 帶來源 label、世界 key、第一個失敗的相關 region，不含 revision（見「呼叫端與語意」） |
| M-5 F4b 的就緒判斷 | 新增 `ChunkSaveCheckpoint.ready`，類別 Javadoc 寫明前置條件、每次呼叫都是全伺服器 flush、退避由呼叫端負責（見 Code-truth） |
| M-6 POI | Javadoc 與本 note 的界線段寫明 POI 的非同步寫入失敗是 best-effort |
| 觀察：`quantumchamber$worldFailures` 不移除 | 寫進界線段，未修改程式 |

逐輪證據在 `h2/fix2/`。diff hash 是 `git diff 8172104 f362ebe -- <path>` 輸出的 SHA-256：

| 路徑 | SHA-256 |
| --- | --- |
| `src` | `ab9b2e5b…` |
| `src/main` | `ac0529a6…` |
| `src/test` | `5306fb13…` |
| `src/testmod` | `2be074a0…` |

H2 全部 code（`git diff f6be3ed f362ebe -- src`）為 `6a1a5930…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（JUnit）：新測試＋暫時 adapter | `8172104`；只有 `NativeSaveFailures.java`、`ChunkSaveCheckpoint.java`、`NativeSaveFailuresTest.java` 有變更（`h2/fix2/red-state.txt`）。adapter 不改語意：3 參數 `verify` 委派到舊的 2 參數 `verify`；`track` 改為套件內可見；`StorageReceipt` 只比對寫入記錄，等同 fix round 1。GREEN 時改寫 | focused JUnit `NativeSaveFailuresTest` | 預期 FAIL：18 個中 3 個失敗，就是 3 個新 test，其餘 15 個通過。三個失敗：`observerRecordingFailureFailsClosedPermanently` 沒有丟例外；`failureMessagesNameSourceWorldAndRegionStably` 的訊息沒有來源與 region；`regionCacheEvictionCloseFailureFailsClosed` 中，快取已滿、相關 region 是最久未用、觸發的 chunk 拿到 IOException、相關 region 已被逐出等前置斷言都成立，只有最後的 verify 沒有拒絕，也就是真的 fail-open |
| GREEN focused | 工作樹＝`f362ebe` | `compileTestmodJava`＋`dev.quantumchamber.persistence.*` | PASS：120 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`）；`NativeSaveFailuresTest` 18/18。eviction test 通過，也證明新的 `@WrapOperation` 在執行時確實命中 |
| GREEN Full | 同上 | `gate-h2-fix2-green-full-628c5475e1884084bb0d8d932d6e6633` | PASS。本 test 的 WARN 原因是「相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint：source=chunk world=minecraft:overworld region=[13859, 15499]」，兩次失敗只記一次 WARN，恢復時記「先前連續失敗 2 次」 |
| GREEN Windows | 同上 | `gate-h2-fix2-green-windows-4ab7df3f9c79430c9b7d7a3d52026017` | PASS：17 個，0 failure |
| GREEN Main＋oracle | 同上 | `gate-h2-fix2-green-main-6e4d17391f5a48979180948d98a51fa4` | PASS：main-only oracle 三層 testmod 命中 0、world keys exact、正常停止、exact restore。stdout 沒有 mixin 錯誤行；class-load 記錄有 `RegionBasedStorage` |
| GREEN M4 recovery | 同上 | `recovery-run-2345b542d9bb4f61936d5f465e2be670.json` | PASS |

gate 在 commit 之前以工作樹執行。執行前記錄 12 個檔案的 `git hash-object`（`h2/fix2/green-state.txt`），與 `f362ebe` 中各檔的 blob id 逐一相同（`h2/fix2/commit-hashes.txt`）。

fix round 2 沒有另跑 GameTest RED，理由如下：
- I-1、M-1、M-4 由上表的 JUnit RED 證明。
- M-3 的 GameTest 斷言逐字比對 WARN 訊息（含來源與 region），在 `14fc189` 上會因訊息沒有這兩項而失敗；這部分與 JUnit 的 M-4 RED 是同一個原因，所以沒有再花一輪 Full gate 取證。
- 在 `f362ebe` 上，這個斷言證明加上 region 座標後，同一原因仍然只記一次 WARN。

fix round 2 的 GREEN 數字：

- default GameTest 147/147（`_windows` 74），0 failure／error／skipped，與前一輪相同。boundary oracle PASS；per-test receipt 29/29 PASS。
- JUnit 430，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 fix round 1 的 427 多 3 個，就是新增的 3 個 test。
- JAR：release 304 entries（261 classes），比前一輪多 4 個 class：`RegionBasedStorageEvictionFailureMixin`、`StorageIoWorkerRegionStorageAccessor`、`ChunkPosKeyedStorageWorkerAccessor`、`ChunkSaveCheckpoint$StorageReceipt`。sources 194，多 3 個新檔。testmod 命中只有合法的 `ModPresenceProbe`。
- M4 recovery 24/24 phases，24 個獨立 JVM，Gradle exit 0，正常停止，lock 已釋放。`native-write-fail` 照舊 PASS：366 次真 region 寫入失敗，涵蓋 superposition 與 overworld 的 chunk 與 entities，leases 與資源保留。

### 觀察（未修改）

- `.github/workflows/build.yml` 的 `$requiredM4GameTests` 明列清單沒有加入新 test（不在本 task 的檔案清單）。新 test 名稱以 `_windows` 結尾，由同一步驟的通則（宣告必須出現在 XML 且實際通過）涵蓋。是否補進明列清單由 controller 決定。

## H3：未 checked authority 的收斂

- Commit：`1fb9c9b`（parent `611bcad`），只改派工列出的檔案：production `SessionRecoveryState.java`、`CandidateLedgerService.java`、`SessionRecoveryManager.java`、`SuperpositionSessionManager.java`；測試 `SessionRecoveryStateTest.java`、`CandidateLedgerServiceTest.java`；testmod `M4CandidateDoorGameTests.java`。
- Review：待 spec review 與 code quality review。

### Code-truth（`1fb9c9b`）

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| q1 | `SessionRecoveryState.abortUnchecked`（`:130-143`） | CAS 成立時，只有 `candidateChecked(expected)` 為假才還原。candidate authority 已 checked 的 next（legacy、與 flushed 只差返還／lease 進度、W-b 已落盤）一律不倒退並回 true；原本的 `expected.equals(durable)` 早退由這條涵蓋。CAS 不符的隔離路徑不變 |
| q2 | `CandidateLedgerService.commitChecked`、`contain`（`:146-189`） | abort 丟例外時呼叫新增的 `JournalPort.quarantine`（`:43`），真的隔離該 session；隔離也丟例外時記 ERROR。log 的處置文字取自 `Containment`（`:167-174`），依實際結果：還原（或 next 已 checked）、CAS 不符隔離、abort 失敗後隔離、兩者都失敗（等 recovery tick 隔離） |
| q3 | `SessionRecoveryState.quarantine`、`quarantinedSessions`、`convergeQuarantined`、`convergenceTarget`（`:149-184`）；`SessionRecoveryManager.convergeUnchecked`（`:116-142`） | 見下方「隔離 session 的收斂」 |
| q4 | `SessionRecoveryManager.tick`（`:74-79`）、`convergeUnchecked`；`SuperpositionSessionManager.settleUncheckedInitial`（`:206-214`）、`finishAbandoned`（`:216-231`，在 `recovery.tick` 之後呼叫） | 見下方「殘留與全域暫停」 |
| q5 | `SuperpositionSessionManager.abandonInitial`（`:165-179`）、`cancelInitial`（`:181-188`）、`warnAbandoned`（`:190-201`）；start 成功落盤時的 INFO（`:134-135`） | 見下方「放棄入場的補寫與 WARN」 |
| Nit 3 | `CandidateLedgerService.describe`（`:195-202`） | 以 identity set 走訪 cause 鏈，多節點環在有限步內停止。改為 `public static`，`SuperpositionSessionManager` 的 WARN 共用 |

**隔離 session 的收斂（q3）**：

- 隔離的語意不變：`put`／`remove` 拒絕（其他 owner 不能在未 checked 的 current 上疊寫），`writable()` 只寫上一份 flushed authority（沒有時不寫）。
- 唯一的出口是 `convergeQuarantined`。它不經 `put`：隔離 session 的 current 從未被寫出或承認，所以不需要 CAS，直接以最後 checked authority 取代 current，authority history 也還原為 `checkedAuthority`（不還原的話，history 留著未 checked 的 SELECTED，之後 SELECTABLE 的 RETURNING 會被 `sameAuthority` 拒絕）。
- 收斂目標（`convergenceTarget`，對 `SessionState` exhaustive switch，新增狀態時編譯即要求決定）：flushed 為 ARMING／SUPERPOSITION → 同一 authority 的 RETURNING（candidate context、ledger、selection 原樣，只改狀態）；RETURNING／MEASURED → flushed 原樣；沒有 flushed authority → 移除。目標先經 `validateOwnership` 驗證才改動。
- 取代後 current 已沒有未 checked authority，所以立即解除隔離並保持 dirty。任何之後的寫出（tick 的重試、其他 session、原生 autosave）帶著它都安全，也會覆寫磁碟上可能已落地但未確認的 next。
- 被丟棄的是未 checked 的 candidate／selection，以及疊在它上面的進度（只有 CAS 不符的防禦路徑會有）。丟掉的返還進度由 recovery 依既有的 checkpoint marker 冪等重做。
- 驅動者是 `SessionRecoveryManager.tick`：每個 tick 對隔離 session 呼叫 `convergeQuarantined`（`operationInProgress` 的 session 略過；驗證失敗時維持隔離、同原因只記一次 WARN），然後 checked flush。寫出成功才記 INFO「已收斂：→ checked RETURNING／checked 移除」；失敗時保持 dirty、下一 tick 重試，同原因只記一次 WARN，恢復時記 INFO。
- flushed RETURNING 落盤後，既有流程接手：`SuperpositionSessionManager` 把 runtime 改為 RETURNING，`recover` 把玩家送回原艙並寫返還進度，最後 release、移除 record。

**殘留與全域暫停（q4）**：

- `SessionRecoveryManager.tick` 不再因為 `uncheckedCandidateSessions()` 非空而整個 return。tick 在任何 checked 提交的同步 put→flush 之外執行，所以此時看得到的未 checked current 都是殘留：逐一隔離（每個 session 只記一次 WARN），再走上面的收斂；其他 session 的 disconnect 與返還照常。原本說得過滿的註解（「只剩 put→flush 的瞬間窗口」）改為說明雙重故障仍可能留下殘留，以及 tick 的處理。
- `recoveryWritesSafe()` 的語意不變（只排除隔離 session），`CorridorPageManager` 的 `tickRelease`、`fail` 等仍以它把關；殘留在下一個 recovery tick 被隔離後即恢復。
- 已知的建立點都在建立當下隔離，tick 只是保底：
  - `commitChecked`：CAS 不符（既有）與 abort 失敗（q2）。
  - start：`abandonInitial` 失敗、改走 `fail` 之後，`settleUncheckedInitial` 檢查初始 authority 是否已 checked。fallback RETURNING 已 checked 時照舊由一般返還流程處理；否則立即隔離仍留在 current 的未 checked record（同一 tick 的其他寫出也不會承認它），並記入 `abandonPending`。
- 沒有 checked authority 的殘留收斂為 checked 移除之後，`finishAbandoned` 取消 reservation（`cancelPrepared`＋`retire`）、移除 runtime 與來源票，chamber 可再使用。取消失敗時同原因只記一次 WARN，下一 tick 重試。
- 保底的界線：殘留出現到下一個 recovery tick 之間，若有其他 session 的寫出，非隔離的殘留仍會被寫出。這與 M4 相同（M4 的暫停也擋不住其他 session 的寫出），只影響未知路徑。

**放棄入場的補寫與 WARN（q5）**：

- `abandonInitial`：CAS 移除未 checked 的 ARMING → 立即 checked flush，覆寫磁碟上可能已原子寫入但未確認的 ARMING（readback 才失敗的窗口）→ 取消 reservation、移除 runtime → 回 REJECTED。
- 補寫失敗不改變放棄的結果：record 已不在 current，journal 保持 dirty，由下一次寫出覆寫；這個情況寫進 WARN 的原因。CAS 或清理失敗才改走既有的 `fail`（同上 q4）。
- WARN 帶 `describe`（含 root cause）與第一次的 stack，依 chamber＋原因去重（`abandonWarnings`，比照 `checkpointCapable`）；同原因的再次放棄只記 DEBUG。同一 chamber 之後初始 ARMING checked 落盤成功時記一次 INFO 並清除。原本 WARN 只有頂層例外（`journal checked 落盤失敗…`），看不到 root cause，而且週期 refresh 每次重試都記一則。

**M5 restore 類（spec §6）的重用**：`restoreCheckedAuthority`（`:186-192`）是不檢查 CAS 的還原核心，`abortUnchecked` 與 `convergeQuarantined` 共用。依 q1，`abortUnchecked` 只回退 candidate authority；COLLAPSING 提交、READY、終局等 candidate 三欄位不變的 restore 類寫入，`abortUnchecked` 會視為已 checked 而不回退。F2a 需要以同一核心另開「current exact 等於 expected 才還原」的入口；H3 沒有新增這個入口。另外 `convergenceTarget` 對 M5 新狀態必須明確決定收斂目標（exhaustive switch 會在編譯時要求）。

### 既有測試的處置

- `readbackFailureAfterWriteRevertsAndReturningOverwritesUnacknowledgedDisk`：未修改，語意仍成立。selected（MEASURED＋SELECTED）的 candidate authority 與 flushed（SELECTABLE）不同，q1 下照樣還原；之後的 checked RETURNING 覆寫磁碟上未確認的 MEASURED。整合審查延後清單說它「已明確接受」start 路徑在放棄後讓未確認的 ARMING 留在磁碟上，直到下一次寫出；這部分由更嚴格的行為取代：`abandonInitial` 放棄後立即補一次 checked 寫出，`native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows` 以讀檔比對證明正式檔 exact 等於 flushed、不含該 chamber 的 record。M4「M5 handoff acceptance」第 6 點的 crash 窗口縮小為「補寫也失敗，且在下一次寫出前 crash」。
- `abortUncheckedWithoutFlushedRecordRemovesCurrentAndRestoresCheckedHistory`：後半（checked 移除後同 UUID 重新放入、abort 後不能繞過 authority）原本用 legacy RETURNING record。q1 起 abort 不回退沒有 candidate authority 的 legacy record，所以改用 schema 3 candidate record 驗證同一性質，並新增 `candidateAuthorityChanges`（context、ledger 縮減、ledger 內容改變）。這個改寫在 RED 的 HEAD 上也通過。
- `native_start_initial_flush_fault_rejects_without_residue_windows`：`journalUnchanged` 不再比對 journal 檔 bytes。q5 會多一次 checked 寫出：同內容時 bytes 相同，但 fresh world 時檔案會從不存在變成存在，schema 也可能由 2 變 3。改為 records／flushed 不變，再加上 `diskEqualsFlushedWithoutChamber`（正式檔 exact 等於 flushed，且不含本 chamber 的 record）。

### 測試（`1fb9c9b`）

JUnit（+5）：

- `SessionRecoveryStateTest`：
  - `abortUncheckedNeverRollsBackCheckedCandidateAuthority`（q1）：SUPERPOSITION→RETURNING、MEASURED 的返還進度、legacy RETURNING 的返還進度，三者 abort 後 current 都不得倒退，且仍 dirty。
  - `quarantinedSessionConvergesToCheckedReturningFromFlushedAuthority`（q3）：CAS 不符隔離後，收斂目標＝上一份 flushed 推進的 RETURNING；解除隔離；落盤後讀檔等於它；之後照常寫返還進度；非隔離 session 收斂丟 `IllegalStateException`。
  - `quarantinedSessionWithoutCheckedAuthorityConvergesToCheckedRemoval`（q4）：未 checked 的初始 ARMING 已原子寫入、readback 失敗；隔離後不讓 recovery 等待、拒絕 put，隔離期間的寫出不含它；收斂為移除後 checked 寫出，讀檔只剩其他 session。
- `CandidateLedgerServiceTest`：
  - `abortFailureQuarantinesSessionSoNoLaterWriteAdmitsUncheckedAuthority`（q2）：selection 與 batch 兩條路徑，abort 丟例外後 `quarantines==1`、`quarantinedSessions()=={SESSION}`、put 拒絕，其他 session 的寫出只帶 flushed SELECTABLE。
  - `failureLogTerminatesOnMultiNodeCauseCycle`（Nit 3）：兩節點 cause 環經 batch 的 WARN 與選擇的 DEBUG 兩條 log，10 秒內結束。
- Port 新增 `abortFails`、`readFailure` 與 `quarantine`，計數 `aborts`／`quarantines`。

GameTest（+4，其中 `_windows` +3）：

- `native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows`（q5）：probe `WRITE_THEN_FAIL` 每次先把含 ARMING 的整份 journal 原子寫入正式檔再丟 readback 失敗；供電一次後再直接 `start` 一次。斷言 faults≥2、每次故障時磁碟確有 ARMING、重試 REJECTED、放棄後正式檔 exact 等於 flushed 且不含本 chamber、WARN 恰一則且含 chamber 與 root cause。
- `native_start_double_fault_residue_converges_and_releases_chamber_windows`（q4，start 路徑）：probe `DIVERGE_ONCE` 在第一次初始 flush 失敗前，於 current 疊上同 authority 的 RETURNING，讓 `abandonInitial` 的 CAS 必定不符；斷言殘留在 current、沒有 flushed、recovery 未暫停、chamber 被佔用，之後收斂為 checked 移除，space／runtime／來源票都釋放，玩家留在原艙且不被凍結。
- `unchecked_residue_is_quarantined_and_removed_without_pausing_recovery`（q4，tick 保底）：直接 put 一筆沒有 flushed authority 的 synthetic ARMING；斷言下一 tick 已被隔離並 checked 移除（current、flushed、正式檔都沒有），`recoveryWritesSafe()` 為真。不用原生玩家，名稱不帶 `_windows`，各平台都執行。
- `native_quarantined_selection_converges_to_checked_returning_windows`（q3）：在真實 SUPERPOSITION session 上 put 未 checked 的 SELECTED，再疊返還進度，以原 next 呼叫 abort（CAS 不符而隔離）；斷言隔離中 put 拒絕、recovery 未暫停，之後 flushed 為同 authority 的 RETURNING＋SELECTABLE、current 等於 flushed、正式檔等於 flushed；`trustedTeardown` 驗證玩家回到原艙完整 bbox、record 已 checked 移除、保護解除。
- 新增 `whenWithinTicks`：以 server tick 計數的有界等待。GameTest server 不以 20 TPS 節流，原本 wall-clock 的 `whenWithin` 在 RED 時晚於 100000 tick 的 tickLimit 才到期，診斷訊息被通用逾時蓋掉（見 RED round 1）。

### Gate 與證據

gate root 都在 `.superpowers/verification/`；逐輪的 state、console、diff 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/h3/`。GameTest RED 以 `JAVA_TOOL_OPTIONS=-Dquantumchamber.gametest.onlyBatches=m5_h3_start_readback_fault,m5_h3_start_double_fault,m5_h3_unchecked_residue,m5_h3_quarantine_converges` 只跑 4 個新 batch（`M4LegacyGameTestFilterMixin` 的 RED 取證子集），避免 HEAD 上卡住的隔離 session 影響其他 test；production 與 JUnit 測試檔都是 HEAD 內容。

diff hash 是 `git diff 611bcad 1fb9c9b -- <path>` 輸出的 SHA-256：

| 路徑 | SHA-256 |
| --- | --- |
| `src` | `f67f2be4…` |
| `src/main` | `5ec8f4ed…` |
| `src/test` | `d970ae9b…` |
| `src/testmod` | `a54209cd…（與 RED round 2 的 testmod diff 相同）` |

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED GameTest round 1 | `611bcad`；只有 `M4CandidateDoorGameTests.java` 有變更（`h3/red-gametest-state.txt`，diff `d7586c4c…`） | Full＋onlyBatches `gate-h3-red-gametest-60539e80b7444e90b17647554f51d906` | 預期 FAIL：4/4（JUnit 430 全過）。q5 的 witness：`diskOverwrittenExactlyFlushed=false`、`singleWarning=false`、`warningNamesChamberAndRootCause=false`（faults=3、3 則 WARN，只有頂層例外）。其餘 3 個是「Didn't succeed or fail within 100000 ticks」：wall-clock 期限晚於 tickLimit，診斷訊息被蓋掉，所以改用 tick 計數重跑 |
| RED GameTest round 2 | 同上，testmod 改用 `whenWithinTicks`（`h3/red2-gametest-state.txt`，diff `a54209cd…`，與 `1fb9c9b` 的 testmod 相同） | Full＋onlyBatches `gate-h3-red2-gametest-efc2a19c133b45eca23c08a87936a168` | 預期 FAIL：4/4（JUnit 430 全過）。q4 tick：「至 tick 102：current=true durable=false recoveryWritesSafe=false」（HEAD 全域暫停、殘留留在 current）；q5：同 round 1；q3：「至 tick 1326：durable=SUPERPOSITION current=MEASURED」（隔離後沒有出口）；q4 start：「至 tick 103：presence=RETURNING current=RETURNING durable=false」（殘留卡在隔離、chamber 不釋放）。HEAD 上卡住的 q3 session 每次 reconcile 都記一則「艙體 session 協調失敗」WARN（round 1 共 8329 則），只在 RED run |
| RED JUnit：新測試＋暫時 adapter | `611bcad`；production 2 檔＋測試 2 檔＋上列 testmod（`h3/red-junit-state.txt`，diff `e32632b5…`）。adapter 不改語意：`quarantine` 回 false 且不隔離、`quarantinedSessions` 回副本、`convergeQuarantined` 丟 `UnsupportedOperationException`、`JournalPort.quarantine` 只有介面與委派，`commitChecked` 不呼叫。GREEN 時改寫 | focused JUnit `SessionRecoveryStateTest`＋`CandidateLedgerServiceTest` | 預期 FAIL：63 個中 5 個失敗，就是 5 個新 test：q1 三個 fixture 都被回退；q2 `quarantines` expected 1 but 0；q3 adapter 例外；q4 `quarantine` expected true；Nit 3 timed out after 10000 ms。改寫後的 `abortUncheckedWithoutFlushedRecord…` 通過 |
| GREEN focused | 工作樹＝`1fb9c9b` | `compileTestmodJava`＋`dev.quantumchamber.persistence.*`＋`dev.quantumchamber.candidate.*` | PASS：174 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`） |
| GREEN Full | 同上 | `gate-h3-green-full-af56441caaea47a292fc7a811afa1162` | PASS |
| GREEN Legacy | 同上 | `gate-h3-green-legacy-8b0052ea3af9437ab6e7270ada092b76` | PASS |
| GREEN M4 recovery | 同上 | `recovery-run-494c9aa995d740f4bf84aa379e7beed0.json` | PASS |
| GREEN Full（第二次，確認下方觀察） | 同上 | `gate-h3-green-full2-88337a4689554ea3b154fff24044c52a` | PASS，數字與第一次相同 |

gate 在 commit 之前以工作樹執行。執行前記錄 7 個檔案的 `git hash-object`（`h3/green-state.txt`），與 `1fb9c9b` 中各檔的 blob id 逐一相同（`h3/commit-hashes.txt`）。

GREEN 數字：

- default GameTest 151/151（`_windows` 77），0 failure／error／skipped。比 H2 多 4 個，就是本 task 新增的 4 個 GameTest（3 個 `_windows`、1 個跨平台）。boundary oracle PASS；per-test receipt 33/33 PASS。
- JUnit 435，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 H2 的 430 多 5 個，就是本 task 新增的 5 個 test。
- JAR：release 306 entries（263 classes），比 H2 多 2 個 class：`CandidateLedgerService$Containment`，以及 `SessionRecoveryState$1`（`convergenceTarget` 的 enum switch map）。sources 194 不變。testmod 命中只有合法的 `ModPresenceProbe`。
- legacy 1/1，per-test receipt 1/1 PASS。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。

GREEN log 中的 H3 行（`latest.log`）：CAS 不符的隔離 WARN 之後，q3 記「未 checked candidate authority 已收斂：→ checked RETURNING」；q4 start 路徑記「→ checked 移除」與「初始 ARMING 殘留已 checked 移除，取消 reservation 並釋放 chamber」；q4 tick 保底記「recovery 發現未 checked candidate authority 殘留，隔離」與「→ checked 移除」；q5 的 WARN 帶 chamber 與 root cause（`… <- java.io.IOException: M5 H3 GameTest 受控 readback 失敗（ARMING 已寫入正式檔）`）。沒有其他 session 被隔離或收斂。

### 觀察（未修改）

- 第一次 GREEN Full 中，`m2_native_shared_west`（M2 的 test，不是 H3 的）的 `recover` 寫出一次失敗，記一則 WARN「返還保留 pending … `journal checked 落盤失敗`」，下一 tick 重試成功、test PASS。前後沒有任何 session 被隔離或收斂，之前 8 個 Full gate 都沒有這一行。第二次 Full 的 batch 順序相同，這一行沒有再出現，所以判定為非決定性的暫態寫出失敗。WARN 只記例外 class 與 message，看不到 root cause（既有的 `SessionRecoveryManager` 寫法，不在本 task 修改）。
- `.github/workflows/build.yml` 的 `$requiredM4GameTests` 明列清單沒有加入新的 3 個 `_windows` test（不在本 task 的檔案清單），由同一步驟的通則涵蓋；新的跨平台 test 不在 Ubuntu 的 `$requiredCrossPlatform` 清單。是否補進明列清單由 controller 決定。
- q5 的補寫在同一 chamber 反覆放棄時，每次放棄都會多一次 journal checked 寫出（與入場嘗試同頻率，週期 refresh 約每 20 tick 一次）。持續 IO 故障時補寫本身也失敗，只記一次 WARN 原因。
