# 交接：M5 收尾到 M6（Claude → Codex，2026-10-21）

本檔是 M5 收尾與 M6 的接手入口，由 `AGENTS.md` 的「目前接手入口」指向。寫作時的狀態以下方「目前狀態」的 commit 為準；之後的實際狀態一律以 `git`、遠端 ref 與 GitHub Actions 為準。

接手者（Codex）在每一次停下、交回，或換對話之前，都要更新本檔末尾的「交回紀錄」。

## 1. 初次交接快照（2026-10-21，44f6cb9；最新狀態見 §7）

- Worktree：`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m5-collapse`，branch `feature/m5-collapse`。
- 初次交接 HEAD 為 `44f6cb9ca54545091e6d8296bee478370c574b6c`，前一個是 `3e97dc9`（`docs: record F6-3c-2 in the M5 note`）。當時工作樹乾淨；不要把這份歷史快照當成後續接手 HEAD，最新方式見 §7。
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

### 3.1 F6-3c-2 的 review（已完成；原派工清單保留）

2026-10-21 已完成首輪 review、I-1 的 fix round 1 補證，以及兩份全新獨立複審（Critical／Important 0）。下一步是 §3.2；本節以下保留原始範圍與判定題，結果見 note Review 區塊與 §7。

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

### 3.2 F6-3d：harness 腳本與 README（三段已完成；下一步 F6-3e）

- 2026-10-21 controller 分三段，理由是隔離 Java runner 與程序／世界管理的變更並限制 context；原 scope 與裁示不變。
- **d-1 已完成**：#26～29 與 M-1，`4527f26..c526b295`；兩份獨立 review C/I/M皆0，兩次完整回歸 exit0，詳 §7 最新紀錄與 note。
- **d-2 已完成**（2026-10-22）：#15～21，共用owned-phase launcher／timeout／encoding／diagnostics與X4；range `498d5e4..4e3fa6c`，首輪I-1由fix1補有效RED後經全新spec／quality複審關閉，C/I/M皆0。
- **d-3 已完成**（2026-10-22）：#13/#14/#22～24/#30/#31及既定#25/X2(a)/X3/U3；range `2243b07..a829bed`，兩份全新獨立review各C0/I0/M1，兩focused與兩完整run-all通過。下一步F6-3e，先處理下節兩項Minor。下列保留原完整範圍，實際完成以本routing與§7區分。

- Step 0：F6-3c-2 spec Minor M-1 已由 d-1 處理：32 個跨類別可見成員中，30 個移除 private；161 個成員數保持。

- 範圍：盤點的 #13～#24、#26～#31。#25 只評估，已裁示 probe 不進 CI（X2 選 (a)）。
- 已有的裁示（ledger 2026-10-16 22:05 一條）：
  - X3：README 補一句取證腳本的寫法，不 tracked 化；
  - X4：`classload`／`latest.log` 缺少時記在 `launcher.json`；
  - U3：以 H5 為由關閉，note 要記下 H5 之後的 run-all 都是 recovery 24。
- 全部在本機驗收，不需要 CI。

### 3.3 F6-3e：`build.yml` 的明列清單與 gate 斷言

- **Step 0（d-3 review Minor）**：S-M1補phaseTable Count及區分大小寫的逐項比較；Q-M1對預期Minecraft成功invocation要求非空當次log，缺失／全舊／空檔補正式caller負例並明列診斷，啟動前失敗與純JUnit可標不適用。兩者不改production，Q-M1不改X4 probe輔助log裁示；本輪不回頭開d-3 fix round。

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
  - Windows 原子移動的 `AccessDeniedException`：根因最可能是外部程序（Defender／索引器）。production 會 fail closed，並依退避重試。使用者已於 2026-10-21 裁示：M5 維持現況，列為已知限制，不加入 production 短暫重試；外部程序的實際持有者仍未確認。
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
- AccessDenied 的 B4 **已裁示（2026-10-21）**：M5 維持現有 fail-closed 與退避、列已知限制，不加短暫重試。B3 機器設定實驗未授權、未執行；若日後需要才另請使用者決定。
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
- **已查明根因**：M5 GameTest 在 PREPARE 預載之後的有界等待逾時，是原版 `TestServer` 不依 20 TPS 節流所致，由 F6-3b 修正。Ubuntu 上的效果要等 CI 驗收。

### 已歸屬但未列為已知偶發

