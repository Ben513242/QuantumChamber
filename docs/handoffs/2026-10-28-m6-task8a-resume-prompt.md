# QuantumChamber M6：新對話完整接續提示詞（2026-10-28）

## 0. 接續後更新（2026-10-29）

**目前接手點：** C1c-T `d0bf69b` 已建立typed NBT局部契約，C1c-A `bf46e97` 已接通來源／同live lease隔離native rebuild／exact Single局部閉包。T乾淨雙focused20/20、獨立spec/quality各C0/I0/M1；A乾淨雙focused Adapter54/54、Canonicalizer76/76，完整`run-all-55870333f3bb422b9f53535574e302ec`自然`END ALL exit=0`、JUnit838（0 fail／0 error／2既有skip），獨立spec C0/I0/M0、quality C0/I0/M1。首次full長cwd前置FAIL與所有RED/中途FAIL保留。這是本機CI oracle，非遠端CI；`createSnapshot/resolve`仍fail closed。C1d先做完整owner census、typed leaf/consumer白名單，100ms live hold I-1與8B3三bundle仍須過門才能進Task9；完整三role／OW973/N210/E20／FP與人工未完成。詳M6 note末節、plan Task8與STATUS current；以下舊段保留當時快照，v4父FAIL/B27 FAIL不改判。

本檔第1–7節保留交接當時狀態；本段為後續更新。8A完整B28在8d65769 pin通過且v4父FAIL/B27 G1 FAIL不改判。8B1候選code3ec19675經具名RED、乾淨雙focused、完整run-all與獨立spec/quality審查C/I0，已結案；正式createSnapshot/resolve仍fail closed。8B2內部G已在fix code0666cfd完成gate/mixin/context的乾淨雙focused/full與獨立C/I0，但8B2整體未完成；C1a純owned SettingsGraph已在fix code71e85ae完成具名RED、乾淨雙focused/full及獨立C/I/M0；C1b-B1白名單局部typed registry已在本機code a5db2c7完成具名RED、乾淨雙focused/full及獨立C/I/M0；B2局部End code acaf81b已乾淨雙focused49/49/獨立C/I/M0但base_3d_noise仍拒閉合；B3 code530491b已三samplerRED/乾淨雙focused70/70/完整full/獨立C/I/M0，但仍非三role/FP；C1c template安全DTO/來源/typed NBT v2設計C0/I0/M0待code，後續C1d仍須three-role/custom provider schema/resolve及100ms單次live hold量測，8B3的lifecycle/三bundle跨mapping／OS、Task8終局與M6人工/整分支門檻仍待。最新原件/限制看M6 note末節與STATUS最前current；新候選綁實際新HEAD，不沿舊8d/3ec驗證pin。


請接續 QuantumChamber M6。本檔是目前最新接續入口；先讀工作樹 AGENTS.md，再完整讀本檔。不要從歷史 M5／A7 setup 重來，也不要重新詢問已裁定的 M6 範圍。

## 1. 使用者最新要求

- 一律繁體中文（台灣），回報簡短、先結果與證據。
- 使用者已批准完整 M6：測試腳本安全與生成競態 → random seed → same-seed fresh／profile → Nether／End family、portal／gateway、respawn；M7 不納入。
- 2026-10-28 起，子代理只用 gpt-5.6-sol 或 gpt-6-sol；若工具不支援其中一個，使用另一個，不自行換其他模型。重用適當工作樹，一個實作者配有界獨立 review。
- 使用者要求「趕快完成、不要鬼打牆或太嚴格」。採已完成且未變的證據；不擴張無關框架／理論反例，不為純父端修正反覆啟相同 JVM。Minor 列後續，Critical／Important 只處理具體問題。
- 最新裁示：v4 Java 的 23 案已全 PASS，v5 只修父端 predicate且Java不變；可使用現有真原件做離線核對，再直接銜接完整 B。不要再啟 v5 的相同23案 focused。舊 v4 父 FAIL 必保留，不能重寫成 accepted。
- 若遠端工具／代理失敗，先核程序及落盤產物再續接；失效代理換新的允許模型，不盲目重送已啟命令。真的無可用額度時保全後說明，不捏造測試／review。
- 此次依使用者要求收在交接點。新對話收到「繼續」即按下方步驟工作，不再詢問是否開工。

## 2. 工作區與 Git

