# M6 宇宙生成與完整維度家族：實作紀錄

日期：2026-10-24（台灣）。分支 `codex/m6-universe-generation`。目前為設計階段，尚未實作或執行 M6 runtime。

## 使用者確認的範圍與順序

完整 M6，依序分段驗收：測試腳本安全與生成競態 → random seed → same-seed fresh／generation profile 與 registry/API → 完整 Nether／End family、portal／gateway、respawn 與同 DimensionRole 投影／跨越。M7 literal branch／snapshot 不納入。子代理使用 SOL；本輪採 `gpt-6-sol`。

先寫 binding spec，獨立 spec review 清除 Critical／Important；再寫 plan 並獨立 review，通過後直接實作。具體 profile 類型、權重與技術選擇若由協調者決定，會在 spec 標明為設計預設，不冒稱使用者逐項選定。

## 開工 Git 證據

- main、origin/main 與遠端實際 main 均為 `0d05f889da6f8b82b97af756aaa50066b3d42854`，主 checkout porcelain 空；唯一主旨 `docs: record M5 integration and retirement handoff` 解析至同 SHA。
- m5 annotated object=`15c2943ce37950f468b030c5d194485a328c1705`，peeled=`a2d2b404329d2574469fca064a663e21fe89f333`，本機／遠端一致；`merge-base --is-ancestor m5^{} main` exit 0。
- m5..main 九份變更全部為文件。M5 `.worktrees/m5-collapse` 路徑不存在；`git worktree list --porcelain` 不再列出它。
- 宿主 create_worktree 因主 checkout 已知 dubious ownership 失敗，未建立可用工作樹；遵守不改全域 safe.directory，以每次命令限定的設定建立 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation` 與 `codex/m6-universe-generation`。
- 新工作樹從上述 main SHA 開出，初始 porcelain 空；`.superpowers/sdd/2026-10-24-m6-universe-generation/` 已由 `git check-ignore` 確認 ignored。

## 已核現行契約

- NEW candidate 已凍結 token、profile、generationSeedMaterial，並寫入 receipt；目前僅允許 legacy shared-seed profile。
- catalog schema1 未保存實際 seed；factory 共用 canonical Overworld generator／save seed／random sequences。random seed 不能只改 factory 的單一 seed 引數，必須先定義新版本持久化與legacy相容性。
- 三個 DimensionRole 是預留型別；keys、definition、backend、lifecycle、transfer 仍有 Overworld 限制。完整 family 需要實作，不把 enum 視為已完成。
- Stronghold／Nether fortress／End city static 競態仍需各自根因與定向 runtime 證據；H5/H6/H8 未覆蓋它們。
- 舊 Universe 身分／seed／destination、M4 receipt、M5 schema4 authority、同XYZ passage、inert投影與既有失敗語意保持，除非新binding spec明列已授權的M6變更。

## Step 0 與驗證邊界

- `probe-common.ps1:17–24`：New-OwnedRoot 尚未在建立前守住 run 祖先的 canonical／reparse 邊界。
- `gates.ps1:80–102`：Move-Fresh 搬移後 New-Item 失敗，caller 尚未取得 swap；需確保復原仍執行，且保留原例外。現況不是原世界已毀損。
- 開工發現 `scripts/verification/README.md:392` 仍有F7前的27a5039現況句，本輪已同步更正為最新已驗M5本機full：6b116e6／run-all-e32343e8bd034936aee50b9b3db48a1f；CI：f99ce66／run40079019075。這是文件修正，未重跑已完成M5。
- 本輪尚無M6測試、runtime root、review通過或code commit。兩個SOL唯讀盤點不是獨立spec/plan review。
- 後續每task／fix round保持RED、同乾淨code HEAD的兩次focused GREEN與必要完整回歸；具體矩陣由plan定義。純文件只驗diff與格式，不冒稱新SHA已跑runtime。

## 接續

SDD／短派工入口：`.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md`。目前正撰寫 `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md`；完成後交全新SOL reviewer。

## Spec 第一輪獨立 review（2026-10-24）

- 作者稿SHA256：`CBC7E584C629356836335FF1EB1D76A663F047BFE8DAF73D5FD7F0098D3DF7CB`；review時HEAD=`0d05f889da6f8b82b97af756aaa50066b3d42854`，當時spec為本輪untracked文件。主控重算hash一致。
- 全新SOL reviewer `/root/m6_spec_review1` 結論 Needs changes：Critical0／Important3／Minor1。I1：遷移後legacy Overworld的SHARED_SAVE_SEED與新schema2規則互斥；I2：NEW_M6 descriptor的GeneratorProfile值域缺失；I3：持久設定只有名稱、無法核provider映射變動。主控對現行UniverseRegistry／Record及spec核對後採納。Minor為②③承接M5非正規回程的階段界線，於同一文件修正輪明列，不提前改portal／死亡玩法。
- 原報告：SDD `spec-review-result.md`，SHA256 `05371679DEE51172899CE87DA76E0CF6EF11A4980592381D7B603BFEA595CAAE`。這是規格審查，沒有M6 runtime或code-quality通過結論。修正後須交另一個全新reviewer。
- 原版唯讀報告：SDD `native-inventory.md`，SHA256 `7C8A17AEEA3FDC440F406AEECCA22CE2343EEA6E62D1F3DA98B631003FA15091`；Yarn named Minecraft 1.21 JAR SHA256 `C3D5432D0538D7750B985E42788484BEE4C63BBFACD7CADD496E940C83DFCEC4`。主控用Java21 javap直接複核`ServerWorld.getSeed()`每次讀全域SaveProperties；完整報告另列noise/structure、RandomSequences、End dragon及portal/gateway/respawn的精確方法與bytecode offset。只有唯讀證據，未啟動runtime或驗mixin。
- 下一步：作者修正三Important與相鄰的文件Minor→全新spec複審→plan及獨立review→有界實作。尚無新增使用者裁示需求。