- Yggdrasil 公鑰讀取失敗（F6-3c-2 的 `run-all-1r`，Full ERROR +1）：兩份首輪 review 已讀原 stack，cause 是 `UnknownHostException: api.minecraftservices.com`。歸屬該次外部名稱解析失敗；未證實發生頻率或外部根因，不因下一次未出現就自動視為已知偶發。

## 7. 交回紀錄（Codex 每次停下或交回時更新）

### 2026-10-21：F6-3c-2 review 與 fix round 1 完成

- **HEAD 與 porcelain（此筆歷史紀錄）**：完整回歸與兩份複審釘選 `0ed3e62180d34371bdf6a6aad888defa8b27dcef`；其後治理文件同步 commit 為 `4527f265d0a01bfd8f42008c9f38f4b3f96f42f4`，當時提交後 porcelain 空。`0ed3e62..4527f26` 只有 AGENTS、handoff、note、plan 四份文件。此筆已由後續交回紀錄接續，不再以它的舊動態 git log 指引判定最新 HEAD。
- **實際 Git 狀態（2026-10-21 即時唯讀核對）**：origin feature=`30666c42c35e82c8938f13902d06af97f050e753`；本機 main 與 origin main=`a7e1be2dab7424bf067bda378c6edd7518bbaf7f`；本機與遠端沒有 `m5` tag。本輪沒有 push、merge、tag 或刪除 worktree。
- **完成範圍**：原 F6-3c-2 `48c5e4d..3e97dc9` 的兩份 review；fix round 1 補證 `44f6cb9..0ed3e62`（只有 note 11 行，沒有正式 code／test 變更）。controller 再以本 docs-only commit 同步交接狀態。F6-3d／F6-3e／F7／milestone 收尾／M6 都尚未開始。
- **Review**：首輪 spec ❌ C0/I1/M1、quality Not approved C0/I1/M0，各為獨立 gpt-6-sol context。共同 I-1 是 pending after-pass 守門缺定向 RED／GREEN。補證後，由兩個全新獨立 gpt-6.1-sol context 複審：spec ✅、quality Approved，C0/I0/新增M0；I-1 關閉。reviewer 皆唯讀且沒有跑 Gradle；派工與結果在 SDD `f6/f63c2/codex-*-20261021.md`。
- **接力**：原補證 implementer 因模型容量限制停止回報，既有 runtime 沒有中斷；新 context 只補既有 artifact 比對與 STATUS，未新跑或終止 runtime。這不是失敗 runtime root，不重跑改判。
- **最終完整回歸**：`.superpowers/verification/run-all-a8841a60e8b84bea8887c01992e4ca1f`，乾淨 `0ed3e62`，2026-10-21 15:58:09（台灣）`END ALL exit=0`。前一次 `.superpowers/verification/run-all-2fa881c45802414fac0674ddfc37da6f` 在乾淨 `44f6cb9` 也全綠。
- **SUMMARY**：default179/179 windows89 legacy1/1 m5=60/60 perTest56+1 junit666 failures0 errors0 skipped2 windowsCheckpoint17 release432entries/385classes sources226 testmodHits0 mainOnlyPASS m3=4+5 recovery24 m5collapse69（halt31）ci=win:PASS,ubuntu:PASS。這是本機 CI 規則，不代表 GitHub runner 驗收。
- **差異歸屬**：案例集合、release SHA、sources226項內容相同（只差 zip metadata）；Full55/31、legacy0/0、M538/10，動態欄位歸屬後無剩餘差異；pin4/9/4、worldgen四類0、具名斷言與steps RESULT=OK。兩輪 w8late 的兩鍵順序交換不改名／真值／steps；catalog digest 隨UUID改變，60筆增量及suite56筆收斂相同。最終M5 tps85.5、HOLD0。完整證據與原始失敗RED保留在 SDD `f6/f63c2/fix1/STATUS.md` 及其指向的roots。
- **未關閉 finding**：沒有 Critical／Important；原 spec M-1 計數文案交 F6-3d Step 0，不在本補證輪順手修。既有後續事項仍依 §3.2／§3.3／§3.5 排程。
- **已裁示**：AccessDenied 在 M5 維持既有 fail-closed／退避，列已知限制，不加 production 短暫重試。
- **仍待使用者裁示**：每一次 push、main fast-forward、建立 tag、刪除 worktree；代操作人工 smoke 的授權；M6 範圍與優先序。現在不需再次詢問 AccessDenied。
- **下一步**：先以派工檔開始 F6-3d（含 M-1 Step 0）→ F6-3e → 取得單次 push 同意後驗收 GitHub CI → F7 → manual smoke／whole-branch review／milestone 收尾 → m5 合併並打 tag 後才開始 M6。沒有跳過任一門檻。

