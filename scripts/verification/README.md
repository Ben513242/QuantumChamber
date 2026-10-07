# M1–M4 驗證 harness

M1–M4 automated gates 的 tracked 版本。原本只放在 gitignored 的 SDD workspace `.superpowers/sdd/2026-09-21-m4-candidate-doors/`，刪除 worktree 或 `.superpowers/` 後就無法重跑；這裡的腳本只改路徑、參數與名稱，安全行為（fresh world move／逐檔 hash exact restore、owned roots、拒絕覆寫、main-only 三層 oracle、JAR testmod 檢查）與原始檔相同；PID 過濾等少數邏輯差異與拿掉的核對，逐項列在「來源對照」。

M5 的 gate 擴充也放在這裡。M5 F3c 新增 M5 GameTest run（M5 plan 決策 6）：會配置或物化 Universe、或會留下 M5 record 的 GameTest（batch 前綴 `m5_universe`），在獨立的 fresh 世界 `run/gametest-m5` 執行，不放在 default run。

## 環境需求

- Windows，repo 位於本機固定 NTFS 磁碟（Windows checkpoint 只接受本機固定 NTFS，`_windows` GameTest 與 checkpoint JUnit 都依賴它）。
- PowerShell 7（`pwsh`）；`git` 在 PATH。`m4-recovery-probe.ps1` 另以 Windows PowerShell（`powershell.exe`）啟動 Gradle。
- Java 21。本機驗證使用 Eclipse Adoptium `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`（`JAVA_HOME`）。
- Gradle 8.8：預設 `C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat`，不存在時改用 repo 的 `gradlew.bat`（這條 fallback 尚未實跑；第一次執行需要網路下載 Gradle）；也可用 `-Gradle` 指定絕對路徑。兩者都不存在時直接失敗。
- 執行期間不得有 Minecraft client／server 或其他 Java 程序使用 `run/`。harness 會檢查 live owner 與 `session.lock`，有就拒絕。
- 磁碟空間：一次 `run-all.ps1` 約 0.85 GB。evidence 約 420 MB（`-EvidenceRoot` 下約 260 MB，testmod 固定位置約 160 MB），`run/` 新增的 owned roots 約 420 MB；harness 都不刪除。

## 檔案

| 檔案 | 用途 |
| --- | --- |
| `gates.ps1` | gate 主體。`-Mode Full`（`clean test runGameTest build --rerun-tasks`）、`Legacy`（`runGameTestLegacy`）、`M5GameTest`（`runGameTestM5`）、`Main`（main-only `runServer`）、`M3`（lifecycle 4 phases＋transfer 5 phases）、`GameTest`（只跑 `runGameTest`）、`Windows`（3 個 Windows checkpoint JUnit class）、`Inventory`（靜態列出 `@GameTest`）。三個 GameTest run（default、legacy、M5）都核對 XML 是本次 run 寫出的，而且 testcase 集合恰好等於原始碼依 batch 前綴矩陣屬於該 run 的宣告（`scope.json`）：`m4_legacy_runtime` 屬 legacy run，前綴 `m5_universe` 屬 M5 run，其餘屬 default run。default 與 legacy 另核對 M4 boundary oracle；M5 run 不註冊 boundary probe，改核對每個 M5 test 自己斷言的 catalog 增量、增量之間的串接（筆數與 digest），以及 suite 結束時（`SERVER_STOPPED`）的 catalog 等於最後一個 test 的結果（`m5-oracle.json`） |
| `main-oracle.ps1` | 讀 `-Mode Main` 的 gate root：Fabric mod list、runtime classpath／argfiles／DLI、exact server PID class-load 三層都不得有 testmod；另核對 world keys、正常停止與 exact restore |
| `m4-recovery-probe.ps1` | M4 retained recovery 跨 JVM probe，24 phases，每個 phase 一個 JVM |
| `ci-rule-check.ps1` | 從 `.github/workflows/build.yml` 逐字抽出 step 的 `run: \|`，在 fixture 複本上以 pwsh 執行；fixture 同時放 default 與 M5 兩份 GameTest XML。可加 XML／原始碼 mutation 證明規則會失敗 |
| `runtime.init.gradle` | 所有 gate 的 Gradle 呼叫都以 `-I` 載入：run* 接 stdin、寫 `classload-<pid>.log` |
| `probe.init.gradle` | M3 gate 另外載入：把 `-Ptask9.*` 轉成 probe 的 system property |
| `run-all.ps1` | 依序跑 Full、Legacy、M5 GameTest、Main＋oracle、M3、M4 recovery、CI 綠燈規則（Windows／Ubuntu 兩個 step），最後以唯讀 summary 核對 |

## 執行

全部 gates（約 15 分鐘；以下指令在 repo 根目錄執行，腳本本身依檔案位置找 repo root）：

```powershell
pwsh -NoProfile -File scripts/verification/run-all.ps1
```

成功時最後一行是 `END ALL exit=0 HEAD=<sha>`，倒數第二步印出 `SUMMARY ...`。任何步驟失敗都會印 `END <step> FAIL <原因>` 並停止。

`run-all.ps1` 不會因工作樹有未提交的變更而拒絕執行，只會在 `console.log` 的 `STATUS` 行列出 tracked 變更。一次 run 要能採計，需同時滿足三個條件：

1. `STATUS` 行沒有 `src/`、build 檔或 `.github/` 的變更。
2. 存在一個 commit C，本目錄每個 `.ps1`／`.gradle` 的 `HARNESS` 雜湊，都等於 C 中同一檔（`git show C:<path>`）的 SHA-256。
3. C 與 run 的 `HEAD` 之間，沒有 `src/`、build 檔或 `.github/` 的差異。

`HARNESS` 是工作樹 bytes 的雜湊。CRLF checkout 時，它不會等於 blob 內容的雜湊，這時改成兩步核對：先確認目前檔案的 SHA-256 仍等於 `HARNESS`，再確認 `git hash-object <path>` 等於 `git rev-parse C:<path>`。

個別執行：

