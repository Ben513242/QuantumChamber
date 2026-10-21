# 交接：M5 收尾到 M6（Claude → Codex，2026-10-21）

本檔是 M5 收尾與 M6 的接手入口，由 `AGENTS.md` 的「目前接手入口」指向。寫作時的狀態以下方「目前狀態」的 commit 為準；之後的實際狀態一律以 `git`、遠端 ref 與 GitHub Actions 為準。

接手者（Codex）在每一次停下、交回，或換對話之前，都要更新本檔末尾的「交回紀錄」。

## 1. 目前狀態（2026-10-21）

- Worktree：`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m5-collapse`，branch `feature/m5-collapse`。
- HEAD 是本檔所在的 docs commit，它的前一個是 `3e97dc9`（`docs: record F6-3c-2 in the M5 note`）。工作樹乾淨。
- 遠端與 tag：
  - `origin/feature/m5-collapse` 仍是 `30666c4`（2026-10-09 試玩用的 push）。之後的 199 個以上 commit **都還沒 push**。
  - `main` 與 `origin/main` 都是 `a7e1be2`。
  - 只有 tag `m1-m4`，`m5` 還沒建立。
- 最新的完整回歸是 `run-all-cbf51c5a8afc4506a4a162a46f876e1c`（`3e97dc9`），`END ALL exit=0`：
  `default=179/179 windows=89 legacy=1/1 m5=60/60 perTest=56+1 junit=666 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=432entries/385classes sources=226 testmodHits=0 mainOnly=PASS m3=4+5 recovery=24 m5collapse=69 ci=win:PASS,ubuntu:PASS`。
  Full WARN／ERROR 55／31，其中多出的 1 則 WARN 是新 default test 預期的受控提交失敗。M5 是 38／10，legacy 是 0／0。
- 停點：F6-3c-2 的實作已完成（`48c5e4d..3e97dc9`），但**還沒做 spec review 與 quality review**。這是使用者 2026-10-16 的指示：「F6-3c-2 implementer這個代理跑完就先停」。

## 2. 先讀

1. `AGENTS.md`：硬性規則都在這裡，本檔不重複。
2. spec（binding）`docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md`（v5.10），以及 plan `docs/superpowers/plans/2026-10-01-m5-collapse-passage.md`：Task F6 的 F6-3、Task F7、「Milestone 收尾」。
3. note `docs/implementation-notes/2026-10-01-m5-collapse-passage.md`（約 9900 行，**不要整份讀**，以 `rg -n` 定位）：
   - 末尾的「## F6-3c-2」一節：本檔交接的起點；
   - 其前的「## F6-3c-1」「## F6-3b」「## F6-3b fix round 1」「## F6-3 Step 0」各節開頭的 review 區塊，裡面有交給後續的事項。
4. SDD ledger `.superpowers/sdd/2026-10-01-m5-collapse-passage/progress.md`（gitignored）：
   - 「接手指引」的最新一段；
   - 「Rulings」：使用者的裁示；
   - 「F7 待辦彙整」：維護到 F6-2d 為止，之後的 F7 事項散在 note 各節。
   - 2026-10-15 之後的 controller 處置，在檔案末尾、「## Plan review」之前的時間序條目。
5. 交接用的檔案，放在 `.superpowers/sdd/2026-10-01-m5-collapse-passage/handoff-codex/scratchpad/`（從 Claude 的暫存區複製，已逐檔核對 SHA-256）：
   - `f63-inventory.md`：F6-3 的 32 項盤點與拆分建議；
   - `f63a-findings.md`：Ubuntu CI 失敗與 AccessDenied 的根因調查；
   - `f63a-ci/`：三次 CI run 的 artifact，屬不受信任的資料，只讀；
   - `h8rev/all/`：原版 1.21 的反編譯。note 有引用它的行號，例如 `GameTestState`、`TestServer`；
   - `f6*-prompt.md`、`*-review.md`、`*-result.md`、`*-review-block.md`：每一輪的派工、review 派工、review 結果與 note 的 review 區塊，**是 §5 工作方式的範本**。
6. `scripts/verification/README.md`：gate、probe、run-all 的用法與預期數字。

## 3. 剩下的工作（依序）

### 3.1 F6-3c-2 的 review（下一步）

- 範圍是 `48c5e4d..3e97dc9`，code 到 `e151595`。內容：
  - 把 `M5UniverseGameTests` 拆成 4 個 class，分別有 13／18／11／18 個 test；
  - 新增 `M5UniverseSupport` 與 `M5UniverseHold`；
  - fixture 的 `extraPlayer`、`crossMidline`、`collapseFixtureOwnedBy`；
  - 新的 default test `m5collapsegametests.dormant_entry_within_the_commit_backoff_is_consumed_without_a_commit`；
  - `gates.ps1` 的 `$M5RunClasses`。
