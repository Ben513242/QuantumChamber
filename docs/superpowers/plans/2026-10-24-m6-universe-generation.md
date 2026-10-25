# M6 宇宙生成多樣性與完整維度家族 Implementation Plan

> **給執行 agent：** 必須使用 `superpowers:subagent-driven-development` 逐 task 執行，以 `- [ ]` 追蹤。每個 task 的 implementer 與獨立 spec／quality reviewers 使用不同 context。

**Goal:** 在保留 M4／M5 既有收據與世界語意下，依序完成驗證腳本安全、三種原版結構生成競態防護、持久隨機與同種子生成、完整 Overworld／Nether／End 家族及其路由與重生。

**Architecture:** frozen candidate intent → checked allocation/catalog → exact runtime world identity。新 `SettingsSnapshot`、profile registry 與每 U root seed 是新生成的持久來源；legacy schema1 保持舊路徑，family 階段才依 provenance 做 checked 遷移。原版 singleton world 假設只在 exact custom family 邊界攔截。

**Tech Stack:** Minecraft 1.21、Yarn `1.21+build.9`、Fabric Loader `0.17.2`、Fabric API `0.102.0+1.21`、Loom `1.7.4`、Java 21、Gradle 8.8、PowerShell 7；JUnit／Fabric GameTest／跨 JVM probe。

**Spec:** `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md`（本計畫依已獨立審查的 v1.0 契約；目前文件 SHA256 `C776E2BA4A12F61A727D0C956B7522D7709C51975712C993E45CE498E3EF26BC`）。

