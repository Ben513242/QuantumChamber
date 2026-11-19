# M7 正規回程實作計畫

> 執行使用 `superpowers:subagent-driven-development`。每個新 task 派一位新的 implementer；該 task 的 fix round 由原 implementer 修訂，再交獨立 reviewer 複審。核取框由 controller 依實際證據勾選。

**目標（Goal）**　合法 COMPLETE 投影 B 的參與者走固定回家門，先回 B，再經 B 的膜以原座標回 A。旅程終局可安全解除同艙占用。
**架構（Architecture）**　將既有 checked journal 擴充為能同存舊列與 HOME 列的單一權威。沿用走廊、回收、票額、原生移動與 chunk checkpoint，分別核對 Entry=B 與 Root=A。每 SID 的終局收據保留歷史，活躍列負責完成 B 的最後門狀態。
**技術基線（Tech Stack）**　Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Loom 1.7.4、Java 21、Gradle 8.8、PowerShell 7。
**對應規格（Spec）**　[v1.6 遊玩條件組合裁示](../specs/2026-11-13-m7-return-home-design.md)，SHA-256 `E8AB80D3D7C1296CF64E00CEC16EE14AED30EED6AA06E35819794357DD68ECA2`。玩法、R1–R4 與授權出處見 [M7 實作紀錄](../../implementation-notes/2026-11-13-m7-return-home.md)。

## 2026-11-13 現行續行授權與止損

- 從 T2 依已審 plan 連續推進至 T8 已授自動化／整分支審查／docs；每 task C/I 清零且同 SHA required CI 真全綠才 closed，直接下一 task。T1 後「本輪不 T2」及 I11 單次處理限制均為歷史，已被本裁示取代；不重開 T1 或 spec／plan 審批。
- I2 是正式資料正確性義務，在 T2 由 HomeJournalBudget／正式 schema6／共用 writer 修復，附 fixed32 保租 root latch／COLLAPSING 具名 RED 與直接 production-owner mutation；不得延期或當超 M6 殘留。
- M6 同等限定驗證工具與證據做法；不豁免 binding 功能、strict schema／合法來源／預算／所有共用 writer、production RED/GREEN/mutation/focused/full/review/同 SHA CI。超標工具意見列殘留，不默認塞 T4。
- 每 task 同範圍記 N0、每整改 N1/N2…（Important 總數含新項）；任一連續兩整改輪窗口未下降即停報，不做下一輪。不得藉重命名、nonce、docs 或無證據重新分類洗數；Critical 永不帶過。
- 驗證工具依穩定 problem ID 記連續非預期失敗，同問題第三次即停，安全收回相關自有 owner 並保全原件；expected RED／negative／mutation 正確拒絕分列不算工具缺陷。production bug 與工具 bug 依證據分開；既有 Evidence3／NativeStartup3／main-only2 歷史原判保留，已移除框架不復活，不任意合併無關問題或拆簽章迴避。
- 每新 task fresh implementer＋一位獨立 task reviewer（spec＋quality 同席），fix 回原作者。controller `/root/m7_t2_onward` 是 note／AGENTS／plan 核取／progress／STATUS 唯一 writer。每 closed 短報 task／code pin／CI run／殘留／下一 task。
- 人工代操作開始（方式已選 Claude）、main ff-only、tag、PR／LICENSE、worktree 退役仍各自未授。正常 feature push 已授；既有 W10 producer 只核身分／hash／非reparse／owner／locks，不重建或重套 patch。保全部原 world／FAIL／UNKNOWN，修正用新 nonce/root。**2026-11-18 追加（AccessDenied）**：使用者 2026-11-18 09:14 第 2 題選「比照 M6，每個 code pin 一次（建議）」；適用範圍依同日 11:5x 第 4 題為 M7 剩下的 T5～T8。原文與 controller 執行說明見 [M7 note「最新：T4 CLOSED」](../../implementation-notes/2026-11-13-m7-return-home.md)與 AGENTS「M7 Windows atomic-move AccessDenied 單次重跑規則（使用者 2026-11-18）」。完整回歸唯一失敗是 Windows atomic move AccessDenied、沒有其他 FAIL，且 production 仍 fail-closed／退避（依 M6 原文補上的前提，controller 解讀）時，保留原 root，換新 nonce 重跑一次、不用再問；同一 code pin 第二次就停報；只改 docs 不重置次數（controller 執行說明）。本檔 Task 2（:134、:138）與 Task 3（:182）記的 AccessDenied 1/1 已用完、不刷新是當時的規則，保留為歷史，已被這條取代。（本段接在同一行，是為了不讓 Task 4 核取項的行號位移。）

以下 T1 單輪／不 T2 流程是歷史；本節已解除舊 hold，不變更已審玩法／資料／task 契約。原文見 M7 note 最上方同名節。

## 2026-11-13 T1收斂為M6同等（原同席C0/I0/M1後採用）

使用者原文見[M7 note「T1收斂、M6同等」](../../implementation-notes/2026-11-13-m7-return-home.md)及SDD/user-t1-m6-standard-ruling-20261113.md。取代上一輪PoC18/T2T3守門/退休流程；玩法不變、舊FAIL/根/計數不重置。

| 本輪範圍 | 契約 |
| --- | --- |
| T1 | 只保留兩平台JUnit（合法NBT大小上限/硬連結發佈）＋GameTest（實際入口守門/平台拒絕）＋M7 CI exact集合/正負例。完全取消T1 native PoC、measurement/shard/aggregate/default呼叫，不以skip/空矩陣/PASS chains=0代替。 |
| T2/T3與T4 | T2/T3不再跑T1 PoC。T4正式Windows39/78phase保持，依M6已驗probe做法薄接，不另造更嚴框架；無PoC退休提交/退休額外CI。舊Git/證據全留。 |
| Ubuntu | JUnit真volume硬link/大小與GT正式拒絕；不Linuxowner/native/main-only。原M6 Windows native/main-only jobs和所有舊required門檻照跑照保留。 |
| M6同等 | 固定baseline與逐行行為見SDD/t1-m6-equivalence-baseline.md/json；b26b90c/CI43373504422全success。PID+start/receipt hash/summary readonly+SelfTest照既有source；全class/argfile不可變/ZIP seal等超標要求列殘留不擋，也不默認轉T4。基本失敗根/XML/log保全仍是使用者要求，不得豁免。 |

### 本輪順序與停止點

1. 先本note原文及docs-only plan/spec勘誤，原唯一reviewer逐現OPEN12項（10舊I2/I4–I12＋F1/F2）分類並審C/I0。I1/I3舊ADDRESSED附註，原core/fix1判定不覆寫。分類用具體M6 source位置：保留M6內/超M6殘留不擋/移T2正式設計/nativePoC範圍移除；不可因「收斂」整批關閉。
2. 清零後原implementer只一輪scope-convergence：同步inventory/Gradle/testmod/gates/run-all/CI/oracle/README；T1不再import新native/main-only/consumer嚴框架，移除所有T1 native jobs/default執行/新startup日期門檻。可清掉未用tracked程式或保Git歷史，不刪ignored原root。不得改M6 source斷言/門檻/job。
3. 事前凍結一次窄T1 batch之code SHA、精確命令/expected、唯一root/nonce、預先具名negative。只執行JUnit＋GT＋真正CI接線正負例及M6等價summary readonly/SelfTest；不native單pair/18/39/新全class或argfile框架/額外main-only/clean/W10/full。source記codeSHA/原件hash即可，不延續上輪完整consumer快照門檻。本機沒有Ubuntu真環境則本機不假跑Ubuntu，推送後同SHA CI補真平台實證。
4. 一次定向任一非預期工具FAIL立即停、不修/重試/續跑/不push，安全收回已在途owned JVM、保原件；舊Evidence3/Native保守3/main-only2歷史不清。expected negatives必事前標明，正確拒絕不算工具故障。
5. 定向PASS後原同席只讀核差異/原件與新scope，保留M6內的C/I清零才正常push（已授權不另問），追同SHA新T1兩平台JUnit/GT及既有全部required jobs。超M6殘留不擋；不能把真入口/大小/硬link/CI負例/exact缺口稱殘留來豁免。CI FAIL保原判、不自動rerun；全綠才可關T1。
6. 這輪即使T1 closed也回root、不開T2。不新建/退役worktree，producer留存。只做必要本機candidate/docs commit，逐檔stage；每事件按實際更新note/AGENTS/ledger，CI終局先留工作區、下次合法docs提交收錄，不造無限CI。

### 現OPEN12項分類提案（須原席按M6 source確認）

- I2移T2正式HomeJournalBudget/共用writer：固定lease32 reserve0反證須由正式owner修/測，不能再以T1舊數字證上界。
- I4、I11及F1/F2涉及新T1真入口/當次JUnit-GT/CI正負/路徑/第一失敗即停的部分保留M6內；额外事件witness、全套raw byte封包等要求由原席對照來源判是否超標，不能自動關掉基本缺口。
- I5/I12與I6/I8/I9/I10的T1 native/main-only部分因scope移除；Windows T4原有39與既有M6 owner/hash/readonly/SelfTest契約保留，不自動攜入超M6 framework。I7全class/argfile/不可變DLI框架屬超M6殘留，不擋T1；不改碼/根/原件的使用者保全義務仍保留。
- I10的兩平台JAR隔離、I11的fresh XML/log/summary、自測、F1的輸出canonical路徑與F2的CI真正例/負例/立即停，依m6-ci-gametest/gates/ci-rule-check既有方式保持實際可用。舊source仍有的失效路徑須在本輪收斂後消失/修正，不以文件分類代替接線。

## 共通限制與證據

- 在 `.worktrees/m7-return-home` 的 `codex/m7-return-home` 執行。git 每條加 `-c safe.directory="C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home" -C "C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home"`。不要在 main 實作。
- task 啟動讀 AGENTS 現行入口、上述 spec／note、本 task、前一筆 ledger。臨時原件與 `progress.md` 放 `.superpowers/sdd/2026-11-13-m7-return-home/`。長期 gate 放 tracked `scripts/verification/`。舊 FAIL、root、receipt 保留。失敗記實際原因、暫停相依工作；修正後新 nonce，M6 重跑額度與 waiver 不自動沿用。
- 每次 commit、push、gate 終局由 controller 同步 note 與 AGENTS 入口。記 task、base/code pin、實跑命令／root／hash、退出碼、review range、遠端 SHA／CI attempt 與未決項。未發生事件寫「待驗」。同 SHA CI 終局先寫 ledger／工作區，下次文件提交收錄，勿為寫回該次 CI 觸發無限 CI。
- 每個 production task 都有具名 RED、GREEN、直接變異 production owner、還原原 bytes、focused、完整 run-all、獨立規格與品質審查、同 SHA CI。修正輪重新執行適用門檻。純測試／腳本／文件一席獨立 review、定向驗證與 CI，免 production mutation 與本機 full。T1依新M6同等只驗JUnit/GT/CI接線，不額外clean build或新main-only/native；既有兩平台封裝隔離與M6 main-only不變。
- 逐檔 stage 該 task 表列的變更與兩份 controller 狀態文件，先核 staged diff／清單。禁止 `git add -A`、覆寫既有 dirty、force push、force-add ignored 證據。先凍結本機候選 commit 再用其乾淨 code pin 跑 production full；若審查改碼，重新凍結／驗證。純進度文件另列 docs pin，遠端採最終推送的同 SHA CI。
- 一個 task 的 C/I 全清且 required CI 各 job 真 success 才 closed。依現行續行授權，T2 起閉合就直接進下一 task；人工代操作（方式 Claude）、main、tag、工作樹退役、PR、LICENSE 各自另問，無需為執行計畫追加整份核可。
- Root 僅 canonical `minecraft:overworld` 原 ORIGIN；Entry 僅 exact live catalog OVERWORLD COMPLETE PROJECTION。HOME door = `new DoorKey(sid, 1L, DoorKey.DoorWallSide.POSITIVE_LATERAL)`。SOURCE 與 legacy return-only 維持原意；FamilyCapabilityVersion=0，Nether／End 共用原版，M8 不排入。
- 啟動與選門各驗 A 的實際供電一次。選後 LOW 不取消，停用／拆除／結構失效停止旅行、保留選擇、checked 收尾才解除 A 保護及鎖。A 絕不重建、改門或清容器。新旅程須重新關 B 門，不必出艙，可沿用未到期藥效。7×7×7 與一格外殼不變。
- 相依順序 T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8。T1–T6 的定向入口限既有 testmod，可呼叫 production owner，release 的 B activation guard 到 T7 才放行。不得用 production 開關、偽造 catalog 或跳過真 checkpoint 替代這個順序。（2026-11-18 v1.6：改為 T4 → T4A → T4B → T5 → T6 → T7 → T8，這是 controller 的排序，不是使用者裁示；新 task 與 T5～T8 追加項見檔尾「2026-11-18 v1.6 修訂」節。本段接在同一行，是為了不讓 Task 4 核取項的行號位移。）

### 常用命令與全程資料安全

- 現有定向 JUnit 入口：`pwsh -NoProfile -File scripts/verification/gates.ps1 -Mode M6JUnit -TestClass <類別全名> -Label <唯一英數連字標籤>`。每類各跑一次，核 XML 完整、零失敗／略過；舊 mode 名稱不代表 M7 集合已驗。
- 現有完整入口：`pwsh -NoProfile -File scripts/verification/run-all.ps1`。自然印出 `END ALL exit=0 HEAD=<本機候選>`、outer exit=0，逐一讀本輪 summary 原件。子 gate 的成功或 `-PreflightOnly` 不抵 full。
- **首次碰 W10 前**依 [驗證 README 的 W10 段](../../../scripts/verification/README.md) 重建指定 fresh detached producer `.worktrees/m6-w10-m5-producer`。核 tag object、peeled `a2d2b404329d2574469fca064a663e21fe89f333`、原 blob、patch、runner 與 diff 雜湊、唯一允許 dirty；已有資料先驗原件，不覆寫或重套。Windows console `chcp 65001`，讀回 65001。
- W10 入口含完整／preflight 的 run-all、m5-collapse-probe 未限 `-Only`、含 `w10` 或 `-PreflightOnly`，以及 m6-harness-safety-check 的 `ci-cwd-preflight`。先驗 canonical 絕對路徑、所有祖先非 reparse、沒有 live Java owner、session.lock 可獨占。世界只可唯一可恢復 move、逐檔 hash 還原。不能祕密清除舊 FAIL 世界來取得 fresh。
- T1 將建立下列入口，此前不存在。M7 GameTest 用 `gates.ps1 -Mode M7GameTest -Label <唯一標籤>`；`m7-ci-gametest.ps1 -Platform Windows|Ubuntu` 驗該平台 JUnit、GameTest XML、log、scope 與兩 JAR。T1沒有M7 native命令；T4另以Windows薄adapt接M6 owned/receipt/readonly/SelfTest模式，當期命令與39鏈到T4才啟用。均以 `pwsh -NoProfile -File scripts/verification/<腳本>` 呼叫。
- T1 在 `S/m7-inventory.json` 定義 Windows 與 Ubuntu 各自的 exact inventory，由 `portable` 加該 OS 專屬集合組成，runner、CI、summary 與負控驗實際 OS／集合。Windows 承擔需要 PlayerCheckpointStore 的正式成功入場、恢復、跨越與 playerdata 三窗口；Ubuntu 執行正式入口的非 Windows 原生玩家 checkpoint 拒絕，要求拒絕時幾何、玩家移動、active journal、鎖／票增量全為零。Ubuntu 不啟用 Windows 成功案例、兩平台皆不允許 skip 或用 testmod 繞過拒絕。tracker、真volume硬連結與可攜JUnit／GameTest兩平台實跑；M7原生checkpoint/獨立關門當機重開留T4 Windows，不是T1門檻。Ubuntu不寫Linuxowner、不跑native/main-only，Linux crash/reopen只由Windows推論，列已知限制。T1 先驗既有 A 平台界線，T5 與 T7 在各自正式 B owner／普通玩家入口重驗。下文「全集合／全部鏈」一律指該平台 manifest 的精確全集。
- 每個 task 只加入當次真的實作、非空的 JUnit／GameTest／native 集合。tracked 必要清單、source 宣告與實際 XML／manifest 三方相等；新案例註冊、fixture 變造反例、run-all 與雙平台 CI 同 commit 生效。舊 scope 各自精確匹配。禁止空集、skip、假 PASS 或把未實作鏈列為已啟用。
- 正常 push 指定 feature branch。用 `gh run list -R Ben513242/QuantumChamber --branch codex/m7-return-home --commit <sha>`、`gh run view <id> -R Ben513242/QuantumChamber --json headSha,status,conclusion,jobs` 核本 SHA 及各必要 step／job；保留 attempt／artifact digest。workflow 對應本機負例必須跑其實際 step 原文。

