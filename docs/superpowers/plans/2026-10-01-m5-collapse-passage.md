# M5 塌縮與單向通道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. 每個 task 都必須完成 RED → GREEN → focused tests → 完整回歸 → spec review → code-quality review → commit，Critical／Important 為 0 才能開始下一個 task。

**Goal:** 把 M4 留下的 checked `MEASURED + SELECTED` receipt 接成玩家看得到的結果：塌縮、回到原艙、確定目的地、量子膜、單向通道，以及 DORMANT 續行與 SEALED 的 fail-closed 收斂。

**Architecture:** 新的 `collapse` package 以 `CollapseService` 讀 `flushedRecords()`（與 spec §6 的 collapse view），在既有的 session operation guard 內推進 journal schema 4 的三個新狀態（COLLAPSING、PASSAGE_READY、SEALED）。目的地由 RESOLVE 在 PREPARE 之前確定：EXISTING 重驗，NEW 經 allocation ledger → catalog → `UniverseLifecycleService` 物化 → discovery。PREPARE 只做預載、投影、量子膜與 READY；跨越以 passage 專用的 transfer safety 傳送玩家。所有新權威沿用 checked 寫入，並遵守 spec §6 的初始化、既有紀錄驗證與 roll-forward／restore 收斂。

**Tech Stack:** Java 21、Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、NBT checked persistence、JUnit 5、Fabric GameTest、PowerShell cross-JVM probes。

**Spec（binding）:** `docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md`（v5.2，`6aadb03`；之後的修訂以最新版為準）。本 plan 與 spec 衝突時以 spec 為準，並回報 controller。

**SDD ledger:** `.superpowers/sdd/2026-10-01-m5-collapse-passage/progress.md`（gitignored，不得 force-add）。

## Global Constraints

- 所有工作都在 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m5-collapse`（branch `feature/m5-collapse`）。不在主 checkout 或 `main` 上實作。
- 每個 task 只能修改該 task「Files」列出的路徑，另外可以修改 implementation note `docs/implementation-notes/2026-10-01-m5-collapse-passage.md` 中屬於該 task 的段落（code-truth、RED／GREEN root、commit range）。列表外的修改一律退回；需要新增路徑時，先回報 controller。不順手修已排入其他 task 或 milestone 的項目。
- 不使用 `git add -A`；逐檔 stage。不 rebase、不 force push、不建立 PR。
- 所有 journal、catalog、ledger、discovery、門與玩家的變更只在 server thread 發生。gameplay 判斷只信 `SessionRecoveryState.flushedRecords()` 與各權威已確認的內容；唯一例外是 spec §6 的 collapse view（roll-forward 尚未確認的 SEALED 與 `Crossed`）。
- M5 不重抽候選，不覆寫或刪除 M4 receipt 的 context、ledger、selection。M4 的 `remove` 對 MEASURED 與三個 M5 狀態一律拒絕；只有 spec §5 的終局 API 可以移除。
- 不做 Nether／End family、`DimensionFamilyRoutingService`、`RespawnRoutingService`、see-through portal、complex renderer 或自訂 client packet。
- 不改 `ChamberRegistryState`（仍是 schema v2、只收 ORIGIN），投影不進 chamber registry 或既有的 `ProjectionIndex`。
- Testmod 只存在於 `src/testmod`（含其下的子 source set）與測試 runtime；release／sources JAR 不得包含 testmod、gametest、fault mixin 或 probe classes（`dev/quantumchamber/compat/ModPresenceProbe` 是合法的 production class）。
- RED 證據一律同時記錄 `git rev-parse HEAD` 與 `git status --porcelain`。RED 若是新的 `@GameTest`，GameTest 數字的變化要在報告中逐項說明。
- 任何 runtime／probe 失敗都保留原 root 與 receipt；修正後以新的 nonce／root 重跑，不在失敗 root 上改判。
- F1b 失敗就停止 M5 的玩法實作，回報使用者。
- 新的 M5 訊息一律用 `Text.translatable`，key 放在 `message.quantumchamber.m5.*`，`zh_tw.json` 與 `en_us.json` 同時加入；既有的 `Text.literal` 不改。
- M5 新增的正常結束（SOURCE fast path、通道結束 (a)–(d)、§11.3 的取消）一律記 INFO，不記 WARN／ERROR（spec §17 N-4 的部分納入）；每個 task review 必查。

## 決策（spec 交給 plan 決定的事）

1. **tick 順序：** `CollapseService.initialize()` 在 `QuantumSuperpositionMod.onInitialize` 中註冊在 `SuperpositionSessionManager.initialize()` 之後、`UniverseLifecycleService.initialize()` 之前。Fabric 事件依註冊順序執行，所以每個 `END_SERVER_TICK` 中，`CollapseService` 排在 session 與 recovery 的 tick 之後。`CollapseService` 對 `CorridorPageManager.operationInProgress(session)` 為真的 session 一律跳過。
2. **初始化時點：** `CollapseAuthorities` 的初始化與 `ProjectionProtectionIndex` 的建立，註冊在 `SERVER_STARTED`，而且註冊順序早於 `UniverseLifecycleService.initialize()`，確保在 bootstrap 物化之前執行（spec §6、§9.3）。
3. **投影的方塊寫入：** 比照原版 `/setblock`，寫入前對既有 block entity 呼叫 `Clearable.clear`，flags 用 `Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS`，全部經 `ChamberProtectionService.authorizedMutation`。F4b 以 GameTest 證明 volume 內的箱子不會噴出物品、鄰格不觸發物理更新。
4. **passage 傳送政策：** 不複製 M3 的移動與回滾邏輯。`UniverseTransferService` 新增 `moveToUniverse(player, descriptor, TransferSafety safety)` 多載；既有的 `moveToUniverse(player, descriptor)` 以 `TransferSafety.M3_SAFE_LANDING` 委派，行為不變，M3 的測試檔不得修改。`PassageTransferPolicy` 是 `TransferSafety` 的 passage 實作：只檢查有限值、世界高度與邊界、bbox 外擴 2 格的 chunks 為 FULL，不做碰撞、流體與地板檢查；前置檢查、驗證與回滾三處都用同一個 safety（spec §10.4）。
5. **membrane 不變量：** `QuantumBulkheadBlock` 提供 `withOpen(BlockState, boolean)`，關門時同時把 `membrane` 設為 `none`。所有切換 `OPEN` 的寫入點（`WorldChamberDoorAdapter.java:21-22`、`CorridorPageManager.java:476-477`）都改用它。
6. **M5 GameTest 隔離：** 會配置或物化 Universe、或留下 M5 record 的 GameTest，一律放在新的 `gameTestM5` run（獨立的 fresh 世界），不放在共用的 default run。原因：default run 的 `M4GameTestBoundaryProbe` 要求整個 suite 前後 catalog 與 world keys 不變（`scripts/verification/gates.ps1:199-201`），M3 的 `empty_bootstrap_and_disabled_record_do_not_create_worlds` 也要求 catalog 為空。只用 SOURCE、不留殘留的 M5 GameTest 可以留在 default run。
7. **collapse-suspended hook：** 語意是「暫停」（spec v5.1 §13.1）：被 hook 的 session，`CollapseService` 完全不嘗試提交，也不觸發 fallback。真正的「提交失敗 → M4 fallback」另由 M5 GameTest 覆蓋。不變量：
   - hook 的靜態集合在同一個 JVM 內不得清除。
   - default run 中每一個會產生 MEASURED 的 test 都必須掛 hook（F4a 之後，沒掛 hook 的 test 會在 default run 配置宇宙，Review Focus 8 會抓到）。
8. **與使用者指定順序的差異：** 使用者的順序把 discovery／terminal 排在 passage 之後。本 plan 把終局 API 放在 F2a、discovery append 放在 F2c，因為 SOURCE fast path（F3d）需要終局 API，而 spec §3 詮釋 2 把 discovery 放進 RESOLVE（F4a）。其餘順序相同。
9. **pure JVM 的 fault 注入：** 權威的 IO 失敗（ledger、discovery、catalog 的「atomic move 完成、讀回失敗」）在 pure JVM 測試以 package-private 的 IO port 注入，比照 `SessionRecoveryState.JournalReadback`（`SessionRecoveryState.java:26-29`）。port 的可見性決定測試位置：store 層的注入測試放在 store 所在的 package（catalog 在 `universe`，discovery 在 `candidate`）；`CollapseAuthorities` 在 `collapse` package 提供自己的 package-private port。GameTest 層的注入（物化失敗、投影寫入失敗、native move，以及 GameTest 中需要的 ledger IO 失敗）用 testmod mixin。
10. **門的目標狀態：** F3b 在 `ChamberDoorService` 新增公開的「把 25 格門套用成指定狀態（open＋membrane）」API，沿用既有的 rollback 與 `activationBlocked` 處理；之後 F3d、F4b、F5 都經由它改門，不在 `CollapseService` 重寫一份。
11. **過渡期的 presence：** `Presence.COLLAPSE`／`PASSAGE`、`presence()` 依 collapse view 推導，以及 `reconcile` 對 M5 presence 的分支（不走 pending、不呼叫 `returnToOrigin`、power state 只設 POWERED 或 RETURNING、比較器 11）都在 F3a 完成；`ChamberState.PASSAGE`→15 留到 F5。所以 F3d 之後產生的 M5 record 一開始就有正確的 presence。

## 指令

Gradle（AGENTS.md 指定）：

```powershell
$gradle = 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat'
& $gradle test --tests '<class>'          # focused JUnit
& $gradle test                            # 全部 JUnit
```

GameTest 與跨 JVM gate 一律經 `scripts/verification/`（會 move／restore `run/` 內的世界，不要直接跑 `runGameTest`）：

```powershell
$v = 'scripts/verification'
& "$v/gates.ps1" -Mode Full -Label <task>-full      # clean test runGameTest build --rerun-tasks
& "$v/gates.ps1" -Mode Legacy -Label <task>-legacy
& "$v/gates.ps1" -Mode Main -Label <task>-main; & "$v/main-oracle.ps1" -Gate <GATE_ROOT>
& "$v/gates.ps1" -Mode Windows -Label <task>-win    # 3 個 Windows checkpoint JUnit class
& "$v/gates.ps1" -Mode M3 -Label <task>-m3
& "$v/gates.ps1" -Mode M5GameTest -Label <task>-m5gt  # F3c 之後才存在
& "$v/m4-recovery-probe.ps1"                       # 24 phases，約 6.5 分鐘
pwsh -NoProfile -File scripts/verification/run-all.ps1   # 全部 gates，約 15 分鐘
```

同一時間只能有一個 Gradle／gate 在跑（`run/` 與 build 目錄共用）。

**每個 task 的完整回歸**（task 另有規定時取聯集）：
- 程式 task：`-Mode Full`，0 failure／error；F3c 之後再加 `-Mode M5GameTest`。JUnit 與 GameTest 數字和上一個 task 相比只增不減，差異能逐項對應到本 task 新增的測試。
- 動到 journal codec、schema、返還路徑或 legacy 路徑：加 `-Mode Legacy`。
- 動到 recovery、MEASURED、COLLAPSING、SEALED、PASSAGE_READY 或 testmod probe：加 `m4-recovery-probe.ps1`（F6 之後再加 `m5-collapse-probe.ps1`）。
- 新增 `SERVER_STARTED` listener、mixin、client 程式碼或資源：加 `-Mode Main`＋`main-oracle.ps1`。
- 動到 catalog、lifecycle 或 transfer：加 `-Mode M3`。
- 動到 `scripts/verification/`、`build.gradle` 的 run 設定或 `.github/`：跑 `run-all.ps1`。

## Review Focus

以下是 happy path 容易漏掉、而且 spec review 抓過的高風險點。每項都綁定到擁有它的 task 測試，review 不得只靠文件宣告：

1. **回滾失敗後刪掉唯一的 record**（spec §10.3）：F5 以 testmod native move fault 注入 `FAILED_RECOVERY_REQUIRED`，驗證 SEALED(`TRANSFER_RECOVERY_REQUIRED`)、`RecoveryRequired`、`Crossed` 保留、沒有終局；再注入 SEALED 的落盤失敗，驗證 roll-forward。
2. **ledger 遺失的誤判與漏判**（spec §6）：F2b 驗證 schema 3 存檔（discovery 非空）第一次 NEW 不被判為遺失；schema 4 存檔刪除任一 ledger 一律 `AUTHORITY_CORRUPT`、不重建；F4b 驗證 COMPLETE 的 interior 不被重寫。
3. **NEW 收據被消耗、宇宙卻不存在**（spec §7.3、§8）：F4b 驗證 PREPARE 一開始就關門時，catalog 已恰好多 1 筆、discovery 已有紀錄。
4. **已落地但未確認的寫入**（spec §6）：F2b／F2c 注入「atomic move 完成、讀回失敗」，驗證 roll-forward 不移除已落地的 append、restore 類還原並保持 dirty、未確認期間不做新決策。
5. **比較器到不了 15、通道被 refresh 關掉**（spec §10.7）：F5 以沒有 runtime 的 DORMANT 續行到達 15，並撐過強制 refresh 與 neighbor update。
6. **把方塊寫在玩家身上、把外人關在艙內**（spec §9.2、§7.3）：F4b 驗證。
7. **Buff 到期時 MEASURED-only 分支丟例外**（spec §13.2）：F3a 逐一處理呼叫點。
8. **共用 GameTest server 的 Universe 汙染**（決策 6）：F3c 之後，default run 的 boundary oracle 必須維持 PASS。

---

## File Map

### 新增（production）

- `src/main/java/dev/quantumchamber/persistence/ChunkSaveCheckpoint.java`（H2）
- `src/main/java/dev/quantumchamber/persistence/CollapseFields.java`（F2a）
- `src/main/java/dev/quantumchamber/collapse/CollapseDestination.java`、`SealedReason.java`（F2a）
- `src/main/java/dev/quantumchamber/collapse/UniverseAllocationLedger.java`、`ProjectionLedger.java`、`CollapseAuthorities.java`、`CollapseBackoff.java`（F2b）
- `src/main/java/dev/quantumchamber/chamber/MembraneState.java`（F3b）
- `src/main/java/dev/quantumchamber/collapse/CollapsePhase.java`、`CollapseService.java`（F3d）
- `src/main/java/dev/quantumchamber/collapse/DestinationResolver.java`（F4a）
- `src/main/java/dev/quantumchamber/collapse/ProjectionWriter.java`、`ProjectionProtectionIndex.java`（F4b）
- `src/main/java/dev/quantumchamber/universe/TransferSafety.java`、`src/main/java/dev/quantumchamber/collapse/PassageTransferPolicy.java`、`PassageService.java`（F5）
- `src/main/resources/assets/quantumchamber/textures/block/quantum_membrane.png` 與 membrane 的 model（F3b）

### 新增（testmod／harness）

- `src/testmod/clientprobe/`：F1a 的獨立 source set（`environment:"*"`、沒有 mixin）。
- `src/testmod/java/dev/quantumchamber/gametest/M5CollapseGameTests.java`（default run，只放 SOURCE、無殘留）、`M5UniverseGameTests.java`（`gameTestM5` run）。
- `src/testmod/java/dev/quantumchamber/gametest/M5CollapseRecoveryProbe.java`（F6）。
- `src/testmod/java/dev/quantumchamber/gametest/mixin/` 下的 M5 fault 與 hook mixin（各 task 列出檔名）。
- `scripts/verification/m5-collapse-probe.ps1`（F6）。
- `docs/implementation-notes/2026-10-01-m5-collapse-passage.md`（第一個需要它的 task 建立）。

---

### Task T0: Clean-clone 基線（已完成，2026-10-01）

**不改任何 tracked 檔案。** 由 controller 執行，結果寫入 SDD ledger。

- [x] 開始時沒有 Java 程序，worktree 沒有 `run/`，也沒有 testmod 固定的 `.superpowers/sdd/2026-09-21-m4-candidate-doors/`（clean clone 狀態）。
- [x] `run-all.ps1`：HEAD `2c24d51`，root `.superpowers/verification/run-all-fc443127944f40638c4ffcf6771b8f6c`，`END ALL exit=0`；SUMMARY 與 `scripts/verification/README.md` 的預期表逐項相同；testmod 固定目錄由 harness 自動建立。
- [ ] 31 項 runtime noise 分類（subagent 只讀分類，controller 寫入 ledger）。若發現真正缺陷，回報使用者再決定 owner，不在 M5 task 裡順手修。

---

### Task T1: GameTest 順序依賴

**Files:**
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`

