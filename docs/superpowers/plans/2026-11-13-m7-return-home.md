# M7 正規回程實作計畫

> 執行使用 `superpowers:subagent-driven-development`。每個新 task 派一位新的 implementer；該 task 的 fix round 由原 implementer 修訂，再交獨立 reviewer 複審。核取框由 controller 依實際證據勾選。

**目標（Goal）**　合法 COMPLETE 投影 B 的參與者走固定回家門，先回 B，再經 B 的膜以原座標回 A。旅程終局可安全解除同艙占用。
**架構（Architecture）**　將既有 checked journal 擴充為能同存舊列與 HOME 列的單一權威。沿用走廊、回收、票額、原生移動與 chunk checkpoint，分別核對 Entry=B 與 Root=A。每 SID 的終局收據保留歷史，活躍列負責完成 B 的最後門狀態。
**技術基線（Tech Stack）**　Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Loom 1.7.4、Java 21、Gradle 8.8、PowerShell 7。
**對應規格（Spec）**　[v1.1 驗證平台勘誤候選](../specs/2026-11-13-m7-return-home-design.md)，SHA-256 `8F6AA80876FAF27363FFB4AB576DDBC8BD74BF1CA7F8EA4E96A40404D54ECDA6`。玩法、R1–R4 與授權出處見 [M7 實作紀錄](../../implementation-notes/2026-11-13-m7-return-home.md)。

## 2026-11-13 有界驗證修訂（待一席獨立審查）

來源：[M7 note「使用者回覆 T1 checkpoint」](../../implementation-notes/2026-11-13-m7-return-home.md)逐字四裁示，原文在SDD/user-t1-scope-ruling-20261113.md。只改驗證範圍，不改玩法、production契約或歷史原判。

| 範圍 | 現行決定 |
| --- | --- |
| Windows native | T1 PoC三門態×六cut=18列/36phase；T4正式owner三門態×十三cut=39列/78phase。T1六cut為before-close、after-door、after-checkpoint、after-temp-force、after-publish、after-remove。 |
| Ubuntu | 真volume硬連結/大小上限等JUnit＋正式拒絕GameTest，exact零skip；不寫Linuxowner、不跑native/main-only，Linux crash/reopen只由Windows推論。 |
| PoC生命週期 | T1原件留存，T2/T3 default full/CI仍守門。T4正式Windows39本機及同SHA CI逐列通過後，保存原件/SHA/digest退休證據，以純驗證接線提交從default full/CI移除PoC；退休接線CI成功才進T5。T5–T8不重跑PoC，歷史原件/FAIL不刪，保具名歷史唯讀入口。 |
| 本輪授權 | docs-only plan/必要spec平台勘誤→原唯一reviewer一席plan C/I0＋逐I歸屬→原implementer一輪核心整改→一個事前凍結batch執行一次→無論結果停回報。不是T1 closed/正式18或39/下一task授權。 |

### I1–I12原判與範圍歸屬（須原reviewer確認）

原task-1-core-review.md C0/I12/M2保持不變。I1–I5、I7、I9、I11、I12保留；I6只移除Linuxowner部分，Windows exact owner/timeout保留；I8只移除Ubuntu native/雙OS26-shard部分，Windows artifact/收據/disk/selftest/digest嚴驗保留；I10只移除Ubuntu main-only，Windows三層/上傳/兩JAR角色保留。此為proposed disposition，不自行整項關閉。原reviewer另出逐I確認表；plan review清零只採新契約，不代表12I實作已修完。

### 一輪整改／一次定向batch硬邊界

- plan/spec一席review C/I清零前，不改inventory/CI/oracle/harness/test source。之後只原implementer修一輪T1測試/腳本/build/CI，production完全不改；原12I保留部分照修、移除部分不另寫Linux程式。M1/M2可同輪移除重複未用匯出分支/EOF，不另開fix round。
- 實作完成先本機候選commit並凍結`t1-fix1-batch-plan.json/md`：完整SHA、dirty/raw source/compiled/actual argfiles的完整成員/hash、唯一nonce/root、按序精確命令、預定退出/事前具名negative及錯因。controller核清單後只執行一次，沒有自動重試、不邊跑邊改消費檔；compile僅由該batch一次Gradle建置依賴完成，不能先試編譯偷跑第二批。
- batch上限：兩spike JUnit一次、Windows M7 GameTest一次、Windows ordinary/after-checkpoint單pair最多一組（crash77/reopen0），以及同批離線strict/raw/owner/artifact/manifest/consumer/summary/workflow各控制一次。不是18/39全集。expected negatives須執行前具名固定錯因，不得事後把失敗改expected。
- 任一非預期工具FAIL/不符預定終局，立即停止後續命令；保原root/exit/partial，安全結束已在途exact owned JVM（身份未知不廣殺），本輪不得fix/重試/續跑。原EVIDENCE-INTEGRITY3、Native2/main-only2等計數/FAIL/UNKNOWN不重置；expected negative精確正拒不算工具FAIL。
- batch自然PASS後也停止，不再fix/compile/runtime/CI/push；原唯一reviewer只讀核分類/差異/既有原件，新缺口open待下次裁示，不求所有實證清零而擴權。本輪禁止正式18/39/full/T2/新CI/push/project clean/W10 preflight或重建/退役；可必要本機待驗candidate/docs commit。Ubuntu本機無真環境且須push才有CI時標未驗，不偽造Linux。
- 停止交付：plan/spec與採用review、逐I原判/範圍移除/保留/修正/未驗表、改檔/exact集合、唯一batch實際命令/root/SHA/exit、新Windows-only＋PoC退休時程模型及UNKNOWN/未驗能力。後續main/tag/人工/PR/退役仍另問。