### 檔案責任圖

下文記號均能展開成唯一 repository-relative path。`P/`＝`src/main/java/dev/quantumchamber/`，`J/`＝`src/test/java/dev/quantumchamber/`，`G/`＝`src/testmod/java/dev/quantumchamber/gametest/`，`S/`＝`scripts/verification/`，`R/`＝`src/main/resources/assets/quantumchamber/`。大括號列出個別完整檔名。

| 責任 | 實際 owner／新增邊界 |
| --- | --- |
| 舊資料與新列共用持久權威 | `P/persistence/{SessionRecoveryState,SessionJournalStore,SessionRecoveryRecord}.java`；新 `JournalSession`／`HomeSessionRecord` 各自 strict，保持既有 candidate 讀者型別。 |
| 資源與端點身分 | `P/collapse/{CollapseAuthorities,ProjectionProtectionIndex,HomeEndpointAuthority,HomeRootTickets}.java`；`P/corridor/{CorridorPageManager,SlotAllocator}.java` 持有同一實例的全域預算。 |
| 終局或恢復 | `P/collapse/{HomeRootFence,HomeClosure,OriginDoors}.java`，`P/persistence/{HomeReceiptStore,ChunkSaveCheckpoint,SessionRecoveryManager}.java`。 |
| 啟動與走廊 | `P/superposition/{SuperpositionSessionManager,SuperpositionSession}.java`、`P/chamber/ChamberPowerCoordinator.java`、`P/corridor/{CorridorGeometry,CorridorPageManager}.java`。 |
| 膜、原生 transfer | `P/collapse/{HomePassage,PassageService,PassageTransferPolicy}.java` 與 `P/universe/{UniverseTransferService,HomeTransferResult}.java`。 |
| 入口、驗證及證據 | `build.gradle`、`.github/workflows/build.yml`、testmod entrypoints、`S/{gates,run-all,ci-rule-check,m7-ci-gametest,m7-home-probe}.ps1` 與 `S/m7-inventory.json`。每 task 更新有新增案例的清單／chain；`AGENTS.md` 與 M7 note 由 controller 維護。 |

### 五個獨立審查焦點

1. 合法大 cohort 靠近 16 MiB 時，其他 SID 的 candidate append 與 remap 的舊＋新 lease 同時發生。T1/T2 的 `legacyAppendCannotSpendUnselectedRemapAndCloseReserve` 驗原 mapping 與已承諾的終局空間。
2. A 暫載後相同 DoorKey 映射到另一 generation，或原玩家 entity 被替換。T5 的 `same_key_new_generation_or_player_never_selects` 驗 callback 只釋自己票。
3. registry 已落 disabled、journal stop 未落，重啟先嘗試 enable，DORMANT 沒有 B runtime。T4 的 `restart-enable-before-entry` 驗 opening fence 與 R1–R3。
4. B 原本普通關門、膜為 NONE，或 receipt 已發布但 active 移除失敗。T4 的 `close-ordinary-{cut}` 及 `receipt-existing` 鏈驗真實改門、重做 checkpoint、不覆寫與不早解鎖。
5. 傳送、Crossed 與 playerdata 各在不同提交點。T6 的 `move-before-crossed`、`crossed-before-player-save`、`move-and-save` 各用獨立 JVM 核真實登入位置與零重播。

### Task 1: M6同等的窄驗證入口

**新增／收斂** `J/persistence/{M7BudgetSpikeTest,M7PublishSpikeTest}.java`、`G/M7HomeSpikeGameTests.java`、`S/{m7-ci-gametest.ps1,m7-inventory.json}`。
**修改** `build.gradle`、testmod fabric.mod.json/filter/observer/preset、`S/{gates,run-all,ci-rule-check}.ps1`、`.github/workflows/build.yml`、`S/README.md`。移除T1 native/main-only/export/consumer框架的入口與相依；可刪未用tracked source，保所有Git歷史/ignored證據。production不動。
**消費／產出** 沿M6既有JUnit入口/GT Gradle run與gates exact oracle、m6-ci-gametest的fresh/raw/pace/JAR/readonly做法；產出M7兩平台各自非空JUnit/GT exact集合、原XML/log/hash/summary、真正workflow fixture正負證據。T1沒有native集合/成功收據，不執行native/readback/date/selftest腳本作影子門檻。

- [x] 只保合法NBT大小上限、same-bytes bounded讀取與硬連結no-clobber JUnit；I1/I3已處理回歸保留。移走形式上的所有後繼/CLOSE/固定lease預留公式證明，I2固定32反證列T2。startup/native publication日期工具不再是T1測項；兩平台真volume硬link/大小仍須真跑，不以假平台或skip代替。
- [x] GT用同一附著BE的ORIGIN成功/PROJECTION拒對照與既有正式A平台入口；Ubuntu拒絕/零新增副作用依M6既有做法，保源/實際XML exact、正常pacing/testmod隔離。不要用CLOSE欄位讀取冒充guard，也不為額外witness新改production。
- [x] T1 CI只JUnit/GT與M6同等封裝/summary，不新M7 native/main-only/measurement-shard-aggregate。M6原required build/windows-native/m6-native及原main-only/全部斷言不改。raw輸出放本輪安全新root，canonical/非reparse/已有root拒覆寫，原XML/log先保留/hash；不clean刪證據。
- [x] Java方法宣告/portable+windows或ubuntu清單/實際XML三方exact，所有required非空無failure/error/skip；移除native18與舊26/shard成功標籤，不用空列表PASS。JUnit/GT原件須本次呼叫、帶codeSHA與平台；verify-only/readback不可冒稱新runtime。
- [x] 照M6模式接真正workflow step fixture正例與drop/skip/wrong-scope等具名負例；每步即核預期exit/結果，首非預期立即停。F1路徑及F2正例/negative/停點依原席分類實際修掉或隨取消框架移除，不能只忽略error。
- [x] 本輪一輪整改後凍結一次窄batch，執行JUnit/GT/CI正負與M6等價readonly/SelfTest。不原生pair/18/39/完整consumer/main-only/clean/W10/full。FAIL即停不補修/重跑/不push；PASS後同席核新scope C/I0，正常push追同SHA雙平台新T1及全部既有required CI，CI FAIL無自動rerun。全success才T1 closed，仍不開T2。
- [x] controller按實際逐檔stage本task與狀態文件；超M6殘留列清楚不擋依據、不得影子阻擋或默認塞T4。保歷史原判/根/計數，回報來源分類/改檔/精確集合/實際一次命令rootSHAexit/CI/未知與新成本模型。

### Task 2: schema6 共同 journal 與不可變單 SID 歷史


**新增**　`P/persistence/{JournalSession,HomeSessionRecord,HomeJournalCodec,HomeJournalBudget,HomeReceiptStore}.java`、`J/persistence/{M7HomeJournalTest,M7HomeHistoryTest}.java`。

**修改**　`P/persistence/{SessionRecoveryRecord,SessionRecoveryState,SessionJournalStore,JournalNbt,SessionRecoveryManager}.java`、`P/corridor/SlotAllocator.java`、`J/persistence/{SessionJournalSchemaFiveTest,SessionRecoverySchema4Test}.java`、`S/{m7-inventory.json,gates.ps1,m7-ci-gametest.ps1}`、`README.md`。runner兩處原T1 JUnit硬編清單需最小同步／依受控inventory驅動，真執行T2新增兩suite並保T1既有4案，與source／XML exact同審（2026-11-13 routine ruling，功能scope不變）。依賴T1已驗大小上限/同volume發布與exact清單；正式預留設計/上界由本task負責，不沿用錯誤T1公式。

2026-11-13 routine擴檔：SlotAllocator只共用既有restore的shape/coverage/collision驗證，RecoveryManager只typed convergence/全列readback；舊兩類6=未知負例改7以配合正式升6，保全部其他舊strict斷言。source候選81bd13e7、五類focused118與M7 16/3已通過；full/review/CI未完成，未勾項仍待獨立審查/完整門檻。

整改第1輪（N0=5）：原席T2-I01～I05皆正式正確性，回原作者核實；必要最小擴檔含 `P/candidate/CandidateLedgerService.java`、`P/corridor/{CorridorPageManager,CorridorRepositionService}.java` 及具名資料／caller測試與exact inventory。不得改舊sameAuthority/occupies、不得以純容量pending吞真正IO/strict錯誤，不新增nativePoC或驗證框架。初候選full innerPASS/outerUNKNOWN受控終止，修後另新pin/full，未預勾最末閉合項。

2026-11-13 fix1實際測試接線：`G/M7HomeSpikeGameTests.java`新增Windows真caller容量案，`G/M2CorridorGameTests.java`僅walkNative package可見供重用既有native walk，新增 `src/testmod/java/dev/quantumchamber/persistence/M7CapacityFixture.java`；M7 exact集合21JUnit、Windows4GT／Ubuntu沿平台集合。source候選9d1e6ba已focused149與六owner mutation；先原席N1 C/I0，再修後完整full/CI，最後閉合項未勾。

2026-11-14 fix2僅State共同lease聯集合計＋既有JUnit兩上限/去重/checked後重試＋inventory，source f3a79ec。focused125/M7 23/4已核，先原唯一席N2，C/I0才full/CI；未預勾閉合。

2026-11-14 N2原席C0/I0/M1（N0=5→N1=1→N2=0），source f3a79ec；M02舊時態已同步。可接修後完整full/同SHA全CI，仍未closed，最末項未預勾。

2026-11-14授權單次重跑已自然ENDALL0＋actual ExitCode0（fullHEAD9bd/sourcef3，used1/1，summary7A40006A…），N2 C/I0；原FAIL保留。待正常push與同SHA5requiredCI真全綠，最後Controller閉合項仍未勾、T2未closed。

#### 2026-11-14 fix3：依真user新保存邊界修production

此段優先於root／原席先前「先驗save以上原鏈非reparse」推論；原文見note新巡檢節／SDD user-t2-ci-path-ruling-from-root-20261114.md。原N2=0歷史保留，CI新整合缺口I07待fix3，不能把舊scope審當新版清零。

- [x] 原同席窄確認spec§5.3／V01與本fix3清單忠實於user新方向C/I0，不重開其他玩法；之後原author做production fix3。
- [ ] 最小改 `P/persistence/HomeReceiptStore.java` 構造時save.toRealPath一次，只守save內data/receiptDir/leaf；必要正式caller受控IO路徑隨同小改，save以上junction/symlink/短名不拒、不檢查，內部reparse/containment/strict/exact/no-clobber全保。
- [ ] 具名Windows真GetShortPathName原始短名及ancestor junction成功publish/lookup，data junction拒；其他OS真實對應正負案例／exact集合，無M7skip假PASS；修 `J/persistence/{M7HomeHistoryTest,M7HomeJournalTest}.java` 正式save/data布局，新增最小必要具名path測試及 `S/m7-inventory.json`，不建FS/alias框架。
- [ ] rg其餘新production逐層祖先檢查同型並列窄處置；RED/直接owner mutation/bytes還原/必要focused，凍結新codepin、同席N3 C/I0後完整run-all、正常push新SHA首次全部required CI。原CI43540208676／所有FAIL不變，不rerun舊CI或只換docs重推。
- [ ] AccessDenied full單次額度1/1已用完，不因新pin重置；完整producer／UTF8／owner/locks前置及既有止損照舊，source正確性修正不是豁免安全或驗收門檻。

2026-11-14 fix3候選0a36323已Windows focused128/M7 26/4、真FS9/9與兩owner mutation；controller已核原件，原席N3已C0/I0/M1（I07已解，M03留診斷殘留）。非Windows真對應待新UbuntuCI，不預勾跨平台驗證或最末閉合項。

N3後source0a首次完整full已自然ENDALL0/actualExit0（HEAD5df/summaryDFAC895B…），新SHA首次CI仍待，T2閉合未勾；AccessDenied full額度1/1不可刷新，舊CI不rerun。（2026-11-18 起 AccessDenied 改用新規則，見本檔「2026-11-13 現行續行授權與止損」節；本段保留為歷史。）

**消費**　`SessionRecoveryRecord.Participant`、`SessionRecoveryRecord.SpaceLease`、`ChamberOriginAuthority`、`UniverseId`、`SessionRecoveryState.CommitResult/TerminalResult`。

**產出**　`sealed interface JournalSession` 共同提供 `UUID sessionUuid()/chamberUuid()`、`List<Participant> participants()`、`List<SpaceLease> spaceLeases()`、`SessionState state()`。舊 record 與新 `HomeSessionRecord` 實作；新列提供 `ChamberOriginAuthority rootAnchor()`、`HomeSessionRecord.EntryEndpoint entryEndpoint()`、`HomeSessionRecord.Home home()`。

**欄位型別**　`EntryEndpoint(UUID chamberUuid,UniverseId universeId,Identifier worldKey,DimensionRole role,BlockPos controllerPos,Direction facing,ChamberInstanceKind instanceKind)`；`Home` 中 Selection、CloseIntent 為各自有型別的分支，`HomeSessionRecord.CloseIntent` 的 NONE／CLOSE 與 Evidence 共用 §9.2 的驗證矩陣。

**跨 task API**　`SessionRecoveryState.allSessions()/allFlushedSessions(): Map<UUID,JournalSession>`、`admitHome(MinecraftServer,HomeSessionRecord): CommitResult`、`commitHomeRestore/commitHomeRollForward(MinecraftServer,HomeSessionRecord expected,HomeSessionRecord next): CommitResult`。歷史 `HomeReceiptStore.lookup(UUID): Optional<HomeSessionRecord>` 回傳經嚴格驗證、以外層 CLOSE 重建的凍結列；`publishOrVerify(HomeSessionRecord): void` 與 `requireAdmissible(UUID): void` 錯誤不可誤當 absent。


