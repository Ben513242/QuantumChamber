# M7 正規回程：實作紀錄

本檔記錄 M7（正規回程）的範圍裁示、Step0 與之後各 task 的實際狀態。binding spec 與 plan 已經獨立審查清零採用，T1 已以同 SHA 五 job CI 全綠結案；使用者已授權 T2 起依審查清零連續推進，production T2 正在實作。本檔的範圍裁示（含本輪追加與釐清）是 M7 範圍唯一的使用者決策來源，規格據此定義技術契約；流程與授權另見「流程指示」節與 AGENTS。

## 使用者新裁示：T2 起連續推進與新止損（2026-11-13）

來源：本 Codex 使用者對話；root 原文檔 `user-t2-continuation-ruling-20261113.md`，非技能或 Claude 建議稿。使用者原文：

> T1 結案已核實（CI 43418385083 五 job success、src/main 無改動、PoC 已移除、歸類合理）。請開始 T2，之後依「審查清零就直接往下做（建議）」連續推進。
> 1. I2 保留金是正式設計的正確性問題，要在 T2 以 production 方式解決並附 RED／mutation 證據，不能再移到後面。
> 2. 驗證標準維持「M6 同等」；審查意見超出時列殘留，不擋關。
> 3.（Claude 建議的止損）同一個 task 整改兩輪後，Important 數量沒有下降，或同一個驗證工具連續第三次非預期失敗，就停下回報，不要繼續疊修。
> 4. 每個 task closed（同 SHA CI 全綠）時簡短回報一次：task、code pin、CI run、殘留、下一個 task。

現行流程：

- 從 T2 依已審 plan 連續推進至 T8 已授自動化／整分支審查／docs；每 task C/I 清零且同 SHA required CI 真全綠才 closed，直接下一 task。T1 後「本輪不 T2」及 I11 單次處理限制均為歷史，已被本裁示取代；不重開 T1 或 spec／plan 審批。
- I2 是正式資料正確性義務，在 T2 由 HomeJournalBudget／正式 schema6／共用 writer 修復，附 fixed32 保租 root latch／COLLAPSING 具名 RED 與直接 production-owner mutation；不得延期或當超 M6 殘留。
- M6 同等限定驗證工具與證據做法；不豁免 binding 功能、strict schema／合法來源／預算／所有共用 writer、production RED/GREEN/mutation/focused/full/review/同 SHA CI。超標工具意見列殘留，不默認塞 T4。
- 每 task 同範圍記 N0、每整改 N1/N2…（Important 總數含新項）；任一連續兩整改輪窗口未下降即停報，不做下一輪。不得藉重命名、nonce、docs 或無證據重新分類洗數；Critical 永不帶過。
- 驗證工具依穩定 problem ID 記連續非預期失敗，同問題第三次即停，安全收回相關自有 owner 並保全原件；expected RED／negative／mutation 正確拒絕分列不算工具缺陷。production bug 與工具 bug 依證據分開；既有 Evidence3／NativeStartup3／main-only2 歷史原判保留，已移除框架不復活，不任意合併無關問題或拆簽章迴避。
- 每新 task fresh implementer＋一位獨立 task reviewer（spec＋quality 同席），fix 回原作者。controller `/root/m7_t2_onward` 是 note／AGENTS／plan 核取／progress／STATUS 唯一 writer。每 closed 短報 task／code pin／CI run／殘留／下一 task。
- 人工代操作開始（方式已選 Claude）、main ff-only、tag、PR／LICENSE、worktree 退役仍各自未授。正常 feature push 已授；既有 W10 producer 只核身分／hash／非reparse／owner／locks，不重建或重套 patch。保全部原 world／FAIL／UNKNOWN，修正用新 nonce/root。

## T2 N1降至1項；I06共同總量守門進fix2（2026-11-13）

- 原唯一席N1已終局：C0/I1/M0，N0=5保留；I01～I05與M01均ADDRESSED，新增T2-I06。fix1把共同room改逐對檢查後漏既有64 slot／4096 chunk coverage合計守門：一列舊租約remove未checked，仍在flushed占用，但新SID可拿不相交第65格。這是本輪資料層退化，不是超M6工具要求。新ID與原五項分列，原fixed32/48及五項修正證據不洗。
- fix2由原作者依同SDD/task-2-fix2-brief.md核正式RED→最小共同聯集計數→GREEN/mutation/focused，仍保I02每snapshot原occupies/碰撞且同租約跨snapshot不重複算；沿既有allocator，不造新權威/公式。原席N2 C/I0後才full/CI；N0=5→N1=1已下降，N2未評，止損表完整保留。source現9d1、reviewBASE5e2b2c6，未啟修後full或CI。
- fix1 production候選 `9d1e6bab4840ca658191a6c005fbd172d7bcef06`，14檔，原席fixBASE71c47ba、實作起點a34d4c0；作者report SHA `7308DA43449B063EF0BB23EEA685F7C2DA453788FEA11B738224E04AF3AA5F83`。六類focused 11/6/52/13/41/26共149、Windows M7 21JUnit/4GT、11個自測按預期完成；controller `t2-fix1-focused-controller-readback.json`核實際XML/hash/九raw及pin9d1/平台，M7零skip。
- fix1 I01～I05及reposition六個直接owner mutation，controller `t2-fix1-mutations-controller-readback.json`核各實體XML/hash/目標assert、保存before/mutant bytes、還原SHA，六個owner現行bytes也等於變異前bytes。I01與reposition均為正式caller目標assert，非cleanup timeout；I03/I04原history RED具名2FAIL、原件SHA `E009BEA0…`。原首I01仍保正式remap漏撤reserve導致權威不符＋fixture最後僅cleanup逾時兩層因果，不冒充目標RED、不回填新marker。
- mutation site多行CRLF/LF不匹配在source/runtime前拒絕的原事件1保留；單行唯一命中修正後新nonce才KILLED，不重設fullDriver2/3。controller讀fix1 proof曾誤用初版JSON欄位xml產生KeyError，是已保留的只讀操作事件；改按實際xmlSha/mutated/hits核原件，未重跑mutation、不改source、不當遊戲或fullDriver失敗。
- 現行順序由root調整：先同席N1增量審查，C/I0才啟修後完整full，然後同SHA全部required CI；未減最終門檻，不在未清source中間pin先耗full。N0=5歷史保留，N1未評；五項「作者已修」尚不是獨立採用。M01診斷修改一併待原席核。T2仍OPEN、T3未啟，未push/CI。
- controller `/root/m7_t2_onward`；BASE `6032027`，續行狀態 docs `670c782`；fresh implementer `t2_implementer`（gpt-6-astra/high/fork none）。source 初候選 `8c6e542`、兩個未知版本負例修正 `363d4df`，首次未知寫入占用修正後最終 production code pin `81bd13e7ccebe167fcb36341c1416384e305ebec`；均未 push。T1 不重做、B activation guard 仍封至 T7。
- 新 `JournalSession`／`HomeSessionRecord`／`HomeJournalCodec`／`HomeJournalBudget`／`HomeReceiptStore` 與共用 journal owner 完成候選。current/flushed/quarantine/pending 由同一資料源管理；pending exact-attempt 未確認時保守占用，向後提供 typed `pendingSessions()`。T3 busy、T4 root fence/CLOSE event owner、T5 startup space 留各自 task，不因本候選宣稱 M7 完成。
- 正式 schema6 writer 三案行為 RED、歷史 API 缺失 compile RED 分列；I2 可編譯正式 owner 特定行為 RED 真 3 案恰1FAIL，SHA `2629BA16…`。I2 同32租約 current50,397→COLLAPSING＋root latch50,445，保留48；64租約亦48，1租約2,010、8租約162。另第一次 HOME atomic move 後 readback未知的占用缺口真 RED1/8→GREEN8/8，未確認時不借用玩家／Chamber／slot，修後 pin81。
- 9個直接 production owner mutation：I2、raw重鍵、tracker、未選remap、CLOSE、舊append、receipt覆寫、空journal降版、pending占用，均由具名正式測試 KILLED，保存 before/mutant/還原bytes與SHA。原8項已controller逐XML/hash及保存source bytes核，pending第9原件見 report。變異時間點與其後改碼分列，不把早期測試冒充最終候選。
- 最終 pin81 focused 原根 `t2-final-focused-75621f8aa7784c70a159fa3b93309e03`：五類JUnit 8/4/52/13/41，共118案，XML failure/error/skip全0；Windows M7 16JUnit＋3GT，11個 readonly SelfTest正/負例全按預期，兩JAR隔離。controller `t2-final-focused-controller-readback.json` 已核五XML/hash、M7 pin/platform/九份raw hash與實際16/3零nonPass；原件仍在SDD/run，不以build唯一副本採計。
- 必要routine擴檔：兩M7 runner的T1硬編suite改接新集合，保T1既有4JUnit；SlotAllocator既有restore驗證最小共用、SessionRecoveryManager僅typed convergence／全列readback；舊兩類「6=未知」負例依正式升6改未知7，保-1/0、重鍵與5→4 readback全部斷言，直接舊row decoder(6)仍拒。初次 schema5 52案1FAIL保原根與XML SHA `FA41BD70…`，不改判。這些同task同席review，不增加工具標準。
- 真舊reader採 `2255027` SessionJournalStore 原blob `ac764b16b1093399607182e6e6fb57a16b421784`，controller核saved source exact；隔離classes CodeSource，實際 `read` 以具體「journal版本缺失或未知」IOException拒固定schema6（bytes SHA `B22FFD16…`），不是classloading/反射錯誤。最後wrapper將CP950 log當UTF8顯示致outer1，原FAIL保留；只讀正確解碼取證，不重跑Java。`T2_OLD_READER_CONSOLE_ENCODING`連續1；controller另一次UTF8 source誤用預設CP950的只讀操作事件獨立保留，不加/抵/重置原工具計數。
- 既有 W10 producer 未重建／重套。初核02、候選前03均只讀 PASS（固定tag/head/blob/patch/runner/diff exact、detached唯一dirty、祖先非reparse、32鎖可獨占、runtimeOwners0、65001）；正式full前再核。自寫只讀helper首次DirectoryInfo provider屬性誤判FAIL保原件，修正後PASS；不併舊Native/Evidence。staged whitespace檢查抓真格式問題是正常品質RED，修後過關，不是工具故障。
- 初次長SDD EvidenceRoot full在既有CI cwd preflight native267而outer1；成功fixture258字元、失敗265且存在，長路徑列推論，原根與FAIL保留。第二次改回既有短預設 `.superpowers/verification`（不改harness/acceptance/source），HEAD `71c47bad2dbe5e47f7ffb43d72b679fd85a0ea4b`／source81，新root `run-all-611806be445a42148e0a012c293d6bb7`。
- 第二次內層自然 `END ALL exit=0`、summary PASS SHA `83A01553383CD957FA9A342514AA316221F24B021FF5A999BD4333BDFA377702`：JUnit1151（failure/error0，2個既有NonWindows專用案在Windows條件skip；M7仍零skip）、default166/normal13/legacy1/M560/M635、M7 16JUnit/3GT、main-only、M3、recovery、M5 69phase（31個預定halt，不能稱全正常停服）、M6 9鏈27phase、CI規則按各自scope完成。
- 但外wrapper PID2320未自然返回，無run-all子程序僅conhost，exit.txt缺失；不能冒稱outer0。確認同一process handle／start／command+唯一root、無world owner、94把本樹鎖可獨占後，僅受控停止本次wrapper，實際exit -1，自然outer為UNKNOWN；Gradle/Java未動。`outer-before-stop.json`／`outer-stop.json`及外log SHA `E53BF9EF…`保留。停止前可選open-log hash遇file in use一案保留，未當停止必要條件或補假退出碼。controller `t2-initial-full-controller-readback.json`如實記innerPASS／outerUNKNOWN，不採為完整full門檻已過。
- 唯一獨立原席 `task-2-review.md` 首審規格❌／品質Needs fixes，C0/I5/M1、**N0=5**。T2-I01純容量拒絕導致舊candidate/remap取消團隊；I02同SID current遮蔽未確認flushed資源；I03 schema6舊列漏同SID歷史；I04 receipt漏完整wrapper DataVersion exact；I05永久SEALED/root latch不可達組合仍被strict/峰值接受。全部屬binding正式資料/現有caller正確性，不列超M6工具殘留。M01診斷schema名稱可順手最小修，非阻塞。
- 舊full消費source期間全程未改HEAD/source；作者只在SDD準備fix1。controller postcheck07核95鎖（含producer）獨占、runtimeOwners0、固定pins/hash exact，已到安全邊界。整改1由原作者依 `task-2-fix1-brief.md`／`task-2-fix1-design.md` 核具體反例後修正；I02每row沿原 `sameAuthority`／`occupies`，不改已Returned舊M5規則。I01允必要舊caller最小接線；純容量retry不得吞一般IO/型別/深度/重鍵故障。修後需新pin RED/GREEN/mutation/focused/full（含真自然外部0）及同席N1、同SHA全CI。當前N1/N2尚待，N0=5保留，連續兩整改輪未下降就停。
- root固定同一實際full driver計數：長CWD267與第二次outer未返回各保原因/原判，但合計已2/3；不能因換簽章或wrapper名稱重置，修後同driver第三次非預期工具失敗立即停。可選hash／人工source解碼錯另列操作事件，不冒稱遊戲失敗。fix1前已保存原frozen diff與設計原bytes/hash於SDD archive。
- T2 OPEN，未push／CI、未啟T3，沒有人工/main/tag/PR/LICENSE/退役。初候選內層PASS與原fixed32反證修復不抵I5，也不抵整改後完整門檻。

