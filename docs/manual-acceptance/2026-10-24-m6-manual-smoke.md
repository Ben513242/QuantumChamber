# M6 Overworld 人工 smoke 清單

狀態：2026-11-10 Task R 開工前修正裁示之待驗清單，本次唯一窄審 C0／I0／M0 已採用；尚未實玩，所有結果待填。人工代操作另需使用者明確授權；不以本清單當 client 啟動授權。

範圍：平行宇宙只有主世界，FamilyCapabilityVersion=0；共用原版 Nether／End。SHALLOW_V1 僅 id 層設定 fingerprint，same-seed／toggle 保證以兩端 datapack／mod 配置相同為前提。8-H 必要項與 waiver 待使用者依只有主世界重新裁定，全部舊 FAIL 不改判。

每輪記版本／code SHA、世界、操作者、時間、玩家／Universe UUID、world key、seed／profile、receipt／log 路徑與實際結果。只使用另獲授權的新測試世界，不動使用者世界。

| 輪次 | 操作 | 預期 | 實際結果／證據 |
| --- | --- | --- | --- |
| 單人 | 從艙選 NEW，依 receipt 辨認 random profile 與 seed | 進入不同 seed 的 U 主世界，UUID／key 精確 | 未執行 |
| 單人 | 重進同一 Universe，正常存退並重啟登入 | 不重抽，仍同 UUID／key／seed 的 U | 未執行 |
| 多人 | 兩玩家走正式 NEW／跟隨與重進流程，存退重啟 | 各自保存正確位置與同一 U 權威，不串 U | 未執行 |
| 共用維度 | 從 U 經地獄門到原版 Nether 再回程；另驗 End 出口／credits | 地獄門回程一律落在原版主世界；終界出口照原版看床，床在 U 回 U、沒床回原版主世界；與死亡分開記 | 未執行 |
| 宇宙內重生加一輪 | U 內設床後死亡；正常存退重啟後再驗 | 既有 EAGER_ENABLED bootstrap 後同 U 床有效、登入仍同 UUID／key；不新增登入物化 | 未執行 |
| 宇宙內重生加一輪 | 有效床／錨位於另一 U 或 canonical；在 U 死亡 | 床／錨依原版優先，不另判合法性、不改錨消耗 | 未執行 |
| 宇宙內重生加一輪 | 拆床／無有效床，在 exact live U 主世界死亡 | 僅原版 canonical fallback 改留同 U，取原版主世界 spawn 同 block XZ 安全 Y；無解沿原版並記原因 | 未執行 |
| 宇宙內重生加一輪 | 受控 U／安全落點不可用、DISABLED 重啟登入；另驗 canonical／共用 Nether／End 死亡 | 無安全落點保原版 fallback 並記原因；DISABLED 不在登入物化、保持既有行為；其他維度死亡照原版 | 未執行 |

安全列與 U 不可用控制只在受控驗收世界做，不改正式使用者存檔。End 出口／credits 因有效 U 床返回 U 是預期行為；與預期不符才保存原件並回報，不新增 portal 注入點。8-H 另含 Windows default AABB／走廊容量的 testmod 自身清理或隔離 batch 候選，待使用者裁示、未實作；不把 4096 根因當已證。最終 full 必自然 END ALL exit=0，同 pin 最多重跑一次、第二次停報，原 case／期待／timeout／production 容量與 old FAIL 均保留。main 合併、tag、人工代操作各自另問使用者。