### 2026-10-21：F6-3d-1 完成，下一步 F6-3d-2

- **HEAD 與 porcelain**：實作、最終回歸與正式review固定 `c526b2956cd660c47aa0d41ae3654007e843c15f`，feature/m5-collapse；controller核對porcelain與diff --check皆空。此筆歷史治理文件同步commit為 `498d5e44476e4d2827dcf24d3d837c0a1c758a72`，提交後porcelain空；相對c526b295只有AGENTS/handoff/note/plan四檔。後續接手改讀本節最後一筆，不再以舊動態git log指引判斷最新HEAD。
- **實際 Git 狀態**：2026-10-21 19:09唯讀核對，origin feature=`30666c42c35e82c8938f13902d06af97f050e753`；本機main/origin main=`a7e1be2dab7424bf067bda378c6edd7518bbaf7f`；本機/遠端無m5 tag。本輪未push、merge、tag或刪worktree。
- **完成 task/range**：F6-3d-1（#26～29與M-1），`4527f265..c526b295`，7 commits；code/README HEAD b003ea3。未改production/build/workflow/正式verification腳本；沒有處理d-2/d-3/F6-3e的實作。
- **Review**：全新獨立context `/root/f63d1_spec` spec ✅、`/root/f63d1_quality` Approved，兩份C0/I0/M0；先唯讀預審固定版本，第二輪END後在各自原獨立reviewer context續核才出正式結論，沒有實作context自評。派工/預審/正式結果在SDD `f6/f63d1/*review*20261021.md`；沒有新增Minor，原M-1已關閉。
- **兩次完整回歸**：SDD `f6/f63d1/runall1/run-all-a734d37c40a64806a742bef2a5fbfe1b`，18:32:07 END ALL exit0（b003ea3）；最終 `f6/f63d1/runall2/run-all-833243ca0c104b8fb6b887dd96445a7a`，19:16:42 END ALL exit0（note後的乾淨c526b295）。source/harness SHA相同；collapse IDs分別9e30735145b643468afc25b192658faf、1f62bc8f8d994298b1257d2c98c53020。
- **SUMMARY**：default179/179 windows89 legacy1/1 M560/60 perTest56+1 JUnit666 failures0 errors0 skipped2 windowsCheckpoint17 release432entries/385classes sources226 testmodHits0 mainOnlyPASS M3 4+5 recovery24 collapse69/halts31 CI本機規則win/ubuntu兩PASS；不代表GitHub runner驗收。
- **差異/證據**：案例集合與release SHA相同，sources226項內容/順序同、只差ZIP metadata。Full55/31、legacy0/0、M538/10限定動態欄位後remaining0，pin逐batch4/9/4、worldgen四類0。第一輪w8late-restart僅observed鍵順序不同，第二輪也同順序；名稱/真值/steps/schema不變。M5 tps85.9/85.3、HOLD0、suite records56/converged true/unfinished空。104個M5 evidence roots與5183證據檔manifest已凍結；controller與reviewer各自讀回核SHA/大小。沒有將此宣稱為全部runtime世界備份驗證。
- **保留與控制層紀錄**：原始RED、90秒timeout、CIM拒絕與誤標GREEN實為RED的root均保留；setup編碼/路徑及note後STATUS資料形狀錯誤均已歸屬，不列runtime偶發。後者沒有啟動新runtime，ignored工具修正後才啟第二輪，未重跑成功第一輪。最終owned runtime0，沒有停止daemon。舊stdout的Get-FileHash分享模式例外以可共享唯讀串流核SHA相符，未停止程序。
- **未關閉 finding**：本task無C/I/M；其他既有事項仍按d-2/d-3/F6-3e/F7處理。AccessDenied維持M5現況、列已知限制的使用者裁示不變。
- **等使用者裁示**：每一次push、main ff-only、tag、刪worktree；代操作manual smoke授權；M6範圍/優先序。現在沒有需要重新詢問AccessDenied的事項。
- **下一步**：F6-3d-2 → d-3 → F6-3e → 單次push同意與雙平台CI驗收 → F7 → required manual smoke/whole-branch review/里程碑收尾 → m5合併/tag後才新M6 worktree。M5尚未收尾，M6尚未開始。

