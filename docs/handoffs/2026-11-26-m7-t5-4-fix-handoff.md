# M7 交給新對話接手（2026-11-26，T5-4 本體審查未過，下一步 fix round）

使用者 2026-11-26 原話「好，審查回來沒問題就繼續另外等兩個子代理完成就收尾交接給新對話完整提示詞」。兩席審查都沒過（見第 3 節），所以本對話不派 fix round，停在這裡交接。本檔是新對話（Claude，controller）的接手入口；`AGENTS.md`「目前接手入口」也指向這裡。

下文簡稱：

- `W`＝`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home`（唯一開發 worktree，分支 `codex/m7-return-home`）。
- `S`＝`W/.superpowers/sdd/2026-11-13-m7-return-home/`（gitignored 的證據與派工空間，不得 force-add 或刪除）。
- `V`＝`W/.superpowers/verification/`（gitignored 的 gate／run-all root）。

## 1. 先讀（依序）

1. 本檔。
2. `AGENTS.md`「目前接手入口」最上面一段，以及其中的常駐段：AccessDenied 單次重跑（M7 2026-11-18 起，2026-11-25 擴充「連坐」）、M5 投影不變格熔岩偶發、Windows 優先驗證（2026-11-24）。
3. M7 note `docs/implementation-notes/2026-11-13-m7-return-home.md` 最上面兩節：「最新：T5-4 本體實作完成、兩席審查未過（2026-11-26）」與「最新：T5-4 進行中，暫停等使用者裁示（2026-11-25）」（舊節，標題沿用不改，以免其他文件的引用失效；使用者裁示原文、CI 45569149943 的現象都在這節）。
4. plan `docs/superpowers/plans/2026-11-13-m7-return-home.md`：Task 5（:299–:331）、:617–:642 的 T5 切片表，以及檔尾「T5-3 的追加」「T5-4 的追加」兩小節。
5. binding spec `docs/superpowers/specs/2026-11-13-m7-return-home-design.md`（plan 第 8 行有 pin）。
6. S 的 `t5-4-design.md`（核可的設計稿）、`task-5-4-brief.md`、`t5-4-report.md`（implementer 三次交回，最新是「本體（恢復後；第三次交回）」節）、`t5-4-review-spec.md`、`t5-4-review-quality.md`。
7. `S/STATUS.md` 最上面幾段（controller 的短期狀態，新的在上）。
8. `scripts/verification/README.md` 的 M7 段。

## 2. Git 狀態（交接時）

- 本機 HEAD：本檔所在的交接 docs commit，接在 `25e5038` 之後；之前四筆都只在本機：
  - `46a4683`：T5-4 RED（刻意失敗的測試＋no-op hook）。
  - `7182641`：2026-11-25 暫停紀錄 docs。
  - `5be0a35`：T5-4 GREEN（6 檔，+449／−26）。
  - `25e5038`：CI 第三段 Select（`build.yml`、`README.md`、`m7-inventory.json`）。
- origin feature：`238ade6`（T5-4 Step 0）。origin main：`2255027`；M7 沒有合併 main、沒有 tag。
- **為什麼沒有 push**：使用者所選選項的說明原文「對應的 commit 要排在 GREEN 之後一起 push（推 RED 會讓 CI 變紅）」，指 CI 卡住自動快照的腳本 commit 要和 GREEN 一起推；GREEN 又有審查 Important 待修。controller 決定（不是使用者裁示）：fix round 與快照腳本 commit 完成後，連同這些本機 commit 一起正常 push（持續 push 授權適用），不 force push。
- 交接時工作樹 tracked 乾淨，沒有 live Java（Gradle daemon 除外）。實際狀態以 `git log`、`git status`、`git ls-remote` 為準。

## 3. T5-4 現況

### 3.1 已完成、已驗證（implementer 自報，controller 抽核相符）

