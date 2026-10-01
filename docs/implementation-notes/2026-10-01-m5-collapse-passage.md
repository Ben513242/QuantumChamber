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

- Commits：
  - `1fb9c9b`（H3 首輪，parent `611bcad`），只改派工列出的檔案：production `SessionRecoveryState.java`、`CandidateLedgerService.java`、`SessionRecoveryManager.java`、`SuperpositionSessionManager.java`；測試 `SessionRecoveryStateTest.java`、`CandidateLedgerServiceTest.java`；testmod `M4CandidateDoorGameTests.java`。`6a3d56d` 是本段的 docs commit。
  - `3957624`（fix round 1，parent `6a3d56d`）：同上 7 檔，另新增派工允許的 `persistence/FailureText.java` 與 `FailureTextTest.java`。內容見下方「Fix round 1」。
- Review：
  - 首輪 spec review：✅，Minor 2。
  - 首輪 code quality review：Critical 0、Important 1、Minor 8。fix round 1（`3957624`）處理 Important 1 與 Minor 1、2、4–8；Minor 3（收斂寫出每 tick 重試、沒有退避）依 controller 裁定不在本 task 處理，記為延後項（journal 層級共用節流）。
  - fix round 1 的 re-review：Critical 0、Important 0、Minor 0。H3 完成。

### Code-truth（`3957624`）

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| q1 | `SessionRecoveryState.abortUnchecked`（`:132-145`） | CAS 成立時，只有 `candidateChecked(expected)` 為假才還原。candidate authority 已 checked 的 next（legacy、與 flushed 只差返還／lease 進度、W-b 已落盤）一律不倒退並回 true；原本的 `expected.equals(durable)` 早退由這條涵蓋。CAS 不符的隔離路徑不變。`@apiNote`（`:128-130`）註明只回退 candidate 欄位、不適用 M5 restore 類寫入 |
| q2 | `CandidateLedgerService.commitChecked`、`contain`（`:147-192`） | abort 丟例外時呼叫 `JournalPort.quarantine`（`:44`，回傳是否新進入隔離），真的隔離該 session；隔離也丟例外時記 ERROR。`contain` 為 package-private，回傳 `Containment`（`:168-175`），log 的處置文字依實際結果：還原（含 next 已 checked、session 已不在 current）、已隔離（CAS 不符或原本就在隔離中）、abort 失敗後隔離、兩者都失敗（等 recovery tick 隔離） |
| q3 | `SessionRecoveryState.quarantine`、`quarantinedSessions`、`convergeQuarantined`（package-private）、`convergenceTarget`（`:151-187`）；`SessionRecoveryManager.convergeUnchecked`（`:116-149`）、`convergencePending`（`:155-158`） | 見下方「隔離 session 的收斂」 |
| q4 | `SessionRecoveryManager.tick`（`:74-79`）、`convergeUnchecked`；`SuperpositionSessionManager.settleUncheckedInitial`（`:223-231`）、`finishAbandoned`（`:236-252`，在 `recovery.tick` 之後呼叫） | 見下方「殘留與全域暫停」 |
| q5 | `SuperpositionSessionManager.abandonInitial`（`:167-183`）、`cancelInitial`（`:188-198`）、`AbandonWarning`／`warnAbandoned`（`:200-217`）；start 成功落盤時的 INFO（`:136-137`） | 見下方「放棄入場的補寫與 WARN」 |
| Nit 3 | `FailureText.describe`（`persistence/FailureText.java:17-24`） | 以 identity set 走訪 cause 鏈，多節點環在有限步內停止。由 `CandidateLedgerService.describe` 移到中立的 `FailureText`，`CandidateLedgerService`、`SessionRecoveryManager`、`SuperpositionSessionManager` 共用；同檔的 `dedupeKey`（`:31-46`）見 fix round 1 |

**隔離 session 的收斂（q3）**：

- 隔離的語意不變：`put`／`remove` 拒絕（其他 owner 不能在未 checked 的 current 上疊寫），`writable()` 只寫上一份 flushed authority（沒有時不寫）。
- 唯一的出口是 `convergeQuarantined`。它不經 `put`：隔離 session 的 current 從未被寫出或承認，所以不需要 CAS，直接以最後 checked authority 取代 current，authority history 也還原為 `checkedAuthority`（不還原的話，history 留著未 checked 的 SELECTED，之後 SELECTABLE 的 RETURNING 會被 `sameAuthority` 拒絕）。
- 收斂目標（`convergenceTarget`，對 `SessionState` exhaustive switch，新增狀態時編譯即要求決定）：flushed 為 ARMING／SUPERPOSITION → 同一 authority 的 RETURNING（candidate context、ledger、selection 原樣，只改狀態）；RETURNING／MEASURED → flushed 原樣；沒有 flushed authority → 移除。目標先經 `validateOwnership` 驗證才改動。
- 取代後 current 已沒有未 checked authority，所以立即解除隔離並保持 dirty。任何之後的寫出（tick 的重試、其他 session、原生 autosave）帶著它都安全，也會覆寫磁碟上可能已落地但未確認的 next。
- 被丟棄的是未 checked 的 candidate／selection，以及疊在它上面的進度（只有 CAS 不符的防禦路徑會有）。丟掉的返還進度由 recovery 依既有的 checkpoint marker 冪等重做。
- 驅動者是 `SessionRecoveryManager.tick`：每個 tick 對隔離 session 呼叫 `convergeQuarantined`（`operationInProgress` 的 session 略過；驗證失敗時維持隔離、同原因只記一次 WARN），然後 checked flush。寫出成功後逐一以 flushed 比對收斂目標：exact 相符才記 INFO「已收斂：→ checked RETURNING／checked 移除」，不符（之後的寫入已改動該 session）記 WARN。寫出失敗時保持 dirty、下一 tick 重試，同原因只記一次 WARN，恢復時記 INFO。
- 收斂完成的訊號：`convergencePending(sid)` 在 session 仍隔離中、或已取代 current 但 checked 寫出尚未成功時為真。依賴「殘留已 checked 移除」的清理（`finishAbandoned`）必須等它為假（fail-closed）。
- flushed RETURNING 落盤後，既有流程接手：`SuperpositionSessionManager` 把 runtime 改為 RETURNING，`recover` 把玩家送回原艙並寫返還進度，最後 release、移除 record。

**殘留與全域暫停（q4）**：

- `SessionRecoveryManager.tick` 不再因為 `uncheckedCandidateSessions()` 非空而整個 return。tick 在任何 checked 提交的同步 put→flush 之外執行，所以此時看得到的未 checked current 都是殘留：逐一隔離（每個 session 只記一次 WARN），再走上面的收斂；其他 session 的 disconnect 與返還照常。原本說得過滿的註解（「只剩 put→flush 的瞬間窗口」）改為說明雙重故障仍可能留下殘留，以及 tick 的處理。
- `recoveryWritesSafe()` 的語意不變（只排除隔離 session），`CorridorPageManager` 的 `tickRelease`、`fail` 等仍以它把關；殘留在下一個 recovery tick 被隔離後即恢復。
- 已知的建立點都在建立當下隔離，tick 只是保底：
  - `commitChecked`：CAS 不符（既有）與 abort 失敗（q2）。
  - start：`abandonInitial` 失敗、改走 `fail` 之後，`settleUncheckedInitial` 檢查初始 authority 是否已 checked。fallback RETURNING 已 checked 時照舊由一般返還流程處理；否則立即隔離仍留在 current 的未 checked record（同一 tick 的其他寫出也不會承認它），並記入 `abandonPending`。立即隔離分支由 `native_start_cancel_fault_quarantines_unchecked_initial_at_once_windows` 覆蓋（fix round 1）。
- 沒有 checked authority 的殘留，要等 recovery 以 checked 寫出確認移除（current、flushed 都沒有，且 `convergencePending` 為假），`finishAbandoned` 才取消 reservation（`cancelPrepared`＋`retire`）、移除 runtime 與來源票，chamber 可再使用。`cancelInitial` 可安全重試：`cancelPrepared` 成功後保留 `RetiredMappings`，`retire` 失敗時下一次只重做 `retire`。取消失敗時同原因只記一次 WARN，下一 tick 重試。
- 保底的界線：殘留出現到下一個 recovery tick 之間，若有其他 session 的寫出，非隔離的殘留仍會被寫出。這與 M4 相同（M4 的暫停也擋不住其他 session 的寫出），只影響未知路徑。
- 釋放 chamber 的殘留路徑（fix round 1 re-review 的觀察）：四件事同時發生時——abort 已移除 record、補寫失敗、`retire` 失敗、fallback 的 `returning` 因另一個 session 的殘留（`recoveryWritesSafe()==false`）在 put 之前就丟例外——current、flushed 都沒有該 session，`convergencePending` 為 false，下一個 tick 就釋放 chamber，但磁碟上可能還留著未確認的 ARMING（下一次成功寫出時覆蓋）。屬四重故障，不另處理。

**放棄入場的補寫與 WARN（q5）**：

- `abandonInitial`：CAS 移除未 checked 的 ARMING → 立即 checked flush，覆寫磁碟上可能已原子寫入但未確認的 ARMING（readback 才失敗的窗口）→ 取消 reservation、移除 runtime → 回 REJECTED。
- 補寫失敗不改變放棄的結果：record 已不在 current，journal 保持 dirty，由下一次寫出覆寫；這個情況寫進 WARN 的原因。CAS 或清理失敗才改走既有的 `fail`（同上 q4），此時補寫的例外也附在原失敗上。
- WARN 的內文用 `FailureText.describe`（含 root cause）並附第一次的 stack；去重鍵另用 `FailureText.dedupeKey` 加上補寫是否成功，依 chamber 保存（`abandonWarnings`，比照 `checkpointCapable`）。同原因的再次放棄只記 DEBUG。同一 chamber 之後初始 ARMING checked 落盤成功時，記一次 INFO（帶先前的顯示原因）並清除。原本 WARN 只有頂層例外（`journal checked 落盤失敗…`），看不到 root cause，而且週期 refresh 每次重試都記一則。

**M5 restore 類（spec §6）的重用**：

- `restoreCheckedAuthority`（`:189-195`）是不檢查 CAS 的還原核心，`abortUnchecked` 與 `convergeQuarantined` 共用。
- 依 q1，`abortUnchecked` 只回退 candidate authority（`@apiNote` 已註明）；COLLAPSING 提交、READY、終局等 candidate 三欄位不變的 restore 類寫入，`abortUnchecked` 會視為已 checked 而不回退。F2a 需要以同一核心另開「current exact 等於 expected 才還原」的入口；H3 沒有新增這個入口，`abortUnchecked` 也不改名。
- `convergeQuarantined` 是 package-private，只由 `SessionRecoveryManager` 呼叫並負責 checked 寫出與確認。`convergenceTarget` 對 M5 新狀態必須明確決定收斂目標（exhaustive switch 會在編譯時要求）。
- `uncheckedCandidateSessions`／`recoveryWritesSafe` 與 recovery tick 的保底，只偵測 candidate context／ledger／selection 未 checked 的 current。M5 restore 類寫入失敗後殘留的 COLLAPSING 等 current，candidate 三欄位與 flushed 相同，這三者都看不到。F2a 必須擴充偵測（例如把 M5 的 `Collapse` 欄位納入），或在 owner 端失敗時自行 CAS 還原或隔離。

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

