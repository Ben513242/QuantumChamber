# QuantumChamber M6：Codex 接續提示（2026-11-02，Task 8 縮範圍裁示）

全程使用繁體中文（台灣）、精簡回報。先核原件，再推論。本檔取代 [`2026-10-31-m6-codex-resume-prompt.md`](2026-10-31-m6-codex-resume-prompt.md) 成為唯一接手入口；10-31 檔保留為歷史，其「下次工作順序」一節已被本檔第 3 節取代，其餘停點事實與原 FAIL 原判仍有效。

**2026-11-04 最新接續點**：8C-0 docs HEAD `551607cc69a64ecebd5ebc31e2aa0afd5739a33a` 的遠端 run `41833239533` Ubuntu／Windows 雙 job success；8C-1 本機 code pin `d38178b467e6412aa070091ad8228fd22366347d`，兩檔、九具名 RED、四個最終 mutation killer、乾淨雙 focused、`run-all-01dd2ce3b8b844178b291d4869ef00d5` 自然 `END ALL exit=0`／JUnit1002／M6 9/9；spec C0/I0/M1、quality Approved C0/I0/M1。Minor 是 after-lease 首輪存活診斷 raw 被覆寫，僅 summary 保留；修後殺手另有原件，不追認首輪。此筆 8C-1 docs truth／push／遠端 CI 待；雙平台成功後派 8C-2。原 8C-0 接續點與 §9 派工簡報均為歷史，Task9 尚未開始。

**2026-11-04 目前接續點**：8C-0 本機 code pin `d39fef1530bcf30102731e15aca1478657429c30`，三個 code commit、九檔；具名 RED／GREEN、四個原計畫 mutation 加 depth／detect-shape 兩個修正 mutation、乾淨雙 focused 與完整 `run-all-3e4a5c71830d4a5ea0e642176c7576b2` 自然 `END ALL exit=0`（JUnit993、M6 9/9）。spec I-1 修後 C0/I0/M0；quality Approved C0/I0/M1，Minor 為測試 fixture 清理雙重失敗可能遮蔽原錯誤。此筆撰寫時 docs truth／feature push／本 code 的遠端 CI 尚待；確認雙平台成功後接 8C-1。原 `run-all-8b67…` FAIL 與所有既有 FAIL 不改判。以下原 2026-11-02 停點與 §9 派工簡報保留為歷史，不再當未開工指示。

## 0. 貼給 Codex 的起手句

