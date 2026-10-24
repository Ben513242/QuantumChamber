# M6 宇宙生成多樣性與完整維度家族 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每個 task 的 implementer 與獨立 spec／quality reviewers 使用不同 context。

**Goal:** 在保留 M4／M5 既有收據與世界語意下，依序完成驗證腳本安全、三種原版結構生成競態防護、持久隨機與同種子生成、完整 Overworld／Nether／End 家族及其路由與重生。

**Architecture:** frozen candidate intent → checked allocation/catalog → exact runtime world identity。新 `SettingsSnapshot`、profile registry 與每 U root seed 是新生成的持久來源；legacy schema1 保持舊路徑，family 階段才依 provenance 做 checked 遷移。原版 singleton world 假設只在 exact custom family 邊界攔截。

**Tech Stack:** Minecraft 1.21、Yarn `1.21+build.9`、Fabric Loader `0.17.2`、Fabric API `0.102.0+1.21`、Loom `1.7.4`、Java 21、Gradle 8.8、PowerShell 7；JUnit／Fabric GameTest／跨 JVM probe。

**Spec:** `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md`（本計畫依已獨立審查的 v1.0 契約；目前文件 SHA256 `C776E2BA4A12F61A727D0C956B7522D7709C51975712C993E45CE498E3EF26BC`）。

## Global Constraints

