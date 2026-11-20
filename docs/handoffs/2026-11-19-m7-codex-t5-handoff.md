# M7 交給 Codex 接手（2026-11-19，T5-0 進行中）

使用者 2026-11-19 指示「可以準備收尾交給codex實作」。Claude 停止新的實作派工，把狀態寫在本檔。本檔是 Codex 的接手入口；`AGENTS.md`「目前接手入口」也指向這裡。

下文簡稱：

- `W`＝`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home`（唯一開發 worktree，分支 `codex/m7-return-home`）。
- `S`＝`W/.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored 的證據與派工空間，不得 force-add 或刪除）。
- `V`＝`W/.superpowers/verification/`（gitignored 的 gate／run-all root）。

## 1. 先讀（依序）

1. 本檔。
2. `AGENTS.md`「目前接手入口」的 M7 段，以及其中兩個常駐段：「M7 Windows atomic-move AccessDenied 單次重跑規則（使用者 2026-11-18）」與「M5 投影不變格熔岩偶發的 CI 處理（使用者 2026-11-18）」。
3. binding spec `docs/superpowers/specs/2026-11-13-m7-return-home-design.md`（v1.6，pin 見 plan 第 8 行），特別是文末「2026-11-18 v1.6 遊玩條件組合裁示」節 A～G。
4. plan `docs/superpowers/plans/2026-11-13-m7-return-home.md`：Task 5～8，以及檔尾「2026-11-18 v1.6 修訂」節的追加項。
5. M7 note `docs/implementation-notes/2026-11-13-m7-return-home.md` 最上方幾節：「最新：T4B CLOSED（2026-11-19）」（含「殘留交 T5 Step 0」與「SelfTest 負控上限的使用者裁示」）、「最新：T4A CLOSED」、「最新：spec v1.6 修訂」、「最新：T4 CLOSED」。
6. `S/STATUS.md` 首段數段（controller 的短期狀態，最新在最上面）。
7. `scripts/verification/README.md` 的 M7 段（gate、原生 runner、run-all 用法）。

## 2. Git 與各 task 狀態（2026-11-19 交接時）

交接撰寫時：本機 HEAD `c749739`（T5-0 第二批），工作樹 tracked 乾淨，沒有殘留 Java 程序；origin feature 為 `c533193`，origin main 為 `2255027`。本檔與 AGENTS／note 的交接 docs commit 接在 `c749739` 之後，連同 T5-0 兩筆 commit 一起 push；實際 HEAD 與遠端以 `git log`、`git ls-remote` 為準。

| 項目 | 狀態 |
| --- | --- |
| origin/main | `2255027`（M6 整合後）；M7 沒有合併 main |
| tag `m6` | peeled `53573fc`；M7 沒有 tag |
| T1、T2、T3、T4 | CLOSED（細節見 note 各節；T4 code pin `e28784c`，同 SHA CI 44207705353） |
| spec v1.6 | 採用並 push（`5ae0a04`、`70ed3d9`）；目前 pin `E8AB80D3D7C1296CF64E00CEC16EE14AED30EED6AA06E35819794357DD68ECA2` |
| T4A | CLOSED。production `c091581`，testmod fix1 `099252f`；完整回歸 `V/run-all-5ff86c0b885e46e691645c7608afe69f` END ALL 0；CI 44281662893 十 job success |
| T4B | CLOSED。production 最後一筆 `0f04037`，HEAD `e3eea5a`；第一次完整回歸 `V/run-all-902b2e39…` FAIL 原判保留，依使用者裁示重跑 `V/run-all-b90f443e98784cbbb1c4903cb292d90c` END ALL 0；CI 44362426613 十 job success |
| T4B 結案 docs | `c533193`，CI 44456286699 十 job success |
| T5-0（T5 Step 0） | **未結案**：第一批 `b0ea0f8`、第二批 `c749739` 已 commit，未審查、未跑完整回歸；第三批未開始。見第 3 節 |
| T5 本體、T6、T7、T8 | 未開始 |

## 3. T5-0 交接狀態（未結案，Codex 的第一件工作）

T5-0 是 controller 從 T5 拆出的獨立段（controller 排序，不是使用者裁示）：只做 Step 0 清單，不碰 T5 本體。派工簡報 `S/task-5-0-brief.md`，implementer 報告 `S/t5-0-report.md`。

implementer 依 controller 的收尾指示停在乾淨的點（報告 `S/t5-0-report.md`，SHA-256 前綴 `1555e9a0`）：

- **第一批 `b0ea0f8`（SelfTest 上限，只改 `scripts/verification/m7-home-probe.ps1` 與 README）**：每次呼叫最多 1000 個負控、120 秒，超過個數 `M7_HOME_SELFTEST_BUDGET`、超時 `M7_HOME_SELFTEST_TIME`；補 `M7_HOME_REOPEN_B_TICKET`、`entryTicketLease` 負控。新數：單份完整 404（5.3 秒）、family 三份彙總 434、門態五份彙總 454。8 個變異具名殺掉並逐位元還原。證據 `S/t5-0-selftest/`。
- **第二批 `c749739`（只改 testmod、inventory、README、workflow）**：`sharedEntry` 外層失敗收尾、M4 readback probe 改走正式 schema5 路徑、fixture checked 寫入失敗記 `TESTMOD_FIXTURE_WRITE_FAILED`（帶 cause）再丟出、T4B 審查 M-2 的新案 `close_ticket_released_when_the_door_write_fails_and_backs_off`（`backOff` 單點變異已實跑並殺掉）、M-7 的新案 `until_predicate_failure_runs_registered_cleanup`、CI 規模段 `if: ${{ !cancelled() }}` 與 20／10 分時限。乾淨 HEAD 定向一輪 `S/t5-0-batch2/focused-r1/` 全過（M7 `junit=53 gametest=53`、default 5/5、M5 2/2）；只有一輪，第二輪待做。
- **未做**：
  - 第二批的 B2b5-M1（宣告掃描在 `scripts/verification/m7-ci-gametest.ps1`，不在授權清單）。報告寫了兩種做法：授權該腳本加類別層級核對與負例，或在 `src/test/java` 加 JUnit 掃描。controller 未決定。
  - 第三批 production 全部（T4B 審查 M-1 `restartVerifies` 加 `stopReason().isEmpty()`、M-3 FULL 後端點不合法立即釋票與正確 WARN、`SessionRecoveryState.java:1027` 註解歸屬）。具名 RED 的設計在報告。
  - 只評估項：第 6、7、8 點屬 T5／T6 本體；第 10 點是測試規則；第 9 點的 `onEntryStopped` GameTest＋mutation 可在 T5-0 先做（尚未做）；待驗「真停服」「RESTART_VERIFY 真重啟」需要新原生列；relatch 變異在 Ubuntu 只能靠 CI。
  - fixture cause 記錄沒有擴到 M2 `TrustedSpace`、`M4CandidateTestAccess`、`M5CollapseFixture`（理由在報告）。

已 commit 的兩批**沒有經過獨立審查，也沒有跑完整回歸**；第一次 CI 會在交接 push 後跑（看交接 docs HEAD 的 run）。Codex 接手後：

1. 核 HEAD、`git status --porcelain`、`S/t5-0-report.md`，確認與本節一致；看交接 docs HEAD 的 CI 結果（FAIL 保留原判、分類後處理）。
2. 完成剩下的 T5-0 項目：B2b5-M1（擇一做法；改 `m7-ci-gametest.ps1` 屬只改腳本的 B+C）、第三批 production、第二批定向第二輪；第 9 點 `onEntryStopped` 要不要在 T5-0 做由 Codex 依 plan 判斷並記錄。
3. 獨立審查（一席，規格＋品質）C/I 清零。
4. 完整回歸（見第 6 節）自然 `END ALL exit=0`，並逐項核採計條件。
5. push，同 SHA 全部 required CI 全綠（M5 熔岩偶發依常駐段處理）。
6. 寫 T5-0 結案 docs（一席審查），再開 T5 本體。

## 4. 剩下的工作

依 plan 的順序：T5-0 → T5 本體 → T6 → T7 → T8（controller 排序；T4A、T4B 已插在 T5 前並完成）。

- **T5 本體**：plan Task 5（:299–:331）＋檔尾「Task 5 追加項」。T5 Step 0 清單中屬 T5 本體的評估項，見 `S/t5-0-report.md` 的評估結論。
- **T6、T7**：plan Task 6、7 與檔尾追加項（v1.6 A 事件 3、4 的 latch、B 門擁有、B 收尾上鎖等）。
- **T8**：整分支驗收、人工 smoke、整合交回。人工 smoke 的方式使用者先前選由 Claude 代操作，但**每次開始前都要另問使用者**；main 合併、tag、worktree 退役也各自另問。

## 5. 使用者裁示與常駐規則（binding）

原文都在 note 與 `S/user-*.md`；轉述不加引號。

- **連續推進**：使用者選「審查清零就直接往下做（建議）」。每個 task 結案短報 task、code pin、CI run、殘留、下一個 task。
- **AccessDenied 單次重跑**（AGENTS 常駐段）：完整回歸唯一失敗是 Windows atomic move AccessDenied、沒有其他失敗、production 仍 fail-closed 時，保留原 root，換新 nonce 重跑一次，不用再問；同一 code pin 第二次就停下回報。適用 M7 剩下的所有 task（含 T4A／T4B 是 controller 讀法，已告知使用者、使用者未回覆）。`e3eea5a` 的額度已用；新的 code pin 各自一次。cause 未記錄、只能推論時不自動套用，要問使用者（T4B 先例）。
- **M5 熔岩偶發**（AGENTS 常駐段）：只限 `m5universeprojectiongametests.projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids` 對照組逾時且 `waterMoved=false`，加它連帶的 `M6_SCOPE_MISMATCH（m5）`（expected=actual、缺多皆空）。同 run 有其他失敗照舊停報；符合時不阻擋結案，但結案 docs 逐筆列出，不寫 CI 全綠。
- **SelfTest 負控上限**（使用者 2026-11-19）：M7 原生 runner 每次 `-VerifySummary -SelfTest`／`-VerifyAggregate -SelfTest` 呼叫最多 1000 個負控、SelfTest 120 秒，超過以具名錯誤失敗，不得抽樣略過。T5-0 第一批負責實作。
- **spec v1.6 的玩法裁示**（11-18 11:5x、12:4x）：取消或回家後要玩家親手重新關門才再出發（A、B 一致）；回程期間 B 門比照原艙門（參與者關門＝取消，含等家艙載入；非參與者拒；B 斷電等待照一般門）；回程收尾替 B 取有界票（T4B 已做）；永久封存的最小解除路徑（T4A 已做）；未選門的整組返還不上鎖（已知行為）。
- **spec G 節的 controller 讀法**（第 1～7 點）：都已告知使用者或待告知，使用者有不同意見時照改。
- **時限與容許值**：不自行放寬驗收容許值或時限；時限類決定交使用者（M7 gate `M7Runtime` 總時限 600、idle 90 是使用者裁示）。

## 6. 工作方式與門檻

- **Subagent-Driven Development**：一個 implementer 完成一個 task 或 fix round，之後必有獨立 reviewer（規格＋品質，可一席）。Critical／Important 清零才往下。fix 回原作者，複審回原審查席。
- **production 改動**：具名 RED（在現行程式上取得、保留原件）→ GREEN → 每個 mutation 由具名測試 killed 並逐位元還原（記 SHA-256）→ focused 兩輪 → 完整回歸 → 同 SHA CI。
- **只改測試／腳本／文件（B+C）**：一席審查、定向驗證、CI。之前 production 的完整回歸可以採計，但要在結案 docs 寫明。
- **完整回歸**：用 holder 跑，讓子程序 UTF-8（code page 65001）並持有 process handle。holder 腳本原件在 `S/t4b-full-holder-script-b5aa425c22f2480b998e8adfdd1bfdec.ps1`（SHA-256 前綴 `5AC80EDD`）；用法 `pwsh -NoProfile -File <腳本> -Root <S 下新的空目錄> -ExpectedHead <HEAD 全長>`，HEAD 必須乾淨。採計：`END ALL exit=0 HEAD=<pin>`，再以 `S/t4b-accept-check/t4b-accept-check.ps1 -Root <run-all root> -Pin <HEAD> -Rows <列數> -Phases <phase 數> -NegCount <負控數>` 逐項核對（預期值依當時 inventory 與原生 summary，並跑一次舊值負控確認會 FAIL）。
- **W10 前置**：run-all 的 w10-preflight 會核 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-w10-m5-producer`（detached `a2d2b40`），不重建、不重套。
- **CI**：push 後等整個 run `completed` 再判（`gh run watch` 曾在 job 未完成時提早結束，改用輪詢 `status==completed`）。CI FAIL 保留原判、不 rerun（M5 熔岩常駐段除外）；artifact 下載到 scratchpad 或 S 下新的空目錄，只讀。
- **docs commit**：spec 有改時，stage spec 後以 staged blob 的 SHA-256 填 plan 第 8 行，commit 後讀回核對。被程式、測試、腳本引用的 spec／plan 行號不能位移：新增內容放檔尾或行末附加（先 `git grep` 核對）。