## 共通限制與證據

- 在 `.worktrees/m7-return-home` 的 `codex/m7-return-home` 執行。git 每條加 `-c safe.directory="C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home" -C "C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m7-return-home"`。不要在 main 實作。
- task 啟動讀 AGENTS 現行入口、上述 spec／note、本 task、前一筆 ledger。臨時原件與 `progress.md` 放 `.superpowers/sdd/2026-11-13-m7-return-home/`。長期 gate 放 tracked `scripts/verification/`。舊 FAIL、root、receipt 保留。失敗記實際原因、暫停相依工作；修正後新 nonce，M6 重跑額度與 waiver 不自動沿用。
- 每次 commit、push、gate 終局由 controller 同步 note 與 AGENTS 入口。記 task、base/code pin、實跑命令／root／hash、退出碼、review range、遠端 SHA／CI attempt 與未決項。未發生事件寫「待驗」。同 SHA CI 終局先寫 ledger／工作區，下次文件提交收錄，勿為寫回該次 CI 觸發無限 CI。
- 每個 production task 都有具名 RED、GREEN、直接變異 production owner、還原原 bytes、focused、完整 run-all、獨立規格與品質審查、同 SHA CI。修正輪重新執行適用門檻。純測試／腳本／文件一席獨立 review、定向驗證與 CI，免 production mutation 與本機 full。改 build 的 T1 只接驗證入口，仍另實際驗 clean build、測試隔離及必要 JVM。
- 逐檔 stage 該 task 表列的變更與兩份 controller 狀態文件，先核 staged diff／清單。禁止 `git add -A`、覆寫既有 dirty、force push、force-add ignored 證據。先凍結本機候選 commit 再用其乾淨 code pin 跑 production full；若審查改碼，重新凍結／驗證。純進度文件另列 docs pin，遠端採最終推送的同 SHA CI。
- 一個 task 的 C/I 全清且 required CI 各 job 真 success 才 closed。本輪只在下列有界修訂一席 C/I 清零後進一次核心整改／定向，不恢復正式矩陣或下一 task。正常 push 舊授權本輪暫不使用；人工代操作（方式 Claude）、main、tag、工作樹退役、PR、LICENSE 各自另問，無需為執行計畫追加整份核可。
- Root 僅 canonical `minecraft:overworld` 原 ORIGIN；Entry 僅 exact live catalog OVERWORLD COMPLETE PROJECTION。HOME door = `new DoorKey(sid, 1L, DoorKey.DoorWallSide.POSITIVE_LATERAL)`。SOURCE 與 legacy return-only 維持原意；FamilyCapabilityVersion=0，Nether／End 共用原版，M8 不排入。
- 啟動與選門各驗 A 的實際供電一次。選後 LOW 不取消，停用／拆除／結構失效停止旅行、保留選擇、checked 收尾才解除 A 保護及鎖。A 絕不重建、改門或清容器。新旅程須重新關 B 門，不必出艙，可沿用未到期藥效。7×7×7 與一格外殼不變。
- 相依順序 T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8。T1–T6 的定向入口限既有 testmod，可呼叫 production owner，release 的 B activation guard 到 T7 才放行。不得用 production 開關、偽造 catalog 或跳過真 checkpoint 替代這個順序。

### 常用命令與全程資料安全

