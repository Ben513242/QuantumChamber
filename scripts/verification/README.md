# M1–M4 驗證 harness

M1–M4 automated gates 的 tracked 版本。原本只放在 gitignored 的 SDD workspace `.superpowers/sdd/2026-09-21-m4-candidate-doors/`，刪除 worktree 或 `.superpowers/` 後就無法重跑；這裡的腳本只改路徑、參數與名稱，安全行為（fresh world move／逐檔 hash exact restore、owned roots、拒絕覆寫、main-only 三層 oracle、JAR testmod 檢查）與原始檔相同；PID 過濾等少數邏輯差異與拿掉的核對，逐項列在「來源對照」。

M5 的 gate 擴充也放在這裡。M5 F3c 新增 M5 GameTest run（M5 plan 決策 6）：會配置或物化 Universe、或會留下 M5 record 的 GameTest（batch 前綴 `m5_universe`），在獨立的 fresh 世界 `run/gametest-m5` 執行，不放在 default run。

## 環境需求

- Windows，repo 位於本機固定 NTFS 磁碟（Windows checkpoint 只接受本機固定 NTFS，`_windows` GameTest 與 checkpoint JUnit 都依賴它）。
- PowerShell 7（`pwsh`）；`git` 在 PATH。`m4-recovery-probe.ps1` 與 `m5-collapse-probe.ps1` 另以 Windows PowerShell（`powershell.exe`）啟動 Gradle。
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
| `m5-collapse-probe.ps1` | M5 塌縮的跨 JVM crash／restart probe（spec §12 的 crash 窗口），每個 phase 一個 JVM；見「M5 collapse probe」 |
| `ci-rule-check.ps1` | 從 `.github/workflows/build.yml` 逐字抽出 step 的 `run: \|`，在 fixture 複本上以 pwsh 執行；fixture 同時放 default 與 M5 兩份 GameTest XML。可加 XML／原始碼 mutation 證明規則會失敗 |
| `runtime.init.gradle` | 所有 gate 的 Gradle 呼叫都以 `-I` 載入：run* 接 stdin、寫 `classload-<pid>.log` |
| `probe.init.gradle` | M3 gate 另外載入：把 `-Ptask9.*` 轉成 probe 的 system property |
| `run-all.ps1` | 依序跑 Full、Legacy、M5 GameTest、Main＋oracle、M3、M4 recovery、M5 collapse probe、CI 綠燈規則（Windows／Ubuntu 兩個 step），最後以唯讀 summary 核對 |

## 執行

全部 gates（M5 F3c 時約 15 分鐘；F6-1 加上 M5 collapse probe 之後的實測耗時記在 M5 implementation note 的 F6-1 段；以下指令在 repo 根目錄執行，腳本本身依檔案位置找 repo root）：

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
& "$v/m5-collapse-probe.ps1"                       # -Only w2,w3 只跑指定 chain
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
- **F5-1 的通道 helper**（M5 run）：
  - F5-1 起，準備完成的同一個 tick 就在 §10.2 的前置條件成立時 checked 提交 PASSAGE_READY，原艙門改為 passable 量子膜（`doorPassable(world, frame)` 判斷 25 格都是朝向的 passable）。要讓 session 在準備完成之後仍留在 PREPARE，用 `holdReady(context, sid, hooks)`：testmod `CollapseReadyHoldMixin` 掛在 `CollapseService.ready` 的開頭（`M5TestHooks.READY_HOLD`，經 `installHook`），裝著時這個 session 不提交；`release()` 之後不再暫緩，`hits()` 是暫緩的次數，test 要以它當 witness。F4b-3 的 T-MEM、T-FAIL 與 F5-1 的 `m5_universe_passage_crossed_pending`（等原生 autosave 的窗口開始才放行，讓通道的 60 秒從窗口內起算）用它。
  - 要讓通道的時間從原生 autosave 的窗口內起算時（`m5_universe_passage_crossed_pending` 的作法，F5-1 fix round 1）：先 `holdReady`，等暫緩真的發生（`hits()>0`；準備完成之後才會呼叫提交）之後，才開始等 `afterAutosaveClear` 的窗口，窗口開始時才 `release()`。順序反過來時，窗口可能在準備完成之前就開始，放行時暫緩根本沒有發生，通道的起點就不在窗口內。
  - `installStepPause(context, sid, hooks)`（F5-1 fix round 1）：可撤銷的推進暫停（`M5TestHooks.STEP_PAUSE`，經 `installHook`）。裝著、還沒 `release()` 時，testmod `CollapseSuspendMixin` 在 `CollapseService.step` 的開頭取消這個 session 的推進，等同 production 中推進被略過的 tick（journal 有未 checked 的 authority、隔離中、整個 tick 無法執行）；`m5_universe_passage_stale` 用它。與不可撤銷的 `M5TestHooks.suspendCollapse` 不同。mixin 呼叫會計數的 `M5TestHooks.cancelStep`，test 以 `hits()` 當 witness；`M5TestHooks.collapseSuspended` 是同一個判斷的純查詢，不計數（F5-2 Step 0）。
  - `standInDoorway(player, frame, x, y, z, yaw, pitch, velocity)`：把玩家放到來源艙門口的區域座標（同一個世界）。跨越是「上一個 tick 腳在門板中線內側（local z≥0.5）、這個 tick 在外側（z<0.5）」，所以內側與外側要排在相鄰的兩個 tick。`whenTicks` 的起點不要是目前的 tick：`whenTicks` 以排定的 tick 呼叫 `ready`，排在目前的 tick 時，原版可能下一個 tick 才執行它，`ready` 拿到的 tick 就比實際的早一個，之後以它為基準排的兩步可能落在同一個 tick、以雜湊順序執行（F5-1 開發時兩個 test 因此看不到跨越）。同一個 callback 要排很多步時，用 `M5UniverseGameTests.sequence(context, tick, hooks, steps)`：每一步的 callback 只排下一步（避開上方「前提」所說的 rehash 風險）。
  - `installNativeMoveFault(context, player, destination, destinationFailures, otherFailures, hooks)`：通道的原生移動被拒（testmod `SessionTransferFaultMixin` 在 `SessionTransferService.move(ServerPlayerEntity, …)` 的開頭多問一個條件，`M5TestHooks.NATIVE_MOVE_FAULT`，經 `installHook`；既有的 M2 條件不變）：這位玩家往 `destination` 的前 `destinationFailures` 次、往其他世界（回滾）的前 `otherFailures` 次原生移動回 false。1／0 是 `FAILED_ROLLED_BACK`，1／1 是 `FAILED_RECOVERY_REQUIRED`。注入點是 `require = 0`，所以 test 要以 `destinationHits()`／`otherHits()`／`hits()` 當 witness。
  - roll-forward 的寫出失敗之後，recovery 的 RETRY_FLUSH 在下一個 tick 就重試；它的退避（200、400、800…）從它自己的寫出失敗才開始。以 `FlushFault` 讓「未確認」持續到某個時點時，前 N 次要把這次立即的重試算進去（F5-1 的 `m5_universe_passage_recovery_pending` 是 2，`m5_universe_passage_crossed_pending` 是 4）。
  - SEALED(TRANSFER_RECOVERY_REQUIRED) 是永久的 recovery authority，沒有 API 可以終局或重試：`removeFixture` 不移除它（記一則 INFO），留在這個 run 的 journal。它的原參與者不再被佔用，原艙是那個 test 自己的。
  - 「同一座艙可以開新 session」的 M2 入場（`_windows`）：等它真入場（走廊發布 358 扇側門，同 `NetherChamber.whenEntered`）才斷電收尾，等待上限與 `tickLimit` 要給足（`m5_universe_passage_expire` 實測入場約 1300 ticks、返還約 20 ticks）。入場途中（ARMING）斷電的返還不在這裡驗。