## 7. 證據紀律、止損與計數

- 不偽造結果；舊 FAIL、UNKNOWN 保留原判；失敗的 root 保留，修正後換新 nonce。未執行的人工驗收不寫成通過。
- 使用者原文逐字照錄；轉述不加引號；controller／implementer 的決定與讀法要標明不是使用者裁示。
- 止損：
  - 完整回歸啟動器歷史 2/3：下一次非預期的工具失敗就停。
  - 同一驗證工具問題連續第三次非預期 FAIL 就停。
  - `participantCloses` fixture 與 T3 `only_exact` fixture：再有非預期失敗就停並詢問使用者。
  - 任何 cause 未記錄的寫入失敗，不自行歸為 AccessDenied。
- 跨案共用狀態：新測試要盤點並避免污染後續 batch（T4A fix1 的 checkpoint WARN 去重、T4B 的全域 FakeBackend 都是先例）。

## 8. 已知工具陷阱

- 路徑含空格：bash 裡不要把 `git -c safe.directory=$PWD` 存成字串變數再展開，會被拆字；用 shell 函式或直接寫全指令。
- `sed -i` 會把 CRLF 工作副本轉成 LF；改 tracked 檔用編輯工具。repo 是 `autocrlf=true`、index 存 LF。
- PowerShell 變數不分大小寫：參數 `$Neg` 會和腳本內的 `$neg` 衝突。
- `gh api` 不吃 `-R`，路徑直接寫 `repos/Ben513242/QuantumChamber/...`。
- github.com:443 偶爾連不上，稍後重試即可。
- 主 checkout 的 git 指令加 `-c safe.directory="<路徑>"`；`gh` 一律 `-R Ben513242/QuantumChamber`。