- 現有定向 JUnit 入口：`pwsh -NoProfile -File scripts/verification/gates.ps1 -Mode M6JUnit -TestClass <類別全名> -Label <唯一英數連字標籤>`。每類各跑一次，核 XML 完整、零失敗／略過；舊 mode 名稱不代表 M7 集合已驗。
- 現有完整入口：`pwsh -NoProfile -File scripts/verification/run-all.ps1`。自然印出 `END ALL exit=0 HEAD=<本機候選>`、outer exit=0，逐一讀本輪 summary 原件。子 gate 的成功或 `-PreflightOnly` 不抵 full。
- **首次碰 W10 前**依 [驗證 README 的 W10 段](../../../scripts/verification/README.md) 重建指定 fresh detached producer `.worktrees/m6-w10-m5-producer`。核 tag object、peeled `a2d2b404329d2574469fca064a663e21fe89f333`、原 blob、patch、runner 與 diff 雜湊、唯一允許 dirty；已有資料先驗原件，不覆寫或重套。Windows console `chcp 65001`，讀回 65001。
- W10 入口含完整／preflight 的 run-all、m5-collapse-probe 未限 `-Only`、含 `w10` 或 `-PreflightOnly`，以及 m6-harness-safety-check 的 `ci-cwd-preflight`。先驗 canonical 絕對路徑、所有祖先非 reparse、沒有 live Java owner、session.lock 可獨占。世界只可唯一可恢復 move、逐檔 hash 還原。不能祕密清除舊 FAIL 世界來取得 fresh。
- T1 將建立下列入口，此前不存在。M7 GameTest 用 `gates.ps1 -Mode M7GameTest -Label <唯一標籤>`；`m7-ci-gametest.ps1 -Platform Windows|Ubuntu` 驗該平台 JUnit、GameTest XML、log、scope 與兩 JAR。M7 native 僅 Windows，用 `m7-home-probe.ps1 -AllEnabled`，唯讀重驗 `m7-home-probe.ps1 -VerifySummary <summary.json> -ExpectedHead <完整SHA> -SelfTest`；具名鏈用 `-Only <chain>`。均以 `pwsh -NoProfile -File scripts/verification/<腳本>` 呼叫。
- T1 在 `S/m7-inventory.json` 定義 Windows 與 Ubuntu 各自的 exact inventory，由 `portable` 加該 OS 專屬集合組成，runner、CI、summary 與負控驗實際 OS／集合。Windows 承擔需要 PlayerCheckpointStore 的正式成功入場、恢復、跨越與 playerdata 三窗口；Ubuntu 執行正式入口的非 Windows 原生玩家 checkpoint 拒絕，要求拒絕時幾何、玩家移動、active journal、鎖／票增量全為零。Ubuntu 不啟用 Windows 成功案例、兩平台皆不允許 skip 或用 testmod 繞過拒絕。tracker、真volume硬連結與可攜JUnit／GameTest兩平台實跑；B native checkpoint／獨立關門跨JVM及main-only只Windows。Ubuntu不寫Linuxowner、不跑native/main-only，Linux crash/reopen只由Windows推論，列已知限制。T1 先驗既有 A 平台界線，T5 與 T7 在各自正式 B owner／普通玩家入口重驗。下文「全集合／全部鏈」一律指該平台 manifest 的精確全集。
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
4. B 原本普通關門、膜為 NONE，或 receipt 已發布但 active 移除失敗。T1/T4 的 `close-ordinary-{cut}` 及 `receipt-existing` 鏈驗真實改門、重做 checkpoint、不覆寫與不早解鎖。
5. 傳送、Crossed 與 playerdata 各在不同提交點。T6 的 `move-before-crossed`、`crossed-before-player-save`、`move-and-save` 各用獨立 JVM 核真實登入位置與零重播。

### Task 1: 驗證入口與三項可行性實證

**交付與依賴**　只需已審計畫與 M6 基線。本 task 用實際 1.21 NBT、兩平台檔案系統、真 B 門／原生 checkpoint 建立可重現實證。所有 build／CI 修改只為驗證接線，屬使用者指示的純測試／腳本豁免，仍須真兩平台定向實證與同 SHA CI。

**新增**　`J/persistence/{M7BudgetSpikeTest,M7PublishSpikeTest}.java`、`G/{M7HomeSpikeGameTests,M7HomeProbe,M7CloseSpikePhases}.java`、`S/{m7-ci-gametest,m7-ci-main-only,m7-home-probe}.ps1`、`S/m7-launch-export.init.gradle`、`S/m7-inventory.json`。薄helper只保Windows必要路徑，移除重複匯出分支與新Linuxowner/main-only程式。

**修改**　`build.gradle`、`src/testmod/resources/fabric.mod.json`、`G/mixin/{M4LegacyGameTestFilterMixin,StaticDimensionsTestServerMixin}.java`、`G/M4GameTestBoundaryProbe.java`、`S/{gates,run-all,ci-rule-check}.ps1`、`.github/workflows/build.yml`、`S/README.md`。

**消費**　`ChunkSaveCheckpoint.ready(ServerWorld,Set<ChunkPos>)`／`save(MinecraftServer,Map<ServerWorld,Set<ChunkPos>>)`、既有 probe-common.ps1 的 Windows exact owner helper、NBT hooks 與 M6 scanner／artifact 檢查。沿用已安裝版本，查實 Minecraft JAR 的 tracker API。