**背景：** `Batches.createBatches` 以 HashMap 分組不排序，batch key 超過 96 會 resize，改變執行順序。`unhealthy_authorities_reject_before_reservation_windows` 經 `assertEmptyAuthorities`（`:975`）要求整份 journal 與 pages 全空。後續 task 會新增大量 GameTest，必須先修。

- [ ] **Step 1: RED。** 在本 test 內，先以 `M4CandidateTestAccess.syntheticSession` 建立一筆屬於**另一座** chamber、可以移除的 ARMING record，執行 `assertEmptyAuthorities` 的檢查，證明現行檢查會因他艙殘留而失敗；在 `finally` 中移除這筆 record（不要用 DORMANT：`remove` 拒絕 MEASURED，會變成永久殘留）。記錄 HEAD 與 status。這個 RED 是改寫既有 test 的暫時版本，GREEN 時保留為正式斷言，GameTest 總數不變。
- [ ] **Step 2: GREEN。** `assertEmptyAuthorities` 改為只檢查本 test 自己的 chamber／session（journal、pages、tickets）；本 test 自己的殘留仍必須失敗。
- [ ] **Step 3:** 清查同檔其他依賴「全域為空」的斷言，回報清單；本 task 只改 `M4CandidateDoorGameTests.java`。其他檔若也有，回報 controller。
- [ ] **Step 4:** 完整回歸 `-Mode Full`；GameTest 數字不變（146，`_windows` 73）。
- [ ] **Step 5: Commit** `test: scope M4 empty-authority checks to the test's own chamber`

---

### Task H1: Codec strictness