- [x] RED：`mixedRowsAndEmptyJournalKeepSix`、`legacyAppendCannotSpendUnselectedRemapAndCloseReserve`、`duplicateRawKeysAndHistoryMismatchFailClosed`；現行版拒合法 schema6／沒有 HOME 寫入路徑，先保存具名失敗。

- [x] schema1–5 留舊 exact 流程；schema6 envelope 僅 SchemaVersion/Records，無 RecordVersion 必恰舊12欄且有 context，有則必 int 2＋精確 §5.2 新列。新列 travel=1、semantics=LATERAL_BUFF_MAINTAINED、restore byte=0；root/entry 同 chamber、位置／facing 相同。讀寫全欄 types、enum、time 溢位、UUID、空 list held type、有限值／±0、1..25 人及快照不可變。不得混裝 legacy return-only；schema3 升版舊 Collapse 空；已升6即使清空仍為6。

- [x] 將 current／flushed、quarantine、pending、未知寫入收斂與 ownership 以同一資料源管理；舊型別 accessor 可提供相容檢視，全體讀者依 task 接線：本 task 接資料層玩家占用、reservation/collision、write/save/init；T3 接全域 busy；T4 接 startup fence；T5 接 startup space/recovery；T6 接 collapse。全數接妥前封住一般 activation。首次 checked ARMING 僅在既有 M5 instance authority 初始化且無未確認寫入。schema6 pending／readback 驗完整 wrapper、active 及 CLOSE。

- [x] 移入I2正式設計反證：`fixed_lease_reserve_covers_root_latch_and_collapsing`，以合法MEASURED+32 lease保留租約時寫ROOT_INVALID_STRUCTURE或進COLLAPSING，預留不可是0；同驗其他固定集合/共享舊append保HOME完成餘額。舊XML50397→50397/reserve0與原FAIL留歷史，須正式production owner修正及mutation證明，不靠T1範圍分類假關。

- [x] 由正式schema6與可達狀態證明公式計算 `HomeJournalBudget.requireReserved(NbtCompound wrapped): void`，以正式編碼及相同 tracker 核每個 writer 變更前的可容納性、成功後的 exact readback。舊 append、put、remap、upgrade、共享 writer 不得侵占；preselected phase 保 64、selected 按凍結集合，獨立最壞 receipt 也核。越界維持原 journal／mapping，不能因拒增長取消原隊伍。checked ARMING 前同時核 SID 的合法單檔上界與發布能力，拒絕不得改動既有 journal 或租約。

- [x] DONE 條件採 spec §9.1 單步狀態／進度矩陣與 ownership，§9.2 各原因的 state／Evidence／時序／DoorGoal 成對嚴驗；old sameAuthority／occupies 原義。恢復或未知的邊用 spec 指定 CAS，禁止跨 state 夾改核心身分、合併變更或 CLOSE 後續移動。

- [x] 使用 T1 發布機制，save canonical/非 reparse，按 lowercase SID 單檔精查；`DataVersion,data{SchemaVersion=1,SessionUuid,CloseIntent,Session}`，Session 僅內 CLOSE 正規化 NONE。active/receipt 同 SID 僅容許 exact 凍結 CLOSE。既存錯配／損壞保持原件；無能力即拒新 HOME，不掃目錄，失敗 temp 留證。

- [x] GREEN：所有舊 schema fixtures 及 25 人／64 slot 值、硬連結競爭、預算邊界、parse path 切換皆實跑；以基線 `2255027` 的真正舊 reader 或隔離 classpath 讀固定 schema6 bytes，須拒絕。README 說明首次 HOME 升6 後舊 build 不可降版；備份資料不能被測試改寫。

- [x] 變異 production：raw walker 漏 Home 內層、把 tracker 換 unlimited、未選只保現有 lease、選後漏 CLOSE、舊 append 跳預算、replace 既存 SID、空 journal 降版，各由上述正式 reader/writer 測試 killed 並逐檔還原。focused 跑兩個新 JUnit、SessionJournalSchemaFiveTest／SessionRecoverySchema3Test 與 M7 全集合。

- [x] Controller 同步並逐檔 stage T2 清單，本機候選 `feat: 支援有界 schema6 與不可變回程收據`。先依 README 重建／核 W10，再跑乾淨 pin 的完整 run-all；獨立規格／品質 C/I 清零、記 docs truth、正常 push 並核同 SHA CI 才 closed。


2026-11-14 T2 CLOSED：source0a/full5df/成功CI-docs935，CI43554024617 attempt1全部5required success；Win26/4、Ubuntu26/3與symlink真原件已核。fix3相關producer/owner/mutations/focused/full/newSHA首次CI均完成，原FAIL不改，M03交wholebranch。

### Task 3: 核對 B／A、占用與有界 A 票

**2026-11-14收尾交接（最新）**：真user「準備收尾交接」，本輪不fix2/runtime/full/push/T4。N1清零後full0e8於:test1164/2fail/2existingSkip自然1，GT未啟，兩JUnit待修／T3未closed；精確續作與原件見docs/handoffs/2026-11-14-m7-t3-handoff.md。handoff事實窄審最終docs C0/I0/M0不當N2，本機docs不push/CI並非豁免。下段為歷史。

**N1清零（2026-11-14）**：sourceaa1d354e／已驗7558b0e，N0=6→N1 C0/I0/M0，I01～I06/M01全ADDRESSED；原driverFAIL及全舊計數保。現進freshW10前置／固定pin完整full／新SHA首次五CI，尚未closed／T4。

**fix1候選（2026-11-14）**：sourceaa1d354e，六目標RED／GREEN9GT／九direct mutation livebytes還原已核；expiry原driverFAIL與semanticKILLED待N1分層保留，詳note。最後必要focused後同席N1，C/I0才full/CI，原fixture3/3不洗。

**N0終局／fix1（2026-11-14）**：C0/I6/M1（Important總6），原作者fix1處理正式I01～I06；report task-3-review.md與M7 note，FIX_BASE35e0f40。N1含新項總數，C/I0才full；原fixture3/3與再任一非預期即停不變。

**2026-11-14恢復裁示**：真user選「允許 N0 後續完整門檻（建議）」；原題/條件見M7 note與SDD user-t3-resume-ruling-20261114.md。從sourcea506既有證據freshN0，C/I0才完整full/新SHA首次五CI；保原fixture3/3、任何新同fixture非預期即停、AccessDenied1/1不刷新。下段STOP是歷史，不自動要求重跑已齊focused/mutations。（2026-11-18 起 AccessDenied 改用新規則，見本檔「2026-11-13 現行續行授權與止損」節；AccessDenied1/1 保留為歷史。）

**2026-11-14 STOP（未結案）**：同M7HomeAuthority／runGameTestM7 fixture三次非預期（665空A UUID／3ec初載範圍／6ae400tick卸載）3/3，依user止損＋root分組停報；sourcea5061383、定向HEADee2437e凍結，已在途996自然PASS不抵銷。N0/full/CI/push與下一task全部HOLD，僅只讀必要收尾／本機truthdocs；詳M7 note及SDD t3-fixture-stop.json，不自行恢復。


**新增**　`P/collapse/{HomeEndpointAuthority,HomeRootTickets}.java`、`J/corridor/M7HomeCoverageTest.java`、`G/M7HomeAuthorityGameTests.java`。

**修改**　`P/chamber/{ChamberCollapsePort,ChamberSessionGateway,ChamberSessions}.java`、`P/collapse/{CollapseAuthorities,ProjectionProtectionIndex,PassageTransferPolicy}.java`、`P/corridor/{CorridorPageManager,SlotAllocator}.java`、`P/superposition/SuperpositionSessionManager.java`、`src/testmod/resources/fabric.mod.json`、`S/m7-inventory.json`。

**執行接線補記（2026-11-14）**　新corridor JUnit／GT由受控inventory驅動正式runner／exact驗證，故gates.ps1、m7-ci-gametest.ps1、ci-rule-check.ps1同task最小同步；ChamberPowerCoordinator、SuperpositionSession、SessionRecoveryState及testmod原生票fixture按本task權威／占用／共用coverage契約必要接線，由同一task reviewer核。順序為RED/GREEN／mutation／focused齊→獨立C/I0→固定pin完整full→同SHA五required CI，全門檻不減。

**消費**　T2 全體 current/flushed 與 `HomeSessionRecord.EntryEndpoint`；既有 projection ledger、catalog、Universe lifecycle、registry、ChamberFrame、`PassageTransferPolicy.preloadChunks(ChamberFrame)`。

**產出**　`HomeEndpointAuthority.resolveEntry(ServerWorld,ChamberControllerBlockEntity): Optional<EntryEndpoint>`、`checkRoot(MinecraftServer,ChamberOriginAuthority,boolean requirePower): RootStatus`（READY／UNKNOWN／LOW／DISABLED／INVALID_STRUCTURE／DESTROYED／IDENTITY_MISMATCH）；另產出 `inspectEntry(MinecraftServer,EntryEndpoint): EntryStatus`，值為 VALID／UNKNOWN／ENTRY_AUTHORITY_INVALID／AUTHORITY_CORRUPT／AUTHORITY_UNREADABLE，供 T4/T6 區分缺載、拒絕、永久與可重試封存。

**所有權**　`SuperpositionSessionManager.globalChamberBusy(MinecraftServer,UUID): boolean`、`localPresence(ServerWorld,BlockPos): ChamberSessionGateway.Presence`；`claimProvisional(MinecraftServer,UUID chamber,UUID sid): boolean` 使用既有 sessions map 值分支。既有 A start 同查，不能讓本 SID upgrade 被自己拒絕。

**票 API**　`HomeRootTickets.acquire(MinecraftServer,UUID,ChamberOriginAuthority,Purpose): Optional<Lease>`；`Purpose` 僅 START_CHECK／SELECT_CHECK／HOME_PREPARE_PASSAGE，`Lease.full(): boolean`、`close(): void` 在 server thread。T4–T7 只透過此 owner 借票。provisional、DORMANT、READY、CLOSE 無走廊 Space 時亦須序列同 SID。將既有 CorridorPageManager.exclusiveOperation 的 operation owner 擴充為同一 server instance＋SID guard，有 Space 仍守原重入檢查。


- [ ] RED：`only_exact_complete_projection_resolves`、`provisional_wins_before_first_ticket`、`sameXZDifferentWorldConsumesCoverage`；先錄現行 inert 成例不能辨識或舊 accounting 漏計的具名結果。

- [ ] 查 B block/BE/facing/live instance、唯一 COMPLETE exact site（含 chamber、U、OVERWORLD）、catalog EAGER_ENABLED descriptor 同 live、根 registry exact A；只用現行 projections port 加窄介面，chamber 套件維持既有依賴 guard。PENDING／foreign／假 UUID／錯 U／frame／遺失或退化／未確認全部拒絕。B BE UUID 可 null，完全無 registerOrigin。

- [ ] A 與 B/B 共用 server-thread 非重入 claim；查 provisional、current、flushed、quarantine、未確認刪除、DORMANT／SEALED／CLOSE。舊 start 亦經同查。provisional 作廢先 invalidate callback、撤票，確認無 ARMING／reservation 殘留才解除。journal unreadable 保守拒絕；歷史不重建鎖。

- [ ] root ticket coverage 是 preloadChunks 與 Controller 水平 ±3 聯集，exact world＋chunk；每 SID 1 份 ≤4 refs，server ≤64 份／256 refs 並納入既有 4096 coverage。map 內不同 SID 負責自己的 ref，重疊不可錯釋。走廊 reserve/restore 同時看 root。

- [ ] 使用非建立 FULL 查詢，不在 server thread 同步等 chunk；START/SELECT 各 200 ticks，PREPARE 第 200 tick 仍未知釋票、200→12000 backoff、原 SID 續行。READY ticket 只限原期限。release 部分成功、例外、取消、late callback、reload、SEALED、stop 與 SERVER_STOPPING 均冪等，journal 卡住亦可釋 process 資源。

- [ ] GREEN：測 1/4/64/256/4096 邊界、A與走廊相同 XZ、兩 SID 同 chunk、mock 部分 acquire 拋錯後真票計數歸零、201 tick 才 FULL；`a_and_two_b_entries_contend_once`（A/B 與 B/B 重入）、DORMANT／SEALED／ARMING 未知擋 start。尚不讓普通玩家操作 B。

- [ ] 變異 COMPLETE→PENDING、略過 live instance、先加票再 claim、world 從 coverage key 移除、取消 leak；測試逐一 killed／還原。focused 跑新 JUnit／M7 GameTest 與原 ChamberCollapseDependencyGuardTest、ProjectionProtectionIndexTest。

- [ ] Controller 同步、逐檔 stage T3 清單、候選 `feat: 核對回程兩端並共用艙占用與票額`。核 W10、完整 run-all、獨立規格／品質 C/I 清零；push／同 SHA CI 成功才 closed。

### Task 4: root fence、checked CLOSE、B 門交接與重啟


**新增**　`P/collapse/{HomeRootFence,HomeClosure}.java`、`J/persistence/M7CloseMatrixTest.java`、`G/{M7HomeClosureGameTests,M7HomeClosurePhases,M7HomeProbe}.java`、`G/mixin/M7HomeCloseWindowMixin.java`、`S/m7-home-probe.ps1`。（2026-11-17 更正：`G/M7HomeProbe.java` 與 `S/m7-home-probe.ps1` 原列在「修改」；兩者在 T1 收斂 `04d42f4` 刪除，T4 Phase B1 `aa12ace`、`8c7c08f` 重新新增。）

**修改**　`P/QuantumSuperpositionMod.java`、`P/chamber/{ChamberLifecycleService,ChamberRegistry,ChamberRegistryState,ChamberProtectionService,ChamberMaintenanceInteraction,ChamberCollapsePort}.java`、`P/collapse/{OriginDoors,HomeEndpointAuthority}.java`、`P/persistence/SessionRecoveryState.java`、`P/superposition/SuperpositionSessionManager.java`、`src/testmod/resources/{fabric.mod.json,quantumchamber-test.mixins.json}`、`S/m7-inventory.json`。

**擴檔（2026-11-17，Phase A 實際改動，controller 補列）**　上列以外另改：
- `P/persistence/SessionRecoveryManager.java`：HOME roll-forward 的唯一重試寫出者（Phase A 審查 I-1）。
- `P/chamber/ChamberSessionGateway.java`：只補 `Presence.BUSY` 的 Javadoc。
- `G/M7HomeAuthorityGameTests.java`（T3 fixture），共三次改動，授權來源各不相同：
  - `550f12e`（Phase A 首輪，依連續授權，不是使用者裁示）：
    - CLOSE 改由 T4 owner `HomeClosure.step` mint；
    - 非停止分支從 PARTICIPANT_CLOSED 改成真到期的 EXPIRED；
    - 新增「結案列不能由外部 caller 自宣完成」的斷言；
    - 兩趟改用 continuation 串接。

    這次改寫讓第二趟在第一趟的收尾回呼裡同步啟動，引入了 fastutil rehash 重跑。
  - `bd84b8f`：第二趟的輪詢改為逐 tick 排程，依使用者第 1 題授權的「一次有界調查＋修正」。
  - `62beae7`：開頭呼叫 `M7HomeClosureGameTests.requireCandidateStores`。這是 fix1 回歸途中發現的既有順序相依修正（新增 batch 後執行順序改變），依連續授權，不屬第 1 題授權。
