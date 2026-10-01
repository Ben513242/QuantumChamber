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
  - fix round 1 的 re-review 尚未進行。

### Code-truth（`8a68630`）

| 規則 | 位置 | 失敗路徑 |
| --- | --- | --- |
| entropy root 恰為 `{SchemaVersion, CandidateEntropy}` | `CandidateEntropyState.read`（`:70-72`），在既有的型別與長度檢查之前 | `UnhealthyEntropyException`（固定訊息、不帶 cause）；不換發 secret、不改檔案 |
| `ledger.size() ≤ maxCandidateEntries` | `SessionRecoveryRecord.fromNbt` 的 schema 3 分支（`:271-274`）：先解出 `CandidateContext`，再拿 `CandidateLedger` 的 list 長度與 `maxCandidateEntries()`（M4 policy 固定為 16384）比對，比對通過才逐筆解碼 | `IllegalArgumentException` → `SessionRecoveryState.fromNbt` 的 failed state |
| schema 3 的 `Origin`、`Participants`、`SpaceLeases` exact key | `fromNbt` 的 `:232`、`:244-245`、`:255`，呼叫帶位置標籤的 `CandidateJournalCodec.keys(where, …)`（`:146-152`），只在 `strict`（`:221`）時執行 | 同上 |
| vanilla WorldKey 帶 namespace 且為 canonical | `CandidateJournalCodec.canonicalIdentifier(where, nbt, key)`（`:157-162`）：`Identifier.tryParse(raw)` 不為 null，且 `toString()` 等於原字串。用在 `SourceFamilyRef` 的 VANILLA（`:47`，標籤 `SourceFamily`）與 schema 3 的 Origin（`SessionRecoveryRecord.java:236`，標籤 `Origin`） | 同上 |

- `strict`：在 `fromNbt` 的版本白名單之後立即定義為 `envelopeSchema >= 3`（`:221`），函式內原本的 `envelopeSchema == 3`／`< 3` 判斷都改用它；`compounds(nbt, key, envelopeSchema)` 的空 list 規則改為 `>= 3`（`:312`）。白名單仍只接受 1–3，所以目前行為不變；之後的 schema 4 預設走嚴格路徑。`SessionRecoveryState.java:81` 的白名單沒有修改（不在本 task 的檔案清單，留給 F2a）。
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
