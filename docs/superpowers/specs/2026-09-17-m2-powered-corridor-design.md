# M2：供電式延伸走廊與斷電安全返還設計

日期：2026-09-17。程式基準：`873b3356cedb8bfb76f330341d706cf8c8f1ff1b`，分支 `feature/m1-chamber`。

**狀態：2026-09-17 使用者已核准本規格與保守安全策略並授權實作。尚無 M2 程式，不宣告走廊、傳送或照明已可用。**

## 1. 前置與固定範圍

先完成並驗證 [M1.2 供電／原艙保護](2026-09-17-m1.2-powered-origin-design.md)，再接入本 M2，不把兩個里程碑混成單一大提交。

- Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、Java 21、Gradle Wrapper 8.8。
- 單一 Fabric module，common／client 分離；三種建材、7×7×7 外殼、5×5×5 interior 與青紫造型不變。
- 沿用原設計 §6.1、§19 的單一固定 `quantumchamber:superposition` 空間、相同入口 replica、有限頁面與 logical corridor。
- 無 `UniverseRegistry`、runtime dimension allocation、平行 Overworld／Nether／End family、Universe candidate selection、跨宇宙 passage 或 projection materialization。
- M2 允許原世界↔固定 Superposition Dimension 的直接入場／返還；這不是 M3 的新宇宙穿越。
- 不新增 mandatory renderer／portal／dimension library，不新增 `LICENSE`，不合併／推送 `main`，不刪使用者世界。

## 2. 單人成功畫面

```text
外部拉桿開啟 → 原艙受保護
普通右鍵開門 → 進入 → 普通右鍵關門
已有 QuantumState，或關門後喝下藥水
        ↓ 不需要第二次撥桿，也不用指令
相同入口房間內顯露長直走廊，左右有連續門面
玩家可以持火把向前走，跨頁面仍不遇到可見終點
        ↓ 外部電源被切斷
玩家回原始艙體 → 延伸空間結束 → 原艙才解除保護
```

內部延伸必須是真實伺服器 collision 與移動，不能以一張圖片、純 shader 幻覺或 ARMED 狀態字樣冒充。

## 3. 入場與共享 session

- 電源保持開啟、原艙 enabled／有效／sealed、至少一名完整位於 interior 的非 spectator 玩家、全員 QuantumState、無活動或返還中的 session。
- 成功開始時凍結完整 cohort，不能排除某位缺 buff 的室內玩家；零人不得開始，mobs 不自動成為參與者。
- 先保存恢復資料並確認入口 replica／必要 chunk／返還位置就緒，再直接轉移玩家。
- 全員成功提交進入 `SUPERPOSITION` 時，依原設計一次消耗全員 QuantumState；失敗或 rollback 不消耗，不留下半個 cohort。
- 入場後不因 buff 倒數結束立即取消；外部電源仍是整個 session 的持續必要條件。
- 一個艙體至多一個活動 session，一名玩家至多屬於一個；重複刷新、門控或 held-high 不建立重複 session。
- 入口 replica／走廊 slot 使用獨立 session 保護索引，核對真實 world／server 身分；不能沿用僅支援 vanilla role 的查詢，讓固定 Superposition Dimension 意外落到「未知 key 放行」。建造、回收與門控使用精確的 authorized mutation。
- 不使用 Nether portal block、overlay、nausea 或刻意動畫；首次版本容許 Minecraft 同步維度時短暫卡頓，不宣稱零幀無縫。

## 4. 有限實體、無限邏輯走廊

沿用原規格的分頁模型，不改成整個 cohort 共用單一有限視窗。

| 項目 | 開發預設 |
| --- | --- |
| interior 寬／高 | 5／5 格 |
| logical page 長度 | 96 格 |
| 門站間距 | 8 格 |
| 每站門面 | LEFT／RIGHT 各一 |
| physical page-slot 間距 | 至少 160 格 |
| 保留範圍 | 已占用頁面與相鄰一頁安全範圍 |

- 玩家邏輯位置、頁碼與實體 slot 分開；頁碼採 floor division，負向與往返都必須正確。
- 實作澄清：96 格是邏輯頁；slot 是有限局部 affine 實例，可包含多頁外觀 alias。近玩家共享同一映射、遠群體使用獨立實例，不是整個 cohort 一個有限視窗。slot 間距仍至少160且完整AABB不可相交；邏輯occupied±1之外的576格有限apron只呈現重複幾何，不配發候選／Universe。這項控制器技術決定與成本記於本M2計畫ledger。
- 跨頁只在同一固定 Superposition Dimension 重新定位，保留朝向與安全速度；正常步行不得跌入 void 或看到端牆。
- 多人可以相聚或分散到遠方；不生成兩人之間所有空頁面，各 session 的 slot 不重疊。
- 只回收沒有玩家、相關掉落物／投射物、跨 seam 操作或門互動的頁面；必須有有限延遲與資源上限。
- `DoorKey` 由 session UUID、logical door index、LEFT／RIGHT 構成，不使用 physical slot 當永久身分。
- M2 門面保留穩定邏輯身分；尚未提供 Universe backend 時，不讓側門假裝能穿越新宇宙。選擇／測量／跨宇宙通道依 M3／M4 邊界另行實作。
- 採普通方塊、模型／blockstate JSON，不要求 recursive renderer 或 Sodium 私有 renderer hook。
- 固定 session 入口 replica 的前門保留普通 Controller 右鍵整面開／關，打開後通往既有負向走廊；只有已發布 SUPERPOSITION 的目前映射可操作。ARMING／RETURNING、Shift 維護與投影拆除皆拒絕，不自動刪／開前門，不改來源原艙前門，也不是 M3 的跨宇宙門。