狀態（2026-10-25）：獨立 plan review 已完成，最終 C0/I0/M0，釘選 `38e80fa`／原 plan SHA256 `0F848A7153A4B9F93C1E191FCE3AE4469A1EFE10575078933F0DF43E9650D9D6`。主控 preflight 已核22個task與196個共享file/interface pair，路徑/前置缺口0；只同步本段與執行說明語言，task契約不變。各task完成狀態依checkbox及note中的實際證據；未勾選項目尚未完成。

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
| snapshot／policy | `src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`、`UniverseProfileProvider.java`（新建）；`src/main/java/dev/quantumchamber/candidate/SourceGenerationSnapshot.java`、`M6CandidatePolicySelector.java`（新建）；`candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`CandidateLedgerService.java`、`NewUniverseIntent.java`、`superposition/SuperpositionSession.java`、`persistence/CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionJournalStore.java`; `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java` | `UniverseProfileRegistry.register(provider)`、`freeze()`、`resolve(snapshot)`；`SourceGenerationSnapshot(long rootSeed, GenerationSettingsSnapshot settings)` 僅在 NEW 分支求值；`M6CandidatePolicySelector.forNewSession(server)` 只在已驗後端 ready 時回 policy1/2/3/capability1，舊 frozen Context 不改。 |
| catalog／runtime seed | `collapse/UniverseAllocationLedger.java`、`CollapseAuthorities.java`、`DestinationResolver.java`; `universe/UniverseRecord.java`、`UniverseDefinition.java`、`UniverseRegistryState.java`、`UniverseRegistry.java`、`UniverseWorldDescriptor.java`; `universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`; `mixin/ServerWorldUniverseSeedMixin.java`（新建） | `catalogRecordMatches(intent, record, availability)` 依版本／provenance；`rootSeed(server, worldKey, descriptor)` 只對 exact schema2 world 生效，無匹配時原版。 |
| family identity／owner | `universe/UniverseKeys.java`、`UniverseRoleResolver.java`、`UniverseRuntimeRegistry.java`、`UniverseLifecycleService.java`、`UniverseTransferService.java`; `universe/DimensionFamilyRoutingService.java`、`RespawnRoutingService.java`（新建）與 `mixin/` 窄 portal／respawn／End state hooks（新建） | `(UniverseId,DimensionRole)↔worldKey↔live ServerWorld` exact 雙向驗；`resolveTarget(sourceWorld, targetRole)` 拒絕任何 canonical fallback。 |
| GameTest／recovery | `src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`（新建），`src/testmod/resources/fabric.mod.json`、`quantumchamber-test.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/mixin/M4LegacyGameTestFilterMixin.java`；`build.gradle` 的 `runGameTestM6`／`m6UniverseProbe`、`.github/workflows/build.yml` | batch `m6_universe*` 專屬 fresh `run/gametest-m6`；cross-JVM `m6UniverseProbe` 接 `m6UniverseNonce/Phase/StartupNonce/Root/Chain/ExpectedMode`，每 phase 新 JVM；`schema/expectedMode` 進 receipt。 |

以上是擁有權地圖，不授權任一 task 順手修改全部列出的檔。task 派工逐項列本 task 允許路徑與 exact HEAD。原版 mixin 注入點在 native gate 證明前不可預寫成已可注入；下列功能介面及斷言固定，具體 descriptor/ordinal 由對應 native task 記錄後交 implementer。

`Files` 簡寫規則：同一串列中未寫目錄的 Java 檔名，沿用前一個完整 Java 路徑的目錄，直到下一個完整路徑；`README.md` 一律是 `scripts/verification/README.md`；`mixins JSON` 一律是 `src/main/resources/quantumchamber.mixins.json`。`src/testmod/resources/fabric.mod.json` 與 `quantumchamber-test.mixins.json` 永遠分別指正式 testmod entrypoints 與 testmod-only mixin 清單，不能用 main mixin 清單代替。task 的短派工仍須展開成絕對工作樹下的 exact 允許檔案。

## 驗證命令與 code 提交流程

第一個 focused runner task 完成後，JUnit 統一用 `pwsh -NoProfile -File scripts/verification/m6-focused.ps1 -Suite JUnit -TestClass <fqcn> -Label <nonce>`；GameTest 用 `-Suite GameTest -Batch <batch>`；跨 JVM 用 `-Suite Probe -Chain <chain>`。RED 預期**具名斷言失敗**，不是 class 未定義／編譯失敗。每次不同 `<nonce>`，保留 failure root。每個 task 的 code commit 前用 `pwsh -NoProfile -File scripts/verification/gates.ps1 -Mode M6Compile -Label <task>-compile` 產生 compile log（Task 3 加入此 mode；僅編譯受影響 source/test，不以 compile 代替 GREEN）；Task 1–2 尚無此 mode，改用各自 PS parser／專用 focused check 作 code 前驗證。commit 後確認 `git status --porcelain` 空且 HEAD 精確；兩個 focused GREEN 使用不同 label，但同 HEAD。完整回歸固定 `pwsh -NoProfile -File scripts/verification/run-all.ps1`；未到 Task 12 的 M6 runtime probe 尚未納入 full 時，run-all 必包含當時已有 M6 gates，不空宣全 M6 完成。每次 full 記 `END ALL`、scope JSON、XML 集合與 artifact oracle；必要 mutation adapter 在 `finally` 還原並用 `git hash-object` 比對原 blob。可重現的 gate 更新後才採計新 mode，嚴禁直接 `gradle test`。

### 平台／CI／證據矩陣（Task 3 建框架，Task 12 啟用 native CI）

| 環境 | 必跑、合法排除與 owner | 初始時間及證據 |
| --- | --- | --- |
| 本機 Windows `run-all.ps1` | 既有全部 gates＋當階段 M6 JUnit/GameTest；Task 7 起 `m6-universe-probe.ps1 -AllEnabled` 從 tracked chain manifest 執行**所有當前已新增** native chains（Task 7 diagnostic 只依 diagnostic oracle 採計，不冒稱 seed 功能通過）；每 task 擴充 manifest。`run-all.ps1` 自己對 summary/receipt/XML/內外 JAR/mode 做最終核對。 | `gates.ps1`／`m6-universe-probe.ps1` 每 phase 預設 3 分鐘、idle 90 秒，整個 M6 native 區塊初始 60 分鐘；nonce root 完整保留。整體 runner 不用外層硬 timeout 截斷 move/restore。 |
| GitHub `build` Ubuntu，90 分鐘 | Java 21（`actions/setup-java`）、`./gradlew` 經 tracked `scripts/verification/m6-ci-gametest.ps1 -Platform Ubuntu` 啟動 M6 JUnit/`runGameTestM6`，加既有 build/default/M5；原生 Windows checkpoint 與 `Win32_Process` owned-process native chains **明列不支援且不得假 PASS**，執行 `-CheckUnsupportedNative` 拒絕契約。`ci-rule-check.ps1` 驗實跑 exact testcase 與合法排除。 | Build 25、default 15、M5 15、M6 15、oracle 5、upload 5、保留 10 分鐘；job 上限仍 90。`if: always()` 上傳 XML、三 run log、M6 scope/summary、拒絕 receipt，即使失敗。 |
| GitHub `windows-native`，90 分鐘 | Java 21、repo `gradlew.bat` 經 tracked `m6-ci-gametest.ps1 -Platform Windows` 跑 M6 JUnit/GameTest，既有 build/default/M5 照原 workflow；不把 GameTest 假稱 main-only。 | build/default 30、M5 20、M6 15、獨立 Main-only 7、oracle 5、upload 5、保留 8；`if: always()` 上傳 XML、logs、scope、artifact/main-only oracle。 |
| **新增** GitHub `windows-m6-native`，90 分鐘 | `on: push` 目前無 branch filter，`codex/m6-universe-generation` 的正常 push 會觸發；`actions/setup-java` Temurin 21，tracked `m6-universe-probe.ps1 -CiAll -Gradle <workspace>/gradlew.bat` 串所有已啟用 Windows native chains。PowerShell 7 的 `Win32_Process`／`java.exe` 與 local fixed NTFS 前提先查；每 chain 真實 Java phase、stop／crash 和 exact restore；只在 Windows 跑，不借 M5 CI。 | setup 10、native 60（phase 3、idle 90 秒）、summary/oracle 5、`if: always()` artifact upload 5、保留 10。Job/step timeout 不得在 move/restore 中強制中斷；PS 自管 deadline 並在 finally 復原，再回傳 FAIL。若初輪計量超預算，保留 root/時間證據、修 plan 與 review，不盲目加長。 |
Task 3 Windows CI main-only 補充（2026-10-25，獨立 plan review C0/I0/M0）：使用薄 `m6-ci-main-only.ps1` 沿用既有 `gates.ps1 -Mode Main`（240秒＋既有60秒grace、finally exact restore）及 `main-oracle.ps1 -Gate`，在獨立7分鐘step保存該CI同SHA、Main scope、三層／stop／restore與原件hash receipt，always-upload；不得借本機full或GameTest冒充。CI新鮮checkout先驗沒有既有world；本機run-all保留原Move/Restore語意且Main只跑一次。Ubuntu不新增Main-only，Task12 native chains job仍另啟；若實測超預算保全原件再review，不靠外層強停move/restore。



CI 的 Gradle 來源：CI 只用該 checkout 的 `gradlew`／`gradlew.bat`，先核檔案、wrapper distro 與 `java -version=21`；本機用 AGENTS 固定 Gradle 8.8 絕對路徑或存在的 repo wrapper，記實際 resolved 路徑。新增 native job 的 `if: always()` artifact 包括每 phase `phase.json`／`final.json`／`halt.json`、preflight/restore/hash manifest、server stdout/stderr/latest.log、GameTest XML、`summary.json` 與 SHA256 manifest；失敗仍上傳**已有** root，缺 mandatory receipt 使 job／`ci-rule-check` FAIL。成功採計需 exact commit SHA、所有 mandatory chain/phase `expectedMode`、實際 stop、無 orphan Java／session.lock、逐檔 restore hash 相同。`ci-rule-check.ps1` 與 `run-all.ps1` 按平台／已啟用階段比較集合，不把 Ubuntu 合法排除當成 Windows PASS。新增 M6 CI step/job 名稱與 artifacts 由 Task 3／12 的 `.github/workflows/build.yml` 明列；不得只改 README。既有 Windows／Ubuntu job 90 分鐘上限維持，時間分配是待實測的初值。

M6 `runGameTestM6` 需在 `build.gradle` 設 `-Dquantumchamber.gametest.minTickMillis=10`，只作用於此 run；`TestServerTickPaceMixin` 未設 property 時無節流，故 default／legacy 保持現況。M6 GameTest 的 full/CI oracle 必見恰一個結束 pace 記錄、`waited>0`、`tps<=100.0`，並核 server/test集合同 SHA；缺 pace 證據即 FAIL，不把先前 Ubuntu 超速當 M6 已知偶發。

### Cross-JVM chain 落點矩陣

Task 7 建立 `src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`（`ModInitializer`，登記於 testmod `fabric.mod.json` main）、Loom `m6UniverseProbe`、`scripts/verification/m6-universe-probe.ps1`。PS 設 `Context.Task=runM6UniverseProbe`、`PropertyPrefix=m6Universe`，沿 `probe-common.ps1:Invoke-OwnedPhase` 傳 `Nonce/Phase/StartupNonce/Root`；Task 7 在 `probe-common.ps1` 加**可選且預設空**的 extra properties `Chain/ExpectedMode`，M4/M5 caller bytes/行為不變。PS 有 tracked `-AllEnabled` manifest，`run-all.ps1` 從 Task 7 起呼叫它，後續 task 加 chain 時同時擴 manifest／PS oracle／Java phase，full 自動取得新集合。Java 入口按 `chain+phase` 明列 dispatch 到下表 owner 的 testmod phase helper；每 phase 存 `m6ProbeSchema=1`、`expectedMode=DIAGNOSTIC|FUNCTIONAL`、chain/phase/startupNonce、exact UUID/key/seed/authority digest 與具名斷言，PS oracle 讀同 nonce receipt 及 log，不以 exit 0 取代語意。Task 7 的 `native-seed-diagnostic` GREEN 僅表示可靠觀察 M5 全域 seed/共享序列；Task 11 的**另一條** `native-seed-functional` 才判 schema2 獨立 seed，兩者不可混用或同名改判。phase 失敗保留 root，不重跑同 phase。

| chain／Java phase helper（均在 `src/testmod/java/dev/quantumchamber/gametest/`，新建時標新） | phase／fixture 與具名斷言 | Gradle／PS owner |
| --- | --- | --- |
| `native-seed-diagnostic`／`M6UniverseRecoveryProbe`（Task 7 新建） | `baseline,save-reopen`：schema1 U 觀察 `getSeed=saveSeed`、shared randomSequences；控制組 canonical 一致，receipt mode `DIAGNOSTIC`，功能斷言仍 RED。 | Task 7 建 `m6UniverseProbe`、PS chain dispatch/診斷 oracle、testmod main entry。 |
| `catalog-readback`／`M6CatalogProbePhases.java`（Task 10 新建） | `intent,allocation,atomic-move-readback,restart`：同 token/UUID/seed，讀回拒絕時不物化；故障 mixin 只在 testmod。 | Task 10 修改 Java dispatch、`src/testmod/resources/quantumchamber-test.mixins.json`、PS oracle/phase table；Task 12 不補這個前置。 |
| `native-seed-functional`、`intent-allocation`、`partial-overworld`／`M6RandomProbePhases.java`（Task 11 新建、Task 12 擴） | 前者 `construct,reopen` 斷 schema2 getSeed/noise/structure/biome、schema1/canonical 控制組；後兩者 `intent,allocation,catalog,partial,restart` 斷 checked 順序與同 identity。 | Task 11/12 各修改 central Java dispatch、PS oracle/phase table；receipt mode `FUNCTIONAL`。 |
| `same-seed-restart`、`profile-restart`／`M6ProfileProbePhases.java`（Task 13 新建、Task 14 擴） | `source,new,restart`：同 seed/settings 空歷史；toggle 後 target preset/fingerprint 固定。 | Task 13/14 各修改 Java dispatch、PS oracle/phase table。 |
| `legacy-preset-change`／`M6LegacyProbePhases.java`（Task 15 新建） | `migrate,mutate,restart`：seed 不變 normal↔amplified 拒絕，未遷移 schema1 照舊。 | Task 15 修改 Java dispatch、PS oracle/phase table。 |
| `partial-role`,`role-login`,`nether-route`,`end-restart`,`respawn-restart`,`legacy-new-role`／`M6FamilyProbePhases.java`（Task 16 新建，17–21 擴） | 各有 `create,halt,restart,assert` 及同 U role/owner/無 canonical fallback 的具名 receipt。 | Task 16–21 各修改 central Java dispatch、PS oracle/phase table；新 role／玩家 fixtures 由該 task 的 GameTest class 建。 |

### Task 1：`New-OwnedRoot` 的 `run/` 祖先守門

**Files:** Modify `scripts/verification/probe-common.ps1:17-24`；Create `scripts/verification/m6-harness-safety-check.ps1`；Modify `scripts/verification/README.md`。
**Interfaces:** consumes `Resolve-Evidence(Context,Path)`／`New-OwnedRoot(Context,Suffix)`；produces `Assert-OwnedRunAncestor(Context)`，建 root 前 canonical／non-reparse，拒絕不屬 repo 的 run ancestor。
- [x] 寫 `m6-harness-safety-check.ps1 -Case owned-root`：受控 reparse `run/`／非 canonical ancestor 的 RED 必以 `OWNED_ROOT_ANCESTOR_UNSAFE` 拒絕且不在外部建檔；正常實體 `run/` PASS。保留 fixture，不碰使用者 run。
- [x] 執行 `pwsh -NoProfile -File scripts/verification/m6-harness-safety-check.ps1 -Case owned-root -Label h1-red`，原版必在具名 unsafe 斷言 FAIL；修窄 guard、同檔 `h1-green-a/b` PASS。
- [x] 依全域 code 提交流程完成此 task 的必要 compile（PS parser／source guard）、乾淨 HEAD 兩 GREEN、full、fresh spec＋quality review；結果文件另 commit。

完成證據：code "b533367"，兩次focused 8/8、run-all-0790df31c2a943ceac91c2ca2eb01890 END ALL exit0；全新spec/quality皆C0/I0/M0。詳M6 note Task1；本結果文件不另跑runtime。

### Task 2：`Move-Fresh` 建目錄失敗的 exact 復原

**Files:** Modify `scripts/verification/gates.ps1:80-102`；Modify `scripts/verification/m6-harness-safety-check.ps1`、`README.md`。
**Interfaces:** consumes `Move-Fresh(root,tag)`／`Restore-Root(swap)`；produces catch/finally 內即使 New-Item 失敗仍以已知 `swap` 復原，原例外保留，原世界逐檔 hash 相同。
- [x] `-Case move-fresh` 注入「move 成功、新 fresh root `New-Item` 失敗」：RED `MOVE_FRESH_RESTORE_MISSING`，檢查 backup 存在且原錯誤可追；另測 restore 自身失敗兩個原因皆保留。
- [x] 跑 `m6-harness-safety-check.ps1 -Case move-fresh -Label h2-red`；修後 `h2-green-a/b` PASS，原世界 hash/lock 正常，所有失敗 root 保留。
- [x] 逐檔 commit、同 HEAD 兩 GREEN、full、fresh spec＋quality review；不與 Task 1 或 generation commit 合併。

### Task 3：M6 定向 gate 與結果 oracle

Task3完成（code ac085c8；spec C0/I0/M1、quality C0/I0/M0；CI commit4d8fc66／run40144948603雙平台原件PASS）。M-1交Task4 Step0；本機、CI與後續純文件SHA分別採計，不冒稱新docs SHA重跑runtime。

補充（2026-10-25）：M4 boundary observer 的必要 owner 隔離已經全新 SOL 獨立 review，C0/I0/M0；原四 scope 契約不變。Task 2 quality Minor M-1 依交接規則併入本 task Step 0。

**Files:** Create `scripts/verification/m6-focused.ps1`、`m6-ci-gametest.ps1`、`scripts/verification/m6-deadline-check.ps1`、`scripts/verification/m6-ci-main-only.ps1`、`src/testmod/java/dev/quantumchamber/gametest/M6GateCanaryGameTests.java`；Modify `scripts/verification/gates.ps1`、`m6-harness-safety-check.ps1`、`run-all.ps1`、`ci-rule-check.ps1`、`README.md`、`build.gradle`、`.github/workflows/build.yml`、`src/testmod/resources/fabric.mod.json`、`src/testmod/java/dev/quantumchamber/gametest/mixin/M4LegacyGameTestFilterMixin.java`、`src/testmod/java/dev/quantumchamber/gametest/M4GameTestBoundaryProbe.java`、`src/testmod/resources/quantumchamber-test.mixins.json`（僅新 testmod mixin 確有加入時）。
**Interfaces:** `gates.ps1` 新增 `-Mode M6Compile|M6JUnit|M6GameTest`、`-TestClass <fqcn>`（JUnit）、`-Batch <batch>`（GameTest），由 `m6-focused.ps1 -Suite JUnit|GameTest|Probe` 封裝；`runGameTestM6` 是獨立 fresh runtime。filter 四種 scope 互斥：legacy=`m4_legacy_runtime`、M5=`m5_universe*`、M6=`m6_universe*`、default=其餘。focused `-Batch` 可含多個 testcase：比較所選 batch 的**完整 `classname+name` 集合**，不能用 testcase 數等於 batch 數。full/CI 同樣比 exact 集合及 JAR/main-only 隔離。
- [x] Step 0：承接 Task 2 quality M-1，非 restore-fails 案例直接斷言目錄存在性等於 originalExisted，receipt 保存 observedExists；以受控殘留空目錄反例證明可拒絕，再跑 move-fresh 與 owned-root focused。只補測試，不改已驗 Move-Fresh 行為；完整回歸併本 task 最終候選。
- [x] 精確 M6 run 略過 M4 boundary observer 與 M5 suite-end 註冊；mixed scope flags 必拒絕。fresh M6 canary root 不得產生 m4-boundary artifact 或 M5_UNIVERSE_SUITE_END；default／legacy／M5 原 oracle 由 full 保持。
- [x] 先建可啟動的最小 `runGameTestM6`／`m6-focused.ps1`／四 scope filter，於 `fabric.mod.json` 註冊 canary `fabric-gametest` 並加入可觀察且為真的 `m6_universe_gate` 斷言；跑 `pwsh -NoProfile -File scripts/verification/m6-focused.ps1 -Suite GameTest -Batch m6_universe_gate -Label g0-canary`，確認 XML 有該 exact case 且 PASS；這是 harness bootstrap，不當行為 RED。
- [x] 在可執行 canary 基礎上，用錯誤 XML／testmod 泄漏 fixture 使 oracle 具名 `M6_SCOPE_MISMATCH`／`M6_TESTMOD_LEAK` RED；證 default/legacy/M5 均排除 M6、M6 不含它們，不能拿缺 entrypoint 或空測試當 RED。
- [x] 加 fresh `run/gametest-m6`、`gameTestM6` 專用 `minTickMillis=10`、`m6-ci-gametest.ps1` 的 Windows／Ubuntu 正常 run 與合法平台拒絕路徑；`g0-green-a/b` 的 canary PASS，negative fixture 仍 FAIL，M6 pace `waited>0/tps<=100.0`、`run-all`/`ci-rule-check` 新 summary 與兩平台同 SHA 比對，release/sources/main-only oracle 照舊。CI 新 step 的 budget/artifact 依上方矩陣。
- [x] 完成 compile、code commit、兩次 clean HEAD focused、full、fresh spec＋quality review；之後所有 task 只能經此 tracked runner 跑 focused。

## 切片①：三類原版結構生成競態

三類的 RED 必包含兩世界同時 invocation、相同世界單序列對照及 server-thread `/place` 與 worker 交錯。既有唯讀 `native-races-inventory.md` 只指出候選的**整輪**邊界：`StrongholdStructure.addPieces`（內部 `init` 後擴張）、`NetherFortressStructure.addPieces`（`Start` 建構時共用 arrays 重設）、`EndCityGenerator.addPieces`（重設 Part 後遞迴）；不是只攔 reset/pick。先在各 task 的 ignored root 保存固定 1.21 JAR SHA、讀寫欄位表、呼叫鏈、barrier 時點與 thread dump；若 runtime 不重現 crash，以 deterministic 共享計數／state 污染 RED，不能寫「CME 已重現」。窄修以 invocation 私有狀態為先；ThreadLocal 須證遞迴與 finally 清理；鎖須證可重入、鎖階序與 `/place`／shutdown 不死鎖。H5/H6/H8 不當本輪 GREEN。

### Task 4：Stronghold invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/StrongholdGeneratorStateMixin.java`、`src/test/java/dev/quantumchamber/universe/StrongholdGenerationConcurrencyTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`src/testmod/resources/fabric.mod.json`（註冊 `fabric-gametest`）、`scripts/verification/README.md`、`scripts/verification/m6-ci-gametest.ps1`（僅Step0）、`build.gradle`（僅JUnit共用testmod classes；不帶resources）。
**Interfaces:** exact 原版 `StrongholdStructure.addPieces` invocation 內隔離 `possiblePieces`、`activePieceType`、`totalWeight`、`ALL_PIECES.generatedCount`；新 mixin 不改其他 structures。
- [x] Step0承接Task3 spec M-1：強制把單一scope名稱包成陣列後再加入獨立m6_first，核原合法case仍在、actual=2、extra恰為m6_first；修正三個跨scope fixture的精準性。以受控反例／focused驗證，完整回歸併本task；不改正式filter語意。
- [x] 讀 native `init/pickPiece/addPieces` 實際讀寫、`/place` caller，記可注入方法 descriptor 與再入性；JUnit `twoWorldStrongholdsDoNotSharePieceCounts` 的 barrier RED 要見 count/state 交叉，`singleWorldStrongholdLayoutUnchanged` 對照固定 seed。
- [x] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.StrongholdGenerationConcurrencyTest -Label r1-red` 具名 RED；最小 mixin 後 `r1-green-a/b` PASS。另 `-Suite GameTest -Batch m6_universe_race_stronghold` 驗 `/place`／worker 無卡住。
- [x] compile→code commit→兩個 clean HEAD JUnit GREEN＋GameTest→full→fresh spec／quality；若鎖／ThreadLocal 不能證安全，停在此 task 不進 Task 5。