**Files:**
- Modify: `src/main/java/dev/quantumchamber/candidate/CandidateEntropyState.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/CandidateJournalCodec.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Modify: `src/main/java/dev/quantumchamber/candidate/SourceFamilyRef.java`（只有 WorldKey 解析在此時）
- Test: `src/test/java/dev/quantumchamber/candidate/CandidateEntropyStateTest.java`、新增 `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStrictnessTest.java`

**範圍（spec §4 H1；M4 round 1 quality M4）：**
- entropy root 必須恰為 `{SchemaVersion, CandidateEntropy}`（`CandidateEntropyState.java:69-71` 目前只檢查存在）。
- decode 時強制 `ledger.size() ≤ context.maxCandidateEntries()`。
- schema 3 內層的 `Origin`、`Participants`、`SpaceLeases` 改為 exact key 與型別檢查（目前沿用 legacy 寬鬆解析）。schema 1／2 的 legacy 解析不變。
- Vanilla `WorldKey` 必須帶 namespace，並為 canonical 形式（`Identifier.toString()` 讀回等於原字串）。
- 不改 schema 版號；既有的合法 fixture 必須照舊 exact 讀回。

- [ ] **Step 1: RED fixtures。** 每一條規則至少一個 rejection fixture：entropy 多一個 key；ledger 超過上限；`Origin`／`Participants`／`SpaceLeases` 多 key、缺 key、錯型別；`WorldKey="overworld"`（無 namespace）與非 canonical 形式。另加「既有合法 schema 3 fixture exact round-trip」與「schema 1／2 寬鬆解析不變」作為不得退步的對照。
- [ ] **Step 2:** 執行 RED：`& $gradle test --tests 'dev.quantumchamber.candidate.CandidateEntropyStateTest' --tests 'dev.quantumchamber.persistence.*'`，rejection fixtures 失敗（目前被接受）。
- [ ] **Step 3: GREEN。** 收緊 decode。錯誤以既有的 strict 失敗路徑回報（entropy 為 `UnhealthyEntropyException`；journal 為 `fromNbt` 的 failed state），不吞例外、不補預設值。
- [ ] **Step 4:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`m4-recovery-probe.ps1`。
- [ ] **Step 5: Commit** `fix: tighten entropy and schema 3 journal decoding`

Review 必確認：legacy schema 1／2 的解析沒有被收緊；沒有新增或改名任何 NBT key。

---

### Task H2: 只看相關 chunks 的原生存檔 checkpoint

**Files:**
- Create: `src/main/java/dev/quantumchamber/persistence/ChunkSaveCheckpoint.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/MeasuredWorldSaveCheckpoint.java`、`NativeSaveFailures.java`、`NativeSaveFailureAccess.java`
- Modify: `src/main/java/dev/quantumchamber/mixin/MinecraftServerSaveFailureMixin.java`、`StorageIoWorkerSaveFailureMixin.java`、`EntityChunkRecoverySaveMixin.java`
- Test: 擴充既有的 `src/test/java/dev/quantumchamber/persistence/NativeSaveFailuresTest.java`；`-Mode Windows` 涵蓋的 checkpoint 測試
- Modify（GameTest 注入）：`src/testmod/java/dev/quantumchamber/gametest/mixin/MeasuredCheckpointFaultMixin.java`、`src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`

**範圍（spec §4 H2；M4 handoff 第 9 點）：**
- 目前 `MeasuredWorldSaveCheckpoint.java:30-38` 用全伺服器的 `saveFailureRevision`，`NativeSaveFailures` 是整個 worker 的計數。無關 chunk 的失敗會讓清理一直重試。
- 改為 per-chunk（或等效）追蹤：`onChunkSaveFailure(error, key, pos)`、`StorageIoWorker.setResult(pos, …)`、`EntityChunkDataAccess.handleSaveFailure(future, pos)` 都帶 chunk 位置，記錄到「(storage, chunk) → 失敗 revision」。判定時以 region 為單位（spec v5.3 §4 H2）：相關 chunks 所在 region 檔（`chunk >> 5`）內任一 chunk 的失敗都視為相關。原版 flush 時仍 pending 的寫入失敗由原版 join 傳出，仍 fail-closed；這是原版語意的界線，不繞過。
- `completeAll`（region flush）失敗沒有 chunk 位置：保守地視為該 worker 全部 chunks 都失敗（fail-closed）。
- `ChunkSaveCheckpoint.save(MinecraftServer, Map<ServerWorld, Set<ChunkPos>>)` 是通用元件：redirty → `server.save(false,true,true)` → barrier → 只比對相關 chunks 的失敗。`MeasuredWorldSaveCheckpoint` 改為呼叫它；F4b 的投影會再用它。
- 沿用現有退避（`CorridorPageManager` 的 per-space 20→1200 ticks）與 WARN 去重；不改呼叫端語意。

- [ ] **Step 1: RED。** pure JVM：證明「無關 chunk 失敗」目前會讓相關 chunk 的 verify 失敗；GameTest（`_windows`）：注入一個無關 chunk 的存檔失敗，MEASURED 清理被擋住。記錄 HEAD 與 status。
- [ ] **Step 2: GREEN。** 實作 per-chunk 追蹤與 `ChunkSaveCheckpoint`。
- [ ] **Step 3:** 相關 chunk 失敗仍 fail-closed（保留 leases）；`completeAll` 失敗仍 fail-closed。
- [ ] **Step 4:** 完整回歸 `-Mode Full`、`-Mode Windows`、`-Mode Main`＋oracle（mixin 變更）、`m4-recovery-probe.ps1`。
- [ ] **Step 5: Commit** `fix: scope the native save checkpoint to the transaction's chunks`

---

### Task H3: 未 checked authority 的收斂

**Files:**
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Modify: `src/main/java/dev/quantumchamber/candidate/CandidateLedgerService.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`
- Test: `src/test/java/dev/quantumchamber/persistence/` 下既有的 schema 3 測試檔、`src/test/java/dev/quantumchamber/candidate/CandidateLedgerServiceTest.java`；必要時 `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`

**範圍（spec §4 H3）：**
- q1：`abortUnchecked`（`SessionRecoveryState.java:129-145`）在 CAS 成立時，只回退尚未 checked 的 authority：`candidateChecked(expected)` 為真、但 expected≠durable 時不得倒退。
- q2：`commitChecked`（`CandidateLedgerService.java:150-157`）的 abort 本身拋例外時，真的隔離該 session（`quarantined.add`），log 與實際狀態一致。
- q3：隔離中的 session 要有 checked RETURNING 的收斂路徑（目前隔離後玩家困到重啟）。
- q4：殘留的 unchecked ARMING 也走同一個收斂機制；修正 `SessionRecoveryManager.java:73` 說得過滿的註解。
- q5＋待辦：`abandonInitial`（`SuperpositionSessionManager.java:154-169`）在 abort 之後補一次 checked 寫出，覆蓋磁碟上未確認的 ARMING；WARN 記 root cause，並依 chamber＋原因去重（比照 `checkpointCapable`）。
- Nit 3：`CandidateLedgerService.describe()`（`:162-167`）以 identity set 或深度上限走訪 cause chain。

- [ ] **Step 1: RED（每一項一個測試）。** q1：`candidateChecked(expected)` 為真且 expected≠durable 時，abort 不得倒退；q2：abort 丟例外後 session 必須在 `quarantined`；q3：隔離 session 經收斂路徑寫出 checked RETURNING，玩家返還；q4：殘留 unchecked ARMING 被收斂；q5：abandon 之後磁碟上不再有該 ARMING（讀檔比對），WARN 同原因只記一次；Nit 3：兩節點 cause 環在有限步內結束。
- [ ] **Step 2: GREEN。** 任何新路徑都不得承認未 checked 的 candidate／selection。
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `fix: make unchecked-authority aborts fail closed and converge`

Review 必確認：既有測試 `readbackFailureAfterWriteRevertsAndReturningOverwritesUnacknowledgedDisk` 的語意仍成立或被更嚴格的行為取代，且在 implementation note 說明。

---

### Task H4: 凍結時的位置修正與測試品質子項

**Files:**
- Modify: `src/main/java/dev/quantumchamber/mixin/ServerPlayNetworkHandlerRecoveryMixin.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`（只限凍結判斷與節流狀態）
- Modify: `src/main/resources/quantumchamber.mixins.json`（`"injectors": {"defaultRequire": 1}`）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`（`:1591` 的 yaw／pitch 改原始 float 比較；新增位置修正 GameTest）
- Modify: `src/testmod/resources/fabric.mod.json`（`name` 改為「QuantumChamber 原生驗證 testmod」）

**範圍（spec §4 H4）：**
- 取消 `onPlayerMove`／`onVehicleMove` 時，送回伺服器權威位置（`requestTeleport` 或等效），每位玩家節流（例如每 10 ticks 最多一次）。
- 涵蓋 RETURNING 與 MEASURED 的凍結。把「是否凍結」集中成單一方法，F3d 只需要把 COLLAPSING 加進去。
- mixin 設定加 `defaultRequire: 1`，所有 injector 都必須命中。若本 task 碰到的欄位缺 `@Unique`，一併補上；其他不碰。

- [ ] **Step 1: RED。** GameTest：MEASURED（或 RETURNING）凍結中的玩家送出移動封包，驗證目前沒有位置修正；節流：連續 20 個封包最多產生 2 次修正。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 測試品質子項。
- [ ] **Step 4:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`-Mode Main`＋oracle、`m4-recovery-probe.ps1`。
- [ ] **Step 5: Commit** `fix: send position corrections while recovery freezes movement`；測試品質子項另一個 commit `test: compare raw yaw and pitch, rename the testmod`。

---

### Task F1a: 真 client probe 工具

**Files:**
- Create: `src/testmod/clientprobe/java/dev/quantumchamber/gametest/M5ClientProbe.java`
- Create: `src/testmod/clientprobe/resources/fabric.mod.json`（mod id `quantumchamber-clientprobe`、`environment:"*"`、只有 `M5ClientProbe` 一個 `main` entrypoint、沒有 mixin）
- Modify: `build.gradle`：
  - 新增 source set `clientProbe`：`srcDir 'src/testmod/clientprobe/java'`、resources `src/testmod/clientprobe/resources`；compile classpath＝main＋testmod 輸出；runtime classpath＝`client.runtimeClasspath`＋testmod 與 clientProbe 輸出。
  - 新增空的 source set `serverProbe`，只當 server run 的 classpath 載體：runtime classpath＝`testmod.runtimeClasspath`＋testmod 與 clientProbe 輸出（不要讓 server run 直接用 clientProbe 的 source set，否則 clientOnly 的 Minecraft jar 會上 server classpath）。
  - 新增 run `clientProbe`（`client()`、`source sourceSets.clientProbe`）與 `serverProbe`（`server()`、`source sourceSets.serverProbe`），兩者都 `vmArg '-Dquantumchamber.clientProbe=true'`，runDir 依 `-Pm5ProbeNonce` 為 `run/client-probe-<nonce>`、`run/server-probe-<nonce>`，比照 `m4Recovery` 的 nonce 驗證。
  - 讓 `check` 依賴 `compileClientProbeJava`，Full gate 的 `build` 因此會編譯它；之後改到 `UniverseTransferTestAccess` 或 lifecycle 的 task 若弄壞它，Full gate 會失敗。
- 只讀共用：`src/testmod/java/dev/quantumchamber/universe/UniverseTransferTestAccess.java`（若需要新的唯讀 helper，可以修改此檔）。`M3UniverseTransferProbe.java` 不修改。

**範圍（spec §14）：**
- testmod 本身維持 `environment:"server"`，不改。clientProbe 是 `src/testmod/` 之下的獨立 source set，不進 release JAR。
- 只在 `-Dquantumchamber.clientProbe=true` 時註冊 `/qcprobe`（op 2）。M3 的 runtime 物化 API 要到 F4a 才有，所以流程是「配置 → 重啟 → 由 bootstrap 物化 → 進入」，同時驗證重啟後回到同一個世界：
  - `/qcprobe universe allocate`：catalog 為空時，以固定 UUID `allocateOverworld(uuid, 0)`（`EAGER_ENABLED`）並 checked flush，提示需要重啟。
  - `/qcprobe universe enter`：以 `UniverseTransferTestAccess.capture(server)` 取得正式 lifecycle 的 backend，`resolveActive` 拿到 exact ACTIVE world；加 chunk ticket、等 FULL、在目的 XYZ 下方放一塊地板，再以 `UniverseTransferService.moveToUniverse` 傳送執行指令的真實玩家。
  - `/qcprobe universe return`：以 receipt 傳回。
  - `/qcprobe universe status`：唯讀印出玩家所在 world key 與 catalog 狀態。
- **禁止**直接 new backend 或呼叫 backend 的 materialize：繞過 lifecycle 會留下 `STOPPED_UNVERIFIED` 風險。
- 沒有 property 時不註冊任何東西。

- [ ] **Step 1: RED。** pure JVM 或 GameTest：沒有 property 時指令不存在。
- [ ] **Step 2: GREEN。** 實作指令與 source set、run 設定。
- [ ] **Step 3: 啟動 smoke。** `runServerProbe` 與 `runClientProbe`（新 nonce）都能啟動到可進入世界，而且兩個 run 都斷言 `/qcprobe` 已註冊（不只核對 mod list）。mod list：clientProbe run 有 `quantumchamber-clientprobe`、沒有 `quantumchamber-testmod`；serverProbe run 兩者都有。確認 `gameTest`、`m4Recovery` 的 classpath 不受影響。
- [ ] **Step 4:** 完整回歸 `-Mode Full`、`-Mode Main`＋oracle、`run-all.ps1`（`build.gradle` 的 run 設定有變）；JAR 檢查 testmod／clientprobe 命中 0。
- [ ] **Step 5: Commit** `test: add an opt-in real-client universe probe`

### Task F1b: 真 client 驗證（blocker）

**不改 tracked 程式。** 由 controller 依 memory 的代操作流程執行（`C:\Users\Ben\Documents\QC-acceptance-evidence\tools\mc-drive.ps1`，證據放 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-10-<日>\`），結果寫入 implementation note（tracked）。證據一律標明 `testmod-present`（含 clientprobe），不得當成 main-only 證據。

- [ ] 單人（`runClientProbe` 的 integrated server）與 dedicated（`runServerProbe`＋`runClient`）各至少一次：`allocate` → 重啟 → `enter` → `return`。進出動態世界不斷線、不 crash；天空、時間、天氣正常、地形可見；可以移動、放置與破壞方塊。
- [ ] 在動態世界中重新登入，以及重啟 server，都回到同一個世界。
- [ ] 在動態世界死亡：記錄實際的原版重生行為（預期回 canonical 主世界，作為 §2 已知限制的證據）。
- [ ] Sodium＋Iris：從外部備份的 `run/client-render/mods` 唯讀複製 render 用的 mods 到 `run/client-probe-<nonce>/mods`（記 SHA-256），光影開與關各進出一次，不 crash、光影不被強制關閉。
- [ ] **任何一項失敗：立即停止 M5 玩法實作，保留證據，回報使用者。**

---

### Task F2a: Schema 4、狀態機、唯一性與終局 API

**Files:**
- Modify: `src/main/java/dev/quantumchamber/superposition/SessionState.java`（新增 `COLLAPSING`、`PASSAGE_READY`、`SEALED`）
- Create: `src/main/java/dev/quantumchamber/persistence/CollapseFields.java`
- Create: `src/main/java/dev/quantumchamber/collapse/CollapseDestination.java`、`SealedReason.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`CandidateJournalCodec.java`
- Test: 新增 `src/test/java/dev/quantumchamber/persistence/SessionRecoverySchema4Test.java`

