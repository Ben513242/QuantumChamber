# QuantumChamber M6：Codex 接續提示（2026-11-02，Task 8 縮範圍裁示）

全程使用繁體中文（台灣）、精簡回報。先核原件，再推論。本檔取代 [`2026-10-31-m6-codex-resume-prompt.md`](2026-10-31-m6-codex-resume-prompt.md) 成為唯一接手入口；10-31 檔保留為歷史，其「下次工作順序」一節已被本檔第 3 節取代，其餘停點事實與原 FAIL 原判仍有效。

## 0. 貼給 Codex 的起手句

```
請以 Codex 身分接手 QuantumChamber M6。先讀 AGENTS.md，再讀 docs/handoffs/2026-11-02-m6-codex-resume-prompt.md 全文並依其第 1 節順序讀完其餘文件。使用者 2026-11-02 已裁示縮 Task 8 範圍（見該檔第 2 節原話）：先完成 Task 8-core 與 Task 9–21 全部功能，之後才做 Task 8-H 中經 triage 判為必須的切片。第一件工作是第 3 節第 2 步：只改 spec／plan 文件並送獨立 review，不動程式。全程繁體中文（台灣），先報結論與證據。
```

## 1. 工作區與先讀

- Worktree：`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation`；分支 `codex/m6-universe-generation`。不在主 checkout 或 `main` 實作。
- 依序讀：本工作區 `AGENTS.md`；本檔全文；`docs/implementation-notes/2026-10-24-m6-universe-generation.md` 末四節（含 2026-11-02 裁示條目）；spec `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md` §3.1.1、§3.1.2、§8；plan `docs/superpowers/plans/2026-10-24-m6-universe-generation.md` Task 8 整段與 Task 9–22；10-31 接手檔「停點事實」與「尚未完成與原 FAIL」兩節；ignored `.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md` 首段與 `progress.md` 末筆。ignored 原件不在 Git，換機先確認存在，不可補造。
- 首先重核 `git status --porcelain --untracked-files=all`、`git rev-parse HEAD`、`git ls-remote origin refs/heads/codex/m6-universe-generation`、`git worktree list`、workspace Java owner，以及 `run/gametest-m6/world/session.lock` 可獨占。

## 2. 使用者 2026-11-02 裁示（binding）

使用者原話：「我想先裁示縮 Task 8 範圍，至少本模組功能都完成後才修必須的任務切片」。同日稍早的問句是「看一下m6分支到底是卡在哪裡還不進入Task8 仍有門檻，Task9–22 尚未開始」。

controller 解讀（非使用者原話；Codex 在 spec／plan 修訂時若認為解讀有誤，先向使用者確認再動）：

1. Task 8 拆成 **Task 8-core** 與 **Task 8-H**。8-core 只做讓 Task 9–14 能用的最小 snapshot／provider／context 接線；8-H 收納目前所有未完成的完整資料閉包、lifecycle 證明與跨 mapping bundle（第 5 節）。
2. 「本模組功能」指 plan Task 9–21 的全部遊戲功能：random seed、same-seed fresh、profile mutation、完整 Nether／End family、portal／gateway、respawn、同 role 艙。這些各自通過 plan 原門檻之後，才回頭做 8-H。
3. 「必須的任務切片」指 8-H 中經 triage 判為 M6 合併 `main` 前必須完成的項目；其餘以明確 gate waiver 記錄並移到 M7 或之後。哪些為必須，由 triage review 提案、使用者裁示，不由 controller 自行認定。
4. 本裁示不改變：Subagent-Driven Development 與每片獨立 review、retained FAIL 不改判、push 持續授權、不建 PR、不在 `main` 實作、不 force push，以及 `main` 合併、tag、worktree 退役各自門檻。

## 3. 新工作順序（取代 10-31 檔「下次工作順序」）

1. 核 Git／CI／ignored 原件（第 1 節）。
2. **只改文件，不動程式**：spec 升 v1.4（§3.1.1 增「SHALLOW_V1 最小 closure 與已知限制」小節、§3.1.2 標明 8-core 的最小 lifecycle、§8 門檻對應調整）；plan Task 8 重構為 8-core 與 8-H 兩段，8B2／8B3 既有已結案切片紀錄原文保留為歷史、不刪。送獨立 spec review 與獨立 plan review；Critical／Important 清零才進第 3 步。第 4 節是 controller 建議的界線，不是已審設計。
3. 實作 Task 8-core，依 plan 每批協定切片（第 4 節末）。
4. Task 9 → 10 → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19 → 20 → 21，依 plan 原文與各自門檻；不因 Task 8 縮範圍而放寬任何 Task 9–21 的 RED／雙 focused／full／兩席 review／CI。
5. Task 8-H triage：列出第 5 節全部項目的當時狀態，提案哪些為「必須」、哪些 waiver，送獨立 review 後交使用者裁示；只實作裁示為必須者。
6. Task 22 收尾（含人工 smoke、whole-branch review、docs truth、CI），之後 `main` 合併、tag、退役仍另守門檻。

