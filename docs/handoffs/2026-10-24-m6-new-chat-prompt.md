# QuantumChamber M6：新對話完整接手提示詞

> 最新（2026-10-27）：Task8仍8A，A7與End等限定focused已通過；完整B27 G1停在End相依owner，G2未啟。接續看§11與STATUS最前current block；§9停工點已失效、§10是較早歷史。遇遠端回合失敗先核既有程序／產物，再接續或換fresh SOL。範圍／順序已批准，不重問、不重做M5、不重建工作樹。

你是 QuantumChamber（Minecraft 1.21 Fabric mod）的 M6 接手者。現在已有M6工作樹與經review的spec／plan，按§11與最新STATUS接續；§1–8保留最初開工背景及長期限制。

一律用繁體中文（台灣）回報；程式識別字、指令、檔名維持原文。先講結論與可驗證證據，再談推論。對使用者用白話。可以派工給 gpt-6-sol；總協調者負責分工、驗證與文件真實性。

## 1. 已完成的 M5 與開工核對

- 主 repository：C:/Users/Ben/Documents/minecraft QuantumChamber。
- M5 已以 --ff-only 合併 main，annotated tag m5 的 peeled target 是 a2d2b404329d2574469fca064a663e21fe89f333；tag object 是 15c2943ce37950f468b030c5d194485a328c1705，兩者已推上 origin。
- 合併後另有純狀態文件 commit。M5 最後治理 HEAD 用 main 上唯一主旨「docs: record M5 integration and retirement handoff」解析；不要把 a2d2b40 當作最後文件 HEAD。
- 先核 main、origin/main、tag、工作樹列表與 porcelain。預期主 checkout 乾淨、main 包含 m5。若 dirty、tag 不符，或出現交接未記錄的程式變更，停下回報，不 reset／clean／自行合併。
- 舊 M5 工作樹 C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m5-collapse 是已指定的不備份退役目標。先確認路徑與 git registration 是否都已移除；若仍存在，先讀最後交回紀錄核對中斷點，不自行重建、清空或接著實作 M6。
- M5 的 run/、.superpowers/、一次性派工與原始驗收資料依使用者裁示不另留備份。長期摘要與 hash 在 tracked note／handoff，正式可重跑 harness 在 scripts/verification/。缺少舊 ignored 原件不是要求重新驗收 M5 的理由。
- 不在主 checkout 或 main 上實作。重用已存在的 .worktrees/m6-universe-generation／codex/m6-universe-generation；所有指令明確指定此workdir，不另建同一milestone工作樹。

可用的唯讀開工指令（PowerShell；safe.directory 只對該次命令生效，不改全域設定）：

~~~powershell
$qcRepo = 'C:/Users/Ben/Documents/minecraft QuantumChamber'
git -c "safe.directory=$qcRepo" -C "$qcRepo" status --porcelain
git -c "safe.directory=$qcRepo" -C "$qcRepo" rev-parse main
git -c "safe.directory=$qcRepo" -C "$qcRepo" log -1 --format=%H --grep='^docs: record M5 integration and retirement handoff$' main
git -c "safe.directory=$qcRepo" -C "$qcRepo" rev-parse 'm5^{}'
git -c "safe.directory=$qcRepo" -C "$qcRepo" ls-remote origin refs/heads/main refs/tags/m5 'refs/tags/m5^{}'
git -c "safe.directory=$qcRepo" -C "$qcRepo" worktree list --porcelain
~~~

## 2. 必讀文件與限制 context

依序讀，先定位再讀需要的段落：

1. 根目錄 AGENTS.md 的硬規則與目前接手入口。
2. docs/handoffs/2026-10-21-m5-wrapup-to-m6-codex.md：§7 最後紀錄、§3.7 M6、相關裁示。
3. docs/quantum_superposition_chamber_design.md：以 rg -n 定位「### M6」及 seed、generation profile、Universe family、portal／respawn 的相關契約；M7 是另一個 milestone。
4. docs/superpowers/specs/2026-10-01-m5-collapse-passage-design.md（binding v5.10）：M5 authority、schema4、persist／recovery、投影／通道契約，以及 §20.14 的延後事項。
5. docs/implementation-notes/2026-10-01-m5-collapse-passage.md：只讀檔頭、最後收尾節、M6／已知限制命中段；不要整份載入約一萬行的 note。
6. docs/manual-acceptance/2026-10-01-m5-manual-smoke.md、docs/implementation-notes/compatibility.md：已驗範圍與未外推的界線。
7. scripts/verification/README.md，以及實際要碰到的 source／test／build 檔。先用 rg --files／rg -n，避免掃完整歷史。

