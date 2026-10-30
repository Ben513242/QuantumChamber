# QuantumChamber M6：Claude 交回 Codex 的接續提示詞（2026-10-30）

請接續 QuantumChamber M6，全程使用繁體中文（台灣）精簡回報；先核對原件，再推論。工作區是 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation`，分支 `codex/m6-universe-generation`。不要在主 checkout 或 main 上實作，不要重做已結案的切片，也不要把任何保留下來的 FAIL 追認為 PASS。

## 1. 接手時先讀、先核

1. 依序讀：本工作區 `AGENTS.md`；本檔全文；`docs/implementation-notes/2026-10-24-m6-universe-generation.md` 從「2026-10-29：D1a fix2 與結案」到檔尾（C1d 的逐片紀錄、裁示、殘餘與待辦都在這裡）；`docs/superpowers/plans/2026-10-24-m6-universe-generation.md` 的 Task 8 與 Task 9–22。
2. 讀 ignored 的接力檔：`.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md` 最前段，以及 `progress.md` 的最後 40 筆。ignored 原件不在 Git 裡；若在其他機器上工作，要先確認能取得這些檔，不可補造收據。
3. 執行 `git status --porcelain --untracked-files=all`、`git rev-parse HEAD`、`git ls-remote origin`、`git worktree list`。確認沒有 live 的 Java／Gradle 測試 owner（閒置的 GradleDaemon 不需要動），也確認 `run/gametest/world/session.lock` 已經釋放。

## 2. 交接時的 Git 與證據

- 最後一筆程式 commit 是 `fe76d49e3e4cd08e97e51f5e9e16d092177a2104`（D1b-3c 本體 feat），前一筆是 `77491088f579a2d6d598af19b563ecb7ccb61b85`（RED）。兩筆都**已在本機 commit**；本交接的 docs commit 會一起 push，實際狀態以 ls-remote 為準。
- **D1b-3c 本體只完成 implementer 階段**：還沒有乾淨雙 focused、沒有完整 run-all，也沒有獨立 spec／quality 審查，**不可稱為已結案**。implementer 的回報與全部證據在 ignored 的 `task-08/8b2-c1d-d1b3c-evidence/`，總索引是 `D1B3C-INDEX.json`。
- 已結案的 code pin（每片都有本機 full、兩席審查 C/I0 與遠端雙平台 CI），詳見 M6 note：

  | 切片 | code pin |
  |---|---|
  | D1a | `9d97cd0` |
  | C1d Step0 | `242c20b` |
  | D1b-0 | `0efb058` |
  | D1b-1 BLOCK leaf | `58a2ff9` |
  | D1b-2 ITEM leaf | `3fbe626` |
  | D1b-3-0 | `2823985` |
  | D1b-3a | `36fdd36` |
  | D1b-3b | `ff3aecc` |
  | D1b-3c-0 | `be9d408` |
  | D1b-3c-0b | `ae25614` |

  最新一次完整回歸是 `run-all-89f998ff5a984fe4ac109f88431f6da9`（`ae25614`，JUnit919）。
- 3b 首輪 full 的 FAIL 原 root 保留：`run-all-afa9356c32f2436d8b78bf58e8fcb2c2`，根因是 AccessDenied 外部鎖檔，屬 M5 的已知限制。
- Claude 這段工作的 scratchpad 已複製到 ignored 的 `handoff-claude-20261030/scratchpad/`，附有 `SCRATCHPAD-MANIFEST-sha256.txt`。內容是各片的 javap 輸出與抽取腳本，只作為參考。

## 3. 第一件工作：完成 D1b-3c 本體的結案

1. 核對 HEAD 與 porcelain，確認 `git diff 463a984..fe76d49 --stat` 只動到六個允許的檔：ItemComponentContracts、LeafContracts、Closure、CanonicalizerTest、LeafContractsTest、Fixtures。
2. 在乾淨的 `fe76d49`（或之後的 docs HEAD，只要 code 相同）上跑：M6Compile；`dev.quantumchamber.universe.Minecraft121SettingsCanonicalizerTest` 與 `dev.quantumchamber.universe.Minecraft121RegistryLeafContractsTest` 各兩次 focused；完整 `scripts/verification/run-all.ps1`。前後都要做 Assert-Pin（HEAD 不變且 porcelain 為空），範本見 `task-08/8b2-c1d-d1b3c0b-evidence/D1B3C0B-CLEAN-VALIDATION.ps1`。run-all 約需 45 分鐘，判斷是否完成看 `END ALL` 那一行。
   - 預期：LeafTest 43、Canon 121。
   - 特別看 T2（全域 tag populate 視窗）在完整 JUnit 中的 P3 啟發式（以 thread 名稱判斷 worldgen worker）是否誤判。
3. 派獨立 spec review，再派獨立 quality review（唯讀、不跑 Gradle），審查對象是 `463a984..fe76d49`。implementer 自報的偏離要請審查判定：
   - Named 改用 `instanceof Named` 加原生 source 檢查（不用 exact class）。原因是 `RegistryEntryList$1` 是固定 JAR 內的匿名子類。
   - closure 層不另加 Named identity 檢查，因為那裡是恆真式。
   - T2 以反射唯讀 `Named.entries`／`Reference.tags`。
   - 成本診斷的 RM 改用 vanilla 預設 pack。
4. C／I 清零之後，更新 tracked docs truth（AGENTS 入口、M6 note、plan 快照），然後 push 並讀回遠端 CI。另外要把兩項待辦寫入 tracked note：「8B3 須證明 context 在 tag binding 之後才發布」，以及「JUnit 中帶 tag 的真資料閉包，成員只能當空下界」。

## 4. 之後的順序與已定規則

- **D1b-3d（jukebox_playable）**：JUKEBOX_SONG（dynamic registry）定義與 SOUND_EVENT leaf；identity 聯集要加入 SOUND_EVENT；`Contract.registries` 與 M9 交叉檢查的 registry 集合要擴充到 dynamic registry。**結案條件**：重跑見證 W-OPS（把 codec 改回 static ops），而且這個見證必須被殺掉。設計見 ignored 的 `task-08/8b2-c1d-design/D1B3-PROPOSAL.md` §3.1 的 3d 列，以及 `D1B3-DESIGN-REVIEW.md` 的 M3、M8。
- 之後依 ignored 的 `task-08/8b2-c1d-design/SEQUENCE-PROPOSAL.md`（Codex 寫的未審順序提案）：D1c LOOT_TABLE（真資料 GREEN 依賴 D1b-3）→ D1d 有 child 的 processor → D2a 模板 BE、D2b entity／ItemStack component → D3 非模板三 role closure → D4 100ms live hold I-1 與 provider／snapshot／resolve → D5（8B3 真 lifecycle 與三 bundle）。每一片都要先寫設計，經獨立設計審查放行後才能實作。
- Task 8 整體通過之後，才依 plan 接 Task 9–12（random seed，Task 12 才可玩）、13（same-seed fresh）、14（profile）、15–20（完整 Nether／End family、portal／gateway、respawn）、21、22。人工 smoke、整分支審查、main fast-forward、tag 各有獨立門檻。
- **controller 範圍裁示（2026-10-30）**：凡是必須以反射、access widener、mixin 等**繞過存取限制**才能觸發的殘餘，只寫進 Javadoc 與 tracked 待辦，不再另開硬化切片。只有一般 mod 用 public API 就能觸發，或 vanilla 會受影響的問題，才當場修。
- 已登記、要帶入後續切片的待辦（詳見 M6 note）：
  - D2b：TemplateValueContracts 的 CodecCache 快取端同類風險、`nativeSource` 的四份複本收斂、container oracle 用 equals 但 production 用 identity。
  - D3：End 的 `default_block`／`default_fluid` 缺 BLOCK 邊、world enabled feature set。
  - D4／I-1：冷尖峰要在全新 JVM 裡專門量測。注意私有 ops 改變了逐件診斷的冷暖語意，3c-0b 前後的數字不能直接比。

## 5. 工作方式（沿用至今的做法）

- **Subagent-Driven Development**：一個 implementer 處理一片，之後依序派獨立 spec review、quality review；Critical／Important 清零才能往下。派工時要寫明允許修改的檔案。使用者 2026-10-28 指定，子代理只用 gpt-5.6-sol、gpt-6-sol 或 gpt-6-astra。
- **證據紀律**：
  - 每片都要保留具名 RED、GREEN、突變見證（MUTATION-ONLY diff、REVERT 核對）、乾淨雙 focused、完整 run-all，以及 commit range。
  - 失敗的 root 保留不動，查明根因後改用新 nonce 重跑，不在原 root 上重跑改判。
  - 遇到 `TEMPLATE_SNAPSHOT_NOT_READY` 時，保留 root 並查根因，不重送碰運氣。
- **已知的坑**：
  - Canon 的 EndFixture 會經 `CombinedDynamicRegistries`→`toImmutable` 把全域 static registry freeze 掉，freeze 之後就不能再註冊。所以 `ModBlocks` 在 Canon 的 `@BeforeAll` 初始化；`ModItems` 一律不可引用；不可註冊新的 Item 或 Block。
  - 需要 foreign 物件時，用 `Unsafe.allocateInstance` 配置，不經過註冊。
  - `run/gametest/world/data/quantumchamber_sessions.dat` 偶爾會遇到 AccessDenied（外部程序鎖檔），屬 M5 的已知限制：維持 fail-closed 與退避，不加 production 重試。
- **docs 寫法**：
  - 寫進 tracked 文件的 SHA256 一律用 `@@X@@` 佔位符，由程式重算後填入，並核對每個佔位符只出現一次；commit 前要抽出 diff 新增行裡的所有長 hex，逐一與原件比對。
  - `git diff --check` 失敗必須 throw，不能只印出來。
  - 用 PowerShell 寫含反引號的內容時，改用單引號 here-string。
  - 被舊審查以 SHA 引用的 ignored 設計檔不得修改；要更正，就寫進 tracked note 的勘誤，或另開 addendum。
- **Git**：不用 `git add -A`、`reset --hard`、`checkout --`、`clean` 或 force-add，逐檔 stage。正常 push 有使用者 2026-10-24 的持續授權。force push、PR、main 合併、tag、人工代驗與 worktree 退役都**沒有**因此獲得授權，各自要另外取得同意。

## 6. 每次停下時要更新

AGENTS 的「目前接手入口」、M6 note 末節、plan 快照、ignored 的 STATUS.md 與 progress.md，並在本檔末尾的「交回紀錄」加一筆。保留原始 FAIL，並記下精確的 code pin 與 CI run。

## 交回紀錄

### 2026-10-30：Claude 交回 Codex

- 這段由 Claude 接手後，D1a 以及 C1d 的 Step0、D1b-0／1／2、D1b-3-0／3a／3b／3c-0／3c-0b 都已結案；完整 JUnit 從 847 增為 919。D1b-3 的設計 v1 經審查、修訂後放行。BLOCK／ITEM static leaf 已局部閉合；ITEM 被拒數從 175 降為 35（以 3c 實作後的實測為準）。
- 停點是 D1b-3c 本體：已 commit（`fe76d49`），還沒有驗證，也還沒有審查。
- 使用者沒有新的範圍裁示。Claude 在 2026-10-30 曾向使用者提出：「只有 mod 會觸發的硬化，可以改為記成殘餘、先推主線」，使用者尚未回覆；目前依上方 controller 範圍裁示處理。