- 主 checkout：C:/Users/Ben/Documents/minecraft QuantumChamber，停 main；不得在此實作。
- 唯一 M6 工作樹：C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation
- 分支：codex/m6-universe-generation。所有指令明確指定此 workdir／git -C。
- 本交接文件改動前，local/origin feature 均為 f6065fde7f0dffe7b2a29c894f69a257978b4e64；這是前一次已推送的 docs checkpoint。交接文件會再有純 docs commit，接手時以 git rev-parse HEAD、git status、遠端 ref 讀回實際值，不沿用舊硬 pin。
- main/origin main 最後實核為 0d05f889da6f8b82b97af756aaa50066b3d42854；m5 tag peeled target為 a2d2b404329d2574469fca064a663e21fe89f333。M5已收尾／退役，不重建舊工作樹或重驗 M5。
- 正常 push 持續授權；不 force push、不建 PR／LICENSE、不 add -A、不 reset/clean/刪未追蹤檔。M6 main 合併、tag、人工 smoke、退役各依既有門檻，不能套用 M5 的單次例外。
- 所有 failed roots與frozen包保留；B28仍是未凍結草稿，可以完成該草稿，毋須因每個未凍修改另造版本。
- prototype與runtime原件在本機gitignored目錄，未隨push上傳。新對話須使用同一工作樹；若缺原件，先報明缺少的檔案，不以摘要冒充原始證據。

## 3. 目前完成度與可玩狀態

- 已完成、可玩的基準是 M5；完整 M6 尚未交付。
- M6 Task1–7及既有CI已完成。正式 tracked source/build/scripts/workflow仍是 Task7 code245051e87af563b778b79bca27e6706be30848f2 的基準，Task8目前是 ignored原型與驗證證據。
- 既有完整回歸 run-all-efbd4990e8824a63bc93b458643877b0、CI40184992145/head64d6317671b5e9152bfd9e088356d16175088b3c 只採原pin；不稱後來docs commit自己跑過full／CI。
- A7 38案、Magma、Axis、generic與End原11案的限定focused已通過及獨立review。不要重跑setup來重新建立這些已驗結論。
- Task8A仍未關。完整B G1/G2尚未通過，8B正式設定snapshot/API/context/lifecycle、三bundle跨mapping／OS、Task9起及完整M6人工／整分支收尾仍待。

## 4. 此次可靠收尾點

SDD根目錄為工作樹下 .superpowers/sdd/2026-10-24-m6-universe-generation/（以下task-08相對此根）。runtime roots在 .superpowers/verification/。

1. v4真Java：root g-e660823a8ed3b887/p1-fd1eacb0，PID9212，6014ms，自然child exit0。23 rows／23 graphs、5 Context/Session/lock、2 typed-only／21具名拒、0 component／FP、32 ResourceManager reads＋3 owned reads、charged68508、fatal空。舊11與P2 meter都PASS。
2. 該run父端close-audit仍FAIL：END_DEP_EDGE_COPY，acceptanceForbidden=true。父N18 predicate誤刪全部typed-entry-data，連NODE→BASE鄰邊也刪；因此不能把整個v4 gate稱PASS。
3. v5只把incoming刪除改成from=NOISE、target=NODE、path=typed-entry-data；argument2也精確比from=NODE、target=BASE。保留完整陣列順序／scope／原圖等值。127 Java source/class全部與v4 byte exact，未重編。
4. 新父oracle唯讀核v4全部真原件回傳accepted:true／23rows／5Context／23graphs；未寫accepted marker、未改原root。這是離線複核，不是新的native run，亦不追認v4父FAIL。

重要原件：
- task-08/end-dependency-implementation-v5/REVIEW-PACKAGE-v5.md，SHA 3ac10f12aa5ca92c1600373194503787c97036eeebb96baececdea4c7ec502b3。
- 同目錄 V4-PARENT-FAIL-RESULT.json，SHA a0d7cf43a223022aa2e425277e43ca83016231c2dd63cd94c3bd9a57101df759；原178檔manifest SHA d868e1253d3febc35fcae1523e5a813a5d8ddebe410dba12972b965a4474fa41；219 postpins exact、PIDgone、五lock獨占/hash不變。
- 同目錄 V4-RAW-OFFLINE-CHECK-v5.json，SHA 59f9f65a6deb624030e9fdb1e94d63117fc700e23af65adeed87c9fe0e8541ce；v4 final SHA 6646958b8a26919c1bb2b9c06aad5d9278d4a58af9ca4ddd85336e26e354296a。
- 同目錄 EDGE-PREDICATE-CONTROLS-v5.json，SHA a2c1da34d801d233a215ba9be6b5c102605c8bdc0d2fd34f9108fffb66633473；九項真edge控制包含鄰邊保留及錯刪／from／target／scope／順序拒絕。
- task-08/end-dependency-v4-meter-review.md，SHA 91fc6b4b98f1c77bfb1a9ebd15f0362de68cf665e297745745e9d0e5d6f06f1c，C0/I0；舊source/parent/lock審查沿用其未變部分。
- task-08/2026-10-28-owner-handoff.md，SHA e1b89fab81a6a26d441e019b4b30fedb53664831564dde7a40788485bfb32b07。