新的 M6 spec／plan／note 使用新的日期與檔名；新的 SDD ledger 放在 .superpowers/sdd/<date>-m6-universe-generation/，確認已被 gitignore，不 force-add。不要依賴已退役的 M5 ledger 或 scratchpad 才能開工。

## 3. M6 已知方向與需要確認的決定

長期設計列出的 M6 範圍：

- random seed、same-seed fresh world、generation-profile mutation；
- generation profile registry／API；
- 每個 Universe family 的 Nether／End 生成與持久化對齊；
- DimensionFamilyRoutingService：Nether／End portals 與 gateways；
- RespawnRoutingService：custom family 的重生與 fallback；
- 同 DimensionRole 的 Chamber projection／transfer tests。

使用者已指定併入 M6：調查並修正 StrongholdGenerator、Nether fortress、End city 在多世界同時生成時的 static 可變狀態競態。既有 H5／H6／H8 修補不能冒充這些新 race 已修。

使用者已裁定完整M6分段實作：測試腳本與生成競態 → random seed → same-seed fresh／profile → 完整Nether／End family、portal／gateway與respawn；各段驗收，M7不納入。這個範圍與順序不再待問。

不要納入 M7 的 literal branch／snapshot；它有獨立設計 gate。也不要為「完整」順手新增與所選切片無關的 renderer、UI 或架構重寫。

## 4. 必須保留的契約與待辦

- M5 舊世界、已落盤 Universe 身分／seed／destination、checked MEASURED＋SELECTED 與 schema4 receipt 不能被重新抽選或偷偷改寫。新 family／profile／schema 的遷移與相容性要先寫進 spec，包含重啟、部分提交與損壞時的處置。
- 遵守 commit-before-expose、checked 落盤、fail-closed、exact owner／world identity 與既有跨越／返還語意。不得因加入生成多樣性而讓同一已建立 Universe 每次載入重新選 seed 或 profile。
- 同 XYZ passage、不提供正規回程、投影艙不可啟動等 M5 行為，在新的 binding spec 明確變更並經確認前維持不變。
- Windows atomic move AccessDenied 在 M5 已裁示維持既有 fail-closed／退避，不加短暫 retry、不更動 Defender。M6 若要改，必須作為明確範圍決定，不能順手修改。
- 原版 playerdata 若自行吞下 IOException，checked Crossed 不等於已證明 playerdata 持久化；保留既有已知限制，不能靠文字宣稱已解決。
- 下一輪 Step 0 的兩個非阻擋 harness Minor：probe-common.ps1 的 New-OwnedRoot 應補 run/ 祖先 canonical／reparse 守門；gates.ps1 的 Move-Fresh 在 move 後 New-Item 失敗時，應使復原仍能執行並保留原例外。第二項目前原世界會留在 backup，不是資料已毀損。這些獨立拆 task，不混入 generation 的 production commit。

## 5. 實作與驗證方式：保持精簡，不省略品質門檻

