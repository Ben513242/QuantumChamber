# M2 修訂實作、驗證狀態與 M3 交接

原修訂日期：2026-09-18；本機最終驗證更新：2026-09-19。功能分支：`feature/m1-chamber`。

使用者明確授權先推送目前狀態供同步審查；這不是 main 合併或人工驗收通過的授權。未新增 LICENSE、刪除世界或修改既有玩家存檔。

## 原版歷史快照（不代表新版最終結果）

- M1／M1.1／M1.2，以及原版 M2 固定 `quantumchamber:superposition` 走廊、共享參與者、原生入場／返還與恢復資料。
- Task5 的正常多 JVM restart／真 disconnect／正式玩家與有價物品讀回探針，以及部分 ticket acquire 故障測試；測試模組預設關閉、不進 release JAR。
- 唯一 Task5 main 修補：SuperpositionSessionManager 未完成 attach 時的 stopping／detach 安全清理，不放寬正常 API guard。
- 原契約最後自動驗證：166 JVM tests 通過、1 非 Windows 契約測試因本機 Windows 略過；105 GameTests 零失敗／錯誤／略過；29 個原生 server JVM gates 與14組唯讀正式資料查核完成。
- release JAR SHA256：`769FA9215C15622A951CD22CCDCE4927E702C334C59497BDC726891BC8C2D6DF`。全部 testmod class 與 release JAR、25個 testmod Java files 與 sources JAR 的交集為零；main 不直接引用 client-only 類別。

上述數字與JAR只代表原方向／耗藥契約的歷史快照，不替代新版 gate。Task5既有獨立review已處理，其文件狀態Minor在Task10同步；一次whole-branch final review與人工8項依使用者安排留整體收尾。Linux成功原生checkpoint、GPU與照明仍不可由本機headless測試推定。

## 已實作的新 M2 契約

1. 以原艙門為基準，走廊向左右延伸；sourceFacing與corridorFacing獨立，原材料、7³／5³、25門與青紫外觀不變。
2. 喝藥依原生規則一次消耗藥瓶；成功入場不再移除 QuantumState Buff。創造模式物品消耗仍遵循原生行為，不額外改背包規則。
3. Buff 持續維持疊加態；任一凍結參與者的 Buff 到期或被解除，就讓整組安全回到同一原艙。
4. 若紅石仍供電，返還後原艙保持保護。全員再次有 Buff、關門且其他條件成立時，可再次出現走廊，不要求新的紅石 edge。
5. 外部斷電仍是先安全返還／收尾，再解除原艙保護；離線／錯來源／返還失敗者保留 pending 與必要空間。
6. 新native為schema2的LATERAL_BUFF_MAINTAINED／ARMING,false，始終KEEP_CURRENT；schema1只按完整legacy return-only契約讀回，原bounds不旋轉，不冒充新入場。

Task9最後code SHA `bdb74027206febdb332fbf8c098d9fc2d96bf53f` 的 [兩平台CI run 35351777308](https://github.com/Ben513242/QuantumChamber/actions/runs/35351777308) 已成功：Windows必要12項零skip、GameTest116項零失敗；Ubuntu合法OS契約與GameTest116項成功。Windowsguard真實執行，Task9獨立review已結案。後置純文件BASE `681cdc41586797827ad984b43c77fef1eabfedf9` 不改該source。

Task10本機必要自動gate已通過：一次最後非快取 `clean build m2ExportRuntime runGameTest`（另含官方照明唯讀SHA工作），19工作全執行；183個JUnit中182通過、1個NonWindows專用測試依 `@DisabledOnOs(OS.WINDOWS)` 合法略過，Windows必要12項零略過；fresh GameTest116項零failure/error/skip。

最後source/classes完成36個跨JVM phase：新active／ARMING中斷／milk與expiry離線／缺來源／unknown拒絕、四組schema1 legacy正常恢復與二次重啟、新W1 KEEP_CURRENT／W2 ARMINGfalse／stale、舊RESTORE四JVM真中斷鏈。31組原生NBT正式讀回成功；313份source/class指紋×36phase＝11,268次比較均對上最後clean產物。Windows重用兩個數字PID，因此36次啟動是34個PID值、36個不同StartTime／startup nonce，未以PID重找中止目標。另驗partial-ticket例外對稱清理，以及main-only新世界Done→正常stop→四world save，Java／wrapper0。

發行JAR為291,559 bytes／SHA256 `1B542A17990B25342CF30E376A06297CAE17991D25797ED48DBA72393C08A401`；sources JAR為129,394 bytes／SHA256 `6AE4C3495336BC0CCABCF1657D439041C4F4F59DB016D066CCD632AEB5895D2F`。全部41個testmod class／25個testmod source與雙JAR交集、測試／照明污染、main對client-only直接引用均0。官方固定照明依賴SHA512符合鎖定值，沒有啟動client／GPU或搬移存檔。

上述為本機gate完成；Task10精確提交的獨立review與新code遠端CI仍待root關閉，不能把Task9舊SHA的CI當成本次新SHA結果。初輪資料與唯一末次資料分列保留，不混用classes。

## M3 開始條件與整體收尾

M3 spike須等新版M2自動gate、Task10獨立review與必要新code遠端CI全綠；這份文件只交接，尚未執行M3。八項單人／多人指引已列於 [M2操作文件](m2-corridor.md#八項人工驗收)，皆待人工：真飲用／HUD、單人到期、多人失效與離線、HIGH再入／LOW、32chunks遠望、96格近遠seam、Iris／shader／resource reload、主副手火把。原生particles不是bloom；照明只屬選用client profile。

原6個compiler warnings、deprecated notes、正常expiry WARN保留；Task9原31項runtime noise與TransferService診斷等production Minor留final triage，不宣稱已清空。whole-branch final只在整體收尾另做一次。使用者目前授權M2feature push，不代表main merge、刪除世界或新增LICENSE。

## 仍不在本次 M2 範圍

玩家選候選門→測量立即塌縮→有限艙體→選定 Universe passage，需後續 M3／M4；目前側門只有門面與邏輯身分。沒有 UniverseRegistry、runtime Universe allocation、候選世界選擇或跨宇宙通道；不能假開門來冒充完成。

人工最新狀態：尚未全部測完。未合併 main。
