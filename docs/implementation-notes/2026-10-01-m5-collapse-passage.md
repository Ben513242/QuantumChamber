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

## F2a：Schema 4、狀態機、唯一性與終局 API

- Commits（依序；每個 commit 只 stage 自己的檔案）：
  - `7c3c247` `refactor: extract the shared journal NBT helpers`（parent `cfe5133`）：新增 `persistence/JournalNbt.java`，改 `CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionRecoveryState.java`。共用的 strict NBT helper（`requireType`、`uuid`、`string`、`compound`、兩個 `compounds`、兩個 `keys`、`canonicalIdentifier`）移到 `JournalNbt`，`CandidateJournalCodec` 不再 `import static SessionRecoveryRecord.*`。訊息文字不變。`SessionRecoveryRecord` 保留 `requireType`／`uuid`／`string` 三個委派，因為 `PlayerRecoveryCheckpoint.java:18-21` 仍以這個名稱取用，而它不在本 task 的檔案清單。refactor 前後 `gradle test --rerun` 都是 447（2 skipped），同一組 class。
  - `b400b7f` `feat: add journal schema 4 with collapse states and a checked terminal`（parent `7c3c247`）：`superposition/SessionState.java`；新增 `collapse/CollapseDestination.java`、`collapse/SealedReason.java`、`persistence/CollapseFields.java`；改 `persistence/SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`CandidateJournalCodec.java`；測試新增 `SessionRecoverySchema4Test.java`，改 `SessionRecoveryStateTest.java` 一行（見「既有測試的處置」）。
  - 本段的 docs commit 接在 `b400b7f` 之後。
- Code range `cfe5133..b400b7f`。
- 沒有接任何 runtime 呼叫點：production 不會呼叫 `markCollapseAuthoritiesInitialized`，journal 照舊寫 schema 3（或 2），也沒有任何程式產生塌縮狀態，所以 production 行為不變。
- Review：首輪結果與 fix round 1（`2d13248`）見本節最後的「Fix round 1」。

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
- Review：尚未進行。

### Code-truth（`96a442c`）

行號以 `96a442c` 為準；`A` 指 `collapse/CollapseAuthorities.java`，`D` 指 `candidate/UniverseDiscoveryState.java`，`C` 指 `persistence/CheckedNbtFile.java`，`N` 指 `collapse/LedgerNbt.java`。

| 項目 | 位置 | 行為 |
| --- | --- | --- |
| discovery 的正式追加（§6.4） | `D.append` `:121-134`、`Append` `:38-51`、`find` `:105-108` | 只算出內容、不寫檔。沒有這個 UniverseId：新 record 為 ordinal＝`watermark()`＋1（`Math.addExact`；不可見的 record 也算）、`discoveredAtGameTime`＝傳入的 game time、`firstObserver`＝SelectedBy、`gameplayEligible`＝true，回 `APPENDED` 與多一筆的新 state（canonical 順序，新 record 一定在最後）。已有：`gameplayEligible` 而且 `firstObserver` 等於 SelectedBy 為 `EXISTING`，否則（不可見、別人、M4 fixture 沒有 observer）為 `MISMATCH`；兩者都回原本的 state，不改動。ordinal 與 time 不比對 |
| discovery 的 store 層 port（決策 9） | `D.save(Path, ReadPort)` `:91-98`（package-private）；`save(Path)` `:85` 以 `CheckedNbtFile::readFile` 委派 | 暫存檔驗證與 atomic move 之後的讀回都經 port；失敗丟 `UnhealthyDiscoveryException`，正式檔不還原 |
| discovery 的追加與收斂（§6.4、§6） | `A.appendDiscovery` `:500-522`、`readDiscovery` `:685-700`、`markLost` `:703-709`、discovery slot `:266-267` | 閘門（初始化、遺失、未確認）之後，每次從磁碟 strict 讀取（與 M4 讀取點相同，不快取）。`EXISTING`→`FOUND`（不寫）；`MISMATCH`→`CORRUPT`（不寫）；`APPENDED`→沿用 ledger 的 `checkedWrite`：寫入或讀回失敗為 roll-forward（current 保留這筆 append、標為未確認、以權威為單位退避、WARN 去重），回 `WRITE_FAILED`／`ALLOCATION_IO_FAILED`。讀取分類：`NoSuchFileException`→遺失（`CORRUPT`，本 instance 之後一律 `CORRUPT`、只記一次 ERROR、不重建）；讀 bytes 的 IO 例外→`UNREADABLE`（不黏著）；之後的解析或 strict decode 失敗→`CORRUPT`。game time 為負是 caller 錯誤，丟 `IllegalArgumentException` |
| discovery 的唯讀查詢 | `A.discovery` `:528-536` | 給 F4a 的 EXISTING 重驗（§8.1）：`FOUND`（該 record，`gameplayEligible` 由呼叫端判斷）／`ABSENT`；閘門與讀取分類同上 |
| catalog 配置或驗證（§6.2） | `A.allocateOrVerifyCatalog` `:555-597` | 依序：初始化閘門；catalog 不健康→`CORRUPT`；catalog 未確認（`:568`）→`UNCONFIRMED`（依 §6.2 不進 SEALED，見「偏離」）；flushed 已有該 UniverseId→`catalogRecordMatches`（`:575`），符合 `FOUND`、否則 `CORRUPT`，都不寫出；沒有→orphan 檢查（`:578-587`），再 `allocateOverworld(uuid, flushed 最大 ordinal＋1)`（空時 0）與 `flushCatalog`（`:595`）：成功 `APPENDED`，失敗 `WRITE_FAILED`／`ALLOCATION_IO_FAILED` |
| 既有 catalog record 的判定 | `A.catalogRecordMatches` `:606-615`（public static） | definition 必須 exact 等於 M3 以同一個 UUID 與該 record 的 ordinal 呼叫 `new UniverseRegistry().allocateOverworld` 得到的 definition（OVERWORLD descriptor、world key、`VANILLA_OVERWORLD_SHARED_SEED_V1`、`SHARED_SAVE_SEED_V1`、storage policy 1）；availability 為 `EAGER_ENABLED`，`DISABLED` 只在 `acceptDisabled` 為 true（§8.3 的重試路徑，F4a 決定）時接受。不比對 ordinal |
| orphan storage | `A.universeStorage` `:771-774`、`allocateOrVerifyCatalog` `:578-587` | storage 位置與 M3 `inspectStorage` 同一推導：`<save>/dimensions/<namespace>/<path>` 的上一層（`dimensions/quantumchamber/universe/<uuid>`）。catalog 沒有這筆、目錄（任何型別，不跟隨連結）卻已存在→`CORRUPT`，不採用、不刪除；`readAttributes` 的其他 IO 例外→`UNREADABLE`／`AUTHORITY_UNREADABLE`。pure JVM 可測，不需要 runtime 世界路徑 |
| catalog 的未確認與收斂 | `A.catalogUnconfirmed` `:712-718`、`unconfirmed` `:621-625`、`convergeIfNeeded` `:632-644`、`convergeCatalog` `:720-733`、`flushCatalog` `:739-760`、`catalogConfirmed` `:763-768` | 不另存旗標，由 catalog 推導：`isDirty()`，或 current 不等於 flushed（catalog 不健康時為真）。所以原生 autosave 寫出成功就自動回到已確認；restore 之後 current 已等於 flushed、但仍 dirty，也算未確認。`flushCatalog` 經 port 寫出；失敗時 M3 的 save 已保留 current 與 dirty（`UniverseRegistryState.java:228-234`），這裡只記退避（`catalogBackoff`，以權威為單位）、WARN（同一原因一次）與「曾經失敗」；確認時清除退避，曾失敗過就記 INFO「CATALOG 收斂完成」。沒有新增還原 API |
| catalog 的 port（決策 9） | `A.CatalogPort` `:82-88`、`ServerCatalog` `:90-93`、package-private 建構子 `:252` | production：`UniverseRegistryState.get(server)` 與 `flush(server)`。測試以它提供真的 M3 `UniverseRegistryState` 並注入寫出失敗 |
| catalog 的 store 層 port（決策 9） | `UniverseCatalogStore.Readback` `:27-29`、`write(Path, NbtCompound, Readback)` `:35-60`（package-private）；`UniverseRegistryState` 欄位與建構子 `:43-52`、`save` `:228` | 只把 atomic move 之後的 `read(absolute)` 換成 port；public `write` 與 public 無參數建構子都用 `UniverseCatalogStore::read`，production 行為不變 |
| 閘門 | `A.gate` `:649-658`、`initializationGate` `:661-670` | 四個權威共用初始化閘門（fail closed）：未初始化是 caller 錯誤，初始化失敗回初始化的失敗結果；之後檔案權威再看遺失與未確認。`convergeIfNeeded` 在初始化失敗時一律不做事 |
| 共用 checked 檔案 helper | `C.write` `:58-75`、`verify` `:77-85`、`readFile` `:37-41`、`parse` `:44-47`、`ReadPort` `:34` | 第 6 點，見下一小節 |
| 共用 strict NBT helper | `N` `:16-47` | 第 6 點，見下一小節 |

### 第 6 點：共用 helper 的 refactor（`c0167e0`）

- **`persistence/CheckedNbtFile`**：把 `CollapseAuthorities.writeAtomic`／`verify`／`parse`／`readFile` 與 `UniverseDiscoveryState.save`／`verifyReadback`／`readNbt` 兩份幾乎相同的寫法合成一份，帶 `ReadPort`（讀原始 bytes；檔案不存在丟 `NoSuchFileException`）。`CollapseAuthorities.LedgerIo` 改為 `extends CheckedNbtFile.ReadPort`，測試的 port 不用改型別。catalog（`UniverseCatalogStore`）與 journal（`SessionJournalStore`）的 `{data, DataVersion}` wrapper 不併入。
- **strict NBT helper 選 `collapse/LedgerNbt.java`（從 `CollapseAuthorities` 搬出、public），不選 `JournalNbt` 改 public**。理由：
  1. `JournalNbt` 的規則綁 journal envelope：`compounds` 的空 list 規則依 envelope schema 而不同，`keys` 的訊息寫「schema 3 payload」，另有 journal 專用的 `canonicalIdentifier`。本 task 對它只能改可見性或搬移，不能加筆數上限、enum 或與 schema 無關的 list 檢查；共用的話，ledger 與 discovery 要傳一個假的 envelope schema 3，錯誤訊息也會指向 journal。
  2. `LedgerNbt` 原封搬出，ledger 的錯誤訊息逐字不變；只把原本寫死的 `Entries` 改成 key 參數，並拆出沒有上限的 `compounds(nbt, key)` 給 discovery 的 `Records`。兩個 ledger 不再經過 `CollapseAuthorities` 解碼（解開 Q5 指出的互相依賴）。
  3. 代價：`JournalNbt` 仍有自己的 `requireType`／`uuid`（journal 專用，兩三行）；`candidate` 多了一條對 `collapse` 的 import（`collapse` 原本就依賴 `candidate`），性質與既有的 `candidate`↔`persistence`、`persistence`↔`collapse` 相同。
- **行為不變的界線**：
  - 接受與拒絕的集合完全相同。discovery 改用 `LedgerNbt` 後，型別、UUID 長度、list held type 的錯誤訊息不同，但 `load`／`loadOrCreate`／`save` 都把例外包成不帶 cause 的 `UnhealthyDiscoveryException`，對外看不到。
  - discovery 讀檔改為「先讀原始 bytes（最多 16 MiB＋1）再解析」，比原本直接 `NbtIo.readCompressed(path, 16 MiB 預算)` 多一個壓縮檔大小上限。能通過 strict decode 的 discovery 內容，解壓後的 NBT 計量一定先超過 16 MiB 預算，所以這個上限不會比原本更早拒絕；兩者都會拒絕的內容，對外一樣是 `UnhealthyDiscoveryException`。
  - ledger 的 `readFile`／`parse` 訊息由「M5 ledger／ledger 檔案」改成「checked NBT 檔案」，exact readback 不符的訊息不變（label 仍是權威名稱）。
- **JUnit 前後相同**：refactor 前的基準是 F2b fix round 1 的 Full gate（`gate-f2b-fix1-green-full-7b226c11…`；`84acd2b..b712193` 沒有 `src/`、build 檔、`.github/` 或 `scripts/` 的差異）。refactor 之後在工作樹跑全部 JUnit：518，0 failure／error，2 skipped，**逐 class 數字與基準完全相同**（`f2c/refactor-baseline-junit-perclass.txt`、`refactor-junit-perclass.txt`）。之後沒有再改檔就 commit。

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
| refactor JUnit | `b712193`；porcelain 為 5 個 ` M` 與 2 個 `??`（`f2c/refactor-state.txt`），內容等於之後的 `c0167e0` | `f2c/refactor-junit-all.log` | 518，0 failure／error，2 skipped；逐 class 與 F2b fix round 1 的 Full gate 相同（見「第 6 點」） |
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
- **catalog 的 `UNCONFIRMED` 帶 `ALLOCATION_IO_FAILED`**：`Lookup` 的不變式要求失敗的結果帶 SEALED 原因，但 §6.2 規定 catalog 不相等（dirty）時等下一個 tick、不進 SEALED。Javadoc（`Lookup` 與 `allocateOrVerifyCatalog`）寫明 catalog 的 `UNCONFIRMED` 是例外，F4a 應先查 `unconfirmed(CATALOG)`；見「需要裁示」。
- **catalog 的未確認不另存旗標**：由 catalog 推導（`isDirty()` 或 current 不等於 flushed）。controller 補充第 4 點寫「`CollapseAuthorities` 只追蹤『未確認』」；推導的好處是原生 autosave 寫出之後自動確認，F4a 在 §8.3 直接用 M3 API 改 availability 時也自動涵蓋。本類別只保留退避、WARN 去重與「曾失敗」（INFO）這些簿記。
- **profile 不符無法構成既有 record**：M3 的 `UniverseDefinition` 建構子只接受恰一個 OVERWORLD descriptor、`VANILLA_OVERWORLD_SHARED_SEED_V1`、`SHARED_SAVE_SEED_V1`、storage policy 1（`UniverseDefinition.java:16-45`），`GeneratorProfile`／`SeedPolicy` 也只有這一個值。所以「profile 不符」的 RED 以 catalog NBT 驗證：M3 strict decode 讓 catalog 不健康，`allocateOrVerifyCatalog` 回 `CORRUPT`、不配置；`catalogRecordMatches` 仍以 M3 的 `allocateOverworld` 產生的 definition exact 比對，防守日後放寬。availability 不符則直接以 `DISABLED` 驗證。
- **orphan 判定在本 task 完成**：storage 位置可以從存檔根目錄與 `UniverseKeys` 推導（與 M3 `inspectStorage` 相同），pure JVM 可測，所以直接放在 `allocateOrVerifyCatalog` 裡，F4a 不需要另外接線。
- **「current 恰等於 flushed 才配置」**：controller 補充第 2 點把等待流程交給 F4a；`allocateOrVerifyCatalog` 本身也先檢查，未確認時回 `UNCONFIRMED`、不配置（防守 F4a 漏查）。600 ticks 的 WARN 屬 F4a。
- **本輪不跑 Legacy、Main＋oracle**：controller 指定 Full、M3 與 M4 recovery。沒有新增 `SERVER_STARTED` listener、mixin 或 client 程式；`onStarted` 只多建一個不讀任何東西的 `ServerCatalog`。

### 需要裁示

- **catalog 的 `UNCONFIRMED` 要不要改成不帶 SEALED 原因的獨立狀態**（例如 `WAIT`）：目前沿用 `Lookup` 的 `UNCONFIRMED` 並帶 `ALLOCATION_IO_FAILED`，靠 Javadoc 要求 F4a 不要因此 SEALED。改成獨立狀態需要放寬 `Lookup` 的不變式，F4a 才不必特別處理；兩種都不影響本 task 的測試意圖。
- **strict NBT helper 的選擇**（第 6 點）：選了 `collapse/LedgerNbt.java`，`JournalNbt` 維持 journal 專用。若 controller 偏好 `JournalNbt` 改 public 共用，需要同意 ledger 與 discovery 傳入 envelope schema 3、錯誤訊息改為 journal 的措辭。

### 觀察（未修改）

- `UniverseDiscoveryState.UnhealthyDiscoveryException` 不帶 cause，M4 的 log 看不到 discovery 讀寫失敗的根因。M5 的路徑不經過它（`CollapseAuthorities` 自己分類並保留 cause），所以本 task 不改。
- `UniverseDiscoveryStateTest` 的 `target()` 不是 normalize 過的路徑；store 層的注入以 `Path.equals` 比對 `CheckedNbtFile` 傳入的 normalize 路徑，在 `@TempDir`（已是絕對路徑）下相等。

### 交給後續

- F4a（RESOLVE 的接線；F4a 的檔案清單沒有 `CollapseAuthorities.java`，以下 API 都已在本 task 提供）：
  - NEW 第 2 步（§6.2）：`unconfirmed(CATALOG)` 為真時先 `convergeIfNeeded(CATALOG)`；仍未確認就等下一個 tick、不進 SEALED，持續 600 ticks 記一次 WARN。否則 `allocateOrVerifyCatalog(id, false)`（§8.3 的 `MATERIALIZE_FAILED` 重試才傳 true）：`APPENDED`／`FOUND` 繼續；`WRITE_FAILED`→SEALED(`ALLOCATION_IO_FAILED`)；`CORRUPT`→`AUTHORITY_CORRUPT`（含 orphan、不符、catalog 不健康）；`UNREADABLE`→`AUTHORITY_UNREADABLE`；`UNCONFIRMED`（查完之後才變 dirty 的競態）照等待處理。物化只用 flushed catalog 的 record，而且 `unconfirmed(CATALOG)` 為假。
  - NEW 第 4 步（§6.4）：物化成功之後 `convergeIfNeeded(DISCOVERY)`，再 `appendDiscovery(id, 當下 game time, SelectedBy)`：`APPENDED`／`FOUND` 完成；`WRITE_FAILED`／`UNCONFIRMED`→SEALED(`ALLOCATION_IO_FAILED`)，重試前先收斂；`CORRUPT`／`UNREADABLE`→對應的 AUTHORITY 原因。game time 用哪一個世界的時間由 F4a 決定（必須非負）。
  - EXISTING（§8.1）：`discovery(id)` 為 `FOUND` 且 `gameplayEligible`；flushed catalog 的 record 以 `catalogRecordMatches(record, id, false)` 判定；不符→`EXISTING_INELIGIBLE`；discovery 的 `CORRUPT`／`UNREADABLE`→對應的 AUTHORITY 原因（§12）。
  - §8.3：改為 `DISABLED`、重試時改回 `EAGER_ENABLED`、失敗時改回 `DISABLED`（restore），都直接用 M3 的 `changeAvailability` 與 `flush(server)`；`unconfirmed(CATALOG)` 會自動反映 dirty（含 restore 之後 current 等於 flushed 但仍 dirty），之後由 `convergeIfNeeded(CATALOG)` 或原生 autosave 寫出。這些寫出不經 `CollapseAuthorities`，失敗不會累積它的退避，第一次 `convergeIfNeeded` 會立即嘗試。
- F6：W12 的跨 JVM 注入（catalog 配置與 discovery 追加的「atomic move 完成、讀回失敗」）需要 testmod mixin，可以掛在 `UniverseCatalogStore.write` 的讀回與 `CheckedNbtFile` 的讀檔（決策 9 的 GameTest 層注入）。
- F7：M5 build 之後 discovery 與兩個 ledger 共用 `CheckedNbtFile` 的讀檔上限（檔案 16 MiB、解壓後 NBT 16 MiB）。
