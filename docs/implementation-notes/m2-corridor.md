# M2 供電走廊：操作與驗證

`feature/m1-chamber` 已實作真入場、有限走廊換頁與安全返還。單人操作與視覺效果仍待人工驗收；自動原生測試不等於已用滑鼠完成全部玩法。此分支尚未推送／合併 main，M3 跨宇宙通道未實作。

## 從正確的功能工作區啟動

本機完整路徑是 `C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber`，分支為 `feature/m1-chamber`。主目錄的 main 較舊；請先正常儲存並退出舊遊戲，再在此工作區啟動：

```powershell
Set-Location 'C:\Users\Ben\Documents\minecraft QuantumChamber\.worktrees\m1-chamber'
.\start-client.bat
# 選用手持照明的獨立 profile
.\start-client.bat light
```

參數是 `light`，不是 `--light`。一般 profile 使用 `run/client-base/saves`，照明 profile 使用 `run/client-light/saves`；腳本不會複製或搬移存檔。請先在新創造模式測試世界操作。若需複製既有世界，先退出所有遊戲、人工完整備份到遊戲目錄外，再人工複製到另一 profile；保留原本資料夾，不要覆蓋或同時開啟同一世界。

## 單人無遊戲指令流程

沿用 [逐格施工指引](m1-player-build-verification.md) 的三種材料：192 基岩、25 量子艙門、1 腔室控制器。外側 7×7×7、內側 5×5×5、控制器在正面頂排中央；沿用 [SVG](../images/m1-chamber-build-guide.svg)／[PNG](../images/m1-chamber-build-guide.png) 核對格數，圖中舊版狀態與維護文字不作本版玩法依據。

1. 未供電時先完成艙體、門控與外部紅石裝置，準備量子態藥水。釀造仍是水瓶→地獄疙瘩→回聲碎片；火把照明不能替代 QuantumState。
2. 在艙外先供電。可用控制器上方、位於艙體外的拉桿；空艙或門開時也會先登錄 UUID 並保護艙體。
3. 單人進入走廊後不能留在原世界操作外部拉桿，因此入艙前必須安排會自行斷電的外部計時電路，並確認沒有另一支拉桿／紅石方塊持續供電。先以紅石燈測試「足夠時間的高電位→低電位」，再接到控制器；實際電路與倒數時間列為人工待驗。
4. 普通右鍵開門，完整走進 5×5×5 室內，再瞄準前牆上方控制器關門。開啟的門格沒有可瞄準外形，請操作控制器。
5. 已有 QuantumState 可直接等待；否則在室內喝藥。關門、完整碰撞箱在室內、全員有效且非旁觀者後自動啟動，無須再次撥桿。走廊準備與原生保存需要時間；成功後才一次消耗全員入場效果。
6. 確認已進入真延伸走廊，再向前走、回頭並觀察底板／碰撞。外部電路到時斷電後，應安全回到同一座原艙的原世界；所有參與者與有價物品處理完成後才結束 session、清理空間並解除原艙保護。
7. 原艙回到 OFF 後仍是一座保留 UUID 的普通盒子，可重新供電；要拆除時以創造模式實際確認可拆，再移除控制器與材料。不要以效果消失、比較器輸出、管理停用或某位玩家先返回，推斷整個 session 已完成。

可選的單人計時器做法是外部漏斗接箱子，以比較器讀漏斗、再用中繼器將輸出接到控制器。先用外部拉桿鎖住漏斗並放入物品；此時漏斗比較器已供電。解除漏斗鎖定後開始流出物品，清空才斷電。這支拉桿控制的是漏斗鎖定，供電來源是比較器；不要另留直接供電的拉桿。物品數、線路與可用入艙時間先在空艙旁量測，不能把這份施工說明當成電路已人工通過。

## 狀態、安全與恢復界線

- `ARMING`／`SUPERPOSITION`／`RETURNING` 是 session 狀態；原艙比較器仍用 `INVALID=0`、`IDLE=3`、`READY=7`、`ARMED=11`。等待返還也可能維持 11，所以 11 不是「已安全返還」的證明。
- 入場前的 durable ARMING 保留完整效果快照，未提交入場時返還會還原；一旦 durable SUPERPOSITION 已提交 false，返還保留玩家當前效果，不補發入場藥效。同 session 的原生玩家 checkpoint 防止重啟再套用快照。
- 重啟後只處理 journal 裡仍 pending 的完整 cohort，不重建舊活動 session。JOIN 先排隊、下一 server tick 才返還；離線玩家尚未回來，原艙與租約仍保持保護。
- 原控制器 UUID／ORIGIN／原世界／朝向／registry 必須可信；缺來源不送去床、出生點或另一座艙。無法完成返還時保留資料與保護，應保存現場檢查，不刪世界來掩蓋問題。
- 固定世界只有 `quantumchamber:superposition`，不建立 Universe 或動態 Dimension。每邏輯頁 96 格、兩端各 576 格外觀延伸；局部實例有限，並非無限物化世界。
- 方塊建造／清理預算每 tick 4096，ticket 覆蓋聯集上限另外是 4096 chunks，兩者不同。64 實例上限不代表同時跑滿 64 個的硬體效能已通過；容量不足採安全返還。
- 玩家 checkpoint 的成功 runtime 證據限已驗 Windows／NTFS／Java21 原生 HANDLE 條件。非 Windows 目前 fail closed；Ubuntu 建置契約、OS 條件略過與真正 Linux 恢復成功需分別看待。正常 stop、受控 JVM 中止與整機斷電也不是同一種保證。

