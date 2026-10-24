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

## Spec review 結案與 plan 入口（2026-10-24）

- fix1 range `0569fba..dc1cdf1`：按Provenance×role定義seed policy與新舊Worlds形狀，來源/目標SettingsSnapshot分離、持久provider/parameters/fingerprint；明列②③沿用M5非正規回程。新M6政策4096候選、snapshot512 bytes與正式NbtSizeTracker16MiB重讀是設計約束，尚未跑量測，不是runtime通過證據。
- 全新SOL `/root/m6_spec_review2` 釘選dc1cdf1／spec SHA256 `35E42C37FE48D38940A78348F0D10CAD5BDFC0F87852C2022E8E6D100116D449`，原I1/I2/I3/M1全關，但發現已遷移legacy Overworld未重驗實際設定；C0/I1/M0。報告spec-rereview1-result.md SHA256 `DFD3F772FA680ECD88A2B9234736906CA563AD5B13BF7F747CE8CAF52F8A2F82`，主控已讀回核hash。
- fix2 range `dc1cdf1..d742355`：只新增已遷移U載入/物化/來源與目的使用前，比對實際canonical Overworld及exact-owned live U設定的守門；不改未遷移schema1。source/fingerprint、seed與live instance皆需符合，不重建/覆寫舊世界。4行、純規格變更。
- 另一個全新SOL `/root/m6_spec_review3` 複審 **Approved，C0/I0/M0**，新I1關閉。固定HEAD=`d7423558cf5a3cd9f3689ddfaa7ed7d6addc81b6`、spec SHA256=`CC37ACDB3E7F0A3A5585E94AFBF852FB16DA60906ECD7749309FD3A75A8B64DF`，起迄porcelain空。報告spec-rereview2-result.md SHA256=`8FE37328A1BDE5B2C58C55DB9D81E060EFB58683E4549C274D7FF670C8709C5B`；主控已讀完整報告並重算spec/報告hash一致。
- 本次後續只將spec標題/主控預設採用狀態、AGENTS與handoff同步為設計已review完成，不更改已審查契約；不因此重跑runtime或啟動另一輪無變更審查。從main開工至此只有Markdown文件變更，沒有M6 production/test/build/harness行為變更，也未push。
- 下一步依writing-plans技能產生完整 `docs/superpowers/plans/2026-10-24-m6-universe-generation.md`，交全新SOL plan reviewer；C/I清零後直接SDD實作，不另問已確認的範圍或是否開始。M6未實作、未自動驗收、未人工驗收。

## Plan 作者稿與原版競態補充（2026-10-24）

- 完整plan作者稿已完成22個task，SHA256 `36754BDDEE7D686E45586053F577A33BAD0F0FEACAA6F6F1EE3BDF8338D7452D`；尚待全新獨立plan review，不是可開始實作的批准。
- Task1–2為兩harness安全修正，3為M6 gate/oracle，4–6為三race，7–12為native/snapshot/codec/seed/random，13–14為same-seed/mutation，15–21為family/login/routing/respawn/same-role，22為整合/人工/文件/CI。每task的Files/Interfaces與命令仍需review驗依賴及可執行性。
- 新唯讀補充SDD `native-races-inventory.md` SHA256 `3839ACA24CF7E6C1AF39842764F1D8F99410A1CB6C18094F4EE5393F8BBAEB4F`：固定同一1.21 JAR，列Stronghold全輪init/擴張、Fortress per-Start list仍引用static PieceData、End City共享Part.shipGenerated，以及worldgen與/place共通createStructureStart路徑。它是靜態候選，不是RED、runtime根因驗收或已證修法。
- 主控已讀plan全文、重算hash並核實際candidate入口：`SuperpositionSession`建立目前M4 policy，`CandidateLedgerService`建立resolver；獨立review須核計畫是否完整覆蓋這些接線與分階段能力。沒有執行runtime或改source/build/harness。

## Plan 與執行前核對結案（2026-10-25）

- 首輪plan review：全新SOL /root/m6_plan_review1 C0/I7/M0，釘選ed7ff9d／36754B；報告SHA256 `6B12F15FF0EB7FF95FBCC495B109C0238E0E004C05B99631B9C4EB8515014E65`。七項是正式capture/provider、外層journal reader、三role key/seed policy、testmod註冊/filter、Java/Gradle/PS chain、CI矩陣與退化projection。
- fix1 `ed7ff9d..9fdf9e4` 補實際owner/前置/平台/receipt/TPS矩陣；全新SOL複審C0/I1/M1，六I關閉，只留legacy journal被全域16MiB誤限及Task7 check script Files。報告SHA256 `DF769680104CC216A7E256B36FFF1F3F61F015670AC3D7AA2CF28413BC488B62`。
- fix2 `9fdf9e4..38e80fa` 改依同一穩定snapshot版本分派：schema1–4保舊合法容量，schema5才正式16MiB預算；要求合法>16MiB舊fixture、未知/換檔/升版拒絕反例，並補Task7檢查腳本owner。這些測試尚未執行。
- 另一個全新SOL /root/m6_plan_review3 複審 **Approved C0/I0/M0**，I2/M1關閉；釘選 `38e80fa569839ac36fb603f376f99413971aa951`、plan SHA256 `0F848A7153A4B9F93C1E191FCE3AE4469A1EFE10575078933F0DF43E9650D9D6`、spec C776E2BA...EF26BC。報告SHA256 `D53F46BFB167710A4ABC90F4915BAE9B415065AAE11452FBCBA0D29140E693E5`；主控已讀完整報告並核hash、起迄乾淨。
- 主控preflight：22個task逐列、196個共享file/interface pair、94個展開檔案ID；路徑/既存/前置Create缺口0，沒有未裁示文字衝突。SDD preflight.md SHA256 `7116CA522D6EC8989F238B344556C5FD5B2DF7D1704C40EE45C9A852F3019111`，同表與rulings已入ledger。這是計畫自洽檢查，不是runtime證明。
- 使用者要求的Claude專案記憶已唯讀參考；採用已有的人工驗收白話/焦點/當次log/15秒查看、STATUS小範圍接力與M6新run tick pacing檢查。舊進度/授權由最新AGENTS取代，未修改來源記憶；私人完整內容未複製進tracked文件。
- 本次只同步plan通過狀態、執行說明繁體中文與接手入口；task文字未改，spec未改，所有M6來源檔/測試/harness仍無實作變更。接續Task1的受控RED→窄修→同clean code HEAD兩GREEN/full→獨立雙review，無需再問是否開始。
