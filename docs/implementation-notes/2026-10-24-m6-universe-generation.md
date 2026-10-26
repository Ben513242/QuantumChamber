# M6 宇宙生成與完整維度家族：實作紀錄

日期：2026-10-24（台灣）。分支 `codex/m6-universe-generation`。最新（2026-10-26）：Task1–7及Task7 CI64d完成；Task8仍8A。spec v1.3／正常遊戲Model B與absence設計已獨立Approved採用，A7未跑，完整B/G2/native gate未過、無production/8B。最新v6–v13限定結果與下一步見文末；不把文件採用當實作結果。

## 使用者確認的範圍與順序

完整 M6，依序分段驗收：測試腳本安全與生成競態 → random seed → same-seed fresh／generation profile 與 registry/API → 完整 Nether／End family、portal／gateway、respawn 與同 DimensionRole 投影／跨越。M7 literal branch／snapshot 不納入。子代理使用 SOL；本輪採 `gpt-6-sol`／`gpt-6.1-sol`。

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
