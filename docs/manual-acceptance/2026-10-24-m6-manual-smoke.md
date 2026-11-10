# M6 Overworld 人工 smoke 清單

狀態：2026-11-10 最新裁示及唯一範圍窄審採用的待驗清單；尚未實玩，所有結果待填。人工代操作另需使用者明確授權；不以本清單當 client 啟動授權。

範圍：平行宇宙只有主世界，FamilyCapabilityVersion=0；共用原版 Nether／End。SHALLOW_V1 僅 id 層設定 fingerprint，same-seed／toggle 保證以兩端 datapack／mod 配置相同為前提。8-H 必要項與 waiver 待使用者依只有主世界重新裁定，全部舊 FAIL 不改判。

每輪記版本／code SHA、世界、操作者、時間、玩家／Universe UUID、world key、seed／profile、receipt／log 路徑與實際結果。只使用另獲授權的新測試世界，不動使用者世界。

| 輪次 | 操作 | 預期 | 實際結果／證據 |
| --- | --- | --- | --- |
| 單人 | 從艙選 NEW，依 receipt 辨認 random profile 與 seed | 進入不同 seed 的 U 主世界，UUID／key 精確 | 未執行 |
| 單人 | 重進同一 Universe，正常存退並重啟登入 | 不重抽，仍同 UUID／key／seed 的 U | 未執行 |
| 多人 | 兩玩家走正式 NEW／跟隨與重進流程，存退重啟 | 各自保存正確位置與同一 U 權威，不串 U | 未執行 |
| 共用維度 | 從 U 經地獄門到原版 Nether 再回程；另驗 End 出口／credits | 共用原版 Nether／End，回原版主世界，不回出發 U；與死亡分開記 | 未執行 |
| 宇宙內重生加一輪 | U 內設床後死亡；再驗重啟時 U 尚未載入 | 在同 U 有效床邊，維持 UUID／key | 未執行 |
| 宇宙內重生加一輪 | 拆床／無有效床，在 U 死亡 | 留同 U，取原版主世界 spawn 同 block XZ 的安全 Y | 未執行 |
| 宇宙內重生加一輪 | 受控 U 不可用；另驗 canonical／共用 Nether／End 死亡 | U 不可用退 canonical 並有原因記錄；其他維度死亡照原版 | 未執行 |

安全列與 U 不可用控制只在受控驗收世界做，不改正式使用者存檔。若 End 出口／credits 因 U 床返回 U 或其他行為不符，保存原件並回報；不擅新增 portal 注入點。main 合併、tag、人工代操作各自另問使用者。