- RED：root `t5-4-red-pin-303080cd`，三案具名失敗。
- 走廊死亡案：依使用者選「恢復，死亡案改用真重生（建議）」，改用原生 `player.networkHandler.onClientStatus(new ClientStatusC2SPacket(PERFORM_RESPAWN))`，再用 `ConnectedGameTestPlayer.confirmTeleport()` 回覆傳送確認；targeted 一次 PASS（`t5-4-death-real-respawn-ca0a1669`）。
- 第三段：Windows 段案數 Main 36／Corridor 37／Select 19；Ubuntu 35／13／6。`M7_CI_SEGMENT_SET` 守門負控 `t5-4-segset-guard-6fa4c05a`（拿掉段、改順序、加段、改名都被擋）。
- mutation 11 項全部 KILLED（`t5-4-body-mutations.log`、`t5-4-mut-b-*`；拋棄式 clone `.superpowers/t54x`，逐位元還原）。
  - 更正 controller 先前的誤述：b-dead-skip 不是「第一輪存活」。第一輪 11 項都因驅動 `-Segment` 缺 Select、在參數綁定被拒，沒有套用變異；b-dead-skip 唯一一次真跑就被殺。
- focused 兩輪（`t5-4-focus-1-c9a32609`、`t5-4-focus-2-1ad4d955`）：JUnit 五類兩輪 PASS；三段 `M7_VERIFIED PASS`、各段 SelfTest 28 例符合預期。本機秒數 Main 155／149、Corridor 114／101、Select 79／76。
- default 子集：default 23/23、normal 3/3（`t5-4-default-subset-9edef930`）。同 root 的 m5「FAIL cases=60 seconds=23」是驅動錯誤：把屬 default run 的 batch `m5_collapse_source_live` 排進 M5 run，伺服器啟動即以「指定 batch 子集不符」失敗、0 案執行，60 是 11-24 的舊 XML（controller 核 `execution/stdout.log:76–77` 與 `gates.ps1:325` 相符）。之後 SOURCE fast path 在正確的 default run 補跑 1/1 PASS（`t5-4-default-subset-9a0464c0`）。
- M6GameTest 35/35（`t5-4-m6gametest-b66d1fa1`）。
- ci-rule-check 的 M7 fixture 正例：第一次（`t5-4-ci-fixture-2aeb830f`）卡住約 12.8 小時，使用者原話「可以，你結束那兩個程序，繼續下一步」後 controller 結束，原判「未完成」。controller 不改 SDD 腳本，以 `t5-4-ci-fixture-rerun.ps1`（`-NonInteractive`、stdin 導向空檔、900 秒上限）重跑一次 PASS：`t5-4-ci-fixture-rerun-b5e9ca82`，49 秒，`M7_VERIFIED PASS platform=Windows segment=Main junit=59 gametest=36 HEAD=25e5038…`，輸入 hash 不變。卡住原因「子 pwsh 等繼承的 stdin」仍是推論。兩席審查開始時還沒有這個結果，所以審查把它列為未完成（spec M-9、quality M-12），新對話可在 fix round 簡報裡註明已補。

### 3.2 兩席審查（都沒過）

- 規格：❌ C0／I3／M9，`S/t5-4-review-spec.md`（SHA-256 `9E5C9425BA6F24B383F0DDBF2019B59146C169340ED021D83315BC7960BE8B0E`）。production 讀碼與 spec、設計稿一致；三項使用者裁示有落實；第三段逐段正確；沒有越界到 T5-5、T8。不過的原因在驗收覆蓋：
  - I-1：「原玩家實體被替換」沒有測試（plan :88 焦點 2、V05、plan :325 的 replaced；plan :329「忽略原 entity」變異沒有具名殺手）。報告說不可達，但只是推論。審查意見：若判定不可達，等於縮小 V05 驗收，要交使用者裁示。
  - I-2：多項延遲期間的變更沒有測試，報告也沒揭露：延後期間離線、離開 bbox（stale 案擋下的是互動距離）、延後期間取消或 B 斷電／開門（只有 FULL 時重驗擋得住）、SELECT_CHECK 200 tick 逾時、A 停用之後點門；設計稿第 12 案（選定後一人離線、JOIN 後返還）被拿掉。死亡案的票從未開閘，「死亡者不得被選定」斷言恆真。
  - I-3：選定列「清 lease 前先 checkpoint」沒有會失敗的測試；拿掉 `tickHomeMeasuredRelease` 的 save，13 案仍全過；checkpoint 失敗後退避的分支沒執行過。
  - Minor 9 項見審查檔。其中 M-3 要讓使用者知道：返還引擎「跳過死亡中玩家」是 production 改動，使用者所選的走廊死亡選項寫「只補測試」；審查判定在裁示意圖內、不需新裁示，但 tracked 文件要寫明。controller 已在交接回覆中告知使用者。