- 範圍確認後先寫 M6 binding spec，經獨立 spec review；再寫 plan，經獨立 plan review。Critical／Important 為 0 後直接按核准 plan 實作，不再重問是否開始。
- 每個 task／fix round 先有短派工檔：背景、HEAD／porcelain、允許與禁止路徑、必讀範圍、驗收及回報。不要把整份歷史塞給每個代理。
- 一個 implementer 處理有界 task；完成後由未參與實作的全新 context 分別做 spec compliance 與 code quality review。不能同 context 自評通過；有 Important 先修，再由新的 reviewer 複審。Minor 排下一個 Step 0，不另開冗長修正輪。
- 行為變更保留 RED→GREEN；mutation／暫時改動以 adapter 套用還原，並用 git hash-object 核回原 blob。純 refactor 比具名 probe 斷言與 RESULT=OK，不偷偷改名。
- 純搬移、refactor、行為變更分開 commit。code commit 前，只對該 commit 當下內容做必要編譯並保留 log。
- 在 plan 一次寫清 focused／回歸矩陣。程式或 harness 行為改變要測受影響路徑，在同一乾淨 code HEAD 留兩次必要 GREEN，並完成該風險批次的完整 run-all。不要對未改變的 code HEAD、review 結論紀錄或純狀態文件反覆跑相同完整 suite。
- 純文件／合併狀態紀錄不重新跑 runtime；須以 git diff 證明沒有 source/test/build/workflow 變更，明列採計的 code／CI SHA，不能說新 SHA 自己跑過測試。若 M6 的既有硬規則與擬採用的精簡矩陣衝突，開始實作前集中列出一次讓使用者裁示，不偷偷外推 M5 的特定豁免到 production。
- 同一時間只跑一個 Gradle／gate／probe／run-all，一律經 scripts/verification/。會 move/restore 世界的腳本不可強制中斷；有 gate 在跑時不殺 daemon。重導 run-all 的外層可能等 daemon，完成以 END ALL exit=0 為準。
- 失敗 root 原樣保留，新 label／nonce 重跑，不覆寫失敗改判；未在既有清單的失敗先取證調查，不隨意說偶發。
- 完整回歸依任務比對 SUMMARY、帶 class 的 testcase／JUnit 集合、release／sources 內容、排序後 WARN／ERROR、pin 與 worldgen 例外。每個差異有歸屬；CI artifact／log 只讀，不執行其內容。
- 每輪結束同步 note、plan、AGENTS 接手入口與 ledger；note 的行號條目後放 review 結論、Minor 處置、range／最終 gate。沿用檔案行尾，git diff --check；不要為結果文件再衍生無限 review／CI 循環。
- 任務過大先拆小；接近 context 上限先寫 STATUS.md（精確HEAD、下一步、有效／失敗roots、尚未完成事項）。接手從最新STATUS繼續，不重跑已完成部分。

## 6. Git、資料與授權邊界

- 不用 git add -A，逐檔 stage；自己的 docs commit 用 git commit -- <paths>。不 rebase、reset --hard、checkout --、git clean 或裸 git stash。不建立 PR／LICENSE。
- main 僅 --ff-only；不在 main 上直接實作／commit。新 milestone 的合併、tag、退役仍要其各自明確授權；M5 的一次授權不能拿來做 M6。
- 正常 push 已有持續授權，不再每次詢問；不含 force push 或刪除遠端 refs。需要再次裁示時，先做出具體可審查結果，清楚說明哪條規則／哪個決定需要確認。
- M5「整個指定工作樹不備份刪除」只適用 M5，不外推到 M6。M6 使用者世界、run/ 與唯一證據依 AGENTS 的一般保全規則處理；沒有新的處置同意不得刪。
- 移動世界前核 canonical path、非 reparse、無 live Java owner、session.lock 已釋放；fresh world 只能可恢復地移到唯一備份並逐檔 hash 還原。
- testmod 只在 src/testmod 與測試runtime；release/sources不能混入 testmod/gametest/fault mixin。production ModPresenceProbe 是合法 main class，不誤判。main-only 必核 mod list/classpath/argfiles/DLI/class-load，不能用 GameTest runtime 冒充。
- 人工 gameplay smoke 和自動測試分開，不能代替彼此。M5 的遊戲代操作授權不自動擴大為 M6 任意操作；M6 清單先具體、白話且可審查，再依必要範圍取得授權。
- 若遭關機中斷，先唯讀核HEAD/porcelain、Java、session.lock、run/停在move中的世界，中斷root不採計；不要盲目重跑或刪除。

固定基線為 Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Loom 1.7.4、Java 21。Gradle 路徑 C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat。不要在 M6 起手順便升級整套版本。

## 7. 已有驗證基準，避免重做 M5

- 最後本機 full：乾淨6b116e60c0865000b5381740146aa4c54428297e；run-all-e32343e8bd034936aee50b9b3db48a1f，END ALL exit=0。SUMMARY：default179/windows89/legacy1/M560/perTest56+1/JUnit666（0fail/0error/2skip）/checkpoint17/release432entries/385classes/sources226/testmodHits0/MainOnlyPASS/M3 4+5/recovery24/collapse69/HALT31。
- CI：f99ce66132dbe4b7d4aff6719ef95bdf8486eac0，https://github.com/Ben513242/QuantumChamber/actions/runs/40079019075 。Windows job120828478496、Ubuntu jobs120828478682與120834288496皆success，TPS65.9/86.3/86.4。14原件、測試集合、JAR內容與log差異已核，未歸屬差異0。
- M5 required manual smoke 已完成並獨立review。整分支spec C0/I0/M0、quality C0/I0/M2；最後文件spec/quality各C0/I0/M1，同一導覽Minor已更正。只保留前述兩個harness Minor待Step0。
- 這些是已完成 M5 的基準，不是 M6 新行為的測試證據。新的程式行為要有自己的 RED／GREEN、review與必要回歸。