## 使用者async裁示：只補I11後原席清零、push同SHA CI（2026-11-13）

來源：本Codex對話使用者對root具體async問句的明確回覆，非Claude稿或技能。題目原文：

「本機定向已 PASS，但原 reviewer 仍留一項 M6 標準內的 I11：live 終局重讀 HEAD，並核對／使用原件重算的摘要。你限定的一輪整改與一次定向已完成；是否允許最小補齊這項後，原席確認 C／I 清零，再正常 push 看同 SHA CI？」

使用者選：「允許只補 I11、原席確認後 push CI；不再本機重跑（建議）」。

- 同SHA CI `43418385083` 已啟動，實際5jobs；Windows/Ubuntu兩個m7-validation已success，downloaded原件各4JUnit/3GT零fail/skip、7raw hashes吻合，11個SelfTest（正1PASS＋負10KILLED）含I11新五控制真完成。原件索引`ci-43418385083/m7-artifacts-readback.json`／`m7-original-pointers.json`，只是讀JSON/XML/hash，沒有本機重跑。原build/windows-native/m6-native仍執行，整run與T1尚未PASS/closed；不為記錄子gate而commit/push新CI。
- 已依授權正常push：I11 source67bf1a0之後必要狀態docs `57ca71ae35173e784a9f53013b2f5dfeba5548ba` 已push feature，`ls-remote`讀回同SHA；沒有force/main/tag。新source/pushed SHA本機0重跑，舊local PASS仍04d。當次CI初始事件待發現，不重送push；T1仍OPEN、真五job終局待驗。此狀態先留工作區/ledger，不為追寫CI再push。
- **T1已按新M6同等範圍closed，本輪停在T2前。** CI`43418385083` attempt1，head `57ca71ae35173e784a9f53013b2f5dfeba5548ba`，實際5required jobs全部completed/success。primary terminal JSON SHA `12FC3BD15165BAFBE783A701A8ECF34E0AB4597B9EB15515D6CC1766C3699C96`，root另親核`root-terminal-run.json` SHA `A3749D0B8104D58FCE12831BCC2A1D69FD75EC5C08828029823C01A56489A160`。原席source67 C0/I0/M0、舊local04d七步與新CI57分開；沒有新本機重跑、CI重跑或提前宣稱。
- 真CI新M7兩OS各4JUnit/3GT/11SelfTest及7raw hash、兩JAR隔離已原件讀核；舊兩OSdefault166/M560/M635/legacy1、Windows normal13及M6 main-only三層、M6 native9鏈27phase/479反例皆有terminal log。六artifact metadata/digest/完整log與兩OS raw定位都在`ci-43418385083/`。
- 實測run41分02秒；job M7 Windows3分33秒/Ubuntu3分14秒、build12分36秒、windows-native40分59秒、m6-native24分08秒。舊31.5分只歷史；M7後續正式39/full/production focused/mutation/人工未測，總額UNKNOWN。PoC與退休額外CI=0，不為估時加跑。
- 全部新授權完成後凍結`T1-CLOSEOUT.md` SHA `1474365A5F5F9B1248EF193E87F298CE9B680E501E8D1240F250EBAEBC44597A`（JSON `ADBD2F50482DFE84093EBEA3B7FED3BAF61F97FB8AEA720598683F16EC2F5A7D`），包含I11閉合、原席/基線/CI/逐項分類/殘留不擋/實測/未驗；I2移T2仍未解、I7超M6殘留不自動T4、舊FAIL/UNKNOWN/計數都留。此最後狀態/plan勾選只本機記錄，不再push造第二CI；原作者/controller idle，不T2/main/tag/人工/PR/LICENSE/退役。
- I11原同席靜態審查已採用C0/I0/M0，`task-1-i11-review.md` SHA `644A3F418D332832B8DCD0AC0C35A42BDA53F0AF4F1B22ABD60E3EFC7356AED6`，新source `67bf1a08f1cd85044bf1eba37aaa0322295ea67b` 已達普通push門檻。這是純靜態，不是新SHA本機PASS；既有本機七步仍04d42f43。依async授權將必要待CI狀態docs一併正常push，實際遠端SHA/同SHA CI尚待後續讀回；5required job與兩OS新11controls均待真跑，不能先closed。
- 原作者最小靜態候選已本機commit `67bf1a08f1cd85044bf1eba37aaa0322295ea67b`（BASEa6df06e，只有m7-ci-gametest/run-all，39插10刪），已交原唯一reviewer `review-a6df06e..67bf1a0.diff` 核I11與新breakage。新增五個同scope CI-only負例及expected列`t1-i11-static-ci-controls.json`；本機任何compile/test/parser/SelfTest皆未跑，新code只能由原席靜態＋將來CI驗，舊local PASS仍04d42f43。
- 這次只取代I11等待hold：原作者最小修live發布／終局重新讀實際HEAD，以及saved summary案例/pace/artifact對raw重算proof核對或讓run-all直接消費validated fresh proof。歷史VerifyOnly仍可明示ExpectedHead讀舊SHA，不強要求目前checkout同SHA。不改JUnit/GT/production/PoC/T2/其他殘留，不造新框架。
- 可寫必要同scope CI SelfTest負例（如saved案例變空），但只將來CI跑；現在絕不本機compile/JUnit/GT/SelfTest/ParseExact/其他定向重跑。只讀source/git diff/check/hash、必要本機candidate/docs commit允許。
- 既有本機七步PASS仍是`04d42f43b693ba012f1f00409c541fa290980511`與63.6456469秒，不冒稱新補SHA有本機測試。新補候選交原同席只讀C/I0後，正常push（已授不再問）/ls-remote核讀，追同SHA實際5個required jobs與新雙平台JUnit4/GT3及舊全部M6門檻。
- CI FAIL保log/artifact metadata/原根，不自動rerun、不改碼重送，M6同等不沿用舊偶發重跑額度。CI真全綠且新T1 scoped C/I0才可關T1；即使通過也本輪不T2/main/tag/人工/PR/LICENSE/新worktree/退役/Native39/full/W10。舊FAIL/根/計數全保。
- CI終局先在ledger/工作區記真狀態，最後必要狀態docs可只本機提交，不為追寫本次CI再push造成無限CI。本輪controller仍是note/AGENTS/plan/ledger唯一writer。
## 使用者新裁示：T1收斂、驗證標準改為M6同等（2026-11-13）

來源：2026-11-13使用者在本Codex對話回覆T1 fix1結果，由root照錄於SDD `user-t1-m6-standard-ruling-20261113.md`；不是Claude派工稿／技能。以下為題目、所選選項及說明原文：

- 題目：「T1 接下來怎麼走？」
- 選：「收斂 T1，驗證標準定為「M6 同等」（建議）」
- 說明：「T1 只留 JUnit（大小上限、硬連結發佈）＋GameTest（入口守門）＋M7 的 CI 接線；取消 T1 的 Windows 原生 PoC，當機重開證明交給 T4 正式矩陣。驗證工具以 M6 已在 CI 驗過的 probe 做法為標準，審查意見超出 M6 同等標準的列為殘留、不擋關；「保留金算錯」這類屬於正式設計的問題移到 T2 修。代價：改掉你一小時前「T2～T3 保留 PoC」的裁示。」