- 品質：Changes requested C0／I2／M12，`S/t5-4-review-quality.md`（SHA-256 `E2832897506B3AC26241660EE1D9FD1AA43453EDECA5080A6610BC79D7F6C0FA`）：
  - I-1：「離線」情境完全沒有測試（與規格 I-2 重疊），補設計稿第 12 案 `offline_selected_participant_returns_after_join`，並在 stale 案補「延遲 FULL 期間離線則零 selection」。
  - I-2（production 活性 bug，讀碼已驗證、序列未實跑）：`tickHomeMeasuredRelease` 的 `ROLL_FORWARD_PENDING` 分支，同一 tick 內 SSM 的重試 flush 確認寫入後，HomeClosure 看到 cleaned＋停止原因就結案移除列；下一 tick `settleConfirmedHomeMeasuredRelease` 因 flushed 列已不在而不動，`homeDurable` 每 tick 丟例外、`failHome(Space)` 每 tick 記 ERROR；SSM runtime 因 `hasReservation` 仍真而不釋放，B 來源票留著，艙永久 BUSY，要重啟才解除。觸發條件：A 停用＋一次寫出失敗。修法二擇一：CPM 的 settle 補「列已被終局 owner 移除」；或 HomeClosure 結案要求走廊已原生釋放（會改 T4 的檔，要先確認授權）。另補注入案例、ERROR 去重。
  - Minor 12 項見審查檔。要特別注意：M-8 走廊掉落物其實會被返還引擎送回 B，與 plan、報告、死亡案 Javadoc 寫的「留在走廊或清理時消失」相反，T8 發行說明前要更正；M-2 refresh 變異的殺法描述要更正（真殺，但 `HOME_CANDIDATE_PUBLISHED` 在現架構不可能失敗）；M-3 兩項未跑的 mutation 應記為等價變異並改理由。

## 4. 新對話的第一件工作：T5-4 fix round

1. 核對：HEAD、`git status --porcelain`、origin feature 仍 `238ade6`、無 live Java；讀兩份審查檔，對照 `t5-4-report.md`。
2. 派一位新的 fix implementer（本對話的 implementer 與審查席無法跨對話續用）：輸入兩份審查檔與設計稿，範圍＝兩席所有 Important（規格 I-1～I-3、品質 I-1、I-2），Minor 逐項判定修或交後續並記錄。允許檔案以 plan :634 的 T5-4 清單與 `task-5-4-brief.md` 為準；品質 I-2 的第二種修法會改 T4 的檔，超出清單，選它之前要先問使用者。
3. 要先問使用者的事：
   - 規格 I-1：若 implementer 找不到觸發「原玩家實體被替換」的方法，要以不可達結案，就是縮小 V05 驗收，交使用者裁示（先寫好可行性證據與選項）。
   - 品質 I-2 若需要改 T4 的檔。
   - 任何時限或容許值的放寬。