## 8. 最初開工流程（已完成，不重跑）

以下是最初開工流程；目前已完成，後續直接按§11接續：

1. 實際main／origin／m5 tag／工作區狀態與證據。
2. M6哪些範圍是已知要求，哪些只是建議或待決；建議第一個可驗收切片與順序。
3. 僅詢問尚待使用者裁示的範圍／優先序（若有規則衝突，一併列出一次），同時繼續不依賴答案的盤點。
4. 範圍確認後建立M6 spec／plan、安排獨立review並接續實作；不把準備完成誤報為M6已實作。


## 9. 歷史停工點（2026-10-26；已被恢復指示取代）

使用者明確指示「本週20x方案使用量剩11%,自己找收尾點」。本輪不再新編譯、測試、修正或擴大review；所有代理已交回／停止，沒有A7 fixture、pending工具或live compiler。這是可接續的停工點，不是Task8完成或gate waiver。

- 交回前已驗local/origin feature為612a5e78abb099e3604176691fedd2e20637a614（已push）；其後只允本次交回文件commit，實際HEAD以Git為準。正式code仍245051e，main仍0d05f889、m5 peeled仍a2d2b404，未合併／移動tag。既有full/CI只採原pin，不代表後續docs HEAD已跑。
- spec v1.3 SHA5CA4C3058C2E0564A7EF8979427C4C5A6DD3FE5B9ED8FBFF999BCCC0E53BC4A9；plan C8DA5985FD6984B3B074EBE697955182389D311D2D46468FF2FA6F5478225453。正常遊戲、snapshot期間無外部改檔、外部修改reload／重啟的使用者裁示保持。
- 唯一A7準備owner在本工作樹的.superpowers/sdd/2026-10-24-m6-universe-generation/task-08/graph-absence-contract-v1/；sources、observer/agent、純編譯紀錄及immutable package保留，沒有force-add。這些準備與原始證據只在本機ignored空間，不等同已推送的正式程式。
- 實作者交回：task-08/A7-closeout-2026-10-26.md，SHA EF98C94685BD620676C849A2B5CB01919E8B3D9247A87AB107408802BE67106C。最近pure javac 2740／19312／9244均自然closed0；core最後finally小delta尚未重編。集中Main38尚未寫成、尚無final candidate/freeze/guards，不能啟動A7。
- 核心先行review：task-08/a7-core-preflight-review-v1.md，SHA B3F390BD691C73E168D4F361B499251326567BD5AEB1B4C9A4C7CF9A5280B15A，C0/I2。I-1：extract finally末尾endAttempt/checkFatal仍可能覆蓋primary；須單一例外仲裁並保suppressed與fatal latch。I-2：depth replay仍沿去重readerSamples且把SNBT當binary；須依完整ReaderOccurrence及kind重播。此輪未修，不可升格final freeze。
- immutable core-review-package-v1為14Java，manifest SHA200CF6A383E28DA3BF90948CC2DF3D0F12DA5D4BBC7ECAFD1A7E0E8F665D77C4，不改該包。後續修正另釘新包並獨立複審。
- Loader前提候選：同owner/A7-native-transform-preflight-v2.md，SHA6D724C129CA6477F1C65CBAB0919A9306F4ED12E56E2A497E488EDBA44D35413；primary索引C172D5DE846BF2CD1471895A32BACF22FC41D55FA42DC7204311FBB5F946CC6D。尚未fresh review／採用／實作；現agent raw hash硬拒，不能假定Knot define bytes就是raw JAR。候選須在target定義前獨立釘fixed Loader expected，再證Mixin identity與incoming exact；不反射重入或略hash。

下一次按此順序：核實際Git／程序狀態→讀closeout與兩項finding→有限修正及複審／Loader前提review→完成Main38、once-dev、16hook觀測與包裝→按當時新pin做純編譯/freeze/guards→root短讀後才另授權唯一A7 JVM。A7通過後才完整B34/G2/native review；Task8未完成前不進Task9。新實驗不沿用dce／614／612舊硬pin。

所有舊FAIL／INCOMPLETE／root／binding archives保留，不改判、不刪世界或工作樹。無自動排程或背景續跑；待使用者下次指示。


## 10. 恢復開工與A7實跑（2026-10-26）