```
請以 Codex 身分接手 QuantumChamber M6。先讀 AGENTS.md，再讀 docs/handoffs/2026-11-02-m6-codex-resume-prompt.md 全文並依其第 1 節順序讀完其餘文件。使用者 2026-11-02 已裁示縮 Task 8 範圍：先完成 Task 8-core 與 Task 9–21 全部功能，之後才做 Task 8-H 中經 triage 判為必須的切片。8C-0 已結案；8C-1 本機 code pin d38178b467e6412aa070091ad8228fd22366347d 已通過兩席 review。到場先核 Git、ignored 原件、8C-1 四份 docs truth 是否已提交推送，以及本 code 的 Ubuntu／Windows 雙平台 CI；未完成者依序完成，雙平台成功後依 plan 派 8C-2，不重做 8C-0／8C-1。全程在 Windows M6 工作樹使用 Subagent-Driven Development，繁體中文（台灣）精簡回報，先證據後結論。
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

1. 核 Git／CI／ignored 原件（第 1 節）。【已完成，見第 6 節 2026-11-02 稍後事實】
2. 【已完成：spec v1.4 與 plan 8C／8-H 經兩席獨審加三輪窄複審採用，最終 C0/I0/M0；commit 鏈見第 6 節】**只改文件，不動程式**：spec 升 v1.4（§3.1.1 增「SHALLOW_V1 最小 closure 與已知限制」小節、§3.1.2 標明 8-core 的最小 lifecycle、§8 門檻對應調整）；plan Task 8 重構為 8-core 與 8-H 兩段，8B2／8B3 既有已結案切片紀錄原文保留為歷史、不刪。送獨立 spec review 與獨立 plan review；Critical／Important 清零才進第 3 步。第 4 節是 controller 建議的界線，不是已審設計。
3. **目前下一步**：8C-0 已通雙平台；8C-1 本機已驗且兩席 C/I0。完成 8C-1 docs truth／正常 push／本 code 雙平台 CI 後，依 plan 實作 8C-2→8C-3；8C-0 派工簡報留第 9 節供歷史核對。
4. Task 9 → 10 → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19 → 20 → 21，依 plan 原文與各自門檻；不因 Task 8 縮範圍而放寬任何 Task 9–21 的 RED／雙 focused／full／兩席 review／CI。
5. Task 8-H triage：列出第 5 節全部項目的當時狀態，提案哪些為「必須」、哪些 waiver，送獨立 review 後交使用者裁示；只實作裁示為必須者。
6. Task 22 收尾（含人工 smoke、whole-branch review、docs truth、CI），之後 `main` 合併、tag、退役仍另守門檻。

## 4. Task 8-core 範圍界線（已由 spec v1.4 §3.1.2／§3.1.3 與 plan 8C 定案；本節是摘要，細節以 spec／plan 為準）

**保留不動**：所有已 commit 的 Task 8 owner 與測試繼續編譯並在 run-all 內通過（10-31 基線 JUnit 971、M6 GameTest 9/9）。不刪、不改名、不為縮範圍而放寬任何既有 fail-closed 斷言。Template access gate 的 mixin、Contexts 的 stop fence／owner Cell／initial ticket、S1a 的 `DataPackContentsRefreshSealMixin` 診斷都保留；它們不進 8-core 的成功路徑。

**最小 closure（spec §3.1.3 `SHALLOW_V1`，已採用）**：

- 內建 provider（`quantumchamber:vanilla_normal_v1`／`vanilla_amplified_v1`，ProviderVersion 1；`m6_profiles_v1` 是 PolicyId，不是 ProviderId）的 closure 定義為 `SHALLOW_V1`：三 role 的 `DimensionOptions` 以 lease 的 registry ops 做原生 codec encode，registry 引用保留為 id、不做 transitive 展開、不讀 template／tag／loot／component；加上 Parameters 的 `OverworldPreset` 與 `GenerateStructures`。canonical 編碼為固定 key 排序的 NBT；SHALLOW_V1 envelope 上限 8192 bytes，snapshot 外層五鍵上限 512 bytes，FP 為 32 bytes。
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

### 6b. 2026-11-02 稍後事實（spec／plan 採用輪）

- commit 鏈：`d8c5897` 裁示與接手檔 → `20338be` v1.4 草案（待獨審）→ `4ca1303` 首輪 fix（spec C0/I4/M11、plan C0/I5/M11 全處理）→ `4a50629` 第二輪 fix（窄複審一 C0/I1/M3，I-N1 registrar seam）→ `2b1764e` 第三輪 fix（窄複審二 C0/I1/M2，I-N2 Sources factory）→ 本檔所在的採用 commit（窄複審三 C0/I0/M0）。review 報告 SHA256：spec `c1ab9ec35a6e6c9dfba14f7f9532a2dfcca655019c974446c4ecf41df0963be8`、plan `5aa3302026c10312b9d1c415b471d44db7bd18b5a5789e2d823f1ba719120e12`、窄複審一 `ff9ae0b788bc9ba4b2704c25166db6ba87f24e93149c815eac0a1007388868f1`、二 `e52e6a80755a7faceb6d193242010f951be0d0d792f02194702376e2f2a0c920`、三 `943626daecfb517da91a74828d02412c4866c2c81e2f702e8d75c30ccb04ebb2`；原件只在雲端 session scratchpad，要旨記於 M6 note。
- 採用版 spec SHA256 `e9dbaac9736a83a38dde96605c2ffd411a7b716b35e241eaa209d4943748dad2`、plan SHA256 `e087f43c1717fa63ea6857220544443ca8d528930d71cf107acb5424001646a5`。
- 遠端 CI：`d8c5897` run 41409147739、`20338be` run 41429481418、`4ca1303` run 41432221278 皆 Ubuntu `build`／Windows `windows-native` 雙 job success；`4a50629` run 41433659755、`2b1764e` run 41434595336 於撰寫時 in_progress；採用 commit 的 run 待讀回。全部純 docs，CI 只證既有 gate 未壞。
- 本輪 controller 是 Claude Code 雲端 session，子代理為 Claude（只讀 reviewer）。雲端容器網路政策擋 `maven.fabricmc.net`、`libraries.minecraft.net`、`piston-meta.mojang.com`，且無 pwsh，故**未寫任何 8C 程式、未跑任何 Gradle／focused／run-all**；8C 實作必須在 Windows 工作樹（或同等可 build、可跑 PowerShell gate 的環境）進行。
- 使用者 2026-11-02 另問是否提供 ignored 的 SDD ledger／STATUS；controller 回覆只需 `progress.md` Rulings 段與 `STATUS.md` 首段作唯讀參考，本輪未取得，因此 v1.4 未與 ignored ledger 交叉核對；Codex 到場第一步須補核此點，若 ledger 有與 v1.4 衝突的舊裁示，先向使用者確認再動。

## 7. 授權與邊界（沿用）

- 子代理只用 `gpt-5.6-sol`、`gpt-6-sol` 或 `gpt-6-astra`。一個 implementer 對應一片，之後獨立 spec／quality；C/I 清零才往下一片。
- 正常 push 已持續授權。force push、PR、`main` fast-forward、tag、人工代驗與 worktree 退役不在本授權。不得 `git add -A`、`reset --hard`、`checkout --`、`git clean`、force-add ignored 證據或修改主 checkout。
- retained FAIL 不改判；不重跑相同 focused 改判；不以改名或合併 task 省略門檻。
- 任何文件不得提前寫「已合併 main」「已 push」「人工驗收已通過」。

## 8. 交回紀錄

Codex 每次交回在此追加：日期、code pin、range、RED／GREEN、雙 focused、full nonce、兩席 review、CI run、下一步。

- 2026-11-02（Claude 雲端 controller）：只有文件變更。spec v1.4／plan 8C 與 8-H／接手檔／AGENTS／M6 note；review 鏈與 CI 見第 6b 節。8C-0 未開工。下一步：Codex 依第 9 節派工 8C-0。
- 2026-11-04（Codex，8C-0）：code range `071bdf0..d39fef1`（`1c10b6a` 本體、`a8b8d20` Graph depth fix、`d39fef1` preset detect I-1 fix），九檔。具名 RED／GREEN、六個 mutation-only＋REVERT；最終乾淨 pin 三 class 各兩次 focused：Provider 5、Shallow 14、Contexts 44；depth fix pin 另有舊 CanonicalizerTest 123×2。新 full `run-all-3e4a5c71830d4a5ea0e642176c7576b2` 自然 `END ALL exit=0`／JUnit993／M6 9/9／mainOnly PASS；先前 `run-all-8b67…` JUnit 1 FAIL 原判保留。spec I-1 修後 C0/I0/M0、quality Approved C0/I0/M1；Minor 為測試 fixture 清理失敗的原錯誤保留，記於後續 fixture 整理。此筆尚未 push、無本 code 遠端 CI；下一步先 docs truth／push／CI，再 8C-1。
- 2026-11-04（Codex，8C-1）：8C-0 docs HEAD `551607c` 的 GitHub run `41833239533` Ubuntu／Windows雙job success。8C-1 code range `551607cc69a64ecebd5ebc31e2aa0afd5739a33a..d38178b467e6412aa070091ad8228fd22366347d` 恰 Registry／RegistryTest 兩檔；九具名 RED（12案9敗0錯）、四個最終 production mutation 具名被殺／REVERT，after-lease 首輪存活僅 summary 留存、raw 已不可恢復並有 ignored 勘誤。乾淨同 pin Registry12×2、Snapshot7×2、Shallow14×2 focused PASS；完整 `run-all-01dd2ce3b8b844178b291d4869ef00d5` 自然 END ALL0／JUnit1002／M6 9/9／mainOnly PASS。spec C0/I0/M1、quality Approved C0/I0/M1，同一 Minor 為首輪 survivor raw 保全缺口；原 FAIL 不改判。此筆 docs truth／push／本 code 遠端 CI 尚待；通過後派 8C-2。

## 9. 8C-0 派工簡報（給 Codex implementer；binding 以 plan 8C-0 與 spec §3.1.3 原文為準）

**前置**：Windows 工作樹、porcelain 空、HEAD 為本檔所在 commit 或其後 docs commit；先 `gates.ps1 -Mode M6Compile -Label 8c0-baseline` 確認基線可編譯。先讀 plan 8C 總說與 8C-0、spec §3.1.2 (d)／§3.1.3、M6 note D1b-1（`ModBlocks` 須在 registries fixture freeze 前初始化）。

**允許檔案**：main `ServerSettingsContext.java`、`Minecraft121SettingsContexts.java`（只加 lease 兩個讀面）、`Minecraft121SettingsGraph.java`（只抽出 NBT canonical 純函式，行為不變）、新 `Minecraft121ShallowSettingsCanonicalizer.java`、新 `Minecraft121VanillaPresetProfileProvider.java`；test 新 `Minecraft121WorldgenRegistryFixture.java`、新 `Minecraft121ShallowSettingsCanonicalizerTest.java`、新 `Minecraft121VanillaPresetProfileProviderTest.java`、既有 `Minecraft121SettingsContextsTest.java`。不碰 `GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`（8C-1）、任何 testmod／scripts／build。

**介面要點**：
- `ServerSettingsContext` 公開方法恰三個：既有 `requireValid()`，新 `DynamicRegistryManager.Immutable registries()`、`boolean sourceGenerateStructures()`。Lease 實作在既有 `synchronized(cell.fence){ validateLocked() }` 之後讀 `Sources.registries`，非 `DynamicRegistryManager.Immutable` 即拋既有 `unavailable()`（`CONTEXT_UNAVAILABLE`）；`Sources` record 型別不變。
- provider 同一類別以兩個 id 註冊：`quantumchamber:vanilla_normal_v1`（preset 必 normal）、`quantumchamber:vanilla_amplified_v1`（必 amplified），version 1，`adapterCapabilities` 恰 `quantumchamber:m6-settings-shallow-v1`；`ParameterSchema` 先 `GenerationSettingsSnapshot.validateBuiltinParameters` 再核 id↔preset。三 role 以 lease registries 的 Reference entry 建構（spec §3.1.3 精確 id）；`generateStructures` 取 Parameters。另 `Optional<String> detectOverworldPreset(lease)`。
- canonicalizer 輸入 `ResolvedSettings`＋Parameters＋lease；`RegistryOps.of(NbtOps.INSTANCE, lease.registries())`，encode 前後各 `requireValid()`；encode 後逐 role 結構檢查 id 字串（`SHALLOW_INLINE_ELEMENT`）；四鍵 envelope；canonical 由 Graph 抽出的純函式編碼（鍵序＝Unicode scalar 次序、list 保序、big-endian）；FP＝SHA-256(`quantumchamber:m6:settings:v1` UTF-8 ‖ canonical)；上限 8192 bytes。
- 固定 1.21 API 落點（實作時以 Yarn `1.21+build.9` 實查，不以此處為已驗）：`DimensionOptions(RegistryEntry<DimensionType>, ChunkGenerator)`、`NoiseChunkGenerator(BiomeSource, RegistryEntry<ChunkGeneratorSettings>)`、`MultiNoiseBiomeSource.create(RegistryEntry<MultiNoiseBiomeSourceParameterList>)`、`TheEndBiomeSource.createVanilla(RegistryEntryLookup<Biome>)`、keys `ChunkGeneratorSettings.OVERWORLD／AMPLIFIED／NETHER／END`、`MultiNoiseBiomeSourceParameterLists.OVERWORLD／NETHER`、`DimensionTypes.OVERWORLD／THE_NETHER／THE_END`、`DimensionOptions.CODEC`、`RegistryOps.of`。

**fixture**：`Minecraft121WorldgenRegistryFixture` 以 `ServerDynamicRegistryType.createCombinedDynamicRegistries()` 與 `RegistryLoader.loadFromResource(rm, preceding(WORLDGEN), RegistryLoader.DYNAMIC_REGISTRIES)` 真載入原版 worldgen registries（技法同 `Minecraft121SettingsCanonicalizerTest.EndFixture`，但不得 import 該 private record）；提供第二個入口以額外測試 pack 覆寫 `minecraft:amplified` noise settings（改 `sea_level`），供限制見證。每次載入都是獨立 registries；provider 與 canonicalizer 必用同一 lease 的 registries。

**具名 RED 清單**：依 plan 8C-0 第 4 點十七個名稱逐字使用；RED 須是具名斷言失敗，不是 compile error。mutation 四個與 killer 對應見 plan 8C-0 第 5 點；每輪 mutation-only diff、REVERT 後 `git hash-object` 比對原 blob。

**驗證命令**：`gates.ps1 -Mode M6Compile -Label 8c0-compile`；`m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.Minecraft121ShallowSettingsCanonicalizerTest -Label 8c0-canon-{{1,2}}`、同法 `…Minecraft121VanillaPresetProfileProviderTest`、`…Minecraft121SettingsContextsTest`；完整 `run-all.ps1` 看 `END ALL exit=0`。結案 note 記實測三 role canonical 長度、JUnit 數、M6 GameTest 仍 9/9。之後 fresh spec／quality 各一席，C/I 清零才進 8C-1。