- **F5-2 的比較器與量子膜正規化**（M5 run）：
  - 比較器：PASSAGE_READY、已供電、enabled 時 Controller 是 `ChamberState.PASSAGE`、比較器 15；COLLAPSING、SEALED（含 TRANSFER_RECOVERY_REQUIRED）與斷電時是 ARMED（11）。test 以 Controller 的 `chamberState()` 與 `getComparatorOutput` 一起斷言（`M5UniverseGameTests.controllerSignal`）。看比較器的斷言要考慮 Controller 每 20 tick 的排程刷新：要證明「某一步自己刷新了 Controller」時，不要等下一個 test tick 再看（排程刷新可能已在同一個 server tick 的 world tick 裡先刷新），改用在那一步的當下記錄的探針。
  - `installSealProbe(context, sid, world, controllerPos, hooks)`：進入 SEALED 的 Controller 探針（testmod `CollapseSealProbeMixin` 掛在 `CollapseService.sealCommitted` 的開頭與 RETURN，`M5TestHooks.SEAL_PROBE`，經 `installHook`）。`before()`／`after()` 是每一次 `sealCommitted` 開頭與 RETURN 那一刻的比較器與 `ChamberState`，`hits()` 是記到的 RETURN 次數，test 要以它當 witness。T-TRR 用它鎖住「進入 SEALED 之後刷新 Controller」：進入 SEALED 時參與者在門口、門已是一般開門，關門是 UNCHANGED（不經門服務的刷新），所以 RETURN 的 11 只來自那一次刷新。
  - 找不到所屬 session 的量子膜（spec §10.6 :613）：已註冊的原艙在 Controller 刷新時，門帶著量子膜、而 presence 不是 COLLAPSE／PASSAGE，塌縮流程就排入下一個 tick 正規化為一般開門。INFO 帶 `TICK_LOG_MARKER`（不屬於任何 session），以 `chamber=<UUID>` 篩選：排入記「找不到所屬 session、卻帶著量子膜的原艙門：下一個 tick（tick N）」，完成記「找不到所屬 session 的量子膜已正規化為一般開門（APPLIED，tick N）」；套用時發現有 COLLAPSING、SEALED、PASSAGE_READY 的 session 時丟棄，記「…不再正規化找不到所屬 session 的量子膜」（帶 `LOG_MARKER` 與 `session=`）。所以 test 收尾（終局 API 移除 fixture、斷電）之後還留著量子膜的原艙，會在下一次刷新被正規化（M5 run 中多數走到 PREPARE 的 test 結束時各一則）；要在 test 期間保留量子膜的原艙，要讓它一直有 M5 塌縮狀態的 session，或不註冊（default run 的 F3b 量子膜 test 用未註冊的艙）。
- **F6-0 的 helper**：
  - `holdEntityStorage(context, world, chunks, hooks)`：原生存檔 checkpoint 的 entity storage 就緒保留（testmod `ChunkSaveCheckpointEntityHoldMixin` 掛在 `ChunkSaveCheckpoint.loaded` 的開頭，`M5TestHooks.ENTITY_STORAGE_HOLD`，經 `installHook`）。裝著、還沒 `release()` 時，`world` 的 `chunks` 在 checkpoint 的就緒判斷（`ChunkSaveCheckpoint.ready` 與 `save` 的前置條件）裡回「entity storage 尚未載入」；只改 checkpoint 自己的判斷，原版的 entity 管理、`ServerWorld.isChunkLoaded` 與走廊的 `leaseReady` 都不受影響。`hits()` 是保留生效的次數，test 要以它當 witness。default run 的 `m5_f60_source_entity_storage`（`M4CandidateDoorGameTests`）用它。
  - F6-0 起，MEASURED 與 COLLAPSING 的走廊清理在 checkpoint 之前先問 `MeasuredWorldSaveCheckpoint.ready`（來源艙與走廊 lease 的 chunks 都已載入、entity storage 也已載入），未就緒時下一個 tick 再試，不清除、不掃描、不存檔，也不記 WARN、不進 checkpoint 的退避。所以「來源 chunk 的 entity storage 尚未完整載入」不再以「保留 receipt 等待安全返還」WARN 出現；要驗 checkpoint 失敗的退避與去重，仍用 `M4CandidateDoorGameTests` 的 checkpoint 探針（`m4_ifix1_checkpoint_backoff`）。
  - `installConvergeFault(context, sid, hooks)`：隔離 session 的收斂失敗（testmod `QuarantineConvergeFaultMixin` 掛在 `SessionRecoveryState.convergeQuarantined` 的開頭，`M5TestHooks.CONVERGE_FAULT`，經 `installHook`）。recovery 的收斂排在塌縮流程之前，test 以 `SessionRecoveryState.quarantine(sid)` 造出的隔離，沒有這個注入時同一個 tick 就被收斂，塌縮流程看不到隔離中的 session。裝著時這個 session 每一次收斂都失敗（recovery 記一則「隔離 session 尚無法收斂」WARN、維持隔離）；`release()` 或 hooks 釋放之後，下一個 tick 收斂（INFO「未 checked authority 已收斂」）。`hits()` 是注入的次數。`m5_universe_door_quarantine` 用它。
  - `installJournalPutFault` 的 `ticks()`：每一次拒絕的 server tick，用來斷言重試的間隔（`m5_universe_seal_rejected_retry`：[R, R+200]，第三次在 R+600 寫入成功，以 `installSealProbe` 的 `after()` 記下 `sealCommitted` 的 tick）。數「被拒」的 WARN 時，以「寫入 SEALED沒有完成（REJECTED）」篩選：寫入成功的那一次若遇到 journal 落盤失敗（Windows 的 `AccessDeniedException`，已知偶發），會另記一則 ROLL_FORWARD_PENDING 的「寫入 SEALED」WARN。
  - 原版 `TestContext` 背後的 `GameTestState` 一律經 `M5GameTestSupport.gameTestState(context)` 取得（testmod 的 accessor mixin `TestContextStateTestAccessor`），runAtTick 表經 `GameTestStateRunnablesTestAccessor`；不要再以反射讀 `TestContext.test`（F4a-0 quality re-review Minor 3）。
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
  - 玩家收到的 actionbar 與 title（F5-3 起；F6-1 Step 0 補進本節）：`hud(connection)` 一次讀掉玩家目前收到的全部封包，回傳 `Hud(overlays, titles)`。`overlays` 是 actionbar 的翻譯 key（overlay 的 `GameMessageS2CPacket` 與 `OverlayMessageS2CPacket`），`titles` 是 title 系列封包依送出順序的描述（`fade <淡入>/<停留>/<淡出>`、`title <key>`、`subtitle <key>`、`clear`、`reset`）。到達提示的預期是 `arrivalTitle(key)`（`["fade 10/70/20", "title " + key]`，字面值）；key 也以 test 自己的字面值斷言（`M5UniverseGameTests` 的 `ARRIVED_NEW_KEY`／`ARRIVED_EXISTING_KEY`；`PassageService` 是 package-private，testmod 看不到它的常數）。一律精確比對：「有收到」比對整份 `overlays` 或 `titles`，「沒有收到」斷言清單為空，不要只用 `contains`。
  - 陷阱：`ConnectedGameTestPlayer.drainOutbound(type)` 會讀掉並釋放**所有**型別的封包，只回傳 `type` 的那些。所以 `overlayKeys` 也會讀掉 title，先呼叫 `overlayKeys` 再讀 title 會讀不到。同一個時點要同時看 actionbar 與 title 時，只呼叫一次 `hud`。要清掉之前的封包時，先呼叫一次 `hud`（或 `overlayKeys`）；跨很多 tick 的等待之後要斷言「結束時恰好收到哪些」，在結束之前先清一次（例如 `crossed_write_failure_pauses_the_passage_until_it_converges_then_expires` 在到期之後、收斂之前先清掉兩人的封包）。
  - `NetherChamber.open(context, controller, hooks)`：Nether 的真原艙＋一位在艙內的真玩家（強制載入並以石頭圍住）；`whenEntered(ready)` 沿用 `open` 的 context 與 hooks，同樣以呼叫當下的 tick 為基準：t0+2 供電，從 t0+3 起等真入場、358 扇側門發布。
  - 候選門：`candidateDoor(pages, target, sid, 種類)`、`sourceDoor(fixture)` 回傳 `CandidateDoor(key, cell)`。