Task4完成（code4485a92；spec C0/I0/M0、quality C0/I0/M1；同pin雙GREEN與full通過）。測試cleanup Minor明列於下方Task5 Step0，無未關C/I；詳細root/hash及支援限制見M6 note。

### Task 5：Nether fortress invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/NetherFortressGeneratorStateMixin.java`、`src/test/java/dev/quantumchamber/universe/NetherFortressGenerationConcurrencyTest.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`、`scripts/verification/README.md`、`src/test/java/dev/quantumchamber/universe/StrongholdGenerationConcurrencyTest.java`（僅Step0測試cleanup回歸）。
**Interfaces:** 兩組 static pieces 的 `generatedCount` 只在一個 fortress invocation 內共享；不變更 Stronghold 或 canonical 單世界輸出。
- [x] Step0承接Task4 quality M-1：worker啟動後await也納入finally；先release，再逐一嘗試有界join，保留主要例外、cleanup以suppressed附加。只改測試cleanup並加具名失敗路徑回歸，不強停thread、不改Stronghold production；必要共用helper新增前向主控補exact owner。完整回歸併本task。
- [x] 先盤點 `ALL_BRIDGE_PIECES`／`ALL_CORRIDOR_PIECES` 的全部讀寫、遞迴與 `/place` 路徑；RED `twoWorldFortressesKeepIndependentCounts` 的兩世界 barrier 應顯示交叉，`singleWorldFortressLayoutUnchanged` 固定 seed。
- [x] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.NetherFortressGenerationConcurrencyTest -Label r2-red` 具名 FAIL；窄修後 `r2-green-a/b`、`-Suite GameTest -Batch m6_universe_race_fortress` PASS。
- [x] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；保留原 RED root。

Task5完成（code43edeb1；fresh spec/quality均C0/I0/M2；同pin五focused與full通過）。Task4 cleanup M-1關閉；兩個Task5 Minor是lag/Bat原件觀察，記M6 note並由後續相關驗證追蹤，無要求現在新增production修正或為清空警告重跑。

### Task 6：End city invocation state

**Files:** Create `src/main/java/dev/quantumchamber/mixin/EndCityGeneratorStateMixin.java`、`src/main/java/dev/quantumchamber/mixin/EndCityBridgeStateMixin.java`、`src/main/java/dev/quantumchamber/universe/EndCityInvocationState.java`、`src/test/java/dev/quantumchamber/universe/EndCityGenerationConcurrencyTest.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6RaceGameTests.java`、`scripts/verification/README.md`。
**Interfaces:** `Part.shipGenerated` 每次 city invocation 獨立，不洩漏下一任務或另一世界。

Task6原生方案owner補充（2026-10-25）：模板載入有同步resource/disk/DataFix鏈，採五參數root的invocation-private ThreadLocal stack與匿名bridge三個exact字段點，不新增全輪跨thread鎖。已由fresh SOL方案review C0/I0/M1；新增上述bridge mixin/state helper，後續仍須有效RED/compile/remap/同pinGREEN/full/fresh雙review。無frame只沿原字段，不承諾第三方root/Part混用相容；無withInitial，無frame查詢get()==null立即remove，允許JDK瞬時null而不留entry/空stack，root finally恢復父frame或remove。
- [x] 盤點 `Part.shipGenerated` 寫入／重設、遞迴及 `/place`；RED `twoWorldEndCitiesKeepShipChoiceIndependent` 與 `singleWorldEndCityLayoutUnchanged` 分別證共享污染及原版輸出。
- [x] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.EndCityGenerationConcurrencyTest -Label r3-red` 具名 FAIL；窄修後 `r3-green-a/b`、`-Suite GameTest -Batch m6_universe_race_endcity` PASS。
- [x] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality，完成切片①的三類獨立驗收，不用 race 修補取代後續 family 測試。