### Gate 與證據（H3 首輪，`1fb9c9b`）

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

### Fix round 1（`3957624`）

處理首輪 code quality review 的 Important 1 與 Minor 1、2、4–8，以及 Nit「`finishAbandoned` 在 `abandonPending` 為空時提早 return」：

| 項目 | 處理 |
| --- | --- |
| I-1 q5 WARN 去重鍵含隨機暫存檔路徑 | 新增中立的 `persistence/FailureText`：`describe`（由 `CandidateLedgerService.describe` 移入，`CandidateLedgerService` 的版本移除，呼叫端改用它）與 `dedupeKey`（同樣以 identity set 走訪 cause 鏈，每個節點取 class 與 message；`FileSystemException` 改取 `getReason()`）。`warnAbandoned` 以 `dedupeKey`＋補寫是否成功當去重鍵，WARN 內文照舊用 `describe`；`abandonWarnings` 改存 `AbandonWarning(key, reason)`，恢復時的 INFO 印 reason。另加一條：message 恰等於 cause 的 `toString()`（JDK 以單一 cause 包裝的預設，例如 `new UncheckedIOException(e)`）時記為 `<cause>`，否則外層 message 同樣帶路徑 |
| Minor 1 收斂完成的訊號 | `SessionRecoveryManager.convergencePending(sid)`；`finishAbandoned` 等它為假，且 current、flushed 都已沒有該 session，才釋放 chamber 並記 INFO。`convergeUnchecked` 寫出成功後逐一比對 flushed 與目標，相符才記 INFO，不符記 WARN。Javadoc 與本段同步 |
| Minor 2 測試缺口 | `contain` 改 package-private 並回傳 `Containment`，JUnit 表格涵蓋六種情況（含 Minor 6 的兩種）；cause 環的逐字輸出移到 `FailureTextTest`。`settleUncheckedInitial` 的立即隔離分支以新的 probe 模式覆蓋（見下方測試） |
| Minor 3 收斂寫出沒有退避 | 不在本 task 處理（controller 裁定，延後為 journal 層級的共用節流） |
| Minor 4 | `SessionRecoveryManager` 的兩處去重（收斂失敗、收斂寫出失敗）改用 `FailureText.dedupeKey`，WARN 照舊附 stack；`finishAbandoned` 的取消失敗去重也改用它。其他檔不改 |
| Minor 5 F2a 重用 | `convergeQuarantined` 改 package-private；`abortUnchecked` 不改名，加 `@apiNote`；「M5 restore 類的重用」段補上偵測範圍的限制 |
| Minor 6 | `JournalPort.quarantine` 改回傳 boolean。abort 失敗而隔離回 false 時：current 已不存在歸為 RESTORED，否則（原本就在隔離中）歸為 QUARANTINED |
| Minor 7 | `cancelInitial` 可安全重試（`retiredInitial` 保留 `cancelPrepared` 的結果，重試只做 `retire`）；`abandonInitial` 清理失敗時，補寫的例外也加成 suppressed |
| Minor 8 GameTest 收尾 | (a) q5 test 的取證與斷言都在同一個 try 內，`LogCapture` 一定移除；(b) q3 test 加失敗路徑 hook（關閉 fixture 玩家）；(c) q5 test 的收尾等待改用 `whenWithinTicks`；(d) tick 保底 test 的 hook 先檢查 `quarantinedSessions()`，隔離中交給 recovery，不呼叫會被拒絕的 remove |

測試（JUnit +6、GameTest +1）：

- `FailureTextTest`（新增，5 個）：
  - `dedupeKeyIgnoresTemporaryFilePathsOfFileSystemExceptions`：兩個只差暫存檔路徑的 `AccessDeniedException`（包在相同的 `UncheckedIOException`）得到相同、逐字固定的鍵，`describe` 仍各自保留路徑。
  - `dedupeKeyDoesNotRepeatAMessageThatIsJustItsCause`：`new UncheckedIOException(e)` 的外層記為 `<cause>`。
  - `dedupeKeyKeepsDifferentCausesApart`：reason、例外 class、一般例外的 message、root 以外的節點，任一不同都是不同的鍵。
  - `multiNodeCauseCycleTerminatesWithExactText`：兩節點 cause 環，`describe` 與 `dedupeKey` 都在 10 秒內結束且逐字相符。
  - `describeShowsTopAndRootOnly`。
- `CandidateLedgerServiceTest.containmentClassifiesWhatActuallyHappened`：以腳本化的 `JournalPort` 涵蓋 RESTORED、QUARANTINED、QUARANTINED_AFTER_ABORT_FAILURE、UNCONTAINED，以及隔離回 false 的兩種，並核對附在原失敗上的 suppressed 數。Port 的 `quarantine` 改回傳 boolean。
- GameTest：
  - `native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows`：probe 的 root cause 改為 message 含隨機暫存檔路徑的 `AccessDeniedException`（reason 為受控字串），新增 witness `tempPathsDiffered`。這是 warnAbandoned 層級的去重測試。
  - `native_start_double_fault_residue_converges_and_releases_chamber_windows`：probe 改名 `DIVERGE_THEN_FAIL_CONVERGENCE`，殘留被收斂移除後的第一次寫出再受控失敗一次；以 `LogCapture` 斷言收斂寫出失敗 WARN 恰一則，且「釋放 chamber」的 INFO 在「已收斂 → checked 移除」的 INFO 之後。
  - 新增 `native_start_cancel_fault_quarantines_unchecked_initial_at_once_windows`（probe `CANCEL_THEN_FAIL`）：初始 flush 失敗；放棄時的補寫也失敗，probe 趁補寫把 ARMING 放回 current，使 `cancelPrepared` 失敗而改走 `fail`；`fail` 的 RETURNING 因 recovery 寫入不安全而沒有寫出。斷言 `settleUncheckedInitial` 在同一個 start 內立即隔離殘留，之後 checked 移除、釋放 chamber。

逐輪證據在 `h3/fix1/`。diff hash 是 `git diff 6a3d56d 3957624 -- <path>` 輸出的 SHA-256：

| 路徑 | SHA-256 |
| --- | --- |
| `src` | `6511ab09…` |
| `src/main` | `50b62548…` |
| `src/test` | `c3615e0a…` |
| `src/testmod` | `401c7aea…`（與 GameTest RED 的 diff 相同） |

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED GameTest | `6a3d56d`（code＝`1fb9c9b`）；只有 `M4CandidateDoorGameTests.java` 有變更（`h3/fix1/red-gametest-state.txt`） | Full＋onlyBatches `m5_h3_start_readback_fault,m5_h3_start_double_fault,m5_h3_start_cancel_fault`：`gate-h3-fix1-red-gametest-f383ce786822485cbd4fad274883d146` | 預期 FAIL（JUnit 435 全過）。q5：`singleWarning=false`、`warningNamesChamberAndRootCause=false`，faults=3、3 則 WARN 各帶不同的暫存檔路徑。double fault：`releasedOnlyAfterCheckedRemoval=false`（收斂寫出失敗的同一 tick 就釋放 chamber 並記 INFO，checked 移除尚未確認：convergedLog=-1、freedLog=4）。cancel fault 在基底就 PASS：該分支首輪已存在，這是新覆蓋，不是 RED |
| RED JUnit：新測試＋暫時 adapter | 同上 HEAD；production 2 檔＋測試 2 檔＋上列 testmod（`h3/fix1/red-junit-state.txt`）。adapter：`FailureText.describe` 為舊實作的複本、`dedupeKey` 沿用舊去重語意（＝`describe`）；`JournalPort.quarantine` 回傳 boolean、`contain` 改 package-private，但分類照舊忽略回傳值。GREEN 時改寫 | focused JUnit `FailureTextTest`＋`CandidateLedgerServiceTest` | 預期 FAIL：28 個中 3 個失敗，就是 3 個 RED test：暫存檔路徑不同的鍵不相等；`<cause>` 包裝的鍵不相等；表格中隔離回 false 的兩種都得到 QUARANTINED_AFTER_ABORT_FAILURE。表格的其餘 4 種、cause 環逐字輸出等對照通過 |
| GREEN focused | 工作樹＝`3957624` | `compileTestmodJava`＋`dev.quantumchamber.persistence.*`＋`dev.quantumchamber.candidate.*` | PASS：180 個，0 failure／error，2 skipped（`NonWindowsPlayerCheckpointStoreTest`） |
| GREEN Full | 同上 | `gate-h3-fix1-green-full-42d347dd99c545aa895f9ddd5af22fc6` | PASS |
| GREEN Legacy | 同上 | `gate-h3-fix1-green-legacy-c7493f6715d343b9beccc7b59b683b22` | PASS |
| GREEN M4 recovery | 同上 | `recovery-run-95f9887dfd0c46ae84a0cca0d2109e41.json` | PASS |

gate 在 commit 之前以工作樹執行。執行前記錄 8 個檔案的 `git hash-object`（`h3/fix1/green-state.txt`），與 `3957624` 中各檔的 blob id 逐一相同（`h3/fix1/commit-hashes.txt`）。

fix round 1 的 GREEN 數字：

- default GameTest 152/152（`_windows` 78），0 failure／error／skipped。比首輪多 1 個，就是新增的 cancel fault test。boundary oracle PASS；per-test receipt 34/34 PASS。
- JUnit 441，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比首輪的 435 多 6 個：`FailureTextTest` 5 個與 `containmentClassifiesWhatActuallyHappened`。
- JAR：release 308 entries（265 classes），比首輪多 2 個 class：`FailureText`、`SuperpositionSessionManager$AbandonWarning`。sources 195，多 `FailureText.java`。testmod 命中只有合法的 `ModPresenceProbe`。
- legacy 1/1，per-test receipt 1/1 PASS。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。

GREEN log 中的對應行：double fault 依序為「收斂寫出失敗」WARN 一則、「已收斂 → checked 移除」、「收斂寫出已恢復」，之後才是「初始 ARMING 殘留已 checked 移除，取消 reservation 並釋放 chamber」；cancel fault 為「已收斂 → checked 移除」再釋放；q5 的「入場 ARMING 未能 checked 落盤」WARN 只有一則。其餘行（pin 上限 2 則、MEASURED checkpoint 恢復的 2／3 次）與前幾次 Full 相同。

### 觀察（未修改）

