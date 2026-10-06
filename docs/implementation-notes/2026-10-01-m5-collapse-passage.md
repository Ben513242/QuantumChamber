# M5：塌縮與單向通道（實作紀錄）

- Spec：[`docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md`](../superpowers/specs/2026-10-01-m5-collapse-passage-design.md)（v5.3；最後一次修改 `8172104`）
- Plan：[`docs/superpowers/plans/2026-10-01-m5-collapse-passage.md`](../superpowers/plans/2026-10-01-m5-collapse-passage.md)（`ec7229d`、`9c315f8`；之後的修訂見 git log，最新為 `b712193`）
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
- 未預期的方塊改動：單人動態世界 (-2, 71, 0) 的一格草方塊被破壞（`sp-17`～`sp-19` 截圖中視角朝下看著這個坑；docs reviewer 唯讀解析 region 檔，同座標在主世界是 `grass_block`、在 Universe 是 `air`，下方 (-2, 70, 0) 的泥土已長成草）。指令紀錄裡沒有任何指令動到這個位置，probe 在 (-2.5, 72, -0.5) 的兩次 enter，`landingChanges` 都是空的。依截圖，改動發生在 22:26:13 第二次 enter 到 22:29:20 之間，期間有儲存離開、從清單重進與約 130 秒閒置。原因：使用者 2026-10-02 說明，是使用者用遠端桌面連線造成的，不是自動輸入誤觸（取代先前的推測）。上述指令紀錄與 `landingChanges` 也沒有 mod 或 probe 改動這一格的跡象。不影響 F1b 各項判定。
- 站立檢查：dedicated 與單人各有一次 `enter` 因為腳下不是完整方塊頂面而被拒，這是預期行為。單人那次 F3 顯示 `M: 78 ML: 73`，表示腳下 y=78 是樹葉；dedicated 那次在森林的出生點附近，推測也是樹葉（拒絕訊息是通用文字，沒有截圖確認方塊種類）。以 `/setblock` 把腳下換成石頭、`/tp` 到方塊中央後再 enter，動態世界的落點同時產生 `landingChanges=[…=stone]`。
- 放置與破壞的目標是用 `/fill` 在空中鋪的石台（避開草叢與樹冠），朝向用 `/tp` 設定。放置與破壞本身是滑鼠操作，操作前以 F3 的 Targeted Block 確認目標。
- Dedicated 在 `/tp` 到石台的那一刻，出現一次原版的 `QC_A moved too quickly! 0.0,12.0,8.0`，沒有其他影響。
- 單人從世界清單重新進入時，原版顯示「使用實驗性設定的世界不受支援」；選「我知道我在做什麼！」後繼續。用 `--quickPlaySingleplayer` 進入時不顯示。level.dat 的世界生成設定裡只有 M1 起就有的 `quantumchamber:superposition`；Universe 的維度鍵只出現在玩家的 `LastDeathLocation`（原版的死亡紀錄）。推論：警告來自 datapack 維度，不是 M5 新增的；這點未另外驗證。
- Iris 對光影包回報 uniform 與 block ID map 的 WARN（例如 `BIOME_PALE_GARDEN`、`minecraft:stone_slab has no property with the name variant`），`run3-render-latest.log` 中有 9 段附在這些 WARN 上的 `java.lang.RuntimeException: Unknown variable …` stack trace，之後 grep `Exception` 時不要誤判。這些 WARN 在 22:34:39 開始建立 `minecraft:overworld` pipeline 之後（22:34:40–41）第一次出現，早於 22:35:09 進入動態維度，和動態維度無關；推論是光影包版本與 1.21 的差異，不是本專案造成的。
- 未做：H4 提到的「凍結中騎乘載具」是可選的觀察項，本次沒有做。probe 不建立 Chamber session，玩家不會被凍結。
- 所有斷線紀錄都對應三種預期操作：`/stop`（「伺服器已關閉」）、dedicated 重新登入測試時玩家主動中斷連線（`ded-s2-server.log` 的 `QC_A lost connection: Disconnected`），以及儲存離開與關閉遊戲（integrated 的「連線中斷」）。三個 runDir 都沒有 `crash-reports`。結束時的 Java 程序清單只剩既有的閒置 Gradle daemon（PID 129228）；這份清單只有工具輸出，未留檔。

## F2a：Schema 4、狀態機、唯一性與終局 API

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `7c3c247` `refactor: extract the shared journal NBT helpers`（parent `cfe5133`）：新增 `persistence/JournalNbt.java`，改 `CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionRecoveryState.java`。共用的 strict NBT helper（`requireType`、`uuid`、`string`、`compound`、兩個 `compounds`、兩個 `keys`、`canonicalIdentifier`）移到 `JournalNbt`，`CandidateJournalCodec` 不再 `import static SessionRecoveryRecord.*`。訊息文字不變。`SessionRecoveryRecord` 保留 `requireType`／`uuid`／`string` 三個委派，因為 `PlayerRecoveryCheckpoint.java:18-21` 仍以這個名稱取用，而它不在本 task 的檔案清單。refactor 前後 `gradle test --rerun` 都是 447（2 skipped），同一組 class。
  - `b400b7f` `feat: add journal schema 4 with collapse states and a checked terminal`（parent `7c3c247`）：`superposition/SessionState.java`；新增 `collapse/CollapseDestination.java`、`collapse/SealedReason.java`、`persistence/CollapseFields.java`；改 `persistence/SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`CandidateJournalCodec.java`；測試新增 `SessionRecoverySchema4Test.java`，改 `SessionRecoveryStateTest.java` 一行（見「既有測試的處置」）。
  - 本段的 docs commit 接在 `b400b7f` 之後。
- Code range `cfe5133..b400b7f`。
- 沒有接任何 runtime 呼叫點：production 不會呼叫 `markCollapseAuthoritiesInitialized`，journal 照舊寫 schema 3（或 2），也沒有任何程式產生塌縮狀態，所以 production 行為不變。
- Review：首輪結果與 fix round 1（`2d13248`）見本節最後的「Fix round 1」。
- 最終 review：fix round 2（`b488bfc`）的 re-review Approved（Critical 0、Important 0、Minor 0）。**F2a 完成**：range `cfe5133..7bae536`（code `7c3c247`、`b400b7f`、`2d13248`、`b488bfc`）。

### Code-truth（`b400b7f`）

行號以 `b400b7f` 為準；`State` 指 `persistence/SessionRecoveryState.java`，`Record` 指 `persistence/SessionRecoveryRecord.java`。

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| 新狀態與謂詞 | `SessionState.java:4-12` | 新增 `COLLAPSING`、`PASSAGE_READY`、`SEALED`。`retainsReceipt()`＝MEASURED 或三個塌縮狀態；`collapseState()`＝三個塌縮狀態。`State.receiptRetained`（`:250`）委派前者 |
| `Collapse` 欄位 | `CollapseFields.java:20-97` | 8 個欄位照 plan。`EMPTY`（`:30`）是 M4 狀態的空 Collapse，也是唯一 `destination==null` 的值。建構子（`:32-52`）只驗形狀：Crossed／RecoveryRequired 不得重複；非 EMPTY 時 `CollapseRevision==1`、`CommittedAtGameTime>=0`、opened／expires 同為 -1 或 `expires==opened+1200`（含溢位檢查）。`committed`／`withPassage`／`withCrossed`／`sealed`／`transferRecoveryRequired`／`retry`（`:59-96`）對應 spec §5 的每一條邊；`sealed(TRR)` 直接拒絕 |
| 目的地與原因 | `collapse/CollapseDestination.java`、`collapse/SealedReason.java` | sealed interface `Source`／`Universe(UniverseId)`。`SealedReason` 8 個值；`retryable()` 為 ALLOCATION_IO_FAILED、PROJECTION_FAILED、MATERIALIZE_FAILED、AUTHORITY_UNREADABLE；`restartOnly()` 為後兩者（§11.2） |
| record 第 12 個欄位 | `Record:39-53` | canonical 建構子多 `CollapseFields collapse`；保留 M4 的 11 欄位建構子（補 `EMPTY`），testmod 與既有呼叫端不用改 |
| 合法組合 | `Record:101-131`、`validateCollapse` `:138-170` | 原本寫死 MEASURED 的三處（legacy 不得有、SELECTED 的狀態、可保留空 lease）改用 `retainsReceipt()`。M4 狀態的 Collapse 必須是 `EMPTY`，塌縮狀態不得是 `EMPTY`。Destination 必須對應被選中的候選：SOURCE→`Source`、EXISTING→同一個 UniverseId、NEW→`Universe`（推導值的比對留給 F2b／F4a）。COLLAPSING：opened 未寫、Crossed／RecoveryRequired 空、沒有 SealedReason（部分返還、lease 原樣都可以；空 lease 蘊含全員已返還，沿用 `:128-130`）。PASSAGE_READY：全員已返還、lease 空、UNIVERSE、opened 已寫、Crossed⊆參與者、RecoveryRequired 空、沒有 SealedReason。SEALED：全員已返還、lease 空、UNIVERSE、有 SealedReason；TRR 時 opened 已寫、RecoveryRequired 恰一位參與者且不在 Crossed、Crossed⊆參與者；其他原因 opened 為 -1、兩個清單都空 |
| `selectedCandidate()` | `Record:173-178` | SELECTED 對應的 ledger 候選；`validateCollapse` 與 `TerminalOrigin` 共用 |
| `withCollapse` | `Record:188-191` | 換狀態與 Collapse，參與者、lease、收據原樣。`withProgress`（`:181-185`）保留 Collapse |
| 單調 authority 與狀態邊 | `sameAuthority` `Record:211-225`、`collapseEdge` `:231-275`、`startsWith` `:277-279` | 原本只對 MEASURED 的單調條件（ledger 完全不變、`restoreEntryEffectOnReturn` 不變、returned 只增不減、lease 原樣或清空）擴大到 `retainsReceipt()`。`collapseEdge`：ARMING／SUPERPOSITION／RETURNING 不得進入塌縮狀態（M4 的邊不另檢查）；MEASURED 只到 MEASURED 或 COLLAPSING；塌縮狀態之間 revision、committed、Destination 不可變，其餘欄位依邊：C→C 全等（只有返還與清 lease）；C→R 只寫 opened／expires；C→S 只寫 SealedReason 且不得是 TRR；R→R 只在 Crossed 尾端附加（前綴比對）；R→S 只接受 TRR，opened／expires 與 Crossed 原樣；S→S 全等；S→C 只清空 SealedReason，TRR 沒有這條邊 |
| codec | `CandidateJournalCodec.java:29-87`；`Record:306-348`、`:350-425` | `Collapse` 的寫出與 strict 讀取：M4 狀態寫空 compound；塌縮狀態恰 8 個 key（`keys("Collapse", …)`），Destination 依 `Kind` exact key，UUID 清單用 4-int 的 int array、空清單必須是 END held type，SealedReason 空字串表示沒有。`toNbt(int envelopeSchema)`：schema 4 才寫 `Collapse`，schema 3 以下遇到非空 Collapse 丟例外。`fromNbt` 接受 1–4；schema 4 的 record exact key 為 schema 3 的 11 個加 `Collapse`；schema 3 讀入補 `EMPTY`，所以 schema 3 的塌縮狀態一律被建構子拒絕 |
| envelope 與兩個旗標 | `State:50-51`、`fromNbt` `:92-114`、`:123-134` | 版本白名單 1–4，schema ≥3 的 envelope exact key；`candidateInitialized = schema>=3`、`m5Evidence = schema==4`、`m5ReadyThisInstance` 不受讀檔影響。`markCollapseAuthoritiesInitialized()` 兩個旗標都設真，不標 dirty（§12：之後第一次 checked 寫入才升 schema 4）。`requireType(nbt,"SchemaVersion",3)` 的 `3` 原本就是 `NbtElement.INT_TYPE`，不是 schema 上限，改寫成常數 |
| 寫出式子 | `writeNbt` `State:544-551`、`writtenSchema` `:556-562` | 有 legacy → 2；否則 `candidateInitialized` → (`m5Evidence` ? 4 : 3)；否則 → 空 journal 的 2。兩個旗標都不會變回假，所以不降版。`envelopeSchema` 照舊先驗不混裝 |
| `occupies` | `State:257-265` | spec §5 的佔用謂詞。`validateOwnership`（`:620-630`）與 `participantsAvailable`（`:479-483`）改用它；Chamber 與 slot 的唯一性不變 |
| `remove` | `State:529-538` | `records`、`flushed`、`authorityHistory` 任一份是 `receiptRetained` 的狀態就拒絕 |
| 一般 `put` | `State:488-515`、`requireCollapseWrite` `:520-528` | 新增：tombstone 拒絕；塌縮狀態要求 `m5Evidence`、至少有一份既有 authority（current、flushed 或 history）可以沿狀態邊進入，而且任一份到 record 是 restore 類邊時拒絕。塌縮狀態的一般 put 把 session 記為 roll-forward 尚未確認（與 flushed 相同時清除） |
| roll-forward 提交 | `commitRollForward` `State:355-372` | 前置：current 與 flushed 都 exact 等於 expected、未隔離、未終局、有變更，而且邊是 C→C、R→R、C→S 或 R→S。put→flush 之後以 flushed 判定：等於 next 就 COMMITTED；否則 current 保留 next、保持 dirty，回 `ROLL_FORWARD_PENDING`（附 root cause） |
| restore 提交 | `commitRestore` `State:381-405` | 前置同上，邊必須是 M→C、C→R 或 S→C；進入 COLLAPSING 另外要求 `m5ReadyThisInstance`。flushed 等於 next 就 COMMITTED；否則 current 仍 exact 等於 next 時 `restoreCheckedAuthority`（current 還原為 flushed＝expected、history 還原為 checked authority、保持 dirty），回 `RESTORED`；CAS 不符時隔離，回 `QUARANTINED` |
| 終局 | `terminate` `State:413-448`、`TerminalOrigin` `:322-347` | 見下方「第 6 點」 |
| 結果型別 | `CommitResult` `State:280-299`、`TerminalResult` `:302-319` | `status`＋`Optional<RuntimeException> failure`（只有 COMMITTED／REMOVED 沒有 failure）。commit 本身不記 log，由 caller 依原因去重；終局失敗記 WARN（spec §5） |
| 讀回確認的判定 | `checkedFlush` `State:451-453` | 三個入口都在 flush 之後以 flushed 判定結果；flush 之後（讀回已確認）才出現的例外不會讓已確認的寫入被當成失敗而還原 |
| collapse view | `collapseView` `State:271-274`、`rollForwardPending` `:277`；清除點 `save` `:594`、`restoreCheckedAuthority` `:238-245` | roll-forward 尚未確認、而且未隔離時回 current，其餘回 flushed。旗標在 checked flush 讀回確認 flushed 等於 current 時清除；restore 類失敗不設旗標 |
| 未 checked 偵測 | `uncheckedCandidateSessions` `State:147-152`、`authorityChecked` `:153-158`、`recoveryWritesSafe` `:141` | 見下方「第 4 點」 |
| 收斂目標 | `convergenceTarget` `State:229-236` | switch 明確列出三個塌縮狀態：與 MEASURED 相同，原樣保留 flushed |
| `abortUnchecked` | `State:177-190` | 行為不變；`@apiNote` 改為指向 `commitRestore`／`terminate` |

### 第 4 點：M5 欄位如何參與未 checked 偵測

- 決定：`uncheckedCandidateSessions()`（以及由它推導的 `recoveryWritesSafe()`）除了 candidate 三欄位，另把「current 相對 flushed 跨過 restore 類狀態邊」的 session 算進去：MEASURED→COLLAPSING、COLLAPSING→PASSAGE_READY、SEALED→COLLAPSING（`authorityChecked`）。current 是塌縮狀態而沒有 flushed 時也算。
- roll-forward 類（COLLAPSING 的返還與清 lease、Crossed、進入 SEALED）不算：spec §6 要它由之後的任何寫出帶出去，plan 也要求失敗後 `recoveryWritesSafe` 仍為真。
- 理由：
  - restore 類內容只能經 `commitRestore` 的同步 put→flush 承認，失敗時立即 CAS 還原或隔離，所以正常情況下只在那個同步窗口中可見，與 M4 candidate 窗口的語意相同（`commitRestoreWindowIsVisibleAsUncheckedAuthority`）。
  - 雙重故障留下殘留時，H3 的 recovery tick（`SessionRecoveryManager.convergeUnchecked`）會隔離它，`convergeQuarantined` 以 flushed 的狀態收斂（`convergenceTarget` 對塌縮狀態原樣保留），之後的寫出只帶 flushed，不會承認 restore 類內容。
  - 寫入端的保證：一般 `put` 拒絕 restore 類邊（current、flushed、history 任一份到 record 是 restore 邊都拒絕），所以除了 `commitRestore`，沒有路徑能寫入這三條邊；終局只能經 `terminate`（`remove` 拒絕 `receiptRetained` 的狀態）。
- `abortUnchecked` 維持只回退 candidate authority：它是 M4 candidate owner 的入口，M5 的 restore 類寫入有自己的 CAS（`commitRestore`、`terminate`）。

### 第 6 點：終局 API

- 前置：未隔離、未終局（tombstone）；current 與 flushed 都 exact 等於 expected；`TerminalOrigin.accepts(expected)`。任一不成立回 `REJECTED`，journal 不變。
- `TerminalOrigin` 的實際判定（`State:332-347`）：
  - `PASSAGE_ENDED`：state 為 PASSAGE_READY。
  - `SOURCE_FAST_PATH`：state 為 COLLAPSING、Destination 為 `Source`、全員已返還、lease 為空（§7.3 的 PREPARE 只會在這個條件下出現，§7.4）。
  - `PREPARE_DOOR_CLOSED`：同上，但 Destination 為 `Universe`。
  - `UNSUPPORTED_SOURCE_CANCEL`（§11.3）：DORMANT 的 MEASURED（`MeasuredRecoveryPhase.from`＝DORMANT：SELECTED、全員已返還、lease 空），`candidateContext().sourceFamilyRef().role()` 不是 OVERWORLD，`selectedCandidate()` 是 `New` 或 `Existing`。flushed 的要求由上面的 CAS 前置保證。不要求 `m5Evidence`：M4 時期的 schema 3 收據也要能取消，移除 MEASURED 不需要 schema 4。
  - journal 只驗證由 record 推導得到的條件；「現在是否處於 PREPARE」「參與者是否關門」「通道是否結束」由呼叫端判斷。
- 流程：移除 current → flush → 以 flushed 判定。不存在就修剪該 session 的 `authorityHistory` 與 `checkedAuthority`、加入 tombstone，回 `REMOVED`。仍存在時（flush 或讀回失敗）：current 仍不存在就 `restoreCheckedAuthority`（放回 expected、history 還原、保持 dirty），回 `RESTORED`；否則隔離，回 `QUARANTINED`。兩者都記 WARN（含 origin 與 `FailureText.describe` 的 root cause）。不改門。
- tombstone 只在本 process 內：`put` 對同一個 UUID 一律丟 `IllegalStateException`，commit 與再次終局回 `REJECTED`。
- 交給後續：同時移除 `SuperpositionSessionManager` 的 runtime（`sessions`）與來源 ticket（`tickets`）是 F3d 的呼叫點工作；正常結束的 INFO（SOURCE fast path、通道結束、§11.3 的取消）也由呼叫端記。

### 既有測試的處置

- `SessionRecoveryStateTest.negativeZeroSourceEqualsItsNbtRoundtrip`（`:358`）：`initial.toNbt()` 改成 `initial.toNbt(3)`。`toNbt` 現在要依 envelope schema 決定要不要寫 `Collapse`，沒有參數的版本已移除；斷言（schema 3 的 ±0 exact roundtrip）不變。
- `SessionRecoverySchema3Test.variantsRejectUnknownOrForeignPayloadAndInvalidEnumsVersionsAndLengths` 的 `SchemaVersion=4` mutation：未修改，仍然 unhealthy，但原因從「不支援的版本」變成「schema 4 的 record 缺 `Collapse`」。schema 5 與缺 `Collapse` 的拒絕另由 `schemaFourRejectionFixturesFailClosed` 覆蓋。
- `SessionSemanticsTest.lateralAlwaysKeepsCurrentEffectIncludingArming` 逐一走 `SessionState.values()`：未修改，新狀態也照舊通過。
- 其餘既有 JUnit 未修改、全部通過。

### 測試

JUnit +31：`src/test/java/dev/quantumchamber/persistence/SessionRecoverySchema4Test.java`。

- 合法狀態矩陣：`m4StatesKeepAnEmptyCollapseAndCollapseStatesRequireOne`、`collapsingMatrixAllowsPartialReturnButNoPassageFields`、`passageReadyMatrixRequiresSettledUniverseWithOpenedPassage`、`sealedMatrixBindsTransferRecoveryRequiredToPassageFieldsBothWays`（TRR 的雙向「必須」）、`destinationMustMatchTheSelectedCandidate`、`collapseFieldsRejectMalformedShapes`（含 `SealedReason` 的分類）。
- 狀態邊：`stateEdgesAcceptOnlyTheSpecifiedTransitions`（33 列，合法 16、非法 17）、`immutableCollapseFieldsCannotChange`、`sealedTransferRecoveryRequiredIsFullyFrozen`（RecoveryRequired 只寫一次、沒有 self-loop、沒有重試邊）。
- 唯一性：`occupiesReleasesOnlyReturnedCollapseParticipantsAndDormantReceipts`、`sealedReturnedParticipantCanJoinAnotherActiveSession`（含 strict reload 與「仍封鎖自己的原艙」）、`removeRejectsEveryRetainedReceiptState`。
- 提交：`restoreEdgesOnlyCommitThroughCommitRestore`、`collapseCommitsRequireEvidenceAndNewCollapsingRequiresReadiness`、`commitRestoreSucceedsOnlyAfterExactReadback`、`commitRestoreFailureRestoresCurrentKeepsDirtyAndIsOverwrittenByTheNextWrite`（寫入前失敗，以及 atomic move 完成、讀回失敗兩種）、`commitRestoreCasMismatchQuarantinesAndConvergesToFlushed`、`exceptionAfterConfirmedReadbackStillCountsAsCommitted`、`commitRestoreWindowIsVisibleAsUncheckedAuthority`、`rollForwardFailureKeepsCurrentAndAnotherSessionFlushWritesIt`（兩種失敗）、`collapseViewUsesCurrentOnlyForUnconfirmedRollForward`（SEALED、Crossed、返還、清 lease 四種，加上 M4 進度、隔離與空 journal 的對照）。
- 終局：`terminalRemovesAfterCheckedReadbackThenTrimsHistoryAndTombstones`（以反射讀 `authorityHistory`／`checkedAuthority`，與 testmod `M4CandidateTestAccess` 相同的欄位名）、`terminalAcceptsOnlyItsFourOriginsAndExactCurrentAndFlushed`、`unsupportedSourceCancelAcceptsOnlyNonOverworldDormantNewOrExisting`（schema 3 journal 上的 Nether fixture）、`terminalFlushFailureRestoresExpectedAndKeepsDirty`（兩種失敗）、`terminalCasMismatchAfterFailureQuarantines`。
- schema：`schemaFormulaNeverWritesFourWithoutCandidateAndM5Evidence`、`readingSchemaFourSetsEvidenceButNotReadinessAndNeverDowngrades`、`schemaThreeLoadsWithEmptyCollapseAndUpgradesOnTheNextCheckedWrite`、`schemaFourRoundtripsEveryStateExactly`（11 種 record exact 讀回）、`schemaFourRejectionFixturesFailClosed`（38 個 rejection fixture）。
- CAS 不符在單執行緒下只可能來自 flush 期間的重入，測試以 flush 的 `Runnable` 改動 current 來模擬（restore 用反射寫 `records`，終局用一般 `put` 放回）。
- GameTest 不變（F2a 不加 GameTest）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2a/`；gate root 在 `.superpowers/verification/`。

diff hash 是 `git diff <from> <to> -- <path>` 輸出的 SHA-256：refactor `cfe5133..7c3c247` 的 `src` `99405502…`；`cfe5133..b400b7f` 的 `src` `0f90aaf8…`、`src/main` `812a70d6…`、`src/test` `093e1848…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| refactor 前 | `cfe5133`，工作樹乾淨 | `gradle test --rerun`（`f2a/refactor-before-test.log`） | 447，0 failure／error，2 skipped |
| refactor 後 | `cfe5133`＋refactor 4 檔（即 `7c3c247` 的內容） | 同上（`f2a/refactor-after-test.log`） | 447，0 failure／error，2 skipped，與 refactor 前同一組 class |
| RED | `7c3c247`；工作樹為暫時 adapter（`f2a/red-junit-state.txt`、`red-junit-hashes.txt`，tracked diff `f686da69…`）：`SessionState`、`CollapseFields`、`CollapseDestination`、`SealedReason` 已是最終版（blob 與 `b400b7f` 相同）；`SessionRecoveryRecord` 只加第 12 個欄位、11 欄位建構子、`withCollapse` 與不看 schema 的 `toNbt(int)`，沒有任何新驗證，`fromNbt` 仍只收 1–3；`SessionRecoveryState` 的新 API 都是 stub（`receiptRetained`／`occupies` 照 M4 語意、旗標恆為假、`collapseView` 回 flushed、commit 與 `terminate` 丟 `UnsupportedOperationException`），`convergenceTarget` 只補 case 讓它編譯；測試為 `SessionRecoveryStateTest` 的一行與 RED 版 `SessionRecoverySchema4Test`（`f2a/red-SessionRecoverySchema4Test.java`，比最終版少 `exceptionAfterConfirmedReadbackStillCountsAsCommitted`） | `compileTestmodJava`＋focused `dev.quantumchamber.persistence.*`（`f2a/red-junit.log`、`red-junit-cases.txt`） | 預期 FAIL：新 test 30 個中 29 個失敗。多數是 HEAD 只允許 MEASURED 帶 SELECTED（`session 狀態與 selection 不一致`）；其餘為 schema 4 被拒（`不支援 journal schema`）、式子 `expected: <4> but was: <3>`、M4 狀態接受非空 Collapse、stub 的 `UnsupportedOperationException`。通過的 1 個是 `collapseFieldsRejectMalformedShapes`（`CollapseFields` 在 adapter 中已是最終版）。其他 persistence 測試 128 個全部通過 |
| GREEN focused | 工作樹，blob 與 `b400b7f` 相同（`f2a/green-state.txt`） | `compileTestmodJava`＋`dev.quantumchamber.persistence.*`（`f2a/green-focused.log`） | PASS：159 個，0 failure／error，2 skipped；`SessionRecoverySchema4Test` 31/31 |
| GREEN JUnit 全部 | 同上 | `gradle test --rerun`（`f2a/green-junit-all.log`） | 478，0 failure／error，2 skipped |
| GREEN Full | 同上 | `gate-f2a-green-full-ee1ba955367345a8acd89a7a97d87b86` | PASS（`f2a/green-full-summary.txt`） |
| GREEN Legacy | 同上 | `gate-f2a-green-legacy-3657175c26424d90ae1571f4ee577491` | PASS（`f2a/green-legacy-summary.txt`） |
| GREEN M4 recovery | 同上 | `recovery-run-6baddfa9c82c4dd99392c21ce0017dec.json` | PASS（`f2a/green-recovery-summary.txt`） |

- `exceptionAfterConfirmedReadbackStillCountsAsCommitted` 是 GREEN 首輪（30/30 通過）之後，處理「flush 之後才丟例外」的邊界時加入的；在 RED adapter 上會因 stub 失敗，沒有另跑 RED。
- 所有 gate 在 commit 之前以工作樹執行；commit 時 9 個檔的 blob 與 gate 前記錄的 `git hash-object` 逐一相同（`f2a/green-state.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。數字由 `f2a/gate-summary.ps1`、`f2a/jar-delta.ps1` 唯讀計算（邏輯取自 `run-all.ps1` 的 summary）。

GREEN 數字：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped，與 F1a 相同；boundary oracle PASS；per-test receipt 35/35 PASS。
- legacy 1/1，per-test receipt 1/1 PASS。兩個 gate 的 world 都 exact restore。
- JUnit 478，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）。比 F1a 的 447 多 31，就是 `SessionRecoverySchema4Test`。
- JAR：release 320 entries（276 classes），比 F1a 的 308（265）多 12 個 entry：`collapse/` 目錄、`CollapseDestination`（含 `$Source`、`$Universe`）、`SealedReason`、`CollapseFields`、`JournalNbt`、`SessionRecoveryState$CommitResult`（含 `$Status`）、`$TerminalResult`（含 `$Status`）、`$TerminalOrigin`。sources 200，比 195 多 `collapse/` 目錄與 4 個新 `.java`（`f2a/green-jar-delta.txt`）。testmod／gametest／probe 命中 0，probe 命中只有合法的 `ModPresenceProbe`。
- M4 recovery 24/24 phases，24 個獨立 JVM，receipt 全部 PASS。結束後 Java 程序只剩既有的閒置 Gradle daemon（PID 129228）。

### 偏離 plan／spec 字面之處

- `CollapseFields.destination` 照 plan 是 `CollapseDestination`（不是 `Optional`）；只有 `EMPTY` 為 null，建構子保證其餘情況都非 null。
- Destination 必須對應被選中的候選（SOURCE↔`Source`、EXISTING 的 UniverseId、NEW→`Universe`）：spec §5 的合法組合沒有列這條，但 §7.2 規定了提交時的值，所以放進 record 的不變量。NEW 的推導值比對需要 F2b 的 `deriveUniverseId`，留給 F2b／F4a。
- `Crossed`「只增不減」實作為前綴比對（只能在尾端附加），比集合包含更嚴格；正常的跨越寫入一次加一位，不受影響。
- SEALED→COLLAPSING 的重試邊在 journal 層級對所有非 TRR 原因都開放（含 EXISTING_INELIGIBLE、AUTHORITY_CORRUPT、PROJECTION_CONFLICT）：spec §5 只把 TRR 排除在這條邊之外。「永久原因不自動重試」（§11.2）是 caller 的前提：fix round 1 起 `commitRestore` 的 Javadoc 寫明，S→C 只能在 caller 以 `SealedReason.retryable()`／`restartOnly()` 與 §11.2 的觸發、退避把關之後呼叫。
- 一般 `put` 不能憑空建立塌縮狀態的 record（current、flushed、history 都沒有時拒絕）：spec §5 的塌縮狀態只能由 MEASURED 經狀態邊進入。之後的 GameTest 要放 M5 fixture，必須先放 MEASURED 再經 `commitRestore`（需要 `m5ReadyThisInstance`），或沿用 testmod 的反射。
- READY 提交（C→R）不要求 `m5ReadyThisInstance`：spec §5 只用它決定「能不能提交新的 COLLAPSING」；READY 所需的權威確認由 F2b 之後的流程負責。
- `TerminalOrigin` 的 SOURCE_FAST_PATH 與 PREPARE_DOOR_CLOSED 另要求全員已返還、lease 為空：spec §5 只寫「`Destination=SOURCE` 的 COLLAPSING」與「PREPARE 期間」，但 PREPARE 依 §7.3 必然在這個條件之後，提前移除 record 會讓尚未返還的參與者失去收據。
- controller 補充第 2 點寫的「`requireType(..., 3)` 的上限」：那個 `3` 是 `NbtElement.INT_TYPE`，不是 schema 上限；本 task 改成常數名，版本上限另在白名單處理。
- 可選項（controller 補充第 10 點）：`SessionRecoveryRecord` 的 strict 註解已補「並必須帶 candidate payload」；`SourceFamily` 位置標籤沒有改，因為 `SessionRecoveryStrictnessTest` 斷言訊息含 `SourceFamily.WorldKey`，改它需要同時改既有測試。

### 觀察（未修改）

- strict codec 的訊息仍寫「schema 3 payload 欄位缺失或含未知欄位」「schema 3 ledger …」，schema 4 也沿用；`SessionRecoveryStrictnessTest` 以這些字串斷言，沒有為了措辭改既有測試。
- `SessionRecoveryManager.returnedRecord`（`:308-317`）只接受 RETURNING／MEASURED，F3a 延伸返還到 COLLAPSING 時要一併處理（不在本 task 檔案清單）。
- 多位參與者的 COLLAPSING 返還：第一位的 roll-forward 寫出失敗後，current 帶著他的返還進度；下一 tick 若以 flushed 為基礎寫第二位，`sameAuthority` 會拒絕（第一位的返還倒退）。這正是 spec §6 表格所說「真正讓它收斂的是只重試同一份 current 的寫出」，F3a／F3d 必須在 `rollForwardPending` 為真時只重試 flush，不疊寫新進度。
- 隔離與尚未確認的 roll-forward：`b400b7f` 的 `quarantine()` 會隔離帶著未確認 roll-forward 內容的 session，`convergeQuarantined` 再以 flushed 取代 current，可能把已落地的 SEALED(TRR) 寫回成 READY（違反 §10.3）。fix round 1 起，`quarantine()` 對「只差已 checked 的 roll-forward 進度」的 session 回 false、不隔離，caller 只重試寫出。只有 roll-forward 旗標與未 checked 的 restore 殘留同時存在時才隔離；經公開 API 不會出現這種組合，只有雙重故障才會。這時殘留必須收斂，roll-forward 內容會連同殘留一起由 flushed 取代，旗標同時清除。

### 交給後續

- F2b：M5 authority 初始化成功時呼叫 `markCollapseAuthoritiesInitialized()`；初始化之前以 `m5Evidence()` 判斷「flushed envelope 已是 schema 4」。
- F3a：`SessionRecoveryManager.recordFor`、`blocks`／`freezes`、`presence()` 與 spec §13.2 其餘呼叫點改用 `occupies`、`receiptRetained` 與 `collapseView`；COLLAPSING 的返還與清 lease 經一般 `put`（roll-forward）或 `commitRollForward`。
- F3d：COLLAPSING 提交一律經 `commitRestore`（`REJECTED`／`RESTORED` 時走 §7.1 的提交失敗路徑與退避）；終局經 `terminate`，同時移除 `SuperpositionSessionManager` 的 `sessions` 與來源 `tickets`；SOURCE fast path、通道結束與 §11.3 取消的 INFO 由呼叫端記；commit 失敗的 WARN 去重也在呼叫端。
- F7：寫入文件：M5 build 寫過 schema 4 之後，M4 build 讀取會 fail closed（不可降版）。
- F3a（fix round 2 加入）：`sameAuthority` 對塌縮狀態是單步關係、不具遞移性，只能拿最新一步的 authority 比對。`CorridorPageManager.durable(space)`（`:721-731`）在 `:727` 拿快取的 `space.authority` 和最新的 flushed 比對 `sameAuthority`，比對之後才刷新（`:729`）；快取的來源是 attach（`:91`）與上一次 `durable(space)` 呼叫，選門（`:305`）時也是經 `durable(space)` 取得。F3 依 §13.2 讓 COLLAPSING 的 release 重用 `releaseMeasured`（`:234-237`）時，若兩次 `durable(space)` 之間有不只一次 checked 寫入（例如 MEASURED→COLLAPSING 提交之後又寫了返還進度），快取與最新 flushed 之間就不是單步，會丟出「durable lease／來源權威不符」。必須在每次 checked 寫入後刷新快照，或改為只比對最新的 flushed。

### Fix round 1（2d13248）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 2），code quality review Approved（Critical 0、Important 0、Minor 8）。controller 指派本輪處理 Q1–Q6、nit 與 spec M1／M2；Q7 交給 F3a（見下方）。
- Commit `2d13248` `fix: tighten collapse edges and keep unconfirmed roll-forward out of quarantine`（parent `a143b2f`）：`SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`SessionState.java`、`SessionRecoverySchema4Test.java`。本小節由其後的 docs commit 加入，同一個 commit 也改寫了上方「偏離 plan／spec 字面之處」的 SEALED→COLLAPSING 一條與「觀察」的隔離一條。
- 行號以 `2d13248` 為準。

| 項目 | 處理 |
| --- | --- |
| Q1 改變狀態的邊不得夾帶進度 | `collapseEdge`（`SessionRecoveryRecord.java:236-270`）：M→C、C→R、C→S、R→S（TRR）、S→C 要求 `participants` 與 `spaceLeases` 都與 previous exact 相同（`sameProgress`，含 returned 與順序）。進度只能經 C→C、R→R 的 self-loop 寫入。§7.1 的「部分參與者已返還、lease 已清空」是 previous MEASURED 本來就帶著的進度，提交時原樣帶入，所以仍合法（`部分返還的 MEASURED→COLLAPSING`、`DORMANT→COLLAPSING` 兩列照舊合法）。沒有需要夾帶進度的邊 |
| Q2 不隔離尚未確認的 roll-forward | `quarantine`（`SessionRecoveryState.java:210-218`）：session 有 roll-forward 旗標，而且 current 的 authority 已 checked（`authorityChecked`：candidate 三欄位與 flushed 相同、沒有跨 restore 邊）時回 false，不隔離。Javadoc 寫明理由（收斂會以 flushed 取代 current，把可能已落地的 SEALED(TRR) 寫回成 READY，違反 §10.3）與 caller 的做法（只重試寫出）。已隔離時直接回 false（原本由 `Set.add` 回 false，行為相同） |
| Q2 兩者能否同時存在 | roll-forward 旗標只由一般 `put` 設定，而一般 `put` 在 current、flushed、history 任一份到 record 是 restore 邊時拒絕；塌縮狀態的 candidate 三欄位又由 `sameAuthority` 固定。所以經公開 API 不會同時有「旗標」與「未 checked 殘留」，只有雙重故障（測試以反射製造）才會。那時照常隔離：殘留必須收斂，`restoreCheckedAuthority` 同時清除旗標 |
| Q2 內部的 CAS 不符分支 | `commitRestore`（`:456`）與 `terminate`（`:491`）的 CAS 不符分支照舊呼叫 `quarantine`；若因上述規則被拒（current 是已 checked 的 roll-forward 內容），回 `ROLL_FORWARD_PENDING`，不再謊稱 `QUARANTINED`。`TerminalResult.Status` 新增 `ROLL_FORWARD_PENDING`（`:343`）：沒有終局，caller 只重試寫出。終局的 flush 期間以一般 `put` 重入寫入 roll-forward 就會走到這裡（`terminalCasMismatchWithUnconfirmedRollForwardIsNotQuarantined`）；`commitRestore` 的窗口中一般 `put` 寫不出這種 current，只能以反射覆蓋 |
| Q3 讀回已確認之後的例外 | `warnAfterConfirmed`（`:508-513`）：三個入口在 flushed 已確認、但 flush 有例外時記 WARN（`FailureText.describe`），同一個 session 以 `FailureText.dedupeKey` 去重（`confirmedFailureWarnings` `:59`，原因改變才再記；終局成功時移除，`:495`）。（這一輪的去重與 `SessionRecoveryManager` 的慣例並不一致：乾淨的寫出不會清除去重鍵，WARN 也沒有附 stack trace。fix round 2 已修正，見下方「Fix round 2」） |
| Q4 log 責任 | `terminate` 不再記 WARN（`:471-497`），與 `commit*` 一致。`CommitResult`（`:296-320`）、`TerminalResult`（`:325-349`）的 Javadoc 寫明 caller 依 (sid, 提交的狀態邊／origin, `FailureText.dedupeKey`) 去重後記 WARN |
| Q5 API 前提 | `commitRestore` 的 Javadoc：S→C 只能在 caller 以 `SealedReason.retryable()`／`restartOnly()` 與 §11.2 的觸發、退避把關之後呼叫。`TerminalOrigin`：`SOURCE_FAST_PATH`／`PREPARE_DOOR_CLOSED` 只能在 PREPARE 階段呼叫（RESOLVE 已完成），journal 只能檢查 settled，前提由 caller 負責。`markCollapseAuthoritiesInitialized`（`:130-144`）：不可撤銷；只能在兩個 ledger 都建立並 strict 驗證成功之後呼叫；呼叫之後 `m5Evidence()` 不再等於「flushed envelope 是否為 4」 |
| nit | `checkedAuthority` 欄位註解（`:42-44`）改為說明它供 `restoreCheckedAuthority` 的四個呼叫者，終局確認後修剪；`SessionState.retainsReceipt()`（`SessionState.java:7-11`）註明呼叫端用 `SessionRecoveryState.receiptRetained`（§13.2）；`writtenSchema`（`:616-624`）註明 `envelopeSchema` 的不混裝驗證是副作用、回傳值只在最後一行使用 |

測試（`SessionRecoverySchema4Test`，JUnit +5，既有 test 有增改）：

- Q1：`stateEdgesAcceptOnlyTheSpecifiedTransitions` 加 6 個非法列：M→C 夾帶返還、M→C 夾帶清 lease、C→R 夾帶返還與清 lease、C→R 夾帶清 lease、C→S 夾帶返還與清 lease、C→S 夾帶清 lease（R→S、S→C 兩端都已 settled，無法夾帶）。
- Q2：新增 `unconfirmedRollForwardIsNotQuarantinedAndTheNextFlushWritesIt`（TRR 的 roll-forward 寫出在 atomic move 之後讀回失敗 → `quarantine` 回 false → current 仍是 TRR → 下一次 flush 寫出 TRR，不退回 READY）、`terminalCasMismatchWithUnconfirmedRollForwardIsNotQuarantined`、`commitRestoreCasMismatchWithUnconfirmedRollForwardIsNotQuarantined`；`collapseViewUsesCurrentOnlyForUnconfirmedRollForward` 原本「put SEALED → 隔離 → view 回 flushed」的案例改成「`quarantine` 回 false、view 仍是 SEALED」，另加雙重故障（反射）照常隔離、view 回 flushed 的案例。
- Q3：`exceptionAfterConfirmedReadbackStillCountsAsCommitted` 加 log capture：第一個原因出現兩次（restore、roll-forward）只記一次 WARN；換原因後再記一次，之後終局遇到同一個新原因不再記，共 2 則，含 session 與 root cause。
- Q4：`terminalFlushFailureRestoresExpectedAndKeepsDirty` 加 log capture：終局失敗時 journal 不記 WARN。
- Q6／spec M1 的覆蓋：
  - `:274` 的空斷言（`commitRollForward(transfer, transfer)` 被拒其實是因為「沒有變更」）改成有實際變更的 TRR self-loop（多附加一位 Crossed），並斷言拒絕原因是「不是 roll-forward 類」。
  - 新增 `preconditionsRejectBeforeAnyWrite`：先隔離再終局 → REJECTED；`commitRollForward(MEASURED, 返還後的 MEASURED)` → REJECTED，journal 不變、不 dirty。
  - 新增 `quarantinedDoubleFaultConvergesAndClearsTheRollForwardFlag`：Q2 改了隔離條件，所以用 Q2 允許隔離的雙重故障（反射）：旗標＋restore 殘留 → 隔離 → 收斂 → 旗標為假。
  - 單一違規的 fixture：非 TRR SEALED 只帶 Crossed（opened／expires 為 -1，每個非 TRR 原因各一）、COLLAPSING 只帶 RecoveryRequired、READY 只帶 RecoveryRequired、READY 只帶 SealedReason、TRR 的 Crossed 含參與者以外的 UUID。
  - `!m5Evidence` 守門改斷言錯誤訊息（「M5 authority 尚未初始化」）。
  - Nether fixture 只改 `SourceFamilyRef`、不動 Origin；另加「只有 Origin 在 Nether」的列（REJECTED）。
  - `commitRestoreFailureRestoresCurrentKeepsDirtyAndIsOverwrittenByTheNextWrite` 擴充為三條 restore 邊（M→C、C→R、S→C）× 兩種失敗。
- log capture 是測試內的 log4j appender（比照 testmod `LogCapture`），掛上時先送 WARN 探針，攔不到就失敗，不能以空結果冒充「沒有 WARN」。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2a/fix1/`。diff hash 是 `git diff a143b2f 2d13248 -- <path>` 輸出的 SHA-256：`src` `075bdb92…`、`src/main` `aa95d7bd…`、`src/test` `e584af9e…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `a143b2f`；測試為本輪的 `SessionRecoverySchema4Test`，production 只多 `TerminalResult.Status.ROLL_FORWARD_PENDING` 一個常數讓測試能編譯（`fix1/red-state.txt`、`red-main-adapter.diff`，tracked diff `f68be30e…`） | focused `persistence.*`（`fix1/red-junit.log`、`red-junit-cases.txt`） | 預期 FAIL：36 個中 7 個，全部在目標斷言失敗，沒有任何 fixture 在建構時丟例外。Q1：6 個新邊列 `expected: <false> but was: <true>`；Q2：`未確認的 SEALED(TRR) 不得隔離 ==> expected: <false> but was: <true>`、view 案例同、兩個 CAS 不符案例 `expected: <ROLL_FORWARD_PENDING> but was: <QUARANTINED>`；Q3：`expected: <2> but was: <0>`；Q4：`expected: <[]> but was: <[checked 終局未確認…]>`。其餘 29 個（含所有只補覆蓋的測試）通過 |
| mutation | GREEN 工作樹，每個 mutation 單獨套用、只跑 `SessionRecoverySchema4Test`、跑完從備份還原並以 SHA-256 核對（`fix1/mutations.ps1`、`mutations/summary.txt`、`mutations/M-*.log`、`M-*-TEST.xml`） | 12 個 | 全部被對應的測試抓到，見下表；跑完後 4 個檔的 blob 與跑之前相同（`pre-mutation-hashes.txt`、`post-mutation-hashes.txt`） |
| GREEN focused | 工作樹，blob 與 `2d13248` 相同（`fix1/green-state.txt`） | `fix1/green-focused.log` | 164，0 failure／error，2 skipped；`SessionRecoverySchema4Test` 36/36 |
| GREEN JUnit 全部 | 同上 | `fix1/green-junit-all.log` | 483，0 failure／error，2 skipped |
| GREEN Full | 同上 | `gate-f2a-fix1-green-full-1146b03a353145e0a9705b7f96e4a086` | PASS |
| GREEN Legacy | 同上 | `gate-f2a-fix1-green-legacy-3d22705faa5d4807b889c837dfdc074e` | PASS |
| GREEN M4 recovery（第一次） | 同上（`fix1/recovery-console.log`） | root `run/m4-recovery-0118ed4b47cc4027a7878ed58a3f6c53-chain`；phase evidence `.superpowers/sdd/2026-09-21-m4-candidate-doors/recovery-0118ed4b47cc4027a7878ed58a3f6c53-chain/dormant-reentry/` | FAIL，保留不改判：`chain` 的 select、recover、verify PASS，第 4 個 phase `dormant-reentry` 8 分鐘逾時（`owned phase 逾時；禁止同 root 重跑`）。見下方說明 |
| GREEN M4 recovery（重跑，新 nonce） | 同上（`fix1/recovery2-console.log`） | `recovery-run-adba9afab5da40c983703cbac4c1e433.json` | PASS |

| Mutation | 拿掉的守門 | 失敗的測試（目標斷言） |
| --- | --- | --- |
| M-a | `rollForwardEdge` 排除 SEALED | `sealedTransferRecoveryRequiredIsFullyFrozen`（拒絕原因不再是「不是 roll-forward 類」） |
| M-b | `terminate` 拒絕隔離中的 session | `preconditionsRejectBeforeAnyWrite`（`被拒絕的提交不得寫出`） |
| M-c | `commitRollForward` 的邊檢查 | `preconditionsRejectBeforeAnyWrite`、`sealedTransferRecoveryRequiredIsFullyFrozen` |
| M-d | `restoreCheckedAuthority` 清除旗標 | `quarantinedDoubleFaultConvergesAndClearsTheRollForwardFlag` |
| M-e | 塌縮寫入要求 `m5Evidence` | `collapseCommitsRequireEvidenceAndNewCollapsingRequiresReadiness`（訊息變成 restore 邊的拒絕） |
| M-f | §11.3 改看 `origin().role()` | `unsupportedSourceCancelAcceptsOnlyNonOverworldDormantNewOrExisting`（3 列：兩個只改 SourceFamilyRef 的列被拒、只改 Origin 的列被接受） |
| M-g | `commitRestore` 失敗時還原 current | `commitRestoreFailureRestoresCurrentKeepsDirtyAndIsOverwrittenByTheNextWrite`（三條邊 × 兩種失敗共 6 個案例）、`commitRestoreWindowIsVisibleAsUncheckedAuthority` |
| M-h | 非 TRR SEALED 的 Crossed 為空 | `sealedMatrixBindsTransferRecoveryRequiredToPassageFieldsBothWays`（7 個「非 TRR 只帶 Crossed」） |
| M-i | COLLAPSING 的 RecoveryRequired 為空 | `collapsingMatrixAllowsPartialReturnButNoPassageFields`（「COLLAPSING 只帶 RecoveryRequired」） |
| M-j | READY 的 RecoveryRequired 為空 | `passageReadyMatrixRequiresSettledUniverseWithOpenedPassage`（「READY 只帶 RecoveryRequired」） |
| M-k | READY 沒有 SealedReason | 同上（「READY 只帶 SealedReason」） |
| M-l | TRR 的 Crossed⊆參與者 | `sealedMatrixBindsTransferRecoveryRequiredToPassageFieldsBothWays`（「TRR 的 Crossed 只能是參與者」）、`schemaFourRejectionFixturesFailClosed`（Crossed 含 OUTSIDER 的 fixture） |

- Full、Legacy 與 M4 recovery 在 commit 之前以工作樹執行；commit 時 4 個檔的 blob 與 gate 前記錄的相同。
- default GameTest 153/153（`_windows` 79）、boundary PASS、per-test receipt 35/35；legacy 1/1、receipt 1/1；兩個 gate 的 world 都 exact restore。
- JUnit 483，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`），比首輪多 5，就是本輪新增的 5 個 test。
- JAR：release 320 entries（276 classes）、sources 200，與首輪 entry 清單完全相同（`fix1/green-jar-delta.txt`；`ROLL_FORWARD_PENDING` 是既有 enum 的常數，`confirmedFailureWarnings` 是欄位，都不新增 class）。testmod 命中 0。
- M4 recovery（重跑）24/24 phases、24 個獨立 JVM。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 第一次 M4 recovery 的 `dormant-reentry` 逾時：
  - probe 本身的斷言都通過：`events.jsonl` 只有 `initialized`、沒有 `failure`；log 依序是第二座 Chamber 走廊幾何完成、`走廊 STOPPING 票已對稱移除`、`Stopping server`、`Saving players`、`Saving worlds`，也就是 `success()` 之後的 `server.stop`。
  - 之後原版的停服沒有結束。兩次 jstack（`fix1/dormant-reentry-jstack-98312.txt`、`-b.txt`，相隔 87 秒）的 `Server thread` 都在 `MinecraftServer.shutdown → ServerChunkManager.tick → ServerChunkLoadingManager.unloadChunks → tryUnloadChunk → thenRunAsync`，CPU 由 689 秒增加到 776 秒（約 100% 忙等），沒有 `quantumchamber` 的 frame。
  - 同一 phase 的時間線（加入後約 3 秒停服）與 F2a 首輪的 `recovery-run-6baddfa9…` 相同，那次在 1 秒內停完。本輪的程式改動不在 M4 路徑上：Q1 只影響進入塌縮狀態的邊；Q2 只在有 roll-forward 旗標時生效，而旗標只會出現在塌縮狀態；終局與提交在 runtime 沒有呼叫點。判斷為原版停服的 chunk unload 忙等，與本輪改動無關；未查明觸發條件，若之後再出現要另開 task 調查。
  - 處置：確認 PID 98312 的 commandline 是這個 nonce 的 `dormant-reentry` 之後才強制結束（`fix1/dormant-reentry-kill.txt`）。失敗的 root 與 phase evidence 原樣保留，以新的 nonce 重跑全部 24 phases。

交給後續（本輪新增）：

- F3a（Q7）：`uncheckedCandidateSessions` 現在也包含 restore 類殘留，名稱只講 candidate；`SessionRecoveryManager.java:147-148` 的 WARN 文字也只說「未 checked candidate authority」。兩處要改名或改字都需要動允許清單外的呼叫端（`SessionRecoveryManager`、`SuperpositionSessionManager`、testmod），交給 F3a。
- F3d：終局與兩種 commit 的失敗都不在 journal 記 log，由 `CollapseService` 依 (sid, 狀態邊／origin, `FailureText.dedupeKey`) 去重後記 WARN；`ROLL_FORWARD_PENDING`（含終局回的那一種）時只重試寫出，不疊寫、不終局。

### Fix round 2（`b488bfc`）

- Review：fix round 1 的 re-review Approved（Critical 0、Important 0、新 Minor 3）。controller 指派本輪處理 R1–R3。reviewer 讀過 jstack 與 log 後，判定 fix round 1 第一次 M4 recovery 的停服卡住與 F2a 無關：卡在 `save()` 之前等 chunk 卸載的迴圈，log 停在 `Saving worlds`，journal 還沒寫出。這一項另立追蹤，不歸 F2a。
- Commit `b488bfc` `fix: clear the confirmed-write warning key after a clean write`（parent `55f0692`）：`SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`SessionRecoverySchema4Test.java`。本小節由其後的 docs commit 加入，同一個 commit 也改寫了上方 Fix round 1 的 Q3 列，並在 F2a 的「交給後續」補上 F3a 一條。
- 行號以 `b488bfc` 為準。

| 項目 | 處理 |
| --- | --- |
| R1 `sameAuthority` 不具遞移性 | 只改 Javadoc。`sameAuthority`（`SessionRecoveryRecord.java:207-216`）寫明：塌縮狀態的邊是單步關係，例如 M→C 合法、C 的返還 self-loop 合法，但 M 直接到返還後的 C 回 false；ARMING／SUPERPOSITION／RETURNING 到任何塌縮狀態也回 false。只能和最新一步（`put` 的 current、flushed、history）比對，不能拿跨越多次寫入的舊快照比對；`put` 同時要求三者都成立，所以 roll-forward 尚未確認時再疊上狀態邊會被拒絕，符合 §7「不疊寫」。`collapseEdge`（`:233-239`）補一句指向它。`sameProgress` 仍在 `sameAuthority` 之內。`CorridorPageManager` 快取比對的風險寫入 F2a 的「交給後續」（F3a） |
| R2 去重與 stack trace | `afterConfirmedWrite`（`SessionRecoveryState.java:505-519`，原 `warnAfterConfirmed`）：讀回已確認而 flush 沒有例外時，清除該 session 的去重鍵（對應 `SessionRecoveryManager` 成功後的 `failures.remove`）；有例外時才依 `FailureText.dedupeKey` 去重並記 WARN，最後一個參數傳 `late`，附上 stack trace。三個入口（`:413`、`:448`、`:495`）都改呼叫它；終局成功時仍一併移除去重鍵 |
| R2 去重鍵是否區分寫入種類 | 不區分。這種例外來自 restore、roll-forward、終局三者共用的 flush 路徑（例如 flush 之後的 hook），與寫的是哪一條邊無關；用同一把鍵，同一原因只記一次。每則 WARN 仍帶寫入種類（`restore M→C`、`roll-forward …`、`terminate <origin>`）。Javadoc 與 `confirmedFailureWarnings`（`:58-60`）的註解同步更新 |
| R3 note | Fix round 1 的 Q3 列改寫：那一輪「沿用 `SessionRecoveryManager` 的去重慣例」的說法不成立（乾淨寫出不清除去重鍵、WARN 沒有 stack trace），本輪才一致。F2a「交給後續」新增 F3a 一條（`CorridorPageManager.durable(space)` 的快取比對）。本小節 |

測試（`SessionRecoverySchema4Test`，JUnit +1）：

- 新增 `cleanWriteClearsTheConfirmedWarningKey`：同一原因的讀回後例外（restore）→ 中間一次乾淨的 roll-forward → 同一原因再出現（restore 重試），斷言共 2 則 WARN，而且每則都附 throwable、訊息是注入的那個例外。兩個斷言以 `assertAll` 分開判定。
- 測試內的 log capture 增加 `events(Level)`，可以讀到 `LogEvent.getThrown()`。
- `exceptionAfterConfirmedReadbackStillCountsAsCommitted` 不用改：它的序列中間沒有乾淨的寫出，去重鍵也不分寫入種類，仍是 2 則。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2a/fix2/`。diff hash 是 `git diff 55f0692 b488bfc -- <path>` 輸出的 SHA-256：`src` `88f65793…`、`src/main` `dc5703d4…`、`src/test` `1bcc04cb…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `55f0692`，只有測試檔有變更（`fix2/red-state.txt`；tracked diff 的 SHA-256 前 8 碼 `6EB1E68E`） | focused `persistence.*`（`fix2/red-junit.log`、`red-junit-cases.txt`、`red-TEST-SessionRecoverySchema4Test.xml`） | 預期 FAIL：37 個中 1 個，就是新 test，兩個目標斷言都失敗：「中間的乾淨寫出清除去重鍵，同一原因再出現時再記一次」（只有 1 則），以及「WARN 必須附上 stack trace ==> expected: not <null>」 |
| GREEN focused | 工作樹，blob 與 `b488bfc` 相同（`fix2/green-state.txt`、`commit-blobs.txt`） | `fix2/green-focused.log` | 165，0 failure／error，2 skipped；`SessionRecoverySchema4Test` 37/37 |
| GREEN JUnit 全部 | 同上 | `fix2/green-junit-all.log` | 484，0 failure／error，2 skipped，比 fix round 1 多 1（新 test） |
| GREEN Full | 同上 | `gate-f2a-fix2-green-full-1934f3843c7c40a98d29fd667aa22c82` | PASS：default GameTest 153/153（`_windows` 79）、boundary PASS、per-test receipt 35/35、world exact restore；JUnit 484；release 320 entries／276 classes、sources 200，entry 清單與 fix round 1 完全相同（`fix2/green-jar-delta.txt`）；testmod 命中 0 |

- 本輪不跑 Legacy 與 M4 recovery（controller 指示）：程式只改了 Javadoc，以及「讀回已確認之後的 log」這條路徑。這條路徑在 production 到不了，因為 F2a 的 commit 與終局在 runtime 沒有呼叫點，M4 與 legacy 流程都不經過它。
- Full gate 在 commit 之前以工作樹執行；commit 時 3 個檔的 blob 與 gate 前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

## F2b：M5 authority 初始化、兩個 ledger 與收斂

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `eeb0524` `feat: add the allocation and projection ledgers with schema 4 initialization evidence`（parent `7bae536`）：新增 `collapse/UniverseAllocationLedger.java`、`ProjectionLedger.java`、`CollapseAuthorities.java`、`CollapseBackoff.java`；改 `QuantumSuperpositionMod.java`（3 行）；測試新增 `src/test/java/dev/quantumchamber/collapse/UniverseAllocationLedgerTest.java`、`ProjectionLedgerTest.java`、`CollapseAuthoritiesTest.java`。
  - 本段的 docs commit 接在 `eeb0524` 之後。
- Code range `7bae536..eeb0524`。`SessionRecoveryState.java` 沒有修改：controller 補充第 5 點允許的唯讀 getter 用不到（見「偏離」）。
- production 唯一的新行為是開服初始化：建立缺少的空 ledger、標記 journal 已初始化。之後第一次 checked journal 寫入（有 candidate record 時）寫出 schema 4。沒有接任何 gameplay 呼叫點（`CollapseService` 是 F3d），兩個 ledger 在 runtime 只會是空的。
- Review：首輪結果與 fix round 1 見本節最後的「Fix round 1」。
- 最終 review：fix round 1（`84acd2b`）的 re-review Approved（Critical 0、Important 0、Minor 2，兩項併入 F2c 處理）。**F2b 完成**：range `7bae536..ba60df0`（code `eeb0524`、`84acd2b`）；期間另有 controller 的 plan commit `245785f`、`b712193`。

### Code-truth（`eeb0524`）

行號以 `eeb0524` 為準；`A` 指 `collapse/CollapseAuthorities.java`，`U` 指 `collapse/UniverseAllocationLedger.java`，`P` 指 `collapse/ProjectionLedger.java`。

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| 註冊順序（決策 2） | `QuantumSuperpositionMod.java:47-49`；`registerEvents` `A:238-243` | 在 `SuperpositionSessionManager.initialize()` 之後、`UniverseLifecycleService.initialize()` 之前註冊 `SERVER_STARTED`（`onStarted` `:245-249`）與 `SERVER_STOPPED`（從 `INSTANCES` 移除）。Fabric 依註冊順序執行，所以初始化早於 bootstrap 物化（Full、Main gate 的 log 都是 M5 authority 的 INFO 先於 `M3_UNIVERSE_BOOTSTRAP_READY`）。`INSTANCES` 以 server identity 為 key（`:59`），單人在同一個 JVM 重開世界是新的 instance；`get(server)`（`:252-257`）只在開服之後、停服之前存在 |
| 初始化 | `initialize(MinecraftServer)` `A:260-263`、`initialize(Supplier)` `:265-287`、`examine` `:289-311` | 每個 instance 一次（第二次丟 `IllegalStateException`）。先 `requireHealthy`，再在 `markCollapseAuthoritiesInitialized()` 之前讀 `m5Evidence()`：F2a 保證此時它等於「flushed envelope 已是 schema 4」；journal 檔不存在時是全新的 state，為假。兩個 ledger 都讀完而且沒有任何失敗，才建立缺少的空檔；全部成功才呼叫 `markCollapseAuthoritiesInitialized()`。成功記 INFO；任何失敗記 ERROR（含 root cause 與 stack trace），回傳失敗的 `InitResult`，不丟例外，所以之後的 lifecycle bootstrap 照常執行。意外的 `RuntimeException` 也轉成失敗的結果 |
| 遺失判定與讀取分類 | `load` `A:313-333`、`createIfAbsent` `:335-346`、`parse` `:530-533`、`readFile` `:536-540` | 讀檔丟 `NoSuchFileException`：journal 還不是 schema 4 → `NOT_CREATED`，之後建立空檔為 `CREATED`；已是 schema 4 → `LOST`。讀原始 bytes 時的其他 IO 例外（含非一般檔案）→ `UNREADABLE`。讀到 bytes 之後的任何失敗（gzip、NBT、strict decode、超過 16 MiB）→ `CORRUPT`。建立空檔失敗（含讀回失敗）→ `UNREADABLE` |
| 失敗的 SEALED 原因 | `InitResult.sealedReason` `A:109-113`、`gate` `:443-457` | 任一 ledger 為 `LOST` 或 `CORRUPT` → `AUTHORITY_CORRUPT`；其餘（IO、journal 不健康、標記失敗）→ `AUTHORITY_UNREADABLE`。初始化失敗時，兩個權威的查詢與寫入一律回這個原因，即使其中一個 ledger 讀取成功（spec §12：遺失或損壞時「不配置任何東西」） |
| UniverseId 推導 | `deriveUniverseId` `U:79-94` | `SHA-256(UTF-8("quantumchamber:universe-id:v1") ‖ token)` 前 16 bytes，`byte[6]=(b&0x0F)|0x80`、`byte[8]=(b&0x3F)|0x80`（RFC 9562 version 8 與 variant 10），big-endian 組成 UUID |
| allocation ledger | `U`：`Entry` `:45-57`、建構子 `:61-70`、`append` `:108-114`、`toNbt` `:116-130`、`fromNbt` `:133-152`、`MAX_ENTRIES` `:38` | 不可變。root 恰為 `{SchemaVersion, Entries}`，entry 恰為 `{AllocationToken(32 bytes), UniverseId(4 int), Profile, ProfileVersion}`；token 與 UniverseId 都唯一；只能追加，保留追加順序。strict decode 只驗結構（含筆數上限在逐筆解碼前檢查）；UniverseId 是否等於推導值、profile 是否為 OVERWORLD v1，由 lookup 以 `Entry.of(intent)` exact 比對（spec §6.1「已有 entry 就用 entry…不符進 SEALED」） |
| projection ledger | `P`：`Entry` `:34-57`、建構子 `:61-70`、`with` `:85-102`、`toNbt` `:104-121`、`fromNbt` `:124-145` | 不可變。entry 恰為 `{ChamberUuid, UniverseId, Role, ControllerPos(3 int), OutwardFacing, Status}`；`(ChamberUuid, UniverseId)` 唯一；OutwardFacing 必須是水平方向。`with`：新 key 只能寫 PENDING（尾端追加）；既有 key 的 site（Role、ControllerPos、OutwardFacing）必須相同，而且只能 PENDING→COMPLETE（原位取代）；沒有刪除 |
| allocation lookup-or-create | `lookupOrAppendAllocation` `A:359-379` | 閘門（初始化失敗、遺失、未確認）之後：同 token 的既有 entry 必須 exact 等於 `Entry.of(intent)`，否則 `CORRUPT`，不沿用、不覆寫；推導的 UniverseId 已屬於另一個 token、或已達上限 → `CORRUPT`；否則 checked 追加，回 `APPENDED`、`WRITE_FAILED` 或（寫入前發現檔案消失）`CORRUPT` |
| projection 查詢與寫入 | `projection` `A:385-391`、`writeProjection` `:397-420` | `projection` 只回已確認的 entry（`FOUND`／`ABSENT`），未確認時回 `UNCONFIRMED`。`writeProjection` 要求 role 為 OVERWORLD（否則丟 `IllegalArgumentException`）；既有 entry 的 site 不符 → `CORRUPT`；同 Status → `UNCHANGED`（不寫檔）；新 key 寫 COMPLETE、或既有 COMPLETE 寫回 PENDING → `INVALID_TRANSITION`（caller 錯誤，帶回既有 entry，沒有 SEALED 原因）；新 key 已達上限 → `CORRUPT`；其餘 checked 寫入。（fix round 1 起：既有 COMPLETE 寫回 PENDING 改回 `ALREADY_COMPLETE`，新 key 寫 COMPLETE 改為丟 `IllegalArgumentException`，`INVALID_TRANSITION` 已移除，見「Fix round 1」） |
| checked 寫入 | `checkedWrite` `A:467-488`、`writeAtomic` `:506-521`、`verify` `:523-527` | 先確認正式檔仍存在（`NoSuchFileException` → 遺失：記一次 ERROR、該權威本 instance 一律 `CORRUPT`、不重建、current 不變）。再沿用 M4 discovery 的步驟：同目錄暫存檔、`force`、以正式讀取規則驗證暫存檔、`ATOMIC_MOVE`、讀回 strict decode 並 exact 比對。暫存檔驗證與讀回都經 IO port |
| roll-forward 與收斂 | `rollForward` `A:491-503`、`unconfirmed` `:423-426`、`convergeIfNeeded` `:432-438`；`CollapseBackoff.java:17-32` | 寫入或讀回失敗：current 保留這次的新內容（絕不移除可能已落地的追加）、標為未確認、退避加倍；WARN 依 `FailureText.dedupeKey` 以權威為單位去重，成功後清除。未確認期間該權威的 `lookupOrAppendAllocation`／`projection`／`writeProjection` 一律回 `UNCONFIRMED`。`convergeIfNeeded` 在退避期之外以 checked 方式重寫 current；成功記 INFO「收斂完成」並清除退避，失敗維持未確認。退避數列 200、400、…、6400、12000（上限），新 instance 沒有退避 |
| 結果型別 | `InitResult` `A:76-117`、`Lookup` `:123-157`、`WriteResult` `:160-211` | `Lookup`／`WriteResult` 的失敗狀態都帶 `sealedReason()`，F4a／F4b 直接用：`CORRUPT`→`AUTHORITY_CORRUPT`、`UNREADABLE`→`AUTHORITY_UNREADABLE`、`UNCONFIRMED`／`WRITE_FAILED`→allocation 為 `ALLOCATION_IO_FAILED`、projection 為 `PROJECTION_FAILED`。compact 建構子強制「只有失敗帶原因與 failure」 |
| IO port（決策 9） | `LedgerIo` `A:73`；package-private 建構子 `:221` | `byte[] read(Path)`：初始化載入、暫存檔驗證、atomic move 之後的讀回都經過它；正式實作是 `readFile`。測試以它注入 IO 例外與「atomic move 完成、讀回失敗」，時鐘以 `IntSupplier` 注入（production 為 `server::getTicks`） |
| strict NBT helper | `LedgerNbt` `A:582-611` | 兩個 ledger 共用：型別、4-int UUID、enum 名稱、`Entries` 的 held type（空 list 必須 END）與筆數上限 |

### spec 對應與遺失判定表（§15）

| spec | 程式位置 | 測試 |
| --- | --- | --- |
| §6 初始化：還不是 schema 4 時補建；已是 schema 4 時缺檔即遺失 | `examine`、`load`、`createIfAbsent` | 下列各列 |
| §15 全新存檔第一次 NEW 不被判為遺失 | `load` `:317-318` → `createIfAbsent` | `CollapseAuthoritiesTest.freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost`（含重啟後 strict 讀回、同 token 為 `FOUND`） |
| §15 M4 schema 3 存檔、discovery 非空：升級後第一次 NEW 不被判為遺失 | `examine` `:296` 只看 `m5Evidence()`，不讀 discovery | `schemaThreeSaveWithNonEmptyDiscoveryIsNotTreatedAsLost`（初始化前寫出 3、之後寫出 4；discovery bytes 不變） |
| §15 初始化只建立一個 ledger 就中斷（W11） | 同上：既有的 `LOADED`、缺的 `CREATED` | `interruptedInitializationRecreatesOnlyTheMissingLedger`（兩個方向；既有檔的 bytes 與 mtime 不變） |
| §15 schema 4 刪除任一個 ledger：不重建、`AUTHORITY_CORRUPT` | `load` `:318-320`；`examine` `:305-306`；`gate` `:445-450` | `schemaFourJournalWithAMissingLedgerIsLostAndNeverRecreated`（兩個方向；不建檔、另一個檔 bytes 不變、ERROR 一則、journal 照寫 4） |
| §15 只有 EXISTING 造訪過的存檔也一樣；COMPLETE 投影的 interior 不被重寫 | 同上；`projection()` 回 `CORRUPT`，不回 `ABSENT` | `existingOnlySaveWithADeletedLedgerIsLostToo`（allocation 一直是空的；刪任一個都 `LOST`；投影查詢不是 `ABSENT`，F4b 不會當成第一次寫入。實際不重寫 interior 由 F4b 驗證） |
| §15 初始化失敗時 journal 不升 schema 4、不提交 COLLAPSING | `examine` `:307-309` 只在全部成功時標記 | `initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing`（5 種失敗：`m5ReadyThisInstance` 與 `m5Evidence` 為假、寫出仍為 3、缺的 ledger 不建立、ERROR 一則）、`unhealthyJournalFailsInitializationWithoutTouchingTheLedgers` |
| §15 確定遺失 → `AUTHORITY_CORRUPT`；IO 例外 → `AUTHORITY_UNREADABLE`（§6 讀取失敗的分類） | `load`、`InitResult.sealedReason`、`checkedWrite` `:470-474` | 上列兩個 test 的 5＋1 種情境（含 CORRUPT 與 IO 同時發生時取 CORRUPT）、`aLedgerThatDisappearsAtRuntimeIsLostAndNotRecreated`（執行期間消失，§6.1） |
| §6 既有紀錄的驗證（ledger 部分）、§6.1 | `lookupOrAppendAllocation` `:366-376` | `existingAllocationEntriesMustBeExactlyWhatThisSessionWouldWrite`（推導值不符、ProfileVersion 2、UniverseId 屬於另一個 token 都 `CORRUPT`；檔案 bytes 不變） |
| §6.5 site 驗證與 PENDING→COMPLETE | `writeProjection` `:404-417`、`P.with` | `existingProjectionEntriesMustMatchTheSourceChamberAndOnlyAdvance`、`ProjectionLedgerTest.transitionsAreOnlyPendingThenCompleteAndKeysAreUnique` |
| §6 roll-forward：注入「atomic move 完成、讀回失敗」，append 不被移除、下一次使用前重寫、未確認期間拒絕新決策 | `rollForward`、`gate` `:451-455`、`convergeIfNeeded` | `allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse`、`unconfirmedProjectionPendingBlocksDecisionsUntilConverged` |
| §6 表：新內容或舊內容重新讀取後都被接受 | strict decode 與 lookup | 上列兩個 test 的重啟讀回（新內容）、`allocationWriteFailureBeforeTheMoveIsRolledForwardToo`（move 之前失敗：舊內容重啟後同一個 token 得到同一個 UniverseId；本 instance 收斂寫出新內容） |
| §6 收斂寫入本身失敗時維持未確認；以權威為單位的退避（§11.2 數列） | `convergeIfNeeded`、`CollapseBackoff` | `convergenceFailuresStayUnconfirmedWithAPerAuthorityDoublingBackoff`（200→12000 每一階的前一 tick 不嘗試、到期才嘗試；同一原因只記一次 WARN）、`backoffDoublesFrom200To12000AndResets` |
| §6.1 推導、只能追加、唯一性、strict codec | `U` | `UniverseAllocationLedgerTest` 7 個 test |
| §6.5 strict codec | `P` | `ProjectionLedgerTest` 5 個 test |

### UniverseId 的 test vectors

- 期望值不在測試裡用 production helper 反算。以 Python 3.11 的 `hashlib` 獨立計算：`SHA-256(b"quantumchamber:universe-id:v1" + token)` 取前 16 bytes，`d[6]=(d[6]&0x0F)|0x80`、`d[8]=(d[8]&0x3F)|0x80`，再以 `uuid.UUID(bytes=…)` 輸出。腳本與輸出在 `f2b/vectors.py`、`vectors.txt`；"one" 的 SHA-256 另以 .NET `SHA256.HashData` 交叉核對，結果相同。
- 測試 `UniverseAllocationLedgerTest.derivedUniverseIdsMatchTheIndependentVectors` 以 hex 常數寫死 4 組，並斷言 `version()==8`、`variant()==2`：

| token（hex） | SHA-256 前 16 bytes | UniverseId |
| --- | --- | --- |
| 32 個 `00` | `99a218638079ad81adcfd8b547656a24` | `99a21863-8079-8d81-adcf-d8b547656a24` |
| `00 01 … 1f` | `da2b7e945767f9339bc740395d0f2854` | `da2b7e94-5767-8933-9bc7-40395d0f2854` |
| 32 個 `ff` | `504cc474b37976299d035f901e452b40` | `504cc474-b379-8629-9d03-5f901e452b40` |
| `01` 後接 31 個 `00` | `3e80969f13f2adf76ca687f2863b0480` | `3e80969f-13f2-8df7-aca6-87f2863b0480` |

- 前三組原始的 byte 8 已經是 `10xxxxxx`，只靠它們抓不到「忘了設 variant」。第四組原始的 byte 6／8 是 `0xad`／`0x6c`，兩個位元遮罩都會改變值，所以特地加入。

### 測試

JUnit +28：`UniverseAllocationLedgerTest` 7、`ProjectionLedgerTest` 5、`CollapseAuthoritiesTest` 16，都在 `src/test/java/dev/quantumchamber/collapse/`。

- `UniverseAllocationLedgerTest`：`derivedUniverseIdsMatchTheIndependentVectors`、`entryForAnIntentUsesTheDerivedIdAndTheIntentProfile`、`appendOnlyKeepsOrderAndRejectsDuplicateTokensAndUniverseIds`、`codecRoundtripsExactlyInAppendOrderWithExactKeys`、`strictDecodeRejectsMalformedLedgers`（24 個 rejection fixture，`assertAll` 逐一判定）、`decodeKeepsNonDerivedEntriesForLookupVerification`、`entryCountIsCheckedBeforeDecodingAnyEntry`（16384 筆 exact 讀回與寫回；16385 筆合法 entry 與 16385 筆空 compound 都以上限訊息拒絕；追加也不能超過上限）。
- `ProjectionLedgerTest`：`codecRoundtripsEveryFacingAndStatusExactlyInWriteOrder`、`transitionsAreOnlyPendingThenCompleteAndKeysAreUnique`、`entryRequiresAHorizontalFacing`、`strictDecodeRejectsMalformedLedgers`（28 個 rejection fixture）、`entryCountIsCheckedBeforeDecodingAnyEntry`。
- `CollapseAuthoritiesTest`：見上方對應表，另有 `initializationRunsOnceAndIsRequiredBeforeUse`。
- 注入方式：測試的 `LedgerIo` 委派正式的 `readFile`，依檔名注入一次或持續的 `IOException`。對正式檔注入，在寫入時就是「atomic move 完成、讀回失敗」，在初始化時就是讀取的 IO 例外；對暫存檔注入，就是 move 之前的失敗。時鐘以 `int[]` 控制。log capture 是測試內的 log4j appender（比照 `SessionRecoverySchema4Test`），先送 WARN 探針，攔不到就失敗。
- GameTest 不變（F2b 不加 GameTest）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2b/`；gate root 在 `.superpowers/verification/`。diff hash 是 `git diff 7bae536 eeb0524 -- <path>` 輸出的 SHA-256：`src` `b5fb6ef8…`、`src/main` `04fb698a…`、`src/test` `6ff80a9c…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `7bae536`；porcelain：` M QuantumSuperpositionMod.java`、`??` 4 個新 production 檔與 `src/test/java/dev/quantumchamber/collapse/`（`f2b/red-state.txt`）。4 個新 production 檔是暫時的 RED adapter（`f2b/red-adapter/`，與 GREEN 的差異在 `green-to-red.diff`）：GREEN 拿掉 spec 規則——推導改用 `UUID.nameUUIDFromBytes`；兩個 ledger 的 decode 寬鬆、沒有唯一性、上限、轉換規則與水平方向檢查；退避固定 200；初始化不看 journal envelope（一律補建）、IO 例外與損壞不分、任何情況都標記 journal、可重複初始化、未初始化也能查詢；lookup 不驗證既有紀錄；寫入前不檢查檔案消失，寫入失敗時還原 current、不標未確認、不收斂。測試檔與 GREEN 相同 | focused `dev.quantumchamber.collapse.*`（`f2b/red-junit.log`、`red-junit-cases.txt`、`red-TEST-*.xml`） | 預期 FAIL：28 個中 21 個失敗。逐一檢查 XML：21 個 test 的 69 個失敗原因全部是 `AssertionFailedError`（含 `assertThrows` 的「nothing was thrown」與「Unexpected exception type」），沒有任何 fixture 在建立時丟例外。通過的 7 個都是只補覆蓋的 test，以 mutation 證明（下表） |
| RED 第一次（保留） | 同上，但 `CollapseAuthoritiesTest` 是較早的版本（`f2b/red1/`） | 同上 | 21 個失敗中，`existingProjectionEntriesMustMatchTheSourceChamberAndOnlyAdvance` 有一個原因是 `sealedReason().orElseThrow()` 丟出的 `NoSuchElementException`。為了讓失敗一律落在目標斷言，把測試中 16 處 `orElseThrow()` 的比對改成 `assertEquals(Optional.of(…), …)`，以同一個 adapter 重跑，就是上一列 |
| mutation | GREEN 工作樹，每個 mutation 單獨套用、只跑目標 test class，跑完還原並以 `git hash-object` 核對（`f2b/mutations.ps1`、`mutations/summary.txt`、`M-*.log`、`M-*-TEST.xml`） | 7 個 | 全部被對應的 test 抓到（下表）；跑完後 4 個 production 檔的 blob 與 mutation 前相同 |
| GREEN focused | 工作樹，blob 與 `eeb0524` 相同（`f2b/green-state.txt`、`commit-blobs.txt`） | `f2b/green-focused.log` | 28/28，0 failure／error |
| GREEN JUnit 全部 | 同上 | `f2b/green-junit-all.log` | 512，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`） |
| GREEN Full | 同上 | `gate-f2b-green-full-d49ee07a881448408795ccb45ca57192` | PASS |
| GREEN Legacy | 同上 | `gate-f2b-green-legacy-c1dcaccf1b4b41f7a5e3c81c1e09ed93` | PASS |
| GREEN Main＋oracle | 同上 | `gate-f2b-green-main-012f7bdf34034aa5a162618c5581c16b`（`main-only-oracle.json`） | PASS |
| GREEN M3 | 同上 | `gate-f2b-green-m3-a3ddfa9c91e445fa898eb2c092c73c73` | PASS |
| GREEN M4 recovery | 同上 | `recovery-run-bde9df436918427890464242656f72c8.json` | PASS |

| Mutation | 改動 | 失敗的 test（目標） |
| --- | --- | --- |
| M-1 | `Entry.of` 改從 `generationSeedMaterial` 推導 | `entryForAnIntentUsesTheDerivedIdAndTheIntentProfile` |
| M-2 | strict decode 依 UniverseId 排序 | `codecRoundtripsExactlyInAppendOrderWithExactKeys` |
| M-3 | strict decode 驗證推導值（一筆不符整份失效） | `decodeKeepsNonDerivedEntriesForLookupVerification` |
| M-4 | projection 的 ControllerPos 寫成 `{x, z, y}` | `codecRoundtripsEveryFacingAndStatusExactlyInWriteOrder` |
| M-5 | 遺失判定反轉（還不是 schema 4 時缺檔判為遺失） | `freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost` |
| M-6 | 以 `candidateInitialized()` 取代 `m5Evidence()` | `schemaThreeSaveWithNonEmptyDiscoveryIsNotTreatedAsLost` |
| M-7 | 只有一個 ledger 存在時把另一個判為遺失 | `interruptedInitializationRecreatesOnlyTheMissingLedger` |

- 所有 gate 在 commit 之前以工作樹執行；commit 時 8 個檔的 blob 與 gate 前記錄的逐一相同（`f2b/green-state.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。數字由 `f2b/gate-summary.ps1`、`jar-delta.ps1` 唯讀計算。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

GREEN 數字：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped，與 F2a 相同；boundary oracle PASS；per-test receipt 35/35 PASS。legacy 1/1，receipt 1/1。兩個 gate 的 world 都 exact restore。
- JUnit 512，比 F2a fix round 2 的 484 多 28，就是本 task 新增的 3 個 test class。
- JAR：release 337 entries（293 classes），比 F2a 的 320（276）多 17 個 class：`CollapseAuthorities` 與它的 `$Authority`、`$InitResult`、`$InitResult$Outcome`、`$LedgerIo`、`$LedgerNbt`、`$Lookup`、`$Lookup$Status`、`$Slot`、`$WriteResult`、`$WriteResult$Status`，`CollapseBackoff`，`ProjectionLedger`、`$Entry`、`$Entry$Status`，`UniverseAllocationLedger`、`$Entry`。sources 204，比 200 多 4 個 `.java`（`f2b/green-jar-delta.txt`）。testmod／gametest／probe 命中 0。
- main-only：oracle 三層都沒有命中，正常停止、world keys exact、catalog 前後都不存在、exact restore。class-load 有 production 的 `CollapseAuthorities` 等 class。
- M3：universe 4 個 phase、transfer 5 個 phase 全部 PASS。
- M4 recovery 24/24 phases、24 個獨立 JVM。這次沒有出現 F2a fix round 1 的停服卡住。

runtime 證據（plan Step 3「第一次在 runtime 寫出 schema 4 與 ledger 檔」，以 `f2b/inspect-data.py` 唯讀解析，輸出在 `f2b/green-*-world-data.txt`）：

- Full gate 的 GameTest world：journal 為 schema 4；兩個 ledger 都存在，`{SchemaVersion:1, Entries:[]}`、空 list 為 END held type；沒有殘留的暫存檔。
- M4 recovery 的 13 個 root（`journal-corrupt` 以外）：journal 都是由 M5 build 在 runtime 寫出的 schema 4，兩個 ledger 都存在且為空。跨 JVM 的 phase 以 `chain` 為例（各 phase 的 `latest.log`）：select 是 fresh root，初始化為兩個 `CREATED`、flushed journal 還不是 schema 4；之後的 recover、verify、dormant-reentry 都讀到 schema 4，初始化為兩個 `LOADED`。
- `journal-corrupt`：既有的 journal 健康檢查 listener（`QuantumSuperpositionMod.java:38`）先丟例外，M5 的初始化沒有執行，data 目錄沒有 ledger 檔，與 M4 的預期相同。
- main-only：沒有 journal（沒有 session），兩個空 ledger 已建立。legacy world：journal 最後是 schema 3（見「觀察」），兩個空 ledger 已建立。

### 偏離 plan／spec 字面之處

- **沒有新增 `SessionRecoveryState` getter**（controller 補充第 5 點）：`m5Evidence()` 在呼叫 `markCollapseAuthoritiesInitialized()` 之前就等於 flushed envelope 是否為 schema 4；journal 檔不存在時 `load` 回傳全新的 state，`m5Evidence()` 為假，正好對應 spec「schema 1–3，或 journal 檔不存在」。不需要另外區分檔案是否存在。
- **寫入 helper**：controller 要求沿用既有 helper。`UniverseCatalogStore`／`SessionJournalStore` 的 `write` 都要求 `{data, DataVersion}` wrapper，與 spec §6.1 的檔案格式不同，讀回也不能經決策 9 的 port；discovery 的 `save` 是 private。所以照 discovery 的步驟在 `CollapseAuthorities.writeAtomic` 實作一份，兩個 ledger 共用；strict NBT helper 也因為 `JournalNbt` 是 persistence 的 package-private 而另寫在 `CollapseAuthorities.LedgerNbt`。只能修改 4 個新檔，所以都放在 `CollapseAuthorities` 內。
- **讀取分類的界線**：spec 只說 strict decode 失敗為 `AUTHORITY_CORRUPT`、IO 例外為 `AUTHORITY_UNREADABLE`。`NbtIo` 對 gzip 或 NBT 格式錯誤也丟 `IOException`，所以改成「讀到原始 bytes 之前的失敗是 IO，之後的任何失敗都是內容損壞」。非一般檔案（例如目錄）照 discovery 當成 IO 例外。
- **strict decode 只驗結構**：UniverseId 與推導值的比對、ProfileVersion 是否為 1、projection 的 Role 與 site，都在 lookup／寫入時比對，不讓整份 ledger 失效（spec §6.1、§6.5 都寫成「既有 entry…不符進 SEALED」）。decode 接受任何合法的 `DimensionRole`；`writeProjection` 要求 OVERWORLD。
- **`projection()` 在未確認時也回 `UNCONFIRMED`**：controller 補充第 4 點只列 `lookupOrAppendAllocation`／`writeProjection`。但未確認的 PENDING 不能拿來決定寫方塊（spec §6 收斂共通第 2 點），所以查詢也一起拒絕。
- **筆數上限**（controller 補充第 2 點）：兩個 ledger 都是 16384，沿用 M4 的 `MaxCandidateEntries`。理由：每筆 allocation 對應一個 `EAGER_ENABLED` 的宇宙，每次開服都會全部物化，實際遠低於此值。寫滿時以 `NbtSizeTracker` 實測的解碼計量是 projection 13,402,377 bytes（約 13.4 MB）、allocation 9,961,737 bytes（約 10.0 MB），都在讀檔的 16 MiB（16,777,216 bytes）預算之內，寫滿仍讀得回來。這組數字來自 spec reviewer 的 scratchpad 程式 `scratchpad\f2b-budget\BudgetProbe.java`（輸出在同目錄 `run.log`；projection 用極端座標與 COMPLETE），本輪沒有重測；fix round 1 的 `fullAllocationLedgerRejectsNewAppendsWithoutWriting`／`fullProjectionLedgerRejectsNewKeysWithoutWriting` 另以正式初始化路徑（16 MiB 預算）讀入 16384 筆，結果都是 `LOADED`。（首輪寫的「約 3 MB」是未經計量的估計，不正確。）decode 在逐筆解碼前檢查（H1 的做法）；追加與新的 projection key 也先檢查，避免寫出讀不回來的檔案，或讓權威因為永遠失敗的寫入卡在未確認。已滿時回 `CORRUPT`（controller 已裁定接受，見「裁示」）。
- **讀檔的記憶體上限**：正式 `readFile` 最多讀 16 MiB＋1 bytes，超過由 `parse` 判為損壞；解壓後的 NBT 預算與 discovery 相同（16 MiB）。
- **初始化有任何讀取失敗時，不建立另一個缺少的 ledger**：spec 允許 schema 4 之前補建，但失敗時沒有必要產生副作用；下次開服照常補建（W11 的情境不受影響）。
- **執行期間的遺失偵測**：spec §6.1「執行期間發現檔案消失，一律視為遺失」。實作為每次 checked 寫入（追加、收斂）前確認正式檔仍存在；不存在就把該權威標為遺失（本 instance 一律 `CORRUPT`、不重建、記一次 ERROR）。純查詢不讀檔。
- **靜態註冊方法叫 `registerEvents()`**：plan 的介面把 `initialize(MinecraftServer)` 定為 instance 方法，所以沒有再用同名的 static `initialize()`。

### 裁示（controller，2026-10-02，F2b review 之後）

- **ledger 已滿時的 SEALED 原因**：回 `AUTHORITY_CORRUPT`（永久），接受。理由是 append-only 的 ledger 一旦達到上限就不會再有空間，自動重試（`ALLOCATION_IO_FAILED`／`PROJECTION_FAILED`）只會在退避後一再失敗。權威層的預先檢查由 fix round 1 的測試鎖住。
- **F4b 的唯讀列舉與健康 API**：由 F4b 依 plan `245785f` 在 `CollapseAuthorities.java` 新增（只限唯讀：列舉已確認的 projection entries、權威健康查詢）。健康只看初始化結果與執行期間的 `lost`，不看單次呼叫回的 `CORRUPT`（後者也涵蓋單筆不符與 ledger 已滿）。

### 觀察（未修改）

- legacy runner 的 `M4CandidateTestAccess.reloadEmptySchemaThree` 會以讀入的 schema 3 state 取代 server 的 journal（`m5Evidence` 為假），所以那個 world 之後的寫出是 schema 3，兩個空 ledger 照樣存在。下次以 M5 開啟時判定為「還不是 schema 4」，照常 `LOADED`，不是遺失，也不影響 legacy test 的斷言。這只是 testmod 的路徑。
- Gradle 的 `stdout.log` 裡，本 task 新的中文 INFO（與既有的中文 log 一樣）因為 console 編碼而顯示成亂碼；`latest.log` 與 JUnit 的 log capture 內容正確。
- 預設 GameTest world 結束時 journal 仍有 3 筆 record，是既有測試留下的（T0 已記錄的 GameTest 順序依賴類），與本 task 無關。

### 交給後續

- F2c：`CollapseAuthorities.Authority` 加入 discovery 與 catalog。ledger 的 `Slot`／`LedgerIo`／`checkedWrite` 只服務兩個 ledger；discovery 與 catalog 有各自的 store 與 port（決策 9），收斂旗標與退避可以沿用同一個 `CollapseBackoff`。
- F2c（fix round 1 加入，quality review Q5，controller 指派）：`CollapseAuthorities.writeAtomic`／`verify` 與 `UniverseDiscoveryState.save`／`verifyReadback` 的「暫存檔、force、驗證暫存檔、atomic move、讀回 exact 比對」是重複的寫法；`LedgerNbt` 放在 `CollapseAuthorities` 裡，兩個 ledger 類別因此反過來依賴它。F2c 本來就要改 `UniverseDiscoveryState` 並加 IO port，屆時一起抽出共用 helper，並解開這個互相依賴。
- F3d：提交 COLLAPSING 時，NEW 的 `Destination` 用 `UniverseAllocationLedger.deriveUniverseId(intent.allocationToken())`（§7.2，提交時不寫 ledger）。F2a 把「NEW 的 Destination 等於推導值」的 record 不變量留給 F2b／F4a，但 `SessionRecoveryRecord.java` 不在 F2b 的檔案清單，所以這個比對交給 F4a（RESOLVE 時以 `lookupOrAppendAllocation` 的 entry 與 `Destination` 比對，不符進 `AUTHORITY_CORRUPT`）或由 controller 指派。
- F4a：`lookupOrAppendAllocation` 之前先呼叫 `convergeIfNeeded(ALLOCATION_LEDGER)`；結果的 `sealedReason()` 可直接用於 SEALED（`AUTHORITY_UNREADABLE` 是 `restartOnly`）。SEALED 的 WARN／ERROR 去重由 caller 負責；權威層級的 WARN（寫入未確認）、ERROR（初始化失敗、執行期間遺失）已由 `CollapseAuthorities` 記錄。
- F4b：
  - `projection()` 只看 key、不驗 site。決定第一次寫入或修復之前，先 `writeProjection(PENDING)` 取得 site 驗證：`WRITTEN`／`UNCHANGED`（既有 PENDING）照第一次寫入處理；`ALREADY_COMPLETE`（fix round 1 起，帶回既有的 COMPLETE）改走修復路徑；`CORRUPT` 進 SEALED。plan `245785f` 的 F4b 寫的是「回 INVALID_TRANSITION 並帶回 COMPLETE」，最終狀態名是 `ALREADY_COMPLETE`，由 controller 同步 plan。
  - §9.3 的 `ProjectionProtectionIndex` 需要列舉已確認的 projection entries，以及權威健康（退化規則）：依 controller 裁示，由 F4b 依 plan `245785f` 在 `CollapseAuthorities.java` 新增唯讀 API（見「裁示」）。
  - 索引的 `SERVER_STARTED` listener 必須註冊在 `CollapseAuthorities.registerEvents()` 之後；任何更早的 `SERVER_STARTED` listener 都不能呼叫 `CollapseAuthorities.get(server)`（instance 在 `onStarted` 才建立）。
- F7：寫入文件：M5 build 開服後，存檔的 `data/` 會多出 `quantumchamber_universe_allocations.dat` 與 `quantumchamber_projections.dat`；journal 一旦寫出 schema 4，就不能刪除這兩個檔（會被判為遺失）。

### Fix round 1（`84acd2b`）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 3），code quality review Approved（Critical 0、Important 0、Minor 6）。controller 指派本輪處理 Q1–Q4、Q6 與 spec M2；Q5 交給 F2c（見「交給後續」）。開工 HEAD 是 controller 的 plan docs commit `245785f`（只改 F4b 的允許路徑與兩條 RED 項）。
- Commit `84acd2b` `fix: separate completed projections and lock the per-authority backoff`（parent `245785f`）：`CollapseAuthorities.java`、`UniverseAllocationLedger.java`（只改 `MAX_ENTRIES` 的 Javadoc）、`CollapseAuthoritiesTest.java`。本小節由其後的 docs commit 加入，同一個 commit 也改寫了上方的 Review 行、Code-truth 的 projection 列（加註）、「偏離」的筆數上限一條、「裁示」（原「需要裁示」）與「交給後續」的 F2c、F4b 兩條。
- 行號以 `84acd2b` 為準；`A` 指 `collapse/CollapseAuthorities.java`。

| 項目 | 處理 |
| --- | --- |
| Q1 成功後重置與以權威為單位的退避沒有鎖住 | 只補測試。`allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse` 在收斂成功、再追加一筆成功之後，於 tick 1300 再注入同一種讀回失敗：斷言 1499 不嘗試、1500（1300＋200）就重寫成功，WARN 共 2 則、「收斂完成」INFO 共 2 則。新增 `backoffIsPerAuthorityWhenFailuresAreStaggered`：allocation 在 1000、projection 在 1100 各自失敗；1199 兩者都不嘗試；1200 只有 allocation 重寫，projection 不嘗試、仍未確認；1299 仍未確認，1300 才重寫。成功時的重置在 `checkedWrite`（`A:508`） |
| Q2 ledger 已滿沒有權威層測試 | 只補測試。新增 `fullAllocationLedgerRejectsNewAppendsWithoutWriting`、`fullProjectionLedgerRejectsNewKeysWithoutWriting`：以正式的單筆 `toNbt` 組出 16384 筆的檔案，`journal(4)` 初始化為兩個 `LOADED`（寫滿仍讀得回來）；新的追加或新 key 回 `CORRUPT`／`AUTHORITY_CORRUPT`，檔案 bytes 不變、IO port 沒有任何讀取（沒有嘗試寫入）、沒有暫存檔、權威沒有變成未確認；既有 token 照常 `FOUND`，既有 key 的 PENDING→COMPLETE 照常寫入。可選項也做了：`freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost` 斷言初始化成功恰好記一則開頭為「M5 authority 初始化完成」的 INFO，而且沒有 ERROR |
| Q3 `INVALID_TRANSITION` 混用 caller 錯誤與正常的「已 COMPLETE」 | `WriteResult`（`A:159-208`）移除 `INVALID_TRANSITION` 與 `invalid` factory，新增 `ALREADY_COMPLETE`：寫 PENDING 而既有 entry 已是 COMPLETE、site 驗證相同時回傳（`A:434`），帶既有的 COMPLETE entry，沒有 failure、沒有 SEALED 原因，`succeeded()` 為真；F4b 改走修復路徑。site 不符仍是 `CORRUPT`（判斷在前）。新 key 直接寫 COMPLETE 改為丟 `IllegalArgumentException`（`A:436`），比照 role 檢查；選這個而不保留 `INVALID_TRANSITION` 的理由：已確認的 PENDING 不會消失（不刪除 entry，遺失由閘門先回 `CORRUPT`），所以這種呼叫一定是 caller 的錯誤，保留成結果狀態只會讓每個 caller 處理一條到不了的分支。這個檢查在閘門之後，因為它依賴已確認的 ledger 內容。compact 建構子（`A:185-191`）改為「成功的三種狀態帶 entry、沒有原因與 failure；其餘沒有 entry、必須有原因與 failure」。**最終狀態名是 `ALREADY_COMPLETE`**；plan `245785f` 的 F4b 寫的「回 INVALID_TRANSITION 並帶回 COMPLETE 時改走修復路徑」需要由 controller 同步（plan 不在本輪允許路徑） |
| Q4 Javadoc 的呼叫前提 | `lookupOrAppendAllocation`（`A:361-367`）與 `writeProjection`（`A:403-419`）：只能在本 session 的 COLLAPSING 已經 checked 落盤（journal flushed 已是 schema 4）之後呼叫，並寫明初始化的遺失判定依賴這個不變量；`writeProjection` 另列 role 與「COMPLETE 只能寫在已確認的 PENDING 之後」兩個前提。`get()`（`A:248-261`）：Javadoc 與例外訊息都改為指出原因（instance 在 `registerEvents()` 註冊的 `SERVER_STARTED` listener 裡才建立、在 `SERVER_STOPPED` 釋放；更早註冊的 `SERVER_STARTED` listener 與停服之後都不能呼叫），取代原本會誤導的「尚未開服或已停服」。`initialize(MinecraftServer)`（`A:263-270`）：註明只供 `registerEvents` 的 listener 呼叫一次。`projection()`（`A:390-401`）的 Javadoc 改指向 `UNCHANGED`／`ALREADY_COMPLETE` 的驗證路徑 |
| Q6 暫存檔讀回不符時 WARN 去重失效 | `verify`（`A:546-557`）的訊息不再帶路徑，改為「`<authority>` 的暫存檔／正式檔 exact readback 不符（`<正式檔名>`）」。新增 `temporaryReadbackMismatchWarnsOncePerCause`：IO port 持續把暫存檔讀成另一份合法的空 ledger，追加失敗後在 1200、1600 各重試一次（斷言確實有讀取），WARN 只有 1 則而且不含 `.tmp`；之後移除注入，2400 收斂成功 |
| spec M2 | `UniverseAllocationLedger.java:33-38` 與上方「偏離」的筆數上限一條，改為實測的 `NbtSizeTracker` 計量（projection 13,402,377 bytes、allocation 9,961,737 bytes，預算 16,777,216 bytes）。數字取自 spec reviewer 的 `scratchpad\f2b-budget\`（`BudgetProbe.java`、`run.log`），本輪沒有重測；Q2 的兩個 test 以正式初始化路徑讀入 16384 筆作為佐證。兩項裁示寫入「裁示」 |

測試（`CollapseAuthoritiesTest`，JUnit +6，既有 test 有增改）：

- 新增 `backoffIsPerAuthorityWhenFailuresAreStaggered`、`fullAllocationLedgerRejectsNewAppendsWithoutWriting`、`fullProjectionLedgerRejectsNewKeysWithoutWriting`、`temporaryReadbackMismatchWarnsOncePerCause`、`completedProjectionAnswersAlreadyCompleteWithTheExistingEntry`、`completeWithoutPendingIsACallerError`。
- 改 `allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse`（Q1 的重置段）、`freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost`（INFO 斷言）、`existingProjectionEntriesMustMatchTheSourceChamberAndOnlyAdvance`（拿掉 `INVALID_TRANSITION` 的兩個斷言，它們移到上面兩個新 test；另加「既有 COMPLETE 的 site 不符仍是 CORRUPT」與這些呼叫都不寫檔）。
- 測試的 `LedgerIo` 增加「以指定 bytes 取代讀到的內容」的注入（`staleTemporary`），用來製造暫存檔的 exact 讀回不符。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2b/fix1/`。diff hash 是 `git diff 245785f 84acd2b -- <path>` 輸出的 SHA-256：`src` `be5be75f…`、`src/main` `b2e0c279…`、`src/test` `9ae7a46f…`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `245785f`；porcelain 為 ` M CollapseAuthorities.java`、` M CollapseAuthoritiesTest.java`（`fix1/red-state.txt`）。production 只多 `WriteResult.Status.ALREADY_COMPLETE` 一個 enum 常數，讓測試能編譯，沒有任何行為改變；測試檔與 GREEN 相同 | focused `collapse.*`（`fix1/red-junit.log`、`red-junit-cases.txt`、`red-TEST-CollapseAuthoritiesTest.xml`） | 預期 FAIL：34 個中 3 個，就是兩個行為修正的 3 個 test，原因全部是 `AssertionFailedError`：Q3 `completedProjectionAnswersAlreadyCompleteWithTheExistingEntry` 為 `expected: <ALREADY_COMPLETE> but was: <INVALID_TRANSITION>`、failure 不為空、`succeeded()` 為假；Q3 `completeWithoutPendingIsACallerError` 為「Expected IllegalArgumentException to be thrown, but nothing was thrown」；Q6 為 `expected: <1> but was: <3>`（三則 WARN 各帶不同的 `.tmp` 路徑）。其餘 31 個通過，其中 Q1、Q2 與 INFO 斷言是只補覆蓋，以 mutation 證明（下表） |
| RED 第一次（保留） | 同上，但 Q3 的斷言還在 `existingProjectionEntriesMustMatchTheSourceChamberAndOnlyAdvance` 裡（`fix1/red1/`） | 同上 | 32 個中 2 個失敗，原因都是 `AssertionFailedError`；但 `assertAll` 的第一組失敗之後，`ALREADY_COMPLETE` 的斷言沒有執行到。為了讓兩個目標斷言各自在 RED 失敗，拆成上面兩個新 test 後重跑，就是上一列 |
| mutation | GREEN 工作樹，每個 mutation 單獨套用、只跑 `CollapseAuthoritiesTest`，跑完還原並以 `git hash-object` 核對（`fix1/mutations.ps1`、`mutations/summary.txt`、`F-*.log`、`F-*-TEST.xml`） | 6 個 | 全部被目標 test 抓到（下表）；跑完後 `CollapseAuthorities.java` 的 blob 與 mutation 前相同 |
| GREEN focused | 工作樹，blob 與 `84acd2b` 相同（`fix1/green-state.txt`、`commit-blobs.txt`） | `fix1/green-focused.log` | 34/34 |
| GREEN JUnit 全部 | 同上 | `fix1/green-junit-all.log` | 518，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`），比首輪多 6，就是本輪新增的 6 個 test |
| GREEN Full | 同上 | `gate-f2b-fix1-green-full-7b226c11a19648b0ac71e5e1a052e18e` | PASS：default GameTest 153/153（`_windows` 79）、boundary PASS、per-test receipt 35/35、world exact restore；JUnit 518；release 337 entries／293 classes、sources 204，entry 清單與首輪完全相同（`fix1/green-jar-delta.txt`；`ALREADY_COMPLETE` 取代 `INVALID_TRANSITION`，同一個 enum class）；testmod 命中 0 |

| Mutation | 改動 | 失敗的 test（目標） |
| --- | --- | --- |
| F-1 | 成功之後不重置退避（刪 `backoff.reset()`） | `allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse`（「1300＋200 到期就重寫」） |
| F-2 | 成功之後不清除 WARN 去重鍵（刪 `warned = null`） | 同上（WARN `expected: <2> but was: <1>`） |
| F-3 | 兩個權威共用同一個退避（4 處 `slot.backoff` 改成 `allocation.backoff`） | `backoffIsPerAuthorityWhenFailuresAreStaggered`（「allocation 在 1000＋200 到期就重寫」）；同一個 mutation 也讓 Q1 的 test 失敗 |
| F-4 | 拿掉 allocation 已滿的預先檢查 | `fullAllocationLedgerRejectsNewAppendsWithoutWriting`：ledger 的 `IllegalArgumentException`（「allocation ledger 已達 16384 筆上限」）外洩 |
| F-5 | 拿掉 projection 新 key 已滿的預先檢查 | `fullProjectionLedgerRejectsNewKeysWithoutWriting`：同上（「projection ledger 已達 16384 筆上限」）外洩 |
| F-6 | 初始化成功改記 DEBUG | `freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost`（INFO `expected: <1> but was: <0>`） |

- 本輪不跑 Legacy、Main＋oracle、M3 與 M4 recovery（controller 指示）：改動只在 `writeProjection` 的結果狀態（runtime 沒有呼叫點）、`verify` 的例外訊息、`get()` 的例外訊息與 Javadoc；`registerEvents`、`onStarted` 與初始化流程（`examine`、`load`、`createIfAbsent`）都沒有改。
- Full gate 在 commit 之前以工作樹執行；commit 時 3 個檔的 blob 與 gate 前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

## F2c：Discovery append 與 catalog 的既有紀錄驗證

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `c0167e0` `refactor: share the checked NBT file helper`（parent `b712193`）：新增 `persistence/CheckedNbtFile.java`、`collapse/LedgerNbt.java`；改 `collapse/CollapseAuthorities.java`、`UniverseAllocationLedger.java`、`ProjectionLedger.java`、`candidate/UniverseDiscoveryState.java`；`CollapseAuthoritiesTest.java` 只改一行引用（`CollapseAuthorities.readFile` → `CheckedNbtFile.readFile`）與一個 import。controller 補充第 6 點（F2b quality review Q5）。
  - `96a442c` `feat: add checked discovery append and catalog verification`（parent `c0167e0`）：改 `candidate/UniverseDiscoveryState.java`、`collapse/CollapseAuthorities.java`、`universe/UniverseCatalogStore.java`、`universe/UniverseRegistryState.java`（後兩者只加 package-private 的讀回 port）；測試改 `UniverseDiscoveryStateTest.java`、`UniverseRegistryStateTest.java`（只新增一個 test method）、`CollapseAuthoritiesTest.java`。
  - 本段的 docs commit 接在 `96a442c` 之後。
- Code range `b712193..96a442c`。
- production 的新行為只有 API，沒有接任何 gameplay 呼叫點（RESOLVE 是 F4a）。runtime 唯一的差別是 discovery 的讀寫改經 `CheckedNbtFile`（M4 的 `loadOrCreate`／`load` 照常，見「第 6 點」），以及 `onStarted` 多建一個 `ServerCatalog`（只包住 server，開服時不讀 catalog）。
- Review：首輪結果與 fix round 1 見本節最後的「Fix round 1」。
- 最終 review：fix round 2（`82d7bb3`）的 re-review Approved（Critical 0、Important 0、Minor 0）。**F2c 完成**：range `b712193..6cce51f`（code `c0167e0`、`96a442c`、`7676424`、`fece7a3`、`82d7bb3`）。

### Code-truth（`96a442c`）

行號以 `96a442c` 為準；`A` 指 `collapse/CollapseAuthorities.java`，`D` 指 `candidate/UniverseDiscoveryState.java`，`C` 指 `persistence/CheckedNbtFile.java`，`N` 指 `collapse/LedgerNbt.java`（fix round 1 起搬到 `persistence/LedgerNbt.java`）。本表標「fix round 1 起」的列已在 `fece7a3` 改變，見「Fix round 1」。

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| discovery 的正式追加（§6.4） | `D.append` `:121-134`、`Append` `:38-51`、`find` `:105-108` | 只算出內容、不寫檔。沒有這個 UniverseId：新 record 為 ordinal＝`watermark()`＋1（`Math.addExact`；不可見的 record 也算）、`discoveredAtGameTime`＝傳入的 game time、`firstObserver`＝SelectedBy、`gameplayEligible`＝true，回 `APPENDED` 與多一筆的新 state（canonical 順序，新 record 一定在最後）。已有：`gameplayEligible` 而且 `firstObserver` 等於 SelectedBy 為 `EXISTING`，否則（不可見、別人、M4 fixture 沒有 observer）為 `MISMATCH`；兩者都回原本的 state，不改動。ordinal 與 time 不比對 |
| discovery 的 store 層 port（決策 9） | `D.save(Path, ReadPort)` `:91-98`（package-private）；`save(Path)` `:85` 以 `CheckedNbtFile::readFile` 委派 | 暫存檔驗證與 atomic move 之後的讀回都經 port；失敗丟 `UnhealthyDiscoveryException`，正式檔不還原 |
| discovery 的追加與收斂（§6.4、§6） | `A.appendDiscovery` `:500-522`、`readDiscovery` `:685-700`、`markLost` `:703-709`、discovery slot `:266-267` | 閘門（初始化、遺失、未確認）之後，每次從磁碟 strict 讀取（與 M4 讀取點相同，不快取）。`EXISTING`→`FOUND`（不寫）；`MISMATCH`→`CORRUPT`（不寫）；`APPENDED`→沿用 ledger 的 `checkedWrite`：寫入或讀回失敗為 roll-forward（current 保留這筆 append、標為未確認、以權威為單位退避、WARN 去重），回 `WRITE_FAILED`／`ALLOCATION_IO_FAILED`。讀取分類：`NoSuchFileException`→遺失（`CORRUPT`，本 instance 之後一律 `CORRUPT`、只記一次 ERROR、不重建）；讀 bytes 的 IO 例外→`UNREADABLE`（不黏著）；之後的解析或 strict decode 失敗→`CORRUPT`。game time 為負是 caller 錯誤，丟 `IllegalArgumentException` |
| discovery 的唯讀查詢 | `A.discovery` `:528-536` | 給 F4a 的 EXISTING 重驗（§8.1）：`FOUND`（該 record，`gameplayEligible` 由呼叫端判斷）／`ABSENT`；閘門與讀取分類同上（fix round 2 起：未確認時回不帶原因的 `WAIT`，見「Fix round 2」） |
| catalog 配置或驗證（§6.2） | `A.allocateOrVerifyCatalog` `:555-597` | 依序：初始化閘門；catalog 不健康→`CORRUPT`；catalog 未確認（`:568`）→`UNCONFIRMED`（依 §6.2 不進 SEALED，見「偏離」；fix round 1 起改回不帶原因的 `WAIT`）；flushed 已有該 UniverseId→`catalogRecordMatches`（`:575`），符合 `FOUND`、否則 `CORRUPT`，都不寫出；沒有→orphan 檢查（`:578-587`），再 `allocateOverworld(uuid, flushed 最大 ordinal＋1)`（空時 0）與 `flushCatalog`（`:595`）：成功 `APPENDED`，失敗 `WRITE_FAILED`／`ALLOCATION_IO_FAILED` |
| 既有 catalog record 的判定 | `A.catalogRecordMatches` `:606-615`（public static） | definition 必須 exact 等於 M3 以同一個 UUID 與該 record 的 ordinal 呼叫 `new UniverseRegistry().allocateOverworld` 得到的 definition（OVERWORLD descriptor、world key、`VANILLA_OVERWORLD_SHARED_SEED_V1`、`SHARED_SAVE_SEED_V1`、storage policy 1）；availability 為 `EAGER_ENABLED`，`DISABLED` 只在 `acceptDisabled` 為 true（§8.3 的重試路徑，F4a 決定）時接受。不比對 ordinal。fix round 1 起 boolean 改為 `AvailabilityPolicy`（`EAGER_ONLY`／`MATERIALIZE_RETRY`） |
| orphan storage | `A.universeStorage` `:771-774`、`allocateOrVerifyCatalog` `:578-587` | storage 位置與 M3 `inspectStorage` 同一推導：`<save>/dimensions/<namespace>/<path>` 的上一層（`dimensions/quantumchamber/universe/<uuid>`）。catalog 沒有這筆、目錄（任何型別，不跟隨連結）卻已存在→`CORRUPT`，不採用、不刪除；`readAttributes` 的其他 IO 例外→`UNREADABLE`／`AUTHORITY_UNREADABLE`。pure JVM 可測，不需要 runtime 世界路徑 |
| catalog 的未確認與收斂 | `A.catalogUnconfirmed` `:712-718`、`unconfirmed` `:621-625`、`convergeIfNeeded` `:632-644`、`convergeCatalog` `:720-733`、`flushCatalog` `:739-760`、`catalogConfirmed` `:763-768` | 不另存旗標，由 catalog 推導：`isDirty()`，或 current 不等於 flushed（catalog 不健康時為真）。所以原生 autosave 寫出成功就自動回到已確認；restore 之後 current 已等於 flushed、但仍 dirty，也算未確認。`flushCatalog` 經 port 寫出；失敗時 M3 的 save 已保留 current 與 dirty（`UniverseRegistryState.java:228-234`），這裡只記退避（`catalogBackoff`，以權威為單位）、WARN（同一原因一次）與「曾經失敗」；確認時清除退避，曾失敗過就記 INFO「CATALOG 收斂完成」。沒有新增還原 API |
| catalog 的 port（決策 9） | `A.CatalogPort` `:82-88`、`ServerCatalog` `:90-93`、package-private 建構子 `:252` | production：`UniverseRegistryState.get(server)` 與 `flush(server)`。測試以它提供真的 M3 `UniverseRegistryState` 並注入寫出失敗 |
| catalog 的 store 層 port（決策 9） | `UniverseCatalogStore.Readback` `:27-29`、`write(Path, NbtCompound, Readback)` `:35-60`（package-private）；`UniverseRegistryState` 欄位與建構子 `:43-52`、`save` `:228` | 只把 atomic move 之後的 `read(absolute)` 換成 port；public `write` 與 public 無參數建構子都用 `UniverseCatalogStore::read`，production 行為不變 |
| 閘門 | `A.gate` `:649-658`、`initializationGate` `:661-670` | 四個權威共用初始化閘門（fail closed）：未初始化是 caller 錯誤，初始化失敗回初始化的失敗結果；之後檔案權威再看遺失與未確認。`convergeIfNeeded` 在初始化失敗時一律不做事 |
| 共用 checked 檔案 helper | `C.write` `:58-75`、`verify` `:77-85`、`readFile` `:37-41`、`parse` `:44-47`、`ReadPort` `:34` | 第 6 點，見下一小節 |
| 共用 strict NBT helper | `N` `:16-47` | 第 6 點，見下一小節 |

### 第 6 點：共用 helper 的 refactor（`c0167e0`）

- **`persistence/CheckedNbtFile`**：把 `CollapseAuthorities.writeAtomic`／`verify`／`parse`／`readFile` 與 `UniverseDiscoveryState.save`／`verifyReadback`／`readNbt` 兩份幾乎相同的寫法合成一份，帶 `ReadPort`（讀原始 bytes；檔案不存在丟 `NoSuchFileException`）。`CollapseAuthorities.LedgerIo` 改為 `extends CheckedNbtFile.ReadPort`，測試的 port 不用改型別。catalog（`UniverseCatalogStore`）與 journal（`SessionJournalStore`）的 `{data, DataVersion}` wrapper 不併入。
- **strict NBT helper 選 `collapse/LedgerNbt.java`（從 `CollapseAuthorities` 搬出、public），不選 `JournalNbt` 改 public**（controller 裁示接受；fix round 1 依裁示 2 搬到 `persistence/LedgerNbt.java`，下面第 3 點的 `candidate`→`collapse` import 因此消失）。理由：
  1. `JournalNbt` 的規則綁 journal envelope：`compounds` 的空 list 規則依 envelope schema 而不同，`keys` 的訊息寫「schema 3 payload」，另有 journal 專用的 `canonicalIdentifier`。本 task 對它只能改可見性或搬移，不能加筆數上限、enum 或與 schema 無關的 list 檢查；共用的話，ledger 與 discovery 要傳一個假的 envelope schema 3，錯誤訊息也會指向 journal。
  2. `LedgerNbt` 原封搬出，ledger 的錯誤訊息逐字不變；只把原本寫死的 `Entries` 改成 key 參數，並拆出沒有上限的 `compounds(nbt, key)` 給 discovery 的 `Records`。兩個 ledger 不再經過 `CollapseAuthorities` 解碼（解開 Q5 指出的互相依賴）。
  3. 代價：`JournalNbt` 仍有自己的 `requireType`／`uuid`（journal 專用，兩三行）；`candidate` 多了一條對 `collapse` 的 import（`collapse` 原本就依賴 `candidate`），性質與既有的 `candidate`↔`persistence`、`persistence`↔`collapse` 相同。
- **行為不變的界線**：
  - 接受與拒絕的集合完全相同。discovery 改用 `LedgerNbt` 後，型別、UUID 長度、list held type 的錯誤訊息不同，但 `load`／`loadOrCreate`／`save` 都把例外包成不帶 cause 的 `UnhealthyDiscoveryException`，對外看不到。
  - discovery 讀檔改為「先讀原始 bytes（最多 16 MiB＋1）再解析」，比原本直接 `NbtIo.readCompressed(path, 16 MiB 預算)` 多一個壓縮檔大小上限。只看 root compound 本身，能通過 strict decode 的 discovery 內容，解壓後的 NBT 計量一定先超過 16 MiB 預算，所以這個上限不會比原本更早拒絕；兩者都會拒絕的內容，對外一樣是 `UnhealthyDiscoveryException`。**例外**（fix round 1 依 spec review M1 更正，首輪寫成沒有例外）：舊版從串流讀到 root compound 就停止，不看之後的資料；root compound 之後若接了壓縮後超過 16 MiB 的多餘資料，舊版接受、新版拒絕。這只會發生在損壞的檔案，方向是 fail closed。
  - ledger 的 `readFile`／`parse` 訊息由「M5 ledger／ledger 檔案」改成「checked NBT 檔案」，exact readback 不符的訊息不變（label 仍是權威名稱）。
- **JUnit 前後相同**：refactor 前的基準是 F2b fix round 1 的 Full gate（`gate-f2b-fix1-green-full-7b226c11…`；`84acd2b..b712193` 沒有 `src/`、build 檔、`.github/` 或 `scripts/` 的差異）。refactor 之後在工作樹跑全部 JUnit：518，0 failure／error，2 skipped，**逐 class 數字與基準完全相同**（`f2c/refactor-baseline-junit-perclass.txt`、`refactor-junit-perclass.txt`）。之後沒有再改檔就 commit。限制（spec review M4）：`f2c/refactor-state.txt` 只記了 HEAD 與 porcelain，沒有記逐檔 blob，事後無法以 blob 證明 JUnit 跑的正是 `c0167e0` 的內容，也無法補記；fix round 1 的 refactor 起逐檔記錄 blob。

### 第 7 點：F2b fix round 1 re-review 交給本 task 的兩項 Minor

- (a) 「新 key 直接寫 COMPLETE 丟 IAE」排在閘門之後（`A:465`、`:477`）：新增 helper `completeWithoutPending`（`CollapseAuthoritiesTest`），以 `assertDoesNotThrow` 呼叫，用在 `initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing`（5 種失敗：`CORRUPT`／`UNREADABLE`，斷言 SEALED 原因）與 `schemaFourJournalWithAMissingLedgerIsLostAndNeverRecreated`（兩個方向，斷言 `CORRUPT`）。mutation M-4（把檢查移到閘門之前、改讀 `projection.current`，並拿掉原位置的檢查）讓兩個 test 都失敗，失敗訊息是 `assertDoesNotThrow` 的「Unexpected exception thrown: java.lang.IllegalArgumentException」。
- (b) `projection()` 的 Javadoc（`A:430-435`）補上「`ABSENT` 時，先做 spec §6.5 的實體交叉檢查（F4b），才能寫 PENDING。PENDING 一旦存在就不再做交叉檢查。」

### spec 對應

| spec | 程式位置 | 測試 |
| --- | --- | --- |
| §6.4 欄位：ordinal＝watermark＋1、當下 game time、firstObserver＝SelectedBy、gameplayEligible | `D.append` | `UniverseDiscoveryStateTest.appendUsesTheNextOrdinalTheGameTimeAndTheSelectedByObserver`（含不可見 record 與同 ordinal、空 discovery 從 0 開始、原 state 不變、新 watermark 之內可被選到、負的 game time 被拒）；`CollapseAuthoritiesTest.discoveryAppendWritesTheNextRecordAndIsIdempotent`（落盤後 M4 的 `load` 讀得到） |
| §6.4 idempotency 與既有紀錄驗證（`gameplayEligible=false`、`firstObserver` 不符時拒絕且不改寫；ordinal 與 time 可以不同） | `D.append`、`A.appendDiscovery` | `appendIsIdempotentByUniverseIdAndOnlyAcceptsThisSessionsEligibleRecord`（`EXISTING` 回原 state；三種不符：不可見、別人、沒有 observer）；`existingDiscoveryRecordsMustBeEligibleAndFirstObservedByThisSession`（三種都 `AUTHORITY_CORRUPT`，檔案 bytes 不變）；`discoveryAppendWritesTheNextRecordAndIsIdempotent`（第二次 `FOUND`，不改寫） |
| §6 roll-forward（discovery）：讀回失敗後已落地的 record 不移除、M4 讀取點的候選池不倒退、未確認期間不做新決策、下一次使用前重寫 | `A.appendDiscovery` → `checkedWrite`／`rollForward`、`gate`、`convergeIfNeeded` | `discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress`（以 M4 的 `UniverseDiscoveryState.load` 與 `eligibleSnapshot` 驗證：已凍結 watermark 的候選池不變、新的候選池含已落地的 record；未確認期間同一筆也不回 `FOUND`、新的不追加、檔案不變；1199 不嘗試、1200 重寫；之後 `FOUND`、下一筆 ordinal 2；WARN 一則、INFO「DISCOVERY 收斂完成」一則）；`discoveryWriteFailureBeforeTheMoveIsRolledForwardToo`；store 層 `UniverseDiscoveryStateTest.readbackFailureAfterTheMoveLeavesTheAppendOnDiskForM4Readers`、`temporaryVerificationFailureLeavesTheFormalFileUnchanged` |
| §6 讀取失敗的分類（discovery） | `A.readDiscovery`、`markLost` | `discoveryReadFailuresAreClassified`（遺失：`CORRUPT`、不建檔、ERROR 一則；損壞：`CORRUPT`、不覆寫；IO：`UNREADABLE`、不是未確認、之後照常） |
| §6.2 ordinal＝flushed 最大＋1、`EAGER_ENABLED`、先 checked flush | `A.allocateOrVerifyCatalog` | `catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords`（0、再來 1；`DISABLED` 的 record 也算進最大 ordinal；寫出之後 flushed 與磁碟才有） |
| §6.2 既有 record 驗證（profile、availability） | `A.catalogRecordMatches` | 同上（`DISABLED` 只在 `acceptDisabled` 時 `FOUND`；別的 UniverseId 的 record 不符；驗證不寫出）；`catalogRecordsWithAnotherProfileNeverReachAllocation`（`GeneratorProfile`、`SeedPolicy`、`StoragePolicyVersion` 不符時 M3 strict decode 就讓 catalog 不健康，`acceptDisabled` 真假都 `CORRUPT`，未確認為真、不寫出） |
| §6.2 roll-forward：讀回失敗後保留 dirty、下一次寫出帶著它、確認前 `unconfirmed` 為真、未確認期間不疊加配置 | `A.flushCatalog`、`catalogUnconfirmed`、`convergeCatalog`；M3 `UniverseRegistryState.save` | `catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged`（CollapseAuthorities 自己的 port；重試只得到同一個 UniverseId，重新讀盤只多兩筆）；`nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed`（原生 autosave 寫出即確認、不需 converge；restore 後 current 等於 flushed 但仍 dirty 為未確認）；store 層 `UniverseRegistryStateTest.allocationReadbackFailureAfterTheMoveKeepsCurrentAndDirtyAndTheNextWriteCarriesIt` |
| §6.2 orphan storage | `A.universeStorage`、`allocateOrVerifyCatalog` | `orphanStorageIsCorruptAndIsNeitherAdoptedNorDeleted`（有 world 目錄、只有 universe 目錄兩種；不配置、不刪除、不寫出；其他 UUID 照常） |
| §6 收斂共通第 2 點：以權威為單位的旗標與退避 | `Authority`、`unconfirmed`、`convergeIfNeeded` | 上列 roll-forward 測試都斷言另一個權威不受影響，並鎖住 1199／1200 的退避 |
| §6 初始化失敗：UNIVERSE 目的地依初始化的失敗分類（discovery 與 catalog 也 fail closed） | `initializationGate` | `initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing`（`appendDiscovery`、`discovery`、`allocateOrVerifyCatalog` 都回初始化的原因，不寫出 catalog、不建立 discovery）；`initializationRunsOnceAndIsRequiredBeforeUse` |

### 測試

JUnit +15（518 → 533）：`UniverseDiscoveryStateTest` +4（9 → 13）、`UniverseRegistryStateTest` +1（6 → 7）、`CollapseAuthoritiesTest` +10（22 → 32）。

- 新增：
  - `UniverseDiscoveryStateTest`：`appendUsesTheNextOrdinalTheGameTimeAndTheSelectedByObserver`、`appendIsIdempotentByUniverseIdAndOnlyAcceptsThisSessionsEligibleRecord`、`readbackFailureAfterTheMoveLeavesTheAppendOnDiskForM4Readers`、`temporaryVerificationFailureLeavesTheFormalFileUnchanged`。
  - `UniverseRegistryStateTest`：`allocationReadbackFailureAfterTheMoveKeepsCurrentAndDirtyAndTheNextWriteCarriesIt`。這是 M3 的測試檔：只新增這個 method，用完整類名，沒有改 import 或既有 method（controller 補充第 3 點）。
  - `CollapseAuthoritiesTest`：discovery 5 個（`discoveryAppendWritesTheNextRecordAndIsIdempotent`、`existingDiscoveryRecordsMustBeEligibleAndFirstObservedByThisSession`、`discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress`、`discoveryWriteFailureBeforeTheMoveIsRolledForwardToo`、`discoveryReadFailuresAreClassified`）、catalog 5 個（`catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords`、`catalogRecordsWithAnotherProfileNeverReachAllocation`、`catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged`、`nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed`、`orphanStorageIsCorruptAndIsNeitherAdoptedNorDeleted`）。
- 改既有的 `CollapseAuthoritiesTest`：
  - `interruptedInitializationRecreatesOnlyTheMissingLedger`、`schemaFourJournalWithAMissingLedgerIsLostAndNeverRecreated`、`existingOnlySaveWithADeletedLedgerIsLostToo` 的迴圈由 `Authority.values()` 改成只走兩個 ledger（`LEDGERS`）；`Authority` 加入 `DISCOVERY`、`CATALOG` 之後，原寫法會把它們當成 projection 再跑一次。
  - 第 7 點 (a) 的斷言（見上）；`failedInitialization` 另加 discovery 與 catalog 的 fail closed 斷言；`initializationRunsOnceAndIsRequiredBeforeUse` 加兩行（discovery、catalog 未初始化丟例外）。
  - fixtures：`authorities(save)` 改傳 `TestCatalog`（每個存檔一份真的 M3 `UniverseRegistryState`；`failAfterMove` 先以 `UniverseCatalogStore.write` 落地再丟例外，不經過 save，所以 catalog 照 M3 save 失敗的語意維持 current 與 dirty；`failBeforeMove` 不落地就丟例外）；`Io` 的 fault 改成可變 class 並加 `skip`（discovery 每次使用都先讀一次正式檔，`failReadbackAfter(…, 1)` 讓第二次讀，也就是 atomic move 之後的讀回失敗）；`@BeforeAll` 建立 game version（catalog wrapper 的 DataVersion）。
- 注入方式（決策 9）：discovery 的 store 層以 `UniverseDiscoveryState.save(Path, ReadPort)`；catalog 的 store 層以 `UniverseRegistryState(Readback)`；`CollapseAuthorities` 用自己的 `LedgerIo`（discovery 檔）與 `CatalogPort`。都不用 testmod mixin。
- GameTest 不變（F2c 不加 GameTest）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2c/`；gate root 在 `.superpowers/verification/`。diff hash 是 `git diff <a> <b> -- <path>` 輸出的 SHA-256 前 8 碼（`f2c/diff-hashes.txt`）：`b712193..c0167e0` 為 `src` `27653dbc`、`src/main` `f0456ee1`、`src/test` `7a837b9c`；`c0167e0..96a442c` 為 `src` `2697a11f`、`src/main` `510fed67`、`src/test` `f95f02c4`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| refactor JUnit | `b712193`；porcelain 為 5 個 ` M` 與 2 個 `??`（`f2c/refactor-state.txt`），內容等於之後的 `c0167e0`（沒有記逐檔 blob，見「第 6 點」的限制） | `f2c/refactor-junit-all.log` | 518，0 failure／error，2 skipped；逐 class 與 F2b fix round 1 的 Full gate 相同（見「第 6 點」） |
| RED | `c0167e0`；porcelain 為 7 個 ` M`（`f2c/red-state.txt`，含逐檔 blob）。`UniverseDiscoveryState.java` 與 `CollapseAuthorities.java` 是暫時的 RED adapter（`f2c/red-adapter/`，與 GREEN 的差異在 `green-to-red.diff`）：GREEN 拿掉 spec 規則——`append` 的 ordinal 沿用 watermark、不記 game time 與 observer、不檢查 game time、既有紀錄一律 `EXISTING`；`appendDiscovery`／`discovery` 不看初始化與未確認、讀取失敗一律 `CORRUPT`、寫入失敗時把舊內容寫回（移除已落地的 append）；`allocateOrVerifyCatalog` 不看初始化與未確認、不驗證既有紀錄、不檢查 orphan、ordinal 一律 0、不健康回 `UNREADABLE`；`catalogRecordMatches` 一律為真；catalog 不追蹤未確認；discovery 與 catalog 不收斂。兩個 M3 檔的 port 與測試檔與 GREEN 相同 | focused `candidate.*`、`universe.*`、`collapse.*`（`f2c/red-junit.log`、`red-junit-cases.txt`、`red-TEST-*.xml`、`red-failure-types.txt`） | 預期 FAIL：217 個中 15 個失敗，就是 13 個新的行為 test 與 2 個加了斷言的既有 test（`initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing`、`initializationRunsOnceAndIsRequiredBeforeUse`）。逐一解析 XML 的 failure 內文：15 個 test 裡出現的例外型別只有 `AssertionFailedError`（`assertAll` 包成 `MultipleFailuresError`／`DefaultMultiCauseException`），沒有任何 fixture 在建立時丟例外。RED 通過的新斷言只補覆蓋，以 mutation 證明（下表 M-2、M-3、M-4）；`readbackFailureAfterTheMoveLeavesTheAppendOnDiskForM4Readers` 在 RED 是因為 adapter 的 ordinal 而失敗，「不還原正式檔」另以 M-1 證明 |
| mutation | GREEN 工作樹，每個 mutation 單獨套用、只跑目標 test class，跑完還原並以 `git hash-object` 核對（`f2c/mutations.ps1`、`mutations/summary.txt`、`M-*.log`、`M-*-TEST.xml`） | 11 個 | 全部被目標 test 的斷言抓到（下表）；跑完後 3 個 production 檔的 blob 與 mutation 前相同。第一輪（保留在 `mutations/run1/`）的 M-2 是「先 move 再驗證暫存檔」，它讓每一次 save 都失敗，目標 test 只在建 fixture 時就丟例外；改成下表的版本後重跑全部 11 個 |
| GREEN focused | 工作樹，blob 與 `96a442c` 相同（`f2c/green-state.txt`、`commit-blobs.txt`） | `f2c/green-focused.log` | 217/217 |
| GREEN JUnit 全部 | 同上 | `f2c/green-junit-all.log`、`green-junit-perclass.txt` | 533，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；與 refactor 後相比只有上述 3 個 class 增加 |
| GREEN Full | 同上 | `gate-f2c-green-full-ac54bdbd7c4f48ab8c27296ef8d53fb6` | PASS（`f2c/green-full-summary.txt`） |
| GREEN M3 | 同上 | `gate-f2c-green-m3-b3c1aaac100c44078a22f594383779ee` | PASS（`f2c/green-m3-summary.txt`） |
| GREEN M4 recovery | 同上 | `recovery-run-9c8f822fb90345c2bf32d169a7320f30.json` | PASS（`f2c/recovery-console.log`、`green-recovery-summary.txt`） |

| Mutation | 改動 | 失敗的 test（目標）與斷言 |
| --- | --- | --- |
| M-1 | `CheckedNbtFile`：atomic move 之後讀回失敗時，把正式檔寫回上一份 | `readbackFailureAfterTheMoveLeavesTheAppendOnDiskForM4Readers`（「atomic move 已完成：append 已落地，沒有被還原」） |
| M-2 | `CheckedNbtFile`：驗證暫存檔之前先以暫存檔取代正式檔 | `temporaryVerificationFailureLeavesTheFormalFileUnchanged`（「atomic move 之前失敗：正式檔不變」） |
| M-3 | M3 `UniverseRegistryState.save` 失敗時把 current 還原成 flushed 並清除 dirty | `allocationReadbackFailureAfterTheMoveKeepsCurrentAndDirtyAndTheNextWriteCarriesIt`（「保留 dirty」） |
| M-4 | 第 7 點 (a)：IAE 檢查移到閘門之前、改讀 current | `schemaFourJournalWithAMissingLedgerIsLostAndNeverRecreated`、`initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing`（`assertDoesNotThrow` 的「Unexpected exception thrown: java.lang.IllegalArgumentException」） |
| M-5 | catalog 的未確認只比 current 與 flushed，不看 dirty | `nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed`（「current 等於 flushed 但仍 dirty：下一次寫出之前仍未確認」） |
| M-6 | 閘門不看 discovery 的未確認 | `discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress`（未確認期間回了 `FOUND`） |
| M-7 | catalog 未確認時照常配置 | `catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged`（未確認期間回了 `APPENDED`） |
| M-8 | catalog 收斂不看退避 | 同上（「退避期內不嘗試」`expected: <1> but was: <2>`） |
| M-9 | discovery 收斂只清旗標、不寫出 current | `discoveryWriteFailureBeforeTheMoveIsRolledForwardToo`（「收斂把 current 寫出」） |
| M-10 | 既有 catalog record 為 `DISABLED` 時一律接受 | `catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords`（`acceptDisabled=false` 卻回 `FOUND`） |
| M-11 | discovery 讀檔的 IO 例外判為 `CORRUPT` | `discoveryReadFailuresAreClassified`（`expected: <UNREADABLE> but was: <CORRUPT>`） |

- M-5～M-11 鎖的是 RED 時排在第一個失敗斷言之後、沒有執行到的性質。
- 所有 gate 在 commit 之前以工作樹執行；commit 時 7 個檔的 blob 與 gate 前記錄的逐一相同（`f2c/green-state.txt`、`commit-blobs.txt`）。數字由 F2b 的 `f2b/gate-summary.ps1`、`jar-delta.ps1` 與 `f2c/junit-perclass.ps1`、`junit-cases.ps1`、`failure-types.ps1` 唯讀計算。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

GREEN 數字：

- default GameTest 153/153（`_windows` 79），0 failure／error／skipped，與 F2b 相同；world exact restore。
- JUnit 533，比 F2b fix round 1 的 518 多 15，就是本 task 新增的 15 個 test。
- JAR（`f2c/green-jar-delta.txt`，與 F2b fix round 1 的 Full gate 比較）：release 345 entries（301 classes），多 9 個、少 1 個：新增 `CheckedNbtFile`、`CheckedNbtFile$ReadPort`、`LedgerNbt`、`CollapseAuthorities$CatalogPort`、`$ServerCatalog`、`$1`（javac 為跨 class 的 enum switch 產生的 switch map）、`UniverseDiscoveryState$Append`、`$Append$Outcome`、`UniverseCatalogStore$Readback`，移除 `CollapseAuthorities$LedgerNbt`。sources 206，多 `CheckedNbtFile.java`、`LedgerNbt.java`。testmod／gametest／probe 命中 0。
- M3：universe 4 個 phase、transfer 5 個 phase 全部 PASS。
- M4 recovery 24/24 phases、24 個獨立 JVM（PID 不重複）。
- runtime 證據（discovery 的讀寫改經 `CheckedNbtFile`，以 `f2c/inspect-discovery.py` 唯讀解碼，輸出在 `f2c/green-runtime-discovery.txt`）：Full gate 的 GameTest world 與 M4 recovery 的 12 個 root（`discovery-corrupt`、`journal-corrupt` 以外），discovery 都是由 M4 的 `loadOrCreate` 在 runtime 寫出的 `{SchemaVersion:1, Records:[]}`（root 恰為兩個 key、空 list 為 END held type），沒有暫存檔殘留。`discovery-corrupt` 是 probe 自己放的 4 bytes 損壞檔；`journal-corrupt` 沒有 discovery，與 F2b 記錄的相同（journal 健康檢查先失敗）。

### 偏離 plan／spec 字面之處

- **「正式 checked append」拆成兩層**：§6.4 的欄位與 idempotency 規則在 `UniverseDiscoveryState.append`（只算內容）；checked 寫入、讀取分類與 roll-forward 收斂在 `CollapseAuthorities.appendDiscovery`。後者直接用 `CheckedNbtFile` 與 `UniverseDiscoveryState` 的 codec 寫 discovery 檔（與 `UniverseDiscoveryState.save` 同一寫法），讀寫都經 `CollapseAuthorities` 自己的 `LedgerIo`，因為 discovery 自己的 `load`／`save` 把所有失敗包成同一個不帶 cause 的例外，分不出 spec §6 要求的遺失、IO 與損壞，而且它的 port 是 package-private（決策 9）。
- **discovery 不快取**：每次使用都從磁碟 strict 讀取，與 M4 的讀取點一致；current 只在 roll-forward 未確認時持有（收斂時重寫的就是它）。未確認期間 M5 不寫 discovery，M4 只在檔案不存在時建立，所以 current 不會蓋掉別人的寫入。
- **discovery 檔不存在一律是遺失**：spec 只對兩個 ledger 寫初始化與遺失的規則。M5 只在 RESOLVE 用 discovery，那時 journal 已有 candidate evidence，M4 已建立 discovery（`SuperpositionSession.java:33-35` 在有 evidence 時改用 `load`，不再建立），所以檔案不存在就是遺失；與 ledger 相同，本 instance 之後一律 `CORRUPT`、只記一次 ERROR、不重建。
- **discovery 與 catalog 也走初始化閘門（fail closed）**：spec §6 的初始化失敗規則是「UNIVERSE 目的地到 RESOLVE 時依失敗分類進 SEALED」，沒有逐一列出 discovery 與 catalog。初始化失敗時 RESOLVE 第一步就會 SEALED，本來就走不到這兩步；統一 fail closed，讓初始化失敗的 instance 沒有任何 M5 寫入。
- **新增唯讀的 `discovery(UniverseId)`**：plan 的 F2c 沒有列。F4a 的檔案清單沒有 `CollapseAuthorities.java`，而 §8.1 的 EXISTING 重驗需要分得出遺失、損壞、IO 與未確認的 discovery 查詢（M4 的 `load` 分不出來），所以在本 task 提供。
- **catalog 的 `UNCONFIRMED` 帶 `ALLOCATION_IO_FAILED`**：`Lookup` 的不變式要求失敗的結果帶 SEALED 原因，但 §6.2 規定 catalog 不相等（dirty）時等下一個 tick、不進 SEALED。Javadoc（`Lookup` 與 `allocateOrVerifyCatalog`）寫明 catalog 的 `UNCONFIRMED` 是例外，F4a 應先查 `unconfirmed(CATALOG)`；見「需要裁示」。（fix round 1 起依裁示 1 改為不帶原因的 `WAIT`，本條不再適用。）
- **catalog 的未確認不另存旗標**：由 catalog 推導（`isDirty()` 或 current 不等於 flushed）。controller 補充第 4 點寫「`CollapseAuthorities` 只追蹤『未確認』」；推導的好處是原生 autosave 寫出之後自動確認，F4a 在 §8.3 直接用 M3 API 改 availability 時也自動涵蓋。本類別只保留退避、WARN 去重與「曾失敗」（INFO）這些簿記。
- **profile 不符無法構成既有 record**：M3 的 `UniverseDefinition` 建構子只接受恰一個 OVERWORLD descriptor、`VANILLA_OVERWORLD_SHARED_SEED_V1`、`SHARED_SAVE_SEED_V1`、storage policy 1（`UniverseDefinition.java:16-45`），`GeneratorProfile`／`SeedPolicy` 也只有這一個值。所以「profile 不符」的 RED 以 catalog NBT 驗證：M3 strict decode 讓 catalog 不健康，`allocateOrVerifyCatalog` 回 `CORRUPT`、不配置；`catalogRecordMatches` 仍以 M3 的 `allocateOverworld` 產生的 definition exact 比對，防守日後放寬。availability 不符則直接以 `DISABLED` 驗證。
- **orphan 判定在本 task 完成**：storage 位置可以從存檔根目錄與 `UniverseKeys` 推導（與 M3 `inspectStorage` 相同），pure JVM 可測，所以直接放在 `allocateOrVerifyCatalog` 裡，F4a 不需要另外接線。
- **「current 恰等於 flushed 才配置」**：controller 補充第 2 點把等待流程交給 F4a；`allocateOrVerifyCatalog` 本身也先檢查，未確認時回 `UNCONFIRMED`（fix round 1 起為 `WAIT`）、不配置（防守 F4a 漏查）。600 ticks 的 WARN 屬 F4a。
- **本輪不跑 Legacy、Main＋oracle**：controller 指定 Full、M3 與 M4 recovery。沒有新增 `SERVER_STARTED` listener、mixin 或 client 程式；`onStarted` 只多建一個不讀任何東西的 `ServerCatalog`。

### 裁示（controller，2026-10-02，F2c review 之後；原「需要裁示」）

- **裁示 1：catalog 的「等待」改成獨立狀態。** `Lookup.Status` 新增 `WAIT`（不帶 SEALED 原因、不帶 failure），只有 catalog 會回，依 §6.2 不得 SEALED、下一個 tick 再試；ledger 與 discovery 的 `UNCONFIRMED` 維持帶原因。由 fix round 1 實作。
- **裁示 2：strict NBT helper 維持 `JournalNbt` 與 `LedgerNbt` 分開，但 `LedgerNbt` 搬到 `persistence`，名稱不變。** 由 fix round 1 的 refactor commit 實作。
- **裁示 3（fix round 1 re-review 的 I-1 之後）：discovery 的唯讀查詢在未確認時也回 `WAIT`**（選項 (a)，依 spec §6 收斂共通第 2 點：未確認期間只重試收斂，不做新決策）；NEW 的 `appendDiscovery` 維持帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`。由 fix round 2 實作。

### 觀察（未修改）

- `UniverseDiscoveryState.UnhealthyDiscoveryException` 不帶 cause，M4 的 log 看不到 discovery 讀寫失敗的根因。M5 的路徑不經過它（`CollapseAuthorities` 自己分類並保留 cause），所以本 task 不改。
- `UniverseDiscoveryStateTest` 的 `target()` 不是 normalize 過的路徑；store 層的注入以 `Path.equals` 比對 `CheckedNbtFile` 傳入的 normalize 路徑，在 `@TempDir`（已是絕對路徑）下相等。

### 交給後續

- F4a（RESOLVE 的接線；F4a 的檔案清單沒有 `CollapseAuthorities.java`，以下 API 都已在本 task 提供）：
  - NEW 第 2 步（§6.2）：`unconfirmed(CATALOG)` 為真時先 `convergeIfNeeded(CATALOG)`；仍未確認就等下一個 tick、不進 SEALED，持續 600 ticks 記一次 WARN。否則 `allocateOrVerifyCatalog(id, EAGER_ONLY)`（§8.3 的 `MATERIALIZE_FAILED` 重試才傳 `MATERIALIZE_RETRY`；fix round 1 前是 boolean）：`APPENDED`／`FOUND` 繼續；`WRITE_FAILED`→SEALED(`ALLOCATION_IO_FAILED`)；`CORRUPT`→`AUTHORITY_CORRUPT`（含 orphan、不符、catalog 不健康）；`UNREADABLE`→`AUTHORITY_UNREADABLE`；`WAIT`（查完之後才變 dirty 的競態；fix round 1 前是 `UNCONFIRMED`）照等待處理，不 SEALED。物化只用 flushed catalog 的 record，而且 `unconfirmed(CATALOG)` 為假。
  - NEW 第 4 步（§6.4）：物化成功之後 `convergeIfNeeded(DISCOVERY)`，再 `appendDiscovery(id, 當下 game time, SelectedBy)`：`APPENDED`／`FOUND` 完成；`WRITE_FAILED`／`UNCONFIRMED`→SEALED(`ALLOCATION_IO_FAILED`)，重試前先收斂；`CORRUPT`／`UNREADABLE`→對應的 AUTHORITY 原因。game time 用哪一個世界的時間由 F4a 決定（必須非負）。
  - EXISTING（§8.1）：`discovery(id)` 為 `FOUND` 且 `gameplayEligible`；flushed catalog 的 record 以 `catalogRecordMatches(record, id, EAGER_ONLY)` 判定；不符→`EXISTING_INELIGIBLE`；discovery 的 `CORRUPT`／`UNREADABLE`→對應的 AUTHORITY 原因（§12）。
    - `discovery(id)` 的 `FOUND` **不檢查 eligibility**（唯讀查詢只回 record）。F4a 要以測試鎖住「record 存在但 `gameplayEligible=false` 時進 `EXISTING_INELIGIBLE`」（fix round 1 依 quality review Q-2(c) 補）。
    - discovery 回 `WAIT` 時（discovery 有未確認的 append），等待下一個 tick 或先 `convergeIfNeeded(DISCOVERY)` 再試，不判 `EXISTING_INELIGIBLE`，也不 SEALED（fix round 1 依 spec review M2 補；fix round 2 起 `discovery(id)` 在這時回不帶原因的 `WAIT`，不再是帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`）。NEW 的 `appendDiscovery` 不同：未確認時仍回帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`，依 §6.4 進 SEALED。
  - §8.3：改為 `DISABLED`、重試時改回 `EAGER_ENABLED`、失敗時改回 `DISABLED`（restore），都直接用 M3 的 `changeAvailability` 與 `flush(server)`；`unconfirmed(CATALOG)` 會自動反映 dirty（含 restore 之後 current 等於 flushed 但仍 dirty），之後由 `convergeIfNeeded(CATALOG)` 或原生 autosave 寫出。這些寫出不經 `CollapseAuthorities`，失敗不會累積它的退避，第一次 `convergeIfNeeded` 會立即嘗試。
- F6：W12 的跨 JVM 注入（catalog 配置與 discovery 追加的「atomic move 完成、讀回失敗」）需要 testmod mixin，可以掛在 `UniverseCatalogStore.write` 的讀回與 `CheckedNbtFile` 的讀檔（決策 9 的 GameTest 層注入）。
- F7：M5 build 之後 discovery 與兩個 ledger 共用 `CheckedNbtFile` 的讀檔上限（檔案 16 MiB、解壓後 NBT 16 MiB）。

### Fix round 1（`fece7a3`）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 6），code quality review Approved（Critical 0、Important 0、新 Minor 2，並對兩項待裁示給了設計建議）。controller 依此作出兩項裁示（見「裁示」），並指派本輪處理 Q-1、Q-2(a)(b)(c) 與 spec M1、M2、M4；spec M3（§15 第 805 行附近「catalog 配置失敗時的還原」與 §6.2 矛盾）由 controller 列入 F7 的 spec errata，本輪不處理。開工 HEAD 是 `b764413`。
- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `7676424` `refactor: move the strict NBT helper next to the checked file helper`（parent `b764413`）：`git mv` `collapse/LedgerNbt.java` → `persistence/LedgerNbt.java`，只改 package 宣告與 Javadoc；`UniverseDiscoveryState` 改一行 import，兩個 ledger 各加一行 import。
  - `fece7a3` `fix: give catalog waits their own result and name the availability policy`（parent `7676424`）：`CollapseAuthorities.java`、`UniverseDiscoveryState.java`（只改 Javadoc）、`CollapseAuthoritiesTest.java`。
  - 本小節由其後的 docs commit 加入，同一個 commit 也改寫了本節的 Review 行、Code-truth 的圖例與兩列（加註）、「第 6 點」的 strict helper 一條、行為不變的界線（M1）與 JUnit 的限制（M4）、「偏離」的兩條（加註）、「裁示」（原「需要裁示」）與「交給後續」的 F4a 各條。
- 行號以 `fece7a3` 為準；`A` 指 `collapse/CollapseAuthorities.java`。

| 項目 | 處理 |
| --- | --- |
| 裁示 1：catalog 的等待 | `Lookup.Status` 新增 `WAIT`（`A:176-181`），Javadoc 寫明只有 catalog 會回、依 §6.2 不得 SEALED、下一個 tick 再試。compact 建構子（`A:192-200`）改成只有 `WRITE_FAILED`、`CORRUPT`、`UNREADABLE`、`UNCONFIRMED` 帶原因與 failure，`WAIT` 兩者都不帶。`succeeded()`（`A:202-203`）改為依 status 判斷（FOUND、APPENDED、ABSENT），不再以「沒有原因」判斷，否則 `WAIT` 會被當成成功。新增 factory `waiting()`（`A:208`）；`allocateOrVerifyCatalog` 在 catalog 未確認時回 `Lookup.waiting()`（`A:599`）。`WriteResult.failed` 的 switch 補 `case WAIT`（`A:260`，丟 `IllegalArgumentException`：WAIT 不是 projection 的寫入結果）。`Lookup`、`allocateOrVerifyCatalog` 與 class 的 Javadoc 同步。ledger 與 discovery 的 `UNCONFIRMED` 不變（照舊帶 `Slot.writeFailure` 的原因） |
| 裁示 2：`LedgerNbt` 搬到 `persistence` | `7676424`，見上。JUnit 前後 533／2 skipped，逐 class 相同；JAR 的 entry 數不變，只有 class 路徑改變（見下方 JAR delta） |
| Q-1：ordinal 的算法測不出差異 | 新增 `catalogOrdinalIsTheFlushedMaximumPlusOneNotTheRecordCount`：flushed catalog 先放 ordinal 0 與 7（7 是 `DISABLED`），下一筆必須是 8，不是筆數 2。mutation Q-1（改成 `flushed.size()`）讓它失敗（`expected: <8> but was: <2>`）；首輪的 `catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords` 在這個 mutation 下仍會通過，所以這個 test 是必要的 |
| Q-2(a)：`append` 的呼叫者 | `UniverseDiscoveryState.append` 的 Javadoc（`D:110-112`）改為「只由 `CollapseAuthorities.appendDiscovery` 呼叫；gameplay 程式不得自行 append＋save」，並寫明繞過的後果 |
| Q-2(b)：`acceptDisabled` 的 boolean | 改為 `public enum AvailabilityPolicy { EAGER_ONLY, MATERIALIZE_RETRY }`（`A:72-88`，`accepts` 為 package-private）；`allocateOrVerifyCatalog(UniverseId, AvailabilityPolicy)`（`A:586`）與 `catalogRecordMatches(UniverseRecord, UniverseId, AvailabilityPolicy)`（`A:635`）的 Javadoc 寫明只有 §8.3 的 `MATERIALIZE_FAILED` 重試路徑可以傳 `MATERIALIZE_RETRY`（接受 `DISABLED`） |
| Q-2(c)、spec M2 | 「交給後續」的 EXISTING 一條補兩項：`discovery(id)` 的 `FOUND` 不檢查 eligibility，F4a 要以測試鎖住「`gameplayEligible=false` 時進 `EXISTING_INELIGIBLE`」；EXISTING 遇到 discovery 的 `UNCONFIRMED` 時等待或先 converge，不判 `EXISTING_INELIGIBLE`、也不進 `ALLOCATION_IO_FAILED`。NEW 第 2 步原本「`UNCONFIRMED`（競態）照等待處理」改為 `WAIT`。（re-review I-1 指出這與 `discovery()` 回帶原因的 `UNCONFIRMED` 互相矛盾，由 fix round 2 改為 `WAIT`） |
| spec M1 | 「第 6 點」行為不變的界線改寫：root compound 之後接了壓縮後超過 16 MiB 的多餘資料時，舊版接受、新版拒絕；只會發生在損壞檔案，方向是 fail closed |
| spec M4 | 「第 6 點」與證據表的 refactor JUnit 一列註明：首輪的 refactor 沒有記逐檔 blob，事後無法補。本輪的 refactor 在跑 JUnit 之前記錄了逐檔 blob（`f2c/fix1/refactor-state.txt`），commit 後的 blob 相同（`refactor-commit-blobs.txt`） |

測試（`CollapseAuthoritiesTest`，JUnit +1，既有 test 有改）：

- 新增 `catalogOrdinalIsTheFlushedMaximumPlusOneNotTheRecordCount`。
- 新增 helper `assertWait`：status 為 `WAIT`、沒有 SEALED 原因、沒有 failure、沒有 entry、`succeeded()` 為假。`catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged` 的兩個、`nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed` 的一個 catalog 斷言由 `assertUnconfirmed(…, ALLOCATION_IO_FAILED)` 改為 `assertWait`。
- 所有 catalog 呼叫的 `false`／`true` 改為 `EAGER_ONLY`／`MATERIALIZE_RETRY`；`catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords` 另加「`MATERIALIZE_RETRY` 也接受 `EAGER_ENABLED`」。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2c/fix1/`。diff hash 是 `git diff <a> <b> -- <path>` 輸出的 SHA-256 前 8 碼（`fix1/diff-hashes.txt`）：`b764413..7676424` 為 `src` `5a5531cf`、`src/main` `5a5531cf`、`src/test` 無差異；`7676424..fece7a3` 為 `src` `11421d61`、`src/main` `927e722b`、`src/test` `23b487dd`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| refactor JUnit | `b764413`；porcelain 為 1 個 `RM`（`git mv` 後再改）與 3 個 ` M`，逐檔 blob 在 `fix1/refactor-state.txt` | `fix1/refactor-junit-all.log`、`refactor-junit-perclass.txt` | 533，0 failure／error，2 skipped；逐 class 與 F2c 首輪的 GREEN（`f2c/green-junit-perclass.txt`）完全相同。commit `7676424` 的 4 個檔 blob 與記錄的相同（`refactor-commit-blobs.txt`） |
| RED | `7676424`（＝`b764413` 加上只搬位置的 refactor，沒有行為改變）；porcelain 為 3 個 ` M`，逐檔 blob 在 `fix1/red-state.txt`。`CollapseAuthorities.java` 是暫時的 RED adapter（`fix1/red-adapter/`，與 GREEN 的差異在 `green-to-red.diff`）：只保留讓測試能編譯的 API（`WAIT` 常數、`waiting()`、`AvailabilityPolicy`），catalog 未確認時照 `b764413` 回帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`，不變式與 `succeeded()` 也照 `b764413`。測試檔與 GREEN 相同 | focused `candidate.*`、`universe.*`、`collapse.*`（`fix1/red-junit.log`、`red-junit-cases.txt`、`red-TEST-collapse.CollapseAuthoritiesTest.xml`、`red-failure-types.txt`） | 預期 FAIL：218 個中 2 個，就是改用 `assertWait` 的兩個 test，失敗在 `assertWait` 的 status、原因與 failure 三個斷言（`Lookup[status=UNCONFIRMED, …, sealedReason=Optional[ALLOCATION_IO_FAILED], …]`）。failure 內文裡的例外型別只有 `AssertionFailedError`（`assertAll` 包成 `DefaultMultiCauseException`）。Q-1 的新 test 與 policy 的斷言在 RED 通過（行為本來就對），以下表 mutation 證明 |
| mutation | GREEN 工作樹，每個 mutation 單獨套用、只跑 `CollapseAuthoritiesTest`，跑完還原並以 `git hash-object` 核對（`fix1/mutations.ps1`、`mutations/summary.txt`、`*.log`、`*-TEST.xml`） | 5 個 | 全部被目標 test 的斷言抓到（下表）；跑完後 `CollapseAuthorities.java` 的 blob 與 mutation 前相同 |
| GREEN focused | 工作樹，blob 與 `fece7a3` 相同（`fix1/green-state.txt`、`commit-blobs.txt`） | `fix1/green-focused.log` | 218/218 |
| GREEN JUnit 全部 | 同上 | `fix1/green-junit-all.log`、`green-junit-perclass.txt` | 534，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；比 refactor 後多 1，就是 Q-1 的新 test |
| GREEN Full | 同上 | `gate-f2c-fix1-green-full-055407ac20b847e7b8b47cec3a18e959` | PASS（`fix1/green-full-summary.txt`）：default GameTest 153/153（`_windows` 79）、world exact restore；JUnit 534；testmod 命中 0 |
| GREEN M3 | 同上 | `gate-f2c-fix1-green-m3-c41dc298500f4bf0832f27c3e1be80b7` | PASS：universe 4 個 phase、transfer 5 個 phase（`fix1/green-m3-summary.txt`） |
| GREEN M4 recovery | 同上 | `recovery-run-bbc3c6dc82ac45629243d33bc14dcfe3.json` | PASS：24/24 phases、24 個獨立 JVM（`fix1/recovery-console.log`）。`LedgerNbt` 換了 package，discovery 的讀取路徑經過它，所以照 controller 指示加跑 |

| Mutation | 改動 | 失敗的 test（目標）與斷言 |
| --- | --- | --- |
| W-1 | catalog 未確認時改回帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`（首輪的行為） | `catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged`、`nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed`（`assertWait`：status、原因、failure 三個斷言） |
| W-2 | `Lookup.succeeded()` 改回以「沒有 SEALED 原因」判斷 | 同上兩個（「WAIT 不算成功」`expected: <false> but was: <true>`） |
| Q-1 | ordinal 改成 `flushed.size()` | `catalogOrdinalIsTheFlushedMaximumPlusOneNotTheRecordCount`（`expected: <8> but was: <2>`） |
| P-1 | `EAGER_ONLY` 也接受 `DISABLED` | `catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords`（`EAGER_ONLY` 卻回 `FOUND`） |
| P-2 | `MATERIALIZE_RETRY` 不接受 `DISABLED` | 同上（`expected: <FOUND> but was: <CORRUPT>`） |

- JAR（`fix1/green-jar-delta.txt`，與 F2c 首輪的 Full gate 比較）：release 346 entries（302 classes），多 `CollapseAuthorities$AvailabilityPolicy`；`LedgerNbt.class` 由 `collapse/` 移到 `persistence/`。sources 206，`LedgerNbt.java` 同樣只換路徑。
- runtime：Full gate 的 GameTest world 與 M4 recovery 的 12 個 root（`discovery-corrupt`、`journal-corrupt` 以外），discovery 都解碼為 `{SchemaVersion:1, Records:[]}`，沒有暫存檔殘留（`fix1/green-runtime-discovery.txt`）。
- 所有 gate 在 commit 之前以工作樹執行；commit `fece7a3` 時 3 個檔的 blob 與 gate 前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Legacy、Main＋oracle（controller 指示）：改動只在 `Lookup` 的狀態、catalog 的等待結果、availability 參數的型別與 helper 的 package，沒有新增 listener、mixin 或 client 程式。

### Fix round 2（`82d7bb3`）

- Re-review（fix round 1 之後）：Not approved，Critical 0、Important 1、Minor 0；裁示 1、裁示 2 與 Q-1、Q-2、M1、M4 都已關閉。
- **I-1**：EXISTING 的 handoff（spec M2）與 `discovery()` 的 API 契約互相矛盾。`Lookup` 的 Javadoc 說 `UNCONFIRMED`「帶可直接用於 SEALED 的原因」，`discovery()` 在 discovery 未確認時也回帶 `ALLOCATION_IO_FAILED` 的 `UNCONFIRMED`（測試鎖住），但 handoff 要 F4a 在 EXISTING 遇到它時等待、不 SEALED。同一個狀態的意思取決於哪個方法回的，正是裁示 1 要消除的陷阱。controller 裁示採 (a)（見「裁示」第 3 條）。開工 HEAD 是 `802870a`。
- Commit `82d7bb3` `fix: let discovery reads wait while the authority is unconfirmed`（parent `802870a`）：`CollapseAuthorities.java`、`CollapseAuthoritiesTest.java`。本小節由其後的 docs commit 加入，同一個 commit 也改寫了 Code-truth 的 discovery 唯讀查詢一列（加註）、「裁示」（加第 3 條）、「交給後續」的 EXISTING 一條，以及 Fix round 1 表的 Q-2(c)／M2 一列（加註）。
- 行號以 `82d7bb3` 為準；`A` 指 `collapse/CollapseAuthorities.java`。

| 項目 | 處理 |
| --- | --- |
| `discovery()` 的回傳 | 閘門回 `UNCONFIRMED` 時改回 `Lookup.waiting()`（`A:572`）；初始化失敗、遺失（`CORRUPT`、`UNREADABLE`）照舊。Javadoc（`A:557-567`）寫明：未確認時回 `WAIT`，依 §6 收斂共通第 2 點只等收斂，呼叫端等下一個 tick 或先 `convergeIfNeeded`，不判 `EXISTING_INELIGIBLE`、不 SEALED；並與 NEW 的 `appendDiscovery`（依 §6.4 回帶原因的 `UNCONFIRMED`）對照 |
| `WAIT` 的 Javadoc | `A:179-185`：改為「只有兩處會回：catalog 的配置（§6.2），以及 discovery 的唯讀查詢（§6 收斂共通第 2 點）」，依 spec 不得 SEALED，下一個 tick 再試或先 converge |
| `UNCONFIRMED` 的 Javadoc | `A:174-178`：寫明由 ledger 的查詢與寫入、discovery 的追加（NEW）回，帶原因。`Lookup` 開頭「其餘四種失敗狀態都帶著可直接用於 SEALED 的原因」因此對每一個回傳 `UNCONFIRMED` 的方法都成立 |
| `WriteResult.failed` 的訊息 | `A:264`：「WAIT 只屬於 catalog 的配置與 discovery 的唯讀查詢，不是 projection 的寫入結果」 |
| class Javadoc | 寫入一條補「discovery 的唯讀查詢與 catalog 的配置依 spec 不得 SEALED，回 `WAIT`」 |
| 測試 | `discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress` 的 `discovery(first)` 斷言（`CollapseAuthoritiesTest.java:716`）由 `assertUnconfirmed(…, ALLOCATION_IO_FAILED)` 改為 `assertWait`；同一段 `appendDiscovery` 的兩個 `assertUnconfirmed` 不變（NEW 照舊帶原因）。JUnit 數量不變 |

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f2c/fix2/`。`802870a..82d7bb3` 的 diff hash（`git diff` 輸出的 SHA-256 前 8 碼，`fix2/diff-hashes.txt`）：`src` `0f690423`、`src/main` `b2d63d0a`、`src/test` `fbbe3ac4`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `802870a`；porcelain 只有 ` M CollapseAuthoritiesTest.java`，production 沒有改（`CollapseAuthorities.java` 的 blob `84b4a88…` 與 HEAD 相同，逐檔 blob 在 `fix2/red-state.txt`）。這次不需要 adapter：`WAIT` 與 `assertWait` 在 fix round 1 已存在 | focused `collapse.*`（`fix2/red-junit.log`、`red-junit-cases.txt`、`red-TEST-collapse.CollapseAuthoritiesTest.xml`、`red-failure-types.txt`） | 預期 FAIL：45 個中 1 個，就是 `discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress`，失敗在新的 `assertWait` 的 status、原因與 failure 三個斷言（`Lookup[status=UNCONFIRMED, …, sealedReason=Optional[ALLOCATION_IO_FAILED], …]`）；例外型別只有 `AssertionFailedError` |
| mutation D-1 | GREEN 工作樹；把 `discovery()` 改回 `if (gate != null) return gate;`，只跑 `CollapseAuthoritiesTest`，跑完還原並以 `git hash-object` 核對（`fix2/mutations.ps1`、`mutations/summary.txt`、`D-1.log`、`D-1-TEST.xml`） | 1 個 | 被抓到：只有目標 test 失敗，訊息同 RED；還原後 blob 為 `d208077…`，與 mutation 前相同 |
| GREEN focused | 工作樹，blob 與 `82d7bb3` 相同（`fix2/green-state.txt`、`commit-blobs.txt`） | `fix2/green-focused.log` | `collapse.*` 45/45 |
| GREEN JUnit 全部 | 同上 | `fix2/green-junit-all.log`、`green-junit-perclass.txt` | 534，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；逐 class 與 fix round 1 的 GREEN 相同 |
| GREEN Full | 同上 | `gate-f2c-fix2-green-full-67099075fb104eb5b450e7ea4c3226be` | PASS（`fix2/green-full-summary.txt`）：default GameTest 153/153（`_windows` 79）、world exact restore；JUnit 534；release 346 entries（302 classes）、sources 206，entry 清單與 fix round 1 完全相同（`fix2/green-jar-delta.txt`）；testmod 命中 0 |

- 本輪不跑 M3 與 M4 recovery（controller 指示）：只改一個唯讀查詢在未確認時的回傳狀態，這個查詢目前沒有任何 production caller（EXISTING 重驗屬 F4a），catalog、lifecycle、transfer、journal 與 discovery 的讀寫路徑都沒有變；Legacy、Main＋oracle 同樣不跑。
- Full gate 在 commit 之前以工作樹執行；commit 時 2 個檔的 blob 與 gate 前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

## F3a：既有 MEASURED-only 分支

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `20966a2` `refactor: teach MEASURED-only branches about the collapse states`（parent `6cce51f`）：production 8 個檔——`superposition/SuperpositionSessionManager.java`、`persistence/SessionRecoveryManager.java`、`SessionRecoveryState.java`（只有改名）、`MeasuredRecoveryPhase.java`、`MeasuredWorldSaveCheckpoint.java`（只有 Javadoc）、`corridor/CorridorPageManager.java`、`chamber/ChamberSessionGateway.java`、`ChamberPowerCoordinator.java`；測試擴充 `SessionRecoverySchema4Test.java`、`ChamberPowerCoordinatorTest.java`，以及 testmod 的 `M4CandidateDoorGameTests.java`、`M4CandidateTestAccess.java`（只新增 fixture 與收尾 helper）；`CandidateLedgerServiceTest.java`、`SessionRecoveryStateTest.java` 只改名稱（見「補充第 3 點」）。
  - 本段的 docs commit 接在 `20966a2` 之後。
- Code range `6cce51f..20966a2`。
- 還沒有任何程式會產生塌縮狀態（提交 COLLAPSING 屬 F3d），所以 production 行為不變：新的分支只有測試 fixture 走得到，M4 狀態的判斷都照舊（逐列見下表）。
- Review：首輪結果與 fix round 1 見本節最後的「Fix round 1」。
- 最終 review：fix round 1（`dce3da0`）的 re-review Approved（Critical 0、Important 0、Minor 0）。**F3a 完成**：range `6cce51f..e10baab`（code `20966a2`、`dce3da0`）。

### §13.2 逐列對照

「spec 行號」以 spec 的基準 `a7e1be2` 為準，「目前行號」以 `20966a2` 為準。`SSM` 指 `superposition/SuperpositionSessionManager.java`，`SRM` 指 `persistence/SessionRecoveryManager.java`，`SRS` 指 `persistence/SessionRecoveryState.java`，`CPM` 指 `corridor/CorridorPageManager.java`，`PC` 指 `chamber/ChamberPowerCoordinator.java`。測試欄的 G1、G2、G3 見「測試」。

| spec 列（spec 行號） | 目前行號 | 處置 | 測試 |
| --- | --- | --- | --- |
| SSM attach `:59-64`、`:387-394`（`returning`） | attach 迴圈 `SSM:67`、`attachRecord` `:74-81`、`returning` `:492-502` | 迴圈本體抽成 `attachRecord`（窮舉 switch）。COLLAPSING 只取得來源 ticket（返還需要），未完成的返還與清理由 recovery 依推導階段續做。PASSAGE_READY 與 SEALED 不建 ticket、不寫入，record 原樣保留。M4 狀態照舊：非 DORMANT 取得 ticket 並 `returning`。`returning` 對塌縮狀態直接 return（呼叫端已排除，這裡再防守一次）。PASSAGE_READY 如何保留給 F3d，見「補充第 6 點」 | G2 的 attach 段（以反射呼叫 `attachRecord`）|
| SSM presence `:66-76`、runtime `:81` | presence `SSM:82-97`（塌縮狀態 `:85-89`）；`candidateSourceAuthorized` `:99-106` 未改 | flushed record 是塌縮狀態時，在任何 runtime 判斷之前只依 `collapseView` 推導：COLLAPSING、SEALED 回 `COLLAPSE`，PASSAGE_READY 回 `PASSAGE`。roll-forward 尚未確認的 READY→SEALED(TRR) 因此是 `COLLAPSE`。MEASURED 照舊。`:81` 的 runtime 判斷維持只認 SUPERPOSITION／MEASURED：塌縮狀態在上游 `candidateSelectionRecord`（`CPM:326`）就被拒絕，tick 也讓 runtime 跟隨塌縮狀態（下一列），塌縮狀態不會得到選擇權 | G1（presence）、G3（`presenceCollapse`、`runtimeFollowsCollapsing`）|
| SSM tick `:178-186` | `SSM:276` | flushed 是塌縮狀態時，runtime.state 跟隨它並 `continue`：不請求 measured recovery、不 `fail`、不看 Buff（由塌縮流程負責，F3d）。MEASURED 照舊 | G3（HEAD 在這裡丟 `IllegalArgumentException`；延伸之後 Buff 到期也不丟例外）|
| SSM measured 收尾 `:203` | `SSM:303-309` | 維持只處理 DORMANT 的 MEASURED（釋放 runtime 與來源 ticket）。COLLAPSING 的清理收尾走 `CPM.tickMeasuredRelease` 延伸後的同一路徑；依 spec §5，它的 runtime 與來源 ticket 保留到終局或進入 SEALED 才釋放（F3d），所以這個迴圈不處理它。見「偏離」 | G3（清理完成後 `sourceTicketHeldUntilTerminal`）|
| SSM returnToOrigin `:315-345`（`:323`） | `SSM:417-450`（塌縮狀態 `:426`） | authority 是塌縮狀態時一律回 false，沒有任何寫入，也不動 ticket 或 runtime；排在 `operationInProgress` 與 MEASURED 分支之前 | G1（`returnToOriginNoSideEffect`）|
| SRM blocks `:52` | `blocks` `SRM:54-60`、`frozen` `:66-76`、`freezes` `:82-85` | `freezes` 加入 COLLAPSING：只凍結尚未返還者；PASSAGE_READY 與 SEALED 不凍結。新增 package-private 的 `frozen(journal, player)`：M4 的 session 照舊看 current 或 flushed 任一份；current 或 flushed 任一份是塌縮狀態的 session，只看 `collapseView`（返還進度的 roll-forward 尚未確認時採用 current，已返還者先於落盤解除凍結；隔離中看 flushed） | JUnit `frozenFollowsTheCollapseViewForCollapseStates`；G2（`collapsingUnreturnedFrozen`）；G3（`collapsingUnreturnedFrozen`、`returnedPlayerNotFrozen`）|
| SRM onJoin `:59`、onDisconnect `:81-84`、recordFor `:210-213` | onJoin `SRM:100-103`、斷線迴圈 `:124-131`、`recordFor` `:330`、`:336-340` | 新增 package-private 的 `recordFor(journal, player)`：走訪 flushed，塌縮狀態改用 `collapseView`，只回 `SessionRecoveryState.occupies` 成立的 record。DORMANT、PASSAGE_READY、SEALED 與塌縮狀態的已返還者都不算；依唯一性規則最多一筆 | JUnit `recordForFindsOnlyTheSessionThatStillOccupiesThePlayer`；G2（`sealedReturnedJoinNotQueued`、`sealedDisconnectWritesNothing`、`disconnectFindsOnlyActiveB`）|
| SRM 返還迴圈 `:94`、release `:185`、markReturning `:216-222`、returnedRecord `:230` | 迴圈 `SRM:136-157`、`recoveryWork` `:160-176`、release `:303-307`、`markReturning` `:342-350`、`returnedRecord` `:356-366` | 迴圈條件抽成 `recoveryWork`（窮舉 switch）：RETURNING 為 RECOVER；MEASURED 照舊只在已請求時 RECOVER；COLLAPSING 依 receipt 推導——返還與清理完成（SETTLED）為 NONE，roll-forward 尚未確認為 RETRY_FLUSH（只重試寫出，補充第 2 點），其餘為 RECOVER；其他狀態為 NONE。全員返還後，MEASURED 與 COLLAPSING 都走 `releaseMeasured`。`markReturning` 對塌縮狀態不寫入：COLLAPSING 的返還由推導驅動，PASSAGE_READY 與 SEALED 依 §13.2 不寫入。`returnedRecord` 接受 COLLAPSING，進度經一般 `put`（roll-forward）寫入 | JUnit `recoveryWorkDerivesCollapsingReturnFromTheReceiptAndOnlyRetriesUnconfirmedProgress`；G3（真返還）|
| SRS remove `:179` | `SRS:595-604` | **F2a 已完成**：current、flushed、history 任一份是 `receiptRetained` 的狀態都拒絕。本 task 未改 | F2a 的 `removeRejectsEveryRetainedReceiptState` |
| SRS dormant、participantsAvailable、validateOwnership `:146-151`、`:246-258` | `participantsAvailable` `SRS:545-549`、`dormant` `:684-687`、`validateOwnership` `:688-698` | **F2a 已完成**：兩者都改用 `occupies`，Chamber 的唯一性不變。本 task 未改 | F2a 的 `occupiesReleasesOnlyReturnedCollapseParticipantsAndDormantReceipts`、`sealedReturnedParticipantCanJoinAnotherActiveSession` |
| CPM attach `:89-92` | attach 迴圈 `CPM:88`、`restoreSpace` `:101-114`、`spaceless` `:119-125` | 迴圈本體抽成 `restoreSpace`。`spaceless`（窮舉 switch）判定不需要 space 的保留收據：返還與清理都完成的 MEASURED（DORMANT）與 COLLAPSING（SETTLED），以及 PASSAGE_READY、SEALED。只有尚未完成的 COLLAPSING 建立 space，走 MEASURED 的同一路徑 | G2（`noCorridorSpace`，以反射呼叫 `restoreSpace`）|
| CPM requestMeasuredRecovery、releaseMeasured `:219-232` | `requestMeasuredRecovery` `CPM:235-243`、`measuredRecoveryComplete` `:248-252`（未改）、`releaseMeasured` `:254-263` | `requestMeasuredRecovery`：沒有保留收據才丟例外；塌縮狀態沒有請求可做（COLLAPSING 由推導驅動，PASSAGE_READY 與 SEALED 沒有 space），直接 return。`releaseMeasured`：COLLAPSING 不需要 recovery 請求，其餘條件不變。`measuredRecoveryComplete` 維持只認 DORMANT（供 SSM 收尾迴圈） | G1（`requestMeasuredRecoveryNoOp`）、G3（HEAD 丟 `IllegalArgumentException`）|
| CPM `:306`（candidateSelectionRecord） | `CPM:326` | 未改：原條件只接受 SUPERPOSITION／MEASURED，塌縮狀態本來就被拒絕 | —（既有條件）|
| CPM `:321`（measured） | `CPM:340-343` | 改為 flushed 或 current 任一份是 `receiptRetained`：塌縮狀態的 space 同樣凍結（不 publish、remap、retire），tick 只走保留式清理 | G3 |
| CPM `:424`（returnEntityPins） | `CPM:442-453` | COLLAPSING 不需要請求就能取得 pins | G3 |
| CPM `:837-842`（measured release） | `tickMeasuredRelease` `CPM:879-921` | 第一個分支改用 `spaceless`；空 lease 的 expected 保留原狀態（MEASURED 或 COLLAPSING；COLLAPSING 的清 lease 是 roll-forward）。「幾何仍未清空」與「checkpoint 已恢復，繼續 … 收尾」兩則訊息改為帶狀態，MEASURED 的文字不變。失敗處理 `fail` 的延伸見「偏離」 | G3（受控 checkpoint 失敗、退避、恢復、SETTLED）|
| MWSC `:23` | `MeasuredWorldSaveCheckpoint.java:20-21` | 程式不變：守門用 `MeasuredRecoveryPhase.from`，延伸之後 COLLAPSING 的 RELEASE_GEOMETRY 也會通過。只更新 Javadoc | G3、mutation M-G4 |
| SRS 版本白名單與寫出 schema `:78-85`、`:83`、`:192` | `fromNbt` `SRS:97-119`（白名單 `:101`）、`writtenSchema` `:622-630` | **F2a 已完成**。本 task 未改 | F2a 的 schema 測試 |
| ChamberSessionGateway `:10` | `ChamberSessionGateway.java:10-14` | 新增 `Presence.COLLAPSE`、`PASSAGE`，Javadoc 寫明推導來源。repo 內沒有對 `Presence` 的窮舉 switch | G1、JUnit |
| ChamberState、ChamberStatusSignal（整檔） | — | **F5**（`ChamberState.PASSAGE`→15），本 task 未改 | — |
| PC reconcile `:111-156` | M5 分支 `PC:117-122` | 決策 11：presence 為 `COLLAPSE` 或 `PASSAGE` 時，在 pending 判斷之前處理：不走 pending／needsReturn、不呼叫 `returnToOrigin`、不開新 session；已供電且 enabled 時 power state 設 POWERED，否則設 RETURNING（不設 OFF，保護維持）；一律回 ARMED（11）。R2 M5（`current==ARMED` 短路）與 PASSAGE→15 屬 F5，未改 | JUnit `collapseAndPassagePresenceNeverReturnOrStartAndOnlySetPoweredOrReturning`、`terminalPresenceNoneResumesTheExistingReturningRelease`；G1（`poweredArmed11`、`COLLAPSING.unpoweredKeepsProtectionWithoutReturn`）|
| ChamberControllerBlock `:87` | — | **F5**，本 task 未改 | — |

### controller 補充第 2–7 點

- **第 2 點（F2a 交接）**
  - `recordFor`、`blocks`、`presence` 改用 `occupies`／`collapseView`：見上表。
  - `returnedRecord` 延伸到 COLLAPSING：`SRM:356-366`。
  - 多人返還的 roll-forward：`recoveryWork` 在 `rollForwardPending(sid)` 為真時回 RETRY_FLUSH，tick 只呼叫 `journal.flush`，不呼叫 `recover`，也不從 flushed 疊寫另一位的返還；WARN 沿用既有「返還保留 pending」的去重。真正的落盤注入（兩位參與者、第一位的 flush 失敗、第二位上線）已列在 F3d 的 RED 清單（「返還進度的 roll-forward：注入返還寫入的落盤失敗 → 只重試同一份 current」）。本 task 以 JUnit 鎖住決策本身，live 注入留給 F3d。
- **第 3 點（Q7 改名）**
  - `SessionRecoveryState.uncheckedCandidateSessions()` 改名為 `uncheckedSessions()`（`SRS:158`）。production 呼叫端：`SRS.recoveryWritesSafe`（`:152`）與 Javadoc、`SRM.convergeUnchecked`（`:184-188`）、`SSM.settleUncheckedInitial`（`:241`）。`CorridorPageManager` 沒有呼叫它。
  - 只改名稱的既有測試共 6 個 test、7 處：`CandidateLedgerServiceTest.java:304`、`SessionRecoveryStateTest.java:282`、`SessionRecoverySchema4Test` 的 `commitRestoreWindowIsVisibleAsUncheckedAuthority`（2 處）、`rollForwardFailureKeepsCurrentAndAnotherSessionFlushWritesIt`、`quarantinedDoubleFaultConvergesAndClearsTheRollForwardFlag`，以及 testmod `M4CandidateDoorGameTests.java:460`（`unchecked_residue_is_quarantined_and_removed_without_pausing_recovery`）。斷言都不變。
  - WARN（`SRM:187-188`）改為「recovery 發現未 checked authority 殘留（candidate 或 M5 restore 類寫入）……並收斂為該 flushed 狀態（ARMING／SUPERPOSITION 推進為 checked RETURNING，其餘原樣保留；沒有 flushed 時 checked 移除）」，與 `convergenceTarget` 一致；`convergeUnchecked` 的 Javadoc 同步更新。收斂完成的 INFO「未 checked candidate authority 已收斂」沒有改：testmod `M4CandidateDoorGameTests.java:373` 以這段文字斷言，而既有測試只允許改名稱（見「交給後續」）。
- **第 4 點（F2a R1）**：選「改為只比對最新的 flushed」。`CPM.durable` 改用 `sameLineage`（`CPM:744-777`）：
  - 先照舊以單步 `sameAuthority` 判定。M4 狀態之間它本來就可遞移，守門不變。
  - 不成立、而最新 flushed 是塌縮狀態時，只比對跨越任意多次合法寫入都仍成立的不變量：candidate context 不變、ledger 只增不減；快取已保留收據時，ledger、selection、效果政策不變、returned 只增不減；快取已是塌縮狀態時，CollapseRevision、CommittedAtGameTime、Destination 不變。來源身分、參與者來源與 lease 照舊對 Space 核對。
  - 不選「每次 checked 寫入後刷新快照」：寫入者分散在 recovery 的返還、`tickMeasuredRelease`，以及 F3d 之後的 `CollapseService` 提交，每一個都要記得通知 CPM，漏一個就會誤判。每一次寫入的單步狀態邊本來就由 journal 的 `put`（current、flushed、history 三者）把關，CPM 不需要重複。快取欄位保留，因為 testmod 的 `resetMeasuredForTeardown` 以反射寫它。
  - 真實的多次寫入在本 task 已能重現，不必等 F3d：G3 的快取是選門時的 SUPERPOSITION，之後依序寫入 M→C 提交與 C 的返還進度。舊的比對會讓 `returnEntityPins` 丟「durable lease／來源權威不符」，返還永遠 pending（mutation M-G1）。
- **第 5 點（fixture）**：G1、G2 的塌縮狀態都由 `M4CandidateTestAccess.measuredReceipt`（candidate-aware 的 MEASURED＋SELECTED）先 `put`＋`flush`，再經 `commitRestore`（M→C、C→R）或 `commitRollForward`（C→S）進入。G3 用真入場、真側門選擇得到的 live MEASURED，再 `commitRestore` M→C（GameTest server 開服時 `m5ReadyThisInstance` 已為真）。收尾一律走終局 API（`terminateCollapseFixture`：SEALED 先 S→C 重試；未完成的 COLLAPSING 以一次 roll-forward 寫成全員返還、lease 清空；之後依狀態與目的地走 SOURCE_FAST_PATH、PREPARE_DOOR_CLOSED 或 PASSAGE_ENDED）。runtime 與來源 ticket 的釋放 API 屬 F3d，G2、G3 以 testmod 反射（`releaseSourceTicketForTeardown`、`releaseCollapseRuntimeForTeardown`）收尾。沒有改動 catalog 或 world keys：default run 的 boundary oracle PASS，per-test Universe receipt 38/38 PASS。
- **第 6 點（attach 的語意）**
  - COLLAPSING：取得來源 ticket。未完成的返還與清理由 recovery 依推導階段續做，CPM 也會為它重建 space。
  - PASSAGE_READY：`attachRecord` 不建 ticket、不轉 RETURNING、不寫入；CPM 不建 space；`recoveryWork` 回 NONE。presence 為 `PASSAGE`，所以 `start` 會因 presence 不是 NONE 而拒絕，`reconcile` 走 M5 分支。record 原樣留在 journal、原艙維持封鎖，等 F3d 的 `CollapseService` 在開服後以 `terminate(PASSAGE_ENDED)` 結束並正規化門。F3d 之前沒有任何程式會產生 PASSAGE_READY。
  - SEALED：同上，不建 ticket、不寫入，原樣保留。
- **第 7 點（`MeasuredRecoveryPhase`）**：選「延伸到 COLLAPSING」。
  - `from` 接受 MEASURED 或 COLLAPSING（都必須是 SELECTED）。RETURN_PLAYERS、RELEASE_GEOMETRY 就是 spec §7.3 同名的推導階段，兩者共用同一份推導。
  - 全員返還、lease 清空時，MEASURED 回 DORMANT，COLLAPSING 回新的 SETTLED。COLLAPSING 永遠不會得到 DORMANT，所以 M4 呼叫端的 `== DORMANT` 語意完全不變。新增 `released()`（DORMANT 或 SETTLED）。PASSAGE_READY 與 SEALED 照舊丟 `IllegalArgumentException`。
  - 移除 `RETAIN_RECEIPT`：repo 內沒有任何引用；journal 不保存 phase，所以不影響序列化，也沒有出現在 log。
  - 不另抽共用推導：多一個常數就能涵蓋；另抽一份，同名的階段就會有兩個來源。

### 測試

JUnit +6（534 → 540）：

- `SessionRecoverySchema4Test` +4（37 → 41）：
  - `measuredRecoveryPhaseExtendsToCollapsingAndDropsRetainReceipt`：COLLAPSING 的三個階段（以 `assertDoesNotThrow` 包住，HEAD 丟例外時落在斷言上）、MEASURED 照舊、PASSAGE_READY／SEALED 丟例外、`values()` 已不含 `RETAIN_RECEIPT`、`released()`。
  - `recordForFindsOnlyTheSessionThatStillOccupiesThePlayer`：「SEALED A＋活動 B」重複 16 組彼此獨立的 UUID（flushed 是 `Map.copyOf`，迭代順序不固定；只看參與者的 `findFirst` 全部恰好挑中 B 的機率約 2^-16）；只有 SEALED A、PASSAGE_READY、DORMANT、COLLAPSING 的已返還者都找不到；非 DORMANT 的 MEASURED 與 COLLAPSING 的未返還者找得到；返還進度 roll-forward 尚未確認時依 collapse view。
  - `frozenFollowsTheCollapseViewForCollapseStates`：COLLAPSING 凍結未返還者；roll-forward 尚未確認的已返還者解除；PASSAGE_READY／SEALED 不凍結；M4 的 MEASURED 與只在 current 的 RETURNING 照舊凍結。
  - `recoveryWorkDerivesCollapsingReturnFromTheReceiptAndOnlyRetriesUnconfirmedProgress`：COLLAPSING 的三種進度不論有無請求都 RECOVER、SETTLED 為 NONE、roll-forward 尚未確認為 RETRY_FLUSH、PASSAGE_READY／SEALED 為 NONE，以及 M4 狀態照舊。
- `ChamberPowerCoordinatorTest` +2（10 → 12）：
  - `collapseAndPassagePresenceNeverReturnOrStartAndOnlySetPoweredOrReturning`：兩種 presence × registry 的 4 種 power state × 供電 × enabled × 4 種目前 ChamberState，共 128 組。
  - `terminalPresenceNoneResumesTheExistingReturningRelease`：終局之後 presence 回到 NONE，既有流程把 RETURNING 解除為 POWERED 或 OFF。
  - 既有的測試 backend 新增 `returns` 計數，只供新 test 使用；既有斷言不變。

GameTest +3（153 → 156；`_windows` 79 → 80；per-test Universe receipt 35 → 38）。三個都在 `M4CandidateDoorGameTests.java`，各自使用獨立的 batchId：

- G1 `collapse_states_derive_presence_and_never_return_through_reconcile`（`m5_f3a_presence_reconcile`，各平台）：以 `ChamberGameTestBuilder` 建立並供電的真原艙，依序放 COLLAPSING（SOURCE）、PASSAGE_READY、SEALED（NEW）fixture，斷言 presence、`returnToOrigin`、`requestMeasuredRecovery`（兩個入口）、`blocks`、refresh 後的 ChamberState、比較器與 power state，以及斷電時的 RETURNING 與保護；每種 fixture 終局後原艙回到 IDLE／POWERED，最後斷電解除保護。
- G2 `collapse_states_attach_without_returning_and_lookup_only_occupying_sessions`（`m5_f3a_attach_lookup`，各平台）：以反射呼叫 `attachRecord`、`restoreSpace`；以 `onJoin`／`onDisconnect` 驗證 SEALED A 的已返還參與者，以及同時是活動 B（synthetic RETURNING）參與者時只找到 B；COLLAPSING 未返還者凍結。origin 在測試區內但沒有 Controller，所以 recovery 不會移動任何人。成功與失敗路徑都由 `TestHooks` 收尾。
- G3 `native_collapsing_buff_lapse_returns_through_the_retained_path_windows`（`m5_f3a_native_collapsing_return`，Windows）：真入場 → 真側門選擇（MEASURED）→ `commitRestore` M→C → 移除 Buff → 在 test 內直接呼叫 `SuperpositionSessionManager.tick` 與 `requestMeasuredRecovery`。之後讓 production 路徑完成返還與清理，第一次原生存檔 checkpoint 以既有的 `CheckpointFaultProbe` 受控失敗。斷言收據、凍結、ticket、log（COLLAPSING 的保留 WARN 一則附 stack、沒有 RETURNING 的 ERROR、沒有「返還保留 pending」、checkpoint 恢復的 INFO 一則）。最後終局並釋放 runtime 與 ticket，斷電後原艙解除保護。
  - RED 的安全：tick 與請求都以 `attempt` 在 test 內執行。HEAD 丟例外時，先以 `revertCollapseForTeardown`（只在 RED 使用）把 journal 與 runtime 退回 MEASURED，再走既有的 `resetMeasuredForTeardown`＋`trustedTeardown`，所以 RED run 沒有讓例外從 `END_SERVER_TICK` 拋出。
- testmod helper（`M4CandidateTestAccess`，只新增）：`origin`、`measuredReceipt`、`collapsing`、`terminateCollapseFixture`、`attachRecord`、`restoreSpace`、`removeSpaceForTeardown`、`sourceTicketHeld`、`runtimeState`、`disconnectPending`、`releaseSourceTicketForTeardown`、`releaseCollapseRuntimeForTeardown`、`revertCollapseForTeardown`、`call`。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3a/`；gate root 在 `.superpowers/verification/`。`git diff 6cce51f 20966a2 -- <path>` 輸出的 SHA-256 前 8 碼（`f3a/diff-hashes.txt`）：`src` `61c84e32`、`src/main` `129c23e2`、`src/test` `37266fa7`、`src/testmod` `0eaa63eb`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| 開發確認（不作為證據） | 工作樹為 GREEN 的 production 與測試，在 RED 之前先確認新 GameTest 能跑完 | GameTest＋onlyBatches：`gate-f3a-dev-green-b8428108d7d54745aef340179ee2f76a` | 3/3 PASS |
| RED JUnit 第一次（保留） | 同下一列，但 `recoveryWork…` 的失敗訊息在建構時呼叫 `MeasuredRecoveryPhase.from`（`f3a/red1/`） | focused | 6 個 test 失敗，其中 `recoveryWork…` 有 6 個子案例是在建構訊息時丟出 `IllegalArgumentException`，不是目標斷言。改成預先算好的 label 之後重跑，就是下一列 |
| RED JUnit | `6cce51f`；porcelain 為 12 個 ` M`：6 個 production 檔是暫時的 RED adapter，6 個測試檔與 GREEN 相同（逐檔 blob 在 `f3a/red-state.txt`）。adapter（`f3a/red-main-adapter.diff`，SHA-256 前 8 碼 `ae7b7faf`）只提供讓測試能編譯的 API，行為維持 HEAD：`Presence` 兩個常數、`MeasuredRecoveryPhase.SETTLED` 與 `released()`（`from` 仍是 HEAD 的推導，`RETAIN_RECEIPT` 仍在）、改名、以 HEAD 語意抽出的 `SSM.attachRecord`、`CPM.restoreSpace`、`SRM.recordFor`／`frozen`／`recoveryWork`。`ChamberPowerCoordinator.java` 與 `MeasuredWorldSaveCheckpoint.java` 與 HEAD 相同 | focused `persistence.*`、`chamber.*`（`f3a/red-junit.log`、`red-junit-xml/`、`red-junit-cases.txt`、`red-junit-breakdown.txt`、`red-failure-types.txt`） | 預期 FAIL：271 個中 6 個，就是 6 個新 test；子失敗 332＋1＋7＋16＋2＋5 個全部是 `AssertionFailedError`，沒有任何 fixture 在建立時丟例外。`recordFor…` 的 16 組「SEALED A＋活動 B」有 12 組挑中 A，另外 4 個確定性子案例（只有 SEALED A、PASSAGE_READY、COLLAPSING 的已返還者、roll-forward）全部失敗；`measuredRecoveryPhase…` 的 4 個 COLLAPSING 案例為「Unexpected exception thrown: … 只有 MEASURED + SELECTED 可進行保留式恢復」，`values()` 仍含 `RETAIN_RECEIPT`。RED 時通過的斷言（MEASURED 照舊、PASSAGE_READY／SEALED 丟例外、`released()`、unpowered 的 RETURNING 等）以下表 mutation 證明 |
| RED GameTest | `6cce51f`；production 同上一列的 adapter；兩個 JUnit 測試檔暫時換成只有改名的版本，讓 `test` 先通過（完整版本另存在 `f3a/full-tests/`，SHA-256 記在 `full-tests.sha256`，跑完逐位元組放回）；testmod 與 GREEN 相同（diff 前 8 碼 `0eaa63eb`，等於 commit 的 `src/testmod`）。逐檔 blob 在 `f3a/red-gametest-state.txt` | Full＋onlyBatches 3 個新 batch：`gate-f3a-red-gametest-af7f18345dc049b7b0efa17e4c153e25` | 預期 FAIL：JUnit 534 全過；3/3 新 GameTest 都失敗在目標 witness。G1：三種 fixture 的 presence、`returnToOrigin`（HEAD 丟例外）、`requestMeasuredRecovery`（丟例外）、`poweredArmed11`（power state 被 catch 設為 RETURNING）都是 false。G2：三種 fixture 的 attach 都丟例外；PASSAGE_READY 與 SEALED 也被建立 ticket；三者都被建立 space；`sealedReturnedJoinNotQueued`、`collapsingUnreturnedFrozen` 為 false；斷線等待逾時（SEALED A 被當成所屬 session，「斷線返還尚未落盤」WARN）。G3：「frozen=false tick=IllegalArgumentException: 缺少 durable MEASURED receipt request=同上」。RED run 沒有 crash，boundary oracle PASS，world exact restore |
| mutation（JUnit） | GREEN 工作樹，每個 mutation 單獨套用、只跑目標 test class，跑完還原並以 `git hash-object` 核對（`f3a/mutations.sh`、`mutations/summary.txt`、`M-*.diff`、`M-*.log`、`M-*-TEST.xml`） | 7 個 | 全部被目標斷言抓到（下表）；跑完後 production blob 與 GREEN 相同 |
| mutation（GameTest） | 同上（`f3a/gametest-mutations.sh`、`mutations/gametest-summary.txt`、`M-G*.diff`、`M-G*-gate.log`） | GameTest＋onlyBatches：`gate-f3a-mut-m-g1-167b1f29…`、`-m-g2-86ecce99…`、`-m-g3-c7122a70…`、`-m-g4-9a0adccc…`、`-m-g5-f839ed6d…` | 全部被抓到（下表）；5 個 gate 的 world 都 exact restore |
| GREEN focused | 工作樹，blob 與 `20966a2` 相同（`f3a/green-state.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`） | `f3a/green-focused.log`、`green-focused-xml/` | 271，0 failure／error，2 skipped；`SessionRecoverySchema4Test` 41/41、`ChamberPowerCoordinatorTest` 12/12 |
| GREEN JUnit 全部 | 同上 | `f3a/green-junit-all.log`、`green-junit-all-xml/` | 540，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；比 F2c 的 534 多 6，就是本 task 新增的 6 個 test |
| GREEN Full | 同上 | `gate-f3a-green-full-824d9fd266bc468b92ad8b4c584153cd` | PASS（`f3a/green-full-summary.txt`）：default GameTest 156/156（`_windows` 80），0 failure／error／skipped；boundary oracle PASS；per-test receipt 38/38 PASS；world exact restore；JUnit 540 |
| GREEN Legacy | 同上 | `gate-f3a-green-legacy-aa3615cfcefb43ba82ad962fbc71ed7f` | PASS（`f3a/green-legacy-summary.txt`）：1/1，receipt 1/1，world exact restore |
| GREEN M4 recovery | 同上 | `recovery-run-c9a7e12f6bdd4675bf5b0f9e43aa233d.json`（`f3a/recovery-console.log`、`green-recovery-summary.txt`） | PASS：24/24 phases、24 個獨立 JVM，全部正常停止、lock 已釋放 |

| Mutation | 改動 | 失敗的 test（目標斷言） |
| --- | --- | --- |
| M-1 | `frozen` 對塌縮狀態也看 current 或 flushed 任一份（不用 collapse view） | `frozenFollowsTheCollapseViewForCollapseStates`（「返還進度尚未確認：collapse view 已返還，解除凍結」） |
| M-2 | `recordFor` 不換成 collapse view | `recordForFindsOnlyTheSessionThatStillOccupiesThePlayer`（「返還進度尚未確認：依 collapse view，已返還者不再屬於它」） |
| M-3 | `released()` 只認 DORMANT | `measuredRecoveryPhase…`、`recoveryWork…`（兩者的 SETTLED 案例） |
| M-4 | `from` 接受任何保留收據 | `measuredRecoveryPhase…`（PASSAGE_READY、SEALED 不再丟例外） |
| M-5 | `recoveryWork` 不回 RETRY_FLUSH | `recoveryWork…`（「返還進度尚未確認：只重試寫出」） |
| M-6 | M5 分支斷電時設 OFF | `collapseAndPassage…`（96 個子案例）、`terminalPresenceNone…`（`expected: <RETURNING> but was: <OFF>`） |
| M-7 | M5 分支只認 `COLLAPSE` | `collapseAndPassage…`（PASSAGE 的 166 個子案例：呼叫 `returnToOrigin`、開新 session） |
| M-G1 | `CPM.durable` 改回單步 `sameAuthority`（補充第 4 點） | G3：返還進度寫入後 `returnEntityPins` 丟「durable lease／來源權威不符」（log 有「返還保留 pending」），清理一直沒有開始，有界等待逾時 |
| M-G2 | `CPM.fail` 改回只認 MEASURED | G3：`checkpointFaultWarnsOnceAsRetained=false`、`neverAttemptsReturning=false`（log 有「走廊保留租約等待安全返還」ERROR） |
| M-G3 | `attachRecord` 不為 COLLAPSING 取得 ticket | G2：`COLLAPSING.sourceTicketOnlyForCollapsing=false` |
| M-G4 | `MeasuredWorldSaveCheckpoint` 只接受 MEASURED | G3：checkpoint 一直失敗，COLLAPSING 的清理沒有完成，有界等待逾時 |
| M-G5 | 拿掉 SSM tick 的塌縮狀態分支 | G3：`runtimeFollowsCollapsing=false`。CPM 的 `requestMeasuredRecovery` 已改為不丟例外，所以 tick 本身不再丟出；這個 witness 是 tick 分支的唯一鎖 |

- JAR（`f3a/green-jar-delta.txt`，與 F2c fix round 2 的 Full gate 比較）：release 350 entries（306 classes），多 4 個 class：`SessionRecoveryManager$RecoveryWork`，以及 javac 為跨 class 的 enum switch 產生的 switch map `CorridorPageManager$1`、`SessionRecoveryManager$1`、`SuperpositionSessionManager$1`。sources 206，與之前相同。testmod／gametest／probe 命中 0。
- 所有 gate 都在 commit 之前以工作樹執行；commit 時 14 個檔的 blob 與 gate 前記錄的逐一相同（`f3a/green-state.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。數字由 `f3a/gate-summary.ps1`、`jar-delta.ps1`（沿用 F2b）唯讀計算。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Main＋oracle 與 M3：沒有新增 `SERVER_STARTED` listener、mixin、client 程式或資源，也沒有動 catalog、lifecycle 或 transfer。

### 偏離 plan／spec 字面之處

- **COLLAPSING 的返還由推導驅動，不需要請求**：MEASURED 的保留式 recovery 要先有 `requestMeasuredRecovery`（runtime 旗標）。COLLAPSING 的 RETURN_PLAYERS／RELEASE_GEOMETRY 依 spec §7.3 是由 receipt 推導、不另存的階段，§13.2 也要求 M5 狀態不呼叫 `requestMeasuredRecovery`，所以 recovery tick 直接依 `recoveryWork` 處理 COLLAPSING，重啟後也不需要 attach 補旗標。結果是 plan F3d 檔案清單中 `SessionRecoveryManager` 的「COLLAPSING 凍結、返還延伸」與 `CorridorPageManager` 的「release 延伸」已在本 task 完成（§13.2 與 controller 補充第 2 點都指派給 F3a）。F3d 仍負責提交、返還失敗 WARN 附 cause、落點碰撞（R2 M6），以及 live 流程的 GameTest。
- **`CorridorPageManager.fail` 不在 §13.2 表內，但一併延伸**：它是 measured release 路徑（`:837-842` 那一列）的失敗處理。若不延伸，COLLAPSING 的清理一失敗（例如原生存檔 checkpoint）就會嘗試寫 RETURNING（被 `sameAuthority` 拒絕），而且每次都記 ERROR。現在的做法：保留收據一律不轉 RETURNING；MEASURED 照舊請求 recovery；兩者都記同一則去重的 WARN，文字帶狀態（MEASURED 的文字不變，testmod 的既有斷言照舊通過）。由 G3 的受控 checkpoint 失敗與 mutation M-G2 鎖住。（controller 依 spec review 追認，見「Fix round 1」）
- **SSM 的 measured 收尾迴圈（`:203`）維持只處理 DORMANT**：§13.2 寫「COLLAPSING 的 release 收尾使用延伸後的同一路徑」，但 spec §5 規定 COLLAPSING 的 runtime 與來源 ticket 保留到終局或進入 SEALED，而這個迴圈的工作正是釋放兩者。所以解讀為：COLLAPSING 的收尾是 `CPM.tickMeasuredRelease` 的同一路徑，SSM 不在清理完成時釋放 runtime 與 ticket（G3 斷言 ticket 仍在）。（controller 裁定照實作，§13.2 的措辭列入 F7 的 spec errata，見「交給後續」）
- **為了可測而抽出的方法**：`SSM.attachRecord`、`CPM.restoreSpace` 是 private，GameTest 以反射呼叫，因為開服接手無法在共用的 GameTest server 上重跑；`SRM.recordFor(journal, player)`、`frozen(journal, player)`、`recoveryWork` 是 package-private，JUnit 在同一個 package。行為與原本的迴圈本體或條件相同，只多了 M5 分支。
- **例外文字**：`CPM.requestMeasuredRecovery` 的例外由「缺少 durable MEASURED receipt」改為「缺少 durable 保留收據」，`releaseMeasured` 的例外改為「保留收據清理前必須先確認全員返還（MEASURED 另須獨立 recovery 請求）」。沒有測試依賴這兩段文字。
- **G3 自行提交 COLLAPSING**：F3d 之前沒有 production 的提交路徑，G3 以 `commitRestore` 代替 `CollapseService`；其餘的 tick、返還與清理都走 production 路徑。

### 觀察（未修改）

- RED 時，HEAD 的 `reconcile` 對塌縮 fixture 每次 refresh 都記一則「艙體 … session 協調失敗，保留返還保護」WARN（RED run 共 7 則）；延伸之後不再出現。
- `CPM.measuredRecoveryRequested` 只對 MEASURED 有意義，COLLAPSING 的 space 不會設這個旗標。

### 交給後續

- F3d：
  - 提交：live MEASURED 的 runtime 已持有來源 ticket；DORMANT 續行與同一 instance 內的 SEALED→COLLAPSING 重試必須自行取得來源 ticket（attach 只涵蓋重啟）。提交之後，recovery 會依 `recoveryWork` 自動返還與清理，不要呼叫 `requestMeasuredRecovery`（對塌縮狀態是 no-op）。
  - 終局與進入 SEALED：需要正式 API 釋放 `SuperpositionSessionManager` 的 runtime（`sessions`）與來源 ticket（`tickets`）。目前 testmod 以 `M4CandidateTestAccess.releaseCollapseRuntimeForTeardown`／`releaseSourceTicketForTeardown` 反射代替，有 API 之後應改用它，並刪除這兩個 helper（fix round 1 的 m-4）。終局之後若 runtime 仍在，presence 會是 UNKNOWN，原艙停在 RETURNING（spec §5 的警告）。
  - 離開 SETTLED 的時機（fix round 1 的 I-1）：`MeasuredRecoveryPhase.released()` 與 SETTLED 只是 flushed record 的推導。清 lease 的寫出失敗、同一 tick 內 RETRY_FLUSH 成功時，record 先變成 SETTLED，走廊 space 要到下一個走廊 tick 才釋放。CollapseService 離開 SETTLED（RESOLVE、終局、C→S）之前，必須等 `CorridorPageManager.measuredRecoveryComplete(sid)`（或 `SessionRecoveryManager` 的同名委派）為真。「清 lease 的 flush 注入失敗、同一 tick 內 RETRY_FLUSH 成功」列入 F3d 的 RED：fix round 1 的 G3 已能在 recovery 層重現，F3d 要在 CollapseService 的流程上再鎖一次。
  - RETRY_FLUSH 沒有退避（fix round 1 的 m-6）：目前每個 tick 都重試寫出。依 spec §6 收斂共通第 2 點，以權威為單位統一退避。
  - `MeasuredRecoveryPhase.from` 只接受 MEASURED 與 COLLAPSING（SELECTED），PASSAGE_READY、SEALED 與其他狀態丟 `IllegalArgumentException`（fix round 1 的 m-7）。CollapseService 走訪 journal 時要先以狀態過濾，或逐 session catch，不能讓例外從 `END_SERVER_TICK` 拋出。
  - PASSAGE_READY 由 `CollapseService` 在開服後以 `terminate(PASSAGE_ENDED)` 結束（見「補充第 6 點」）。
  - 多人返還 roll-forward 的 live 注入測試（RED 清單已列）；返還失敗 WARN 附 cause；落點碰撞（R2 M6）。
  - `SessionRecoveryManager` 收斂完成的 INFO 仍寫「未 checked candidate authority 已收斂」。F3d 本來就要改 `M4CandidateDoorGameTests.java`，可以連同 `:373` 的斷言一起改成不限 candidate 的文字。
- F6 的 W1：attach 為 MEASURED／COLLAPSING 重建走廊 space 的正向測試（controller 依 F3a spec review 裁定交給 W1）。
- F7：spec §13.2「COLLAPSING 的 release 收尾使用延伸後的同一路徑」的措辭與 SSM 收尾迴圈（`SSM:203`）只處理 DORMANT 的實作不一致，controller 裁定照實作，措辭列入 F7 的 spec errata（見「偏離」第 3 條）。
- F5：`ChamberState.PASSAGE`→15、`ChamberStatusSignal`、`ChamberControllerBlock` 的訊息，以及 `reconcile` 的 R2 M5。`reconcile` 的 M5 分支目前一律回 ARMED（`PC:117-122`），F5 在這裡加入「PASSAGE＋已供電＋enabled→15」。

### Fix round 1（`dce3da0`）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 3）。controller 裁定：「偏離」的 (b) `CPM.fail` 延伸追認；(c) SSM `:203` 照實作，§13.2 的措辭列入 F7 勘誤；attach 重建 space 的正向測試交給 F6 的 W1。code quality review Not approved（Critical 0、Important 1、Minor 7），controller 指派本輪處理 I-1 與 m-1～m-7（m-6 只寫交接）。開工 HEAD 是 `7506be2`。
- Commit `dce3da0` `fix: expose retained release completion and narrow the collapse lineage check`（parent `7506be2`）：`CorridorPageManager.java`、`MeasuredRecoveryPhase.java`（只有 Javadoc）、`SessionRecoveryManager.java`（只有 Javadoc）、`SuperpositionSessionManager.java`（只有例外文字）；測試新增 `src/test/java/dev/quantumchamber/corridor/CorridorPageManagerLineageTest.java`，修改 testmod `M4CandidateDoorGameTests.java`、`M4CandidateTestAccess.java`。本小節由其後的 docs commit 加入，同一個 commit 也在本節開頭加上 Review 行，在「偏離」第 2、3 條加註 controller 的裁定，並在「交給後續」補上 I-1、m-6、m-7 與 m-4 的交接，以及 F6 W1、F7 errata 兩條。
- 行號以 `dce3da0` 為準。

| 項目 | 處理 |
| --- | --- |
| I-1 (a) 清理完成的查詢 | 延伸既有的 `CPM.measuredRecoveryComplete`（`CPM:254-258`），不另開 API：flushed 是 MEASURED 或 COLLAPSING、`released()` 成立，而且 `!spaces.containsKey`。MEASURED 時就是原本的「DORMANT 且 space 已釋放」；SSM 收尾迴圈（`SSM:305`）先過濾 MEASURED，M4 行為不變。Javadoc 寫明 record 可能先於 space 變成 SETTLED，塌縮流程離開 SETTLED 之前必須等它為真。`SRM.measuredRecoveryComplete`（`SRM:240`）是同一個查詢的委派，補了 Javadoc |
| I-1 (b) `released()` 的 Javadoc | `MeasuredRecoveryPhase.java:27-32`：只是 flushed record 的推導，不代表走廊 space、slot、保護範圍與 chunk ticket 已釋放；判斷清理完成要用 (a) 的查詢 |
| I-1 (c) 交接 | 寫進「交給後續」的 F3d：CollapseService 離開 SETTLED（RESOLVE、終局、C→S）之前必須等 (a) 為真；「清 lease 的 flush 注入失敗、同一 tick 內 RETRY_FLUSH 成功」列入 F3d 的 RED（本輪的 G3 已能重現，F3d 要在 CollapseService 的流程上再鎖一次） |
| I-1 測試 | G3 新增 `ReleaseFlushFault`（`M4CandidateDoorGameTests.java:665-682`，由 `beforeCandidateFlush` 在每次 journal flush 開頭呼叫，`:1285`）：COLLAPSING 清 lease 的寫出（current 已清空、flushed 仍有 lease）在 flush 開頭受控失敗恰一次。同一個 tick 內 recovery 的 RETRY_FLUSH 寫出成功，所以第一次看到 SETTLED 的 record 時 space 還在。新 witness：`releaseFlushFaultedOnce`、`recordSettledBeforeSpaceReleased`（重現 I-1 的情境）、`releaseIncompleteWhileSpaceRemains`（查詢為假）、`releaseCompleteOnceSpaceReleased`（space 釋放之後為真）。checkpoint 與清 lease 各失敗一次，保留 WARN 改為 `retainedWarnOncePerCause`（兩則、各附 stack、各對應一個原因） |
| m-1 `sameLineage` 限縮 | `CPM:761-787`：改為 package-private static。單步 `sameAuthority` 照舊；放寬只限最新 flushed 是 COLLAPSING、快取是 SUPERPOSITION／MEASURED／COLLAPSING。Javadoc 說明：COLLAPSING 沒有 opened／expires、Crossed、RecoveryRequired 與 SealedReason（合法組合規定全部為空），所以不重檢；不放寬 ARMING，因為 `commitInitial` 與選門都會經過 `durable`，快取到 MEASURED 之前至少是 SUPERPOSITION；PASSAGE_READY 與 SEALED 沒有 space，快取不可能是它們。新增 JUnit 表格 `CorridorPageManagerLineageTest`（17 列，見下） |
| m-2 失敗路徑的殘留 | G1（`:1671-`）加 `TestHooks`：本艙還有 fixture record 就移除，再斷電解除保護；成功路徑最後也 `hooks.release()`（冪等）。G2 的 hook 改用同一個 helper `cleanupFixtures`（`:1967-1975`），以 origin 位置找出本 test 的 fixture，連 `collapseFixture` 中途失敗留下的 MEASURED 也移除（反射）。G3 在 commit 成功後最先登記收尾 hook：清理真的完成（`measuredRecoveryComplete`）就以終局 API 移除，record 已不在就釋放 runtime 與來源 ticket；清理還沒完成時不能安全移除，保留現場。成功路徑先自行終局，再 `hooks.release()`，並新增 `runtimeAndTicketReleased` witness。這些收尾只動 journal、runtime 與 ticket，不動 catalog 或 world keys，boundary oracle 不受影響 |
| m-3 G2 的 witness | 改名為 `disconnectWithActiveBWritesNeither`（`:1793`），註解與 test Javadoc 寫明：它只證明整合結果（斷線待處理清除、A 與 B 都沒被改寫）。GREEN 的 `markReturning` 對 A、B 都不寫入，所以 GameTest 分不出找到的是誰；「只找到 B」的鑑別由 JUnit `recordForFindsOnlyTheSessionThatStillOccupiesThePlayer` 的 16 組獨立案例鎖住 |
| m-4 `releaseCollapseRuntimeForTeardown` | `M4CandidateTestAccess.java:264-280`：多一個 server 參數；前置檢查該 session 已不在 journal（current 與 flushed），或 flushed 是 SEALED，不符就丟例外。Javadoc 寫明 F3d 提供正式 API 之後刪除它與 `releaseSourceTicketForTeardown`（後者 G2 也用來移除自己以 `attachRecord` 建立的 ticket，所以不做 journal 前置檢查） |
| m-5 例外文字 | `SSM:496` 改為「未 checked authority 尚未確認，不可經返還落盤」，`CPM:1095`、`:1099` 改為「未 checked authority 不可經 fault recovery 承認」。沒有測試依賴這些文字。`SRM` 收斂完成的 INFO 依原交接留給 F3d |
| m-6 RETRY_FLUSH 沒有退避 | 本輪不改，寫進「交給後續」的 F3d：依 spec §6 收斂共通第 2 點，以權威為單位統一退避 |
| m-7 `from` 的前提 | `MeasuredRecoveryPhase.java:13-16` 的 Javadoc：只接受 MEASURED 與 COLLAPSING（SELECTED），其他狀態丟 `IllegalArgumentException`，走訪多筆 record 的呼叫端要先過濾或逐筆隔離例外。「交給後續」的 F3d 寫明 CollapseService 要 per-session catch 或先過濾 |

測試：

- JUnit +1（540 → 541）：`CorridorPageManagerLineageTest.lineageRelaxesOnlyMultiStepProgressIntoCollapsing`。fixture 只用 public 建構子（與 persistence 測試的 schema 3 fixture 同形狀）。
  - 接受：SUP→M→C、SUP→M→C→C、M→C→C（返還）、M(A 返還)→C（全員返還、清 lease）、C(返還)→C(清 lease)（單步）、S→C 重試（單步）。
  - 拒絕：Destination 改變、CommittedAtGameTime 改變、selection 改變、ledger 少一筆（快取 M 與快取 SUP 兩種）、returned 倒退、R→C、快取 SEALED(TRR)→C、M→…→PASSAGE_READY、M→…→SEALED、快取 ARMING→C。CollapseRevision 無法改變：`CollapseFields` 的建構子要求非空 Collapse 的 revision 固定為 1，所以以 CommittedAtGameTime 與 Destination 代表不可變欄位。
  - 對照：除了兩個單步列，其餘每一列都同時斷言單步 `sameAuthority` 不成立，證明接受確實來自放寬、拒絕不是靠第一行放行。
- GameTest 數量不變（156，`_windows` 80）：G1、G2、G3 的修改見上表。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3a/fix1/`。`git diff 7506be2 dce3da0 -- <path>` 輸出的 SHA-256 前 8 碼（`fix1/diff-hashes.txt`）：`src` `b5263af5`、`src/main` `ff740a22`、`src/test` `95282c60`、`src/testmod` `9b0e7fc1`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| 開發確認（不作為證據） | GREEN 的 production 與測試，在 RED 之前先確認改過的 GameTest 能跑完 | GameTest＋onlyBatches 3 個 F3a batch：`gate-f3a-fix1-dev-green-cb02fcd400584be48cc6e36d3df1ae0f` | 3/3 PASS；log 有兩則 COLLAPSING 保留 WARN（checkpoint、清 lease 的受控失敗）與 checkpoint 恢復的 INFO |
| RED JUnit | `7506be2`；porcelain 為 3 個 ` M` 與 1 個 `??`（逐檔 blob 在 `fix1/red-state.txt`）。`CorridorPageManager.java` 是暫時的 RED adapter（`fix1/red-main-adapter.diff`，前 8 碼 `878fdf30`）：只把 `sameLineage` 改成 package-private，判定與 `7506be2` 相同；其他 production 檔與 `7506be2` 相同。測試與 GREEN 相同 | focused `persistence.*`、`chamber.*`、`corridor.*`（`fix1/red-junit.log`、`red-junit-xml/`、`red-junit-breakdown.txt`、`red-failure-types.txt`） | 預期 FAIL：298 個中 1 個，就是新的 lineage test。5 個子失敗全部是 `AssertionFailedError`：R→C、快取 SEALED(TRR)→C、M→…→PASSAGE_READY、M→…→SEALED、快取 ARMING→C 都是 `expected: <false> but was: <true>`。其餘列在 RED 通過（舊的放寬也會拒絕它們），以 mutation L-1 證明 |
| RED GameTest | 同上一列的工作樹（GameTest 不跑 JUnit，所以不必換掉 JUnit 測試檔） | GameTest＋onlyBatches 3 個 F3a batch：`gate-f3a-fix1-red-gametest-7e2e004462c643dcb736bd2290fb0a67` | 預期 FAIL：G1、G2 PASS；G3 只有 `releaseCompleteOnceSpaceReleased=false`（`7506be2` 的查詢對 COLLAPSING 一律為假）。`releaseFlushFaultedOnce`、`recordSettledBeforeSpaceReleased`、`releaseIncompleteWhileSpaceRemains` 都是 true，也就是 I-1 的情境在 `7506be2` 上確實出現。test 的收尾照常完成，boundary oracle PASS，world exact restore |
| mutation | GREEN 工作樹，每個 mutation 單獨套用，跑完還原並以 `git hash-object` 核對（`fix1/mutations.sh`、`mutations/summary.txt`、`L-1.diff`、`L-1.log`、`L-1-TEST.xml`、`Q-1.diff`、`Q-1-gate.log`） | L-1：只跑 lineage test。Q-1：GameTest＋onlyBatches `m5_f3a_native_collapsing_return`，`gate-f3a-fix1-mut-q-1-cdd96dab471f43bc93804fabe6776775` | L-1（放寬分支改回 `return record.state().collapseState();`，controller 指定的 mutation）：11 個拒絕列全部失敗（`expected: <false> but was: <true>`）。Q-1（查詢不看 `!spaces.containsKey`）：G3 只有 `releaseIncompleteWhileSpaceRemains=false`。兩者還原後 blob 都與 GREEN 相同；Q-1 的 world exact restore |
| GREEN focused | 工作樹，blob 與 `dce3da0` 相同（`fix1/green-state.txt`、`commit-blobs.txt`） | `fix1/green-focused.log`、`green-focused-xml/` | 298，0 failure／error，2 skipped |
| GREEN JUnit 全部 | 同上 | `fix1/green-junit-all.log`、`green-junit-all-xml/` | 541，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；比 F3a 首輪多 1，就是新的 lineage test |
| GREEN Full | 同上 | `gate-f3a-fix1-green-full-b56446d837a64cf18574965fde713265` | PASS（`fix1/green-full-summary.txt`）：default GameTest 156/156（`_windows` 80）；boundary oracle PASS；per-test receipt 38/38 PASS；world exact restore；JUnit 541；release 350 entries／306 classes、sources 206，entry 清單與首輪完全相同（`fix1/green-jar-delta.txt`）；testmod 命中 0 |
| GREEN Legacy | 同上 | `gate-f3a-fix1-green-legacy-cbe101eed6b84474a021ae5b6b35f2db` | PASS：1/1，world exact restore |
| GREEN M4 recovery | 同上 | `recovery-run-5c17fbd9a8c8478e99e691a7d9629d6d.json`（`fix1/recovery-console.log`、`green-recovery-summary.txt`） | PASS：24/24 phases、24 個獨立 JVM，全部正常停止、lock 已釋放 |

- 所有 gate 都在 commit 之前以工作樹執行；commit 時 7 個檔的 blob 與 gate 前記錄的逐一相同（`fix1/green-state.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

## F3b：量子膜方塊狀態

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `71dcaaf` `feat: add the quantum membrane door states`（parent `e10baab`）：production 新增 `chamber/MembraneState.java`，修改 `chamber/QuantumBulkheadBlock.java`、`ChamberDoorService.java`、`WorldChamberDoorAdapter.java`、`corridor/CorridorPageManager.java`（只有 `:506` 一行）；資源修改 `blockstates/quantum_bulkhead.json`，新增 `models/block/quantum_bulkhead_membrane.json`、`textures/block/quantum_membrane.png`；client 修改 `QuantumSuperpositionClient.java`；測試新增 `src/test/java/dev/quantumchamber/chamber/MembraneStateTest.java`、testmod `M5CollapseGameTests.java`，`src/testmod/resources/fabric.mod.json` 只登記 `fabric-gametest` entrypoint。
  - 本段的 docs commit 接在 `71dcaaf` 之後。
- Code range `e10baab..71dcaaf`。
- 範圍是 spec §10.1 的方塊與門的狀態，以及決策 10 的「套用目標狀態」API。什麼時候套用哪個 membrane 屬 F4b、F5，本 task 沒有任何呼叫點。既有的開關門行為不變，只把切換 `OPEN` 的兩個寫入點改走 `withOpen`。
- 行號以 `71dcaaf` 為準。`QBB` 指 `chamber/QuantumBulkheadBlock.java`，`CDS` 指 `chamber/ChamberDoorService.java`，`WCDA` 指 `chamber/WorldChamberDoorAdapter.java`，`MS` 指 `chamber/MembraneState.java`。測試欄的 J 是 `MembraneStateTest`，G1、G2、G3 見「測試」。
- Review：首輪 spec review ✅（Critical 0、Important 0、Minor 2）；code quality review Not approved（Critical 0、Important 1、Minor 5）。controller 接受「偏離」第 1、2 條（開門擋流體、量子膜外框可瞄準）。處理見本節最後的「Fix round 1」；本節其餘內容描述 `71dcaaf`，fix round 1 改過的地方另加註。
- 最終 review：fix round 2（`f181203`）的 re-review Approved（Critical 0、Important 0、Minor 0）。**F3b 完成**：range `e10baab..e197108`（code `71dcaaf`、`88c220a`、`f181203`）。

### spec §10.1 逐項對照

| spec §10.1 | 程式位置 | 測試 |
| --- | --- | --- |
| `membrane` 屬性，值為 `none｜sealed_x｜sealed_z｜passable_x｜passable_z` | `MS` 全檔（`asString`）；`QBB:42` 的 `EnumProperty`、`appendProperties` | J `serializedNamesAndPropertyMatchTheSpec`（M-J1） |
| axis 是門平面的水平軸，由 outwardFacing 推得 | `MS:64` `axisFor`、`:71`／`:76` `sealedFor`／`passableFor`、`:54` `fits`；公式見「補充第 2 點」 | J `axisIsTheHorizontalAxisOfTheDoorPlaneForAllFourFacings`（M-J12）；G1 四個朝向 |
| `open=false` 時只能是 `none` | `QBB:64` `withOpen`、`:76` `withDoor`、`CDS:157` `DoorState` 建構子；讀取端 `QBB:83` `membrane` 把指令寫出的關門＋量子膜當成 none | J `withOpenFalseAlways…`（M-J11）、`withDoorAndDoorStateReject…`、`everyOpenAndMembraneWrite…`（M-J14）；G1 `toggleClose.*`、`applyClosed.*`；G2 `noClosedMembrane`（M-G2b） |
| 舊存檔沒有這個屬性，預設為 `none` | 預設狀態 `QBB:51`；原版 `BlockState.CODEC` 與 `NbtHelper.toBlockState` 對缺少的屬性一律取預設狀態的值 | J `defaultStateAndOldSavesWithoutTheMembraneDecodeToNone`（M-J2） |
| 碰撞：`sealed_*` 完整方塊；`none`、`passable_*` 沒有 | `QBB:108` `getCollisionShape` | J `collisionIsAFullCubeOnlyWhenClosedOrSealed`（M-J10）；G1 `*.collision`（以 `isSpaceEmpty` 查真實世界碰撞） |
| 模型：置中、雙面、不透明的薄平面；新貼圖；cutout | `blockstates/quantum_bulkhead.json`（`_z` 以 `y: 90` 旋轉）、`models/block/quantum_bulkhead_membrane.json`（z=8 的零厚度平面，north／south 兩面、沒有 `cullface`、`ambientocclusion: false`、`shade: false`）、`textures/block/quantum_membrane.png`；`QuantumSuperpositionClient.java:17` `BlockRenderLayerMap.putBlock(…, RenderLayer.getCutout())`（fix round 1 改為 `getCutoutMipped()`，見 M-1） | J `blockstateMapsEveryStateToExactlyOneModel`（10 個 state 各恰好一個 variant）、`membraneModelIsADoubleSidedCentredPlaneWithAnOpaqueSixteenPixelTexture`；真 client 畫面未驗（見「交給後續」） |
| 逐狀態覆寫 opacity、face culling、`suffocates`、`blockVision` | 見「補充第 3 點」 | J `openStatesNeitherSuffocate…`（M-J3、M-J4、M-J5）、`openStatesLetLightThroughAndNeverCullNeighbourFaces`（M-J6、M-J7、M-J8） |
| 方塊仍不可被流體取代 | `QBB:126`／`:131` `FluidFillable`（一律 false）、`:137` `canBucketPlace`（一律 false）；見「偏離」第 1 條 | J `noBulkheadStateCanBeFilledOrReplacedByFluids`（M-J13）；G3 |
| `ChamberDetector` 與 `WorldChamberBlockView` 不變：`open=true` 不論 membrane 都是 BULKHEAD_OPEN | 沒有改動（`WorldChamberBlockView.java:22` 只看 `OPEN`） | G1 `*.detectorOpen`、`mixed.detectorOpen`（M-G1） |
| 既有的門切換只改 `OPEN`，所有關門路徑都要同時設 none | `WCDA:27`、`CorridorPageManager.java:506` 改用 `withOpen` | J `everyOpenAndMembraneWrite…`（M-J14）；G1 `toggleClose.*` |

### controller 補充第 1–6 點

- **第 1 點（非法狀態）**
  - 守門只有三處：`withOpen`（關門一律設 none；由關到開一律是一般開門；已開時再開保留 membrane）、`withDoor`（`open=false`＋量子膜丟 `IllegalArgumentException`）、`ChamberDoorService.DoorState` 的建構子（同樣拒絕）。三者都拒絕非 bulkhead 的 state。
  - grep 結果：production 與 client 原始碼中直接 `.with(OPEN／MEMBRANE, …)` 寫 bulkhead 的位置，除了 `QBB` 自己，只有 `corridor/SessionEntranceAllocator.java:26` 的 `ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, frontOpen)`。它從預設狀態（membrane=none）建立入口門，任何 `frontOpen` 都得到合法狀態，所以不改（它也不在允許路徑內）。切換 `OPEN` 的兩個寫入點（`WCDA:27`、`CorridorPageManager.java:506`）已改用 `withOpen`。
  - 以 J `everyOpenAndMembraneWriteGoesThroughTheBulkheadGuards` 把 grep 變成斷言：掃描 `src/main/java` 與 `src/client/java`，`QBB` 之外不得有 `.with(OPEN／MEMBRANE, …)`，`SessionEntranceAllocator` 只允許恰好一處、而且緊接在 `QUANTUM_BULKHEAD.getDefaultState()` 之後；兩個切換寫入點必須含 `QuantumBulkheadBlock.withOpen(`。把 `CorridorPageManager` 改回直接寫 `OPEN`（M-J14）會被它抓到。
  - 沒有在 `getStateForNeighborUpdate`、`onBlockAdded` 等事件中另外正規化。理由：(a) 本 mod 的寫入路徑已不可能產生非法狀態；(b) 指令（`/setblock`）寫得出的 `open=false`＋量子膜，碰撞、模型（blockstate 的 `open=false` variant 涵蓋所有 membrane）、`WorldChamberBlockView` 與 `QBB.membrane` 都與關門相同，下一次開門或套用量子膜時由 `withOpen`／`withDoor` 正規化；(c) 在事件中呼叫 `setBlockState` 會碰到保護與重入，也會與 F5「沒有所屬 session 的 membrane 正規化」重疊。
- **第 2 點（axis）**：`MembraneState.axisFor(outwardFacing) = outwardFacing.rotateYCounterclockwise().getAxis()`。這正是 `ChamberGeometry.localToWorld` 中門板 local x（25 格的左右，`right`）所沿的軸，與門的法線（outwardFacing 的軸）垂直：NORTH／SOUTH → X，EAST／WEST → Z；UP／DOWN 丟 `IllegalArgumentException`。Javadoc 在 `MS:58-63`。J 的 axis test 鎖住四個方向，並以 `localToWorld` 交叉核對（門板左右兩端只沿 axis 相差 4 格、法線方向不在 axis 上）；把公式改成 `outwardFacing.getAxis()`（M-J12）會讓四個方向全部失敗。G1 在四個朝向的真實原艙上各跑一次。
- **第 3 點（逐狀態覆寫，1.21 Yarn 名稱）**：bulkhead 是 `Settings.copy(Blocks.BEDROCK)`，原本的預設值都由碰撞或外框推導。只有關門維持 bedrock 的行為，開門（含量子膜）一律如下：
  - opacity：覆寫 `AbstractBlock#getOpacity(BlockState, BlockView, BlockPos)`（`QBB:120`）。開門 0；關門 `world.getMaxLightLevel()`，與 HEAD 由 `isOpaqueFullCube` 推得的 15 相同。薄平面的光照取自所在格，所在格不透光時平面會全黑。
  - face culling：覆寫 `AbstractBlock#getCullingShape(BlockState, BlockView, BlockPos)`（`QBB:114`），不用 `isSideInvisible`。開門回空形狀，所以 shape cache 的 `extrudedFaces` 是空的、`isOpaqueFullCube` 為 false，鄰格面向它的面一律照畫，薄平面周圍不會出現透視破洞；關門回完整方塊（HEAD 由外框推得的值）。預設的 culling shape 等於外框，而本 task 給量子膜一個薄板外框（見下），所以要明確覆寫，讓剔除不隨外框的選擇改變。`isSideInvisible` 只影響同方塊鄰格，量子膜模型的兩個面都沒有 `cullface`，不需要它。
  - `suffocates`／`blockVision`：1.21 是 `AbstractBlock.Settings#suffocates(ContextPredicate)`、`#blockVision(ContextPredicate)` 的 predicate。`AbstractBlockState#shouldSuffocate`／`shouldBlockVision` 只是評估建立 state 時從 Settings 拷貝的 predicate；`Block` 的建構子固定以原版 `BlockState::new` 建立 state，mod 無法換成自己的 `BlockState` 子類別去覆寫這兩個方法，`AbstractBlock` 上也沒有可覆寫的對應方法。（fix round 1 更正：首版寫成「兩個方法是 final」，不正確，它們不是 final。）所以在 block 自己的建構子中，對傳入的 Settings 設定（`QBB:50` `super(settings.suffocates(QuantumBulkheadBlock::closedDoor).blockVision(QuantumBulkheadBlock::closedDoor))`），不改 `ModBlocks.java`。predicate 是 `!state.get(OPEN)`（`QBB:94`）：關門與 bedrock 的預設（solid 且碰撞為完整方塊）相同；sealed 量子膜雖有完整碰撞，也不窒息、不遮蔽視線。
  - 外框（不在四項內，一併說明）：`getOutlineShape`（`QBB:99`）對量子膜回置中的 2px 薄板（`_x` 為 z 7–9，`_z` 為 x 7–9），讓玩家瞄得到量子膜（spec §7.3、§10.5 的「以 bulkhead 開關門」）；一般開門維持空形狀、關門維持完整方塊。
  - 模型關閉自身的 ambient occlusion 與 shade：sealed 量子膜碰撞為完整方塊，`getAmbientOcclusionLightLevel` 會回 0.2，相鄰量子膜會互相變暗；兩個軸向的平面也因此亮度一致。
- **第 4 點（貼圖）**：`textures/block/quantum_membrane.png` 由 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3b/make-quantum-membrane-png.py`（SHA-256 `b2dfd1cef3035b02d72a846bebe4a900f17743b5fafb1f8389532c898a81810c`，gitignored）以 Python 標準庫產生，不取自網路或其他 mod。
  - 圖樣：每個像素 `t = (sin(2πx/8) + sin(2π(y+2)/8) + sin(2π(x+y)/16)) / 6 + 0.5`，顏色由深紫 (58,24,110) 線性插值到青 (64,210,220)，各通道 `int(a + (b-a)·t + 0.5)`。週期 8、8、16 都整除 16，所以門板上的 25 格會無縫拼接。
  - 格式：16×16、8-bit RGB（color type 2，沒有 alpha 通道，因此完全不透明）、filter 0、zlib level 9，386 bytes；沒有 `.mcmeta`，不用 emissive。
  - SHA-256 `522135683489b50b853589d49ba587880ce8d018e080f1fc8095e5d4c1d95c94`（commit 內的 blob 相同）。
- **第 5 點（GameTest）**：三個 test 都在 `M5CollapseGameTests.java`（default run），batchId 為 `m5_collapse_membrane_door`、`m5_collapse_apply_rollback`、`m5_collapse_fluid`。都不配置 Universe、不寫 journal：原艙用 `ChamberGameTestBuilder.buildForSession` 建立、不供電，所以不註冊（每個 test 以 `neverRegistered` witness 確認），結束時在 `finally` 移除自己寫入的全部方塊（流體通道先清通道再清外殼，不觸發鄰格更新）。default run 的 boundary oracle PASS，per-test Universe receipt 維持 38/38。
- **第 6 點（「套用目標狀態」API）**
  - `CDS:56` `public ApplyResult applyTarget(ServerWorld world, ChamberFrame frame, DoorState target)`，委派 `WCDA:40` `apply`。
  - 回傳 `ApplyResult(Outcome outcome, boolean activationBlocked)`（`CDS:183`）。`Outcome` 有 APPLIED（已改成 target，activationBlocked 已解除、Controller 已刷新）、UNCHANGED（原本就是 target，不寫入、不刷新、activationBlocked 不變）、REJECTED（門板不是完整 25 格 bulkhead，或寫入失敗且 rollback 成功；世界不變）、ROLLBACK_FAILED（門處於混合狀態，已設 activationBlocked、刷新 Controller、記 ERROR）。（fix round 1：REJECTED 拆成 DOOR_INCOMPLETE 與 ROLLED_BACK，另加 NOT_LOADED，見「Fix round 1」的 I-1、M-3。）`activationBlocked` 是呼叫結束時該 Controller 的旗標（Controller 不存在或未載入時為 false）；`succeeded()` 是 APPLIED 或 UNCHANGED。
  - 不在 server thread 呼叫時丟 `IllegalStateException`（`WCDA:44`）；target 的量子膜軸與門的朝向不符時丟 `IllegalArgumentException`，不寫任何一格（`CDS:72-74`）。Javadoc（`CDS:42-55`）寫明這兩點，以及 F3d、F4b、F5 經由它改門。
  - 沿用既有處理：`mutateWithRollback` 改成以狀態型別參數化（`CDS:96`），toggle（`Boolean`）與 apply（`DoorState`）共用同一份逐格寫入與逆序 rollback；apply 只寫目前不是 target 的格，每格 rollback 回自己的原狀態（原狀態可以不一致，例如前一次 rollback 失敗留下的混合門）。rollback 失敗的處理抽成 `WCDA:77` `blockActivation`，toggle 與 apply 共用，ERROR 文字不變。Controller 刷新同樣延到交易結束（`deferActivation`）。
  - 純邏輯核心 `CDS:65` `apply(DoorStateView, DoorStateMutator, …)` 是 package-private，JUnit 以記憶體門測試；世界讀取以 `QBB.membrane` 取實際生效的 membrane（`WCDA:70`）。
  - 本 task 沒有任何呼叫點；`toggle` 與它的 public 簽名不變。

### 測試

JUnit +19（541 → 560），全部在新的 `MembraneStateTest`（`@BeforeAll` 對 bulkhead 的 10 個 state 呼叫 `initShapeCache`，與 server 載入 data pack 後的 `Blocks.refreshShapeCache()` 一致；見「Gate 與證據」的第一次 RED）：

- `MembraneState`：`axisIsTheHorizontalAxisOfTheDoorPlaneForAllFourFacings`、`serializedNamesAndPropertyMatchTheSpec`。
- 屬性與守門：`defaultStateAndOldSavesWithoutTheMembraneDecodeToNone`（`BlockState.CODEC` 與 `NbtHelper` 兩條讀取路徑，`open` 為 true／false）、`withOpenFalseAlwaysClearsTheMembraneAndOpeningFromClosedIsAPlainOpenDoor`（走訪全部 10 個 state，含指令才寫得出的組合）、`withDoorAndDoorStateRejectAClosedDoorWithAMembrane`。
- 形狀與逐狀態覆寫：`collisionIsAFullCubeOnlyWhenClosedOrSealed`、`outlineIsTheThinMembranePlaneAlongTheDoorAxis`、`openStatesNeitherSuffocateNorBlockVisionWhileTheClosedDoorStillDoes`、`openStatesLetLightThroughAndNeverCullNeighbourFaces`（opacity、culling shape、`isOpaqueFullCube`，以及以 `Block.shouldDrawSide` 驗證六個方向的 bedrock 鄰格面只有關門時被剔除）、`noBulkheadStateCanBeFilledOrReplacedByFluids`（10 個 state × 水與岩漿的 still／flowing）。
- 套用目標狀態的核心：`applyWritesOnlyTheCellsThatDifferAndRefreshesOnce`、`applyWithEveryCellAlreadyAtTheTargetIsUnchangedWithoutWritingOrRefreshing`、`applyRejectsAnIncompleteDoorWithoutWritingAnyCell`、`applyRollsEveryChangedCellBackToItsOwnOriginalWhenAWriteFails`（第 13 次寫入失敗；回 false／丟例外 × 失敗前有無改動；原狀態不一致）、`applyReportsAnIncompleteRollbackAndStillTriesEveryChangedCell`、`applyRejectsATargetWhoseAxisDoesNotFitTheFrame`。
- 資源：`blockstateMapsEveryStateToExactlyOneModel`、`membraneModelIsADoubleSidedCentredPlaneWithAnOpaqueSixteenPixelTexture`。
- 寫入路徑：`everyOpenAndMembraneWriteGoesThroughTheBulkheadGuards`（補充第 1 點）。
- 既有的 `ChamberDoorServiceTest`（toggle 9 個 test）未修改，`mutateWithRollback` 參數化之後照常通過。

GameTest +3（156 → 159；`_windows` 維持 80；per-test Universe receipt 維持 38）：

- G1 `membrane_door_states_keep_the_detector_open_and_every_close_clears_them`（`m5_collapse_membrane_door`）：四個朝向各建一座未供電的原艙。(1) 直接寫入 none、sealed、passable 與混合的量子膜：`WorldChamberBlockView` 25 格都是 BULKHEAD_OPEN、`ChamberDetector` valid 且非 sealed；只有 sealed 有真實碰撞。(2) 以 mock player 對 bulkhead `onUse`（`WorldChamberDoorAdapter.toggle`）：混合量子膜的門關上後 25 格都是 `open=false`＋none、detector 為 sealed；再開是一般開門。(3) `applyTarget` 依序 sealed → 再一次（UNCHANGED）→ passable → 關門 → 由關門直接 sealed → 一般開門，每一步斷言 outcome、activationBlocked、25 格狀態與 detector；軸向不符丟 `IllegalArgumentException` 且不寫入；門板缺一格時 REJECTED、其他 24 格不寫。
- G2 `door_target_rollback_failure_blocks_activation_until_the_next_success`（`m5_collapse_apply_rollback`）：先套用 passable，再以既有的 `DoorWriteFault.during`（第 25 格關門寫入失敗、所有回到開門的 rollback 被拒；回 false 與丟例外兩種）套用關門：結果為 ROLLBACK_FAILED、回報與 Controller 的 activationBlocked 都是 true，而且 25 格沒有任何 `open=false`＋量子膜；之後套用一般開門為 APPLIED，activationBlocked 解除。
- G3 `every_bulkhead_state_holds_back_fluids`（`m5_collapse_fluid`）：不在任何 Chamber 內（不受保護），每個 state（關門、none、sealed_x／z、passable_x／z）各一個封閉的 1×3 通道「水源｜bulkhead｜乾格」，30 tick 後水源仍在、bulkhead 不變、乾格仍乾；另一組相同通道直接對 bulkhead 呼叫 `BucketItem.placeFluid`，必須回 false、bulkhead 不變、乾格仍乾。
- 三個 test 都以 witness 清單彙整；失敗時完整清單寫入 log `M5_COLLAPSE_WITNESS_FAILED`，斷言訊息只列前 8 項（原因見「Gate 與證據」的第一次 RED GameTest）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3b/`；gate root 在 `.superpowers/verification/`。`git diff e10baab 71dcaaf -- <path>` 輸出的 SHA-256 前 8 碼（`f3b/diff-hashes.txt`）：`src` `4ba33b52`、`src/main` `ca9fcb3f`、`src/client` `e07a3a0a`、`src/test` `424f5297`、`src/testmod` `e0e59750`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED JUnit 第一次（保留） | `e10baab`；同下一列的 adapter，測試是較早的版本（`f3b/red1/`） | focused `chamber.*`（`f3b/red1/red-junit.log`、`red-junit-xml/`） | 121 個中 15 個失敗，其中兩個不是目標斷言：`outline…` 對空形狀呼叫 `getBoundingBox` 丟 `UnsupportedOperationException`；`openStatesNeitherSuffocate…` 斷言 HEAD 的關門會窒息卻得到 false，原因是 JUnit 不經過 server 的 `Blocks.refreshShapeCache()`，mod 方塊的 state 沒有 shape cache、`isSolid()` 一律 false。測試改為比較外框的 box 清單、在 `@BeforeAll` 建立 shape cache，就是下一列 |
| RED JUnit | `e10baab`；porcelain 為 3 個 ` M`（`ChamberDoorService.java`、`QuantumBulkheadBlock.java`、testmod `fabric.mod.json`）與 3 個 `??`（`MembraneState.java`、`MembraneStateTest.java`、`M5CollapseGameTests.java`），逐檔 blob 在 `f3b/red-state.txt`。production 是暫時的 RED adapter（`f3b/red-main-adapter.diff`，SHA-256 前 8 碼 `e8343e62`）：只提供讓測試能編譯的 API，行為維持 HEAD——`MEMBRANE` 屬性與預設 none、`withOpen`／`withDoor` 只做 `with`、`membrane` 不正規化、形狀只看 `OPEN`、沒有任何覆寫；`MembraneState` 的名稱齊全，但 `axisFor` 一律回 X、`fits` 一律 true；`ChamberDoorService` 的型別齊全，`apply` 與 `applyTarget` 一律回 REJECTED、不寫入。資源與 client 與 HEAD 相同 | focused `chamber.*`（`f3b/red-junit.log`、`red-junit-xml/`、`red-junit-breakdown.txt`） | 預期 FAIL：121 個中 14 個，都是新的 test；子失敗全部是 `AssertionFailedError`（外層為 assertAll 的彙整），沒有任何 fixture 在建立時丟例外。RED 時通過的 5 個新 test（名稱、舊存檔預設、窒息與視線、光與剔除、門板缺格）以下表 mutation 證明 |
| RED GameTest 第一次（保留） | 同上一列；testmod 是較早的版本 | GameTest＋onlyBatches 3 個新 batch：`gate-f3b-red-gametest-c9fd10e2fa8d499abcae42510a738437` | 預期 FAIL，3/3 都失敗在目標 witness。另外停服存檔時有一個 chunk 存檔失敗：原版把 GameTest 的失敗訊息寫進 lectern 的書，72 個 witness 的訊息超過書頁長度上限。之後斷言訊息改為只列前 8 項、完整清單寫入 log，就是下一列 |
| RED GameTest | 同上；testmod 與 commit 相同（逐檔 blob 在 `f3b/red-gametest-state.txt`） | GameTest＋onlyBatches 3 個新 batch：`gate-f3b-red-gametest-2-315a27760dc2411a95ba742d2ddb4db0`（`f3b/red-gametest-summary.txt`） | 預期 FAIL：0/3。G1 72 個 witness 失敗（四個朝向各 18：sealed 沒有碰撞、toggle 關門後留下量子膜、`applyTarget` 各步驟、軸向守門、缺格門）；G3 20 個（none、sealed、passable 五個 state 的流動與水桶，HEAD 的開門會被水取代）；G2 10 個（setup、ROLLBACK_FAILED、activationBlocked 的回報與 Controller、恢復）。沒有 crash，沒有 chunk 存檔失敗，boundary oracle PASS，world exact restore |
| 開發確認（不作為證據） | GREEN 工作樹 | GameTest＋onlyBatches：`gate-f3b-dev-green-4b809595c0ba4794830aaf053241d5df` | 3/3 PASS；log 只有 G2 預期的兩則 rollback ERROR |
| mutation（JUnit） | GREEN 工作樹，每個 mutation 單獨套用、只跑 `MembraneStateTest`，跑完還原並以 `git hash-object` 核對（`f3b/mutations.sh`、`mutations/summary.txt`、`M-J*.diff`、`M-J*.log`、`M-J*-TEST.xml`） | 14 個 | 全部被目標斷言抓到（下表），失敗都是 `AssertionFailedError`；跑完後 blob 與 GREEN 相同（`f3b/pre-mutation-blobs.txt`、`post-mutation-blobs.txt`） |
| mutation（GameTest） | 同上（`f3b/gametest-mutations.sh`、`gametest-mutations-2.sh`、`mutations/gametest-summary.txt`、`M-G*.diff`、`M-G*-gate.log`、`M-G*-witness.txt`） | GameTest＋onlyBatches：`gate-f3b-mut-m-g1-d5f88de335674067936fa2d5f618a2f0`、`-m-g2-e232d2b6997242728dc7038b5fbda1c6`、`-m-g2b-6bcbd3d366f945a0beec6a6a49a01d22`、`-m-g3-9ad96f180dca4149b0a95ed6c00352b0` | 全部被抓到（下表）；4 個 gate 的 boundary oracle PASS、world exact restore；跑完後 blob 與 GREEN 相同（`f3b/green-state-blobs.txt`） |
| GREEN focused | 工作樹，blob 與 `71dcaaf` 相同 | `f3b/green-focused.log`、`green-focused-xml/` | 121，0 failure／error |
| GREEN Full（含全部 JUnit） | 同上 | `gate-f3b-green-full-efd4355e1f5147018a73c0902d6219c9`（`f3b/green-full-summary.txt`、`green-junit-all-xml/`） | PASS：default GameTest 159/159（`_windows` 80），0 failure／error／skipped；boundary oracle PASS；per-test receipt 38/38 PASS；world exact restore；JUnit 560，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`），比 F3a fix round 1 的 541 多 19，就是本 task 新增的 19 個 test；testmod 命中 0 |
| GREEN Main＋oracle | 同上 | `gate-f3b-green-main-7a729763b6d44553bb6132e60f39293a`（`f3b/green-main-gate.log`、`green-main-oracle.log`） | PASS：Fabric mod list 沒有 testmod、runtime classpath／argfiles 命中 0、class-load 命中 0、world keys exact、正常停止、exact restore |

| Mutation | 改動 | 失敗的 test（目標斷言） |
| --- | --- | --- |
| M-J1 | `PASSABLE_Z` 的名稱改為 `passable_y` | `serializedNames…`；`blockstateMaps…`（blockstate 的 key 對不上） |
| M-J2 | 預設狀態的 membrane 改為 `PASSABLE_X` | `defaultStateAndOldSaves…` |
| M-J3 | 拿掉 `suffocates(...)`（回到 bedrock 預設） | `openStatesNeitherSuffocate…`（sealed 會窒息） |
| M-J4 | 拿掉 `blockVision(...)` | 同上（sealed 遮蔽視線） |
| M-J5 | predicate 改為 `state.get(OPEN)`（兩個分支都反轉） | 同上（關門與開門都錯） |
| M-J6 | culling shape 反轉 | `openStatesLetLightThrough…`（含 `shouldDrawSide` 的透視破洞） |
| M-J7 | opacity 反轉 | 同上 |
| M-J8 | 拿掉 culling 覆寫（回到預設的外框） | 同上（量子膜的 culling shape 不為空） |
| M-J9 | 門板缺格時略過而不是 REJECTED | `applyRejectsAnIncompleteDoor…` |
| M-J10 | 碰撞改為「有量子膜才完整」 | `collisionIsAFullCube…`（關門沒有碰撞、passable 有碰撞） |
| M-J11 | 拿掉「已開時再開保留 membrane」 | `withOpenFalseAlways…` |
| M-J12 | `axisFor` 改為 `outwardFacing.getAxis()`（門的法線） | `axisIs…`、`withDoorAndDoorState…`（`DoorState.sealed(EAST)`） |
| M-J13 | 拿掉 `canBucketPlace` 覆寫 | `noBulkheadState…`（開門可被水桶取代） |
| M-J14 | `CorridorPageManager.java:506` 改回直接寫 `OPEN` | `everyOpenAndMembraneWrite…` |
| M-G1 | `WorldChamberBlockView` 把量子膜當成關門 | G1：`*.detectorOpen`、`mixed.detectorOpen` 等 36 個 witness |
| M-G2 | `applyTarget` 的寫入只寫 `OPEN` | G1 的 `apply*` 32 個 witness、G2 `setupPassable`。套用量子膜變成「寫入相同狀態」而回 false、整面 rollback，G2 在 setup 就失敗，沒有打到 `noClosedMembrane`，所以另做 M-G2b（保留這個 root） |
| M-G2b | `applyTarget` 開門照常，關門只寫 `OPEN`（保留量子膜） | G2：`false.noClosedMembrane`、`throw.noClosedMembrane`；G1：四個朝向的 `applyClosed.allCells` |
| M-G3 | APPLIED 時不解除 activationBlocked | G2：`recoveredApplied`、`controllerUnblocked`（兩種故障各一） |

- G3 的關門 witness 在 RED 就成立（HEAD 的關門本來就是 solid，水流不進去），屬既有行為的回歸檢查；同一段 witness 程式在 RED 對 none、sealed、passable 都失敗，證明它確實偵測得到取代。
- JAR（`f3b/green-jar-delta.txt`，與 F3a fix round 1 的 Full gate 比較）：release 350 → 359 entries（306 → 312 classes）。新增 6 個 class：`MembraneState`、`ChamberDoorService$DoorState`、`$ApplyResult`、`$ApplyResult$Outcome`、`$DoorStateView`、`$DoorStateMutator`；3 個資源 entry：`models/block/quantum_bulkhead_membrane.json`、目錄 `textures/block/`、`textures/block/quantum_membrane.png`。sources 206 → 210：`MembraneState.java` 與同樣 3 個資源 entry。testmod／gametest／probe 命中 0。
- log（與 F3a fix round 1 的 Full gate 比較）：ERROR 27 → 29，多的兩則是 G2 預期的「整面門控 rollback 失敗」（M1 既有的 `DoorWriteFault` test 在兩次 run 都各有兩則同樣的 ERROR）；WARN 49 → 49。
- 所有 gate 都在 commit 之前以工作樹執行；commit 時 12 個檔的 blob 與 gate 前記錄的逐一相同（`f3b/green-state-blobs.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。數字由 `f3b/gate-summary.ps1`、`jar-delta.ps1`（沿用 F2b）、`junit-breakdown.py`、`gametest-witness.py` 唯讀計算。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Legacy、M3 與 M4 recovery：沒有動 journal codec、schema、返還路徑、recovery 或 catalog／lifecycle／transfer。

### 偏離 plan／spec 字面之處

- **流體：把「不可被流體取代」做成方塊本身的性質，連帶改變一般開門的既有行為**。spec 寫「方塊**仍**不可被流體取代」，但 HEAD 的開門沒有碰撞、不是 solid，原版 `FlowableFluid.canFill` 與 `BucketItem` 都會取代它；目前只有 Chamber 保護（`ChamberProtectionService` 在 OFF 以外拒絕寫入）擋住。passable 量子膜同樣沒有碰撞，所以沒有保護時也會被取代（G3 的 RED 證實 none、passable，以及 adapter 下的 sealed 都會被水取代）。本 task 讓 bulkhead 實作 `FluidFillable`（一律不可填入）並覆寫 `canBucketPlace`（一律 false），任何狀態都擋得住流體。副作用：未供電（OFF）或未註冊的艙，開著的門也不再被水或水桶沖掉（原本會被取代、而且不掉落）。這只會讓門更堅固，但屬既有行為的改變，請 controller 確認。（controller 已接受。）
- **量子膜有薄板外框**：spec 只規定碰撞與模型。給量子膜 2px 的外框，玩家才瞄得到它、以 bulkhead 開關門（spec §7.3、§10.5）；一般開門維持空外框。若要讓量子膜瞄不到，只需改 `getOutlineShape`；culling 已明確覆寫，不受影響。（controller 已接受；設計文件 :164「開門後仍維持 empty outline／collision」的同步交給 F7，見「交給後續」。）
- **plan 只列 `withOpen`，另外新增 `withDoor` 與 `membrane`**：套用目標狀態 API 需要寫入完整的 open＋membrane（`withDoor`），讀取時需要把指令寫出的關門＋量子膜當成 none（`membrane`）。三者共用同一個 bulkhead 守門。
- **既有程式的整理**：`mutateWithRollback` 改成以狀態型別參數化，`WorldChamberDoorAdapter` 抽出 `blockActivation`／`loadedController`。toggle 的行為、例外與 ERROR 文字都不變，既有的 `ChamberDoorServiceTest` 未修改並通過。
- **測試位置**：門服務核心的 JUnit 放在 `MembraneStateTest`，因為 `ChamberDoorServiceTest` 不在允許路徑內。
- **GameTest 斷言訊息只列前 8 項**：見「Gate 與證據」的第一次 RED GameTest。

### 觀察（未修改）

- `toggle` 用在量子膜門上時（F4b 的攔截之前，或沒有所屬 session 的殘留量子膜），關門失敗後的 rollback 只能寫回「開門」，量子膜會變成 none；toggle 的 mutator 是 `boolean`，`CorridorPageManager` 也使用同一個 public 簽名，所以不改。`applyTarget` 會逐格寫回原本的量子膜。
- sealed 量子膜的碰撞是完整方塊，所以 `getSidesShape`（預設取碰撞）與 `isSolidBlock`（bedrock 預設的 `solidBlock` predicate 取 `isFullCube`）都和關門一樣：可以從艙外在它上面掛拉桿或火把（變成 passable 時掉落），也會傳導強充能。Controller 就在門板最上排中間那格的正上方，所以有人把強充能打進那一格時，PREPARE 的「已供電」可能隨 sealed／passable 切換。這兩項不在補充第 3 點的四項內，本 task 不改。
- sealed 量子膜的 `getAmbientOcclusionLightLevel` 為 0.2（碰撞為完整方塊），鄰格的 ambient occlusion 會和關門時一樣變暗；量子膜自己的模型已關閉 ambient occlusion。
- JUnit 環境中，mod 方塊的 state 不會自動建立 shape cache（server 在載入 data pack 後才呼叫 `Blocks.refreshShapeCache()`），依賴 `isSolid()`、`blocksMovement()` 的斷言要先建立 shape cache，否則讀到的值與 runtime 不同。

### 交給後續

- F3d：SOURCE fast path 以 `applyTarget(world, frame, DoorState.OPEN)` 改為一般開門，並依 `ApplyResult` 處理（fix round 1 更新）：NOT_LOADED 等之後的 tick 再試、不強制載入；DOOR_INCOMPLETE 不要每個 tick 重試、等結構恢復；ROLLED_BACK 可在之後的 tick 重試並去重；ROLLBACK_FAILED 已設 activationBlocked、已記 ERROR，重試去重。各值的完整說明在 `ChamberDoorService.ApplyResult.Outcome` 的 Javadoc。呼叫必須在 server thread。
- F4b：
  - 門的攔截要排在 `ChamberDoorService` 之前，量子膜門不要再走 `toggle`（見「觀察」第 1 條）。
  - sealed 量子膜以 `applyTarget(…, DoorState.sealed(facing))` 套用；量子膜軸不符是 caller 錯誤（丟例外）。
  - 「觀察」第 2 條的掛件與紅石傳導，請在 PREPARE 閘門的設計中判斷是否需要處理。
- F5：passable 以 `DoorState.passable(facing)`；通道結束與「沒有所屬 session 的 membrane 正規化」以 `DoorState.OPEN` 或 `DoorState.CLOSED`。`QBB.membrane` 把 `open=false`＋量子膜讀成 none，所以對這種格套用 `CLOSED` 會得到 UNCHANGED、不重寫；它的行為與關門相同，下一次開門時由 `withOpen` 正規化（fix round 1 的 G1 `stale*` 鎖住這兩點）。另外，UNCHANGED 不會解除 activationBlocked（解除需要一次 APPLIED）：前一次 rollback 失敗之後，門若剛好已是 target，正規化不會修復 Controller 的禁止啟動，F5 要決定是否另外處理。F5 在 load-sync 的 refresh 中正規化時，鄰接 chunk 可能還沒 FULL，會收到 NOT_LOADED，要排到之後的 tick 再試。
- F7：設計文件 `docs/quantum_superposition_chamber_design.md:164` 寫「開門後仍維持 empty outline／collision」，與量子膜的外框（可瞄準）及 sealed 的完整碰撞不一致，需要同步。
- 真 client 畫面（cutout mipped、兩個軸向、薄平面周圍沒有透視破洞、外框，以及關門遠景的 mipmap）本 task 沒有實機確認，列入 M5 的 required manual smoke（spec §18）或 F1b 類的真 client 驗證。

### Fix round 1（`88c220a`）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 2，其中 M-1 是「補充第 3 點」把 `shouldSuffocate`／`shouldBlockVision` 寫成 final 的錯誤理由）；code quality review Not approved（Critical 0、Important 1、Minor 5）。controller 指派 I-1、M-1～M-5 與 spec 的 M-1；不處理的三項已確認寫在「交給後續」（設計文件 :164 → F7、UNCHANGED 不解除 activationBlocked → F5、sealed 量子膜的掛件與強充能 → F4b）。開工 HEAD 是 `8513e09`。
- Commit `88c220a` `fix: refuse door applies on unloaded chunks and split rejection reasons`（parent `8513e09`）：`ChamberDoorService.java`、`WorldChamberDoorAdapter.java`、`QuantumSuperpositionClient.java`；測試修改 `MembraneStateTest.java`、`M5CollapseGameTests.java`。本小節由其後的 docs commit 加入，同一個 commit 也更正「補充第 3 點」的理由、在本節開頭補上 Review 行、在幾處加註 fix round 1 的改動，並在「交給後續」補上 F5、F7 兩項。
- 行號以 `88c220a` 為準。

| 項目 | 處理 |
| --- | --- |
| I-1 不強制載入 chunk | `WCDA:43` `apply` 依序檢查：server thread（`:45`）→ 量子膜軸（`CDS:95` `requireFits`，從核心抽出共用）→ `WCDA:58` `doorChunksLoaded`。門平面 local x 0–6、法線方向 local z −1～1 涵蓋的每個 chunk 都要 `getWorldChunk != null`（只查詢、不載入）。範圍比「門板＋Controller」多了門前後各一排：寫入門格時，原版的 neighbor update 與 shape update 以 `World.getBlockState`（`getChunk(FULL, create=true)`，會同步載入）讀相鄰六格，所以門平面剛好在 chunk 邊界上、門外那一排在另一個 chunk 時，那個 chunk 也必須 FULL。（fix round 2 再擴大為門板周圍水平 ±3，見「Fix round 2」。）任一不是 FULL 就回新的 `NOT_LOADED`：不讀不寫任何一格、不設 activationBlocked；回報的 activationBlocked 以不載入的方式讀 Controller。Javadoc：`CDS:49-52` 寫明前提，`Outcome.NOT_LOADED`（`CDS:210-214`）寫明 caller 等之後的 tick 再試、不強制載入、不設 activationBlocked。Controller 的 chunk 在範圍內，所以只要不是 NOT_LOADED，ROLLBACK_FAILED 就一定設得到 activationBlocked |
| I-1 的測試 | 新增 G4 `door_target_refuses_unloaded_chunks_without_forcing_them`（`m5_collapse_not_loaded`），沿用 `ChamberNoForceGameTests` 的手法（只讀，未修改）：遠處的 chunk 以 radius 0 的 ticket 維持 FULL，鄰接的 chunk 不是 FULL；fixture 的方塊直接寫進 chunk（`WorldChunk.setBlockState`），不觸發鄰格更新。兩個情境：A `straddle`，門板最左一欄（5 格）在東側未載入的 chunk；B `outsideRow`，門板與 Controller 都在已載入的 chunk，只有門外那一排在北側未載入的 chunk。斷言 NOT_LOADED、缺的 chunk 在呼叫前後都不是 FULL、已寫入的門格都沒被改。結束時還原被覆寫的方塊（先記下原狀態）並移除 ticket |
| M-1 cutout mipped | `QuantumSuperpositionClient.java:18` 改為 `RenderLayer.getCutoutMipped()`：仍屬 cutout 類，符合 spec §10.1 的字面，關門遠景保留 mipmap。註解改寫（首版的「畫法不變」不正確，cutout 不做 mipmap）。「關門遠景」已與量子膜畫面一起列入 manual smoke（「交給後續」） |
| M-2 兩項承諾的行為 | G1 新增兩段，四個朝向各跑一次。(4) `stale*`：門關上後，以原始寫入把一格改成 `open=false`＋sealed；`applyTarget(CLOSED)` 回 UNCHANGED、那一格不被重寫；`applyTarget(OPEN)` 回 APPLIED、25 格都是一般開門。(5) `offThread.rejected`：從另一個 thread 呼叫丟 `IllegalStateException`，門不變。呼叫以 `attempt` 包住，丟例外時 witness 記為 false，不中斷 test。mutation M-G4（`doorAt` 改讀原始 membrane）與 M-G5（拿掉 thread 檢查）都被抓到 |
| M-3 拆開 REJECTED | `Outcome`（`CDS:205-229`）改為 APPLIED、UNCHANGED、NOT_LOADED、DOOR_INCOMPLETE（門板不是完整 25 格 bulkhead，結構性：不要每個 tick 重試，等結構恢復，記錄去重）、ROLLED_BACK（寫入被拒、rollback 完成，可能是暫時性：之後的 tick 重試、去重、必要時退避）、ROLLBACK_FAILED（補上「之後一次 APPLIED 會解除、重試去重」）。每一值的 Javadoc 寫明 caller 的處理。JUnit 的兩個核心 test 改為斷言新值；G1 的缺格門改為 DOOR_INCOMPLETE；G2 新增 `refused.*`：`DoorWriteFault` 拒絕所有開門寫入時，關著的門套用一般開門回 ROLLED_BACK，門與 activationBlocked 都不變 |
| M-4 守門掃描 | `MembraneStateTest:472` 改寫。只比對 bulkhead 自己的屬性常數：`QuantumBulkheadBlock.OPEN／MEMBRANE`（可帶套件名稱），或以 `import static` 引入的 `OPEN／MEMBRANE`；不再比對 `Properties.OPEN` 與其他類別的裸 `OPEN`。是否為寫入，由它是不是 `with`／`withIfExists`／`cycle` 的參數判定（`writeReceiver` `:576`）。預設狀態的豁免改為「寫 OPEN，而且 receiver 去掉空白與換行後以 `QUANTUM_BULKHEAD.getDefaultState()` 結尾」，不依賴排版，並斷言恰好只有 `SessionEntranceAllocator` 一處。兩個切換寫入點改為取出 `new ChamberDoorService().toggle(…)` 的第二個參數（`toggleMutator` `:594`），去掉空白後與預期的 lambda 本體逐字比對，不再用 `contains` |
| M-5 建置在 try 之內 | G1、G2 先以 `fixtureFrame`（`M5CollapseGameTests:397`，與 `ChamberGameTestBuilder` 相同的錨點）算出 frame，建置放進 try，finally 清除同一個範圍，建置後以 `equals` 核對（`fixtureFrame` witness）。G3 先算好所有通道位置，建置或放水源失敗時以 `FluidPocket.clearAt`（`:455`）全部清掉再丟出。G4 的建置也在 try 之內 |
| spec reviewer 的 M-1 | 「補充第 3 點」的理由已更正：兩個方法不是 final；真正的原因是 `Block` 建構子固定以原版 `BlockState::new` 建立 state，mod 無法換成自己的子類別，而這兩個方法只評估建立 state 時拷貝的 Settings predicate |

測試：

- JUnit 數量不變（560；`MembraneStateTest` 維持 19 個）：兩個核心 test 改為斷言 DOOR_INCOMPLETE／ROLLED_BACK，守門掃描改寫。
- GameTest +1（159 → 160；`_windows` 維持 80；per-test Universe receipt 維持 38）：新增 G4；G1 多了 `stale*`、`offThread.rejected`、`fixtureFrame`；G2 多了 `refused.*`、`fixtureFrame`。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3b/fix1/`。`git diff 8513e09 88c220a -- <path>` 輸出的 SHA-256 前 8 碼（`fix1/diff-hashes.txt`）：`src` `17782852`、`src/main` `ddc24397`、`src/client` `7d5e59ab`、`src/test` `a113f75a`、`src/testmod` `cd4a3c5f`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED JUnit | `8513e09`；porcelain 為 3 個 ` M`（`ChamberDoorService.java`、`MembraneStateTest.java`、`M5CollapseGameTests.java`），逐檔 blob 在 `fix1/red-state.txt`。`ChamberDoorService.java` 是暫時的 RED adapter（`fix1/red-main-adapter.diff`，前 8 碼 `51fd2a29`）：只在 `Outcome` 加上 `NOT_LOADED`、`DOOR_INCOMPLETE`、`ROLLED_BACK` 三個常數讓測試能編譯，行為維持 `8513e09`（兩種拒絕仍回 REJECTED、不檢查 chunk） | focused `chamber.*`（`fix1/red-junit.log`、`red-junit-xml/`、`red-junit-breakdown.txt`） | 預期 FAIL：121 個中 2 個，`expected: <DOOR_INCOMPLETE> but was: <REJECTED>` 與 `expected: <ROLLED_BACK> but was: <REJECTED>`，都是 `AssertionFailedError`。改寫的守門掃描在 RED 就通過（`8513e09` 的程式本來就符合），以下表 M-J14～M-J19 證明 |
| RED GameTest | 同上 | GameTest＋onlyBatches 4 個 batch：`gate-f3b-fix1-red-gametest-c121ffee80a94f01bc2b5349e95e1e4c`（`fix1/red-gametest-summary.txt`） | 預期 FAIL：1/4（G3 PASS）。G4 失敗 5 個 witness：`straddle.notLoaded`、`straddle.missingStillNotFull`（`8513e09` 讀門格時把東側 chunk 同步載入到 FULL）、`outsideRow.notLoaded`、`outsideRow.missingStillNotFull`（寫入門格時 neighbor update 把北側 chunk 載入）、`outsideRow.nothingWritten`。G1 是四個朝向的 `incompleteRejected`，G2 是 `refused.rolledBack`。M-2 的 `stale*`、`offThread.rejected` 在 RED 就通過（`8513e09` 已有這兩項行為），以 M-G4、M-G5 證明。boundary oracle PASS，world exact restore，沒有 chunk 存檔失敗 |
| 開發確認（不作為證據） | GREEN 工作樹 | GameTest＋onlyBatches：`gate-f3b-fix1-dev-green-41faf92212234b298a39173727577f9b` | 4/4 PASS；只有 G2 預期的兩則 rollback ERROR |
| mutation（JUnit） | GREEN 工作樹，每個 mutation 單獨套用、只跑 `MembraneStateTest`，跑完還原並以 `git hash-object` 核對（`fix1/mutations.sh`、`mutations/summary.txt`、`M-J*.diff`、`M-J*.log`、`M-J*-TEST.xml`） | 6 個 | 下表；跑完 blob 與 GREEN 相同（`fix1/pre-mutation-blobs.txt`） |
| mutation（GameTest） | 同上（`fix1/gametest-mutations.sh`、`mutations/gametest-summary.txt`、`M-G*.diff`、`M-G*-gate.log`、`M-G*-witness.txt`） | GameTest＋onlyBatches：`gate-f3b-fix1-mut-m-g4-bcd7b017a8214f6290857ffb0bcd539b`、`-m-g5-1ad23eceed08414e9c5088a09d8297ef`、`-m-g6-c67a6a668b5447358d050e3c657f881f` | 下表；3 個 gate 的 boundary oracle PASS、world exact restore；跑完 blob 與 GREEN 相同（`fix1/green-state-blobs.txt`） |
| GREEN focused | 工作樹，blob 與 `88c220a` 相同 | `fix1/green-focused.log`、`green-focused-xml/` | 121，0 failure／error |
| GREEN Full（含全部 JUnit） | 同上 | `gate-f3b-fix1-green-full-5e807f3736ff4d55bb89d43198ac10ce`（`fix1/green-full-summary.txt`、`green-junit-all-xml/`） | PASS：default GameTest 160/160（`_windows` 80）；boundary oracle PASS；per-test receipt 38/38；world exact restore；JUnit 560，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；release 359 entries／312 classes、sources 210，entry 清單與首輪完全相同（`fix1/green-jar-delta.txt`；`Outcome` 的新常數與 `requireFits` 不產生新 class）；testmod 命中 0 |
| GREEN Main＋oracle | 同上 | `gate-f3b-fix1-green-main-1d9d52cc5f40446bb7215bc52870581e`（`fix1/green-main-gate.log`、`green-main-oracle.log`） | PASS：testmod 沒有載入，layer 2／3 命中 0，world keys exact，正常停止，exact restore |

| Mutation | 改動 | 結果 |
| --- | --- | --- |
| M-J14 | `CorridorPageManager.java:506` 改回直接寫 `QuantumBulkheadBlock.OPEN` | 守門掃描失敗（`506:OPEN`） |
| M-J15 | 同一行改為帶套件名稱的 `dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN` | 守門掃描失敗（首版的正則抓不到這種寫法） |
| M-J16 | `WorldChamberDoorAdapter` 的 mutator 改為 `withOpen(…, true)`（忽略 `open` 參數） | 守門掃描的 lambda 逐字比對失敗（首版的 `contains` 抓不到） |
| M-J17 | `SessionEntranceAllocator` 的預設狀態再接 `.with(QuantumBulkheadBlock.MEMBRANE, SEALED_X)` | 守門掃描失敗（`26:MEMBRANE`） |
| M-J18（正向） | 同一行拆成四行（`ModBlocks.QUANTUM_BULKHEAD`／`.getDefaultState()`／`.with(`／`QuantumBulkheadBlock.OPEN, frontOpen)`） | 仍通過（19/19）：豁免不依賴排版 |
| M-J19 | `CorridorPageManager` 加 `import static …QuantumBulkheadBlock.OPEN`，該行改為 `.with(OPEN,open)` | 守門掃描失敗（`507:OPEN`） |
| M-G4 | `doorAt` 改讀原始 membrane | G1 四個朝向的 `staleClosed.unchanged`、`staleOpened.normalized`（讀到 `open=false`＋sealed 時 `DoorState` 丟例外）；之後的 `offThread`、`axisGuard`、`incompleteRejected` 因門停在關閉狀態而連帶失敗 |
| M-G5 | 拿掉 server thread 檢查 | G1 四個朝向的 `offThread.rejected`（off-thread 的 `getWorldChunk` 一律回 null，所以得到 NOT_LOADED 而不是例外） |
| M-G6 | `doorChunksLoaded` 只看門平面（不含門前後一排） | G4 的 `outsideRow.notLoaded`、`outsideRow.missingStillNotFull`、`outsideRow.nothingWritten`；`straddle.*` 照常通過 |

- log（與首輪的 Full gate 比較，以 `/ERROR]`、`/WARN]` 行計；首輪「Gate 與證據」寫的「WARN 49」是以子字串計，同一個 run 以行計為 46）：ERROR 29 → 28，WARN 46 → 46。差異都在 M2 的 batch：`m2_pin_capacity` 的「走廊保留租約等待安全返還」ERROR 3 → 2、「返還保留 pending」WARN 2 → 1；`m2_task4_disconnect_io` 多一則「session 協調失敗」WARN，它的 stack 是 M2 注入的 DISCONNECT journal flush 失敗，經排程的 Controller 刷新浮出，沒有門的程式。這是 M2 測試在不同 run 之間的時序差異（T0 已記錄 `m2_pin_capacity` 的連坐）。F3b 的四個 test 沒有任何 `M5_COLLAPSE_ATTEMPT_THREW`，rollback ERROR 仍是 G2 與 M1 各兩則。
- 所有 gate 都在 commit 之前以工作樹執行；commit 時 5 個檔的 blob 與 gate 前記錄的逐一相同（`fix1/green-state-blobs.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Legacy、M3 與 M4 recovery，理由同首輪。

### Fix round 2（`f181203`）

- Review（fix round 1 的 re-review）：Approved（Critical 0、Important 0、新 Minor 2）。reviewer 也獨立確認 fix round 1 的 log 差異與 F3b 無關。controller 指派兩項新 Minor 在 F5 使用這個 API 之前修掉。開工 HEAD 是 `090efdc`。
- Commit `f181203` `fix: widen the door apply load check to the redstone neighborhood`（parent `090efdc`）：`ChamberDoorService.java`、`WorldChamberDoorAdapter.java`（只有檢查範圍與 Javadoc）；testmod `M5CollapseGameTests.java`（只有 G4）。本小節由其後的 docs commit 加入，同一個 commit 也在 fix round 1 的 I-1 列加註範圍已擴大。
- 行號以 `f181203` 為準。

| 項目 | 處理 |
| --- | --- |
| m-1 範圍與 Javadoc | `WCDA:64` `doorChunksLoaded` 改為門板 25 格向水平各方向外擴 `DOOR_NEIGHBORHOOD`＝3 格（`WCDA:16`），也就是門平面 local x −2～8、法線方向 local z −3～3，Controller 也在其中。這與專案先例 `ChamberRedstoneService.powerNeighborhoodLoaded` 把原版一層紅石讀取定為水平 ±3 的慣例相同。寫法改為取門板兩端欄的世界座標，再對世界 X、Z 各外擴 3，四個世界方向對應的 local 邊界依門的朝向而定。每個軸最多跨 2 個 chunk，最多查 4 個 chunk，成本不變。Javadoc（`WCDA:55-63`、`CDS:49-53`、`Outcome.NOT_LOADED`）改為描述實際保證：寫入門格本身、原版一層 neighbor／shape update，以及這些鄰格一層的紅石讀取（水平 ±3）都不會載入 chunk；更遠的連鎖反應（例如紅石沿線路一路傳遞）不在保證內。首版的「不會在 server thread 上同步載入 chunk」太絕對，已移除 |
| m-2 G4 的新情境 | G4（`M5CollapseGameTests:195`）的 `checkNotLoaded`（`:223`）一般化為可以用 ticket 維持多個 FULL chunk、可指定預期寫入的門格數。門一律朝北（local x 增加是往西，艙內 z 增加是往南），新增三個情境，每個情境都只有恰好落在 ±3 邊界上的那一欄或那一排在未載入的 chunk：C `diagonal`，外殼外 x=8 那一欄所在的西側 chunk 與艙內 z=+3 那一排所在的南側 chunk都已載入，只有西南的對角 chunk 未載入，它只被 x=8、z=+3 的角落涵蓋，所以同時涵蓋外殼欄、艙內那一排與對角 chunk；D `eastEdge`，只有 x=−2 那一欄在東側未載入的 chunk；E `northEdge`，只有門外 z=−3 那一排在北側未載入的 chunk。四個世界方向的 ±3 邊界各由一個情境鎖住：西、南由 C，東由 D，北由 E |

關於派工的「門平面在 chunk 的最後一排，艙內那一排（z=+1，或擴大後的 z=+3）」：若只讓 z=+1 落在未載入的 chunk，`090efdc` 的舊範圍（z −1～1）本來就會回 NOT_LOADED，RED 不會失敗。所以 C 讓門平面落在倒數第三排（z=+3 那一排剛好是下一個 chunk 的第一排），只有擴大後的邊界才涵蓋得到。

測試：JUnit 數量不變（560）；GameTest 數量不變（160），只有 G4 多了三個情境。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3b/fix2/`。`git diff 090efdc f181203 -- <path>` 輸出的 SHA-256 前 8 碼（`fix2/diff-hashes.txt`）：`src` `0d1e00f8`、`src/main` `8c157ff1`、`src/testmod` `82e5a43f`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED GameTest | `090efdc`；porcelain 只有 ` M src/testmod/java/dev/quantumchamber/gametest/M5CollapseGameTests.java`，`src/main` 與 HEAD 相同（`fix2/red-state.txt`）。本輪沒有新 API，不需要 adapter | GameTest＋onlyBatches `m5_collapse_not_loaded`：`gate-f3b-fix2-red-gametest-34eece90a7d64743a4283f1f3e656e34`（`fix2/red-gametest-summary.txt`） | 預期 FAIL：新的三個情境都失敗在目標 witness，`diagonal`、`eastEdge`、`northEdge` 的 `notLoaded`（舊範圍沒有涵蓋到缺的 chunk，套用照常進行，回 APPLIED）與 `nothingWritten`（門格被改成 sealed）；`straddle`、`outsideRow` 照常通過。缺的 chunk 在 RED 也沒有被載入（舊程式的寫入只讀到相鄰一格）。boundary oracle PASS，world exact restore |
| 開發確認（不作為證據） | GREEN 工作樹 | GameTest＋onlyBatches 3 個門的 batch：`gate-f3b-fix2-dev-green-1117bacf968f46698132a1c9cf35b00a` | 3/3 PASS |
| mutation（GameTest） | GREEN 工作樹，每個 mutation 單獨套用、只跑 G4，跑完還原並以 `git hash-object` 核對（`fix2/gametest-mutations.sh`、`mutations/gametest-summary.txt`、`M-G*.diff`、`M-G*-gate.log`、`M-G*-witness.txt`） | GameTest＋onlyBatches `m5_collapse_not_loaded`：`gate-f3b-fix2-mut-m-g7-c759ed85a9094d45a15d3bc3e25865ef`、`-m-g8-155e666877464823bd3582ad97fb7841`、`-m-g9-1355828daef74c02a2633147420f12ba`、`-m-g10-2c56575df0a74b15bca04cb32cd5f1ee`、`-m-g11-16368ec266c64c7fa54fbc0458dd776f` | 下表；5 個 gate 的 boundary oracle PASS、world exact restore；跑完 blob 與 GREEN 相同（`fix2/pre-mutation-blobs.txt`、`green-state-blobs.txt`） |
| GREEN focused | 工作樹，blob 與 `f181203` 相同 | `fix2/green-focused.log`、`green-focused-xml/` | 121，0 failure／error |
| GREEN Full（含全部 JUnit） | 同上 | `gate-f3b-fix2-green-full-d704d51a02e64fc8a99567331f0cc220`（`fix2/green-full-summary.txt`、`green-junit-all-xml/`） | PASS：default GameTest 160/160（`_windows` 80）；boundary oracle PASS；per-test receipt 38/38；world exact restore；JUnit 560，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；release 359 entries／312 classes、sources 210，entry 清單與 fix round 1 完全相同（`fix2/green-jar-delta.txt`）；testmod 命中 0 |

| Mutation | 改動 | 結果 |
| --- | --- | --- |
| M-G7 | `DOOR_NEIGHBORHOOD` 改為 1（縮回 fix round 1 的範圍：local x 0～6、z −1～1） | `diagonal`、`eastEdge`、`northEdge` 的 `notLoaded`、`nothingWritten` |
| M-G8 | 只有東側（maxX，門朝北時是 local x=−2）縮為 2 | 只有 `eastEdge.*` |
| M-G9 | 只有西側（minX，local x=8）縮為 2 | 只有 `diagonal.*` |
| M-G10 | 只有北側（minZ，門外 z=−3）縮為 2 | 只有 `northEdge.*` |
| M-G11 | 只有南側（maxZ，艙內 z=+3）縮為 2 | 只有 `diagonal.*` |

- log（與 fix round 1 的 Full gate 比較，以 `/ERROR]`、`/WARN]` 行計）：ERROR 28 → 28，WARN 46 → 45。唯一的差異是 `m2_task4_disconnect_io` 的 WARN 3 → 2，就是 fix round 1 記錄、會隨時序出現的那一則「session 協調失敗」，這次沒有出現；所有 `m5_collapse_*` batch 都沒有變化。rollback ERROR 仍是 G2 與 M1 各兩則，沒有 `M5_COLLAPSE_ATTEMPT_THREW`。
- 所有 gate 都在 commit 之前以工作樹執行；commit 時 3 個檔的 blob 與 gate 前記錄的逐一相同（`fix2/green-state-blobs.txt`、`pre-commit-hashes.txt`、`commit-blobs.txt`）。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Main＋oracle：只改 main 的檢查範圍與 Javadoc，以及 testmod 的 G4，沒有動 client 程式、資源、`SERVER_STARTED` listener 或 mixin；client 的最後一次改動（fix round 1 的 cutout mipped）已由 `gate-f3b-fix1-green-main-1d9d52cc5f40446bb7215bc52870581e` 驗過。也不跑 Legacy、M3 與 M4 recovery，理由同首輪。

## F3c：M5 GameTest 隔離 run

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `e11b341` `test: isolate universe-touching GameTests in their own run`（parent `4098d0c`）：`build.gradle`（只新增 run `gameTestM5`）、`.github/workflows/build.yml`；`scripts/verification/` 的 `gates.ps1`、`run-all.ps1`、`ci-rule-check.ps1`、`README.md`；testmod 修改 `M4GameTestBoundaryProbe.java`、`mixin/M4LegacyGameTestFilterMixin.java`，新增 `M5UniverseGameTests.java`；`src/testmod/resources/fabric.mod.json` 只登記 `fabric-gametest` entrypoint。沒有 production（`src/main`、`src/client`）與 JUnit 的變更。
  - 本段的 docs commit 接在 `e11b341` 之後。
- Code range `4098d0c..e11b341`。派工時 HEAD 是 `c19a399`；之後 controller 的兩個 docs commit（`909f033`、`4098d0c`）只改 AGENTS.md 與本 note 的開頭、F1b，不影響本 task。
- 範圍是 plan 決策 6 與 Review Focus 8：會配置或物化 Universe、或會留下 M5 record 的 GameTest，放進新的 `gameTestM5` run（獨立的 fresh 世界 `run/gametest-m5`），default run 的 boundary oracle 維持 PASS。
- 行號以 `e11b341` 為準。`FLT` 指 `src/testmod/java/dev/quantumchamber/gametest/mixin/M4LegacyGameTestFilterMixin.java`，`M5U` 指 `src/testmod/java/dev/quantumchamber/gametest/M5UniverseGameTests.java`，`GT`、`RA`、`CRC` 指 `scripts/verification/` 的 `gates.ps1`、`run-all.ps1`、`ci-rule-check.ps1`，`YML` 指 `.github/workflows/build.yml`。
- Review：首輪 spec review ✅（Critical 0、Important 0、Minor 2）；code quality review Approved（Critical 0、Important 0、Minor 7）。controller 指派全部在 fix round 1 處理，見本節最後的「Fix round 1」；fix round 1 的 re-review 為 Not approved（Important 1），見「Fix round 2」。本節其餘內容描述 `e11b341`（當時 M5 run 只有 sanity 一個 test）。

### plan 逐項對照

| plan（Task F3c） | 程式位置 | 證據 |
| --- | --- | --- |
| `build.gradle` 新增 run `gameTestM5`：`server()`、`source sourceSets.testmod`、runDir `run/gametest-m5`、`-Dfabric-api.gametest`、`-Dquantumchamber.gametest.m5Only=true`、report file `gametest-m5-results.xml` | `build.gradle:126-135`（loom 的 `runs` 內，緊接 `gameTestLegacy`）；Loom 產生的 task 是 `runGameTestM5` | GREEN M5 gate 的指令與 JVM commandline（`GT:236` 也核對 m5Only） |
| filter：`m5Only` 時只保留前綴 `m5_universe`；default run 排除；legacy run 不變 | `FLT:20-27`：legacy 仍只比對 `m4_legacy_runtime`；另加 legacyOnly 與 m5Only 同時設定時丟例外 | 三個 GREEN gate 的 `scope.json`；M-G1、M-S1、M-S2 |
| boundary probe：`m5Only` 時不註冊 | `M4GameTestBoundaryProbe.java:27` | GREEN M5 沒有 `m4-boundary-*.json`；M-B |
| 新增 `M5UniverseGameTests`，sanity test 只做配置、改 DISABLED、checked flush，不物化；斷言 default run 不會執行它 | sanity `M5U:45`；`begin` `M5U:65`（第一個動作；`:67` 不在 M5 run 時在碰 catalog 之前失敗）；`complete` `M5U:82`；`converged` `M5U:100` | RED；GREEN M5 1/1；M-G1、M-D、M-N、M-H、M-P |
| `fabric.mod.json` 登記 entrypoint | `fabric-gametest` 陣列的最後一項 | GREEN M5 XML 有這個 case |
| `gates.ps1 -Mode M5GameTest`：fresh world move／restore 與 default 相同；不檢查 M4 boundary oracle，改檢查每個 M5 test 自己斷言的 catalog 增量 | `GT:17` ValidateSet；`GT:255` `Fresh-Game` 改為以 scope（default／legacy／m5）參數化，三個 run 共用同一份 move／restore；M5 分支 `GT:271-274`；`GT:235` `Assert-M5Run`；`GT:339` | GREEN M5 的 `world-restore.json`（exact）、`m5-oracle.json`；M-B、M-D、M-F |
| `run-all.ps1`：新增步驟與 summary 數字；CI 規則呼叫加上 M5 XML | 步驟 `RA:83`（Legacy 之後）；CI 呼叫 `RA:96-100`；summary 加上 M5 gate 狀態、restore、指令、XML、`RA:125` 三個 run 的 batch 範圍、`RA:132` M5 oracle、`RA:217` CI 結果的 M5 XML hash、`RA:227` `m5=` | run-all（「Gate 與證據」最後一列） |
| `ci-rule-check.ps1`：新增 M5 XML 參數，fixture 同時寫入兩份 XML | 必填 `-M5GameTestXml`（`CRC:20`），`CRC:71-72` 讀入、`CRC:106-107` 寫入 fixture；另加 `-MutationXml`（`CRC:25`）與 `-SourceMutation rename-windows`（`CRC:85-91`） | Step 3 矩陣 |
| `README.md` | 檔案表、個別執行、mutation 說明、Evidence 位置、預期結果（改為 M5 F3c 的數字，保留 M1–M4 收尾時的數字一句）、來源對照 | — |
| `build.yml`：兩個 job 都加跑 `runGameTestM5`；`_windows` 宣告規則同步排除與納入 | Ubuntu：step `YML:24-25`、檢查 `YML:41-62`；Windows：step `YML:95-96`、讀 M5 XML `YML:112-113`、規則 1／2／3 `YML:194-262`；兩個 artifact 上傳加上 M5 XML 與 `run/gametest-m5/logs/latest.log` | Step 3 矩陣 |
| Step 1 RED／Step 2 GREEN／Step 3 CI／Step 4 run-all／Step 5 commit | 見「Gate 與證據」；commit 見上方 | — |

### controller 補充第 1–5 點

- **第 1 點（catalog 怎麼配置）**：照 plan，直接用 `UniverseRegistryState`：`allocateOverworld` → `changeAvailability(DISABLED)` → `flush`（`M5U:51-55`），不走 `CollapseAuthorities.allocateOrVerifyCatalog`。理由：
  - sanity test 要證明的是「M5 run 可以改 catalog，而且這個改動被隔離、被逐 test 核對」，不需要 allocation ledger 與 NEW 的語意。
  - `allocateOrVerifyCatalog` 只配置 `EAGER_ENABLED`；改 DISABLED 仍要直接呼叫 catalog，而且繞過 allocation ledger 的 M5 配置本來就不是正式路徑（RESOLVE 在 F4a）。
  - ordinal 取 flushed 的最大 ordinal＋1，與 `allocateOrVerifyCatalog` 的規則相同，之後同一個 M5 世界中的 NEW 配置不會撞號。UUID 固定為 `50000000-0000-4000-8000-0000000f3c01`。
  - 初始化閘門：`begin` 斷言 `CollapseAuthorities.get(server).initialized()`。GREEN M5 的 log 有「M5 authority 初始化完成：allocation=CREATED（0 筆），projection=CREATED（0 筆）」，證實 GameTest server 開服時已初始化。
- **第 2 點（前綴矩陣）**
  - 規則在 `FLT:23-27`：default run 排除 `m4_legacy_runtime` 與前綴 `m5_universe`；M5 run 只保留前綴 `m5_universe`；legacy run 只保留 `m4_legacy_runtime`（不變）；`m5_collapse_`、`m5_f3a_` 都留在 default run。
  - 以腳本鎖住，沒有用 JUnit。理由：`test` source set 的 classpath 沒有 testmod 的 class（`build.gradle:146-149` 只加 clientProbe 的 class，註解也寫明不帶 testmod）；filter 是 Mixin class，不能直接呼叫；把判斷搬到 JUnit 看得到的地方，需要新的 testmod 檔或改 test classpath，都不在允許路徑內。
  - 腳本：`GT:194` `Batch-Scope` 是同一份規則；`GT:197` `Declared-GameTests` 掃描 testmod 全部 162 個 `@GameTest` 宣告（每個檔的 `@GameTest` 數必須等於可解析的宣告數，否則視為格式漂移而失敗）；`GT:215` `Assert-Scope` 要求每個 GameTest run 的 XML testcase 集合恰好等於屬於該 run 的宣告（以 onlyBatches 取證時再取交集），結果寫入 `scope.json`。三個 GameTest gate 都會跑（`GT:273`、`GT:278`），`RA:125` 再確認完整 suite 的數量（default 160，其中 `m5_collapse_` 4、`m5_f3a_` 3；legacy 1；M5 1）。
  - 這比 JUnit 多驗了一層：核對的是真實 filter 在真實 run 的結果。M-S1（default 另外排除 `m5_collapse_`）、M-S2（M5 run 多收一個 default batch）、M-G1（default 不再排除 `m5_universe`）都被抓到。legacy 另有既有的「恰好 1 個」檢查。
- **第 3 點（CI）**
  - 兩個 job 都在既有的 GameTest step 之後加一個 `runGameTestM5` step。
  - Windows step：規則 1（`_windows` 案例不得 failure／error／skipped）涵蓋兩份 XML；規則 2 依 annotation 分流，batch 前綴 `m5_universe` 的 `_windows` 宣告要求出現在 M5 XML（納入），不再要求出現在 default XML（排除）；新增規則 3：每個 `m5_universe` 宣告（不論名稱）都必須出現在 M5 XML 並實際通過，嚴格掃描與寬鬆掃描（`batchId="m5_universe` 字串）數量不一致即失敗。
  - Ubuntu step：名稱不以 `_windows` 結尾的 `m5_universe` 宣告都必須出現在 M5 XML 並實際通過；`_windows` 的不列入；同樣的嚴格／寬鬆掃描核對。
  - 目前 M5 run 沒有 `_windows` 案例，所以以 `ci-rule-check.ps1 -SourceMutation rename-windows`（fixture 中把 sanity test 與它的 testcase 一起改名為 `…_windows`）證明納入與排除：Windows 在該案例 skip 或缺席時 FAIL，Ubuntu 照常 PASS（Step 3 矩陣的 rename 列）。
  - F2a～F3b 新增的 `_windows` test 沒有列入 `build.yml` 的明列清單，依派工交給 F6。`ci-rule-check` 對兩個 step 都 PASS（通則涵蓋它們），所以沒有停下。實際清單見「交給後續」，數量與派工寫的不完全相同。
- **第 4 點（summary）**：`SUMMARY` 在 legacy 之後加 `m5=1/1`；`summary.json` 加上 M5 的數量、XML hash、三個 run 的 batch 範圍與逐 test 的 catalog 增量。README 的預期表加上「M5 GameTest」與「batch 範圍」兩列，其他數字同步為目前的值。
- **第 5 點（RED root）**：保留 `gate-f3c-red-boundary-51306ca253f641d7a5d6bb5a7f1fa91b`。

### 測試

- JUnit：不變（560）。
- default GameTest：不變（160，`_windows` 80，per-test Universe receipt 38）。sanity test 不在 default XML（`scope.json` 核對）。
- M5 GameTest（新 run）：1 個，`m5universegametests.sanity_allocates_one_disabled_record_without_materializing`（batch `m5_universe_sanity`）：
  - `begin`：不在 M5 run 時失敗；baseline 不可重複；M5 authority 已初始化；開始時 catalog 與四個權威都已收斂（前一個 test 違反衛生規則時在這裡被抓到）；記下 catalog record 的快照，log `M5_UNIVERSE_BEGIN`。
  - 本體：配置一筆、改 DISABLED、checked flush；斷言 flushed 的 record 是 DISABLED、world 沒有被建立、world keys 不變。
  - `complete(context, 1)`：catalog 恰好多 1 筆、開始時已有的 record 一筆都沒有改變、四個權威都已收斂（plan 的衛生規則），log 一行 `M5_UNIVERSE_CATALOG_DELTA … status=PASS|FAIL`，通過才 complete。
- `gates.ps1 -Mode M5GameTest` 的 oracle（`GT:235`）：JVM 帶 m5Only、沒有 boundary receipt、M5 test 都宣告在 `M5UniverseGameTests` 而且各自一個 batch、每個 test 恰有一行 PASS 的增量；依 log 順序串接，fresh 世界從 0 筆開始，前一個 test 的 after 等於下一個的 before（catalog 只在 test 自己斷言的範圍內改變）。三個 GameTest run 另外都核對 XML 是本次 run 寫出的（`GT:269`）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3c/`；gate root 在 `.superpowers/verification/`。`git diff 4098d0c e11b341 -- <path>` 輸出的 SHA-256 前 8 碼（`f3c/diff-hashes.txt`）：`src` `56ba6b79`（全部在 `src/testmod`）、`scripts/verification` `8889b379`、`build.gradle` `41db6076`、`.github` `055141b9`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED | `4098d0c`；porcelain 為 ` M src/testmod/resources/fabric.mod.json` 與 `?? src/testmod/java/dev/quantumchamber/gametest/M5UniverseGameTests.java`，逐檔 blob 在 `f3c/red-state.txt`。test 檔是 RED adapter：與 commit 相同，只少了 `begin` 的 run guard 兩行（`f3c/red-adapter.diff`，SHA-256 前 8 碼 `7660e3a7`）。有 guard 的話，test 在 default run 會先在 guard 失敗、碰不到 catalog，boundary oracle 就不會判 FAIL。filter、boundary probe、`build.gradle` 與 harness 都是 HEAD | GameTest＋onlyBatches `m5_universe_sanity`：`gate-f3c-red-boundary-51306ca253f641d7a5d6bb5a7f1fa91b`（`f3c/red-boundary-gate.log`） | 預期 FAIL：sanity test 在 default run 1/1 通過（Gradle exit 0），boundary receipt 為 FAIL：before `catalogExists=false`、0 筆 → after `catalogExists=true`、1 筆，world keys 不變。gate 以「GameTest Universe boundary oracle 缺失或 FAIL」失敗。world exact restore |
| GREEN M5 | 工作樹，blob 與 `e11b341` 相同（`f3c/green-state-blobs.txt`、`commit-blobs.txt`） | `gate-f3c-green-m5-81d26f3b1a354edaa7e991251418b663`（`f3c/green-m5-gate.log`） | PASS：1/1；沒有 `m4-boundary-*.json`；`scope.json` PASS（宣告 default 160／legacy 1／M5 1，M5 的 expected＝actual＝1）；`m5-oracle.json` PASS（0 → 1，expected 1、converged、preserved）；world exact restore（`run/gametest-m5/world` 原本不存在） |
| GREEN Full | 同上 | `gate-f3c-green-full-94cb96f3315746319ec47b8e7fcdc59d`（`f3c/green-full-summary.txt`） | PASS：default 160/160（`_windows` 80），0 failure／error／skipped；boundary PASS（唯一的 catalog 變更仍是 M3 fixture，0 → 1）；`scope.json` PASS（160，XML 沒有任何 `m5universegametests` case）；per-test receipt 38/38；JUnit 560，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；release 359 entries／312 classes、sources 210，entry 清單與 F3b fix round 2 完全相同（`f3c/green-jar-delta.txt`）；testmod 命中 0；world exact restore |
| GREEN Legacy | 同上 | `gate-f3c-green-legacy-edebd635b324456e9ae7fdbf789965dc`（`f3c/green-legacy-gate.log`） | PASS：1/1，boundary PASS，`scope.json` PASS（1） |
| CI 規則（Step 3） | 同上；XML 取自 GREEN Full 與 GREEN M5（`f3c/ci-matrix.ps1`、`ci-matrix.txt`） | 16 個 `ci-f3c-ci-*` root | 16 列全部符合預期（下表） |
| gate mutation | 同上；每個 mutation 單獨套用，跑完還原並以 `git hash-object` 核對（`f3c/gate-mutations.sh`、`mutations/summary.txt`、`M-*.diff`、`M-*-gate.log`、`roots-summary.txt`） | 9 個 `gate-f3c-mut-*` root（下表） | 全部在目標檢查失敗；跑完 blob 與 GREEN 相同（`f3c/post-mutation-blobs.txt`） |
| run-all（Step 4） | `e11b341`，`STATUS` 為空（`f3c/run-all-console.log`） | `run-all-4c38c370ca924c8d9641474109b00414` | `END ALL exit=0 HEAD=e11b341c544f4d0f8a1d03472176e33a1f346d42`，約 15 分鐘（Full 約 4 分、M5 GameTest 23 秒、M3 約 3 分、M4 recovery 約 6.7 分）。`SUMMARY default=160/160 windows=80 legacy=1/1 m5=1/1 perTest=38+1 junit=560 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=359entries/312classes sources=210 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。`STATUS` 為空；本目錄每個檔的 `HARNESS` 雜湊都等於 `e11b341` 的同一檔（`git hash-object` 相符），依 README 的採計規則以 C＝`e11b341` 採計。`summary.json` 的 batch 範圍為 default 160、legacy 1、M5 1，M5 增量 0 → 1 |

CI 規則矩陣（Windows＝「確認 Windows 原生案例確實執行」，Ubuntu＝「確認非 Windows 跨平台案例確實執行」；sanity＝`m5universegametests.sanity_allocates_one_disabled_record_without_materializing`）：

| 列 | 改動（只在 fixture） | Windows | Ubuntu |
| --- | --- | --- | --- |
| green | 無 | PASS（`_windows` 宣告 80、XML 80；M5 宣告 1、M5 XML 1） | PASS |
| drop-m5 | M5 XML 刪掉 sanity | FAIL「必要 M5 GameTest 未實際通過」 | FAIL（同） |
| skip-m5 | M5 XML 的 sanity 加 `skipped` | FAIL（同） | FAIL（同） |
| interpose-m5 | sanity 的 `@GameTest` 與方法之間插入另一個 annotation | FAIL「M5 GameTest 宣告掃描不一致：嚴格 0、寬鬆 1」 | FAIL（同） |
| rename | sanity 與它的 testcase 改名為 `…_windows` | PASS（M5 宣告 1，其中 `_windows` 1；不要求出現在 default XML） | PASS |
| rename-skip | 改名後在 M5 XML 加 `skipped` | FAIL（`_windows` 未實際通過；M5 未實際通過） | PASS（`_windows` 不列入） |
| rename-drop | 改名後從 M5 XML 刪掉 | FAIL（M5 `_windows` 缺席；M5 未實際通過） | PASS |
| drop-default | default XML 刪掉既有的必要案例（Windows：`native_selection_all_25_cells_first_wins_and_measured_freeze_windows`；Ubuntu：`unsupported_checkpoint_platform_rejects_start_without_residue`） | FAIL | FAIL |

gate mutation（M-G1、M-S1 用 `-Mode GameTest`，其餘用 `-Mode M5GameTest`）：

| Mutation | 改動 | Root | 結果（目標檢查） |
| --- | --- | --- | --- |
| M-G1 | `FLT` 的 default 分支不再排除 `m5_universe`；onlyBatches `m5_universe_sanity` | `gate-f3c-mut-m-g1-3a248b9a0c8f4f2fbef0dd9a035b3ebe` | sanity 在 run guard 失敗（「M5 Universe GameTest 只能在 gameTestM5 run 執行」），Gradle exit 1；boundary PASS（0 → 0，catalog 沒被碰） |
| M-S1 | `FLT` 的 default 分支另外排除 `m5_collapse_` | `gate-f3c-mut-m-s1-75eb2c374fc14460aad820fabf9c8540` | 156/156 通過、boundary PASS，`scope.json` FAIL：缺 4 個 `m5collapsegametests` case |
| M-S2 | `FLT` 的 M5 分支多收 `m5_collapse_fluid` | `gate-f3c-mut-m-s2-62853c59377e4350888b6eecf940dba3` | 2/2 通過，`scope.json` FAIL：多 `m5collapsegametests.every_bulkhead_state_holds_back_fluids` |
| M-B | 拿掉 `M4GameTestBoundaryProbe` 的 m5Only 略過 | `gate-f3c-mut-m-b-b6ae64d0b1db46848f63f4056b1489f0` | 1/1 通過、scope PASS，gate FAIL「M5 run 不得留下 M4 boundary receipt」（receipt 本身 FAIL，0 → 1） |
| M-D | sanity 在 `begin` 之前先配置並寫出一筆 | `gate-f3c-mut-m-d-a11ff99d63d14daa9ae784e6a87659a9` | test 自己量到的增量仍是 1、1/1 通過，gate 的串接核對 FAIL「catalog 在 test 自己斷言的範圍之外改變：before=1，預期 0」 |
| M-N | test 內多配置一筆 | `gate-f3c-mut-m-n-07cd29a9f70d46ea8f9b210fc682491c` | `complete` 失敗：expected=1 actual=2（增量行 status=FAIL） |
| M-H | flush 之後再改一次 availability、不寫出 | `gate-f3c-mut-m-h-389994c63aaf41b8974debd2a05ee7aa` | `complete` 失敗：converged=false |
| M-P | `begin` 之前已有一筆，test 內把它改成 EAGER_ENABLED 並寫出 | `gate-f3c-mut-m-p-33ff51dba3ce48b681b4b7b139cbebe3` | `complete` 失敗：preserved=false |
| M-F | `build.gradle` 的 M5 report file 改名（`build/gametest-m5-results.xml` 留著前一次的舊檔） | `gate-f3c-mut-m-f-5602bd3b24004397837a531d396c7659` | 1/1 通過，gate FAIL「GameTest XML 不是本次 run 寫出的」；舊檔正是 M-P 失敗的那一份，沒被當成本次結果 |

- log（以 `/ERROR]`、`/WARN]` 行計）：GREEN Full ERROR 28、WARN 45，與 F3b fix round 2 的 Full gate 相同。GREEN M5 只有新 run 目錄的兩則（`server.properties` 讀取失敗、`eula.txt`），與 T0 的 #1、#2 同類。GREEN Legacy 0／0。
- RED 之外的 gate（GREEN、CI 矩陣、mutation）都在 commit 之前以工作樹執行；commit 時 10 個檔的 blob 與 gate 前記錄的逐一相同。run-all 在 commit 之後執行。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

### 偏離 plan／spec 字面之處

- **Step 4 在 commit 之後執行**：plan 的順序是 run-all 全綠之後才 commit。README 的採計規則要求 run 的 `STATUS` 沒有 `src/`、build 檔或 `.github/` 的變更，所以比照 F1a，commit 之前以工作樹跑 Full、M5、Legacy、CI 矩陣與 mutation，commit 之後才跑 run-all。
- **RED 用 adapter**：RED 的 test 少了 run guard（見上表），理由同上表。
- **前綴矩陣以腳本鎖住，不是 JUnit**：見「補充第 2 點」。
- **plan 沒寫、但一併加上的檢查**：
  - 三個 GameTest gate 都核對 XML 是本次 run 寫出的，以及 batch 範圍（不只 M5 run）。
  - M5 gate 另外核對 M5 test 都在 `M5UniverseGameTests`、各自一個 batch，以及增量的串接。
  - `begin` 另外斷言 authority 已初始化、開始時已收斂；`complete` 另外斷言既有 record 不變。
  - `ci-rule-check.ps1` 加上 `-MutationXml` 與 `rename-windows`，用來證明 M5 `_windows` 的納入與排除。
  - CI 的兩個 artifact 上傳加上 M5 XML 與 log。
  - filter 拒絕 legacyOnly 與 m5Only 同時設定。
- **sanity 的 ordinal**：取 flushed 最大值＋1，不是 M3 fixture 的固定 8（見「補充第 1 點」）。

### 觀察（未修改）

- `run/gametest-m5` 是新目錄，第一次 run 會記錄 `server.properties` 與 `eula.txt` 的讀取失敗（T0 #1、#2 同類），之後的 run 只要這個目錄還在就不會再出現。
- README `:30` 寫「約 14 分鐘」，本次 run-all 約 15 分鐘（多了 M5 GameTest 約 23 秒；Full、M3、M4 recovery 也各比 README 寫的長 0.2～0.5 分鐘）；差距小，沒有另外改。
- M5 run 的 world 每次 gate 都是 fresh，但同一次 run 中的 M5 test 共用同一個世界；之後的 test 要依「衛生規則」收斂後才 complete，`begin` 會擋下前一個 test 留下的未收斂狀態。

### 交給後續

- F4a 起，會配置或物化 Universe、或會留下 M5 record 的 GameTest：
  - 放進 `M5UniverseGameTests`，`batchId="m5_universe_<名稱>"`，每個 test 一個 batch（M5 gate 會檢查）。
  - 第一個動作呼叫 `begin(context, "<方法名稱>")`，最後在收斂之後呼叫 `complete(context, <預期的 catalog 增量>)`；名稱必須等於方法名稱，gate 以它對應 XML 與增量行。
  - sanity test 直接改 catalog，只是隔離 run 的自我檢查；正式配置與物化要走 lifecycle 與 `CollapseAuthorities`。
  - M5 gate 支援以 JAVA_TOOL_OPTIONS 的 onlyBatches 只跑子集（`scope.json` 記錄 onlyBatches）。
- F6：`build.yml` 的 `_windows` 明列清單。M5 期間新增、目前不在任何明列清單中的 test（都由通則涵蓋，兩個 step 目前 PASS）：
  - `_windows`：H2 `native_measured_checkpoint_ignores_unrelated_chunk_failure_windows`；H3 `native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows`、`native_start_double_fault_residue_converges_and_releases_chamber_windows`、`native_start_cancel_fault_quarantines_unchecked_initial_at_once_windows`、`native_quarantined_selection_converges_to_checked_returning_windows`；H4 `native_frozen_movement_gets_throttled_position_corrections_windows`（在 `M2CorridorGameTests`）；F3a `native_collapsing_buff_lapse_returns_through_the_retained_path_windows`。派工寫「H3 3 個」，實際是 4 個，另有 H4 的 1 個。
  - 跨平台（Ubuntu 的明列清單也沒有）：H3 `unchecked_residue_is_quarantined_and_removed_without_pausing_recovery`；F3a `collapse_states_derive_presence_and_never_return_through_reconcile`、`collapse_states_attach_without_returning_and_lookup_only_occupying_sessions`；F3b 的 4 個 `m5collapsegametests`；F3c 的 M5 sanity（M5 run，由新規則 3 與 Ubuntu 的 M5 規則要求）。
  - M2 時期也有許多 `_windows` test 不在明列清單（例如 `m2_task4_*`、`m2_task7_*`、`m2_task8_*`），屬既有狀態，不是 M5 新增。
- F6：`m5-collapse-probe.ps1` 納入 `run-all.ps1` 時，沿用本 task 的 summary 寫法（gate 狀態、restore、數量、oracle）。
- F7：`scripts/verification/README.md` 的「還沒在沒有 `.superpowers/` 的 clean clone 上實跑」一句（本 task 之後在 `:69`）仍待同步，已在 F7 待辦中，本 task 沒有動它。

### Fix round 1（`5307407`）

- Review（首輪）：spec review ✅（Critical 0、Important 0、Minor 2）；code quality review Approved（Critical 0、Important 0、Minor 7）。這些項目大多在 F3c 的檔案裡，F4a 改不到，但 F4a 開始物化 Universe 時一定會用到，所以 controller 指派本輪全部處理：S1、S2、Q1～Q7 與一個 Nit。開工 HEAD 是 `2ee8539`。
- Commit `5307407` `test: harden the M5 GameTest run and document adding universe tests`（parent `2ee8539`）：`.github/workflows/build.yml`；`scripts/verification/` 的 `gates.ps1`、`run-all.ps1`、`README.md`；testmod `M4GameTestBoundaryProbe.java`、`M5UniverseGameTests.java`。`build.gradle`、`ci-rule-check.ps1`、filter mixin、`fabric.mod.json` 沒有改。本小節由其後的 docs commit 加入。
- Code range `2ee8539..5307407`。
- 行號以 `5307407` 為準，簡稱同本節開頭（`M5U`、`GT`、`RA`、`YML`）。

| 項目 | 處理 |
| --- | --- |
| S1 suite 結束時的 catalog | 擇「testmod 寫 log 行」，不讀 `world-fresh` 的 catalog。理由：catalog 檔是 gzip 壓縮的 NBT，PowerShell 要另寫 NBT 解析；testmod 手上已有解析好的 state，而且 `SERVER_STOPPED` 在原生存檔之後，log 的內容就是磁碟上的內容（`converged=true` 證實 records 等於 flushed）。`M5U:124` `registerSuiteEnd` 由 `M4GameTestBoundaryProbe.java:29` 在 M5 run 呼叫（取代 boundary probe），`SERVER_STOPPED` 時寫 `M5_UNIVERSE_SUITE_END records=… digest=… converged=… unfinished=[…]`。digest（`M5U:236`）是依 UUID 排序的「UUID＝record NBT」的 SHA-256，所以只改 availability、筆數不變的改動也抓得到。增量行加上 `beforeDigest`／`afterDigest`；gate（`GT:254-266`）依序串接筆數與 digest，並要求恰一行 SUITE_END、筆數與 digest 等於最後一個 test 的 after、`converged=true`、沒有還沒通過的 test。`m5-oracle.json` 多了 `suiteEnd`，`RA:134` 也核對它 |
| S2 README 耗時 | `README.md:30` 改為約 15 分鐘 |
| Q1 可重試的 complete 與收斂 helper | `complete`（`M5U:160`）委派 `finish`（`M5U:192`）：未通過時丟 GameTest 斷言例外，baseline 保留、不寫增量行，狀態改變時才記一行 `M5_UNIVERSE_CATALOG_UNMET`（重試不洗版）；通過時才移除 baseline（`:210`）、寫一行 PASS。新增 `awaitConvergedThenComplete(context, delta, maxTicks)`（`M5U:170`）：每一輪對四個權威呼叫 `CollapseAuthorities.convergeIfNeeded`（`convergeAll` `M5U:216`；catalog 是 checked flush），收斂之後以最終判定完成；第一輪在呼叫當下，之後以 `runAtTick` 預先排好每個 tick 一輪（不在 `runAtTick` 的 callback 裡再排程，避開原版在迭代中改 map；fix round 2 更正：helper 本身在 callback 內被呼叫時，這樣仍會一次插入多項，改用 TimedTaskRunner，見「Fix round 2」I1），收斂後條件不符或逾時都寫一行 `status=FAIL` 並讓 test 失敗。`begin`（`M5U:140`）失敗時列出還沒通過的 test（`unfinished` `M5U:248`）。class Javadoc（`M5U:30-49`）與各方法的 Javadoc 寫明用法；sanity 改以 `awaitConvergedThenComplete(context, 1, 20)` 結尾示範。新增兩個 harness test 鎖住行為：`harness_complete_retries_without_losing_the_baseline`（`M5U:88`，batch `m5_universe_harness_retry`：catalog 的變更 5 tick 後才寫出，以 `addInstantFinalTask` 重試 `complete`）與 `harness_await_converges_an_unflushed_catalog_change`（`M5U:109`，batch `m5_universe_harness_await`：變更不自行寫出，由 helper 收斂） |
| Q2 CI timeout | 四個 GameTest step 都加 `timeout-minutes`：Ubuntu 的 default 與 M5、Windows 的 M5 為 20（`YML:23`、`:28`、`:105`）；Windows「原生建置與 GameTest」含 clean build 與全部 JUnit，2026-10-01 run `36804549120` 這一步約 9 分鐘，設為 30（`YML:100`） |
| Q3 default 失敗時仍跑 M5 | 兩個 M5 step 加 `if: ${{ !cancelled() }}`（`YML:27`、`:104`）。job 仍是紅燈：GitHub Actions 中只要有一個 step 失敗（沒有 `continue-on-error`），job 的結論就是 failure；`!cancelled()` 只讓 M5 step 照跑，後面的檢查 step 維持預設的 `success()`，前一步失敗時略過，不會把 job 變綠。此項無法在本機實跑，依 GitHub Actions 的 step 狀態規則判定 |
| Q4 三份「哪些 test 屬於 M5」的實作 | 三處都改為掃描 `src/testmod/java` 下全部 `*.java`（CI 原本只掃 `*GameTests.java`），掃描前先去掉以 `//`、`/*`、`*` 開頭的行（`GT:202`、`YML:52`、`:222`）。Windows step 的規則 3 併入規則 2 的同一個檔案迴圈。CI step 只能是 `run: \|` 原文（`ci-rule-check.ps1` 逐字抽出），無法共用腳本，所以 README「新增 M5 GameTest」一節最後一段寫明三處（與 filter）必須同步，以及同步的內容；三處的註解也互相指向 |
| Q5 README 新增 M5 GameTest 的步驟 | `README.md:65-79`「新增 M5 GameTest」：放進 `M5UniverseGameTests`；一個 test 一個 batch、前綴 `m5_universe_<名稱>`；`_windows` 的平台略過放在 `begin` 之前（略過時不留 baseline）；`begin` 是第一個動作、名稱等於方法名稱；最後用 `awaitConvergedThenComplete` 或 `complete`（可放進 `addInstantFinalTask` 重試）；Windows 上每個 test 都必須走到最後並通過；衛生規則；用 `gates.ps1 -Mode M5GameTest` 跑，直接 `gradle runGameTestM5` 需要 fresh 的 `run/gametest-m5/world` |
| Q6 run-all 核對 CI 結果的 mutation 欄位 | `RA:102`（ci-rule step）與 `RA:217`（summary）都要求 `mutation -eq 'none'` 而且 `sourceMutation -eq 'none'` |
| Q7 新守門分支的 mutation | 見下方 mutation 表 M-Q7A（`GT:242-243` 宣告在其他 class）、M-Q7B（`GT:244` 共用 batch）、S-Q7C／M-Q7C（filter 的互斥，`M4LegacyGameTestFilterMixin.java:22`） |
| Nit | Windows step 結尾兩行（`YML:270-271`）改為規則實際檢查的內容：`_windows` 案例分 default／M5 的宣告數，都出現在所屬 XML，兩份 XML 的 `_windows` 案例都沒有 failure／error／skipped；M5 宣告都出現在 M5 XML 且沒有 failure／error／skipped |

測試：

- JUnit 不變（560）；default GameTest 不變（160，`_windows` 80）。
- M5 GameTest 1 → 3（新增兩個 harness test，各配置一筆 DISABLED、不物化）。執行順序（batch 依名稱的雜湊）是 harness_await → sanity → harness_retry，catalog 0 → 1 → 2 → 3。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3c/fix1/`。`git diff 2ee8539 5307407 -- <path>` 輸出的 SHA-256 前 8 碼（`fix1/diff-hashes.txt`）：`src/testmod` `5eeae94a`、`scripts/verification` `3f9a2c7d`、`.github` `06358092`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED S1 | `2ee8539`；porcelain 只有 ` M …/M5UniverseGameTests.java`（`fix1/red-s1-state.txt`）。test 檔是 `2ee8539` 的版本，sanity 在 `begin` 之後多註冊一個 `SERVER_STOPPING` listener，停服時多配置一筆並寫出（`fix1/red-s1.diff`，前 8 碼 `d49a6c1e`）；harness 是 `2ee8539` | `gate-f3c-fix1-red-s1-666a8ceb99f542428927928297db2926` | 預期的缺口：log 有 `F3C_S1_INJECTED_AFTER_LAST_COMPLETE records=2`（最後一個 test 的 after 是 1），gate 仍 PASS，`2ee8539` 的 oracle 看不到最後一個 test 之後的改動 |
| RED Q1 | 同上；test 檔是 `2ee8539` 的版本加上兩個 harness test，以及只為編譯的 adapter：`awaitConvergedThenComplete` 直接呼叫 `2ee8539` 的 `complete`（`fix1/red-q1.diff`，前 8 碼 `982f4f71`；`red-q1-state.txt`） | `gate-f3c-fix1-red-q1-539d83089023425cb0609a2d5b711f64` | 預期 FAIL：harness_await 失敗在 `converged=false`（沒有主動收斂）。它留下未寫出的 catalog，sanity 與 harness_retry 都在 `begin` 的收斂檢查失敗，訊息沒有列出是哪個 test 留下的 |
| RED Q1（retry） | 同上，onlyBatches `m5_universe_harness_retry`（`red-q1-retry-state.txt`） | `gate-f3c-fix1-red-q1-retry-b2f020d4ab7c46fa8ef7137bf932f0b8` | 預期 FAIL：第一次嘗試（未收斂）就移除 baseline 並寫一行 `status=FAIL`；第 5 tick 寫出之後，每次嘗試都失敗在「缺少 begin 記下的 catalog baseline」，直到 tick 上限 |
| GREEN M5 | 工作樹，blob 與 `5307407` 相同（`fix1/green-state-blobs.txt`、`commit-blobs.txt`） | `gate-f3c-fix1-green-m5-5c8c78a33b7b4d40905aad4a899af5e7` | PASS：3/3；harness_retry 只記一行 UNMET、第 6 次嘗試通過；harness_await 當下就由 helper 收斂；增量 0→1→2→3，digest 逐一接續；`M5_UNIVERSE_SUITE_END records=3`、digest 與最後一個 test 的 after 相同、`converged=true`、`unfinished=[]` |
| GREEN Full | 同上 | `gate-f3c-fix1-green-full-6dce8d0411444774ba4529ca0e8e1f9d`（`fix1/green-full-summary.txt`） | PASS：default 160/160（`_windows` 80）；boundary PASS；scope PASS（宣告 160／1／3）；JUnit 560（2 skipped）；release 359／312、sources 210；log ERROR 28、WARN 45，與 F3c 首輪相同 |
| CI 規則 | 同上；XML 取自上兩列（`fix1/ci-matrix.ps1`、`ci-matrix.txt`） | 22 個 `ci-f3c-fix1-ci-*` root | 22 列全部符合預期：F3c 首輪的 16 列照舊，另加 Q4 的 6 列（下表） |
| gate mutation | 同上；每個單獨套用，跑完還原並以 `git hash-object` 核對（`fix1/gate-mutations.sh`、`mutations/summary.txt`、`roots-summary-1.txt`） | 9 個 `gate-f3c-fix1-mut-*` root（下表） | 全部符合預期；跑完 blob 與 GREEN 相同（`fix1/post-mutation-blobs.txt`） |
| run-all | `5307407`，`STATUS` 為空（`fix1/run-all-console.log`） | `run-all-e74bc2927e5f4b29a459cd9f86815fdc` | `END ALL exit=0 HEAD=5307407a35041336c485addb7fd154c2f2aa0f8f`，約 15 分鐘。`SUMMARY default=160/160 windows=80 legacy=1/1 m5=3/3 perTest=38+1 junit=560 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=359entries/312classes sources=210 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。本目錄每個檔的 `HARNESS` 雜湊都等於 `5307407` 的同一檔，以 C＝`5307407` 採計。`summary.json` 的 batch 範圍為 default 160、legacy 1、M5 3；`m5SuiteEnd` 為 3 筆、`converged=true` |

Q4 的 CI 列（註解 mutation：在 `M5UniverseGameTests` 的 class Javadoc 寫 `{@code @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_example")}` 與 `{@code public void example_windows(TestContext context)}`，`fix1/javadoc-mutation.diff` 前 8 碼 `dcc93cfa`；以修改過的原始碼複本當 `-SourceRoot`）：

| 列 | Workflow | Windows | Ubuntu |
| --- | --- | --- | --- |
| javadoc | `5307407` | PASS | PASS |
| old-javadoc | `2ee8539`（`fix1/old-build.yml`） | FAIL：`_windows` 宣告掃描不一致「僅寬鬆掃描 [example_windows]」；M5 宣告掃描不一致「嚴格 3、寬鬆 4」 | FAIL：M5 宣告掃描不一致「嚴格 3、寬鬆 4」 |
| old-green | `2ee8539`，原始碼不變 | PASS | PASS |

同一個 Javadoc 改動，`2ee8539` 的 `Declared-GameTests` 判「GameTest 宣告格式漂移（M5UniverseGameTests.java）：@GameTest 4 個，可解析 3 個」，`5307407` 的版本 PASS（宣告 164 個，M5 3 個）（`fix1/q4-declared-scan.txt`）。

| Mutation | 改動 | Root | 結果 |
| --- | --- | --- | --- |
| M-S1A | sanity 註冊 `SERVER_STOPPING` listener：停服時多配置一筆並寫出 | `gate-f3c-fix1-mut-m-s1a-20861a19334e4ede9d4df8eb5b5cf579` | 3/3 通過、scope PASS，gate FAIL「suite 結束時的 catalog 與最後一個 test 的結果不符：records=4（最後一個 test 之後是 3）」 |
| M-S1B | 同上，但只把 sanity 那一筆改成 EAGER_ENABLED 並寫出（筆數不變） | `gate-f3c-fix1-mut-m-s1b-be15fbcabb53419f820cdd2714575dec` | 3/3 通過，gate FAIL：records=3、digest 不同 |
| M-Q1A | `convergeAll` 不呼叫 `convergeIfNeeded` | `gate-f3c-fix1-mut-m-q1a-84d1a9c5f13b47ef8ddc2b55208aabbb` | harness_await 逾時，寫一行 `converged=false status=FAIL`；sanity 與 harness_retry 在 `begin` 失敗，訊息列出「還沒通過的 test：[harness_await_converges_an_unflushed_catalog_change]」；SUITE_END 的 `unfinished` 也列出它 |
| M-Q1B | `finish` 一開始就移除 baseline（回到 `2ee8539` 的行為） | `gate-f3c-fix1-mut-m-q1b-f83e94f3a5874a3eb68f6e06fe1d14aa` | harness_retry 失敗在「缺少 begin 記下的 catalog baseline」；其他兩個照常通過 |
| M-Q4（正向） | class Javadoc 寫入上表的註解 | `gate-f3c-fix1-mut-m-q4-6f16dead45c6444ab9ffe781c329d4f1` | gate PASS（沒有誤判為格式漂移），3/3 |
| M-Q7A | `M5CollapseGameTests` 的 `m5_collapse_fluid` 改為 `m5_universe_fluid_outside` | `gate-f3c-fix1-mut-m-q7a-dea4d3794cca4bfdbb3a13c3ab29cef5` | 4/4 通過、scope PASS（宣告 4），gate FAIL「m5_universe batch 只能宣告在 M5UniverseGameTests：m5collapsegametests.every_bulkhead_state_holds_back_fluids」 |
| M-Q7B | harness_await 的 batch 改為 `m5_universe_sanity`（與 sanity 共用） | `gate-f3c-fix1-mut-m-q7b-b03defc832a54f3492ab24ad47d032cb` | 3/3 通過、scope PASS，gate FAIL「M5 test 必須各自一個 batch」 |
| S-Q7C（情境，不改檔） | M5 gate 另以 JAVA_TOOL_OPTIONS 加 `-Dquantumchamber.gametest.legacyOnly=true` | `gate-f3c-fix1-mut-s-q7c-6a954e480803461f9fb1c9020fb1ea3f` | 開服即失敗：「Failed to start the minecraft server」「IllegalStateException: legacyOnly 與 m5Only 不可同時設定」，約 19 秒結束，沒有卡住 |
| M-Q7C | 拿掉 filter 的互斥檢查，同一情境 | `gate-f3c-fix1-mut-m-q7c-8cfe128d173341a4826195e7349d914f` | 不再於開服時拒絕：legacy 優先，M5 世界裡跑了 legacy 的那一個 test（1/1 通過），只靠 gate 的 scope 核對擋下（缺 3 個 M5 case、多 1 個 legacy case） |

- Q6 以條件式驗證（`fix1/q6-condition-check.txt`）：取 `RA:102` 的條件式，對 CI 矩陣的實際 `result.json` 求值。green 兩列接受；`rename`、`rename-skip`、`rename-drop` 的 Ubuntu 列與 `rename` 的 Windows 列 verdict 都是 PASS，但 mutation／sourceMutation 不是 none，全部拒絕。沒有為此另跑一次 run-all。
- Nit 的實際輸出（`fix1/win-green-output.txt`）：「Windows 原生 _windows GameTest：default 宣告 80 個、M5 宣告 0 個（嚴格與寬鬆掃描一致），都出現在所屬 XML；default XML 80 個、M5 XML 0 個 _windows 案例，都沒有 failure／error／skipped」「M5 GameTest：宣告 3 個（嚴格與寬鬆掃描一致），都出現在 M5 XML 且沒有 failure／error／skipped（M5 XML 共 3 個）」。
- 所有 gate 都在 commit 之前以工作樹執行；commit 時 6 個檔的 blob 與 gate 前記錄的相同。run-all 在 commit 之後執行。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

偏離與說明：

- **S1、Q1 的 RED**：S1 是 gate 的缺口，RED 的形式是「同一個情境，`2ee8539` 的 gate PASS」，GREEN 時以 M-S1A／M-S1B 證明 gate FAIL。Q1 的 RED 加了兩個新 test 與只為編譯的 adapter。RED Q1 中 harness_await 留下的未寫出狀態讓另外兩個 test 失敗在 `begin`，retry 的目標斷言沒有被打到，所以另以 onlyBatches 單獨跑 harness_retry（保留兩個 root）。`begin` 列出殘留 test 屬診斷訊息，以 M-Q1A 證明。
- **M5 test 數 1 → 3**：Q1 的兩個新行為各用一個 test 鎖住；sanity 只示範一般結尾，它當下就已收斂，碰不到重試與主動收斂的路徑。
- **timeout**：Windows 含建置的那一步用 30 分鐘，不是派工舉例的 20 分鐘（理由見上表 Q2）。
- **Q3 沒有實跑**：本機無法執行 GitHub Actions，依 Actions 的 step 狀態規則判定（見上表）。
- **Q4 沒有抽成共用腳本**：理由見上表；改為三處同一套規則，並在 README 與三處註解寫明必須同步。
- **註解排除的範圍**：只排除整行註解（去掉前導空白後以 `//`、`/*`、`*` 開頭）。程式碼行尾的 `//` 註解，以及不以 `*` 開頭的多行 `/* */` 內文不排除；目前 testmod 沒有這兩種寫法會命中掃描的情況。

交給後續：

- F4a：新增 M5 GameTest 依 README「新增 M5 GameTest」一節。注入失敗的 test 優先用 `awaitConvergedThenComplete`；需要在多個 tick 後才判定時，也可以把 `complete` 放進 `addInstantFinalTask`，但只能在最後一步之後註冊（例如在最後一個 `waitAndRun` 的 callback 裡呼叫）；在 test 開頭就註冊的話，catalog 增量一達標就會 complete，之後的檢查不會執行，等於誤判 PASS（fix round 2 M1 更正）。
- F6：`_windows` 明列清單的交接同 F3c 首輪；M5 run 新增的兩個 harness test 不是 `_windows`，由 Windows 規則 3 與 Ubuntu 的 M5 規則要求。

### Fix round 2（`86e16d4`）

- Review（fix round 1 的 re-review）：Not approved（Critical 0、Important 1、Minor 3）；fix round 1 的其餘 8 項（S1、S2、Q2～Q7、Nit）確認已正確關閉。controller 指派 I1、M1～M3 與可選的 Nit。開工 HEAD 是 `1988744`。
- Commit `86e16d4` `test: poll M5 convergence one tick at a time`（parent `1988744`）：testmod `M5UniverseGameTests.java`、`scripts/verification/README.md`、`.github/workflows/build.yml`（只有 job 層級的 timeout）。本小節由其後的 docs commit 加入；同一個 commit 也在 fix round 1 的 Q1 列加註，並更正 fix round 1「交給後續」的 F4a 一句（M1）。
- Code range `1988744..86e16d4`。
- 行號以 `86e16d4` 為準（`M5U`、`YML` 同前）。

| 項目 | 處理 |
| --- | --- |
| I1 helper 在 callback 內呼叫時改到迭代中的 runAtTick 表 | 擇「專用的 TimedTaskRunner」，不用「每輪只排下一個 tick」的自我重排。理由：原版 1.21 的 `GameTestState` 把 runner 放在 `CopyOnWriteArrayList`，在同一個 tick 迭代完 `ticksByRunnables`（`Object2LongOpenHashMap`）之後才執行（javap 確認：`tickTests` 先迭代那張表，再 `timedTaskRunners.forEach(runSilently)`），所以不論 helper 在哪裡被呼叫，輪詢都不會插入正在迭代的表；自我重排每輪仍在 callback 內插入一項，只是 rehash 機率低。`awaitConvergedThenComplete`（`M5U:266`）第一輪在呼叫當下；沒有完成時以 `context.createTimedTaskRunner().createAndAdd(…)`（`M5U:277`）每個 tick 一輪，以 GameTestException 表示「還沒好」（runner 下一個 tick 重試）。不用 `addInstantFinalTask`：它會占用 test 唯一的 final clause（`markFinalCause`），而且 runner 成功之後會自動 complete。runner 會把 GameTestException 當成「還沒好」，所以逾時或收斂後條件不符時，寫一行 `status=FAIL` 之後由 `failNextTick`（`M5U:296`）排一個 `runAtTick` 在下一個 tick 丟出，讓 test 確定失敗；這一項是在 runner 階段插入，那時表已迭代完。另加可選的 `ready` 條件（`BooleanSupplier`，3 個參數的版本傳 `() -> true`），`finish`（`M5U:300`）把 `ready` 納入判定，不成立時在狀態尾端加 `ready=false`，PASS 行的格式不變 |
| I1 harness | `harness_await_inside_a_scheduled_callback_keeps_other_scheduled_checks`（`M5U:150`，batch `m5_universe_harness_nested`）：先排 5 個 `runAtTick` 檢查（tick ＋4～＋8），在 `waitAndRun(2)` 的 callback 裡配置一筆、呼叫 helper（maxTicks 20），ready 要求外層恰好跑過一次、每個檢查都恰好跑過一次，所以第一輪一定不能完成。斷言三種：外層 callback 重跑時當場失敗（`M5U:163`）；檢查重複時當場失敗；檢查遺失時逾時失敗（`ready=false`）。這些現象取決於 lambda 的 identity hash，不一定每次發生，所以另加結構面的斷言：helper 呼叫前後 runAtTick 表的項目數不變（`M5U:174`；`scheduledCount` `M5U:182` 以反射只讀 `TestContext.test`／`GameTestState.ticksByRunnables` 的大小，欄位名稱以 javap 確認，GameTest 只在 Loom 的 named runtime 執行）。maxTicks 取 20 的理由：這張表有 5 個檢查＋外層，預設容量的 maxFill 是 24，一次預排在第 19 項就 rehash；以 fastutil 8.5.12 模擬原版的迴圈 2000 次（`fix2/sim/HarnessTrials2.java`、`HarnessTrials2.out`），1988744 的做法約 66% 出現異常（外層重跑 63%、檢查遺失 6%、iterator 例外 3%，沒有卡住）；maxTicks ≤ 15 不會 rehash，25～30 時 iterator 例外（等於 server 崩潰）升到約三成 |
| I1 文件 | Javadoc（`M5U:243-265`）與 README「新增 M5 GameTest」第 5～7 點：為什麼用 runner；注入寫出失敗時退避從 200 tick 起跳（`CollapseBackoff.INITIAL_TICKS`，之後加倍），`maxTicks` 要超過 200、`tickLimit` 要跟著加大（預設 100） |
| M1 `addInstantFinalTask` 的註冊時機 | class Javadoc（`M5U:46-49`）、`complete` 的 Javadoc（`M5U:235-236`）、README 第 5 點：只能在最後一步之後註冊（例如在最後一個 `waitAndRun` 的 callback 裡），在 test 開頭就註冊的話，catalog 增量一達標就會 complete，之後排定的檢查不會執行，等於誤判 PASS。fix round 1「交給後續」的 F4a 一句同步更正 |
| M2 retry harness 通過前斷言至少失敗過一次 | `M5U:109-121`：每次嘗試先看權威是否已收斂；已收斂而且是第一次嘗試時，以 `failNextTick` 讓 test 確定失敗（runner 裡的 GameTestException 只會被重試） |
| M3 deadline 超過 tickLimit | 擇「寫明」：Javadoc（`M5U:259-261`）與 README 第 7 點。超過 tickLimit 的那一個 tick，原版一律判 test 失敗：即使同一個 tick 剛收斂、已寫了 PASS 的增量行也一樣（`timedTaskRunners.forEach(runReported)` 之後 throwable 仍為 null 時判 `No sequences finished`）；還沒收斂時以「等待…」的訊息失敗，不留下 `status=FAIL` 的增量行。沒有改成由 caller 傳入 tickLimit：`TestContext` 沒有公開 tickLimit，caller 重複填寫同一個值也可能填錯 |
| Nit（可選）job 層級 timeout | 兩個 job 都加 `timeout-minutes: 90`（`YML:14`、`:93`），Ubuntu 的 Build step 也受限 |

測試：

- JUnit 不變（560）；default GameTest 不變（160，`_windows` 80）。
- M5 GameTest 3 → 4（新增 nested harness）。執行順序：harness_await → sanity → harness_retry → harness_nested，catalog 0 → 4。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3c/fix2/`。`git diff 1988744 86e16d4 -- <path>` 輸出的 SHA-256 前 8 碼（`fix2/diff-hashes.txt`）：`src/testmod` `dbe382a6`、`scripts/verification` `acb99f68`、`.github` `dd7e5f52`。

nested harness 本身改過兩次（下表的 v1、v2、v3），每次都重跑：

- v1：只有 ready 條件。
- v2：外層 callback 與每個檢查重跑時當場失敗。
- v3：加上表項目數的結構斷言，也就是 commit 的版本。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| GREEN M5（v1） | `1988744`＋工作樹 v1 | `gate-f3c-fix2-green-m5-aced232a7b764d319b1b18fd8185812b` | PASS 4/4；nested 的外層 1 次、5 個檢查各 1 次；SUITE_END 4 筆 |
| GREEN Full（v1） | 同上 | `gate-f3c-fix2-green-full-e6f96452e8444ba495abc66ad9a518c5`（`fix2/green-full-summary.txt`） | PASS：default 160/160（`_windows` 80）；boundary PASS；scope 160／1／4；JUnit 560（2 skipped）；release 359／312、sources 210；log ERROR 28、WARN 45 |
| RED／mutation I1（v1，1988744 的一次預排） | v1 的輪詢換回 `1988744` 的一次預排（`fix2/mut-i1-prescheduled.diff`，前 8 碼 `fafbc4c1`）；onlyBatches `m5_universe_harness_nested` | `gate-f3c-fix2-mut-m-i1-1-6f6471351f5d4a1d9186dbe7e83c35ec`、`-2-100aa32873da4c5aaf1e547d019e2e58`、`-3-0304f6aa1d2a4b12a9eba63f4d01c4a8` | 3/3 FAIL。三次都是外層 callback 重跑：第二次的 `allocateOverworld` 拿到不同的 ordinal，丟「既有 Universe definition 不可改寫」。第 3 次另外讓 server 崩潰：`NullPointerException: … "this.wrapped" is null`（fastutil iterator），crash report 在 `run/gametest-m5/crash-reports/`。失敗點不在 harness 自己的檢查，所以收緊為 v2 |
| GREEN M5（v2） | 工作樹 v2 | `gate-f3c-fix2-green-m5-v2-ed00e03a30dc4f219bf5a7cafb8dfd6d` | PASS 4/4 |
| GREEN Full（v2） | 同上 | `gate-f3c-fix2-green-full-v2-451f86ad51d14d95a29abfc07e49ae5c` | PASS，數字同 v1 |
| mutation I1（v2） | 同一個替換 | `gate-f3c-fix2-mut-m-i1b-1-523b6f6f04514efaa98dcd89d469dcbd`、`-2-e7776762c4e748a1b65f41c49042b306`、`-3-f2132ac65f394553b7cbb61e5451736a` | 2/3 FAIL，都失敗在目標檢查「外層 waitAndRun 的 callback 重跑：runAtTick 的表在迭代中被改動」。第 1 次 PASS：那次的 hash 分布沒有出現異常（log 顯示檢查晚一個 tick 執行，但各 1 次）。這一次證明只靠現象是機率性的，所以加上結構斷言（v3） |
| GREEN M5（v3） | 工作樹，blob 與 `86e16d4` 相同（`fix2/green-state-blobs.txt`、`commit-blobs.txt`） | `gate-f3c-fix2-green-m5-v3-d537ee65bc8a47be85d287f807febe2c` | PASS 4/4；nested 的外層 1 次、檢查各 1 次；增量 0→1→2→3→4、digest 接續；SUITE_END 4 筆、`converged=true`、`unfinished=[]` |
| mutation I1（v3） | 同一個替換（`fix2/mut-i1-prescheduled-v3.diff`，前 8 碼 `5135e75e`） | `gate-f3c-fix2-mut-m-i1c-1-a824df3963a64a349a2884e3b2edd94c`、`-2-497c76ed801b4d3f8cb56cb25a31c820` | 2/2 FAIL，都失敗在結構斷言「awaitConvergedThenComplete 在 runAtTick 的 callback 裡插入了 20 個排程項目」，不再取決於 hash |
| mutation M2 | retry harness 在註冊前就寫出 catalog（`mutations/M-M2.diff`，前 8 碼 `e38f7ee9`）；onlyBatches `m5_universe_harness_retry` | `gate-f3c-fix2-mut-m-m2-df05a131ce2a43aa8fc5a5f4c721e977` | FAIL：「第一次嘗試就已收斂，complete 的重試路徑沒有被走到」 |
| 逾時路徑 | `convergeAll` 不呼叫 `convergeIfNeeded`（`mutations/M-TIMEOUT.diff`，前 8 碼 `01494a9c`）；onlyBatches `m5_universe_harness_await` | `gate-f3c-fix2-mut-m-timeout-dcdb6f1e7bee41bb9e11450d0b021b74` | FAIL：寫一行 `converged=false status=FAIL`，test 的失敗訊息是 finish 的「M5 catalog 增量不符…converged=false」，不是「等待…」或 tick 上限，表示 runner 逾時後由 `failNextTick` 確定失敗 |
| CI 規則 | 工作樹 v3；XML 取自 GREEN Full v2 與 GREEN M5 v3（default XML 與 nested harness 的內容無關）；`fix1/ci-matrix.ps1` | 22 個 `ci-f3c-fix2-ci-*` root（`fix2/ci-matrix.txt`） | 22 列全部符合預期，與 fix round 1 相同 |
| run-all | `86e16d4`，`STATUS` 為空（`fix2/run-all-console.log`） | `run-all-0eef2a87f8dc4871a1f7c6e7f440bb1c` | `END ALL exit=0 HEAD=86e16d4fec5045247dddfb8a436fd2f370b4b7db`，約 17 分鐘。`SUMMARY default=160/160 windows=80 legacy=1/1 m5=4/4 perTest=38+1 junit=560 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=359entries/312classes sources=210 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。本目錄每個檔的 `HARNESS` 雜湊都等於 `86e16d4` 的同一檔，以 C＝`86e16d4` 採計。`summary.json` 的 batch 範圍為 160／1／4；`m5SuiteEnd` 為 4 筆、`converged=true` |

- 所有 mutation 跑完都還原並以 `git hash-object` 核對；commit 時 3 個檔的 blob 與 GREEN M5 v3 之前記錄的相同。v3 只改 testmod 的 nested harness，沒有在 commit 前另跑 Full；commit 之後 run-all 的 Full 步驟就是 `86e16d4` 的 Full。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

觀察（未修改）：

- run-all 的 Full 步驟 log 為 ERROR 31、WARN 46，commit 前的兩次 Full 為 28、45。差異只在兩個既有的 default batch：
  - `m2_task4_disconnect_io` 的「session 協調失敗」WARN 多一則，是 F3b fix round 1 記錄過、隨時序出現的那一則。
  - `m5_h2_checkpoint_scope`（H2）的 ERROR 15 → 18：同樣是那 3 個被注入失敗的 chunk，其中一個多存了一次（「Failed to store chunk」「Failed to save chunk」「Failed to store entity chunk」各多一行），test 照常通過。
  - 兩者都與本輪的改動無關，本輪只改 M5 run 的 testmod、README 與 CI 的 job timeout。

偏離與說明：

- **RED 以 mutation 證明**：nested harness 呼叫的 4 個參數版本（帶 ready）在 `1988744` 不存在。所以 RED 是把 GREEN 的輪詢換回 `1988744` 的一次預排，與派工允許的「mutation（改回一次預排）」相同。
- **反射只讀原版欄位**：只用在 nested harness 的結構斷言，不寫入。欄位名稱是 Yarn 1.21+build.9，換 mappings 時要一起改。
- **崩潰的 root**：mutation I1 第 3 次（v1）的 server 崩潰屬預期的危害證據，crash report 留在 `run/gametest-m5/crash-reports/`，沒有刪除。

### Fix round 3（`ccc8801`）

- Review（fix round 2 的 re-review）：Approved（Critical 0、Important 0、Minor 2）；I1、M1～M3 已關閉，run-all 的 log 差異經 reviewer 確認與 fix round 2 無關。剩下兩項 Minor 都在 `M5UniverseGameTests.java` 與 README，F4a 一開始就會用到，所以 controller 指派本輪處理。開工 HEAD 是 `c2babda`。
- Commit `ccc8801` `test: turn M5 helper exceptions into test failures`（parent `c2babda`）：testmod `M5UniverseGameTests.java`、`scripts/verification/README.md`。本小節由其後的 docs commit 加入。
- Code range `c2babda..ccc8801`。行號以 `ccc8801` 為準（`M5U` 同前）。

| 項目 | 處理 |
| --- | --- |
| Minor 1：runner 或 callback 丟出非 GameTestException 的例外會讓 server 崩潰 | 依據（reviewer 的 javap，本輪的 RED 實證）：`TimedTaskRunner.runSilently` 只攔 GameTestException，runAtTick 的迴圈只攔 `Exception`，其他例外丟出 server tick，`TestServer` 寫 crash report 後結束程序，後面的 test 都不會執行。處理分四處：<br>1. `awaitConvergedThenComplete` 的 runner 工作（`M5U:322-339`）：`ready` 與 `convergeAll` 包在 `catch (RuntimeException)` 裡，例外時設 `done`、寫一行 `M5_UNIVERSE_CATALOG_DELTA name=… status=FAIL error=…`（`logFailedDelta` `M5U:356`），再以 `failNextTick` 讓 test 在下一個 tick 確定失敗；最終判定的例外同樣轉成 `failNextTick`。訊息都帶原例外，原例外的 stack 另記一行 ERROR（`asTestFailure` `M5U:348`）。<br>2. 第一輪在呼叫當下執行（`M5U:300-316`）：例外轉成 GameTestException 丟給呼叫端。test 方法與 runAtTick 的 callback 都會讓 test 失敗。<br>3. `complete`（`M5U:251`）：判定丟出非 GameTestException 的例外（例如 catalog 不健康時 `records()` 的 IllegalStateException）時，轉成 GameTestException，之後每次呼叫都直接丟同一個（`BROKEN` `M5U:65`）。放在 `addInstantFinalTask` 裡時，test 在 tickLimit 以這個訊息失敗，不會讓 server 崩潰。（fix round 4 更正：「延到 tickLimit」的理由不成立，原版公開的 `TimedTaskRunner.fail` 不碰 runAtTick 的表，現在改在下一個 tick 失敗，見「Fix round 4」。）<br>4. `scheduledCount` 改丟 IllegalStateException（`M5U:201`），因為 runAtTick 的迴圈攔不到 `Error`；retry harness 自己在 runner 裡的收斂檢查也同樣轉換（`M5U:119`）。<br>Javadoc（`M5U:285-289`）寫明：ready 用回傳 false 表示「還沒好」；ready、權威收斂與判定丟出的例外（含 GameTestException）一律轉成 test 失敗；不要在 TimedTaskRunner 的工作裡呼叫 helper。README 第 5 點同步 |
| Minor 2：完成時點之後才到期的排程檢查會被跳過 | Javadoc（`M5U:283-284`）與 README 第 5 點各補一句：test 完成之後原版不再 tick 它，還沒到期的檢查必須放進 ready，或排在呼叫 helper 之前、而且在呼叫時已經到期 |

測試：JUnit 560（2 skipped），M5 GameTest 4/4，數量都不變。例外的情境只做 mutation，跑完還原，沒有留在 GREEN。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3c/fix3/`。`git diff c2babda ccc8801 -- <path>` 輸出的 SHA-256 前 8 碼：`src/testmod` `2b692a67`、`scripts/verification` `96e061aa`。五次 M5 gate 都是完整的 M5 run（沒有 onlyBatches），以確認失敗的 test 之後其他 test 照常執行（`fix3/runs.sh`、`mutations/summary.txt`、各 `*-state.txt`）。

| 輪次 | 狀態 | Root | 結果 |
| --- | --- | --- | --- |
| RED-A：ready 在 runner 裡丟例外 | `c2babda` 的 test 檔，harness_await 改用「第一輪回 false、之後丟 IllegalStateException」的 ready（`fix3/red-a.diff`，前 8 碼 `9c7e0fd9`） | `gate-f3c-fix3-red-a-d0e2a77733e74e8db796b185be6a86b1` | server 崩潰：「Game test server crashed」「Encountered an unexpected exception」IllegalStateException，crash report `run/gametest-m5/crash-reports/crash-2026-10-02_12.26.31-server.txt`。harness_await 是第一個 test，後面三個都沒有執行 |
| RED-B：complete 的判定在 runner 裡丟例外 | `c2babda` 的 test 檔，`finish` 對 harness_retry 的非最終嘗試丟 IllegalStateException（`fix3/red-b.diff`，前 8 碼 `6c02a457`） | `gate-f3c-fix3-red-b-4dd90b965ebd42b1a8a9d8cc7e08c1f1` | server 崩潰（crash report `…crash-2026-10-02_12.26.55-server.txt`）。harness_await 與 sanity 已 PASS，nested 沒有執行 |
| GREEN | 工作樹，blob 與 `ccc8801` 相同（`fix3/green-state-blobs.txt`、`commit-blobs.txt`） | `gate-f3c-fix3-green-650c0ceca7f846f78949d6d16497320b` | PASS 4/4；增量接續，SUITE_END 4 筆、`converged=true`、`unfinished=[]` |
| GREEN-A | GREEN 的 test 檔加上與 RED-A 相同的 ready（`fix3/green-a.diff`，前 8 碼 `285f95d0`） | `gate-f3c-fix3-green-a-daa0aa0f63344c969a74f5acf38066b9` | 預期的 gate FAIL（exit 1，「1 required tests failed」），沒有崩潰。harness_await 的失敗訊息是「ready 條件或權威收斂丟出例外：java.lang.IllegalStateException: F3c fix round 3 mutation：ready 丟出例外」，log 有 `status=FAIL error=…` 與 ERROR stack；sanity、harness_retry、harness_nested 照常 PASS；SUITE_END 正常寫出（`unfinished` 列出 harness_await） |
| GREEN-B | GREEN 的 test 檔加上與 RED-B 相同的 `finish` 例外（`fix3/green-b.diff`，前 8 碼 `c18a896e`） | `gate-f3c-fix3-green-b-efbf7467386f4251b3463b400448366d` | 預期的 gate FAIL，沒有崩潰。harness_retry 的失敗訊息是「complete 的判定丟出例外：java.lang.IllegalStateException: F3c fix round 3 mutation：finish 丟出例外」（runner 重試到 tickLimit 後由原版回報；fix round 4 改為下一個 tick 失敗）；其他三個照常 PASS；SUITE_END 正常寫出 |
| JUnit | 工作樹（同 GREEN） | Gradle `test --rerun-tasks`（`fix3/junit.log`、`junit-xml/`） | 560，0 failure／error，2 skipped（只在 `NonWindowsPlayerCheckpointStoreTest`）；同一次也重新編譯 testmod（`compileTestmodJava`） |

- 每次跑完都把 test 檔還原並以 `git hash-object` 核對（`restored=OK`）；commit 時 2 個檔的 blob 與 GREEN 之前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。
- 本輪不跑 Full 與 run-all：只改 M5 run 的 testmod 類別（`M5UniverseGameTests` 只在 M5 run 執行；default run 由 filter 排除，`begin` 也會擋下）與 README。`scripts/` 的程式、CI、build 檔與 default run 都沒有改，所以 M5 gate 加上全部 JUnit 就涵蓋了本輪的變更，與派工的回歸範圍相同。
- RED 的兩份 crash report 屬預期的危害證據，留在 `run/gametest-m5/crash-reports/`，沒有刪除。

### Fix round 4（`e75bf78`）

- Review（fix round 3 的 re-review）：Approved（Critical 0、Important 0、新 Minor 1）。controller 指派本輪只處理 N1 與一個 Nit。開工 HEAD 是 `a950caf`。
- Commit `e75bf78` `test: fail M5 helper conversions on the next tick`（parent `a950caf`）：testmod `M5UniverseGameTests.java`、`scripts/verification/README.md`。本小節由其後的 docs commit 加入；同一個 commit 也在 fix round 3 的兩處加註更正。
- Code range `a950caf..e75bf78`。行號以 `e75bf78` 為準（`M5U` 同前）。

| 項目 | 處理 |
| --- | --- |
| N1：轉換後「延到 tickLimit 才失敗」可以免掉 | fix round 3 的理由是：`complete` 不知道自己在哪個情境，而在 runAtTick 的 callback 裡用 `runAtTick` 排失敗，會碰到正在迭代的表。這個理由不成立：原版公開的 `TimedTaskRunner.fail(Supplier<Exception>)`（javap：新增一個工作，直接呼叫 `test.fail(supplier.get())`）加上 `context.createTimedTaskRunner()`（新 runner 加進 `CopyOnWriteArrayList`）完全不碰 runAtTick 的表。新增 `failDefinitely`（`M5U:373`）：在 test 方法或 runAtTick 的 callback 裡呼叫時，同一個 tick 的 runner 階段就失敗；在 runner 的工作裡呼叫時，下一個 tick 失敗。`complete` 的轉換分支（`M5U:256-268`）改為轉換之後 `failDefinitely`、再丟出。原本以 `runAtTick` 排失敗的 `failNextTick` 整個換成 `failDefinitely`，用在 helper 的 runner 工作與 retry harness 的 M2 檢查。`BROKEN` 保留，理由改寫在 Javadoc（`M5U:247-254`）：失敗落地之前 runner 還會重試一次，sticky 讓這次重試直接丟同一個例外，不再記 ERROR、不再排第二個失敗，也不會因為判定剛好通過而先寫出 PASS。README 第 5 點同步更正 |
| Nit：retry harness 的例外轉換每個 tick 都記一次 ERROR | `M5U:110-123`：轉換一次、設 `failing`、`failDefinitely`，之後的嘗試直接丟「等待確定失敗」，不再進入收斂檢查 |

測試：JUnit 560（2 skipped），M5 GameTest 4/4，數量都不變。例外情境只做 mutation，跑完還原。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3c/fix4/`。`git diff a950caf e75bf78 -- <path>` 輸出的 SHA-256 前 8 碼：`src/testmod` `4e8ab150`、`scripts/verification` `33a661c0`。七次 M5 gate 都是完整的 M5 run（`fix4/runs.sh`、`mutations/summary.txt`、`roots-summary.txt`、各 `*-state.txt`）。RED 對照用 `a950caf` 的 test 檔加上同一個 mutation。

| 輪次 | 狀態 | Root | 結果 |
| --- | --- | --- | --- |
| GREEN | 工作樹，blob 與 `e75bf78` 相同（`fix4/green-state-blobs.txt`、`commit-blobs.txt`） | `gate-f3c-fix4-green-944a7a27ea804cbc9dc137919ed37362` | PASS 4/4；SUITE_END 4 筆、`converged=true`、`unfinished=[]` |
| GREEN-BTL | retry harness 的 `tickLimit` 改為 100000（repo 既有的前例），`finish` 對它的非最終嘗試丟 IllegalStateException（`fix4/green-btl.diff`，前 8 碼 `80174e8c`） | `gate-f3c-fix4-green-btl-50d46a156c0c4411b7faf9b42410bdb4` | gate 22 秒（12:35:57→12:36:19）。harness_retry 在第一次嘗試（tick 0）丟出，以「complete 的判定丟出例外：…」失敗，XML 時間 0.043 秒，ERROR 只記一次；沒有崩潰 |
| RED-BTL | `a950caf` 的 test 檔加上同一個 mutation（`fix4/red-btl.diff`，前 8 碼 `772a68d9`） | `gate-f3c-fix4-red-btl-c94d1c3bf2e742b9962c142eed295807` | gate 5 分 10 秒（12:36:20→12:41:30）：同一個失敗等到 tick 100000 才由原版回報。這正是 N1 指出的情況：tickLimit 再大一些，就會先撞上 gate 的 1200 秒或 CI step 的 20 分鐘上限 |
| GREEN-N | retry harness 的收斂檢查每次都丟 IllegalStateException（`fix4/green-n.diff`，前 8 碼 `69e542ee`） | `gate-f3c-fix4-green-n-763b87f043104a038a31cb9d19dd6859` | harness_retry 以「retry harness 的收斂檢查丟出例外：…」失敗；注入點只執行 1 次，`M5_UNIVERSE_HELPER_EXCEPTION` 只有 1 行 |
| RED-N | `a950caf` 的 test 檔加上同一個 mutation | `gate-f3c-fix4-red-n-47453dcca1a94206929e14d2ca1718f2` | 注入點執行 102 次，`M5_UNIVERSE_HELPER_EXCEPTION` 102 行（每個 tick 一次 ERROR stack），到 tickLimit 才失敗 |
| GREEN-A | harness_await 的 ready 在 runner 裡丟例外（同 fix round 3 的 GREEN-A，`fix4/green-a.diff`） | `gate-f3c-fix4-green-a-66a9be1e15764b85b38644e333bb841a` | harness_await 以「ready 條件或權威收斂丟出例外：…」失敗，其他三個 PASS，沒有崩潰 |
| GREEN-M2 | retry harness 在註冊前就寫出 catalog（`fix4/green-m2.diff`） | `gate-f3c-fix4-green-m2-80e281f5afaa4f62a462e89fbe97e4c0` | harness_retry 以「第一次嘗試就已收斂…」失敗（走 `failDefinitely`），其他三個 PASS |
| JUnit | 工作樹（同 GREEN） | Gradle `test --rerun-tasks`（`fix4/junit.log`、`junit-xml/`） | 560，0 failure／error，2 skipped；同一次重新編譯 testmod |

觀察（未修改）：

- GREEN-BTL 與 GREEN-N 中，harness_retry 改為在 tick 0～1 失敗，比它自己排在 tick 5 的 catalog 寫出還早，所以 catalog 留在未寫出的狀態。下一個 test（harness_nested）因此在 `begin` 的收斂檢查失敗，訊息列出「還沒通過的 test：[harness_complete_retries_without_losing_the_baseline]」。這是 `begin` 的衛生檢查照設計運作：前一個 test 沒有收斂就結束。RED 對照沒有這個連帶失敗，只是因為它等了 100000 tick，排定的寫出在那之間跑完了。
- 若希望例外失敗的 test 不連累下一個 test，可以讓 `failDefinitely` 先盡力收斂一次（呼叫 `convergeAll`，忽略例外）。這超出本輪的窄範圍，沒有做，列為可選的後續。
- 本輪不跑 Full 與 run-all，理由同 fix round 3：只改 M5 run 的 testmod 類別與 README。controller 會在 F3d 完成之後的新 HEAD 上跑 run-all 作為正式回歸。
- 每次跑完都把 test 檔還原並以 `git hash-object` 核對；commit 時 2 個檔的 blob 與 GREEN 之前記錄的相同。結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

## F3d：CollapseService（提交、返還、SOURCE、Nether／End）

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `cc936d0` `fix: release collapse runtime through the session manager and harden returns`（parent `438c306`）：production `superposition/SuperpositionSessionManager.java`、`persistence/SessionRecoveryManager.java`、`FailureText.java`（只有 Javadoc）、`transfer/ChamberReturnPlacement.java`；測試擴充 `FailureTextTest.java`、`ChamberReturnPlacementTest.java`；testmod `ConnectedGameTestPlayer.java`、`M2CorridorGameTests.java`（只有共用 drain 的一處）、`M4CandidateDoorGameTests.java`、`M4CandidateTestAccess.java`。
  - `33624ad` `feat: commit collapses and return the cohort to the origin chamber`（parent `cc936d0`）：production 新增 `collapse/CollapsePhase.java`、`CollapseService.java`，修改 `QuantumSuperpositionMod.java`、`corridor/CorridorPageManager.java`（只新增 `measuredReleaseInProgress`）、兩個語言檔（「塌縮」）；`build.gradle`（只有 `m4Recovery` run 的 vmArg）；測試新增 `src/test/java/dev/quantumchamber/collapse/CollapseServiceTest.java`；testmod 新增 `mixin/CollapseSuspendMixin.java`，修改 `quantumchamber-test.mixins.json`、`M4CandidateDoorGameTests.java`、`M4CandidateTestAccess.java`、`M5CollapseGameTests.java`、`M5UniverseGameTests.java`。
  - `93df897` `feat: refuse side doors whose source is the Nether or the End`（parent `33624ad`）：`candidate/CandidateDoorInteraction.java`、兩個語言檔（「Nether／End 選門」）、`CollapseServiceTest.java`（語言 test 加上新 key）、`M5CollapseGameTests.java`（Nether 原艙的 test 與 helper）。
  - 本段的 docs commit 接在 `93df897` 之後。
- Code range `438c306..93df897`。沒有改 `SessionRecoveryState.java`（補充第 2 點的 API 不需要 journal 配合）、`TestHooks`、M2 的 `OBSERVED` 靜態表、`.github/`、`scripts/verification/`。
- 範圍是 plan Task F3d：第一次在 production 提交 COLLAPSING。返還、清理、RESOLVE stub、WAITING 期間的門都是一般狀態，不攔截（F4b）；RESOLVE 只有 stub（F4a）；PREPARE 只有 SOURCE 的 fast path（UNIVERSE 的 PREPARE 屬 F4b）。
- 行號以 `93df897` 為準。`CS` 指 `collapse/CollapseService.java`，`CP` 指 `collapse/CollapsePhase.java`，`SSM`、`SRM`、`CPM`、`CRP`、`CDI` 指 `SuperpositionSessionManager`、`SessionRecoveryManager`、`CorridorPageManager`、`ChamberReturnPlacement`、`CandidateDoorInteraction`；testmod 的 `M5C`、`M5U`、`M4C`、`M4A`、`MIX` 指 `M5CollapseGameTests`、`M5UniverseGameTests`、`M4CandidateDoorGameTests`、`M4CandidateTestAccess`、`mixin/CollapseSuspendMixin`；JUnit 的 `CST` 指 `CollapseServiceTest`。測試欄的 G-* 見「測試」。
- Review：spec review ❌ I1／m5 → fix round 1（見本節最後的「Fix round 1」）；spec re-review ✅（Minor 2）、quality review Not approved（Important 1、Minor 7）→ fix round 2（見「Fix round 2」）；spec re-review（fix round 2）❌（Important 1、Minor 3）→ fix round 3（見「Fix round 3」）。最終結果由 controller 補。本節其餘內容描述 `93df897`，fix round 1 改過的地方另加註。
- 最終 review：
  - fix round 3（`08dd4d7`）的 spec re-review ✅（Critical 0、Important 0、Minor 0）。
  - fix round 2＋3（`b82b2cf..56a766e`）的 quality re-review Approved（Critical 0、Important 0、Minor 4，全部交給 F4a，見「交給後續」）。
  - 兩項需要裁示的事項，使用者 2026-10-02 確認維持：「量子態塌縮。」顯示兩次；live 提交的時點。
  - **F3d 完成**：range `438c306..56a766e`。
    - code：`cc936d0`、`33624ad`、`93df897`、`186b8f7`、`fa9e39d`、`8f3d76c`、`08dd4d7`。
    - 最終 run-all：`run-all-b5b2bf9b30fc49e5980973ea708490c2`，`END ALL exit=0`（`08dd4d7`）。

### plan Step 1 逐項對照

| plan Step 1 | 程式位置 | 測試 |
| --- | --- | --- |
| 選門 → 下一 tick COLLAPSING | `CS:184` `measured`：current＝flushed（`:187`）、M4 的 measured release 進行到一半時等待（`:199`，`CPM:263`）、per-session 退避；`CS:261` `commit` 經 `commitRestore`（M→C），Destination 依 `CS:211`；live 不要求供電。CollapseService 在選門那個 tick 的 `END_SERVER_TICK` 提交，所以下一個 tick 看到的就是 checked COLLAPSING（見「偏離」第 2 條） | G-SRC `committedNextTick`；G-RF、G-FB 的第一個斷言；CST `destinationIsFixedAtCommitFromTheSelectedCandidate`。fix round 1：live 提交包在 operation guard 內，G-SRC `commitInsideOperationGuard` |
| 全員返還（落點碰撞、WARN 附 cause）；未返還者凍結並收到位置修正 | 返還與凍結是 F3a 的 `recoveryWork`／`frozen`，CS 不重複驅動。落點 `CRP:26`（`plan` 的碰撞版本）、`SRM:285`／`:348` `landingClear`／`:353` `warnLandingBlocked`；返還 WARN 附 cause `SRM:173` | G-SRC（預設 slot 放石頭、`firstAvoidedTheStone`、`returnedInsideWithoutCollision`、兩人同 tick 各一則位置修正）；G-RF `oneWarnWithCause`；JUnit `ChamberReturnPlacementTest.returnSlotsSkipBlockedCellsThenRaiseOrFallBackDeterministically`、`FailureTextTest.dedupeKeyOfANullReasonCarriesNoFileIdentity` |
| SOURCE → 一般開門、catalog 不變、record 移除、INFO；presence NONE、runtime 與 ticket 釋放、同艙立即開新 session | `CS:341` `sourceFastPath`：`terminate(SOURCE_FAST_PATH)` → `:377` `terminated`（`SSM:508` `releaseAfterTerminal`）→ INFO → `:462` `openDoor`／`:482` `applyDoor` → `:443` 訊息 → `:451` `refresh` | G-SRC 的 after（`doorPlainOpen`、`diskHasNoRecord`、`oneSourceInfo`、`presenceNone`、`runtimeAndTicketReleased`、`newSessionStarted`）；per-test receipt（catalog 與 world keys 不變） |
| 返還、清理、RESOLVE stub、WAITING 期間開關門不結束塌縮 | CS 不讀門的狀態，門也沒有攔截（F4b）；結束塌縮的只有 `CS:341`／`:220`／`:357` 的終局 | 返還：G-OFF `openKeepsCollapse`／`closeKeepsCollapse`；清理：G-RF `releaseDoorOpens`／`releaseDoorKeepsCollapse`；RESOLVE：G-RES `resolveDoorOpens`／`resolveDoorCloses`／`holdsAtResolve`；WAITING：G-DOR `waitingDoorOpens`／`doorDoesNotEndWaiting`／`waitingDoorCloses` |
| 返還進度的 roll-forward：返還寫出失敗 → 只重試同一份 current、不疊寫；確認後恢復 | `SRM:152-157` RETRY_FLUSH 只寫出同一份 current、以 journal 退避；`SRM:164-174` 失敗時退避一次、WARN 去重；`CS:291` roll-forward 未確認時 CS 對該 session 全部暫停 | G-RF（`currentCarriesOneReturn`、`flushedHasNoReturn`、`neverOverwroteAnotherReturn`、`noRetryBeforeBackoff`、`confirmedNotBeforeBackoff`） |
| 離線參與者重新加入後完成返還；COLLAPSING 期間 Buff 到期不丟例外 | F3a 的 recovery（JOIN 的下一個 tick 返還）；CS 不碰 Buff | G-OFF（fix round 1：`returnedOnTheTickAfterJoin` 斷言返還寫出恰在 JOIN 的 tick＋1） |
| Nether／End 選門被拒；Nether／End 的 M4 NEW 收據 → DORMANT → 取消（INFO） | 選門 `CDI:41-46`、`:55`；收據 `CS:190`、`:204` `unsupportedSource`、`:220` `cancelUnsupported` | G-NETHER、G-UNSUP；fix round 1 的 G-NLIVE（非 DORMANT 的 Nether NEW：不提交 → 保留式 recovery → DORMANT → 取消）；CST `unsupportedSourceIsOnlyNetherOrEndWithNewOrExisting`、`f3dMessagesExistInBothLanguages` |
| DORMANT 邊緣觸發；未供電或停用時不觸發 | `CS:244` `resumeDormant`、`:550` `EntryWatch`、`:414` `chamberGate`（即時紅石 `:427`、enabled `:430-432`） | G-DOR (a)～(e)；CST `entryWatchTriggersOnlyOnEntryEdges` |
| 提交失敗 → M4 fallback → DORMANT → 退避；M5 authority 初始化失敗 → 不提交 | `CS:265-270`（WARN、`backoff.failed`、live 才 `requestMeasuredRecovery`）；`m5ReadyThisInstance` 為假時 journal 拒絕 M→C（F2b） | G-FB |
| 跨 session 唯一性 | `CS:406` `triggerReady`＝`participantsAvailable`；唯一性規則本身是 F2a 的 `occupies` | G-ELSE（整體結果：已返還者不被佔用、B 進入 RETURNING、A 與 A′ 不被改寫）；依玩家查找只找到佔用他的 session：F3a 的 `collapse_states_attach_without_returning_and_lookup_only_occupying_sessions`（M-LOOKUP 下 FAIL）；G-DOR `busyParticipantDidNotBlock` |
| M4 GameTest 在 hook 下維持原斷言 | `MIX:23`；`M4C:38-50`；`build.gradle:103` | Full 169/169；`m4-recovery-probe` 24/24；M-HOOK |

### controller 補充第 1–17 點

- **第 1 點（F3a 已完成的部分）**：CS 沒有任何返還或清理的呼叫；`requestMeasuredRecovery` 只在 MEASURED 時呼叫（`CS:225`、`:269`），這兩處依第 8 點與 spec §11.3 是 M4 的保留式 recovery。`CP:32-33` 只讀 receipt 與 `measuredRecoveryComplete` 推導階段。
- **第 2 點（終局與 runtime／ticket 的正式 API）**：`SSM:508` `releaseAfterTerminal`（要求 current 與 flushed 都沒有這個 session）、`SSM:519` `releaseOnSealed`（要求 collapse view 是 SEALED；F4a 才會呼叫）、`SSM:531` `acquireCollapseTicket`（要求 flushed 恰為這份 COLLAPSING），三者共用 `SSM:538`。CS 在每個 checked 終局之後呼叫（`CS:381`），presence 因此在 production 自行回到 NONE（G-SRC `presenceNone`、`newSessionStarted`）。testmod 的 `releaseCollapseRuntimeForTeardown`、`releaseSourceTicketForTeardown` 已刪除，F3a 的 G2、G3 與 M5 的收尾改走正式 API（`M4A:272` 只是轉呼叫）。`revertCollapseForTeardown`（`M4A:280`）保留：它把 COLLAPSING 退回 MEASURED，production 沒有、也不應該有 C→M 的正式路徑。`SessionRecoveryState.java` 沒有修改。
- **第 3 點（離開 SETTLED 的前提）**：`CP:33` 在 `measuredRecoveryComplete` 為假時一律是 RELEASE_GEOMETRY，所以 RESOLVE、WAITING、PREPARE（SOURCE 終局）都要等走廊 space 真的釋放；DORMANT 續行（`CS:247`）與 §11.3 的取消（`CS:232`）也一樣。RED 用 G-RF 的後半：清 lease 的寫出受控失敗一次，同一個 tick 內 RETRY_FLUSH 寫出 SETTLED；斷言那個 tick 沒有離開 SETTLED，下一個 tick 才終局，space、slot、chunk ticket 都沒有洩漏（`settledWhileSpaceRemains`、`leftSettledOnlyAfterSpaceReleased`、`noSpaceSlotOrTicketLeak`）。M-SETTLED 證明它會抓到。
- **第 4 點（RETRY_FLUSH 的退避）**：`SRM:39-40` 一個 journal 一個 `CollapseBackoff`（200→12000）。設計：
  - 單位是 journal 這個權威：RETRY_FLUSH 每次都寫出整份 journal，以 session 為單位退避並不會少寫。
  - 同一個 tick 只算一次失敗（`journalRetryFailedTick`），所以多個 session 同時待確認不會讓退避多跳幾級。
  - 退避期內 RETRY_FLUSH 跳過（`SRM:156`）；本 tick 已沒有待確認的 roll-forward（寫出已確認，或被其他寫出、原生 autosave 帶出）時重設（`SRM:178`）。
  - 只有 recovery 自己的寫出失敗會餵退避；走廊清理（CPM）的寫出失敗不餵。所以 F3a G3 的「清 lease 失敗、同一 tick RETRY_FLUSH 成功」照樣可以重現。
- **第 5 點（`MeasuredRecoveryPhase.from`）**：CS 只在 `record.state()==MEASURED` 時呼叫它（`CS:188`），PASSAGE_READY、SEALED 走 `CS:173-179` 的其他分支；每個 session 另有 try/catch（`CS:139-143`），整個 tick 也有外層 catch（`CS:159-163`），例外不會從 `END_SERVER_TICK` 拋出。
- **第 6 點（終局 WARN 的去重）**：`CS:510` `warn` 以 (session, 寫入種類, `FailureText.dedupeKey`) 去重；提交成功時清除該鍵（`CS:272`），終局成功時整個 Track 移除（`CS:379`）。G-FB 斷言兩種原因各一則 WARN。
- **第 7 點（`TerminalOrigin` 的前提）**：`SOURCE_FAST_PATH` 只在 `CP` 推得 PREPARE 時呼叫（全員已返還、沒有 lease、`measuredRecoveryComplete`），`UNSUPPORTED_SOURCE_CANCEL` 只在 flushed DORMANT、`measuredRecoveryComplete`、`unsupportedSource` 時呼叫（`CS:232`），`PASSAGE_ENDED` 只對開服時就是 PASSAGE_READY 的 session（`CS:176`）。journal 本身另有檢查：M-NETHERSOURCE 的第一輪就是 journal 拒絕對 SOURCE 取消。
- **第 8 點（提交 COLLAPSING）**：`CS:263-264` 以 `commitRestore`（M→C）提交，`m5ReadyThisInstance` 由 journal 檢查（G-FB 以反射把它暫時設為假）。Destination 依 §7.2（`CS:211`）：SOURCE、EXISTING 的 UniverseId、NEW 的 `deriveUniverseId(token)`；ledger 的查詢屬 RESOLVE（F4a），F3d 只用推導值（G-RES `destinationIsTheDerivedUniverse`）。CommittedAtGameTime 用 overworld 的 game time。提交失敗：WARN、per-session 退避（新 instance 沒有退避，所以開服後第一次觸發不受限）；live 另請求 M4 的保留式 recovery（`CS:269`），DORMANT 維持原狀。
- **第 9 點（DORMANT 續行）**：觸發條件見上表；「已供電」一律是 `world.isReceivingRedstonePower(controllerPos)`（`CS:427`），而且艙體外擴 3 格涵蓋的 chunk 都已載入（`CS:435`，只查詢、不載入），不看 registry 的 `ChamberPowerState`。續行提交之後自行取得來源 ticket（`CS:276-280`，G-DOR `dormantResumeTakesTicket`、G-RES）。
- **第 10 點（開服時就是 PASSAGE_READY）**：`CS:111` 開服時記下這些 session；`CS:357` 以 `terminate(PASSAGE_ENDED)` 終局、記 INFO，門在下一個 tick 才改為一般開門（`CS:370` 的延遲 1）。G-PASS 以 fixture 與 `M4A:161` `markStartup` 驗證；M-DOORNOW 證明「下一個 tick」有被鎖住。
- **第 11 點（SOURCE fast path 的門）**：順序是終局 → 改門 → 訊息（`CS:348-351`）。`CS:482` 依 `ApplyResult`：APPLIED／UNCHANGED 完成；NOT_LOADED 等之後的 tick（不強制載入）；DOOR_INCOMPLETE 記一次並退避，等結構恢復；ROLLED_BACK、ROLLBACK_FAILED 下一個 tick 重試一次，再失敗才退避（ROLLBACK_FAILED 已設 activationBlocked、已記 ERROR）。去重以原因字串為單位（`CS:585`）。原艙已開始新的 session（presence 不是 NONE）時不再套用。
- **第 12 點（R2 M6 與返還 WARN）**：見上表第 2 列。`CRP:26` 先依 25 個地板 slot（y=1），再依 y=2、3、4 找第一個不碰撞、未被占用的位置；都不行時退回預設 slot，並記 WARN（`SRM:291`、`:353`，同一 session 的同一玩家只記一次）。返還 pending 的 WARN 附 root cause 的 `describe` 與 stack（`SRM:173`）；M-CAUSE 證明它會抓到。
- **第 13 點（文字修正）**：`SRM:236` 改為「未 checked authority 已收斂」，`M4C:397` 的斷言同步。
- **第 14 點（H3、H4 交給 F3d 的子項）**：
  - `ConnectedGameTestPlayer.drainOutbound`（`:63`）由 `confirmTeleport`（`:54`）、`M2CorridorGameTests:1086`（位置修正）、`M4C:1092`（訊息）共用。
  - 同 tick 兩位參與者各一則位置修正：G-SRC 的 `oneCorrectionThisTick`（`M5C:310-316`）。
  - `FailureText`：收斂恢復的 INFO 印 `describe`（`SRM:228`、`:241`）；null reason 的 JUnit；`dedupeKey` 的 Javadoc 註明「鍵不含檔案身分」（`FailureText:30`）。
  - M4 test 收尾：`batchFaultProbe`、`publishProbe` 在所有路徑上以 TestHooks 清除並關閉 fixture（`M4C:228-231`、`:1002-1005`）；publish probe 的等待改為有界（`whenWithin` 60 秒）；DORMANT 收據的 test 在失敗路徑也移除自建的收據（`M4C:564`）。q7：`selectionTeardown`（`M4C:1020`）斷言失敗時仍收尾（把 MEASURED 重設回 SUPERPOSITION 並斷電觸發一般返還），收尾例外附在原失敗上；拿掉了死變數 `observed`。
- **第 15 點（Nether／End）**：選門 `CDI:41-46`：只對 SUPERPOSITION 的新選擇判斷（MEASURED 的 M4 收據照原本回 ALREADY_SELECTED，見「偏離」第 4 條），回 FAIL、actionbar 是翻譯 key `message.quantumchamber.m5.dimension_door_unavailable`（`CDI:55`），不寫 SELECTED。收據：SOURCE 照一般規則完成；NEW／EXISTING 不提交，非 DORMANT 時請求一次保留式 recovery（`CS:224-228`，開服 attach 通常已請求），一到 flushed DORMANT（而且 space 已釋放）就 `terminate(UNSUPPORTED_SOURCE_CANCEL)`，記 INFO「不支援的來源維度，已安全取消」（`CS:239`）。非 DORMANT 的這一段由 fix round 1 的 G-NLIVE 覆蓋（只覆蓋 NEW，理由見「Fix round 1」）。
- **第 16 點（RESOLVE stub）**：`CS:333`：UNIVERSE 目的地停在 RESOLVE，每個 session 在本 instance 只 WARN 一次，不配置、不寫 SEALED。G-RES 用 `begin`／`complete`，斷言 catalog 增量 0、allocation 與 projection ledger 的檔案 bytes 不變；M-RESOLVEWARN 證明「只一次」有被鎖住。
- **第 17 點（F3c 交接）**：G-RES 放在 `M5UniverseGameTests`（`m5_universe_resolve_stub`），以 `awaitConvergedThenComplete(context, 0, 20)` 收尾，tickLimit 400；故障 hook（`m5FlushProbe`）一律以 TestHooks 在所有路徑上釋放；斷言只看增量（ticket、record、WARN 都以 session 篩選）。注入失敗的 test 都以 server tick 計算退避窗口（G-FB）。

決策 1 的順序（`QuantumSuperpositionMod:41`、`:49`、`:52`、`:53`）：`SuperpositionSessionManager.initialize()` → `CollapseAuthorities.registerEvents()` → `CollapseService.initialize()`（`:52`）→ `UniverseLifecycleService.initialize()`。Fabric 依註冊順序執行，所以：
- `SERVER_STARTED`：journal 的 `requireHealthy`、session 與 recovery 的 attach、M5 authority 的初始化都在 `CS:111` 之前；CS 記下開服時 journal 裡的 session（第 9、10 點的「開服之後第一次」）。
- `END_SERVER_TICK`：`ChamberControllerLoadSyncQueue`、`CorridorPageManager.tick`、`SuperpositionSessionManager.tick`（含 recovery）都在 CS 之前；CS 看到的是本 tick 返還與清理寫入之後的 flushed。
- `CorridorPageManager.operationInProgress` 為真的 session 一律略過（`CS:172`），隔離中的 session 也略過；有未 checked 殘留時（`recoveryWritesSafe` 為假，`CS:137`）整個 tick 不寫入。

決策 7（`MIX:23`）：`CS:170` `step` 的開頭被 hook 取消時，這個 session 完全不推進（不提交、不 fallback、不終局）。testmod 的集合不得清除（`M4C:38`，沒有移除的 API）；default run 中會產生 MEASURED 的 test 都在交出 fixture 之前加入（`nativeCandidates` `M4C:1076`、F3a fixture `M4C:1972`、DORMANT 收據的 test `M4C:558`）；`m4Recovery` run 以 `-Dquantumchamber.test.collapseSuspended=all` 暫停全部（`build.gradle:103`）。

### 測試

- JUnit：560 → 567（2 skipped，只在 `NonWindowsPlayerCheckpointStoreTest`）。`cc936d0` 的 2 個（見上表）；`CST` 5 個：
  - `collapsePhaseFollowsReturnReleaseResolveThenTheGate`（`CST:59`）：未返還 → RETURN_PLAYERS；有 lease 或 space 未釋放 → RELEASE_GEOMETRY；UNIVERSE 未確定 → RESOLVE；之後依閘門 PREPARE／WAITING，而且閘門只在前面都成立時才被呼叫；非 COLLAPSING 丟例外。
  - `destinationIsFixedAtCommitFromTheSelectedCandidate`（`:90`）、`unsupportedSourceIsOnlyNetherOrEndWithNewOrExisting`（`:106`，3 role × 3 種候選）、`entryWatchTriggersOnlyOnEntryEdges`（`:115`）、`f3dMessagesExistInBothLanguages`（`:136`，兩個 key、zh_tw 逐字、兩個語言檔 key 集合相同）。
- default GameTest：160 → 169（`_windows` 80 → 86，per-test Universe receipt 38 → 47）。新增 9 個，都在 `M5CollapseGameTests`、各自一個 batch，每個都呼叫 `M4PerTestUniverseProbe.begin`／`complete`：
  - G-SRC `native_source_selection_collapses_returns_the_cohort_and_opens_the_door_windows`（`M5C:268`，`m5_collapse_source_live`）
  - G-RF `native_return_write_fault_retries_only_the_same_write_then_settles_windows`（`M5C:359`，`m5_collapse_return_roll_forward`）
  - G-OFF `native_offline_participant_rejoins_and_returns_while_the_collapse_holds_windows`（`M5C:441`，`m5_collapse_offline_rejoin`）
  - G-ELSE `native_returned_participant_starts_elsewhere_and_only_the_active_session_returns_him_windows`（`M5C:510`，`m5_collapse_returned_elsewhere`）
  - G-DOR `dormant_source_receipt_resumes_only_on_a_powered_enabled_entry_edge`（`M5C:572`，`m5_collapse_dormant_edge`，跨平台）
  - G-FB `native_commit_failure_falls_back_to_dormant_and_backs_off_windows`（`M5C:691`，`m5_collapse_commit_fallback`）
  - G-UNSUP `nether_and_end_universe_receipts_are_cancelled_once_dormant`（`M5C:760`，`m5_collapse_unsupported_cancel`，跨平台）
  - G-PASS `startup_passage_ready_ends_and_normalizes_the_door_next_tick`（`M5C:806`，`m5_collapse_startup_passage`，跨平台）
  - G-NETHER `nether_side_doors_are_refused_and_an_m4_source_receipt_still_completes_windows`（`M5C:852`，`m5_collapse_nether_door`）：在 Nether 的 (24008, 100, 24008) 強制載入涵蓋的 chunk，外圍以石頭圍住（隔開岩漿與地形），蓋一座真原艙（`M5C:954-987`），一位玩家真入場。結束時解除強制載入；已註冊的原艙比照其他原生 test 留在原地、只斷電。
- M5 GameTest：4 → 5。G-RES `dormant_universe_receipt_commits_then_holds_at_the_resolve_stub`（`M5U:214`，`m5_universe_resolve_stub`），catalog 增量 0。
- `_windows` 的原生 test 需要 Windows 的原生玩家 checkpoint，非 Windows 在 per-test baseline 之前完成；跨平台的 3 個不需要。
- 原生入場的走廊要建很多個 tick（GameTest server 不以 20 TPS 節流），等待入場的上限一律 20000 tick（`M5C:996`）。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3d/`；gate root 在 `.superpowers/verification/`。取證用的暫時性改動都由 `f3d/adapter.py` 套用與還原（套用前保存原檔、還原後以 `git hash-object` 核對），各 adapter 的 diff 在 `f3d/adapters/<名稱>/`。`git diff 438c306 93df897 -- <path>` 輸出的 SHA-256 前 8 碼（`f3d/diff-hashes.txt`）：`src/main` `e019c074`、`src/test` `528ac11e`、`src/testmod` `50178f75`、`build.gradle` `d60609fd`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（`cc936d0` 的 JUnit） | `438c306`，工作樹是 `cc936d0` 的 production 加上新 test，`ChamberReturnPlacement` 是舊版（`f3d/a-red-junit-state.txt`、`a-red/`） | Gradle `test`（`a-red/red-junit.log`） | `returnSlotsSkipBlockedCellsThenRaiseOrFallBackDeterministically` FAIL（落點在石頭上）；`FailureTextTest` 的 null reason test 以 mutation A-1（reason 為 null 時改用帶檔名的 message）證明（`a-mut/`） |
| focused（`cc936d0`） | 同上，工作樹＝`cc936d0`（`a-focused-gametest-state.txt`） | `gate-f3d-a-focused-d1d1e521419042fc84572a1816385900` | PASS 12/12（H4、H3、F3a G1～G3 與 M2 的 drain 使用者）；JUnit 全部通過（`a-junit-all.log`） |
| RED JUnit | `cc936d0`；adapter `junit-red`（`CP` 一律 WAITING、Destination 一律 SOURCE、`unsupportedSource` 一律假、`EntryWatch` 不回傳、移除兩個語言 key，語言檔因此等於 HEAD）；`f3d/c3-red-junit-state.txt` | Gradle `test --tests …CollapseServiceTest`（`c3-red/red-junit.log`、XML） | 5/5 在各自的斷言 FAIL |
| RED GameTest（第 1 輪） | `cc936d0`；adapter `tick-noop`（`CS.tick` 一開頭就 return，服務照常註冊）；`c3-red-gametest-state.txt` | `gate-f3d-red-c3-0f47ef9470fa46f8bb5e0e1748b9d6ca` | 保留不採計：G-DOR 在 (e) 放第二份收據時被 journal 以「來源艙已有 session」拒絕（fixture 失敗，不是目標斷言）。修正為 (a)～(d) 先斷言；另把 G-RF、G-FB 的第一個斷言提前到選門後的下一個 tick，G-NETHER 在 DORMANT 收據應已提交的 tick 先斷言（不再等到逾時） |
| RED GameTest（第 2 輪） | 同上，test 已修正（`c3-red-gametest-2-state.txt`） | `gate-f3d-red-c3b-e0e5aa2bbbd54f64bb70225a754b6d55` | 8/9 在目標斷言 FAIL（`c3-red/red-gametest-2-summary.txt`）：G-SRC `committedNextTick=false`；G-RF「應已 checked COLLAPSING」；G-OFF `collapsingHoldsForTheOffline=false`；G-DOR (a)～(d) 的 `entryEdgeCommits=false`；G-FB `attempts=[]`；G-UNSUP `cancelled=false`；G-PASS `endedAtStartup=false`；G-NETHER `entryEdgeCommits=false`。G-ELSE PASS：它驗的是 F2a 的唯一性規則與 SSM 的斷線處理，CS 不參與，以 M-AVAILABLE 證明覆蓋 |
| RED M5 | 同上 adapter；`-Mode M5GameTest`，onlyBatches `m5_universe_resolve_stub`（`c3-red-m5-2-state.txt`） | 第 1 輪 `gate-f3d-red-c3-m5-042712b601474bbdad6de0ae8bc6633d`；第 2 輪 `gate-f3d-red-c3-m5b-3f42d7783d364756a82b1044acf54044` | 第 1 輪保留不採計：witness 在 MEASURED 時讀 `collapse().destination()` 得到 NPE（test 的缺陷）；加上狀態判斷之後，第 2 輪在目標斷言 FAIL：`entryEdgeCommits=false`、`holdsAtResolve=false`、`oneResolveStubWarn=false` |
| RED Nether | `cc936d0`；adapter `nether-open`（`CDI` 的判斷恆假）；onlyBatches `m5_collapse_nether_door`（`c4-red-gametest-2-state.txt`） | 第 1 輪 `gate-f3d-red-c4-a77dc751b4fd44f8881542a2e1266c77`；第 2 輪 `gate-f3d-red-c4b-ed2b5b9211244ce8a404117e272de8a2` | 第 1 輪保留不採計：SOURCE 門被選中之後，test 才找 NEW 門，此時已沒有可選的門（fixture 失敗）；改為先找好兩扇門。第 2 輪在目標斷言 FAIL：`sourceDoorFails=false`、`dimensionMessageEachTime=false`、`noSelectionWritten=false`、`notFrozen=false`、`corridorWalkable=false`，keys＝［量子候選已鎖定，等待塌縮、候選已鎖定。］ |
| mutation | 工作樹（`mut-base-state.txt`、`mut-base-2-state.txt`、`mut-base-3-state.txt`）；每次只套用一個，跑完還原並核對 blob | 下表 | 下表 |
| GREEN Full | 工作樹（`green2-state.txt`），只有 `M5U` 一行的 NPE 防護與 commit 不同（見下） | `gate-f3d-green-full2-8aad108b6dab41cab979a2db0ce7102b`（`green2/full-summary.txt`） | PASS：default 169/169（`_windows` 86），0 failure／error／skipped；boundary PASS；`scope.json` PASS（宣告 default 169／legacy 1／M5 5）；per-test receipt 47/47；JUnit 567，0 failure／error，2 skipped；release 365 entries／318 classes（新增 `CollapsePhase`、`CollapseService` 與它的 `$1`、`$EntryWatch`、`$PendingDoor`、`$Track`）、sources 212（新增 2 個 `.java`）；testmod 命中 0；world exact restore |
| GREEN M5 | 工作樹（`green3-m5-state.txt`），blob 與 `93df897` 相同 | `gate-f3d-green-m5-3-72e79441961149c7a01e033a27e826f5` | PASS 5/5；`m5-oracle.json` PASS（G-RES 1 → 1，expected 0、converged、preserved） |
| GREEN M5（第二組，不採計） | 同 GREEN Full（`green2-state.txt`，`M5U` 還沒有 NPE 防護） | `gate-f3d-green-m5-2-2e36b6caad9442988991f16036c1b5fa` | PASS 5/5（14:24）。不採計：之後 `M5U` 加上 NPE 防護（見表後第 1 條），M5 run 只執行 `M5U`，所以以最終 blob 重跑為上一列。fix round 1 補列（m-5） |
| GREEN Legacy | 同 GREEN Full | `gate-f3d-green-legacy2-a8def7f63b63439081499fe6571edee0` | PASS 1/1 |
| GREEN Main＋oracle | 同 GREEN Full | `gate-f3d-green-main2-6f0defaab642474289f5cfea297e7d66`（`green2/main-oracle.log`） | PASS；main-oracle PASS（mod list、classpath／argfiles／DLI、class-load 都沒有 testmod；exact restore） |
| M4 recovery | 同 GREEN Full（`m4-recovery-state.txt`） | `f3d/green2/m4-recovery/recovery-run-9dbb81613e38475ab2a2c46ae770097c.json` | PASS 24/24 phases。各 phase 的 server log 都沒有任何塌縮流程的訊息（`塌縮已提交`、`SOURCE 塌縮完成`、`不支援的來源維度`、`塌縮流程`），hook 生效 |
| commit 檢查（`33624ad`） | 工作樹＝`33624ad`（C4 的 5 個檔換成 C3 版本，`c3-commit-state.txt`；版本由 `c3-snapshot/make.py` 產生） | Gradle `test`（`c3-commit/junit.log`）；`gate-f3d-c3-commit-focused-501b035dfd6a40509a90ae1159481516` | JUnit 567 全部通過（語言 test 是 C3 的 `collapseMessageExistsInBothLanguages`）；focused 14/14 PASS（F3d 的 8 個 batch 與 hook 下的 6 個 M4／F3a batch） |
| run-all | `93df897`，`STATUS` 為空（`f3d/run-all-console.log`） | `run-all-ff25dbc9e87749c2a09b599ad2ae5b71` | `END ALL exit=0 HEAD=93df897289c5cd51ddd485cefb0028e1ba4b492b`，約 17.5 分鐘（14:39:33→14:57:05）。`SUMMARY default=169/169 windows=86 legacy=1/1 m5=5/5 perTest=47+1 junit=567 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=365entries/318classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- GREEN 的 Full、Legacy、Main 與 M4 recovery 跑的是 `M5U` 加上 NPE 防護之前的版本（blob `3a23d7c`）；之後只改了 `M5U:259-260` 的一個條件（先判斷狀態再讀 Destination），所以 M5 GameTest 以最終 blob 重跑（GREEN M5），其他 gate 不受影響（`M5U` 只在 M5 run 執行）。`33624ad`、`93df897` 的 15 個檔，commit 時的 blob 與 GREEN M5 之前記錄的相同（`green3-m5-state.txt`）；`cc936d0` 的 8 個檔在所有 gate 期間都是 HEAD。run-all 在 commit 之後以 `93df897` 全部重跑。
- 第一組 GREEN（`gate-f3d-green-full-1f7748eb414143c9ae447fbc8d49ddc2`、`gate-f3d-green-m5-7db7d95a1a3c4d89a78f569607baef51`、`gate-f3d-green-legacy-0cd68855a2df4ecba58c8d85ba387a8d`、`gate-f3d-green-main-36985169b28646e18e6b7684f304c1d0`）都 PASS，但之後 G-RF 加上清理期間的開門，所以改以上表的第二組採計。
- 開發期間的 GameTest（`gate-f3d-dev1-31cc8084cc7246ab8e8ac8f203d21435` exit 1：原生入場的等待上限 600／400 tick 太小，改為 20000；`dev2`、`dev3`、`dev4-m5` PASS）不採計。
- log（以 `/ERROR]`、`/WARN]` 行計）：GREEN Full ERROR 28、WARN 50。F3c 的 Full 為 28／45；多出的 5 則都來自 F3d 的 batch（逐 batch 歸屬見 `green2/full-warn-by-batch.txt`）：G-FB 2（提交失敗的兩種原因）、G-RF 2（返還 pending，與 F3a 既有的「COLLAPSING 保留 receipt 等待安全返還」）、G-ELSE 1（B 斷線時 M2 既有的「入場交易保留 RETURNING」）。第一組 GREEN Full 多了 1 則 `m2_task4_disconnect_io` 的「艙體 … 的 session 協調失敗，保留返還保護」，這則在 H2 與 F3b fix round 1 的 Full 也各出現過一次，屬既有的偶發。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

mutation（GameTest 都用 `-Mode GameTest` 加 onlyBatches，M-RESOLVEWARN 用 `-Mode M5GameTest`；摘要在 `f3d/mut/<名稱>-summary.txt`）：

| Mutation | 改動 | Root | 結果（目標檢查） |
| --- | --- | --- | --- |
| M-HOOK | `MIX` 不再取消 | `gate-f3d-mut-m-hook-078a3df517744e69a99262ec442e0230` | 6 個 hook 下的 M4／F3a test 中 2 個 FAIL：`native_selection_all_25_cells_first_wins_and_measured_freeze_windows`（MEASURED 應停止 publish／remap）、`native_measured_checkpoint_failure_backs_off_then_reaches_dormant_windows`（`dormantAfterRecovery=false`、`singleWarnWithStack=false`） |
| M-RETRY | 拿掉 `SRM:156` 的退避 | `gate-f3d-mut-m-retry-d4d8fe1ecd27437c8b09cd11c694f44f` | G-RF `noRetryBeforeBackoff=false`（失敗的下一個 tick 就重試） |
| M-SETTLED | `CP:33` 不看 `releaseComplete` | 第 1 輪 `gate-f3d-mut-m-settled-359f52d8d32647aea327a1a951b9c9c1`；第 2 輪 `gate-f3d-mut-m-settled-6a1941f4eb2e48d1bebef0e1d2a08d2e` | 第 1 輪 G-RF 以「返還確認之後沒有完成清理：record=ABSENT」逾時（record 在同一個 tick 就終局，等不到 SETTLED）；test 改為 record 消失也停下取證，第 2 輪 `settledWhileSpaceRemains=false`、`noSpaceSlotOrTicketLeak=false` |
| M-CAUSE | 返還 pending 的 WARN 不附 throwable | `gate-f3d-mut-m-cause-c9b538230bad494bb2529d0669f35450` | G-RF `oneWarnWithCause=false` |
| M-TICKET | 終局之後不呼叫 `releaseAfterTerminal` | `gate-f3d-mut-m-ticket-8d357ef8a9984c18a35a20d4bd6a5c5d` | G-SRC `presenceNone=false`、`runtimeAndTicketReleased=false`、`newSessionStarted=false`、`doorPlainOpen=false`（presence 不是 NONE，門不再套用） |
| M-EDGE | `EntryWatch` 改成位準 | `gate-f3d-mut-m-edge-d384cf6a8d5b472881e9454d3237f991` | G-DOR `stayingInsideIgnored=false`、`noCommitYet=false` |
| M-POWER | 閘門不看即時紅石輸入 | `gate-f3d-mut-m-power-eaacde1646cc41fc839ee2208e7edd41` | G-DOR `unpoweredEntryIgnored=false` |
| M-OCCUPY | `occupies` 對塌縮狀態一律為真 | `gate-f3d-mut-m-occupy-09ff59fd57f34e60a538acd1b43fa3ad` | 不採計：journal 的唯一性驗證也用 `occupies`，fixture 在寫入時就被拒絕（「玩家跨 session 重複」）。改用 M-AVAILABLE |
| M-AVAILABLE | `participantsAvailable` 把任何參與者都算成佔用（只改這個查詢） | `gate-f3d-mut-m-available-775e05f9b1584c5793dc7eb4d5d76a8e` | G-ELSE 在「已返還的塌縮與 SEALED 參與者不佔用 P」FAIL |
| M-LOOKUP | `SRM.recordFor` 不看 `occupies` | `gate-f3d-mut-m-lookup-bd3bebf72fd14d54baa3b0c9df5c541c`、`gate-f3d-mut-m-lookup-ee2b7a39a9b24c42b032f59270f64419` | G-ELSE 兩次都 PASS（見「觀察」第 1 條）；F3a 的 `collapse_states_attach_without_returning_and_lookup_only_occupying_sessions` FAIL（`sealedReturnedJoinNotQueued=false`） |
| M-DOORNOW | `CS:370` 的延遲改為 0 | `gate-f3d-mut-m-doornow-97e8e4d0f082408190890790e9dc171a` | G-PASS `doorNotYetWritten=false` |
| M-NETHERSOURCE | `unsupportedSource` 對 SOURCE 也成立 | 第 1 輪 `gate-f3d-mut-m-nethersource-ed58292cc21440bb8f56f527b0d32ab0`；第 2 輪 `gate-f3d-mut-m-nethersource-00141cfbbd4d4ae9a109bfc072b38866` | 第 1 輪 G-NETHER FAIL（`entryEdgeCommits=false`），G-UNSUP PASS：journal 拒絕對 SOURCE 的取消，收據原樣留著，只多一則 WARN。G-UNSUP 改為同時斷言 SOURCE 收據沒有 WARN、沒有取消的 INFO；第 2 輪兩個都 FAIL（G-UNSUP `netherSourceKept=false`） |
| M-RESOLVEWARN | `CS:334` 不去重 | `gate-f3d-mut-m-resolvewarn-6a4288aae81f45e585b9dd4346000237` | G-RES `oneResolveStubWarn=false`、`noOtherWarnOrError=false` |

- mutation 跑在採計的 GREEN 之前；之後 test 的改動只有 G-RF 的清理期間開門、G-ELSE 的提前斷言、G-UNSUP 的 SOURCE 收據條件與 `M5U` 的 NPE 防護，都不影響各 mutation 的目標檢查。每次跑完的 blob 都與 `mut-base*-state.txt` 相同。

### 偏離 plan／spec 字面之處

1. **commit 拆成三個**：`cc936d0`（終局 API 與返還的修正，補充第 2、4、12、13、14 點）、`33624ad`（CollapseService、hook 與 M4 test）、`93df897`（Nether／End 的選門）。plan Step 4 的 commit 訊息用在 `33624ad`。`33624ad` 的 C3 版本另以 JUnit 與 focused GameTest 驗證（上表「commit 檢查」）。
2. **「下一 tick COLLAPSING」**：CS 掛在 `END_SERVER_TICK`，選門（玩家互動封包或 test callback）在同一個 tick 的前段處理，所以提交發生在選門那個 tick 的結尾，guard 已閒置；下一個 tick 開始時看到的就是 checked COLLAPSING。spec §7.1「選擇之後的下一個 tick」以「下一個 tick 可見」解讀；若要求提交本身延到下一個 tick 的結尾，需要在 Track 記下選門的 tick（見「需要裁示」第 2 條）。
3. **閘門的評估方式**：spec §7.3「不成立時停在 WAITING，之後依 §7.1 的邊緣觸發再評估」。實作是：本 instance 第一次推到閘門階段時以位準評估一次（live 返還之後通常立即成立；開服之後即「第一次觀察」），之後只在邊緣觸發時評估（`CS:313-331`）。進入 PREPARE 之後參與者在艙內不是持續條件；來源艙的條件不再成立就退回 WAITING（`CS:314-317`）。
4. **Nether／End 的判斷只看 SUPERPOSITION**：spec §11.3 寫的是「新的選擇」。MEASURED 的 M4 收據在側門互動時照原本回 ALREADY_SELECTED（不寫入），不改成「此維度的宇宙門尚未開放。」。
5. **G-RES 用 DORMANT 收據觸發**：plan 的 UNIVERSE 過渡測試沒有指定觸發方式。live 選 NEW 門需要原生 checkpoint（只有 Windows），而 M5 run 要求跨平台可跑，所以用真原艙上的 DORMANT NEW 收據與邊緣觸發；提交之後的路徑與 live 相同。
6. **`m5FlushProbe` 放在 `M4CandidateDoorGameTests.beforeCandidateFlush`**：M5 test 需要在 journal flush 開頭注入失敗。既有的呼叫點是 `SessionJournalWindowFaultMixin` → `beforeCandidateFlush`，mixin 不在允許路徑內，所以在 `M4C:1325` 加一行轉呼叫，只在 server thread 上生效，由各 test 以 TestHooks 清除。
7. **`revertCollapseForTeardown` 保留**（補充第 2 點寫「若能改走正式路徑，一併刪除」）：沒有 C→M 的正式路徑。
8. **M-OCCUPY、M-LOOKUP**：G-ELSE 的覆蓋以 M-AVAILABLE 證明；`SRM.recordFor` 的覆蓋由 F3a 的 test 提供（見上表與「觀察」第 1 條）。

### 需要裁示

1. **「量子態塌縮。」顯示兩次**：spec §10.8 的「塌縮」訊息在提交時顯示（`CS:281`），§7.4 第 3 步「對參與者顯示訊息」沒有指定內容，目前也用同一則（`CS:351`）。SOURCE 的玩家因此在提交與開門時各看到一次。選項：(a) 維持；(b) fast path 不再顯示；(c) fast path 改用另一則（例如通道結束的「量子通道已關閉。」，但 SOURCE 沒有通道）。→ **使用者 2026-10-02 確認維持**（(a)，controller 轉達），不改程式。
2. **live 提交的時點**：見「偏離」第 2 條。目前的解讀不需要改程式；若裁定為「下一個 tick 的結尾才提交」，CS 需要記下選門的 tick，G-SRC 等 test 的時點同步順延一個 tick。→ **使用者 2026-10-02 確認維持**在選門那個 tick 的 `END_SERVER_TICK` 提交（controller 轉達），不改程式。

### 觀察（未修改）

1. G-ELSE 在 M-LOOKUP 下兩次都 PASS。B 進入 RETURNING 不只經由 `SRM.recordFor`：P 斷線之後，B 的 runtime 在 tick 中檢查來源身分與 cohort 效果失敗，走 `SSM:286-291` 的 `fail`（G-ELSE 的 batch 因此有一則既有的「入場交易保留 RETURNING」WARN）；而且 `flushedRecords()` 是 `Map.copyOf`，迭代順序每個 JVM 不同。G-ELSE 斷言的是整體結果（B 進入 RETURNING、A 與 A′ 沒被改寫），查找本身在 F3a 的 test 有直接的斷言（M-LOOKUP 下 FAIL）。
2. `CS:83` 的待套用門以 chamber 為 key，只存在記憶體：終局之後、門還沒套用成功之前重啟，門會停在終局前的狀態（F3d 只會留下一般關門或一般開門）。spec §7.3「門狀態的重新套用」由 collapse view 投影，終局之後沒有 record 可投影；是否需要開服時的正規化，交給 F5（沒有所屬 session 的 membrane 正規化）一併處理。fix round 2 更正：從 F4b 起，「先終局、再改門」之間 crash 會留下 sealed 量子膜，所以改交給 F4b（見「交給後續」）。
3. 原生 test 的走廊建置在 GameTest server 上需要上千個 tick；`dev1` 用 600／400 tick 的上限全部逾時在 ARMING。之後一律以 20000 tick 為上限（`M5C:996`），實際約 1～3 秒。
4. G-NETHER 在 Nether 強制載入 4 個以內的 chunk，結束時解除；已註冊的 Nether 原艙與外圍石牆留在 fresh 世界裡（每次 gate 都是 fresh 世界）。
5. `m4-recovery-probe` 的 phase 證據（JSON）沒有記錄 JVM 的指令列，所以「hook 生效」以 `build.gradle:103` 與 server log 中沒有任何塌縮流程訊息證明。

### 交給後續

- F4a：
  - 以真正的 RESOLVE 取代 `CS:333` 的 stub（`CS:300` 的分支），並在提交時依 §7.2 先查 allocation ledger（已有 entry 時用 entry 的 UniverseId；目前 `CS:211` 只用推導值）。
  - 進入 SEALED 之後呼叫 `SSM:519` `releaseOnSealed`；SEALED 的重試（同一 instance 內 S→C）要自行取得來源 ticket（`SSM:531`），`CS:178` 目前不處理 SEALED。
  - `retryable()`／`restartOnly()` 的分流照派工不在本 task。
  - （fix round 2，quality m-6(c)）開工時先抽出 `M5GameTestSupport`：`collapseFixture`／`cleanupFixtures` 在 `M5C` 與 `M4C` 各有一份；`at`／`whenTicks` 與 `M4C` 的 `whenWithinTicks` 重複；Nether 原艙的建置在 G-NETHER 與 G-NLIVE 各寫一次；`SourceDoor` 的名稱與用途不符（它也裝 NEW 門）；`M5U` 直接借用 `M5C` 的 helper。
  - （fix round 2，A2）進入 SEALED、`Crossed` 等 roll-forward 寫出失敗，已由 `SessionRecoveryManager.recoveryWork` 交給 recovery 以 journal 為單位帶退避重試；F4a 與 F5 不要另做重試，只要在 roll-forward 未確認時暫停（`CS` 的 `collapsing()` 已經如此）。SRM 的 RETRY_FLUSH 失敗 WARN 文字仍是「返還保留 pending」，F4a 改成不限返還的文字。
- F4b：UNIVERSE 的 PREPARE（`CS:301-304` 目前只處理 SOURCE）、量子膜的套用、PREPARE 期間的門攔截（排在 `ChamberDoorService` 之前）、PREPARE 中途閘門失效時釋放目的世界 ticket 並把門改為一般開門（`CS:314-317` 目前只退回 WAITING）。
  - （fix round 2，quality m-6(b)）PREPARE→WAITING 的轉換藏在有副作用的 `gate()` supplier 裡；改成回傳明確的轉換結果，才能在轉換點釋放目的世界 ticket、把門改為一般開門。
  - （fix round 2，quality m-7(b)）待套用的門只存在記憶體（「觀察」第 2 條）。從 F4b 起，「先終局、再改門」之間 crash 會留下 sealed 量子膜；spec :613 的 bulkhead 正規化就是處理這種情況，F4b 要確認它涵蓋這個窗口。
- F5：PASSAGE_READY 的 runtime 產生與通道結束；`CS:357` 只處理開服時就是 READY 的 record。READY 提交之後的 Controller 刷新可沿用 `CS:451`。（終局之後的門正規化在重啟時遺失的情況，fix round 2 改交 F4b。）
- F6：
  - `build.yml` 的明列清單：本 task 新增 `_windows` 6 個（G-SRC、G-RF、G-OFF、G-ELSE、G-FB、G-NETHER），跨平台 3 個（G-DOR、G-UNSUP、G-PASS），M5 run 1 個（G-RES）；目前都由通則涵蓋。fix round 1 再加 `_windows` 1 個（G-NLIVE），fix round 2 再加 `_windows` 1 個（G-RFB）、跨平台 2 個（G-SEAL、G-TERM）。
  - 重啟矩陣：DORMANT 的「開服之後第一次觀察」與開服時的 PASSAGE_READY 目前以 `M4A:161` `markStartup` 模擬，真的跨 JVM 重啟屬 F6 的 harness。
  - （fix round 2，spec n-2、quality m-5）W10：以非 DORMANT 的 schema 3 fixture 加上真重啟，覆蓋 M4 時期的 Nether／End 收據（重啟時沒有 runtime、attach 已請求 recovery）；`markStartup` 對 production 狀態的反射寫入也由真重啟取代。
- F7：README 與 player guide 的 M5 行為（Nether／End 選門的訊息、SOURCE 的塌縮）；「需要裁示」第 1 條若改訊息，spec §10.8 一併更新。
  - （fix round 3，spec re-review m-3）spec errata 兩處：
    - §7 :332：改為「roll-forward 尚未確認時，由 recovery tick 以 journal 為單位帶退避，重試同一份 current；塌縮流程與走廊清理暫停」（fix round 2 的 A2：`SRM:192` `recoveryWork`、`CS:325`、`CPM:912`）。
    - §13.2 :758 的 SRM 列：註明 PASSAGE_READY 與 SEALED 在 roll-forward 待確認時，會重新寫出同一份 current（RETRY_FLUSH），這不是新的進度寫入。
- （fix round 3）之後新增「靠 journal 的 flush 探針計數、而且跨越 200 ticks 退避」的 GameTest，比照 G-SEAL、G-RFB 先以 `afterAutosaveClear` 等出沒有原生 autosave 的窗口，確認時以 `AutosaveWindow.untouched` 斷言期間沒有 autosave（見「Fix round 3」）；F4a 抽出 `M5GameTestSupport` 時一併搬過去。G-SEAL 的 `collapseFlowNeverWarned` 以 CollapseService 的 WARN 用語比對（「目的地解析尚未實作」、「塌縮流程」、「終局之後原艙門」）：F4a 以真正的 RESOLVE 取代 stub 時，新增的 WARN 要沿用 `warn()` 的「塌縮流程的…」前綴，或同步更新這個 helper。
- F4a（quality re-review 的 Minor 4 項，與抽出 `M5GameTestSupport` 放在同一個開工 step）：
  - m-1：`AutosaveWindow.untouched` 只偵測原生 autosave。另一條路也會繞過 flush 探針直接寫出 journal：`ChunkSaveCheckpoint.save`（`:50`）→ `server.save` → `PersistentStateManager.save` → `SessionRecoveryState.save(File,…)`。保留式清理的 checkpoint、F4b 的投影都會走這條路；前一個 batch 殘留 session 的 flush 也會被探針計數。
    - 建議一：用 testmod mixin 統計 `SessionRecoveryState.save(File,…)` 的所有寫出，斷言窗口內每一次都經過探針。
    - 建議二：改成「autosave 剛發生後立即開始」。
    - 上方「之後新增…比照 G-SEAL、G-RFB」那條建議，依此一併更新。
  - m-2：`collapseFlowNeverWarned` 以字串比對，失效時只會默默變寬鬆。三種情況會漏：stub 移除之後、新的 WARN 沒有用該前綴、`tickWarning` 不含 sid。建議 `CollapseService` 的 log 帶 SLF4J Marker（或用專屬 logger），witness 改為「此 sid 沒有任何帶塌縮 marker 的 WARN 或 ERROR」。
  - m-3：`DoorStep.warn` 由 JUnit 驗證，但 `applyDoor`（`CS:526-535`）沒有讀它，等價只靠兩邊都呼叫 `newWarning` 維持。改為由 `applyDoor` 依 `step.warn()` 決定是否記錄，或拿掉這個欄位。
  - m-4：G-SEAL 在確認那一個 tick，塌縮流程會看到沒掛 hook 的 flushed SEALED（PROJECTION_FAILED），要到下一個 callback 才收尾。F4a 實作 SEALED 重試時，探針在放行第 4 次寫出時順手 `suspendCollapse(sid)`，或確認邊緣觸發不會成立。
  - （fix round 3 spec re-review 的觀察）H2（`m5_h2_checkpoint_scope`）若有 autosave 落在兩次 checkpoint 之間，約有 1% 的機率多出一則原生 ERROR 或 WARN，斷言不受影響。比對 Full gate 的 log 數字時，可以先從這裡查。
- F4a 之後、有允許修改 mixin 的 task 時（fix round 2，quality m-6(d)）：把 `m5FlushProbe` 與 collapse-suspended 的靜態集合，從 `M4CandidateDoorGameTests` 移到 M5 專用的 hook 類別。
- `TerminalOrigin.preparing`（`SessionRecoveryState.java:385`）是 RETURN_PLAYERS／RELEASE_GEOMETRY 判斷的第三份副本（fix round 2 的 B6 只收斂了 `CollapsePhase`），留給之後允許修改 `SessionRecoveryState` 的 task。
- milestone 的 required manual smoke（fix round 1 的 m-3）：
  - R2 M6 的返還落點修正（`CRP:26`、`SRM:285`）依 `docs/implementation-notes/2026-09-29-m1-m4-integration-review.md:255` 需要重驗人工驗收清單的 A-2、B-1b、C-3a。本 task 只有 GameTest（G-SRC 的石頭）與 JUnit，沒有做人工重驗。
  - 新的落點邏輯在共用的 `SessionRecoveryManager.recover()` 裡，M2 的 RETURNING 返還（例如斷電、Buff 到期的一般返還）也會走它，不只 M5 的塌縮返還；人工重驗要涵蓋 M2 的一般返還，最好在艙內放裝飾方塊，確認返還的人不會卡在方塊裡。

### Fix round 1（`186b8f7`）

- Review（首輪）：spec review ❌（Critical 0、Important 1、Minor 5），controller 指派本輪全部處理。開工 HEAD 是 `5009e59`，worktree 乾淨。
- Commit `186b8f7` `fix: guard live collapse commits and test the unsupported-source recovery`（parent `5009e59`）：`collapse/CollapseService.java`、testmod `M5CollapseGameTests.java`。`M4CandidateTestAccess.java`、`M4CandidateDoorGameTests.java`、`CorridorPageManager.java` 沒有改。本小節由其後的 docs commit 加入；同一個 commit 也更新本節的 Review 行、對照表、「需要裁示」、Gate 表與「交給後續」。
- Code range `5009e59..186b8f7`。本小節的行號以 `186b8f7` 為準（簡稱同前）。

| 項目 | 處理 |
| --- | --- |
| I-1：§11.3 第 1 步（非 DORMANT 的 Nether／End NEW／EXISTING 收據不提交，先以 M4 的保留式 recovery 到 DORMANT）沒有 test | 新增 G-NLIVE `nether_live_new_selection_recovers_to_dormant_then_cancels_windows`（`M5C:984`，batch `m5_collapse_nether_live_cancel`）。在 Nether 的另一處（24200, 100, 24008）蓋真原艙，一位玩家真入場；繞過 `CandidateDoorInteraction`，照它的 guard 與檢查（`exclusiveOperation` → `candidateSelectionRecord` → `candidateSourceAuthorized` → `CandidateLedgerService.trySelect` → `candidateMeasured`）直接經 ledger 選 NEW 門，得到非 DORMANT 的 Nether MEASURED NEW。斷言：選門的 tick 還沒有人請求 recovery；下一個 tick 仍是 MEASURED、已請求保留式 recovery、記一則「不支援的來源維度：不提交 COLLAPSING」；之後玩家返還回 Nether 原艙、checked 寫成 DORMANT（journal 寫出探針記下 tick），再以 `UNSUPPORTED_SOURCE_CANCEL` 取消，記一則「不支援的來源維度，已安全取消」；presence NONE、runtime 與來源 ticket 已釋放、磁碟沒有 record；從頭到尾沒有任何一次寫出 COLLAPSING、沒有「塌縮已提交」、沒有「量子態塌縮。」、沒有 WARN 或 ERROR。catalog 不變由 per-test receipt 與 boundary oracle 核對。下一個 tick 的斷言之後移除玩家的 Buff：取消之後原艙仍供電、玩家回到艙內，有 Buff 就會依 M2 規則立刻開始新的 session，收尾會變複雜；這時返還已由塌縮流程的請求驅動，Buff 不影響本 test 要證明的事。只覆蓋 NEW：EXISTING 候選要 discovery 裡已有宇宙，default run 的世界沒有，建立它會改變 discovery 與 catalog；判斷 `CS:207` 對 NEW 與 EXISTING 相同，JUnit `unsupportedSourceIsOnlyNetherOrEndWithNewOrExisting` 逐一覆蓋 3 種 role × 3 種候選 |
| m-1：spec §7 :330「所有 journal 變更都在 session operation guard 之內」 | 依 controller 裁定：live 提交以 `pages.exclusiveOperation(sid, …)` 包住 `commitRestore`（`CS:264-271`）；提交失敗時 fallback 的 `requestMeasuredRecovery`（要求沒有進行中的操作）在 guard 釋放之後才呼叫（`CS:277`）。live（非 DORMANT）的 MEASURED 一定有走廊 space：`CorridorPageManager.restoreSpace` 只對 released 的 MEASURED 不建 space（`spaceless`），執行期間的 space 要到 release 完成才移除；`step` 開頭已確認 guard 閒置（`CS:175`），所以這裡不會撞上「已有進行中的操作」。DORMANT 續行、三種終局（SOURCE fast path、§11.3 的取消、開服時的 PASSAGE_READY）都在 space 釋放之後才發生（PASSAGE_READY 本來就沒有 space），沒有 guard 可取，維持「guard 忙就略過（`step` 開頭）＋在 server thread 上同步寫入」，寫在 class Javadoc（`CS:54-56`）。`CorridorPageManager` 沒有改，`exclusiveOperation` 只做 `operations` 的加減，不改 page 狀態。G-SRC 新增探針：MEASURED→COLLAPSING 那次 journal 寫出的開頭記下 `operationInProgress(sid)`，斷言恰為 `[true]`（`M5C:315`） |
| m-2：G-ELSE 的 Javadoc 說得過滿 | 改寫（`M5C:525-531`）：B 進入 RETURNING 主要經 SSM 的 cohort 檢查失敗走 `fail`（`SSM:286-291`），塌縮狀態的 record 不經斷線的 `markReturning` 寫入（`SRM:387`）；本 test 只斷言整體結果，「依玩家查找只找到佔用他的 session」的直接斷言是 F3a 的 `collapse_states_attach_without_returning_and_lookup_only_occupying_sessions`（M-LOOKUP 下 FAIL：`gate-f3d-mut-m-lookup-ee2b7a39a9b24c42b032f59270f64419`）。對照表同步 |
| m-3：R2 M6 的人工重驗沒有交接 | 「交給後續」新增一條，交給 milestone 的 required manual smoke（見下） |
| m-4：G-OFF 的 `returnedAfterJoin` 只檢查 `joined >= 0` | 重新連線之前裝一個 journal 寫出探針，記下這位玩家的返還第一次寫出時的 server tick；witness 改名為 `returnedOnTheTickAfterJoin`，斷言恰為 JOIN 的 tick＋1（`M5C:509`）。SRM 在 JOIN 時排定「下一個 tick」（`SRM:107`），返還在那個 tick 的 recovery 中寫出，所以能精確相等，不需要容許範圍。舊斷言只看 `joinTick` 有沒有記錄，與返還時點無關：下表的 FIX1-JOIN-LATE 讓返還晚兩個 tick，舊斷言仍會通過，新斷言 FAIL |
| m-5：note 的證據表漏列 `gate-f3d-green-m5-2-2e36b6caad9442988991f16036c1b5fa` | Gate 表補上這一列與不採計的原因 |

原「需要裁示」的兩項由 controller 轉達：「量子態塌縮。」維持顯示兩次、live 提交維持在選門那個 tick 的 `END_SERVER_TICK`，使用者 2026-10-02 確認維持，本輪不改程式（「需要裁示」一節已改為記錄）。

測試：
- default GameTest：169 → 170（`_windows` 86 → 87，per-test Universe receipt 47 → 48），只增加 G-NLIVE。
- JUnit 567、M5 GameTest 5、legacy 1，都不變。
- G-SRC 多一個 witness（`commitInsideOperationGuard`），G-OFF 的 `returnedAfterJoin` 換成 `returnedOnTheTickAfterJoin`；G-ELSE 只改 Javadoc。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3d/fix1/`（state 檔由 `fix1/state.ps1` 產生）；mutation 由 `f3d/adapter.py` 的 `fix1-*` 條目套用與還原，摘要在 `f3d/mut/fix1-*-summary.txt`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED m-1 | `5009e59`；工作樹只有 `M5C` 的改動（blob `db95913`，`fix1/red-guard-state.txt`），`CS` 是 HEAD | `gate-f3d-fix1-red-guard-1ac3ae54a5394177a1085ff6ab20a14b`（onlyBatches `m5_collapse_source_live`） | G-SRC FAIL，witness 只有 `commitInsideOperationGuard=false`（其他全 true） |
| GREEN focused | 工作樹＝`186b8f7`（`fix1/green-focused-state.txt`：`CS` `e756c3e`、`M5C` `db95913`） | `gate-f3d-fix1-green-focused-c7e9dd222fe24367896a0be1e719ce52`（F3d 的 10 個 batch 與 hook 下的 6 個 M4／F3a batch） | PASS 16/16，`scope.json` PASS。G-NLIVE 的 log：入場、「不支援的來源維度：不提交 COLLAPSING」、「不支援的來源維度，已安全取消（…候選=New）」，per-test receipt PASS（`fix1/green-focused-livecancel-log.txt`） |
| I-1 RED（a）FIX1-DORMANT-ONLY | 同 GREEN；`CS:193` 改為 `unsupportedSource(record) && phase == DORMANT`（判斷搬進 DORMANT 分支） | `gate-f3d-mut-fix1-dormant-only-830e36e214b44d1daf9b8d9326ea2cb9` | G-NLIVE 在下一個 tick FAIL：`notCommittedNextTick=false`、`retainedRecoveryRequested=false`、`oneNotCommittingInfo=false`，record=COLLAPSING（Nether 的 NEW 收據被提交） |
| I-1 RED（b）FIX1-NO-REQUEST | 同 GREEN；`CS:227` 的條件改為 `false`（非 DORMANT 時不請求保留式 recovery、不記 INFO） | `gate-f3d-mut-fix1-no-request-91dd8519d304448b91871468790d3e8e` | G-NLIVE 在下一個 tick FAIL：`retainedRecoveryRequested=false`、`oneNotCommittingInfo=false`，record=MEASURED |
| m-4 FIX1-JOIN-LATE | 同 GREEN；`SRM:107` 的 `+1` 改為 `+3` | `gate-f3d-mut-fix1-join-late-1779648974bc4891a6ed6d5cdaafcba2` | G-OFF FAIL：`returnedOnTheTickAfterJoin=false`，joinTick=1155、returnTick=1158 |
| run-all | `186b8f7`，`STATUS` 為空（`fix1/run-all-console.log`） | `run-all-ea6d979e6e6f428f9a78d413da31ac0d` | `END ALL exit=0 HEAD=186b8f71753c2db80fbbe64944e47dce74b87b00`，約 15.5 分鐘（15:34:38→15:50:10）。`SUMMARY default=170/170 windows=87 legacy=1/1 m5=5/5 perTest=48+1 junit=567 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=365entries/318classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- 三個 mutation 都是單獨套用，跑完還原；還原後的 blob 與 `fix1/green-focused-state.txt` 相同。commit 時 2 個檔的 blob 也與它相同。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

### Fix round 2（`fa9e39d`、`8f3d76c`）

- Review：quality review（`438c306..b82b2cf`，code HEAD `186b8f7`）Not approved（Critical 0、Important 1、Minor 7）；spec re-review（fix round 1）✅（Critical 0、Important 0、Minor 2：n-1、n-2）。controller 指派本輪：必修 A1～A3、一併處理 B1～B7、只寫交接 C。開工 HEAD 是 `b82b2cf`，worktree 乾淨。
- Commits（本小節由其後的 docs commit 加入；同一個 commit 也更新本節的「交給後續」與「觀察」第 2 條）：
  - `fa9e39d` `fix: retry every pending roll-forward with the journal backoff and harden collapse edges`（parent `b82b2cf`）：production `collapse/CollapseService.java`、`CollapsePhase.java`、`corridor/CorridorPageManager.java`（只有 A1 的判斷）、`persistence/SessionRecoveryManager.java`（只有 `recoveryWork` 與它的 Javadoc）、`transfer/ChamberReturnPlacement.java`；JUnit `CollapseServiceTest.java`、`ChamberReturnPlacementTest.java`；testmod `M5CollapseGameTests.java`。
  - `8f3d76c` `test: let a native autosave confirm the pending roll-forward in the backoff tests`（parent `fa9e39d`）：只有 `M5CollapseGameTests.java`，見下方 run-all 的第 1 輪。
  - `SessionRecoveryState.java`、`M4CandidateTestAccess.java`、`M4CandidateDoorGameTests.java` 都不需要改。
- Code range `b82b2cf..8f3d76c`。本小節的行號以 `8f3d76c` 為準（簡稱同前；`CPM` 的行號是 `CorridorPageManager.java`）。

| 項目 | 處理 |
| --- | --- |
| A1（quality I-1）：COLLAPSING 清 lease 寫出持續失敗時，CPM 每個 tick 重掃 lease、重寫整份 journal | 依裁定 (a)：`tickMeasuredRelease` 算出 `expected`、`current` 之後，`record.state()==COLLAPSING && expected.equals(current) && journal.rollForwardPending(space.id)` 時直接 return（`CPM:912`），確認交給 SRM 以 journal 為單位退避的 RETRY_FLUSH；確認之後既有的 spaceless 分支照常釋放 slot。MEASURED（M4）的逐 tick 重試沒有動。新 GameTest G-RFB `native_persistent_release_write_fault_waits_for_the_journal_backoff_windows`（`M5C:1104`，batch `m5_collapse_release_fault_backoff`）：前 3 次清 lease 的寫出受控失敗；斷言第一次失敗之後 200 ticks 內最多 2 次寫出（同一個 tick 的走廊清理與 RETRY_FLUSH），之後的間隔依退避（≥200、≥400）；確認之後 space、runtime、ticket 都釋放，門照常打開。G-RF（同一 tick 內 RETRY_FLUSH 成功）與 F3a G3 都在 GREEN focused 通過 |
| A2（quality m-7(a)）：SETTLED 之後的 roll-forward 沒有負責重試的元件 | 依裁定：`recoveryWork`（`SRM:192`）對 COLLAPSING 先看 `rollForwardPending`（為真回 RETRY_FLUSH，已 released 也一樣），PASSAGE_READY 與 SEALED 在 `rollForwardPending` 為真時回 RETRY_FLUSH、否則 NONE（`SRM:198`）。`recoveryWork` 的 Javadoc（`SRM:184-191`）與 `CollapseService` 的 class Javadoc（`CS:52-54`）改成同一個說法：三個塌縮狀態的所有 roll-forward 都只由 recovery 以 journal 為單位帶退避重試，塌縮流程與走廊清理只暫停。新 GameTest G-SEAL `settled_collapse_roll_forward_into_sealed_is_retried_by_recovery_with_backoff`（`M5C:1151`，batch `m5_collapse_sealed_roll_forward`，跨平台）：F2a 的 journal 允許 SETTLED COLLAPSING 以 `commitRollForward` 進入 SEALED（G-ELSE 的 fixture 已用過這條邊），但 SEALED 只能接在 UNIVERSE 目的地之後，所以 fixture 選 NEW（塌縮流程不暫停，SETTLED 之後停在 RESOLVE stub、不寫入）。test 直接寫入 SEALED、前 3 次寫出受控失敗；斷言 recovery 在退避之後真的重試、間隔 ≥200（第 4 次另要 ≥400）、確認的是同一份 SEALED、期間 current 一直是那份 SEALED、沒有任何塌縮流程的 WARN；收尾以終局 API 移除 |
| A3（spec n-1）：G-NLIVE 沒有證明「原艙解除封鎖」 | 取消之後斷言 Controller 已協調（`chamberState()` 不再是 session 期間的 ARMED）與 registry 的 power state 不是 RETURNING（`M5C:1083-1085`）。mutation 下只有 `controllerCoordinated` 會 FAIL：registry 在 session 期間一直是 POWERED（沒有斷電），所以 `registryNotReturning` 只是附帶的檢查 |
| B1（quality m-1）：`ChamberReturnPlacement` | `CRP:31` 起改寫：地板 → 上層（先選這一欄還沒有人的，再選任何一欄）→ 退路是第一個沒被取走的地板 slot（`CRP:49` `pick`）。最多 25 人、每人取走一個位置，輪到任何人時一定還有沒被取走的地板 slot，Javadoc 寫明結果不會重複。JUnit `returnSlotsNeitherStackNorRepeatWhenSomeCellsAreBlockedAndOthersTaken`（`ChamberReturnPlacementTest:61`）：地板只剩第一格時其他人站上一層、不在他頭上；唯一不被擋的地板被第一位取走之後，第二位的退路不重複 |
| B2(i)（quality m-2）：終局寫出失敗 | 新 GameTest G-TERM `source_fast_path_terminal_write_fault_backs_off_and_keeps_the_door`（`M5C:1213`，batch `m5_collapse_terminal_fault`，跨平台）：真原艙上的 DORMANT SOURCE 收據由邊緣觸發提交、走到 PREPARE；第一次終局（移除 record 的寫出）受控失敗。斷言兩次終局嘗試、間隔 ≥200、只有 1 則「SOURCE fast path 的終局」WARN、終局之前門一直是一般關門、之後門照常改為一般開門、presence NONE、runtime 與 ticket 已釋放 |
| B2(ii)：待套用門的 ROLLED_BACK | 改成 JUnit 層的等價證明。理由：`DoorWriteFault.during` 是 ThreadLocal scope，只能包住呼叫端自己的程式；fast path 的改門在 `CollapseService` 的 `END_SERVER_TICK` 裡，要在那裡包 scope 需要新的 mixin，不在允許路徑內。做法：`applyDoor` 的處置抽成純函式 `doorStep`（`CS:544`，回傳 `DoorStep`：完成、要記錄的原因、這次是否記 WARN、是否退避、之後的「已立即重試」狀態），WARN 去重抽成 `newWarning`（`CS:561`，`PendingDoor.warn` 用同一個規則）；`applyDoor` 照它做（`CS:526-533`）。JUnit `pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings`（`CollapseServiceTest:156`）：第一次被拒記 WARN、不退避、立即重試；再被拒退避、同一原因不再記；原因改變再記；DOOR_INCOMPLETE 一律退避而且去重；NOT_LOADED 只等待；APPLIED、UNCHANGED 完成。「最後門照常打開」由既有的 G-SRC 等覆蓋 |
| B2(iii)：live 提交的資格判斷 | `measured()` 的分流抽成純函式 `measuredAction`（`CS:222`，回傳 WAIT／CANCEL_UNSUPPORTED／RESUME_DORMANT／COMMIT_LIVE），涵蓋 current＝flushed、§11.3、DORMANT 分流、`measuredReleaseInProgress` 與退避。operation guard 忙碌、已隔離與 testmod 的 collapse-suspended hook 都在 `step` 開頭、呼叫它之前就略過，所以沒有搬進純函式（hook 是 testmod 的 mixin，分別由 M-HOOK 與 fix round 1 的 m-1 RED 鎖住），寫在它的 Javadoc。JUnit `measuredActionCommitsOnlyAnIdleSupportedLiveReceiptOutOfBackoff`（`CollapseServiceTest:137`）逐列 8 列 |
| B3（quality m-3）：log 斷言沒有依 sid 篩選 | G-OFF 的 ERROR 改依 sid、「session 協調失敗」改依 chamber（`M5C:492-493`）；G-DOR、G-NETHER、G-NLIVE 的 ERROR 改依各自的 sid（G-DOR 含開服那份收據） |
| B4（quality m-4）：`CollapseService` 的 log | (a) tick 恢復的 INFO 印 `describe`（`CS:166`，另存 `tickWarningText`）。(b) `warn` 多一個參數寫「之後實際會發生的事」（`CS:569`）：提交失敗（live：改走 M4 保留式 recovery、之後依退避重試；DORMANT：退避期過後、下一次觸發時重試）、終局失敗（之後依退避重試）、推進（下一個 tick 重試）、取得來源 ticket（本 server instance 不重試，開服 attach 時會為 COLLAPSING 重新取得）、終局之後刷新 Controller（不重試，Controller 在下次載入或紅石更新時協調）。(c) 推進成功時清除它的去重鍵（`CS:147-149`，F2a Q4） |
| B5（quality m-5）：G-FB 的 hook 寫死 `true` | 開場記下 `m5ReadyThisInstance` 的原值，hook 與測試中途的恢復都還原成它（`M5C:727-728`、`:763`）。`markStartup` 的反射維持現狀，交接 F6 以真重啟取代 |
| B6（quality m-6(a)）：RETURN_PLAYERS／RELEASE_GEOMETRY 的判斷有三份 | `CollapsePhase.derive` 改經 `MeasuredRecoveryPhase.from(view)`（`CP:35`），只多加「走廊 space 真的釋放」。第三份副本 `TerminalOrigin.preparing`（`SessionRecoveryState.java:385`）依派工不動：它是 journal 對終局前提的獨立檢查，留給之後有允許修改 `SessionRecoveryState` 的 task 再收斂 |
| B7（spec n-2）：G-NLIVE 的 Javadoc | 改為「record 形狀與 M4 收據相同；重啟時沒有 runtime、attach 已請求 recovery 的那條路徑屬 F6 的 W10」（`M5C:981`） |
| C：只寫交接 | 見本節「交給後續」新增的各條 |

測試：
- default GameTest：170 → 173（`_windows` 87 → 88，per-test Universe receipt 48 → 51），新增 G-RFB（`_windows`）、G-SEAL、G-TERM（跨平台）。
- JUnit：567 → 570，新增 B1、B2(ii)、B2(iii) 各 1 個。
- M5 GameTest 5、legacy 1，不變。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3d/fix2/`（state 檔由 `fix2/state.ps1` 產生）。RED 時把 5 個 production 檔換成 HEAD 版本（`fix2/swap.py`，先保存工作樹版本，還原後核對 blob）；mutation 由 `f3d/adapter.py` 的 `fix2-*` 條目套用與還原，摘要在 `f3d/mut/fix2-*`。

| 輪次 | 狀態 | Root | 結果 |
| --- | --- | --- | --- |
| RED B1（JUnit） | `b82b2cf`；新 JUnit、`ChamberReturnPlacement` 是 HEAD（`fix2/red-b1-junit-state.txt`） | Gradle `test --tests …ChamberReturnPlacementTest`（`fix2/red-b1-junit.log`、`.xml`） | 新 test FAIL 在「不在他頭上」：第二位站到 (5.5, 2, 1.5)，正在第一位頭上。退路重複由下方 FIX2-B1-OLD-FALLBACK 證明 |
| RED GameTest（第 1 輪） | `b82b2cf`；5 個 production 檔是 HEAD、test 是新的（`fix2/red-gametest-state.txt`） | `gate-f3d-fix2-red-4149bf3327ce4633b734d92b51392f8f` | G-RFB FAIL：`atMostTwoWithinTheFirst200Ticks=false`，writes=[2478, 2478, 2479, 2480]（走廊清理每個 tick 重寫）。G-TERM、G-NLIVE PASS（覆蓋既有行為，以 mutation 證明）。G-SEAL 在 fixture 失敗（「SEALED 的 Collapse 欄位組合不合法」：SOURCE 目的地不能進 SEALED），不採計，改選 NEW 後重跑 |
| RED G-SEAL（第 2 輪） | 同上（`fix2/red-gametest-2-state.txt`） | `gate-f3d-fix2-red-sealed-08f2aee4869d41cab7e88a9dab46f6a6` | FAIL：逾時，attempts=[45]、pending=true（沒有任何元件重試） |
| GREEN JUnit | 工作樹 | Gradle `test`（`fix2/green-junit.log`） | 570，0 failure／error，2 skipped |
| GREEN focused | 工作樹＝`fa9e39d`（`fix2/green-focused-state.txt`） | `gate-f3d-fix2-green-focused-1cbe45b049684af7ba3830e4983baad6`（F3d 的 13 個 batch 與 hook 下的 6 個 M4／F3a batch） | PASS 19/19 |
| FIX2-A1-NO-CHECK | 同 GREEN；拿掉 `CPM:912` | `gate-f3d-mut-fix2-a1-no-check-680fb2043d4c4f6ebd42efc72fd840f3` | G-RFB FAIL：writes=[1151, 1151, 1152, 1153] |
| FIX2-A2-NONE | 同 GREEN；已 released 的 COLLAPSING 改回一律 NONE | `gate-f3d-mut-fix2-a2-none-f0ca0c142a0b40398f468e9581f716d2` | G-SEAL FAIL：逾時，attempts=[39] |
| FIX2-A3-NO-REFRESH | 同 GREEN；拿掉 `cancelUnsupported` 的 `refresh(record)` | `gate-f3d-mut-fix2-a3-no-refresh-3a73eb712bd443f5abb0f93996405ab6` | G-NLIVE FAIL：`controllerCoordinated=false` |
| FIX2-B2I-NO-BACKOFF | 同 GREEN；終局失敗之後不退避 | `gate-f3d-mut-fix2-b2i-no-backoff-b29f73b4e89f4fb798106a9b4e48acb4` | G-TERM FAIL：`retryWaitedForTheBackoff=false`，attempts=[60, 61] |
| JUnit mutation | 同 GREEN；各自單獨套用（`fix2/junit-mut.txt`、`f3d/mut/fix2-*-junit.xml`） | Gradle `test --tests …` | FIX2-B1-OLD-FALLBACK（退路改回預設 slot）：B1 test 在「不重複」FAIL；FIX2-B2III-NO-RELEASE（不看 `releaseInProgress`）：`releasing=true` 那一列 FAIL；FIX2-B2II-ALWAYS-BACK-OFF（第一次被拒就退避）與 FIX2-B2II-NO-DEDUPE（不去重）：門的 test 各自 FAIL |
| run-all（第 1 輪） | `fa9e39d`，`STATUS` 為空（`fix2/run-all-console.log`） | `run-all-23b86f151dd241ee89c3b46033649feb`（Full：`gate-full-e8311af098714104afdc9ad38cf9000c`） | FAIL：Full 172/173，G-SEAL 的 `fourAttempts=false`，attempts=[101493, 101493, 101693]。第 3 次重試之後，原生 autosave 在 tick 102000（6000 的倍數）先把 SEALED 帶出；autosave 不經過 journal 的 flush 探針，所以只看到 3 次。G-RF 的 Javadoc 早就寫明確認可能由 autosave 帶出；G-SEAL 與 G-RFB 是新 test，沒有比照。`8f3d76c` 改為：要求 recovery 在退避之後真的重試過（3～4 次）、已發生的間隔依退避，不再要求恰好 4 次。RED 與 mutation 的失敗 witness（G-RFB 的 200 ticks 內次數、G-SEAL 的逾時）都不受這個放寬影響。放寬之後的 focused：`gate-f3d-fix2-green-focused-2-3f72ae6775094051b2121d16f87b5107` PASS 2/2 |
| run-all（第 2 輪） | `8f3d76c`，`STATUS` 為空（`fix2/run-all-2-console.log`） | `run-all-cfc41c7ebac1447f9f26b423a280d211` | `END ALL exit=0 HEAD=8f3d76c9878df28167e0bb4c313fbc144ef512f1`，約 15 分鐘（16:41:38→16:56:32）。`SUMMARY default=173/173 windows=88 legacy=1/1 m5=5/5 perTest=51+1 junit=570 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=367entries/320classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。release JAR 365／318 → 367／320：新增 `CollapseService$MeasuredAction` 與 `CollapseService$DoorStep`；sources 212 不變 |

- log（Full gate，以 `/ERROR]`、`/WARN]` 行計）：與 fix round 1 的 run-all（`run-all-ea6d979e6e6f428f9a78d413da31ac0d`）相比，ERROR 28 → 28，WARN 50 → 55（逐 batch 的比對在 `fix2/wbdiff.txt`）。新增的 5 則都來自本輪新增的 test：G-RFB 2（走廊清理的「COLLAPSING 保留 receipt 等待安全返還」、recovery 的「返還保留 pending」，各自去重只記一次）、G-SEAL 2（RESOLVE stub 的 WARN、recovery 的「返還保留 pending」）、G-TERM 1（「SOURCE fast path 的終局沒有完成」）。G-FB 的 2 則數量不變，只有 B4(b) 改過的後半句文字不同
- 每個 mutation 跑完都還原，還原後的 blob 與 `fix2/green-focused-state.txt` 相同；`fa9e39d` 的 8 個檔 commit 時的 blob 也與它相同。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

觀察（未修改）：
- SRM 的 RETRY_FLUSH 失敗 WARN 文字是「返還保留 pending」（`SRM:173`）。A2 之後它也涵蓋進入 SEALED、Crossed 的寫出，文字只說返還。本輪只允許改 `recoveryWork` 與 Javadoc，沒有動，交給 F4a。

### Fix round 3（`08dd4d7`）

- Review：spec re-review（fix round 2，範圍 `b82b2cf..675d53d`）❌（Critical 0、Important 1、Minor 3：I-1、m-1、m-2、m-3）。controller 指派本輪四項都處理，I-1 採 reviewer 的第一建議。原 implementer 的 context 過長，本輪由新的 implementer 接手。開工 HEAD 是 `675d53d`，worktree 乾淨。
- Commit `08dd4d7` `test: keep the sealed retry fixture away from RESOLVE and the native autosave`（parent `675d53d`）：testmod `M5CollapseGameTests.java`、新增 `mixin/MinecraftServerAutosaveTestAccessor.java`、`quantumchamber-test.mixins.json`（只登記這個 accessor）；JUnit `SessionRecoverySchema4Test.java`（只有 m-2 的一列）。production（`src/main/`）、`scripts/verification/`、`.github/` 都沒有改。本小節由其後的 docs commit 加入；同一個 commit 也更新本節的 Review 行與「交給後續」。
- Code range `675d53d..08dd4d7`。本小節的行號以 `08dd4d7` 為準（簡稱同前；`S4T` 指 `SessionRecoverySchema4Test`）。

| 項目 | 處理 |
| --- | --- |
| I-1：G-SEAL 違反決策 7（fixture 是沒掛 hook 的 SETTLED COLLAPSING、目的地 NEW，tick 2 才寫 SEALED；中間塌縮流程推導為 RESOLVE，記一則 RESOLVE stub WARN） | 依裁定（reviewer 的第一建議）：等出沒有原生 autosave 的窗口（m-1）之後，在同一個 callback 內建立 fixture（仍不掛 collapse-suspended hook）、裝 flush 探針、立即 `commitRollForward`（`M5C:1176-1197`）。全部發生在 test callback（`tickWorlds` 之內），塌縮流程在這個 tick 的 `END_SERVER_TICK` 第一次看到這個 session 時，roll-forward 已經未確認，`CS:325` 早退；之後一直到確認為 SEALED 都是如此，確認之後的 SEALED 不是塌縮流程處理的狀態（`CS:189`）。所以塌縮流程永遠推導不到 RESOLVE，F4a 接上真的 RESOLVE 之後也不會在 default run 配置宇宙。`collapseServiceNeverOverwrote` 沒有改：仍不掛 hook，它量的仍是塌縮流程自己的暫停（每個 tick 的 current 都還是那份 SEALED，而且沒有「塌縮流程」WARN），下方 FIX3-CS-NO-PENDING-PAUSE 下 FAIL。新 witness `collapseFlowOnlySawPendingRollForward`（`M5C:1213`，helper `collapseFlowNeverWarned` `M5C:1434`）：LogCapture 在建立 fixture 之前就開始，整個 test 期間這個 sid 沒有 CollapseService 的 WARN：RESOLVE stub 的「目的地解析尚未實作」、`warn()` 的「塌縮流程的…沒有完成」、終局之後改門的「終局之後原艙門…」 |
| m-1：原生 autosave 造成的誤判窗口（G-RFB、G-SEAL 的第 1、2 次寫出在同一個 tick，第 3 次在 200 ticks 之後；autosave 落在中間就只記到 2 次） | 見下方「原生 autosave 的排程」與「M5 GameTest 的掃描」。共用 helper `afterAutosaveClear`（`M5C:1520`）在開始注入之前等到「接下來 window 個 tick 內不會有原生 autosave」；`AutosaveWindow.untouched`（`M5C:1536`）在確認時斷言期間確實沒有 autosave。G-SEAL（`M5C:1176`）、G-RFB（`M5C:1124`）套用，窗口都是 800 ticks，各加一個 witness `noNativeAutosaveBeforeTheConfirmation`（`M5C:1207`、`:1131`）。因為窗口涵蓋到第 4 次寫出，兩者的計數恢復為 fix round 2 原本的恰好 4 次（`fourAttempts` `M5C:1208`、`fourWrites` `M5C:1132`，`retriesFollowTheBackoff` 也回到原本的寫法）；`8f3d76c` 的放寬只為了 autosave。G-SEAL 的 tickLimit 2000 → 3000（最多先等 801 ticks） |
| m-2：`recoveryWork` 對 PASSAGE_READY 與 SEALED 的新分支沒有測試 | `S4T:1046-1053`：在 `recoveryWorkDerivesCollapsingReturnFromTheReceiptAndOnlyRetriesUnconfirmedProgress` 的 cases 表補一列。flushed 是 PASSAGE_READY（由 settled 推得），以 `commitRollForward` 寫入加入 Crossed（A）的 self-loop，flush 注入 `UncheckedIOException`（同檔 COLLAPSING 那一列的作法），斷言 `ROLL_FORWARD_PENDING`，而且 `recoveryWork` 回 `RETRY_FLUSH`。F2a 的 journal 允許這條邊（`SessionRecoveryState.rollForwardEdge`：PASSAGE_READY 的 self-loop 是 roll-forward 類；同檔 `S4T:232` 的 edge 表列為合法），不需要改用 SEALED。這是既有 test 裡的一列，JUnit 的 test 數不變（見「偏離」第 2 條） |
| m-3：spec errata 的交接 | 「交給後續」的 F7 一條補上 §7 :332 與 §13.2 :758 兩處（本輪不改 spec） |

原生 autosave 的排程（m-1 第 1 點）：
- 看的原始碼：Loom cache 只有 Yarn mapped 的 jar，沒有 sources jar（沒有跑過 genSources），所以以 `javap -p -c` 讀 `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common/1.21-net.fabricmc.yarn.1_21.1.21+build.9-v2/minecraft-common-1.21-net.fabricmc.yarn.1_21.1.21+build.9-v2.jar`（SHA-256 `f1a51c67…`），Fabric 的部分讀 `fabric-lifecycle-events-v1-2.3.12+6c1df360d1-sources.jar` 的 `MinecraftServerMixin`。摘錄在 `fix3/autosave-source.txt`。
- 結論：
  1. 是一個會遞減的計數器 `MinecraftServer.ticksUntilAutosave`，不是看 `ticks % 6000`。建構子把它設為 6000（`<init>` offset 77–81）。
  2. `MinecraftServer.tick(BooleanSupplier)`：`++ticks`（offset 4–11）→ `tickWorlds`（offset 23；GameTest 的 callback 在其中的 `TestManager.INSTANCE.tick()`）→ `ticksUntilAutosave` 減一（offset 52–59）→ ≤0 時重設為 `getAutosaveInterval()`（offset 69–74）並 `saveAll(true, false, false)`（offset 100–104）。Fabric 的 `END_SERVER_TICK` 注入在 `tick` 的 TAIL，`TestServer.tick` 先呼叫 `super.tick`。所以同一個 tick 內的順序是：GameTest callback → autosave → `END_SERVER_TICK`（recovery 的 RETRY_FLUSH、走廊清理、塌縮流程）。
  3. `getAutosaveInterval()` 是 `max(100, (int)(tickRate × 300))`，sprint 期間改用實測的每 tick 耗時換算；20 TPS 時是 6000。
  4. 計數器只在三處寫入：建構子、`tick` 自己的到期重設，以及 `updateAutosaveTicks()`（新間隔小於目前倒數時下修）。`updateAutosaveTicks()` 只由 `ServerTickManager.setTickRate`（`/tick rate`）與 `finishSprinting`（`/tick sprint` 結束）呼叫；整個 jar 只有 `MinecraftServer` 與 `ServerTickManager` 引用這兩個名稱。存檔指令、玩家加入、其他 `saveAll` 都不會重設它。
  5. 因此沒有 `/tick` 指令時，autosave 落在 server tick 是 6000 倍數的那個 tick（fix round 2 的 102000；本輪 mutation 的 6000、12000）。callback 當下讀到倒數 c（≥1）時，autosave 在 server tick `getTicks()+c-1`。
  6. autosave 經 `PersistentState.save` 寫出 journal，不經過 `flush`，所以 flush 開頭的探針看不到它（fix round 2 已經觀察到）。
- helper：讀私有計數器用 testmod 的 accessor mixin（`MinecraftServerAutosaveTestAccessor`，唯讀），不假設 `% 6000`，也不用名稱反射。`afterAutosaveClear(context, tick, name, window, start, hooks)` 從 `tick` 起逐 tick 檢查 `nextAutosaveTick − getTicks() ≥ window`，成立就在那個 tick 的 callback 呼叫 `start`，記一行 `M5_AUTOSAVE_CLEAR`；不成立時倒數最晚 window 個 tick 內到期並重設為一整個間隔，所以最多等 window＋1 個 tick，等不到（間隔被 `/tick` 改過）就以有界等待逾時失敗。`AutosaveWindow.untouched` 比較「下一次 autosave」是否仍是開始時的那一個（發生過 autosave 就會往後移一整個間隔），記一行 `M5_AUTOSAVE_WINDOW`（含被探針計數的 server tick）。
- 窗口長度：G-SEAL 開始注入的 tick 就是第 1、2 次寫出，第 3、4 次在 +200、+600，至少要 601；G-RFB 從選門到第一次清 lease 的寫出實測 11 個 tick（下表 dev1、GREEN focused、GREEN focused 2、FIX3-WAIT-PATH-RFB 四次都一樣），4 次寫出在 +11、+11、+211、+611，至少要 612。兩者都取 800，約有 190～200 ticks 的餘裕。

M5 GameTest 的掃描（m-1 第 3 點；判斷「靠探針計數、而且跨越多個 tick」的 witness 會不會被 autosave 影響）：

| Test | 探針計數的 witness | 判斷 | 處置 |
| --- | --- | --- | --- |
| G-RFB | 清 lease 寫出的次數與間隔（跨 600 ticks） | 會：autosave 落在第 1 次與第 3 次寫出之間就只記到 2 次 | 套用 helper（窗口 800） |
| G-SEAL | 進入 SEALED 的寫出次數與間隔（跨 600 ticks） | 會：同上 | 套用 helper（窗口 800） |
| G-RF | 返還寫出失敗之後的 `sameWriteRetries`、清 lease 失敗 1 次 | 不會誤判：Javadoc 已寫明確認可能由 autosave 帶出；`noRetryBeforeBackoff`、`confirmedNotBeforeBackoff` 在沒有重試時成立，`oneWarnWithCause` 只看第一次失敗。清 lease 的失敗與同一個 tick 的 RETRY_FLUSH 都在 `END_SERVER_TICK` 內，autosave 在它之前，插不進去。M-RETRY（失敗的下一個 tick 就重試）只有在 autosave 剛好落在失敗後的那一個 tick 時才會漏掉（約 1/6000） | 不套用 |
| G-FB | 提交 COLLAPSING 的嘗試次數（跨約 1400 ticks） | 不會：restore 類寫出失敗時 current 還原為 MEASURED，沒有待確認的內容讓 autosave 帶出；探針只在塌縮流程的提交 flush 中計數，退避是塌縮流程的 per-session 退避 | 不套用 |
| G-TERM | 終局寫出的嘗試次數與間隔 | 不會：終局寫出失敗時是 RESTORED（current 放回 record），同上 | 不套用 |
| G-OFF | 重新連線者第一次返還寫出的 tick（恰為 JOIN＋1） | 不會：返還是 `commitRollForward`，put 與 flush 在同一個呼叫裡，autosave 無法先帶出 | 不套用 |
| G-SRC | 提交那次寫出的 operation guard | 不會：只有一次寫出，在同一個呼叫裡 | 不套用 |
| G-NLIVE | 是否寫出過 COLLAPSING、第一次寫成 DORMANT 的 tick | 不會：DORMANT 由走廊清理的 `journal.put(expected); journal.flush(server)` 寫入（`CPM:937`，同一個呼叫）；COLLAPSING 只可能經提交的 flush 寫入 | 不套用 |
| G-ELSE、G-DOR、G-UNSUP、G-PASS、G-NETHER、F3b 的 4 個 | 沒有 journal 探針 | 不受影響 | 不套用 |
| M5 run 的 5 個（`M5U`） | 沒有 journal 探針。`harness_retry` 計的是 `complete` 的嘗試次數：原版 `GameTestState.tickTests` 在同一次呼叫裡先執行 test 本體、再執行 timed task runner，第一次嘗試在 autosave 之前，一定看到未寫出的 catalog | 不受影響 | 不套用（也不在允許路徑） |

- 本輪只掃描、沒有修改 M5 期間放在 `M4CandidateDoorGameTests`、`M2CorridorGameTests` 的 test（不在允許路徑）：F3a 的 `m5_f3a_native_collapsing_return` 的清 lease 失敗與 RETRY_FLUSH 在同一個 tick 的 `END_SERVER_TICK` 內；H2 的 `calls` 是 production checkpoint 的呼叫次數，region write 的計數斷言是 ≥；H3 的故障在入場的 initial flush，重試沒有 200 ticks 的退避，整段在 `whenWithinTicks` 的 100 ticks 上限內。這些沒有逐一以 mutation 驗證，列為觀察。

測試：
- default GameTest 173、M5 GameTest 5、legacy 1，都不變；只改 G-SEAL、G-RFB 的結構與 witness。
- JUnit 570 不變：m-2 是既有 test 的 cases 表中的一列，不是新的 `@Test`。

證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f3d/fix3/`：state 檔由 `fix3/state.ps1`（與 fix2 相同）產生；focused gate 由 `fix3/gate.ps1` 執行（先記 state，再以 `JAVA_TOOL_OPTIONS=-Dquantumchamber.gametest.onlyBatches=…` 跑 `gates.ps1 -Mode GameTest`，摘要寫 `fix3/<label>-summary.txt`）；暫時性改動由 `fix3/adapter.py` 套用與還原（套用前保存原檔，還原後以 `git hash-object` 核對），各 adapter 的 diff 在 `fix3/adapters/<名稱>/`。

| 輪次 | 狀態 | Root | 結果 |
| --- | --- | --- | --- |
| RED I-1 | `675d53d`；工作樹只有 `M5C`：HEAD 的 test 結構加上新 witness 與 `collapseFlowNeverWarned`（blob `7d859e0`，`fix3/f3d-fix3-red-seal-state.txt`、diff `fix3/red-seal.diff`） | `gate-f3d-fix3-red-seal-57f82c28fac249c1b186f75f6b6cc24d` | FAIL，witness 只有 `collapseFlowOnlySawPendingRollForward=false`，attempts=[57, 57, 257, 657]；log 有該 sid 的「目的地解析尚未實作（F4a）：COLLAPSING 停在 RESOLVE…」WARN（`fix3/f3d-fix3-red-seal-warn.txt`） |
| RED m-2（JUnit mutation FIX3-SRM-READY-SEALED-NONE） | 工作樹已有新列；`SRM:198` 改回一律 NONE（`fix3/mut-junit-ready-sealed-none-state.txt`） | Gradle `test --tests …SessionRecoverySchema4Test`（`fix3/mut-junit-ready-sealed-none.log`、`.xml`） | 41 個 test 中 1 個 FAIL，assertAll 只有新列失敗：`expected: <RETRY_FLUSH> but was: <NONE>`；既有的 NONE 列照常通過 |
| 開發（量測 L） | 工作樹（`fix3/f3d-fix3-dev1-state.txt`），與 `08dd4d7` 只差 G-RFB Javadoc 的一句 | `gate-f3d-fix3-dev1-e3c7c383e7a84e60b074b0764ef6569a` | PASS 2/2；G-RFB start=1788、writes=[1799, 1799, 1999, 2399]（L=11）；G-SEAL start=59、attempts=[59, 59, 259, 659] |
| GREEN focused | 同上（`fix3/f3d-fix3-green-focused-state.txt`：`M5C` `ce510f8`） | `gate-f3d-fix3-green-focused-dc3933d5f8b045b2b867d57c5542fbef`（F3d 與 F3b 的全部 17 個 `m5_collapse_` batch） | PASS 17/17，`scope.json` PASS；G-RFB start=6245、writes=[6256, 6256, 6456, 6856]，G-SEAL start=9319、attempts=[9319, 9319, 9519, 9919]，兩者的 autosaveAt 都是 12000、確認時不變（`fix3/green-focused-autosave.txt`） |
| GREEN focused 2 | 工作樹＝`08dd4d7`（`fix3/f3d-fix3-green-focused-2-state.txt`：`M5C` `76ab727`、`S4T` `a56d283`、mixins.json `c65193b`、accessor `d64bfce`） | `gate-f3d-fix3-green-focused-2-8c059ae1d3114cc8b3a9234b3dffd93e`（G-SEAL、G-RFB） | PASS 2/2；G-SEAL attempts=[57, 57, 257, 657]、G-RFB start=1840、writes=[1851, 1851, 2051, 2451]（`fix3/green-focused-2-autosave.txt`） |
| GREEN JUnit | 同上（`fix3/green-junit-state.txt`） | Gradle `test --rerun-tasks`（`fix3/green-junit.log`） | 570，0 failure／error，2 skipped |
| FIX3-CS-NO-PENDING-PAUSE（I-1 的 mutation） | GREEN focused 的工作樹；拿掉 `CS:325` 的 `rollForwardPending` 早退 | `gate-f3d-fix3-mut-cs-no-pending-pause-233e099b332b4c2c92c8dbcf76b9d686` | G-SEAL FAIL：`collapseServiceNeverOverwrote=false`、`collapseFlowOnlySawPendingRollForward=false`；log 是「塌縮流程的推進沒有完成，下一個 tick 重試：…IllegalArgumentException: 只有 COLLAPSING 有塌縮的推導階段：SEALED」，與 reviewer 的推論相同（`fix3/f3d-fix3-mut-cs-no-pending-pause-warn.txt`）。計數 witness 照常成立，attempts=[54, 54, 254, 654] |
| FIX3-AUTOSAVE-FORCED-150（m-1 的 mutation） | 同上；helper 改成「在原生 autosave 之前恰好 150 ticks 開始注入」（最多等一整個間隔），G-SEAL 的 tickLimit 暫時放大到 9000 | `gate-f3d-fix3-mut-autosave-forced-150-a3c9d71366104b809894d10aa1427fbf` | 兩個都 FAIL：G-SEAL start=5850、autosave 在 6000，attempts=[5850, 5850]；G-RFB start=11850、autosave 在 12000，writes=[11861, 11861]。兩者都是 `noNativeAutosaveBeforeTheConfirmation=false`、`fourAttempts`／`fourWrites=false`、`retriesFollowTheBackoff=false`（`fix3/f3d-fix3-mut-autosave-forced-150-log.txt`）。不等待時，fix round 2 第 1 輪 run-all 的誤判可以穩定重現 |
| FIX3-WAIT-PATH（預期 PASS，走 helper 的等待分支） | 同上；G-SEAL 的窗口暫時改成 5990、tickLimit 9000 | `gate-f3d-fix3-wait-path-65dea0b10a6d4eae988b4388fda23ec0` | PASS：開始時不成立，等到 6000 的 autosave 之後，在 6001 才開始注入；attempts=[6001, 6001, 6201, 6601]，autosaveAt=12000（`fix3/f3d-fix3-wait-path-log.txt`） |
| FIX3-WAIT-PATH-RFB（預期 PASS） | 同上；G-RFB 的窗口暫時改成 5990 | `gate-f3d-fix3-wait-path-rfb-8cc391a60c544535938bdd54fc608bbe` | PASS：入場完成之後在 SUPERPOSITION 等了約 4300 ticks，6001 才選門；writes=[6012, 6012, 6212, 6612]，等待本身不影響 test（`fix3/f3d-fix3-wait-path-rfb-log.txt`） |
| run-all | `08dd4d7`，`STATUS` 為空（`fix3/run-all-console.log`） | `run-all-b5b2bf9b30fc49e5980973ea708490c2`（Full：`gate-full-2eac6badc23d44cabdef554467fcf3db`） | `END ALL exit=0 HEAD=08dd4d76efda461d2235750ae9781b708bc921d3`，約 15 分鐘（17:35:10→17:50:14）。`SUMMARY default=173/173 windows=88 legacy=1/1 m5=5/5 perTest=51+1 junit=570 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=367entries/320classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`，與 fix round 2 的 run-all（`run-all-cfc41c7ebac1447f9f26b423a280d211`）逐項相同。Full 裡 G-RFB start=13887、writes=[13898, 13898, 14098, 14498]（autosaveAt=18000）；G-SEAL start=100133、attempts=[100133, 100133, 100333, 100733]（autosaveAt=102000，正是 fix round 2 第 1 輪誤判時的那一次 autosave） |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對）；mutation 與等待分支跑的是 GREEN focused 的工作樹（`M5C` `ce510f8`），之後只改了 G-RFB Javadoc 的一句。`08dd4d7` 的 4 個檔 commit 時的 blob 與 GREEN focused 2、GREEN JUnit 的 state 相同。
- 與 fix round 2 的 run-all 相比：default 173/173、`_windows` 88、per-test receipt 51＋1、JUnit 570（2 skipped）、legacy 1/1、M5 5/5、release 367／320、sources 212 都不變（本輪沒有新增 test、沒有改 production；accessor 在 testmod，不進 release 與 sources JAR，testmod 命中仍是 0）。log（Full gate，以 `/ERROR]`、`/WARN]` 行計）：ERROR 28 → 28，WARN 55 → 54，少的就是 G-SEAL 的 RESOLVE stub WARN；逐 batch 的比對在 `fix3/wbdiff.txt`（`fix3/wb.py` 依「Running test batch」分組，對 fix round 2 的 Full log 重算的結果與 `fix2/wb2.txt` 相同，只差負號的正規化）。G-SEAL 剩下 recovery 的「返還保留 pending」1 則，G-RFB 仍是 2 則。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

偏離：
1. **G-SEAL、G-RFB 恢復恰好 4 次**：派工只要求 helper。`8f3d76c` 把計數放寬成 3～4 次只是為了 autosave；helper 讓窗口涵蓋到第 4 次寫出，並以 `noNativeAutosaveBeforeTheConfirmation` 斷言期間沒有 autosave，放寬已經沒有理由，所以改回 fix round 2 原本的 `fourAttempts`／`fourWrites` 與間隔斷言。
2. **JUnit 數不變**：派工的「預期 JUnit 多 1 個」與「在該檔補一列」不一致。依「只限第 3 項新增的一列」，補在既有 test 的 cases 表中（與同表 COLLAPSING 那一列相同的寫法），所以 JUnit 仍是 570；mutation 下該 test 失敗，失敗訊息只有新列。若要求獨立的 `@Test`，需要再改一次這個檔。

## F4a-0：M5 GameTest 共用支援

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `81217a0` `test: share M5 GameTest support`（parent `219145a`）：Step 1 的純搬移。新增 testmod `M5GameTestSupport.java`、`M5TestHooks.java`；修改 `M5CollapseGameTests.java`、`M5UniverseGameTests.java`、`M4CandidateDoorGameTests.java`；`mixin/SessionJournalWindowFaultMixin.java`、`mixin/CollapseSuspendMixin.java` 只改指向。沒有 production 與 JUnit 的變更。
  - `53e923e` `test: count every journal write and tag collapse warnings`（parent `81217a0`）：Step 2～4。production 只有 `collapse/CollapseService.java`（marker 與 `applyDoor`／`PendingDoor`）；JUnit `CollapseServiceTest.java`（只擴充 `pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings`）；testmod 新增 `mixin/SessionJournalWriteCountMixin.java`，修改 `quantumchamber-test.mixins.json`（只登記它）、`M5TestHooks.java`、`M5GameTestSupport.java`、`M5CollapseGameTests.java`、`LogCapture.java`。
  - 本段與 `scripts/verification/README.md`「新增 M5 GameTest」一節由其後的 docs commit 加入。
- Code range `219145a..53e923e`。沒有改 `M2CorridorGameTests.TestHooks`、M2 的 `OBSERVED`、`SessionRecoveryState.java`、`build.gradle`、`.github/`、`scripts/verification/` 的腳本。
- 範圍是 plan Task F4a-0：F3d「交給後續」中 F4a 的 `M5GameTestSupport`、quality re-review 的 m-1～m-3，以及 F3d fix round 2 的 m-6(d)（把 hook 搬出 `M4CandidateDoorGameTests`）。m-4 屬 F4a 本身，沒有處理（見「交給後續」）。除了 m-2 的 marker 與 m-3 的 `applyDoor` 結構，production 行為不變。
- 行號以 `53e923e` 為準。`SUP` 指 `M5GameTestSupport`，`HK` 指 `M5TestHooks`，`WCM` 指 `mixin/SessionJournalWriteCountMixin`，`LC` 指 `LogCapture`；`M5C`、`M5U`、`M4C`、`CS`、`CST` 同 F3d 一節。
- Review：spec review ✅（Critical 0、Important 0、Minor 3：m-1～m-3）；quality review Approved（Critical 0、Important 0、Minor 5：Q1～Q5）。controller 裁定開窄範圍的 fix round 1（Q1～Q4、Q5 的部分項目、spec m-1～m-3），見本節最後的「Fix round 1」。
- 最終 review：
  - fix round 1 的 spec re-review ✅（Critical 0、Important 0、Minor 1：F6 交接漏列新的 harness，已由 controller 補在「交給後續」）。
  - quality re-review Approved（Critical 0、Important 0、Minor 3）。其中 LEGACY 的文件描述與 fault hook 的 `Slot` 規則，已由 controller 寫入 README 與「交給後續」；其餘小幅重複交給 F6。
  - **F4a-0 完成**：range `219145a..b56aba4`。
    - code：`81217a0`、`53e923e`、`b333feb`、`a8863de`、`bab3474`、`d5a372e`。
    - 最終 run-all：`run-all-32c099d096b049e98e6e8e21d773ff1f`，`END ALL exit=0`（`5bd9844`）。本節其餘內容描述 `53e923e`：fix round 1 改過的 API 在相關列另加註；首輪證據敘述的更正標「fix round 1 更正」。

### 派工逐項對照

| 派工 | 處置與位置 | 證據 |
| --- | --- | --- |
| Step 1：`collapseFixture`、`cleanupFixtures` 在 M5C 與 M4C 各一份 | 合成 `SUP:221`／`:231`（MEASURED 收據只經 commitRestore／commitRollForward 進入指定狀態，最後確認 exact 落盤；`suspend` 為真時暫停）與 `SUP:258`／`:268`（依 session 清單或依條件；共用 `SUP:281` `removeFixture`）。M4C 的 F3a 規則（COLLAPSING 選 SOURCE、其他選 NEW、一律暫停）留成一行轉呼叫 `M4C:1956` `f3aFixture` | Step 1 前後 focused |
| Step 1：`at`／`whenTicks` 與 M4C 的 `whenWithinTicks` 重複 | `SUP:81`、`SUP:93`；M4C 的 `whenWithinTicks` 刪除，呼叫點改用 `whenTicks`（static import；判定與逾時訊息逐字相同） | 同上（用到它的 H3、F3a batch 也在 focused 內） |
| Step 1：Nether 原艙的建置在 G-NETHER 與 G-NLIVE 各寫一次 | `SUP:388` `NetherChamber`：`open` 強制載入、以石頭圍住並建置、連線並把玩家移進艙內（登記解除強制載入、關閉連線、斷電三個收尾）；`whenEntered` 第 2 tick 供電並等 358 扇側門；`record` 取這座艙的 flushed record（fix round 1：`whenEntered(ready)` 改以呼叫當下的 tick 為基準，沿用 `open` 的 context 與 hooks） | 同上 |
| Step 1：`SourceDoor` 名不符實 | 改名 `CandidateDoor`（`SUP:475`），`candidateDoor`／`sourceDoor` 一併搬到 `SUP:483`／`:478` | 同上 |
| Step 1：M5U 借用 M5C 的 static helper | M5U 改用 `SUP` 的 `at`、`interact`、`doorAll`、`moveInside` 與 `DormantOrigin`；M5C 不再有被別的類別呼叫的 helper | M5 前後 |
| Step 1：5 個「記 tick、前 N 次失敗」的計數探針 | `SUP:128` `FlushFault`：條件成立就記 server tick，武裝時前 N 次在 flush 開頭以指定訊息丟出（`ticks()`、`failures()`、`retries()`、`disarm()`）。CommitFault＝N 無上限＋`disarm()`；ReturnWriteFault 拆成返還與清 lease 兩個 N＝1 的探針，以 `andThen` 依序檢查（兩者互斥：全員返還之後才會清 lease）；G-RFB、G-SEAL 的 N＝3；G-TERM 的 N＝1。探針一律經 `SUP:117` `installFlushProbe` 安裝（fix round 1：改為 `installFlushProbe(context, probe, hooks)`，位置被占用時丟出、不覆蓋） | 同上；G-RFB 與 G-SEAL 的計數在前後兩輪都是「開始＋11、＋0、＋200、＋600」與「＋0、＋0、＋200、＋600」 |
| Step 1：autosave／寫出窗口 helper | `afterAutosaveClear`、`AutosaveWindow` 搬到 `SUP:183`、`:195`（Step 2 再擴充） | 同上 |
| Step 1：G-DOR、G-TERM、G-RES 共用的「真原艙＋DORMANT 收據＋玩家在艙外」 | `SUP:322` `DormantOrigin`：`open` 依序登記關閉連線、斷電、`cleanupFixtures(owned)`，再建置、連線、移到艙外、開始 log capture；`whenRegistered` 第 2 tick 供電、等原艙註冊並 IDLE 之後交出 chamber UUID；`receipt` 只建立收據，`own` 先加入 `owned` 再 put＋flush（fix round 1：`whenRegistered` 改以呼叫當下的 tick 為基準，供電經 `at`；log capture 改經 `captureLogs`） | 同上 |
| Step 1：`m5FlushProbe` 與 collapse-suspended 集合搬出 M4C | `HK:33` 集合（仍沒有移除的 API，決策 7 的不變量不變）、`HK:34` 探針（fix round 1：探針與稽核改為 `HK.Slot`）、`HK:49` `beforeJournalFlush`。`SessionJournalWindowFaultMixin` 在原本呼叫 M4C `beforeCandidateFlush` 的那一行之前加一行呼叫 `HK.beforeJournalFlush`：m5 探針原本就是 `beforeCandidateFlush` 的第一個動作，順序不變。`CollapseSuspendMixin` 只改 import、Javadoc 與呼叫的類別。`M2CorridorGameTests.TestHooks` 與 M2 的 `OBSERVED` 沒有改 | 同上；掛 hook 的 6 個 M4／F3a batch 都在 focused 內 |
| Step 2（m-1）：統計 journal 的每一次寫出 | `WCM`：掛在 `SessionRecoveryState.save(File,…)` 呼叫 `SessionJournalStore.write` 的那一點（`require=1`）。讀過 production：journal 唯一的寫出點是 `save`（`SessionRecoveryState.java:643`）；`flush`（`:605`）以正式路徑呼叫它；原生存檔（autosave、`server.save`、停服）經 `PersistentStateManager.save` 呼叫同一個 `save`；不 dirty 時 `save` 在 `:646` 提早返回、不寫，所以掛在寫出那一點才只算真的寫出。`HK:81` `JournalWriteAudit` 記兩種事件：探針看到的 flush（`HK:49`，在探針之前記）與實際寫出（`HK:58`），各附 server tick、呼叫端與「current 與 flushed 不同的 session」。寫出的來源依呼叫堆疊判定（`HK:139`）：有 `SessionRecoveryState.flush` 就是經 flush；否則看呼叫 `MinecraftServer.save/saveAll` 的那一層：`MinecraftServer.tick` 是原生 autosave、`shutdown` 是停服，其他是 `server.save`（附呼叫端，例如 `ChunkSaveCheckpoint.save`）；都不是則為其他寫出。稽核本身的例外只記下（判定時失敗）、不丟出，以免那次寫出被當成落盤失敗 | GREEN 的 `M5_JOURNAL_WINDOW` 列出 G-RFB、G-SEAL 窗口內的每一次 flush 與寫出（下方「Gate 與證據」） |
| Step 2：窗口 helper 斷言「窗口內每一次寫出都是探針看到的」 | `afterAutosaveClear`（`SUP:183`）在開始注入的那個 tick 啟動稽核（hooks 釋放時移除）；`AutosaveWindow.journalWrites`（`SUP:204`）回傳 `JournalWindow(onlyProbed, nativeAutosave, problems)`：每一次實際寫出都經 flush，而且探針看到的每一次 flush（含受控失敗、沒有寫出的那幾次）只帶本 test 自己的 session 的變更。`problems` 逐則列出旁路的種類：原生 autosave、`server.save`＋呼叫端、停服、其他寫出、其他 session 的 flush（附 session 與呼叫端）；另記一行 `M5_JOURNAL_WINDOW`。`afterAutosaveClear` 的等待保留（降低誤判機率）；`nativeAutosave`（即 F3d `untouched` 的反面）只供診斷：autosave 時 journal 沒有變更就不會寫出，不單獨判定。G-RFB（`M5C:1093`）、G-SEAL（`M5C:1170`）的 `noNativeAutosaveBeforeTheConfirmation` 換成 `onlyProbedJournalWrites`，失敗訊息附 `problems` | RED F4A0-M1-BYPASS；F4A0-M1-AUTOSAVE |
| Step 3（m-2）：marker 或專屬 logger，二擇一 | 選 SLF4J marker：`CS:83` `LOG_MARKER`（`QUANTUMCHAMBER_COLLAPSE`）、`CS:85` `TICK_LOG_MARKER`（`QUANTUMCHAMBER_COLLAPSE_TICK`，父 marker 是前者）。`CS` 的每一則 log 都帶 marker；不屬於任何 session 的三則（開服讀不到 journal 的 ERROR、整個 tick 無法執行的 WARN 與它恢復時的 INFO）帶子 marker。logger 名稱、等級與訊息都沒有改 | 選擇理由見下方「marker 與 logger 的選擇」 |
| Step 3：witness 改依 marker | `SUP:306` `collapseFlowNeverWarned`：這個 sid 沒有帶 `LOG_MARKER`（含子 marker）的 WARN／ERROR，而且 capture 期間沒有任何帶 `TICK_LOG_MARKER` 的 WARN／ERROR。判定之前先以子 marker 記一行 INFO 探針（`M5_COLLAPSE_MARKER_PROBE`），確認 capture 讀得到 marker 與父子關係，讀不到就丟例外。`LC:19` 的 Entry 多記 log4j 收到的 marker，`LC:49` `marked` 以 `isInstanceOf` 篩選 | RED F4A0-M2-MARKER-WARN；F4A0-M2-NO-MARKER |
| Step 3：`tickWarning` 若有 sid 可帶就帶 | 沒有 sid 可帶：`tick()` 外層 catch 涵蓋的是整個 tick 的失敗（取 journal、pages、走訪 flushed、去掉不在 journal 的 track），不屬於任何 session；每個 session 的例外已由內層 catch 經 `warn()` 帶 sid 記錄。所以帶子 marker，witness 另以「整個 capture 期間沒有帶子 marker 的 WARN／ERROR」涵蓋，寫在 `SUP:306` 的 Javadoc | — |
| Step 4（m-3）：`applyDoor` 依 `step.warn()` 決定是否記錄；`PendingDoor` 只記下原因 | `CS:556` `recordDoorStep`：只在 `step.warn()` 為真時記 WARN，再交給 `PendingDoor.remember`（`CS:679`，只記下原因與「已立即重試過一次」），`backOff()` 時退避；回傳是否記了 WARN。`applyDoor`（`CS:532`）與 `tick()`／`openDoor` 的套用例外都經它；套用例外的處置抽成 `CS:584` `doorFailure`（「套用失敗：…」、同一原因去重、一律退避、不改重試狀態）。行為與搬移前逐項相同：同一原因不再記、原因改變再記、NOT_LOADED 沒有原因時保留上一個、套用例外的 WARN 仍附 stack。`PendingDoor` 由 private 改為 package-private（JUnit 用），沒有新增 class（fix round 1：`applyDoor` 與兩處套用例外的 catch 改經 `applyOutcome`／`applyFailure`，`recordDoorStep` 改為 private、不回傳） | JUnit 與 mutation（下表） |
| Step 4：JUnit 要鎖住 production 的記錄行為 | 現有 JUnit 不足：它只測純函式 `doorStep`，`applyDoor` 是否照 `warn()` 記錄、`PendingDoor` 是否記下原因都沒有被執行到。擴充 `CST:161` `pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings`：以真的 `PendingDoor` 經 `recordDoorStep` 走過 ROLLED_BACK、ROLLED_BACK、ROLLBACK_FAILED、NOT_LOADED、DOOR_INCOMPLETE×2 與兩次套用例外，斷言每一步是否記 WARN（`[T,F,T,F,T,F,T,F]`）、門記下的原因、退避 6 次（delay 6400）與重試狀態。仍是同一個 `@Test`，JUnit 數不變（fix round 1：改以 `applyOutcome`／`applyFailure` 驅動，並擷取 log 判定是否記 WARN，見「Fix round 1」） | F4A0-M3-NO-REMEMBER、F4A0-M3-IGNORE-WARN |
| Step 5：README 與 note | `scripts/verification/README.md`「新增 M5 GameTest」一節開頭新增共用支援的用法（hook 釋放、flush 探針與計數探針、窗口 helper 與 journal 寫出統計、marker 版 witness、`collapseFixture`／`DormantOrigin`／`NetherChamber`／候選門），原本的第 1–8 點不變；本節 | — |

### marker 與 logger 的選擇

- 專屬 logger 名稱會讓既有的 `LogCapture("quantumchamber")` 看不到塌縮流程的 log：`LC` 依 logger 名稱完全相等篩選，testmod 有 17 處以它斷言「塌縮已提交」「SOURCE 塌縮完成」「不支援的來源維度…」「開服時仍是 PASSAGE_READY」等 INFO，改名就要同步改這些 capture；latest.log 的 `(%logger{1})` 也會從 `(quantumchamber)` 變成新名稱的最後一段。
- marker 不影響這兩者：logger 名稱、等級與訊息不變。已核對 Loom 開發 runtime 的 log4j 設定（`.gradle/loom-cache/log4j.xml`，GameTest 與各 gate 都用它）：三個 appender 的 pattern 都不印 marker，也沒有 marker filter。正式環境（非 Loom）的 log 設定沒有核對；marker 只是附加在 log event 上的資訊（推論：不影響輸出）。
- `scripts/verification/` 中與 log 有關的地方都不看 logger 名稱或 marker：note 慣用的 WARN／ERROR 統計是以 `/WARN]`、`/ERROR]` 計行；`main-oracle.ps1` 只比對 `(Minecraft) Missing data pack quantumchamber-testmod` 那一行；`gates.ps1` 解析的是 `M5_UNIVERSE_*`、`M4_PER_TEST_*` 等 testmod 的行。run-all 的 latest.log 中塌縮流程的行仍是 `[Server thread/WARN] (quantumchamber) …`（例如 F4A0-M2-MARKER-WARN 的 mutation 行），WARN／ERROR 的計數只差下方說明的一則既有偶發。
- `LogCapture` 讀得到 marker：SLF4J 的 marker 經 log4j-slf4j 轉成同名的 log4j marker（含父子關係），由 `LogEvent.getMarker()` 取得。以實跑確認：GREEN 與 run-all 的 G-SEAL 判定前的探針都通過；F4A0-M2-NO-MARKER（capture 不記 marker）時 G-SEAL 以「log capture 讀不到塌縮流程的 marker」失敗，不會以空結果通過。

### 測試

- default GameTest 173（`_windows` 88）、legacy 1、M5 5、per-test receipt 51＋1，都不變：沒有新增或刪除 `@GameTest`，`M4PerTestUniverseProbe.begin(` 仍是 52 處。
- JUnit 570 不變（2 skipped）：Step 4 擴充的是既有的 `@Test`。`CollapseServiceTest` 仍是 7 個。
- 改了 witness 的只有 G-RFB、G-SEAL：`noNativeAutosaveBeforeTheConfirmation` → `onlyProbedJournalWrites`；G-SEAL 的 `collapseFlowOnlySawPendingRollForward` 改依 marker。其餘 test 的斷言沒有改，只換成共用的 fixture、探針與 helper。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4a0/`：state 由 `f4a0/state.ps1`（比照 `f3d/fix2/state.ps1`）產生；focused gate 由 `f4a0/gate.ps1`（比照 `f3d/fix3/gate.ps1`，先記 state，再以 `JAVA_TOOL_OPTIONS=-Dquantumchamber.gametest.onlyBatches=…` 跑 `gates.ps1`）執行；暫時性改動由 `f4a0/adapter.py` 套用與還原（套用前保存原檔，還原後以 `git hash-object` 核對），各 adapter 的 diff 在 `f4a0/adapters/<名稱>/`。gate root 在 `.superpowers/verification/`。`git diff 219145a 53e923e -- <path>` 輸出的 SHA-256 前 8 碼（`f4a0/diff-hashes.txt`）：`src/main` `621b072d`、`src/test` `01520b9b`、`src/testmod` `dcf65cb1`。

focused 的範圍（`f4a0/focused-batches.txt`，34 個 batch）：全部 17 個 `m5_collapse_*`；F3d fix round 2 focused 的 6 個掛 hook 的 M4／F3a batch（`m4_select_first_wins`、`m4_ifix1_checkpoint_backoff`、`m4_dormant_other_chamber`、`m5_f3a_native_collapsing_return`、`m5_f3a_presence_reconcile`、`m5_f3a_attach_lookup`）；另外加上搬移碰到的 M4C batch：用到 `nativeCandidates`（暫停 hook）的 `m4_select_rejections`、`m4_select_flush_before`、`m4_select_flush_after`、`m4_select_fault_other_flush`、`m4_select_mapping_windows`、`m5_h2_checkpoint_scope`，與用到 `whenTicks` 的 5 個 `m5_h3_*`。M5 run 一律跑全部 5 個 `m5_universe_*`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| Step 1 前（focused） | `219145a`，工作樹乾淨（`f4a0-before-focused-state.txt`） | `gate-f4a0-before-focused-994587093f07449ab55408afc53f74c8` | PASS 34/34 |
| Step 1 前（M5） | 同上（`f4a0-before-m5-state.txt`） | `gate-f4a0-before-m5-361bb1640e6542779264afe66660ab0a` | PASS 5/5 |
| Step 1 後（focused） | `219145a`＋Step 1 的工作樹（`f4a0-after-focused-state.txt`，7 個檔的 blob 與 `81217a0` 相同） | `gate-f4a0-after-focused-f5d50d167f7449cba1b5b06b77797ea6` | PASS 34/34；testcase 集合與每一個的結果都與「Step 1 前」相同。G-RFB、G-SEAL 的計數型態相同（見上表）。WARN／ERROR 各多 1 則，都在 G-FB 的入場：走廊候選批次的 journal 原子移動遇到 Windows `AccessDeniedException`（`.tmp -> quantumchamber_sessions.dat`），該 session 依 M4 規則轉 RETURNING、原生入場重新開始，test 照常通過。這屬環境偶發，與搬移無關：之前沒有 IO 注入的同型偶發見 `gate-f2a-fix2-green-full-1934f3843c7c40a98d29fd667aa22c82/execution/runtime-logs/latest.log:301`（`m5_h3_quarantine_converges` 的入場 ARMING；該 test 以 CAS 不符製造隔離，沒有 IO 注入）。fix round 1 更正：原文寫「在之前的 Full gate log（含 F3d 最終 run-all）中也出現過」，但 F3d 最終 run-all 的 Full log（`run-all-b5b2bf9b…/gate-full-2eac6bad…`）中的 `AccessDeniedException` 只有 `m5_h3_start_readback_fault` 的注入（:2578）與 `m2_final_flush` 刻意持有無 DELETE 分享 handle 的受控故障（:9553、:9562），都不是偶發的證據 |
| Step 1 後（M5） | 同上（`f4a0-after-m5-state.txt`） | `gate-f4a0-after-m5-dc409fade4cc4123a8c86700a8e549d1` | PASS 5/5，與「Step 1 前」相同 |
| GREEN focused（第 1 輪，保留不採計） | `81217a0`＋Step 2～4 的工作樹（`f4a0-green-focused-state.txt`） | `gate-f4a0-green-focused-c8dc9fc71cd84ec082c6892f21846eca` | 32/34。兩個失敗都在 F4a-0 沒有碰的 production 路徑上（`f4a0/green-focused-1-failures.txt`）：F3a G3 `noStaleAuthorityReturnPending=false`，返還寫出遇到同一種 Windows `AccessDeniedException`，多出一則「返還保留 pending」；G-NLIVE `noWarnOrError=false`，保留式清理的原生存檔 checkpoint 丟「相關 chunk／entity storage 尚未完整載入」，多出一則「MEASURED 保留 receipt 等待安全返還」（見「觀察」第 2 條）。G-RFB、G-SEAL 都通過、`M5_JOURNAL_WINDOW` 都是 PASS |
| GREEN focused（第 2 輪） | 同上，blob 與第 1 輪相同（`f4a0-green-focused-2-state.txt`） | `gate-f4a0-green-focused-2-c4e073d851db4092b1d0ec9841cf51c2` | PASS 34/34。G-RFB start=6167、counted=[6178, 6178, 6378, 6778]；G-SEAL start=12001、counted=[12001, 12001, 12201, 12601]；兩者 `nativeAutosave=false`、`problems=[]` |
| GREEN M5 | 同上（`f4a0-green-m5-state.txt`） | `gate-f4a0-green-m5-24c4a84eb0064d31908fee1b9e4faf15` | PASS 5/5 |
| GREEN JUnit（focused） | 同上 | Gradle `test --tests …CollapseServiceTest` | 7/7。fix round 1 更正：這一次的 log 沒有保存；可查的證據改引 run-all（`53e923e`）：`run-all-93e36a3b…/gate-full-6f3c36c2…/result-build/test-results/test/TEST-dev.quantumchamber.collapse.CollapseServiceTest.xml` 是 tests=7、failures=0，整體 junit=570、failures=0。同一個 gate 的 `prior-build/` 是 run 開始前留在 `build/` 的 F4A0-M3-IGNORE-WARN mutation 結果（failures=1），不是 run-all 的結果 |
| F4A0-M1-BYPASS（Step 2 RED） | 同上；adapter `f4a0-m1-bypass`：G-SEAL 在進入 SEALED 的寫出受控失敗之後、同一個 callback 內呼叫 `server.save(false,true,true)`；G-RFB 在窗口內、選門之前 put＋flush 另一個 session 的 DORMANT 收據（模擬殘留 session 的 flush）；兩者都加上舊斷言 `legacyUntouched` 作對照 | `gate-f4a0-mut-m1-bypass-6198316ab90549b38fbc8a5cd0788fa3` | 兩個都在目標斷言 FAIL。G-SEAL：`onlyProbedJournalWrites=false`、`legacyUntouched=true`，`problems=[旁路寫出（server.save，呼叫端 M5CollapseGameTests.lambda$…:1163）tick=41]`（server.save 把 SEALED 帶出確認，attempts=[41]，計數 witness 也因此失敗）。G-RFB：`onlyProbedJournalWrites=false`、`legacyUntouched=true`，而計數的 witness 全部通過（writes=[1154, 1154, 1354, 1754]），`problems=[其他 session 的 flush（session=[…]，呼叫端 M5CollapseGameTests.lambda$…:1089）tick=1143]` |
| F4A0-M1-AUTOSAVE | 同上；adapter `f4a0-m1-autosave`：helper 改成「在原生 autosave 之前恰好 150 ticks 開始注入」（比照 F3d fix3 的 forced-150），G-SEAL 的 tickLimit 暫時放大 | `gate-f4a0-mut-m1-autosave-ac9da059f0c046d399b6a7e53c63ca15` | 兩個都 FAIL，`problems=[旁路寫出（原生 autosave，呼叫端 MinecraftServer.tick:924）tick=6000／12000 …]`；這一型舊斷言也抓得到（`legacyUntouched=false`） |
| F4A0-M2-MARKER-WARN（Step 3 RED） | 同上；adapter `f4a0-m2-marker-warn`：`CS` 在 roll-forward 未確認、暫停的那一刻，對該 sid 記一則帶 `LOG_MARKER`、但沒有舊前綴的 WARN（每個 session 一次）；G-SEAL 另加舊的字串版 witness `legacyStringWitness` | `gate-f4a0-mut-m2-marker-warn-53afd2dd59d048728cf3f3736021d50a` | G-SEAL FAIL：`collapseFlowOnlySawPendingRollForward=false`、`legacyStringWitness=true`、`collapseServiceNeverOverwrote=true`，計數與 `onlyProbedJournalWrites` 照常為真。latest.log 的那一行是 `[Server thread/WARN] (quantumchamber) F4a-0 mutation：…` |
| F4A0-M2-NO-MARKER | 同上；adapter `f4a0-m2-no-marker`：`LC` 不記 marker | `gate-f4a0-mut-m2-no-marker-67c77bdccdb2497eb410119375a64865` | G-SEAL FAIL：「log capture 讀不到塌縮流程的 marker（QUANTUMCHAMBER_COLLAPSE／QUANTUMCHAMBER_COLLAPSE_TICK）」 |
| F4A0-M3-NO-REMEMBER（Step 4） | 同上；adapter `f4a0-m3-no-remember`：`PendingDoor.remember` 不記下原因（去重壞掉） | Gradle `test --tests …CollapseServiceTest`（`f4a0/mut-f4a0-m3-no-remember-junit.log`、`.xml`） | 7 個中 1 個 FAIL：`pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings`，記 WARN 的序列變成 `[T,T,T,F,T,T,T,T]`，門記下的原因全是 null |
| F4A0-M3-IGNORE-WARN（Step 4） | 同上；adapter `f4a0-m3-ignore-warn`：`recordDoorStep` 不看 `step.warn()`，只要有原因就記 | 同上（`f4a0/mut-f4a0-m3-ignore-warn-junit.log`、`.xml`） | 同一個 test FAIL：序列 `[T,T,T,F,T,T,T,T]` |
| run-all | `53e923e`，`STATUS` 只有 ` M scripts/verification/README.md`（`f4a0/run-all-console.log`） | `run-all-93e36a3b5f8e43b39bb8bbf446c70fcc`（Full：`gate-full-6f3c36c21e6241029ab77eaacb37f14d`） | `END ALL exit=0 HEAD=53e923e8b3ed75c62c7e7ce210aa86c2846bd249`，約 17 分鐘（18:53:49→19:11:06）。`SUMMARY default=173/173 windows=88 legacy=1/1 m5=5/5 perTest=51+1 junit=570 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=367entries/320classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`，與 F3d 最終 run-all（`run-all-b5b2bf9b30fc49e5980973ea708490c2`）逐項相同。`STATUS` 沒有 `src/`、build 檔或 `.github/` 的變更，本目錄的 `.ps1`／`.gradle` 都沒有改，依 README 的採計規則以 C＝`53e923e` 採計。Full 裡 G-RFB start=14169、counted=[14180, 14180, 14380, 14780]，G-SEAL start=101034、counted=[101034, 101034, 101234, 101634]，兩者 `M5_JOURNAL_WINDOW … problems=[] status=PASS` |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對）；mutation 跑的是 GREEN 的工作樹。`81217a0` 的 7 個檔、`53e923e` 的 8 個檔 commit 時的 blob，分別與「Step 1 後」與 GREEN focused 2 的 state 相同。run-all 在兩個 code commit 之後執行。
- fix round 1 更正：6 份 mutation（F4A0-M1-BYPASS、F4A0-M1-AUTOSAVE、F4A0-M2-MARKER-WARN、F4A0-M2-NO-MARKER、F4A0-M3-NO-REMEMBER、F4A0-M3-IGNORE-WARN）與 GREEN M5 的工作樹，比 GREEN focused 2 多了 `scripts/verification/README.md` 未 commit 的變更（blob `99ff474`，與之後 `d667a04` commit 的版本相同；見各自的 state 檔）。README 不進 build，不影響這些結果。
- release JAR：entry 清單與 F3d 最終 run-all 完全相同（367／320，`unzip -Z1` 比對）。內容不同的只有 `CollapseService` 與它的 6 個巢狀 class：`CollapseService.class` 與 `$PendingDoor`（marker 常數、`recordDoorStep`、`doorFailure`、`remember`，`PendingDoor` 由 private 改為 package-private）；`$1`、`$DoorStep`、`$EntryWatch`、`$MeasuredAction`、`$Track` 以 `javap -c -p -v` 去掉行號表之後完全相同（原始檔的行數位移）。sources 212 不變。新的 mixin 與 hook 類別都在 testmod，testmod 命中仍是 0。
- log（Full gate，以 `/ERROR]`、`/WARN]` 行計）：ERROR 28 → 28，WARN 54 → 55。依 batch 分組、把 UUID 與數字正規化之後比對（`f4a0/wbdiff.txt`），唯一的差異是 `m2_task4_disconnect_io` 的「艙體 … 的 session 協調失敗，保留返還保護」，F3d 一節已記為既有的偶發（H2 與 F3b fix round 1 的 Full 也各出現過一次）。marker 探針在 Full 的 log 中恰一行（G-SEAL 一次）。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

### 偏離派工字面之處

1. **focused 的範圍比 F3d fix round 2 大**：除了指定的 `m5_collapse_*`、`m5_universe_*` 與 6 個掛 hook 的 batch，另外加上搬移碰到的 11 個 M4C batch（見上方範圍）。M5 run 的 5 個 test 前後各跑一次。
2. **README 放在 docs commit**：派工的 commit 順序只列 code 與 note。README 的一節是 Step 5 的文件，與 note 一起放在第 3 個 commit（訊息據此調整）；run-all 的 `STATUS` 因此列出 README，不影響採計。
3. **M4C 保留一行 `f3aFixture`**：它只是 F3a 的規則（COLLAPSING 選 SOURCE、一律暫停）轉呼叫共用的 `collapseFixture`，不是第二份實作。
4. **合併後的 fixture 比原本的嚴一點**：M5C 的 `collapseFixture` 原本沒有最後的 exact 落盤核對（M4C 有），合併後兩邊都有；checked commit 成功時它必定成立。依 session 清單的 `cleanupFixtures` 也會先移除沒有 lease 的 space（M4C 原本就這麼做）；M5C 的 fixture 都沒有 space，這一步是 no-op。
5. **「殘留 session 的 flush」以「探針看到的 flush 帶了別的 session 的變更」判定**：這涵蓋殘留 session 的 roll-forward、進度寫入等真的會被一起寫出的變更。另一種情形是別的程式路徑在沒有任何別的 session 變更時呼叫 flush（只會帶出本 test 的 session）：它在結構上無法與本 test 自己的 flush 區分，只能由 `M5_JOURNAL_WINDOW` 列出的呼叫端診斷，沒有列為判定條件。
6. **多做的 mutation**：派工要求 Step 2 的 server.save 與 Step 3、Step 4 各一個；另外加上 F4A0-M1-BYPASS 的 G-RFB 殘留 session flush、F4A0-M1-AUTOSAVE 與 F4A0-M2-NO-MARKER，證明失敗訊息能區分旁路的種類，以及 marker 讀不到時不會默默通過。

### 觀察（未修改）

1. **Windows 的 journal 原子移動偶發 `AccessDeniedException`**（`quantumchamber_sessions.dat.<n>.tmp -> quantumchamber_sessions.dat`）：本 task 的 Step 1 後與 GREEN 第 1 輪各遇到一次；之前沒有 IO 注入的一次在 `gate-f2a-fix2-green-full-1934f384…/execution/runtime-logs/latest.log:301`（`m5_h3_quarantine_converges` 的入場）。fix round 1 更正：原文的「之前的 Full gate log（含 F3d 最終 run-all）中也有」不成立，F3d 最終 run-all 的那幾行是注入與受控故障（見上表「Step 1 後（focused）」列）。production 照設計 fail closed（保留 dirty 與上一份 durable、之後重試），但會多一則 WARN；斷言「這個 sid 沒有 WARN」的 test（例如 F3a G3 的 `noStaleAuthorityReturnPending`）因此偶發失敗。
2. **原生存檔 checkpoint 與走廊清理的就緒條件不一致**：`CorridorPageManager.leaseReady`（`CPM:1032`）只檢查 chunk 是 FULL、已載入、會 tick，而 `ChunkSaveCheckpoint` 另外要求 entity storage 已載入（`ChunkSaveCheckpoint.java:73`）。entity storage 是非同步載入的，兩者之間有空檔時 checkpoint 丟 IllegalStateException，走 per-space 退避（20 ticks 起跳）後重試，多一則「MEASURED 保留 receipt 等待安全返還」WARN。GREEN 第 1 輪的 G-NLIVE 因此失敗一次；所有已保存的 gate log 中只有這一次。production 會自行恢復；沒有修改（不在本 task 的允許路徑）。
3. G-SEAL 的 `collapseServiceNeverOverwrote` 仍以字串「塌縮流程」比對 WARN；同一個 test 的 `collapseFlowOnlySawPendingRollForward` 已依 marker 涵蓋它，沒有改。

### 交給後續

- F4a：
  - （F3d quality re-review m-4）G-SEAL 在確認的那一個 tick，塌縮流程會看到沒掛 hook 的 flushed SEALED（PROJECTION_FAILED），要到下一個 callback 才收尾。F4a 實作 SEALED 重試時，探針在放行第 4 次寫出時順手 `M5TestHooks.suspendCollapse(sid)`，或確認邊緣觸發不會成立。本 task 沒有處理。
  - （F3d fix round 3 spec re-review 的觀察）H2（`m5_h2_checkpoint_scope`）若有 autosave 落在兩次 checkpoint 之間，約有 1% 的機率多出一則原生 ERROR 或 WARN，斷言不受影響。比對 Full gate 的 log 數字時，可以先從這裡查（本 task 的 run-all 沒有出現）。
  - `CollapseService` 新增的 log 一律帶 `LOG_MARKER`；能歸屬到 session 的 WARN／ERROR 訊息要帶 `session=<UUID>`，不屬於任何 session 的改帶 `TICK_LOG_MARKER`。F3d「交給後續」中「新增的 WARN 要沿用 `warn()` 的『塌縮流程的…』前綴」那一條由此取代。fix round 1 起這是守門的規則：F4a 新增的 collapse 類別（例如 `DestinationResolver`）同樣適用，`CollapseLogMarkerGuardTest` 掃描整個 collapse package；`CollapseAuthorities` 的 7 則 log 由 F4a 改成帶 marker，並刪除 `LEGACY` 清單，之後不再新增例外。`UniverseLifecycleService` 不記塌縮流程的 WARN。以上兩點都是 controller 的裁示，見 Fix round 1 的「裁示」。（controller 更正：原本寫的「或經 review 加進 `LEGACY` 清單」「或同步擴充守門」不再適用。）
  - F3d「交給後續」中「之後新增靠 journal 的 flush 探針計數、而且跨越 200 ticks 退避的 GameTest，以 `AutosaveWindow.untouched` 斷言」那一條，改為以 `afterAutosaveClear`＋`journalWrites(server, 計數, 自己的 session)` 斷言 `onlyProbed()`；test 自己建立的每一個 session 都要放進 `own`，否則它的 flush 會被判為「其他 session 的 flush」。
  - 新的 M5 test 用 `M5GameTestSupport`（用法與前提見 `scripts/verification/README.md`「新增 M5 GameTest」）；SRM 的 RETRY_FLUSH WARN 文字（「返還保留 pending」）仍待 F4a 改成不限返還，本 task 沒有動。fix round 1 之後：探針經 `installFlushProbe(context, probe, hooks)`、稽核經 `afterAutosaveClear` 安裝，位置被占用時丟出；用探針或稽核的 test 必須獨占一個 batch；`DormantOrigin.whenRegistered`、`NetherChamber.whenEntered(ready)` 以呼叫當下的 tick 為基準，可以在 callback 裡呼叫。
- 觀察第 1、2 條（Windows `AccessDeniedException`、checkpoint 與 `leaseReady` 的就緒條件）的 owner 由 controller 指定（SDD ledger）：第 2 條 F4b（與 F4b 的投影共用同一個 checkpoint，判斷是否要在 `leaseReady` 加上 entity storage 的檢查），第 1 條 F6（「斷言沒有 WARN」的 test 的穩定性，整理 harness 時一併處理）。fix round 1 的最終 focused 第 1 輪又遇到一次第 2 條（G-NLIVE，見「Fix round 1」）。
- controller 依兩項最終 review 補充：
  - F4a：
    - plan 要求的物化失敗、ledger IO 失敗 mixin，所讀取的靜態 hook 一律做成 `M5TestHooks.Slot`，並經 `installHook` 安裝。規則已寫入 README（quality re-review Minor 2）。
    - 依賴 `collapseFlowNeverWarned` 檢查權威寫入故障的 test，要等 `CollapseAuthorities` 的 7 則 log 補上 marker 之後才可信（quality re-review Minor 1）。
  - F6：
    - `build.yml` 的明列清單要加上 `harness_hook_slots_fail_fast_and_leave_no_residue`（跨平台，default run；spec re-review m-1）。
    - 小幅重複（quality re-review Minor 3）：
      - 讀取 `TestContext.test` 的反射有兩份：`M5GameTestSupport.gameTestState`、`M5U.scheduledCount`。可考慮改成 accessor mixin。
      - JUnit 的 log4j appender 有三份：`CollapseServiceTest`、`CollapseAuthoritiesTest`、`SessionRecoverySchema4Test`。

### Fix round 1（`b333feb`、`a8863de`、`bab3474`、`d5a372e`；README `5bd9844`）

- Review：spec review ✅（Minor 3：m-1～m-3）、quality review Approved（Minor 5：Q1～Q5）。這些 Minor 大多落在 F4a-0 自己的共用測試檔，F4a 的 plan 改不到，而且 F4a 新增第一個 test 時就會用上，controller 指派本輪一次處理：Q1～Q4、Q5 的部分項目、spec m-1～m-3。原 implementer 的 context 過長，本輪由新的 implementer 接手。開工 HEAD 是 `d667a04`，worktree 乾淨。
- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `b333feb` `test: share log capture and sid witness helpers in M5 support`（parent `d667a04`）：Q5 的純搬移。`SUP`、`M5C`、`M4C`（只有 `use` 與 `registeredIdle` 兩處）。
  - `a8863de` `test: guard the M5 hook slots and anchor fixture schedules at the call tick`（parent `b333feb`）：Q1、Q2。`SUP`、`HK`、`M5C`。
  - `bab3474` `refactor: route pending door outcomes through applyOutcome and applyFailure`（parent `a8863de`）：spec m-2。production 只有 `CS`；JUnit `CST`。
  - `d5a372e` `test: require the collapse log marker on every collapse package log call`（parent `bab3474`）：Q3 的守門，新增 `src/test/java/dev/quantumchamber/collapse/CollapseLogMarkerGuardTest.java`。
  - `5bd9844` `docs: state the M5 GameTest support rules and preconditions in the README`（parent `d5a372e`）：Q3 的規則與 Q4，只改 `scripts/verification/README.md`「新增 M5 GameTest」一節。
  - 本小節由其後的 docs commit `docs: record F4a-0 fix round 1 in the M5 note` 加入；同一個 commit 也更正首輪的段落（spec m-1、m-3），並更新本節的 Review 行與「交給後續」。
- Code range `d667a04..d5a372e`。沒有改 `M5UniverseGameTests.java`（它只用 `DormantOrigin.whenRegistered`，簽名不變）、mixin 與 `quantumchamber-test.mixins.json`、`LogCapture.java`、`M2CorridorGameTests.TestHooks`、`SessionRecoveryState.java`、`CollapseAuthorities.java`、`build.gradle`、`.github/`、`scripts/verification/` 的腳本。
- 本小節的行號以 `d5a372e` 為準（簡稱同前；`GT` 指 `CollapseLogMarkerGuardTest`）。`git diff d667a04 5bd9844 -- <path> | sha256sum` 的前 8 碼（`fix1/diff-hashes.txt`）：`src/main` `a38b4c55`、`src/test` `5abe1814`、`src/testmod` `fc715611`、README `27bebfe0`。

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q1(a)：`installFlushProbe`、`startJournalAudit` 先設欄位、後 `hooks.add`；前一個 callback 已 `releaseAfter` 時 `hooks.add` 丟出，探針或稽核殘留在 JVM | 探針與稽核改為 `HK.Slot`（`HK:72`；`FLUSH_PROBE` `HK:38`、`JOURNAL_AUDIT` `HK:40`），一律經 `SUP:146` `installHook` 安裝：先 `hooks.add(移除)`，hooks 已釋放時在這裡就丟出、什麼都還沒裝。`Slot.remove`（`HK:95`）只在位置仍是同一個值時生效，所以安裝失敗時登記的移除不會動到持有者。`installFlushProbe(context, probe, hooks)`（`SUP:124`）與 `installJournalAudit(context, hooks)`（`SUP:130`；`afterAutosaveClear` 改用它）都經它，`HK.startJournalAudit` 刪除；M5C 的 8 個呼叫點補上 context | harness（下一列）；FIX1-Q1-ORDER |
| Q1(b)：兩個 test 同時使用時，後裝的默默覆蓋前者 | `Slot.install`（`HK:86`）：位置已被占用就丟 `IllegalStateException`、不覆蓋。訊息列出 hook、持有者（`GameTestState` 的 template path，附「執行中」或「已結束而沒有釋放」）、這次安裝的 test，並提醒用探針或稽核的 test 必須獨占一個 batch | harness；FIX1-Q1-OVERWRITE |
| Q1(c)：tickLimit 逾時不釋放 hooks，殘留的稽核一直抓堆疊 | 兩道：一是下一次安裝時 fail-fast，訊息寫出持有者「已結束而沒有釋放」（(b) 的規則）；二是安裝時另在這個 test 的原版 `GameTestState` 登記一個 `TestListener`（`SUP:155` `ReleaseOnCompletion`），test 結束時（通過、失敗或逾時）位置若仍是這個值就移除，記一行 `M5_TEST_HOOK_RESIDUE` WARN。選第二道的理由：原版 `GameTestState.tick` 在 test 完成的同一個 tick（`tickTests` 之後）就呼叫每個 listener 的 `onPassed`／`onFailed`，逾時也一樣（`tickTests` 以 `fail(TickLimitExceededException)` 結束；以 `javap -c -p` 讀 Yarn 1.21+build.9 的 `GameTestState` 確認），所以殘留在逾時的那一個 tick 就被移除，不會繼續抓堆疊、不會對之後別人的 flush 注入失敗，之後的 test 也不會因為別人的殘留而失敗；只靠 fail-fast 的話，殘留會一直留著，之後每個用探針的 test 都失敗。`GameTestState` 以反射讀 `TestContext.test`（`SUP:175`，與 `M5U.scheduledCount` 讀同一個欄位）。只移除探針與稽核；hooks 上其他的收尾在逾時時仍不會執行（既有行為，不在本輪範圍） | FIX1-Q1-TIMEOUT；對照 FIX1-Q1-TIMEOUT-NOLISTENER |
| Q1 的證明 | 新增常駐 harness `M5C:1230` `harness_hook_slots_fail_fast_and_leave_no_residue`（batch `m5_collapse_harness_hook_slots`，default run；不碰 journal、不建 fixture，各平台都執行）：(a) 以已釋放的 hooks 安裝探針與稽核，斷言丟出「已釋放」而且位置仍空（先單獨判定，殘留會讓後面的安裝失敗、遮住原因）；(b) 持有者裝好之後，另一組 hooks 再裝探針與稽核，斷言立即丟出、訊息含 hook 名稱、持有者與這次安裝的 test，位置沒有被覆蓋，那組 hooks 釋放時也不動持有者；(c) 持有者釋放之後兩個位置都空、可以再裝，最後仍空。放在 default run 的 `M5C`，因為用這兩個位置的都是 default run 的 test，也不碰 catalog | GREEN；FIX1-Q1-ORDER、FIX1-Q1-OVERWRITE 都在目標 witness FAIL |
| Q2：`DormantOrigin.whenRegistered` 與 `NetherChamber.whenEntered` 混用相對（`waitAndRun(2)`）與絕對（`whenTicks(3, …)`）tick；`DormantOrigin` 的供電不經 hooks | 二擇一取前者：兩者都以呼叫當下的 `context.getTick()`（t0）為基準，t0+2 供電、從 t0+3 起有界等待（`SUP:454`、`SUP:530`），供電改經 `at(…, hooks, …)`。`NetherChamber` 在 `open` 記下 context 與 hooks，`whenEntered(ready)` 不再傳這兩個（M5C 的兩個呼叫點）。test 方法本體的 t0 是 0（原版 `startCountdown` 設 `startTime = time + setupTicks`，`tickTests` 在 `tick = time − startTime` 第一次 ≥0 時才呼叫 test 方法），所以既有 5 個呼叫點的排程與首輪逐 tick 相同 | focused 前後相同；FIX1-Q2-LATE-CALL；對照 FIX1-Q2-LATE-CALL-OLD |
| Q3：marker 的涵蓋仍靠慣例 | README 改寫成規則（下一列）。新增 JUnit `GT`：從 `CollapseService` 的 class 所在位置（`build/classes/java/main`）往上找 `src/main/java/dev/quantumchamber/collapse/`，找不到再從工作目錄往上找（Gradle test 的工作目錄是 `run/testworlds`，`build.gradle:273`），都找不到就失敗；掃描其下所有 `.java`（含子 package）。比對前先把註解換成空白、字串／字元常值／text block 的內容遮掉（索引與行號不變），所以跨行的呼叫照樣抓得到，註解與字串裡的字樣不算。規則：每個 `LOGGER.trace/debug/info/warn/error(` 的第一個參數必須是 `LOG_MARKER` 或 `TICK_LOG_MARKER`（可加 `CollapseService.`）；logger 只能是 `LOGGER = LoggerFactory.getLogger("quantumchamber")`（`LogCapture` 依 logger 名稱擷取，class 名稱的 logger 看不到）；其他名稱的 `Logger`（別名）、宣告以外的 `getLogger(`、`LOGGER` 當成值（方法參考、參數）、`LOGGER` 的其他方法（`is*Enabled` 除外）、`LogManager`、`System.out/err`、`printStackTrace` 都算違規；只有 `CollapseService` 可以宣告這兩個 marker。寫明不處理的：別的 package 代為記錄的 log、反射；把 marker 存進別名常數會被當成沒有 marker 而失敗。`CollapseAuthorities` 既有的 7 則沒有 marker 的 log 逐則列在 `GT:41` `LEGACY`（檔名、等級、第一個字串常值的開頭），每一則必須恰好對到一個呼叫（見「偏離」第 3 條）。第二個 test 以合成的原始碼驗證掃描器本身：跨行、限定名稱、註解、字串、text block、`is*Enabled` 合規；別名、class 名稱的 logger、方法參考、當成參數、`atWarn`、`LogManager`、`System.err`、`printStackTrace`、別的類別宣告 marker、沒有宣告 LOGGER 都違規 | GREEN 2/2；FIX1-Q3-DROP-MARKER（RED）、FIX1-Q3-ALIAS-LOGGER |
| Q3 的規則；Q4：README 補上使用前提 | README「新增 M5 GameTest」一節：開頭補上 (d)「兩個類別不互相借用 helper，需要共用時先搬進 `M5GameTestSupport`」；flush 探針一點補上位置的安裝順序、占用時 fail-fast、TestListener，以及 (b)「`FlushFault` 的條件只看 journal 狀態、不分呼叫端，帶著同一份未確認變更的任何 flush 都會被計數，前 N 次之內也會受控失敗」；窗口一點補上 (a)「`own` 必須包含 test 自己建立的每一個 session」；塌縮流程的 log 改寫成規則（守門、例外清單、collapse package 以外不在掃描範圍）；新增「前提」(c)：用探針或稽核的 test 獨占一個 batch、有界等待的總長度小於 tickLimit（逾時時只有探針與稽核會被移除）、fixture 依 Q2 以呼叫當下的 tick 為基準（在 callback 裡呼叫時會在迭代 runAtTick 表的途中插入項目）；fixture 一點改寫 `whenRegistered`／`whenEntered` | — |
| Q5：殘留的重複 | `SUP:363` `captureLogs(hooks)` 取代 M5C 的 9 處「建 `LogCapture`＋登記 `capture::close`」與 `DormantOrigin.open` 的 1 處；只包著 capture 的 3 個 try 區塊（G-NETHER、G-NLIVE、G-SEAL）整個去掉。建立之後 `hooks.add` 失敗時，它會先關閉 capture（原本會留下掛在 root logger 上的 appender）；這是唯一的行為差異，只在失敗路徑。`SUP:379` `noWarnOrError(capture, sid)` 取代 4 處「這個 sid 沒有 WARN／ERROR」（G-SRC、G-DOR、G-NETHER、G-NLIVE；G-DOR 對 startup sid 只看 ERROR 的部分照舊）。M4C 的 `use`（`M4C:1069`）改為轉呼叫 `SUP.interact`（11 個呼叫點不動）；`registeredIdle(TestContext, frame)` 刪除，唯一的呼叫點（`M4C:1703`）改用 `SUP.registeredIdle(world, frame)`。G-SEAL 的 `collapseServiceNeverOverwrote` 沒有動 | 搬移前後 focused 相同（下表「開工前」與「Q5 後」） |
| spec m-2：m-3 的 JUnit 照抄 `applyDoor` 的接線 | `CS:550` `applyOutcome(door, outcome, now)`：依門記下的 `retried`、`warned` 推導 `doorStep`；完成時（之前記過 WARN 才記一則 INFO）回 true，`applyDoor`（`CS:542`）據此移除這扇門；沒有完成時經 `recordDoorStep` 記錄、退避，回 false。`CS:561` `applyFailure(door, failure, now)`：`tick()`（`CS:179`）與 `openDoor()`（`CS:522`）的 catch 共用。`recordDoorStep`（`CS:569`）改為 private、不回傳。行為一致：只有 `doors.remove` 與完成 INFO 的先後對調（同一個 server thread，INFO 不讀 `doors`）。JUnit `CST:168`：真的 `PendingDoor` 經 `applyOutcome`／`applyFailure` 走首輪的 8 步，另以只收 `quantumchamber` logger 的 appender（`CST:267` `DoorLogs`，先以 `LOG_MARKER` 送一則探針，確認攔得到 log 與 marker）逐步計 WARN：`[1,0,1,0,1,0,1,0]`；斷言原因依序、每則 WARN 帶 `LOG_MARKER`／chamber／session、只有套用例外的 WARN 附 stack、未完成的 6 步都回 false、APPLIED／UNCHANGED 回 true、完成時只有記過 WARN 的門記一則 INFO。套用例外的原因改由 production 的 `FailureText.describe` 產生（`套用失敗：java.lang.IllegalStateException: x`）。仍是同一個 `@Test` | FIX1-SM2-WARNED-NULL、FIX1-SM2-BYPASS-RECORD、FIX1-SM2-FAILURE-WARNED-NULL |
| spec m-1、m-3：note 的證據敘述 | 首輪「Gate 與證據」的「Step 1 後（focused）」列與「觀察」第 1 條改引 `gate-f2a-fix2-green-full-1934f384…/latest.log:301`，並寫明 F3d 最終 run-all 的那幾行是注入與 `m2_final_flush` 的受控故障；「GREEN JUnit（focused）」列寫明 log 沒有保存、改引 run-all 的 JUnit XML；mutation 的工作樹多了 README 一事補在表後。各處都標「fix round 1 更正」 | 已逐行核對：F2a 那份 `latest.log` 的 `:244` 開始 batch `m5_h3_quarantine_converges`，`:251` 是入場 ARMING 未能 checked 落盤的 WARN，`:301` 是它的 `Caused by`；F3d 最終 run-all 那 4 行（`:2525`、`:2578`、`:9553`、`:9562`）依前一行「Running test batch」歸屬；6 份 mutation 與 GREEN M5 的 state 檔都列出 README `99ff474` |

測試：
- default GameTest 173 → 174：新增 `m5_collapse_harness_hook_slots` 1 個（名稱不以 `_windows` 結尾，`_windows` 仍是 88；不呼叫 `M4PerTestUniverseProbe.begin`，per-test receipt 仍是 51＋1）。legacy 1、M5 5 不變。
- JUnit 570 → 572：新增 `GT` 的 2 個 test；`CST` 仍是 7 個（m-2 改的是既有的 test）。

Gate 與證據：逐輪的 state、摘要與 log 統計在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4a0/fix1/`。`fix1/state.ps1`、`fix1/gate.ps1` 比照首輪（路徑多一層）；`fix1/junit.ps1` 先記 state，再以 Gradle `test --tests <class>` 跑並保存 console、摘要與 XML；`fix1/logstats.ps1` 從 gate 的 `latest.log` 計 WARN／ERROR 行數，摘出 `M5_JOURNAL_WINDOW`、`M5_TEST_HOOK_RESIDUE` 與 batch 順序；暫時性改動由 `fix1/adapter.py` 套用與還原（套用前保存原檔，還原後以 `git hash-object` 核對），各 adapter 的 diff 在 `fix1/adapters/<名稱>/`。focused 的範圍是首輪的 34 個 batch（`fix1/focused-batches.txt`，與 `f4a0/focused-batches.txt` 相同），加上 harness 之後是 35 個；M5 run 一律跑全部 5 個。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| 開工前（focused） | `d667a04`，乾淨（`fix1/fix1-before-focused-state.txt`） | `gate-fix1-before-focused-fa7c16da8c5e4b9eb1a650870b6a5cbd` | PASS 34/34；WARN 28、ERROR 19；G-RFB start=6149、counted=[6160, 6160, 6360, 6760]，G-SEAL start=12115、counted=[12115, 12115, 12315, 12715]，兩者 `problems=[]` |
| 開工前（M5） | 同上 | `gate-fix1-before-m5-6198ad8884724378bdc3858497c4588b` | PASS 5/5 |
| Q5 後（focused） | `d667a04`＋Q5 的工作樹，3 個檔的 blob 與 `b333feb` 相同（`fix1/fix1-q5-focused-state.txt`） | `gate-fix1-q5-focused-507a97b426b34c1da2f82da62206b41a` | PASS 34/34，testcase 集合與每一個的結果都與開工前相同；WARN 28、ERROR 19；G-RFB、G-SEAL 的計數型態相同（+11、+11、+211、+611／+0、+0、+200、+600），`problems=[]` |
| Q5 後（M5） | 同上 | `gate-fix1-q5-m5-5805e371611b49a99309b40818ad28d3` | PASS 5/5，與開工前相同 |
| Q1／Q2 開發（保留，不採計） | `b333feb`＋Q1、Q2 的工作樹（harness 還沒把 (a) 拆成單獨的判定） | `gate-fix1-q1q2-focused-79d229074574452db3614e179048e474`、`gate-fix1-q1q2-m5-9edb3c6e459a4953b845e38201005710` | PASS 35/35、5/5 |
| GREEN（Q1、Q2） | `b333feb`＋工作樹，3 個檔的 blob 與 `a8863de` 相同（`fix1/fix1-green-b-focused-state.txt`） | `gate-fix1-green-b-focused-b2e0937ab6f846df81e8a8a09922d742`、`gate-fix1-green-b-m5-1b94b34172a2459f89cd7b8589f6281f` | PASS 35/35（原本 34 個的集合與結果與開工前相同，另加 harness）、M5 5/5；WARN 28、ERROR 19；沒有 `M5_TEST_HOOK_RESIDUE` |
| FIX1-Q1-ORDER | `d5a372e`；adapter `fix1-q1-order`：`installHook` 改回「先安裝、後登記 hooks」 | `gate-mut-fix1-q1-order-ba8792d08a3946da87eab26177f37a80`（只跑 harness） | harness FAIL 在 (a)：`releasedHooksLeftNoProbe=false`、`releasedHooksLeftNoAudit=false`（`releasedHooksRejected=true`：舊順序同樣丟出，但值已經裝上、殘留） |
| FIX1-Q1-OVERWRITE | 同上；`Slot.install` 不檢查占用 | `gate-mut-fix1-q1-overwrite-8171787bf87f467b80d3d90e7c36ab88`（只跑 harness） | harness FAIL：`occupiedProbeRejected`、`occupiedAuditRejected`、`occupiedNotOverwritten`、`intruderReleaseKeepsHolder` 都是 false |
| FIX1-Q1-TIMEOUT | 同上；harness 改到 batch `m5_collapse_hook_residue_t1`，裝上探針與稽核之後不釋放、不完成。選這個名稱，是因為在這 8 個 batch 的 HashMap（容量 16）中只有它落在 bucket 0，一定最先執行（log 的 batch 順序證實） | `gate-mut-fix1-q1-timeout-f9d33a6e5de64e508775c63075f9a533`（它與 G-TERM、G-SEAL、G-RFB、G-FB、G-SRC、G-OFF、G-DOR） | 它以「Didn't succeed or fail within 100 ticks」FAIL，同一個 tick 記兩行 `M5_TEST_HOOK_RESIDUE`（探針、稽核，`failed=true`）；之後執行的 7 個都 PASS，G-SEAL、G-RFB 的 `M5_JOURNAL_WINDOW` 都是 `problems=[] status=PASS` |
| FIX1-Q1-TIMEOUT-NOLISTENER（對照） | 同上，再拿掉 TestListener 的登記 | `gate-mut-fix1-q1-timeout-nolistener-075387687a7f4d9a8fa6b2648646650d`（同樣 8 個 batch） | 它同樣逾時 FAIL，沒有 `M5_TEST_HOOK_RESIDUE`；之後裝探針或稽核的 6 個 test 都在安裝時 FAIL：「journal flush 探針 已被占用，不覆蓋：持有者 m5collapsegametests.harness_hook_slots_fail_fast_and_leave_no_residue（已結束而沒有釋放），這次安裝來自 …（執行中）。…」（G-SEAL、G-RFB 是「journal 寫出稽核 已被占用」）；不用探針的 G-DOR PASS。即 (c) 的第二道：殘留沒有被移除時，下一次安裝以清楚的訊息 fail-fast，不會默默覆蓋 |
| FIX1-Q2-LATE-CALL | 同上；G-TERM 的 `whenRegistered` 改在 `at(context, 10, …)` 的 callback 裡呼叫，記一行 `F4A0_FIX1_Q2_LATE_CALL` | `gate-mut-fix1-q2-late-call-fe9bd06af51b44848fea031d58787a28` | PASS；`registered=13 now=13`（t0=10：12 供電、13 起檢查） |
| FIX1-Q2-LATE-CALL-OLD（對照） | 同上，`whenRegistered` 再換回首輪的寫法 | `gate-mut-fix1-q2-late-call-old-78e158bec9784675b040aea10bcd61b1` | 也 PASS，但 `registered=4 now=12`：交給 callback 的 tick 比實際早 8，之後以它排的檢查都排到過去的 tick。即 quality 指出的「順序與間隔都錯、不會報錯」 |
| GREEN JUnit（spec m-2） | `a8863de`＋`CS`、`CST` 的工作樹，blob 與 `bab3474` 相同（`fix1/fix1-sm2-green-junit-state.txt`） | Gradle `test --tests …CollapseServiceTest`（`fix1/fix1-sm2-green-junit.log`、`-summary.txt`、XML） | 7/7 |
| FIX1-SM2-WARNED-NULL | 同上；`applyOutcome` 推導 `doorStep` 時以 null 代替 `door.warned` | 同上（`fix1/mut-fix1-sm2-warned-null-junit.*`） | `pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings` FAIL：WARN 序列 `[1,1,1,0,1,1,1,0]`，WARN 的原因多了重複 |
| FIX1-SM2-BYPASS-RECORD | 同上；`applyOutcome` 不呼叫 `recordDoorStep` | 同上（`fix1/mut-fix1-sm2-bypass-record-junit.*`） | 同一個 test FAIL：WARN 序列 `[0,0,0,0,0,0,1,0]`，門記下的原因前 6 個是 null，退避次數不符 |
| FIX1-SM2-FAILURE-WARNED-NULL | 同上；`applyFailure` 以 null 代替 `door.warned` | 同上（`fix1/mut-fix1-sm2-failure-warned-null-junit.*`） | 同一個 test FAIL：WARN 序列 `[1,0,1,0,1,0,1,1]` |
| GREEN JUnit（Q3） | `bab3474`＋`GT`，blob 與 `d5a372e` 相同（`fix1/fix1-q3-green-junit-state.txt`） | Gradle `test --tests …CollapseLogMarkerGuardTest`（`fix1/fix1-q3-green-junit.*`） | 2/2 |
| FIX1-Q3-DROP-MARKER（RED） | 同上；`CS:387` RESOLVE stub 的 WARN 拿掉 `LOG_MARKER` | 同上（`fix1/mut-fix1-q3-drop-marker-junit.*`） | `everyCollapseLogCallCarriesTheCollapseMarker` FAIL：`CollapseService.java:387 LOGGER.warn 的第一個參數不是 LOG_MARKER／TICK_LOG_MARKER：目的地解析尚未實作（F4a）：…`；掃描器本身的 test 照常 PASS |
| FIX1-Q3-ALIAS-LOGGER | 同上；`CS` 加 `Logger FLOW = LoggerFactory.getLogger(CollapseService.class)`，RESOLVE stub 改用 `FLOW.warn(LOG_MARKER, …)`（帶 marker） | 同上（`fix1/mut-fix1-q3-alias-logger-junit.*`） | FAIL：`CollapseService.java:78 別名 logger：FLOW`、`getLogger 只能用在 LOGGER 的宣告` |
| 最終 focused 第 1 輪（保留，不採計） | `5bd9844`，乾淨（`fix1/fix1-final-focused-state.txt`） | `gate-fix1-final-focused-e9914a241dc04973914d38eaaf0a5677` | 34/35：G-NLIVE `noWarnOrError=false`。唯一多出的 WARN 是「MEASURED 保留 receipt 等待安全返還」，cause「相關 chunk／entity storage 尚未完整載入」，即首輪「觀察」第 2 條（`fix1/fix1-final-focused-failure.txt`）。這個 witness 的判定與首輪相同（Q5 只換成 helper） |
| 最終 focused 第 2 輪 | 同上（`fix1/fix1-final-focused-2-state.txt`） | `gate-fix1-final-focused-2-47c75a41864741a3bd8150acb9444ddb` | PASS 35/35；WARN 28、ERROR 19；G-RFB start=6291、counted=[6302, 6302, 6502, 6902]，G-SEAL start=12118、counted=[12118, 12118, 12318, 12718]，`problems=[]` |
| 最終 M5 | 同上 | `gate-fix1-final-m5-336c75a93c2b441aa0bdfe5243901573` | PASS 5/5 |
| run-all | `5bd9844`，`STATUS` 為空（`fix1/run-all-console.log`） | `run-all-32c099d096b049e98e6e8e21d773ff1f`（Full：`gate-full-4c9f70b4c91f421f9be75ad41b2d172c`） | `END ALL exit=0 HEAD=5bd984418fddf0553a2eba1684f5cf419db5b115`，約 17 分鐘（20:29:28→20:46:44）。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=5/5 perTest=51+1 junit=572 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=367entries/320classes sources=212 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。與首輪 run-all（`run-all-93e36a3b…`）只差 default 173 → 174（harness）與 JUnit 570 → 572（`GT` 的 2 個），其餘逐項相同。`STATUS` 沒有任何變更，本目錄的 `.ps1`／`.gradle` 都沒有改，依 README 的採計規則以 C＝`5bd9844` 採計。Full 裡 G-RFB start=13666、counted=[13677, 13677, 13877, 14277]，G-SEAL start=100466、counted=[100466, 100466, 100666, 101066]，兩者 `M5_JOURNAL_WINDOW … problems=[] status=PASS`；harness 通過 |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對）。GameTest 的 mutation 跑在 `d5a372e` 的乾淨工作樹上（README 的修改在其後）；JUnit 的 mutation 跑在各自 GREEN 的工作樹上。4 個 code commit 的 blob 都與對應 GREEN 的 state 相同。
- release JAR（`fix1/jarcmp-release.txt`，與首輪 run-all `run-all-93e36a3b…` 的 JAR 比較）：entry 清單完全相同（367／320）。內容不同的只有 `CollapseService` 與它的 5 個巢狀 class：`CollapseService.class` 多了 `applyOutcome`、`applyFailure`，`recordDoorStep` 改為 private void；`$1`、`$DoorStep`、`$EntryWatch`、`$PendingDoor`、`$Track` 以 `javap -c -p` 去掉行號表之後完全相同（原始檔的行數位移）。sources JAR 212 個 entry 相同，只有 `CollapseService.java` 不同。新的 test 都不在 release 與 sources JAR，testmod 命中仍是 0。
- log（run-all 的 Full gate，以 `/ERROR]`、`/WARN]` 行計，`fix1/wb-run-all.txt`）：ERROR 28 → 28，WARN 55 → 54。依 batch 分組、正規化 UUID 與數字（另把負號併入）之後比對（`fix1/wbdiff.txt`），唯一的差異是首輪 run-all 多出的 `m2_task4_disconnect_io`「艙體 … 的 session 協調失敗，保留返還保護」這次沒有出現（首輪已記為既有的偶發）；與 F3d 最終 run-all 的 54／28 相同。harness 沒有 WARN；整個 Full 沒有 `M5_TEST_HOOK_RESIDUE`。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

偏離派工字面之處：
1. **`recordDoorStep` 改為 private、不回傳**：派工只要求抽出 `applyOutcome`／`applyFailure` 並改呼叫點。它的回傳值原本只給 JUnit 用，JUnit 改呼叫 `applyOutcome`／`applyFailure` 之後就沒有用處，留著會讓人以為還有別的呼叫端。production 行為不變。
2. **Q1(c) 兩道都做**：派工允許「下一次安裝時 fail-fast」或更好的做法。fail-fast（(b) 的規則）照做；另加 TestListener，理由見上表。代價是以反射讀原版 `TestContext.test`（`M5U` 已有同樣的寫法）。
3. **Q3 的守門有例外清單**：派工要求掃描 collapse package 的「所有 `.java`」並要求 marker，但 `CollapseAuthorities`（F2b）有 7 則沒有 marker 的 log，而它不在本輪的允許路徑。依 README 的規則（「`CollapseService` 與 F4a 起新增的 collapse 類別」），它不屬於塌縮流程，所以逐則列為例外（不是整個檔案豁免）：多一則沒有 marker 的 log、或例外清單對不到呼叫，都會失敗。守門另外檢查 logger 名稱、別名與繞過，比派工要求的多。
4. **harness 放在 default run**：派工建議比照 F3c 在 `M5U` 的 harness 寫法。用這兩個位置的都是 default run 的 test，harness 也不碰 catalog，所以放在 `M5C`；寫法（witness 表、失敗路徑先釋放）比照 F3c。default 因此多 1 個 test。
5. **Q2 多做兩個 mutation**：派工沒有要求 Q2 的 RED。為了證明新的寫法可以在 callback 裡呼叫、舊的寫法不會報錯，加跑 FIX1-Q2-LATE-CALL 與它的對照。
6. **spec m-3 的「6 份 mutation」**：核對 state 檔時發現 GREEN M5 的工作樹也有同一份 README，一併寫明。

裁示（controller，2026-10-02，原「需要裁示」）：
1. `CollapseAuthorities` 的例外清單：本輪接受；**F4a** 把這 7 則改成帶 marker（只改 log，不改行為），並刪除 `LEGACY` 清單與 `CollapseLogMarkerGuardTest` 中相關的 Javadoc（`:28-30`）。之後不再新增例外。理由：F4a 的 RESOLVE 會大量經過 `CollapseAuthorities`。在補上 marker 之前，權威寫入未確認的 WARN 不會被 `collapseFlowNeverWarned` 看到。
2. 守門範圍維持只掃 collapse package。F4a 的設計準則：`UniverseLifecycleService` 的新 API（`materializeAllocated`／`resolveActive`）只回傳結果，不記塌縮流程的 WARN；塌縮流程的 WARN 一律在 collapse package（`DestinationResolver`／`CollapseService`）帶 marker 記錄。M3 既有的 log 不動。

## F4a：RESOLVE（lifecycle 物化、EXISTING 重驗、NEW 配置）與 SEALED

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `4bb07b3` `refactor: tag the M5 authority logs with the collapse marker and widen the recovery pending warning`（parent `e4e1b2f`）：補充第 8、9 點。production `collapse/CollapseAuthorities.java`（7 則 log 加 marker、class Javadoc）、`persistence/SessionRecoveryManager.java`（只有 WARN 文字）；JUnit `CollapseLogMarkerGuardTest.java`（刪除 `LEGACY`）；testmod `M4CandidateDoorGameTests.java`、`M5CollapseGameTests.java`（各一處斷言改成新文字）；`scripts/verification/README.md`（刪除例外說明）。
  - `458c297` `feat: let the universe lifecycle materialize and resolve allocated universes at runtime`（parent `4bb07b3`）：補充第 1 點，只有 `universe/UniverseLifecycleService.java`。
  - `04c7fea` `feat: resolve collapse destinations through the ledger, catalog, lifecycle and discovery`（parent `458c297`）：補充第 2–4 點。新增 `collapse/DestinationResolver.java`、`collapse/ResolveResult.java`、JUnit `DestinationResolverTest.java`。
  - `c825a49` `feat: run RESOLVE from the collapse service and enter and retry SEALED`（parent `04c7fea`）：補充第 5–7、10、12、13 點。production `collapse/CollapseService.java`、兩個語言檔（「進入 SEALED」）；JUnit `CollapseServiceTest.java`；testmod `M5UniverseGameTests.java`（G-RES 改寫成三個 test）、`M5GameTestSupport.java`（`existingReceipt`、`overlayKeys`）、`M5CollapseGameTests.java`（只有 G-SEAL 的探針與一個 witness）。
  - `64ffaf9` `test: take the ineligible EXISTING authority snapshot after the receipt is written`（parent `c825a49`）：只有 `M5UniverseGameTests.java` 的 G-INELIGIBLE（見「Gate 與證據」的 D-FRESH）。
  - `46946c0` `test: inject materialize and checked read faults into the universe GameTests`（parent `64ffaf9`）：補充第 11 點。testmod 新增 `mixin/MaterializeFaultMixin.java`、`mixin/CheckedNbtFileReadFaultMixin.java`，修改 `quantumchamber-test.mixins.json`（只登記這兩個）、`M5TestHooks.java`、`M5GameTestSupport.java`、`M5UniverseGameTests.java`（三個故障注入 test）；`scripts/verification/README.md`（新 helper 的用法）。
  - 本段由其後的 docs commit 加入。
- Code range `e4e1b2f..46946c0`。沒有改 `SuperpositionSessionManager.java`（`releaseOnSealed`、`acquireCollapseTicket` 已足夠）、`SessionRecoveryState.java`、`ChamberDoorService`、`UniverseRegistryState`、`gates.ps1`、`build.gradle`、`.github/`。
- 範圍是 plan Task F4a：UNIVERSE 目的地的 RESOLVE、進入 SEALED 與 SEALED 的重試。RESOLVE 完成之後，F4b 之前停在 WAITING（PREPARE stub）；不預載、不投影、不寫 projection ledger、不攔截門（F4b），沒有通道（F5）。
- 行號以 `46946c0` 為準（production 自 `c825a49` 起沒有變）。`DR` 指 `collapse/DestinationResolver.java`，`RR` 指 `collapse/ResolveResult.java`，`ULS` 指 `universe/UniverseLifecycleService.java`，`CA` 指 `collapse/CollapseAuthorities.java`，`DRT` 指 `DestinationResolverTest`，`GT` 指 `CollapseLogMarkerGuardTest`，`MFM`、`CRM` 指兩個新的 mixin；`CS`、`CST`、`SRM`、`M5C`、`M5U`、`M4C`、`SUP`、`HK` 同前面各節。
- Review：spec review ❌（Critical 0、Important 1、Minor 6：I-1、M-1～M-6）；Q1、Q2（「需要裁示」兩條）判定符合 spec，controller 已接受。處置見本節末的「Fix round 1」（`7d6fae7`、`e0780fc`、`7f91400`）與「Fix round 2」（`abb7ddf`、`0cdc951`、`72e4d8c`、`df66619`）。
- 最終 review：
  - fix round 1：
    - spec re-review ✅（Critical 0、Important 0、Minor 3：S-m1～S-m3）。
    - 首輪的 quality review（範圍 `e4e1b2f..ff4fc55`）Approved（Critical 0、Important 0、Minor 8：m-1～m-8）。
    - controller 開窄範圍的 fix round 2，處理 m-1～m-8 與 S-m1、S-m2。
    - S-m3（controller 裁示的 F7 errata 沒寫進 note）由 controller 補在「裁示」第 5 條。
    - 所有 commit 之後的 run-all：`run-all-521b3824c2bd4c2386e935e0abbc4353`，`END ALL exit=0`（`ff4fc55`），SUMMARY 與 `7f91400` 的 `run-all-e35b21eb…` 相同。
  - fix round 2：
    - spec review ✅（Critical 0、Important 0、Minor 3：S2-mA～S2-mC）。§8.3 的兩個方向、權威退避、I-1 的不變式、`catalogRecord` 的 §8.1／§6.2 判定都判定成立。
    - quality review Approved（Critical 0、Important 0、Minor 6：Q2-m1～Q2-m6）。
    - 兩位 reviewer 都判定這些 Minor 可以等下次改到 CA／DR／CS 時處理。controller 不再開 fix round，全部寫進「交給後續」（F4b Step 0、F6）。
    - 所有 commit 之後的 run-all：`run-all-9d3a6073d1d84645930b0af986e44af1`，`END ALL exit=0`（`942a33f`），SUMMARY 與 `df66619` 的 `run-all-a0e29009…` 相同。
  - **F4a 完成**：range `e4e1b2f..942a33f`，之後接 controller 的 docs commit。
    - code：`4bb07b3`、`458c297`、`04c7fea`、`c825a49`、`64ffaf9`、`46946c0`、`7d6fae7`、`e0780fc`、`7f91400`、`abb7ddf`、`0cdc951`、`72e4d8c`、`df66619`。
    - docs：`459b4e0`、`ff4fc55`、`942a33f`。
    - 最終 run-all：`run-all-9d3a6073d1d84645930b0af986e44af1`，`END ALL exit=0`（`942a33f`）。數字如下：

      | 項目 | 數值 |
      | --- | --- |
      | default | 174/174（`_windows` 88） |
      | M5 | 13/13 |
      | JUnit | 611 |
      | release | 383／336 |
      | sources | 214 |
      | Full WARN／ERROR | 54／28 |
      | M5 WARN／ERROR | 13／1 |
- 本節「Fix round 1」之前的內容描述首輪（`46946c0`）；fix round 1 改過的地方在相關列另加註「fix round 1」，加註裡的行號以 `7f91400` 為準。

### plan Step 1 逐項對照

| plan Step 1 | 程式位置 | 測試 |
| --- | --- | --- |
| EXISTING：fixture 同時寫入一致的 allocation 與 projection ledger（spec §15）；重驗失敗 → `EXISTING_INELIGIBLE` | `DR:188` `resolveExisting`：§8.1 的條件依序是 discovery 有而且 `gameplayEligible`（`:193`、`:194`）、flushed catalog 有這筆而且是 `EAGER_ENABLED` 的 OVERWORLD（`:204`、`:205`，`catalogRecordMatches(EAGER_ONLY)`）、exact ACTIVE world（`:208`）、來源 role 是 OVERWORLD（`:209`）；任一不符 `:213` `ineligible`。只讀，不寫任何權威 | G-EXIST（`M5U:288`）：fixture 不是手寫的 ledger，而是先以 DORMANT NEW 收據 D1 經真的 RESOLVE 建立宇宙 U（allocation entry、catalog、ACTIVE world、discovery 都一致；projection ledger 沒有 entry，屬 spec §15「不會觸發 §6 遺失判定的資料」），終局 D1 之後寫入選中 EXISTING U 的 D2；`authoritiesUnchanged`（三個權威檔的 bytes）、`sameWorldInstance`。G-INELIGIBLE（`M5U:342`，discovery 沒有這個 UniverseId）。DRT `existingRevalidatesWithoutWritingAnyAuthority`（`:352`）、`existingIneligibleForEachRevalidationCondition`（`:365`，6 列：discovery 沒有、`gameplayEligible=false`、catalog 沒有、`DISABLED`、沒有 ACTIVE world、來源 NETHER） |
| NEW：ledger → catalog → 物化 → discovery，catalog 恰好加 1；RESOLVE 不受斷電影響 | `DR:114` `resolveNew`：每一步之前 `convergeIfNeeded` 該權威（`:116`、`:125`、`:142`）；`:117` ledger、`:126` catalog、`:135` lifecycle、`:143` discovery。CS 的 RESOLVE 不看閘門（`CS:386-387`，見補充第 2 點） | G-NEW（`M5U:222`）：RESOLVE 之前斷電，`resolvedWithoutPower`、`oneAllocationEntry`、`catalogEagerEnabled`、`worldActive`、`discoveredByP`、`oneResolvedInfo`；catalog 增量 1（M5 oracle）。DRT `newResolvesLedgerCatalogWorldAndDiscoveryInOrderAndIsIdempotent`（`:69`，順序、game time、第二次呼叫不再寫入） |
| 注入物化失敗 → `DISABLED`＋SEALED(`MATERIALIZE_FAILED`)、同 server instance 不重試、門可開、來源 ticket 已釋放 | `DR:173` `materializeFailed`；`ULS:150`（同一 instance 失敗過的 descriptor 直接 `FAILED_BEFORE`）；`CS:521` `sealedRetry`（restart-only、本 instance 封存的不重試，`CS:477`） | G-MATFAIL（`M5U:414`）：`catalogDisabledAndWritten`、`noWorld`、`doorClosedOnSeal`、`doorOpensAgain`、`ticketReleased`、`noRetryThisInstance`（退避期過後再由艙外進入）、`lifecycleRefusesTheFailedDescriptor`。DRT `materializeFailureDisablesTheRecordAndSealsMaterializeFailed`（`:173`） |
| §8.3 的兩個方向：`DISABLED` 落盤失敗 → roll-forward；改回 `EAGER_ENABLED` 的寫入失敗 → restore | roll-forward：`DR:176-182`（M3 的 save 保留 current 的 `DISABLED` 與 dirty；記 ERROR，照樣 `MATERIALIZE_FAILED`）。restore：`DR:152` `reenable`（`:163-164` current 改回 `DISABLED`、維持 dirty、不物化） | DRT `disablingWriteFailureRollsForwardKeepsDisabledAndLogsOneError`（`:190`，atomic move 之前失敗與之後讀回失敗兩種）、`reenableWriteFailureRestoresDisabledAndDoesNotMaterialize`（`:243`）；另有 `materializeRetryReenablesThenMaterializesAndOnlyTheRetryAcceptsDisabled`（`:223`）、`materializeRetryThatFailsAgainDisablesAgain`（`:270`）。fix round 1：前者改寫並改名為 `disabledRecordOfTheSessionIsReenabledThenMaterialized`（拿掉「沒有重試旗標時 `DISABLED` 是損壞」），另新增 `disabledRecordOfTheSessionIsRetriedRegardlessOfTheLastSealedReason`、`aSpentInstanceSealsMaterializeFailedWithoutWritingOrMaterializingAgain` 與 G-DISABLED（見「Fix round 1」的 I-1）。fix round 2（m-2）：兩個方向改經 `CA` 的 `changeCatalogAvailability`（`ROLL_FORWARD`／`RESTORE`），沿用 catalog 以權威為單位的退避與 log；語意不變，上列三個 DRT 的收斂時點改為退避期過後（見「Fix round 2」） |
| 注入 IO → `AUTHORITY_UNREADABLE`／`ALLOCATION_IO_FAILED`；`ALLOCATION_IO_FAILED` 經邊緣觸發＋退避重試成功，而且沒有第二個宇宙 | 原因直接採用權威結果帶的 SEALED 原因（`DR:220` `failed`）；重試 `CS:527` `sealed`、`:550` `retrySealed`；重試後的 RESOLVE 先收斂 ledger（`DR:116`） | G-ALLOCIO（`M5U:485`）：allocation 正式檔的讀回失敗一次 → SEALED(`ALLOCATION_IO_FAILED`)；退避期內的進入不重試（`noRetryWithinTheBackoff`）；退避期過後的進入重試成功（`retriedOnce`、`ledgerConvergedFirst`、`stillOneAllocationEntry`、`oneUniverse`）。G-UNREADABLE（`M5U:552`）：discovery 讀檔的 IO 例外 → SEALED(`AUTHORITY_UNREADABLE`)、本 instance 不重試。DRT `allocationReadbackFailureSealsAllocationIoFailedAndRetryConvergesToOneUniverse`（`:113`）、`discoveryFailuresMapToTheirAuthorityReasons`（`:298`）、`existingDiscoveryReadFailuresAreAuthorityReasons`（`:411`）、`initializationFailureSealsEveryUniverseDestinationWithTheInitializationReason`（`:319`） |
| orphan storage → `AUTHORITY_CORRUPT`；catalog dirty 時等待、不進 SEALED | orphan 由 `CA:619-622` 判定，`DR:128` 採用；catalog 的 `WAIT`：`DR:127` → `CS:427` `waiting`（不 SEALED，連續等待超過 600 ticks 記一次 WARN，`CS:436`） | DRT `orphanStorageIsCorruptAndNothingIsMaterialized`（`:157`）、`catalogWithUnconfirmedWritesWaitsWithoutSealing`（`:136`）、`existingWaitsWhileDiscoveryOrCatalogIsUnconfirmed`（`:391`）；CST `resolveWaitWarnsOnceAfterSixHundredTicks`（`:284`）。orphan 與 catalog dirty 沒有 GameTest（理由見「偏離」第 6 條）。fix round 1：補 G-WAIT（`m5_universe_resolve_wait`），以 discovery 未確認驅動 CS 層的等待分支（不封存、600 ticks 只 WARN 一次、收斂後繼續；見「Fix round 1」的 M-2） |

### controller 補充第 1–14 點

- **第 1 點（lifecycle API）**：`ULS:126` `materializeAllocated`、`:179` `resolveActive`，都只能在 server thread 呼叫（`:197`），使用 Context 既有的 backend（`context.backend`）與 runtime registry，不另外 new backend。
  - 前提不符時不呼叫 backend：沒有 ready 的 context → `NOT_READY`（`:130`）；flushed catalog 沒有這筆、current 與 flushed 不同、不是 `EAGER_ENABLED`、沒有 exact catalog role 的 OVERWORLD descriptor → `NOT_ELIGIBLE`（`:136-147`）。已經 ACTIVE → `ALREADY_ACTIVE`（同一個 world instance）。
  - 同一個 instance 內失敗過的 descriptor 直接拒絕：Context 的 `failedDescriptors`（`:470`），`:150` 回 `FAILED_BEFORE`。runtime registry 本來就不允許原地重試，這一層讓 `REJECTED` 等沒有留下 receipt 的失敗也一樣拒絕。
  - 失敗記入 Context 的 materialize failures（`:158`、`:169`）：與 bootstrap 一致，停服驗證據此核對 rollback 收據。`REJECTED` 在建立 runtime owner 之前就拒絕，不記入（記入的話停服驗證會把它當成沒有安全 close 證據的失敗）。`Error` 記入之後照舊往外丟。
  - 兩個 API 都不記 log；結果是 `ULS:207` `MaterializeOutcome`（只有成功帶 exact world，只有 `FAILED` 帶 backend status）。M3 的 log 與行為不變：`-Mode M3`（M3-B）PASS 4＋5。
- **第 2 點（RESOLVE）**：`DR:97` `resolve(view, materializeRetry)` 回 `RR`（`Resolved`／`Wait(reason)`／`Sealed(reason, failure)`，`Sealed` 不接受 TRR）。前提不符（不是 COLLAPSING＋UNIVERSE、有人未返還、有 lease、SOURCE）丟 `IllegalArgumentException`（DRT `resolveOnlyAcceptsSettledUniverseCollapses`）。CS 以 `CS:404` `resolve` 取代 F3d 的 stub。fix round 1：介面改為 `resolve(view)`，拿掉 `materializeRetry`（「偏離」第 2 條）。
  - EXISTING 依 §8.1（上表第 1 列）。discovery 或 catalog 未確認時回 `Wait`（`DR:191`、`:202`），不判 `EXISTING_INELIGIBLE`。fix round 2：兩者都確認之前不從磁碟讀 discovery（m-3）；lifecycle 沒有 ready 的 context 時也是 `Wait`（m-4）；catalog 改經 `CA` 的唯讀查詢（m-2）。
  - NEW 依 §8.2 的四步，每一步之前先 `convergeIfNeeded` 該權威。結果依 F2c 交接的分類：成功繼續；`WAIT` 等待；其餘採用權威結果帶的 SEALED 原因與 root cause（`DR:220`）。
  - catalog 的 `WAIT`：`CS:427` 不進 SEALED，下一個 tick 再試；同一次等待超過 600 ticks 記一次 WARN（`CS:436` `waitWarning`，純函式），確定或封存時重設（`Track.endWait`）。
  - discovery 的 game time 用 overworld（`DR:87`）：與提交 COLLAPSING 時的 `CommittedAtGameTime` 同一個時鐘（F3d `CS` 的提交）；discovery 是跨世界的權威，不跟著來源艙的維度走。DRT `newResolves…`（`:69`）斷言寫入的是注入的 game time（R-9）。
  - RESOLVE 不需要供電、不看閘門：`CS:386-387` 以 `CollapsePhase.derive(view, measuredRecoveryComplete, track.resolved, …)` 推導，RESOLVE 階段只看收據、`measuredRecoveryComplete` 與本 instance 的 `resolved`；UNIVERSE 的閘門 supplier 恆為假（第 7 點）。G-NEW 在 RESOLVE 之前斷電（GM-5 證明鎖住）。
  - Destination 與 ledger entry 的 UniverseId 不符 → `AUTHORITY_CORRUPT`（`DR:120-123`，DRT `newDestinationThatIsNotTheLedgerEntryIsCorrupt`）。
- **第 3 點（提交時的 Destination）**：選 (b)，維持 F3d 的推導值，由 RESOLVE 偵測不符。理由見「偏離」第 1 條。`CollapseAuthorities` 沒有新增查詢。
- **第 4 點（§8.3）**：上表第 3、4 列。`DISABLED` 的 checked 寫出失敗時記 ERROR（`DR:179`，帶 `LOG_MARKER` 與 `session=`），照樣 `MATERIALIZE_FAILED`。重啟後的重試由 CS 傳 `materializeRetry=true`：catalog 以 `MATERIALIZE_RETRY` 驗證（接受 `DISABLED`，`DR:126`），先 `reenable`（`DR:152`）改回 `EAGER_ENABLED` 再物化，仍失敗就 `materializeFailed` 再改回 `DISABLED`；`reenable` 的寫出失敗屬 restore。兩個方向的 RED 是 RED-C 的 `disablingWriteFailure…` 與 `reenableWriteFailure…`，mutation R-4、R-5、R-12。CS 何時傳 `materializeRetry`：SEALED(`MATERIALIZE_FAILED`) 的重試（`CS:561`），以及開服時就是 COLLAPSING 的 session 在本 instance 的第一次 RESOLVE（`CS:378`，見「偏離」第 4 條）。fix round 1（I-1）取代這段簿記：NEW 的 RESOLVE 一律以 `MATERIALIZE_RETRY` 驗證本 session 推導的 UniverseId，遇到 `DISABLED` 時由 resolver 的 `materializeSpent` 決定重試或等下次重啟，CS 不再傳旗標（見「Fix round 1」）。fix round 2（m-2）：改為 `DISABLED` 與改回 `EAGER_ENABLED` 的寫出都經 `CA.changeCatalogAvailability`（roll-forward／restore），resolver 不再直接寫 catalog。
- **第 5 點（進入 SEALED）**：`CS:454` `seal`，順序：
  1. journal 以 `commitRollForward`（C→S）寫入 SEALED(reason)（`CS:457`）。呼叫前 `collapsing` 已確認 collapse view 等於 current（`CS:369` 起），也就是 current＝flushed。寫出未確認（`ROLL_FORWARD_PENDING`）時依 collapse view 照樣生效，記 WARN，重試交給 SRM 的 RETRY_FLUSH（F3d A2）；CS 對 roll-forward 未確認的 session 只暫停。寫入在前置檢查就被拒（`REJECTED`，journal 沒有改變）時，下一個 tick 只重試這筆 SEALED 寫入，不重跑 RESOLVE（`Track.pendingSeal`，`CS:380-383`）。fix round 2（m-5(b)）：改為依 per-session 退避只重試這筆寫入，不是每個 tick。
  2. 門：`CS:730` `queueDoor(…, CLOSE_ON_SEALED)` 經 F3d 的待套用門與 `applyTarget`（`CS:754`）。每次嘗試都重新看門口：25 格門的範圍內有任何 entity 的 bbox（`CS:780` `doorwayOccupied`、`:785` `doorwayBox`）時改為一般開門（`open=true`、`membrane=none`），否則關閉（`CS:775` `sealedDoor`）。session 已不是 SEALED（重試回到 COLLAPSING 或已終局）時不再套用。
  3. 對參與者顯示 `message.quantumchamber.m5.sealed`（zh_tw「量子通道無法開啟，已封存。」，en_us「The quantum passage could not open and has been sealed.」）。
  4. 刷新 Controller（`CS:714`）。
  5. `releaseOnSealed` 釋放來源 ticket（`CS:482`，失敗時 WARN，不重試，停服時一併釋放）。
  6. log（`CS:490` `sealedLog`）：可重試的原因 WARN（附重試方式），永久原因 ERROR「需要管理員處理」；依 (session, 原因, `FailureText.dedupeKey`) 去重，RESOLVE 成功時清除。
- **第 6 點（SEALED 的重試）**：`CS:521` `sealedRetry` 依 `retryable()`／`restartOnly()` 分流（純函式，CST `sealedRetryFollowsTheReasonTable` 逐一列出 8 個原因）：
  - 可重試、非 restart-only（`ALLOCATION_IO_FAILED`、`PROJECTION_FAILED`）：`EDGE_WITH_BACKOFF`。
  - `MATERIALIZE_FAILED`、`AUTHORITY_UNREADABLE`：只對開服 attach 當下就在 journal 的 session、而且本 instance 還沒用掉那一次（`restartRetryConsumed`）時重試；本 instance 自己以這兩個原因封存的，`seal` 時就標為已用掉（`CS:477`）。
  - 永久原因與 TRR：`NONE`。
  - 觸發（`CS:527` `sealed`）：與 DORMANT 相同的 `EntryWatch`；開服 attach 當下就在 journal 的 session，第一次觀察到原參與者在艙內也算（§7.1、§12）。進入 SEALED 時重設 `EntryWatch`，封存時已在艙內的人不算觸發。觸發者必須沒有被其他 session 佔用、來源艙通過 `chamberGate`（已載入、結構合法、已供電、enabled）、而且在 per-session 退避期之外：可重試的原因封存時 `backoff.failed`（200→12000），開服之後第一次不受限（新 instance 的 Track 沒有退避）。
  - 重試（`CS:550` `retrySealed`）：checked SEALED→COLLAPSING（`commitRestore`，restore 類；`CollapseFields.retry()` 清掉原因）→ `acquireCollapseTicket` → INFO。之後由推導階段重跑 RESOLVE（未確認的權威由 resolver 先收斂）；restore 寫入失敗時 WARN 並退避。成功之後依推導階段繼續，F4b 之前停在 WAITING（G-ALLOCIO `collapsingAgainWithTicket`）。
- **第 7 點（PREPARE stub）**：`CS:441` `prepareStub`：UNIVERSE 在 RESOLVE 完成之後推導為 WAITING（閘門 supplier 恆為假，不消耗邊緣觸發），每個 session 在本 instance 只 WARN 一次「準備階段尚未實作（F4b）」。不預載、不投影、不寫 projection ledger。G-NEW `oneWaitingStubWarn`、`stubWarnedOnce`（恢復供電之後也不重複，GM-4）。
- **第 8 點（`CollapseAuthorities` 的 marker）**：`CA` 的 7 則 log 都帶 `TICK_LOG_MARKER`（`CA:358`、`:363`、`:744`、`:794`、`:802`、`:834`、`:845`），只改 log：它們記的是權威本身，不屬於任何 session（class Javadoc `CA:63`）。`GT` 的 `LEGACY` 清單、`record Legacy` 與 Javadoc 的說明刪除，未帶 marker 的呼叫一律是違規；README「塌縮流程的 log」一點的例外說明刪除。RED-A：`GT` 在改 `CA` 之前恰好列出這 7 處。G-ALLOCIO 的 `ledgerWarnCarriesTheTickMarker` 確認 `collapseFlowNeverWarned` 依 marker 看得到權威的 WARN。
- **第 9 點（SRM 的 RETRY_FLUSH WARN）**：`SRM:173` 改為「recovery 寫出保留 pending（返還，或 roll-forward 的重試寫出），session=…」。以舊文字斷言的兩處同步：`M4C:1921`（F3a G3）、`M5C:438`（G-RF），斷言片段改為「recovery 寫出保留 pending」。沒有 JUnit 以這段文字斷言。
- **第 10 點（測試放置與衛生）**：會配置或物化宇宙的 6 個 test 都在 `M5U`，各自一個 `m5_universe_<名稱>` batch，以 `begin`／`awaitConvergedThenComplete` 包住，只斷言增量。default run 的稽核見下一小節。G-RES（`m5_universe_resolve_stub`）刪除，改寫成 G-NEW（同一個 fixture：DORMANT NEW 收據、P 由艙外進入、RESOLVE 期間開關門），原本的 stub 斷言（停在 RESOLVE、catalog 增量 0）不再成立。
- **第 11 點（fault 注入）**：`HK:53` `MATERIALIZE_FAULT`、`HK:55` `CHECKED_READ_FAULT` 都是 `Slot`；`SUP:138` `installMaterializeFault(context, universeId, hooks)`、`SUP:149` `installCheckedReadFault(context, 檔名, skip, failures, hooks)` 經 `installHook` 安裝。
  - `MFM` 掛在 `Minecraft121DynamicDimensionBackend.materialize` 的開頭（`require=1`、cancellable）：這個 UniverseId 的 OVERWORLD 每一次經 backend 的物化都回 `FAILED_ROLLED_BACK`，不建立 world（`HK:144`，`calls()` 計數）。
  - `CRM` 掛在 `CheckedNbtFile.readFile` 的開頭（static、`require=1`）：檔名恰為指定檔名的讀取，先放行 `skip` 次、之後 `failures` 次丟 `IOException`（`HK:164`）。對正式檔注入，寫入時就是「atomic move 完成、讀回失敗」，讀取時就是讀檔的 IO 例外。
  - 三個注入 test 各自一個 batch；有界等待的總長度都小於 tickLimit 600（最長的 G-ALLOCIO：registered ≤ 43，等待到 registered＋228，再加 `awaitConvergedThenComplete` 的 20）；完成前都等 catalog 與 ledger 收斂（`awaitConvergedThenComplete`）。
- **第 12 點（G-SEAL m-4）**：選「放行第 4 次寫出時暫停」：`M5C:1145-1149` 的探針在前 3 次受控失敗之後、看到同一份「current 是 SEALED、flushed 仍是 COLLAPSING」的寫出時呼叫 `M5TestHooks.suspendCollapse(sid)`，再交給 `FlushFault` 放行；新 witness `suspendedAtTheConfirmingWrite`（`M5C:1167`）。理由：確認之後的 flushed SEALED(`PROJECTION_FAILED`) 是可重試的原因，F4a 起塌縮流程會評估它的重試。雖然這個 fixture 的參與者是離線的隨機 UUID、也沒有艙體，邊緣觸發不會成立，但暫停讓「確認的那一個 tick 起到收尾之前都不推進」不必依賴這個推論（決策 7）。
- **第 13 點（新增的 log）**：collapse package 新增的 log 都帶 `LOG_MARKER`：`CS` 的 RESOLVE 完成 INFO、等待 WARN、stub WARN、SEALED 的 WARN／ERROR、寫入失敗 WARN（經 `warn()`）、重試 INFO、待套用門的 log；`DR` 的 §8.3 ERROR。能歸屬到 session 的 WARN／ERROR 都帶 `session=<UUID>`。`GT` 在每一輪 JUnit 都通過（GREEN-C、GREEN-D）。
- **第 14 點（plan Step 1 的 RED 清單）**：見上方「plan Step 1 逐項對照」。`gameplayEligible=false` 在 DRT 的 ineligible 表（`:365`），GameTest 沒有這一列：M5 不會寫出 `gameplayEligible=false` 的 discovery，要在 GameTest 造出它只能手寫 discovery 檔。orphan 與 catalog dirty 只有 JUnit（「偏離」第 6 條）。

### default run 的稽核（補充第 10 點）

F4a 之後，沒有掛 hook、目的地是 UNIVERSE 的 COLLAPSING 走到 RESOLVE，就會在 default run 配置宇宙。逐一稽核 default run 中會產生 MEASURED、COLLAPSING 或 DORMANT 續行，或以 NEW／EXISTING 收據為 fixture 的 test：

| Test（batch） | 會不會走到 RESOLVE |
| --- | --- |
| G-SRC `m5_collapse_source_live`、G-RF `m5_collapse_return_roll_forward`、G-OFF `m5_collapse_offline_rejoin`、G-FB `m5_collapse_commit_fallback`、G-RFB `m5_collapse_release_fault_backoff` | 不會：原生選 SOURCE 側門（`sourceDoor`），`Destination=SOURCE`，推導階段沒有 RESOLVE |
| G-DOR `m5_collapse_dormant_edge`、G-TERM `m5_collapse_terminal_fault` | 不會：DORMANT 收據選 SOURCE（`receipt(…, false)`，`M5C:630`、`:694`、`:1196`） |
| G-ELSE `m5_collapse_returned_elsewhere` | 不會：兩個 fixture（COLLAPSING 選 SOURCE、SEALED 選 NEW）都以 `collapseFixture(…, suspend=true)` 暫停（`M5C:571`、`:574`）；另一個原生 session 只到 RETURNING |
| G-UNSUP `m5_collapse_unsupported_cancel`、G-NETHER `m5_collapse_nether_door`、G-NLIVE `m5_collapse_nether_live_cancel` | 不會：Nether／End 的 NEW／EXISTING 收據依 §11.3 不提交，保留式 recovery 到 DORMANT 之後取消；G-NETHER 的 Nether 側門被拒，M4 SOURCE 收據照常完成 |
| G-PASS `m5_collapse_startup_passage` | 不會：PASSAGE_READY fixture（NEW，未暫停）標為開服時就有，CS 直接 `PASSAGE_ENDED` 終局；PASSAGE_READY 沒有 RESOLVE |
| G-SEAL `m5_collapse_sealed_roll_forward` | 不會：NEW 的 COLLAPSING fixture 未暫停，但同一個 callback 內就以 roll-forward 寫入 SEALED（未確認），CS 看到的是 roll-forward 未確認的 collapse view，只暫停；放行確認的第 4 次寫出時 `suspendCollapse`（補充第 12 點） |
| 其他 `m5_collapse_*`（膜方塊、門的套用、流體、未載入、hook slot） | 不會：沒有 session |
| M4C 原生選門（`m4_select_*`、`m4_ifix1_checkpoint_backoff`、`m5_h2_checkpoint_scope`、`m5_h3_*` 中產生 MEASURED 的） | 不會：經 `nativeCandidates`（`M4C:1063`），交出 fixture 之前 `suspendCollapse`（`:1067`）；M4 原斷言「MEASURED 一直等待」不變 |
| M4C `m4_dormant_other_chamber` | 不會：`dormantReceipt` 選 SOURCE，而且 `suspendCollapse`（`M4C:543-545`） |
| M4C `m5_f3a_*`（F3a 的三個 test） | 不會：`m5_f3a_presence_reconcile`、`m5_f3a_attach_lookup` 經 `f3aFixture`（`M4C:1956`），一律暫停（COLLAPSING 選 SOURCE，其他選 NEW）；`m5_f3a_native_collapsing_return` 經 `nativeCandidates`（`M4C:1849`），交出 fixture 之前 `suspendCollapse`（`M4C:1067`）。fix round 1 更正（M-5）：首輪寫成三個都經 `f3aFixture` |
| M2 的走廊 test | 不會：只檢查 358 扇側門可選（`M2CorridorGameTests:491`），不選門 |
| m4Recovery run（`m4-recovery-probe.ps1`） | 不會：`-Dquantumchamber.test.collapseSuspended=all`（`build.gradle:103`） |

實證：run-all 的 Full 中 default run 的 boundary oracle（`M4GameTestBoundaryProbe`：suite 前後的 catalog 快照必須相同，只容許具名的 M3 fixture）為 PASS；Full 的 latest.log 沒有任何「目的地已確定」「準備階段尚未實作」「塌縮進入 SEALED」「M5 ALLOCATION_LEDGER」的行（見「Gate 與證據」）。

### 測試

- JUnit：572 → 595（2 skipped 不變）。`DRT` 18 個（新增，C）；`CST` 7 → 12（D）：`sealedMessageExistsInBothLanguages`（`:253`，key、zh_tw 逐字、兩個語言檔 key 集合相同）、`sealedRetryFollowsTheReasonTable`（`:266`，8 個原因 × 是否開服 × 是否已用掉）、`resolveWaitWarnsOnceAfterSixHundredTicks`（`:284`）、`sealedDoorClosesUnlessTheDoorwayIsOccupied`（`:296`，四個朝向的 25 格與門內外的 cell）、`sealedPendingDoorUsesItsOwnWording`（`:317`）。`GT` 仍是 2 個（只刪除 `LEGACY`）。
- default GameTest 174 不變（`_windows` 88）：default run 沒有新增或刪除 `@GameTest`。G-SEAL 多一個 witness；G-RF、F3a G3 只改斷言的文字片段。
- M5 GameTest：5 → 10。刪除 G-RES（−1）；D 新增 G-NEW、G-EXIST、G-INELIGIBLE（＋3）；E 新增 G-MATFAIL、G-ALLOCIO、G-UNREADABLE（＋3）。catalog 增量：G-NEW、G-EXIST（只有 D1）、G-MATFAIL（`DISABLED`）、G-ALLOCIO、G-UNREADABLE 各 ＋1，G-INELIGIBLE 0；連同 sanity 與三個 harness test，suite 結束時 9 筆。
- legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 都不變。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4a/`：`f4a/state.ps1`（比照 `f4a0/state.ps1`，記 HEAD、porcelain 與逐檔 blob）、`f4a/gate.ps1`（以 `onlyBatches` 跑 `gates.ps1`）、`f4a/junit.ps1`（Gradle `test --tests`）。暫時性改動由 `f4a/adapter.py` 套用與還原（套用前保存原檔，還原後以 `git hash-object` 核對），JUnit 與 GameTest 的 mutation 各由 `f4a/mut-junit.ps1`、`f4a/mut-gate.ps1` 執行，各 adapter 的 diff 在 `f4a/adapters/<名稱>/`。gate root 在 `.superpowers/verification/`。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED-A（第 8 點） | `e4e1b2f`＋只改 `GT`（`red-a-guard-junit-state.txt`） | Gradle `test --tests …CollapseLogMarkerGuardTest` | 2 個中 1 個 FAIL：`everyCollapseLogCallCarriesTheCollapseMarker` 恰好列出 `CA` 的 7 處 |
| GREEN-A | `e4e1b2f`＋A 的 6 個檔（`green-a-junit-state.txt`） | JUnit `GT`＋`CollapseAuthoritiesTest`；focused `gate-green-a-focused-fac464d550d14279967b60c0ba29a896`（G-RF、G-SEAL、F3a G3） | JUnit 2＋33 PASS；focused PASS，latest.log 中新文字的 WARN 2 則（G-RF、F3a G3 的受控失敗） |
| GREEN-B（第 1 點） | `4bb07b3`＋`ULS`（`green-b-universe-junit-state.txt`） | JUnit `dev.quantumchamber.universe.*`；`gate-f4a-green-b-m3-95b0e39c86314f2783efb606720e2abf`（`-Mode M3`） | JUnit PASS；M3 PASS（universe 4、transfer 5） |
| RED-C（第 2–4 點） | `458c297`＋DRT＋RED adapter（`f4a/red-c-adapter/DestinationResolver.java`：`resolve` 一律回 `Resolved`；`red-c3-junit-state.txt`） | Gradle `test --tests …DestinationResolverTest` | 18 個中 17 個 FAIL，全部是 `AssertionFailedError`（`red-c3-failure-types.txt`）；通過的是 `existingRevalidatesWithoutWritingAnyAuthority`（期望本來就是 `Resolved`，由 R-8 鎖住）。前兩輪 `red-c`、`red-c2` 有 fixture 層的例外（Optional 取值、`subList`、檔案不存在），修正 test 的 helper 之後才採計第三輪，兩輪保留不採計 |
| GREEN-C | `458c297`＋C 的 3 個檔 | 同上＋`GT` | 18／18、2／2 |
| R-1～R-12（JUnit mutation） | GREEN-C 的工作樹，各自套用 adapter | `mutations-junit-summary.txt` | 12 個都被抓到：R-1（不先收斂 ledger）→ allocation 讀回失敗那一個；R-2（不比對 Destination）→ `newDestinationThatIsNotTheLedgerEntryIsCorrupt`；R-3（catalog 的 WAIT 當 SEALED）→ catalog 等待；R-4（物化失敗不改 DISABLED）→ 3 個；R-5（reenable 失敗不 restore）→ `reenableWriteFailure…`；R-6（EXISTING 的 WAIT 判 INELIGIBLE）→ `existingWaits…`；R-7（不看 `gameplayEligible`）、R-10（不看 ACTIVE world）、R-11（不看來源 role）→ ineligible 表；R-8（EXISTING 也物化）→ `existingRevalidates…`＋ineligible 表；R-9（discovery game time 不用時鐘）→ `newResolves…`；R-12（DISABLED 寫出失敗不記 ERROR）→ `disablingWriteFailure…`。還原後 blob 與 GREEN-C 相同（`mut-c-base-state.txt`） |
| RED-D（JUnit） | `04c7fea`＋D 的 test＋adapter `f4a/red-d-adapter/CollapseService.java`（`04c7fea` 的 CS，只補 test 需要的 API stub；`red-d-junit-state.txt`） | Gradle `test --tests …CollapseServiceTest` | 12 個中 5 個（新增的 5 個）FAIL，全部是 `AssertionFailedError` |
| RED-D（GameTest） | `04c7fea`＋D 的 testmod 與 CST，CS 仍是 `04c7fea`（RESOLVE stub；`red-d-m5-state.txt`） | `gate-red-d-m5-5c2ca0a1b5bc4992bba0d27cd4ff6a53` | 3 個都 FAIL。G-NEW 在目標 witness（allocation、catalog、world、discovery、INFO、stub WARN）；G-INELIGIBLE 在 `sealedIneligible`、`sealedMessage`、`oneAdminError`、`ticketReleased`、`permanentNeverRetried`（`occupiedDoorwayStaysOpen`、`presenceCollapse`、`controllerRefreshedArmed` 在 stub 下也成立，由 GM-1 與「觀察」第 1 條處理）；G-EXIST 在 D1 的有界等待（stub 下 D1 不會建立宇宙），所以另以 RED-EXIST 的 adapter 證明 |
| GREEN-D | `04c7fea`＋D 的 7 個檔（`green-d-junit-state.txt`、`green-d-m5-state.txt`、`green-d-focused-state.txt`） | JUnit CST＋GT＋DRT；`gate-green-d-m5-37b6851ba0ce4d7d901693d49235aa8f`；default focused `gate-green-d-focused-058d45fa1bc44df680cc00415298b7f6`（全部 18 個 `m5_collapse_*` 與 3 個 `m5_f3a_*`） | JUnit 12＋2＋18 PASS；M5 3／3、M5 oracle PASS、`M3_UNIVERSE_STOPPED_VERIFIED worlds=2 runtimeReceipts=2`；focused 21／21 |
| RED-EXIST、GM-1～GM-7（GameTest mutation，第一輪） | `c825a49`，各自套用 adapter（`mut-chain-d.log`） | 8 個 root（`mutations-gate-summary.txt`） | 8 個都在目標 witness FAIL：RED-EXIST（EXISTING 一律 INELIGIBLE）→ G-EXIST `existingCommitted`、`oneResolvedInfo`、`noSealedWarnOrError`；GM-4（stub 不去重）→ G-NEW `oneWaitingStubWarn`、`stubWarnedOnce`；GM-5（RESOLVE 要求供電）→ G-NEW 斷電期間沒有配置。G-INELIGIBLE 的 5 個（GM-1、2、3、6、7）另外都多了 `authoritiesUnchanged=false`：見 D-FRESH |
| D-FRESH（`64ffaf9` 的 RED／GREEN） | RED：`c825a49`，工作樹乾淨（`red-ineligible-fresh-state.txt`）；GREEN：`c825a49`＋G-INELIGIBLE 的修正 | RED `gate-red-ineligible-fresh-c128436e4f7645a2b3142cd2e1506c91`；GREEN `gate-green-ineligible-fresh-5ff71fde469649029e5ef52c4d6ee018` | G-INELIGIBLE 單獨在 fresh 世界執行時只有 `authoritiesUnchanged=false`：建立收據的 candidate context 先以 M4 的 `loadOrCreate` 建立空的 discovery 檔，而基準是在那之前取的。GREEN-D 一起跑三個 batch 時，discovery 檔已由 G-NEW 建立，所以沒有發現。修正：基準改在收據寫入之後才取；GREEN 單獨 PASS |
| GM-1、2、3、6、7（第二輪） | `64ffaf9`，各自套用 adapter（`mut-chain-d2.log`；第一輪的 adapter 與結果移到 `f4a/adapters-round1/`） | 5 個 root | 只剩目標 witness FAIL：GM-1（門口有人也關門）→ `occupiedDoorwayStaysOpen`；GM-2（不釋放 ticket）→ `ticketReleased`；GM-3（永久原因也重試）→ `permanentNeverRetried`；GM-6（不顯示訊息）→ `sealedMessage`；GM-7（永久原因記 WARN）→ `oneAdminError`、`errorStillOnce` |
| GREEN-E | `64ffaf9`＋E 的 7 個檔（`green-e-m5-state.txt`、`green-e-m5-all-state.txt`、`green-e-focused-state.txt`，blob 相同） | `gate-green-e-m5-1810c5473f984071bdd3ced7114b3009`（E 的 3 個）；`gate-green-e-m5-all-d8a03f4f63fb46f3b1da5954b801d9a6`（整個 M5 run）；default focused `gate-green-e-focused-76d33e8d58084d2ab0260f269a9c7c1c`（hook slots、G-SEAL、G-DOR、`m4_select_first_wins`、F3a presence） | 3／3；M5 10／10，catalog 增量逐 test 串接 0 → 9，`M5_UNIVERSE_SUITE_END records=9`，`M3_UNIVERSE_STOPPED_VERIFIED worlds=4 runtimeReceipts=4`；focused 5／5 |
| RED-E 與 mutation（GameTest） | `64ffaf9`＋E 的工作樹，各自套用 adapter（`mut-chain-e.log`） | 6 個 root | 全部在目標 witness FAIL：RED-SEALED（SEALED 一律不重試）→ G-ALLOCIO 的有界等待逾時，診斷列出重試之前的 witness 全部成立；GR-4（同 R-4）→ G-MATFAIL `catalogDisabledAndWritten`；GM-9（restart-only 也在本 instance 重試）→ G-MATFAIL 與 G-UNREADABLE 的 `noRetryThisInstance`；GM-8（lifecycle 不拒絕失敗過的 descriptor）→ G-MATFAIL `lifecycleRefusesTheFailedDescriptor`（outcome 變成 `FAILED` 且 backend 被呼叫第二次）；GM-10（封存時不記退避）→ G-ALLOCIO `noRetryWithinTheBackoff`、`retriedOnce`；GR-1（同 R-1）→ G-ALLOCIO 的有界等待逾時 |
| run-all | `46946c0`，工作樹乾淨（`run-all-state.txt`；`STATUS` 是空的；`f4a/run-all-console.log`） | `run-all-efb99d1e845549699febf6356355625f`（Full：`gate-full-598c608154f94c409db7a8ccfcff58e2`；M5：`gate-m5-gametest-a512afb9b7f64434b613629a43a31f7c`；M3：`gate-m3-67d85e0be50b4a618c584275f67c9ee9`） | `END ALL exit=0 HEAD=46946c087af69bdda3497c409824531f3beb0508`，約 15 分鐘（22:12:12→22:27:32）。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=10/10 perTest=51+1 junit=595 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=382entries/335classes sources=214 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。default 與 legacy 的 boundary oracle（`m4-boundary-*.json`）都是 PASS |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對；`mut-chain-*.log` 末尾的 porcelain）。每個 commit 的 blob 都與它之前最後一輪 GREEN 的 state 相同（`commit-*-blobs.txt`）。
- 各 commit 的 focused：A 見 GREEN-A；B 見 GREEN-B；C 見 GREEN-C（C 只新增 class，CS 尚未呼叫它）；D 見 GREEN-D；`64ffaf9` 見 D-FRESH；E 見 GREEN-E。
- 與 F4a-0 最終 run-all（`run-all-32c099d096b049e98e6e8e21d773ff1f`）的差異只有本 task 新增的部分：
  - JUnit 572 → 595（＋23：DRT 18、CST 5）；M5 5 → 10（見「測試」）；default、legacy、perTest、windowsCheckpoint、M3、M4 recovery 不變。
  - release JAR 367 → 382 entries（320 → 335 classes），新增的 15 個都是 main 的 class（`f4a/jar-diff.txt`）：`DestinationResolver` 與它的 `$1`（switch map）、`$Lifecycle`、`$Materialization`、`$Materialization$Kind`、`$ServerCatalog`、`$ServerLifecycle`；`ResolveResult` 與 `$Resolved`、`$Wait`、`$Sealed`；`UniverseLifecycleService$MaterializeOutcome`、`$MaterializeOutcome$Status`；`CollapseService$SealedRetry`、`$DoorGoal`。sources 212 → 214（`DestinationResolver.java`、`ResolveResult.java`）。兩個新 mixin 與 hook 類別都在 testmod，testmod 命中仍是 0。
- log（以 `/WARN]`、`/ERROR]` 計行）：
  - Full：WARN 54 → 54、ERROR 28 → 28。把 UUID 與數字正規化、排序之後比對（`f4a/wbdiff.txt`），唯一的差異是 SRM RETRY_FLUSH 的 4 則 WARN 文字由「返還保留 pending」換成「recovery 寫出保留 pending（返還，或 roll-forward 的重試寫出）」（補充第 9 點；G-RF、G-RFB、G-SEAL 的受控失敗與一則 managed entity pin 上限），一對一替換。Full 的 latest.log 沒有任何「目的地已確定」「準備階段尚未實作」「塌縮進入 SEALED」「SEALED 重試」「M5 ALLOCATION_LEDGER」「M5 CATALOG」的行：default run 沒有任何 session 走到 RESOLVE。
  - M5 run：WARN 1 → 6、ERROR 0 → 1。少了 G-RES 的「目的地解析尚未實作」1 則；多了 G-NEW 與 G-EXIST（D2）的「準備階段尚未實作」各 1 則、G-ALLOCIO 的 allocation ledger 寫入未確認 1 則（`TICK_LOG_MARKER`）與 SEALED(`ALLOCATION_IO_FAILED`) 1 則、G-MATFAIL 的 SEALED(`MATERIALIZE_FAILED`) 1 則、G-UNREADABLE 的 SEALED(`AUTHORITY_UNREADABLE`) 1 則；ERROR 是 G-INELIGIBLE 的 SEALED(`EXISTING_INELIGIBLE`)「需要管理員處理」。都是 test 刻意造成、而且由 witness 斷言的。
  - legacy：0／0 不變。
- 本 task 沒有遇到派工列出的已知偶發（entity storage 未載入、Windows journal `AccessDeniedException`、H2 多一則原生 log），也沒有遇到停服卡住；所有失敗的 root 都是刻意的 RED 或 mutation。
- 結束後 Java 程序只剩閒置的 Gradle daemon（PID 129228）。

### 偏離 plan／spec 字面之處

1. **提交時的 Destination 選 (b)（補充第 3 點）**：維持 F3d 的推導值，由 RESOLVE 比對 ledger entry。理由：
   - spec §7.2 的原文是「NEW → ledger entry 的 UniverseId；還沒有 entry 時，用 §6.1 的推導值（此時不寫 ledger）」。§6.1 規定合法的 entry 一定等於推導值；兩者不符本身就是 `AUTHORITY_CORRUPT`。（fix round 1 更正（M-5）：首輪把 §7.2 的字面寫成「NEW 的 `deriveUniverseId(token)`」，不是原文。）
   - 提交 M→C 的時候，ledger 不可能已有這個 token 的合法 entry：ledger 只在 COLLAPSING 已 checked 落盤之後才寫（`CA` `lookupOrAppendAllocation` 的呼叫前提）。所以 (b) 走的就是原文「還沒有 entry」的分支；提交時讀 ledger 得到的，若合法就等於推導值，若不合法就是損壞。
   - 若採 (a) 而 entry 不符，要嘛把損壞的 id 寫進不可變的 Destination（錯誤擴散），要嘛在提交時就拒絕（M4 的收據會停在 MEASURED，沒有 spec 規定的 SEALED 出口）。(b) 讓不符走 §8.4「RESOLVE 的失敗進 SEALED、保留同一個 Destination」，與其他權威損壞的處理一致。
   - spec review 判定符合 spec，controller 已接受（Q1，見「裁示」）。
2. **`resolve` 的介面**：plan 寫 `resolve(MinecraftServer server, SessionRecoveryRecord record)`；首輪實作是 `DestinationResolver.forServer(server).resolve(view, materializeRetry)`，server 在建立時綁定（JUnit 經 package-private 建構子注入 port，plan 決策 9 的作法），`materializeRetry` 由 CS 依 Track 傳入。controller 已接受這個介面（M-6，見「裁示」）。fix round 1（I-1）之後的最終簽名是 `DestinationResolver.forServer(server).resolve(view)`：「接受 `DISABLED`」不再由呼叫端依最近一次封存的原因傳入；§8.3「每個 instance 一次」的物化額度改由 resolver 自己的 `materializeSpent`（`DR:83`）把關。resolver 每個 server instance 一個（CS 在 `SERVER_STARTED` 建立、`SERVER_STOPPED` 移除，resolver 在 `CS:387` 第一次使用時建立），所以它的狀態跨重啟自然重置。
   - fix round 2 更正（quality m-1）：上一句的「每個 server instance 一個」只靠 CS 自己的快取（`CS:387-390`），`forServer` 每次都建新的 instance，之後的 task 再呼叫一次就拿到空的額度。現在由 `DestinationResolver.forServer` 以 server 為 key 保存（`SERVER_STOPPED` 時移除），任何呼叫端、任何時候都拿到同一個 resolver 與同一份額度，CS 不再快取（見「Fix round 2」的 m-1）。
3. **commit 拆分與訊息**：plan Step 4 是一個 commit `feat: resolve collapse destinations before preparing the passage`；依派工拆成 6 個 code commit 與 1 個 docs commit（訊息見上）。plan 的 Files 列了 `SuperpositionSessionManager.java`，F3d 已提供 `releaseOnSealed`／`acquireCollapseTicket`，沒有修改。
4. **開服時就是 COLLAPSING 的 session，第一次 RESOLVE 視同 §8.3 的重試**（`CS:378`）：§8.3 先寫 catalog 的 `DISABLED`、再寫 SEALED；兩者之間 crash 時，重啟後 session 仍是 COLLAPSING、catalog 卻已是 `DISABLED`。spec §6「重啟後為什麼合法」的表把這一列寫成「等同 §8.3 已完成」，§12 的 COLLAPSING 列要求重啟後「依推導階段續做、idempotent」；若照一般規則以 `EAGER_ONLY` 驗證，卻會把它判為既有紀錄不符的 `AUTHORITY_CORRUPT`（永久）。所以這種 session 在本 instance 的第一次 RESOLVE 以 `MATERIALIZE_RETRY` 驗證（catalog 是 `EAGER_ENABLED` 時沒有差別），等同「§8.3 已完成、重啟之後重試一次」。spec 沒有逐字寫出這條分流；這個窗口的 cross-JVM 證明屬 F6（W9 的變形），交 spec reviewer 判斷。
   - spec review 判定這個讀法符合 spec，controller 已接受（Q2，見「裁示」）；但「只有第一次 RESOLVE 接受」會在第一次 RESOLVE 以別的原因封存之後失效（I-1 的第 3 條路徑）。fix round 1（I-1）以更一般的規則取代本條：CS 不再記開服時的 COLLAPSING（`startupCollapsing` 刪除），NEW 的 RESOLVE 一律把本 session 推導的 UniverseId 的 `DISABLED` 視為 §8.3 已完成，與開服狀態或最近一次封存的原因無關；重試或等下次重啟由 `materializeSpent` 決定（見「Fix round 1」）。
5. **「目的地已確定」只記在本 instance**（`Track.resolved`）：重啟之後 RESOLVE 以 idempotent 的方式重新確認（§7.3、§12）。F3d 的 `CollapsePhase.derive` 已是這個介面。
6. **orphan storage 與 catalog dirty 沒有 GameTest**：plan Step 1 最後一列由 DRT 涵蓋（真的 `CollapseAuthorities` 與 catalog，以 port 注入 IO 與 storage 目錄）。在 M5 run 造 orphan 需要在 `dimensions/` 下手動建立目錄；catalog dirty 需要讓 catalog 的寫出持續失敗，會讓之後所有 NEW 配置都 WAIT（F3c 的衛生規則）。兩者在 GameTest 層沒有額外的整合風險：WAIT 的處理只在 CS（CST 與 GREEN 的 G-NEW 已涵蓋呼叫路徑）。
   - fix round 1 更正（M-2）：「G-NEW 已涵蓋呼叫路徑」不成立。G-NEW 的 RESOLVE 一次就確定，不經過 CS 的等待分支（`CS:397` → `:414` `waiting`）；CST 只測純函式 `waitWarning`，把等待改成封存的 mutation 抓不到。改由 G-WAIT（`M5U:665`）在 M5 run 驅動這個分支：EXISTING 的 discovery 未確認時等待約 1400 ticks，不封存、600 ticks 只 WARN 一次、收斂後確定目的地（MW-1、MW-2 抓得到）。CS 的等待分支不分 catalog 或 discovery（`ResolveResult.Wait` 只帶原因文字），catalog dirty 時的等待在 resolver 層仍由 DRT `catalogWithUnconfirmedWritesWaitsWithoutSealing` 鎖住。orphan storage 仍只有 JUnit。
7. **物化失敗的注入點**：`MFM` 在 backend 的開頭直接回 `FAILED_ROLLED_BACK`，沒有走 backend 原生的 rollback（`created.close()`）。原生 rollback 是 M3 的範圍，GameTest 無法在不改 M3 的情況下讓它安全地失敗；這裡要驗證的是 F4a 的處理（`DISABLED`、SEALED、同 instance 拒絕、停服驗證）。注入的失敗照樣記入 Context 的 materialize failures，`M3_UNIVERSE_STOPPED_VERIFIED` 照常通過。
   - fix round 1 更正（M-1）：「照常通過」不能證明失敗有記入。`MFM` 在 backend 開頭就回傳，沒有建立 runtime owner，拿掉 `ULS` 的兩處記錄之後停服驗證照樣通過。改由 `7d6fae7` 的 JUnit `UniverseLifecycleMaterializeFailureTest` 經真的 `verifyStopped` 鎖住（見「Fix round 1」的 M-1）。
8. **進入 SEALED 的寫入被前置檢查拒絕時（`REJECTED`）只重試這筆寫入**（`Track.pendingSeal`）：spec 沒有規定；RESOLVE 已有副作用（例如 §8.3 的 `DISABLED`），重跑 RESOLVE 會走 `EAGER_ONLY` 而判為 `AUTHORITY_CORRUPT`。
   - fix round 1 改寫理由：I-1 之後重跑 RESOLVE 不會再判為 `AUTHORITY_CORRUPT`；物化失敗過的 UniverseId 已在 `materializeSpent`，重跑只會得到不寫、不物化的 `MATERIALIZE_FAILED`。仍只重試這筆寫入，理由改為：保留第一次 RESOLVE 判定的原因與 root cause（重跑時權威可能已收斂或改變，原因會不同），也不重複權威的寫入。
   - fix round 2 更正（quality m-5(b)）：「也不重複權威的寫入」不成立（RESOLVE 的每一步都是 idempotent，重跑不會重複寫入），而且首輪起被拒之後每個 tick 重試、沒有退避。現在保留這筆寫入的依據是 spec §8.4「RESOLVE 與 PREPARE 的任何失敗都進 SEALED」：重跑 RESOLVE 可能因權威已收斂而得到別的結果，等於丟掉已判定的失敗；重試改依 per-session 退避，依據是 §6「journal 寫入失敗時的 session 行為」（restore 類：record 留在上一份已確認的狀態，套用 §11.2 的記憶體內退避）與 §11.2 最後一點。見「Fix round 2」的 m-5(b)。
9. **G-EXIST 用同一座艙**：spec §15 的「另一座艙的 session 也能到達同一個宇宙（基本重逢）」需要通道（F5）；RESOLVE 本身不看是哪一座艙。
10. **runtime 的 `REJECTED` 不記入 materialize failures**（fix round 1 補列，M-6）：spec §6.3 寫「失敗時要記入 lifecycle `Context` 的 materialize failures」，沒有區分種類。`REJECTED` 是 backend 的 guard 在 `UniverseRuntimeRegistry.beginMaterialize` 之前就拒絕（`Minecraft121DynamicDimensionBackend:53`、`:73`；`UniverseMaterializationService:22` 的前提不符也是；`beginMaterialize` 在 backend `:79`），沒有建立 runtime owner，也就沒有需要 rollback 收據的 owner；記入的話，停服驗證會把它當成沒有安全 close 證據的失敗，讓正常停服 `STOPPED_UNVERIFIED`。它仍記入 `failedDescriptors`，同一個 instance 內直接 `FAILED_BEFORE`（`ULS:164`、`:180-184`）。JUnit `rejectedIsRefusedAgainButNotRecordedBecauseItHasNoOwner`（MU-3 抓得到）。controller 已接受（見「裁示」）。

### 裁示

fix round 1 由「需要裁示」改為本小節；以下都是 controller 在 F4a spec review 之後的裁示。

1. 「偏離」第 1 條（提交時的 Destination 選 (b)）：spec review 判定符合 spec，controller 接受（Q1）。
2. 「偏離」第 4 條（開服時就是 COLLAPSING 的 session，第一次 RESOLVE 接受 `DISABLED`）：spec review 判定這個讀法符合 spec，controller 接受（Q2）；實作在 fix round 1 由 I-1 的一般規則取代。
3. resolve 的介面改為 `forServer(server).resolve(…)`（「偏離」第 2 條）：controller 接受（M-6）。fix round 1 之後的最終簽名是 `DestinationResolver.forServer(server).resolve(view)`。
4. runtime 的 `REJECTED` 不記入 materialize failures（「偏離」第 10 條）：controller 接受（M-6）。
5. （controller 補，fix round 1 的 spec re-review S-m3）Q1、Q2 與 I-1 的 spec errata 交給 F7，內容見「交給後續」的 F7。
6. （controller 補，fix round 2）第一次 run-all（`run-all-a0e29009…`）的 Full WARN 是 55，多出的一則是 `m2_task4_disconnect_io` 既有的偶發 WARN「艙體 … 的 session 協調失敗」，歸屬見 Fix round 2。controller 裁示：
   - 列入已知偶發。它不經過 M5 程式，也不會讓任何 gate 失敗。
   - 之後比對 Full WARN 數字時，先排除這一則。

### 觀察（未修改）

1. **G-INELIGIBLE 的 `controllerRefreshedArmed` 分辨不出有沒有刷新**：進入 SEALED 前後 presence 都是 `COLLAPSE`，Controller 在提交 COLLAPSING 時就已是 ARMED，所以拿掉刷新也成立。CS 的刷新與 F3d 終局之後的刷新共用 `CS:714`。要分辨，需要先讓 Controller 的快取與 presence 不一致（例如直接改 block entity），屬 harness 的工作。
2. **開服時就是 SEALED 的 session，門不重新套用**：SEALED 的門在世界的方塊裡持久化。`seal` 的 journal 寫入與門的套用之間 crash 時，重啟後門可能維持開著：不會困住人，但與 §11.2「門為關閉」不一致，直到有人關門。
3. **WAIT 的 WARN 以 CS 的 tick 計算**：`waitSince` 是本 instance 的 server tick，重啟之後重新計算；等待期間 session 進入其他階段（例如 roll-forward 未確認）不會重設。
4. G-ALLOCIO 的重試要等兩個 200 ticks 的退避：per-session 的 SEALED 退避與 allocation ledger 的收斂退避同時開始，所以第一次重試時 ledger 已可以收斂。若之後兩者的起點不同（例如收斂失敗加倍），重試可能先遇到 `UNCONFIRMED`，再回到 SEALED(`ALLOCATION_IO_FAILED`) 並加倍退避；行為正確，只是重試次數變多。

### 交給後續

- F4b：
  - UNIVERSE 的 PREPARE：`CS:386-387` 的閘門 supplier 目前恆為假，`CS:441` 的 stub 要換成預載、投影、量子膜與 PASSAGE_READY；`CollapsePhase` 的 WAITING／PREPARE 與 §7.3 的閘門評估沿用 SOURCE 的寫法。
  - PREPARE 的失敗（`PROJECTION_FAILED`、`PROJECTION_CONFLICT`）經 `CS:454` `seal` 進入 SEALED；重試已由 `CS:527` 處理，PREPARE 只要回報原因。projection ledger 的未確認在重試時由 resolver 之後的 PREPARE 收斂。
  - 門的攔截（PREPARE、PASSAGE_READY 期間）：SEALED 的門走 `CLOSE_ON_SEALED` 的待套用門；F4b 新增的攔截不要與它衝突（同一座艙的待套用門以 chamber 為 key，後排入的取代先排入的）。
  - （fix round 1，M-4）§7.3「門狀態的重新套用」（重啟或 chunk 載入之後，依 collapse view 排入下一個 tick 重新套用）由 F4b 實作時，要涵蓋 SEALED 的關門（門口有人時改為一般開門）。目前開服時就是 SEALED 的 session，門不會重新套用（「觀察」第 2 條）。
  - （controller 補，fix round 2 的 quality Q2-m6）G-NEW 的 `onlyTheStubWarnAndNoError`（`M5U:274`）沒有依 sid 篩選 ERROR（Fix round 2「觀察」第 1 條）。PREPARE 實作之後，stub 的 WARN 會消失，F4b 本來就要改寫這個 witness。改寫時用 `noWarnOrError(capture, sid)`＋`collapseFlowNeverWarned(capture, sid)`：後者涵蓋帶 `TICK_LOG_MARKER` 的 log，不會失去 tick 層級 ERROR 的涵蓋。
  - **Step 0（controller 補）：F4a 最終 review 留下的 Minor。** 兩位 reviewer 都判定可以等下次改到 CA／DR／CS 時一併處理。F4b 在開始 PREPARE 之前，先以獨立 commit 處理下列各項，每項都要有 RED 或 mutation 證據。F4b 的派工會把 `DestinationResolver.java` 與 `CollapseAuthorities.java` 中下列各點列入允許路徑。行號以 `942a33f` 為準。
    - Q2-m1：`CA:692-724` 的 `WriteDirection` 可以和 availability 任意組合。依 spec §6 收斂規則表，方向由目標值決定：DISABLED 是 roll-forward，改回 EAGER_ENABLED 是 restore。作法二擇一：由 availability 推導方向、拿掉這個參數；或在組合不符時丟 `IllegalArgumentException`。兩種都要一併調整 `CAT:1010`。
    - Q2-m2：`DR:208-216` 的 `materializeFailed` 把 CORRUPT 與初始化失敗也當成「寫出未確認」。比照 `reenable`（`DR:199`），`status != WRITE_FAILED` 時 `return failed(disabled)`，並刪掉 dead code `orElseGet`。
    - S2-mA：restore 路徑記額度的時點（`DR:194-197`）。作法二擇一：呼叫前先 `materializeSpent.add`，回 WAIT 時再 `remove`；或在 Javadoc 寫明這個時點與它成立的前提。
    - Q2-m3：m-6 的 JUnit（`CST:358-374`）自己重寫了 `sideEffect` 的組合邏輯，沒有經過 `CS:861-865`。作法：把組合抽成可直接呼叫的 static，讓 test 經由它驗證。
    - Q2-m4：
      - `Track.backoff` 的 Javadoc（`CS:877`）補上「進入 SEALED 的寫入被拒」。
      - WARN 字句「200 ticks 起、每次被拒加倍」（`CS:447`）改用 `track.backoff.delayTicks()`。
      - `sealedLog`（`CS:474`）改用 `firstWarning`。
    - S2-mC：`warn` 的 Javadoc（`CS:829-831`）說得太滿。SEAL_WRITE 在 `ROLL_FORWARD_PENDING` 之後由 SRM 的 RETRY_FLUSH 確認，CS 只在 committed 時移除鍵（`CS:452`）。作法二擇一：縮小 Javadoc 的說法；或在 `sealed()` 觀察到已確認的 SEALED 時，清除 SEAL_WRITE 鍵。
    - Q2-m5：CA 類別 Javadoc `CA:53` 的「寫入」那一點補上：`catalogRecord` 與 RESTORE 方向回 WAIT，ROLL_FORWARD 在未確認時照寫。
- F5：PASSAGE_READY 提交前，依 §10.2 再驗一次 EXISTING；`DR:188` 的 `resolveExisting` 可以重用（它只讀不寫）。fix round 2：resolver 一律以 `DestinationResolver.forServer(server)` 取得（每個 server instance 一個，m-1）。TRR 只能由 PASSAGE_READY 進入，`ResolveResult.Sealed` 不接受 TRR。
  - （fix round 1，M-3）進入 SEALED 之後刷新 Controller（§10.7 :632，`CS:467` → `:700`）目前沒有測試鎖住：SEALED 前後 presence 都是 `COLLAPSE`、Controller 都是 ARMED，拿掉刷新也成立（「觀察」第 1 條）。F5 實作比較器時，要讓比較器在 SEALED 前後的數值不同，並補一個會因拿掉刷新而失敗的斷言。
- F6：
  - 重啟矩陣：SEALED(`MATERIALIZE_FAILED`、`AUTHORITY_UNREADABLE`) 在重啟之後的一次重試（W9）；開服時就是 COLLAPSING、catalog 已是 `DISABLED` 的窗口（「偏離」第 4 條）；SEALED 寫入與門之間的 crash（「觀察」第 2 條）。
  - （fix round 1，I-1 的跨重啟版）重啟之後 SEALED(`MATERIALIZE_FAILED`) 的那一次重試，在 RESOLVE 第 1 步就因 allocation ledger 未確認（另一個 session 的 append 讀回失敗、仍在退避）而封存為 `ALLOCATION_IO_FAILED`；之後的邊緣觸發重試仍要把 `DISABLED` 視為 §8.3 已完成，改回 `EAGER_ENABLED`、物化、確定目的地，不得是 `AUTHORITY_CORRUPT`。開服時就是 COLLAPSING、catalog 已是 `DISABLED` 的變形（第一次 RESOLVE 在改回之前以別的原因封存）同理。同一個 instance 內的版本已由 DRT 與 G-DISABLED 鎖住（見「Fix round 1」），F6 要補 cross-JVM 的版本。
  - `build.yml` 的明列清單：本 task 沒有新增 default run 的 test；M5 run 的 6 個新 test 不在 `_windows` 規則內（各平台都執行）。
  - harness：`MaterializeFault`、`CheckedReadFault` 已是 `Slot`；`CheckedReadFault` 以檔名比對，M4 的 discovery 讀取也會被它攔到（README 已註明）。
  - （controller 補，fix round 2 的 spec S2-mB）`CS:370-371` 對 `sealRetryDue` 的接線沒有 CS 驅動的測試：把接線改成每個 tick 都呼叫 `seal` 的 mutation 會存活。原因是 production 的 REJECTED 只剩 `put` 的決定性驗證失敗，GameTest 造不出來。F6 整理 harness 時，新增 journal `put` 的 fault `Slot`，補一個經 `collapsing()` 驅動的 GameTest：被拒之後依 per-session 退避重試、不重跑 RESOLVE。
- F7：玩家可見的「量子通道無法開啟，已封存。」與 SEALED 時可以照一般方式開門離開，要寫進玩家文件；管理員處理永久原因的方式（ERROR「需要管理員處理」）屬 §2 已列的限制。
  - （controller 補，S-m3）spec errata：
    - §7.2：註明提交 COLLAPSING 時，ledger 不可能已有本 token 的合法 entry，所以一律用 §6.1 的推導值；entry 的比對在 RESOLVE 進行（Q1）。
    - §8.3／§12：catalog 若是本 session 推導 id 的 DISABLED，就視為 §8.3 已完成，與最近一次封存原因無關（Q2、I-1）。由每個 server instance 的物化額度決定下一步：
      - 額度還在：走 §8.3 的重試，改回 EAGER_ENABLED 並物化，每個 instance 一次；
      - 額度已用掉：SEALED(`MATERIALIZE_FAILED`)，等下次重啟。
    - §6.2 :287 的「（§8.3 的重試路徑才可能是 DISABLED）」改寫成同一規則。
  - （controller 補，fix round 2）已知偶發清單補一則：`m2_task4_disconnect_io` 偶爾多一則「艙體 … 的 session 協調失敗」WARN（見「裁示」第 6 條）。

### Fix round 1（`7d6fae7`、`e0780fc`、`7f91400`）

- Review：spec review ❌（Critical 0、Important 1、Minor 6）。Q1、Q2 判定符合 spec，controller 已接受（見「裁示」）。開工 HEAD 是 `459b4e0`，worktree 乾淨。第一位 implementer 做完 `7d6fae7` 與 I-1、M-2 的程式和測試之後，因使用者的用量上限在安全點停下（交接 `f4a/fix1/STATUS.md`）；第二位 implementer 從 `7d6fae7`＋未 commit 的工作樹接續：自我審查 I-1、M-2 的 diff（沒有改動程式）、驗證 commit 的中間狀態、拆 commit、寫 note、跑 run-all。
- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `7d6fae7` `test: lock the lifecycle failure records that the stop verification relies on`（parent `459b4e0`）：M-1。`ULS`（`materializeAllocated` 前提之後的部分抽成 package-private `attemptMaterialize`，行為不變）；新增 JUnit `src/test/java/dev/quantumchamber/universe/UniverseLifecycleMaterializeFailureTest.java`。
  - `e0780fc` `fix: accept a disabled record of the session's own universe regardless of the last sealed reason`（parent `7d6fae7`）：I-1。`DR`、`CS`、`CA`（只有 Javadoc）、`DRT`、`M5U`（G-DISABLED；不含 G-WAIT）。
  - `7f91400` `test: drive the collapse service through a long destination wait`（parent `e0780fc`）：M-2，只有 `M5U`（G-WAIT 與 helper `waitWarnings`）。
  - 本小節由其後的 docs commit `docs: record F4a fix round 1 in the M5 note` 加入；同一個 commit 也更新本節的 Review 行、plan Step 1 表、補充第 2、4 點的加註、稽核表、「偏離」「裁示」「交給後續」。
- Code range `459b4e0..7f91400`。沒有改 `ResolveResult.java`、`CollapseServiceTest.java`、`M5GameTestSupport.java`、`M5TestHooks.java`、mixin 與 `quantumchamber-test.mixins.json`、`build.gradle`、`.github/`、`scripts/verification/`。
- 本小節的行號以 `7f91400` 為準（簡稱同前；`ULMF` 指 `UniverseLifecycleMaterializeFailureTest`）。證據都在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4a/fix1/`（以下簡稱 `fix1/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`adapter.py`（RED adapter、mutation 與 commit 中間狀態的 adapter，套用前保存原檔，還原後以 `git hash-object` 核對）、`mut-junit.ps1`、`mut-gate.ps1`；gate root 在 `.superpowers/verification/`。

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| I-1：「接受 `DISABLED`」綁在最近一次封存的原因上。首輪的 `Track.materializeRetry` 在每次封存都清掉，`retrySealed` 只在 `MATERIALIZE_FAILED` 時設回；resolver 在 `EAGER_ONLY` 下把 `DISABLED` 判為 `AUTHORITY_CORRUPT`。同一個 instance 中 `MATERIALIZE_FAILED` 重試 → 第 1 步 ledger 未確認 → `ALLOCATION_IO_FAILED` → 下一次重試得到永久的 `AUTHORITY_CORRUPT`（違反 §6「既有紀錄的驗證」、§6.2、§11.2） | `DR:134`：NEW 的第 2 步一律以 `MATERIALIZE_RETRY` 驗證 catalog（UniverseId 是本 session 的推導值，第 1 步已和 allocation entry 比對，`DR:128`）。這筆是 `DISABLED` 時（`DR:137`）依 §6 收斂規則表等同 §8.3 已完成：`materializeSpent.add(id)` 成功（本 instance 還沒用掉額度）就走 §8.3 的重試，`reenable`（`DR:168`）之後再物化；已經用掉就在 `DR:140-143` 直接 SEALED(`MATERIALIZE_FAILED`)，不寫、不物化，等下次重啟。物化 `FAILED` 時也記為已用掉（`DR:153`）。絕不判 `AUTHORITY_CORRUPT`。`materializeSpent`（`DR:83`）只在記憶體，resolver 每個 server instance 一個（「偏離」第 2 條），跨重啟自然重置；額度在 resolver 真的決定 reenable（第 2 步）或物化失敗時才記，第 1 步就封存時不記。lifecycle 對本 instance 失敗過的 descriptor 回 `FAILED_BEFORE`（`ULS:164`），是同一件事的後盾。`CS`：刪除 `startupCollapsing`（欄位、建構子參數、`onStarted`、`retainAll`、`collapsing()` 裡的設定）與 `Track.materializeRetry`，`resolve()` 呼叫 `resolver().resolve(view)`（`CS:395`），`retrySealed`（`CS:537`）不再設旗標；`restartRetryConsumed`（`CS:875`）只管 SEALED→COLLAPSING 重試的觸發。`CA` 只改 `AvailabilityPolicy` 與兩個 `@param` 的 Javadoc（`CA:75-84`、`:598-599`、`:648-649`），行為不變（release JAR 的 `CollapseAuthorities` 各 class 以 `javap -c -p` 去掉行號之後與首輪相同，見 run-all 一列）。catalog 中別的 UniverseId 是 `DISABLED`（EXISTING 重驗）維持 `EAGER_ONLY` → `EXISTING_INELIGIBLE`（`DR:221`），不在本輪範圍。被取代的 JUnit：首輪的 `materializeRetryReenablesThenMaterializesAndOnlyTheRetryAcceptsDisabled` 改寫為 `DRT:227` `disabledRecordOfTheSessionIsReenabledThenMaterialized`，拿掉「沒有重試旗標時 `DISABLED` 是損壞」：新語意下本 session 推導的 UniverseId 的 `DISABLED` 一律是 §8.3 已完成（理由寫在它的 Javadoc） | 下方三條路徑；RED-I1（JUnit、GameTest）、GREEN、MI-1～MI-5 |
| M-1：lifecycle 的失敗記入 materialize failures，沒有測試鎖住（`MFM` 在 backend 開頭回 `FAILED_ROLLED_BACK`、沒有 owner，拿掉首輪 `ULS:158`、`:169` 的記錄，`verifyStopped` 照樣通過） | 三擇一選「補 universe package 的 JUnit」：`ULS:162` `attemptMaterialize(descriptor, failedDescriptors, materializeFailures, materializer)` 由 `materializeAllocated`（`ULS:151`）呼叫，是原本前提之後的部分抽出成 package-private，行為不變。`ULMF` 6 個 test 以真的 `verifyStopped` 判定：`FAILED_ROLLED_BACK` 記入之後，失敗的 owner 可以通過停服驗證，沒有紀錄的同一個 owner 不能通過（對照）；`FAILED_UNHEALTHY`、例外、`Error` 記入之後停服驗證拒絕（`Error` 記入之後照舊丟出）；`REJECTED` 不記入、同一個 instance 再遇到是 `FAILED_BEFORE` 而且不呼叫 backend；失敗過的 descriptor 是 `FAILED_BEFORE`、不重複記入。只補覆蓋，以 mutation 證明。例外與 `Error` 的案例會讓停服 `STOPPED_UNVERIFIED`（M3 的設計），所以只能在 JUnit 驗，不能放進 M5 run | GREEN-M1；MU-1～MU-4；S1 |
| M-2：CS 的等待分支（`CS:397` → `:414` `waiting`）沒有測試；把等待改成封存的 mutation 抓不到，首輪「偏離」第 6 條的說法不成立 | G-WAIT `M5U:665` `existing_resolve_waits_for_an_unconfirmed_discovery_without_sealing_and_warns_once`（batch `m5_universe_resolve_wait`，tickLimit 1700，約 1400 ticks；catalog 增量 1）與 helper `M5U:720` `waitWarnings`（依 `LOG_MARKER` 與 sid 篩選）。D1（DORMANT NEW）的 discovery 追加在 atomic move 之後讀回失敗 → D1 進 SEALED(`ALLOCATION_IO_FAILED`)、discovery 未確認（已落地的 record 保留）；D1 收尾之後，同一座艙的 D2（DORMANT EXISTING，目的地是 D1 的宇宙 U）提交 COLLAPSING，discovery 的收斂再失敗兩次（權威的退避 200、400），D2 的 RESOLVE 一直等待。registered＋620：仍是 COLLAPSING、沒有確定目的地、等待 WARN 恰 1 則；＋1300：仍是同一則；＋1400 起 discovery 收斂之後確定目的地，WARN 只 1 則、從未封存。`CheckedReadFault` 也會攔到 M4 的 discovery 讀取，所以兩份收據都在注入之前建立。note 的「偏離」第 6 條已更正 | GREEN M5；MW-1、MW-2 |
| M-3：進入 SEALED 之後刷新 Controller 沒有測試 | 不在本輪補；寫入「交給後續」的 F5 | — |
| M-4：F4b 的交接 | 「交給後續」的 F4b 加一條：§7.3 門狀態的重新套用要涵蓋 SEALED 的關門 | — |
| M-5：note 文字更正 | 「偏離」第 1 條改引 spec §7.2 原文；稽核表 `m5_f3a_*` 一列改為兩個經 `f3aFixture`、`m5_f3a_native_collapsing_return` 經 `nativeCandidates`（`M4C:1849`，`:1067` 暫停）。兩處都標「fix round 1 更正」 | 已逐行核對 spec `:370` 與 `M4C` |
| M-6：controller 已裁示的兩項 | 「需要裁示」改為「裁示」，寫入 Q1、Q2 與 M-6 兩項；resolve 的最終簽名寫在「偏離」第 2 條；runtime 的 `REJECTED` 不記入列為「偏離」第 10 條 | — |

I-1 的三條路徑（第二位 implementer 的自我審查；派工 RED 的三條）：

- **(a) 同一個 instance**：`MATERIALIZE_FAILED` 的重試 → 第 1 步 ledger 未確認 → `ALLOCATION_IO_FAILED` → 下一次重試改回 `EAGER_ENABLED`、物化、RESOLVED，絕不是 `AUTHORITY_CORRUPT`。由 `DRT:248` `disabledRecordOfTheSessionIsRetriedRegardlessOfTheLastSealedReason` 鎖住：同一個 resolver（同一個 instance），fixture 是 §8.3 已完成的合法狀態（`afterMaterializeFailure`：allocation entry 已確認、catalog 的 `DISABLED` 已落盤），另一個 session 的 append 讀回失敗讓 ledger 未確認、仍在退避；第一次 RESOLVE 是 SEALED(`ALLOCATION_IO_FAILED`)、沒有物化、catalog 仍是 `DISABLED`；退避之後是 `Resolved`，物化之前已 checked 改回 `EAGER_ENABLED`，之後追加 discovery。RED-I1 在目標斷言（「下一次重試仍走 §8.3，不是 AUTHORITY_CORRUPT」與其後三個）FAIL；MI-5（改回依封存原因判斷）也 FAIL。CS 層的 SEALED(`MATERIALIZE_FAILED`) 重試只對開服 attach 時就在 journal 的 session 發生，要重啟才造得出來，交給 F6（「交給後續」）。
- **(b) 已用掉額度**：再遇到 `DISABLED` 是 SEALED(`MATERIALIZE_FAILED`)，不寫、不物化。由 `DRT:278` `aSpentInstanceSealsMaterializeFailedWithoutWritingOrMaterializingAgain` 的 3 列鎖住（本 instance 第一次物化就失敗；§8.3 的重試物化失敗；§8.3 的重試改回 `EAGER_ENABLED` 的寫出失敗而 restore）：第二次 RESOLVE 都是 SEALED(`MATERIALIZE_FAILED`)、物化次數不變、三個權威檔的 bytes 不變、catalog 維持 `DISABLED`。RED-I1 三列都 FAIL（`AUTHORITY_CORRUPT`）；MI-2、MI-3、MI-4、MI-5 都抓得到。CS 層：這個原因是 restart-only，`seal` 時就標 `restartRetryConsumed`（`CS:464`），本 instance 不再重試；正常流程下這條是防守，「每個 instance 一次」不必依賴 CS 的簿記。
- **(c) Q2 路徑**：開服時 COLLAPSING＋`DISABLED` → 第一次 RESOLVE 在改回之前以別的原因封存 → 之後的重試仍改回。fix round 1 之後它在結構上與 (a) 是同一條路徑：CS 不再區分「開服時就是 COLLAPSING」與「本 instance 重試回到 COLLAPSING」（`startupCollapsing` 刪除），resolver 也不知道 session 怎麼到 COLLAPSING，只看 catalog 的 `DISABLED` 與本 instance 的 `materializeSpent`；第一次 RESOLVE 在第 2 步之前封存時不用掉額度，之後的重試在第 2 步照常改回。所以由 (a) 的 DRT 與 GameTest G-DISABLED（`M5U:601`，batch `m5_universe_disabled_retry`，tickLimit 600，catalog 增量 1）一起鎖住：G-DISABLED 的 session 在本 instance 由 DORMANT 提交 COLLAPSING，catalog 這筆已是 `DISABLED`；第一次 RESOLVE 在 allocation 的讀回失敗封存為 `ALLOCATION_IO_FAILED`，改回之前沒有 world；退避之後由艙外進入 → 重試一次 → 改回 `EAGER_ENABLED`、物化、discovery、目的地確定，沒有 `AUTHORITY_CORRUPT`。RED-I1（GameTest）以 `sealed=AUTHORITY_CORRUPT` FAIL；MI-5 FAIL。跨重啟的版本交給 F6。
- G-DISABLED 的 fixture 有一處簡化（觀察，未修改）：catalog 這筆由 fixture 直接配置並改為 `DISABLED`，沒有對應的 allocation entry（M5 本身不會產生這個狀態：catalog 只在 entry 確認之後配置），所以第 1 步的失敗來自本 session 自己的 append 讀回失敗，不是「另一個 session 的 append 未確認」。封存之後 CS 與 resolver 走的路徑相同；合法狀態的版本是 `DRT:248`。

證據（逐輪的 state 記 HEAD、porcelain 與逐檔 blob）：

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED-I1（JUnit） | `459b4e0`＋`DRT`＋RED adapter `red-i1-flag-from-sealed-reason`（首輪的 resolver 加上 `resolve(view)` 轉接，模擬修改前 CS 的旗標：本 instance 對這個目的地的第一次 RESOLVE 傳 true、之後傳 false；`fix1/red-i1-junit-state.txt`） | Gradle `test --tests …DestinationResolverTest` | 20 個中 2 個 FAIL，就是 (a)、(b) 的兩個新 test，都在目標斷言（`fix1/red-i1-failure-types.txt`；型別中的 `IllegalStateException` 來自斷言訊息裡的 `Sealed[…failure=…]` 字串，不是丟出的例外）。改名的 `disabledRecordOfTheSessionIsReenabledThenMaterialized` 在 adapter 下通過（第一次 RESOLVE 傳 true），由 MI-1 鎖住 |
| RED-I1（GameTest） | `459b4e0`＋同一個 adapter＋`M5U`（只有 G-DISABLED，blob `c8ec475`；`fix1/red-i1-m5-state.txt`）。CS 是 `459b4e0`，呼叫首輪的兩參數 `resolve(view, flag)`，所以就是修改前的程式 | `gate-red-i1-m5-4ea719ea586045268a4224f424fa72eb` | G-DISABLED FAIL：`resolvedAfterTheRetry=false`、`neverAuthorityCorrupt=false`、`reenabledMaterializedAndDiscovered=false`，`sealed=AUTHORITY_CORRUPT`；重試之前的 witness 都成立 |
| GREEN-I1（JUnit） | `459b4e0`＋I-1 的工作樹（`fix1/green-i1-junit-state.txt`） | Gradle `test --tests` | `DRT` 20、`CST` 12、`GT` 2、`CollapseAuthoritiesTest` 33 全 PASS |
| GREEN-M1（JUnit） | `459b4e0`＋I-1 與 M-1 的工作樹（`fix1/green-m1-junit-state.txt`；M-1 只有自己的兩個檔，`7d6fae7` 的中間狀態另見 S1） | 同上 | `ULMF` 6、`UniverseRoleResolverTest` 33 PASS |
| GREEN M5 | `459b4e0`＋最終工作樹（`fix1/green-fix1-m5-state.txt`；7 個檔的 blob 與 `7f91400` 相同） | `gate-green-fix1-m5-c6e71c190fc247408b06ee90f330f29c` | 12/12；catalog 增量逐 test 串接 0 → 11，`M5_UNIVERSE_SUITE_END records=11`，`M3_UNIVERSE_STOPPED_VERIFIED worlds=6 runtimeReceipts=6`；WARN 11／ERROR 1 |
| JUnit mutation | GREEN 的工作樹（`fix1/mut-base-state.txt`），各自套用 adapter（`fix1/mut-chain.log`、`fix1/mutations-junit-summary.txt`） | Gradle `test --tests` | 9 個都被抓到：MI-1（NEW 不接受 `DISABLED`）→ 5 個；MI-2（不看額度）、MI-3（物化失敗不記額度）、MI-4（「第一次」記在第 2 步）→ (b)；MI-5（改回依封存原因：本 instance 第一次 RESOLVE 才接受，不論停在哪一步）→ (a)、(b)；MU-1（不記 backend status）→ 3 個；MU-2（不記例外）→ 2 個；MU-3（`REJECTED` 也記）→ 1 個；MU-4（不拒絕失敗過的 descriptor）→ 2 個 |
| GameTest mutation | 同上，`fix1/mut-gate.ps1`（`fix1/mutations-gate-summary.txt`） | MI-4 `gate-mut-mi4-accept-disabled-only-first-time-bc5d3f2f0d9b485396a58e5265d87bae`；MI-5 `gate-mut-mi5-accept-disabled-only-on-the-first-resolve-b0fdfe520e2a4eb5868da5c1b7e8f679`；MW-1 `gate-mut-mw1-wait-seals-02add80c2887454eb6015b697e450e6c`；MW-2 `gate-mut-mw2-wait-warn-not-deduped-0a7ff42519224f2694ee77281323ae8a` | MI-4 **存活**（PASS，預期內：它的「第一次」在第 2 步才記，G-DISABLED 的第一次 RESOLVE 停在第 1 步，所以它不是「依封存原因」的模型；DRT 的 (b) 抓得到）。真正模擬「依封存原因」的是 MI-5：G-DISABLED FAIL（`resolvedAfterTheRetry`、`neverAuthorityCorrupt`、`reenabledMaterializedAndDiscovered`）。MW-1（等待改成封存）→ G-WAIT 的有界等待逾時，`waitingWithoutSealing=false`（D2 已 SEALED）；MW-2（等待的 WARN 不去重）→ G-WAIT `oneWaitWarnAfterSixHundredTicks`、`stillWaitingWithTheSameWarn`、`warnedOnlyOnce` FAIL |
| S1（`7d6fae7` 的中間狀態） | `459b4e0`＋adapter `state-s1`（I-1 的 5 個檔換回 HEAD 版本，只剩 M-1 的兩個檔；`fix1/s1-junit-state.txt`、`fix1/s1-m3-run-state.txt`） | JUnit；`gates.ps1 -Mode M3` `gate-s1-m3-run-32f6f31cb3394d2a906f4bf9098d5e72` | `ULMF` 6、`UniverseRoleResolverTest` 33、`DRT` 18（首輪版）、`CST` 12 PASS；M3 PASS（動到 lifecycle） |
| S1 誤操作（不採計） | 同上（`fix1/s1-m3-*`） | `gate-s1-m3-b215f5186660456d85bed6b27f8516cb` | 第一位 implementer 誤以 `fix1/gate.ps1 -Mode GameTest -Batches '__none__'` 呼叫：server 啟動時「指定 batch 不存在」失敗，沒有任何 test 執行；summary 裡的 174 行 PASS 是 result-build 裡舊的 XML，不是這次的結果。不是偶發，改以上一列的 `-Mode M3` 採計 |
| S2（`e0780fc` 的中間狀態） | `7d6fae7`＋adapter `state-s2`（`M5U` 剪掉 G-WAIT 與 `waitWarnings`，blob `c8ec475`；其餘 4 個檔是 I-1 的最終版；`fix1/s2-junit-state.txt`、`fix1/s2-m5-state.txt`、`fix1/s2-focused-state.txt`） | JUnit；`gate-s2-m5-5fc8d74de10b49268eca284dae913c64`；default focused `gate-s2-focused-a2a905b964fb48988789032f8cc0a41f` | JUnit `DRT` 20、`CST` 12、`GT` 2、`CollapseAuthoritiesTest` 33 PASS。M5 11/11，`records=10`、`worlds=5 runtimeReceipts=5`，WARN 8／ERROR 1。default focused 21/21（全部 18 個 `m5_collapse_*` 與 3 個 `m5_f3a_*` batch；本次 XML 的 timestamp 是 `2026-10-05T10:32:44Z`，boundary oracle PASS，latest.log 沒有任何「目的地已確定」「準備階段尚未實作」「塌縮進入 SEALED」「M5 ALLOCATION_LEDGER」「M5 CATALOG」的行）。I-1 改了 CS，default run 的 CS 也一併跑過 |
| run-all | `7f91400`，工作樹乾淨（`fix1/run-all-1-state.txt`；`STATUS` 是空的；`fix1/run-all-1-console.log`） | `run-all-e35b21eba4614c60ba09aa9465cc0098`（Full：`gate-full-4a8fe4e503cf456189709a2d5461a7d2`；M5：`gate-m5-gametest-32310897be21494eb9c3e9251ce131c0`；M3：`gate-m3-40d2964ba0d94c52b7ddc1aac40606b5`） | `END ALL exit=0 HEAD=7f91400d966aab54326a00355c2555e7a4a2508c`，約 17 分鐘（18:35:58→18:52:49）。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=12/12 perTest=51+1 junit=603 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=382entries/335classes sources=214 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。HARNESS 雜湊與首輪 run-all 相同。default 與 legacy 的 boundary oracle 都是 PASS。與首輪 run-all（`run-all-efb99d1e845549699febf6356355625f`）逐項比較：JUnit 595 → 603、M5 10 → 12（見下方「測試」），其餘各項不變。release JAR 382／335 與 sources 214 的 entry 清單完全相同（`fix1/jarcmp-release-run-all-1.txt`、`fix1/jarcmp-sources-run-all-1.txt`，以 F4a-0 的 `f4a0/fix1/jarcmp.py` 比對）；以 `javap -c -p` 去掉行號之後內容不同的只有 `CollapseService`、`CollapseService$Track`、`DestinationResolver`、`UniverseLifecycleService`，`CollapseAuthorities` 的各 class 只差行號；sources 內容不同的是 `CollapseAuthorities.java`、`CollapseService.java`、`DestinationResolver.java`、`UniverseLifecycleService.java`。log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`fix1/wbcmp.py`）：Full 54／28 → 54／28，沒有任何差異（`fix1/wbcmp-full-run-all-1.txt`），latest.log 沒有任何「目的地已確定」「準備階段尚未實作」「塌縮進入 SEALED」「SEALED 重試」「M5 ALLOCATION_LEDGER」「M5 CATALOG」的行；legacy 0／0 不變；M5 run 6／1 → 11／1（`fix1/wbcmp-m5-run-all-1.txt`，見下方「測試」），`M5_UNIVERSE_SUITE_END records=11`、`M3_UNIVERSE_STOPPED_VERIFIED worlds=6 runtimeReceipts=6` |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對）。commit 時的 blob：`e0780fc` 的 5 個檔等於 S2 的 state（`fix1/commit-i1-blobs.txt`），`7f91400` 的樹等於 GREEN M5 的 state（`fix1/commit-m2-blobs.txt`），`7d6fae7` 的兩個檔等於 GREEN-M1／S1（`fix1/commit-m1-blobs.txt`）。`7f91400` 只多了 M5 run 的 G-WAIT（`M5UniverseGameTests` 只在 M5 run 執行），所以它的 default run 與 S2 相同。
- 上表的 run-all 在最後一個 code commit 執行（與 F4a 首輪、F4a-0 fix round 1 相同），由本 docs commit 記錄；docs commit 只改本 note，不影響 README「採計」的三個條件。派工要求在所有 commit 之後跑 run-all，所以 docs commit 之後另跑一次，root 與 SUMMARY 記在 `fix1/run-all-2-console.log`，交 controller 核對。
- 本輪沒有遇到派工列出的已知偶發，也沒有停服卡住；不採計的 root 只有上表的 S1 誤操作，其餘失敗的 root 都是刻意的 RED 或 mutation。

測試：

- JUnit 595 → 603：`ULMF` ＋6；`DRT` 18 → 20（改名 1 個、新增 2 個）。`CST` 仍是 12、`GT` 仍是 2。
- M5 GameTest 10 → 12：G-DISABLED、G-WAIT 各 ＋1，各自一個 batch，名稱不以 `_windows` 結尾（各平台都執行）。catalog 增量各 ＋1（G-DISABLED 是 fixture 預先配置、之後改回 `EAGER_ENABLED` 的那一筆，G-WAIT 是 D1 的宇宙 U），suite 結束時 9 → 11 筆；`M3_UNIVERSE_STOPPED_VERIFIED` worlds 4 → 6（兩個 test 各物化一個宇宙）。
- M5 run 的 log：WARN 6 → 11、ERROR 1 不變。多出的 5 則都是 test 刻意造成、而且由 witness 斷言的：G-DISABLED 的 allocation ledger 寫入未確認（`TICK_LOG_MARKER`）與 SEALED(`ALLOCATION_IO_FAILED`)；G-WAIT 的 discovery 寫入未確認（`TICK_LOG_MARKER`）、D1 的 SEALED(`ALLOCATION_IO_FAILED`) 與 D2 的「目的地解析持續等待超過 600 ticks」。兩個 test 都在看到「目的地已確定」之後就收尾，沒有走到 PREPARE stub 的 WARN。
- default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 都不變：本輪沒有新增或刪除 default run 的 `@GameTest`。

### Fix round 2（`abb7ddf`、`0cdc951`、`72e4d8c`、`df66619`）

- Review：fix round 1 之後 spec re-review ✅（Critical 0、Important 0、Minor 3：S-m1～S-m3），quality review Approved（Critical 0、Important 0、Minor 8：m-1～m-8）。controller 開窄範圍的 fix round 2，處理 quality m-1～m-8 與 spec S-m1、S-m2（S-m3 由 controller 處理）。開工 HEAD 是 `ff4fc55`，worktree 乾淨。本輪的 review 結果（spec ✅、quality Approved）見本節開頭的「最終 review」。
- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `abb7ddf` `test: share one log capture across the collapse JUnit tests`（parent `ff4fc55`）：m-8。新增 `src/test/java/dev/quantumchamber/collapse/CollapseLogCapture.java`；`CAT`、`CST`、`DRT` 刪除各自的 appender。
  - `0cdc951` `refactor: keep one destination resolver per server and route its catalog access through the authorities`（parent `abb7ddf`）：m-1～m-4。`DR`、`CA`、`CS`（只有 resolver 的生命週期）、`ULS`（`ready`）、`DRT`、`CAT`、`M5U`（G-MATFAIL 的 m-1 witness）。
  - `72e4d8c` `fix: back off a rejected SEALED write and clear side-effect warning keys on success`（parent `0cdc951`）：m-5(b)、m-6。`CS`、`CST`。
  - `df66619` `test: lock the pending SEALED write, the wait warning start and the lifecycle failure record`（parent `72e4d8c`）：m-5(a)、S-m1、S-m2、m-7。`ULS`（`materializeFailures`）、`M5U`。
  - 本小節由其後的 docs commit `docs: record F4a fix round 2 in the M5 note` 加入；同一個 commit 也在本節受影響的舊敘述加註「fix round 2」（「偏離」第 2、8 條，plan Step 1 表的 §8.3 列，補充第 2、4、5 點，「交給後續」的 F5）。
- Code range `ff4fc55..df66619`。沒有改 `ResolveResult.java`、`M5GameTestSupport.java`、`M5TestHooks.java`、`ULMF`、mixin、`build.gradle`、`.github/`、`scripts/verification/`、README。
- 本小節的行號以 `df66619` 為準（簡稱同前；`CLC` 指 `CollapseLogCapture`）。證據都在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4a/fix2/`（以下簡稱 `fix2/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`mut-junit.ps1`、`mut-gate.ps1`、`failure-types.ps1`、`wbcmp.py` 由 `fix1/` 複製（只改註解）；`adapter.py` 是本輪的 RED adapter 與 mutation（套用前保存原檔，還原後以 `git hash-object` 核對）。gate root 在 `.superpowers/verification/`。

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| m-1：`forServer` 每次建新的 resolver，「每個 instance 一份物化額度」只靠 CS 的快取 | 二擇一選「比照 `CA.get` 以 server 為 key 保存」：`DR:63` `INSTANCES`（IdentityHashMap）、`DR:113` `forServer` 以 `computeIfAbsent` 取得、`DR:121` `release` 由 `CS:156` 在 `SERVER_STOPPED` 註冊。CS 的 `resolver` 欄位與 `resolver()` 刪除，`CS:394` 每次經 `forServer`。理由：(1) `materializeSpent`（`DR:97`）是塌縮流程的簿記（§8.3 的重試額度、§11.2「每個 server instance 一次」），不是 M3 lifecycle 的狀態；ULS 的 M5 API 依 F4a-0 裁示 2 只回傳結果，搬進 Context 會把塌縮語意放進 M3 的 owner，DRT 也只能驗 fake 的額度。(2) 以 server 為 key、停服移除與 `CA.get` 相同；單人遊戲重開世界是新的 server instance，自然拿到新的 resolver。Javadoc 更新（class doc、`materializeSpent`、`forServer`） | G-MATFAIL 新 witness（`M5U:458`、`:462`）：封存之後再取一次 resolver 是同一個（`oneResolverPerServer`）；以只在記憶體的 COLLAPSING view（不寫 journal）再解析一次，因本 instance 已用掉額度而直接 `MATERIALIZE_FAILED`、不改回 `EAGER_ENABLED`、不再物化（`anotherLookupSeesTheSpentQuota`）。MM-1（`forServer` 每次 new）→ 只有這兩個 witness FAIL |
| m-2：DR 直接寫 catalog，繞過 CA 的退避、WARN、INFO；EXISTING 的 catalog 驗證手寫；DR 有第二個 `CatalogPort` | CA 新增 `CA:100` `WriteDirection { ROLL_FORWARD, RESTORE }`、`CA:692` `changeCatalogAvailability(id, availability, direction)` 與 `CA:669` 唯讀的 `catalogRecord(id)`，都回 `Lookup`。`allocateOrVerifyCatalog` 與 `catalogRecord` 共用 `CA:864` `catalogGate`（初始化失敗、不健康 CORRUPT、未確認 WAIT、已確認時清除簿記；`allocateOrVerifyCatalog` 的行為不變）；`CA:888` `flushCatalog(state, restore)`：restore 時先把 current 改回、WARN 字句說明 restore，既有呼叫端的字句不變。ROLL_FORWARD 不看 catalog 是否已確認（較保守的新內容要立刻記下，§8.3），RESTORE 在未確認時 WAIT、不變更（之後的物化依賴它，§6 收斂共通第 2 點）；已是目標 availability 為 FOUND、變更成功為 APPENDED。唯讀查詢不帶 policy：§8.1（EXISTING）不符是 `EXISTING_INELIGIBLE`，§6.2（NEW）既有紀錄不符是 `AUTHORITY_CORRUPT`，帶 policy 的驗證只能擇一，所以比照 `discovery(id)` 回 flushed 的 record，由呼叫端以既有的 `catalogRecordMatches` 判斷。DR：刪除第二個 `CatalogPort` 與 `ServerCatalog`，建構子改為 `(authorities, lifecycle, gameTime)`；`DR:194` `reenable` 走 RESTORE（WAIT 時等待、不用掉額度），`DR:208` `materializeFailed` 走 ROLL_FORWARD（寫出失敗照舊記 ERROR），`DR:225` EXISTING 改用 `catalogRecord`。§8.3 兩個方向的語意不變 | RED-B（下表）：DRT `resolverCatalogWriteFailureBacksOffTheNextConvergence`（`DRT:355`，兩個方向：寫出失敗之後下一個 tick 的 `convergeIfNeeded(CATALOG)` 不重寫、退避期過後寫出一次）與 `resolverCatalogWriteFailureLogsTheAuthorityWarnAndTheConvergenceInfo`（`DRT:395`：權威層級的 WARN 一則、restore 的字句、收斂完成的 INFO 一則）都 FAIL。既有的 `disablingWriteFailure…`、`reenableWriteFailure…`、`aSpentInstance…` 照常通過，收斂時點由 now＋1 改為 now＋200（寫出失敗現在記入 catalog 的退避，正是 m-2 要的行為）；G-MATFAIL、G-DISABLED 照常通過。另補 CAT `catalogRecordIsAReadOnlyQueryOfTheFlushedCatalog`（`CAT:971`）、`catalogAvailabilityChangeRollsForwardOrRestoresWithTheCatalogBookkeeping`（`CAT:1000`），並在 `failedInitialization` 與不健康 catalog 的表加上兩個新 API（fail closed） |
| m-3：EXISTING 等 catalog 收斂期間，每個 tick 都從磁碟讀 discovery | `DR:221` `resolveExisting`：先收斂 DISCOVERY 與 CATALOG，`catalogRecord`（只看記憶體）是 WAIT、或 discovery 未確認（`DR:227`）就等待，兩者都確認之後才 `discovery(id)` 讀磁碟。失敗的先後維持首輪（discovery 的失敗先於 catalog 的失敗） | RED-B：DRT `existingReadsTheDiscoveryOnlyAfterTheCatalogAndTheDiscoveryAreConfirmed`（`DRT:560`，`Io.readsOf` 計數）：修改前 catalog 未確認時 3 次解析讀了 3 次 discovery、收斂後共 4 次（預期 0、1）；discovery 未確認的一列修改前就不讀（`discovery(id)` 的閘門），列入只為完整 |
| m-4：`Lifecycle.active` 回 boolean，EXISTING 遇到 lifecycle 沒有 ready 的 context 會進永久的 `EXISTING_INELIGIBLE` | `DR:74` 改回 `DR:78` `Activity`（ACTIVE／NOT_ACTIVE／NOT_READY），`DR:242` NOT_READY → Wait；production 的 `DR:280` 先查新的 `ULS:216` `ready(server)`（Context 存在而且 ready），再查 `resolveActive`。ULS 只新增這個唯讀查詢，M3 行為不變 | RED-B：DRT `existingWaitsWhileTheLifecycleIsNotReady`（`DRT:599`）在 RED adapter `red-b-activity-stub`（只改簽名、語意維持修改前）下是 `Sealed[EXISTING_INELIGIBLE]`。`-Mode M3` PASS（GREEN-B、GREEN-D） |
| m-5(a)：進入 SEALED 的 `ROLL_FORWARD_PENDING` 沒有 CS 驅動的測試 | 新增 G-SEALPEND `M5U:752` `sealed_entry_with_an_unconfirmed_write_still_closes_the_door_and_releases_the_ticket`（batch `m5_universe_sealed_pending`，tickLimit 400，catalog 增量 1）與 helper `M5U:821` `sealWriteWarnings`。DORMANT NEW 收據；`installMaterializeFault`（物化失敗 → `DISABLED` → SEALED(`MATERIALIZE_FAILED`)）；flush 探針 `FlushFault(…, 1, current SEALED ∧ flushed COLLAPSING)`；`afterAutosaveClear` 的窗口 40。未確認的那一個 tick（`whenTicks` 等 `rollForwardPending`）：collapse view 是 SEALED、flushed 仍是 COLLAPSING、門已關（P 在 RESOLVE 之前打開了門）、來源 ticket 已釋放、訊息已送、這個 sid 的 SEAL_WRITE WARN 恰 1 則。下一個 tick recovery 的 RETRY_FLUSH 確認同一份 SEALED（探針計 2 次、受控失敗 1 次；窗口內的寫出都經探針、只帶本 session）。確認之後 5 ticks：沒有「目的地已確定」、backend 只物化 1 次、catalog 這筆是 `DISABLED`、allocation entry 1 筆、WARN 仍 1 則 | GREEN-D 的 `M5_JOURNAL_WINDOW`：commit 的 flush＋write@253、seal 的 flush@254（受控失敗）、`SessionRecoveryManager.tick` 的 flush＋write@255，`problems=[]`。MA-1（pending 時提早 return）→ `doorClosedWhilePending`、`ticketReleasedWhilePending`、`sealedMessageWhilePending`、`oneSealWriteWarn`、`sealWriteWarnStillOnce` FAIL，其餘成立 |
| m-5(b)：`REJECTED` 之後每個 tick 重試、沒有退避；note 的理由已不成立 | 二擇一選「保留並加上退避」：`CS:445` 被拒時 `Track.sealRejected`（`CS:904`：記下原因與 root cause、`backoff.failed(now)`），`CS:370` 只在 `sealRetryDue`（`CS:911`）時重試這筆寫入；寫入不再被拒時 `sealWritten`（`CS:916`）。WARN 照舊依 (session, SEAL_WRITE, dedupeKey) 去重，`next` 字句改為依 per-session 退避。`Track` 改為 package-private 讓 JUnit 驗證。理由：保留——spec §8.4「RESOLVE 與 PREPARE 的任何失敗都進 SEALED」：被拒時重跑 RESOLVE，權威可能已收斂（例如 `ALLOCATION_IO_FAILED` 在收斂之後變成 RESOLVED），等於丟掉已判定的失敗，只重試這筆寫入保留 RESOLVE 判定的原因與 root cause。退避——spec §6「journal 寫入失敗時的 session 行為」：restore 類「record 留在上一份已確認的狀態，該 session 套用 §11.2 的記憶體內退避」；§11.2 最後一點：「journal restore 類寫入失敗後的記憶體內退避，使用同一套 per-session 退避」。REJECTED 在寫入前就拒絕、journal 沒有改變，正是「留在上一份已確認的狀態」。「偏離」第 8 條已更正 | CST `rejectedSealWriteIsRetriedOnlyOutOfThePerSessionBackoff`（`CST:334`：被拒之後 200 ticks 內不重試、再被拒加倍為 400、重試的是原本的原因與 cause、寫入之後沒有待重試的寫入）。MS-1（`sealRetryDue` 不看退避，即修改前的每個 tick 重試）、MS-2（被拒時不記退避）都 FAIL。限制：REJECTED 在 production 只剩 `put` 的決定性驗證失敗，GameTest 造不出來（需要新的 fault mixin，不在本輪允許的路徑），所以 `collapsing()` 對 `sealRetryDue` 的接線沒有 CS 驅動的測試 |
| m-6：附帶動作的 WARN 鍵在成功時不清除，與 `warn()` 的 Javadoc 不符 | 取得來源 ticket（`CS:353`、`:544`，共用鍵 `ACQUIRE_TICKET`，`CS:110`）、進入 SEALED 時釋放來源 ticket（`CS:466`）、刷新 Controller（`CS:690`，含 F3d 的「終局之後刷新 Controller」）一律經 `CS:861` `sideEffect`：`CS:850` `attempt` 成功時移除該鍵，失敗交給 `warn`（去重抽成 `CS:841` `firstWarning`）。這三個鍵沒有 spec 規定「每個 session 只記一次」（那樣規定的只有 PREPARE stub 的 WARN，它不經 `warn`），所以依 Javadoc 成功時清除；`warn` 的 Javadoc 改寫說明 | CST `sideEffectWarningKeysAreClearedOnSuccess`（`CST:358`：失敗、同一原因、成功、同一原因再失敗 → WARN 為 是、否、否、是；只清自己的鍵；沒有簿記的 session 只執行）。MS-3（成功時不清除，即修改前）FAIL |
| S-m1：G-WAIT 沒有鎖住「600 ticks 之後才 WARN」 | `M5U:718`：registered＋590（等待約 585 ticks）時仍是 COLLAPSING、還沒有等待的 WARN（`noWaitWarnBeforeSixHundredTicks`）；G-WAIT 的 Javadoc 補一句 | MW-3（`CS:414` 的起點改早 100 ticks）→ 只有這個 witness FAIL |
| S-m2：M-1 的接線（`ULS:151` 傳 `context.materializeFailures`）沒有測試 | `ULS:227` 唯讀的 `materializeFailures(server, id, status)`：Context 的 materialize failures 中，這個 UniverseId 的 OVERWORLD world key、backend status 相符的筆數（不記 log）。G-MATFAIL `M5U:451` `lifecycleRecordedTheRolledBackFailure`（等於 1）；registered＋213 的 `lifecycleRefusesTheFailedDescriptor` 另要求仍是 1（`FAILED_BEFORE` 不重複記入） | MF-1（`ULS:151` 改傳拋棄式 list）→ 這兩個 witness FAIL |
| m-7：G-ALLOCIO 兩處 `TICK_LOG_MARKER` 的斷言沒有依 sid 篩選又斷言絕對筆數 | `M5U:533` `ledgerWarnCarriesTheTickMarker`、`M5U:551` `ledgerConvergedFirst`：這兩則 log 屬於權威、不屬於任何 session，訊息沒有 sid 可篩選，改為 `>= 1`，test 內寫明理由；「已收斂」仍由 `unconfirmed` 判定。README 不改 | GREEN-D、run-all 通過（只放寬，沒有 mutation） |
| m-8：三份 log4j appender | `CLC`（package-private）：只收 `quantumchamber` logger，`Entry(level, message, thrown, marked, tick)`，`entries()`、`messages(Level)`、`count(Level)`；建立時以 `LOG_MARKER` 送一則 INFO 探針。原本三份的探針不同（CAT 是不帶 marker 的 WARN、CST 是帶 marker 的 INFO、DRT 是帶 marker 的 ERROR），共用的取帶 marker 的 INFO：三者斷言的最低等級是 INFO，所以同時證明 WARN／ERROR 與 marker 攔得到 | `fix2/m8-testset-compare.txt`：CAT 33、CST 12、DRT 20、`GT` 2 的 testcase 名稱與結果，和 `ff4fc55` 的 run-all（`run-all-521b3824c2bd4c2386e935e0abbc4353` 的 Full）完全相同。`abb7ddf` 只改 src/test，main 與 testmod 的 blob 與 `ff4fc55` 相同，GameTest 沿用該次 run-all |

證據（逐輪的 state 記 HEAD、porcelain 與逐檔 blob）：

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| m-8 GREEN | `ff4fc55`＋A 的 4 個檔（`fix2/m8-green-junit-state.txt`） | Gradle `test --tests`（CAT、CST、DRT、`GT`） | 33＋12＋20＋2 PASS，test 集合與結果和基準相同 |
| RED-B（m-2、m-3、m-4） | `abb7ddf`＋DRT 的 RED 版（仍以修改前的 4 參數建構子建立 resolver）＋adapter `red-b-activity-stub`（只把 lifecycle port 改成三態的簽名，語意維持修改前；`fix2/red-b-junit-state.txt`） | Gradle `test --tests …DestinationResolverTest` | 24 個中 4 個 FAIL，就是 4 個新 test，都在目標斷言（`fix2/red-b-failure-types.txt`；型別中的 IOException 等來自斷言訊息裡列出的 log 與 `Sealed[…]` 字串，不是丟出的例外）；改了收斂時點的 3 個 §8.3 test 在修改前也通過 |
| GREEN-B | `abb7ddf`＋B 的 7 個檔（`fix2/green-b-*-state.txt`，blob 相同；JUnit 的 state 在 M5U 改動之前取，main 與 test 的 blob 相同） | JUnit；`gate-green-b-m5-838ac03c89d643c1997051e56ba55567`；default focused `gate-green-b-focused-6f2f9b7affc8423589521ef231a32fd6`；`gate-green-b-m3-92e7f6780e034f4e834cf1321009f359` | DRT 24、CAT 35、CST 12、`GT` 2、`ULMF` 6、`UniverseRoleResolverTest` 33 PASS；M5 12/12（`records=11`、`worlds=6`，WARN 11／ERROR 1）；focused 21/21（21 個 batch 照抄 `f4a/green-d-focused-summary.txt`），boundary oracle PASS，latest.log 沒有任何「目的地已確定」等 RESOLVE 的行；M3 PASS（universe 4、transfer 5） |
| MM-1 | GREEN-B 的工作樹＋adapter | `gate-mut-mm1-new-resolver-per-call-caee1a65285345b5aba0e0a174f8407d` | G-MATFAIL FAIL：只有 `oneResolverPerServer`、`anotherLookupSeesTheSpentQuota` 為 false |
| GREEN-C | `0cdc951`＋C 的 2 個檔（`fix2/green-c-*-state.txt`） | JUnit；`gate-green-c-m5-9afd54ea5ea44f94b59dda2c5cd32bb2`；focused `gate-green-c-focused-6848bc31099946c0b9a429209d1267f1` | CST 14、`GT` 2、DRT 24、CAT 35 PASS；M5 12/12；focused 21/21，boundary oracle PASS |
| MS-1～MS-3（JUnit） | GREEN-C 的工作樹，各自套用 adapter（`fix2/mutations-junit-summary.txt`） | Gradle `test --tests …CollapseServiceTest` | 都只有目標 test FAIL：MS-1、MS-2 → `rejectedSealWrite…`（退避期內也重試）；MS-3 → `sideEffectWarningKeys…`（成功之後同一原因不再記 WARN） |
| GREEN-D | `72e4d8c`＋D 的 2 個檔（`fix2/green-d-*-state.txt`） | `gate-green-d-m5-18276487c8a74045b40c58802225e39a`；focused `gate-green-d-focused-6a60fd7a995a49eb9edbe66ef013becb`；`gate-green-d-m3-b10fa77c323540aeabca015bd79d3ee7`；JUnit | M5 13/13，catalog 增量逐 test 串接 0 → 12，`records=12`、`worlds=6`，WARN 13／ERROR 1；focused 21/21，boundary oracle PASS；M3 PASS；JUnit `ULMF` 6、`UniverseRoleResolverTest` 33、`GT` 2、DRT 24、CST 14、CAT 35 PASS |
| MA-1、MW-3、MF-1（GameTest） | GREEN-D 的工作樹，各自套用 adapter（`fix2/mut-chain-d.log`、`fix2/mutations-gate-summary.txt`） | `gate-mut-ma1-pending-seal-returns-early-27ae93902f504de0a45298dddef8c2a7`；`gate-mut-mw3-wait-since-earlier-d6f50a56e5b74d918f4fb74c677ae9bc`；`gate-mut-mf1-throwaway-failure-list-73e9112042554433b234cc56cac805da` | 都只有目標 witness FAIL（見上表） |
| run-all | `df66619`，工作樹乾淨（`fix2/run-all-1-state.txt`；`STATUS` 是空的；`fix2/run-all-1-console.log`） | `run-all-a0e290094ced46118258c1d83a0773d5`（Full：`gate-full-2bc4c275866c4b739c516ea3f858e94b`；M5：`gate-m5-gametest-7f2736ac722a48959c2b4e87be07f4b1`；M3：`gate-m3-3872df23e18e4705a3cc0d824245a703`） | `END ALL exit=0 HEAD=df66619b4b216363d1d99afe6b041031dd17ceff`，約 15 分鐘（20:25:51→20:40:35）。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=13/13 perTest=51+1 junit=611 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=383entries/336classes sources=214 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`。default 與 legacy 的 boundary oracle 都是 PASS；M5 oracle PASS，`M5_UNIVERSE_SUITE_END records=12` |

- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同（`adapter.py restore` 核對）。每個 commit 的 blob 都與它之前最後一輪 GREEN 的 state 相同（`fix2/commit-m8-blobs.txt`、`commit-b-blobs.txt`、`commit-c-blobs.txt`、`commit-d-blobs.txt`）。各 commit 的 focused：`abb7ddf` 見 m-8 GREEN；`0cdc951` 見 GREEN-B；`72e4d8c` 見 GREEN-C（只改 CS，沒有動 lifecycle，不跑 M3）；`df66619` 見 GREEN-D。
- 與 `ff4fc55` 的 run-all（`run-all-521b3824c2bd4c2386e935e0abbc4353`）逐項比較：
  - JUnit 603 → 611（＋8：DRT 20 → 24、CAT 33 → 35、CST 12 → 14）；M5 12 → 13（G-SEALPEND）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 不變。
  - release JAR 382 → 383 entries（335 → 336 classes，`fix2/jarcmp-release-run-all-1.txt`）：新增 `CollapseAuthorities$WriteDirection`、`DestinationResolver$Activity`，移除 `DestinationResolver$ServerCatalog`。以 `javap -c -p` 去掉行號之後內容不同的是 `CollapseAuthorities`、`CollapseAuthorities$WriteResult`（WAIT 的例外訊息改為涵蓋新的 API）、`CollapseService`、`CollapseService$Track`、`DestinationResolver`、`DestinationResolver$Lifecycle`、`DestinationResolver$ServerLifecycle`、`UniverseLifecycleService`；其餘只差行號。sources 214 不變，內容不同的是 `CollapseAuthorities.java`、`CollapseService.java`、`DestinationResolver.java`、`UniverseLifecycleService.java`。testmod 命中仍是 0。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`fix2/wbcmp.py`）：M5 run WARN 11 → 13、ERROR 1 不變（`fix2/wbcmp-m5-gametest-run-all-1.txt`），多出的 2 則都是 G-SEALPEND 刻意造成、由 witness 斷言的：SEAL_WRITE 的 WARN（`ROLL_FORWARD_PENDING`）與 SEALED(`MATERIALIZE_FAILED`) 的 restart-only WARN。legacy 0／0 不變。Full ERROR 28 不變、WARN 54 → 55（`fix2/wbcmp-full-run-all-1.txt`）：多出的一則是「艙體 <U> 的 session 協調失敗，保留返還保護」，來自 M2 的 TASK4 斷線 IO test（`ChamberControllerBlock.scheduledTick` → `ChamberPowerCoordinator.refresh` → `SuperpositionSessionManager.returnToOrigin` 的 journal flush，被該 test 自己的探針「own DISCONNECT RETURNING journal flush 受控拒絕」拒絕），是否出現取決於 Controller 的 scheduled tick 是否落在探針拒絕的窗口，不經過本輪改動的程式。F4a 之前已出現過 3 次（`run-all-e74bc2927e5f4b29a459cd9f86815fdc`、`run-all-0eef2a87f8dc4871a1f7c6e7f440bb1c`、`run-all-93e36a3b5f8e43b39bb8bbf446c70fcc`，HEAD 分別是 `5307407`、`86e16d4`、`53e923e`），見 `fix2/full-warn-attribution.txt`。不在派工列出的已知偶發清單內，交 controller 判斷是否列入。Full 的 latest.log 沒有任何「目的地已確定」「準備階段尚未實作」「塌縮進入 SEALED」「SEALED 重試」「M5 ALLOCATION_LEDGER」「M5 CATALOG」的行。
- 本輪沒有遇到派工列出的已知偶發，也沒有停服卡住；失敗的 root 都是刻意的 RED 或 mutation。

觀察（未修改）：

1. G-NEW 的 `onlyTheStubWarnAndNoError`（`M5U:274`）以 `capture.matching(ERROR).isEmpty()` 斷言整個 capture 期間沒有 ERROR，沒有依 sid 篩選，與 README「log 斷言」的規則不同。m-7 只列了 G-ALLOCIO 的兩處，本輪沒有改；它斷言的是 0 則，F4b 之後若同一個 capture 期間有別的 ERROR 會誤判。
2. lifecycle 的 `FAILED_BEFORE` 後盾（spec re-review 的觀察）在 production 仍走不到：本 instance 物化失敗過的 UniverseId 已記入 `materializeSpent`（`DR:178`），catalog 這筆是 `DISABLED`，下一次 RESOLVE 在 `DR:165` 就先 `MATERIALIZE_FAILED`。m-1 之後，之後的 task 再取 resolver 也看得到同一份額度。

測試：

- JUnit 603 → 611：DRT 20 → 24（m-2 兩個、m-3、m-4），CAT 33 → 35（`catalogRecord…`、`catalogAvailabilityChange…`），CST 12 → 14（m-5(b)、m-6）；`GT` 2、`ULMF` 6 不變。DRT 另有 3 個既有 test 只把收斂時點改為退避期過後（m-2）。
- M5 GameTest 12 → 13：G-SEALPEND ＋1，一個 batch（`m5_universe_sealed_pending`），名稱不以 `_windows` 結尾（各平台都執行）。catalog 增量 1（物化失敗、改為 `DISABLED` 的那一筆），suite 結束時 11 → 12 筆；`M3_UNIVERSE_STOPPED_VERIFIED` 仍是 worlds 6（G-SEALPEND 沒有建立 world）。G-MATFAIL、G-WAIT、G-ALLOCIO 只增減 witness。
- default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 都不變：本輪沒有新增或刪除 default run 的 `@GameTest`。

## F4b-1：投影元件（寫入器、保護索引、保護、載入辨識）與 F4a 的 Step 0

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `4e6b75a` `fix: require the catalog availability write direction to follow its target value`（parent `5bafb25`）：Step 0 的 Q2-m1、Q2-m5。`CA`、`CAT`。
  - `f2b8f34` `fix: keep authority failures and the spent quota exact in the destination resolver`（parent `4e6b75a`）：Q2-m2、S2-mA。`DR`、`DRT`。
  - `544cd37` `refactor: route collapse side-effect warnings through one composition and report the real seal retry delay`（parent `f2b8f34`）：Q2-m3、Q2-m4、S2-mC。`CS`、`CST`。
  - `4f962ca` `feat: list confirmed projections and report authority health for the protection index`（parent `544cd37`）：`CA` 的唯讀 API、`CAT`。
  - `f809c98` `feat: write the destination projection with vanilla setblock semantics`（parent `4f962ca`）：新增 `collapse/ProjectionWriter.java`；testmod `M5GameTestSupport.java`（投影 helper）、`M5UniverseGameTests.java`（W1、W2）。
  - `802342f` `feat: build the projection protection index at server start`（parent `f809c98`）：新增 `collapse/ProjectionProtectionIndex.java`、JUnit `ProjectionProtectionIndexTest.java`；`QuantumSuperpositionMod.java`（只有註冊）。
  - `92881dd` `feat: protect projection invariant cells in dynamic universes`（parent `802342f`）：`ChamberProtectionService.java`；testmod `M5GameTestSupport.java`（`blockTicking`）、`M5UniverseGameTests.java`（GT-P）。
  - `8c0d670` `feat: recognize projection controllers in dynamic universes by the index`（parent `92881dd`）：`ChamberPowerCoordinator.java`（只有 `refreshNow` 的分流）；testmod `M5UniverseGameTests.java`（GT-L）。
  - `340a953` `test: judge projection pistons and fluids after their controls settle`（parent `8c0d670`）：只有 GT-P（見「Gate 與證據」的 P-FLAKE）。
  - `515fbc4` `docs: describe the F4b-1 projection GameTest helpers`（parent `340a953`）：`scripts/verification/README.md`「新增 M5 GameTest」的一點。
  - 本段由其後的 docs commit `docs: record F4b-1 projection components in the M5 note` 加入。
- Code range `5bafb25..340a953`。沒有改 `CollapseService` 的流程（PREPARE stub 照舊；Step 0 的 CS 項目除外）、`ChamberControllerBlock`、`QuantumBulkheadBlock`、`WorldChamberDoorAdapter`、`ChamberControllerLoadSyncQueue`、`SuperpositionSessionManager`、`SessionRecoveryState`、`UniverseRegistryState`、`ChamberRegistryState`、`ChamberDoorService`、語言檔、`gates.ps1`、`build.gradle`、`.github/`；沒有新增 mixin。
- Review：spec review ❌（Critical 0、Important 1、Minor 3：I-1、M-1～M-3）。三項「需要裁示」與「偏離」第 3 條，reviewer 都判定符合 spec，controller 正式接受（見「裁示」）。處置見本節末的「Fix round 1」。
- 最終 review：
  - fix round 1 的 spec re-review ✅（Critical 0、Important 0、Minor 0）。
  - quality review（範圍 `5bafb25..0576b44`）Approved（Critical 0、Important 0、Minor 9：Q-m1～Q-m9），也沒有發現 spec reviewer 漏掉的正確性缺陷。
  - controller 不再開 fix round：Q-m1 交給 F4b-3 的 Step 0，其餘交給 F4b-2 的 Step 0，見「交給後續」。
  - **F4b-1 完成**：range `5bafb25..0576b44`，之後是 controller 的 docs commit。
    - code：`4e6b75a`、`f2b8f34`、`544cd37`、`4f962ca`、`f809c98`、`802342f`、`92881dd`、`8c0d670`、`340a953`、`0020bd5`、`a928216`。
    - docs：`515fbc4`、`c7bbde7`、`0576b44`。
    - 最終 run-all：`run-all-7bab6785434347c4ab75c5d21a34c4b0`，`END ALL exit=0`（`a928216`）。數字如下：

      | 項目 | 數值 |
      | --- | --- |
      | default | 174/174 |
      | M5 | 17/17 |
      | JUnit | 626 |
      | release／sources | 393／346、216 |
      | Full WARN／ERROR | 54／28 |
      | M5 WARN／ERROR | 17／1 |
- 範圍是 plan Task F4b 拆分後的 F4b-1（plan「拆分」一段）。這些元件在 production 中，除了開服建立索引、保護查詢、載入辨識之外，還沒有呼叫端；寫入器只由測試呼叫。PREPARE（預載、PENDING→寫入→checkpoint→COMPLETE、§6.5 交叉檢查、§9.2 衝突、退化時進 SEALED、量子膜、門的攔截）屬 F4b-2。
- 行號以 `340a953` 為準。`PW` 指 `collapse/ProjectionWriter.java`，`PPI` 指 `collapse/ProjectionProtectionIndex.java`，`PPIT` 指 `ProjectionProtectionIndexTest`，`CPS` 指 `chamber/ChamberProtectionService.java`，`CPC` 指 `chamber/ChamberPowerCoordinator.java`；`CA`、`CAT`、`DR`、`DRT`、`CS`、`CST`、`M5U`、`SUP` 同前面各節。M5U 新增的四個 test：
  - W1 `M5U:835` `projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates`（`m5_universe_projection_first_write`）
  - W2 `M5U:931` `projection_writer_waits_for_players_and_repairs_only_the_invariant_cells`（`m5_universe_projection_repair`）
  - GT-P `M5U:1021` `projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids`（`m5_universe_projection_protection`）
  - GT-L `M5U:1160` `projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added`（`m5_universe_projection_recognition`）
  - 四個都由 `M5U` 的 `resolvedUniverse` 取得宇宙 U：原艙上的 DORMANT NEW 收據、P 由艙外進入、真的 RESOLVE 物化 U，之後停在 PREPARE stub 的 WAITING。catalog 增量各 1。

### Step 0（F4a 最終 review 的 Minor）

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q2-m1 | 二擇一選「組合不符時丟 `IllegalArgumentException`」：拿掉參數要改 `DR` 的兩個呼叫端，而 `DR` 只為 Q2-m2、S2-mA 列入允許路徑。`CA:739-742` 前提檢查（fix round 1 更正，M-3：首輪寫成 `CA:735`，那是方法宣告）、`CA:775` `directionFor`（DISABLED→ROLL_FORWARD、EAGER_ENABLED→RESTORE），Javadoc 同步。`CAT:1010` 改為 RESTORE | RED `red-0`：CAT `catalogAvailabilityDirectionMustFollowTheTargetValue`（`CAT:1147`，兩種不符的組合都丟、兩種相符的照寫）FAIL |
| Q2-m2 | `DR:215` `materializeFailed`：`status != WRITE_FAILED` 時 `return failed(disabled)`（`DR:218`），刪掉 dead code `orElseGet` | RED `red-0`：DRT `materializeFailureWithAnUnhealthyCatalogSealsTheAuthorityReasonWithoutTheDisableError`（`DRT:356`）FAIL（修改前封存為 `MATERIALIZE_FAILED` 並記「DISABLED 的寫出未確認」ERROR） |
| S2-mA | 二擇一選「呼叫前先記、回 WAIT 時移除」：`DR:196` `reenable` 在 `changeCatalogAvailability` 之前 `materializeSpent.add`（`DR:197`），WAIT 時 `remove`（`DR:200`），Javadoc 同步 | RED `red-0`：DRT `aReenableThatThrowsAfterTheRestoreStillSpendsTheQuota`（`DRT:379`，restore 寫入之後丟出的例外也算用掉額度）FAIL |
| Q2-m3 | 組合抽成 static `CS:884` `sideEffect(warnings, ledger, sid, what, next, action)`，instance 版委派給它；WARN 的格式抽成 static `CS:847` `logWarn` | CST `sideEffectWarningKeysAreClearedOnSuccess`（`CST:366`）改為經由 production 的 static 驗證。mutation `mut-q2m3-composition-always-warns`（組合一律 WARN）：新 test FAIL（`mut-0-q2m3-new-test`），舊 test 存活（`mut-0-q2m3-head-test`，證明修改前的 test 沒有經過 production 的組合） |
| Q2-m4 | `Track.backoff` 的 Javadoc 補「進入 SEALED 的寫入被拒」；WARN 的重試說明改用 `CS:947` `Track.sealRetryNext()`（實際的 `delayTicks` 與 `retryAtTick`，`CS:447`）；`sealedLog` 改用 `firstWarning`（`CS:476`） | RED `red-0`：CST `rejectedSealWriteIsRetriedOnlyOutOfThePerSessionBackoff`（`CST:334`，斷言「200 ticks 後，tick 1200」與第二次的「400 ticks 後，tick 1600」）FAIL。`firstWarning` 的 mutation `mut-firstwarning-always`（不去重）：`sideEffect…` FAIL（`mut-0-firstwarning`）；`sealedLog` 的去重另由 G-INELIGIBLE 的 `errorStillOnce`（`M5U:404`，再次進入之後永久原因的 ERROR 仍只有一則）涵蓋（`green-0-m5` 13/13） |
| S2-mC | 二擇一選「縮小 Javadoc」：`warn` 的 Javadoc 寫明 SEAL_WRITE 在 `ROLL_FORWARD_PENDING` 之後由 SRM 的 RETRY_FLUSH 確認時，CS 不移除這個鍵 | 只有 Javadoc |
| Q2-m5 | `CA` 類別 Javadoc「寫入」一點補上：`catalogRecord`、`allocateOrVerifyCatalog` 與 RESTORE 方向在未確認時回 WAIT；ROLL_FORWARD 在未確認時照寫 | 只有 Javadoc |

- `red-0` 一次套用四個 RED adapter（`red-q2m1-no-direction-check`、`red-q2m2-corrupt-as-unconfirmed`、`red-s2ma-spent-after-call`、`red-q2m4-fixed-text`），恰好這四個 test FAIL，都在目標斷言（`f4b1/red-0-failure-types.txt`：`AssertionFailedError`；Q2-m2 的訊息帶的 `IllegalStateException` 是 fixture 注入、被斷言檢查的 root cause）。
- GREEN：`green-0a`、`green-0b`（CAT、DRT、CST、`GT`，逐 commit 取證）、`green-0-junit` 78、M5 `green-0-m5` 13/13、default focused `green-0-focused` 21/21（F4a `green-d-focused` 的 21 個 batch，`f4b1/focused-batches.txt`）。

### plan Step 1 中屬於 F4b-1 的 RED

| plan Step 1 | 程式位置 | 測試 |
| --- | --- | --- |
| 第 2 組：第一次寫入清 interior、箱子不噴物品、逐格驗證 | `PW:118` `write(…, FIRST_WRITE)`：目標 343 格（interior AIR、shell bedrock、`local(3,6,0)` 是 `FACING`＝outward 的 Controller、門 25 格 `open=true`／`membrane=none`）；寫入前 `Clearable.clear`，flags `PW:48` `NOTIFY_LISTENERS \| FORCE_STATE \| SKIP_DROPS`；寫完逐格驗證 | W1：`written`、`everyCellIsTheSpecTarget`（test 依 spec 自己寫出目標，`SUP:648` `projectionTarget`）、`controllerHasADefaultBlockEntity`、`chestsDroppedNothing`、`nothingDroppedLater`、`observerNotScheduled`／`observerNeverPulsed`（看著 shell 的觀察者：沒有 `FORCE_STATE` 就會排程）、`floatingWireStays`／`wireStillThere`（懸空在 shell 旁的紅石線：收到鄰格更新就掉落）。RED `red-4b-writer-naive-w1`（直接 `setBlockState` flags 3、不 clear、不擋玩家）W1 FAIL；mutation 都 FAIL：`mut-4b-no-clear`（箱子的物品掉出）、`mut-4b-neighbors`（加 `NOTIFY_NEIGHBORS`：紅石線掉落，連帶有掉落物）、`mut-4b-no-force-state`（觀察者排程） |
| 第 2 組：之後的寫入只修不變量 cell、保留 interior；逐格驗證 | `PW:118` `write(…, REPAIR)`：只寫 218 個不變量 cell 中與目標不同的格；驗證失敗 → `VERIFY_FAILED`（failure 列出至多 8 格） | W2：`repairWritesExactlyTheDamagedInvariantCells`（恰好 27 格：2 格 shell＋25 格門）、`invariantCellsRestoredDoorOpen`、`interiorKept`、`verifyFailedAboveTheBuildLimit`、`notLoadedWithoutLoading`。mutation `mut-4-repair-writes-interior` FAIL。「checkpoint 之後才 COMPLETE」與「刪除 ledger 之後不重寫 interior」屬 F4b-2 |
| 第 5 組：退化只依權威健康 | `PPI:256` `syncHealth` 只看 `CA:536` `unhealthy(PROJECTION_LEDGER)`（初始化結果與執行期的 `lost`） | PPIT `onlyAuthorityHealthDegradesNotAFullLedgerOrASingleMismatch`（`PPIT:188`，真的 CA：16384 筆已滿的 ledger、單筆 site 不符的 `CORRUPT` 都不退化；之後執行期遺失才退化）、`runtimeLossDegradesAtTheNextSyncKeepsKnownProjectionsAndNeverRecovers`（`:160`）、`startupDegradationIgnoresTheLedgerAndProtectsRegisteredOverworldOriginsInEveryDynamicOverworld`（`:130`）；CAT `healthOnlyFollowsTheInitializationResultAndRuntimeLoss`（`CAT:656`）。mutation `mut-ca-single-corrupt-marks-lost`（單筆 `CORRUPT` 就標為 lost）→ CAT 3 FAIL |
| 第 6 組：要寫入的 cell 有玩家時不寫入；動物照常寫入、不移除 | `PW:118`：非旁觀玩家的 bbox 與檢查範圍相交 → `BLOCKED_BY_PLAYER`（帶玩家），一格都不寫；FIRST_WRITE 的檢查範圍是整個 7×7×7，REPAIR 只看這次要寫的格；非玩家 entity 不檢查、不移動 | W2：`firstWriteBlockedByQ`、`nothingWrittenWhileBlocked`（343 格都沒變）、`firstWriteAfterQLeft`、`interiorPlayerNotMoved`、`repairBlockedByAPlayerInADamagedCell`、`doorwayPlayerDoesNotBlockTheRepair`。W1：`pigNotMovedOrRemoved`、`pigStillThere`、`spectatorNotMoved`（旁觀玩家不擋）。mutation `mut-4-first-write-checks-pending-only`、`mut-4-repair-checks-every-invariant-cell`、`mut-4b-spectators-block`、`mut-4b-entities-block` 都 FAIL |
| 第 8 組：保護（破壞、放置、爆炸、活塞、流體） | `CPS:65`：非 vanilla 的 world key 查 `PPI:152` `protectedCell` | GT-P（下一小節的「保護」）。RED `red-8-protection-no-index`（`mayMutate` 改回 `orElse(true)`）FAIL |
| 第 8 組：退化規則 | `PPI:231` `protects` 的退化分支 | PPIT 上列三個退化 test、`unexpectedFailuresFailClosedWithTheDegradationRule`（`:234`） |
| 第 8 組：Controller 載入辨識為 inert PROJECTION（比較器 0、不 `registerOrigin`、不重複 log），涵蓋 chunk 載入與 `onBlockAdded` 兩條路徑 | `CPC:57-67` | GT-L。RED `red-7-recognition-no-branch` FAIL；mutation `mut-7-kind-criterion`、`mut-7-any-dynamic`、`mut-7-controllers-complete-only` 都 FAIL |

### controller 補充第 1–8 點

- **第 1 點（寫入器的結果）**：`PW` 的 `Status`：`WRITTEN`（寫完而且逐格驗證通過）、`BLOCKED_BY_PLAYER`（被玩家擋住，一格都沒寫）、`NOT_LOADED`（volume 的 chunk 不是都 FULL，不讀不寫、不載入）、`VERIFY_FAILED`（寫了、驗證不符）、`WRITE_FAILED`（寫入中丟出例外）。`Result(status, written, mismatched, blockingPlayers, failure)` 的不變式在建構時檢查（只有 `VERIFY_FAILED`／`WRITE_FAILED` 帶 failure，只有 `BLOCKED_BY_PLAYER` 帶玩家）。`WRITE_FAILED` 沒有 GameTest（要 fault mixin，屬 F4b-2）。
- **第 2 點（兩種模式）**：第一次寫入 343 格、interior 一律 AIR；修復只寫不變量 cell，門回到開啟。上表第 2 組。
- **第 3 點（方塊與 block entity）**：shell 是 bedrock；Controller 是 `FACING`＝outward 的預設 block entity，不複製身分（第一次寫入一律重寫 Controller 格，`chamberUuid` 為 null，驗證也檢查）；門 `open=true`、`membrane=none`。
- **第 4 點（寫法）**：`authorizedMutation` 之內，對既有的 block entity `Clearable.clear`，再以 `PW:48` 的 flags 寫入（比照原版 `/setblock`）。W1 證明箱子不噴物品、鄰格不觸發物理（上表）。
- **第 5 點（玩家與 entity）**：上表第 6 組。
- **第 6 點（索引）**：
  - 註冊：`QuantumSuperpositionMod:56`，在 `CollapseService.initialize()` 之後、`UniverseLifecycleService.initialize()` 之前；所以它的 `SERVER_STARTED` 晚於 `CollapseAuthorities.registerEvents()` 的初始化、早於 lifecycle 的 bootstrap 物化（Fabric 依註冊順序執行，決策 2）。`PPI:92` `registerEvents`：`SERVER_STARTED` 建立、`END_SERVER_TICK` 同步健康（不丟例外）、`SERVER_STOPPED` 釋放。
  - 資料：`CA:516` `confirmedProjections()`，加上 flushed catalog 的 `UniverseId → worldKey`（catalog 沒有這筆時用 M3 的推導 key，只有 OVERWORLD）。結構是不可變的快照：`worldKey → chunk → 投影`，另有 Controller 位置的集合；更新時整份換新（volatile）。
  - 熱路徑只讀記憶體：`PPI:152` `protectedCell`（`mayMutate`）、`PPI:158` `projectionControllerAt`（`refreshNow`）。不讀 PersistentState。
  - 更新入口 `PPI:197` `confirmed(entry)`：下一小節。
  - 退化：只依權威健康切換。開服時不健康 → 不讀 ledger；執行期間 `lost` → 下一個 `END_SERVER_TICK` 退化，已在索引的投影照樣受保護。退化時另保護「chamber registry 中登記的 OVERWORLD Origin 艙，在每個動態 Overworld 同位置的不變量 cell」（`PPI:231-247`；動態 Overworld 以 M3 推導的 key exact 相等判定，`PPI:291`）。同一個 instance 內不解除，下次開服若健康就不退化。非預期的例外 fail closed：ERROR，退化為 `AUTHORITY_UNREADABLE`。
  - F4b-2 的查詢：`PPI:212` `degradation()`（退化時回 SEALED 的原因）、`PPI:220` `intersecting(worldKey, volume)`（§9.2 的衝突：與 volume 相交的已登記投影）。
  - Origin 拆除之後，entry 照樣在索引、照樣受保護：索引不看 chamber registry（退化分支除外）。沒有改 `ChamberRegistryState`；投影不進 chamber registry，也不進 `chamber/ProjectionIndex`。
  - log：開服一則 INFO（COMPLETE／PENDING 筆數、世界數、無法對應世界而略過的筆數）；退化 WARN、fail closed ERROR。都帶 `TICK_LOG_MARKER`（屬於權威，不屬於任何 session）。
- **第 7 點（`CA` 的唯讀 API）**：`CA:516` `confirmedProjections()`：已確認的 entries，依寫入順序；不健康時丟 `IllegalStateException`（呼叫端先查健康）。`CA:536` `unhealthy(authority)`：初始化失敗時回初始化的原因，執行期 `lost` 時回 `AUTHORITY_CORRUPT`，否則為空；不看單次呼叫回的 `CORRUPT`（已滿、單筆不符）。CATALOG 不是 M5 的 ledger，丟 `IllegalArgumentException`。既有 API 的行為不變（Step 0 的 Q2-m1、Q2-m5 除外）。CAT `confirmedProjectionsListOnlyCheckedEntriesInWriteOrder`（`CAT:621`）、`healthOnlyFollowsTheInitializationResultAndRuntimeLoss`（`CAT:656`），以及既有 `initializationFailures…` 與 `fullProjectionLedger…` 補的斷言。RED `red-1-ca-api`（stub）3 FAIL。
- **第 8 點（保護與載入辨識）**：下兩小節。

### 更新入口的設計（「裁示」第 1 條，fix round 1 已接受）

- `PPI:197` `confirmed(entry)`：F4b-2 每次 checked 寫入 projection entry 得到確認的 entry 之後呼叫（`WRITTEN`、`UNCHANGED`，或 `ALREADY_COMPLETE` 帶回的既有 entry）。
  - PENDING 確認之後、寫方塊之前登記：只供辨識（Controller 位置進集合），**不保護**。
  - COMPLETE 確認之後再呼叫：這座投影的不變量 cell 開始受保護。
  - Status 只前進：已登記 COMPLETE 時再登記 PENDING 不倒退。site（Role、ControllerPos、OutwardFacing）與已登記的不同、或對應不到世界 → `IllegalArgumentException`（ledger 已保證這兩者，違反是 caller 錯誤）。
- 為什麼這樣同時滿足兩者：spec §9.3「每次 COMPLETE 時更新這個索引」對**保護**成立：只有 COMPLETE 的不變量 cell 受保護。plan 第 8 組要求投影寫入時 `onBlockAdded` 觸發的 refresh 辨識為 PROJECTION；第一次寫入發生在 PENDING 確認之後、COMPLETE 之前，所以 Controller 位置必須在 PENDING 確認時就登記。GT-L 的 A 與 B 都在 PENDING 期間被辨識。
- 開服時 PENDING entry 也入索引，但只供辨識：PENDING 表示方塊可能已寫了一部分（寫入與 COMPLETE 之間 crash）。重啟之後那座 Controller 載入時辨識為 inert PROJECTION，F4b-2 的 PREPARE 再以第一次寫入或修復處理；依 §9.3 字面不保護。PPIT `startupIndexProtectsOnlyTheInvariantCellsOfCompleteProjectionsAndRecognizesEveryController`（`PPIT:59`）、`updateEntryRecognizesAConfirmedPendingFirstAndProtectsOnlyOnceComplete`（`:88`）；mutation `mut-index-pending-protected`、`mut-index-status-regresses`、`mut-index-controllers-complete-only` 都 FAIL。

### 保護（補充第 8 點、派工第 4 點）

- 呼叫點：既有 Origin 的保護全部經 `WorldSetBlockStateMixin`（`World.setBlockState` 的開頭呼叫 `CPS.mayMutate`，不分世界）。玩家破壞（`tryBreakBlock` → `removeBlock`）、放置（`BlockItem.place` → `setBlockState`）、爆炸（`onExploded` → `setBlockState`）、活塞（`PistonBlock.move` 的 `setBlockState`；shell、門與 Controller 本身也是推不動的方塊）、流體（`FlowableFluid` 的 `setBlockState`）都經過它。動態世界也走同一個 `mayMutate`，不需要新的 mixin。另有 `ChamberMaintenanceInteraction`（AttackBlockCallback）在動態世界拒絕拆 Controller，與索引無關。
- `CPS:65`：vanilla 的 world key 照舊查 chamber registry；非 vanilla 的 world key 改為 `!protectedCell(...)`（修改前一律放行）。interior 與 volume 外放行。
- GT-P 依 F4b-2 的順序：checked 寫入 PENDING 並登記（`pendingNotProtected`）、第一次寫入、checked 寫入 COMPLETE 並登記。之後：
  - `mayMutate`：218 個不變量 cell 全部拒絕、125 格 interior 與 volume 外放行。
  - 門：玩家以門板（既有的 `onUse` → 整面門的授權寫入）把門關上再打開。
  - 破壞：創造模式的玩家（原版 `tryBreakBlock`）打 shell、Controller、門板都不消失；interior 的石頭照常打掉。
  - 放置：授權寫入把一格 shell 損壞成 AIR 之後，玩家對它放石頭被拒；interior 照常放得下。
  - 爆炸：shell 的玻璃留著，interior 的玻璃消失。
  - 活塞：三台通電的活塞對著 shell、門板與 Controller，都推不動；對照組的活塞照常推動石頭。
  - 流體：損壞成 AIR 的 shell 旁放水源（南北擋住，只能往西與往東，兩邊離落差一樣近）：水流不進 shell；對照組的空格有水。
  - 新 chunk 要等鄰居非同步產生才開始 block ticking，所以先以 `SUP:640` `blockTicking` 等投影周圍強制載入的 chunk；活塞與流體的判定等對照組真的發生（`340a953`，見 P-FLAKE）。
  - RED `red-8-protection-no-index`：`everyInvariantCellDenied`、`breakingRefused`、`placingIntoAnInvariantCellRefused`、`explosionKeepsTheInvariantCell`、`fluidCannotEnterAnInvariantCell` 都是 false。`pistonsCannotMoveInvariantCells` 也是 false，但那是因為 RED 中破壞沒有被擋、門板被打掉；活塞本身推不動不變量 cell（方塊設定），這一條的牙齒是對照組的活塞（「偏離」第 4 條）。

### 載入辨識（補充第 8 點、派工第 5 點）

- `CPC:57-67`：`role.isEmpty()`（動態世界）而且 `projectionControllerAt` 為真 → 把 block entity 的 instanceKind 寫回 PROJECTION（不同時才寫並 `markDirty`）、`changeState(INVALID)`、return。插在原本的 foreign 分支（`CPC:68`）之前，只動 plan 允許的 `:54-58` 分流；不在索引中的照舊走 foreign。不記 log。
- 判定依據是索引，不是 block entity 的 instanceKind（§9.3「依索引判定」）。mutation `mut-7-kind-criterion`（判定改看 instanceKind）→ A、B 都沒有辨識，FAIL。
- 寫回 instanceKind 的理由（「裁示」第 2 條，fix round 1 已接受）：foreign 分支同樣是 INVALID、比較器 0、不註冊、不啟動，只靠 INVALID 的話，辨識沒有任何可觀察的結果，兩條路徑的 test 也無從區分辨識與 foreign。PROJECTION 是既有的 enum 值：M1 走廊投影的 Controller 已經這樣標記（`SessionEntranceAllocator:39`，`CorridorPageManager:505` 依它判斷），ORIGIN 專屬的守門（發光、維護手勢、`ChamberActivationService`、session 的 controller 檢查）因此也一併不適用。
- 兩條路徑都在 GT-L：
  - `onBlockAdded`：A 寫入之後立刻 `ChamberLoadSyncTestAccess.discardWorld(U)` 丟掉 load-sync（收據 `DISCARDED_WORLD_UNLOAD`）；寫入後 4 tick 還沒有 refresh 過（`powerInitialized=false`、ORIGIN），25 tick 時已由 `onBlockAdded` 排的 refresh 判定為 PROJECTION（`aRecognizedByBlockAdded`）。
  - load-sync：B 在 PENDING 登記一 tick 之後才寫入，收據 `REQUEUED_NOT_DUE, CONSUMED`，寫入後 3 tick（早於它自己 20 tick 後的排程 refresh）已是 PROJECTION（`bRecognizedByLoadSync`）。chunk 從磁碟載入時的 `BLOCK_ENTITY_LOAD` 走同一個 queue（「偏離」第 3 條）。
  - foreign：C 不在索引裡，直接放置，照舊是 ORIGIN、INVALID、比較器 0（`cForeignAfterLoadSync`、`cStillForeign`）。mutation `mut-7-any-dynamic`（動態世界一律當成投影）→ C 被當成 PROJECTION，FAIL。
  - inert：A、B 轉為 COMPLETE 之後在 Controller 上方放紅石塊供電，45 tick 後已讀到供電（`wasPowered`），仍是 PROJECTION、INVALID、比較器 0、沒有 chamber UUID（`poweredButInert`）；chamber registry 沒有 U 的紀錄（`noRegistryRecordInU`）、journal 沒有來源在 U 的 session（`noSessionFromU`）；整段期間 `quantumchamber` logger 除了塌縮 session 本身的 log 之外沒有 WARN／ERROR，也沒有任何 log 提到三座 Controller 的位置（`noWarnOrErrorOutsideTheSession`、`noLogAboutTheControllers`）。
- 沒有改 `ChamberControllerLoadSyncQueue`，不需要 spec §17 的 queue observer regression。

### default run 的稽核（派工第 8 點）

| 新分支 | default run 中的行為 |
| --- | --- |
| 開服建立索引 | default、legacy、main-only、M5 run 開服各一則 INFO「M5 投影保護索引已建立：COMPLETE 0 筆…0 個世界」，沒有退化 WARN、fail closed ERROR。索引只讀 ledger 與 catalog，不寫任何權威：Full 的 boundary oracle PASS（catalog 快照與 world keys 不變） |
| `CPS:65` | 只在非 vanilla 的 world key 生效（default run 中是走廊世界與 M3 fixture 的動態世界）。索引沒有 entry、也沒有退化，`protectedCell` 一律 false，與修改前的 `orElse(true)` 相同 |
| `CPC:57` | 只在動態世界、而且索引有這個位置時生效；default run 的索引沒有 entry，一律走原本的分支。走廊投影的 Controller（PROJECTION）照舊走 foreign 分支 |

- 實證：Full 的 default 174/174；Full latest.log 的 WARN／ERROR 54／28 不變，正規化排序之後與基準逐行相同（`f4b1/wbcmp-full-run-all-final.txt`）；legacy 0／0 不變。

### 測試

- JUnit 611 → 626（＋15）：CAT 35 → 38（Q2-m1 一個、API 兩個）、DRT 24 → 26（Q2-m2、S2-mA）、PPIT 0 → 10（新增）、CST 14 不變（Q2-m3 改寫一個、Q2-m4 補斷言）；`GT` 2 不變（`PW`、`PPI` 都在 collapse package，log 都帶 marker）。
- M5 GameTest 13 → 17：W1、W2、GT-P、GT-L，各自一個 `m5_universe_projection_*` batch，名稱不以 `_windows` 結尾（各平台都執行），catalog 增量各 1；suite 結束時 12 → 16 筆。
- default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 都不變：沒有新增或刪除 default run 的 `@GameTest`。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b1/`（以下簡稱 `f4b1/`）：`state.ps1`（記 HEAD、porcelain 與逐檔 blob）、`gate.ps1`（以 `onlyBatches` 跑 `gates.ps1`；2026-10-06 補上「XML 早於本次 gate 開始就標為 STALE、不採計」）、`junit.ps1`、`adapted-junit.ps1`、`chain.ps1`（依 plan JSON 逐步套用 adapter、跑 gate、還原並核對 blob）。暫時性改動由 `f4b1/adapter.py` 套用與還原（套用前保存原檔，還原後以 `git hash-object` 核對），各 adapter 的 diff 在 `f4b1/adapters/<名稱>/`。gate root 在 `.superpowers/verification/`。

| 輪次 | HEAD／工作樹 | 證據 | 結果 |
| --- | --- | --- | --- |
| Step 0 RED／mutation | `5bafb25`＋Step 0 的檔 | `f4b1/red-0-*`、`mut-0-*` | 見 Step 0 表 |
| Step 0 GREEN | 同上 | `green-0a`、`green-0b`、`green-0-junit`；`green-0-m5`、`green-0-focused` | JUnit 76／78／78 PASS；M5 13/13；focused 21/21 |
| CA API | `544cd37`＋API | `red-1-ca-api`、`mut-1-single-corrupt`、`green-1-ca-api` | RED 3 FAIL；mutation 3 FAIL；GREEN 80 PASS |
| 寫入器 | `4f962ca`＋`PW`、testmod（`hide-index` 讓工作樹等於該 commit） | `red-4-writer-naive`、`red-4b-writer-naive-w1`、`mut-4-*`、`mut-4b-*`、`green-4w-quick`、`green-4-writer-m5`、`green-4b-writer-m5`、`green-2-writer-junit` | RED W1、W2 FAIL；mutation 見上表（`mut-4-entities-block` 存活 → W1 改為等 entity storage 載入之後再寫，`mut-4b-entities-block` FAIL）；GREEN M5 15/15 |
| 索引 | `4f962ca`＋`PPI`、PPIT、註冊 | `red-3-index`、`mut-3-*`、`green-3-index`、`green-3b-index`、`green-5-index-m5`、`green-5-index-focused` | RED 6 FAIL；mutation 見下；GREEN JUnit 95 PASS、M5 15/15、focused 21/21 |
| 保護 | `802342f`＋`CPS`、GT-P | `green-6bq-protection`、`red-6b-protection-no-index`、`green-6b-protection-m5`、`green-6b-protection-default` | GREEN 1/1；RED FAIL（上一小節）；M5 16/16；default 174/174 |
| 載入辨識 | `92881dd`＋`CPC`、GT-L（＝`8c0d670` 的 blob） | `green-7q-recognition`、`red-7-recognition-no-branch`、`mut-7-*`、`green-7-recognition-default`、`green-7-recognition-m5` | GREEN 1/1；RED 與 3 個 mutation FAIL；default 174/174；M5 16/17（GT-P 的 P-FLAKE） |
| P-FLAKE 修正 | `8c0d670`＋GT-P | `green-8q-protection-1..3`、`red-8-protection-no-index`、`green-8-protection-m5-1..3` | GREEN 3 次、M5 17/17 三次；RED FAIL。對照組的 settle 是 5、5、12、17、5、11 tick（RED 13） |
| run-all | `515fbc4`，工作樹乾淨（`STATUS` 是空的） | `run-all-28d70ca9adf14ccdbb63acd8958c576b`（Full：`gate-full-a6b7c3fc51be40f4be2393dd5a036494`；M5：`gate-m5-gametest-58b7944b20ec46ea9f6e21b136377ac8`；`f4b1/run-all-final-console.txt`） | `END ALL exit=0 HEAD=515fbc4`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=17/17 perTest=51+1 junit=626 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=393entries/346classes sources=216 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- 本節的 mutation 與 RED 以 adapter 名稱稱呼（`mut-index-*`、`mut-w-*`、`mut-cpc-*`…），evidence 的 root 以輪次命名（`mut-3-*`、`mut-4-*`、`mut-7-*`…）；兩者的對照表見「Fix round 1」的 M-3。
- 索引的 mutation：`mut-index-intersect-all`、`mut-index-no-startup-info`、`mut-index-no-exact-key`、`mut-index-onshell-includes-interior`、`mut-index-pending-protected`、`mut-index-status-regresses` 都 FAIL。`mut-index-any-namespace`（拿掉 namespace 檢查）存活：它與之後的 exact key 相等重複，所以刪掉重複的檢查（`PPI:291`），改對 exact 相等做 mutation（`mut-index-no-exact-key` FAIL）。
- 每個 adapter 跑完都還原，還原後的 blob 與套用前相同。每個 commit 的 blob 都與它之前最後一輪 GREEN 的 state 相同（`f4b1/commit-*-blobs.txt`）。`8c0d670` 的 `M5U` 是「HEAD＋GT-L」的 blob `5b0022a`（`f4b1/m5u-recognition-commit.java`，以 `git update-index --cacheinfo` stage），等於 `green-7q-recognition` 等各輪 state 記錄的 blob；工作樹當時已另有 GT-P 的修正，留給 `340a953`。
- P-FLAKE：GT-P 首版在佈置後固定 20 tick 判定，`green-6q-protection`（還沒有等 block ticking）與 `green-7-recognition-m5`（已等 block ticking）兩次是對照組沒有發生（`controlPistonPushes`、`controlFluidFlows` 都是 false，保護的 witness 都成立）。`340a953` 改成以有界等待（200 tick）等對照組的活塞推動完成、水源流動過再判定，並記錄 settle 的 tick。之後各輪是 5–21 tick（run-all 的 M5 是 21），所以固定 20 tick 本來就不夠；多出的 tick 來自哪裡沒有追。
- 不採計的 root：`green-6-protection-default`：本 implementer 以 `onlyBatches=defaultBatch,…` 想選 default batch 的 test，但 `M4LegacyGameTestFilterMixin` 要求每個指定的 batch 恰好一個 test，`defaultBatch` 有 63 個，GameTest 在寫出 XML 之前就丟 `IllegalStateException`（`指定 batch 子集不符`）。`gate.ps1` 當時讀到 `result-build` 裡上一次 run 的 XML，摘要誤列 21 PASS；已補 STALE 檢查，改跑完整的 default run（`green-6b-protection-default`、`green-7-recognition-default`）。GT-P 首版的 `green-6q-protection`、`green-6-protection-m5`、`red-6-protection-no-index` 也不採計：首版流體的 fixture 讓水只流向南北兩側的落差，`controlFluidFlows` 恆為 false（RED 中 `fluidCannotEnterAnInvariantCell` 也因此是 true），`92881dd` 之前改為南北擋住、並等 block ticking。`green-7-recognition-m5` 是 P-FLAKE，保留 root。
- 與 `942a33f` 的 run-all（`run-all-9d3a6073d1d84645930b0af986e44af1`）逐項比較：
  - JUnit 611 → 626、M5 13 → 17（「測試」）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24、mainOnly、CI 兩個平台都不變。M5 oracle PASS：17 個 test 的 catalog 增量逐一串接，suite 結束時 16 筆；`M3_UNIVERSE_STOPPED_VERIFIED worlds=10`。
  - release JAR 383 → 393 entries（336 → 346 classes，`f4b1/jarcmp-release-run-all-final.txt`）：只新增 `ProjectionWriter` 與它的 `ControllerPresence`、`Mode`、`Result`、`Status`，以及 `ProjectionProtectionIndex` 與它的 `Indexed`、`Key`、`Snapshot`、`WorldView`；沒有移除。內容不同的 class 都屬於本 task 改過的 6 個 production 檔（`QuantumSuperpositionMod`、`ChamberPowerCoordinator`、`ChamberProtectionService`、`CollapseAuthorities`、`CollapseService`、`DestinationResolver` 與它們的 nested class）。testmod 命中 0。
  - sources JAR 214 → 216（`f4b1/jarcmp-sources-run-all-final.txt`）：新增 `ProjectionWriter.java`、`ProjectionProtectionIndex.java`；內容不同的是同上 6 個檔。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`f4b1/wbcmp.py`）：Full 54／28 不變、逐行相同（`f4b1/wbcmp-full-run-all-final.txt`）；legacy 0／0（`wbcmp-legacy-run-all-final.txt`）。M5 WARN 13 → 16、ERROR 1 不變（`wbcmp-m5-gametest-run-all-final.txt`）：多出的 3 則都是 PREPARE stub 的「準備階段尚未實作（F4b）」，分別屬 W1、GT-P、GT-L 的塌縮 session（它們在 RESOLVE 之後還要幾個 tick 才收尾；W2 在 RESOLVE 的同一個 callback 就收尾，所以沒有）。
  - 新增的 INFO：每次開服一則「M5 投影保護索引已建立」（四個 run 都是 0 筆）。
- 本 task 沒有遇到派工列出的已知偶發，也沒有停服卡住。

### 偏離 plan／spec 字面之處

1. 更新入口（上方小節）：spec §9.3 的字面是「每次 COMPLETE 時更新」；本實作在 PENDING 確認時就登記 Controller 位置供辨識，COMPLETE 時才保護。
2. Q2-m1 選丟 `IllegalArgumentException`，沒有拿掉參數（理由在 Step 0 表）。
3. 「chunk 載入」路徑的 test 以寫入器放置 Controller 觸發的 `BLOCK_ENTITY_LOAD` → load-sync queue 驗證，沒有真的讓 chunk 卸載再載入。chunk 從磁碟載入時是同一個事件與同一個 queue（`QuantumSuperpositionMod:43-48`）。強制載入的 chunk 要卸載，得解除強制載入並等存檔卸載，時序不可控；重啟後載入屬 F6 的重啟矩陣。
4. 活塞：shell、門板與 Controller 本身就推不動（既有的方塊設定），活塞在 `mayMutate` 之前就被擋，關掉索引的 RED 也推不動；`pistonsCannotMoveInvariantCells` 的牙齒是對照組的活塞。首輪沒有測「活塞把方塊推進損壞成 AIR 的不變量 cell」；fix round 1（M-1）已補，見「Fix round 1」：那一格維持 AIR，原版 `PistonBlock.move` 在寫入被拒之後仍對那一格 `addBlockEntity`，記一則原版 WARN，被推的石頭消失、活塞頭留在石頭原本的位置。這與 Origin 的既有保護是同一條路徑。
5. GT-P 的對照組以有界等待判定（P-FLAKE），不是固定 tick 數。

### 裁示

首輪列為「需要裁示」的三項，以及「偏離」第 3 條（chunk 載入路徑以 `BLOCK_ENTITY_LOAD` → load-sync queue 驗證），spec review 都判定符合 spec，controller 正式接受（fix round 1 的派工）：

1. 更新入口的設計（PENDING 確認時登記供辨識、COMPLETE 時保護；開服時 PENDING entry 只供辨識）。
2. 辨識時把 block entity 的 instanceKind 寫回 PROJECTION（判定依據仍是索引；理由見「載入辨識」）。
3. 退化時的辨識：`projectionControllerAt` 只看索引裡的 entries，不含退化分支由 chamber registry 推得的 Origin 位置。開服時就退化，投影的 Controller 走 foreign 分支：同樣 INVALID、比較器 0、不註冊、不啟動，只是 instanceKind 不寫回。若要在退化時也辨識，需要決定「registry 的 Origin 在動態 Overworld 同位置的 Controller」是否一律視為投影。

### 觀察（未修改）

1. 新 chunk 的 entity storage 是非同步載入的：剛強制載入的 chunk 裡生成的 entity，原版的 entity 查詢要等幾個 tick 才看得到（W1 先等豬看得到才寫入）。寫入器以 `world.getPlayers()` 檢查玩家，不受影響；F4b-2 若要以 entity 查詢做其他檢查，要注意這點。
2. 同理，強制載入的 chunk 開始 block ticking 要等鄰居非同步產生；GameTest server 不以 20 TPS 節流，固定 tick 數的等待不可靠（README「F4b-1 的投影 helper」）。
3. `onBlockAdded` 排的 refresh 與排程刻的 refresh 每 20 tick 一次，投影 Controller 每次都走辨識分支（只讀記憶體、不記 log）。
4. W1 的 `written=220`：U 沿用 server 的 Overworld 設定，GameTest server 是 flat 世界，投影的 volume 原本都是 AIR；第一次寫入改的是 218 個不變量 cell，加上 W1 放在 interior 的箱子與石頭兩格。

### 交給後續

- F4b-2：
  - 呼叫順序：`writeProjection(PENDING)` 確認 → `ProjectionProtectionIndex.get(server).confirmed(entry)` → `ProjectionWriter.write(…, FIRST_WRITE 或 REPAIR)` → checkpoint（`ChunkSaveCheckpoint.ready`／`save`）→ `writeProjection(COMPLETE)` 確認 → `confirmed(entry)`。`ALREADY_COMPLETE` 帶回的 entry 也要 `confirmed`（開服時已在索引，呼叫是 no-op）。`confirmed` 只在違反 ledger 的保證（site 改變、對應不到世界）時丟 `IllegalArgumentException`；F4b-2 在 `END_SERVER_TICK` 呼叫，仍要放在塌縮流程既有的例外界線之內。
  - 寫入器的結果：`BLOCKED_BY_PLAYER`、`NOT_LOADED` 下一個 tick 再試（不是失敗）；`VERIFY_FAILED`、`WRITE_FAILED` 帶 failure，可直接當 SEALED(`PROJECTION_FAILED`) 的 root cause。寫入器不記 log，WARN／ERROR 由 F4b-2 以 session 記。`WRITE_FAILED` 的 GameTest 需要 F4b-2 的 fault mixin。
  - 寫入器不做衝突與 ledger 的判斷：§6.5 的實體交叉檢查用 `PW:105` `controllerPresence`；§9.2 的衝突用 `PPI:220` `intersecting`（回傳 PENDING 與 COMPLETE，F4b-2 只把另一座艙的 COMPLETE 當成衝突）；退化時新的 PREPARE 進 SEALED 用 `PPI:212` `degradation()`。
  - 預載：寫入器在 volume 的 chunk 不是都 FULL 時回 `NOT_LOADED`、不載入；F4b-2 的預載不得用阻塞式 `getChunk(create=true)`（plan Step 1）。GameTest 的 `forceProjectionChunks` 是 test 專用的同步載入。
  - G-NEW 的 `onlyTheStubWarnAndNoError`（F4a 交下）照舊交給 F4b-2；W1、GT-P、GT-L 各有一則 stub WARN（M5 WARN 13 → 16），PREPARE 實作之後會消失，屆時 M5 run 的 WARN 數字要重新歸因。GT-L 的 log 斷言只排除塌縮 session 本身的 log（fix round 1 更正：首輪寫成「GT-P、GT-L」，GT-P 沒有 log 斷言）。
  - （fix round 1，controller 補）「寫入器驗證 → checkpoint → `writeProjection(COMPLETE)`」之間是 spec 本身就有的未保護窗口：這段期間不變量 cell 的損壞不會被擋，之後仍以 COMPLETE 記下，要到下次 EXISTING 造訪的修復才寫回。F4b-2 要在寫 COMPLETE 之前再驗一次（例如再以 REPAIR 呼叫寫入器，或比對不變量 cell），或在 note 註明這個窗口。
- F6：
  - 重啟矩陣加入「PENDING 寫到一半時重啟」：開服時 PENDING entry 只供辨識，PREPARE 再寫。
  - （fix round 1，controller 補；spec review 對「偏離」第 3 條的意見）真正從磁碟載入時的辨識：在重啟矩陣中帶 COMPLETE 與 PENDING 的 entry 重啟，投影的 chunk 從磁碟載入之後，Controller 要辨識為 inert PROJECTION，而且不記 log。
- F7：spec §9.3 的 errata（裁示已採用更新入口的設計）：「PENDING 確認時登記 Controller 位置供載入辨識；COMPLETE 時開始保護」。
- **F4b-2 Step 0（controller 補，quality review 的 Minor）**：F4b-2 開始 PREPARE 之前，先以獨立 commit 處理下列各項，每項都要有 RED 或 mutation 證據（純文件項除外）。行號以 `0576b44` 為準。
  - Q-m4：`PPI:197-206` 的 `confirmed(Entry)` 接受原始 entry，F4b-2 若傳入 `writeProjection` 的輸入值，未確認的投影會提早被辨識，甚至受保護。改成接受 `CollapseAuthorities.WriteResult`：只接受 `WRITTEN`／`UNCHANGED`／`ALREADY_COMPLETE`，其他丟 `IllegalArgumentException`。`confirmed` 與 `intersecting` 都加上 server thread 檢查。
  - Q-m2：`PPI:53`、`:150` 的「任何執行緒都可以呼叫」改寫：健康時任何執行緒都可以呼叫；退化分支會讀 `ChamberRegistry`，只在 server thread 安全。
  - Q-m3：`PPI:269-276` 的 fail closed，同一份 stack trace 印了兩次。`degrade` 不帶 cause；WARN 字句「新的 PREPARE 進 SEALED」改成與 F4b-2 接上後的實際行為一致。
  - Q-m5：GT-P 的 tickLimit 是 400（`M5U:1036`），小於有界等待總和的最壞情況（約 486 tick），改成 600。
  - Q-m6：PPIT 補一個跨 chunk 邊界、含負座標的 frame，斷言 218／125，並以「只登記 Controller 所在 chunk」的 mutation 證明鎖得住。
  - Q-m7：`CS:858-861` 的 `attempt` Javadoc 已過時：唯一的呼叫端是 static `sideEffect`。改字句，可以改成 private。
  - Q-m8：在 README「新增 M5 GameTest」補一條隔離規則：索引退化之後，同一個 instance 內不會解除；GT-P、GT-L 寫入的 COMPLETE entry 會一直留在 M5 run 的 ledger。會讓 projection ledger 在執行期遺失的 test，要排在最後，或提供 testmod 專用的重建 hook；projection ledger 的殘留也要寫進串接說明。
  - Q-m9：M5U 新增的部分有 173 處完整限定名稱，改用 import（只改寫法，行為不變，test 集合不變）。把投影的 test 拆到獨立類別不在這一輪做。
- **F4b-3 Step 0（controller 補，quality Q-m1）**：`chamber` 與 `collapse` 互相依賴（`CPS:3`／`:65`、`CPC:3`／`:57`）。`5bafb25` 之前，`chamber` 不 import `collapse`，跨 package 都走反轉（`registerAdditionalGuard`、`ChamberSessions.gateway()`）。
  - F4b-3 的門攔截也需要從 `chamber` 呼叫 `collapse`，所以一併處理：
    - 保護改經 `registerAdditionalGuard`，或在 `chamber` 定義的 port；
    - 辨識與門攔截改經 `chamber` 定義、由 `collapse` 註冊的 port。
  - 處理後 `chamber` 不再 import `collapse`。以 grep 或 `CollapseLogMarkerGuardTest` 類的守門 test 鎖住。

### Fix round 1（`0020bd5`、`a928216`）

- Review：spec review ❌（Critical 0、Important 1、Minor 3）。三項「需要裁示」與「偏離」第 3 條判定符合 spec，controller 正式接受（「裁示」）。controller 的 fix round 1 派工處理 I-1、M-1～M-3。
- Commits（依序；只 stage 自己的檔案）：
  - `0020bd5` `test: lock the forced controller rewrite of the first projection write`（parent `c7bbde7`）：M-2。只有 `M5U` 的 W2。
  - `a928216` `test: repair a protected projection and push into a damaged invariant cell`（parent `0020bd5`）：I-1、M-1。只有 `M5U` 的 GT-P。
  - 本小節由其後的 docs commit `docs: record F4b-1 fix round 1 in the M5 note` 加入；同一個 commit 也更正本節的 M-3 兩處、在 Review 行、「偏離」第 4 條、「裁示」、「交給後續」加註 fix round 1。
- Code range `c7bbde7..a928216`。只改 testmod 的 `M5UniverseGameTests.java`；沒有改 production（`ProjectionWriter` 沒有缺陷：I-1、M-2 的新斷言在修改前的寫入器上都通過），沒有改 `M5GameTestSupport.java`、mixin、`build.gradle`、`.github/`、`scripts/verification/`。
- 本小節的行號以 `a928216` 為準。證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b1/fix1/`（以下簡稱 `fix1/`）：`state.ps1`、`gate.ps1`、`chain.ps1`、`adapter.py`、`wbcmp.py`、`jarcmp.py` 由 `f4b1/` 複製，只改 repo 的相對路徑與 adapter 表（`adapter.py` 另支援 `FILE:` 整檔替換，供逐 commit 取證）；`f4b1/` 下既有的檔案沒有改動。

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| I-1：寫入器經 `authorizedMutation`，以及修復 COMPLETE、已受保護的投影，都沒有測試 | 依 reviewer 的建議放在 GT-P（不新增 `@GameTest`）：五類保護之後（`M5U:1149` 起的判定 callback），對被損壞的 COMPLETE 投影呼叫 `ProjectionWriter.write(…, REPAIR)`。損壞的 4 格：放置 test 的 `local(0,3,3)`（AIR）、爆炸 test 的 `local(6,3,3)`（玻璃）、流體 test 的 `local(6,4,3)`（AIR）、M-1 的 `local(6,2,2)`（AIR）。新 witness：`repairOfTheProtectedProjectionWritten`（`M5U:1163`，WRITTEN 而且恰好 4 格）、`repairRestoresEveryInvariantCell`（`:1164`，`projectionMismatches(shellOnly=true)` 為空、門開著）、`repairKeepsTheInteriorChange`（`:1166`，`local(2,1,3)` 的石頭）、`stillProtectedAfterTheRepair`（`:1167`） | mutation `mut-w-no-authorization`（拿掉 `PW:131-133` 的 `authorizedMutation` 包裝）：`mut-a-no-authorization`、`mut-d-no-authorization`（`a928216` 的工作樹）都只有 GT-P FAIL，恰好 `repairOfTheProtectedProjectionWritten`、`repairRestoresEveryInvariantCell` 為 false；W2 照常 PASS（證明 W2 的修復不涵蓋授權） |
| M-1：活塞這一類沒有能抓到保護失效的斷言 | 補「活塞把石頭推進損壞成 AIR 的不變量 cell」：授權寫入把 `local(6,2,2)` 改成 AIR，`outside(7,2,2)` 放石頭，`outside(8,2,2)` 的活塞由 `outside(9,2,2)` 的紅石塊供電、朝西推（`M5U:1126-1130`）。判定等這台活塞伸出、兩格都不是 MOVING_PISTON（`pushSettled`）。新 witness `pistonCannotPushIntoADamagedInvariantCell`（`M5U:1155`，那一格仍是 AIR） | RED `red-cps-no-index`：`red-a-protection-no-index`、`red-d-protection-no-index` 中那一格變成石頭（log `pushedInto=Block{minecraft:stone}`），witness 為 false。GREEN 的 log 是 `pushedInto=Block{minecraft:air} pushedStone=…piston_head…`：被推的石頭消失，活塞頭留在石頭原本的位置 |
| M-2：第一次寫入時，格上已有 Controller 會被強制重寫，沒有測試 | W2 在 Q 離開之後、成功的第一次寫入之前，先在 Controller 位置放一座方塊狀態與目標相同、block entity 帶隨機 chamber UUID 的 Controller（`M5U:958-965`，`staleControllerHasAnIdentity`）。新 witness `existingControllerRewrittenWithADefaultBlockEntity`（`M5U:969`：block entity 換成新的、舊的已 removed、沒有 chamber UUID） | 兩個 mutation 都 FAIL，`firstWriteAfterQLeft`、`existingControllerRewrittenWithADefaultBlockEntity` 為 false：`mut-w-no-forced-controller-write`（`PW:174` 不強制重寫，`mut-a-…`、`mut-b-…`）與 `mut-w-no-controller-air-step`（`PW:181-183` 不先換成 AIR，同一格寫入相同的狀態不會換 block entity；`mut-a-…`、`mut-b-…`） |
| M-3：note 的小錯 | Step 0 表的「`CA:735` 前提檢查」改為 `CA:739-742`；adapter 名與 root 名的對照見下表；另更正「交給後續」中「GT-P、GT-L 的 log 斷言」為只有 GT-L | — |

M-3 的對照（左邊是 adapter 名，`f4b1/adapter.py`；右邊是 evidence 的 root／摘要名，`f4b1/<root>-summary.txt`）：

| adapter | root |
| --- | --- |
| `red-q2m1-no-direction-check`、`red-q2m2-corrupt-as-unconfirmed`、`red-s2ma-spent-after-call`、`red-q2m4-fixed-text`（同時套用） | `red-0` |
| `mut-q2m3-composition-always-warns`（`cst-at-head`＋它） | `mut-0-q2m3-new-test`（`mut-0-q2m3-head-test`） |
| `mut-firstwarning-always` | `mut-0-firstwarning` |
| `red-ca-api-stub` | `red-1-ca-api` |
| `mut-ca-single-corrupt-marks-lost` | `mut-1-single-corrupt` |
| `red-index-stub` | `red-3-index` |
| `mut-index-intersect-all`、`-no-startup-info`、`-any-namespace`、`-no-exact-key`、`-onshell-includes-interior`、`-pending-protected`、`-status-regresses` | `mut-3-intersect-all`、`mut-3-no-startup-info`、`mut-3-any-namespace`、`mut-3-no-exact-key`、`mut-3-onshell-includes-interior`、`mut-3-pending-protected`、`mut-3-status-regresses` |
| `red-w-naive` | `red-4-writer-naive`、`red-4b-writer-naive-w1` |
| `mut-w-no-clear`、`mut-w-neighbors`、`mut-w-no-force-state`、`mut-w-spectators-block`、`mut-w-entities-block` | `mut-4-*` 與 `mut-4b-*` 同名（例如 `mut-w-no-clear` → `mut-4-no-clear`、`mut-4b-no-clear`） |
| `mut-w-first-write-checks-pending-only`、`mut-w-repair-checks-every-invariant-cell`、`mut-w-repair-writes-interior` | `mut-4-first-write-checks-pending-only`、`mut-4-repair-checks-every-invariant-cell`、`mut-4-repair-writes-interior` |
| `red-cps-no-index` | `red-6-protection-no-index`（不採計）、`red-6b-protection-no-index`、`red-8-protection-no-index` |
| `red-cpc-no-recognition` | `red-7-recognition-no-branch` |
| `mut-cpc-kind-criterion`、`mut-cpc-any-dynamic`、`mut-index-controllers-complete-only` | `mut-7-kind-criterion`、`mut-7-any-dynamic`、`mut-7-controllers-complete-only` |
| fix round 1：`mut-w-no-authorization`、`red-cps-no-index`、`mut-w-no-forced-controller-write`、`mut-w-no-controller-air-step`（`fix1/adapter.py`） | `fix1/mut-a-no-authorization`、`mut-d-no-authorization`；`red-a-protection-no-index`、`red-d-protection-no-index`；`mut-a-`／`mut-b-no-forced-controller-write`；`mut-a-`／`mut-b-no-controller-air-step` |

M-1 的原版 WARN：GREEN 的 GT-P 每次恰好多一則 `(Minecraft) Trying to set block entity net.minecraft.block.entity.PistonBlockEntity@… at position BlockPos{…}, but state Block{minecraft:air} does not allow it`（`WorldChunk.setBlockEntity`），位置就是 `local(6,2,2)`。`addBlockEntity` 之後的 `updateTicker` 仍登記了 ticker，但那個 block entity 沒有 world（`setBlockEntity` 拒絕時不設 world），ticker 的 `hasWorld()` 為假，不會 tick，所以沒有「invalid for ticking」的第二則 WARN；這個 ticker 留在 chunk 上，到 chunk 卸載為止。RED 中那一格放行，沒有這則 WARN。這個情境穩定（`green-a-quick`、`green-a-m5`、`green-c-quick`、`green-c-m5` 與 run-all 都是同一則），也不影響 M5 run 的 log 規則：gate 不掃描 WARN，`quantumchamber` logger 的斷言（GT-L）不收原版 logger。

證據（逐輪的 state 記 HEAD、porcelain 與逐檔 blob）：

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| GREEN-A | `c7bbde7`＋W2、GT-P 的改動（`M5U` blob `30308a5`） | `gate-green-a-quick-cdefc6e935cc4000bb9014145bfbc700`；`gate-green-a-m5-30c7f26720f44e6883e1faf224f9aad8` | GT-P、W2 PASS；M5 17/17 |
| 首輪 mutation／RED | GREEN-A 的工作樹＋adapter | `gate-mut-a-no-authorization-53fd8e52deb443008df0c0f818ed60e5`；`gate-red-a-protection-no-index-521d544ec0b54ce7944e34e87f0ffd17`；`gate-mut-a-no-forced-controller-write-70f48adba4bc45df82fb0df3ebd4d25e`；`gate-mut-a-no-controller-air-step-dad371d845204b64812cae5310a7fdc7` | 上表 |
| GREEN-B（`0020bd5` 的內容） | `c7bbde7`＋只有 W2（`M5U` blob `c6b4b66`，`fix1/m5u-w2-only.java`，以 adapter `state-w2-only` 換入） | `gate-green-b-w2-quick-cbd81bd5e3e94ec0a26f9fa79c341dac`；`gate-green-b-w2-m5-af62d5cde0b744c79cb18e280f7e3efb`；mutation `gate-mut-b-no-forced-controller-write-4a8ca20a618846dd90504532ed2fdf74`、`gate-mut-b-no-controller-air-step-e2b7e0bfb9484d2bb908069240b1c3de` | W2 PASS；M5 17/17；兩個 mutation FAIL |
| GREEN-C（`a928216` 的內容） | `c7bbde7`＋全部（`M5U` blob `5328482`；GREEN-A 之後只改了 GT-P 的 Javadoc：首版寫「兩則原版 WARN」，實測一則） | `gate-green-c-quick-b844cb8a49f44d4eb642ac70e6fff1f5`；`gate-green-c-m5-36af75c9409749d09579c3bd1d8a670e` | GT-P、W2 PASS；M5 17/17 |
| `a928216` 的 mutation／RED | `a928216`，工作樹乾淨＋adapter | `gate-mut-d-no-authorization-7b9c1751cb4647e08c6c06e2d17fac54`；`gate-red-d-protection-no-index-c25b48ba59204b4895782c1c166575bb` | 與首輪相同 |
| run-all | `a928216`，工作樹乾淨（`STATUS` 是空的） | `run-all-7bab6785434347c4ab75c5d21a34c4b0`（Full：`gate-full-5f11241d3816402782059fc56c68da40`；M5：`gate-m5-gametest-ee51511058224936aee030665488a908`；`fix1/run-all-console.txt`） | `END ALL exit=0 HEAD=a928216`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=17/17 perTest=51+1 junit=626 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=393entries/346classes sources=216 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- `0020bd5` 的 `M5U` 是 GREEN-B 的 blob `c6b4b66`：以 `git apply --cached` 只把 W2 的兩個 hunk 放進 index（`fix1/m5u-w2-only.patch`），取出成檔驗證之後以 `git update-index --cacheinfo` stage；`a928216` 是 GREEN-C 的 blob `5328482`（`fix1/commit-blobs.txt`）。每個 adapter 跑完都還原，還原後的 blob 與套用前相同。
- 沒有改 production，所以沒有加跑 default 的 21 個 batch；run-all 的 Full 涵蓋 default run。
- 與首輪的 run-all（`run-all-28d70ca9adf14ccdbb63acd8958c576b`，`515fbc4`）逐項比較：
  - SUMMARY 的每一項都相同。M5 oracle PASS：17 個 test，suite 結束時 16 筆；`M3_UNIVERSE_STOPPED_VERIFIED worlds=10` 不變。
  - release、sources JAR 的 entries 與每個 entry 的內容都相同（`fix1/jarcmp-release.txt`、`fix1/jarcmp-sources.txt`；本輪沒有改 production）。
  - log（`fix1/wbcmp.py`）：Full 54／28、legacy 0／0 不變（`fix1/wbcmp-full.txt`、`wbcmp-legacy.txt`）。M5 WARN 16 → 17、ERROR 1 不變（`fix1/wbcmp-m5-gametest.txt`）：多出的一則是 M-1 的原版 WARN「Trying to set block entity … PistonBlockEntity … but state Block{minecraft:air} does not allow it」（上方「M-1 的原版 WARN」）。
  - GT-P 的 log：`M5_PROJECTION_PROTECTION controlsSettledAfter=11 … pushedInto=Block{minecraft:air} pushedStone=…piston_head…`、`M5_PROJECTION_PROTECTED_REPAIR status=WRITTEN written=4 mismatched=0`。
- 本輪沒有遇到派工列出的已知偶發，也沒有停服卡住；失敗的 root 都是刻意的 RED 或 mutation。

測試：

- M5 GameTest 17 不變：只增加 witness（W2 ＋2：`staleControllerHasAnIdentity`、`existingControllerRewrittenWithADefaultBlockEntity`；GT-P ＋5：`pistonCannotPushIntoADamagedInvariantCell` 與 I-1 的四個）。GT-P 的 catalog 增量仍是 1。
- JUnit 626、default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 都不變。

## F4b-2：PREPARE 的目的宇宙側（預載、投影、交叉檢查、衝突、退化）與 F4b-1 的 Step 0

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `b05032c` `fix: register only confirmed projection write results in the protection index`（parent `97df474`）：Step 0 的 Q-m4、Q-m2、Q-m3、Q-m6。`PPI`、`PPIT`；`M5U` 只改 GT-P、GT-L 的六個呼叫點（`confirmed(writeResult)`）。
  - `62d535c` `refactor: make attempt private and describe it as the first step of sideEffect`（parent `b05032c`）：Q-m7。只有 `CS`。
  - `6eeff52` `test: import the universe GameTest types and give the protection test room`（parent `62d535c`）：Q-m9、Q-m5。只有 `M5U`。
  - `2924c8b` `docs: state the projection residue and isolation rule for M5 GameTests`（parent `6eeff52`）：Q-m8。只有 `scripts/verification/README.md`。
  - `5e9b174` `refactor: return the PREPARE transition from the collapse phase derivation`（parent `2924c8b`）：補充第 3 點。`CP`、`CS`、`CST`；行為不變。
  - `d5b059a` `feat: preload the destination universe under an M5 chunk ticket during PREPARE`（parent `5e9b174`）：補充第 2、3、4（移除 F4a 的 stub）、8 點。`CS`、`CST`；testmod `M5U`（P-NEW、P-POWER、G-NEW 改寫、F4b-1 fixture 暫停）、`SUP`。
  - `9c8c986` `feat: write the destination projection through its ledger and checkpoint before COMPLETE`（parent `d5b059a`）：補充第 1、4、5、6 點。`CS`、`CST`；testmod `M5U`（P-NEW 擴充、P-POWER 擴充、P-REVISIT、P-BLOCK）、`SUP`、`HK`、新增 `mixin/ChunkSaveCheckpointProbeMixin.java`、`quantumchamber-test.mixins.json`（只登記它）。
  - `c87ae77` `test: keep the blocking player five more ticks to lock the wait warning dedupe`（parent `9c8c986`）：只有 `M5U` 的 P-BLOCK（見「Gate 與證據」的 `mut-d-wait-warns-every-tick`）。
  - `49e665e` `test: seal PREPARE on conflicts, a bad site, degradation and unconfirmed or failed writes`（parent `c87ae77`）：testmod `M5U`（P-CROSS、P-OVERLAP、P-SITE、P-DEGRADED、T-PF、T-CF）、`SUP`（重建 hook）。
  - `b8b2b54` `refactor: name the phase PREPARE leaves to in its INFO`（parent `49e665e`）：只有 `CS` 的 `leavePrepare` 字句。
  - `c67663c` `test: fail a projection write and retry it after the backoff over its own PENDING`（parent `b8b2b54`）：testmod `HK`、`SUP`、`M5U`（T-WF）、新增 `mixin/ProjectionWriteFaultMixin.java`、`quantumchamber-test.mixins.json`（只登記它）。
  - `dc11821` `docs: describe the F4b-2 PREPARE GameTest helpers`（parent `c67663c`）：`scripts/verification/README.md`「新增 M5 GameTest」一節。
  - 本段由其後的 docs commit `docs: record F4b-2 prepare destination side in the M5 note` 加入。
- Code range `97df474..c67663c`。沒有改 `ChamberControllerBlock`、`QuantumBulkheadBlock`、`WorldChamberDoorAdapter`、`ChamberDoorService`、`ChamberProtectionService`、`ChamberPowerCoordinator`、`CollapseAuthorities`、`DestinationResolver`、`ProjectionWriter`、`CorridorPageManager`、`SuperpositionSessionManager`、`SessionRecoveryState`、`SessionRecoveryManager`、`UniverseRegistryState`、`ChamberRegistryState`、`UniverseLifecycleService`、語言檔、`gates.ps1`、`build.gradle`、`.github/`。
- Review：spec review ✅（Critical 0、Important 0、Minor 5：S-M1～S-M5，Nit 1）；quality review Not approved（Critical 0、Important 1、Minor 6、Nit 3：Q-I1、Q-M1～Q-M6）。三項「需要裁示」與「偏離」第 1 條（退化檢查提早），spec reviewer 都判定符合 spec，controller 正式接受（見「裁示」）。處置見本節末的「Fix round 1」。
- 最終 review：
  - fix round 1 的 spec re-review ✅（Critical 0、Important 0、Minor 1）。Q-M3、Q-M4 的處理都判定符合 spec，controller 正式接受。Minor 1 由 controller 寫進「交給後續」的 F4b-3：REJECTED 與隔離期間，門也要改為一般開門。
  - fix round 1 的 quality re-review Approved（Critical 0、Important 0、Minor 2、Nit 2）。Q-I1 從根本關閉，查過所有路徑，沒有其他每個 tick 存檔的路徑。Minor 與 Nit 由 controller 寫進「交給後續」：
    - m-1（CS 的大小）交給 F5 的 Step 0；
    - m-2 就是上面那一條；
    - 兩則 Nit 交給 F4b-3 的 Step 0。
  - **F4b-2 完成**：range `97df474..2dcfebf`，之後是 controller 的 docs commit。
    - code：`b05032c`、`62d535c`、`6eeff52`、`5e9b174`、`d5b059a`、`9c8c986`、`c87ae77`、`49e665e`、`b8b2b54`、`c67663c`、`de3f56d`、`726c7e0`、`aea4b07`、`c3c7ea8`、`bfaf6f0`、`b6fdf59`、`1f41d0a`。
    - docs：`2924c8b`、`dc11821`、`3e7cf1d`、`97f1898`、`2dcfebf`。
    - 最終 run-all：`run-all-4d864a16e1cf45fd90cdfe3c3d35b0ac`，`END ALL exit=0`（`97f1898`）。基準數字如下：

      | 項目 | 數值 |
      | --- | --- |
      | default | 174/174 |
      | M5 | 30/30 |
      | JUnit | 631 |
      | release／sources | 401／354、216 |
      | Full WARN／ERROR | 54／28 |
      | M5 WARN／ERROR | 29／6 |
- 範圍是 plan Task F4b「拆分」的 F4b-2：UNIVERSE 的 PREPARE（目的宇宙這一側），準備完成之後在 F5 之前停住（READY stub）。不套用量子膜、不攔截門（F4b-3）：PREPARE 期間原艙門維持一般狀態，開關門照舊交給 `ChamberDoorService`（§7.3「套用量子膜的條件不成立時，門維持一般開門」）。
- 行號以 `c67663c` 為準。`CP` 指 `collapse/CollapsePhase.java`，`HK` 指 `M5TestHooks`，`CCPM` 指 `mixin/ChunkSaveCheckpointProbeMixin.java`，`PWFM` 指 `mixin/ProjectionWriteFaultMixin.java`；`CS`、`CST`、`PPI`、`PPIT`、`PW`、`CA`、`M5U`、`SUP` 同前面各節。M5U 新增的 11 個 test（各自一個 batch，名稱不以 `_windows` 結尾，各平台都執行，catalog 增量各 1）：
  - P-NEW `M5U:1374` `prepare_new_runs_the_destination_side_then_stops_before_the_passage`（`m5_universe_prepare_new`）
  - P-POWER `M5U:1449` `prepare_power_loss_releases_the_destination_ticket_and_an_entry_edge_resumes_it`（`m5_universe_prepare_power`）
  - P-REVISIT `M5U:1505` `existing_revisit_repairs_only_the_invariant_cells_and_keeps_the_interior`（`m5_universe_prepare_revisit`）
  - P-BLOCK `M5U:1563` `prepare_waits_for_a_player_in_the_destination_volume_and_warns_once_after_six_hundred_ticks`（`m5_universe_prepare_blocked`）
  - P-CROSS `M5U:1616` `prepare_seals_a_conflict_when_the_destination_already_has_a_controller_without_a_ledger_entry`（`m5_universe_prepare_cross_check`）
  - P-OVERLAP `M5U:1664` `prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection`（`m5_universe_prepare_overlap`）
  - P-SITE `M5U:1707` `prepare_validates_the_site_of_an_existing_complete_entry_before_repairing`（`m5_universe_prepare_site`）
  - P-DEGRADED `M5U:1756` `deleting_the_projection_ledger_after_complete_degrades_and_seals_without_rewriting_the_interior`（`m5_universe_prepare_degraded`）
  - T-PF `M5U:1822` `pending_write_failure_seals_without_writing_any_block`（`m5_universe_prepare_pending_fault`）
  - T-CF `M5U:1857` `checkpoint_failure_seals_before_the_projection_is_complete`（`m5_universe_prepare_checkpoint_fault`）
  - T-WF `M5U:1900` `projection_write_failure_seals_then_retries_after_the_backoff_over_its_own_pending`（`m5_universe_prepare_write_fault`）
  - 另外 G-NEW 改名為 `dormant_new_receipt_resolves_without_power_then_waits_for_an_entry_edge`（`M5U:278`，batch 不變）。

### Step 0（F4b-1 quality review 的 Minor）

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q-m4 | `PPI:211` `confirmed(CollapseAuthorities.WriteResult)`：只接受 `WRITTEN`／`UNCHANGED`／`ALREADY_COMPLETE`（取結果帶的已確認 entry），其他四種丟 `IllegalArgumentException`、索引不變；`confirmed` 與 `intersecting`（`PPI:243`）都 `requireServerThread`（`PPI:306`）。執行緒判斷是建構子的新參數 `BooleanSupplier onServerThread`（`PPI:78`、`:88`；production 是 `server::isOnThread`，JUnit 傳 `() -> true` 或 `() -> false`）。`CS` 只傳 `writeProjection` 的結果（`CS:584`、`:648`） | PPIT `confirmedAcceptsOnlyConfirmedWriteResultsOnTheServerThread`（`PPIT:120`）。API 換了簽名，修改前的 test 無法編譯，所以以 mutation 證明：`mut-q4-no-status-check`（直接 `entry().orElseThrow()`：失敗的結果丟 `NoSuchElementException`，不是 IAE）、`mut-q4-no-confirmed-thread-check`、`mut-q4-no-intersecting-thread-check` 都只有這個 test FAIL（`f4b2/s0-mut-q4-*-junit-summary.txt`） |
| Q-m2 | 類別 Javadoc「熱路徑」與 `protectedCell` 的 Javadoc：健康時任何執行緒都可以呼叫；退化之後讀 `ChamberRegistry`，只在 server thread 安全 | 只有 Javadoc |
| Q-m3 | `PPI:301` `failClosed` 記 ERROR（附 stack）之後以 `degrade(reason)`（`PPI:292`，不再帶 cause）退化：同一份 stack 只印一次。WARN 字句改成接上之後的實際行為：「還沒完成準備的 PREPARE（進行中的，以及之後開始的）一律進 SEALED(原因)，已完成準備的不受影響」 | RED `red-q3-stack-twice`（修改前的 `degrade(reason, cause)` 與舊字句）：`unexpectedFailuresFailClosedWithTheDegradationRule`（`PPIT:296`，退化 WARN 不附 stack）與 `startupDegradation…`（`PPIT:188`，新字句）FAIL（`f4b2/s0-red-q3-stack-twice-junit-summary.txt`） |
| Q-m5 | GT-P 的 tickLimit 400 → 600，Javadoc 寫出最壞的總和約 486 tick（註冊 ≤43、RESOLVE ≤+22、block ticking ≤+200、對照組 ≤+200、收斂 ≤+20） | 純設定；`gates.ps1 -Mode Inventory` 前後只有這一個 annotation 不同（`f4b2/qm9-before-inventory.json`、`qm9-after-inventory.json`） |
| Q-m6 | PPIT `crossChunkFrameWithNegativeCoordinatesIsIndexedInEveryCoveredChunk`（`PPIT:153`）：frame `(-17, 70, -2)` 朝西，7×7×7 在 x、z 都跨兩個 chunk、chunk 座標為負（fixture 先斷言），218／125，四個角落都 `intersecting` 得到 | mutation `mut-q6-controller-chunk-only`（依 chunk 的索引只登記 Controller 所在的 chunk）：只有這個 test FAIL，218 → 35 |
| Q-m7 | `CS` 的 `attempt` 改成 private，Javadoc 寫明唯一的呼叫端是 static `sideEffect` 的組合 | 純寫法；`CST` 不呼叫 `attempt` |
| Q-m8 | README「新增 M5 GameTest」新增「投影的殘留與隔離」一點：projection ledger 的殘留不被 gate 串接、為什麼不會互相衝突；退化與執行期遺失在 instance 內不解除、batch 順序不固定（T1），所以必須以重建 hook 還原（`2924c8b`；`dc11821` 補上 hook 名稱與唯一使用它的 test） | 純文件 |
| Q-m9 | `M5U` 程式碼行的 258 處完整限定名稱改用 import（註解行不動；`f4b2/qm9_imports.py` 機械替換、`f4b2/qm9-report.txt`），連同 F4a 的部分一起改，一份 import 清單 | `gates.ps1 -Mode Inventory` 前後都是 192 個 `@GameTest`，差異只有 Q-m5 的 tickLimit；M5 run 17/17（`green-0b-m5`） |

- Step 0 的 JUnit GREEN：`green-0a`（PPIT 12、CST 14、`GT` 2）；GT-P、GT-L 在 `confirmed` 換簽名之後的 M5 subset `green-0a-m5` 2/2。

### plan Step 1 中屬於 F4b-2 的 RED

| plan Step 1 | 程式位置 | 測試與證據 |
| --- | --- | --- |
| 第 1 組：預載不用阻塞式 `getChunk(create=true)` | `CS:722` `preload`：第一次對 `CS:736` `preloadChunks`（bbox 水平外擴 `PRELOAD_MARGIN`＝2 格）的每個 chunk 加 `CS:130` `DESTINATION_TICKET`（`quantumchamber_destination`、argument＝session、radius 0＝level FULL），之後每個 tick 以 `getWorldChunk` 查詢 | P-NEW：`ticketsCoverTheBoxPlusTwoAtFull`（範圍由 test 依 spec 自己算，`SUP:736` `preloadArea`；ticket 從原生 ticket 表讀，`SUP:699`）、`preloadWaitedAtLeastOneTick`（production 的 INFO「等了 N ticks」≥1）、`everyPreloadChunkFull`。CST `preloadCoversTheChamberBoxPlusTwoBlocks`（四個朝向、負座標、貼著 chunk 邊界）。RED `red-c-universe-gate-shut`（修改前：UNIVERSE 的閘門恆為假）P-NEW、P-POWER FAIL；mutation `mut-c-blocking-preload`（改用 `world.getChunk(x, z)`）：`preloadWaitedAtLeastOneTick=false`（同一個 tick 就全部 FULL） |
| 第 2 組：checkpoint 之後才 COMPLETE | `CS:615` 先 `ready`、`:620` `save`，`:643` 才寫 COMPLETE | P-NEW：checkpoint 探針（`CCPM`）記下每一次 checkpoint 當下 ledger 的狀態，`checkpointedTwiceBothWhilePending`、`checkpointCoversTheVolumeChunks`。T-CF：checkpoint 受控失敗 → `stillPendingNotComplete`。mutation `mut-d-complete-before-checkpoint`（COMPLETE 寫在 checkpoint 之前）P-NEW FAIL；`mut-e-complete-despite-checkpoint-failure`（checkpoint 失敗也繼續）T-CF FAIL |
| 第 2 組：COMPLETE 之後刪除 projection ledger（schema 4）→ 不重寫 interior | ledger 遺失之後：下一次 checked 寫入的前置檢查判為遺失（`CA`，F2b）→ 權威不健康 → 索引退化 → `CS:509` 的退化檢查 → SEALED(`AUTHORITY_CORRUPT`) | P-DEGRADED：`lossDetectedByTheNextCheckedWrite`、`indexDegraded`、`ledgerNotRecreated`、`interiorNotRewritten`、`invariantNotRepaired`、`neverPrepared`、`onePermanentAdminError`；之後以重建 hook 還原（`rebuiltHealthy`）。mutation `mut-e-no-degradation-check` FAIL（見「觀察」第 1 條：拿掉退化檢查之後，`CA` 自己的遺失閘門仍會在投影那一步以 `AUTHORITY_CORRUPT` 封存，所以這個 mutation 是靠 ERROR 的 cause 抓到的） |
| 第 3 組：實體交叉檢查；有 PENDING 時照第一次寫入 | `CS:557`：ledger 沒有 entry 時才查 `PW.controllerPresence`，PRESENT → `PROJECTION_CONFLICT` | P-CROSS：`noPendingWritten`、`interiorNotCleared`、`noProjectionBlocks`、`existingControllerKept`、`onePermanentAdminError`、`permanentNeverRetried`。T-WF 的重試：自己寫了一部分的 Controller 不算衝突（`rewroteOverItsOwnPending`）。mutation `mut-e-no-cross-check` P-CROSS FAIL；`mut-f-cross-check-with-pending`（已有 PENDING 也檢查）T-WF FAIL（重試封存成 `PROJECTION_CONFLICT`） |
| 第 4 組：既有 entry 的位置驗證 | `CS:576`：一律以 `writeProjection(PENDING)` 取得 site 驗證，不直接拿 `FOUND COMPLETE` 走修復；`CS:689` `writeMode` | P-SITE：ledger 已有本艙在 U、ControllerPos 高一格的 COMPLETE → SEALED(`AUTHORITY_CORRUPT`)，`nothingRepairedAtThisSite`、`ledgerNotRewritten`。mutation `mut-e-skip-site-validation`（查到的 entry 直接決定寫法）FAIL |
| 第 5 組：退化時進 SEALED 的流程 | `CS:509`：預載之後、任何 ledger 操作之前查 `PPI.degradation()`，有值就以它的原因封存；準備完成（`CS:495`）之後不再查 | P-DEGRADED（上列）。PPIT 的退化 test 不變 |
| 第 6 組：玩家擋住時下一個 tick 重試，併入 600 ticks WARN | `CS:677` `writeStep`：`BLOCKED_BY_PLAYER`／`NOT_LOADED` → WAIT；`CS:745` `prepareWait` 從進入 PREPARE 起超過 600 ticks 記一次 WARN | P-BLOCK：`noWarnBeforeSixHundredTicks`、`warnNamesTheBlockingPlayer`、`nothingWrittenWhileBlocked`、`pendingConfirmed`、`playerNotMoved`、`stillOneWarnWhileBlocked`、`writtenAfterQLeft`、`warnedOnlyOnce`。mutation `mut-d-blocked-seals` FAIL；`mut-d-wait-warns-every-tick`（不去重）首輪存活 → `c87ae77` 讓 Q 再擋 5 tick，之後 FAIL |
| 第 7 組：投影衝突；注入寫入失敗 → `PROJECTION_FAILED` → 邊緣觸發＋退避重試成功 | 衝突：`CS:570`／`:698` `conflict`（另一座艙的 COMPLETE）；寫入失敗：`writeStep` → SEAL（`PROJECTION_FAILED`，cause 是結果帶的 failure）；重試沿用 F4a 的 `sealed`／`retrySealed` | P-OVERLAP：`noPendingForThisChamber`、`notOverwritten`、`onePermanentErrorNamingTheOtherProjection`。T-WF：`writeFaultedOnce`、`pendingKept`、`oneRetryableWarnWithTheInjectedCause`、`noRetryWithinTheBackoff`、`retriedOnce`、`oneUniverse`（allocation entry 一筆、catalog 增量 1）。mutation `mut-e-no-overlap-conflict` P-OVERLAP FAIL；`mut-f-write-failure-waits` T-WF FAIL |
| 第 9 組：投影方塊只在 PENDING 確認之後寫 | `CS:576-580`：PENDING 沒有確認（含讀回失敗）就封存、return | T-PF（`CheckedNbtFileReadFaultMixin` 對 `quantumchamber_projections.dat` 注入一次）：`pendingReadbackFaultedOnce`、`ledgerUnconfirmed`、`noBlockWritten`（343 格都是 AIR）、`notRegistered`。mutation `mut-e-write-despite-unconfirmed-pending` FAIL |
| 第 13 組（ticket 與 WAITING）：PREPARE 中斷電 → ticket 釋放、回到 WAITING；恢復供電並由參與者觸發 → 回到 PREPARE，沒有第二個宇宙 | `CP:57` `derive` 回傳 `LEAVE_PREPARE` → `CS:473` `leavePrepare` 釋放 ticket | P-POWER：`cutBeforeThePreloadCompleted`、`ticketReleasedOnPowerLoss`、`backToWaitingWithOneInfo`、`stillCollapsing`、`noReentryWithoutAnEntryEdge`、`resumedWithTheSameTickets`、`projectedAfterTheEdge`、`resolvedOnlyOnce`、`oneAllocationEntry`。CST `collapsePhaseReportsThePrepareTransition`。mutation `mut-c-leave-keeps-ticket` P-POWER FAIL；`mut-b-leave-is-none` CST FAIL |

### controller 補充第 1–11 點

- **第 1 點（PREPARE 的步驟與順序）**：`CS:493` `prepareUniverse`＋`CS:547` `project`，每個 tick 依權威重新推導，可以在任一步中斷：
  1. 預載（第 2 點）。
  2. 退化檢查（`CS:509`）：預載已加 ticket 之後就查，不等預載完成（不需要 chunk）。
  3. 預載還沒全部 FULL：等下一個 tick。完成時記 INFO「目的世界預載完成…等了 N ticks」。
  4. `convergeIfNeeded(PROJECTION_LEDGER)`（`CS:550`），再 `CA.projection`：沒有成功（未確認、損壞、讀不到）就以結果帶的原因封存；`ABSENT` 時做 §6.5 的交叉檢查（`CS:557`）。
  5. §9.2 的衝突（`CS:570`）。
  6. `writeProjection(PENDING)`（`CS:576`）：沒有確認就封存，不寫方塊；確認之後以寫入結果登記到索引（`CS:584`，同一份 entry 不重複登記）。`CS:689` `writeMode`：`WRITTEN`／`UNCHANGED` → `FIRST_WRITE`，`ALREADY_COMPLETE` → `REPAIR`。
  7. 寫入器：WAIT／SEAL／PROCEED（`CS:677`）。`REPAIR` 寫完就是準備完成（不寫 ledger、不做 checkpoint，見「偏離」第 2 條）。
  8. 第一次寫入之後：`ChunkSaveCheckpoint.ready`（`CS:615`，未就緒等下一個 tick），`save`（`CS:620`，失敗 `PROJECTION_FAILED`）。
  9. 再驗不變量 cell（`CS:625`，見下方「第 7 步」）。
  10. checked 寫入 COMPLETE（`CS:643`），確認之後登記（`CS:648`），準備完成（`CS:657`）。
  - 任何 checked 寫入沒有確認都封存，不在未確認的狀態下寫方塊。PENDING 的落盤失敗：T-PF。
- **第 2 點（預載）**：見上表第 1 組。ticket 的釋放：閘門失效（`leavePrepare`）、進入 SEALED（`CS:812`，在 `seal()` 中、緊接著來源 ticket 的 `releaseOnSealed`）、塌縮流程自己的終局（`CS:959` `terminated`），以及其他路徑的終局（例如收尾的終局 API、F4b-3 的參與者關門、F5 的通道結束）：下一個 tick `CS:753` `releaseTerminatedDestinations` 看到 session 已不在 journal（current 與 flushed 都沒有）就釋放（`CS:224`）。F5 的釋放入口：`CS:772` static `releaseDestination(server, sid)`。超過 600 ticks 的 WARN：`CS:745`（併入第 6 組的等待）。
- **第 3 點（階段轉換與閘門失效）**：`CP:27` `Transition`（`NONE`／`ENTER_PREPARE`／`LEAVE_PREPARE`）、`CP:40` `Derivation`、`CP:57` `derive(view, releaseComplete, resolved, preparing, gate)`：呼叫端傳入「上一個 tick 是否在 PREPARE」，`gate` 不再有改 `track.preparing` 的副作用（`CS:903`）；`CS:401-409` 依轉換呼叫 `enterPrepare`／`leavePrepare`。`leavePrepare` 是 F4b-3 把門改回一般開門的轉換點（Javadoc 寫明）。閘門失效時已完成的 RESOLVE（`track.resolved`）與投影都保留；閘門再次成立時依 §7.1 的邊緣觸發續做（P-POWER）。`CollapsePhase` 以外的推導語意不變（`5e9b174` 之後 default focused 21/21，`green-b-focused`）。
- **第 4 點（READY stub）**：`CS:657` `prepared`：記 INFO「準備完成…投影=FIRST_WRITE／REPAIR（寫入 N 格）」；每個 session 在本 instance 只 WARN 一次「通道尚未實作（F5）…」（`Track.readyWarned`，帶 session）。之後的 PREPARE tick 直接 return（`CS:495`），不再重做；目的世界的 ticket 繼續持有。玩家照一般方式開關門（P-NEW `doorOpensNormally`／`doorClosesNormally`）。舊的 stub WARN「準備階段尚未實作（F4b）」與 `Track.prepareWarned` 已移除（`d5b059a`）。mutation `mut-d-ready-redoes`（沒有停點）P-NEW FAIL。
- **第 5 點（EXISTING 再次造訪）**：P-REVISIT：D1 的 COMPLETE 之後，interior 的石頭保留、損壞的 shell 與關上的 25 格門被修復（`投影=REPAIR（寫入 26 格）`），projection 檔的 bytes 不變、沒有 checkpoint。mutation `mut-d-revisit-first-write` FAIL。「COMPLETE 之後刪除 ledger」：P-DEGRADED（上表第 2 組）。Q-m8 的隔離：P-DEGRADED 在完成之前以 `SUP:718` `rebuildProjectionAuthority` 還原，並登記在 hooks（失敗路徑也會還原）。
- **第 6 點（SEALED 的原因與重試）**：PREPARE 的封存都經 F4a 的 `seal()`；原因直接採用權威或寫入器的結果：projection 的 `UNCONFIRMED`／`WRITE_FAILED` 是 `PROJECTION_FAILED`（F2b 的 slot 設定），損壞與 site 不符是 `AUTHORITY_CORRUPT`，讀不到是 `AUTHORITY_UNREADABLE`，交叉檢查與衝突是 `PROJECTION_CONFLICT`，寫入器與 checkpoint 的失敗是 `PROJECTION_FAILED`。重試語意沿用 `SealedReason` 與 F4a 的 `sealedRetry`：`PROJECTION_FAILED` 邊緣觸發＋退避（T-WF）；`PROJECTION_CONFLICT`、`AUTHORITY_CORRUPT` 永久（P-CROSS 的 `permanentNeverRetried`）；`AUTHORITY_UNREADABLE` 只在重啟之後。
- **第 7 點（fault mixin）**：
  - 投影寫入失敗：新增 `PWFM`（`ProjectionWriter.write` 的每個回傳點，把寫完的 WRITTEN 換成 WRITE_FAILED；`HK:67` `PROJECTION_WRITE_FAULT`、`HK:261`，`SUP:172` `installProjectionWriteFault`）。
  - checkpoint 失敗：新增 `CCPM`（`ChunkSaveCheckpoint.save` 的開頭；`HK:65` `CHUNK_CHECKPOINT_PROBE`、`HK:224`，`SUP:161` `installChunkCheckpointProbe`），同一個 hook 也是 P-NEW 的觀察探針。既有的都不能重用：`CheckpointPlatformFaultMixin` 改的是 `PlayerCheckpointStore` 的平台判斷；`ChunkCheckpointScopeFaultMixin` 綁在 `M4CandidateDoorGameTests` 私有的 H2 探針（`static volatile`，不是 Slot，M4C 不在允許路徑）；`MeasuredCheckpointFaultMixin` 只看 MEASURED 的 checkpoint。
  - PENDING 落盤失敗：重用 `CheckedNbtFileReadFaultMixin`（對 `quantumchamber_projections.dat` 的第一次讀取就是 PENDING 寫入的 atomic move 之後的讀回；`projection()` 只讀記憶體，暫存檔名稱不同），沒有新增。
  - 兩個新 mixin 都是 Slot、經 `installHook`，登記在 `quantumchamber-test.mixins.json`，只在 testmod（release JAR 的 testmod 命中 0，見「Gate 與證據」）。
- **第 8 點（G-NEW 的 witness 與 log 數字）**：G-NEW 改名，`onlyTheStubWarnAndNoError` 改寫成 `noWarnOrError(capture, sid)`＋`collapseFlowNeverWarned(capture, sid)`，另加 `waitingWithoutPower`、`noPrepareWithoutAnEntryEdge`（RESOLVE 之後第一次閘門評估時沒有供電 → WAITING、沒有目的世界的 ticket；恢復供電但沒有邊緣觸發，仍不進 PREPARE）。stub WARN 的斷言只有 G-NEW 有；W1、GT-P、GT-L 沒有斷言 stub WARN，改由 `resolvedUniverse` 在交出之前暫停 session（F4b-2 起 RESOLVE 之後會自己投影到同一個位置），所以它們不再有任何塌縮流程的 PREPARE log。`M5CollapseGameTests` 沒有 stub WARN 的斷言（`:1112` 只是 Javadoc 提到 F3d 的 RESOLVE stub），沒有修改。M5 run 的 WARN 逐則歸屬見「Gate 與證據」。
- **第 9 點（`leaseReady`）**：不加，理由見下方小節。F4b-2 自己的 checkpoint 一律先問 `ChunkSaveCheckpoint.ready`（`CS:615`）。
- **第 10 點（default run 的隔離）**：見下方「default run 的稽核」。
- **第 11 點（log 與訊息）**：新增的 log 都在 collapse package、都帶 `LOG_MARKER`（`GT` 每輪 PASS）；能歸屬到 session 的 WARN 都帶 `session=`：600 ticks 的等待、「通道尚未實作（F5）」、目的世界不是 ACTIVE（不應發生）。正常情況只記 INFO：進入 PREPARE、預載完成、checkpoint 之前的改動已修復、準備完成、閘門失效退回 WAITING、ticket 釋放（進入 SEALED、終局）。沒有新的玩家訊息：PREPARE 的封存沿用 F4a 的「量子通道無法開啟，已封存。」；spec §10.8 沒有 PREPARE 期間的訊息。

### 補充第 1 點第 7 步：COMPLETE 之前再驗不變量 cell

- 作法：checkpoint 成功之後、同一個 tick，以 `ProjectionWriter.write(…, REPAIR)` 再驗一次（`CS:625`）。寫了 0 格就寫 COMPLETE；寫了任何一格（PENDING 期間不變量 cell 不受保護，第一次寫入與 checkpoint 之間可能被改動）就記 INFO、不寫 COMPLETE，下一個 tick 重做 checkpoint 與這次驗證（`Prepare.written` 仍為真，不重寫整個 volume）；玩家擋住或 chunk 不是 FULL 時等下一個 tick；驗證或寫入失敗封存 `PROJECTION_FAILED`。
- （fix round 1，quality Q-I1 更正）上面的作法在再驗一直修復或被擋住時，每個 tick 都會存一次檔。fix round 1 在 checkpoint 之前另加一次同樣的再驗：修復了任何一格或被擋住都不存檔、下一個 tick 再驗，乾淨才存檔；存檔之後的再驗保留。兩處的修復都併入 600 ticks 的 WARN，「已修復」的 INFO 每一段 PREPARE 只記一次。見「Fix round 1」的 Q-I1 列。
- 選 REPAIR、不另加唯讀 API 的理由：
  - REPAIR 只寫與目標不同的不變量 cell；沒有損壞時一格都不寫、也不檢查玩家（要寫的格是空集合），所以它本身就是唯讀比對，而且與第一次寫入用同一份目標與驗證（含 Controller 的 block entity）。
  - 有損壞時，直接修好比只回報不符更好：不需要多一輪「發現 → 下一個 tick 重寫整個 volume」，interior 在 PENDING 期間被放的東西也不會被第一次寫入再清一次。
  - 不必改 `ProjectionWriter`（它只在需要唯讀 API 時才列入允許路徑）。
- 為什麼同一個 tick 內的「checkpoint → 驗證 → COMPLETE」沒有窗口：`ChunkSaveCheckpoint.save` 在 server thread 上序列化相關 chunks 並等原生 IO 完成，之後的驗證與 COMPLETE 的 checked 寫入都是同步的，中間沒有任何遊戲邏輯執行；驗證通過，就代表 checkpoint 存下的方塊也通過。checkpoint 之前的改動由「修復 → 下一個 tick 重做 checkpoint」涵蓋。
- 證據：P-NEW 以 checkpoint 探針在第一次 checkpoint 存檔之前把一格 shell 改成 AIR：INFO「已修復 1 格」一則、checkpoint 恰好兩次（兩次都看到 PENDING）、最後 343 格都是目標。mutation `mut-d-no-reverify`（拿掉再驗）FAIL：checkpoint 只有一次、那一格寫成 COMPLETE 時仍是 AIR。

### 補充第 9 點：`leaseReady` 不加 entity storage 的檢查

- F4a-0「觀察」第 2 條的前提不成立：`CorridorPageManager.leaseReady`（`CPM:1032`）呼叫的 `world.isChunkLoaded(packed)` 是 `ServerWorld.isChunkLoaded(long)`，它的實作就是 `entityManager.isLoaded(long)`（`ServerEntityManager.managedStatuses` 為 `LOADED`），與 `ChunkSaveCheckpoint.loaded` 的 entity storage 檢查（`manager.isLoaded(pos.toLong())`）是同一個判斷（javap：`f4b2/leaseready-javap-minecraft.txt`、`f4b2/leaseready-javap-cpm.txt`）。所以走廊 lease 的 chunks 早已要求 entity storage 已載入，再加一次是重複。
- 兩次 G-NLIVE 的「相關 chunk／entity storage 尚未完整載入」（`gate-f4a0-green-focused-…/latest.log:2234`、`gate-fix1-final-focused-…/latest.log:2218`）都緊接在走廊幾何完成、§11.3 請求保留式 recovery 之後。`MeasuredWorldSaveCheckpoint.save` 的相關範圍除了走廊 lease，還有**來源世界**的艙體 chunks，而 `leaseReady` 只看走廊世界；同一個 tick 內 `leaseReady` 剛確認過走廊的 chunks，所以剩下的可能是來源（Nether）的 chunks。這屬於 measured release 的呼叫端（`tickMeasuredRelease` 在 checkpoint 之前沒有對來源 chunks 問 `ChunkSaveCheckpoint.ready`），不在 `leaseReady`，也不在本 task 的允許路徑；依派工不再追查（仍列為已知偶發），交給後續（見「交給後續」）。
- M2、M4 的既有行為因此不變（沒有修改 `CorridorPageManager`）。

### default run 的稽核（補充第 10 點）

- F4b-2 只改 UNIVERSE 的 PREPARE（`collapsing()` 的 SOURCE 分支只多一個依推導結果記錄 `track.preparing` 的步驟，`5e9b174` 起行為不變）。UNIVERSE 要走到 PREPARE，必須先在 RESOLVE 確定目的地；F4a 的稽核（F4a「default run 的稽核」）已逐一列出 default run 中沒有任何 test 走到 RESOLVE，F4b-1、F4b-2 都沒有新增或修改 default run 的 `@GameTest`（`M5CollapseGameTests`、`M4CandidateDoorGameTests` 沒有改）。所以 default run 沒有 test 會進入 UNIVERSE 的 PREPARE。
- 新的 testmod mixin 在 default run 也會載入，但沒有安裝 hook 時不做任何事（`CCPM` 對走廊清理的 MEASURED checkpoint 也只是呼叫空的 Slot）。
- 實證：run-all 的 Full（default run）boundary oracle PASS；Full 的 latest.log 沒有任何「進入 PREPARE」「目的世界預載完成」「準備完成（預載與投影都已完成）」「通道尚未實作（F5）」「quantumchamber_destination」的行；Full 的 WARN／ERROR 與基準逐行比對見「Gate 與證據」。

### 測試

- JUnit 626 → 631（＋5）：CST 14 → 17（`collapsePhaseReportsThePrepareTransition`、`preloadCoversTheChamberBoxPlusTwoBlocks`、`prepareDecisionsFollowTheWriterAndLedgerResults`；`collapsePhaseFollows…` 改用新的 `derive`）、PPIT 10 → 12（Q-m4、Q-m6；Q-m3 補斷言、更新入口的 test 改用寫入結果）。`GT` 2 不變。
- M5 GameTest 17 → 28：上列 11 個新 test，各自一個 batch，catalog 增量各 1；G-NEW 改名（batch 不變，數量不變）。suite 結束時 16 → 27 筆。
- default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 不變：沒有新增或刪除 default run 的 `@GameTest`。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b2/`（以下簡稱 `f4b2/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`adapted-junit.ps1`、`chain.ps1`、`wbcmp.py`、`jarcmp.py` 由 `f4b1/`（`jarcmp.py` 由 `f4b1/fix1/`）複製，只改註解；`adapter.py` 的 adapter 表改放在 `f4b2/adapters_f4b2.py`。每個 adapter 跑完都還原，還原後的 blob 與套用前相同；每次 RED、GREEN、mutation 的 state 記 HEAD、porcelain 與逐檔 blob（`f4b2/<label>-state.txt`）。gate root 在 `.superpowers/verification/`。

| 輪次 | HEAD／工作樹 | 證據 | 結果 |
| --- | --- | --- | --- |
| Step 0（PPI） | `97df474`＋Step 0 的檔 | `green-0a`（JUnit）、`s0-mut-q4-*`、`s0-red-q3-stack-twice`、`s0-mut-q6-controller-chunk-only`；M5 subset `gate-green-0a-m5-de126980a5194d7da0650c301c23ab0b` | JUnit PPIT 12／CST 14／`GT` 2 PASS；RED 與 3 個 mutation 都只有目標 test FAIL；GT-P、GT-L 2/2 |
| Step 0（Q-m9、Q-m5） | `62d535c`＋`M5U` | `gate-qm9-before-b0299bb351fa4c55b4387f394a38dca7`、`gate-qm9-after-9ba7ea78dfd845d2a467ac136903b00f`（Inventory）；`gate-green-0b-m5-e19694419d954c91b024ce5815b3b839` | 192 個 `@GameTest`，差異只有 tickLimit；M5 17/17 |
| 階段轉換 | `2924c8b`＋`CP`、`CS`、`CST` | `green-b3`、`mut-b3-leave-is-none`；default focused `gate-green-b-focused-88fb64fdbf3046d888f41aa20c05a2d0` | CST 15 PASS；mutation FAIL；21/21 |
| 預載 | `5e9b174`＋C 的檔 | `green-c`；`gate-green-c-m5-d58902c673f5457891859d4b0e06f648`；`gate-green-c-focused-df3cb7fb53954ddf9291987186ea2f5e`；`chain-c.log` | CST 16 PASS；M5 19/19；default 21/21；RED `red-c-universe-gate-shut` 與 3 個 mutation FAIL |
| 投影 | `d5b059a`＋D 的檔（＝`9c8c986`） | `green-d`；`gate-green-d-m5-c7646ac0b00040a3a91efe188544836f`；`chain-d.log`、`chain-d2.log` | CST 17、PPIT 12 PASS；M5 21/21；RED `red-d-no-projection` 與 mutation FAIL，`mut-d-wait-warns-every-tick` 存活 → `c87ae77` 之後 FAIL（`gate-mut-d2-wait-warns-every-tick-6c52022c736c4e1fa87aab3272958206`；GREEN `gate-green-d2-blocked-7e650455289c478f8b9c391ee5c2f1cc`） |
| 失敗與 SEALED | `c87ae77`＋E 的檔（＝`49e665e`） | `gate-green-e2-m5-165bec1a8e2043e7bdff6be1f2ad3f1d`；`chain-e.log` | M5 27/27；7 個 mutation FAIL |
| 字句 | `49e665e`＋`CS`（＝`b8b2b54`） | `green-w`；`gate-green-w-power-d38c9f41a6254bb29689e977ccd2ee72` | CST 17 PASS；P-POWER PASS |
| 寫入失敗 | `b8b2b54`＋F 的檔（＝`c67663c`） | `gate-green-f-m5-4068cd10603e4e33b60af25d12e63524`；`chain-f.log` | M5 28/28；3 個 mutation FAIL |
| 最終 focused | `dc11821`，工作樹乾淨 | `final-focused`（JUnit）；`gate-final-default-focused-fdf71a0c4d224c5b9fbdc63223938fbc` | CST 17、PPIT 12、`GT` 2 PASS；default 21/21 |
| run-all | `dc11821`，工作樹乾淨（`STATUS` 是空的） | `run-all-3b4d8e88f93645ec8b4d9383c7e40094`（Full：`gate-full-4ebf4e7402264398bb1c90de81f6d1a0`；M5：`gate-m5-gametest-2674523120424fbd990f8a355f588eda`；`f4b2/run-all-console.txt`） | `END ALL exit=0 HEAD=dc11821`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=28/28 perTest=51+1 junit=631 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=399entries/352classes sources=216 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- RED 與 mutation 的對照（adapter → gate root 的 label 相同，`f4b2/<label>-summary.txt`）：
  - commit C：`red-c-universe-gate-shut`（P-NEW、P-POWER 逾時在「RESOLVE 之後沒有進入 PREPARE」）、`mut-c-blocking-preload`（`preloadWaitedAtLeastOneTick=false`）、`mut-c-leave-keeps-ticket`（`ticketReleasedOnPowerLoss=false`）、`mut-c-no-terminal-sweep`（P-NEW 的完成條件 `ready=false`：終局之後 ticket 沒有釋放）。
  - commit D：`red-d-no-projection`（P-NEW、P-REVISIT 逾時在「沒有準備完成」）、`mut-d-complete-before-checkpoint`、`mut-d-no-reverify`（`checkpointedTwiceBothWhilePending=false`、`windowDamageRepairedBeforeComplete=false`）、`mut-d-ready-redoes`（`holdsAtTheStub=false`）、`mut-d-revisit-first-write`（`interiorKept=false`）、`mut-d-blocked-seals`（等不到 600 ticks 的 WARN）、`mut-d2-wait-warns-every-tick`（`stillOneWarnWhileBlocked=false`）。
  - commit E：`mut-e-no-cross-check`、`mut-e-no-overlap-conflict`、`mut-e-skip-site-validation`（三者都逾時在「沒有進 SEALED」）、`mut-e-no-degradation-check`（`onePermanentAdminError=false`，見「觀察」第 1 條）、`mut-e-write-despite-unconfirmed-pending`（`noBlockWritten=false`）、`mut-e-complete-despite-checkpoint-failure`（逾時在「沒有進 SEALED」）、`mut-e-seal-keeps-destination`（T-PF `ticketsReleased=false`；同一輪的 T-CF 因 T-PF 失敗而在 `begin` 的衛生檢查失敗，所以另以 `mut-f-seal-keeps-destination-checkpoint` 單獨跑 T-CF：`ticketsReleased=false`）。
  - commit F：`mut-f-write-failure-waits`（逾時在「沒有進 SEALED」）、`mut-f-cross-check-with-pending`（重試封存成 `PROJECTION_CONFLICT`，逾時在「退避之後的重試沒有準備完成」）。
- 不採計的 root：
  - `green-b`、`green-b2`（JUnit）：新的 CST 列 fixture 寫錯（`returned(settled, A)` 沒有造出未返還的人；`cleared` 一個有未返還參與者的 record 違反收據不變量），不是 production 的問題；修正 fixture 之後 `green-b3`。同一份錯 fixture 上的 `mut-b-leave-is-none`、`mut-b2-leave-is-none` 也不採計，以 `mut-b3-leave-is-none` 為準。
  - `gate-green-e-m5-2de88247e9694afdbd41934519aff835`（M5 25/27）：P-OVERLAP、P-SITE 的「沒有寫投影方塊」witness 用了 `controllerPresence == ABSENT`，但封存之後目的世界的 ticket 已釋放、chunk 可能已卸載，查詢回 `NOT_LOADED`；`gate-diag-e-m5-9a1420d07a2f49ee852794a12483bb06` 加印診斷確認 218 格都不是目標（`matchedShell=[]`）。改成讀方塊狀態之後 `green-e2-m5` 27/27。
  - `mut-d-wait-warns-every-tick`（`gate-…-6e19067b90a040308994abd8429f072b`，存活）：P-BLOCK 在 WARN 出現的那一個 tick 就讓 Q 離開，每個 tick 都記 WARN 也看不出來；`c87ae77` 修正 test，之後的 `mut-d2-…` FAIL。

- 與基準 run-all（`run-all-7bab6785434347c4ab75c5d21a34c4b0`，`a928216`）逐項比較：
  - JUnit 626 → 631、M5 17 → 28（「測試」）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24、mainOnly、CI 兩個平台都不變。M5 oracle PASS（`m5-oracle.json`）：28 個 test 的 catalog 增量逐一串接（`existing_receipt_without_a_discovered_universe_seals_permanently` 為 0，其餘各 1），每個都 converged、preserved，suite 結束時 27 筆。`M3_UNIVERSE_STOPPED_VERIFIED` 的 worlds 10 → 21（`M3_UNIVERSE_LOAD`／`UNLOAD` 各 10 → 21，多出的 11 與新增的 test 數相同；沒有逐一對應到 test），`quarantine=0`、`contextDetached=true` 不變。
  - release JAR 393 → 399 entries（346 → 352 classes，`f4b2/jarcmp-release-run-all.txt`）：新增 `CollapsePhase$Derivation`、`CollapsePhase$Transition`、`CollapseService$DestinationTicket`、`CollapseService$Prepare`、`CollapseService$WriteStep`，以及 `ProjectionProtectionIndex$1`（javac 為 `confirmed` 對 `WriteResult.Status` 的 switch 產生的 switch map）；沒有移除。內容不同的 class 都屬於本 task 改過的 3 個 production 檔（`CollapsePhase`、`CollapseService`、`ProjectionProtectionIndex` 與它們的 nested class）。其中 `CollapseService$1`（switch map 多了 `Transition`、`ControllerPresence`、`ProjectionWriter$Status`、`WriteResult$Status`）與 `CollapseService$Track`（`prepareWarned` 換成 `prepare`、`readyWarned`）的 bytecode 不同；其餘 nested class 以 `javap -p -c` 比對 bytecode 相同，差異只在行號表（`f4b2/jar-attribution.txt`）。testmod 命中 0。
  - sources JAR 216 不變（`f4b2/jarcmp-sources-run-all.txt`）：內容不同的是 `CollapsePhase.java`、`CollapseService.java`、`ProjectionProtectionIndex.java`。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`f4b2/wbcmp.py`）：Full 54／28 不變、逐行相同（`f4b2/wbcmp-full-run-all.txt`）；legacy 0／0（`wbcmp-legacy-run-all.txt`）。M5 WARN 17 → 25、ERROR 1 → 6（`wbcmp-m5-gametest-run-all.txt`）：
    - 移除的 WARN 5 則：PREPARE stub「準備階段尚未實作（F4b）」（G-NEW、G-EXIST 的 D2、W1、GT-P、GT-L 各一）。M-1 的原版 WARN（GT-P 的 `PistonBlockEntity … does not allow it`）仍是一則，排序比對中出現一減一加只是 object hash 不同。
    - 新增的 WARN 13 則：「通道尚未實作（F5）」7 則（P-NEW、P-POWER、P-REVISIT 的 D1 與 D2、P-BLOCK、P-DEGRADED 的 D1、T-WF 的重試）；600 ticks 的等待 1 則（P-BLOCK）；SEALED(`PROJECTION_FAILED`) 3 則（T-PF、T-CF、T-WF 的受控失敗）；`CA` 的「PROJECTION_LEDGER 的 checked 寫入未確認」1 則（T-PF）；`PPI` 的退化 WARN 1 則（P-DEGRADED）。
    - 新增的 ERROR 5 則：`CA` 的「PROJECTION_LEDGER 的檔案在執行期間消失」1 則（P-DEGRADED）；SEALED(`AUTHORITY_CORRUPT`) 2 則（P-DEGRADED 的 D2、P-SITE）；SEALED(`PROJECTION_CONFLICT`) 2 則（P-CROSS、P-OVERLAP）。
    - 17 − 5 ＋ 13 ＝ 25；1 ＋ 5 ＝ 6。每一則都是 test 刻意造成、而且有 witness 斷言的（P-NEW 等的「通道尚未實作」由 `readyStubWarnings` 斷言恰好一則）。
  - default run 的稽核（上方「default run 的稽核」）：Full 的 latest.log 對「進入 PREPARE」「目的世界預載完成」「準備完成（預載與投影都已完成）」「通道尚未實作（F5）」「quantumchamber_destination」「準備階段尚未實作」「目的地已確定」都是 0 行。
- 本 task 沒有遇到派工列出的已知偶發（G-NLIVE、Windows journal 的 `AccessDeniedException`、H2 多出的原生 ERROR／WARN、`m2_task4_disconnect_io` 多一則 WARN、`unloadChunks` 停服卡住）；失敗的 root 都是刻意的 RED 或 mutation，以及上列「不採計的 root」。

### 偏離 plan／spec 字面之處

1. **退化檢查的位置**：controller 的順序是「1. 預載 → 2. 退化」。本實作在預載加上 ticket、查詢之後就查退化，不等預載完成（`CS:509`）：退化不需要 chunk，提早封存就不必等 chunk 載入，也不會碰 ledger。預載完成之後的步驟順序與 controller 的相同。
2. **修復（`ALREADY_COMPLETE`）不做 checkpoint、不寫 ledger**：spec §9.2 的「之後（COMPLETE）」只要求修復不變量 cell 並把門設回開啟；checkpoint 與 COMPLETE 是第一次寫入「讓 COMPLETE 成立」的條件，ledger 已是 COMPLETE。修復之後、原生存檔之前 crash，修復可能遺失，下一次造訪會再修。
3. **準備完成之後不再查退化**：退化 WARN 與 spec §9.3「所有新的 PREPARE 都進 SEALED」一致的讀法是「還沒完成準備的 PREPARE」；準備完成是 F5 提交 PASSAGE_READY 的前一刻，F5 之後 PASSAGE_READY 本來就不受退化影響。
4. **預載 ticket 的 level**：spec 只說「直到全部到達 FULL」。用 radius 0（level 33＝FULL）：剛好讓預載範圍到達 FULL，不讓它們 tick；entity storage 在 FULL（tracked）時也會載入，checkpoint 另外問 `ready`。
5. **其他路徑的終局在下一個 tick 才釋放目的世界的 ticket**：塌縮流程自己的終局（`terminated`）當場釋放；別的路徑（收尾的終局 API、之後 F4b-3／F5 若不經 `terminated`）由 `releaseTerminatedDestinations` 在下一個 tick 看到 session 已不在 journal 時釋放。
6. **600 ticks 的 WARN 從進入 PREPARE 起算，每一段 PREPARE 一次**：預載、玩家擋住寫入、checkpoint 未就緒、交叉檢查的 chunk 不是 FULL 都併入同一個時鐘（補充第 1 點第 5、6 步）；離開 PREPARE 再進入時重新起算。
7. **`CollapsePhase.derive` 的簽名換成回傳 `Derivation`**：舊的 `derive(…, BooleanSupplier)` 刪除（只有 `CS` 與 `CST` 使用）。
8. **G-NEW 改名**（batch 不變）：F4a 的名稱描述的 PREPARE stub 已不存在。
9. **P-DEGRADED 是同一個 instance 內的遺失**（執行期間刪檔、下一次 checked 寫入發現），不是重啟之後的初始化判定；後者屬 F6 的重啟矩陣（「交給後續」）。
10. **commit 拆分**：plan Step 4 是一個 commit `feat: project the chamber into the destination universe`；依派工依子主題拆成上列 code commit 與 docs commit。
11. **site 驗證排在 §9.2 的衝突之後**（`CS:570` 的衝突、`CS:576` 的 `writeProjection(PENDING)`；fix round 1 補，spec review S-M4）：派工的順序是 site 驗證在衝突之前。site 驗證是經 `writeProjection(PENDING)` 取得的，新 key 時它本身就是 PENDING 的 checked 寫入；衝突時不得寫 PENDING（P-OVERLAP 的 `noPendingForThisChamber`），所以衝突必須先判。既有 entry 時 `writeProjection` 不寫入（UNCHANGED／ALREADY_COMPLETE／CORRUPT），順序只影響「site 不符、又與另一座艙的 COMPLETE 相交」時回報哪一個原因（`PROJECTION_CONFLICT` 或 `AUTHORITY_CORRUPT`）：兩者都是永久原因，都不寫方塊。

### 裁示

首輪列為「需要裁示」的三項，以及「偏離」第 1 條（退化檢查提早到預載加上 ticket 之後），spec review 都判定符合 spec，controller 正式接受（fix round 1 的派工）。第 4 條是 fix round 1 的 controller 裁定。

1. 重建 hook（`SUP:718`）以反射清除 `CollapseAuthorities` 的 projection slot 的 `lost` 與 `ProjectionProtectionIndex` 的 `degraded`（只在 testmod、只由 P-DEGRADED 使用）。這是 Q-m8 列出的兩個選項之一（batch 順序不固定，「排在最後」不可行）；若不接受反射，替代作法是 P-DEGRADED 只做到「刪檔 → 修復仍保留 interior」而不觸發遺失（不能證明 `AUTHORITY_CORRUPT` 與退化），或改由 F6 的重啟矩陣涵蓋。
2. 「偏離」第 2 條（修復不做 checkpoint）與第 3 條（準備完成之後不再查退化）是 spec 沒有寫明的讀法。
3. 補充第 9 點的結論：`leaseReady` 不需要改；G-NLIVE 的偶發可能出在 measured release 沒有對來源世界的 chunks 先問 `ChunkSaveCheckpoint.ready`，需要指定一個允許修改 `CorridorPageManager.tickMeasuredRelease`（或 `MeasuredWorldSaveCheckpoint`）的 owner（fix round 1 的派工交給 F6，見「交給後續」）。
4. （fix round 1，quality Q-M1）projection 的查詢回 `UNCONFIRMED` 時改為等待（`prepareWait`），不封存；只有本 session 自己的 `writeProjection` 失敗才封存。依據：spec §6 收斂規則第 2 點（未確認期間只重試收斂，不做新決策），以及 F4a 對 catalog／discovery 未確認一律等待（G-WAIT）。處置見「Fix round 1」。

### 觀察（未修改）

1. **退化檢查與權威的遺失閘門重疊**：執行期間遺失時，`CA.projection()` 本身就因 `lost` 回 `CORRUPT`，所以拿掉 `CS:509` 的退化檢查，P-DEGRADED 的 D2 仍以 `AUTHORITY_CORRUPT` 封存（mutation `mut-e-no-degradation-check` 是靠 ERROR 的 cause 抓到的）。退化檢查另外涵蓋索引自己 fail closed（建立或同步時的非預期例外，權威本身健康）的情況，而且在預載完成之前就封存。
2. **GameTest server 上的預載時間**：新宇宙的 1–4 個 chunk 到 FULL 要 85–264 tick（P-NEW、P-POWER、P-BLOCK 等的 INFO），GameTest server 不以 20 TPS 節流。
3. **封存之後目的世界的 chunk 很快卸載**：ticket 釋放之後，`getWorldChunk` 可能回 null；test 讀方塊狀態（會同步載入）而不是用 `controllerPresence`（「不採計的 root」第 2 條）。
4. **G-EXIST 的 D2 會進入 PREPARE 一個 tick**：RESOLVE 之後的下一個 tick 閘門成立、加 ticket，同一個 tick test 就以終局 API 收尾，下一個 tick 釋放 ticket（只有 INFO）。F4a 的其他 test 都在偵測到 RESOLVE 完成的那個 tick 收尾，沒有進入 PREPARE。
5. **「目的世界不是 ACTIVE」的防守分支沒有 GameTest**（`CS:497-505`）：動態世界在執行期間不卸載，造不出這個狀態；推導回到 RESOLVE 的路徑由 CST 的轉換列（`PREPARE 中目的地不再確定`）涵蓋。
6. **修復也會被玩家擋住**：玩家站在一格損壞的不變量 cell 裡時，修復（含 checkpoint 之後的再驗）等下一個 tick；站在 interior 或已是目標的門口不擋（F4b-1 W2 的寫入器行為）。

### 交給後續

- F4b-3：
  - `CS:493` `prepareUniverse` 的每個 PREPARE tick 加上「套用量子膜的條件」的判斷（Javadoc 已標出位置）；`CS:473` `leavePrepare` 是閘門失效時把門由 sealed 改回一般開門的轉換點。準備完成（READY stub）之後的 tick 也要維持門的狀態。
  - PREPARE 期間參與者關門的終局：若在 `CollapseService` 內經 `terminated()`，目的世界的 ticket 當場釋放；其他路徑由下一個 tick 的 `releaseTerminatedDestinations` 釋放。
  - Q-m1（`chamber`↔`collapse` 互相依賴）不變。
  - （fix round 1，quality Q-M4）量子膜判斷的插入點：`prepareUniverse` 中 `if (progress.ready) return;` 之前（fix round 1 之後的 `CS:502`，Javadoc `CS:497`）。
  - （fix round 1，quality Q-M4）PREPARE 中 SEALED 的寫入被拒（REJECTED）與 session 被隔離時，本輪決定不釋放目的世界的 ticket（理由見「Fix round 1」的 Q-M4 列、`CS:815`、`CS:251`）。F4b-3 加上門的攔截之後若要在嘗試進入 SEALED 時就釋放，要能以測試造出被拒的路徑；若要在隔離時釋放，要補上解除隔離之後（含 READY stub 之後）重新加 ticket 的路徑。
  - （fix round 1，quality Q-M5）`M5UniverseGameTests` 的 F4b-2 test 前後段重複（`DormantOrigin.open`＋`asTestFailure`、`collapsedToUniverse`、收尾的 `hooks.release`＋`awaitConvergedThenComplete`，約 10 行 × 13 個）。F4b-3 要新增 test，可以先抽成 helper。
  - （controller 補，fix round 1 spec re-review 的 Minor 1）REJECTED（`pendingSeal`）與隔離期間，`collapsing()` 在推導之前就 return，看不到閘門失效。F4b-3 套上 sealed 量子膜之後，這兩段期間門也必須能改回一般開門（§6 spec:260、§7.3 :392-394）。不能只處理 ticket。
  - （controller 補，fix round 1 quality re-review 的 Nit）F4b-3 的 Step 0 一併處理：
    - `CollapsePhase.Gate#edge` 的 Javadoc 只寫契約：評估會消耗一次邊緣觸發；
    - `prepareUniverse` 的 lazy `Prepare` 建立已經走不到，改成 `requireNonNull`。
- F5：
  - `CS:657` `prepared` 的停點換成 checked PASSAGE_READY（含 §10.2 的 EXISTING 再驗）；屆時移除「通道尚未實作（F5）」WARN 與 `Track.readyWarned`。
  - PASSAGE_READY 期間繼續持有目的世界的 ticket（`releaseTerminatedDestinations` 只在 session 不在 journal 時釋放，PASSAGE_READY 仍在 journal）；通道結束時呼叫 `CS:772` `releaseDestination(server, sid)`（或 `CollapseService` 內的同名 instance 方法）。
  - （fix round 1，spec S-M1）預載外擴 2 格，可能比 `UniverseTransferService:279-282` 的 FULL 檢查少 1 格。實作 `PassageTransferPolicy` 時讓兩邊的 chunk 推導一致（例如 margin 改成 3），並交 F7 寫 spec errata。
  - （fix round 1，spec S-M2）修復路徑（`ALREADY_COMPLETE`）沒有 checkpoint：READY 之前，對 `written > 0` 的修復補一次 `ChunkSaveCheckpoint.ready`＋`save`，或由 F6 驗證。
  - （fix round 1）`releaseDestination(server, sid)`（fix round 1 之後的 `CS:800`）目前沒有呼叫端、也沒有測試，F5 接上時補測試。
  - （controller 補，fix round 1 quality re-review m-1）F5 的 Step 0：`CollapseService` 已有 1462 行，F4b-3 還會再加。F5 開工時先評估是否把 PREPARE（`Prepare`、`destinations`、預載與投影）抽成 package-private 類別。若決定抽，以獨立 commit 完成，行為不變。
- F6：
  - 重啟矩陣：W4（PENDING 寫到一半）、W4b（寫完、驗證過、checkpoint 之前）、W6（COMPLETE 之後、READY 之前）；開服時投影 ledger 在 schema 4 下遺失 → 初始化 `LOST`／`AUTHORITY_CORRUPT`、COMPLETE 投影的 interior 不被重寫（P-DEGRADED 的跨 JVM 版本）。重啟之後 `Track` 是新的：PREPARE 從預載重新推導（PENDING 照第一次寫入、COMPLETE 只修復）。
  - `build.yml` 的明列清單：本 task 新增 M5 run 的 11 個 test（都不是 `_windows`），G-NEW 改名為 `dormant_new_receipt_resolves_without_power_then_waits_for_an_entry_edge`。
  - 補充第 9 點：measured release 的來源 chunks 沒有先問 `ChunkSaveCheckpoint.ready`（見「裁示」第 3 條）；G-NLIVE 的偶發仍列為已知偶發。（fix round 1）派工的允許路徑要明列 `CorridorPageManager.tickMeasuredRelease` 或 `MeasuredWorldSaveCheckpoint`。
  - （fix round 1，spec S-M2）重啟矩陣的 W6／W7 加上 EXISTING 再次造訪的版本（修復之後、READY 之前重啟）。
  - （fix round 1，quality Q-M5）`M5UniverseGameTests` 拆檔時要一併改 `gates.ps1:242-243`（M5 run 的 class）與 `fabric.mod.json`（gametest entrypoint）。
  - （fix round 1）`build.yml` 的明列清單另加 fix round 1 的 2 個 test：`checkpoint_waits_for_a_clean_recheck_while_the_invariant_cells_keep_changing`、`prepare_waits_while_another_projection_write_is_unconfirmed`（都不是 `_windows`）。
  - （fix round 1，觀察）`gates.ps1` 只以 PID 從 `run/gametest` 挑本次的 `m4-boundary-*.json`；那裡累積了 179 份舊 receipt，fix round 1 有兩次 default gate 因舊檔的 PID 與本次 server 相同而判 FAIL（「Fix round 1」的不採計 root）。harness 可以另以 startup nonce 或時間篩選。
- F7：
  - spec errata：§9.1 預載 ticket 的 level（FULL）與「其他路徑的終局在下一個 tick 釋放」；§9.2 修復不做 checkpoint、不寫 ledger；§9.3「新的 PREPARE」＝還沒完成準備的 PREPARE（含進行中的）；§7.3 閘門失效的第 2 步（門）屬 F4b-3。（fix round 1）§9.1 的預載外擴（S-M1，見 F5）。
  - README「預期結果」的數字以最新的 run-all 為準更新（fix round 1 之後：M5 30、JUnit 631、release 401／354 等）。

### Fix round 1（`de3f56d`、`726c7e0`、`aea4b07`、`c3c7ea8`、`bfaf6f0`、`b6fdf59`、`1f41d0a`、`97f1898`）

- Review：spec review ✅（Critical 0、Important 0、Minor 5：S-M1～S-M5，Nit 1）；quality review Not approved（Critical 0、Important 1、Minor 6、Nit 3：Q-I1、Q-M1～Q-M6）。三項「需要裁示」與「偏離」第 1 條（退化檢查提早），spec reviewer 都判定符合 spec，controller 正式接受（「裁示」）。controller 的 fix round 1 派工處理 Q-I1、Q-M1～Q-M6、S-M3～S-M5、Nit 與 note 的交接；S-M1、S-M2 交給 F5／F6／F7（「交給後續」）。開工 HEAD 是 `3e7cf1d`，worktree 乾淨。
- Commits（依序；只 stage 自己的檔案）：
  - `de3f56d` `refactor: route projection writer results through one helper`（parent `3e7cf1d`）：Q-M5。只有 `CS`。
  - `726c7e0` `refactor: let the phase derivation choose how the PREPARE gate is evaluated`（parent `de3f56d`）：Q-M6。`CP`、`CS`、`CST`。
  - `aea4b07` `fix: checkpoint the destination projection only after a clean recheck`（parent `726c7e0`）：Q-I1。`CS`；testmod `M5U`（新增 P-RECHECK）。
  - `c3c7ea8` `fix: wait instead of sealing while the projection ledger is unconfirmed`（parent `aea4b07`）：Q-M1。`CS`；testmod `M5U`（新增 P-UNCONF）。
  - `bfaf6f0` `fix: re-prime the entry edge snapshot when PREPARE is left`（parent `c3c7ea8`）：Q-M2。`CS`；testmod `M5U`（P-POWER 加第二位參與者、`collapsedToUniverse` 的 `others` 版本）。
  - `b6fdf59` `docs: state how PREPARE resumes and where F4b-3 hooks into it`（parent `bfaf6f0`）：Q-M3、Q-M4。只有 `CS` 的 Javadoc、註解與一則 WARN 的字句。
  - `1f41d0a` `test: import the F4b-2 GameTest hook types and name the reflected fields as mod fields`（parent `b6fdf59`）：Nit。testmod `HK`、`SUP`、`M5U`。
  - `97f1898` `docs: note the tick-limit exception to the projection isolation rule`（parent `1f41d0a`）：S-M5。`scripts/verification/README.md`「新增 M5 GameTest」一節（另補 `collapsedToUniverse` 的 `others` 版本與未確認寫入的一點）。
  - 本小節由其後的 docs commit `docs: record F4b-2 fix round 1 in the M5 note` 加入；同一個 commit 也在本節的 Review 行、「補充第 1 點第 7 步」、「偏離」第 11 條、「裁示」、「交給後續」加註 fix round 1。
- Code range `3e7cf1d..1f41d0a`。沒有改 `ProjectionProtectionIndex`、`ProjectionWriter`、`CollapseAuthorities`、`CorridorPageManager`、mixin 與 `quantumchamber-test.mixins.json`、語言檔、`gates.ps1`、`build.gradle`、`.github/`，也沒有改派工列為不得修改的檔案。
- 本小節的行號以 `97f1898` 為準。證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b2/fix1/`（以下簡稱 `fix1/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`adapted-junit.ps1`、`chain.ps1`、`adapter.py`、`wbcmp.py`、`jarcmp.py` 由 `f4b2/` 複製，只改 repo 的相對路徑與註解；adapter 表在 `fix1/adapters_fix1.py`。`f4b2/` 下既有的檔案沒有改動。每個 adapter 跑完都還原，還原後的 blob 與套用前相同；每個 commit 的 blob 都等於它之前最後一輪 GREEN 的 state 記下的 blob（`fix1/<label>-state.txt`）。
- 新增的 2 個 `@GameTest`（M5 run，各自一個 batch，名稱不以 `_windows` 結尾，各平台都執行，catalog 增量各 1）：
  - P-RECHECK `M5U:1973` `checkpoint_waits_for_a_clean_recheck_while_the_invariant_cells_keep_changing`（`m5_universe_prepare_recheck`，tickLimit 1500）
  - P-UNCONF `M5U:2052` `prepare_waits_while_another_projection_write_is_unconfirmed`（`m5_universe_prepare_unconfirmed`，tickLimit 1200）

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q-I1（Important）：checkpoint 之後的再驗持續修復或被擋住時，每個 tick 都存一次檔 | `CS:624`：第一次寫入之後、`ChunkSaveCheckpoint.save`（`CS:631`）之前，先以 REPAIR 再驗。修復了任何一格（`CS:627`）或被擋住（`proceeds` 的 WAIT）都不存檔、下一個 tick 再驗；乾淨才存檔。存檔之後的再驗保留（`CS:636`）。兩處的修復都經 `CS:656` `repairedBeforeComplete`：「已修復」的 INFO 每一段 PREPARE 只記一次（`Prepare.repairLogged`，`CS:1388`），並經 `prepareWait` 併入 §9.1 的 600 ticks WARN（「正在等：投影的不變量 cell 在第一次寫入之後一直被改動…」）。P-RECHECK：第一次 checkpoint 存檔之前（探針）把門板格 D＝`local(3,1,0)` 改成 AIR；之後每個 test tick 都改 D，直到進入 PREPARE 之後 610 ticks；接著讓 Q 站在被改成 AIR 的 D 上 5 個 tick；Q 離開之後完成。witness：`noCheckpointWhileChanging`、`oneRepairInfoWhileChanging`、`oneWaitWarnNamingTheChange`、`stillPendingWhileChanging`、`noCheckpointWhileBlocked`、`secondCheckpointAfterACleanRecheck`、`everyCellIsTheSpecTarget`、`complete`、`stillOneRepairInfoAndOneWaitWarn`、`onlyTheWaitAndReadyStubWarnsAndNoError` | RED `red-c2-recheck`（`726c7e0`＋P-RECHECK）：checkpoint 577 次、「已修復」INFO 572 則，改動期間沒有 WARN（唯一的 WARN 出在 Q 擋住的 WAIT 分支），`noCheckpointWhileChanging`、`oneRepairInfoWhileChanging`、`oneWaitWarnNamingTheChange`、`noCheckpointWhileBlocked` 為 false。GREEN `green-c-recheck`（P-RECHECK、P-NEW）：checkpoint 恰兩次（第一次與 Q 離開之後那一次），WARN 在進入 PREPARE 之後 600 ticks 出現。mutation（`chain-c.log`）都 FAIL：`mut-c-recheck-wait-saves`（被擋住也存檔：`noCheckpointWhileBlocked=false`）、`mut-c-repair-info-every-time`（INFO 不去重：`oneRepairInfoWhileChanging=false`）、`mut-c-repair-no-wait`（不經 `prepareWait`：`oneWaitWarnNamingTheChange=false`）、`mut-c-no-post-checkpoint-verify`（F4b-2 的 `mut-d-no-reverify` 在本輪的版本，P-NEW：`checkpointedTwiceBothWhilePending=false`、`windowDamageRepairedBeforeComplete=false`，存檔之後的再驗仍有效） |
| Q-M1：projection ledger 未確認時，其他 session 的 PREPARE 也被封存 | controller 裁定：`CS:564` 查詢回 `UNCONFIRMED` 時 `prepareWait`（「projection ledger 有尚未確認的寫入（收斂之前不做新決策）」）、return，不封存；只有本 session 自己的 `writeProjection` 失敗才封存（PENDING、COMPLETE 照舊）。P-UNCONF：Q 擋住第一次寫入；本艙的 PENDING 確認之後，test 替另一座（不存在的）艙在 U 遠處 checked 寫入一筆 PENDING，讀回受控失敗一次 → 權威未確認（退避 200 tick）。witness：`otherWriteUnconfirmed`、`lookupUnconfirmed`、`noSealWhileUnconfirmed`、`nothingWrittenWhileUnconfirmed`、`convergedAfterTheAuthorityBackoff`、`preparedAfterTheConvergence`、`otherPendingKept`、`stillNoSeal` | RED `red-d-unconfirmed`（`aea4b07`＋P-UNCONF）：`noSealWhileUnconfirmed=false`，record 是 SEALED。GREEN `green-d-unconfirmed`（P-UNCONF、T-PF、T-WF）：塌縮流程自己的 `convergeIfNeeded` 在 201 tick 時確認，202 tick 時準備完成；T-PF（本 session 自己的 PENDING 寫入失敗）照舊封存 |
| Q-M2：PREPARE 期間不更新邊緣觸發的快照 | `CS:479`：`leavePrepare` 以目前在艙內的原參與者重新 prime 快照（`EntryWatch.prime`）。P-POWER（`M5U:1453`）：收據加上第二位原參與者 B（`collapsedToUniverse` 的 `others` 版本，`M5U:2223`；`newCollapse` 改以 `LinkedHashMap` 建立參與者，`M5U:2228`）；PREPARE 中 B 進艙（`M5U:1478`），同一個 tick 斷電。既有的 witness `noReentryWithoutAnEntryEdge`（恢復供電之後沒有人移動：沒有 ticket、「進入 PREPARE」仍是一則）就是鎖 | RED `red-e-power-second`（`c3c7ea8`＋P-POWER 的改動）：`noReentryWithoutAnEntryEdge=false`（B 被當成新進入的人，恢復供電的下一個 tick 回到 PREPARE）。GREEN `green-e-m5`（M5 30/30） |
| Q-M3：「目的世界不是 ACTIVE」的防守分支 | 選第二個：WARN（`CS:510`）與 `leavePrepare` 的 Javadoc 改成「之後與閘門失效相同，依邊緣觸發續做」，行為不變。理由：§7.3 的「閘門再次成立時，依 §7.1 的邊緣觸發續做」是離開 PREPARE 之後唯一的續做規則；這個分支也是離開 PREPARE（推導回到 RESOLVE），套同一條規則就不需要另一種續做路徑。改成 `gateEvaluated=false`（自動續做）的話，若 RESOLVE 判定已確定而 `resolveActive` 仍是空的（兩者不一致才會進這個分支），每 2～3 個 tick 就會「進入 PREPARE → WARN → 回到 RESOLVE」一次，WARN 不去重；這個分支在 GameTest 也造不出來（F4b-2「觀察」第 5 條），新行為無法驗證。`leavePrepare` 在這條路徑上也會重新 prime 快照（Q-M2），所以之後只有真正的邊緣才會續做 | 只有字句；JUnit `f-wording`（CST 17、`GT` 2），run-all |
| Q-M4：F4b-3 的接點 | 量子膜判斷的插入點寫在 `prepareUniverse` 的 Javadoc（`CS:497`）：必須在 `CS:502` `if (progress.ready) return;` 之前。PREPARE 中 SEALED 的寫入被拒（REJECTED）與 session 被隔離時，決定**不**釋放目的世界的 ticket（`seal` 的 Javadoc `CS:815`、`step` 的註解 `CS:251`）。理由：（1）被拒時 journal 沒有改變，record 仍是 COLLAPSING，之後只重試這筆寫入、不推導階段，寫入成功時 `seal` 照常釋放；session 離開 journal 時由 `releaseTerminatedDestinations` 釋放，所以 ticket 不會洩漏。（2）PREPARE 中的被拒只有前置檢查的拒絕，而呼叫前已確認 current＝flushed＝view，GameTest 造不出這條路徑，提早釋放無法以 RED 或 mutation 證明。（3）隔離是 H3 的暫時收斂，解除之後依原進度續做；準備完成（READY stub）之後的 tick 在 `CS:502` 就 return、不會重新加 ticket，若隔離時釋放，F5 的 PASSAGE_READY 會少了預載。交給 F4b-3（「交給後續」） | 只有 Javadoc 與註解 |
| Q-M5：`project()` 三段幾乎相同的 `switch (writeStep(…))` | `CS:701` `proceeds(view, track, progress, result)`：WAIT 交給 `prepareWait`、SEAL 以結果帶的 failure 進 SEALED(PROJECTION_FAILED)，回傳是否繼續；三處（修復、第一次寫入、再驗）都改用它（Q-I1 之後是四處）。行為不變 | `a-qm5`：JUnit CST 17、PPIT 12、`GT` 2 PASS；M5 `gate-a-qm5-m5-3d037b1c6805497fbe6c82d0e03867a8` 28/28，test 集合與結果與 F4b-2 相同 |
| Q-M6：「上一個 tick 是否在 PREPARE」有兩個來源 | `CP:53` `Gate`（`stillOpen()`、`edge()`），`CP:73` `derive` 依 `preparing` 選一種評估；`CS:936` `PrepareGate` 不再讀 `track.preparing`（原本 `gate()` 的第一行）。`Track.preparing` 的 Javadoc（`CS:1304`）寫明它只交給 `derive`，UNIVERSE 時恆等於 `prepare != null`。CST 的兩個表格不變，閘門的 fixture 改成 `CountingGate`（`CST:93`），並多斷言呼叫的是哪一種評估。三個相鄰的 boolean 參數沒有改成物件：`preparing` 已與閘門相鄰，表格以具名的 record 欄位傳入 | `b-qm6`：JUnit 17／12／2 PASS；mutation `mut-b-qm6-always-edge`（`derive` 一律評估邊緣）：`collapsePhaseReportsThePrepareTransition` 兩列 FAIL；M5 `gate-b-qm6-m5-1a599485b868450f8fe6fc7c8fe22dbe` 28/28；default focused 21 個 batch `gate-b3-qm6-default-4ff28632c405432ab666f758b18d6d62` 21/21（`b-qm6-default`、`b2-qm6-default` 不採計，見下方） |
| S-M3：`interiorNotRewritten` 沒有單點 mutation 的牙齒 | 不改程式。這個 witness 是縱深防禦：D2 單獨造訪時，即使沒有偵測到遺失，`writeProjection(PENDING)` 也會依記憶體中的 current 回 `ALREADY_COMPLETE`（`CA:575`）而只走修復，interior 本來就不會被重寫；要讓它 FAIL，必須同時拿掉遺失的偵測與 CORRUPT 的處置 | 多點 mutation `mut-sm3-corrupt-as-absent-and-written`（同時拿掉退化檢查、把 projection 查詢的 CORRUPT 當成 ABSENT、略過 §6.5 的交叉檢查、把 PENDING 寫入的 CORRUPT 當成 WRITTEN）：P-DEGRADED `interiorNotRewritten=false`、`invariantNotRepaired=false`、`onePermanentAdminError=false`（`chain-sm3.log`） |
| S-M4：site 驗證排在 §9.2 的衝突之後 | 補進「偏離」第 11 條 | — |
| S-M5：重建 hook 不涵蓋純 tickLimit 逾時 | README「投影的殘留與隔離」補上這個例外：逾時時原版不執行 callback，hooks 不釋放，重建 hook 不會執行；遇到時保留 root、以新 label 重跑整個 M5 run。沒有改成 TestListener | 純文件 |
| Nit | `HK` 的 `IntFunction`、`Optional`，`SUP:164-165`、`:175-176` 的 `RegistryKey`、`IntFunction`、`ProjectionWriter`，`M5U` 的 `HashSet`、`Pattern` 改用 import；`SUP` 重建 hook 的 Javadoc 更正：`projection`、`lost`、`degraded` 是本 mod 自己的欄位，不經 remap | `g-nit-m5`：M5 30/30，test 名稱與結果和 `green-e-m5` 逐一相同 |

證據（逐輪的 state 記 HEAD、porcelain 與逐檔 blob）：

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| Q-M5 | `3e7cf1d`＋`CS`（＝`de3f56d`） | `a-qm5`（JUnit）；`gate-a-qm5-m5-3d037b1c6805497fbe6c82d0e03867a8` | 17／12／2；M5 28/28 |
| Q-M6 | `de3f56d`＋`CP`、`CS`、`CST`（＝`726c7e0`） | `b-qm6`、`mut-b-qm6-always-edge`（JUnit）；`gate-b-qm6-m5-1a599485b868450f8fe6fc7c8fe22dbe`；`gate-b3-qm6-default-4ff28632c405432ab666f758b18d6d62` | 17／12／2；mutation FAIL；M5 28/28；default 21/21 |
| Q-I1 | `726c7e0`＋`CS`、`M5U`（＝`aea4b07`） | `gate-red-c2-recheck-738de728c3d544e3a2ec05e4cd2a8f9d`（RED）；`gate-green-c-recheck-d783e369eb5a46cdaea0a4f8a405c66d`；`chain-c.log`；`gate-green-c-m5-df79a1f68fe74f4d8d0bb88023e7f2cf`；`green-c`（JUnit） | RED FAIL；2/2；4 個 mutation FAIL；M5 29/29；17／12／2 |
| Q-M1 | `aea4b07`＋`CS`、`M5U`（＝`c3c7ea8`） | `gate-red-d-unconfirmed-6419c23b2e3b40cfbfd9e02074aab8f5`（RED）；`gate-green-d-unconfirmed-4456c1132e0d4f868b2bb666d2b4dfed`；`gate-green-d-m5-c8c9cc4287954cde88987744b0d5d4ac`；`green-d`（JUnit） | RED FAIL；3/3；M5 30/30；17／2 |
| Q-M2 | `c3c7ea8`＋`CS`、`M5U`（＝`bfaf6f0`） | `gate-red-e-power-second-b0ac0cfd4f014977afc081c679d9b22c`（RED）；`gate-green-e-m5-34a27407c43342028f47194b0891fa71` | RED FAIL；M5 30/30 |
| Q-M3、Q-M4 | `bfaf6f0`＋`CS`（＝`b6fdf59`） | `f-wording`（JUnit） | 17／2 |
| Nit | `b6fdf59`＋`HK`、`SUP`、`M5U`（＝`1f41d0a`） | `gate-g-nit-m5-1d08eb6ebb8144caab58fd948f6b980c` | M5 30/30 |
| S-M3 | `97f1898`＋adapter | `gate-mut-sm3-corrupt-as-absent-and-written-feb2836a40bd410f89b72040714faf6a` | FAIL（目標 witness） |
| run-all | `97f1898`，工作樹乾淨（`STATUS` 是空的） | `run-all-4d864a16e1cf45fd90cdfe3c3d35b0ac`（Full：`gate-full-d0e1272b2ca449baafea5e0ee801fd8b`；M5：`gate-m5-gametest-2cc7bc4d00db424d9e88ce65b8e8097f`；`fix1/run-all-console.txt`） | `END ALL exit=0 HEAD=97f1898`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=30/30 perTest=51+1 junit=631 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=401entries/354classes sources=216 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- 不採計的 root：
  - `gate-red-c-recheck-b8c6b4a61c9f46b18b655726dbc4f39b`：P-RECHECK 首版的改動迴圈晚了一個 tick 才開始（第一次 checkpoint 在上一個 tick 的 `END_SERVER_TICK`，偵測到它的 test tick 沒有改 D），第二次 checkpoint 就在那個 tick 乾淨地完成、寫了 COMPLETE，之後的改動與 checkpoint 無關；FAIL 不是因為目標行為。改成在偵測到的那個 tick 就先改一次之後，`red-c2-recheck` 才是有效的 RED。
  - `gate-b-qm6-default-86b040ba08814eb59f49d534e067f7be`、`gate-b2-qm6-default-507b126a36694b149f22988ec581ffd8`：21 個 test 都 PASS，但 gate 的 boundary oracle 判 FAIL。原因是 `gates.ps1` 只以 PID 從 `run/gametest` 挑本次的 `m4-boundary-*.json`，而那裡累積了 179 份舊的 receipt；兩次都剛好有一份舊檔的 PID 與本次的 server 相同（10-01 的 63888、10-02 的 49544），gate 讀到兩份。不是本輪的改動造成的，也不在派工列出的已知偶發；沒有修改 `gates.ps1`、也沒有移動 `run/` 的檔案，以新 label 重跑（`b3-qm6-default`）通過。交給 F6（「交給後續」）。

- 與 F4b-2 的 run-all（`run-all-3b4d8e88f93645ec8b4d9383c7e40094`，`dc11821`）逐項比較：
  - M5 28 → 30（下方「測試」）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、JUnit 631、windowsCheckpoint 17、M3 4＋5、M4 recovery 24、mainOnly、CI 兩個平台都不變。M5 oracle PASS（`m5-oracle.json`）：30 個 test 的 catalog 增量逐一串接（`existing_receipt_without_a_discovered_universe_seals_permanently` 為 0，其餘各 1），每個都 converged、preserved，suite 結束時 29 筆。`M3_UNIVERSE_STOPPED_VERIFIED` 的 worlds 21 → 23（多出的 2 與新增的 test 數相同），`quarantine=0`、`contextDetached=true` 不變。
  - release JAR 399 → 401 entries（352 → 354 classes，`fix1/jarcmp-release-run-all.txt`）：新增 `CollapsePhase$Gate`、`CollapseService$PrepareGate`；沒有移除（原本 `gate()` 的 lambda 不產生 class 檔）。內容不同的 class 都屬於 `CollapsePhase`、`CollapseService` 與它們的 nested class；以 `javap -p -c` 比對，bytecode 不同的只有 `CollapsePhase`（`derive` 的最後一個參數換成 `Gate`）、`CollapseService` 與 `CollapseService$Prepare`（多了 `repairLogged`），其餘 nested class 只差行號表（`fix1/jar-attribution.txt`）。testmod 命中 0。
  - sources JAR 216 不變（`fix1/jarcmp-sources-run-all.txt`）：內容不同的是 `CollapsePhase.java`、`CollapseService.java`。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`fix1/wbcmp.py`）：Full 54／28 不變、逐行相同（`fix1/wbcmp-full-run-all.txt`）；legacy 0／0（`wbcmp-legacy-run-all.txt`）。M5 WARN 25 → 29、ERROR 6 不變（`wbcmp-m5-gametest-run-all.txt`）：多出的 4 則是 P-RECHECK 的 600 ticks 等待 WARN（「正在等：投影的不變量 cell 在第一次寫入之後一直被改動…」）與「通道尚未實作（F5）」、P-UNCONF 的「通道尚未實作（F5）」與 `CA` 的「PROJECTION_LEDGER 的 checked 寫入未確認」（test 替另一座艙寫入、受控讀回失敗）。GT-P 的原版 `PistonBlockEntity` WARN 仍是一則，排序比對中的一減一加只是 object hash 不同。
  - default run 的稽核：Full 的 latest.log 對「進入 PREPARE」「目的世界預載完成」「準備完成（預載與投影都已完成）」「通道尚未實作（F5）」「quantumchamber_destination」「已修復」「PREPARE 持續等待」都是 0 行。
- 本輪沒有遇到派工列出的已知偶發，也沒有停服卡住；失敗的 root 都是刻意的 RED 或 mutation，以及上列「不採計的 root」。

測試：

- M5 GameTest 28 → 30：新增 P-RECHECK、P-UNCONF（各自一個 batch，catalog 增量各 1）；P-POWER 只加第二位參與者（名稱、batch、增量都不變）。suite 結束時 27 → 29 筆。
- JUnit 631 不變（CST 17、PPIT 12、`GT` 2；CST 只改閘門的 fixture 與斷言）。default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 不變。

## F4b-3：PREPARE 期間的原艙門（量子膜、門攔截、門狀態的重新套用）與 Step 0

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `099f4a8` `refactor: route chamber projection lookups through a chamber-defined port`（parent `3f96399`）：Step 0 的 Q-m1。新增 `chamber/ChamberCollapsePort.java`（只有投影查詢）；`ChamberProtectionService.java`、`ChamberPowerCoordinator.java`（只有改走 port）、`ProjectionProtectionIndex.java`（只有 port 的註冊）；新增 JUnit `collapse/ChamberCollapseDependencyGuardTest.java`。
  - `71b5d74` `test: share the PREPARE GameTest opening and closing steps`（parent `099f4a8`）：Step 0 的 Q-M5。只有 `M5U`。
  - `3b9bbb0` `refactor: require the PREPARE bookkeeping instead of creating it lazily`（parent `71b5d74`）：Step 0 的兩則 Nit。`CP`（只有 Javadoc）、`CS`。
  - `270af2d` `feat: give the origin door its PREPARE state and reapply it from the collapse view`（parent `3b9bbb0`）：補充第 1、3、4、5、6 點。`CS`、`CST`；testmod `M5U`（只有 P-NEW、P-POWER）、`SUP`（只有 `doorSealed`）。
  - `287d216` `feat: intercept origin door use during PREPARE before the door service`（parent `270af2d`）：補充第 2 點。`PORT`（加上門的攔截）、`WCDA`、`QBB`、`CCB`、`CS`、`CST`。
  - `89c39ad` `test: cover the PREPARE origin door in the M5 run`（parent `287d216`）：testmod `M5U`（6 個新 test）、`SUP`、`HK`、新增 `mixin/SessionJournalPutFaultMixin.java`、`quantumchamber-test.mixins.json`（只登記它）。
  - `7ad2ddc` `docs: describe the F4b-3 origin door GameTest helpers`（parent `89c39ad`）：`scripts/verification/README.md`「新增 M5 GameTest」一節。
  - 本段由其後的 docs commit `docs: record F4b-3 source door in the M5 note` 加入。
- Code range `3f96399..89c39ad`。沒有改 `ChamberDoorService`、`ChamberControllerLoadSyncQueue`、`QuantumSuperpositionMod`（port 的註冊在 `PPI.registerEvents()` 與 `CS.initialize()` 裡，兩者本來就由它呼叫）、`CollapseAuthorities`、`DestinationResolver`、`ProjectionWriter`、`CorridorPageManager`、`SuperpositionSessionManager`、`SessionRecoveryState`、`SessionRecoveryManager`、`UniverseRegistryState`、`ChamberRegistryState`、`UniverseLifecycleService`、`M5CollapseGameTests`、語言檔、`gates.ps1`、`build.gradle`、`.github/`。
- Review：spec review ✅（Critical 0、Important 0、Minor 4：S-M1～S-M4）；quality review Not approved（Critical 0、Important 1、Minor 4、Nit 5：Q-I1、Q-M1～Q-M3、`openForInsider`、N-1～N-4）。上一輪回報列出、需要裁示的各項讀法，spec reviewer 都判定可以接受或符合 spec，controller 正式接受（「裁示」）。
- 最終 review：
  - fix round 1 的 spec re-review ✅（Critical 0、Important 0、Minor 3：m-1～m-3）。偏離第 9～11 條都判定符合 spec，controller 接受。
  - fix round 1 的 quality re-review Approved（Critical 0、Important 0、Minor 2、Nit 3）。Q-I1 已從根本關閉。
  - controller 不再開 fix round。理由：兩位 reviewer 都判定這些 Minor 可以留到下次改門的程式時處理；使用者也指示 F4b 完成後就停。spec m-1～m-3、quality M-1、M-2 與 Nit，全部寫進「交給後續」的 F5 Step 0。
  - **F4b-3 完成**：range `3f96399..df3aee2`，之後是 controller 的 docs commit。
    - code：`099f4a8`、`71b5d74`、`3b9bbb0`、`270af2d`、`287d216`、`89c39ad`、`ae3f1bc`、`bbf7aa2`、`3c9b42c`。
    - docs：`7ad2ddc`、`fda05fe`、`df3aee2`。
    - 最終 run-all：`run-all-9a5c30415e954e88848c98cfb42f13c3`，`END ALL exit=0`（`3c9b42c`）。Full 那一輪多了一則已知偶發，以 `gate-full-rerun-06323a88…` 重跑，結果與基準逐行相同。

      | 項目 | 數值 |
      | --- | --- |
      | default | 174/174 |
      | M5 | 37/37 |
      | JUnit | 636 |
      | release／sources | 410／363、217 |
      | Full WARN／ERROR | 54／28（重跑） |
      | M5 WARN／ERROR | 36／6 |

  - **F4b（F4b-1、F4b-2、F4b-3）全部完成**。
- 處置見本節末的「Fix round 1」。
- 範圍是 plan Task F4b「拆分」的 F4b-3：PREPARE 期間的原艙門（量子膜的套用條件與門口規則、門的攔截、閘門失效與暫停時的門、門狀態的重新套用），以及 Step 0（`chamber`／`collapse` 的依賴反轉、M5U 的前後段 helper、F4b-2 的兩則 Nit）。PASSAGE_READY 期間的門（§10.5）、passable 量子膜與沒有所屬 session 的量子膜正規化（§10.6 :613）屬 F5。
- 行號以 `89c39ad` 為準。`PORT` 指 `chamber/ChamberCollapsePort.java`，`WCDA`、`QBB`、`CCB` 指 `chamber/WorldChamberDoorAdapter.java`、`QuantumBulkheadBlock.java`、`ChamberControllerBlock.java`，`GUARD` 指 `ChamberCollapseDependencyGuardTest`，`PJF` 指 `mixin/SessionJournalPutFaultMixin.java`；`CS`、`CST`、`CP`、`CPS`、`CPC`、`PPI`、`PPIT`、`M5U`、`SUP`、`HK` 同前面各節。M5U 新增的 6 個 test（M5 run，各自一個 batch，名稱不以 `_windows` 結尾，各平台都執行）：
  - T-MEM `M5U:2064` `prepare_seals_the_membrane_only_with_a_clear_doorway_and_no_non_participant_inside`（`m5_universe_door_membrane`，tickLimit 1200，catalog 增量 1）
  - T-CLOSE `M5U:2136` `prepare_participant_closing_the_door_ends_the_collapse_before_the_preload_with_the_universe_kept`（`m5_universe_door_close`，600，1）
  - T-CLOSE-D `M5U:2180` `prepare_participant_closing_by_the_controller_with_an_entity_in_the_doorway_leaves_it_open`（`m5_universe_door_close_doorway`，600，1）
  - T-REJ `M5U:2213` `prepare_rejected_seal_write_hands_the_door_back_open_until_the_retry_seals_it`（`m5_universe_door_rejected`，1200，1）
  - T-START `M5U:2254` `startup_reapplies_a_stale_membrane_in_waiting_once_on_the_next_tick`（`m5_universe_door_startup`，900，1）
  - T-RELOAD `M5U:2306` `sealed_door_closes_once_after_its_chunk_reloads_and_stays_open_once_a_player_opens_it`（`m5_universe_door_reload`，1500，0：SEALED 收據不配置宇宙）
  - 另外 F4b-2 的 P-NEW（`M5U:1377`）、P-POWER（`M5U:1448`）改了門的 witness（名稱、batch、增量都不變），見下表。

### Step 0

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q-m1：`chamber` 與 `collapse` 互相依賴 | 新增 `PORT`：`chamber` 定義的反轉介面，比照 `ChamberSessions.gateway()`。`Projections`（`PORT:42`，`protectedCell`、`projectionControllerAt`）由 `PPI.registerEvents()` 註冊（`PPI:106`，委派給既有的兩個 static 查詢）；`Doors`（`PORT:61`）由 `CS.initialize()` 註冊（`CS:193`，見補充第 2 點）。沒有註冊時是 `NONE`：沒有投影（`protectedCell`／`projectionControllerAt` 都是 false，`mayMutate` 放行、Controller 走 foreign 分支）、不攔截（`PASS`），等同修改前「沒有索引」的語意。`CPS:65`、`CPC:56` 改呼叫 port，呼叫位置與順序不變；選 port 而不選 `registerAdditionalGuard`：後者排在 `attachedServer` 檢查之前、對 vanilla 世界也會被呼叫，保護的順序會改變。守門：`GUARD` 掃描 `src/main/java/dev/quantumchamber/chamber/` 下所有 `.java`，以 `CollapseLogMarkerGuardTest.mask` 遮掉註解與字串之後，不得出現 `dev.quantumchamber.collapse`（import、static import、完整限定名稱、點旁的空白都算）；另一個 test 鎖住掃描器本身（註解、Javadoc、字串、相近的識別字不算） | RED `red-s0a-guard`（`3f96399`＋只有 `GUARD`）：`chamberSourcesNeverReferenceTheCollapsePackage` FAIL（`[ChamberPowerCoordinator.java:3, ChamberProtectionService.java:3]`），掃描器的 test PASS。GREEN `green-s0a`（24 個 class：collapse 4＋chamber 20，全部 PASS）；GT-P、GT-L `green-s0a-m5` 2/2。mutation `mut-s0-no-port-install`（不註冊 `Projections`）：GT-P（`everyInvariantCellDenied`、`breakingRefused`… false）與 GT-L（`bRecognizedByLoadSync`、`aRecognizedByBlockAdded`、`poweredButInert` false）都 FAIL（`chain-s0.log`） |
| Q-M5：M5U 的 F4b-2 test 前後段重複 | `M5U:2538` `openOrigin`（`DormantOrigin.open`＋`asTestFailure`）、`M5U:2548` `releaseThenComplete`（`hooks.release`、斷言 witness、`awaitConvergedThenComplete(context, 1, maxTicks, ready)`）。F4b-2 的 13 個 test 以 `f4b3/s0b_helpers.py` 機械替換（只動 F4b-2 一節，前段 13 處、後段 13 處都斷言匹配數；`s0b-report.txt` 逐處列出 maxTicks 與 ready），失敗訊息的字串不變。F4b-3 的新 test 也用它們（T-RELOAD 的增量是 0，自己寫後段）。不拆檔（屬 F6） | 純重構：`green-s0b-m5` M5 30/30，test 名稱與結果和 F4b-2 的 run-all 相同 |
| Nit：`CollapsePhase.Gate#edge` 的 Javadoc | `CP:57` 只寫契約：「這個 tick 是否有 §7.1 的邊緣觸發。評估會消耗一次邊緣觸發：同一個觸發只會讓一次評估成立」；「本 instance 第一次以位準評估」是 `CS` 的 `PrepareGate` 的實作，留在它自己的 Javadoc | 只有 Javadoc |
| Nit：`prepareUniverse` 走不到的 lazy `Prepare` | `CS:622` 改成 `Objects.requireNonNull(track.prepare, …)`：推導回傳 PREPARE 的那個 tick 由 `enterPrepare` 建立，之後一直保留到離開（`Track#preparing` 的不變式；`sealRejected` 不重設它，被拒期間 `collapsing()` 不推導階段，不會走到這裡） | 純重構：`green-s0c`（CST 17）、`green-s0c-m5`（P-NEW、P-POWER、P-REVISIT、T-WF 4/4） |

### plan Step 1 中屬於 F4b-3 的 RED

| plan Step 1 | 程式位置 | 測試與證據 |
| --- | --- | --- |
| 第 10 組：sealed 量子膜期間來源 chunk 卸載再重載 → 下一個 tick 依 collapse view 重新套用，不在 chunk 載入的當下寫方塊 | `CS:1141` `watchDoor`（每個 tick 對 COLLAPSING、SEALED 觀察一次來源艙是否載入）、`CS:1163` `doorReloaded`（一次性觸發）、`CS:1177` `reapplyDoor`（排入下一個 tick，`queueDoor(view, 1, …)`；已有待套用的門時只延後）、`CS:1168` `reapplyGoal`、`CS:1465` `applyDoor` 的 `NORMALIZE`。COLLAPSING 在同一個 instance 內不會卸載（來源 ticket，見「偏離」第 1 條）：WAITING 的版本由 T-START 證明；PREPARE 的版本交給 F6（fix round 1，spec S-M3） | T-START：WAITING 時門上留著 sealed 量子膜，模擬重啟之後的第一次觀察：觀察到的 tick 只排入（`notWrittenWhenObserved`），下一個 tick 才改為一般開門（`appliedOnTheQueuedTick`：INFO 記下的排入 tick 與套用 tick 相同）、只有一次（`onlyOnce`）；恢復供電、P 再進入 → PREPARE 依條件重新套用 sealed。mutation `mut-g-no-reapply`（拿掉重新套用）T-START 逾時在「沒有排入重新套用」；`mut-g-reapply-same-tick`（觀察到的同一個 tick 就套用）`notWrittenWhenObserved=false`；JUnit `CST:496` `doorReapplicationTriggersOnlyAfterAnUnloadOrOnTheFirstStartupObservation`（`j-mut-reload-on-first-runtime-observation` FAIL） |
| 第 10 組（SEALED 版本）：開服或重載之後關門一次；門口有人時改為一般開門；玩家打開之後不會被關回去 | 同上；`applyDoor` 的 `CLOSE_ON_SEALED` 先等門口的 entity storage 載入（`CS:590` `doorwayEntitiesLoaded`），再依門口決定關門或一般開門（既有的 `sealedDoor`） | T-RELOAD（`SUP:651` `RemoteOrigin`：overworld 遠處、只有它自己強制載入的真原艙；真的讓 chunk 不再是 FULL 再載入兩次）：`notReappliedWhileLoaded`、`closedOnceAfterTheReload`、`appliedNoEarlierThanTheQueuedTick`、`anyPlayerOpensASealedDoor`、`notClosedAgain`（40 tick 後仍開著、只有一次重新套用）、`openedForTheEntityInTheDoorway`（第二次重載時門口有盔甲座）。實測關門等了 25～45 tick 的 entity storage（fix round 1，spec S-M4：採計的 root，GREEN `gate-green-a-m5-b9294ff30f914b4094973bb06ac38442` 的 `M5_DOOR_RELOAD queued=[5298, 5389] applied=[5343, 5414]`，run-all `gate-m5-gametest-8ca0268f8f384bf6bad7b892f702cad1` 的 `queued=[5784, 5866] applied=[5818, 5908]`；fix round 1 起 T-RELOAD 再載入第三次，見「Fix round 1」）。mutation 都 FAIL：`mut-g-no-reapply`（逾時在「載入之後沒有重新套用」）、`mut-g-reapply-every-tick`（`notReappliedWhileLoaded=false`）、`mut-g-close-no-entity-wait`（`openedForTheEntityInTheDoorway=false`：entity 還沒載入就關門） |
| 第 11 組：sealed 量子膜只在門口沒有 entity、艙內沒有非參與者時套用；sealed 期間被指令傳送進艙內的非參與者可以把門改為一般開門，塌縮不結束；艙外的非參與者被拒 | 套用：`CS:527` `prepareDoor`、`CS:565` `membraneTarget`、`CS:573` `outsiderInside`。攔截：`WCDA:25` `toggle` → `PORT` `Doors` → `CS:1239` `useDoor` → `CS:1253` `door` → `CS:1225` `doorAction` → `CS:1307` `openForInsider` | T-MEM：`plainOpenWithANonParticipantInside`、`preparingInTheBackground`、`sealedAfterTheNonParticipantLeft`、`aSpectatorInsideKeepsTheMembrane`、`plainOpenWithAnEntityInTheDoorwayAfterThePreparation`、`sealedAgainOnceTheDoorwayIsClear`、`insiderOpensAtOnce`、`collapseContinues`、`resealedAfterTheInsiderLeft`、`outsiderRefusedAtTheBulkhead`、`outsiderRefusedAtTheController`、`oneSealedAndOneOpenInfo`。RED 的等價：`mut-g-no-prepare-door`（F4b-2 的行為：不套用量子膜）T-MEM 10 個 witness false；`mut-g-no-interception`（F4b-2 的行為：門照舊交給 `ChamberDoorService`）`insiderOpensAtOnce`、`outsiderRefusedAt…` false。其他 mutation：`mut-g-membrane-ignores-outsider`、`mut-g-membrane-ignores-doorway`、`mut-g-outsider-passes`、`mut-g-prepare-door-after-the-ready-return`（判斷移到 READY stub 的 return 之後：準備完成之後門口的 entity 不再讓門打開）都 FAIL；JUnit `CST:478`、`CST:448` 與它們的 mutation |
| 第 12 組（Review Focus 3）：PREPARE 一開始、預載還沒完成時參與者關門 → 終局，catalog 恰好多 1 筆、discovery 有紀錄；門口有 entity 時改為一般開門 | `CS:1319` `endByParticipant`：current＝flushed、退避期之外 → `terminate(PREPARE_DOOR_CLOSED)` → `terminated`（runtime、來源與目的世界的 ticket）→ INFO → `queueDoor(CLOSE_AFTER_TERMINAL)`（門口有 entity 時一般開門）→ 刷新 Controller | T-CLOSE：`sealedAtThePrepareStart`、`beforeThePreloadCompleted`、`terminalBeforeTheDoorChanged`（flush 探針在終局那一次 journal flush 的開頭看到門仍是 sealed）、`terminatedAtOnce`、`doorClosedAfterTheTerminal`、`ticketsReleasedAtOnce`、`universeKeptInTheCatalog`（EAGER_ENABLED、仍是 ACTIVE）、`universeDiscovered`（discovery 正式檔有 gameplay-eligible record）、`oneAllocationEntry`、`oneTerminalInfo`、`noWarnOrError`、`staysClosed`、`presenceNone`；catalog 增量 1。T-CLOSE-D（Controller、門口有盔甲座）：`plainOpenWithAnEntityInTheDoorway`、`leftPlainOpenNotClosed`、`ticketsReleased`。mutation：`mut-g-no-interception`（T-CLOSE 6 個、T-CLOSE-D 3 個 witness false）、`mut-g-door-before-terminal`（`terminalBeforeTheDoorChanged=false`）、`mut-g-close-ignores-doorway`（`leftPlainOpenNotClosed=false`）、`mut-g-membrane-ignores-doorway`（T-CLOSE-D `plainOpenWithAnEntityInTheDoorway=false`） |
| 第 13 組的門：PREPARE 中斷電 → 門由 sealed 改回一般開門 | `CS:499` `leavePrepare` → `CS:514` `releasePrepareDoor`（`OPEN_ON_LEAVE`） | P-POWER 新增 `sealedWhilePreparing`、`plainOpenAfterThePowerLoss`、`sealedAgainAfterTheEdge`。mutation `mut-g-leave-keeps-membrane` `plainOpenAfterThePowerLoss=false`；`mut-g-no-prepare-door` 另兩個 false |
| 補充第 2 點：roll-forward 尚未確認時拒絕 | `doorAction`（`CS:1225`）的 `rollForwardPending` | GameTest 造不出 PREPARE 中的 roll-forward 未確認（「觀察」第 2 條）。JUnit `CST:448` `doorActionFollowsThePrepareDoorRules` 走訪全部組合；mutation `j-mut-door-rollforward-ignored`、`j-mut-ready-rollforward-passes` 都只有這個 test FAIL |
| 補充第 4 點：REJECTED 與隔離期間的門 | REJECTED：`seal` 的被拒分支（`CS:950`）。隔離：`step`（`CS:267`）在隔離的 return 之前呼叫 `watchDoor`，它在隔離中、上一次推導在 PREPARE 時交還門 | T-REJ（`PJF` 拒絕這個 session 的 SEALED 一次）：`plainOpenWhileTheSealIsRetried`、`stillCollapsing`、`destinationTicketKept`（Q-M4）、`oneRejectedWarn`、`onePauseInfo`、`stillPlainOpenDuringTheBackoff`、`closedOnceSealed`、`ticketsReleasedOnceSealed`、`putRejectedOnlyOnce`。mutation `mut-g-rejected-keeps-membrane`：`plainOpenWhileTheSealIsRetried`、`onePauseInfo`、`stillPlainOpenDuringTheBackoff` false。隔離只有程式與說明（「觀察」第 3 條） |

### controller 補充第 1–11 點

- **第 1 點（套用量子膜的條件）**：`CS:527` `prepareDoor` 在 `prepareUniverse` 的最前面（`CS:623`，`if (progress.ready) return;` 之前），所以進入 PREPARE 的那個 tick、預載與投影期間、準備完成之後的每個 tick 都判斷。條件（`CS:565` `membraneTarget`，純函式）：門的 25 格（`doorwayBox`）內沒有任何 entity 的 bbox，而且門口所在 chunk 的 entity storage 已載入（否則當成門口有人）；艙內沒有非參與者玩家（`CS:573`：同一個 world、不是旁觀者、不是這個 session 的參與者、bbox 與 interior 相交）。成立時 `applyTarget(…, DoorState.sealed(facing))`，否則 `DoorState.OPEN`；準備照常進行。`applyTarget` 的結果經既有的 `applyOutcome`／`recordDoorStep`（F3d、F4a-0）：APPLIED／UNCHANGED 完成；NOT_LOADED 下一個 tick 再試、不載入；DOOR_INCOMPLETE、ROLLED_BACK、ROLLBACK_FAILED 依原因去重記 WARN、退避（狀態記在 `Track.membrane`，成功時清除）。每次 PREPARE 的 tick 都呼叫 `applyTarget`，門已是目標時是 UNCHANGED（不寫入、不刷新）。改了門才記 INFO，「套用 sealed 量子膜」與「維持一般開門」每一段 PREPARE 各一則（`Prepare.sealedLogged`／`openLogged`）。PREPARE 擁有門的期間，先移除這座艙其他待套用的門（`doors.remove`）。
- **第 2 點（門的攔截）**：位置選 `WCDA:25` `toggle`：bulkhead（`QBB:156`）與 Controller（`CCB:80`）的 `onUse` 都經它，是玩家開關原艙門唯一進到 `ChamberDoorService.toggle` 的路徑（走廊入口門走 `CorridorPageManager` 自己的 toggle，不是原艙）。`toggle` 多了 `PlayerEntity` 參數，先呼叫 `PORT` 的 `Doors`：`HANDLED` → `Result.INTERCEPTED`（不切換），`REFUSED` → `Result.REFUSED`（不切換、門不變）；兩個 `onUse` 對 REFUSED 回 `ActionResult.FAIL`，Controller 對 INTERCEPTED 照常顯示既有的門切換訊息（沒有新的訊息；拒絕時不顯示）。`CS:1253` `door`：依 world key、Controller 位置與朝向找出以這座艙為原艙的 session（`sessionAt`，collapse view），由純函式 `doorAction`（`CS:1225`）決定：
  - COLLAPSING、目的地是 UNIVERSE、PREPARE 擁有原艙門（上一次推導在 PREPARE，而且沒有被拒而待重試的 SEALED 寫入、沒有隔離）：roll-forward 尚未確認 → 拒絕；參與者 → `endByParticipant`；艙內的非參與者（bbox 與 interior 相交）→ `openForInsider`（`applyTarget(OPEN)`、INFO，塌縮不結束；下一個 tick 依第 1 點重新判斷）；艙外的非參與者 → 拒絕（DEBUG）。
  - PASSAGE_READY：roll-forward 尚未確認 → 拒絕（§7 :336）；其餘照一般門的邏輯，F5 在同一個 `doorAction` 接上 §10.5（F5 之前執行期間沒有 PASSAGE_READY，開服時的在第一個 tick 終局）。
  - 其他：SEALED、門本來就是一般狀態的階段（RETURN_PLAYERS、RELEASE_GEOMETRY、RESOLVE、WAITING）、SOURCE、M1–M4 的狀態 → 照一般門的邏輯。
  - 量子膜門不再經 `toggle`：PREPARE 擁有門時一律 HANDLED 或 REFUSED。
  - 不丟出例外：讀不到 journal 時不攔截（DEBUG；M1–M4 的門行為不變，塌縮流程本身此時也不推進）；處置途中失敗時拒絕、不改門、依原因去重記 WARN（帶 `session=`）。
- **第 3 點（閘門失效）**：`CS:499` `leavePrepare` 的轉換點呼叫 `CS:514` `releasePrepareDoor`：排入 `OPEN_ON_LEAVE`（立即嘗試一次；chunk 不是 FULL 時等之後的 tick），INFO 的字句補上「原艙門改為一般開門」。目的世界的 ticket 照 F4b-2 在同一點釋放。P-POWER 的三個新 witness。
- **第 4 點（REJECTED 與隔離）**：設計選「被拒時先把門改為一般開門」：`seal` 的 REJECTED 分支（`CS:950`）在 PREPARE 中被拒時呼叫 `releasePrepareDoor`、記 INFO「PREPARE 暫停」；隔離由 `watchDoor` 在 `step` 隔離的 return 之前處理（同樣一次）。兩者都不釋放目的世界的 ticket（F4b-2 Q-M4 的裁示，沒有改）。PREPARE 恢復擁有門（隔離解除、`prepareDoor` 再次執行）時清除 `Track.doorReleased`、移除待套用的門；被拒的寫入最後成功時照常 `CLOSE_ON_SEALED`。T-REJ 以新的 testmod mixin `PJF` 造出 F4a 起「GameTest 造不出」的被拒路徑。
- **第 5 點（門狀態的重新套用）**：見下方小節。沒有改 `ChamberControllerLoadSyncQueue`，不需要 §17 的 queue observer regression。
- **第 6 點（門口有人時的規則）**：三種會讓門變成有碰撞的轉換都先看門口：sealed 量子膜（第 1 點）、PREPARE 期間參與者關門的終局之後（`CLOSE_AFTER_TERMINAL`）、SEALED 的關門（`CLOSE_ON_SEALED`，含重新套用）。門口有 entity 時一律改為一般開門。另外都要等門口那幾個 chunk 的 entity storage 載入（`CS:590`）才判斷門口：重啟或 chunk 重新載入之後 entity 是非同步載入的，`getOtherEntities` 在那之前看不到門口的 entity（T-RELOAD 的實測等了 25～45 tick，採計的 root 見上表；`mut-g-close-no-entity-wait` 會把門關在盔甲座上）。
- **第 7 點（掛件與強充能）**、**第 8 點（先終局、再改門之間 crash）**：見下方小節。
- **第 9 點（RED）**：見上表。新增的 6 個 `@GameTest` 都是 M5 run 的新 batch（M5 30 → 36）；P-NEW、P-POWER 只改 witness。
- **第 10 點（default run 的隔離）**：見下方「default run 的稽核」。
- **第 11 點（log 與訊息）**：新增的 log 都在 `CS`、都帶 marker（`CollapseLogMarkerGuardTest` 每輪 PASS）；能歸屬 session 的 WARN 都帶 `session=`（門的套用失敗沿用 `recordDoorStep` 的字句，帶 `chamber=`、`session=`；攔截途中失敗的 WARN）。正常情況只記 INFO：套用 sealed／維持一般開門（每段一次）、艙內的非參與者開門、PREPARE 期間參與者關門的終局、PREPARE 暫停（被拒、隔離）、重新套用的排入與完成。拒絕記 DEBUG。沒有新的玩家訊息（拒絕不顯示；Controller 的既有門切換訊息照舊）。

### 補充第 5 點：「一次性」觸發的設計

- 觸發只有兩種（`CS:1163` `doorReloaded(previous, loaded, startupSession)`，純函式）：
  - 本 instance 看過來源艙「沒有載入」，這次「已載入」（chunk 重新載入）；
  - 開服 attach 當下就在 journal 的 session（`startupSessions`），本 instance 第一次觀察到已載入（重啟之後）。
- 「已載入」是艙體向水平外擴 3 格的 chunk 都是 FULL（與閘門、門的套用同一個範圍，只查詢、不載入），每個 tick 在 `step` 觀察一次，記在 `Track.doorLoaded`（不持久化、不隨 `resetGate` 重設）。執行期間才出現、一直載入著的 session 永遠不觸發：SEALED 的門被玩家打開之後不會被關回去（T-RELOAD 的 `notClosedAgain`；`mut-g-reapply-every-tick` 會把它關回去）。
- 觸發時排入下一個 tick（`queueDoor(view, 1, goal)`），不在觀察到的那個 `END_SERVER_TICK`、更不在 chunk 載入的事件裡寫方塊；已有待套用的門（例如閘門失效時排入、chunk 不是 FULL 而一直等著）時只把它延到下一個 tick（`PendingDoor.deferTo`）。目標依 collapse view（`CS:1168`）：SEALED → `CLOSE_ON_SEALED`（門口有 entity 時一般開門）；COLLAPSING → `NORMALIZE`（門帶著量子膜就改為一般開門，否則不寫；PREPARE 時由 `prepareDoor` 接手並移除它）。roll-forward 中的 SEALED 也以 collapse view 投影成 SEALED，重載時關門，不會被套回 passable。
- 重新套用的 INFO 記下排入時的「下一個 tick（tick N）」與完成時的 tick（T-START、T-RELOAD 以它們證明延後）。

### 補充第 7 點：掛件與強充能（F3b「觀察」第 2 條）

- 判斷：不需要處理，列為已知限制交給 F7。理由：
  1. **「已供電」不會因 sealed／一般開門的切換而反覆失效。** sealed 量子膜是完整方塊、`isSolidBlock`，會傳導強充能；一般開門與 passable 沒有碰撞、不是 solid，傳導不到，bulkhead 本身也不發出紅石訊號。Controller 在門板最上排中間那格的正上方，只有「Controller 唯一的供電來源，是經由那一格門的強充能」（例如門外在那一格上掛拉桿）時，sealed → 一般開門才會讓閘門失效。那時掛在門上的拉桿或火把會因為失去支撐而掉落（原版的方塊更新），供電來源就此消失，退回 WAITING、門已是一般開門，之後要有新的供電與新的邊緣觸發才會再進入 PREPARE。所以每一次「失效」都需要一個新的外部事件，不會自己來回振盪；而閘門失效時依即時訊號退回 WAITING，正是 §7.3 的規則。
  2. **掛件掉落的後果偏向安全。** sealed → 一般開門時（艙內出現非參與者、門口有 entity、閘門失效、隔離或被拒），掛在門上的掛件掉成物品；物品若落在門口，門口有 entity，量子膜會延後套用（最多到物品被撿起或消失），門維持一般開門，不會困住人。
  3. 處理它（例如禁止在 sealed 量子膜上放置、或讓 sealed 不傳導）要改 `QuantumBulkheadBlock` 的方塊性質，屬 F3b 已 review 的範圍，也會改變關門（同樣 solid）的既有行為。
- 已知限制（交 F7 寫進玩家說明）：sealed 量子膜可以掛掛件、會傳導強充能；改為一般開門時掛件會掉落；Controller 只靠門上強充能供電的艙，量子膜改為一般開門時會斷電而結束 PREPARE。

### 補充第 8 點：「先終局、再改門」之間 crash（F3d m-7(b)）

- 窗口：`endByParticipant` 的 checked 終局已讀回確認，`CLOSE_AFTER_TERMINAL` 還沒寫入門就 crash（或待套用的門還在等 NOT_LOADED、entity storage 時停服）。重啟之後 journal 沒有這個 session，門留著 sealed 量子膜（有碰撞）。待套用的門只在記憶體（以 chamber 為 key），所以 F4b-3 的重新套用（只看 journal 裡的 COLLAPSING、SEALED）涵蓋不到。
- §10.6 :613（F5）：「找不到所屬 session、卻帶著 membrane 的 bulkhead，在 `ChamberPowerCoordinator` 刷新該艙時，排入下一個 tick 正規化為 `membrane=none`」涵蓋這個窗口：重啟之後 Controller 的 BE 載入會經 load-sync 排一次刷新，之後每 20 tick 的排程刻也刷新，所以只要 F5 在刷新時做這件事，就會正規化。結果是一般開門，不是關門：終局本來就已完成、不會被困住，與 §10.6 (b)(c)(d) 的結束狀態相同，可以接受。
- 交給 F5 的條件：
  1. 在 `refreshNow` 的 ORIGIN 分支、而且這座艙沒有 M5 塌縮狀態的 session（presence 不是 `COLLAPSE`／`PASSAGE`）時才正規化；有 COLLAPSING／SEALED 時由 F4b-3 的重新套用處理，兩者不要同時寫門。
  2. 同時處理 sealed 與 passable，目標是 `DoorState.OPEN`。
  3. 排入下一個 tick（刷新可能就在 chunk 載入的 load-sync 裡），NOT_LOADED 時等之後的 tick。
  4. 量子膜只在 PREPARE 與通道期間才會出現，所以 MEASURED（含 DORMANT）的艙出現量子膜時，也是這一條的對象。
- 沒有缺口需要另外交給 F5；Origin 被拆除的艙不再刷新，但拆除需要先完成 Controller 的刷新與維護手勢，不在這個窗口內。

### default run 的稽核（補充第 10 點）

| 新分支 | default run 中的行為 |
| --- | --- |
| `CPS:65`、`CPC:56` 改走 port | port 委派給同一個 static 查詢，結果與修改前相同；Full 的 GT 與 boundary oracle 照常 |
| 門的攔截（`WCDA:25`） | 每一次原艙門的使用都查一次 journal 的 flushed records。default run 只有 SOURCE、M1–M4 的 session 與 suspend 的塌縮 fixture：SOURCE 與非塌縮狀態一律 PASS；被 collapse-suspended hook 暫停的 session 沒有 `Track`，PREPARE 不擁有門，也是 PASS。讀不到 journal 時 PASS（M2 的 journal 故障 test 不受影響） |
| `prepareDoor`、閘門失效的門、被拒與隔離的門 | 只有 UNIVERSE 的 PREPARE 會走到；default run 沒有 test 走到 RESOLVE（F4a 的稽核），所以不會進 PREPARE |
| 重新套用（`watchDoor`） | 只有「看過沒有載入」或「開服時就在的 session 的第一次觀察」才觸發。default run 以 `markStartup` 模擬開服的是 G-DOR（DORMANT）與 G-PASS（PASSAGE_READY，`watchDoor` 不看它）：G-DOR 的 DORMANT 收據提交 COLLAPSING 之後第一次觀察排入一次 `NORMALIZE`（INFO 一則），同一個 tick 的 SOURCE fast path 以終局之後的一般開門取代它，沒有因重新套用而寫門 |

- 實證：見「Gate 與證據」的 default focused（`green-a2-default`、`c5-default`：21 個 `m5_collapse_*`／`m5_f3a_*` batch＋與門、Controller 有關的 7 個 M2 batch：`m2_task7_lateral_west`、`m2_return_cohort`、`m2_entrance_edge`、`m2_clear_retry`、`m2_task4_isolated_remap`、`m2_task4_isolated_reservation`、`m2_powered_entry`）與 run-all 的 Full（含 `defaultBatch` 的 M1：門的 rollback、Controller 開關門、缺格門、bulkhead toggle 等；`onlyBatches` 要求每個 batch 恰一個 test，`defaultBatch` 有 54 個，只能由 Full 跑）。

### 測試

- JUnit 631 → 636（＋5）：`GUARD` 2（新增）、CST 17 → 20（`doorActionFollowsThePrepareDoorRules`、`membraneIsSealedOnlyWithAClearDoorwayAndNoNonParticipantInside`、`doorReapplicationTriggersOnlyAfterAnUnloadOrOnTheFirstStartupObservation`）。PPIT 12、`CollapseLogMarkerGuardTest` 2、chamber 的 20 個 class 不變（沒有因 port 而改的 test）。
- M5 GameTest 30 → 36：上列 6 個新 test，catalog 增量 T-RELOAD 0、其餘各 1；suite 結束時 29 → 34 筆。P-NEW 的 `doorOpensNormally`／`doorClosesNormally` 改為 `sealedMembraneWhenPrepared`／`sealedMembraneHeldAfterThePreparation`（F4b-3 起 PREPARE 期間參與者開關門就是終局），P-POWER 加三個 witness。
- default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 不變：沒有新增或刪除 default run 的 `@GameTest`。

### Gate 與證據

逐輪的 state、console 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b3/`（以下簡稱 `f4b3/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`adapted-junit.ps1`、`chain.ps1`、`adapter.py`、`wbcmp.py`、`jarcmp.py` 由 `f4b2/fix1/` 複製，只改 repo 的相對路徑與註解；adapter 表在 `f4b3/adapters_f4b3.py`。`f4b2/` 下既有的檔案沒有改動。每個 adapter 跑完都還原，還原後的 blob 與套用前相同；每次 RED、GREEN、mutation 的 state 記 HEAD、porcelain 與逐檔 blob（`f4b3/<label>-state.txt`）。gate root 在 `.superpowers/verification/`。

- commit 的拆分：最終版的工作樹（`f4b3/after-mut-g-state.txt`，與 GREEN 的 `green-a-m5-state.txt` 逐檔相同）先保存在 `f4b3/final/`；`270af2d` 的內容由 `f4b3/snapshots.py` 從最終版刪除區塊產生（每一處都斷言匹配數，`snapshots-report.txt`），`f4b3/swap.ps1` 把工作樹換成 `c4`（`270af2d`）、`c5`（`287d216`）再換回 `final`（換回時以 `git hash-object` 逐檔核對）。兩個中間 commit 都在換入之後以 JUnit 與 GameTest 驗過（下表），commit 時的 blob 等於 `commit-c4-state.txt`、`commit-c5-state.txt` 記下的；`89c39ad` 的 testmod 與 GREEN 的 blob 相同。

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| Step 0 RED | `3f96399`＋只有 `GUARD` | `red-s0a-guard`（JUnit） | 守門 FAIL（`ChamberPowerCoordinator.java:3`、`ChamberProtectionService.java:3`）；掃描器的 test PASS |
| Step 0 GREEN（Q-m1） | `3f96399`＋`099f4a8` 的檔 | `green-s0a`（JUnit 24 個 class）；`gate-green-s0a-m5-c10318e30fc447f89e6e6fd1f274bf3e`；`chain-s0.log`（`gate-mut-s0-no-port-install-13dcb519ca6946728cf429a7f7d72279`） | 154 個 JUnit PASS；GT-P、GT-L 2/2；mutation 2/2 FAIL |
| Step 0（Q-M5） | `099f4a8`＋`M5U` | `gate-green-s0b-m5-982a34f0507149209fdae61812636cb2` | M5 30/30，test 集合與結果和 F4b-2 相同 |
| Step 0（Nit） | `71b5d74`＋`CP`、`CS` | `green-s0c`（JUnit）；`gate-green-s0c-m5-1a742fd8e83443f890a0bd6fe6dc4263` | CST 17、`CollapseLogMarkerGuardTest` 2、`GUARD` 2；P-NEW、P-POWER、P-REVISIT、T-WF 4/4 |
| 開發確認（不採計） | 最終版的前身 | `gate-dev-door-m5-1-4ec10ded145d4162ae3761d90e283cc3`、`gate-dev-door-m5-2-8ffc4f24e3714c96b38e17bf10da79be` | 見下方「不採計的 root」 |
| GREEN（最終版） | `3b9bbb0`＋全部（＝`89c39ad` 的內容） | JUnit：`dev-door-1`、`j-*`；M5：`gate-green-a-m5-b9294ff30f914b4094973bb06ac38442`；default focused：`gate-green-a2-default-b57f7a50a0bb47f3bea14aa761c23626` | CST 20；M5 36/36；default 28/28 |
| JUnit mutation | 同上＋adapter | `adapted-junit-summary.txt`：`j-mut-door-rollforward-ignored`、`j-mut-ready-rollforward-passes`、`j-mut-outsider-passes`（`doorAction…`）、`j-mut-membrane-ignores-outsider`、`j-mut-membrane-ignores-doorway`（`membraneIsSealed…`）、`j-mut-reload-on-first-runtime-observation`、`j-mut-reapply-every-tick`（`doorReapplication…`） | 7 個都只有目標 test FAIL |
| GameTest mutation | 同上＋adapter（`plan-mut-g.json`、`chain-mut-g.log`） | 14 個 `gate-mut-g-*`（名稱見上方「RED」表） | 14 個都 FAIL 在目標 witness（`mut-g-*-summary.txt`） |
| `270af2d` | `3b9bbb0`＋`swap.ps1 -To c4` | `c4-junit`；`gate-c4-m5-bb4b74b83949431c8297e08900079e25` | 156 個 JUnit PASS（CST 19）；P-NEW、P-POWER、P-REVISIT、T-WF、P-CROSS、P-DEGRADED、F4a 的 sealed_pending 7/7 |
| `287d216` | `270af2d`＋`swap.ps1 -To c5` | `c5-junit`；`gate-c5-default-27c91532ada240d281823dd7f45a6216`；`gate-c5-m5-c9527cb3a4c244aabc27fd2264d61d9b` | 157 個 JUnit PASS（CST 20）；default 28/28；M5 4/4 |
| run-all | `7ad2ddc`，工作樹乾淨（`STATUS` 是空的） | `run-all-4c73feaa25344beea8867cac2ba44074`（Full：`gate-full-7b461785cde64c449f28210109acb028`；M5：`gate-m5-gametest-8ca0268f8f384bf6bad7b892f702cad1`；`f4b3/run-all-console.txt`） | `END ALL exit=0 HEAD=7ad2ddc`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=36/36 perTest=51+1 junit=636 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=410entries/363classes sources=217 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS` |

- 不採計的 root：
  - `gate-dev-door-m5-1-…`（8 個 batch，7/8）：T-RELOAD 首版有兩個 test 的缺陷：看到 `unloaded()` 的同一個 tick 就恢復強制載入，chunk 還在記憶體裡、塌縮流程看不到卸載，第二次沒有重新套用；而且以「套用 tick 等於排入 tick」斷言關門，但關門要等門口的 entity storage 載入（實測晚 42 tick）。改成多等 3 個 tick 再恢復、關門改斷言「不早於排入的 tick」之後，`dev-door-m5-2` PASS。不是 production 的問題。
  - `gate-green-a-default-3d54962acb4f42abb93c32f52598118e`：focused 的 batch 清單放了 `defaultBatch`，testmod 的 `onlyBatches` 過濾要求每個 batch 恰一個 test，server 啟動即失敗、XML 是上一次的（gate.ps1 標 STALE）。拿掉之後是 `green-a2-default`；M1 的門 test 由 run-all 的 Full 涵蓋。
- 與基準 run-all（`run-all-4d864a16e1cf45fd90cdfe3c3d35b0ac`，`97f1898`）逐項比較：
  - M5 30 → 36、JUnit 631 → 636（「測試」）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24、mainOnly、CI 兩個平台都不變。M5 oracle PASS（M5 root 的 `m5-oracle.json`）：36 個 test 的 catalog 增量逐一串接（`existing_receipt_without_a_discovered_universe_seals_permanently` 與 T-RELOAD 為 0，其餘各 1），每個都 converged、preserved，suite 結束時 34 筆。`M3_UNIVERSE_STOPPED_VERIFIED` 的 worlds 23 → 28（多出的 5 與新增的配置宇宙的 test 數相同），`quarantine=0`、`contextDetached=true` 不變。
  - release JAR 401 → 410 entries（354 → 363 classes，`f4b3/jarcmp-release-run-all.txt`）：新增 `ChamberCollapsePort` 與它的 `$Projections`、`$Projections$1`（`NONE`）、`$Doors`、`$DoorUse`（`Doors.NONE` 是 lambda，不產生 class 檔）、`WorldChamberDoorAdapter$1`（`toggle` 對 `DoorUse` 的 switch map）、`CollapseService$DoorAction`、`$DoorUser`、`ProjectionProtectionIndex$2`（`registerEvents` 註冊的匿名 `Projections`，與既有的 switch map 一起重新編號）；沒有移除。內容不同的 class 都屬於本 task 改過的 production 檔（`ChamberControllerBlock`、`ChamberPowerCoordinator`、`ChamberProtectionService`、`QuantumBulkheadBlock`、`WorldChamberDoorAdapter`、`CollapseService`、`ProjectionProtectionIndex` 與它們的 nested class）；`CollapsePhase` 只改 Javadoc、行數不變，class 相同。testmod 命中 0。
  - sources JAR 216 → 217（`f4b3/jarcmp-sources-run-all.txt`）：新增 `ChamberCollapsePort.java`；內容不同的是上列 7 個檔與 `CollapsePhase.java`。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`f4b3/wbcmp.py`）：Full 54／28 不變、逐行相同（`f4b3/wbcmp-full-run-all.txt`）；legacy 0／0（`wbcmp-legacy-run-all.txt`）。M5 WARN 29 → 32、ERROR 6 不變（`wbcmp-m5-gametest-run-all.txt`），多出的 3 則都是 test 刻意造成、有 witness 的：T-REJ 的「塌縮流程的寫入 SEALED 沒有完成（REJECTED）」與重試成功之後的「塌縮進入 SEALED(PROJECTION_FAILED)，可重試」（`oneRejectedWarn`）、T-MEM 的「通道尚未實作（F5）」（`onlyTheReadyStubWarnAndNoError`）。GT-P 的原版 `PistonBlockEntity` WARN 仍是一則，排序比對中的一減一加只是 object hash 不同。
  - default run 的稽核：Full 的 latest.log 對「原艙門套用 sealed」「原艙門維持一般開門」「PREPARE 期間」「原艙門的攔截」「原艙門已依 collapse view 重新套用」「PREPARE 暫停」「進入 PREPARE」「quantumchamber_destination」都是 0 行；「重新套用原艙門」1 行（G-DOR 的 `NORMALIZE` 排入，見上方稽核表）。
- 本 task 沒有遇到派工列出的已知偶發（G-NLIVE、Windows journal 的 `AccessDeniedException`、H2 多出的原生 ERROR／WARN、`m2_task4_disconnect_io` 多一則 WARN、`unloadChunks` 停服卡住、boundary oracle 挑到舊 receipt）；失敗的 root 都是刻意的 RED 或 mutation，以及上列「不採計的 root」。

### 偏離 plan／spec 字面之處

1. **第 10 組的 PREPARE 版本沒有在同一個 instance 內讓來源 chunk 卸載**：COLLAPSING（含 PREPARE）持有來源 ticket（`SuperpositionSessionManager.sourceTicket`：Controller 所在 chunk、半徑 2，涵蓋門與閘門看的艙體外擴 3 格），所以 PREPARE 期間來源 chunk 在同一個 instance 內不會卸載；只有重啟會讓它「重新載入」。改以 T-START 模擬重啟之後的第一次觀察（`simulateDoorStartup`，比照 F3d 的 `markStartup`），真正跨 JVM 的版本屬 F6（plan F6 已列「sealed 量子膜期間 crash → 重啟後停在 WAITING，門是一般開門」）。SEALED 已釋放來源 ticket，所以 SEALED 的版本以 T-RELOAD 真的讓 chunk 卸載再載入。
2. **WAITING 等一般狀態的階段，重新套用只把量子膜改為一般開門，不把關著的門打開**（`NORMALIZE`）：補充第 5 點寫「開服時就是 WAITING：門要是一般開門，不得留下有碰撞的膜」。帶著量子膜的門一律改為一般開門；本來就關著的門是一般狀態的合法狀態（§7.3 表：已返還的人可以照常開關），保留不動，關門沒有碰撞以外的風險，裡面的人可以照一般方式開門。閘門失效（`OPEN_ON_LEAVE`）照 §7.3 的字面一律改為一般開門。
3. **「艙內」以 bbox 與 interior 相交判定**（套用量子膜的條件、攔截的 INSIDER）：spec 的字面是「bbox 在 interior 內」。相交包含完整在內，兩處都偏向不套用有碰撞的門、讓人可以開門；跨在門口的人另由門口的規則涵蓋。參與者的邊緣觸發與閘門仍是「完整在內」（F3d）。
4. **會讓門變成有碰撞的轉換，先等門口的 entity storage 載入**：spec 沒有寫；重啟或 chunk 重新載入之後 entity 是非同步載入的，不等就看不到門口的 entity（T-RELOAD 的 `mut-g-close-no-entity-wait`）。PREPARE 時沒有載入就當成門口有人（一般開門）；關門時等它載入、不寫入。
5. **讀不到 journal 時不攔截**：§7 :336 要求 roll-forward 未確認時拒絕；journal 不健康時根本讀不到 collapse view，這時塌縮流程本身也不推進，而 M1–M4 的門行為不得改變（補充第 10 點），所以照一般門的邏輯。處置途中的失敗才拒絕、不改門。
6. **PASSAGE_READY 只實作「roll-forward 未確認時拒絕」**：其餘（參與者結束通道、非參與者拒絕，§10.5）屬 F5，接在同一個 `doorAction`。
7. **commit 拆分**：plan Step 4 是一個 commit；依派工依子主題拆成上列 7 個 commit 與 docs commit。派工例子的「量子膜的套用」「閘門失效與 REJECTED／隔離」「門狀態的重新套用」合成 `270af2d` 一個 commit（同一套待套用門的目標與 `Track` 的簿記，分開的中間版本要拆開同一批欄位）；攔截是 `287d216`。
8. **新增 testmod mixin `PJF`**：F4a 交給 F6 的「journal `put` 的 fault Slot」（F4a 交給後續的 S2-mB），T-REJ 需要它，所以先在本 task 加上（`M5TestHooks.JOURNAL_PUT_FAULT`、`installJournalPutFault`）；F6 可以直接用它補 `sealRetryDue` 接線的 test。
9. **（fix round 1）找出所屬 session 之前的非預期例外，拒絕並記 WARN、不去重**：Q-I1 的去重以 session 為單位（`warn`），而這時沒有 session 可以歸屬，也無法判斷門是否屬於塌縮流程；journal 健康時 `sessionAt` 只讀記憶體內的 record，不會走到。不另外加一個不屬於 session 的去重欄位（派工要求刪除 `doorUseWarning`）。
10. **（fix round 1）SEALED 的門被玩家使用時，取消的是同一個 session 所有還沒套用的 `CLOSE_ON_SEALED`**：Q-M2 的字面是「取消重新套用」；進入 SEALED 時排入、還在等待（entity storage、chunk 不是 FULL、退避）的關門，同樣會在玩家開門之後把門關回去，所以一併取消（§7.3 :404）。取消在門切換之前：門切換本身被拒時（門板不完整），門維持原狀，也不再由塌縮流程關上。
11. **（fix round 1）重新套用的排入 INFO 保留**（N-3 只改完成的 log）：理由見「Fix round 1」的 N-3。

### 裁示（controller，F4b-3 spec review 之後；原「需要裁示」）

上一輪回報列出、需要裁示的各項讀法（含下列三條），spec reviewer 都判定可以接受或符合 spec，controller 正式接受。

1. 「偏離」第 1 條：第 10 組的 PREPARE 版本以模擬重啟（T-START）代替同一個 instance 內的卸載，真正的重啟留給 F6。
2. 「偏離」第 2 條：一般狀態的階段重新套用時，關著的門保留關著。
3. Controller 的攔截處理之後（INTERCEPTED）照常顯示既有的門切換訊息；拒絕時不顯示訊息（spec §10.8 沒有拒絕的訊息）。

### 觀察（未修改）

1. **重新套用的觀察看的是「FULL」，同一個 tick 內的卸載又載入看不到**：chunk 還在記憶體裡時門的方塊也沒有重新讀入，不需要重新套用；T-RELOAD 因此在卸載之後多等 3 個 tick。
2. **PREPARE 中的 roll-forward 未確認造不出來**：COLLAPSING 的 roll-forward 寫入（返還、清 lease）都在 PREPARE 之前；PREPARE 中的 roll-forward 只有進入 SEALED，那時 collapse view 已是 SEALED（照一般門的邏輯）。§7 :336 對 PREPARE 的拒絕只由 JUnit 鎖住；F5 的 PASSAGE_READY（`Crossed` 未確認）才會真的走到。
3. **隔離只在塌縮流程自己造成的那個 tick 看得到**：recovery 的 tick 排在塌縮流程之前，每個 tick 開頭就以 `convergeQuarantined` 收斂（同時解除隔離），所以只有塌縮流程自己的 restore 類寫入或終局在 CAS 不符時隔離、或 `convergeQuarantined` 一直失敗時，塌縮流程才會看到隔離中的 PREPARE。這條路徑只有程式與 Javadoc，沒有 GameTest。
4. **重新套用之前 Controller 的刷新先跑**：chunk 重新載入時，load-sync 會排一次 Controller 刷新；presence 是 `COLLAPSE`，刷新不改門，與重新套用不衝突。
5. **門口的盔甲座在 chunk 重新載入之後要等 25～45 tick 才看得到**（T-RELOAD 在採計的 root 的實測，見 RED 表；fix round 1 的 run-all 是 60、51 tick，見「Fix round 1」）；這段期間 SEALED 的門不關（等待），不會困住人。

### 交給後續

- F5：
  - PASSAGE_READY 期間的門（§10.5）接在 `CS:1225` `doorAction`：參與者 → 結束通道（先終局、再改門，§10.6 (a)）、非參與者 → 拒絕；roll-forward 未確認時已拒絕。`CS:1239` `useDoor` 與 `CS:1253` `door` 不必改。passable 量子膜由 F5 套用時，`prepareDoor` 只在 PREPARE 擁有門時執行，READY 之後不會把它改回 sealed。
  - §10.6 :613 的正規化要涵蓋「先終局、再改門」之間 crash 留下的量子膜（上方「補充第 8 點」的四個條件）；有 COLLAPSING／SEALED 時不要與 `watchDoor` 的重新套用同時寫門。
  - **Step 0（必要，fix round 1 controller 派工）**：先把原艙門相關的程式從 `CS`（fix round 1 之後約 1950 行）抽成 package-private 類別（例如 `OriginDoors`），範圍包括 `PendingDoor`／`DoorGoal`／`DoorStep` 系列、`applyDoor`、`watchDoor`／`reapply*`（含 `reapplyCause`、`cancelSealedClose`）、`door`／`doorAction`／`openForInsider`／`endByParticipant`、門口系列（`doorwayEntitiesLoaded`、`doorwayOccupied`、`doorwayBox`）。行為不變，以獨立 commit 完成；之後才在上面接 §10.5 與 :613。F4b-2 交給 F5 的「評估抽出 PREPARE」一併在這個 Step 0 決定。
  - **Step 0 的追加項目（controller 補，fix round 1 的 spec re-review 與 quality re-review 留下的 Minor 與 Nit）**。以 `3c9b42c` 為準：
    - 抽出的範圍另含 `prepareDoor`、`releasePrepareDoor`、`membraneTarget`、`outsiderInside`、`doorHasMembrane`，以及 Track 上與門相關的欄位（`doorLoaded`、`doorSeenLoaded`、`membrane`、`doorReleased`）。寫明它們是跟著門一起抽，還是跟著 PREPARE 一起決定（quality Nit）。
    - quality M-1：DOOR_USE 的去重鍵目前會被同一個 session 任何人的成功使用清掉（`CS:1314-1315`）。改成依 action 區分，例如 `DOOR_USE + action`，成功時只清同一個 action 的鍵；或在 note 寫明維持現狀的理由。T-FAIL 的 `warnedAgainAfterASuccess` 也要對應調整。
    - quality M-2／spec m-3：T-FAIL 的 `breakParticipantDoorUse`（`SUP:765`）以反射把 final 的 `Track.backoff` 設成 null。F5 在準備完成點提交 PASSAGE_READY 時會讀 `track.backoff`，這個 test 會因此 NPE 而失敗。改成走 Slot 的 testmod 注入，例如在 `endByParticipant` 開頭丟出例外。README「新增 M5 GameTest」補上 F4b-3 fix round 1 新增的 helper：`useDoor`、`lookupFailingPlayer`、`putUncheckedAuthority`／`removeUncheckedAuthority`、`discardDoorwayEntities`、`RemoteOrigin.entitiesLoaded`。
    - spec m-1：`CS:1319` 是找出所屬 session 之前的 WARN，沒有 `session=`，marker 要改為 `TICK_LOG_MARKER`。
    - spec m-2：`cancelSealedClose` 在門切換之前就取消。若之後 toggle 被拒或 rollback，SEALED 艙會留著 sealed 量子膜，而 :613 的第 1 個條件把有 SEALED session 的艙排除在外。二擇一處理：
      - 在 `doorHasMembrane` 為真時不取消；
      - 或讓 :613 的正規化也涵蓋「SEALED 帶著量子膜」。
    - quality Nit：T-RELOAD 各段有界等待的總和最壞約 2214 tick，超過 tickLimit 2000。縮小第三段的上限，或調高 tickLimit。
- F6：
  - 重啟矩陣：sealed 量子膜期間 crash → 重啟後停在 WAITING、門是一般開門（T-START 的跨 JVM 版本）；SEALED 開服後關門一次、門口有人時一般開門；COLLAPSING 在 PREPARE 中重啟 → 依條件重新套用 sealed（第 10 組的 PREPARE 版本，fix round 1 spec S-M3）。
  - spec S-M1（fix round 1）：隔離期間 PREPARE 的原艙門交還（`3c9b42c` 的 `CS:1161-1164`，`watchDoor` 的隔離分支）目前沒有任何 test 或 mutation。補一個 phase 或 mutation（例如拿掉 `CS:1162` 的 `releasePrepareDoor`）。
  - `build.yml` 的明列清單：本 task 新增 M5 run 的 7 個 test（fix round 1 加 T-FAIL；都不是 `_windows`）。
  - `PJF`（`installJournalPutFault`）可以補 F4a 交下的 `sealRetryDue` 接線 test（S2-mB）。
- F7：
  - 玩家說明的已知限制（上方「補充第 7 點」）：sealed 量子膜可以掛掛件、會傳導強充能；改為一般開門時掛件掉落；只靠門上強充能供電的艙，量子膜改為一般開門時會結束 PREPARE。
  - 玩家說明：PREPARE 期間（量子膜出現之後）參與者開關原艙門會結束塌縮（宇宙保留，不重抽）；艙內的非參與者可以開門離開；艙外的人打不開。
  - spec errata：§7.3 第 10 組的「來源 chunk 卸載」在 COLLAPSING 期間只會因重啟發生（來源 ticket）；「艙內」的判定與門口的 entity storage 等待（「偏離」第 3、4 條）；一般狀態的階段重新套用時保留關著的門（「偏離」第 2 條）。
  - README「預期結果」的數字以最新的 run-all 為準更新（fix round 1 之後：M5 37、JUnit 636、release 410／363、sources 217）。
  - 玩家說明（fix round 1）：SEALED 的原艙門被玩家打開之後，不會被塌縮流程關回去；只有之後來源 chunk 真的重新載入（或重啟）時才再關一次。

### Fix round 1（`ae3f1bc`、`bbf7aa2`、`3c9b42c`）

- Review：spec review ✅（Critical 0、Important 0、Minor 4：S-M1～S-M4）；quality review Not approved（Critical 0、Important 1、Minor 4、Nit 5：Q-I1、Q-M1～Q-M3、`openForInsider`、N-1～N-4）。上一輪回報列出、需要裁示的各項讀法，spec reviewer 都判定可以接受或符合 spec，controller 正式接受（「裁示」）。controller 的 fix round 1 派工處理 Q-I1（與 S-M2 一併）、Q-M1～Q-M3、`openForInsider`、N-1～N-4、S-M3、S-M4 與 note 的交接；S-M1 交給 F6（「交給後續」）。開工 HEAD 是 `fda05fe`，worktree 乾淨。
- Commits（依序；只 stage 自己的檔案）：
  - `ae3f1bc` `fix: refuse failing or unsafe origin door use and warn once per cause`（parent `fda05fe`）：Q-I1、S-M2、Q-M1、`openForInsider`。`CS`；testmod `SUP`（fixture）、`M5U`（新增 T-FAIL）。
  - `bbf7aa2` `fix: keep a sealed origin door a player used open and name the reapply cause`（parent `ae3f1bc`）：Q-M2、N-3、N-4。`CS`、`CST`；testmod `SUP`（`simulateDoorStartup`、`RemoteOrigin.entitiesLoaded`、`discardDoorwayEntities`）、`M5U`（T-RELOAD 加第三次重新載入；T-START、T-RELOAD 加原因的 witness）。
  - `3c9b42c` `docs: align the collapse door Javadoc and pin the door action anchors`（parent `bbf7aa2`）：Q-M3、N-1、N-2。`CS` 的 Javadoc、`GUARD` 的 Javadoc、`HK` 的 Javadoc、`CST` 的錨點。
  - 本小節由其後的 docs commit `docs: record F4b-3 fix round 1 in the M5 note` 加入；同一個 commit 也改了本節的 Review 行、RED 表第 10 組那兩列（S-M3、S-M4）、補充第 6 點與「觀察」第 5 條的實測數字（S-M4）、「偏離」、「裁示」與「交給後續」。
- Code range `fda05fe..3c9b42c`。沒有改 `ChamberDoorService`、`WorldChamberDoorAdapter`、`ChamberCollapsePort`、mixin 與 `quantumchamber-test.mixins.json`、語言檔、`gates.ps1`、`build.gradle`、`.github/`、`scripts/verification/README.md`，也沒有改派工列為不得修改的檔案。
- 本小節的行號以 `3c9b42c` 為準。證據在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/f4b3/fix1/`（以下簡稱 `fix1/`）：`state.ps1`、`gate.ps1`、`junit.ps1`、`adapted-junit.ps1`、`chain.ps1`、`adapter.py`、`wbcmp.py`、`jarcmp.py` 由 `f4b3/` 複製，只改 repo 的相對路徑與註解；adapter 表在 `fix1/adapters_fix1.py`。`f4b3/` 下既有的檔案沒有改動。每個 adapter 跑完都還原，還原後的 blob 與套用前相同；每個 commit 的 blob 都等於它之前最後一輪 GREEN 的 state 記下的 blob（`fix1/commit-c1-state.txt` 對 `green-f1b-m5`、`c1-default`；`commit-c2-state.txt` 對 `green-f1d-m5`、`c2-default`；`commit-c3-state.txt` 對 `c3-m5`、`c3-default`、`c3-junit`）。
- 新增的 1 個 `@GameTest`（M5 run，自己一個 batch，名稱不以 `_windows` 結尾，各平台都執行）：
  - T-FAIL `M5U:2413` `prepare_door_use_failures_refuse_and_warn_once_per_cause_until_recovery_writes_are_safe`（`m5_universe_door_refusal`，tickLimit 1200，catalog 增量 1）
  - T-RELOAD（`M5U:2311`）加第三次重新載入，tickLimit 1500 → 2000（名稱、batch、增量都不變）；T-START（`M5U:2256`）只加一個 witness。

| 項目 | 處置與位置 | 證據 |
| --- | --- | --- |
| Q-I1（Important）：`door()` 在 switch 之前把 `doorUseWarning` 設成 null，持續性的處置失敗每次都記一則附 stack trace 的 WARN | 刪除 `doorUseWarning`。`CS:1279` `door` 的處置途中（含判斷，見 S-M2）丟出例外時，以既有的 `warn(sid, DOOR_USE, failure, 處置, …)`（`CS:123` `DOOR_USE`＝「原艙門的攔截」）依 (session, 種類, `dedupeKey`) 去重，回 REFUSED、不改門；這個 session 的攔截沒有例外地完成時（任何處置，含 PASS、REFUSE）移除這個鍵（`CS:1315`），所以同一原因「失敗 → 成功 → 再失敗」會再記一次。WARN 的字句沿用 `warn` 的格式（帶 `session=`、處置名稱與之後會發生的事）。T-FAIL：準備完成之後，以 `SUP:765` `breakParticipantDoorUse`（反射把這個 session 的 `Track.backoff` 換成 null：參與者關門的處置每次都在退避檢查丟 `NullPointerException`；準備完成之後的 PREPARE tick 不讀它）讓 P 以 bulkhead 連點兩次：`failingCloseRefusedTwice`、`oneWarnForTwoFailingCloses`（WARN 只多一則）、`sealedAndCollapsingAfterTheFailures`；艙內的非參與者 N 開門成功之後 P 再點一次：`warnedAgainAfterASuccess`（再多一則） | RED `red-f1a`（`fda05fe`＋T-FAIL，`gate-red-f1a-0cadb4f692e34744922ae4f39f29fa85`）：`oneWarnForTwoFailingCloses=false`（兩次點門兩則 WARN）。GREEN `green-f1a-m5`（門的 9 個 batch）、`green-f1b-m5`。mutation（`fix1/chain-mut-f1.log`）：`mut-f1-key-cleared-before-handling`（處置之前清掉去重鍵，即原本的缺陷）`oneWarnForTwoFailingCloses=false`；`mut-f1-success-keeps-key`（成功時不清除）`warnedAgainAfterASuccess=false` |
| spec S-M2：`door()` 第一段 catch 範圍過寬，任何例外都回 PASS 並以「讀不到 journal」記 DEBUG | 第一段只剩 `SessionRecoveryState.get(server)` 與 `requireHealthy()`（`CS:1283`）：只有 journal 不健康時不攔截（DEBUG）。之後的 `sessionAt`、身分與 `doorAction` 的判斷都在第二段（`CS:1291` 起），非預期的例外照 Q-I1 拒絕並去重記 WARN（處置名稱記為「判斷」），不交給門服務。找出所屬 session 之前的例外（journal 健康時 `sessionAt` 只讀記憶體內的 record，不會發生）沒有 session 可以歸屬：拒絕並記 WARN、不去重（`CS:1319`，「偏離」第 9 條）。T-FAIL：判斷身分時丟例外的非參與者（`SUP:752` `lookupFailingPlayer`）直接呼叫 port 兩次（`SUP:744` `useDoor`）：`lookupFailureRefusedTwice`、`lookupFailureWarnedOnce`、`sealedAfterTheLookupFailures` | RED `red-f1a`：`lookupFailureRefusedTwice=false`（PASS）、`lookupFailureWarnedOnce=false`（只有 DEBUG）。mutation `mut-f1-lookup-passes`（判斷途中的例外照舊回 PASS）：`lookupFailureRefusedTwice=false`、`lookupFailureWarnedOnce=false` |
| Q-M1：攔截的終局寫入沒有檢查 `recoveryWritesSafe()` | `CS:1386` `endByParticipant`：前置條件依序是 `journal.recoveryWritesSafe()`（`CS:1388`，比照 `tick` 的 `CS:228` 與 `SSM:496`）、current＝flushed、退避期之外；不符時 REFUSED、記 DEBUG（帶原因）、不改門。T-FAIL：還原 P 的處置；`SUP:780` `putUncheckedAuthority`（新的 ARMING session，candidate context 沒有 flush 過；暫停、不衝突的 chamber、玩家與 slot）讓 `recoveryWritesSafe()` 為 false 時 P 關門：`refusedWhileRecoveryWritesAreUnsafe`、`uncheckedAuthorityNotWrittenOut`、`noWarnWhileUnsafe`；同一個 callback 內移除（`SUP:796`）之後 P 關門：`closesOnceSafe`（checked 終局、門關上） | RED `red-f1a`：`refusedWhileRecoveryWritesAreUnsafe=false`、`uncheckedAuthorityNotWrittenOut=false`（終局的 flush 把那筆未 checked 的 ARMING 寫出了）、`closesOnceSafe=false`。mutation `mut-f1-ignore-recovery-safety`：同樣三個 false |
| `openForInsider` 不論結果一律回 HANDLED | `CS:1359`：依 `applyTarget` 的結果回傳。APPLIED → HANDLED（INFO）；UNCHANGED（門已是一般開門）→ HANDLED（DEBUG，N-3）；NOT_LOADED、DOOR_INCOMPLETE、ROLLED_BACK、ROLLBACK_FAILED → REFUSED（DEBUG）：門沒有改，艙內有非參與者時下一個 tick 的 `prepareDoor` 照樣改為一般開門（失敗時由它去重記 WARN、退避）。T-FAIL：N 在艙內、25 格之一暫時換成石頭（同一個 callback 內放回）時直接呼叫 port：`insiderRefusedWhenTheDoorCannotChange`、`sealedAfterTheIncompleteDoor` | mutation `mut-f1-insider-always-handled`（照舊回 HANDLED）：`insiderRefusedWhenTheDoorCannotChange=false`（這個 witness 在 `red-f1a` 之後才加入，以 mutation 證明） |
| Q-M2：重新套用排入的 SEALED 關門，會蓋掉等待期間玩家開的門 | `CS:1335` `cancelSealedClose`：SEALED 的門照一般門的邏輯使用時（`door` 的 PASS，`CS:1304`），移除這座艙由同一個 session 排入、還沒套用的 `CLOSE_ON_SEALED`（重新套用的，以及進入 SEALED 時排入、還在等待的；「偏離」第 10 條），記 INFO 一則。之後只有下一次真的重新載入才再關門一次。T-RELOAD 第三次：Q 關門 → 卸載、載入 → 排入的關門還在等門口的 entity storage 時（`closeStillPendingWhenQueued`）Q 開門（`opensWhileTheCloseIsPending`）→ entity storage 載入之後兩個 tick（`SUP:699` `RemoteOrigin.entitiesLoaded`）：`staysOpenOnceTheEntityStorageLoads`（門仍開著、沒有第三次套用）、`onePendingCloseCancelled` | RED `red-f1b2`（`bbf7aa2` 的 testmod＋`ae3f1bc` 的 `CS`、`CST`，adapter `red-f2-cs-at-commit1`；`gate-red-f1b2-a55272d8df7d413e99a7c305f382d209`）：`staysOpenOnceTheEntityStorageLoads=false`（排入的關門在 entity storage 載入之後把門關回去）、`onePendingCloseCancelled=false`。mutation `mut-f2b-no-cancel`（不取消）：同樣兩個 false |
| Q-M3：Javadoc 與程式不一致 | `CS:796` `prepared`、`CS:623` `prepareUniverse`：準備完成之後的 PREPARE tick 不再預載或投影，只判斷原艙門（sealed 量子膜，或門口有人、艙內有非參與者時的一般開門）。`CS:160` `doors`：補上 `CLOSE_AFTER_TERMINAL`、`OPEN_ON_LEAVE`、`NORMALIZE`，並寫明 PREPARE 每個 tick 的門在 `Track#membrane`。`CS:151` `startupSessions`：補上重新套用也用它。`HK:52`：兩個探針（journal flush、原生存檔 checkpoint）、寫出稽核與四個故障注入（物化、checked NBT 讀取、投影寫入、journal put） | 只有 Javadoc：`c3-junit`（CST 20、`GUARD` 2、PPIT 12、`CollapseLogMarkerGuardTest` 2）；`c3-m5` M5 37/37（全部）、`c3-default` 28/28（與 `c2-default` 的 test 集合與結果相同） |
| N-1：`doorActionFollowsThePrepareDoorRules` 的錨點 | `CST:468` 起補上 COLLAPSING＋UNIVERSE＋PREPARE 擁有原艙門時的 INSIDER（OPEN_FOR_INSIDER）、OUTSIDER（REFUSE），以及不擁有時的 PARTICIPANT（PASS） | `c3-junit` |
| N-2：守門 test 的 Javadoc | `GUARD:16`：「chamber package 的原始碼不直接參考 collapse package」，並寫明執行期間仍經 port 呼叫 collapse 註冊的實作、守門只約束原始碼裡的直接參考 | `c3-junit` |
| N-3：log 量 | `openForInsider` 的 UNCHANGED 記 DEBUG（見上）。重新套用的完成只有 APPLIED 記 INFO（`CS:1582`），UNCHANGED 與「門沒有量子膜」記 DEBUG（`CS:1568`、`:1585`）。排入（或延後）的 INFO 保留：它記下的「下一個 tick（tick N）」是「不在 chunk 載入的當下寫方塊」的證據，T-START、T-RELOAD 以它比對套用的 tick；改成 DEBUG 要讓 test 的 log capture 收 DEBUG 並改寫 tick 的解析，所以不改（每次重新載入一則，量很小）。default run 仍是 G-DOR 的一則排入 | GameTest 的 tick 解析不變；`c3-m5`、run-all |
| N-4：`reapplyDoor` 的字句要能區分「重啟之後第一次載入」與「重新載入」 | `CS:1181` `reapplyCause(startupSession, seenLoaded)`（純函式）：開服時就在、本 instance 還沒看過來源艙已載入的 session 是「重啟之後第一次載入」（重啟之後來源 chunk 是非同步載入的，第一次觀察常常還沒載入，所以不只看是不是第一次觀察），其餘是「重新載入」。`Track.doorSeenLoaded`（`CS:1783`）在 `watchDoor`（`CS:1149`）記下；排入與延後的 INFO 都以「來源艙重啟之後第一次載入：…」或「來源艙重新載入：…」開頭。`SUP:725` `simulateDoorStartup` 一併重設 `doorSeenLoaded`。T-START：`queuedAsTheFirstLoadAfterARestart`；T-RELOAD：`queuedAsReloads`（三次都是「重新載入」） | JUnit `CST:517` 起的 4 列；mutation `j-mut-f2-cause-ignores-seen`（只看是不是開服時就在）：`doorReapplicationTriggers…` FAIL（「開服時就在、看過已載入之後卸載再載入」）。`red-f1b2` 的 `queuedAsReloads=false`（舊字句） |
| spec S-M3：第 10 組那一列 | RED 表第 10 組改寫成「WAITING 的版本由 T-START 證明；PREPARE 的版本交給 F6」 | 純文件 |
| spec S-M4：note 引用的實測數字 | 改用採計的 root：GREEN `gate-green-a-m5-b9294ff30f914b4094973bb06ac38442` 的 `M5_DOOR_RELOAD queued=[5298, 5389] applied=[5343, 5414]`（等了 45、25 tick），run-all `gate-m5-gametest-8ca0268f8f384bf6bad7b892f702cad1` 的 `queued=[5784, 5866] applied=[5818, 5908]`（34、42 tick）；範圍寫成 25～45 tick（RED 表、補充第 6 點、「觀察」第 5 條）。原本的 27～42 取自不採計的 `dev-door-m5-1`、`dev-door-m5-2` | 純文件 |

證據（逐輪的 state 記 HEAD、porcelain 與逐檔 blob）：

| 輪次 | HEAD／工作樹 | Root | 結果 |
| --- | --- | --- | --- |
| RED（commit 1） | `fda05fe`＋T-FAIL（`SUP`、`M5U`） | `gate-red-f1a-0cadb4f692e34744922ae4f39f29fa85` | FAIL（見上表） |
| GREEN（commit 1） | `fda05fe`＋`CS`、`SUP`、`M5U` | `green-f1a`（JUnit）；`gate-green-f1a-m5-4359bb19c93b40439342beb2eab42e6b`（門的 9 個 batch）；`gate-green-f1b-m5-5edd8bbfa0c545be95b6674c10c5a976`（T-FAIL 加上門改不了的 witness 之後）；`gate-c1-default-9629918b2d8b4702bb1011b898046fde` | CST 20、`GUARD` 2、PPIT 12、`CollapseLogMarkerGuardTest` 2；9/9；1/1；default 28/28 |
| mutation（commit 1） | 同上＋adapter（`fix1/plan-mut-f1.json`、`chain-mut-f1.log`） | `gate-mut-f1-key-cleared-before-handling-41ada0558d0d4e5189658a2cb16ec7fe`、`gate-mut-f1-success-keeps-key-4b6f245fb27349e8adec41c3b9126144`、`gate-mut-f1-lookup-passes-7114f8a5db2647d48d5780ae25cd9bfd`、`gate-mut-f1-ignore-recovery-safety-25adc86abb164971a17094f7ba3cbd15`、`gate-mut-f1-insider-always-handled-1b35b1726aae4d51a9fd589d8d9cb653` | 5 個都 FAIL 在目標 witness（`fix1/mut-f1-*-summary.txt`） |
| RED（commit 2） | `ae3f1bc`＋T-RELOAD 第三次 | `gate-red-f1b-f83d61b5b297490ebdd7c4f34aabc124`（不採計，見下方） | — |
| RED（commit 2，重做） | `bbf7aa2` 之前的工作樹＋adapter `red-f2-cs-at-commit1` | `gate-red-f1b2-a55272d8df7d413e99a7c305f382d209`（`fix1/chain-mut-f2b.log`） | FAIL（見上表） |
| GREEN（commit 2） | `ae3f1bc`＋`CS`、`CST`、`SUP`、`M5U` | `green-f1c`（JUnit）、`j-mut-f2-cause-ignores-seen`；`gate-green-f1d-m5-6f3e9523f1b54e22985074db8031037b`（門的 9 個 batch）；`gate-c2-default-a7e1ae887d7e4316ae367d73506ed5cd` | CST 20；mutation FAIL；9/9；default 28/28 |
| mutation（commit 2） | 同上＋adapter | `gate-mut-f2b-no-cancel-6ebcc087064043a0836240b1d25a7f00` | FAIL（目標 witness） |
| commit 3 | `bbf7aa2`＋`CS`、`CST`、`GUARD`、`HK`（＝`3c9b42c`） | `c3-junit`；`gate-c3-m5-dcaebd6fb8a14ec7a4cf846c732a646d`（M5 全部）；`gate-c3-default-029aa06a9ee7464e9a5258984e922d25` | 20／2／12／2；M5 37/37；default 28/28 |
| run-all | `3c9b42c`，工作樹乾淨（`STATUS` 是空的） | `run-all-9a5c30415e954e88848c98cfb42f13c3`（Full：`gate-full-399301f211f04a87acb4b2e827c106e2`；M5：`gate-m5-gametest-28cf98f14cd94e69ac1ab404108fc9de`；`fix1/run-all-console.txt`）；Full 以新 label 重跑：`gate-full-rerun-06323a8890f34205a3c40da2062be331` | `END ALL exit=0 HEAD=3c9b42c`。`SUMMARY default=174/174 windows=88 legacy=1/1 m5=37/37 perTest=51+1 junit=636 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=410entries/363classes sources=217 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 ci=win:PASS,ubuntu:PASS`；重跑 174/174 |

- 不採計的 root：
  - `gate-red-f1b-f83d61b5b297490ebdd7c4f34aabc124`：T-RELOAD 第三次的首版有 test 的缺陷：移走門口的盔甲座時用的是卸載之前拿到的參照，第二次重新載入之後門口的是從存檔重新載入的另一個 entity，所以盔甲座還在門口；排入的關門因門口有 entity 改套一般開門（UNCHANGED），`staysOpenOnceTheEntityStorageLoads=false` 只是因為舊程式對 UNCHANGED 也記「已重新套用」的 INFO，不是門被關回去。`mut-f2-no-cancel`（`gate-mut-f2-no-cancel-1eb2cd6856b6433fa7a34be835159e5c`，同一個缺陷）因此只有 `onePendingCloseCancelled=false`。改成以世界查詢移走門口的 entity（`SUP:824` `discardDoorwayEntities`，witness `doorwayClearedBeforeTheThirdReload`）之後重做 RED（`red-f1b2`）與 mutation（`mut-f2b-no-cancel`），兩者都是門真的被關回去。不是 production 的問題。
  - `gate-green-f1c-m5-c1bedc9ebc9b4b37bcbc997f49828b09`（9/9）：同一個 test 缺陷的版本，由修正之後的 `green-f1d-m5` 取代。
- 與 F4b-3 的 run-all（`run-all-4c73feaa25344beea8867cac2ba44074`，`7ad2ddc`）逐項比較：
  - M5 36 → 37（下方「測試」）；default 174（`_windows` 88）、legacy 1、perTest 51＋1、JUnit 636、windowsCheckpoint 17、M3 4＋5、M4 recovery 24、mainOnly、CI 兩個平台都不變。M5 oracle PASS（M5 root 的 `m5-oracle.json`）：37 個 test 的 catalog 增量逐一串接（`existing_receipt_without_a_discovered_universe_seals_permanently` 與 T-RELOAD 為 0，其餘各 1），每個都 converged、preserved，suite 結束時 34 → 35 筆。`M3_UNIVERSE_STOPPED_VERIFIED` 的 worlds 28 → 29（T-FAIL），`quarantine=0`、`contextDetached=true` 不變。
  - release JAR 410 entries（363 classes）不變（`fix1/jarcmp-release-run-all.txt`）：沒有新增或移除；內容不同的只有 `CollapseService` 與它的 nested class（本輪唯一改過的 production 檔）。testmod 命中 0。
  - sources JAR 217 不變（`fix1/jarcmp-sources-run-all.txt`）：內容不同的只有 `CollapseService.java`。
  - log（以 `/WARN]`、`/ERROR]` 計行，UUID 與數字正規化、排序之後比對，`fix1/wbcmp.py`）：
    - Full：run-all 這次是 55／28（`fix1/wbcmp-full-run-all.txt`）。多出的一則是 `m2_task4_disconnect_io` 的「艙體 … 的 session 協調失敗，保留返還保護」：stack trace 是那個 test 受控拒絕的 DISCONNECT RETURNING journal flush，經 Controller 的 scheduled tick → `ChamberPowerCoordinator.reconcile` → `returnToOrigin`，不經原艙門的程式，即派工列出的已知偶發（54→55）。保留 root、不調查，以新 label 重跑 Full（`full-rerun`）：174/174，WARN／ERROR 54／28，與基準逐行相同（`fix1/wbcmp-full-rerun.txt`）。
    - legacy 0／0（`wbcmp-legacy-run-all.txt`）。
    - M5 WARN 32 → 36、ERROR 6 不變（`wbcmp-m5-gametest-run-all.txt`）：多出的 4 則都來自 T-FAIL、都有 witness：判斷途中的例外一則（`lookupFailureWarnedOnce`）、處置途中的 `NullPointerException` 兩則（`oneWarnForTwoFailingCloses`、`warnedAgainAfterASuccess`），以及 T-FAIL 的「通道尚未實作（F5）」一則（`onlyTheseWarnsAndNoError`）。GT-P 的原版 `PistonBlockEntity` WARN 仍是一則，排序比對中的一減一加只是 object hash 不同。
  - default run 的稽核：Full 的 latest.log 對「取消還沒套用的關門」「原艙門的攔截」「來源艙重新載入」「原艙門已依 collapse view 重新套用」「原艙門套用 sealed」「PREPARE 期間」「進入 PREPARE」都是 0 行；「重啟之後第一次載入」1 行（G-DOR 的 `NORMALIZE` 排入，就是 F4b-3 稽核的那一則「重新套用原艙門」，只改了字句）。
  - T-RELOAD 在 run-all 的實測：`M5_DOOR_RELOAD queued=[5468, 5575, 5633] applied=[5528, 5626] thirdQueuedAt=177 entityStorageAt=219`：前兩次的關門等了 60、51 tick 的 entity storage（比 F4b-3 的 25～45 長；test 只斷言「不早於排入的 tick」與 entity storage 已載入，等待的長短不影響結果），第三次被取消、沒有套用。
- 本輪遇到一次派工列出的已知偶發：run-all 的 Full 多一則 WARN（`m2_task4_disconnect_io`，見上），保留 root、以新 label 重跑。其餘失敗的 root 都是刻意的 RED 或 mutation，以及上列「不採計的 root」。

測試：

- M5 GameTest 36 → 37：新增 T-FAIL（自己一個 batch，catalog 增量 1）；T-RELOAD 加第三次重新載入、T-START 加一個 witness（名稱、batch、增量都不變）。
- JUnit 636 不變（CST 20：`doorActionFollowsThePrepareDoorRules` 加 3 列錨點、`doorReapplication…` 加 4 列原因；`GUARD` 只改 Javadoc）。default GameTest 174（`_windows` 88）、legacy 1、per-test receipt 51＋1、windowsCheckpoint 17、M3 4＋5、M4 recovery 24 不變。