- 證據在 `.superpowers/sdd/.../f6/f63c2/STATUS.md`。
- 派 spec 與 quality 兩份 review，都只讀、不跑 Gradle。可以照 `handoff-codex/scratchpad/f63c1-review.md` 的寫法，用一份派工檔、兩個角色。review 的重點：
  1. 拆檔是否為純搬移。`f63c2/b1-split/split.py` 只有兩種改動：拿掉 32 個 `private`，以及把 `{@link #x}` 改指向新的 owner。test 集合以方法名稱比對是否相同；執行順序有 4 對相鄰的 test 對調，原因是原版以 HashMap 分組 batch。
  2. `M4GameTestBoundaryProbe:29`（與 :19 的 Javadoc）超出派工允許的範圍。implementer 判斷這是 SUITE_END listener 搬家的必要結果，請判定是否成立。
  3. 中間的 commit `bb0bb15` 單獨存在時，M5 gate 會因 `gates.ps1` 的舊規則而失敗，下一個 commit `9b2ed8c` 修正。請判定這樣的 bisect 斷點能否接受。
  4. `run-all-1r` 的 Full ERROR +1 是 Yggdrasil 公鑰讀取失敗，不在已知偶發清單裡，`run-all-2` 沒有再出現。請判定它的歸屬。
  5. D 段的 RED：`mut-backoff-first` FAIL 在 `notCommittedPastTheBackoffWithoutANewEdge`；這個 test 放在 default run，判斷 M4 boundary oracle 是否不受影響。
  6. `src/main/PassageService.java:93` 的註解仍指向 `M5UniverseGameTests`，交給 F7。
- 先把 `f63c2/STATUS.md` 的 run-all-2 補進 note。controller 已在本檔的同一個 commit 補記，請核對。
- review 通過之後，在 note 的 F6-3c-2 一節寫 review 區塊，並更新 plan、AGENTS、ledger。

### 3.2 F6-3d：harness 腳本與 README

- 範圍：盤點的 #13～#24、#26～#31。#25 只評估，已裁示 probe 不進 CI（X2 選 (a)）。
- 已有的裁示（ledger 2026-10-16 22:05 一條）：
  - X3：README 補一句取證腳本的寫法，不 tracked 化；
  - X4：`classload`／`latest.log` 缺少時記在 `launcher.json`；
  - U3：以 H5 為由關閉，note 要記下 H5 之後的 run-all 都是 recovery 24。
- 全部在本機驗收，不需要 CI。

### 3.3 F6-3e：`build.yml` 的明列清單與 gate 斷言

- `build.yml` 的明列清單（盤點 #01～#03）：
  - default run 在 M5 期間新增的 32 個 test，加上 F6-3c-2 新增的 1 個，共 33 個；
  - M5 run 的 60 個 test，以帶 class 的比對明列（U1 選 (b)）。
  - 現有三個陣列都寫死 class 前綴，要改成帶 class 的比對，或另開陣列。
- gate 的新斷言（來自 F6-3b 與 fix round 1 的 review）：
  - 全部 PASS 的 M5 run，不得出現任何 `M5_UNIVERSE_HOLD`；
  - 節流的結束列（`GameTest tick 節流 結束：…`）恰一行、`waited>0`、`tps≤100.0`。不要用 `waitedMs`，它包含等待期間執行的工作。
  - SUITE_END 核對四個權威，不只 catalog。
- 驗收：`ci-rule-check.ps1` 的兩個 step 都 PASS；每一類都要有 mutation（`-Mutation drop`、`-MutationXml m5`、`-SourceMutation rename-windows`）；run-all 全綠。

### 3.4 push 與 CI 驗收（**先問使用者**）

- 建議 3.1～3.3 都完成之後，再問使用者要不要 push `feature/m5-collapse`，然後只 push 一次。
- 驗收：
  - GitHub Actions 的 Ubuntu `build` 與 Windows `windows-native` 都通過；
  - **Ubuntu 的 M5 GameTest 在同一個 SHA 至少兩次 run 都綠**，這是 F6-3b 節流的驗收；
  - 從 artifact 核對 Ubuntu 的 tps 不超過 100，以及 PREPARE 的最大 tick 數。
- 紅燈時先依 systematic debugging 找根因，再修正、重跑。

### 3.5 F7：文件