- 唯一工作樹 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation`；不在 main 實作。不得 reset／clean／覆寫 dirty 檔、`git add -A`、建 PR／LICENSE。正常 push 已授權；main ff、tag、退役與人工代操作另守門檻。
- 不改 Minecraft/Fabric/Java 版本，不併 M7、renderer、Catalog Origin 啟動、AccessDenied 短暫重試或原版 playerdata IOException 行為。
- 保留 legacy UUID／key／seed／地形、M4 frozen receipt、M5 schema4、同 XYZ passage、inert projection；②③仍有 M5 原版 portal／死亡非正規回程，④完成才宣稱 no-leak。
- `run/`、ignored SDD root 與使用者世界不得刪除。fresh world 僅可核 canonical/non-reparse/Java owner/session.lock 後可恢復 move，逐檔 hash 還原；失敗 root 永留，修正用新 nonce。
- 正式 harness／probe／oracle 在 tracked `scripts/verification/`；任何時間僅一個 Gradle／gate／probe。會 move 世界的 runner 不強制中斷；`run-all.ps1` 以 `END ALL exit=0 HEAD=<sha>` 判完成。
- testmod 僅 `src/testmod`／測試 runtime；release／sources 無 testmod/gametest/fault mixin。main-only 以 Fabric mod list、classpath／argfiles／DLI、class-load 三層核，不能拿 GameTest 冒充。
- 每個 code／test／harness 行為 task 先具名 RED，受影響 code 編譯與 log 後逐檔 stage 成 candidate commit；在**同一乾淨 code HEAD** 跑兩次 focused GREEN 與該 task 的完整 `run-all.ps1`，其後全新 spec compliance 與 code quality reviewer（C/I=0）才進下一 task。fix round 重做 RED、兩次 GREEN、full、review；review／結果文件另 commit，不宣稱文件 SHA 自己跑過 runtime。只改 docs 時驗 diff／`git diff --check`。
- 交接 note 記每輪 code SHA、命令、兩個 GREEN root、full root、case 集合、失敗原 root、review、commit range。完整 suite 比對 SUMMARY、XML `classname+name`、JUnit 集合、WARN／ERROR、pin、worldgen 例外、release／sources 與 main-only。M5 基準 6b116e6／CI f99ce66 只作回歸對照，不是 M6 通過證據。

## Review Focus

| 高風險輸入／行為 | 擁有 task 與具名 test |
| --- | --- |
| 同一 `AllocationToken` 的 catalog atomic move 已落地但讀回失敗 | Task 10 `m6CatalogReadbackAfterMoveKeepsSameSeedAndUuid`，cross-JVM `catalog-readback` |
| frozen seed 不變但 migrated legacy Overworld 的 normal／amplified 設定被外改 | Task 15 `migratedLegacyRejectsChangedLiveGenerator`，雙向 cross-JVM `legacy-preset-change` |
| provider 同 id/version 輸出變更或已卸載 | Task 8 `snapshotRejectsChangedProvider`、Task 9 `journalRejectsMissingProvider` |
| 多世界 End dragon/gateway state 共用或覆寫 canonical | Task 16 `endStateIsPerUniverse`、Task 19 `gatewayKeepsOwnEnd` |
| custom Nether／End 玩家重啟登入或死亡退回 canonical | Task 17 `loginMaterializesExactRoleBeforeLookup`、Task 20 `invalidAnchorFallsBackWithinUniverse` |

## File map 與固定介面

| 責任 | 檔案（新增標「新建」） | 跨 task 介面 |
| --- | --- | --- |
| scripts 安全／focused gate | `scripts/verification/probe-common.ps1`, `gates.ps1`, `run-all.ps1`, `README.md`; `m6-harness-safety-check.ps1`、`m6-focused.ps1`、`m6-universe-probe.ps1`（新建） | `m6-focused.ps1 -Suite JUnit -TestClass <fqcn> -Label <nonce>`、`-Suite GameTest -Batch <batch>`、`-Suite Probe -Chain <name>`；各回傳 receipt 與 root。 |
| 三類 race | `src/main/java/dev/quantumchamber/mixin/` 的三個專用 mixin（新建），`src/main/resources/quantumchamber.mixins.json`; `src/test/java/dev/quantumchamber/universe/` 三個 race test、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`（新建） | 每類只隔離該結構生成 invocation 的 mutable state，`require=1`；原版 `/place`／worker 路徑都受同一 guard。 |
| snapshot／policy | `src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`、`UniverseProfileProvider.java`（新建）；`candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`NewUniverseIntent.java`、`persistence/CandidateJournalCodec.java` | `UniverseProfileRegistry.register(provider)`、`freeze()`、`resolve(GenerationSettingsSnapshot)`；`GenerationSettingsSnapshot` 包含 source/target 各自 frozen provider id/version、typed parameters、fingerprint。 |
| catalog／runtime seed | `collapse/UniverseAllocationLedger.java`、`CollapseAuthorities.java`、`DestinationResolver.java`; `universe/UniverseRecord.java`、`UniverseDefinition.java`、`UniverseRegistryState.java`、`UniverseRegistry.java`、`UniverseWorldDescriptor.java`; `universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`; `mixin/ServerWorldUniverseSeedMixin.java`（新建） | `catalogRecordMatches(intent, record, availability)` 依版本／provenance；`rootSeed(server, worldKey, descriptor)` 只對 exact schema2 world 生效，無匹配時原版。 |
| family identity／owner | `universe/UniverseKeys.java`、`UniverseRoleResolver.java`、`UniverseRuntimeRegistry.java`、`UniverseLifecycleService.java`、`UniverseTransferService.java`; `universe/DimensionFamilyRoutingService.java`、`RespawnRoutingService.java`（新建）與 `mixin/` 窄 portal／respawn／End state hooks（新建） | `(UniverseId,DimensionRole)↔worldKey↔live ServerWorld` exact 雙向驗；`resolveTarget(sourceWorld, targetRole)` 拒絕任何 canonical fallback。 |
| GameTest／recovery | `src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`（新建），testmod-only mixin；`build.gradle` 的 `runGameTestM6`、`.github/workflows/build.yml` | batch `m6_universe*` 專屬 fresh `run/gametest-m6`；probe chain 每 phase 新 JVM、原 root 不覆寫。 |

以上是擁有權地圖，不授權任一 task 順手修改全部列出的檔。task 派工逐項列本 task 允許路徑與 exact HEAD。原版 mixin 注入點在 native gate 證明前不可預寫成已可注入；下列功能介面及斷言固定，具體 descriptor/ordinal 由對應 native task 記錄後交 implementer。

## 驗證命令與 code 提交流程

第一個 focused runner task 完成後，JUnit 統一用 `pwsh -NoProfile -File scripts/verification/m6-focused.ps1 -Suite JUnit -TestClass <fqcn> -Label <nonce>`；GameTest 用 `-Suite GameTest -Batch <batch>`；跨 JVM 用 `-Suite Probe -Chain <chain>`。RED 預期**具名斷言失敗**，不是 class 未定義／編譯失敗。每次不同 `<nonce>`，保留 failure root。每個 task 的 code commit 前用 `pwsh -NoProfile -File scripts/verification/gates.ps1 -Mode M6Compile -Label <task>-compile` 產生 compile log（Task 3 加入此 mode；僅編譯受影響 source/test，不以 compile 代替 GREEN）。commit 後確認 `git status --porcelain` 空且 HEAD 精確；兩個 focused GREEN 使用不同 label，但同 HEAD。完整回歸固定 `pwsh -NoProfile -File scripts/verification/run-all.ps1`；未到 Task 12 的 M6 runtime probe 尚未納入 full 時，run-all 必包含當時已有 M6 gates，不空宣全 M6 完成。每次 full 記 `END ALL`、scope JSON、XML 集合與 artifact oracle；必要 mutation adapter 在 `finally` 還原並用 `git hash-object` 比對原 blob。可重現的 gate 更新後才採計新 mode，嚴禁直接 `gradle test`。

### Task 1：`New-OwnedRoot` 的 `run/` 祖先守門

**Files:** Modify `scripts/verification/probe-common.ps1:17-24`；Create `scripts/verification/m6-harness-safety-check.ps1`；Modify `scripts/verification/README.md`。
**Interfaces:** consumes `Resolve-Evidence(Context,Path)`／`New-OwnedRoot(Context,Suffix)`；produces `Assert-OwnedRunAncestor(Context)`，建 root 前 canonical／non-reparse，拒絕不屬 repo 的 run ancestor。
- [ ] 寫 `m6-harness-safety-check.ps1 -Case owned-root`：受控 reparse `run/`／非 canonical ancestor 的 RED 必以 `OWNED_ROOT_ANCESTOR_UNSAFE` 拒絕且不在外部建檔；正常實體 `run/` PASS。保留 fixture，不碰使用者 run。
- [ ] 執行 `pwsh -NoProfile -File scripts/verification/m6-harness-safety-check.ps1 -Case owned-root -Label h1-red`，原版必在具名 unsafe 斷言 FAIL；修窄 guard、同檔 `h1-green-a/b` PASS。
- [ ] 依全域 code 提交流程完成此 task 的必要 compile（PS parser／source guard）、乾淨 HEAD 兩 GREEN、full、fresh spec＋quality review；結果文件另 commit。

### Task 2：`Move-Fresh` 建目錄失敗的 exact 復原

**Files:** Modify `scripts/verification/gates.ps1:80-102`；Modify `scripts/verification/m6-harness-safety-check.ps1`、`README.md`。
**Interfaces:** consumes `Move-Fresh(root,tag)`／`Restore-Root(swap)`；produces catch/finally 內即使 New-Item 失敗仍以已知 `swap` 復原，原例外保留，原世界逐檔 hash 相同。
- [ ] `-Case move-fresh` 注入「move 成功、新 fresh root `New-Item` 失敗」：RED `MOVE_FRESH_RESTORE_MISSING`，檢查 backup 存在且原錯誤可追；另測 restore 自身失敗兩個原因皆保留。
- [ ] 跑 `m6-harness-safety-check.ps1 -Case move-fresh -Label h2-red`；修後 `h2-green-a/b` PASS，原世界 hash/lock 正常，所有失敗 root 保留。
- [ ] 逐檔 commit、同 HEAD 兩 GREEN、full、fresh spec＋quality review；不與 Task 1 或 generation commit 合併。

### Task 3：M6 定向 gate 與結果 oracle

**Files:** Create `scripts/verification/m6-focused.ps1`；Modify `scripts/verification/gates.ps1`、`run-all.ps1`、`README.md`、`build.gradle`、`.github/workflows/build.yml`；Create `src/testmod/java/dev/quantumchamber/gametest/M6GateCanaryGameTests.java`。
**Interfaces:** `gates.ps1` 新增 `-Mode M6Compile|M6JUnit|M6GameTest`、`-TestClass <fqcn>`（JUnit）、`-Batch <batch>`（GameTest），由 `m6-focused.ps1 -Suite JUnit|GameTest|Probe` 封裝；`runGameTestM6` 是獨立 fresh runtime。full/CI 比對 `m6_universe*` 的 `classname+name` 與 JAR/main-only 隔離。
- [ ] RED 用可通過的 canary 與錯誤 XML／testmod 泄漏 fixture 證明 gate 會拒絕 `M6_SCOPE_MISMATCH`／`M6_TESTMOD_LEAK`，且舊 M5/default/legacy scopes 不變。`pwsh -NoProfile -File scripts/verification/m6-focused.ps1 -Suite GameTest -Batch m6_universe_gate -Label g0-red` 需具名 oracle FAIL。
- [ ] 加 fresh `run/gametest-m6`、新 Gradle run task 與 scope；`g0-green-a/b` 的 canary PASS，negative fixture 仍 FAIL，`run-all` 新 summary 與 CI 對同 SHA 比對，release/sources/main-only oracle 照舊。
- [ ] 完成 compile、code commit、兩次 clean HEAD focused、full、fresh spec＋quality review；之後所有 task 只能經此 tracked runner 跑 focused。

## 切片①：三類原版結構生成競態

三類的 RED 必包含兩世界同時 invocation、相同世界單序列對照及 server-thread `/place` 與 worker 交錯。既有唯讀 `native-races-inventory.md` 只指出候選的**整輪**邊界：`StrongholdStructure.addPieces`（內部 `init` 後擴張）、`NetherFortressStructure.addPieces`（`Start` 建構時共用 arrays 重設）、`EndCityGenerator.addPieces`（重設 Part 後遞迴）；不是只攔 reset/pick。先在各 task 的 ignored root 保存固定 1.21 JAR SHA、讀寫欄位表、呼叫鏈、barrier 時點與 thread dump；若 runtime 不重現 crash，以 deterministic 共享計數／state 污染 RED，不能寫「CME 已重現」。窄修以 invocation 私有狀態為先；ThreadLocal 須證遞迴與 finally 清理；鎖須證可重入、鎖階序與 `/place`／shutdown 不死鎖。H5/H6/H8 不當本輪 GREEN。

### Task 4：Stronghold invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/StrongholdGeneratorStateMixin.java`、`src/test/java/dev/quantumchamber/universe/StrongholdGenerationConcurrencyTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`scripts/verification/README.md`。
**Interfaces:** exact 原版 `StrongholdStructure.addPieces` invocation 內隔離 `possiblePieces`、`activePieceType`、`totalWeight`、`ALL_PIECES.generatedCount`；新 mixin 不改其他 structures。
- [ ] 讀 native `init/pickPiece/addPieces` 實際讀寫、`/place` caller，記可注入方法 descriptor 與再入性；JUnit `twoWorldStrongholdsDoNotSharePieceCounts` 的 barrier RED 要見 count/state 交叉，`singleWorldStrongholdLayoutUnchanged` 對照固定 seed。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.StrongholdGenerationConcurrencyTest -Label r1-red` 具名 RED；最小 mixin 後 `r1-green-a/b` PASS。另 `-Suite GameTest -Batch m6_universe_race_stronghold` 驗 `/place`／worker 無卡住。
- [ ] compile→code commit→兩個 clean HEAD JUnit GREEN＋GameTest→full→fresh spec／quality；若鎖／ThreadLocal 不能證安全，停在此 task 不進 Task 5。

