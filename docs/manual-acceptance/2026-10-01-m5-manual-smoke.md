# M5 塌縮與單向通道人工 smoke 清單

狀態：**必測操作、取證及獨立結果 review 已完成**。2026-10-24 由 Codex 經 Ben 明確授權代操作；以下 PASS 是實際觀察結果，不代表 M5 已合併或整體結案。依 [M5 binding spec §18](../superpowers/specs/2026-10-01-m5-collapse-passage-design.md) 與 F3d／F5-3 裁示核對；M1–M4 歷史結果保留於 [原清單](2026-09-24-m1-m4-manual-acceptance.md)。

## 執行基準與記錄方式

本輪日期：2026-10-24；操作者：Codex（Ben 授權鍵鼠、指令、截圖與測試世界提示）。基準 HEAD：`734d72b67d47c09a4348a8667a9bf794b4e41b72`，開工 porcelain 空；本輪未改 production。Minecraft 1.21／Fabric Loader 0.17.2／Fabric API 0.102.0+1.21／Java 21。先以單人整合伺服器驗 A／B／E，再以僅監聽 `127.0.0.1:25576` 的 dedicated server 與 `QC_A`、`QC_B`、`QC_OBS` 三個獨立 main/client 開發 runtime 驗多人與光影；未使用 probe/testmod 啟動入口。

先用獨立的新測試世界或 Chamber 做單人兩輪；DORMANT 只用外部備份 `QC-accept-WM4` 的**複本**，不碰備份原件與使用者正式世界。需要唯讀查收據或比對 journal 時，記下命令、時間與輸出檔，不改收據。測試過程不要用 `/tick freeze`，否則通道 60 秒也會停止計時。沒有正規回程；目的宇宙的死亡與原版傳送門屬非正規回程，不能當作本次通道成功或安全回程的驗收方式。