- 延後項（controller 裁定，Minor 3）：收斂寫出失敗時每 tick 重試，沒有退避；之後以 journal 層級的共用節流處理。
- 第一次 GREEN Full 中，`m2_native_shared_west`（M2 的 test，不是 H3 的）的 `recover` 寫出一次失敗，記一則 WARN「返還保留 pending … `journal checked 落盤失敗`」，下一 tick 重試成功、test PASS。前後沒有任何 session 被隔離或收斂，之前 8 個 Full gate 都沒有這一行。第二次 Full 的 batch 順序相同，這一行沒有再出現，所以判定為非決定性的暫態寫出失敗。WARN 只記例外 class 與 message，看不到 root cause（既有的 `SessionRecoveryManager` 寫法，不在本 task 修改）。
- `.github/workflows/build.yml` 的 `$requiredM4GameTests` 明列清單沒有加入新的 3 個 `_windows` test（不在本 task 的檔案清單），由同一步驟的通則涵蓋；新的跨平台 test 不在 Ubuntu 的 `$requiredCrossPlatform` 清單。是否補進明列清單由 controller 決定。
- q5 的補寫在同一 chamber 反覆放棄時，每次放棄都會多一次 journal checked 寫出（與入場嘗試同頻率，週期 refresh 約每 20 tick 一次）。持續 IO 故障時補寫本身也失敗，只記一次 WARN 原因。

## H4：凍結時的位置修正

- Commits（parent `0c04bd4`），只改派工列出的檔案：
  - `355a3c0` `fix: send position corrections while recovery freezes movement`：production `ServerPlayNetworkHandlerRecoveryMixin.java`、`SessionRecoveryManager.java`（只有凍結判斷與節流狀態）、`quantumchamber.mixins.json`；testmod `M2CorridorGameTests.java`（新增 GameTest）。
  - `29bba37` `test: compare raw yaw and pitch, rename the testmod`：testmod `M2CorridorGameTests.java`（yaw／pitch 斷言）、`src/testmod/resources/fabric.mod.json`。
  - Code range `0c04bd4..29bba37`；`1c4b432` 是本段的 docs commit。
  - `a534184`（fix round 1，parent `1c4b432`）：`ServerPlayNetworkHandlerRecoveryMixin.java`、testmod `M2CorridorGameTests.java`。本段的 fix round 1 文字由其後的 docs commit 加入。內容見下方「Fix round 1」。
- Review：
  - 首輪 spec review：✅，Minor 3。
  - 首輪 code quality review：Critical 0、Important 0、Minor 6。fix round 1 處理 spec Minor 3 與 quality Minor 1–5；quality Minor 6（testmod 共用的 outbound 讀取 helper）由 controller 交給 F3d。
  - 首輪 spec Minor 1、2 的處置：spec Minor 1（載具修正沒有自動化覆蓋）接受，只靠 code review（走廊世界不接受載具），F1b 可選擇在凍結中騎乘觀察；spec Minor 2（只覆蓋單人、RETURNING）中，MEASURED 與 RETURNING 共用 `blocks()`／`freezes`，不另補，各玩家獨立節流交給 F3d 的 COLLAPSING 凍結 GameTest。
  - fix round 1 的 re-review：Critical 0、Important 0、Minor 1（本段 review 處置的追溯性，已在 controller 的 docs commit 補上）。H4 完成。

### Code-truth（`a534184`）

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| 單一凍結判斷 | `SessionRecoveryManager.freezes`（`:64-71`）；`blocks`（`:50-62`） | `freezes(record, player)`：狀態為 RETURNING 或 MEASURED，且該玩家是尚未 checked 返還的參與者。`blocks` 對 current 與 flushed 都改用它，語意不變；JOIN 排隊與斷線待處理和 record 無關，仍留在 `blocks`。F3d 只需要把 COLLAPSING 加進 `freezes` 的狀態條件 |
| 節流 | `correctionDue`（`:73-83`）、`corrections`（`:36-38`）、`CORRECTION_INTERVAL_TICKS=10`（`:41-42`）；`tick`（`:100-104`）、`detach`（`:204-207`） | 以 `server.getTicks()` 計，同一玩家 10 ticks 內最多一次；回 true 時記下本次。玩家移動與載具移動共用同一個節流 |
| 玩家位置修正 | mixin `quantumchamber$holdPlayerMovement`（`:23-34`） | 見下方「位置修正與原版 teleport 流程」 |
| 騎乘 | mixin `quantumchamber$holdVehicleMovement`（`:36-48`） | 見下方「騎乘」 |
| `defaultRequire: 1` | `quantumchamber.mixins.json:6-8` | main 的每個 injector 都必須命中，否則啟動失敗。原本以單一 injector 同時注入 `onPlayerMove` 與 `onVehicleMove`，`require` 以整個 injector 的成功數計算，其中一個失配仍會通過；拆成兩個 injector 後各自受檢 |
| `@Unique` | mixin | 本 task 沒有新增 mixin 欄位；唯一的欄位 `player` 是 `@Shadow`，不需要 `@Unique`。節流狀態放在 `SessionRecoveryManager`，不放在 handler 上 |
| ±0 斷言 | `M2CorridorGameTests.java:1679-1681` | `context.assertEquals`（裝箱，`Float.equals` 區分 +0 與 -0）改為原始 float `==`，訊息帶兩邊的值。目前沒有 -0 fixture，結果不變 |
| testmod 名稱 | `src/testmod/resources/fabric.mod.json:5` | 「QuantumChamber M1–M2 原生驗證」→「QuantumChamber 原生驗證 testmod」。Fabric 的 mod list 只印 id 與版本，main-only oracle 以 mod id 比對，`scripts/verification/` 與 `.github/` 都沒有引用這個名稱 |

**位置修正與原版 teleport 流程**（以 javap 讀 1.21 Yarn 的 `ServerPlayNetworkHandler`、`ClientPlayNetworkHandler`、`ServerPlayerEntity` 確認）：

- 被凍結的玩家送出 `PlayerMoveC2SPacket`：照舊在 `forceMainThread` 之後取消，不進原版的移動處理。`correctionDue` 允許時，呼叫原版 `requestTeleport(x, y, z, yaw, pitch, {Y_ROT, X_ROT})`，座標是伺服器現值。
- 原版 `requestTeleport` 設定待確認的位置與新的 teleport id，以同一個位置呼叫 `updatePositionAndAngles`（位置與朝向不變；會重設 `prevX／Y／Z`、`prevYaw／prevPitch` 這些插值欄位與 handler 的待確認 teleport 狀態），再送 `PlayerPositionLookS2CPacket`。客戶端收到後設定位置、把絕對軸的速度歸零，回 `TeleportConfirmC2SPacket` 與一個 `Full` 移動封包。ACK 照常由沒有被攔截的 `onTeleportConfirm` 處理：id 相符時把位置設為請求值，並清掉待確認。之後的移動封包仍被凍結，也受節流，所以不會來回觸發。
- 不會讓客戶端卡在等待 ACK：每一次修正都有新的 id，客戶端對每一則都回 ACK，最後一則相符。舊 id 的 ACK 原版直接忽略；只有「id 相符但沒有待確認」才會斷線，這個流程不會發生。
- 不與伺服器的移動衝突：返還移動（`SessionTransferService.move` → `ServerPlayerEntity.teleportTo`，同世界與跨世界兩條分支都呼叫 `requestTeleport`）會換新的 id，先前修正的 ACK 不再生效，不會把玩家拉回舊座標。修正的目標永遠是送出當下的伺服器位置。
- 原版 `onPlayerMove` 在 `notInAnyWorld`（終界結尾畫面）時直接 return，不處理 teleport；修正同樣略過。
- 視角：凍結期間伺服器的 yaw／pitch 不再更新。以絕對值送出的話，每 10 ticks 會把鏡頭拉回舊朝向，所以 yaw／pitch 以相對 0 送出，客戶端保留自己的視角。
- 節流狀態的清理：`corrections` 是「玩家 → 最近一次修正的 server tick」。過期的項目與不存在等價，`tick` 在 journal 健康檢查之前移除已滿 10 ticks 的項目（journal 不健康時 `blocks` 仍會攔截並修正），所以 map 只留最近 10 ticks 內修正過的玩家；玩家斷線或 session 結束後，最多再留一個間隔。`detach` 全部清除。沒有把節流狀態放在 handler 上，也沒有用 handler 的 `ticks`：GameTest 的嵌入式連線不經 `NetworkIo` 輪詢，handler 的 `ticks` 不會前進。
- 玩家持續按著移動鍵時，客戶端最多先走 10 ticks 再被拉回（rubber-band）。這是節流的取捨，間隔是常數。
- 節流在窗口開始時送出修正，窗口內不補送。放開按鍵後，最後一段位移要等客戶端下一個移動封包才會修正；原版客戶端靜止時至少每 20 ticks 送一次位置（`ClientPlayerEntity.sendMovementPackets` 的 `ticksSinceLastPositionPacketSent >= 20`），所以最長約 20 ticks＋RTT。

**騎乘（`onVehicleMove`）：修正載具，不修正玩家。**

- 騎乘中的客戶端只送只帶視角的 `PlayerMoveC2SPacket.LookAndOnGround`；原版 `onPlayerMove` 對騎乘中的玩家也只採用視角，位置由載具決定。所以騎乘時 `holdPlayerMovement` 只取消、不修正玩家；對騎乘中的玩家 `requestTeleport` 反而會和載具位置衝突。
- 漂移來自客戶端控制的載具（`VehicleMoveC2SPacket`）。`holdVehicleMovement` 只在原版 `onVehicleMove` 會處理的情況下修正：根載具不是玩家本身，而且 `getControllingPassenger()==player`。這時送 `VehicleMoveS2CPacket(rootVehicle)`，與原版拒絕載具移動時送的修正相同；客戶端只在自己控制該載具（`isLogicalSideForUpdatingMovement`）時套用，並回一個 `VehicleMoveC2SPacket`（仍被凍結、受節流）。其他情況原版也直接忽略封包，這裡只取消，不消耗節流。
- 與原版的唯一差異（javap 確認）：原版還要求根載具等於 handler 的 `topmostRiddenEntity`。原版 `tick()` 每 tick 把它設為 `getRootVehicle()`，根載具就是玩家本身或不由玩家控制時改設 null，所以它是同一條件在上一個 handler tick 的快照。兩者只在剛上車、換載具或剛取得控制的那一個 tick 不同：原版忽略該封包，這裡送回的仍是載具的伺服器位置。為此 shadow 一個 private 欄位不划算，所以不比對。
- 走廊世界只接受玩家、掉落物、投射物與經驗球（`SuperpositionEntityPolicy`），沒有載具。這條路徑只適用於不在走廊、尚未返還的參與者（例如 ARMING 期間離開原艙的 RETURNING 參與者），沒有 GameTest 覆蓋，只靠 code review。

**`defaultRequire: 1` 給後續 task 的指引**：

- 之後新增的 main injector 自動受 `require=1` 約束：target 改名或簽名不符時，server 啟動就失敗。刻意可選的 injector 要明寫 `require=0`，並在註解寫明理由。
- 同一個 injector 用 `method={...}` 注入多個方法時，`require` 以整個 injector 的成功數計算（Mixin `InjectionInfo` 每個 injector 只有一個計數），其中一個失配仍會通過。需要每個 target 都命中時要拆開，如本 task 的 `onPlayerMove`／`onVehicleMove`。
- testmod 的 `quantumchamber-test.mixins.json` 沒有 `defaultRequire`。fault mixin 失配時不會失敗，只能靠各 test 的命中計數（例如 `hits`）斷言發現。
- 相容性取捨：其他模組若改寫 `onPlayerMove`／`onVehicleMove`，讓本 mod 的注入點消失，server 會在啟動時 fail closed，而不是默默失去凍結。README 的說明由 F7 處理。