- 新增 `G/M7HomeFenceStartupProbe.java`：只在 M7 GameTest run 註冊的探針（審查 M5）。
  - 在 `SERVER_STARTED` 事件裡、`HomeRootFence` 開服掃描所在的預設 phase 之前，用同一個 port 記錄一個不存在的艙能否 enable。
  - 由 `fence_rejects_enable_before_startup_scan` 斷言被拒。

上列中 Phase A 未改的檔（`ChamberRegistryState`、`HomeEndpointAuthority`、`M7HomeProbe`、`M7HomeClosurePhases`、`M7HomeCloseWindowMixin`、`quantumchamber-test.mixins.json`、`m7-home-probe.ps1`）留給 Phase B 或依需要再改。Phase A／B 的切分見 M7 note。Phase B／P1／P2 的實際擴檔見本 Task 末尾「擴檔（2026-11-17～18，Phase B／P1／P2…）」段；那段放在核取項之後，讓核取項的行號不位移（程式 Javadoc 與 SDD 報告引用了這些行號）。

**消費**　T2 的 `commitHomeRollForward/commitHomeRestore`、`HomeReceiptStore`、T3 exact Entry／global busy／票與既有 `ChamberDoorService`、`ChunkSaveCheckpoint`。

**產出**　`HomeRootFence.bootstrap(MinecraftServer): void`、`onRootEvent(MinecraftServer,ChamberOriginAuthority,RootStatus,long): boolean`（true 限 stop 已確認或沒有需 latch 的旅程）、`permitsEnable(MinecraftServer,UUID): boolean`。透過 chamber port 安裝，不直接反向依賴 collapse。

**交接**　`HomeClosure.step(MinecraftServer,HomeSessionRecord): void` 收斂已凍結 CLOSE，或由當下已核全跨／expiry／持久 root latch 決定原因。正式互動入口 `HomeClosure.onParticipantClose(MinecraftServer,HomeSessionRecord,ServerPlayerEntity actor,HomeClosure.DoorPhase phase,long observedAt): ChamberCollapsePort.DoorUse`，phase=PREPARE／PASSAGE；`HomeClosure.onEntryStopped(MinecraftServer,HomeSessionRecord,ServerWorld entry,HomeClosure.EntrySignal signal,long observedAt): void`，signal=LOW／DISABLED／INVALID（DISABLED 不可達、一律拒絕，見 spec「T4 ENTRY_STOPPED DISABLED 勘誤」）。在事件 guard 內核 actor、cohort、phase、exact B、當時計時與真實 signal 再 mint Evidence；無事件的 step 不補造 actor 或時間。RESTART_CLOSED 只由 startup owner 持當次 instance UUID 與 startup READY 集合送出，不能由一般 tick 假造。journal 新 `finishHomeClose(MinecraftServer,HomeSessionRecord,HomeClosureProof): TerminalResult` 的 proof 為本 instance／該 SID／該列／B門及 checkpoint／exact receipt 的一次性權威，由 close owner 驗證，不讓外部 caller 自宣已完成。proof 型別放 `HomeClosure` 的巢狀 `HomeClosureProof`。


**Windows native固定矩陣（T4的權威來源，不依賴已取消/可清除的T1 source）** `close-{ordinary|sealed|passable}-{cut}`；十三cut逐字為 `before-close,after-close,before-door,after-door,before-checkpoint,after-checkpoint,after-temp-force,before-publish,after-publish,after-readback,after-recheck,before-remove,after-remove`。三門態×十三cut=39列；每列唯一nonce/root/save，依序crash（預定halt77）→新獨立JVM reopen（正常stop0），保留實際原件。以M6 manifest/receipt/readonly+SelfTest做法精確比對此列表/phase/退出，不以單純總數39或改名窗口替代。這是原已採固定矩陣的移置，不新增高於M6的框架要求。

- [ ] RED：`ordinary_closed_door_needs_native_checkpoint` 在舊膜 cleanup 遇 NONE 會留下普通關門；`restart-enable-before-entry` 的首個 maintenance call 不應跳過尚未落盤的 stop；`permanent_seal_cannot_be_archived` 拒偽造 evidence。（T4 結案：`ordinary_closed_door_needs_native_checkpoint` 有具名 RED／GREEN；`permanent_seal_cannot_be_archived` 的 RED 不成立（T2 的 `HomeSessionRecord` 已拒絕，現行 production 原件 PASS），改以 JUnit `permanentSealCannotBeArchived` 守門加 Phase A M4 mutation；`restart-enable-before-entry` 的同 JVM 部分在 Phase A `root_disable_latches_before_ack_and_reenable` 有 RED／GREEN，跨 JVM 部分以 B2a 原生列加 rm1、rm2 mutation 證明，不是在舊 production 上取得的 RED，見 M7 note）

- [ ] 開服 strict journal＋registry／ownership 完成後、任何 maintenance enable／entry 操作前掃 active selected（含無 runtime 的 DORMANT），不掃 receipt。registry confirmed disabled/destroyed/exact 撤銷 → per-root fence，逐 SID first-wins event。已確定根停用再重啟先 enable 仍 pending；UNKNOWN 不 mint ROOT。初始化失敗維持阻擋。（T4 結案：開服掃描、含 DORMANT（fix1 M6、B2a rm1）、disabled 的重啟先 enable 仍 pending（B2a 原生主鏈）、初始化失敗（fix1 M17）都有具名證據；缺具名證據的是「UNKNOWN 不 mint ROOT」（只有 `HomeRootFence.java:116`；Phase A 審查只概括寫開服 fence 核實，沒有點名這個分支）與開服掃描遇 destroyed、exact 撤銷的分支，見 M7 note）

- [ ] `setEnabled`、`setOriginEnabled`、`completeRemoval`／真正移除的持久提交點（含原生 setter）與結構觀測都通知同服務。註冊順序保 journal／M5 ready；普通 LOW 不發通知。registry 已落、stop 未落不能回報成功／允許復啟；同 tick disable→enable 仍先 checked latch。選後全階段可同 state latch，包含無 B runtime／部分返還。A 保護自 B provisional 起到 checked 終局；正常拆 A 拒，但管理員停用仍可。（T4 結案：持久提交點（含原生 setter）的通知、同 tick 停用→啟用、journal 未收斂時排隊、registry 已落而 stop 未落不回報成功、A 保護都已完成；「結構觀測」的通知沒有 production 呼叫端（`checkRoot`、`onRootEvent(INVALID_STRUCTURE/IDENTITY_MISMATCH)`），依 Phase A 審查 M6 交 T5／T6，見 M7 note）

- [ ] 新事件優先序：未確定 move／永久 TRR → pending journal 收斂 → 既有 CLOSE → root latch／queue → 普通終局。TRR、ENTRY_AUTHORITY_INVALID、AUTHORITY_CORRUPT 不可歸檔；未返完／有 lease 時停止旅行、保留 pending 給 T5 的返 B／清租約，不提前 CLOSE。普通缺載 B 保持 pending，不改 A。（T4 結案：優先序與不可歸檔原因已在 `HomeClosure.decide` 與事件入口實作、JUnit 覆蓋，「未返完」依本項只做到不提前 CLOSE；「普通缺載 B 保持 pending」在 T4 以測試票保持 B 的區塊（B1 假設 (c)），B1 審查要求 T5 有正式 B 來源票後，以真票確認重開時 B 無人無票保持 pending，見 M7 note）

- [ ] 唯一 owner 依 §9.2 矩陣 mint 原因與 evidence：ALL_CROSSED、EXPIRED、PARTICIPANT_CLOSED（PREPARE/PASSAGE）、ENTRY_STOPPED（LOW/DISABLED/INVALID；DISABLED 不可達、一律拒絕，見 spec「T4 ENTRY_STOPPED DISABLED 勘誤」）、RESTART_CLOSED、四個 ROOT_*。要求 closed≥各原時間、actor 是本人、expiry 真到、來源 phase／observed exact。close latch 用 roll-forward，未確認不改門；checked 後列凍結。具名 `participantCloseKeepsActualActorAndPhase` 同一 READY 列分別由兩位參與者於獨立 fixture 操作，Evidence 各核原 actor／時間；無互動或錯 phase 不得由 step mint。加入非 startup 呼叫無法生成 RESTART_CLOSED。（T4 結案：其餘原因的 mint、`participantCloseKeepsActualActorAndPhase`、非 startup 不能生成 RESTART_CLOSED 都已完成（Phase A JUnit 具名 RED／GREEN、M3），DISABLED 依裁示一律拒絕；ENTRY_STOPPED 的 LOW／INVALID 正式 mint 路徑沒有呼叫端（B 端在 T6），目前沒有 GameTest 驅動與 mutation，交 T5／T6，見 M7 note）

- [x] 對 exact B，普通／sealed／passable 均實際套 DoorGoal，逐格驗門、所有 B 相關 chunks 原生 checkpoint，`publishOrVerify`、再驗 B 門＋checkpoint、checked remove（restore CAS）後放鎖。PARTICIPANT_CLOSED 防夾 CLOSE_IF_CLEAR_ELSE_OPEN，其餘 OPEN，不能以 ownerless membrane queue 代替。遇未知或錯配保原件與 busy；移除後不得修舊門。

- [ ] GREEN：正式 owner 的 `close-{ordinary|sealed|passable}-{cut}` 在Windows逐一以正式39列／兩phase（T1原生PoC已取消，不抵正式集合） 驗 §5.3；另跑 `receipt-existing`，另測 checkpoint unknown／跨檔不符／已存 target 異值／成功後玩家主動關門。逐 phase 核兩側門格、region、識別、active與歷史 hash、資源、零移動。經正式發布與刪活列完成 >4096 個互異 SID，仍可新 admission，舊單檔 exact 可讀，禁止全目錄掃描或虛增獨立歷史計數。（T4 結案：Windows 39 列／78 phase、`receipt-existing`、checkpoint 結果未知／跨檔不符／已存 target 異值／成功後玩家主動關門、>4096 都已完成；原生列的 B 區塊以測試票保持載入（B1 假設 (c)，正式 B 來源票到 T5 才有），B1 審查要求 T5 以真票重驗，見 M7 note）

- [x] T4 Windows正式39保持；native runner優先薄接固定M6 probe-common owned機制、PID+start、receipt hashes、readonly summary+SelfTest；沒有T1 PoC/退休額外提交或CI。超M6的全class/argfile/ZIP seal等標殘留不擋，不默認必做；如需提高標準先交使用者。功能契約仍由原39及正式owner斷言證明，既有M6標準不改。
- [x] 日期工具TODO：來源為使用者提供Claude pwsh7.7.0重現，原ParseExact object[]誤綁單format。若恢復此呼叫，format集合明確`[string[]]@(...)`（或依M6原解析做法，不新造框架）；具名正例3/6/9位小數秒均接受、錯格式拒絕，保實際原件。cast碼存在不能當三種都已驗；本T1不重演/改造日期工具。（2026-11-17 註記：T4 B2b-3 已 grep，`scripts/verification/` 沒有任何 `ParseExact`／`TryParseExact`，M7 runner 只用 `[DateTimeOffset]::Parse`、`ConvertFrom-Json -DateKind String` 與 cast；判定為未恢復此呼叫、不適用，未新增 3／6／9 位小數秒正例。這是 implementer 的結論，controller 採用，不是使用者裁示；核取框等 T4 完整回歸與 CI 通過後才勾。）（T4 結案：完整回歸 `run-all-5c0458b7…` 與同 SHA CI 44207705353 都已通過，依上述註記勾選，見 M7 note）

- [ ] `restart-enable-before-entry` 真跨 JVM 留 registry disabled 已存、stop 尚未落盤，startup 先 direct enable 再操作 B；另驗 DORMANT、同 tick 復啟、部分 Crossed、stop 已落後 A 修復均不復活，普通 LOW 無 latch。對已有 READY 無已確認 root 的 startup 僅 RESTART_CLOSED，不等待 A FULL。（T4 結案：跨 JVM 主鏈與 DORMANT、同 tick 復啟、部分 Crossed、stop 已落後 A 修復不復活、普通 LOW 無 latch、READY 無已確認 root 只走 RESTART_CLOSED 且不等 A FULL 都已完成（B2a）；「再操作 B」因 T4 沒有 B 端續行 owner，以 journal 的 MEASURED→COLLAPSING 提交邊代表，T5／T6 有正式 B 端續行後要以真實 B 端操作重跑（B2a-M3），見 M7 note）

- [x] 變異掃描僅有 runtime、略過 direct setter、close 前提省略 checkpoint、先刪 active 再發 receipt、漏核 existing exact、TRR 允許 ROOT close。focused 跑 M7CloseMatrixTest／M7 GameTest／上述全鏈與 SelfTest，確認每個 production mutation killed、還原。

- [ ] Controller 同步、逐檔 stage T4 清單、候選 `feat: 持久完成回程終局並重建停用防線`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。（T4 結案：沒有用這個候選 commit，改成 Phase A、B0～B2b-5、P1、P2 各段分開 commit；W10 由 run-all 的 w10-preflight 核對（兩次都 exit 0）、完整回歸（第一次 FAIL 後依使用者 2026-11-18 09:14 第 1 題裁示換 nonce 重跑）、各段審查 C/I 清零、push 與同 SHA CI 44207705353 都已完成，T4 已結案，見 M7 note）

**擴檔（2026-11-17～18，Phase B／P1／P2 實際改動，controller 補列）**　以 `git diff --name-status cc92b66..HEAD`（HEAD `e28784c`）與逐檔 `git log` 核對；該範圍內的 `AGENTS.md`、M7 note、本 plan 與 spec 是 docs commit `c74bf79` 的改動，不列。上方「新增」「修改」與 Phase A 擴檔以外，另有：
- production：
  - `P/persistence/ChunkSaveCheckpoint.java`：只改 Javadoc，寫明 journal 不在 checkpoint 判定之內（`864b0fb`，P2-M1）。
  - `P/persistence/HomeReceiptStore.java`：抽出純計算的 `receiptPath`，收據讀寫與收據 ERROR 共用（`a7772a6`，P2-M5 第 4 點）；Javadoc 寫明只按 SID 查詢、禁止列舉目錄（`3e2c8c7`，B2b2-M2）。
  - 上方清單已有的 `P/collapse/HomeClosure.java`（B0 `7acc564`）與 `P/persistence/SessionRecoveryState.java`（P1 `c9ef60f`、P2 `cea8d44`、B2b-1 `864b0fb`、B2b-2 `a7772a6`）也在本範圍內改過。
- JUnit：
  - 新增 `J/persistence/M7NativeSaveFailureTest.java`（P2 `dbbb0d4`，B2b-1、B2b-2 補案）、`J/persistence/M7HomeHistoryScaleTest.java`（>4096，B2b-2 `647a127`；B2b-3 拆段、B2b-4 strict 趟抽樣；B2b-5 `5bcfce4` 只改註解，`e28784c` 鎖定 86 個 strict 趟與各段首尾）。
  - 修改 `J/persistence/M7HomeHistoryTest.java`（P1 `6926e93`）、`J/persistence/SessionJournalSchemaFiveTest.java`（P2 `dbbb0d4`、B2b-2 `647a127`）、`J/persistence/{SessionRecoveryManagerTest,SessionRecoveryStateTest}.java`（P2 `dbbb0d4`）。原生 save 斷言改用 `checkedSave` 的對照見 M7 note 的 P2 段。
