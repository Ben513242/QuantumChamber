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

- Commit：`f7ac878`（parent `f6be3ed`）。只改派工列出的檔案：新增 `ChunkSaveCheckpoint.java`；修改 `MeasuredWorldSaveCheckpoint.java`、`NativeSaveFailures.java`、`NativeSaveFailureAccess.java`、三個 production mixin（`MinecraftServerSaveFailureMixin`、`StorageIoWorkerSaveFailureMixin`、`EntityChunkRecoverySaveMixin`）、`NativeSaveFailuresTest.java`、testmod 的 `MeasuredCheckpointFaultMixin.java` 與 `M4CandidateDoorGameTests.java`。`NativeStorageSaveAccess.java`、`EntityRecoverySaveAccess.java` 沒有修改。
- 新增一個 testmod mixin `ChunkCheckpointScopeFaultMixin`（登記在 `quantumchamber-test.mixins.json`），理由見下方「GameTest」。M4 recovery probe 專用的 `M4RecoveryRegionWriteFaultMixin` 與兩個 drain mixin 沒有修改。
- Review：尚未進行。

### Code-truth（`f7ac878`）

失敗記錄 `NativeSaveFailures`：

- 狀態：單調遞增的 `revision`；`chunkFailures`（chunk → 最近一次失敗時的 revision）；`storageFailure`（最近一次沒有 chunk 位置的失敗時的 revision）。
- 寫入：`observe(future, chunk)` 與 `fail(chunk)` 記到該 chunk；`observeFlush(future)` 與 `failStorage()` 記為整份失敗。
- `verify(before, chunks)`（`:53-61`）：先等所有已受理的 observer 完成，barrier 語意與 H2 之前相同，無關 chunk 的 observer 也要等。之後 `storageFailure > before` 一律拒絕；`chunks` 中任一 chunk 的失敗 revision `> before` 就拒絕；其他 chunk 的失敗不影響。
- 移除不帶位置的 `observe(future)` 與 `verify(before)`，所有呼叫點都必須帶位置或明確標為 flush。

各失敗來源都帶位置：

| 來源 | 記錄位置 | 記法 |
| --- | --- | --- |
| `MinecraftServer.onChunkSaveFailure(error, key, pos)`（含序列化失敗與 chunk、entities、poi 的非同步寫入失敗） | `MinecraftServerSaveFailureMixin` | 依 `key.dimension()` 分世界，每個世界一份 `NativeSaveFailures`，呼叫 `fail(pos)`；`pos` 為 null 時 `failStorage()`。同一世界的三種 storage 合併計入。新增介面方法 `NativeSaveFailureAccess.quantumchamber$saveFailures(RegistryKey<World>)` |
| `StorageIoWorker.setResult(pos, …)` | `StorageIoWorkerSaveFailureMixin` | 每個 worker 一份，`observe(future, pos)` |
| `StorageIoWorker.completeAll(sync)` | 同上 | `observeFlush`：沒有 chunk 位置，失敗時視為該 worker 全部 chunks 都失敗（fail-closed） |
| `EntityChunkDataAccess.handleSaveFailure(future, pos)` | `EntityChunkRecoverySaveMixin` | 每個 entity storage 一份，`observe(future, pos)` |

- 全伺服器累計次數 `quantumchamber$saveFailureRevision()` 保留，只供診斷與既有 probe（`M4CandidateRecoveryProbe.java:569`、`:573`），checkpoint 不再使用。
- 不同世界的相同 chunk 座標不會混在一起：server 端依 dimension 分開，worker 與 entity storage 本來就是每個世界各自一份。

`ChunkSaveCheckpoint.save(MinecraftServer, Map<ServerWorld, Set<ChunkPos>>)`（通用元件，F4b 的投影會再用）：

1. 守門：必須在 server thread；map 不得為空；每個世界必須屬於此 server，相關 chunk 集合不得為空；每個相關 chunk 與它的 entity storage 都必須已載入（`:23-24`、`:41-52`）。接著取得三個來源各自的 `before`。
2. 重新標髒（`setNeedsSaving(true)`＋`markEntitiesForResave`）→ `server.save(false,true,true)`，回 false 視為失敗（`:31`）→ 每個世界做 barrier（`completeAll(true).join()`、`awaitAll(true)`）→ 每個世界 verify 三個來源，只看該世界的相關 chunks（`:64-68`）。
3. 任何例外都重新標髒後重丟。

`MeasuredWorldSaveCheckpoint.save`：

- phase 守門不變，只接受 MEASURED 的 RELEASE_GEOMETRY（`:18`）。spec §13.2 的「也接受 COLLAPSING」不在 H2 範圍。
- 相關範圍不變：來源世界取 `ChamberGeometry.bounds` 涵蓋的 chunks，走廊世界取所有 lease 涵蓋的 chunks。以 `LinkedHashMap`（來源、走廊）呼叫 `ChunkSaveCheckpoint.save`（`:26`）。
- 另加 `source==corridor` 守門（`:22`）。record 建構子已保證來源是原版世界，所以實際上不會觸發；只是避免 map 合併時悄悄少檢查一個世界。

呼叫端與語意：

- `CorridorPageManager` 沒有修改：per-space 20→1200 tick 退避、WARN 去重與恢復時的 INFO 都照舊。
- 新的例外訊息都是固定字串，不含 chunk 位置，WARN 依 class＋message 去重仍有效：
  - 「相關 chunk 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint」
  - 「原生 storage flush 已回報失敗（沒有 chunk 位置，視為全部 chunks），拒絕承認 checkpoint」
  - 其餘訊息沿用 H2 之前的文字。舊訊息「原生 storage write／flush 已回報失敗，拒絕承認幾何清理」與「原生 chunk／entity 序列化或存檔失敗，保留 MEASURED leases」已移除，沒有測試或腳本比對它們。