### Task 5：Nether fortress invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/NetherFortressGeneratorStateMixin.java`、`src/test/java/dev/quantumchamber/universe/NetherFortressGenerationConcurrencyTest.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`、README。
**Interfaces:** 兩組 static pieces 的 `generatedCount` 只在一個 fortress invocation 內共享；不變更 Stronghold 或 canonical 單世界輸出。
- [ ] 先盤點 `ALL_BRIDGE_PIECES`／`ALL_CORRIDOR_PIECES` 的全部讀寫、遞迴與 `/place` 路徑；RED `twoWorldFortressesKeepIndependentCounts` 的兩世界 barrier 應顯示交叉，`singleWorldFortressLayoutUnchanged` 固定 seed。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.NetherFortressGenerationConcurrencyTest -Label r2-red` 具名 FAIL；窄修後 `r2-green-a/b`、`-Suite GameTest -Batch m6_universe_race_fortress` PASS。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；保留原 RED root。

### Task 6：End city invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/EndCityGeneratorStateMixin.java`、`src/test/java/dev/quantumchamber/universe/EndCityGenerationConcurrencyTest.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`、README。
**Interfaces:** `Part.shipGenerated` 每次 city invocation 獨立，不洩漏下一任務或另一世界。
- [ ] 盤點 `Part.shipGenerated` 寫入／重設、遞迴及 `/place`；RED `twoWorldEndCitiesKeepShipChoiceIndependent` 與 `singleWorldEndCityLayoutUnchanged` 分別證共享污染及原版輸出。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.EndCityGenerationConcurrencyTest -Label r3-red` 具名 FAIL；窄修後 `r3-green-a/b`、`-Suite GameTest -Batch m6_universe_race_endcity` PASS。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality，完成切片①的三類獨立驗收，不用 race 修補取代後續 family 測試。

## 切片②：持久 random seed

### Task 7：native seed/settings owner 的 RED 與介面釘選

**Files:** Create `src/testmod/java/dev/quantumchamber/gametest/M6NativeOwnerProbe.java`、`scripts/verification/m6-universe-probe.ps1`；Modify `scripts/verification/m6-focused.ps1`、`README.md`。此 task 不改 production。
**Interfaces:** produces probe `-Chain native-seed` 的 per-world `getSeed/noise/structure/biome/randomSequences` receipt，以及固定 1.21 的 exact mixin descriptor／constructor thread map，供 Task 11。
- [ ] 以 native-inventory JAR hash 為起點，補 `ServerWorld.getSeed()` 持續全域讀、constructor `StructureLocator`、`ServerChunkLoadingManager` noise/structure、biome source、`RandomSequences` 非 null 共用、End dragon 讀寫、`/place` worker；測試 `newWorldStillReadsSaveSeed`、`newWorldSharesCanonicalRandomSequences` 在 M5 code 上取得**預期現況 RED**，不是測試框架失敗。
- [ ] `m6-focused.ps1 -Suite Probe -Chain native-seed -Label g1-red` 保留原 root；probe 用新 owned world，startup/shutdown 與 restore 留 receipt；此 task 的 GREEN 是「探針可靠辨識舊行為、單世界控制組通過」，不是 random 功能已通過。
- [ ] compile→test/probe commit→兩次同 HEAD 探針驗證與 full→fresh spec／quality；將注入點、context owner、不得改全域 SaveProperties 的可執行決定寫入 tracked note 的結果 docs commit。

### Task 8：可持久化 `SettingsSnapshot` 與 provider API

**Files:** Create `src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`、`UniverseProfileProvider.java`、`src/test/java/dev/quantumchamber/universe/GenerationSettingsSnapshotTest.java`、`UniverseProfileRegistryTest.java`；Modify `scripts/verification/README.md`。
**Interfaces:** `register(ProfileProvider)`→拒重、`freeze()`→鎖定、`resolve(GenerationSettingsSnapshot)`→exact 三 role settings 或 fail；`GenerationSettingsSnapshot` 具有 v1 provider id/version、bounded typed Parameters、32-byte fingerprint、canonical bytes ≤512。
- [ ] RED `snapshotRejectsChangedProvider`（同 id/version 改 Overworld biome/structure closure）、`snapshotRejectsUnknownParameter`、`snapshotRejectsOversize512`、`registryRejectsLateOrDuplicateRegistration`；內建 normal/amplified provider 的 canonical closure 排序固定且 root seed 不入 fingerprint。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.GenerationSettingsSnapshotTest -Label g2-red` 具名斷言 FAIL；實作後 `g2-green-a/b` 加 RegistryTest PASS；未知／遺失 provider 拒絕，擴充 provider 不能偷進候選權重。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；native closure 若無法 canonical 化，停在此 task修訂方案，不以 id 字串冒充 fingerprint。