4. 門檻（production 改動，品質 I-2 一定會改 production）：具名 RED（在 `25e5038` 上取得）→ GREEN → 新增或受影響的 mutation 具名殺掉並逐位元還原（在拋棄式 clone 做）→ focused 兩輪（JUnit 五類＋M7 三段＋各段 SelfTest）→ default 子集（M4 候選門、SOURCE fast path、M2 corridor；驅動要讀本次寫出的 XML，batch 要排在它所屬的 run）→ M6GameTest。完整 run-all 仍留到 T5-5 之後一次。
5. 複審：新開兩席（規格、品質），把原審查檔給它們，逐項核 ADDRESSED，並審 fix 的增量；C／I 清零才往下。
6. Select 段新增案例後，留意本機秒數（目前 79 秒）與 CI 的 U1：任一段 Windows CI 步驟超過約 450 秒先報使用者。

## 5. 之後的順序

1. CI 卡住自動快照（使用者選「讓 CI 卡住時自動留快照（建議）」，原文 `S/user-t5-4-resume-ruling-20261125.md` 第 2 題）：派工簡報已寫好 `S/task-ci-idle-jstack-brief.md`（SHA-256 `39454EB842B2AD9F43B343E3B33696D985C1CA0197BF9BBC39D5303F59B3FEE4`），只改 `gates.ps1` 與 README，B+C，獨立 commit。fix round 結束後再派（同一工作樹不並行）。
2. 全部本機 commit 一起正常 push，讀回遠端；等整個 run `completed`；逐段核 Windows job 與 U1（三段各自）。Ubuntu 只照錄一行。
3. T5-4 結案 docs（一席審查），至少要收：
   - Step 0 審查 M-6 的過時數字（README :905–913 段案數、plan :676、plan :765）；
   - CI 45569149943 的逐筆紀錄（原判 FAIL 保留、沒有 rerun、本機 3 次未重現）；
   - 品質 M-8（掉落物送回 B）與規格 M-7（實際重生在 `minecraft:overworld`；停在死亡畫面不重生時艙會一直被占用）的已知行為更正，交 T8；
   - 規格 M-3（跳過死亡中玩家是 production 改動）；
   - fixture 正例卡住與重跑結果、b-dead-skip 的更正、三次本體工具錯誤。
4. T5-5（開服接手／attach、原生列、完整 run-all；T5-5 簡報要逐檔授權 plan 檔尾列的腳本與 mixin）→ T6 → T7 → T8。
5. main 合併、tag `m7`、人工代操作、worktree 退役各自另問使用者。

## 6. 使用者裁示與常駐規則（binding）

原文都在 M7 note、AGENTS 與 `S/user-*.md`；轉述不加引號。

- 連續推進：審查 C／I 清零就直接往下做（使用者選「審查清零就直接往下做（建議）」）。
- T5-4 設計三題（`S/user-t5-4-design-ruling-20261125.md`）：「新走廊建造中不受理（建議）」、「加第三段（建議）」、「照原版，記為已知行為（建議）」。
- 走廊死亡案：「允許一次受限診斷（建議）」（FAIL）→「恢復，死亡案改用真重生（建議）」（PASS）；死亡案計數從真重生修法起重算。
- CI 卡住：「本機重現調查（建議）」（3 次未重現）→「讓 CI 卡住時自動留快照（建議）」。
- AccessDenied（AGENTS 常駐段，2026-11-25 擴充）：唯一起點是真實 AccessDenied、其餘失敗都能在 log 追到它時，每個 code pin 一次重跑，完整回歸與 focused 共用；第二次即停；追不到就停下問。docs-only commit 不重置次數；`m7-t4p2-native-fault` 路徑的注入不算。
- M5 熔岩偶發：只限原簽章，不重跑、不阻擋，結案 docs 逐筆列出。
- Windows 優先：Ubuntu CI 照跑不擋、只照錄一行、不調查不修；止損只計 Windows。
- 時限：M7 Invoke-Gradle 總時限 600 秒、idle 90 秒是使用者裁示，不自行改；U1 逐段約 450 秒回報線。

## 7. 工作方式與門檻