### 新範圍與本輪程序
- **本輪終局：本機窄batch七步PASS，原同席C0/I1/M0，I11仍阻T1，所以未push、無新CI。** 報告`task-1-m6-convergence-review.md` SHA `2605C4C173EE02EEA68D4592165149634208A45D666BB7037992AAD225157905`：live終局未重讀真Git HEAD，及最終full嵌未與raw重算結果比對的saved案例/pace/artifact；依M6 GT124/142/native720與精確集合，屬M6同等內，不降殘留。歷史VerifyOnly允許明示ExpectedHead，不要求舊收據等於目前checkout；本批無實際漂移或原件損壞，不改PASS。
- 完整凍結`t1-m6-convergence-final.md` SHA `C97911A3BAC4EAE66769A8037EB7A55039D93F75A62C6309F8CBA49056BBA1B3`（同名JSON `F4F9E8018704FE8E182A1F48ADB018D07DB8DE5ABE01A5E12CAF717DFF242D60`），含原席12歸屬、新scope／18檔、4JUnit3GT、一次7步／raw hash／root／codeSHA04d42f43／63.6456469秒、未pushCI、殘留依據與新成本模型。PoC/退休額外CI皆0；新CI沒跑，舊31.5分只參考，總時長UNKNOWN，不為估時push。
- 這次一輪／一次已用完。root正向使用者等一個僅I11最小補正的async裁示，答案前不動source／測試／push；目前沒有該授權。不自動補兩行或重跑。原作者/controller idle，T1 OPEN、不T2，raw與舊FAIL/計數全保留。結果只作必要本機docs記錄，並未發布。
- **新M6同等一次窄batch已本機PASS/exit0，仍待原席實作C/I0及真CI。** code pin `04d42f43b693ba012f1f00409c541fa290980511`，UTC `10:35:27.1370031`→`10:36:30.7826500`，63.6456469秒。JUnit4/WindowsGT3、真relative root/readonly、真正workflow正0/PASS與三變造各1/FAIL逐例核、最後原件hash不變，共7步全PASS；正例step的6個M6等價readonly controls完成，無native/main-only/class快照/clean/full。
- 原根`t1-m6-convergence-run-82ca010a6cfd4f45809e0e0f14629645`、raw XML/log/JAR/inputs/summary與workflow各result保留；postcheck701檔hash、Java owner0與GT鎖獨占。原同席只讀核`review-0b9bbb7..04d42f4.diff`及新scope，未再測；只有新範圍C/I0才正常push，同SHA所有required CI全綠才T1 closed。此刻未push／Ubuntu未真驗，不把本機PASS抵CI或舊FAIL改判。
- 新收斂候選`04d42f43b693ba012f1f00409c541fa290980511`已本機commit（18檔、取消六框架檔與JavaPoC、inventory/default/CI native引用移除），未compile/test/push；M6基準與production不改。窄batch清單`t1-m6-convergence-batch-plan.json` SHA `6A665538113C6332B8AE4ADC74F8E0C33253CEE6E002E03EFC3FB4EC3B656050`已由controller核codeSHA/driver/fresh根/expected；只一次JUnit4+WindowsGT3+readonly/真正workflow正負/SelfTest，第一呼叫relative root驗F1，不新consumer框架。原根`t1-m6-convergence-run-82ca010a6cfd4f45809e0e0f14629645`；首非預期FAIL就停不修/重試/不push，PASS才同席核新scope後按授權push追CI。
- 文件/分類已由原席採用：初審f22db82 C0/I1/M1，僅T4固定十三cut來源缺失；docs-only `12fcdca98252d4b4f36c41630dc6f2f610cfe1df`補回原固定矩陣，補正採用C0/I0/M1，報告`task-1-m6-standard-plan-review-addendum.md` SHA `655E1F14DFBE6103EF6B8C386FB8B20376C7B3A87DDC2577FBF154768D555AB5`。逐12項原席表`task-1-m6-standard-disposition.md` SHA `ABBED551AD24FE1599605617D41CA6499E694F0CB9DF9CE4694204C28C7E4A45`：I2移T2，I7超M6不擋，I4/I11/F1/F2保留、native部分移除但安全/FAIL原件/JAR/exact仍保。不是舊code已修；現在才允許原作者一次窄scope收斂，先不試跑，交候選/一次batch核。

- 本次取代舊Windows PoC18、T2/T3 PoC守門與T4退休接線流程：T1原生PoC取消，T2/T3也不跑；T4 Windows正式39仍保留。Ubuntu仍只有JUnit＋GameTest，沒有Linux owner/native/main-only。不是skip／空矩陣／假PASS；exact清單、default full、CI、oracle同步新scope，Git歷史與全原件保留，既有M6 native/main-only守門和斷言／門檻不改。
- 標準具體比對已在CI通過的當前 `m6-universe-probe.ps1`、`probe-common.ps1` 及M6 JUnit/GT/CI入口。分類須固定file/blob/SHA及b26b90c／CI43373504422等已PASS來源，原同席逐項核現OPEN的I2、I4–I12及F1/F2：保留M6內／超M6殘留不擋／移T2正式設計／nativePoC範圍移除。I1/I3已處理歷史也保留，不整批宣稱全關。
- 先本note原文→docs-only plan／必要spec邊界勘誤＋原同席分類採用C/I0→原作者一次scope convergence整改＋事前凍結的一次窄T1定向（JUnit/GT/CI正負）。不原生單pair/18/39、新全class/argfile框架、額外main-only、clean/W10/full。任何非預期工具FAIL立即停，不修後再跑／不push；舊FAIL/根/計數不重置。
- 若本次定向PASS且新scope沒有C/I阻擋，已授權正常push、追同SHA新T1雙平台JUnit/GT及既有全部required M6 jobs；CI真終局全綠才可關T1。CI FAIL原判保留，不自動rerun；「M6同等」不是沿用M6舊重跑waiver。無論T1是否過關，本輪不開T2，回root。
- 超M6意見標殘留並給具體不擋依據，不默認塞回T4必做；若要提高標準另問。使用者的原FAIL/root/XML/log保全規則仍在，clean證據遺失不能豁免。producer保留、不新建／退役worktree，production不改。

### M7_STARTUP_TIME根因的新外部證據

- 使用者提供Claude巡檢：pwsh7.7.0已確定重現，原`ParseExact($s,@(3格式),...)`將`object[]`綁成單format字串；明確`[string[]]@(...)`或單format可行，例值`2026-11-13T09:26:48.506Z`。來源是使用者提供的Claude實測，**不是本controller或root本輪復現**。根因據此補記已知；舊batch FAIL/當時未保底層exception、原UNKNOWN診斷侷限不改寫。
- 此native工具不再是T1入口；T4 plan記明確string[]修法及合法3/6/9位秒接受／錯格式拒絕TODO。不能把cast改碼當作3/6/9已驗。現在只靜態列其他M7同類array-overload，不新增ParseExact重演或日期框架。
## 使用者回覆 T1 checkpoint：驗證縮範圍與一次有界恢復（2026-11-13）

來源：**2026-11-13 使用者在本 Codex 對話回覆 T1 正式全集前 checkpoint**。以下四題、所選選項及說明為使用者看過並選定的原文，由 root 轉交照錄；不是 Claude 稿，也不是技能。root 另存原文於本計畫 SDD 的 `user-t1-scope-ruling-20261113.md`。

1. 題目：「T1 的可行性實證（PoC）每個平台跑幾列？」
   - 選：「B 每門態六個代表切點（建議）」
   - 說明：「每平台 18 列，保留六個存檔階段；未抽到的組合由 T4 正式程式的 39 列全驗。Codex 也建議這個。」
2. 題目：「PoC 要保留多久？（這項影響最大）」
   - 選：「T4 正式矩陣上線後退役（建議）」
   - 說明：「T1 跑完的 PoC 證據留存；T4 的正式 39 列通過後，PoC 從完整回歸與 CI 移除，不在 T5～T8 重複跑。T2～T3 期間仍跑，當作守門。」
3. 題目：「Ubuntu 要驗到什麼程度？」
   - 選：「比照 M6：原生當機重開只在 Windows（建議）」
   - 說明：「Ubuntu 跑 JUnit（含真實磁碟的硬連結發佈與大小上限）和 GameTest（含正式拒絕契約）；不寫 Linux 程序管理、不跑 Ubuntu 原生矩陣與「只載正式模組」檢查。代價：Linux 上「當機後重開」的原生行為只靠 Windows 推論。」
4. 題目：「實作改完之後怎麼恢復執行？」
   - 選：「先改 plan，再一輪整改＋一次定向驗證後回報（建議）」
   - 說明：「依上面裁示修改 plan 並一席獨立審查；再做一輪核心整改加一次定向驗證就停下來回報，不開正式全集。」

### 本輪執行界線（controller 依上述裁示落地）
- **本輪許可已用完、完整報告凍結，現在只等使用者下一裁示。** 原同席`task-1-fix1-review.md` SHA-256 `7A355066BBC88EAC1C355137E649B6BFA10DE2C637F399B977CF3CBC3B84FF6D`：I1/I3 ADDRESSED，I2及I4–I12十項PARTIAL/OPEN，新增F1/F2兩Important，合計C0/I12 open（10原+2新）/新增M0；原核心/plan/scope判定不改。I2仍低估固定32lease保租後繼；I7實際argfile/DLI、I8seal/CI失敗原件等仍有缺口；F1為CI相對evidence跨cwd，F2為workflow正例DOM重存與raw hash契約，皆未以重跑修補。
- 完整交root檔` t1-fix1-final-report.md`（SHA-256 `1AB7931E3AE331D69F7AE3AA1455449F707E20A83B0BCD0D407C2ED17BFBF502`；同名JSON `94AF7F34A7C3F987A8F6BC02BA875047574EDC0B1466C8062F782CF0BFF031C6`），列plan/spec採用原件、12I範圍/修法/本批覆蓋/未驗、17檔/exact集合、唯一batch完整命令/原root/SHA/exit/NOT_RUN與保全。成本檔`t1-fix1-cost-model.md`只用已有44.606秒情境與Win-only/PoC退休公式；本輪native未跑、新CI未測、總時長UNKNOWN，不用失敗65秒冒成本全額。
- 只知離線正例進ParseExact catch拋M7_STARTUP_TIME，底層exception未留，根因UNKNOWN；06原log0bytes不補trace、未重演。所有新tools修正/compile/runtime/重跑/CI/push停止，T1 OPEN；原作者與controller idle，沒有fix2或下一task。runtime code pin b69397b未push；本結果只作必要本機docs記錄，不觸遠端。
- **唯一batch已終局FAIL_STOP，不再整改／續跑。** 實跑HEAD `b69397b4c02085f549f445d763787f5b8e8408b9`，outer1，`2026-11-13T09:03:57.6315490Z`→`09:05:02.8290377Z`（65.1974887秒）。1–5步source／一次compile／compiled-freeze／JUnit6案0錯0略過／WindowsGT3案0錯0略過自然PASS；第6步offline-once出非預期 `M7_STARTUP_TIME`（`m7-contract.ps1:13`）立即停，不是事前negative。7–10 native單pair／artifact／workflow／end-freeze全NOT_RUN，native根未建立，沒有新pair測值。
- 原根 `.superpowers/sdd/2026-11-13-m7-return-home/t1-fix1-run-00b3fe17d408424e9eabd07914b365a0` 的result/partial/logs、raw argv/DLI/stdout/stderr、source/compiled manifest/copies、JUnit/GT XML保留。`t1-fix1-outcome.json`記完整命令清單引用/exit/未跑；controller只讀保全2995檔hash與GT原world全檔、Java owner0、session.lock獨占，沒有重現或清理。
- 新增離線startup驗證工具FAIL保守續原 `T1-NATIVE-HARNESS` 原2→3，非宣稱與舊失敗同code根因；原EVIDENCE-INTEGRITY3/main-only2及全部FAIL/UNKNOWN不清零。不論歸屬本批已停，不能分類後再跑。原作者idle、原唯一reviewer只讀核diff及既有原件；I7 worker argfile／I8 seal兄弟負例缺口仍open，未執行能力不能假關。
- fix1原作者候選已本機commit `b69397b4c02085f549f445d763787f5b8e8408b9`，17檔、src/main無diff，未compile/test/JVM/push；controller核`t1-fix1-batch-plan.json`（SHA `076E85F8F70E8D28767DDBCAABFDEE549D207C47069848C9F299AAAD2A8B6BB3`）/driver/513source副本hash與三fresh根，核定只執行這一次10步定向。batchroot `t1-fix1-run-00b3fe17d408424e9eabd07914b365a0`，不開全集。I7 JUnit worker內部argfile、I8兄弟反例未入seal等已列open，沒有要求再補功能；任何非預期工具FAIL立即停、不修/重試/續跑，PASS也停。
- 採用狀態文件已本機commit `5785f684e9dc44e60b7ccc0bf79b3541fc8189c4`、未push；spec v1.1 SHA-256 `0A93B044009ADCD8096D00C74F99EA596F86A76DA181CA1BCC8BB9E2F12EC4E7`。已回原implementer fix1，只一輪code整改；先不compile/測試，待其本機候選與事前batch清單交controller核，再執行這唯一一次。本輪不啟獨立main-only/Ubuntu/正式矩陣/full/CI。
- 原唯一reviewer已採用文件候選`4132fe5`：plan/spec C0/I0/M1，report `task-1-plan-amendment-review.md` SHA-256 `C457062A04AD863F468CDCC05B09CB533A4566D77A98B824488AC9133FD7A584`。逐I歸屬`task-1-scope-disposition.md` SHA-256 `A29404AA66A460A683792AF9B389946587B124D1FEEF99A014A57E4FBFA7AA0E`：9項全保留；I6/I8/I10僅Linux/Ubuntu/雙OS26shard部分標範圍移除，12項皆仍有待修/待驗義務。原core C0/I12/M2及8D3C報告不改。
- 採用後只更新plan/spec標頭及引用SHA，不改已審契約；AGENTS將本輪現況與舊快照分隔以處理M1。現在可回原作者fix1，但先不compile/測試，先交程式候選及事前batch清單，controller核後才一次定向；未開正式矩陣、CI或push。