- testmod：
  - 新增 `G/M7HomeChainPhases.java`（B2a `fca6d31` 起）、`G/M7NativeSaveFault.java`（P2 `dbbb0d4`）、`G/mixin/M7HomeReceiptWindowMixin.java`（B2a Step 0 `c58c129` 自 `M7HomeCloseWindowMixin.java` 拆出）。
  - 修改 `G/M4CandidateRecoveryProbe.java`（P2 `dbbb0d4`、B2b-1 `a8755ce`；M4 `readback-fault` 的新預期，依使用者 17:1x 追加題）、`G/mixin/SessionJournalWindowFaultMixin.java`（P2 加 `@ModifyArg`）、`src/testmod/java/dev/quantumchamber/persistence/M7CapacityFixture.java`（B2b-1，`PRIOR_TEST_RESIDUE`）。
  - `G/M7HomeAuthorityGameTests.java`（T3 fixture）第四次改動：`27415b4` 只在 `until` 加 label（等待餘量記錄），上限 200 與斷言不變；依連續授權，不是使用者裁示。
- 驗證與 CI：
  - `build.gradle`：`m7HomeProbe` run（B1 `aa12ace`）、`test` 的 `testLogging`（B2b-3 `ebc723e`）。
  - `.github/workflows/build.yml`：新增 `m7-home-native`（四片 matrix）與 `m7-home-native-aggregate`（B2b-3 `436cb5d`），以及 runner 環境比對與 `try/catch`（B2b-4 `926425c`）；`m7-validation` 新增規模段的 3 個 step（B2b-5 `5bcfce4`，依使用者 2026-11-18 規模測試裁示）；既有 job 與既有 step 不變。
  - `S/gates.ps1`：:470 M7Runtime 總時限 180→600（`e652c23`，依使用者 11:0x 第 2 題）；新增 `-M7Tests`、`M7ScaleRuntime` 與 `M7ScaleTest`（B2b-5 `5bcfce4`）。
  - `S/m7-ci-gametest.ps1`：新增 `-Segment Main|Scale`，`--tests` 改由各段 exact 集合產生，兩段互斥、聯集等於原始碼宣告（B2b-5 `5bcfce4`）。
  - 上方清單已有的 `S/m7-inventory.json`：B2b-5 `5bcfce4` 升 schema 4，拆成 Main 48 與 Scale 11。
  - `S/run-all.ps1`：recovery 的 `readback-fault` 分支（P2 `dbbb0d4`）；新 step `m7-home-native` 與 B2b1-M4 的直接核對（B2b-3 `434c858`）；summary step 的 `m7HomeNative`（B2b-4 `636d3a5`）；新 step `m7-scale` 與 `m7HomeScale`（B2b-5 `5bcfce4`）。
  - `S/m4-recovery-probe.ps1`：M4 `readback-fault` 的新預期與 `stopSaveCalls`／`universeUnchanged`（P2 `dbbb0d4`、B2b-1 `a8755ce`）。
  - `S/README.md`：M7 home probe、B2a、>4096、run-all 與 CI 接線、規模測試獨立一段各節（B1 起各段，含 B2b-5 `5bcfce4`、`e28784c`）。
- 上方「修改」清單中，整個 T4 都沒有改的檔：`P/chamber/ChamberRegistryState.java`、`P/collapse/HomeEndpointAuthority.java`。

### Task 5: 從 B 入走廊、唯一 HOME 選擇與保留式返 B


**新增**　`P/collapse/HomeDoorInteraction.java`、`P/corridor/HomeDoorLayout.java`、`J/corridor/M7HomeDoorLayoutTest.java`、`G/{M7HomeCorridorGameTests,M7HomeCorridorPhases}.java`、`R/models/block/quantum_bulkhead_home.json`。（v1.6 追加見檔尾「2026-11-18 v1.6 修訂」節。）

**修改**　`P/superposition/{SuperpositionSessionManager,SuperpositionSession}.java`、`P/persistence/{SessionRecoveryManager,SessionRecoveryState,MeasuredWorldSaveCheckpoint}.java`、`P/corridor/{CorridorPageManager,CorridorGeometry,CorridorRepositionService,SessionEntranceDoorService}.java`、`J/chamber/MembraneStateTest.java`、`P/chamber/{QuantumBulkheadBlock,ChamberCollapsePort}.java`、`P/candidate/CandidateDoorInteraction.java`、`R/blockstates/quantum_bulkhead.json`、`G/M7HomeProbe.java`、`src/testmod/resources/fabric.mod.json`、`S/{m7-inventory.json,m7-home-probe.ps1}`。

**消費**　T2 `admitHome`／兩種 CAS、T3 `resolveEntry/checkRoot/claimProvisional/acquire`、T4 stop fence／CLOSE；`MappingRef(UUID sessionUuid,long instanceEpoch,int slotId)` 為既有 exact 物理 owner。

**產出**　`SuperpositionSessionManager.startHome(ServerWorld,ChamberControllerBlockEntity,List<UUID>): ChamberSessionGateway.StartResult`（僅 T7 掛正式啟動）；`HomeDoorLayout.key(UUID): DoorKey`、`HomeDoorInteraction.onUse(ServerWorld,BlockPos,ServerPlayerEntity): Optional<ActionResult>`；`SessionRecoveryState.finishHomeReturn(MinecraftServer,HomeSessionRecord): TerminalResult` 僅容許未選 RETURNING、全員已返 B、最後實體清理/checkpoint 已驗的受守護移除。

**交付界線**　testmod 明確以這些正式方法驅動閉合 route；正常玩家的 B 入口仍維持原 guard。選後清理結果保留真 HOME 列，供 T6 PREPARE。


- [ ] RED：`home_station_one_has_25_cells_in_four_orientations` 若沿原一般生成則多門；`same_key_new_generation_or_player_never_selects` 與 `cancel_or_restart_returns_to_b` 核舊邏輯不會成功服務合法 HOME／同 key stale。

- [ ] server thread 核 B 完整、FULL 供電鄰域、即時 ON、封艙、cohort／QuantumState、exact 來源；取得 provisional 再借 A START_CHECK；當次成功後在同 owner 重驗 B、全 cohort、原人／門；此時凍結快照、租走廊、checked ARMING。未確認不得建幾何／動玩家。unknown 寫入進既有 abort/quarantine 收斂，不能因 REJECTED 放行另一端。

- [ ] 建構與 startup restore 的 space/source ticket、參與者快照、取消、JOIN 下一 tick、幾何 checkpoint 全使用 explicit Entry=B，舊 SessionRecoveryRecord.origin() 與 legacy path 不變。正式 startHome 在任何 claim／ticket／reservation／ARMING 前先呼叫與舊 A 相同的 PlayerCheckpointStore.unsupportedReason(server)，非 Windows 受控拒絕。Windows 保持既有入場順序：checked ARMING → 逐人 native move → 重驗位置與 cohort 效果 → 逐人 PlayerCheckpointStore.saveAndVerify → 重驗位置、exact B 與效果 → checked SUPERPOSITION → commitInitial 發布 mapping。`last_checkpoint_fails_after_native_moves` 在最後一人 checkpoint 前注入失敗，驗沒有發布 SUPERPOSITION 或 mapping，依 checked ARMING 進度安全回 exact B。Ubuntu 驗拒絕前後 player pose、幾何、票、鎖、active journal 完全一致；未選取消、牛奶／任一凍結者失效整組返 B、不退款。exact B 不可用保持托管，無 A/spawn 替代。

- [ ] 幾何 builder、overlay、refreshCandidates、remap／頁面伸展與 interaction 同一 HOME mode。入口 station0 lateral overlay 25 格不碰；四朝向 station1 POSITIVE 側唯一完整 25 格 bulkhead，其他側格為牆。HOME 門新增布林 state `home_marker` 預設 false，單一既有材質的不同模型，舊膜／候選模型照常。修改 `MembraneStateTest` 的全狀態 oracle，舊資料無 marker 預設 false；每一可列舉 state 恰一 variant。關閉且 home_marker=true 取 HOME 模型，關閉 false 取舊 bulkhead；open＋NONE 仍 air，open＋膜仍原膜模型及 X/Z 旋轉（含 marker 的指令組合）。一般門不產生候選、entropy、ledger、機率或文字。

- [ ] 點門記原 SID/player entity/world、pos、原 MappingRef／generation；借 SELECT_CHECK。FULL 後回 server thread 進原 session exclusiveOperation，重抓原 25 格 owner／mapping、bbox 完整在唯一 mapping、可互動距離、玩家在線活著非旁觀未 removed、全凍結人 Buff／未 Returned、leases ready、current=flushed=SUPERPOSITION 未選、B 身分電位封艙與 A 當下身分結構 enabled／電位。stale／取消／reload／晚到只釋該票。

- [ ] checked MEASURED＋HOME first-wins 後釋 SELECT_CHECK；不能改選門者、cohort、Root/Entry。已選後藥效失效不撤銷。依既有保留式流程回 B、記 Returned、按 checkpoint 清走廊／lease；MEASURED DORMANT 持鎖不占玩家，stop 有效時交 T4，不重新選門。未選 RETURNING 走無歷史 checked remove。

- [ ] GREEN：四朝向 25∩入口25=0、真互動成功／換頁無第二扇；A 探索 SOURCE fast path 與舊 profile 仍可選。延遲中變更 mapping、death/spectator/replaced／離線／離開 bbox、cohort Buff 已到期但尚未 tick 取消，以及 A FULL 入隊後轉 LOW 各零 selection。在接近 16 MiB 的合法混合 journal 由正式 `SuperpositionSessionManager.tick→CorridorRepositionService.tick` 驅動 `formal_tick_capacity_pending_preserves_home_mapping`：換頁前追蹤 `allFlushedSessions()`、以 `JournalSession`／typed HOME 分派，舊路徑沿原 predicate。PageManager 與正式 reposition 將容量不足歸為可重試 pending，不送入舊例外轉 RETURNING；若已暫保留新 slot，撤回未發布的額外資源，保舊 mapping、原 SID、未變 Selection 及 close 空間，容量恢復後重試。`MeasuredWorldSaveCheckpoint.ready/save(MinecraftServer,HomeSessionRecord)` 共用與既有 overload 相同的原生 checkpoint，相關 chunks 僅 exact live B frame＋走廊 leases；A 不可入列。另在正式 remap 時讓舊候選 append 嘗試侵占新舊 lease 預留；拒絕者留下既有 mapping／SID／可用 CLOSE 餘額，已承諾 remap 可完成。兩人同 tick 只一個 exact HOME；A 停用前後／未選重啟／離線 JOIN 僅回 B。

- [ ] 新 native chain `corridor-recovery` 各在 ARMING、SUPERPOSITION、選擇未確認、MEASURED、部分 returned、cleanup checkpoint 前後切 JVM。核原玩家快照／Returned／lease／選擇存續相符。參與者回 B 不能錯送 A。

- [ ] 變異 station1→0、refresh 走一般 resolver、只比 DoorKey／忽略原 entity、跳過 A 二次電位、將 Entry 回傳改 Root、返還給回原 Buff，各由具名 test killed／還原。focused 新 JUnit／M7 GameTest／corridor-recovery 及舊 MembraneStateTest（全部模型 variant）、M2/M4 對應 suite。

- [ ] Controller 同步、逐檔 stage T5 清單、候選 `feat: 從投影進入單門走廊並可靠返還入口`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。

### Task 6: HOME 準備、B 膜與 canonical 原生傳送


**新增**　`P/collapse/HomePassage.java`、`P/universe/{CanonicalHomeTarget,CatalogUniverseTarget,HomeTransferResult}.java`、`J/universe/M7HomeTransferTest.java`、`G/{M7HomePassageGameTests,M7HomeTransferPhases}.java`、`G/mixin/M7HomeTransferWindowMixin.java`。（v1.6 追加見檔尾「2026-11-18 v1.6 修訂」節。）

**修改**　`P/collapse/{CollapseService,EntryWatch,PassageService,OriginDoors,PassageTransferPolicy,HomeClosure}.java`、`P/universe/UniverseTransferService.java`、`P/persistence/SessionRecoveryManager.java`、`G/M7HomeProbe.java`、`src/testmod/resources/{fabric.mod.json,quantumchamber-test.mixins.json}`、`S/{m7-inventory.json,m7-home-probe.ps1}`。（v1.6 修改追加 `R/lang/{zh_tw,en_us}.json`，見檔尾「2026-11-18 v1.6 修訂」節 Task 6 追加項。）

**消費**　checked HOME、T5 exact Entry/Returned/走廊清理、T3 `HOME_PREPARE_PASSAGE`、T4 root stop／close、原 `UniverseTransferService.NativeMove` 與 PassageTransferPolicy。

**產出**　`HomePassage.tick(MinecraftServer,HomeSessionRecord): void`；`UniverseTransferService.moveToHome(ServerPlayerEntity,CanonicalHomeTarget,TransferSafety): HomeTransferResult`。target 內有 exact startup/server、原 ORIGIN、live canonical World 與經核 exact Entry。舊 `moveToUniverse(ServerPlayerEntity,UniverseWorldDescriptor,TransferSafety): UniverseTransferResult` 內用 `CatalogUniverseTarget`，保持原介面與 non-null catalog receipt。


- [ ] RED：`canonicalHomeNeverCreatesCatalogOrWritesRoot`、`failedNativeRollbackNeedsActualObservation`、`partial_crossing_preserves_deadline`。只靠既有 returnToSource／catalog route 無法滿足。

- [ ] selected MEASURED→COLLAPSING（commit≥selectedAt），保原進度，再完成尚欠返 B／清 lease；所有人 Returned/空 lease 才 PREPARE。無 stop 時 DORMANT 沿原觸發／退避，不因新 instance 擅自開膜。PREPARE、READY 提交及每人跨越前各重驗 exact A 身分、enabled、結構、FULL；選後不查 A 電位作允許。UNKNOWN 只停跨、限 200 票、釋票退避，READY 原 expires 繼續。

- [ ] B 的 PREPARE／SEALED 膜守實體防夾、非參與者可離開、即時供電與來源資格；checked READY 才讓 B 門 passable／比較器15，opened/expires 不可變、expires=opened+1200 且 long 無溢位。A 備妥不寫 A 門／容器／projection／FIRST_WRITE，四種 root 失效進 T4，不封成等待修復。

- [ ] transfer 在三階段沿 M5 PASSAGE safety（finite、FULL、高度／邊界，無碰撞／流體／地板／安全 Y）；膜四朝向只限 frozen cohort 非騎乘，以原 XYZ／yaw/pitch／velocity。共用 native move、逐欄 postcheck、rollback；以 `server.getOverworld()` 與 exact live root 取當次 target，無 fake UniverseId／descriptor。

- [ ] `HomeTransferResult` 四 outcome：REJECTED_BEFORE_MOVE 無 native/rollback；MOVED 有 exact target、native+postcheck 且無 rollback；FAILED_ROLLED_BACK 有失敗 move 與實際回 B pose；FAILED_RECOVERY_REQUIRED 保留已觀測證據、進永久 SEALED 恰一 RecoveryRequired。僅 off-thread 可省 raw source；非有限保 raw observation。TRR 不重送、不拉回，每 instance 管理員 ERROR 一次。

