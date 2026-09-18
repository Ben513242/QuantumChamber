# M2 供電走廊：操作與驗證

功能分支 `feature/m1-chamber` 已實作以原艙門為基準左右延伸、入場保留 Buff、任一凍結參與者 Buff 失效則全組安全返還。自動驗證與人工觀察分開記錄；以下八項目前皆待人工驗收。尚未合併 main，M3 尚未執行。

## 從功能工作區啟動

先正常儲存並退出舊客戶端，再從 `C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber` 執行 `start-client.bat`；選用照明為 `start-client.bat light`，不是 `--light`。

一般與照明 profile 分別使用 `run/client-base/saves`、`run/client-light/saves`；腳本不搬移世界。優先另建創造模式測試世界；如需複製舊世界，先退出遊戲並人工完整備份，不覆蓋原檔或同時開同一世界。

## 單人無遊戲指令流程

施工沿用 [逐格指引](m1-player-build-verification.md)：192 基岩、25 量子艙門、1 腔室控制器；外側7³、內部5³、控制器 local `(3,6,0)`。青色／紫色是現有方塊外觀，不增加第四種建材。舊施工圖僅供幾何參考。

1. 未供電時完成艙體與外部裝置；釀造水瓶→地獄疙瘩→回聲碎片的量子態藥水。火把不能代替 QuantumState。
2. 在艙外先供電。有效空艙或門開時也可先取得 UUID 並保護艙體。
3. 普通右鍵開門，完整進入室內；瞄準前牆頂排中央 Controller 關門。開門後門格沒有可瞄準外形，應操作 Controller。
4. 真正飲用藥水並觀察 HUD。全員有效、非旁觀者、完整碰撞箱在室內且關門時自動準備走廊，不要求新紅石 edge。入場保留現有效果、duration 與 hidden chain，自然倒數；不額外消耗 Buff，也不改原生喝藥的物品規則。
5. 走廊相對原艙門朝左右延伸，`sourceFacing` 與 `corridorFacing` 分開。先看左、右、回頭，再行走檢查底板／碰撞；側門目前只有門面與邏輯身分。
6. 任何一位凍結參與者的 Buff 自然到期或喝奶解除，全組安全返回同一原艙，保留返還當下效果，沒有入場藥效退款。HIGH 仍保護原艙；全員再次補喝、關門並滿足資格後可建立新 SID。
7. 要測 LOW 或拆除，回到原艙後從外部切斷供電；或在入艙前預先安排並量測外部計時斷電。待完整 cohort／有價物品返還與清理完成、原艙確認 OFF 後，才以 Creative 實際確認可拆。計時器是 LOW 案例的選用安排，Buff 返還不需要它。

多人案例至少兩位室內玩家；如需觀察外部比較器，另安排觀察者。帳號、GPU 或觀察條件不足時保留待驗，不用自動測試代填。

## 八項人工驗收

每項記錄版本／場景／單人或多人／實際結果與截圖；以下狀態統一為「待驗」，沒有從 headless 測試推定通過。

| 項目 | 操作與預期 | 狀態 |
| --- | --- | --- |
| 1. 單人原生飲用與左右入口 | 外部 HIGH→完整入艙→關門→真喝藥；原生瓶子消耗正確、真進左右廊、HUD Buff 仍在且倒數不中斷 | 待驗 |
| 2. 單人自然到期 | 不續杯，觀察 HUD 自然到期；回同一原世界／原艙，不退款效果；原艙 HIGH 仍受保護 | 待驗 |
| 3. 多人共享效果與離線 | 兩人進同 session，任一位到期／喝奶即全組返還；另一位途中斷線，下次 JOIN 才完成，不丟 cohort／物品 | 待驗 |
| 4. HIGH 再入場與 LOW 收尾 | HIGH 全員補喝且關門建立新 SID；另測外部 LOW，安全返還→清理→OFF 後 Creative 可拆 | 待驗 |
| 5. 32 chunks／512 blocks 視距 | 32 chunk 設定下往兩側遠望、行走與回頭；記錄可見端部、底板及碰撞，不把有限實例稱為無限物化 | 待驗 |
| 6. 多人96格頁面 seam | 近玩家同群越96格邊界；再拉遠、分離、重聚，核對接縫、實體與碰撞，不能只測單人 | 待驗 |
| 7. 光影與資源重載 | Iris／shader 開關、資源重載、日夜與GPU組合；原生 particles 不是 bloom 或新增真光源 | 待驗 |
| 8. 選用手持照明 | `light` 新世界主手／副手火把、移動、收起熄滅；與client-base對照，不混入dedicated或伺服器世界光照 | 待驗 |