Task6完成（code e077；fresh spec/quality均C0/I0/M2；同pin五focused與新短full44da通過）。原深root9280保持FAIL，未拼接；兩Minor為lag觀察與深cwd前置支援缺口，後者交Task7 Step0 triage，無未關C/I。

## 切片②：持久 random seed

### Task 7：native seed/settings owner 的 RED 與介面釘選

**Files:** Create `src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`scripts/verification/m6-universe-probe.ps1`；Modify `scripts/verification/m6-focused.ps1`、`m6-harness-safety-check.ps1`、`probe-common.ps1`、`run-all.ps1`、`README.md`、`build.gradle`、`src/testmod/resources/fabric.mod.json`。此 task 不改 production。
**Interfaces:** `M6UniverseRecoveryProbe` 是 testmod `main` entrypoint，`build.gradle` 的 Loom `m6UniverseProbe` run 綁 `run/m6-universe-<nonce>`；`m6-universe-probe.ps1` 使用 `probe-common.ps1` owned process 與 `m6UniverseNonce/Phase/StartupNonce/Root/Chain/ExpectedMode` properties；`-AllEnabled` manifest 首版只有 diagnostic，`run-all.ps1` 從此 task 起真跑。produces `-Chain native-seed-diagnostic` 的 per-world `getSeed/noise/structure/biome/randomSequences` receipt `m6ProbeSchema=1,expectedMode=DIAGNOSTIC`，以及固定 1.21 exact mixin descriptor／constructor thread map，供 Task 11。`probe-common.ps1` 新 extra properties 在 M4/M5 caller 未指定時無行為差異。
- [x] Step0承接Task6 M-2：先依280字cwd Win32267／147與218字成功的當次原件，triage正式run-all入口的最小前置診斷或明確支援範圍；在任何昂貴runtime/world操作前拒絕無法啟動的CI布局。用process-only受控反例與短root正例，不臆測通用路徑上限、不改global設定、不搬失敗root。只先用既有run-all／harness owner；若需改ci-rule-check等新path先向主控補owner/方案，獨立review與本task最終gates仍必要。
- [x] 以 native-inventory JAR hash 為起點，補 `ServerWorld.getSeed()` 持續全域讀、constructor `StructureLocator`、`ServerChunkLoadingManager` noise/structure、biome source、`RandomSequences` 非 null 共用、End dragon 讀寫、`/place` worker；測試 `newWorldStillReadsSaveSeed`、`newWorldSharesCanonicalRandomSequences` 在 M5 code 上取得**預期現況 RED**，不是測試框架失敗。
- [x] `m6-focused.ps1 -Suite Probe -Chain native-seed-diagnostic -Label g1-red` 保留原 root；probe 用新 owned world，startup/shutdown 與 restore 留 receipt；另以 `m6-harness-safety-check.ps1 -Case optional-probe-properties -Label g1-extra` 驗 `probe-common.ps1` 的新 Chain/ExpectedMode properties 僅 M6 傳入、M4/M5 原 arguments 完全不變。此 task 的兩次 GREEN 只表示 `DIAGNOSTIC` oracle 可靠辨識舊行為、單世界控制組通過，另保留 `newWorldHasIndependentSeed` 功能斷言 RED，不能把預期舊缺陷當功能 PASS。
- [x] compile→test/probe commit→兩次同 HEAD 探針驗證與 full→fresh spec／quality；將注入點、context owner、不得改全域 SaveProperties 的可執行決定寫入 tracked note 的結果 docs commit。

**結果（2026-10-25）：** range71f51f7..245051e；原0f診斷RED/雙GREEN及ca76完整回歸保留歷史。quality I-1由245051e修正，安全RED/同clean雙focused/新full efbd通過；fresh scoped spec與quality均I-1 ADDRESSED、無新增C/I。Task7完成，詳M6 note；獨立seed尚待Task11/12。

### Task 8：可持久化 `SettingsSnapshot` 與 provider API

**Files:** Create `src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`、`UniverseProfileProvider.java`、`src/test/java/dev/quantumchamber/universe/GenerationSettingsSnapshotTest.java`、`UniverseProfileRegistryTest.java`；Modify `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`、`scripts/verification/README.md`。
**Interfaces:** `register(ProfileProvider)`→拒重、`freeze()`→鎖定、`resolve(GenerationSettingsSnapshot)`→exact 三 role settings 或 fail；`GenerationSettingsSnapshot` 具有 v1 provider id/version、bounded typed Parameters、32-byte fingerprint、canonical bytes ≤512。`QuantumSuperpositionMod.onInitialize()` 先註冊內建 normal/amplified provider、freeze，**之後**才讀 journal／初始化 M5 authority／lifecycle bootstrap；重啟找不到 provider 在 load gate fail closed。
- [ ] RED `snapshotRejectsChangedProvider`（同 id/version 改 Overworld biome/structure closure）、`snapshotRejectsUnknownParameter`、`snapshotRejectsOversize512`、`registryRejectsLateOrDuplicateRegistration`；內建 normal/amplified provider 的 canonical closure 排序固定且 root seed 不入 fingerprint。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.GenerationSettingsSnapshotTest -Label g2-red` 具名斷言 FAIL；實作後 `g2-green-a/b` 加 RegistryTest PASS；未知／遺失 provider 拒絕，擴充 provider 不能偷進候選權重。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；native closure 若無法 canonical 化，停在此 task修訂方案，不以 id 字串冒充 fingerprint。