### 測試（`355a3c0`、`29bba37`、`a534184`）

GameTest（+1，`_windows` +1）：`native_frozen_movement_gets_throttled_position_corrections_windows`（batch `m5_h4_freeze_correction`，`M2CorridorGameTests.java:1008-1095`）：

- 一人入場後，以既有的 `TeleportExceptionFault` 讓返還移動持續失敗，再以 LOW 觸發返還；等 durable RETURNING 且 recovery 至少嘗試過一次移動，玩家就停在走廊、被凍結。
- 先讀掉既有的 outbound 封包。同一 tick 連送 20 個 `Full` 移動封包（每個往 +x 多漂 0.125 格、yaw 加 1 度），之後連續 20 ticks 每 tick 送一個；每次都立即讀出 outbound 的 `PlayerPositionLookS2CPacket`（讀法沿用 M4 `messages()`：先 flush 再讀）。
- witness：`stillFrozen`（仍是 durable RETURNING、在走廊、`blocks` 為真）、`serverPositionHeld`（伺服器位置不變）、`burstCorrectedOnce`（同一 tick 的 20 個只修正一次）、`spreadCorrectedAtExactInterval`（burst 在 server tick S 時，之後 20 ticks 的修正恰在 `List.of(S+interval, S+2*interval)`；`interval` 讀 `SessionRecoveryManager.CORRECTION_INTERVAL_TICKS`，不寫字面值）、`correctionsSpacedByInterval`（任兩次修正相隔 ≥ `interval`）、`serverAuthoritativePosition`（修正的座標 exact 等於伺服器位置）、`viewNotPulledBack`（flags 恰為 `{Y_ROT, X_ROT}`，yaw、pitch 為 0）。
- 收尾：移除 fault，等 recovery 正常返還、record checked 移除，玩家在來源艙；per-test Universe probe PASS。
- 沒有新增 JUnit（派工的檔案清單不含 JUnit 測試檔）；節流由這個 GameTest 覆蓋。
- 只有一位玩家；「節流是每位玩家各自計算」沒有在 H4 覆蓋，controller 交給 F3d 的 COLLAPSING 凍結 GameTest。

### Gate 與證據

gate root 都在 `.superpowers/verification/`；逐輪的 state、console、diff 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/h4/`。RED 與 mutation 兩輪以 `JAVA_TOOL_OPTIONS=-Dquantumchamber.gametest.onlyBatches=m5_h4_freeze_correction` 只跑新 batch。

diff hash 是 `git diff <from> <to> -- <path>` 輸出的 SHA-256：

| 範圍 | 路徑 | SHA-256 |
| --- | --- | --- |
| `0c04bd4..355a3c0` | `src` | `9b67e960…` |
| `0c04bd4..355a3c0` | `src/main` | `95fdd47a…` |
| `0c04bd4..355a3c0` | `src/testmod` | `e4df7a49…`（與 RED 的 testmod diff 相同） |
| `355a3c0..29bba37` | `src/testmod` | `c0e2e3b3…` |
| `0c04bd4..29bba37` | `src` | `27bc6d96…` |

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED GameTest | `0c04bd4`；只有 `M2CorridorGameTests.java` 有變更（`h4/red-gametest-state.txt`，diff `e4df7a49…`） | Full＋onlyBatches `gate-h4-red-gametest-d6b3f90971774a7d9b74ba5af60ac44b` | 預期 FAIL：1/1（JUnit 441 全過）。`burstCorrectedOnce=false`、`spreadCorrectedAtMostTwice=false`、`corrections=[]`；`stillFrozen`、`serverPositionHeld` 為 true：40 個移動封包都被丟棄、伺服器位置不變，但沒有任何位置修正 |
| GREEN Full（`355a3c0` 的工作樹） | `0c04bd4`＋production 3 檔＋上列 testmod（`h4/green1-state.txt`，blob 與 `355a3c0` 逐一相同） | `gate-h4-green1-full-4f5cfdb2cef64081b0d7fe0f56f14b15` | PASS |
| mutation：`defaultRequire` 生效 | `355a3c0`；`onPlayerInteractBlock` 的 `@At` target 暫時改成不存在的方法（`h4/mutation-require-state.txt`） | Full＋onlyBatches `gate-h4-mutation-require-49ccf039af7742aab8cfdb15e5648354` | 預期 FAIL：server 啟動時 `InjectionError: Critical injection failure: Callback method quantumchamber$holdInteraction … (0/1) succeeded` |
| mutation 對照 | 同一 mutation，再暫時拿掉 `injectors.defaultRequire`（`h4/mutation-control-state.txt`） | Full＋onlyBatches `gate-h4-mutation-control-5ddd701c47134e828c71a326f8f1344b` | PASS：server 正常啟動、GameTest 通過，log 沒有 injection 錯誤（失配的 injector 被默默略過）。兩處 mutation 跑完都還原，blob 與 `355a3c0` 相同 |
| GREEN Full（最終） | `355a3c0`＋測試品質子項（`h4/green2-state.txt`，blob 與 `29bba37` 逐一相同） | `gate-h4-green-full-cf992bbe05864fffa2762e793a2105ae` | PASS |
| GREEN Legacy | 同上 | `gate-h4-green-legacy-57105af3ddb8419d84f7657eacdb8ce5` | PASS |
| GREEN Main＋oracle | 同上 | `gate-h4-green-main-8b40ee64f3574214a86a2e7da4bf8cb3` | PASS；`main-oracle.ps1` PASS |
| GREEN M4 recovery | 同上 | `recovery-run-8ce417312d0e43b989dd03abefbca977.json` | PASS |

gate 在 commit 之前以工作樹執行。兩個 commit 的檔案 blob 與各自 gate 前記錄的 `git hash-object` 逐一相同（`h4/precommit1-hashes.txt`、`h4/precommit2-hashes.txt`、`h4/commit-hashes.txt`）。

GREEN 數字（兩次 Full 相同）：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped。比 H3 多 1 個，就是本 task 新增的 `_windows` test。boundary oracle PASS；per-test receipt 35/35 PASS（多的 1 個是新 test）。
- JUnit 441，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`），與 H3 相同。
- JAR：release 308 entries（265 classes）、sources 195，都與 H3 相同（沒有新 class；`freezes`、`correctionDue` 的 lambda 不產生 class 檔）。testmod 命中只有合法的 `ModPresenceProbe`。
- `defaultRequire: 1`：最終 Full 與 Main 兩次啟動，14 個 main mixin target class（`ChunkPosKeyedStorage`、`Entity`、`EntityChunkDataAccess`、`ItemStack`、`MinecraftServer`、`PlayerEntity`、`PlayerManager`、`RegionBasedStorage`、`ServerEntityManager`、`ServerPlayNetworkHandler`、`ServerPlayerEntity`、`ServerWorld`、`StorageIoWorker`、`World`）在 class-load log 中都已載入，啟動與正常停止都沒有 injection 錯誤，所以所有 main injector 都命中。
- legacy 1/1，per-test receipt 1/1 PASS。
- main-only oracle：layer 2／3 的 testmod 命中為空、world keys exact、正常停止、exact restore。
- M4 recovery 24/24 phases，24 個獨立 JVM；每個 phase 的 Gradle exit 0，正常停止，lock 已釋放。

GREEN log 中，新 test 的 fault 造成「原生移動例外，保留 pending 等待重試」與「返還移動尚未確認」WARN 各一則，與既有的 `native_return_move_exception_logs_cause_once_then_recovers_windows` 相同（另一組就是它的）。

### Fix round 1（`a534184`）

處理首輪 spec review 的 Minor 3，以及 code quality review 的 Minor 1–5：

| 項目 | 處理 |
| --- | --- |
| quality Minor 1 測試沒有鎖住精確間隔 | 新 witness `spreadCorrectedAtExactInterval`（`M2CorridorGameTests.java:1042-1043`）：burst 只有一次時，之後 20 ticks 的修正 server tick 必須恰為 `List.of(S+interval, S+2*interval)`。原本的 `spreadCorrectedAtMostTwice` 由它取代。`interval` 讀 `SessionRecoveryManager.CORRECTION_INTERVAL_TICKS`（本來就是 `public static final`，值 10，未修改），測試裡不再有字面值 10；javadoc 與失敗訊息（印出 `interval`、burst 與 spread 的 tick）同步 |
| quality Minor 2 witness 名稱過寬 | `throttledPerPlayer` 改名 `correctionsSpacedByInterval`（`:1044-1045`）。只有一位玩家，「各玩家獨立節流」不在 H4 補，controller 交給 F3d 的 COLLAPSING 凍結 GameTest |
| quality Minor 3／spec Minor 3 note 用語 | 「伺服器端狀態不變」改為位置與朝向不變、但會重設插值欄位與待確認 teleport 狀態；補上節流在窗口開始時送、窗口內不補送，放開按鍵後最後一段位移最長約 20 ticks＋RTT 才修正（見上方「位置修正與原版 teleport 流程」） |
| quality Minor 4 `defaultRequire` 的指引 | 見上方「`defaultRequire: 1` 給後續 task 的指引」的四點：新 injector 自動受 `require=1` 約束、刻意可選要明寫 `require=0` 與理由；`method={...}` 多目標要拆開；testmod 沒有 `defaultRequire`；其他模組改寫目標方法時啟動 fail closed（README 由 F7 處理） |
| quality Minor 5 載具條件 | `holdVehicleMovement`（mixin `:36-48`）改為只有在根載具不是玩家、而且 `getControllingPassenger()==player` 時才送 `VehicleMoveS2CPacket` 並消耗節流；其他情況只取消。javap 確認原版的第三個條件 `topmostRiddenEntity` 是同一條件的上個 tick 快照，不比對的理由寫在註解與上方「騎乘」 |

載具分支仍沒有 GameTest（走廊世界沒有載具），只靠 code review。