- docs-only amendment當前HEAD `4132fe53b92078faf7c16f0bd2460511baa767a1`（`d6caf20`加單批build/快照取得順序自審補正），未push；已交原唯一reviewer靜態審plan/spec及12I scope disposition，未動工具/清單/CI/source，沒有新增runtime。
- docs-only amendment 候選 d6caf208116120b9862b0651f91f018017763f68 已在本機提交、未push；plan/spec驗證勘誤待原唯一reviewer，一切工具/清單/CI/source尚未整改，沒有新增runtime。

- 先僅修改 plan／必要 spec 驗證平台勘誤，不改玩法；由原唯一 reviewer 獨立審查 plan amendment 及 I1–I12 歸屬，C/I 清零後才可動 exact 清單／CI／oracle／工具。原 review 的 C0/I12/M2 與 finding 不覆寫；「範圍移除」須原 reviewer 確認，Windows 與其他真正缺陷不能整項豁免。
- T1 為 **Windows 18 列**、六 cut × 三門態；T4 正式 **Windows 39 列**通過後，PoC 從 default full／CI 退役，歷史原件保留；T2–T3 保留 PoC 守門。Ubuntu 僅 JUnit＋GameTest，沒有 native／main-only／Linux owner 程式。exact inventories 與 CI／oracle 同步新集合，禁止 skip 冒充 PASS。
- 本次只授權原 implementer **一輪核心整改＋一個事前凍結的定向 batch 執行一次**。不開正式18／39、T2、完整回歸、新 CI 或 push；可必要本機未完成候選／docs commit。production 不改。batch 不邊跑邊改；任何非預期工具失敗立即停止，不重試／補修／續跑，安全收回已在途 owned JVM 並留原根。expected negatives 必事前具名。
- 原 `T1-EVIDENCE-INTEGRITY` 保守第三事件、Native2／main-only2 等歷史計數、舊 FAIL／UNKNOWN／clean 事故與來源漂移診斷全部保留，不因本次有界授權重置。batch 終局後不再 fix／compile／runtime／CI／push；原 reviewer 只讀核差異與原件，新增缺口列 open 待下次裁示。
- W10 producer 已存在，這輪不重建、不移除、不跑 full；不 clean 刪證據。Ubuntu 若本機不可真跑且需 push 才有 CI，就如實未驗，不模擬 Linux 或擅 push。現在先做文件修訂與一席 review，工具仍未開始整改。
## 使用者新增正式批前巡檢裁示（2026-11-13，立即生效）

來源：使用者在本 Codex 對話、T1 執行期間貼入的新增訊息，root 照錄於 `.superpowers/sdd/2026-11-13-m7-return-home/user-checkpoint-instruction-20261113.md`；controller 已讀原文。這不是 Claude 派工稿，也不是技能要求；本次不改玩法範圍。

- 使用者原文：「請在 T1 提前核心審查完成後、正式 39 列雙平台批次開跑前，先回報下列四項再繼續」。因此設 **formal-full hold**：先完成本 task 唯一核心審查，再由 root 向使用者回報 checkpoint；在此前不開正式 39 列雙 OS，也不 push 會自動觸發該全集的 workflow。核心編碼、定向驗證與唯一 reviewer 可續，T1 OPEN。
- 四項為：本機／CI／shard 與 T1–T8 時程（實測、假設、UNKNOWN 分開）；T1 非預期失敗（驗證工具／遊戲功能）與未實跑新能力；T1 PoC 與 T4 正式 owner 的重複／不同風險及縮樣選項；穩定 problem ID／第三次止損。controller 整理 `t1-pre-full-checkpoint.md/json`，核心 review 後補凍結版。
- 關於縮減，使用者原文：「這是 plan 變更，只提出選項與影響，不要自行修改，由使用者裁示。」所以沒有自行改 plan、清單或門檻。
- 第 4 項原文：「同一個驗證工具問題連續第三次失敗時，先停下回報，不要繼續疊修。」立即適用；跨 nonce／commit／文件／改名不重置，同問題不拆簽章；expected RED、mutation／negative 被正確拒絕不算非預期工具失敗。有效 GREEN 需直接原件支持。
- 作者初盤目前無已達第三次者；其中 main-only 的 executable=null 與 oracle 誤判合併為同工具問題兩次，再以新 root 真 JVM／三負例直接 GREEN。其他問題與保全事故將逐項列原 root、原判及狀態；controller 尚待完整索引核對，不以作者自報當最終清零。
### root 執行裁定：證據完整性工具第三事件止損（2026-11-13）

依上方使用者第4項原文，root 已向使用者回報，保守把 clean 刪證據、active log hash=null、來源指紋不一致合併為穩定問題 `T1-EVIDENCE-INTEGRITY` 的第三事件並止損。**這是 root 的保守計數裁定，不冒稱三者為同一 code root cause，也不偽稱使用者逐字指定這個 group。**

- 停止 T1 新增工具修正、所有新 compile／runtime／重跑／CI／push；只讀盤點、README／狀態整理、現有候選 diff 凍結與本 task 唯一核心靜態 review 可續。此項取代上方較早「核心編碼／定向可續」的執行許可，不能為了 C/I 清零突破止損；未完成能力、open Important 如實交回使用者。
- 已在途 `t1-core-directed-one-pair`（非39列）於 hold 訊息送達前啟動，後自然完成 Windows crash77／reopen0、鎖釋放；沒有廣殺或新列。完整 chain 44.405 秒、summary 44.606 秒（含匯出與取證成本），原件 summary SHA-256 `C59835C4BF3A9AAC711D96E8B3F8446BC07E1FD8BA0705B8CB3D93FECDB38418`。
- controller 只讀重讀 1660 份 source／compiled raw copy 的 bytes／hash，0 mismatch；manifest SHA-256 `60FD9137D0E6EC752440CCBFFD92D5996A95F72F42ABDCCAE53A3111A2A7854F`，兩 phase prelaunch hash 吻合、8 summary 負例 KILLED。postcheck 留 `t1-core-one-pair-controller-postcheck.json`。這只證單列，不抵正式39列、Ubuntu或真 project clean 存續，也不自行解除任何止損／formal-full／push hold。
- 當前 M7 Java0。單列終局後才允許本機明標未完成候選 commit 或 immutable diff，維持 HEAD／消費檔不在 runtime 中途改動；候選供唯一 reviewer 靜態＋既有證據審查。收到審查即把 `t1-pre-full-checkpoint.md/json` 完整交 root，待使用者選下一步。T1 OPEN，M7 未取消，main／tag／人工皆未授權。
### T1 核心候選凍結與唯一靜態審查（未完成、未 push）

- 本機候選 commit `55c48682279392e23805e08b93c00deb6637db88`（BASE `b26b90c`），訊息明標未完成／hold，20 檔清單見 `task-1-core-commit-files.txt`。controller 已核 `src/main` 無 diff、HEAD／status 符合；只有本 note／AGENTS controller 狀態 dirty。未 push、未有新 CI。
- 原 runtime 在 BASE＋dirty 候選執行，不能稱這筆 commit 同 SHA gate。已知靜態 diff-check 有 `m7-ci-gametest.ps1` EOF 多空行，止損下留原件，不為修小項再改工具。
- `review-b26b90c..55c4868.diff` 為技能產生的完整 immutable range，1 commit／232733 bytes；已派本 task 唯一獨立 reviewer `/root/m7_sdd_execution/t1_core_reviewer`（`gpt-6-astra`／high，fresh context）靜態＋既有證據審查。禁止 runtime／compile／修碼／CI，不追加 reviewer；可有 open Important 如實交使用者，不突破第三事件止損求清零。
- 報告預定 `task-1-core-review.md`；收到後補凍結 `t1-pre-full-checkpoint.md/json` 交 root 回報四項並等使用者下一步。T1 OPEN，正式39／Ubuntu／完整 bundle／project clean 存續／同 SHA CI 仍未完成，所有 hold 維持。
### T1 唯一核心審查終局與 checkpoint 交回（OPEN、止損維持）

