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
