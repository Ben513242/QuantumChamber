# M6 宇宙生成多樣性與完整維度家族 Implementation Plan

> **給執行 agent：** 必須使用 `superpowers:subagent-driven-development` 逐 task 執行，以 `- [ ]` 追蹤。每個 task 的 implementer 與獨立 spec／quality reviewers 使用不同 context。

**Goal:** 在保留 M4／M5 既有收據與世界語意下，依序完成驗證腳本安全、三種原版結構生成競態防護、持久隨機與同種子生成、完整 Overworld／Nether／End 家族及其路由與重生。

**Architecture:** frozen candidate intent → checked allocation/catalog → exact runtime world identity。新 `SettingsSnapshot`、profile registry 與每 U root seed 是新生成的持久來源；legacy schema1 保持舊路徑，family 階段才依 provenance 做 checked 遷移。原版 singleton world 假設只在 exact custom family 邊界攔截。

**Tech Stack:** Minecraft 1.21、Yarn `1.21+build.9`、Fabric Loader `0.17.2`、Fabric API `0.102.0+1.21`、Loom `1.7.4`、Java 21、Gradle 8.8、PowerShell 7；JUnit／Fabric GameTest／跨 JVM probe。

**Spec:** `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md`（已獨立審查採用v1.3，SHA256 `5CA4C3058C2E0564A7EF8979427C4C5A6DD3FE5B9ED8FBFF999BCCC0E53BC4A9`）。

狀態（2026-10-29 Task8B2-C1b B3）：Task1–7、8A、8B1、8B2-G、C1a、C1b-B1/B2/B3局部資料切片完成；B3 code530491b三sampler橋接具名RED、同乾淨雙focused70/70、完整run-all與獨立spec/quality C/I/M0通過。C1c template安全DTO/來源/typed NBT的v2設計獨立C0/I0/M0，可接正式實作；C1d三role/provider/resolve、100ms live hold I-1、8B3三bundle、Task8終局、Task9–20/人工仍待。B3不冒完整三role/FP/snapshot；舊v4父FAIL/B27FAIL不改判。

採用紀錄（2026-10-26；歷史r3/r4）：r3平台排程由fresh reviewer 4F03E40E…判C0/I0/M0；v1.2／r4窄差異由E9FCC770…判C0/I0/M0。r3 runtime硬gate完整保留，RecipesUsed新增完整raw/typed-history與必要controls；正文依已審草案採用，只有標題、採用狀態及spec引用更新。當時native資料gate仍未通過，沒有8B放行；現況以上方2026-10-28 B28結果為準。