- [ ] MOVED→單步 roll-forward Crossed，確認後立即 savePlayerData。未確認先停其他 move／close，僅收斂，不用位置猜測。rolled back 者仍在 B，可在未過期原通道重試。在 T6 `OriginDoors`／`PassageService` 以事件中實際 ServerPlayerEntity、DoorPhase、觀測時間調 T4 `onParticipantClose`；EntryWatch 核實 B 的現場 signal 與時間調 `onEntryStopped`。全跨完／expiry 交 step；startup 只走 T4 專屬入口。

- [ ] AUTHORITY_UNREADABLE 只在新 instance 重試一次；未曾 READY、無 stop、當次恢復可 restore 回 COLLAPSING；曾 READY 只能 RESTART_CLOSED 原 timing／Crossed。永久 authority／TRR 不能 close 或自動重試。無 B 或 unknown 權威保持保守，資源依 T3 釋放。

- [ ] GREEN：真 player 跨越（含跳躍、流體、腳下空、A 門關），兩人一跨一留、騎乘／非參與者拒傳，前置拒絕零 move、半改後 rollback 正確與 rollback 不可能的真證；比較 A 內容／門／既有 B projection ledger、allocation、catalog、discovery hashes／count，所有 HOME A-write hooks=0。

- [ ] 三個需要正式成功入場的 Windows native chain 各有 fresh save/journal及不同 JVM 登入：`move-before-crossed` 強制最後成功 playerdata 在 B，核 Crossed 未確認、依真檔登入；`crossed-before-player-save` 已確認 Crossed＋舊 B playerdata，重登仍可在 B 且不補傳；`move-and-save` 已確認兩者，重登 A。另驗 `ready-unreadable-restart`（期限前／後）、`late-full-prepare`（201 tick 遲到 FULL），每案零重播／延時。

- [ ] 變異 canonical 換同 key 外來實例、選後查 A 電位、PREPARE 先開 B 膜、postcheck 減一欄、假 rollback 成功、先存 player 再 checked Crossed、startup 重開 READY。focused 跑新 JUnit／M7 GameTest／上述五鏈與 SelfTest；逐一 killed 並還原。

- [ ] Controller 同步、逐檔 stage T6 清單、候選 `feat: 由入口量子膜原位返回 canonical 原艙`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。

### Task 7: 開放正常 B 操作、在地提示與端到端回歸


**新增**　`G/{M7HomeEntryGameTests,M7HomeEntryPhases}.java`、`J/chamber/M7HomeFeedbackTest.java`、`docs/manual-acceptance/2026-11-13-m7-manual-smoke.md`（先建未執行清單）。（v1.6 追加見檔尾「2026-11-18 v1.6 修訂」節。）

**修改**　`P/chamber/{ChamberPowerCoordinator,ChamberActivationService,ChamberControllerLoadSyncQueue,ChamberRedstoneService,ChamberControllerBlock,QuantumBulkheadBlock,WorldChamberDoorAdapter,ChamberProtectionService,ChamberSessionGateway}.java`、`P/collapse/{HomeDoorInteraction,HomePassage,HomeClosure,OriginDoors}.java`、`P/superposition/SuperpositionSessionManager.java`、`R/lang/{zh_tw,en_us}.json`、`G/M7HomeProbe.java`、`src/testmod/resources/fabric.mod.json`、`S/{m7-inventory.json,m7-home-probe.ps1,README.md}`、`G/{M5CollapseWindows,M5CollapseDormantChains,M5CollapseFixture,M5UniverseProjectionGameTests}.java`。

**消費**　T3 的 `globalChamberBusy/localPresence`、T4 startup fence、`onParticipantClose/onEntryStopped` 收當時真事件、T5 `startHome/onUse`、T6 `HomePassage.tick/moveToHome`。T1–T6 全部 closed，三項 spike 和正式 owner 證據可由清單讀回後才啟用。

**產出**　實際紅石／關門、載入同步、門互動、膜與 lifecycle 接通同一套正式 HOME 路徑；沿用現有呼叫端 contract。公開行為由 `formal_projection_trip_uses_real_controls` 與 `formal-home-trip` 鏈固定。


- [ ] RED：現有正式入口 `formal_projection_trip_uses_real_controls` 在確認合法、完整供電 B 關門後仍 inert。記 A 舊探索正常作對照；禁止以直接呼叫 startHome 充當本案成功。

- [ ] 以 T3 checked COMPLETE route 取代 power coordinator 合格 B 的 inert 分支。無效／PENDING／role 錯留 comparator 0；B 開始檢查、進走廊、返 B、封存與 READY 各對 local owner。其他合法端 busy 11，B 等待／封存 11、READY 15。A 保護與 A 的比較器顯示 busy，但不能因 B presence 在 A 開關膜、A LOW 誤取消或讓普通玩家拆艙。

- [ ] 貼近操作時機送 translatable keys，兩份 locale 同 key、無 UUID/schema/token。至少 `message.quantumchamber.m7.{root_unpowered,root_disabled,root_destroyed,root_invalid,checking_root,check_timeout,chamber_busy,return_stopped,transfer_failed}`、`title.quantumchamber.m7.home_arrived`。覆蓋「正在檢查原艙…」「原艙檢查逾時，請稍後重試」「這座艙已有旅程進行中」「已回到原本世界。」；停用/失效/未跨/回滾按實際結果。已到 A 者不再收到通道關閉 actionbar。

- [ ] 整合 load-sync、regular redstone refresh、兩端門攔截、首次／startup recovery、player JOIN、reload 與 stop。原 root 事件停旅行並保持 A 保護至 checked 終局；成功後 B 普通開門，玩家實際重新關門才有新 SID。不得把 A 復啟、未到期 Buff、原玩家仍在 B 或 receipt 本身當新 start。

- [ ] 舊 W4/W6 與 recognition oracle 在 T7 一起修：W4 的 PENDING／其他未證權威端顯示 0；W6 在 JOIN 前同艙舊列仍 busy、該 COMPLETE 端權威已核實則 11。recognition 案逐一核同艙已確認 COMPLETE 11、未核實／異身分投影 0。保留保護、無新 Origin、不能只靠載入／上電自啟動，使用 allSessions/allFlushedSessions 的 typed Entry 查無新 B session。保留所有舊 FAIL，不重定義 M5 歷史證據。

- [ ] GREEN：Windows `formal-home-trip` 以正式 A 旅程產 B COMPLETE、B 正常啟動 → 唯一門 → 回 B → 膜 → A；核每個 SID／Root／Entry、兩端比較器、門、標題、實際 pose、A 舊內容，testmod 只能觀測／安排世界狀態。B re-entry 可由其他合法投影發起，不能建新 ORIGIN。

- [ ] `root_stop_unlocks_only_after_checkpoint` 標定 R1–R4：選後 A LOW 仍跨，A 停用／管理員拆除、已跨與未跨分留 A/B、舊 SID 另檔保留、解鎖後新 SID 未選、有效效果可沿用、過期須補。新 SID 仍須 start/select 檢查，無需先出艙。半返還與 receipt pending 維持 lock/保護。

- [ ] `formal_a_and_b_reload_independently` 驗兩端 reload 後可開新 session；重疊 A check 取消與釋票、已 checked HOME 保留。重跑既有 M4 SOURCE、M5 catalog 路徑、M6 各 profile、同 U respawn、Nether／End shared、legacy return-only。供電、藥效與 cohort 限制沿 M2/M5；home capability 1、family 0。

- [ ] 變異 projection guard 接受 PENDING、A 使用 B local presence、跳過 A 保護、舊 receipt 促自啟動、錯端比較器、向已抵達者發關膜通知，各由具名正常入口／訊息產生器測試 killed／還原。focused 新 JUnit、兩平台各自M7全GameTest與Windows native／SelfTest；Windows `formal-home-trip` 與 `m5-collapse-probe.ps1 -Only w4,w6`，原 `gates.ps1 -Mode M5GameTest -Label m7-recognition` 的完整 M5 scope 中必有 m5_universe_projection_recognition 具名案例。Ubuntu 以 `formal_projection_checkpoint_unavailable_has_no_side_effects` 核同一普通入口的拒絕、零副作用。

- [ ] Controller 同步、逐檔 stage T7 清單、候選 `feat: 開放已確認投影的正規回程操作`。核 W10、完整 run-all、獨立規格／品質 C/I 清零，push 讀回同SHA CI、兩平台JUnit/GT與封裝、Windows main-only/native必要步驟成功才 closed。

### Task 8: 整分支驗收、人工 smoke 與整合交回


**修改**　`docs/manual-acceptance/2026-11-13-m7-manual-smoke.md`、`docs/implementation-notes/2026-11-13-m7-return-home.md`、`AGENTS.md`、本計畫。依賴 T1–T7 全數 closed，採實際 release JAR、HEAD 與證據，沒有新 production API。（v1.6 追加見檔尾「2026-11-18 v1.6 修訂」節。）
**消費與產出**　讀上方 gate 原件、V01–V11 索引及 H01–H05；產出逐項結果、已知 crash 界線、未驗事項、基準範圍與待核可整合狀態。


- [ ] 先驗具名負控：把複本中的 M7 一例改 skipped、移除 chain／篡改 HEAD、將 GameTest receipt 充當 main-only，各應拒絕；確認 PENDING、失敗或漏 H 列的交付檢查不能記里程碑已完成。只動 fixture，保留原件。

- [ ] 核 final code pin、重新執行 milestone 完整 run-all／Windows正式當期M7 native/negative oracle（T1 PoC不存在）、兩平台JUnit/GT精確集合／release+sources／Windows獨立main-only（mod list、runtime classpath/argfiles/DLI、exact PID class-load，唯一合法 production ModPresenceProbe）。產出各種集合、hash、全 SHA 與零略過 M7。純 docs 本身豁免 production mutation／另跑 local full，此處明定的是 milestone 最終 full。

- [ ] 對 `2255027..候選` 做 whole-branch 規格／品質審查，檢五個焦點、完整路徑與 V/H 對照。C/I 有事項回 owning task，production 修正再跑完整門檻；任何修正均保舊原判，採新的有效 code pin。文檔列出的 READY 跨檔窗口不可宣稱原子。

- [ ] 取得本次人工代操作同意後才由 Claude 操作，使用有備份、已核路徑／鎖／身分的專用世界。紀錄環境、JAR SHA、實際按鍵與畫面、兩端狀態、玩家位置及 evidence digest。沒有同意時交回可審清單，等待授權，保持 T8 OPEN。

- [ ] H01 單人 A 探索 → 真 B → HOME → B 膜 → A；觀察到達 title、同XYZ、A 容器內容，另列 A 原門當時關閉之落點／碰撞實況，重登與完全重啟實際存檔世界。任何玩法變更需求先交使用者。

- [ ] H02 A 未載入、啟動前 OFF、選前 OFF、選後 OFF 四案各核行為；selected 後 disabled／管理員拆除停止，B 普通開門、鎖釋放、歷史保留，修 A 不復活；有效 Buff 新操作新 SID。

- [ ] H03 dedicated 兩人一跨一留、其他端同時啟動被 busy 拒絕，原期限到已跨者不回拉、未跨者留 B。

- [ ] H04 B 牛奶／Buff 到期回 B，記錄兩個不同 world 身分；B 與 A 各一次 `/reload` 後開新 session，A 一般探索正常。

- [ ] H05 以 release JAR、真 client、main-only 實跑；看唯一可辨識 HOME 門、其他位置為牆、B 量子膜與回家 title，不能用 testmod run 抵用。

- [ ] Controller 更新 note／入口／人工清單，逐檔 stage T8 清單，提交 `docs: 記錄 M7 完整驗證與人工結果`。文件一席獨立審查、定向核文件連結與證據、正常 push，同 SHA CI 全部 required job 成功才關 task。保留未解實測差異，不提前勾選。

- [ ] 收尾證據齊全後分別取得 main ff-only、tag 授權；先核主 checkout 的 6 份未追蹤原檔與 note hashes，有阻擋照 note 交使用者決定。整合後讀回遠端 main／tag object 與 peeled，docs 寫真實順序。worktree 退役獨立授權；M8 另確認。PR／LICENSE 未經特別指示不建立。

## 驗收索引

| 規格 | 可驗條件與 owning task |
| --- | --- |
| V01 | T1 邊界實測、T2 schema1–6／單檔、shared writer／64 lease remap／close 餘額、舊 build 拒6；T5 live remap 拒絕保持原 mapping。 |
| V02 | T3 exact COMPLETE/canonical registry/live B 及所有拒絕，T7 正式開門防線與零 registerOrigin。 |
| V03 | T3 200 ticks 與 A FULL，T4 lifecycle latch／開服 enable fence，T6 late-full-prepare 與原期限，T7 LOW／disabled 分流。 |
| V04 | T5 exact B、JOIN 下一 tick、取消／牛奶／藥效、跨 JVM 回收；T7 原 A SOURCE regression。 |
| V05 | T3 A/B、B/B、既有占用；T5 stale mapping/玩家/全 cohort／A 入隊後 OFF；T4/T7 ordinary 拆 A 拒至收尾。 |
| V06 | T3 4／64／256／4096、200/1200、全部釋放路徑；T5 reload、T6 UNKNOWN 退避與終局。 |
| V07 | T6 canonical target、四 outcome 實際移動／rollback evidence、零 A 改寫與零 catalog 副作用；T7 正式再驗。 |
| V08 | T5 四朝向 station1 25 格與入口零交集，其他頁為牆／無候選；T7 A 一般候選／profile／SOURCE 回歸。 |
| V09 | T6 四朝向、空／流體／跳、兩人／騎乘／外人、實體防夾／關門／LOW／到期；T7 真入口比較器與提示。 |
| V10 | T4 正式 close 全切點、>4096 旅程、任意改 evidence 拒絕與重啟 fence；T5 partial return；T6 三個真正獨立移動窗口、曾READY不可重開；T7 新 SID 沿 Buff。 |
| V11 | T2 travel1、T7 family0、shared Nether/End、舊 session、U respawn；T8 final full 獨立核清單。 |
| H01–H05 | 各次實際操作及單人、兩人、release 的不同證據由 T8 同名五列逐項採計，逐列須有結果與實際原件。 |

本計畫列的是待執行工作。SDD 的 `progress.md` 記錄實際 task、fix round、review／hash／CI 與阻點，tracked 文件由 controller 按事實同步。

## 2026-11-18 v1.6 修訂：新增 Task 4A／4B 與 T5～T8 追加項

依 spec「2026-11-18 v1.6 遊玩條件組合裁示（依使用者裁示）」節（下稱 v1.6 A～G）；使用者原文見 M7 note「最新：spec v1.6 修訂（2026-11-18）」節與「遊玩條件組合分析與使用者裁示（2026-11-18）」節。本節只增補，放在檔尾，前面各節行號不變；各 task 原有核取項不變，追加項與原項同樣由 controller 依實際證據勾選。

執行順序改為 T4 → T4A → T4B → T5 → T6 → T7 → T8。這是 controller 的排序，不是使用者裁示。T4A、T4B 都是 production task，走完整門檻。