**Interfaces（名稱可依現有風格調整，語意不可變）：**

```java
public record CollapseFields(int revision, long committedAtGameTime, CollapseDestination destination,
        long passageOpenedAtGameTime, long passageExpiresAtGameTime, List<UUID> crossed,
        List<UUID> recoveryRequired, Optional<SealedReason> sealedReason) {
    public static final CollapseFields EMPTY; // ARMING／SUPERPOSITION／RETURNING／MEASURED
}
public sealed interface CollapseDestination { record Source() ...; record Universe(UniverseId id) ... }
public enum SealedReason { ALLOCATION_IO_FAILED, PROJECTION_FAILED, MATERIALIZE_FAILED, AUTHORITY_UNREADABLE,
        EXISTING_INELIGIBLE, AUTHORITY_CORRUPT, PROJECTION_CONFLICT, TRANSFER_RECOVERY_REQUIRED;
    public boolean retryable(); public boolean restartOnly(); }

// SessionRecoveryState
public static boolean receiptRetained(SessionState state);
public static boolean occupies(SessionRecoveryRecord record, UUID player);
public void markCollapseAuthoritiesInitialized();          // F2b 的初始化成功時呼叫：m5Evidence 與 m5ReadyThisInstance 都設為真
public boolean m5ReadyThisInstance();                       // 提交 COLLAPSING 的閘門；讀到 schema 4 不會讓它變真
public Optional<SessionRecoveryRecord> collapseView(UUID session); // spec §6：flushed，或 roll-forward 尚未確認時的 current
public TerminalResult terminate(MinecraftServer server, SessionRecoveryRecord expected, TerminalOrigin origin);
public enum TerminalOrigin { PASSAGE_ENDED, SOURCE_FAST_PATH, PREPARE_DOOR_CLOSED, UNSUPPORTED_SOURCE_CANCEL }
public CommitResult commitRollForward(MinecraftServer server, SessionRecoveryRecord expected, SessionRecoveryRecord next);
public CommitResult commitRestore(MinecraftServer server, SessionRecoveryRecord expected, SessionRecoveryRecord next);
```

**範圍：** spec §5 全部（record 欄位含 `RecoveryRequired`、雙向的合法組合、單調 authority 與狀態邊、`occupies`、終局 API、`receiptRetained`、schema 寫出式子），以及 journal 的 roll-forward／restore 兩種提交（spec §6）。本 task 不接 runtime 呼叫點（F3a 起才接），所以 production 行為不變。

- [ ] **Step 1: RED。**
  - 合法狀態矩陣（每個狀態的合法／非法組合，含 TRR 的雙向「必須」）。
  - `sameAuthority` 的每條狀態邊與 self-loop：PASSAGE_READY→SEALED 只接受 TRR，保留 `Crossed` 與 opened／expires；TRR 沒有 SEALED→COLLAPSING。
  - 不可變欄位；`occupies` 表（含「SEALED A 的已返還參與者同時是活動 B 的參與者」）。
  - 終局 API 的 CAS、flush 失敗放回並保持 dirty、tombstone、`authorityHistory` 修剪（M4 quality M1）。
  - roll-forward：flush 失敗後 current 保留新內容、`recoveryWritesSafe` 仍為真、其他 session 的 flush 把它寫出。restore：current 還原並保持 dirty。
  - collapse view：只有 roll-forward 尚未確認的 journal 寫入（SEALED、`Crossed`、返還與清 lease 進度）才回 current，其他情況回 flushed。
  - schema 寫出式子：空 journal＋已初始化 → 2；只有 legacy → 2；`candidateInitialized`＋`m5Evidence` 為假 → 3；`candidateInitialized`＋`m5Evidence` 為真 → 4；讀到 4 → `candidateInitialized` 與 `m5Evidence` 為真、`m5ReadyThisInstance` 仍為假；之後不降回。
  - SEALED(TRR) 完全凍結（沒有 self-loop），`RecoveryRequired` 只寫一次。
  - schema 4 strict codec 與 rejection fixtures；schema 3 讀入補空 `Collapse`；`remove` 拒絕三個 M5 狀態。
- [ ] **Step 2: GREEN。** `validateOwnership`、`participantsAvailable` 改用 `occupies`。testmod 的 `M4CandidateTestAccess`（`:69-79`、`:95-107`）以反射按欄位名讀寫 `records`、`authorityHistory`、`checkedAuthority`；這三個欄位名必須保留。若一定要改，先回報 controller，把 `M4CandidateTestAccess.java` 列入本 task。
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `feat: add journal schema 4 with collapse states and a checked terminal`

---

### Task F2b: M5 authority 初始化、兩個 ledger 與收斂

**Files:**
- Create: `src/main/java/dev/quantumchamber/collapse/UniverseAllocationLedger.java`、`ProjectionLedger.java`、`CollapseAuthorities.java`、`CollapseBackoff.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`（決策 2 的註冊順序）
- Test: 新增 `src/test/java/dev/quantumchamber/collapse/UniverseAllocationLedgerTest.java`、`ProjectionLedgerTest.java`、`CollapseAuthoritiesTest.java`（fault 注入依決策 9，用 package-private 的 IO port，不用 testmod mixin）

**Interfaces：**

```java
public final class UniverseAllocationLedger {
    public static UniverseId deriveUniverseId(CandidateBytes allocationToken);   // spec §6.1
    public record Entry(CandidateBytes allocationToken, UniverseId universeId, GeneratorProfile profile, int profileVersion) {}
}
public final class ProjectionLedger {
    public record Entry(UUID chamberUuid, UniverseId universeId, DimensionRole role, BlockPos controllerPos,
            Direction outwardFacing, Status status) { public enum Status { PENDING, COMPLETE } }
}
public final class CollapseAuthorities {          // 每個 server instance 一個
    public static CollapseAuthorities get(MinecraftServer server);
    public InitResult initialize(MinecraftServer server);      // SERVER_STARTED，lifecycle 之前
    public boolean initialized();
    public Lookup<UniverseAllocationLedger.Entry> lookupOrAppendAllocation(NewUniverseIntent intent);
    public Lookup<ProjectionLedger.Entry> projection(UUID chamber, UniverseId id);
    public WriteResult writeProjection(ProjectionLedger.Entry entry);   // PENDING 或 COMPLETE
    public boolean unconfirmed(Authority authority);            // §6 收斂旗標
    public void convergeIfNeeded(Authority authority);          // 下一次使用前的 checked 重寫
}
```

**範圍：** spec §6 的「M5 authority 初始化與遺失判定」「既有紀錄的驗證」（ledger 部分）「讀取失敗的分類」「checked 寫入失敗的收斂」（ledger 部分，roll-forward）、§6.1、§6.5（不含實體交叉檢查，那在 F4b）。