## 4. Task 8-core 範圍界線（spec／plan 修訂須落實；具體設計仍須獨審）

**保留不動**：所有已 commit 的 Task 8 owner 與測試繼續編譯並在 run-all 內通過（10-31 基線 JUnit 971、M6 GameTest 9/9）。不刪、不改名、不為縮範圍而放寬任何既有 fail-closed 斷言。Template access gate 的 mixin、Contexts 的 stop fence／owner Cell／initial ticket、S1a 的 `DataPackContentsRefreshSealMixin` 診斷都保留；它們不進 8-core 的成功路徑。

**最小 closure（controller 建議預設，待 spec 修訂與獨審定案）**：

- 內建 provider（`quantumchamber:vanilla_normal_v1`／`vanilla_amplified_v1`，ProviderVersion 1；`m6_profiles_v1` 是 PolicyId，不是 ProviderId）的 closure 定義為 `SHALLOW_V1`：三 role 的 `DimensionOptions` 以 lease 的 registry ops 做原生 codec encode，registry 引用保留為 id、不做 transitive 展開、不讀 template／tag／loot／component；加上 Parameters 的 `OverworldPreset` 與 `GenerateStructures`。canonical 編碼為固定 key 排序的 NBT，沿用既有 512 byte 與 32 byte FP 契約。
- 完整資料閉包是未來另一個 ProviderVersion（例如 version 2），不是改 version 1 的語意；snapshot 外層五欄 schema 不變。
- 明列已知限制：`SHALLOW_V1` 的 FP 偵測不到 datapack 改寫 `minecraft:amplified` 等 noise_settings 內容、tag 內容或 template 內容。這是本裁示接受的風險，寫進 spec 已知限制與 Task 22 人工清單，不隱藏。
- `createSnapshot`／`resolve` 對 version 1 走真路徑；對未知 provider、version 或 closure 仍 fail closed。
- 不取得 template gate exclusive，不呼叫 `Minecraft121TemplateSettingsAdapter`／`Minecraft121RegistryClosure` 的 live 路徑；因此 100ms live hold I-1 不在 8-core 路徑上，歸 8-H。

**最小 lifecycle**：

- mod init：註冊內建 provider → freeze metadata → 以固定 Fabric lifecycle 事件接 `Minecraft121SettingsContexts` 的 `publishInitial`／`reloadStarted`／`reloadFinished`／`stop`，早於 journal／M5 authority 第一次需要 resolve。發布時點仍須以實際生命週期證據釘選，不靠 listener 註冊順序猜。
- 一個 M6 GameTest scenario：自然 startup 後 `acquire` 成功、registries identity、`createSnapshot`／`resolve`；**不做真 reload**，因為 retained 的 S1a observer 會把同 server 任何 reload 記為 `UNEXPECTED_RELOAD`。reload／STOPPING 接線由 lifecycle JUnit（可注入 registrar seam 核四個 Fabric 事件的註冊、假 Sources factory 核委派）加 mutation 守；真 server reload、多 inflight、controlled future 失敗、off-thread 交錯、refresh seal、source bridge 都歸 8-H。
- 不新增三 bundle oracle；CI 仍是現有 Ubuntu `build`／Windows `windows-native` 雙 job。

**8-core 完成門檻**：同 plan 每批協定：候選 compile → 具名 RED → GREEN → mutation-only＋REVERT → 乾淨同 pin 雙 focused → 完整 run-all 自然 `END ALL exit=0` → fresh spec／quality 各 C0/I0 → docs truth → 正常 push → 雙平台 CI success。全部通過才進 Task 9。

## 5. Task 8-H 延後清單（triage 候選；現在不做）