- 照 plan 的 Task F7 進行。
- 先盤點：ledger 的「F7 待辦彙整」，加上 `rg -n "F7" docs/implementation-notes/2026-10-01-m5-collapse-passage.md` 的所有命中，再對照之後各節是否已處理或改派。
- controller 已知、但還沒寫進「F7 待辦彙整」的事項：
  - 原版 `StrongholdGenerator` 的 static 可變狀態：寫成已知問題，修正延到 M6（spec §20.14）。
  - Windows 原子移動的 `AccessDeniedException`：根因最可能是外部程序（Defender／索引器）。production 會 fail closed，並依退避重試。是否在 production 加短暫重試要由使用者裁示；controller 的建議是 M5 不改，列為已知限制。
  - collapse probe 不進 CI，只在本機 run-all 執行。
  - F1a 的 smoke driver 與核對腳本沒有 tracked，記為不可重現（U5）。
  - `src/main/PassageService.java:93` 的註解。
  - W12 已驗證退避加倍，範圍是 `INITIAL_TICKS＋6 < d ≤ 2×INITIAL_TICKS＋6`。
  - spec:252「attach 只為 COLLAPSING 建立來源 ticket」的措辭（F6-2e-1 spec review）。
  - H8、H9 與其他各輪 quality review 交給 F7 的註解與 Javadoc 修正。
  - Track 依階段拆成小 holder：延到 M5 之後（F5-1 R-m3）。
  - README 的預期數字：default 179、m5collapse 69 等。另外把已知偶發清單（§6）寫進 README。
- 文件 review 由獨立 reviewer 進行，Critical／Important 為 0。

### 3.6 Milestone 收尾（plan「Milestone 收尾」，順序固定）