**產出**　上方三個 M7 命令、`runGameTestM7`、`runM7HomeProbe`、scope `m7_home`、flag `quantumchamber.test.m7Home`、`build/gametest-m7-results.xml`，及固定 chain/phase、JUnit class.method、GameTest template＋class.method 清單。probe 每 phase 指定本 task 定義的 `-Pm7ProbeNonce`、`-Pm7Chain`、`-Pm7Phase`，共用該鏈 fresh save，獨立 JVM。


- [ ] 先寫候選側 RED driver，以凍結舊 workflow／fixture 確認舊版沒有 M7 scope；`M7_MISSING_SCOPE` 由新 driver 報出。舊 verifier 找不到 step 的原錯照錄。接線後另在本機用實際候選 step 文本驗 drop／skip／wrong-scope 負例。

- [ ] 製作具名 `legalHomeAtAndBeyondTrackerLimit`、`legacyAppendCannotSpendUnselectedRemapAndCloseReserve`。用 spec §5 exact schema 的 25 個合法 effect snapshots、合法候選舊列與 64 個幾何合法的 slot row；位置、UUID、Bounds、時間／狀態相容，按真實 tracker 記 wrapper／list／row 的計量。

- [ ] 實測並列出 tracker 用量與 growth 算式，確認同一 bytes 的 probe/raw/decode、512 層界線、重鍵可定位；16,777,216 以內可讀，下一個超限合法大小遭拒。實際合法編碼有粒度時記最近可達兩側與底層 tracker 精確邊界。加入切換 path、重鍵於 wrapper/data/內層與高度可壓縮檔案。

- [ ] 分別用合法狀態驗未選舊＋新 lease 聯集到 64、選後固定 lease 再清空、25 Crossed、單一不在 Crossed 的 recovery UUID、四種 root stop、最長合法 CLOSE evidence、獨立 receipt wrapper。不能把 25 Crossed 與 TRR 強組成不存在的狀態。最壞餘額須涵蓋所有可達後繼；競爭舊 append 不能使已入艙者失去完成預算。

- [ ] `publishesCompleteBytesWithoutClobber` 在真 Windows 與 Ubuntu volume 以同目錄 force 完整 temp＋`Files.createLink(target,temp)`；兩競爭者、預存 exact／異值／破損、未知後讀回、缺硬連結能力，分別驗 target 永遠單一完整 bytes、舊 hash／檔案身分不變，不退到 replace。相同能力需在實際 save volume 的新 SID admission 可驗。

- [ ] native及main-only runner僅Windows。以啟動時原process handle綁實體，核PID/start、exact nonce/root/java executable/命令列後才定向收回；PID回收或身分未知保原件報錯、不殺其他程序。各驗owned timeout/stale PID/foreign同名程序與鎖釋放。移除新Linux `/proc`/pidfd/Python owner；Ubuntu要求native/main-only於啟動前受控拒絕，不記skip/PASS。
- [ ] native manifest只Windows，stage/door/cut/implemented/phases與Java宣告/原件exact。T1 `spike-close`每門態六cut：`before-close,after-door,after-checkpoint,after-temp-force,after-publish,after-remove`，三門態共18列；T4 `close`每門態十三cut：`before-close,after-close,before-door,after-door,before-checkpoint,after-checkpoint,after-temp-force,before-publish,after-publish,after-readback,after-recheck,before-remove,after-remove`，共39列。每列唯一nonce/root/save、crash77→新JVM reopen0；不一root跑多cut。T1集合外cut不是skip，T4正式39不縮減。
- [ ] 後續另獲正式執行授權才量Windows一組完整crash/reopen（含JVM/readback/取證/鎖釋放/負例），按本機及CI實測選Windows shards，不沿用13/OS或26-shard常數。每shard不超舊90分鐘job，原M6 70/15分鐘step/job完整保留。同SHA匯總Windows exact stage/列/phase/exit、artifact本體digest及跨shard unique root/nonce/JVM identity；缺列/重複/錯OS/錯綁具名拒絕。Ubuntu只JUnit/GT，不建空native job或假PASS；不足只報阻點、不加舊timeout。

- [ ] T1 交易用 test-only fixture 明標 proof-of-concept；Windows直接準備合法 B 區塊與 close 前置資料，T4 則由合法已選、全 Returned 的磁碟 fixture 交正式 close owner。兩者不需先走 Windows 玩家 checkpoint，不將 fixture 當正式入場證據。門與 chunk checkpoint 用正式 API 並驗重開 region 的實際門格、門口 entity 防夾、B 門全 chunk；注入一次 checkpoint 拒絕，須無終局成功。保留磁碟與 trace。T4 另外以 production close owner 重跑同切點。