- 唯一獨立審查 `task-1-core-review.md` 已完成並由 controller 完整讀取：**規格❌、品質 Needs fixes，C0／I12／M2，全部 open**。原報告 SHA-256 `8D3C8F3A074B04FF9C49CC30AEE4EF7FD2A2B6A545BEC4C85749449ACBCECC27`。這是現有候選 `b26b90c..55c4868` 的靜態＋既有原件審查，沒有新增 runtime／compile／修碼。
- 12 個 Important：I1–I3 的合法 schema6 舊列、CLOSE／後繼最壞預算與 bounded raw/depth；I4–I5 的真正 guard／平台入口及同一 PoC 交易內故障／防夾；I6–I9 的 exact owner、完整消費集合／mutable DLI、空 disk／未綁 startup/owner自測/digest、startup strict；I10–I12 的 main-only classload/JAR角色/CI原件、gates/run-all保全與最終重驗、manifest結構化精確性。精確 file:line 與證據見原報告，不以既有測試 GREEN 當作這些缺口已解。
- Windows 真單列 77／0、兩 receipt hash、1660 raw copy hash 確實存在；但單列不能證完整快照集合／所有 JVM 消費凍結 bytes，JUnit 6/6 的數字也不能證不合法 fixture 的 tracker 最壞上界。正式39列／Ubuntu／完整 artifact bundle／真正 project clean 存續／同 SHA CI 仍缺。T1 三項先導尚未全可採，T2 不放行。
- 四項 checkpoint 凍結交回版1：`t1-pre-full-checkpoint.md` SHA-256 `E70C0F84F139E84A3864C8D3F2ED73343603B4025A3B64D49E5F399415876EFA`；JSON `B465285EE904ECD10C8D690D2F3D9AE1706F6ACF26DEDDF92BB8F47EE64F1E12`。列真單列44.606秒、39列約29.0分本機native條件情境；Ubuntu／新CI UNKNOWN；至少7 local full＋8 CI，舊CI約31.5分×8≈4.2小時只是同速CI情境，驗證總時長仍 UNKNOWN。
- checkpoint 列全6次非預期 runtime 工具FAIL與3事故、所有新能力未驗；A39／B18／C25只為選項，不改 plan／清單／門檻。PoC生命週期另需裁示：目前沒有T4退役PoC明文，若保留則本機至少`8n`、taskCI至少`16n`個PoC pair，正式close從T4–T8另反覆跑；不能只算T1＋T4各一次。
- 依使用者/root止損，不為 Important 清零開 fix／重跑／push。候選55c4868只在本機，遠端讀回仍b26b90c；僅本note／AGENTS dirty。root將四項與審查交使用者選下一步。T1 OPEN、T2–T8未開始，main／tag／人工／PR／LICENSE／退役未執行。
## Codex 接手（2026-11-13，規格與計畫已採用，準備 T1）

- 工作樹與分支已核對：`.worktrees/m7-return-home`／`codex/m7-return-home`，接手時 HEAD `30c0c627d7fb3f205d3a329152d62e6d04cdc773`，工作樹乾淨。
- Step0 同 SHA CI run `43359327038` 已由 `gh run view -R Ben513242/QuantumChamber` 讀回：`build`、`windows-native`、`m6-native` 全部 success。
- 目前採用 binding spec v1.0，SHA-256 `9054C4D75480D18178FD6A6420EBEF7A4ABABE4A55A95E5B4090581CC6F77932`，與清零 v0.3 契約相同、只更新採用標頭。第一個規格文件 commit `03b8c9472f7b1fa67427dbb1d7741b8f3a2bbf42` 包含 spec、本 note 與 AGENTS，已正常 push 並由 `ls-remote` 讀回相同 SHA；同 SHA CI run `43370074593` 執行中，尚無終局，不能採計 Step0 CI 代替。plan 正撰寫，尚未實作。
- binding spec v0.1 初稿 `docs/superpowers/specs/2026-11-13-m7-return-home-design.md` 共316行，SHA-256 `F1087A4A7551D5E0491BB77AF21ADC75811CCED84AA2D04C9D1FF306285ECA15`；第一輪獨立審查 Critical 1／Important 6／Minor 1，尚未採用，已交原作者修訂。plan 與 production 實作尚未開始。本輪沒有執行 Gradle、run-all 或人工驗收，也尚未重建 W10 producer。
- 短期派工與審查證據使用 `.superpowers/sdd/2026-11-13-m7-return-home/`；正式可重跑工具仍須放 tracked `scripts/verification/`，不 force-add SDD 空間。
- plan 初稿 `docs/superpowers/plans/2026-11-13-m7-return-home.md`：334行、8個 task，SHA-256 `00346816839FF702A660040E97C6756D124A0446D1C3AC16B707E13E874F9F8E`；獨立審查中，尚未採用／實作。T1 的 NBT 計量、no-clobber 發布及 B 門 checkpoint 均待實跑，不是已驗結果。
- plan 第一輪獨立審查 `plan-review-1.md`：Critical 0／Important 7／Minor 2，未採用、禁止派 T1。阻擋包含 Windows／Ubuntu 正式入場預期分流、Linux launcher／唯一切點 manifest、既有入場交易順序、正式 remap／來源 checkpoint 接線、全 BlockState JUnit、舊 M5 W4／W6 oracle 與 typed CLOSE 事件入口。交同作者窄修，未擴張 Linux 玩家 checkpoint 能力、未改 binding spec、未執行 runtime gate。
- plan 修稿339行、8 task，SHA-256 `7BE47AD2DB5AB11C034E9B7A502E622A50CE1924D4A367227C1A407632C3135A`；第二輪獨立複審 `plan-review-2.md` 為 Critical 0／Important 0／Minor 0，報告 SHA-256 `596D799B40D37AE7B04B37F1637D0B69612A6BBCBAAE7268A6150851CE8D983B`。前輪九項關閉，採用計畫，依授權直接派 SDD T1。此計畫文件 commit 尚待自身同 SHA CI；T1 的跨平台先導與任何 production 功能尚未執行。
- 計畫文件 commit `b26b90ceefd7827ea907d498a2fc096c873fdaa1` 已正常 push，`ls-remote` 讀回相同 SHA；同 SHA CI `43373504422` 執行中。規格 CI `43370074593` 最近讀回 build／m6-native success、windows-native 執行中，尚無整體通過。本輪 docs gate 只完成獨立審查與文件定向檢查，不冒充 runtime。

### SDD T1：驗證入口與三項可行性實證（2026-11-13，OPEN）

- 計畫文件 CI `43373504422` attempt 1 已終局：HEAD `b26b90ceefd7827ea907d498a2fc096c873fdaa1`，`build`／`windows-native`／`m6-native` 全 success。原件 `ci-43373504422/terminal-ci-verification.json` SHA-256 `2918741462576909603A1AB541BD61A575822A1890844BB6C7741241CACE07EF`，完整 log、run／artifact metadata 與四個 digest 同目錄。實核兩平台 default166／M5 60／M6 35／legacy1、Windows normal13、M6 native9鏈27phase／479反例；這是 plan docs 自身原 scope，不能抵 T1 新增實證／事故後重驗。上方 pending 是歷史。

- 規格文件 CI `43370074593` attempt 1 已終局：HEAD `03b8c9472f7b1fa67427dbb1d7741b8f3a2bbf42`，`build`／`windows-native`／`m6-native` 全部 success。root watch 原件 `ci-43370074593/terminal-ci-verification.json`，controller 重新 parse 核對，SHA-256 `0CB0A84B4A0B8F66841ABDB270E8A09DECA8E96A95FEECB5ECA84DD2A0B768ED`。上方 pending 為歷史；plan CI `43373504422` 與 T1 實證仍獨立，不互抵。

- controller 接手 BASE `b26b90ceefd7827ea907d498a2fc096c873fdaa1`，實查 linked worktree／分支正確；保留既有兩份 controller 文件 dirty。
- 使用 `subagent-driven-development` 與 `using-git-worktrees`，採用本計畫 workspace，`progress.md` 首行記 plan identity；前置掃描已逐 task 及共享檔案／接口成對列出 8＋28 列，沒有需要新玩法裁示的衝突。細節與 routine 技術裁示留 ledger。
- 已派 fresh implementer `/root/m7_sdd_execution/t1_implementer`（`gpt-6.1-sol`／high）；只給 T1 brief 與必要共通限制，不派平行 implementer。報告將在 `task-1-report.md`；之後由 controller 派獨立 task reviewer。
- T1 屬純測試／腳本／build／CI接線，免 production mutation 與本機 full，仍須具名 RED、真 Windows 定向／clean build／隔離、workflow fixture 正負例及同 SHA 雙平台完整 CI 實證。production 尚未修改，任何 T1 gate 尚待終局，不記 PASS；T2–T8 未開始。
- controller 已依 README 重建 fresh detached W10 producer `.worktrees/m6-w10-m5-producer`；native 工具無固定 absolute path 參數，採 README 的 `git worktree add --detach`。m5 tag object／peeled／原 runner blob、patch／runner bytes／LF diff 六項身分吻合，chcp 65001，target 與祖先非 reparse、Java owner0、fresh 無 session.lock，僅 runner 是允許 dirty。原件 `w10-rebuild-precheck.json`／`w10-rebuild-proof.json`；尚未執行 W10 或 full，不能視為 runtime 通過。producer 不退役，後續另問。T8 人工代操作開始前另問，main／tag／退役／PR／LICENSE 均未啟動。
#### T1 首輪 JUnit 事件（尚非 task 終局）

- dirty 候選 HEAD 仍 `b26b90c`；首輪 `t1-junit-first/` 的兩份 XML 實際為 Budget 3 案零失敗、Publish 2 案 1 失敗、零略過，Gradle exit 1。原件與 log 保留，SHA 見 ledger；失敗是 Windows JDK `fileKey()` 可為 null 的測試前提錯誤。
- 原作者改以保留 audit hardlink 與 `Files.isSameFile` 核實檔案身分；新 `t1-compile-2.log` 已自然 `BUILD SUCCESSFUL`，第二輪 `t1-junit-green-2/` 兩份 XML 已由 controller parse 核讀：Budget 3、Publish 2，共 5/5，failures／errors／skipped 皆為 0。log 有既有 deprecation／unchecked warnings，不稱無警告。
- NBT 初測得到合法可達 tracker 邊界兩側與預留公式，但合法後繼自審仍待；native 首組、兩平台與完整 T1 gate 未完成。上述不當作三項可行性終局，也不放行 T2。
#### T1 首組 native FAIL（原判保留）

- chain `spike-close-ordinary-after-checkpoint` 的 root `run/m7-home-0012f18edfaa4d378809a0e2f1f49dec`：crash 預定 halt 77、PID 25684、12.03 秒；reopen PID 17632／start `2026-11-13T06:03:08.579Z`，在既定 1200 ticks 觸發 `M7_CHECKPOINT_READY_TIMEOUT`，halt 78、outer exit 1。整組 FAIL，不以 crash 達切點抵通過。
- failure／startup／latest.log／jstack 原件保留，controller 已核 failure 欄位；hash、owner 與鎖檢查見 `t1-first-native-fail-postcheck.json`。作者正查 test-only fixture 在重啟後 FULL／entity readiness 的根因與 flat generator 設定 log，不改 production、不放寬 deadline，也不視為可洗的偶發重跑；修正後須新 nonce。
- T1 持續 OPEN、三項可行性未全部證明、T2 不放行。
#### T1 第二組 Windows native 局部 GREEN（未結案）