### Task 9：schema5 候選與 policy1，只開 random

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`NewUniverseIntent.java`、`src/main/java/dev/quantumchamber/persistence/CandidateJournalCodec.java`、`SessionRecoveryState.java`、`src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`、`scripts/verification/README.md`；Create `src/test/java/dev/quantumchamber/persistence/CandidateJournalCodecTest.java`。
**Interfaces:** `CandidatePolicySnapshot` M6 `quantumchamber:m6_profiles_v1`/candidate schema2/5-20-75/4096/family0；新 intent 持 source/target snapshot 與 profile1；journal schema5 strict mixed legacy/M6，不改 schema3/4 receipt。
- [ ] RED `policyOneOnlyFreezesRandom`（固定 material/token 的 domain hash→seed 不等來源）、`schemaFiveRoundTripsMixedLegacyIntent`、`journalRejectsMissingProvider`、`journalRejectsWrongSnapshotOrCapability`、`oldSchemaFourByteSemanticsUnchanged`。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.candidate.CandidateResolverTest -Label g3-red` 具名 FAIL；最小 codec/policy 實作後 `g3-green-a/b` 加 CodecTest PASS。正式 strict reader/NbtSizeTracker 16 MiB 讀回 4096 最壞行與兩個 512-byte snapshots，writer 拒絕 reader 不可讀的 candidate。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；policy2/3及family1都不能在此 task 啟用。

### Task 10：allocation2／catalog2 checked 原子權威

**Files:** Modify `collapse/UniverseAllocationLedger.java`、`CollapseAuthorities.java`、`universe/UniverseRecord.java`、`UniverseDefinition.java`、`UniverseRegistryState.java`、`UniverseRegistry.java`、`UniverseWorldDescriptor.java`；Modify `src/test/java/dev/quantumchamber/collapse/UniverseAllocationLedgerTest.java`、`CollapseAuthoritiesTest.java`、`src/test/java/dev/quantumchamber/universe/UniverseRegistryStateTest.java`；Modify testmod fault fixture、`m6-universe-probe.ps1`。
**Interfaces:** allocation row v2 保存 root seed＋target fingerprint；catalog record schema2 `NEW_M6` 三 role exact 4欄；舊 row/record 混合保留；`catalogRecordMatches(intent,record,availability)` exact；checked move/readback/roll-forward gate。
- [ ] RED `allocationTwoKeepsLegacyRowsExact`、`catalogRejectsWrongProvenanceRoleShape`、`m6CatalogReadbackAfterMoveKeepsSameSeedAndUuid`、`candidateWriterRejectsNbtSizeTrackerOverflow`、`orphanStorageNeverAdopted`；fault injection 不能改失敗 root。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.collapse.CollapseAuthoritiesTest -Label g4-red` 具名 FAIL；codec/authority 後 `g4-green-a/b`＋`-Suite Probe -Chain catalog-readback` PASS，第一次 v1→v2 envelope 與重啟多窗口分開驗。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；未經 catalog checked，不可呼叫物化。