| 項目 | 做法與預期（同列核對） | 結果與證據 |
| --- | --- | --- |
| A1 單人跨越 NEW | 在新艙選到 NEW 門，等走廊安全返還與清理。**預期**回原艙後，門口先是不可穿越的量子膜；可穿越時比較器為 15。穿過後以 F3／唯讀讀值比對跨越前後的 XYZ 相同，維度變為 `quantumchamber:universe/<id>/overworld`；地形是同 seed 的原始地形，沒有來源世界的修改，身後是不能啟動的基岩投影艙。到達 title「你已進入新的宇宙。」在載入畫面結束後**仍可見**；它的總顯示設定約 5 秒，不要求載入後再完整顯示 5 秒。單人跨過即全員跨過，原艙門回到一般開門、record 已移除；跨過者不再收到「量子通道已關閉。」。 | PASS。普通世界 NEW 跨越、同 XYZ、原始地形／來源修改隔離、投影不可啟動、載入後 NEW title、1/1 終局已核；另以單一參與者 NEW 輪次補到 `sealed_x` 碰撞再轉 `passable_x`。詳下方 A1。 |
| A1b EXISTING 到達提示 | 在有合格 EXISTING 收據的**另一輪**跨越，記錄收據類型。**預期**載入畫面後能看到「你已進入另一個宇宙。」；F3 顯示相同的既有目的宇宙。若本輪沒有取得 EXISTING，明記未驗，不把 NEW 的觀察替代。 | PASS。checked 收據確認 EXISTING；`c2-cross21` 顯示「你已進入另一個宇宙。」且地形已載入，F3／讀值為原有 `c2234adb…`。 |
| A2 單人到期 | 另選一輪 NEW／EXISTING；量子膜可穿越後留在原艙，不跨越，等遊戲時間約 60 秒。**預期**通道到期、量子膜消失，原艙門回到一般開門；以唯讀讀值確認 record 終局。這輪與 A1 分開，因單人跨過會立即使通道結束。 | PASS。B 複本 C2 的獨立 NEW 輪次留在原艙；11:09:01→11:10:01，1200 ticks，0/1 跨越；膜消失、普通開門／輸出3、record 移除。 |
| B DORMANT 續行 | 從外部備份複製 `QC-accept-WM4`，啟動複本前後唯讀記錄三座艙的 exact 收據（SOURCE／NEW／EXISTING）與供電。只進入**已供電且有 DORMANT 收據**的艙，依讀到的類型先寫該艙預期。**預期**沿原收據續行，不重抽；SOURCE 最終一般開門、不配置宇宙，NEW／EXISTING 依其收據準備通道。若某艙不符前置條件，記為未驗並附讀值。 | PASS（符合前置的 C2、C3）。原件三筆皆 NEW；兩個已供電艙沿原 allocationToken 續行，沒有重抽。C1 未供電，依前置規則未驗且原收據保留；沒有冒稱測過 SOURCE／EXISTING 舊收據。 |
| C 兩人一跨一留 | 用兩個 client 進同一個 eligible cohort；待可穿越時，一人跨過，另一人留在原艙。**預期**只有跨過者到目的宇宙，留下者仍在原世界；跨過者收到正確到達 title。通道最後結束時，留下者收到「量子通道已關閉。」、原艙門回一般開門；已跨過者不收到這句。兩人的 F3 與訊息各自截圖。 | PASS。C2 與東向輪次皆僅 OBS 跨越，A 留在來源；東向輪次14:04:41即時拍到 A 的關閉提示，14:04:43 OBS 仍在目的宇宙且無該提示，正常1200 ticks／1/2終局。 |
| D 光影與量子膜 | 啟用已記錄的 Sodium／Iris／Complementary render stack，分別從兩個門軸向靠近、瞄準與穿越量子膜，並在遠處觀察關著的門。**預期**量子膜可見、可瞄準，薄平面周圍沒有透視破洞；穿越仍到正確目的宇宙，光影保持正常。記錄實際版本與畫面；F1b 的 `enter`／`return` 測試不能代替此項。 | PASS。NORTH／EAST 的 `passable_x`／`passable_z` 皆可見、可瞄準且真跨越；東向另驗 `sealed_z` 兩次前進受阻、正面／斜角邊緣正常；兩軸向閉門遠景正常。版本與限制見下方 D。 |
| E 目的地持續性 | 在 A1 的目的宇宙先記錄 F3 維度與位置，登出重登，再重啟 server 後登入。**預期**兩次都回同一個 Universe，而非來源原世界；保留前後維度、位置及 server log。 | PASS（單人整合伺服器）。登出重載、正常停止整個 Java 後由新 Java 登入，皆回同一個 U 與相同位置；不冒稱另做 dedicated server 重啟。 |
| F1 M2 A-2：自然到期返還 | 依 [M1–M4 清單 A-2](2026-09-24-m1-m4-manual-acceptance.md) 建立單人走廊 session，讓效果自然到期。**預期**安全返還原艙、沒有藥水退款、可開門離開；在艙內加入不影響測試的裝飾方塊，確認新返還落點不會把人卡在方塊內。 | PASS。原版180秒藥水、生存飲用3瓶→2瓶；自然到期回避開兩石頭的空位，無效果、仍2瓶；真實移動、開門走出，輸出3。 |
| F2 M2 B-1b：斷電返還 | 依 [原清單 B-1b](2026-09-24-m1-m4-manual-acceptance.md) 在走廊斷電並保留一個可核對的掉落物。**預期**玩家安全回到原艙、掉落物在原艙中央、效果不重置，最後轉 OFF；以裝飾方塊確認新落點不夾住玩家。 | PASS。真 Q 丟鑽石，斷電後玩家回安全空位；同 UUID 掉落物回原艙中央，效果2751→2591 ticks未重置；能開門走出、輸出0。 |
| F3 M2 C-3a：喝奶全組返還 | 依 [原清單 C-3a](2026-09-24-m1-m4-manual-acceptance.md) 用兩人及觀察者，讓一人喝奶解除效果。**預期**兩人都安全返還原艙、原有掉落物回原艙，尚有效的效果繼續遞減；檢查兩人都不被艙內裝飾方塊卡住。 | PASS。兩人真喝藥、B真喝奶；兩人回不同空位並能走動，A效果3485→2485→1537 ticks持續遞減；掉落物回原艙後由B拾取，旁觀者畫面可見兩人未卡進石頭。 |

F1–F3 是 `SessionRecoveryManager.recover()` 返還落點修正（R2 M6）的必要重驗，涵蓋一般 M2 RETURNING。整合審查的 R2 M5 另提 B-1a、B-3c、C-1b、C-2a；F5-2 spec reviewer 已依程式判斷這四項不經改動路徑（前三項從 IDLE／INVALID 起步，B-1a 的 pending 路徑會把 current 設為 INVALID），所以**不列為本次必測**。若人工 smoke 順手涵蓋其中一項，可在下列選測欄記錄，不能把它冒稱為必測已完成。