本次v1.3採用紀錄：草案SHA `0E14C72680E4F9728694A4BDD927E13E7F833351872CD00128D552EB67DF4E20`、fresh review SHA `3E61305D9BE716F29C4249F8EB59D366AB468298FA6574F983BA14C5033987D5`。只修M1「其餘四鍵」為「其餘三鍵」，其餘已審語意保留，分置spec與本Task8節；當時所有unchecked gate仍未通過；現況以上方2026-10-28 B28結果為準。

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
| snapshot／policy | `src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`、`UniverseProfileProvider.java`（新建）；`src/main/java/dev/quantumchamber/candidate/SourceGenerationSnapshot.java`、`M6CandidatePolicySelector.java`（新建）；`candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`CandidateLedgerService.java`、`NewUniverseIntent.java`、`superposition/SuperpositionSession.java`、`persistence/CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionJournalStore.java`; `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java` | `UniverseProfileRegistry.register(provider)`、`freeze()`、`createSnapshot(...,lease)`、`resolve(snapshot,lease)`；`SourceGenerationSnapshot(long rootSeed, GenerationSettingsSnapshot settings)` 僅在 NEW 分支求值；`M6CandidatePolicySelector.forNewSession(server)` 只在已驗後端 ready 時回 policy1/2/3/capability1，舊 frozen Context 不改。 |
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
| GitHub `windows-native`，90 分鐘 | Java 21、repo `gradlew.bat` 經 tracked `m6-ci-gametest.ps1 -Platform Windows` 跑 M6 JUnit/GameTest；Task8B3新增獨立 `m6-settings-mapping-probe.ps1`，以production-mode KnotServer與remapped main/testmod在新root跑同canonical fixture。既有 build/default/M5/Main-only維持；mapping run有testmod，不冒稱main-only。 | build/default 30、M5 20、M6 15、獨立 Main-only 7、settings mapping 7、oracle 5、upload 5、保留 1；總上限仍90。mapping step內準備至多2分鐘、JVM180秒＋合作取消grace60秒、oracle/緩衝1分鐘；只有足夠剩餘時間可正常close才launch，不在持Session時靠外層timeout收尾。`if: always()` 上傳XML、scope、原件與三類證據；超預算保留FAIL再review，不自動加時。 |
| **Task8B3新增** GitHub `settings-canonical-compare`，15分鐘 | Ubuntu離線比較job，`needs: [build, windows-native]`且`if: always()`；下載兩named與Windows intermediary三份bundle，同code SHA／fixture／adapter契約、exact六role及兩family檔案集合，逐byte比較。缺任一bundle或前置job失敗必FAIL，不當skip或成功。 | checkout/setup 2、download 5、strict比較3、always-upload結果與已有原件3、保留2；只讀artifact資料，不執行其中程式。比較器串流並套用已審解壓／總bytes上限。 |
| **新增** GitHub `windows-m6-native`，90 分鐘 | `on: push` 目前無 branch filter，`codex/m6-universe-generation` 的正常 push 會觸發；`actions/setup-java` Temurin 21，tracked `m6-universe-probe.ps1 -CiAll -Gradle <workspace>/gradlew.bat` 串所有已啟用 Windows native chains。PowerShell 7 的 `Win32_Process`／`java.exe` 與 local fixed NTFS 前提先查；每 chain 真實 Java phase、stop／crash 和 exact restore；只在 Windows 跑，不借 M5 CI。 | setup 10、native 60（phase 3、idle 90 秒）、summary/oracle 5、`if: always()` artifact upload 5、保留 10。Job/step timeout 不得在 move/restore 中強制中斷；PS 自管 deadline 並在 finally 復原，再回傳 FAIL。若初輪計量超預算，保留 root/時間證據、修 plan 與 review，不盲目加長。 |
Task 3 Windows CI main-only 補充（2026-10-25，獨立 plan review C0/I0/M0）：使用薄 `m6-ci-main-only.ps1` 沿用既有 `gates.ps1 -Mode Main`（240秒＋既有60秒grace、finally exact restore）及 `main-oracle.ps1 -Gate`，在獨立7分鐘step保存該CI同SHA、Main scope、三層／stop／restore與原件hash receipt，always-upload；不得借本機full或GameTest冒充。CI新鮮checkout先驗沒有既有world；本機run-all保留原Move/Restore語意且Main只跑一次。Ubuntu不新增Main-only，Task12 native chains job仍另啟；若實測超預算保全原件再review，不靠外層強停move/restore。



CI 的 Gradle 來源：CI 只用該 checkout 的 `gradlew`／`gradlew.bat`，先核檔案、wrapper distro 與 `java -version=21`；本機用 AGENTS 固定 Gradle 8.8 絕對路徑或存在的 repo wrapper，記實際 resolved 路徑。新增 native job 的 `if: always()` artifact 包括每 phase `phase.json`／`final.json`／`halt.json`、preflight/restore/hash manifest、server stdout/stderr/latest.log、GameTest XML、`summary.json` 與 SHA256 manifest；失敗仍上傳**已有** root，缺 mandatory receipt 使 job／`ci-rule-check` FAIL。成功採計需 exact commit SHA、所有 mandatory chain/phase `expectedMode`、實際 stop、無 orphan Java／session.lock、逐檔 restore hash 相同。`ci-rule-check.ps1` 與 `run-all.ps1` 按平台／已啟用階段比較集合，不把 Ubuntu 合法排除當成 Windows PASS。新增 M6 CI step/job 名稱與 artifacts 由 Task 3／Task8B3／Task12 的 `.github/workflows/build.yml` 明列；Task8B3的三bundle／比較結果是Task8完成門檻，不等Task12才驗，不得只改 README。既有 Windows／Ubuntu job 90 分鐘上限維持，時間分配是待實測的初值。

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

### Task 8：完整設定資料 snapshot、provider 與 context

本task分為8A與8B1–8B3有依賴的風險批次；不是用改名省略門檻。8A為原生探索／方案 gate，沒有production。8B1–8B3各有candidate compile、真RED、同clean雙focused、完整run-all與全新spec/quality；C/I未關不進下一批。8B3 final code的完整full同時作Task8終局，沒有程式差異時不另為結果文件重跑同一full。Task8整體完成前不進Task9。8A的資料closure／budget／cold-restart與native review通過，只允許進入8B正式實作；放行紀錄須明列跨mapping／平台runtime尚未驗。該runtime比較在8B3用正式canonicalizer、共用testmod fixture與exact artifact oracle完成；三bundle任一缺失／不符不得結Task8或進Task9，不以靜態格式審核冒稱跨runtime實跑。

**核心owner（12檔）：** Create src/main/java/dev/quantumchamber/universe/GenerationSettingsSnapshot.java、UniverseProfileRegistry.java、UniverseProfileProvider.java、Minecraft121SettingsCanonicalizer.java、ServerSettingsContext.java、Minecraft121SettingsContexts.java；Create src/test/java/dev/quantumchamber/universe/GenerationSettingsSnapshotTest.java、UniverseProfileRegistryTest.java、Minecraft121SettingsCanonicalizerTest.java、Minecraft121SettingsContextsTest.java；Modify src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java、scripts/verification/README.md。

**Template資料owner（2檔）：** Create src/main/java/dev/quantumchamber/universe/Minecraft121TemplateSettingsAdapter.java、src/test/java/dev/quantumchamber/universe/Minecraft121TemplateSettingsAdapterTest.java。只管有效template authority、可重建比對與NBT typed edges；Canonicalizer管graph/encoding/budgets，context不偷做codec/lifecycle以外責任。

**正式lifecycle測試owner（7檔）：** Create src/testmod/java/dev/quantumchamber/gametest/M6SettingsGameTests.java、M6SettingsLifecycleObserver.java、M6SettingsTestHooks.java、src/testmod/java/dev/quantumchamber/gametest/mixin/M6SettingsReloadWindowMixin.java、M6SettingsJournalReadObserverMixin.java；Modify src/testmod/resources/fabric.mod.json、src/testmod/resources/quantumchamber-test.mixins.json。只在M6 GameTest scope啟用，其他run安靜，沒有production test開關/操作者UI。

**外部oracle owner（7檔＋workflow）：** Create scripts/verification/m6-settings-receipt-check.ps1、m6-settings-mapping-probe.ps1、m6-settings-compare.ps1；Modify scripts/verification/gates.ps1、m6-ci-gametest.ps1、run-all.ps1、ci-rule-check.ps1、.github/workflows/build.yml。Receipt/local/CI/full用同一strict validator；mapping wrapper只管production runtime／artifact／owned root；compare只讀三bundle逐byte核對。既有scope動態掃m6_universe，不新增固定class清單。另Modify build.gradle：gameTestM6的receipt root／startup nonce／expected head／expected coverage mode完整properties配對轉發；新增m6SettingsTestmodJar、remapM6SettingsTestmodJar及準備production runtime/依賴/namespace manifest所需的限定task。testmod JAR只包含sourceSets.testmod.output，remap named→intermediary，不nested main或依賴；放build/m6-settings-artifacts，不入build/libs。release／sources JAR與build/libs恰兩JAR的既有隔離oracle不變。只借用固定版本已核的官方server／Loader／production Fabric API、完整mapping及相依性；所有實際載入JAR（含預先產生的intermediary game cache）必在launch前存在並hash，不能用launch後生成檔倒補pre-pin。不得用未還原的JAVA_TOOL_OPTIONS、daemon舊環境或Loom named remapped_mods充當production runtime。

**介面與owner：** Provider只回immutable ResolvedSettings（三role DimensionOptions＋frozen generateStructures），不供FP bytes。Registry register拒id+version重複，freeze凍metadata/schema/adapter能力，createSnapshot/resolve需有效ServerSettingsContext lease並由canonicalizer重算。內建Parameters恰OverworldPreset string normal/amplified與GenerateStructures NBT byte0/1；缺失/錯type/其他值拒絕。三策略保留source bool；resolve已存snapshot不用live canonical bool覆蓋。Snapshot外層exact五欄、canonical NBT含欄名/值<=512、FP32byte與defensive copy。ServerSettingsContext是窄只讀lease/view；Minecraft121SettingsContexts是唯一server/epoch/inflight owner，acquire(server)或unavailable，所有consumer以exact owner驗使用，不暴露mutable server/lookup/template，不帶rootseed。Native generation controls及source採集另守r2；不能只將flag放FP而Task11仍讀global。

#### 8A：完整native資料 gate（不得先進production）

- [x] 依已審spec完整走實際options、相關sets/placements、dynamic/reloadable definitions、required/optional tags、Identifier-only/hardcoded resources、template NBT pool/loot/component等；明列支援adapter與static/runtime條件，unknown/opaque/缺required/不可重建fail closed。不得全registry/resource dump；tag保留native有效順序，僅證無序才排序。
- [x] Alias按typed來源/欄位/edge occurrence；同id同時source/target/ordinary仍展開所有真target，非alias恰有type/alias不可誤免。Template data node可encode一次，但引用遍歷按alias-context occurrence；所有可能target納入，resource→pool→template cycle/shared依typed node/ctx去重，不截邊。
- [x] 先freeze executable artifact與spec SHA，launch前後source/class/agent/dependency hash、實際class-load source exact；每次新短owned cwd。一個probe，不以REPL閉包舊artifact/label或batch後snapshot補造pin；原失敗root永留。normal/amplified六個preset/role各在clear前strict判，任何失敗不得全familyFP。
- [x] Native template有效來源須按cache→saved generated→development SNBT（適用時）→ResourceManager的真優先序核；effective getTemplate/writeNbt與可持久來源同manager.createTemplate重建後exact比。cached-only／來源壞／未知priority不拿同名vanilla fallback替代。合法temporary LevelStorage.Session fixture可於新owned storage root內調查，不建MinecraftServer/ServerWorld、不觸既有世界；需before canonical/非reparse/absent、finally Session.close、另channel lock釋放、重開新JVM可重建證據。每一批仍先向主控送有界方法/預算再放行。
- [x] 精確原生furnace／blast_furnace／smoker的RecipesUsed按spec v1.2 literal history規則，保留完整raw NBT，不建立required recipe edge；其他owner/occurrence不全域豁免。新增有界controls：同template只改history id或int值，canonical payload與FP必變；empty compound／存在性與型別保留；合法missing recipe id可原生讀入／保存，無required-missing；wrong container/value type具名拒；未知owner同名不放寬。只改無關recipe資源不得污染此history圖，必明標未驗actual RecipeManager；不得用vanilla recipe JSON冒充它。Items／BurnTime等改變同樣保留並可辨，loot/component真正target變更與required-missing原gate照跑。可擴既有MINI/成對assertions，短派工明列actual case/evaluation計數與上限，不用改名隱藏新增工作量。
- [x] 必要native控制：template payload/target定義mutation、unrelated resource不污染OW、NBT pool/loot/component target、alias交集/非alias同欄、required tag/unknown feature/processor/resource種別、tag同members不同order與native取樣、GenerateStructures true/false、static消費端有限盤點/known unsupported拒絕、逐role strict與冷JVM restart穩定性。8A同時審核canonical格式不依runtime mapping／path及consumer邊界，誠實保留跨mapping／平台未實跑狀態；實跑exact比較移至下方8B3具名gate，未完成不得結Task8／進Task9。Prototype只作資料/可行性證據，正式FP不可用DIAGNOSTIC_UNPROVEN狀態。
- [x] Context可行性先釘實際SaveLoading.refresh→factory/create、Fabric SERVER_STARTING→setup/首次journal、reload holder→tags refresh→template clear→END的時序。多個inflight不能先publish；失敗/stop失效與borrowed resource ownership明列。尚未接線不當runtime已證。
- [x] 完整vanilla reachable圖量測後給有限node/edge/resource decode/total canonical bytes/depth表與邊界/超限反例，由主控及獨立native review釘選後才8B；目前1180個全pack templates與40,024,933解壓bytes只是upperbound，不等於reachable/budget驗收。不得以unlimited tracker、只有壓縮長度或未定budget啟用可持久化provider。
- [x] 全新獨立 native re-review關閉8A的資料完整性/方法finding及有限預算；spec/plan獨立review與主控明確8B放行都必要。放行必列「資料gate已驗，跨mapping/platform runtime待8B3」，不稱已驗所有執行環境。此階段不拿repo未變的run-all冒充prototype coverage。

#### 8A7與8B2前策略：template absence／live admission（設計已採用，未實作）

使用者限定正常遊戲操作，snapshot期間無外部改模板/datapack；外部修改走reload／重啟。以下完整承接已審v2設計，schema仍snapshot五鍵／512bytes／32byte FP；`quantumchamber:m6-template-resolution-v1`是未發布首版resolved契約，不升未發布ProviderVersion。當次error拒、五態只讀與完整constructor blank定義見spec §3.1.1。舊required-template FAIL不改判。

##### 已選Model B：具體owner／writer／consumer inventory與gate

固定JAR token篩選83class再解析actual opcode/indy，28個相關callsite列missing-template-live-admission-inventory-v2.json；不是只計constant-pool引用，更不把此數量稱所有mod完整性。owner／network/dev證據見missing-template-live-owner-evidence-v2.json；process NBT copy見missing-template-process-copy-evidence-v2.json。

|來源/狀態|exact owner/API/field|normal writer與consumer同源|選定協定|
|---|---|---|---|
|server/Session|MinecraftServer.structureTemplateManager；getStructureTemplateManager；session由既有MinecraftServerDynamicWorldAccess.quantumchamber$getSession|ServerWorld.getStructureTemplateManager轉同server manager；不是自行造另一manager冒live|lease綁同server/Session/manager，server.isOnThread，epoch及inflight/stop先後核|
|ResourceManager|MinecraftServer.getResourceManager→resourceManagerHolder.resourceManager；Manager.resourceManager|MinecraftServer.method_29440 offset107 setResourceManager；Single Manager loader實際open同resourceManager|reload從開始先標inflight；apply shared gate換RM/clearcache並更epoch，END後可新lease；old RM不可偷換為另一image|
|saved|Session GENERATED normalize→Manager.generatedPath；getTemplatePath為generated/<ns>/structures/<path>.nbt|StructureBlockBlockEntity.saveStructure offset41 getter、79 saveFromWorld、88 setAuthor、101 manager.saveTemplate；packet handler forceMainThread offset9，save offset203|saved native寫由server-thread command或manager.saveTemplate shared側；snapshot server-thread不交錯。可寫目錄不因此拒|
|dev SNBT|SharedConstants.isDevelopment時StructureTestUtil.testStructuresDirectoryName/<id.path>.snbt|TestCommand.executeExport→NbtProvider.convertNbtToSnbt；executeImport reader42/output75/write87、unload138；正常command同步server-thread|server-thread窗口不交錯；release後操作，下次新capture；若同API被foreign async呼叫不在此admission內|
|cache|Manager.templates:Map<Identifier,Optional<StructureTemplate>>|getTemplate computeIfAbsent；OrBlank put；unload remove；setRM clear；FossilFeature/SimpleStructurePiece/Single/ruined portal在生成路徑可從worker取同manager|相關manager methods共同shared gate，snapshot只try排他|
|cached template內容|StructureTemplate四instance欄；normal mutator saveFromWorld/readNbt/setAuthor|saveStructure在getter返回後才改template，故只包manager方法不足；Manager.createTemplate呼readNbt；datagen validator亦readNbt但不屬live server owner|已發佈template identity綁current manager gate；三個mutator也shared，無owner association的live cache entry拒coverage|

現有H6只把PalettedBlockInfoList.blockToInfos換ConcurrentHashMap，是derived lookup cache安全，不當本次writer exclusion。Template.process offset88把NBT copy到current BlockInfo後才processor.process；place的LootTableSeed put作用在處理後資料。此為固定native已讀路徑；未知processor/writer不可因namespace而admit，若會修改原template必有adapter/gate或拒。

**已選gate設計（設計層Approved；尚未實作，8B2成功路徑前仍守策略/owner/hold budget審查）：**

1. 每exact native manager建立ReentrantReadWriteLock＋current identity binding。普通getTemplate、getTemplateOrBlank、saveTemplate、unloadTemplate、setResourceManager、createTemplate的完整body取shared/read側，nested reentrant；用具備exception-finally的wrap，而非只有HEAD/RETURN漏例外。正常getter仍可原語意填cache，snapshot不呼它們。
2. createTemplate返回及兩getter返回前，在仍持shared時登記template identity→同manager gate；OrBlank新blank亦先關聯再離開wrapper。所有live cache entry必可核此association。StructureTemplate.saveFromWorld/readNbt/setAuthor若對associated template則取其gate shared。未關聯新constructor/isolated instance可在自身owned owner建立，但不能混入live cache冒coverage。固定原生正常不共用一mutable template給不同manager；若發現多owner則TEMPLATE_OWNER_AMBIGUOUS拒，不猜一個。這是當次同步ownership，不是歷史provenance。
3. association map短monitor：template mutator只查owner後立即釋monitor，然後取shared並重驗；manager取shared後可短暫登記。snapshot不可持association monitor去取gate。鎖階序gate→短association monitor；不存在monitor→等待gate。弱identity/lifecycle紀錄於manager退休後不提前拆掉仍在worker使用的舊template gate；正式owner清理／GC策略須review。
4. snapshot先檢server-thread/epoch/inflight/stop/身份，**只writeLock.tryLock()，不排隊等待**。忙即TEMPLATE_SNAPSHOT_NOT_READY，無副作用回既有unavailable/重試路徑，不把短暫busy當永久corrupt。取得後只讀cache field/完整template資料、bounded來源reader與隔離native重建；不呼live五個禁用method、不等worker、無world副作用。finally必釋放，任error也釋放。return前核同身份/epoch/stop並完整cache/source相符。
5. 已持shared的thread再要求snapshot，不做read→write upgrade，立即NOT_READY。shared/isolated重建同thread嵌套可重入；write-owner合法巢狀只讀／lease需配對depth，每層finally釋一層，禁止未結束lease外漏。一次snapshot只碰一live manager；不得持A再取B。worker可能等server時snapshot try排他直接失敗，所以沒有server等worker閉路；exclusive期間禁止future.join/get、事件迴圈pump或調worker。
6. snapshot exclusive會短時阻擋後來的普通shared getter；snapshot成本/hold時間須量測，不能拿59秒partial prototype當遊戲server可接受延遲。策略正確性與可接受hold budget在8B2成功路徑前review釘選，未滿足便不發布；8B3再跑真worker busy、巢狀/exception、save、reload/stop接線與deadlock反例，不到8B3才發明策略。

**可通與必拒配置：**固定1.21 native manager、標準Session/generated、普通Fabric遊戲server、既有受審mixin＋本gate/accessors全命中、來源使用目前RM、正常結構方塊/command/reload的server-thread模型，無外部同時改檔及未知async writer：策略上可try-acquire成功，8B2實作/8B3 runtime仍待證。標準可寫saved/dev不必OS鎖。已知async模組直接Files寫saved/dev或直接改cached NBT且未走gate/source adapter，或者替換manager／resource讀取語意而沒有受審能力：拒，不以檔案當次不存在接納。未知任意Java行為不在本版支援承諾，不能只憑相同namespace／descriptor宣稱已admit。

**新增production/test owner計畫（本版文件採用；尚未派工實作）：**

- 新main universe/Minecraft121TemplateAccessGate.java，test universe/Minecraft121TemplateAccessGateTest.java。
- 新main mixin/StructureTemplateManagerSettingsGateMixin.java、StructureTemplateSettingsGateMixin.java、StructureTemplateManagerSettingsAccessor.java、StructureTemplateSettingsAccessor.java；更新src/main/resources/quantumchamber.mixins.json。全部前綴src/main/java/dev/quantumchamber或src/test/java/dev/quantumchamber。
- 既有Minecraft121SettingsContexts／TemplateSettingsAdapter／Canonicalizer owners接lease與readonly DTO；既有MinecraftServerDynamicWorldAccess已供Session，不新增provenance store。gate wrap若用MixinExtras完整method wrapper，其固定依賴/descriptor/exception-finally及release無testmod污染須在策略review核。必要testmod observer/fault仍依8B3原owner另派，不藉此新增production test開關。
- 8B2正式資料閉包另增四個最小support main owner：`universe/Minecraft121SettingsGraph.java`（graph/預算/編碼）、`Minecraft121RegistryClosure.java`（typed registry/tag/ref可達性）、`Minecraft121NativeValueContracts.java`（受審原生值與End契約）、`Minecraft121TemplateValueContracts.java`（template NBT pool/loot/component/RecipesUsed）；精確責任/原8A證據與JUnit映射見ignored `task-08/8b2-gate-design/SUPPORT-OWNERS.md`。B28 test-only launcher/fixture/observer/receipt不得搬入release。
- C1b-B2 End/native正式值另需三個窄Mixin `mixin/EndIslandsSettingsStateMixin.java`、`SimplexSettingsStateMixin.java`、`DensityBinarySettingsAccess.java` 與既有 `src/main/resources/quantumchamber.mixins.json` 註冊，僅供受審EndIslands/Simplex/BinaryOperation欄位封存，無production反射/AW；設計review C0/I0/M1，摘要 `task-08/8b2-c1b-b2-design/DESIGN-REVIEW.md` SHA `e96337ee4429c80476fc1fdff6aaa93825ca92ac26638b78d7d34393ea30db3e`。M1須seal入/出byte[]雙向防禦複製，只有公開endIslands(0L)重建reference須三層identity獨立，unit codec parse可能回singleton，只驗replay/原owner穩定。本列是B2實作前的設計門檻；後續acaf候選已通過M6Compile及Windows named focused的mixin命中，完整full留C1整合，intermediary/OS仍待8B3；詳下方B2狀態。
- C1b-B3 `end/base_3d_noise` 的真值為InterpolatedNoiseSampler，另增三個窄Mixin `mixin/InterpolatedSettingsStateMixin.java`、`OctavePerlinSettingsStateMixin.java`、`PerlinSettingsStateMixin.java`，窄改既有NativeValueContracts、main mixins.json與CanonicalizerTest；不含DoublePerlin或反射。獨立設計review C0/I0/M1，摘要 `task-08/8b2-c1b-b3-design/DESIGN-REVIEW.md` SHA `22fe1881f6a086018299aa034e5294ad01887afd63740bd381734571d57b2fe5`。M1：逐槽Perlin identity vector/nested seal array入參必clone，非首槽換raw相同但identity不同仍拒；補充SHA `fc9badafab5ac8e8563693b6899126f1bee192de798787a9b16d0dc41c846c27`。本列是B3實作前設計門檻；後續code530491b已通過M6Compile、Windows named的三Mixin命中/具名RED-GREEN/乾淨雙focused與完整full，詳下方B3狀態；intermediary/OS仍待8B3三bundle。
- C1c template資料的已審v2 owner限 `universe/Minecraft121TemplateValueContracts.java`、`Minecraft121TemplateSettingsAdapter.java`、窄改`Minecraft121RegistryClosure.java`（非空pool→exact Single）、`mixin/StructureTemplateManagerSettingsAccessor.java`、`StructureTemplateSettingsAccessor.java`與main mixins.json、兩JUnit `Minecraft121TemplateSettingsAdapterTest.java`／既有`Minecraft121SettingsCanonicalizerTest.java`。live Mixin消費端先驗matching exclusive lease後直接產深owned DTO，禁止public raw Map/List/Template/Either-right/RM/Path；Single location優先exact class/source＋CODEC.codec() left roundtrip，Legacy/subclass/right拒，不增第三raw accessor。RecipesUsed key只須Identifier.of可解析且保原始key/NBT type/int，不建required recipe edge。v1設計C0/I2/M1；v2獨立窄複審C0/I0/M0，摘要 `task-08/8b2-c1c-design/V2-DESIGN-REVIEW.md` SHA `5ff85b467e8112af67966e2266f161472697cf077b141e75e8422050bf2786fe`；本列為當時實作前設計門檻，後續T/A已按下列結果完成compile/RED/GREEN/full；100ms live hold I-1仍待。
- C1c-A隔離擷取依spec §3.1.1的私有`ISOLATED_OWNED`例外，先獨立窄審C/I0才實作。仍限原八owner，ManagerSettingsAccessor僅增`dataFixer:Lcom/mojang/datafixers/DataFixer;`與`blockLookup:Lnet/minecraft/registry/RegistryEntryLookup;`兩shadow供safe consumer內部使用；公開只收permit/當次Session/owned值，只回深owned DTO，不提供raw getter或withFields/callback/factory。隔離ctor必用live/context exact RM/Session、live原生DataFixer/原filtered BLOCK lookup相同identity；filtered.getBase()==context BLOCK read-only base，Context.templates綁server實際manager（8B3驗正式發布），不解讀opaque predicate或重建等值wrapper，僅createTemplate或新constructor blank＋四欄/writeNbt，前後核source/cache/epoch，finally失效權威並清除登記；不取得第二exclusive、不改Gate跨manager拒絕。RED涵蓋四來源錯配、外部isolated/共享identity、closed/wrongthread、前後漂移、零palette與owned DTO解鎖後mutation；T兩Minor補history key-only/empty-vs-absent及effective component payload。完整run-all與fresh spec/quality在A整合候選執行；100ms live hold I-1仍open。
- C1c-T code`d0bf69b`已完成typed NBT局部契約：乾淨M6Compile、雙focused20/20、獨立spec/quality各C0/I0/M1。C1c-A code`bf46e97`已完成來源／隔離native rebuild／exact Single局部閉包：乾淨M6Compile、Adapter54/54×2、Canonicalizer76/76×2、full `run-all-55870333f3bb422b9f53535574e302ec` END ALL0/JUnit838 f0e0 skip2；獨立spec C0/I0/M0、quality C0/I0/M1。首次full長cwd前置FAIL及RED／中途FAIL保原root。結果見ignored `task-08/8b2-c1c-a-evidence/FINAL-RESULT.md`、`FINAL-RESULT-HASHES.json`與`ADMISSION.md`。本機CI oracle不等於遠端CI。C1d先做完整owner census及typed leaf/consumer白名單；BLOCK/ITEM/PROCESSOR_LIST/LOOT_TABLE等leaf、Vault/TrialSpawner/entity、完整OW973/N210/E20、three-role FP/provider/snapshot與100ms live hold I-1仍待。
- C1d-D1b static leaf（BLOCK／ITEM）另增兩個owner：main `universe/Minecraft121RegistryLeafContracts.java`（BLOCK／ITEM leaf payload、唯一`requireNativeSource`、vanilla feature子集與純函式seam）與test `universe/Minecraft121RegistryLeafContractsTest.java`；窄改既有`Minecraft121RegistryClosure.java`（expandDefinition的BLOCK／ITEM分支經definitions去重、nativeSource委派）與`Minecraft121SettingsCanonicalizerTest.java`。不改Graph／TemplateValueContracts／Adapter／NativeValueContracts／Contexts／Gate／mixin／build／scripts；NativeValueContracts的靜態原生值責任不含BLOCK／ITEM leaf。依據ignored設計`task-08/8b2-c1d-design/D1B-PROPOSAL-v2.md` SHA256 `4efd44fc691c51d5badb28f00744e3b8be1eb5f05e1db54ef225a38f423defa7`，窄複審`D1B-DESIGN-REREVIEW.md` SHA256 `adf099ca5b99d968d273f2ccf4e53c8a8c66988642d02f13d44334bc9b290766`（C0/I0/M5放行）。
- 8B2 gate設計獨立review C0/I1/M1：固定JAR九method descriptors與MixinExtras快取類別靜態核實；I-1為單次live exclusive hold尚無量測，候選100ms只作fail-closed硬上限。可先實作gate/RED/計時與超限拒，normal/amplified三role、present/absent、saved/dev/resource與worker busy的單次hold原件及獨立複審通過前不得發布成功snapshot；超限須縮短持鎖工作並重審，不調高上限湊PASS。弱identity/retired gate與鎖順序列實作review。設計review摘要 SHA `b48713c05b1bf4a3a76e310a7cd71764e8e7fbe2f24f86d586c66e7d65882013`。

##### A7：已採用固定操作／計數（未執行）

38個top-level，G40／有效component輸出O14／單node diagnostic D2／adapter attempts A42／owned native manager上限M68。1Context/1Session/1JVM；不是68個Context。#11/12整圖一旦unknown use失敗，一律O=0；各只許D=1保存成功Single node的診斷bytes，檔名diagnostic-node，無FP/完整component/accepted，不得拿D當合法圖。無論先後，unknown拒與原圖不可發布都必斷言。

表內I為額外fixture/template IO quota（不含該列12*A的解析IO quota）；N為額外native template setup/observation操作quota（不含16*A解析操作quota）。這些是精確分配的hard ceiling，不是未跑先報實際呼叫數。每列失敗／setup也計，不另加隱藏evaluation。

|#|固定fixture／assertion|預期結果／碼|G|O|D|A|M|I|N|
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
|1|不可觀測cache|TEMPLATE_CACHE_UNOBSERVABLE|1|0|0|1|0|0|0|
|2|無entry＋真absence|ABSENT|1|1|0|1|2|8|2|
|3|negative＋真absence|ABSENT|1|1|0|1|2|8|2|
|4|genuine constructor blank＋真absence|ABSENT|1|1|0|1|2|8|2|
|5|其他present＋來源present exact|PRESENT|1|1|0|1|2|8|2|
|6|其他present＋來源absent|TEMPLATE_CACHE_SOURCE_MISMATCH|1|0|0|1|2|8|2|
|7|negative＋來源present且恰空|TEMPLATE_CACHE_SOURCE_MISMATCH|1|0|0|1|2|8|2|
|8|無entry＋來源present恰空|PRESENT|1|1|0|1|2|8|2|
|9|constructor blank＋來源present|TEMPLATE_CACHE_SOURCE_MISMATCH|1|0|0|1|2|8|2|
|10|孤立native absence→getTemplate→OrBlank cache變化|NATIVE_CONTROL|0|0|0|0|2|8|8|
|11|unknown先、Single後：同id|UNKNOWN_TEMPLATE_USE|1|0|1|2|2|8|2|
|12|Single先、unknown後：同id|UNKNOWN_TEMPLATE_USE|1|0|1|2|2|8|2|
|13|RM present，open丟FNF|TEMPLATE_SOURCE_READ_FAILED|1|0|0|1|2|8|2|
|14|RM present，open丟IOException|TEMPLATE_SOURCE_READ_FAILED|1|0|0|1|2|8|2|
|15|高優先saved corrupt＋低優先合法RM|TEMPLATE_SOURCE_DECODE_FAILED|1|0|0|1|2|8|2|
|16|saved存在但不可讀|TEMPLATE_SOURCE_READ_FAILED|1|0|0|1|2|8|2|
|17|固定saved父目錄readAttributes在第一次probe拋AccessDeniedException|TEMPLATE_SOURCE_UNOBSERVABLE|1|0|0|1|2|8|2|
|18|讀取中source generation變更|TEMPLATE_SOURCE_UNSTABLE|1|0|0|1|2|8|2|
|19|cache entry instance替換|TEMPLATE_CACHE_UNSTABLE|1|0|0|1|2|8|2|
|20|同instance cache內容改變|TEMPLATE_CACHE_UNSTABLE|1|0|0|1|2|8|2|
|21|lease epoch失效|TEMPLATE_LEASE_STALE|1|0|0|1|2|8|2|
|22|受支持writer-token故障變更：前後bytes/stat相同|TEMPLATE_SOURCE_UNSTABLE|1|0|0|1|2|8|2|
|23|已知async writer繞過gate且無受審source adapter|TEMPLATE_COHERENCE_UNAVAILABLE|1|0|0|1|2|8|2|
|24|一個空palette不當constructor blank（另記0/1 shape與NBT）|TEMPLATE_NATIVE_BLANK_SHAPE|1|0|0|1|2|0|6|
|25|非exact native template class假空|TEMPLATE_NATIVE_IMPLEMENTATION|1|0|0|1|2|8|2|
|26|blank但author非constructor值|TEMPLATE_NATIVE_BLANK_SHAPE|1|0|0|1|2|8|1|
|27|固定isolated重建回傳同一live template instance（manager為不同owner）|TEMPLATE_REBUILD_ISOLATION|1|0|0|1|2|8|2|
|28|正常absence歷史 vs error產blank後錯檔已移出：當次全absent|SAME_ABSENT_BYTES|2|2|0|2|4|16|12|
|29|error產blank但當次壞檔仍存在|TEMPLATE_SOURCE_DECODE_FAILED|1|0|0|1|2|8|3|
|30|PRESENT空模板 vs ABSENT constructor blank|DIFFERENT_PRESENCE_BYTES|2|2|0|2|2|8|2|
|31|固定TemplateId="minecraft:bad id"（空白非法）|TEMPLATE_ID_INVALID|1|0|0|1|0|0|2|
|32|固定Resolution為NbtInt(1)，其餘三鍵合法|TEMPLATE_ENVELOPE_INVALID|1|0|0|1|0|0|2|
|33|固定四鍵合法envelope另加Unexpected:NbtInt(1)|TEMPLATE_ENVELOPE_INVALID|1|0|0|1|0|0|2|
|34|固定Resolution="OTHER"，其他欄位合法|TEMPLATE_ENVELOPE_INVALID|1|0|0|1|0|0|2|
|35|固定saved=S、dev=D、RM=R，winner=S；保完整真refs|PRESENT_WITH_EDGES|1|1|0|1|2|8|2|
|36|固定saved absent、dev=D、RM=R，development=true，winner=D|PRESENT_WITH_EDGES|1|1|0|1|2|8|2|
|37|固定saved/dev absent、RM=R，winner=R|PRESENT_WITH_EDGES|1|1|0|1|2|8|2|
|38|ABSENT envelope按tag孤立constructor重建exact|SAME_ABSENT_BYTES|2|2|0|2|2|8|2|

**固定input／coverage補正：** #17只在第一次saved父目錄readAttributes probe注入AccessDeniedException，預期UNOBSERVABLE；不是其他父節點或OS ACL全測。#27只測隔離manager不同但回傳同一live template instance，預期ISOLATION拒；shared manager路線本套未另測，必由8B2 gate/owner單元測試補，不能聲稱兩者皆runtime覆蓋。#31只用空白非法id，不聲稱合法但非canonical spelling全覆蓋；#32固定Resolution=NbtInt(1)；#33四鍵合法baseline另加Unexpected:NbtInt(1)；#34 Resolution="OTHER"。其他欄位錯型如Contract/TemplateId/NativeTemplate、missing key等由8B2 strict encoder/decoder單元測試另列，不冒A7全測。每個baseline NativeTemplate由owned native constructor/writeNbt取得，不手填假payload。

#35–37各一次真正resolution：S=chest＋原生simple_dungeon loot引用、D=jigsaw＋minecraft:empty pool、R=stone palette三份不同且合法native NBT。#35 saved S/dev D/RM R，assert S及loot edge；#36 saved absent/dev D/RM R，assert D及pool edge；#37 saved/dev absent/RM R，assert R及block edge；development=true；dev路徑取原生相對目錄。依下列已審A7 startup前提，fresh專屬A7 JVM只在任何Context/Manager/Session/server/world建構前，由Main顯式一次設定SharedConstants.isDevelopment=true並保持到退出；不是自然Fabric設定、不是途中切換／還原，不進observer或B/8B3。每列另M=2，3次不是一次name比較；完整payload與其他已知refs沿原adapter保留。production development=false的NA分支是靜態與後續8B3責任，不冒稱A7三次已測。

#24固定順序：①new StructureTemplate（ctor blank 0palette）；②writeNbt A；③隔離manager.createTemplate(A)，其內另一次ctor及readNbt；④writeNbt B。共2ctor+2write+1create+1read=6個新增native觀測操作，全由N=6承擔，IO=0（memory）。assert實際重建形狀是否1空palette；fixture形狀不符則SETUP_SHAPE_MISMATCH，不能當RED/GREEN。記A/B bytes是否同及native分支，不預造RNG差異；absent guard仍只收constructor 0palette。#10只owned getTemplate empty、OrBlank及其nested getter/blank ctor，另一次writeNbt記完整native結果（5個已列native entry操作，quota8）；不偷偷另unload或解碼。#28保兩條歷史，每次rawerror/搬出保全記錄，當次全absence兩輸出同；#29錯檔仍在必拒。

**IO與native operation歸屬：**解析IO定義為TemplateSourceObservation對source stat/查RM/open的logical attempt（native loader控制以每provider的一次probe/open計），每adapter attempt<=12，A42最多504。額外fixture建立/原生getter查源/寫壞檔/保全move按表I總和264；合計新增template logical IO<=768。這不是OS syscall總數，**未量MC bootstrap的IO不包括在768且不宣稱已量**；bootstrap仍受同JVM時間/heap與新owned root約束。每次stream bytes另走原bounded reader，不能以一次open繞decode上限。

解析native template observation/constructor/write/read/decode等每attempt<=16，最多672；表N setup總和94（#24六步、#10五步有預留、#28歷史建構含失敗均在各列quota），合計<=766。M68是manager constructor個數上限，與Template constructor操作分開；codec/MC bootstrap內未量全域constructor不冒稱包含。counter在各wrapper操作前檢；nested createTemplate的ctor/read明列計帳，不以一個外層呼叫抹掉。實作準備若無法在此counter model核nested實際次數，須回root修表，不用估算值冒receipt。

每graph原isolated CaseMeter重置，case IO/N quota列內reset，JVM batch總計不重設；D亦用bounded encoder並獨立輸出bytes上限，不發布FP。原raw8MiB/tracker64MiB/total128MiB/depth128與node/edge/state/canonical限額保留；A7 owned input總16MiB、O+D bytes總16MiB/evidence8MiB。只一JVM、2G、180秒＋合作30／600秒，沿v13非阻塞start/status。未有38case成本證據，超時保留/停止不加時、不kill Session owner、不追加JVM。

#18/22是同owned model A的受控token/race fault；不是外部違約FS ABA偵測承諾。#13/14是實際Resource supplier FNF/IO；#16/17為同source IO/authority邊界故障，不稱Windows ACL驗證。genuine native資源/Session與只讀cache狀態before/after保留，finally resource views/streams close，最後Session.close＋獨立鎖。

##### A7 startup／observer窄修訂（已採用；未實作驗收）

採用A7-setup-amendment草案 SHA FB36DCC097FFD90D51E25E4DBA26B213385F7E341D28B899FDF76C7EC5E3D37E；獨立review FC7AD02FDCD59D854C2F3D19E3FB33C7B33D26A072D5061239D3E1C456291847為C0/I0/M0。這只核准測試前提／觀測／兩格quota修訂，不代表agent或A7 runtime通過；spec v1.3五態、encoding與正式正常遊戲支援域不改。

- 已核自然前提缺口：固定MC沒有isDevelopment的PUTSTATIC；固定Loader與builtin ASM patch／Hooks未找到自然設定，fabric.development不等於MC native flag。舊resource方法fixture曾暫設flag，不能拿它證此次自然配置。因此只允fresh專屬A7 JVM由集中Main在任何Context/Manager/Session/server/world建立前，明確參數A7_NATIVE_DEV_BOOTSTRAP_ONCE下單次設true，保持至退出；B及8B3不帶override。observer不設定flag，不注入假provider list。
- bootstrap順序：Knot unit-test init但不launch MC main→安裝／核16 exact hooks及單一bootstrap bridge identity/fatal空→讀flag before=false與Context attempts=0、manager/Session/server/world ctor attempts/returns全0→Main一次assignment→after=true、assignmentCount/BootstrapEnvironmentWrites皆1→才Bootstrap/Context與唯一Session。前提或coverage不符即SETUP_FAIL；class load不是instance建構證據。單次環境寫獨立列，不混N/IO或38eval。
- constructor hook必分pre-init attempt與成功post-init return：pre event只有tag／stage，不傳uninitialized-this；N或M在attempt計一次，正常return後才帶receiver身份記完成，不重複charge。非ctor在method entry計。receipt分列attempt/return／entry，#24六個charged operations不是把ctor的兩種event算兩次；失敗ctor不得冒稱完成。
- 原10 exact hook：Template ctor/writeNbt/readNbt，Manager ctor/createTemplate/getTemplate/getTemplateOrBlank/unloadTemplate/setResourceManager/saveTemplate；另三provider loadTemplateFromFile/loadTemplateFromGameTestFile/loadTemplateFromResource（同Identifier→Optional descriptor）只計logical IO；再加MinecraftServer／ServerWorld／LevelStorage.Session各自固定ctor作前置／禁止觀測。合計16 method targets，descriptor/class hash與既有靜態原件逐一核，缺／未知hook拒。server/world全程attempt=0；Session前0後最多1，仍finally close／獨立鎖。
- agent/helper僅ignored test runtime：helper JDK-only，append bootstrap search，由null loader取；Main／MC進同bridge identity，不把helper複製進main artifacts或release。transform失敗即使返回原class也先latch fatal；callback自身throw亦先latch；每次expected rejection檢fatal仍空才可PASS，native Throwable fallback不能洗掉觀測／budget錯。entry只記SETUP/RESOLVE/NATIVE_CONTROL，bootstrap另列，不改MC資料／codec結果。
- RESOLVE標exact live/isolated receiver；live get/getBlank/unload/setRM/save/create及live readNbt入口0。#10/#24提供getter/ctor/write/create/read正向witness；save/unload/setRM僅確切安裝＋零入口，不假稱正向調用。#10的N5（其中ctor attempt/return分列、只charge一次）在quota8；#24須native實證2ctor/2write/create/read共N6及原shape，不用估值代PASS。
- #35–37在各原RESOLVE attempt對isolated manager另作一次native getTemplate/writeNbt counterpart，對strict選中NativeTemplate exact；actual provider順序分別Saved、Saved→dev、Saved→dev→RM。native counterpart與strict rebuild共N9<=16，provider最多3＋strict stat/open最多4，IO<=7/12；仍各1A/1G/1O、M2，無live getter。必要return前驗證不為守7而刪，實際仍受IO12。
- #26 setup N2→1：只owned native blank constructor一次，author由fixture固定field反射改值，不呼setAuthor、不改shared static；保author guard。#29 N2→3：獨立manager用固定4byte非GZIP saved壞檔產blank（getBlank＋nested get＋blank ctor），壞檔保持存在、不與#28共manager。兩格相抵setup總94，672＋94仍766；IO768、G/O/D/A/M與時間/JVM/bytes cap不變。實際entry比預期多或shape不符就FAIL/SETUP_FAIL，回root修表，不偷延長。

##### A7 Loader admission v4 採用（原始設計；最新執行狀態見下節）

採用 ignored owner `task-08/graph-absence-contract-v1/A7-native-transform-preflight-v4.md`，SHA `BDD8BFD4D82A401EE26AC83CB7A572B9420802352F703CE21C883766775A239A`；獨立設計 review `task-08/a7-loader-admission-v4-review.md`，SHA `33D8D22229D6D304C435C6CEC1DC643A1656602B6319EC7497A689FA6C7823BA`，C0/I0/M0。此為 A7 專用有限 Loader admission：必要 JDK-only helper 必經正常 Knot loadClass 定義與選擇返回，先證 owner 初始化；固定 raw 與各一次 pre-mixin expected 分離，expected 表只發布一次，每 target define 再核只讀狀態閉包／路由／incoming exact expected，通過才注入原16 hooks。唯一 bootstrap bridge 與永不清除首錯 fatal latch 必貫穿 launcher／attempt／Main／finally。

固定 producer、manifest、JVM/env/mod/config/plugin/AW 原件封閉與 callback 預備閉包，依 v4 全文；未知／null／來源／狀態／hash／coverage 不符即 SETUP_FAIL，不以 raw 相同、dummy probe 或未知當零替代。runtime 仍未授權；helper正常路由、集合實際型別、pending空、五target的raw/expected/incoming/output與16hook需當次receipt證明。此採用不代表目前候選實作已通過review或A7／B／8A完成，B／8B3不沿用A7特例；38eval、G40/O14/D2/A42/M68、IO768/native766、時間與容量上限均不變。

##### A7 Loader狀態窄修（2026-10-26採用；下一次runtime待驗）

已實跑三次setup，最新PID34220已證正常helper define，停semaphore=false要求；38eval未開始。採用ignored owner prepare-assumption-audit-v1/A7-LOADER-STATE-ADDENDUM-v1.md（SHA9A281576D34A308108229EBBFBA064A772D9A2C5267679A302DA39F5039C9512），獨立review8992DAFDD47391515C930407C196100EC8D4600DA20A97BB0F96DAEE0C8752FA為C0/I0/M0。此節僅取代v4的兩個錯誤技術前提：

- 本固定A7 profile要求semaphore=true且原Watch持續核對不漂移。它是歷史OR latch，pop不清；inactive仍要求depth=0、maxDepth=1、errorState=false、同owner／thread及所有原狀態閉包。不呼set/clear，不允任意boolean；公開getter的mutable escape仍受固定manifest與逐次watch限制，首次置true的stack未知，不補造。
- 固定shaded AccessWidener.classes實為LinkedHashSet，使用既有exact kind3並仍要求空／identity／member穩定；三rules map維持exact HashMap且空，不擴容器白名單。config仍逐一要求visited=true；addendum的visited0記號不代表把此布林值改false。

Helper固定package及literal Minecraft JAR接線已經C3BE0742…獨立review；三source窄修在第三次setup只證helper正常返回，不冒稱其餘guard通過。此次helper恰兩token修正，compiler24648自然exit0；其餘prepare constructor／normal setter已靜態盤點，runtime未到的條件仍必實驗拒絕或通過。spec v1.3產品契約、38eval／16hooks、fixed expected／incoming exact、quota／deadline不變；新文件pin與全部artifact preflight後才另啟唯一A7。完整B34/G2及8B門檻不變。

##### B34責任與策略review先後

B#19保原exact Single MISS_TEMPLATE fixture，改完整OW正向；檢Contract/TemplateId/ABSENT_NATIVE_BLANK/完整native blank與role COMPLETE，新增該role完整canonical/FP/metrics/node manifest一組。B仍34case/24ctx/28role attempt，因原#19本來同一Context及OW attempt；required pool#18/loot#20負向不變。原9對18 H/H-1全依新圖測；不得拿舊partial或diagnostic D補。G2原2BASE/2Context/6role，同deadline remaining>=210與六canonical逐byte/node exact證cold absence穩定；未過不稱8A gate。

先獨立review本次live admission/gate策略與必要owner，通過前8B2不能建立正式snapshot成功路徑。8A A7只owned模型A/契約資料；8B2須具名RED→同clean雙focused/full/reviews（含tryLock忙、finally/exception、nested、no upgrade、owner錯配、writer拒）。8B3再真normal server worker/cache fill、正常saved/dev操作、reload多inflight/失敗、stop與snapshot同源lease runtime，並守三bundle跨mapping/OS。此處尚未實作/compile，沒有把候選稱已證無死鎖或live GREEN。

#### 8B1：snapshot與metadata API

**本批owner子集：** Snapshot/Provider/Registry及其兩JUnit、ServerSettingsContext的窄API宣告、README。若需要尚未完成的canonicalizer/context implementation，只能最小可編譯且fail-closed的介面/骨架，不作假成功，不接journal/candidate/世界物化。

- [x] 先具名RED：snapshotRejectsUnknownParameter、snapshotRejectsOversize512、registryRejectsLateOrDuplicateRegistration、strict GenerateStructures缺失/錯type/非0或1、roundtrip/defensive copies。新API骨架需可編譯；不存在class、NPE/setup/compile錯不當RED。
- [x] 註冊metadata/query/freeze與來源/目標immutable DTO完成；native changed-provider與完整resolve仍屬8B2，不把8B1稱Task8完成。compile→逐檔candidate→SnapshotTest/RegistryTest各兩focused→full→fresh兩review。

#### 8B2：canonicalizer、template adapter與context owner

內部分round G、C1a與C1b-B1/B2/B3已按各自code pin通過既定focused/review；C1c-T/A亦已完成上列局部範圍，8B2整體仍未完成。C1d須先做完整owner census與typed leaf/consumer白名單，再完成三role閉包、provider/FP、snapshot/resolve；`createSnapshot/resolve`目前fail closed。100ms單次live hold I-1須以代表配置量測及獨立review關閉，超限縮短鎖內工作而非提高cap；8B3三bundle與真server lifecycle仍待。原G quality Minor公平鎖無參數tryLock可插隊仍須評估，C1c-A quality Minor manager來源raw/total-raw計費與depth亦交C1d。G結果摘要 `task-08/8b2-g-result-review.md` SHA `35485325987576a6ca4ef8a0339a293351b0c99205deb01067ee376c95e5dacc`；B3、B1、C1a各自原件與SHA見前筆note，舊驗證pin不轉成新docs HEAD驗證。

2026-10-29 Claude 接手快照：D0-O六組raw分母已獨立C0/I0/M0，但consumer/source/admission仍未證。D1a原候選`877d20f`完整full通過，獨立spec/quality各C0/I1/M1；fix1`165b8b9`的same-size RED→GREEN已通，乾淨第二focused及新nonce補驗各因既有template測試先碰100ms而FAIL，fix1 full未啟且無最終review。下一步先限CanonicalizerTest窄修BUDGET_TOTAL_RAW fixture，再新HEAD雙focused/full與獨立review；詳[`Claude續作提示詞`](../../handoffs/2026-10-29-m6-claude-resume-prompt.md)。此狀態不代表D1a／8B2／Task8完成。

2026-10-29 D1a結案快照：test-only fix2 `9d97cd0`在任何lease前算blank charge兼暖機，BUDGET_TOTAL_RAW改為正例1次occurrence＋負例2次occurrence（原fixture實際也只到第2次）；乾淨M6Compile、雙focused88/88、完整`run-all-3990c81a33b84ee9961ae54b63f4b752` END ALL0／JUnit850 f0e0 skip2，fresh spec C0/I0/M5、quality C0/I0/M6。D1a只限named PROCESSOR_LIST empty／NOP／LAVA局部閉包；fix2降低100ms偶發風險、不宣稱根治。11項Minor交下一C1d切片Step0，清單見M6 note末節。C1d其餘、100ms live hold I-1、8B3、Task8終局仍待。

2026-10-29 C1d Step0快照：D1a遠端CI run40836148542（307565f）兩job success。D1a Minor收斂code `242c20b`（d561520 test＋242c fix，只RegistryClosure與CanonicalizerTest）：具名RED→GREEN93/93、乾淨雙focused93/93、完整`run-all-41d76dffa3104602a75c8836ff2184ae` END ALL0／JUnit855，fresh spec C0/I0/M0、quality C0/I0/M4。4096＝D1a保守pre-encode工作量cap、無原生上限依據、不保證完整vanilla配置可用；多個大清單同lease可能逼近100ms，列為I-1輸入。242c遠端CI待本筆push後讀回。新Minor S1～S4併入下一切片D1b（BLOCK/ITEM有效值與component）Step0；D1b依ignored SEQUENCE-PROPOSAL（未審順序提案）先寫設計並獨立審查才實作。

2026-10-29 D1b設計與D1b-0快照：Step0遠端CI run40845641782（0998f67）兩job success。D1b設計v2（ignored `task-08/8b2-c1d-design/D1B-PROPOSAL-v2.md`）經全新設計審查C0/I1/M7→窄複審C0/I0/M5放行，切分D1b-0 Step0→D1b-1 BLOCK leaf→D1b-2 ITEM leaf→D1b-3引用其他registry的component→D1c真資料。D1b-1／D1b-2的新增owner見上方Files段的C1d-D1b bullet。D1b-0 code `0efb058`：乾淨雙focused95/95、完整`run-all-359153a6930a42c7a8b9a05df4901f9e` END ALL0／JUnit857，spec C0/I0/M1、quality C0/I0/M2。設計審查要求的7項C1d待辦已寫入M6 note。

2026-10-29 D1b-1快照：D1b-0遠端CI run40863093293（b7d539a）兩job success。D1b-1 BLOCK static leaf code `58a2ff9`（9cfab02 RED＋df3e66b feat＋58a2ff9），具名RED2→GREEN、見證1/2/5a/5b/6/8；乾淨Canon focused103/103×2、LeafTest7/7×2、完整`run-all-9b4906c116d14c6ab5b42da9723209f3` END ALL0／JUnit872，spec C0/I0/M2、quality C0/I0/M5。更正設計前提：Canon EndFixture會freeze全域static registry，freeze後無法註冊，ModBlocks改於Canon @BeforeAll初始化。quality M1～M4併入D1b-2；下一步D1b-2 ITEM leaf。

8B1 quality Minor交此批：正式create／resolve接線時凍結provider/schema/adapter能力並驗custom參數；補resolve直接正反測試。8B1的`fromNbt`只證結構有效，不能冒稱custom設定已驗。

**本批owner子集：** Canonicalizer/TemplateAdapter/Contexts main及其三JUnit、ServerSettingsContext實作、Registry/Snapshot及既有兩JUnit所需整合、README；同時包含上方 gate 七個 owner 與四個資料 support owner。mod初始化與testmod/PS屬8B3。

- [ ] 先具名RED snapshotRejectsChangedProvider（同id/version改實際biome/structure/tag/loot/template，不以fake FP字串）、missingProvider/unknownVersion、required/unknown/resource budget、alias/symbol、tag順序及source資料controls。normal/amplified三rolenative輸出，N/E component相同；不同rootseed不進FP，其他salt等不刪。GenerateStructures false可精確重建，不受只改canonical global覆蓋。
- [ ] 實作full data encoding/strict reconstruction、exact ctx/epoch/owner/readonly API，不跨解析呼叫cache FP。純unit測contextEpochRejectsReloadAndStop、contextDoesNotCrossServer及inflight/失敗；不把unit當native事件接線。Source/Target snapshot格式與資料owner按spec，任一role未閉合不可建立snapshot。
- [ ] TemplateAdapterTest保留上述history/raw-type/native optional與unknown-owner回歸，與loot/component等真正configuration refs明確分開。這次澄清不增加RecipeManager context owner／recipe registry dump；若新配置consumer確需recipe資料，先具體owner/來源/同epoch/rebuild方案與review，未證便unsupported，不默默擴充或省略。
- [ ] compile→code candidate→受影響五JUnit class各兩focused（實際列表在短派工釘選）＋已審native/context資料證據→full→fresh兩review。仍不啟M6 candidate政策、不碰schema5/catalog2/factory或family。

#### 8B3：真正lifecycle/entrypoint與停服後oracle

**本批owner子集：** QuantumSuperpositionMod、Contexts/Registry初始化必要接線、上述七testmod＋七PS、.github/workflows/build.yml、build.gradle的gameTestM6 properties／限定artifact/runtime準備task與README；其他core變更若必要須明列fix scope。

- [ ] mod init builtins→專用Java entrypoint→freeze metadata→contexts事件註冊，早於journal/M5 authority/lifecycle必要使用；不在mod init解析未ready native data。SERVER_STARTING發布需native時序支持；START reload原子失效/inflight++，END依實際active holder一致資料且成功/inflight0才publish，失敗unavailable，STOPPING/STOPPED失效。只釋放本mod owner，不close借用server RM。
- [ ] 同一settings情境的第一階段 settingsCanonicalFixtureExact，固定同一fixture內容與GenerateStructures=true，經正式canonicalizer輸出normal/amplified×OW/N/E六role及兩family的原始canonical bytes與FP；fixture由M6SettingsGameTests內固定來源產生UTF-8/LF bytes並留下輸入manifest，所有平台相同，不以OS checkout換行或物件身分作fixture。證據metadata含fixture/code/adapter、實際runtime namespace與Minecraft/main/testmod class source；這些metadata不進FP。原始bytes可用固定gzip傳輸，receipt留raw長度/hash，compare解壓後逐byte比而非只比預寫FP字串；輸入／輸出皆套用8A已審有限budget。
- [ ] 一個獨立batch m6_universe_settings_context，以有階段的單一情境避免async測試互搶reload。 先完成canonical fixture，再在同一server按原計畫測reload，避免另開平行batch污染context。真server.reloadResources而非手動invoke event；測成功、controlled future失敗後恢復、多inflight兩種完成先序；server thread只tick-poll、不join future。新owned datapack有tag/loot marker，獨立讀server actual views與lease比同epoch；所有test hooks綁server/nonce並finally清理。
- [ ] Startup testmod observer在SessionRecoveryState.get真入口記首用時ctx ready/事件sequence；自然startup證據與fixture人工觸發分開。真STOPPING後lease invalid、STOPPED寫final receipt晚於XML，外部validator強制採計。暫定testmod故障點為MinecraftServer.method_29437呼叫DataPackContents.reload的future wrapper，require1；正式native/compile gate再核exact descriptor/點位，不當目前已可注入。
- [ ] m6-settings-receipt-check共用strict oracle；gates/CI提供唯一root/nonce與預期scope，run-all/ci-rule再核同收據hash/owner。純JSON負例含missing/wronghead/PID/nonce、journal早於ready、stale/mixed epoch、inflight卻ready、假reload成功、missing/失敗stop、hash不符。focused若不含settings batch，只可採該scope實際startup/stop，不冒稱reload已覆蓋；full/CI完整M6必含settings scenario及stop receipt，artifact glob需確實上傳。
- [ ] production runtime先作具名準備核：InspectRemapM6TestmodConfig、InspectTestmodMixinRefmap、PrepareProductionClasspathManifest、ValidateOfficialMappingInputs、ProbeCommonProductionAdapterBoundary；compile/remap本身不當實跑。以Loader0.17.2的KnotServer production mode及pin過的intermediary game／main release／獨立remapped testmod與production Fabric API啟動；不得含dev-launch-injector、named Minecraft/classes目錄、named remapped_mods，不設fabric.development=true。驗實際MappingResolver namespace=intermediary、代表class CodeSource恰對準備manifest；不靠參數自報namespace。
- [ ] m6-settings-mapping-probe只用全新短owned cwd，沿既有canonical/non-reparse/absent/nonce/PID@StartTime/prepost hash與釋鎖協定，不move既有world。利用既有testmod M4LegacyGameTestFilterMixin的m6Only=true及onlyBatches=m6_universe_settings_context，不假設Fabric另有testClass property。expected coverage明列canonical-only：同一情境只完成canonical階段，仍必真startup與STOPPED receipt，不能冒稱reload覆蓋；正常named full/CI為complete且必跑reload全部階段。取消由testmod M6SettingsTestHooks/Observer的綁server/nonce取消訊號合作要求正常stop並finally清理；不假設GameTest有stdin console、不強殺持Session程序。超時保全原件/owner並FAIL。
- [ ] local run-all在本批啟用後必跑一次mapping fixture並與同HEAD named canonical輸出逐byte比；不假裝含遠端Ubuntu證據。Windows/Ubuntu既有named CI各產同fixture bundle，Windows另跑mapping step。settings-canonical-compare嚴格核三bundle的code/fixture/adapter、六role＋兩family集合、實際namespace/class來源、hash/raw長度/自然exit/stop/釋鎖，再串流逐byte比；只允每bundle各自實際artifact hash，不把named與intermediary JAR hash要求相同。任一差異/未跑/不確定namespace或前置job失敗阻擋Task8完成。ci-rule-check與共用compare負例涵蓋missing bundle/role、wrong head/fixture、假namespace/source、改一byte、錯hash、漏stop及解壓超限；實際呼叫同一validator。workflow always-upload涵蓋全部原始bytes/metadata/receipt/失敗root及比較結果。
- [ ] compile→code candidate→兩次Settings GameTest focused＋核心JUnit/新oracle自測必要回歸→完整run-all（含當下native DIAGNOSTIC2、一次本機mapping比較與所有新case/receipt）→fresh spec/quality→docs truth後push feature→雙平台named及Windows intermediary/比較job全通過且原件audit。最後一項之前不結Task8或進Task9；本機full不代跨平台CI。這個final code full即Task8本機終局，保留exact新增case/原件/發布隔離/警告與world restore；同code的docs-only不重跑。

**後續consumer責任：** Task9正式load/create/resolve都取得exact server lease，SourceGenerationSnapshot仍(rootSeed,settings)，GenerateStructures在settings.Parameters；Task11把frozen primitive投影到constructor/Accessor/worker；Task15live/canonical OW比對同canonicalizer＋實際bool，full provider FP與OW component分開。本task不提前實作這些接線或增加候選權重。

### Task 9：schema5 候選與 policy1，只開 random

**Files:** Modify `src/main/java/dev/quantumchamber/candidate/CandidatePolicySnapshot.java`、`CandidateResolver.java`、`NewUniverseIntent.java`、`src/main/java/dev/quantumchamber/persistence/CandidateJournalCodec.java`、`SessionRecoveryRecord.java`、`SessionRecoveryState.java`、`SessionJournalStore.java`、`src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`、`scripts/verification/README.md`；Create `src/main/java/dev/quantumchamber/candidate/SourceGenerationSnapshot.java`、`src/test/java/dev/quantumchamber/persistence/CandidateJournalCodecTest.java`、`SessionJournalSchemaFiveTest.java`。
**Interfaces:** `CandidatePolicySnapshot` 能 strict 表示 M6 `quantumchamber:m6_profiles_v1`/candidate schema2/5-20-75/4096/family0；新 intent 持 source/target snapshot 與 profile1；`SessionRecoveryRecord.fromNbt(..., envelopeSchema=5)` 外層放行並 strict 分派。`SessionJournalStore.read(path)` **按同一穩定輸入的 envelope 版本**選 reader：schema1–4 仍用舊合法容量／解碼／寫入與 checked readback 契約（含 schema3/4 原 Context 16384）；schema5 的正式 load、checked readback 與候選完整 envelope 寫前 preflight 才用同一 `NbtSizeTracker` 16 MiB。unknown>5／版本不符 fail closed，不回退 unlimited。**只加格式／解析能力**，正式 `SuperpositionSession.freezeCandidateContext` 此 task 仍產生 M4 context，新意圖尚不公開。
- [ ] Snapshot使用明確server-bound lease；source/target內建Parameters嚴格包含GenerateStructures byte0/1，三策略保留來源值，無global再抽取或缺欄default。ProfileRegistry/provider create/resolve介面接v1.1；structural codec、runtime解析與checked authority的先後明列，legacy不被新ctx要求偷偷改寫。
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

**Files:** Modify `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121ServerWorldFactory.java`、`Minecraft121DynamicDimensionBackend.java`、`src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`、`src/main/resources/quantumchamber.mixins.json`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseRecoveryProbe.java`、`src/testmod/resources/fabric.mod.json`、`scripts/verification/m6-universe-probe.ps1`、`scripts/verification/run-all.ps1`；Create `src/main/java/dev/quantumchamber/mixin/ServerWorldUniverseSeedMixin.java`、`src/main/java/dev/quantumchamber/mixin/ChunkGeneratingUniverseSettingsMixin.java`、`src/test/java/dev/quantumchamber/universe/UniverseRootSeedOwnerTest.java`、`src/testmod/java/dev/quantumchamber/gametest/M6UniverseGameTests.java`、`M6RandomProbePhases.java`。
**Interfaces:** `rootSeed(server, worldKey, descriptor)` 只在 exact schema2 owner 且 live instance 匹配時回 frozen seed；`ServerWorld.getSeed` 的建構期與之後都一致；無 match 完全原版。factory 取 provider resolved options，不修改全域 save properties/registry；新 U randomSequences 用自己的 persistent state。
- [ ] 承接Task7 review M-2：首次新增enabled chain時，同步擴充正式run-all的manifest/summary/receipt oracle與正反例，逐mode/schema/chain/phase核對；保留既有DIAGNOSTIC歷史語意，不延到Task12才補。此owner補正已經Task7兩席獨立review確認，隨本task雙GREEN/full及fresh review驗收。
- [ ] GenerateStructures由checked target snapshot重建，exact schema2 owner供StructureAccessor及ChunkGenerating.generateStructures持續worker，不改全域GeneratorOptions。具名true/false、NEW_M6重啟切canonical global仍frozen、legacy原路、constructor/worker/source capture一致；native descriptor/consumer漏一處不得結案。新mixin只對exact owner生效，unknown/canonical沿原版，不將ProviderContext晚到當fallback。
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
- [ ] 遷移以lease及exact有效GenerateStructures建立snapshot兩欄params；live/canonical OW實際bool也比frozen，外改拒絕；未遷移schema1仍原版、首次遷移前歷史未記值不能倒推。新增N/E及NEW_M6沿Task11 frozen control owner。
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