### Task 9：schema5 候選與 policy1，只開 random

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`NewUniverseIntent.java`、`src/main/java/dev/quantumchamber/persistence/CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`SessionJournalStore.java`、`src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`、`scripts/verification/README.md`；Create `src/main/java/dev/quantumchamber/candidate/SourceGenerationSnapshot.java`、`src/test/java/dev/quantumchamber/persistence/CandidateJournalCodecTest.java`、`SessionJournalSchemaFiveTest.java`。
**Interfaces:** `CandidatePolicySnapshot` 能 strict 表示 M6 `quantumchamber:m6_profiles_v1`/candidate schema2/5-20-75/4096/family0；新 intent 持 source/target snapshot 與 profile1；`SessionRecoveryRecord.fromNbt(..., envelopeSchema=5)` 外層放行並 strict 分派。`SessionJournalStore.read(path)` **按同一穩定輸入的 envelope 版本**選 reader：schema1–4 仍用舊合法容量／解碼／寫入與 checked readback 契約（含 schema3/4 原 Context 16384）；schema5 的正式 load、checked readback 與候選完整 envelope 寫前 preflight 才用同一 `NbtSizeTracker` 16 MiB。unknown>5／版本不符 fail closed，不回退 unlimited。**只加格式／解析能力**，正式 `SuperpositionSession.freezeCandidateContext` 此 task 仍產生 M4 context，新意圖尚不公開。
- [ ] 指定版本分派安全實作：`SessionJournalStore` 由單一檔案 handle 取得不可變 compressed bytes 快照；在**不物化 `Records`、不以 unlimited NBT reader 解完整檔**的 top-level tag 探測 `data.SchemaVersion`，拒絕缺失、重複、型別錯或 unknown version；隨後只從**同一份 bytes**以選定 tracker 做正式 parse/strict decode，禁止兩次開檔之間的 TOCTOU 或 schema5 超限後回退 legacy。探測需允許合法舊 NBT 欄位順序，具體固定 1.21 streaming API／小型 skip parser 先由 Task 9 釘選並留測試，不能依「SchemaVersion 永遠第一欄」猜。writer 已知候選版本：schema1–4 沿既有 preflight/readback；schema5 先以同一 bounded reader 對**候選** bytes preflight，通過才 atomic write，readback 還要版本 exact 相同。
- [ ] RED `policyOneOnlyFreezesRandom`（手工 M6 fixture，固定 material/token 的 domain hash→seed 不等來源）、`schemaFiveRoundTripsMixedLegacyIntent`、`formalRecordRejectsUnknownSchemaSix`、`journalRejectsMissingProvider`、`journalRejectsWrongSnapshotOrCapability`、`schemaFiveWriteReadbackReloadExact`、`preflightRejectsOverBudgetWithoutChangingFlushed`、`versionProbeReadbackMismatchRejectsWithoutUnlimitedFallback`。另做真正**strict 合法且 `NbtSizeTracker` 解碼計量>16MiB**的 schema3 與 schema4 多 session fixture：每 session candidate≤16384、UUID/Origin/owner 唯一、候選／selection／collapse 組合合法，`legacySchemaThreeAndFourOver16MiBStillLoadAndReadback` 經正式 `SessionJournalStore`、`SessionRecoveryRecord`、`SessionRecoveryState` 載入／既有 checked 更新後 exact authority 相同；不以未知欄位、非法大 list 或壓縮檔 byte 長度冒充。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.persistence.SessionJournalSchemaFiveTest -Label g3-red` 具名 FAIL；最小 codec/外層record/版本化正式 reader 實作後 `g3-green-a/b` 加 CandidateResolver/CodecTest PASS。schema5 的 4096 最壞行＋兩個 512-byte snapshots、超限、unknown6、版本 probe/實讀不符均走正式 load/readback；超限 schema4→5 必在寫前拒絕，磁碟正式檔、flushed authority 與 envelope schema4 原值不變。不能以 fixture-only `CheckedNbtFile` 或先 unlimited 解析 schema5 冒充完成。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；policy2/3及family1都不能在此 task 啟用。

### Task 10：allocation2／catalog2 checked 原子權威