- test-only 自有 runtime ticket 與合法 flat generator-settings 修正後，新 nonce `8895aa8fba8e4552aa9a6d891183f491` 的同一 chain 已 crash exit 77／reopen exit 0；controller 核 summary 的兩個 PID／start、鎖釋放與 `Windows`／HEAD。兩 phase 實測 17.283／12.287 秒，summary 總 29.612 秒（outer 29.773 秒）。原件 `build/m7-second-measurement/summary.json`，SHA-256 `37BC4BB64A2CA3ED9339A18F2ACDBBF97F304A728FEADAD5CB88AF659520666C`。
- 這輪同時改票與 flat 設定，故 root 要求另補最小因果對照／call trace，再判 TIMEOUT 根因是否證實；`No key layers` 另記設定 RED／GREEN，不能混為因果。原 FAIL 不改判。
- 目前只證一組 Windows PoC，全部 39 列、Ubuntu、清單／CI 與隔離門檻仍待。T1 OPEN，不放行 T2。
#### T1 證據保全事故與修正要求（尚未結案）

- implementer 在搬出證據前執行 `Gradle clean`，刪除了三個 `build/m7-*` runner 輸出目錄，包括 phase 磁碟複本、launcher logs、summary。這是可避免的保全疏失；不能以重建件冒充原件。controller 已只讀核 `build/m7-second-measurement` 不存在、三個 `run/m7-home-*` root 仍在；完整具名／nonce／phase／唯一檔案清冊待 immutable `evidence-loss-*` 報告，不可知項寫 UNKNOWN。
- 上述 29.612／29.773 秒與作者後報 ticket-only 69.33 秒目前都屬歷史量測缺口，**不作 gate 或 shard 實測採計**。controller 曾核讀的 summary／8 負例亦因 runner 原件已刪，不作本 task 完成證據。原首輪 FAIL 不變，run 下的 startup／receipt／failure／world 與 SDD top logs 仍保留；現存 primary SHA 清冊為 `t1-evidence-loss-controller-inventory.json`。
- 立即停止 clean／其他覆蓋 build 的清理，先搬出所有現存 evidence，建立並驗 manifest／hash。runner 和 CI 輸出須改到 clean 不觸及的唯一 SDD／run root；hidden SDD 必核 artifact `include-hidden-files` 及 exact 收據查找。保全完成前不恢復 clean build。
- 技術修正後用新 nonce 補完整定向證據，並實跑「clean 後原 receipt／log／hash 仍可讀」正控制與反例，交獨立 reviewer 審查。T1 維持 OPEN，不洗原 FAIL、不採 M6 偶發重跑額度；production 未修改。
#### T1 事故盤點固定（未結案）

- immutable 原件：`evidence-loss-20261113-0620.json`（SHA-256 `24FB865D9FAF635352134461F7248E3480DECB0C33C14F5583B49592EA1775DD`）與同名 `.md`（`176F85E0F4BC861722B16F9D8712527835159E2E1433C63C0633F7F9C09CC6CB`）。三個確切目錄為 `build/m7-first-measurement`／`m7-second-measurement`／`m7-ticket-only-control`，對應 nonce `0012f18e…`／`8895aa8f…`／`d8198b2d…` 的 crash／reopen；刪除的唯一檔完整清單與 hash 為 UNKNOWN，不能還原冒充。
- 尚存原件 `task-1-surviving-evidence.json` SHA-256 `921F351B84C98B9EC0B8F5DC6154F9BA1A800420D3B52EF345344815728700BA`，controller 逐 17 個現存檔案驗 bytes／hash 吻合；六個原 Java owner 不活躍、三個 session.lock 可獨占。世界、runtime log 與 SDD top logs 原件仍在。
- 已啟動的 clean build 後自然 exit 0（1m56s），`t1-clean-build.log` 尚存，目前 95 個測試檔先快照到 `t1-clean-build-snapshot-current`。這不代表事故已結案或 T1 全部隔離／clean 存續 gate 通過。作者目前無 Gradle／runtime、clean 停止；後續改安全 evidence root、新 nonce 與實際存續正負控。
#### T1 完整補充保全已驗（安全修正與新 gate 執行中）

- `evidence-loss-supplement-20261113-0628-stable.json` 共 273 實檔，controller 逐檔重讀 bytes／SHA-256，0 mismatch；清冊 SHA-256 `84FFDF1C7B7AA55FE02C2251F559F234D100F726AF63FB943D39D43A42DE20A1`。涵三原 run 的完整 world／log、95 份測試快照、cleanbuild JARs、SDD RED／GREEN／javap／top 歷史 log；原 run 留安全原地，不搬不寫。
- 前一 immutable `0627` 補清冊誤納後續正在寫的 `t1-native-final.log`、該欄 hash 為 null；保留前件，stable 明列排除原因，不採該欄作事故保全證明。
- 新 nonce 完整 39 列已啟動，新 runner 原件皆在 clean 外 SDD／run；首列完成不代表全套通過。clean 存續正負控、獨立 review 與同 SHA 雙平台 CI 均待，T1 OPEN、舊量測不採 gate。
#### T1 執行中批次降為工程診斷（來源指紋缺口）

- 本批 39 列啟動後，作者曾修改 runner 磁碟文字（長駐 pwsh 已解析舊版）、`build.gradle` 的 `Pm7ExportMain` 分支與 JUnit budget source，Native Java／inventory 未改。因沒有完整一致的消費 snapshot，這批所有結果只列 `diagnostic-only`，不作 T1 正式 gate，也不把範圍縮成只看 Native Java。原件保留，另寫分類 sidecar，不改舊 metadata 冒充新 pin。
- 可先完成其餘 T1 接線與必要本機測試，正式 gate 前固定 immutable 消費清單／raw bytes、HEAD／dirty、Runner／Gradle／Java／inventory、compiled artifacts／loader argfiles 的 hash。各 phase 實際消費檔跑完逐檔重讀未變才採計；需要改碼先結束 batch、新 nonce。正式兩平台 39 列仍完整，不能以此診斷批抵用。
- 若在 phase 自然結束後可安全停止下一 dispatch，標 controller 中止診斷並保原件；無安全取消能力則不冒險殺程序。所有新結果仍留 clean 外安全 root，clean 保全與正負控要求不變。T1 OPEN。
#### T1 工程診斷批 runner FAIL 終局

- `t1-native-final.log` 實際完成 22 列／44 phase；第 23 個 root `run/m7-home-61c6dcb0cc8f4e25a20b4dcc8c6b7059` 的 crash 收據已 exit 77，runner 在 startup 寫入中讀得 null，於讀取 `nonce` 欄位報錯。這是 runner FAIL，不能改記為 controller 已成功取消；原 sidecar 的 `CONTROLLER_ABORTED_DIAGNOSTIC` 須另補終局分類，原件／log 保留。整批原已 diagnostic-only，不抵正式 gate。
- 作者追 startup 完整發布／讀取及 main-only typed class oracle 的測試前提；後者曾把 Fabric API 的 `FabricGameTestModInitializer` 名稱誤判為 testmod。所有實際失敗與期待負例須分列原件，修正後新 root，不洗原判。
- 正式 freeze 前仍須完成上述技術修正、合法 budget fixture、main-only／aggregate 正負控、README 及完整 source／compiled／argfiles 快照，再以新 nonce 正式全列。必要可攜 main-only／Loom 匯出薄 helper 納 T1 allowlist／指紋／review，production 未改。T1 OPEN。
#### T1 startup 最小正負控與剩餘接線（OPEN）

- `t1-startup-publication/` XML 已由 controller 核 `M7PublishSpikeTest.startupPublicationIsInvisibleUntilForcedAndPublished` 1/1，failures／errors／skipped 0，0.062 秒，SHA-256 `9E43B2F71179415AB3C6E741DC03AA7D965584213A5DA9580FB3673F10532889`。作者以實際 `M7HomeProbe.publishDurable` 驗 temp 未發布不可見、force＋hardlink後完整、已發布空／半截／缺欄／錯 nonce 拒絕；正式 native／雙 OS 尚待。
- 舊 runner 當時實讀 raw bytes／長度／時間未保存，明記 UNKNOWN；現在完整 startup 不能當作當時 raw。工程批已另存 `t1-native-final-terminal.json` 為 `RUNNER_FAIL_DIAGNOSTIC`，保留原分類 sidecar 與 FAIL。
- main-only 實際首試兩個 FAIL 分開保留：`t1-main-only` 匯出 executable=null、JVM 前拒絕 outer1；`t1-main-only-2` 真 JVM 正常停服但 oracle 誤判 Fabric API 類別名稱，outer1。這些不是設計的隔離負控；修正後須新 root 實跑。
- 尚欠 main-only runtime／aggregate 正負 fixture、合法 budget 後繼與清單、README／workflow 最終 fixture、immutable source／compiled／argfiles 新 runner 定向驗；補齊再一次凍結，當前無 active JVM／正式全集。T1 OPEN。
### binding spec 第一輪獨立審查（2026-11-13）

- 結果：Critical 1／Important 6／Minor 1，禁止進入 plan；本輪沒有 runtime gate。
- 報告：`.superpowers/sdd/2026-11-13-m7-return-home/spec-review-1.md`，SHA-256 `96062DAC8AD533D2CD7270249966B603EE6AD2EDFFD909C947AE54ED5A6432B5`。
- 已核實的阻擋：固定 HOME 與入口返還門重疊；選後停用事件與停止 latch 的合法矩陣不足；終局後 B 普通開門的 crash 恢復不足；終身4096筆歷史上限及實際 NBT 預算不足；非同步選門重驗不足；UNKNOWN 載入逾時不應當作已選旅程終局；終局原因與持久證據需要共用 strict 矩陣。
- 處置中：交同一作者修訂，保留原審查結果；歷史收據改採可持續、按 SID 獨立有界存放的設計，不把全存檔回程次數永久封頂。修稿仍須獨立複審清零，不是實作完成。
- 修稿 v0.2 已交回：361行，SHA-256 `04653A840E9086A7AD8B903054B56D30A83D0D8DC7910029CC0EF081947B99F5`；處置表 `spec-fix-1.md`，原獨立 reviewer 正在定向複審。作者稱已處理不等於 finding 已關閉；未開始 plan、production 或 runtime gate。
- 第二輪獨立複審 `spec-review-2.md`：Critical 0／Important 2／Minor 1。第一輪 C1、I2、I4–I6、M1 已關閉；I1 尚欠停用已落盤而 stop 未落盤的開服復啟圍欄，I3 尚欠未選門 remap 新舊租約聯集的容量計量。Minor 是補回 B/B 競爭、普通玩家忙時拆 A 與 native／Crossed／playerdata 跨 JVM 窗口。已交同作者窄修 v0.3；仍禁止進 plan，未執行 runtime gate。
- 第三輪獨立複審 `spec-review-3.md`：Critical 0／Important 0／Minor 0，SHA-256 `F6213389B4FD3C796E19644AB83AA0B68C4FBE1D80A111D0E1BB884BE90B1CA0`。審查稿 v0.3 共369行，SHA-256 `463FAA723F6E15915B34378774E298D52C84BB3AFC09EF44EC980807B988D1F2`。I1／I3／漏測均關閉，採用為 binding spec v1.0；只更新規格標頭的採用註記，契約不變。依使用者「審查清零就直接往下做（建議）」開始 plan，不再等核可；未執行 runtime gate。