- UniverseId 推導的固定 test vectors 以 hex 常數寫在測試中，不得在測試裡用 production helper 反算。
- 初始化依 journal flushed envelope 決定補建或判為遺失；成功時呼叫 `SessionRecoveryState.markCollapseAuthoritiesInitialized()`；任何原因的失敗都不呼叫，並記 ERROR。
- 收斂以權威為單位的退避（`CollapseBackoff`，200→12000 ticks）。

- [ ] **Step 1: RED。** test vectors；append-only 與唯一性；rejection fixtures；既有 entry 驗證；遺失判定表（spec §15 的每一列：全新存檔、schema 3＋discovery 非空、只建了一個 ledger、schema 4 刪檔、只有 EXISTING 造訪過的存檔刪 projection ledger、初始化失敗不升 schema 4）；收斂（注入 atomic move 後讀回失敗：append 不被移除、下一次使用前重寫、未確認期間拒絕新決策）。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`-Mode Main`＋oracle、`-Mode M3`、`m4-recovery-probe.ps1`（第一次在 runtime 寫出 schema 4 與 ledger 檔）。
- [ ] **Step 4: Commit** `feat: add the allocation and projection ledgers with schema 4 initialization evidence`

---

### Task F2c: Discovery append 與 catalog 的既有紀錄驗證

**Files:**
- Modify: `src/main/java/dev/quantumchamber/candidate/UniverseDiscoveryState.java`（正式 checked `append`）
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseAuthorities.java`（discovery 與 catalog 的收斂 owner）
- Modify: `src/main/java/dev/quantumchamber/universe/UniverseRegistryState.java`、`UniverseCatalogStore.java`（只限加入 package-private 的 IO port 以便注入「atomic move 完成、讀回失敗」；production 行為不變）
- Test: `src/test/java/dev/quantumchamber/candidate/UniverseDiscoveryStateTest.java`、擴充或新增 `src/test/java/dev/quantumchamber/universe/UniverseRegistryStateTest.java`（catalog 的 store 層注入）、`src/test/java/dev/quantumchamber/collapse/CollapseAuthoritiesTest.java`（以 `CollapseAuthorities` 自己的 port 測未確認狀態）；fault 注入依決策 9

**範圍：** spec §6.2（既有 record 驗證、roll-forward；**不**新增 catalog 還原 API）、§6.4（append 與既有 record 驗證）、§6 收斂規則（discovery 與 catalog 部分）。M3 既有的 `save` 失敗語意（保留 current 與 dirty）本來就是 roll-forward，不改。

- [ ] **Step 1: RED。** append 的 ordinal／time／observer；idempotent；既有 record 不符（`gameplayEligible=false`、`firstObserver` 不符）時拒絕且不改寫；discovery 讀回失敗後，已落地的 record 不被移除、M4 讀取點看到的候選池不倒退；catalog 配置讀回失敗後保留 dirty、下一次寫出帶著它、確認前 `unconfirmed` 為真；catalog 既有 record 不符（profile、availability）時拒絕。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode M3`。
- [ ] **Step 4: Commit** `feat: add checked discovery append and catalog verification`

---

### Task F3a: 既有 MEASURED-only 分支

**Files:**
- Modify: spec §13.2 表列出的 production 檔案與位置：`superposition/SuperpositionSessionManager.java`、`persistence/SessionRecoveryManager.java`、`persistence/SessionRecoveryState.java`、`corridor/CorridorPageManager.java`、`persistence/MeasuredWorldSaveCheckpoint.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/MeasuredRecoveryPhase.java`（延伸到 COLLAPSING 或抽出共用推導；移除永遠不會回傳的 `RETAIN_RECEIPT`，M4 quality M6 的這一小項）
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberSessionGateway.java`（新增 `Presence.COLLAPSE`、`PASSAGE`）、`ChamberPowerCoordinator.java`（只限 `reconcile` 對 M5 presence 的分支，決策 11；PASSAGE→15 與 R2 M5 在 F5）
- Test: GameTest 加在 `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`；pure JVM 加在 `SessionRecoverySchema4Test.java`、`src/test/java/dev/quantumchamber/chamber/ChamberPowerCoordinatorTest.java`

**範圍：** spec §13.2（`ChamberState`、`ChamberStatusSignal`、`ChamberControllerBlock` 那幾列除外，屬 F5）。本 task 讓所有 MEASURED-only 分支認得三個 M5 狀態：`receiptRetained`、`occupies`、`recordFor`、`presence()` 依 collapse view 推導（COLLAPSING／SEALED→`COLLAPSE`、PASSAGE_READY→`PASSAGE`）、`reconcile` 的 M5 分支（比較器暫時一律 11）、M5 狀態的 `returnToOrigin` 沒有副作用並回 false、attach 只為 COLLAPSING 建立來源 ticket。**還沒有**任何程式會產生 M5 狀態，所以 production 行為不變。

- [ ] **Step 1: RED。**
  - 以 journal fixture 放入 COLLAPSING record，在 test 內直接呼叫 `requestMeasuredRecovery` 與 tick 路徑並 assertThrows，證明目前會丟例外（`CorridorPageManager.java:219`、`SuperpositionSessionManager.java:179-184`）。不要讓例外從 `END_SERVER_TICK` 拋出，否則整個 GameTest server 會 crash。
  - `recordFor` 在玩家同時屬於 SEALED A（已返還）與活動 B 時挑錯 record。
  - `presence()` 對沒有 runtime 的 M5 record 回 UNKNOWN；`reconcile` 遇到它會走 pending 並呼叫 `returnToOrigin`（pure JVM，`ChamberPowerCoordinatorTest`）。
  - attach 會為 PASSAGE_READY／SEALED 建立來源 ticket。
  - `MeasuredRecoveryPhase.from()` 對 COLLAPSING 丟例外。
  - 測試結束時以終局 API 或 testmod 的 teardown 清除 fixture，不留殘留。
- [ ] **Step 2: GREEN。** 逐一處理 §13.2 每一列，表外不得改動其他分支。
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode Legacy`、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `refactor: teach MEASURED-only branches about the collapse states`

---

### Task F3b: 量子膜方塊狀態

**Files:**
- Create: `src/main/java/dev/quantumchamber/chamber/MembraneState.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/QuantumBulkheadBlock.java`（`membrane` 屬性、`withOpen`、逐狀態碰撞、opacity、culling、`suffocates`、`blockVision`）
- Modify: `src/main/java/dev/quantumchamber/chamber/WorldChamberDoorAdapter.java`、`src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`（只限 `:476-477` 改用 `withOpen`）
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberDoorService.java`（決策 10：公開的「套用目標狀態」API，沿用 `mutateWithRollback` 與 `activationBlocked` 的處理）
- Modify: `src/main/resources/assets/quantumchamber/blockstates/quantum_bulkhead.json`、`models/block/`（新增 membrane model）、新增 `textures/block/quantum_membrane.png`
- Modify: `src/client/java/dev/quantumchamber/client/QuantumSuperpositionClient.java`（`BlockRenderLayerMap` cutout）
- Test: 新增 `src/test/java/dev/quantumchamber/chamber/MembraneStateTest.java`；GameTest 加在 `M5CollapseGameTests.java`（新增檔並加入 testmod `fabric-gametest` entrypoint；`src/testmod/resources/fabric.mod.json`）

**範圍：** spec §10.1。只做方塊與門的狀態；什麼時候套用哪個 membrane 由 F4b、F5 決定。「沒有所屬 session 的 membrane 正規化」放在 F5（它在 `ChamberPowerCoordinator`）。

- [ ] **Step 1: RED。** `open=false` 只能是 `none`；舊存檔沒有屬性時為 `none`；sealed 有完整碰撞、passable／none 沒有；`withOpen(false)` 一定設 `none`；`ChamberDetector` 對任何 membrane 都是 BULKHEAD_OPEN；方塊仍不可被流體取代；「套用目標狀態」API 對 25 格一致套用，任一格失敗時整面 rollback。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode Main`＋oracle（client 程式碼與資源）。
- [ ] **Step 4: Commit** `feat: add the quantum membrane door states`

---

### Task F3c: M5 GameTest 隔離 run

**Files:**
- Modify: `build.gradle`（新增 run `gameTestM5`：`server()`、`source sourceSets.testmod`、runDir `run/gametest-m5`、`vmArg '-Dfabric-api.gametest'`、`vmArg '-Dquantumchamber.gametest.m5Only=true'`、report file `gametest-m5-results.xml`）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/mixin/M4LegacyGameTestFilterMixin.java`（`m5Only` 時只保留 batch id 前綴 `m5_universe` 的 test；default run 排除它們；legacy run 不變）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M4GameTestBoundaryProbe.java`（`m5Only` 時不註冊，避免 M5 run 留下 FAIL receipt）
- Create: `src/testmod/java/dev/quantumchamber/gametest/M5UniverseGameTests.java`。先放一個 sanity test：只做 catalog 配置、改為 `DISABLED`、checked flush（比照 `M3UniverseLifecycleGameTests.java:62-64`；`DISABLED` 讓停服驗證不會期待一個沒物化的 world），**不物化**（runtime 物化 API 要到 F4a 才有，直接呼叫 backend 會繞過 lifecycle）；斷言 default run 不會執行它。
- Modify: `src/testmod/resources/fabric.mod.json`（登記新的 `fabric-gametest` entrypoint）
- Modify: `scripts/verification/gates.ps1`（新增 `-Mode M5GameTest`：fresh world move／restore，與 default 相同的安全行為；不檢查 M4 boundary oracle，改為檢查每個 M5 test 自己斷言的 catalog 增量）、`scripts/verification/run-all.ps1`（新增步驟與 summary 數字；`:93-97` 的 CI 規則呼叫加上 M5 XML）、`scripts/verification/ci-rule-check.ps1`（新增 M5 GameTest XML 參數，fixture 同時寫入兩份 XML）、`scripts/verification/README.md`
- Modify: `.github/workflows/build.yml`（Windows 與 Ubuntu job 都加跑 `runGameTestM5`；`_windows` 宣告規則同步排除與納入）

**範圍：** 決策 6。之後所有會配置或物化 Universe、或留下 M5 record 的 GameTest，都用 `batchId="m5_universe_<名稱>"` 放進 `M5UniverseGameTests.java`。M5 run 的衛生規則：注入失敗的 test 必須等 catalog 與 ledger 收斂完才結束（catalog 一直 dirty 會讓後續所有 NEW 配置都在等待）。