- [ ] 將 Gradle run／testmod registrations／filter／舊 observer 及 preset 隔離接好，M7 正常 pacing 及 actual mod list 具證。兩平台 CI 各依 `portable+windows`／`portable+ubuntu` 的 exact JUnit／GameTest清單（native/main-only僅Windows）與唯讀反驗；Gradle run/filter、source 掃描、XML oracle、chain manifest 都以相同平台分派，將 Windows 成功案例放入 Ubuntu 清單／移除 Ubuntu 正式拒絕案例須遭對應負例拒絕。所有該平台必要案例零略過；T1正式完成前另驗不丟證據的clean build、兩平台release/sources（含nested JAR）與Windows main-only。本輪禁止project clean，真正clean後存續可列未驗。

- [ ] 每個 probe receipt 核 code SHA、nonce、chain、phase、schema、canonical root、PID＋start、獨立 JVM、預期 halt 或正常結束、磁碟原件 hash、鎖釋放。summary 反例至少有刪 phase、重 PID／根錯配、調 hash、調 HEAD、假 PASS／略過；全部必須以對應錯因拒絕。run-all包含執行/readonly/negative及最終原件重驗；Windows上傳native/main-only全部原證，Ubuntu只上傳JUnit/GT/JAR原證。

- [ ] 定向跑兩個 spike JUnit、M7 GameTest、全部已啟用 spike 鏈與 SelfTest。push 前先取得本機 runtime 與 workflow fixture 正負例，push 後才讀回遠端同 SHA 正例及每平台反例；三項有任何未證即阻擋 T2 及 guard，記具體阻點。此 task 豁免 production mutation／本機 full。

- [ ] Controller 同步 note／入口，逐檔 stage T1 清單，提交 `test: 建立 M7 驗證入口與持久化先導實證`；一席獨立審查原件／C/I 清零，正常 push、核同 SHA 雙平台與完整必要 job 才 closed。

### Task 2: schema6 共同 journal 與不可變單 SID 歷史


**新增**　`P/persistence/{JournalSession,HomeSessionRecord,HomeJournalCodec,HomeJournalBudget,HomeReceiptStore}.java`、`J/persistence/{M7HomeJournalTest,M7HomeHistoryTest}.java`。

**修改**　`P/persistence/{SessionRecoveryRecord,SessionRecoveryState,SessionJournalStore,JournalNbt}.java`、`S/m7-inventory.json`、`README.md`。依賴 T1 的已驗 tracker／同 volume 發布與 exact 清單。

**消費**　`SessionRecoveryRecord.Participant`、`SessionRecoveryRecord.SpaceLease`、`ChamberOriginAuthority`、`UniverseId`、`SessionRecoveryState.CommitResult/TerminalResult`。

**產出**　`sealed interface JournalSession` 共同提供 `UUID sessionUuid()/chamberUuid()`、`List<Participant> participants()`、`List<SpaceLease> spaceLeases()`、`SessionState state()`。舊 record 與新 `HomeSessionRecord` 實作；新列提供 `ChamberOriginAuthority rootAnchor()`、`HomeSessionRecord.EntryEndpoint entryEndpoint()`、`HomeSessionRecord.Home home()`。

**欄位型別**　`EntryEndpoint(UUID chamberUuid,UniverseId universeId,Identifier worldKey,DimensionRole role,BlockPos controllerPos,Direction facing,ChamberInstanceKind instanceKind)`；`Home` 中 Selection、CloseIntent 為各自有型別的分支，`HomeSessionRecord.CloseIntent` 的 NONE／CLOSE 與 Evidence 共用 §9.2 的驗證矩陣。

**跨 task API**　`SessionRecoveryState.allSessions()/allFlushedSessions(): Map<UUID,JournalSession>`、`admitHome(MinecraftServer,HomeSessionRecord): CommitResult`、`commitHomeRestore/commitHomeRollForward(MinecraftServer,HomeSessionRecord expected,HomeSessionRecord next): CommitResult`。歷史 `HomeReceiptStore.lookup(UUID): Optional<HomeSessionRecord>` 回傳經嚴格驗證、以外層 CLOSE 重建的凍結列；`publishOrVerify(HomeSessionRecord): void` 與 `requireAdmissible(UUID): void` 錯誤不可誤當 absent。


- [ ] RED：`mixedRowsAndEmptyJournalKeepSix`、`legacyAppendCannotSpendUnselectedRemapAndCloseReserve`、`duplicateRawKeysAndHistoryMismatchFailClosed`；現行版拒合法 schema6／沒有 HOME 寫入路徑，先保存具名失敗。

- [ ] schema1–5 留舊 exact 流程；schema6 envelope 僅 SchemaVersion/Records，無 RecordVersion 必恰舊12欄且有 context，有則必 int 2＋精確 §5.2 新列。新列 travel=1、semantics=LATERAL_BUFF_MAINTAINED、restore byte=0；root/entry 同 chamber、位置／facing 相同。讀寫全欄 types、enum、time 溢位、UUID、空 list held type、有限值／±0、1..25 人及快照不可變。不得混裝 legacy return-only；schema3 升版舊 Collapse 空；已升6即使清空仍為6。