## 9. 授權與邊界

- 正常 feature push 已持續授權；不 force push、不建 PR、不建 LICENSE。
- `main` 只能 fast-forward；main 合併、tag、人工代操作、worktree 退役各自另問使用者。
- 不用 `reset --hard`、`checkout --`、`clean`、`add -A`、bare `git stash`；逐檔 stage。

## 10. 交回紀錄

- 2026-11-19（Claude → Codex）：本檔建立，與 AGENTS 入口、M7 note 新首節同一筆交接 docs commit（接在 `c749739` 之後），連同 T5-0 兩筆 commit 正常 push；push 後的遠端讀回記在 `S/STATUS.md` 最新段。Claude session 的 scratchpad 已整份複製到 `S/scratchpad-session-aa0d7b1f/`，逐檔 SHA-256 見 `S/scratchpad-session-aa0d7b1f-MANIFEST-sha256.txt`（3331 檔全部 OK）；其中 `full-holder.ps1` 與 `t4b-accept-check.ps1` 也另有 S 根目錄的副本。Claude 之後不再在這條分支實作。
- （Codex 交回時在此追加：日期、HEAD、完成與未完成項目、待裁示事項。）
- 2026-11-20（Codex接手階段記錄，非結案）：已核HEAD8f3c977/branch與clean，讀完指定入口/規則並判CI44480505340 attempt1 completed/failure（9success/1WindowsM7 failure）；原CI不rerun，原件與唯讀分析保在S的新ci-44480505340-codex-0befa5f8a4734cff83d2016383ae9418與t5-0-codex-ci-analysis.md。budget案首次票未ready、合法400退避超過原case觀察/清理，底層延誤未知；controller選不變時限的fixture責任分區，具體見M7note最新節，非user新裁示。T5-0第三批兩具名RED已取，production尚未改；B2b5-M1候選在途，兩批原code未獨審/未full的狀態保留，仍未結案。後續完成/未完成/待裁示在本節再追加。
