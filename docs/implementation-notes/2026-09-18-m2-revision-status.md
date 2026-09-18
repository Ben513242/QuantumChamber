# M2 遠端審查快照與待實作修訂

日期：2026-09-18。功能分支：`feature/m1-chamber`。

使用者明確授權先推送目前狀態供同步審查；這不是 main 合併或人工驗收通過的授權。未新增 LICENSE、刪除世界或修改既有玩家存檔。

## 這次快照實際包含

- M1／M1.1／M1.2，以及原版 M2 固定 `quantumchamber:superposition` 走廊、共享參與者、原生入場／返還與恢復資料。
- Task5 的正常多 JVM restart／真 disconnect／正式玩家與有價物品讀回探針，以及部分 ticket acquire 故障測試；測試模組預設關閉、不進 release JAR。
- 唯一 Task5 main 修補：SuperpositionSessionManager 未完成 attach 時的 stopping／detach 安全清理，不放寬正常 API guard。
- 原契約最後自動驗證：166 JVM tests 通過、1 非 Windows 契約測試因本機 Windows 略過；105 GameTests 零失敗／錯誤／略過；29 個原生 server JVM gates 與14組唯讀正式資料查核完成。
- release JAR SHA256：`769FA9215C15622A951CD22CCDCE4927E702C334C59497BDC726891BC8C2D6DF`。全部 testmod class 與 release JAR、25個 testmod Java files 與 sources JAR 的交集為零；main 不直接引用 client-only 類別。

上述測試證明原方向與原藥效契約，不代表下列新玩法已完成；Task5 獨立評審、整分支 final review 與人工 checklist 仍未結案。真正 Linux runtime／遠端 CI／GPU與照明不由本機測試代替。

## 最新已核准、尚未實作的 M2 修訂

1. 以原艙門為基準，走廊向左右延伸，而非目前的前後軸。
2. 喝藥依原生規則一次消耗藥瓶；成功入場不再移除 QuantumState Buff。創造模式物品消耗仍遵循原生行為，不額外改背包規則。
3. Buff 持續維持疊加態；任一凍結參與者的 Buff 到期或被解除，就讓整組安全回到同一原艙。
4. 若紅石仍供電，返還後原艙保持保護。全員再次有 Buff、關門且其他條件成立時，可再次出現走廊，不要求新的紅石 edge。
5. 外部斷電仍是先安全返還／收尾，再解除原艙保護；離線／錯來源／返還失敗者保留 pending 與必要空間。
6. 新方向與藥效契約需有明確的舊 journal 相容策略；不自動刪除、轉置或猜測舊世界資料。

目前原版程式仍會在成功入場移除 Buff，且不依後續藥效倒數結束 session。README／原版 implementation note 中的已實作描述應按這個快照理解，不當成新版已完成。

## 仍不在本次 M2 範圍

玩家選候選門→測量立即塌縮→有限艙體→選定 Universe passage，需後續 M3／M4；目前側門只有門面與邏輯身分。沒有 UniverseRegistry、runtime Universe allocation、候選世界選擇或跨宇宙通道；不能假開門來冒充完成。

人工最新狀態：尚未全部測完。未合併 main。