- [ ] **Step 1: RED。** 先把 sanity test 放在 default run，證明 `M4GameTestBoundaryProbe` 判 FAIL（保留這個 RED root）。
- [ ] **Step 2: GREEN。** 移到 M5 run；default run 的 boundary oracle 回到 PASS；M5 run 只執行 `m5_universe*`，而且沒有 boundary receipt。
- [ ] **Step 3:** `ci-rule-check.ps1` 對新 workflow 兩個 step PASS，並加一個 mutation 證明規則會擋下 M5 XML 的缺漏。
- [ ] **Step 4:** `run-all.ps1` 全綠。
- [ ] **Step 5: Commit** `test: isolate universe-touching GameTests in their own run`

---

### Task F3d: CollapseService（提交、返還、SOURCE、Nether／End）

**Files:**
- Create: `src/main/java/dev/quantumchamber/collapse/CollapseService.java`、`CollapsePhase.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`（決策 1）
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`（COLLAPSING 凍結、返還延伸、返還失敗 WARN 附 cause）
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`（終局後移除 runtime 與來源 ticket 的 API）
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`（release 延伸）
- Modify: `src/main/java/dev/quantumchamber/transfer/ChamberReturnPlacement.java`（R2 M6：返還落點碰撞）
- Modify: `src/main/java/dev/quantumchamber/candidate/CandidateDoorInteraction.java`（Nether／End 拒絕）
- Modify: `src/main/resources/assets/quantumchamber/lang/zh_tw.json`、`en_us.json`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/CollapseSuspendMixin.java`（決策 7；依 testmod 靜態集合或 system property `quantumchamber.test.collapseSuspended=all` 暫停），登記在 `quantumchamber-test.mixins.json`
- Modify: `build.gradle`（`m4Recovery` run 加 `vmArg '-Dquantumchamber.test.collapseSuspended=all'`）
- Modify: 依賴「MEASURED 會一直等待」的 M4 GameTest：`src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`（加入 hook、處理延後清單「M4 test 收尾」與 q7 的 `selectionTeardown`）；若 `TestHooks` 或 M2 的 `OBSERVED` 靜態表必須改，先回報 controller
- Test: `M5CollapseGameTests.java`（SOURCE、無殘留）、`M5UniverseGameTests.java`（UNIVERSE 停在 RESOLVE 的過渡測試）

**範圍：** spec §7 開頭的規則（讀 collapse view、寫入前 current＝flushed、尚未確認的 roll-forward 期間只做收斂、「已供電」＝即時紅石輸入）。本 task 涵蓋的階段（返還、清理、RESOLVE stub、WAITING）門都是一般狀態，依 spec §7 照該階段規則處理，**不改門的攔截**（攔截在 F4b）。另含§7.1、§7.2、§7.3 的 RETURN_PLAYERS／RELEASE_GEOMETRY／RESOLVE（本 task 的 RESOLVE 是 stub：對 UNIVERSE 目的地停住、WARN 一次「目的地解析尚未實作」，不配置、不寫 SEALED；F4a 取代）、§7.4 SOURCE fast path、§5 終局之後移除 runtime 與來源 ticket、§11.1 的觸發部分、§11.3、§10.8 的「塌縮」與「Nether／End 選門」訊息。PREPARE、量子膜的套用與 PREPARE 期間的門規則在 F4b。

- [ ] **Step 1: RED（GameTest）。**
  - 選門 → 下一 tick COLLAPSING；全員返還（含返還落點碰撞、WARN 附 cause）；COLLAPSING 期間未返還者凍結並收到位置修正。
  - SOURCE → 一般開門、catalog 不變、record 移除、INFO；終局之後 presence 為 NONE、runtime 與來源 ticket 已釋放、同艙可以立即開新 session。
  - 返還、清理、RESOLVE stub、WAITING 期間，參與者開關門不會結束塌縮（spec §3 詮釋 3）。
  - 返還進度的 roll-forward：注入返還寫入的落盤失敗 → 只重試同一份 current，不疊寫其他參與者的返還；確認後恢復。
  - 離線參與者重新加入後完成返還；COLLAPSING 期間 Buff 到期不丟例外。
  - Nether／End 選門被拒；Nether／End 的 M4 NEW 收據 → DORMANT → 取消（INFO）。
  - DORMANT 邊緣觸發；未供電或停用不觸發。
  - 提交失敗（注入）→ M4 fallback → DORMANT → 退避（不會每個 tick 重試）；M5 authority 初始化失敗 → 不提交。
  - 跨 session 唯一性：已返還的參與者可以在別艙開 session；DORMANT 續行不因其他參與者在別的 session 而失敗；SEALED A 的已返還參與者斷線後，活動 B 正確進入 RETURNING。
  - M4 GameTest 在 hook 下維持原斷言。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode M5GameTest`、`-Mode Legacy`、`-Mode Main`＋oracle、`m4-recovery-probe.ps1`（hook 下 24 phases 全過）；`build.gradle` 的 run 設定有變，另跑 `run-all.ps1`。
- [ ] **Step 4: Commit** `feat: commit collapses and return the cohort to the origin chamber`

---

### Task F4a-0: M5 GameTest 共用支援（F3d 交接，controller 2026-10-02 插入）

F3d 的 quality review 建議：在 F4a 新增任何 test 之前，先把 M5 GameTest 的共用部分抽出來，並另做一次獨立 review。這是前置整理，不屬 spec 的任何章節。除下列兩項 log 與門的小修之外，production 行為不變。

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M5GameTestSupport.java`。若需要拆分，可在同一個 package 多建幾個 M5 專用的 support 或 hook 類別。
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/` 下一個統計 journal 寫出的 mixin（`SessionRecoveryState.save(File,…)`），登記在 `quantumchamber-test.mixins.json`。
- Modify:
  - `M5CollapseGameTests.java`、`M5UniverseGameTests.java`。
  - `M4CandidateDoorGameTests.java`、`M4CandidateTestAccess.java`、`LogCapture.java`：只限共用 helper 與 hook 的搬移、log 篩選。
  - `mixin/SessionJournalWindowFaultMixin.java`、`mixin/CollapseSuspendMixin.java`：只限改指向搬移後的 hook 類別。
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseService.java`，只限兩件事：
  - log 帶 SLF4J Marker，或改用專屬 logger；
  - `applyDoor` 依 `DoorStep.warn()` 決定是否記錄。
- Modify: `scripts/verification/README.md` 的「新增 M5 GameTest」一節。

**範圍：** F3d note「交給後續」中 F4a 的 `M5GameTestSupport` 與 quality re-review 的 m-1～m-3，以及 F3d fix round 2 的 m-6(d)（把 hook 搬出 `M4CandidateDoorGameTests`）。m-4 屬 F4a 本身。

- [ ] **Step 1：** 純搬移。test 數不變；`m5_collapse_*`、`m5_universe_*`，以及掛 hook 的 M4／F3a batch，focused 全部 PASS。
- [ ] **Step 2：** m-1。窗口 helper 改為斷言「窗口內每一次 journal 寫出都經過探針」，G-SEAL 與 G-RFB 改用它。在窗口內觸發一次 `server.save` 的 mutation 必須 FAIL。
- [ ] **Step 3：** m-2。`collapseFlowNeverWarned` 改為依 marker（或 logger）篩選。用一則帶 marker、但沒有舊前綴的 WARN 做 mutation，必須 FAIL。
- [ ] **Step 4：** m-3。`DoorStep` 的 JUnit 要能鎖住 production 的記錄行為。
- [ ] **Step 5：** 完整回歸 `run-all.ps1`。
- [ ] **Step 6: Commit** `test: share M5 GameTest support and count every journal write`

---

### Task F4a: RESOLVE（lifecycle 物化、EXISTING 重驗、NEW 配置）

**Files:**
- Create: `src/main/java/dev/quantumchamber/collapse/DestinationResolver.java`
- Modify: `src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseService.java`（以 RESOLVE 取代 stub；SEALED 的原因、退避、SEALED→COLLAPSING 重試；RESOLVE 完成後在 F4b 之前停在 WAITING，WARN 一次「準備階段尚未實作」）
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`（進入 SEALED 時釋放來源 ticket、重試時再取得，spec §5）
- Test: 新增 `src/test/java/dev/quantumchamber/collapse/DestinationResolverTest.java`；GameTest 加在 `M5UniverseGameTests.java`
- Test（fault 注入）：`src/testmod/java/dev/quantumchamber/gametest/mixin/` 新增物化失敗與 ledger IO 失敗的 mixin，登記在 `quantumchamber-test.mixins.json`

**Interfaces：**

```java
// UniverseLifecycleService（server thread）
public static MaterializeOutcome materializeAllocated(MinecraftServer server, UniverseId id); // ACTIVE world 或失敗原因
public static Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseId id);
// DestinationResolver
public ResolveResult resolve(MinecraftServer server, SessionRecoveryRecord record);  // RESOLVED／WAIT／SEALED(reason)
```

**範圍：** spec §6.2、§6.3、§8（全部）、§11.2（`ALLOCATION_IO_FAILED`、`MATERIALIZE_FAILED`、`AUTHORITY_UNREADABLE`、`EXISTING_INELIGIBLE`、`AUTHORITY_CORRUPT` 的進入與重試）。

- [ ] **Step 1: RED。**
  - EXISTING：fixture 同時寫入一致的 allocation 與 projection ledger（spec §15）；重驗失敗 → `EXISTING_INELIGIBLE`。
  - NEW：ledger → catalog → 物化 → discovery，catalog 恰好加 1；RESOLVE 不受斷電影響。
  - 注入物化失敗 → `DISABLED`＋SEALED(`MATERIALIZE_FAILED`)、同 server instance 不重試、門可開、來源 ticket 已釋放。
  - §8.3 的兩個方向：`DISABLED` 落盤失敗 → roll-forward（current 保留 `DISABLED`、持續重寫）；重試時改回 `EAGER_ENABLED` 的寫入失敗 → restore（current 改回 `DISABLED`、不物化）。
  - 注入 IO → `AUTHORITY_UNREADABLE`／`ALLOCATION_IO_FAILED`；`ALLOCATION_IO_FAILED` 經邊緣觸發＋退避重試成功，而且沒有第二個宇宙。
  - orphan storage → `AUTHORITY_CORRUPT`；catalog dirty 時等待、不進 SEALED。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode M5GameTest`、`-Mode M3`、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `feat: resolve collapse destinations before preparing the passage`