AccessDenied 單次重跑規則的適用範圍：使用者 11:5x 第 4 題選「M7 從現在起適用（建議）」，選項說明原文是「M7 剩下的 T5～T8 都照這條。」提問時還沒有 T4A、T4B。controller 讀為「M7 剩下的所有 task」，含 T4A、T4B，依本檔 :18 與 AGENTS 的規則執行。這是 controller 的讀法，不是使用者裁示，會在下次回報時告知使用者。

### Task 4A: 親手重新關門 latch 與永久封存解除（含 M6 已出貨行為修正）

**新增**　`J/chamber/M7RearmLatchTest.java`、`J/superposition/M7SealReleasePredicateTest.java`、`G/{M7RearmGameTests,M7SealReleaseGameTests}.java`。「只剩永久封存」的判定抽成同 package 可單測的純函式，供 JUnit 逐一覆蓋。

**修改**　`P/chamber/{ChamberControllerBlockEntity,ChamberPowerCoordinator,WorldChamberDoorAdapter,ChamberDoorService,ChamberSessionGateway}.java`、`P/superposition/SuperpositionSessionManager.java`、`P/collapse/{OriginDoors,HomeRootFence,CollapseService}.java`、`J/chamber/ChamberPowerCoordinatorTest.java`、`src/testmod/resources/fabric.mod.json`、`S/m7-inventory.json`。runner 若仍有硬編清單，最小同步 `S/{gates,m7-ci-gametest}.ps1`，與 source／XML exact 同審。

**消費**　T2 journal 全體讀者、T3 `globalChamberBusy`、T4 root fence，以及既有 `OriginDoors`／`PassageService` 的終局路徑。

**產出**　Controller BE 的 latch：設定 public、清除 package-private；`SessionActions` 新增 latch 與「只剩永久封存」兩個查詢；gateway 新增 `permanentlySealedOnly(MinecraftServer,UUID): boolean`（default false）。T5～T7 只經這組 API 設定與檢查 latch。

- [x] RED：`prepare_participant_close_requires_player_reclose_windows`、`passage_participant_close_requires_player_reclose_windows`、`permanent_sealed_origin_dismantles_after_disable_and_power_off`；先在現行程式記具名失敗。「取消後立刻再出發」是推論：若前兩案在現行程式上不會自動出發，保留原件、回報 controller，比照 T4 的處理改以守門測試加 production mutation，不得改 fixture 硬造失敗。T4 的先例：GameTest 版 `permanent_seal_cannot_be_archived` 的 RED 不成立，改由 JUnit `M7CloseMatrixTest.permanentSealCannotBeArchived` 守門加 Phase A M4 mutation（:253 行末註記）。原地重建的座標權威案（`rebuilt_chamber_door_ignores_released_seal_after_restart_reapply`）在 T4A 之前不可達：在「已有解除路徑、尚未加座標核對」的中間候選上取得具名 RED；若實作順序取不到，改以變異「拿掉座標核對」證明並記下原因。（2026-11-18 T4A 勾選：前兩案在 `7a0e39d` 以 `REARM_DEPARTED_AFTER_PARTICIPANT_CLOSE` 失敗，取消後立刻再出發在 Windows 證實；永久封存案第一次是測試自己的診斷 NPE，原 root 保留，`fc78e13` 只改訊息順序後以新 nonce 取得 `PERMANENT_SEAL_NOT_RELEASED`；原地重建案在中間候選 `4acf64a` 取得 `REBUILT_DOOR_CHANGED_BY_RELEASED_SEAL`。root 見 M7 note「最新：T4A CLOSED（2026-11-18）」節。）

- [x] Controller BE 新增 NBT 布林 `RearmRequiresPlayerClose`（缺 key 讀成 false）。供電協調在 `candidateRetryRequiresLow` 之後、資格預覽之前，latch 為真回 IDLE；`startClaimed` 的同一個條件式也拒絕。DISABLED 優先回 INVALID；斷電不清 latch。

- [ ] `OriginDoors.terminateByDoor` 成功時、排入關門之前，設定原艙 latch；Controller 未載入時，在 `CLOSE_AFTER_TERMINAL` 的 `applyTarget` 之前補設。其他終局不設；未選門的整組返還也不設（v1.6 E）。（2026-11-18 T4A 未勾：終局時上鎖已實作，變異「改成排入關門之後才上鎖」被 `prepare_participant_close_requires_player_reclose_windows` 殺掉；Controller 未載入時的補設也已實作（`CLOSE_AFTER_TERMINAL` 套用前一律呼叫冪等的 `latchRearm`，不另設 pending 旗標），但沒有具名測試單獨覆蓋；依審查 4 的分析，拿掉補設或把它移到 `applyTarget` 之後，這兩個單獨的變異都不會被殺（這兩個變異未實跑，是審查 4 的靜態分析）。審查 4 判定風險低、不擋結案，補註解交 T4B Step 0（審查 M-2）。）

- [x] `WorldChamberDoorAdapter.toggleDeferred`：攔截回 PASS、`ChamberDoorService.toggle` 成功，而且整面門由開切成關時，在延後刷新之前清 latch（`toggle` 帶出切換後的狀態）。系統 `apply`、攔截 HANDLED／REFUSED、由關切成開都不清。

- [x] 永久封存解除：gateway 查詢只在該艙每一列都是已 checked 的永久封存，而且沒有 runtime、provisional、reservation 時為真。協調器的 COLLAPSE 與 BUSY 分支在 disabled 且 LOW 時設 OFF、回 INVALID；`HomeRootFence` 的拆除防線對同一條件放行；舊列（M5／M6）進入與開服時的 ERROR 字句（`CollapseService.java:1044,1100`）補解除路徑。HOME 永久封存目前沒有產生點與 ERROR，字句屬 T6 追加項。封存列、各 ledger、HOME 收據都不寫、不刪。（2026-11-18 T4A 勾選：ERROR 字句的解除路徑沒有具名斷言（審查 M-8，交 T4B Step 0），以 GameTest 的 log 原件證實進入時與開服提醒兩種 ERROR 都帶解除路徑；其餘子項的具名測試與 mutation 見 M7 note「最新：T4A CLOSED（2026-11-18）」節。）

- [ ] 原艙座標的門權威（v1.6 D，controller 的決定）：以原艙座標找到的舊列（M5／M6／M7），只在 registry 在該 frame 上仍登記同一個 Chamber UUID 時才取得門權威；否則視為無主，不攔截、不重套、不代關。涵蓋 `OriginDoors.sessionAt`、`watchDoor`／`reapplyDoor` 與待套用門的套用。保留「同位置可重建」。（2026-11-18 T4A 未勾：三處都已涵蓋，座標核對的兩個變異都被殺；但待套用門的核對只擋關門與套上量子膜的目標（`CLOSE_ON_SEALED`、`CLOSE_AFTER_TERMINAL`、`PASSABLE`），開門類目標維持原行為，比本行與 spec v1.6 D 的「待套用門的套用」窄。審查 3(a) 判定接受，記為 controller 讀法（spec G 節第 7 點）；另外 registry 紀錄的 ORIGIN、未拆除兩個子條件沒有具名測試（審查 M-3）。兩者都交 T4B Step 0。）

- [x] GREEN：上述 RED，加上下列具名案例。（2026-11-18 T4A 勾選：上述 RED 與下列案例共 14 個 GameTest、4 個 JUnit 在 `c091581` 全部通過；兩平台的 11 案在 Ubuntu 由 `099252f` 的 CI 44281662893 驗到；盤點沒有找到依賴取消後同艙自動再出發的既有案例。:481 是「或」，`applyTarget(CLOSED)` 那一支成立（`M7RearmGameTests.java:224-226`）。另以授權的直接寫方塊（`M5GameTestSupport.writeDoor`）代替 spec V12 的 `/setblock`（已供電的登記艙受保護，`/setblock` 會被拒絕）；這是 V12 字面上的差異，記在 M7 note「最新：T4A CLOSED（2026-11-18）」節的「GREEN」小節（審查 M-9）。）
  - latch（GameTest）：
    - `participant_close_latch_shows_idle_until_player_reclose`（兩平台）；
    - `rearm_latch_survives_power_cycle_enable_toggle_and_system_writes`；
    - `latch_survives_player_open_then_system_close`：上鎖後玩家開門，再由系統 `applyTarget(CLOSED)` 或 `/setblock` 關門，仍不出發；
    - `formal_start_refuses_latched_origin`：直接呼叫正式 `start`，被拒，零副作用；
    - `rearm_latch_survives_datapack_reload`：沿 M6 既有 `/reload` GameTest 的觸發方式。
  - 永久封存解除（GameTest）：
    - `permanent_sealed_stays_protected_while_enabled_or_powered`；
    - `retryable_or_unchecked_seal_never_released`；
    - `home_permanent_seal_allows_root_removal_after_disable`；
    - `rebuild_after_release_gets_new_identity_and_is_not_busy`。
  - 原地重建的座標權威（GameTest）：
    - `rebuilt_chamber_door_ignores_released_seal_after_restart_reapply`：解除、拆除、原地重建後，模擬重啟的第一次觀察與 chunk 重新載入，新艙的門都不被舊封存列改動；
    - `rebuilt_chamber_prepare_participant_close_still_cancels_windows`：新艙 PREPARE 中參與者關門照常取消。
  - JUnit：
    - `latchedOriginReturnsIdleWithoutStart`、`permanentSealOnlyReachesOffWhenDisabledAndLow`、`nbtRoundTripAndLegacyDefault`；
    - `M7SealReleasePredicateTest.onlyCheckedPermanentRowsQualify`：舊列四種與 HOME 三種永久原因逐一為真；可重試與 restart-only 原因、DORMANT、WAITING、pending、roll-forward、隔離、混有其他列、有 runtime／provisional／reservation 逐一為假。

  盤點既有 M5／M6 GameTest 與原生鏈是否依賴「取消後同艙自動再出發」；有就逐案列出交 controller 處置，不得自行改舊斷言。新案放 M7 run，不改 M5 run 的 60 案集合。

- [ ] 變異 production，每一項都要由對應的具名測試 killed，逐位元還原：（2026-11-18 T4A 未勾：14 項都逐位元還原，13 項由表列的全部具名測試殺掉；「忽略 rollForwardPending」只由 JUnit 殺掉，GameTest 沒有殺到，審查 3(b) 判定為可達狀態下的等價變異，見表內該列。focused 兩輪在乾淨 pin `c091581` 上全過。）

  | 變異 | 殺掉它的具名測試 |
  | --- | --- |
  | 拿掉協調器的 latch 檢查 | `prepare_participant_close_requires_player_reclose_windows`、`latchedOriginReturnsIdleWithoutStart` |
  | 任何方向的切換都解鎖 | `latch_survives_player_open_then_system_close` |
  | 系統 `apply` 也解鎖 | `rearm_latch_survives_power_cycle_enable_toggle_and_system_writes` |
  | 改成排入關門之後才上鎖 | `prepare_participant_close_requires_player_reclose_windows` |
  | `startClaimed` 不看 latch | `formal_start_refuses_latched_origin` |
  | 斷電清 latch | `rearm_latch_survives_power_cycle_enable_toggle_and_system_writes` |
  | `readNbt` 不讀新 key | `nbtRoundTripAndLegacyDefault` |
  | 可重試原因也放行 | `M7SealReleasePredicateTest.onlyCheckedPermanentRowsQualify`、`retryable_or_unchecked_seal_never_released` |
  | enabled 或供電時也設 OFF | `permanent_sealed_stays_protected_while_enabled_or_powered`、`permanentSealOnlyReachesOffWhenDisabledAndLow` |
  | 忽略 rollForwardPending | `M7SealReleasePredicateTest.onlyCheckedPermanentRowsQualify`、`retryable_or_unchecked_seal_never_released`（2026-11-18 T4A：GameTest 沒有殺到，在可達的 journal 狀態下是等價變異，只由 JUnit `onlyCheckedPermanentRowsQualify` 殺掉；審查 3(b) 判定成立，不必以反射造出不可達的狀態） |
  | 任何 HOME 列都放行拆除 | `home_permanent_seal_allows_root_removal_after_disable` |
  | 解除時改寫封存列 | `permanent_sealed_origin_dismantles_after_disable_and_power_off`（封存列位元組不變） |
  | 拿掉 `sessionAt` 的座標核對 | `rebuilt_chamber_prepare_participant_close_still_cancels_windows`（只有舊封存列在 `OriginDoors.sessionAt` 的走訪順序排在新 session 之前才殺得到；案例要先斷言或建構這個順序） |
  | 拿掉重新套用與待套用門的座標核對 | `rebuilt_chamber_door_ignores_released_seal_after_restart_reapply` |

  focused 跑兩個新 GameTest 類、`ChamberPowerCoordinatorTest`、`M7RearmLatchTest`、`M7SealReleasePredicateTest`、M7 全集合，以及受影響的 M5 door／passage 案例。

- [ ] Controller 同步、逐檔 stage T4A 清單、候選 `fix: 取消與回家後須親手關門才再出發，永久封存可停用拆除`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。（2026-11-18 T4A 未勾：沒有用這個候選 commit，改成 RED 兩筆、fix 兩段與 fix1 共五筆分開 commit（`5ae0a04..099252f`），逐檔 stage。run-all 的 `w10-preflight` step exit 0；完整 run-all 在 `c091581` 自然 `END ALL exit=0`；獨立審查 C/I 清零；已 push。但 CI 不是 production pin 的同 SHA：`c091581` 的 44259424086 FAIL 原判保留，結案採 fix1 `099252f` 的 44281662893（十個 job 全部 success），fix1 只改 testmod，依 B+C 採計，這是 controller 的判定。報告與證據 root 改用 `t4a-v16-*`，避免覆寫 T4 Phase A 的唯一原件。）

### Task 4B: 回程收尾的 B 有界票

**新增**　`P/collapse/HomeEntryTickets.java`、`G/M7HomeEntryTicketGameTests.java`。

**修改**　`P/collapse/{HomeClosure,HomeRootTickets}.java`、`P/corridor/{SlotAllocator,CorridorPageManager}.java`、`P/superposition/SuperpositionSessionManager.java`（初始化）、`J/corridor/M7HomeCoverageTest.java`、`G/{M7HomeProbe,M7HomeChainPhases}.java`、`src/testmod/resources/fabric.mod.json`、`S/{m7-inventory.json,m7-home-probe.ps1,README.md}`。新增原生列 `restart-close-unloaded-b` 會改變硬編的 46 列／92 phase，所以另改 `S/run-all.ps1`（:55-64 summary step 重驗、:294-301 `m7-home-native` step）與 `.github/workflows/build.yml`（:120 `m7-home-native` 的四片分配、:263 aggregate 的 `rows=46 phases=92`）。

**消費**　T3 root coverage 與 A 票、T4 `HomeClosure.converge`、開服集合與 `ChunkSaveCheckpoint`。

**產出**　`HomeEntryTickets.acquire(MinecraftServer,UUID,HomeSessionRecord.EntryEndpoint,Purpose): Optional<Lease>`，`Purpose` 恰為 CLOSE_CONVERGE／RESTART_VERIFY；`Lease.full()`、`close()` 在 server thread。`CorridorPageManager.reserveRootCoverage` 改收 world＋chunk。