- [ ] 將 current／flushed、quarantine、pending、未知寫入收斂與 ownership 以同一資料源管理；舊型別 accessor 可提供相容檢視，全體讀者依 task 接線：本 task 接資料層玩家占用、reservation/collision、write/save/init；T3 接全域 busy；T4 接 startup fence；T5 接 startup space/recovery；T6 接 collapse。全數接妥前封住一般 activation。首次 checked ARMING 僅在既有 M5 instance authority 初始化且無未確認寫入。schema6 pending／readback 驗完整 wrapper、active 及 CLOSE。

- [ ] 用 T1 實測公式計算 `HomeJournalBudget.requireReserved(NbtCompound wrapped): void`，以正式編碼及相同 tracker 核每個 writer 變更前的可容納性、成功後的 exact readback。舊 append、put、remap、upgrade、共享 writer 不得侵占；preselected phase 保 64、selected 按凍結集合，獨立最壞 receipt 也核。越界維持原 journal／mapping，不能因拒增長取消原隊伍。checked ARMING 前同時核 SID 的合法單檔上界與發布能力，拒絕不得改動既有 journal 或租約。

- [ ] DONE 條件採 spec §9.1 單步狀態／進度矩陣與 ownership，§9.2 各原因的 state／Evidence／時序／DoorGoal 成對嚴驗；old sameAuthority／occupies 原義。恢復或未知的邊用 spec 指定 CAS，禁止跨 state 夾改核心身分、合併變更或 CLOSE 後續移動。

- [ ] 使用 T1 發布機制，save canonical/非 reparse，按 lowercase SID 單檔精查；`DataVersion,data{SchemaVersion=1,SessionUuid,CloseIntent,Session}`，Session 僅內 CLOSE 正規化 NONE。active/receipt 同 SID 僅容許 exact 凍結 CLOSE。既存錯配／損壞保持原件；無能力即拒新 HOME，不掃目錄，失敗 temp 留證。

- [ ] GREEN：所有舊 schema fixtures 及 25 人／64 slot 值、硬連結競爭、預算邊界、parse path 切換皆實跑；以基線 `2255027` 的真正舊 reader 或隔離 classpath 讀固定 schema6 bytes，須拒絕。README 說明首次 HOME 升6 後舊 build 不可降版；備份資料不能被測試改寫。

- [ ] 變異 production：raw walker 漏 Home 內層、把 tracker 換 unlimited、未選只保現有 lease、選後漏 CLOSE、舊 append 跳預算、replace 既存 SID、空 journal 降版，各由上述正式 reader/writer 測試 killed 並逐檔還原。focused 跑兩個新 JUnit、SessionJournalSchemaFiveTest／SessionRecoverySchema3Test 與 M7 全集合。

- [ ] Controller 同步並逐檔 stage T2 清單，本機候選 `feat: 支援有界 schema6 與不可變回程收據`。先依 README 重建／核 W10，再跑乾淨 pin 的完整 run-all；獨立規格／品質 C/I 清零、記 docs truth、正常 push 並核同 SHA CI 才 closed。

### Task 3: 核對 B／A、占用與有界 A 票


**新增**　`P/collapse/{HomeEndpointAuthority,HomeRootTickets}.java`、`J/corridor/M7HomeCoverageTest.java`、`G/M7HomeAuthorityGameTests.java`。

**修改**　`P/chamber/{ChamberCollapsePort,ChamberSessionGateway,ChamberSessions}.java`、`P/collapse/{CollapseAuthorities,ProjectionProtectionIndex,PassageTransferPolicy}.java`、`P/corridor/{CorridorPageManager,SlotAllocator}.java`、`P/superposition/SuperpositionSessionManager.java`、`src/testmod/resources/fabric.mod.json`、`S/m7-inventory.json`。

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


**新增**　`P/collapse/{HomeRootFence,HomeClosure}.java`、`J/persistence/M7CloseMatrixTest.java`、`G/{M7HomeClosureGameTests,M7HomeClosurePhases}.java`、`G/mixin/M7HomeCloseWindowMixin.java`。

**修改**　`P/QuantumSuperpositionMod.java`、`P/chamber/{ChamberLifecycleService,ChamberRegistry,ChamberRegistryState,ChamberProtectionService,ChamberMaintenanceInteraction,ChamberCollapsePort}.java`、`P/collapse/{OriginDoors,HomeEndpointAuthority}.java`、`P/persistence/SessionRecoveryState.java`、`P/superposition/SuperpositionSessionManager.java`、`G/M7HomeProbe.java`、`src/testmod/resources/{fabric.mod.json,quantumchamber-test.mixins.json}`、`S/{m7-inventory.json,m7-home-probe.ps1}`。