- 仍維持 fail-closed 的情況：相關 chunk 失敗、`completeAll` 失敗、`server.save` 丟例外或回 false、barrier 的 join 丟例外。這些情況一律拒絕並重新標髒，呼叫端保留 leases 並退避。
- `NativeSaveFailures` 只在上述三個 mixin 與 `ChunkSaveCheckpoint` 使用。玩家 checkpoint 不使用它，語意不受影響。

界線（H2 未改變）：

- 無關 chunk 的寫入如果在原版 `completeAll` 執行時仍 pending，原版會 join 到失敗，讓外層 `server.save` 直接丟例外。涉及的呼叫是 `VersionedChunkStorage.completeAll()` 的 `join`，以及 `EntityChunkDataAccess.awaitAll` 的 `join`；`ServerWorld.save` 與 `MinecraftServer.save` 都沒有 catch。這時 checkpoint 仍會失敗。不同世界也一樣：前一個世界的 `save` 丟例外後，後面的世界不會存檔。
- H2 會忽略的，只有在 `completeAll` 取快照之前就已完成的無關失敗，例如較早受理的寫入、同步的序列化失敗，以及非 flush 存檔或卸載時的失敗。要完全消除上面那種情況，就得改用 `server.save` 以外的存檔路徑，這超出 plan 的指定範圍。
- `chunkFailures` 只在失敗時新增，key 數量上限是曾經失敗的不同 chunk 數，沒有修剪。

### 測試（`f7ac878`）

JUnit `NativeSaveFailuresTest`：由 4 個增加為 13 個。

- 原有 4 個改為新 API，相關 chunk 照舊判定：吞掉的失敗仍使收據失效、barrier 等待延後的失敗、已完成的失敗不會遺失、上一輪失敗後的新成功可被承認。
- H2 規則（3 個）：
  - `unrelatedChunkWriteFailureDoesNotBlockRelevantChunks`：同一筆失敗若納入相關範圍，仍必須拒絕。
  - `unrelatedReportedFailureDoesNotBlockRelevantChunks`。
  - `barrierWaitsForUnrelatedAsyncFailureWithoutBlocking`：observer 完成前，barrier 不得返回。
- fail-closed 對照（6 個）：
  - 相關 chunk 寫入失敗與同步回報失敗。
  - flush 失敗對任何 chunk 集合（含空集合）都拒絕。
  - barrier 等待延後的 flush 失敗。
  - snapshot 之前的失敗不計，同一 chunk 之後再失敗就計入。
  - 兩份 storage 的相同 chunk 座標互不影響。

GameTest `native_measured_checkpoint_ignores_unrelated_chunk_failure_windows`（batch `m5_h2_checkpoint_scope`，`M4CandidateDoorGameTests.java:418`）：

- 流程：
  1. 真側門 checked SELECTED，移除 Buff 讓玩家返還。
  2. 第一次 checkpoint：讓來源艙 controller 所在 chunk 的原生 region write（chunk＋entities）真的失敗。
  3. 第二次 checkpoint：改讓同一世界、同一份 storage 中已載入的無關 chunk 失敗。hook 自行把該 chunk 標髒，並撤銷它的 entity 空快取。
- 判定：
  - 第一次：故障確實觸發（chunk、entities 兩種）、未被承認、flushed receipt 與 leases 原樣保留、runtime space 仍在。
  - 退避間隔 20 tick 不變。
  - 第二次：故障確實觸發（chunk、entities），production 的 server 計數至少 +2、來源 chunk worker 計數至少 +1，checkpoint 被承認。
  - 只有兩次呼叫，之後完成 DORMANT，selection receipt 保留，玩家回到來源。
  - 收尾移除本案例自建的 DORMANT receipt。
- 故障注入 `ChunkCheckpointScopeFaultMixin`（`StorageIoWorker`）：
  - 以 `@WrapOperation` 在 `StorageIoWorker.write` 內、原生 try 範圍中的 `RegionBasedStorage.write` 前丟出 `IOException`。由 worker 自己 `completeExceptionally`，不寫入 region 檔。
  - 在 `setResult` RETURN 只對故障目標等真 IO 完成，讓失敗 entry 不會留到原版 `completeAll`，外層 `server.save` 照常回 true（見上方「界線」）。
  - 只比對 (dimension, chunk, type ∈ {chunk, entities})。
- 為什麼新增 mixin：M4 的 region write／drain mixin 由 `M4CandidateRecoveryProbe` 的靜態 fault 驅動，只用在 M4 recovery probe；派工規定不得改變它們的行為。
- `MeasuredCheckpointFaultMixin` 只多一個 RETURN hook，用來記錄 checkpoint 被承認的那一次；原 HEAD hook 與既有退避測試的行為不變。
- GameTest 只使用 HEAD 已有的 API（全伺服器計數、worker `revision()`），所以同一份測試碼可以在 HEAD 上跑 RED。

### Gate 與證據

gate root 都在 `.superpowers/verification/`；逐輪的 state、console、diff 與摘要在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/h2/`。

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

### 觀察（未修改）

- `.github/workflows/build.yml` 的 `$requiredM4GameTests` 明列清單沒有加入新 test（不在本 task 的檔案清單）。新 test 名稱以 `_windows` 結尾，由同一步驟的通則（宣告必須出現在 XML 且實際通過）涵蓋。是否補進明列清單由 controller 決定。
