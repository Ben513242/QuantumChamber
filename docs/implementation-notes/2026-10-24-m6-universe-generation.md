# M6 宇宙生成與完整維度家族：實作紀錄

日期：2026-10-24（台灣）。分支 `codex/m6-universe-generation`。最新（2026-10-25）：Task1–3完成；Task3 code ac085c8、本機雙review C/I=0，CI commit4d8fc66雙平台原件已核PASS。接續Task4 Stronghold競態，先補scalar fixture Minor；生成／維度家族程式尚未實作。

## 使用者確認的範圍與順序

完整 M6，依序分段驗收：測試腳本安全與生成競態 → random seed → same-seed fresh／generation profile 與 registry/API → 完整 Nether／End family、portal／gateway、respawn 與同 DimensionRole 投影／跨越。M7 literal branch／snapshot 不納入。子代理使用 SOL；本輪採 `gpt-6-sol`／`gpt-6.1-sol`。

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