### Task 11：new U 的長期 root seed 與原版生成設定 owner

**Files:** Modify `universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`、`universe/UniverseLifecycleService.java`；Create `src/main/java/dev/quantumchamber/mixin/ServerWorldUniverseSeedMixin.java`；Modify mixins JSON；Create `src/test/java/dev/quantumchamber/universe/UniverseRootSeedOwnerTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`；Modify `m6-universe-probe.ps1`。
**Interfaces:** `rootSeed(server, worldKey, descriptor)` 只在 exact schema2 owner 且 live instance 匹配時回 frozen seed；`ServerWorld.getSeed` 的建構期與之後都一致；無 match 完全原版。factory 取 provider resolved options，不修改全域 save properties/registry；新 U randomSequences 用自己的 persistent state。
- [ ] RED `newUniverseGetSeedChangesAfterConstruction`（現行全域 seed）、`noiseStructureBiomeUseFrozenRoot`、`twoUniversesDoNotShareRandomSequences`、`legacySchemaOneStillUsesSaveSeed`；以 Task 7 native descriptor 實證構造器直接讀與 worker 建 noise 的窗口。
- [ ] `m6-focused.ps1 -Suite Probe -Chain native-seed -Label g5-red` 具名 FAIL；窄 owner/mixin 後 `g5-green-a/b`、GameTest `m6_universe_seed_owner` PASS。另 probe 同 server 兩 U 並行、canonical 無 context、constructor 失敗 `finally` 清理及重啟後 seed 相同。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；任何原生 seed 消費者仍讀全域時切片②不得結案。