---

### Task F4b: PREPARE（預載、投影、量子膜套用、保護、載入辨識）

**Files:**
- Create: `src/main/java/dev/quantumchamber/collapse/ProjectionWriter.java`、`ProjectionProtectionIndex.java`
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseService.java`（PREPARE、閘門、套用量子膜的條件、PREPARE 期間的門規則、閘門失效退回 WAITING）
- Modify: `src/main/java/dev/quantumchamber/chamber/QuantumBulkheadBlock.java`、`ChamberControllerBlock.java`、`WorldChamberDoorAdapter.java`（門攔截排在 `ChamberDoorService` 之前）
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberProtectionService.java`（非 vanilla world key 查索引）
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberPowerCoordinator.java`（只限 `refreshNow` `:54-58` 的載入辨識分流：索引中的 PROJECTION 為 inert）
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`（決策 2：索引早於 bootstrap，註冊在 `CollapseAuthorities.registerEvents()` 之後）
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseAuthorities.java`（2026-10-02 F2b review 後加入：只限新增唯讀 API——列舉已確認的 projection entries，以及權威健康查詢。健康只看初始化結果與執行期間的 `lost`，不看單次呼叫回的 `CORRUPT`；後者也涵蓋單筆不符與 ledger 已滿）
- 條件式：若改到 `ChamberControllerLoadSyncQueue`，依 spec §17 補 queue observer 的 regression，並先回報 controller
- Test: 新增 `src/test/java/dev/quantumchamber/collapse/ProjectionProtectionIndexTest.java`；GameTest 加在 `M5UniverseGameTests.java`
- Test（fault 注入）：`src/testmod/java/dev/quantumchamber/gametest/mixin/` 新增投影寫入或 checkpoint 失敗的 mixin，登記在 `quantumchamber-test.mixins.json`

**範圍：** spec §7.3 的 PREPARE 部分（閘門、套用量子膜的條件、門口規則、PREPARE 期間的開關門與非參與者規則、閘門失效）、§9（全部，含 §6.5 的實體交叉檢查），使用 H2 的 `ChunkSaveCheckpoint` 與決策 3 的寫入方式。

**拆分（controller 2026-10-06）：** 本 task 的 RED 有 13 組，production 檔案有 9 個；F4a 的 implementer 光是一輪修正就用掉約 74 萬 token 的 context。所以拆成兩個子 task，各自走完 implementer → spec review → quality review，兩個都完成才算 F4b 完成。檔案清單與 Step 1 的 RED 不變，分配如下：

- **F4b-1：投影元件。**
  - Step 0：F4a 最終 review 留下的 Minor，清單見 note F4a「交給後續」的 F4b Step 0。只為這幾點，`DestinationResolver.java` 也列入允許路徑。
  - 新增 `ProjectionWriter`（決策 3 的寫入方式；第一次寫入與修復兩種模式；逐格驗證；玩家 bbox 擋住時不寫入，非玩家 entity 照寫）。
  - 新增 `ProjectionProtectionIndex`：開服時由 ledger 與 catalog 建立，註冊順序依決策 2；提供 COMPLETE 時的更新入口，以及退化規則。
  - `CollapseAuthorities` 的唯讀列舉與健康 API。
  - `ChamberProtectionService` 對非 vanilla world key 查索引：破壞、放置、爆炸、活塞、流體。
  - `ChamberPowerCoordinator.refreshNow` 的 inert PROJECTION 載入辨識，涵蓋 chunk 載入與 `onBlockAdded` 兩條路徑。
  - Step 1 中屬於這些元件的 RED：
    - 第 2 組的「清 interior、箱子不噴物品、逐格驗證、修復只動不變量 cell、保留 interior」；
    - 第 5 組的「退化只依權威健康」；
    - 第 6 組的玩家與動物；
    - 第 8 組全部。
  - 不改 `CollapseService` 的流程：Step 0 的 CS 項目除外，PREPARE stub 照舊。
- **F4b-2：PREPARE 流程（目的宇宙這一側）。**（controller 2026-10-06 在 F4b-1 完成後再拆：原本的 F4b-2 同時牽涉目的宇宙與原艙門兩側，RED 有 10 組，另有 6 項交接，所以把原艙門的部分分給 F4b-3。）
  - Step 0：F4b-1 quality review 留下的 Minor，清單見 note F4b-1「交給後續」的「F4b-2 Step 0」。
  - `CollapseService` 的 PREPARE：
    - §9.1 預載與 600 ticks WARN；
    - PENDING→寫入→checkpoint→COMPLETE→更新索引；
    - §6.5 實體交叉檢查與既有 entry 的位置驗證；
    - §9.2 衝突；
    - 退化時新的 PREPARE 進 SEALED。
  - 準備完成之後，在 F5 之前停住（READY stub，比照 F4a 的 PREPARE stub）。
  - 閘門失效時釋放目的世界的 ticket、退回 WAITING。F3d m-6(b)：轉換點改成回傳明確的轉換結果。
  - 投影寫入與 checkpoint 的 fault mixin。
  - 本子 task 不套用量子膜：門維持一般開門，這符合 §7.3「套用量子膜的條件不成立時，門維持一般開門」。PREPARE 期間的開關門照舊交給 `ChamberDoorService`，門攔截由 F4b-3 加上。
  - Step 1 中目的宇宙側的 RED：
    - 第 1 組：預載；
    - 第 2 組：checkpoint 之後才 COMPLETE，以及刪除 ledger 之後不重寫；
    - 第 3、4 組；
    - 第 5 組中「退化時進 SEALED」的流程；
    - 第 6 組的流程：玩家擋住時下一個 tick 重試，併入 600 ticks WARN；
    - 第 7 組；
    - 第 9 組；
    - 第 13 組的 ticket 與 WAITING。
  - 交接：F4a-0 的 `leaseReady` 就緒條件；F4a Q2-m6（G-NEW 的 witness）；F4b-1 的「驗證 → checkpoint → COMPLETE」未保護窗口。
- **F4b-3：PREPARE 期間的原艙門。**
  - Step 0：F4b-1 quality Q-m1（`chamber` 與 `collapse` 互相依賴），見 note F4b-1「交給後續」。
  - 量子膜的套用條件與門口規則（§7.3）。
  - 門的攔截：`QuantumBulkheadBlock`、`ChamberControllerBlock`、`WorldChamberDoorAdapter`，排在 `ChamberDoorService` 之前。行為包括：
    - 參與者開關門會終局；
    - 艙內的非參與者可以把門改成一般開門；
    - 艙外的非參與者被拒；
    - roll-forward 待確認期間拒絕開關門。
  - 閘門失效時，門由 sealed 改為一般開門。
  - §7.3 門狀態的重新套用，含 SEALED 的關門（F4a M-4）。
  - Step 1 中原艙門側的 RED：第 10、11、12 組，以及第 13 組的門。
  - 交接：
    - F3b 的掛件與強充能評估；
    - F3d m-7(b)：確認 §10.6 :613 的正規化（F5 實作）涵蓋「先終局、再改門」之間的 crash 窗口。
- F4b-1、F4b-2、F4b-3 都完成，才算 F4b 完成。

- [ ] **Step 1: RED。**
  - 預載不用阻塞式 `getChunk(create=true)`。
  - 第一次寫入清 interior、箱子不噴物品、逐格驗證、checkpoint 之後才 COMPLETE；之後的寫入只修不變量 cell、保留 interior；COMPLETE 之後刪除 projection ledger（schema 4）→ 不重寫 interior。
  - 實體交叉檢查：ledger 沒有 entry、目的地已有一座艙 → `PROJECTION_CONFLICT`；有 PENDING 時照第一次寫入。
  - 既有 entry 的位置驗證（spec §6「既有紀錄的驗證」）：`CollapseAuthorities.projection(UUID, UniverseId)` 不帶來源艙位置、不驗證 Role／ControllerPos／OutwardFacing。在決定首次寫入或修復之前，一律先 `writeProjection(PENDING)` 取得 site 驗證（回 `ALREADY_COMPLETE` 並帶回 COMPLETE entry 時改走修復路徑；新 key 直接寫 COMPLETE 是 caller 錯誤，會丟例外），或以帶預期位置的查詢比對；不符 → `AUTHORITY_CORRUPT`。不得直接拿 `FOUND COMPLETE` 跳過驗證。
  - §9.3 退化規則只依權威健康（初始化結果、執行期間 `lost`）切換；projection ledger 已滿或單筆不符不得讓全部投影進入退化模式。
  - 要寫入的 cell 有玩家時不寫入；有生成的動物時照常寫入、不移除 entity。
  - 投影衝突 → `PROJECTION_CONFLICT`；注入寫入失敗 → `PROJECTION_FAILED` → 邊緣觸發＋退避重試成功。
  - 保護（破壞、放置、爆炸、活塞、流體）；退化規則；Controller 載入辨識為 inert PROJECTION（比較器 0、不 `registerOrigin`、不重複 log），涵蓋 chunk 載入與投影寫入時 `ChamberControllerBlock.onBlockAdded`（`:98-101`）觸發 refresh 兩條路徑。
  - 投影方塊只在 PENDING 確認之後寫：注入 PENDING 的落盤失敗 → 不寫任何方塊。
  - 門狀態的重新套用（spec §7.3）：sealed 量子膜期間讓來源 chunk 卸載再重載 → 下一個 tick 依 collapse view 重新套用，不在 chunk 載入事件當下寫方塊。
  - sealed 量子膜只在門口沒有 entity、艙內沒有非參與者時套用；sealed 期間被指令傳進艙內的非參與者可以把門改為一般開門，塌縮不結束；艙外的非參與者被拒。
  - PREPARE 一開始（預載未完成）參與者關門 → 終局，catalog 已多 1、discovery 有紀錄（Review Focus 3）；門口有 entity 時改為一般開門。
  - PREPARE 中斷電 → 釋放 ticket、門一般開門、退回 WAITING；恢復供電並由參與者觸發 → 回到 PREPARE，沒有第二個宇宙。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode M5GameTest`、`-Mode Windows`、`-Mode Main`＋oracle、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `feat: project the chamber into the destination universe`

