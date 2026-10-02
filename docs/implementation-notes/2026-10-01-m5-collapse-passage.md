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