v5 REVIEW-PACKAGE 最後仍寫「再跑fresh focused」是較早流程，已被上方使用者的收斂裁示取代。v5預留g-d50fe59fcde94491/p1-424648db未建立；不要為父端一行修正再次啟相同23案。

## 5. 唯一待續實作：B28部分草稿

目錄 task-08/graph-strict-v28-full-end-dependency/。

- 從B27組合116個Java artifacts：新增EndDependencyUseContract.java/class、替換Context/NVC四件；其餘110件沿B27。不得帶入focused test Main/Launcher。
- 已寫dependency use-proof父檢查、launcher/controller與strict-spec-full-end-v28.json；僅三個模組Node語法check exit0。
- 尚未完成：完整pin驗證、pure prepare、父proof必要控制、preservation/manifest/review package、獨立合併review。未freeze，G1/G2未啟。
- 部分spec列196 pins，SHA f73919f539d3f8060a911e3618c538a5ece43b4fbe3f62555b1d4ad0a06404a5；proof 4c50286d5415b387af5bf50383e3d95f6f0b3320846d857322a487fcc4a0b54d；launcher 02919410d7b85546dbe94b6674143dcf995e1ebace554ba7414d8f0e58a6f40d；controller d67be437560d95b04d5be0e16661e59a7fb621353efa4eb47790dbd555da6aed。這些只是停止時的草稿hash，不是可啟動證明。
- 預留root g-97908086aa3f6eac未建立。B28的control-owners含複製來的B27 claim b6cb136e6d57ff8519c027f9a50fb919da1de36e378c942254e2ee55365f8b4e-explore.json；不是B28啟動證據。保留歷史claim，正式啟動前使用新的乾淨owner位置並讓controller/spec一致，不刪原件。
- 目錄保留 copied v27 spec/manifest/PREPARE-RESULT/control紀錄。不得採它們當B28結果；只採完成後新B28具名紀錄。

## 6. 新對話直接執行的順序

1. 唯讀核Git/porcelain、Java owner/lock與B28 root實況；先讀STATUS最前current block、owner handoff、v5包及B28草稿。舊含「最新」的歷史段落不當現況。現在所有Java已正常退出，不應有要接管的live run。
2. 交接docs會改HEAD及plan/note hash。先重綁B28 expectedGitPin、相關文件/模組pins、root/nonce與owner，再做純prepare；不要拿f606硬pin或舊prepare直接start。原f606 binding raw在task-08/binding-inputs-f6065fde-20261028/mapping-index.json，舊1ec另有archive，歷史run不重釘。
3. 完成B28最小組合與必要控制。核來源artifact一致、exact End dependency source/identity/state/codec/edge與兩phase class-source；完整B的CaseMeter含多role，應用實際canonicalAccounting邊界，不能照抄focused P2的8304固定起點。不要擴動Core／budget／無關矩陣。
4. 用一次有界獨立review合併檢查v5精確predicate＋離線真原件採計＋B28組合，沿用未變source的已審結論；不要拆成多輪重審或再跑相同focused JVM。Critical/Important清零後由controller唯一啟G1。
5. G1固定34case／24Context／28role、9對18個budget evaluation。G2固定兩BASE／2Context／6role及六份canonical／node manifest冷JVM逐byte比較。維持2G、phase180秒＋合作30、同batch600秒；只有G1父端accepted且剩至少210秒才另放G2，不在兩phase中間等一輪漫長review耗光batch。
6. G1/G2後做一次必要native/quality結果review，原件和限制寫note；通過才明確放8B。Task8完成及Task9前仍要三bundle跨mapping／OS，不能以Windows named prototype冒充。
7. 若真失敗：保存原root/receipt、查具體原因、只修必要差異；不覆寫失敗改判、不提高上限來湊PASS。若只是工具回覆失敗先核原程序與輸出，不重送已啟動命令。

## 7. 工具與交付習慣

- 同時只一個native／Gradle／gate。JVM以非阻塞module controller或保存Promise啟動，短poll；不要在30秒工具cell同步等整段native。取消只走已審合作流程，不強殺持Session的JVM。
- Node fs／execFile對此工作樹較快；PowerShell/exec前置曾單次約70秒。工具慢時可由reviewer給完整文字、controller忠實保存，清楚標誰實核哪些證據；不冒稱未做的檢查。
- 保持所有run、frozen、原世界；不force-add ignored原件。可重用正式harness之後才依8B/plan整理到tracked路徑。
- 回報務必分清Java child、父oracle、離線稽核、完整B、正式release／人工驗收。現在可玩M5；完整M6還不能稱完成。