**Files:** Modify `src/main/java/dev/quantumchamber/collapse/UniverseAllocationLedger.java`、`CollapseAuthorities.java`、`src/main/java/dev/quantumchamber/universe/UniverseRecord.java`、`UniverseDefinition.java`、`UniverseRegistryState.java`、`UniverseRegistry.java`、`UniverseWorldDescriptor.java`、`UniverseKeys.java`、`SeedPolicy.java`；Modify `src/test/java/dev/quantumchamber/collapse/UniverseAllocationLedgerTest.java`、`CollapseAuthoritiesTest.java`、`src/test/java/dev/quantumchamber/universe/UniverseRegistryStateTest.java`、`UniverseKeysTest.java`、`UniverseDefinitionTest.java`、`scripts/verification/m6-universe-probe.ps1`、`scripts/verification/run-all.ps1`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`src/testmod/resources/quantumchamber-test.mixins.json`；Create `src/testmod/java/dev/quantumchamber/gametest/M6CatalogProbePhases.java`、`src/testmod/java/dev/quantumchamber/gametest/mixin/M6CatalogReadbackFaultMixin.java`。
**Interfaces:** `UniverseKeys.world(U,role)` 對三 role 只推導 pure identity，`SeedPolicy.EXPLICIT_ROOT_SEED_V1` 可 strict decode；allocation row v2 保存 root seed＋target fingerprint；catalog record schema2 `NEW_M6` **先保存**三 role exact key/四欄、**此 task 不物化**新 Nether／End。舊 row/record 混合保留；`catalogRecordMatches(intent,record,availability)` exact；checked move/readback/roll-forward gate。Task 15 不再擁有首次 key/enum 引入，只做 legacy migration。
- [ ] 承接Task7 review M-2：首次新增enabled chain時，同步擴充正式run-all的manifest/summary/receipt oracle與正反例，逐mode/schema/chain/phase核對；保留既有DIAGNOSTIC歷史語意，不延到Task12才補。此owner補正已經Task7兩席獨立review確認，隨本task雙GREEN/full及fresh review驗收。
- [ ] RED `allocationTwoKeepsLegacyRowsExact`、`catalogRejectsWrongProvenanceRoleShape`、`universeKeysDeriveAllThreeWithoutLoadingWorld`、`seedPolicyRejectsUnknownValue`、`m6CatalogReadbackAfterMoveKeepsSameSeedAndUuid`、`candidateWriterRejectsNbtSizeTrackerOverflow`、`orphanStorageNeverAdopted`；testmod fault mixin 注入 catalog atomic move 後讀回失敗，Java `M6CatalogProbePhases` 與 PS `catalog-readback` 在**本 task**已有 executable phase/receipt，不等 Task 12。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.collapse.CollapseAuthoritiesTest -Label g4-red` 具名 FAIL；codec/authority 後 `g4-green-a/b`＋`-Suite Probe -Chain catalog-readback` PASS，第一次 v1→v2 envelope 與重啟多窗口分開驗。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；未經 catalog checked，不可呼叫物化。

### Task 11：new U 的長期 root seed 與原版生成設定 owner

**Files:** Modify `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`、`src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`、`src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`src/testmod/resources/fabric.mod.json`、`scripts/verification/m6-universe-probe.ps1`、`scripts/verification/run-all.ps1`；Create `src/main/java/dev/quantumchamber/mixin/ServerWorldUniverseSeedMixin.java`、`src/test/java/dev/quantumchamber/universe/UniverseRootSeedOwnerTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6RandomProbePhases.java`。
**Interfaces:** `rootSeed(server, worldKey, descriptor)` 只在 exact schema2 owner 且 live instance 匹配時回 frozen seed；`ServerWorld.getSeed` 的建構期與之後都一致；無 match 完全原版。factory 取 provider resolved options，不修改全域 save properties/registry；新 U randomSequences 用自己的 persistent state。
- [ ] 承接Task7 review M-2：首次新增enabled chain時，同步擴充正式run-all的manifest/summary/receipt oracle與正反例，逐mode/schema/chain/phase核對；保留既有DIAGNOSTIC歷史語意，不延到Task12才補。此owner補正已經Task7兩席獨立review確認，隨本task雙GREEN/full及fresh review驗收。
- [ ] RED `newUniverseGetSeedChangesAfterConstruction`（現行全域 seed）、`noiseStructureBiomeUseFrozenRoot`、`twoUniversesDoNotShareRandomSequences`、`legacySchemaOneStillUsesSaveSeed`；以 Task 7 native descriptor 實證構造器直接讀與 worker 建 noise 的窗口。
- [ ] `m6-focused.ps1 -Suite Probe -Chain native-seed-functional -Label g5-red` 在 `expectedMode=FUNCTIONAL` 下具名 FAIL；窄 owner/mixin 後 `g5-green-a/b`、GameTest `m6_universe_seed_owner` PASS。Task 11 修改 Java dispatch 與 PS oracle，不重判 Task 7 的 `DIAGNOSTIC` root；另 probe 同 server 兩 schema2 U 並行、schema1/canonical 控制組無 context、constructor 失敗 `finally` 清理及重啟後 seed 相同。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；任何原生 seed 消費者仍讀全域時切片②不得結案。

### Task 12：random NEW 的 collapse／restart 端到端