### Task 12：random NEW 的 collapse／restart 端到端

**Files:** Modify `collapse/DestinationResolver.java`、`CollapseAuthorities.java`、`CollapseService.java`；Modify `src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6UniverseRecoveryProbe.java`（新建）、`scripts/verification/m6-universe-probe.ps1`、`run-all.ps1`、`README.md`；Modify `.github/workflows/build.yml`。
**Interfaces:** frozen policy1 intent→checked COLLAPSING→allocation2→catalog2→world，依同 token 重試；probe chains `intent-allocation`、`catalog-readback`、`partial-overworld`。
- [ ] RED `randomNewUsesFrozenSeedAcrossRestart`、`existingLegacyNewNeverBecomesRandom`、`catalogBeforeWorldStorage`、`unknownProviderSealsWithoutReplacement`；cross-JVM crash 各窗口比較同 UUID/seed/profile/storage，playerdata limitation 另保留。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_random -Label g6-red` 具名 FAIL；最小接線後 `g6-green-a/b` 加 `-Suite Probe -Chain intent-allocation` 等三鏈 PASS。`run-all` 與 CI 開始強制 M6 probe／scope／artifact 正確，②只驗 seed/profile，不要求 portal/respawn no-leak。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality，完成切片②自動驗收；人工清單最後統一執行。

## 切片③：same-seed fresh、mutation、registry

### Task 13：policy2 的 same-seed fresh

**Files:** Modify `candidate/CandidateResolver.java`、`NewUniverseIntent.java`、`universe/UniverseProfileRegistry.java`；Modify `src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`m6-universe-probe.ps1`。
**Interfaces:** new Context policy2 固定 random/same 各一半，same 複製 source root seed 與**來源**snapshot，新的 UUID/空 storage；舊 policy1 不升版。
- [ ] RED `sameSeedFreshCopiesSettingsNotBlocks`（原始相同坐標 terrain/biome/structure，玩家建築不在新 U）、`policyOneNeverRechoosesSame`、`unknownSourcePresetRejectsNewOnly`、`freshUniversesDivergeAfterEdits`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_same_seed -Label g7-red` 具名 FAIL；完成後 `g7-green-a/b`、`-Suite Probe -Chain same-seed-restart` PASS，來源 normal/amplified 分別驗。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；再開 policy3 前須可追溯 policy2 的獨立結果。

### Task 14：policy3 的 normal↔amplified mutation

**Files:** Modify `candidate/CandidateResolver.java`、`universe/UniverseProfileRegistry.java`、`universe/minecraft121/Minecraft121ServerWorldFactory.java`；Modify `src/test/java/dev/quantumchamber/universe/GenerationSettingsSnapshotTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`。
**Interfaces:** new Context policy3 固定三類等量，toggle 只翻轉 target Overworld preset，Nether/End snapshot 沿 source；原 policy1/2、既有 target fingerprint 不改。
- [ ] native gate 證 1.21 amplified preset 與 normal 都能獨立建 world。RED `toggleChangesOverworldNotNetherEnd`、`sameSeedAmplifiedTerrainDiffersReproducibly`、`oldPolicySnapshotsRemainFrozen`、`providerChangedOutputFailsClosed`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_profile -Label g8-red` 具名 FAIL；窄改後 `g8-green-a/b` 與 `-Suite Probe -Chain profile-restart` PASS，不以重抽修錯。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；完成切片③自動驗收，②③ portal／死亡限制仍明列。

## 切片④：完整 family、routing 與同 role 艙

### Task 15：family catalog、legacy migration 與 exact role identity