### 2026-10-22：F6-3d-2（含 fix round 1）完成，下一步 F6-3d-3

- **HEAD與porcelain**：實作／補證／最終回歸及正式複審固定 `4e3fa6cd3952e2a3e9dd0f4c5ca59708375d865a`、feature/m5-collapse，controller核porcelain與diff --check空。本筆由其後的四檔治理文件同步commit加入；該commit精確HEAD以 `git log -1 --format=%H -- docs/handoffs/2026-10-21-m5-wrapup-to-m6-codex.md` 解析，提交後HEAD/porcelain另記ledger。相對4e3fa6c只應有AGENTS/handoff/note/plan，不冒稱治理docs新HEAD另跑過runtime。
- **實際Git狀態**：2026-10-22 00:41唯讀核對，origin feature=`30666c42c35e82c8938f13902d06af97f050e753`，本機main及遠端main=`a7e1be2dab7424bf067bda378c6edd7518bbaf7f`，本機／遠端無m5 tag。本輪未push、merge、tag、建PR/LICENSE或刪worktree。
- **完成task/range**：F6-3d-2 #15～21/X4，`498d5e4..4e3fa6c`共6 commits；code/README=`6406208`，原note=`399f4b4`，fix1 note=`4e3fa6c`。僅兩個probe wrapper、新common、README與note；沒有production／Java／build／workflow變更。fix1只有note提交與ignored補證。
- **Review**：首輪全新獨立spec `/root/f63d2_spec` ❌與quality `/root/f63d2_quality` Not approved，各C0/I1/M0；共同I-1為total/idle舊RED在PSScriptRoot空Path setup失敗卻被採計。fix1補有效目標RED、同乾淨HEAD各兩GREEN後，由另外兩個全新獨立context `/root/f63d2_fix1_spec` 正式spec ✅、`/root/f63d2_fix1_quality` 正式Approved，兩份C0/I0/M0，I-1 ADDRESSED。定向預審與回歸中續核均未提前通過；最後END/manifest齊備後，各自在原獨立context正式裁決。正式報告在SDD `f6/f63d2/fix1/*-review-final-20261022.md`。
- **最終完整回歸**：SDD `f6/f63d2/fix1/runall1/run-all-274351d14b764d4ba9e6cf34b31216f2`（399f4b4）及 `f6/f63d2/fix1/runall2/run-all-da52833326044b678588d05ac836a020`（note後4e3fa6c，2026-10-22 00:34:09）均精確END ALL exit0。原d2兩輪a4b41a90…／2ab81a16…亦保留，沒有因比較控制層錯誤重跑成功runtime。
- **SUMMARY**：default179/179 windows89 legacy1/1 M560/60 perTest56+1 JUnit666 failures0 errors0 skipped2 windowsCheckpoint17 release432entries/385classes sources226 testmodHits0 mainOnlyPASS M3 4+5 recovery24 collapse69/HALT31 本機CI規則win/ubuntu兩PASS；不等於GitHub CI或人工驗收。
- **差異與保留**：案例集合／release SHA同，sources226內容／順序同、僅ZIP時間metadata。第一輪Full55/31；第二輪Full跨午夜，兩piece完整串接後56/31（原latest21/5），唯一WARN+1以batch／受控拒絕stack／source／XML PASS對上§6的m2_task4_disconnect_io；保留raw added1，未歸屬0。legacy0/0、M538/10、pin逐batch4/9/4、每輪107份log四類worldgen0。只調整本輪ignored比較輸入，未改原gate/END或正式harness。
- **證據封存**：controller與兩個reviewer均直接重算9,852檔／2,027,023,673 bytes、202個phase索引與398 source/harness pins，全部符合。入口 `f6/f63d2/fix1/STATUS.md`；evidence manifest SHA=`4e9c05192cd30c5d2b95e8e4739ff6ad7b64bfe4ec3bc72b71e85187b3d2b30e`。原兩份無效timeout RED／全部舊root與manifest保留；控制層路徑、schema與分析假設失敗保留、不充作RED或runtime偶發。未重hash全部runtime世界，未宣稱備份完成。00:41只剩GradleDaemon，無owned game/probe JVM，未殺daemon。
- **未關閉finding**：本task無C/I/M；剩餘d-3、F6-3e、F7及里程碑事項按既定順序。AccessDenied依使用者裁示維持M5現況、列已知限制。
- **等使用者裁示**：每次push／main ff-only／tag／刪worktree；代操作manual smoke授權；M6範圍與優先序。當前沒有需重新詢問AccessDenied的事項。
- **下一步**：F6-3d-3 → F6-3e → 單次push同意與雙平台CI驗收 → F7 → required manual smoke／whole-branch review／里程碑收尾 → m5合併並tag後才新M6 worktree。M5尚未整體收尾，M6未開始。