**Files:** Create `src/main/java/dev/quantumchamber/candidate/M6CandidatePolicySelector.java`；Modify `src/main/java/dev/quantumchamber/superposition/SuperpositionSession.java`、`src/main/java/dev/quantumchamber/candidate/CandidateLedgerService.java`、`CandidateResolver.java`、`CandidateDoorInteraction.java`、`src/main/java/dev/quantumchamber/collapse/DestinationResolver.java`、`CollapseAuthorities.java`、`CollapseService.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6RandomProbePhases.java`、`src/testmod/resources/fabric.mod.json`、`scripts/verification/m6-universe-probe.ps1`、`run-all.ps1`、`README.md`、`.github/workflows/build.yml`、`ci-rule-check.ps1`。
**Interfaces:** `M6CandidatePolicySelector.forNewSession(MinecraftServer)` 僅在 Task 10 catalog authority 與 Task 11 backend/seed owner ready 且經兩次 GREEN/full 後，令**新** `SuperpositionSession.freezeCandidateContext(server,source,selector)` 取 policy1/family0；舊 session 的 frozen Context 不變。`CandidateResolver.resolve(DoorKey,CandidatePolicySnapshot,List<UniverseDiscoveryRecord>,Supplier<SourceGenerationSnapshot>) -> QuantumCandidate` 由 `CandidateLedgerService` 傳 lazy supplier，且只在 bucket 真為 `NEW` 時求 `SourceGenerationSnapshot(long rootSeed,GenerationSettingsSnapshot settings)`；`SOURCE`／`EXISTING` 即使來源 preset 未知仍沿原路可用。frozen policy1 intent→checked COLLAPSING→allocation2→catalog2→world，同 token 重試；probe chains `intent-allocation`、`partial-overworld`，`catalog-readback` 已由 Task 10 擁有。
- [ ] RED `formalEntryFreezesPolicyOneAndSourceSnapshot`、`sourceAndExistingDoNotResolveUnknownPreset`、`randomNewUsesFrozenSeedAcrossRestart`、`existingLegacyNewNeverBecomesRandom`、`catalogBeforeWorldStorage`、`unknownProviderSealsWithoutReplacement`；由真正式入場與 candidate batch 產生收據（不是手工 record），cross-JVM crash 比較同 UUID/seed/profile/storage，playerdata limitation 另保留。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_random -Label g6-red` 具名 FAIL；最小接線後 `g6-green-a/b` 加 `-Suite Probe -Chain intent-allocation`、`partial-overworld` PASS；Task 12 修改 Java/PS phase table 並讓 `run-all` 與**新增 Windows CI native job**強制所有已啟用 chain/receipt/restore，Ubuntu只驗支援 cases 與非Windows拒絕。②只驗 seed/profile，不要求 portal/respawn no-leak。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality，完成切片②自動驗收；人工清單最後統一執行。

## 切片③：same-seed fresh、mutation、registry

### Task 13：policy2 的 same-seed fresh

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/CandidateResolver.java`、`NewUniverseIntent.java`、`M6CandidatePolicySelector.java`、`src/main/java/dev/quantumchamber/universe/UniverseProfileRegistry.java`、`src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6UniverseRecoveryProbe.java`、`scripts/verification/m6-universe-probe.ps1`；Create `src/testmod/java/dev/quantumchamber/gametest/M6ProfileProbePhases.java`。
**Interfaces:** 同一真實入場 `M6CandidatePolicySelector.forNewSession` 只對**新 session**啟用 policy2/family0，固定 random/same 各一半，same 複製 source root seed 與**來源**snapshot，新的 UUID/空 storage；舊 policy1 Context 不升版。
- [ ] RED `formalEntryFreezesPolicyTwoAndSourceSnapshot`、`sameSeedFreshCopiesSettingsNotBlocks`（原始相同坐標 terrain/biome/structure，玩家建築不在新 U）、`policyOneNeverRechoosesSame`、`unknownSourcePresetRejectsNewOnly`、`freshUniversesDivergeAfterEdits`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_same_seed -Label g7-red` 具名 FAIL；完成後 `g7-green-a/b`、`-Suite Probe -Chain same-seed-restart` PASS，來源 normal/amplified 分別驗。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；再開 policy3 前須可追溯 policy2 的獨立結果。

### Task 14：policy3 的 normal↔amplified mutation

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/CandidateResolver.java`、`M6CandidatePolicySelector.java`、`src/main/java/dev/quantumchamber/universe/UniverseProfileRegistry.java`、`universe/minecraft121/Minecraft121ServerWorldFactory.java`、`src/test/java/dev/quantumchamber/universe/GenerationSettingsSnapshotTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6ProfileProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`。
**Interfaces:** 同一真實入場 selector 只對**新 session**啟用 policy3/family0、三類等量；toggle 只翻轉 target Overworld preset，Nether/End snapshot 沿 source；舊 policy1/2、既有 target fingerprint 不改。
- [ ] native gate 證 1.21 amplified preset 與 normal 都能獨立建 world。RED `formalEntryFreezesPolicyThreeWithoutChangingOlderSessions`、`toggleChangesOverworldNotNetherEnd`、`sameSeedAmplifiedTerrainDiffersReproducibly`、`oldPolicySnapshotsRemainFrozen`、`providerChangedOutputFailsClosed`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_profile -Label g8-red` 具名 FAIL；窄改後 `g8-green-a/b` 與 `-Suite Probe -Chain profile-restart` PASS，不以重抽修錯。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；完成切片③自動驗收，②③ portal／死亡限制仍明列。

## 切片④：完整 family、routing 與同 role 艙

### Task 15：family catalog、legacy migration 與 exact role identity

**Files:** Modify `src/main/java/dev/quantumchamber/universe/UniverseDefinition.java`、`UniverseRecord.java`、`UniverseRegistry.java`、`UniverseRegistryState.java`、`UniverseRoleResolver.java`、`src/main/java/dev/quantumchamber/collapse/CollapseAuthorities.java`、`src/test/java/dev/quantumchamber/universe/UniverseRegistryTest.java`、`UniverseDefinitionTest.java`、`src/test/java/dev/quantumchamber/collapse/CollapseAuthoritiesTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`scripts/verification/m6-universe-probe.ps1`；Create `src/testmod/java/dev/quantumchamber/gametest/M6LegacyProbePhases.java`。
**Interfaces:** `MIGRATED_SCHEMA1` 的舊 Overworld 五欄／SHARED_SAVE_SEED、原 descriptor digest 不變；新增兩 role 四欄／EXPLICIT_ROOT_SEED，`TargetSettingsSnapshot` checked；`validateMigratedLegacyOverworld(server,record,liveWorld)` 比對實際 canonical/live generator closure。
- [ ] RED `migratedLegacyRejectsChangedLiveGenerator`、`legacyNormalAndAmplifiedMigrateExact`、`unknownLegacyPresetOnlyBlocksFamilyMigration`、`oldReceiptAcceptsOnlyDigestEquivalentMigratedRecord`；cross-JVM `legacy-preset-change` 於 seed 相同但 preset normal→amplified 與反向都拒絕，未遷移 schema1 照 M5。
- [ ] `m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.UniverseRegistryTest -Label f1-red` 具名 FAIL；實作後 `f1-green-a/b`、`-Suite Probe -Chain legacy-preset-change` PASS，已 live U 不重建/重綁/覆寫，canonical Nether/End 無關變動不誤當 Overworld 改變。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；不能以 migration 改寫舊 chunk 或已取消 receipt。

### Task 16：三 role storage、randomSequences 與 End state owner

**Files:** Modify `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`、`src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`、`UniverseRuntimeRegistry.java`、`src/main/resources/quantumchamber.mixins.json`、`src/testmod/resources/fabric.mod.json`（註冊 `fabric-gametest`）、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`scripts/verification/m6-universe-probe.ps1`；Create `src/main/java/dev/quantumchamber/universe/UniverseEndStateStore.java`、`src/main/java/dev/quantumchamber/mixin/ServerWorldUniverseEndStateMixin.java`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6FamilyProbePhases.java`。
**Interfaces:** `materialize(UniverseId,DimensionRole)` lazy，owner 以 UUID/role 對應獨立 region/entities/POI/random_sequences；U:END dragon/gateway data 存 `UniverseEndStateStore`，canonical End 原讀寫不變。
- [ ] RED `threeRolesHaveSeparateStorageAndRandomSequences`、`endStateIsPerUniverse`、`canonicalDragonStateUnaffected`、`partialRoleStorageRecoversSameKey`。native gate 補 End `getDragonFight/setDragonFight`、saveLevel、gateway 的可攔截時序，global clock 與背包不改。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_storage -Label f2-red` 具名 FAIL；窄 owner/hooks 後 `f2-green-a/b`、`-Suite Probe -Chain partial-role` PASS。目錄 orphan 或 owner 不符停服/fail closed，不刪世界。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality；沒有 role world 就不可先開 portal。

### Task 17：exact role lifecycle 與玩家重啟登入

**Files:** Modify `src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`、`UniverseMaterializationService.java`、`UniverseRuntimeRegistry.java`、`UniverseTransferService.java`、`src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6FamilyProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`；Create `src/main/java/dev/quantumchamber/mixin/PlayerManagerUniverseLoginMixin.java`。
**Interfaces:** saved player world key→catalog `(U,role)`→先物化 exact role 再 vanilla login world lookup；unknown/corrupt key 保留 playerdata 並 fail closed，不 spawn canonical。
- [ ] RED `loginMaterializesExactRoleBeforeLookup`（U:Nether／U:End 各一）、`corruptRoleRefusesCanonicalFallback`、`staleLiveWorldIdentityRejected`、`restartKeepsEntityAndPoiOwnership`。
- [ ] `m6-focused.ps1 -Suite Probe -Chain role-login -Label f3-red` 具名 FAIL；修後 `f3-green-a/b`、GameTest `m6_universe_family_login` PASS，saved playerdata 最後一次成功寫入限制另留。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 18：Nether portal family routing