**Files:** Modify `universe/UniverseKeys.java`、`UniverseDefinition.java`、`UniverseRecord.java`、`UniverseRegistry.java`、`UniverseRegistryState.java`、`UniverseRoleResolver.java`、`collapse/CollapseAuthorities.java`；Modify `src/test/java/dev/quantumchamber/universe/UniverseRegistryTest.java`、`UniverseDefinitionTest.java`、`src/test/java/dev/quantumchamber/collapse/CollapseAuthoritiesTest.java`；Modify `m6-universe-probe.ps1`。
**Interfaces:** `MIGRATED_SCHEMA1` 的舊 Overworld 五欄／SHARED_SAVE_SEED、原 descriptor digest 不變；新增兩 role 四欄／EXPLICIT_ROOT_SEED，`TargetSettingsSnapshot` checked；`validateMigratedLegacyOverworld(server,record,liveWorld)` 比對實際 canonical/live generator closure。
- [ ] RED `migratedLegacyRejectsChangedLiveGenerator`、`legacyNormalAndAmplifiedMigrateExact`、`unknownLegacyPresetOnlyBlocksFamilyMigration`、`oldReceiptAcceptsOnlyDigestEquivalentMigratedRecord`；cross-JVM `legacy-preset-change` 於 seed 相同但 preset normal→amplified 與反向都拒絕，未遷移 schema1 照 M5。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.UniverseRegistryTest -Label f1-red` 具名 FAIL；實作後 `f1-green-a/b`、`-Suite Probe -Chain legacy-preset-change` PASS，已 live U 不重建/重綁/覆寫，canonical Nether/End 無關變動不誤當 Overworld 改變。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；不能以 migration 改寫舊 chunk 或已取消 receipt。

### Task 16：三 role storage、randomSequences 與 End state owner

**Files:** Modify `universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`、`universe/UniverseLifecycleService.java`、`UniverseRuntimeRegistry.java`；Create `src/main/java/dev/quantumchamber/universe/UniverseEndStateStore.java`、`src/main/java/dev/quantumchamber/mixin/ServerWorldUniverseEndStateMixin.java`；Modify mixins JSON；Create `src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`。
**Interfaces:** `materialize(UniverseId,DimensionRole)` lazy，owner 以 UUID/role 對應獨立 region/entities/POI/random_sequences；U:END dragon/gateway data 存 `UniverseEndStateStore`，canonical End 原讀寫不變。
- [ ] RED `threeRolesHaveSeparateStorageAndRandomSequences`、`endStateIsPerUniverse`、`canonicalDragonStateUnaffected`、`partialRoleStorageRecoversSameKey`。native gate 補 End `getDragonFight/setDragonFight`、saveLevel、gateway 的可攔截時序，global clock 與背包不改。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_storage -Label f2-red` 具名 FAIL；窄 owner/hooks 後 `f2-green-a/b`、`-Suite Probe -Chain partial-role` PASS。目錄 orphan 或 owner 不符停服/fail closed，不刪世界。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；沒有 role world 就不可先開 portal。

### Task 17：exact role lifecycle 與玩家重啟登入

**Files:** Modify `universe/UniverseLifecycleService.java`、`UniverseMaterializationService.java`、`UniverseRuntimeRegistry.java`、`UniverseTransferService.java`；Create `src/main/java/dev/quantumchamber/mixin/PlayerManagerUniverseLoginMixin.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`m6-universe-probe.ps1`。
**Interfaces:** saved player world key→catalog `(U,role)`→先物化 exact role 再 vanilla login world lookup；unknown/corrupt key 保留 playerdata 並 fail closed，不 spawn canonical。
- [ ] RED `loginMaterializesExactRoleBeforeLookup`（U:Nether／U:End 各一）、`corruptRoleRefusesCanonicalFallback`、`staleLiveWorldIdentityRejected`、`restartKeepsEntityAndPoiOwnership`。
- [ ] `m6-focused.ps1 -Suite Probe -Chain role-login -Label f3-red` 具名 FAIL；修後 `f3-green-a/b`、GameTest `m6_universe_family_login` PASS，saved playerdata 最後一次成功寫入限制另留。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 18：Nether portal family routing

**Files:** Create `src/main/java/dev/quantumchamber/universe/DimensionFamilyRoutingService.java`、`src/main/java/dev/quantumchamber/mixin/NetherPortalFamilyMixin.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`m6-universe-probe.ps1`。
**Interfaces:** `resolveTarget(ServerWorld source, DimensionRole targetRole)` → exact same U world 或拒絕；canonical source 走原版。`NetherPortalBlock.createTeleportTarget` 的 key 與 8:1 direction 均按 role，而非 singleton equality。
- [ ] RED `netherPortalNeverLeavesFamily`（玩家/非玩家、U1/U2）、`netherEightToOneOnlyWithinFamily`、`missingRoleDoesNotFallBackCanonical`、`canonicalPortalStillVanilla`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_nether -Label f4-red` 具名 FAIL；修後 `f4-green-a/b`、cross-JVM `-Suite Probe -Chain nether-route` PASS，找不到目標時來源 entity 保留。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 19：End portal、gateway、credits 與 dragon 終局

**Files:** Modify `universe/DimensionFamilyRoutingService.java`；Create `src/main/java/dev/quantumchamber/mixin/EndPortalFamilyMixin.java`、`EndGatewayFamilyMixin.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`m6-universe-probe.ps1`。
**Interfaces:** U:Overworld→U:End，End exit/credits→同 U:Overworld；gateway 仍在同 U:End 自動建出口，dragon 終局持久化歸 U；canonical 行為不變。
- [ ] RED `endPortalAndExitKeepFamily`、`gatewayKeepsOwnEnd`、`dragonCreditsReturnToOwnOverworld`、`nonPlayerEndPortalKeepsFamily`、`canonicalEndStillVanilla`；native gate 核 `EndGatewayBlockEntity.getOrCreateExitPortalPos` singleton 判斷、credits caller。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_end -Label f5-red` 具名 FAIL；修後 `f5-green-a/b`、`-Suite Probe -Chain end-restart` PASS，U1/U2 dragon/gateway/canonical 資料不互覆。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 20：目前 U 的 bed／anchor／fallback respawn

**Files:** Create `src/main/java/dev/quantumchamber/universe/RespawnRoutingService.java`、`src/main/java/dev/quantumchamber/mixin/ServerPlayerUniverseRespawnMixin.java`；Modify mixins JSON、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`m6-universe-probe.ps1`。
**Interfaces:** `resolveRespawn(ServerPlayerEntity player, UniverseId currentUniverse, DimensionRole deathRole)`→有效 bed/anchor 同 U 原版目標；無效/耗盡→U:OVERWORLD；未知 authority fail closed。canonical 仍原版。
- [ ] RED `invalidAnchorFallsBackWithinUniverse`、`validBedAndAnchorStayInOwnFamily`、`deathInEndOrNetherKeepsUniverse`、`reconnectAfterDeathDoesNotFallBackCanonical`、`canonicalRespawnUnchanged`；核 `getRespawnTarget` 兩條 getOverworld fallback。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_respawn -Label f6-red` 具名 FAIL；修後 `f6-green-a/b`、`-Suite Probe -Chain respawn-restart` PASS，playerdata IOException 未證持久時依最後保存位置，不假造保證。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 21：同 role 量子艙、舊收據與 capability gate