### 新增玩法裁示：選定後 A 被停用或拆除（2026-11-13）

- Codex 提問：「M7 已規定選定回家門後，A 斷電不影響這趟；但尚未規定『選門後，A 原艙被管理員停用或拆除』的結果。這種情況要如何處理？第一項會保留回家目的地與收據，玩家留在 B，且不重建 A。」
- 使用者選：「停止回程並保留選擇（建議）」；未選「仍照原座標傳回 A」。
- 適用結果：不把單純斷電的快照規則擴張成忽略停用或失根；停止尚未完成的回程、保留選擇，不重建 A。「保留選擇」只指保留收據作紀錄，不是掛著等待；具體終局依下方追加裁示。已跨過的人不回拉、不重建、不寫入 A，使用者在本次追加裁示再次確認。

### 追加玩法裁示：停止回程的終局與 A 保護（2026-11-13）

來源：使用者貼入本對話的「巡檢回饋與使用者新增裁示」，以下照錄題目、選項與說明。

1. 題目：「選定回家門後 A 被停用或拆除「停止回程」，之後這趟旅程怎麼收尾？」
   - 使用者選：「這趟結束，艙恢復可用（建議）」。
   - 說明原文：「B 的門照一般方式打開，「同時只能一趟」的佔用解除。收據保留作紀錄，不重抽、不改寫。A 恢復之後，要重新喝藥、再出發一次。」
   - 未選：「掛著等 A 恢復」。
2. 題目：「有旅程從 B 出發時，A 原艙要不要維持保護（不能被拆）？」
   - 使用者選：「維持保護到這趟收尾（建議）」。
   - 說明原文：「和現在「供電中或返還完成前保留保護」的做法一致。A 斷電仍不影響這趟，但收尾前 A 不會變成可拆。這樣「選門後 A 被拆」只剩管理員用指令等例外。停用開關仍可切換，切了就照剛才的裁示停止回程。」
   - 未選：「不特別保護」。

本追加裁示釐清前一節：「停止」是這一趟的終局，終局後解除同 Chamber 全域佔用；收據保留、不重抽、不改寫，不把原 session 掛著等 A 恢復。A 恢復後須另開新 session；上方選項說明的「重新喝藥」經下一題釐清為可沿用未到期藥效。只有停用或拆除會依此停止；選定後單純斷電不影響這趟。已跨過的人不回拉，不重建、不寫入 A。

3. Codex 釐清題目：「你補充的「A 恢復之後，要重新喝藥」，是否表示原本藥效還在，也必須額外喝一瓶？既有規則會保留剩餘藥效；兩個選項都會結束舊旅程、解除佔用，並要求另開新旅程。」
   - 使用者選：「可沿用未到期藥效（建議）」。
   - 未選：「停止後必須重新喝一瓶」。
   - 因此不清除剩餘 QuantumState、不要求額外喝一瓶、不新增飲藥次數或 playerdata 門檻；新旅程沿既有全員有效藥效規則，仍須新 session，不復活舊收據。

## Step0（2026-11-13，Claude，只改文件）

### 基線