**Files:** Create `src/main/java/dev/quantumchamber/universe/DimensionFamilyRoutingService.java`、`src/main/java/dev/quantumchamber/mixin/NetherPortalFamilyMixin.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6FamilyProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`。
**Interfaces:** `resolveTarget(ServerWorld source, DimensionRole targetRole)` → exact same U world 或拒絕；canonical source 走原版。`NetherPortalBlock.createTeleportTarget` 的 key 與 8:1 direction 均按 role，而非 singleton equality。
- [ ] RED `netherPortalNeverLeavesFamily`（玩家/非玩家、U1/U2）、`netherEightToOneOnlyWithinFamily`、`missingRoleDoesNotFallBackCanonical`、`canonicalPortalStillVanilla`。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_nether -Label f4-red` 具名 FAIL；修後 `f4-green-a/b`、cross-JVM `-Suite Probe -Chain nether-route` PASS，找不到目標時來源 entity 保留。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 19：End portal、gateway、credits 與 dragon 終局

**Files:** Modify `src/main/java/dev/quantumchamber/universe/DimensionFamilyRoutingService.java`、`src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6FamilyProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`；Create `src/main/java/dev/quantumchamber/mixin/EndPortalFamilyMixin.java`、`EndGatewayFamilyMixin.java`。
**Interfaces:** U:Overworld→U:End，End exit/credits→同 U:Overworld；gateway 仍在同 U:End 自動建出口，dragon 終局持久化歸 U；canonical 行為不變。
- [ ] RED `endPortalAndExitKeepFamily`、`gatewayKeepsOwnEnd`、`dragonCreditsReturnToOwnOverworld`、`nonPlayerEndPortalKeepsFamily`、`canonicalEndStillVanilla`；native gate 核 `EndGatewayBlockEntity.getOrCreateExitPortalPos` singleton 判斷、credits caller。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_end -Label f5-red` 具名 FAIL；修後 `f5-green-a/b`、`-Suite Probe -Chain end-restart` PASS，U1/U2 dragon/gateway/canonical 資料不互覆。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 20：目前 U 的 bed／anchor／fallback respawn

**Files:** Create `src/main/java/dev/quantumchamber/universe/RespawnRoutingService.java`、`src/main/java/dev/quantumchamber/mixin/ServerPlayerUniverseRespawnMixin.java`；Modify `src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6FamilyProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`。
**Interfaces:** `resolveRespawn(ServerPlayerEntity player, UniverseId currentUniverse, DimensionRole deathRole)`→有效 bed/anchor 同 U 原版目標；無效/耗盡→U:OVERWORLD；未知 authority fail closed。canonical 仍原版。
- [ ] RED `invalidAnchorFallsBackWithinUniverse`、`validBedAndAnchorStayInOwnFamily`、`deathInEndOrNetherKeepsUniverse`、`reconnectAfterDeathDoesNotFallBackCanonical`、`canonicalRespawnUnchanged`；核 `getRespawnTarget` 兩條 getOverworld fallback。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_respawn -Label f6-red` 具名 FAIL；修後 `f6-green-a/b`、`-Suite Probe -Chain respawn-restart` PASS，playerdata IOException 未證持久時依最後保存位置，不假造保證。
- [ ] compile→code commit→兩次 clean HEAD focused＋full→fresh spec／quality。

### Task 21：同 role 量子艙、舊收據與 capability gate

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/M6CandidatePolicySelector.java`、`CandidateDoorInteraction.java`、`CandidateResolver.java`、`src/main/java/dev/quantumchamber/collapse/CollapseService.java`、`DestinationResolver.java`、`CollapseAuthorities.java`、`PassageTransferPolicy.java`、`ProjectionProtectionIndex.java`、`src/main/java/dev/quantumchamber/universe/UniverseTransferService.java`、`src/testmod/java/dev/quantumchamber/gametest/M6FamilyGameTests.java`、`M6UniverseRecoveryProbe.java`、`M6FamilyProbePhases.java`、`scripts/verification/m6-universe-probe.ps1`、`src/test/java/dev/quantumchamber/collapse/ProjectionProtectionIndexTest.java`。
**Interfaces:** 同一真實入場 selector 在 Task 16–20 no-leak gate 後只對**新** Context 凍結 `FamilyCapabilityVersion=1`；NEW intent capability 必一致，SOURCE/EXISTING 讀 Context。只在此版本開 vanilla Nether/End Origin 的同 role SOURCE/NEW/EXISTING。目的 projection 同 XYZ 且 inert，passage 原有限玩家／frozen cohort／無正規回程；legacy schema3/4 Nether/End receipt 仍取消。`ProjectionProtectionIndex` 正常分支用 exact catalog `(U,role,worldKey)`；catalog／ledger 不健康時，對**已有且 runtime exact-owned** 的 world，以 `UniverseKeys.world(U,role)` 驗完整 namespace/UUID/role key，再查同 role Origin shell 作保守保護，不以 `/overworld` suffix 或空 catalog 猜另一個 world；unknown/stale owner fail closed，canonical/其他 role 不誤保護。
- [ ] RED `sameRoleNetherAndEndProjectionAtSameXYZ`、`oldNetherReceiptStillCancels`、`newSourceOpensNormally`、`newExistingRechecksEligibility`、`projectionNeverActivatesCatalogOrigin`、`wrongRoleCannotCross`、`netherEndCompleteShellProtectedAfterLedgerLoss`、`corruptLedgerRestartDoesNotRewriteInterior`、`catalogUnreadableKeepsShellFailClosed`、`otherRoleAndCanonicalNotOverprotected`。fixture 分別含 Nether/End COMPLETE、ledger 遺失與損壞、重啟、catalog 不可讀；projection tests 不只正常同 XYZ。
- [ ] `m6-focused.ps1 -Suite GameTest -Batch m6_universe_family_chamber -Label f7-red` 具名 FAIL；修後 `f7-green-a/b`、`-Suite Probe -Chain legacy-new-role` PASS，WorldKey/role/projection ledger/live instance exact 比對。
- [ ] Task 16–20 的 role owner、portal 與 respawn no-leak gate 已各自通過後，才在本 task 的 code commit 讓**新建** Context 凍結 capability 1；本 task 的兩次 clean HEAD focused＋full 驗此值與同 role 通道。既有 0 不升版；再交 fresh spec／quality review。

## Milestone 收尾（與 code task 分離）

### Task 22：整合驗證、人工清單與文件真實性

**Files:** Create `docs/manual-acceptance/2026-10-24-m6-manual-smoke.md`；Modify `docs/implementation-notes/2026-10-24-m6-universe-generation.md`、`scripts/verification/README.md`、`AGENTS.md`（接手入口）；必要獨立結果文件均 tracked，SDD ledger ignored。
**Interfaces:** 自動證據索引 `code SHA → focused兩root → full root → CI run → reviewer`；人工 smoke 每項操作／預期／結果分欄，不用自動 PASS 代替實玩。
- [ ] 完成四段白話人工清單，每項把「項目＋做法＋預期」同列：①多人同時找 stronghold／fortress／End city 並正常存退；②連續 NEW 看到不同地形且重進相同；③same-seed 新世界無舊建築、mutation 地形不同；④同 U Nether 8:1、End 入口／gateway／dragon／credits、床／錨有效與失效、Nether/End 艙同 role 同 XYZ。每段預留實際版本、世界名、截圖/receipt 與操作者；若經具體授權代操作，先核遊戲前景焦點、只讀當次新 log，進世界後 15 秒內確認畫面與世界識別；未授權就交清單，不寫通過。舊 Claude 記憶僅供工作方式參照，不是代操作授權或 M6 狀態證據。
- [ ] 於最終乾淨 code HEAD 跑 `pwsh -NoProfile -File scripts/verification/run-all.ps1`，核 `END ALL exit=0`、所有 M6 probe/GameTest、原 M5/legacy/main-only、release/sources、排序 WARN/ERROR 與 Windows／Ubuntu CI。無未歸屬差異；失敗用新 root 修正並走 fix round。
- [ ] 必需人工 smoke 完成並寫 tracked note 後，交 task／whole-branch 全新 spec＋quality review；C/I 清零、docs truth、正常 push feature、雙平台 CI 讀回。M6 `main --ff-only`、tag 與 worktree 退役仍另守門檻，不借 M5 的一次授權；純 docs commit 只 `git diff --check` 並註明採計 code/CI SHA，不為同 code 重跑 full。

## 五項自查索引

Spec §1/7→Tasks 1–6，§2–4→Tasks 7–14，§5→Tasks 15–20，§6→Task 21，§8→每 task protocol＋22。所有 Review Focus 各有具名斷言。跨 task 介面由 file map 固定；原版 native 注入點保留 Task 7/16/18/19/20 的先驗 gate，未當成已驗 API。計畫不含本輪授權外玩法；若 native gate 證固定 1.21 無法實現某個 binding 契約，停止該 task、具名回報 spec 修訂與獨立 review，不能用不受控 fallback 替代。