證據在 `h4/fix1/`。diff hash 是 `git diff 1c4b432 a534184 -- <path>` 輸出的 SHA-256：`src` `5c075cdb…`（與 GREEN 工作樹的 diff 相同）、`src/main` `bb9f3f64…`、`src/testmod` `c509882f…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED：新斷言＋暫時 mutation | `1c4b432`；`M2CorridorGameTests.java` 的新斷言，加上把 `correctionDue` 的 `now-last<CORRECTION_INTERVAL_TICKS` 暫時改成 `<=`，實際間隔變 11（`h4/fix1/red-state.txt`，diff `bdd3b6e6…`） | Full＋onlyBatches `gate-h4-fix1-red-7bf59df9a40644bd975e7e8ba40c4152` | 預期 FAIL：1/1（JUnit 441 全過）。只有 `spreadCorrectedAtExactInterval=false`：`interval=10 burstTicks=[1193] spreadTicks=[1204]`（S+11；S+22 已超出 20 ticks）。同一份資料下，舊的三個斷言（burst 一次、spread 1～2 次、間隔 ≥ 10）都成立，所以舊 test 抓不到這個 mutation。mutation 已還原，blob 與 `1c4b432` 相同 |
| GREEN Full | `1c4b432`＋mixin＋testmod（`h4/fix1/green-state.txt`，blob 與 `a534184` 逐一相同） | `gate-h4-fix1-green-full-ffbfbccbe28744ee81c108bcea1cd5e8` | PASS |
| GREEN Main＋oracle | 同上 | `gate-h4-fix1-green-main-482abfedebbd4af8871101dd3f4490b1` | PASS；`main-oracle.ps1` PASS |

GREEN 數字與首輪相同：default GameTest 153/153（`_windows` 79），0 failure／error／skipped；boundary oracle PASS；per-test receipt 35/35；JUnit 441（2 skipped，只在 `NonWindowsPlayerCheckpointStoreTest`）；release 308 entries（265 classes）、sources 195，testmod 命中只有 `ModPresenceProbe`。Full 與 Main 兩次啟動，14 個 main mixin target class 都已載入，沒有 injection 錯誤。main-only oracle：layer 2／3 命中為空、world keys exact、正常停止、exact restore。

Legacy 與 M4 recovery 依 controller 指示省略：本輪只改移動封包 mixin 的載具條件與一個 GameTest，不碰 journal、返還路徑或 recovery。

### 觀察（未修改）

- `.github/workflows/build.yml` 的明列清單沒有加入新的 `_windows` test，依 controller 對 H2／H3 的裁定留給 F6，期間由同一步驟的通則涵蓋。
- 既有在凍結中送移動封包的 test（`native_disconnect_io_failure_preserves_removal_pending_and_retry_windows`、`native_channel_disconnect_and_join_queue_block_movement_until_next_tick_windows`）現在也會收到位置修正。它們斷言伺服器位置不變，仍成立；之後解凍再移動前都先 `confirmTeleport()`（取最後一則 teleport id），不受影響。
- `onPlayerInteractBlock` 的凍結仍只取消，不重送方塊狀態；原生 sequence ACK 已讓客戶端撤回預測，不在 H4 範圍。

## F1a：真 client probe 工具

- Commit `6d28fa0` `test: add an opt-in real-client universe probe`（parent `fad924c`），只改派工列出的檔案：`build.gradle`；新增 `src/testmod/clientprobe/java/dev/quantumchamber/gametest/M5ClientProbe.java`、`src/testmod/clientprobe/resources/fabric.mod.json`，以及派工允許的 pure JVM 測試 `src/test/java/dev/quantumchamber/gametest/M5ClientProbeTest.java`。`UniverseTransferTestAccess.java`、`M3UniverseTransferProbe.java` 與 testmod 的 `fabric.mod.json` 都沒有改。
- Code range `fad924c..6d28fa0`；`779601d` 是本段的 docs commit。
- `2bd75b1`（fix round 1，parent `779601d`）`test: align the client probe with the transfer safety rules`：`M5ClientProbe.java`、`build.gradle`（只動 F1a 區段），新增 `src/test/java/dev/quantumchamber/gametest/M5ClientProbeSafetyTest.java`。內容見下方「Fix round 1」；本段的 fix round 1 文字由其後的 docs commit 加入。
- Review：
  - 首輪 spec review：✅，Minor 5。首輪 code quality review：Critical 0、Important 1、Minor 6。
  - fix round 1 處理 quality Important I-1 與 Minor M-1～M-6，以及 spec Minor 1–3（本 note 的文字）。fix round 1 的 re-review：Critical 0、Important 0、Minor 1（本段 code-truth 兩句過時描述，已在 controller 的 docs commit 加註）。F1a 完成（F1b 結果見下方「F1b：真 client 驗證」）。
  - controller 裁定：probe 的訊息用 `Text.literal` 可以接受（plan 的 `Text.translatable` 規則針對 production 訊息，testmod 也不能改 main 的語言檔）；在 `scripts/verification/` 的 main-only oracle 與 JAR 檢查加入 clientProbe，交給 F6。
- F1b（真 client 驗證）已於 2026-10-01 完成，結果見下方「F1b：真 client 驗證」。

### Code-truth（`6d28fa0`）

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| source set `clientProbe` | `build.gradle:129-137` | java／resources 只在 `src/testmod/clientprobe/`。compile classpath＝main 的 compile classpath＋main 輸出＋testmod 輸出，只有 common Minecraft jar（不含 clientOnly），所以引用 client-only 類別會編譯失敗。runtime classpath＝自己的輸出＋`client.runtimeClasspath`＋testmod 的 `classesDirs`：不帶 testmod 的 `fabric.mod.json` 與 mixin 設定，client run 的 mod list 不會出現 testmod，也不靠它的 `environment:"server"` 過濾 |
| source set `serverProbe` | `:139-143` | 沒有 source；runtime classpath＝`testmod.runtimeClasspath`＋testmod 輸出＋clientProbe 輸出，不經過 clientProbe 的 runtimeClasspath，clientOnly jar 不會上 server classpath |
| JUnit classpath | `:145-148` | test 的 compile／runtime classpath 只多 `build/classes/java/clientProbe`（不帶 clientprobe 的 `fabric.mod.json`） |
| run `clientProbe`／`serverProbe` | `:151-173` | `client()`／`server()`；`source` 是上面兩個 source set；都加 `vmArg -Dquantumchamber.clientProbe=true`。runDir 依 `-Pm5ProbeNonce` 為 `run/client-probe-<nonce>`／`run/server-probe-<nonce>`，nonce 格式檢查與 `m4Recovery` 相同（沒給時是 `disabled`） |
| nonce 必填 | `:175-184` | `runClientProbe`／`runServerProbe` 的 `doFirst`：沒有 `-Pm5ProbeNonce` 或值為 `disabled` 時，在啟動 JVM 與建立 runDir 之前失敗，避免共用 `run/*-probe-disabled` |
| `check` | `:186-188` | 依賴 `compileClientProbeJava`；Full gate 的 `build` 因此會編譯 clientProbe |
| mod metadata | `src/testmod/clientprobe/resources/fabric.mod.json` | id `quantumchamber-clientprobe`、`environment:"*"`、唯一的 `main` entrypoint `M5ClientProbe`、沒有 mixin；depends `quantumchamber`、`fabric-api`、`minecraft 1.21` |
| 開關 | `M5ClientProbe.java:67-80` | `System.getProperty("quantumchamber.clientProbe")` 精確等於 `true` 才註冊指令與事件，否則什麼都不註冊 |
| 指令樹 | `:82-89` | `/qcprobe universe allocate｜enter｜return｜status`；根節點 `requires(hasPermissionLevel(2))`，子指令沒有參數 |
| READY 紀錄 | `:104-121` | `SERVER_STARTED`（排在預設 phase 之後，晚於正式 lifecycle 的 bootstrap）讀 server 實際的 dispatcher，記 `M5_CLIENT_PROBE_READY server=<dedicated｜integrated> registered=… subcommands=… op1Denied=… op2Allowed=…`，後接 lifecycle 與 catalog 狀態。兩種 run 的 smoke 以這一行斷言 `/qcprobe` 已註冊 |
| allocate | `:123-158` | 先 `UniverseTransferTestAccess.capture(server)`（正式 lifecycle 必須 ready 且健康）。catalog dirty 時拒絕；已有固定 UUID `f1a00000-0000-4000-8000-00000000f1a0` 時只回報 durable／ACTIVE；有其他 Universe 時拒絕；空 catalog 時 `allocateOverworld(uuid, 0)`（`EAGER_ENABLED`）→ `flush` → 讀回磁碟檔，比對 current／flushed／disk 相同且不 dirty，提示重啟 |
| enter | `:160-184`；`advance` `:253-280` | 見下方「等待 FULL 與傳送」 |
| return | `:186-209` | 只用本 server instance 內的 receipt。玩家不在 receipt 的目的世界時（例如死亡重生）作廢 receipt。在來源世界對 receipt 來源位置的 bbox 加 ticket，等 FULL 後 `returnToSource`，RETURNED 才移除 receipt。不改來源世界的方塊 |
| status | `:212-221` | 唯讀：玩家的 world key、座標、receipt／pending；lifecycle；catalog 每筆的 availability、durable、ACTIVE |
| 不建立 backend | `activeTarget` `:294-304`、`service` `:282-291` | backend 一律取自 `capture(server).backend()`，只呼叫 `resolveActive`，沒有 `new` backend、沒有 `materialize`。每個 server 只建一個 `UniverseTransferService(backend, new SessionTransferService())`（receipt 的 world token 只在建立它的 service 內有效）；backend instance 改變時拒絕 |
| server 綁定 | `bind` `:337-344`；`stopping`／`stopped` `:236-250` | integrated server 可在同一個 JVM 重開，所以狀態綁定 server instance，換 server 時清空。STOPPING 釋放所有 ticket，STOPPED 清除參照 |

**等待 FULL 與傳送**：

- `enter` 的受理條件：玩家在 `minecraft:overworld`、不在坐騎上、`isOnGround`、腳底恰在完整方塊頂面（與 transfer 安全檢查同一個式子）、bbox 內沒有流體（`standingRefusal` `:404-413`）。transfer 沿用同一個 XYZ，返還時來源位置也要通過安全檢查，所以這些條件在入口就擋下，不等 transfer 拒絕。（`6d28fa0` 的條件不完整：沒有逐欄檢查地板與 `isSpaceEmpty`，站在樹葉或方塊邊緣時 enter 成功、return 被拒；見下方 Fix round 1 的 I-1。）
- 受理後在目的 Universe 對「bbox 外擴 2 格涵蓋的 chunks」（`requiredChunks` `:393-401`，與 `UniverseTransferService.targetSafety` 同一個範圍）加 radius 0 的 ticket（level 33，FULL），指令就此結束。
- `END_SERVER_TICK`（排在預設 phase 之後）每 tick 檢查：玩家仍是同一個 entity、仍在原世界；依目前的 pose 重算 chunks、補上缺的 ticket；以 `ServerChunkManager.getWorldChunk` 判斷是否全部 FULL。以 javap 確認：`getWorldChunk` 走 `ChunkHolder.getOrNull(FULL)`，不阻塞；`getChunk(x, z, FULL, false)` 在 holder 已達 FULL 等級、但尚未完成時，會在 server thread 上 `runTasks` 等待，等於阻塞，所以不用它判斷。
- 全部 FULL 且站立條件成立的那一個 tick 內：重新取 lifecycle 與 exact ACTIVE world（instance 改變就取消）→ 準備落點 → `moveToUniverse`；MOVED 時保存 receipt。之後不論成功與否都釋放本 request 的 ticket，玩家自己的 ticket 會保住目的 chunk。
- 落點準備（`prepareLanding` `:419-441`）：bbox 下方每一欄的頂面不完整時放石頭；bbox 內有碰撞形狀或流體的方塊改成空氣；flags 只用 `NOTIFY_LISTENERS`。（這句描述不正確：沒有 `FORCE_STATE` 時仍會做鄰格 shape update，容器也會在 `onStateReplaced` 噴出物品；見下方 Fix round 1 的 M-3。）實際改動的位置寫在結果 log 的 `landingChanges=`。plan 原本只寫「下方放一塊地板」：Universe 與存檔共用種子，同一個 XYZ 的自然地形通常相同，但玩家站在自己放的方塊上、或站在挖開的洞裡時，目的地會缺地板或有方塊擋住，transfer 會以 `REJECT_TARGET_UNSAFE` 拒絕，所以補齊整個 bbox 的範圍。
- 上限 600 ticks；逾時、玩家離線或換世界都取消並釋放 ticket。站立條件在等待期間暫時不成立（例如正在跳）時繼續等，逾時才取消。
- `return` 同樣先加 ticket 等 FULL：玩家離開後來源 chunk 可能已卸載，transfer 會以 `REJECT_TARGET_NOT_FULL` 拒絕。

### 測試

- JUnit +1：`M5ClientProbeTest.registersQcprobeOnlyWithExactTrueProperty`。以 `CommandRegistrationCallback.EVENT` 發到新的 dispatcher，依序驗證：沒有 property → 沒有 `qcprobe`；`TRUE`（非精確值）→ 沒有；`true` → 有，op 1 拒絕、op 2 允許，`universe` 之下恰為四個沒有參數的子指令。Fabric 的事件是全域的、註冊之後不能撤銷，所以三段寫在同一個 test、依此順序執行。
- enter／return 的實際傳送需要真 client 玩家，沒有自動化覆蓋，由 F1b 驗證。GameTest 不變（clientProbe 不在 GameTest 的 classpath 上）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f1a/`；gate root 在 `.superpowers/verification/`。diff hash 是 `git diff fad924c 6d28fa0 -- <path>` 輸出的 SHA-256：`src` `f6ccd58d…`、`src/testmod` `c0b9010f…`、`src/test` `7a17438c…`、`build.gradle` `3dc7fccc…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| classpath baseline | `fad924c`，工作樹乾淨（`f1a/baseline-state.txt`） | `f1a/classpaths-baseline-fad924c.txt` | 以 `f1a/dump-run-classpaths.init.gradle` 列出每個 run 的 source set、runDir、vmArg 與解析後的 classpath |
| RED | `fad924c`；`build.gradle`＋4 個新檔，`M5ClientProbe` 暫時是無條件註冊完整指令樹的 stub（`f1a/red-state.txt`） | focused JUnit（`f1a/red-console.log`、`f1a/red-TEST-M5ClientProbeTest.xml`） | 預期 FAIL：`沒有 property 時不得註冊 /qcprobe ==> expected: <null> but was: <<literal qcprobe>>` |
| GREEN focused | 工作樹，blob 與 `6d28fa0` 相同 | `f1a/green-focused-console.log` | PASS；`M5_CLIENT_PROBE_ENABLED` 只在 `true` 那一段記一次；JUnit runtime 不需要 testmod 的 class |
| classpath 比對 | 同上 | `f1a/classpaths-after-green.txt` | 既有 9 個 run（`runClient`、`runClientLight`、`runClientRender`、`runGameTest`、`runGameTestLegacy`、`runM3Transfer`、`runM3Universe`、`runM4Recovery`、`runServer`）的 classpath，以及 main／client／testmod source set，都與 baseline 逐行相同。test 只多 `build/classes/java/clientProbe`。`runServerProbe`＝`runGameTest` 的 classpath＋clientProbe 輸出，沒有 clientOnly jar 或 `-client` 模組；`runClientProbe`＝`runClient`＋clientProbe 輸出＋testmod class，沒有 `resources/testmod` |
| 啟動 smoke | 同上（`f1a/smoke-state-f1a-smoke-5829b4de.txt`） | `f1a/smoke-f1a-smoke-5829b4de/`（`smoke-summary.json`） | PASS，見下方 |
| GREEN Full | 同上（`f1a/green-state.txt`） | `gate-f1a-green-full-38594f95056a4c0d83631cf7041ff595` | PASS |
| GREEN Main＋oracle | 同上 | `gate-f1a-green-main-183132da69324842ad053cc9887920d3` | PASS；`main-oracle.ps1` PASS |
| run-all | `6d28fa0`，`STATUS` 為空（`f1a/run-all-console.log`） | `run-all-0413bd8524c645a699cb1f959f346cc4` | `END ALL exit=0`。`SUMMARY default=153/153 windows=79 legacy=1/1 perTest=35+1 junit=442 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=308entries/265classes sources=195 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |
| nonce 必填 | `6d28fa0` | `f1a/guard-no-nonce.log`、`f1a/guard-disabled-nonce.log` | `runServerProbe` 不給 nonce、`runClientProbe -Pm5ProbeNonce=disabled`：都在 `build.gradle:180` 的 `doFirst` 失敗，沒有啟動遊戲 JVM，也沒有建立 `run/*-probe-disabled` |

Full 與 Main＋oracle 在 commit 之前以工作樹執行；commit 時四個檔的 `git hash-object` 與 gate 前記錄的相同（`f1a/green-state.txt`）。run-all 與 nonce 防護在 commit 之後執行。

GREEN 數字：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped，與 H4 相同；boundary oracle PASS；per-test receipt 35/35 PASS。
- JUnit 442，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 H4 多 1，就是 `M5ClientProbeTest`。
- JAR：release 308 entries（265 classes）、sources 195，與 H4 相同；testmod／gametest／clientprobe／`M5ClientProbe` 命中 0，probe 命中只有合法的 `ModPresenceProbe`。
- Full gate 的實際 `runGameTest` JVM classpath（argfile）與 H4 最後一次 Full（`gate-h4-fix1-green-full-ffbfbccbe28744ee81c108bcea1cd5e8`）逐項相同，110 個、順序一致。
- main-only oracle：layer 1／2／3 的 testmod 命中都是 0、world keys exact、正常停止、exact restore。另外對同一個 Main gate 做 clientprobe 專屬檢查（`f1a/green-main-clientprobe-check.json`）：mod list、`M5_CLIENT_PROBE` log、server JVM 的 commandline／argfile（`clientprobe`、`client-probe`、`quantumchamber.clientProbe`）、class-load（`M5ClientProbe`、`clientProbe` 來源）都是 0。

### 啟動 smoke（nonce `f1a-smoke-5829b4de`）

由 `f1a/f1a-smoke.ps1`（SHA-256 `a0d05644…`）執行，`f1a/f1a-smoke-verify.ps1`（`715c31f6…`）唯讀核對並寫出 `smoke-summary.json`。兩支腳本只在 gitignored 的 SDD workspace。所有 Gradle 呼叫都載入 `scripts/verification/runtime.init.gradle`（stdin、class-load log）。

| 階段 | runDir | 內容 | 結果 |
| --- | --- | --- | --- |
| S1 | 新建 `run/server-probe-f1a-smoke-5829b4de`（腳本寫入 `eula.txt`、`server.properties`） | `runServerProbe`；console 依序 `qcprobe universe status`、`allocate`、`status`、`stop` | exit 0。mod list 有 `quantumchamber`、`quantumchamber-clientprobe`、`quantumchamber-testmod`。`M5_CLIENT_PROBE_READY server=dedicated registered=true subcommands=[allocate, enter, return, status] op1Denied=true op2Allowed=true lifecycle=READY catalog=0`。allocate：`已配置並 checked flush：quantumchamber:universe/f1a00000-0000-4000-8000-00000000f1a0/overworld（EAGER_ENABLED）`；之後 status 為 `durable=true ACTIVE=false`。沒有物化（沒有 `M3_UNIVERSE_LOAD`），`M3_UNIVERSE_STOPPED_VERIFIED worlds=0` |
| S2 | 同一個 runDir（重啟） | `runServerProbe`；`status`、`allocate`、`stop` | exit 0。正式 bootstrap：`M3_UNIVERSE_LOAD key=…/overworld`、`M3_UNIVERSE_BOOTSTRAP_READY worlds=1`；READY 與 status 都是 `durable=true ACTIVE=true`；allocate 回報「已配置過 … ACTIVE=true，可以 enter」，沒有第二筆；`M3_UNIVERSE_STOPPED_VERIFIED worlds=1 runtimeReceipts=1 quarantine=0 contextDetached=true` |
| C1 | 新建 `run/client-probe-f1a-smoke-5829b4de`；`saves/f1a-smoke` 是 S2 停止後（`session.lock` 已釋放）的 world 複本；腳本寫入最小 `options.txt`（`onboardAccessibility:false` 等，略過首次啟動的無障礙引導畫面） | `runClientProbe --args='--username QC_PROBE --quickPlaySingleplayer f1a-smoke'` | exit 0。mod list 有 `quantumchamber`、`quantumchamber-clientprobe`，沒有 `quantumchamber-testmod`。integrated server：`M3_UNIVERSE_BOOTSTRAP_READY worlds=1`，`M5_CLIENT_PROBE_READY server=integrated registered=true …`（ACTIVE=true），`QC_PROBE joined the game`。10 秒後截圖（`client-in-world.png`：地形、hotbar、血量；視窗沒有焦點，原版自動開了暫停選單），再以 `CloseMainWindow` 結束（等同按視窗的關閉鈕）：`Stopping!` → `QC_PROBE left the game` → `M3_UNIVERSE_STOPPED_VERIFIED worlds=1`。沒有 crash report，stderr 為空，沒有 ERROR 行 |

- 實際 JVM classpath（argfile）：S1／S2 有 `resources/testmod` 與 `resources/clientProbe`，沒有 clientOnly jar；C1 有 `resources/clientProbe` 與 `classes/java/testmod`，沒有 `resources/testmod`。
- class-load：三個 JVM 都從 `build/classes/java/clientProbe/` 載入 `M5ClientProbe`。C1 從 `build/classes/java/testmod/` 只載入 `UniverseTransferTestAccess` 與 `$Lifecycle`；除了 `M5ClientProbe*`，沒有載入任何 `dev.quantumchamber.gametest.*`（沒有 testmod 的 mixin 或 probe）。
- C1 唯一和 mod 相關的 WARN 是 `Missing data pack quantumchamber-testmod`：world 由帶 testmod 的 dedicated server 建立，是 AGENTS 所說的 stale save metadata warning。其餘 WARN 是原版開發環境的雜訊：`Missing sound for event` 6 則、shader `rendertype_entity_translucent_emissive` 找不到 sampler 1 則。
- 開始前已有一個閒置的 Gradle daemon（PID 129228，12:15 建立，不屬於本 task）；smoke 結束後 Java 程序仍只有它，沒有殘留。
- smoke 沒有自動化 `enter`／`return`：需要在遊戲內以 op 玩家輸入指令，屬於 F1b。

### Fix round 1（`2bd75b1`）

處理首輪 code quality review 的 Important I-1 與 Minor M-1～M-6；spec review 的 Minor 1–3 是本 note 的文字，一併修正。行號以 `2bd75b1` 為準。

| 項目 | 處理 |
| --- | --- |
| I-1 enter 寬鬆、return 拒絕 | 原本的 `standingRefusal` 只檢查 `isOnGround`、Y 對齊與流體，但 `returnToSource` 在 receipt 的來源位置套用 `targetSafety`：bbox 下方每一欄都要 `isSideSolidFullSquare(UP)`，而且 `isSpaceEmpty`。站在樹葉上或方塊邊緣時 enter 會成功、return 被 `REJECT_TARGET_UNSAFE` 拒絕。現在拆成純判斷 `standingRefusal(riding, onGround, box, world, fluid, spaceEmpty)`（`M5ClientProbe.java:438-459`），在原本的條件之後以 `floorColumns`（`:462-471`，與 `prepareLanding` 及 `targetSafety` 的地板檢查同一個欄位範圍）逐欄檢查完整頂面，再檢查 `isSpaceEmpty(player, box)`。不合格時回報該欄座標與建議（站到完整方塊的中央或換個位置） |
| I-1 return 被拒時的訊息 | `nextStep`（`:388-400`）：RETURN 為 `REJECTED_BEFORE_MOVE` 時回「未移動、receipt 保留；修正來源位置後可以再 return，或用 `/execute in <來源世界> run tp @s <x> <y> <z>`」，座標取自 receipt 的來源位置。receipt 只在 RETURNED 時移除（行為不變） |
| M-1 put／hold 失敗 | `enter`、`returnHome` 的 `pending.put`＋`hold` 包在 `try`（`:178-184`、`:212-218`），`RuntimeException` 時以 `finish(request)` 釋放已加的 ticket、移除 pending，再回報失敗 |
| M-2 ticket 互相移除 | 改為 `ChunkTicketType<UUID>`（`UUID::compareTo`，`:95-96`），argument 是 `request.playerId()`（`hold`／`finish` `:358-368`）。原本 argument 是 chunk 本身，兩位玩家在同一個 chunk 的 ticket 相同，先結束的一方會把另一方的 ticket 移除 |
| M-3 落點寫入 | `prepareLanding`（`:477-495`）一律經 `replace`（`:498-501`）：寫入前 `Clearable.clear(world.getBlockEntity(pos))`，flags `NOTIFY_LISTENERS｜FORCE_STATE｜SKIP_DROPS`（比照 plan 決策 3 與原版 `/setblock`），只有 `setBlockState` 回傳 true 才記入 `landingChanges` |
| M-4 下一步提示與通知對象 | `nextStep` 依 outcome 回提示：`REJECTED_BEFORE_MOVE` 未移動、可修正後重試；`FAILED_ROLLED_BACK` 已回到移動前的位置；`FAILED_RECOVERY_REQUIRED` 依 F1b 規則停止並保留證據。失敗的 log 另帶 `next=`。`notify`（`:403-408`）改送給 `getPlayer(playerId)` 取得的目前在線 entity（重新登入後是新的 entity），離線時不送 |
| M-5 `build.gradle` | 刪除 `serverProbe` 重複的 `+ testmod.output`（`testmod.runtimeClasspath` 已含，`build.gradle:138-144`）；`check` 改為依賴 `clientProbeClasses`，涵蓋 resources（`:190-193`）；nonce 只在 `:153-157` 解析一次，兩個 run 與 `doFirst` 共用；註解改為「每一次新的驗收用新的 nonce（新的 runDir），同一次驗收中重啟同一個世界時沿用同一個」；兩個 probe run 加 `ideConfigGenerated false` |
| M-6 `requiredChunks` 與 test 的界線 | 新增 JUnit 覆蓋負座標與 chunk 邊界（見下）；`build.gradle:145-146` 註明 test 不得呼叫 Probe 的指令路徑（會載入 testmod 的 `UniverseTransferTestAccess`），需要時要另加 testmod 的 `classesDirs` |
| spec Minor 1–3 | 本 note：C1 的 WARN 改寫為「唯一和 mod 相關的 WARN」並列出其餘原版雜訊；兩種 run 的證據都標 `testmod-present`；寫明 F1b 尚未進行；使用說明補上「不要在 Chamber session 中使用」與新的 enter／return 行為；記錄 controller 的兩項裁定 |

JUnit +5：`M5ClientProbeSafetyTest`（`src/test/java/dev/quantumchamber/gametest/`）。`Bootstrap.initialize()` 之後，以假的 `BlockView` 呼叫純判斷，不走指令路徑：

- `acceptsStandingInsideOneFullBlockColumn`：正向對照，石頭上、單欄。
- `refusesLeavesUnderTheFeet`：腳下是樹葉（頂面不完整），訊息含 `8, 63, 8`。
- `refusesStandingOnTheEdgeOverAir`：bbox x＝8.65..9.25 跨兩欄，x＝8 是石頭、x＝9 是空氣，訊息含 `9, 63, 8`。
- `refusesWhenTheBoxIsNotEmpty`：`isSpaceEmpty` 不成立。
- `requiredChunksMatchTheTransferSafetyRangeAcrossChunkBorders`：x＝-0.3..0.3 → chunk x -1..0；13.7..14.3 → 0..1；原點的四個象限 → 4 個 chunk；8.2..8.8 → 只有 (0, 0)。

證據在 `f1a/fix1/`。diff hash 是 `git diff 779601d 2bd75b1 -- <path>` 輸出的 SHA-256：`src` `bbd223fc…`、`src/testmod` `bc13bd38…`、`src/test` `935e507c…`、`build.gradle` `b0b26e97…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `779601d`；`M5ClientProbe.java` 只把 `standingRefusal` 拆成純判斷、保留原本的條件（`world`、`spaceEmpty` 尚未使用），加上新測試檔（`f1a/fix1/red-state.txt`） | focused JUnit（`f1a/fix1/red-console.log`、`red-TEST-*.xml`） | 預期 FAIL 3/5：樹葉、邊緣、`isSpaceEmpty` 不成立都被接受（`expected: not <null>`）。正向對照與 `requiredChunks` PASS；`M5ClientProbeTest` 1/1 PASS |
| GREEN focused | 工作樹，blob 與 `2bd75b1` 相同（`f1a/fix1/green-state.txt`） | `f1a/fix1/green-focused-console.log` | `M5ClientProbeSafetyTest` 5/5、`M5ClientProbeTest` 1/1 |
| classpath 比對 | 同上 | `f1a/fix1/classpaths-after-fix1.txt` | 與首輪 GREEN 的 dump 相比，既有 9 個 run 與全部 source set（含刪除重複項後的 `serverProbe`）逐行相同。兩個 probe run 多出 `build/resources/clientProbe`，只因為這次 dump 時它已產生（Loom 只列出存在的路徑）；首輪 smoke 的實際 JVM classpath 本來就有它 |
| GREEN Full | 同上 | `gate-f1a-fix1-green-full-bf8254c391154072a817eccf050824d9` | PASS |
| GREEN Main＋oracle | 同上 | `gate-f1a-fix1-green-main-ac16fcbcf5504e3192840a9550a01f29` | PASS；`main-oracle.ps1` PASS；clientprobe 專屬檢查全為 0（`f1a/fix1/green-main-clientprobe-check.json`） |
| 啟動 smoke | 同上（`f1a/fix1/smoke-state-f1a-fix1-smoke-14c7cc00.txt`） | `f1a/smoke-f1a-fix1-smoke-14c7cc00/` | PASS（S1、S2、C1；`smoke-summary.json`），結果與首輪相同，見下 |
| run-all | `2bd75b1`，`STATUS` 為空（`f1a/fix1/run-all-console.log`） | `run-all-d5a7b33f1214479790a905c7b16081a5` | `END ALL exit=0`。`SUMMARY default=153/153 windows=79 legacy=1/1 perTest=35+1 junit=447 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=308entries/265classes sources=195 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |
| nonce 必填 | `2bd75b1` | `f1a/fix1/guard-no-nonce.log`、`f1a/fix1/guard-disabled-nonce.log` | `runServerProbe` 不給 nonce、`runClientProbe -Pm5ProbeNonce=disabled`：都在 `build.gradle:184` 的 `doFirst` 失敗，沒有啟動遊戲 JVM，也沒有建立 `run/*-probe-disabled` |

Full 與 Main＋oracle、smoke 在 commit 之前以工作樹執行；commit 時三個檔的 `git hash-object` 與 gate 前記錄的相同（`f1a/fix1/green-state.txt`）。run-all 與 nonce 防護在 commit 之後執行。

GREEN 數字：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped；boundary oracle PASS；per-test receipt 35/35 PASS。
- JUnit 447，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比首輪多 5，就是 `M5ClientProbeSafetyTest`。
- JAR：release 308 entries（265 classes）、sources 195，不變；testmod／gametest／clientprobe／`M5ClientProbe` 命中 0，probe 命中只有 `ModPresenceProbe`。Full 的 `build` 現在也執行 `processClientProbeResources`（`check` → `clientProbeClasses`）。
- 啟動 smoke：S1、S2 的 mod list 有 `quantumchamber`、`quantumchamber-clientprobe`、`quantumchamber-testmod`，`M5_CLIENT_PROBE_READY server=dedicated registered=true`，allocate → 重啟 → bootstrap 物化（`ACTIVE=true`），`STOPPED_VERIFIED` 依序為 `worlds=0`、`worlds=1`。C1 的 mod list 只有 `quantumchamber`、`quantumchamber-clientprobe`，`M5_CLIENT_PROBE_READY server=integrated registered=true`、`QC_PROBE joined the game`，`CloseMainWindow` 後 `STOPPED_VERIFIED worlds=1`；stderr 為空、沒有 crash report；WARN 的種類與首輪相同（`Missing data pack quantumchamber-testmod` 1 則、`Missing sound for event` 2 則、shader sampler 1 則；`Missing sound` 的則數與首輪的 6 則不同，都是原版雜訊）。前後 Java 程序都只有既有的閒置 daemon（PID 129228），沒有殘留。
- I-1 的修正沒有在遊戲內實測（smoke 不輸入指令）；由 JUnit 覆蓋，F1b 會實際走到。

### 給 F1b 的使用說明

- 單人：`runClientProbe -Pm5ProbeNonce=<新 nonce>`（可加 `--args='--username QC_A …'`）。新建世界時要開「允許作弊」，或進入後「在區域網路開放」並允許作弊，host 才是 op、才看得到 `/qcprobe`。fresh runDir 第一次啟動會出現無障礙引導畫面。
- 流程：`/qcprobe universe allocate` → 儲存並離開到標題畫面 → 重新進入同一個世界（integrated server 重啟，由 bootstrap 物化）→ `/qcprobe universe status` 確認 `ACTIVE=true` → 站在完整方塊上 `enter` → `return`。
- Dedicated：`runServerProbe -Pm5ProbeNonce=<新 nonce>`。runDir 要先放 `eula.txt` 與 `server.properties`（`online-mode=false`；`server-port` 要固定，smoke 用的 `0` 是隨機埠）。console 下 `qcprobe universe allocate` → `stop` → 以同一個 nonce 再啟動；client 連線後由 console `op <名字>`。`/qcprobe` 是 server 指令，client 端不需要 clientprobe。
- receipt 不跨 server 重啟；重新登入（同一個 server instance）仍有效。重啟後要離開 Universe，用原版 `/execute in minecraft:overworld run tp @s <x> <y> <z>`。死亡重生後 receipt 會在下一次 `return` 時作廢。
- 重新登入、重啟、重生之後，用 `status` 讀玩家所在的 world key。log 標記：`M5_CLIENT_PROBE_ENTER status=OK … outcome=MOVED …`、`M5_CLIENT_PROBE_RETURN status=OK … outcome=RETURNED …`、`landingChanges=[…]`；失敗時是 `status=FAIL` 或 `status=CANCELLED` 並附原因。
- 兩種 run 的證據都標 `testmod-present`：serverProbe run 帶整個 testmod（mixin 與各自由 property 控制的 probe）；clientProbe run 的 classpath 有 testmod 的 class（`UniverseTransferTestAccess`），只是 testmod 沒有以 mod 的身分載入。兩者都不能當成 main-only 證據。
- 不要在 Chamber session 中使用：probe 直接以 M3 的 transfer 移動玩家，不經過 session、journal 與返還流程。
- `enter` 只在玩家站穩時受理：腳下每一格都是完整方塊頂面（不能站在樹葉、半磚上，也不能站在方塊邊緣、腳下一半懸空），而且不與方塊重疊。不合格時回報腳下的座標，請站到完整方塊的中央或換個位置。
- `return` 在傳送前被拒（`REJECTED_BEFORE_MOVE`）時玩家沒有移動、receipt 保留：修正來源位置後可以再 `return`，或照訊息裡的 `/execute in <來源世界> run tp @s <x> <y> <z>` 離開。`FAILED_RECOVERY_REQUIRED` 時依 F1b 規則停止並保留證據。

### 觀察（未修改）

- `main-oracle.ps1` 的 layer 1／2 與 `run-all.ps1` 的 JAR 樣式只比對 `testmod` 字樣與 `src/testmod/java` 的型別。clientProbe 的輸出路徑（`build/classes/java/clientProbe`）不含 `testmod`，若 clientProbe 洩漏到 `runServer`，只有 layer 3 的 `dev.quantumchamber.gametest.` 前綴與 JAR 的 `gametest`／`probe` 樣式會抓到。本 task 另做了專屬檢查（上方，全為 0）。controller 裁定把 `clientProbe` 加進 `scripts/verification/` 的樣式交給 F6。
- probe 的 allocate 直接寫 catalog，不經 F2b 之後的 allocation ledger。F2b 上線後，用 probe 配置過的世界可能被 spec §6 判為 catalog 與 ledger 不一致；probe 世界都是一次性的，不要拿來測 M5 玩法。
- JUnit 在同一個 JVM 內留下全域的指令與 lifecycle listener（Fabric 事件不能撤銷）。目前沒有其他 JUnit 建立 `CommandManager` 或啟動 server。
- smoke 的 driver 與核對腳本沒有 tracked。之後若要重跑，需要另開 task 整理到 `scripts/verification/`。

## F1b：真 client 驗證

- 結論：通過。plan F1b 的四項檢查全部通過；沒有非預期的斷線，也沒有 crash report。M5 玩法實作從 F2a 繼續。
- 執行：2026-10-01 22:04–22:36，Claude 代操作（Ben 授權），以鍵鼠自動輸入、截圖與唯讀指令進行。程式基準 `2e90ae9`（code 與 `2bd75b1` 相同）。本 task 不改 tracked 程式。
- 證據：repo 外的 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-10-01-f1b\`。`MANIFEST-sha256.txt` 列出 80 檔（不含 MANIFEST 本身），其中截圖 56 張；MANIFEST 的 SHA-256 為 `58813ee5b5a111ae6f0028017416f490eaa401fb0490aea9a419f60ca5cdcb6f`。docs review 之後補入兩個 client runDir 的 `command_history.txt` 並重建 MANIFEST（原本 78 檔，SHA-256 `c36692f9…a61a3`）。runDir 為 `run/server-probe-f1b-ded-befbaafc`、`run/client-probe-f1b-dc-befbaafc`、`run/client-probe-f1b-sp-d826199d`（gitignored，保留）。證據全部標 `testmod-present`。

### 結果

| 項目 | Dedicated（`runServerProbe`＋`runClientProbe` 連線） | 單人（`runClientProbe` 的 integrated server） |
|---|---|---|
| allocate → 重啟 → 物化 | allocate 後 `/stop`，以同一個 nonce 重啟：`READY … ACTIVE=true` | 新建世界（創造、和平、允許指令）後 allocate → 儲存並離開 → 重新進入：`READY … ACTIVE=true` |
| enter／return | `ENTER OK outcome=MOVED`，`RETURN OK outcome=RETURNED`，回到來源座標 | 同左 |
| 天空、時間、天氣、地形 | F3 顯示 `quantumchamber:universe/f1a00000-…/overworld`。`/time set 18000` 後是星空夜色，`/weather rain` 後下雨，時間從 6000 繼續走到 6081。地形可見 | 同左（時間 6000→6097） |
| 移動、放置、破壞 | 右鍵放置橡木材（`oak_planks`）、左鍵破壞。server 端對 (-23, 76, 63) 先測 `oak_planks`、再測 `air`，兩次都「測試通過」（座標見 `ded-logs\client-command_history.txt`）。按 W 前進，server 回報 z 從 61.5 到 63.655 | 同左，目標是 (-3, 86, 5)（`sp-logs\client-command_history.txt`）；z 從 3.5 到 5.655 |
| 在動態世界重新登入 | 中斷連線後重新加入：`world=quantumchamber:universe/…` | 儲存並回到標題畫面後重進：同左 |
| 在動態世界重啟 server | `/stop` 後重啟：仍在 `quantumchamber:universe/…`（`receipt=none`，receipt 依設計不跨重啟） | 關閉遊戲，再以 `--quickPlaySingleplayer` 重開：同左 |
| 在動態世界死亡 | `/kill` 後重生在 `minecraft:overworld` 的 (-35.5, 65, 50.5)（level.dat 的世界出生點是 (-32, 68, 48)，原版在附近選位） | 重生在 `minecraft:overworld` 的 (-8.5, 79, -0.5) |

- Sodium＋Iris：在單人 runDir 測。Sodium 0.6.13 與 Iris 1.8.8（兩者都是 `+mc1.21.1` 版，跑在 MC 1.21）與光影包，從外部備份 `C:\Users\Ben\Documents\QC-m1-m4-worktree-backup-20261001\run\client-render\` 的 `mods\`、`shaderpacks\` 唯讀複製，SHA-256 與來源相符（`render-mods-sha256.txt`）。
  - 啟動時偵測到 `sodium=true, iris=true`。
  - 光影關閉（`enableShaders=false`）：enter → return 正常，F3 顯示 Sodium Renderer。
  - 按 K 開啟 Complementary Reimagined r5.9.3 後 enter → return：Iris 先後 `Creating pipeline for dimension quantumchamber:universe/…/overworld` 與 `… minecraft:overworld`，畫面有光影效果，F3 有 Iris Shadow info。
  - 結束時 `iris.properties` 仍是 `enableShaders=true`，光影沒有被強制關閉。

### 偏差與觀察

- Dedicated 的 client 用 `runClientProbe`（plan 寫 `runClient`）：`runClient` 的 runDir 固定是 `run/client-base`，改用 `runClientProbe` 才能以新 nonce 取得全新的 runDir，不碰既有的 client 資料。clientProbe 在 client 端多了 `quantumchamber-clientprobe` mod（mod list 與 `M5_CLIENT_PROBE_ENABLED`）和 testmod 的 class；`/qcprobe` 由 dedicated server 註冊與執行，不影響連線測試；證據同樣標 `testmod-present`。
- 15 秒接手：單人兩次重新進入世界時，等待迴圈在 `latest.log` 沒有比對到加入紀錄，玩家分別閒置約 90 秒與 130 秒。事後在同一個工作階段以同一個條件重測可以命中（只有工具輸出，未留檔）；推測是寫入時序造成，未查明。世界是和平＋創造模式，玩家沒有受傷。之後改以 Gradle 輸出偵測。
- 未預期的方塊改動：單人動態世界 (-2, 71, 0) 的一格草方塊被破壞（`sp-17`～`sp-19` 截圖中視角朝下看著這個坑；docs reviewer 唯讀解析 region 檔，同座標在主世界是 `grass_block`、在 Universe 是 `air`，下方 (-2, 70, 0) 的泥土已長成草）。指令紀錄裡沒有任何指令動到這個位置，probe 在 (-2.5, 72, -0.5) 的兩次 enter，`landingChanges` 都是空的。依截圖，改動發生在 22:26:13 第二次 enter 到 22:29:20 之間，期間有儲存離開、從清單重進與約 130 秒閒置。推測是自動輸入在世界中誤觸左鍵，但無法從紀錄確定時刻，原因未查明。不影響 F1b 各項判定。
- 站立檢查：dedicated 與單人各有一次 `enter` 因為腳下不是完整方塊頂面而被拒，這是預期行為。單人那次 F3 顯示 `M: 78 ML: 73`，表示腳下 y=78 是樹葉；dedicated 那次在森林的出生點附近，推測也是樹葉（拒絕訊息是通用文字，沒有截圖確認方塊種類）。以 `/setblock` 把腳下換成石頭、`/tp` 到方塊中央後再 enter，動態世界的落點同時產生 `landingChanges=[…=stone]`。
- 放置與破壞的目標是用 `/fill` 在空中鋪的石台（避開草叢與樹冠），朝向用 `/tp` 設定。放置與破壞本身是滑鼠操作，操作前以 F3 的 Targeted Block 確認目標。
- Dedicated 在 `/tp` 到石台的那一刻，出現一次原版的 `QC_A moved too quickly! 0.0,12.0,8.0`，沒有其他影響。
- 單人從世界清單重新進入時，原版顯示「使用實驗性設定的世界不受支援」；選「我知道我在做什麼！」後繼續。用 `--quickPlaySingleplayer` 進入時不顯示。level.dat 的世界生成設定裡只有 M1 起就有的 `quantumchamber:superposition`；Universe 的維度鍵只出現在玩家的 `LastDeathLocation`（原版的死亡紀錄）。推論：警告來自 datapack 維度，不是 M5 新增的；這點未另外驗證。
- Iris 對光影包回報 uniform 與 block ID map 的 WARN（例如 `BIOME_PALE_GARDEN`、`minecraft:stone_slab has no property with the name variant`），`run3-render-latest.log` 中有 9 段附在這些 WARN 上的 `java.lang.RuntimeException: Unknown variable …` stack trace，之後 grep `Exception` 時不要誤判。這些 WARN 在 22:34:39 開始建立 `minecraft:overworld` pipeline 之後（22:34:40–41）第一次出現，早於 22:35:09 進入動態維度，和動態維度無關；推論是光影包版本與 1.21 的差異，不是本專案造成的。
- 未做：H4 提到的「凍結中騎乘載具」是可選的觀察項，本次沒有做。probe 不建立 Chamber session，玩家不會被凍結。
- 所有斷線紀錄都對應三種預期操作：`/stop`（「伺服器已關閉」）、dedicated 重新登入測試時玩家主動中斷連線（`ded-s2-server.log` 的 `QC_A lost connection: Disconnected`），以及儲存離開與關閉遊戲（integrated 的「連線中斷」）。三個 runDir 都沒有 `crash-reports`。結束時的 Java 程序清單只剩既有的閒置 Gradle daemon（PID 129228）；這份清單只有工具輸出，未留檔。