## 5. 斷電返還交易

```text
收到原艙真實斷電
    → session 進入 RETURNING，拒絕新入場及門互動
    → 保存返還進度，確認原始 world／艙體身分與有限 interior
    → 安全返還參與者
    → session 結束，暫時走廊不再可進入
    → M1.2 關閉協調確認完成
    → 僅原艙解除保護
```

- 請求與確認分開；移動失敗、例外、原 world 不可用、身分不一致時，不能先發布 OFF／解鎖，也不能提前回收仍有玩家的頁面。
- 整個過程以伺服器執行緒序列化並可重試；同 tick 斷電／重新供電、頁面跨越／返還只得到一個權威結果。
- 回原本建立此艙體的 world 與 Chamber，不是玩家的床、全域 spawn 或任意當前世界。
- 按玩家 UUID 安排確定性返還槽位，優先保留入場時 local x／z 排序；不能為解碰撞把人送到艙外或殼內。
- 有限 interior 容量不足時拒絕開始，不在返還時才臨時猜測位置。
- 回原艙後玩家看到普通盒子；來源 shell 不因建立／收起走廊而被拆掉。
- 重新供電必須等本次返還完成，再依新 cohort 與新一劑 QuantumState 建立新 session。
- 活動 session 必須維持已驗證來源 Controller 的必要 chunk 可用，確保電源能被監測；chunk ticket 只針對具體 session 的來源／頁面與返還準備，不改 M1 load-sync queue 的不強載規則。

## 6. 離線、重啟與失敗策略

以下是已核准的保守安全策略，避免拆掉原艙後才發現玩家留在無效空間。

- 入場前版本化保存 session UUID、chamber UUID、來源 world／role、anchor／facing、參與者、原艙 local 位置與返還進度；恢復資料的落盤須先於入場提交。
- 玩家離線時不把他從 cohort 靜默移除；保留 pending-return。斷電時其他在線參與者先返還，離線者在登入正常操作前返還。
- 還有離線 pending-return 或返還失敗者時，原艙暫不解鎖；不刪玩家、不任意刪資料、不把空集合當作全部返還。
- 重啟／不完整入場一律取消舊 session 並執行恢復，不能自動挑選／建立新 Universe，也不能因 persisted ARMED 複製一個 session。
- 恢復資料需持久化藥效返還決策：不完整入場 rollback 才還原入場完整快照；成功 session 的正常返還不補發快照，也不刪除玩家在走廊後來新喝的 QuantumState。轉成 RETURNING 及再次重啟都不能遺失此決策。
- 恢復與 cleanup 需冪等；SERVER_STOPPED 清 runtime queue／ticket／server identity，保留必需恢復資料。
- 掉落物與投射物列入頁面生命週期及斷電清理策略；不在仍有玩家／有價物品的 slot 上直接清空方塊。支援失敗時保留可恢復狀態與明確錯誤。
- 沒有可證實的來源資料時不得猜另一個 Universe；記錄錯誤並阻止不安全提交。

## 7. 光照與邊界

- 沿用 M1.2 的可選 LambDynamicLights 方案，驗證主手／副手持火把在普通艙內、入口 replica、走廊跨頁與返還後都能照亮。
- 火把不替代 QuantumState，不需要在受保護 interior 放置方塊；照明缺少不改 cohort、session 或世界身分。
- 固定 Superposition Dimension 是 M2 的已知空間，必須有真實 live 驗證。
- 任意第三方／未知 key／runtime-created Universe world 的 lifecycle／queue live coverage 仍是 M3 gate；不得以 M2 固定 world 或 M1 pure tests 冒充。

## 8. 驗證與交付 gate

- 純測試：level-trigger 資格、單一 session、cohort／效果原子消耗、分頁 floorDiv／往返、穩定 DoorKey、slot 不重疊、確定性返還、重試及恢復 NBT。
- 原生 GameTest／testmod：外部先供電→進艙關門喝藥→真實固定 world 入場；缺 buff／零人／spectator 排除；斷電→返還→保護解除順序；返還失敗維持保護。
- 分頁 live：連續走過多頁並返回、多人相距很遠、掉落物與投射物跨 seam、無可見終點且資源占用不按行走總距離無界成長。
- 持久化：活動中 disconnect、返還期間 disconnect、不同 Java 程序 restart、來源／目的 world identity 拒絕、同 tick 斷電與重新供電。
- production-only dedicated Done→stop、各 world 正常儲存、無 client class 載入錯誤、release JAR 不含測試探針／第三方照明 JAR。
- `clean build` 與全套相關測試；原版／照明／Sodium／Iris／shader 的人工紀錄分開，不宣稱 build 覆蓋 GPU。
- 人工單人 gate：不用指令，外部拉桿開→進入→關門→喝藥或已有 buff→走廊真的延伸。斷電可使用預先設好的外部延遲斷電電路，不要求第二帳號或室內輸入指令。
- 最終人工 gate：斷電回同一原艙且看回普通盒子、原艙創造模式可拆、session replica／非原艙不因斷電獲得拆除權限、全程沒有新 Universe allocation。

實作前須審閱本規格，再在 `docs/plans/` 建立獨立 M2 計畫。M1.2 自動驗證完成後，可在 feature branch 繼續 M2；M1.2／M2 的人工 gate 必須各自記錄，未關閉前不得合併 main。本文件不是 main 合併授權，也不豁免既有人工驗收。