- [x] RED：`close_loads_unloaded_b_with_bounded_ticket`（現行 B 無人無票時永遠 pending）、`entryAndRootSameXzDifferentWorldCountSeparately`（現行同 chunk long 會去重）。（2026-11-19 T4B 勾選，controller 的決定，比照 T4A :465：兩案都取得具名失敗，GameTest 是 `CLOSE_PENDING_WITHOUT_B_TICKET`，JUnit 是 `expected: <2> but was: <1>`，兩個 root 都記有 HEAD `6c6051f`。揭露：RED commit `8e12954` 本身不能建置（testmod 不能編譯，已 push，審查 M-6），具名 RED 在只補逾時回呼的 test-only 修正 `6c6051f` 上取得；`71d5b90..6c6051f` 沒有 `src/main` 變更，production 的行為與 `70ed3d9` 相同（`71d5b90` 改了 3 個 `src/main` 檔，只限 Javadoc、註解與可見性）；GameTest RED root 的 thresholds 在重新保持 B 之後才記。見 M7 note「最新：T4B CLOSED（2026-11-19）」節。）

- [x] coverage key 改為 world＋chunk；A 票傳 overworld。既有走廊與 A 票的計量不變：同 world 同 chunk 才去重，不同 SID 各記責任。（2026-11-19 T4B 勾選：`SlotAllocator.RootChunk(Identifier, long)`、`reserveRootCoverage` 改收 world＋chunk、A 票傳主世界，審查第 2 節以程式核對；JUnit `M7HomeCoverageTest` 4 案（含新案 `entryAndRootSameXzDifferentWorldCountSeparately`）在 GREEN 與 focused 兩輪都通過；變異「coverage key 拿掉 world」被該新案殺掉。）

- [ ] B 收尾票只在 v1.6 C 的兩種情況取；取前釋放同 SID 的 A 票。≤4 refs、200 ticks、非建立 FULL 查詢；與 A 票合計 64 份／256 refs、共用 4096。checked 移除、失敗進退避、租期到、列不再適用、reload、stopping 都冪等釋放；退避期間不持票。（2026-11-19 T4B 未勾：各子項都已實作，審查第 3 節逐項核對；取前釋放 A 票（變異殺手）、≤4 refs（斷言 `refs<=4`）、64 份與 4096（JUnit 與 budget GameTest）、checked 移除（變異殺手）、reload（具名 GameTest）都有證據。但沒有案例在持票中發生門、移除或交接失敗時核對釋放（審查 M-2，讀測試可證）；變異只實跑了兩處一起拿掉的合成版，「失敗進退避時釋票」與「租期到時釋票」各自單獨拿掉預期會存活，這兩個單點變異未實跑，是審查 M-2 與 implementer 的讀碼分析；SERVER_STOPPING 只以反射呼叫票 owner 的 `closeAll(server, true)` 驗證，不是真的停服；開服集合中已有 StopReason 的 SEALED(AUTHORITY_UNREADABLE) 仍先走 RESTART_VERIFY（審查 M-1）。M-1、M-2 交 T5 Step 0。）

- [ ] 取不到或 200 tick 未 FULL：WARN（同 SID 同原因一則）並沿 `HomeClosure` 的 per-SID 退避重試；達 12000 上限仍失敗再記一則 ERROR（每 instance 每 SID）。期間保留 CLOSE、busy 與 A 保護。（2026-11-19 T4B 未勾：`close_ticket_never_full_releases_and_backs_off` 斷言 WARN 恰一則、退避 200、推到 12000 的那次失敗記 ERROR 恰一則，`close_ticket_budget_exhausted_warns_and_retries` 斷言取不到時 WARN 恰一則、退避到期才重取；變異「逾時不記 WARN」被殺。但 CLOSE_CONVERGE 路徑在票 FULL 之後遇到決定性的非 VALID 會持票到期才退避，WARN 去重只比上一個鍵，兩種 WARN 交替各記一次，嚴格說不符「同 SID 同原因一則」（審查 M-3，交 T5 Step 0）；ERROR 記在第一次把退避推到 12000 的那次失敗，是 implementer 的讀法，審查 4(b) 判定可接受；期間保留 busy 沒有逐 tick 斷言，只在最後核解除 busy。）

- [ ] GREEN：上述 RED，加上 `close_ticket_never_full_releases_and_backs_off`、`close_ticket_budget_exhausted_warns_and_retries`、`close_ticket_released_on_reload_and_stop`，以及開服時曾 READY 的 AUTHORITY_UNREADABLE 列走 RESTART_VERIFY。T4 既有 39 列原生鏈（B 由測試票保持載入）行為不變。Windows 原生新增 1 列 `restart-close-unloaded-b`：READY 時 crash、重開時 B 無人，收尾完成、A 解除 busy、Crossed 不重播。這列先依 T5 Step 0 第 5 點定 SelfTest 餘量策略；超過 479 交使用者裁示。（2026-11-19 T4B 未勾：GameTest 6 案（上述 RED、本行三案、RESTART_VERIFY，加 plan 外新增的 `close_ticket_releases_the_root_ticket_first`）與 JUnit `M7HomeCoverageTest` 4 案在 `e3eea5a` 全過，Ubuntu 由 CI 44362426613 驗到；RESTART_VERIFY 以反射把 SID 加進開服集合驗證。原生新列加入後，完整回歸 47 列／94 phase COMPLETE，既有 39 列 close 矩陣也在其中。但本行要求的「先依 T5 Step 0 第 5 點定 SelfTest 餘量策略」在 T4B 沒有做，只做了 ≤479 的檢查（單份 384、CI 門態彙總 434）（審查 M-5）。之後使用者 2026-11-19 11:5x 選「上限改 1000 個＋時限 120 秒（建議）」，題目與選項原文見 M7 note「最新：T4B CLOSED（2026-11-19）」節的「SelfTest 負控上限的使用者裁示（2026-11-19 11:5x）」小節；實作與補負控排在 T5 Step 0 第一項，這個排序是 controller 的決定，不是使用者裁示。）

- [ ] 變異 production：coverage key 拿掉 world；退避期間持票；CLOSE 未 checked 就取票；不先釋放 A 票；逾時不記 WARN；移除後不釋票。各由具名測試 killed，逐位元還原。focused 跑新 GameTest、`M7HomeCoverageTest`、`ProjectionProtectionIndexTest`、M7 全集合與新原生列＋SelfTest。（2026-11-19 T4B 未勾：六項都由具名測試殺掉並逐位元還原（三檔 `sha256sum -c` OK），focused 兩輪在乾淨 pin `e3eea5a` 上全過。但「退避期間持票」與「CLOSE 未 checked 就取票」都是兩處合成的變異，原件只有合成版；「退避期間持票」有一種實現方式（只拿掉 `backOff` 的釋放）沒有任何案例在持票中遇到門、移除或交接失敗時驅動到（審查 M-2，讀測試可證）。單改一處會被另一個釋放點補上、這個單點變異預期會存活，都未實跑，是審查 M-2 與 implementer 的讀碼分析，比照 :469 的寫法。「不先釋放 A 票」的殺手是 plan 外新增的 `close_ticket_releases_the_root_ticket_first`；「CLOSE 未 checked 就取票」要靠 `e3eea5a` 新增的每 tick 結束時觀察才殺得到。）

- [ ] Controller 同步、逐檔 stage T4B 清單、候選 `feat: 回程收尾以有界票載入投影端`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。（2026-11-19 T4B 未勾：沒有用這個候選 commit，改成 Step 0、RED、RED 編譯修正、本體、testmod 共五筆分開 commit（`70ed3d9..e3eea5a`），逐檔 stage；本體 `0f04037` 的訊息是 `feat(m7): 回程收尾以有界票載入投影端（T4B，spec v1.6 C）`。完整回歸第一次 `run-all-902b2e39…` 在 M5 GameTest FAIL，原判保留；依使用者 2026-11-19 06:3x 裁示換新 nonce 重跑一次，`run-all-b90f443e…` 自然 `END ALL exit=0`，兩次的 `w10-preflight` 都 exit 0。獨立審查 C/I 清零；已 push；同 SHA CI 44362426613 十個 job 全部 success。）

### Task 5 追加項

- [ ] （v1.6 A）`startHome` 在任何 claim、ticket、reservation 或 ARMING 之前，B Controller 的 latch 為真就受控拒絕，與 A 的 `startClaimed` 同序。具名 `start_home_refuses_latched_entry`；變異拿掉守門，須被 killed。
- [ ] （v1.6 C）T5 Step 0 第 6 點（B1 假設 (c)）改為：以 T5 的正式 B 來源票重驗 COLLAPSING／ARMING 的開服返還。「重開時 B 無人無票」的收尾已由 T4B 的 B 收尾票處理，不再期待 pending。

B 門攔截屬 T6；T5 期間 B 門照一般門，T5 的 GameTest 不斷言攔截。

### Task 6 追加項

**修改追加**　`R/lang/{zh_tw,en_us}.json`（`message.quantumchamber.m7.chamber_busy`，T7 尚未加入時由 T6 先加）。本 Task 原「**修改**」行（:338）也以行末附加指向這裡。

- [ ] （v1.6 A）每次對 A 發起 HOME 跨越之前，在 A FULL 已核的同一步驟冪等設定 A 的 latch。具名 `home_arrival_requires_player_reclose_at_a`：A 門原本關著、到達者有剩餘藥效、A 供電；收尾後 A 比較器 3、不出發，親手開關一次才出發。原 GREEN（:359）的「HOME A-write hooks=0」不計 latch 欄，另斷言 A 的方塊、門、容器與 registry 都不變。變異拿掉 A latch，須被 killed。
- [ ] （v1.6 B）`OriginDoors.door` 在舊列找不到所屬 session 時，查 exact Entry 相符的 HOME 列，交純函式 `homeDoorAction`；擁有期間由 `HomePassage` 提供（HOME 準備含 A 預載、HOME_CHECK_WAIT、B PREPARE 膜，以及未到期的 READY；B 斷電等待不在期間內）。參與者 → `onParticipantClose`（Phase PREPARE／PASSAGE）；非參與者 → REFUSED 並顯示 `message.quantumchamber.m7.chamber_busy`；期間外 PASS；攔截不持同 SID guard；journal 不健康 PASS。
- [ ] （v1.6 B）膜規則與暫停，比照 M5：
  - 每 tick 的膜規則（門口有 entity、艙內有非參與者就改一般開門）在所有擁有子階段持續執行，含 A 預載與 HOME_CHECK_WAIT；退避本身不放開膜。
  - HOME 準備暫停（隔離、SEALED 寫入被拒待重試、READY 提交失敗後的退避）時不擁有門：B 門交還一般開門、攔截 PASS，暫停期間參與者開關門不算取消。
  - 只有 roll-forward 未確認時拒絕、門不變；mint 前的其他暫時條件拒絕、門不變，之後可再按。
  - 從 HOME 準備進入 B 斷電等待時，依 M5 交還一般開門；恢復供電後，原參與者走出再走進即續行。
- [ ] （v1.6 D）HOME 永久封存（ENTRY_AUTHORITY_INVALID、AUTHORITY_CORRUPT、TRANSFER_RECOVERY_REQUIRED）的產生點落地時，管理員 ERROR 的字句一併補上 v1.6 D 的解除路徑與「封存紀錄保留」。
- [ ] （v1.6 B）GREEN：
  - `home_b_door_participant_cancels_during_check_wait`、`home_b_door_participant_cancels_during_prepare_and_passage`；
  - `home_b_door_refuses_non_participants_while_owned`；
  - `home_b_door_passes_outside_owned_window`：含走廊期間、返還中、B 斷電等待、SEALED、CLOSE 收尾中；
  - `home_b_door_interception_runs_outside_sid_guard`：走真實 bulkhead `onUse`；
  - `home_b_check_wait_outsider_teleported_in_gets_open_door`：HOME_CHECK_WAIT 中把外人傳進 sealed 的 B，下一個 tick 門改一般開門，他按門仍被拒；
  - `home_b_prepare_pause_releases_door_and_passes`：隔離或 SEALED 寫入被拒時，B 門是一般開門、照一般門，參與者開關門不取消；roll-forward 未確認時拒絕；
  - `home_b_waiting_resumes_after_power_and_reentry`：B 斷電等待中恢復供電，原參與者走出再走進即續行。

  變異，每一項都要被 killed：HOME_CHECK_WAIT 判成期間外；B 斷電等待判成期間內；B 艙內非參與者改為開門；跳過 HOME 查找；參與者改以「人在艙內」判定；退避期間停掉每 tick 的膜規則；暫停時仍擁有門。

### Task 7 追加項

- [ ] （v1.6 A）`HomeClosure.converge` 每次改 B 門之前設定 B 的 latch（所有 CloseReason）；B 分支協調與 `startHome` 檢查 latch。`formal_projection_trip_uses_real_controls` 與 `root_stop_unlocks_only_after_checkpoint` 加斷言：收尾後 B 比較器 3，玩家實際開關一次才有新 SID（落實 :385）。變異：收尾不設 B latch；B 分支不看 latch。
- [ ] （v1.6 B、C、D）`formal-home-trip` 端到端涵蓋：真入口下 B 門的擁有期間、非參與者拒絕與提示；B 無人時收尾由 B 收尾票完成；A 永久封存（testmod 注入）停用斷電後可拆。

### Task 8 追加項

- [ ] （v1.6 F）人工清單加 H06、H07，取得代操作授權後與 H01～H05 同輪執行。
- [ ] whole-branch 審查加入下方焦點 6～10。

### 審查焦點追加

6. latch 必須在系統改門觸發的刷新之前設定；玩家親手關門與系統排入的關門交錯時，最終狀態仍可解釋。由 T4A `prepare_participant_close_requires_player_reclose_windows` 與變異「排入關門之後才上鎖」驗。
7. 永久封存解除只在全列已 checked 時放行，而且不改寫封存列。由 T4A `retryable_or_unchecked_seal_never_released` 與變異「忽略 rollForwardPending」「解除時改寫封存列」驗。
8. B 收尾票與 A 票同 XZ、不同 world 時不去重。由 T4B `entryAndRootSameXzDifferentWorldCountSeparately` 驗。
9. 解除並原地重建之後，舊封存列不得以原艙座標取得新艙的門權威，也不得替新艙代關門。由 T4A `rebuilt_chamber_door_ignores_released_seal_after_restart_reapply`、`rebuilt_chamber_prepare_participant_close_still_cancels_windows` 與座標核對的兩個變異驗。
10. B 擁有期間不得困人：每 tick 的膜規則在 HOME_CHECK_WAIT 也執行，PREPARE 暫停時交還一般開門。由 T6 `home_b_check_wait_outsider_teleported_in_gets_open_door`、`home_b_prepare_pause_releases_door_and_passes` 與對應變異驗。

### 驗收索引追加

| 規格 | 可驗條件與 owning task |
| --- | --- |
| V12 | T4A M6 兩類取消、斷電再供電與系統改門不解鎖、NBT；T6 HOME 到 A；T7 B 收尾與 B 啟動。 |
| V13 | T6 擁有期間、參與者取消、非參與者拒絕、每 tick 膜規則、暫停交還、B 斷電等待照一般門與續行；T7 真入口。 |
| V14 | T4B 有界票、world＋chunk 計量、重啟原生列。 |
| V15 | T4A 舊列四種與 HOME 三種永久封存解除、判定的純函式 JUnit、原地重建的座標權威；T7 端到端。 |
| H06–H07 | T8 同名兩列逐項採計。 |