- 分支 `codex/m7-return-home`，工作樹 `.worktrees/m7-return-home`，2026-11-13 從 `main` `225502728bd7839809ee5a6e4696ce079aebd42e` 開出。
- annotated tag `m6`：object `54a8f66f744ed741056785980c8904ef95b0a25f`，peeled `53573fc17fa665d254ed08e9327bb843a5af93e9`。
- `2255027` 的 CI run `43223550800`：`build`、`windows-native`、`m6-native` 三個 job 都是 success（2026-11-13 以 `gh run view` 讀回）。log 另存在 repo 外 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\ci-43223550800.log`，SHA-256 `8CB19D68358C9D41F6CB2BF66B17C64967678C2C7FCE3082DCA8BF2B3788B4DC`。
- 本 Step0 只改文件，沒有跑 Gradle 或任何 gate。

### 範圍裁示（2026-11-13）

使用者在這次討論以 AskUserQuestion 逐題回答。下表照錄題目、使用者選的選項標籤與該選項的說明原文；標籤就是使用者的原話。題目與選項由 Claude 撰寫，選項說明是使用者選擇時看到的內容。

#### 1. 在 B 啟動的是哪一種艙

- 題目：「「在 B 啟動」要做哪一種艙？」
- 使用者選：「到達時的投影艙（建議）」
- 選項說明原文：「到 B 時同座標自動出現的那座艙可以再出發。它和 A 的原艙算同一座，A 永遠是它的「家」。好處：不用再蓋；回程終點明確（就是 A 原艙，同座標）；不會在你的原本世界亂寫新艙。」
- 未選：「在 B 新蓋的艙」「兩者都要」。
- 依這段選項說明，回程的落點是 A 原艙內的同座標（投影艙和原艙同座標）。

#### 2. 回程入口

- 題目：「從 B 回家的入口要怎麼做？」
- 使用者選：「走廊裡固定一扇回家門（建議）」
- 選項說明原文：「在 B 的投影艙照常供電、喝藥、關門進走廊；走廊裡永遠有一扇外觀不同的「回家門」。選了它 → 回到 B 的艙 → 量子膜打開 → 跨過去就回到 A 原艙。和探索是同一套流程；之後加上連續穿越時，同一條走廊會同時有回家門和一般的門。」
- 未選：「不進走廊，直接回家」。

#### 3. 範圍切分與順序

- 題目：「範圍和先後順序怎麼切？」
- 使用者選：「拆兩個，先回程（建議）」
- 選項說明原文：「下一個 milestone 先做「投影艙可啟動＋回程」，完成就合併 main、可以試玩；再下一個 milestone 加上一般的門，完成 A→B→C。兩者共用的基礎（投影艙啟動、同一座艙一次只跑一趟、取消時回到出發點）在第一個就做好。」
- 未選：「一個 milestone 一起做」「拆兩個，先連續穿越」。

#### 4. 回程的供電條件

- 題目：「回程要什麼條件（供電、藥水）？」
- 使用者選：「A、B 兩端都要供電」（不是建議項；建議項是「和出發一樣，只要 B 端供電（建議）」）
- 選項說明原文：「更符合「錨點維持聯繫」的設定。但 A 斷電時（例如多人服有人關掉拉桿）會被困在 B；也要多做「從 B 讀取 A 的供電狀態」的機制。」
- 另一個未選項是「回程不用藥水」。藥水要求見「沿用與推論」。

#### 5. A 的電在什麼時候檢查

- 題目：「既然 A 也要供電，什麼時候檢查 A 的電？」
- 使用者選：「選回家門時檢查一次（建議）」
- 選項說明原文：「選門那一刻 A 要有電，否則門鎖住並提示「原艙未供電」。選定之後 A 斷電不影響這趟（和「選擇鎖定就不反悔」一致）。A 區塊未載入時，系統會先暫時載入去讀電位，不直接當成斷電。」
- 未選：「選門時＋量子膜打開前各一次」「全程監看」。

#### 6. A 原艙不能用時

- 題目：「A 的原艙不能用時（被拆、結構不完整）怎麼辦？」
- 使用者選：「回家門鎖住並說明原因（建議）」
- 選項說明原文：「不重建、不寫入 A 的任何方塊；提示「原艙已被拆除／結構不完整」。A 的區塊沒載入不算失效，系統會暫時載入去檢查。玩家仍可走地獄門或床回去。」
- 未選：「照樣回到 A 同座標」。

#### 7. 第一個 milestone 時 B 的走廊

- 題目：「第一個 milestone（只做回程）時，B 的走廊裡一般的門怎麼處理？」
- 使用者選：「只有回家門（建議）」
- 選項說明原文：「一般門的位置先是牆，下一個 milestone 才加入。遊戲裡不放「即將開放」之類的字樣。」

#### 8. 同一座艙的同時使用

- 第一次題目：「A 的原艙和 B 的投影艙算同一座，多人怎麼處理？」使用者選「各端可以同時跑」。
- Claude 隨後說明互等卡死：甲在 A 選到通往 B 的門、乙在 B 選了回家門，兩隊都回到各自的艙等量子膜；落點又都在對方正在使用的艙內，若規則是「目的端忙就等」，兩邊會互等。
- 第二次題目：「知道互等卡死的問題後，多人同時使用要怎麼定？」
- 使用者選：「改回同時只能一趟（建議）」——**這是有效裁示，取代第一次的選擇**。
- 選項說明原文：「同一座艙的所有端（A 原艙、B、C 投影艙）同時只有一趟旅程；另一端啟動時提示「這座艙已有旅程進行中」。不會互等、不會兩隊擠一艙；最多等一趟（約 3 分鐘走廊＋準備＋60 秒膜）。」
- 「約 3 分鐘」來自 `ModPotions` 的 QuantumState 藥水時長 3600 tick。

#### 9. 狀態提示

- 題目：「狀態提示（設計稿建議先做的「玩家不看 log 也知道艙在做什麼」）要不要一起做？」
- 使用者選：「只做回程需要的提示（建議）」
- 選項說明原文：「例如回家門鎖住的原因、「這座艙已有旅程進行中」、「已回到原本世界」。完整的狀態提示另外排。」

#### 10. 外觀

- 題目：「4 張概念圖要採用到什麼程度？」
- 使用者選：「這兩個 milestone 不改外觀（建議）」
- 選項說明原文：「概念圖先當之後的外觀方向。艙的結構（7×7×7、1 格基岩外殼）不變；只給回家門做「看得出和一般門不同」的最小區別。完整外觀改版另外開 milestone。」

#### 11. 人工驗收

- 題目：「回程 milestone 的人工驗收（實際開遊戲試）由誰操作？」
- 使用者選：「由 Claude 代操作（建議）」
- 選項說明原文：「比照 M6：由 Claude 自動操作測試世界並截圖留證，結果寫進驗收清單。到時候仍會先問你一次再開始。」
- 這是驗收方式的決定，不是現在就授權代操作；開始前仍要另問。

#### 12. Step0 由誰做

- 題目：「範圍確認後，開新工作樹與第一個文件 commit（納入你的 6 個檔、記錄工作樹刪除結果、修正 README／AGENTS 過時的入口、寫入這次的決策紀錄）由誰做？」
- 使用者選：「由 Claude 在這次對話做（建議）」

#### 13. 命名

- 題目：「milestone 與分支名稱怎麼取？」
- 使用者選：「M7 正規回程／M8 連續穿越（建議）」
- 選項說明原文：「分支 codex/m7-return-home，工作樹 .worktrees/m7-return-home，從 main 2255027 開出。M8 等 M7 合併、打 tag 後再開。」

#### 14. Claude 補的細節 a～e

- 題目：「上面「我補的細節」a～e 可以採用嗎？」
- 使用者選：「全部採用（建議）」
- 使用者看到的 a～e 原文：
  - a. 這個 milestone 的走廊只有回家門。所以在 B 啟動的那一刻，如果 A 已經不能回（原艙不在或沒電），就直接拒絕啟動並說明原因，免得你喝了藥進走廊卻發現門鎖住。到第二個 milestone 走廊有其他門時，改成只鎖回家門。
  - b. 走廊中途藥效到期，或喝牛奶取消，一律回到 B，不是回 A。
  - c. 任何宇宙裡、由這座艙產生的投影艙都能回家。
  - d. A 原艙被管理員設為停用時，也算不能回，回家門鎖住。
  - e. 舊存檔與進行中的舊旅程維持原本的意思。新資料要有版本號；「平行宇宙只有主世界」的設定不變。

#### 15. M8 方向（開工前再確認）

- 題目：「（第二個 milestone 的方向）從 B 出發時，一般的門以誰為準？」
- 使用者選：「一律以 B 為準（建議）」
- 選項說明原文：「原艙門（直接打開、不穿越）代表留在 B；「同 seed」類的新宇宙以 B 的生成設定為基準；「已去過的宇宙」排除 B 本身、也不包含 A（回 A 走回家門）；走廊中途取消或藥效到期回到 B。」
- 這是 M8 的方向，不是 M7 範圍；M8 開工前要再和使用者確認。

### 沿用與推論（沒有單獨提問）

以下各條由 Claude 依現行行為或建議稿列入給使用者看的摘要。使用者沒有對這些條目單獨作答。binding spec 要逐條標明來源，有疑義就問使用者：

- 回程和出發一樣要全員具 QuantumState（喝藥）。使用者沒有選「回程不用藥水」，第 2 題選項說明原文也寫了「照常供電、喝藥、關門進走廊」；但藥水沒有另外單獨提問。
- 回程的隊伍規則沿用出發：只算一起進艙的參與者；量子膜開約 60 秒；跨過的人到 A，沒跨的留在 B。
- 成功回到 A 時，也不寫入、不重建 A 的任何方塊，不清空原艙內容。來源是建議稿 §10.3 與 §17.3 C10；第 6 題選項說明只涵蓋原艙失效的情況。

### 流程指示（2026-11-13）

現行追加授權與止損以本檔最上方「T2 起連續推進與新止損」節為準；下列舊 T1 hold 為歷史。

來源是使用者 2026-11-13 貼給本對話的接手提示詞。那份提示詞由先前的 Claude 對話起草，再由使用者貼上。相關原文：

- 「最後才產出一份「給 Codex 新對話的派工提示詞」（見第 6 節）。討論完成前不要先寫。」
- 「流程：binding spec → 獨立審查 → plan → 審查 → Subagent-Driven Development 實作。production 改動走完整 gate；只改測試、腳本或文件時，照 B+C 走一席審查、定向驗證與 CI；」
- 「授權邊界：正常 push 可以；main 合併、tag、人工代操作、worktree 退役各自要另問使用者。」

Codex 接手時，使用者的流程選項裁示（2026-11-13）：

- 「審查清零就直接往下做（建議）」：binding spec 與 plan 各經獨立審查，Critical／Important 清零後，直接接續 plan／Subagent-Driven Development 實作，不另等整份設計或計畫核可。只有 M7 note 未涵蓋、需要使用者決定的玩法問題才停下詢問。

Codex 接手時的派工提示詞（Claude 起草、使用者轉貼，2026-11-13）另載下列要求；它們不是另一次選項裁示：

- production 改動完整門檻是 RED／GREEN、mutation、focused、完整 run-all、同 SHA CI；只改測試、腳本或文件時一席審查、定向驗證與 CI，免 mutation 與本機完整回歸。M6 B+C 的其他條款不沿用。
- 每次 commit、push 或 gate 終局同步本檔與 AGENTS 接手入口；舊 FAIL 與失敗 root 保留原判，修改後使用新 nonce，不自行放寬驗收容許值。
- 正常 push 可以但不 force；main 合併、tag、人工代操作（方式已選 Claude，開始前仍問）、worktree 退役、PR、LICENSE 各自另問使用者。

依此，M7 的 binding spec、plan 與實作交給 Codex 新對話；Claude 只做 Step0 並產出派工提示詞。

M7 從 M6 的 B+C 規則只沿用上面引號內的那一條：只改測試、腳本或文件時，做一席審查、定向驗證與 CI，免 mutation 與本機完整回歸。M6 B+C 的其他條款不自動沿用，例如「純文件 CI 若僅已知偶發失敗不重開 task」「同 code pin 的已知 AccessDenied／走廊容量連坐最多保留原根後重跑一次」，要用時另問使用者。

### 與建議稿 `QuantumChamber_Gameplay_Core_Design_v0.1.md` 的差異

建議稿的 G0–G5 是該稿自己的代號，不是 M7／M8。對應關係與差異：

- 順序：建議稿 §16 建議先做 G1（狀態提示）。使用者裁示先做回程（M7），狀態提示只做回程需要的部分（第 9 題）。
- 回程入口：建議稿 §10.2 建議返航不走未知候選走廊，採固定目的地模式。使用者選走廊裡固定一扇回家門（第 2 題）。
- 根錨供電：建議稿 §13.2 建議有界的根錨檢查。使用者選 A、B 兩端都要供電，選回家門時檢查一次（第 4、5 題），M7 另在 B 啟動時先檢查（細節 a）。
- 一致的部分：投影代理（§10.1，第 1 題）；先回程、後連續穿越（§10.2，第 3 題）；同一座艙一次一趟（§10.6，第 8 題）；B 出發以 B 為準（§10.4 與 §17.3 C12，第 15 題）。
- 對應：M7 約等於 G3，改用走廊回家門，並加上回程需要的提示；M8 約等於 G4。

### 不在 M7 範圍

- 從 B 出發的一般候選門與 A→B→C（M8）。
- 在 B 新蓋的艙作為新起點（未排程）。
- 艙的外觀與結構改版，包括 4 張概念圖的風格（另開 milestone）。
- 完整狀態提示與探險紀錄（建議稿 G1、G2，未排程）。
- 專屬 Nether／End family；`FamilyCapabilityVersion` 維持 0。平行宇宙只有主世界。
- 可逆佔位、自選已知航路、任意生物或載具旅行。

### 納入 git 的使用者檔案

使用者 2026-11-12 在主 checkout 的 `docs/` 新增 6 個檔。Step0 複製到本工作樹後一併 commit，兩邊的 SHA-256 一致：

| 檔案 | SHA-256 |
|---|---|
| `docs/QuantumChamber_Gameplay_Core_Design_v0.1.md` | `EBA1D7D4775EDE51194DF6A40BEFA84ACDFB9EE9ED1FCA2CA8319BE4CBC6B3F0` |
| `docs/QuantumChamber_Code_Agent_Handoff_v0.1.md` | `9546014A8898449001CD291FCC09B3FD61F0CFAA4F8DA9CA39D47950689D8462` |
| `docs/mc量子盒構造1.png` | `081A93B904B9D5309E9F9F858BAA5671EECE968A353F3904421B93148CA687E7` |
| `docs/mc量子盒構造2.png` | `5FF276CFAA5749C5AC5FF4B2ED2973A3E04372FB33EC4F2460A41E2A76B36C84` |
| `docs/mc量子盒構造3.png` | `97E169265E3AAE8C9CFE2007E0A8F6570CFD5FE186F44E4B9A9BAE1E9FE168F9` |
| `docs/mc量子盒構造4.png` | `0B1F62A774AD1FA8C1F93BAE4079E85ECB54986CBA51880DD826A3CB756C2D39` |

- 上表是原檔位元組的 SHA。兩份 md 是 LF 換行，也就是 commit 內的內容；這台機器 `core.autocrlf=true`，Windows checkout 出來會變成 CRLF，雜湊會不同。要驗證請用 `git show <commit>:<path> | sha256sum`。png 不受影響。
- 兩份 md 是建議稿與接手範本，**不是 binding spec**。它們的基線是 `71f4f72`（M6 收尾前），把 M6 收尾列為待辦（建議稿 §16「G0｜完成原定 M6 收尾」、接手範本「G0：依原計畫完成 M6 收尾」），反映的是當時狀態。
- 接手範本 §3～§5 要求 code agent 先做 G1，並在第一輪交付五項（含 G1 設計）。這些已被本檔的範圍裁示取代（第 3、9 題），不作為派工指示。
- 4 張 png 是概念美術，牆和屋頂比現行 1 格外殼厚，只當外觀方向。
- 主 checkout 的未追蹤原檔依 AGENTS 規則保留，沒有刪除。M7 以 `--ff-only` 合併 main 時，git 可能因為這些未追蹤檔會被覆蓋而拒絕；屆時先比對上表 SHA，再請使用者決定怎麼處理主 checkout 的那份。

### `.worktrees` 退役結果（2026-11-12）

依 AGENTS「M6／`.worktrees` 退役專屬裁示」，於 2026-11-12 21:42–21:48 刪除整個 `.worktrees`（M6 工作樹、W10 producer、兩個 T12 突變 clone）。

前一個對話的執行紀錄如下。原始 log 放在那個對話的 scratchpad，現已不存在，本 Step0 無法重驗：

- 刪除前執行 `gradle --stop`，確認沒有 java 行程。
- 目標是唯一的 canonical absolute path，不是 reparse point，底下恰好 4 個資料夾。
- `session.lock` 共 11726 個，都能以獨占方式開啟，代表沒有被持有。
- 先只解除 4489 個 junction 連結本身，不跟進目標；其中有些指向 `C:\Users\Ben`。
- 刪除後以 `git worktree prune` 移除兩筆登記。
- 程序和裁示第 5 步不同：沒有用 `git worktree remove --force`。原因是有些 junction 指向 repo 外，改為先只解除連結，再 `Remove-Item` 遞迴刪除，最後 `git worktree prune`。刪除範圍不變。

本 Step0（2026-11-13）重驗：

- 開 M7 工作樹前，`.worktrees` 不存在，`git worktree list` 只有主 checkout `2255027`。
- 分支 `codex/m6-universe-generation` 在本機與 origin 都保留；`ls-remote` 讀回 origin 是 `2255027`。
- repo 外驗收證據 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\evidence-index.json`（SHA-256 `84D058E4BB1CE22888D1FD7C7D91FED985E1064FFAA7522BBA75851093FAC8D7`）列出的 177 個檔，逐檔 SHA-256 都相符，沒有缺檔。

### 給後續 task 的提醒

- 執行任何會跑 W10 preflight 的入口之前，要先照 `scripts/verification/README.md` 重建 W10 producer，並在 UTF-8 console（code page 65001）執行。這些入口包括：完整或 `-PreflightOnly` 的 run-all、不加 `-Only` 或 `-Only w10` 的 `m5-collapse-probe.ps1`、`m6-harness-safety-check.ps1 -Case ci-cwd-preflight`。CI 不跑這些入口。
- 下一步：binding spec → 獨立審查 → plan → 獨立審查 → Subagent-Driven Development 實作。