| 選測 | 做法與預期 | 結果與證據 |
| --- | --- | --- |
| R2 M5 抽驗 | 從 [M1–M4 原清單](2026-09-24-m1-m4-manual-acceptance.md) 選 B-1a、B-3c、C-1b 或 C-2a 之一，沿原步驟與預期重做；寫明選擇理由。 | 未執行：＿＿ |

F5-3 的文字審閱不需另開遊戲輪次：2026-10-24 已唯讀核對 `src/main/resources/assets/quantumchamber/lang/en_us.json` 的 NEW 為 `You have entered a new universe.`、EXISTING 為 `You have entered another universe.`；`zh_tw.json` 的「新的／另一個」也對應。這是文字檔審閱，不代表上表的 title 畫面已人工驗收。

若 A1／A1b 的到達 title 在地形載入畫面後不可見，先記 FAIL、畫面與時序；這可能是 title 在 `DownloadingTerrainScreen` 期間已開始倒數。是否改在 teleport confirm 之後或延後數 tick 發送，屬需另改 spec 的後續判斷，不能直接把未看見改判為 PASS。


## 本輪世界、操作界線與證據

證據共同根：`.superpowers/sdd/2026-10-01-m5-collapse-passage/manual-smoke-20261024/`（下列檔名皆相對此根）。`controller-manual-evidence-index.json` 收錄70個主要原件／畫面、25,751,089 bytes，SHA-256 `afb40a6c907144a31667f1c30d8fd4940b88ca74862d33d0b6cf08f25783f3f6`；主控逐檔重算。這是證據索引，不是世界備份；原件隨指定 M5 worktree 退役依使用者裁示刪除，結果與關鍵識別留在本清單及 note。

- 新單人世界 `run/client-base/saves/QC-M5-20261024-A`，seed `20261024`；原 `client-base/saves` 為空。C1 controller `(3,166,20)`，來源金塊標記 `(9,161,23)`。
- 舊 DORMANT 原件只讀；複本 `QC-M5-WM4-r1` 的60檔／32,867,934 bytes逐檔同 hash（`wm4-copy-manifest.json`）。多人世界 `run/server/QC-M5-MP` 從正常停止的複本另複製，75檔／37,286,477 bytes逐檔同 hash（`mp-world-copy.json`）。
- C2 controller `(23,-55,20)`；F1–F3先斷電，放石頭 `(25,-60,21)`／`(24,-60,21)`，再供電確認 IDLE／輸出3。東向新艙外框 `(80,-61,40)..(86,-55,46)`、controller `(86,-55,43)`。
- 走路省時定位使用遊戲內 `/tp`，每次明列為佈置；正式跨越均實際按 W。C／D及最後 NEW 碰撞補拍使用原版 `custom_effects` 長效量子態藥水，實際飲用；只延長前置操作時間，通道仍1200 ticks。F1保持原180秒藥水。未使用 tick freeze、未修改收據。F3為看清裝飾加夜視；光影 D 前已清除 OBS 夜視。目的宇宙返回來源的指令只作下一項佈置，沒有當成正規回程。

### A1：NEW 與最後碰撞補拍

普通世界採計 session `56e42668-3ca8-496d-b36a-1b31f221fee4`：09:50:28 PREPARE22 ticks、FIRST_WRITE218格，READY opened25876/expires27076，比較器15；09:50:40真 W 跨越到 `a1bb581a-89c0-8a9e-a043-5c7a0ee16974`。cross log 與到達讀值同 `(3.5,161,20.257569071866254)`。`095043-QC_A-a1-r3-after25.png` 顯示載入後 NEW title；來源金塊仍在、目的同座標air、同seed原始森林地形已核。投影 controller 為 PROJECTION／INVALID／無原艙UUID，實際供電喝藥仍不啟動（`102916-QC_A-a1-r3-powered-projection-inert.png`）。`a1-r3-final-journal.json` 為schema4／Records0；原門普通開門、1/1 checked終局。原件 `a1-r3-through-projection.log`。

NEW 封閉碰撞另補單一參與者的 dedicated 輪次 `5d2a32d8-802a-4953-8fb3-daa29a8214ab`：入場前 survival、位置 `(23.5,-60,25.69)`、yaw180/pitch0；原位朝向保留，返還避開兩石頭。`a1-new-sealed-r2-candidates.json`／原journal確認 station1 NEGATIVE_LATERAL、NEW CandidateId `1683edb2cf2361059e49654e526b510814818538f70059cfc8654be26319911c`。14:34:26起連續 W70ms與20張原始圖；`143427-QC_A-a1-new-r2-frame-08.png`、`…frame-09.png`均為sealed_x且位置z21.300，frame09膜清楚可見；frame10變passable_x才前進到z21.202。PREPARE7 ticks，14:34:28跨到新U `7286d79d-c707-8929-bfa2-ee02fc7fd988`，1/1正常終局。原件 `a1-new-sealed-r2-complete-client-base.log`／`…server.log`；沒有以 EXISTING 的碰撞替代 NEW。

