# M6 宇宙生成與完整維度家族：實作紀錄

日期：2026-10-24（台灣）。分支 `codex/m6-universe-generation`。最新（2026-10-29）：Task1–7、8A、8B1、8B2-G、C1a與C1b-B1/B2/B3局部資料切片已完成；B3 code `530491b` 乾淨雙focused70/70、完整run-all與獨立spec/quality C/I/M0，但仍無完整三role/FP。C1c template v2設計C0/I0/M0待code；C1d/100ms live hold/8B3三bundle及Task9–20/人工仍待；原件見文末。

## 使用者確認的範圍與順序

完整 M6，依序分段驗收：測試腳本安全與生成競態 → random seed → same-seed fresh／generation profile 與 registry/API → 完整 Nether／End family、portal／gateway、respawn 與同 DimensionRole 投影／跨越。M7 literal branch／snapshot 不納入。子代理依2026-10-28最新使用者指示，只採 `gpt-5.6-sol`、`gpt-6-sol` 或 `gpt-6-astra`，按任務選用。

先寫 binding spec，獨立 spec review 清除 Critical／Important；再寫 plan 並獨立 review，通過後直接實作。具體 profile 類型、權重與技術選擇若由協調者決定，會在 spec 標明為設計預設，不冒稱使用者逐項選定。

## 開工 Git 證據

- main、origin/main 與遠端實際 main 均為 `0d05f889da6f8b82b97af756aaa50066b3d42854`，主 checkout porcelain 空；唯一主旨 `docs: record M5 integration and retirement handoff` 解析至同 SHA。
- m5 annotated object=`15c2943ce37950f468b030c5d194485a328c1705`，peeled=`a2d2b404329d2574469fca064a663e21fe89f333`，本機／遠端一致；`merge-base --is-ancestor m5^{} main` exit 0。
- m5..main 九份變更全部為文件。M5 `.worktrees/m5-collapse` 路徑不存在；`git worktree list --porcelain` 不再列出它。
- 宿主 create_worktree 因主 checkout 已知 dubious ownership 失敗，未建立可用工作樹；遵守不改全域 safe.directory，以每次命令限定的設定建立 `C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation` 與 `codex/m6-universe-generation`。
- 新工作樹從上述 main SHA 開出，初始 porcelain 空；`.superpowers/sdd/2026-10-24-m6-universe-generation/` 已由 `git check-ignore` 確認 ignored。

## 開工時核對的契約（後續結果見各 task）

- NEW candidate 已凍結 token、profile、generationSeedMaterial，並寫入 receipt；目前僅允許 legacy shared-seed profile。
- catalog schema1 未保存實際 seed；factory 共用 canonical Overworld generator／save seed／random sequences。random seed 不能只改 factory 的單一 seed 引數，必須先定義新版本持久化與legacy相容性。
- 三個 DimensionRole 是預留型別；keys、definition、backend、lifecycle、transfer 仍有 Overworld 限制。完整 family 需要實作，不把 enum 視為已完成。
- 開工時 Stronghold／Nether fortress／End city static 競態尚未由 H5/H6/H8 覆蓋；後續已在 Task4–6 分別完成根因、修正、定向 runtime 與獨立 review。
- 舊 Universe 身分／seed／destination、M4 receipt、M5 schema4 authority、同XYZ passage、inert投影與既有失敗語意保持，除非新binding spec明列已授權的M6變更。

## Step 0 與驗證邊界

- 開工盤點 `probe-common.ps1:17–24`：當時New-OwnedRoot尚未守住run祖先的canonical／reparse邊界；後續修正與證據見Task1。
- 開工盤點 `gates.ps1:80–102`：當時Move-Fresh搬移後New-Item失敗會使caller尚未取得swap；後續復原與原例外修正見Task2。當時原世界仍在backup，並非已毀損。
- 開工發現 `scripts/verification/README.md:392` 仍有F7前的27a5039現況句，本輪已同步更正為最新已驗M5本機full：6b116e6／run-all-e32343e8bd034936aee50b9b3db48a1f；CI：f99ce66／run40079019075。這是文件修正，未重跑已完成M5。
- 開工盤點當時尚無M6測試、runtime root、review通過或code commit；下方逐輪紀錄覆蓋該歷史狀態。兩個SOL唯讀盤點本身不是獨立spec/plan review。
- 後續每task／fix round保持RED、同乾淨code HEAD的兩次focused GREEN與必要完整回歸；具體矩陣由plan定義。純文件只驗diff與格式，不冒稱新SHA已跑runtime。

## 接續

SDD／短派工入口：`.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md`。目前依已核 spec／plan 接續 Task 3；Task 1、2 不重做，最新原件與派工狀態看 STATUS。

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

## Task 1：owned run 祖先守門完成（2026-10-25）

- range `0087b2f..b533367`，code `b53336717ecc0c54040fda6fa5b9b929e5644f2d`。只改probe-common.ps1、新m6-harness-safety-check.ps1與README；沒有Java/testmod/build/workflow變更。New-OwnedRoot建立前驗canonical Project、run與所有祖先非reparse/實體目錄，root direct parent必為run。
- RED根 `task-01/h1-red-f0c519d004454e758785643026741ffc` 在BASE上，正常existing/fresh先PASS，真run-junction案例因未拒絕且受控外側被寫入FAIL，原現場保留。receipt SHA `6E2B94A71AE92AC18B5D577344415FDF978ACC66B4E9F5E6305B21D9EC7AFED1`。
- 同clean b533367兩GREEN：`h1-green-a-627cf067877d478090b87cd37e01e59a`、`h1-green-b-0f2b78c3592745249a19cf2a8902e192`，各8/8；receipt SHA分別 `567D1F26E608E7D7F284717570E860E92F9E310F7E5446A7DC704D8D0065E673`、`6F40D75D7EAA769F7B852F2CCA266499FD82CACB359BD3465397119B845531A1`。PS parser兩檔0錯。
- 完整run：`task-01/full/run-all-0790df31c2a943ceac91c2ca2eb01890`，2026-10-25 02:03:32精確`END ALL exit=0 HEAD=b53336717ecc0c54040fda6fa5b9b929e5644f2d`；summary SHA `2241279E97DBFD256EA86D1BCF2518F40EEC354D92E1E2A62C9836B9EE470C29`。
- SUMMARY：default179/179/windows89/legacy1/M560/60/perTest56+1/JUnit666(0fail/0error/2skip)/checkpoint17/release432entries/385classes/sources226/testmod0/MainOnlyPASS/M3 4+5/recovery24/collapse69/HALT31。本機win/ubuntu CI rule PASS不是GitHub runner結果，沒有為此push。
- 主控讀回RED/兩GREEN、三份具名XML、JUnit原XML、JAR ZIP/sha、main-only三層/normalStop/exactRestore/worldKeys與三world restore；scope missing/extra皆空。對M5 6b116e6的src/build/workflow無差異；未聲稱比過已退役的歷史raw XML。
- 原97-log audit保留（SHA `667064F15D63454C83E70C252292AF10E957F438F93CC534462B1B39F5631081`）；M3 9phase/9log補充另存，主控與spec reviewer各重算255個原件hash零差異。M3 manifest SHA `CB6558B0D2800826456816A46D1D7944B8DD70760499A052E04B44D30367178C`，補充audit SHA `B914B4E503453047EF22F55DA45FCC32A4726BDB2B5CDBBE8590146B1B5618C1`。M3是fresh owned roots，無既有world可還原；均保留、owner/lock釋放。
- 合計106當次log，2425 WARN/ERROR依具名fault/原版訊息歸屬，worldgen命中0。journal-corrupt的unexpected字樣為預期損壞GZIP拒絕：原receipt PASS/failures[]、拒啟動/保留bytes/正常停止及lock釋放皆證；不是未歸屬新例外。完整來源/stack與集合見task-1-report.md及原件。
- 全新獨立spec `/root/m6_task1_spec_review` Approved C0/I0/M0，報告SHA `894FCB1DDE9C12700F3AF545C8E6F2BC103E271857C5B239F9EA7B5028A6C2AD`；另一全新quality `/root/m6_task1_quality_review` Approved C0/I0/M0，SHA `F75D4CB15CCFE06494E809B2D62F593F998D3FDB4C25D5CF9E8320373F79659B`。主控已讀完整兩報告/核hash；兩者僅唯讀，不重跑suite。
- task-1-report.md SHA `063B359FD4810AC2BEF9224D0CF7AF2EE409D4A79E1CD25A84FEB653356AF4BC`；review diff SHA `FFA670EA39483B33450E63A3702C1461385B6D6B8861C8DBE39913C8D33AF43D`。本段相對上述code只有結果文件，不冒稱新docs SHA重跑runtime。
- Task1完成、無未關finding。下一步Task2 Move-Fresh建目錄失敗復原，另派全新implementer；不做生成/renderer/M7。人工M6 smoke未執行、遠端CI未執行、尚未push/merge/tag/退役。

## Task 2：fresh 建立失敗的原世界復原完成（2026-10-25）

- range `0c20c59..98ea115`，code `98ea1151c6ddefb2ece82a3f71cc6b1715818348`，只改 gates.ps1、m6-harness-safety-check.ps1、README。建立 fresh 前保存 swap；建立失敗自動復原並保留原例外，雙重失敗保留兩原因與可恢復位置。無／部分 fresh 與正常路徑均涵蓋。
- BASE 具名 RED `task-02/h2-red-real-e21690134914410ab33990ac4f10cc2b` 保留 backup；receipt SHA `48725ABB6DD5AF1BB00DAB0085F3271600A3A490037973BD9A961788D5293B3B`。兩次同 clean code GREEN 各6/6：`h2-green-a-469229a36a7c41748f3863cb535cc609`、`h2-green-b-236ae49654a544cfa099d580f219ae16`；SHA `4B8C40AF4F45311EDF568753BC3642BD4CBE5AB646ED89236CC9383CE8465296`／`A4702DE3ADE05514A3005B2BC01442ACD0F5C75C9DB078D4F3A179C3C3CAB563`。owned-root回歸8/8，PS parser/diff check通過。
- full `task-02/full/run-all-700c8a5654fa445283b6885f02810ba0`，03:34:36精確 `END ALL exit=0 HEAD=98ea1151c6ddefb2ece82a3f71cc6b1715818348`；summary SHA `07949818454F0F994BF582299B8D443B3F439C5AE44946B5D2DFD14B31F1F63B`。default179/windows89/legacy1/M560/perTest56+1/JUnit666(0fail/0error/2skip)/checkpoint17/JAR432entries/385classes/sources226/testmod0/MainOnlyPASS/M3 4+5/recovery24/collapse69/HALT31。本機兩平台CI規則PASS不是GitHub runner。
- 主控核原XML/JUnit集合、JAR、main-only三層與exact restore；implementer逐entry名稱/bytes比Task1完全相同。M3固定owner的255原件由主控及reviewers重算無差；manifest SHA `020F1F2F2C0D58971FE5D4612EE1A4BAFD429B8F60AE0350D2F1EC52C718C3D8`，補充audit SHA `0E733BC1EEEFA690EA74E998D37971E12B18AE7BC2B31F3903490C3D186CF4BC`。
- 106當次logs、2420 WARN/ERROR皆具名分類，worldgen例外0；新增Bat UUID重複一次與2059ms/41tick延遲一次，相關phase均PASS，保留為觀察，不宣稱受控或已修復。完整原行／原因在SDD report及audit。
- 首次full `run-all-9333c7196eaa4631ac99182b5343f025` 在JVM啟動前失敗，原root/日誌與exact restore保留，不採GREEN。必要子環境差異未單變量證因果；未改hook/全域或殺daemon。首啟動誤建的8空目錄經canonical/非reparse/empty/time/owner核對後，可恢復move至 `task-02/first-launch-artifacts-4f3146759827477b8596d498214ec07e`，preservation為8 moved、deleted=false。
- 獨立spec `/root/m6_task2_spec_review` Approved C0/I0/M0，SHA `998EE4E6A73DA838A40BD55F798B5666BACC7A9C2800F1BC745CBEC04C1FD23C`；獨立quality `/root/m6_task2_quality_review` Approved C0/I0/M1，SHA `297A9762A5A833ED2BEF7E7A3558409563C3E9541ECEAB59D22A9D73EBDE13BF`。主控已完整讀回。Minor M-1：absent案例補直接Test-Path存在性斷言及observedExists；本次實際四個路徑皆不存在，依規則交Task3 Step0，不另重跑Task2。
- 終局 report SHA `D8CE35B30021EDFA178CBEC50C8ACA4F6E3793E2A20AA980F84337DE3FA8E5EA`；diff SHA `B78BE2549D3FE7BD7002FB77C4655749CE48B1F1452DDE60C72793A2286E25A3`。結果文件採計98ea115，不冒稱docs SHA重跑runtime。Task2完成；未push、遠端CI／人工smoke／main／tag／退役未執行。

## Task 3 前置計畫補充（2026-10-25）

- 已核四scope矩陣漏列M4GameTestBoundaryProbe owner：它原本對非M5 GameTest註冊catalog/world不變快照，不適用後續M6 Universe配置。補入Task3 Files，exact M6略過M4/M5 observer，混合scope拒絕；fresh M6無M4 artifact/M5 suite-end，既有三scope由full守住。不改production/spec。
- 另一全新SOL `/root/m6_task3_plan_check` 狹義review Approved C0/I0/M0，報告SHA `22AE0B840CD1E9BF2A696BEB03B353C0A69757AC59EADAEB25B32FBF0125393D`。主控已讀回；這是必要owner補足，未宣稱Task3 code或runtime通過。另將Task2 Minor列Task3 Step0。

## Task 3：本機實作與雙 review 完成，CI gate 待驗（2026-10-25）

- range `f4b5ab1..ac085c8`，最終 code `ac085c8c2fdaaf9d3fbddcb7cc1affd305eb4602`；五commit含初稿355648a、path/ignored修正64b9e8d、初deadline b773f12、idle邊界5df251d、Main CI接線ac085c8。16檔限testmod／harness／build／workflow與核准plan owner，src/main/client及版本未變。
- 新M6Compile／M6JUnit／M6GameTest與m6-focused入口、四scope exact classname|name、多case同batch、專用fresh M6 run、tick10ms及37項oracle。M4/M5 observer不混入M6；testmod仍不進release/sources。Task2 M-1已補observedExists與真正存在性斷言，原Move-Fresh行為不變。
- 真bootstrap與scope／JAR／mixedflags／nullBatch反例保留。第一修正的world junction、首次ignored寫入、deadline自然exit0誤PASS均有原guard RED；兩fresh修正review仍找到idle退出／輸出恢復邊界（各C0/I1/M0）。第二修正以真Invoke-Gradle重現quiet-exit、late-output-live及慢取證誤FAIL，改在drain前／poll後／退出後觀測，OS ExitTime封頂；7個process-only案例不冒稱Minecraft。
- 路徑守門允許實體empty/absent，拒完整target/祖先reparse、非目錄、非空world與未證I/O，輸出先驗ignored。原非ignored副作用9檔經hash保全搬存；Windows symbolic-link EPERM與原本可拒的dangling Junction不冒稱RED。所有原失敗／舊full根保留，詳終局report。
- Windows CI Main-only補充經另一fresh plan review C0/I0/M0，報告SHA `459C320F892C5581BB53161BEE10FD61FEFA4C9C237CAB85C545D579CFD16313`。薄wrapper沿用既有Main／三層oracle，relative bundle、同SHA與17原件hash；Windows獨立7分鐘、reserve8，job90/M6step15不變，always-upload；Ubuntu不加Main，Task12 native job未提前。這是已核初值，非最壞IO時間保證。
- compile在各code commit前對同內容工作樹完成；同clean ac085的g2兩bundle：M6各2/2，waited26/25、TPS49.4/49.8；各37項oracle＋deadline7、Main-Ci真Run/Verify與1正7負Main receipt規則。兩Main runtime25.067/24.008秒，三層0、normalStop/exactRestore、同SHA/hash皆符；額外JUnit入口exact3。
- 正式full：`.superpowers/verification/run-all-77dcbab203604ecfbff7d94e79f009c6`，07:43:39精確 `END ALL exit=0 HEAD=ac085c8c2fdaaf9d3fbddcb7cc1affd305eb4602`；summary SHA `702FEEB64347E9AB517B245AE3383282236063D66799A8B769789528C0D325FC`。default179/windows89/legacy1/M560/M62（TPS48.5）/perTest56+1/JUnit666(0/0/2)/checkpoint17/M3 4+5/recovery24/collapse69/HALT31/MainOnlyPASS。
- full Main只跑一次，整wrapper/oracle約40.629秒；main-ci-receipt SHA `FE304FB91E83CDF211BBE354DD621BF52168B6BAEDD4DB9F9CF2425B0052D7F3`，17個原件主控及reviewers重hash相符。缺收據／錯head／GameTest冒充／testmod／stop／restore／缺原件皆由正式CI rule fixture具名拒絕，仍非遠端CI證據。
- audit-ac085c8：107 Minecraft logs與7 process-only分開；2418 WARN/ERROR逐筆歸屬、unattributed0、worldgen/pin-HOLD0。M3固定owner255原件hash、停止/lock/restore皆核。既有三GameTest scope/JUnit集合同Task2，新增M6兩case另列；release432entries/385classes與sources226逐entry bytes同Task2、testmod0。14核心hash主控重算無差。
- 首次整Task3 fresh spec Approved with minor C0/I0/M1，報告SHA `8B13968806BC981D4C5E83C4ACC876B9999AC64AD2D62A318A30DC55418B6FD4`；另一fresh quality Approved C0/I0/M0，SHA `39BACC339A238F87D47A153D9E9DCA8169D8D2B1394EC8B7B757EAE329633C76`。兩者皆獨立確認R2 I-1 ADDRESSED，主控已全文讀回。
- Minor M-1：m6-ci-gametest的單值scope名稱被PowerShell解包，`$names+'m6_first'`形成串接名稱，三個反例未真的加入獨立M6 case。正式filter與四run XML互斥已成立；依handoff規則交Task4 Step0補精準fixture，非阻擋、此輪不為Minor重跑相同full。
- 終局task-3-report.md SHA `C06A50AA623DCB8C64E0F4EE94ED69785DD43FDA45543B2DEE2392AEAFD123DB`；完整diff SHA `172DE25DC28F75ACC2705917945F7436AE3E12A4921DAD764CFC868190AA8C07`。本筆為純結果／接續文件，採計ac085，不冒稱docs SHA自己跑過runtime。
- **目前未push M6、未取得遠端同CI commit的Windows／Ubuntu結果，Task3尚未全部結案。** 下一步正常push feature並讀回實際兩平台XML／pace／JAR／排除及Windows Main原件；C/I及此外部gate清零後才Task4。人工、milestone整分支、main/tag/退役均未執行。

## Task 3：實際雙平台 CI 完成，接續 Task 4（2026-10-25）

- 正常push `codex/m6-universe-generation` 已成功，遠端ref讀回 `4d8fc66b1dfe6f1ac573c9b078766793982c6e3e`；相對本機已驗code ac085c8只有5份Markdown文件，沒有source/test/build/workflow差異。主checkout當時clean/main0d05f88，m5仍a2d2b40；未合main/tag。
- 實際 [GitHub Actions run40144948603](https://github.com/Ben513242/QuantumChamber/actions/runs/40144948603)：Ubuntu build job121024188392、Windows windows-native job121024188613，同CI SHA4d8fc66、皆success。已經GitHub app讀全文job logs與原artifact，不以本機fixture代替；未重跑此CI。
- 三ZIP比GitHub digest及upload log相符：Ubuntu `DBF46CC2C7F8C3A59B93D518F3153A5AEE0E7070D5FB8FCA7AD2A2EBB123CBBC`；Windows `147815827481F927ACC29047330A82BA567C7931D253D90CBF7EC23F7E4CE08B`；JAR `FA427C26197B18FB67BB886B134686ABDD37166F2EFC9D95DAB56134020C681C`。安全解壓669檔逐一hash/size核符，mandatory missing0，未執行artifact內程式。
- 各平台四scope XML為179/1/60/2，exact identities對scope與local ac085；bad/duplicates0。JUnit均666：Ubuntu638 pass＋28 Windows-only skip，Windows664 pass＋2 NonWindows skip。Ubuntu default89個與M5一個_windows案例為平台guard complete，不計原生正向覆蓋。
- 真wrapper8.8/Temurin21.0.12.1；M6 pace Ubuntu waited29/TPS36.3、Windows waited16/TPS24.0，minTick10且結束行各恰一。Ubuntu真SymbolicLink/selftest37與native UNSUPPORTED、nativeExecuted=false；Windows真Junction/selftest37＋process helper7。Task12 chains未提前採計。
- Windows Main17原件hash/sameSHA/Main mode/PID2780核符；Fabric mod list43、runtime argfiles/DLI、exact classload皆無testmod/clientProbe；save/normalStop/lock/exactRestore/worldkeys/catalog成立。API Main step65秒（execution51.466秒），低於已核7分鐘初值，只是當次量測。
- release432entries/385classes、sources226，entry集合同；Windows release與local整JAR SHA相同，Windows sources payload相同但226timestamps不同。Ubuntu release11個JSON、sources193個文字entry僅CRLF/LF；class bytes全exact，換行正規化後無其他payload差異。
- 9份實際server log共212 WARN/ERROR均歸屬；Windows pin256來自受控第257pin案例，不宣稱零pin warning。另Ubuntu12筆既有SessionTransferFaultMixin annotation warning、兩平台Node20 deprecation與Gradle8.8 notice已記錄；無未歸屬runtime例外、worldgen/CME/NPE/OOM/hold。沒有順手升級版本。
- 全新SOL唯讀CI auditor `/root/m6_task3_ci_audit` PASS，主控已全文讀回；報告 `task-03/ci-40144948603/ci-audit-report.md` SHA `A2A6094B83322D2CFAF75E98903FE6D0E5276AA9F8EDB23A3807FA56D65D700D`，結果JSON SHA `3BC674AA612465AC64AA6E8E39F9567963185F55C34ACCE76791D8FA7414A2D9`。原ZIP/manifests/原API時間/selected摘錄及完整joblog讀取歸屬均在該root；空jobs-timing.json未採計。
- Task3所有必要本機／code-review／遠端CI gates已完成；spec M-1已明列交Task4 Step0，沒有未關C/I。此後純狀態文件採計code ac085／CI4d8，不為湊CI次數另推；待下一次正常功能推送合併文件。Task4尚未實作；人工gameplay、milestone整分支、main/tag/退役仍未執行。


## Task 4：Stronghold invocation state完成（2026-10-25）

- range `170d2fa..4485a92`，最終 code `4485a92544df83d1d2337b4d8358451c0c040013`；Step0 `bf84d26`、窄修 `70ec295`、共用fixture `4485a92`。production僅Stronghold整輪monitor與註冊；不改RNG/原版算法，未碰Fortress/EndCity/seed/family。
- Task3 M-1：三個跨scope反例改為實際兩case，合法case保留、missing空、唯一extra=m6_first；受控原guard RED成立，selftest37→40。此改進不改正式filter。
- 固定1.21 native JAR仍C3D5432D0538D7750B985E42788484BEE4C63BBFACD7CADD496E940C83DFCEC4；完整StrongholdStructure.addPieces含init、piece遞迴與PortalRoom重試。已核vanilla高度getter無chunk/future/server等待，沒有整輪再入；JVM monitor可重入且例外釋放。此結論限已核vanilla caller，不承諾任意第三方hook巢狀整輪。
- 有效原版RED：`focused-r1-red-native-108d56fd056c45809940f36c6299536e`為3/1failure/0error，具名STRONGHOLD_SHARED_STATE_CROSSED；count/list/weight及NBT有交叉。`focused-r1-place-red-exact-box-9d57524d4f2a4c0081bb582c63b2d1ce`兩真世界/真正server-thread dispatcher的兩case均具名失敗。原setup API/box失敗保留、非RED。70ec移除mixin註冊mutation重現具名失敗並finally exact restore。
- 固定seed8128/chunk32,32原版98pieces，完整NBT SHA `a80452213409b05c039da03127cc9864ae6f779722541e1256336131bb9bd21a`。並行測試使用真原版入口/兩個實際ServerWorld與受控worker；不冒稱自然worldgen executor或重現CME。BLOCKED核production monitor identity/owner=A，另驗例外後同worker與不同worker。
- 主控發現兩份158行helper逐字重複後，核准classes-only fixture共用與plan owner追加；build僅test compile/runtime加testmod.output.classesDirs、不帶resources。JUnit枚舉40manifest/testmixins/modlist確認邊界；不是main-only。4485相對70ec production diff空。
- 新4485 compile `gate-task4-shared-fixture-compile-2a3b92970c8443ec971da00438763544` PASS；同clean pin兩focused `focused-shared-green-a-22d7808cbec84984a36753c7b968b74d`、`focused-shared-green-b-fe2907cbd2844440b1d8e116ae341d62` 各3/0/0/0；`focused-shared-world-green-6c980c1fca9249a8a183fd7d189947bf` 2/2、真place result1/正常停止/原世界還原。
- 完整full `.superpowers/verification/run-all-5c211bff7c78488b8e22ddc4575258f4`，09:55:19 END ALL exit0/HEAD4485；summary SHA `7AE0EA0B8C864245101757F743E59E8A5742E5DF5ACBCC67D93619E7FAD3DB27`。四scope179/1/60/4；JUnit667pass+2合法skip，舊666集合不減；M3 4+5/recovery24/collapse69/HALT31。Main17原件三層/stop/exactRestore、五world swaps、owner0/locks均符。
- release433entries/386classes、sources227，testmod0，新增僅mixin與註冊/refmap；107logs2863 WARNERROR全歸屬（原有2418+place445），worldgen/pin-HOLD/未歸屬0。place RED444/focused443/full445差異座標皆從保留Anvil讀回為wall_torch/air，符合原版place的world RNG；沒有抑制訊息。既有compiler warnings未擴修。
- 全新獨立spec Approved C0/I0/M0，報告SHA `3D6806A9F3C3B8B01469005382FB727FD195969F15E116011B79E1B60BCCAB00`；quality Approved C0/I0/M1，SHA `EEF08095042EB2914AED0D2F5814B56A6855745D28702ABFA2DF0B398F4F288D`，主控已全文讀回。Minor：測試worker啟動後的await應納finally，多worker逐一嘗試join並保留主要例外；交Task5 Step0，不改production鎖，無待關C/I。
- clean-pin的當時證據包括session工具讀回；runner有HEAD但沒有各次porcelain檔案，B沒有獨立clean receipt。主控09:15原工具輸出逐bytes摘錄已保存，spec reviewer直接比原session三行一致；不拿現在clean補歷史。後續新launcher保存當時前後HEAD/status，不重跑舊gate。
- 實作者終局report SHA `926FF0F39D04AFD491E151A535884F654EF3465479E5F4BEB499051392ACDF67`，完整diff SHA `E1AFD05DE95DAF8A6CE8FF9DBAA2CFB5766D80EFEE7A05E00FEA238A967E11DE`。主控另核39核心/source hash、4XML、Main17原件。本段結果文件採計4485，非新docs SHA runtime證據；未新增遠端CI/main/tag/人工/退役結論。


## Task4：實際雙平台 CI 結案（2026-10-25）

- 功能分支已正常push並讀回165b0111a4ff00fa5c68ed44a56b92c84bcfd039；相對已驗code4485只有五份結果/接續Markdown。實際[run40153911285](https://github.com/Ben513242/QuantumChamber/actions/runs/40153911285) attempt1，同head的Ubuntu job121051034307與Windows121051034451均success；未重跑。
- 三ZIP digest與GitHub API/joblog符：Ubuntu F59B71DBF5D2718AE79BE2A085A97B9D6920CA3AF1FC67B6FDF5708AB572533D、JAR E73B8CB86728DF72B5BE3A6011304FB18D7D31A245C5E68D61E38AA49A9FE6AA、Windows21186484503DBAAD6F1A6F4F6D59853EF952CDE9E0A32ECDC7B573B1CE5CBAE5。安全解壓675檔逐hash核符，沒有執行artifact內容。
- 兩平台四scope原XML179/1/60/4 exact；JUnit669，Ubuntu641pass+28合法Windows-only skip、Windows667pass+2NonWindows skip。Ubuntu原GameTest平台略過與M5唯一_windows的early complete已核，不當原生正向覆蓋；M5 delta Ubuntu59/terminal55、Win60/terminal56，皆連續/confirmed。
- Stronghold兩平台固定98pieces/a804完整NBT、正式monitor owner、真world/place result1/state與NBT不變皆有原件。M6 pace Ubuntu waited50/TPS27.3、Win38/19.5，minTick10；selftest40，Windows process-only7，Ubuntu UNSUPPORTED/nativeExecuted=false。Task12 native chains NOT_REQUESTED，不冒稱已涵蓋。
- Windows真Main-only PID4604，17原件hash/samehead、43mod list/實際DLI與argfiles/classload三層均符；221個自家class/lambda均可對release owner。兩次save-all、normalStop、worldkeys/catalog、exclusive lock與exactRestore成立。
- release433entries/386classes、sources227，無testmod。Ubuntu相對本機class bytes全同，11release resources與193sources文字只CRLF/LF；Windows release完整payload/整JAR同本機，sources僅新增Stronghold mixin28行的CRLF差異。行尾正規化後無其他漂移。
- 九份實際runtime logs共1108 WARN/ERROR全部逐path/line/block/batch歸屬，M6原版place Ubuntu448/Win444；Windows Main實際4筆offline警告（oracle排除後warningCount0，不等於零原始警告）。受控M5 H2 IO、policy/piston等依當次來源核；沒有新worldgen/CME/NPE或pin/HOLD異常。既有compiler/Actions棄用提示保留，未擴修版本。
- 全新SOL CI auditor PASS C0/I0/M0，主控已全文讀回。task-04/ci-40153911285/ci-audit-report.md SHA93C1E269CB1B504F6B058727B2D6FA56D4E694B2F9564495C5DE4C1B8C9DC818；result SHA26BAFFD99F676790B45FC6CF76639BE6349420EA710D6412F0F2723E0FDDBB73，independentChecks全true/pending空。
- 此CI結論僅Task4 code4485/CI165；不代Task5 code43edeb。取得結果時Task5 full在codefreeze，先存ignored原件，本段待其full/review後才併結果文件commit；不為文件重跑runtime或填充CI次數。main/m5、人工、里程碑整分支、tag/退役未動。


## Task5：Nether Fortress invocation state完成（2026-10-25）

- range165b011..43edeb1，最終code43edeb12bddacc0014e8af85df74c2e49e9000b7；05f2bda為Task4 M-1 cleanup獨立測試修正、43edeb1為Fortress窄修。只六個允許路徑，production僅Fortress整輪monitor與註冊，不改Stronghold production/算法/RNG/build/EndCity/seed/family。
- Step0把worker啟動後await納清理範圍，先release、逐一有界join、保留primary/suppressed。原helper RED為5tests/2具名失敗PRIMARY_LOST/JOIN_SKIPPED；另補真worker前屏障失敗回歸。dirty開發GREEN不當clean證據；最終Stronghold A/B各6及full涵蓋三個新增cleanup case。
- 原版JAR仍C3D5432D0538D7750B985E42788484BEE4C63BBFACD7CADD496E940C83DFCEC4。Start兩list引用同static PieceData；完整addPieces涵蓋重設、所有擴張與shift，獨立monitor保護兩組count。已核vanilla沒有整輪再入/持鎖chunk-server-future等待；限制不外推第三方hook，不宣稱自然worldgen executor壓測或CME已重現。
- Fortress fixture每invocation用原版9參數Context的createChunkRandom/setCarverSeed，不沿用Stronghold的顯式random0或重用耗過的random。原版seed8128/chunk32,32=155pieces，完整NBT0c60781b533c2b0d82e2f36835d9174a58cc1b72a82cf5035d1cf97150b0149b；Stronghold98/a804原基準保留。
- 有效RED：focused-r2-red-native-f02801d037484bcdbbbd347f88983b5d為3/1具名FORTRESS_SHARED_STATE_CROSSED/0error，bridge/corridor污染、A NBT0c6078→f76ca1；focused-r2-place-red-native-249ac7287fae436d9c5b016403d5b070兩真world/place案例皆具名污染、原世界exact restore。未把靜態lookup錯誤當runtime RED。
- compile gate-r2-compile-registered-7df5279cd5764e868ab7f0e5d616b376 PASS。五輪同clean43edeb：Fortress A/B各3、真GT2、Stronghold A/B各6，bad0；每輪原始pre/post HEAD/porcelain現場保存，不補歷史。完整root/XML hash見task-05/focused-full-readiness.json，主控直接重算五XML並讀pin原文。
- 唯一full .superpowers/verification/run-all-01d6cb27560441ba9b12bd95b40be49b，11:19:59 END ALL exit0/43edeb；summary SHAD631F2965728E8FCC5E9BEBE78672DEA588D537FA25350224BF3733BFE7A6D28。四scope179/1/60/6，JUnit673pass+2合法skip、舊669全保留只增6；M3 4+5/recovery24/collapse69/HALT31/MainPASS。五swap/原M6world23檔pathset/hash/長度/lock/stop均符。
- release434entries/387classes、sources228，只增Fortressclass/java與註冊/refmap，其餘payload同Task4；testmod0。M6兩個正式monitor物件不同，各有真place與雙world witness；full節流waited63/TPS14.2。
- 107原logs共3660 WARNERROR：3658已分類（含原版/place1240=Fortress791＋Stronghold449），另兩筆觀察如下。Fortress791與原版RED/focused多重集合exact；Stronghold比Task4 full445的28差異座標Anvil全wall_torch/air，四regionhash保留。沒有新增worldgen/pinHOLD異常；六compiler warnings與Task4逐字同。
- 新觀察保留：M6 raw:894單次2760ms/55ticks延遲，Fortress PASS後/Stronghold前，M6 6/6/pace/stop仍符；native-save/recover-after-write-fail log:76 Bat UUID重複，原receipt/stop/lock符。source與上下文已留，但根因、實際重複來源/影響未單變量證實；非受控注入、未宣稱已修、不為洗掉warning重跑。兩席獨立review均判非阻擋Minor，保留後續觀察；不是根因已關閉。
- 最終liveMinecraft0，保留idle daemon25796。初始34120查無、退出原因未知，未kill或重啟補造。full當時前後原文與最後Git同43edeb/空porcelain。主控核四XML與Main17，完整192原件hash由實作者核且供fresh review。
- 全新spec Approved C0/I0/M2，報告SHA586C3B1B1E7E73F9AC9D2322067B4B43BBDC29F7EC5806BABC790EC34EF12E3A；全新quality Approved C0/I0/M2，SHA021EDE3C40530CFFFD82C9C1EECFA7DF3FB37BC094EF22E09478C77BDF914E63。主控已全文讀回，Task4 cleanup M-1關閉，無未關C/I；兩席M1/M2是同兩筆lag/Bat觀察，去重後共兩項，非四項。
- 觀察保留於tracked note與後續native／自然多世界驗證；若再現或有entity遺失症狀再依原nonce/世界證據作有界調查，不直接擴張Task6 production，也不為清空警告重跑。Task5結案，接續Task6。
- 終局report SHA809AEE8181174144084CFCE1F554D5F7AC72A1B153B2293C8F358B08CDC5D6EB；manifest SHACC6DB49928DC8D0BFE0432C4952D74B81FB597D3E9B833AA08F940E147C60D18；完整diff SHA4B5DCE5A352EAB56A111CA257B08D1FB3CDA5F9B731A86DB977256CA8AFCBB6D。結果文件採code43edeb，不冒稱新docs SHA有runtime或借Task4 CI165替本task背書。未新增main/tag/人工/退役結論。


## Task5：實際雙平台 CI 結案（2026-10-25）

- 功能分支正常push並讀回d9e570545a6c9bf5186bb4fc154b9f848d196281；對已驗code43edeb只五份純結果Markdown。[run40158887195](https://github.com/Ben513242/QuantumChamber/actions/runs/40158887195) attempt1，同head的Ubuntu job121066035452與Windows121066035350皆success，沒有重跑。
- 官方三ZIP SHA：Ubuntu5EEF559B0F1976769DD5B7F68ACEE071E50BC8AC38976D8B2EF783D882BDC6B4、JAR3DEC5EF1CA3E45BBE17FF4B44B6593EEAF427C5B674924889DD3CCEAB7E11B9E、Win18274B9E93A6B11B4D1191D34C51D8660421C320D74E78FB40F2917B0521E9D1；681原檔逐hash/length/ZIPpayload皆符。
- 兩平台四scope179/1/60/6 exact；JUnit675，Ubuntu647pass+28合法skip、Windows673pass+2skip。Stronghold6/98/a804、Fortress3/155/0c6078與四真world/place案例皆有原NBT/monitor/owner/stop正向原件；原版算法/RNG基準無漂移。
- M6 pace Ubuntu waited58/TPS12.4、Windows49/7.0、minTick10，selftest40與Windows process-only7符。Ubuntu平台略過及M5唯一_windows early complete已核，不算Windows原生正向；Task12 native chains NOT_REQUESTED，本CI亦未跑本機M3/recovery24/collapse69。
- Windows真Main-only PID6008，17原件hash/samehead、43mod list、DLI/argfiles/classload三層、兩次save-all/normalStop/exclusive lock/exactRestore/worldkeys/catalog成立。release434/387classes、sources228無testmod；兩平台class bytes同本機，Ubuntu11resources/193sources與Windows兩mixin.java僅CRLF差異。
- 當次九份runtime原log共2694 WARNERROR逐來源歸屬，M6 PostProcessing Ubuntu1237/Win1239，另properties/eula及lag；Main四筆offline不等於實際零warning。既有受控IO/policy/rollback等按當次batch/stack核，不以舊full數字代入。
- CI O-1延續Task5 M-1觀察：Ubuntu原latest:913 lag3444ms/68ticks，在Fortress REAL_WORLDS/canary後、Stronghold前；Windows:897 lag7209ms/144ticks，在Fortress placePASS後、two-context前。秒級preflight→place粗窗口不是分段/單變量量測，不能歸因鎖或預載，也不宣稱性能/吞吐已驗或警告已修。本機Bat/recovery未歸因觀察不因本CI未跑相關chain而排除。
- 全新SOL CI auditor PASS C0/I0/M0＋非阻斷O-1，主控已全文讀回。task-05/ci-40158887195/ci-audit-report.md SHA087821AAE3BDB99475F0792457EC548C09A1F7C3B1400F1C6206A9A9531EC504；result SHA898F2A368671D632592D6732CD991E95654E8ECF9E87A8301043126BAE3C2411，independentChecks全true/pending空。
- 結論只對Task5 code43edeb/CI d9，不代Task6 e077。結果取得時Task6 full已凍結，先留ignored原件，本段待其full/review後才併結果docs；不為文件重跑runtime或填CI次數。main/m5、人工、里程碑整分支、tag/退役未動。


## Task6：End city invocation state完成（2026-10-25）

- range d9e5705..e077be6，production候選74943e83601c3a514a9148a7b791b5611943a9db、fixture修正e077be6a0918d7c2e56056e23363b0e2cde1a2cd。八tracked檔含主控核准plan owner單段；不改Stronghold/Fortress production、seed/profile/family/renderer。
- 原版EndCity五參數generator整輪包四個Part.init與全部遞迴；模板有同步resource/disk/DataFix鏈，採invocation-private ThreadLocal stack，不新增整輪全域鎖。新增EndCityBridgeStateMixin／EndCityInvocationState兩owner經fresh方案review C0/I0/M1，SHA CDD62677A952B7AF4F32661BDA0DAD696B96C92DAEFF39E53C5472B27C7C929E。
- root push false、finally回父/空時remove；無withInitial，無frame查詢getnull即remove，不留空stack/entry（允許JDK瞬時null）。三字段init PUT/create GET+PUT各require1/allow1/Coerce/Shadow/remap；保持選船但collision拒絕的原語意。無frame只沿原field，不承諾第三方root/Part混用final共享flag相容，方案M1已限縮Javadoc/README。
- 真20模板byte/hash/尺寸/DataVersion3953，以Schemas.getFixer+manager.createTemplate原版loader。Context/carverseed/rotation/offset+7按原版，JUnit固定y80不冒稱terrain eligibility；session唯一nonce/finally close，失敗保留不刪。
- 原版完整loader RED：focused-r3-red-template-loader-e9dd1a9bf1cb4877965d0fe6752dcb62/gate-r3-red-template-loader-10e85cf7edd64b719e12b737732a2b2c，XML SHA B44A7CC4B3D3E091A0E99C675CE7DAD8EFA52EF0316830B279B83374C1DE0116；4/2具名FAIL，並行flag true→false使A94pieces/1ship變108/2，nested父flag亦被污染，非setup。初A無船timeout原root另留不當RED。
- A seed481516/chunk-32,-32/pos-505,80,-505 原NBT d06329e10c9031569b17660abba5d85069ee705fc05145321b5b99daa543925a、94/1、rng-1076416987575963468、rot90；B8128/chunk32,32原NBT33a33d1ffe909140fd5cc5f6bed8a2d06e130b3ffbe924a45f0064a349fb2c13、8/0、rng-8328947346988894843、rot180。未為通過改基準；RNG wrapper只委派不加抽樣。
- 74943初GT5/6為EndCity固定位置高度<60/無pieces，在worker前bbox失敗，原root/restore保留。e077純fixture修正用81點有界外島grid問原版hasChildren；第7點1024,80,1792合法，exactbox預載、同座標真place1且A仍暫停時command完成；A完整NBT/船/RNG保持。production diff空，非繞過eligibility/skip。
- 最終e077五focused：EndCity雙6、三raceGT6、Stronghold6、Fortress3，全bad0；每輪現場pre/post git exit0/expectedHEAD/0byte status。原74943GREEN（含A缺status鏡像的真pre-JUnit/post時序）只作歷史，不背書最終。既有NativeHarness本文不變、bbox抽函式正規化exact；舊兩JUnit各一回歸，完整full再驗。
- 發布remap靜態核major65、匿名class_3342$3/field_14394、root14679/init14688/create14687及兩註冊；後續以最終full發布原件核內容。主控已讀三production、fixturefix與五原XML/pin/GT原log/restore。
- 首輪full深SDD/pin/run-all-92807788b5214113ac1ff5c99fb276b1：runtime全過但13:14:58 CI-rule Process.Start失敗，outer14684 exit1，無summary/ENDALL；不拼接改判、原世界5swap/locks皆恢復且root保留。原cwd280字實體存在；同pwsh/args/環境process-only v2/v3兩次Win32267，147與最長CIlabel投影218均exit0；正式CI-only短cwd178 smoke通過。此控制不代full。
- 未改source/global設定/registry/PATH或搬動失敗root。主控另放行預設短.superpowers/verification的新full run-all-44da51f56266477882047b03b7fcfc60，metadata pin89f91另存SDD，不作EvidenceRoot；同clean e077五focused沿用。14:10:16新輪END ALL exit0/e077，summary SHA D885B7EDFB574D094C76FB7E7DFE39BF45BFAA46E4B3092AB74315AE01186988；四scope179/1/60/8、JUnit679pass+2合法skip、M3九/recovery24/collapse69/HALT31、11CI規則全完成。release438/391classes/sources231/testmod0；新Main PID16228三層/17原件/stop/restore符。主控已核新原summary/四XML/prepost同pin；完整warnings/owner以終局audit再核，不借原失敗輪。
- 新短full107原logs3652 WARNERROR全逐來源核對，包含place1231（Fortress791、Stronghold440）及三lag：M6:893 2819ms/56ticks、w4-restart:101 2726/54、w10-crash:97 2001/40，分別綁當次receipt/stop或HALT77/lock；不把M6時序套其餘phase。controlled IO可識別、worldgen/pinHOLD0，原M6world23檔與5swap/7locks均符。
- Task5 lag M1在本task focused再現2577ms/51ticks，Fortress place後/EndCity前；原深full亦2763/55。唯讀lag-triage比較原行/舊preload與NativeHarness原文一致，沒有分段/單變量因果或吞吐保證；不稱已修、不改logger/timeout/production或為pristine重跑。新的短full依當次原件另報，不借舊數字。Task5 Bat M2未因本task某輪未見而關閉。
- fresh spec Approved C0/I0/M2，SHA5614D5425B1F0F500ACEE45EFB6D36F7C3DC1827CF4C624CFF5078E4E8784DEE；fresh quality Approved C0/I0/M2，SHAB0EAD6A3F59589873A40E55A17B177B8958505E3C7E676C0587376D217BEF167，主控全文讀/hash核。兩席同兩Minor去重：M1三lag未歸因，M2深EvidenceRoot的CI cwd支援缺口；不因全綠關性能/Bat觀察，M2交Task7 Step0先triage最小正式入口前置診斷/支援範圍。沒有未關C/I，Task6結案；Task7僅診斷，不先宣稱獨立seed功能。
- 唯一終局report SHA0F0ED58B604555DF6B2C33B28D601B24FBB90CF43EBBFB1A562B9F99EED8AE1F；新短full audit SHA3496C5E3E695A69B9B5F646A49E10FAA82DA5B1F249B125DA33CEA020006AB00；manifest（44evidence+8source）D3CEA1D6EFF3C8477514BFC37EF334BE021AC9CD18351B35124C47DD45746B3F。
- 完整review diff SHA A6677A3783782FB93C68FF94D23F5843DA3C9B1C61EAD89782A82F65A6B13CEF。結果文件採最終code e077，不冒稱docs SHA或Task5 CI d9背書此候選；main/tag/人工/退役未動。

## Task6：實際雙平台 CI 結案（2026-10-25）

- 功能分支已正常 push 並讀回 71f51f741d75e566281a64ac111aa7719170e883；相對 code e077 只有五份 Markdown。[run40169884283](https://github.com/Ben513242/QuantumChamber/actions/runs/40169884283)／attempt1，同 head 的 Ubuntu job121098398199、Windows job121098398061 均 success，沒有重跑。
- 三 ZIP digest 與官方 API 核符：Ubuntu629BF00916BEEF6D024107015803E9A26B1972173FFDE48D33516ED828A30D0D、JAR ADB4C674AEBBC4FD4142AC15DB24DF38AB341842118AAA5F79FBC32E1712C084、Windows035FBAFD1C83827528F87F2317670FE3314C1E6C14AEE50E4DC8AD164FDAFF81。687 檔逐 hash/length 核符，無 symlink 或 artifact 執行。
- 四 scope179/1/60/8 exact；JUnit681：Ubuntu653pass+28合法skip、Windows679pass+2skip。三種race基準、EndCity6JUnit/20原版模板、完整NBT/船數/RNG與合法place第7點均有原件。
- release438entries/391classes、sources231，無testmod；兩平台class bytes同本機，只記錄CRLF文字差異。正式remap class/refmap與匿名field14394/root14679/init14688/create14687相符。
- Windows main-only17原件hash與same-head/PID2716/parent8060核符；43mod list、runtime CP/argfiles/DLI、exact PID class-load三層隔離、兩次save與normalStop/after-lock/restore成立。locks-running快照沒有server PID/時間，且由Gradle launcher存活時覆寫，該輪exclusiveAvailable=true，不能宣稱它證明server執行期間持鎖；after快照僅證明釋放。
- 九份canonical runtime log共2686 WARN/ERROR逐行歸屬；lag於Ubuntu3529ms/70ticks、Windows5218ms/104ticks再現，均在Fortress/place後、two-context/EndCity前。根因/吞吐仍未證明，歷史Bat/recovery未覆蓋，不因此關閉。Task12 native chains未執行。
- 全新SOL CI auditor PASS，11原件核對全true，主控已全文讀回。task-06/ci-40169884283/ci-audit-report.md SHA037BAE124D6AE828B640236C09935AAA89B29170D6491F6D2D7FD41824F75BB9；result SHA E1C23525E632943366776DF6DF1ED2478AFAF037D4B57CA021BF6F6CD894D646。
- 此結果只對Task6 codee077/CI71f，不代Task7。結果取得時Task7 full凍結，先留ignored原件，本段在其fix完整回歸與複審完成後納入tracked；main/tag/人工/退役未動。

## Task7：原生 seed/settings 診斷與安全自測完成（2026-10-25）

- range71f51f7..245051e，最終code245051e87af563b778b79bca27e6706be30848f2。5fd9142新增probe/CI cwd前置守門；0f3574e修正診斷metadata語意；245051e關閉quality I-1。共九個允許路徑，沒有production/client差異；發布payload維持Task6。
- 正式tracked入口為testmod M6UniverseRecoveryProbe／Loom m6UniverseProbe、m6-universe-probe.ps1、focused Probe與run-all AllEnabled。首版只有native-seed-diagnostic，兩個新JVM的baseline/save-reopen，m6ProbeSchema=1、expectedMode=DIAGNOSTIC。無M6參數安靜停用，partial/unknown/mixed拒絕；M4/M5未傳extra時UTF8/NUL邊界arguments byte exact。
- 兩個schema1 U由正式catalog checked提交/讀回與backend物化，重開由正式lifecycle恢復；另有canonical O/N/E控制。量測getSeed、StructureLocator/placement seeds、BiomeAccess hash、NoiseConfig/biome樣本、properties delegates，以及用真正object ==分組的nonce專用randomSequences連續值。有界worker僅唯讀採樣，不稱自然worldgen壓測。新native root永留、沒有移動既有world；完整回歸的五組fresh/restore另驗。
- **語意界線**：schema1的allocateOverworld第二參數是ordinal，沒有seed權威。原synthetic seedHints未傳catalog/factory，已移除；主控更正先前要求hint互異的假設。功能RED只觀察schema1沒有獨立seed/sequence能力，不是factory忽略seed入參。Task11另用真正schema2 root與FUNCTIONAL鏈；診斷綠不代表隨機種子功能完成。
- 原0f具名功能RED與雙DIAGNOSTIC GREEN完整原件見task-07/fix1-focused-full-readiness.json。RED/A的expectedHead標籤錯留5fd，但raw前後HEAD/summary皆0f、status0byte/git0；三輪原launcher缺pre-spawn guard。主控及兩席原review依實際原件採計，保留限制、不回補歷史。後來immutable pin/Node+PS guard的process-only反例只證新launcher；新GT8及ca76/full有自己的真guard。
- Task6深root9280保持FAIL。正常run-all在任何昂貴runtime/world前，依同一11-case table對等長cwd-map兄弟布局真Process.Start；不猜通用260上限、不改global設定。當前正常mode=None/REAL_PROJECTED_CWD；PreflightOnly只證布局啟動，不能代full。
- 原0f full ca76為ENDALL0；fresh spec C0/I0/M2（SHA B1BF9E3E96786E711483B80F166B0ABBD48F47E20AFFE05479FBF2EAC80921D3），quality C0/I1/M0（SHA C84C173772308E56CC0F51ECE76F01ADE265A7AABAAC231288DF6BFE7FA332ED）。I-1是deep自測若能啟動便進真Full/世界操作及遞迴；當次本機深cwd拒絕不能證跨環境安全，故當時Task7未結案。
- **I-1 fix round1（0f..245）**：所有自測child無條件PreflightOnly；非None受控模式只准與停止旗標配對。RootSuccess用既存磁碟根、StartFailure用owned evidence內未建立唯一cwd，仍真Process.Start，requested/effective與CONTROLLED_TRANSPORT_ONLY分列。None保留真布局檢查。150秒只是等待界線，逾時留PID/start/root且不kill/不續下一runtime；安全本體是停止契約。
- 安全RED在原0f真函式AST僅替唯一Start呼叫，檢實際argv缺停止旗標，fork前具名M6_CI_SELFTEST_RUNTIME_NOT_BOUNDED；child0/ownedJava0/run不變。原source SHA4BC710F6426D52A5B054372499B5BCEA80EE1897790FE851239009414B4AF5E1，函式C91DF72700E4571F3306149F5F8191A25FA0F3DD01D7C654D6DB000ED0721BE3。不是unknown參數/編譯錯或真進unsafe full冒充RED。
- compile gate-qfix1-compile-637e90481d2542a98c20441c97950124/parser PASS。245同clean雙focused：quality-fix1/green-a-pin-eefef957ad544a8d99c14850fdbb1fd7與green-b-pin-b5f4bd113af1472ab1ea9096f60d1630；兩輪AST adapter各真委派3次且全bounded，正式六case PASS，兩缺配對由正式純guard安全驗證。B另move6/owned8/optional9 PASS。原prestart/prepost/exit均245/clean/git0；完整索引quality-fix1/focused-full-readiness.json。
- **最終新full**：.superpowers/verification/run-all-efbd4990e8824a63bc93b458643877b0，18:04:45+08 END ALL exit0/245，outer42724自然exit0。summary SHA E69A1055D2691645B9FDFD5B54FF5AB7B2EFFABA7B0F3553A303760C91A4DFE1；新guard/prepost同pin/clean。四XML179/1/60/8、JUnit681=679pass+2合法skip；release438entries/391classes/sources231/testmod0；Main17原件/PID32016/43mods/三層/兩save/stop/restore；M3九/255原件、recovery24、collapse69/HALT31、11本機CI規則，全部本輪原件核對。沒有把本機規則稱為遠端CI。
- 新full的正式safety6與父None/真11布局都核實；native兩JVM23884/40936、不同startupNonce、catalog與reopen一致、控制組/序列連續/正常stop/鎖皆符，功能仍false。五restore實體逐檔hash/存在性一致（legacy60檔、M6原world23檔）；8鎖釋放，liveMinecraft0，保留idle29168。沒有拼用ca76作245的full。
- 本輪109logs/3659 WARNERROR全歸屬：fault/policy157、region1845、piston1、PostProcessing1232、lag1、offline412、journal-corrupt3、native-offline8；worldgen/pin-HOLD/未歸屬0。六compiler warnings與ca76逐字同。Fortress791多重集合exact；Stronghold447→441的20差異座標兩輪Anvil僅wall_torch/air，EndCity0。M6 log894的2773ms/55ticks lag未歸因，歷史Bat未關；不稱pristine或效能通過，不為清空警告重跑。
- fresh scoped spec I-1 ADDRESSED／新增C0/I0/M0，SHA96E35BBB0D69BAEF9DA5BF4A653C55837D5ECB48C22F472209C89D11E7AAFFF3；fresh scoped quality Approved／I-1 ADDRESSED／新增C0/I0/M0，SHA3FF2D7062D20F004AC7A763164CD89DC9D55E6F4F343447E4E67C456E5EE059F。主控全文讀回，並直接核127檔hash、原pin/XML/JUnit/Main17/native與safety原件；沒有未關C/I。原Minor保留lag/Bat追蹤；Task10/11的run-all owner/oracle責任已依兩席同意補入plan，不提前擴future鏈。
- native交接：getSeed()J持續讀全域；SCML constructor先凍結noise/placement，ServerWorld constructor另直接讀全域建StructureLocator；BiomeAccess需hashSeed，schema1 properties wrapper委派canonical、randomSequences實際共用。Task11須按exact server/key/catalog descriptor建立長期owner與設定，不替換全域SaveProperties，不只依暫時context。End dragon尚有canonical key+type創建條件及全域讀寫，交Task16。named merged/runtime common整JAR分開；9/10 class原bytes一致，ServerWorld只對相關方法normalized指令比對相符，不能稱整class或mixin後記憶體相同。
- 最新report SHA FDADAE2C3D69CF9A12F011845EFD8BFD10D9FD289E6DD754ED2B5066D4EBC21D；audit2F5D92DB6B09B1338185148656E35599AB05C8000DC37953259D468EEF40F9EB；manifestA4ECD3B94302FF47A40473AEEE04334B241958FA4B6ADD167E61DB7ABBB7D33B（9source+118evidence）。fix diff08682C2FB62D075974B197D49A7FC1E5C84B304D42B5797BC5372271A9F53CE9。舊report/delivery/manifest原bytes保留review-round0；目前report追加fix不冒稱舊hash。
- 本筆結果文件採計code245及Task6實際CI71f，不冒稱docs SHA跑過runtime或Task7遠端CI已過。Task7完成，接續Task8設定快照/provider API；獨立seed/profile/family、人工smoke、整分支review、main/tag/退役仍有各自門檻。

## Task7：實際雙平台 CI 結案（2026-10-25）

- 功能分支已正常push並讀回64d6317671b5e9152bfd9e088356d16175088b3c；對已驗code245只有五份結果Markdown。[run40184992145](https://github.com/Ben513242/QuantumChamber/actions/runs/40184992145) attempt1，同head的Ubuntu121143800814與Windows121143800620均completed/success，無rerun。
- 官方三ZIP digest/length核符：Ubuntu266193bytes／7BD1BE012B8F1818A21C7F73904D9F08051FDCA9CD12DD9A04FC586BDB47DC33；Windows4031616／37338C344C7052F8542429762B511B0DA143EA8F7195045D8DC1847303CBF51C；JAR1142601／020B2969E64FE608BADA49FB080A332F2BF5775DB916E5CD7C693F162EC13214。安全解壓687原檔逐hash/length，無symlink，未執行artifact。
- 四scope179/1/60/8與完整JUnit681集合exact本機efbd；Win679pass+2合法skip／Ubuntu653pass+28合法skip，Ubuntu_windows early-complete不冒稱Windows原生正向。三race基準、EndCity20模板/NBT/船數/RNG與合法place第7點均原件核符。
- release438entries/391classes、sources231，兩平台所有class bytes exact本機；Windows release全payload同、sources6文字僅CRLF，Ubuntu release11resources/sources193文字僅CRLF；未有testmod/gametest/fault洩漏，合法ModPresenceProbe保留。major65與EndCity匿名欄位/remap/refmap相符。
- Windows Main PID8232/parent7852，17原件hash/samehead；43mods、runtime CP/兩argfiles/DLI/exact PID classload三層無leak；兩save/normal stop/exit0/四worldkey/catalog一致。原server不存在、fresh保留、restore exact/deletedfalse、after-lock可exclusive。running-lock缺PID/時間且availabletrue，不當live server持鎖證明。
- 九份canonical logs2679 WARNERROR逐來源/phase/batch/cause歸屬；Fortress各791、Stronghold Win438/Ubuntu441、End0。Win lag6506ms/130ticks、Ubuntu2877/57均在Fortress後/Stronghold前，未有分段/單變量因果，不稱吞吐/效能通過。原六種compiler warnings重複於兩次compile，不當新增十二種。歷史Bat/recovery未覆蓋不關。
- 本CI未跑M3跨JVM、recovery24、collapse69、native DIAGNOSTIC2或本機新safety6；Task12遠端native job尚未新增。M6 pace Win109ticks/61waited/8.3TPS、Ubuntu101/80/15.2、minTick10，只證節流契約。
- fresh SOL獨立auditor PASS C0/I0/M0，12原件checks true/pending空，root全文讀回/hash核。task-07/ci-40184992145/ci-audit-report.md SHA B6ADA59DC981AC3F7EF0ABC3E0DC0CC298CDC10B87818907C4DDC88BD239E4B1；result SHA12D5732240E666468C30F1E039D9D6194E9537B43CFB4B6C2BE1105730ADB10C。初XML屬性讀取器誤取name的比較檔保留不採；final精確解析才採。
- 結論僅Task7 code245/CI64d，不代Task8 native gate/production、人工/整M6/main/tag；結果於本次契約/計畫更新納入tracked，不為CI次數另行重跑相同程式。

## Task8A：原生調查與 v1.1 契約採用（尚未實作完成）

- Task8 BASE64d，僅ignored原生調查／方案，沒有production/JUnit/testmod/source變更或正式snapshot寫出。最初native DimensionOptions.CODEC只輸出refs；Builtin lookup未綁tags會失敗。改以真vanilla datapack RegistryLoader與tags後得到部分typed graph，不能把key數當完整性。
- 獨立native review SHA546D733B65744A79778394A5DA258802F88DB9DA585CB99EE4AAE7E4D9215094確認production gate仍FAIL：I1 template/Identifier/resource未閉合；I2全role alias symbols會吞真target；I3缺tag靜默ifPresent；I4 static/runtime條件；I5 context接線/時序。原strict只檢最後ampEnd，accepted gate還須逐role。Implementer沒有自批完整。
- 原生AbstractBlock.Settings.CODEC為Codec.unit；strength1/99均輸出{}，只證codec非單射，不當已證terrain漂移。v1.1因此明確完整生成**資料**graph（dynamic/reloadable、typed references、tag有效次序、template資源/NBT等）與受支援static/dispatch runtime前提；不是忽略已知依賴，也不承諾偵測任意Java/mixin語意。未涵蓋/required缺失/不支持/不可重建仍拒，不以namespace/JAR當actual值未改證據，M7玩家chunk/entity歷史仍排除。
- root原生短讀另核ChunkGenerating.generateStructures直接讀global GeneratorOptions.shouldGenerateStructures；ServerWorld ctor交GeneratorOptions給StructureAccessor。r2將內建Parameters明列OverworldPreset string＋GenerateStructures嚴格NBT byte0/1，三策略保留source值；NEW_M6及新family role須由Task11 exact owner供constructor/worker frozen值，未遷移legacy不變、migrated OW額外比實際bool。外層snapshot五欄/512bytes/32byteFP不變，尚無正式M6snapshot需遷移。
- v1.1首草案EA51CDD1B16135D10B999E3EE12A4F54122DFABE1BCFB5E176BAC8AE6E9C18FE，fresh spec report6FAA19EEA2609B66373B55A4CABE4B2500A54A26A726BCAE86BAE0326DB7C00D；r2 CA21AF117A6C7D47F7DE9E6A2ECD9A994A40BFC88F3EEA48F58EF8FD2C36D8A1，fresh delta report3921419E7879ADAB3D03A3778E1E84DA5D50AF416447499217B7E717FDA8256F，均C0/I0/M0。主控全文讀回；兩席判屬維持既有M6承諾的技術明確化，不需另作產品範圍裁示，不冒稱使用者逐項批准細節。
- 精確plan草案CF851C38AF64FC1D891E5D4F7F1A62F9E64A0AD6A93FD62143D89009193319F9，fresh review05FC7673941BE8B0D5B927C86D1585E166194752E0BC4507FCE001C0193AC41C Approved C0/I0/M0。採12core、2template adapter、7testmod、5PS與build.gradle窄轉發owner；8A完整資料/方法gate→8B1 DTO/metadata→8B2canonicalizer/context→8B3真lifecycle與stop後oracle。各正式風險批次仍雙focused/full/fresh reviews，B3 final code full即Task8終局，不為docs再跑。
- 8A原型已有biome/structure/tag/loot真overlay、Nether-only不污染OW、typedcycle與unknown generator拒絕，但皆diagnostic。舊source/class只在batch後凍結、repo cwd.fabric/logs缺before不存在證據的限制原樣保留，內容完整move到ignored evidence，不補造歷史。Prototype agent只作原生觀察，不是release注入方案。
- 8a2第一批6輪已保存80項artifact pre/post；GenerateStructures敏感、required tag拒與六role各自REJECT_OPAQUE_STATIC成立。tag overlay未replace沒改有效次序、alias manual namespace錯誤是setup，未當成功。後三輪REPL舊閉包實跑v3而非預期v4，同樣保留且不採v4結果。
- 有效批次改固定launcher-v2 D1B87239C8689FE6425BDBCDC194DD7FE7C7D468B3B2EBBBBC3C1E44C903ABD8與literal spec SHA988DB27D2BFEC331FEB4BBB73671AAB4E322BCA8DFB8A9071DD655153F69EE38；七個無Java正反例後，3新root各81項pre/post、actual class-source realpath exact=v4、自然exit0。baseline2d983d0381b341a1b68cd8f5daf30322、tag38afc4173f6448a2ae8c006c608fbd6b、alias e553ccd3424b4e7eadcf8e3bc418ebd0均在短verification。
- 原生tag同members反序使同RNG seed0–4選值由magma變netherrack、FP變；RegistryEntryList的get/index/getRandom有順序語意，不全排序。真Jigsaw alias==target==start_pool town_centers仍有actual definition type10/equalsNative兩preset真。root已讀原stdout/comparison；target definition mutation與非alias同欄仍未覆蓋，不將I2全關。batch3 findings2135F73CEE83D14D5CA21A1B1E6CA319321383AC6AD0A72BE773E4DEF9FF23AA、comparison50EF470DC84FD54CFE45B8177443B1DEB60FA2B509D92C132E6646476DD03833、manifestB8FBCB6692A4FD8F23D7C32CEDC1006A973CCB2F767ADC64E66AF57DF62F3958。
- Context/data authority方案依native SaveLoading refresh→factory及SERVER_STARTING→setup、reload holder/refresh/template clear/END時序；實際接線仍留8B。Template manager cache→saved→devSNBT→ResourceManager；有效writeNbt不單獨證可重建，須持久來源以同manager.createTemplate重建exact比；錯誤不沿native fallback冒充完整。合法temporary Session方法fixture僅提案，尚未執行；完整resource graph、alias-context、預算與其他native缺口仍未關。
- 本次只有已審契約/plan與CI結果文件採用，不聲稱Native/Task8/新code gates或人工已完成；main/tag/退役未動。正式SourceSnapshot/API/registry/data graph/context/runtime程式，必等8A accepted native review與主控8B放行。


## Task8A：方法fixture、型別診斷與 v1.2／r3-r4 採用（2026-10-26）

本段採用前Git實核為67e63df04b0061f493f048fa5c043a1e5832d7aa／clean，實驗均以該HEAD及逐檔凍結artifact釘選。只有ignored prototype／合法temporary LevelStorage.Session；沒有MinecraftServer/ServerWorld、既有世界搬移或production/testmod/build/workflow實作變更。固定constructor後工作、正常與例外路徑共用Session.close＋獨立FileChannel鎖；timeout只合作取消。失敗root與所有原件保留，不在原root改判重跑。

- resource v1原root727551…因fixture誤用NbtIo.read讀disk named-root，得到空compound；保留FAIL，不當native cache故障。v2改readCompound＋EOF及分開witness，在m6-settings-resource-8ffde62a548748b88bdb438fd217e432以PID38016/30500兩phase自然exit0，cache3/unload2/reopen3全true、68項pre/post/classsource與Session/鎖成立。只證有效來源／可持久重建方法；TWO-PHASE-RESULT SHA520557EACAC829E842A512A9202655B016EE6913AA5830506765431E1F2E2C37。root另重算72個原件索引項相符。
- graph v1準備未runtime；prelaunch修正constructor owner寫入漏close窗口與G2只比FP的缺口，v2增受控acquire後故障與外部canonical逐byte/node manifest比較。固定34 top-level（含9budget pairs／18evaluation），但下面各輪皆未跑完整表；準備／純編譯與guard不當runtime GREEN。

| 實驗（短verification root） | 實際結果 | 清理／範圍 |
| --- | --- | --- |
| graph v2 g-e632c7cf188f4691，PID6396 | 7054ms自然exit1；ParameterList.Preset$2未接納 | constructor cleanup控制PASS；2 Session正常close/獨立鎖、105pre/post/classsource exact；無G2 |
| graph v3 g-855b08c99e567a0b，PID18776 | 10579ms自然exit1；BiomeMaterialCondition CodecHolder未接納 | 同上；原始actual getEntries與重建比較已入prototype，但未認整體通過 |
| graph v4 g-397fc76c237be2b0，PID11552 | 10384ms自然exit1；TemperatureModifier enum常數子類未辨識 | 同上；15 exact MaterialRules CodecHolder及唯一derived欄有native證據，非泛略callback |
| graph v5 g-984933d432388149，PID38792 | 10547ms自然exit1；direct Pool未接納 | 同上；enum NONE witness已到達，不能外推全圖 |
| D1 g-2d01d2c3f0eeafe6，PID27780 | DIAGNOSTIC_STOPPED／自然exit1；hardcoded field.get(null) NPE | 只normal/overworld；1 Session/鎖、100hash/classsource、No-FP真拒。4 raw型別/8 lineage樣本；非gate PASS |
| D2 g-c4d472e040a02306，PID31540 | DIAGNOSTIC_STOPPED／自然exit1，22.945s；Recipe deepslate unsupported | 只normal/overworld；1 Session/鎖、105hash/classsource、No-FP真拒。8 raw型別/20 lineage樣本，其他5role未到 |

各輪無timeout/kill，事後owner消失、lock獨佔開啟且hash不變；root原件及外部audit無level.dat/region/entities/poi。Graph各FAIL沒有accepted/G2；D1/D2設計上禁止settings FP/canonical.bin/accepted，即使未知0亦非PASS。D2 metrics 174 resources／7,149,297 raw bytes、nodes1435/edges44727/states2044/depth62皆為partial，不據此釘正式budget。

D2 receiver audit在固定JAR8241class/73043method的真GETSTATIC/GETFIELD對照1496列（1486static＋10instance），exact declaring owner/descriptor/flags/read全部閉合；七instance配置欄留typed邊與original Codec input witness。已見11個樣本、9requests/13receiver tokens，但role未完成，不能代role-end完整配對。106個class-group列無同class直接read的保守邊仍保留；receiver audit不等於完整consumer callgraph。NPE的唯一觸發row未有原件，不補造。

原報告SHA：graph v2 B6E00EA49A16C6127590FD1C83D2777A3562982B8EFE9790FFA1B2301800B313；v3 EFBE3B74D63CEEF5DCA9FDE7100A1BD996CE1CE69E927C2E6AD3CF5A00CCC770；v4 74D9174A6FC8F79542AA5A357F12C27FBDBE63F7C80E56CECE78A81DEABCE918；v5 3D50C9269FFC3785750A8143D3B003A7C0DD31B268EEC8FA700F782D95DB4261；D1 3A8D6243D361B26D358D7A91EA79A034522A234EB75D2C99DE547189178A554E；D2 9F940E86F8A76FBFF90DF77F8A4C800B55439B5654149C2DA2A41AA1F43031DD。原始索引在本工作樹SDD task-08各owner；不force-add。

已見family合併草案D025B8AA5FBD326BDF9F3946BECDBF18707FE720DE8E5FB5DCE3E7012AA60743：Pool ordered資料、temple精確factory、sampler original/rebuild（state-provider seed非root必留）、FluidState資料與receiver分流。只有原生證據與待測方案，尚未編譯或實跑這份合併修補。D2 failure前未flush context.evidence/readerSamples，無停止template id／NBT path原件；不把靜態候選當runtime歸屬。

### 已審文件採用與後續硬門檻

- 平台advisory52CB31BF…及owner0845AD41…指出原兩named JVM不證mapping/platform。r3草案8C86461384A6512C9AFB750E4FDFD2CB499F329BB6D0540F534FC0840B05FCDA由fresh SOL review4F03E40EDA33158CA8FF5343BAB72B3B9FAAE485081CBB7F986C30AE903AFD41判C/I/M=0；root全文核。
- Recipe原生advisory D9927B1B3BBBAEB5C57E7934C3E9B718D21F19CC48EC89BB104AF72D68C4533C釘AbstractFurnaceBlockEntity read/setLastRecipe/領XP Optional語意；不把vanilla deepslate JSON當actual RecipeManager。spec草案49D8D54DA55BA0A958AC2B28702C468DE6CCC215769A647F3C8BC63BF289651C、r4 C2E3926949B62883DE0D7A93706EB76DB9EC06F897A9E925755A7A4623A8E1B8由fresh SOL E9FCC770E6417014F1BE4E8A2F85ADD04E79AFF88B8CA43F4C64158C450522ED判C/I/M=0，root全文核。屬現有生成設定契約的原生語意澄清，不冒稱新增使用者裁示。
- v1.2只在exact原生furnace/blast_furnace/smoker的RecipesUsed保留完整typed raw history；合法missing id不作required recipe edge，wrong shape/unknown owner不默放。Items/Burn/Cook與真正jigsaw/loot/component依賴仍保留；後續smelting/XP/unlock不在生成設定凍結保證。新controls與正式TemplateAdapterTest仍必做，這次採用不是它們已過。
- 採用spec SHA 0E0E3E5500241756077BB0860A55D00F8EE80C7508DEF11AD0093AD768D1C305；plan SHA 795FABA837C2D73B08055F305CD8E12A6EAE8D115BC49EC2AB27C969FEDCA71E。相對審過的草案只改標題／狀態／spec引用（spec行1/5、plan行1/11/13/15），契約正文逐行相同。Recipe邊界沒有擴成任意recipe豁免。
- 8A先完成native資料／有限budget／restart與fresh native review，才可明確放8B；跨runtime比較仍待8B3，Windows named／Ubuntu named／Windows release/intermediary三bundle六role及兩family原始bytes exact、實際classsource／namespace／stop／鎖、CI raw audit未全過不得結Task8或進Task9。新scripts/workflow/build owner見plan，release/sources隔離不變；不是將未驗項目waive。
- 本次只MD採用／結果記錄，正式code/test/build/workflow與已驗code245／CI64d相同；不重跑同code full、不冒稱採用docs SHA有runtime或新CI audit。後續launcher/spec須改釘採用commit的實際HEAD，不能重用硬67e。尚無8B、random seed玩法、人工／整M6 review或main/tag／退役完成。


## Task8：v6–v13局部證據與v1.3設計採用（2026-10-26）

### 本輪仍未完成的範圍

Task8仍8A，正式code為245051e87af563b778b79bca27e6706be30848f2；沒有production／8B或random seed玩法。以下均ignored standalone方法／component prototype，不是main-only／正式server或完整family FP。各自有自然exit、source/prepost與Session/鎖證據者只按各自範圍採計；不把其中一個PASS抵完整B失敗。A40/A2/A3早期named rejection部分僅frozen predicate/source證據，不能升格為後來expectedCode/actualCode raw控制；A4起raw碼依各報告採計。

|批次|實際範圍／結果|ignored報告與SHA256|
|---|---|---|
|v6 A40|40eval／56graph／39component；Pool/temple/noise/Fluid/history/receiver限定contract|task-08/graph-strict-v6/A-RESULT.md；`B9883DA463E4273942C21B193714A4A57457E1CC5299D7B1786CDEF80494608F`|
|v7 A2|6eval／7graph／3component；exact SimpleParticleType；參數化粒子仍unsupported|task-08/graph-strict-v7/A2-RESULT.md；`494693BB5896F567A3685F713767557B02193FB4AB8EEF15A7AC7E80B4C33B80`|
|v8 A3|4eval／5graph／4component；GameEvent native id/radius；非same-id radius-only mutation|task-08/graph-strict-v8/A3-RESULT.md；`6C835C7CCCD940F03247C6EF914C009A244F434CB0613A20FED7EC335D7AEF44`|
|v9 A4|13eval／18graph／11component；Changes present/removed、trim refs、DATA三入口與七raw拒碼|task-08/graph-strict-v9/A4-RESULT.md；`0C094DC5F8A065A5F5AEB17DC08A50659D6C23BD13F3930D1640B37A66A43DAD`|
|v10 A5|22eval／31graph／18component；A4十三項回歸＋exact Enchantment.effects owner／13raw拒碼|task-08/graph-strict-v10/A5-RESULT.md；`45BB8E4ECCEC0D50B9DF95968B3C8163669309020BA18B4B231A614193B03CE1`|
|v11 A6|真FAIL：case18 finish把template payload送到pool node；19–21未到|task-08/graph-strict-v11/A6-FAIL-RESULT.md；`85C2FE787CB7E9AEA7F4AB178BF01297276B3BF9D18C420B848023CC59E03FC5`|
|v12 A6|21eval PASS／24graph／15component／10raw碼；Single use、cycle、state、雙palette；仍component|task-08/graph-strict-v12/A6-RESULT.md；`A127E3F43B53B838F68AC13154CB52A423697508D998DE68D53D25962239A153`|
|v12 B|TOOL_INTERRUPTED/INCOMPLETE；缺exit/final/base cleanup，不能當native FAIL/PASS|task-08/graph-strict-v12/B-TOOL-INTERRUPTED-RESULT.md；`D9514D53E5ED0677834DCF91DD1E584AD362191266B21353393D18F527479BBE`|
|v13 B|正常closed1／59.098s；base-normal OW缺vanilla template，其他32case未到，G2未啟|task-08/graph-strict-v13/B-G1-FAIL-RESULT.md；`C86EF968B3F986E303F5A7AC9AD4ADD9C0E7BBAAAA9C276760111105AB829C0B`|

- v11 A6原FAIL的guard正確：fixture finish owner錯配；v12只改最後用已存current pool payload，保template/pool兩nodes、回邊/state與immutable guard。原FAIL及原root不改判。v12 A6的15bytes、四pair、十raw碼、234prepost/source及事後鎖均root核過；25native element只為driver顯式建立預定數，不是bootstrap constructor實測總量。
- v12 B的node_repl預設30秒await逾時/kernel reset後PID不存在，原件缺exit/final/base cleanup；兩鎖事後可獨占只證當時鎖釋放，不證正常finally。該輪只INCOMPLETE，未重送run或kill。v13改非阻塞start/status，35秒純Node測試跨呼叫完成（非Minecraft證據），B取得正常exit/final且真FAIL，不混兩輪原因。
- v13 B原件25檔、236pre/post/source、兩正常close/鎖與事後owner核對。runtime incoming確為ancient_city/walls/no_corners pool→pool-location→intact_horizontal_wall_stairs_5；固定JAR pool確含該Single location而NBT缺失。成功reader/authority885、resource attempts886；nodes3420/edges79851/states4241/depth62、decoded26032598，published canonical0、case/batch charged32619。只是partial，未證完整矩陣時間或H/H-1／full resolve總成本。完整watch鏈只按原件，不概稱所有Nether資料皆與OW相關或ISO已過。

### 已採用的窄設計與使用者裁示

使用者明確裁示「限定正常遊戲操作（外部修改走reload／重啟）」：snapshot窗口內無外部改模板/datapack，不要求OS隔離或任意外部FS ABA保證。fresh設計review3E61305D9BE716F29C4249F8EB59D366AB468298FA6574F983BA14C5033987D5為C0/I0/M1，root全文讀回；草案0E14C72680E4F9728694A4BDD927E13E7F833351872CD00128D552EB67DF4E20採用，M1「其餘四鍵」已改「其餘三鍵」。這是設計層Approved，不是A7/8A/native/lifecycle/死鎖／8B GREEN。

- spec v1.3釘exact Single真absence、五態只讀cache與完整constructor blank／當次error拒；不查歷史provenance、不讓已清除error永久污染。unknown/required用途逐use與scope驗，不因一次Single而全域豁免。完整NativeTemplate保留真refs；PRESENT與ABSENT_NATIVE_BLANK用quantumchamber:m6-template-resolution-v1 exact四鍵envelope區分，不加snapshot鍵/Parameters、不升尚未發布ProviderVersion。
- Model B已選per-manager普通方法shared側／snapshot server-thread只try-exclusive，取不到NOT_READY不等worker；getter返回後的template三mutator須當次owner association，完整finally／nested與鎖序列入plan。新增gate/mixin/accessor/test owners只為計畫，未實作。8B2成功路徑前仍有strategy/owner/hold budget review，8B3再真server worker、saved/dev、reload/stop與三bundle跨runtime驗證；不把候選無閉路論證當實測。
- 新採用spec SHA 5CA4C3058C2E0564A7EF8979427C4C5A6DD3FE5B9ED8FBFF999BCCC0E53BC4A9；plan SHA 024D0903AE7586A6AEE706D549B2AEB964B102C760686F3948D04A06CA5CB573。採用前dce5963的spec v1.2原SHA0E0E3E55…、plan795FABA8…原bytes已存task-08/binding-inputs-dce5963/，mapping-index.json列原path／preserved path／bytes／SHA。舊prototype/報告/prepost原件不改；它們指向當時binding bytes，不能把現已更新的檔案假當原bytes。後續新launcher釘root完成docs-truth後的實際commit，不重用dce/67e硬pin。

### 唯一下一步與保留門檻

下一步只按plan準備A7：38evaluation／40G／14有效O／2diagnostic D／42A／M68上限，1Context/Session/1JVM，尚未執行。#11/12 unknown圖O0、D單node無FP；三source winner各一次；固定type/id/extra／palette操作與新增logical template IO768、native操作766上限已明列，未量MC bootstrap不混稱總系統IO。先root短讀固定source/artifact/module/spec與guards，再另授權runtime。

A7後才另放完整B34/G2：#19保原exact Single missing fixture改完整OW absence正向，34case/24ctx/28role attempt不增，但須新增該role完整bytes/FP/metrics/node manifest；pool/loot真正required負向不變。全部新圖mutation、H/H-1、冷JVM/native review未過前不進8B；8B正式TDD/雙focused/full/freshreviews及8B3 Windows named／Ubuntu named／Windows intermediary三bundle仍硬gate。Task9起後續順序、M6人工/whole-branch/main/tag/退役門檻均不變。

Task7既有full為code245的run-all-efbd4990e8824a63bc93b458643877b0，CI為64d6317671b5e9152bfd9e088356d16175088b3c/run40184992145；不把本輪六MD或之後新docs HEAD稱為已跑新runtime/CI。此次只diff／git diff --check，不為未變code重跑full；尚未commit/push，由root先做獨立docs-truth。


### A7 startup／observer前提修訂採用（2026-10-26；仍只準備）

- 固定MC與Loader/ASM patch／Hooks唯讀證據未證自然SharedConstants.isDevelopment=true；fabric.development不是該native flag。既有resource方法fixture用過暫設flag，不可冒充自然環境。A7-setup-amendment FB36DCC097FFD90D51E25E4DBA26B213385F7E341D28B899FDF76C7EC5E3D37E經fresh SOL FC7AD02FDCD59D854C2F3D19E3FB33C7B33D26A072D5061239D3E1C456291847 Approved C0/I0/M0；設計允fresh A7 Main在零Context/manager/Session/server/world前顯式一次dev=true，保持退出，記before/after與唯一參數，不在observer暗改，B/8B3無override。尚未fixture執行，不把設計採用當runtime。
- 16 exact method hooks／bootstrap單counter identity／transform與callback latched fatal／expected拒絕前fatal空／readonly禁用入口均須實作核；ctor pre-init attempt不傳this、成功return才記receiver，attempt與完成分列且不double charge。#10/#24與三個isolated native winner承擔正向witness，bootstrap未量成本不混N/IO。
- #26 setup N2→1（owned author反射）、#29 N2→3（獨立corrupt→OrBlank歷史保留壞檔）；總setup94/native766及IO768、38eval/G40/O14/D2/A42/M68不變。A7仍準備、無runtime，完整B/G2／8B／平台／人工未過。
- 修改前614eda458561697bbeb6383ae04c5142f49e34ad的plan原bytes/SHA024D0903AE7586A6AEE706D549B2AEB964B102C760686F3948D04A06CA5CB573已存task-08/binding-inputs-614eda4/mapping-index.json；原dce archive與所有舊runtime原件不改。本次只plan/note兩MD；新plan SHA C8DA5985FD6984B3B074EBE697955182389D311D2D46468FF2FA6F5478225453，spec v1.3仍5CA4C3058C2E0564A7EF8979427C4C5A6DD3FE5B9ED8FBFF999BCCC0E53BC4A9。root另做窄docs-truth後建立新pin，final launcher/spec/freeze不沿614硬pin；不宣稱新docs SHA已有full/CI。

### A7 Loader admission v4 設計採用（2026-10-26；未 runtime）

- plan採用 v4 `BDD8BFD4D82A401EE26AC83CB7A572B9420802352F703CE21C883766775A239A`，獨立設計 review `33D8D22229D6D304C435C6CEC1DC643A1656602B6319EC7497A689FA6C7823BA` C0/I0/M0。正常 helper producer／owner初始化、一次expected與每define只讀閉包／唯一bridge fatal latch，實作與literal manifest仍須核；設計通過不等於native parser或A7通過。
- 修改前4976847的plan bytes已保存 `task-08/binding-inputs-4976847/plan.md` 與mapping-index.json；spec不改。僅本plan與note兩MD採用，不改production/build/scripts，不commit/push；root窄docs-truth後給新pin，final literal/freeze再綁實際新pin。所有舊immutable包及FAIL root保持，1JVM/38case與成本上限不改，B/G2／8B／平台runtime仍未過。


### A7恢復開工、核心複審與三次setup實跑（2026-10-26）

使用者已明確恢復工作，舊11%用量停工限制失效；要求遠端回合失敗後續接，子代理卡住就換新的SOL。Task8仍8A，正式程式對245051e無差異；三輪都沒有執行38案例，不稱A7或8A通過。

- core I1/I2已關：獨立複審3B7D7ACBB055545335DDD82714AFCAB753A0FD54F6C01263AF04DD07EC2537C0，限定JDK helper RED/GREEN及實際接線；native parser仍須A7。Launcher primary仲裁複審96EDFBF6DCCFF07E89D9F83233C220B82E3734A887697C56E37B761C5FACDCE8，System argv複審18DC95AC826D60B0DDCED9610E491AE629B5DDDF26537B143F84085439CFC019；均不外推完整runtime。
- root使用非阻塞controller v5（SHA368D42D1623BE8D84540AD7234821C352ED8F221A668466DD01CA66A013751D6），保180秒成功期限／30秒合作收斂／600秒共同期限，無強殺、無重用root。

|候選／root（均在.superpowers/verification）|實際結果|限定證據|
|---|---|---|
|controller-v5；g-db27835c4a1a4f59/p1-d8b9919c|PID25756，631ms，自然exit1；ENV_UNLISTED:SYSTEMDRIVE|272 pre/post相同，Knot及Minecraft未載入|
|env-systemdrive-v1；g-8f2144b9edf20c0a/p1-8dcc36fc|PID40768，1063ms，自然exit1；HELPER_ROUTE，首錯latch HELPER_NORMAL_DEFINE_BYTES|269 pre/post相同；實際Windows argv exact、LocalFree一次成功；Knot返回|
|helper-route-fix-v1；g-05aaff1453002de0/p1-76102627|PID34220，1166ms，自然exit1；STATE_VALUE:semaphore|270 pre/post相同；helper一次callback、loader/source/bytes exact並正常返回；五個native target未載入|

原始stderr/setup與exit均保留；exit的contract-final.json缺失是setup停止後的次要audit結果，不能蓋掉Java首錯。root逐輪八原件hash及有限判讀存各owner的ROOT-FIRST／SECOND／THIRD-A7-FAILURE-AUDIT.json。沒有Context／Session／38eval的成功證據。

- 環境修正只固定SYSTEMDRIVE=C:，其餘未知env仍拒；獨立review0AF5BFBD42AED72B435C6F8978B1DF9770667032F1CC48E2B6795FFC132348FD，八pure Node controls只證資料語意。272→269為三個重複realpath改新位置單列，unique同為268，非少驗三個來源。
- helper原default package被固定Loader列為Minecraft並做access transform，raw相等前提錯；改qc.m6.a7.admission，維持所有精確guard與首次有界分項receipt。同輪修正fixture JSON誤傳為mcJar，改取literal已pin Minecraft JAR；三source窄修獨立reviewC3BE0742ACF7745B5BAF93756780E65B4A400C8B5745ECB779CDE75A7EB88F90、compiler21752自然exit0。第三跑只關閉helper路由缺口，不冒稱整A7 GREEN。
- semaphore獨立分析4897422CB344328D62F892CBC060F5002DE1C531B5B8EB0FC5FF9E9731529D3F：旗標是歷史OR latch，Knot流程不以isSet判目前active；仍需depth0/max1/errorfalse、固定owner/同thread與所有snapshot不漂移。首次置true的精確stack未觀測，不補造。剩餘prepare集合型別一次核對，未到達的guard不當已驗。

修改前a6b7fcc的spec/plan/note原bytes已保存task-08/binding-inputs-a6b7fcc/mapping-index.json；三次實驗仍對原pin，不能以更新後live文件冒原件。此處僅同步狀態，不更改產品spec／38eval、G40/O14/D2/A42/M68、IO768/native766或容量／期限。下一輪技術前提採用與實作review後再綁新HEAD。既有Task7 full efbd與CI64d維持原pin；完整B34/G2、8B、三bundle與人工仍待。


#### A7 Loader狀態窄修已採用（尚未重跑）

- root完整讀獨立分析4897422C…、addendum9A281576D34A308108229EBBFBA064A772D9A2C5267679A302DA39F5039C9512與修正review8992DAFDD47391515C930407C196100EC8D4600DA20A97BB0F96DAEE0C8752FA（C0/I0/M0），重算STATE-FIX-REVIEW-MANIFEST-v1的九原件均符。Helper source SHA0CC4A46BFAADEDD67FFB7AE29181B6EA8D44FDA1BDB5F9B070D150D47B805AC4；純javac24648自然exit0、stdout/stderr空。
- 恰兩token：固定semaphore=false→true並維持所有Watch；AW.classes以既有kind3 exact LinkedHashSet取代錯誤kind2 HashSet，仍必空。不得clear/set、任意boolean或降低owner/incoming守門。其餘prepare normal constructor／setter／queue／config／coprocessor已一次靜態核對，未到runtime的值不稱已過。
- plan已明確採用此窄技術更新；產品spec不改。三次FAIL原判保留，下一次以本輪docs commit新pin重建literal與完整freeze後再由root啟動A7；尚無第四次runtime或38eval結果。


### 2026-10-27接續點：A7與限定focused完成，完整B27仍停End相依owner

本節取代前節「A7三次setup、尚未執行案例」作為目前狀態。Task1–7不重做；Task8仍8A，8B未放行。以下native原件均釘1ec44f10e878ecc86ae773de1fbf658ab6a3f2d3；本次是純接續文件更新，正式src/build/scripts/.github對Task7 code245051e87af563b778b79bca27e6706be30848f2無差異。既有full run-all-efbd4990e8824a63bc93b458643877b0及CI40184992145/head64d6317671b5e9152bfd9e088356d16175088b3c仍只代表原pin，不稱新docs HEAD自己跑過full或CI。

#### 已採計的限定結果

下列root均在本工作樹的.superpowers/verification/；review均在.superpowers/sdd/2026-10-24-m6-universe-generation/task-08/。它們是ignored prototype的有限證據，不是正式release／完整B或8A完成。

|範圍|唯一採計root與結果|獨立結果review（SHA-256）|
|---|---|---|
|A7 owned Model A/component|g-398b36a7c5f4e794/p1-c4d90214；PID41900／5560ms／exit0，38/38，G40/O14/D2/A42/M68；1 Context/Session/lock|a7-result-review-v1.md：bd7c0150f2084199898f50a5fc84cc2d46a0807421e285f2e045d7f7a9455cfb，C0/I0/M0|
|取消輪詢／primitive helper|g-815b2a49157c0a62；PID15752／415.218ms／exit0，13控制、0 Context，4組raw逐byte同|B18-focused-result-review-v1.md：9f46f445ebae1494337b09b813bfdbc63aabf32688df9b29f423fd89c54dda63，C0/I0；不代表完整B效能|
|Magma受限NBT profile|g-47048bdb93f40710/p1-7ff3456c；PID36104／4510ms／exit0，54輸入／52控制，24 publication＋30拒，1 Context|magma-focused-result-review-v1.md：25d61b749377bdc41385b6a5b345ac4d4769262a8b14c03761b8b81189b545b5，C0/I0/M0|
|Axis declaring admission|g-b1ea237c4d4281ae/p1-57d12428；PID13592／4646ms／exit0，15案=8 component、6預期拒、1 typed-only|axis-focused-result-review-v1.md：f0a894a6eadd3e64e7c2022d7429909cb067f541d6bcbeab8ff8b4e35335a0ec，C0/I0/M0|
|generic三鍵guard|RED g-c9817fe6e686cf0a/p1-270471bd，PID12344／4442ms；GREEN g-59cd65b580dc8241/p1-b0525e54，PID42676／4462ms；GREEN 37案=8 publication＋29拒|generic-rg-result-review-v1.md：d0f1056b21b74d99948ec7d88587be41230a7f131f11db2d44e14a1483fe3606，限定C0/I0/M0|
|End兩路只讀path|g-714529baf4880917/p1-3f85c9e5；PID27552／4269ms／exit0，13 logical rows／37物件觀測，1 Context、0 graph／publication／FP|end-holder-path-native-result-review.md：d20a2bc5639013732eeaeaca5539540a0b7c740d06ec0a7b6d863ee827a636a3，C0/I0|
|End settings完整state focused|g-73006d864209429f/p1-be5dce71；PID14088／4610ms／exit0，11案=1 typed-only＋10具名拒、11graph、2 Context/Session/lock、0 publication／FP|end-simplex-focused-native-result-review.md：5281ed89dbf31af860c596d491c97acbfc786f3107f9e39220340505fc07a219，C0/I0/M0|

A7第五跑才達38案；第四跑g-5e850f7b8deaa86e/p1-e024dab7（PID588／5502ms）在case5因inventory接錯而FAIL。修正只接回固定inventory及實際class source路徑，未放寬Loader守門。A7 actual IO170/native142僅屬預定觀測窗口，不是整個JVM全部I/O；once-dev=false→true只在A7 Main明示一次設定，不外推B／8B3。五個targets、16 hooks與fatal空均由當次原件核；原四次FAIL、ModRemap/CDS警告與兩個故意壞GZIP的ERROR保留。A7結果入口為graph-absence-contract-v1/reference-inventory-fix-v1/A7-result-v1/A7-RESULT-v2.md，SHA e40b0eb74e52aeb317872a3b6c9fa6a548e4070ab1b71420a9a72fd0e9f646b0。

generic有效RED是舊adapter三項缺陷被受控重現（其餘六baseline保留），自然exit0／execution accepted不表示舊版修好；GREEN限制generic根及巢狀entity的DeathLootTable、active_effects、CustomName三鍵，Magma走獨立受限profile。六baseline逐byte相同。早先g-d90131fa6016d5cf/p1-590f3e82只是driver owner/scope收尾錯誤，仍是HARNESS FAIL，不採作缺陷RED。這些focused不關閉完整B與fresh native完整性門檻。

#### End從靜態推導到實測接線

固定End JSON 3923 bytes，SHA 8f779257fd0a4237e38cf09c7e8057152b73d1ae0c392cefbd21b1b10763daaf。只讀probe證兩路的11個density child均是Holder→exact Direct（key空）→value；initial-density七個Binary依序ADD/MUL/ADD/ADD/MUL/ADD/ADD、走argument2，cache的mapped欄位CACHE2D、raw enum name Cache2D與asString cache_2d分清。兩個終點在該run同identity8；程式不硬編8。曾有的LinearOperation推導及直接Wrapping假設已被真trace取代。

End v6完整focused保留原物件、獨立new EndIslands(0)及codec後／終局四份各2076-byte狀態（全512個permutation與3個raw double bits），四份相同SHA 2c5b0805b26a64d392e0653a22864f1908e310587230f4a939e0df3a6df08557；owner／sampler／array均與重建reference分離。N1–4逐案只翻一bit並由同正式comparator拒，registry前後不變；owner、node、非零seed、裸Simplex／Octave與overlay source負例具名拒。14次source attest、charged canonical41520、小於32MiB；forced/file/checkpoint計數11/15/21421；dev false前後相同、fatal空。198 pre/post pins exact、PIDgone、兩個session.lock可獨占且bytes不變。final SHA eb53dbaeb1aa9112204ab8ec7291f1e99d30fb54fb3d69f4aab12dac7926b105；有限RESULT SHA cd6e9c07810ebcdf10f9cc47a3fea164099342f7fa83cd308655d9af3c3969ee，88檔keymanifest SHA f2b4797f12deb4606da5e4c0ae8627cfff9cff4ecd0d40faa903452b1031d85d。

中間失敗均保原root：End v3 PID6256的class hash literal只有56碼；End v4 PID7952漏Holder；path probe v2 PID29372把mapped欄位當Enum.name；End v5 PID6796已完成7列，N8正確拒絕後因測試漏sampler identity而NPE。v6只補test Main的sampler／array欄位與父端必填／原owner鏈，production End契約未因此改；原117 artifact byte exact。36項純Node controls包含原有反例與缺／錯N8身分、cache asString／constant及extra Holder，v5的parser覆蓋Minor由v6 review關閉。純Node controls本身不稱native GREEN。

#### 完整B27仍FAIL：唯一新增dependency owner

B26組合首次review C0/I1：classSource函式用未定義s.minecraftJar，未啟runtime；B27以顯式nativeJar參數及callsite修正。114 Java artifacts、End helper、child/cold與B26 byte exact，沒有重編Java；雙phase同函式class-source控制6/6、nativeClaim控制4/4。B27獨立複審C0/I0/M0由reviewer訊息交root忠實落檔B27-class-source-rereview.md（SHA 5e909b2c9d88c382a209741deac17fb3f7eae86b75af1fc0ae9a92512953988a）；manifest/pins由root另行實核，沒有假稱reviewer重新跑。

B27唯一G1 root g-7d3a70ec3b9f4f67/p1-d4526a5e，PID38124／10131ms／自然exit1。constructor control PASS；base-normal的overworld/the_nether COMPLETE，the_end在registry|minecraft:worldgen/density_function|minecraft:end/sloped_cheese被UNSUPPORTED_END_OWNER拒絕，34案尚未完成。當次End noise_settings proof已完成，後續drain才遇到新owner。context/session/close/lock各2；role attempts3；decoded39669941、chargedCanonical74814321、filePoll399/forced20/checkpoints26453863、fatal空、dev false不變。193 postpins exact、PIDgone／rootJava0、兩鎖獨占且hash不變；G2未啟，不能重用該batch/root或改判。

FAIL-RESULT為task-08/B27-G1-END-DEPENDENCY-FAIL/FAIL-RESULT.json，SHA 01f725a53983bab2edc2f4f569aa544c5f62df520fa47b09763e023bd11dadd4；42檔keymanifest SHA 342f74a832bb5fc70ab9593b79b38bb59cffc26b4277d109449de2a756a419ec。B17逾時、B18 Magma、B23 Axis與B25 Simplex原FAIL，以及其他未啟動候選都保留；不得以後來focused通過回改舊結果。

下一個限定方案為task-08/end-dependency-owner-audit-v1/CONTRACT-PROPOSAL.md，SHA 69bc0509ddd953b45a5d74acc60d8717109ff3fba5f3fbe62470ff19370a4131，仍是待審方案。固定JAR896份worldgen JSON精掃僅3處end_islands：已驗noise_settings/end兩路，加上density_function/end/sloped_cheese的argument1。後者133 bytes、SHA 175954c7e03469b790e047f16358e0c0a6a7804030c34cf160a9361219d67c25；固定JAR中只有end.json的final_density引用它。OWNER-CENSUS SHA 0419a6a8b008b107f441dce1da8f451f56040f11b9ca858e4c2fe31595bfff9f。lastEnd在noise owner完成後留存，activeEnd在encode終局清空；後續NVC只看activeEnd，所以拒絕。失敗收據未記dependency物件identity／winner／decoded root，unit codec的same-object只能稱靜態推論。新候選須runtime exact source、同已驗allowlist物件／sampler／array／state／codec後才接受；未知node、clone、非零seed、overlay仍拒，不能把activeEnd延伸到整個drain。

#### 後續與文件真實性

先關dependency契約review並釘新增正反案例／Context／source-read／charge上限，再新immutable實作、fresh review、focused；其後完整B G1，G1 accepted且同600秒batch剩至少210秒才另放G2。B原34case／24Context／28role與9對18 budget controls、G2兩BASE／2Context／6role、2G／180+30／600及原graph/output caps保持。完整B／fresh native與quality未通過，不進8B；三bundle跨mapping／OS仍是Task8完成及Task9前硬門。既有B18 focused helper的第二file open失敗可能留第一handle、Magma缺id診斷訊息等Minor維持後續Step0；static canonical未charge／H-H1 replay與full resolve成本界線仍留給完整native review，不默稱已關。

本次改檔前已把1ec44f10的spec/plan/note/AGENTS/兩handoff原bytes保存task-08/binding-inputs-1ec44f10-20261027/mapping-index.json並逐byte讀回。spec v1.3仍SHA5ca4c3058c2e0564a7ef8979427c4c5a6dd3fe5b9ed8fbff999bccc0e53bc4a9；產品範圍不改。下一候選需以本次文件commit後實際HEAD與新plan/note pins重新freeze；舊run只採原1ec44f10，不能拿變動後live文件冒原件。本次不重跑未變的正式full，不建立PR／LICENSE，不合main／移tag／刪工作樹。push依持續授權；實際commit／遠端狀態以Git讀回為準。


### 2026-10-28交接：23案child通過，v5離線父修，B28草稿待續

使用者要求「趕快完成、不要鬼打牆或太嚴格」，並要求找收尾點交新對話。子代理最新限定gpt-5.6-sol／gpt-6-sol。原writer額度中斷後，由fresh 6 SOL接續，不重做M5/A7。新完整入口為docs/handoffs/2026-10-28-m6-task8a-resume-prompt.md；本節是進度檢查點，不代表M6或Task8A完成。

- v3 P2 S4計費FAIL的根因是isolated graph使用g.totals，但Use讀Context.totals。v4只修Use起迄與N18/N21實際帳接線、父端對應；127 artifacts中123 byte exact，四個Use/Main source/class改。窄審end-dependency-v4-meter-review.md SHA91fc6b4b98f1c77bfb1a9ebd15f0362de68cf665e297745745e9d0e5d6f06f1c，C0/I0。未改core/isolation/2076或預算。
- v4真root g-e660823a8ed3b887/p1-fd1eacb0，PID9212／6014ms／child自然exit0，Java 23/23 PASS：23graph、5Context/Session/lock、2typed-only／21具名拒、0component/FP、source32＋owned3、charged68508、fatal空。final SHA6646958b8a26919c1bb2b9c06aad5d9278d4a58af9ca4ddd85336e26e354296a。父close-audit仍END_DEP_EDGE_COPY FAIL／acceptanceForbidden，整run不能稱PASS；219postpins、PIDgone、五鎖獨占、178原件保留。v5目錄V4-PARENT-FAIL-RESULT SHA a0d7cf43a223022aa2e425277e43ca83016231c2dd63cd94c3bd9a57101df759，root manifest SHA d868e1253d3febc35fcae1523e5a813a5d8ddebe410dba12972b965a4474fa41。
- N18真圖6條edge有兩條typed-entry-data；父端只按path泛刪誤傷NODE→BASE。v5只精確化NOISE→NODE的from/target/path，argument2用from/target，保其他欄位與順序；127個Java artifacts全部byte exact，無javac。v5 REVIEW-PACKAGE SHA3ac10f12aa5ca92c1600373194503787c97036eeebb96baececdea4c7ec502b3；真edge九項控制SHAa2c1da34d801d233a215ba9be6b5c102605c8bdc0d2fd34f9108fffb66633473。V4-RAW-OFFLINE-CHECK-v5.json SHA59f9f65a6deb624030e9fdb1e94d63117fc700e23af65adeed87c9fe0e8541ce：新oracle唯讀套真23案回傳accepted:true／5Context，未寫accepted marker；它是離線countercheck，不是新native也不追認v4原FAIL。
- 使用者本輪收斂裁示：保留v4原判、沿用其Java23真結果與v5離線父修證據；不再啟相同23案focused，與B28組合做一次有界獨立審查後直接完整B。這取代v5 REVIEW-PACKAGE末段的再次focused流程；不是跳過完整B或改寫歷史。
- B28目錄task-08/graph-strict-v28-full-end-dependency是未凍草稿：116 Java artifacts已組合、dependency父proof/launcher/controller與196 listed-pin spec已寫；三模組Node syntax exit0，但尚無pins全驗、pureprepare、完整控制、preservation/manifest/review package或獨立review。預留g-97908086aa3f6eac不存在，G1/G2未啟。spec草稿SHA f73919f539d3f8060a911e3618c538a5ece43b4fbe3f62555b1d4ad0a06404a5；controller-owner目錄含複製來的B27 claim b6cb…-explore.json，不能當B28run，正式啟動前保留舊claim並使用乾淨owner。copied v27 PREPARE/manifest/review都不採為B28結果。
- owner handoff task-08/2026-10-28-owner-handoff.md SHA e1b89fab81a6a26d441e019b4b30fedb53664831564dde7a40788485bfb32b07。新對話先核現況、完成B28草稿／新HEAD與文件pins／root nonce owner，再一次合併review；C/I0後唯一G1，accepted且同600秒batch剩至少210秒才G2。原34/24/28、9對18、G2兩BASE/6role與180+合作30等門檻保持；8B、三bundle平台與完整M6人工收尾仍未到。

交接前已確認無M6 Java/javac程序，所有run已收斂，未強制終止Session。交接docs前HEAD f6065fde7f0dffe7b2a29c894f69a257978b4e64／tracked clean；原spec/plan/note/AGENTS/兩handoff六份raw已逐byte保存task-08/binding-inputs-f6065fde-20261028/mapping-index.json。本次只MD，正式source/build/scripts/workflow仍Task7，不為文件重跑full；新commit/push以Git讀回，不能把新docs pin冒原native輸入。可玩基準仍是已完成M5，完整M6未交付。

### 2026-10-28：B28 完整 G1/G2 接受，Task8A 資料 gate 結案

在工作樹 `codex/m6-universe-generation` 的 8d65769483703589a10d37820c139441b2524baa（local/origin同、tracked clean）重綁B28。spec SHA `09612c111cf09c371cd3c820b29f5123fb9739f95c611b717a2a208936ed6159`、200 pins純prepare exact、116 Java artifacts（B27 110件＋已驗v5六件逐byte exact）；新root/owner在啟動前空。合併窄審 C0/I0/M1（Minor只在 `PRESERVATION-v28.json` 來源標籤誤寫112/4，實際110/6，原包不改）；reviewer訊息由controller忠實保存於 `task-08/B28-COMBINED-REVIEW.md` SHA `30dba7567c33ff0bbddd66641c18a79eaa288f178fe6f8b89d207c7c1d1e8808`。v4舊root `g-e660823a8ed3b887/p1-fd1eacb0` 的23案Java child真PASS、父 `END_DEP_EDGE_COPY` FAIL/acceptanceForbidden仍原判；v5只離線重核真raw，不把它追認成新native。

controller唯一啟B28 root `g-95266857184fe3fd`：G1 `p1-9db25adc` PID38140自然exit0/66,770ms，34case、24 Context/Session/lock、28 role attempts，九對18次boundary/overflow evaluation；graph-final SHA `9d350ca4119d7bcda56b7de1e25e54aef3082accec0f085340b9f230c983d6d8`。G1父端accepted後、同batch餘約513秒才啟G2 `p2-18238982`，PID39276自然exit0/15,708ms，兩BASE、2 Context/Session/lock、6 role；graph-final SHA `cc66ea37121069f3d388665599eef3074e328fa3ac72e2c673ae89dc18cc6c64`。G2六份canonical逐byte與node manifest比較SHA `37d16bd88e60b1208ddf24ee44b0e63be7afcd5fa66c355711872ada6524aeee`，六組直接bytes相同。兩phase END proof audit為 `f4af2c4e81e232eb5ce6d2162e46d08f51c6d85ef8daad3f3bf353b8c175459d`／`63c7b9185e96620cc144c27a55c9f4f07c15bfdb050931b176b137e7410769eb`。

獨立native/quality review C0/I0/M0：200 pins與四份pre/post exact、outputs-manifest 431/81檔逐SHA、七End Context及多role charge、23條phase class-source、六份冷比較、PID消失與26把lock獨佔、無原世界資料皆核。結果訊息保存 `task-08/B28-NATIVE-RESULT-REVIEW.md` SHA `e638b5c485f10e73ff1f8af06aa6c59dbeea3d173ee253339d1831192135aa20`。`exit.json` 的accepted:false是marker前snapshot，不改寫；最終以`phase-accepted.json`與controller final state判定。18次預算是邊界評估，不是18次完整native resolve；本8A證據限Windows named vanilla fixture。Task8A資料gate結案、明確放行8B1；正式production、三bundle跨mapping／OS及Task8/Task9之門檻仍待，既有Task7 full/CI僅採原pin。

### 2026-10-28：Task8B1 正式 snapshot DTO／profile metadata API 結案

前置 HEAD `77b98a1116f2ec8cc5031f5f2eda020a963de8cc`（local/origin同、tracked clean），唯一實作者只改七檔：`GenerationSettingsSnapshot`、`UniverseProfileProvider`、`UniverseProfileRegistry`、`ServerSettingsContext`、兩JUnit與verification README；候選commit `3ec19675e834d93d98c2af38f54bcc18a2053e1b`，range `77b98a1..3ec1967`，未改journal/candidate/world/family。Snapshot外層exact五鍵、canonical provider id、OutputFingerprint32 bytes、NBT寫入途中512-byte上限、內建preset與GenerateStructures byte0/1及公開入口防禦複製；Registry只做metadata註冊/查詢/freeze及duplicate/late拒。`createSnapshot`／`resolve`明確fail closed，8B2前沒有正式settings FP發布。

骨架M6Compile exit0後，正式具名RED：Snapshot `focused-8b1-red-snapshot-2e77af489d794aa7ac44104a919526c6` 6失敗/0error，XML SHA `1bd92e2e95a4411e0cbbaadd61f7b4cdf23f38d6a6e19057016624aa8e6dee34`；Registry `focused-8b1-red-registry-216ea76c33f344bcb28ec2cdc2e2d9d6` 2失敗/0error，SHA `ff2cfc6dfd6d13c5de26205f1d468eff637819c9e29ca99b61a2080e224c7e4f`。先前兩次Node→PowerShell找不到git的setup log留在ignored `task-08/8b1-evidence`，不當行為RED；getter/metadata受控mutation共9案2fail原XML也保留。超限16k測試只證拒絕，不宣稱量到heap。

初次四focused在commit前dirty，junit-scope仍記前一HEAD，不採為乾淨候選gate；spec初審I-1已由新root關閉。乾淨`3ec19675`四focused：Snapshot `focused-8b1-clean-snapshot-1-61e96dd4154444b3abf43872972a3470`／`focused-8b1-clean-snapshot-2-f9677d480e414fc3ac949bfbf7ca6296` 各7/7，XML SHA `3a77364c7efdb10a63fc468be61dd0bfe9d14bcb9fdbf52d3cd3dee06c0f9481`／`322408fb1c39e10794ff74744f49663a3bb3f6ababaafc32a091ca7694d1ab70`；Registry `focused-8b1-clean-registry-1-684785127b014f83b9b5bcf749300c2f`／`focused-8b1-clean-registry-2-182a3ea84fa14b938dd9f5198fbbf2e6` 各3/3，SHA `63ef252a6d33a70e3193e41f60c0201b5f29df3f6f9f6c3e4d2248af50f0dd74`／`6594fe2e17bcf63197e9a47cdcba0185b5b2537b8251bf5cefbc348b10670600`。四份scope均HEAD=3ec19675、PASS、0skip/fail/error。

完整回歸 `run-all-0845a02d8c3b47bd98d239ca7bb61bb3`（乾淨3ec19675）`END ALL exit=0`，console SHA `377798a4dba8e8df9928a73c24a705ba60b4d08381decae696e13b8946876256`、summary SHA `a9d14961543dee2166bd59c3c44772246d8a446fe38545584f717601fb42813e`；default179/179、legacy1/1、M5 60/60、M6 8/8、JUnit691 failures0/errors0/skipped2（Windows預期NonWindows）、M5 collapse69、main-only PASS、release/sources testmodHits0，CI規則win/ubuntu PASS。這是本機規則檢查，非8B3三bundle實跑。獨立spec review C0/I0/M0（初審I-1後補證關閉）紀錄`task-08/8b1-evidence/8B1-SPEC-REVIEW.md` SHA `86c4e41f1b460b69a8c11c017ad6ac5714b4d9b5c82a3f0e236d3b9888996241`；quality C0/I0、Minor交8B2，紀錄`8B1-QUALITY-REVIEW.md` SHA `3b44fb451e00d73690772293579fa96dc62d1f25e39cab993c67c4388a43680c`。8B2接線前必以exact frozen provider id/version驗custom schema並重算FP，防可變provider/schema參照漂移，補resolve直接正反測試；8B1 fromNbt結構有效不等於完整custom設定可用。候選code尚未push；docs truth後一起正常push。

### 2026-10-28：Task8B2 gate／資料閉包 owner 前置設計（未實作）

以local/origin `85289ac76a70275c626fe3e29fbad19edad298f7`、tracked clean開工8B2；Astra子代理在唯讀階段遇模型容量，改派使用者允許的gpt-6-sol，未重送native/Gradle。實作者核plan §203–233/§328：正常worker對同StructureTemplateManager的cache fill與template mutator必有per-manager shared gate，snapshot只try exclusive；原8B2短派工漏plan明列七個main/test/mixin/config owner。另B28 full closure涉及多個獨立責任，最小正式support四類（graph、registry closure、native value、template NBT），不搬test-only原型。忽略空間設計 `task-08/8b2-gate-design/GATE-DESIGN.md` SHA `fedbb4c77aec3152401d919a6bd428f734841934ef126778e1914b99ceb740cd`、`SUPPORT-OWNERS.md` SHA `edae41bf09eb8c3e8c919bdb02cbe3938594402a511d613b8fc354ff5d16a96c`；未改production、未啟Java。

獨立設計review C0/I1/M1，摘要 `task-08/8b2-gate-design/8B2-DESIGN-REVIEW.md` SHA `b48713c05b1bf4a3a76e310a7cd71764e8e7fbe2f24f86d586c66e7d65882013`。固定1.21 JAR SHA b50eb354… 的六manager/三template方法 descriptor 實核，MixinExtras cached 0.5.0 有WrapMethod/Operation；正式compile/mixin命中/runtime仍待。七gate＋四support路徑可增列8B2 allowlist。I-1：100ms只是候選單次exclusive hold上限，8A G1總66.8秒不能當持鎖證據；先做fail-closed gate、具名RED、從try成功至unlock計時及超限拒，實測normal/amplified三role、present/absent、saved/dev/resource/worker busy並獨立review前不開成功snapshot。若超限，縮短持鎖工作重審，不調高上限湊PASS。M-1弱identity、退役gate存活與鎖序列實作review。8B3真server worker/reload/stop與三bundle仍未驗；8B2未完成，Task9不得開始。

### 2026-10-28：Task8B2-G template gate／context 結案，接8B2-C

為避免把完整資料closure擠進gate fix，8B2內部分G／C兩round，Task8B2整體仍未完成。G只改gate、九方法mixin、context state machine與兩JUnit，`UniverseProfileRegistry.createSnapshot/resolve`仍fail closed。基準 `e11b96b8ab33e98a62a0f3e58da16106bb73b45f`；唯一實作者先以具名RED取得Gate busy/no-upgrade/retire、Contexts epoch/inflight等行為失敗，早期node→pwsh找不到git及native fixture初始化/順序失敗各保原root，不算行為RED。候選 `548220481b3772612c7c36269d4ff9ca97dcd23c` 十檔、Gate10/10×2/Contexts5/5×2同乾淨HEAD，原full `run-all-0c0b825b2f824565b45b0bad8a5c1902` END ALL0，summary SHA `bfe9f643c578ffc87f0c84869ac1f47c7e26d1107b22bfbc2ceefdb805a392ec`。但獨立spec/quality皆C0/I4，不能因原full綠而結案：raw mutable accessor、持鎖起止漏算、失敗reload可再初始發布、public gate constructor可建第二鎖。

單一fix commit `0666cfd29b216e62e98402b8ffdfb76fb1ef3f04`（range54822048..0666cfd）刪未使用raw accessor及mixin註冊；Gate只經`forManager`取同manager canonical鎖、try前保守計時/實際unlock後判100ms與retired；Contexts `initialPublished`一次性。原四I具名RED、開發GREEN與失敗root各自保留；修正後四乾淨focused Gate11/11×2（`focused-8b2g-fix-candidate-gate-1-ad1f0436e3c84916ac20f8d2190a588e`／`gate-2-b411579dd2e843ef87c528b490dd075a`）、Contexts6/6×2（`focused-8b2g-fix-candidate-contexts-1-41754bb07f394451bc0f6efe5d2b80b7`／`contexts-2-4bd3afe8727d4af88275aa61f217869b`），四scope同0666/PASS、0skip/fail/error。新full `run-all-484f111755db4714b2666db4ab02ffa6` END ALL0，console SHA `3394467a5e78488f3e87b72d4f1a2ee88b540b9246d542436b256c1f9014dad2`、summary SHA `4521c488ac30b7ed5638bf12026747d42af9c4f84eb5556ce1dcb87494ccac70`，JUnit708 f0/e0/skipped2、default179/179、legacy1/1、M5 60/60、M6 8/8、mainOnlyPASS、recovery24、collapse69、CI規則win/ubuntuPASS。獨立spec最終C0/I0/M0、quality C0/I0/M1；摘要 `task-08/8b2-g-result-review.md` SHA `35485325987576a6ca4ef8a0339a293351b0c99205deb01067ee376c95e5dacc`。

G結案只證gate/context原型與既有回歸：安全只讀DTO在C的真TemplateAdapter消費點建立，不能用舊raw getter；設計review單次live exclusive hold 100ms I-1尚無代表配置量測，8A B28 batch66.8秒或Overworld973 template／27MB raw／54MB canonical只作風險推論。C Step0先用真manager有界量短鎖擷取含何資料；超限不發布snapshot，提出多短視窗/一致性token設計及spec/plan窄review，不提高cap。quality Minor公平鎖無參數tryLock插隊交C評估。8B3真server worker/save/reload/stop與三bundle跨mapping/OS仍未驗；Task9不得開始。G code尚未push，docs truth後一併正常push。

### 2026-10-28：Task8B2-C1a bounded SettingsGraph 結案，接C1b

基準local/origin `7fe58691d6e05b9ebf7af3c6ecf8f79a3f69ade6`、tracked clean；唯一實作者只新增 `Minecraft121SettingsGraph.java` 與 `Minecraft121SettingsCanonicalizerTest.java`，圖節點/typed edge/occurrence、固定big-endian與有界NBT/canonical bytes，無live source/FP/snapshot發布。骨架M6Compile PASS，正式RED `focused-c1a-red-0d66d982e10e4822afcc9ba50b4ba132` XML13案13fail/0error，初GREEN13/13。原候選88b0db5漏合併相同target/scope/use的兩個真edge occurrence；fix1 `01bcbae`前具名RED15案1fail、修後GREEN15/15，另固定1.21原生Identifier接納`minecraft:`的測試錯假設root保留。乾淨01bc雙focused各15/15但獨立spec/quality各C0/I2：compound/edge排序前無界UTF8配置、驗證失敗後圖未poison，不能結案，沒有為它跑full。

fix2 `71e85aedac01ff84a5c415e384a20eaa690736b1`（完整range7fe5869..71e85ae）以不配置byte[]的Unicode scalar排序、排序/retention前有界文字計費與全部變異入口checked poison關兩I；RED `focused-c1a-fix2-red-667e54e42980421b809d85d624176078` XML19案恰兩fail，開發GREEN19/19，固定bytes oracle補big-endian/array/raw float/nonASCII key/edge順序。乾淨雙focused `focused-c1a-fix2-clean-1-d75334e2865c4359afb54c3be89b80bd`／`focused-c1a-fix2-clean-2-231350afcd8f4027be15341f2067fc69` 各19/19、scope71e85/PASS、0skip/fail/error；M6Compile `gate-c1a-fix2-clean-compile-0eca3dd66f6340a69dbf0e625bb85c4d` PASS。完整`run-all-e64b6c86c16c4f78ba7fff476ea1a6ce` END ALL exit0 HEAD71e85，console SHA `29285b5776b36fb07eb02cf45c24e1b2bf4e27d3790f58a0a85d72531312493d`、summary SHA `3f35fe79edf6a00f23e22ee2f9fd285dc009484cd5f73786f912d597ab6a4b99`，JUnit727 f0/e0/skipped2、default179/179、legacy1/1、M5 60/60、M6 8/8、mainOnlyPASS、recovery24、collapse69、CI規則win/ubuntuPASS；tracked clean。獨立spec/quality最終皆C0/I0/M0，結果索引 `task-08/8b2-c1a-evidence/C1A-REVIEW-RESULT.md` SHA `fcc12f82cf7141b32425f72a10005a0c7431bdb91b6e6c3301e2548973c9e222`。空NbtList heldType候選經固定1.21原生write(empty)設0核證撤回，非finding。 實作者的ignored `candidate-evidence.json` 曾保存full RUNNING中間快照，最終狀態以上述當次console/summary與controller結果索引為準，不回寫中間檔。

本節只結C1a純圖；C1b typed registry/native、C1c template capture/rebuild、C1d three-role協調與安全DTO/custom schema/resolve仍待。100ms單次live hold設計I-1仍未量；Step0 v1方法I2，v2單normal/OW診斷方法已獨立C0/I0/M0，但需C正式fail-closed閉包先存在才可實跑，不把B28原型大包搬testmod。8B3真server worker/reload/stop與三bundle跨mapping/OS仍待，Task9–20不得提前。本節code驗證原pin為71e85ae；後續push狀態以Git/STATUS實際讀回為準。

### 2026-10-29：C1b-B1局部typed registry結案，B2 End橋接待實作

B1基準遠端`d23b20acc9f1bcc67e3d5bc5cad1ee30a5f9481e`，本機正式code候選`464946b341c943e58794abcb3d168135bce4a3f9`新增RegistryClosure、窄改SettingsGraph與同一JUnit；unknown definition、random_group、inline entry、非空pool維持具名拒。可編譯骨架首輪因固定1.21沒有ResourceManager.EMPTY而setup FAIL，不採RED；初RED28案9fail中兩alias fixture在進production前錯加namespace，不採。修正fixture後RED `focused-c1b-b1-alias-value-red-8ab9ca3313d14ddd9f0dca8ae08b5fab` 29案4具名fail/0error，涵蓋alias source/targets、空pool fallback完整圖及同typed id原生值變動；開發GREEN3 29/29。原464乾淨focused因TagGroupLoader.load回Collection而fixture compile FAIL，保root；test-only fix commit `a5db2c7831b63a53e38e7ffcc2a479838a364695` 用List.copyOf保原迭代序，production未變。

乾淨a5db雙focused `focused-c1b-b1-clean2-1-4f428d60cb9e4e83b6ce1dfc467e631b`／`focused-c1b-b1-clean2-2-12a4464c319840ee844c261ba6777e1d` 各29/29、scope同HEAD/PASS、0skip/fail/error；M6Compile `gate-c1b-b1-clean2-compile-59c6c28b49ac4ff1b76f6c44424746b3` PASS。完整`run-all-53c1566db6194a27bdbe494e8c1deb32`自然END ALL exit0 HEAD a5db，console SHA `92239ffccdfa33cfb8f30eb8e7d4c14a43f7e577596d0f18990626f58dd01d27`、summary SHA `785998e0027c71f89d9ec15d48647fc11fea439b2afe52237c03de2ac8f4b1c3`，JUnit737 f0/e0/skip2、default179/179、legacy1/1、M5 60/60、M6 8/8、mainOnlyPASS、recovery24、collapse69、CI規則win/ubuntuPASS。獨立spec/quality最終皆C0/I0/M0；controller索引 `task-08/8b2-c1b-b1-evidence/B1-FINAL-RESULT.md` SHA `2d2466e72158cb4315ba8d974862a66f00d1aa815d91f2873b0839b2a3be3a91`。本slice只NOISE_PARAMETERS葉、空StructurePool fallback、direct alias與原生ordered tag；同lease registry/RM配對及完整DimensionOptions/End/Template三role留後續，不冒正式FP/snapshot。

B2前置發現B28原型的EndIslands/Simplex/Binary私有讀使用setAccessible，正式不可搬。ignored `task-08/8b2-c1b-b2-design/PROPOSAL.md` SHA `bd80adf02b1148c2e2a15e4298db85f0e7044d5466f9b9d15c2d293e0979f70a` 與fixed-jar-javap SHA `8645045fd0a62e7e77c65104ec817b111f24113dcb2b4be628eb96b8a6334187` 釘三個最小mixin＋config、owned2076-byte state/identity seal。獨立設計review C0/I0/M1，摘要SHA `e96337ee4429c80476fc1fdff6aaa93825ca92ac26638b78d7d34393ea30db3e`；M1補充 `PROPOSAL-ADDENDUM.md` SHA `8215ddcd7ee537d33b1d1ce3d7fd0ae81a3d8fe999ac16118691bb2c1c0a854e`：seal入參/bytes()均clone、identity用==；公開endIslands(0L)參考物件要獨立，unit CODEC parse可能回singleton，只驗replay與原owner前後不變。固定JAR靜態descriptor通過，正式compile/mixin命中與B2行為RED/GREEN仍未跑。為避免B1 code無docs先推，B1與本次B2設計文件一起經docs review後正常push；B2程式用新實際HEAD綁定。

### 2026-10-29：C1b-B2局部End捕獲結案，B3 sampler橋接待實作

基準local/origin `60073ed5f7492149160ae4575d49089834f02ccb`、tracked clean；唯一實作者在審過的三End/Simplex/Binary窄Mixin＋config與NativeValueContracts/RegistryClosure/SettingsGraph/同JUnit八檔實作，候選commit `acaf81b63d367fafacaf0649c69026ef52059666`，未推。Node→pwsh參數/PATH兩次setup失敗保原log，不當RED；正式骨架M6Compile PASS。行為RED `focused-c1b-b2-red-f706ed4afaeb44878bb2746e3cf5c94f` XML32案1fail UNSUPPORTED_NATIVE_VALUE；開發GREEN2 40/40。終局array identity RED43案1fail、sloped registry root RED45案1fail、每次state canonical計費 RED46案2fail後，GREEN4 49/49；共用capture出口在source-after後重驗actual root，terminal原owner/sampler/array與來源bytes均守住。

乾淨acaf `M6Compile` gate-c1b-b2-clean-compile-8914efbdef364a5eb77f32d24570914a PASS；雙focused `focused-c1b-b2-clean-1-dd28f55ee02948c3abb02737db31435d`／`focused-c1b-b2-clean-2-e079d37602d74690941ed6213bb4738c` 各scopeacaf/PASS、XML49/49、0skip/fail/error。獨立spec/quality最終各C0/I0/M0；controller結果索引 `task-08/8b2-c1b-b2-evidence/B2-FINAL-RESULT.md` SHA `ff73ff04a60a3d2f57e601bd61523249fc84e5a26496919f5c1889c88b025b39`。B2只有固定noise_settings/end兩路EndIslands、sloped_cheese ADD Direct/Reference與必要incoming/outgoing、owned2076bytes/三層identity/codec replay及每use canonical charge；end/base_3d_noise真InterpolatedSampler尚未支援，drain/finish仍拒，不發完整End FP或snapshot。此B2首輪無已提交fix round，完整run-all留C1資料整合後；本機CI規則PASS不是三bundle。

B3先期唯讀核固定1.21 common JAR SHA b50eb354…、Interpolated/Octave/Perlin欄位與B28 case10–13/39–40；不搬其setAccessible。ignored `task-08/8b2-c1b-b3-design/PROPOSAL.md` SHA `422865422b79ab5ff7a75cd19da76fdfeffbdb0b68adcc78cf65922b81b9e622`、javap SHA `95927be8679f4862ac22adf7ea69493cab7924d6141d013570c40f5c2b7bad05`，獨立設計review C0/I0/M1，摘要 `DESIGN-REVIEW.md` SHA `22fe1881f6a086018299aa034e5294ad01887afd63740bd381734571d57b2fe5`；M1補充 `PROPOSAL-ADDENDUM.md` SHA `fc9badafab5ac8e8563693b6899126f1bee192de798787a9b16d0dc41c846c27`：逐槽identity快照與seal陣列須clone，非首槽同raw新identity也必拒。B3三Mixin＋config正式compile/runtime/RED/GREEN/full尚未做。B2 code與本B3 owner文件經docs truth後正常push，新B3候選綁實際新HEAD。C1c/d、100ms live hold I-1、8B3三bundle與Task9–20仍未完成。

### 2026-10-29：C1b-B3 base sampler橋接結案，C1c template v2設計待實作

B3基準local/origin `96a6311e61e4b2885d5e446500917710e424f05d`、tracked clean；唯一實作者只改三個Interpolated/Octave/Perlin窄Mixin、NativeValueContracts、main mixins.json與同CanonicalizerTest六檔，候選 `530491bde96b022b25363d669899caceeb017915`。原method名傳TestClass造成父scope FAIL不採；正式RED `task-08/8b2-c1b-b3-evidence/RED.xml` 57案7具名fail（B28 case10–13/39–40＋BASE），開發GREEN57/57；終局Registry Reference同value換物件 RED `TERMINAL-RED.xml` 70案1fail，修後GREEN70/70，保所有原root。三Mixin固定1.21真命中，sampler三層owned raw bytes/slot-null次序/64octave/256permutation/identity及CODEC重建均守住；非首槽同raw不同identity拒。

乾淨530的`M6Compile` PASS；雙focused `focused-c1b-b3-clean-1-2e589d50382148a292b19b7e87a17de5`／`focused-c1b-b3-clean-2-262f73ebe65249f2b1ce1870c5efa551` 各70/70、scope同HEAD/PASS、0skip/fail/error。完整`run-all-4fdb961002a3414e808c1cb12f488ba8`自然END ALL exit0 HEAD530，console SHA `df5b38080d20e49c8e3dfd6ffeec3e913a457540697cf940e5d0c8cfe18d4bf1`、summary SHA `df5f4530e35963c385217d31d853ee42253b20cf9cf759164e0de0e997aa8dc9`，JUnit778 f0/e0/skip2、default179/179、M5 60/60、M6 8/8、mainOnlyPASS、recovery24、collapse69、CI規則win/ubuntuPASS。release/sources JAR實際hash符合summary且testmodHits0；quality另核三Mixin 11/6/4 shadow欄位descriptor轉intermediary，無named shadow殘留。獨立spec/quality最終各C0/I0/M0；結果索引 `task-08/8b2-c1b-b3-evidence/B3-FINAL-RESULT.md` SHA `7e04ebb77c67816b30f447d1e222ff11bd4039adba8bb29684fb1a147e898df6`。B3只閉固定End base required node；未知/foreign仍拒，完整三role/FP/snapshot未證。

C1c template唯讀v1提案 `task-08/8b2-c1c-design/PROPOSAL.md` SHA `d9d4ee4c29d247fad7d7890834112c83a67a52ed161fb7c4d559a8384cf8bcce` 首審C0/I2/M1（public raw accessor仍可外洩、RecipesUsed錯強迫canonical key）。另存v2 `PROPOSAL-v2.md` SHA `086ced87f0bc9257c697053ba9912490254210c57a4c92c83f0c249b1dadaba0`，獨立窄複審C0/I0/M0，摘要 `V2-DESIGN-REVIEW.md` SHA `5ff85b467e8112af67966e2266f161472697cf077b141e75e8422050bf2786fe`。v2限八owner：安全mixin消費端CapturePermit驗exact lease後深owned DTO、五cache態/source priority/native rebuild、非空pool→Single CODEC location、TemplateValueContracts真loot/component與RecipesUsed原key literal；四鍵/envelope/五態×來源/雙順序/身份負例列實作RED。100ms live hold I-1仍未量，不發布snapshot；8B3真server/三bundle與Task9–20仍待。本節code驗證pin為530，後續push狀態以Git/STATUS讀回。

### 2026-10-29：C1c-T/A template局部閉包結案，C1d待辦

T基準`54c7979`、code `d0bf69b`，兩新增檔建立owned typed NBT窄契約；四輪正式RED 11/11、16/1、18/1、20/2失敗均保原件，最終乾淨M6Compile及雙focused各20/20、0 skip/fail/error。獨立spec／quality各C0/I0/M1。T僅支援白名單furnace／lootable／pool-only jigsaw及窄item/component，RecipesUsed保原key/type/int而不查RecipeManager；不讀live manager/cache/source，不構成完整C1c。原件`task-08/8b2-c1c-t-evidence/T-IMPLEMENTER-RESULT.md`。

A binding設計docs `29a5988`、code `bf46e97d262b911a19f2a1437461ce1a8dbeacf8`，八owner接通source priority、五cache態、同live lease深owned擷取／隔離native rebuild及非空pool exact Single CODEC left局部閉包；未知／foreign source與漂移具名拒絕。原owned/cache/isolated RED及新增SOURCE49/9、SINGLE75/5、SOURCE-NEGATIVE53/1均保原root，Single兩次中途FAIL75/3、75/1與首次full長cwd前置FAIL `run-all-93b4d8d63c6547cc98ca1d97f24d3d5f`未改判。乾淨M6Compile PASS；Adapter54/54×2、Canonicalizer76/76×2，均0 skip/fail/error。完整`.superpowers/verification/run-all-55870333f3bb422b9f53535574e302ec`自然`END ALL exit=0 HEAD=bf46e97`，summary SHA256 `2d6fc699ae0292500da48303a85811078860f4c508ccd139c02ca3c3c712107f`、console SHA256 `a0c403457c1bf2bceae4cb75cd41cdce3a6cbbcfdf64c28fdeccb2781d27c06a`；JUnit838 f0/e0/skip2（既有Windows skip）、default179、M5 60、M6 8、M4 recovery24、collapse69、main-only PASS、release/sources testmodHits0；M6 native兩phase仍診斷。獨立spec C0/I0/M0、quality C0/I0/M1；M1為manager來源實讀下raw/total-raw計費與depth未直接驗。結果原件`task-08/8b2-c1c-a-evidence/FINAL-RESULT.md` SHA256 `f87c2025ff5bb82b017da7143c11e34c2b3e28563c83358c7d11e685481aa6f5`，`FINAL-RESULT-HASHES.json`與`ADMISSION.md` SHA256 `55c531199af8f03583d0e78ad6cb75a37461b3d34ba95beea9c3da1b4e57bfd9`。win/ubuntu PASS僅本機CI oracle，非遠端CI；本筆docs未重跑full。

範圍僅局部template source/cache/Single。BLOCK/ITEM/PROCESSOR_LIST/LOOT_TABLE等leaf、Vault/TrialSpawner/entity、完整OW973/N210/E20與three-role FP/provider/snapshot皆待；`UniverseProfileRegistry.createSnapshot/resolve`仍fail closed。C1d先完成owner census及typed leaf/consumer白名單，之後100ms單次live hold I-1與8B3真server／三bundle也須關閉才進Task9。v4父FAIL與B27 FAIL不改判，人工與整分支仍待；新docs HEAD不冒上述code pin已驗full或遠端CI。

### 2026-10-29：D0-O原始分母與D1a未結案交接

D0-O純離線六組在前兩個新root依序觸每role 16MiB與total treeNodes 8M，上限修訂均另設方法／原件／獨立審查，不改判舊FAIL。第三個root `.superpowers/sdd/2026-10-24-m6-universe-generation/task-08/8b2-c1d-census-offline/run-89ca6200-a1fb-4856-bc0c-b24a8ea76b6a` 自然 RAW_CENSUS_COMPLETE，獨立結果審查C0/I0/M0：六組、2406 memberships／1174 unique模板、97,494 template edges、treeNodes12935322、pre/post pins各73、1181輸出逐檔SHA/size全符。這只接納raw分母；consumer/source/production admission仍false，N-R未放。jigsaw N-S靜態局部規則C0/I0/M1；scope/use codec合成基底v2 C0/I0/M0，真native與六圖replay未啟。

D1a原程式候選 `877d20f041b5d853ab227fc444ea9c6413924fbb` 限RegistryClosure與CanonicalizerTest兩owner，乾淨M6Compile與focused85/85×2、完整 `run-all-d4822a217c9d48f2894cfc83bf2a1bc0` 自然END ALL exit0／JUnit847 f0e0 skip2；但獨立spec/quality各C0/I1/M1，I1為同大小List.set期間可能發布混合processor清單。固定原生DFU List.copyOf／RegistryLoader證據的I1設計補件v2已獨立C0/I0/M0，僅限正常原生解碼、非任意第三方writer/ABA。

fix1 code `165b8b9c9d1db6999b65b39a201f35bc9397bdd8`（range 877d20f..165b8b9）只在原兩owner加bounded第二iterator identity重核及三個精確測試；same-size具名RED88案1fail→GREEN88/88。乾淨M6Compile與第一次focused88/88通過；第二focused及新nonce補驗皆88案1fail，既有templateStateEdgeResourceRawCanonicalBudgetOverflowRejected先在native重建碰100ms `TEMPLATE_SNAPSHOT_NOT_READY`，未達預期BUDGET_TOTAL_RAW；原兩FAIL完整保留，fix1 full尚未啟，沒有fix1最終code review。接手先核ignored `task-08/8b2-c1d-d1a-evidence/IMPLEMENTER-HANDOFF.md`（SHA256 457d9f7b01aed648b4fc6ef376e710d5d5833380784af23fea39175ef0d4d44a）及Git，不提高100ms或production cap；下一步是原測試owner的窄fixture fix2、clean雙focused/full及獨立review。詳新 [`Claude續作提示詞`](../handoffs/2026-10-29-m6-claude-resume-prompt.md)。本段只記部分實作與交接，D1a／C1d／Task8及Task9–20皆未完成。

### 2026-10-29：D1a fix2 與結案（Claude 接手）

Claude 開工實核：local／origin feature 同 `62a2f72`、tracked clean；main `0d05f88`、tag m5 一致；只有閒置 Gradle daemon；fix1 兩次原 FAIL 原件完整保留，不改判。

fix2 先做具名診斷，推翻交接中「BUDGET_TOTAL_RAW 在同一 lease 重建 10 次」的假設（證據在 ignored `task-08/8b2-c1d-d1a-evidence/FIX2-*`）：
- 具名 RED root `focused-c1d-d1a-fix2-red-3bd6e097d5164d9c88df8c383c3d5144`（88 案 1 fail，只含兩個刻意的邊界 RED）：一份原生 blank template 的 charge 為 328；原 fixture（totalRaw=512）在第 2 次 occurrence 就超過剩餘 184，第 3～10 次從未執行；totalRaw=charge-1 時單次就被拒，2×charge 時兩次都不拒。
- 不暖機的 DIAG2 root `focused-c1d-d1a-fix2-diag2-464d2cac0c5f43c9b06b1eb960d771a8`（88/88）：第一個會 capture 的子案 BUDGET_RESOURCES，lease 內 drain 33.1ms；RED run 在方法開頭先算 charge，等於已暖機，該子案只要 9.5ms。兩者相差約 23.6ms，是落在 lease 內的冷成本。
- 兩次原 FAIL 都停在 `privateRebuild` 新建 template instance 之後的 checkpoint（`requireManager`／`chargeOwned`），但這並未證明是 JVM 第一次載入；舊 XML 沒有子案名，原失敗子案未能定位。以本測試耗時比例粗估（0.269s 或 0.234s 對 0.125s，乘上 33.1ms），失敗時這個 lease 約 71ms／62ms，仍低於 100ms；而且 fix1 GREEN 的耗時是 0.287s 卻通過，測試耗時只是很粗的代理指標。
- DIAG2 依派工的預設判讀規則判定為未成立（STOP_NO_FIX）。fix2 是 controller 之後以「冷成本確實在 lease 內、可以移除」為由裁示續作，只算降低風險，不宣稱根治，也不認定為原 FAIL 的成因。

fix2 code `9d97cd0854b1139d040063ac3d58424a3b76462c`（range `62a2f72..9d97cd0`）只改 `Minecraft121SettingsCanonicalizerTest` 的 `templateStateEdgeResourceRawCanonicalBudgetOverflowRejected` 一個方法：
- 方法開頭、任何 lease 之前算 charge，兼作 JVM 首次載入的暖機；
- BUDGET_TOTAL_RAW 改為 totalRaw=charge，1 次 occurrence 的正例 drain／finish 成功，2 次相同 occurrence 的負例為 BUDGET_TOTAL_RAW、finish 為 GRAPH_FAILED；
- 每個子案的失敗訊息常駐子案名；其他 5 子案、100ms、raw cap 與 production 都不變。

GREEN root `focused-c1d-d1a-fix2-green-67d2ef88317b4130af43b41b24c8e4cd` 88/88。乾淨 `9d97cd0`：M6Compile PASS；focused `focused-c1d-d1a-fix2-clean-1-a9bd437970404d1caf4f6464257cb44b`、`focused-c1d-d1a-fix2-clean-2-c5dc4938d7674e75bcf0aa41a5fa1acd` 皆 PASS；完整 `run-all-3990c81a33b84ee9961ae54b63f4b752` 自然 END ALL exit0：JUnit850（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、testmodHits0、release 502 entries／455 classes；console.log SHA256 `83bf09c346df6b5c3c1f91796e5cd796af2d7b8d7ddae17210854d4144a2b187`、summary.json SHA256 `2c72c9bcd5e0b9c29835761e38b67120f7e20ea3cf0a7fca9406ef94fdde98cd`。驗證腳本前後 `Assert-Pin` 皆通過。本機 CI oracle 不等於遠端 CI。

全新獨立審查（整段 `f1e0bef..9d97cd0`，含原候選與 fix1）：spec C0/I0/M5、quality C0/I0/M6 Approved。877d20f 的 I1 已由 fix1 依 addendum v2 的限定前提（固定原生 codec、正常遊戲無 writer）關閉；原候選審查的 M1 原文不在磁碟上，改由本次 fresh review 從頭審。報告為 ignored `FIX2-SPEC-REVIEW.md`（SHA256 `fcc22aa22f7bbf6a8cf33e8388985c74721a6693782316abebdd891ca51e722b`）與 `FIX2-QUALITY-REVIEW.md`（SHA256 `4f8a408570f21679681f9d9a53fb2595f8339e492593b8468e9ff3743bf65884`）。

**D1a 結案**，但只限 named PROCESSOR_LIST empty／NOP／LAVA 的局部閉包；這段 closure 目前沒有 production caller，`createSnapshot/resolve` 仍 fail closed。11 項 Minor 交下一個 C1d 切片的 Step0：
- spec M1：負的 size，以及 CME 以外的 iterator 例外，未歸類為 PROCESSOR_LIST_CHANGED。
- spec M2：null list、size 例外、清單縮短、CME、第二遍件數不符，缺直接負例。
- spec M3：RegistryClosure 類別 Javadoc 未更新。
- spec M4：charge「精確邊界」的措辭，或補 charge-1 負例。
- spec M5：fix2 的根因證據不足，不可宣稱根因（DIAG2 判定 STOP_NO_FIX，原失敗子案未定位，粗估仍低於 100ms），docs 只能寫成降低風險；BUDGET_EDGES 的第一個 lease 仍有 30～45ms 的 pool codec 冷成本。
- quality Q1：`namedProcessorCaptureOwnsEarlierGraphAfterSourceMutation` 的舊圖斷言，因 finish 有快取而恆成立。
- quality Q2：沒有測試證明 processor payload 受 Graph canonical／owned-bytes 預算約束；`namedProcessorCountCapIsIndependentOfGraphBudget` 的 BUDGET_CANONICAL 負例與 `namedProcessorRepeatedUsesKeepBothEdgesAndBudget` 的 BUDGET_EDGES 負例，都在 reference 階段就失敗，根本沒走到 processor 分支（與上述 budget 測試的同名子案無關）。
- quality Q3：缺多元素清單的型別負例。
- quality Q4：改錯誤碼時 initCause 不一致。
- quality Q5：4096 缺命名常數與出處、邏輯未抽成方法、沒有逐項 checkpoint。
- quality Q6：BUDGET_TOTAL_RAW 子案改拆成 @Test，或斷言 finish 的回傳。

若同一測試日後再出現 NOT_READY，視為新的 FAIL，保留原 root，不重送改判。C1d 其餘部分（BLOCK／ITEM／LOOT_TABLE、Vault／TrialSpawner／entity／component、非模板閉包、三 role FP／provider／snapshot／resolve、100ms live hold I-1）、8B3 三 bundle、Task8 終局與 Task9–20 仍待。

D1a 遠端 CI：docs commit `307565fc6c22b2066f7b1359c13f7d97ad8603ce`（含 code `9d97cd0`）的 GitHub Actions Build run `40836148542` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩 job 皆 success。只讀回 job 結論，沒有 audit artifact 內容，也不是 8B3 三 bundle。

### 2026-10-29：C1d Step0（D1a Minor 收斂）

範圍只限 D1a 兩個 owner，不擴充任何 leaf 或 consumer：commit `d561520`（只改測試）與 `242c20b3715ccdc64f68f3923809bdbdd542ef5a`（`Minecraft121RegistryClosure` 加對應測試）。
- production：
  - PROCESSOR_LIST 分支抽成 `expandProcessorList`，除下列修改外與舊碼等價；
  - 負 size 在配置之前拒絕；
  - iterator 的 CME／NoSuchElementException 歸類為 PROCESSOR_LIST_CHANGED；
  - 兩個 catch（`size()` 例外，以及 CME／NSEE）一律 `initCause`；
  - owned copy 的逐項迴圈比照 `expandPool` 加 checkpoint；
  - 類別 Javadoc 補上 named PROCESSOR_LIST 的局部範圍。
- 4096 抽成 `MAX_NAMED_PROCESSORS`。出處是 D1a 設計（ignored `task-08/8b2-c1d-design/D1A-PROPOSAL-v2.md` 第 28 行）：這是 D1a 新增的保守 pre-encode 工作量上限，恰 4096 接受、4097 拒；沒有原生上限依據，不取代 Graph 的 edge／state／raw／canonical／depth 預算，也不保證完整 vanilla 配置可用。quality 審查另指出：同一次 drain 若有多個接近 4096 項的清單，lease 內的 codec 工作可能逼近 100ms，結果是 fail closed（NOT_READY）。這是 live hold I-1 與這個上限之間的取捨，列為 I-1 的輸入。新加的逐項 checkpoint 沒有專屬的鑑別測試，因為不准用 clock override；依等價論證，拿掉它不改變 fail-closed 結果。
- 測試從 88 變 93：
  - 來源漂移與不可讀的直接負例：null list、size 例外、清單縮短、CME、第一遍後 size 改變、第二遍較短／較長、第二遍後 size 改變、負 size、NSEE；
  - `namedProcessorCaptureOwnsEarlierGraphAfterSourceMutation` 改為 drain→mutate→first finish，與獨立擷取的 bytes 比對；
  - processor payload 在 drain 階段受 canonical 預算約束；
  - 多元素清單的型別負例；
  - BUDGET_TOTAL_RAW 拆成獨立的 `templateTotalRawAllowsOneChargeAndRejectsAccumulation`，含 charge-1／charge 單次／charge 兩次三例。

  兩個 template budget 測試都在任何 lease 之前先算 charge 暖機；註解只寫降低風險，不寫根治或成因。

證據放在 ignored `task-08/8b2-c1d-step0-evidence/`：
- 具名 RED `focused-c1d-step0-red-914093591eb54318be69c4d6b9f6c6db`：93 案 2 fail，原因是負 size 拋 IAE、NSEE 未正規化、size()／CME 沒有 cause。
- GREEN `focused-c1d-step0-green-40fa111be00e425f8557aaed9f7ae0ad`：93/93。
- Q1 的突變證明 `focused-c1d-step0-q1-mutation-df4c0f3851ec4d1b8c053c38e1b95375`：新寫法 FAIL、舊寫法 PASS。為了讓舊圖在 finish 時讀到改過的來源，這次突變暫時改了範圍外的 `Minecraft121SettingsGraph.java`，以及 closure 與測試檔。三檔都已還原並逐檔核對 hash，沒有進 commit；Graph 檔在 HEAD 與 `9d97cd0` 是同一個 blob。

乾淨 `242c20b` 的驗證結果：
- M6Compile PASS。
- focused `focused-c1d-step0-clean-1-38bbbccf7d8742a29d83008a077231e3`、`focused-c1d-step0-clean-2-813f16645c8b4c1692826ee171695453` 皆 93/93。
- 完整 `run-all-41d76dffa3104602a75c8836ff2184ae` 自然 END ALL exit0：JUnit855（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、testmodHits0。console.log SHA256 `0ce56107cf330a6c564121dba1bc42e44189c275b263fedab1a745f107bd3950`，summary.json SHA256 `961d9b8c4b57f068e9adaffe3394038b8876fe4244951812aff9d3b235b28e77`。
- 驗證腳本的 `Assert-Pin` 全部通過。本機 CI oracle（`ci=win:PASS,ubuntu:PASS`）不等於遠端 CI；`242c20b` 的遠端 CI 要等本筆 docs push 之後才讀回。

D1a 那 11 項 Minor 的處置：spec M1～M4 與 quality Q1～Q6 都在本輪完成。spec M5 屬 docs truth，D1a 結案文件已寫成「降低風險、不認定成因」；BUDGET_EDGES 第一個 lease 的 30～45ms 冷成本是殘餘風險，歸 live hold I-1。

全新獨立審查：spec C0/I0/M0；quality C0/I0/M4，Approved。報告是 ignored 的 `STEP0-SPEC-REVIEW.md`（SHA256 `3706958c1a4ba539a5eafd832915e1e846e8867d0d5e2468652ac5c0ef864888`）與 `STEP0-QUALITY-REVIEW.md`（SHA256 `dda9c4b1e8b187d540dd91d51f3d82a4a6946faf25a8193e838cf98c3cb56c54`）。

新的 Minor 編為 S1～S4（與 D1a 的 spec M1～M4 區分），併入下一個實質 C1d 切片（D1b）的 Step0，不另開清理輪：
- S1：第一次 `size()` 以外的讀取點（包括第二、三次 `size()` 與 iterator），CME／NSEE 以外的 RuntimeException 沒有正規化，且 catch 旁的註解不精確。
- S2：`processorListChanged` 沒有斷言 require 路徑必須沒有 cause。
- S3：`namedProcessorCountCapIsIndependentOfGraphBudget` 殘留 reference 階段的空轉負例。
- S4：processor 負例 helper 要一般化，Q2 測試改用 assertAll。

另有兩項既有問題，本輪不改，同樣由 D1b Step0 處理（兩者都在 CanonicalizerTest，屬 D1b 的 owner 檔）：
- template budget 測試的 BUDGET_CANONICAL 子案，也在 reference 階段就失敗（出處：Step0 quality 報告的觀察 1）；
- noise 測試 `sameTypedIdChangedNativeValueChangesOwnedBytes` 先 finish、改值、再 finish，因 finish 有快取，舊圖斷言恆成立（出處：D1a `FIX2-QUALITY-REVIEW.md` 的 Q1）。

下一個實質切片依 ignored `task-08/8b2-c1d-design/SEQUENCE-PROPOSAL.md` 的 D1b（BLOCK／ITEM 有效值與 default／effective component）。這份順序提案是 Codex 寫的，本身標示「未審」；D1b 要先寫設計提案並經獨立審查，才能實作。

Step0 遠端 CI：docs commit `0998f677d3dacce9041507dfcb3ec53c4cdc66cd`（含 code `242c20b`）的 GitHub Actions Build run `40845641782` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 都是 success。只讀回 job 結論，沒有審查 artifact 內容，也不是 8B3 三 bundle。

### 2026-10-29：D1b 設計放行與 D1b-0（Step0）結案

**D1b 設計**（全部是 ignored 文件，位於 `task-08/8b2-c1d-design/`）：
- `D1B-PROPOSAL.md` v1（SHA256 `c685d0da36d102c03c0b77b30d03b115e112067fae88f75641e9e007bb306d0a`）經全新設計審查，結果 C0/I1/M7（`D1B-DESIGN-REVIEW.md`，SHA256 `d9671dbde86b99b125c2f56521f7f2a4e83251654893ef8be1d7a5e2dd1a3278`）。事實核對全部成立，包括：
  - RED 位置；
  - census：每個 role 的 BLOCK 數為 924／857／853、ITEM 數為 290／147／106，聯集為 928／314；D1b 實際入口為 BLOCK 401、ITEM 10；
  - javap 的 consumer 證據；
  - Fabric 在 runtime 改寫 `Item.components`；
  - Block 與 Item 之間沒有互相引用的邊；
  - trapped_chest 被誤拒。
- I-1：測試計畫中有兩項無法在 finally 還原，因為完整 JUnit 共用同一個 JVM，會汙染其他測試：
  - BLOCK tag populate；
  - 註冊 foreign Item。
- `D1B-PROPOSAL-v2.md`（SHA256 `4efd44fc691c51d5badb28f00744e3b8be1eb5f05e1db54ef225a38f423defa7`）改寫測試計畫，並逐項修正 M1～M7。原審查者窄範圍複審（`D1B-DESIGN-REREVIEW.md`，SHA256 `adf099ca5b99d968d273f2ccf4e53c8a8c66988642d02f13d44334bc9b290766`）結果 C0/I0/M5：I-1、M1～M7 全部 CLOSED，**放行 D1b-1 實作**；5 項 Minor 隨 D1b-1／D1b-2 派工修正。
- Q1～Q10 都由 controller 定案，沒有需要使用者裁示的項目。v2 新增三項行為，處置如下：
  - N1 採嚴格做法：ITEM leaf 拒絕 block 為 foreign class 的 BlockItem，因此 mod 的 BlockItem 會 fail closed。這與 spec 第 73、104 行不衝突，玩家也看不到差異。
  - N2：ignored `SUPPORT-OWNERS.md` 第 9 列在實作時同步加註。
  - N3：採用。
- 切分：D1b-0（Step0）→ D1b-1（BLOCK leaf，新增 `Minecraft121RegistryLeafContracts` 及其測試）→ D1b-2（ITEM leaf，含真 chest 模板）→ D1b-3（會引用其他 registry 的 default component，需另行設計）→ D1c 的真資料整合。D1c 的真資料 GREEN 依賴 D1b-3。

**C1d 待辦**（D1b 設計審查要求寫入 tracked note）：
1. world enabled feature set 屬於 spec 第 59 行要求的資料控制值。`MinecraftServer.<init>` 以 `saveProperties.getEnabledFeatures()` 過濾 template 的 block lookup，D3／D4 必須納入；D1b 只 admit vanilla 子集，作為暫時的 fail closed。
2. End 的 `default_block`／`default_fluid` 缺 BLOCK 邊，是 C1b-B2 的既有缺口（`Minecraft121NativeValueContracts.java:175`），由 D3 補。
3. Block Java 方法中 hardcoded 的 tag 讀取（B28 有 87 個 block-placement 群組）另開切片，排在 D2 之後、D4 之前。在那之前，不宣稱 palette 的語意已閉包。
4. 對 spec 第 73 行「原生可編碼值」的解讀：取生成 consumer 實際解碼的 state definition 與 default state。`BlockTypes.CODEC` 不納入，因為在固定 JAR 中引用它的只有 `data/report/BlockListProvider` 與 `Registries`，沒有生成 consumer。
5. 交 D2 的 Step0：
   - trapped_chest 的 BE owner 被誤拒（`Minecraft121TemplateValueContracts.java:98`，fail closed）；
   - palette 使用寬鬆 codec；
   - `containsId` 沒有依 feature 過濾；
   - `nativeSource` 的另外三份複本：TemplateValueContracts、TemplateSettingsAdapter、NativeValueContracts（D1b-1 spec 審查補充）。
6. D1c 的真資料 GREEN 依賴 D1b-3。
7. BLOCK tag 次序的 closure 測試延到產生 BLOCK tag edge 的切片（預計 D1b-3 的 tool）。那個切片要先設計可還原、或不碰全域 registry 的 tag 測試技法。

**D1b-0（Step0）**：
- commit `cd4ec85`（test）＋`0efb058a79a2387096045922c972e3515bda021a`（fix）。只改 RegistryClosure 與 CanonicalizerTest；測試數從 93 變 95。
- production 只有 S1：`expandProcessorList` 的第一次 `size()`、require、配置與兩遍迭代改放進同一個 try；catch 先把 `Failure` 原樣 throw，其餘 `RuntimeException` 改碼為 PROCESSOR_LIST_CHANGED 並 `initCause`。逐項 checkpoint 留在 try 之外。
- 測試：
  - processor 負例 helper 一般化，並斷言 cause；
  - 刪除在 reference 階段就失敗的空轉負例；
  - Q2 改用 assertAll；
  - template budget 測試把 payload 與 reference 移出 `rejects`，並在 lease 外暖機 pool codec 的 encode 路徑；
  - 新增 `templateCanonicalBudgetRejectsInsideDrainAtExactBoundary`：自行量出 L=2067，canonical=L 時通過，L-1 時在 drain 內拒絕；
  - noise 測試改為 drain → 改值 → 第一次 finish。
- 證據（ignored，`task-08/8b2-c1d-d1b0-evidence/`）：
  - E1 RED `focused-c1d-d1b0-e1-red-7b7acb057e6640a0a15de9db7c3a68df`：93 案 1 fail，原 CANONICAL 子案在 reference 階段就失敗，從沒進入 drain；
  - S1 具名 RED `focused-c1d-d1b0-red-731c77e552684a2592b8eaf7c61d9da8`：95 案 1 fail，三種裸例外直接逸出；
  - GREEN `focused-c1d-d1b0-green-ff716c9d83e44ca0b554b3f6fbd5ace2`：95/95；
  - S2、E2 的突變見證。E2 曾暫時修改範圍外的 Graph 檔，已還原並核對 hash；Graph 的 blob 在 HEAD 與 `9d97cd0` 相同。
- 乾淨 `0efb058` 的驗證：
  - M6Compile PASS；
  - focused `focused-c1d-d1b0-clean-1-2936b55c3e664208beeacd0750ae80fe`、`focused-c1d-d1b0-clean-2-67b48f3b0adb42b3942efaafa2287e3c` 都是 95/95；
  - 完整 `run-all-359153a6930a42c7a8b9a05df4901f9e` 自然結束，END ALL exit0。JUnit857（0 fail／0 error／2 個既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、testmodHits0。console.log SHA256 `4097282377245295d82f85815860df00ab5fc8c04b9cd168a190ef8889a90a2b`，summary.json SHA256 `107ea3262984cc0113213614ca1a08f928e2a0dac658cca003e1e2c18382b721`；
  - 本機 CI oracle 不等於遠端 CI，`0efb058` 的遠端 CI 要等本筆 push 之後才讀回。
- 全新審查：spec C0/I0/M1（`D1B0-SPEC-REVIEW.md`，SHA256 `7f10dce91438ce0296577ba1edbd656120c47af022bbe9dfce91af9d029bac65`），quality C0/I0/M2，Approved（`D1B0-QUALITY-REVIEW.md`，SHA256 `0816b34d73ddaee51904454765864130d5b89c741df9da7d8d456af7c67281bd`）。三項 Minor 併入 D1b-1 派工：
  - `warmPoolCodec` 的 Javadoc 說得比實際暖機的範圍寬；
  - 寬 catch 依賴一個未寫明的前提。要寫成註解規則：try 內只放來源讀取，以及前置 require 已保證不會丟例外的本地操作；checkpoint、graph、codec 必須放在 try 外。D1b-1 會在 LeafContracts 重用這個模式；
  - E1 的三張圖要抽成共用工廠。

D1b-0 遠端 CI：docs commit `b7d539aa067049a423ef437abada88716fcc649a`（含 code `0efb058`）的 GitHub Actions Build run `40863093293` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 都是 success。這裡只讀回 job 結論，沒有 audit artifact 內容。

### 2026-10-29：D1b-1（BLOCK static registry leaf）結案

**commit**：`9cfab02`（RED 測試）、`df3e66b`（feat）、`58a2ff9eae62c759778e6c6eb158a53d82bf0e7c`（診斷輸出改 ASCII）。只動四個 owner：
- 新增 `Minecraft121RegistryLeafContracts`；
- 新增 `Minecraft121RegistryLeafContractsTest`；
- 窄改 `Minecraft121RegistryClosure`：加 BLOCK 分支，並把 `nativeSource` 改為委派；
- 窄改 `Minecraft121SettingsCanonicalizerTest`。

ITEM 屬於 D1b-2，本輪沒有做，也沒有留空殼。

**行為**：
- BLOCK leaf 的 payload 只有四個鍵：`Contract=quantumchamber:m6-static-block-leaf-v1`、`DefaultState`（`NbtHelper.fromBlockState`）、`PropertyDomains`（依原生次序）、`RequiredFeatures`（只 admit vanilla 子集）。Settings、Java 行為、`BlockTypes.CODEC`、`asItem` 與 state id 都不入值。
- 檢查依序是 registry identity → class 與 source → intrusive identity → features → 原生 property 與值型別 → default state roundtrip；每一步都有具名錯誤碼，`Failure` 原樣透傳。
- BLOCK 經 `definitions` 去重，leaf 沒有子邊。
- payload 範例：stone 180 bytes、oak_stairs 503 bytes。
- 成本診斷（不 gating）：1060 個 BLOCK leaf 冷啟動合計 30ms、暖啟動 11.5ms，p99 137µs。這些數字是 I-1 的輸入。
- D1b-0 審查的三項 Minor 也已處理：`warmPoolCodec` 的 Javadoc、把寬 catch 的前提寫成註解規則、E1 三張圖改用共用工廠。

**測試數**：Canon 從 95 增為 103，LeafTest 新增 7 個。

**證據**（ignored，`task-08/8b2-c1d-d1b1-evidence/`）：
- RED1 `focused-c1d-d1b1-red-d52da108…` 保留為原件，見下方「與設計前提的衝突」。
- 具名 RED2 `focused-c1d-d1b1-red2-069b3c36…`：103 案中 6 個停在 `UNSUPPORTED_REGISTRY_DEFINITION`。
- GREEN：canon 103/0、leaf 7/0。
- 突變見證 1、2、5a、5b、6、8 都能鑑別，而且只改 LeafContracts，跑完已還原。

**與設計前提的衝突（更正 v2 §5.6／§12）**：
- Canon 的 `EndFixture` 經 `CombinedDynamicRegistries` → `ImmutableImpl.toImmutable()` → `freeze`，會凍結全域 static registry。freeze 之後無法註冊。
- 所以 JVM 裡第一次觸發 `ModBlocks.<clinit>` 若晚於 freeze，就會丟 "This registry can't create intrusive holders"，而且這個 class 在該 JVM 會永久不可用。RED1 就是這個情況；javap 證據見 `D1B1-MODBLOCKS-FREEZE-JAVAP.txt`。
- 設計原本假設「`MembraneStateTest` 等測試已在同一個 JVM 先觸發」，這個前提不成立；fabric-loader-junit 在測試前也不會執行 mod 的 `onInitialize`。
- 修正方式：在 Canon 的 `@BeforeAll` 呼叫 `ModBlocks.register()`。這只會觸發既有的 class init，可以重複呼叫，沒有新增註冊。
- 殘餘風險：
  - Canon 的 freeze 是 JVM 全域的，D1b-1 只處理了 ModBlocks。`ChamberControllerBlockEntityNbtTest` 經 `ModBlockEntities` 寫入 `BLOCK_ENTITY_TYPE`，所以它若排在 Canon 之後，仍會失敗。這是既有依賴，本輪不改。
  - 日後新增的測試，若經 CombinedDynamicRegistries／toImmutable 碰到 static registry，必須先完成 ModBlocks 的初始化。
  - `ModItems` 受同一機制影響，D1b-2 維持完全不引用。
- 這次完整 JUnit 的 class 順序是 `chamber.*` 先於 `universe.*`。

**乾淨 `58a2ff9` 的驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b1-clean-canon-1-7c220cc5128d40beaf2f80e0f05547e5`、`focused-c1d-d1b1-clean-canon-2-ffdbb0c9167140f49d7b50228ad8bf1f` 都是 103/103。
- LeafTest focused `focused-c1d-d1b1-clean-leaf-1-390308d56b28406ea4bb0427b0bf3711`、`focused-c1d-d1b1-clean-leaf-2-9d6d2858a358483a90e6b6c8797540a5` 都是 7/7。
- 完整 `run-all-9b4906c116d14c6ab5b42da9723209f3` 自然結束，END ALL exit0：
  - JUnit 872（0 fail／0 error／2 個既有 skip）；
  - default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2；
  - release 503 entries／456 classes，mainOnly PASS，testmodHits0；
  - console.log SHA256 `954bdf143a76e752ab1c41412509468bd1e18874a0b39fdb21ee8204d37b2759`，summary.json SHA256 `e69653af61f158a9cc32474b5cd329f3e8fa63c651f741b9f860e527593a13da`。
- 本機 CI oracle 不等於遠端 CI；`58a2ff9` 的遠端 CI 要等本筆 push 之後才讀回。

**全新審查**：
- spec C0/I0/M2（`D1B1-SPEC-REVIEW.md`，SHA256 `9b98354a9c398aaae0727f82d402ad1767fa7a234f80671cb7d89470732ce573`）。
  - M1：「唯一的原生 source 判斷」只在 Closure 與 LeafContracts 兩者之間成立。src/main 另有 TemplateValueContracts、TemplateSettingsAdapter、NativeValueContracts 三份，交 D2 收斂；C1d 待辦第 5 項也一併補上 Adapter 與 NativeValueContracts。
  - M2：即上方的設計前提更正。
- quality C0/I0/M5 Approved（`D1B1-QUALITY-REVIEW.md`，SHA256 `0266f660b5890eb6057f0b440f88cc81df808825ffa2c8ab03a3457de9320e02`）。M1～M4 併入 D1b-2 派工：
  - M1：`block()` 補上 node 與 key 對應的檢查，並修正註解；
  - M2：foreign source 測試加一個只帶原生 property 的 `chamber_controller` 子案；
  - M3：新增 gating 測試，斷言所有 minecraft namespace 的 BLOCK 都 ACCEPTED；controller 採用，用來防止可用性回歸；
  - M4：`BLOCK_CONTRACT` 改為 private，並調整 `value` 的取值位置。

  M5 即上方的殘餘風險說明。

D1b-1 遠端 CI：docs commit `4b16cb0d0cbd9a0f98c7a937b3265a59a03aa69a`（含 code `58a2ff9`）的 GitHub Actions Build run `40875796265` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 都是 success。這裡只讀回 job 的結論。

### 2026-10-29：D1b-2（ITEM static registry leaf）結案

**commit**：`138ca88`（RED 測試）、`3fbe626f0428d98b175107103c4b173daf36168b`（feat），同樣只動那四個 owner 檔。

**行為**：
- ITEM leaf 的 payload 只有三個鍵：`Contract=quantumchamber:m6-static-item-leaf-v1`、`DefaultComponents`、`RequiredFeatures`。
- `DefaultComponents` 的白名單與 TemplateValueContracts 相同，但扣掉 `container_loot`，本輪沒有擴充。
- 檢查依序是：registry identity → 對 `executedOwnerClasses(item)` 逐項做 source 檢查（BlockItem 包含 block class）→ node 與 key 的對應及 entry identity → features → 白名單 → codec roundtrip。
- leaf 沒有出邊；Item→Block 只經由 default component 出現，D1b 對這類 fail closed。
- component codec 用的是只含全域 static registry 的 RegistryOps。白名單內的型別都不查 registry，所以在 D1b-2 成立。
- 一併處理了 D1b-1 quality 的 M1～M4：
  - BLOCK 與 ITEM 都補上 node 與 key 的對應檢查；
  - 新增 `chamber_controller` 這個 foreign source 子案；
  - 新增 gating 測試 `everyVanillaBlockLeafIsAccepted`；
  - contract 常數改為 private，並調整取值位置。

**ITEM 的成本與覆蓋（診斷，不 gating）**：1333 個 vanilla ITEM 中，1158 個 ACCEPTED，175 個因 default 帶有白名單外的型別而 fail closed，留給 D1b-3。以含該型別的 item 數計：

| 型別 | item 數 |
|---|---|
| food | 41 |
| tool | 33 |
| 非空 attribute_modifiers | 32 |
| container | 32 |
| jukebox_playable | 19 |
| banner_patterns | 17 |
| fire_resistant | 13 |

ITEM 冷啟動合計約 98ms，這是 I-1 的輸入。BLOCK 維持 1060 個全數 ACCEPTED。

**測試數**：Canon 從 103 增為 109，LeafTest 從 7 增為 15。

**證據**（ignored，`task-08/8b2-c1d-d1b2-evidence/`）：
- `ItemAccessor` 的 setup probe `focused-c1d-d1b2-probe-9bc46ea7…`：9/0，成立，所以採分支 A，保留 `Item.components` 變更測試。測試依賴 Fabric 內部的 mixin，升級 Fabric 時要重驗。
- 具名 RED `focused-c1d-d1b2-red-a69bf53a…`：109 案中 4 個停在 `UNSUPPORTED_REGISTRY_DEFINITION`。
- GREEN：Canon 109/0，LeafTest 15/0。
- 突變見證 3、4、7 都能鑑別，另有 M2 的補證。

**與設計的差異（審查判定合規）**：
- chest、furnace、barrel 的 ITEM default 帶 `minecraft:container`，不在 binding 白名單內，所以 chest 的 ITEM leaf 依規則會被拒。這是設計表本身的矛盾。
- 因此測試改用 dark_oak_sapling 驗證「同 id 的 BLOCK 與 ITEM 是不同 node」與「leaf 不產生邊」，並另外加上 chest 的拒絕子案。
- 真 vanilla 模板的 GREEN 有實證：`woodland_mansion/1x2_a4` 的 chest 只出現在 palette（由 BLOCK leaf 處理），chest 內的物品是 sapling。
- 設計 §3.4 說 transient component 會在 codec 失敗，實際上是被直接略過，所以白名單是唯一的防線。

**殘餘風險（併入 D1b-3 Step0）**：以下只有第三方 mod 放入 foreign 實作時才會觸發，vanilla 與 Fabric 不受影響。
- `ComponentMap` 容器本身的 class 沒有驗 source，巢狀 List 也可能自稱為空、在 codec 迭代時卻吐出元素。修法是 roundtrip 之後，對原生重建出的 map 再跑一次白名單檢查。
- 原生 `SpawnEggItem` 會委派到可覆寫的 `EntityType.getRequiredFeatures`，但 `executedOwnerClasses` 只列了 BlockItem 的委派。
- 「整個 source 迴圈被刪除」沒有測試能抓到，要靠 spec review 逐行確認。

**D1b-3 的設計前置決策**：在 static RegistryOps 下，引用 static registry 或 tag 的型別（例如 potion、tool）會用全域 static registry 解析，而不是 lease 的 registries。依 spec 第 123 行，不能拿全域 fallback 冒充實際的 context，所以必須改用 lease 的 registries（seam 要多帶 registries），或證明兩者等價。

**乾淨 `3fbe626` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b2-clean-canon-1-aaa3823af73843a08e4a98b6b4e29cbb`、`focused-c1d-d1b2-clean-canon-2-1519eb9588ae4eaa8e5f63dab151e6db` 皆 109/109。
- LeafTest focused `focused-c1d-d1b2-clean-leaf-1-f32ee566c2d64ce699b92a7e4e6a3408`、`focused-c1d-d1b2-clean-leaf-2-d0093ae5911e4795ab68fb416ce6b176` 皆 15/15。
- 完整 `run-all-cb0f99493892472ab1f63860f3ed3ad4` 自然 END ALL exit0：JUnit886（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、testmodHits0。console.log SHA256 `504a3e0eb25e21aaa0bfb37025fe0f2cbe5bee60b78a0937341b2bf34481e505`，summary.json SHA256 `bef10370d940af3664a1a7a4b1994861a5fa3f318df3cae63fce03be2a839139`。
- 本機 CI oracle 不等於遠端 CI，`3fbe626` 的遠端 CI 要等本筆 push 之後才讀回。

**全新審查**：
- spec：C0/I0/M3（`D1B2-SPEC-REVIEW.md`，SHA256 `36e46901408b0435f6eec6cd46742c4d67509e39ff7b9b26f9a0314c969a6a43`），四項偏離全部合規。
- quality：C0/I0/M5，Approved（`D1B2-QUALITY-REVIEW.md`，SHA256 `ad50e4846f1648ea2ba14d03de087c928b26bb8b65ef00135281f1020c715bd8`）。確定性成立：Encoder 依 UTF-8 排序 compound key，所以 component 的迭代次序不影響 bytes。

兩份審查的 Minor 全部併入 D1b-3 Step0：
- 前述殘餘風險；
- 成本診斷的退場條件；
- ITEM 拒絕原因層級的 gating 測試（controller 已採用：結果只允許 ACCEPTED 或白名單外拒絕，不斷言數量）；
- 抽出 `requireStaticRegistry`，並讓 seam 自行驗 source；
- 變更測試先斷言 `ItemAccessor`，並補上影響範圍的註解；
- 整理測試 fixture 的重複。

TemplateValueContracts `:171-193` 也有同樣的模式，記給 D2。

**D1b 的 BLOCK 與 ITEM static leaf 至此都已閉合**（局部閉包；`createSnapshot/resolve` 仍 fail closed）。下一個是 D1b-3：會引用其他 registry 的 default component，例如 tool→block tag、attribute、food 與 potion→status_effect。它要另行設計並審查；D1c 的真資料 GREEN 依賴 D1b-3。

D1b-2 遠端 CI：docs commit `b6004de5205984a983bdc0cba1efd8206cfc26a7`（含 code `3fbe626`）的 GitHub Actions Build run `40888610474` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 都是 success。這裡只讀回 job 的結論。

### 2026-10-29：D1b-3 設計放行

D1b-3 處理會引用其他 registry 或 tag 的 ITEM default component。設計提案是 ignored 的 `task-08/8b2-c1d-design/D1B3-PROPOSAL.md`（SHA256 `9f6ba930ff55ea2abc1e488aa6ea65871fbf2fceaaade07d10405bd2d399d58d`），經全新設計審查（`D1B3-DESIGN-REVIEW.md`，SHA256 `3518edb0a95df15b92f5ed3973e55887cf1782574aedeb511b2728eff03c7de2`）為 C0/I0/M10，**可進實作**。審查者以自己的 javap 與 census 腳本重算，事實全部成立。

**前置決策（C1）**：component codec 改用 lease registries，並固定證明一組 static registry 的 identity：lease 的 `registries.get(K)` 必須與全域 `Registries.K` 是同一個物件。這組固定為 ITEM、DATA_COMPONENT_TYPE、BLOCK、ATTRIBUTE、STATUS_EFFECT，3d 起再加 SOUND_EVENT。
- 理由：ATTRIBUTE、STATUS_EFFECT、POTION、ITEM、DATA_COMPONENT_TYPE 的 codec 都直接綁定全域 registry，不看 ops。只換 ops 改不了解析來源，必須靠 identity 證明，全域解析才等於 lease 解析，這樣才符合 spec 第 123 行。
- dynamic 的 JUKEBOX_SONG 只能經 lease 解析；在 static ops 下必定失敗。
- identity 不成立時，在 codec 之前就 fail closed。

**範圍**：D1b-2 被拒的 175 個 ITEM 共 23 型；其中 B28 實際可達的只有 85 個、12 型。真正會產生 registry 或 tag 邊的只有四型：
- tool：BLOCK 直接引用與 BLOCK tag；
- 非空 attribute_modifiers：ATTRIBUTE；
- food：STATUS_EFFECT，以及 using_converts_to 引用的 ITEM；
- jukebox_playable：JUKEBOX_SONG，再引用 SOUND_EVENT。

另有 8 型在 vanilla default 中是 literal 或空的 DEFAULT 常數。其餘 11 型只出現在 B28 不可達的 16 個 ITEM，維持 fail closed，列為具名未支援。

**切分**：每片累計可接受的數量以「175 個中／B28 85 個中」表示。
- D1b-3-0（Step0）：處理 D1b-2 兩席審查列出的殘餘風險與 Minor，共 9 項。
- 3a：改用 lease ops 並證明 identity，接受 8 型 literal（66／16）。
- 3b：attribute、food，以及只有直接引用的 tool；新增 ATTRIBUTE 與 STATUS_EFFECT leaf，ITEM leaf 開始產生出邊（109／46）。
- 3c：tool 的 tag 規則；每個 TAG 節點只展開一次（OW 的成員邊會從 1,127 條避免膨脹到約 6,184 條；不改變任何既有 bytes）；tag 測試技法（140／77）。
- 3d：jukebox_playable，新增 JUKEBOX_SONG 定義與 SOUND_EVENT leaf（159／85）。D1c 的真資料 GREEN 要等 3d。

**tag 測試技法（C6）**：
- 採用 T1：seam 加 local registry，不碰全域 registry。
- 採用 T2：可還原的全域 populate 視窗。前提是所有 BLOCK tag 都為空、只使用既有的 tag key；finally 呼叫 `clearTags()`，再以後置斷言確認。審查以 javap 確認可以精確還原到原本的 JDK 空集合單例。
- T3（真 server 上已載入的 tag）交給 8B3。

**新增 owner（C4）**：`Minecraft121ItemComponentContracts`，在 3a 引入，並在 plan 新增 bullet。

C1～C15 全部由 controller 依審查定案，不需要使用者裁示。

**新增的 C1d 待辦**：
- Named tag list 沒有「已載入」旗標，「tag 尚未 populate」與「真的空 tag」分不出來，要由 8B3 保證 context 在 tag 載入之後才發布。
- JUnit 中帶 tag 的真資料閉包（例如 D1c），成員只能當作空集合下界，不能拿來證明成員已閉合。
- 冷啟動時，tool、food、effect 三種 codec 的第一次使用分別約 17.5／16.9／11.8ms（單輪值，兩輪之間差約 11%），是 I-1 的輸入。I-1 的候選方向是把不連到 TEMPLATE 的 static leaf 子圖延後到 template gate 之外處理，這屬於 D4，需要四項證明。

10 項 Minor 依審查的放行條件，分別帶入對應子片的派工：
- 3a：M1、M4；
- 3b：M2 的 Entry 部分、M3、M5、M9；
- 3c：M2 的 Named 部分、M6、M7；
- 3d：M3、M8；
- M10：在結案 docs 中修正。

### 2026-10-30：D1b-3-0（Step0）結案

**commit**：`73acdaf`（RED 測試）、`2823985a30be5dc0571139ff0913ee4a32127b77`（fix）。改動五個檔：LeafContracts、Closure（只改 Javadoc）、CanonicalizerTest、LeafContractsTest，以及新增的 `Minecraft121RegistryLeafTestFixtures`。這一輪不新增 component 型別，也不更換 codec ops。

**行為**，對應設計 §5 的 S0-1～S0-9：
- S0-1：新增 `requireReviewedDefaults`，處理順序如下：
  - 先驗容器本身的 source；
  - 迭代時，`Failure` 原樣透傳，其他 RuntimeException 改碼為 `UNSUPPORTED_ITEM_DEFAULT_COMPONENT` 並 `initCause`；
  - roundtrip 之後保留 `parse` 重建的原生 map，再驗一次白名單。

  這樣同時關閉兩種繞過：巢狀 List 自稱為空、迭代卻吐出元素；容器兩次迭代的內容不一致。

  容器的 source 檢查只擋直接 foreign 的容器。原生包裝（例如 `ComponentMapImpl`、`ComponentMap.of`／`filtered`）會委派到外部 map，它們的內容一致性由「重建後再驗」保證，所以仍然 fail closed。
- S0-2：`executedOwnerClasses` 加入 SpawnEggItem 分支。以 `getEntityType(ItemStack.EMPTY)` 取得 EntityType，並驗它的 source。
- S0-3：新增 `everyVanillaItemLeafIsAcceptedOrRejectedOnlyForUnreviewedTypes`。測試端獨立列出已審的 8 型與值條件，斷言每個 vanilla item 只能 ACCEPTED，或因白名單外型別被拒；不斷言數量。
- S0-4：成本診斷拆成 BLOCK、ITEM 兩個 `@Test`，共用 `measureLeafCosts`。**退場條件**：D1b-3 結案或 I-1 定案，以先到者為準。屆時刪除逐行的 `*_COST_ENTRY` 輸出，只留 SUMMARY、CODES、REJECTED_TYPES、COLD／WARM。不改用 system property 開關，因為 test JVM 拿不到 Gradle 的 `-D`。
- S0-5：抽出 `requireStaticRegistry`；`blockPayload` 與 `itemPayload` 兩個 seam 開頭都自驗 source。ITEM seam 的自驗沒有行為層級的 RED，因為測試無法建立 foreign Item；這是殘餘風險，靠 spec review 逐行確認。
- S0-6：變更測試在 try 之前先 `assertInstanceOf(ItemAccessor.class,…)`。
- S0-7：fixture 去重，並把測試改名。
- S0-8、S0-9：只改 Javadoc。

**測試數**：LeafTest 從 15 增為 21，Canon 從 109 增為 110。

**證據**（ignored，`task-08/8b2-c1d-d1b30-evidence/`）：
- 具名 RED：RED2-LEAF `focused-c1d-d1b30-red2-leaf-3d41ffbe…` 19/4，RED-CANON `focused-c1d-d1b30-red-canon-c2ce82ff…` 110/1。
- GREEN：leaf 21/0，canon 110/0。
- 5 個突變見證（W-S0-1A／1B／1C／2／3）都能鑑別，只改 LeafContracts，跑完已還原。
- 行為不變：ITEM 仍是 1158 件 ACCEPTED、175 件被拒，BLOCK 仍是 1060 件全部 ACCEPTED。

**乾淨 `2823985` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b30-clean-canon-1-725f946130a94cf89cecce3a40c6d8e9`、`focused-c1d-d1b30-clean-canon-2-bbb494b08469411bb911ca62c438438f` 皆 110/110。
- LeafTest focused `focused-c1d-d1b30-clean-leaf-1-95dd6351fb1a47efbd6447f2df286bf3`、`focused-c1d-d1b30-clean-leaf-2-2b4e8776997d4aba9eaedf950d7bfc6d` 皆 21/21。
- 完整 `run-all-42034bb2a7144d8bb4841f9a10b242c4` 自然 END ALL exit0：JUnit893（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、testmodHits0。console.log SHA256 `0615f4db0450f7f7f2268ad78896ae838c596c156e5ed349d820cbd1b767b352`，summary.json SHA256 `87d6faae33b2d58976599cbdcf96fbd7b17ad9a50003429e192b1c7e18c0ea44`。
- 本機 CI oracle 不等於遠端 CI；`2823985` 的遠端 CI 要等本筆 push 之後才讀回。

**全新審查**：
- spec C0/I0/M2 ✅（`D1B30-SPEC-REVIEW.md`，SHA256 `67e96b84f69ab4f49b66cb60596fce17dc12029d755fd19710f82f19b299631e`）。
- quality C0/I0/M3 Approved（`D1B30-QUALITY-REVIEW.md`，SHA256 `5ad4746699fc28b0c64612f3347cad35884d2f8b8b3464990e7328a710edcee9`）。fail-closed 完整：payload 一律是 `encoded`，且必須等於重建 map 的 encode 結果，而重建 map 已經再驗過白名單。

五項 Minor 併入 3a：
- `stats()` 遇到空陣列會丟 AIOOBE（既有行為）；
- 類別 Javadoc 補上 SpawnEggItem 的委派；
- 容器 source 檢查的 Javadoc 改寫為實際擋下的範圍；
- SpawnEgg 前提 `assertSame` 恆真（requiredFeatures 是同一個 VANILLA 實例），改寫訊息並引用 javap；ISE 斷言改成檢查訊息；
- Javadoc 改以 spec 章節引用、不用行號；Fixtures 註明需要先 `createGameVersion`。

D1b-3-0 遠端 CI：docs commit `5a789fa77eeed05e63dd1b3744bed802a2d7bc63` 對應 run `40911589298`，修正行尾空白的 `0f1e4e055a95a06311a37772a35596b5eaeccef4` 對應 run `40911636980`，兩者都是 completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 也都 success。兩個 SHA 都包含 code `2823985`。這裡只讀回 job 的結論。

### 2026-10-30：D1b-3a（lease registries 加 identity，8 型 literal／空 DEFAULT）結案

**commit**：`c8c1e7e`（RED 測試）與 `36fdd36941c8ec36281cc53183c86d53248a68e9`（feat），共改六個檔：
- 新增 main owner `Minecraft121ItemComponentContracts`；
- LeafContracts；
- Closure（只改 Javadoc）；
- CanonicalizerTest、LeafContractsTest；
- test fixture。

**行為**：
- **換源**：ITEM component codec 的 ops 改為 `registries.getOps(NbtOps.INSTANCE)`。closure 建構時建立一次；RegistryOps 對 registry 的解析是 lazy 的，實際發生在 lease 內。
- **identity 證明**：`requireItemComponentRegistries()` 固定證明 ITEM、DATA_COMPONENT_TYPE、BLOCK、ATTRIBUTE、STATUS_EFFECT 這組聯集，lease 的 registry 必須與全域 static registry 是同一物件。證明在 `item()` 與 `itemCapture()` 都會執行，而且都早於 codec。
  - registry 缺席時的錯誤碼改為 `REQUIRED_REGISTRY_MISSING`；
  - 同 key 但不是同一物件，仍為 `STATIC_REGISTRY_OWNER_MISMATCH`。
- **seam**：static `itemPayload` 改為 instance `itemCapture`；ITEM leaf 的 Contract 維持 v1。
- **8 型值條件**，寫在新 owner：

  | 型別 | 值條件 |
  |---|---|
  | fire_resistant | `==net.minecraft.util.Unit.INSTANCE` |
  | enchantment_glint_override | Boolean |
  | ominous_bottle_amplifier | Integer |
  | potion_contents | exact class，且三欄皆空 |
  | suspicious_stew_effects | exact class，且為空 |
  | banner_patterns | exact class，且為空 |
  | charged_projectiles | exact class，且為空 |
  | container | `==ContainerComponent.DEFAULT` |

  - 經 `ComponentMap.CODEC` 或 `fromStacks` 產生的空 container 都是 DEFAULT。其他原生路徑可能產生非 DEFAULT 的空 container，這種情況只會被誤拒，仍是 fail closed。
  - roundtrip 之後，在重建的 map 上再驗一次值條件。
- **白名單一致性測試**改為「涵蓋」關係，並附上預期的分歧表 `[potion, chest]`。
- D1b-3-0 兩席審查提出的 5 項 Minor 已一併處理。

**覆蓋**：
- 1333 個 ITEM 中，ACCEPTED 從 1158 增為 1224，被拒從 175 降為 109。
- B28 可達的 85 個原被拒 item 中，新接受 16 個；剩下 69 個分別屬於 attribute_modifiers 32、tool 33、food 28、jukebox 8，交給 3b～3d。
- 既有 1158 件沒有退化。payload 只比較了**長度**相同，內容等值依據 Contract 維持 v1 的推理與具名 oracle 抽樣，沒有逐件比對內容 hash。
- 成本：closure 層級在換源前後量不到差異。`D1B3_ITEM_OPS_COST` 量到的只是兩種 lazy ops 的物件配置成本，不包括 wrapper 解析。

**測試數**：LeafTest 21→25，Canon 110→112。

**證據**（ignored，`task-08/8b2-c1d-d1b3a-evidence/`）：
- 具名 RED：RED-LEAF 25/4，RED-CANON 112/2。
- GREEN：25/0，112/0。
- 見證 W-ID-ALL、W-ID-SEAM、W-ID-K、W-VAL-POTION、W-VAL-CONTAINER、W-VAL-UNIT 都能鑑別。

**殘餘風險**：
- W-OPS（把 codec 改回 static ops）存活，這是預期中的等價突變：5 個 static registry 的 identity 都已證明，3a 收的型別也不經 ops 查 registry。3a～3c 都沒有行為層級的見證，靠逐行審查確認。**只有 3d 的 dynamic JUKEBOX_SONG 能殺掉它，所以「3d 重跑 W-OPS 並殺掉」列為 3d 的結案條件。**
- 只刪 `item()` 的步驟 1 時，DCT、BLOCK、ATTRIBUTE、STATUS_EFFECT 四個 key 是等價突變，因為 seam 會丟出同一個碼；只有 ITEM key 抓得到。

**乾淨 `36fdd36` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b3a-clean-canon-1-b0661554f5764eaa93623e1fc7a2e363`、`focused-c1d-d1b3a-clean-canon-2-7c048c84662b4181afac05e5b90c5307` 皆 112/112。
- LeafTest focused `focused-c1d-d1b3a-clean-leaf-1-13ce2aa833854998afc07268a058a2a0`、`focused-c1d-d1b3a-clean-leaf-2-eee388c303e04ec5867f76573236166a` 皆 25/25。
- 完整 `run-all-8aba2dc201f94d959e0e886b6be6ea4f` 自然 END ALL exit0：
  - JUnit899（0 fail／0 error／2 既有 skip）；
  - default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2；
  - release 505 entries／458 classes，mainOnly PASS，testmodHits0；
  - console.log SHA256 `4e3bb95e47989f8ef4974ec8c61843d6378d5db99f89d7244ccc9cb26825f934`，summary.json SHA256 `8cef36eb7fcb62385fdda3819e44b85e6fc4479a6e422350aeecd27397cb09d1`。
- 本機 CI oracle 不等於遠端 CI，`36fdd36` 的遠端 CI 要等本筆 push 之後才讀回。

**全新審查**：
- spec C0/I0/M4 ✅（`D1B3A-SPEC-REVIEW.md`，SHA256 `2190319a61c747deef9cc3c48efc0452a711b14402599428f5decda8599e76c3`），五項偏離都合規。
- quality C0/I0/M3 Approved（`D1B3A-QUALITY-REVIEW.md`，SHA256 `df2f54ac03030ddab4ae81b46a75cd350a441d6ce3d1e18e7cfeb184d0802a7c`）。

**交 3b 的事項**（3b 的硬性前置在最前面）：
- **硬性前置**：已審型別表要改成**單一分派的 per-type contract**，每型在同一處宣告值條件與 refs，literal 型別明確宣告 `NO_REFS`，並加上結構測試。這樣才能避免 ref 抽取另寫一條分派、與值條件表漂移，造成「型別被接受卻沒抽出 ref」的靜默少邊（fail-open）。
- 設計 §1.5 第 2、5～7 點。
- 設計審查 M2（Entry）、M3、M5、M9。
- 診斷的 node／edge 改為實際量測值。
- 改寫 ItemComponentContracts 的 Javadoc：tool 的 BLOCK 歸類，以及「固定聯集」的說法；同時寫明 ops 是 lazy 解析。
- `item()` 步驟 1 的殘餘風險要註記，或補 W-ID-ITEM 見證。
- `reviewedItemDefaults()` 的 putAll 在型別重疊時要明確處理。
- `StaticRegistry` 改用 `List<Registry<?>>`。
- withFood 子案翻轉的清單。

另有兩項交給其他切片：
- 3d：對齊 JUKEBOX_SONG 缺席時的定碼。
- D2：收斂 `nativeSource` 的互相依賴，並處理 container oracle 用 equals、production 用 identity 的差異。

D1b-3a 遠端 CI：docs commit `bb52b471e21f23323b8cc1b9afb84c6a650f62f7`（含 code `36fdd36`）的 run `40926054933` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩個 job 都是 success。這裡只讀回 job 結論。

### 2026-10-30：D1b-3b（attribute／food／直接 tool，ATTRIBUTE 與 STATUS_EFFECT leaf，ITEM 出邊）結案

**commit**：`8e6b9a5`（refactor：單一分派 contract）、`c4d8549`（RED 測試）、`ff3aecc21651145de1df3c410dc9ccdc1a34c83a`（feat）。共改六個檔：ItemComponentContracts、LeafContracts、Closure、CanonicalizerTest、LeafContractsTest、Fixtures。

**硬性前置（3a quality M3(a)）已完成**：
- 已審型別表改為單一分派 `IdentityHashMap<ComponentType,Contract>`，Contract 包含值條件、refs 與 registries；literal 與空值型別明確宣告 `NO_REFS`。
- 結構測試 `itemComponentContractTableIsSingleDispatchWithExplicitRefs` 守住這張表。
- 子片表改用 `merge()` 合併，重疊必須明確宣告。

**行為**：
- **接受的型別**：
  - 非空 attribute_modifiers：attribute 必須是 exact Reference；
  - food：`using_converts_to` 必須沒有 patch、count 為 1，並在 codec 之前先驗目標 item 的 source；
  - 只有 direct BLOCK rule 的 tool：tag rule 仍拒，屬 3c。
- **新 leaf**：ATTRIBUTE、STATUS_EFFECT。
  - 檢查順序：registry identity → instanceof 基底類別＋source → node 與 entry identity →（只有 STATUS_EFFECT）features → payload。
  - payload 只有 Contract；STATUS_EFFECT 另帶 RequiredFeatures。
- **ITEM leaf 開始產生出邊**：
  - 步驟 6 從原值抽 ref，步驟 7b 從重建值抽 ref，兩邊逐一比對：path 不同為 `NATIVE_CODEC_ROUNDTRIP_CHANGED`，entry 不是同一物件為 `REGISTRY_ENTRY_OWNER_MISMATCH`。
  - payload 寫入之後，才交給 ref sink；sink 沿用 `entry()` 的檢查，use 為 `item-default-component`。
  - 出邊範例：golden_apple→regeneration／absorption、beetroot_soup→bowl、mace／trident→attack_damage／attack_speed。
- **獨立 oracle**：`everyAcceptedVanillaItemLeafRefsMatchIndependentWalk`（設計審查 M9）。
- **一併處理**：設計審查的 M2（Entry）、M3、M5、M9，以及 3a 兩席審查列給 3b 的事項（Javadoc 更正、診斷改為實際量測、W-ID-ITEM 見證、`StaticRegistry` 改成 `List<Registry<?>>`、putAll 改為 merge、withFood 翻轉清單）。
- **與設計不同、經審查判定合規的地方**：
  - 新增 count==1 這個收緊；
  - inline 引用在值條件階段就被拒；
  - 設計 §3.3 說 wooden_shovel 與 iron_axe 會在 3b 翻轉，這是錯的：兩者都帶 tag rule，屬 3c；
  - M5 見證用 `Unsafe.allocateInstance` 配置未註冊的 foreign Item，不碰 registry，在完整 JUnit 中安全。

**controller 裁示**：
- **D1 hidden_effect**：javap 證實 `StatusEffectEntry.effect()` 回傳不帶 hiddenEffect 的新 instance，所以 hidden_effect 不可能被觀察、編碼或使用；設計的「否則拒」是恆真條件。改為斷言 hidden 被丟棄，不以反射讀原物件。spec review 已獨立核實這個前提。
- **D2 CodecCache**：FOOD、attribute_modifiers、tool 與 lore 的型別都帶 `.cache()`，而這個 cache 是**全域**的，以 value 的 equals／hashCode 比對。foreign 的子類或 List 實作若 equals 說謊，可能命中另一個值的快取編碼，使 payload 的 literal 與實際使用的值不同；重建再驗與 ref 比對都抓不到。這只有 mod 刻意構造才會發生，vanilla 不受影響。**修正排在 3c Step0**：在 codec 之前，用已驗過的元素重建三型 record，包括 food 的 effects 改用 `effect()` 的 copy、tool 的 Direct 用 `RegistryEntryList.of` 重建，LORE 也一併正規化。

**覆蓋**：
- 1333 個 ITEM 中，ACCEPTED 為 1267，被拒為 66。D1b-2 被拒的 175 件中累計接受 109 件；B28 的 85 件中累計接受 46 件，與設計預估相同。
- 沒有任何件退化；既有已接受件的 payload 長度不變。
- 剩下被拒的：tool 的 tag rule 31 件（屬 3c）、jukebox 19 件（屬 3d），其餘是設計列為不納入的型別。
- closure 量測：1267 件，nodes 1277，edges 1288，canonical 720,515 bytes。
- 冷尖峰在本輪量不到，因為同一個 JVM 中先跑的測試已經暖化了 codec。I-1 若要可靠的冷啟動數字，需要在全新 JVM 中專門量測。

**測試數**：LeafTest 從 25 增為 34，Canon 從 112 增為 115。

**證據**（ignored，`task-08/8b2-c1d-d1b3b-evidence/`）：
- 具名 RED：leaf 33/12，canon 115/3。
- GREEN：GREEN 前兩次 FAIL 的 root 都保留；最終 leaf 34/0、canon 115/0。
- 8 個見證全部被殺：W-REF-ID、W-SRC-ATTR、W-SRC-EFFECT、W-M5、W-STRUCT、W-M9-DROP、W-ID-ITEM、W-COUNT。

**乾淨 `ff3aecc` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b3b-clean-canon-1-e5a88c0e53b34519b577e523b7354940`、`focused-c1d-d1b3b-clean-canon-2-bf5e0b1edc124e3e9512973fb712edde` 皆 115/115。
- LeafTest focused `focused-c1d-d1b3b-clean-leaf-1-16eb450bc9524ba9b855a6dbe94866e6`、`focused-c1d-d1b3b-clean-leaf-2-7d5a8d3d62a64e509b87f0ed5c5556c3` 皆 34/34。
- **第一次完整回歸 FAIL，原 root 保留**：`run-all-afa9356c32f2436d8b78bf58e8fcb2c2`。
  - 現象：default GameTest 55 個 required test 失敗。
  - 根因：03:17:45，`run/gametest/world/data/quantumchamber_sessions.dat` 的原子移動發生 `AccessDeniedException`，是外部程序鎖住了檔案。之後 session 一直留在保留租約、佔住走廊容量，連帶造成後續的「AABB 容量不足」。
  - 這屬於使用者 2026-10-21 裁示的 M5 已知限制：維持 fail-closed 與退避，不加 production 重試。3b 改的只有 universe closure，沒有任何 GameTest 或 production 呼叫端，與這次失敗無關。
- **新 nonce 重跑**：`run-all-090bcb63a6e04cff9650b9aaba5281bd` 在同一個 HEAD 自然 END ALL exit0，前後的 Assert-Pin 都通過。
  - JUnit911（0 fail／0 error／2 個既有 skip）；
  - default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2；
  - release 510 entries／463 classes，mainOnly PASS，testmodHits0；
  - console.log SHA256 `129dca3579a0efa5cca7227d3d9122434211c725844869414ac18c9e3a8aacbb`，summary.json SHA256 `a2e0babd880865f3439ad67d57cc73bcfc3ee4519b4011f947bec3a393a8fe71`。
- 本機 CI oracle 不等於遠端 CI；`ff3aecc` 的遠端 CI 要等本筆 push 之後才讀回。

**全新審查**：
- spec C0/I0/M4 ✅（`D1B3B-SPEC-REVIEW.md`，SHA256 `29ae4676bafa85213e26c256378fa9c159b9ce2c8e4e563ec3bc4689c5ad4735`）。
- quality C0/I0/M4 Approved（`D1B3B-QUALITY-REVIEW.md`，SHA256 `4dd729cdce3db04109c85195d6e2037e38dac9a1be772f5fd318966f5b475736`）。新 leaf 的 payload 不含 attribute 預設值或 effect 顏色，這些只影響遊戲行為與 client 呈現，不影響生成或 loot，所以不是生成層級的缺口。

**交 3c Step0 的事項**：
- D2 的 copy 重建，涵蓋三型 List 容器，並補上「manager 不共用」的前提斷言。
- `requireSameReferences` 先比 `registryKey()`：key 不同為 ROUNDTRIP_CHANGED，同 key 但物件不同才是 OWNER_MISMATCH。
- M9 加一道與型別模型無關的交叉檢查：payload 中可以解析、且屬於固定聯集的 registry id，都必須出現在 ref id 裡。另外寫明新增帶引用的型別時，三處清單要同步。
- 降低擴充摩擦：
  - ref sink 改成 entry／tag 兩個方法的小介面；
  - 測試中的 cast 改成 sealed switch；
  - `Contract.registries` 只列 static，列入 3d 待辦；
  - 寫明 `contracts()` 的迭代次序不得用於 canonical。
- 補上同一個 ITEM 被兩條 occurrence 引用、只產生一次出邊的 Canon 測試。
- 用 Unsafe 技法為 S0-5 的 ITEM seam 補上行為見證。
- 更正設計文件中 wooden_shovel 與 iron_axe 的翻轉時點（屬 3c），並在 plan 第 241 行補上 ATTRIBUTE／STATUS_EFFECT 分支與 ref sink。

D1b-3b 遠端 CI：docs commit `6b5e495c9db241231f155b3b41eae313ac9d3122`（含 code `ff3aecc`）的 run `40948195881` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩 job 皆 success。只讀回 job 結論。

**設計勘誤**（ignored 的 `task-08/8b2-c1d-design/D1B3-PROPOSAL.md` 被 plan 以 SHA256 釘住，所以原檔不改，勘誤記在這裡）：
- §3.3 說 wooden_shovel 與 iron_axe 會在 3b 翻轉，這是錯的。兩者都帶 tool 的 tag rule，翻轉要等 3c。
- 設計審查 M4 說 fire_resistant 的值是「DFU Unit」，實際上是固定 JAR 內的 `net.minecraft.util.Unit`。

### 2026-10-30：D1b-3c-0（Step0：D1b-3b 殘餘收斂）結案

**commit**：`d904df2`（RED 測試）與 `be9d408986bb7a6e7acd17c5c74d6548dd6af660`（fix），只改 ItemComponentContracts、LeafContracts、LeafContractsTest、CanonicalizerTest 四個檔。本片不接受新型別，也不改 `expandTag`。

**行為**：
- **codec 前的 copy 重建**：Contract 新增 `copy` 欄位。attribute_modifiers、food、tool 三型以已驗元素加 `List.copyOf` 重建：tool 的 Direct 用 `RegistryEntryList.of`，food 的 effects 用 `effect()` 的 copy。LORE 正規化為 DEFAULT，並加 iterator 檢查。流程是：原值的值條件 → 第二次迭代建立 copy → 檢查 copy 的值條件 → 從 copy 抽 ref。
  - **具名 RED 證實了等值替換的風險**：修正前，foreign List 的 equals 說謊時，payload 會變成另一個已快取值的編碼。實測 attribute 預期 9.0 得到 3.0、tool 預期 4.0 得到 15.0、food 預期 999 得到 100。修正後不再發生。
- `requireSameReferences` 改為先比 `registryKey()`：key 不同為 `NATIVE_CODEC_ROUNDTRIP_CHANGED`，同 key 但不是同一物件才是 `REGISTRY_ENTRY_OWNER_MISMATCH`。未綁定 key 的 Reference 丟出的 ISE 也改碼，並以 initCause 保留原因。
- M9 新增與模型無關的交叉檢查：payload 中凡是可解析、且屬於固定聯集 registry 的 id，都必須出現在 ref id 中。Javadoc 也寫明：新增帶引用的型別時，三處清單要同步更新。
- ref sink 改成 `RefSink` 小介面，有 entry 與 tag 兩個方法。tag 目前一律拋 Failure，所以整件 item 會被明確拒絕，不會靜默遺漏。測試端的 cast 改為 sealed switch。
- 新增 Canon 測試：同一個 ITEM 被兩條 occurrence 引用時，出邊只產生一次。
- 以 `Unsafe.allocateInstance` 補上 ITEM seam 自驗的行為見證，關閉 D1b-3-0 S0-5 的殘餘風險。

**payload 不變**：1267 件 ACCEPTED 逐件比較內容 hash，重建前後 0 件改變，digest 為 `8bdc6c43e1280e98e2a335efb4a01cb1703cc9086a26edda442a12f643c9e396`。spec 審查以自寫 parser 獨立重算；另以 D1b-3b 的 XML 為基準比對，1333 件全部相同。

**測試數**：LeafTest 34→36，Canon 115→116。

**證據**（ignored，位於 `task-08/8b2-c1d-d1b3c0-evidence/`）：
- 具名 RED：leaf 36/3。
- GREEN：GREEN2 的內容與 commit 相同，leaf 36/0、canon 116/0。
- 5 個見證全部被殺：W-M9-CROSS、W-ITEM-SEAM、W-CANON-ONCE、W-LORE-ITER、W-COPY-FOOD。

**乾淨 `be9d408` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b3c0-clean-canon-1-3e1c55b83e7348c3826529cf1a0c1e04`、`focused-c1d-d1b3c0-clean-canon-2-15a4656db38849e9aebb6483409ea628` 皆 116/116。
- LeafTest focused `focused-c1d-d1b3c0-clean-leaf-1-c2c39a8d86be412297c56f07b8cd1303`、`focused-c1d-d1b3c0-clean-leaf-2-dbb934b3e3d84f34a54926481b5a0d5c` 皆 36/36。
- 完整 `run-all-a465f127b08a431db4f24eeda83ebf8c` 自然 END ALL exit0：JUnit914（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2，release 514 entries／467 classes，mainOnly PASS，testmodHits0。console.log SHA256 `d975e5b3be1481b1b40ba2442f7271d1595cf578902aa9792f45c55624d53c91`，summary.json SHA256 `9897259f97ab159cc6be713e685102b965cfab64ccbfe4f50568a674ff5906de`。
- 本機 CI oracle 不等於遠端 CI，`be9d408` 的遠端 CI 要等本筆 push 後才讀回。

**全新審查**：
- spec C0/I0/M3 ✅（`D1B3C0-SPEC-REVIEW.md`，SHA256 `98f2d3c9e5e22bb9948402d07b2220b844513bea431a5b6bd0bdb7959402a1b3`）。
- quality C0/I0/M4 Approved（`D1B3C0-QUALITY-REVIEW.md`，SHA256 `cfaeb7caeda5bdec7dba69429a1abf2a62fb759b6082d0dbcbe13822a96921fb`）。

**殘餘與下一片 D1b-3c-0b**：以下各項只有第三方 mod 刻意構造時才會觸發，vanilla 不受影響。controller 把兩席的 Minor 合併成獨立切片 D1b-3c-0b，排在 3c 本體之前：
- **CodecCache 的快取端**（quality Q-M1）：copy 只擋住「本值是 foreign」這個方向。JDK 的 `List.equals` 會呼叫**對方**的 iterator，而 ops 等值只看 manager 是否為同一物件。所以只要先前有人用同一個 manager encode 過帶說謊 List 的 record，誠實的 copy 也會命中快取，vanilla item 的 literal 欄位（例如 golden_apple 的 food duration）就可能被替換；邊不受影響。修法有兩種，擇一：讓 ops 的 identity 獨立，不與遊戲共用快取；或做一個與快取無關的事後比對。另外要補「快取端說謊」的 RED。
- tool rule 的 `speed`／`correct_for_drops` 是 Optional，內容沒有驗證 class（spec M1）。
- potion_contents、suspicious_stew_effects、banner_patterns 三型也走 CodecCache；DFU 的 `optionalFieldOf` 預設值比對同樣會呼叫 equals。三型要比照 LORE，正規化為 DEFAULT 並加 iterator 檢查（spec M2）。
- copy 的保真度要有全族群的 gating 測試：每件 ACCEPTED item 的 payload，都要等於用另一個新建 manager 的 static ops 對原值獨立編碼的結果；並為各重建型別加上帶非預設 literal 的 seam 子案（quality Q-M2）。
- M9 的「不空轉」前提改成逐件雙向集合相等，並補上 `#` tag 與 compound 鍵兩個盲點（quality Q-M3）。
- 結構測試補上 `refBearing ⊆ rebuilt`；`itemLeafRejectsLyingNestedListAfterRoundtrip` 改名；`foodCopy` 的 Javadoc 補充說明（quality Q-M4）。
- 交 3d 的待辦：`Contract.registries` 只列 static registry，3d 引入 dynamic 的 JUKEBOX_SONG 時要重新定義。M9 交叉檢查的 registry 集合也要隨 dynamic registry 擴充，否則 3d 漏抽 ref 時抓不到。

D1b-3c-0 遠端 CI：docs commit `1b7dbe5f6a63b0ef490d270943a2c3eea2bf230b`（含 code `be9d408`）的 run `40962495545` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩 job 都是 success。這裡只讀回 job 結論。

### 2026-10-30：D1b-3c-0b（硬化）結案

**commit**：`a5e4f97`（RED 測試）、`ae256143485479134d9a7192506f174c3e9f808b`（fix），共改四個檔：ItemComponentContracts、LeafContracts、Closure（只改 Javadoc）、LeafContractsTest。這一片不接受新型別，也不改 `expandTag`。

**行為**：
- **CodecCache 快取端（採 (B)）**：LeafContracts 改用 `RegistryOps.of(NbtOps.INSTANCE, new PrivateLookup(registries))`。`PrivateLookup` 委派了兩個抽象方法，三個預設方法都只走 `getOptionalWrapper`。它不覆寫 equals／hashCode，所以 ops 的 identity 是私有的，不會和遊戲或其他 closure 共用快取 key。registry 解析、owner 與 lazy 的時點都不變，spec 審查已用 javap 獨立核實。
  - 具名 RED 證實了修正前的問題：別人先存入說謊的值後，誠實 vanilla 值的 payload 會被替換成那個值，實測 duration 該是 100，得到 999。修正後，foreign 值不會被命中，也不會被迭代。
  - 帶快取的型別分成「重建」與「明確列為 AS_IS」兩組，由結構測試守住。enchantments、charged_projectiles、container 維持 AS_IS：前兩者的建構子不是 public，只收空值；container 只收 DEFAULT 單例。
- **tool rule 的 Optional**：`jdkLiteral`／`jdkLiteralCopy` 要求內容的 class 恰為 Float／Boolean，copy 時重建 Optional。
- **potion_contents、suspicious_stew_effects、banner_patterns**：比照 LORE，正規化為各自的 DEFAULT，並加 iterator 檢查。potion 另外檢查 `getEffects()`，因為 `customEffects()` 是 `Lists.transform` 的 view，走的迭代路徑不同。
- **copy 保真度的全族群 gating**：`everyAcceptedVanillaItemLeafPayloadEqualsIndependentEncodingOfOriginal`，以另一個新建 manager 的 static ops 對原值獨立編碼來比對；並加上非預設 literal 的 seam 子案。
- **M9**：改為逐件雙向集合相等，收集器補上 `#` tag，以及巢狀 compound 中含「:」的鍵。
- **結構測試**：補 `rebuilt ⊇ refBearing`；測試改名為 `itemLeafRejectsLyingNestedListBeforeCodec`。

**payload 不變**：1267 件 ACCEPTED 的逐件內容 hash 0 件改變，digest 仍為 `8bdc6c43e1280e98e2a335efb4a01cb1703cc9086a26edda442a12f643c9e396`。spec 審查以自寫 parser 獨立重算。

**測試數**：LeafTest 36→41，Canon 維持 116。

**證據**（ignored，`task-08/8b2-c1d-d1b3c0b-evidence/`）：
- 具名 RED：41/5。
- GREEN1：41/1，原 root 保留。這次失敗是 potion view 的自然見證。
- GREEN2、GREEN3（等於 commit）：41/0、116/0。
- 8 個見證全部被殺：W-CACHE-OPS、W-TOOL-OPT、W-EMPTY-ITER、W-COPY-EAT、W-COPY-TOOLTIP、W-COPY-MINING、W-M9-BIDIR、W-STRUCT-REF。

**乾淨 `ae25614` 驗證**：
- M6Compile PASS。
- Canon focused `focused-c1d-d1b3c0b-clean-canon-1-9fa3b179dc224b4bb25cddee35a6c65e`、`focused-c1d-d1b3c0b-clean-canon-2-d457770a4dc14a15a4171124157a4870` 皆 116/116。
- LeafTest focused `focused-c1d-d1b3c0b-clean-leaf-1-0caba695007e42f18e6e30dbca34b92d`、`focused-c1d-d1b3c0b-clean-leaf-2-5d960e568ad7413381c9c04de4c84a3b` 皆 41/41。
- 完整 `run-all-89f998ff5a984fe4ac109f88431f6da9` 自然 END ALL exit0：JUnit919（0 fail／0 error／2 既有 skip）、default179、M5 60、M6 8、recovery24、M5 collapse69、M6 native DIAGNOSTIC2、release 515 entries／468 classes、mainOnly PASS、testmodHits0。console.log SHA256 `bb89802c11d7bec524c5a00dec587de3475bc4dbe0e08ea31ee95bfec5818bfb`，summary.json SHA256 `7e3de3272711998fad19c6f5e85c9eea64f8a2364ab88c3cdc04db94720eb9e6`。
- 本機 CI oracle 不等於遠端 CI；`ae25614` 的遠端 CI 要等本筆 push 後才讀回。

**全新審查**：
- spec C0/I0/M2 ✅（`D1B3C0B-SPEC-REVIEW.md`，SHA256 `49a69fd49d83b67e3f0df2ecc4c429fb98eb6b214518292c587620b1c389c185`）。
- quality C0/I0/M4 Approved（`D1B3C0B-QUALITY-REVIEW.md`，SHA256 `e4a7a0e18e211c67a47212fcebd2b7291848dbe9c4ce4777c8261137c6b7d050`）。

**controller 範圍裁示**：凡是必須以反射、access widener、mixin 等**繞過存取限制**才能觸發的殘餘，只寫進 Javadoc 與 tracked 待辦，不再另開硬化切片。只有一般 mod 用 public API 就能觸發，或 vanilla 會受影響的問題，才當場修正。理由是前者已超出「正常遊戲加一般 mod」的威脅模型，繼續追會讓 C1d 的硬化沒有盡頭。依此裁示，以下只登記、不修：
- 繞過存取限制、放進 `ItemEnchantmentsComponent` map 子類時，可以等值命中空的 DEFAULT（spec M1）。
- 讀得到全域 CACHE 的程式碼，可以經由 `Key.ops()` 取得私有 ops（spec M2）。
- 第三方 mod 若以 mixin 改寫 `CodecCache$Key`、`RegistryOps`、`CachedRegistryInfoGetter` 的 equals，(B) 就會失效（quality R1）。

**交 D2b 的待辦**：TemplateValueContracts（`:123`、`:195-198`）仍用共用 manager 的 ops，對帶快取的型別做 encode，而且重建後沒有再套值條件，所以存在同類的快取端風險（spec O1）。

**I-1 輸入的註記**：改用私有 ops 後，逐件成本診斷的暖值不再包含 CodecCache 命中，3c-0b 前後的數字不能直接比較；closure 層級的診斷不受影響。另外，私有 ops 會在全域 512 筆的 CACHE 中多占數十到一兩百筆。被驅逐的一方只是重新 encode，沒有正確性風險。

**交 3c 本體的事項**（3c-0b quality 的 M1～M4）：
- potion 補一個「只有 `listIterator` 說謊」的子案；三型各加一個「正規化成 DEFAULT」的正向見證，要求 equals／hashCode 被呼叫 0 次。
- 更正類別 Javadoc、結構測試失敗訊息、`foodCopy` Javadoc 三處措辭。
- 結構測試加上「已審型別恰等於 rebuilt、literal、cachedAsIs 三組不相交的聯集」，並在 ops Javadoc 寫明「私有 ops 不得交給固定 JAR 以外的 codec」這個前提。
- 成本診斷的 Javadoc 註明冷暖語意已改變。

D1b-3c-0b 遠端 CI：docs commit `463a984b6f714da911466d039dff99defac97cb8`（含 code `ae25614`）的 run `40975183995` completed／success，Ubuntu `build` 與 Windows `windows-native` 兩 job 都是 success。只讀回 job 結論。

### 2026-10-30：D1b-3c 本體實作與本機結案

**commit**：`7749108`（RED 測試）、`fe76d49e3e4cd08e97e51f5e9e16d092177a2104`（feat），改動六個檔：ItemComponentContracts、LeafContracts、Closure、CanonicalizerTest、LeafContractsTest、Fixtures。本片的乾淨本機驗證及獨立雙席審查已完成；下列 implementer 原始紀錄仍保留作歷程。

implementer 的回報（後經下方雙席審查核對）：
- **tool 的 tag rule**：rule 可以是 Direct，也可以是原生 Named。Named 的 TagKey 必須是 exact TagKey，registry 必須是 BLOCK。ref 為 `Tag(...)`，Closure 以 required（`required-tag`）入列。
- **foreign Named**：平行比對原值與重建值。key 不同為 `NATIVE_CODEC_ROUNDTRIP_CHANGED`，同 key 但不是同一物件為 `STATIC_TAG_OWNER_MISMATCH`。
  - Named 的值條件改用 `instanceof Named` 加原生 source 檢查，不用 exact class。原因：`RegistryEntryList.of(owner, tag)` 回傳的是固定 JAR 內的匿名子類 `RegistryEntryList$1`；若用 exact class，負例會在值條件就被擋下，identity 比對也會變成等價突變。**這項偏離後經雙席審查接受。**
- **TAG 節點只展開一次**：以 `tagPresence` 記錄，第一條 occurrence 才展開成員邊。T2 視窗內的真 vanilla 成員實測：單次展開時成員邊 1,127、canonical 593,818 bytes；每條 occurrence 都展開時是 6,184 條、1,428,896 bytes。ITEM→TAG 邊 56 條，與 B28 census 一致。
- **tag 測試技法**：T1 用 seam 加 local registry；T2 是可還原的全域 populate 視窗，放在 Canon。T2 會檢查前提 P1～P3，並以反射唯讀 `Named.entries` 與 `Reference.tags`，確認狀態精確還原。T3 交給 8B3。
  - P3 用 thread 名稱判斷 worldgen worker，屬測試啟發式；本次完整 run-all 通過，但不能外推為真 server 隔離保證。
- **3c-0b quality 的 M1～M4**：已處理。
- **ITEM 覆蓋**（實測）：1333 件中 1298 件 ACCEPTED，35 件被拒。B28 可達件中，仍被拒的只剩 8 張唱片（屬 3d）。原本已接受的 1267 件，逐件 hash 0 件改變，digest 仍為 `8bdc6c43e1280e98e2a335efb4a01cb1703cc9086a26edda442a12f643c9e396`。
- **測試數**：LeafTest 41→43，Canon 116→121。
- **證據**（ignored，`task-08/8b2-c1d-d1b3c-evidence/`，總索引 `D1B3C-INDEX.json`）：
  - 具名 RED：leaf 43/7、canon 121/4。
  - GREEN1：leaf 43/1，原 root 保留。
  - GREEN2：leaf 43/0、canon 121/0。
  - 9 個見證全部被殺：W-NAMED-ID、W-TOOL-REG、W-M9-TAG、W-TAG-EACH、W-PRESENCE-EACH、W-T2-NOCLEAR、W-POTION-VIEW、W-EMPTY-DEFAULT、W-STRUCT-LITERAL。

**新增的 C1d 待辦**：
- 8B3 必須證明 context 是在 tag binding 之後才發布（設計 §4.2 H-TAG）。
- JUnit 中帶 tag 的真資料閉包，其成員只能當作空集合的下界，不能拿來證明成員閉合（設計審查 M7）。

**交接**：使用者 2026-10-30 指示，等這個子代理完成就收尾並交回 Codex。新入口是 [`docs/handoffs/2026-10-30-m6-codex-resume-prompt.md`](../handoffs/2026-10-30-m6-codex-resume-prompt.md)。Claude 這段工作的 scratchpad 已複製到 ignored 的 `handoff-claude-20261030/scratchpad/`，附 `SCRATCHPAD-MANIFEST-sha256.txt`。

**乾淨本機驗證與原件**：code pin `fe76d49e3e4cd08e97e51f5e9e16d092177a2104`；純 docs HEAD `a635b3abeb4879c0b1fcb5df08c1eadb5ae21b2d`，驗證前 tracked clean，code 無差異。M6Compile PASS；Canon focused 121/121×2、LeafTest 43/43×2。完整 `run-all-976de0f417a84aa3b5e1074fe4b99dca` 自然 `END ALL exit=0`：JUnit926（fail0／error0／既有 skip2）、default179、M5 60、M6 8、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnly PASS、release 516 entries／469 classes、sources 254 entries、兩 JAR testmodHits0。本機 CI oracle 不能替代遠端 CI；本 docs HEAD 的遠端 CI 尚未採計。完整 summary SHA256 `81935c0c8d4ffcb3a1c650bcf932e2e411d2c29bac7d8a3c1a6816695807792d`、console SHA256 `762a3f4baf9f081d6d8739690061bdb6750692b933d30d31495566120215286a`；ignored `D1B3C-CLEAN-RESULT.json` SHA256 `12c3dd702f90e398df6c0958ae3a091dd12c51931420fea5f12356c143fb72f9`、`D1B3C-INDEX.json` SHA256 `f3a8c48bac83de125820a44959543d92b18a0ec823e8b10a105c75cc4ac0d843`。

**獨立審查與處置**：spec C0/I0/M1（`D1B3C-SPEC-REVIEW.md`，SHA256 `279325d7f6466112123aa1785e008cbf8b28f7ecd00079329a9c89e39dca7b47`），quality C0/I0/M1（`D1B3C-QUALITY-REVIEW.md`，SHA256 `14923d16fd37732fa830b4ee031adf98b4cfdb99953b3f8213396b7d91713b19`）；兩席接受四項實作偏離：Named 用 `instanceof` 加原生 source、closure 不重做恆真 identity 比對、T2 唯讀反射、成本診斷 RM 用 vanilla 預設 pack。同一 Minor 是 T2 的 `clearTags()` 雖在 `finally`，後置還原斷言在外層，body 拋錯時會跳過；交 D1b-3d Step0，修正時須保留 primary／suppressed 例外。3c 只就本片本機與雙席 C/I0 結案；D1b-3d 先設計／審查，且必須殺 W-OPS。8B3 仍須證明 context 在 tag binding 後發布；JUnit tag 真資料的成員只是空集合下界。Task8、C1d、M6 均未完成；3b AccessDenied 原 FAIL、v4 父 FAIL、B27 FAIL 均不改判。

**使用者範圍裁示更新**：使用者本輪同意「只有 mod 須繞過存取限制的殘餘記錄、先推主線」。此裁示只及於須以反射、access widener、mixin 等繞過存取限制才能觸發的殘餘；一般 mod 透過 public API 可觸發或 vanilla 會受影響者仍當場修正。

### 2026-10-30：D1b-3d（dynamic jukebox 與 SOUND_EVENT leaf）本機結案

**範圍與 code pin**：`857cd4882da50e370a1550647ae38486f3572818..ccccb9697e005ad9eece0f88545df949c5ca3c45`，四筆 commit（`b61c414`、`1e2c96f`、`f663924`、`ccccb969`），恰為六個 owner：`Minecraft121ItemComponentContracts`、`Minecraft121RegistryClosure`、`Minecraft121RegistryLeafContracts`、`Minecraft121RegistryLeafContractsTest`、`Minecraft121RegistryLeafTestFixtures`、`Minecraft121SettingsCanonicalizerTest`。設計 proposal／addendum 已獨立審查放行；本節只描述 D1b-3d 的局部閉包。

**行為**：JUKEBOX_PLAYABLE 的真 vanilla key-only default 對 lease dynamic JUKEBOX_SONG registry 做 lookup；bound Reference／registry pair／owner 與 key 精確驗證，必要時才要求 dynamic registry。JUKEBOX_SONG 定義限制四個原生欄位、有限且正的長度、比較器範圍與受控的 translatable Text；ITEM→JUKEBOX_SONG→SOUND_EVENT required edge 接入 closure。SOUND_EVENT leaf 保持 exact class／source／identity 與有限 literal 的 codec roundtrip。`Contract.registries` 與 M9 的候選聯集已納入 dynamic JUKEBOX_SONG，static identity 聯集納入 SOUND_EVENT。3c 留下的 T2 `finally` 之外還原斷言已移入 `finally` 的 restoration callback，body 失敗時仍驗證還原，保留 primary／suppressed 例外。

**RED／GREEN 與見證**：具名 RED、GREEN 與 mutation-only diff／revert 均保留在 ignored `task-08/8b2-c1d-d1b3d-evidence/`。七個 mutation 乾淨被殺。W-OPS 只把私有 ops lookup 改回 static manager；真 vanilla `music_disc_13` 的 key-only default 因缺 dynamic registry 而使具名測試失敗，revert 後第二輪 LeafTest GREEN。T2 突變也殺到兩個具名目標，但原 XML 同輪另有 `nonEmptyPoolSingleCodecLocationCreatesTemplateEdge(Path)` 因 100ms `TEMPLATE_SNAPSHOT_NOT_READY` 失敗；不能稱八個乾淨見證。原 root、`T2-EXTRA-FAIL.md` 與 `ERRATUM.md` 都保留，沒有在原 root 重跑或改判；同 pin 的還原 GREEN 不關閉 100ms live hold I-1。

**payload 與成本**：D1b-3c 已接受的 1298 件，逐件內容 hash 0 件改變；本片後 ACCEPTED 1317 件、REJECTED 16 件。兩種 COST_ENTRY 前綴均為 0。這是本片取樣與 payload 比對，不是整體 C1d／Task8 放行。

**乾淨本機驗證**：code pin `ccccb9697e005ad9eece0f88545df949c5ca3c45`。M6Compile PASS；Canon focused 123/123 兩次、LeafTest 51/51 兩次。完整 `run-all-d9c930ca1b374edda994caf753abe746` 自然 `END ALL exit=0 HEAD=ccccb9697e005ad9eece0f88545df949c5ca3c45`：JUnit936（fail0／error0／既有 skip2）、default179、legacy1、M5 60、M6 8、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnly PASS；release 517 entries／470 classes、sources 254 entries、兩 JAR testmodHits0。`summary.json` SHA256 `615111b53c765cb5e5ee3ae55fc1443978ee2f0e624b02972077522efeae887f`；`console.log` SHA256 `e6203e0f4db52b6b79ecb72f3f0490fa1777b2e9f64646fd6b7764c78d2a6c59`。ignored `FINAL-RESULT.json` SHA256 `14f26d63a424061c96c66ceb7275efb759c8550148dc26211eed120b3d0eecf0`；`ERRATUM.md` SHA256 `764b1c378274d990f16bd60c93ef5997723d01689d25bcdea9e7b29ed7026d37`。

**獨立審查與限制**：spec C0/I0/M1（`D1B3D-SPEC-REVIEW.md`，SHA256 `8b666f2e26b304d7da8b9ac2932e46e3df169b531208662fbfb889f30506a47e`）；quality C0/I0/M1（`D1B3D-QUALITY-REVIEW.md`，SHA256 `73bdc8902bea3c316e5f66e5f12354c785df530c74cb8ab6ff645aeea65da7b3`）。兩席 Minor 都限制 T2 的額外 100ms FAIL：spec 指出不能稱八個乾淨見證；quality 指出同 root 受 100ms 問題干擾。舊 `VALIDATION-PROGRESS.json` 的 T2 `KILLED` 只表示具名目標失敗，若單獨引用會誤導；`MUTATION-AUDIT.json` 與 `ERRATUM.md` 已列為 `TARGET_KILLED_WITH_UNEXPECTED_BUDGET_FAILURE`。遠端 CI 尚未在此 code／docs HEAD 採計。上一個 D1b-3c docs HEAD `857cd4882da50e370a1550647ae38486f3572818` 的 GitHub run `40996306990` 雙 job success，只讀 job／steps，不是 8B3 三 bundle。

**下一步**：D1c-0 僅完成只讀取證設計獨立審查 C0/I0/M2；D1c-1 尚未放行。C1d 其餘切片、100ms live hold I-1、8B3 真 lifecycle／三 bundle、Task8 終局、Task9–22、人工 smoke 與 M6 整體門檻仍待。v4 父、B27、3b AccessDenied 與本片 T2 額外 100ms 的 FAIL 均保留原判。只有須繞過存取限制才能觸發的 mod 殘餘可文件化先推主線；一般 mod 透過 public API 可觸發或 vanilla 受影響者仍須修正。

### 2026-10-30：8B3-LT 本機觀測片結案（僅 testmod）

- Code range 3d9248a28fc52694ef43bd11d7f739fec46ea11d..0b8f86b90e932810b7b0a4a79a9a82cfdc6bd8f0：候選 fe1e3c3 加 fix1 0b8f86b，恰 11 個 testmod 檔；ignored launcher／preflight／oracle 不在 Git。3d docs HEAD 3d9248a28fc52694ef43bd11d7f739fec46ea11d 的 GitHub run 41011354606 雙 job success，非本片或 8B3 三 bundle 的 CI。
- 同一乾淨 code pin 兩次新 nonce focused GREEN：4d1a132a9f31af3f4b65cc30f5f0758b、5e2c9cbccee5876bd6fc0e00836566b8。postflight SHA256 9315559061e1ee3d17254fd506c8b29ac859df8dfa972465cbf51143528fcca5／6725ffdc8a6d4c15962a0007d3dc305c3bc8d323150b3108c1175f25cbfecbd0；strict oracle stdout SHA256 均為 32d5aca3896d148f694a8820da39db6fc3dd7db65525db0f1577e9e31645834f；外部 owner／lock 收據 SHA256 feeeea6be829e8d189338cb7e50d4cb031f6d2c9343586a8064e26f05413a130／df4b2d01818a2d0f505a26dafc1fbafb5ca74ea041441748cfef8f55e9e204fd。各 71 事件、29 identity；受控失敗觀測為 RETAINED，不外推所有 failure。postflight 原件的 cleanupConfirmed=false／newLaunchAllowed=false 未改寫；獨立外部收據才證 owner／lock 收斂。
- 完整 run-all-ecd3be3ca6ca4bd191a98ccb1f4506ce 自然 END ALL exit=0，HEAD=0b8f86b90e932810b7b0a4a79a9a82cfdc6bd8f0；summary SHA256 a26b4bef04489c9abc6309cf716e5e21340a1db18f0d73b306fd6c5d83dc8a2c、console SHA256 e7495589a1d3a2a963d30800b5c971dc60af5815dc4167ec8388aed26e26a5ef。JUnit936（fail0／error0／既有 skip2）、default179、M5 60、M6 8、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnly PASS；release／sources testmodHits0，實際 JAR hash 與 summary 相符。full 後無 live Java，gametest session.lock 不存在。
- 獨立 task spec C0/I0/M1（報告 SHA256 29d8058a3865edd5ab71887ec19863a2533c441f5ffd385647206af6c9ca3d00）、quality C0/I0/M1（報告 SHA256 a3d11dc322712190a9e262e61f082a66150deafd920b75aab5d973f1cc0110a8），僅允本機觀測片結案。共同 Minor：Fabric START／END 診斷事件未記當刻 active RM；下一個 8B3 lifecycle 設計 Step0 須裁定刪除冗餘診斷欄，或補 exact active RM。不能從 Fabric callback RM 推定 active RM，更不能推 token／發布權威。
- prepare 6f3c2cb55001eb32807f2b36b1e898e1 FAIL，native faebfc97c1fe62f4da2d467bd36f4ee2、7537ecf0b6caefd00c00705712c4b859、18660a8c33433a7486cf85e4ee4b1bcd FAIL 均保留原 root／原判；v4 父、B27、3b AccessDenied、T2 額外 100ms FAIL 亦不改判。killCalls0 僅指外層 launcher；preflight Python spawnSync timeout30s 可能終止 Python。
- D1c-0a–0d raw 證據與 8B3-L 順序設計仍 STOP。D1c-1、production publisher／lease／source GREEN、100ms live hold I-1、off-thread／重疊 reload／stop交錯、8B3-F 三 bundle、Task8 終局、Task9–22、人工 smoke 與 M6 均未放行。createSnapshot/resolve 仍 fail closed。

### 2026-10-31：8B3-L STOP fence 第一片本機結案（僅 Contexts 本地演算法）

- **前因與遠端基線**：8B3-LT docs HEAD `3d4a792ee4d22d56a5266a940d3644e2264eace1` 已 push／ls-remote 讀回；GitHub Build run `41053474913` 同 SHA completed/success（attempt 1），Ubuntu `build` 與 Windows `windows-native` 兩 job success。Windows 的 M6、Main-only、精確集合、原生案例與上傳步驟 success；三份 artifact 清單已讀、未解壓。這是 LT 基線的 CI，**不是** 8B3 三 bundle，也不是下述新 code 的遠端 CI。LT2 code 候選 `22bde925` 與 Observer Gson fix `cd691f5` 之後，OFFTHREAD／AB／BA／PARTIAL 各一個不同 nonce 的 native postflight、strict oracle 及外部清理 PASS；沒有第二輪。
- **原 FAIL 不改判**：LT2 STOP-PENDING nonce `6bc60fc91584008b983aa668ebad1f00` 在 STOPPING 之後觀測到 worker off-thread 安裝 holder／refresh／templates 並使 F success；oracle `RUN_FAIL`，外部 owner0／lock 已釋放。原 root 永留 FAIL；真 late 已觀察，故不再稱 `STOP_UNPROVEN`，但安全性未證。獨立分析 `STOP-PENDING-ANALYSIS.md` SHA256 `660b639daf3d5c5e5d160d32498bbc460bb78d5ec29bc09158fb25431d55a0f8`。LT2 整片仍未結案，第二輪、完整回歸與 CI 未跑。
- **設計、code 與 RED/GREEN**：stop fence proposal SHA256 `b6e2aa9fef939e57fea37cf79bce40f01155316c893c72969973395ed1814314`、addendum v1 SHA256 `200cd51bef53f12509f226b6a22615eba36a5f992630620a8677526eecf2b744`、設計 review SHA256 `66bdb70d6d6435ff9fb276a5b37fe41bdf6419678494280b4570bff2a73aeba4`。Code range `cd691f5a8d81920e22bbcf89fffc05944a909ec4..402850b93f6b9c0dfd81b7620e65e3c44383720f` 恰改 `Minecraft121SettingsContexts.java`、`Minecraft121SettingsContextsTest.java`。真正 RED 為 JUnit 8/2（foreign START／STOP 舊 lease 仍可用）；最終 GREEN 21/0，七個 production mutation 被殺，首次 tombstone 提早還原不採計。ignored `IMPLEMENTER-RESULT.json` SHA256 `45ef0e5b215fa96e58a2ba062bc8f960b98b3abb1caab458bbbed04b178cd749`。Fence 在 Contexts 本地拒絕過期／foreign 接受與發布；vanilla late apply 本身不受此片阻止。
- **乾淨本機驗證**：M6Compile `gate-stop-fence-clean-compile-caf9b92b4471440d9d2712e1ef2d68a4` PASS；兩個獨立 focused 根 `focused-stop-fence-clean-1-5901c0d01f9c4334a6f7a0d5a7a101bc`／`gate-stop-fence-clean-1-ab3a87970ea8496fb8d1e5274dcf3d07` 與 `focused-stop-fence-clean-2-57a88937888942aa9198e3025e6c1902`／`gate-stop-fence-clean-2-6332d40e817448b3988e9a5bbfef375a`，各 M6JUnit PASS、精確 21 case。完整 `run-all-03daf56742b14a0fa7acad45e5ec8ba1` 自然 `END ALL exit=0`，summary SHA256 `bef8ca83b993666b8dc8a119044a54cb1414089876b066e3520dcddb0c85a5f9`、console SHA256 `5226894b425466c30e9b3e98c6a04c70b6fc7ed77805f0d8e4ba25a59b82846f`；JUnit 951（fail0／error0／既有 skip2）、default179／M5 60／M6 8／recovery24／collapse69／M6 native DIAGNOSTIC2／mainOnly PASS。release JAR 實際 SHA256 `1796dd02587d7bdb1d68da4af8a11cfe7c23982b4bf089b8ff4e3efad0e76202`、sources JAR `495f71640737355ddc44815e9efc1ba7a2a6d3bea9e62b3b92ef3f582df048c2`，testmodHits 均 0；full 後無 live Java、gametest lock 不存在、驗證前後 Git clean。
- **獨立結案審查**：final spec C0/I0/M0，報告 SHA256 `947bc83bc74c85a7193c107ebe1e820a5c36db91d888f11c4313dfb6ebb9efa7`；quality C0/I0/M1，報告 SHA256 `46b28beddc06953e7a5d6f01a1fa0644c11b43afd812e7f3478a8c48159f2b59`。共同界線只允本片本地 Contexts 演算法結案，不能推論正式 lifecycle／publisher／source GREEN。quality Minor：測試 worker 非 daemon，逾時時可能存活，交下一 Step0。
- **後續 STOP**：正式 consumer raw refs／census 與 source checkpoint、foreign shutdown owner cleanup、100ms live hold／8B3-F 的 Windows named／Ubuntu named／Windows intermediary 三 bundle、Task8 終局及 Task9–22 均待；`createSnapshot`／`resolve` 繼續 fail closed。v4 父、B27、3b AccessDenied、T2 額外 100ms 與 LT2 STOP-PENDING 原 FAIL 全部保留。新 code HEAD `402850b` 尚未 push、沒有該 HEAD 遠端 CI。本筆 docs 變更待提交；新 code 遠端 CI 待實際 push 後讀回。

### 2026-10-31：8B3-L owner Cell 能力片本機結案

- **前次遠端基線與範圍**：前次 docs `a339a76e03cb3b8c7c3dd44d061c64d06158a9e7` 已 push／遠端讀回；GitHub Build run `41089088851` 同 SHA completed/success，Ubuntu `build` 與 Windows `windows-native` 雙 job success，只採 stop fence code `402850b`，不是本片 code CI 或 8B3-F 三 bundle。本片 range `a339a76e03cb3b8c7c3dd44d061c64d06158a9e7..d88bed9060f55d8f7651b1c1fed98d161a3861b6` 恰改 `Minecraft121SettingsContexts.java` 與 `Minecraft121SettingsContextsTest.java`；正式 lifecycle、source 與 provider 未接。
- **設計與取證**：Step1a 引用圖／固定 Minecraft 位元碼取證 SHA256 `9bdcd9f5428c3d955f012ae1ac28ab830d2c97b742f96822ced921473e1ada05`，獨審 `dcd33789d70ce73ef480d70c95bde99382b4f557ed11b8ae602c0bc16f1c43c` 為 C0/I0/M0。owner Cell 提案 SHA256 `b72f10814e96b5d23bd062bf41b2b5a7de692ce458aa20840e6442409d82105d`、設計獨審 `1029f1b35e8eb783ef155f80488fdb3a57222cc389729de697e6004958bcf027` 為 C0/I0/M2，只放行本片。Facade／Lease 持 Cell，callback 強持 Fence 加弱 Cell；foreign STOP 在同鎖 tombstone 後清 Cell.payload，不讀 native、不等 owner 事件迴圈，也不宣稱 Java monitor wait-free。外部已借 raw Sources、活動 stack、Closure／CapturePermit／CodecCache 仍待 consumer 片。
- **RED／GREEN／補證**：具名 retained-lease RED 為 JUnit22案1失敗，實作 GREEN24/24；初輪 M1 去 payload detach、M2 Lease 強持 Thread 均被具名測試殺，惟其逐輪 REVERT 時點收據未單獨保存，不事後補造。初次 spec review SHA256 `1f7ae968a6e162bbde00978ddd0886def43c157037003e58b3a2bb9d3ed06460` 為 C0/I1/M1，I-1 要求補 M3／M5／M6兩處／M7／M8。FIX1 六個新 root 各 targeted 1案1 failure／0 error，M5 是撤銷後錯誤返回舊 Sources 的斷言失敗、非 NPE；每輪 mutation-only diff、REVERT 同 source SHA／HEAD／空 Git diff/status，M4 在本片無 map 且局部效果由 M1 涵蓋。還原後 focused24/24。`FIX1-result.md` SHA256 `3328ccf66138cd6a1da36e508c1181317a910233aee561c417e47028abbcd2ef`，`FIX1-index.json` SHA256 `391d07a1c67a200287163a63c1d7488733f2732f4131447adb9b5272d114d71a`，failure audit SHA256 `75396c645a157260744ac254c5342d4c4d55cf50685847ad608296d0de3b0663`。最終 source/test 與 `d88bed9` 相同，沒有新 commit；舊 FAIL root 不重跑改判。
- **乾淨本機驗證**：code pin `d88bed9060f55d8f7651b1c1fed98d161a3861b6` 的 M6Compile PASS；兩次新 focused 根 `focused-owner-cell-clean-1-9a0ff19e-6294114a50c44cf8b49382ff07f9519c`、`focused-owner-cell-clean-2-06a1eb79-2563d36a83f946548c29a554987c878c` 各 ContextsTest 精確24/24 PASS。完整 `run-all-22b3e881d7234ad38da265a9b1b1e772` 自然 `END ALL exit=0`：JUnit954（fail0／error0／既有 skip2）、default179、M5 60、M6 8、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnly PASS；`summary.json` SHA256 `3ae27df947600860673bca3dbbe0172bfcb53bb240d17600ee38e8844ee1be01`、`console.log` SHA256 `94d5237fe8d84b006431ae9acafebe751a6189100946dc3b0ee6d956ab9d627f`。release JAR 實體／summary SHA256 `cfe7be31fee197966d9e264fe18fca450c4a2c641f8afc5c63d9f5e7912239fd`，sources JAR `ace78940e60a1c970700ba38c9aa698552b5372c0fe3ffc6cbfc975ab89a699c`，兩者 testmodHits0；full 後、此輪文件編輯前 Git clean，且無此工作樹 Java owner、gametest session.lock 不存在。
- **獨立審查與後續**：FIX1 spec 窄複審 SHA256 `5ab711bc90a416ef2f1c09f40728a86edc3f0ce34adb05fa38a907eefac6ee58` 關 I-1，final C0/I0/M1；quality SHA256 `451be1ed208d1bf84380756dbbea92350f6d4b627ee7cc85b6601d44934b2326` 為 C0/I0/M1，兩席同一 Minor 是舊 `contextDoesNotCrossServerOrThread` worker 非 daemon，交下一 Step0。initial source 提案首審 C0/I2/M2，metadata-only addendum SHA256 `e594ecfe35bcf928020772b818e8bff89465f9708da71550355be60ff9015e6c` 窄複審 SHA256 `a150dab13ff52e2261eab20a6929f9f18f8021fdf792dabf598824d2ade2af63` 為 C0/I0/M1，只放行下一個 ticket／permit metadata 能力片。Fabric fixed mapping 補證與獨審各 SHA256 `56d3480de5bcfa969463ce96da4d9d5e4a416a4fb87966ef792a2150929dfa05`／`3e23add3318389cccb0a8386abb43cbd6d0ba2c6da9ad60d4e262bbe893cc499`，只證 mapping，binary invoke／transform／integrated refresh 仍 UNKNOWN。正式 lifecycle／source GREEN／consumer全圖、D1c LOOT_TABLE、100ms live hold、8B3-F 三 bundle、Task8／Task9–22 均未完成；`createSnapshot`／`resolve` 仍 fail closed。LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 真 late 原 FAIL，v4 父、B27、3b AccessDenied、T2 額外100ms 原 FAIL 全保留。截至本筆紀錄撰寫時，此新 code 尚未 push／無遠端 CI。

### 2026-10-31：8B3-L initial ticket／permit metadata-only 本機結案

- **遠端基線與範圍**：前次 docs `8c48482876bcd5b6c645b5a91b43656a1a2c4781` 已 push／ls-remote 讀回；GitHub Build run `41109557592` 同 SHA completed/success attempt1，Ubuntu `build`、Windows `windows-native` 雙 job success；Windows log 有 M6_SUMMARY、M6_MAIN_RUN／VERIFY PASS，三份 artifact 清單已讀未解壓。這只採 owner Cell code `d88bed9`，不是本片 code CI 或 8B3-F 三 bundle。本片總 range `8c48482876bcd5b6c645b5a91b43656a1a2c4781..0c757c60cc43ff69ba8a6a9302bf2f9a09907355` 恰兩個 Contexts 檔，中間候選 `a37568ce25fe65c7f99c093b5b26cebe909afc7d`、I1 fix1 `0c757c60`。
- **設計、RED／GREEN**：initial source 原提案首審 C0/I2/M2，metadata-only addendum SHA256 `e594ecfe35bcf928020772b818e8bff89465f9708da71550355be60ff9015e6c` 窄複審 C0/I0/M1；新 pin rebind SHA256 `7fd603be6a3539e15da4f8d66df55390c962be85165e2a2584dea777ff706c16` 的 M3 等價問題經 addendum SHA256 `2a6f9232594dcf8c99cd093ba5ffe39c292028def29ffa9e64a650d4db1a6a18`、窄複審 SHA256 `c6d2a73dbc601917bd23609555ef53413b8954a993abb9dd25fc9efd9825f175` 關閉至 C0/I0/M0。Step0 舊 worker daemon assertion RED1→GREEN；metadata 先有缺 API compile RED18，再有可編譯行為 RED1（出票後直 publish 未拒）→候選 focused38/38。ISSUED／CAPTURING／COMMITTED／ABORTED 同 Fence monitor，`InitialCapture` 只持 Cell／ticket／metadata；M1/M2/M3/M4/M6 各具名 mutation 被殺並還原。M5 單刪 final requireLive 仍 PASS，被 Cell.payload null 擋住，等價不採計；不為殺突變放寬程式。候選 `RESULT.md` SHA256 `2625653ff2720c73e1ff3ef19abb1c225b746af0bf54578a12a1186b3a246d93`。
- **I1 fix 與修後驗證**：候選 spec 初審 SHA256 `29ed57b3eb12163b33d9e93e931789ff46741be358f2b7adb0fd95adee0c5552` 為 C0/I1/M0：Fence 強持 InitialCapture→Cell，令 callback 強達 owner。原候選 CAPTURING／COMMITTED+close／ABORTED+close 的 callback 強圖 RED3/3；fix1 改 Fence 只持純 Object token，facade 持同 token＋Cell，同鎖 identity 守門，恢復強回邊 mutation RED3/3，REVERT byte hash 相同。fix1 `RESULT.md` SHA256 `6108c579eb65299771d0291f93dff4c5ec8802c2fe0e82b33ac88bf3926818aa`；其 precommit mutation REVERT 時 Git 仍有本片兩檔 M，只能採 byteEqual＋最終 clean commit，不能追認逐輪 Git 空。原候選完整 `run-all-fad3c6453eb345eb9964a7e566151a3c` PASS 僅屬修前 pin，不採為 fix1 full。修後乾淨 M6Compile PASS，兩新 focused 根 `focused-initial-metadata-fix1-clean-1-34be07d2-f155bc83287d4f44920071df86178024`、`focused-initial-metadata-fix1-clean-2-0a7d95cb-db0d5d5dca5e4306a5ddb794557fbbab` 各 ContextsTest 41/41 PASS。完整 `run-all-5e4546b2b92d4ad0aa6ed5562738f8dd` 自然 `END ALL exit=0`：JUnit971（fail0／error0／既有 skip2）、default179、M5 60、M6 8、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnly PASS；summary SHA256 `67792bf065c40bcf35d3cc96f0b97a6c56f0cf2910539797a4dc3c6b46762bfc`、console SHA256 `51ce9997508ee3fd1ba2080d2efb1e4077c7355e1410d10d360bad7e2cbce40e`。release JAR實體／summary SHA256 `9eb85d11cb87ecba0613232b2ac73a248fb6d41dab3e66bb4b364e438a5feefa`、sources JAR `538403978b06857ffe4afbaab9db3801ac301491231a81d7394d97f7dca44259`，兩者 testmodHits0；full後、此輪文件編輯前 Git clean、無工作樹 Java owner、gametest lock 不存在。fix1 spec 窄複審 SHA256 `665aff35d695663e20b22f3321b177f2ba448153725ffed2ec6418d4bf80b2ea`、quality SHA256 `4f7e4faab439f1bc80296acc69d0268c1829458c1ea75101be6c0314944dd922` 各 C0/I0/M0，僅 metadata-only 能力片本機結案。
- **來源靜態補證與後續 STOP**：Fabric TestServer 固定 binary trace／獨審 SHA256 `d7617d8e24abdaf281a02ce178cf2c97a7a09d734480efb0cc305de821997747`／`d93a0a48549e6582289e2f1f5c6111c86751a715884c7163eac2cd80c9717340` 為 C0/I0/M0，靜態接到 SaveLoading.refresh；integrated 三條 vanilla UI content refresh 路徑 trace／獨審 SHA256 `ffb0c8cf9462aa5670fef83df932794401b7724a10e9c4754768c9dd682f57b6`／`27872e0355ec1e40f113a31845aa518754be74062cabf8e0eefe372a306c9ce8` 為 C0/I0/M0。但 integrated 新建 active RM 未證與先前 content 載入 RM 同源；Fabric transform 命中、真 journal 首用、effective Lookup／tag／loot、refresh seal、source bridge、consumer全圖、D1c LOOT_TABLE、100ms live hold、8B3-F 三 bundle、Task8／Task9–22 均未完成。`createSnapshot`／`resolve` 維持 fail closed；LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 原 root 永 FAIL，v4 父、B27、3b AccessDenied、T2 額外100ms 原 FAIL 全保留。截至本筆紀錄撰寫時，新 code 尚未 push／無其遠端 CI。

### 2026-10-31：8B3-L S1a 自然 TestServer startup refresh seal 本機結案

- **前次遠端基線與設計邊界**：metadata-only docs `350aebce57c3a4242a8cbfbd535ad350a18e5862` 已正常 push／ls-remote 讀回；GitHub Build run `41129836626` 同 SHA completed/success attempt1，Ubuntu `build`、Windows `windows-native` 雙 job success，Windows log 的 M6／Main-only／upload-artifact 步驟成功，三份 artifact 清單已讀未解壓。此 CI 只採 metadata-only code `0c757c60`，不是下述 S1a code 或 8B3-F 三 bundle。人工第二次 refresh 的原 S1 設計因公開可變 fuel map 的一般 mod 寫入會被 `clearFuelTimes()` 丟失而 STOP，cache 獨審 SHA256 `1505561934968f5e43c234815479e958bb962d7b536706b951d9e9b5f973ad2a`；改採只觀測自然 TestServer 啟動 refresh 的 S1a 提案 SHA256 `4b1b8411fb4690a10d57d4142ac011ae09e80a753ef4e6096e220b94a441161b`，獨立設計審 SHA256 `8add2df1f355d05478e3434a491a795dc6fee6c85afca696e72b806dfa378205` 為 C0/I0/M2，僅放行此片十四檔。
- **code、RED／突變與原 FAIL**：range `350aebce57c3a4242a8cbfbd535ad350a18e5862..96cc6c3ba2cac4d8806684e1443de0e9335d9590` 恰十四白名單檔；production 只加 DataPackContents instance HEAD 清舊／正常 RETURN 設弱 Lookup／manager tuple 的瞬時診斷，不發 Sources、lease 或正式 publisher。真 TestServer 首次自然 startup 的可編譯 false-stub 行為 RED：BEFORE_WORK=false，AFTER 必須 true 的唯一 XML failure，STOPPED／exactRestored 已觀測；該 root 啟動前缺獨立 remap／release 隔離，只採行為 RED，不追認正式 native gate。兩個 `PATHEXT`／`ComSpec` launcher setup FAIL 與其它 setup root 保留，不算 code RED；九個同名空目錄 birthtime 早於本輪，未搬／刪。M-A1（HEAD 提早設tuple並移除 inflight 遮蔽）、M-A2、M-A3 各被具名殺，tag／loot stone→dirt 各在 actual marker/value 斷言失敗，後兩者僅 oracle sensitivity；五輪各新 root、mutation-only diff、原 FAIL、REVERT byteEqual，不把等價的單獨 HEAD 寫入算殺。`RESULT.json` SHA256 `32b1d38847ae71568d266f747fc12b5b556e78201f4321964cfe97243b7ac579`、`HANDOFF.md` SHA256 `0cb7cdb91ad9409156182691929983991df3b9a805898f50114f5b22a2ffa883`。
- **同 pin 驗證與原件**：候選 `96cc6c3ba2cac4d8806684e1443de0e9335d9590` 兩個不同 nonce focused gate `gate-s1a-final1-cd44d599bbf84c2fbab0e28ff56c5103`、`gate-s1a-final2-9db107eb0da545e58d5be02ad55ddc7e`，每輪先 remap/release隔離與1482檔prepin，後postpin無變，GameTest各精確1案、十事件，M6_FOCUSED／S1A_ORACLE／launch均PASS；oracle SHA256分別 `1650148145b8e8331bf521b9ec18e6c61391f8cc36a8d484abc8313e27c6b18c`、`f507134c2aed9f3a7d82632ba1f160a87ca2bfc6057b046c06d652cd4c9cc7f7`。雙輪 sources JAR 整檔 hash 因 ZIP DOS timestamps 不同，但256個entry payload hash逐一相同；不冒稱整JAR bit-identical。完整 `run-all-203ba85dadc64a9e99253cc99587275f` 自然 `END ALL exit=0`：JUnit971（f0/e0／既有skip2）、default179／M5 60／M6 **9/9**、recovery24、collapse69、M6 native DIAGNOSTIC2、mainOnlyPASS；summary SHA256 `0f97996ae3f351894843a223bab1a7f6126921ca5a9d24c17cb59312a2e2fc80`、console SHA256 `ee982a9414c41db571c31f6695062cdceaf8ba79448d2f1ecf5b319032271b3d`。release JAR實體／summary SHA256 `d3c71b8c943fc740652a24fce6f3895c0efc5119ddca763ae778ba88e0908ccb`、sources JAR `ce7e0d5338557a4cd60c2243f652956917afaf1421f28896064468d9355920ef`，testmodHits均0。對 full M6 gate 另執行只讀 `m6-refresh-seal-check.ps1 -Scope Full`，oracle SHA256 `b4a00a5b7880982764bab69fb8e715e72b98b1cbd2a921cdc294044260f89685`，Full／9tests／1seal／十事件／STOPPED PASS；owner0採可信 Restore-Root 首步 Assert-Idle成功控制流，locks-after另有可獨占收據，不能虛構獨立post-owner JSON欄。full後、此輪文件編輯前 Git clean、無workspace Java owner、M6 world session.lock可獨占。
- **獨立審查與後續**：S1a spec SHA256 `b86493a9d5593a64ee1eb120ae60c1110777414868edd6853c6078a85aeaf364`、quality SHA256 `41b824370d8e32612b9650d24a51946dfc202174219b48560ec14d9a3743e122` 各 C0/I0/M0，只接受自然一次成功 startup 本機片。相對原無參數 boolean 示意，實作介面帶 Lookup／manager 參數只做 exact identity 瞬時比對、不保存或回傳 raw 值；此偏離獲 spec 接受。GameTest 方法為全小寫以對原版 XML，`DIAGNOSTIC_FAILED` 會令額外 late 錯誤使 checker 拒，設計勘誤與原 root 均保留。S1b 異常／順序第二次／重入／overlap、integrated 新 active RM 同源、正式 source／同票同seal publication、consumer全圖、D1c LOOT_TABLE、100ms live hold、8B3-F 三 bundle、Task8／Task9–22 都未完成；`createSnapshot`／`resolve` 仍 fail closed。LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 原 FAIL，v4 父、B27、3b AccessDenied、T2 額外100ms 原 FAIL 不改判。截至本筆紀錄撰寫時，新 S1a code 尚未 push／無其遠端 CI。

### 2026-10-31：Codex 自用收尾交接（停止新實作）

- **Git／CI 核對時點**：本輪停止實作時 local／origin feature 同為 `d5004398c1bb9e502c75650f3d5095c3e329c443`，porcelain 空；主 checkout `main` 仍為 `0d05f889da6f8b82b97af756aaa50066b3d42854`，本輪未在 main 實作。工作樹無 live Java，`run/gametest-m6/world/session.lock` 可獨占。S1a code pin `96cc6c3ba2cac4d8806684e1443de0e9335d9590` 的前述本機 full／strict oracle 不變。GitHub Build run `41145800231` 同 docs SHA completed/success attempt1，Ubuntu `build`／Windows `windows-native` 雙 job success；Windows log 有 M6_SUMMARY／M6_MAIN_RUN／VERIFY與上傳步驟，三份artifact清單已讀未解壓。CI log有S1a事件，但沒有 `S1A_ORACLE PASS`，故遠端 strict／8B3-F三bundle仍未採。此段是核對時快照，新 docs commit 的 CI 需另讀遠端狀態。
- **最新唯讀設計，尚未獨審／未實作**：S1b 原提案 SHA256 `0436c14a65eb2456c4309f51a41eb65d26954bd4bf545c51f26738d63f80a0e1` 首審 C0/I2/M1；ownership addendum v1 SHA256 `9957d1c0aaca5221d16313201aba16c4ad507e37b16869c742b7cf1bb40cc5cc` 複審 C0/I0/M3，只放下一份唯讀設計。S1b-0 execution addendum v2 SHA256 `6ba74d6fc88bf1cfd8d653fe706bfefde109d30eadbb440109999ce0e83841ff` **未獨審**，actual packs／Fabric排序後listeners仍UNKNOWN；不能派 F1/F2 native。固定 TestServer.create fault catch會走System.exit(-1)，下游refresh fault不能靠上游RM close callback清理；不能用程序退出取代 owned close。S2 integrated新RM提案 SHA256 `1eb5e823675a11fdaea0dff0562b95ebc36393f8516693c0596c8f0ea2a76ca5` **未獨審／STOP**：舊content seal無法授權新active RM，單純re-refresh或同pack配置不證同代。
- **接續與原判**：使用者指示之後仍由 Codex 做，因此建立 `docs/handoffs/2026-10-31-m6-codex-resume-prompt.md` 作下一次入口，不產Claude提示詞。下次先核Git／ignored原件，先唯讀獨審上兩份未審草案；C/I未關閉不開S1b fault native或S2。Task8仍缺正式source/consumer、D1c LOOT_TABLE、100ms、8B3-F三bundle；Task9–22（random seed／same-seed fresh／profile、完整維度家族／portal／重生）未開始。LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 真late原FAIL、v4父/B27/3b AccessDenied/T2額外100ms原FAIL皆保留；S1a原setup／RED／突變FAIL不改判。新文件只更新接手真相，沒有新程式／build／workflow差異，未重跑相同focused或完整回歸。

### 2026-11-02：使用者裁示縮 Task 8 範圍（Claude 只讀盤點，無程式變更）

- **裁示**：使用者原話「我想先裁示縮 Task 8 範圍，至少本模組功能都完成後才修必須的任務切片」。controller 解讀為 Task 8 拆成 8-core（Task 9–14 所需最小 snapshot／provider／context 接線）與 8-H（完整資料閉包、正式 lifecycle 證明、8B3-F 三 bundle、完整 reload GameTests）；Task 9–21 全部功能完成後才 triage 8-H，只做裁示為必須者，其餘明確 waiver。解讀、界線與延後清單見 [`docs/handoffs/2026-11-02-m6-codex-resume-prompt.md`](../handoffs/2026-11-02-m6-codex-resume-prompt.md) 第 2、4、5 節；該檔取代 10-31 檔成為唯一接手入口。
- **盤點事實**：HEAD／origin `5b2bafa67790916e6d9dfac9a71c1d3e60a47442`，GitHub run `41149366263` 雙 job success、無 `S1A_ORACLE`。`UniverseProfileRegistry.createSnapshot`／`resolve` 仍無條件 throw；`src/main` 無 `Minecraft121SettingsCanonicalizer.java`；`QuantumSuperpositionMod` 無 Settings／Contexts 接線；8B3 五個 testmod lifecycle owner 與三個 settings oracle 腳本不存在；Task 9–22 owner 檔全部不存在。盤點於無 ignored 原件的 clone 完成，未核 SHA256 原件、未跑 run-all。
- **下一步**：Codex 先只改 spec（升 v1.4）與 plan Task 8 重構並送獨立 review；C/I 清零後實作 8-core，再依 plan Task 9–21。所有 retained FAIL（v4 父、B27、3b AccessDenied、T2 額外 100ms、LT2 STOP-PENDING、S1a setup／RED／突變）原判不變。本筆只有文件變更，沒有新程式、build、workflow 差異，未重跑任何 focused 或完整回歸。

### 2026-11-02：spec v1.4／plan Task 8 拆分的首輪獨立 review 與修訂（無程式變更）

- **草案與審查**：草案 commit `20338bef738ab0b0f3e633317cfd7be33cea8945`（spec SHA256 `891c7e0bb7de0e2ddde7151cf085942bbdf5f29c13c5afdc12f0a7de858fb36f`、plan SHA256 `247e70b64f0cefce16e76c7ab62bc4d818f1dc512205ed328bca2c3f8a060b5b`、diff SHA256 `5d0098a8784062d7447a9312df0af25de4339deb07f88d31d8adaeedb6da6f83`）。兩席唯讀 Claude 子代理獨審：spec review **C0/I4/M11**（報告 SHA256 `c1ab9ec35a6e6c9dfba14f7f9532a2dfcca655019c974446c4ecf41df0963be8`）、plan review **C0/I5/M11**（報告 SHA256 `5aa3302026c10312b9d1c415b471d44db7bd18b5a5789e2d823f1ba719120e12`）。報告原件只在本次雲端 session 的 scratchpad，session 結束即消失；finding 要旨與處置全部記於本條與修訂後文件，不另補造。
- **Important 處置**：(1) 內建 provider id 三處不一致：定為既有程式的兩個 id `quantumchamber:vanilla_normal_v1`／`vanilla_amplified_v1`（id↔`OverworldPreset` 必相符，provider schema 層拒），`m6_profiles_v1` 只是 PolicyId；不改 `GenerationSettingsSnapshot.isBuiltin`。(2) §3.1.1 適用範圍改為明列四段對所有 ProviderVersion binding，解除循環引用。(3) §3.1.2 兩句 v1.3 條文加 ProviderVersion≥2 範圍，並為 8-core 定義「資料就緒」。(4) `SHALLOW_V1` 加 Reference-only 建構、encode 後逐 role id 字串結構檢查、`SHALLOW_INLINE_ELEMENT` 與具名 RED。(5) plan I-1：程式核實 `M6RefreshSealStartupObserver.store(...,initial=false)` 對自然啟動 server 的任何 reload 丟 `UNEXPECTED_RELOAD`、`m6-refresh-seal-check.ps1 -Scope Full` 寫死 9 案，故 8C-2 GameTest **不做真 reload**，只驗 startup acquire、registries `==`、createSnapshot／resolve；reload／STOPPING 接線由 lifecycle JUnit（純核心＋可注入 registrar／Sources factory 的接線層）加 mutation 守，真 server reload 歸 8-H H4；`m6-refresh-seal-check.ps1` 只窄修 Full 預期數來源（該腳本不在 run-all／gates／CI）。(6) Task 9–15 取用入口釘為 `Minecraft121SettingsLifecycle.registry()`／`acquire(server)`，File map 補三個 8C 新類別。(7) 8C-0 四個 mutation 各指具名 killer。(8) 平台／CI 證據矩陣三處三 bundle 條款標為 8-H。
- **Minor 處置**：lease 讀面定為 `registries()`＋`sourceGenerateStructures()`（型別不符 `CONTEXT_UNAVAILABLE`）、三 role 精確 id、canonical 鍵序＝Unicode scalar 次序且 owner 為 Graph 編碼器抽出的純函式、8192 量法、兩條 fail closed、已知限制擴為「任何被引用 registry 元素內容」且標為 controller 設計預設、限制見證與超限技法界線、freeze 於 mod init 並在 §4 註明本輪不接受擴充 provider、§3.2 比對深度、§8 加 mutation-only＋REVERT、共用 fixture 檔、`detectOverworldPreset`、各片 focused 集合與允許檔案、Task 9／14／22 與 waiver 措辭、自查索引。全部已寫入修訂稿。
- **邊界**：本條只有文件變更；未改程式、build、workflow，未跑任何 focused／完整回歸；本雲端容器網路政策擋 `maven.fabricmc.net`、`libraries.minecraft.net`、`piston-meta.mojang.com`，無法 build。修訂稿待窄複審確認 I 項全部關閉後才在 spec／plan 狀態段補採用 SHA；在此之前 8C 不派實作。所有 retained FAIL 原判不變。

### 2026-11-02：spec v1.4／plan 8C 採用（三輪窄複審後 C0/I0/M0；無程式變更）

- **複審鏈**：首輪 fix `4ca1303` 後窄複審一 C0/I1/M3（SHA256 `ff9ae0b788bc9ba4b2704c25166db6ba87f24e93149c815eac0a1007388868f1`）：I-N1 接線層 mutation 無具名 killer→`4a50629` 改 Lifecycle 三層（純核心／`Sources.of`／可注入 Registrar 接線層）與 JUnit `lifecycleRegistersAllFourFabricEventsAndDelegatesToCore`，並處理 M-N1（GameTest 不斷言 S1a private failure）、M-N2（owner 總表補 `Minecraft121SettingsGraph`）、M-N3（toggle 同步切 ProviderId）。窄複審二 C0/I1/M2（SHA256 `e52e6a80755a7faceb6d193242010f951be0d0d792f02194702376e2f2a0c920`）：I-N2 END listener 以 null 呼叫會在 `Sources.of` NPE→`2b1764e` 接線層再注入 `Function<MinecraftServer, Sources>`，四個 listener 委派皆可在 JUnit 以假 factory 核；M-N4 `lifecycleStoppingRemovesServerEntry` 補 lease `requireValid` 拒的斷言；M-N5 本 note 措辭同步。窄複審三 C0/I0/M0（SHA256 `943626daecfb517da91a74828d02412c4866c2c81e2f702e8d75c30ccb04ebb2`）。報告原件只在雲端 session scratchpad。
- **採用**：spec v1.4 採用版 SHA256 `e9dbaac9736a83a38dde96605c2ffd411a7b716b35e241eaa209d4943748dad2`、plan SHA256 `e087f43c1717fa63ea6857220544443ca8d528930d71cf107acb5424001646a5`（含本條之前的狀態段更新）。接手檔第 6b 節記 commit 鏈與 CI；第 9 節新增 8C-0 派工簡報。AGENTS 接手入口同步。
- **邊界**：本輪只有文件；未寫 8C 程式、未跑 Gradle／focused／run-all（雲端容器無法 build、無 pwsh）。遠端 CI 只證純 docs commit 未弄壞既有 gate：`4ca1303` run 41432221278 雙 job success，`4a50629`／`2b1764e` 的 run 撰寫時 in_progress。v1.4 未與 ignored SDD ledger 交叉核對，Codex 到場先補核。所有 retained FAIL 原判不變；8-H 四項狀態不變。


### 2026-11-04：Task 8C-0 SHALLOW_V1 canonicalizer／內建 provider 本機結案

- **範圍與能力**：range 071bdf0bc0f3eca7b8329498a7de239db1829e5d..d39fef1530bcf30102731e15aca1478657429c30，依序為 1c10b6a 本體、a8b8d20 Graph depth fix、d39fef1 preset detect I-1 fix；恰九個 plan 8C-0 白名單檔。lease 只新增 registries()/sourceGenerateStructures()；兩內建 provider id、三 role Reference options 與 SHALLOW_V1 四鍵 canonical／8192-byte cap／32-byte FP 已實作。真 RegistryLoader 兩次獨立載入及 amplified seaLevel 覆寫見證通過；三 role 實測 canonical 長度 Overworld 208、Nether 203、End 162 bytes。detectOverworldPreset 對 Direct／flat 合法但不支援來源回 empty，失效 lease 仍 CONTEXT_UNAVAILABLE。正式 createSnapshot/resolve 與 lifecycle 仍屬 8C-1／8C-2，不宣稱 Task 8-core 完成。
- **RED／突變與原 FAIL**：fail-closed／permissive 具名 RED 覆蓋 16 個 canonical/provider 名稱及 lease 新讀面；四個原計畫 Contract／Roles／GenerateStructures／preset mutation 各由指定測試殺掉並 REVERT 相同 hash。首輪完整 run-all-8b67ce90f75f4fa4a468a587be4d98cf 在既有 Graph depth JUnit 失敗（JUnit990/1），原 root 永 FAIL；a8b8d20 將原 graph limits 傳回共用 Encoder，depth mutation 由 canonicalAndDepthBudgetsRejectBeforeUnboundedCopy 殺掉。spec 首審 I-1 後，Direct／flat／失效 lease 定向 RED 5案3敗0錯、GREEN 5/5；detect-shape mutation 為 5案2敗0錯，REVERT hash相同。六個 mutation result JSON 原件在 ignored task-08/8c0-evidence/，不 force-add。v4父、B27、3b AccessDenied、T2額外100ms及LT2 STOP-PENDING 等既有 FAIL 原判均不變。
- **乾淨 pin 驗證**：末 pin d39fef1530bcf30102731e15aca1478657429c30 的 ProviderTest 5×2、ShallowCanonicalizerTest 14×2、ContextsTest 44×2 focused 全 PASS；depth fix pin a8b8d20 另有舊 CanonicalizerTest 123×2 PASS。完整 run-all-3e4a5c71830d4a5ea0e642176c7576b2 自然 END ALL exit=0，JUnit993 f0/e0/既有skip2、default179/179、M5 60/60、M6 9/9、recovery24、collapse69、mainOnly PASS、release/sources testmodHits0、M6 native仍DIAGNOSTIC2；summary SHA256 1352A87DD7FE6722CEB193AE6B9FCCB0DF345BBFB53B9553B5D680A1DF6BC352，console SHA256 673E1C6A6F28022BDE76A86C7AE5940DDE644B8EF6D84A7667FAD5776A5793B7。full後 HEAD/porcelain 空、M6 session.lock 可獨占；殘留 Java PID31136 是 GradleDaemon。
- **獨立審查與後續**：spec 首審 C0/I1/M0（報告 SHA256 FA38DB07E0AF92579C4ADFF7B71200DA030DD3E8130038C752675BBC74A95175），I-1 修後窄複審 ADDRESSED、C0/I0/M0（SHA256 2E1E726C8225171A98C955612D948EC0D555ED9A7C37D48C8FBD3B7DA42ACCAE）；quality Approved、C0/I0/M1（SHA256 D8907E2964B12AE8661922AAE7186990342366D95EE637C72D3312EF8B09AD77）。唯一 Minor：測試 fixture 的 ResourceManager close 與 overlay 刪除雙重失敗時，清理錯可能遮蔽原 registry 載入錯或留暫存目錄；交後續 fixture 整理，不影響已驗 production 路徑。此筆文件撰寫時 docs truth commit／正常 push／本 code 遠端 CI 尚待；雙平台成功後接 8C-1。SHALLOW_V1 對被引用 registry 元素內容改寫不敏感，仍依 spec v1.4 已知限制；8-H H1–H4 尚待 Task 21 後 triage。


### 2026-11-04：Task 8C-1 snapshot／resolve 真路徑本機結案

- **前置與範圍**：8C-0 docs HEAD 551607cc69a64ecebd5ebc31e2aa0afd5739a33a 的 GitHub Build run 41833239533 Ubuntu build／Windows windows-native 雙 job success；CI 只採 8C-0 code d39fef1，不冒稱 8C-1。8C-1 code range 551607cc69a64ecebd5ebc31e2aa0afd5739a33a..d38178b467e6412aa070091ad8228fd22366347d 恰 UniverseProfileRegistry.java 與 UniverseProfileRegistryTest.java 兩檔。Registry createSnapshot／resolve 對 frozen registry、精確內建 ProviderVersion1／SHALLOW_V1 綁定、Parameters schema、同 lease 及回傳前有效性、五鍵 snapshot 512 bytes 與重算 FP 逐 byte 比對走真路徑；已註冊但未綁 closure 的擴充 provider 仍拒。8C-2 lifecycle／GameTest、8C-3 終局與 Task9 均未完成。
- **RED／GREEN／突變**：初次 RED 只命中8個失敗，原root保留；新具名 RED 12案9敗0錯，九個 plan 名稱全命中，非 compile error。候選 RegistryTest 12/12 GREEN。四種 production-only mutation（未 freeze、未綁 closure、FP mismatch、回傳前 lease 重驗）最終各由對應具名測試殺掉、mutation-only diff 與 source REVERT SHA 相同。after-lease 首輪 mutation 存活（exit0）不能追認被殺；首輪僅 mutation-results-first.json summary SHA256 8D410BD023E49AD9A10FC37F30526B795959DD2DAD8D68E66E1021688A952D4E，raw log／XML 被固定檔名的修後輪覆寫，搜尋後確定不可恢復，不補造／不重跑同 root。修後 mutation-results-after-fix.json SHA256 36CC2C69C2F26371C92E9C1CF7384438492049B0A7059C41C03DA13E70C66EA2 有具名殺手與獨立 raw；ignored 勘誤 SHA256 B3850FB42A3C7231E4F2546B446E2E91D3834C0A369029765BD0B719ED3BF538 明列缺口與後續唯一 root 規則。空的 mutation-results.json 不採。
- **乾淨 pin 回歸與審查**：末 pin d38178b467e6412aa070091ad8228fd22366347d 的 RegistryTest 12×2、GenerationSettingsSnapshotTest 7×2、ShallowSettingsCanonicalizerTest 14×2，六次不同 root 的 focused 皆 PASS，前後 pin／porcelain 空。完整 run-all-01dd2ce3b8b844178b291d4869ef00d5 自然 END ALL exit=0：JUnit1002 f0/e0/既有skip2、default179/179、M5 60/60、M6 9/9、recovery24、collapse69、mainOnly PASS、testmodHits0、M6 native仍DIAGNOSTIC2；summary SHA256 A84EFB9817B598EACA969650107DB0BAFB7627175108F1D6E66E7AAECCD901A2，console SHA256 57986A190A2A3C3A8E636ADEC89AA632161694D9493B59F4243ABD6FA5A9DA1D。獨立 spec C0/I0/M1，報告 SHA256 76832882CA69A575C8353D6207D66559FF26C48C5F2C0541BC3BFA62C216A240；quality Approved C0/I0/M1，報告 SHA256 42A28F63887682D1D2DE41804A25AEC347D0F3CE030D95AFB3250C3D33A3A806。同一 Minor 即首輪 after-lease raw 保全缺口，最終 whole-branch review 再 triage。此筆撰寫時 docs truth commit／push／本 code 遠端 CI 尚待；8C-2 需等雙平台成功。v4父、B27、LT2 STOP-PENDING 等既有 FAIL 原判不變。


### 2026-11-05：Task 8C-2 最小 lifecycle／自然 startup 接線本機結案

- **前置與範圍**：8C-1 docs HEAD 443221e634142e9ee39bc1cb5b0123c6c08cf059 的 GitHub Build run 41863993829 Ubuntu build／Windows windows-native 雙 job success。固定 Fabric sources 與 Minecraft bytecode 前置審指出 foreign START／停後 END 執行緒風險，thread/ticket ignored addendum SHA256 64B357BA5E12F06E46BCC787A292A7392B99603F8AF17D4D9F03E2F486A08E1D 經獨審 C0/I0/M2，tracked spec／plan窄修 docs 77d8b50。GameTest 首次因 testmod manifest 沒登新 class 而指定 batch 空，原 setup FAIL 保留；plan 第七檔窄補獨審 C0/I0/M0、docs f8f8d847b5509e02a84cde2c1685986d687c5780。code range f8f8d847b5509e02a84cde2c1685986d687c5780..16b833cc43278fe8fb56e8e75f2aed4beee49667 恰七檔；mod init兩內建 provider→freeze、四 Fabric events、exact server最小 Contexts接線，START同步撤舊lease、STOP同步detach、END守唯一原ticket且factory前拒無效。自然GameTest只驗startup acquire／registries identity／normal與amplified各三role及FP，不真reload、未改S1a observer。8-H完整source同代、真reload與三bundle仍未驗。
- **RED／突變與原判**：stub M6Compile PASS、Lifecycle三具名 CONTEXT_UNAVAILABLE RED及真自然GameTest acquire CONTEXT_UNAVAILABLE RED；早期FQCN錯誤 M6_JUNIT_SCOPE_MISMATCH、batch空、單參數 START listener M6Compile FAIL各原root保留，不算RED。初次 Sources factory 失敗後reservation殘留另具名RED→GREEN。原計畫與thread補充 mutation逐型具名殺，stop-no-detach首輪survivor原raw留存，新root具名殺；wrong-registry mutation的GameTest registry==斷言具名殺，但同root S1a EVENT_ORDER使整體FAIL，不冒稱clean strict證據。舊round2逐輪REVERT JSON未保存，只能核其腳本finally guard及最後base hash；不補造逐輪收據。接力新增mutation有獨立逐輪REVERT JSON。ignored接力索引 SHA256 D2DD209331B3378CAB90EE114FB3D9B1A28D94532F9BA5565E74155AF7255B60、mutation audit SHA256 8B75039826DAB057C7B6C8F858B33E7806611499A798A00D574028CEA26500BF；所有既有v4父、B27、LT2 STOP-PENDING及8C先前FAIL原判不變。
- **乾淨pin回歸**：末pin16b833cc43278fe8fb56e8e75f2aed4beee49667 的 LifecycleTest12×2及自然settings GameTest1×2四個不同root focused PASS，pre/postHEAD與porcelain一致。完整 run-all-f9344a453d914fc2b22bae056c0fed1e 自然 END ALL exit=0：JUnit1014 f0/e0/既有skip2、default179/179、M5 60/60、M6 10/10、mainOnlyPASS、recovery24、collapse69、release/sources testmodHits0，M6native仍DIAGNOSTIC2；summary SHA256 95E2846D3EE676BDB8687B844A03BAF043381B5E7421E018E90A70FA9FBAE743，console SHA256 8A022454F7934C8536CB8F5C28702B0834371B18AC571349F523A097A82A307C。另對同full M6 gate只讀跑S1a strict Full：10案／固定十事件／failure空／STOPPED PASS，oracle SHA256 79A1F7A568A9F74641B876C68FA26F9D2FC7AC5D54643AED50370344015E2DD7；舊9案full原件少案負例 exit1 S1A_ORACLE_SCOPE，原root未改。full後tracked clean、只有閒置GradleDaemon，M6 lock可獨占。
- **獨立審查與後續**：spec C0/I0/M1，報告SHA256 F83156A9D4715485E4BF0F4EEF9747A7EEA07D756E68A985DA0F1C7F06933B71；quality Approved C0/I0/M1，報告SHA256 76FA461BF2E2A576B89D9B94B7BED93D798EFC3921D83D02824335E627EDB42A。同一Minor是 foreign END／dispatch 異常未永久poison，tracked最小契約只要求同步撤舊lease、factory前短路及原claim final guard，已守；較強ignored addendum此兩分支未原樣實作，後續若要永久poison須另開窄fix，不能稱已驗。此筆docs truth commit／正常push／本code遠端CI及8C-3終局尚待；Task9未開始。SHALLOW_V1對引用資料內容變動不敏感的限制保持原判。


### 2026-11-05：Task 8C-3 終局準備與 8-H 狀態

- **同 code 終局原件**：8C-2 docs HEAD e1eb9fcdda9c38496db803c0797a7477a7b363c1 的 GitHub Build run 41911139872 同SHA completed/success，Ubuntu build／Windows windows-native雙job；兩邊M6 JUnit/GameTest與精確集合/隔離、Windows main-only三層oracle／原生案例、上傳步驟均success，三artifact清單已讀未解壓raw audit。末code pin16b833cc43278fe8fb56e8e75f2aed4beee49667 之後只有AGENTS、handoff、M6 note、plan四份docs，沒有code/build/workflow變更。其本機full run-all-f9344a453d914fc2b22bae056c0fed1e自然END ALL0／JUnit1014 f0e0 skip2／M6 10/10／M5 60/60／mainOnlyPASS／testmodHits0，另S1a strict Full10案固定十事件PASS；純文件不重跑同focused或full。8C-3本機唯讀審核報告SHA256 14E7689190DEFD7071F55A2B0852C5004677388DFF0825797785532FA1F92A48，C0/I1/M1；I1是handoff§4仍用legacy直呼摘要，已改為Lifecycle thread/ticket接線待窄複審。M1是8C-2已揭露foreign END／dispatch未永久poison限制。最終8C-3 docs truth／commit／CI尚待，不提前宣稱8-core完成或Task9可開。
- **8-H交接狀態（只列事實，尚未triage／waiver）**：H1完整資料閉包ProviderVersion≥2仍缺D1c LOOT_TABLE、三role完整canonical/FP、100ms live hold I-1，T2額外100ms原FAIL保留。H2正式lifecycle/source同代仍缺S1b異常與重入/overlap、integrated active RM同源、source bridge／consumer final acceptance，LT2 STOP-PENDING真late原FAIL保留；8C-2最小SHALLOW adapter不等於此證明。H3 Windows named／Ubuntu named／Windows intermediary三bundle逐byte比較、正式production runtime與三腳本未做。H4多inflight先序、controlled future失敗後恢復、owned tag／loot同epoch真reload GameTests未做。依11/02裁示，Task21完成後才獨立review提案並請使用者裁示哪些為M6必須、哪些明確waiver；現在無任何waiver。v4父、B27、3bAccessDenied、T2、LT2及8C各原FAIL原判不變。SHALLOW_V1對被引用registry內容變動不敏感，仍依spec已知限制。


### 2026-11-05：Task 8-core 終局 gate 結案，下一片 Task 9

- 最終code pin16b833cc43278fe8fb56e8e75f2aed4beee49667 的本機完整 run-all-f9344a453d914fc2b22bae056c0fed1e 自然 END ALL exit=0／JUnit1014 f0e0既有skip2／M6 GameTest10/10／mainOnlyPASS／release與sources testmodHits0，S1a strict Full另10案固定十事件PASS；該code之後只有 AGENTS、handoff、M6 note、plan四份docs，沒有code/build/workflow差異。最終docs HEAD0f02011b97e9b03b043afbfe33d14aa203d88708 已正常push且ls-remote同SHA；GitHub Build run41918108739 同SHA completed/success，Ubuntu build與Windows windows-native雙job及M6／精確集合隔離、Windows main-only三層oracle／原生案例與證據上傳步驟皆success；artifact清單quantumchamber-native-windows、quantumchamber-tests-ubuntu、quantumchamber-jars已讀未解壓raw audit。8C-3本機審核原C0/I1/M1（報告SHA256 14E7689190DEFD7071F55A2B0852C5004677388DFF0825797785532FA1F92A48），handoff§4 legacy直呼摘要修後獨立窄複審 ADDRESSED、C0/I0/M1（報告SHA256 E74D0653CD254E4BAB135E851924F219DD8D58EB14317BB6378A614AE1151140）。唯一Minor沿8C-2已揭露foreign END／dispatch異常未永久poison，tracked最小factory前短路與原claim guard已守；不改稱ignored addendum完全實作。本筆只更新整合狀態與plan勾選，不重跑同code focused/full；Task8-core（8C-0～3）可依此 gate 結案，下一片Task9。Task9–21與M6整體仍未完成；8-H H1–H4仍在Task21後才triage，尚無waiver，所有原FAIL原判不變。

- **Task9前置設計狀態**：唯讀preflight SHA256 F5711DCE1E5E1A670A320019C8128DF685125A0F7256FBB95FA63176E1BD98A5；執行addendum首審 C0/I2/M2，報告SHA256 9AE4FA8BD5FEF1B3038734D1ED926301674D609DF2111F95D43DF1A8EA337317。I1為PersistentStateManager.get在deserializer前 unlimited讀完整檔，callback內bounded驗不能取代首次load；I2為原始M6 marker可被重複Context／Intent祖先欄位覆蓋。修訂ignored v2 addendum SHA256 2BB81A215355D4CA134DFEFE5E0C1D84C53C1EF16F65DE43B85C805D1580E295 已寫並經獨立窄複審C0/I0/M0（報告SHA256 86A9599D8AC189EDEF05A8FEA81469F9FE0981E0E1A5D77B5CFC6253B00CD9EB）；tracked plan窄補已獨審C0/I0/M0（報告SHA256 832EB19BF104F7C2A31804A407654680F551F5487640341D78CAA191F09BEF50），隨本docs commit採用；Task9程式尚未開工，不能把8C結案自動視為此設計放行。

- **8-H 原件 SHA 索引（8C 結案時）**：H1 的 D1C-PROPOSAL.md SHA256 75AA599216D3300FE163BF83D254222A7A42D4D0E7E0911655B69337C099332F、D1C-ADDENDUM-v2.md SHA256 E38C502C200E0679F1ABC2414DE4759F0643FDDC0D5DD3F614013C6AB21C5A71；D1c-1與100ms正式結案原件 NONE，T2額外100ms原FAIL另保留。H2 的未審 S1B0 execution v2 SHA256 6BA74D6FC88BF1CFD8D653FE706BFEFDE109D30EADBB440109999CE0E83841FF、integrated RM提案 SHA256 1EB5E823675A11FDAEA0DFF0562B95EBC36393F8516693C0596C8F0EA2A76CA5；正式source/consumer結案原件 NONE，LT2 STOP-PENDING原root 6bc60fc91584008b983aa668ebad1f00 仍FAIL。H3三bundle與正式compare原件 NONE；H4真reload GameTest原件 NONE。以上 NONE 表示尚未建立，不是PASS或waiver。


### 2026-11-05：Task9 reserved journal slot 縮範圍裁示

- 使用者補原話：「我同意先推主線」。此話回覆 controller 的「只有 mod 會觸發的硬化，可以改成先推主線」，意指 Task8-core／Task9–21 功能先行，未授權現在合併 main、tag、PR 或退役工作樹。
- Task9 保留 `SessionJournalStore` 對同一份 compressed bytes 的版本探測與有界 load，A／B／C、raw walker、pending 故障語意及 16MiB／4096 上限。slot mixin 只留 `@Shadow loadedStates`、私有 fence、唯讀 ABSENT／PRESENT_NULL／SAME／OTHER；首次 ABSENT 登記 LOADING，安裝前再核 ABSENT、用原生 `set`、安裝後核 SAME；之後本模組每次 get／owner save 核 SAME，不符拒回、拒寫、停寫，保留 dirty／current／flushed／pending。弱 binding、LOADING 重入與雙 server 隔離不變；M4 空 schema3 的 testmod cold-rebind 保留。
- 其他 mod 在安裝後以公開 `set` 碰同 id、或以另一 type 公開 `get`，本片不即時攔截；下次本模組 get/save 才 fail closed。在此之前原生 autosave **可能把 foreign state 寫入同名檔**，為使用者明確接受的已知限制。三個原生 hook、只為 hook 所需 permit／負控／mutation、真 intermediary/JDI gate 與 handler monitorenter 證明均列 Task8-H H5，Task21 後 triage，不提前 waiver。
- 此時 code HEAD `743ffbf891b81b585e12dbbd3fc25d09656b089b` 僅是被取代的舊設計 docs commit；Task9 code 是未提交 WIP，未達縮範圍後的乾淨雙 focused、full、spec／quality、CI，不能稱結案。舊 `focused-t9-slot-v3-public-red-e0efdcab5c054885abee920c7c52aaac` 與 `focused-t9-slot-v3-public-green-31bdf3781130412dbe13890a75a76bd6`、Legacy 原 FAIL 均保原件／原判，不挪為新版證據。縮範圍 mixin 驗證採 compile／remapJar、GameTest、完整 run-all、雙平台 CI。Task10–21 同類其他 mod 專屬碰撞先事後 fail closed；新增 plan 外 production 注入點、腳本或外部 JVM observer 先請使用者裁示。
- 縮範圍五份 tracked docs 獨立窄審初 C0/I1/M0：Task10–21 通則「不寫壞」措辭超出已接受的 vanilla autosave 風險；plan 改為偵測後本模組拒回／停寫、不主動覆蓋，偵測前風險仍在。同一審查席唯讀重核 I-1 關閉，最終 C0/I0/M0；此審只涵文件，不採 Task9 WIP code。


### 2026-11-05：Task9 schema5／policy1 本機與審查終局，遠端 CI 待

- **範圍與 code truth**：`d52936d7dc6cf583f2eaeb4dd6d3950a6576914f..73266221f8334bd1dbb4ca5c0193e239e8395a93` 含本體 `9ef4936`（15白名單檔）、純plan相容窄補 `01be38c`（獨審C0/I0/M0）、三測試檔 fix1 `d414b60`、State／SchemaFiveTest fix2 `ef7b2e5` 與 fix3 `7326622`。只加 schema5／policy1 格式解析與新意圖；正式 `freezeCandidateContext` 仍產 M4，不啟用 policy2/3、family1 或玩家 random NEW。新正式首次 journal 由 Store 對同一份 bytes 探版與有界載入；slot mixin 只有 `@Shadow`／唯讀四態，安裝前ABSENT、後SAME，後續本模組get/save核SAME停寫。其他 mod 碰保留鍵後，在下次核對前 vanilla autosave 仍可能寫 foreign state，H5尚未實作。
- **RED／容量／保留失敗**：原4096全NEW合法形狀於正式tracker計量17,258,862>16,777,216，原 `focused-t9-c3-capacity-fb71565ee14244e0b1b3db299ef12100` 33案1FAIL保留，改作超限反控制；同形狀3981筆16,774,482可checked寫出、3982筆16,778,694拒，4096計數上限另以較小合法形狀守。首本體full `run-all-d0b5d7fa10a64433892471e08d37d93d` JUnit1067/6 FAIL、world exactRestored，六案XML索引SHA256 `87451562B8D94F4BDF27021ADCF7CCDDB08CD8DC5E3914C72E7E64E09E86A61B`：新fixture先freeze全域registry造成舊測試五敗、舊schema4 sentinel=5造成一敗；原root永FAIL。縮後slot RED42/2→GREEN42/42及五輪 mutation-only/逐位元REVERT；fix2 RED47/5→GREEN47/47、兩輪mutation殺；fix3 RED48/1→GREEN48/48、單迴圈mutation殺。最終 fix3 mutation索引 SHA256 `BF5760A0DEB3AB3CA5F558E27175403A71A0CCA2EF18B6EACCBB394187F19C9C`；舊v3即時攔截root保原判，不挪作縮後證據。
- **乾淨驗證與獨立審查**：最終pin `73266221f8334bd1dbb4ca5c0193e239e8395a93`，四類 resolver14／codec5／journal48／schema4 41 各兩輪，216案次全過；完整 `run-all-7c709cf9aa4f4f4eb2f036a8b1631fb1` 自然 `END ALL exit=0`，summary SHA256 `C1E7CF8E1DF4EE34264D336DFB0D83832EEDD870B56B7579A2310010EAAF6709`，JUnit1073/0/0 skip2、default179/179、Legacy1、M5 60、M6 10、main-onlyPASS、recovery24、collapse69、M6 native DIAGNOSTIC2、release/sources testmodHits0，pre/post同pin、porcelain空、Java owner0、M6 lock可獨占；最終索引SHA256 `150E671F6DC917065DE1107F1CD13DF008BE60E391C67CB3802262B2C721AD5D`。spec初C0/I2（pending未知後同instance可復寫；wrong manager後回原binding可寫），fix2 I1關而I2尚缺S2/B先佔順序，fix3同席窄複審I1/I2皆ADDRESSED、最終C0/I0/M0；獨立quality C0/I0/M0。fix2完整run-all雖自然PASS，外層整理器因多目錄假設exit1，原件與限制保留，不拿來代最終pin。
- **尚待**：本code仍未正常push或讀回同SHA Ubuntu／Windows雙平台CI；前docs-only `d52936d` 的run `41989850628` 雙job success只採先前code，不採Task9。四份tracked docs truth已獨立窄審C0/I0/M0；接著commit／push／CI，通過才結Task9，下一片Task10。main合併、tag、PR、人工代驗及工作樹退役另守門檻；所有retained FAIL原判不變。


### 2026-11-05：Task9 遠端雙平台採計與 Task10 前置勘誤

- Task9 最終 code pin `73266221f8334bd1dbb4ca5c0193e239e8395a93` 的本機 gate、兩席審查與 retained FAIL 見上節。含該code與四份tracked docs truth 的 feature HEAD `870e2947fa02a8fd3196bcde62cd818a02ca0ca4` 已正常push、ls-remote讀回同SHA；GitHub Build run `42035809807` 同SHA completed/success，Ubuntu `build`、Windows `windows-native`雙job成功。Ubuntu／Windows M6 JUnit與GameTest、Windows main-only三層oracle、雙平台精確集合與隔離、Windows原生案例、雙平台證據上傳步驟均success；三artifact清單 `quantumchamber-native-windows`、`quantumchamber-tests-ubuntu`、`quantumchamber-jars` 已讀，未下載解壓raw audit。此前docs-only run `41989850628` 不採Task9 code。據此 Task9 自動驗證與審查門檻結案；正式玩家入口仍是M4，random NEW到Task12才可玩，M6整體遠未完成。
- Task10唯讀預查發現 plan 原列 `candidateWriterRejectsNbtSizeTrackerOverflow` 與Task9已驗 writer 容量上限重複，且Task10白名單沒有candidate owner。plan窄補改採Task9兩個正式 `SessionJournalSchemaFiveTest` 容量測試作前置證據；Task10新catalog／envelope若另有超限路徑，仍須在本片owner另立RED，不把Task9結果冒稱catalog2已驗。獨立plan-only窄審C0/I0/M0；本筆與Task9終局純docs一併提交後才派Task10。未加plan外production注入點、腳本或外部JVM observer。
- 首full `run-all-d0b5d7fa10a64433892471e08d37d93d` JUnit六FAIL、4096全NEW容量原FAIL、舊v3 slot各root、v4父與其餘retained FAIL原判不變；H5待Task21後triage，無waiver。Task9本片已接受的foreign autosave視窗限制仍在；main合併、tag、PR、人工代驗與工作樹退役未授權。


### 2026-11-06：Task10 allocation2／catalog2 本機與雙審終局，遠端 CI 待

- **code／scope**：plan-only 入口 `25a4d7525644821c661f81f50945c409bac53d57`（FUNCTIONAL定向走既列 `m6-universe-probe.ps1`）與 `c6ed3e23315edd7a6796192a81475626df8132ca`（`NEW_M6` 真 `DISABLED` 暫存、首tick前四phase halt77），各經獨立設計窄審修後C0/I0/M0；本體 `b7bff070de7936513122f204cf71fcc32338d66f` 精確20白名單檔，fix1 `b01abc54e44f681e7c717fbe2fe5c25b6ff2ae1a` 只改 UniverseRegistryState／Test、fix2 `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c` 只改 UniverseRecord／DefinitionTest。正式新配置先 checked allocation2 token→UUID／root seed／profile／目標FP，再 checked catalog2 保存 `NEW_M6` 三role exact key／四欄與目標snapshot，availability真 `DISABLED`；未啟Task11 backend或Task12玩家入口，不物化任何新role。既有 id-only NEW 見M6 row拒造schema1 catalog，EXISTING仍依原catalog／EAGER_ONLY；未支援 `MIGRATED_SCHEMA1` 一律fail closed待Task15，不發明遷移profile。
- **RED／突變／原判**：純codec及catalog具名RED後各新nonce GREEN，真 `catalog-readback` 初次dispatch `M6_CHAIN_UNKNOWN` RED後四phase功能鏈雙輪PASS。五個production-only mutation與fix1、fix2獨立殺手各留mutation-only diff／具名killer／逐位元REVERT；首mutations-`a9d292d5` baseline路徑錯指主checkout但寫入失敗、id-only `40d6ad68` 外層取證參數錯、fix2首CRLF定位失敗均保留不完整原判，後以新nonce補證；主checkout porcelain唯讀核空。首full `run-all-9f3ac0e3f01d4fe58d654ea9853fb293` 在Windows更新計畫重啟（System event1074，2026-11-06 01:29:06）中斷，沒有END ALL/summary，原判INTERRUPTED；五world restore exact與當次locks已核，不追PASS。前pin `b7bff070` 的 `run-all-95f676dc33594f01bcd69fa5edeb3278` 內層END ALL0，外層因稍後fix1 WIP被延遲postpin讀到而exit1，明記外層FAIL、不冒整體乾淨；fix1首GREEN拿gzip row片段逐byte比而FAIL（291/292），按spec改採canonical NBT＋NBT等值＋逐索引/Extra型別值並保原FAIL。
- **最終本機gate／review**：最終pin `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c`，三類乾淨focused每輪63案、雙輪126案次，`catalog-readback` FUNCTIONAL四phase各兩輪；完整 `run-all-7f5d72f098a242efb685cea1f9135d4a` 自然END ALL exit0且外層exit0，summary SHA256 `B12B46A3E89C892C2A80815F35C2ABAD17CA42AC3DFF8DD005B78711FFD282ED`，JUnit1086/0/0 skip2、default179、Legacy1、M5 60、M6 10、mainOnlyPASS、recovery24、collapse69、M6 native `DIAGNOSTIC:2,FUNCTIONAL:4`、release/sources testmodHits0；五restore exact、2421把session.lock全解、Java owner0，最終索引SHA256 `37981288E398C28065BF20995BAA43B53BCAFD5922C8E7C0ABAE04ECDF915DF3`。獨立spec初C0/I1（schema2 envelope誤拒合法schema1 record／role Extra），fix1同席窄複審I1 ADDRESSED C0/I0/M0；獨立quality初C0/I1（未支援遷移profile被接受），fix2同席窄複審I1 ADDRESSED C0/I0/M0。此本機片不證首tick後M5續行、正常stop或可玩random NEW；Task11/12仍待。
- **遠端與限制**：本code尚未push或讀回同SHA Ubuntu／Windows雙平台CI，前Task9 docs-only `88d4dd0` run `42042882124` success不能採Task10 code。四份tracked docs truth獨立窄審C0/I0/M0；commit／push及同SHA雙job成功後才結Task10、進Task11。其他mod觸碰保留id的事後fail closed與已接受的偵測前autosave風險沿2026-11-05裁示；事前hook／專用oracle仍Task8-H H5及延後清單，沒有本片waiver。main合併、tag、PR、人工代驗與工作樹退役另守門檻。


### 2026-11-06：Task10 遠端雙平台採計與 Task11 入口勘誤

- Task10 code pin `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c` 的本機 gate、雙審與保留原判見上節。含該code與四份tracked docs truth的feature HEAD `cceacd5140f1e1a6e8fedab2430cd67d772ad0f9` 已正常push、ls-remote讀回同SHA；GitHub Build run `42163888020` 同SHA completed/success，Ubuntu `build`、Windows `windows-native`雙job成功。雙平台M6 JUnit/GameTest、Windows main-only三層oracle、M6精確集合與隔離、Windows原生案例、雙平台證據上傳步驟均success；三artifact清單 `quantumchamber-native-windows`、`quantumchamber-tests-ubuntu`、`quantumchamber-jars` 已讀未解壓raw audit。據此Task10自動驗證與雙審門檻結案；正常首tick後NEW_M6續行／seed owner／玩家random NEW仍屬Task11/12，不因CI過就稱可玩。
- Task11唯讀預查查到plan原 `m6-focused.ps1 -Suite Probe -Chain native-seed-functional` 預設DIAGNOSTIC且成功文字固定diagnostic-only，不可作功能PASS。plan窄補改用Task11 Files已列 `m6-universe-probe.ps1 -Chain native-seed-functional -ExpectedMode FUNCTIONAL`，先接完整manifest／Java dispatch再取真正行為RED，以原生摘要和逐phase receipt判；`run-all.ps1` 必逐chain保舊diagnostic2、Task10 catalog功能4與Task11 seed功能鏈各自mode/schema/phase，原診斷負向原判不改。獨立唯讀plan窄審C0/I0/M0；本四份閉幕docs truth獨立窄審C0/I0/M0；此與Task10閉幕純docs一併提交後派Task11，不改白名單外 `m6-focused.ps1` 或新增驗證腳本／外部observer。
- 首full `run-all-9f3ac0e3f01d4fe58d654ea9853fb293` Windows更新中斷、前pin `run-all-95f676dc33594f01bcd69fa5edeb3278` 內層END ALL0但外層晚postpin讀到fix1 WIP而exit1、fix1首GREEN gzip row片段逐byte誤assert FAIL、其它mutation取證不完整及全部retained FAIL原判保留；H5後置，沒有waiver。Task10 `NEW_M6` 目前真 `DISABLED`、只證bootstrap至首tick前；main合併、tag、PR、人工代驗、worktree退役仍需各自門檻。


### 2026-11-06：Task11 原版 `locateStructure` 消費點使用者裁示

- **原件與影響**：Task11 唯讀 native 設計原件 `.superpowers/sdd/2026-10-24-m6-universe-generation/task11-locate-design/design.md` SHA256 `FC123A7326A122454180311C06446A6B865002D7943B99DB169A789E301E7143`，附固定 Minecraft1.21／Yarn build.9 common及merged JAR hash、九份 `javap -c -p -s` 輸出。兩份JAR皆證 `ServerWorld.locateStructure(TagKey,BlockPos,int,boolean)` bytecode12呼全域 `GeneratorOptions.shouldGenerateStructures()Z`，false於18–19直接null；原版終界之眼、探索地圖loot、村民地圖與海豚會直接走此入口；`/locate structure` 另直呼 `ChunkGenerator`，不能替代此見證。global=false/frozen=true會錯誤提前拒絕；反向會執行本應略過的搜尋，既有structure start可能漏回。這是原版玩家路徑，不符合「只有其他mod才會觸發」的延後條件。
- **裁示原話與最窄範圍**：使用者回覆「同意 Task 11 窄補（建議）」。binding spec §4與plan Task11補入：只在原白名單 `ServerWorldUniverseSeedMixin` 新增 `ServerWorld.locateStructure` 的唯一 `GeneratorOptions.shouldGenerateStructures()Z` callsite `@WrapOperation(require=1)`；exact checked schema2 `NEW_M6` server/world key/catalog descriptor/live instance owner 回 frozen `GenerateStructures`，canonical、schema1及未匹配回原版。owner需在world發布前建立、建構失敗finally清理；不得改全域options或用建構期ThreadLocal當長期owner。不新增第三mixin class、驗證腳本或外部JVM observer。新具名RED／GREEN、global/frozen兩方向真可定位fixture、原版眼睛或地圖整合witness、雙U/legacy/canonical/重啟反控、mutation-only＋REVERT、compile/remapJar、native功能鏈與完整回歸均在Task11內驗；Task15 `MIGRATED_SCHEMA1`仍待。兩份tracked設計草稿獨立窄審C0/I0/M0，五份docs truth亦獨立窄審C0/I0/M0；此時尚未純docs commit，Task11 locator注入未實作。
- **邊界**：Task11其餘既列seed／StructureAccessor／worker／randomSequences TDD可進行；若實作還需另一個plan外production注入點、驗證腳本或外部JVM observer，仍依11/05裁示先停回報使用者。Task11／12與M6整體未完成，所有retained FAIL原判不變；main合併、tag、PR、人工代驗及工作樹退役仍有獨立門檻。


### 2026-11-06：Task11 frozen root seed／原生生成控制本機與雙審終局，遠端 CI 待

- **code 範圍與功能界線**：純docs locator裁示pin `3ba2e1ac4f3ae3858a9e4f5ae43e295178da1e31` 後，本體 `9ebe351b7afebf290a38fd63fe5a563b9bd8d6d1` 恰13白名單檔；並行world-map I1 fix `232359a9eccf93b7d939c0f05ebe0f400d66146e` 恰6白名單檔，最終tracked clean、未push。schema2 `NEW_M6` 由checked catalog target snapshot／provider resolved options 供長期root seed與GenerateStructures；ServerWorld建構／後續getSeed、StructureAccessor、ChunkGenerating worker及使用者已授權的唯一 `ServerWorld.locateStructure` bool callsite取exact owner，不改全域save properties/registry。新U各有自身持久randomSequences，schema1/canonical照原路；Task10 `DISABLED` catalog到Task12才checked啟用，這片沒有玩家random NEW、portal或Task15遷移。
- **RED／GREEN／突變與保留原判**：owner JUnit具名RED5案3敗→dirtyGREEN5/5；首native錯import root為SETUP FAIL，換nonce取得真原版錯seedRED（預期712345678901，實際saveSeed）；另一真native雙向locator RED：frozen=true/global=false時直接generator可定位但ServerWorld回null、探索圖未填，反向則不應定位卻回位置/填圖。修後 construct/reopen 功能鏈與GameTest GREEN；flat canonical/legacy原測試誤假設有monument，原GameTest FAIL保留，新nonce只把flat定位交normal native fixture，legacy seed/shared sequence斷言仍守。本體九輪production mutation各具名殺且逐byteREVERT；首root-seed mutation因NoSuchElement未採，換nonce補證，原FAIL保留。
- **並行 I1 修正**：原pin `9ebe351` 的 `run-all-2f7d8a7517194859bc69d2e02b7e4151` 自然END ALL0／outer0，只證該pin，不關I1。獨立唯讀審見factory worker讀原生未同步 `LinkedHashMap worlds`、backend本模組可同時發布/卸載第三U，判load-bearing I1（靜態可達，當時未實測錯seed）。fix在Factory短live map身分讀與Backend publish／rollback／unload／failure-retain四寫共用同原生map monitor，鎖內僅Map操作，未發布建構world仍限owning ThreadLocal。具名native monitor+latch RED `M6_WORLD_OWNER_READ_ESCAPED_MONITOR`、JUnit writer RED7案2敗→GREEN7/7；真第三U checked物化/卸載＋兩舊U背景worker兩phase PASS；read/publish/remove三個mutation分別被具名殺並逐byteREVERT。原席窄複審I1 ADDRESSED C0/I0；其他mod未同步改原生map不在此保證。
- **最終同pin gate／雙審**：`232359a9eccf93b7d939c0f05ebe0f400d66146e` 的乾淨雙focused JUnit7/7、GameTest1/1、native `native-seed-functional` construct/reopen各兩輪；另獨立診斷負控 `RequireIndependentSeed` 仍自然 `M6_FUNCTIONAL_RED`，沒有追改Task7。完整 `run-all-a08ab0aec02c49239ff32334cd0b9d74` 自然END ALL0且外層exit0，summarySHA256 `8228208F1231EB909E96944019AEE26D4F20F8D2C33BD424E4A2869CA02B9361`：JUnit1093/0/0 skip2、default179、M5 60、M6 11、main-onlyPASS、recovery24、collapse69、M6 native三chain八phase（DIAGNOSTIC2、catalog FUNCTIONAL4、seed FUNCTIONAL2）、release/sources testmodHits0；五restore exact、2552把session.lock釋放、非daemonJava0，最終ignored indexSHA256 `4EB3AEE67C7955D2148A6641CD84EE9E66C11BC14AF9FF3847B963BE20B0D1BB`。整段fresh spec及獨立quality各C0/I0/M1：同一Minor為Backend.java:38 Javadoc仍寫「不讀catalog」，非load-bearing，交Task12 Step0且已在plan加一行註解白名單，不以舊pin冒稱已修。
- **待辦與已知限制**：`WorldGenerationOwnerAccess` public setter 若由其他mod在本模組安裝前直接放入偽 `GenerationOwner`，canonical可能被錯seed／結構旗標污染；正常原版／本模組路徑無此行為，依11/05「先推主線」裁示不在Task11加事前攔截或專用oracle，列Task8-H H6於Task21後triage，不宣稱已防護。此code尚未正常push或讀回同SHA Ubuntu／Windows雙平台CI；前純docs `bf1d2ef` run `42166764970` success不採Task11 code。五份tracked docs truth已獨立窄審C0/I0/M0；commit／push及同SHA雙job成功後才結Task11，下一片Task12；全部舊FAIL原判不變，main合併、tag、PR、人工代驗與工作樹退役各守門檻。


### 2026-11-06：Task11 遠端雙平台採計，下一片 Task12

- Task11 最終 code pin `232359a9eccf93b7d939c0f05ebe0f400d66146e` 的本機 gate、雙審與原FAIL見上節。含此code及五份docs truth的feature HEAD `3452e311058647aa8998436d72cac26612156a2c` 已正常push、ls-remote讀回同SHA；GitHub Build run `42195022397` 同SHA completed/success，Ubuntu `build`、Windows `windows-native`雙job成功。雙平台M6 JUnit/GameTest、Windows main-only三層oracle、雙平台M6精確集合／隔離、Windows原生案例與雙平台證據上傳步驟皆success；三artifact清單 `quantumchamber-native-windows`、`quantumchamber-tests-ubuntu`、`quantumchamber-jars` 已讀未下載解壓raw audit。Task11自動驗證與雙審門檻據此結案，不把前純docs run `42166764970` 冒稱Task11 codeCI。
- Task12唯讀預查已核：原plan可作單一切片，Step0僅Backend.java:38 Javadoc一行（schema2物化前重核checked catalog、schema1原路）；正式policy1新session凍結、NEW-only lazy source snapshot、checked COLLAPSING→allocation2→catalog2→checked EAGER_ENABLED→物化/ACTIVE/discovery、`intent-allocation`與`partial-overworld`兩跨JVM功能鏈及Windows native CI皆屬本片。預查未發現必需plan外production注入點、新驗證腳本或外部JVM observer；若實作遇到，仍依使用者11/05裁示停下回報。本四份閉幕docs truth獨立窄審C0/I0/M0；本輪只完成Task11閉幕純docs，待commit/push，Task12程式未啟、玩家random NEW尚不可玩。
- 原錯importSETUP、錯seed／locator雙向RED、flat GameTest FAIL、首root-seed mutation NoSuch、Task10舊INTERRUPTED/FAIL及所有retained FAIL保原判；8-H H5/H6仍待Task21後triage，沒有waiver。main合併、tag、PR、人工代驗及worktree退役另守門檻。


### 2026-11-07：Task12 safety 計數、兩個後續 FAIL 與使用者補充裁示

- 使用者同意既有 `m6-harness-safety-check.ps1` 的成功情境預期列數11→21及腳本註解／README舊數字窄補；原 `run-all-7c31dca7f9794afab9e9ac665ef30b27` 在前四 GameTest scope PASS 後因父端舊11拒 child 真21列而整體 FAIL、無 `END ALL`，永久保留。純plan `b9873e41a423c40139ef1c69f0d49dea67723218` 獨審C0/I0/M0後採用，兩檔 code `c20484ffdbc76c6d05ef691789b4b5b049fa011e` 已正常push且ls-remote讀回同SHA；mutation-only21→20被父端具名殺並逐位元REVERT，正控新root RootSuccess21／StartFailure1／None21 PASS、noRuntime/worldOps0，獨立spec／quality各C0/I0/M0。這只結清計數窄補，不代表 Task12 結案。
- 新pin完整 `run-all-7540cc9cbde04746a8840b4d06de7571`：default166、normal13、legacy1、M5 60、M6 17、safety/main/main-oracle/M3各自自然PASS、五world exact restore；M4 recovery `select` 真358候選後 `recover` 在舊 testmod `M4CandidateRecoveryProbe.java:147` 用無 server 綁定 `fromNbt` 讀 schema5 receipt，故 `M6 snapshot 缺少 server-bound validator` 自然 FAIL、無 `END ALL`。正式 `get(server)` 在同 phase 先成功，journal 前後SHA256同為 `1ee9aa67513eb1a07c5240b4fd08365ad143cd41814c61596d06e36a7b2da9f0`；目前只支持 probe 不相容，不能以此宣稱 journal 全面正確。原root/原判保留；post HEAD clean、非daemon Java owner0、相關locks可獨占。ignored 兩既有testmod檔窄設計初審I1/M1，修訂複審C0/I0/M0；tracked plan轉錄獨審C0/I0/M0，程式尚未修。
- 同SHA GitHub Build run `42313448722` completed/failure：Ubuntu `build`、Windows `m6-native` success，Windows `windows-native` 五 GameTest／main-only步驟success，但原生案例核對 `M4_TEST_PRESET_MISMATCH：m5 marker 必須恰一` FAIL。Windows M5 step console 23:58:44有正確唯一六欄 marker，下載的M5 `latest.log` 00:00起無marker、SHA256 `4E853D40791488304118C23D35BB644EFD0D9BCA370B0AA401B1DF4033A0095F`；Log4j日期gzip輪替與workflow只讀latest支持跨午夜來源假說，舊artifact未上傳gzip，故不聲稱已核該次gzip bytes。CI原判永保。ignored現有workflow/ci-rule窄設計初審I2/M2，修訂與檔名負控複審C0/I0/M0；tracked plan轉錄獨審C0/I0/M0，workflow尚未修。
- 使用者補充裁示原話：「已有 code commit 就正常 push，不必等 task 結案。」以及「第 6 點只管『新增』的 production mixin 注入點、驗證腳本或外部 JVM 觀測程式。修改現有檔案（含常數、計數、註解、README），即使超出原白名單，也只需記進 plan 並照常審查，不必問我。」本次兩個既有檔案窄補依此記 plan／獨審後實作，不再重問；正常push不追認任何FAIL，也不授權force push、PR、main合併、tag、人工代驗或worktree退役。Task12未結，Task13–21與8-H仍待。

### 2026-11-07：Task12 續發 pending 與 gzip framing 設計

- M4 recovery reader WIP 新 nonce `37206dfb527847e4b74cc1948bdb2639` 前19 phase PASS，`readback-fault` 自然 FAIL、無正常 final/manifest；正式磁碟 schema5 exactOwner／exactValidator 與前後同 SHA 通過，但舊 probe:326 要同 process 寫 checked `RETURNING+SELECTABLE`，和 Task9 已採的 exact attempt pending 停寫契約衝突。首次 `JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED` 實際來自 `CandidateDoorInteraction.failSession → CorridorPageManager.fail → journal.flush`，其後同 instance 應只得 `JOURNAL_PENDING_RESTART_REQUIRED`；不得為舊斷言改 production 或把磁碟已寫入的 `MEASURED+SELECTED` 冒稱 checked flushed。原root／原判永久保留；13把 session.lock 已釋放、非daemon owner0。
- ignored `task12-m4-pending-readback-addendum.md` 初審 I1/M1、二審 I1，第三輪依實際首次 flush 順序改正後獨立複審 C0/I0/M0。白名單擴到既有 probe、`m4-recovery-probe.ps1`、`run-all.ps1`、README；`readback-fault` 專用預期停寫 oracle，接既有 `recover→verify`，全輪24→25 phase，其餘24仍需正常 final PASS。原 M4 reader與EOF addendum各已獨審 C0/I0/M0；本節僅為設計採用，pending 流程新實作／新 nonce尚待。
- CI 受控 `ci-t12-truncated-red-692440d94f3c4152b1f496f8a62104c7`：gzip 缺8-byte trailer，.NET `GZipStream` 卻產明文使 workflow意外PASS，原oracle RED／原判永保。ignored gzip v1 CRC/ISIZE單獨方案獨審 I1不可採，v2 單位元組方案獨審 I2不可採；v3 `DeflateStream.Read` 受控同步來源、精確單 member 尾端／CRC／ISIZE，加壓縮16MiB與展開64MiB上限，隔離58格及真Log4j片段實驗通過，獨立複審 C0/I0/M1 可採設計；M1 要求上限入tracked plan/README並在雙平台新CI核真實日誌。本輪已轉錄草稿，正式 workflow gzip 修正尚未實作／驗收，不把隔離實驗當CI PASS。
- c8991b2 docs採用後現有4個tracked source／workflow WIP由單一實作者暫停，沒有stage、code commit或push；本輪新增的plan／README與本節note依獨審結果作純docs commit，程式修正仍待。先前本機full7540與遠端run42313448722仍為FAIL，Task12未結；Task13–21與8-H未開始。

### 2026-11-07：Task12 a5bba05 乾淨回歸與雙平台 CI 的續發停點

- 七檔 test／harness code pin `a5bba05178cfc5cae7be8da04355ae848a907b87` 已正常push、ls-remote讀回同SHA。提交前 M4 recovery 25phase、三鏈10phase、A–H mutation逐位元REVERT、CI5正／28負與gzip guard mutation有 ignored 原件；乾淨同pin兩輪M4 recovery `recovery-run-ee7e64251d954f8e8d92c8602880f7ba.json` 與 `recovery-run-6e716740073145dda0665ec35022c0e2.json` 各自然PASS25/25與25獨立JVM，owner0/locks釋放。獨立spec C0/I0/M0、quality C0/I0/M1；M1為本機 `ci-rule-check.ps1` 在嚴格16/64MiB workflow oracle前先經既有 `Read-GateRuntimeLog` 無界展開惡性gzip可能耗記憶體，未證假綠，列為後續harness限制，不在本片擴驗證框架。
- 乾淨full `run-all-76d18e09506f43a29dcb672eb8b7a198`：preflight21、default166/normal13/legacy1/M5 60/M6 17各scope PASS且五world exact restore，safety/main/main-only/M3與M4 recovery25亦各自然PASS；M5 collapse首鏈 `w1-crash` 預期HALT卻 `final.json status=FAIL`，首錯 `首次 schema5 必須完成 M5 初始化且沒有其他未確認寫入`，整體FAIL／無END ALL。原root/原判永久保留；post同HEAD/porcelain空、非daemonowner0、w1 session.lock可獨占。stdout stack精確到 `SessionRecoveryState.writtenSchema:789 → M5CollapseDiskEvidence.write:184 → M5CollapseFixture.recordReceipt:493`：正式選門已成功，testmod旁證另建未綁server的暫時State寫M6 record才拒，不能為此放寬production。失敗後正式journal唯讀重算SHA256 `0d0f6e60c32957030e033bbf3206c1eb37a2aab0a82324e239cc0994fdbd947d`、schema5與恰一筆 `MEASURED+SELECTED`，只證停止後此root，不冒失敗當刻/其它M5鏈。
- 同SHA GitHub Build run `42327535426` completed/failure：Ubuntu build success；Windows `m6-native` 在 `partial-overworld/restart` 以 `PARTIAL_RAW_MCA_TERRAIN` 首敗（前14phase PASS，失敗phase無正常final，首live差值未記）；Windows `windows-native` 在M5 GameTest唯一 `projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates` 失敗，13 witness僅 `wireStillThere=false`，投影WRITTEN 330格/mismatched0、約4 tick後線消失，後續M6/main-only未執行。原CI永FAIL，不能用本機M5 60/60改判。partial halt前 `r.0.0.mca` SHA與下載artifact相同、唯讀48個raw block與partial receipt相符，但restart失配坐標/live值缺，尚不能判功能或oracle根因。
- 上述三停點各有 ignored 窄設計及勘誤獨立複審 C0/I0/M0；tracked plan轉錄已獨立窄審C0/I0，純docs commit待；程式尚未實作。M5旁證需在新nonce strict server-bound讀正式journal、量當刻單row／多row並保原摘要，runtime journalRecord與restart receipt讀者同步相容schema5、W10/W11 pre-start schema3路不降；Windows M5 wire僅先做受控延後狀態診斷，不預判fixture/production；M6 partial先保首差raw/live/hash與owner到durable FAIL receipt，不改原失敗判定。任一新根因仍需新root、mutation、雙focused/full、雙審與同SHA新CI。Task12未結，Task13–21及8-H仍待，main合併/tag/PR/人工代驗/worktree退役另守門檻。
- M5 旁證 implementer 於改碼前唯讀停門：`M5CollapseDiskEvidence.summary:312 → journalRecord:320 → journal:105 → fromNbt:110` 的 runtime call graph 證 `M5CollapsePassage:543`、`M5CollapseRevisitChains:142,231`、`M5CollapseInitChain:366` 仍會以無綁定 reader 讀 schema5；InitChain:170,187,209 pre-start 歷史schema3/4須保 path-only。本次只將上述三個既有檔的 runtime 顯式server呼叫點轉入plan白名單並送獨審，尚未實作/跑gate；使用者11/07裁示現有檔改動只需記plan照常審查。

### 2026-11-07：M5 旁證 W1 GREEN／W10 多row續發 FAIL（皆未提交 WIP）

- 純docs HEAD `e987463482acf9a57e2f4b9f04a1662cfdd15182` 後，M5旁證實作者六個既有testmod檔WIP已通過M6Compile root `986333a08410458f9bb5999708134334`；W1新root `bad5e375e3a2436e994330003cb7c7b6` 真 HALT77／獨立JVM restart STOP0，當刻正式schema5恰一row、來源與旁證SHA同 `55bc2304dd2df6b44bdc446910eaba19f8c464837348bd90aacddaf56b65775b`。這只算WIP W1 focused，未作本片mutation、完整M5 69、乾淨新pin gate或CI。
- W10新root `cf5d8fdb395f4a0db76eb3c9385872b4-w10` 在 `recordReceipt` 的 `receiptSingleRow` 具名FAIL：正式schema5三row（目標NEW、Nether SOURCE、End）、來源SHA `6261143a3195c5fea490099bf13221dfbae15dc0379d35cee766dd7a96f8a5b0`；未產receipt／HALT，原root/判永久保留。W11因runner fail-fast未跑。post非daemonowner0、兩新root locks可獨占，六檔WIP未commit/push；不能把W1單row外推W10。
- ignored多row旁證原設計初審I1、另寫不改原稿的補充後獨立複審C0/I0/M0：正式同snapshot嚴格取唯一目標row，保原NBT內容、受控單row重包並strict server-bound讀回，compressed bytes另算SHA；此片只驗旁證，W10後續舊 `writeBatch`／schema3及其restart無綁定batch reader新首錯另留root/設計，不冒W10整鏈PASS。tracked plan已完成獨立窄審C0/I0，純docs commit待。歷史M4 schema3 Nether／End收據應取真舊save，不能把新policy1 M6 session事後塞回M4 context；M1–M4只讀備份合格原件仍在盤點。

### 2026-11-07：M5 旁證 partial code 與 W10 舊測試式 producer 路線

- 多row旁證 partial code `b406cf6282800ae3a9f49c35c2f68a015993fbd6` 恰六授權testmod檔，已正常push並ls-remote讀回同SHA，post porcelain空、非daemonowner0/2673 locks釋放。提交前W1/W11六phasePASS、W10新root `d05a2e9cc5cd414bb9a1e326756c6df8` 的三row→單row八項旁證oracle GREEN後舊 `writeBatch` 自然FAIL，八mutation具名殺/逐位元REVERT，索引SHA `ea8a539458eaa46b9218bc424830031fba9cd5b96a3852057b1f56b716ce1254`；原W10各FAIL保判。乾淨同pin兩輪 `collapse-run-1ba3912d99cc472f88388059f039c940.json` 與 `collapse-run-620126a626c5415682227eff3849d96a.json` 各自然PASS6phase/6獨立JVM、W1真HALT77→restart0及W11歷史prestart四phasePASS，兩root locks釋放。獨立spec C0/I0/M0、quality C0/I0/M2：M1 testmod snapshot先readAllBytes，僅能宣稱schema5解碼NBT 16MiB有界，不能宣稱整體壓縮輸入記憶體有界；M2 CREATE_NEW後若再驗失敗，旁證檔保留在FAIL root且必標未採計，不可把失敗原件刪掉或當成功receipt。兩Minor列後續harness限制，不在本片擴框架；M5 69/full/CI/Task12均未過。
- W10後段新首錯stack為 `SessionRecoveryState.writtenSchema:789 → M5CollapseDiskEvidence.write:267 → writeJournal:254 → M5CollapseUnsupportedChain.writeBatch:315`；當時真新policy1三筆schema5，之後的schema3手寫也會遭 `SessionRecoveryRecord.toNbt(3)` 拒M6 Context。不可為過舊測試把新session事後改寫M4 Context、放寬production或刪W10。外部M1–M4備份唯讀盤點403份journal均符manifest，391份可解析共542row皆OVERWORLD，無同save Nether／End三筆合格來源；此是靜態NBT盤點，不冒正式reader。原M5 spec §12只明定schema3 fixture與SOURCE消費，歷史W10的SOURCE／End本來由testmod合成；『三筆皆真舊M4玩家正式producer』是更強新目標，不能說合成fixture自動違反原spec。
- ignored W10選項原稿初審I1/M1、驗收口徑勘誤後複審I1/M1、匯入流程v2複審C0/I0/M1。tracked plan本節提**固定m5 tag原W10測試式producer→現版consumer**可達性spike，尚未建立舊checkout／跑舊HALT或跨版本restart；controller擬用不存在的 `.worktrees/m6-w10-m5-producer` detached checkout，保留至M6收尾、不在spike後刪。先核HALT_ONLY與source world/playerdata/journal/evidence全檔hash、owner0/lock，再保舊nonce CREATE_NEW匯入新owned root、bridge manifest分列兩code/patch pin，現版RESTART_ONLY_IMPORT必核schema3三筆與原W10全部行為；若既有runner不能安全承載，留FAIL另審。若須新增驗證腳本／外部JVM observer或縮W10明文驗收，按11／07裁示再呈具體方案請使用者決定。本段已獨立窄審C0/I0，待純docs commit；spike尚未執行，原本機full76d18e、CI42327535426與W10諸root均維持FAIL。

### 2026-11-07：W10 舊版 HALT_ONLY 原件與兩階段匯入勘誤

- 固定舊 checkout `.worktrees/m6-w10-m5-producer` 以 detached `m5` peeled `a2d2b404329d2574469fca064a663e21fe89f333`、僅既有runner v2 patch dirty，真新nonce `625a9f816af14087bc50e66896bd137b-w10` 只跑 `w10-crash`：`HALT_ONLY/completeChain=false`、JVM77/Gradle1/外層0、`M4_RECEIPT_STEP` hit1/failures0，沒有restart/final。producer.json SHA256 `CE944EC478FFD3F1D83C495963CEFFA4FF89940D2E8D6974382121217B049EDB`；manifest63檔大小／SHA全符、source lock可獨占、owner0。獨立原件審C0/I0/M1，純Python NBT只證來源schema3三筆 m4_v1／MEASURED+SELECTED（Nether NEW、Nether SOURCE、End NEW）及world/playerdata/level.dat存在，不能代現版正式strict reader。這是舊M5測試式producer，並非三筆皆真舊M4玩家checked；舊W10 FAIL及HALT_ONLY分列，不冒整鏈PASS。
- 兩checkout的runner v2 WIP均僅改 `m5-collapse-probe.ps1`，bytes SHA256 `6036C012B890080DFD15168B6E90D42856698B062BAA2405BBB44B38F3E7FE7E`；安全靜態審C0/I0/M1只放行舊producer。Get-W10Pin內部LF無末換行diff SHA `54993C33BE32E74FEF571804C91E527C912C721440F8E7B17FEDD3115EA01670`，保存patch檔含末LF SHA `87019F483ED40F95256300CA4F9318E64873F52414AAC775930D077C66A0CE3C`，兩口徑不能互冒。獨立真producer原件審只准controller**匯入**；現有v2 `-ImportW10Crash` 會匯入後立即啟restart，故未執行它、M6目的root/evidence仍不存在。
- ignored `task12-w10-two-stage-import-addendum.md` 獨立審C0/I0/M1，推薦保舊v2producer原件、只升現版runner v3為 `ImportOnly` 與另次 `ResumeImportedW10`；source固定v2 SHA/manifest/nonce allowlist，consumer v3 pin另記，匯入後bridge完整SHA由獨立審查釘選才可resume。v3尚未改碼/commit，ImportOnly、現版strict schema3 restart也未跑；tracked plan轉錄已獨立窄審C0/I0，純docs commit待。成功結果須最後才寫，不能先標橋接PASS再核source、owner或lock。此流程只在已審路徑／舊nonce新目標上運作，不刪舊checkout/world；若中途FAIL，保部分root與原判，不在原root改跑。Task12及M6整體未結。

### 2026-11-07：W10 跨版 spike 原件審與可重現 full69 設計

- 現版 `218945bf42c567a6ca047ad2d96e4437c86a0f86` 的單次 W10 bridge：舊 `m5` producer `HALT_ONLY` PID5208/JVM77，import `IMPORTED_ONLY` bridge SHA `DEC8CF9E1A3EE157D12672116168D52C62AB3FDFE9FE41CF9616D40D217745C7`，現版新JVM restart PID45252/STOP0。`bridge-result.json` SHA `BDA92E263AECCA4D704677C6A0B3DD65AD54DAC7BCDE116A9C34D64AE9271DDE`；獨立唯讀結果審 C0/I0/M1：final PASS/failures0、37項 observed全true（先前目視38已更正），schema3三row、NEW取消/SOURCE完成、零配置／不重抽、舊source63/63未變、copied crash11/11同bytes、兩lock獨占、owner0。這只證此固定nonce的**spike**，`completeChain=false`，不能重用已消費root或稱M5 69/full/CI/Task12結案。原W10/full76d18e/CI42327535426 FAIL原判仍保。
- ignored `task12-w10-full-integration-design.md` 初審 C0/I0/M3，另寫不改原稿的 `task12-w10-full-integration-addendum.md` 後複審 C0/I0/M0；本輪 tracked plan 已獨立窄審C0/I0，純docs commit待。方案固定舊v2 runner每輪新nonce，新增一份**非可執行**tracked patch資料 `scripts/verification/m5-w10-producer-v2.patch` 供新機重建舊checkout，舊m5 code/tag/runner三pin各核；run-all在第一個world前強制W10唯讀preflight。父runner依序HALT_ONLY→ImportOnly→與writer不同實作的 `AUTOMATED_PRESTART` 磁碟validator→單次Resume，最後把舊source HALT與現版dest STOP兩row納原69精確集合；run-all再獨立核雙pin／雙root／原halt/世界hash。這個受審自動oracle是**擬取代spike逐輪人工審的新增契約**，不是已驗行為。遠端CI不跑M5跨JVM，後續成功也只能證其實際scope；如需新驗證腳本／外部JVM observer或縮W10明文驗收，仍依11／07裁示另呈具體方案。
- 兩檔純診斷 code `eba4530f86117783342a54db4ea62e354c647a75` 已正常push、ls-remote讀回同SHA；乾淨同pin M5 gate60/60／catalog delta60／suite-end恰一／world exact restore。M6 partial新乾淨root `b5b4a38362144e52bdd9d39c2d08dd75` 再自然 `PARTIAL_RAW_MCA_TERRAIN` FAIL，首差(0,32,0) raw granite/live diorite、biome deep_frozen_ocean同、seed/owner同、MCA SHA `4f8c0c3e859dd7369f1733314718f31c89bdd521c8f14a5bb6334ea8f1c27568` 與partial相同且讀取穩；原c444首差(0,0,15) raw andesite/live diorite另保FAIL。兩處靠chunk邊界僅支持相鄰生成假說，尚未證明；第二輪loaded-only時序診斷原設計+勘誤獨審C0/I0/M0，尚未轉plan／實作。兩檔spec review C0/I0/M1（partial PASS缺raw chunk Status），quality C0/I0/M1（partial48格逐格重解raw biome增加同步IO）；下輪設計須守原48格比較並處理這兩Minor。完整run-all與同SHA遠端CI未因此通過。
- W10 tracked patch bytes 的新機重建前置：此Windows工作樹 `git config core.autocrlf=true`，若不指定屬性，`.patch` 可能checkout成CRLF而不再符合原保存檔SHA。plan白名單窄加既有 `.gitattributes` 的唯一 `/scripts/verification/m5-w10-producer-v2.patch text eol=lf`，不更動其他檔案換行；實作後須以clean checkout重讀patch工作檔SHA、tag原blob、套後runnerSHA與內部patchSHA，不能只驗當下工作檔。此規則轉錄已獨立窄審C0/I0，純docs commit待；尚未改 `.gitattributes` 或建立tracked patch。

### 2026-11-07：W10 patch 空白提交停門與 eba4530 遠端 CI 原判

- W10 full整合五檔 WIP（既有 runner／run-all／README／`.gitattributes`、新非可執行patch）已做 AST／合成正反控；尚未啟真新nonce runtime、commit或push。原patch20,330 bytes／251 LF／0 CR／末LF、SHA256 `87019F483ED40F95256300CA4F9318E64873F52414AAC775930D077C66A0CE3C` 必逐位元保留；stage後原樣 `git diff --cached --check` 非零，三個 `+ ` 行被判 trailing whitespace。不能以臨時 `core.whitespace=-blank-at-eol` 的exit0冒正式gate PASS、不能改patch bytes。ignored單一路徑勘誤原稿審C0/I0/M1、澄清後複審C0/I0/M0；tracked plan本節窄加 `.gitattributes` 原規則的 `whitespace=-blank-at-eol`，只對patch忽略該固有行尾空白，保其它檔原檢查與patchLF/hash。此設計轉錄已獨立窄審C0/I0，純docs commit待；`.gitattributes` WIP尚未補此屬性，plain staged gate仍FAIL，實作者已停提交／真runtime。
- `eba4530f86117783342a54db4ea62e354c647a75` 的同SHA GitHub Build run `42348162531` completed/failure：Windows `m6-native` success；Ubuntu M5 GameTest60案2FAIL，兩案在COLLAPSING／PREPARE的400tick等待內沒有目的預載完成紀錄，不能把第二案等待文句當已寫PENDING；Windows default首步30分鐘timeout且default XML缺，後續normal13/13及M5 60/60 success、M6/main-only skipped。前一run `42344234930` default也30分鐘timeout，但首X與末batch不同（舊76/166結束、新29/166），不能由兩次timeout判同因。本輪Ubuntu失敗原件ignored診斷SHA `BF00A7BB653AA1EE19F4384754F45A0F868EB2A4706D312F2AFAFBBA63517CFD`；Windows新診斷報告SHA `B57B8E9C8C490D053E3C75F5A34220E4DC58D1E2B96CFACCF2901B6C217730BD`。三個CI原判仍FAIL，不用本機M5 60/60或Windows m6-native success抵銷。M6 partial同pin兩個自然FAIL首差 raw/live 不同且落chunk邊界，鄰FEATURES跨界寫仍未證；第二輪loaded-only timing設計原稿＋勘誤獨審C0/I0/M0、尚未實作或改原terrain oracle。Task12未結，Task13–21與8-H仍待。

### 2026-11-07：W10 full 整合首個清 pin 的程序路徑 SETUP_FAIL

- 五檔 code pin `19f0902b62afff1d79f87cfdb52826b1bec8b725` 已正常push、ls-remote同SHA；pre-run獨立安全審C0/I0/M1、原樣 staged/unstaged diff-check與patchLF/SHA、合成STOP負控已通，但這些不代真 W10/full。清pin `m5-collapse-probe.ps1 -Only w10` 新runId `b561cf122a4e458397379ef338620a60` 在舊producer ProcessStartInfo.Start 前自然SETUP_FAIL／outer1：`Get-Command pwsh -CommandType Application` 回本機runtime和WindowsApps兩個Source，FileName被合成兩路徑。controller補 ignored `controller-setup-failure.json` SHA256 `3E16ACD5CA9F6D5867E47D2C85F17F43D406AB8FDA07C65C88703BE1444D61E3`；舊新nonce root/evidence與現版dest均未建，post同HEAD clean/owner0、原已驗625a source/patch不動。原root/原判永久保留，不能說W10 HALT/full69成功。
- 獨立spec review判本次新增子程序路徑 I1（其餘未見I），窄設計原稿初審I1/M1、另寫不改原稿的祖先非reparse/純路徑負控補充後複審C0/I0/M0。tracked plan本節補既有runner:419單一路徑 `$PSHOME/pwsh.exe`、`Assert-W10Path` 全祖先＋Leaf、任何錯具名 `W10_PWSH_UNAVAILABLE`，不以舊expression跑負控而意外啟世界；此設計轉錄已獨立窄審C0/I0，純docs commit待；程式尚未修改。新code pin與新nonce必另跑 PreflightOnly／W10完整橋接及後續full、CI；所有前FAIL保原判，Task12未結。

### 2026-11-07：W10 舊 producer 重導輸出 UTF-8 pin 漂移

- `dfed554922af46ec290fd753d9691557344aedb9` 的新 W10 父runId `b9ff1077ee6449e5a4510105dc9fee3d` 已越過先前pwsh啟動SETUP_FAIL，舊checkout新nonce `ff247e8553a646e787bba2e945d48014-w10` 真產 `HALT_ONLY`、`producer.json` SHA `43604D45748E01BE8262428BBCCB92F546F467A49B82CE2C9071AAABC5FD45FD`；父在 `W10_SOURCE_PIN` 自然FAIL／outer1，現版dest未建。兩次舊runnerbytes SHA均為v2 `6036C012B890080DFD15168B6E90D42856698B062BAA2405BBB44B38F3E7FE7E`，新producer內部diff SHA卻 `904C97FFEAD03465E880556838CD8D2C946228983ABAE77FD8BAF6DBDD7B383F`，已審應為 `54993C33BE32E74FEF571804C91E527C912C721440F8E7B17FEDD3115EA01670`；父拒絕正確，不為趕進度放寬allowlist。controller另建 ignored `controller-source-pin-failure.json` SHA `8ED7302B53D5B3C88EA95ED4B8381A2666EED247A800D5C77694BD167A0425FB`；原新root/原判保留，postfeature同HEAD clean、非daemonowner0、新source鎖可獨占，舊625a來源63檔不動。
- 純PowerShell隔離實驗中有rawGitSha記錄的組別證 Git diff 原raw bytes20,330/SHA87019...相同；新子程序因重導 stdout 無console，PowerShell以CP950解Git UTF8中文context，才算904C；在子程序啟動前設 `[Console]::OutputEncoding=UTF8` 即回54993，單設 `$OutputEncoding` 或 `chcp 65001` 無效。ignored原設計獨審C0/I0/M1，另寫wrapper exit語意補充後複審C0/I0/M0；tracked plan本節轉錄已獨立窄審C0/I0，純docs commit待；僅准現版既有runner父子shell由`-File`改固定`-EncodedCommand`（ArgumentList仍結構化）、先設UTF8與ErrorActionPreference Stop，舊runner正常return即outer0、throw/Write-Error outer非0；純無世界stub三反控先行。此處未修改程式、未重跑ff247或b9ff。後續clean新pin/新nonce W10、M5 69/full/CI各自守門，Task12未結。

### 2026-11-08：Task12 M5 wire 增量、同 pin 回歸與尚未關閉的門檻

- 純 plan commit `afef8fe8dfe7c646d6ae4e65c7d8a7d35840dab5` 採 Task12 M5 wire fixture 窄片，獨立設計審 C0/I0/M1，M1 已在 plan 補明放線後／writer 前 UP/N/S/W 非流體。code pin `5c7edc99097e8b2e94ad69b76d2c661b1764b78f` 只改既有 `M5UniverseProjectionGameTests.java`，已正常 push 且遠端讀回同 SHA；先等既存 FULL／block ticking，再在原單一 200 tick 截止內建外側阻流袋，fixture 不穩定於 writer 前具名失敗，原 343 格／無掉落／observer／wire 與後四 tick 斷言保留。獨立 spec、quality 程式各 C0/I0；乾淨同 pin M5 單案兩根 `gate-t12-wire-clean-a-5ba710f0863743ac897d7d2786f74357`、`gate-t12-wire-clean-b-2056876bade447c4918eeb7e420c9611` 各 1/1 PASS、世界 exact restore。初次誤用只接受 M6 batch 的 `m6-focused.ps1` 得前置 `M6_SCOPE_MISMATCH` exit1、無 gate root，未採計；其後以既有 M5 gate 新 nonce 正驗。
- 負控 B 新根 `gate-t12-wire-mutb-e124a33e-b1e5928f8d474487bbb1f0a086facf71` 在 WRITTEN-343／浮線仍在後送 EAST 鄰格更新，使原 `wireStillThere=false` 具名 FAIL；mutation-only 三行 patch SHA-256 `CCD0E3B105EFCAE73500E13484703D6B4CF58610E0FF1CFC45C8617B17A53431`、gate 前 mutant SHA、候選副本及逐位元 REVERT SHA 已由獨立 spec 窄複審 C0/I0/M0 核畢。前一 B 與 A1／A2 的舊 root 缺當次獨立 mutation patch／revert 原件，不追認。A1／A2 的固定熔岩 source 與原生排程均未在原四 tick 真流入，原測試 PASS，故 A＝`UNRESOLVED`；不稱流體隔離或 wire 修復結案。舊 Ubuntu CI `42358264048`／`42401517613` 的 wire FAIL 仍保留。
- 同 pin 首個本機完整根 `run-all-9dddc0a0642a43fca91af08082b8ae87` 在 full／M5／M6／main／M3 子 gate 通過後，M4 recovery `M4_PENDING_STACK` 因預期兩條 ERROR 外另有 `Yggdrasil Key Fetcher` 公鑰請求 ERROR 而整根 FAIL、無 `END ALL`；原 readback-fault `latest.log` SHA-256 `603992C7B31223546632057402E152E66AEA6485A506E4B64D3F06D5EE48C233`、console SHA-256 `D826C4AE59466778BADE383E56C06E818BFFD4D146160C0EB8F8FA9FB18D3F58`，原判不變。查因後新 nonce `run-all-402db178e3d64e6194630859f1acce24` 自然 `END ALL exit=0`／外層0：default166、normal13、legacy1、M5 60、M6 17、JUnit1100／0 failure／0 error／skip2、M5 collapse69、M6 native15、main-only PASS。summary SHA-256 `BB522C7470583A28FAC7F3A6A8385BF4D115026A0DB209569DD0D0ED7E04051E`、console SHA-256 `AAD74AC7797BC0758B755D526687CB8CF62AEFF6E81B408CD0DE3E9A6833103B`；post HEAD／porcelain乾淨、非daemon Java0、五份世界 exact restore、3052個 session.lock 均可獨占。此本機 PASS 不回判前根 FAIL。
- 同 pin GitHub Actions run `42411190339` 結論 failure：Windows `windows-native` 與 `m6-native` success，Ubuntu `build` 唯一 M5 `prepare_waits_while_another_projection_write_is_unconfirmed` FAIL；原 build job log SHA-256 `DD5A13860FD7E4007CB07B81296A1B15D091DAA62BE80EEF2BF36D7A6033460F`。`prepare_unconfirmed_first` 在 resolved+400 的同次快照：current/flushed COLLAPSING、phase PREPARE、owner MATCH、四張目的票皆 level33，但四格中三格非 FULL，projection ABSENT、分類 `CHUNK_NOT_FULL`；該次新 wire 案 PASS。這只證當刻預載未完成，不證延長期限必成功，也不能將先前 CI 的其他 FAIL 改判。ignored `task12-m5-prepare-prewarm-design.md`（SHA-256 `B42E6BADFB411B9D63877E4496563E2261C782A7A3B720FC1B094877D9102864`）提出精確四格同步預熱，獨立設計窄審 C0/I2/M1：同步 `getChunk` 的 ticket／wall time 風險未封閉，且本案可能不再驗 production 原 400 tick 預載；未採用、未實作，不擴新驗證框架。
- partial-overworld 的多份自然 `PARTIAL_RAW_MCA_TERRAIN` FAIL 仍在；同格原生 Ore 讀前寫入已有當次見證，但不能據此稱唯一根因或改原 48 格 exact oracle。唯讀決策再核 A 的有效 feature/helper/mixin 閉包與 durable status 仍缺、B 會改驗收語意且現 witness coverage 不足；本次本機 native partial 一次 PASS 不抵銷舊 FAIL。Task12／M6 未結、Task13 未開始；若使用者明確允許保留 Task12 FAIL 先行 Task13，先在 ledger 記排序例外，main／tag／人工驗收仍各守門檻。

### 2026-11-08：使用者放行先做 Task13，Task12 原判保留

- 使用者回覆原話：「先實作 T13，T12 保持未結（建議）」。此為本 feature 分支的排序例外，不是 Task12 gate waiver、整體 PASS、main 合併或 tag 授權；Task13 自身具名 RED／GREEN、mutation-only＋REVERT、乾淨雙 focused、full、獨立 spec／quality 與同版 CI 照跑，若整體仍被 Task12 擋住須分列結果。
- 到場 local／origin HEAD 均 `048e8359c9cd61a611f9e43b298ab4b757a54533`、porcelain 空、非 daemon Java owner0。純文件 CI `42419184480` completed/failure：Ubuntu build 與 Windows windows-native 皆在 M5 `prepare_waits_while_another_projection_write_is_unconfirmed` 得 `CHUNK_NOT_FULL`；Ubuntu 本次預載集合2格、1格非 FULL，Windows集合4格、2格非 FULL。Windows 更早的 default GameTest 30分鐘逾時，最後見 `m5_collapse_returned_elsewhere` 開始而無該步具名 XML／根因；`m6-native` success。ignored 原 job log SHA-256：Ubuntu `52BAD9A2A3E0E5AF64C36C9F486E31595A0C7B88BEA60EBE04025DB3C9BA3B6C`、Windows `5F277E633D8E3DE7435D1BB33BB4DFF60E7A73B363162EBDE0B6542BE372B492`。此 docs-only run 不重判先前 code pin CI，也證先前「恰四格」預熱候選不是通用前提；該設計 I2 未採用，Task12 續保 FAIL。
- Task13 唯讀 source 盤點發現原 plan Files 漏三個既有 production strict guards：`CandidatePolicySnapshot` 固定 policy1、`UniverseAllocationLedger.M6Fields` 固定 policy1/random、`UniverseRecord.Generation` 固定 NEW_M6 random/normal。另三個既有測試需擴充：`CandidateJournalCodecTest` 與 `UniverseAllocationLedgerTest` 目前把 policy2 當未知，`UniverseDefinitionTest` 需補 same／amplified catalog 讀回。這六檔已補進 tracked Task13 plan 採用候選，待獨立窄審。`CandidateJournalCodec`、`SessionRecoveryRecord`、`UniverseProfileRegistry` 目前沒有相同硬拒絕，先補測，不預設改 production。same profile 的 canonical id、policy2 雙策略、source/target exact snapshot、舊 policy1 不升版與原始地形成熟度比較門檻見 plan Task13；未新增 production 注入點、驗證腳本或外部 JVM observer。
- 使用者同日補充 T13 期間 CI 三點：先固定 plan Task13 的 T12 具名清單 K1 Ubuntu M5 PREPARE、K2 Windows M5 PREPARE、K3 Windows `m6-native` `partial-overworld/restart` 的 `PARTIAL_RAW_MCA_TERRAIN`；Windows default 30 分鐘 timeout 無具名結果列 `UNKNOWN`，不得算 K1–K3，歷史 wire 與 Yggdrasil 亦非新 CI 容許項。T13 每次 CI 僅容 K1–K3 相同失敗特徵，清單外即停查，不歸給 T12；新增 JUnit／GameTest 必須在 Ubuntu、Windows 各具名 PASS。使用者對平台矩陣澄清：`same-seed-restart` 跨 JVM 原生鏈只在既有 Windows `m6-native` 跑，Ubuntu 只核 `-CheckUnsupportedNative` 拒絕契約、不得當原生鏈 PASS。為讓 M5 已知失敗後仍能觀測 M6 新測項，Task13 plan 允修改既有 workflow 的 M6 steps 條件，不新增腳本。T12 暫停新增診斷；T13 後先交根因結論及修法選項供裁示，含原四格預熱 I1 同步載入副作用／wall time 與 I2 預載覆蓋縮窄的解法，並修正 2／4 格可變前提。

### 2026-11-08 稍後：Task12 測試判定收尾先於 Task13 程式

- 使用者最新裁示：Task12 卡在測試判定沿用平地假設，允許只改測試、不改 production，全部舊 FAIL 原件保留。PREPARE 的400 tick是測試預算而非 production 契約（PREPARE 無期限、600 tick僅 WARN），改在 tickLimit 內等條件，不做預熱；wire 改直接斷言寫入前四周非流體並沿用真殺的負控 B，主世界熔岩30 tick流動使 A 在原四 tick 窗口不可觸發；partial 不再要求半成品48格逐格相同，改比 crash 前已完全生成的區塊並加 seed/noise/biome 確定性查詢。Windows 30分鐘無具名測項的逾時保 `UNKNOWN`，自本裁示後再出現一次才查，不擋上述三項。
- 實作先後改為：先讓 Task13 plan 的排序／白名單／CI 補充經**同一次獨立窄審 C/I0**採用；接著 Task12 三項測試判定只寫**一份**合併設計、做**一次**獨立窄審 C/I0 即實作收尾；最後才實作 Task13。Task12 不再逐項加觀測證據。原「T13先做、T12後交根因選項」為較早裁示，已由本次更具體順序取代。Task13 的 K1–K3 有限失敗清單與兩平台測試矩陣仍保留作歷史原件／CI 分類，不當成最新裁示下可跳過 Task12 收尾的豁免。既有四格預熱設計 I1/I2 未採用，PREPARE 修正不預熱；其預載集合實際2或4格，不能再寫固定四格。

### 2026-11-08：Task12 三項測試判定收尾，自動切片結案

- 使用者最新裁示只准改測試，舊 FAIL 根保判。純 docs `7bbdcdfeeb462129354c5db7e1ca8aa059a14f39` 採 Task13 的排序／白名單／CI 釘選（獨立窄審 C0/I0/M1），純 plan `12a158d2448f1b80824b37d3f90352171ef74b25` 採唯一 Task12 合併測試設計；ignored `task12-test-oracle-closeout-design.md` SHA-256 `3BB5696A17EC38B7E0671578E42E2D692B9CA5877D3DA9882E0F5803F00AACE6` 獨立窄審 C0/I0/M1。code pin `76111e8e0486baeb9c7cf3835e0ab397eab576e0` 僅改 `M5UniverseProjectionGameTests`、`M5UniversePassageGameTests`、`M6RandomProbePhases` 與既有 `m6-universe-probe.ps1`；無 `src/main`／production mixin／build／workflow 差異，已正常 push 且 `ls-remote` 同 SHA。候選 patch SHA-256 `4C9D9173284970FCB2EB5441FBE9214E859BF761813FC3891B06AB6DE7A5FB63`，獨立 spec／quality 各 C0/I0/M1。兩席唯一 Minor：底層16格只能當有限儲存樣本，不是整個 chunk 的自然地形或全部原版 writer 完整證明；新樣本若變動仍 FAIL。
- PREPARE 兩案保持原條件 predicate、後段功能與 cleanup，首段測試截止改為絕對 tick750／780，仍在各自 tickLimit 1200／1500 內；無預熱／新 ticket。existing passage 的 sid1、sid2 各至多允一則精確600tick PREPARE載入等待 WARN，其他 WARN／ERROR 仍拒。wire 在 writer 前直接核 UP/N/S/W 四格非流體，失敗時不呼 writer；原343格、無掉落、observer、浮線及後四tick斷言保留。舊熔岩 A1/A2 root 的 `UNRESOLVED` 原判不變；依使用者「主世界熔岩30tick、原四tick無法觸發」裁示，本輪標 `NOT_TRIGGERABLE_IN_4_TICK_WINDOW` 而非追認 killed，以新流體前置殺手及原 B 負控證敏感度。
- partial 在原正式玩家／checked intent→allocation→catalog→EAGER_ENABLED、MATERIALIZING／未ACTIVE／discovery0 的 LOAD crash 窗口，原生請求 C=(0,0) FULL 一格、flush，讀回 C raw status／座標／原manifest及 y=-64 固定16格 bedrock；restart先驗保存的 raw C FULL 再載入同C核16格。原半成品石種48格逐格相同的門檻撤銷、舊 `PARTIAL_RAW_MCA_TERRAIN` 失敗全保判；新增從 checked frozen target／root 生成獨立 expected 的原生 seed、noise finalDensity raw bits、biome key、structure placement/locator/GenerateStructures 驗證，不能用實際錯設定自證。Java/PowerShell 的欄位／manifest／phase/身份一起同步；未新增驗證腳本或外部 JVM observer。
- precommit 候選 M5 三案focused 3/3 PASS、partial四phase PASS；首M5 batch拼字錯的 SETUP FAIL、partial首兩根 b20e／75c FAIL、首 B 採集器漏 XML 的 `killed=false`、首WARN錯logger `NOT_KILLED` 原件保留。隨後 750／780 期限、wire前流體與 B、真capture ERROR、partial樣本／seed／settings 各有具名 mutation-only patch、gate前 SHA、真殺及逐位元 REVERT；WARN21、partial13 純判定器矩陣各自具名通過，不冒成每列真runtime殺手。最終候選 patch 另經 controller重輸出 `git diff` 同SHA核對，PS誤加BOM已只刪前三byte且 bodyByteEqual／parse／13列矩陣重驗通過。
- **乾淨 code pin 驗證：** M5 三案兩根 `gate-t12oracle-clean-m5-a-f51fbfae77824179a37126ae4cc7e1f2` 與 `gate-t12oracle-clean-m5-b-9545bcf5b4aa4ada873da04ced6af0ec` 各 scope3/3 PASS、world exact restore；`partial-overworld` 兩根 `m6-native-run-dfe4f9fc18d64f7fbcfc65ce61090742.json`、`m6-native-run-191777b749d64436b6dff0fd7f21cd50.json` 各 intent/catalog/partial/restart 四 phase PASS。完整 `run-all-7c079c8613c54067a5b5094bc62823fa` 自然 `END ALL exit=0`／外層0，summary SHA-256 `7A345D621A422BA639EA8E4DB27868FB558BC893288EC7D43762EC7B9C0860D9`、console SHA-256 `8899052515C3EACD3AC61A2087E2275D843E55AA274DBC7A990E601C20F9F3CC`；default166／normal13／legacy1／M5 60／M6 17／JUnit1100／0 fail／0 error／skip2、M5 collapse69、M6 native15、main-only PASS。五份世界 exact restore，post HEAD／porcelain空、非 daemon Java0、3116個session.lock均可獨占。
- 同 SHA GitHub Actions run `42436349061` completed/success：Ubuntu `build`、Windows `windows-native`、Windows `m6-native` 三job及 M5／M6／原生精確驗證、證據上傳關鍵步驟success；`quantumchamber-native-windows`、`quantumchamber-tests-ubuntu`、`quantumchamber-m6-native-windows`、`quantumchamber-jars` 四artifact名稱讀回、未下載解壓 raw audit。使用者最新裁示後 Windows 無新30分鐘 UNKNOWN timeout。先前 CI `42411190339`／`42419184480` 及所有 retained FAIL／NOT_KILLED 原判仍是 FAIL／UNKNOWN，不能用此run追認。Task12只完成**自動**切片驗收；人工 smoke、Task13–21、M6整體、main合併與tag都未完成。按已採用 plan 下一步直接 Task13 policy2／same-seed fresh。

### 2026-11-08：Task13 policy2 候選、七鏈原生 PASS 與 U→U 全量地形未結

- 已採 Task13 既有檔窄補：`1afd4d1` 加 `run-all.ps1`／README 白名單（獨審 C0/I0/M0），`c2e5b10` 加 `M6RandomProbePhases.java` 舊 random 鏈 policy2 適配（獨審 C0/I0/M1），`2e96891` 將 canonical normal／amplified 分成兩條獨立 owned root、各 source/new/restart 真玩家三 JVM（設計初 C0/I0/M1、M1 窄複審 C0/I0/M0），`3fadba0` 加同 JVM U→U GameTest 九格 virgin／actual consumer 前置（獨審 C0/I0），`5e9dc8f` 加四階段只讀方塊差定位（初 I1/M1，修後窄複審 C0/I0/M0）。截至本停點提交前，以上純 plan commit 都只在本機，remote feature為 `f77f9d608206bd61c938cf08b320f8eb17fb83ec`；Task13 程式與 testmod WIP未 stage／commit／push。
- WIP policy2 selector／intent／allocation／catalog strict 分支、正式入場／codec測試、既有 workflow M5先FAIL後仍跑M6、原生七鏈與 README 已接。具名 policy/codec/entry RED 各保原root，JUnit1107候選 PASS；四組初始 policy1重抽／unknown source／建築複製／雙向 edits mutation 有具名殺及逐位元 REVERT，後續 virgin、actual expected seed、stage 缺列的 test-only 負控另有新根並保原判。早期缺 import／throws、U來源艙不可達、camelCase父gate、不同生成次序、pack表示法誤等、地形差等 FAIL／SETUP 原根一律不改判。提前固定封包唯讀 core 審查 C0/I0/M0，**非**Task13 最終 spec／quality 放行。
- 候選 Windows `m6-native-run-c9eb67b128a4421a87954f6b25e1ba4f.json` SHA-256 `DAB08FAEFD918512DEDE43D2CE01CB1056969135D180BF4EA250372368F19DD4`：七鏈21phase全部 PASS（DIAGNOSTIC2／FUNCTIONAL19），`-VerifySummary -SelfTest` 255負例 PASS；canonical normal／amplified 各自新 root 的真玩家槓桿→checked same intent→新UUID／空storage→同未預生成樣本區的1440方塊／biome、非空monument、建築未複製與雙向修改／restart各通過；舊intent-allocation及partial-overworld在policy2正式候選中仍選random、保T12權威／16格有限儲存oracle。這是 base HEAD `5e9dc8f`＋dirty WIP 的候選，未做clean雙focused/full/遠端CI，不稱Task13結案。
- 同 JVM `M6UniverseGameTests.same_seed_fresh_copies_settings_not_blocks` 用 testmod checked catalog 直建兩個 schema2 U，**不是**Task21前不可達的正式 U-origin 玩家鏈。`gate-t13-consumer-candidate-c7d973d894a543008f9aac4b94a9ec95` XML SHA-256 `4BB410179ED5ED86532A028B3979BA498657455ACCAF01ECE92C493FAACEF87F` 原FAIL：兩側比較區各九格 holder/raw 皆先不存在、actual NoiseConfig／seed／structure owner各對 frozen expected，normal在(521,56,524) source seagrass／target water[level0]、兩側cold_ocean／FULL／同seed。保留的world-fresh raw MCA source SHA `6953509D31C743EF3718D50BB78C0E4253B721892D0412BFC92B35D819E0B0DA` 為seagrass、target SHA `973F49C0BC79ADE5BD6DA56440C0CF1B4725F88360B80583E12F51FC93171089` 為water，差異已入磁碟。四階段 test-only 見證新根 `gate-t13-stage-candidate-e1d182611aa141229f2321af568a7b56` XML SHA `153CC9949384192E3F97578F87F547854962E916AB8727BF0E93178F5E4C1001`／log SHA `3CB948BCB78533A0DB18C7C699939691030005BD2D0BE83ECE13BA81A9D147B5` 仍FAIL：四列同tick31，SOURCE_CENTER來源已有seagrass，BOTH_CENTER目標已是water，後兩個鄰域階段各hash未變；原1440方塊與biome斷言未刪，structure／建築／edits因前置terrain失敗未執行。缺列負控 `SAME_STAGE_RECEIPT_INCOMPLETE` 具名殺／REVERT，但不把診斷當功能GREEN。這只能把首次觀測分歧縮至雙中心FULL時，不能證生成放置或之後更新的唯一根因。
- 固定Yarn1.21 JAR SHA `C3D5432D0538D7750B985E42788484BEE4C63BBFACD7CADD496E940C83DFCEC4` 的唯讀bytecode：FEATURES population seed由 `ChunkRegion` 所見root與chunk座標重設，feature再依index／step派生；已讀鏈未見UUID／world key直接混seed。cold_ocean同時有 `seagrass_cold`／`seagrass_simple`，實際海草放置還依heightmap／WATER／支撐等當時世界狀態，且方塊／流體後續更新可改狀態。保存根沒有feature index／當次seed／候選條件／寫入順序，故**不能**判純vanilla或production defect。user已收到具體三選範圍問題：①U→U全量留Task13並做testmod專用有界因果見證；②U→U全量移Task21，Task13只保canonical真玩家與雙平台owner/settings；③binding縮為base terrain。回覆前不改U→U接受條件、不啟新Java、不提交 WIP，歷史所有FAIL保持原判。ignored `task13-scope-pending-20261108/manifest.json` SHA `1340A33D9BB60EF8FD3FF3C15C2FF0CBE9984B0C0F57A67F0883C73B58F07F8A` 封存18檔WIP，tracked patchSHA `02402496A046918FAC61BAF766F68F7C1B0A9227EFAB37444887E919978CDBD8`、新helperSHA `2EC64FDDBCDE896C77F5D63126149E6AE114BD4BE957AB9E389A105C81E9132B`，無非daemonJava owner。Task13未結，Task14、M6人工/main/tag均未放行。

### 2026-11-08：Task13／21 驗收分工裁示與 owner 測試候選

- 使用者原話「U→U 全量比較移 Task21；T13 保留 canonical→U 真玩家鏈與雙平台 owner／設定測試（建議）」。binding spec §4.1／plan Task13、21 已獨立窄審，初 C0/I1（新範圍 RED／mutation 不明），補具名 policy2 RED 與 testmod-only 錯 frozen seed→SAME_NATIVE_CONSUMER_EXACT 後同席複審 C0/I0/M0；ignored 設計 SHA-256 `66307D2F3C3B60A77B8F157AABC14AE40A511A316782EF81AE9C8B60800ACB6B`，tracked patch SHA-256 `0CC36B3E882E2ECDA9885E9152CCEB69D659B6DB42F88F3B6C75831C72F8FB90`。純文件 commit `ec605477890e044e112fe4b2b2a43d099be96e13` 已 push 並遠端讀回；CI `42458754297` 以即時結果為準。§4 same-seed 產品契約不縮，Task21 的 direct catalog U→U 僅是 testmod 控制，不開 spec §6 延後的動態 U-origin 真艙。
- Task13 同一 implementer 僅改既有 `M6UniverseGameTests.java` 與新 `M6ProfileProbePhases.java`：原 full 方法僅撤 `@GameTest`，full helper、1440 block／biome、非空 structure、建築／edit／stage 原碼逐位元保留，Task21 重新發現執行。新同 batch owner／settings 測試在 normal／amplified 各核兩個世界的 frozen seed、provider／preset／Parameters／fingerprint 與原生 noise／biome／structure owner。候選 compile `gate-t13-owner-split-compile-84a6af0d9fa843be9d7b8ddadae00572` exit0；testmod-only 預期 seed XOR1 負控 `t13-mutation-owner-frozen-seed-a94b746354ee4a39a5dfde7e4aabaca5` 的 focused 父 gate 由 `SAME_NATIVE_CONSUMER_EXACT` 具名 FAIL、當時名為 formal_entry 的 selector 控制 PASS，patch SHA-256 `53B8FF87D1C635B9FFE0B72619C9BD80F29DB66445BD196153F937E0A3F396D5`、逐位元 REVERT、世界 exact restore。新正常 focused `gate-t13-owner-green-d2a052f4236f4db2a15ef7b544c436da` 自然 exit0，owner／直接 selector 控制 2/2 具名 PASS（當時 XML 名為 formal_entry），normal／amplified 四列 native consumer PASS；scope patch SHA-256 `AA701AAF9034213C96E5D2B184C32DC87B2B970F8DE0AB287288FBAA27E89910`。均為 dirty WIP 候選，不冒乾淨 code pin 或整 Task13 PASS。
- 舊 c7d／e1d seagrass／water FAIL、raw MCA 與四階段收據保留原判，移作 Task21 尚未通過的起點；canonical→U 候選 PASS 不抵銷。此時 Task13 18 檔 WIP 未 stage／commit／push，下一步先建立 code pin、依使用者持續授權立即 push，再同乾淨 HEAD 雙 focused、完整 `run-all`、獨立 spec／quality 與同版平台矩陣 CI；Task14、M6 人工／main／tag 未放行。

- **純文件 CI 失敗保留（`ec60547`／run `42458754297`）：** Ubuntu `build`、Windows `m6-native` success，但 Windows `windows-native` 的預設 `./gradlew.bat clean build runGameTest --rerun-tasks --no-daemon` 在30分鐘逾時，workflow FAIL；M5 GameTest step 後續 success，M6 JUnit/GameTest 因當時 workflow 依賴被 skip，故此 run 不能作 T13 新測試證據。原 log 唯讀保存於 ignored `task13-ci-ec605-windows-timeout.log`，SHA-256 `BD1C941F502FB33AB7B74BA4AB260F948EB450DC08E6A5D322A59D1FB4EF55B2`；原生 Windows artifact 已下載至 ignored `ci-ec605-native-windows-artifact/`，沒有 default GameTest XML（逾時前未產終局）。日誌第一個可見 `X` 緊接 `m2_task4_no_entrance` batch，但缺它的終局 XML，不能定其根因；其後十個具名 M2／M4 方法在 `CorridorPageManager.reserve` 報 AABB 容量不足，最後 `m5_collapse_returned_elsewhere` 的 `native_returned_participant_starts_elsewhere_and_only_the_active_session_returns_him_windows` 於 07:40 開始、07:56 timeout 時無 END。這些是清單 K1–K3 外的失敗，不歸 T12、不改 PASS。對照 `11d38d4` 同 production/test code 的 CI `42455654361` 三 job success，兩次 default 前30 batch 同序；`ec605` 相對 `11d38d4` 僅 spec／plan docs，故不是本次 T13 程式差異所致，但首個 X 的執行期因果及後續 AABB 連坐尚未證實。保留整根 FAIL，待 T13 同 code pin CI 與本機 full 分別判定，不以一次成功追認本根。

- **Task13 spec Minor 單檔窄修提交前快照：** 正式 spec review `ec60547..860da7b` C0/I0/M1：新 GameTest 的 `formal_entry` 名稱只是直接 selector 控制，不能冒 Ubuntu 真玩家入場。原 implementer 只把名稱／斷言訊息改成 `selector_freezes_policy_two_for_new_session`／`selectorFreezesPolicyTwoForNewSession`，加 selector-only 註解；既有 Windows guarded 正式入口、兩條 canonical 真玩家 native、U→U full helper 不動。候選 compile `gate-t13-selector-name-compile-eb373502a3794b878b84b6f30f59bfbd` PASS，focused `gate-t13-selector-name-focused-6287faa2648040628c2120293a3e3ed7` owner＋selector 2/2 PASS；最終精度 focused `gate-t13-selector-precision-726ca36ed9034d36ba2bc23b8f985eb2` owner＋精確名稱 2/2 PASS、XML SHA-256 `0E5B80304CF1996A533644C2E200AB333F0EE483C621D084466526356BF41CED`、world exact restore、owner0。相對 `860da7b` 單檔 patch SHA-256 `019C38663B6F6BA553EDFEF7D24554417CD4A92CEA495BAB8BBBDA114D057A03`，尚未 commit/push/full；前一輪 focused 原名收據照原判。

### 2026-11-08：Task13 clean pin、清單外 CI FAIL 與 A/B/C 相容設計

- code pin `a92acb942c3e455c5f2daa37ff5dd23d0a8d49cd` 已正常 push、遠端同 SHA；正式 spec 的 selector 命名 Minor 修後同席複核 C0/I0/M0，正式 quality C0/I0/M0。乾淨同 pin 的 `M6Compile` PASS；`CandidateResolverTest`、`UniverseAllocationLedgerTest`、`CandidateJournalCodecTest`、`UniverseDefinitionTest` 各雙輪 focused 共8根 PASS；`m6_universe_same_seed` 兩根 `gate-t13-pin-same-a-59edb21425dd4e9cbbcb4cf73571242f`／`gate-t13-pin-same-b-67db4f860b0649deabac8c09284778bc` 各 owner／selector 2/2 PASS，未發現 Task21 full U→U 方法。canonical normal `same-seed-restart` 三 JVM FUNCTIONAL summary SHA-256 `65DA6618876EFA661FA94B76C72DE44AAFD2BF3856668C67A4912E9DE2563FA1`、amplified `same-seed-amplified-restart` 三 JVM summary SHA-256 `6B098F7BBB5B8F3120BFA14B55CBC60AF9B62CE6719D25969ADF7FAA411C79C5` 均 PASS，functionalAssertionsRemainRed=false。首次漏 `-ExpectedMode FUNCTIONAL` 的 `focused-t13-pin-normal-d9ccc3642be246e4baa7da2f62579d72` 在 JVM 前 SETUP FAIL 永保，改以新 root 正確重驗；wrapper末行固定的 `diagnostic-only` 字樣不作 FUNCTIONAL oracle，以原生 summary／三phase為準。
- 本機完整 `run-all-a872681b0343473e883590ee9f6e13ac` 在第一個 default gate `gate-full-5e801aeb1859406c8d257ed817ea893e` 自然 FAIL、沒有 `END ALL`；default XML 166案僅 `M4CandidateDoorGameTests.native_start_freezes_candidate_context_before_geometry_windows` 因 `M4_NORMAL_POLICY` 期待 policy1、實得 policy2 而FAIL，XML SHA-256 `6D2EAE7FDBA0E11FD85938EF31D7A417612E89D2D8E0A5FF4B9EE8067FE9AE72`、outer log SHA-256 `77727F4471DB96E3AE15976022C6BFF5E1EDEB313F2C6A50EA1212BCFB4CD002`；世界 exactRestored=true、lock可獨占、owner0、post porcelain空。原root/FAIL不重跑改判。
- 同pin CI `42462792915` Ubuntu build、Windows m6-native success；Windows default 30分鐘 timeout、M4 normal XML 13/13 FAIL（前五 policy1期待／實2、後八候選等待逾時，八案精確因果未證）、M6 Gradle成功但 `m6-ci-gametest.ps1` 讀缺席 default XML而step FAIL，整workflow FAIL。ignored CI log SHA-256 `EC657E2CFF10F7F5DB5804D2511BAA16111AD3658CF1E735D173FB2D6D6459CF`；下載的原 Windows artifact 中 M6 XML SHA-256 `C27A469CA839455223BF2BDDD93F1BF0A98CCC2E0CD7B5198D8C9AD7AB5BE998` 為19案0 failure/error/skipped（含owner與selector），仍不可冒 M6 step PASS；normal XML SHA-256 `A6CDAC8EFEA7489C8A66C1352FB8B4BB72A8FA241A1D664F447C659C29A21D47`。較早 `42458754297`、`42460563636` 同類 Windows FAIL 各保原判，不歸 Task12。default 兩份原 log 首 X 均緊接 `m2_task4_no_entrance`，slot1完成分別10561／25327ms；成功 f77 同序同code slot1／slot2為8686／9272ms，該測試兩段等待各硬10秒；後續 AABB 及末 M5 批次停滯可見，但 default 缺終局 XML，完整因果仍 UNKNOWN，不能宣稱 production 根因。
- 一份 Task13 CI 相容設計 A/B/C：A 嚴核當次新session policy2/family0，涵蓋本機 default 1案與遠端normal13案，舊凍結policy1反例不升版；B 先留本次M6+legacy exact own-scope收據、legacy XML/log freshness，再保留原 default/M5 完整 Assert-Scope 與終局 job FAIL；C 只在 M2 no-entrance 的兩段 remap 換用既有60秒 `CohortWaitTrace` 與正式 LOW／return cleanup，清理自身失敗仍保原錯/root且停止後續採計，不保證無殘留。原 ignored A/B 設計SHA `827C39725A883776C8CA11B30F4C7E06CDBC187979B3894AAC56AA673C9A8FF5`、C addendum SHA `5D61C73F53522EF89E85D20F2D04AE599905A0106E06E4E5C7E28B1963238178`、fix1 addendum SHA `58009A0E4A0ACAFCDF3B0099FE875CA59741F295CE3248A8707AF30BA35F2FFF`。獨立設計初審 C0/I3、fix1 與檔數窄補後同席複審 C0/I0/M0；tracked plan 加精確四既有檔白名單，**尚未改 code/test/script 或證修復**。下一步先 docs truth commit/push，然後一個 implementer 做 A/B/C、具名 RED/GREEN/mutation-only＋REVERT、乾淨雙 focused／完整run-all／兩席程式審查／同版雙平台 CI；Task13、14與M6整體仍未結，舊 U→U `c7d`／`e1d` FAIL 留 Task21。

### 2026-11-08：Task13 CI 相容 A/B/C 候選實作（未提交）

- 基準純文件 `f28ebe4d7b241c7408d1c2e84580a0c06fea6ef8` 之上僅四個既有檔 WIP：`M4CandidateDoorGameTests.java`、`M2CorridorGameTests.java`、`m6-ci-gametest.ps1`、驗證 README；production／workflow／新腳本均未改。A 舊期待值的定向 RED：default `gate-t13-compat-a-default-red2-345fee2372bc433bb077d835e75a8d09` 與 normal `gate-t13-compat-a-normal-red-e6468e990f0f4dc5a6478af500159b29` 各以 `M4_NORMAL_POLICY` 期待 policy1／實際 policy2 具名 FAIL、世界精確還原。首 default 呼叫參數 SETUP 與首收集器 `d038...` 的 `NOT_KILLED` 原判保留；更正收集方法後的新 mutation normal `gate-t13-compat-a-mut2-normal-76a3e44cd5974db5bfb86d4ffdfaa2f8`、default `gate-t13-compat-a-mut2-default-ef640f8a932b4cbb863b527bdaa85098` 各由原始 XML 的唯一政策錯配具名殺，mutation patch SHA-256 `28AEB3822887190B72090690FEECCC22F63BBFB1E09F7DE9ECED3237EA0FF25A`，逐位元 REVERT。只把四個確為當次新 session 的 helper 呼叫改驗 exact policy2／family0，未允許 1|2，也未改358門／選門／故障斷言。正常 default單案 `gate-t13-compat-a-default-green-00a5e74c883a4388b0d1589da22a96d4` 1/1、normal前五 `gate-t13-compat-a-normal-five-green-e3e389fd23d7483791479ed147725b37` 5/5、完整 normal `gate-t13-compat-a-normal-all-5dec56eed8414f14b1be54538457ef05` 13/13 均 PASS／world exact restore。這些新根不追認先前三筆 normal CI FAIL 或其八案逾時。
- B 原尾段受控 fixture RED `t13-b-red-fixture-135f248d...`：缺 default／M5 時舊腳本 own 與 full 收據均缺且 validation FAIL；首 `t13-b-selftest-907d097e...` 因 test-only `Get-Item` 替身參數 SETUP FAIL，兩根原判保留。窄改既有腳本：當次 M6＋legacy 精確 scope、新鮮度、pace／JAR／HEAD 先驗，再寫標 `own-scope-only` 的 `m6-own-summary.json`；其後**原 default／legacy／M5 完整 `Assert-Scope`** 仍執行，終局 `m6-summary.json` 僅四scope都過才寫。新受控 fixture 最終 `t13-b-final-selftest-78c0c0f1d98d41c8a2b4e2398487e6eb` 59 checks PASS（19個 post-launch 純 fixture，`minecraftLaunched=false`），缺 default／M5 可留 own 收據但 full 缺／validation FAIL；錯或舊 M6／legacy、pace／JAR／HEAD 均拒。延後 own 收據 mutation `45c1d587...` 與 legacy freshness mutation `6fb3d628...` 各具名殺並逐位元 REVERT。此為離線 oracle 證據，不能冒真 Windows M6 step 或整個 CI PASS。
- C 僅在 `native_remote_mapping_without_entrance_returns_to_frozen_source_windows` 的 epoch2／3 兩段等待改用同檔既有 `CohortWaitTrace` stage0／1；初始 ready、成功 alias／無入口斷言、tickLimit、共用 helper 及產品都未改。強制 stage0 不 ready 的 mutation root `t13-c-stage0-mutation-947d3ed32ef9479b86f75c5d2d4d3fca`：60,006ms／8,162tick 後具名等待失敗，cleanup FINAL `CLEAN`，正式 LOW／return／release、玩家與operator關閉、來源bbox與世界還原均真，patch SHA-256 `D7CD1A9589E140EEF3D717EDF41957BE602C4DB7640F9434D474D0FC0C2831A6` 並逐位元 REVERT；正常單案 `gate-t13-c-no-entrance-green-1220e7278105412fa8eb29aebc8b2c94` PASS。候選 default 全scope `gate-t13-abc-default-878ae22cba1d4dfea08a746d96ea5def` XML 166/166、零failure/error/skipped，SHA-256 `34C827D7C15EA0B2DFFD5FDADDDFF84DA025FB18EA611FEAAC77B7D2E04FE146`、world exactRestored=true。原 ec605／860／a92／f28 Windows default 逾時因果仍標 UNKNOWN，這次本機 PASS 不改它們原 FAIL。
- 以上 A/B/C 全是 base `f28ebe4`＋dirty WIP 候選，未建 code pin、未跑完整 `run-all`、未有同版雙平台 CI 或正式 spec／quality review。先由獨立窄審核四檔差異及三份 docs truth，然後精確 stage／commit、依持續授權立即 push；之後同乾淨 HEAD 雙 focused／完整回歸／兩席review／CI。Task13、Task14及M6整體仍未結，Task21 的 U→U c7d／e1d FAIL 另保原判。
- **B OutputRoot 重用 Important（提前程式窄審，未修）：** 同一輸出根先留舊 `m6-summary.json` 四scope PASS，後續 default/M5 缺席的本次 invocation 即使 validation FAIL，也可能與舊終局收據並存；原 19 個 fixture 各用新 output，未證此界線。ignored 新設計 `task13-ci-compat-reuse-addendum.md` SHA-256 `F36FCB11AB7E621D8117E01EB9F1517BA2990172DBD87553AAE9485B7B3B39B6`、plan 新窄補；獨立設計 C0/I0/M1 可採，唯一 Minor 是負控須證正式 launched callsite 而非只驗函式。僅既有 `m6-ci-gametest.ps1` 擬在Java/Gradle/寫證據前拒非空 `OutputRoot`、不刪舊檔，共用尾段另拒既有收據；本筆尚未改碼／建pin。任何只刪接線的 mutation 若未殺須保 `NOT_KILLED`，不能稱此 Important 關閉。
- **B 重用守門候選補證：** 先以受控同output根 `t13-b-reuse-red-2d1b16ad5ecf46728ae15ff88d48dacc` 證舊四scope PASS後本次缺default仍殘舊own/full；只在既有 `m6-ci-gametest.ps1` 增 launched Java/Gradle/任何證據寫入前的空 `OutputRoot` 拒絕及共用尾段在scope/own/full前的舊收據拒絕，舊證據不刪／不覆寫，非launched入口不誤擋。final `t13-b-reuse-final-570cabda9a7b4477b88a79fc36954d5a` 純 fixture SelfTest 70 checks PASS，selftest SHA-256 `51871AD7D22EC9A433B1F1AD5FD45C31F0A64FE5D8AEFC65E2BBD077121AA153`；只刪正式launcher第一句的 mutation root `63498696...` 由 `M6_OUTPUT_FRESH_WIRING` 具名殺、只刪尾段守門 root `7afdd68e...` 由同root重用具名殺，兩者 byteEqual REVERT。舊own/full hash不變，新root缺default仍own PASS／full缺席／validation FAIL；本輪四實作檔最終 patch SHA-256 `A4E7BBE1A0901FA40F4C3239188C58A3FCD595D5A414D8D591DAC665F9823FEF`、腳本SHA `E4A4F3ECE25113D9562DE71876ACC04C00B2EE668009CB0C829955BB5BDD9027`。原提前審查I1同席窄複核 C0/I0/M0，非正式Task13 quality；此時尚未commit／full／真CI，不能稱整片PASS。

### 2026-11-08：Task13 b704 同 pin 本機／遠端新 FAIL 與 E1/E2／amplified 見證設計

- code pin `b7041021260547117852249779a16c6c337152b7` 已 push／遠端同 SHA。乾淨 pin 的 M6Compile `gate-t13-abc-pin-compile-388a04b662cc4af9a7d1bb013c66c8f8` PASS，腳本純 SelfTest `t13-abc-pin-selftest-077f7573e8dc4692b007204c08e8f09b` 70項PASS（JSON SHA-256 `EA230ACB7F1A055991797E8E9F9EF5C9D61B81BA77C65BD13E0CC353A94C8B7D`）；default policy focused `gate-t13-abc-pin-default-a-c1b1956db25144cfa06d4878e9f30efc`／`-b-005b79a3afb042f6bcb0a773141c2130` 各1/1，M4 normal `gate-t13-abc-pin-normal-a-a98fc930aaa746298a175d45a210a7f9`／`-b-5fdd3931b19549548544193a07aeb3f0` 各13/13，M2 no-entrance `gate-t13-abc-pin-no-entrance-a-407909d046b94a089f4ff7c64198dd47`／`-b-133cfc4a3d13435b87f9aa839f55ab03` 各1/1且 stage0/1 READY，六根世界 exact restore。正式獨立 spec／quality 各 C0/I0/M0；這些局部成功不等於完整回歸或 CI。
- 本機完整 `run-all-35f5c298641f4b7cb043de022d548f76`：full、M4 normal、legacy、M5／M6 GameTest、main、main oracle、M3、M4 recovery、M5 collapse 都自然 exit0；M5 collapse 原69phase及W10 imported-only橋照原判。M6 native summary `m6-native-run-34857873a27c45cb99fd5f0c6b181f1c.json` SHA-256 `0E685B40FE3DE047CD3DBB7CC65017F651A47DC6EDDDBD1E6B593D9097756A94` 為 **FAIL**、phaseCount19：normal same-seed 的 source／new／restart均PASS，amplified source PASS，amplified new 在 `(527,64,512)` source `coal_ore`、target `stone`、兩側 `taiga`／`FULL`／seed `20260921` 而 `M6_SAME_FUNCTIONAL_FAILED`。`new/comparison-before-assert.json` SHA-256 `19AA7B47C63D94EB1508B2CB4001A5CBCB8825F6449191A1A1C29299C8D8162A`：兩側18格 virgin 前置PASS、原20次getChunk外部請求序列相同、1440樣本 terrain digest不同而biome digest相同；結構／建築／edit及restart因terrain先FAIL未執行。`run-all` console SHA-256 `9A8139E5133E2210813BB2E9175AA1F8B3BD2839EBD9456361768530B2D9A48D` 有 `END m6-native FAIL`，**無 `END ALL`**；重導外層仍等閒置GradleDaemon管線，自然子程序已退出、非daemonJava0，原根／原判不改。舊 a92 standalone amplified PASS 與本失敗並列，不能判純vanilla、production或必然可重現。這是 Task13 canonical→U 全量驗收，不在使用者移至Task21的direct U→U 範圍。
- 同 pin 遠端 CI `42476033674` Ubuntu build、Windows m6-native success，Windows windows-native FAIL。原 log ignored `task13-ci-b704-windows-failed.log` SHA-256 `3AB5F9FB90EE93D39EDF26113E73A30281B0C228B22DE59926F51808CFA23DDF`，artifact default XML SHA-256 `A1ADBC69E00AF9259297F4151ABB16193C2AB21A278D1F919856CFD03D29874A` 共166案27FAIL：no-entrance新修已PASS，首敗 `native_custom_milk_after_first_remap_move_never_publishes_half_cohort_windows` 在第二段10秒等待逾時，當刻slot2 FULL194／loaded及ticking180、仍SUPERPOSITION；CI批次轉下一案後才記 `TASK8_NATIVE_REMAP_MILK_HIT`，後26案完整連坐因果未證。Windows M4 normal／M5各success；M6 step在正式Gradle前的 SelfTest fixture `missing-legacy-xml` 失敗：manifest synthetic launchStart `2026-11-08T11:53:10.5668448Z`，`m6.xml` lastWriteTimeUtc `11:53:10.5667087Z`，早136.1微秒，先觸 `M6_SCOPE_MISMATCH`，未到缺legacy的預期拒絕，後續M6 XML缺席；workflow FAIL，不歸Task12。原 `ec605`／`860da7`／`a92`／`f28` CI FAIL、a92本機full FAIL、`d038` NOT_KILLED、Task21 U→U c7d／e1d FAIL均原判保留。
- 唯讀根因分析後兩份**既有檔**設計 E1/E2：E1只把 `m6-ci-gametest.ps1` 的 ownCases synthetic `$start` 往前5秒，四個old-*仍明確設為更舊，正式launched freshness完全不加容差；E2僅 `M2CorridorGameTests.java` 的 custom milk 第二段改有界60秒且失敗先關hook、以單一cleanup owner走正式LOW／return／release／玩家close，原例外primary，等待與cleanup witness共用 milk epoch1／RETURNING，既有stage0/1仍2/3。ignored 原設計SHA `C4143CDCB461085AFFC096A2A2C0EDDEE8E835BEB4253978418A4F1695F458A6` 初審 C0/I0/M2，fix1 SHA `BE4D0A5156872720B06F7FB801AC2CBC80F19C2F1443D62C611DBA46C21E30FB` 關閉後 C0/I0/M0；tracked plan轉錄初審 C0/I1/M1、補齊三項mutation及Error原錯後窄複審 C0/I0/M0。任何清理自身失敗仍保根，不宣稱無殘留。
- amplified 有界 testmod 見證只改既有 `M6ProfileProbePhases.java`：正式 native 也沿原同步請求記 `SOURCE_CENTER`／`BOTH_CENTER`／兩鄰域四階段唯讀中心引用；原1440點、biome、structure、建築、edit、restart斷言不減。當次 native 原本沒有四階段，`nativeConsumer`也只在direct catalog GameTest，不能把其他收據套到這次 FAIL；新方案在原1440取樣及比較收據落盤**之後**、terrain require之前以可選 native-only callback 核 exact target catalog／實際 noise／biome／structure owner，direct GameTest no-op保持原順序。它只能分流本次目標 owner與首分歧階段，canonical source未有同等consumer；若owner先FAIL，terrain require當次未執行，不能冒其結果。ignored 原稿SHA `91FCA1E46ABDA286E8583EC479477AF0A541AD36E8FE05B506C923ABC0FCB9E7` 初審 C0/I0/M2，fix1 SHA `316500F23A11E41861FDF200DD3E501F921C0F2810DD863E35F0B0EC4BBCE973` 與tracked plan段窄複審 C0/I0/M0。限缺階段mutation具名殺／逐位元還原後**一次**未施mutation amplified新原生三phase；若PASS仍不追認舊35f5 FAIL，若FAIL按階段／target owner收據查，不連續調樣本湊綠。
- 以上只是設計與原件，三檔程式尚未修、沒有新code pin、乾淨雙focused／full與同版CI仍待。下一步純docs truth窄審／commit／push後派一個 Task13 implementer 依plan白名單做 E1/E2 與 amplified 見證；不得新增 production mixin 注入點、驗證腳本、外部 JVM observer 或 CI job。Task13、Task14及M6整體仍未結，main／tag與人工smoke未放行。

### 2026-11-09：Task13 E2 milk handoff 候選修正交回（仍未定版）

- 原 `9b04f6d08d9234c4c8ad6a53143319bf49cb7ef1` 上三份 E1／E2／amplified 程式 WIP 仍未 stage／commit／push；當次 E2 early-review Important 是 `MilkRemapWait` 在 `trustedTeardown` 同步前置接手前先設旗標，若 record／LOW／journal／排程丟錯會跳過正式清理；異步 `whenUntil` hooks=null 亦會直接重丟。只在既有 M2 testmod 檔改 milk 專用 teardown，其他 `trustedTeardown` caller、共用 `whenUntil`、production／workflow均不改。ignored 原設計 `task13-milk-handoff-design.md` SHA-256 `14CEA5296BF186C03F4A5BFAEF452C27083673C5C91D603E99B26C82DBD9A39F` 初審 C0/I1/M1；fix1 SHA-256 `4C0F89C281E9AC23495F12A0153A8DE49C35B21BE2DA2BA3A663DDEE7538BB4B` 複審 C0/I0/M1；fix2 SHA-256 `A03AE309992D3C984998D43B5A25D1F6C60DC9FA50B0FF640F99DF8E8E064B1E` 最終同席 C0/I0/M0。fix1 只靜態證固定 1.21 `stop(false)` 不在server thread join，實際停服另靠runtime負控。
- 修前同步pre-schedule、異步condition、post-return close 各有新RED，原 GameTest FAIL／缺 terminal 原判保留。修後 milk 專用 LOW_ATTEMPTED fence、captured exact SID/chamber＋skipLow、單一 terminal callback／stop-before-primary 與逐錯 suppressed：before／after／partial LOW 的負控各原FAIL但 LOW1／terminal1／CLEAN；異步 condition／ready／Error、post-return、close、never-clean、stop-throw及跨tick三錯各有具名原件。close-fail 與 never-clean 須 terminal1／stop1、自然停服後同 runtime 零採計；stop-throw 原件明記 `EXPECTED_STOP_THROW_NO_STOP_PROOF`，不冒自然停服；首Error收集根 `3f2b...` 是 `NOT_KILLED_OR_UNRESOLVED`，後以新root精確crash/exit1/identity另證，舊判不改。inline準備 `r` alias／CRLF等三個 SETUP 永保，另有只到外層的 helper-close 根不冒真 helper close 分支。反向 11 個 mutation 具名 KILLED／逐位元REVERT，完整枚舉、patch及raw root見 ignored 交付索引。
- 自審另發現 close 重丟**已在 suppressed 的同一 Throwable**時，舊候選以數量增量判錯而假稱 `CLEAN_WITH_RETRY_ERROR`／stop0；修前RED `b9715f8d...`、獨立 failed 旗標後GREEN `a9f0b9fe...` 為 `CLOSE_FAILED`／terminal1／stop1、撤旗標反向 `4a47...` 具名殺，三根及原判均保留。最終 M2 SHA-256 `60606134C3F3ABD4804874E1A263C8FEC66B566B9BB6EA294A67E0FF5CFD8642`；E1 腳本 SHA `ACC49BFE3DB796089CE98D29CEF6DDD86B20E23E42390CEF349F476C8695D06A`、amplified helper SHA `A0AF5B5DDB184780BF9007F422E8D1A074BCBF5D011E3D17E39C77AA8042AB2F` 逐位元維持。三檔 patch SHA-256 `C790E7B1432A4D81A362F11A2272BAA9FC6BA203CA5268E3E4A236A076440D40`，本輪 M2 patch SHA-256 `FEB9C4602D0BD172983EE898F35EB52374EE3B65AD54DAB829D4B9B3D5851155`；ignored `task13-milk-handoff-delivery-bed56fe7212248eb82be69de474f1aac/index.json` SHA-256 `8914BAAC36009847DDD509DCDDD83EDE9956396DD86270121FCFD4B426CB2C5F`。
- 最終未注入候選的 milk＋no-entrance focused `gate-t13-milk-final2-focused-a431165e9e364360a4e076a204689029` 2/2 PASS，XML SHA-256 `EAE8419C960EF77B9A825F30687F663375A7E4B5D7C1530F55597363EB24277C`；default 全scope `gate-t13-milk-final2-default-86597b3d35f141498b2ac49213882ecc` 166/166、零bad PASS，XML SHA-256 `17D90F7570DA1BDB4EE44DA950C67323A856AB442F576ADAFC967C70E011116C`；38個本輪世界 exact restore、原world仍不存在、非daemonJava0、38個保留lock可獨占。這些是 dirty候選，沒有乾淨同 pin/full/同版CI，不可稱 Task13通過。提前獨立程式審 C0/I0/M1：LOW fence 與正式呼叫間的診斷log自身若拋錯會保守 `INCOMPLETE`／停服，不是假PASS；本輪列可見限制，不擴成新的硬化片。
- amplified 唯一未施mutation鏈 `db0fa1e8...` 三phasePASS／四階段READY hash一致及target consumer exact，只表示該次未重現；舊 `run-all-35f5...` amplified coal／stone FUNCTIONAL FAIL、raw MCA中心chunk全98,304方塊16差（14個在東界x527）仍原因未明，不能追認。舊 CI `42476033674` Windows default166/27與SelfTest時戳FAIL、docs-only `42483590507`及更早FAIL／`d038` NOT_KILLED／首amp stage收集UNRESOLVED／Task21 U→U c7d/e1d FAIL各保原判。依使用者2026-11-08最新指示，本對話只把Task13推到可誠實判定後收尾、不進Task14、不再詢問卡點。下一步四份 tracked docs truth 獨審後與三檔 code 精確commit／依持續授權立即push，再乾淨雙 focused/full、正式spec／quality與同版雙平台CI；若同seed全量仍有FAIL，Task13按實際證據保持未結，不以別根PASS改判。

### 2026-11-09：Task13 乾淨 pin 完整回歸 FAIL，維持未結

- code pin `1332c527b829b4a228471de541aff493cdcc7e49`（range `9b04f6d08d9234c4c8ad6a53143319bf49cb7ef1..1332c527b829b4a228471de541aff493cdcc7e49`）已正常 push、遠端同 SHA。三個既有程式／測試檔的 E1 合成時戳、E2 milk cleanup 與四階段見證已提交；production `src/main` 相對舊失敗 pin `b704102` 無變更。clean M6Compile、SelfTest70 PASS；milk/no-entrance 與 same-seed owner/selector 各雙 focused 2/2，正式獨立 spec／quality 各 C0/I0/M1。Minor 分別為 LOW fence 後診斷 log 若丟錯保守 INCOMPLETE／停服，以及固定 `stageWitness=true` 留死分支。既有 RED／GREEN、11 項 KILLED mutation 與逐位元 REVERT、三個 SETUP、首 Error `NOT_KILLED_OR_UNRESOLVED`、stop-throw `NO_STOP_PROOF` 依 ignored `task13-milk-handoff-delivery-bed56fe7212248eb82be69de474f1aac/index.json` 保原判。
- 同 pin GitHub Actions run `42499957714` completed/success：Ubuntu `build`、Windows `windows-native`、Windows `m6-native` 三 job及 M6／原生精確驗證步驟均 success。下載的 raw artifact：Ubuntu／Windows M6 XML 各19案，T13 owner／selector／正式入口三案皆零 bad；兩平台 JUnit各1107、零 failure/error；Windows原生 summary SHA-256 `8511AFEC15DABE68863D0A37906644C904449ED20268004987552D1865A96D10` 七鏈21phase PASS。Ubuntu `unsupported-native.json` 為 `UNSUPPORTED`／`nativeExecuted=false`，僅拒絕契約，非原生鏈 PASS。
- 本機完整 `run-all-dd57e94d52274671b4cfc506b390a9ab` 的 default／M4normal／legacy／M5 GameTest／M6 oracle-safety／M6 GameTest／main-only／M3／recovery／M5 collapse 69 phase各自然exit0；接著 M6 native canonical normal→U `same-seed-restart/new` 在 `(527,72,512)` source air／target spruce_leaves，兩側 taiga／FULL／seed20260921，由 `M6_SAME_FUNCTIONAL_FAILED:TERRAIN_BIOME` 具名 FAIL，outer exit1、無 `END ALL`。原native summary SHA-256 `26804DFD00281087765A0F3D0EE1ABEDC5FD328E571C1B2E94C34230AE53B6B8`（phaseCount16）；比較收據 SHA-256 `22C9D5EDD7AB4997D5ECEF044AC53E290C02F68CCE10A2176316D467D9A12B37`。兩側3×3 virgin preflight全ABSENT、外部20個請求順序相同、1440樣本與FULL_3X3不縮；四階段在 tick44 的 BOTH_CENTER 首次同時可比時已有差，載入兩側鄰域後hash不變；target native consumer exact PASS，canonical source consumer `NOT_COVERED`。terrain先FAIL，後續 structure／building／雙向edit/restart **本根未執行**。
- 舊 `run-all-35f5c298641f4b7cb043de022d548f76` amplified 在同東界x527 coal ore／stone FAIL 與本次 normal air／spruce leaves FAIL 並列；兩者的具體 feature writer／內部生成排程、canonical source owner 都無直接原件。固定1.21 bytecode只證 `ChunkRegion` 可跨chunk寫feature及seed派生路徑，不證本次原因；同pin遠端CI成功不可抵銷本機FAIL。新根原判永久FAIL、不在原root重跑，舊U→U c7d/e1d與所有歷史FAIL／NOT_KILLED／UNRESOLVED保判。**Task13未結、Task14不前進**；不改樣本或請求順序湊綠。下一步如再接 T13，先有界證 source owner／feature寫入者與內部時序，設計經獨審後才採可驗修法；本對話依使用者指示在此收尾。人工smoke、main合併、tag、PR及worktree退役未放行。

### 2026-11-09：T13 canonical-only 十次對照，原正式 oracle 不變

**結論：無動態 U 的 canonical normal 十個世界資料出現兩種歷史 exact hash：`82e9…` 九根、`704b…` 一根；第10根與兩個舊 normal FAIL 的1440列完全相同。** 本結果只支持目前模組環境內最終方塊變動，不證純 vanilla 根因、不替 amplified 判 PASS，也不實作14格／1%容差。Task13仍 **OPEN**、不進Task14。

使用者同日追加參考由主控採用，取代稍早自動放寬／全同真bug分支；binding轉錄見spec §4.2及plan末節。控制設計一次獨立窄審 C0/I0/M0後實作。受驗碼以 `7bbf7d78a872cda98f0252ffec6c44c95e78e462` 為基準，只改兩個既有testmod檔，沒有production、script、build、workflow、mixin或正式oracle變更：

- `M6UniverseRecoveryProbe.java` SHA-256 `33429e46ec2a8cb4f748d72825576569203992b5d61552ee3fc1627e0a116271`。
- `M6ProfileProbePhases.java` SHA-256 `3e08fe228fce7ea958a664e5208f20a4b0d37af5e6b79aae53882cc2208499de`。
- 新 `vanilla-normal-control/sample/DIAGNOSTIC` 在原Flow前短路；沿互動命令呼既有 `probe-common.ps1` 的 `New-OwnedRoot`／`Invoke-OwnedPhase`，沒有加入正式七鏈manifest或將此診斷冒充FUNCTIONAL PASS。實際互動命令保存為本批次ignored命令紀錄，不是新增可重用驗證腳本。

**批次與可比性**：批次 `t13vc-mw8nd4zy-r2`，執行時間 `2026-11-09 10:48:29～10:54:06 +08:00`。每根為 `run/m6-universe-<batch>-vanilla-control-NN`；原件在 `.superpowers/verification/m6-native-<batch>-vanilla-control-NN/sample/`，含 `sample-before-generation.json`／所有preflight attempt、`sample.json`、`final.json`、process／stdout／stderr。十個不同startupNonce與JVM identity、相同兩source與全部330個編譯class；3×3 virgin、原1440座標／Gson UTF-8 digest、每側請求及FULL_3X3均核過。所有JVM／launcher自然exit0，session.lock可獨占。

實際LOAD世界為 canonical Overworld／Nether／End及既有 `quantumchamber:superposition`；動態 `quantumchamber:universe/` LOAD數為0，U storage不存在、catalog空、allocation空。故本控制是無動態U的canonical取樣，不是無模組純原版，也不是server只含一個ServerWorld。

| 世界編號 | 最終block hash | 外層原始狀態 |
| --- | --- | --- |
| 01 | `82e90855…` | VALID |
| 02 | `82e90855…` | runtime PASS；原外層SETUP錯誤保留，另逐欄唯讀核對 |
| 03 | `82e90855…` | VALID |
| 04 | `82e90855…` | VALID |
| 05 | `82e90855…` | VALID |
| 06 | `82e90855…` | VALID |
| 07 | `82e90855…` | VALID |
| 08 | `82e90855…` | VALID |
| 09 | `82e90855…` | VALID |
| 10 | `704bcc53…` | VALID |

完整hash為 `82e90855546ea91cb34dfaa3483689655e9688cb3d2aeca2322a511725d0ccf6`（01–09）及 `704bcc53c0544f0448eba13a870a9ca64d4beab23b99097cd99fc123a784b0d4`（10）。十根biome hash為 `40346ea6cbec6ccb4b67425a709df1dcddc72c962fdee1f1e7f079fa5f57963b`、density hash為 `c51842a66aec974642a2ff741914c2ee19416ec378f23c4995d82fc90a01ba68`、normal設定指紋為 `4bdb138addb5fca11d0cce76c69d08d7d1598b56a6088286debb86202ef5f4b5`，逐欄／逐列相同。control settings於FULL快照固定後凍結，標 `CONTROL_POST_SNAPSHOT_NOT_MEASURED_RECEIPT`；不是正式MEASURED權威。density亦為FULL後actual finalDensity同1440點raw bits，標 `POST_FULL_FINAL_DENSITY_RAW_BITS`，**不是NOISE階段或feature前方塊地形**。

**全部差格（10相對01；其餘八根逐列同01）**：

| index | XYZ | 01／82e9 | 10／704b | 分類 |
| --- | --- | --- | --- | --- |
| 1217 | (527,72,512) | spruce_leaves（distance=3,persistent=false,waterlogged=false） | air | 葉／空氣 |
| 1337 | (527,72,521) | 同上spruce_leaves | air | 葉／空氣 |
| 1375 | (527,56,524) | coal_ore | granite | 礦石／石材變體 |

三格都在x527；不以差格少就採任意比例。主控唯讀解碼兩個舊normal FAIL原MCA，重算hash皆等於原704b收據，且其1440列與本次第10根逐列相同。歷史manifest排除restart重複、加入CI42499957714的new原件後，正好重現normal 7根來源82e9×5／704b×2、target82e9×7；amplified 6根來源3580×6、target3580×5／1a3c×1。各preset內seed／snapshot／1440樣本／pack相同；一個較早normal原件缺virgin證據、部分歷史dirty碼身份不完整，不能宣稱歷史全部條件已證同。

**SETUP與負控原判**：

- 初候選sample-count負控先遇 `SAME_SAMPLE_SHORT_HEADER`，原根仍FAIL／NOT_KILLED。只在control加入有界END tick就緒：九格live皆無、raw只ABSENT或唯一SHORT_HEADER才等待；成功仍走原strict完整header／九格entry ABSENT，原helper與正式鏈不改。全部attempt保留，不把短header當ABSENT。
- seed、合成U-loaded、1439 samples、略一鄰格及假hash五項具名KILLED並逐位元REVERT；假hash由外層從原rows重算拒絕，該負控runtime PASS不算正常樣本。缺sample／final／SHA欄位只對副本作負例。
- 正常02的Java／單根原件通過，原外層跨JVM把Map JSON鍵順序誤當內容差異而記 `invalid.json`；保原SETUP錯誤，另寫 `revalidation.json` 明示 `RUNTIME_PASS_OUTER_SETUP_ERROR`／`javaNotRerun=true`，沒有重啟或回填原index。批次status為 `TEN_WORLD_DATA_COMPLETE_WITH_OUTER_SETUP_ERROR`，`originalOuterBatchPassed=false`，不稱原外層十根全PASS。即使排除02，剩餘九根仍82e9×8／704b×1，不影響存在兩種hash的結論。

**證據、可支持結論與仍未排除的假設**：

| 證據 | 可支持的結論 | 尚不能排除／不能推論 |
| --- | --- | --- |
| 無動態U也重現歷史兩種exact hash；3差格全同 | normal最終方塊差異不以動態U載入為必要條件；不能把本次圖樣單憑有U就判T13 production bug | 當前mod環境、canonical/既有背景world共享狀態、原版排程、fixture／觀測成本；未直接證明FEATURES writer時序 |
| seed／設定／biome／raw density皆相同 | 這些已取樣的設定與函式值未變 | 不證feature前block相同，也不證所有owner／structure皆無競態 |
| canonical normal 10根 | 本次normal控制範圍可重核 | 不能替amplified target FAIL改判；尚未做amplified-only控制 |

**建議的最小後續控制（僅提案，本輪不執行）**：先以同一有界控制改amplified-only，保相同每側請求／pack／原1440點，比對3580／1a3c，取得amplified獨立證據。只有差異仍無法解釋時，才做配對的「只對一個normal U取樣（canonical啟動背景仍在）」與「canonical+U沿原global交錯取樣」；若要區分fixture，另以正式來源玩家／艙／source→new重啟但禁止U物化做canonical對照。這些不可同時改fixture與請求順序後就稱已分離共享狀態；先寫小範圍對照表再執行，不追feature writer內部時序。

**驗收契約候選（未採用）**：seed、frozen settings、biome、已證的density／structure authority維持exact，final FEATURES方塊差異完整保留分類。若需真正feature前NOISE方塊層，另證明忠實取得及觀測成本，不能用本次density替代。任何final允許範圍需獨立控制證據、使用者新裁定及獨立spec review；本次既不採14格／1%，也不重判舊FAIL。建築不複製、雙向修改隔離、持久UUID／catalog／restart保證均不降低。

**驗證與證據索引**：

- 最終compile `gate-t13-vanilla-control-postsnapshot-16107545e42745fb9d01a2d9403959f2` PASS。控制設計審C0/I0/M0；獨立控制code spec／quality各C0/I0/M0，終局證據確認另記同報告；不是正式Task13整段兩席放行。
- SDD共同前綴 `.superpowers/sdd/2026-10-24-m6-universe-generation/`：設計 `task13-vanilla-control-design-20261109.md`、設計窄審 `task13-vanilla-control-review-20261109.md`、implementer報告 `task13-vanilla-control-implementation-20261109.md`、code審 `task13-vanilla-control-code-review-20261109.md`。
- 批次 `vanilla-control-evidence-t13vc-mw8nd4zy-r2/ten-controls-index.json` 及各 `normal-NN` 原判／只讀重核；immutable pin保存兩source與330class manifest。
- 主控獨立audit `task13-control-independent-audit-20261109.json` SHA-256 `5651b20184fde6bf11854e2d350e6aa276783011e0552bd38513120a9381342f`，重算十根block／biome／density與所有SHA、檢查noU／preflight／identity／runtime及source／class逐檔hash。
- 歷史manifest `task13-historical-sample-manifest-v2-20261109.json` SHA-256 `94d081c58a13d0eb63266d13b4d02e2f7cea6440146e6efcc999e993fd93c963`；原收據／MCA僅讀，沒有改舊原件。

本次對照不是T13正式full或新code雙平台CI驗收，不採前pin CI替代，也不將診斷切片當Task13結案。舊dd57／35f5、Task21 c7d／e1d與所有FAIL／NOT_KILLED／UNRESOLVED永久保原判；本次資料與code/doc truth提交推送狀態以實際Git／遠端ref為準。main／tag／人工smoke未放行。

### 2026-11-09 補充交付：CENTER_FULL與基線集合候選（未生效）

normal原批次 `t13vc-mw8nd4zy-r2` 的10份 `CENTER_FULL`／`FULL_3X3` 完整rows再次唯讀核對：CENTER_FULL即82e9×9／704b×1，每根到FULL_3X3皆零變動；第01與10在CENTER_FULL已恰3/1440格不同，座標及block正是上節表格，全部x=527。補核原件 `task13-normal-center-full-audit-20261109.json` SHA-256 `67181d7b809e47156a7073ec9abf6b1e7f02f7da11e5348b4b5d833113662962`。02原外層SETUP錯誤與只讀逐欄重核處置不變。結論只到「模組載入、無動態U的單世界取樣環境」，背景world keys仍完整揭露；不稱純vanilla，不由normal推論amplified或U→U，亦不證具體writer/feature時序。

候選評估另見 `docs/superpowers/specs/2026-11-09-same-seed-baseline-set-candidate.md`。它分開討論T13／T21的固定受審canonical-only基線集合、新hash逐格差異並FAIL、strict項不降低、反例與spec §4.1／§4.2影響及獨審計畫；**未生效、未實作、不訂數字容差**。已明列關鍵缺口：人工把82e9三格改成704b時可命中另一已知成員，純集合判定不能保證所有人工改塊FAIL；不是SHA碰撞，也不能用故障旗標硬判遮掩。候選文件審查不等於正式spec acceptance已獲批准。

本輪新增amplified-only10已明確授權，另列獨立結果；canonical+U、U-only與client未授權。維度key、T14 Files／比較層、T18–20 credits後respawn／spawn owner／管理員指令的稽核只記SDD ledger，對應task開工前處理，現在不修。所有舊FAIL原判保留，T13仍OPEN、不進T14，交付後停等使用者裁示。

### 2026-11-09：amplified-only 十次對照交付（診斷切片，T13仍OPEN）

依使用者明確授權，比照normal的條件完成批次 `t13ac-mw8oturs`，十個全新canonical amplified世界、seed20260921、無動態U。**10/10控制有效，只有 `35809b1527b2e30b08b74140d5f8911798758978672956d68b8cb889e0417c4f` 一型；本批未觀察到 `1a3c…`。** CENTER_FULL十根亦全3580，各根到FULL_3X3的1440列零變動。這不是純vanilla證明、不是跨世界bug證明，也不替舊amplified FAIL或T21 U→U改判。

本次只在兩個既有testmod將嚴格chain對應expectedPreset、properties／actualpreset與post-FULL provider；normal原路徑保留，normal10原件不重跑。正式七鏈manifest、production、正式oracle、腳本、build、mixin與workflow皆不變。受驗HEAD基準 `c19aec704f71c93d9edf7de3b41aa8b29a2b3553` 加兩檔候選，十根前後source及330個編譯class逐檔exact：

- `M6UniverseRecoveryProbe.java` SHA-256 `4eb5b14b223f2c2adebb6f925403066d5e0a63a34fcdbda750bdca93adceaeb0`。
- `M6ProfileProbePhases.java` SHA-256 `7514d76d19cd630b1ef84393cef6d275b3f90ada17752ed7c2032c703bb21780`。
- class manifest SHA-256 `57f012f7ff043da5803bd3571e82e8c53759f99804073f6e747fb17a516ea82f`。

| 世界編號 | CENTER_FULL／FULL_3X3 | 有效性 |
| --- | --- | --- |
| 01 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 02 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 03 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 04 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 05 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 06 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 07 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 08 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 09 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |
| 10 | `35809b15…`／`35809b15…` | VALID；center→full 0差 |

十根的biome hash `ebba1f03180c56627a3df8ed55b7cc8a5995131df2f1d310f4f5a4553010188b`、density hash `d317d2cca4622ea21e1c70d10dcaf9fe19eadae743ea51a3ed8568efff8f411d`、amplified provider設定指紋 `102962da118dad66a5777ceeabd8590041862eea8f78a68f22725e6e2cdadf40` 均相同。density是FULL快照固定後的actual finalDensity raw bits；主控獨立重算的是原rows編碼／hash，**本控制沒有另建expected NoiseConfig獨立重做生成函式，也不是feature前NOISE方塊層**。control settings同樣標post-snapshot凍結，不冒正式MEASURED權威。

十根的原1440座標／順序／Gson digest、0,0與128,128前置及32,32中心→x外圈z內圈31..33九格請求、strict virgin／FULL、pack／mod配置、provider/version/Parameters/frozen seed與no-U證據均核過。world keys仍為canonical Overworld／Nether／End及既有superposition背景；無動態U LOAD、無U storage、catalog/allocation為空。所有launcher／JVM自然exit0、十locks可獨占、JVM/startupNonce各自獨立，沒有沿用normal root或拼不同code pin。

**歷史逐格比較**：唯讀解碼舊amplified FAIL原MCA，兩側重算hash分別等於原收據3580／1a3c；本10根每份1440列都同歷史canonical source3580。對歷史U target `1a3cd3e5a2bd1f9f9011dba0d13a369b7dcf8b6d492b618cd3662df543fbdcd1`，每根恰差index1216 `(527,64,512)`：control煤礦／舊target石頭。這不表示canonical-only重現1a3c；舊比較原件沒有stages時，不能倒推舊target在CENTER_FULL的狀態。

**驗證與原件**：

- compile `gate-t13-amplified-control-compile-5808cba6e51b43cfa752c8756cc76752` PASS；錯seed、錯preset各一個獨立fresh負控root，由 `VANILLA_CONTROL_SEED`／`VANILLA_CONTROL_PRESET` 具名拒絕，natural exit0／locks通過。只改各新root啟服前properties，沒有mutation候選碼，負控不混入十根。
- amplified窄設計及獨立設計審 `task13-amplified-control-design-20261109.md`／`task13-amplified-control-design-review-20261109.md`，C0/I0/M0；實作報告 `task13-amplified-control-implementation-20261109.md`；獨立code spec／quality審 `task13-amplified-control-code-review-20261109.md`。這些皆在本plan ignored SDD目錄，不是T13整段正式兩席放行。
- 批次原件在 `amplified-control-evidence-t13ac-mw8oturs/`；`ten-controls-index.json` SHA-256 `a3fae36a7ccd992ea8bf73e63c74f35b7cbf1e80794e1d1e938c23ae7936895b`，狀態 `TEN_VALID_AMPLIFIED_CONTROLS`。世界為 `run/m6-universe-t13ac-mw8oturs-amplified-control-NN`，原始三份收據及attempt/process/stdout/stderr在 `.superpowers/verification/m6-native-t13ac-mw8oturs-amplified-control-NN/sample/`。
- 主控獨立audit `task13-amplified-independent-audit-20261109.json` SHA-256 `6f11b69be301d1e39d9a37c70616bc2dbeb091766095e0763113ad06c4737b4a`，重算30份原件與block／biome／density hash、核strict preflight／identity／runtime、逐檔source及330class。
- 歷史兩側唯讀還原 `task13-amplified-historical-pair-20261109.json` SHA-256 `4f2eed28ac526442c8d0cc8cf35a59b9f92d10c15ae07837fb813f18b88ff004`；只作歷史對照，不是本次canonical-only見證。

**交付與停止邊界**：normal仍9＋1且三差已在CENTER_FULL；amplified本批3580×10未重現另一型。兩批各自解讀，不能外推U→U。基線集合候選文件完成分T13／T21評估，獨立文件窄審C0/I0/M0只代表文件可交付；純集合無法拒絕人工改成另一已知型態的缺口保留，正式採用仍需使用者新裁定及獨立spec review。本輪不實作集合、不訂數字容差、不改正式oracle，所有舊FAIL／NOT_KILLED／SETUP原判保留，T13仍OPEN、不進T14。canonical+U、U-only與client未授權且未執行；交付後停等裁示。稽核只記ledger，T14／T15–16／T18–20開工前處理，現在未修。

### 2026-11-09：T13 CARVERS 分層裁示與受審設計（控制／程式尚待執行）

使用者新裁示只適用T13；T21之後另裁，維持原exact。T13嚴格seed／frozen settings／provider-FP／biome／structure／建築不複製／雙向修改隔離／重啟權威／UUID-catalog不降；新增遠離FULL且初次virgin區的actual CARVERS 1440方塊exact，不以density代替、不設容差。final原1440 rows與所有差格保留、new hash標UNRESOLVED送審，final block相等不再作full回歸關卡；基線集合不採T13關卡。

前置已核 `c19aec7` 的run42554234370與 `7c03919` 的run42556499574均同SHA三job終局success；metadata原件 `task13-layered-preflight-ci-c19aec7-20261109.json`／`task13-layered-preflight-ci-7c03919-20261109.json`。這只滿足使用者要求的先行CI，不是未來新code CI替代。

一份設計 `task13-layered-carvers-design-20261109.md`，SHA-256 `e53f560020550b7079e1d05a35904a4e6524137d80067edf5d775b65baaf0e5d`；獨立窄審 `task13-layered-carvers-design-review-20261109.md` 初C0/I1/M0，唯一I1（只改期待值不足以證實際wrong seed/preset）已由fresh canonical root啟服前實改properties修正，chain期待及class pin不變；同席複核關閉為C0/I0/M0。失敗properties與prelaunch副本/SHA保留，不把wrong輸入改回掩蓋；只有實際testmod source mutation需逐位元REVERT。

實作分A→B→C：A只兩個既有testmod，canonical normal/amplified各10全新CARVERS-only root，固定中心(1024,1024)平移原6×6×40點；用actual generation dependencies及write radius計halo，前後raw/live隔離、exact CARVERS直接讀塊與stored biome，不求FULL。每preset hash必單一，第二hash即停A/B/C回報，不移點／減halo／改判。兩preset均單一才可做B正式T13新strict層及final診斷（T21 default helper exact保留），再做C原雙focused／完整run-all／獨立spec-quality／同SHA三jobCI。錯seed、錯preset、actual一格CARVERS改塊各具名FAIL，不用故障旗標假殺。

原生save/flush對純ProtoChunk有型別限制，不能預設CARVERS一定存MCA，也不得為保存升FULL。new actual witness及SHA／權威鏈必存；restart在raw/live不高於CARVERS前提下忠實標fresh重建或partial重讀／完成，實際取樣後對new同側及兩側exact。缺witness、錯鏈、升級或不相等均FAIL，不能missing-cache略過。Java與PS1的new/restart terrain隱藏關卡同步改CARVERS/權威strict，final跨phase也留診斷diff，biome／structure／edits等不降。

本節是裁示／設計採用紀錄，不是控制或程式驗收通過。所有dd57／35f5／c7d／e1d及FAIL／NOT_KILLED／UNRESOLVED原判保留；T13未結不進T14，新增canonical+U／U-only／client控制仍未授權。

### 2026-11-09：T13 CARVERS A20與B候選交付（C正式出口待驗）

**A前置條件已成立，B候選已完成且兩席早審C0/I0/M0；此時T13仍OPEN。** c19aec7／7c03919先行CI已三job終局success；新code的乾淨雙focused、完整run-all與同SHA CI仍待，不能沿用舊CI或dirty候選稱正式通過。T21原exact與dd57/35f5等全部舊FAIL不變，不進T14。

| A：canonical-only CARVERS | 有效世界資料 | actual1440 block hash |
| --- | --- | --- |
| normal | 10 | `957bf76ec7cc5d47bbd4176e0876b5422d27545597234f95b369c1c72f47acb7` |
| amplified | 10 | `742a4d5b7966e6d5596da7e62fbfc7916f7df14913f6e47649928996a1f05455` |

固定chunk(1024,1024)，x/z16384+{0,3,6,9,12,15}、y=-64..<256 step8，直接讀原生ProtoChunk；每根恰一次typed CARVERS request、exact class/status、stored biome，無density替代、無FULL捷徑。actual依賴Rc9／Rf11／W1，H=22，三個snapshot各2025cell；NOISE/SURFACE/CARVERS writeRadius各0，virgin與live/raw>CARVERS排除、最近FULL在H外、配置/seed/provider/FP/mod/pack/身分/自然exit均核。只到模組載入／無動態U的canonical單世界取樣範圍，不稱純vanilla或推T21。

A原外層結果為19PASS＋normal03一筆JSON鍵序比較SETUP；該根Java及raw條件有效，另有readonly逐欄核證/javaNotRerun，原錯誤保留、不說原外層20/20PASS。此前三個未達測項的前置失敗原判分列：uppercase nonce為啟JVM前SETUP；短region-header為runtime FAIL `SAME_CARVERS_SHORT_HEADER`；過強current-holder要求為runtime FAIL `SAME_CARVERS_OWNER`。它們皆無有效hash樣本、不冒具名RED；兩筆runtime FAIL沒有改稱SETUP或改判。僅exact SHORT_HEADER有界下一END tick就緒、不重請求／加票／升FULL；初次manager→holder==actual返回Chunk綁定不可覆寫，後續保同原生reference，允許自然卸載但pos/class/status與完整halo判定不降。

實際wrong seed/preset各fresh properties負控具名拒（早期source pin，startup guard之後未改，不冒最終pin重跑）；actual CARVERS一格mutation由共用sampler/exact helper拒，guard bypass實際NOT_KILLED RED→恢復KILLED GREEN及source/全compiled byte REVERT齊。wrong properties留失敗根，不改回掩蓋。A20以同兩source與1094class完成，immutable保存；主控與獨立spec另讀20sample/final/request，重算1440座標/block/stored-biome與121500 halo cells。

**B限定現有兩條正式T13鏈。** 只兩既有testmod及既有 `scripts/verification/m6-universe-probe.ps1`；source遠區只preflight，new雙側actual CARVERS exact及immutable witness，restart依raw/live<=CARVERS partial重讀／完成或fresh重建並對new同側及彼此actual exact。原生partial不保證存MCA，沒有靠FULL／反射硬寫或missing-witness略過。世界key→dimension region解析補齊，canonical path及A sampler/point/radius/H/exact body不變。

Java/PS1兩處final terrain相等關卡皆移除，只T13 explicit opt-in；default helper的T21仍exact。原final32,32/3×3 FULL請求與1440點保留，完整兩側rows/hash、全部pairwise及new→restart差格、biome/structure/building/edits/UUID/catalog等strict不降。diagnostic採 `novelty=NOT_CLASSIFIED`／`reviewStatus=UNRESOLVED`（未作歷史新舊分類，不把已見hash宣稱新）；不維護或擴baseline、不以任何集合或UNRESOLVED當full PASS/FAIL，審查處置另記且不改原receipt。少列、假hash、漏diff、錯stage/link仍屬完整性FAIL。

新增的跨tick會讓既有M5正常到PASSAGE_READY。舊probe合併null/SEALED guard在候選ed0c…誤擋，原FAIL保留。owning source確認M5§12以PASSAGE_ENDED exact current/flushed CAS後checked absent才留terminated tombstone；B只接受startup checked exact READY且frozen/selected/intent已核，之後current/flushed皆absent＋本instance tombstone＋無quarantine/rollforward的合法終局。CREATE_NEW startup NBT/JSON/SHA綁sameSid/UUID/root/source-new鏈；真SEALED、不明null、未確認刪除仍FAIL。正常候選normal648f…走CHECKED_PASSAGE_ENDED、amp e8d…走LIVE_CHECKED READY，兩者三phase皆Java＋PS通過；沒有修改production或暫停其狀態推進。

**最終pin正向實證**：f0e2a2f353274d74b51e4ea67a16af48為既有normal三phase的temporary testmod controlled fault，restart在原FULL請求/四階段後、取樣前真改source index0 `(512,-64,512)` bedrock→diamond_block。far CARVERS仍兩側957bf exact；new source82e9→restart sourceaa116a66…28c2c7，target704b保持；pairwise完整4差（3自然＋1受控）、source new→restart完整1差、target0差。Java＋PS/continuity皆PASS且UNRESOLVED，證兩處沒有藏舊final gate。此fault不算正常C；完整patch/pin/原根保留且finally三source＋1095class逐檔REVERT。離線打包初誤設pairwise恰1已保為純離線檢查錯誤，只改獨立計算全差格與含受控格，不改runtime或oracle、不補跑；數字4/1/0只是該根事實，不是一般限制。

14個獨立parser副本反例具名KILLED，涵蓋缺列/stage/SHA/UUID/替換new witness/漏pairwise或phase diff/halo/radius/request/真SEALED/不明null/未確認刪除；PS差格guard bypass NOT_KILLED RED→恢復 `M6_FINAL_DIFF_COMPLETE` GREEN並byte REVERT。synthetic不冒functional，原件不回填。最終候選compile與純parser sanity通過，正常兩鏈、受控正例與兩席早審皆不替代C出口。

**固定候選與可核原件**（共同SDD前綴 `.superpowers/sdd/2026-10-24-m6-universe-generation/`）：

- A報告/原件 `carvers-control-evidence-t13lc-20261109/`，index SHA `e708c7fa6075e5de98bd9a201b961cf8032a0d854e21d42eb221704a1464aeee`；主控audit `task13-carvers-a-independent-audit-20261109.json` SHA `c5807ca3d6f104ab012467fcd502b76b3e47e4260a59b204d424fbc59b3117b0`。
- B `carvers-stage-b-evidence-t13lb-20261109/candidate-pin/pin.json` SHA `859c876d0169bdbb395503a957be29f836fa6192bc103e4753ab5349ec6efafb`；完整A+B diff SHA `0711550661b35ce482a68d8e42e3b1b89af347a9b8722b9255fc4130dde76347`。
- source SHA：Profile `e85a0e1df634a7b46210199267d8521165541491d01a988c6db03675beb4f18a`；Recovery `e976ed5f0c90fcb455c00a6c44a22c494024e8d172dceff99b390ef317a2f8e6`；PS1 `46968771d579bf3090b38f4b0f1b9230f6952ff369eddefbff931d9b44acb36b`。
- 實作報告 `task13-layered-carvers-implementation-20261109.md`（A/B各時點byte副本保留、live報告供追加）；B交付 `stage-b-delivery-index.json` 與1596檔manifest（SHA `1aab990709b4f299e8bf21cac77e7bc701213774e020377f04d3688b401f9bb7`）釘全路徑/原件SHA，非完整world備份。
- 獨立formal spec早審 `task13-layered-spec-review-20261109.md`、fresh quality `task13-layered-quality-review-20261109.md` 均C0/I0/M0；只審immutable `.bin`/diff/archive與既有raw結果，未重跑runtime。C乾淨pin與終局會同席另補，不能預寫通過。

本次具體code pin後才執行C：原same-seed owner/selector與milk相關雙focused、兩條正常native、完整run-all自然END ALL0、獨立spec/quality終局與新同SHA三jobCI。C前不結T13，不進T14，人工/main/tag與新增CU/U-only/client仍未授權。


### 2026-11-09：4593 CI verifier逾時與PS1流程fix1（新C／真CI待驗）

`4593e5cdb3f0ba02fe89eba42f8a0a26d308996f` 的CI `42572502698` 終局FAIL：Ubuntu及Windows主job成功，M6 native七鏈21個runtime phase全PASS，但後續精確收據 `-VerifySummary -SelfTest` 在既有5分鐘timeout。這不是已觀測CARVERS不等，仍是實際CI失敗，不採runtime PASS替代。完整job log捕獲SHA `b50853415792c2b62a044b778ef1e53f4d080c47dd9b2e69ff36ebea58a27f38`；原artifact13654541826 digest `611e54e837992e491b2928188cba022283401740614b1d27eb5da6c8096a7ea0` 在 `ci-4593-native-verifier-artifact/` 保留不改。獨立spec另核21receipt、兩T13 CARS/final/diffs/24linkSHA/52650halo及startup NBT無strict矛盾，只是資料review，不將CI改判。

已向使用者先回報；本機C自然停在M6Compile一次PASS與四個既有JUnit各雙focused：CandidateResolver18、AllocationLedger10、CandidateJournalCodec6、UniverseDefinition12各×2＝92 exact，無bad/skip，HEAD4593/porcelain空。GameTest/milk/native/full未啟；這些只屬舊pin部分C歷史PASS，不假稱新pin門檻已滿足。

唯讀profiling找出重複工作：原SelfTest連baseline234次總驗證／379次Profile consumer／normal continuity60次、277反例；單次normal source約128–226ms、new788–1036ms、restart1621–1913ms、continuity636ms，JSONparse/clone約300–781ms，halo68ms/sample14ms/diff18ms/hash5ms。工作投影348.7秒未含其他開銷，不能稱CI walltime。fix1只改既有 `scripts/verification/m6-universe-probe.ps1`：原global與整chain predicates逐字不變，真Verify先global+全7鏈；SelfTest局部receipt/preservation/hash override同global+受影響chain，manifest/global仍完整，另拒跨chain override並在SelfTest末再全7baseline。所有277反例、錯誤碼/identity/SHA/CARVERS/diff/lifecycle/continuity守門保留，不cache PASS、不改Java或任何worldgen/門檻/timeout/workflow。

parser-only合成輸入以既存本地c9eb前五鏈15phase與真B normal/amp六phase聚合，只新summary/header/provenance，不改原receipt/path/SHA/world，不是同run functional或原CI replay。完整baseline7/21 PASS耗9.573秒；完整277反例＋末尾全baseline耗189.9467614秒、outer0/無stderr，在原300秒硬界內餘裕110.053秒。此只是本機parser benchmark，仍須真正新code SHA CI在原5分鐘通過。

固定PS1 source SHA `e768b910d5fa23c6e1a92791255f260065a0b9e85a138bdadeeea4b1a84e0c8d`；fix diff(base4593) SHA `b8bc0d8b9e3144535a7706b4a10528d85af40e7b390ebc94d392bcf2dd4e529e`、pin SHA `bd2c17514ec82e83d82bd9ebc7d6e25f71a1728c216c959d7d24c38860289019`。原件在本plan SDD `carvers-verifier-timing-4593-20261109/`、`carvers-verifier-fix1-20261109/`；bench result SHA `10af9190efc695d741790c8b01549e2a15cde1fb7953a835f67e9125f672f73a`，predicate文字等價proof與原資料manifest另存。Java Profile仍 `e85a0e1d…4f18a`、Recovery仍 `e976ed5f…a2f8e6`；A20不因純PS流程抽取重跑，也不把A當C。

同席fix1 spec／quality窄複審C0/I0/M0，獨立重核global/chain body、offset0/2/6/8/11/15/18及global唯一性/局部作用域/前後fullbaseline；這不是新CI已通過。fix1新code pin後須重新按原C做乾淨雙focused、正常native、完整run-all及同SHA CI。所有舊FAIL/UNRESOLVED仍保，T13OPEN、不進T14/no新CU-Uonly-client；任何final診斷審查只加獨立review，不回寫原producer UNRESOLVED/NOT_CLASSIFIED。

### 2026-11-09：C2 full 原判 FAIL 與 continuity evidence-base fix2

code `d973710cf21c89fa5f491dc3b9874e2bdf11058c` 的 CI `42581484000` 同 SHA 三 job 終局 success；真 SavedSummary-SelfTest 273秒、原300秒內，日誌為7鏈21phase／277反例。原 metadata、job logs、native ZIP 與430檔manifest在 SDD `ci-d973-42581484000-20261109-083f5344/`，artifact13657757471 digest `3ee812469f60369ee029199393ae1e7465d9ac5484aacbe50ef502c19aa102c2`。不改4593原CI FAIL，也不拿CI代本機 full。

C2 compile、四class各雙JUnit共92案次、owner／selector各輪2case及milk／no-entrance各輪2case雙focused，另normal／amplified各雙fresh三phase鏈均通過；restore與環境精確還原。原owner locator遞迴錯算自測fixture的aux SETUP保留，另唯讀重核唯一真gate2case，不重跑改判。spec同席focused審C0/I0/M0，final所有rows／差格／UNRESOLVED保留。

首full深路徑根 `carvers-stage-c2-evidence-t13lc-clean-d973-20261109/06-c2-full/run-all-d818a0ebde4141e29a020ec700f1f3ea` 在runtime前被 `M6_CI_CWD_UNSUPPORTED` 擋下，outer1／無END ALL。只改呼叫用原腳本預設短EvidenceRoot後的新根 `.superpowers/verification/run-all-f8eefde1119b4f55b24fde36668368db`，所有runtime gate自然exit0，但最後 `END ci-rule FAIL CI規則未原樣通過：native-green`，outer1／無END ALL。兩根與原failed fixture均保原判，不將runtime全綠稱full PASS。

原f8的真AllEnabled native摘要 `073c800d4e394c529d8ec7a0c9d41f74` 為7鏈21phase PASS；失敗出在ci-rule複製腳本至新fixture後，`Assert-ProfileContinuity` 仍從全域 `$project` 重組CARVERS路徑而報 `M6_CARVERS_LINK_MISSING`。初步「少複製sidecar」猜測已撤回：既有wrapper刻意保留原absolute receipt paths，各phase從已驗receipt dirname讀sidecar本來正確，原new／restart sidecar存在且SHA精確。只讀診斷保在C2證據目錄 `native-green-path-diagnosis.json`。

fix2僅既有 `scripts/verification/m6-universe-probe.ps1`：continuity顯式接caller已驗過的三phase receipt evidence bases，核長度／順序／absolute normalized path／nonce-root-chain／同父及exact phase目錄；Saved從已驗state累積，runtime從已驗current receipt dirname傳入。原ReadLink的檔名／相對正規化／SHA／schema及actual CARVERS／biome／structure比較原封；不glob／fallback／補拷sidecar／改wrapper或Java，也不提高timeout。

固定source SHA `6d9f9e334e305919b122e23573f4dfd2b344eb12a4a8a69e73b963c8ac32c969`、base d973 diff SHA `9043ae5f2e20c5c6d9712c16fbbb4eb2027eb62dfac637b946a56c5e985a4ad2`，pin SHA `aa0ab4413d122d08a3215fb639657ce9c8f9b895862d1d743e2b33d198a6b31b`；原件在 SDD `carvers-continuity-fix2-20261109/`。9項context正反例均通過，包括正常base、原fixture context、缺／錯／換序base與缺witness時repo有同名仍不得fallback，以及錯SHA／檔名。f8真單run073c輸入完整Verify＋277及末全baseline自然exit0／空stderr，203.610秒，原300秒餘96.390秒；這是唯讀parser回歸，不是新pin C3或原full改判。

fresh原ci-rule fixture `.superpowers/verification/ci-t13-fix2-native-green-e25355a8c9d042369ad305de69c1c3de` 自然PASS，outer／step0、196.995196秒，真7鏈21phase／277。result SHA `2fa654ece78df0838756fa5a7bae7512a44ad33d9e7b670bf349f50ae18c64c0`；原f8 summary SHA `cec6602ac2ac20b4b863e03479b2aeee9d41e01b05302da616711cb26cb2bf9e` 未變，原failed fixture未動，沒有啟新JVM。同兩席fix2窄審C0／I0／M0，均獨立核source／pin／context cases／真277與fresh replay，報告先留前版快照再追加。新乾淨code pin的C3雙focused／full／同SHA CI尚待；C checkbox不勾，T13仍OPEN、不進T14。Java與A20原封，不因路徑relay重跑20個控制或把它們當C；T21及所有舊FAIL／UNRESOLVED原判不變，新增canonical＋U／U-only／client未授權。


### 2026-11-09：T13正式結案（只適用T13，停在T14前）

**T13已達本輪全部正式出口；formal spec／quality終局各C0／I0／M0，可關閉T13。** 程式驗收pin固定 `d0aafb47d574f5fff24f712246bbf3508ec2a635`；同pin雙focused、完整full與三job CI均已真正完成。後續本次提交只更新AGENTS、接手入口、plan及本note四份狀態文件，不改程式／測試／script／build／workflow，也不把任何舊FAIL翻案。文件HEAD與正常push狀態以Git／遠端ref為準，不預寫合併main、tag或人工驗收。

| 出口 | 本次實測結果 |
| --- | --- |
| A canonical-only CARVERS | normal、amplified各10個有效fresh世界資料，各preset hash單一；原outer19PASS＋normal03鍵序SETUP與唯讀重核分列保留 |
| 新strict層與反例 | actual CARVERS 1440逐格exact；實際錯seed、錯preset、actual一格改塊各具名拒，負控pin、RED／GREEN／REVERT限制見A／B原章，不冒最終pin重跑 |
| C3原雙focused | compile1；四class各雙共92 JUnit案次零bad／skip；owner／selector兩輪各2case、milk／no-entrance兩輪各2case；world／env EXACT |
| C3正常native focused | normal、amplified各雙fresh三phase，共12phase／12獨立owner，actual CARVERS與重啟連續性exact |
| 完整run-all | `run-all-3eaeb70caecd4e39b4e22935f05cd53d` 自然END ALL exit0／outer0，同HEAD；summary SHA `9bf3ceb82469b993d4e6b3205e8b08fc32cda86db86933c3e1831553a2bf9c05` |
| full實際scope | default166／normal13／legacy1／M5 GameTest60／M6 GameTest19，皆零bad／skip；JUnit1107＝1105實際執行＋2既有Windows平台skip，failure／error0；M3 4＋5、recovery25、M5collapse69 |
| full原生與收據驗證 | AllEnabled7鏈21phase＝DIAGNOSTIC2＋FUNCTIONAL19；原ci-rule native-green與原末段VerifySummary各自277全過，分開採計、未新增standalone／未減原step |
| artifact／還原 | release／sources testmodHits0、main-only PASS；原世界及環境還原exact；最終porcelain空、非daemon測試Java0 |
| 同SHA CI | [42595046917](https://github.com/Ben513242/QuantumChamber/actions/runs/42595046917) 同d0aafb47，build／windows-native／m6-native三job終局success；真SavedSummary-SelfTest258秒／原300秒，7鏈21phase／277 |
| 終局review | formal spec C0／I0／M0；formal quality C0／I0／M0；完整final診斷另有唯讀處置，producer原件不改 |

A兩組actual CARVERS block hash仍為normal `957bf76ec7cc5d47bbd4176e0876b5422d27545597234f95b369c1c72f47acb7`、amplified `742a4d5b7966e6d5596da7e62fbfc7916f7df14913f6e47649928996a1f05455`。只支持本批模組載入、無動態U的canonical控制條件；不是純vanilla定理，也不外推T21或任意生成歷史。seed／frozen settings／provider-FP／biome／structure／建築不複製／雙向edit／restart權威／UUID-catalog與actual CARVERS exact均保持嚴格，不以density代替。

**final診斷處置：** C3 focused、full及CI的每側1440列、digest、pairwise與new→restart完整差格均已獨立核算。C3 focused amplified第一輪是source1a3c／target3580，full則source3580／target1a3c；兩者都只有index1216 `(527,64,512)` stone／coal_ore方向相反的完整差格，各側跨phase0差。這是各根事實，不是容許一格或任何比例；兩側完整rows對上歷史觀測僅供分類，不構成集合membership關卡。此次正常觀測未見新形狀，審查接受為完整診斷輸出且未見新增strict finding；FEATURES先後、共享狀態等因果仍未證，不再追feature writer時序。受控人工FULL改塊正例另列B原章，不混入正常觀測。

所有producer `reviewStatus=UNRESOLVED`／`novelty=NOT_CLASSIFIED`原值保留。獨立審查的「資料完整、可接受診斷」與「原因未證」分欄記錄；不回填receipt、不自動擴baseline、不將舊FAIL追認PASS。T21共用helper仍default exact，正式same-seed驗收之後另裁。

**固定交付索引與SHA**（共同SDD前綴 `.superpowers/sdd/2026-10-24-m6-universe-generation/`）：

- `carvers-stage-c3-evidence-t13lc-clean-d0aa-20261109/stage-c3-delivery-index.json` SHA `2b8a2cbc037a5a883251d1bed7c9a0cbbc0d9049a62e85474837b35d77cfd82e`；8804檔`owned-evidence-sha256.json` SHA `b70757bf73a3793393dfd743fd3c6ad1244f9d46de1513d4b1ed63fd62da0bf1`，42個intentional reparse只列不follow，不冒完整世界備份。
- 同目錄`immutable-final/pin.json` SHA `0078c8d84f58235f65fcf0dd7a3ec6046d4c06376ea5c07d6a404d10d22d6b5d`；三source與1095compiled class current／archive逐檔exact、554tracked前後exact。C3原時點報告`report-through-c3.bin` SHA `a51340020e576a0d703e8696cb365a88aaa452449d80f09a6ee3f55592b5ceae`。
- full native `m6-native-run-af923338ffa148d39e3c77f1d9e4c114.json` SHA `e1cbfd8a1de7e44fe492592a58aa522bbb27a7ffc91ae19004f192d351e9e6d7`；原末段`m6-native-verify.log` SHA `007d7529cd02dd9a586e7544bbed676da8ca026a5edff2d9c86c4c2e87dea383`；原native-green fixture69f186f…的command／result／stdout與同摘要鏈列於C3索引。
- `task13-layered-spec-review-20261109.md` 正式終局SHA `9d10ec77cd76f61f2b59ddc41fb9fb0c2679f69e6d88c7845631783eca44a804`；`task13-layered-quality-review-20261109.md` 正式終局SHA `c02427c2fb989d1ac5a8f96b9959ff26e89e486152058a61bcd6f5a93b9c6dc4`。其後文件truth窄核如追加，原終局時點保byte快照，不讓append掩蓋此引用。
- `task13-layered-final-diagnostic-review-20261109.md` SHA `0d09958b41cd8f0c22bdb5fc4c135a95ed25d3cbdfcbce3bc75f6492dc85c657`、同名JSON SHA `728f86eacdbc8f4c27bf623732419a627c19b269507b5550f0245fe148febebb`；`fullAccepted=true`只對本次3ea，不改舊f8／dd57等判定。
- 新CI原metadata／全job log／native ZIP與445檔manifest在 `ci-d0aafb-42595046917-20261109-093b0f71/`；原ZIP API digest核相同。舊d973成功與4593失敗均另存各時點，不混用。

**保留的失敗與停止界線：** dd57、35f5、U→U c7d／e1d、4593 CI timeout、C2深路徑preflight與f8 native-green FAIL，以及所有原NOT_KILLED／SETUP／UNRESOLVED均保原根原判。fix2只修證據base relay，不補檔覆蓋舊fixture；24份舊原件前後SHA仍相同。C3離線包裝曾用錯歷史class檔名而SETUP，原錯保留、修正唯讀包裝查找、runtimeRerun=false，沒有因此重跑或修改正式原件。

本輪到T13與文件交付為止。Task14未開工，T21另裁；新增canonical＋U／U-only／client smoke未執行，M6整體／人工／main合併／tag／PR均未完成。維度key、T14模型Files／amplified比較層、T18–20 respawn／spawn與管理指令等deferred ledger事項只記錄，仍到對應task開工前依授權處理。


### 2026-11-09稍後：純文件CI再發失敗，T13重新OPEN

純四docs commit `43cb99b26543204ba23f9fbd3502b03cbe91c934` 已正常push／遠端讀回同SHA／porcelain空，對d0沒有任何code／script／build／workflow差異。自動CI `42608921665` 卻再發兩項實際失敗：Windows「原生建置與GameTest」step6於11:44:23Z FAIL（job後續仍跑，具名case尚待原log／XML）；m6-native runtime21phase成功後，SavedSummary-SelfTest step6於11:41:59Z至11:46:59Z正好原300秒timeout，log／annotation明記5分鐘逾時，沒有M6_NATIVE_VERIFIED終局。Ubuntu build成功不能抵銷兩個FAIL，runtime成功也不能代SelfTest過關。

已立即回報使用者，原run／log／metadata／artifact保留，不重送碰運氣、不提高timeout、不減277或更改正式oracle。這是同程式再次暴露不穩定的驗證出口，故T13最新狀態重新OPEN、C checkbox恢復待驗；上節d0的C3 END ALL0／CI三job成功／兩席C0I0M0仍為真實歷史，原件與formal報告不回判。不得把純docs當作忽略此次失敗的理由，也不能尚未見Windows原case就判external、known或unrelated。

初次回報時，同一implementer只做既有PS1 verifier的唯讀成本量測、確認重複驗證範圍及保全部criteria／277／前後全baseline／無PASS cache的最小流程改善候選；尚未改source或啟新runtime。候選須先量測／設計與同席窄審，再依已授權T13界線處理；不趁機修未授權檔案／production／workflow。不進T14、T21不變，全部舊FAIL／UNRESOLVED與原失敗根全保留。

**上述新CI原件補核與fix3設計採用當時快照：** 42608921665終局build成功、兩Windows job失敗。Windows原XML default166中15fail／0skip（10個有限等待逾時、5個局部實例／完整AABB容量不足），normal13／legacy1／M5 60／M6 19零bad／0skip，JUnit1107零fail/error／既有skip2；step9由default scope連帶拒，不是M6原XML本身bad。原default XML SHA `15f387efb6ffa3d852bf287207cbc266fc80f7d591b7aeb95d7726e37ab75920`。完整capture在SDD `ci-docs43cb99b-42608921665-20261109-4e19fcb2/`，`CI_OBSERVATION.md`與`ci-terminal-failure-audit.json`（後者SHA `a278303337ee66e3378572e508a405ca2cf5acba4167f6f90b778a3e27f41b2e`）列實際終局及根因未證；未重跑。PS1-only fix3候選設計8557…10cb及plan已同spec窄審C0／I0／M0（review e07cc7…d703），准同一implementer僅manifest前置與SelfTest exact phase-scope，不減正常7／21／原277／common及continuity／首尾baseline，無PASS cache或clone語義／timeout更改；候選bench與新pin正式出口未完成。M2僅核owning-source，不改碼／新probe。


### 2026-11-09：verifier fix3候選完成（新pin C4／真CI待，T13仍OPEN）

同一implementer僅改既有PS1的manifest完整cheap檢查前置及SelfTest單receipt exact phase-scope，正常Verify仍完整7鏈21phase、全部原common SHA／header／JVM／lock／PID與cross-phase continuity、首尾全baseline／277皆保留。沒有PASS／parsed cache、clone／COW、Java／production／workflow或timeout修改；多故障first-error優先序可能因manifest前置改變已明列，單一代表反例拒碼等價。24原函式全文、manifest if與switch literal、原chain20個if與general／真runtime入口有獨立文字證明。

真3ea／af9233單run完整Verify＋277＋末全baseline自然outer0，152.7044356秒，原300秒餘147.2955644秒；這是新候選對既存真原件的唯讀量測，不是43CI重播、新SHA CI或C4功能驗收。另45項context cases（含正例、拒碼等價、scope反向NOT_KILLED→KILLED、byteREVERT、raw tamper與object隔離，不能稱45個負控）完成。原62收據／CI43artifact438檔／兩Java source／1095classes前後exact，四份docs WIP明列由controller管理，不冒當時porcelain空。

固定PS1 SHA `f12d80ee4dbdd14b44a2e4662a3e669ff2d0be6c4478aad4c5ced6fd6d69bf60`，base43 diff SHA `2ffefcaa4ecc7aeddb1ded423644399559526eb6cf8eeb31277efd1379c83075`；SDD `carvers-verifier-fix3-20261109/fix3-delivery-index.json` SHA `f807d494a33a2d0dad745c0e8d480768579c5903e451af209ab59612c0798345`，final-candidate-pin SHA `65c947514f23e2e94e3cf2e6d6501b3296948ee56a7612501158e227ebaf0b64`，manifest SHA `d7282687cf6152ea0231e3fca2b41cd97ae96516c48f894277f31b296d9fdfbe`。候選時點報告先保byte快照再append，`report-through-fix3.bin` SHA `4bf47aed46ecedec9c2b5b74bd5ae3b96637661f264dc98e549fe99aa9224ce7`。

fix3正式窄spec／quality均C0／I0／M0、無必要修正，review另存本plan ignored檔，原formal9d10／c024／diagnostic不append或回判。新code pin的原C4 focused／完整run-all／同SHA真CI仍待；不得以152秒本地結果替代真5分鐘CI。M2一般TrustedSpace／whenUntil不在milk-only舊授權內；本段寫成時新增範圍尚未批准，其後使用者已明確授權（見下節），仍不能視為已修或以等待時間當授權。T13 OPEN、T14不前進，所有原FAIL／SETUP／NOT_KILLED／UNRESOLVED保留。


### 2026-11-09：使用者另准M2 testmod窄修（尚在設計，未實作）

使用者已明確回覆「授權此 M2 testmod 窄修」，核准範圍限M2CorridorGameTests.java這批TrustedSpace等待／失敗cleanup，先一份設計與獨立窄審C/I0後實作，保原ready predicate／功能斷言，以原tickLimit內條件等待及正式cleanup驗證，不改production／不加外部observer；15案是否同因仍待驗證。這是本次新增授權，取代上述當時『M2未准』狀態，不回寫歷史原件。

controller已派fresh設計者先寫唯一ignored有界設計，未放行任何M2碼。原read-only audit確認這個首列case是TrustedSpace manager.ready(prepared/remap)，不能混稱NativeEntry.activeReady；無hooks when每次10秒另有scheduledtesttick90000，generic FULL只固定62,125旁證。當時target182格loaded/ticking129不滿整lease必要條件，但哪個deadline OR分支／時間首因／case級cleanup完成與15案資源連帶尚未證；production allocator reserve empty亦不構成共因證明。

PS1 fix3兩席C0／I0／M0可先checkpoint，後續M2受審候選完成後才以最終固定pin做原C4／完整回歸／同SHA CI，避免混source或重用舊pin驗收。T13仍OPEN，T14／T21／新canonical＋U／U-only／client與production等原邊界不擴。


**M2唯一設計終複審與開碼（2026-11-09）：** f57初稿的release checked/accepted混用I1與FinalFlushDiag不可見Throwable文字M1，經同一設計8d與69ada最小修訂，兩項皆ADDRESSED；核可設計SHA `69adaadb7bc136f59799aa6349a3a737390483546c40abb3181ed0c7b1fe2aa1`，獨立終複審SHA `f0c21ba29cb01eaffeb81c0e26946ae59014ba65531c01d50eb66cf7e9e39f36`，C0／I0／M0。原f57、8d與原finding原件保留。controller已採用精確plan轉錄，放行fresh implementer僅七案opt-in與同檔privateowner／constructor／returnTrusted／assertComplete分支；receipt-first與release accepted分離、原ready/tickLimit/功能斷言、正式cleanup與primary/suppressed均依定稿，不改其他callers／production／observer。這是開始受限實作，不是已驗收；RED/GREEN／候選、最終固定pin C4／full／同SHA CI仍待。

PS1 checkpoint `e3389d995eec46cbf562261ce01817a24b35b942` 的CI42619363848已三job終局success，真SavedSummary原300秒內212秒／7鏈21phase／277，rawlog已核；這份程式尚無M2修正，不回判43原FAIL、不認定15案同因，也不替最終M2 C4驗收。舊formal9d10／c024與fix3 e64等報告SHA均保原值。


**2026-11-09使用者最新止損補充：** 本輪照69ada已審設計完成七案，不再擴大；七案外那10不修、不新設計／觀測。最終pin full／同SHA三jobCI若全過才依原門檻收尾；若失敗只落Windows default該10或同類偶發，保平台／job／具名test／特徵後停等使用者，不重跑湊綠／自發waiver；其他失敗停查不歸偶發。結案文件commit先「待本 commit CI」，該commitCI真過才算T13結案，不預寫closed、不在CI真過後再遞迴發狀態docs。每commit／push／gate終局更新ignored STATUS首段；controller已補正原停17:22的首段並保存原bytes，local gate交implementer即時更新，後文歷史保留。舊FAIL／T21／T14待使用者／未授權CU-Uonly-client邊界照舊。本段是流程限制，沒有更改任何原件判定或宣布目前候選完成。


**2026-11-10 M2 code QA目前：** frozen source0f1、真9/9與唯一default166/166均PASS，但尚非最終C4。immutable packet index `c79b424ac8a8dcbd4f6288021dab41843da6f62227aec11df06ff7fb108c7f80`／supplement `42f057bafd8ae49cc696ad45ed890b4f618591aeb8778fad6264ac0c067c88b4`保94實際gate與normal fresh XML／scope／restore／locks、1100compiled pin。兩席正式source review同C0/I1/M0：finish的terminal診斷RTE及末error logger Error漏stop-before-primary，原candidate不定版。按SDD第5輪fresh agent只此finish/stopOnce收尾與兩位置定向控制，其他69支路不改，不重做94或default，不擴七外10。原candidate source／report／控制FAIL與UNRESOLVED保原判；I1未清與最終同SHA full/CI未完成，T13仍OPEN。最新首段/每gate/commit/push狀態以ignored STATUS即時更新；結案文件commit必先待本commitCI。


### 2026-11-10：M2第5輪I1關閉，候選可定版（最終C4與CI待）

M2 source `51fbf9198f1732dc604698fd73eb4afad35ba760b03c6fa15b5b2d2cd359c17e`，相對原0f1只finish／private requestStop +9/-7。兩個logger位置RTE/Error現在都走一次fail-stop、原primary不變、每個owner可見錯完整即時suppressed，stop fence在可能丟的diagnostic/API前，非clean後logger再錯也不雙stop。90000／8000／99900／60秒與69其他程式不變；不改production／PS1／任何七外caller。原兩席I1已逐一ADDRESSED，限定spec及quality皆C0／I0／M0（不代表最終C4/CI已通過）。

- immutable fix5入口 `m2-terminal-fix5-20261110/index.json` SHA `5894216cd575d5df3aa25763761e3526884c431a7c116b5659c3ab78b394ff2c`，原report最後snapshot `c57f6fc0e9a3d9c71ec931ae8545d4185e36865c2a248f4de474038154bacb36`。
- 有效控制2RED／5GREEN／1精確scoped fence KILLED；所有expected GameTestFAIL與契約oracle分列，真backend/ack/close、sameprimary/suppressed/stop1-before-dispatch、自然runtime終局／world／env／locks均核。原source與1100compiled逐位元REVERT，正常9根08104fb7 fresh9/9零badskip。
- 原682e temporary全域替換誤碰舊milk stopAfter同字行，scope-tainted原source／patch／oracle仍保、不採scoped證明；新exact owner-only202c控制前onehunk/excludedbytes檢查、真2stop目標KILLED另列。正常9的額外只讀checker曾誤期待runner狀態SUCCESS，原SETUP保留、依原EXIT_ZERO schema補核，沒有重跑或改正式收據。
- 限定spec report `m2-terminal-fix5-20261110/spec-i1-scoped-rereview-20261110.md` SHA `7576d425caa288e5cff7d167cd055ea731c8cfe2a6307b5ee2aa0f9f14d97f93`；quality `task13-m2-terminal-fix5-quality-rereview-20261110.md` SHA `517afc24e9caaeaf5914b15c6dfd201c16f502fe53a8b068b8bcea3c1dc9ca47`。原f13/f4e1 code finding與原0f1/9d10/c024/e64等時點報告不回判。

此前M2固定codepacket `m2-trusted-fix4-20261109/delivery-0f1cd6e8-20261110/index.json` SHA `c79b424ac8a8dcbd4f6288021dab41843da6f62227aec11df06ff7fb108c7f80`，原source0f1正常9與唯一default166均真PASS；其source不同，不冒新51fb或最終C4的default/full。source69要求的retained entity witness已依同spec源證落實，不推論c8f028消失原因；所有七內原normalFAIL與controlledRED／UNRESOLVED保持，HANDLE只讀target補證仍不改原checker。

目前只准定版這個候選並跑最終同乾淨pin的原雙focused／完整full／同SHA三jobCI與final-data兩席，tracked在C4凍結。T13保持OPEN，結案文件commit必先「待本 commit CI」、該commitCI真過才閉；Windowsdefault該10/同類偶發停報user，其他fail停查不歸偶發，不rerun湊綠，不擴七外10，不開T14／新CU-Uonly-client。每commit/push/gate更新STATUS首段。


### 2026-11-10：最終C4真FAIL後止損交回（T13 OPEN，未結案）

來源pin `0abd50e92592e9d50a1c2fa0fe1c944409608c95` 已正常push／remote exact。17focused全actualPASS、來源/554tracked/1100classes/env exact，sameSHA CI42675304922三job成功（真SelfTest246秒／277／原300內）及兩席focused/CI部分data審C0/I0/M0都保原判，但不能代完整full。

原full8f190 w10-preflight SOURCE_PIN FAIL/outer1/noENDALL原判保留；只讀同Hidden child對照（default Console950導Gitdiff patch904c，起始UTF8原54993與其他4pin全exact，producer runnerbytes6036相同）確認啟動編碼設定缺失。只修互動launch UTF8、新root原full80f9ddf169964559a7a787101133cd9d未繞原guard，W10/default166/normal13/legacy1與JUnit1107(執行1105、Windows既有skip2、fail/error0)自然通過。

新full在本機Windows M5 GameTest正式60case/1failure/0skip，唯一 `m5universeprojectiongametests.prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection`。UTF8原log03:36:58顯示projections.dat.<nonce>.tmp→projections.dat的 `java.nio.file.AccessDeniedException`；CheckedNbtFile.write:70原Files.move(ATOMIC_MOVE,REPLACE_EXISTING)失敗，CollapseAuthorities.checkedWrite/rollForward保未確認current與退避並回WRITE_FAILED，test1070將PENDING傳index.confirmed，被原只接受WRITTEN/UNCHANGED/ALREADY_COMPLETE的守門嚴拒。本案是在建立fixture另一艙PENDING時失敗，尚未驗實際projection overlap conflict；不冒衝突行為已測。

全域較早03:35:47另有AUTHORITY_CORRUPT錯，不與目標case混因。原件不足確定哪個process持鎖，不推Defender／索引／另一測試或外因。既有M5 AccessDenied已知限制紀錄不構成本輪CI/full waiver；依最新user『其他失敗停下查』停止，無M6後續、無ENDALL／outer1。不rerun湊綠、不改M5/production/OS/retry、不擴七案外十案。M5世界exactRestored、locks可獨占，test結束來源/compiled/env/no非daemonJava均已核。

證據共同SDD前綴，`final-c4-evidence-0abd50e-20261110/result-index.json` SHA `0e60b81a3924f0558632249efde25eaf9a599e097813a42e593b6b785735950d` 保原17focused與第一fullFAIL；`diagnostic-delivery-index.json` SHA `5a980c7d7b9a8ce32fdf51d76c14a72d7a7b41cc0a9bec429310563edce88bcc` 保編碼只讀對照；`full-explicit-utf8-supplement-index.json` SHA `d0b74389e4064d684d884eff0c4acb6bc9ca2d2bd6c9304b11a1e14e70d3dd0d` 保新full4011原件SHA/launch/actualexit/sourcepostpin/scope/XMLfresh/restore/locks/具名stack/report快照。正式M5 XML SHA `ced900ad3aa367f55aab26d53434c7a0ff92ff05a709291d118300fbb2c51578`。不覆原index或任何原判；producerfinal UNRESOLVED亦不回填。

T13維持OPEN，沒有T13結案文件commit；四份本次失敗交回文件先保WIP，不為狀態觸發另一輪CI。STATUS首段已按事件即時更新，交回時controller接管。後續等待使用者裁示，不進T14／新CU-Uonly-client。


### 2026-11-10：80f9 AccessDenied已知限制裁示／准一次新full（尚未執行）

使用者確認80f9的Windows原子移動AccessDenied屬2026-10-21既有已知限制，production維持fail-closed／退避，不修production、不改OS／Defender／索引。原80f9 full FAIL及所有原件不回判。先提交並push四份失敗交回文件，核除docs外全部code與0abd50e相同，再於該cleanHEAD新nonce完整run-all一次；這次再遇AccessDenied或任何其他failure都停報，不再重跑。

80f9停在m5-gametest，其後M6 safety/GameTest、main-only、M3、recovery、collapse、native、ci-rule及summary等都沒跑，本次不能沿用。僅完整自然ENDALL0／outer0與該SHA三jobCI成功、所需獨審C/I0齊後才做結案docs；先寫「待本 commit CI」，該commitCI真過才結案，仍停T14前。每commit/push/gate後即更新STATUS首段，T13目前OPEN，不預寫新full/CI已通過。

通用T14–T21規則同時記入AGENTS與plan：某codepin full唯一為本簽章且無其他FAIL，可保原root、freshnonce重跑一次不必再問；同pin第二次發生停報，不藉純docs SHA重置次數，不改正式判定或production/OS。其他未授權控制、T21與舊FAIL界線維持。


### 2026-11-10：f308一次授權完整重跑失敗，止損交回

第一步已完成：四份失敗交回文件（含最新明確裁示）commit／push `f30853a2ca6429050e890568cb35dc5ef1501af0`，remoteexact/porcelain空；Git tree排四docs後550項mode/type/blob對0abd全exact，code-equality-before-push.json存本plan ignored full-retry-f30853a-20261110/。source M2 51fb／PS1f12不變。新full與新sameSHA CI均只本輪資料，未沿用80f9的M5後半。

唯一full `run-all-50cee6a578f8476f81e1bc1ef10a11bf`：UTF8啟動，原W10 preflight exit0，default原runner09:03:08開始、MC09:04:41；執行至09:24:08 timeout、stdin stop未完成，fullchild09:24:12 outer1。RestoreRoot拒liveowner，原gate/execution/locks-after保持FAIL，無ENDALL／任何後續gate。正式default GameTest XML缺席，不填最終166或fail數；先行JUnit fresh91XML=1107/0failure/error、既有Windows skip2。原liveUTF8 log09:14:20具名trusted_publish_checkpoint_failure_preserves_false_windows、09:14:42 east_complete_doors_require_checked_ledger_windows各InvocationTargetException caused by AABB容量不足；09:11:21 pin>256只是更早特徵，不推共因、不擴source/觀測。

安全收尾：原execution已送stdin stop未完，當下雙核唯一MC PID58156／Parent12036／CreationUTC01:04:41.158957／完整本repo DLIreport+defaultNormal+argfile，root授權retainedHandle只kill此owned orphan並確認退出；非自然stop、不改測試原判，Gradle64216/12036隨後自然退出、未另kill。兩輪Java0/lockexclusive後，再驗canonical工作區界線與祖先nonreparse/current存在/backup與fresh目標均absent，只losslessMove失敗world至原gate/world-fresh，663檔SHA與22目錄前後exact。原beforemanifest與算法backup缺席，不能冒原harness exactrestore；新manual-owned-cleanup-preservation.json分列sourceflow inference、當前runtime world path absent與保留world。原world-restore.json未造、原FAIL收據不覆。

sameSHA CI `42732476087` 終局FAIL：build success、m6-native真187秒／7鏈21phase／277原300秒內success，但Windows主job step6/default failure、step9缺build/gametest-results.xml連帶fail。原artifact沒有default XML，不以oracle副本補；normal13/M560/M619/legacy1 freshXML0bad0skip，JUnit1107/0failerror/skip2。raw lateral-south第一primary是AABB容量不足（reserve807→prepareRemap541→lateralRemap311）；north constructor/reserveInitial同容量拒、RESERVATION_UNCONFIRMED/STOP。只記具名stack/cleanup與stop差異，不認定與local共因。AccessDenied在T12_FINAL_FLUSH_DIAG/HANDLE背景不當獨立unexpected IO簽章；明示模擬readback fault亦分開。

本次新證據：local `full-retry-f30853a-20261110/index.json` SHA `0a7d6873843b8fef51a753bb3becff767cd521e975f5154a4c32ebff05f44ed1`，1053原件SHA／source+compiled/env／forced-owned-cleanup與manual保留界限俱在。CI `ci-once-f30853a-42732476087-20261110-a7e62390/ci-terminal-stop-audit.json` SHA `efb60ce57c099aa00531b1f4c55cc55b40101e68d1eb1a3db87c024bcb34bc05`；最小case/scope audit SHA `9c2b78ac2908d8ddb28c9554a42506041c01693a337d54d0d15e88fde3571daf`；1289檔capture manifest SHA `d8feba6187b89ce87201f7982eb249bcd886c4b97137a87637dc673ab9c67b1b`。全部原0abd三index與80f9FAIL仍exact，不回判。

使用者本次一次額度已使用，因真FAIL已停下、不再重跑；不套T14–T21一般規則給本輪更多嘗試、不修七外10或production/OS、不新設計/觀測。T13仍OPEN，沒有結案文件commit；四份新的失敗交回記錄先保未提交WIP，避免以狀態push自動觸發另一輪CI。STATUS首段已即時更新，runner/CI collector全交回controller並停止；T14與新CU-Uonly-client仍未授權，等待使用者後續裁示。


### 2026-11-10：AABB 原件調查止損與 B+C 範圍／流程新裁示

先前有界原件調查只核既有六份 default log 與四份本機終局 journal，沒有新 runtime 或 source 修改。global managed pin 超 256 會連帶 fail 其他符合條件的 space；offline 的離線 cohort 可繼續保留。但四份終局 `Records` 均只有指定 offline SID，指定 pin-capacity SID 全部缺席，四個有正式 XML 的樣本該 pin-capacity case 均 PASS，推翻兩 SID 都長期保留到終局的概括。journal 缺席不代表當刻 allocator 無租約；max slot 5／6 只有關聯，首個 AABB 當刻完整 live／provisional／pending bounds 不足，未證實或否定 4096 expanded chunk union 耗盡。依原裁示停在這個證據邊界，不繼續設計或修 M2。

既有原件索引 `.superpowers/sdd/2026-10-24-m6-universe-generation/default-aabb-crossrun-original-audit-20261110/index.json` SHA256 `6d84464c4bb13e070bae78015cf5c6af8a9c9a7d78beb27826ffbc8c78c1261d`；報告 SHA256 `6ecccaae3d5d26241df18d5dea8186da6e709c87951bb4b65fbd26059c133db9`。獨立 owning-source 只讀報告 `task13-pin-capacity-owning-source-readonly-20261110.md` SHA256 `e422f5a9d5ad484634e95ffd51eab460d19b24de505bbe531fa1f2a51692869e`。皆為 ignored 證據，原 log／NBT／FAIL 不改。

後續使用者另裁示「可以B+C」。本次只將新範圍與流程寫進同一份 spec／plan：M6 僅 Overworld，T13／T14 後進行 8-H triage 與縮範圍 T22；T15–T21 移 M7、FamilyCapabilityVersion 固定 0。8-H 依 SHALLOW_V1／Overworld 提必要項與 waiver 給使用者裁定，沒有自動 waiver。測試／腳本／文件一席審查、免 mutation／本機 full，CI 照常；production 與資料安全門檻全保。已知偶發 AccessDenied／走廊容量連坐適用同 code pin 最多一次新 nonce 重跑，純文件 SHA 不重置既有次數，第二次停報。純文件 CI 僅因已知偶發失敗不重開 task，原 CI FAIL 不改判、不當 PASS。

目前範圍變更待一次獨立窄審 C／I 清零後才接續 T13；T13 仍 OPEN。本次無新的測試／完整回歸／CI 結果，不稱 M6 或 T13 已結案。收尾仍須本範圍完整回歸、整分支 review、docs truth、CI、單人與多人 smoke 各一輪；清單須載明 SHALLOW_V1 與傳送門回原版世界，main 合併／tag／人工代操作另行詢問。


### 2026-11-10：唯一 B+C 窄審採用／T13 候選結案待本 commit CI

五文件凍結 patch SHA256 `195b3f8c7018225b8242c874ced04386bf9760aa1c5e41ceac160694588ba1f8` 經唯一獨立窄審 C0／I0／M0，無必要修正；報告 `.superpowers/sdd/2026-10-24-m6-universe-generation/task13-bc-scope-independent-review-20261110.md` SHA256 `938656a1beec0f9eb25034d94fcc28dc9a0de4d155db0ad26636a55f79c82315`。本段僅轉錄採用狀態及審查允許的出口，不新增設計、觀測或 source 變更。

本次五文件是 T13 候選結案文件，**待本 commit CI，T13 仍 OPEN**。production／client／build／workflow 自 `d0aafb47d574f5fff24f712246bbf3508ec2a635` 無差；其後僅 M2 testmod 與既有 M6 verifier PS1 增量。程式 pin `0abd50e92592e9d50a1c2fa0fe1c944409608c95` 的最終 focused index SHA `0e60b81a3924f0558632249efde25eaf9a599e097813a42e593b6b785735950d`：17 步 PASS，JUnit92、owner4、M2九案雙輪18、native12 phase；CI42675304922 三 job 真 success，CI audit SHA `6771e1dfe37673d2b4e23aa8b92c7ebf6ae20a352a28925b2fee44beef549ed4`。final-data／focused／CI quality 獨審 C0/I0/M0 報告 SHA `738e682dc258619724b2d55f6c1394965ae3e47bacab8b847f40b990bd0f05d6` 仍可採，normal-r2 三格 final 差異的完整 rows／diff 與 UNRESOLVED 診斷不改。

依 B+C 對本次測試／腳本／文件增量不另跑本機 full 或 mutation。原 d0 full 是既有 production 基準，0abd focused／CI 與本次文件 CI 各依其 SHA 分列；80f9、f308 的所有 FAIL 仍原判，不能稱本次已重跑 full 通過。正常 commit／push 後等此文件 SHA 的 build／windows-native／m6-native 三 job 真全綠，才在 ignored STATUS／ledger 及交付記 T13 closed；不為更新這個狀態再遞迴發文件 CI。若仍 FAIL，保持 T13 OPEN 並依既有重跑次數止損回報。T14 未啟，8-H、M6 T22 與人工／main／tag 門檻仍待。


### 2026-11-10：宇宙只有主世界／取代 T20 的宇宙內重生（範圍修訂待一次窄審）

使用者明確改為宇宙只有主世界、Nether／End 共用原版；T15–T19、T21 不排 M6 或 M7，plan 只保留一段未排程構想。U→U 全量比較取消，c7d／e1d 原 FAIL 保為歷史；catalog 未物化 Nether／End descriptor 與 key 不一致待辦取消，不修程式或改已保存資料。這取代同日稍早的「整段移 M7」，不是舊 FAIL 改判。

M6 新順序 T13→T14→宇宙內重生（取代 T20）→8-H triage（只有主世界重新提案）→收尾。重生限定 U 主世界死亡：同 U 有效床點優先；無有效點取 canonical 主世界 spawn 同 block XZ、同 U 安全 Y；U 無法使用 canonical fallback 並記錄。重啟後 U 未載入須依 checked authority 物化以保床點與登入；原版主世界／共用 Nether與End 死亡原版。原版 End credits 另屬非死亡路徑，U 床是否影響回 canonical 先驗、不擅擴 portal task。

只讀固定 1.21 named JAR 的 javap 已定位兩個 proposed production 點：ServerPlayerEntity.getRespawnTarget 的床點 world lookup（offset25）與兩支 canonical fallback；PlayerManager.onPlayerConnect 的 MinecraftServer.getWorld lookup（offset106）在 missing-world fallback 前。plan 已具名 proposed method wrapper 與單 callsite WrapOperation，先回報、未實作；若需第三點先回報，不因文件審查冒 production gate。factory現有 UnmodifiableLevelProperties 來源與 canonical spawn 共用不代替新政策顯式讀 canonical spawn XZ；安全 Y 無解先回報，不擅造平台／換 XZ。

人工 smoke 新建 tracked 待驗清單，含單人／多人與宇宙內死亡重生加一輪，明列從 U 經門到共用 Nether／End、回 canonical 主世界而非原 U 的產品預期；無人工實測或代操作授權，main／tag／人工各另問。spec v1.6／plan／三交接文件與清單待一次獨立窄審；本輪未改 production／testmod／腳本，未新增 runtime／本機 full／mutation。4e600a6 候選 T13 CI42746956981繼續獨立追蹤，不取消或重送。


### 2026-11-10：只有主世界範圍唯一窄審採用

初始六docs tracked patch SHA `bd438fed2fa082d6aacc7440aa2caef739624911b480defd96bf0f422ef90923` 加新 manual 清單經同一席窄審。唯一 Important I1 是 active 主幹仍殘留 future family／capability1／Task15–21 gate；只定向修 spec／plan（delta SHA `7bf3f810c6aea8479b51ecee46ca9bc23ffd9236a907a74657d11c57e9248c97`），未改既有資料shape／authority保證／歷史結果或 source。I1 ADDRESSED，終局 C0／I0／M0；唯一報告 `task13-overworld-only-scope-independent-review-20261110.md` SHA `062ac1acd07c6f073517c50f3cf69adfbaac7ab47ef6bad3e31ec13eb27cff3c`，可採用並只轉錄本段狀態，不另設計／觀測。

Task R 的 getRespawnTarget 與 onPlayerConnect/getWorld 兩點仍為 proposed，已寫 plan 並先回報，沒有 production 變更或實作 gate 宣稱。manual 全部未執行，人工／main／tag 分別另問。T13 候選 commit 4e600a6 的 CI42746956981 尚未終局：已確認 build/m6-native job成功，Windows default與normal步驟成功、M5進行中；尚未三job全綠，不在本文件預寫T13 closed。新產品範圍文件只按 B+C 正常送CI，不以它改寫舊FAIL或重置額度。


### 2026-11-10：Task R 原版優先修正已交付／Task14 開工與預算止損交回

使用者確認 T13 結案及270ea3f；本輪先只修 spec／plan／人工清單，一席獨立窄審 C0/I0/M0（SHA1a40624a243630e9d832cecadc967b41a05785cae6ee4e328401bcdf45fe9ee0）後 commit／push e366571f8bcb66467a1b3624f94f1df709540519，CI42756204211 build／windows-native／m6-native三job真success，API/index SHA1c9165cb7bcde37b6a92effbb31f314785bbeb13b0b5521330fe137feb6f49b6。R撤PlayerManager登入hook、不改UniverseLifecycleService，EAGER bootstrap原路徑／DISABLED不登入物化；有效任意世界床錨原版優先，只exact live U死亡且原兩fallback才同U canonical spawn同XZ安全Y，無解沿原版並記錄。地獄門回canonical，End依原版床回U是預期，不portalhook。8-H加入testmod自身清理A／隔離batch B候選，尚待使用者裁示，不實作。

Task14唯一既有plan設計窄審C0/I0/M0（950cb15060035ed36efce4f64ea5fa597d2c7977405d1eea81e65b0e2e980234），canonical source NOT_COVERED／target native consumer exact界線保。RED原production未動時五個新增JUnit失敗＋正式入口1失敗，原37案PASS；原compile／SETUP／訊息expected/actual反向等均另分，原index e080a6a53811ea8c84d13a1f935a302bd8f6e624b07a0a5db33bbd6da4976d18保。GREEN六production＋既有tests/Flow/PS/README精確19檔，前置118 JUnit／8 M6 GT／13 M4 normal通過（含oldpolicy1/2/3 checked重讀不升版、allocation/catalog encode-decode exact、rootSeed assertion已達），targeted index71b29ed1effe0a46dc9c008fd88d9cf65b223ce49535e1a895a1845db054800f。allocation原『policy3非法』期待首FAIL保，僅依新功能將未知版本負例3→4，原1/2與非法矩陣不改；fresh fix1及roundtrip根採GREEN，不回判舊root。

本機候選 cdb589eb6f84ae8eaccb4aa634cbfb1d5e319540 尚未push，patch470a41b60f25e82b6a95c26707fa22df9d003f69d7f52c4622903cc922120694。首次AllEnabled run076004e4a535423b994b075b7b20b081九鏈27phase(D2/F25)全部PASS/outer0、runtime901.5644451秒；原summary SHA cabe1e7a24c47102813b3852dddcee98ff37170a84d86625ef1e05793889bdb1。原兩same-preset CARS exact；兩toggle兩向new/restart各261 actual CARVERS差格、每側1440rows對new逐格exact、四profile identity true。完整SelfTest479＝原277＋202及末baseline parser PASS，但Stopwatch598.8306253秒>300，outer1，正式BUDGET_FAIL，不能以功能runtime或晚PASS抵銷。首次只報無終局到約441秒才明標budgetFAIL的回報問題已修正。phase3a-delivery-index SHA1f3c8e7ee06cf60acb2b5b540966a54b3cf7e9590ecc071d6ff14cc501dc3423保426原件；normalStop聚合false正確：18normal0與9原表expectedhalt77，27lockReleased真，不能稱全27正常0。Root獨核426SHA與既有ending判式，原index／summary不改。

同設計內只PS1兩計算窄修（halo每2025格JoinPath改局部4region字串／單leaf負控三層獨立copy，其餘nested唯讀共享），parser、52halo105300列oldpath exact及192clone隔離/type/原JSON不變通過；一席code窄審C0/I0/M0（82e2a9aba55f4dc5500dc9360c560f8671efb6e8c1b125d916b1ccc5b1c6ce15）。純修patch9aec74b645df776882f0673006fa25fc61f9015608e8c84e17d5d1eb88413716/source5efbaeca9c23029c156617edd52ef121774ef76f4faba10c610b0f8d2b4f67b1不改479／原277／所有predicate／每次原檔讀SHA／九鏈首末baseline／300秒，沒有parsed或PASS cache。

唯一已審後舊summary唯讀回放 c94cb59142e64858b7e8cc9f7309b6a3 仍BUDGET_FAIL：UTC07:17:55.0759332、300.0827308秒即落marker，核exact自有唯讀pwsh PID55568／建立時間／VerifySummary與SelfTest命令後安全停止，wrapper終局300.3782108秒／verifierExit=-1／outer1。不是parser自然實耗或完整案例結果；stdout/stderr各0bytes，479完成數及首末baseline狀態UNKNOWN。delivery-index SHA3d23de5f5a63673949b5469152135bf835f17b3513385c76ccbb7dcaf37d1f42、原件6SHA已root核；source/input未變、原426及其他18source未變，9locks獨占hash不變、JavaAfter0。原runtime daemon50556在首輪終局後正常Gradle8.8 --stop，與計時分列，非MC強停。

Task14仍OPEN，停止第三輪回放、再改碼、production mutation／第2focused／full／最終spec及quality／Task14 CI；沒有END ALL0或Task14結案。PS1窄修保未提交WIP，三份必要交回文件也未提交／push，避免以狀態commit觸發已知未達預算的CI。既有R三文件已交付不受影響；本段是Task14交回事實，不是新R設計。所有原FAIL／UNRESOLVED／SETUP保原判與root，無新canonical＋U／U-only／client，R／8H候選／人工／main／tag均未啟動。


### 2026-11-10：900秒新裁示／8-H A兩案清理測試片

user以原479全部判對／598.8306253秒為據改SelfTest900秒，CI m6-native SelfTest單step5→15分鐘，本機原inline唯讀owned child wrapper同步900、保存新launch/硬deadline；run-all原531只是直接呼VerifySummary，並無原300 guard，本次沒有新tracked wrapper或改runner。PS效能兩改保留與否交一席，本席選撤回：恢復cdb原功能source SHA04526225e66ba16c3613998bd6862041eed7ad46a9cc06df546caf3430ffca84；5ef原bytes與9aecpatch另保，不刪原兩BUDGETFAIL或移除479／原277+202/首末baseline/任何predicate，不再perf優化。TaskR若增native鏈，先估耗時>900先回報，不自提高。

user採8-H候選A並要求兩案各自獨立batch，在T14full前整合。兩案原已各unique m2_native_offline/m2_pin_capacity，沒有rename造成假隔離；原filter/gates env onlyBatches exact可達且full無filter，不需runner/build/harness路徑。原preflight誤稱gates -ModeGameTest -Batch可用，實際launch前param guard拒（無gate/runtime），保SETUP；改用原process-local JAVA_TOOL_OPTIONS onlyBatches，finally復原，full必無subset。M2只兩case＋專用private helper，三共用NativeEntry/TrustedSpace/TrustedSpaceRun全文與cdb exact，71宣告case/batch/tickLimit不變、default166全保，production容量／case期待／timeout不變。

offline原真離線/cohort/KEEP_CURRENT/epoch0/效果/保護斷言後sameGameProfileUUID真重連，正式recovery/trustedTeardown→release/ack/保護解後complete；pin-cap原255items+1player256合法、第257拒/RETURNING/epoch1全保，有價items真teleport保類型數，自身partial/失敗保primary＋cleanup錯、無法清即停。admission與prepare共用唯一原10秒deadline，其他return/release原10秒及90000/tickLimit100000不變；admission可等合法退休但不超原界線，入overflow後每tick/key點map唯讀只ownSID，foreign則FAIL只清自己，無反射mutate/reset/dropSpace、無清他案、不棄物品。4096根因仍未證，不把此次cleanup PASS或slot值當根因證實。

M6Compile與兩正常單案、combined、最終共用deadlinecombined皆PASS；最後root f241f848a7a44fb797f220a415846936 XML2/2零bad/outer0/default166/exactrestore，兩案CLEAN ownSpaces/ownLeases/liveSpaces/protectedSlots0、releaseAcktrue。兩testmod有界失敗路徑控制保原M2_OFFLINE_CONTROL_PRIMARY／M2_PIN_PARTIAL_CONTROL_PRIMARY FAIL，且正式自清理；控制屬EC6C4950較早source，final僅共用deadline3處變更後combined重驗，不冒控制在final重跑。全部原件索引dd4d2ec4f94440d816dd66e3ca491ab78c025f64ad2f74906f4ab0e10d9b969a。

本輪測試CI片唯一獨立review C0/I0/M0，報告SHA050d0e5128834874cc18758c5a8359630730ba00e29565438895c56a78ab8c39，核final source M2 1b5a3dcd25a26f4783133c3135e0eee756a1af6eff94ad17144f552b9aa972c1、final28 M2 classes、正常/controlled scope與原件。root亦核5source/7gate所列SHA，single seat不代Task14 production總出口：900回放、雙focused、必要production mutation、同pin full自然ENDALL0／outer0、最後spec/quality兩席、push與同SHA三job仍待，Task14OPEN。R未實作、其他8H待原triage，無client/人工/main/tag。


### 2026-11-10：Task14 本機出口及最終兩席完成，待本 commit CI

**2026-11-10 Task14 候選結案：本機原門檻與兩席審查完成，待本 commit CI，仍 OPEN。** 最終 code pin `5ab3aa4fe6f4ddb63e0469b7c2148b56b8f1b9ff`（本輪 code range `e366571..5ab3aa4`）；同 pin 雙 focused、八個 production mutation 具名 KILLED 並 byte-exact REVERT，完整 `run-all-a45fe11f8f8b4ff8bf5218966b65bf1f` 自然 `END ALL exit=0`／outer0。spec C0/I0/M1、quality C0/I0/M0；M1 只涉及 toggle GameTest 的 nested preset 診斷標籤，實際 parameters/provider/FP 與 native 權威不受影響，保留原碼並入 ledger 待裁示。full 的 default166／normal13／legacy1／M5 60／M6 21、JUnit1115（零 failure/error，2 個既定 NonWindows skip）、recovery25／collapse69／main-only／artifact 與九鏈27 phase／479反例均通過；本機 ci-rule fixture 不當成遠端 CI。首 full `e0165a45b6fe4b7f9bd7b4e978dbbbea` 因 M5 overlap 的原子移動 AccessDenied 保留 FAIL；唯一同 code 重跑額度已用 1/1，文件 SHA 不洗額度。Java0、六個正式 restore exact、58 個 owned root 鎖可獨占已核；CI 三 job 真全綠後才在 STATUS／ledger／接手入口記 T14 closed。只完成 T14 即停，不開 Task R、8-H triage、client、人工 smoke、main 合併或 tag；舊 FAIL／UNRESOLVED 全保。

正式設計與產品內容仍是 policy3 的 random／same-seed／toggle 三類、Overworld only／capability0。舊 policy1/2 checked 權威不升版不重抽；toggle 保 source root seed、source snapshot 不變，只翻 builtin preset/provider、保 GenerateStructures 並重算 FP。沒有 production registry/factory/mixin/schema 變更。

同 clean 5ab 雙 focused 每輪118 JUnit／8 M6／13 M4normal／四條 native 12 phase，案例集合 exact。交付 `task14-focused-5ab-20261110/phase4-delivery-index.json` SHA `80d283a48260f097a4f05dfa7297ce44dcd1a35b9297775f201f3c761702705e`；主控逐項核2512原件hash及raw XML／24phase receipt。

| Production mutation | 結果 | 新root（均位於本plan SDD的 task14-mutations-5ab-20261110/） |
| --- | --- | --- |
| M1 selector退2 | KILLED／source、owner全nested class與jar byte-exact REVERT | `m1-selector-de9c2b909e7f49229eb29ac57239d356` |
| M2 policy版本拒絕撤除 | KILLED／source、owner全nested class與jar byte-exact REVERT | `m2-policy-5d1f26fb2afd4888ae230842eca04916` |
| M3 toggle誤用random seed | KILLED／source、owner全nested class與jar byte-exact REVERT | `m3-rootseed-ab7bb994328a4970baae201f3c28e71a` |
| M4 resolver不翻preset | KILLED／source、owner全nested class與jar byte-exact REVERT | `m4-preset-212ba1ae3c7c41939f656a7dd23e47f3` |
| M5 resolver覆蓋GenerateStructures | KILLED／source、owner全nested class與jar byte-exact REVERT | `m5-structures-711b22abb642484887c8259f5688ca94` |
| M6 intent放行非法target | KILLED／source、owner全nested class與jar byte-exact REVERT | `m6-intent-496f857c48f54dc5a29e5aeeccdfbc3b` |
| M7 allocation放行非法policy/profile | KILLED／source、owner全nested class與jar byte-exact REVERT | `m7-allocation-677ae5c45257412c9b0798d71a63fc98` |
| M8 catalog忽略provider/preset配對 | KILLED／source、owner全nested class與jar byte-exact REVERT | `m8-catalog-8e40953163614c92861c694e1f56fc83` |

八個具名殺手分別命中正式selector入口、直接policy constructor、已合法建立intent後的root seed、合法source經resolver的preset/flag、直接intent／allocation／catalog constructor；沒有拿compile/SETUP或前層遮蔽冒殺。總交付 `task14-mutations-5ab-20261110/phase5-delivery-index.json` SHA `dc7a6f0ad797c3ea366aa64a5b46a41e9f0bfe951a8f38fba9cff063c6bf34cb`，原件index SHA `5631a895afd2991275d69eb46762683cc57b75bce96138ef4977c1f733c1d051`；主控獨核raw XML／actual mutant hash及555 source／1102 compiled class還原。工具端M2 launcher replacement曾於變更source前parser SETUP，原錯保留，純語法修正後新nonce，未算KILLED或full重跑。

首 full `run-all-e0165a45b6fe4b7f9bd7b4e978dbbbea` 在Windows m5-gametest失敗、outer1／無END ALL；60案僅 `prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection` 失敗。同case/SID與stack指向 CheckedNbtFile.write 原子移動 projections.dat.tmp→dat 的 AccessDenied，production維持fail-closed／200tick退避。已過的前段不抵免後續；原root永久FAIL。依使用者一次fresh full例外，保留原件／restore與Java0後在同5ab從頭重跑一次；counter已用1/1，之後任何正常FAIL停報，pure docs不重置。首失敗交付SHA `9ca07889db139fe096b9fb4111bd14f28452dc24022f937d427d1f72006fa018`，主控精確簽章ruling SHA `d886fa0955af3bbb06c01ce95a05622ca19c502e6522e23b9686b049623b7986`。

唯一重跑 `run-all-a45fe11f8f8b4ff8bf5218966b65bf1f` 於20:37:18+08自然 `END ALL exit=0 HEAD=5ab3aa4...`／outer0，實耗4220.4702249秒。正式 summary SHA `ed95056b87951def18b18d37adadd03dcef1b4d80cc0c5fe7d3a5854ff1e48c5`；console SHA `83153e12c8bf8a2db0d6958826631d1fdcad4e2197aa8558d84136da508e9cb5`。default166、normal13、legacy1、M5 60、M6 21原XML零failure/error/skip；JUnit1115零failure/error，僅2個原oracle明許NonWindows skip；M3 4+5、recovery25、collapse69、main-only PASS、release/sources testmodHits0。兩M2 case各一次，三CLEAN及一ADMISSION全own/live/lease/protected slot0、releaseAck；全default最高slot4≤歷史PASS5，仍不把4096因果記為已證。

本次 native producer `beb0ae46b81e46cd8e412ba05e002ffb`／HEAD5ab：九鏈27phase PASS、DIAGNOSTIC2／FUNCTIONAL25，18正常exit0＋9預期halt77，27 lockReleased真；summary SHA `504a0443c880fef5edb4038b02473281b1be68f0c6f9b7e62cf966aa4cbc5f04`。ci-rule native-green與最後原summary均真479（277+202）及末baseline PASS，不拿本機CI規則fixture冒遠端CI。summary整段壁鐘382.812531秒，沒有個別SelfTest Stopwatch，不能把此值冒精確SelfTest耗時；既有900秒owned唯讀回放自然544.3705029秒採舊cdb producer、5ab reader，界線保留。

最小full交付 `task14-full-5ab-retry1-d73435c7399542848bb1b06e55646a13/full-delivery-index.json` SHA `636f6f694d219c8f49d41518ad41b6a3856a8f3447acbd025b6da2c18ddecd89`（46原件links/hash）；555 source exact、6正式restore manifest exact、58個owned root鎖可獨占／bytes不變；本次唯一自有GradleDaemon經identity核對正常--stop，最終Java0／porcelain空。未複製全root或另製生成world manifest。

最終兩席：spec C0/I0/M1，`task14-final-spec-review-20261110.md` SHA `f3f2330803e5bc629bf4afd2cf697bf54c920a72016334efa4b36c06345536b3`；quality Approved C0/I0/M0，`task14-final-quality-review-20261110.md` SHA `b89921263f0493614960bfd2374090bc27de63a584361b29239b10d9c4f9f68a`。M1為 M6ProfileProbePhases.java:986 的target nested preset診斷標籤仍取source值；actual parameters/provider/FP與native嚴格判定不受影響，未見consumer據該tag決定PASS。controller保留精確5ab full pin，記待後續使用者裁示，不宣稱已修、不拿該tag作preset證據。

兩席已讀final完整rows／差格：兩toggle各261個actual CARVERS差格，兩原same-seed跨側CARVERS exact，各側重啟exact。本次same-amplified final一差(521,24,524) granite/andesite，新hash `427ce479bd879cac0edf8b8d1a6f44efea9334fbaf231dde85a27a5c18dd0239`；toggle final各167差。全部reviewItems維持UNRESOLVED／NOT_CLASSIFIED，不建立baseline/容差，不推論feature writer根因；canonical source native consumer仍NOT_COVERED／target-only，SHALLOW_V1與其他覆蓋限制不外推。

**本節候選狀態：待本 commit CI，T14 OPEN。** 正常push後必核該SHA的build、windows-native、m6-native三job真success，再於STATUS／ledger／接手入口記closed；不得沿用e366的CI或把本機fixtures當遠端。C/I已清零，不因M1新增fix/gates。本輪僅T14，TaskR／8-H triage／client／人工smoke／main／tag均未開工；所有舊FAIL/UNRESOLVED及本次e016原判保留。


### 2026-11-10：Task14 同SHA Windows CI失敗，OPEN／停止交回

**2026-11-10 Task14 OPEN：同SHA CI的Windows M6正式入口案失敗，已止損，不修碼／不重跑。** code pin `5ab3aa4fe6f4ddb63e0469b7c2148b56b8f1b9ff`，遠端候選 `c1b4ea78115e3bbe81bdd2ecb258e39cf4cbf4e0`；[CI42820518452](https://github.com/Ben513242/QuantumChamber/actions/runs/42820518452) attempt1終局：build success、m6-native success、windows-native failure。具名 `m6universegametests.formal_entry_freezes_policy_three_without_changing_older_sessions`，21案僅1失敗：有限等待逾時，FULL／loaded／ticking／tickingFuture=false；own slot0、chunks182、FULL=0／loaded=0／ticking=0。這是目前觀察到的就緒等待簽章，根因未查，不歸已知AccessDenied／AABB偶發。Windows M6執行與集合驗證兩步失敗，2/3 job不能抵全綠；T14從未closed。Windows FAIL artifact與raw XML/log已保存、digest讀回相符，見本plan SDD `task14-ci-c1b4ea7-20261110/terminal-ci-verification.json`（SHA `951b62f929d124bf7a9e49197b8cbf32f0542743ae9ebc28bc95f62a47a6a130`）。同clean5ab雙focused／8個production mutation KILLED及byte-exact REVERT／完整 `run-all-a45fe11f8f8b4ff8bf5218966b65bf1f` 自然END ALL0／outer0與spec C0/I0/M1、quality C0/I0/M0原判保留。首full e016的AccessDenied FAIL及唯一重跑1/1不洗，舊FAIL／UNRESOLVED不改判。為遵守本輪停止指示，交回文件只在本機commit，不再push觸發新CI；最新Git/Java/鎖狀態以ignored STATUS首段與交回索引為準。下一步只等使用者裁示此CI失敗處置；不開Task R、8-H triage、client、人工smoke、main合併或tag。

候選四docs經唯一窄審C0/I0/M0（SHA `21fa7f303c6445b4c48915d64a3c9d9ca767608b11a707c9d56dbee14832ecc8`）後commit／正常push `c1b4ea78115e3bbe81bdd2ecb258e39cf4cbf4e0`，遠端讀回exact；排除四docs後551個tree entry對full pin5ab mode/type/blob exact。此候選始終寫「待本commit CI」，沒有提前close。

CI42820518452（head c1b4ea7、push、attempt1）已completed/failure。build job129844605874 success；m6-native job129844606216 success，原log真9鏈27phase／479 PASS（SelfTest在15分鐘步驟內自然成功）；windows-native job129844606318 failure。Windows原default／normal／M5均完成，失敗步驟是「Windows M6 JUnit 與獨立 GameTest」及「確認 Windows M6 精確集合與隔離」。raw M6 XML21案僅formal_entry_freezes_policy_three_without_changing_older_sessions一案failure，並非以舊S1A diagnostic欄位冒充本次根因。

原XML：位置(-1017730,-60,11939593)，有限等待逾時；FULL=false、loaded=false、ticking=false、tickingFuture=false；chunkDebug含minecraft:empty／ENTITY_TICKING，worldTime/serverTicks302；own SID f64eefd3-5343-410a-9964-65375298b2cb、flushed/current RETURNING、slot0、chunks182、FULL0/loaded0/ticking0。只記原錯與狀態，不把worldTime當等待耗時、不推論policy3產品錯或純fixture偶發，不追內部生成時序、不加probe、不改timeout／production。依使用者「其他FAIL立即停報」停止；同code唯一重跑1/1早已用於本機e016→a45，CI沒有rerun，也不藉pure docs重送CI。T14尚OPEN，不能借純文件偶發不重開已結task的規則倒推closed。

保存：Windows artifact13768837100 ZIP SHA `4fbf9a80cedb21156f1c56d7c294c71a8d02536548677b7750116f00ae4e963f` 與GitHub digest exact；raw M6 XML SHA `706b7c5787a134c4f26d7d2bfb82ef9528e269c08c1409c10ca11fed0f21965d`，job raw log／terminal run&jobs／原ZIP及分析均在同CI root。terminal-ci-verification SHA `951b62f929d124bf7a9e49197b8cbf32f0542743ae9ebc28bc95f62a47a6a130`。CI runner只保存實際上傳的證據，不假稱已保留未上傳的完整遠端world。

CI native PASS原artifact13768121649 ZIP SHA `b48c4b726abc292d55806cba4c0e9f098acac60a9d08ce1f9337eed1b8ad94ee` digest exact，269 JSON原bytes保留。既有final診斷送同spec席補記，新增C0/I0；報告 `task14-ci-c1b4ea7-20261110/native-final-diagnostic-review.md` SHA `03e61621882329f9f920e419d1f5f59782e512d62fc0f5f16e8a1a26eca9686a`。CI未新增local full未見hash；same-amplified target由本機427ce…回35809…，new/restart各對local一格差，CI跨側0；兩toggle仍final167／CARVERS261、每側重啟exact。全部UNRESOLVED／NOT_CLASSIFIED保留，原f3審查及原本機差格不改，不推根因、不建baseline。這項補記不能取代失敗Windows job或放行closed。

本輪收尾只保存原件、更新交回並驗local Java0／鎖／porcelain；此後不開任何runtime、review新修法、Task R／8-H triage／client／人工／main／tag。交回文件僅本機commit，遠端仍c1b4ea7；不push，避免以文件SHA觸發同code新CI。後續調查或修正範圍交使用者裁示；M1診斷標籤待辦亦未修。


### 2026-11-11：Task14 Windows M6 失敗的根因與拆 batch 修正（Claude 接手）

使用者 11-11 指示「由你繼續接手實作」，T14 改由 Claude 實作。上一段 CI42820518452 的失敗已查到根因。以下是依 CI log 與耗時對照的推論，要等本 commit 的同 SHA CI 才算確認。

**根因**：T14 新增的正式入口案 `formal_entry_freezes_policy_three_without_changing_older_sessions`，和 `toggle_owner_settings_match_both_frozen_presets` 排在同一個 batch `m6_universe_profile`，同一 batch 的案例會同時執行。
- 正式入口案在 tick 2 拉桿入場（ARMING），同一個 tick 內呼叫 `NativeEntry.trustedTeardown` 拉 LOW 進入 RETURNING。之後從 tick 3 起輪詢，用固定 10 秒的 `System.nanoTime()` 牆鐘等待 presence 變成 NONE（`M2CorridorGameTests.java:3249`、`3268`）。
- toggle 案在 server thread 上同步物化四個 U：CI log 的 `M3_UNIVERSE_LOAD` 出現在 13:34:53.04、55.51、57.94、13:35:00.35，每個約 2.4 秒。
- 這段期間 server 幾乎不 tick：失敗當下 `worldTime=serverTicks=302`，13:35:03.8 出現「Running 8602ms or 172 ticks behind」。走廊 slot0 的 182 chunks 因此一個都沒載入（FULL／loaded／ticking 都是 0），10 秒一到就判逾時。
- 失敗後，slot0 在 13:35:17 被下一批的另一個 session（b999cccf）重配，表示失敗案的 session 之後已不再佔用 slot0。這只證明 slot 已釋放，不能區分是正式返還完成，還是其他清理路徑釋放的；「返還只是比 10 秒慢」屬於推論。

耗時對照（秒）：

| 測試 | 本機 `run-all-a45fe11f…` | CI Windows |
| --- | --- | --- |
| toggle_owner_settings_match_both_frozen_presets（同批） | 0.601 | 7.356 |
| formal_entry_freezes_policy_three_without_changing_older_sessions（同批） | 2.119 | 10.795 FAIL |
| formal_entry_freezes_policy_two_on_flat（同流程，batch `m6_universe_random`） | 0.906 | 2.307 PASS |

policy2 對照案走同一個 `trustedTeardown` 流程，也斷言 policy3，在沒有宇宙物化同伴的 batch 裡通過，所以不是 policy3 的 production 差異。production 自 `5ab3aa4` 之後沒有改動。

**修正** `d73de9c`：只把正式入口案的 `batchId` 改為 `m6_universe_profile_entry`，加一行註解；案例名稱、斷言、`tickLimit` 與 10 秒等待都不變。README 的 focused 命令和 plan 的 T14 Files 表、CLI 句同步新 batch。M6 run 以 `startsWith("m6_universe")` 選案，CI 範圍檢查以 source 宣告解析，所以案例數仍是 21。

**驗證**（依 B+C 純測試改動：一席審查、必要 targeted 與 CI；不另跑 mutation 或本機 full。production 基準仍是 `5ab3aa4` 的 `run-all-a45fe11f8f8b4ff8bf5218966b65bf1f`，自然 END ALL 0）：
- 同 pin `d73de9c` 的 M6 GameTest focused 兩輪都 PASS：
  - `focused-t14-entry-batch-r1-0a6d6cb99ee345ec98b6d59afb224b9c`
  - `focused-t14-entry-batch-r2-5de1d8401d934389806d567167e3e569`
- 兩輪的結果都是 scope 宣告 21／實跑 21、0 failure；正式入口案 0.313／0.305 秒。
- 兩輪的 runtime log 都顯示 `m6_universe_profile:0 (1 tests)` 與 `m6_universe_profile_entry:0 (1 tests)` 依序執行：r1 在 15:08:37／15:08:48，r2 在 15:10:29／15:10:40。
- 一席獨立審查 C0／I0／M2，報告在 SDD `task14-entry-batch-review-20261111.md`。
  - M1：「根因未查」字樣，由本段與接手入口補記。
  - M2：共用的 10 秒牆鐘等待仍然脆弱，只記為風險，不放寬。

**出口**：本 commit push 後，同 SHA 三 job CI（build、windows-native、m6-native）全綠才記 T14 closed；若失敗就停下回報，不重送。以下全部保留原判：
- CI42820518452 的 FAIL；
- 首次 full e016 的 AccessDenied FAIL 與已用掉的重跑額度；
- 兩次 BUDGET_FAIL；
- 所有 UNRESOLVED。

spec review 的 M1 診斷標籤仍未修。Task R、8-H triage、client、人工 smoke、main 合併與 tag 都沒有啟動。

**殘留風險**：`whenUntil` 用的是牆鐘，不是 tick 數。之後如果有案例在同一 batch 裡長時間同步佔用 server thread，同類逾時可能再出現。新增 M6 案例時，會同步物化宇宙的案例，不要和依賴走廊返還的案例放在同一 batch。


### 2026-11-11：Task14 結案（CI 42978379270 attempt 2）

CI 42978379270（head `715c47688115760dd1fdcdeb6b1852ff2f67e11e`，code pin `d73de9c`）：

**attempt 1**：build、m6-native success，windows-native failure。
- Windows M6 獨立 GameTest 21/21 PASS，`m6_universe_profile_entry` 單獨執行，拆 batch 修正在 CI 生效。
- 失敗來自步驟 6 Windows default：166 案中 8 案失敗，全是 M2 走廊 `_windows` 案例。
- 步驟 9 的 `M6_SCOPE_MISMATCH（default）expected=166 actual=166 缺[] 多[]` 是 default XML 含失敗的連帶。

8 案的失敗過程：
- 首案 `native_shoulder_parrot_stays_on_shoulder_in_corridor_and_returns_windows` 在 10 秒牆鐘等待逾時（ARMING、slot2、chunks182、FULL182、loaded106）。同案在 c1b4ea7 那次 CI 的 default 步驟（該步驟全過；該次 windows-native 因 M6 正式入口案失敗，原判不變）要 9.593 秒，本機 `run-all-a45fe11f…` 4.011 秒。
- 之後逾時案例不清理自己的 session，新 session 的 slot 從 2 爬到 6。
- `trusted_lateral_geometry_south_windows`、`trusted_lateral_geometry_north_windows` 兩案報「局部實例／完整 AABB 容量不足」，其餘 10 秒逾時。

這和 `43cb99b`、`f30853a` 屬同一 Windows default AABB／走廊容量連坐家族；最近 6 次 Windows CI（43cb、e338、0abd、f308、c1b4、715c）有 3 次中。

停服 log 的「expanded coverage」是整個 run 的**峰值**（`CorridorPageManager.java:129–130` 的 `peakCoverage()`），不是停服當下值。通過的 run 峰值也有 3858（0abd、e338），所以不能當洩漏證據。原件存 SDD `task14-ci-715c476-20261111/`：job log、default 與 M6 XML、batch／slot 時間線。

**使用者裁示**（AskUserQuestion）：
- 「重跑失敗 job 一次（建議）」：`gh run rerun 42978379270 --failed`，用掉 code pin d73de9c 唯一一次已知偶發重跑額度。
- 「可以，列為 8-H 必要項（建議）」：題目是「根治 Windows default 偶發時，可以調整 M2 走廊測試共用的 10 秒牆鐘等待嗎？」，該選項說明原文是「在 M6 收尾前做：放寬或改寫這些等待，並讓逾時案例清掉自己的 session 以免連坐。數值依 CI 實測耗時定，經審查後才採用，只改測試。」已寫入 plan 8-H 節。

**attempt 2**：windows-native job 130400524850 success，步驟 6–13 全部 success：Windows default 166、normal 13、M5 60、M6 21 全過；M6 步驟內另有 legacy 1 案 `isolated_legacy_schema2_then_empty_schema3_authority_gates_windows` 也通過。build、m6-native 沿用 attempt 1 的 success。

**T14 closed。** attempt 1 的 FAIL、CI42820518452 的 FAIL、首次 full e016 的 AccessDenied FAIL、兩次 BUDGET_FAIL 與所有 UNRESOLVED 都保留原判。spec review 的 M1 診斷標籤待辦仍未修。下一步是 Task R（宇宙內重生）；8-H triage、client、人工 smoke、main 合併與 tag 都未啟動。


### 2026-11-11：8-H 必要項「M2 走廊等待餘量與失敗連坐」（待本 commit CI）

使用者 17:26 指示「現在開始排 8-H 的走廊等待修正然後再進Task R」，所以排在 Task R 之前。授權原文見 plan 8-H 節。

**設計**：SDD `8h-m2-wait-budget-design-20261111.md`。窄審第一輪 C0／I3／M4，修訂後複審 C0／I0／M2。三項 Important 已修進設計：
- I1：觸發範圍只限逾時，不含 ready 內的一般斷言失敗，忠於「逾時案例」的授權；
- I2：前置查詢包在 try/catch 裡，不遮蔽原錯；
- I3：補上未修正版的本機強制 RED 基準。

**修正** `c410975`，只改 `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`（+40／-8）：
1. 兩個預設 `when` 的牆鐘上限由 10 秒改為 60 秒。`tick>=90000` 上限與所有明確傳入 deadline 的等待都不變。
   - 依據：與 M4 `when`、`CohortWaitTrace`、`CohortFailureCleanup` 既有的 60 秒一致；約為 CI 最慢通過單段（9.6 秒）的 6 倍。
2. `NativeEntry` 在 5 參數建構子末尾登記到以 `TestContext` 為鍵的 `IdentityHashMap`。同一 context 第二次登記改標 AMBIGUOUS；`close()` 只移除自己。
3. `whenUntil` 的逾時分支：逾時錯誤仍用 `context.assertTrue(false, 原訊息)` 產生，捕捉後處理。
   - `hooks!=null`：照原樣丟出，經外層 catch 走 `hooks.primary/releaseAfter`。
   - `hooks==null`：交給 `NativeEntry.failAfterTimeoutCleanup`。若本 context 唯一登記的 fixture 仍有本艙 session 且尚未持有收尾，就先記 `CI_W1_COHORT_WAIT FAIL`，再呼叫既有的 `failCohortAfterCleanup`，並直接 return，不再排下一輪等待。
   - 收尾只走正式 LOW／`returnToOrigin`，確認乾淨後關閉連線，最後才丟出原錯。
   - 查詢失敗、沒有 session、已持有收尾時，都照原樣丟出原錯。

**不變**：
- production、案例名、斷言、tickLimit；
- ready 或 condition 內的一般斷言失敗；
- `Error`；
- `TrustedSpace`／`TrustedSpaceRun`；
- offline／pin-cap 自有的 10 秒 `await`；
- M4 自有的 `when`。

**本機證據**（SDD `8h-m2-wait-forced-20261111/`）：
- **RED-forced**：在未修正的 `ee398ad` 上，把 `native_shoulder_parrot…` 第一個等待的 condition 暫時改為 `() -> false`。
  - gate：`gates.ps1 -Mode GameTest`，root `gate-red-forced-8h-08f174ef662c4c4a8575297b693907f2`。
  - 結果：166 案中 5 案 FAIL。強制的 1 案逾時當下是 SUPERPOSITION，slot 2。另 4 案是連坐：entity_placement（slot 3，loaded 92／182）、lateral_north（AABB 容量不足）、custom_hidden（slot 4，2／91）、custom_single（slot 5，0／91）。
  - 這重現了 CI 42978379270 attempt 1 的連坐模式。
- **GREEN-forced**：在修正版上套用同一強制修改，root `gate-green-forced-8h-c990d43db88a45bb9370c1a4ca767855`。
  - 結果：166 案中只有強制的 1 案 FAIL，60.534 秒逾時，原錯訊息不變。
  - log 依序出現 `CI_W1_COHORT_WAIT FAIL`、`CI_W1_COHORT_CLEANUP BEGIN`，19 tick、516 ms 後出現 `FINAL phase=CLEAN`：presence NONE、journal 無此 SID、玩家回到來源 bbox、連線已關、保護已解除、space 已清。
- 兩次強制修改都先存原檔，gate 後逐位元組還原並核 SHA：未修正版 `1b5a3dcd…`，修正版 `68bc51d5…`。
- **GREEN-normal**（乾淨 pin `c410975`）：
  - default：`gate-green-normal-8h-c410975-3b443e6fd71c4f719fe3bf2a5bde4e0d`，166/166，沒有觸發任何自清；
  - M6：`focused-green-normal-8h-m6-c410975-064521dd245544e0aa18038ff764c640`，21/21；
  - 上次 CI 失敗的 `native_*` 案中，有 5 案本機為 2.8–5.4 秒。`native_cohort_walks_and_returns_west_windows` 總耗時 69.3 秒，但它是多段 cohort 等待，每段各有自己的 60 秒上限，不是單一 `when`。
  - M4 normal 與 M5 依賴同 SHA CI。

**審查**：程式一席審查 C0／I0／M3（SDD `8h-m2-wait-code-review-20261111.md`）。三項 Minor 記為殘留：
- 靜態登記表會保留未 `close()` 測試的引用；
- ARMING 狀態的逾時沒有被直接強制驗過；
- 靜態欄位的位置。

**殘留**：
- 一般 `TrustedSpace` 案逾時仍不自清。CI 42978379270 attempt 1 的 8 個失敗案中，有 3 案用 `TrustedSpace` 系列，角色不同：
  - `native_projection_identified_before_budget_split_cancel_windows` 用一般 `TrustedSpace`，逾時後留下 slot 4 的 ARMING session，不會自清。這是本片沒有涵蓋的缺口。
  - `trusted_lateral_geometry_south_windows`、`trusted_lateral_geometry_north_windows` 經 `withTrustedSpace` 使用有自己清理機制的 `TrustedSpaceRun`（`M2CorridorGameTests.java:252–254`）。失敗訊息只有「局部實例／完整 AABB 容量不足」，屬於在建構時拿不到容量的受害者。
  - 其餘 5 案是 `NativeEntry`：shoulder_parrot、cohort_walks_west、entity_placement、custom_hidden、custom_single，本片已涵蓋。
- 「通過的案例留下 session」只記為觀察，不在本片處理。

**出口**：push 後同 SHA 三 job CI 全綠，才勾選 plan 的 8-H 這一項；若失敗就停下回報。之後進 Task R。

**結案（2026-11-11）**：CI 43003044905（head `6c14dbb`，code pin `c410975`）attempt 1 三 job 全 success：Windows default 166、normal 13、M5 60、legacy 1、M6 21 全過，windows-native job 130463615833 的 log 中，`CI_W1_COHORT_CLEANUP … waitLabel=when` 為 0 行，代表沒有觸發自清。該次 CI 中 `native_shoulder_parrot…` 的案例耗時是 10.628 秒，超過舊的 10 秒上限；60 秒上限提供了餘量。案例總耗時不一定等於單段等待的時間，所以這只是旁證。plan 的 8-H 這一項已勾選。


### 2026-11-11：Task R 設計（落點照原版），待窄審

（設計當時的紀錄。窄審後已採用，實作見下段「Task R 實作」。）

使用者在 AskUserQuestion「在宇宙裡死亡且沒有有效重生點時，落點要怎麼決定？」選「只換世界，位置照原版（建議）」，取代 spec §5 原本「同 block XZ、自訂有界安全 Y」的策略。spec §5、plan Task R 與人工清單已依此修訂。設計在 SDD `task-r-design-20261111.md`。

**固定 1.21 native gate（javap）**：
- `ServerPlayerEntity.getRespawnTarget` 的 `MinecraftServer.getOverworld()` 正好兩處：offset 99 交給 `TeleportTarget.missingSpawnBlock`，offset 116 交給 `new TeleportTarget(world, entity, transition)`。有效床或錨在 offset 94 就 return。
- 整個 jar 只有 `PlayerManager.respawnPlayer` 與 `EndPortalBlock.createTeleportTarget` 呼叫它。
- 兩個 fallback 的位置由 `ServerPlayerEntity.getWorldSpawnPos(world, world.getSpawnPos())` 計算：依 spawnRadius 取點，或在同一 column 上下調整。
- U 的 level properties 包住主世界 properties，所以 U spawn 就是原版主世界的 spawn 座標。

**設計重點**：
- production 只有兩個新檔：`ServerPlayerUniverseRespawnMixin`（`@WrapOperation` 兩個 `getOverworld()`，`require=2`）與 `UniverseRespawnService`，另在 mixins.json 加一行。
- 判斷條件：死亡世界是 checked flushed catalog 對應的 exact live U:OVERWORLD，而且 `resolveActive` 取回的是同一個實例。符合時只把 fallback 世界換成該 U；位置與「床不見了」提示等欄位都由原版產生。
- 測試：JUnit 測泛型 `decide`；新 GameTest batch `m6_universe_respawn` 跑 13 個情境；6 個 production mutation。
- 不新增 native 鏈。此時尚未實作。


### 2026-11-11：Task R 實作（待本 commit CI）

**設計窄審**（SDD `task-r-design-review-20261111.md`）：一席三輪。
- 第一輪 C0／I4／M8：案例數自相矛盾、M4 是等價突變、`respawnPlayer` 留下新 entity、重啟床點的涵蓋說法不成立。
- 第二輪 C0／I1／M2。
- 第三輪 C0／I0／M0，設計採用。docs commit `9f4edba`。
- 修訂結果：13 個獨立 `@GameTest`，M6 宣告數 34；M4 的殺手改為 JUnit；情境 13 在測試內清理；重啟床點改由人工清單驗證。

**RED**（SDD `task-r-red-20261111/`）：
- HEAD `9f4edba`，production 未改，只新增 GameTest 類別並登記。
- root `focused-task-r-red-901375e0d7ec4d6d92ea1ea22470b87d`：13 案中 4 案 FAIL，都是「實際 minecraft:overworld」：
  - 情境 1：沒有重生點；
  - 情境 6：床被拆；
  - 情境 12：兩個 U 兩個玩家；
  - 情境 13：`respawnPlayer` 整合。
- 其餘 9 個控制情境 PASS。

**實作** `e9fdbf5`：
- production：
  - 新增 `ServerPlayerUniverseRespawnMixin`：`@WrapOperation` 包住 `getRespawnTarget` 內兩個 `MinecraftServer.getOverworld()`，`require=2`，只換傳給原版建構式的 world；
  - 新增 `UniverseRespawnService`；
  - `quantumchamber.mixins.json` 加一行。
- 測試：
  - 新增 `UniverseRespawnServiceTest`；
  - 新增 `M6UniverseRespawnGameTests`，batch `m6_universe_respawn`；
  - testmod 的 `fabric.mod.json` 登記新類別。
- `decide` 的判斷順序：
  1. 原版世界：原版，不查 catalog。
  2. 不在 server thread、查詢失敗、flushed 沒有這筆：原版。只有 `quantumchamber:universe/…` 樣式的 key 才記 WARN。
  3. descriptor 不是 OVERWORLD、current 與 flushed 不同、U 不是 ACTIVE、ACTIVE 實例不是死亡世界：原版，並記 WARN。
  4. 以上都不成立：換成死亡的 U，並記 INFO `UNIVERSE_RESPAWN_FALLBACK`。

**GREEN**：
- GameTest root `focused-task-r-green-gametest-85360ef2…`：13/13，有 5 筆 `UNIVERSE_RESPAWN_FALLBACK`。
- JUnit：10/10。第一次 JUnit（root `focused-task-r-green-junit-1de8c65e25944d4f9ef8e1583162729b`）10 案中 2 案失敗，原因是測試的 key 常數取名 `UNIVERSE`，遮住了同名 enum 常數；改名為 `UNIVERSE_KEY`，production 不變。

**兩席審查 `e9fdbf5`**（SDD `task-r-code-reviews-20261111.md`）：
- spec 席：C0／I0／M3。
- quality 席：C0／I1／M8，另有 S-1：
  - I-1：`lookup` 沒有單元測試，有兩個突變會存活。修正 `1ead254`：`lookup` 改為 package-private，接收 catalog 與 `resolveActive` 函式；JUnit 用經 NBT 讀回的真 `UniverseRegistryState` 加三案。兩個突變補為 M7、M8。同時移除 spec 席 M2 指出的同義反覆測試，JUnit 共 12 案。
  - Minor：除 M-6 外都已處理。M-6（把 key 格式判斷收進 `UniverseKeys`）不採，因為要改白名單外的檔案；改在註解指向 `UniverseKeys.world`。
  - S-1：U 的 spawn 若在海上，沒床死亡會照原版落到海底。使用者選「接受，照原版落海底」，docs `aa9b45f`。
- 複審 `1ead254`：
  - quality：C0／I0／M0。另附一項文件同步提醒（不計入 Minor）：M-4 改變了 log 規則，plan Interfaces 要跟著改，已在 `a81ffaa` 補一行。兩項極小建議（一個沒被讀取的欄位、catch 內的 `addSuppressed`）只記為殘留，不改程式，避免換 pin。
  - spec：C0／I0／M2。spec 摘要表的舊字樣已在 `a81ffaa` 修正；spec、plan 中「尚未實作」等過期說法在本 commit 更新。

**mutation**（code pin `1ead254`，SDD `task-r-mutations-1ead254-20261111/mutations-results.json`，SHA256 `ed0611cfd8b85360602631aae8805a5f34c95972ce4b0d4e9a4ab0de31ea6484`）：8 個全部 killed，逐位元組還原並核 SHA。

| 編號 | 突變 | 殺手 |
| --- | --- | --- |
| M1 | mixin 一律回傳原版主世界 | GameTest 情境 1、13 |
| M2 | 在 server thread 上就直接判 UNIVERSE | JUnit 沒有 catalog 記錄的案例、GameTest 走廊死亡 |
| M3 | 拿掉「ACTIVE 實例等於死亡世界」的檢查 | JUnit `differentActiveInstanceIsRejected` |
| M4 | 拿掉原版世界的提早 return | JUnit `vanillaWorldsNeverQueryCatalog` |
| M5 | 只包 offset 99 那一處 | GameTest 情境 1（沒有重生點） |
| M6 | 只包 offset 116 那一處 | GameTest 情境 6（床被拆） |
| M7 | `currentMatchesFlushed` 固定為 true | JUnit `lookupReportsCurrentDifferentFromFlushed` |
| M8 | `lookup` 跳過非 EAGER_ENABLED 的記錄 | JUnit `lookupIgnoresAvailabilityForFlushedDisabledUniverse` |

M1–M6 先在 `e9fdbf5` 跑過一次，全部 killed（SDD `task-r-mutations-20261111/`）。

**雙 focused**（HEAD `aa9b45f`，code 同 `1ead254`）：
- M6 GameTest：`focused-task-r-pin2-r1-m6-d64ba4e8bb934adfa0f952c2e4b20e06`、`focused-task-r-pin2-r2-m6-3c9c3d844ca4400d944e476deb84e0ff`，兩輪都是 34/34；
- JUnit：`focused-task-r-pin2-r1-junit-b643e2b4ea9149a8aae48a6facb190f5`、`focused-task-r-pin2-r2-junit-8599dbfae91c42559f30ab70204740cc`，兩輪都是 12/12。

**完整回歸**：`run-all-c3f5f4705d584008b26d2b6b0505cc1e`（HEAD `a81ffaa`，code 同 `1ead254`），外層 0，自然結束於 `END ALL exit=0`。
- `SUMMARY default=166/166 windows=76 legacy=1/1 m5=60/60 m6=34/34 m6pace=38 normal=13/13 perTest=43+13+1 junit=1127 failures=0 errors=0 skipped=2 windowsCheckpoint=17 release=570entries/523classes sources=266 testmodHits=0 mainOnly=PASS m3=4+5 recovery=25 m5collapse=69 m6native=DIAGNOSTIC:2,FUNCTIONAL:25 ci=win:PASS,ubuntu:PASS`。
- 其中 `ci=` 是本機 workflow 規則自檢，不是 GitHub Actions 的結果。
- `summary.json` SHA256 `86f2b46d9c8dc1187f3b1fc1a475415def2639d9ebf9b6c4782be13a3ceb2744`。
- 與 T14 的 `run-all-a45fe11f…` 相比：M6 21→34、JUnit 1115→1127，差額正好是本片新增的 13 案與 12 案；default 166、M5 60、legacy 1、normal 13 不變。
- 原生鏈 9 條 27 phase PASS，其中 7 條有名為 restart 的 phase；SelfTest 479 反例。本片沒有新增 native 鏈。
- M6 gate 的 runtime log：`UNIVERSE_RESPAWN_FALLBACK` 5 筆，與 GREEN 相同；`UNIVERSE_RESPAWN_KEPT_CANONICAL` 0 筆。

**殘留**：
- 沒有測試直接斷言 log 欄位（spec 席 M3）。
- 重啟後床點有效、海上 spawn 落海底，由人工清單驗證。
- T14 spec 審查的 M1 診斷標籤待辦仍未修。

**出口**：push 後同 SHA 三 job CI 全綠，才勾 plan Task R 的「具名正反控制」與出口兩項並記結案；「重啟登入仍在同一 U、床點有效」要等人工 smoke。CI 失敗就停下回報。之後進 8-H triage。

**docs 審查**：結案文件 `33dc315` 經一席審查，第一輪 C0／I1／M4。I-1 是 plan 的重啟床點項被提前勾選，已改回 `[ ]`。修正後複審 C0／I0／M1，該 Minor 依建議處理：具名控制項也等 CI 全綠才勾。

**結案（2026-11-12）**：CI 43079591580（head `33dc315a54ed2a7138e85a60ba90ade83587f560`，code pin `1ead254`）attempt 1 三 job 全 success：
- windows-native（job 130728309236）：default 166、normal 13、M5 60、M6 34、legacy 1 全過。`UNIVERSE_RESPAWN_FALLBACK` 5 筆、`UNIVERSE_RESPAWN_KEPT_CANONICAL` 0 筆，`CI_W1_COHORT_CLEANUP … waitLabel=when` 0 行。12 行 `M6_SCOPE_MISMATCH` 都是 `EXPECTED_RED` 自檢。
- build（Ubuntu，job 130728309171）：default 166、M5 60、M6 34、legacy 1 全過。
- m6-native（job 130728308724）：`M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479 HEAD=33dc315…`。

plan Task R 的「具名正反控制」與出口兩項已勾選，**Task R closed**。「重啟登入仍在同一 U、床點有效」與海上 spawn 落海底留給人工 smoke。下一步是 8-H triage；client、人工 smoke、main 合併、tag 都未啟動。