1. automated gates：`run-all.ps1` 全綠。
2. required manual smoke（spec §18）：
   - 清單寫成白話，項目、做法、預期寫在一起，見 F7 的 `docs/manual-acceptance/2026-10-01-m5-manual-smoke.md`。
   - 上一次（M1–M4）由 Claude 經使用者授權代操作遊戲：鍵盤滑鼠自動輸入、截圖、唯讀指令。驅動腳本在 repo 外的 `C:\Users\Ben\Documents\QC-acceptance-evidence\tools\mc-drive.ps1`，證據放在 `C:\Users\Ben\Documents\QC-acceptance-evidence\<日期>\`。
   - 若不能代操作，就把白話清單交給使用者執行，並記錄結果。
3. task 與 whole-branch review（範圍 `a7e1be2..HEAD`）：Critical／Important 為 0。
4. docs truth：不提前寫「已合併 main」「已 push」「人工驗收已通過」。
5. push `feature/m5-collapse`（**使用者明確指示**）。
6. GitHub Actions 雙平台通過。
7. `git merge --ff-only` 合併 `main`，並建立 annotated tag `m5`（**使用者明確指示**）。
8. 依 worktree 退役流程做外部備份：建立 SHA-256 manifest、逐檔讀回驗證，之後才刪除 worktree（**使用者明確指示**）。
   - 備份工具在 `C:\Users\Ben\Documents\QC-acceptance-evidence\tools\backup-worktree.ps1`。
   - M1–M4 的前例在 `C:\Users\Ben\Documents\QC-m1-m4-worktree-backup-20261001\`。

### 3.7 M6

- `m5` 合併並打 tag 之後，才另開新的 worktree 與 branch（AGENTS 的分支策略）。
- 範圍依 `docs/quantum_superposition_chamber_design.md` 的「### M6 — Universe generation diversity + full dimension families」：
  - random seed、same-seed fresh world、generation-profile mutation；
  - profile registry／API；
  - 每個 Universe family 的 Nether／End 生成與持久化對齊；
  - `DimensionFamilyRoutingService`、`RespawnRoutingService`；
  - same-`DimensionRole` 的 Chamber projection／transfer tests。
- 使用者已指定要併入 M6 的事項：
  - 原版 `StrongholdGenerator`、Nether fortress、End city 的 static 狀態在兩個世界同時生成時的 race（spec §20.14）；
  - 試玩回饋希望看到不同的宇宙生成，M6 建議先做 random seed。
- 流程：
  1. 先與使用者確認範圍與優先序；
  2. 寫 M6 spec，經獨立 spec review 到 Critical／Important 為 0；
  3. 寫 plan，經 plan review；
  4. 依 §5 的方式逐 task 實作。
  - M7（literal branch／snapshot）有獨立的設計 gate，不併入 M6。

## 4. 等待使用者裁示的事項

- 每一次 push、合併 `main`、打 tag、刪除 worktree。
- AccessDenied 是否在 production 加短暫重試（`f63a-findings.md` 的 B4）。另有可選的實驗 B3：把 `run/` 排除在 Defender 與索引之外，用來確認根因；這是使用者的機器設定。
- M6 的範圍與優先序。
- 只問真正需要使用者決定的事。其餘依文件與合理預設處理，並在 ledger 與回報中說明。

## 5. 工作方式（Claude 的做法，請比照）

- **一個 implementer 做一個 task 或 fix round，之後一定有獨立的 spec review 與 code quality review。** Critical／Important 為 0 才往下。
  - Minor 不開 fix round，排進下一輪的 Step 0 或 A 段，不順手修。
  - 不能另開獨立 agent 時，review 要在**新的、乾淨的 context**（例如另一個 Codex session）裡，以 review 派工檔進行。不要在寫程式的同一個 context 自評通過。
- **派工檔**明列以下內容，範本見 `handoff-codex/scratchpad/f63c2-prompt.md`：
  - 開工時的 HEAD 與 porcelain 檢查；
  - 允許與不得修改的路徑；
  - 讀取範圍（限制 context）；
  - 硬性規則；
  - 已知偶發；
  - 驗證方式；
  - 最終回報的格式；
  - context 用到約 60 萬時寫 `STATUS.md` 之後停下，由接力者以 `STATUS.md` 為主要依據接手。
- **證據紀律**：
  - 每個行為改變都要有 RED → GREEN。暫時的改動與 mutation 以 adapter 套用與還原，再以 `git hash-object` 核對 blob。
  - GREEN 在同一個乾淨的 code HEAD 連跑兩次。probe 的具名斷言要與基準 runId 比對，既有的全在、全成立，新增與改名另外列出。
  - 完整回歸：所有 commit 之後跑 `run-all.ps1`，`END ALL exit=0`；note 的 commit 之後再跑一次。
  - 與基準逐項比較：
    - SUMMARY；
    - testcase 與 JUnit 的集合；
    - release 的 SHA；
    - sources 的內容，SHA 不同時逐項核對是否只差 zip 中繼資料；
    - Full／legacy／M5 的 WARN／ERROR，排序後做 diff；
    - pin 計數；
    - worldgen 例外。
  - 每一個差異都要有歸屬。
  - 失敗的 root 一律保留，修正後以新的 label 或 nonce 重跑；不在失敗的 root 上重跑改判。
- **controller 的核對**：不直接採信 agent 的回報。commit 之前，以唯讀的方式核對 HEAD、porcelain、`END ALL`、log 與 artifact。
- **文件**：
  - 每一輪結束，在 note 該節開頭的「行號」條目之後寫 review 區塊：兩份 review 的結論、Minor、處置，以及完成的 range 與最終 run-all。
  - 同時更新 plan、`AGENTS.md`、ledger。
  - commit 用 `git commit -- <paths>`，訊息結尾附上 `Co-Authored-By`（依你的工具慣例）。
  - 行尾：note、AGENTS、ledger 在工作樹是 CRLF（`core.autocrlf=true`），plan 是 LF。寫入之後要確認沒有混用。
- **Gradle、gate、probe**：
  - 同一時間只跑一個；
  - 有 gate 正在跑時，不殺任何 Gradle daemon；
  - 會 move／restore `run/` 世界的腳本，跑到一半不可強制中斷；
  - 以重導輸出呼叫 `run-all.ps1` 時，外層要等 daemon 結束才會返回，判斷完成看 `END ALL` 那一行；
  - 取證腳本不要用 `Select-Object -First N` 截斷 pipeline；
  - 含空白的路徑要加引號。
- **電腦被關機時**：先唯讀核對以下各項，再從中斷處續行，中斷的 root 記為不採計：
  - HEAD、porcelain；
  - java 程序；
  - 中斷的 root 裡有沒有 `session.lock`；
  - `run/` 底下有沒有停在 move 中的世界。

## 6. 已知偶發（root 保留，以新的 root 重跑，在 note 歸屬）

- **Windows 原子移動的 `AccessDeniedException`**：
  - 遇過的檔：journal、catalog、allocation ledger、discovery ledger；
  - 可能讓 probe 的 halt step 逾時，也可能在 default run 的 `m2_task4_*` 多出 WARN。
- **boundary oracle 挑到同 PID 的舊 receipt**：盤點 #13 會修。
- **H2 的 Full ERROR +2～+3**：在 `m5_h2_checkpoint_scope`，被注入失敗的 chunk 多出 store／save 訊息。
- **`m2_task4_disconnect_io` 的 WARN +1**：「session 協調失敗，保留返還保護」。
- **default run 某個 test 不收尾**：會使 `m2_pin_capacity` 的 entity pin 溢出 → R2 M7 連坐 → gate 逾時並停在「root 有 live owner」。處置見 note 的 H9 一節。
- **gate 跨過午夜**：latest.log 會被輪替，比較時要合併兩段。
- **尚未歸屬**：Yggdrasil 公鑰讀取失敗（F6-3c-2 的 `run-all-1r`，Full ERROR +1），交 F6-3c-2 review 判定。
- **已查明根因**：M5 GameTest 在 PREPARE 預載之後的有界等待逾時，是原版 `TestServer` 不依 20 TPS 節流所致，由 F6-3b 修正。Ubuntu 上的效果要等 CI 驗收。

## 7. 交回紀錄（Codex 每次停下或交回時更新）

- （尚無）