### A2／B：原收據續行與到期

`wm4-readback.json`／`wm4-blocks-before.json`先核三筆schema3 MEASURED/SELECTED與供電：controller x=3/23/43，拉桿false/true/true，全部NEW。C3沿原session `4e00375b-57dd-44f4-a11f-11f4afa2e340`在登入時續行，目的U `c2234adb-c609-8839-ad3e-ffa17ca6bcda`；11:05:50→11:06:50，opened28436/expires29636，0/1到期。C2沿原session `20571672-6da2-4a6b-98f1-e7e894f6a8b3`，目的U `bf64212d-3bba-8a6c-a7c7-67d4b3f3f824`；11:09:01→11:10:01，opened32260/expires33460。C2圖 `b-c2-a2-ready`／`b-c2-a2-after-expiry`顯示膜15→普通開門3與關閉提示；人仍在原世界。

`b-final-readback.json`逐筆核allocationToken與原選定值相同，schema4剩唯一未供電C1原exact收據；兩個採計record已移除。terminal journal SHA `0ed080ecad3aaf7aa643114fa853318e192f70f3b5dc1428149dd8d6929f75a6`，allocations SHA `2fce6805510022bc941c906f206cc576b1f2be8aef9348ef340bc23a451306f4`。原件 `b-complete-runtime.log`；沒有重抽、沒有改備份原件。

### A1b／C／D：既有宇宙、分流與光影

C2採計session `4c4c2597-7703-45f7-aede-4d79f8b41abe`，checked station2 NEGATIVE_LATERAL為EXISTING（CandidateId `975fd378584dd1f51618dfcce2cab62afd9c69591ad090bf3fecf85e3031d4f6`），指向原有U `c2234adb-c609-8839-ad3e-ffa17ca6bcda`。12:58:30 READY opened147138/expires148338；OBS12:59:14真跨越，log／到達皆 `(22.5,-60,20.24908955079098)`，`c2-cross21`清楚顯示「你已進入另一個宇宙。」。北向F3為passable_x，12:59:30到期1/2，A仍overworld。這輪未及時拍到A的短暫關閉提示，未用事後聊天或log冒稱看到，由下輪補齊。

東向採計session `f661bb6f-27e4-482d-a1ee-262f40f8b6fd`，checked station3 NEGATIVE_LATERAL為EXISTING（CandidateId `522f6a5be9009819a4963e40c79bb834a8d58b0fd5be0ff7676c86741365fd9d`），同U；sealed當下journal SHA `38f9e8669207398707f07ea99a433a492546fc2506c4c709f88d2941bd07a4c5`。第三名非旁觀玩家站目的新投影範圍內，使ProjectionWriter依法等待；13:55:58/59連續兩次W600ms皆停在 `(85.69999998807907,-60,43.5)`，F3為sealed_z。`d-r3-sealed-blocked1/2`、wide／angle皆已直接核圖。移開B後14:03:41 READY、比較器15／passable_z，OBS14:03:47真跨越，位置 `(86.7393285524347,-60,43.5)`。14:04:41到期1/2；同秒 `d-r3-left-close-message`顯示A在原世界與「量子通道已關閉。」；14:04:43 `d-r3-crossed-no-close`顯示OBS仍在U且沒有該句。`d-r3-release-transcript.txt`／terminal-observed保存有界取證時序，`d-r3-complete-*`保存三份原log。

D的北／東向閉門遠景與斜角圖各自保存為 `d-north-far-closed`／angle-closed、`d-east-far-closed`／angle-closed。實際stack：Sodium `0.6.13+mc1.21.1`、Iris `1.8.8+mc1.21.1`、ComplementaryReimagined `r5.9.3`，NVIDIA RTX4090／OpenGL `3.2.0 NVIDIA 610.88`。三份套件hash見 `render-files.json`，沿用已核支援1.21的既有檔案，未下載新套件。Iris缺少新版本biome變數的既有WARN與Sodium的NVIDIA workaround如實保留；未把其他GPU組合列為已驗。

### E：單人持續性