- 使用者已撤銷停工點並要求持續工作；若代理遠端回合失敗，先核程序與產物再續接／換新SOL，不能因工具回覆失敗重送已啟動的JVM。
- 唯一工作樹為 C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation；本節更新前HEAD a6b7fcc39fe52945176bef036759df8d98204c75。後續文件commit以Git核實，main仍0d05f889、m5仍a2d2b404，未合M6／移動tag。
- 原core I1/I2已由獨立複審3B7D7ACB…關閉，Main38已寫成；Loader v4設計、錯誤仲裁與System argv候選已有獨立review。這些限定證據不等於38案例通過。
- 三次A7自然exit1：PID25756/631ms為ENV_UNLISTED:SYSTEMDRIVE；PID40768/1063ms為HELPER_ROUTE／HELPER_NORMAL_DEFINE_BYTES；PID34220/1166ms已證helper正常define，停STATE_VALUE:semaphore。三輪各自pre/post輸入一致，沒有載入五個原生target或執行38案例。完整root與hash見M6 note及ignored原件。
- 最新調查：semaphore為歷史OR latch、pop不清，inactive仍由depth0等條件證；另固定AccessWidener.classes為LinkedHashSet。兩token窄修已編譯並經獨立review8992DAFD…通過，plan已採用；下一步新pin/freeze後由root啟下一次唯一A7，禁止清flag、放寬任意值或略過incoming exact。
- 本輪正式src/build/scripts/workflow對245051e無差異；既有full efbd／CI64d只採原pin，不稱新文件HEAD已跑full。三次原始binding文件已保存task-08/binding-inputs-a6b7fcc；歷史FAIL不改判。
- A7通過才完整B34/G2/native review；完整8A資料gate後才8B。Task8及三bundle跨平台gate未完成前不進Task9，M6人工／整分支／main／tag／退役門檻不變。


## 11. 2026-10-27 接續點：End focused完成，完整B相依owner仍待修

- 所有本節原生證據的Git pin為1ec44f10e878ecc86ae773de1fbf658ab6a3f2d3。本次只更新接續文件；後續HEAD／push以Git讀回，不把新docs SHA當已跑full或native。spec v1.3不變；原spec/plan/note/AGENTS/兩handoff原bytes已保存task-08/binding-inputs-1ec44f10-20261027/mapping-index.json。
- A7已38/38，獨立結果review bd7c0150… C0/I0/M0，只結owned Model A/component。Magma、Axis、generic RED/GREEN與End各focused的範圍、root及review hash見M6 note最後一節，不重跑已完成的setup。
- 最新End v6 focused：g-73006d864209429f/p1-be5dce71，PID14088／4610ms自然exit0；11案=1 typed-only＋10具名拒、11graph、2Context／Session／lock關閉、0 component／FP、source14、charged41520。父oracle與fresh native結果review 5281ed89… C0/I0/M0；僅限End settings契約，不是完整圖。
- 最新完整B27 G1：g-7d3a70ec3b9f4f67/p1-d4526a5e，PID38124／10131ms自然exit1；constructor control PASS，base-normal的overworld/the_nether COMPLETE、the_end FAIL於UNSUPPORTED_END_OWNER registry|minecraft:worldgen/density_function|minecraft:end/sloped_cheese。193postpins exact、雙Session／lock關閉，G2未啟。舊root保留，不改判或重送。
- 下一個限定方案：task-08/end-dependency-owner-audit-v1/CONTRACT-PROPOSAL.md（SHA69bc0509ddd953b45a5d74acc60d8717109ff3fba5f3fbe62470ff19370a4131）。固定JAR896份worldgen JSON只有3處end_islands，新增owner僅sloped_cheese的argument1；失敗raw沒記該物件identity，不能把unit-codec推論當實測。先關契約review，明列新正反案例與預算，再實作新immutable候選；不泛放寬Simplex、不保持activeEnd覆蓋整個drain。
- 順序不變：限定修正／fresh review／focused → 完整B G1 → 同batch剩餘至少210秒才G2 → fresh native及quality review → root明確8B放行。8B、跨mapping／OS三bundle、Task9起與人工尚未完成。正常push持續授權；main／tag／退役各自門檻不變。
- 唯一工作樹與SOL要求沿用AGENTS。目前沒有待存取的live JVM；若接手時實況不同先核owner/lock，不依文字盲啟。STATUS第一個current block優先於下方含「最新」字樣的歷史段落。
