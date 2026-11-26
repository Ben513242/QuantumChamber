# M7 正規回程：實作紀錄

本檔記錄 M7（正規回程）的範圍裁示、Step0 與之後各 task 的實際狀態。binding spec 與 plan 已經獨立審查清零採用，T1 已以同 SHA 五 job CI 全綠結案；使用者已授權 T2 起依審查清零連續推進，T2 已以同 SHA 五 required CI 全綠結案，T3曾獲明確授權恢復並通過N1，但完整回歸兩JUnit失敗；2026-11-16 起由 Claude 接手續作 fix2，T3 已於 2026-11-17 以同 SHA 五 required CI 全綠結案，T4 已於 2026-11-18 以同 SHA 十個 required CI 全綠結案（見「最新：T4 CLOSED（2026-11-18）」節）；依 2026-11-18 遊玩條件組合裁示的 spec v1.6 與 plan 修訂已採用（見「最新：spec v1.6 修訂（2026-11-18）」節），T4A 已於 2026-11-18 結案（見「最新：T4A CLOSED（2026-11-18）」節），T4B 已於 2026-11-19 結案（見「最新：T4B CLOSED（2026-11-19）」節）；同日依使用者指示交給 Codex 接手，T5-0 未結案（見「最新：交給 Codex 接手（2026-11-19）」節）；2026-11-24 由 Claude 接回，T5-0 已以 `98036e8` 的 CI 45342810380（Windows 各 job 全 success）結案（見「最新：T5-0 結案（2026-11-24）」節）；T5 分 5 段，T5-1、T5-2 已依段落結案，T5 整體未完成（見「最新：T5-1、T5-2 結案（2026-11-24）」節）；T5-3 於 2026-11-25 依段落結案，同日依使用者裁示把 M7 Main 的 GameTest 拆成兩段（見「最新：T5-3 結案（2026-11-25）」節）；T5-4 的 Step 0 已完成，本體曾暫停等使用者裁示（見「最新：T5-4 進行中，暫停等使用者裁示（2026-11-25）」節），2026-11-26 恢復後本體已實作、本機驗證通過，但兩席審查未過，交給新對話做 fix round（見「最新：T5-4 本體實作完成、兩席審查未過（2026-11-26）」節）；同日新對話完成 fix1 與 CI 卡住自動快照並已 push，兩席複審 C／I 清零，T5-4 已以 `f8ee0bf` 的 CI 45767504067（Windows 各 job 全 success）依段落結案，T5 整體未完成（見「最新：T5-4 結案（2026-11-26）」節）。本檔的範圍裁示（含本輪追加與釐清）是 M7 範圍唯一的使用者決策來源，規格據此定義技術契約；流程與授權另見「流程指示」節與 AGENTS。

## 最新：T5-4 結案（2026-11-26）

狀態：T5-4 fix1 與 CI 卡住自動快照已 commit 並正常 push；fix1 的兩席複審 C／I 清零，快照的一席審查 Approved。同 SHA CI run `45767504067`（head `f8ee0bf`，push，attempt 1，created 06:20:24Z）**completed／success**（updated 06:52:35Z），Windows 各 job 全 success，T5-4 依段落結案。T5 整體未完成：T5-5（完整 run-all 與同 SHA CI）結案前不勾 Task 5 的核取框。

下文 S 指 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。本節的事實讀自 S 的 `STATUS.md`（2026-11-26 11:3x～15:2x 各段）與下表各檔。設計判定、Minor 處置與後續分派寫在 plan 檔尾「T5-4 的追加」小節的「2026-11-26：T5-4 結案（controller）」段。

| 檔（S） | 內容 | SHA-256 |
|---|---|---|
| `t5-4-fix1-report.md` | fix1 implementer 報告 | `2FC017C7D9FA1A16BEAD06627AC7AFC21345DA1A8FC437E3ECB45FDA794926F7` |
| `t5-4-review-spec-fix1.md` | fix1 規格複審 | `56FA552E1EB4A1B1F87C902AA366855D81A12B892B266242E26BE2754DF2BF00` |
| `t5-4-review-quality-fix1.md` | fix1 品質複審 | `DB2261AD11C7DFAA137ABB626FD6C40312CB893919FC5DA4746CE07A4EAD4FE1` |
| `t5-4-review-spec.md` | 本體規格審查（原審查） | `9E5C9425BA6F24B383F0DDBF2019B59146C169340ED021D83315BC7960BE8B0E` |
| `t5-4-review-quality.md` | 本體品質審查（原審查） | `E2832897506B3AC26241660EE1D9FD1AA43453EDECA5080A6610BC79D7F6C0FA` |
| `t5-4-report.md` | 本體 implementer 報告（三次交回） | `75A8ACDC1A2756B18E010B9DD1430A60C011AC57570D4206E447B3ED1E3389DF` |
| `ci-idle-jstack-report.md` | CI 卡住自動快照 implementer 報告 | `A41CBC0A565DA0BD9B339C365C7D48F8A9E5EB996EB5AD317E0B4C47AFB30425` |
| `ci-idle-jstack-review.md` | CI 卡住自動快照一席審查 | `FF9F124E80D91D5A4366338048D2EF1D1123D36D036E4E9F5F9CF63F6C351D3E` |
| `t5-4-hang-repro-report.md` | CI 45569149943 本機重現調查 | `7C67574E68358184904BBBF6D1ABA678E5386237E09564B8E9F45230CF64DD85` |
| `t5-4-ci-fixture-rerun.log` | ci-rule-check M7 fixture 正例重跑 | `4F804B65172B46525D6CEBAF623A152EAFA3A1CC0F429C67A01A289835DF00C9` |
| `ci-45749878698-22471c9/terminal-run.json` | CI 45749878698 終局 | `53FA5376E1672F31596F65E18AB9D529EFD8D2C7C577275F634DCA49D96E67AC` |
| `ci-45749878698-22471c9/windows-native-failed.log` | CI 45749878698 失敗 job 的 log | `9A17ACC5A768B3017AA882C83B8C1C8813BC05714EBC81131CA93BA2FE492845` |
| `task-5-4-fix1-brief.md` | fix1 派工簡報 | `37703AFE4A27BF99F44298771F61FB3CF7F28E48CBD9FBA6E9428EACE069B56C` |
| `task-ci-idle-jstack-brief.md` | 快照派工簡報 | `39454EB842B2AD9F43B343E3B33696D985C1CA0197BF9BBC39D5303F59B3FEE4` |
| `ci-45767504067-f8ee0bf/terminal-run.json` | CI 45767504067 終局 | `09FBDDD3A17FC92785CDA6E38DE7646A7CA209FDA061BFB6C90DADEB2A16545B` |
| `user-t5-4-death-premise-ruling-20261126.md` | 走廊死亡前提更正的裁示原文 | `EEEAA6353C1DF3046DE5384DCC10C53676FF6CD7081D8B9CFF51CA20057D1DAD` |
| `t5-4-closeout-docs-review.md` | 本筆結案 docs 一席審查 | `13009514116A9E3F02D6ECB046A98D4C8949300221B6E8D74643CD3CD818680C` |

### commit 與 push

- 本輪三筆 commit：
  - `0243fc01e34062fee6276d271ba2690c777ccf96` fix1 RED：只改 `M7HomeSelectGameTests.java` 與 `m7-inventory.json`。新增 6 案，既有 7 案補段與見證；production 與 `25e5038` 相同（C1）。
  - `2ef2df841ea24bfc602ac61bc8ef1ca3eb45ba1f` fix1 GREEN：8 檔（`CorridorPageManager`、`SuperpositionSessionManager`、`SessionRecoveryManager`、`HomeDoorInteraction`、`ChamberCollapsePort`、`M7HomeCorridorGameTests`、`M7HomeSelectGameTests`、`scripts/verification/README.md`），+81／−40。全在 fix1 簡報的允許清單內。
  - `f8ee0bffbcb885d2e0c619d13d0906dae2381d6b` CI 卡住自動快照：只改 `scripts/verification/gates.ps1`（+100／−1）與 README（+10）。
- push：
  - 依使用者原話「push 上去吧」，`238ade6..22471c9`（`46a4683`、`7182641`、`5be0a35`、`25e5038`、`22471c9`）已正常 push，`ls-remote` 讀回 feature `22471c9506ffee9f41a8671b20a08292ed5d2713`。這取代交接檔原本「fix round 結束後再一起 push」的安排（轉述，不是交接檔原文）。使用者之後的原話（11:37）：「已推上去了，但 T5-4 還沒結案：兩份審查的 Important 問題都還沒修。46a4683..22471c9 已 push，fix round 的 commit 正常 push」。
  - 14:2x controller 依持續 push 授權正常 push `22471c9..f8ee0bf`（上列三筆），`ls-remote` 讀回 feature `f8ee0bffbcb885d2e0c619d13d0906dae2381d6b`、main `2255027`；push 前 porcelain 0、無 live Java。沒有 force push。
- M7 沒有合併 main，也沒有 tag。

### fix1（`22471c9..2ef2df8`）

- 範圍：兩席原審查的全部 Important，加上 CI 45749878698 的 log marker（controller 併入 fix1）。
  - 規格 I-1（原玩家實體被替換）：`same_key_new_generation_or_player_never_selects` 後半，以原生 `respawnPlayer(player,true,CHANGED_DIMENSION)`、`networkHandler.player`＝新實體、原生移動回原位替換實體。FULL 後零 selection、只釋自己的票（`REPLACED_PLAYER_SELECTED`）。
  - 規格 I-2／品質 I-1（延後期間的變更、選定後離線）：
    - 新案 `offline_selector_during_select_check_never_selects`、`selector_outside_instance_bbox_never_selects`、`cancelled_select_request_is_dropped_promptly`、`offline_selected_participant_returns_after_join`（設計稿第 12 案）。
    - stale 案補 B 斷電、B 開門、SELECT_CHECK 200 tick 逾時三段；`root_low_after_full_never_selects` 補 A 停用段。
    - 死亡案改成死亡同 tick 開閘，`DEAD_SELECTOR_SELECTED` 有 FULL 見證。
  - 規格 I-3（選定列 checkpoint）：新案 `selected_checkpoint_failure_keeps_selection_and_lock`（B 艙體範圍、`before-clear`；前兩次失敗時保留列、租約、space 與 busy，之後成功才 DORMANT）。走廊範圍在 `retiring_old_home_door…` 案。
  - 品質 I-2（production 活性 bug）：只用 CPM 側修法 (a)。
    - 清 lease 寫出未確認時，記下送出的列（`homeMeasuredClearSent`）。之後 current、flushed、pending 都沒有這個 SID、也不在隔離時，`settleConfirmedHomeMeasuredRelease` 的新分支補做走廊原生釋放；gateway 的 `removed` 分支再釋放 runtime 與 B 來源票。
    - `failHome(Space)` 的 ERROR，對同一 space、同一原因只記一次。
    - 新案 `measured_release_confirmed_and_closed_same_tick_releases_space`。
    - T4 的 `HomeClosure`、`HomeRootFence`、`HomeRootTickets` 沒有改。
  - CI 45749878698：`5be0a35` 的 `HomeDoorInteraction.java:34` debug log 補 `CollapseService.LOG_MARKER`。
  - 另修兩項 production Minor：品質 M-5（選門 checked 之後釋票失敗只記 WARN，仍完成 MEASURED 與 INFO）、M-6（runtime 或 checked 列已不是 SUPERPOSITION／未選定時，`continueHomeSelects` 立即釋票、丟請求）。其餘 Minor 的處置見 plan 檔尾。
- 門檻（Windows；implementer 自報，兩席複審重算 XML、diff 與還原的 SHA 都相符）：
  - RED（乾淨 `0243fc0`）：Select 段 root `t5-4-fix1-red-select-11f225b0`（XML SHA-256 `CAB650F63F64267E704FA0102A7DA6C1D2ECB6B28F7B82403D5D686A006E42C1`），19 案中恰 3 案具名失敗：`MEASURED_RELEASE_STUCK_AFTER_CLOSE`（errors=19）、`SELECT_RELEASE_FAILURE_ABORTED_COMMIT`、`CANCELLED_SELECT_REQUEST_KEPT`。其餘 16 案 PASS，符合「覆蓋缺口不是 bug」的預期。
  - log marker 的 JUnit：RED `t5-4-fix1-red-logmarker-7cf11ea0`（tests=2、failures=1，訊息指 `HomeDoorInteraction.java:34`）→ GREEN `t5-4-fix1-green-logmarker-5535a7c9`（failures=0）。
  - mutation 14 項（拋棄式 clone `.superpowers/t54x`、pin `2ef2df8`，每項逐位元還原 exact）：
    - 12 項 KILLED，各由具名 marker 殺：i2-revert、uuid-resolve、bbox、b-power、b-seal、timeout、root-disabled、offline-join、checkpoint-skip、m5-revert、m6-revert、no-release。i2-revert 的殺手訊息是 `errors=1`（RED 是 19），證實 ERROR 去重。
    - 2 項等價存活：uuid-only、alive（理由見 plan 檔尾 M-1(a)）。
    - 本體既有 11 項中，只有 no-release 的目標程式碼被改到，已重跑；其餘 10 項沿用 `25e5038` 的結果。
  - focused 兩輪（乾淨 `2ef2df8`；`t5-4-fix1-focus-1-71f7647f`、`t5-4-fix1-focus-2-34161943`）：JUnit 五類（12／52／1／7／2 案）PASS；Main（JUnit 59＋GameTest 36）、Corridor（37）、Select（25）三段都 `M7_VERIFIED PASS`，三段 SelfTest 都 PASS。
  - 完整 JUnit（controller 追加；`t5-4-fix1-junit-all-77d91d75`）：1208／0／0，skip 2，85 秒。
  - default 子集（`t5-4-fix1-default-subset-eef713aa`）：27 案（default 23、normal 3、SOURCE fast path 1），都讀本次寫出的 XML。
  - M6GameTest（`t5-4-fix1-m6gametest-b4142cf2`）：35/35。
  - 本機秒數見下方 U1。
- 兩席複審（新的兩席，唯讀、並行）：
  - 規格 ✅ C0／I0／M1：原 I-1～I-3 都 ADDRESSED。B.1 判定：原生替換忠實代表 V05「玩家換 instance」，**不涉及縮小 V05 驗收**，不需問使用者。新 M-1 要求在結案 docs 記六項判定與偏離，已記入 plan 檔尾：(a) :329 的殺手是 uuid-resolve，uuid-only 與 alive 等價存活；(b) 點門受理回 `CONSUME`；(c) 互動距離核 25 格中任一格；(d) 設計 #9「停用前 stop 已 latch」對未選定列不成立，改驗 registry 停用；(e) checkpoint 範圍分兩案驗；(f) `proof==null` 具名化交 T5-5。
  - 品質 Approved C0／I0／M3：原 I-1、I-2 都 ADDRESSED；M-4 交 T6；M-7 PARTIALLY（第 3 點「選定之後死亡」沒有案例）。新 Minor F-1～F-3 的分派見 plan 檔尾。

### CI 45749878698（head `22471c9`）

- attempt 1，push，completed／failure（updated 03:58:23Z）。原判保留、不 rerun。這個 run 的 production 是審查未過的 GREEN `5be0a35`，只作 U1 與回歸資料，不作 T5-4 結案依據。
- Windows：
  - `m7-validation` success。逐段：Main 430 秒（03:37:53→03:45:03，約 450 秒回報線的 96%）、Corridor 313 秒、Select 263 秒（Select 段第一次上 CI）、規模段 166 秒。
  - `windows-native` failure：「Windows 原生建置與 GameTest」與「Windows M6 JUnit 與獨立 GameTest」兩步都是 JUnit `CollapseLogMarkerGuardTest.everyCollapseLogCallCarriesTheCollapseMarker()` FAILED（`1208 tests completed, 1 failed, 2 skipped`）。`:test` 失敗，所以這兩步的 default 與 M6 GameTest 沒有執行；「確認 Windows M6 精確集合與隔離」因 `build/gametest-m6-results.xml` 不存在而連帶失敗。
  - 原因（controller 讀碼）：`5be0a35` 新增的 `HomeDoorInteraction.java:34` `LOGGER.debug(...)` 沒帶 `LOG_MARKER`。
  - 判定（controller，不是使用者裁示）：這是 T5-4 自身的缺陷，由既有守門 JUnit 抓到，不屬「與本段無關的既有 fixture 非預期失敗」，不停下問使用者。本機 focused 只跑 JUnit 五類，所以漏掉。已併入 fix1：補 marker、具名 RED→GREEN、GREEN pin 加跑一次完整 JUnit。
  - `m7-home-native` 四片、aggregate、`m6-native` 都 success。
- Ubuntu（只照錄一行）：`build` failure（Build 與 Ubuntu M6 步驟），`m7-validation (ubuntu-latest, Ubuntu)` success。Ubuntu 未採計（暫停雙平台驗證）。

### CI 45569149943（head `238ade6`，Step 0）的後續

- 原判 FAIL 保留，沒有 rerun。現象（逐筆見「最新：T5-4 進行中，暫停等使用者裁示（2026-11-25）」節「CI」）：Windows 走廊段的既有 T5-2 案 `home_entry_commits_superposition_with_entry_b` 進入走廊（SUPERPOSITION）之後，全伺服器存檔卡住超過 90 秒，被 idle 守門結束（`M6_PHASE_DEADLINE_EXCEEDED reason=idle`）。沒有執行緒快照，原因不明。
- 使用者選「本機重現調查（建議）」（原文檔 S `user-step0-ci-hang-ruling-20261125.md`）：拋棄式 clone 以 `238ade6` 跑走廊段 3 次，都 PASS（43 案、0 失敗，116～124 秒）；看門程式沒有觸發，沒有取得快照（S `t5-4-hang-repro-report.md`）。
- 之後使用者選「讓 CI 卡住時自動留快照（建議）」；依該選項的說明（原文見 S `user-t5-4-resume-ruling-20261125.md` 第 2 題），這次失敗原判保留、不重跑。快照見下一節。

### CI 卡住自動快照（`2ef2df8..f8ee0bf`）

- 依據：使用者選「讓 CI 卡住時自動留快照（建議）」（S `user-t5-4-resume-ruling-20261125.md` 第 2 題；選項說明原文見該檔，以及本檔「最新：T5-4 本體實作完成、兩席審查未過（2026-11-26）（歷史）」節的使用者裁示第 2 點）。依 B+C：只改驗證腳本與 README，一席審查。
- 內容：`gates.ps1 -Mode M7Runtime` 的 `Invoke-Gradle` 在 idle 判定成立、送 `stop` 之前，對已核身分的 owned JVM 取兩次執行緒快照（相隔約 10 秒；`jstack -l`，其次 `jcmd Thread.print -l`）。快照寫進該次 gate 的 `execution/`，隨既有 artifact 上傳；失敗只記錄，不改判。
- 時限不變：總時限 600 秒、idle 90 秒、grace 60 秒都沒有改。`build.yml`、production、testmod 沒有改。
- 驗證（拋棄式 clone `.superpowers/t54j`，用完已刪除；另在工作樹乾淨 pin 跑一次 targeted）：
  - RED（原腳本＋暫時的卡住案）`ci-idle-jstack-red-7170b8ee05b94570be8633af49dc3e2a`：exit 1、`reason=idle`、0 個快照。
  - GREEN `ci-idle-jstack-green-a3d4f6d5c6bd4132a39a014a2cb9f76b`：exit 1，判定與訊息同 RED；兩份快照與 `m7-jstack.json` COMPLETE，快照裡的 `"Server thread"` 停在卡住案。
  - 工具失敗負控 `ci-idle-jstack-toolfail-f23d8f8738854bb08ad2b5f481bae42b`：判定不變，兩次都記 `START_FAILED`。
  - 正常負控 `ci-idle-jstack-normal-b957bbc823c14d98812562c70537ccbf`、工作樹乾淨 pin `f8ee0bf` 的 targeted `ci-idle-jstack-targeted-148864f8310644ffb8f6c0b3a62e3852`：都是 `M7_VERIFIED PASS segment=Select junit=0 gametest=25`，0 個快照。
  - 回歸：`m6-deadline-check.ps1` PASS（cases=7）、`gradle-task-guard-check.ps1` PASS；函式 harness 6/6；`gates.ps1` 語法解析 0 錯誤。真實 AccessDenied 0。
- 審查：一席 Approved C0／I0／M3。Minor 分派見 plan 檔尾。
- controller 決定（不是使用者裁示）：接受快照裡是 `"Server thread"`＋`"DestroyJavaVM"`，沒有名為 `"main"` 的執行緒（派工簡報寫「含 main thread」）。Fabric 的 main 啟動 server 後即返回，主執行緒在快照裡顯示為 `DestroyJavaVM`；快照要抓的是卡住的 Server thread，已抓到。

### 規格 M-3：返還引擎跳過死亡中玩家是 production 改動

- 位置：`SessionRecoveryManager.recoverHome`（`SRM:518` 的 `|| !player.isAlive()`），由 GREEN `5be0a35` 加入（commit 訊息「死亡中的玩家等重生後再返還」）。死亡中的玩家不移動、不記 Returned，等重生後才返還。這一行也作用於 T5-2 未選定 RETURNING 的共用路徑。
- 理由：這是走廊內死亡的玩家最終回到 exact B 的必要條件。不跳過時，死亡者會在重生前被送到 B 並記 Returned，之後原版把他重生在 spawn，列被移除、人不在 B。原規格審查把這點標為讀碼推論；本體的 b-dead-skip 變異（拿掉這個跳過）被 `DEATH_NOT_RETURNED_TO_B` 具名殺掉，fix1 規格複審讀碼確認殺手路徑仍在。
- 使用者走廊死亡選「照原版，記為已知行為（建議）」，其選項說明寫的是 T5-4 只補測試（原文見 S `user-t5-4-design-ruling-20261125.md` 第 3 題）。原規格審查判定這項 production 改動在裁示意圖內、不需新裁示；controller 已在 11-26 交接回覆中告知使用者（見下方歷史節）。

### 本體期間與本輪的工具、fixture 事件

- ci-rule-check M7 fixture 正例（本體期間）：
  - 第一次 `t5-4-ci-fixture-2aeb830f` 自 2026-11-25 20:01 起卡住約 12.8 小時（CPU 約 1.5 秒、無子程序、沒有輸出）。
  - 使用者原話（2026-11-26 08:52，主 chat，不是選項題；原文檔 S `user-t5-4-fixture-hang-kill-ruling-20261126.md`）：「可以，你結束那兩個程序，繼續下一步」。controller 只結束 PID 30904 與 27632；這次原判「未完成」，不算 PASS 也不算 FAIL，root 保留。
  - controller 決定（不是使用者裁示）不改 SDD 腳本，以 `t5-4-ci-fixture-rerun.ps1`（`-NonInteractive`、stdin 導向空檔、900 秒上限）重跑一次：`t5-4-ci-fixture-rerun-b5e9ca82`，exit 0、PASS、49 秒、輸入不變。卡住原因「子 pwsh 等繼承的 stdin」仍是推論。fix1 沒有改 `build.yml` 與腳本，所以這個結果仍適用（規格複審 M-9 列）。
- b-dead-skip 的更正：controller 先前向使用者說「b-dead-skip 第一輪存活、第二輪才殺」，是誤述。
  - 第一輪 11 項，都因驅動 `t5-4-targeted.ps1` 的 `-Segment` 是 `ValidateSet('Main','Corridor')`，傳 `Select` 在參數綁定就被拒（工具錯誤；沒有建立 root、沒有套用變異、沒有啟動 Java）。
  - 第二輪 11 項第一次真正執行，全部 KILLED。b-dead-skip 只執行過這一次，就被 `DEATH_NOT_RETURNED_TO_B` 殺掉。
- 本體的工具錯誤 3 次，各是不同問題，原 root 與原檔保留：
  1. 段名守門負控腳本的解碼（`t5-4-segset-guard-4e750769`，空 root）。
  2. mutation 驅動的 `-Segment` 缺 Select（`t5-4-body-mutations-toolerror1.*`，即上一點）。
  3. default 子集把屬 default run 的 SOURCE batch `m5_collapse_source_live` 排進 M5 run：伺服器啟動即以「指定 batch 子集不符」失敗、0 案執行，驅動誤報的 `FAIL cases=60` 是 11-24 的舊 XML（`t5-4-default-subset-9edef930/m5`）。SOURCE 之後在正確的 default run 補跑 1/1 PASS（`t5-4-default-subset-9a0464c0`）。
- fix1 的工具錯誤 1 次：mutation 批次驅動的 `-Only` 經 `pwsh -File` 傳成一個逗號字串，13 項全被略過、0 項執行、沒有啟動 Java（原 log 改名保留為 `t5-4-fix1-mutations-run2-toolerror1.log`）。驅動改成逐一拆逗號後重跑（`t5-4-fix1-mutations-run3.log`）。
- CI 快照工作的 runner 收尾崩潰 1 次：GREEN 第一次 `ci-idle-jstack-green-5a73e26cd4b64542946dbb8e8a9a8e76` 的 gate 已結束（判定與快照內容和採計的那次一致），但 SDD runner 收尾的 `Get-LiveJava` 遇到命令列為 null 的 `java.exe`，在 StrictMode 下崩潰，子程序 exit code 沒有保存。root 保留、不採計；修 runner 後以新 nonce 重跑（`ci-idle-jstack-green-a3d4f6d5c6bd4132a39a014a2cb9f76b`）。
- 走廊死亡案的診斷程式（marker `M7_T54_DEATH_DIAG`）已在 GREEN `5be0a35` 移除（原規格審查以 `git grep` 核對）。AGENTS 與本檔舊節提到它的段落是當時的進度。

### 止損計數（只計 Windows）

- 既有冷 B 案、門故障案、與本段無關的既有 fixture：非預期失敗 0（fix1 的 Main／Corridor 兩輪、default 子集 27 案、M6 35 案、完整 JUnit 1208 案）。
- T5 新案：fix1 新增的 6 案各 0；`corridor_death_returns_to_exact_b_with_returned`（從真重生修法起重算）0；`collapsing_row_uses_the_same_return_engine` 維持 1；其餘 0。RED 與 mutation 的失敗是預期的，不計。
- 工具：上一節各次都是不同問題，沒有同一工具問題連續第 3 次。
- 真實 AccessDenied：0。`0243fc0`、`2ef2df8` 兩個 pin 的額度都未用。`f8ee0bf` 只改腳本，依 AGENTS 既有讀法（任何含非 docs 變更的 commit）也算 code pin，額度也未用。
- CI 45767504067：Windows 各 job 全 success，非預期失敗 0，上列計數不變；真實 AccessDenied 0。

### CI 45767504067（head `f8ee0bf`）

- completed／success，push，attempt 1，created 06:20:24Z、updated 06:52:35Z。原件 S `ci-45767504067-f8ee0bf/terminal-run.json` SHA-256 `09fbddd3a17fc92785cda6e38de7646a7ca209fda061bfb6c90dadeb2a16545b`。
- Windows：`windows-native`、`m7-validation`、`m7-home-native` 四列（ordinary、passable、sealed、restart-receipt）與 aggregate、`m6-native` 全 success。這是 fix1 production（`2ef2df8`）與第三段 Select 第一次一起在 CI 全綠。
- Ubuntu（只照錄，Ubuntu 未採計，暫停雙平台驗證）：`build`、`m7-validation` Ubuntu success。

### 走廊死亡的前提更正（使用者裁示）

- 使用者選「維持現行行為（建議）」（2026-11-26 AskUserQuestion；題目、選項說明與其他選項原文見 S `user-t5-4-death-premise-ruling-20261126.md`）。
- 背景（controller，不是使用者裁示）：11-25 第 3 題寫「他在走廊掉的物品會在走廊清理時消失」，是 controller 撰題時的錯誤前提，使用者當時在這個前提下選了「照原版，記為已知行為（建議）」。結案 docs 起草時發現，controller 決定向使用者說明並重新確認。
- 實際行為（不改程式，T8 發行說明照此寫）：走廊掉落物隨返還送回 B 艙內；重生先到 canonical `minecraft:overworld` 的 spawn 附近，再被送回出發的宇宙 B（實測三次都沒有設床，設了床時的落點沒有驗過）；玩家停在死亡畫面不按重生時，艙一直被占用，和離線相同，按重生後才送回 B 並釋放。

### U1

- 本機 fix1 focused 兩輪：Main 211／157 秒、Corridor 117／121 秒、Select 104／99 秒。Select 由本體的 79／76 秒（19 案）增為 25 案的 104／99 秒。
- 品質複審觀察：fix1 的 6 個新案都在 Select 段，Main、Corridor 沒有新增或移動案例。Main 第 1 輪 211 秒找不到 fix1 的成因，fix1 報告推論是本機負載波動（未查證）。
- CI：`22471c9`（CI 45749878698）Main 430、Corridor 313、Select 263、規模 166 秒。
- CI 45767504067（Windows `m7-validation` 的 M7 GameTest 執行 step）：Main 372、Corridor 355、Select 283、規模 153 秒（controller 以 `python -I` 從 terminal-run.json 的 step 起訖時間計算）。都低於約 450 秒回報線；Corridor 355 秒約是回報線的 79%。
- 任一段 Windows CI 步驟超過約 450 秒，先報使用者，不自行放寬。

### 下一步

1. T5-4 已依段落結案（CI 45767504067 Windows 全綠）。本筆結案 docs 自己的 CI 另外讀回，不影響 T5-4 判定。
2. T5-5：開服接手／attach、corridor-recovery 原生鏈、完整 run-all、T5 結案。開頭的 Step 0 處理 plan 檔尾分給 T5-5 Step 0 的項目；T5-5 簡報要逐檔授權 plan 檔尾列的腳本與 mixin。
3. 之後 T6 → T7 → T8。main 合併、tag `m7`、人工代操作、worktree 退役，各自另問使用者。

## 最新：T5-4 本體實作完成、兩席審查未過（2026-11-26）（歷史）

狀態：T5-4 本體的 GREEN 與 CI 第三段已在本機 commit，本機驗證通過；兩席審查都沒過，依使用者原話「好，審查回來沒問題就繼續另外等兩個子代理完成就收尾交接給新對話完整提示詞」，本對話不派 fix round，交給新對話。接手入口 `docs/handoffs/2026-11-26-m7-t5-4-fix-handoff.md`。T5-4 未結案。

下文 S 指 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。本節的事實讀自 S 的 `STATUS.md`（2026-11-25 18:4x 起各段）、`t5-4-report.md`（SHA-256 `75A8ACDC1A2756B18E010B9DD1430A60C011AC57570D4206E447B3ED1E3389DF`）、兩份審查檔與各使用者裁示原文檔。

### 使用者裁示（2026-11-25 18:3x 之後；第 1、2 點是 AskUserQuestion，題目與選項由 controller 撰寫，原文檔 S `user-t5-4-resume-ruling-20261125.md`；第 3、4 點是主 chat 原話）

1. 恢復 T5-4：選「恢復，死亡案改用真重生（建議）」。選項說明原文：「只改測試：死亡案改用真客戶端的重生封包（onClientStatus PERFORM_RESPAWN）觸發重生，再照真客戶端回覆傳送確認，跑一次。過了就寫成正式步驟、拿掉診斷程式，繼續 GREEN、第三段、mutation、focused 與審查；死亡案計數從這次修法起重算，同一案第 3 次再停。沒過就停下問你。」
2. Step 0 CI 卡住：選「讓 CI 卡住時自動留快照（建議）」。選項說明原文：「只改驗證腳本：逾時守門判定卡住、結束之前，對測試 server 取兩次執行緒快照並隨 artifact 上傳；時限不改，走一席審查。這次失敗原判保留、不重跑。對應的 commit 要排在 GREEN 之後一起 push（推 RED 會讓 CI 變紅）。之後若再卡住就有快照可以查根因。」
3. 卡住的 fixture 程序（2026-11-26 08:52，原文檔 S `user-t5-4-fixture-hang-kill-ruling-20261126.md`）：「可以，你結束那兩個程序，繼續下一步」。
4. 收尾交接（2026-11-26 09:29）：「好，審查回來沒問題就繼續另外等兩個子代理完成就收尾交接給新對話完整提示詞」。controller 的讀法（不是使用者逐字裁示）：「兩個子代理」是兩席審查；審查沒過，所以不派 fix round，直接交接。

### commit（都只在本機）

- `46a4683` RED（7 檔）、`7182641` 暫停紀錄 docs、`5be0a35` GREEN（6 檔，+449／−26）、`25e5038` CI 第三段 Select（`build.yml`、`scripts/verification/README.md`、`scripts/verification/m7-inventory.json`）。origin feature 仍是 `238ade6`。
- 沒有 push 的理由（controller 決定）：第 2 點選項說明要求快照腳本 commit 排在 GREEN 之後一起 push；GREEN 又有審查 Important 待修。fix round 與快照腳本 commit 完成後一起正常 push。

### 本機驗證（Windows；implementer 自報，controller 抽核相符）

- RED：`t5-4-red-pin-303080cd` 三案具名失敗。
- 走廊死亡案改用原生 `onClientStatus(PERFORM_RESPAWN)`＋`confirmTeleport()`，targeted 一次 PASS（`t5-4-death-real-respawn-ca0a1669`）；診斷程式已移除。
- 第三段：Windows Main 36／Corridor 37／Select 19，Ubuntu 35／13／6；`M7_CI_SEGMENT_SET` 守門負控 `t5-4-segset-guard-6fa4c05a` 四例都被擋。Authority 類別從 Corridor 移到 Select（implementer 決定，理由是只放 Select 類別時 Ubuntu 該段是 0 案；規格審查判定理由成立）。
- mutation 11 項全部 KILLED（拋棄式 clone、逐位元還原）。第一輪 11 項都因驅動 `-Segment` 缺 Select 在參數綁定被拒（工具錯誤，沒有套用變異），controller 先前向使用者說「b-dead-skip 第一輪存活、第二輪才殺」是誤述，已更正。
- focused 兩輪（`t5-4-focus-1-c9a32609`、`t5-4-focus-2-1ad4d955`）：JUnit 五類（12／52／1／7／2 案）PASS；三段 `M7_VERIFIED PASS`、各段 SelfTest 28 例符合預期。本機秒數 Main 155／149、Corridor 114／101、Select 79／76。
- default 子集：default 23/23、normal 3/3（`t5-4-default-subset-9edef930`）。同 root 的 m5「FAIL cases=60 seconds=23」是驅動錯誤：batch `m5_collapse_source_live` 屬 default run，被排進 M5 run（`gates.ps1:325` 的 `$M5RunClasses`），伺服器啟動即以「指定 batch 子集不符」失敗、0 案執行，60 是 11-24 的舊 XML。SOURCE fast path 在正確的 default run 補跑 1/1 PASS（`t5-4-default-subset-9a0464c0`）。
- M6GameTest 35/35（`t5-4-m6gametest-b66d1fa1`）。
- ci-rule-check M7 fixture 正例：第一次 `t5-4-ci-fixture-2aeb830f` 卡住約 12.8 小時（CPU 約 1.5 秒、無子程序、沒有輸出），依第 3 點由 controller 結束，原判「未完成」。controller 決定（不是使用者裁示）不改 SDD 腳本，以 `t5-4-ci-fixture-rerun.ps1` 直接呼叫 `ci-rule-check.ps1`（`-NonInteractive`、stdin 導向空檔、900 秒上限）重跑一次：`t5-4-ci-fixture-rerun-b5e9ca82`，exit 0、PASS、49 秒、`M7_VERIFIED PASS platform=Windows segment=Main junit=59 gametest=36 HEAD=25e5038…`，輸入 hash 不變。卡住原因「子 pwsh 等繼承的 stdin」仍是推論。

### 兩席審查（都沒過）

- 規格：❌ C0／I3／M9（S `t5-4-review-spec.md`，SHA-256 `9E5C9425BA6F24B383F0DDBF2019B59146C169340ED021D83315BC7960BE8B0E`）。production 與 spec、設計稿一致，三項使用者裁示有落實，第三段逐段正確；不過的原因是驗收覆蓋：I-1「原玩家實體被替換」沒有測試（若判定不可達，等於縮小 V05 驗收，要交使用者裁示）；I-2 延遲期間的多項變更（離線、離開 bbox、取消或 B 斷電／開門、SELECT_CHECK 逾時、A 停用後點門）與設計稿第 12 案沒有測試；I-3 選定列清 lease 前的 checkpoint 沒有會失敗的測試。
- 品質：Changes requested C0／I2／M12（S `t5-4-review-quality.md`，SHA-256 `E2832897506B3AC26241660EE1D9FD1AA43453EDECA5080A6610BC79D7F6C0FA`）：I-1 離線情境沒有測試（與規格 I-2 重疊）；I-2 production 活性 bug：選定列的清租約寫出未確認，且同一 tick 確認後被 HomeClosure 移除時，走廊 space 與 HOME runtime 永久殘留、每 tick 記 ERROR、艙一直 BUSY 到重啟（讀碼已驗證，序列未實跑）。
- 要讓使用者知道的（規格 M-3）：返還引擎「跳過死亡中玩家」是 production 改動，而第 1 點與走廊死亡選項寫的是「只補測試」；審查判定在裁示意圖內、不需新裁示。controller 已在交接回覆中告知使用者。
- 會影響 T8 發行說明的更正：品質 M-8，走廊掉落物其實會被返還引擎送回 B，與 plan、報告與死亡案 Javadoc 寫的「留在走廊或清理時消失」相反；規格 M-7，實際重生在 `minecraft:overworld`，停在死亡畫面不重生時艙會一直被占用。

### 止損計數（只計 Windows）

- 既有冷 B 案、門故障案、無關的既有 fixture：0。
- 走廊死亡案：從真重生修法起重算，0。`collapsing_row_uses_the_same_return_engine`：1（dev1，已修）。其餘新案 0。
- 本體工具錯誤 3 次，各是不同問題（守門負控腳本解碼、mutation 驅動缺 Select、default 子集 batch 排錯）；另有 fixture 正例卡住一次（未完成）。
- 真實 AccessDenied：0；`46a4683`、`5be0a35`、`25e5038` 額度都未用。

### 下一步

見交接檔第 4、5 節：T5-4 fix round（production 門檻）→ 新兩席複審 → CI 卡住自動快照（簡報 S `task-ci-idle-jstack-brief.md`）→ 一起 push 與 CI（U1 逐段）→ T5-4 結案 docs → T5-5。

## 最新：T5-4 進行中，暫停等使用者裁示（2026-11-25）

狀態：T5-4（唯一 HOME 選擇與保留式返 B）的 Step 0 已完成並 push；本體的 RED 已在本機 commit、沒有 push；GREEN 候選留在工作樹、沒有 commit。走廊死亡案的受限診斷沒過，依使用者原話「好，CI 綠了就提交並收尾，等我裁示繼續做 T5-4」暫停，等使用者裁示再繼續。

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。本節的事實讀自 S 的 `STATUS.md`（2026-11-25 12:4x～15:4x 各段）、`task-5-4-brief.md`、`t5-4-design.md`、`t5-4-report.md`（SHA-256 `93f4508cf603f1c08d9748cc4c8632b79902d017c3c5fa8ee7cc9bcd9207c9a0`）、`t5-4-step0-review.md`、兩份使用者裁示原文檔，以及各 `ci-*` 目錄。

### Step 0（`238ade6`，已 push）

- commit：`971bbb8..238ade6`，一筆、11 檔（+399／−83），全在 Step 0 授權清單內。內容是 plan 檔尾分給 T5-4 Step 0 的 production 小修與測試修正：
  - HOME 的返還進度、未選定移除、換頁退休的清租約寫入，都包進該 SID 的 session operation guard（spec §9.1）。
  - `HomeReturnProof` 綁定建立的 tick；移除一律拒絕的 public `terminateHome`；兩處寫死的門 key 改用 `HomeDoorLayout.key`；`prepareHomeRemap` 的容量拒絕比照 legacy 先核對已撤回；容量 pending 的重試加 20→1200 tick 退避，pending 段結束時清除 INFO 去重鍵。
  - 測試：guard 寫入見證與「guard 被占」負控、proof 的偽造／重用／過期／他列反例、容量案的走回原頁與退避、新案 `unchecked_home_arming_cleanup_fault_keeps_lock_until_converged`、失敗收尾延後斷開凍結 cohort（operator 立即斷開、停服清 `WAITING`）。
  - inventory：Windows 79（Main 36＋Corridor 43），Ubuntu 54。
  - 原寫在 plan T5-3 小節、分給 T5-4 Step 0 的腳本與 CI 整理項目（拆段審查 M2～M7、M9、fix1 新 Minor、T5-3 規格 M-6）不在本筆，已改交 T5-5（見 plan 檔尾 T5-4 小節「改派」）。
- 驗證（Windows，implementer 自報、審查重算相符）：
  - RED（在 `971bbb8` 的拋棄式 clone 只加 4 個測試檔）：`HOME_WRITE_OUTSIDE_GUARD`、`HOME_CAPACITY_INFO_NOT_RESET`＋`HOME_CAPACITY_RETRY_NOT_BACKED_OFF`、`PROOF_STALE_TICK_ACCEPTED`。
  - mutation 11 個（guard 三處、guard 前置、proof 三項、退避、pending 段結束、隔離、`homeAbandons.put`），都在拋棄式 clone 執行，各由具名測試殺掉、還原 exact；tracked 檔全程沒有暫改。
  - 乾淨 pin `t5-4-step0-green-m7-d8aa7fea`：Main `junit=59 gametest=36`、Corridor `gametest=43`，兩段 `M7_VERIFIED PASS`，SelfTest 都 PASS；本機 Main 149 秒、Corridor 134 秒。
  - 計數 0，真實 AccessDenied 0。
- 審查（一席，規格＋品質）：Approved，C0／I0／M8（S `t5-4-step0-review.md`，SHA-256 `d4883787c4a640302e5378af58603116ee929165ce5d83381b48f1e1706638bd`）。Minor 的分配見 plan 檔尾「T5-4 的追加（進行中，2026-11-25）」。
- CI：見下方「CI」。

### 本體（進行中，未完成）

- RED `46a4683`（`238ade6..46a4683`，7 檔，本機 commit、沒有 push）：hook 與 `HomeDoorInteraction` 的 no-op 腳手架（C1，production 行為不變）、新 class `M7HomeSelectGameTests` 13 案（暫列 Main 段）、inventory 與 entrypoint。
  - 乾淨 pin（拋棄式 clone）root `t5-4-red-pin-303080cd`，XML SHA-256 `41446092AD02E16D3F0F731264CC99592CC8B03BCB9B7B93572FAEB050F9246D`：`same_key_new_generation_or_player_never_selects`、`home_select_commits_measured_and_returns_cohort_to_b` 以 `HOME_NOT_SELECTED` 失敗，`home_corridor_walls_never_produce_candidates` 以 `HOME_DOOR_LOCATED_AS_CANDIDATE` 失敗。
  - 這筆 commit 的測試是刻意失敗的，所以沒有 push；T5-4 恢復、GREEN commit 之後一起 push。
- GREEN 候選：6 檔留在工作樹、沒有 commit（CPM、SRM、SSM、`HomeDoorInteraction`、`M7HomeCorridorGameTests`、`M7HomeSelectGameTests`），編譯通過。整份 diff 另存 S `t5-4-body-wip-46a4683-after-diag.diff`（SHA-256 `8c12e2f2c92604a8605698397a2978703a593a3b58a7b49f8abd449e181dc9b8`）。
  - 開發輪 dev2：13 案中 12 案通過。這是較早的 WIP 狀態；之後 WIP 又加了 Step 0 審查 M-2、M-4 的修改與死亡案的診斷程式（marker `M7_T54_DEATH_DIAG`，恢復時要移除或改寫成正式步驟），這些改動還沒在 Corridor 段跑過。
  - 走廊死亡案 `corridor_death_returns_to_exact_b_with_returned` 第 3 次非預期失敗（dev1、dev2、dev4；dev3 是刻意的診斷重跑，不計），依止損停下。implementer 在第 3 次之後又多跑了 dev5，違反止損，報告照實列出。
- 尚未做：第三段、mutation、focused 兩輪、default 子集、M6GameTest。

### 走廊死亡案的受限診斷（使用者授權一次，FAIL）

- 只改測試：拿掉前一輪直接改玩家狀態的寫法，重生之後與之後每個 tick 都用既有 `ConnectedGameTestPlayer.confirmTeleport()` 照真客戶端回覆傳送確認，並記錄狀態。跑一次、沒有重跑。
- root `t5-4-death-diag-once-bd83226b`（HEAD `46a4683`＋未提交 WIP），XML SHA-256 `C35934E9E33318668814D8D5E3A0C77A12377B0230EE0324F3C88CE5A78A85F2`：`DEATH_NOT_RETURNED_TO_B`，列停在 RETURNING。
- 記錄：重生後玩家在 `minecraft:overworld`、`isInTeleportationState=false`；返還程式第一次傳送後，玩家的世界換成 B 所在的 U，位置卻沒有變、`isInTeleportationState=true`；之後每個 tick 都有傳送封包也都回覆了，位置一直不動。
- implementer 的判讀（只讀原版 bytecode，尚未以修正後重跑證實）：真客戶端重生走 `ServerPlayNetworkHandler.onClientStatus(PERFORM_RESPAWN)`，它在 `PlayerManager.respawnPlayer` 之後把 handler 的 `player` 改指新的玩家實體；測試直接呼叫 `respawnPlayer`，handler 仍指著已死的舊實體，之後的傳送與確認都作用在舊實體上。若成立，這是測試的重生寫法造成的，不是遊戲程式的缺陷。
- 依使用者所選選項的說明原文「還是卡住就視為遊戲程式問題，停下來再問你修法」，結果 FAIL 即停下；在使用者另有裁示之前，預設把它當成遊戲程式問題看待。implementer 建議的下一步（只改測試、需要另外授權再跑一次）：死亡案改用原生 `onClientStatus(PERFORM_RESPAWN)` 觸發重生，再照真客戶端時序回覆傳送確認。

### 止損計數（只計 Windows）

- 走廊死亡案 `corridor_death_returns_to_exact_b_with_returned`：3 次（dev1、dev2、dev4），依規則停下。dev3 是刻意的診斷重跑，不計；dev5 也 FAIL，是第 3 次之後多跑的一次，違反止損，照實記錄。
- `collapsing_row_uses_the_same_return_engine`：1 次（dev1，選定列被結案移除後 runtime 沒釋放，已在 WIP 修正）。
- 其餘 T5-4 新案與 Step 0 新案：0。
- 受限診斷的那一次 FAIL 是使用者另行授權的一次，不計入止損。
- 既有冷 B 案、門故障案：本體還沒跑 focused，0。
- 真實 AccessDenied：0。
- 恢復之後死亡案的計數要延續 3/3 還是重算，還沒有決定；controller 會在向使用者提問時一併列出。

### 使用者裁示（2026-11-25；第 1、2 點是 AskUserQuestion，題目與選項由 controller 撰寫；第 3 點是主 chat 原話）

1. T5-4 設計三題（原文檔 S `user-t5-4-design-ruling-20261125.md`）：
   - 點門時機：選「新走廊建造中不受理（建議）」。選項說明原文：「只有新一段走廊還在生成時，點 HOME 門沒有反應（也沒有提示，提示文字到 T7 才加），等生成完再點一次就好。一般只有幾十 tick，負載重時可能到約 50 秒。舊一段正在拆除時照常可選（只認新的門）。不改已完成的換頁程式。」
   - 第三段：選「加第三段（建議）」。選項說明原文：「T5-4 的新測試放進新 class，成為第三段（例如 Select），各段各自 600 秒、約 450 秒回報線。估算 Main、Corridor 維持約 340～365 秒，第三段約 170～210 秒。要改清單、CI 加 3 個 step 與段名守門，比照剛才拆兩段的做法，只改腳本與 CI。」
   - 走廊死亡：選「照原版，記為已知行為（建議）」。選項說明原文：「T5-4 只補測試，確認死亡的玩家最後一定回到正確的 B、返還紀錄正確。重生點與「走廊掉落物會消失」寫進發行說明的已知行為；要不要改善之後另案討論。」
2. 走廊死亡案（原文檔 S `user-t5-4-death-diagnosis-ruling-20261125.md`）：選「允許一次受限診斷（建議）」。選項說明原文：「只改測試：重生後讓假玩家照真客戶端回覆傳送確認，並記錄傳送前後的狀態，跑一次。過了就證明是測試限制，照此修測試、繼續 T5-4；還是卡住就視為遊戲程式問題，停下來再問你修法。不改遊戲程式。」
3. 收尾（主 chat，非選項題）：使用者原話「好，CI 綠了就提交並收尾，等我裁示繼續做 T5-4」。這句話在診斷結果出來之前說的；使用者還沒有針對診斷 FAIL 裁示。controller 的讀法（不是使用者逐字裁示）：只 push 已驗證的 Step 0 `238ade6`，刻意失敗的 RED 不 push，避免 CI 變紅；診斷跑完即停，不論結果都不續做；本筆收尾 docs 在本機 commit 於 RED 之上、不 push，等 T5-4 恢復後隨 GREEN 一起 push。

### CI

- `971bbb8`（T5-3 結案 docs）的 CI 45547816262 attempt 1 success（2026-11-25 05:10:39Z），10 個 job 全 success；原件 S `ci-45547816262-971bbb8/terminal-run.json`（SHA-256 `ad760792b8407d6a82d903fa097247d4a4a439346cd1cfcc975b35ad9095d265`）。Windows 逐段：Main 337 秒、Corridor 351 秒、規模 152 秒；程式與 `eb10bc8`（258／215 秒）相同，同一份程式的 CI 耗時波動很大。
- `238ade6`（Step 0）的 CI 45569149943 attempt 1 **failure**（08:21:12Z），原判保留、沒有 rerun；原件 S `ci-45569149943-238ade6/`（`terminal-run.json` SHA-256 `5ef7d1be146944061d857325559f88bcd67e443603a7e2876dd2feb84536c298`，Windows 走廊段 artifact 在 `art-m7-corridor-Windows/`）。
  - Windows `m7-validation`：Main 段 success（07:36:37→07:42:55，378 秒）、規模段 success；**走廊段 FAIL**，「執行 M7 走廊段 GameTest」與「確認 M7 走廊段精確集合與隔離」兩個 step failure。`failure.json`：`M6_PHASE_DEADLINE_EXCEEDED reason=idle`（idle 90 秒、總時限 600 秒都沒有改）。
  - 現象：既有 T5-2 案 `home_entry_commits_superposition_with_entry_b` 進到 SUPERPOSITION（`M7_GT_WAIT used=59`，07:45:38）之後，同一秒開始全伺服器存檔，log 停在 `Saving chunks for level …/quantumchamber:universe/7dbeac25-…/overworld`（該 B 宇宙在 07:45:32 才載入）；之後 90 秒以上沒有任何輸出，被 idle 守門結束。走廊段沒有產生本次的 XML（`result-build` 裡的 36 案 XML 是 Main 段留下的舊檔）。沒有執行緒快照，卡住的原因未查明。
  - Step 0 把返還收尾的 HOME checkpoint 包進 session operation guard；guard 是不可重入、被占就丟例外，不會等待。這次卡在原版的區塊存檔內，是否與 Step 0 有關，尚未證實。本機乾淨 pin 的走廊段跑過一次（134 秒，PASS）。
  - 其他 Windows job 都 success。Ubuntu `build` job 的「非 Windows M5 GameTest」「Ubuntu M6 JUnit 與獨立 GameTest」「確認 Ubuntu M6 精確集合與隔離」failure，只照錄、不採計、不調查（Windows 優先）。
  - 依止損（與本段無關的既有 fixture 非預期失敗即停下回報），停下問使用者。
- Ubuntu 兩個 job 只照錄、不採計。

### 下一步

1. 等使用者裁示是否繼續 T5-4，以及走廊死亡案的修法（建議見上方診斷節）。
2. 繼續之後：GREEN commit → 第三段（獨立 commit，B+C）→ mutation → focused 兩輪（JUnit 類別＋M7 三段＋各段 SelfTest）→ default 子集（M4 候選門、SOURCE fast path、M2 corridor）→ M6GameTest → 兩席審查 → push 與 CI。
3. 之後 T5-5。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

## 最新：T5-3 結案（2026-11-25）

狀態：T5-3（HOME 換頁與容量 pending）已依段落結案；T5 整體未完成，完整回歸在 T5-5。同日依使用者裁示把 M7 Main 的 GameTest 拆成 Main 與 Corridor 兩段。

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。本節的事實讀自 S 的 `STATUS.md`（2026-11-24 22:3x～2026-11-25 各段）、`task-5-3-brief.md`、`task-5-3-fix1-brief.md`、`task-5-3-fix2-brief.md`、`t5-3-report.md`（最終 SHA-256 `1b70d48cef2b756226cb65d9f65630bfe9e3a5d6690974783b3f62ff08f4303a`）、`t5-3-review-spec.md`、`t5-3-review-quality.md`、`t5-3-review-quality-fix2.md`、`task-m7-ci-split-brief.md`、`m7-ci-split-report.md`、`m7-ci-split-review.md`、`m7-ci-split-review-fix1.md`、三份使用者裁示原文檔，以及各 `ci-*` 目錄。

- **段落結案判定**（controller 的判定，不是使用者裁示）：照 T5-1、T5-2 的標準，完整 run-all 以外的 production 門檻都做到，再加上 Windows 各 job success 的 CI。
  - T5-3 的最終 code `62dacdd` 沒有自己的 CI：它和拆段 `c0dbdd7` 一起 push。CI 跑在後繼的 `c0dbdd7`、`eb10bc8` 上，兩者的 `src/main` 與 `62dacdd` 相同（`git diff --stat 62dacdd..eb10bc8 -- src/main` 為空），以這兩次 CI 採計（controller 的判定）。
  - 拆段沒有改 `src/main`。改了驗證腳本、CI 設定、清單與 README，加上 testmod 的 `M4LegacyGameTestFilterMixin.java`（在 JVM 內依段篩選類別）與 `build.gradle` 的 `gameTestM7` 區塊（傳段名屬性）；後兩檔是 controller 在派工中授權的。拆段依 B+C（一席審查、定向驗證、CI）。
- 設計決定、審查 Minor 與交接事項的分配，見 plan 檔尾「T5-3 的追加（2026-11-25）」小節。

### commit

- T5-3：`8d868e7..62dacdd`，6 筆。
  - Step 0 `23480d7`：T5-2 品質審查分給 T5-3 Step 0 的 M-1、M-2、M-8、M-9、M-10 與可選第 6 項（production 小修，加 4 案 GameTest）。
  - RED `7a8ae04`：只有測試、testmod fixture 與 inventory。
  - GREEN `19f3bd9`：HOME 換頁分支、容量 pending、退休。
  - fix1 `852f27c`：只改 `M7HomeCorridorGameTests.java`（換頁案先生成走廊 chunk）。
  - fix2 RED `43e82c1`、GREEN `62dacdd`：品質 I-1 與 M-7。
- 拆段：`62dacdd..eb10bc8`，2 筆。`c0dbdd7` 拆段本體（8 檔），`eb10bc8` 拆段 fix1（workflow 與 README）。
- production 改動的檔：`CorridorPageManager.java`、`CorridorRepositionService.java`、`SuperpositionSessionManager.java`、`SessionRecoveryManager.java`、`MeasuredWorldSaveCheckpoint.java`。全部在授權清單內。

### 內容

- `CorridorRepositionService`：以 `allFlushedSessions()` 修剪追蹤並以 typed 分派處理 HOME 列；舊列的迴圈不變。HOME 換頁遇到容量拒絕時，`targetPages` 清空、同一段 pending 只記一則 INFO、下一個 tick 重試，不送 RETURNING。
- `CorridorPageManager`：
  - 7 個 legacy remap 方法開頭各加一行 HOME 分派，`retire` 加一行「已選定的 HOME 保留所有 mapping」守門，其餘 HOME 版本以加法新增。
  - `prepareHomeRemap` 以 checked roll-forward 寫新舊租約聯集。寫入前就被拒時撤回額外 slot 等資源，原因含 `CapacityExceeded` 就原樣丟出留 pending，其他拒絕回 B；寫出未確認或確認後才失敗時，保留額外 slot、補取票，再回 B。
  - 換頁退休：舊租約清空後以 checked roll-forward 移除，確認之後才釋放票、slot 與保護範圍；寫出未確認時保留舊 slot、整組回 B（fix2）；之後確認時由 `settleConfirmedHomeRetirement` 補做原生釋放。
  - 新實例以同一個 HOME mode 建造，換頁之後沒有第二扇 HOME 門。
- `SuperpositionSessionManager`：reposition 的 HOME predicate（本 server 的 runtime、同一座艙與 SID、checked 列為 SUPERPOSITION 且未選定）；HOME 的四個 tick 段各自經 `isolateHome` 執行，意外例外只記去重 WARN（Step 0 M-10）。
- `SessionRecoveryManager`：roll-forward 待確認時記 journal 退避，同一個 tick 不重寫（Step 0 M-8、fix2 M-7）。
- `MeasuredWorldSaveCheckpoint.entryLive`：`tickHomeRelease` 在 `ready` 之前先核 exact B，B 暫時不在時托管等待，不再每 tick 記 ERROR（Step 0 M-2）。
- `M7CapacityFixture`：容忍一列活的 HOME 列並計入兩列 HOME 預留；`admitBeside`／`inflateInMemory` 觸發容量拒絕的做法見 plan 檔尾。

### 驗證（Windows）

- Step 0：
  - RED：M-1 `HOME_START_LEAKED_AFTER_CHECKED_ARMING`、M-2 `HOME_RELEASE_ERROR_FLOOD count=30`、M-8 `HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK hits=2`。M-10 的 RED 會讓 GameTest server 停服，以讀碼論證代替（見 plan 檔尾）。
  - mutation S0-M1、S0-M2、S0-M8 各由具名測試殺掉，逐位元還原。
  - 規格審查另以 Step 0 RED root 的 sources jar 比對 T5-2 build，四個 production 檔逐位元相同，確認 RED 跑在未修的程式上。RED root 的 `launch.json` 把 `MeasuredWorldSaveCheckpoint.java`、`SessionRecoveryManager.java` 列為 dirty，是 CRLF 換行造成的假象，不是內容不同（規格 M-6）。
- 本體：
  - RED（乾淨 `7a8ae04`，root `t5-3-red-pin-1a548735`，XML SHA-256 `75FA9E957CD302CA9EECEF6998C35A9F15CE7433B908BBEAF0FC77ADFC107D7B`）：`home_remap_builds_no_second_home_door`、`formal_tick_capacity_pending_preserves_home_mapping` 都以 `HOME_REMAP_NOT_ATTEMPTED` 失敗。
  - GREEN（乾淨 `19f3bd9`，root `t5-3-green-pin-7ad07819`，XML SHA-256 `A986963293F328F2706AECE69A3F632951B8D98BC3A5FE781E7A690A79C74BD5`）：HOME 走廊 18 案＋`m7_home_capacity_windows`，19/19。
  - mutation M-A（容量拒絕改送 RETURNING）以 `CAPACITY_SENT_RETURNING` 殺、M-B（不撤回多保留的 slot）以 `REMAP_RESERVE_NOT_WITHDRAWN` 殺，逐位元還原。
- focused 第 1 輪（乾淨 `19f3bd9`，root `t5-3-focus-1-ef7f2d18`）FAIL，原判保留：M7 Main 75 案 4 failure（XML SHA-256 `A16A6859068F7DCAA7216DBEB0FD240605F45B1ADEF22EDD0A025F2DD83B08B3`）。兩個換頁案的聯集已 checked，但新租約周邊的走廊 chunk 在負載下生成太慢，600 tick 內沒完成；另外兩案被殘留的 RETURNING 列連坐（`PRIOR_TEST_RESIDUE`）。implementer 依止損停下，使用者裁示 fix1（見下方「使用者裁示」第 1 點）。
- fix1（乾淨 `852f27c`）：
  - targeted `t5-3-fix1-targeted-d9d10e29` 19/19（XML SHA-256 `71285400D6D877ED7BDC5377D20A83EAE67630ADC5B356E088B358D788AC08B7`）。每筆預生成 570 chunk、耗時 42～159 ms。
  - focused 第 1 輪 `t5-3-focus-1-456fe6c4`：JUnit 四類、M7 Main（junit 59、gametest 75，XML SHA-256 `BED1275FDE478480AC00618BBFF80444FF22C27062E40790313412AFEDA5D5FC`，250 秒）、SelfTest 全 PASS。
  - focused 第 2 輪 `t5-3-focus-2-deedbe0c` FAIL，原判保留：M7 Main 75 案 3 failure（XML SHA-256 `3466D49B9DEF3404867A3353CFEE9DED9F7DA54BDD3CDB99DAA3F5B671FE0E66`）。起點是 `permanent_sealed_origin_dismantles_after_disable_and_power_off` 的 fixture 寫出遇到真實 AccessDenied（真實 journal 檔的 atomic move；latest.log 的 `TESTMOD_FIXTURE_WRITE_FAILED`），`passable_door_closes_out_as_ordinary_open`、`last_checkpoint_fails_after_native_moves` 被它連坐。同一份 log 另有 3 則路徑含 `m7-t4p2-native-fault` 的刻意注入，不算真實。使用者裁示重跑一次（見下方第 2 點）。
  - 重跑 `t5-3-focus-2-123baf63`：JUnit 四類 PASS；M7 Main `M7_VERIFIED PASS platform=Windows junit=59 gametest=75`（XML SHA-256 `79ED0B10B4554D8ECF5E492E209E8EFE708E971A13733AA8DAB128B2F3F27E19`，236 秒），真實 AccessDenied 0；SelfTest PASS。
  - default 子集 `t5-3-default-subset-8ee020bd`：17 個 M2 換頁 batch 17/17（XML SHA-256 `7560120897E825AB452FC8D8C9B89B2ED9D625D260610AF28A53EA80F75FD590`）。驅動誤讀 `prior-build/` 舊 XML 寫出的原 `verified.json`（FAIL）保留，改讀本次 `result-build` 另寫 `verified-result-build.json`（PASS）；這是工具錯誤，不是測試失敗。
- fix2：
  - RED（乾淨 `43e82c1`，root `t5-3-fix2-red-50596049`，XML SHA-256 `6B8018F26ABAFA86333C8CEDC1E834281E2CA20B1DF67D91D3561978FDE8C948`）：兩個新案依預期失敗。退休案同時重現 `HOME_ROLL_FORWARD_REWRITTEN_SAME_TICK hits=2` 與原因誤標；聯集案 hits=2。
  - GREEN（乾淨 `62dacdd`，root `t5-3-fix2-green-adecdf64`，XML SHA-256 `74F34A0298001D08D1B360DDCB85E2855A2555EE1B2BAEAD37A3521A385CB3BC`）：新 2 案＋HOME 走廊 18 案＋`m7_home_capacity_windows`，21/21。
  - mutation f2-M1（移除 settle 呼叫）以 `HOME_RETIRED_SLOT_NOT_SETTLED` 殺、f2-M2（移除聯集寫出未確認時的補票）以 `HOME_UNION_PENDING_TICKETS_MISSING` 殺、f2-M3（移除共用退避）兩案都以 hits=2 殺，逐位元還原。
  - focused 兩輪：`t5-3-focus-1-0a4a324d`（M7 Main gametest 77，XML SHA-256 `48B950BAFB1A716484CFD14CCBD3921EBB6564D744923C88BF741991147FB24A`，239 秒）、`t5-3-focus-2-22307dab`（XML SHA-256 `02246F46DAA09567B08FF5064BE4B8A9C6EE1E690D4AE14624D14813966709F0`，256 秒），JUnit 四類與 SelfTest 都 PASS，兩輪真實 AccessDenied 都是 0。
  - default 子集沒有重跑：fix2 沒有改到 legacy 路徑（新增那一行在 legacy 迴圈之後，沒有 HOME 列時不執行）。
- 拆段（`c0dbdd7`）：
  - 兩段在乾淨 pin 上都 PASS（`m7split-run2-297b6983`；`gates.ps1 -Mode M7GameTest` 入口 `m7split-gate-add78c69`），本機 Gradle 時間 Main 157～175 秒、Corridor 112～152 秒。兩段 GameTest 互斥、合計 77，JUnit 59 不變，Scale 段不變。
  - SelfTest 兩段各 28 例；VerifyOnly 合成負控 18 例（`m7split-neg-75927831`）；真的啟動 JVM 的負控 4 例（`m7split-jvmneg-5b4d59e3`：未知段名、沒給段名、類別重疊、類別遺漏，都在開跑前拒絕）。
  - 工具錯誤 2 次（driver 參數與陣列攤平，都在呼叫被測程式之前），root `m7split-run1-bf8dd5e0`、`m7split-runall-a79e8449` 保留。
  - JVM 負控曾暫改 tracked 的 inventory 約 1 分鐘，之後以原 bytes 寫回並核對 SHA 與 git status；審查判定這次可接受，之後改在拋棄式副本執行。
- 拆段 fix1（`eb10bc8`）：守門正例 PASS，多一段、少一段、只改順序三種都以 `M7_CI_SEGMENT_SET` 失敗（`m7split-fix1-e1e50483`）；ci-rule-check 的 M7 fixture 逐字執行到守門後 PASS。
- legacy 方法本體（C13）：規格審查逐處核對，只有加法分派與等價改寫，m4-recovery／m5-collapse probe 留 T5-5。

### 審查

- T5-3 規格一席：✅，C0／I0／M6。
- T5-3 品質一席：Changes requested，C0／I1／M8。I-1 是換頁退休的寫出未確認時，程式說明寫原地等待，實際上同一個 tick 就以「B 身分或完整 cohort 效果失效」的錯誤原因整組回 B；兩條收斂路徑（settle、聯集寫出未確認時補票）都沒有測試。fix2 處理 I-1 與 M-7，原品質席窄複審 Approved，C0／I0／M2。
- 拆段一席：Changes requested，C0／I1／M9。I1 是 README 說加第三段只改清單，但 CI 的段名寫死，照做會讓新段一案都不跑而 CI 仍全綠。拆段 fix1 處理 I1 與 M1（Main 兩個 CI step 加時限），原席窄複審 Approved，C0／I0／M1（S `m7-ci-split-review-fix1.md`，SHA-256 `ac1ba7d07fb2f5655ff1edddc537ee9008b130d87941bd63c9813611431f2fe7`）。

### 使用者裁示（2026-11-25，AskUserQuestion，題目與選項由 controller 撰寫）

1. 換頁案的修法：使用者選「測試先生成走廊 chunk（建議）」。選項說明原文：「只改測試。三個換頁案在租約寫入確認後，先把新租約外擴 2 圈的走廊 chunk 同步生成好，等待時間維持 600 tick。做法比照你先前對冷 B 案選的「測試先生成 B 地形」。修完照原門檻重跑 focused 兩輪、default 子集並實量耗時。止損計數從修法起重算，同一案第 3 次非預期失敗再停。」原文檔：S `user-t5-3-fix1-ruling-20261125.md`（題目、三個選項與 controller 註記）；派工見 S `task-5-3-fix1-brief.md`。
2. AccessDenied 加連坐（兩題，原文檔 S `user-t5-3-accessdenied-ruling-20261125.md`）：
   - 第 1 題選「換新 nonce 重跑一次（建議）」。選項說明原文：「保留原 root 與原判，對同一版本 852f27c 重跑 focused 第 2 輪一次，接著跑 SelfTest 與 default 子集。這次重跑用掉這個版本的 AccessDenied 重跑額度；再失敗（任何原因）就停下回報。」
   - 第 2 題選「併入，追得到起點才算（建議）」，成為 AGENTS「M7 Windows atomic-move AccessDenied 單次重跑規則」的 2026-11-25 擴充。選項說明原文：「M7 之後的完整回歸與 focused：唯一的起點是真實 AccessDenied，而且其餘失敗都能從 log 追到是被它連帶時，視同只有 AccessDenied，保留原 root、換新 nonce 重跑一次，不必再問。每個版本仍只一次，同一版本第 2 次就停報；追不到連帶關係就停下問你。」
3. CI 時限：使用者選「拆成兩段（建議）」。選項說明原文：「把 M7 Main 的 GameTest 依測試類別拆成兩段（例如 HOME 走廊／換頁一段、其餘一段），各自一次 Gradle 呼叫、各自 600 秒上限，約 450 秒回報線改成逐段計算。比照 11-18 把規模測試拆成獨立一段的做法。只改驗證腳本、CI 設定與清單，不改遊戲程式；走一席審查＋定向驗證＋CI，在 fix2 push 之前完成。」原文檔：S `user-m7-ci-split-ruling-20261125.md`。實際分配與建議範例不同（見 plan 檔尾）。

controller 的決定（不是使用者裁示）：品質 I-1 採「寫出未確認即回 B」；品質 M-3 維持與 legacy 一致；品質 M-7 併入 fix2；容量案以記憶體加大壓力列觸發；接受 implementer 提出、與使用者選項說明範例不同的拆段分配；拆段審查 M1 採建議加 Main step 時限。理由見 plan 檔尾。

題目原文：都在上列原文檔。第 1 點的題目是：「T5-3（HOME 走廊換頁）在 focused 第 1 輪的 M7 Main 有 4 案失敗，已依止損停下。查到的原因：換頁要寫的新舊租約已寫入並確認，但新租約周圍的走廊 chunk 在負載下生成很慢。單獨跑時 30～60 tick 就好，整批跑時約 989 tick，所以 600 tick 內換頁沒完成，另外 2 案被它留下的殘留列連帶失敗。換頁和容量判讀的程式本身沒有錯，等得夠久就會完成。要怎麼修？」題目中「整批跑時約 989 tick」是 controller 當時的轉述，不精確：989 tick 量自開發輪 dev6 的單案實驗，整批 focused 沒有量到完成時間（見 plan 檔尾）。

### 止損計數（只計 Windows）

- fix1 之前：
  - `legacy_candidate_append_cannot_spend_home_remap_reserve` 3 次（開發輪 dev1、dev2，以及 focus-1 的連坐），依規則停下；dev3～dev5 是刻意的診斷重跑，不計入。
  - 另外兩個換頁案各 1 次；既有 `candidate_and_remap_capacity_retry_keeps_published_team` 1 次（被本段新案的殘留連坐）。
  - Step 0 新案 `home_release_waits_for_exact_b_without_error_flood` 1 次：Step 0 RED root `t5-3-step0-red-a939c12d` 被 M-8 RED 留下的 pending roll-forward 連坐，之後補上收尾。
- 計數從 fix1 起重算（使用者選項說明「止損計數從修法起重算」）。重算的範圍是 T5-3 的全部新案，以及被本段影響的既有案（controller 的讀法，不是使用者原文）：
  - 換頁三案 0；`852f27c` 第 2 輪的三案各 1（真實 AccessDenied 與它的連坐），重跑時都是 0。
  - fix2 的新案與既有案 0。
- 真實 AccessDenied：`852f27c` 1 次，額度已用；`62dacdd` 0 次。
- 既有冷 B 案、門故障案：0。
- 工具錯誤：default 子集驅動 1 次、拆段驅動 2 次，root 都保留。

### CI

- `852f27c` 的 CI 45508192969 attempt 1 success（2026-11-25 00:00:22Z），10 個 job 全 success；原件 S `ci-45508192969-852f27c/terminal-run.json`（SHA-256 `5ad8401319bca8283d73004a9ed35c8fbccaff5e661f50a507bd245dd67ffe35`）。但 Windows「執行 M7 獨立 GameTest」步驟 602 秒（Gradle `BUILD SUCCESSFUL in 9m 57s`，離 Invoke-Gradle 600 秒總時限只剩約 3 秒），超過約 450 秒的 U1 回報線；回報使用者後裁示拆段。
- `c0dbdd7` 的 CI 45532643386 attempt 1 success（02:50:09Z），10 個 job 全 success；原件 S `ci-45532643386-c0dbdd7/terminal-run.json`（SHA-256 `657c23749770df5d737127b1cc305ff26abe7bdfe643857afa24bb168bf29c6a`）。這個 run 包含 T5-3 的最終 code `62dacdd`，Windows 逐段：Main 300 秒、Corridor 261 秒、規模 186 秒，都低於約 450 秒。
- `eb10bc8` 的 CI 45537137999 attempt 1 success（03:47:57Z），10 個 job 全 success；原件 S `ci-45537137999-eb10bc8/terminal-run.json`（SHA-256 `afdb5ff3c9189df78aeaf7655337cf60f7db530c33399b826afc6542b42b70e5`）。Windows 逐段：Main 258 秒、Corridor 215 秒、規模 164 秒。
- Ubuntu：上述三個 run 的 Ubuntu 兩個 job（`build`、`m7-validation (ubuntu-latest, Ubuntu)`）都 success，只照錄、不採計（暫停雙平台驗證）。

### 補記

- T5-1、T5-2 結案 docs `8d868e7` 的 CI 45406804011 attempt 1 failure（2026-11-24 14:26:49Z），依 M5 熔岩偶發常駐規則不阻擋、不重跑，不能算 CI 全綠。逐筆：
  - job `windows-native`；
  - step 8「Windows 原生 M5 GameTest」：M5 60 案中 1 failure，`m5universeprojectiongametests.projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids`，對照組等待逾時（tick 239）、`controlPistonPushed=true waterMoved=false`、`wet=Block{minecraft:cobblestone}`；
  - step 9「Windows M6 JUnit 與獨立 GameTest」：連帶的 `M6_SCOPE_MISMATCH：GameTest run 範圍不符（m5）：expected=60 actual=60 缺 [] 多 []`，M6 本身 35/0；
  - step 12「確認 Windows 原生案例確實執行」因前面的 step 失敗而 skipped；
  - 同一 run 的其他 9 個 job 都 success，沒有其他失敗。這是同簽章第 4 次。原件 S `ci-45406804011-8d868e7/`（`terminal-run.json` SHA-256 `da3d354ec4345304be38a553f16fb94dbad3782d4f603e463e81f7dba77590ed`，核對紀錄 `signature-check.md`）。
- 本筆 docs commit 的 CI 終局先記在 S `STATUS.md`，下次文件提交再收錄。

### 下一步

1. T5-4（唯一 HOME 選擇與保留式返 B）。開頭的 Step 0 先處理 plan 檔尾分給 T5-4 Step 0 的項目，加上 T5-2 已分配的 T5-4 Step 0 項目；T5-4 本體要先定案「選門與換頁、退休的互斥」。
2. 之後 T5-5（含 corridor-recovery 原生鏈與完整 run-all；完整回歸的採計改為兩段加總，並列入 m4-recovery／m5-collapse probe）。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

## 最新：T5-1、T5-2 結案（2026-11-24）

狀態：T5 分 5 段（見 plan 檔尾「2026-11-24 T5 切片與追加（controller）」節），T5-1、T5-2 已依段落結案；T5 整體未完成，完整回歸在 T5-5。

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。本節的事實讀自 S 的 `STATUS.md`（2026-11-24 16:5x～21:1x 各段）、`task-5-1-brief.md`、`t5-1-report.md`、`t5-1-review.md`、`task-5-2-brief.md`、`t5-2-report.md`、`t5-2-review-spec.md`、`t5-2-review-quality.md`、`task-5-2-fix1-brief.md`、`t5-2-fix1-report.md`、`t5-2-review-quality-fix1.md`、`t5-slice-plan.md`、`t5-12-docs-review.md`、三個 `ci-*` 原件目錄，以及 git 的唯讀查詢。

- **段落結案判定**（controller 的判定，不是使用者裁示）：production 完整門檻中，完整 run-all 以外的項目都要做到，也就是 RED、GREEN、mutation 逐位元還原、focused 兩輪、獨立審查 C/I 清零；再加上同 SHA CI 的 Windows 各 job success。完整 run-all 依切片方案在 T5-5 跑一次。Ubuntu 只照錄、未採計（暫停雙平台驗證）。
- 兩段的設計決定、審查 Minor 與交接事項的分配，以及 T5-1 legacy hash 基準的出處，見 plan 檔尾「T5-1、T5-2 的追加（controller，2026-11-24）」小節。

### T5-1：HOME 門模型與單門幾何

- commit：`a1468c3..79c232e`，RED `7992739`、GREEN `79c232e`，8 檔。
- 內容：
  - `HomeDoorLayout`：`key(UUID)` 是 `DoorKey(sid,1,POSITIVE_LATERAL)`，station1 POSITIVE 側唯一 25 格。
  - `CorridorGeometry.Builder` 的 HOME 模式：新增 package-private 五參數建構子，舊四參數建構子委派 `home=false`。
  - `QuantumBulkheadBlock.HOME_MARKER`：預設 false，state 數由 10 變 20。
  - `quantum_bulkhead_home.json`：只把 panel 換成 `cyan_concrete`。
- 驗證：
  - RED：`M7HomeDoorLayoutTest` 6 案 5 FAIL（首案 `expected: <25> but was: <8950>`）；`MembraneStateTest` 22 案 5 FAIL（缺 `home_marker`）。
  - GREEN：6/6、22/22；mutation 2 項都被殺掉，逐位元還原。
  - focused：JUnit 五類兩輪全過；M7 Main 一輪 `junit=59 gametest=57`＋SelfTest；default 子集一輪 8/8。
- 審查（一席，規格＋品質）：spec ✅、quality Approved，C0／I0／M6。
  - M-1（HOME 門不進候選）的 T5-2 部分（不發布候選、不寫 ledger）已處理；`candidateDoor`／`onUse` 的 HOME 排除屬 T5-4 原範圍。M-3（以真 Space 驗開口）、M-6（守門訊息）已在 T5-2 處理；M-2（key 寫死）交 T5-4 Step 0。
  - M-4：T5-1 報告說 main-only 會驗 client 對 20 個 state 的載入，這個說法過度。main-only 跑的是 dedicated server，只驗 server 端的註冊與世界載入；client 端目前只靠 `MembraneStateTest` 的 JSON oracle 與人工驗收。人工 smoke 的加驗項見 plan 檔尾。
  - M-5：RED 與 mutation root 沒有綁定 head 與 diff；還原 SHA 取的是工作樹 bytes（CRLF），報告沒有寫明；legacy 基準的出處只在 gitignored 的 SDD。T5-2 起，採計的 RED／GREEN root 都在乾淨 pin 上（記 head、dirty 為空），mutation root 另記 diff 與變異前後的檔案 SHA，並寫明取工作樹 bytes；基準出處補記在 plan 檔尾。
- CI：`79c232e` 的 CI 45362017556 attempt 1 success（台北 17:53:59），10 個 job 全 success；原件 S `ci-45362017556-79c232e/terminal-run.json`（SHA-256 `65220a6367ee3c6e57174a6301feabe9a7eefe77774cf102193aea94163cfe17`）。

### T5-2：HOME 入場與未選定返 B

- commit：`79c232e..bc87df0`，共 14 檔，全在授權清單內。
  - RED `ddb0c17`：`startHome` 一律回 REJECTED 的 stub，加 11 案 GameTest 與 inventory、entrypoint 登記。
  - GREEN `b8511e3`：production、testmod 接線，以及 T5-1 審查 M-3、M-6 的測試修正。
  - fix1 `bc87df0`：只改 testmod。
- 內容：
  - `SuperpositionSessionManager.startHome`：檢查順序是 latch → `PlayerCheckpointStore.unsupportedReason` → B 資格 → 暫占 → A 的 START_CHECK 票，受理時回 STAGING。票 FULL 的那個 tick 在 guard 內做 checkRoot（含即時電位）並重驗，接著凍結快照、租走廊、checked ARMING。逾時與 `/reload` 沿用既有暫占與票的 owner。
  - 入場：checked ARMING → 逐人原生移動 → 重驗 → 逐人 checkpoint → 重驗 → checked SUPERPOSITION → `commitHomeInitial`。Entry=B 的來源票保留到返還完成。
  - 未選定的取消（`returnToOrigin` 開頭的 PROJECTION 分派）、牛奶或 Buff 失效、離線，都整組回 exact B，不退款、不上 latch。exact B 不可用時留在走廊托管，不改送 A 或 spawn。
  - 收尾：全員返還、走廊清空並逐格核對 AIR、HOME checkpoint 成功之後，才在同一個 tick 建立一次性的 `HomeReturnProof`，憑它呼叫 `finishHomeReturn`。checkpoint 或移除失敗時保留列、space 與占用，退避後重試。收尾時 ack `completedReleases`。
  - `MeasuredWorldSaveCheckpoint.save/ready(MinecraftServer,HomeSessionRecord)`：範圍只有 exact live B 的艙體 frame 與走廊 lease，A 不入列。兩個 testmod mixin 的完整 descriptor 在同一個 commit 補上。
  - HOME Space 不發布候選，也不寫 M4 ledger（T5-1 審查 M-1）。
- 驗證（Windows）：
  - RED：在乾淨的 `ddb0c17` 上，`cancel_or_restart_returns_to_b` 以「HOME_START_REJECTED」失敗。`start_home_refuses_latched_entry` 在 stub 上會通過，改以 mutation M3 代證。
  - GREEN：乾淨 `b8511e3` 11/11；fix1 後乾淨 `bc87df0` 11/11。
  - mutation 共 10 個，各由具名測試殺掉，逐位元還原（SHA-256 取工作樹 bytes）：
    - M1 Entry 回傳改 Root、M2 返還給回原 Buff、M3 拿掉 latch 守門、M4 `finishHomeReturn` 前省略 checkpoint、M5 `unsupportedReason` 移到 claim 之後；
    - fix1：MI1a 不加來源票、MI1b 票加在 A、MI1b2 A 多加一張、MI1c 不釋放票、MI4 拿掉 JOIN 當 tick 的守門。
  - focused 兩輪（乾淨 `b8511e3`）：JUnit 七類全過；M7 Main 兩輪都是 `M7_VERIFIED PASS platform=Windows junit=59 gametest=68`，`-VerifyOnly -SelfTest` PASS。fix1 後 M7 Main 一輪同結果，SelfTest PASS。
  - 加寬 legacy 回歸一次（乾淨 `b8511e3`）：default 全 scope 166/166、M5GameTest 60/60、M6GameTest 35/35。
  - U1：本機 M7 Main 步驟 206／213 秒（fix1 後 199 秒）。Windows CI 的「執行 M7 獨立 GameTest」步驟（取 `startedAt`→`completedAt`）是 296 秒（`a1468c3`）、268 秒（`79c232e`）、340 秒（`bc87df0`）；Ubuntu 235 秒（`a1468c3`）、294 秒（`79c232e`）、297 秒（`bc87df0`）只照錄。都低於約 450 秒的回報門檻，但 Windows CI 已到門檻的約 76%，T5-3、T5-4 還會再加 GameTest。
- 審查：
  - 規格一席：spec ✅，C0／I0／M7。
  - 品質一席：要求修改，C0／I1／M11。I-1 是 Entry=B 來源票沒有任何 oracle：fixture 自己保持 B 載入，所以拿掉票、把票放到 A、或用完不釋放，11 案都照樣通過。
  - fix1 補上票數斷言與上述 MI 系列 mutation，並修品質 M-4、M-5、M-7；原品質席窄複審 Approved，C0／I0／M0。
- legacy 方法本體：規格審查判定只加分派或做等價改寫，不另跑 m4-recovery／m5-collapse probe。
- 止損計數（只計 Windows）：新案 2 個各 1 次（開發中的 oracle 修正）；工具 1 次（驅動腳本參數解析，已修，root 保留）；既有冷 B 案與門故障案 0 次；真實 AccessDenied 0 次。
- CI：`bc87df0` 的 CI 45395450940 attempt 1 success（台北 21:15:19），10 個 job 全 success；原件 S `ci-45395450940-bc87df0/terminal-run.json`（SHA-256 `e665e4f1e26decc79d2df161c42614a6bc2499a3f3db5b9c078076cee8966821`）。

### 補記

- T5-0 結案 docs `a1468c3` 的 CI 45352032717 attempt 1 success（台北 16:42:17），10 個 job 全 success；原件 S `ci-45352032717-a1468c3/terminal-run.json`（SHA-256 `65198ce08cda5bf37f04cedc508167318148361f339ec87f95fdcd6d741c5334`）。
- 本筆 docs commit 的 CI 終局先記在 S `STATUS.md`，下次文件提交再收錄。

### 下一步

1. T5-3（HOME 換頁與容量 pending）。開頭的 Step 0 先處理 plan 檔尾分配給 T5-3 Step 0 的 Minor；分給 T5-3 本體、T5-4、T5-5 的交接事項也列在同一處，原文見 S `t5-2-report.md`「交給 T5-3／T5-4／T5-5」。
2. 之後依序 T5-4、T5-5（含 corridor-recovery 原生鏈與完整 run-all）。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

## 最新：T5-0 結案（2026-11-24）

狀態：T5-0 已結案。`98036e8` 的 CI 45342810380 的 Windows 各 job 都 success（Ubuntu 兩個 job 也 success，依 Windows 優先只照錄、不採計）。

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`，V 指 `.superpowers/verification/`，兩者都 gitignored。本節的事實讀自 S 的 `STATUS.md`（2026-11-24 各段與 2026-11-20 Codex 各段）、`t5-0-report.md`、`t5-0-codex-report.md`、`t5-0-codex-review.md`、`t5-0-ci-fix2-report.md`、`t5-0-ci-fix2-review.md`、`t50-diagnostic-once-results.md`、`t50-door-fixture-design.md`、`t50-door-fixture-report.md`、`t50-door-fixture-review.md`、`t50-cold-b-pregen-report.md`、`t50-cold-b-pregen-review.md`、`t5-slice-plan.md`、三份使用者原文檔與六個 `ci-*` 原件目錄，V 的 `run-all-bc6ed3352e204d2d87167eb1b2fecbfa`，以及 git 的唯讀查詢（含 `git ls-remote origin`）。下方「Codex 交回，Claude 接手」「D1受限診斷授權」「Codex 接手 T5-0」三節是過程紀錄，本節是彙整。

- **結案判定**（controller 依 B+C 與 Windows 優先規則的判定，不是使用者裁示）：production 完整回歸 `6db3ce6`（`run-all-bc6ed335…` `END ALL exit=0`），加上之後 test、workflow、docs 各批的一席審查與定向，加上 `98036e8` 的 CI 45342810380 的 Windows 各 job 全 success。依下方「止損與使用者裁示」第 5 點的 controller 執行說明，CI 只要求 Windows 各 job success，Ubuntu 兩個 job 照錄。
  - code pin：production 最後一筆是 `19d6e3b`。`git diff --stat 6db3ce6..98036e8 -- src/main` 為空，之後各筆只改 testmod、README、workflow 與文件。
  - `git ls-remote origin` 讀回 `codex/m7-return-home` 是 `98036e8d97127217694ebf0c63a3033aa0eac445`，main 是 `2255027`。
  - 完整回歸：`6db3ce6` 的 `V/run-all-bc6ed335…` 自然 `END ALL exit=0`，採計 26 項全過（見下方「完整回歸」）。
  - 審查（每次都是一席，spec＋quality 同席）：首審 `c533193..df017d2` C0／I2／M2，fix1 原席複審 C0／I0／M0；fix2 C0／I0／M1；D1 C0／I0／M0；門故障夾具 C0／I0／M2；冷 B 先生成＋workflow C0／I0／M2。
  - CI：結案前的五次 CI 有四次 FAIL（原判保留，都沒有 rerun），D1 那次十個 job 全 success；結案 CI 45342810380（`98036e8`）十個 job 全 success。見下方「CI 歷程」。
  - T5-0 結案不代表 T5 完成。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

### commit

| commit | 內容 |
| --- | --- |
| `b0ea0f8` | 第一批（Claude）：原生 SelfTest 預算改每次呼叫 1000 個＋120 秒，補 B 收尾票判定的負控 |
| `c749739` | 第二批（Claude）：連坐缺口兩處、fixture checked 寫入失敗記 cause、T4B 審查 M-2 的門故障新案、M-7 判斷式收尾、CI 規模段時限 |
| `8f3c977` | docs：交給 Codex |
| `3ee0bef` | 第三批 RED（Codex）：T4B 審查 M-1、M-3 的具名 RED（`src/main` 不變） |
| `19d6e3b` | 第三批 GREEN：`HomeClosure` 停止守門與 FULL 端點釋票、`SessionRecoveryState.java:1027` 註解、B2b5-M1（`m7-ci-gametest.ps1`）；**production 最後一筆** |
| `df017d2` | docs：定向終局 |
| `6849c84` | fix1（test）：首審 I-1、I-2、M-1、M-2 |
| `6db3ce6` | docs：複審清零與完整回歸起點（完整回歸的 HEAD） |
| `19e9aac` | fix2（test）：門故障案在正式票之後才準備投影端 |
| `e3ce6e1` | D1（test）：門故障瞬間的票與就緒觀測，只記錄 |
| `73f26e8` | docs：D1 結果同步、Codex 交回 Claude |
| `e5690ef` | 門故障夾具（test）：助載時同步載入半徑 2，補故障瞬間持票見證 |
| `1c61292` | docs：README :849 不把未斷言的性質寫成已驗證（門夾具審查 M-2，controller 修） |
| `81ff022` | 冷 B 先生成（test）：7 個冷 B 案取票前先生成 B 地形、時序 marker；門夾具審查 M-1 |
| `98036e8` | workflow：Ubuntu 兩個 job 照跑但不擋 |

- 範圍 `c533193..98036e8`，共 15 筆。`3ee0bef`、`19d6e3b`、`6849c84`、`19e9aac`、`e3ce6e1` 同時帶 AGENTS、本 note 或交接檔的狀態更新（Codex 期間的做法）。

### 各批要點

- 第一批（`b0ea0f8`，S `t5-0-report.md`）：依使用者 2026-11-19 11:5x 的 SelfTest 上限裁示，每次 `-VerifySummary -SelfTest`／`-VerifyAggregate -SelfTest` 呼叫最多 1000 個負控、120 秒，超過分別是 `M7_HOME_SELFTEST_BUDGET`、`M7_HOME_SELFTEST_TIME`；補 `M7_HOME_REOPEN_B_TICKET`、`entryTicketLease` 的負控。新數：單份完整 404、family 三份 434、門態五份 454。變異 MA～MH 八個都以具名錯因殺掉、逐位元還原。
- 第二批（`c749739`）：`sharedEntry` 外層失敗走既有正式收尾；M4 readback probe 的 journal 寫入改走正式 schema5 路徑；fixture checked 寫入失敗記 `TESTMOD_FIXTURE_WRITE_FAILED … cause=…` 再原樣丟出；T4B 審查 M-2 的新案 `close_ticket_released_when_the_door_write_fails_and_backs_off`（`backOff` 單點變異以 `HANDOFF_FAIL_TICKET_HELD` 殺掉）；M-7：`until` 的判斷式丟出時先跑該 context 的收尾；CI 規模段兩個 step 加 `if: ${{ !cancelled() }}` 與 20／10 分時限。
- Codex 接手（2026-11-20）後，交接 HEAD `8f3c977` 的 CI 44480505340 Windows budget 案失敗（見下方「CI 歷程」）。Codex 期間的 controller 決定（不是使用者裁示）：budget 案只在合時、同 SID、exact B 的正式 `CLOSE_CONVERGE` 票已在 tick 結束時觀察到之後，才由 fixture 補 B ready；原時限、額度與斷言不變（S `t5-0-codex-report.md`）。
- 第三批（`3ee0bef` RED → `19d6e3b` GREEN）：T4B 審查 M-1、M-3。
  - RED：root `S/c-t50-red-ef21bd4f` 3 案恰 2 FAIL（`RESTART_ROOT_STOP_WRONG_TICKET`、`CLOSE_FULL_INVALID_TICKET_RETAINED`），production 未改。
  - GREEN：`restartVerifies` 排除已有 stopReason 的列；票 FULL 而且端點決定性非 VALID 時，兩種票用途都在同一個 tick 釋票、以正確原因 WARN 並退避（UNKNOWN 仍有界等待）；到上限的 ERROR 提示補 B 端點合法性。小集合 5/5；直接變異 M1、M3 各只有目標案 FAIL，`HomeClosure.java` 還原後 SHA-256 `5EBDE4E8…386AD`。
  - 同一筆另含 `SessionRecoveryState.java:1027` 註解的歸屬（改標為 controller 的判讀，不是使用者裁示）與 B2b5-M1：`m7-ci-gametest.ps1` 核每個 `M7*Test.java` 都列入 Main 或 Scale，未支援的宣告形式以 `M7_JUNIT_DECLARATION_FORM` 拒絕。
  - 乾淨 `19d6e3b` 定向兩輪：Main JUnit 53／GameTest 55、Scale 11、default 5、M5 2，VerifyOnly＋SelfTest 15／13 例。
- fix1（`6849c84`）：首審 I-1（fixture checked 提交失敗時保留 cause）、I-2（判斷式清理的 close 失敗仍執行 hooks）、M-1（執行負控之前就核 1000 上限）、M-2（README 實測值）。首個 GREEN `S/c-t50-f1-green-4ee63593` 因新 oracle 期待簡名 `IOException`（實際是 `java.io.IOException`）非預期 FAIL，原判保留；只校正 oracle 後新 nonce 2/2 PASS。Main covering JUnit 53／GameTest 57、SelfTest 15。
- fix2（`19e9aac`）：CI 44674890854 的門故障案失敗之後，改成在 tick 結束時實際看到同 SID exact `CLOSE_CONVERGE` 首票、正確 refs 之後才由 fixture 保持 B；門故障、同 SID WARN 與下一個 tick 的退避一起成立才驗退避，其他原因的退避具名 FAIL。既有 `backOff` 單點變異恰一個目標 FAIL、逐位元還原；Main 53／57、SelfTest 15。
- D1（`e3ce6e1`）：使用者授權的單輪診斷（見下方「止損與使用者裁示」第 1 點），只加觀測 marker `M7_T50_DOOR_DIAG_V1`，原斷言與期限不變；Windows 1/1、CI 1/1 已用。三組有效對照（本機 Windows、CI Windows、CI Ubuntu）助載之後 entity／checkpoint 就緒分別晚 91、96、167 tick，故障距期限分別尚有 108、103、32 tick。舊 Ubuntu 失敗沒有重現，不能回填舊現場或歸因平台（S `t50-diagnostic-once-results.md`）。
- 門故障夾具（`e5690ef`）：設計 S `t50-door-fixture-design.md` 讀碼推論，正式 B 收尾票是 level 31（ENTITY_TICKING），entity 要等每個 home chunk 周圍 5×5 全部 FULL 才載入；原夾具只到 level 33，外圈要在正式票之後現場 worldgen。
  - 修正：助載的同一個 test callback 以同步 `getChunk`（不消耗 tick）把半徑 2 載到 FULL；加兩項見證（助載後半徑 2 全 FULL；門寫入失敗的瞬間仍持同一份首票、tick 小於期限）與夾具前提的錯因。
  - 負控 NC-A（撤同步載入）、NC-B（半徑 1）、NC-C／C′（租期到期之後才故障）、NC-D（`backOff` 不釋票）都以預期錯因失敗、逐位元還原。entity 就緒相對首票：無同步載入 +79、半徑 1 +35、半徑 2 一律 +2 tick。
  - 乾淨 `e5690ef`：門 batch 5/5、整檔 10/10、Main JUnit 53／GameTest 57、SelfTest 15。
  - `1c61292` 是 controller 依審查 M-2 改 README :849 一句。
- 冷 B 先生成（`81ff022`）：依使用者所選「測試先生成 B 地形（建議）」（見下方「止損與使用者裁示」第 4 點）。
  - 7 個冷 B 案在 B 就緒、放掉測試票之前，以同步 `getChunk` 把 B 各 home chunk 周圍半徑 2 的聯集載到 FULL 並自檢（否則 `PREGEN_REGION_NOT_FULL`），之後照原流程等 B 卸載；另加時序 marker `M7_T50_COLD_B_V1`，只記錄、不參與判定。同一筆修門夾具審查 M-1：`HANDOFF_FAIL_ENTITY_REGION_NOT_FULL_AT_ASSIST` 只在助載發生過時命名。
  - A/B 對照（commit 前各一次）：首票到 checkpoint 前置就緒，有先生成時 `restart_verify` 1、`close_loads` 10 tick，撤除先生成時 56、42 tick；兩變體四案都通過，這是機制對照，不是 pass／fail 判定。負控（只載半徑 1）以 `PREGEN_REGION_NOT_FULL` 失敗、逐位元還原。
  - 乾淨 `81ff022`：整檔兩輪各 10/10、Main JUnit 53／GameTest 57、SelfTest 15；全部 marker 的 `homeFullAtFirstLeaseEnd` 都是 0（取票那個 tick 結束時 B 的 home chunks 0 格 FULL）。
- workflow（`98036e8`）：`m7-validation` 加 `continue-on-error: ${{ matrix.os == 'ubuntu-latest' }}`、`build` 加 `continue-on-error: true`；`m6-native`、四片 `m7-home-native`、`m7-home-native-aggregate`、`windows-native` 不變，也沒有 job 以 `needs` 依賴這兩個 job（冷 B 審查第 7 節）。

### 冷 B 先生成的範圍（審查 M-1、M-2）

- 7 案取票當下 B 都仍是「未載入」；之後誰載入 B 各不相同（冷 B 審查 M-1，實作報告第 1 節）：
  - 由正式票獨力載入：`close_loads_unloaded_b_with_bounded_ticket`、`restart_verify_ticket_loads_b_for_unreadable_seal`、`close_ticket_releases_the_root_ticket_first`；`close_ticket_released_on_reload_and_stop` 的前兩份票是 FakeBackend，換回原生 backend 後的第三份原生票獨力載入。
  - 有 fixture 助載：`close_ticket_budget_exhausted_warns_and_retries`、`close_ticket_releases_at_full_with_invalid_entry` 在看到正式票之後由 fixture `holdChunks` 助載。
  - FakeBackend 前段：`restart_seal_with_root_stop_freezes_close_before_loading_b` 的首票是 FakeBackend（不加票），之後 B 由 fixture `holdChunks` 載入。這一案與 `close_ticket_releases_at_full_with_invalid_entry` 的先生成只是統一前提，對判定沒有作用。
  - 先生成保證的是所需地形已生成、不必在 200 tick 票期內現場 worldgen；嚴格說不一定從磁碟讀，剛卸載的 chunk 可能還在卸載佇列或 IO 快取。本筆已依此改寫 `scripts/verification/README.md` 的對應說明。fixture 類別 Javadoc（`M7HomeEntryTicketGameTests.java:40`）本筆由 controller 改寫（只改註解）：舊句「正式票從磁碟載入 B」改為註明冷 B 共 7 案、`close_ticket_never_full_releases_and_backs_off` 除外，並寫「正式票取得後不必在票期內現場 worldgen」，指向 README 的逐案說明。
- `close_ticket_never_full_releases_and_backs_off` 不納入先生成，是 controller 的決定。
  - controller 當時記的理由有兩處：原文檔 controller 執行說明的「`never_full` 以 FakeBackend 永不 FULL，不在內」，以及 S `STATUS.md` 2026-11-24 14:3x 段的「同型風險主要在 Ubuntu，依 Windows 優先不擋；列觀察，controller 決定」。
  - 冷 B 審查 M-2 事後依讀碼指出排除可以成立的依據：它換回原生 backend 之前已把退避歸零，第一份原生票到期後只退避 200 再重取，`CLOSE_TICKS` 600 內能容忍一次到期；`close_ticket_released_on_reload_and_stop` 收尾前已用掉 200 的退避，一次到期就超過 600，所以後者納入。「以 FakeBackend 永不 FULL」不是這個依據。
  - 它仍是 GameTest 中唯一在票期內現場 worldgen 的冷 B 收尾，也就是 Windows GameTest 上唯一同型的殘留曝險；原生 `restart-close-unloaded-b` 是另一條票期內可能現場 worldgen 的路徑，依裁示不改。它在 Windows 上非預期失敗時，依止損停下問使用者，不當成與冷載無關。

### 完整回歸

- root `V/run-all-bc6ed3352e204d2d87167eb1b2fecbfa`，HEAD `6db3ce681bb9a960df7c34a126444c3c843795bd`（console 第 2 行 `STATUS ` 之後為空），holder `S/c-t50-full-434bb30749704defa932f6165b595858` outer 0。console 第 444 行 `END ALL exit=0 HEAD=6db3ce68…`（2026-11-20 12:27:36 台北）。只跑一次，沒有重跑。
- `summary.json` SHA-256 `3B8EDB79B5C363C41C8646E148D7D1CC1FCF62E6E62DCC3636179065B9B43780`，status PASS。console 第 442 行（逐字）：`SUMMARY default=166/166 windows=76 legacy=1/1 m5=60/60 m6=35/35 m6pace=22.1 normal=13/13 perTest=43+13+1 junit=1199 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=639entries/592classes sources=276 testmodHits=0 mainOnly=PASS m3=4+5 recovery=25 m5collapse=69 m6native=DIAGNOSTIC:2,FUNCTIONAL:25 ci=win:PASS,ubuntu:PASS m7native=COMPLETE:47 m7scale=PASS:11`。
  - skipped 2 都是 `NonWindowsPlayerCheckpointStoreTest`，在 Windows 跳過的既有案；M7 集合 zero skip。
  - M7 gate `M7_VERIFIED PASS platform=Windows junit=53 gametest=57`（launched 與 VerifyOnly 兩行）；規模段 `M7_SCALE_VERIFIED PASS … junit=11`。
  - M7 原生 `M7_HOME_VERIFIED_COMPLETE PASS rows=47 phases=94 negativeChecks=404 selfTestSeconds=2.2`；M6 native `chains=9 phases=27 negativeChecks=479`。
- 採計：controller（Codex）以唯讀腳本逐項核對，26 項全 true（`ACCEPT_FAILS=0`）；改用舊的 46／92／376 對同一個 root 核對，恰 5 項 false（`ACCEPT_FAILS=5 native.rows,native.phases,native.verifyLine,native.neg,summaryLine.native`）。原件 S `c-t50-accept-8dd3f3a05704471a9778ead6de9c596f`。

### CI 歷程

| run | head | 結論 | 失敗的 job 與案 | 處置 |
| --- | --- | --- | --- | --- |
| 44480505340 | `8f3c977` | attempt 1 failure，10 job 中 9 success | `m7-validation (windows-latest, Windows)` 的「執行 M7 獨立 GameTest」：`close_ticket_budget_exhausted_warns_and_retries`（`BUDGET_RETRY_NOT_CLOSED`） | 原判保留、不 rerun；budget 案的 fixture 責任分區（見「各批要點」） |
| 44674890854 | `6db3ce6` | attempt 1 failure，9 success | Windows 同一 step：`close_ticket_released_when_the_door_write_fails_and_backs_off`（`HANDOFF_FAIL_TICKET_HELD`；`doorWriteFaultHit=false`、`oneDoorWarn=false`：首份票租期到期的退避被判斷式當成門故障） | 原判保留、不 rerun；fix2 `19e9aac` |
| 44680282020 | `19e9aac` | attempt 1 failure，9 success（Windows M7 success） | `m7-validation (ubuntu-latest, Ubuntu)` 的同一 step：同一門故障案（53 案 1 FAIL；只有 `leaseHeldBeforeTheFailure=false`） | 原判保留、不 rerun；同夾具 3/3 止損，停下問使用者 → D1 |
| 44693421016 | `e3ce6e1` | attempt 1 success，十個 job 全 success | — | D1 診斷；未重現，不因此宣告修復或結案 |
| 45331322846 | `1c61292` | attempt 1 failure；Windows 各 job 與 Ubuntu `build` success | Ubuntu 同一 step（53 案 2 FAIL）：`restart_verify_ticket_loads_b_for_unreadable_seal`（`verifiedWithARestartVerifyTicket=false`）、`close_loads_unloaded_b_with_bounded_ticket`（`CLOSE_PENDING_WITHOUT_B_TICKET`）；門故障案 PASS | 原判保留、不 rerun；共用冷載超過 2/3，停下問使用者 → 冷 B 先生成 |
| 45342810380 | `98036e8` | attempt 1，event push，completed／success；十個 job 全部 completed／success，沒有 failed step；最後更新 2026-11-24T07:34:21Z（台北 15:34:21） | — | 結案 CI。Windows 8 個 job（`m7-validation (windows-latest, Windows)`、`windows-native`、`m6-native`、`m7-home-native` 的 passable／ordinary／sealed／restart-receipt 四片、`m7-home-native-aggregate`）都 success；Ubuntu 2 個 job（`m7-validation (ubuntu-latest, Ubuntu)`、`build`）也 success，依 Windows 優先只照錄、不採計 |

- terminal 原件：S `ci-44480505340-codex-0befa5f8a4734cff83d2016383ae9418/run.json`、`ci-44674890854-6db/terminal-run.json`（SHA-256 `F67FFC2A…CFADBE7`）、`ci-44680282020-19e9/terminal-run.json`（`D437F89F…8553BC`）、`ci-44693421016-d1-e3ce/terminal-run.json`（`AFD77297…6E9177`）、`ci-45331322846-1c61/terminal-run.json`，45331322846 的 Ubuntu GameTest XML SHA-256 `ead3938e…5d9af5`；結案 CI 是 `ci-45342810380-98036e8/terminal-run.json`（SHA-256 `6b2225097710bdee0446f64a53f0afff50c80e23f78b835ace533f36f7bc442b`），event 另以 `gh run view` 讀回。
- 45331322846 依下方第 5 點的 controller 執行說明：Windows 各 job 都 success，Ubuntu M7 的 2 FAIL 照錄、不擋；T5-0 改以 `98036e8` 的 Windows 各 job 判定是否結案，結果是 45342810380 的 Windows 各 job 全 success。
- 沒有動用 M5 熔岩偶發的讀法。

### 止損與使用者裁示

1. **D1 受限診斷（2026-11-20）**：同門夾具達保守計數 3/3 後停下。原文檔 S `user-t50-diagnostic-authorization-20261120.md`，題目原文：

   > 同門夾具已按保守計數達 3/3 止損。是否只授權補活票／租期／ready 與樣本觀測，原席審查後跑一次 Windows 定向＋新 SHA 的既有 CI，完成即停？不改 production、行為、斷言或時限，舊 CI 不 rerun。

   使用者所選選項原文：

   > 允許一次受限診斷（建議）

   controller 執行邊界（不是使用者裁示，原文檔同名段）：只開一次診斷窗口，不重置 3/3 或其他計數，不授權正式修正或 T5～T8；即使碰巧通過也不宣告修復或 T5-0 結案。D1 已完成即停（見下方「D1受限診斷授權」節）。
2. **Claude 接手（2026-11-24 11:3x）**：使用者原話「codex回報換你繼續做」。controller 讀法（不是使用者逐字裁示）見下方「Codex 交回，Claude 接手」節：恢復一般 T5-0 工作，含門故障夾具的正式 test-only 修正；不改 production、斷言、票期 200 與時限；修正後此門案例再有任何非預期失敗即停並問使用者；共用冷載計數維持 2/3。
3. **CI 綠了就繼續（2026-11-24 13:1x）**：使用者原話「好，CI 綠了就繼續做 T5」（S `STATUS.md` 13:1x 段）。controller 執行（不是使用者裁示）：CI 全綠 → T5-0 結案 docs（一席審查）→ 派 T5；CI 若有非預期失敗仍照止損停報。`1c61292` 的 CI 45331322846 FAIL，所以停下（第 4 點）。之後「CI 綠」依第 5 點的 controller 執行說明，以 Windows 各 job success 判定。
4. **冷 B 先生成（2026-11-24，原文檔標 13:4x）**：CI 45331322846 的 Ubuntu 兩個冷 B 案失敗，共用冷載超過 2/3，停下詢問。以 AskUserQuestion 提問，題目與選項由 controller 撰寫，原文檔 S `user-t50-cold-b-pregen-ruling-20261124.md`。題目（header「冷 B 測試」）原文：

   > T5-0 的 CI 又失敗了：Ubuntu 上另外兩個「冷 B」測試沒過（門故障案這次通過）。原因和門故障案一樣：B 周圍的地形是取票後才現場生成，測試裡 200 tick 票期只有約 2 秒，Ubuntu 生成太慢。正式遊玩時玩家去過 B，地形通常已生成；票期約 10 秒。要怎麼處理？

   選項原文：
   1. 「測試先生成 B 地形（建議）」：「只改測試：這些冷 B 案在測試開始時先把 B 周圍 5×5 生成好並卸載，取票時 B 仍是未載入，正式票仍要自己載入 B，只是從磁碟讀、不必現場生成（貼近正式遊玩）。不改正式程式、斷言、票期與時限；審查、定向驗證、CI，再有非預期失敗就停下問你。」
   2. 「先只讀調查再決定」：「不改任何檔，先把這次兩案的原件與所有冷 B 案的時序查清楚，帶著證據和修法選項回來再問你。較慢。」
   3. 「先停在這裡」：「不修、不重跑，T5-0 維持未結案，T5 不開始，等你另行指示。」

   使用者選擇：「測試先生成 B 地形（建議）」。

   controller 執行說明（不是使用者裁示，原文檔同名段）：範圍是所有「B 未載入時由正式 B 收尾票載入 B」的 GameTest 冷 B 案，`never_full` 不在內，原生 `restart-close-unloaded-b` 不改；先生成之後取票當下 B 仍必須符合「未載入」判定；修正後，任何冷 B 案或門故障案在 candidate commit 之後再有非預期失敗，立即停下問使用者。

   另外（S `STATUS.md` 2026-11-24 14:3x 段與實作報告第 9 節）：implementer 依上述定義把派工清單沒列的 `close_ticket_released_on_reload_and_stop` 也納入，controller 接受，實際 7 案。選項說明的「正式票仍要自己載入 B，只是從磁碟讀」與實際不完全相符，見上方「冷 B 先生成的範圍」。
5. **Windows 優先（2026-11-24 13:44、14:02）**：原文檔 S `user-windows-first-ruling-20261124.md`。13:44 使用者原話（主 chat，非選項題）：

   > 我想要先以windows系統為主，先暫時逐步取消雙平台驗證，不然進度會一直被拖延

   14:02，controller 說明 Ubuntu 受控拒絕的回歸風險之後，使用者原話：

   > 接收風險反正未來只在windows遊玩且本reop開源，所以就讓其他人解決Ubuntu linux問題吧

   controller 執行說明（不是使用者裁示），各項出處標在行末：
   - Ubuntu 兩個 job（`build`、`m7-validation` 的 Ubuntu 那一格）照跑但不擋 workflow（job 層 `continue-on-error`，`98036e8`）；Windows 各 job 不變。（第一步；原文檔記為已在回覆告知使用者，可更正）
   - 結案：本機 Windows 門檻照舊；CI 只要求 Windows 各 job success。Ubuntu 失敗時不寫「CI 全綠」，也不因 Ubuntu 失敗停下或重開 task。（第一步，同上）
   - plan／spec 要求 Ubuntu 證據的項目，結案 docs 標「Ubuntu 未採計（暫停雙平台驗證）」。（第一步，同上）
   - 止損計數（門案例、共用冷載）只計 Windows 的非預期失敗。（第一步，同上）
   - Ubuntu 失敗不再調查或修正；結案 docs 只用一行記 Ubuntu 的結果（成功，或失敗的案名）。（14:02 追加的 controller 說明）
   - 之後不新增 Ubuntu 專屬的測試案例，平台相依的行為以 Windows 上的模擬（例如 `CheckpointPlatformFault`）驗證；既有 Ubuntu 案照留、照跑、不擋。（14:02 追加；第一步原寫 Ubuntu 專屬行為的測試照寫、照跑，14:02 追加改為不新增）
   - README 補一句 Linux／Ubuntu 未驗證、歡迎他人處理。（14:02 追加；本筆依結案 docs 審查 M-4 改寫為「T5 起（2026-11-24）只以 Windows 驗證」，controller 的決定）
   - 目前不從 CI 移除 Ubuntu job，使用者要求時再移除。（14:02 追加）Ubuntu 若持續只產生雜訊，是否完全移除、M7 結束前要不要補一次雙平台驗證，之後另問使用者。（原文檔「之後的步驟」）
   - 判讀 CI 逐 job 看 conclusion，不看 run 層的 conclusion：Ubuntu job 失敗時 run 仍可能是 success。（不在原文檔；冷 B 審查第 7 節的建議，controller 採用）

### 採計方式（controller 的判定，不是使用者裁示）

- production 的完整回歸採 `6db3ce6`。`19d6e3b..6db3ce6` 沒有 `src/main` 變更，所以這次完整回歸涵蓋 production 最後一筆 `19d6e3b`。
- `6db3ce6` 之後：`19e9aac`、`e3ce6e1`、`e5690ef`、`81ff022` 只改 testmod 與 README，`73f26e8`、`1c61292` 只改文件，`98036e8` 只改 workflow。依 B+C 採計：各批一席審查（fix2、D1、門故障夾具、冷 B 先生成＋workflow，結果見本節開頭）、各自的 Windows 定向（見「各批要點」）、`98036e8` 的 Windows CI（45342810380 的 Windows 8 個 job 全 success）；不新跑本機完整回歸。
- 這是 controller 依既有 B+C 規則與使用者 Windows 優先指示的判定。
- Ubuntu 未採計（暫停雙平台驗證）：`98036e8` 的 Ubuntu 兩個 job 結果照錄：45342810380 的 `m7-validation (ubuntu-latest, Ubuntu)` 與 `build` 都 success。
- 本筆結案 commit 除文件外，含 `M7HomeEntryTicketGameTests.java:40` 這一行 testmod 註解（controller 改寫，見上方「冷 B 先生成的範圍」）。這行註解依 B+C 採結案 docs 的一席審查（S `t5-0-closeout-docs-review.md`）加這個 commit 的同 SHA Windows CI，不另跑定向；T5-0 的判定仍以 `98036e8` 的 Windows CI 為準。

### 殘留與交接（controller 的決定，不是使用者裁示）

- fix2 審查的 Minor：`RemovalWatch` 只記曾經移除，沒有記首次 checked 移除的 server tick，不能單靠最後的查詢排除未來 owner 在 retryAt 之前提早收尾（本輪沒有觀察到）→ T8 整分支審查。
- production 觀察（門夾具設計第 6 節）：票 FULL 不等於 checkpoint ready；正式票 level 31，entity 載入要等 5×5 全部 FULL，所以 5×5 的 worldgen 須在租期內完成（正式伺服器 20 TPS 下租期約 10 秒；玩家曾在 B 時外圈通常已生成）→ T8 整分支審查評估。
- 未驗：真停服（停服釋票只以反射呼叫 `closeAll(server, true)` 驗證）、RESTART_VERIFY 的真重啟（只在 GameTest 以反射注入開服集合驗證）。Step 0 relatch 變異在 Ubuntu 分支會不會被殺：依 Windows 優先不再追，Ubuntu 未採計（暫停雙平台驗證）。
- 觀察：`quantumchamber-jars` artifact 只由不擋的 Ubuntu `build` 上傳（`build.yml:582–588`），Ubuntu build 失敗時可能缺席或殘缺（冷 B 審查第 10 節）。
- 觀察：`scripts/verification/README.md` 其他段落仍以兩平台描述 CI（冷 B 審查第 10 節第 2 點）；本筆只在 M7 規模段的 CI 項補一句。
- T4B 審查 M-3 中「同原因」的全歷史 WARN 去重仍為待辦（S `t5-0-codex-report.md`），T5 切片方案沒有列入。
- 限制（冷 B 審查第 3 節）：先生成的自檢 `PREGEN_REGION_NOT_FULL` 只守半徑，不守有沒有呼叫；撤除先生成的變異不會讓任何案例失敗，只在 marker 記 `pregen.ran=false`。
- 限制（冷 B 審查第 6、7 節）：Ubuntu 的 M7 Main step 沒有 step 時限，卡住時整個 run 可能拖到 job 的 90 分；job 層 timeout 與 `continue-on-error` 的互動、run 層 conclusion 怎麼呈現，審查標為低確定。依 Windows 優先只觀察。
- plan :319 的「overlay 同一 HOME mode」在 T5 切片方案沒有分到哪一段：由 T5-1 Step 0 確認歸屬（幾何 overlay 屬 T5-1，若涉 Space 接線則交 T5-2）。
- T5 Step 0 清單（下方「最新：T4B CLOSED（2026-11-19）」節「殘留交 T5 Step 0」）的處置：
  - SelfTest 負控上限：`b0ea0f8`、`6849c84`（首審 M-1）。
  - T4B 審查 M-1、M-3：`3ee0bef`、`19d6e3b`。M-2：`c749739`，門故障案的夾具之後經 `19e9aac`、`e5690ef`、`81ff022` 修正。M-7：`c749739`、`6849c84`（首審 I-2）。
  - fixture checked 寫入失敗記 cause：`c749739`、`6849c84`（首審 I-1）。
  - 既有第 1 點（連坐缺口兩處）、第 3 點（B2b5-M2）：`c749739`；第 2 點（B2b5-M1）、第 4 點（`:1027` 註解歸屬）：`19d6e3b`；第 5 點併入 SelfTest 上限。
  - 第 6 點 → T5-5；第 7、8 點 → T6（T5 切片方案 C10，controller 採用）；第 9 點 → T6（Codex 期間 controller 的決定，見下方「Codex 接手 T5-0」節）；第 10 點 → T5-2～T5-4 全段的約束（新 GameTest 不得斷言絕對世界數）。
  - 門夾具審查 M-1 原排 T5 Step 0，已併入 `81ff022`；M-2 由 controller 在 `1c61292` 修正。

### 計數

- AccessDenied：production pin `19d6e3b` 的完整回歸（HEAD `6db3ce6`）一次通過，沒有用到這個 pin 的單次重跑額度。
- 完整回歸啟動器歷史 2/3 不變。
- 門故障夾具：同夾具保守計數到 3/3 時停下（m7-dev1 門一開始就開著、44674890854 租期到期被誤作門故障、44680282020 持票前置未證；三次原因不同，不冒稱同一根因），之後經 D1 與 Claude 接手後的正式修正處理。共用冷載：45331322846 超過 2/3 時停下，經冷 B 裁示處理。`81ff022` 之後，任何冷 B 案或門故障案再有非預期失敗即停下問使用者；依 Windows 優先只計 Windows。
- T5 新案（S `t5-slice-plan.md` 檔尾 U2，controller 的決定，已告知使用者）：各自起算，同一 fixture 第三次非預期失敗才停；只計 Windows。
- 工具：fix1 首個 GREEN 的 oracle 簽章事件 1；D1 的靜態記錄器事件 1（編譯 `verified.json` 的 `sourceUnchangedDuringCompile=false` 是 PowerShell 自動變數 `Matches` 覆寫所致，原件保留，獨立讀回三個 hash 相同）。
- 工具教訓（controller）：controller 的 CI 輪詢腳本用了本機沒有的獨立 `jq`，迴圈卡住；45342810380 實際在台北 15:34 完成，controller 15:45 才讀到。之後輪詢只用 `gh … --jq`。
- M5 熔岩偶發：沒有動用。

### plan

- T5-0 沒有專屬的核取框；本筆不勾 plan 任何核取框（:548 屬 T5-5）。
- plan 檔尾新增「2026-11-24 T5 切片與追加（controller）」節：5 段切法與對應的既有核取行號、C8 擴檔清單、C9 追加 mutation、C10、C11、X1、X2、C1、C3～C7、C12、C13、U1 的一行摘要與 `t5-slice-plan.md` 的 SHA-256，以及 Windows 優先下 Ubuntu 證據的處理。既有各行不動。

### 下一步

1. 本筆結案 commit 的 CI 終局先記在 S（`STATUS.md`），下次文件提交再收錄（controller 的決定）。
2. T5-1（HOME 門模型與單門幾何），依 S `t5-slice-plan.md` 與 plan 檔尾新節。切成 T5-1～T5-5 與這個順序是 controller 依規劃員建議採用的決定，不是使用者裁示；開始 T5 的依據是使用者 13:1x 的「好，CI 綠了就繼續做 T5」，「CI 綠」以 Windows 各 job success 判定（controller 的讀法）。
3. main 合併、tag、人工代操作、worktree 退役、PR、LICENSE 仍各自另問使用者。

## 最新：Codex 交回，Claude 接手（2026-11-24）

（已被上方「最新：T5-0 結案（2026-11-24）」節接續。）

- 使用者原話：「codex回報換你繼續做」。Claude 只讀核對 HEAD＝origin feature＝`e3ce6e174be4438322d9e21f51762a6eb259ae7a`、main `2255027`，之後接手為 controller。
- controller 讀法（不是使用者逐字裁示；已在回覆告知使用者，使用者回覆「好」）：恢復一般 T5-0 工作，含門故障夾具 `close_ticket_released_when_the_door_write_fails_and_backs_off` 的正式 test-only 修正。不改 production、斷言、票期 200 與時限；修正後此門案例再有任何非預期失敗即停並問使用者；共用冷載計數維持 2/3。
- 夾具設計（只讀、未實跑）：M7 SDD `t50-door-fixture-design.md`。讀碼推論的根因：正式 B 收尾票是 level 31，entity 載入要等 5×5 區域全部 FULL；夾具的暖機與助載只到 level 33，外圈要在正式票後現場 worldgen，對應 D1 的 91～167 tick。另推論現行判定在「票於故障當 tick 開頭到期」時可能假通過，所以新增故障瞬間持票見證。建議在助載那一刻同步把 5×5 載到 FULL（不消耗 tick、取票前不碰 B），並以負控 A/B 對照驗證機制。其他冷 B 案依賴同一條件，本輪不處理、列入觀察。

## 最新：T5-0 D1受限診斷授權（2026-11-20）

（已被上方「Codex 交回，Claude 接手」節接續。）

- **D1已依授權完成即停（2026-11-20台北15:55:46 CI終局）。** 原席先審39023E…A912E新增C0/I0/M0，唯一Windows1GT+原入口6JUnit通過；唯一同SHA新CI44693421016、push/head e3ce6e174be4438322d9e21f51762a6eb259ae7a、attempt1、completed/success，10/10jobs均completed/success、無failed step。terminal原件S/ci-44693421016-d1-e3ce/terminal-run.json SHA AFD77297AACE03BBCEF05F8E832D969F7459197DE182110A6A3D9F890F6E9177，root已逐項核10項與pin，原UTC07:55:46/台北15:55:46；controller先前元資料時間顯示轉換丟UTC kind的誤差已核正，tick觀測未受影響，補證controller-terminal-readback.json。
- 最終結果S/t50-diagnostic-once-results.md SHA D3048F5806310F8E4AECC939DED8A4C2C3A3626A821F45B3A57F9624162D7BB9；雙平台CI原件判讀S/t50-diagnostic-once-ci.md SHA998F5E15BD04C553305252DB7AA31C079CD11B387DB628BB863DF62DE7907017。Win CI Main53/57、Ubuntu53/53，exact/zero failure/error/skip；兩平台raw及inputs/summary hash吻合。CIWin原ZIP24,480,595/f35d1…ff783、Ubuntu22,091,848/e3e478…f6a32已實讀匹配，獨立zip-readback.json補足原CI稿當時Win下載未完的歷史狀態。舊A7/390/5DB/998稿皆保bytes，不覆寫舊FAIL。
- 三組有效對照均marker1/captured/diagnosticErrorCount0：本機Win助載47→entities/checkpoint138（91tick），fault138距deadline246尚108；CIWin1286→1382（96tick），fault距1485尚103；CIUbuntu1557→1724（167tick），fault距1756尚32。三組真fault當下同一exact活票closing=false、前一END樣本存在且持票，該tick END活票無、原首票closing=true，退避200。沒有hook unknown。這證明本次block ready與entity/checkpoint前置可相隔許多tick；不同refs/world/fixture及各一次，不能歸因OS或回填舊Ubuntu現場。舊19e9失敗未重現，舊期限/當刻票/前END樣本是否缺失仍未知，先到期假說仍不能定案。
- 本輪新增只有兩testmod觀測/README及授權狀態紀錄，production、行為、原斷言、時限、存檔格式/FamilyCapabilityVersion0不變。Win1/1、CI1/1已用，沒有新full/mutation/額外Main/第二CI，沒有rerun。依「完成即停」停止所有新增修正/驗證/push，不因綠CI把T5-0標CLOSED或進T5；3/3、共同cold2/3、靜態記錄器事件1及舊FAIL/Minor/未驗保持。三份tracked狀態文件為未提交結果同步，不為文件再push觸發額外CI。後續若要穩定持票故障前提或變更witness設計，另待使用者裁示；此輪不落實方案。

- D1唯一新SHA CI已觸發：root正常push e3ce6e174be4438322d9e21f51762a6eb259ae7a，S/push-e3ce-d1-readback.txt讀回feature同pin、main2255027/m6peeled535不變。新CI44693421016（push/head同pin，created07:13:50Z）in_progress；首次發現尚空的S/ci-d1-e3ce-first.json保留，實際發現快照S/ci-d1-e3ce-discovery-1795158951641.json。Win1/1、CI1/1已用，不再改碼/新驗證/重跑/額外push；結果只同步工作區文件，整run終局後收原件並停等使用者。這是診斷，不因任何PASS把舊Ubuntu原判或3/3洗成修復/T50結案。

- D1原席靜態審查已通過，新增C0/I0/M0，新S/t50-diagnostic-once-review.md SHA39023E120696F5192C5E7F621B7A4A4ACA44B12E3FCCAB1B9554FA38882A912E，先審才runtime；舊Minor與FAIL不清除。乾淨候選e3ce6e174be4438322d9e21f51762a6eb259ae7a經root明示後，僅一次Windows定向（無ExpectedFailure/mutation參數），原件S/c-t50-d1-door-c0808e9f。gate/outer0、唯一GT PASS/XML363C35147B46A2D40DAAB3CFE418931481A66B88B3BFC988CBA3E7DF423A535E，入口原JUnit6/6、零fail/error/skip；沒有Main全57/Scale/full/native/mutation或第二次本機。新報告S/t50-diagnostic-once-windows.md SHA5DB05C0BC9CAD9B2B2522DEF1EB635990CEF7CFBFA1D635CDA3970918A33B38B，A7實作報告/390review不append。
- root直接讀回latest.log第140行唯一M7_T50_DOOR_DIAG_V1，SHA2CDBCED84E0374E1893167D2F45EF67BEEE508161FAAF09C33700D16DB90D5FD，status=captured/diagnosticErrorCount=0。SID13dae46f…首正式票END46、deadline246，助載47只blocks true、entities/checkpoint false；137前END有樣本且持同票、entities false。138真OPEN故障當下same first live票closing=false、2refs，entities/checkpoint首次觀察true；138 END owner活票移除、首票closing=true，退避200/retryAt338。這是Win成功對照，只有此輪觀測可證，在舊Ubuntu仍缺同刻資料，不能藉PASS宣告修復或推定平台/production根因。
- 本機Windows預算1/1已用，下一步僅相同e3ce的新SHA既有CI一次，原正式判定保持，舊CI不rerun。結果文件同步但不另push觸發第二CI，完成即停等使用者，不恢復T5。production/原斷言/時限/資料格式皆不變。

- D1觀測實作ready，未runtime：僅兩testmod檔與README一句（M7HomeEntryTicketGameTests +175/0、DoorWriteFault +4/0、README +1/0），原Boolean witness/斷言、observer phase、故障匹配/hit計數/回false、助載、票、期限不變。額外DoorWriteFault檔由controller核為真OPEN故障命中瞬間的必要scope-local observer，其他caller無observer仍原路徑，非新user玩法裁示。新報告S/t50-diagnostic-once-report.md SHA A7A69126E23BED1E26C2E900A8067CA18540F0F8104EAE38665143351585D8F0，來源hash分別F4854EF1EB7C4085081A8C5E64BBAEF582CBF778F7CF1CD49060956704FBF562、A6379ABC59DC9C48112AE2ABFBD18CF239B8D17AAEF76AA595E0CF311E587EED、6A02E7360B2F0539F1107FF6B291D7B59676A7805FA813B9A578F455699619AC。
- 編譯原件S/c-t50-d1-compile-8dba38ce，compileTestmodJava --rerun-tasks --console=plain/BUILD SUCCESSFUL、Gradle及outer0，log SHA88296B6B96DAFCC4B52E975AF9341D203FEC0E64DA47F9ADB80FF8CC413FD23A。verified.json原sourceUnchangedDuringCompile=false保留，因helper變數matches被PowerShell自動Matches覆寫；獨立只讀source-readback.json SH A956B6F9A420F0FDE67208C9BCDA711DDC35DB8EA5C579C01EECDBD2359676EC9之allSame=true，root逐檔讀回確三份前後hash相同，沒有重編/改判原件。列D1靜態記錄器事件1，不算Minecraft診斷已執行。
- 讀取邊界交原席核：避開getWorldChunk的cache寫入與ready test hook的hits副作用；只讀現有cache/holder/future狀態，hook存在則effective ready未知。診斷資料獨立、primitive/string快照、scope終局emitOnce；沒有新PASS判定。原席先審C/I0，才准報告所列一次既有targeted driver之單門batch，其他門檻不加跑；目前Win/新CI使用數0。

使用者透過本chat選項回覆明確授權一輪診斷。題目原文：

> 同門夾具已按保守計數達 3/3 止損。是否只授權補活票／租期／ready 與樣本觀測，原席審查後跑一次 Windows 定向＋新 SHA 的既有 CI，完成即停？不改 production、行為、斷言或時限，舊 CI 不 rerun。

所選選項原文：

> 允許一次受限診斷（建議）

- 原文副本S/user-t50-diagnostic-authorization-20261120.md。這只開D1觀測窗口，原3/3與其他失敗計數不重置，不授權正式修正或T5～T8；即使本輪偶然通過，也不宣告修復或T5-0結案。
- 起跑實核：HEAD／遠端19e9，source仍F8E22788…00E8A9、production HomeClosure仍5EBDE…386AD，三份狀態文件dirty保留。舊CI44680282020 attempt1實際completed/failure，10jobs=9success/UbuntuM7唯一failure，終局原件S/ci-44680282020-19e9/terminal-run.json SHA D437F89FC989AF832D99D035A7B92465DA00692DE773D51170686ECA658553BC，沒有rerun；先前in_progress快照保為歷史。
- controller執行細節（非使用者原文）：沿既有門故障callback與END採樣收有界記憶體事件、終局一次輸出，診斷欄位獨立於Boolean PASS-witness，不改採樣語義/故障匹配/排程/助載/票。只讀狀態，不調用會載入、consume expiry、save或修改owner的getter；缺值記unknown，不用觀測改變原判定。先編譯靜態、原席核C/I0，再一次Windows單門案例，依新SHA既有全套CI收雙平台資料。既有assert的PASS/FAIL各保原判；若觀測有效但原assert失敗，仍可依此次明確授權收CI資料，不補修、不重跑。診斷自身新工具錯誤使資料無效時立即停報。
- 只推本輪一個診斷候選觸發一次新CI，結果同步後保留工作區狀態更新，不為結果文件另push啟第二CI。完整本機full、mutation、Scale、額外Main定向、Ubuntu程序管理與workflow/inventory變更都不排入D1。新報告S/t50-diagnostic-once-report.md；brief S/t50-diagnostic-once-brief.md；完成CI原件判讀後立即停回報，不繼續一般實作。

## 最新：Codex 接手 T5-0（2026-11-20，進行中）

- **止損交回（2026-11-20）：T5-0仍OPEN，停止改碼／新驗證／push，等使用者裁示。** 新CI44680282020 attempt1/head19e9的Ubuntu Main同門case第三次非預期FAIL，WindowsM7jobsuccess。原Ubuntu zip1,867,064 bytes SHA B2CD402F73583F2C8DC6B41125ED0A6DB8EC1552C0A9AFBA5156BA3B447C0530與metadata相符；原件 `S/ci-44680282020-19e9/ubuntu-main-failure-9070c86327e44e549a49e33e7c0404b9/raw/gametest.xml`，SHA5DEB1DC35BA1C9EBBFF03B6E9A2D6BAB2F35B93A186A936D128D304AC5C499D2，53案1FAIL。門fault/WARN/nexttick/exact票後助載/200退避/失敗END與整段無票均真，唯一前一END持票false、leaseTicks空。前一END無樣本也會false，缺門OPEN hook當下活票/租期/deadline與逐tick readiness，無法判合法同tick或已到期，更不能宣稱production defect。來源支持較像首票先到期、B blocks因fixture仍可讀、checkpoint/entities稍後ready而無票進門故障，但僅有條件推論。
- 同門工具保守計數3/3：m7-dev1門已開、6db CI租期到期被誤作門fault、19e9 CI持票前置未證，三原因皆保原判；這是controller先前已公開的同fixture追蹤，不冒稱三輪同底層根因。共用冷載維持2/3、不把推論再加，oracle4ee1、fullLauncher2/3、特殊fixture及AccessDenied額度不重置。只讀止損報告 `S/t5-0-ci-19e9-stop-analysis.md` SHA080D78C77BAEBD3D0DCC937E51F4E72961638D2A275737F048DE47FC57FC3340；待裁示的單輪補觀測方案在 `S/t5-0-stop-next-step-proposal.md`，尚未授權/未實作。當前CI停點快照 `S/ci-44680282020-19e9/stop-snapshot-1795153373751.json` 為in_progress、7success/UbuntuM7failure/Windows-native與m6-native仍跑，非run終局；現有CI不cancel、不rerun，下一接手先讀終局。原6db full/26採計、19e9本機focused/mutation與原review仍各自成立，但不足結案。
- 交回Git：HEAD／遠端feature19e9，main2255027/m6peeled535未動；source/README/時限/資料格式未再變，AGENTS／本note／handoff三份tracked狀態文件有未提交更新，不為止損文件push而另觸發CI。ignored新版結案草稿 `S/t5-0-closeout-docs-draft-fix2.md` SHAC51DE0F0C0CC948C64D57CCE74EFAAA2DF84C1F9B5EC5FEBBB1AA0830B097295保留待填，禁止照稿宣稱CLOSED；T5本體至T8及人工smoke/main/tag/退役都未啟動。

- 原6db CI實際終局：44674890854 attempt1、head6db、completed/failure、10jobs=9success/1failure，唯一Windows M7 job/Main步驟。`S/ci-44674890854-6db/terminal-run.json` SHA F67FFC2A1103320F704B1FF32F74515F4C7FE95CC4CC189553F91EF49CFADBE7，所有原件與Fail保、不rerun。原run完成後，root依既有push授權正常推fix2候選 `19e9aac8abff65123b0acc2d8e6306ba4b71b486` 並ls-remote讀回（`S/push-19e9-readback.txt`），main2255027/m6peeled535不變。新CI44680282020 created05:19:38Z、push/head19e9目前in_progress；首次查詢尚空陣列的`S/ci-19e9-first.json`保留，實際run發現快照`S/ci-19e9-discovery-1795152079732.json`。這是實質測試修正的新pin首次CI，不是舊run rerun；仍未closed，不將6db full冒成19e9新full，C0/I0/M1、2/3止損與未驗限制保留。

- fix2原席窄複審已通過：`6db3ce6..19e9aac`，spec/quality通過、C0/I0/M1；新檔 `S/t5-0-ci-fix2-review.md` SHA E9E4A33C3AF09AB37AD8D57A75AB7E6474C055579A7BF5972F21EAC8EADFC609，原79DA不改。唯一Minor：fixture持續保持B可用後，RemovalWatch僅記曾經移除，未記首次checked移除server tick，故不能單靠最後查詢排除未來owner在retryAt前提早收尾；本輪/backoff後/close仍1tick、未觀察到production提早。本輪門故障分類/真釋票mutation目的成立，root列殘留交T8整分支審查，不為Minor重開驗證輪或改時限。原CI尚未整體終局（Windows-native已進M6）、不rerun，源候選19e9待其終局才push新pin首次CI，Task5本體不啟。

- fix2定向終局：乾淨test候選 `19e9aac8abff65123b0acc2d8e6306ba4b71b486`，窄門故障＋budget2/2 GREEN（XML902E74790EFE573978778DC6E254AA4698C66F814428CBCE0BD710C23CE0A416）；唯一backOff釋票單點變異恰一target FAIL，門fault/WARN/下一tick/正式票後助載/先持票/退避200皆真、只有同tick與整段無票false（XML51B9E4DB3668DF599E70F7BAE20381945B16AC5E2417279CB6FEDFD558D16178），before/restored/即時HomeClosure皆5EBDE…386AD、exact=true，沒有非預期。一次正式Main53JUnit/57GT、zero failure/error/skip、VerifyOnly+SelfTest15與outer皆0；原件 `S/c-t50-f2-covering-66c6fd58`，XML2111D0210AAB4A57AF6B66CB647734F6A3837D08591908F011C55982D0AF1415、inputs1532BD6E42183C5F240DE5EB255DF67B429EAE9C5D7850AC0E64FB64B20189A3，root已核。新報告 `S/t5-0-ci-fix2-report.md` 最終SHA86A1A76B3ECF29A6A5FB503504FC4B97D720EEB3329C0F847D37B1C13C31B884，原191379與79DA報告不改。未新增local full/Scale/native/Ubuntu，清楚採純測試豁免，不把6db full寫成19e9。原席窄審6db..19e9在途，候選尚未push，原run等最後Windows-native終局，舊FAIL與計數照留。

- fix2靜態與編譯：只改 `M7HomeEntryTicketGameTests.java`、verification README。共用既有budget的exact首票identity predicate；門案在首正式CLOSE票經END實際可見、sameSID/exactB/正確refs後才hold fixture chunks；門fault及同SID WARN、下一tick backoff共同成立才驗退避，expiry/其他原因具名FAIL。保全部舊斷言/期限，補真正收據與checked移除核對，類內10GT宣告及inventory不變，獨立cold路徑不變。`S/c-t50-f2-compile-7ff76bfa` 在6db＋明列5dirty下compileTestmodJava --rerun-tasks成功/exit0，log SHA92C415B24ACB3B8A456C48149218BD5182AEAF988ADAB4D3D0AF22AFD7D73E35，前後source SHA F8E227885BB9B517B2AFE21EC1F2D39AD9D9B08B96802625EBC038BEF600E8A9。尚未runtime，不把編譯當GREEN；production HomeClosure仍5EBDE…386AD。root先固定新候選再准窄GREEN/單點mutation/一次Main與原席審查，原CI不rerun。

- CI失敗判因與fix2處置：`S/t5-0-ci-6db-investigation.md` SHA `747964CA7285EEB374E2327B9A70196E6AD5758A2AB85F99A4A88E86DCB32702`，root已讀原件。唯一門故障案例的故障注入未命中；同SID1699取CLOSE票、1899到期進200退避，原predicate取第一個任何原因的backoff後拆fault；四項正式票持有/同tick釋/整段無票/退避witness真，doorWriteFaultHit與oneDoorWarn假。原6db CI仍保FAIL，不歸production錯或已知偶發。ZIP4,171,874 bytes SHA DB7E03AC1BD9873E2E04CA864DC33E5943414941978D9F011B282EC4B1FFB21C與上游digest相符，XML A7BCCC111FF4E89AD750905110E9801229B31AC46A5517A85081EBE6C367E361，57/1/0/0。
- root採保守止損計數：同門案例非預期2次（dev1門已開與本次expiry原因不同）；共用冷載前提把8f budget與本次合併也是2/3，重疊事件不雙加。原預期backOff變異及其連帶保原判、不冒充乾淨case失敗；4ee oracle簽章1、fullLauncher2/3及兩特殊fixture規則照留。下一次相關非預期即停報，不疊修。
- root技術決定（不是新user裁示）：准test-only fix2，只在END實際見同SID/exactB/world/entry/chunks/CLOSE正式票後才用fixture支援B ready；門fault/WARN及tick-end退避同時核，其他退避具名FAIL，不改400/260/600/200與600/90，不移除任何斷言/案例，不碰獨立cold-load GT及native。保原驗證責任，將無關冷載前提留其獨立案例，門案驗正式釋票/退避；不變production、不新增更嚴框架。原6db production19d6完整回歸保證據，依user只測試改動明示豁免不新跑local full；本案仍重驗既有backOff單點殺手與逐位元還原，以免fixture助載代替釋票證明，最後一次M7Main focused/VerifyOnly+SelfTest。原席窄審C/I0後新test pin首次CI，原run終局前不push、不rerun舊CI。Task5本體仍未啟。

- CI在途已發現Windows Main步驟失敗：run44674890854 attempt1/head6db，job136084261534「執行 M7 獨立 GameTest」started04:33:08Z/completed04:39:57Z、conclusion failure。整job仍執行Scale，尚未有run終局；原始job/step快照 `S/ci-44674890854-6db/poll-1795149630071.json`。root停止結案推進，原因待raw log/artifact，不推論AccessDenied或M5熔岩例外。GitHub CLI在整run in_progress拒絕logs；job原生日誌目前BlobNotFound，Windows artifact尚未上傳，未重試gate/CI。Ubuntu M7與restart/receipt分片已有success，不能抵Windows Main失敗。本機完整PASS/26採計與原碼審查各自保留，T5-0仍OPEN，T5本體未啟。

- 12:30正常push `6db3ce681bb9a960df7c34a126444c3c843795bd`，遠端讀回feature同pin、main2255027、m6 peeled53573fc。`S/push-6db-readback.txt` 保存原件。CI `44674890854`（createdAt04:30:44Z、push event、head6db）目前in_progress，`S/ci-6db-first.json`；尚未讀CI終局、不rerun、不提前closed。只有GradleDaemon PID9812仍存活，無本趟server owner。full結果同步之兩份tracked狀態文件目前為未提交docs，HEAD仍6db供full/CI同pin；之後依實際CI終局完成結案docs與原席文件核對。

- 完整回歸已於12:27:36自然 `END ALL exit=0 HEAD=6db3ce681bb9a960df7c34a126444c3c843795bd`，holder `S/c-t50-full-434bb30749704defa932f6165b595858` outer0/endAllLine1，真正根 `V/run-all-bc6ed3352e204d2d87167eb1b2fecbfa`。summary SHA `3B8EDB79B5C363C41C8646E148D7D1CC1FCF62E6E62DCC3636179065B9B43780`。root逐項採計26全true，錯舊46/92/376恰5項false，原件 `S/c-t50-accept-8dd3f3a05704471a9778ead6de9c596f`；helper不以shell exit區分負控，已讀JSON及ACCEPT_FAILS。M7 Main live/VerifyOnly53JUnit/57GT、Scale11、native47列94phase/404負控SelfTest2.2秒；summary再次唯讀重驗native行顯negative0，404取自正式native step的SelfTest輸出，不混為同次呼叫。M6 native9鏈27phase/479負控、M5collapse69、recovery25；全JUnit1199 failures/errors0、skipped2，root已核都是原有 `@DisabledOnOs(OS.WINDOWS)` 的NonWindowsPlayerCheckpointStoreTest，M7集合zero skip。沒有full重跑。依使用者本輪full→push→同SHA CI→結案docs順序，採計事實先同步工作區，HEAD仍6db3ce6供正常push/CI對齊；CI實際終局後再提交結案docs，不提前寫已push或CI全綠。

- fix1 原席窄複審終局：`df017d2..6849c84` 的 I-1／I-2／M-1／M-2 全為 ADDRESSED，新增阻擋 0，spec／quality 通過，C0／I0／M0。原 `S/t5-0-codex-review.md` 保首審並追加，現 SHA `79DA83C672C7E0D48720AE782DE024E6EBD2B2DA46D139D0619836A7782C76CB`，root 已讀回。原 FAIL 不改判，未驗真停服／新 RESTART_VERIFY 原生列／Ubuntu mutation 等限制續存。W10 producer 固定身分、非 reparse、7 個鎖可獨占、Java 0 已核，禁止重建或重套。接著以乾淨 HEAD、既有 UTF-8 holder、新 nonce 完整回歸與逐項採計；還沒有 full PASS／push／新 CI。

- fix1 定向終局：root 已核校正後具名 GREEN 2/2 與 `S/c-t50-f1-covering-e26a682d` 的 Main 53 JUnit／57 GT、零錯誤／失敗／skip，live 與 VerifyOnly 實際 PASS 行，SelfTest 15 全部合預期。XML `469A07D2275965E0BE16D7CFC6D43502D25860EB5E1E39AA081177E54EB81C4B`，inputs `F20B0528A872AEC82CE5E80DD3B6122697F35F90C55D23059CC5AB594AF1EF3D`；作者報告現 SHA `191379E71A3141D1774B3C29FE8057C92F6F6FFA657E30E6F891127F1B1863AB`。這些是 HEAD df017d2＋明列七份 dirty 的定向，不冒稱 clean pin。五個作者修正檔、三份 root 狀態文件整理後交原席窄複審；production 無新 diff、HomeClosure bytes 仍 `5EBDE4E8949707D576D49DD0E0CED1D27239ADE29FF5A07BC6CE2981E30386AD`。首個 oracle FAIL 與簽章1保留；尚未 full／新 CI，T5-0 OPEN。

- 首審終局：`c533193..df017d2` 一席 spec＋quality 皆需修正，C0／I2／M2；`S/t5-0-codex-review.md` SHA-256 `985FA51FDBACA2DE76D8907EC9CABEBC910FD35BA7F153B4FF3EB369F82A6FEA`。I-1 新／抽出 fixture 的 checked setup 只取 committed 布林，遺失底層 cause；I-2 predicate cleanup 先 close 後 hooks，close 例外會跳過 hooks。root 核實後交原 implementer fix round 1，限 testmod；同輪修 Minor 的負控執行前核上限與 README family 5.8 秒原件值。production 無新改動，兩個原 mutation 與 clean19d6 兩輪 focused 不改判。修正需具名控制、定向與原席窄複審 C/I0 後才 full；不是 T5-0 closed。原審查判定與未驗項保留。
- fix1 作者回報的控制原件：`S/c-t50-f1-red-8c11eada` 的 exact 2/2 具名 RED 分別是 checked cause 未記與 close 例外後 hooks 未跑，無 LEFTOVER；root 尚待完整讀回。`S/c-t50-f1-budget-red-da62cdc6` 證明舊版第 1001 次 Check 仍有副作用，`S/c-t50-f1-budget-green-39bcfaee` 則驗第 1000 次合法，第 1001 次在 Check 前以 BUDGET 拒絕、舊兩控制仍過。均為預期 RED／正負控制，非新 production FAIL；testmod 修正定向仍在途。
- root 之後已讀回上述 JSON／RED XML（SHA `CDF0495B8E343D5047A6ECBD979CAA27C1DCD7AEBE315550D9120438C2021114`）。首個 fix1 GREEN `S/c-t50-f1-green-4ee63593` 非預期 exit 1，XML `9D4C55F35B4E4264257A3D34AF8BDCA73CE6906617A954D7BFCB6D3180668406` 保 FAIL：I-2 PASS，I-1 logger 恰一筆 ERROR，原因鏈實為 java.lang.IllegalStateException → java.io.IOException；新 oracle 錯期待簡名 IOException。root 已核 raw log，准僅將 oracle 校為現有 formatter 的全名、拆 witness 並保精確原因／Throwable 身份，不改 logger、production 或門檻。新 nonce 再具名 GREEN，成功才原訂一次 covering；本新驗證 assertion 問題計數 1，未把原 FAIL 改成 RED 或 PASS。
- 校正後作者回報 `S/c-t50-f1-green-oracle-e61e408c`，同 HEAD df017d2＋明列七份 dirty，exact 2/2 PASS、exit 0，XML `D35E9966A1FB29D3B0C66DDAC1B24ED0810723302802313114B7C658939A2528`。單次 Main covering／VerifyOnly+SelfTest 在途；不是 full／CI 或本 task 已結案，root 待核原件後送原席窄複審。

- 定向終局：clean code pin `19d6e3b51bf255010568d7a8a6d900d9ff4f76bc`，兩輪 Windows Main 各 JUnit 53／GameTest 55，R1 Scale 11，R2 default 精確 5／M5 精確 2 均 PASS、zero skip；R1 Main／Scale VerifyOnly+SelfTest 15／13 例，含新增宣告 guard 正負例。原件 `S/c-t50-focus-1-83d2c8a9`、`S/c-t50-focus-2-87a29bf0`，詳細 inputs/XML SHA 見 `S/t5-0-codex-report.md`（SHA-256 `4F2557B06DD72D9485609C615666129B7CB6D198E9867CCE4CC124D57DAAD676`）。root 已核 pin、inputs 雜湊、兩輪 XML 55／零失敗與精確集合。budget 收尾 263／248 tick，未見 LEFTOVER_HOME；此輪未刻意注入第二次退避。T5-0 尚未獨審、full／新 CI；下一步獨立一席規格與品質審查 `c533193..候選`，不是結案。以下較早「focused 待」為歷史階段。

- 真user本輪原文開頭：「M7 由你接手實作（Claude 已收尾）。工作區 C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home，分支 codex/m7-return-home，交接 HEAD 8f3c977。」完整七點文字在S/user-codex-takeover-20261120.md。本輪先T5-0，再T5～T8，下列技術決定不是user新裁示。
- 接手實讀HEAD8f3c977fa72ca506e7289c84fc720ea2d8863ecc、tracked clean。CI44480505340 attempt1同head，10jobs自然終局9success/1failure；Windows M7 Main唯一失敗，原CI不rerun/不改判，不套M5熔岩例外。原件S/ci-44480505340-codex-0befa5f8a4734cff83d2016383ae9418（run.json/failed.log/artifact-Windows）。
- 唯讀分析S/t5-0-codex-ci-analysis.md（6A8A622E89DB1FE95EC3774DE5B425BB4914C65004FCA432A3266E66364D4FD5）：53GT唯一close_ticket_budget_exhausted_warns_and_retries、BUDGET_RETRY_NOT_CLOSED。額度滿/200退避/合時CLOSE_CONVERGE 2refs有原log；票200tick未ready，owner釋票進400退避（retryAt1299）。測試900結束、fixture rehold後1200已ready，仍早於1299，清理也失敗；同SID後來checked收尾。首次lease未逐tick採樣，底層延誤未知，不稱已證實AccessDenied/環境偶發/production載入缺陷。
- controller技術決定（非user裁示）：budget案保400觀察/200退避與租期、額度/WARN/無早取/sameSID/purpose/refs/門/busy/正式收據與移除斷言，僅合時正式CLOSE_CONVERGE已取得後由既有fixture一次補exact B ready。新witness須證助載在正式票之後；無票/錯purpose/提前不能靠助載過。獨立close_loads_unloaded_b_with_bounded_ticket與native restart-close-unloaded-b的獨力路徑不改；此budget案不再作native獨力cold-load證明。責任切分交獨立review，不預壓finding。
- 受限失敗清理決定：先保原failure/thresholds，僅本案owned、healthy、current=flushed、無pending/quarantine、checked CLOSE單SID，fixture rehold後可reset該SID runtime backoff再由正式owner收尾；原300上限/原錯照丟，不改正常退避、不直接刪journal/假收據、不泛改別SID或未確認列。
- B2b5-M1選owning scripts/verification/m7-ci-gametest.ps1納本輪清單，不另建平行JUnit scanner。第三production M-1/M-3與SessionRecoveryState:1027註解接續完整門檻。第9點onEntryStopped由controller留T6真caller/guard事件，真停服/RESTART_VERIFY真重啟/Ubuntu relatch mutation保待驗，不自增native rows，不提前T5本體。
- 當前僅接手/診斷與已授實作在途；原b0ea0f8/c749739未獨審/未full的事實保。整個T5-0審查從c533193起，C/I0後holder full END ALL0逐項採計，再正常push/新SHA required CI與結案docs。時限決定交user，不自行延長。

- 本輪第三批具名RED：HEAD8f3c977加測試/狀態dirty，src/main無變更。S/c-t50-red-ef21bd4f/gate-t50-red-008e9a88ba9b4cf9a55e507503e5a7e6 原XML 3案恰2FAIL，SHA7175B876011297BAF2147EEC89A817912C2A9C0DDD7EC46A3725308BFC80F670。restart_seal_with_root_stop_freezes_close_before_loading_b 以RESTART_ROOT_STOP_WRONG_TICKET失敗（B未載入、ROOT未凍、首票RESTART_VERIFY）；close_ticket_releases_at_full_with_invalid_entry 以CLOSE_FULL_INVALID_TICKET_RETAINED失敗（先有票、INVALID tick未釋/未退避/無精確WARN，checked CLOSE/busy保）。budget案PASS。這是真目標RED，不把先前-Batch靜態拒絕當RED；m7-ci平行候選未列本筆RED提交。

- 第三批GREEN：RED提交3ee0bef874fa11c0dc35eba46d55c1ba0eb0031e後才改production。HomeClosure.stepInGuard共用restartVerifies並排除stopReason，FULL且決定性非VALID於兩種票用途立即釋票/正確WARN退避；另涵蓋tick起初已INVALID而同SID仍有FULL活票的分支，UNKNOWN維持bounded，ERROR提示補B端點合法性。SessionRecoveryState:1027註解歸controller判讀，非user新增裁示。
- S/c-t50-green-b1aae90a 原5GT全PASS（兩新案/budget/原無stop RESTART_VERIFY/原NEVER_FULL），XML ACA04ADD0B58B103FBAC7E30CD7E5AEC25729F9AF924094DB8C8A9D870FFC380。兩direct單點：M1 XML171EC63AF9835839C074AA637BB9DC0AD45D7D72AA6C7594C3A9AF158913C6B6、M3 XMLE5F2DB34DD29DC392CAA1C724801ADBA4487553893D836FAC98B6BD4FE27B19E；每輪2案只有相應精準target FAIL、原守邊案PASS，原/還原HomeClosure SHA均5EBDE4E8949707D576D49DD0E0CED1D27239ADE29FF5A07BC6CE2981E30386AD，root讀回相符。
- B2b5-M1 owning runner與README已實作，S/c-t50-junit-scanner-38a949e8/static.json已驗真64宣告（Main53/Scale11）、public final及FQ/protected synchronized正例、unlisted class/unsupported FQ Parameterized/精確方法不符具名拒。正式新artifact的SelfTest/最終clean pin兩focused與獨審/full/新SHA CI均待；現階段不能寫全部門檻已過。新詳細報告S/t5-0-codex-report.md與原Claude報告分開保存。

## 最新：交給 Codex 接手（2026-11-19）

- 使用者 2026-11-19 原話：「可以準備收尾交給codex實作」。Claude 停止新的實作派工；交接入口是 [`docs/handoffs/2026-11-19-m7-codex-t5-handoff.md`](../handoffs/2026-11-19-m7-codex-t5-handoff.md)，`AGENTS.md`「目前接手入口」也指向它。
- T5-0（T5 Step 0，controller 從 T5 拆出的獨立段，controller 排序）：第一批 `b0ea0f8`（SelfTest 每次 1000 個＋120 秒，補 B 收尾票判定負控；新數單份完整 404、family 434、門態 454）與第二批 `c749739`（連坐缺口、fixture 寫入失敗記 cause、T4B 審查 M-2／M-7 的新案、CI 規模段時限）已 commit，**沒有經過獨立審查，也沒有跑完整回歸**；第二批定向只跑一輪。未做：B2b5-M1（要改 `m7-ci-gametest.ps1`，不在授權清單）、第三批 production（T4B 審查 M-1、M-3、`SessionRecoveryState.java:1027` 註解）。細節在交接檔第 3 節與 S `t5-0-report.md`。
- 之後的工作順序、使用者裁示、門檻與止損，以交接檔為準。

## 最新：T4B CLOSED（2026-11-19）

（已被上方「交給 Codex 接手」節接續。）

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`，V 指 `.superpowers/verification/`，兩者都 gitignored。本節的事實讀自 S 的 `STATUS.md`（2026-11-19 的 11:5x、09:3x、08:3x、06:4x、02:2x、02:1x、02:0x、01:4x 各段與 2026-11-18 21:0x 段）、`t4b-report.md`、`t4b-review.md`、`t4b-closeout-docs-review.md`、`t4b-full-m5-fail-investigation.md`、`user-t4b-full-rerun-ruling-20261119.md`、`user-m7-selftest-cap-ruling-20261119.md`、`m7-selftest-margin-analysis.md`、`t4b-accept-check/`、`t4b-step0/mutation/mutation-log.txt`、`ci-44362426613-run.json`、兩個 holder 目錄，V 的 `run-all-902b2e397d67468b80a9e2b6ab61bb1a`、`run-all-b90f443e98784cbbb1c4903cb292d90c` 與 `t4b-m5-inv-ba90ecab264a4e7cb83e5a04d077bc71`，以及 git 與 `gh run view`（含 `--log`）的唯讀查詢。

- **狀態**：T4B CLOSED。
  - code pin：production 的最後一筆是 `0f04037`。之後的 `e3eea5a` 只改 testmod：`git diff --stat 0f04037 e3eea5a` 只列 `src/testmod/java/dev/quantumchamber/gametest/M7HomeEntryTicketGameTests.java`（+16／−5）。`git ls-remote origin` 讀回 `codex/m7-return-home` 是 `e3eea5a18e4baa4f00eef5cb41b2706edfc86c01`。
  - commit 範圍 `70ed3d9..e3eea5a`，共五筆（見下表）。
  - 完整回歸：第一次 `run-all-902b2e39…` 在 M5 GameTest FAIL，原判保留；依使用者 2026-11-19 06:3x 裁示換新 nonce 重跑一次，`run-all-b90f443e…` 自然 `END ALL exit=0`，採計條件 26 項全 PASS。兩次的 HEAD 都是 `e3eea5a`。
  - 審查：一席（spec＋quality 同席）spec ✅、quality Approved，C0／I0／M7。
  - CI：`e3eea5a` 的 44362426613 attempt 1 十個 job 全部 success，與完整回歸同一個 SHA。沒有動用 M5 熔岩偶發的讀法。
  - 前一筆 docs commit `70ed3d9`（T4A 結案 docs）的 CI 44303845565 attempt 1 十個 job 全部 success（S `STATUS.md` 2026-11-18 21:0x 段）。
  - SelfTest 負控上限：使用者 2026-11-19 11:5x 選「上限改 1000 個＋時限 120 秒（建議）」，實作排在 T5 Step 0 第一項（見下方「SelfTest 負控上限的使用者裁示」）。
  - T4B 結案不代表 M7 完成。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

### commit

| commit | 內容 |
| --- | --- |
| `71d5b90` | Step 0：`OriginDoors` 的 Javadoc 與註解、`ChamberDoorService` 的可見性、`SuperpositionSessionManager` 的 Javadoc、`M7RearmGameTests`／`M7SealReleaseGameTests` 新案、inventory、`scripts/verification/README.md` |
| `8e12954` | test：RED 兩案（`src/main` 不變）；testmod 不能編譯 |
| `6c6051f` | test：RED 編譯修正（未 checked 觀察窗口的 `until` 補上逾時回呼，+5／−1） |
| `0f04037` | T4B 本體：production、測試、原生列、`run-all.ps1`／`build.yml`／README |
| `e3eea5a` | test：每個 tick 結束時另核 CLOSE 未 checked 時不持 B 收尾票 |

- 全部逐檔 stage（S `t4b-report.md` 末節）。`70ed3d9..e3eea5a` 的改動檔都在 plan Task 4B「新增／修改」與派工簡報的 Step 0 授權範圍內；plan 列的 `M7HomeProbe.java` 與 Step 0 授權的 `M7RearmLatchTest.java` 沒有改（審查第 2 節「清單外檔案：無」）。
- `8e12954` 不能編譯而且已 push，bisect 會停在不能建置的 commit（審查 M-6）。Step 0 commit 的 type 寫 `test(m7)`，但含 production 的 Javadoc 與 M-5 的可見性調整（審查 M-6）。審查認為這些都已揭露，建議在 note 寫明、不改歷史。
- 首次 push 遇到連不上 github.com:443（兩次），稍後重試成功（S `STATUS.md` 01:4x 段）。

### Step 0（`71d5b90`）

出處：S `t4a-v16-review.md` 的 M-1～M-8、S `t4a-v16-fix1-review.md` 的 M1～M3，以及下方「最新：T4A CLOSED（2026-11-18）」節「殘留交 T4B Step 0」的 12 點。審查第 1 節逐項判定都是 ✅。

| 項 | 處置 |
| --- | --- |
| M-1、M-6 | `OriginDoors.claimsDoor` 的 Javadoc 改寫成 3(a) 的可達性不變量，標明是 spec G 節第 7 點的 controller 讀法；`OriginDoors.door` 的 Javadoc 改為 `sessionAt` 只讀記憶體內的 journal record 與 `ChamberRegistryState`。 |
| M-2 | 補設 latch 那行加註解，另加具名 GameTest `M7RearmGameTests.close_after_terminal_relatches_before_system_close`（兩平台）。變異 S0-A（拿掉補設）與 S0-B（移到 `applyTarget` 之後）都以 `REARM_LATCH_LOST` 失敗。Windows 以出發殺掉；變異在 Ubuntu 分支會不會被殺只有讀碼推論。 |
| M-3 | 新 GameTest `M7SealReleaseGameTests.door_authority_requires_origin_and_not_destroyed`：以反射把 registry 同一筆改成 destroyed、再改成 PROJECTION，都不排入重新套用。S0-C（拿掉 ORIGIN 條件）以 `projectionRowIsNotTheDoorAuthority=false`、S0-D（拿掉 destroyed 條件）以 `destroyedRowIsNotTheDoorAuthority=false` 失敗。 |
| M-4 | `protectedNow` 與 HOME 對照改成在試拆之前以具名錯因立即丟出。只驗了 GREEN 通過，沒有以 T4A 的 M8／M9／M11 實跑新錯因。 |
| M-5 | `ChamberDoorService.toggle(…, Toggled)` 多載與 `Toggled` 介面降為 package-private；唯一呼叫端 `WorldChamberDoorAdapter` 在同一個 package。 |
| M-7 | 只評估、不改行為，結論寫進 `SuperpositionSessionManager.permanentlySealedOnly` 的 Javadoc：能留下失效暫占記號的只有同 SID 的 HOME 列，HOME 永久封存的產生點屬 T6，目前不可達、屬 fail-closed。 |
| M-8 | `rebuilt_chamber_door_ignores_released_seal_after_restart_reapply` 補斷言：開服提醒恰一則、含「解除佔地：」與「封存紀錄保留在 journal」，不符時以獨立錯因 `PERMANENT_RELEASE_HINT_MISSING` 失敗。舊斷言不動。 |
| fix1 M1～M3 | `CheckpointMemo` 記原值、清成 null、一律期待恰 1 則、收尾還原；已出發分支補 `departsOnlyOnCheckpointPlatform`；`capture` 失敗時先 `releaseAfter`，Javadoc 補「已恢復通過」INFO。 |
| 3(a) 加固 | 不做。自然路徑造不出「開門類待套用門套到原地重建的新艙」，只能以反射造不可達狀態，與 T4A 審查 3(b) 對 M10 的判定同理；維持 spec G 節第 7 點的讀法。審查判定理由成立。 |

- 證據：開發中定向 root S `t4b-step0/gate-t4b-step0-dev1-ba12f39c…`（8 個 GameTest 全過，JUnit `M7RearmLatchTest` 3/3）；變異 S `t4b-step0/mutation/{S0-A,S0-B,S0-C,S0-D}/`，pin `71d5b90` 的 `OriginDoors.java` SHA-256 記在 `pin-hashes-71d5b90.txt`。`mutation-log.txt` 只有三行：`S0-A restored OK`、`S0-B restored OK`、`S0-C restored OK (via S0-D apply); S0-D restored OK`。也就是 S0-A、S0-B、S0-D 還原後 `sha256sum -c` OK；S0-C 沒有單獨還原，直接改成 S0-D，中間沒有單獨核 SHA，最終還原 SHA 與 pin 相同（審查 M-6）。乾淨 pin 的證據併在下方「focused」。
- Step 0 commit 同步在 `m7-inventory.json` 加了這兩個 GameTest（exact 集合由 inventory 驅動）。

### RED

- `8e12954` 的 testmod 不能編譯（未 checked 窗口的 `until` 少了逾時回呼），原 root S `t4b-red/gate-t4b-red-fb33909da89d47d78a15854de7d0e0d4` 保留；它是編譯失敗，不是具名 RED。`6c6051f` 只補回呼（test-only），implementer 記為一次自己的程式錯。
- 具名 RED 取自 `6c6051f`。`71d5b90..6c6051f` 沒有 `src/main` 變更；`71d5b90` 對 `70ed3d9` 的 `src/main` 只有 Javadoc、註解與 M-5 的可見性，審查判定兩個 RED 都代表現行 production（審查第 2 節）。
- GameTest：root S `t4b-red/gate-t4b-red-gt-60a12839e7c7471f930731e296edd0cf`（記有 `quantumchamber.m7game.head=6c6051f…`），XML SHA-256 `c56b43ea…2e78`。`close_loads_unloaded_b_with_bounded_ticket` 以 `CLOSE_PENDING_WITHOUT_B_TICKET` 失敗：CLOSE 已 checked、B 未載入時 400 tick 內沒有收尾，`row=current=CLOSE flushed=CLOSE rollForward=false 票=[]`。未 checked 窗口的樣本是 `current=CLOSE flushed=NONE rollForward=true`，前提成立。這個 root 的 thresholds 是重新保持 B 之後才記的（所以顯示 VALID），GREEN 已改成先記再保持（報告偏離 3、審查 M-6）。
- JUnit：root S `t4b-red/gate-t4b-red2-3a29c247064747ca97c4f7eaeef62d55`，`entryAndRootSameXzDifferentWorldCountSeparately` 以 `A 與 B 同 XZ 異世界各記一格 ==> expected: <2> but was: <1>` 失敗，XML SHA-256 `d04992c1…e7ee`。這個 root 的 `execution/execution.json` 記有 `-Pm7Head=6c6051f1d1f4ec1ff661fa0e0e8889e4a7dd522a`（`gates.ps1:480` 以 `git rev-parse HEAD` 取得；GameTest root 的 `quantumchamber.m7game.head` 也是由同一個屬性帶入，`build.gradle:188`），所以兩個 RED root 記 HEAD 的證據等級相同，都沒有記工作樹是否乾淨；root 的啟動時間 21:10:05 也晚於 `6c6051f` 的 21:09:57。審查 M-6 寫「JUnit RED root 沒記 HEAD」，與原件不符（docs 審查 I-1）。RED 版以 T4 的 API（兩個 SID 同 chunk long）表達 A 與 B，GREEN 版改傳 world＋chunk，斷言相同。
- JUnit 失敗時 Gradle 不跑 GameTest，所以兩個 RED 分兩個 root。原生新列沒有在 RED commit 上跑（plan :531 的 RED 只有上述兩案）。

### GREEN

- root S `t4b-green/gate-t4b-green-gt-5b43e970d3a54273bfe8aaa8e828ae81`（pin `e3eea5a`，head 已記）：6 個新 GameTest 全過（XML `3a93d704…aac`），JUnit `M7HomeCoverageTest` 4/4。
- 新 GameTest 都在 `M7HomeEntryTicketGameTests`，batch 以 `m7_home_entry_ticket_` 開頭，兩平台都跑：
  1. `close_loads_unloaded_b_with_bounded_ticket`（RED 案）：收尾只用過一份 `CLOSE_CONVERGE` 票，`refs` 等於 B 的 homeChunks 數；未 checked 期間在 test callback 與每個 tick 結束時都沒有票；checked 移除的那個 tick 結束時已沒有票；B 門 OPEN/NONE、收據 ROOT_DISABLED、解除 busy。
  2. `close_ticket_never_full_releases_and_backs_off`：以反射把 `HomeEntryTickets.backend` 換成「不加票、永遠不 FULL」的 Proxy。
  3. `close_ticket_budget_exhausted_warns_and_retries`：以 64 份遠處的測試 reservation 佔滿。
  4. `close_ticket_released_on_reload_and_stop`：`/reload` 沿 M6／T4A 直接觸發 Fabric START／END_DATA_PACK_RELOAD；停服以反射呼叫票 owner 的 `closeAll(server, true)` 再還原 `stopping`，不是真的 SERVER_STOPPING（報告偏離 6）。
  5. `restart_verify_ticket_loads_b_for_unreadable_seal`：READY→SEALED(AUTHORITY_UNREADABLE) 經正式 journal 入口；以反射把 SID 加進 `HomeClosure` 本 instance 的開服集合，以 RESTART_VERIFY 票載入 B、RESTART_CLOSED 收尾。
  6. `close_ticket_releases_the_root_ticket_first`：plan 沒有列，為了殺變異「不先釋放 A 票」而加（報告偏離 7）。COLLAPSING 列持有正式 `HOME_PREPARE_PASSAGE` A 票時 checked latch 停止原因，下一個 tick 必須已有 B 票、沒有 A 票、沒有「取不到」WARN。
- `e3eea5a` 只改 testmod：把 `RemovalWatch` 提前到凍結之前，並加每個 tick 結束時的 `leaseWithoutCheckedClose` 觀察，用來殺「CLOSE 未 checked 就取票」（只存在一個 tick 的票只有 tick 結束時的觀察看得到）。審查 4(c) 判定這個案例與 `e3eea5a` 都恰當。
- B 本來就載入時不經票路徑，行為與 T4 相同；既有 39 列 close 矩陣（B 由測試票保持）的行為不變，由完整回歸的 47 列 COMPLETE 與 CI 的四片確認（見下方）。
- Ubuntu：本機沒有 Ubuntu。CI 44362426613 的 Ubuntu `m7-validation` 讀回 `M7_VERIFIED PASS platform=Ubuntu junit=53 gametest=47`（launched True、False 各一行），exact 集合由 inventory 驅動；T4B 的 6 案與 Step 0 的 2 案都是兩平台案。

### mutation

pin `e3eea5a`，root S `t4b-mutation/M<n>/`；`pin-hashes-e3eea5a.txt` 記 `HomeClosure.java`、`HomeEntryTickets.java`、`SlotAllocator.java` 三檔的 SHA-256，審查核過與 `git show e3eea5a:` 的 blob 相同。`mutation-log.txt` 六次都是 `restored OK`，每次還原後 `git status` 乾淨。

| # | plan :541 的變異 | 殺手（具名） |
| --- | --- | --- |
| M1 | coverage key 拿掉 world | JUnit `entryAndRootSameXzDifferentWorldCountSeparately`（expected 2 but was 1） |
| M2 | 退避期間持票 | `close_ticket_never_full_releases_and_backs_off`：`NEVER_FULL_NOT_RELEASED` |
| M3 | CLOSE 未 checked 就取票 | `close_loads_unloaded_b_with_bounded_ticket`：`noTicketWithoutACheckedCloseAtAnyTickEnd=false` |
| M4 | 不先釋放 A 票 | `close_ticket_releases_the_root_ticket_first`：`entryTicketTakenInTheSameTick=false`、`noUnavailableWarn=false` |
| M5 | 逾時不記 WARN | `close_ticket_never_full_releases_and_backs_off`：`oneExpiryWarn=false`、`oneCapError=false` |
| M6 | 移除後不釋票 | `close_loads_unloaded_b_with_bounded_ticket`：`ticketReleasedInTheRemovalTick=false` |

- M2、M3 都是兩處合成的變異，原件只有合成版的 diff 與 gate root。M2 同時拿掉「租期到的 close」與「`backOff` 的釋放」而被殺。
  - 讀測試可以確認：沒有任何案例在「持票中發生門、移除或交接失敗」時核對釋放（審查 M-2）。
  - 單改一處時另一個釋放點（`refresh` 或 START tick 的列不再適用）會補上、兩個元件各自單獨變異預期會存活，這兩點是 implementer 與審查 M-2 的讀碼分析；單點變異沒有實跑（審查員只讀、沒有跑 Gradle）。
- M3 在 test callback 的觀察 `noTicketBeforeTheCloseIsChecked` 仍是 true，要靠 `e3eea5a` 的每 tick 結束時觀察才殺得到。

### 原生列與 SelfTest

- 新列 `restart-close-unloaded-b`（restart 族、passable、cut `ready-saved`）：crash 在 READY＋部分 Crossed 落盤之後；reopen 不保持 B、不讀 B 門，參與者收尾之後才登入。正式 owner 以 RESTART_CLOSED 凍結 CLOSE，取 `CLOSE_CONVERGE` 票載入 B；before-door 起每個事件都有票、B 玩家 0（Java `bClosedByTheEntryTicket`，runner 判定 `M7_HOME_REOPEN_B_TICKET`）；收尾後 `entryTicketLease=false`、A 解除 busy、`crossedKept`。implementer 實測 reopen tick 21 收尾。
- 其他 restart／receipt 族 7 列另核 `noEntryTicket`，runner 核每個事件 `entryTicket=false` 與結束時 `entryTicketLease=false`。close 矩陣 39 列不改。
- 原生列由 46 列／92 phase 變成 47 列／94 phase。硬編同步在 `m7-home-probe.ps1`、`m7-inventory.json`、`run-all.ps1`、`build.yml`、`scripts/verification/README.md`，審查第 2 節核過一致。
- SelfTest 負控上限 479 沒有改（`m7-home-probe.ps1` 的上限）：
  - 單份完整 47 列 384（8＋8×47）：完整回歸實際輸出 `negativeChecks=384`；
  - CI 門態五份彙總 434（8＋10×5＋376）：CI 44362426613 的 `m7-home-native-aggregate` 讀回 `M7_HOME_AGGREGATE_COMPLETE PASS combination=doorState summaries=5 rows=47 phases=94 negativeChecks=434`；
  - family 三份 414（8＋10×3＋376）：`scripts/verification/README.md` 的公式值，本筆沒有實跑原件。
- 都在 479 之內。但 plan :539 要求的「先依 T5 Step 0 第 5 點定 SelfTest 餘量策略」沒有做：派工簡報把它收窄成 ≤479 的檢查，implementer 照簡報做；新判定 `M7_HOME_REOPEN_B_TICKET`、`entryTicketLease` 也沒有 SelfTest 負控（缺欄位時 fail closed，不是空判定）。兩者都是審查 M-5。使用者已於 2026-11-19 11:5x 裁示負控上限（見下方「SelfTest 負控上限的使用者裁示」），實作與補負控排在 T5 Step 0 第一項。
- 乾淨 pin `e3eea5a` 本機另跑（S `t4b-native-clean/`）：`-Family restart` 4 列、`-Family receipt` 4 列、`-DoorState passable` 13 列，各自 `-VerifySummary -SelfTest` PASS，treeClean=true。ordinary、sealed 兩片與 47 列完整一次由完整回歸與 CI 負責。
- 開發中一次 runner 失敗（StrictMode 下空集合被展開成 `$null`），是 implementer 自己的 script 錯，修正後 PASS。

### focused

乾淨 pin `e3eea5a` 兩輪，root S `t4b-focused/r1`、`r2`。每輪依序：M7 全集合（執行與 `-VerifyOnly -SelfTest`）、`ProjectionProtectionIndexTest`、`M7HomeCoverageTest`、新原生列加 `-VerifySummary -SelfTest`。

| 項 | r1 | r2 |
| --- | --- | --- |
| M7 全集合 | `M7_VERIFIED PASS platform=Windows junit=53 gametest=51`（launched True、False），gametest.xml `3a616fd6…c299` | 同左，`ae27036f…23cb2` |
| `ProjectionProtectionIndexTest` | 12/12 | PASS |
| `M7HomeCoverageTest` | 4/4 | PASS |
| 新原生列 | `M7_HOME PASS rows=1`，`M7_HOME_VERIFIED_SHARD PASS … negativeChecks=18 selection=only` | 同左 |

M7 全集合的 gametest 51 是 T4A 的 43 加 Step 0 兩案、T4B 六案；junit 53 是 52 加 1。

### 完整回歸第一次 FAIL（原判保留）

- holder S `t4b-full-holder-da9559c954ec402b881ac7665ea6b8eb`：holder PID 27876、run-all PID 18668，2026-11-18 17:43:31Z 開始，`CHILD_CODEPAGES=65001,65001,65001`，`exit.txt` 為 1。
- run-all root `V/run-all-902b2e397d67468b80a9e2b6ab61bb1a`，HEAD `e3eea5a`。2026-11-19 01:43:32（+08:00）開始；`w10-preflight`、`full`、`m4-normal`、`legacy` 都 exit=0，接著 `END m5-gametest FAIL GameTest gate exit=1`，沒有 `END ALL`。本筆讀回時 `console.log` 的 SHA-256 是 `D5BFCBDE…A717`。
- 失敗：gate root `gate-m5-gametest-167629ab7ba74122808f04a49afea78c`，M5 GameTest 60 案中唯一失敗的是 `m5universeprojectiongametests.prepare_waits_while_another_projection_write_is_unconfirmed`，XML 的 message 是「(-12654555, -60, 7527424) journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot」，`time="0.044"`。XML SHA-256 `CBFC2A61…4858`（調查第 1 節）。
- 四個 gate 的世界都是 `exactRestored=true`，之後只剩 Gradle daemon（S `STATUS.md` 02:0x 段）。
- 調查（S `t4b-full-m5-fail-investigation.md`，唯讀，HEAD `e3eea5a`）：
  - 失敗點：例外只由 production `SessionRecoveryState.java:1101` 內層 catch 的 checked 包裝產生，表示 `save` 的 PREPARE／WRITE／READBACK 其中一段真的失敗；M5 run 的故障注入不會產生這則訊息（證實）。失敗的寫入是本案 fixture `DormantOrigin.own` 對新收據 `50c180cb…` 的 journal checked flush，約在 test tick 3～4，本案還沒進入 RESOLVE、也還沒裝任何故障注入（推論，強）。
  - cause 未記錄：這條路徑把例外直接丟給 GameTest，XML 只寫 message，log 不印 stack，暫存檔也在 `finally` 刪掉。所以 runtime log 沒有 `AccessDeniedException` 不能排除 AccessDenied。最可能是 Windows atomic move 的暫時性 AccessDenied，這是推論（中），沒有證實；持鎖的程序從原件無法證明。
  - 與 T4B 無關的證據（證實）：失敗路徑上的 `SessionRecoveryState`、`SessionJournalStore` 與 testmod 的 `M5GameTestSupport`、`M5UniverseSupport`、`M5UniverseProjectionGameTests`、`M4CandidateTestAccess` 在 `70ed3d9..e3eea5a` 都沒有改；本 run 的 journal 是 schema 5、沒有 HOME 列，log 中「回程」「B 收尾票」「原艙票」都是 0 筆，T4B 新增的 tick handler 在 M5 run 都不做事；`reserveRootCoverage`／`RootChunk` 只被 A 票與 B 收尾票使用；與 T4A `c091581` 的 M5 runtime log 正規化後逐類比較 WARN／ERROR，除了本次失敗本身與兩則數值格式差異之外完全相同。T4B 只可能極間接地改變 tick 時序，這不構成 journal IO 失敗的機制（推論）。
  - 調查重現：在 `e3eea5a` 以新 nonce 跑三次，root `V/t4b-m5-inv-ba90ecab264a4e7cb83e5a04d077bc71/`。
    - r1 完整 M5 gate：59/60。本案 PASS（3.069 秒）；另一案 `prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection` FAIL，log 證實 cause 是 `quantumchamber_projections.dat` 的 atomic move `java.nio.file.AccessDeniedException`，打在測試 fixture 自己的 checked 寫入，與 T2 `run-all-b893e945…` 同簽章。
    - r2 完整 M5 gate：60/60。
    - r3 只跑本案：1/1。
    - 三次的世界都 `exactRestored=true`。本案在 `e3eea5a` 上三次都 PASS，`converged=201 ready=202`，與 T4A 同案相同。
  - 分類：(b) 既有的環境偶發。因為 cause 沒有被記錄，只能寫「已知類型、原因推論」，不能寫成「已證實 AccessDenied」。
  - 調查的建議：不修 production；簽章沒有被記錄、只能推論，不宜由 controller 自行套用 AccessDenied 單次重跑規則，交使用者裁示；另把 fixture 的寫入失敗記下 cause 列入 T5 Step 0（只改 testmod）。
- 更正：S `STATUS.md` 02:0x 段原寫「runtime log AccessDenied 0 則，非 AccessDenied」。這條寫入路徑本來就不記 cause，log 0 則不能排除 AccessDenied，所以「非 AccessDenied」不成立；02:2x 段已更正為 cause 未記錄、無法判定，02:0x 段也已加註。

### 使用者原文（2026-11-19 06:3x）

AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-t4b-full-rerun-ruling-20261119.md`。原文檔的背景段是 controller 寫的說明，不是使用者原文。

- 題目（header「T4B 回歸」）「T4B 完整回歸的 M5 失敗，原因很可能是 Windows 存檔鎖檔（AccessDenied），但沒被記錄、無法證實；同一案重跑 3 次都通過，找不到 T4B 造成的證據。要怎麼處理？」
- 選項：
  1. 「視同 AccessDenied，重跑一次（建議）」：「保留原 root，換新 nonce 對同一個 pin 重跑完整回歸一次。調查時單跑 M5 碰到的那次 AccessDenied 不是完整回歸，不計入次數。重跑再失敗（任何原因）就停下回報。測試寫入失敗時要記下原因的修正，排進 T5 Step 0。」
  2. 「先補診斷再跑」：「先改測試，讓寫入失敗時記下原因，順便修 T4B 審查的 Minor，用新 pin 重新跑完整回歸與 CI。較慢，但之後再遇到能判定簽章。」
  3. 「先停在這裡」：「不重跑、不修改，T4B 維持未結案，等你另行指示。」
- 使用者選「視同 AccessDenied，重跑一次（建議）」。
- controller 的執行說明（不是使用者裁示，S 原文檔同名段）：
  - 這次重跑用掉 `e3eea5a` 這個 pin 的 AccessDenied 單次額度；重跑再失敗（任何簽章）就停下回報，不再重跑。
  - 調查用的 r1 依選項說明不計入次數。
  - fixture 的 checked 寫入失敗時，記一則帶 cause 的 ERROR 再原樣丟出（只改 testmod），列入 T5 Step 0。

### 重跑（依 06:3x 裁示，換新 nonce）

- holder S `t4b-full-holder-b5aa425c22f2480b998e8adfdd1bfdec`：holder PID 32236、run-all PID 8852，2026-11-18 22:40:15Z 開始，`CHILD_CODEPAGES=65001,65001,65001`，`exit.txt` 為 0。
- run-all root `V/run-all-b90f443e98784cbbb1c4903cb292d90c`，HEAD `e3eea5a`。
  - 2026-11-19 06:40:15（+08:00）開始，08:23:27 `END summary exit=0`，接著自然 `END ALL exit=0 HEAD=e3eea5a18e4baa4f00eef5cb41b2706edfc86c01`。
  - console 第 2 行 `STATUS ` 後面是空的，沒有任何 `END … FAIL`；`END m5-gametest exit=0`。
  - 本筆讀回時 `console.log` 的 SHA-256 是 `8EC580A2…817C`，`summary.json` 是 `BFB454A0…AA2D`。
- `SUMMARY` 行（逐字）：

  ```
  SUMMARY default=166/166 windows=76 legacy=1/1 m5=60/60 m6=35/35 m6pace=36.7 normal=13/13 perTest=43+13+1 junit=1199 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=639entries/592classes sources=276 testmodHits=0 mainOnly=PASS m3=4+5 recovery=25 m5collapse=69 m6native=DIAGNOSTIC:2,FUNCTIONAL:25 ci=win:PASS,ubuntu:PASS m7native=COMPLETE:47 m7scale=PASS:11
  ```

- 其他關鍵行，都帶 `HEAD=e3eea5a…`：
  - `M7_VERIFIED PASS platform=Windows junit=53 gametest=51`（launched True、False 各一行）；
  - `M7_SCALE_VERIFIED PASS platform=Windows junit=11`（launched True、False 各一行）；
  - `M7_HOME PASS rows=47 phases=94 selection=all seconds=1683.7`；
  - `M7_HOME_VERIFIED_COMPLETE PASS rows=47 phases=94 negativeChecks=384 selection=all`；
  - `M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479`。

### 採計條件

- 條件來源同 T4、T4A：S `t4b2b3-review.md`「B2b-4 審查」第 2 節，加上「B2b-5 審查」第 4 節的延伸。T4B 版沿用 T4A 版由 `m7-inventory.json` 推出 M7 預期案數（junit 53、gametest 51、與規模段聯集 64），另把原生的列數、phase 數與負控數改成參數。
- controller 以唯讀腳本、參數 47／94／384 逐項核對，26 項全 PASS（`ACCEPT_FAILS=0`）。原件在 S `t4b-accept-check/`：`t4b-accept-check.ps1`（SHA-256 `75B5F43B…93C5`）、`t4b-accept-check-result.json`（`CF751431…79F3`）。
- 核對內容：
  - 乾淨 code pin：console 第 2 行 `STATUS ` 後面是空的（本筆讀 console 確認，不在 26 項腳本內）；
  - `END ALL exit=0 HEAD=e3eea5a…` 恰一行，沒有 `END … FAIL`；
  - `m7HomeNative` 為 COMPLETE、47 列、94 phase，記錄的 SHA 與重算一致（`m7-home-run-1f1cbce2c02d.json`，`9E9AC0BB…A9D4`）；
  - console 有 47 列 COMPLETE 的 verify 行、`END m7-home-native exit=0`、`negativeChecks=384` 的 COMPLETE 行；
  - `SUMMARY` 行尾有 `m7native=COMPLETE:47` 與 `m7scale=PASS:11`；
  - `END m7-home exit=0` 與 `M7_VERIFIED PASS … junit=53 gametest=51`；
  - `END m7-scale exit=0`，launched 與 VerifyOnly 兩行 `M7_SCALE_VERIFIED PASS`；
  - `m7HomeScale` 的 scope、status、head 相符，11 案；`m7Home.junitCases` 恰 53 個、不含規模測試，兩者聯集 64；
  - 規模段的 `execution.json` 為 600／90、`phaseDeadline` 為空、只有一個 `--tests`；M7 gate 的 `execution.json` 不含規模測試。
- 負控：同一支腳本改用舊的 46／92／376 對同一個 root 核對，`ACCEPT_FAILS=5`（`native.rows`、`native.phases`、`native.verifyLine`、`native.neg`、`summaryLine.native`），證明參數化的項目真的會拒絕舊數字（S `STATUS.md` 08:3x 段）。負控原件是 S `t4b-accept-check/t4b-accept-check-negative-46-92-376.json`（SHA-256 `C8A95336…D8B6`，末行 `ACCEPT_FAILS=5`，STATUS 09:3x 段記為補存）。另外，本筆起草時以同一支唯讀腳本對同一個 root 重算正、負兩組，結果相同（腳本只讀檔、只輸出到 console），可作佐證。

### 同 SHA CI

本筆讀 S 的 `ci-44362426613-run.json`（`gh run view --json jobs` 的原件），另以 `gh -R Ben513242/QuantumChamber run view 44362426613` 讀回 event、時間與各 job 的關鍵 log 行。

| run | head | 結果 | 說明 |
| --- | --- | --- | --- |
| 44362426613 | `e3eea5a` | attempt 1，event push，十個 job 全部 success | build、m7-validation（Ubuntu、Windows）、m7-home-native 四片（ordinary、sealed、passable、restart-receipt）、m7-home-native-aggregate、m6-native、windows-native。建立 17:46:46Z，最後更新 18:29:34Z。關鍵行：Ubuntu `M7_VERIFIED PASS platform=Ubuntu junit=53 gametest=47`、Windows `M7_VERIFIED PASS platform=Windows junit=53 gametest=51`、aggregate `negativeChecks=434`，都帶 `HEAD=e3eea5a…` |

- 完整回歸與 CI 都在 `e3eea5a`，所以這次不涉及 T4A 那樣的 B+C 採計。
- 沒有動用 M5 熔岩偶發的讀法，被忽略的 run 是零筆。

### 審查

- S `t4b-review.md`，一席（spec＋quality 同席），範圍 `70ed3d9..e3eea5a`，只讀，審查員沒有跑 Gradle、GameTest 或 run-all。spec ✅、quality Approved，C0／I0／M7。Step 0 逐項、plan Task 4B 各核取項與 spec v1.6 C 逐項的判定都是 ✅（附 Minor）。
- 4(a) production 的測試接縫 `HomeEntryTickets.backend`（package 可見、非 final 的 static）：審查判定可接受（審查 M-4），controller 採用，記為已接受的偏離；這是 controller 的決定，不是使用者裁示。審查的理由：
  - release JAR 預設 `NATIVE`，`src/main` 沒有任何寫入者；`Lease` 在建立時捕捉 backend，替換只影響之後新取得的票；
  - 只在 server thread 讀寫，不需要 volatile；package-private，外部要用反射或同 package 類別才能改，不構成新的邊界；
  - 專案的 fault injection 慣例是 testmod mixin，這是 `src/main` 目前唯一可變的 static 測試接縫，是新的先例；`HomeRootTickets.acquire(…, Backend)` 已有 package 可見的 backend 接縫，差別只在全域可變。
  - 若日後要統一回 mixin 慣例，需要另授權新增 `src/testmod/java/dev/quantumchamber/gametest/mixin/M7HomeEntryTicketBackendMixin.java` 與修改 `src/testmod/resources/quantumchamber-test.mixins.json`，並重跑相關殺手。
- 4(b) spec 沒寫明的三個讀法：S `t4b-report.md` 偏離 8 原寫「controller 判斷，不是使用者裁示」；這三個讀法由 implementer 在實作時採用，並列為報告「留給 controller」第 3 點的審查焦點，本筆依 controller 派工記為 implementer 的讀法。審查判定三個讀法都合理、與 spec 不衝突。不論記成哪一種，都不是使用者裁示。
  1. RESTART_VERIFY 的票在 RESTART_CLOSED checked 之後繼續供收尾使用：`stillApplies` 只要任一用途仍適用就保留，不另換票；換票會先放掉已載入的 B、重置租期，仍受同一份 200 tick 租期約束。
  2. RESTART_VERIFY 載入 B 之後端點仍不合法時釋票、WARN、退避：FULL 之後的非 VALID 是決定性的，但 B 可能被管理員修好；T4 在 B 已載入時本來就逐 tick 重核，這裡改成有界退避重核。審查另指出 ERROR 字句的處理提示偏向 chunk 載入與額度，對這個原因不準（併入 M-3）。
  3. ERROR 記在第一次把退避推到 12000 的那次失敗：字面也能讀成「等滿 12000 之後再失敗」，兩者都恰一則，差別約 12000 tick 才通知管理員。退避計數與門、移除、交接失敗共用，所以 ERROR 可能在其他原因把退避推到上限之後，由第一次票失敗觸發。`close_ticket_never_full_releases_and_backs_off` 已把這個讀法寫死，若改讀法要同步改測試。
- 4(c) plan 沒列的 `close_ticket_releases_the_root_ticket_first` 與 `e3eea5a`：恰當（見上方「GREEN」）。
- 審查第 5 節列的本機未驗部分：
  - Ubuntu：6 個新 GameTest 與 Step 0 兩案在 CI 44362426613 的 Ubuntu `m7-validation` 通過（見上方）；Step 0 relatch 案的變異在 Ubuntu 分支（走 checkpoint 預檢）會不會被殺，仍只有讀碼推論。
  - ordinary、sealed 兩片與 47 列完整：完整回歸與 CI 都通過。
  - 真停服：只以反射呼叫 `closeAll(server, true)` 驗證；原生列的停服發生在票釋放之後，沒有「停服時仍持票」的真實路徑證據。
  - RESTART_VERIFY 的真重啟：只在 GameTest 以反射注入開服集合驗證；原生新列走的是 CLOSE_CONVERGE。
  - 觀察（審查不列 finding）：`Lease` 保存 `ServerWorld` 參照；B 的 universe 若在租期內被卸載，票會留在舊 world 物件上直到到期，與 A 票同型。

### SelfTest 負控上限的使用者裁示（2026-11-19 11:5x）

- 來由：M7 原生 runner `scripts/verification/m7-home-probe.ps1` 的負控上限 479（`:497` 常數，`:505` 彙總、`:519` 單份比較）。只讀分析 S `m7-selftest-margin-analysis.md` 指出：
  - 479 在 M6 是實際的負控數（277＋202）；M6 的使用者裁示是不得刪減這 479 個反例（下限）與 SelfTest 900 秒（時間上限），M6 runner 本身沒有負控數上限；
  - M7 把 479 當上限，是 controller 在 T4 B2b-2 派工簡報（S `task-4b2b2-brief.md:44`）的讀法，不是使用者裁示；之後 runner、B2b2-M1、plan :539 與本 note 都沿用成既定門檻；
  - 實測 M7 的負控很便宜：47 列單份 384 個在本機只多 4.6 秒，CI 門態彙總 434 個約 7～9 秒；上限檢查在全部負控跑完之後才比較，不保護時間；
  - 推估（分析標為推論）T5 加原生列後彙總大概率超過 479，到 T7 約 560～656。
  - plan :539 規定超過 479 交使用者裁示，所以 controller 提問。原文檔的背景段記 controller 已在提問前向使用者說明 479 的來由。
- AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-m7-selftest-cap-ruling-20261119.md`。原文檔的背景段是 controller 寫的說明，不是使用者原文。
  - 題目（header「負控上限」）「M7 原生測試的負控（故意做壞的反例）上限目前是 479，T5 加新的原生鏈後就會超過。實測 400 多個只要幾秒。要怎麼處理？」
  - 選項：
    1. 「上限改 1000 個＋時限 120 秒（建議）」：「每次驗證最多 1000 個負控，而且 SelfTest 超過 120 秒就判失敗。覆蓋不降低，還能補上目前沒有負控的幾個判定。改動只有一個常數加計時，會先經獨立審查。」
    2. 「維持 479，拆成多次驗證」：「不改上限，把驗證按分片拆開，讓每次都不超過 479。覆蓋不變，但要改完整回歸與 CI 的結構，成本較高。」
    3. 「維持 479，超過時再問」：「先不決定，每次要超過時停下來問你。T5 很可能一開始就會碰到。」
  - 使用者選「上限改 1000 個＋時限 120 秒（建議）」。
- controller 的執行說明（不是使用者裁示，S 原文檔同名段）：
  - 「每次驗證」指 runner 每一次 `-VerifySummary -SelfTest` 或 `-VerifyAggregate -SelfTest` 呼叫；上限 1000 與 120 秒對兩者各自適用。超過個數或時限都以具名錯誤失敗（例如 `M7_HOME_SELFTEST_BUDGET`），不得自動抽樣或略過負控。
  - 實作排進 T5 Step 0（只改驗證腳本與 README，走 B+C：一席審查、定向驗證、CI）。同一步補上目前沒有負控的判定（T4B 審查 M-5 指出的 `M7_HOME_REOPEN_B_TICKET`、`entryTicketLease`），新增後的負控總數與實測耗時寫進報告。
  - 這是 M7 原生 runner 的上限；M6 runner 與其他 gate 的時限不變。
- plan :539 的「超過 479 交使用者裁示」與本檔較早各節的同類待辦（例如「最新：T4 CLOSED（2026-11-18）」節「殘留交 T5 Step 0」第 5 點），已由本裁示處理；那些原文保留為當時的狀態。

### 殘留交 T5 Step 0

以下交 T5 Step 0，是 controller 的決定（S `STATUS.md` 11:5x、08:3x、06:4x 段），不是使用者裁示；其中 SelfTest 上限一項依使用者 11:5x 裁示，fixture 記下 cause 一項另有使用者所選選項說明的依據。

T4B 新增（第一項依 controller 排序先做）：

- SelfTest 負控上限（依上方 11:5x 裁示；審查 M-5，併既有清單第 5 點 B2b2-M1）：把 runner 的上限改為每次呼叫 1000 個並加 120 秒計時，補上 `M7_HOME_REOPEN_B_TICKET`、`entryTicketLease` 的負控，走 B+C，報告寫明新增後的負控總數與實測耗時（controller 執行說明）。T4B 結案時的數字：47 列單份完整 384、family 三份 414、門態五份 434；門態彙總剩 45，每多一列原生多 8 個逐列負控，在原上限 479 下再加 5 列是 474、第 6 列起超過（審查原文寫「約再 5 列原生就超過 479」）。
- 審查 M-1（spec 邊界）：開服集合中已有 StopReason 的 SEALED(AUTHORITY_UNREADABLE) 仍先走 RESTART_VERIFY；B 不合法時 ROOT_STOP 的 CLOSE 一直不凍結、log 原因不準（`HomeClosure.java:229-242`、`:459-464`）。審查建議 `restartVerifies` 加 `stopReason().isEmpty()`，並補具名 RED。
- 審查 M-2（測試缺口）：沒有案例在持票狀態下核對「失敗進退避時釋票」（`HomeClosure.java:414-419`）；單點變異預期會存活是讀碼分析，未實跑。審查建議加「B 收尾票 FULL 之後 B 門寫入或收據寫出受控失敗」的案例，斷言失敗那個 tick 結束時與退避期間都沒有票，再對 `backOff` 單點變異實跑。
- 審查 M-3（品質）：CLOSE_CONVERGE 路徑在票 FULL 之後遇到決定性的非 VALID，持票到期才退避，記的是租期到（`TICKET_EXPIRED`）的 WARN，與實際原因不符；WARN 去重只比上一個鍵，兩種 WARN 交替各記一次；ERROR 提示只提 chunk 與額度。審查建議比照 RESTART_VERIFY 呼叫 `entryTicketFailed`、ERROR 提示補「或 B 端點不合法」；要嚴格到「同原因」的去重會影響 T4 既有行為，審查建議交後續。
- 審查 M-4（4(a)）：測試接縫 `HomeEntryTickets.backend` 已接受，T5 Step 0 不必改；若要統一回 testmod mixin，所需授權檔見上方「審查」。
- 審查 M-5（流程）：併入上方「SelfTest 負控上限」一項。
- 審查 M-6（證據與歷史）：本節「commit」「Step 0」「RED」已寫明；審查建議不改歷史，T5 Step 0 不必處理。
- 審查 M-7（測試穩健）：`until` 的 `done` 判斷丟例外時不走 hooks 收尾（`M7HomeClosureGameTests.java:1319-1336`），`FakeBackend`、`stopping`、`Startup` 集合、`Budget` 佔用都不會還原，會污染後面的 batch。審查建議把 `done` 包進同一個 guard，或讓還原另掛在不依賴 callback 的收尾。
- fixture 的 checked 寫入失敗時記下 cause：依使用者 06:3x 所選選項說明原文「測試寫入失敗時要記下原因的修正，排進 T5 Step 0。」做法是 controller 的執行說明（取自調查第 6 節第 3 點）：`DormantOrigin.own`，以及同類在回呼裡直接做 checked 寫入的 fixture，寫入失敗時記一則帶 cause 鏈的 ERROR 再原樣丟出；只改 testmod，不重試、不吞錯、不放寬任何斷言。
- 本機未驗、列入待驗（審查第 5 節；docs 審查觀察 1；列入是 controller 的決定）：
  - 真停服：停服釋票只以反射呼叫 `closeAll(server, true)` 驗證，沒有「停服時仍持票」的真實路徑證據；
  - RESTART_VERIFY 的真重啟：只在 GameTest 以反射注入開服集合驗證；
  - Step 0 relatch 案的變異（S0-A、S0-B）在 Ubuntu 分支會不會被殺：只有讀碼推論。

既有清單（上方「最新：T4 CLOSED（2026-11-18）」節的「殘留交 T5 Step 0」第 1～10 點，逐條核對，沒有遺漏；編號照舊，plan :539、:548 以這個編號引用）：

1. 連坐缺口兩處（`M2CorridorGameTests.java:1979-2002` 的 `sharedEntry` 外層失敗不收尾、`M4CandidateDoorGameTests.java:333` 一帶的 readback probe 受他案殘留影響）：仍待；兩檔在 `70ed3d9..e3eea5a` 沒有改。
2. B2b5-M1（M7 JUnit 宣告掃描只認 `@Test void`）：仍待。
3. B2b5-M2（CI 規模段 step 沒有 `timeout-minutes`，Main 失敗時連帶跳過）：仍待；`build.yml` 在 T4B 只改了原生列四片的註解與彙總比對的列數、phase 數。
4. `SessionRecoveryState.java:1027` 註解的歸屬：仍待；這個檔在 `70ed3d9..e3eea5a` 沒有改。
5. B2b2-M1（SelfTest 上限 479 的餘量策略）：使用者已於 11:5x 裁示（見上方「SelfTest 負控上限的使用者裁示」），已與審查 M-5 併為上方「SelfTest 負控上限」一項；實作仍待。
6. B1 假設 (c)：已依 v1.6 C 改寫（plan :548）。「重開時 B 無人無票」的收尾已由 T4B 的 B 收尾票實作，原生列 `restart-close-unloaded-b` 在完整回歸 COMPLETE；T5 仍要以正式 B 來源票重驗 COLLAPSING／ARMING 的開服返還。
7. B2a-M3（`restart-enable-before-entry` 的「再操作 B」以真實 B 端操作重跑）：仍待（T5／T6）。
8. Phase A 審查 M6（選定後重驗 A 時呼叫 `checkRoot` 與 `onRootEvent`）：仍待（T5／T6）。
9. 呼叫端持有同 SID guard 的限制（T6 的 B 門攔截不得包在同 SID 的 guard 內；`onEntryStopped` 的 LOW／INVALID 正式 mint 路徑沒有 GameTest 驅動與 mutation）：仍待（T5／T6）。
10. Phase A 審查 M7（每個 Projection 案例都物化 U 而且不卸載，後續測試不得斷言絕對世界數）：仍有效。

T4A 節交 T4B Step 0 的 12 點都已在上方「Step 0」處理（第 12 點的可選加固評估後不做）。

### 計數

- AccessDenied：`e3eea5a` 這個 pin 的單次重跑額度已由 06:3x 裁示的重跑用掉；重跑通過。同一 pin 若再發生，停下回報。調查用的 r1（`V/t4b-m5-inv-ba90ecab…` 的完整 M5 gate，已證實的 AccessDenied）依使用者所選選項說明不計入次數。
- 完整回歸啟動器歷史 2/3 不變。第一次 FAIL 是 M5 gate 的 runtime 失敗，不是啟動器問題（S `STATUS.md` 02:0x 段）。
- M5 熔岩偶發：本 task 的兩次完整回歸、調查重現與 CI 都沒有出現這個簽章，同簽章累計仍是 3 次。
- implementer 回報本輪兩次失敗都是自己剛寫的程式或 script 錯（RED 編譯、runner 的 StrictMode），各一次、已修正，沒有同一驗證工具問題連續失敗。

### plan Task 4B 核取框

判定規則（controller 的派工要求，不是使用者裁示）：完全達成才勾；有偏離，或找不到具名測試、原件證實的子項，就不勾，在該行末註明。審查第 2 節對七項都判 ✅（附 Minor）；下列不勾都來自這條 controller 的從嚴規則，其中 :541 從嚴不勾經 controller 確認（S `STATUS.md` 09:3x、11:5x 段）。7 項中勾 2 項（:531、:533），5 項不勾：

- :535：沒有案例在持票中發生門、移除或交接失敗時核對釋放（M-2，讀測試可證）；「失敗進退避時釋票」與「租期到時釋票」各自單獨拿掉預期會存活，是審查 M-2 與 implementer 的讀碼分析，單點變異未實跑。另外 SERVER_STOPPING 只以反射呼叫 `closeAll` 驗證；開服集合中已有 StopReason 的 SEALED(AUTHORITY_UNREADABLE) 仍先走 RESTART_VERIFY（M-1）。
- :537：WARN 去重只比上一個鍵，兩種 WARN 交替各記一次，嚴格說不符「同 SID 同原因一則」（M-3）；ERROR 的時點是 implementer 讀法（4(b) 第 3 點）；busy 沒有逐 tick 斷言。
- :539：「先依 T5 Step 0 第 5 點定 SelfTest 餘量策略」在 T4B 沒有做（M-5；使用者之後於 11:5x 裁示上限，實作排 T5 Step 0）；另有 plan 外新增的 GameTest `close_ticket_releases_the_root_ticket_first`。
- :541：「退避期間持票」「CLOSE 未 checked 就取票」都是兩處合成的變異；「退避期間持票」有一種實現方式（只拿掉 `backOff` 的釋放）沒有任何案例在持票狀態下驅動到（M-2，讀測試可證）。這個單點變異預期會存活，是讀碼分析，未實跑。
- :543：沒有用候選 commit，改成五筆分開 commit；完整回歸第一次 FAIL，依使用者 06:3x 裁示重跑才通過。

勾選項的具名證據：

- :531（controller 的決定，比照 T4A :465）：兩案都有具名 RED，GameTest `CLOSE_PENDING_WITHOUT_B_TICKET`（XML `c56b43ea…2e78`）、JUnit `expected: <2> but was: <1>`（XML `d04992c1…e7ee`），兩個 root 都記有 HEAD `6c6051f`（見上方「RED」）；`71d5b90..6c6051f` 沒有 `src/main` 變更，production 的行為與 `70ed3d9` 相同（`71d5b90` 改了 3 個 `src/main` 檔，只限 Javadoc、註解與 M-5 可見性，位元組並不相同）。行末保留的揭露：RED commit `8e12954` 本身不能建置，具名 RED 在 test-only 修正 `6c6051f` 上取得；GameTest RED root 的 thresholds 在重新保持 B 之後才記（失敗訊息本體不受影響）。T4A :465 同型：第一個 root 是診斷 NPE，`fc78e13` 只改訊息順序後才取得具名 RED，當時判定勾選。
- :533：`SlotAllocator.RootChunk(Identifier, long)`、`CorridorPageManager.reserveRootCoverage(UUID, RegistryKey<World>, Collection<ChunkPos>)`、A 票傳 `server.getOverworld().getRegistryKey()`，審查第 2 節以程式核對；JUnit `M7HomeCoverageTest` 4 案（`sameXZDifferentWorldConsumesCoverage`、`boundsRootSharesAndReferencesWithoutReleasingOthers`、`entryAndRootSameXzDifferentWorldCountSeparately`、`bothOrdersRespectCombinedCoverageLimit`）在 GREEN 與 focused 兩輪都通過；變異 M1「coverage key 拿掉 world」被 `entryAndRootSameXzDifferentWorldCountSeparately` 殺掉。

### 已告知使用者的 controller 讀法

T4A 節「下一步」第 2 點的 spec G 節第 7 點（待套用門的座標核對只擋關門與套膜類目標），以及 spec v1.6 節的 AccessDenied 單次重跑規則涵蓋 T4A、T4B 的讀法，controller 已在 2026-11-18 本 session 的回報中說明（AccessDenied 讀法約 13:1x；G 節第 7 點約 15:5x 與 18:5x）。紀錄位置見 S `STATUS.md` 2026-11-19 12:1x 段，原件是該段列出的 session 對話紀錄。使用者沒有就這兩點回覆；這不是裁示，也不代表同意，使用者之後提出不同意見時照改。

### 下一步

1. 派 T5（含上列 T5 Step 0）。Step 0 第一項是依 11:5x 裁示改 runner 的負控上限與計時，並補上缺的負控；之後依序 T6～T8。這是 controller 的排序，不是使用者裁示。
2. main 合併、tag、人工代操作、worktree 退役、PR、LICENSE 仍各自另問使用者。

## 最新：T4A CLOSED（2026-11-18）

（已被上方 T4B CLOSED 節接續）下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`，V 指 `.superpowers/verification/`，兩者都 gitignored。本節的事實讀自 S 的 `STATUS.md`（18:5x、18:1x、17:3x、16:3x、15:5x、15:3x、14:1x 各段）、`t4a-v16-report.md`（含 fix1 節）、`t4a-v16-fix1/`、`t4a-v16-review.md`、`t4a-v16-fix1-review.md`、`t4a-v16-accept-check/`、`ci-44259424086/`（含 `run.json`）、`ci-44281662893-run.json`、`ci-44281662893-m7ubuntu/`、`m5-water-control-flake-investigation.md`、`user-m5-water-flake-ruling-20261118.md`，V 的 `run-all-5ff86c0b885e46e691645c7608afe69f`（`console.log`、`summary.json`），以及 git 的唯讀查詢。

- **狀態**：T4A CLOSED。
  - code pin：production 的最後一筆是 `c091581`。之後的 fix1 `099252f` 只改 testmod：`git diff --stat c091581..099252f` 只列 `src/testmod/java/dev/quantumchamber/gametest/M7RearmGameTests.java`（+23 行）。`git ls-remote origin` 讀回 `codex/m7-return-home` 是 `099252fb4c3279435a2ca03b5a43296b2e5ccd72`。
  - commit 範圍 `5ae0a04..099252f`，共五筆（見下表）。
  - 完整回歸：`c091581` 的 run-all 自然 `END ALL exit=0`，採計條件 26 項全 PASS。
  - 審查：T4A 一席（spec＋quality 同席）spec ✅、quality Approved，C0／I0／M9；fix1 一席 Approved，C0／I0／M3。
  - CI：`c091581` 的 44259424086 attempt 1 FAIL，原判保留、不 rerun；`099252f` 的 44281662893 attempt 1 十個 job 全部 success。
  - 採計方式見下方「B+C 採計」。
  - 前一筆 docs commit `5ae0a04`（spec v1.6）的 CI 44244964002 attempt 1 十個 job 全部 success（S `STATUS.md` 14:1x 段）。
  - T4A 結案不代表 M7 完成。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

### commit

| commit | 內容 |
| --- | --- |
| `7a0e39d` | test：RED 三案、testmod entrypoint、inventory（`src/main` 與 `5ae0a04` 相同） |
| `fc78e13` | test：永久封存 RED 的失敗訊息先組好再收尾（只改診斷順序） |
| `4acf64a` | fix 第一段：latch 與永久封存的解除路徑（中間候選，還沒有座標核對） |
| `c091581` | fix 第二段：原艙座標的門權威 |
| `099252f` | fix1（test）：idle 案收尾還原 checkpoint 預檢 WARN 的去重記號 |

`5ae0a04..c091581` 的改動檔恰為 plan Task 4A「新增／修改」所列的 16 檔，逐檔 stage（審查的核取項表 :519 那一列已核對）。`S/{gates,m7-ci-gametest}.ps1` 由 inventory 驅動，不需要改。

### RED

- `prepare_participant_close_requires_player_reclose_windows`、`passage_participant_close_requires_player_reclose_windows`：在 `7a0e39d` 以 `REARM_DEPARTED_AFTER_PARTICIPANT_CLOSE` 失敗。參與者關門終局之後，同艙立刻開出新的 ARMING session，比較器 7。plan 記為推論的取消後立刻再出發，因此在 Windows 上證實。root S `t4a-v16-red/gate-t4a-v16-red-gt-9882d736aaa741f8b2585e366c8bbaa7`，GameTest XML SHA-256 `065aa202…2b0a`。
- `permanent_sealed_origin_dismantles_after_disable_and_power_off`：
  - 第 1 root（同上）的失敗是測試自己的診斷 NPE：先收尾，封存列被終局 API 移除之後才組訊息，所以不是具名失敗。原 root 保留。
  - `fc78e13` 只改訊息順序（test-only），以新 nonce 重跑，以 `PERMANENT_SEAL_NOT_RELEASED` 失敗：power=RETURNING、比較器 11、艙體不可寫，封存列是 SEALED/EXISTING_INELIGIBLE。root `t4a-v16-red/gate-t4a-v16-red-seal-71a1cf679ffa45cf958b56e1c5ce7a2b`，XML `7f6eef99…3505`。
  - implementer 記為一次工具問題（RED 診斷順序），沒有重複發生。
- 中間候選 `4acf64a`（已有解除路徑、還沒有座標核對；`4acf64a..c091581` 只改 `OriginDoors.java`）：
  - `rebuilt_chamber_door_ignores_released_seal_after_restart_reapply` 以 `REBUILT_DOOR_CHANGED_BY_RELEASED_SEAL` 失敗：三個時點（待套用門、重啟後第一次觀察、chunk 重新載入），新艙的門都被舊封存列關上；
  - 同一輪 `rebuilt_chamber_prepare_participant_close_still_cancels_windows` 也以 `REBUILT_PREPARE_CLOSE_NOT_CANCELLED` 失敗；
  - root `t4a-v16-intermediate/gate-t4a-v16-mid-49c0133f9e514303a4cbf14e550e61d7`，XML `b0bf221d…3094`。

### GREEN

- pin `c091581`，root S `t4a-v16-green/gate-t4a-v16-green-gt-81118959694844238d747e3698d3ac9a`，GameTest XML `cdadc531…dfbf`：14 個 GameTest 全過；JUnit `M7RearmLatchTest` 3/3、`M7SealReleasePredicateTest` 1/1。
- batch 名稱都是 `m7_home_*`，只在 M7 run 選入，沒有動 M5 run 的 60 案。`_windows` 三案只在 Windows 跑，其餘 11 案兩平台都跑。本機沒有 Ubuntu，兩平台案的 Ubuntu 分支由 CI 驗（見下方「fix1」與「同 SHA CI」）。
- 實作做法的說明（審查都已核過）：
  - `latch_survives_player_open_then_system_close`：已供電的登記艙受保護，真的 `/setblock` 會被拒絕，所以改以授權的直接寫方塊（`M5GameTestSupport.writeDoor`）代替，另有系統 `applyTarget(CLOSED)`（審查 M-9）。這與 V12 的 `/setblock` 字面不同。
  - `/reload`：照 plan 沿 M6 的做法，直接觸發 Fabric START／END_DATA_PACK_RELOAD，不呼叫 `reloadResources`。
  - 待套用門的座標核對只擋關門與套膜類目標，見下方「審查」的 3(a) 與 spec G 節第 7 點。
  - Controller 未載入時補設 latch：實作是 `CLOSE_AFTER_TERMINAL` 套用前一律呼叫冪等的 `latchRearm`，不另設 pending 旗標；沒有具名測試單獨覆蓋（審查 4）。
- 盤點：沒有找到依賴「取消後同艙自動再出發」的既有 M5／M6 GameTest 或原生鏈，逐項見 S `t4a-v16-report.md` 的「:497 盤點」節；審查 3(c) 靜態核對屬實；完整回歸的 default 166、M5 60、collapse 69、M6／M7 原生都通過。

### mutation

pin `c091581`，root S `t4a-v16-mutation/M<n>/`，完整 SHA 見同目錄的 `mutation-log.txt`、`pin-hashes-c091581.txt`。

- 14 項都逐位元還原：六個 production 檔以 `sha256sum -c pin-hashes-c091581.txt` 全部 OK，`git status` 乾淨。M4 第一次還原少還原一行，中間 SHA 與 pin 不符而被發現，補還原後一致；這段期間沒有建置或測試。
- 13 項由 plan 表列的全部具名測試殺掉。M8、M9、M11 的 GameTest 失敗點是後續斷言，錯因訊息與本意無關（審查 M-4），殺手仍然有效。
- M10（忽略 rollForwardPending）：JUnit `onlyCheckedPermanentRowsQualify` 殺掉；GameTest `retryable_or_unchecked_seal_never_released` 是 PASS，沒有殺到。
  - 審查 3(b) 判定等價成立：可達的 journal 狀態下，`rollForwardPending(sid)` 一定代表 current≠flushed；判定先要求 current＝flushed，roll-forward 條件因此被它涵蓋。
  - 只由 JUnit 殺可以接受，不必用反射造出不可達的狀態；roll-forward 條件保留作縱深防禦。plan mutation 表的該列已在行末註記。

### focused

乾淨 pin `c091581` 兩輪，root S `t4a-v16-focused/r1`、`r2`：

- M7 全集合：兩輪都是 `M7_VERIFIED PASS junit=52 gametest=43`（執行與 `-VerifyOnly -SelfTest` 各一次）。
- `ChamberPowerCoordinatorTest` 19/19；加跑的 `MembraneStateTest`、`ChamberDoorServiceTest`、`ChamberControllerBlockEntityNbtTest`、`CollapseServiceTest` 分別 19、9、5、29 全過。
- 受影響的 M5 run 10 案、default run 4 案，兩輪都通過。

### 完整回歸（`c091581`）

- holder S `t4a-v16-full-holder-d02de64847e740babcbf018b0a01f42c`：holder PID 16252、run-all PID 39996，07:30:40Z 開始，`CHILD_CODEPAGES=65001,65001,65001`，`exit.txt` 為 0。
- run-all root `V/run-all-5ff86c0b885e46e691645c7608afe69f`，HEAD `c091581`。
  - 15:30:41 開始，17:23:45 `END summary exit=0`，接著自然 `END ALL exit=0 HEAD=c09158127db2ebccc42714d0dc47e298f588983c`。
  - console 第 2 行 `STATUS ` 後面是空的，沒有任何 `END … FAIL`；第一個 step `END w10-preflight exit=0`。
  - 本筆讀回時 `console.log` 的 SHA-256 是 `1E50F294…7BFE`，`summary.json` 是 `D9D10CCA…4B33`。
- `SUMMARY` 行（逐字）：

  ```
  SUMMARY default=166/166 windows=76 legacy=1/1 m5=60/60 m6=35/35 m6pace=35 normal=13/13 perTest=43+13+1 junit=1198 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=633entries/586classes sources=275 testmodHits=0 mainOnly=PASS m3=4+5 recovery=25 m5collapse=69 m6native=DIAGNOSTIC:2,FUNCTIONAL:25 ci=win:PASS,ubuntu:PASS m7native=COMPLETE:46 m7scale=PASS:11
  ```

- 其他關鍵行，都帶 `HEAD=c091581…`：
  - `M7_VERIFIED PASS platform=Windows junit=52 gametest=43`（launched True、False 各一行）；
  - `M7_SCALE_VERIFIED PASS platform=Windows junit=11`（launched True、False 各一行）；
  - `M7_HOME_VERIFIED_COMPLETE PASS rows=46 phases=92 negativeChecks=376 selection=all`；
  - `M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479`。

### 採計條件

- 條件來源同 T4：S `t4b2b3-review.md`「B2b-4 審查」第 2 節，加上「B2b-5 審查」第 4 節的延伸。T4A 版把 M7 的預期案數改由 `m7-inventory.json` 推出（T4 版寫死 48／29／59），這次推出 junit 52、gametest 43、與規模段聯集 63。
- controller 以唯讀腳本逐項核對，26 項全 PASS（`ACCEPT_FAILS=0`）。原件在 S `t4a-v16-accept-check/`：`t4a-accept-check.ps1`（SHA-256 `1AD089AF…CE60`）、`t4a-accept-check-result.json`（`419F2EA3…6310`）。
- 核對內容：
  - `END ALL exit=0 HEAD=c091581…` 恰一行，沒有 `END … FAIL`；
  - `m7HomeNative` 為 COMPLETE、46 列、92 phase，記錄的 SHA 與重算一致（`m7-home-run-769f03f8f4ad.json`，`61E89A98…023A`）；
  - console 有 46 列 COMPLETE 的 verify 行、`END m7-home-native exit=0`、`negativeChecks=376` 的 COMPLETE 行；
  - `SUMMARY` 行尾有 `m7native=COMPLETE:46` 與 `m7scale=PASS:11`；
  - `END m7-home exit=0` 與 `M7_VERIFIED PASS … junit=52 gametest=43`；
  - `END m7-scale exit=0`，launched 與 VerifyOnly 兩行 `M7_SCALE_VERIFIED PASS`；
  - `m7HomeScale` 的 scope、status、head 相符，11 案；`m7Home.junitCases` 恰 52 個、不含規模測試，兩者聯集 63；
  - 規模段的 `execution.json` 為 600／90、`phaseDeadline` 為空、只有一個 `--tests`；M7 gate 的 `execution.json` 不含規模測試。

### 審查

- T4A：S `t4a-v16-review.md`，一席（spec＋quality 同席），範圍 `5ae0a04..c091581`，只讀，審查員沒有跑 Gradle。spec ✅、quality Approved，C0／I0／M9。給 controller 的四個判定：
  - 3(a) 開門類待套用門不做座標核對：接受。目前不可達，就算發生也不會關住人，也不會取消新艙的 PREPARE 或通道；要記成 controller 讀法（偏離 spec D 字面的「待套用門的套用」），並把 Javadoc 的理由改成真正的不變量。已寫成 spec G 節第 7 點。
  - 3(b) M10 是等價變異：成立（見上方「mutation」）。
  - 3(c) 沒有既有案例依賴取消後同艙自動再出發：靜態核對屬實，最終以完整回歸為準；完整回歸已通過。
  - 4 Controller 未載入時補設 latch 沒有具名測試：風險低，不擋結案。終局當下來源 ticket 讓 Controller 一定是 FULL，唯一的系統關門就是 `CLOSE_AFTER_TERMINAL`，補設排在它的 `applyTarget` 之前；建議補註解（M-2）。
- fix1：S `t4a-v16-fix1-review.md`，一席，範圍 `c091581..099252f`，只讀。Approved，C0／I0／M3。審查認為修法消除了 CI 44259424086 的跨案干擾，不論 batch 順序都不會誤判 FAIL；spike 恰一則的斷言沒有放寬；還原的只是 log 去重記號，不影響 production 放行與否。真 Ubuntu 的行為以同 SHA CI 為準。
- controller 的處置（不是使用者裁示）：M-1～M-8 與 fix1 的 M1～M3 不改 pin，交 T4B Step 0；M-9 是文件項，在本筆結案 docs 處理（見下方「殘留交 T4B Step 0」）。

### fix1：Ubuntu 測試互相干擾

- 失敗：CI 44259424086 的 Ubuntu `m7homespikegametests.checkpoint_capability_remains_rejected_on_ubuntu`，訊息「真正式入口到達checkpoint平台拒絕，非較早前置拒絕」。spike 自己的 capture 期待恰一則預檢 WARN，實得 0 則。
- 原因（讀碼加 CI artifact）：
  - `SuperpositionSessionManager.checkpointWarning` 是整個 server 共用的去重記號，同一原因只記第一次，只在預檢通過或停服時清掉；Ubuntu 的預檢永遠不通過。
  - T4A 新案 `participant_close_latch_shows_idle_until_player_reclose`（batch `m7_home_rearm_idle`）在玩家親手關門解鎖之後，正式 start 走到預檢，留下記號。Ubuntu `latest.log` 第 610 行是整個 M7 run 唯一一則預檢 WARN；spike 的 batch `m7_home_ubuntu` 在第 920 行才開始，再進預檢就被去重。
  - batch 依名稱排序，所以必然重現。Windows 的預檢通過、不留記號，本機與 Windows CI 都看不到。
- 修法（`099252f`，只改 testmod）：本案開始時記下記號的值，收尾時還原；讀寫用既有的 testmod 反射 `M4CandidateTestAccess.get/set`，還原掛在本案既有的 hooks 上。Ubuntu 分支新增斷言 `reclosedStartReachedTheCheckpointPrecheck`。production、spike 與其他既有斷言都沒有動。
- 定向驗證（Windows，乾淨 pin `099252f`，在 controller 的完整回歸 `exit.txt` 出現之後才跑），root 在 S `t4a-v16-fix1/`：
  - compile gate PASS；
  - r1、r2 兩輪：batch `m7_home_rearm_idle`、`m7_home_windows`、`m7_home_portable` 加 JUnit `M7RearmLatchTest`，GameTest 4/4、JUnit 3/3。Windows 上本案走已出發分支，這兩輪只證明修法在 Windows 沒有回歸；
  - 補充：暫時改碼的 Windows 模擬 Ubuntu 分支（`CheckpointPlatformFault.simulateUnsupported`）PASS；拿掉還原 hook 的負控只有 `simMemoRestoredAfterRelease=false`，具名 FAIL。改碼已逐位元還原，`M7RearmGameTests.java` 的 SHA-256 與 `099252f` 的檔案相同。模擬不等於真 Ubuntu。
- 真 Ubuntu：`099252f` 的 CI 44281662893，Ubuntu M7 的 GameTest 39 案 0 失敗，`checkpoint_capability_remains_rejected_on_ubuntu` 與 `participant_close_latch_shows_idle_until_player_reclose` 都 PASS；S `ci-44281662893-m7ubuntu/summary.json` 為 status PASS、head `099252f`、junitCases 52、gameCases 39。

### 同 SHA CI

本筆讀 S 的 `ci-44259424086/run.json` 與 `ci-44281662893-run.json`（`gh run view --json jobs` 的原件）。

| run | head | 結果 | 說明 |
| --- | --- | --- | --- |
| 44259424086 | `c091581` | attempt 1 **FAIL**，原判保留、不 rerun | 十個 job 中八個 success。失敗的兩個：`m7-validation (ubuntu-latest, Ubuntu)` 的 step 6「執行 M7 獨立 GameTest」，即上方 fix1 的測試互相干擾；`windows-native` 的 step 8「Windows 原生 M5 GameTest」與 step 9「Windows M6 JUnit 與獨立 GameTest」。step 8 是 M5 `projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids` 對照組等待逾時（controlPistonPushed=true、waterMoved=false，至 tick 233）；step 9 是它連帶的 `M6_SCOPE_MISMATCH：GameTest run 範圍不符（m5）：expected=60 actual=60 缺 [] 多 []`。原件 S `ci-44259424086/` |
| 44281662893 | `099252f` | attempt 1，十個 job 全部 success | build、m7-validation（Ubuntu、Windows）、m7-home-native 四片（ordinary、sealed、passable、restart-receipt）、m7-home-native-aggregate、m6-native、windows-native。「執行 M7 獨立 GameTest」step：Windows 259 秒（上限 600 秒）、Ubuntu 153 秒。最晚完成的是 windows-native（10:03:20Z→10:39:09Z）。沒有動用下方的 M5 忽略讀法 |

### B+C 採計

- production 的完整回歸採 `c091581` 的 `run-all-5ff86c0b…`。
- fix1 `099252f` 只改 testmod，依既有 B+C 規則採計：只改測試、腳本或文件時，做一席審查、定向驗證與 CI，免 mutation 與本機完整回歸（出處見本檔「流程指示」節與 AGENTS）。fix1 的三項是：一席審查 C0／I0／M3、Windows 定向兩輪、`099252f` 的 CI 44281662893。
- 這是 controller 依既有 B+C 規則的判定，不是使用者裁示。`c091581` 自身的 CI 44259424086 FAIL 原判保留，不 rerun，也不因 44281662893 改判。

### M5 熔岩偶發與使用者裁示

- 調查：S `m5-water-control-flake-investigation.md`（只讀，HEAD `c091581`）。
  - M5 既有 GameTest `m5universeprojectiongametests.projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids` 的對照組，在 normal 地形上偶爾被自然熔岩搶先流進 `wet` 格而變成圓石。這是吸收態，延長等待無效。
  - 分類 (c)：既有 M5 測試的 fixture 在 M6 改成 normal 地形之後的環境偶發，與 M7 改動無關；這個案例的程式碼從 `dfed554` 到 `c091581` 都沒有改。
  - Windows CI 3/86、Ubuntu 0/86。調查建議的是改 fixture 封住鄰格（調查第 7 節的選項 B）。
- 次數更正：同簽章三次是 M6 分支的 42355698932（`dfed554`，2026-11-07）、M7 的 44078380354（`a8755ce`）與 44259424086（`c091581`）。下方歷史節「過程中的 CI」表 44078380354 列寫的「首次偶發」不正確：M6 那次沒有寫進任何 tracked 文件。原句保留，已在該列加註更正。
- 使用者裁示（2026-11-18 18:0x，AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-m5-water-flake-ruling-20261118.md`）：
  - 題目（header「M5 偶發」）「M5 既有測試「投影不變格」在 Windows CI 約 3.5% 會因自然熔岩搶先流入而失敗（和 M7 無關）。要怎麼處理？」
  - 選項：
    1. 「修測試 fixture（建議）」：「放水之前先把對照格周圍接觸自然地形的格子封成石頭（比照 M6 已有的做法），並加「不是流體」的前置斷言。判定與 200 tick 時限都不動，只改測試，當成 M7 裡獨立的小 task，走一席審查與 CI。」
    2. 「記為已知偶發，同簽章可重跑一次」：「不改碼。CI 只出現這個簽章時，保留原判、重跑那個 job 一次；同一 SHA 再發生就停下回報。每次 CI 仍有約 3.5% 機率紅。」
    3. 「維持現狀，每次都問」：「不改碼，也不訂重跑規則；每次出現都停下來問你。」
  - 使用者以「Other」自行輸入，原文：

    > 可以忽略，我記的玩家穿越到水底下或岩漿裡面就要承擔風險，盒子照樣生成

- controller 的讀法（照 S 原文檔第二節，不是使用者裁示；原文檔記為已在回報中告知使用者，使用者可更正）：
  - 不改 fixture、不重跑、不再為這個簽章停下詢問。
  - 範圍限定為「完全相同的簽章」：失敗案恰為上述 M5 案、訊息為對照組等待逾時且 `waterMoved=false`，以及它連帶造成的 `M6_SCOPE_MISMATCH（m5）`（expected=actual、缺與多都是空集合）。同一 CI run 中只要有其他任何失敗，照原規則停下回報。
  - 符合上述範圍時，該次 CI 視為不阻擋 task 結案；結案 docs 必須逐筆列出被忽略的 run id、job、step 與簽章，不寫成「CI 全綠」。
  - 使用者附帶的玩法說明（玩家穿越到水下或岩漿裡自行承擔風險、盒子照樣生成）與 M7 spec 既有的「不調整 Y」已知行為一致，不新增實作。
- T4A 結案沒有動用這條讀法，被忽略的 run 是零筆：44281662893 十個 job 全部 success；44259424086 在裁示之前，而且同一個 run 另有 Ubuntu 的失敗，不符合範圍，原判保留。

### 計數

- AccessDenied：`c091581` 這個 code pin 沒有發生（S `STATUS.md` 18:5x 段）。fix1 只改 testmod，沒有本機完整回歸。
- 完整回歸啟動器歷史 2/3 不變。
- M5 熔岩偶發：同簽章累計 3 次（見上方小節）。

### plan Task 4A 核取框

判定規則（controller 的派工要求，不是使用者裁示）：完全達成才勾；有偏離，或找不到具名測試、原件證實的子項，就不勾，在該行末註明。9 項中勾 5 項（:465、:467、:471、:473、:477），4 項不勾：

- :469：補設 latch 沒有具名測試（審查 4、M-2）；「這兩個單獨變異不會被殺」是審查 4 的靜態分析，沒有實跑。
- :475：待套用門的座標核對只擋關門與套膜類目標（審查 3(a)、spec G 節第 7 點）；registry 紀錄的 ORIGIN、未拆除兩個子條件沒有具名測試（M-3）。
- :498：M10 只由 JUnit 殺（等價，審查 3(b)）。
- :519：沒有用候選 commit，改成五筆分開 commit；production 的 CI 44259424086 FAIL，結案的 CI 是 fix1 的 `099252f`（B+C）；報告與證據 root 改用 `t4a-v16-*`。

勾選項的具名證據：

- :465：上方「RED」的三個 root 與中間候選 root。
- :467：JUnit `nbtRoundTripAndLegacyDefault`、`latchedOriginReturnsIdleWithoutStart`；GameTest `formal_start_refuses_latched_origin`、`rearm_latch_survives_power_cycle_enable_toggle_and_system_writes`；mutation M1、M5、M6、M7 被殺；位置與判斷順序由審查以行號核對（`ChamberPowerCoordinator.java:249-257`、`SuperpositionSessionManager.java:285-288`）。
- :471：GameTest `latch_survives_player_open_then_system_close`、`rearm_latch_survives_power_cycle_enable_toggle_and_system_writes`；mutation M2、M3 被殺；`MembraneStateTest` 核對的 mutator 字串不變（focused 兩輪 19/19）。
- :473：JUnit `M7SealReleasePredicateTest.onlyCheckedPermanentRowsQualify`、`permanentSealOnlyReachesOffWhenDisabledAndLow`；GameTest `permanent_sealed_origin_dismantles_after_disable_and_power_off`、`permanent_sealed_stays_protected_while_enabled_or_powered`、`retryable_or_unchecked_seal_never_released`、`home_permanent_seal_allows_root_removal_after_disable`、`rebuild_after_release_gets_new_identity_and_is_not_busy`；mutation M8、M9、M11、M12 被殺。ERROR 字句的解除路徑沒有具名斷言（M-8），以 log 原件證實：GREEN root 的 `latest.log` 第 170 行是帶解除路徑的開服提醒；完整回歸 `gate-m5-gametest-bfd1b202…` 的 `latest.log` 有 9 則帶解除路徑的「塌縮進入 SEALED」ERROR。
- :477：上方「GREEN」的 root 中 14 個 GameTest（含上述 RED 與原地重建兩案）全過，JUnit `M7RearmLatchTest` 3、`M7SealReleasePredicateTest` 1；兩平台的 11 案在 `099252f` 的 Ubuntu CI XML 都 PASS（審查另核 44259424086 的 Ubuntu XML，這 11 案在 `c091581` 本身也 PASS）；:481 是「或」，`applyTarget(CLOSED)` 那一支成立（`M7RearmGameTests.java:224-226`）。以授權直接寫方塊代替 `/setblock` 是 spec V12 的字面差異，記在上方「GREEN」，不是 :477 本身的偏離（T4A 結案 docs 審查 M-1，controller 決定改勾）。

### 殘留交 T4B Step 0

以下交 T4B Step 0，是 controller 的決定（S `STATUS.md` 15:5x、18:1x、18:5x 段），不是使用者裁示：

- T4A 審查（S `t4a-v16-review.md`）：
  1. M-1：`OriginDoors.claimsDoor` 的 Javadoc 理由（`OriginDoors.java:492-498`）改寫成 3(a) 的可達性不變量；目前寫的「不會把人關住」不足以說明安全。
  2. M-2：`OriginDoors.java:797-798` 的補設 latch 補註解，寫明終局時門開著、唯一的系統關門就是 `CLOSE_AFTER_TERMINAL`、Controller 由來源 ticket 保持 FULL。
  3. M-3：`ownsDoor` 的 ORIGIN／非 destroyed 子條件補具名測試（抽成純述詞補 JUnit），或記為 production 不會產生的縱深防禦後結案。
  4. M-4：`M7SealReleaseGameTests` 的保護斷言具破壞性（:420-428、:231-235），回歸時錯因訊息會誤導；對照步驟失敗時以具名前綴立即丟出，試拆之前先記下狀態。
  5. M-5：`ChamberDoorService` 新的 `toggle(…, Toggled)` 多載與 `Toggled` 介面降為 package-private。
  6. M-6：`OriginDoors.java:364` 的 Javadoc 過時（`sessionAt` 現在也讀 `ChamberRegistryState`）。
  7. M-7：判定把已失效的暫占記號也當成 runtime（`SuperpositionSessionManager.java:467`），屬 fail-closed，解除要等重啟，可達性未證；記下這個保守行為，或改用 `runtime(chamber)!=null || provisionalClaimed(…)`。
  8. M-8：在新的 M7 案以 `PERMANENT_RELEASE_HINT` 的字面片段斷言一次（不改舊斷言），或交 T8 人工驗收。
- fix1 審查（S `t4a-v16-fix1-review.md`）：
  9. M1：新斷言的鑑別力依賴 batch 順序；`capture` 記下原值後立即把記號設成 null，一律期待恰 1 則，收尾仍還原原值。spike 也可比照。
  10. M2：已出發分支補平台 witness（例如 `departsOnlyOnCheckpointPlatform`），讓兩案獨立守住誤放行。
  11. M3：`CheckpointMemo.capture` 納入失敗收尾；Javadoc 補上記號也決定是否記「已恢復通過」INFO。
- 可選加固（審查 3(a) 的 (ii)）：
  12. 評估是否讓開門類待套用門只在 registry 於該 frame 有一筆不同 UUID（或非 ORIGIN、或 destroyed）的紀錄時才丟棄，完全沒有紀錄時照舊；claims 目標維持嚴格的同 UUID 核對。

M-9（docs truth）由本筆結案 docs 處理：

- 根目錄 `README.md` 的 M5 段（:43 行末補親手關門才再出發；:45 第一句之後補永久封存的解除路徑），以及 `scripts/verification/README.md` 的 M7 段（新增「管理員說明：永久封存的解除與親手關門才再出發（T4A）」小節，接在既有的管理員說明之後）。兩處都只描述已實作、已驗證的行為；verification README 另註明回家（HOME 到 A）與 B 收尾之後的上鎖屬 T6、T7，還沒有實作。
- plan :511（M10 列）行末註記等價與 JUnit 殺手。
- V12 的 `/setblock` 以授權的直接寫方塊代替：記在本節「GREEN」小節；plan :477 改為勾選，行末寫明 :481 的「或」由 `applyTarget(CLOSED)` 成立，並指向這裡。
- 證據 root 改名 `t4a-v16-*`（避免覆寫 T4 Phase A 的唯一原件）：plan :519 行末註記。
- spec G 節的讀法連同 3(a) 的新讀法：spec G 節加第 7 點，下次回報時告知使用者。

### 下一步

1. 派 T4B（含上列 T4B Step 0），之後依序 T5～T8。這是 controller 的排序，不是使用者裁示。
2. 下次回報時，告知使用者 spec G 節第 7 點（待套用門座標核對的範圍讀法）。
3. main 合併、tag、人工代操作、worktree 退役、PR、LICENSE 仍各自另問使用者。

## 最新：spec v1.6 修訂（2026-11-18）

（已被上方 T4A CLOSED 節接續）下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored）。

- **狀態**：spec v1.6 與 plan 修訂已採用。
  - 一席獨立審查（S `spec-v16-review.md`）結論 C0／I2／M11；原作者依 controller 的處置做 fix1（見下方「審查與 fix1」）；原席窄複審 C0／I0／M5，原 I-1、I-2 與 11 項 Minor 全部 CLOSED，新的五項 Minor（N-1～N-5）由 controller 在同一筆修正。
  - spec 第 1 行標題改為 v1.6，狀態說明接在第 5 行行末，新節「2026-11-18 v1.6 遊玩條件組合裁示（依使用者裁示）」放在文末，前面各節行號不變。
  - plan 第 8 行的 spec 連結改為 v1.6，SHA-256 取 staged blob 計算。新節「2026-11-18 v1.6 修訂：新增 Task 4A／4B 與 T5～T8 追加項」放在檔尾；其他改動都是同一行改寫或行末附加，被程式、腳本與本 note 引用的 plan 行號（Task 4 :253～:274 等）沒有位移。
  - 設計草稿在 S `spec-v16-design-draft.md`，是 controller 的提案，不是使用者裁示。
- **依據**：
  - 2026-11-18 11:5x 第 1～3 題（題目、選項與選項說明原文見下方「遊玩條件組合分析與使用者裁示（2026-11-18）」節）；
  - 2026-11-18 12:4x 兩個子題（原文如下）。

### 使用者原文（2026-11-18 12:4x）

AskUserQuestion，題目與選項由 controller 撰寫，依據是設計草稿 §9；S 原文檔 `user-spec-v16-subquestions-ruling-20261118.md`。

- 第 1 題（header「B 斷電等待」）「從 B 回家的途中，如果 B 斷電或結構失效而進入等待，參與者在 B 開關門要不要算取消回程？」
  - 使用者選「照一般門（建議）」。選項說明原文：「比照原艙門的等待：門可以自由開關；恢復供電後，原參與者走出艙再走進來就繼續回家。想放棄這一趟，只能由家艙 A 的主人停用 A。不新增系統改門，改動較小。」
  - 未選：「算取消」。選項說明原文：「等待期間參與者關門也會取消回程，家艙立刻解除佔用。系統要在進入等待時把 B 門改成一般開門，原參與者才能走出再走進續行；外人開關一律拒絕。改動較大。」
- 第 2 題（header「其他返還」）「還沒選門就被系統整組送回艙內（例如隊友斷線、走廊容量或實體數上限、入場失敗）之後，如果艙仍有電、門關著、藥效還在，推測也會立刻再出發。要不要也改成要親手重新關門？」
  - 使用者選「不納入，記為已知行為（建議）」。選項說明原文：「M7 只改你上次說的兩類：回程到家、參與者關門取消。」
  - 未選：「一併納入」。選項說明原文：「所有系統返還之後，都要親手關門一次才再出發。會改到更多 M2／M6 已出貨的返還行為，測試也要多做。」
- controller 的執行說明（不是使用者裁示，S 原文檔同名段）：
  - 兩題都是設計草稿的建議項，草稿 §1～§4 的方案不必回修。
  - 第 2 題的「推測也會立刻再出發」未經實測，寫進已知行為清單時標為推論。

### v1.6 的內容

以下是 controller 依裁示寫成的技術契約摘要，細節以 spec 文末新節為準；選項說明沒寫到的細節都是 controller 的提案，不是使用者裁示。

- A 親手重新關門才再出發（11:5x 第 1 題）：
  - 每個端點的 Controller BE 有一個持久 latch。上鎖時機：M6 已出貨的 PREPARE 與通道兩類參與者關門取消、HOME 每次跨越到 A 之前、HOME 每次收尾改 B 門之前。
  - 只有玩家經正式門互動把整面門由開切成關才解鎖；上鎖時比較器 3，不出發。
- B 回程期間 B 門由 session 管（11:5x 第 2 題、12:4x 第 1 題）：
  - 擁有期間是 HOME 準備（含 A 預載與 HOME_CHECK_WAIT、B 的 PREPARE 膜）與未到期的通道。參與者關門即 PARTICIPANT_CLOSED；非參與者一律拒絕。
  - B 斷電等待照一般門（12:4x 第 1 題）。
  - 每 tick 的膜規則在擁有期間持續執行；HOME 準備暫停時比照 M5 交還一般開門（fix1）。
- C 回程收尾的 B 有界票（11:5x 第 3 題）：CLOSE 已 checked 而 B 未載入時，取 ≤4 refs、200 tick 的 B 收尾票，與 A 票合計 64 份／256 refs、共用 4096 coverage。取不到或不 FULL 就釋票、WARN、退避。
- D 永久封存的最小解除路徑（11:5x 第 3 題）：該艙所有列都是已 checked 的永久封存時，停用＋斷電讓 power state 到 OFF，沿既有創造模式拆除；封存列原樣保留、不歸檔、不刪。以原艙座標找到的舊列，只在 registry 仍登記同一個 Chamber UUID 時才有門權威，所以原地重建的新艙不受舊列影響（fix1）。
- E 範圍外：11:5x 第 3 題的 M8 與已知行為，加上 12:4x 第 2 題的已知行為（標為推論），也寫進本檔「不在 M7 範圍」節。

### 新 task 與排序

- 新增 Task 4A（latch 共用機制與 M6 修正，加上永久封存解除）與 Task 4B（B 收尾有界票），都是 production task，走完整門檻；T5～T8 各有追加項。內容見 plan 檔尾新節。
- 執行順序改為 T4 → T4A → T4B → T5 → T6 → T7 → T8。這是 controller 的排序，不是使用者裁示。
- 「殘留交 T5 Step 0」第 6 點依 v1.6 C 改寫（見該清單）。
- AccessDenied 單次重跑規則的範圍：11:5x 第 4 題的選項說明原文是「M7 剩下的 T5～T8 都照這條。」，提問時還沒有 T4A、T4B。controller 讀為「M7 剩下的所有 task」，含 T4A、T4B。這是 controller 的讀法，不是使用者裁示，會在下次回報時告知使用者。

### 審查與 fix1

- 審查：S `spec-v16-review.md`，一席獨立審查，C0／I2／M11。
- controller 的處置（不是使用者裁示）：
  - I-1（解除後原地重建，舊封存列以原艙座標綁上新艙的門）：採審查建議的第一種修法。
    - T4A 加窄修：以原艙座標找到的舊列（M5／M6／M7），必須在 registry 上仍對應同一個 Chamber UUID 才取得門權威，否則視為無主、不攔截、不代關；並補 RED／GREEN GameTest 與 mutation。
    - 保留「同位置可重建」。理由：使用者 11:5x 第 3 題選「永久封存的艙給一條最小解除路徑」；分析報告把這項放 M7 的理由是「沒有出口時一座艙報廢就永久佔地」。controller 據此推論：拆了卻不能原地重建，只解了一半。
  - I-2（B 擁有期間沒有 M5 的釋放機制，可能困人）：採審查建議。
    - 每 tick 的膜規則在所有擁有子階段持續執行；PREPARE 暫停時比照 M5 交還一般開門、攔截照一般門，只有 roll-forward 未確認才拒絕；T6 補對應 GameTest 與 mutation。
    - 這是「比照原艙門」的落實。暫停期間參與者關門不算取消（比照 M5），屬 controller 的讀法。
  - Minor 11 項全修，處置逐項見 S `spec-v16-docs-report.md` 的「fix1」節。
- 待使用者知悉的 controller 讀法（spec 文末新節 G 列出），會在下次回報時告知：
  - [U2] 的「回程期間」限縮為擁有期間；
  - HOME 準備暫停時 B 不擁有門，暫停期間參與者關門不算取消（HOME_CHECK_WAIT 不算暫停）；
  - HOME 期間 A Controller 可寫 latch 欄的窄例外；
  - [R3] 在永久封存時的例外；
  - 永久封存解除後保留「同位置可重建」，並加原艙座標門權威的窄修；
  - 進入 B 斷電等待時，B 門依 M5 既有規則交還一般開門。12:4x 第 1 題未選的選項 2 說明把這次改門寫成該選項的額外成本，這是 controller 提問時的描述不準確，會告知使用者（G 節第 6 點）；
  - 以及上一點 AccessDenied 規則涵蓋 T4A、T4B 的讀法。

### 下一步

1. spec v1.6 與 plan 修訂（含 fix1）送複審，C/I 清零後由 controller 填 spec 的審查結論與 plan 第 8 行的 SHA，再 commit、正常 push。
2. 之後派 T4A，再依序 T4B、T5（含 T5 Step 0 清單）。
   - 這個順序是 controller 的排序，不是使用者裁示。
3. main 合併、tag、人工代操作、worktree 退役、PR、LICENSE 仍各自另問使用者。

## 最新：T4 CLOSED（2026-11-18）

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`，V 指 `.superpowers/verification/`，兩者都 gitignored。本節的事實讀自 S 的 `STATUS.md`（11:5x、11:1x、09:15、09:1x、08:4x 五段）、`user-t4-full-accessdenied-ruling-20261118.md`、`user-gameplay-analysis-ruling-20261118.md`、`t4-full-fail-investigation.md`、`gameplay-combination-analysis-20261118.md`、`t4-accept-check/`、`t4-closeout-docs-report.md`、`t4-closeout-final-docs-review.md`，V 的兩個 run-all root，以及 GitHub Actions 的 `gh run view --json jobs` 讀回。

- **狀態**：T4 CLOSED。
  - code pin `e28784c`，是最後一個 code commit。docs HEAD `72b3445` 只改文件：`git diff --stat e28784c 72b3445` 只列 AGENTS、本 note、plan、spec、`scripts/verification/README.md` 五個檔。origin 的 `codex/m7-return-home` 讀回是 `72b3445`。
  - 完整回歸：第一次 FAIL，原判保留；依使用者 2026-11-18 09:14 第 1 題裁示換新 nonce 重跑一次，自然 `END ALL exit=0`，採計條件全部 PASS。
  - 同 SHA CI：44207705353（`72b3445`）attempt 1 十個 job 全部 success；code pin 的 44198806056（`e28784c`）attempt 1 也是十個 job 全部 success。
  - Phase A、Phase B 各段與 P1／P2 的審查都已清零，見下方歷史節。
  - T4 結案不代表 M7 完成。main 合併、tag、人工代操作、worktree 退役仍各自另問使用者。

### 完整回歸第一次 FAIL（原判保留）

- holder `S/t4-full-holder-41b8aaae9a7a48499e6d4d4e94a2f46a` exit 1。run-all root `V/run-all-c1442b78de7c4b72ae1713408aba13d3`，HEAD `72b3445`，08:25:50 開始。第一個 gate `full`（gate root `gate-full-da1cad0e…`）記 `END full FAIL GameTest gate exit=1`，沒有 `END ALL`。
  - default GameTest 第 10、16 案失敗；第 41 案執行中失敗後，T13 trusted owner 送出 STOP_REQUESTED，server 停服，沒有寫出 GameTest XML。所以第 10、16 案的確切失敗文字從原件取不到。
- 起點（證實）：第 10 案 `m2corridorgametests.native_shared_entry_preserves_frozen_relative_pose_east_windows` 的候選 checked 提交遇到真實的 Windows atomic move AccessDenied。
  - 簽章：`runtime-full.log:1806` 的 WARN「候選 checked 提交失敗 … journal checked 落盤失敗 …」，cause 是 `java.nio.file.AccessDeniedException`，目標是 `run\gametest\world\data\quantumchamber_sessions.dat.<nonce>.tmp -> quantumchamber_sessions.dat`。
  - production 照既有規則 fail-closed：CAS 還原、租約等待安全返還，原 session `77fd5639` 已安全移除。
- 連坐（因果鏈見調查報告第 2 節；第 10、16 案的確切失敗點是推論）：
  - 第 10 案：拉桿仍在高位，同一座艙立刻開出第二個 session `a10cb6c6`。測試 callback 仍拿第一個 session 的 SID 處理而失敗（推論最可能是 `未知 session`）；這條失敗路徑不收尾，`a10cb6c6` 一直留在 SUPERPOSITION（占走廊 slot 0、帶 344 個 M6 NEW 候選），兩個假玩家也留在線上，直到停服。
  - 第 16 案 `m4candidatedoorgametests.native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows`：testmod probe 以兩參數的 `SessionJournalStore.write` 寫整份 journal，這條路會拒絕帶 M6 NEW intent 的 snapshot（`M6 snapshot 缺少 server-bound validator`）。殘留的 `a10cb6c6` 讓 probe 寫不出（證實），推論是 `M4CandidateDoorGameTests.java:333` 的見證斷言失敗。
  - 第 41 案 `m2corridorgametests.trusted_pin_cap_includes_players_and_items_windows`：入場條件是全部 Space 為空，殘留的 Space 讓這個條件 10 秒內都不成立，以 `pin-cap原有限等待逾時` 失敗。
- 與 T4 改動的關係：調查找不到 T4 改動參與的證據。以下五項是證實，另兩項是推論：
  - 原生存檔沒有任何 journal 寫入失敗（log 搜「原生存檔」「回程收據」都是 0 筆），P2 的原生存檔路徑沒有觸發；
  - `M7NativeSaveFault` 沒有武裝，AccessDenied 的目標是真的 journal 檔，不含 `m7-t4p2-native-fault`；
  - HOME 的 B1 mixin 沒有經過，journal 始終是 schema 5、沒有 HOME 列；
  - 第 16 案依賴的 `REJECT_M6` 與第 10 案的測試收尾都是 M6 既有程式，M7 沒有改；
  - 同一份 code 在 CI 44198806056 windows-native 的 default GameTest 是 166/166 PASS。
  - 另有兩項是推論（其他 T4 production 與三案的路徑沒有交集；AccessDenied 本身找不到與 M7 的關聯），見調查報告第 3 節。
- 分類：已知類型（Windows atomic move AccessDenied 加上失敗連坐）。觸發點屬環境因素，持鎖的程序從原件無法證明；找不到 M7 改動造成真實缺陷的證據。
- 原 root 與 holder 都保留 FAIL，世界 `exactRestored=true`（S `STATUS.md` 09:15 段）。當時 M7 的 AccessDenied 額度 1/1 已用完，這次又混有連坐，M6 T14～T21 的單次重跑規則也不適用，所以 controller 停下交使用者裁示。

### 使用者原文（2026-11-18 09:14）

AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-t4-full-accessdenied-ruling-20261118.md`。原文檔的背景段是 controller 寫的說明，不是使用者原文。

- 第 1 題（header「T4 重跑」）「T4 完整回歸失敗的起點是 Windows 存檔時檔案被鎖住（AccessDenied），後面兩案是被它連帶拖垮的。M7 的重跑額度已經用完，要怎麼處理？」
  - 使用者選「換新 nonce 重跑一次（建議）」。選項說明原文：「保留失敗的原 root。起點是已知的環境問題，和 T4 改動無關；同一份程式碼在 CI 上兩次都通過。連帶失敗的測試缺口記下來，交給 T5 Step 0 處理。」
  - 未選：「先修測試缺口再重跑」、「先停在這裡」。
- 第 2 題（header「日後規則」）「M7 之後如果完整回歸又只遇到 AccessDenied（沒有其他失敗），要不要訂一條固定規則？」
  - 使用者選「比照 M6，每個 code pin 一次（建議）」。選項說明原文：「只限唯一失敗是 AccessDenied、沒有混其他失敗的情況。保留原 root，換新 nonce 重跑一次，不用再問；同一 pin 第二次發生就停下來回報。」
  - 未選：「維持每次都問」。
- controller 的執行說明（不是使用者裁示）：
  - 本次重跑用掉第 1 題授權的一次；這次若再 FAIL（任何簽章），就停下回報，不再重跑。
  - 第 2 題的規則從裁示時起適用於 M7 之後的完整回歸。本次 FAIL 混有連坐，不屬第 2 題範圍，是由第 1 題授權重跑。（「M7 之後」的範圍，使用者已在同日 11:5x 第 4 題確認，見下方「遊玩條件組合分析與使用者裁示」。）
  - 連坐缺口列入 T5 Step 0（見下方清單）。
  - 只改 docs 不重置同一 code pin 的次數（比照 M6 規則原文）。

### 重跑（依 09:14 第 1 題，換新 nonce）

- holder `S/t4-full-holder-d07e74ac8e3043689c0cc2f471edb1c9`：holder PID 21672、run-all PID 40576，01:15:51Z 開始，`CHILD_CODEPAGES=65001,65001,65001`，`exit.txt` 為 0。腳本副本的 SHA 與前次相同（S `STATUS.md` 09:15 段，`5AC80EDD…`）。
- run-all root `V/run-all-5c0458b73555465bafbade703b7df4c0`，HEAD `72b3445`。09:15:51 開始，11:04:24 自然 `END ALL exit=0 HEAD=72b3445154bff9cf6a5cfb5a710d0fb94e97407d`；console 沒有任何 `END … FAIL`。本筆讀回時 `console.log` 的 SHA-256 是 `0D5B9D2F…D0F7`，`summary.json` 是 `D7E0A44F…42C8`。
- `SUMMARY` 行（逐字）：

  ```
  SUMMARY default=166/166 windows=76 legacy=1/1 m5=60/60 m6=35/35 m6pace=37.8 normal=13/13 perTest=43+13+1 junit=1194 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=632entries/585classes sources=275 testmodHits=0 mainOnly=PASS m3=4+5 recovery=25 m5collapse=69 m6native=DIAGNOSTIC:2,FUNCTIONAL:25 ci=win:PASS,ubuntu:PASS m7native=COMPLETE:46 m7scale=PASS:11
  ```

- 其他關鍵行，都帶 `HEAD=72b3445…`：
  - `M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479`；
  - `M7_VERIFIED PASS platform=Windows junit=48 gametest=29`（launched True、False 各一行）；
  - `M7_SCALE_VERIFIED PASS platform=Windows junit=11`（launched True、False 各一行）；
  - `M7_HOME_VERIFIED_COMPLETE PASS rows=46 phases=92 negativeChecks=376 selection=all`。
- B2b-5 的 `m7-scale` step 與 `summary.json` 的 `m7HomeScale` 在這次第一次實跑；B2b-4 的 `m7HomeNative` 也在這次取得真實的 46 列 COMPLETE 正例。
- 這次 full gate log 裡的 `AccessDeniedException`，只出自刻意製造的案例（M5 H3 受控 readback、`m2_final_flush` 持 handle）。
- P2-M7 的提醒：run-all root 內各 gate 的 runtime log（`latest.log`、`runtime-*.log`），原生存檔 ERROR（「原生存檔（autosave、/save-all 或停服）的 journal 寫出失敗」）只出現在 M7 gate 的刻意注入案；default、legacy、M4 normal、M5、M6 GameTest 的 log 都是 0 則。M4 recovery、M5 collapse 與 M6／M7 原生列的 runtime log 不在 run-all root 內，本筆沒有核對。
- 原「下一步」第 2 點要另外確認的項目：
  - Phase A 審查指定由完整回歸涵蓋的共用 setter（`setEnabled`、`removeOrigin`），沒有造成任何 gate 失敗；
  - artifact oracle 是 `testmodHits=0`、`mainOnly=PASS`；`M7HomeFenceStartupProbe` 不進 release 或 main-only，是以這兩項判讀；
  - B1、B2a 的 mixin 與 probe 套用到所有 testmod run 之後，default、M5、M6 GameTest、M4 recovery、M5 collapse、M6 native、main-only 都通過。

### 採計條件

- 條件來源：S `t4b2b3-review.md`「B2b-4 審查」第 2 節，加上「B2b-5 審查」第 4 節的延伸。
- controller 以唯讀腳本逐項核對，25 項全 PASS（`ACCEPT_FAILS=0`），另 controller 唯讀 `-VerifySummary -RequireComplete -SelfTest` 一次（見本節最後一點）。原件在 S `t4-accept-check/`：`t4-accept-check.ps1`（SHA-256 `e9d08ca3cead9d64…`）、`t4-accept-check-result.json`（SHA-256 `00134520aeb109ac…`）。核對內容（`<pin>` 指 run-all 的 HEAD `72b3445`，它的 code 與 `e28784c` 相同，只多 docs）：
  - 乾淨 code pin：console 第 2 行 `STATUS ` 後面是空的，tracked tree 乾淨。這一項由 controller 讀 console 確認，不在 25 項腳本內；
  - `END ALL exit=0 HEAD=<pin>` 恰一行，沒有 `END … FAIL`；
  - `m7HomeNative` 為 COMPLETE、46 列、92 phase，記錄的 SHA 與重算一致（`m7-home-run-59ae9ca48825.json`，`F1AB34D4…AE5F`）；
  - console 有 `HEAD=<pin>` 的 46 列 COMPLETE verify 行、`END m7-home-native exit=0`、`negativeChecks=376` 的 COMPLETE 行；
  - `SUMMARY` 行尾有 `m7native=COMPLETE:46` 與 `m7scale=PASS:11`；
  - `END m7-home exit=0`，`M7_VERIFIED PASS … junit=48 gametest=29`；
  - `END m7-scale exit=0`，launched 與 VerifyOnly 兩行 `M7_SCALE_VERIFIED PASS`；
  - `m7HomeScale` 的 scope、status、head 相符，11 案等於 inventory 的 `scale.portable.junit`；`m7Home.junitCases` 恰 48 個、不含 `M7HomeHistoryScaleTest`，兩者聯集 59；
  - 規模段的 `execution.json` 為 600／90、`phaseDeadline` 為空，`command` 只有一個 `--tests`；M7 gate 的 `execution.json` 不含規模測試。
- controller 另對原生 summary 唯讀跑 `-VerifySummary -RequireComplete -SelfTest`：`M7_HOME_VERIFIED_COMPLETE PASS rows=46 phases=92 negativeChecks=376`，前後 hash 不變，tracked tree 乾淨（S `STATUS.md` 11:1x 段）。

### 同 SHA CI

本筆以 `gh -R Ben513242/QuantumChamber run view <id> --json jobs` 讀回。

| run | head | 結果 | 說明 |
| --- | --- | --- | --- |
| 44207705353 | `72b3445` | attempt 1，event push，十個 job 全部 success | build、m7-validation（Ubuntu、Windows）、m7-home-native 四片（ordinary、sealed、passable、restart-receipt）、m7-home-native-aggregate、m6-native、windows-native。最晚完成的是 windows-native（00:25:37Z→01:11:40Z） |
| 44198806056 | `e28784c` | attempt 1，event push，十個 job 全部 success | code pin 本身的 CI，耗時見下方歷史節的 CI 表 |

### plan Task 4 核取框

判定規則（controller 的派工要求，不是使用者裁示）：完全達成才勾；有偏離（做法改變、項目交後續、依裁示取消），或找不到具名測試、原件證實的子項，就不勾，在該行末註明。12 項中勾 4 項（:263、:267、:268、:272），8 項不勾：

- :253 RED：`permanent_seal_cannot_be_archived` 的 RED 不成立（T2 的 `HomeSessionRecord` 已拒絕），改以 JUnit `permanentSealCannotBeArchived` 守門加 Phase A M4 mutation；`restart-enable-before-entry` 的跨 JVM 部分以 B2a 原生列加 rm1、rm2 mutation 證明，不是在舊 production 上取得的 RED。
- :255：「UNKNOWN 不 mint ROOT」只有程式（`HomeRootFence.java:116`），Phase A 審查只概括寫「開服 fence 核實」、沒有點名 UNKNOWN 分支，也沒有具名測試；開服掃描遇到 destroyed 與 exact 撤銷的分支也沒有具名測試（disabled 有 B2a 原生主鏈）。
- :257：「結構觀測」的通知沒有 production 呼叫端（Phase A 審查 M6，交 T5／T6）。
- :259：「普通缺載 B 保持 pending」在 T4 以測試票保持 B 的區塊（B1 假設 (c)），要等 T5 以正式 B 來源票重驗。
- :261：ENTRY_STOPPED 的 LOW／INVALID 正式 mint 路徑沒有呼叫端（B 端在 T6），沒有 GameTest 驅動與 mutation。
- :265：原生列以測試票保持 B 的區塊，T5 要以真票重驗。
- :270：「再操作 B」以 journal 的 MEASURED→COLLAPSING 提交邊代表（B2a-M3，交 T5／T6）。
- :274：沒有用候選 commit `feat: 持久完成回程終局並重建停用防線`，改成各段分開 commit；其餘結案條件都已完成。

勾選項的具名證據：

- :263：GT `ordinary_closed_door_needs_native_checkpoint`、`sealed_door_closes_out_as_ordinary_open`、`passable_door_closes_out_as_ordinary_open`、`participant_close_keeps_door_open_when_doorway_occupied`、`participant_close_closes_door_when_doorway_clear`、`checkpoint_failure_keeps_close_and_lock`、`existing_receipt_mismatch_keeps_lock`、`published_receipt_mismatch_keeps_lock`、`ownerless_membrane_yields_to_home_busy`；原生 39 列的交接順序斷言（checkpoint 當下 CLOSE 已凍結、門已是 OPEN/NONE，S `t4b1-review.md` §2）、B 門 chunk 的 `M7_HOME_CHECKPOINT_DOOR_CHUNKS`、原生列 `receipt-player-closed`。
- :267：S `t4b1-report.md`，runner 沿用 `probe-common.ps1`，PID＋啟動時間、receipt hash、VerifySummary＋SelfTest（319 個負控）。「沒有 T1 PoC、不另做退休提交或 CI」見本 note 歷史節 T1 收斂裁示；「既有 M6 標準不改」：T4 範圍內 `probe-common.ps1` 與 `m6-*` 腳本沒有 commit，重跑 console 仍是 `M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479`。
- :268：該行既有註記寫明完整回歸與 CI 通過後才勾，條件已成立。
- :272：六項各有 KILLED 並逐位元還原的 direct mutation，gate root 與 XML 見 S `t4b2a-report.md` 階段 4b。

### 殘留交 T5 Step 0

T5 Step 0 要處理：

1. 連坐缺口兩處，都是 M6 既有的 testmod 缺口；依 09:14 第 1 題選項說明「連帶失敗的測試缺口記下來，交給 T5 Step 0 處理」。修法由 T5 Step 0 決定，本筆不預設。
   - `sharedEntry` 外層的失敗不收尾：`M2CorridorGameTests.java:1979-2002` 的 `when(… fixture::activeReady, publishedTick -> {…})` callback 沒有 try/catch，只有內層 `runAtTick(publishedTick+25)` 失敗時會 `fixture.close()`；8-H 的 NativeEntry 自清只涵蓋等待逾時，不涵蓋斷言失敗或例外。
   - readback probe 受其他案例殘留影響：`M4CandidateDoorGameTests.java:333` 一帶。probe 以兩參數的 `SessionJournalStore.write` 寫整份 journal，遇到他案殘留的 M6 列就丟例外；失敗後只走 `hooks.releaseAfter`，沒有 `fixture.close()`。
2. B2b5-M1：M7 JUnit 的宣告掃描只認 `@Test void`。只用其他寫法、又沒列進 inventory 的新 `M7*Test` 類別，兩段都不會執行；要補類別層級的核對與負例。
3. B2b5-M2：CI 規模段的新 step 沒有 `timeout-minutes`，Main 失敗時規模段會被連帶跳過。審查建議執行 step 20 分、驗證 step 10 分，要互不連坐可加 `if: ${{ !cancelled() }}`。
4. `SessionRecoveryState.java:1027` 的註解寫「T4 P2 使用者裁示 2」，指執行緒與 owner 守門照舊丟出。這其實是 controller 的決定，要改正歸屬（見下方歷史節「production 註解的歸屬」）。
5. B2b2-M1：SelfTest 上限 479 的餘量策略。門態組合目前是 426，每多一列原生多 8 個逐列負控，約第 53 列會超過；T5～T8 加原生列前要先定策略，需要超過 479 時交使用者裁示。

原 note「帶到後續」已交 T5（或 T5／T6）的項目，T5 開工時一併處理或決定（原文見下方歷史節，不刪）：

6. B1 假設 (c)：T5 有正式 B 來源票之後，以真票重驗，並確認重開時 B 無人無票會保持 pending（spec §5.3 :173）。
   - （2026-11-18 起本點前句已被 spec v1.6 C 取代，舊句保留為歷史。）重開時 B 無人無票的收尾，改由 Task 4B 的 B 收尾有界票載入 B 並完成，不再期待 pending；票期內仍未就緒才 pending 並退避。T5 只以正式 B 來源票重驗 COLLAPSING／ARMING 的開服返還（plan 檔尾「2026-11-18 v1.6 修訂」節的 Task 5 追加項）。這個改寫依 11:5x 第 3 題，具體做法是 controller 的設計，不是使用者裁示。
7. B2a-M3：`restart-enable-before-entry` 的「再操作 B」在 T5／T6 有正式 B 端續行後，以真實 B 端操作重跑；是否補進 plan Task 5／6 的核取項，T5 開工時決定。
8. Phase A 審查 M6：選定後重驗 A 時，要呼叫 `checkRoot` 與 `onRootEvent(INVALID_STRUCTURE/IDENTITY_MISMATCH)`（交 T5／T6）。
9. 呼叫端持有同 SID guard 的限制（交 T5／T6）：T6 的 B 門攔截不得包在同 SID 的 guard 內，GameTest 要走真實的門路徑；`onEntryStopped` 的 LOW／INVALID 正式 mint 路徑目前沒有 GameTest 驅動與 mutation。
10. Phase A 審查 M7：每個 Projection 案例都物化 U 而且不卸載，後續測試不得斷言絕對世界數。

原本交 T5 Step 0 的 B2b4-M1 已在 B2b-5 處理，不再列入。

### 計數

- AccessDenied：改用 09:14 第 2 題的新規則。只限完整回歸唯一失敗是 Windows atomic move AccessDenied、沒有混其他失敗，且 production 仍 fail-closed／退避（這個前提依 M6 原文補上，是 controller 的解讀）；保留原 root，換新 nonce 重跑一次，不用再問；同一 code pin 第二次就停下回報。混有其他 FAIL 不適用，停下回報。
  - 範圍：使用者 11:5x 第 4 題選「M7 從現在起適用（建議）」，選項說明原文「M7 剩下的 T5～T8 都照這條。」（題目原文見下方「遊玩條件組合分析與使用者裁示」）。
  - 其餘條款（不授權改 production／OS／oracle、不豁免完整回歸與同 SHA CI、只改 docs 不重置次數）比照 M6 T14–T21 規則原文；這是 controller 對「比照 M6」的解讀（S `user-gameplay-analysis-ruling-20261118.md` 的 controller 執行說明第 4 點），不是使用者裁示。
  - `e28784c`（controller 的決定，不是使用者裁示）：它的 AccessDenied 已由 09:14 第 1 題授權的那次重跑處理；同一 pin 若再發生，視為第二次、停下回報。之後的新 code pin 各自一次。
  - 規則全文見 AGENTS「M7 Windows atomic-move AccessDenied 單次重跑規則（使用者 2026-11-18）」。下方歷史節與 T2、T3 各節記的舊規則（M7 AccessDenied 額度 1/1 已用完、不刷新，再遇到就停下來問使用者）保留為當時的規則，已被新規則取代。
- 完整回歸啟動器歷史 2/3 不變。第一次 FAIL 是 runtime 的 AccessDenied 與連坐，不是啟動器問題（S `STATUS.md` 08:4x 段；該段「這不是 AccessDenied」已在 09:1x 段更正）。
- `participantCloses` fixture 與 T3 `only_exact` fixture：沒有新的非預期失敗。第一次完整回歸停在 full gate，沒有跑到 M7 gate；重跑的 M7 gate 與兩次 CI 的 m7-validation 都 PASS。兩者的規則照舊。

### 遊玩條件組合分析與使用者裁示（2026-11-18）

- 分析：使用者 2026-11-17 10:17 要求的只讀分析（原文見下方歷史節「使用者原文」），報告在 S `gameplay-combination-analysis-20261118.md`（HEAD `72b3445`）。
- controller 抽查證實兩項（S `user-gameplay-analysis-ruling-20261118.md` 背景段）：
  - 永久 SEALED 的艙拆不掉：`SuperpositionSessionManager.java:115`、`ChamberPowerCoordinator.java:212-216`、`ChamberLifecycleService.java:32`；
  - 停用沒有權限檢查：`ChamberMaintenanceInteraction.java:77-91`。
  - 報告中其餘標「推論」的項目沒有實機確認。
- 使用者裁示（2026-11-18 11:5x，AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-gameplay-analysis-ruling-20261118.md`）：
  - 第 1 題（header「再出發」）「回程到家、或參與者關門取消之後，如果艙仍有電、門關著、玩家藥效還在，現在會立刻再出發。要改成怎樣？」
    - 使用者選「要玩家親手重新關門（建議）」。選項說明原文：「家艙 A 和投影端 B 一致：系統代關的門不算，要有玩家親手關門一次才會再出發。同時修正 M6 已出貨的「取消後立刻再出發」。放 M7，改動小。」
    - 未選：「要斷電再供電一次」、「維持現狀」。
  - 第 2 題（header「B 門歸屬」）「回程進行中（從投影端 B 出發回家），B 的門現在任何人都能開關。要怎麼管？」
    - 使用者選「比照原艙門（建議）」。選項說明原文：「回程期間由 session 管：參與者在 B 關門就是取消回程（包括等家艙載入的期間），非參與者開關一律拒絕。」
    - 未選：「只擋外人」、「照一般門」。
  - 第 3 題（header「其餘分類」）「分析的其餘項目，照建議分類處理嗎？」
    - 使用者選「照建議分類（建議）」。選項說明原文：「M7 加兩項：回程收尾時替 B 取有界票（避免全員回家後家艙永遠鎖住）；永久封存的艙給一條最小解除路徑。停用與拆除權限、離線參與者、原地重建艙、等待上限與狀態提示放 M8。PREPARE 無限等待、投影清空容器、不調整 Y 只記為已知行為。」
    - 未選：「M7 只加替 B 取票」、「這兩項也延到 M8」。
  - 第 4 題（header「規則範圍」）「剛才訂的 AccessDenied 規則，我題目寫「M7 之後」，有兩種讀法。你指的是哪個？」
    - 使用者選「M7 從現在起適用（建議）」。選項說明原文：「M7 剩下的 T5～T8 都照這條。」
    - 未選：「M7 結束後才適用」。
- controller 的執行說明（不是使用者裁示）：
  - 第 1～3 題會改變 M7 範圍：先修 spec（v1.6）與 plan，經獨立審查 C/I 清零，才派 T5。具體設計（重新關門的判定、B 門攔截的範圍、B 取票的上限、永久 SEALED 最小解除路徑的形式）由 controller 依裁示起草，審查時逐一對照選項說明。
  - 第 1 題要修 M6 已出貨行為，屬 production 改動，走完整門檻。
  - 第 3 題列為放 M8 與只記為已知行為的項目，會寫進 M7 note 與 spec 的範圍外清單，M7 不實作。
  - 第 4 題的落地見上方「計數」。
- 本筆沒有改 spec，也沒有改 plan 的任務內容，第 3 題的範圍外清單也還沒寫進本 note 的「不在 M7 範圍」節；這些在下一步的 spec v1.6 與 plan 修訂一起處理。（已被上方「最新：spec v1.6 修訂（2026-11-18）」節取代：spec v1.6、plan 修訂與「不在 M7 範圍」節的清單都已寫入工作樹。本句原文保留。）

### 下一步

（本節已被上方「最新：spec v1.6 修訂（2026-11-18）」節的「下一步」取代：派 T5 之前先做 T4A、T4B。下列原文保留為當時的狀態。）

1. 本筆結案 docs 的一席文件審查；清零後 commit、正常 push。這筆 docs commit 的 CI 終局先記在 S，下次文件提交收錄，不為寫回 CI 另外觸發 CI。
2. 依 11:5x 第 1～3 題修 spec v1.6 與 plan，經獨立審查 C/I 清零。
3. 之後才派 T5（含上方的 T5 Step 0 清單），依修訂後的 plan Task 5 實作。
   - 先修 spec 與 plan、再派 T5，是 controller 的排序，不是使用者裁示。
4. main 合併、tag、人工代操作、worktree 退役、PR、LICENSE 仍各自另問使用者。

## 歷史：T4 Phase B 與 P1／P2 完成，待完整回歸與同 SHA CI（2026-11-17～18）

下文 S 指 SDD 目錄 `.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored，是本節數字與 SHA 的唯一來源，見 `S/STATUS.md` 與各 `t4*-report.md`、`t4*-review.md`）。

- **狀態**：
  - Phase B 的各子段（B0、B1、B2a、B2b-1～B2b-5）與 Phase B 期間插入的兩輪 production 修正（P1、P2）都已實作並 push。各段一席審查提出的 Important（B2a 審查的 P1、B2b-2 審查的 I-1）都已由後續修正輪關閉，目前沒有未關閉的 Critical／Important；B2b-5 的一席審查 C0/I0/M2。HEAD `e28784c`。
  - T4 還沒結案：最終 SHA 的完整回歸還沒跑，最終 SHA 的 CI 還沒全綠。`926425c` 的 CI 44117707536 十個 job 全部 success；`e28784c` 的 CI 44198806056 十個 job 全部 success。這兩次都是過程中的 CI，T4 結案以最終 SHA 的 CI 為準。
- **切分**（controller 的決定，不是使用者裁示）：Phase B 拆成 B0、B1、B2a、B2b-1～B2b-5；P1、P2 是審查發現 production 問題後，controller 插入的修正輪。B2b-5 是 2026-11-18 規模測試裁示之後開的小修。

### 使用者原文（2026-11-17～18）

**10:17 遊玩組合分析要求**（主對話的真實使用者訊息；S 原文檔 `user-gameplay-combination-analysis-request-20261117.md`）：

> 收到問題回答分析，到時候需要完整審查遊玩時候的不同條件組合會有甚麼結果的分析，看有沒有隱式狀態機等等，可加入M7 M8 玩法設計

- 背景：使用者先問了兩個 M6 打包版（main `2255027`）的多人情境，controller 派只讀子代理查證後回覆兩題都可以，另附四個風險點（無限期等待、投影覆寫地形與清空容器、投影與落點不調整 Y、兩種永久 SEALED 沒有管理員解除工具；檔案:行號見原文檔）。這段回覆是 controller 的查證，不是使用者裁示。
- controller 的處置（不是使用者裁示）：
  - 排成獨立的只讀分析任務；範圍是多人遊玩下的條件組合與結果、隱式狀態機，以及會永久卡住、無限等待或吃掉玩家東西的組合，以上面四點為起點；
  - 產出條件組合、結果與風險的報告，每項附檔案:行號，分開標出程式證實與推論，之後交使用者裁示哪些放 M7、哪些放 M8、哪些只記成已知行為；
  - 建議時機是 T4 結案後、T5 開工前，因為 T5～T7 會實作入場、通道與提示。**使用者還沒回覆時機。**

**11:0x CI 44003465357 兩項失敗的處置**（AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-t4b-ci-rulings-20261117.md`）：

- 第 1 題「Ubuntu 上「參與者關門」測試偶發卡住：B 門口 chunk 的方塊已載入，entity storage 卻 200 個 tick 都沒載入，收尾一直等它（正常只用 1～3 tick）。這組測試已第二次非預期失敗，要怎麼處理？」
  - 使用者選「測試先等 B 區塊就緒（建議）」。選項說明原文：「關門前先有界等待 B 門口 chunk 的方塊與 entity 都載入，等不到就明確標成「環境未就緒」；關門後仍照原本 200 tick 驗收。只改測試，正式程式不動，驗收標準不放寬。」
  - 未選：「收尾等待放寬到 1200 tick」、「先不修，再收集一次資料」。
- 第 2 題「Windows 的 M7 驗證全部通過，但整段（編譯、JUnit、GameTest、封裝）超過 180 秒總時限；CI 三次分別跑了 115、142、235 秒，T5～T8 還會加測試。要怎麼調？」
  - 使用者選「總時限改 600 秒（建議）」。選項說明原文：「只放寬 M7 這一段的總時限，90 秒無輸出就判卡住的規則不變。依 CI 實測最慢 235 秒，留給 T5～T8 新增測試的空間。」
  - 未選：「拆成兩段各 180 秒」、「維持 180 秒，縮短測試」。
- controller 的落地（不是使用者裁示）：
  - 第 1 題：只改測試，在觸發收尾之前有界等待 B 的 `homeChunks` 方塊與 entity 都已載入、`doorwayEntitiesLoaded` 為真，等不到就丟 `ENV_NOT_READY` 並附 thresholds；收尾等待仍是 200 tick，斷言不變。涵蓋 `participantCloses` 兩案、N1 案，以及其他同樣經 B 門 converge 的收尾案。修正後同 fixture 再有任何非預期失敗，就停下來問使用者。
  - 第 2 題：只改 `gates.ps1:470` M7Runtime 的總時限 180→600；idle 90 不變，其他 mode 的時限不動。
  - 兩項都只改測試或腳本，走 B+C（一席審查、定向驗證、CI）。

**15:4x 原生存檔遇到 journal 寫入失敗的處置，與 17:1x M4 追加題**（AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-t4-native-save-ruling-20261117.md`）：

- 題目「M5 就有的 Windows AccessDenied（防毒或索引短暫鎖檔）如果剛好發生在 autosave 或停服，會讓伺服器崩潰，遺失 level.dat 與部分存檔資料（chunk 不會遺失）。P1 剛做好的機制可以延伸到所有 journal 寫入失敗。要怎麼處理？」
  - 使用者選「M7 一併修（建議）」。選項說明原文：「在 T4 加一個小的正式修正：原生存檔遇到任何 journal 寫入失敗都不丟進原版存檔流程，保持未寫出的狀態、記 ERROR，交給既有的重試者收斂。伺服器不崩潰，其他資料照常存。走完整門檻，約多一輪實作與審查。」
  - 未選：「記成已知限制，之後再處理」、「排進 M8」。
- 追加題（17:1x）「M4 有一個驗收測試把「停服時崩潰」當成預期（上一筆 journal 寫入結果未確認、本次必須重啟才能收斂的情況）。照你「任何 journal 寫入失敗都不崩潰」的裁示，這類改成：伺服器繼續運作、量子艙 journal 停止寫入並記 ERROR，等管理員重啟後收斂；停服正常完成、level.dat 會存到。這樣 M4 那個測試要跟著改。怎麼定？」
  - 使用者選「改 M4 測試，一律不崩潰（建議）」。選項說明原文：「更新 M4 的驗收測試與 run-all 檢查為新行為：原生存檔不丟出、仍不寫入、ERROR 恰一則、停服正常完成；重啟後的收斂驗證照舊。測試標準不放寬，只是預期行為從「崩潰」改成「不崩潰且不寫」。」
  - 未選：「這類維持崩潰」。
- 原文檔的背景段（P1 RED 的實證、2026-10-21 AccessDenied 裁示的脈絡）是 controller 寫的說明，不是使用者原文。
- controller 的落地（不是使用者裁示）：
  - 在 T4 開 production 修正輪 P2，走完整門檻（具名 RED、GREEN、mutation、focused；完整回歸與同 SHA CI 在 T4 結案時一起跑）。
  - 原生存檔遇到任何 journal 寫入失敗都不丟進原版鏈；保持 M5 既有的 pending／dirty 語意、不覆寫未確認的寫入、記 ERROR，由既有重試者收斂；checked 路徑不變；不新增 production 短暫重試，不更動 Defender 或索引設定（沿 2026-10-21 裁示）。
  - `requireMutationThread` 等執行緒與 owner 守門維持丟出：它們是程式錯誤，不在這題範圍內。
  - M4 oracle 的修改也納入 RED、GREEN、mutation；既有 JUnit 改用 `checkedSave` 時附對照表（見下方 P2）。
  - P1-M1（ERROR 診斷：路徑、處理方式、以 SID＋類別去重）併入 P2。
- spec v1.5 的文末節「2026-11-17 T4 原生存檔失敗不拖垮伺服器（依使用者裁示）」是 controller 依此裁示寫成的技術契約，使用者原文以本節為準。

**2026-11-18 06:3x >4096 規模測試的時限**（AskUserQuestion，題目與選項由 controller 撰寫；S 原文檔 `user-t4-scale-test-ruling-20261118.md`）：

- 背景（controller 整理的事實，不是使用者原文）：CI 44117707536（head `926425c`）十個 job 全部 success，但 M7 Windows 的「執行 M7 獨立 GameTest」step 耗時 494 秒（前一次 CI 44107563893 是 491 秒）。artifact `S/ci-44117707536/windows-artifact/` 的拆解：`M7HomeHistoryScaleTest` 204.456 秒（本機約 28 秒）、其他 M7 JUnit 合計約 15 秒、GameTest 177.4 秒。
- 題目「「超過 4096 趟回程」的規模測試在 Windows CI 要 204 秒（本機 28 秒），讓 M7 驗證整段跑到約 490 秒，離你定的 600 秒只剩約 100 秒；T5～T8 加測試後一定會超過。要怎麼處理？」
  - 使用者選「規模測試獨立一段（建議）」。選項說明原文：「把這個測試從 M7 驗證中拆出來，另外一次執行、自己的 600 秒總時限，90 秒無輸出就判卡住的規則照舊。M7 驗證回到約 290 秒；本機完整回歸與兩個平台的 CI 都照樣跑它，測試內容不變。要多一輪小修。」
  - 未選：「維持現狀，T5 前再處理」、「只在 Ubuntu 跑規模測試」。
- controller 的落地（不是使用者裁示）：
  - 開一輪小修 B2b-5，只改測試、腳本與 workflow，走 B+C。
  - M7 gate 不再執行 `M7HomeHistoryScaleTest`；規模測試另立一段，用自己的 Gradle 呼叫，總時限 600 秒、idle 90 秒。
  - 本機完整回歸（run-all）與 Windows、Ubuntu 兩平台的 CI 都執行規模段，並納入 exact 集合；測試內容不變。
  - B2b4-M1（鎖定 86 個 strict 趟）在同一輪一併處理。

### Phase B 各段

每段列範圍、主要 commit、一席審查結論（spec 與 quality 同席）。證據路徑、SHA 與完整數字在 S 的對應報告。

- **B0**（N1、N2；`7acc564`，另有 controller 追加的 Ubuntu 診斷 `773f709`；S `t4b0-report.md`）：
  - N1：呼叫端自己持有同 SID 的 guard 時，`HomeClosure.onParticipantClose`／`onEntryStopped` 的回傳值與不寫入的行為不變，另記一則 WARN（`LOG_MARKER`、`session=`，以 SID＋入口去重）。新 GT `caller_held_guard_refuses_participant_close_and_entry_stop`；n1a、n1b 兩個 mutation KILLED。
  - N2：兩個 GT 守住 HOME 重試退避（寫出 tick 依序 T、T+200、T+600）。m1 KILLED。m2（拿掉 `SessionRecoveryManager` :201 的同 tick 判斷）SURVIVED：:201 只有在 :196 `ready(now)` 為真時才會執行，同一 tick 呼叫 `failed(now)` 之後 ready 必為假。審查員獨立確認是等價突變，唯一例外是 `getTicks()==Integer.MIN_VALUE` 的 int 溢位（連續運轉約 3.4 年），實務上到不了。記為等價突變、production 不改，是 controller 的決定（不是使用者裁示）。
  - `773f709` 只改 `participantCloses` 逾時訊息，附上各門檻當下的值，不改上限與斷言。
  - 審查（S `t4a-review.md`「B0 增量審查」節）：spec ✅、quality Approved，C0/I0/M1。Minor N3 併入下一段。
- **等待餘量記錄與 N3**（`27415b4`，只改兩個 testmod 檔）：
  - `until` 記一行 `M7_GT_WAIT used=<n> bound=<n> case=<label>`，逾時另加 `timeout=true`；N3 在 N1 新案的逾時訊息補上 thresholds。bound、斷言、tickLimit、案例集合都不變。
  - 這次也改到 T3 fixture `M7HomeAuthorityGameTests:284`，只在 `until` 加 label，上限 200 不變。
  - 判斷是否逾時要看 `timeout=true`：條件在最後一次檢查才成立時，也會記 used=bound。
  - 審查（S `t4a-review.md`「等待餘量記錄審查」節）：C0/I0/M0。
- **前置等待與 M7 總時限**（`e652c23`，依 11:0x 兩題裁示）：
  - `M7HomeClosureGameTests.whenEntryReady` 套用 9 案（經 B 門 converge 收尾、使用 `Projection` 的案例），等不到就丟 `ENV_NOT_READY` 附 thresholds；6 案不適用（沒有 Projection 或 B converge，或收尾離 Projection 建立至少 601 tick）。
  - 前置等待上限：一般 380、queue 360、root_disable 280，由原 tickLimit 扣掉之後的等待算出（380＋200=580 < 600；280＋3×200=880 < 900），收尾逾時仍以本身的具名訊息出現。這組上限是 implementer 的落地，經審查接受，採用是 controller 的決定（不是使用者裁示）。
  - `gates.ps1:470` M7Runtime 總時限 180→600，idle 90 不變。
  - 審查（S `t4a-review.md`「e652c23 審查」節）：C0/I0/M1。Minor N4 的處置見下方「已知界線」。
- **B1**（`aa12ace`、`8c7c08f`、`4f4128e`；S `t4b1-report.md`、`t4b1-review.md`）：
  - Windows 原生 close 矩陣 39 列 `close-{ordinary|sealed|passable}-{cut}`：runner `scripts/verification/m7-home-probe.ps1`、testmod probe `M7HomeProbe`／`M7HomeClosurePhases`、cut 點 mixin、`build.gradle` 的 `m7HomeProbe` run。
  - 乾淨 HEAD `4f4128e`：39 列、78 phase 全 PASS（crash 都是預定的 halt 77，reopen 新 JVM 都正常 exit 0）；summary SHA-256 `b552967b…4f08`；`-VerifySummary` PASS，`-SelfTest` 319 個負控都以預期錯因被拒。
  - 耗時 1589.8 秒（約 26.5 分），每列平均 40.8 秒。
  - 兩個 mutation（close 前提省略 checkpoint、先刪 active 再發 receipt）各被 3 列原生列殺掉。殺掉的證據引用各列 summary 的 `failure` 欄，不引用 mutation 驅動的 `results-*.json`（它只看 exit≠0，summary 路徑又被空白截斷；B1 審查 M1）。
  - 三個 fixture 假設：(a) 結案原因固定 ROOT_DISABLED（DoorGoal=OPEN）；(b) 基線存檔放在停用 A 之後；(c) B 的 chunks 以測試票保持載入（正式 B 來源票到 T5 才有）。審查判定都成立、不需使用者裁示，由 controller 確認。
  - 非預期失敗 1 次，屬驗證工具：probe 寫死的 sealed／passable 初始門摘要軸向錯（`_Z`→`_X`），原 root 保留。
  - 審查：C0/I0/M8。M4 併入 B2b；M2、M5～M8 排進 B2a Step 0；M3 的界線見下方。
- **B2a**（Step 0 `c58c129`、主體 `fca6d31`；S `t4b2a-report.md`、`t4b2a-review.md`）：
  - Step 0 處理 B1 審查的 M2、M5～M8（B 門 25 格所在 chunk 都在 checkpoint relevant 集合內的獨立斷言、注入點 `allow=1`、mixin 拆檔成 `M7HomeReceiptWindowMixin.java`、路徑長度預檢、用詞）。
  - 新原生列 5 列：`restart-enable-before-entry`、`restart-ready-stop-crossed`、`restart-ready-low-no-root`、`receipt-existing`、`receipt-player-closed`。新同 JVM GT 3 個：`checkpoint_failure_keeps_close_and_lock`、`existing_receipt_mismatch_keeps_lock`、`published_receipt_mismatch_keeps_lock`。
  - 乾淨 HEAD `fca6d31`：restart 3 列 PASS（119.2 秒）、receipt 2 列 PASS（83.5 秒）、close 39 列重跑 PASS（1483.3 秒）；三份 summary 的 VerifySummary 與 SelfTest 都 PASS；M7 gate junit 35、gametest 27。
  - 五個假設（controller 的轉述，原文見 S `t4b2a-report.md` 階段 2）：(1) startup 先 direct enable 的時點放在開服掃描之前；(2) 以持有同 SID guard 製造 registry 已落 disabled、stop 未落的窗口；(3) 再操作 B 以正式 journal 入口的 MEASURED→COLLAPSING 續行嘗試代表；(4) 以 journal flush 故障製造 receipt 已發布、active 移除失敗；(5) 以 gamerule `spawnChunkRadius=0` 觀察不等 A FULL。審查判定都成立、不需使用者裁示；(3) 要列為 T5／T6 待辦（B2a-M3）。
  - plan Task 4「變異掃描」核取項的六項都有對應的殺手：
    - 僅有 runtime：Phase A fix1 M6＋本段 rm1；
    - 略過 direct setter：fix1 M5（停用通知）＋rm2（啟用閘門）；
    - close 前提省略 checkpoint：fix1 M7、B1 m1、本段 gm1；
    - 先刪 active 再發 receipt：fix1 M8、B1 m2；
    - 漏核 existing exact：fix1 M9（JUnit，行為層）＋本段 mr1（原生列只能以事件序列缺 `verify-existing` 殺，證明力較弱）；
    - TRR 允許 ROOT close：Phase A M4（JUnit `permanentSealCannotBeArchived`）。
  - 非預期失敗 1 次，屬驗證工具：reopen 重建 entry 時把 `UniverseId[value=…]` 當成 UUID 解析，原件保留。
  - 審查：B2a 本身 C0/I0/M6；另有 production 觀察 P1（Important），合計 C0/I1/M6。P1 的處置見下一段。
- **P1**（production `c9ef60f`、`6926e93`、`73132bc`；S `task-4p1-brief.md`、`t4p1-report.md`，審查在 `t4b2a-review.md`「P1 修正增量審查」節）：
  - 起因：收據與熱列不符、journal 又是 dirty 時，原生存檔（autosave、`/save-all`、停服）的 `verifyReceipts` 例外會穿過原版存檔鏈（stack 經 `PersistentStateManager.save`→`ServerWorld.save`→`MinecraftServer.save`）。
  - 處置：走技術修正、spec 語意不變，是 controller 的決定（不是使用者裁示）。開服 strict 拒絕維持 T2 設計，記為覆蓋界線。
  - 修法（只改 `SessionRecoveryState.java`）：原生存檔遇到收據不符時不再丟出；只有「本次有變動又與收據不符」的列會擋下寫出、保持 dirty；未變動的不符列照原樣重寫，其他 SID 照常落盤；熱列、收據原件與占用都不動，管理員 ERROR 以 SID 去重（去重規則之後在 P2、B2b-2 調整）；checked flush 照舊回報失敗；開服 strict 載入不變。
  - RED（未修的 production）：GT `published_receipt_mismatch_keeps_lock` 得 `NATIVE_AUTOSAVE_THREW`；原生 `/stop` 記 `Exception stopping the server`，沒有走到 SERVER_STOPPED。
  - **實證與原推論的差異**：審查原本推論停服會遺失最後一次成功存檔之後的世界資料。實證是 **chunk 沒有遺失**（原版 `shutdown` 在 `save` 之前的卸載迴圈已經寫出），遺失的是 **level.dat 與 journal 丟出之後原版才會存的 PersistentState**，world close 與 session unlock 也被略過。審查員以唯讀 NBT 檢查離線重現，確認更正成立。
  - 6 個 mutation 全部 KILLED，逐位元還原。新原生列 `receipt-mismatch-stop`（windows.native 改為 45 列）。
  - 審查：spec ✅、quality Approved，C0/I0/M3。P1-M1 併入 P2；P1-M2、P1-M3 見下方「已知界線」（P1-M3 已依 15:4x 裁示由 P2 處理）。
- **P2**（production `cea8d44`、`dbbb0d4`；S `task-4p2-brief.md`、`t4p2-report.md`、`t4p2-review.md`）：
  - 依 15:4x 與 17:1x 兩題裁示。production 只改 `SessionRecoveryState.java`：`save` 外包一層 catch，原生路徑記 ERROR 後 return，checked 路徑丟出同一個例外物件；執行緒與 owner 守門照舊丟出。
  - 原生路徑的失敗類別與收斂者：
    - UNHEALTHY（journal 不健康）：不寫；修檔後重啟；
    - PENDING（未確認的寫入，含需要重啟）：不覆寫，保留 pending 與 dirty；重啟後開服 strict 載入收斂；
    - PREPARE（編碼或寫出前檢查）：保持 dirty；由下一次原生存檔、recovery 的 journal 退避重試或下一次 checked 提交收斂；
    - WRITE（寫入正式檔，例如 atomic move 的 AccessDenied）：possiblyMoved 時保留 pending、下一次先核對磁碟，否則保持 dirty；由下一次原生存檔與 `SessionRecoveryManager` 的 journal 退避重試收斂；
    - READBACK（讀回失敗或不符）：保留 pending，下一次寫出依 M5 既有規則判定需要重啟；
    - RECEIPT（P1）：不變，只補 ERROR 的路徑、處理方式與去重鍵。
  - ERROR 帶 `TICK_LOG_MARKER`，附 cause、journal 路徑、dirty／pending 與收斂方式；收據 ERROR 寫明收據檔路徑與處理方式，「有變動」型註明全服 journal 寫入已暫停，讀不出收據時寫「無法讀取或解析」，不寫成竄改。
  - M4 `readback-fault` 依追加題改成新預期：原生 save 不丟出、不寫入、journal hash 不變、PENDING 類 ERROR 恰一則、停服正常完成（SERVER_STOPPED、JVM exit 0）、磁碟上仍是那份 exact attempt；recover、verify 照舊。改動擴到 `M4CandidateRecoveryProbe.java`、`m4-recovery-probe.ps1`、`run-all.ps1`（只限 recovery 的 readback-fault 分支）。實作前已唯讀確認 recover／verify 不依賴「停服崩潰、沒有落盤」。
  - 既有 JUnit 的對照（`t4p2-report.md`「既有 JUnit 斷言對照表」）：原本以原生 `save` 斷言丟出的 32 處 `assertThrows(…save…)` 中，27 處改成 `checkedSave`，5 處屬 owner 守門、維持原生 `save` 丟出不改；另有 2 處不是 `assertThrows` 形式，也改成 `checkedSave`；原生版本共補 8 處。`CandidateLedgerServiceTest` 實跑 26 案全過，沒有改。
  - 新增：JUnit `M7NativeSaveFailureTest`；testmod `M7NativeSaveFault.java` 與 `SessionJournalWindowFaultMixin` 的 `@ModifyArg`（把 `writePrepared` 的目標導向既存目錄，Windows 上是真正的 `AccessDeniedException`）；GT `native_save_write_failure_keeps_pending`、`native_save_skips_unhealthy_journal`；原生列 `receipt-write-fail-stop`（windows.native 改為 46 列）。
  - RED（未修的 production）：JUnit、GT（`NATIVE_AUTOSAVE_THREW`／`NATIVE_UNHEALTHY_THREW`）、原生列與 M4 新 oracle（`M4_PENDING_NATIVE_THREW`）都 FAIL。6 個 mutation 全部 KILLED；拿掉攔截的 m1 另被 M4 新 oracle 殺掉。
  - focused（`dbbb0d4`）：JUnit 25 類 378 案；M7 gate junit 45、gametest 29；M5 gate m5 60、m4-normal 13、legacy 1；兩條原生列都 PASS。M4 recovery 25 phase 是在 GREEN 階段跑的（commit 前，工作樹內容與 `dbbb0d4` 相同），focused 沒有重跑。
  - 審查：spec ✅、quality Approved，C0/I0/M7。M1、M2、M3、M4、M5、M6 與 B2a-M1 排進 B2b-1；M1 與 M7 的內容見下方「已知界線」。
- **B2b-1**（production `864b0fb`、`a8755ce`；S `task-4b2b1-brief.md`、`t4b2b1-report.md`，審查在 `t4p2-review.md`「B2b-1 增量審查」節）：
  - production：`SessionRecoveryState.java`（去重、ERROR 文案、Javadoc）與 `ChunkSaveCheckpoint.java`（只改 Javadoc）。
  - P2-M2：原生 ERROR 在讀回確認的成功寫出之後清除去重，之後再發生同類失敗會再記一則。這條重新啟用是 controller 的決定（P2 簡報 Q4 的每類只記一次，本來就是 controller 訂的規則，不是使用者裁示）。收據 ERROR 的去重在本段沒有改（未變動的不符列每次存檔都會寫出成功，以寫出成功清除會刷屏），改由 B2b-2 處理。
  - P2-M3：`M7HomeClosureGameTests.Projection` 在 `close()` 時以正式路徑收掉自己留下的列（`M7_GT_RESIDUE_CLOSED`／`M7_GT_LEFTOVER_HOME`）；`M7CapacityFixture.admit` 先檢查共用 journal 沒有殘留，不成立時以 `PRIOR_TEST_RESIDUE` 失敗。
  - P2-M4：M4 的 `pending-stopped.json` 補 `stopSaveCalls`（停服過程中，也就是 SERVER_STOPPING 之後，真正進入 journal save 本體的次數）與 `universeUnchanged`，Java 與 `m4-recovery-probe.ps1` 都要求 `stopSaveCalls≥1`、`universeUnchanged=true`。
  - P2-M5、P2-M6：文案改寫；PREPARE 分類與「保持 dirty」補斷言與 mutation。P2-M5 第 4 點（收據路徑共用 `HomeReceiptStore` 的來源）要改 `HomeReceiptStore.java`，implementer 依規停下，排進 B2b-2。
  - **tickLimit 2000→2300**：新 fixture 第一次負控時 B 還沒就緒，implementer 為 native write GT 加上 `whenEntryReady`，tickLimit 跟著放寬。審查判定不違反裁示也不算放寬驗收：11:0x 第 1 題的原文沒有提到 tickLimit 要不要改；前置等待扣掉後續等待、不超過原 tickLimit，是 controller 落地時對那 9 案的選擇；這個 GT 是 P2 新增的，驗收內容不變，最壞情況 2286 ≤ 2300（餘量 14 tick）。採用這個改動是 controller 的決定（不是使用者裁示）。
  - RED 9 案中 4 案 FAIL；ma、mb、mc 三個 mutation 都 KILLED。focused（`a8755ce`）：JUnit 25 類 380 案；M7 gate junit 47、gametest 29；M5 gate PASS；M4 recovery 25 phase、兩條原生列都 PASS。
  - 審查：spec ✅、quality Approved，C0/I0/M4。M2、M3 併入 B2b-2，M4 併入 B2b-3，M1 見下方「已知界線」。
- **B2b-2**（production `a7772a6`、`647a127`、`9dab143`、`98e8e20`；S `task-4b2b2-brief.md`、`t4b2b2-report.md`，審查在 `t4p2-review.md`「B2b-2 增量審查」節）：
  - production：`HomeReceiptStore` 抽出純計算的 `receiptPath`，收據讀寫與收據 ERROR 共用（P2-M5 第 4 點）；收據 ERROR 在該 SID 的 `verifyActive` 通過時清除這個 SID 的去重鍵（B2b1-M2，具名 RED `RECEIPT_ERROR_NOT_REPORTED_AGAIN`）。
  - B2b1-M3：`SessionJournalSchemaFiveTest` 以真實觸發（lease 失效）補 PREPARE 分類的 log 斷言。
  - **>4096**：JUnit `M7HomeHistoryScaleTest`，4100 個互異 SID 每一趟都走正式 `publishOrVerify` 發布與正式 checked 移除 `terminateHome`；之後舊 SID 重用被拒、新 SID 仍可 admission、抽樣舊收據 exact 讀回；以 `ListingProbe` instrumentation 證明沒有目錄列舉，m4（把 `read` 改成列舉目錄）只被它殺掉。本機 46.3 秒。
  - **彙總驗證**：summary `treeClean` 不是 true 一律拒絕；單份只有完整 46 列才輸出 `M7_HOME_VERIFIED_COMPLETE PASS`，分片只輸出 `M7_HOME_VERIFIED_SHARD PASS`；新增 `-RequireComplete` 與 `-VerifyAggregate`（門態組合或 family 組合）。
  - **restart fixture 出生點修正**（`98e8e20`）：
    - 第一輪 restart 族的 `restart-ready-low-no-root` 在 reopen 以 `aNeverLoadedDuringClose` FAIL，原 root `run/m7-home-0531c6306e06-*` 保留、原判不改。
    - 根因（以 javap 查證）：原版生玩家時會在出生點附近隨機一欄同步載入 chunk；出生點 (0,74,0)、`spawnRadius=10`，A 的 chunk 在 (0,0)、(0,1)，落進的機率約 27%。這是 B2a fixture 的偶發，不是本輪改動造成的。
    - 修法是 controller 的決定（不是使用者裁示）：修 fixture、判定不放寬、不交使用者。基線存檔前把出生點移到 (-256,y,-256)，搜尋 chunk 為 {-17,-16}²，與 A 不相交；新增 `spawnSearchAvoidsA`、`aNotLoadedAfterLogin` 兩個斷言，既有判定不改。
    - 強制 RED（`spawnRadius=0`，在 `9dab143`）FAIL，特徵與原偶發相同；強制 GREEN（在 `98e8e20`）PASS。原 FAIL、強制 RED、強制 GREEN 三組 root 都保留。
  - 新 HEAD `98e8e20` 重跑全部 46 列 PASS（另跑 close 族 39 列）；`-VerifyAggregate` 門態組合（426 個負控）與 family 組合（406 個負控）都是 `M7_HOME_AGGREGATE_COMPLETE PASS`；8 個彙總反例以真實資料逐案以預期錯因被拒。M7 gate junit 49、gametest 29；JUnit 26 類 382 案。
  - 審查：spec ✅、quality Needs fixes，C0/I1/M2。**I-1**：>4096 JUnit 執行期間 Gradle console 沒有輸出，Windows CI 可能觸發「90 秒無輸出就判卡住」；CI 44097956125 證實（見下方 CI）。idle 90 是使用者裁示原文明定不變的，所以修法排進 B2b-3，不調 idle。
- **B2b-3**（`ebc723e`、`3e2c8c7`、`436cb5d`、`434c858`；S `task-4b2b3-brief.md`、`t4b2b3-report.md`、`t4b2b3-review.md`）：
  - I-1：`build.gradle` 的 `test` 開 `testLogging { events 'started', 'passed', 'failed' }`；scale 測試拆成 11 個方法（10 段各 410 趟加最後一段），以 `BLOCK_ORDER` 守住順序。本機 M7 gate 整段最長靜默 5.37 秒；m4 仍 KILLED。idle 90、總時限 600 都沒改。
  - CI：新增 `m7-home-native`（Windows matrix 四片：ordinary、sealed、passable 各 13 列，restart-receipt 3＋4 列）與 `m7-home-native-aggregate`（要求 `M7_HOME_AGGREGATE_COMPLETE PASS combination=doorState summaries=5 rows=46 phases=92`）；既有 job 逐鍵不變。
  - run-all：在 `m6-native` 之後新增 `m7-home-native` step（46 列、`-RequireComplete -SelfTest`）；recovery 檢查直接核 `stopSaveCalls≥1` 與 `universeUnchanged=true`（B2b1-M4）。
  - 日期 TODO：`scripts/verification/` 沒有任何 `ParseExact`，結論是未恢復此呼叫、不適用（implementer 的結論，controller 採用）。plan Task 4 的日期項已註記。
  - B2b2-M2：`HomeReceiptStore` 的 Javadoc 寫明只按 SID 查詢、禁止列舉目錄（只改註解）。
  - focused（`434c858`）：M7 gate junit 59、gametest 29；JUnit 26 類 392 案。
  - 審查：spec ✅、quality Approved，C0/I0/M3。三項 Minor（600 秒餘量偏緊、run-all `summary.json` 沒記 M7 原生、彙總診斷）排進 B2b-4。
- **B2b-4**（`51dc8ca`、`636d3a5`、`926425c`；S `task-4b2b4-brief.md`、`t4b2b4-report.md`，審查在 `t4b2b3-review.md`「B2b-4 審查」節）：
  - B2b3-M1：CI 44107563893 的 M7 Windows step 耗時 491 秒，離 600 秒只剩約 110 秒。scale 測試改成 strict 趟抽樣：第 0 趟、各段首尾、4095／4096 等邊界與每 64 趟，共 86 趟照完整步驟（admission、CLOSE 列以正式 writer 落盤、開服 strict 載入）；其餘 4014 趟在記憶體 strict decode。4100 趟每一趟仍走正式發布與移除，每趟斷言照舊。本機 53.7→28.3 秒，降 47.3%。
  - 「至少降一半」是 controller 自訂的目標；剩下的成本是正式發布與移除本身（每趟約 6.3 ms），接受 47% 是 controller 的決定（不是使用者裁示），審查員同意。600 秒不改。
  - B2b3-M2：run-all 的 summary step 以 `Get-M7HomeNativeProof` 唯讀重驗 M7 原生 summary，寫進 `summary.json` 的 `m7HomeNative`，`SUMMARY` 行尾加 `m7native=COMPLETE:46`。真實的 46 列 COMPLETE 正例要等 T4 完整回歸。
  - B2b3-M3：每片寫 `<shard>.runner.json`（workspace、`ImageOS`、`ImageVersion`），彙總 step 先比對再做原本的檢查，主體包在 `try/catch`，錯誤完整寫進上傳的 log。`ImageVersion` 不同只記錄、不判失敗，是 controller 的決定（不是使用者裁示）；`ImageOS` 不同仍判失敗。
  - gate（`926425c`）：M7 junit 59、gametest 29；Gradle 141.2 秒，最長靜默 4.75 秒。
  - 審查：spec ✅、quality Approved，C0/I0/M2。B2b4-M1（scale 測試補 `assertEquals(86, strictTrips.size())` 或逐段核對首尾）已在 B2b-5 處理（見下段）；B2b4-M2（README 對彙總比對順序的描述）在本筆 docs 更正。
  - `926425c` 的 CI 44117707536 attempt 1 十個 job 全部 success，但 M7 Windows step 耗時 494 秒（scale 測試 204.456 秒、GameTest 177.4 秒），與前一次 CI 44107563893 的 491 秒相近。這是 2026-11-18 規模測試裁示的背景。
- **B2b-5**（`5bcfce4`、`e28784c`，範圍 `926425c..e28784c`；依 2026-11-18 規模測試裁示；S `task-4b2b5-brief.md`、`t4b2b5-report.md`，審查在 `t4b2b3-review.md`「B2b-5 審查」節）：
  - 只改測試、腳本與 workflow（7 個檔）；production、testmod、`build.gradle` 都沒有改。
  - inventory：`m7-inventory.json` 升 schema 4。`portable.junit` 移除規模測試的 11 個方法，新增 `scale.{portable,windows,ubuntu}.junit`（portable 是那 11 個方法，兩平台都跑）。Main 48、Scale 11，交集為空，聯集等於原本的 59，也等於原始碼宣告的全部 M7 `@Test`。
  - `m7-ci-gametest.ps1` 新增 `-Segment Main|Scale`：Gradle 的 `--tests` 改由該段 exact 集合的 class 產生；兩段 class 重疊時 `M7_JUNIT_SEGMENT_OVERLAP`，兩段聯集不等於原始碼宣告時 `M7_JUNIT_DECLARATION_SET`。規模段輸出 `M7_SCALE_VERIFIED PASS`，Main 段的 `M7_VERIFIED PASS` 不變。
  - `gates.ps1`：新增 `-M7Tests`（class 篩選）；新 mode `M7ScaleRuntime`（只跑 `M7HomeHistoryScaleTest`，總時限 600、idle 90，沿用既有 `Invoke-Gradle` 與 M6 deadline，不啟動 Minecraft），以及本機 gate `M7ScaleTest`。`M7Runtime` 的 600／90 不變。
  - CI：在 `m7-validation`（Windows 與 Ubuntu matrix，同一個 job）新增 3 個 step：「執行 M7 規模測試」、「確認 M7 規模測試精確集合」、「上傳 M7 規模測試證據」（artifact `m7-scale-<平台>`）。原有 step 逐鍵不變，沒有新增 job。Ubuntu 的規模段和 Main 段一樣直接呼叫 Gradle，腳本內沒有 600／90 判定，只受 job 的 90 分鐘限制。
  - run-all：`m7-home` 之後新增 `m7-scale` step（執行，再 `-VerifyOnly -SelfTest`）；summary step 唯讀重驗同一個 root，`summary.json` 在 `m7HomeNative` 之後新增 `m7HomeScale`，`SUMMARY` 行尾加 `m7scale=PASS:11`。既有欄位不動（`m7Home` 的 `junitCases` 由 59 變 48）。
  - B2b4-M1：`M7HomeHistoryScaleTest` 新增 `STRICT_TRIPS = 86`；最後一段逐段核對各段首趟與末趟都在 strict 集合內，再核 strict 趟恰為 86。m5a（stride 64→128）與 m5b（刪掉首尾條件）都 KILLED；對照組 m5c（m5b 再拿掉新斷言）如預測存活，證明 m5b 是新斷言殺的。m4（`read` 改成列舉目錄）仍 KILLED。
  - 乾淨 HEAD `e28784c`：M7 gate `M7_VERIFIED PASS junit=48 gametest=29`，Gradle 116.6 秒（B2b-4 是 141.2 秒）；規模段 `M7_SCALE_VERIFIED PASS junit=11`，Gradle 45.1 秒；exact 集合對照與 11 案守門正負例都符合；CI 新 step 以一次性 harness 逐字實跑 PASS。
  - 本機兩段相加（161.7 秒）比原本多，多的是第二次 Gradle 啟動與 `--rerun-tasks` 重新編譯。implementer 原本推估 CI 上 Main 約 290 秒、規模段約 265～295 秒；`e28784c` 的 CI 44198806056 實測 Windows Main 280 秒、規模段 204 秒，見下方 CI 表。
  - 驗證工具的非預期失敗 2 次，另有 1 次 wrapper 卡住與 1 次 root 放錯位置（建在 repo 外的 `C:\c\...`，已核 hash 搬回），都出在 implementer 自己的驅動，原 root 保留，未達止損。T3 那組 GameTest fixture 在本輪兩次 M7 gate 都 PASS。
  - 一席審查（S `t4b2b3-review.md`「B2b-5 審查」節）：spec ✅、quality Approved，C0/I0/M2。審查以原始碼與兩版 inventory 獨立重算，確認 Main 48＋Scale 11＝原 59，交集為空；B2b4-M1 CLOSED。兩項 Minor 交 T5 Step 0：
    - B2b5-M1：宣告掃描只認得 `@Test void`。若新的 `M7*Test` 類別只用 `@ParameterizedTest`、`@RepeatedTest`、`@Test public void` 之類的寫法，又沒有列進 inventory，兩段都不會執行它，exact 檢查也察覺不到；完整 JUnit 仍會跑到。要補上類別層級的核對與負例。
    - B2b5-M2：CI 新 step 沒有 `timeout-minutes`，Ubuntu 卡住時要等到 job 的 90 分鐘上限；Main 失敗時，scale 會連帶被跳過。建議執行 step 設 20 分、驗證 step 設 10 分；要讓兩段互不連坐，可以加 `if: ${{ !cancelled() }}`。

### 過程中的 CI

下列都是 T4 進行中的 CI，不是 T4 結案 CI；T4 結案以最終 SHA 的 CI 為準。FAIL 一律保留原判、不 rerun。原件在 `S/ci-<run>/`（每個目錄都有 `run.json`）。

| run | head | 結果 | 說明 |
| --- | --- | --- | --- |
| 43993912938 | `c74bf79` | **FAIL** | M7 Ubuntu：`participant_close_closes_door_when_doorway_clear` 在 200 tick 內沒有收尾（`PARTICIPANT_CLOSE_NOT_CLOSED_OUT`），是 `participantCloses` fixture 第一次非預期失敗。其他四個 job success |
| 44000617490 | `773f709` | success | 五個 job |
| 44003465357 | `27415b4` | **FAIL** | M7 Ubuntu：`participant_close_keeps_door_open_when_doorway_occupied` 逾時（`M7_GT_WAIT used=200 bound=200 timeout=true`），B chunk 方塊已載入、entity 未載入，是同 fixture 第二次非預期失敗，依 controller 止損交使用者裁示。M7 Windows：24 案全部 PASS，但 `M6_PHASE_DEADLINE_EXCEEDED reason=total`（當時總時限 180 秒）。兩項的處置即 11:0x 兩題 |
| 44008473002 | `e652c23` | success | 五個 job；兩平台 `M7_GT_WAIT` 都沒有 timeout、沒有 ENV_NOT_READY |
| 44013651723 | `4f4128e` | success | 五個 job（B1 的 mixin 已在其中） |
| 44028009042 | `fca6d31` | success | 五個 job；B2a 三個新 GT 第一次在 Ubuntu 跑 |
| 44039568902 | `73132bc` | success | 五個 job |
| 44066568721 | `dbbb0d4` | success | 五個 job |
| 44078380354 | `a8755ce` | **FAIL** | windows-native：M5 GameTest 60 案中 `m5universeprojectiongametests.projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids` 1 案 FAIL（活塞與水的對照組沒有在時限內發生，`M5_WATER_GEOMETRY stage=timeout`），連帶 M6 scope 檢查 `M6_SCOPE_MISMATCH（m5）`。判讀為 M5 既有測試在 CI 上的首次偶發（推論）；再出現就另外調查並交使用者（controller 的決定）。其他四個 job success（2026-11-18 更正：「首次」不正確。M6 分支的 42355698932（`dfed554`，2026-11-07，Windows）已有同簽章，這次是第 2 次，44259424086（`c091581`）是第 3 次；出處 S `m5-water-control-flake-investigation.md` 第 1 節，處置見上方「最新：T4A CLOSED（2026-11-18）」節的「M5 熔岩偶發與使用者裁示」） |
| 44097956125 | `98e8e20` | **FAIL** | M7 Windows：`M6_PHASE_DEADLINE_EXCEEDED reason=idle`（elapsed 157.4 秒、idle 90.03 秒、timeout 600），即 B2b-2 審查的 I-1。其他四個 job success；windows-native 的 M5 這次 PASS |
| 44107563893 | `434c858` | success | 十個 job（第一次有原生分片與彙總）。分片約 ordinary 21 分、sealed 16 分、passable 21 分、restart-receipt 12 分，彙總 30 秒 COMPLETE；windows-native 約 48 分是整個 CI 的牆鐘上限；M7 Windows step 491 秒 |
| 44117707536 | `926425c` | success | 十個 job（attempt 1）。M7 Windows 的「執行 M7 獨立 GameTest」step 494 秒，其中 scale 測試 204.456 秒、GameTest 177.4 秒；這是 2026-11-18 規模測試裁示的背景 |
| 44198806056 | `e28784c` | attempt 1，十個 job 全部 success | 規模段拆出之後：Windows Main step 280 秒（23:25:41→23:30:21）、規模段 204 秒；Ubuntu Main 199 秒、規模段 32 秒。原件 S `ci-44198806056/run.json` |

### 已知界線與帶到後續

- **B1 固定矩陣的覆蓋界線**（B1 審查 M3）：39 列都從 MEASURED DORMANT 出發，原因固定 ROOT_DISABLED／DoorGoal OPEN，基線放在停用 A 之後。以下沒有跨 JVM 覆蓋：
  - CLOSE_IF_CLEAR_ELSE_OPEN 在重開後重新評估門口；
  - 來源狀態為 COLLAPSING、非永久 SEALED 的 ROOT close（推論，不是審查原文的對應：READY＋部分 Crossed 由 B2a 的 `restart-ready-stop-crossed` 補上一列，只在收尾開始前切 JVM）；
  - 「stop 已落、registry 未落」（停用之後、autosave 之前 crash），依 spec §9.1 的「復啟不能撤銷latch」預期終局相同。
  - PARTICIPANT_CLOSED／EXPIRED 的門目標由 Phase A 的同 JVM GT 守住。
- **B2a-M3**（交 T5／T6）：`restart-enable-before-entry` 的「再操作 B」在 T4 只能以 journal 的 MEASURED→COLLAPSING 提交邊代表，靠 `HomeSessionRecord.edge` 的 stop 規則拒絕。T5／T6 有正式 B 端續行之後，要以真實 B 端操作重跑這一段。
- **B2a-M4 三個界線**：
  1. 開服掃描時 latch 的寫出失敗、enable 仍應 pending：只有同 JVM GT（`root_event_queues_behind_unconfirmed_journal`、`fence_init_failure_keeps_enable_blocked`）的同類情況，沒有原生列。
  2. active 移除的寫入結果不明（possiblyMoved 或 pending）：`receipt-existing` 只模擬乾淨的失敗。（推論，不是審查原文）P2 的 `receipt-write-fail-stop` 的 possiblyMoved 發生在 remove-rejected 之後的原生存檔，checked 移除那一次仍是乾淨失敗，所以不改變這一條。
  3. 開服時收據與熱列不符：見下方 R5。
- **P1-M2：雙重異常的出路**：
  - 「本次有變動又與收據不符」只會在外部於未結案的熱列旁放了同 SID 的收據時出現（入場拒絕已有歷史的 SID，收據只在 CLOSE checked 之後才發佈）。期間整份 journal 的原生存檔與所有 SID 的 checked 提交都會失敗並退避，伺服器不崩潰，其他資料照常存。
  - 出路：伺服器運作中刪除或還原那份收據（`<save>/data/quantumchamber_home_receipts/<SID>.dat`），下一次存檔或 owner 重試就恢復寫出，不必重啟（JUnit 實證了移除收據後恢復寫出）。
  - 沒處理就停服：記憶體中尚未寫出的進度回到上一份 durable，下一次開服被 strict 載入拒絕，要先修檔。
  - 這條路徑只有 JUnit 證據，沒有原生跨 JVM 列。管理員說明寫在 `scripts/verification/README.md`「管理員說明：回程收據與熱列不符」。
- **R5：開服 strict 遇到不符時拒絕開服**，維持 T2 設計（載入時 `verifyReceipts` 核全部熱列，不符就成為 loadError，`SERVER_STARTED` 的 `requireHealthy` 拒絕開服）。不會遺失資料，由管理員修檔；沒有以原生列實跑，JUnit 證明 journal 載入失敗。若要開服時只隔離該 SID，需要使用者另外裁示。
- **P2-M1：三個 checkpoint 呼叫端的語意變化**（`t4b2b1-report.md`「三個 checkpoint 呼叫端」節）：`ChunkSaveCheckpoint.save` 內的 `server.save` 走原生路徑，P1／P2 之後 journal 在這裡寫出失敗只記 ERROR、不丟出，checkpoint 只依相關 chunk 判定。
  - `CollapseService`（PREPARE）：以前 checkpoint 丟出就 `seal(PROJECTION_FAILED)`；現在 checkpoint 成功、寫 COMPLETE，之後 PASSAGE_READY 的 checked 提交失敗而 `readyPaused` 退避。
  - `CorridorPageManager`（MEASURED 清理）：以前走 checkpoint 退避；現在由之後的 checked `journal.flush` 失敗把關，不釋放 slot 與票。
  - `HomeClosure.converge`（HOME 收尾）：以前不發收據；現在先發佈收據，再由 `finishHomeClose` 的 checked 移除失敗退避（CLOSE 與 exact 收據並存是允許的配對）。
  - 三者之後都有 checked 寫出把關，審查判為良性，是裁示的直接後果，spec 語意不變。
- **B2b1-M1**：原生存檔 ERROR 的前綴寫「原生存檔（autosave、/save-all 或停服）」，但 checkpoint 的 `server.save` 也會觸發同一則 ERROR。前綴是 M4 ps1、run-all 與 GT 的錨點，不改；管理員看到這則 ERROR 卻找不到對應的 autosave 或指令時，來源可能是 M5 投影、MEASURED 清理或 HOME 收尾的 checkpoint。
- **P2-M7：驗證流程的副作用**：以原生存檔當落盤步驟的 probe（例如 M2、M5、M6 的 `server.save(false,true,true)`／`saveAll`），以前遇到 journal 寫出失敗會當場 FAIL，現在只留一則 ERROR、步驟通過，失敗延後成下游的磁碟不符。屬後續整理，不在 T4 做；T4 完整回歸時留意。AccessDenied 的新樣貌已補進 AGENTS 單次重跑規則的判定說明。
- **B1 假設 (c) 的後續要求（B1 審查）**：B1 以測試票保持 B 的區塊載入。T5 有正式 B 來源票之後，要用真票重驗，並確認重開時 B 無人無票會保持 pending（spec §5.3 :173）。B2a-M3（以真實 B 端操作重跑續行段）同屬 T5／T6，是否補進 plan Task 5／6 的核取項，留到 T5 開工時決定。
- **production 註解的歸屬**：`SessionRecoveryState.java:1027` 的註解寫「T4 P2 使用者裁示 2」，指的是執行緒與 owner 守門照舊丟出。這其實是 controller 的決定，不是使用者裁示，歸屬以本 note 與 spec 文末節為準。註解留待下一個會動到這個檔的 commit（例如 T5 Step 0）修正。
- **B2b2-M1：SelfTest 上限 479**：門態組合目前是 426（8＋5×10＋8×46），每多一列原生多 8 個逐列負控；再加 6 列是 474，仍在上限內，再加 7 列以上（約第 53 列）才會超過。T5～T8 加原生列前要先定策略（例如彙總時成員的逐列負控只跑一次）；需要超過 M6 的 479 時，依 plan Task 4「如需提高標準先交使用者」交使用者裁示。
- **>4096 的覆蓋分工**：JUnit 拿不到 owner 的 mint 與 proof，所以 CLOSE 列以 journal fixture 放入；發布走正式 `publishOrVerify`，刪活列走 `finishHomeClose` 核過 proof 之後呼叫的同一個本體 `terminateHome`。逐趟的 admission 檢查是 `requireAdmissible`，B2b-4 起只在 86 個 strict 趟做；正式 `admitHome` 用在最後一段（>4096 狀態下的新 admission 與舊 SID 被拒）。mint 與 proof 由正式 39 列、GT 與原生 46 列覆蓋。B2b-4 之後，「熱 journal 檔先有這一列、移除後回到空」的磁碟層級證據只在 86 個 strict 趟；其餘趟由 checked 寫出後的 `allFlushedSessions().isEmpty()` 與 journal 位元組負責。
- **N4：T3 `only_exact_complete_projection_resolves` 不套用前置等待**（controller 的決定，不是使用者裁示）：它同樣在剛建立 B 的窗口等 entity 載入，但這個 fixture 帶使用者的止損規則，而且會刻意 hold B 的 entity storage，改動前應先問使用者。記為已知殘留，CI 用量 3/200；再有非預期失敗就停下來問使用者。
- **前置等待的餘量**：CI 44003465357 的 Windows clear 案也用了 156/200，entity 載入慢在兩個平台都有。380 的前置等待在很慢的 runner 上仍可能 `ENV_NOT_READY`；屆時要不要放寬，另外交使用者裁示。
- **B2b4-M1**：scale 測試的 `STRICT_SAMPLE` 沒有鎖住「各段首尾」與 86 這個數量。原本交 T5 Step 0，已在 B2b-5 處理（`STRICT_TRIPS = 86` 與逐段首尾斷言，m5a、m5b KILLED）。
- **B2b-5 的已揭露界線**（`t4b2b5-report.md`「偏離與未驗」）：Main 段失敗時，CI 的規模段 step 會被跳過（預設 step 語意）；這版之前產生的 M7 root 因 `inputs.json` 多了 `segment`，無法再用新版 `-VerifyOnly` 驗；`ci-rule-check.ps1` 的 M7 fixture 要用新版產生的 root（run-all 與 CI 都沒有呼叫它）。
- **Phase A 帶來、仍有效的項目**：Phase A 審查 M6（選定後重驗 A 的 `checkRoot`／`onRootEvent`，交 T5／T6）、M7（Projection 案例物化 U 不卸載，後續測試不得斷言絕對世界數）、M8（`ChamberMaintenanceInteraction` 的提示改 translatable key，交 T7），以及呼叫端持有同 SID guard 的限制（交 T5／T6）。細節見下方歷史節「帶到後續」。
- **範圍外的 (b)**：candidate 檔不健康時入場靜默拒絕（M4 時期 `3d093b50` 的程式碼），建議日後加一則去重的 WARN。

### 計數

- `participantCloses` fixture：非預期失敗兩次（`c74bf79` clear 案、`27415b4` pinch 案）後依止損交使用者裁示。`e652c23` 之後，各段的 M7 gate 與上表的 CI 都沒有這個 fixture 的失敗。修正後再有任何非預期失敗就停下來問使用者（11:0x controller 落地）。
- T3 `only_exact` fixture：Phase A 第 1 題裁示的規則照舊；Phase B 各段報告都沒有記到它的非預期失敗。
- 驗證工具與新 fixture 的非預期失敗：各段報告的計數節都有記錄（例如 B1 的初始門摘要常數、B2a 的 UniverseId 解析、P1 的 5 項、B2b-1 的殘留收尾 fixture 與 mutation 驅動層數、B2b-2 的 restart fixture 偶發、B2b-5 的 4 次驅動問題），問題各不相同，都已修正、原件保留；各報告都判定沒有達到同一問題第三次的止損條件。
- AccessDenied 重跑額度與完整回歸啟動器歷史：照下方歷史節「計數」。Phase B 期間沒有跑完整回歸，這兩個計數沒有變動。M7 的 AccessDenied 額度來自 T2 的單次授權，T3 已記為 1/1 用完、不刷新；AGENTS 中那條同一 code pin 可重跑一次的規則，標題就限定 M6 T14～T21，不適用這裡。所以 T4 完整回歸若出現 AccessDenied，就停下來問使用者。

### 下一步

1. 本筆 docs 的一席文件審查：C0/I0/M11（S `t4-closeout-docs-review.md`），Minor 已在本 commit 修正；B2b-5 審查與 `e28784c` CI 都已完成，見上 → stage spec 後計算 SHA 填入 plan 第 8 行 → commit。
   - 在 run-all 之前，依 plan Task 4 最後一項，只讀核對既有的 W10 producer（固定 pins、hash、非 reparse、owner、鎖）與 child 65001，不重建。
2. 在 holder 下跑完整回歸（run-all）。B2b-5 新增的 `m7-scale` step 與 `summary.json` 的 `m7HomeScale`（`SUMMARY` 行尾 `m7scale=PASS:11`）會在這次第一次實跑。採計條件見 `t4b2b3-review.md`「B2b-4 審查」第 2 節：乾淨 pin、`END ALL exit=0`、`m7HomeNative` 為 COMPLETE／46／92、SHA 與重算一致、console 有 `END m7-home-native exit=0` 與 376 個負控的 COMPLETE 行、`SUMMARY` 行尾 `m7native=COMPLETE:46`。另外確認：
   - Phase A 留下的兩項（共用 setter `setEnabled`、`removeOrigin` 沒有回歸；`M7HomeFenceStartupProbe` 不進 release 或 main-only）；
   - B1、B2a 新 mixin 與 probe 套用到所有 testmod run 後，default、M5、M6 GameTest、M4 recovery、M5 collapse、M6 native、main-only、artifact oracle 都不受影響；
   - P2-M7 第 1 點的副作用。
3. push → 同 SHA 全部 required CI 全綠（`434c858` 起共十個 job）→ T4 CLOSED。
4. T5 開工前先做遊玩組合分析（使用者還沒回覆時機，controller 建議 T4 結案後、T5 開工前）。

## 歷史：T3 結案；T4 Phase A 實作、審查清零（2026-11-17）

- **T3 CLOSED**（2026-11-17 00:58）：
  - production pin `3aa493e`。本機完整回歸跑在文件 commit `6644fe9`，自然 `END ALL exit=0`，啟動器讀到的實際 ExitCode 是 0（JUnit 1164 案 0 failure，default 166、M5 60、M6 35 等全部 PASS）。
  - 同 SHA CI 43898284110（head `6644fe9`）：M7 Ubuntu FAIL，原判保留、不 rerun；其他四個 job success。
    - 失敗案例：`m7homespikegametests.checkpoint_capability_remains_rejected_on_ubuntu`，`NativeEntry` 寫死了世界數。T3 authority 測試物化的 U 留在同一個 server，污染了計數。
    - 本機只跑 Windows，這條 Ubuntu 專用路徑只有 CI 會跑。
  - fix3 `6944236`：只改 `M2CorridorGameTests.java`，改成比對 fixture 建立時的世界數。一席審查 C0/I0/M0。
  - CI 43907694743（head `6944236`）attempt 1：五個 required job（build、windows-native、m6-native、M7 Windows、M7 Ubuntu）全部 success。
- **T4 切成兩段**（controller 的派工安排，不是使用者裁示）：
  - Phase A：正式 owner（`HomeRootFence`、`HomeClosure`）、JUnit `M7CloseMatrixTest` 與 GameTest `M7HomeClosureGameTests`。
  - Phase B：plan Task 4 中 Phase A 還沒完成的全部核取項，至少包括：
    - Windows 原生 39 列 `close-{ordinary|sealed|passable}-{cut}`，逐 phase 核兩側門格、region、識別、active 與歷史 hash、資源、零移動；
    - `receipt-existing` 鏈，以及 checkpoint unknown、跨檔不符、已存 target 異值、成功後玩家主動關門；
    - `restart-enable-before-entry` 跨 JVM 鏈，含 DORMANT、同 tick 復啟、部分 Crossed、stop 已落後 A 修復不復活、普通 LOW 無 latch，以及對已有 READY、沒有已確認 root 的 startup 只走 RESTART_CLOSED、不等待 A FULL；
    - 超過 4096 個 SID；
    - 日期解析：plan 的條件式 TODO，只有 runner 恢復使用該 `ParseExact` 呼叫時才做，屆時 format 集合要寫成 `[string[]]`（或依 M6 原解析做法，不新造框架），並補 3／6／9 位小數秒正例與錯格式負例；
    - 上述全鏈與 SelfTest 的 focused 驗證。

    runner 沿用 M6 probe-common。Phase B 再細分成 B0（N1、N2）、B1（runner 與 39 列）、B2（其餘鏈、>4096、日期、CI 分片與 run-all 接線），也是 controller 的派工安排。
  - 兩段都完成，再跑完整回歸、push、同 SHA CI 全綠，T4 才結案。
- **Phase A 首輪**（`550f12e`，未完成）：T3 的 `only_exact_complete_projection_resolves` 在收尾後仍 busy，implementer 依同 fixture 止損停下。
- **使用者裁示**（2026-11-17，AskUserQuestion，題目與選項由 controller 撰寫；SDD 原文檔 `user-t4a-fixture-ruling-20261117.md`）：
  - 第 1 題「T3 那個已止損的測試（收尾後「這座艙仍在使用中」）要怎麼處理？」，使用者選「授權一次有界調查＋修正（建議）」。選項說明原文：「允許在這個測試加只印資料的診斷（測試碼，不改正式程式），跑一次查出「收尾後是誰還佔著這座艙」，再依證據修正一次。可能是 T4 正式程式漏釋放，也可能是測試時序。修正後同一個測試再有任何非預期失敗，就再停下來問你。」
  - 第 2 題「spec 的 ENTRY_STOPPED 列了 DISABLED 訊號，但 B 的投影艙沒有停用開關，這個訊號不會發生。怎麼定？」，使用者選「當成不會發生，寫成勘誤（建議）」。選項說明原文：「記錄格式保留這個值（舊資料相容），但正式程式不會產生它；B 端的停止只有「斷電」與「結構失效」兩種。在 spec 加一條勘誤說明。」
  - 第 2 題已寫成 spec v1.4 的「T4 ENTRY_STOPPED DISABLED 勘誤」節。
- **fix1**（`bd84b8f..a2c3222`）：
  - 一次診斷查出根因：原版 `GameTestState.tickTests` 走訪 fastutil 表時先執行回呼、再移除項目。回呼裡大量排入 `runAtTick` 會讓表格 rehash，同一個回呼因此重跑。這是測試時序問題，production 沒有漏釋放。
    - 這個時序問題是 `550f12e` 改寫 T3 測試時引入的：第二趟在第一趟的收尾回呼裡同步啟動，一次排入 201 筆 `runAtTick`。
    - 修法 `bd84b8f`：改成逐 tick 排程。之後除了預定要被 fix1 M8 mutation 殺掉的那一次，其餘 fix1／fix2 gate 中這個案例都 PASS，沒有再出現非預期失敗。
  - 其他修正：
    - `86ed60b`：DISABLED 一律拒絕；
    - `62beae7`：補 6 個 GameTest；T3 fixture 開頭加上前提 `requireCandidateStores`。後者是回歸途中發現的既有順序相依（新增 batch 後執行順序改變），屬一般測試修正，不在第 1 題授權範圍內；
    - `a2c3222`：排隊案收尾時補做寫出。
- **獨立審查**（spec 與 quality 同席，`6944236..a2c3222`）：spec ❌、quality Needs fixes，C0/I1/M8。
  - I-1：HOME 回程的 roll-forward 寫出失敗後，沒有人負責重試。後果有三個：
    - 停用一直 pending；
    - 全伺服器的 `admitHome` 被擋；
    - 開著 `/save-off` 時，autosave 也不會補寫。

    原本的排隊 GameTest 用手動 flush，掩蓋了這個問題。
  - M1–M5 在 fix2 修正，分別是：log marker、WARN 附 cause、首次排隊記 log、寫入放進 session operation guard、`SERVER_STARTING` 先放入未就緒的 fence。M6–M8 交給後續 task，見下方「帶到後續」。
- **fix2**（`af8a899` production、`cc92b66` 測試）：
  - I-1：`SessionRecoveryManager` 成為 HOME roll-forward 唯一的重試寫出者，沿用同一個 journal 退避（200→12000 ticks）。
  - M4：三個入口與根事件的寫入都包進 `exclusiveOperation`。
  - M5：fence 在 `SERVER_STARTING` 就先放入，開服掃描完成前一律拒絕 enable。
  - 證據：RED 21 案恰好 5 案失敗，符合預測；5 個 direct mutation 全部 KILLED，並逐位元還原。final M7 gate 為 GameTest 21/21、JUnit 35/35，另 8 個既有 JUnit 類別 71 案全部通過。
  - 途中 GREEN 第一次有非預期失敗，失敗的是 `root_event_queues_behind_unconfirmed_journal`：新寫的等待條件觀察不到中間狀態，production 行為是正確的。原件保留，修一次後通過。這不是第 1 題止損所指的 T3 fixture。
- **增量審查**（`a2c3222..cc92b66`）：spec ✅、quality Approved，C0/I0/M2，I-1 與 M1–M5 都確認 ADDRESSED。新增兩項 Minor 排進 Phase B 的 Step 0：
  - N1：呼叫端自己持有同 SID 的 guard 時，`onParticipantClose` 和 `onEntryStopped` 會靜默拒絕。
  - N2：HOME 重試的退避沒有測試守住（spec §6 :186）。
- **帶到後續**：
  - 交 T5／T6：
    - Phase A 審查 M6：選定後重驗 A 時，要呼叫 `checkRoot` 與 `onRootEvent(INVALID_STRUCTURE/IDENTITY_MISMATCH)`。目前沒有 production 呼叫端。
    - 呼叫端限制：呼叫端自己持有同 SID 的 guard 時，參與者關門與 ENTRY_STOPPED 會被拒絕，根事件則改排隊。T6 的 B 門攔截不得包在同 SID 的 guard 內，GameTest 要走真實的門路徑驅動。`onEntryStopped` 的 LOW／INVALID 正式 mint 路徑目前沒有 GameTest 驅動，也沒有 mutation，只有 DISABLED 拒絕案。
  - 測試隔離殘留（Phase A 審查 M7）：每個 Projection 案例都物化 U 而且不卸載。後續測試不得斷言絕對世界數。
  - 交 T7（Phase A 審查 M8）：`ChamberMaintenanceInteraction` 的提示是 literal 字串，要改成 translatable key。
  - 範圍外殘留：candidate 檔不健康時入場靜默拒絕，這是 M4 時期 `3d093b50` 的程式碼。建議日後加一則去重的 WARN。
  - T3 帶來的 N2-M3（`OriginDoors` 套用時看不到 HOME BUSY）：Phase A 已修，套用前會再查一次 presence，具名 RED 是 `N2_M3_BUSY_DOOR_REWRITTEN`。
  - T4 完整回歸與 CI 要特別確認兩件事（審查留給 controller 的未驗項）：
    - 共用 setter（`setEnabled`、`removeOrigin`）語意改變後，舊流程沒有回歸；
    - 新的 testmod 入口 `M7HomeFenceStartupProbe` 不會進 release 或 main-only，要看 artifact oracle 與 main-only gate。
- **計數**：
  - AccessDenied 重跑額度 1/1 已用完。
  - 完整回歸啟動器歷史 2/3，下一次非預期的工具失敗就停。
  - T3 fixture 依第 1 題裁示：修正後同一個測試再有任何非預期失敗，就停下來問使用者。
- **下一步**：Phase B，先做 Step 0（N1、N2）。

## 歷史：Claude 接手，T3 fix2 審查清零，待完整回歸（2026-11-16）

- **接手**：使用者原話「codex冷卻中你繼續實作」。controller 改由 Claude 擔任，依 [T3 交接](../handoffs/2026-11-14-m7-t3-handoff.md) 續作，沒有重派 T1、T2。
  - 接手時核實：HEAD `a697eba`，tracked clean，比 origin（`9358368`）多 9 個 commit，沒有 java 行程。
  - implementer 與 reviewer 都是 Claude 子代理。
- **fix2**（`9b76d49`，base `a697eba`）：修完整回歸的兩個 JUnit 失敗，原件是 `S/t3-full-holder-6b571243…/junit/`。
  - `ChamberPowerCoordinatorTest`：測試對 BUSY 的期待改成「不交接」，並加上明示案例。production 的 `normalizesOwnerless` 在 `aa1d354` 就已排除 BUSY，這次只補 Javadoc。依據是 spec §6 :190：「其他端busy為11而不取得門控制權」。
  - `HomeRootTickets:140,176`：兩則帶 SID 的 log 補上 `CollapseService.LOG_MARKER`。依 `CollapseService:111–113` 的規則，能歸屬到 session 的 log 用 `LOG_MARKER`，不用 TICK。訊息、等級與參數不變。
  - 證據放在 `S/t3-fix2-gates/`：未修改的 HEAD 上兩案各 1 失敗（具名 RED）→ GREEN 19/19、2/2 → 三個 direct owner mutation（:140 marker、:176 marker、BUSY 排除）全部 KILLED，且以備份還原、SHA 相同 → final GREEN。
- **fix2b**（`3aa493e`）：處理 N2 的 M1、M2，只改註解、區域變數名與斷言訊息：BUSY 不交接的方法註解，以及 spec 引用統一寫成「§6 :190」。
  - 在 commit 後的乾淨 pin 上跑 focused：power 19/19、marker 2/2。
- **N2 審查**：fix2 是 C0／I0／M3，fix2b 增量複核 C0／I0／M0。原件在 `S/t3-fix2-n2-review.md`。
  - **M3 帶到 T4（未修，T4 開工時必須處理或請使用者裁示）**：`OriginDoors.java:597–637` 的 `applyOwnerless` 在套用時只讀 `journal.flushedRecords()`。這份資料經 `SessionRecoveryState.legacyRows` 過濾，看不到 `HomeSessionRecord`。
    - 後果：排入在先、A 之後才因 B 的 HOME 變成 BUSY 時，A 的 stale 量子膜仍可能被改成一般開門，和 spec :212 不對稱。
    - 目前沒有 runtime 證據證實會觸發，後果也不會關住人或損失資料。
    - 最小修法是套用前比照 `:666` 再查 presence。
- **下一步**：在這次文件 commit 上跑完整回歸。production pin 是 `3aa493e`。
  - 完整回歸的 AccessDenied 重跑額度已用完（1/1），同簽章再現就停。
  - 完整回歸的啟動器歷史為 2/3，下一次非預期的工具失敗就停。
  - 通過後正常 push，等同 SHA 五個 required CI 都成功，T3 才算結案。

## 歷史：使用者要求收尾交接；T3 full兩JUnit FAIL未修（2026-11-14）

真user本輪原文「準備收尾交接」（primary root轉述，S/user-handoff-steering-20261114.md）；本輪停止新fix/runtime/full/push/T4，沒有取消整個既授M7範圍。精確交接：`docs/handoffs/2026-11-14-m7-t3-handoff.md`，接手先讀此檔，不沿下面歷史「現在進full」文字自行恢復。

- T1/T2仍closed；T3 sourceaa1d354e／N1 C0/I0/M0及已驗7558b0e原判保留，但full HEAD0e8b9c1/rootcfc16d21…/holder6b571243…自然exit1，Gradle:test1164=1160pass/2fail/2existing平台skip，無ENDALL/summary、defaultGT未啟。98freshJUnit及manifest保全；terminal SHA A0A3B336…90BE3B。
- 兩FAIL：ChamberPowerCoordinatorTest舊enum loop把新BUSY錯期待normalizesOwnerless=true（XMLC55DD4E0…CD4C0EB），production拒BUSY符合A不改門，待核契約後最小同步expectation；HomeRootTickets140/176兩新log缺既有CollapseService.LOG_MARKER（XMLC0CBCCB8…A6637D8），待加既有marker，scanner不改。沒有fix2，不把它們算fixture第四次或fullDriver第三。10:50舊runtime latest內AccessDenied不屬本輪。
- source/HEAD在draft前clean/ahead8、production對src/scripts/build/workflow無差。原件/author報告、source因果、計數、原user恢復題/答案與續作順序全部列handoff。owner/locks fresh只讀postcheck254把可exclusive、scope內javaProcesses/runtimeOwners皆空；該proof不含較早CIM所見Daemon PID，兩來源不混；原defaultworld不存在，freshEvidence保留無鎖，未kill／刪world。
- 原fixture3/3、任何新同fixture非預期即停、AccessDenied1/1、fullDriver歷史2、mutation-site及marker-count舊事件/semanticKILLED分層與N0=6→N1=0全保。N2/full後續/UbuntuT3/首次CI/T4～8未啟。原reviewer只核handoff事實／鏈結／授權，不是N2/code/full新通過。
- 原席交接事實窄審最終docs C0/I0/M0（S/t3-handoff-facts-review.md，SHA244D515F…670C37F），H-M01觀測來源歸屬已修／增量核；不是N2 source或full／CI通過。
- 交接docs只本機commit，不push/CI：依user收尾且code gate當前FAIL，避免只換docs盲觸已知失敗CI；不是CI豁免／CI通過。正常push原授權保留，main/tag/PR/LICENSE/人工開始/退役仍另授。下一棒收到續作指示再依原scope處理兩JUnit、同席C/I0後完整門檻。

## 使用者新裁示：T2 起連續推進與新止損（2026-11-13）

來源：本 Codex 使用者對話；root 原文檔 `user-t2-continuation-ruling-20261113.md`，非技能或 Claude 建議稿。使用者原文：

> T1 結案已核實（CI 43418385083 五 job success、src/main 無改動、PoC 已移除、歸類合理）。請開始 T2，之後依「審查清零就直接往下做（建議）」連續推進。
> 1. I2 保留金是正式設計的正確性問題，要在 T2 以 production 方式解決並附 RED／mutation 證據，不能再移到後面。
> 2. 驗證標準維持「M6 同等」；審查意見超出時列殘留，不擋關。
> 3.（Claude 建議的止損）同一個 task 整改兩輪後，Important 數量沒有下降，或同一個驗證工具連續第三次非預期失敗，就停下回報，不要繼續疊修。
> 4. 每個 task closed（同 SHA CI 全綠）時簡短回報一次：task、code pin、CI run、殘留、下一個 task。

現行流程：

- 從 T2 依已審 plan 連續推進至 T8 已授自動化／整分支審查／docs；每 task C/I 清零且同 SHA required CI 真全綠才 closed，直接下一 task。T1 後「本輪不 T2」及 I11 單次處理限制均為歷史，已被本裁示取代；不重開 T1 或 spec／plan 審批。
- I2 是正式資料正確性義務，在 T2 由 HomeJournalBudget／正式 schema6／共用 writer 修復，附 fixed32 保租 root latch／COLLAPSING 具名 RED 與直接 production-owner mutation；不得延期或當超 M6 殘留。
- M6 同等限定驗證工具與證據做法；不豁免 binding 功能、strict schema／合法來源／預算／所有共用 writer、production RED/GREEN/mutation/focused/full/review/同 SHA CI。超標工具意見列殘留，不默認塞 T4。
- 每 task 同範圍記 N0、每整改 N1/N2…（Important 總數含新項）；任一連續兩整改輪窗口未下降即停報，不做下一輪。不得藉重命名、nonce、docs 或無證據重新分類洗數；Critical 永不帶過。
- 驗證工具依穩定 problem ID 記連續非預期失敗，同問題第三次即停，安全收回相關自有 owner 並保全原件；expected RED／negative／mutation 正確拒絕分列不算工具缺陷。production bug 與工具 bug 依證據分開；既有 Evidence3／NativeStartup3／main-only2 歷史原判保留，已移除框架不復活，不任意合併無關問題或拆簽章迴避。
- 每新 task fresh implementer＋一位獨立 task reviewer（spec＋quality 同席），fix 回原作者。controller `/root/m7_t2_onward` 是 note／AGENTS／plan 核取／progress／STATUS 唯一 writer。每 closed 短報 task／code pin／CI run／殘留／下一 task。
- 人工代操作開始（方式已選 Claude）、main ff-only、tag、PR／LICENSE、worktree 退役仍各自未授。正常 feature push 已授；既有 W10 producer 只核身分／hash／非reparse／owner／locks，不重建或重套 patch。保全部原 world／FAIL／UNKNOWN，修正用新 nonce/root。

## T2 CLOSED；接續T3（2026-11-14）

- T2 production `0a36323e469f190f432c19d16975d92742c7544c`，source N3 C0/I0/M1、本機full HEAD5df701af自然ENDALL0/actualExit0，成功CI/docs HEAD `9358368eab7893c14422e0a62276d09c815d32ab`。CI43554024617 attempt1/eventpush實際5required全completed/success，root已獨立核terminal身份；T2現closed，不代表M7整體完成。
- 兩OS M7原件已真核：Win26JUnit/4GT、Ubuntu26JUnit/3GT，三FS案例無fail/error/skip（Ubuntu真symlink）、各九rawhash/11controls。Windows舊原XML166/13/1/60/35與main-only PASS/head935；M6-native原summary9鏈27phase及原log479反例全部符合既有scope。所有原件索引在本SDD T2-CLOSEOUT.md/json及ci-43554024617/。
- 殘留T2-M03僅測試Win32 attrs失敗值診斷，非阻塞，交wholebranch；不加fix4。原CI43540208676/原AccessDenied/所有FAIL/UNKNOWN全留，full quota1/1耗盡不刷新，舊run未rerun。
- 閉合狀態只本機docs commit，不為回填CI再push。現在依原user連續授權直接Task3新implementer＋獨立review，scope/spec/plan不重開審批；T3 BASE為8a6124416108621f895fa8e9fd57d1b39efdbcea，fresh作者已實作，詳下節。main/tag/人工開始/PR/LICENSE/退役仍未授。

## 使用者 async 裁示：T3 保原判，從 fresh N0 恢復（2026-11-14）

來源：主chat使用者async回覆，primary root核實轉交；原文另存SDD `user-t3-resume-ruling-20261114.md`，不是controller自行突破止損。

原題：

> T3 因同 fixture 3/3 非預期失敗已止損。是否保留原判，另授權候選 a5061383 從 fresh N0 恢復，C/I 清零後續完整回歸與新 SHA 首次 CI？既有證據不盲重跑；同 fixture 再非預期即停，AccessDenied 額度不刷新。

使用者所選原文：

> 允許 N0 後續完整門檻（建議）

- 原STOP／同fixture3/3與全部原FAIL保留，不撤銷也不重分組洗數；同M7HomeAuthority fixture任何新非預期失敗立即再停，不等另三次。AccessDenied完整回歸重跑額度1/1耗盡不刷新，full driver歷史2及其他舊計數保留。
- 恢復點是現候選sourcea5061383、STOPtruthdocs8d933e1、已驗HEAD ee2437e：先核實git/原件無漂移，fresh獨立N0審BASE8a612441起的完整候選。已齊29 M7JUnit／8GT／11controls／guard2+12／五direct mutations不盲重跑。若實質修正，回原作者具名RED/GREEN／direct mutation／focused與同席增量review；原兩輪Important總數含新項不下降止損仍生效。
- N0 C/I0才fresh核已存在W10 producer pins/owner/locks/非reparse、actual child65001，沿M6 handle／ExitCode／stdout與stderr分檔跑新pin完整full；自然ENDALL0＋實際exit0＋summary原件全採，才正常feature push／遠端讀回／新SHA首次五required CI。全綠才T3closed五欄並直接freshT4；沒有舊CI4354 rerun、盲docs重推、main/tag/人工開始/退役授權。
- 恢復前actual HEAD8d933e175bf8c7b49e33d7411d74b3e6b7e9ab14，tracked clean/ahead4；src/scripts/build/workflow對sourcea506差異空。所有舊STOP索引不覆寫，作歷史原件。

## T3 fix1 N1 清零，待完整回歸／CI（2026-11-14）

- production sourceaa1d354ec4e8de0b8bfee5e553f83f3378431618；已驗HEAD7558b0e3557e17489165ae321a448c059b198cb5，Windows29JUnit/9GT/11controls、原guard2+12全部符合，十rawhash與livebytes已核。完整證據t3-fix1-evidence.json與task-3-report.md。
- 原席N1 task-3-fix1-review.md SHA B1858E1EB954E86553B52A93619F314B42993804330F86569B1AECBEA9226E3A：spec通過／quality核准，C0/I0/M0；原I01～I06/M01全ADDRESSED，無新增退化。round表N0=6→N1=0，下降成立，不以C/I0冒稱taskclosed。
- 九變異原XML/livebytes全符；expiry語意KILLED獲同席核兩合法SID呼叫／原assert，原driver UNEXPECTED/killed=false/exit1仍保留。T2 site-count1／本次marker-count1實際入口歷史、原fixture3/3與再非預期即停、AccessDenied1/1、fullDriver歷史2皆不刷新。
- 現進新候選完整full前置：只核既有W10，不重建／重套；確認固定pins/hash/非reparse/owner/鎖及child65001，沿M6同一handle/HasExited/ExitCode與分檔stdoutstderr。固定source/HEAD直到自然終局，任一新FAIL保原件分類／停報，不盲重跑。全summary＋自然ENDALL0／actualExit0才普通push／遠端讀回／新SHA首次五requiredCI；全綠才T3closed並freshT4。full／CI目前尚未執行。

## T3 N0：六項正式缺口，原作者 fix1（2026-11-14）

- 本次明確恢復後fresh reviewer N0（SDD task-3-review.md，SHA B94B0B7C…A7583）已終局：spec未通過／quality需修，C0/I6/M1。原審查BASE8a612441至35e0f40，sourcea506不混；未起runtime。
- N0 Important6：I01 A撤銷遮蔽有效B、I02 catalog/lifecycle分類、I03 stop/CLOSE/expiry本地仍PASSAGE、I04未返還／lease的COLLAPSING過早可PREPARE、I05釋票例外中斷其他SID／event、I06第201 tick首次FULL與雙SID真native重疊ref的直接見證。controller核source/spec確認為binding正式正確性或T3明列GREEN，不是超M6工具框架。
- 原作者已接fix1（FIX_BASE35e0f40），具名RED/GREEN、實改owner direct mutation/bytes還原、必要focused後交同席N1；總數含新Important，N0=6/N1待，任兩整改輪不下降止損仍有效。M01 callback生命週期Minor先殘留；若直接是I06/原cleanup的必要最小修改可同修並說明。
- 原fixture3/3及所有FAIL保留，同fixture再任何非預期即立即停；修新fixture先靜態核A登記／載入／觀測來源／asyncfinally。full與CI仍在C/I清零後，不盲重跑舊證據，不刷新AccessDenied1/1或driver歷史2。

## T3 fix1 候選與具名證據（2026-11-14，N1尚待）

- FIX_BASE35e0f40；原sourcea506的六項目標RED為t3-fix1-red-ba9aabc5…，9GT兩個預定assertions，exit1，XML328965C20C4424EA0EC6274A944A2733F3EDB92EF6CFEEB4B5E2DCE2FA2F800F。聚合target標籤對應I01/I02/I03/I04及I06控制點，另一案I05_RELOAD_DROP_OTHER_OR_THROW；不混為fixture非預期。
- 修後GREEN t3-fix1-green-2c3a58f88f934c42a33c499211f32ccf於35e＋dirty有actualexit0、9GT零failure/error/skip，XML SHA34B31189360DFF08D50B7FCA74584BD54F1F85E3688976D42ABB3CCBEFB6D0C1已controller讀核。source候選aa1d354ec4e8de0b8bfee5e553f83f3378431618（8檔）已凍結，九direct owner mutations自然終局並全部還原live bytes；八項原driver KILLED，expiry一項原driver FAIL/只讀semantic KILLED待同席N1，不能合寫九driver PASS。controller核九XML SHA與owner livebytes全符，後三continuation driver自然0；本次truthdocs後跑29JUnit/9GT＋原guard/protection focused，再N1，尚無full/closed。
- I06新增見證使用既有Backend/testmod seam安排1–200 FULL不可見、第201讀既有原生FULL，不宣稱Minecraft worldgen首次完成；舊租約201仍拒、退避後同SID續行。原RED I06_EARLY_FULL是seam觀測標籤，不把已FULL A正常提前可讀當productionbug；雙SID共chunk原生ref計數沿既有backend。
- mutation local-expired原根5807649e…原XML只有預定case，兩個合法SID各產I03_EXPIRED一次；原driver EndsWith單marker誤設而UNEXPECTED/killed=false，nativeExit1，XML AD2FCC6FE937B58316E4974832355B8EA3104AE334FA22B848C5CDCBF64D810D。原FAIL與result保留不改、不重跑，controller只讀semantic KILLED待N1核兩呼叫語意；exact兩marker最小修法僅續未跑三項。工具歷史表t3-fix1-mutation-driver-history.json並列T2 site-count1與本次marker-count1的實際driver／共用Gradle+XML流程，不依operator換名洗數，若同實際driver第三非預期即停。不是fixture第四次，也不是fullDriver第三。
- 原fixture3/3與任何新同fixture非預期立即停、AccessDenied1/1／full driver歷史2全保。N0總Important6／N1含新項待，同席C/I0後才完整full與新SHA首次CI。

## T3 止損暫停：同一權威測試 fixture 三次非預期失敗（2026-11-14）

- 現在T3 OPEN／STOP；production `a50613830004d083b2fe39e3f1edf38c712a013b`，定向驗證HEAD `ee2437e7137ed18162f609c1ad2e0748a7d1fdd3`。先前進度只併兩件，作者補核漏列事件後，同M7HomeAuthority／runGameTestM7 fixture依原user第三次規則及root保守分組累計3/3；不得用後續成功、nonce或docs改判／清數。這是流程暫停，不是T3結案。
- 三原事件皆HEAD8a612441＋當時dirty、actualexit1，同具名 `only_exact_complete_projection_resolves`：665f9df0…空A UUID（XML SHA3AFD4B84A5BD5AB34987416CF8E26F1E1B81D3C915A760DE8B6FF0245DA9BF24）；3ec4d9fe…已確認COMPLETE辨識assert失敗（880B8CD574E4B878F8952D6C6A751BAC16E01A8FE9F538EFEEF63936FE9A0FD3）；6ae35c35…A/B本地presence雙assert失敗（229863AC1C7F7A9C83CE4BBC887DCCD1AE41789F2E336A1A145499847F82E97E）。前者為fixture未正式A登記；後兩者原因依修前後對照推論為初始預載範圍不足／400tick後B站卸載，原XML未印對應值，不冒稱原XML已證唯一根因。完整命令、stage、dirty、原XML/log見SDD t3-fixture-stop.json；subject階段與修前後語意見t3-fixture-cause-readonly.md。三根未保存當時完整Java bytes，最終line僅修後對照，不能當歷史source hash；未重演，也未以修後PASS證唯一根因。
- 停報前已啟的9968920… official runner工具session49513終局exit_code0（沒有獨立outer exit檔），29 M7JUnit／8GT／11controls；guard root2007060…的Gradle exit.txt實錄0、原dependency2／protection12全部PASS。controller只讀核10 raw hashes與37個M7 testcase零failure/error/skip、11controls正1／負10符合，五個a506 owner mutations目標assert KILLED及live bytes/SHA還原。這些結果保留，但不抵銷3/3。
- 獨立N0／完整run-all／本候選CI／push均尚未啟動，Ubuntu新集合未實跑。無T4；不啟新runtime/source整改/review/full/push。既有AccessDeniedquota1/1、full driver歷史2及T2等舊FAIL/UNKNOWN全部保留。
- 必要只讀postcheck（SDD t3-stop-postcheck.json）：三原FAILworld與996的world共四把session.lock皆可獨占；無遊戲runtime owner，僅現存GradleDaemon PID17700。不殺Java、不移刪或改世界；tracked真狀態只本機提交，不push觸CI。root收短stop索引後依user止損交回，不能憑原連續授權自行恢復。

## T3 止損前進度快照：端點權威、唯一占用與有界原艙票（2026-11-14）

- 原user連續授權下，fresh作者 `/root/m7_t2_onward/t3_implementer`（gpt-6-astra/high）以8a612441為BASE實作；controller保留單一狀態writer，未重做T1/T2。普通B guard仍至T7才開。
- 初RED root t3-red-665f9df0…的跨world同XZ coverage真assert expected26/actual25，以及 provisional首票前占用assert失敗成立；同root COMPLETE案僅fixture空A UUID例外，不採功能RED，保原件／fixture事件1。正式A attemptArm登記後新root t3-red-38e8a9cb…兩項精準GT RED待最終report原件核。
- production候選已凍結為a50613830004d083b2fe39e3f1edf38c712a013b（22檔）；作者報該pin五個直接owner mutations各命中指定assertion、KILLED／bytes SHA還原，driver exit0。最後同內容focused root50516afb…已有29 M7JUnit／原guard及protection兩班／8GT exit0；下一步在本次truthdocs HEAD跑正式runner＋focused，再交N0，尚非taskclosed。追加400tick後B測站自然卸載的fixture FAIL原件保留，改為玩家回來時自然載入站點，沒有當目標RED。
- 原艙FULL輪詢使用HomeRootTickets的getWorldChunk。固定Minecraft1.21/Yarn build9 javap原件為SDD t3-nonblocking-getWorldChunk.txt、holder.txt；controller已只讀確認cache或getOrNull→getUncheckedOrNull→CompletableFuture.getNow，沒有同步等待chunk；jar身分與精確source交最終report及獨立review核。
- 必要增量接線：gates.ps1／m7-ci-gametest.ps1／ci-rule-check.ps1以受控inventory執行新增corridor JUnit／GT並核exact，保原T2及M6集合／閾值。ChamberPowerCoordinator／SuperpositionSession／SessionRecoveryState接線由作者逐項列對應原T3契約，與新增testmod backend fixture同交原task reviewer。
- 門檻順序採候選＋具名RED/GREEN＋直接owner mutation＋focused齊→fresh獨立N0（spec+quality）C/I0→固定pin完整full→正常push／同SHA五required CI真全綠。full在途凍結source/HEAD；AccessDenied1/1與full driver歷史2、舊FAIL/UNKNOWN不刷新。

## 使用者新巡檢裁示：fix3只守canonical save內（2026-11-14）

來源：本 Codex 使用者新訊息，由root完整存於SDD `user-t2-ci-path-ruling-from-root-20261114.md`。以下逐字照錄Markdown跳脫；實際FS路徑字元以CI原件為準，不把原文中的跳脫直接當測試輸入。

> 巡檢回饋（11-14）：CI 43540208676 的 Windows 失敗已由 Claude 從 artifact 定位，是確定的 production 缺陷，不是偶發，不要重跑 CI。
>
> 根因
> - 兩個失敗 job 的 JUnit XML 都是：IOException「收據目錄不符合存檔實體路徑：C:\Users\RUNNER\~1」。
> - HomeReceiptStore.directory()（:17-30）從磁碟根逐層要求非 reparse，且 toRealPath().equals(cursor)。GitHub runner 的 TEMP 是 8.3 短檔名 RUNNER\~1，toRealPath 展開成長檔名，比對失敗。本機路徑沒有短檔名，所以本機 full 抓不到。
> - 本機重現：Paths.get("C:\\\PROGRA\~1").toRealPath().equals(...) 為 false。
> - 玩家影響：存檔上層只要有 junction、符號連結或短檔名，回程收據就一律 fail closed。
> - spec §165 只要求收據的檔案與目錄是 canonical、非 reparse，而且在指定 save 內，沒有要求 save 以上的每一層。
>
> 修法方向（T2 fix round，production 完整門檻）
> 1. 建構時把 save 根 toRealPath 一次；之後只檢查 save 以內的 data、quantumchamber_home_receipts 和收據檔，save 以上不檢查。save 內任何一層是 reparse 仍要拒絕。
> 2. 具名 RED：Windows 上用短檔名（GetShortPathName）和 junction 祖先構成 save 路徑，發佈與查詢要成功；save 內的 data 是 junction 時仍要拒絕。並以 mutation 證明守門有效。
> 3. grep 其他新的 production 程式有沒有同樣「逐層檢查祖先」的寫法。
> 4. 原 FAIL 與 CI 原件保留；新 code pin 走 focused、完整 run-all、同 SHA CI。完整回歸的 AccessDenied 重跑額度已用完，依原規則處理。

現行技術邊界與流程：

- fix3正常feature push與遠端exact讀回9358368eab7893c14422e0a62276d09c815d32ab已完成；source0a／本機full5df／CI-docs935分清，非force/main/tag。新SHA首次CI43554024617 attempt1/eventpush，實際5required jobs全in_progress，initial原JSON在ci-43554024617/。舊CI43540208676仍原FAIL、未rerun；無CI成功或T2closed先行聲明。
- CI在途truth只同步workingtree docs與ignored ledger，不再commit/push觸發第二CI。controller單一路徑保存原件；新Win需26JUnit/4GT，Ubuntu需26JUnit/3GT與symlink三例真實跑，5jobs全success才閉合及直接T3。任何FAIL保原件先報，不盲目rerun/換docs重推。


- fix3 production 0a36323e469f190f432c19d16975d92742c7544c 的首次完整full已自然PASS：11:50:32 ENDALL0，驗證HEAD5df701afae0ad16a8ecca4bc0eaf35e7abfbae96，原PID22708/同一handle actualExit0、exec outer0、exit.txt0。summary SHA DFAC895B0BA63932CFF5CC84F7A44D43A86D12228AA2B9AF1F6185E02A081A7B，root run-all-9f9fd955e2aa42eba3409786d3c4c9a2；controller t2-fix3-full-controller-readback.json親核head/summary/原退出/九raw/M7XML，root另親核。
- JUnit1161 failure/error0、2個既有NonWindows checkpoint平台skip（M7無skip）；M7Windows26JUnit/4GT、default166/normal13/legacy1/M560/M635、main-only/隔離、recovery25/collapse69/M6native9/27各按既有scope完成。post13 runtimeOwners0/225鎖/pins hash exact/非reparse，worktree clean/runtime diff空，report SHA144AB3022BE6DAE13D68E5652675C74CCF0D2AF4A3BE6A6BA1BB8AF085F3E815。
- 下一步必要truth docs後正常push新實質pin的新SHA首次5requiredCI。原CI43540208676自然FAIL不rerun，Ubuntu真symlink三案/26JUnit3GT待新CI，Win26/4；T2仍OPEN/T3未啟。M03列殘留，不新增本機回放/Minor修正。AccessDenied quota1/1耗盡不刷新、driver舊工具2/site1/所有FAIL保。


- 原唯一席N3正式通過：spec符合／qualityApproved，C0/I0/M1，I07 ADDRESSED。N0=5→N1=1→N2=0→N3=0，沒有刷新輪數或帶過Important；scope審0與sourceN3採用分清。唯一殘留T2-M03為Windows測試helper未先排除GetFileAttributes=-1，因junction另有cmd成功與sameFile驗證/真mutation，屬非阻塞診斷缺口，留wholebranch不另開fix4。
- 現在production固定0a36323e，N3 source全清；postcheck12 fresh核164鎖可獨占／runtimeOwners0／producer固定pins/hash/非reparse。必要truth docs後直接新pin完整run-all，沿已工作M6同一process handle/HasExited/ExitCode、stdout-stderr分檔與actual child65001；不再跑focused或N2/N3，不改source/HEAD在途內容。
- 此新codepin的完整full不是重置重跑額度：AccessDenied full quota1/1仍已用完，若同簽章再現停報，不能自行再跑。fullDriver既有工具2次/site1/原CI所有FAIL保留。full自然ENDALL0＋actual ExitCode0＋summary才採；成功後正常push新SHA首次5requiredCI，舊CI43540208676不rerun，全部真success才T2closed→T3。


- fix3 production新candidate `0a36323e469f190f432c19d16975d92742c7544c`（5檔）已凍結，實作按新user只守canonical save內；該candidate原件由下述N3採用，source I07已清但完整full/新CI仍待。五類focused 13/9/52/13/41共128，Windows M7 26JUnit/4GT與11SelfTest自然0；controller `t2-fix3-focused-controller-readback.json`已核實際XML/hash/九raw/pin。非Windows symlink分支只完成實作／靜態，待新SHA Ubuntu CI，不冒稱本機Linux已驗。
- 三真Windows FS具名RED原9案恰兩個正例FAIL，真GetShortPathName取得JUNIT4~1及真linked-worlds junction祖先傳入owner被舊行30拒；內部data-junction負例原本即PASS。修後GREEN9/9；兩直接owner mutation（撤canonical→兩正例FAIL；撤內部guard→data-junction拒絕assert FAIL）由controller核原XML/hash、保存原/變異Java bytes、還原SHA/currentOwner一致。post盤點正式clean檔 t2-fix3-post-production-path-inventory-clean.txt（先前mutation在途版僅歷史，不採）。
- 作者report SHA `9E6FA6CE8E17FAB5D6E99F34FD5BCA350B173721780FF7F7F1B853D549480AC1`，before-full副本保原。仍須同席N3 C/I0→新pin完整full→新SHA首次5requiredCI；不重跑舊CI43540208676，AccessDenied full quota1/1不刷新，所有原FAIL不改。

- 以上是真user新binding，**取代**root及原席先前「先驗save以上原始祖先鏈非reparse」的技術推論。舊 `t2-ci-windows-path-readonly-analysis.md`／`t2-ci-path-contract-review.md` 保原判為歷史，不能採為新版清零；其原始bytes保留，不用新用語倒寫舊結論。
- 構造時對save根 `toRealPath()` 一次，存取時只檢查canonical save以內的 `data`、`quantumchamber_home_receipts` 及收據檔。**save以上junction／symlink／8.3短名允許且不檢查**；save內任何reparse仍拒。不是刪掉內部安全gate，也不是新建handle-based FS／alias框架。
- Windows真 `GetShortPathName`＋ancestor junction的save輸入須具名RED後publish／lookup成功；save內data junction仍拒，直接owner mutation證守門。其他OS用真實對應契約和精確集合，不能skip冒PASS／新增Linux程序管理。fixture採正式save/data布局但保原始alias正向輸入；rg其餘新增production同型祖先檢查。
- 使用者提供的Claude artifact定位及本機PROGRA~1復現是外部已提供證據，controller本輪沒有執行該復現。原CI14個直接stack／2個上層斷言及未保存的predicate分項仍照原件，不回填當時觀察；新user已明確定義正確保存邊界，正式整合缺口T2-I07進fix3。
- 原唯一席已按本段及spec§5.3/test matrix/plan新版窄確認：scope C0/I0/M1、Approved（Minor門檻列舉順序本次同步）。此為新版方案確認，不是source I07已修，也不採舊上層拒絕報告；現在原author開始fix3 production完整門檻；新codepin focused／mutation齊後先同席N3 C/I0，再完整run-all／新SHA首次CI。不是重跑CI43540208676，也不只換docs盲推；其他remote jobs自然終局，不取消。此段裁示記錄時源碼仍f3；現行candidate/驗證進度以上方新條目為準。
- AccessDenied完整full重跑額度**1/1已用完**，不能因新pin重置；full driver歷史工具2次與原mutation-site1／原FAIL／UNKNOWN全保，止損不洗。main/tag/PR/LICENSE/人工/退役仍未授；技術窄修不再另問user核可。

## 使用者 async 裁示：T2 原 pin 單次完整重跑（2026-11-14）

來源：本 Codex 使用者對 root async 題目的明確回覆；原文另存 SDD `user-t2-full-once-ruling-20261114.md`。題目原文：

> T2 的 source f3a79ec 已 C/I 清零，但完整回歸有 1／60 個 M5 GameTest 因 Windows AccessDenied 失敗。是否允許保留原 FAIL、不改碼，用新 nonce 完整重跑一次？同簽章再現即停；通過後才續 push／同 SHA CI，不包含 CI rerun。

使用者選項原文：

> 允許原 pin 完整重跑一次（建議）

- 現行授權解除本項runtime HOLD：source `f3a79ec182653492fcbe5a41313cbf1e41c2a4e8` 不改碼／測試／script／build／workflow，保全部原FAIL/UNKNOWN，僅新nonce短預設root**完整run-all一次**。授權記錄時retryUsed為0/1；實際於2026-11-14T00:04:52.5937338Z啟動後已記1/1；必要truth docs可本機commit，docs SHA不重置同code額度。下方未授/HOLD文字是裁示前歷史，不覆蓋本段。
- 跑前fresh核HEAD/dirty與f3 runtime內容無差、W10既有producer身份/pins/非reparse/owner/locks及actual child65001；沿已工作M6 process handle/HasExited/ExitCode、stdout/stderr分實檔，不改gate/threshold、不造新framework、不重做T1/N2/focused。
- 自然ENDALL0＋actual process ExitCode0＋全summary才採。同AccessDenied再現立即停；同fullDriver第三非預期工具FAIL立即停（歷史2/3），其他FAIL保原件先報/分類，禁止盲重跑。
- 成功才必要docs truth／正常feature push／遠端讀回／追同SHA全部5 required CI真終局；不授CI rerun或CI失敗換docs重推。真全綠才T2closed，依既有連續授權進T3。source仍不得修改；main/tag/PR/LICENSE/人工開始/退役仍未授。
- 原stop-index保runtime AccessDenied原FAIL1，單次重跑已用1/1；原報告8b42…已保獨立before-authorized-retry副本，原XML/log/world不動。

## T2 前pin本機PASS／CI43540208676終局FAIL原件（2026-11-14）

- 已依條件正常feature push：`56fcbdb70ed3affcb0943ca9ee48db0106787765`，origin `codex/m7-return-home` 從57ca71a快轉，非force/main/tag；ls-remote exact同SHA，proof `t2-push-readback.json`。production仍f3，本機完整full驗證HEAD9bd與pushed docs pin56分清，runtime內容diff空。
- 已出現真同SHA [CI43540208676](https://github.com/Ben513242/QuantumChamber/actions/runs/43540208676)，attempt1/eventpush，實際5 required jobs已自然終局：M7 Ubuntu/build/m6-native success，M7 Windows/windows-native failure，initial JSON在本SDD/ci-43540208676/。目前未CI全綠，不得closed/啟T3；只讀watch與保原件，不rerun、不為CI改docs重推。
- 這筆push/CI在途真相先同步workingtree docs與ledger，**不另commit/push製造第二CI**；終局後必要local closure docs由下一正常task提交帶上。
- **唯一授權重跑已PASS，used1/1。** production `f3a79ec182653492fcbe5a41313cbf1e41c2a4e8`、驗證HEAD `9bd1959d16a0c708095ee002bc9780e11e071540`，09:16:27自然 `END ALL exit=0`，原holder PID38032同一handle實際ExitCode0、exec outer0、exit.txt0。summary SHA `7A40006A3CC6C2A03E6E0D8D1AECAA0D59E7373AFD51B3C8F1F508FF03B6C9DB`，run-all `2335932e4e614a8ea5c51ccae3e365bd`；controller `t2-authorized-retry-full-controller-readback.json`已親核head/退出/summary與M7九raw/XML。
- 本輪default166/normal13/legacy1/M560/M635、M7 Windows23JUnit/4GT零nonPass，完整JUnit1158、failure/error0，2個既有NonWindowsPlayerCheckpointStore平台案在Windows條件skip（M7無skip）；main-only、M3、recovery25、collapse69、M6 native9鏈27phase（2diagnostic/25functional）、本機CI規則按既有scope完成。不將預定halt77稱全正常停服，也不把本機Ubuntu規則模擬稱真正Ubuntu CI。
- 前次失敗的同名projection overlap案本輪通過，M5原XML60/60、零fail/skip，SHA `B70A22C3…`；原b893 FAIL/E0FBB212…/08625635…及當時world末態全部保留不改。重跑成功不證OS拒絕的底層根因，亦未修M5production/OS/oracle。fullDriver舊工具事件2次仍保，沒有第三工具失敗；本次waiver用盡，不含CI rerun。
- postcheck11：run內/producer163把session.lock可獨占、runtimeOwners0、producer固定pins/hash/非reparse exact；worktree clean，對f3 source/tests/scripts/build/gradle/workflow差異空。原作者報告已追加本輪，SHA `C13AA61C75FD87ACD2604C6B99259F66B67C96D8BC9D9CC092C9AF282D76894E`。
- 現在只做必要truth docs與正常feature push/遠端SHA讀回，再追同SHA全部5 required CI。**CI尚未執行／核實，T2仍OPEN，未啟T3。** 不另做本機測試/重跑N2；source仍f3，新的docs SHA不是新production pin，也不重置任何額度。CI FAIL保原件停報，不rerun或換docs重推。

以下為各次歷史原件／停點，不能用其中舊現在式覆蓋上方最新授權full結果：

- 修後完整full已自然FAIL：source `f3a79ec182653492fcbe5a41313cbf1e41c2a4e8`、HEAD `cd91b9407d101bc98f7f1f29b8947d0d7a07ad8a`，run-all `b893e94501cd4233800161b4f6965a9a`。預檢/W10/full-default/normal/legacy通過，M5 GT60案恰1FAIL，具名 `prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection`，只允已確認projection結果卻得到WRITE_FAILED。後續M6/M7/full summary/CI未執行，沒有ENDALL。
- 00:27:30原log同case BEGIN後，CheckedNbtFile:70→CollapseAuthorities.writeAtomic/checkedWrite/writeProjection發生 `quantumchamber_projections.dat.<nonce>.tmp -> quantumchamber_projections.dat` Windows AccessDenied；checked保未確認／200tick退避，非F4a注入。XML SHA `E0FBB212A401F47CEF35F23A34432F5DC42494782B1F5D71C28DD0D30ED18C4E`、latest.log `08625635AAB611F00A6443F87951BBEC03D324694D0B2747838EE8F0014A6B1C`。四個直接writer／ledger／測試owner對main2255027的Git blob未變；誰持檔／OS拒絕原因未定，不稱已證偶發。
- M6最小driver持原handle正確觀察actual process ExitCode=1、outer自然1，不是EOF卡住/driver工具第三次。driver工具失敗歷史仍2/3，本次獨立記runtime AccessDenied FAIL；N2 C/I0不因此改成full已過。原N0/N1/N2及所有FAIL/UNKNOWN保留。
- 原gate world-restore exactRestored=true/originalExisted=false/deleted=false，完整failed world保在該gate/world-fresh；其中projection.dat末態1796bytes/SHA `AB9AACBE3641F11E4AFEB6081BE96AD36EC3CF39E45296F70AAEF25EBE2F25BF`，不是失敗瞬間快照，不重建temp。controller postcheck09：run內/producer102鎖獨占、runtimeOwners0、固定pins/hash exact；另保存failed world session.lock亦獨占。無樹殺、無世界刪覆。
- **現在runtime/source/push HOLD，只讀與必要本機truth docs。** 依本note流程節「M6其他條款不預設沿用，要用時另問使用者」，本次T2連續推進沒有包含AccessDenied一次重跑waiver。root將詢問明確一次處置；未重跑、未修M5production/OS/oracle，尚未push/CI或啟T3。
- 具體待同意提案：source/harness維持f3內容，保原FAIL，用新nonce只重跑一次完整run-all（短預設root/M6 handle/child65001/freshW10）。同簽章再現即停，不再重跑；其他FAIL先停保原件分類。成功才必要docs/正常push追同SHA5 required CI；未授CI rerun。提案與短索引在SDD `t2-access-denied-retry-proposal.md`／`t2-access-denied-stop-index.json`，重跑使用0/1、授權尚未取得。
- 唯一原席N2終局：規格✅／品質Approved，C0/I0/M1、N2=0，I06 ADDRESSED；N0=5→N1=1→N2=0，兩輪止損未觸發。唯一新M02是本節舊時態「source現9d1」，本次同步改為「啟動fix2時的production基準是9d1」，不改production。原I01～I05/M01處理判定維持，N2原報告保留。
- 下一步按已授權，不另等核可：在乾淨新docs HEAD（production仍f3）與freshW10/owner/locks核實後，執行修後完整run-all，再原席沿同席核終局/必要docs，正常push讀回並追同SHA全部required CI。M6 outer最小呼叫只沿probe-common:221/224/235、m6-universe:654的同process handle/HasExited/ExitCode、stdout/stderr分檔與實際child UTF8/65001；不以native pipe EOF當退出，不改run-all/threshold，不新framework。fullDriver2/3仍固定，第三非預期工具FAIL即停。
- 修後full/CI尚未啟動或通過，T2尚未closed、T3未啟。f3 source/各原件及原report4740SHA已另存pre-full-fix2 archive，避免後續append報告使舊SHA無副本。
- 2026-11-14 fix2 production候選 `f3a79ec182653492fcbe5a41313cbf1e41c2a4e8`（3檔），原席fixBASE5e2b2c6。五類focused 13/6/52/13/41共125、Windows M7 23JUnit/4GT、11SelfTest按預期完成；controller `t2-fix2-focused-controller-readback.json`核實際XML/hash/九raw/平台與pin。report SHA `4740E1DDE9CC4538AAFC69463E02775D0DBC693079C49AB6DCBACE675DCB637F`。
- I06兩案真RED均為預期REJECTED卻COMMITTED；原64slot與36+4060=4096coverage在未checked移除後分別超額。修正僅將candidate/current/flushed/pending按SID去重實體lease聯集交既有allocator，原每snapshot occupies/slot/bounds不改。飽和時同SID同租約的put確走requireRoom且成功（非no-op）、未確認新增拒絕且current/flushed/disk保持，先checked釋放後同新增成功。
- 直接owner mutation退回candidate-only，兩個具名上限案恰2FAIL；controller `t2-fix2-mutation-controller-readback.json`核實際XML、保存before/mutant bytes與SHA、原bytes還原且現行owner=before。N2尚待原唯一席，不能先記I06已清；N0=5/N1=1歷史不改，fullDriver2/3與舊site1保留，修後full/CI仍未啟。
- 原唯一席N1已終局：C0/I1/M0，N0=5保留；I01～I05與M01均ADDRESSED，新增T2-I06。fix1把共同room改逐對檢查後漏既有64 slot／4096 chunk coverage合計守門：一列舊租約remove未checked，仍在flushed占用，但新SID可拿不相交第65格。這是本輪資料層退化，不是超M6工具要求。新ID與原五項分列，原fixed32/48及五項修正證據不洗。
- fix2由原作者依同SDD/task-2-fix2-brief.md核正式RED→最小共同聯集計數→GREEN/mutation/focused，仍保I02每snapshot原occupies/碰撞且同租約跨snapshot不重複算；沿既有allocator，不造新權威/公式。原席N2 C/I0後才full/CI；N0=5→N1=1已下降，N2未評，止損表完整保留。啟動fix2時的production基準是9d1、reviewBASE5e2b2c6，未啟修後full或CI。
- fix1 production候選 `9d1e6bab4840ca658191a6c005fbd172d7bcef06`，14檔，原席fixBASE71c47ba、實作起點a34d4c0；作者report SHA `7308DA43449B063EF0BB23EEA685F7C2DA453788FEA11B738224E04AF3AA5F83`。六類focused 11/6/52/13/41/26共149、Windows M7 21JUnit/4GT、11個自測按預期完成；controller `t2-fix1-focused-controller-readback.json`核實際XML/hash/九raw及pin9d1/平台，M7零skip。
- fix1 I01～I05及reposition六個直接owner mutation，controller `t2-fix1-mutations-controller-readback.json`核各實體XML/hash/目標assert、保存before/mutant bytes、還原SHA，六個owner現行bytes也等於變異前bytes。I01與reposition均為正式caller目標assert，非cleanup timeout；I03/I04原history RED具名2FAIL、原件SHA `E009BEA0…`。原首I01仍保正式remap漏撤reserve導致權威不符＋fixture最後僅cleanup逾時兩層因果，不冒充目標RED、不回填新marker。
- mutation site多行CRLF/LF不匹配在source/runtime前拒絕的原事件1保留；單行唯一命中修正後新nonce才KILLED，不重設fullDriver2/3。controller讀fix1 proof曾誤用初版JSON欄位xml產生KeyError，是已保留的只讀操作事件；改按實際xmlSha/mutated/hits核原件，未重跑mutation、不改source、不當遊戲或fullDriver失敗。
- 現行順序由root調整：先同席N1增量審查，C/I0才啟修後完整full，然後同SHA全部required CI；未減最終門檻，不在未清source中間pin先耗full。N0=5歷史保留，N1未評；五項「作者已修」尚不是獨立採用。M01診斷修改一併待原席核。T2仍OPEN、T3未啟，未push/CI。
- controller `/root/m7_t2_onward`；BASE `6032027`，續行狀態 docs `670c782`；fresh implementer `t2_implementer`（gpt-6-astra/high/fork none）。source 初候選 `8c6e542`、兩個未知版本負例修正 `363d4df`，首次未知寫入占用修正後最終 production code pin `81bd13e7ccebe167fcb36341c1416384e305ebec`；均未 push。T1 不重做、B activation guard 仍封至 T7。
- 新 `JournalSession`／`HomeSessionRecord`／`HomeJournalCodec`／`HomeJournalBudget`／`HomeReceiptStore` 與共用 journal owner 完成候選。current/flushed/quarantine/pending 由同一資料源管理；pending exact-attempt 未確認時保守占用，向後提供 typed `pendingSessions()`。T3 busy、T4 root fence/CLOSE event owner、T5 startup space 留各自 task，不因本候選宣稱 M7 完成。
- 正式 schema6 writer 三案行為 RED、歷史 API 缺失 compile RED 分列；I2 可編譯正式 owner 特定行為 RED 真 3 案恰1FAIL，SHA `2629BA16…`。I2 同32租約 current50,397→COLLAPSING＋root latch50,445，保留48；64租約亦48，1租約2,010、8租約162。另第一次 HOME atomic move 後 readback未知的占用缺口真 RED1/8→GREEN8/8，未確認時不借用玩家／Chamber／slot，修後 pin81。
- 9個直接 production owner mutation：I2、raw重鍵、tracker、未選remap、CLOSE、舊append、receipt覆寫、空journal降版、pending占用，均由具名正式測試 KILLED，保存 before/mutant/還原bytes與SHA。原8項已controller逐XML/hash及保存source bytes核，pending第9原件見 report。變異時間點與其後改碼分列，不把早期測試冒充最終候選。
- 最終 pin81 focused 原根 `t2-final-focused-75621f8aa7784c70a159fa3b93309e03`：五類JUnit 8/4/52/13/41，共118案，XML failure/error/skip全0；Windows M7 16JUnit＋3GT，11個 readonly SelfTest正/負例全按預期，兩JAR隔離。controller `t2-final-focused-controller-readback.json` 已核五XML/hash、M7 pin/platform/九份raw hash與實際16/3零nonPass；原件仍在SDD/run，不以build唯一副本採計。
- 必要routine擴檔：兩M7 runner的T1硬編suite改接新集合，保T1既有4JUnit；SlotAllocator既有restore驗證最小共用、SessionRecoveryManager僅typed convergence／全列readback；舊兩類「6=未知」負例依正式升6改未知7，保-1/0、重鍵與5→4 readback全部斷言，直接舊row decoder(6)仍拒。初次 schema5 52案1FAIL保原根與XML SHA `FA41BD70…`，不改判。這些同task同席review，不增加工具標準。
- 真舊reader採 `2255027` SessionJournalStore 原blob `ac764b16b1093399607182e6e6fb57a16b421784`，controller核saved source exact；隔離classes CodeSource，實際 `read` 以具體「journal版本缺失或未知」IOException拒固定schema6（bytes SHA `B22FFD16…`），不是classloading/反射錯誤。最後wrapper將CP950 log當UTF8顯示致outer1，原FAIL保留；只讀正確解碼取證，不重跑Java。`T2_OLD_READER_CONSOLE_ENCODING`連續1；controller另一次UTF8 source誤用預設CP950的只讀操作事件獨立保留，不加/抵/重置原工具計數。
- 既有 W10 producer 未重建／重套。初核02、候選前03均只讀 PASS（固定tag/head/blob/patch/runner/diff exact、detached唯一dirty、祖先非reparse、32鎖可獨占、runtimeOwners0、65001）；正式full前再核。自寫只讀helper首次DirectoryInfo provider屬性誤判FAIL保原件，修正後PASS；不併舊Native/Evidence。staged whitespace檢查抓真格式問題是正常品質RED，修後過關，不是工具故障。
- 初次長SDD EvidenceRoot full在既有CI cwd preflight native267而outer1；成功fixture258字元、失敗265且存在，長路徑列推論，原根與FAIL保留。第二次改回既有短預設 `.superpowers/verification`（不改harness/acceptance/source），HEAD `71c47bad2dbe5e47f7ffb43d72b679fd85a0ea4b`／source81，新root `run-all-611806be445a42148e0a012c293d6bb7`。
- 第二次內層自然 `END ALL exit=0`、summary PASS SHA `83A01553383CD957FA9A342514AA316221F24B021FF5A999BD4333BDFA377702`：JUnit1151（failure/error0，2個既有NonWindows專用案在Windows條件skip；M7仍零skip）、default166/normal13/legacy1/M560/M635、M7 16JUnit/3GT、main-only、M3、recovery、M5 69phase（31個預定halt，不能稱全正常停服）、M6 9鏈27phase、CI規則按各自scope完成。
- 但外wrapper PID2320未自然返回，無run-all子程序僅conhost，exit.txt缺失；不能冒稱outer0。確認同一process handle／start／command+唯一root、無world owner、94把本樹鎖可獨占後，僅受控停止本次wrapper，實際exit -1，自然outer為UNKNOWN；Gradle/Java未動。`outer-before-stop.json`／`outer-stop.json`及外log SHA `E53BF9EF…`保留。停止前可選open-log hash遇file in use一案保留，未當停止必要條件或補假退出碼。controller `t2-initial-full-controller-readback.json`如實記innerPASS／outerUNKNOWN，不採為完整full門檻已過。
- 唯一獨立原席 `task-2-review.md` 首審規格❌／品質Needs fixes，C0/I5/M1、**N0=5**。T2-I01純容量拒絕導致舊candidate/remap取消團隊；I02同SID current遮蔽未確認flushed資源；I03 schema6舊列漏同SID歷史；I04 receipt漏完整wrapper DataVersion exact；I05永久SEALED/root latch不可達組合仍被strict/峰值接受。全部屬binding正式資料/現有caller正確性，不列超M6工具殘留。M01診斷schema名稱可順手最小修，非阻塞。
- 舊full消費source期間全程未改HEAD/source；作者只在SDD準備fix1。controller postcheck07核95鎖（含producer）獨占、runtimeOwners0、固定pins/hash exact，已到安全邊界。整改1由原作者依 `task-2-fix1-brief.md`／`task-2-fix1-design.md` 核具體反例後修正；I02每row沿原 `sameAuthority`／`occupies`，不改已Returned舊M5規則。I01允必要舊caller最小接線；純容量retry不得吞一般IO/型別/深度/重鍵故障。修後需新pin RED/GREEN/mutation/focused/full（含真自然外部0）及同席N1、同SHA全CI。當前N1/N2尚待，N0=5保留，連續兩整改輪未下降就停。
- root固定同一實際full driver計數：長CWD267與第二次outer未返回各保原因/原判，但合計已2/3；不能因換簽章或wrapper名稱重置，修後同driver第三次非預期工具失敗立即停。可選hash／人工source解碼錯另列操作事件，不冒稱遊戲失敗。fix1前已保存原frozen diff與設計原bytes/hash於SDD archive。
- T2 OPEN，未push／CI、未啟T3，沒有人工/main/tag/PR/LICENSE/退役。初候選內層PASS與原fixed32反證修復不抵I5，也不抵整改後完整門檻。

## 使用者async裁示：只補I11後原席清零、push同SHA CI（2026-11-13）

來源：本Codex對話使用者對root具體async問句的明確回覆，非Claude稿或技能。題目原文：

「本機定向已 PASS，但原 reviewer 仍留一項 M6 標準內的 I11：live 終局重讀 HEAD，並核對／使用原件重算的摘要。你限定的一輪整改與一次定向已完成；是否允許最小補齊這項後，原席確認 C／I 清零，再正常 push 看同 SHA CI？」

使用者選：「允許只補 I11、原席確認後 push CI；不再本機重跑（建議）」。

- 同SHA CI `43418385083` 已啟動，實際5jobs；Windows/Ubuntu兩個m7-validation已success，downloaded原件各4JUnit/3GT零fail/skip、7raw hashes吻合，11個SelfTest（正1PASS＋負10KILLED）含I11新五控制真完成。原件索引`ci-43418385083/m7-artifacts-readback.json`／`m7-original-pointers.json`，只是讀JSON/XML/hash，沒有本機重跑。原build/windows-native/m6-native仍執行，整run與T1尚未PASS/closed；不為記錄子gate而commit/push新CI。
- 已依授權正常push：I11 source67bf1a0之後必要狀態docs `57ca71ae35173e784a9f53013b2f5dfeba5548ba` 已push feature，`ls-remote`讀回同SHA；沒有force/main/tag。新source/pushed SHA本機0重跑，舊local PASS仍04d。當次CI初始事件待發現，不重送push；T1仍OPEN、真五job終局待驗。此狀態先留工作區/ledger，不為追寫CI再push。
- **T1已按新M6同等範圍closed，本輪停在T2前。** CI`43418385083` attempt1，head `57ca71ae35173e784a9f53013b2f5dfeba5548ba`，實際5required jobs全部completed/success。primary terminal JSON SHA `12FC3BD15165BAFBE783A701A8ECF34E0AB4597B9EB15515D6CC1766C3699C96`，root另親核`root-terminal-run.json` SHA `A3749D0B8104D58FCE12831BCC2A1D69FD75EC5C08828029823C01A56489A160`。原席source67 C0/I0/M0、舊local04d七步與新CI57分開；沒有新本機重跑、CI重跑或提前宣稱。
- 真CI新M7兩OS各4JUnit/3GT/11SelfTest及7raw hash、兩JAR隔離已原件讀核；舊兩OSdefault166/M560/M635/legacy1、Windows normal13及M6 main-only三層、M6 native9鏈27phase/479反例皆有terminal log。六artifact metadata/digest/完整log與兩OS raw定位都在`ci-43418385083/`。
- 實測run41分02秒；job M7 Windows3分33秒/Ubuntu3分14秒、build12分36秒、windows-native40分59秒、m6-native24分08秒。舊31.5分只歷史；M7後續正式39/full/production focused/mutation/人工未測，總額UNKNOWN。PoC與退休額外CI=0，不為估時加跑。
- 全部新授權完成後凍結`T1-CLOSEOUT.md` SHA `1474365A5F5F9B1248EF193E87F298CE9B680E501E8D1240F250EBAEBC44597A`（JSON `ADBD2F50482DFE84093EBEA3B7FED3BAF61F97FB8AEA720598683F16EC2F5A7D`），包含I11閉合、原席/基線/CI/逐項分類/殘留不擋/實測/未驗；I2移T2仍未解、I7超M6殘留不自動T4、舊FAIL/UNKNOWN/計數都留。此最後狀態/plan勾選只本機記錄，不再push造第二CI；原作者/controller idle，不T2/main/tag/人工/PR/LICENSE/退役。
- I11原同席靜態審查已採用C0/I0/M0，`task-1-i11-review.md` SHA `644A3F418D332832B8DCD0AC0C35A42BDA53F0AF4F1B22ABD60E3EFC7356AED6`，新source `67bf1a08f1cd85044bf1eba37aaa0322295ea67b` 已達普通push門檻。這是純靜態，不是新SHA本機PASS；既有本機七步仍04d42f43。依async授權將必要待CI狀態docs一併正常push，實際遠端SHA/同SHA CI尚待後續讀回；5required job與兩OS新11controls均待真跑，不能先closed。
- 原作者最小靜態候選已本機commit `67bf1a08f1cd85044bf1eba37aaa0322295ea67b`（BASEa6df06e，只有m7-ci-gametest/run-all，39插10刪），已交原唯一reviewer `review-a6df06e..67bf1a0.diff` 核I11與新breakage。新增五個同scope CI-only負例及expected列`t1-i11-static-ci-controls.json`；本機任何compile/test/parser/SelfTest皆未跑，新code只能由原席靜態＋將來CI驗，舊local PASS仍04d42f43。
- 這次只取代I11等待hold：原作者最小修live發布／終局重新讀實際HEAD，以及saved summary案例/pace/artifact對raw重算proof核對或讓run-all直接消費validated fresh proof。歷史VerifyOnly仍可明示ExpectedHead讀舊SHA，不強要求目前checkout同SHA。不改JUnit/GT/production/PoC/T2/其他殘留，不造新框架。
- 可寫必要同scope CI SelfTest負例（如saved案例變空），但只將來CI跑；現在絕不本機compile/JUnit/GT/SelfTest/ParseExact/其他定向重跑。只讀source/git diff/check/hash、必要本機candidate/docs commit允許。
- 既有本機七步PASS仍是`04d42f43b693ba012f1f00409c541fa290980511`與63.6456469秒，不冒稱新補SHA有本機測試。新補候選交原同席只讀C/I0後，正常push（已授不再問）/ls-remote核讀，追同SHA實際5個required jobs與新雙平台JUnit4/GT3及舊全部M6門檻。
- CI FAIL保log/artifact metadata/原根，不自動rerun、不改碼重送，M6同等不沿用舊偶發重跑額度。CI真全綠且新T1 scoped C/I0才可關T1；即使通過也本輪不T2/main/tag/人工/PR/LICENSE/新worktree/退役/Native39/full/W10。舊FAIL/根/計數全保。
- CI終局先在ledger/工作區記真狀態，最後必要狀態docs可只本機提交，不為追寫本次CI再push造成無限CI。本輪controller仍是note/AGENTS/plan/ledger唯一writer。
## 使用者新裁示：T1收斂、驗證標準改為M6同等（2026-11-13）

來源：2026-11-13使用者在本Codex對話回覆T1 fix1結果，由root照錄於SDD `user-t1-m6-standard-ruling-20261113.md`；不是Claude派工稿／技能。以下為題目、所選選項及說明原文：

- 題目：「T1 接下來怎麼走？」
- 選：「收斂 T1，驗證標準定為「M6 同等」（建議）」
- 說明：「T1 只留 JUnit（大小上限、硬連結發佈）＋GameTest（入口守門）＋M7 的 CI 接線；取消 T1 的 Windows 原生 PoC，當機重開證明交給 T4 正式矩陣。驗證工具以 M6 已在 CI 驗過的 probe 做法為標準，審查意見超出 M6 同等標準的列為殘留、不擋關；「保留金算錯」這類屬於正式設計的問題移到 T2 修。代價：改掉你一小時前「T2～T3 保留 PoC」的裁示。」

### 新範圍與本輪程序
- **本輪終局：本機窄batch七步PASS，原同席C0/I1/M0，I11仍阻T1，所以未push、無新CI。** 報告`task-1-m6-convergence-review.md` SHA `2605C4C173EE02EEA68D4592165149634208A45D666BB7037992AAD225157905`：live終局未重讀真Git HEAD，及最終full嵌未與raw重算結果比對的saved案例/pace/artifact；依M6 GT124/142/native720與精確集合，屬M6同等內，不降殘留。歷史VerifyOnly允許明示ExpectedHead，不要求舊收據等於目前checkout；本批無實際漂移或原件損壞，不改PASS。
- 完整凍結`t1-m6-convergence-final.md` SHA `C97911A3BAC4EAE66769A8037EB7A55039D93F75A62C6309F8CBA49056BBA1B3`（同名JSON `F4F9E8018704FE8E182A1F48ADB018D07DB8DE5ABE01A5E12CAF717DFF242D60`），含原席12歸屬、新scope／18檔、4JUnit3GT、一次7步／raw hash／root／codeSHA04d42f43／63.6456469秒、未pushCI、殘留依據與新成本模型。PoC/退休額外CI皆0；新CI沒跑，舊31.5分只參考，總時長UNKNOWN，不為估時push。
- 這次一輪／一次已用完。root正向使用者等一個僅I11最小補正的async裁示，答案前不動source／測試／push；目前沒有該授權。不自動補兩行或重跑。原作者/controller idle，T1 OPEN、不T2，raw與舊FAIL/計數全保留。結果只作必要本機docs記錄，並未發布。
- **新M6同等一次窄batch已本機PASS/exit0，仍待原席實作C/I0及真CI。** code pin `04d42f43b693ba012f1f00409c541fa290980511`，UTC `10:35:27.1370031`→`10:36:30.7826500`，63.6456469秒。JUnit4/WindowsGT3、真relative root/readonly、真正workflow正0/PASS與三變造各1/FAIL逐例核、最後原件hash不變，共7步全PASS；正例step的6個M6等價readonly controls完成，無native/main-only/class快照/clean/full。
- 原根`t1-m6-convergence-run-82ca010a6cfd4f45809e0e0f14629645`、raw XML/log/JAR/inputs/summary與workflow各result保留；postcheck701檔hash、Java owner0與GT鎖獨占。原同席只讀核`review-0b9bbb7..04d42f4.diff`及新scope，未再測；只有新範圍C/I0才正常push，同SHA所有required CI全綠才T1 closed。此刻未push／Ubuntu未真驗，不把本機PASS抵CI或舊FAIL改判。
- 新收斂候選`04d42f43b693ba012f1f00409c541fa290980511`已本機commit（18檔、取消六框架檔與JavaPoC、inventory/default/CI native引用移除），未compile/test/push；M6基準與production不改。窄batch清單`t1-m6-convergence-batch-plan.json` SHA `6A665538113C6332B8AE4ADC74F8E0C33253CEE6E002E03EFC3FB4EC3B656050`已由controller核codeSHA/driver/fresh根/expected；只一次JUnit4+WindowsGT3+readonly/真正workflow正負/SelfTest，第一呼叫relative root驗F1，不新consumer框架。原根`t1-m6-convergence-run-82ca010a6cfd4f45809e0e0f14629645`；首非預期FAIL就停不修/重試/不push，PASS才同席核新scope後按授權push追CI。
- 文件/分類已由原席採用：初審f22db82 C0/I1/M1，僅T4固定十三cut來源缺失；docs-only `12fcdca98252d4b4f36c41630dc6f2f610cfe1df`補回原固定矩陣，補正採用C0/I0/M1，報告`task-1-m6-standard-plan-review-addendum.md` SHA `655E1F14DFBE6103EF6B8C386FB8B20376C7B3A87DDC2577FBF154768D555AB5`。逐12項原席表`task-1-m6-standard-disposition.md` SHA `ABBED551AD24FE1599605617D41CA6499E694F0CB9DF9CE4694204C28C7E4A45`：I2移T2，I7超M6不擋，I4/I11/F1/F2保留、native部分移除但安全/FAIL原件/JAR/exact仍保。不是舊code已修；現在才允許原作者一次窄scope收斂，先不試跑，交候選/一次batch核。

- 本次取代舊Windows PoC18、T2/T3 PoC守門與T4退休接線流程：T1原生PoC取消，T2/T3也不跑；T4 Windows正式39仍保留。Ubuntu仍只有JUnit＋GameTest，沒有Linux owner/native/main-only。不是skip／空矩陣／假PASS；exact清單、default full、CI、oracle同步新scope，Git歷史與全原件保留，既有M6 native/main-only守門和斷言／門檻不改。
- 標準具體比對已在CI通過的當前 `m6-universe-probe.ps1`、`probe-common.ps1` 及M6 JUnit/GT/CI入口。分類須固定file/blob/SHA及b26b90c／CI43373504422等已PASS來源，原同席逐項核現OPEN的I2、I4–I12及F1/F2：保留M6內／超M6殘留不擋／移T2正式設計／nativePoC範圍移除。I1/I3已處理歷史也保留，不整批宣稱全關。
- 先本note原文→docs-only plan／必要spec邊界勘誤＋原同席分類採用C/I0→原作者一次scope convergence整改＋事前凍結的一次窄T1定向（JUnit/GT/CI正負）。不原生單pair/18/39、新全class/argfile框架、額外main-only、clean/W10/full。任何非預期工具FAIL立即停，不修後再跑／不push；舊FAIL/根/計數不重置。
- 若本次定向PASS且新scope沒有C/I阻擋，已授權正常push、追同SHA新T1雙平台JUnit/GT及既有全部required M6 jobs；CI真終局全綠才可關T1。CI FAIL原判保留，不自動rerun；「M6同等」不是沿用M6舊重跑waiver。無論T1是否過關，本輪不開T2，回root。
- 超M6意見標殘留並給具體不擋依據，不默認塞回T4必做；若要提高標準另問。使用者的原FAIL/root/XML/log保全規則仍在，clean證據遺失不能豁免。producer保留、不新建／退役worktree，production不改。

### M7_STARTUP_TIME根因的新外部證據

- 使用者提供Claude巡檢：pwsh7.7.0已確定重現，原`ParseExact($s,@(3格式),...)`將`object[]`綁成單format字串；明確`[string[]]@(...)`或單format可行，例值`2026-11-13T09:26:48.506Z`。來源是使用者提供的Claude實測，**不是本controller或root本輪復現**。根因據此補記已知；舊batch FAIL/當時未保底層exception、原UNKNOWN診斷侷限不改寫。
- 此native工具不再是T1入口；T4 plan記明確string[]修法及合法3/6/9位秒接受／錯格式拒絕TODO。不能把cast改碼當作3/6/9已驗。現在只靜態列其他M7同類array-overload，不新增ParseExact重演或日期框架。
## 使用者回覆 T1 checkpoint：驗證縮範圍與一次有界恢復（2026-11-13）

來源：**2026-11-13 使用者在本 Codex 對話回覆 T1 正式全集前 checkpoint**。以下四題、所選選項及說明為使用者看過並選定的原文，由 root 轉交照錄；不是 Claude 稿，也不是技能。root 另存原文於本計畫 SDD 的 `user-t1-scope-ruling-20261113.md`。

1. 題目：「T1 的可行性實證（PoC）每個平台跑幾列？」
   - 選：「B 每門態六個代表切點（建議）」
   - 說明：「每平台 18 列，保留六個存檔階段；未抽到的組合由 T4 正式程式的 39 列全驗。Codex 也建議這個。」
2. 題目：「PoC 要保留多久？（這項影響最大）」
   - 選：「T4 正式矩陣上線後退役（建議）」
   - 說明：「T1 跑完的 PoC 證據留存；T4 的正式 39 列通過後，PoC 從完整回歸與 CI 移除，不在 T5～T8 重複跑。T2～T3 期間仍跑，當作守門。」
3. 題目：「Ubuntu 要驗到什麼程度？」
   - 選：「比照 M6：原生當機重開只在 Windows（建議）」
   - 說明：「Ubuntu 跑 JUnit（含真實磁碟的硬連結發佈與大小上限）和 GameTest（含正式拒絕契約）；不寫 Linux 程序管理、不跑 Ubuntu 原生矩陣與「只載正式模組」檢查。代價：Linux 上「當機後重開」的原生行為只靠 Windows 推論。」
4. 題目：「實作改完之後怎麼恢復執行？」
   - 選：「先改 plan，再一輪整改＋一次定向驗證後回報（建議）」
   - 說明：「依上面裁示修改 plan 並一席獨立審查；再做一輪核心整改加一次定向驗證就停下來回報，不開正式全集。」

### 本輪執行界線（controller 依上述裁示落地）
- **本輪許可已用完、完整報告凍結，現在只等使用者下一裁示。** 原同席`task-1-fix1-review.md` SHA-256 `7A355066BBC88EAC1C355137E649B6BFA10DE2C637F399B977CF3CBC3B84FF6D`：I1/I3 ADDRESSED，I2及I4–I12十項PARTIAL/OPEN，新增F1/F2兩Important，合計C0/I12 open（10原+2新）/新增M0；原核心/plan/scope判定不改。I2仍低估固定32lease保租後繼；I7實際argfile/DLI、I8seal/CI失敗原件等仍有缺口；F1為CI相對evidence跨cwd，F2為workflow正例DOM重存與raw hash契約，皆未以重跑修補。
- 完整交root檔` t1-fix1-final-report.md`（SHA-256 `1AB7931E3AE331D69F7AE3AA1455449F707E20A83B0BCD0D407C2ED17BFBF502`；同名JSON `94AF7F34A7C3F987A8F6BC02BA875047574EDC0B1466C8062F782CF0BFF031C6`），列plan/spec採用原件、12I範圍/修法/本批覆蓋/未驗、17檔/exact集合、唯一batch完整命令/原root/SHA/exit/NOT_RUN與保全。成本檔`t1-fix1-cost-model.md`只用已有44.606秒情境與Win-only/PoC退休公式；本輪native未跑、新CI未測、總時長UNKNOWN，不用失敗65秒冒成本全額。
- 只知離線正例進ParseExact catch拋M7_STARTUP_TIME，底層exception未留，根因UNKNOWN；06原log0bytes不補trace、未重演。所有新tools修正/compile/runtime/重跑/CI/push停止，T1 OPEN；原作者與controller idle，沒有fix2或下一task。runtime code pin b69397b未push；本結果只作必要本機docs記錄，不觸遠端。
- **唯一batch已終局FAIL_STOP，不再整改／續跑。** 實跑HEAD `b69397b4c02085f549f445d763787f5b8e8408b9`，outer1，`2026-11-13T09:03:57.6315490Z`→`09:05:02.8290377Z`（65.1974887秒）。1–5步source／一次compile／compiled-freeze／JUnit6案0錯0略過／WindowsGT3案0錯0略過自然PASS；第6步offline-once出非預期 `M7_STARTUP_TIME`（`m7-contract.ps1:13`）立即停，不是事前negative。7–10 native單pair／artifact／workflow／end-freeze全NOT_RUN，native根未建立，沒有新pair測值。
- 原根 `.superpowers/sdd/2026-11-13-m7-return-home/t1-fix1-run-00b3fe17d408424e9eabd07914b365a0` 的result/partial/logs、raw argv/DLI/stdout/stderr、source/compiled manifest/copies、JUnit/GT XML保留。`t1-fix1-outcome.json`記完整命令清單引用/exit/未跑；controller只讀保全2995檔hash與GT原world全檔、Java owner0、session.lock獨占，沒有重現或清理。
- 新增離線startup驗證工具FAIL保守續原 `T1-NATIVE-HARNESS` 原2→3，非宣稱與舊失敗同code根因；原EVIDENCE-INTEGRITY3/main-only2及全部FAIL/UNKNOWN不清零。不論歸屬本批已停，不能分類後再跑。原作者idle、原唯一reviewer只讀核diff及既有原件；I7 worker argfile／I8 seal兄弟負例缺口仍open，未執行能力不能假關。
- fix1原作者候選已本機commit `b69397b4c02085f549f445d763787f5b8e8408b9`，17檔、src/main無diff，未compile/test/JVM/push；controller核`t1-fix1-batch-plan.json`（SHA `076E85F8F70E8D28767DDBCAABFDEE549D207C47069848C9F299AAAD2A8B6BB3`）/driver/513source副本hash與三fresh根，核定只執行這一次10步定向。batchroot `t1-fix1-run-00b3fe17d408424e9eabd07914b365a0`，不開全集。I7 JUnit worker內部argfile、I8兄弟反例未入seal等已列open，沒有要求再補功能；任何非預期工具FAIL立即停、不修/重試/續跑，PASS也停。
- 採用狀態文件已本機commit `5785f684e9dc44e60b7ccc0bf79b3541fc8189c4`、未push；spec v1.1 SHA-256 `0A93B044009ADCD8096D00C74F99EA596F86A76DA181CA1BCC8BB9E2F12EC4E7`。已回原implementer fix1，只一輪code整改；先不compile/測試，待其本機候選與事前batch清單交controller核，再執行這唯一一次。本輪不啟獨立main-only/Ubuntu/正式矩陣/full/CI。
- 原唯一reviewer已採用文件候選`4132fe5`：plan/spec C0/I0/M1，report `task-1-plan-amendment-review.md` SHA-256 `C457062A04AD863F468CDCC05B09CB533A4566D77A98B824488AC9133FD7A584`。逐I歸屬`task-1-scope-disposition.md` SHA-256 `A29404AA66A460A683792AF9B389946587B124D1FEEF99A014A57E4FBFA7AA0E`：9項全保留；I6/I8/I10僅Linux/Ubuntu/雙OS26shard部分標範圍移除，12項皆仍有待修/待驗義務。原core C0/I12/M2及8D3C報告不改。
- 採用後只更新plan/spec標頭及引用SHA，不改已審契約；AGENTS將本輪現況與舊快照分隔以處理M1。現在可回原作者fix1，但先不compile/測試，先交程式候選及事前batch清單，controller核後才一次定向；未開正式矩陣、CI或push。

- docs-only amendment當前HEAD `4132fe53b92078faf7c16f0bd2460511baa767a1`（`d6caf20`加單批build/快照取得順序自審補正），未push；已交原唯一reviewer靜態審plan/spec及12I scope disposition，未動工具/清單/CI/source，沒有新增runtime。
- docs-only amendment 候選 d6caf208116120b9862b0651f91f018017763f68 已在本機提交、未push；plan/spec驗證勘誤待原唯一reviewer，一切工具/清單/CI/source尚未整改，沒有新增runtime。

- 先僅修改 plan／必要 spec 驗證平台勘誤，不改玩法；由原唯一 reviewer 獨立審查 plan amendment 及 I1–I12 歸屬，C/I 清零後才可動 exact 清單／CI／oracle／工具。原 review 的 C0/I12/M2 與 finding 不覆寫；「範圍移除」須原 reviewer 確認，Windows 與其他真正缺陷不能整項豁免。
- T1 為 **Windows 18 列**、六 cut × 三門態；T4 正式 **Windows 39 列**通過後，PoC 從 default full／CI 退役，歷史原件保留；T2–T3 保留 PoC 守門。Ubuntu 僅 JUnit＋GameTest，沒有 native／main-only／Linux owner 程式。exact inventories 與 CI／oracle 同步新集合，禁止 skip 冒充 PASS。
- 本次只授權原 implementer **一輪核心整改＋一個事前凍結的定向 batch 執行一次**。不開正式18／39、T2、完整回歸、新 CI 或 push；可必要本機未完成候選／docs commit。production 不改。batch 不邊跑邊改；任何非預期工具失敗立即停止，不重試／補修／續跑，安全收回已在途 owned JVM 並留原根。expected negatives 必事前具名。
- 原 `T1-EVIDENCE-INTEGRITY` 保守第三事件、Native2／main-only2 等歷史計數、舊 FAIL／UNKNOWN／clean 事故與來源漂移診斷全部保留，不因本次有界授權重置。batch 終局後不再 fix／compile／runtime／CI／push；原 reviewer 只讀核差異與原件，新增缺口列 open 待下次裁示。
- W10 producer 已存在，這輪不重建、不移除、不跑 full；不 clean 刪證據。Ubuntu 若本機不可真跑且需 push 才有 CI，就如實未驗，不模擬 Linux 或擅 push。現在先做文件修訂與一席 review，工具仍未開始整改。
## 使用者新增正式批前巡檢裁示（2026-11-13，立即生效）

來源：使用者在本 Codex 對話、T1 執行期間貼入的新增訊息，root 照錄於 `.superpowers/sdd/2026-11-13-m7-return-home/user-checkpoint-instruction-20261113.md`；controller 已讀原文。這不是 Claude 派工稿，也不是技能要求；本次不改玩法範圍。

- 使用者原文：「請在 T1 提前核心審查完成後、正式 39 列雙平台批次開跑前，先回報下列四項再繼續」。因此設 **formal-full hold**：先完成本 task 唯一核心審查，再由 root 向使用者回報 checkpoint；在此前不開正式 39 列雙 OS，也不 push 會自動觸發該全集的 workflow。核心編碼、定向驗證與唯一 reviewer 可續，T1 OPEN。
- 四項為：本機／CI／shard 與 T1–T8 時程（實測、假設、UNKNOWN 分開）；T1 非預期失敗（驗證工具／遊戲功能）與未實跑新能力；T1 PoC 與 T4 正式 owner 的重複／不同風險及縮樣選項；穩定 problem ID／第三次止損。controller 整理 `t1-pre-full-checkpoint.md/json`，核心 review 後補凍結版。
- 關於縮減，使用者原文：「這是 plan 變更，只提出選項與影響，不要自行修改，由使用者裁示。」所以沒有自行改 plan、清單或門檻。
- 第 4 項原文：「同一個驗證工具問題連續第三次失敗時，先停下回報，不要繼續疊修。」立即適用；跨 nonce／commit／文件／改名不重置，同問題不拆簽章；expected RED、mutation／negative 被正確拒絕不算非預期工具失敗。有效 GREEN 需直接原件支持。
- 作者初盤目前無已達第三次者；其中 main-only 的 executable=null 與 oracle 誤判合併為同工具問題兩次，再以新 root 真 JVM／三負例直接 GREEN。其他問題與保全事故將逐項列原 root、原判及狀態；controller 尚待完整索引核對，不以作者自報當最終清零。
### root 執行裁定：證據完整性工具第三事件止損（2026-11-13）

依上方使用者第4項原文，root 已向使用者回報，保守把 clean 刪證據、active log hash=null、來源指紋不一致合併為穩定問題 `T1-EVIDENCE-INTEGRITY` 的第三事件並止損。**這是 root 的保守計數裁定，不冒稱三者為同一 code root cause，也不偽稱使用者逐字指定這個 group。**

- 停止 T1 新增工具修正、所有新 compile／runtime／重跑／CI／push；只讀盤點、README／狀態整理、現有候選 diff 凍結與本 task 唯一核心靜態 review 可續。此項取代上方較早「核心編碼／定向可續」的執行許可，不能為了 C/I 清零突破止損；未完成能力、open Important 如實交回使用者。
- 已在途 `t1-core-directed-one-pair`（非39列）於 hold 訊息送達前啟動，後自然完成 Windows crash77／reopen0、鎖釋放；沒有廣殺或新列。完整 chain 44.405 秒、summary 44.606 秒（含匯出與取證成本），原件 summary SHA-256 `C59835C4BF3A9AAC711D96E8B3F8446BC07E1FD8BA0705B8CB3D93FECDB38418`。
- controller 只讀重讀 1660 份 source／compiled raw copy 的 bytes／hash，0 mismatch；manifest SHA-256 `60FD9137D0E6EC752440CCBFFD92D5996A95F72F42ABDCCAE53A3111A2A7854F`，兩 phase prelaunch hash 吻合、8 summary 負例 KILLED。postcheck 留 `t1-core-one-pair-controller-postcheck.json`。這只證單列，不抵正式39列、Ubuntu或真 project clean 存續，也不自行解除任何止損／formal-full／push hold。
- 當前 M7 Java0。單列終局後才允許本機明標未完成候選 commit 或 immutable diff，維持 HEAD／消費檔不在 runtime 中途改動；候選供唯一 reviewer 靜態＋既有證據審查。收到審查即把 `t1-pre-full-checkpoint.md/json` 完整交 root，待使用者選下一步。T1 OPEN，M7 未取消，main／tag／人工皆未授權。
### T1 核心候選凍結與唯一靜態審查（未完成、未 push）

- 本機候選 commit `55c48682279392e23805e08b93c00deb6637db88`（BASE `b26b90c`），訊息明標未完成／hold，20 檔清單見 `task-1-core-commit-files.txt`。controller 已核 `src/main` 無 diff、HEAD／status 符合；只有本 note／AGENTS controller 狀態 dirty。未 push、未有新 CI。
- 原 runtime 在 BASE＋dirty 候選執行，不能稱這筆 commit 同 SHA gate。已知靜態 diff-check 有 `m7-ci-gametest.ps1` EOF 多空行，止損下留原件，不為修小項再改工具。
- `review-b26b90c..55c4868.diff` 為技能產生的完整 immutable range，1 commit／232733 bytes；已派本 task 唯一獨立 reviewer `/root/m7_sdd_execution/t1_core_reviewer`（`gpt-6-astra`／high，fresh context）靜態＋既有證據審查。禁止 runtime／compile／修碼／CI，不追加 reviewer；可有 open Important 如實交使用者，不突破第三事件止損求清零。
- 報告預定 `task-1-core-review.md`；收到後補凍結 `t1-pre-full-checkpoint.md/json` 交 root 回報四項並等使用者下一步。T1 OPEN，正式39／Ubuntu／完整 bundle／project clean 存續／同 SHA CI 仍未完成，所有 hold 維持。
### T1 唯一核心審查終局與 checkpoint 交回（OPEN、止損維持）

- 唯一獨立審查 `task-1-core-review.md` 已完成並由 controller 完整讀取：**規格❌、品質 Needs fixes，C0／I12／M2，全部 open**。原報告 SHA-256 `8D3C8F3A074B04FF9C49CC30AEE4EF7FD2A2B6A545BEC4C85749449ACBCECC27`。這是現有候選 `b26b90c..55c4868` 的靜態＋既有原件審查，沒有新增 runtime／compile／修碼。
- 12 個 Important：I1–I3 的合法 schema6 舊列、CLOSE／後繼最壞預算與 bounded raw/depth；I4–I5 的真正 guard／平台入口及同一 PoC 交易內故障／防夾；I6–I9 的 exact owner、完整消費集合／mutable DLI、空 disk／未綁 startup/owner自測/digest、startup strict；I10–I12 的 main-only classload/JAR角色/CI原件、gates/run-all保全與最終重驗、manifest結構化精確性。精確 file:line 與證據見原報告，不以既有測試 GREEN 當作這些缺口已解。
- Windows 真單列 77／0、兩 receipt hash、1660 raw copy hash 確實存在；但單列不能證完整快照集合／所有 JVM 消費凍結 bytes，JUnit 6/6 的數字也不能證不合法 fixture 的 tracker 最壞上界。正式39列／Ubuntu／完整 artifact bundle／真正 project clean 存續／同 SHA CI 仍缺。T1 三項先導尚未全可採，T2 不放行。
- 四項 checkpoint 凍結交回版1：`t1-pre-full-checkpoint.md` SHA-256 `E70C0F84F139E84A3864C8D3F2ED73343603B4025A3B64D49E5F399415876EFA`；JSON `B465285EE904ECD10C8D690D2F3D9AE1706F6ACF26DEDDF92BB8F47EE64F1E12`。列真單列44.606秒、39列約29.0分本機native條件情境；Ubuntu／新CI UNKNOWN；至少7 local full＋8 CI，舊CI約31.5分×8≈4.2小時只是同速CI情境，驗證總時長仍 UNKNOWN。
- checkpoint 列全6次非預期 runtime 工具FAIL與3事故、所有新能力未驗；A39／B18／C25只為選項，不改 plan／清單／門檻。PoC生命週期另需裁示：目前沒有T4退役PoC明文，若保留則本機至少`8n`、taskCI至少`16n`個PoC pair，正式close從T4–T8另反覆跑；不能只算T1＋T4各一次。
- 依使用者/root止損，不為 Important 清零開 fix／重跑／push。候選55c4868只在本機，遠端讀回仍b26b90c；僅本note／AGENTS dirty。root將四項與審查交使用者選下一步。T1 OPEN、T2–T8未開始，main／tag／人工／PR／LICENSE／退役未執行。
## Codex 接手（2026-11-13，規格與計畫已採用，準備 T1）

- 工作樹與分支已核對：`.worktrees/m7-return-home`／`codex/m7-return-home`，接手時 HEAD `30c0c627d7fb3f205d3a329152d62e6d04cdc773`，工作樹乾淨。
- Step0 同 SHA CI run `43359327038` 已由 `gh run view -R Ben513242/QuantumChamber` 讀回：`build`、`windows-native`、`m6-native` 全部 success。
- 目前採用 binding spec v1.0，SHA-256 `9054C4D75480D18178FD6A6420EBEF7A4ABABE4A55A95E5B4090581CC6F77932`，與清零 v0.3 契約相同、只更新採用標頭。第一個規格文件 commit `03b8c9472f7b1fa67427dbb1d7741b8f3a2bbf42` 包含 spec、本 note 與 AGENTS，已正常 push 並由 `ls-remote` 讀回相同 SHA；同 SHA CI run `43370074593` 執行中，尚無終局，不能採計 Step0 CI 代替。plan 正撰寫，尚未實作。
- binding spec v0.1 初稿 `docs/superpowers/specs/2026-11-13-m7-return-home-design.md` 共316行，SHA-256 `F1087A4A7551D5E0491BB77AF21ADC75811CCED84AA2D04C9D1FF306285ECA15`；第一輪獨立審查 Critical 1／Important 6／Minor 1，尚未採用，已交原作者修訂。plan 與 production 實作尚未開始。本輪沒有執行 Gradle、run-all 或人工驗收，也尚未重建 W10 producer。
- 短期派工與審查證據使用 `.superpowers/sdd/2026-11-13-m7-return-home/`；正式可重跑工具仍須放 tracked `scripts/verification/`，不 force-add SDD 空間。
- plan 初稿 `docs/superpowers/plans/2026-11-13-m7-return-home.md`：334行、8個 task，SHA-256 `00346816839FF702A660040E97C6756D124A0446D1C3AC16B707E13E874F9F8E`；獨立審查中，尚未採用／實作。T1 的 NBT 計量、no-clobber 發布及 B 門 checkpoint 均待實跑，不是已驗結果。
- plan 第一輪獨立審查 `plan-review-1.md`：Critical 0／Important 7／Minor 2，未採用、禁止派 T1。阻擋包含 Windows／Ubuntu 正式入場預期分流、Linux launcher／唯一切點 manifest、既有入場交易順序、正式 remap／來源 checkpoint 接線、全 BlockState JUnit、舊 M5 W4／W6 oracle 與 typed CLOSE 事件入口。交同作者窄修，未擴張 Linux 玩家 checkpoint 能力、未改 binding spec、未執行 runtime gate。
- plan 修稿339行、8 task，SHA-256 `7BE47AD2DB5AB11C034E9B7A502E622A50CE1924D4A367227C1A407632C3135A`；第二輪獨立複審 `plan-review-2.md` 為 Critical 0／Important 0／Minor 0，報告 SHA-256 `596D799B40D37AE7B04B37F1637D0B69612A6BBCBAAE7268A6150851CE8D983B`。前輪九項關閉，採用計畫，依授權直接派 SDD T1。此計畫文件 commit 尚待自身同 SHA CI；T1 的跨平台先導與任何 production 功能尚未執行。
- 計畫文件 commit `b26b90ceefd7827ea907d498a2fc096c873fdaa1` 已正常 push，`ls-remote` 讀回相同 SHA；同 SHA CI `43373504422` 執行中。規格 CI `43370074593` 最近讀回 build／m6-native success、windows-native 執行中，尚無整體通過。本輪 docs gate 只完成獨立審查與文件定向檢查，不冒充 runtime。

### SDD T1：驗證入口與三項可行性實證（2026-11-13，OPEN）

- 計畫文件 CI `43373504422` attempt 1 已終局：HEAD `b26b90ceefd7827ea907d498a2fc096c873fdaa1`，`build`／`windows-native`／`m6-native` 全 success。原件 `ci-43373504422/terminal-ci-verification.json` SHA-256 `2918741462576909603A1AB541BD61A575822A1890844BB6C7741241CACE07EF`，完整 log、run／artifact metadata 與四個 digest 同目錄。實核兩平台 default166／M5 60／M6 35／legacy1、Windows normal13、M6 native9鏈27phase／479反例；這是 plan docs 自身原 scope，不能抵 T1 新增實證／事故後重驗。上方 pending 是歷史。

- 規格文件 CI `43370074593` attempt 1 已終局：HEAD `03b8c9472f7b1fa67427dbb1d7741b8f3a2bbf42`，`build`／`windows-native`／`m6-native` 全部 success。root watch 原件 `ci-43370074593/terminal-ci-verification.json`，controller 重新 parse 核對，SHA-256 `0CB0A84B4A0B8F66841ABDB270E8A09DECA8E96A95FEECB5ECA84DD2A0B768ED`。上方 pending 為歷史；plan CI `43373504422` 與 T1 實證仍獨立，不互抵。

- controller 接手 BASE `b26b90ceefd7827ea907d498a2fc096c873fdaa1`，實查 linked worktree／分支正確；保留既有兩份 controller 文件 dirty。
- 使用 `subagent-driven-development` 與 `using-git-worktrees`，採用本計畫 workspace，`progress.md` 首行記 plan identity；前置掃描已逐 task 及共享檔案／接口成對列出 8＋28 列，沒有需要新玩法裁示的衝突。細節與 routine 技術裁示留 ledger。
- 已派 fresh implementer `/root/m7_sdd_execution/t1_implementer`（`gpt-6.1-sol`／high）；只給 T1 brief 與必要共通限制，不派平行 implementer。報告將在 `task-1-report.md`；之後由 controller 派獨立 task reviewer。
- T1 屬純測試／腳本／build／CI接線，免 production mutation 與本機 full，仍須具名 RED、真 Windows 定向／clean build／隔離、workflow fixture 正負例及同 SHA 雙平台完整 CI 實證。production 尚未修改，任何 T1 gate 尚待終局，不記 PASS；T2–T8 未開始。
- controller 已依 README 重建 fresh detached W10 producer `.worktrees/m6-w10-m5-producer`；native 工具無固定 absolute path 參數，採 README 的 `git worktree add --detach`。m5 tag object／peeled／原 runner blob、patch／runner bytes／LF diff 六項身分吻合，chcp 65001，target 與祖先非 reparse、Java owner0、fresh 無 session.lock，僅 runner 是允許 dirty。原件 `w10-rebuild-precheck.json`／`w10-rebuild-proof.json`；尚未執行 W10 或 full，不能視為 runtime 通過。producer 不退役，後續另問。T8 人工代操作開始前另問，main／tag／退役／PR／LICENSE 均未啟動。
#### T1 首輪 JUnit 事件（尚非 task 終局）

- dirty 候選 HEAD 仍 `b26b90c`；首輪 `t1-junit-first/` 的兩份 XML 實際為 Budget 3 案零失敗、Publish 2 案 1 失敗、零略過，Gradle exit 1。原件與 log 保留，SHA 見 ledger；失敗是 Windows JDK `fileKey()` 可為 null 的測試前提錯誤。
- 原作者改以保留 audit hardlink 與 `Files.isSameFile` 核實檔案身分；新 `t1-compile-2.log` 已自然 `BUILD SUCCESSFUL`，第二輪 `t1-junit-green-2/` 兩份 XML 已由 controller parse 核讀：Budget 3、Publish 2，共 5/5，failures／errors／skipped 皆為 0。log 有既有 deprecation／unchecked warnings，不稱無警告。
- NBT 初測得到合法可達 tracker 邊界兩側與預留公式，但合法後繼自審仍待；native 首組、兩平台與完整 T1 gate 未完成。上述不當作三項可行性終局，也不放行 T2。
#### T1 首組 native FAIL（原判保留）

- chain `spike-close-ordinary-after-checkpoint` 的 root `run/m7-home-0012f18edfaa4d378809a0e2f1f49dec`：crash 預定 halt 77、PID 25684、12.03 秒；reopen PID 17632／start `2026-11-13T06:03:08.579Z`，在既定 1200 ticks 觸發 `M7_CHECKPOINT_READY_TIMEOUT`，halt 78、outer exit 1。整組 FAIL，不以 crash 達切點抵通過。
- failure／startup／latest.log／jstack 原件保留，controller 已核 failure 欄位；hash、owner 與鎖檢查見 `t1-first-native-fail-postcheck.json`。作者正查 test-only fixture 在重啟後 FULL／entity readiness 的根因與 flat generator 設定 log，不改 production、不放寬 deadline，也不視為可洗的偶發重跑；修正後須新 nonce。
- T1 持續 OPEN、三項可行性未全部證明、T2 不放行。
#### T1 第二組 Windows native 局部 GREEN（未結案）

- test-only 自有 runtime ticket 與合法 flat generator-settings 修正後，新 nonce `8895aa8fba8e4552aa9a6d891183f491` 的同一 chain 已 crash exit 77／reopen exit 0；controller 核 summary 的兩個 PID／start、鎖釋放與 `Windows`／HEAD。兩 phase 實測 17.283／12.287 秒，summary 總 29.612 秒（outer 29.773 秒）。原件 `build/m7-second-measurement/summary.json`，SHA-256 `37BC4BB64A2CA3ED9339A18F2ACDBBF97F304A728FEADAD5CB88AF659520666C`。
- 這輪同時改票與 flat 設定，故 root 要求另補最小因果對照／call trace，再判 TIMEOUT 根因是否證實；`No key layers` 另記設定 RED／GREEN，不能混為因果。原 FAIL 不改判。
- 目前只證一組 Windows PoC，全部 39 列、Ubuntu、清單／CI 與隔離門檻仍待。T1 OPEN，不放行 T2。
#### T1 證據保全事故與修正要求（尚未結案）

- implementer 在搬出證據前執行 `Gradle clean`，刪除了三個 `build/m7-*` runner 輸出目錄，包括 phase 磁碟複本、launcher logs、summary。這是可避免的保全疏失；不能以重建件冒充原件。controller 已只讀核 `build/m7-second-measurement` 不存在、三個 `run/m7-home-*` root 仍在；完整具名／nonce／phase／唯一檔案清冊待 immutable `evidence-loss-*` 報告，不可知項寫 UNKNOWN。
- 上述 29.612／29.773 秒與作者後報 ticket-only 69.33 秒目前都屬歷史量測缺口，**不作 gate 或 shard 實測採計**。controller 曾核讀的 summary／8 負例亦因 runner 原件已刪，不作本 task 完成證據。原首輪 FAIL 不變，run 下的 startup／receipt／failure／world 與 SDD top logs 仍保留；現存 primary SHA 清冊為 `t1-evidence-loss-controller-inventory.json`。
- 立即停止 clean／其他覆蓋 build 的清理，先搬出所有現存 evidence，建立並驗 manifest／hash。runner 和 CI 輸出須改到 clean 不觸及的唯一 SDD／run root；hidden SDD 必核 artifact `include-hidden-files` 及 exact 收據查找。保全完成前不恢復 clean build。
- 技術修正後用新 nonce 補完整定向證據，並實跑「clean 後原 receipt／log／hash 仍可讀」正控制與反例，交獨立 reviewer 審查。T1 維持 OPEN，不洗原 FAIL、不採 M6 偶發重跑額度；production 未修改。
#### T1 事故盤點固定（未結案）

- immutable 原件：`evidence-loss-20261113-0620.json`（SHA-256 `24FB865D9FAF635352134461F7248E3480DECB0C33C14F5583B49592EA1775DD`）與同名 `.md`（`176F85E0F4BC861722B16F9D8712527835159E2E1433C63C0633F7F9C09CC6CB`）。三個確切目錄為 `build/m7-first-measurement`／`m7-second-measurement`／`m7-ticket-only-control`，對應 nonce `0012f18e…`／`8895aa8f…`／`d8198b2d…` 的 crash／reopen；刪除的唯一檔完整清單與 hash 為 UNKNOWN，不能還原冒充。
- 尚存原件 `task-1-surviving-evidence.json` SHA-256 `921F351B84C98B9EC0B8F5DC6154F9BA1A800420D3B52EF345344815728700BA`，controller 逐 17 個現存檔案驗 bytes／hash 吻合；六個原 Java owner 不活躍、三個 session.lock 可獨占。世界、runtime log 與 SDD top logs 原件仍在。
- 已啟動的 clean build 後自然 exit 0（1m56s），`t1-clean-build.log` 尚存，目前 95 個測試檔先快照到 `t1-clean-build-snapshot-current`。這不代表事故已結案或 T1 全部隔離／clean 存續 gate 通過。作者目前無 Gradle／runtime、clean 停止；後續改安全 evidence root、新 nonce 與實際存續正負控。
#### T1 完整補充保全已驗（安全修正與新 gate 執行中）

- `evidence-loss-supplement-20261113-0628-stable.json` 共 273 實檔，controller 逐檔重讀 bytes／SHA-256，0 mismatch；清冊 SHA-256 `84FFDF1C7B7AA55FE02C2251F559F234D100F726AF63FB943D39D43A42DE20A1`。涵三原 run 的完整 world／log、95 份測試快照、cleanbuild JARs、SDD RED／GREEN／javap／top 歷史 log；原 run 留安全原地，不搬不寫。
- 前一 immutable `0627` 補清冊誤納後續正在寫的 `t1-native-final.log`、該欄 hash 為 null；保留前件，stable 明列排除原因，不採該欄作事故保全證明。
- 新 nonce 完整 39 列已啟動，新 runner 原件皆在 clean 外 SDD／run；首列完成不代表全套通過。clean 存續正負控、獨立 review 與同 SHA 雙平台 CI 均待，T1 OPEN、舊量測不採 gate。
#### T1 執行中批次降為工程診斷（來源指紋缺口）

- 本批 39 列啟動後，作者曾修改 runner 磁碟文字（長駐 pwsh 已解析舊版）、`build.gradle` 的 `Pm7ExportMain` 分支與 JUnit budget source，Native Java／inventory 未改。因沒有完整一致的消費 snapshot，這批所有結果只列 `diagnostic-only`，不作 T1 正式 gate，也不把範圍縮成只看 Native Java。原件保留，另寫分類 sidecar，不改舊 metadata 冒充新 pin。
- 可先完成其餘 T1 接線與必要本機測試，正式 gate 前固定 immutable 消費清單／raw bytes、HEAD／dirty、Runner／Gradle／Java／inventory、compiled artifacts／loader argfiles 的 hash。各 phase 實際消費檔跑完逐檔重讀未變才採計；需要改碼先結束 batch、新 nonce。正式兩平台 39 列仍完整，不能以此診斷批抵用。
- 若在 phase 自然結束後可安全停止下一 dispatch，標 controller 中止診斷並保原件；無安全取消能力則不冒險殺程序。所有新結果仍留 clean 外安全 root，clean 保全與正負控要求不變。T1 OPEN。
#### T1 工程診斷批 runner FAIL 終局

- `t1-native-final.log` 實際完成 22 列／44 phase；第 23 個 root `run/m7-home-61c6dcb0cc8f4e25a20b4dcc8c6b7059` 的 crash 收據已 exit 77，runner 在 startup 寫入中讀得 null，於讀取 `nonce` 欄位報錯。這是 runner FAIL，不能改記為 controller 已成功取消；原 sidecar 的 `CONTROLLER_ABORTED_DIAGNOSTIC` 須另補終局分類，原件／log 保留。整批原已 diagnostic-only，不抵正式 gate。
- 作者追 startup 完整發布／讀取及 main-only typed class oracle 的測試前提；後者曾把 Fabric API 的 `FabricGameTestModInitializer` 名稱誤判為 testmod。所有實際失敗與期待負例須分列原件，修正後新 root，不洗原判。
- 正式 freeze 前仍須完成上述技術修正、合法 budget fixture、main-only／aggregate 正負控、README 及完整 source／compiled／argfiles 快照，再以新 nonce 正式全列。必要可攜 main-only／Loom 匯出薄 helper 納 T1 allowlist／指紋／review，production 未改。T1 OPEN。
#### T1 startup 最小正負控與剩餘接線（OPEN）

- `t1-startup-publication/` XML 已由 controller 核 `M7PublishSpikeTest.startupPublicationIsInvisibleUntilForcedAndPublished` 1/1，failures／errors／skipped 0，0.062 秒，SHA-256 `9E43B2F71179415AB3C6E741DC03AA7D965584213A5DA9580FB3673F10532889`。作者以實際 `M7HomeProbe.publishDurable` 驗 temp 未發布不可見、force＋hardlink後完整、已發布空／半截／缺欄／錯 nonce 拒絕；正式 native／雙 OS 尚待。
- 舊 runner 當時實讀 raw bytes／長度／時間未保存，明記 UNKNOWN；現在完整 startup 不能當作當時 raw。工程批已另存 `t1-native-final-terminal.json` 為 `RUNNER_FAIL_DIAGNOSTIC`，保留原分類 sidecar 與 FAIL。
- main-only 實際首試兩個 FAIL 分開保留：`t1-main-only` 匯出 executable=null、JVM 前拒絕 outer1；`t1-main-only-2` 真 JVM 正常停服但 oracle 誤判 Fabric API 類別名稱，outer1。這些不是設計的隔離負控；修正後須新 root 實跑。
- 尚欠 main-only runtime／aggregate 正負 fixture、合法 budget 後繼與清單、README／workflow 最終 fixture、immutable source／compiled／argfiles 新 runner 定向驗；補齊再一次凍結，當前無 active JVM／正式全集。T1 OPEN。
### binding spec 第一輪獨立審查（2026-11-13）

- 結果：Critical 1／Important 6／Minor 1，禁止進入 plan；本輪沒有 runtime gate。
- 報告：`.superpowers/sdd/2026-11-13-m7-return-home/spec-review-1.md`，SHA-256 `96062DAC8AD533D2CD7270249966B603EE6AD2EDFFD909C947AE54ED5A6432B5`。
- 已核實的阻擋：固定 HOME 與入口返還門重疊；選後停用事件與停止 latch 的合法矩陣不足；終局後 B 普通開門的 crash 恢復不足；終身4096筆歷史上限及實際 NBT 預算不足；非同步選門重驗不足；UNKNOWN 載入逾時不應當作已選旅程終局；終局原因與持久證據需要共用 strict 矩陣。
- 處置中：交同一作者修訂，保留原審查結果；歷史收據改採可持續、按 SID 獨立有界存放的設計，不把全存檔回程次數永久封頂。修稿仍須獨立複審清零，不是實作完成。
- 修稿 v0.2 已交回：361行，SHA-256 `04653A840E9086A7AD8B903054B56D30A83D0D8DC7910029CC0EF081947B99F5`；處置表 `spec-fix-1.md`，原獨立 reviewer 正在定向複審。作者稱已處理不等於 finding 已關閉；未開始 plan、production 或 runtime gate。
- 第二輪獨立複審 `spec-review-2.md`：Critical 0／Important 2／Minor 1。第一輪 C1、I2、I4–I6、M1 已關閉；I1 尚欠停用已落盤而 stop 未落盤的開服復啟圍欄，I3 尚欠未選門 remap 新舊租約聯集的容量計量。Minor 是補回 B/B 競爭、普通玩家忙時拆 A 與 native／Crossed／playerdata 跨 JVM 窗口。已交同作者窄修 v0.3；仍禁止進 plan，未執行 runtime gate。
- 第三輪獨立複審 `spec-review-3.md`：Critical 0／Important 0／Minor 0，SHA-256 `F6213389B4FD3C796E19644AB83AA0B68C4FBE1D80A111D0E1BB884BE90B1CA0`。審查稿 v0.3 共369行，SHA-256 `463FAA723F6E15915B34378774E298D52C84BB3AFC09EF44EC980807B988D1F2`。I1／I3／漏測均關閉，採用為 binding spec v1.0；只更新規格標頭的採用註記，契約不變。依使用者「審查清零就直接往下做（建議）」開始 plan，不再等核可；未執行 runtime gate。

### 新增玩法裁示：選定後 A 被停用或拆除（2026-11-13）

- Codex 提問：「M7 已規定選定回家門後，A 斷電不影響這趟；但尚未規定『選門後，A 原艙被管理員停用或拆除』的結果。這種情況要如何處理？第一項會保留回家目的地與收據，玩家留在 B，且不重建 A。」
- 使用者選：「停止回程並保留選擇（建議）」；未選「仍照原座標傳回 A」。
- 適用結果：不把單純斷電的快照規則擴張成忽略停用或失根；停止尚未完成的回程、保留選擇，不重建 A。「保留選擇」只指保留收據作紀錄，不是掛著等待；具體終局依下方追加裁示。已跨過的人不回拉、不重建、不寫入 A，使用者在本次追加裁示再次確認。

### 追加玩法裁示：停止回程的終局與 A 保護（2026-11-13）

來源：使用者貼入本對話的「巡檢回饋與使用者新增裁示」，以下照錄題目、選項與說明。

1. 題目：「選定回家門後 A 被停用或拆除「停止回程」，之後這趟旅程怎麼收尾？」
   - 使用者選：「這趟結束，艙恢復可用（建議）」。
   - 說明原文：「B 的門照一般方式打開，「同時只能一趟」的佔用解除。收據保留作紀錄，不重抽、不改寫。A 恢復之後，要重新喝藥、再出發一次。」
   - 未選：「掛著等 A 恢復」。
2. 題目：「有旅程從 B 出發時，A 原艙要不要維持保護（不能被拆）？」
   - 使用者選：「維持保護到這趟收尾（建議）」。
   - 說明原文：「和現在「供電中或返還完成前保留保護」的做法一致。A 斷電仍不影響這趟，但收尾前 A 不會變成可拆。這樣「選門後 A 被拆」只剩管理員用指令等例外。停用開關仍可切換，切了就照剛才的裁示停止回程。」
   - 未選：「不特別保護」。

本追加裁示釐清前一節：「停止」是這一趟的終局，終局後解除同 Chamber 全域佔用；收據保留、不重抽、不改寫，不把原 session 掛著等 A 恢復。A 恢復後須另開新 session；上方選項說明的「重新喝藥」經下一題釐清為可沿用未到期藥效。只有停用或拆除會依此停止；選定後單純斷電不影響這趟。已跨過的人不回拉，不重建、不寫入 A。

3. Codex 釐清題目：「你補充的「A 恢復之後，要重新喝藥」，是否表示原本藥效還在，也必須額外喝一瓶？既有規則會保留剩餘藥效；兩個選項都會結束舊旅程、解除佔用，並要求另開新旅程。」
   - 使用者選：「可沿用未到期藥效（建議）」。
   - 未選：「停止後必須重新喝一瓶」。
   - 因此不清除剩餘 QuantumState、不要求額外喝一瓶、不新增飲藥次數或 playerdata 門檻；新旅程沿既有全員有效藥效規則，仍須新 session，不復活舊收據。

## Step0（2026-11-13，Claude，只改文件）

### 基線

- 分支 `codex/m7-return-home`，工作樹 `.worktrees/m7-return-home`，2026-11-13 從 `main` `225502728bd7839809ee5a6e4696ce079aebd42e` 開出。
- annotated tag `m6`：object `54a8f66f744ed741056785980c8904ef95b0a25f`，peeled `53573fc17fa665d254ed08e9327bb843a5af93e9`。
- `2255027` 的 CI run `43223550800`：`build`、`windows-native`、`m6-native` 三個 job 都是 success（2026-11-13 以 `gh run view` 讀回）。log 另存在 repo 外 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\ci-43223550800.log`，SHA-256 `8CB19D68358C9D41F6CB2BF66B17C64967678C2C7FCE3082DCA8BF2B3788B4DC`。
- 本 Step0 只改文件，沒有跑 Gradle 或任何 gate。

### 範圍裁示（2026-11-13）

使用者在這次討論以 AskUserQuestion 逐題回答。下表照錄題目、使用者選的選項標籤與該選項的說明原文；標籤就是使用者的原話。題目與選項由 Claude 撰寫，選項說明是使用者選擇時看到的內容。

#### 1. 在 B 啟動的是哪一種艙

- 題目：「「在 B 啟動」要做哪一種艙？」
- 使用者選：「到達時的投影艙（建議）」
- 選項說明原文：「到 B 時同座標自動出現的那座艙可以再出發。它和 A 的原艙算同一座，A 永遠是它的「家」。好處：不用再蓋；回程終點明確（就是 A 原艙，同座標）；不會在你的原本世界亂寫新艙。」
- 未選：「在 B 新蓋的艙」「兩者都要」。
- 依這段選項說明，回程的落點是 A 原艙內的同座標（投影艙和原艙同座標）。

#### 2. 回程入口

- 題目：「從 B 回家的入口要怎麼做？」
- 使用者選：「走廊裡固定一扇回家門（建議）」
- 選項說明原文：「在 B 的投影艙照常供電、喝藥、關門進走廊；走廊裡永遠有一扇外觀不同的「回家門」。選了它 → 回到 B 的艙 → 量子膜打開 → 跨過去就回到 A 原艙。和探索是同一套流程；之後加上連續穿越時，同一條走廊會同時有回家門和一般的門。」
- 未選：「不進走廊，直接回家」。

#### 3. 範圍切分與順序

- 題目：「範圍和先後順序怎麼切？」
- 使用者選：「拆兩個，先回程（建議）」
- 選項說明原文：「下一個 milestone 先做「投影艙可啟動＋回程」，完成就合併 main、可以試玩；再下一個 milestone 加上一般的門，完成 A→B→C。兩者共用的基礎（投影艙啟動、同一座艙一次只跑一趟、取消時回到出發點）在第一個就做好。」
- 未選：「一個 milestone 一起做」「拆兩個，先連續穿越」。

#### 4. 回程的供電條件

- 題目：「回程要什麼條件（供電、藥水）？」
- 使用者選：「A、B 兩端都要供電」（不是建議項；建議項是「和出發一樣，只要 B 端供電（建議）」）
- 選項說明原文：「更符合「錨點維持聯繫」的設定。但 A 斷電時（例如多人服有人關掉拉桿）會被困在 B；也要多做「從 B 讀取 A 的供電狀態」的機制。」
- 另一個未選項是「回程不用藥水」。藥水要求見「沿用與推論」。

#### 5. A 的電在什麼時候檢查

- 題目：「既然 A 也要供電，什麼時候檢查 A 的電？」
- 使用者選：「選回家門時檢查一次（建議）」
- 選項說明原文：「選門那一刻 A 要有電，否則門鎖住並提示「原艙未供電」。選定之後 A 斷電不影響這趟（和「選擇鎖定就不反悔」一致）。A 區塊未載入時，系統會先暫時載入去讀電位，不直接當成斷電。」
- 未選：「選門時＋量子膜打開前各一次」「全程監看」。

#### 6. A 原艙不能用時

- 題目：「A 的原艙不能用時（被拆、結構不完整）怎麼辦？」
- 使用者選：「回家門鎖住並說明原因（建議）」
- 選項說明原文：「不重建、不寫入 A 的任何方塊；提示「原艙已被拆除／結構不完整」。A 的區塊沒載入不算失效，系統會暫時載入去檢查。玩家仍可走地獄門或床回去。」
- 未選：「照樣回到 A 同座標」。

#### 7. 第一個 milestone 時 B 的走廊

- 題目：「第一個 milestone（只做回程）時，B 的走廊裡一般的門怎麼處理？」
- 使用者選：「只有回家門（建議）」
- 選項說明原文：「一般門的位置先是牆，下一個 milestone 才加入。遊戲裡不放「即將開放」之類的字樣。」

#### 8. 同一座艙的同時使用

- 第一次題目：「A 的原艙和 B 的投影艙算同一座，多人怎麼處理？」使用者選「各端可以同時跑」。
- Claude 隨後說明互等卡死：甲在 A 選到通往 B 的門、乙在 B 選了回家門，兩隊都回到各自的艙等量子膜；落點又都在對方正在使用的艙內，若規則是「目的端忙就等」，兩邊會互等。
- 第二次題目：「知道互等卡死的問題後，多人同時使用要怎麼定？」
- 使用者選：「改回同時只能一趟（建議）」——**這是有效裁示，取代第一次的選擇**。
- 選項說明原文：「同一座艙的所有端（A 原艙、B、C 投影艙）同時只有一趟旅程；另一端啟動時提示「這座艙已有旅程進行中」。不會互等、不會兩隊擠一艙；最多等一趟（約 3 分鐘走廊＋準備＋60 秒膜）。」
- 「約 3 分鐘」來自 `ModPotions` 的 QuantumState 藥水時長 3600 tick。

#### 9. 狀態提示

- 題目：「狀態提示（設計稿建議先做的「玩家不看 log 也知道艙在做什麼」）要不要一起做？」
- 使用者選：「只做回程需要的提示（建議）」
- 選項說明原文：「例如回家門鎖住的原因、「這座艙已有旅程進行中」、「已回到原本世界」。完整的狀態提示另外排。」

#### 10. 外觀

- 題目：「4 張概念圖要採用到什麼程度？」
- 使用者選：「這兩個 milestone 不改外觀（建議）」
- 選項說明原文：「概念圖先當之後的外觀方向。艙的結構（7×7×7、1 格基岩外殼）不變；只給回家門做「看得出和一般門不同」的最小區別。完整外觀改版另外開 milestone。」

#### 11. 人工驗收

- 題目：「回程 milestone 的人工驗收（實際開遊戲試）由誰操作？」
- 使用者選：「由 Claude 代操作（建議）」
- 選項說明原文：「比照 M6：由 Claude 自動操作測試世界並截圖留證，結果寫進驗收清單。到時候仍會先問你一次再開始。」
- 這是驗收方式的決定，不是現在就授權代操作；開始前仍要另問。

#### 12. Step0 由誰做

- 題目：「範圍確認後，開新工作樹與第一個文件 commit（納入你的 6 個檔、記錄工作樹刪除結果、修正 README／AGENTS 過時的入口、寫入這次的決策紀錄）由誰做？」
- 使用者選：「由 Claude 在這次對話做（建議）」

#### 13. 命名

- 題目：「milestone 與分支名稱怎麼取？」
- 使用者選：「M7 正規回程／M8 連續穿越（建議）」
- 選項說明原文：「分支 codex/m7-return-home，工作樹 .worktrees/m7-return-home，從 main 2255027 開出。M8 等 M7 合併、打 tag 後再開。」

#### 14. Claude 補的細節 a～e

- 題目：「上面「我補的細節」a～e 可以採用嗎？」
- 使用者選：「全部採用（建議）」
- 使用者看到的 a～e 原文：
  - a. 這個 milestone 的走廊只有回家門。所以在 B 啟動的那一刻，如果 A 已經不能回（原艙不在或沒電），就直接拒絕啟動並說明原因，免得你喝了藥進走廊卻發現門鎖住。到第二個 milestone 走廊有其他門時，改成只鎖回家門。
  - b. 走廊中途藥效到期，或喝牛奶取消，一律回到 B，不是回 A。
  - c. 任何宇宙裡、由這座艙產生的投影艙都能回家。
  - d. A 原艙被管理員設為停用時，也算不能回，回家門鎖住。
  - e. 舊存檔與進行中的舊旅程維持原本的意思。新資料要有版本號；「平行宇宙只有主世界」的設定不變。

#### 15. M8 方向（開工前再確認）

- 題目：「（第二個 milestone 的方向）從 B 出發時，一般的門以誰為準？」
- 使用者選：「一律以 B 為準（建議）」
- 選項說明原文：「原艙門（直接打開、不穿越）代表留在 B；「同 seed」類的新宇宙以 B 的生成設定為基準；「已去過的宇宙」排除 B 本身、也不包含 A（回 A 走回家門）；走廊中途取消或藥效到期回到 B。」
- 這是 M8 的方向，不是 M7 範圍；M8 開工前要再和使用者確認。

### 沿用與推論（沒有單獨提問）

以下各條由 Claude 依現行行為或建議稿列入給使用者看的摘要。使用者沒有對這些條目單獨作答。binding spec 要逐條標明來源，有疑義就問使用者：

- 回程和出發一樣要全員具 QuantumState（喝藥）。使用者沒有選「回程不用藥水」，第 2 題選項說明原文也寫了「照常供電、喝藥、關門進走廊」；但藥水沒有另外單獨提問。
- 回程的隊伍規則沿用出發：只算一起進艙的參與者；量子膜開約 60 秒；跨過的人到 A，沒跨的留在 B。
- 成功回到 A 時，也不寫入、不重建 A 的任何方塊，不清空原艙內容。來源是建議稿 §10.3 與 §17.3 C10；第 6 題選項說明只涵蓋原艙失效的情況。

### 流程指示（2026-11-13）

現行追加授權與止損以本檔最上方「T2 起連續推進與新止損」節為準；下列舊 T1 hold 為歷史。

來源是使用者 2026-11-13 貼給本對話的接手提示詞。那份提示詞由先前的 Claude 對話起草，再由使用者貼上。相關原文：

- 「最後才產出一份「給 Codex 新對話的派工提示詞」（見第 6 節）。討論完成前不要先寫。」
- 「流程：binding spec → 獨立審查 → plan → 審查 → Subagent-Driven Development 實作。production 改動走完整 gate；只改測試、腳本或文件時，照 B+C 走一席審查、定向驗證與 CI；」
- 「授權邊界：正常 push 可以；main 合併、tag、人工代操作、worktree 退役各自要另問使用者。」

Codex 接手時，使用者的流程選項裁示（2026-11-13）：

- 「審查清零就直接往下做（建議）」：binding spec 與 plan 各經獨立審查，Critical／Important 清零後，直接接續 plan／Subagent-Driven Development 實作，不另等整份設計或計畫核可。只有 M7 note 未涵蓋、需要使用者決定的玩法問題才停下詢問。

Codex 接手時的派工提示詞（Claude 起草、使用者轉貼，2026-11-13）另載下列要求；它們不是另一次選項裁示：

- production 改動完整門檻是 RED／GREEN、mutation、focused、完整 run-all、同 SHA CI；只改測試、腳本或文件時一席審查、定向驗證與 CI，免 mutation 與本機完整回歸。M6 B+C 的其他條款不沿用。
- 每次 commit、push 或 gate 終局同步本檔與 AGENTS 接手入口；舊 FAIL 與失敗 root 保留原判，修改後使用新 nonce，不自行放寬驗收容許值。
- 正常 push 可以但不 force；main 合併、tag、人工代操作（方式已選 Claude，開始前仍問）、worktree 退役、PR、LICENSE 各自另問使用者。

依此，M7 的 binding spec、plan 與實作交給 Codex 新對話；Claude 只做 Step0 並產出派工提示詞。

M7 從 M6 的 B+C 規則只沿用上面引號內的那一條：只改測試、腳本或文件時，做一席審查、定向驗證與 CI，免 mutation 與本機完整回歸。M6 B+C 的其他條款不自動沿用，例如「純文件 CI 若僅已知偶發失敗不重開 task」「同 code pin 的已知 AccessDenied／走廊容量連坐最多保留原根後重跑一次」，要用時另問使用者。

### 與建議稿 `QuantumChamber_Gameplay_Core_Design_v0.1.md` 的差異

建議稿的 G0–G5 是該稿自己的代號，不是 M7／M8。對應關係與差異：

- 順序：建議稿 §16 建議先做 G1（狀態提示）。使用者裁示先做回程（M7），狀態提示只做回程需要的部分（第 9 題）。
- 回程入口：建議稿 §10.2 建議返航不走未知候選走廊，採固定目的地模式。使用者選走廊裡固定一扇回家門（第 2 題）。
- 根錨供電：建議稿 §13.2 建議有界的根錨檢查。使用者選 A、B 兩端都要供電，選回家門時檢查一次（第 4、5 題），M7 另在 B 啟動時先檢查（細節 a）。
- 一致的部分：投影代理（§10.1，第 1 題）；先回程、後連續穿越（§10.2，第 3 題）；同一座艙一次一趟（§10.6，第 8 題）；B 出發以 B 為準（§10.4 與 §17.3 C12，第 15 題）。
- 對應：M7 約等於 G3，改用走廊回家門，並加上回程需要的提示；M8 約等於 G4。

### 不在 M7 範圍

- 從 B 出發的一般候選門與 A→B→C（M8）。
- 在 B 新蓋的艙作為新起點（未排程）。
- 艙的外觀與結構改版，包括 4 張概念圖的風格（另開 milestone）。
- 完整狀態提示與探險紀錄（建議稿 G1、G2，未排程）。
- 專屬 Nether／End family；`FamilyCapabilityVersion` 維持 0。平行宇宙只有主世界。
- 可逆佔位、自選已知航路、任意生物或載具旅行。
- 2026-11-18 11:5x 第 3 題（選「照建議分類（建議）」，原文見「遊玩條件組合分析與使用者裁示（2026-11-18）」節）：
  - 放 M8：停用與拆除權限、離線參與者、原地重建艙、等待上限與狀態提示。
  - 只記為已知行為：PREPARE 無限等待、投影清空容器、不調整 Y。
- 2026-11-18 12:4x 第 2 題（選「不納入，記為已知行為（建議）」，原文見「最新：spec v1.6 修訂（2026-11-18）」節）：還沒選門就被系統整組送回艙內（例如隊友斷線、走廊容量或實體數上限、入場失敗）之後，若艙仍有電、門關著、藥效還在，可能立刻再出發；M7 不改。「會立刻再出發」是推論，未經實測。

### 納入 git 的使用者檔案

使用者 2026-11-12 在主 checkout 的 `docs/` 新增 6 個檔。Step0 複製到本工作樹後一併 commit，兩邊的 SHA-256 一致：

| 檔案 | SHA-256 |
|---|---|
| `docs/QuantumChamber_Gameplay_Core_Design_v0.1.md` | `EBA1D7D4775EDE51194DF6A40BEFA84ACDFB9EE9ED1FCA2CA8319BE4CBC6B3F0` |
| `docs/QuantumChamber_Code_Agent_Handoff_v0.1.md` | `9546014A8898449001CD291FCC09B3FD61F0CFAA4F8DA9CA39D47950689D8462` |
| `docs/mc量子盒構造1.png` | `081A93B904B9D5309E9F9F858BAA5671EECE968A353F3904421B93148CA687E7` |
| `docs/mc量子盒構造2.png` | `5FF276CFAA5749C5AC5FF4B2ED2973A3E04372FB33EC4F2460A41E2A76B36C84` |
| `docs/mc量子盒構造3.png` | `97E169265E3AAE8C9CFE2007E0A8F6570CFD5FE186F44E4B9A9BAE1E9FE168F9` |
| `docs/mc量子盒構造4.png` | `0B1F62A774AD1FA8C1F93BAE4079E85ECB54986CBA51880DD826A3CB756C2D39` |

- 上表是原檔位元組的 SHA。兩份 md 是 LF 換行，也就是 commit 內的內容；這台機器 `core.autocrlf=true`，Windows checkout 出來會變成 CRLF，雜湊會不同。要驗證請用 `git show <commit>:<path> | sha256sum`。png 不受影響。
- 兩份 md 是建議稿與接手範本，**不是 binding spec**。它們的基線是 `71f4f72`（M6 收尾前），把 M6 收尾列為待辦（建議稿 §16「G0｜完成原定 M6 收尾」、接手範本「G0：依原計畫完成 M6 收尾」），反映的是當時狀態。
- 接手範本 §3～§5 要求 code agent 先做 G1，並在第一輪交付五項（含 G1 設計）。這些已被本檔的範圍裁示取代（第 3、9 題），不作為派工指示。
- 4 張 png 是概念美術，牆和屋頂比現行 1 格外殼厚，只當外觀方向。
- 主 checkout 的未追蹤原檔依 AGENTS 規則保留，沒有刪除。M7 以 `--ff-only` 合併 main 時，git 可能因為這些未追蹤檔會被覆蓋而拒絕；屆時先比對上表 SHA，再請使用者決定怎麼處理主 checkout 的那份。

### `.worktrees` 退役結果（2026-11-12）

依 AGENTS「M6／`.worktrees` 退役專屬裁示」，於 2026-11-12 21:42–21:48 刪除整個 `.worktrees`（M6 工作樹、W10 producer、兩個 T12 突變 clone）。

前一個對話的執行紀錄如下。原始 log 放在那個對話的 scratchpad，現已不存在，本 Step0 無法重驗：

- 刪除前執行 `gradle --stop`，確認沒有 java 行程。
- 目標是唯一的 canonical absolute path，不是 reparse point，底下恰好 4 個資料夾。
- `session.lock` 共 11726 個，都能以獨占方式開啟，代表沒有被持有。
- 先只解除 4489 個 junction 連結本身，不跟進目標；其中有些指向 `C:\Users\Ben`。
- 刪除後以 `git worktree prune` 移除兩筆登記。
- 程序和裁示第 5 步不同：沒有用 `git worktree remove --force`。原因是有些 junction 指向 repo 外，改為先只解除連結，再 `Remove-Item` 遞迴刪除，最後 `git worktree prune`。刪除範圍不變。

本 Step0（2026-11-13）重驗：

- 開 M7 工作樹前，`.worktrees` 不存在，`git worktree list` 只有主 checkout `2255027`。
- 分支 `codex/m6-universe-generation` 在本機與 origin 都保留；`ls-remote` 讀回 origin 是 `2255027`。
- repo 外驗收證據 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\evidence-index.json`（SHA-256 `84D058E4BB1CE22888D1FD7C7D91FED985E1064FFAA7522BBA75851093FAC8D7`）列出的 177 個檔，逐檔 SHA-256 都相符，沒有缺檔。

### 給後續 task 的提醒

- 執行任何會跑 W10 preflight 的入口之前，要先照 `scripts/verification/README.md` 重建 W10 producer，並在 UTF-8 console（code page 65001）執行。這些入口包括：完整或 `-PreflightOnly` 的 run-all、不加 `-Only` 或 `-Only w10` 的 `m5-collapse-probe.ps1`、`m6-harness-safety-check.ps1 -Case ci-cwd-preflight`。CI 不跑這些入口。
- 下一步：binding spec → 獨立審查 → plan → 獨立審查 → Subagent-Driven Development 實作。