## 自動證據與人工 gate

正常持久化探針使用明確 `quantumchamber.m2.phase`，預設停用；只在 fresh `run/m2-persistence` 的各自案例中，依序以不同 JVM 正常保存、退出及重登。`active-pending`、`return-disconnect`、`arming-rollback`、`origin-missing` 分別驗證離線 cohort、先斷電後斷線、true 政策跨兩次重啟、缺來源安全拒絕。測試用連線為原生玩家／Netty EmbeddedChannel 的真 JOIN／DISCONNECT，尚不等於遠端真人客戶端驗收。

active-save 的穩定保存窗口使用明確 testmod 返還拒絕；真入場／效果消耗／B 斷線與原生保存均仍執行。下一 JVM 關閉該故障，以正式 entity region 正常 load 同 UUID 的五顆鑽石，未 FULL／entityLoaded／ticking 前不釋租約，返還後才允許完整收尾。manifest 只有識別字與期望，不回填物品或玩家 NBT。

部分加票的 bootstrap 案例另使用合法 trusted journal，在第一票成功後拒絕第二票；核對原生票集合中目標 SID 零殘票、另一 SID 同區塊票保留，並驗 tick0、原 journal hash 不變。這與原六個加票前的拒啟案例分列。

Task4 最後 W1 true／keep、W2 與 stale-save 證據保留。Task5 發現啟動加票失敗後，尚未 attach 的 session manager 會再於 STOPPING 拋錯，已窄修關閉／detach 流程；四條 checkpoint 鏈已以最後 classes 重新驗證。whole-feature final review 尚待 root 獨立執行。

2026-09-18 本機 Windows 最後驗證：一次非快取 `clean build runGameTest --rerun-tasks`，167 個 JUnit 中166通過、1個非Windows專用案例依OS略過；105個GameTests全部通過。最後classes另完成29個原生JVM驗證（含預期拒啟與受控中止），正常phase皆由console stop後保存四world並exit0；十份正常停止後formal資料及四組checkpoint正式NBT另經唯讀讀回。五鑽石保持同一UUID、數量5與唯一實體，先留fixed、再回同一原艙；ARMING與已提交入場的效果政策保持分離。

發行JAR為282790 bytes，SHA256 `769FA9215C15622A951CD22CCDCE4927E702C334C59497BDC726891BC8C2D6DF`；testmod compiled classes與成品交集0、沒有test mixin metadata，main classes對client-only類別直接引用0。正式既有 `ModPresenceProbe` 是相容性介面，與testmod持久化探針不同。production-only dedicated已驗Done→console stop→四world save→Java／wrapper0，無testmod、client mods或client-loading error。

唯讀照明依賴解析及SHA512核對通過，未啟動客戶端或複製存檔。編譯保留既有deprecated訊息與testmod三target注入的6個annotation warnings，不宣稱輸出零警告。移動RuntimeException診斷細節與此warning整理列為非阻擋Minor；真Ubuntu runtime與下表人工項目仍未驗。

| 人工項目 | 狀態 |
| --- | --- |
| 單人先供電→入艙關門→已有 buff／喝藥→真走廊 | 待驗 |
| 外部計時斷電→回同一原艙→OFF 後 Creative 可拆 | 待驗 |
| 主手／副手火把、移動後照明與收起後熄滅 | 待驗 |
| 32 chunk 遠望、向前與回頭的外觀／底板／碰撞 | 待驗 |
| 近玩家同群換頁、分離再重聚的 seam | 待驗 |
| shader 開關、資源重載、GPU／日夜相容性 | 待驗 |

LambDynamicLights 為選用 client-only 模組，解析及 SHA512 核對不代表已觀察到手持照明；不更改伺服器世界光照，也不加入預設 client-base、dedicated 或發行 JAR。任意 shader、擴大視距、實體數量與硬體負載未獲無縫或效能保證。