- **H1 完整資料閉包 provider v2**：C1d 其餘（D1c LOOT_TABLE；D1c-0 設計已審 C0/I0/M2、D1c-1 未放行）、三 role 完整 canonical／FP、100ms live hold I-1 的量測與獨審、T2 額外 100ms FAIL。
- **H2 正式 lifecycle 證明**：S1b 異常 startup／舊 seal 撤銷／重入／overlap（S1b-0 v2 草案 SHA256 `6ba74d6fc88bf1cfd8d653fe706bfefde109d30eadbb440109999ce0e83841ff` 未獨審）、S2 integrated RM 同源（提案 SHA256 `1eb5e823675a11fdaea0dff0562b95ebc36393f8516693c0596c8f0ea2a76ca5` 未獨審、結論 STOP）、正式 source bridge／同票同 seal publication／consumer 最終接受、LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 真 late 的安全性。
- **H3 8B3-F 三 bundle**：Windows named／Ubuntu named／Windows intermediary 的 canonical fixture 逐 byte 比對、`m6-settings-receipt-check`／`mapping-probe`／`compare` 三腳本、`ci-rule-check` 負例、workflow 改動、production runtime 具名準備核。
- **H4 完整 reload GameTests**：多 inflight 先序、controlled future 失敗後恢復、owned datapack tag／loot marker 同 epoch 比對。
- triage 預設建議（非裁示）：若 M6 正式只出 `SHALLOW_V1`，H1、H4 可 waiver 到 M7；H3 至少要證 Windows intermediary main-only 與 named 的 `SHALLOW_V1` FP 相同；H2 只在 refresh seal 真的進入正式路徑時才必須。最終以使用者裁示為準。
- 所有 retained FAIL（v4 父、B27、3b AccessDenied、T2 額外 100ms、LT2 STOP-PENDING、S1a 的 setup／RED／突變 FAIL）原判不變，不因延後而改寫。

## 6. 2026-11-02 核對事實（Claude 只讀盤點）

- local／origin feature 同為 `5b2bafa67790916e6d9dfac9a71c1d3e60a47442`，純 docs commit；領先 `origin/main` 50 個 commit。GitHub Build run [41149366263](https://github.com/Ben513242/QuantumChamber/actions/runs/41149366263) 同 SHA completed/success，Ubuntu `build`／Windows `windows-native` 雙 job success；CI 沒有執行 `S1A_ORACLE`，也沒有三 bundle。
- 程式碼真相：`UniverseProfileRegistry.createSnapshot`／`resolve` 仍無條件 throw `IllegalStateException("M6 context/canonicalizer 尚未就緒")`；`src/main` 沒有 `Minecraft121SettingsCanonicalizer.java`（同名 test 測的是 Graph／Closure／Contexts／LeafContracts）；`QuantumSuperpositionMod` 沒有任何 Settings／Profile／Contexts 接線；plan 列的 8B3 五個 testmod lifecycle owner 與 `m6-settings-receipt-check.ps1`、`m6-settings-mapping-probe.ps1`、`m6-settings-compare.ps1` 不存在，只有 `m6-refresh-seal-check.ps1`；Task 9–22 的 owner 檔全部不存在。
- 存在的 Task 8 main owner：`GenerationSettingsSnapshot`、`UniverseProfileProvider`、`UniverseProfileRegistry`、`ServerSettingsContext`、`Minecraft121SettingsContexts`、`Minecraft121SettingsGraph`、`Minecraft121RegistryClosure`、`Minecraft121RegistryLeafContracts`、`Minecraft121ItemComponentContracts`、`Minecraft121NativeValueContracts`、`Minecraft121TemplateValueContracts`、`Minecraft121TemplateSettingsAdapter`、`Minecraft121TemplateAccessGate`、`GeneratorProfile`；testmod 有 `M6RefreshSeal*`、`M6SettingsTrace*`、`M6RaceGameTests`、`M6GateCanaryGameTests`、`M6UniverseRecoveryProbe`。
- 此盤點在沒有 ignored 原件的 clone 上完成，未核任何 SHA256 原件，未跑 Gradle／run-all。10-31 檔的本機 full／oracle 數字沿用原紀錄，沒有新驗證。
- 進度判讀：Task 1–7 於 10-25 兩天完成；Task 8 自 10-26 到 10-31 六天，依 owner 檔數估 8B2 約六成、8B3 不到兩成；每片結案都新增 UNKNOWN 或 STOP，整合端未動。這是本裁示的背景，不是新的門檻。

## 7. 授權與邊界（沿用）

- 子代理只用 `gpt-5.6-sol`、`gpt-6-sol` 或 `gpt-6-astra`。一個 implementer 對應一片，之後獨立 spec／quality；C/I 清零才往下一片。
- 正常 push 已持續授權。force push、PR、`main` fast-forward、tag、人工代驗與 worktree 退役不在本授權。不得 `git add -A`、`reset --hard`、`checkout --`、`git clean`、force-add ignored 證據或修改主 checkout。
- retained FAIL 不改判；不重跑相同 focused 改判；不以改名或合併 task 省略門檻。
- 任何文件不得提前寫「已合併 main」「已 push」「人工驗收已通過」。

## 8. 交回紀錄

Codex 每次交回在此追加：日期、code pin、range、RED／GREEN、雙 focused、full nonce、兩席 review、CI run、下一步。