### 2026-10-22：F6-3d-3 完成，下一步 F6-3e（含兩項Minor Step 0）

- **HEAD與porcelain**：實作、最終完整回歸及兩份正式review固定a829bed2f0e62c2093ac7790f62993b5f4488a97、feature/m5-collapse；controller直接核porcelain與diff --check空。本筆由其後四檔治理文件同步commit加入；該commit精確HEAD以git log -1 --format=%H -- docs/handoffs/2026-10-21-m5-wrapup-to-m6-codex.md解析，提交後狀態另記ledger與controller postcommit。相對a829bed只應有AGENTS/handoff/note/plan，不冒稱治理docs HEAD另跑過runtime。
- **push／remote／main／tag**：2026-10-22 08:09（台灣時間）git ls-remote核origin feature=30666c42c35e82c8938f13902d06af97f050e753；本機main與remote main=a7e1be2dab7424bf067bda378c6edd7518bbaf7f；本機與遠端無m5 tag。本輪無push/main合併/tag/刪worktree；當時無Java程序，未終止daemon。
- **完成task/range**：F6-3d-3 #13/#14/#22～24/#30/#31與#25/X2(a)/X3/U3，2243b07..a829bed共7 commits。code/README=e8a3cf0；只有四支verification腳本（含新增helper）、README與note，沒有production/src/build/workflow/closed wrappers正式變更。
- **獨立review**：/root/f63d3_spec正式spec ✅，C0/I0/M1；/root/f63d3_quality正式Approved，C0/I0/M1。兩個全新context均未參與實作、全程唯讀、不跑runtime；原報告f6/f63d3/spec-review-a829bed.md與quality-review-a829bed.md，hash及結論見note本節Review。
- **最終完整回歸**：SDD f6/f63d3/runall2/run-all-af814a5573d443a0bd8faa00d259f76c，原console:140精確END ALL exit=0 HEAD=a829bed2f0e62c2093ac7790f62993b5f4488a97。前一輪ef317f60c17c4f478f5cc4071ad19d0f在e8a3cf0亦END0；兩focused同乾淨e8a3cf0。
- **SUMMARY**：default179/179 windows89 legacy1/1 M560/60 perTest56+1 JUnit666 failures0 errors0 skipped2 windowsCheckpoint17 release432entries/385classes sources226 testmodHits0 mainOnlyPASS M3 4+5 recovery24 collapse69/HALT31 本機CI規則win/ubuntu兩PASS；不代表GitHub CI或人工驗收。
- **差異與封存**：Full第一輪55/31、第二輪56/31；新增WARN由原batch/stack/source/XML對到既有m2_task4_disconnect_io受控拒絕，raw added1/rawDifferenceCount1/attributed1/remaining0，原比較失敗保留、未重跑runtime。release bytes同；sources226內容/順序同，僅ZIP時間；legacy0/0、M538/10、pin4/9/4、worldgen四類0。Controller與兩reviewer各自全量readback20,370檔/3,296,851,406bytes/204原phase roots，SHA/大小全符；399 source/harness pins固定。原STATUS/manifest不回寫，新review/controller報告另存。
- **未關閉finding**：沒有Critical／Important。S-M1（spec）phaseTable串接碰撞、Q-M1（quality）當次log空選集缺失分類，均交F6-3e Step 0；本輪真69項receipt與8份非空當次log不受影響。其餘F6-3e/F7/里程碑事項照排程。
- **使用者裁示**：每次push、main --ff-only合併、tag、刪worktree仍須新的明確同意；代操作manual smoke仍需使用者授權，未授權則由使用者執行；M6範圍與優先序待m5合併/tag後確認。AccessDenied已裁示M5維持現況並列已知限制，不再列為未決。
- **下一步**：F6-3e Step 0兩Minor→default33/M5 run60帶class清單與HOLD/TPS/SUITE_END新斷言→取得單次push同意後雙平台CI（同SHA Ubuntu M5至少兩綠）→F7→required manual smoke/whole-branch review/里程碑收尾。M5尚未整體收尾，M6未開始。