- **前提**：
  - 用探針或稽核的 test 必須獨占一個 batch：同一個 batch 的 test 同時執行，第二個安裝會丟出。gate 只對 `m5_universe` 強制一個 test 一個 batch，default run 靠這條規則與安裝時的 fail-fast。
  - 有界等待的總長度（`whenTicks` 的上限、`afterAutosaveClear` 的等待與窗口、`awaitConvergedThenComplete` 的 maxTicks，加上呼叫時的 tick）要小於 `@GameTest(tickLimit = …)`。超過時原版判 test 逾時失敗，之後不再執行它的任何 callback：hooks 不會釋放，探針與稽核只由 TestListener 移除，其他收尾（fixture、log capture、玩家連線）都不會執行。
  - 等 PREPARE 完成（「準備完成」或 PASSAGE_READY）的有界等待：預載是 wall-clock 的 chunk 載入，GameTest 的 tick 不節流，機器忙時以 tick 計的等待會變長（F6-2c Step 0 之前的 400 tick 窗口實測需要到約 380 tick，H5 有一次逾時）。上限用 production 的 §9.1 門檻 600 tick（`CollapseService.WAIT_WARN_TICKS`，從進入 PREPARE 起算；超過時 production 自己記 PREPARE 等待的 WARN），`tickLimit` 跟著留足。F6-2c Step 0 起 `passage_ends_with_a_plain_open_door_when_the_source_loses_power` 與 EXISTING 再造訪、退化的三個 test 中 D1 的準備是這樣寫的；其他 test 的 400 tick 窗口尚未改。
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

## M5 collapse probe

`m5-collapse-probe.ps1` 是 M5 塌縮的跨 JVM crash／restart probe（M5 F6-1；spec §12 的 crash 窗口）。JVM 內的部分是 testmod 的 `M5CollapseRecoveryProbe`（entrypoint 與 phase 表）：沒有 system property `quantumchamber.m5collapse.phase` 時不註冊任何 listener、不建立 runner 與 chain，其他 run 不受影響；屬性存在但設定不合法時開服前就失敗。F6-2a 把 probe 拆成幾個 package-private 類別（都在 `src/testmod/java/dev/quantumchamber/gametest/`）：

| 類別 | 內容 |
| --- | --- |
| `M5CollapseRecoveryProbe` | entrypoint、phase 表（`phases()`） |
| `M5CollapseRun` | runner：設定（`Configuration`）、`Phase`／`Step`／`PhaseFactory`、生命週期、`guarded`、`expect`、`halt.json`／`final.json`、讀回前一個正常停止的 phase（`readStoppedReceipt`）、crash 點與 probe 端安裝的 hook（`install`）。F6-2c Step 0 起不依賴 entrypoint 與窗口表（halt receipt 的 detail 描述由窗口表交給 `armCrash`），chain 以唯讀的 `server()`、`source()`、`journal()`、`capture()`、`startupLog()`、`ticks()` 讀它的狀態 |
| `M5CollapseWindows` | 窗口表：每個 crash 窗口一列（條件、halt 前取證、restart 開服時的預期 `AtRestart`、PREPARE 的預期）；F6-2c 起也有 PASSAGE_READY 期間與終局的窗口 |
| `M5CollapseFixture` | 原艙、參與者清單與它的子集（`Who`：`EVERYONE`、`NOBODY`、`SELECTOR`、`OTHERS`，在 step 執行時才決定）、收據（選門者由收據的 selection 推導）、這條 chain 的 id、共用的查詢與動作（`arm`、`joinAll`、`tickets`） |
| `M5CollapsePassage` | restart 共用的 step：可選的三段 `resolve()`、`ready(prepared, who)`、`cross(who)`，與三段全員跨越、再接 (b) 終局的 `steps(prepared)`；每個 tick 的不變量；spec §12 的終局 helper（`expectEnd(End)`，`End` 逐人指定位置） |
| `M5CollapseDormantChains`、`M5CollapseLiveChain`、`M5CollapseRevisitChains`、`M5CollapsePassageChains` | chain：DORMANT 收據（W2～W6；restart 也用於 W6x）、live session（W1）、EXISTING 再造訪（W6x 的 crash、退化的 setup 與 restart）、通道與終局（F6-2c：W7 與其餘的重啟變體） |
| `M5CollapseUnloadChain` | chain：chunk 卸載的 livelock 防護（H5，`unload`；不經塌縮流程） |
| `M5CollapseProjectionLoad` | restart 在 JOIN 之前載入投影 Controller、觀察 load-sync |
| `M5CollapseDiskEvidence` | 讀磁碟上的權威；allocation 與 projection ledger 經 `dev.quantumchamber.collapse.CollapseLedgerTestAccess`（testmod 的 split-package accessor）用 production 的嚴格 `fromNbt`，格式不符時丟出，不會讀成空的 ledger |

```powershell
& scripts/verification/m5-collapse-probe.ps1               # 全部 chain
& scripts/verification/m5-collapse-probe.ps1 -Only w2,w3   # 只跑指定 chain（RED 用）；以 pwsh -File 呼叫時也接受 "w2,w3"
```