## 持久化與安全界線

- 新 session 為 `LATERAL_BUFF_MAINTAINED`，從 durable `ARMING,false` 起即 KEEP_CURRENT；ARMING 中斷亦不退款藥效。schema2 凍結來源、參與者與模式，不能改同一 SID 的語意。
- 舊 schema1 只按 `LEGACY_FORWARD_CONSUMED` return-only 讀取；完整原 bounds 不旋轉。舊 ARMING,true／RETURNING,true 可 RESTORE_ENTRY，已保存同 SID／policy marker 則不重套；舊 SUPERPOSITION,false／RETURNING,false 保留當前效果。這不是新版 native entry。
- 重啟只恢復 pending 返還，不重建舊 ACTIVE。JOIN 先排隊、下一 server tick 返回；離線者仍在凍結名單，必要租約與來源保護繼續持有。
- 原 Controller UUID／ORIGIN／世界／朝向／registry 必須可信。缺來源不送床、spawn 或另一座艙；保留現場，不刪世界掩蓋失敗。
- Comparator仍為 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。11不是返還完成證明，管理停用也不是 LOW 或解除保護。
- 固定世界僅 `quantumchamber:superposition`；每邏輯頁96格、外觀延伸兩端各576格，沒有動態 Universe。每 tick 方塊建造／清理4096格與ticket聯集4096chunks是不同預算；64實例上限不是滿載性能保證。
- 成功原生玩家 checkpoint 證據限 Windows／NTFS／Java21 HANDLE 條件。Ubuntu 的合法拒絕及OS skips不等於 Linux native恢復成功。正常stop、受控JVM crash與整機斷電各自不同，不聲稱跨檔原子性。

## 自動證據狀態與M3交接

Task9 精確 code SHA `bdb74027206febdb332fbf8c098d9fc2d96bf53f` 的兩平台CI run `35351777308` 成功：Windows 必要12項零跳過，兩平台GameTest各116項成功；後置純文件BASE為 `681cdc41586797827ad984b43c77fef1eabfedf9`。Task9獨立review已結案。

Task10 使用 default-off probe、fresh canonical nonce root、不同JVM和正式 playerdata／compressed journal／entity-region讀回，分別驗新原生來源與明確trusted legacy來源。manifest僅識別字，不回填效果、pose或inventory。EmbeddedChannel的原生效果ticks與server ticks分列，不冒充真人連線或HUD證據。Task10完整gate與獨立review狀態以 [修訂狀態](2026-09-18-m2-revision-status.md) 為準。

2026-09-19 本機末次非快取驗證：183個JUnit中182通過、1個合法NonWindows案例略過，Windows必要12項零略過；116個GameTest零failure/error/skip。36個原生phase與31組正式NBT讀回均成功，全部source/class指紋對上最後clean classes；同UUID五鑽石從fixed正式region讀回原艙。新KEEP_CURRENT／ARMINGfalse與舊RESTORE_ENTRY的精確crash窗口分別通過，含第二次重啟不退款／不重套。另有partial-ticket對稱清理與不含testmod的main-only四世界Done→stop→save。這些本機結果不取代Task10獨立review與新SHA遠端CI。

Task5 文件狀態Minor在本次修訂同步：舊166/105與29JVM屬原方向／耗藥契約歷史證據，不能代替新版；Task5既有獨立review狀態不再誤列未完成。6個Mixin annotation warnings、deprecated notes、正常expiry WARN與其餘runtime noise保留；TransferService診斷等production Minor交整體final triage，不在本task擴修。

M3前置：新版M2自動gate、Task10獨立review與必要新code遠端CI先綠，才開始M3 spike。本次只交接，沒有實作候選選擇／測量塌縮／family全量／跨Universe passage。八項人工與一次whole-branch final review依使用者安排留整體收尾；main不merge、不新增LICENSE，不清除既有世界或owner證據。

LambDynamicLights是選用client-only模組。官方來源SHA核對與依賴解析不等於手持照明、Iris或shader已實測；本自動gate未啟動GPU客戶端。