在普通世界A的U `a1bb581a…`記錄位置 `(3.5,161,23.5)`。正常存檔離開整合伺服器後重載：10:46:08登入、10:46:59確認同U；`e1-rejoin-f3.png`／dimension.png與`e1-stop-and-relogin.log`。整個舊Java PID20920正常退出、原Gradle結束exit0後，新Java PID2448於10:55:28登入，10:58:22/23確認同U與同位置（`105824-QC_A-e2-restarted-f3.png`、`e2-complete-before-b.log`）。其後為原始地形觀察而移到森林的座標不當作登入落點。

### F1–F3：帶裝飾的返還

- F1：session `406a9bf3-fdf7-463e-9b80-ad7b381896e2`，12:33:56入場、12:36:55自然到期；返還 `(23.5,-60,21.5)`，藥水仍2瓶／無效果。W移到z23.223，再真開門走到z16.319、輸出3。圖 `f1-returned-f3`／free-movement／left-chamber，原件 `f1-complete-client.log`／server.log。
- F2：session `9c54b65a-8c03-4436-b81e-dd130fe9b68c`，真Q丟鑽石5→4。12:40:20依原清單允許方式setblock拉桿OFF，玩家回 `(23.5,-60,21.5)`；同UUID `[-1731684456,1526547349,-2112022205,-1338455069]`的1顆鑽石到 `(23.5,-60,23.5)`。效果2751→2591 ticks，真開門走出／輸出0。圖 `f2-returned`／left-chamber，原件 `f2-complete-client-base.log`／server.log。
- F3：session `f61327b1-0402-41f9-b5ee-55f6758f8752`，兩人真喝藥，12:44:18同組入場；12:45:06 B真喝奶，B回 `(22.5,-60,21.5)`、A回 `(23.5,-60,21.5)`。B效果清空，A量子態3485→2485→1537 ticks持續遞減；兩人各自實際W移動。OBS為旁觀者，`f3-observer-returned`可見兩人站在石頭旁空位。Q丟物UUID `[-2016884150,874987989,-1731930523,423549281]`在B返還畫面可見，B移動後拾取；OBS查詢時地上已無item、A3顆/B1顆。F2先前被B撿到的鑽石已於F3前置被hotbar.0的藥水替換，B飲用前圖無鑽石，不把該舊物混算。原件 `f3-complete-*`四份log；不聲稱OBS拍到地上物品的NBT。

## 未採計紀錄與尚未完成的收尾

- A1前置r1艙外平台未建成（後讀該格為air）造成落下，並非證明QuantumState穿地板；r2未選門就自然到期，均保留原件。投影controller起初誤查是否bedrock，後依spec／ProjectionWriter更正為PROJECTION controller，再實測不可啟動；未修改production。
- 多人client初次使用Sky切窗時，QC_B／QC_OBS原生崩潰於同一 `glfw.dll+0x101ab`，原因未證實，不歸為已知偶發。兩份hs_err與啟動log保留；新label重啟後改用使用者明確授權的既有mc-drive，後續採計輪次完成。FOCUS_LOST均停止輸入，未把被遮住的Codex畫面當遊戲證據。
- C首輪／D早期輪次因藥效到期、失焦、移到另一邏輯頁後重新配置而未完成選門；原收據與log保留。一次在ARMING期間佈置定位使cohort改變，觸發保護性RETURNING；不是無根據歸類的偶發。唯讀NBT摘要的bytes序列化首敗另保留partial JSON與原bytes，改新label輸出hex，未改世界收據。細節及全部root入口見同根 `STATUS.md`。
- 獨立人工結果 review 已完成：原 spec／quality 均 C0/I1/M0，唯一 I-1 是 note／plan 的兩個舊現況句；e8ed90e 修正後，另外兩個全新 context 複審均 C0/I0/M0、I-1 關閉、Minor 0。四份原報告、hash 與 range 見 implementation note 的「人工 smoke：2026-10-24 實際操作與結果」。整分支 review 尚待；本清單不提前宣布 M5 可合併。最終本機full仍為F7的 `run-all-e32343e8bd034936aee50b9b3db48a1f`（乾淨6b116e6，END ALL exit0），已驗雙平台CI仍固定ec394ad／run40023563006；本輪純文件不重跑full，不冒稱734d72b或後續文件HEAD已另跑CI。
- 本輪dedicated server的exec stdin已關閉，無法透過該管道送stop；截至本次紀錄仍在運行，未kill daemon。正常停服／關閉測試client與其實際結果另記，不能提前寫已停服。