- 每條 chain 建一個新的 owned root `run/m5-collapse-<runId>-<chain>`，每個 phase 一個 JVM：Gradle `runM5Collapse`，也就是 `build.gradle` 的 `m5Collapse` run。這個 run 刻意不帶 `-Dquantumchamber.test.collapseSuspended=all`（M4 recovery 用的全域暫停），probe 也斷言 JVM 參數裡沒有它。
- phase evidence 由 testmod 固定在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>/`，不受 `-EvidenceRoot` 影響；彙整在 `-EvidenceRoot` 的 `collapse-run-<runId>.json`。phase evidence 已存在時拒絕執行（禁止重跑）。
- chain 表的每個 phase 標記預期的結束：
  - `stop`：Gradle exit 0、JVM exit code 0、`final.json` 的 status 為 `PASS`（另有 `stoppingSeen`、`stoppedSeen`、`stopRequested`），沒有 `halt.json`。
  - `halt`：JVM 在 crash 窗口寫出 `halt.json`（CREATE_NEW＋force）之後以 `Runtime.halt(77)` 結束，不經正常停止、不存檔，等同 crash。腳本要求：Gradle 非 0、JVM exit code 77（找到 JVM 時先取得 process handle，結束之後以它讀 exit code）、Gradle 輸出有 `finished with non-zero exit value 77`、`halt.json` 的 status 為 `HALT` 而且窗口相符、沒有 `final.json`。窗口沒有到達或斷言失敗時，probe 正常停止並寫 FAIL 的 `final.json`，腳本判 FAIL。
  - halt 必須乾淨（F6-2a）：crash 點的第一次 hit（`hit` 1）、crash 點沒有記下失敗（`crashPointFailure` 為 `none`）、`failures` 是空的，而且停服還沒有開始（F6-2c Step 0）。任何一項不成立時 probe 不當成 crash：status `HALT_REFUSED`（`refused` 列出原因；停服之後是 `stoppingSeen`）、exit 78。腳本、`run-all.ps1` 與 restart 讀回 halt receipt 時（`crashReceiptClean`）也都核對前三項。另外，`SERVER_STOPPING` 一開始（早於 production 的 listener）probe 就移除自己安裝的全部 hook，不經請求停服的停服（例如 crash report 的路徑）也不會再觸發 crash 點。
- 兩種結束都核對：receipt 的 PID、StartTime、startupNonce 與實際的 JVM 相符、標示 testmod runtime；JVM 已結束；`world/session.lock` 可以獨占開啟。同一條 chain 的每個 phase，PID@StartTime 與 startupNonce 都不同。
- 三份表的一致性（F6-2a）：receipt 的 `ending` 必須等於 chain 表的預期結束；receipt 的 `phaseTable`（probe 的整張 phase 表）必須與 `$chains` 的全部 phase 依序相同，任何一邊多出或少了 phase 都判 FAIL。第三份表是 `run-all.ps1` 的 `$expectedCollapse`。
- 失敗訊息：JVM 結束之後的每一項檢查失敗時，訊息都附上 `final.json`／`halt.json` 的 status 與 failures（halt 另有 hit、crashPointFailure、refused）；halt 的 phase 先看 `final.json`，所以「窗口沒有到達、斷言失敗而正常停止」直接看得到原因。
- 逾時（F6-2a）：找到 JVM 之後，`latest.log` 與 Gradle 的輸出都超過 `-IdleSeconds`（預設 90；正常 phase 的 log 間隔最長約 6 秒）秒沒有新內容，或整個 phase 超過 `-PhaseMinutes`（預設 8）分鐘時，腳本核對 PID 仍是本次的 JVM（commandline 含本次的 startupNonce、StartTime 相符），以同一個 JDK 的 `jstack -l` 把 thread dump 存到 phase evidence 的 `jstack-<pid>.txt`，再只結束那一個 java PID（不碰 Gradle daemon），等 Gradle 收尾，寫 `timeout.json`（原因、PID、commandline 是否相符、jstack 檔名、最後 20 行 log 與 Gradle 輸出），判 FAIL。root 保留，禁止重跑。

目前的 chain：

| chain | phases | crash 窗口 | restart 開服時的窗口斷言 |
| --- | --- | --- | --- |
| `w1` | `w1-crash`（halt）→ `w1-restart`（stop） | `COLLAPSING_COMMITTED`：live session 選門之後，MEASURED→COLLAPSING 提交之後、返還之前（磁碟上 P 未返還、lease 還在，P 的 playerdata 在走廊） | P 還沒有返還、走廊 space 已重建、沒有保留式 recovery 的請求；allocation、catalog、discovery、projection 都還沒有 U |
| `w2` | `w2-crash`（halt）→ `w2-restart`（stop） | `ALLOCATION_APPENDED`：allocation ledger flush 之後、catalog flush 之前 | catalog 還沒有 U、U 沒有物化、discovery 沒有 U |
| `w3` | `w3-crash`（halt）→ `w3-restart`（stop） | `CATALOG_ALLOCATED`：catalog flush 之後、物化之前 | catalog 恰多 U 一筆（`EAGER_ENABLED`），開服 bootstrap 已物化 U；discovery 沒有 U |
| `w5` | `w5-crash`（halt）→ `w5-restart`（stop） | `DISCOVERY_APPENDED`：discovery 追加之後、投影之前 | catalog 有 U、已物化、discovery 已有 U |
| `w4` | `w4-crash`（halt）→ `w4-restart`（stop） | `PROJECTION_CELL_WRITTEN`：第一次寫入剛寫完 Controller 那一格；halt 之前刻意把 U 存檔，磁碟上是寫到一半的投影（raw MCA 確認 Controller 在、最後一格還沒寫） | projection ledger 仍是 PENDING、Controller 在磁碟上；JOIN 之前以 probe 的 ticket 載入投影 chunk（之前沒有載入；剛載入時是 ORIGIN、power 未初始化、load-sync 在佇列），load-sync（CONSUMED）之後是 inert 的 PROJECTION、沒有 log 提到它的座標、不受保護 |
| `w4b` | `w4b-crash`（halt）→ `w4b-restart`（stop） | `DESTINATION_CHECKPOINT`：投影寫完並驗證、目的 chunks 的原生存檔 checkpoint 之前 | projection ledger 仍是 PENDING，磁碟上沒有投影方塊 |
| `w6` | `w6-crash`（halt）→ `w6-restart`（stop） | `PROJECTION_COMPLETE`：projection ledger 寫入 COMPLETE 之後、PASSAGE_READY 之前 | projection ledger 是 COMPLETE、Controller 在磁碟上；JOIN 之前載入（同 `w4`：本 JVM 由磁碟載入、辨識），辨識為 inert 的 PROJECTION，不變量 cell 受保護、interior 不受 |
| `w6x` | `w6x-crash`（halt）→ `w6x-restart`（stop） | `PROJECTION_REPAIRED`（EXISTING 再造訪）：REPAIR 寫了格之後、checkpoint 之前 | catalog 不變；磁碟上被改壞的 shell 仍是玻璃、interior 的金塊標記在 |
| `degraded` | `deg-setup`（stop）→ `deg-restart`（stop） | 不是 crash 窗口：開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本）。setup 提交 COLLAPSING 的同一個 tick 在 U 的投影 Controller 正上方放紅石塊（不觸發更新，refresh witness）、正常停服，restart 開服前移走 ledger | 初始化判定 LOST（ERROR 一則，`AUTHORITY_CORRUPT`）、本 instance 不提交新的 COLLAPSING、投影保護索引退化；ledger 不存在、interior 的金塊標記與紅石塊在磁碟上；剛載入時 WasPowered 為 false，CONSUMED 之後為 true（`refreshedThisInstance`：refresh 越過了提早 return） |
| `unload` | `unload-takeback`（stop）→ `unload-shutdown`（stop） | 不是 crash 窗口：chunk 卸載的 livelock 防護（H5，spec §4 H5；根因見 M5 implementation note 的 F6-2b 一節）。takeback：overworld 的 C1 形成殭屍（卸載工作還在佇列時被另一個 ticket 取回同一個 holder），refCount 歸零之後放金塊標記、移除全部 ticket，holder 照常卸載 | 開服時以 raw MCA 讀回 C1 的標記（`takenBackChunkSavedOnDisk`，結果另寫 `marker-on-disk.json`）；之後 C2 形成殭屍、讀檔保留到停服開始之後，2 tick 後停服，必須正常停服（修正前在這裡 livelock，由逾時處置判 FAIL） |
| `w7` | `w7-crash`（halt）→ `w7-restart`（stop） | `CROSSED_COMMITTED`（spec §12 W7）：兩人，選門者是清單的第二位（收據以 `dormantNewReceiptSelectedByTheSecond` 建立，`selectorIsTheSecondParticipant`：驗證選門者由收據推導、不依清單順序）。PASSAGE_READY 之後，以 probe 的 ticket 讓 U 在原艙附近（半徑 view distance＋2）都生成到 FULL（step `universeGenerated`），再原生存檔一次（`nativeSaveDuringThePassage`，模擬跨越之前有過一次 autosave：passable 的門、PASSAGE／15 的 Controller、兩人在來源世界的 playerdata 都在磁碟上）；選門者越過中線，Crossed 的寫入讀回確認的那個 tick 結束時 halt（`HaltAt.TICK_END`）。**halt 之前不存檔**（F6-2c fix round 1）：選門者的 playerdata 在 U，只靠 production 在 Crossed 確認之後的存檔（spec v5.7 §10.3） | record 是 PASSAGE_READY、Crossed＝[選門者]、與 halt 當下相同（`recordKeptAcrossTheRestart`）；磁碟上的門 passable、Controller 是 PASSAGE；選門者的 playerdata 在 U（`selectorPlayerDataInTheUniverse`，目標斷言）、另一位在來源世界（`othersPlayerDataInTheSource`） |
| `readycommit` | `readycommit-crash`（halt）→ `readycommit-restart`（stop） | `READY_COMMITTED`（F6-2-16）：COLLAPSING→PASSAGE_READY 的提交讀回確認時（門改為 passable 之前）halt；halt 之前原生存檔（PREPARE 的 sealed 量子膜與艙內的參與者在磁碟上） | record 是 PASSAGE_READY、沒有人跨過；磁碟上的門是 sealed 量子膜 |
| `terminaldoor` | `terminaldoor-crash`（halt）→ `terminaldoor-restart`（stop） | `PASSAGE_TERMINATED`（F6-2-13）：PASSAGE_READY 時原生存檔，再拿掉紅石塊，(d) 的 checked 終局讀回確認時（改門之前）halt | journal 沒有這個 session（`terminatedOnDisk`）；磁碟上的門 passable、Controller 是 PASSAGE |
| `crossedpending` | `crossedpending-crash`（halt）→ `crossedpending-restart`（stop） | `CROSSED_UNCONFIRMED`（F6-2-17）：journal 的寫出受控失敗，參與者已傳送到 U、Crossed 的寫出沒有確認（ROLL_FORWARD_PENDING）的那個 tick 結束時 halt（`HaltAt.TICK_END`：寫入沒有確認時 production 不存 playerdata，halt 時磁碟上仍在來源世界，`playerDataNotInTheUniverseAtHalt`）；不存檔 | record 是 PASSAGE_READY、Crossed 是空的；參與者的 playerdata 仍在來源世界 |
| `trr` | `trr-crash`（halt）→ `trr-restart`（stop） | `RECOVERY_SEALED`（F6-2-12）：兩人。PASSAGE_READY 之後同 `w7` 讓 U 在原艙附近生成、原生存檔一次（跨越之前，兩人的 playerdata 都在來源世界），選門者照常跨過（production 在他的 Crossed 確認時存下 playerdata，在 U），另一位的原生移動往 U 與回滾都被拒（`FAILED_RECOVERY_REQUIRED`），PASSAGE_READY→SEALED(TRR) 的寫入讀回確認時 halt；跨越之後 probe 不存檔 | record 是 SEALED(TRR)、Crossed＝[選門者]、RecoveryRequired＝另一位，與 halt 當下相同；選門者的 playerdata 在 U（`selectorPlayerDataInTheUniverse`）、另一位在來源世界（`othersPlayerDataInTheSource`） |
| `trrpending` | `trrpending-crash`（halt）→ `trrpending-restart`（stop） | `RECOVERY_REJECTED`（F6-2-12 的 pendingRecovery）：原生移動往 U 與回滾都被拒，這個 session 寫成 SEALED 的 put 一律被拒；第一次被拒（ERROR 一則、通道在記憶體內關閉）之後，退避期過後的重試又被拒時 halt | record 是 PASSAGE_READY、沒有 RecoveryRequired；被拒的 ERROR 只在前一個 JVM 的 log（halt receipt 的 `atHalt.recoveryError`） |

`w2`、`w3`、`w5`、`w4`、`w4b`、`w6` 的 crash phase 相同：fresh 世界蓋原艙（Controller 在 (8,70,8)、朝北）、連線參與者 P 並移到艙外，寫 DORMANT 的 NEW 收據（P 已返還、沒有 lease），原生存檔之後裝上 crash 點，P 由艙外進入觸發塌縮，RESOLVE 或 PREPARE 走到窗口時 halt。`w6x-crash` 先以同樣的方式塌縮到 PASSAGE_READY（U 的投影 COMPLETE），拿掉 Controller 上方的紅石塊讓通道以 (d) 來源艙沒有供電結束，P 移到艙外、恢復供電；之後把 U 的投影改壞一格 shell（玻璃）、在 interior 放金塊標記，寫 DORMANT 的 EXISTING 收據（同一個 U）、原生存檔，P 由艙外進入，PREPARE 以 REPAIR 修好那一格之後、checkpoint 之前 halt。

`w1-crash` 是 live session（比照 M4 recovery probe 的 build／arm）：P 在艙內，以真實的 LOW→HIGH 供電與 Buff 啟動，SUPERPOSITION、358 扇側門發布之後原生存檔（P 的 playerdata 存在走廊），P 以原生互動選一扇 NEW 候選門；下一個 tick 塌縮流程提交 MEASURED→COLLAPSING，`SessionRecoveryState.commitRestore` 讀回確認時 halt。`deg-setup` 先走 `w6x-crash` 的前半段（U 的投影 COMPLETE、通道以斷電結束），在 U 的 interior 放金塊標記，寫 DORMANT 的 EXISTING 收據、原生存檔，P 由艙外進入，看到 flushed COLLAPSING 的同一個 tick 放上 refresh witness、正常停服（RESOLVE 在下一個 tick），所以停服時 P 在艙內。

restart phase 讀回 crash phase 的 `halt.json` 與 `receipt.nbt`（crash 前的 MEASURED 收據），之後 P 真 JOIN（在艙外）、RESOLVE、P 進入艙內、PASSAGE_READY（門 passable、比較器 15、投影 COMPLETE）、P 越過門板中線到 U、(b) 全員都已跨過的終局。投影的 chain 另斷言 PREPARE 怎麼完成（「準備完成」INFO 的模式與格數，READY 時以 raw MCA 確認 checkpoint 已存下）：`w4`、`w4b` 照第一次寫入重寫；`w6` 修復 0 格；`w6x` 再修復那一格、interior 的標記保留。`w1-restart` 的 P JOIN 在走廊、被 recovery 攔截，不經請求就返還到來源世界，清 lease、space 釋放（SETTLED）之後才 RESOLVE；JOIN 之後超過 100 tick 仍在走廊就判為被困住（`notTrappedInTheCorridor`）。`deg-restart` 讀回 `deg-setup` 的 `final.json`：投影 Controller 載入後走 foreign 的分支（索引不認得它；退化的保護另加同位置的不變量 cell），RESOLVE 進 SEALED(AUTHORITY_CORRUPT)（永久原因的 ERROR 一則、沒有 PREPARE），interior 沒有被重寫、ledger 沒有被重建（停服之後也是），P JOIN 在艙內、照一般方式開門，record 仍是 SEALED。spec §12 的四件事是共同斷言，終局由 `M5CollapsePassage.expectEnd` 一次斷言（退化的 chain 也用它，參數不同）：

- 同一個 UniverseId：journal 的 Destination、allocation entry、catalog、discovery（`onlyTheDestinationDiscovered`）、projection entry 與 ACTIVE 的 world key 全部相同；每個 tick 都斷言 catalog 只出現 Destination 的 UniverseId。
- catalog 最多只多 1 筆：NEW 恰多 1 筆，EXISTING 不變。
- 沒有重抽：候選的 context、ledger、selection 與 crash 前的收據 exact 相同，allocation ledger 恰一筆這個 token。
- 沒有人被困住：JOIN 之後參與者在來源世界或 U（`w1` 返還之前可以在走廊），之後到 PASSAGE_READY、跨越成功、跨越的那個 tick 結束時 record 仍在而且 Crossed 恰為每位參與者各一次（`crossedRecorded`）、通道以 (b) 終局；`degraded` 是參與者照一般方式開門、在來源世界。
- 另外（F6-2a）：這個 session 從 `onInitialize` 起沒有任何 ERROR（`noSessionError`；`degraded` 恰為永久原因那一則，`noFurtherSessionError`），非退化的 chain 沒有「M5 authority 初始化失敗」（`noAuthorityInitializationFailure`）。

通道與終局的 chain（F6-2c，`M5CollapsePassageChains`）：crash phase 與 `w2` 等相同地從 DORMANT 的 NEW 收據開始（參與者由艙外進入 → COLLAPSING → RESOLVE），之後依窗口推進到 PASSAGE_READY 或通道期間的那一刻。restart 的開服檢查走窗口表（`expectAtRestart`：record 的狀態是這一列的 `stateAtHalt`，PASSAGE_READY 與 SEALED 另斷言 Crossed 是 `crossedAtHalt`；終局已落盤時改斷言 journal 沒有這個 session），另比對 record 的塌縮欄位與 halt 當下相同。之後依窗口：

- `w7`、`readycommit`、`crossedpending`、`trrpending`（spec §12 的 PASSAGE_READY 列）：第一個 tick checked 終局（「開服時仍是 PASSAGE_READY 的量子通道已結束」INFO 一則，`passageEndedAtStartup`、`endedOnTheFirstTick`）；終局的那個 tick 還沒有改門（`notYetOpenOnTheEndTick`；開服時門已是一般開門（逐步存檔）時不適用，F6-2c fix round 1 起仍每次以同一個名稱斷言「開服時已是一般開門，或終局的那個 tick 不是一般開門」，具名斷言的集合不隨時序變動，spec review M-3），下一個 tick 一般開門（`plainOpenOnTheNextTick`）；終局之後比較器依 presence NONE 重新評估為 IDLE／3（`comparatorReevaluated`，R2 M5：開服時存下的 PASSAGE／15 不保留），registry 的 power state 是 POWERED。之後參與者 JOIN：`w7` 先讓沒跨過的另一位 JOIN 在來源世界的艙內（step `join`），等來源世界的 chunk 生成停下（`sourceSettled`），已跨過的選門者才 JOIN 在 U（`joinCrossed`、`joinedInTheUniverse`；分開 JOIN 的理由見下）；其餘 chain 的參與者在來源世界（`readycommit` 在艙內）；spec §12 的終局斷言（`End.inTheSourceWorld`，`w7` 的選門者 `placed` 在 U）。`w7` 另斷言沒有殘留（`noRuntimeLeft`、`noSourceTicketLeft`、`noDestinationTicketLeft`、`notReturning`），沒跨過的人照一般方式關門，同一個 server 內以真實的 LOW→HIGH 供電與 Buff 開新 session（`newSessionInTheSameServer`：checked ARMING、新的 session UUID、參與者只有他）。
- `terminaldoor`：Controller 的 load-sync 刷新（presence NONE、門帶著量子膜）排入找不到所屬 session 的量子膜的正規化（INFO 記下「下一個 tick（tick N）」，`ownerlessQueuedByTheLoadSync`），比較器重新評估為 IDLE／3；tick N 門一般開門、INFO「已正規化為一般開門」（`ownerlessNormalizedOnTheNextTick`）。
- `trr`：同 `w7` 分開 JOIN：沒跨過的那位（無法回滾、RecoveryRequired 的那位）JOIN 在來源世界的艙內（跨越之前存的位置）→ 來源世界的 chunk 生成停下（`sourceSettled`）→ 已跨過的選門者 JOIN 在 U（`joinCrossed`、`joinedInTheUniverse`）→ 沒跨過的那位在艙內（可重試的原因在這時就會重試）→ 觀察 40 tick：record 一直與開服時相同（`sealedUnchanged`），沒有「SEALED 重試」的 INFO、也沒有這個 session 的 WARN（`trrNotRetried`），presence 是 COLLAPSE、比較器 ARMED／11 → 沒跨過的那位從艙內照一般方式開門（`doorOpensNormally`，record 仍相同）→ spec §12 的終局斷言（選門者 `placed` 在 U，另一位在來源世界）。

`w7`、`trr` 的 restart 分開 JOIN（F6-2c fix round 1）：兩人在同一個 tick 分別 JOIN 在 U 與來源世界時，兩個世界在 restart 一開始同時生成原艙附近（U 以來源世界的 chunk generator 與 seed 建立，兩邊在同一個座標生成同一個結構），`trr` 的 restart 多次撞上原版 worldgen 的 `ConcurrentModificationException`（`StructureTemplate$PalettedBlockInfoList.getAllOf` 的 HashMap，chunk (12,11) 的 `structure_starts`，「Exception generating new chunk」），之後停服卡住、由逾時處置結束。這是另一個**待使用者裁示**的 production 問題（證據與重現條件見 M5 implementation note 的「F6-2c fix round 1」一節），不屬於這兩條 chain 要驗的事；probe 先讓一個世界生成完、再讓另一個世界的人 JOIN，chain 專心驗證「跨過就存檔」。跨越之前讓 U 生成並存檔（`universeGenerated`、`saved`）也是同一個理由：restart 時已跨過的人需要的 U chunk 由磁碟載入。`sourceSettled` 的判準是來源世界 `currentChunkHolders` 的數目與每個 holder 最新狀態 index 的和（`M5CollapseFixture.generationSignature`）連續 20 tick 不變（最多等 400 tick）。

沒有在 halt 之前存檔的窗口，磁碟上的 chunk 是 crash 之前最後一次存下的樣子：原版會在 tick 有餘裕時逐步存下變更過的 chunk（`ServerChunkLoadingManager.unloadChunks`：每個 tick 最多 20 個、同一個 chunk 至少隔 10 秒），所以門的狀態不固定（例如 `trrpending` 的門通常已是一般開門）。playerdata 只在原生 autosave、登出與停服時存；production 另外只在跨越的 Crossed 寫入確認之後存那位玩家（spec v5.7 §10.3，F6-2c fix round 1），寫入沒有確認或被拒時不存。所以沒跨過的參與者（以及 `crossedpending` 那位 Crossed 沒有確認的人）回到上一次存檔的位置，Crossed 已確認的人在 U。

`unload`（M5 H5）不經塌縮流程（原版的機制與 ticket 的類型無關），在 fresh 世界的 overworld，以 testmod 自己的兩種 ticket 操作遠離 spawn 的 chunk（C1＝chunk (-64,64)、C2＝chunk (64,-64)），類別是 `M5CollapseUnloadChain`。殭屍的確定性做法：C 載入、穩定之後，在一個 tick 結束時移除它的 ticket，並讓 server thread 睡 1 秒，之後的 tick 都落後排程（`unloadChunks` 每個 tick 最多移 200 個 holder、不排空卸載佇列）。C 的 holder 出現在 `chunksToUnload` 的那個 tick，以第二種 ticket（半徑 8）再載入，下一個 tick 取回同一個 holder 物件（`sameHolderTakenBack`）。再載入之前裝上 chunk 讀檔的保留（`M5ChunkUnloadHooks.CHUNK_READ_HOLD`：與 C 距離 12～19 的讀檔，結果留到放行才交出；F6-2b 的 `vr-hold`），C 的 refCount 因此大於 0。H5 的兩個 hook 在 `M5ChunkUnloadHooks`（F6-2c Step 0 由 `M5TestHooks` 拆出，沿用 `M5TestHooks.Slot`）。

- `unload-takeback`：等殭屍在 refCount > 0 時執行過（step `staleTaskAwaited`，最多 300 tick；F6-2c Step 0 由 `staleTaskRan` 改名，因為等滿也會完成）→ 放行讀檔 → C1 回到 FULL、refCount 歸零 → 放金塊標記，同一個 tick 移除全部 ticket → holder 不在 `currentChunkHolders`、也不在 `chunksToUnload`，chunk 已卸載（`unloadedAfterTakeBack`）→ 殭屍確實在 refCount > 0 時執行過（`staleTaskSeenWhileHeld`，刻意放在卸載的斷言之後）。修正前後都成立；production mixin 改成無條件 cancel 時失敗在 `unloadedAfterTakeBack`（之後停服也會卡住）。
- `unload-shutdown`：開服時以 raw MCA 讀回 C1 的標記（`takenBackChunkSavedOnDisk`；結果另寫 `marker-on-disk.json`，因為修正前這個 phase 不會寫出 `final.json`）。C2 的讀檔保留到停服開始之後 200 ms，取回之後 2 tick 停服。修正前，停服的 `unloadChunks(() -> true)` 一直重排殭屍、main thread 回不來，由逾時處置判 FAIL（`timeout.json`；jstack 的 server thread 停在 `shutdown → … → unloadChunks` 的卸載佇列排空迴圈，取樣可能落在 `method_60440 → tryUnloadChunk`，也可能落在 `CompletableFuture` 完成之後的 `postFire`：H5 的兩次 `timeout.json` 都是後者）。修正後正常停服，`SERVER_STOPPED` 時斷言 C2 被取回的 holder 的舊工作被丟棄、沒有任何被取回的 holder 的工作照原版重排（`staleUnloadTaskDropped`）。
- receipt 的 witness：`zombie`（holder 的物件 id、各步驟的 tick、refCount、卸載佇列的長度、保留的讀檔數）、`unloadTaskWitness`（`M5ChunkUnloadHooks.UNLOAD_TASK_WITNESS`：被丟棄與照原版重排的工作數，全部與 C 分開記；testmod 的 mixin 把被取回 holder 的重排交給 `M5ChunkUnloadHooks.staleReschedule` 呼叫原本的方法，比對前後 `tryUnloadChunk` 本體的進入次數，一次記下丟棄或照原版重排，被動計數，不在 production 加 test seam）、`shutdownMillis`（`SERVER_STOPPING` 到 `SERVER_STOPPED`）。witness 開服時安裝、停服之後移除；讀檔保留經 `run.install`，請求停服之前移除，已保留的讀檔由它自己的 thread 在停服開始之後放行（請求停服與 crash report 兩條停服路徑都會放行，F6-2c Step 0）。

### 新增 phase

1. 需要新的 crash 窗口時：在 `M5TestHooks.CrashWindow` 選既有的值或新增，在窗口成立的程式點加 hook mixin（呼叫 `M5TestHooks.reachedCrashWindow(window, detail)`，`remap=false`、`require=1`），登記在 `quantumchamber-test.mixins.json`。窗口表以 `CrashWindow` 為 key、一個值一列，所以同一個程式點上條件不同的窗口各要一個值，mixin 在同一個注入點依序以這幾個值呼叫（F6-2c：`commitRollForward` 的回傳點有 `CROSSED_COMMITTED`、`CROSSED_UNCONFIRMED`、`RECOVERY_SEALED`、`RECOVERY_REJECTED` 四個）。
2. 在窗口表（`M5CollapseWindows.table()`）加一列 `row(rows, window, condition, beforeHalt, atRestart, prepared)`。要讓 production 先處理完那一刻的結果再 crash 時，用多一個參數的 `row(rows, window, M5CollapseRun.HaltAt.TICK_END, …)`：crash 點成立時只記下，在那個 tick 結束時（production 的 `END_SERVER_TICK` 之後）才取證、halt，halt receipt 記 `haltAt` 與 `windowServerTick`，halt 的 tick 與窗口成立的 tick 不同時 refused（F6-2c fix round 1：`CROSSED_COMMITTED`、`CROSSED_UNCONFIRMED`；預設 `WINDOW` 是那一刻）。COLLAPSING 中、RESOLVE／PREPARE 期間的窗口（W1～W6x），restart 的窗口預期都寫在這一列，restart 照 `passage.steps(prepared)` 推進到 (b) 終局，不必改 restart 的程式。其他窗口（F6-2c：PASSAGE_READY 期間、終局之後）也加一列，以 `AtRestart` 的 `withStateAtHalt`／`terminatedAtHalt`、`withCrossedAtHalt` 表達開服時的 record，但 restart 的 step 與終局（`End` 的逐人位置）由 chain 自己組（例如 `M5CollapsePassageChains.restart`）：
   - `condition(fixture, detail)`：hook 交來的 detail 是不是那一刻；`beforeHalt(run, fixture, detail, evidence)`：halt 之前的取證（寫進 `halt.json` 的 `atHalt`；丟出時不 halt，phase 照常停止並 FAIL）。
   - `AtRestart`（F6-2c 起是具名的 factory 與 wither，沒有指定的欄位是預設值）：`AtRestart.resolved(Resolved.NOTHING／ALLOCATION／CATALOG／DISCOVERY)`（RESOLVE 在 halt 之前完成到哪一步，由它推出 halt 當下是否已物化，以及 allocation、catalog、discovery 的預期），再接需要的 wither：`notReturned()`（參與者在 crash 前還沒有返還，W1：另斷言走廊 space 已重建、沒有保留式 recovery 的請求）、`withProjection(PENDING／COMPLETE)`（預設沒有 entry）、`withDisk(check)`（窗口自己的磁碟檢查）、`recognizingBeforeJoin()`（JOIN 之前先載入並辨識投影 Controller）、`withStateAtHalt(PASSAGE_READY／SEALED)`（預設 COLLAPSING：`restartedCollapsing`；其他狀態是 `restartedPassageReady`／`restartedSealed`）或 `terminatedAtHalt()`（終局已落盤：`terminatedOnDisk`）、`withCrossedAtHalt(who)`（磁碟上的 Crossed，預設沒有人；狀態不是 COLLAPSING 時斷言 `crossedAtRestart`）。例如 W7 是 `AtRestart.resolved(DISCOVERY).withProjection(COMPLETE).withStateAtHalt(PASSAGE_READY).withCrossedAtHalt(M5CollapseFixture.SELECTOR).withDisk(…)`。`M5CollapseWindows.expectAtRestart` 依這一列斷言。
   - `Prepared(name, mode, cells, afterReady)`：PREPARE 怎麼完成（「準備完成」INFO 的模式與格數，`ANY_CELLS` 為不是 0 格即可）與 READY 時的磁碟檢查；不檢查時為 null。
3. 在 `M5CollapseRecoveryProbe.phases()` 加 entry：phase 名稱 → `PhaseFactory`（在 `onInitialize` 呼叫），通常是 `run -> new <chain 類別>(run).crash(window)` 或 `.restart(window, crashPhase)`。新的 chain family 自己持有自己的狀態，建構時 `new M5CollapseFixture(run)`、`new M5CollapsePassage(run, fixture)`（一個 phase 只能登記一個 `Context`，再登記時開服前就失敗），回傳 `Phase.stop(steps)` 或 `Phase.halt(steps)`，再以具名的方法加上需要的動作：`.beforeStart(…)`（開服前）、`.started(…)`（開服時）、`.everyTick(…)`、`.afterStop(…)`（停服之後）；沒有指定的不做事，每個只能指定一次（F6-2c Step 0：取代位置參數的建構子）。每個 `Step(name, maxTicks, run)` 回 true 才進下一個 step，`maxTicks` 個 tick 內沒完成就 FAIL；整個 phase 另以 3600 tick 為界。
   - crash phase：以 `M5CollapseWindows.arm(run, fixture, window)` 裝上 crash 點，最後一個 step 不會完成（等 halt）。crash 點的健康檢查（`crashPointHealthy`）由 runner 每個 tick 在 `everyTick` 之前自動做（F6-2c），`everyTick` 只放 chain 自己的不變量。
   - restart phase：以 `fixture.readCrashReceipt(crashPhase, window)` 讀回 crash phase 的收據（前一個 phase 是正常停止的 setup 時 `window` 傳 null，改讀 `final.json`；不經收據的 chain 用 `run.readStoppedReceipt(phase, 名稱)`：`<名稱>ReceiptPresent`、`<名稱>ReceiptMatches`、`differentStartupNonce`），開服時 `M5CollapseWindows.expectAtRestart`，之後接 `passage.steps(prepared)`，或只取需要的段（F6-2c）：`passage.resolve()`（RESOLVE 完成、進入艙內）、`passage.ready(prepared, who)`（PASSAGE_READY；之後 `who` 站到門板中線內側）、`passage.cross(who)`（`who` 越過中線、傳送到 U；可以只是參與者的子集、分幾次，`crossedRecorded` 要求 Crossed 恰為到目前為止跨過的人），每個 tick `passage.invariants(...)`；終局以 `passage.expectEnd(End)` 斷言。`End` 以具名的 factory 建立（`End.crossed(fixture)`：全員在 U；`End.inTheSourceWorld(fixture)`：全員在來源世界；`End.degraded(fixture)`：開服時 projection ledger 遺失），再以 `.placed(fixture, who, Place.UNIVERSE／SOURCE)` 逐人改位置；要在 step 執行時建立（參與者在開服時才由收據讀回）。
   - 斷言用 `run.expect(名稱, 條件, 細節)`：失敗訊息以名稱開頭，RED 依名稱判定是否失敗在目標斷言。條件本身會讀磁碟（可能丟出）時用 lambda 版本 `run.expect(名稱, () -> ..., 細節)`，讀取失敗也算這個斷言失敗。
4. 多名參與者（W7 起）：`fixture.buildOrigin(n)` 建立並連線 n 位參與者（移到艙外）。收據（`dormantNewReceipt`、`existingReceiptForTheUniverse`）以清單順序建立 cohort，restart 由收據讀回清單（`receiptIdentity` 比對清單順序）；選門者不依清單順序，由收據的 selection（`CandidateSelection.Selected.selectedBy`）推導（`fixture.selectorId()`、`fixture.selector()`；選門之前還沒有收據，W1 由 chain 自己決定誰去選門）。參與者的子集以 `M5CollapseFixture.Who` 表示（`EVERYONE`、`NOBODY`、`SELECTOR`、`OTHERS`，在 step 執行時才決定）：`passage.ready`／`passage.cross`、`fixture.arm(who)`（W1 與 W7 的 LOW→HIGH 供電與 Buff）、`fixture.joinAll(inUniverse, inside)`（`inUniverse` 的人 JOIN 在 U，`joinedInTheUniverse`）、只讓一部分人 JOIN 的 `fixture.join(who, inUniverse, inside)` 與 `fixture.moveInside(who)`（F6-2c fix round 1）、`AtRestart.withCrossedAtHalt`、`End.placed` 都接受它。要讓選門者不是清單的第一位時，以 `fixture.dormantNewReceiptSelectedByTheSecond()` 建立收據（`w7`）。門口的 local x 依清單順序錯開（3.5、1.5、5.5，最多三人；第一位與拆段之前相同）。`moveInside`／`moveOutside`、`players()`、每個 tick 的位置斷言仍對清單裡的每一位。`w7` 與 `trr` 用兩人，其餘的 chain 都只用一人。
5. 在 probe 內安裝 fault：`run.install(slot, value)` 把既有的故障注入裝進 `M5TestHooks` 的 Slot（例如 `run.install(M5TestHooks.JOURNAL_PUT_FAULT, new M5TestHooks.JournalPutFault(run.server(), sid, SessionState.COLLAPSING, 1))`、`MATERIALIZE_FAULT`、`CHECKED_READ_FAULT`、`PROJECTION_WRITE_FAULT`、`NATIVE_MOVE_FAULT`、`FLUSH_PROBE`（`M5GameTestSupport.FlushFault`）；建構子與 `M5GameTestSupport.install*` 相同，只是沒有 `TestContext`）。crash 點也經它安裝；probe 請求停服之前依安裝的相反順序全部移除（halt 時 JVM 直接結束），停服一開始也再移除一次（F6-2c Step 0）。所以停服期間要保留的 hook 不得經 `run.install`，要由 chain 自己以 `Slot.install` 安裝、在 `SERVER_STOPPED` 之後移除（例如 `unload` 的卸載工作 witness，F6-2c 補上說明）。注意 mixin 的順序（F6-2a 實驗，`f6/f62a/`）：`ProjectionWriteFaultMixin` 與 `ProjectionWriterCrashWindowMixin` 都掛在 `ProjectionWriter.write` 的 RETURN，前者在 `quantumchamber-test.mixins.json` 排在前面；fault 把結果換掉（`setReturnValue`，即取消）的那一次，`PROJECTION_REPAIRED` 的 crash 窗口 hook **不會被呼叫**（實測：沒有 fault 時呼叫 1 次，fault 生效的那一次 0 次，fault 用完之後又是 1 次）。所以「投影寫入失敗之後」的窗口不能用 `PROJECTION_REPAIRED` 觀察，要選之後的程式點（例如 SEALED 的提交）。`MaterializeFaultMixin` 掛在 `Minecraft121DynamicDimensionBackend.materialize` 的 HEAD，那個方法上沒有 crash 窗口的 hook，不受影響。
6. 在 `m5-collapse-probe.ps1` 的 `$chains` 加同名的 phase、預期的結束（`stop`／`halt`）與 halt 的窗口（chain 名稱就是 `-Only` 的值），並更新 `run-all.ps1` 的 `$expectedCollapse`。receipt 的 `phaseTable` 與 `$chains` 不一致時腳本判 FAIL。
7. 以 `-Only <新 chain>` 跑 GREEN，並以暫時的 mutation（production，或 probe 自己的比較對象）證明 restart 會失敗在目標斷言（RED）。每次執行都建立新的 runId 與 root，失敗的 root 與 evidence 都保留。

## Evidence 位置

- `-EvidenceRoot`：預設 `<repo>/.superpowers/verification/`（gitignored）。只接受 repo 內、路徑無 reparse point、且被 gitignore 的目錄，否則在建立任何東西之前就拒絕。
- `run-all.ps1` 每次建立新的 `run-all-<GUID>/`，內含 `console.log`、`gate-<label>-<GUID>/`、`recovery-run-<runId>.json`、`collapse-run-<runId>.json`、`ci-<label>-<GUID>/` 與 `summary.json`。個別腳本直接在 `-EvidenceRoot` 下建立自己的 root。
- 由 testmod 固定、不受 `-EvidenceRoot` 影響的位置（`ProbeEvidenceOwner`、`M4CandidateRecoveryProbe` 只接受這個路徑）：M3 receipt 在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/task-9-final-<nonce>/`、`transfer-final-<nonce>/`，M4 recovery receipt 在同目錄 `recovery-<nonce>/`；M5 collapse probe（`M5CollapseRecoveryProbe`）的 receipt 在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/`。目錄不存在時依程式應由 harness 建立，但還沒在沒有 `.superpowers/` 的 clean clone 上實跑。M3 gate root 另存每個 phase 的 receipt 複本。
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
| M5 collapse probe（M5 F6-1 起；SUMMARY 的 `m5collapse=`） | 9 條 chain、18 phases，各自獨立 JVM：8 個 halt（8 條 crash chain 的 crash phase；JVM exit 77、`halt.json` 的窗口與 chain 表相符）、10 個 stop（8 個 restart 與 `degraded` 的兩個 phase；exit 0、`final.json` PASS）；receipt 的 hash 對上 launcher |
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
| `run-all.ps1` | `ifix4-final-green.ps1`＋`ifix3-build-summary.ps1` §1–§8 | `dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d`／`63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5` | 原本以名稱前綴在 SDD 目錄取最新建立的 root，改為在本次 run root 內要求恰一個；CI 綠燈結果改為 `verdict` 非 PASS 即失敗；summary 去掉該輪固定數量與一次性 RED/GREEN 核對，只保留通用不變式並輸出數字；核對執行前後 HEAD 相同。原 summary 另有幾項核對沒有保留：tracked 變更即失敗（改為 `STATUS` 行與上方採計規則）、workflow 必須等於 HEAD 版本、`M4CandidateDoorGameTests` 每個 `@GameTest` 都呼叫 begin、非 Windows gate 的案例白名單、`Set-StrictMode`。M5 F3c：新增 M5 GameTest 步驟；CI 規則同時讀 M5 XML；summary 加上 M5 XML、三個 run 的 batch 範圍與 M5 oracle。F3c fix round 1：CI 綠燈結果必須沒有 mutation／sourceMutation；summary 加上 suite 結束的核對。M5 F6-1：新增 M5 collapse probe 步驟；summary 加上 collapse 彙整的核對（chain、phase、預期的結束與窗口，獨立 JVM，receipt hash） |

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