---

### Task F5: 通道

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/TransferSafety.java`、`src/main/java/dev/quantumchamber/collapse/PassageTransferPolicy.java`、`PassageService.java`
- Modify: `src/main/java/dev/quantumchamber/universe/UniverseTransferService.java`（決策 4 的多載；既有方法行為不變，M3 測試檔不改）
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberState.java`、`ChamberStatusSignal.java`、`ChamberPowerCoordinator.java`（`PASSAGE`→15、R2 M5、沒有所屬 session 的 membrane 正規化）、`ChamberControllerBlock.java`（`:87` 的訊息 switch）。`Presence` 與 `presence()` 已在 F3a 完成。
- Modify: `src/main/java/dev/quantumchamber/collapse/CollapseService.java`、lang 檔
- Test: 新增 `src/test/java/dev/quantumchamber/collapse/PassageTransferPolicyTest.java`；擴充 `src/test/java/dev/quantumchamber/chamber/ChamberPowerCoordinatorTest.java`、`ChamberStatusSignalTest.java`；GameTest 加在 `M5UniverseGameTests.java`
- Test（fault 注入）：沿用 `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionTransferFaultMixin.java` 注入不可回滾的 native move（若需要修改，列入本 task），以及 journal SEALED 落盤失敗的注入

**範圍：** spec §10.2–§10.8。

**拆分（controller 2026-10-07，比照 F4b）：** 先處理 F4b-3 交下的必要 Step 0（`CollapseService` 約 1950 行），再接通道。共拆成三個子 task，各自走完 implementer → spec review → quality review；三個都完成，才算 F5 完成。檔案清單與 Step 1 的 RED 不變，分配如下：

- **F5-0：Step 0（行為不變的整理）。** 內容見 note F4b-3「交給後續」的 F5 Step 0：
  - 把原艙門的程式抽成 package-private 類別（例如 `OriginDoors`）；
  - 決定 PREPARE 要不要抽出（F4b-2 的交接）；
  - 處理 F4b-3 最終 review 留下的 Minor 與 Nit。

  允許的路徑是 `collapse/` 的新類別、`CollapseService.java`、CST，以及 testmod 的 M5 檔、新的 testmod fault mixin 與 README。不得改變任何行為，只有那幾項 Minor 例外。
- **F5-1：通道本體。** 範圍是 §10.2–§10.6 與 §10.8：
  - READY 的前置條件與提交，含 EXISTING 再驗，以及 F4b-2 的 S-M2：修復之後的 checkpoint；
  - 門改為 passable；
  - §10.3 的跨越判定；
  - §10.4 的 `TransferSafety`、`PassageTransferPolicy`，以及 `UniverseTransferService` 的多載（決策 4）。F4b-2 的 S-M1：預載與 FULL 檢查的 chunk 推導要一致；
  - `Crossed`，以及兩種傳送失敗（`FAILED_ROLLED_BACK`、`FAILED_RECOVERY_REQUIRED`→SEALED(TRR)）；
  - §10.5 通道期間的開關門，接在 `doorAction`；
  - §10.6 的結束條件 (a)–(d) 與結束的順序，含 `releaseDestination`；
  - §10.8 的訊息。

  Step 1 中屬於它的 RED：
  - 跨越幾何、policy 的決策表、`FAILED_ROLLED_BACK`；
  - TRR 與 SEALED 落盤失敗（Review Focus 1）；
  - `Crossed` 落盤失敗；
  - 結束條件、兩人一人跨過、同艙可開新 session、EXISTING 的基本重逢、艙內有非參與者時仍能提交 READY。
- **F5-2：比較器與 membrane 正規化。** 範圍是 §10.7 與 §10.6 :613：
  - `ChamberState.PASSAGE`→15、`ChamberStatusSignal`、R2 M5、`ChamberControllerBlock` 的訊息 switch；
  - 找不到所屬 session 的 membrane 正規化，含 F4b-3 spec m-2：SEALED 帶著量子膜的情況；
  - F4a M-3：SEALED 前後的比較器數值不同時，鎖住 Controller 的刷新。

  Step 1 中屬於它的 RED：`presence()` 與 `reconcile` 的那一組、Review Focus 5（沒有 runtime 的 DORMANT 續行到 READY、比較器 15）、membrane 正規化。

- [ ] **Step 1: RED。**
  - `presence()` 與 `reconcile`：`PASSAGE`＋供電＋enabled → 15；`COLLAPSE`／`PASSAGE` 不呼叫 `returnToOrigin`；registry 已是 RETURNING 時仍能到 15；斷電時 power state 為 RETURNING、輸出 11；終局後恢復；R2 M5。
  - 沒有 runtime 的 DORMANT 續行到 READY、比較器 15，撐過強制 refresh 與 membrane 的 neighbor update；WAITING 期間斷電再恢復後，READY 不被結束（Review Focus 5）。
  - 跨越幾何（四個朝向、方向、騎乘、非參與者、已跨過）；policy 決策表（跳躍、水、岩漿、石頭）；`FAILED_ROLLED_BACK`。
  - `FAILED_RECOVERY_REQUIRED` → SEALED(TRR)＋`RecoveryRequired`；再注入 SEALED 落盤失敗 → 通道立即關閉、下一次其他 session 的 flush 寫出 SEALED（Review Focus 1）。
  - `Crossed` 落盤失敗 → 其他參與者不被傳送、通道不終局、結束條件不評估、參與者關門被拒且門不變；收斂之後恢復，若已到期則依 (c) 結束（spec §7）。
  - 結束條件 (a)–(d)（INFO）；兩人一人跨過、到期後門一般開門、同艙可開新 session；EXISTING 一路到 READY，另一座艙的 session 也到達同一個宇宙（基本重逢）；艙內有非參與者時仍能提交 READY。
  - 沒有所屬 session 的 membrane 在下一 tick 正規化。
- [ ] **Step 2: GREEN。**
- [ ] **Step 3:** 完整回歸 `-Mode Full`、`-Mode M5GameTest`、`-Mode M3`、`-Mode Main`＋oracle、`m4-recovery-probe.ps1`。
- [ ] **Step 4: Commit** `feat: open the one-way passage and transfer crossing participants`

---

### Task F6: 重啟矩陣、跨 JVM probe 與 harness

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M5CollapseRecoveryProbe.java`
- Modify: `build.gradle`（`m5Collapse` run，比照 `m4Recovery` 的 nonce 與屬性）、`src/testmod/resources/fabric.mod.json`
- Create: `scripts/verification/m5-collapse-probe.ps1`
- Modify: `scripts/verification/run-all.ps1`、`scripts/verification/README.md`
- Modify: `.github/workflows/build.yml`（新增的 `_windows` test 加入必要清單）
- 必要時 Modify: `CollapseService.java`、`SessionRecoveryManager.java`（只限 §12 矩陣需要的修正，先回報 controller）

**範圍：** spec §12（矩陣與 W1–W12）、§11.2 的重啟後重試（`MATERIALIZE_FAILED`、`AUTHORITY_UNREADABLE`）、§15 Cross-JVM、CI 清單。另加 phase：`AUTHORITY_UNREADABLE`（注入 IO）→ SEALED → 重啟 → 重試成功（spec §15）；sealed 量子膜期間 crash → 重啟後停在 WAITING，門是一般開門，沒有留下有碰撞的膜（spec §7.3）。

- [ ] **Step 1: RED。** 每個 W 窗口一個 phase；先以 mutation 證明 probe 能偵測失敗（例如故意讓 W2 重新推導不同的 UniverseId）。
- [ ] **Step 2: GREEN。** 每個 phase 證明：同一個 UniverseId、catalog 最多多 1 筆、沒有重抽、沒有人被困。
- [ ] **Step 3:** `run-all.ps1` 全綠（含新 probe 與 summary）；`ci-rule-check.ps1` 兩個 step PASS。
- [ ] **Step 4: Commit** `test: add the M5 cross-JVM collapse probe to the gates`

---

### Task F7: 文件

**Files:**
- Modify: `docs/implementation-notes/2026-10-01-m5-collapse-passage.md`（收尾）
- Create: `docs/implementation-notes/compatibility.md`（設計 §32.3：F1b 實測的 render stack）
- Create: `docs/manual-acceptance/2026-10-01-m5-manual-smoke.md`（spec §18：白話，項目、做法、預期寫在一起）
- Modify: `README.md`、玩家說明（README 內的玩法段落）、`docs/quantum_superposition_chamber_design.md` 的狀態行、`AGENTS.md`「目前接手入口」
- Modify: `docs/implementation-notes/2026-09-21-m4-candidate-doors.md`「已知 deferred 項目」與 `docs/implementation-notes/2026-09-29-m1-m4-integration-review.md`「延後清單」：把 M5 已處理的項目標為已處理（附 commit）

**範圍：** spec §16 F7、§18、§19。必寫：schema 4 不可降版（§5）、§2 的已知限制寫進玩家說明、`ALREADY_SELECTED` 不承諾「候選已鎖定。」。不得寫「已合併」「已 push」或「人工驗收已通過」，除非已實際發生。

- [ ] 文件 review（獨立 reviewer）Critical／Important 為 0。
- [ ] Commit `docs: record the M5 collapse and passage implementation`

---

## Milestone 收尾（依 AGENTS.md）

1. automated gates：`run-all.ps1` 全綠（含 M5 GameTest run 與 M5 probe）。
2. required manual smoke（spec §18）：由 controller 代操作，回報使用者時用白話；結果寫入 F7 的清單（tracked）。
3. task＋whole-branch review：Critical／Important 為 0。
4. docs truth。
5. push `feature/m5-collapse`。
6. GitHub Actions 雙平台通過。
7. `git merge --ff-only` 合併 `main`，annotated tag `m5`（使用者 2026-10-01 開工指示指定的名稱）。
8. 外部備份（依 worktree 退役 memory），再刪除 worktree。

## Completion Evidence

- 每個 task 的 RED／GREEN／focused／完整回歸 root 與 commit range 記在 SDD ledger 與 implementation note。
- spec §19 的完成判準全部成立。