**Files:** Modify `candidate/CandidateDoorInteraction.java`、`CandidateResolver.java`、`collapse/CollapseService.java`、`DestinationResolver.java`、`CollapseAuthorities.java`、`PassageTransferPolicy.java`、`universe/UniverseTransferService.java`；Modify `src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`m6-universe-probe.ps1`。
**Interfaces:** 只在 schema5 frozen Context `FamilyCapabilityVersion=1` 開 vanilla Nether/End Origin 的同 role SOURCE/NEW/EXISTING；NEW intent capability 必一致。目的 projection 同 XYZ 且 inert，passage 原有限玩家／frozen cohort／無正規回程；legacy schema3/4 Nether/End receipt 仍取消。
- [ ] RED `sameRoleNetherAndEndProjectionAtSameXYZ`、`oldNetherReceiptStillCancels`、`newSourceOpensNormally`、`newExistingRechecksEligibility`、`projectionNeverActivatesCatalogOrigin`、`wrongRoleCannotCross`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_chamber -Label f7-red` 具名 FAIL；修後 `f7-green-a/b`、`-Suite Probe -Chain legacy-new-role` PASS，WorldKey/role/projection ledger/live instance exact 比對。
- [ ] Task 16–20 的 role owner、portal 與 respawn no-leak gate 已各自通過後，才在本 task 的 code commit 讓**新建** Context 凍結 capability 1；本 task 的兩次 clean HEAD focused＋full 驗此值與同 role 通道。既有 0 不升版；再交 fresh spec／quality review。

## Milestone 收尾（與 code task 分離）

### Task 22：整合驗證、人工清單與文件真實性

**Files:** Create `docs/manual-acceptance/2026-10-24-m6-manual-smoke.md`；Modify `docs/implementation-notes/2026-10-24-m6-universe-generation.md`、`scripts/verification/README.md`、`AGENTS.md`（接手入口）；必要獨立結果文件均 tracked，SDD ledger ignored。
**Interfaces:** 自動證據索引 `code SHA → focused兩root → full root → CI run → reviewer`；人工 smoke 每項操作／預期／結果分欄，不用自動 PASS 代替實玩。
- [ ] 完成四段白話人工清單：①多人同時找 stronghold／fortress／End city 並正常存退；②連續 NEW 看到不同地形且重進相同；③same-seed 新世界無舊建築、mutation 地形不同；④同 U Nether 8:1、End 入口／gateway／dragon／credits、床／錨有效與失效、Nether/End 艙同 role 同 XYZ。每段預留實際版本、世界名、截圖/receipt 與操作者；使用者未授權代操作時交清單，不寫通過。
- [ ] 於最終乾淨 code HEAD 跑 `pwsh -NoProfile -File scripts/verification/run-all.ps1`，核 `END ALL exit=0`、所有 M6 probe/GameTest、原 M5/legacy/main-only、release/sources、排序 WARN/ERROR 與 Windows／Ubuntu CI。無未歸屬差異；失敗用新 root 修正並走 fix round。
- [ ] 必需人工 smoke 完成並寫 tracked note 後，交 task／whole-branch 全新 spec＋quality review；C/I 清零、docs truth、正常 push feature、雙平台 CI 讀回。M6 `main --ff-only`、tag 與 worktree 退役仍另守門檻，不借 M5 的一次授權；純 docs commit 只 `git diff --check` 並註明採計 code/CI SHA，不為同 code 重跑 full。

## 五項自查索引

Spec §1/7→Tasks 1–6，§2–4→Tasks 7–14，§5→Tasks 15–20，§6→Task 21，§8→每 task protocol＋22。所有 Review Focus 各有具名斷言。跨 task 介面由 file map 固定；原版 native 注入點保留 Task 7/16/18/19/20 的先驗 gate，未當成已驗 API。計畫不含本輪授權外玩法；若 native gate 證固定 1.21 無法實現某個 binding 契約，停止該 task、具名回報 spec 修訂與獨立 review，不能用不受控 fallback 替代。