```powershell
$v = 'scripts/verification'
& "$v/gates.ps1" -Mode Full -Label full            # 印出 GATE_ROOT=<gate root>
& "$v/gates.ps1" -Mode Legacy -Label legacy
& "$v/gates.ps1" -Mode M5GameTest -Label m5-gametest
& "$v/gates.ps1" -Mode Main -Label main-only
& "$v/main-oracle.ps1" -Gate <Main 的 GATE_ROOT>
& "$v/gates.ps1" -Mode M3 -Label m3                # -Only universe 或 -Only transfer 只跑一條
& "$v/m4-recovery-probe.ps1"                       # -Only chain,low 只跑指定 chain／scenario
& "$v/ci-rule-check.ps1" -Label win-green -Workflow .github/workflows/build.yml `
    -Step '確認 Windows 原生案例確實執行' -GameTestXml <Full gate>/result-build/gametest-results.xml `
    -M5GameTestXml <M5GameTest gate>/result-build/gametest-m5-results.xml `
    -JUnitDir <Full gate>/result-build/test-results/test -SourceRoot src/testmod/java
```

`ci-rule-check.ps1` 不因規則 FAIL 而 throw，判定看 `<root>/result.json` 的 `verdict`。要證明規則會擋下缺漏或宣告漂移，加 `-Mutation drop|skip|fail -MutationTarget <XML testcase name>`（`-MutationXml m5` 改對 M5 XML）或 `-SourceMutation interpose-annotation -SourceTarget <方法名稱>`，預期 `verdict=FAIL`。`-SourceMutation rename-windows` 把方法與它的 testcase 一起改名為 `<名稱>_windows`，用來驗證 `_windows` 規則的納入與排除（M5 的 `_windows` 案例：Windows step 要求出現在 M5 XML 並實際通過，Ubuntu step 不要求）。`-Mode GameTest`／`Windows`、`-Only` 與 mutation 模式在 2026-10-01 沒有單獨實跑；M5 F3c 實跑過的 mutation 記在 M5 implementation note 的 F3c 段。

## 新增 M5 GameTest

M5 的 GameTest 分在兩個類別：`M5CollapseGameTests`（default run，只用 SOURCE 或不配置宇宙的 fixture，結束時不留 M5 record）與 `M5UniverseGameTests`（M5 run，見下方第 1–8 點）。兩者共用 `src/testmod/java/dev/quantumchamber/gametest/` 下的 `M5GameTestSupport`（helper 與 fixture）與 `M5TestHooks`（mixin 讀取的靜態 hook）；M5 期間放在 `M4CandidateDoorGameTests` 的 test 也用同一份。`M5CollapseGameTests` 與 `M5UniverseGameTests` 不互相借用 helper：需要共用時，先搬進 `M5GameTestSupport`（或 `M5TestHooks`）。

- **hook 釋放**：每個 test 建一個 `M2CorridorGameTests.TestHooks`，探針、log capture（`captureLogs(hooks)` 建立 capture 並登記關閉）、fixture 的收尾都登記在它上面（後進先出）。排程用 `at(context, tick, hooks, …)` 與 `whenTicks(context, tick, 上限, 條件, ready, 診斷, hooks)`（以 server tick 計的有界等待）：斷言失敗、逾時或例外時先 `releaseAfter`，成功路徑最後 `release()`。test 的程式不得讓例外從 `END_SERVER_TICK` 拋出（GameTest server 會崩潰）；探針只能在 journal flush 的開頭丟出受控的例外。
- **flush 探針與計數探針**：`installFlushProbe(context, probe, hooks)` 把探針裝在 `M5TestHooks.FLUSH_PROBE`（`SessionJournalWindowFaultMixin` 在每次 journal flush 的開頭呼叫）。探針與下一點的 journal 寫出稽核各是一個位置，同一時間只有一個持有者，安裝依序：先在 hooks 登記移除（hooks 已釋放時在這裡就丟出，什麼都不裝）；再在這個 test 的原版 `GameTestState` 登記 TestListener（test 結束時若仍裝著就移除，並記一行 `M5_TEST_HOOK_RESIDUE` WARN，涵蓋 tickLimit 逾時而 hooks 沒有釋放的情況）；最後才安裝。位置已被占用（同一個 batch 的另一個 test 正在使用，或沒有被移除的殘留）時丟 `IllegalStateException`、不覆蓋，訊息列出持有者的 test 與它是否已結束。「記 tick、前 N 次失敗」用 `new FlushFault(server, 訊息, N, 條件)`：條件成立的 flush 記下 server tick（`ticks()`），前 N 次在 flush 開頭受控失敗（`failures()`；之後照常寫出的是 `retries()`；`disarm()` 之後不再失敗）。條件只看 journal 的狀態（current 與 flushed），不分是誰呼叫 flush：只要帶著同一份未確認的變更，任何呼叫端的 flush（受測的寫入、recovery 的 RETRY_FLUSH、其他 session 的寫入）都會被計數，在前 N 次之內也會受控失敗，同一次要寫出的其他變更也跟著失敗。
- **F4b-1 的投影 helper**（M5 run）：`forceProjectionChunks(world, frame, hooks)` 強制載入 frame 的 7×7×7 與水平外擴 `PROJECTION_MARGIN`（8）格涵蓋的 chunk，並在 server thread 上同步載入（先在 hooks 登記解除強制載入），回傳這些 chunk。chunk 本身是同步載入的，但要開始 block ticking（方塊事件、block entity、排程刻與流體才會執行）還要等鄰居非同步產生，而 GameTest server 不以 20 TPS 節流：依賴這些的 test 先以 `whenTicks(…, () -> blockTicking(world, chunks), …)` 等待，判定也等對照組真的發生（有界等待），不用固定的 tick 數。新 chunk 的 entity storage 同樣是非同步載入的：要以原版的 entity 查詢看到 entity，先等它看得到。`projectionTarget(frame, x, y, z)` 是 test 依 spec §9.2 自己寫出的目標方塊（不讀 production 的 `ProjectionWriter`），`projectionMismatches(world, frame, shellOnly)` 列出與目標不符的格。宇宙 U 的投影 test 用 `M5UniverseGameTests` 的 `resolvedUniverse(context, origin, hooks, (tick, universe, sid, chamber, id) -> …)`：原艙上的 DORMANT NEW 收據、玩家由艙外進入、RESOLVE 物化 U 之後交出，catalog 增量 1。F4b-2 起 RESOLVE 之後的下一個 tick 會進入 PREPARE、自己寫同一個位置的投影，所以 `resolvedUniverse` 在交出之前以 `M5TestHooks.suspendCollapse` 暫停這個 session（偵測 RESOLVE 完成的 callback 早於第一次閘門評估）。
- **F4b-2 的 PREPARE helper**（M5 run）：
  - `collapsedToUniverse(context, origin, hooks, (tick, universe, sid, chamber, id) -> …)`：同 `resolvedUniverse` 但不暫停；交出的 callback 早於 RESOLVE 之後的第一次閘門評估，所以要在 PREPARE 之前佈置的東西（例如站進 volume 的玩家、既有的 Controller、另一座艙的投影、故障注入）都在這個 callback 裡做。`collapsedToUniverse(context, origin, hooks, others, …)` 另把 `others`（都已返還、在艙外）列為收據的原參與者（fix round 1）。
  - `destinationTickets(world, sid)`：這個 session 在 `world` 的預載 ticket（名稱 `quantumchamber_destination`、argument 是 session）所在的 chunk 與 level，從原生 ticket 表唯讀取得；`preloadArea(frame)` 是 test 自己逐格算出的預載範圍（F5-1 起與傳送時的 FULL 檢查同一個推導：艙體 bbox 水平外擴 1 格、再外擴 2 格，F4b-2 spec S-M1）。終局之後，ticket 在塌縮流程的下一個 tick 才釋放：以它為完成條件時放進 `awaitConvergedThenComplete` 的 ready。
  - `installChunkCheckpointProbe(context, worldKey, failFirst, observe, hooks)`：`ChunkSaveCheckpoint.save` 的探針（testmod `ChunkSaveCheckpointProbeMixin`，`M5TestHooks.CHUNK_CHECKPOINT_PROBE`，經 `installHook`）。只看 `worldKey` 的 checkpoint，逐次記下 tick、相關 chunks 與 `observe` 在那一刻的觀察（例如 ledger 的狀態；它也可以在存檔之前改動世界），前 `failFirst` 次在存檔之前受控失敗。
  - `installProjectionWriteFault(context, worldKey, mode, failures, hooks)`：`ProjectionWriter.write` 在 `worldKey`、以 `mode` 寫完（WRITTEN）之後，前 `failures` 次把結果換成 WRITE_FAILED（testmod `ProjectionWriteFaultMixin`，`M5TestHooks.PROJECTION_WRITE_FAULT`，經 `installHook`）；方塊已經寫了，模擬寫入途中丟出例外。`hits()` 是已注入的次數。
  - `rebuildProjectionAuthority(server, bytes)`：下一點隔離規則所說的重建 hook。把 projection ledger 檔以 `bytes`（遺失之前的正式檔）寫回，以反射解除 `CollapseAuthorities` 的遺失標記與投影保護索引的退化，之後權威必須健康、索引不退化，否則丟出。
- **F4b-3 的原艙門 helper**（M5 run）：
  - `M5UniverseGameTests` 的 `openOrigin(context, hooks)`（`DormantOrigin.open`，失敗時轉成 test 失敗）與 `releaseThenComplete(context, hooks, witness, 訊息, maxTicks, ready)`（釋放 hooks、斷言 witness 全部成立、以 `awaitConvergedThenComplete` 完成，catalog 增量 1）：PREPARE 系列 test 的前後段，新的 test 也用它們。
  - F4b-3 起，PREPARE 期間原艙門由塌縮流程擁有：門口沒有 entity、艙內沒有非參與者時是 sealed 量子膜；參與者以 bulkhead 或 Controller 開關門就是 checked 終局。PREPARE 期間不要再以參與者「照一般方式開關門」當 witness（F4b-2 的 P-NEW 已改為斷言量子膜）。
  - `RemoteOrigin.open(context, controller, hooks)`：overworld 遠處的真原艙（與 `NetherChamber` 共用建置；只有它自己強制載入、附近沒有玩家），`whenRegistered` 同 `DormantOrigin`。`unload()`／`reload()` 解除與恢復強制載入，`unloaded()`／`loaded()` 只查詢 chunk 是否 FULL；兩者都以有界等待確認，而且看到 `unloaded()` 之後要多等幾個 tick 再 `reload()`（同一個 tick 內恢復時 chunk 還在記憶體裡，塌縮流程看不到卸載）。COLLAPSING 持有來源 ticket（Controller 所在 chunk 半徑 2），所以只有 SEALED（以及沒有 session 的艙）的來源 chunk 能在同一個 instance 內卸載。
  - `installJournalPutFault(context, sid, state, failures, hooks)`：journal `put` 的決定性拒絕（testmod `SessionJournalPutFaultMixin`，`M5TestHooks.JOURNAL_PUT_FAULT`，經 `installHook`）：這個 session 寫成 `state` 的前 `failures` 次被拒，checked 提交回 REJECTED、journal 不變。
  - `simulateDoorStartup(server, sid)`：以反射模擬重啟之後的第一次觀察（比照 `markStartup`；把 session 加進開服時的集合，並清掉塌縮流程對它「來源艙已載入」的觀察）；真正跨 JVM 的重啟屬 F6。`writeDoor(world, frame, open, membrane)` 以授權寫入直接改 25 格門（例如留下 crash 之後的量子膜），`doorwayEntity(world, frame, hooks)` 在門口放一座不受重力的盔甲座，`doorSealed(world, frame)` 判斷 25 格都是 sealed 量子膜。
  - 原艙門重新套用的 INFO 記下排入時的「下一個 tick（tick N）」與完成時的「tick M」（`reapplyQueuedTicks`／`reappliedTicks`）：正規化（NORMALIZE）沒有其他等待，M＝N；關門要等門口的 entity storage 載入，M≥N。
  - F4b-3 fix round 1 的 helper：
    - `useDoor(world, frame, player)`：直接呼叫原艙門的攔截（`ChamberCollapsePort.doors()`；bulkhead 與 Controller 的 `onUse` 經過同一個 port），回傳處置（PASS／HANDLED／REFUSED），不切換門。用來斷言處置本身，也可以造出「攔截回 PASS 之後門切換被拒」（F5-0 的 `sealed_door_with_a_membrane_keeps_its_pending_close_when_a_player_uses_it`）。
    - `lookupFailingPlayer(world)`：判斷身分時（`isSpectator()`）丟出例外的非參與者（不在世界裡），造出攔截「判斷途中」的非預期例外。
    - `putUncheckedAuthority(server, hooks)`／`removeUncheckedAuthority(server, sid)`：在 journal 的 current 放入／移除一筆沒有 flush 過的 authority（新的 ARMING session，暫停塌縮流程、不與其他 session 衝突），讓 `recoveryWritesSafe()` 為 false。在同一個 callback 內移除（hooks 也登記移除）。
    - `discardDoorwayEntities(world, frame)`：以世界查詢移走門口（25 格門的外框）的所有 entity，回傳數目。chunk 重新載入之後，門口的 entity 是從存檔重新載入的另一個物件，卸載之前拿到的參照已經不在世界裡。
    - `RemoteOrigin.entitiesLoaded()`：涵蓋的 chunk 的 entity storage 都已載入（重新載入之後比 FULL 晚）。
  - F5-0 起原艙門的程式在 package-private 的 `collapse/OriginDoors`（從 `CollapseService` 抽出，行為不變）。`installDoorUseFault(context, sid, frame, hooks)`：原艙門攔截的失敗注入（testmod `OriginDoorUseFaultMixin`，`M5TestHooks.DOOR_USE_FAULT`，經 `installHook`），取代 F4b-3 fix round 1 以反射把 final 的 `Track.backoff` 換成 null 的作法。安裝時就讓這個 session 的參與者開關門的處置（`OriginDoors.endByParticipant`）每次都在開頭失敗（`disarm()`／`arm()` 切換，`hits()` 是注入次數）；`armLookup()`／`disarmLookup()` 另讓這座艙的攔截在找出所屬 session 時失敗（`lookupHits()`）。不要再以反射改塌縮流程的欄位來製造失敗：被換成 null 的欄位會讓之後讀它的程式丟出別的例外，witness 量到的就不是目標行為。
- **投影的殘留與隔離（規則，F4b-2 Step 0，F4b-1 quality Q-m8）**：
  - projection ledger 的 entry 不刪除（spec §6.5）：M5 run 中寫過投影的 test（F4b-1 的 GT-P、GT-L，以及 F4b-2 起走到 PREPARE 的 test）留下的 PENDING／COMPLETE entry，會一直留在這個 run 的 ledger 與投影保護索引裡。gate 只串接 catalog 的增量，不串接、也不核對 projection ledger。每個 test 都用自己的原艙（新的 chamber UUID）與自己的宇宙 U（不同的 world key），§9.2 的衝突只看同一個世界，所以殘留的 entry 不會讓其他 test 判成衝突。
  - 索引的退化（§9.3）與權威的執行期遺失（projection ledger 檔在執行期間消失），在同一個 server instance 內都不解除：之後每個 test 的 PREPARE 都會進 SEALED，投影保護也一直套用退化規則。batch 的執行順序不固定（T1：`Batches.createBatches` 以 HashMap 分組），不能靠「排在最後」。所以會讓 projection ledger 在執行期遺失、或讓索引退化的 test，必須在完成之前，以 testmod 專用的重建 hook（F4b-2 的 `rebuildProjectionAuthority`）還原 ledger 檔，並解除權威的遺失標記與索引的退化；hook 登記在 hooks 上，失敗路徑也會執行（F4b-2 的 `m5_universe_prepare_degraded` 是唯一這樣做的 test）。例外是純 tickLimit 逾時：原版在逾時時不執行任何 callback，hooks 不會釋放（`installHook` 的 TestListener 只移除 Slot），重建 hook 也不會執行，所以之後的 test 會看到遺失與退化（PREPARE 一律進 SEALED）。遇到時保留那個 root，以新的 label 重跑整個 M5 run（F4b-2 fix round 1，spec review S-M5）。
  - 未確認的寫入不會讓其他 test 封存：projection ledger 有未確認的寫入時，PREPARE 只等收斂（F4b-2 fix round 1 起，見 `m5_universe_prepare_unconfirmed`）。會留下未確認寫入的 test 仍應在完成之前等它收斂（例如 `awaitConvergedThenComplete` 給足權威的退避，T-PF 是 260 tick），之後的 test 才不必多等。
- **新增的故障注入 hook（規則）**：mixin 讀取的靜態 hook（例如 F4a 的物化失敗、ledger IO 失敗）一律做成 `M5TestHooks.Slot`，並在 `M5GameTestSupport` 新增 `installXxx(context, …, hooks)` 包裝，經 `installHook` 安裝。不要寫成 `static volatile` 欄位：先設值後登記、被覆蓋卻不偵測、逾時後殘留，這三個問題都會重演。target 是 package-private 的 collapse 類別（例如 `OriginDoors`）時，以 `@Mixin(targets = "…")` 指定，package-private 的參數型別以 `@Coerce Object` 收（F5-0 的 `OriginDoorUseFaultMixin`）。`targets` 指定的 class 找不到時（改名或搬移），Mixin 只記 WARN、不套用（sponge-mixin 的 `strict` 預設關閉），`require = 1` 只保護 class 找得到之後的方法選擇器，啟動不會失敗；所以用這種寫法的注入，test 必須有證明注入真的發生的 witness（`hits()` 一類，例如 T-FAIL 的 `closeFailuresWereInjected`），不能只靠「沒有注入時行為不同」（F5-1 Step 0）。
- **F4a 的故障注入**（M5 run）：`installMaterializeFault(context, universeId, hooks)`：這個 UniverseId 的 OVERWORLD 每一次經 lifecycle backend 的物化都回 `FAILED_ROLLED_BACK`（testmod `MaterializeFaultMixin`，掛在 `Minecraft121DynamicDimensionBackend.materialize` 的開頭；沒有建立 world），`calls()` 是 backend 的物化次數。`installCheckedReadFault(context, 檔名, skip, failures, hooks)`：`CheckedNbtFile.readFile` 讀檔名恰為該檔名的檔時（暫存檔的名稱不同，不算），先放行 `skip` 次、之後 `failures` 次丟 `IOException`（testmod `CheckedNbtFileReadFaultMixin`），`hits()` 是已注入的次數。對正式檔注入，寫入時就是「atomic move 完成、讀回失敗」，讀取時就是讀檔的 IO 例外；M4 的 discovery 讀取也經過 `readFile`，所以 discovery 的注入要在收據建立（candidate context 會讀 discovery）之後才安裝。兩者都經 `installHook`，規則同上。
- **原生 autosave 的窗口與 journal 寫出統計**：靠探針計數、而且跨越多個 tick（例如 200 ticks 起跳的退避）的 test，先以 `afterAutosaveClear(context, tick, 名稱, 窗口長度, (start, window) -> …, hooks)` 等到窗口內不會有原生 autosave 才開始注入（降低誤判的機率；開始時以 `installJournalAudit` 安裝稽核，規則同上一點），確認時以 `window.journalWrites(server, 計數, own)` 斷言 `onlyProbed()`：窗口內 journal 正式檔的每一次實際寫出都經過探針，而且探針看到的每一次 flush 只帶 `own` 的 session 的變更。`own` 必須包含 test 自己建立的**每一個** session（fixture、收據、另外寫入的 session），否則它們的 flush 會被判為「其他 session 的 flush」。寫出由 testmod 的 `SessionJournalWriteCountMixin` 統計（`SessionRecoveryState.save(File,…)` 呼叫 `SessionJournalStore.write` 的那一點，不論來源）。違反時 `problems()` 逐則列出旁路的種類：原生 autosave、`server.save`（附呼叫端，例如 `ChunkSaveCheckpoint`）、停服存檔、其他寫出，或其他 session 的 flush；log 另有一行 `M5_JOURNAL_WINDOW` 列出窗口內每一次 flush 與寫出。失敗訊息請附上 `problems()`。
- **塌縮流程的 log（規則）**：塌縮流程（`CollapseService` 與 F4a 起新增的 collapse 類別，例如 F5-0 的 `OriginDoors`）的每一則 log 都必須帶 SLF4J marker `CollapseService.LOG_MARKER` 或它的子 marker `TICK_LOG_MARKER`（不屬於任何 session 的，例如整個 tick 無法執行、原艙門的攔截在找出所屬 session 之前失敗）；能歸屬到 session 的 WARN／ERROR 在訊息中帶 `session=<UUID>`。JUnit `CollapseLogMarkerGuardTest` 掃描 `src/main/java/dev/quantumchamber/collapse/` 下所有 `.java` 守門：每個 `LOGGER.trace/debug/info/warn/error(` 的第一個參數必須是這兩個 marker（跨行的呼叫也算；註解與字串裡的字樣不算）；logger 只能是 `LOGGER = LoggerFactory.getLogger("quantumchamber")`（`LogCapture` 依 logger 名稱擷取），其他名稱的 Logger、`LOGGER` 當成值使用、`LogManager`、`System.out/err`、`printStackTrace` 都算違規；只有 `CollapseService` 可以宣告這兩個 marker。collapse package 以外的類別（例如 `universe` 的 `UniverseLifecycleService`）不在掃描範圍，守門範圍也不擴大：它們的 API 只回傳結果，不記塌縮流程的 WARN；塌縮流程的 WARN 一律在 collapse package 帶 marker 記錄。
- **log 斷言**：「這個 session 沒有塌縮流程的 WARN／ERROR」用 `collapseFlowNeverWarned(capture, sid)`：依 marker 篩選，不比對訊息字串；沒有 sid 的 tick 層級 WARN 以「capture 期間沒有帶 `TICK_LOG_MARKER` 的 WARN／ERROR」涵蓋。判定前它先以 marker 記一行 INFO 探針，capture 讀不到 marker 時丟例外。不分 marker 的「這個 sid 沒有任何 WARN／ERROR」用 `noWarnOrError(capture, sid)`。其他依 marker 的篩選用 `LogCapture.marked(level, marker, 片段…)`。log 斷言一律依 sid 篩選，不斷言絕對筆數或 tick。
- **fixture**：
  - `collapseFixture(…, suspend)`：MEASURED 收據 put＋flush 之後，只經 commitRestore／commitRollForward 進入指定的塌縮狀態。`suspend` 為真時以 `M5TestHooks.suspendCollapse` 暫停這個 session 的塌縮流程（決策 7：集合在同一個 JVM 內不清除；default run 中會產生 MEASURED 的 test 都要在塌縮流程第一次看到它之前加入）。收尾用 `cleanupFixtures`。
  - `DormantOrigin.open(context, hooks)`：真原艙（未供電）＋一位在艙外的真玩家與 log capture。`whenRegistered((tick, chamber) -> …)` 以呼叫當下的 test tick（`context.getTick()`，記為 t0）為基準：t0+2 供電（經 `at`，例外時先釋放 hooks），從 t0+3 起等原艙註冊並進入 IDLE（最多 40 tick），之後交出那個 tick 與 chamber UUID；`own(receipt(chamber, 參與者, 是否選 NEW))` 寫入 DORMANT 收據，收尾時自動移除。選中 EXISTING 的收據用 `existingReceipt(server, origin, 參與者, universeId)` 建立，同樣經 `own` 寫入（F4a）。fresh 世界裡，建立收據的 candidate context 會先建立空的 discovery 檔：比對權威檔 bytes 的基準要在收據寫入之後才取。玩家收到的 actionbar 訊息以 `overlayKeys(connection)` 讀出翻譯 key（讀掉之前的封包，所以可以先呼叫一次清空）。
  - `NetherChamber.open(context, controller, hooks)`：Nether 的真原艙＋一位在艙內的真玩家（強制載入並以石頭圍住）；`whenEntered(ready)` 沿用 `open` 的 context 與 hooks，同樣以呼叫當下的 tick 為基準：t0+2 供電，從 t0+3 起等真入場、358 扇側門發布。
  - 候選門：`candidateDoor(pages, target, sid, 種類)`、`sourceDoor(fixture)` 回傳 `CandidateDoor(key, cell)`。
- **前提**：
  - 用探針或稽核的 test 必須獨占一個 batch：同一個 batch 的 test 同時執行，第二個安裝會丟出。gate 只對 `m5_universe` 強制一個 test 一個 batch，default run 靠這條規則與安裝時的 fail-fast。
  - 有界等待的總長度（`whenTicks` 的上限、`afterAutosaveClear` 的等待與窗口、`awaitConvergedThenComplete` 的 maxTicks，加上呼叫時的 tick）要小於 `@GameTest(tickLimit = …)`。超過時原版判 test 逾時失敗，之後不再執行它的任何 callback：hooks 不會釋放，探針與稽核只由 TestListener 移除，其他收尾（fixture、log capture、玩家連線）都不會執行。
  - fixture 的呼叫時點：`whenRegistered`、`whenEntered` 以呼叫當下的 tick 為基準，可以在 test 方法本體（t0=0）或任何 callback 裡呼叫。在 callback 裡呼叫時，它們會在原版迭代 runAtTick 表的途中插入項目（供電與第一次檢查各一項）；同一個 callback 要再排很多項時，留意第 5 點說明的 rehash 風險。

會配置或物化 Universe、或會留下 M5 record 的 GameTest（M5 plan 決策 6）：

1. 放進 `src/testmod/java/dev/quantumchamber/gametest/M5UniverseGameTests.java`。gate 要求 batch 前綴 `m5_universe` 的 test 都在這個 class。
2. 一個 test 一個 batch：`@GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_<名稱>")`，`<名稱>` 不和其他 test 共用。batch 依序執行，逐 test 的 catalog 增量才只屬於該 test；共用 batch 時 gate 會失敗。
3. 名稱以 `_windows` 結尾的 test（需要 Windows 原生 checkpoint）：平台略過（`if(!Platform.isWindows()) { context.complete(); return; }`）放在 `begin` 之前，略過時不留下 baseline。Windows 上每個 test 都必須走到最後一步並通過，gate 逐 test 要求一行 PASS 的增量。
4. 第一個動作（平台略過之後）是 `begin(context, "<方法名稱>")`，名稱必須等於方法名稱。它在不是 M5 run 時、在碰 catalog 之前就讓 test 失敗，也檢查開始時已收斂。
5. 最後一個動作二選一：
   - `awaitConvergedThenComplete(context, <預期的 catalog 增量>, <maxTicks>[, <ready 條件>])`：每個 tick 主動呼叫 `CollapseAuthorities.convergeIfNeeded`（catalog 是 checked flush），收斂（而且 ready 條件成立）之後才完成，逾時就失敗。可以在 `waitAndRun`／`runAtTick` 的 callback 裡呼叫：輪詢由專用的 `TimedTaskRunner` 執行，不會在原版迭代 `runAtTick` 的表時插入項目。完成時點之後才到期的 `runAtTick`／`waitAndRun` 檢查不會執行：還沒到期的檢查，必須放進 ready 條件，或排在呼叫 helper 之前、而且在呼叫時已經到期。ready 條件用回傳 false 表示「還沒好」；ready、權威收斂與判定丟出的例外都轉成 test 失敗，不會讓 GameTest server 崩潰。
   - `complete(context, <預期的 catalog 增量>)`：當下就判定。未通過時保留 baseline、不寫增量行，所以可以放在 `context.addInstantFinalTask(() -> complete(context, n))` 裡逐 tick 重試；但只能在最後一步之後註冊（例如在最後一個 `waitAndRun` 的 callback 裡呼叫）。在 test 開頭就註冊的話，runner 從那個 tick 起每個 tick 都嘗試，catalog 增量一達標就 complete，之後排定的檢查不會執行，等於誤判 PASS。判定丟出非 GameTestException 的例外（例如 catalog 不健康）時，complete 把它轉成 GameTestException、之後一直丟同一個，並以原版的 `TimedTaskRunner.fail` 讓 test 確定失敗：在 runner 裡時下一個 tick 失敗，直接呼叫或在 callback 裡時同一個 tick 失敗，不必等 tickLimit，也不會讓 server 崩潰。runner 工作裡自己寫的其他程式碼，也不要丟出非 GameTestException 的例外（原版的 runner 只攔 GameTestException）。
6. 注入失敗的 test，要等 catalog 與四個 M5 權威都收斂之後才完成（衛生規則），否則之後的 NEW 配置會一直等待。權威寫出失敗之後的退避從 200 tick 起跳（`CollapseBackoff.INITIAL_TICKS`，之後加倍），所以這類 test 的 `maxTicks` 要超過 200，`@GameTest(tickLimit = …)` 也要跟著加大（預設 100）。
7. tick 上限：`maxTicks` 要小於 test 的 `tickLimit` 減去呼叫時的 tick。超過 `tickLimit` 的那一個 tick，原版一律判 test 失敗：即使同一個 tick 剛收斂、已寫了 PASS 的增量行也一樣；還沒收斂時以「等待…」的訊息失敗，不會留下 `status=FAIL` 的增量行。
8. 用 `gates.ps1 -Mode M5GameTest`（或 `run-all.ps1`）跑。直接執行 `gradle runGameTestM5` 時，`run/gametest-m5/world` 必須是 fresh 的：gate 從 0 筆開始串接增量，固定 UUID 的 fixture 在舊世界中也會撞到既有紀錄。

「哪些 test 屬於哪個 run」有三份實作：`gates.ps1` 的 `Batch-Scope`／`Declared-GameTests`，以及 `.github/workflows/build.yml` 的兩個檢查 step（「確認 Windows 原生案例確實執行」「確認非 Windows 跨平台案例確實執行」）。CI step 只能是 workflow 內的 `run: |` 原文（`ci-rule-check.ps1` 逐字抽出執行），沒辦法共用腳本，所以三處必須同步：都掃描 `src/testmod/java` 下全部 `*.java`；掃描前先去掉註解行（去掉前導空白後以 `//`、`/*`、`*` 開頭的行，含 Javadoc 內的 `{@code @GameTest}`）；嚴格掃描是 `@GameTest(...)` 緊接 `public void <名稱>(TestContext`；前綴規則是 `m4_legacy_runtime` 屬 legacy run、前綴 `m5_universe` 屬 M5 run、其餘屬 default run（與 testmod 的 `M4LegacyGameTestFilterMixin` 相同）。改其中一處時，三處與 filter 一起改，並以 `ci-rule-check.ps1` 與 M5 gate 重跑。

## Evidence 位置

- `-EvidenceRoot`：預設 `<repo>/.superpowers/verification/`（gitignored）。只接受 repo 內、路徑無 reparse point、且被 gitignore 的目錄，否則在建立任何東西之前就拒絕。
- `run-all.ps1` 每次建立新的 `run-all-<GUID>/`，內含 `console.log`、`gate-<label>-<GUID>/`、`recovery-run-<runId>.json`、`ci-<label>-<GUID>/` 與 `summary.json`。個別腳本直接在 `-EvidenceRoot` 下建立自己的 root。
- 由 testmod 固定、不受 `-EvidenceRoot` 影響的位置（`ProbeEvidenceOwner`、`M4CandidateRecoveryProbe` 只接受這個路徑）：M3 receipt 在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/task-9-final-<nonce>/`、`transfer-final-<nonce>/`，M4 recovery receipt 在同目錄 `recovery-<nonce>/`。目錄不存在時依程式應由 harness 建立，但還沒在沒有 `.superpowers/` 的 clean clone 上實跑。M3 gate root 另存每個 phase 的 receipt 複本。
- `run/` 內：GameTest 與 main-only 把 `run/gametest/world`、`run/gametest-legacy/world`、`run/gametest-m5/world`、`run/server` 移到 `<原路徑>.task9-backup-<GUID>-<tag>`，跑完後把 fresh 結果移進 gate root 的 `*-fresh/`，再把原檔移回並逐檔比對 hash（結果在 `<tag>-restore.json`）。M3 與 M4 recovery 每次新建 `run/m3-universe-m4-task9-<GUID>`、`run/m3-transfer-m4-task9-<GUID>`、`run/m4-recovery-<GUID>-<scenario>`，跑完保留。
- 失敗時保留全部 root 與 receipt，修正後以新的 root 重跑，不在失敗 root 上改判。若 restore 失敗，原檔仍在 `.task9-backup-*`，先查明原因再手動移回，不要刪除。

## 預期結果（M5 F3c）

M5 實作期間，程式與測試逐 task 增加，數字以最新一次 run 為準；每個 task 的數字變化記在 M5 implementation note（`docs/implementation-notes/2026-10-01-m5-collapse-passage.md`）。下表是 M5 F3c fix round 2 的 code commit 上以 `run-all.ps1` 實跑的結果，commit、run root 與耗時記在 note 的 F3c 段（Fix round 2）。M1–M4 收尾時（code `ba854e8`，2026-10-01 在 HEAD `e55b7f9` 實跑、以 C＝`c262b2a` 採計）的數字是：default 146/146（`_windows` 73）、per-test receipts 28＋1、JUnit 403、release 299 entries（256 classes）、sources 190，其餘各項與下表相同（當時沒有 M5 GameTest 與 batch 範圍兩項）。

| 項目 | 預期 |
| --- | --- |
| default GameTest | 160/160，其中 `_windows` 80；0 failure／error／skipped |
| per-test Universe probe receipts | default 38＋legacy 1，全部 exact PASS |
| JUnit | 560，0 failure／error，2 skipped（只限 `NonWindowsPlayerCheckpointStoreTest`）；Windows checkpoint 三個 class 共 17 個實際執行 |
| legacy GameTest | 1/1 |
| M5 GameTest | 4/4（sanity 與三個 harness test）；沒有 M4 boundary receipt；每個 M5 test 的 catalog 增量都已核對並串接（各 ＋1，0 → 4 筆），suite 結束時 4 筆、digest 與最後一個 test 相同 |
| batch 範圍 | default、legacy、M5 三個 run 的 XML 都恰好等於原始碼中屬於該 run 的宣告（160、1、4） |
| main-only oracle | PASS（三層 testmod 命中 0、world keys exact、exact restore） |
| M3 | lifecycle 4＋transfer 5 receipts PASS |
| M4 recovery | 24/24 phases，各自獨立 JVM |
| CI 綠燈規則 | Windows、Ubuntu 兩個 step 都 PASS（讀 default 與 M5 兩份 XML） |
| JAR | release 359 entries（312 classes）、sources 210；testmod／gametest／probe 命中 0（`dev/quantumchamber/compat/ModPresenceProbe` 是合法的 production class） |

## 來源對照

原始檔都在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/`，保持原狀未修改。

| tracked 檔 | 原始檔 | 原始檔 SHA-256 | 相對原始檔的改動 |
| --- | --- | --- | --- |
| `gates.ps1` | `task9-gates.ps1` | `34eaecabf9d4685771d53482130c04d496bbdf3e396f6c2d6fc06055249851e8` | 路徑與參數化；gate root 改名 `gate-<Label>-<GUID>`，輸出 `GATE_ROOT=`；移除 `RedOwner` 模式（Task 9 owner 修正前的一次性 RED；現行 testmod 已接受 evidenceOwner，該 RED 的前提不再成立）；classload／m4-boundary 只複製本次觀察到的 JVM PID（整合審查 R1 指定併入本 task）；`-Mode` 必填，`-Label`／`-Only` 加輸入驗證。M5 F3c：新增 `M5GameTest` 模式；三個 GameTest run 加上 XML 新鮮度與 batch 範圍核對，M5 run 改核對逐 test 的 catalog 增量。F3c fix round 1：增量以 digest 串接、核對 suite 結束時的 catalog；宣告掃描先去掉註解行 |
| `main-oracle.ps1` | `task9-main-oracle.ps1` | `7175c03aff38e3c600e06c75248f3a81c660b6a671bbe142c627a54e48bba96b` | 只改 repo root |
| `m4-recovery-probe.ps1` | `run-m4-recovery-probe.ps1` | `00fca9fe186d5efac7d6df5d30dd7503c15d0d37f8a8b718977faccdb0bb0874` | 路徑與參數化；彙整 JSON 改寫到 `-EvidenceRoot`，已存在時拒絕覆寫 |
| `ci-rule-check.ps1` | `ifix3-ci-rule-check.ps1`（取代 `ifix2-ci-rule-check.ps1`） | `279b7b9f46d729ec78bfe72de0e201c09d84a772c50d99751c6e0614f4ded808` | root 改為 `-EvidenceRoot`；輸出標記 `CI_RULE`；注入的失敗訊息改為「CI 規則檢查刻意注入的失敗」；`-Label` 加輸入驗證。M5 F3c：必填 `-M5GameTestXml`，fixture 同時寫入兩份 XML；新增 `-MutationXml` 與 `-SourceMutation rename-windows` |
| `runtime.init.gradle` | `task9-runtime.init.gradle` | `40cccdea23acc6520e92ab03198acd2881132b54962dfca53216e4679dbe75e0` | 只加註解 |
| `probe.init.gradle` | `task9-probe.init.gradle` | `3d14ca4f399a8cc75797b12f4d38c73b02ca149500ef024c53f212634e3c678d` | 只加註解 |
| `run-all.ps1` | `ifix4-final-green.ps1`＋`ifix3-build-summary.ps1` §1–§8 | `dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d`／`63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5` | 原本以名稱前綴在 SDD 目錄取最新建立的 root，改為在本次 run root 內要求恰一個；CI 綠燈結果改為 `verdict` 非 PASS 即失敗；summary 去掉該輪固定數量與一次性 RED/GREEN 核對，只保留通用不變式並輸出數字；核對執行前後 HEAD 相同。原 summary 另有幾項核對沒有保留：tracked 變更即失敗（改為 `STATUS` 行與上方採計規則）、workflow 必須等於 HEAD 版本、`M4CandidateDoorGameTests` 每個 `@GameTest` 都呼叫 begin、非 Windows gate 的案例白名單、`Set-StrictMode`。M5 F3c：新增 M5 GameTest 步驟；CI 規則同時讀 M5 XML；summary 加上 M5 XML、三個 run 的 batch 範圍與 M5 oracle。F3c fix round 1：CI 綠燈結果必須沒有 mutation／sourceMutation；summary 加上 suite 結束的核對 |

## 未 tracked 的腳本

下列檔案被 tracked 文件引用（含 `ifix*-gradle.ps1` 等 glob 寫法），或屬於 AGENTS 要求盤點的 `.tools/`；它們是特定一輪的一次性腳本、已被上表取代，或已不適用目前 code，所以不 tracked。原始檔留在原處；路徑相對於 `.superpowers/sdd/`（`.tools/` 除外）。

| 檔案 | SHA-256 | 不 tracked 的理由 |
| --- | --- | --- |
| `2026-09-21-m4-candidate-doors/ifix2-ci-rule-check.ps1` | `81aba12a98f7f2ef1e0f6c7dded500631abfccf6968849612c63d752853c9153` | 被 `ifix3-ci-rule-check.ps1`（→ `ci-rule-check.ps1`）取代，後者多了 SourceMutation |
| `2026-09-21-m4-candidate-doors/ifix1-build-summary.ps1` | `6767126e35e6b977c57ddc4cbbdda9bc6efa353b16e9e70e21906a23a31cc28a` | round 1 一次性彙整，寫死該輪數量與 RED/GREEN roots |
| `2026-09-21-m4-candidate-doors/ifix2-build-summary.ps1` | `253678540c8461d64684ceb847da8213a0151b7f23a2d83f95f892f9a1009933` | 同上（round 2） |
| `2026-09-21-m4-candidate-doors/ifix3-build-summary.ps1` | `63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5` | 同上（round 3）；通用核對已併入 `run-all.ps1` summary |
| `2026-09-21-m4-candidate-doors/wfix1-build-summary.ps1` | `ca074270b6dfbf1d4924db8a914dbaf3968f2ff8aeef8d4ab57867fd6f871f1e` | M4 whole-branch fix1 一次性彙整 |
| `2026-09-21-m4-candidate-doors/task10-phase2-verify.ps1` | `7aed86be691b3cf128255a323c720ccf46a77f5489f2ca099209fb3b1f9281ec` | Task 10 一次性核對，寫死 `752ada1` 與當時 roots |
| `2026-09-21-m4-candidate-doors/ifix1-gradle.ps1` | `cf5ecb5868031bb79dc8f4fd32c527ec41028f89a986dc165d42d1288086febf` | 只包一層 Gradle 編譯／JUnit 呼叫並記錄 console，focused 測試可直接用 Gradle 或 `gates.ps1 -Mode Windows` |
| `2026-09-21-m4-candidate-doors/ifix2-gradle.ps1` | `7c9ed4d959f1d88c905bb66018ed4885250d773b553f707019c4e90971cf5bad` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix3-gradle.ps1` | `7b2241ba9542272f36fcb543714359eff0aef84a9c39be4f34874598bcb2f152` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix1-final-green.ps1` | `d414e20e92a90466bfe91fdd000d2acd648af617edb35d9415ba71e895ac02a3` | 該輪 wrapper，寫死 label 與 console 名稱；由 `run-all.ps1` 取代 |
| `2026-09-21-m4-candidate-doors/ifix1-final-green2.ps1` | `f6a2570ae173de1b21589ce5d478edefa9ad8e51d4d51f7400962d329eecb276` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix2-final-green.ps1` | `34d4ead3b41c783f7c29b27054dd10b6bf610d7e61b83f1595ba98bfa5fd3c26` | 同上，另含該輪 CI mutation 組合 |
| `2026-09-21-m4-candidate-doors/ifix3-final-green.ps1` | `b7afb1245db37cfeb80172d795e416eaa039a27698988d356f077b108e411455` | 同上，另含舊 workflow（`e20cd9d`）對照 |
| `2026-09-21-m4-candidate-doors/ifix4-final-green.ps1` | `dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d` | 一般化為 `run-all.ps1` |
| `2026-09-21-m4-candidate-doors/wfix1-final-green.ps1` | `6a7c420945060d017c8a2d8891f46c62c1a2dbba4f9b30a0bede74dea7627d2e` | M4 whole-branch fix1 wrapper；由 `run-all.ps1` 取代 |
| `2026-09-21-m4-candidate-doors/wfix1-final-green2.ps1` | `89fa90b826a7caf286cf25311ebf2ca9b53a10f34bc0822a055adc4f07560f2c` | 同上（M3／recovery 續跑） |
| `2026-09-21-m4-candidate-doors/task9-fix1-green.ps1` | `1ee5a74380c375ccc84334aca51b9f8f2911167b6134f595ea2c2a3eeac5cf9b` | 兩行 wrapper，寫死 fix1 labels |
| `2026-09-21-m4-candidate-doors/task9-fix1-oracle-fixtures.ps1` | `a7e9277efdaffd78aa8ac4e394514136767c753e111ee52e13107aa17f89ebf2` | main-oracle 的一次性負向 fixture，來源寫死某個歷史 gate root |
| `2026-09-19-m3a-dynamic-universe-backend/run-gate-a.ps1` | `55c2e883f842020ae485334e03eb0a4d85d5c40a93ab7c83c398c1a57b333ddc` | 需要每個 nonce 預先匯出的 Loom runtime JSON，GateB 寫死 nonce 與 receipt hash；同樣 4 phases 已由 `gates.ps1 -Mode M3`（Gradle `runM3Universe`）取代 |
| `2026-09-19-m3b-server-transfer-readiness/run-transfer-probe.ps1` | `65f018604ff3414833ad16af93c8f3a444b49119b53b256ce3a615d314237609` | 需要預先匯出的 runtime JSON；transfer 5 phases 已由 `gates.ps1 -Mode M3` 取代，各 phase 的結果判定由 probe 內 `require` 決定 receipt status |
| `.tools/m1-server-smoke.ps1` | `5cf237004616bf1622299d03e1776d456fbf9a1f9fc35005b5f5894460ba9438` | M1 時期 smoke：直接在現有 `run/server` 世界放方塊、沒有 fresh move／restore，且讀固定的 `m1-smoke` 存檔；由 `gates.ps1 -Mode Main` 與 GameTest 取代 |
| `.tools/read-m1-registry.py` | `ce3407f0f76cd76a4ff7dd48c413ed0940d9ea9b74cc14b5354dbfaf1527789f` | 斷言 registry `SchemaVersion == 1`，目前 `ChamberRegistryState` 是 schema 2；沒有 tracked 文件引用 |
| `.tools/m12-lighting-audit/lambdynamiclights-3.1.4+1.21.1.jar` | `c91de6935c866c85a68f96ac051dabae6a23b402e345b7141b6fb3c39998612b` | 第三方 JAR 的唯讀 audit 副本，不是腳本；建置以 `gradle.properties` 的 SHA-512 從 Maven 解析 |