- Subagent-Driven Development：一個 implementer 完成一個 task 或 fix round，之後必有獨立 reviewer；fix 後由新審查席拿原審查檔逐項複審。
- production：具名 RED → GREEN → mutation（具名殺、逐位元還原、記 SHA-256）→ focused 兩輪 → 同 SHA CI；完整 run-all 等 T5-5。
- 只改測試、腳本、文件（B+C）：一席審查、定向驗證、CI。
- 會暫改 tracked 檔的負控一律在拋棄式 clone 做（`S` 外的 `.superpowers/t54x` 已有一份 `25e5038` 的 clone，用前核 HEAD 與 porcelain）。
- CI：只用 `gh ... --jq` 輪詢 `status==completed`（本機沒有獨立 `jq`）；`gh` 一律 `-R Ben513242/QuantumChamber`；FAIL 保留原判、不 rerun；artifact 下載到 S 下新的空目錄、只讀；解析用 `python -I`＋glob（路徑有空格）。

## 8. 止損與計數（只計 Windows，交接時）

- 既有冷 B 案、門故障案、與本段無關的既有 fixture：任何非預期失敗即停下問使用者。目前 0。
- T5 新案：同一案第 3 次非預期失敗才停。
  - `corridor_death_returns_to_exact_b_with_returned`：從真重生修法起重算，0。
  - `collapsing_row_uses_the_same_return_engine`：1（dev1，選定列結案移除後 runtime 沒釋放，已修）。
  - 其餘新案：0。
- 同一工具問題連續第 3 次非預期失敗即停。T5-4 本體有 3 次工具錯誤，各是不同問題（守門負控腳本解碼；mutation 驅動缺 Select；default 子集 batch 排錯），原 root 與原檔保留；另有 fixture 正例卡住一次（未完成）。
- 真實 AccessDenied：0。`46a4683`、`5be0a35`、`25e5038` 的額度都未用。
- U1：Windows CI 最新逐段是 `971bbb8` Main 337 秒、Corridor 351 秒；`238ade6` Main 378 秒、Corridor 卡住；Select 段還沒上過 CI。

## 9. 已知工具陷阱（本輪新增）

- 從 pwsh 啟動子 pwsh 跑 `ci-rule-check.ps1` 時，不加 `-NonInteractive`、也不把 stdin 導向檔案，曾卡住 12.8 小時；改用 `Start-Process -RedirectStandardInput <空檔>`＋`WaitForExit(上限)` 後 49 秒 PASS。
- 驅動的 `-Segment` 參數集要跟著段數更新（第三段 Select）；default 子集的 batch 要排進它的 class 所屬的 run（`gates.ps1:325` 的 `$M5RunClasses`）。
- auto mode 的權限分類器會擋 `Stop-Process` 結束不是本次啟動的程序；不要繞過，交使用者決定。
- 既有陷阱見 `docs/handoffs/2026-11-19-m7-codex-t5-handoff.md` 第 8 節（`sed -i` 轉 LF、PowerShell 變數不分大小寫、`gh api` 不吃 `-R` 等）。

## 10. 授權與邊界

- 正常 feature push 已持續授權；不 force push、不建 PR、不建 LICENSE。
- `main` 只能 fast-forward；main 合併、tag、人工代操作、worktree 退役各自另問使用者。
- 不用 `reset --hard`、`checkout --`、`clean`、`add -A`、bare `git stash`、`sed -i`；逐檔 stage。

## 11. 交回紀錄

- 2026-11-26（Claude 舊對話 → 新對話）：本檔建立，與 AGENTS 入口、M7 note 新首節、plan 檔尾補記、舊交接檔交回紀錄同一筆交接 docs commit（接在 `25e5038` 之後），只在本機、不 push（理由見第 2 節）。本對話的 scratchpad 已複製到 `S/scratchpad-session-aa0d7b1f-20261126/`，逐檔 SHA-256 見 `S/scratchpad-session-aa0d7b1f-20261126-MANIFEST-sha256.txt`。舊對話之後不再實作。
- （新對話接手後在此追加：日期、HEAD、完成與未完成項目、待裁示事項。）