**消費**　T2 的 `commitHomeRollForward/commitHomeRestore`、`HomeReceiptStore`、T3 exact Entry／global busy／票與既有 `ChamberDoorService`、`ChunkSaveCheckpoint`。

**產出**　`HomeRootFence.bootstrap(MinecraftServer): void`、`onRootEvent(MinecraftServer,ChamberOriginAuthority,RootStatus,long): boolean`（true 限 stop 已確認或沒有需 latch 的旅程）、`permitsEnable(MinecraftServer,UUID): boolean`。透過 chamber port 安裝，不直接反向依賴 collapse。

**交接**　`HomeClosure.step(MinecraftServer,HomeSessionRecord): void` 收斂已凍結 CLOSE，或由當下已核全跨／expiry／持久 root latch 決定原因。正式互動入口 `HomeClosure.onParticipantClose(MinecraftServer,HomeSessionRecord,ServerPlayerEntity actor,HomeClosure.DoorPhase phase,long observedAt): ChamberCollapsePort.DoorUse`，phase=PREPARE／PASSAGE；`HomeClosure.onEntryStopped(MinecraftServer,HomeSessionRecord,ServerWorld entry,HomeClosure.EntrySignal signal,long observedAt): void`，signal=LOW／DISABLED／INVALID。在事件 guard 內核 actor、cohort、phase、exact B、當時計時與真實 signal 再 mint Evidence；無事件的 step 不補造 actor 或時間。RESTART_CLOSED 只由 startup owner 持當次 instance UUID 與 startup READY 集合送出，不能由一般 tick 假造。journal 新 `finishHomeClose(MinecraftServer,HomeSessionRecord,HomeClosureProof): TerminalResult` 的 proof 為本 instance／該 SID／該列／B門及 checkpoint／exact receipt 的一次性權威，由 close owner 驗證，不讓外部 caller 自宣已完成。proof 型別放 `HomeClosure` 的巢狀 `HomeClosureProof`。


- [ ] RED：`ordinary_closed_door_needs_native_checkpoint` 在舊膜 cleanup 遇 NONE 會留下普通關門；`restart-enable-before-entry` 的首個 maintenance call 不應跳過尚未落盤的 stop；`permanent_seal_cannot_be_archived` 拒偽造 evidence。

- [ ] 開服 strict journal＋registry／ownership 完成後、任何 maintenance enable／entry 操作前掃 active selected（含無 runtime 的 DORMANT），不掃 receipt。registry confirmed disabled/destroyed/exact 撤銷 → per-root fence，逐 SID first-wins event。已確定根停用再重啟先 enable 仍 pending；UNKNOWN 不 mint ROOT。初始化失敗維持阻擋。

- [ ] `setEnabled`、`setOriginEnabled`、`completeRemoval`／真正移除的持久提交點（含原生 setter）與結構觀測都通知同服務。註冊順序保 journal／M5 ready；普通 LOW 不發通知。registry 已落、stop 未落不能回報成功／允許復啟；同 tick disable→enable 仍先 checked latch。選後全階段可同 state latch，包含無 B runtime／部分返還。A 保護自 B provisional 起到 checked 終局；正常拆 A 拒，但管理員停用仍可。

- [ ] 新事件優先序：未確定 move／永久 TRR → pending journal 收斂 → 既有 CLOSE → root latch／queue → 普通終局。TRR、ENTRY_AUTHORITY_INVALID、AUTHORITY_CORRUPT 不可歸檔；未返完／有 lease 時停止旅行、保留 pending 給 T5 的返 B／清租約，不提前 CLOSE。普通缺載 B 保持 pending，不改 A。

- [ ] 唯一 owner 依 §9.2 矩陣 mint 原因與 evidence：ALL_CROSSED、EXPIRED、PARTICIPANT_CLOSED（PREPARE/PASSAGE）、ENTRY_STOPPED（LOW/DISABLED/INVALID）、RESTART_CLOSED、四個 ROOT_*。要求 closed≥各原時間、actor 是本人、expiry 真到、來源 phase／observed exact。close latch 用 roll-forward，未確認不改門；checked 後列凍結。具名 `participantCloseKeepsActualActorAndPhase` 同一 READY 列分別由兩位參與者於獨立 fixture 操作，Evidence 各核原 actor／時間；無互動或錯 phase 不得由 step mint。加入非 startup 呼叫無法生成 RESTART_CLOSED。

- [ ] 對 exact B，普通／sealed／passable 均實際套 DoorGoal，逐格驗門、所有 B 相關 chunks 原生 checkpoint，`publishOrVerify`、再驗 B 門＋checkpoint、checked remove（restore CAS）後放鎖。PARTICIPANT_CLOSED 防夾 CLOSE_IF_CLEAR_ELSE_OPEN，其餘 OPEN，不能以 ownerless membrane queue 代替。遇未知或錯配保原件與 busy；移除後不得修舊門。

- [ ] GREEN：正式 owner 的 `close-{ordinary|sealed|passable}-{cut}` 在Windows逐一以正式39列／兩phase（T1只有18代表列，不抵正式集合） 驗 §5.3；另跑 `receipt-existing`，另測 checkpoint unknown／跨檔不符／已存 target 異值／成功後玩家主動關門。逐 phase 核兩側門格、region、識別、active與歷史 hash、資源、零移動。經正式發布與刪活列完成 >4096 個互異 SID，仍可新 admission，舊單檔 exact 可讀，禁止全目錄掃描或虛增獨立歷史計數。

- [ ] 正式Windows39本機及同SHA CI全部列/phase/負例/artifact原件核齊後，記退休證據/code pin，以純測試/腳本提交把PoC18從default run-all/CI/required集合移除；不刪歷史原件或改FAIL。具名`poc_retired_only_after_formal_close_39_verified`驗缺列/失敗/錯SHA不可退休，default exact改close39＋當期正式新增鏈；退休接線CI成功才進T5。退役前T4 PoC輪次據實計成本，T5–T8 PoC=0。

- [ ] `restart-enable-before-entry` 真跨 JVM 留 registry disabled 已存、stop 尚未落盤，startup 先 direct enable 再操作 B；另驗 DORMANT、同 tick 復啟、部分 Crossed、stop 已落後 A 修復均不復活，普通 LOW 無 latch。對已有 READY 無已確認 root 的 startup 僅 RESTART_CLOSED，不等待 A FULL。

- [ ] 變異掃描僅有 runtime、略過 direct setter、close 前提省略 checkpoint、先刪 active 再發 receipt、漏核 existing exact、TRR 允許 ROOT close。focused 跑 M7CloseMatrixTest／M7 GameTest／上述全鏈與 SelfTest，確認每個 production mutation killed、還原。

- [ ] Controller 同步、逐檔 stage T4 清單、候選 `feat: 持久完成回程終局並重建停用防線`。核 W10、完整 run-all、獨立規格／品質 C/I 清零、push 並核同 SHA CI 才 closed。

### Task 5: 從 B 入走廊、唯一 HOME 選擇與保留式返 B


**新增**　`P/collapse/HomeDoorInteraction.java`、`P/corridor/HomeDoorLayout.java`、`J/corridor/M7HomeDoorLayoutTest.java`、`G/{M7HomeCorridorGameTests,M7HomeCorridorPhases}.java`、`R/models/block/quantum_bulkhead_home.json`。

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


**新增**　`P/collapse/HomePassage.java`、`P/universe/{CanonicalHomeTarget,CatalogUniverseTarget,HomeTransferResult}.java`、`J/universe/M7HomeTransferTest.java`、`G/{M7HomePassageGameTests,M7HomeTransferPhases}.java`、`G/mixin/M7HomeTransferWindowMixin.java`。

**修改**　`P/collapse/{CollapseService,EntryWatch,PassageService,OriginDoors,PassageTransferPolicy,HomeClosure}.java`、`P/universe/UniverseTransferService.java`、`P/persistence/SessionRecoveryManager.java`、`G/M7HomeProbe.java`、`src/testmod/resources/{fabric.mod.json,quantumchamber-test.mixins.json}`、`S/{m7-inventory.json,m7-home-probe.ps1}`。

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


**新增**　`G/{M7HomeEntryGameTests,M7HomeEntryPhases}.java`、`J/chamber/M7HomeFeedbackTest.java`、`docs/manual-acceptance/2026-11-13-m7-manual-smoke.md`（先建未執行清單）。

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


**修改**　`docs/manual-acceptance/2026-11-13-m7-manual-smoke.md`、`docs/implementation-notes/2026-11-13-m7-return-home.md`、`AGENTS.md`、本計畫。依賴 T1–T7 全數 closed，採實際 release JAR、HEAD 與證據，沒有新 production API。
**消費與產出**　讀上方 gate 原件、V01–V11 索引及 H01–H05；產出逐項結果、已知 crash 界線、未驗事項、基準範圍與待核可整合狀態。


- [ ] 先驗具名負控：把複本中的 M7 一例改 skipped、移除 chain／篡改 HEAD、將 GameTest receipt 充當 main-only，各應拒絕；確認 PENDING、失敗或漏 H 列的交付檢查不能記里程碑已完成。只動 fixture，保留原件。

- [ ] 核 final code pin、重新執行 milestone 完整 run-all／Windows當期全M7 native/negative oracle、兩平台JUnit/GT精確集合／release+sources／Windows獨立main-only（mod list、runtime classpath/argfiles/DLI、exact PID class-load，唯一合法 production ModPresenceProbe）。產出各種集合、hash、全 SHA 與零略過 M7。純 docs 本身豁免 production mutation／另跑 local full，此處明定的是 milestone 最終 full。

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


