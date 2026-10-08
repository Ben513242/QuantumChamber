# M5 塌縮與單向通道（第一切片）設計

日期：2026-10-01。基準：`main`＝`a7e1be2`（code 等同 `ba854e8`），分支 `feature/m5-collapse`。下文的 `main/` 代表 `src/main/java/dev/quantumchamber/`，行號以 `a7e1be2` 為準。

狀態：v5.4。使用者已於 2026-10-01 決定 §3 所列事項，並指示依本文寫 spec。v5.4（2026-10-08）依使用者裁示，在 §10.8 新增「到達目的宇宙」的提示，見 §20.8。Spec review 共五輪（含第五輪的 re-review）：
- v1 的 Critical 1、Important 8 由 v2 處理。
- re-review 在 v2 新發現的 Critical 1、Important 2（N1–N3）與 Minor m1–m8 由 v3 處理。
- 第三輪在 v3 新發現的 Important 1（N4）與 4 項 Minor，已在 v3 修正。
- 使用者轉交的第四輪 review（`d9b3249`：Critical 1、Important 7、Minor 1）由 v4 處理。使用者同時指示：修正後直接進入 plan 與實作，不再另等核可。
- 第五輪獨立 review 在 v4（`2c24d51`）新發現的 Important 3（I-1～I-3）與 Minor 10，由 v5 處理；re-review 在 v5 新發現的 Important 1（N-A）與 Minor 7，由 v5.1 處理；v5.1 輕量確認的 Minor 5 項由 v5.2 處理；H2 實作的 spec review 指出的 §4 H2 用語問題由 v5.3 處理。

處置見 §20。

## 1. 目標與成功畫面

M4 留下的是 checked `MEASURED + SELECTED` receipt：門已被量測，但門仍關著，玩家還在走廊裡。M5 第一切片要把這個 receipt 接成玩家看得到的結果：

```text
玩家在走廊右鍵側門（M4：checked SELECTED＋MEASURED）
→ 下一個 tick：checked COLLAPSING
→ 走廊收掉，全組回到原艙（門維持一般狀態，已返還的人可以自由開門）
→ 確定目的地：NEW 在這一步建立並登記新宇宙（不需要供電，關門也不會中止）
→ 開始準備：門口沒有實體、艙內也沒有非參與者時，門打開成量子膜（有碰撞，不能穿過）
   └ 準備期間參與者關門 → 結束（不重抽；目的宇宙已經存在，保留）
→ 目的地準備完成：checked PASSAGE_READY，量子膜可穿過，比較器 15
→ 參與者穿過量子膜：同 XYZ、同 yaw／pitch，進入被選中的宇宙（站在目的投影艙的門口）
→ 以下任一成立，通道就結束，原艙解除封鎖：
   全員都已跨過、參與者關門、60 秒到期、原艙斷電或結構失效
```

- SOURCE 候選：回到原艙後，門直接以一般的開門呈現，門外就是原世界。沒有量子膜，也不傳送。
- DORMANT receipt（M4 時期選完、玩家已返還的原艙）：原參與者進入已供電且 enabled 的該艙時，照收據完成同一個選擇。

## 2. 不在本切片，以及已知限制

- **沒有正規回程。** 玩家一旦成功跨入目的 Universe，本次 passage 不提供反向 traversal。Passage 關閉後的正常回程不是此切片的保證；這是已知的 gameplay limitation，不是 fallback teleport。
- **動態宇宙的維度家族會漏回 canonical 世界**（M6 才修正）：
  - 在動態宇宙死亡、又沒有有效的床或重生錨時，會依原版回到 canonical 主世界的重生點（違反不變量 30）。
  - 在動態宇宙使用原版地獄門或終界門，會進入 canonical 的地獄／終界，回程則到 canonical 主世界（違反不變量 37–39）。

  這兩條是實際存在、但非正規的回程。M5 不阻擋，也不保證。
- **沒有 Nether／End family、`DimensionFamilyRoutingService`、`RespawnRoutingService`**（M6）。
- **沒有 see-through portal、目的地預覽、complex renderer，也沒有自訂 client packet。**
- **只有 `VANILLA_OVERWORLD_SHARED_SEED_V1`。** 新宇宙與存檔同 seed：地形和原世界的原始地形相同，但沒有原世界的任何修改。`generationSeedMaterial` 保持凍結、不使用。
- **沒有全域 lazy 投影。** 只有本次通道的那座艙會投影到目的宇宙，其他艙不會出現在動態宇宙（不變量 32 延後）。
- **目的宇宙的投影艙是 inert。** 不能供電啟動，activation 仍只接受 vanilla Origin。
- **沒有 lazy 宇宙池與卸載。** 新宇宙沿用 M3 的 `EAGER_ENABLED`：每次開服都會物化所有 enabled 的宇宙。依 M3 契約，任何一筆 enabled 宇宙在開服時物化失敗，server 會正常停止；M5 不改這條（§8.3 會把物化失敗的那筆改為 `DISABLED`，避免由 M5 自己觸發）。
- 不做 branch snapshot。不重抽候選，不覆寫或刪除 M4 receipt 的 context、ledger、selection，也不重做 M4 tasks。
- **非參與者不能使用通道。** 通道開著時，非參與者穿過量子膜只會走進原世界。
- **只傳送玩家本人。** 掉落物、投射物與坐騎不會跟著穿過；騎乘中的參與者不會觸發傳送。
- **離線的參與者會延後通道，走廊也不會立刻消失（不變量 24 的例外）。** 尚未返還的離線參與者，要等他重新連線、返還、走廊清完之後，才開始準備目的地（沿用 M2／M4 的返還規則）。期間已返還的人可以自由開門離開，原艙維持封鎖。
- **永久性的失敗會讓原艙一直封鎖。** 這一版沒有管理員取消工具。只有在資料損壞、EXISTING 宇宙失去資格、投影位置衝突，或通道傳送發生無法回滾的原生錯誤時才會發生（§11.2）；暫時性的 IO 失敗不算永久。封鎖期間任何玩家都可以照常開門離開，不會被困住。
- **艙內有非參與者時，準備期間不會出現 sealed 量子膜。** 門維持一般開門，參與者此時走出去只會回到原世界；通道照常可以到達 READY（§7.3）。這與設計 §20.4 STABILIZING 的「門檻碰撞」不同。
- **journal 遺失或被還原成舊版**：schema 4 的初始化證據跟著消失，兩個 ledger 會被當成第一次建立。投影的第一次寫入另有實體檢查（§6.5）：投影 Controller 還在時，不會因此清掉既有投影艙的 interior。ledger 遺失之後投影不受保護，Controller 若已被拆掉，就無法偵測；其他後果不在本切片處理。
- **物化重試時 crash 會停服。** `MATERIALIZE_FAILED` 重試時要先改回 `EAGER_ENABLED`；若在物化完成前 crash，下次開服會依 M3 契約停服（§8.3）。
- **投影不處理非玩家 entity。** 第一次寫入投影時，目的 volume 內的非玩家 entity 不移動、不移除，交由原版處理；可能窒息、被擠出，掛在被覆寫方塊上的掛畫或物品展示框也可能依原版規則掉落（§9.2）。
- **時間凍結**：`/tick freeze` 會讓 game time 停止，通道的 60 秒也跟著停止計時。
- 通道期間斷線、多組 cohort 重逢的完整語意，本切片只做到 fail-closed（§12），完整版留給後續切片。

## 3. 使用者決策（2026-10-01）

| 題目 | 決定 |
| --- | --- |
| 通道外觀 | 門口是量子膜：一般 Minecraft 方塊模型，不透視、不預覽目的地 |
| 回程 | 本切片單向（§2 第一點） |
| DORMANT | 完成原本的選擇，禁止重抽。backend 無法完成時進入 SEALED：收據保留、可重試 |
| 誰能跨過 | 只限 frozen cohort 的參與者 |
| Nether／End 原艙 | 選門之前就擋下。M4 時期在 Nether／End 留下、NEW／EXISTING 的收據，做安全取消（§11.3） |
| discovery | 只在 NEW 追加。EXISTING 只重驗、不追加；SOURCE 不寫 |
| SOURCE | 走 fast path：不配置宇宙、不跨世界傳送，結果就是一般的開門 |
| 投影 | 保護與載入辨識改用動態 role 的 exact 權威，重啟後仍能辨識並保護 |
| 傳送政策 | passage 專用：保留 M3 的身分驗證、FULL chunk 與回滾，不採用 M3 的「安全落點」拒絕 |
| 物化擁有權 | runtime materialize 由 `UniverseLifecycleService` 擁有，M5 不自建 backend |
| 真 client | F1 失敗就立即停止 M5 的玩法實作 |
| 預設 | 塌縮不動 Buff；新宇宙 `EAGER_ENABLED`；通道 60 秒；結束條件見 §10.6 |

本文對上述決策有三處詮釋。使用者沒有逐項確認，但已指示修正後直接實作，所以依本文執行，並在回報中再次列出：

1. **Nether／End 的安全取消。** 「DORMANT 禁止安全取消」與 Nether／End 選項所附的「安全取消」方向相反。本文採用具體的 Nether／End 選項，但把取消限縮為唯一的例外（§11.3）。若改為「SEALED 保留到 M6」，只需要改 §11.3 與 §12 對應的列。
2. **NEW 的 discovery 時機。** 使用者原本寫的是「第一次 checked PASSAGE_READY 時追加」。但依設計 §20.4，READY 之前關門也會結束通道；若等到 READY 才追加，已配置的宇宙會變成無法被候選系統選到的「幽靈世界」，這正是使用者想避免的情況。所以本文改為在 RESOLVE 階段「物化成功之後就追加」（§6.4、§7.3），READY 仍然蘊含 discovery 已落盤。
3. **PassageBinding 從 PREPARE 開始。** 設計 §20.4 規定 READY 之前關門就結束 binding，而且之後重開不會復活。本文把「binding」解讀為從 PREPARE 開始：返還、清理、RESOLVE 與 WAITING 期間，門維持一般狀態，參與者可以自由開關（讓已返還的人能離開原艙），不會結束塌縮；PREPARE 開始之後，參與者關門才結束（§7.3）。

## 4. 前置工作（排在功能 task 之前）

hardening 的順序依使用者 2026-09-29 的指定。每項都是獨立 task，各自要有 RED／GREEN、focused tests 與完整回歸。

- **T1 GameTest 順序依賴**（只改 testmod）：
  - `assertEmptyAuthorities` 只檢查自己的 chamber，或讓留下殘留的 test 自行收尾。
  - 理由：`Batches.createBatches` 以 HashMap 分組，batch key 超過 96 時會 resize。H2–H4 與 F 系列新增的 GameTest 會跨過這個門檻，所以要在任何新增 GameTest 之前修好。
- **H1 codec strictness**（M5 schema 變更的前置）：
  - entropy root 必須恰為 exact keys。
  - decode 時強制 `ledger.size() ≤ maxCandidateEntries`。
  - schema 3 內層的 `Origin`、`Participants`、`SpaceLeases` 改為 exact key 與型別檢查。
  - Vanilla WorldKey 必須帶 namespace，並為 canonical 形式。
  - 補 rejection fixtures。
  - H1 不改 schema 版號；既有的合法 fixture 必須照舊 exact 讀回。
- **H2 checkpoint revision**：
  - 原生存檔的失敗追蹤改為以「同一 storage 的同一個 region 檔」為單位（region 檔的 header 由同一 region 的 32×32 chunks 共用，header 寫入失敗可能波及其中的相關 chunk）。`MeasuredWorldSaveCheckpoint` 只看本交易相關 chunks 所在的 region：來源艙，以及走廊 lease 涵蓋的 chunks。
  - 不再阻擋的：其他 region、其他世界的失敗，只要在原版 flush 之前就已完成（例如同步序列化失敗、已完成的寫入失敗）。server 端的失敗回報（`onChunkSaveFailure`）依世界合併 chunk、entities、poi 三種 storage，所以相關 region 內其他 storage 的失敗仍會阻擋（偏 fail-closed，屬「等效」範圍）。
  - 仍然 fail-closed 的：相關 region 的失敗、region flush（`completeAll`）的失敗，以及原版 flush 時仍 pending 的寫入失敗（原版 `completeAll().join()` 會讓 `server.save` 直接丟例外，`MinecraftServer.save` 與 `ServerWorld.save` 都沒有 catch）。後者是原版語意的界線，不繞過；它不比原本的全伺服器計數更糟。相關失敗都沿用現有退避。
  - 這個 checkpoint 要做成通用元件，§9.2 的投影會再次使用，也受同樣的界線約束。
- **H3 未 checked authority 的收斂**：
  - q1：`abortUnchecked` 只回退尚未 checked 的 authority。
  - q2：abort 本身拋例外時，要真的隔離該 session（或以等效的 fail-closed 停住它），而且 log 要與實際狀態一致。
  - q3：隔離中的 session 要有 checked RETURNING 的收斂路徑。
  - q4：殘留的 unchecked ARMING 也要收斂，並修正 `SessionRecoveryManager` 中說得過滿的註解。
  - q5＋待辦：`abandonInitial` 在 abort 之後補一次 checked 寫出，覆蓋磁碟上未確認的 ARMING；WARN 記 root cause，並依 chamber＋原因去重。
  - M4 quality Nit 3：`describe()` 走訪 cause chain 時，要防範多節點的 cause 環。
- **H4 凍結時的位置修正**：
  - `ServerPlayNetworkHandlerRecoveryMixin` 取消 `onPlayerMove`／`onVehicleMove` 時，送回伺服器權威位置，並加節流，不要每個封包都送。
  - 涵蓋 RETURNING、MEASURED 與 M5 的 COLLAPSING 凍結。
  - mixin 設定加上 `defaultRequire: 1`。
  - 測試品質子項（與位置修正本身無關）：
    - testmod `M2CorridorGameTests.java:1591` 改用原始 float 比較。
    - testmod `fabric.mod.json` 的名稱改為「QuantumChamber 原生驗證 testmod」。

## 5. 狀態機與 journal schema 4

`SessionState` 新增 `COLLAPSING`、`PASSAGE_READY`、`SEALED`：

```text
MEASURED＋SELECTED（M4；live 或 DORMANT）
  └─ checked → COLLAPSING
       ├─ 推導階段：RETURN_PLAYERS → RELEASE_GEOMETRY → RESOLVE → WAITING → PREPARE（不另存）
       ├─ Destination=SOURCE：進入 PREPARE 時 checked 終局（移除 record）→ 一般開門
       ├─ PREPARE 期間參與者關門：checked 終局（不重抽；目的宇宙已在 RESOLVE 建立，保留）
       ├─ 解析或準備失敗：checked → SEALED（收據保留）
       │                  └─ 可重試的原因、且觸發成立：checked → COLLAPSING
       └─ 準備成功：checked → PASSAGE_READY
                       ├─ 通道結束：checked 終局
                       └─ 傳送無法回滾（FAILED_RECOVERY_REQUIRED）：checked → SEALED(TRANSFER_RECOVERY_REQUIRED)，永久
```

**Record 欄位**：schema 4 的 record 是 schema 3 的 11 個 key，再加一個 `Collapse` compound。ARMING、SUPERPOSITION、RETURNING、MEASURED 的 `Collapse` 必須是空 compound。M5 的三個狀態必須恰有以下 key：

| Key | 規則 |
| --- | --- |
| `CollapseRevision` | 固定為 1 |
| `CommittedAtGameTime` | ≥ 0 |
| `Destination` | `{Kind:"SOURCE"}` 或 `{Kind:"UNIVERSE", UniverseId}`。在 COLLAPSING 提交時決定，之後不可變 |
| `PassageOpenedAtGameTime`、`PassageExpiresAtGameTime` | PASSAGE_READY 與 SEALED(`TRANSFER_RECOVERY_REQUIRED`) **必須**是實值，且 expires＝opened＋1200。其餘狀態與其他 SEALED 原因**必須**為 -1 |
| `Crossed` | 已成功完成 passage 傳送的參與者 UUID 清單，必須是 participants 的子集。只在 PASSAGE_READY 與 SEALED(`TRANSFER_RECOVERY_REQUIRED`) 才可能非空；其他狀態與原因必須為空。空清單沿用 END held type |
| `RecoveryRequired` | 傳送無法回滾的參與者 UUID 清單。SEALED(`TRANSFER_RECOVERY_REQUIRED`) 必須恰有一筆，而且是不在 `Crossed` 中的參與者；其他狀態與原因必須為空 |
| `SealedReason` | 只有 SEALED 是非空列舉，其餘狀態為空字串。列舉值見 §11.2 |

**合法組合**（在 M4 矩陣之外新增）：

- COLLAPSING：context 存在、selection 為 SELECTED、Crossed 為空。允許部分參與者已返還；lease 可以維持原樣或一次清空。依 M4 規則，空 lease 蘊含全員已返還（`main/persistence/SessionRecoveryRecord.java:109-111`）。
- PASSAGE_READY：全員已返還、lease 為空、`Destination` 為 UNIVERSE（SOURCE 永遠不會進到 READY）。
- SEALED：全員已返還、lease 為空、`Destination` 為 UNIVERSE。opened／expires、`Crossed` 與 `RecoveryRequired` 依上表：`TRANSFER_RECOVERY_REQUIRED` 必須帶著 READY 的值與一筆 `RecoveryRequired`，其他原因一律為 -1 與空清單。

**單調 authority**（`sameAuthority` 擴充）：

- 進入 MEASURED 之後，context、ledger、selection 都必須完全不變。
- `returned` 只增不減；lease 只能維持原樣或清空。
- `CollapseRevision`、`CommittedAtGameTime`、`Destination` 一經寫入就不可再改。
- opened／expires 只能寫入一次；`Crossed` 只增不減。
- MEASURED 之後的合法狀態邊：
  - MEASURED→COLLAPSING。
  - COLLAPSING→PASSAGE_READY、COLLAPSING→SEALED。
  - PASSAGE_READY→SEALED：只允許 `SealedReason=TRANSFER_RECOVERY_REQUIRED`，opened／expires 與 `Crossed` 原樣保留，`RecoveryRequired` 寫入那位玩家（§10.3）。
  - SEALED→COLLAPSING：重試；只能清空 `SealedReason`。`TRANSFER_RECOVERY_REQUIRED` 是永久原因，沒有這條邊。
  - 同狀態的進度寫入（self-loop）：返還、清 lease、加入 `Crossed`。SEALED(`TRANSFER_RECOVERY_REQUIRED`) 完全凍結，沒有任何 self-loop；`RecoveryRequired` 只在進入時寫入一次。
- M4 原有的狀態邊不變。

**跨 session 的玩家唯一性**（`validateOwnership`／`participantsAvailable`）：

- COLLAPSING、PASSAGE_READY、SEALED 只佔用「尚未返還」的參與者；已返還的參與者不佔用，可以使用其他艙。這比照 M4 的 DORMANT 豁免，並落實 M4 handoff 第 7 點。
- Chamber 的唯一性不變：這三個狀態仍然封鎖自己的原艙。
- M2 的 RETURNING 與 M4 的 MEASURED，保持原本「佔用全部參與者」的規則。
- 以上規則定義成單一的佔用謂詞 `occupies(record, player)`：
  - ARMING、SUPERPOSITION、RETURNING、非 DORMANT 的 MEASURED：佔用全部參與者。
  - DORMANT：不佔用任何人。
  - COLLAPSING、PASSAGE_READY、SEALED：只佔用尚未返還的參與者。
- `validateOwnership`、`participantsAvailable`，以及所有「依玩家找所屬 session」的查找（例如 `SessionRecoveryManager.recordFor`），一律使用這個謂詞（§13.2）。這樣，一位玩家同時是 SEALED A 的已返還參與者、又是活動 B 的參與者時，斷線與 JOIN 的處理只會找到 B。

**終局**：

- M4 的 `remove` 照舊拒絕 MEASURED，也拒絕 M5 的三個狀態。
- M5 新增唯一的 checked 終局 API。它只接受四種起點：
  1. PASSAGE_READY 的通道結束。
  2. `Destination=SOURCE` 的 COLLAPSING。
  3. PREPARE 期間參與者關門。
  4. §11.3 的 Nether／End 取消。
- 終局的流程：current 與 flushed 都要 exact 等於 expected → 移除 → flush → 讀回確認該 record 已不存在。
- flush 或讀回失敗時，以 H3 的 CAS 規則放回 expected record，並依 §6 的收斂規則保持 dirty，然後記 WARN；不改門。
- 讀回確認之後，才修剪該 session 在 `authorityHistory`／`checkedAuthority` 的內容（＝M4 quality M1），並留一個 process 內的 session UUID tombstone，防止同一個 UUID 被重新放回。
- 同時移除該 session 在 `SuperpositionSessionManager` 的 runtime（`sessions`）與來源 ticket（`tickets`）。否則 `returnToOrigin` 會從殘留的 runtime 或 ticket 取得 authority（`main/superposition/SuperpositionSessionManager.java:317-321`），原艙會一直停在 RETURNING。attach 只為 COLLAPSING 建立來源 ticket（返還需要），不為 PASSAGE_READY 與 SEALED 建立。執行期間進入 SEALED 時也立即釋放來源 ticket，SEALED→COLLAPSING 重試時再取得，避免永久原因的 SEALED 讓來源 chunk 一直被載入。

**版本與保護**：

- schema 4 envelope 是「M5 authority 已初始化」的不可撤銷證據，做法與 M4「schema 3 envelope 是 candidate 初始化證據」相同，不另外新增 NBT 欄位（§6）。兩個旗標分開，不可混用：
  - `m5Evidence`：只決定寫出的 schema。讀到 schema 4 時為真；本 server instance 初始化成功時也設為真。之後不再變回假。
  - `m5ReadyThisInstance`：只決定能不能提交新的 COLLAPSING（§6、§7.1）。只有本 server instance 的初始化成功才為真；讀到 schema 4 不會讓它變真。

  寫出的 schema 依下式決定：

  ```text
  有 legacy record          → 2（M4 規則：legacy 與 candidate 不混裝）
  否則 candidateInitialized → m5Evidence ? 4 : 3
  否則                      → 2（空 journal 或只有 legacy，沿用 M4）
  ```

  - 所以 schema 4 只會出現在 `candidateInitialized` 已為真的 journal；讀到 schema 4 時，`candidateInitialized` 與 `m5Evidence` 都為真，但 `m5ReadyThisInstance` 仍取決於本次初始化。
  - 初始化成功之前，journal 維持 3（或 2），也就不能寫入任何 M5 狀態。
  - 一旦寫成 schema 4 就不再降回。
  - schema 3 檔照舊可以 strict 讀取，記憶體內補成空的 `Collapse`。
- 用 M5 build 寫過之後，M4 build 讀到 schema 4 會 fail closed（不可降版，要寫進文件）。
- `blocks()` 在 COLLAPSING 且玩家尚未返還時凍結（含 H4 的位置修正）；PASSAGE_READY 與 SEALED 不凍結。
- `presence()`：三個 M5 狀態只依 record 推導，不看 runtime（DORMANT 續行與重啟之後都沒有 runtime）：COLLAPSING 與 SEALED 回新增的 `Presence.COLLAPSE`，PASSAGE_READY 回新增的 `Presence.PASSAGE`（§10.7）。三者都會讓該原艙不能開新 session。M4 的 MEASURED 維持原本的推導。

**謂詞**：定義 `receiptRetained(state)` 為 MEASURED、COLLAPSING、PASSAGE_READY、SEALED 四者之一。§13.2 列出所有必須改用這個謂詞、或另外處理 M5 狀態的既有分支。

## 6. 新增的 checked 權威

四個權威都沿用 M4 discovery／entropy 的寫法：同目錄暫存檔、force、atomic move、strict 讀回，必要時 exact 比對。

**M5 authority 初始化與遺失判定**（沿用 M4 以 journal envelope 作為初始化證據的做法；`main/persistence/SessionRecoveryState.java:38-39`、`main/superposition/SuperpositionSession.java:33-36`）：

- allocation ledger 與 projection ledger 只由「M5 authority 初始化」建立。初始化在開服時、lifecycle bootstrap 物化之前執行（與 §9.3 的索引同一時點），每個 server instance 一次。本文的「本 server instance」「重啟」以 server 為單位：單人遊戲在同一個 JVM 內關閉再開啟世界，也算一次重啟（與 M3 lifecycle、runtime registry 以 server 為 key 一致）。
- 判定只看 journal 的 flushed envelope：
  - 尚未是 schema 4（schema 1–3，或 journal 檔不存在）：缺少的 ledger 檔以 checked 方式建立為空檔；已存在的檔照常 strict 讀取。
  - 已是 schema 4：兩個 ledger 檔都必須存在。缺任何一個就是遺失，進 `AUTHORITY_CORRUPT`，不建檔、不補寫。
- journal 只有在某一次初始化成功之後才會**升到** schema 4（§5；之後讀到 4 的開服，即使當次初始化失敗，也照寫 4），而 M5 狀態只存在於 schema 4。所以：
  - schema 4 envelope 本身就證明兩個 ledger 檔已建立；
  - journal 還不是 schema 4 時，ledger 不可能有任何 M5 寫入，補建空檔不會蓋掉遺失的證據。
- 這條規則不依賴 discovery 或 catalog 的內容。M4 時期 discovery 已經非空的存檔（例如 fixture 或驗收世界），升級後第一次 NEW 也不會被誤判為遺失。
- 初始化失敗，不論原因（IO 例外、既有 ledger strict decode 失敗，或 schema 4 之下 ledger 遺失）：記 ERROR，本 server instance 一律不提交新的 COLLAPSING（§7.1 的提交失敗路徑；收據留在 MEASURED／DORMANT，修復之後可以續行），journal 也不會因此升到 schema 4。已在 journal 中的 M5 record 若目的地是 UNIVERSE，到 RESOLVE 時依失敗分類進 SEALED（遺失或 decode 失敗為 `AUTHORITY_CORRUPT`，IO 為 `AUTHORITY_UNREADABLE`）；保護改用 §9.3 的退化規則。下次開服重新初始化。

**既有紀錄的驗證**：所有 idempotent 的 lookup（allocation entry、catalog record、discovery record、projection entry），找到既有紀錄時都要先驗證它正是本 session 會寫出的內容，不符就進 `AUTHORITY_CORRUPT`，不沿用、不覆寫。各權威要比對的欄位見 §6.1–§6.5。

**checked 寫入失敗的收斂**：

- 寫入失敗時，磁碟可能仍是舊內容，也可能已經是這次嘗試的新內容（atomic move 已完成、讀回才失敗；例如 `main/universe/UniverseCatalogStore.java:36-42`）。只還原記憶體無法讓兩者一致；而且 discovery 會被 M4 直接從磁碟讀取（`main/superposition/SuperpositionSession.java:35`、`main/candidate/CandidateLedgerService.java:53`），已落地的 append 一旦被移除，其他 session 的候選池就會改變。
- 所以收斂依寫入的種類分成兩個方向：

| 方向 | 適用的寫入 | 做法 |
| --- | --- | --- |
| **roll-forward**（新內容是單調的事實，或是較保守的一方） | allocation entry；catalog 的配置與改為 `DISABLED`；discovery record；projection PENDING 與 COMPLETE；journal 進入 SEALED（任何原因，含由 READY 進入）；journal 加入 `Crossed`；COLLAPSING 的返還與清 lease 進度（M4 的現行做法是保留 current：返還進度由 `recover()` 寫入，`main/persistence/SessionRecoveryManager.java:168-169`，`:93-108` 每個 tick 重新從 flushed 建 record；有多位參與者時，下一個 tick 嘗試的可能是另一位，那筆 put 會被 `sameAuthority` 拒絕。所以真正讓它收斂的是 §7 的「只重試同一份 current 的寫出」或原生 autosave，而不是 M4 的下一 tick 重試） | current 保留這次要寫的新內容並保持 dirty，絕不移除已可能落地的 append。之後的任何寫出（重試、其他 session 的 flush、原生 autosave、停服）都帶著它 |
| **restore**（失敗後的流程依賴舊狀態） | journal 的 COLLAPSING 提交、READY 提交、終局、SEALED→COLLAPSING 重試；catalog 在 §8.3 重試時改回 `EAGER_ENABLED` | 依 H3 CAS 把 current 還原為上一份已確認的內容並保持 dirty（catalog 改回 `DISABLED`，也就是較保守的一方），下一次寫出以它覆寫磁碟 |

- 兩個方向共通：
  1. discovery 與兩個 ledger 不是 PersistentState，由各自的 owner 記住「需要收斂」，在下一次使用前先 checked 重寫。catalog 與 journal 依原生 dirty 機制。
  2. 收斂寫入成功之前，該權威在本 server instance 標為未確認：M5 不依它做新的配置、物化、投影方塊寫入或 READY 判斷，只重試收斂。重試使用權威自己的退避（同 §11.2 的數列，但以權威為單位，不是以 session 為單位）。收斂寫入本身也失敗時維持未確認。投影方塊只在 PENDING entry 確認之後才寫（否則 crash 後 §6.5 的實體交叉檢查會把寫到一半的方塊誤判成衝突）。
  3. **collapse view**：M5 對單一 session 的判斷（presence、門的方塊狀態、跨越、結束條件）以 flushed record 為準；唯一的例外是 roll-forward 尚未確認的 journal 寫入（SEALED、`Crossed`、返還與清 lease 的進度），這時以 current 為準。這些寫入只會讓行為更保守或記錄既成的事實（關閉通道、不再傳送已跨過的人、已返還的人不再凍結），所以先於落盤採用是安全的。尚未確認期間，該 session 只做收斂（§7）。
  4. 收斂完成之前若 crash，磁碟上可能是舊內容，也可能是新內容。M5 的寫入順序保證兩者在重啟後都是合法狀態，照常 strict 讀取、採用：

| 寫入（新內容可能已落地） | 重啟後為什麼合法 |
| --- | --- |
| allocation entry | COLLAPSING 已提交，`Destination` 就是同一個推導值 |
| catalog 配置（`EAGER_ENABLED`） | allocation entry 已確認（W2、W3） |
| catalog 改為 `DISABLED`，或重試時改回 `EAGER_ENABLED` | 前者等同 §8.3 已完成；後者等同 §8.3 重試時 crash（§2 已列的風險） |
| discovery record | catalog 已確認，物化已發生；重啟時由 bootstrap 再物化（W5） |
| projection PENDING | 尚未寫方塊，或寫到一半；PENDING 的下一次寫入照第一次寫入處理（W4） |
| projection COMPLETE | 方塊、驗證與原生存檔 checkpoint 都已完成 |
| journal 的 COLLAPSING 提交、SEALED→COLLAPSING | 都是合法狀態邊；重啟後依 §12 的 COLLAPSING 列續做 |
| journal 的 READY、SEALED、`Crossed`、終局移除 | 都是合法狀態邊；重啟後依 §12 處理 |

- journal 寫入失敗時的 session 行為：
  - restore 類：record 留在上一份已確認的狀態，該 session 套用 §11.2 的記憶體內退避；PREPARE 中的門依 §7.3 閘門失效的處理改為一般開門。
  - roll-forward 類：依上方 collapse view 立即生效（例如 SEALED 立即關閉通道），並持續重試寫出。
  - 兩者重啟後都依 §12 續做。

**讀取失敗的分類**：

- strict decode 失敗、推導值不符、orphan storage、確定遺失：屬於 `AUTHORITY_CORRUPT`，永久。
- 讀寫時的 IO 例外：屬於 `AUTHORITY_UNREADABLE`，可在重啟之後重試（§11.2）。整合審查曾記錄 Windows 上疑似暫時性檔案鎖造成的落盤失敗，所以不能把 IO 例外直接視為損壞。

### 6.1 Allocation ledger

- 檔案：`<save>/data/quantumchamber_universe_allocations.dat`，schema 1。
- 內容：`{SchemaVersion, Entries[{AllocationToken: 32 bytes, UniverseId, Profile, ProfileVersion}]}`。
- 規則：只能追加；token 唯一、UniverseId 唯一；exact keys。
- UniverseId 的推導：取 `SHA-256(UTF-8("quantumchamber:universe-id:v1") ‖ token)` 的前 16 bytes，設定 RFC 9562 的 version 8 與 variant 位元。
  - 推導讓 COLLAPSING 在任何配置之前，就能把 exact 的 `Destination` 寫下來。
  - ledger 才是 idempotency 的權威：已有 entry 就用 entry。entry 的 UniverseId 與推導值不符，或 Profile／ProfileVersion 不是本切片的 OVERWORLD profile，進 SEALED(`AUTHORITY_CORRUPT`)。
- 檔案缺失：依 §6 的初始化規則；執行期間發現檔案消失，一律視為遺失。
- strict decode 失敗：進 SEALED(`AUTHORITY_CORRUPT`)。
- 讀取時的 IO 例外：進 SEALED(`AUTHORITY_UNREADABLE`)。
- 寫入的 IO 失敗：依 §6 的收斂規則處理，進 SEALED(`ALLOCATION_IO_FAILED`)，可重試。

### 6.2 Catalog 配置

- 呼叫 `allocateOverworld(uuid, ordinal)`，其中 `ordinal` 是 flushed catalog 的最大 ordinal 加 1（catalog 為空時為 0），availability 為 `EAGER_ENABLED`。
- **只有在 catalog 的 current 恰等於 flushed（沒有 dirty）時才配置。** 不相等時等到下一個 tick 再試，不進 SEALED。M5 是唯一的 runtime catalog 寫入者，dirty 只在寫入失敗、等待收斂時持續；持續超過 600 ticks 時記一次 WARN。
- 先 checked flush，才物化。flush 或讀回失敗時，依 §6 的 roll-forward：current 保留這筆配置並保持 dirty，下一次寫出（重試或原生 autosave）把它寫出；確認之前不物化。之後進 SEALED(`ALLOCATION_IO_FAILED`)，重試時先完成收斂。M3 的 `UniverseRegistryState.save` 失敗時本來就保留 current 與 dirty（`main/universe/UniverseRegistryState.java:217-224`），不需要新的還原 API。
- catalog 已有該 UniverseId 時不重新配置，但要驗證：OVERWORLD descriptor、`VANILLA_OVERWORLD_SHARED_SEED_V1`、`SHARED_SAVE_SEED_V1`，availability 為 `EAGER_ENABLED`（§8.3 的重試路徑才可能是 `DISABLED`）。不符進 SEALED(`AUTHORITY_CORRUPT`)。
- 配置之前，先確認該 UUID 的 storage 目錄不存在。目錄已存在、catalog 卻沒有這筆紀錄，就是 orphan：進 SEALED(`AUTHORITY_CORRUPT`)，絕不採用或刪除那個目錄。

### 6.3 Runtime 物化與查詢由 lifecycle 擁有

`UniverseLifecycleService` 新增兩個 server-thread 的 gameplay API，使用 lifecycle Context 內既有的 backend 與 runtime registry：

- `materializeAllocated(server, UniverseId)`：
  - 前置條件：lifecycle healthy 且 ready；record 要等於 flushed catalog 的那一筆，而且是 `EAGER_ENABLED`。
  - 已經 ACTIVE 時，直接回傳同一個 world instance。
  - 同一個 server instance 內曾經失敗過的 descriptor，直接拒絕（`main/universe/UniverseRuntimeRegistry.java:91-92` 不允許原地重試）。
  - 失敗時要記入 lifecycle `Context` 的 materialize failures，與 bootstrap 的失敗一致。否則停服驗證會丟出 `STOPPED_UNVERIFIED`（`main/universe/UniverseLifecycleService.java:175-196`），連安全回滾的失敗也會讓正常停服失敗。
- `resolveActive(server, UniverseId)`：回傳 exact 的 ACTIVE world。供 EXISTING 重驗、預載、投影與跨越判定使用。

M5 不另外 new `Minecraft121DynamicDimensionBackend`，也不繞過 lifecycle 的 runtime owner。

### 6.4 Discovery 追加

- 新增正式的 checked `append`：
  - `discoveryOrdinal` 為 watermark＋1。
  - `discoveredAtGameTime` 為當下的 game time。
  - `firstObserver` 為 `SelectedBy`。
  - `gameplayEligible` 為 true。
- 以 UniverseId 判斷 idempotency。已存在時先驗證：`gameplayEligible` 為 true，而且 `firstObserver` 等於本 session 的 `SelectedBy`（這個 UniverseId 由本 session 的 token 推導而來，只有本 session 會追加它）。驗證通過就不改動；不符進 SEALED(`AUTHORITY_CORRUPT`)，不改寫既有紀錄。`discoveryOrdinal` 與 `discoveredAtGameTime` 可以不同（例如上一次寫入已落地、讀回才失敗）。
- 寫入或讀回失敗：依 §6 的 roll-forward 處理（保留這筆 append，絕不移除），然後進 SEALED(`ALLOCATION_IO_FAILED`)，可重試。重試時先完成收斂；append 是 idempotent。
- 只有 NEW 會追加，時間點是 RESOLVE 階段物化成功之後（§3 詮釋 2）。所以只要看到 PASSAGE_READY，就代表 discovery 已經落盤；PREPARE 一定在 RESOLVE 之後，準備期間有人關門結束通道時，目的宇宙也已經可以被選到。
- EXISTING 只重驗，不追加；SOURCE 不寫。

### 6.5 Projection ledger

- 檔案：`<save>/data/quantumchamber_projections.dat`，schema 1。
- Entry 欄位：`ChamberUuid`、`UniverseId`、`Role`（本切片只有 OVERWORLD）、`ControllerPos`、`OutwardFacing`、`Status`（PENDING 或 COMPLETE）。
- `(ChamberUuid, UniverseId)` 唯一。Status 只能 PENDING→COMPLETE；不刪除 entry。既有 entry 的 Role、ControllerPos、OutwardFacing 必須等於本 session 的來源艙，不符進 SEALED(`AUTHORITY_CORRUPT`)。
- 寫方塊之前先 checked 寫入 PENDING。完整寫完、驗證、目的 chunks 的原生存檔 checkpoint 都成功之後，才 checked 改為 COMPLETE（§9.2）。
- 檔案缺失：依 §6 的初始化規則。schema 4 之後檔案缺失一律是遺失，不會被誤判成「第一次寫入」而清掉投影艙 interior 裡玩家的修改；只有 EXISTING 造訪過的存檔也一樣。
- **實體交叉檢查**（涵蓋 journal 遺失或回退，§2）：ledger 沒有這個 `(ChamberUuid, UniverseId)` 的 entry，目的世界的投影 Controller 位置（local (3,6,0)）卻已經有 Controller 方塊（可能是 ledger 遺失，也可能是玩家在動態宇宙自行搭建），進 SEALED(`PROJECTION_CONFLICT`)，不寫 PENDING、不清 interior。已有 PENDING entry 時不做這個檢查（寫到一半的投影本來就照第一次寫入處理）；投影方塊也只在 PENDING 確認之後才寫（§6 收斂規則第 2 點），所以不會因為自己寫到一半而誤判。
- strict decode 失敗：進 SEALED(`AUTHORITY_CORRUPT`)。讀取時的 IO 例外：進 SEALED(`AUTHORITY_UNREADABLE`)。
- 以上兩種情況，保護都改用 §9.3 的退化規則。

## 7. 塌縮交易（COLLAPSING）

新增 `CollapseService`，以自己的 `initialize()` 註冊 `END_SERVER_TICK`。

- 它只讀 §6 的 collapse view（flushed record，加上 roll-forward 尚未確認的例外），所有 journal 變更都在既有的 session operation guard 之內。
- 每次寫入前，都要求該 session 的 current 恰等於 flushed。
- **尚未確認的 roll-forward 期間只做收斂**：某個 session 有尚未確認的 roll-forward journal 寫入（`Crossed`、SEALED、返還與清 lease 的進度）時，`CollapseService` 對它只重試同一份 current 的寫出（這是上一條的唯一例外），其他一律暫停：
  - 不嘗試任何傳送（跨越暫停），所以不會有無法記錄的傳送；
  - 結束條件與終局延後到確認之後才評估；確認時若已到期，照常依 (c) 結束；
  - 不疊寫新的進度。
  - 開關門仍由 `CollapseService` 攔截，不交給 `ChamberDoorService`（它只改 `OPEN`，會產生 `open=false`＋`passable_*` 的非法狀態）：collapse view 為 PREPARE 或 PASSAGE_READY 時，一律拒絕、不改門；collapse view 為 SEALED，或門本來就是一般狀態的階段時，照該階段的規則。
- 與其他 tick 的先後順序由 plan 決定，但判斷不能依賴未 checked 的 current 狀態。
- **「已供電」的定義**：本文所有「已供電」「斷電」（§7.1 的 DORMANT 觸發、§7.3 的閘門、§10.2、§10.6 (d)、§11.2 的重試觸發）一律指來源 Controller 的即時紅石輸入（`world.isReceivingRedstonePower(controllerPos)`，且周圍 chunk 已載入），不看 registry 的 `ChamberPowerState`。M4 的 DORMANT 原艙在 registry 一直是 RETURNING，M5 斷電時也會設成 RETURNING（§10.7），看 registry 會讓續行永遠不觸發。

### 7.1 提交 COLLAPSING 的條件

起點必須是 flushed exact 的 `MEASURED + SELECTED`。M4 的 measured release 進行到一半時不提交（`space.finalizing` 不為空、或 `measuredReleasing` 為真），等它完成。

- **live**（非 DORMANT）：選擇之後的下一個 tick，在 guard 閒置時提交。
  - 重啟後 attach 到非 DORMANT 的 MEASURED 時也一樣，這取代 M4「等待 M5 接管」的狀態。
  - 若 M4 的保留式 recovery 已經開始（例如同一個 tick Buff 失效，或有人斷線），照樣提交：record 允許部分參與者已返還、lease 已清空，COLLAPSING 直接接手後續的返還與清理。
  - 提交 COLLAPSING 不要求供電，§7.3 的 RESOLVE 也不要求。量測不可撤銷；斷電只影響 §7.3 的 PREPARE。
  - `m5ReadyThisInstance` 為假（本 server instance 的 M5 authority 初始化失敗，§5、§6）時，提交一律視為失敗。
- **DORMANT**：邊緣觸發，不是每個 tick 都觸發。觸發事件只有兩種：
  - 某位原參與者由艙外進入來源艙的 interior。
  - 開服 attach 之後，第一次觀察到有原參與者在艙內。

  觸發當下，以下全部成立才提交：
  - 那位參與者在線、存活、非旁觀，bbox 完整在 interior 內。
  - 他沒有被其他 session 佔用（§5 的唯一性規則）。
  - 來源艙已載入、結構合法、已供電、Origin 為 enabled。
  - 該 session 不在退避期內（§11.2）。
- **Nether／End 來源**：依 §11.3。
- **提交失敗**：此時尚未配置任何東西。以 H3 的規則回退。
  - live：請求 M4 的保留式 recovery 作為 fallback，玩家返還，session 變成 DORMANT。
  - DORMANT：維持原狀。
  - 兩者都會啟動退避（§11.2）。

### 7.2 Destination

COLLAPSING record 在提交時寫入 `Destination`：

- SOURCE → `{Kind:"SOURCE"}`。
- EXISTING → 候選裡的 UniverseId。
- NEW → ledger entry 的 UniverseId；還沒有 entry 時，用 §6.1 的推導值（此時不寫 ledger）。

### 7.3 推導階段與門

| 階段 | 條件 | 做法 | 原艙門 |
| --- | --- | --- | --- |
| `RETURN_PLAYERS` | 有人尚未返還 | 沿用 `SessionRecoveryManager.recover`，延伸到 COLLAPSING：在線的人立即返還，每人一次 checked 寫入；離線的人在重新連線時返還。落點用 `ChamberReturnPlacement`，並在這裡修正落點碰撞（R2 M6）與返還失敗 WARN 缺 cause 的問題 | 一般狀態；已返還的人可以照常開關 |
| `RELEASE_GEOMETRY` | 全員已返還，但仍有 lease | 沿用 MEASURED 的 release 路徑並延伸：清除幾何、逐格確認為 AIR、H2 checkpoint、checked 寫入空 lease，最後才釋放 ticket 與 slot | 一般狀態 |
| `RESOLVE` | 全員已返還、沒有 lease，`Destination` 為 UNIVERSE，目的地尚未確定 | §8：EXISTING 重驗；NEW 依序完成 ledger、catalog、物化與 discovery。不需要供電、不看閘門，通常在同一個 tick 完成 | 一般狀態；開關門不影響塌縮 |
| `WAITING` | 全員已返還、沒有 lease、目的地已確定（SOURCE 不需要確定），但 PREPARE 的閘門不成立 | 等待閘門成立 | 一般狀態 |
| `PREPARE` | 同上，而且閘門成立 | SOURCE 走 §7.4；UNIVERSE 走 §9 的預載與投影 | UNIVERSE：依下方「套用量子膜的條件」，改為 `open=true`＋`membrane=sealed_<axis>` |

- **目的地已確定**：EXISTING 為重驗通過；NEW 為 allocation entry、catalog record、ACTIVE world、discovery record 都已確認（§8.2）。這是由權威推導出來的，不另存；重啟之後由 RESOLVE 以 idempotent 的方式重新確認。
- **PREPARE 的閘門**：來源艙已載入、結構合法、已供電、Origin 為 enabled，而且有原參與者在艙內。
  - live 塌縮返還之後，通常立即成立。
  - 不成立時停在 WAITING，之後依 §7.1 DORMANT 的邊緣觸發再評估。
- **套用量子膜的條件**：門的 25 格內沒有任何 entity 的 bbox，**而且**艙內沒有非參與者玩家（不含旁觀者）。不成立時，準備照常在背景進行，門維持一般開門，下一個 tick 再試。
- **門口有人時的規則**（適用所有會讓門變成有碰撞的轉換）：
  - 改成 `sealed` 量子膜：依上一條。
  - 改成關閉（SEALED、§10.6 (a)、PREPARE 期間關門）：門口有 entity 時，改為 `open=true`＋`membrane=none` 的一般開門，不關閉。
  - 目的是不讓任何人卡在方塊裡。
- 「有原參與者在艙內」只是進入 PREPARE 的條件，不是持續條件；PREPARE 開始之後參與者離開艙內（例如斷線），準備照常繼續。
- **PREPARE 進行到一半時閘門不再成立**（斷電、Origin 被停用、結構失效、來源艙卸載）：
  1. 釋放目的世界的 ticket。
  2. 門改為 `open=true`＋`membrane=none` 的一般開門，所以不會有人被困住。
  3. 退回 WAITING。

  已完成的 RESOLVE、投影都保留；閘門再次成立時，依 §7.1 的邊緣觸發續做。
- **門狀態的重新套用**：門的方塊狀態由 §6 的 collapse view 投影出來（所以 roll-forward 中的 SEALED 不會在 chunk 重載時把門套回 passable）。重啟或 chunk 載入之後，排入下一個 tick 的佇列再套用，不在 chunk 載入事件當下呼叫 `setBlockState`。
- **PREPARE 期間的開關門**：
  - 參與者在原艙這一側開關原艙門（bulkhead 或 Controller）：依設計 §20.4 結束這次塌縮，checked 終局，門改為關閉。不重抽；PREPARE 一定在 RESOLVE 之後，所以目的宇宙已經配置、物化，也已經是 discovered。
  - 艙內的非參與者（bbox 在 interior 內）：門改為一般開門，讓他離開。這不結束塌縮、不改變階段；他離開之後，下一個 tick 依「套用量子膜的條件」重新套用。
  - 艙外的非參與者：拒絕。
  - 這個攔截必須排在 `ChamberDoorService` 之前，由 `CollapseService` 處理。參與者關門時，順序是先終局、再改門。
- **非參與者不會被困**：量子膜只在艙內沒有非參與者時才套用，套用之後外人也進不來（量子膜有碰撞）。外人仍可能在 sealed 期間出現在艙內（例如在艙內重生，或被指令傳送進來），這時他可以依上一條把門改為一般開門。PASSAGE_READY 時量子膜可穿過，外人穿過只會走進原世界；SEALED 時任何玩家都可以照一般方式開門。
- 處於 `MeasuredRecoveryPhase` 的對應延伸時，順便移除永遠不會回傳的 `RETAIN_RECEIPT`（M4 quality M6 的這一小項）。

### 7.4 SOURCE fast path

進入 PREPARE 時：

1. checked 終局（§5）。
2. 門改為 `open=true`、`membrane=none`，也就是一般的開門。
3. 對參與者顯示訊息。

不配置宇宙、不呼叫跨世界傳送、不寫 discovery、不會出現比較器 15。

## 8. 目的地解析（RESOLVE，`Destination=UNIVERSE`）

RESOLVE 排在 PREPARE 之前，不需要供電，也不看 PREPARE 的閘門（§7.3）。所以 PREPARE 期間不論誰關門，NEW 的目的宇宙都已經存在，不會出現「收據被消耗、宇宙卻從未建立」的情況。

### 8.1 EXISTING 重驗

以下全部成立才繼續。PASSAGE_READY 提交前會再驗一次（§10.2）：

- discovery 有這個 UniverseId，且 `gameplayEligible`。
- flushed catalog 有這筆紀錄，且為 `EAGER_ENABLED`。
- descriptor 是 OVERWORLD。
- `resolveActive` 回傳 exact 的 ACTIVE world。
- 來源 role 是 OVERWORLD（由 §11.3 保證）。

任一不符就進 SEALED(`EXISTING_INELIGIBLE`)。

### 8.2 NEW 的順序

1. allocation ledger 的 lookup-or-create（checked，§6.1）。ledger 檔由 §6 的初始化建立；判定為遺失就直接進 SEALED(`AUTHORITY_CORRUPT`)。
2. catalog 沒有這筆時，配置它；已有時驗證它（§6.2，checked）。
3. 透過 lifecycle 物化（§6.3，idempotent）。
4. discovery 追加，或驗證既有紀錄（§6.4，checked）。

以上完成，RESOLVE 就結束。之後在 PREPARE 依 §8.4 做預載與投影；投影前寫入 projection ledger 的 PENDING entry（§6.5）。

### 8.3 物化失敗

- 以 checked 方式把該筆 catalog 改為 `DISABLED`（`changeAvailability`），避免下次開服時 bootstrap 因這筆失敗而停服。
  - 這次 flush 也失敗時，依 §6 的 roll-forward：current 保留 `DISABLED` 並保持 dirty，持續重試寫出；記 ERROR，然後照樣進 SEALED(`MATERIALIZE_FAILED`)。在 `DISABLED` 確認之前重啟，下次開服仍可能停服，屬於 §2 已列的同一類風險。
  - 確認之前 catalog 會維持 dirty，其他 NEW 的配置（§6.2）會等待；這是刻意的，避免在 catalog 未確認時疊加新的配置。
- 然後進 SEALED(`MATERIALIZE_FAILED`)。
- 這個原因只能在重啟之後重試：重試時先 checked 改回 `EAGER_ENABLED`，再呼叫 `materializeAllocated`；仍失敗就再改回 `DISABLED`。改回 `EAGER_ENABLED` 的寫入失敗時屬於 §6 的 restore：current 改回 `DISABLED`，不物化。
- 已知風險：改回 `EAGER_ENABLED` 之後、物化完成之前若 crash，下次開服時 bootstrap 仍會因這筆失敗而停服。這與 W3（catalog 配置之後、物化之前 crash）屬於同一類風險，列入 §2。
- 若 backend 回報無法安全回滾（不是 `FAILED_ROLLED_BACK`），依 M3 規則把 backend 標為 unhealthy。M5 不另行處理，列為已知限制。

### 8.4 共通

- PREPARE 依序：非同步預載（§9.1）→ 投影（§9.2）→ checked PASSAGE_READY（§10.2）。
- RESOLVE 與 PREPARE 的任何失敗都進 SEALED，並保留同一個 `Destination`；絕不換成別的宇宙。

## 9. 非同步準備與最小 Projection

### 9.1 預載

- 在目的世界，對涵蓋「艙體 bbox 外擴 2 格」的每個 chunk 加上 M5 自己的 chunk ticket。外擴 2 格，是為了涵蓋傳送時「玩家 bbox 外擴 2 格」的 FULL 檢查（`main/universe/UniverseTransferService.java:279-288`）。
- 每個 tick 以非建立模式查詢，直到全部到達 FULL。不在 server thread 上做阻塞式的 `getChunk(..., create=true)`。
- 沒有劇情上的逾時，也不重抽。超過 600 ticks 仍未完成時記一次 WARN，然後繼續等；期間參與者可以依 §7.3 關門結束。
- ticket 在通道結束、PREPARE 期間結束，或進入 SEALED 時釋放。

### 9.2 寫入投影

在目的世界的同 XYZ、同朝向寫入：

- shell 用 bedrock。
- local (3,6,0) 放 Controller，`FACING` 設為 outwardFacing。block entity 用預設值，不複製來源的身分欄位。
- 門板 25 格放 bulkhead，`open=true`、`membrane=none`。

兩種寫法：

- **第一次**（ledger 為 PENDING）：
  - 寫完整的 7×7×7。interior 5×5×5 清成 AIR；volume 內原本的方塊與 block entity 直接覆寫，不掉落物品。這就是設計文件 §3.5 的 retrocausal 覆寫。
  - 寫完逐格驗證。
  - 對目的世界的相關 chunks 做原生存檔 checkpoint（H2 的通用元件）。
  - 以上都成功，才 checked 改為 COMPLETE。
- **之後**（COMPLETE；EXISTING 再次造訪）：只修復不變量的 cell（shell、Controller、門），並把門設回開啟；不碰 interior，保留之前玩家在投影艙內的修改。

**既有 entity**（兩種寫法都適用）：

- 玩家：任何非旁觀玩家的 bbox 與這次要寫入的 cell 相交時，不寫入任何方塊，下一個 tick 再試，併入 §9.1 的 600 ticks WARN。第一次寫入的範圍是整個 7×7×7；之後的修復只有不變量 cell，所以留在投影艙 interior 的玩家不會擋住修復。絕不把方塊寫在玩家身上；期間參與者可以依 §7.3 關門結束。
- 非玩家 entity：投影不移動、不移除、不殺死，也不給予物品；方塊照常寫入，後果交由原版處理（可能窒息或被擠出，掛在被覆寫方塊上的掛畫與物品展示框可能依原版規則掉落）。第一次寫入只清 interior 的方塊，不清 entity。列入 §2 的已知限制。

**衝突**：目的 volume 已經有另一座艙的 COMPLETE 投影時（例如原 Origin 拆除後，在同位置新建的艙），進 SEALED(`PROJECTION_CONFLICT`)，不覆寫。

**寫入方式**：所有寫入都經 `ChamberProtectionService` 的授權寫入。flag 的選擇要避免掉落與連鎖物理更新，由 plan 決定。

**失敗**：寫入、驗證或 checkpoint 失敗時，進 SEALED(`PROJECTION_FAILED`)，可重試。

### 9.3 投影的保護與載入辨識

- **索引**：開服時，在任何動態世界載入 chunk 之前，從 projection ledger 與 catalog 建立記憶體內的索引：`worldKey → UniverseId → 不變量 cell`。
  - 每次 COMPLETE 時更新這個索引。
  - `mayMutate` 是每次 `setBlockState` 都會經過的熱路徑，所以只查這個索引，不讀 PersistentState。
  - 註冊順序要讓索引早於 `UniverseLifecycleService` 的 bootstrap 物化（`main/QuantumSuperpositionMod.java:34-46`）。
- **保護**：`ChamberProtectionService.mayMutate` 遇到非 vanilla 的 world key 時，查上述索引。
  - 不變量 cell（shell、Controller、門）拒絕未授權的變更：玩家破壞與放置、活塞、爆炸、流體。
  - interior 不保護。
  - 投影艙的門可以照既有的門邏輯開關；下一次 PREPARE 會把它重新打開。
- **退化規則**：projection ledger 損壞、遺失或讀不到時，fail closed：
  - 保護改為「所有目前登記中的 OVERWORLD Origin 艙，在每個動態 Overworld 同位置的不變量 cell」，資料取自 `ChamberRegistry`。
  - 已知缺口：這不是完整的超集。已拆除的 Origin 會從 registry 移除，它們留在動態宇宙裡的 Dead Chamber 投影，在退化模式下不受保護。
  - 同時，所有新的 PREPARE 都進 SEALED（`AUTHORITY_CORRUPT` 或 `AUTHORITY_UNREADABLE`）。
  - 下次開服若讀取成功，退化規則自動解除。
- **Origin 拆除之後**：projection ledger 的 entry 保留，投影照樣受保護，也就是 inert 的 Dead Chamber（不變量 17、18）。
- **載入辨識**：動態世界裡的 Controller 載入時，依索引判定為 PROJECTION instance。
  - 它是 inert 的：比較器 0、不 `registerOrigin`、不啟動，也不會反覆記 log。
  - 不在索引中的 Controller，照舊視為未知的 foreign Controller。
- **不改 `ChamberRegistryState`**：仍是 schema v2，只收 ORIGIN；投影不進入 chamber registry 或 `ProjectionIndex`。

## 10. 量子膜與通道

### 10.1 Bulkhead 方塊狀態

新增 `membrane` 屬性，值為 `none | sealed_x | sealed_z | passable_x | passable_z`：

- axis 是門平面的水平軸，由 outwardFacing 推得。
- `open=false` 時只能是 `none`。舊存檔沒有這個屬性，預設為 `none`。
- **碰撞**：
  - `open=true`＋`sealed_*`：完整方塊。
  - `open=true`＋`none` 或 `passable_*`：沒有碰撞。
- **模型**：兩種 membrane 狀態都畫成置中、雙面、不透明的薄平面。
  - 新增貼圖 `quantumchamber:block/quantum_membrane`（16×16，不透明）。
  - 用 cutout layer，需要在 `src/client` 以 `BlockRenderLayerMap` 註冊（目前 client 沒有任何 RenderLayer 註冊）。
  - 不使用 emissive，也不使用任何 shader 專屬的特性。
- **逐狀態覆寫**：bulkhead 是 `Settings.copy(Blocks.BEDROCK)`（`main/registry/ModBlocks.java:18`），所以要依 state 覆寫 opacity、face culling、`suffocates` 與 `blockVision`，避免薄平面周圍的面被剔除而出現透視破洞，也避免 sealed 時讓人窒息。
- 方塊仍不可被流體取代，所以門口擋得住流體。
- `ChamberDetector` 與 `WorldChamberBlockView` 的判定不變：只要 `open=true`，不論 membrane 為何，都是 BULKHEAD_OPEN。
- **既有的門切換只改 `OPEN`**（`main/chamber/WorldChamberDoorAdapter.java:21-22`）。所有會關門的路徑，都要同時把 membrane 設為 `none`，不得產生 `open=false`＋`*_x/z` 的非法狀態。

### 10.2 PASSAGE_READY

- **前置條件**：
  - `Destination` 為 UNIVERSE；`resolveActive` 回傳目的世界；EXISTING 再次通過 §8.1 的重驗。
  - 預載已到 FULL。
  - 投影為 COMPLETE，且已驗證。
  - （只有 NEW）discovery 已落盤。
  - 相關權威都不在 §6 的「未確認」狀態。
  - 來源艙已載入、結構合法、已供電、enabled。
- **提交**：opened＝now、expires＝now＋1200。
- **提交之後**：
  1. 門改為 `passable_<axis>`。
  2. 刷新該艙的 Controller，比較器經 §10.7 的 `Presence.PASSAGE` 輸出 15。
  3. 對在艙內的參與者顯示 actionbar「量子通道已開啟：穿過門口的量子膜即可前往。」
- **client 同步**：設計 §20.4 readiness 第 7 項（client 收到目的地同步）由原版跨維度移動的 respawn／chunk 同步負責；F1 會以真 client 驗證，不另做預先同步。

### 10.3 跨越判定

在 PASSAGE_READY 期間，server 每個 tick 逐一檢查參與者。以下全部成立才觸發傳送：

- 玩家 UUID 屬於 participants，且尚未在 `Crossed` 中。
- 在線、是 exact 的玩家 instance、存活、非旁觀。
- 位在 exact 的來源 world instance。
- 沒有騎乘任何東西。
- 沒有被其他 session 佔用（§5）。
- 腳的位置在門口範圍內：local x∈[1,6)、y∈[1,6)。
- 方向正確：local z 已越過門板中線往外（z<0.5），而上一個 tick 還在中線內側。

觸發後，依 §10.4 傳送到目的世界的同 XYZ、yaw、pitch 與速度：

| 傳送結果 | 處理 |
| --- | --- |
| 成功 | checked 把該玩家加入 `Crossed` |
| `FAILED_ROLLED_BACK` | 顯示 actionbar「通道傳送失敗，你仍在原世界。」，WARN 只記一次；通道仍開著，可以再試 |
| `FAILED_RECOVERY_REQUIRED` | 不移除 record。checked PASSAGE_READY→SEALED(`TRANSFER_RECOVERY_REQUIRED`)，`RecoveryRequired` 記下該玩家，保留 participants、`Crossed`、opened／expires 與收據，作為需要人工處理的 recovery authority；記 ERROR（含 transfer result 的移動與回滾證據）。通道對所有人關閉，門依 §11.2 處理，釋放目的世界的 ticket。不再自動移動該玩家，也不重試 |

這筆 SEALED 寫入屬於 §6 的 roll-forward：寫入失敗時 current 保留 SEALED(`TRANSFER_RECOVERY_REQUIRED`) 並保持 dirty，不做 CAS 還原，之後任何寫出（其他 session 的 flush、autosave、停服）都帶著它。依 §6 的 collapse view，通道立即關閉：不再接受跨越、presence 為 `COLLAPSE`、門依 §11.2、結束條件不再評估，也不會改走終局。只有在磁碟仍是 PASSAGE_READY、而且在任何一次寫出成功之前就 crash 時，重啟才會依 §12 的 PASSAGE_READY 列結束，只剩 log 證據。

非參與者穿過量子膜時不觸發任何東西，會走進原世界。

### 10.4 Passage 傳送政策

新增 `PassageTransferPolicy`。它適用於傳送前的前置檢查、傳送後的驗證，以及回滾資格，三者都一樣：

- **保留**：
  - 玩家、來源 world、目的 ACTIVE world 的 exact 身分。
  - 姿態必須是有限值，並在世界高度與世界邊界之內。
  - 目的 bbox（外擴 2 格）涵蓋的 chunks 必須是 FULL。
  - 傳送後逐欄位核對位置、速度、yaw、pitch 與 bbox。
- **一律不採用**：M3 `targetSafety`／`inspectSafety` 的碰撞、流體與地板支撐檢查（`main/universe/UniverseTransferService.java:191-195`、`:237-242`、`:276-302`）。玩家在門口跳躍、目的地是水或岩漿時，都不能因此被判為失敗或無法回滾。
- 不搜尋安全位置，也不調整 Y。
- M3 的 `UniverseTransferService` 與它的測試保持不變。passage 使用新的 policy 實作，或在 M3 服務中加入 policy 參數，由 plan 決定。

設計文件刻意允許門外是石頭、水、岩漿、懸崖或空中。正常情況下，落點本來就在目的投影艙的門口內。

### 10.5 通道期間的開關門

- 參與者在原艙這一側開關原艙門（bulkhead 或 Controller）：結束通道（§10.6 (a)）。攔截排在 `ChamberDoorService` 之前，順序是先終局、再改門。
- 非參與者：拒絕。

### 10.6 結束條件與終局

由 `CollapseService` 每個 tick 判斷，任一成立就結束（時間以 game time 計）：

- (a) 參與者主動關門。
- (b) 全員都在 `Crossed` 中。
- (c) game time 已到 `PassageExpiresAtGameTime`。
- (d) 來源艙沒有供電（LOW）、Origin 被停用，或結構失效。依即時訊號判斷（Controller 的紅石輸入、registry 的 enabled、結構驗證），不經 `ChamberPowerCoordinator` 的 `returnToOrigin`（§10.7）。

結束的順序：

1. checked 終局（§5）。
2. 門的狀態：(a) 改為關閉（門口有人時，依 §7.3 改為一般開門）；(b)(c)(d) 改為 `open=true`＋`membrane=none`，也就是一般的開門。
3. 重新計算比較器，釋放目的世界的 ticket。
4. 原艙解除封鎖。若有尚未處理的 LOW，接著走一般的 OFF 流程。

通道結束之後，若關了門、而且全員仍有 Buff，依 M2 規則會自動開始新的 session；塌縮不會動 Buff。

找不到所屬 session、卻帶著 membrane 的 bulkhead（例如終局之後、改門之前 crash），在 `ChamberPowerCoordinator` 刷新該艙時，排入下一個 tick 正規化為 `membrane=none`。

### 10.7 比較器

現有的比較器只經 `ChamberSessionGateway.Presence` 推導：`reconcile` 把 ACTIVE 對應到 ARMED（11，`main/chamber/ChamberPowerCoordinator.java:139-141`），enum 只有 NONE／ARMING／ACTIVE／RETURNING／UNKNOWN（`main/chamber/ChamberSessionGateway.java:10`）。另外兩個現有行為會擋住 M5：

- `presence()` 在沒有 runtime 時回 UNKNOWN（`main/superposition/SuperpositionSessionManager.java:73`，排在 `:74-75` 之前）。DORMANT 續行與重啟之後的 M5 record 都沒有 runtime。
- `reconcile` 把 UNKNOWN、RETURNING，以及 registry 的 `ChamberPowerState.RETURNING` 都當成 pending，即使有供電也會呼叫 `returnToOrigin`（`main/chamber/ChamberPowerCoordinator.java:117-137`）。M4 的 DORMANT 早已因 UNKNOWN 被寫成 RETURNING，之後只有 presence 變回 NONE 才會解除。門板改 membrane 也會經 neighbor update 觸發 Controller 刷新（`main/chamber/ChamberControllerBlock.java:106-111`）。

因此：

- `ChamberSessionGateway.Presence` 新增 `COLLAPSE` 與 `PASSAGE`。`SuperpositionSessionManager.presence()` 對三個 M5 狀態，在既有的 runtime 判斷之前，只依 §6 的 collapse view 推導：COLLAPSING、SEALED → `COLLAPSE`；PASSAGE_READY → `PASSAGE`。
- `ChamberState` 新增 `PASSAGE`，`ChamberStatusSignal` 對應 15；`ChamberControllerBlock` 的開關門訊息 switch（`:87` 附近）補上 PASSAGE 的訊息。
- `reconcile` 遇到 `COLLAPSE` 或 `PASSAGE`：
  - 不走 pending／needsReturn 路徑，也不呼叫 `returnToOrigin`。
  - registry 的 power state：已供電且 enabled 時設為 POWERED；否則設為 RETURNING（不是 OFF），讓保護維持（`ChamberProtectionService` 只在 OFF 時放行變更）。
  - 比較器：presence 為 `PASSAGE`、已供電、enabled → `ChamberState.PASSAGE`（15）；其餘 → ARMED（11）。
- M5 狀態的 `returnToOrigin` 一律沒有副作用，回 false。(d) 與 PREPARE 閘門失效都只由 `CollapseService` 依即時訊號判斷（§10.6、§7.3）。
- record 終局之後 presence 回到 NONE，下一次 `reconcile` 依既有流程把 RETURNING 解除為 POWERED 或 OFF。
- `CollapseService` 在 READY 提交、通道結束、進入 SEALED 之後，都要刷新該艙的 Controller（`ChamberPowerCoordinator.refresh`），讓比較器即時更新。
- `ChamberControllerBlockEntity` 讀到不認得的 `ChamberState` 時沿用既有的 INVALID fallback，所以舊 build 讀到 `PASSAGE` 不會 crash（M5 本來就不可降版）。
- `ChamberPowerCoordinator.reconcile` 在 `current==ARMED` 時短路的問題（R2 M5），在同一段程式一起修。

### 10.8 訊息

actionbar 使用 zh_tw，en_us 另附翻譯：

| 時機 | 訊息 |
| --- | --- |
| 塌縮 | 「量子態塌縮。」 |
| 通道開啟 | 「量子通道已開啟：穿過門口的量子膜即可前往。」 |
| 跨越成功、到達目的宇宙（只對該玩家，v5.4） | 候選是 NEW：「你已進入新的宇宙。」；候選是 EXISTING：「你已進入另一個宇宙。」 |
| 傳送失敗 | 「通道傳送失敗，你仍在原世界。」 |
| 通道結束 | 「量子通道已關閉。」 |
| 進入 SEALED | 「量子通道無法開啟，已封存。」 |
| Nether／End 選門 | 「此維度的宇宙門尚未開放。」 |

- 不顯示 Universe ID、allocation token 或 profile。
- 選門成功時的「量子候選已鎖定，等待塌縮」維持不變。
- 玩家說明與人工預期都不得承諾「候選已鎖定。」：`ALREADY_SELECTED` 在真實 client 上看不到（M4 handoff 第 12 點）。

## 11. DORMANT、SEALED 與 Nether／End

### 11.1 DORMANT 續行

- 觸發條件見 §7.1。提交 COLLAPSING 時，RETURN_PLAYERS 與 RELEASE_GEOMETRY 都已完成，直接進入 RESOLVE，再進 PREPARE 的閘門判斷。
- 使用的是原本的 candidate、原本的 allocation token，以及推導出的同一個 UniverseId。
- NEW 即使沒有人跨過去，也會留下一個永久、而且已 discovered 的宇宙。

### 11.2 SEALED

- **狀態**：參與者都已返還，可能還在原艙內，也可能已經離開（`TRANSFER_RECOVERY_REQUIRED` 時，已跨過的人在目的世界）；門為關閉（`open=false`、`membrane=none`；門口有人時，依 §7.3 改為一般開門）。任何玩家（含非參與者）都可以照一般方式開門離開，不會被困住。原艙維持封鎖（presence 為 `COLLAPSE`），收據保留；已返還的參與者可以使用其他艙（§5）。
- **原因分類**：

| `SealedReason` | 可否重試 | 重試的時機 |
| --- | --- | --- |
| `ALLOCATION_IO_FAILED` | 可 | 邊緣觸發＋退避 |
| `PROJECTION_FAILED` | 可 | 邊緣觸發＋退避 |
| `MATERIALIZE_FAILED` | 可 | 只在重啟之後，每個 server instance 一次（§8.3） |
| `AUTHORITY_UNREADABLE` | 可 | 只在重啟之後，每個 server instance 一次 |
| `EXISTING_INELIGIBLE` | 永久 | — |
| `AUTHORITY_CORRUPT` | 永久 | — |
| `PROJECTION_CONFLICT` | 永久 | — |
| `TRANSFER_RECOVERY_REQUIRED` | 永久 | —（只能由 PASSAGE_READY 進入，§10.3） |

- **重試的觸發**：與 DORMANT 相同的邊緣觸發（§7.1），而且要在退避期之外。
  - 每個 session 的退避從 200 ticks 開始，每次失敗加倍，上限 12000 ticks。
  - 開服之後的第一次觸發不受退避限制。
  - 重試時 checked SEALED→COLLAPSING，重跑 RESOLVE 與 PREPARE（未確認的權威先完成 §6 的收斂）；仍失敗就回到 SEALED。WARN 依原因去重。
- **永久原因**：不再自動重試。記 ERROR，說明「需要管理員處理」，原艙會一直封鎖（§2）。
- 絕不重抽，也絕不取消。
- §7.1 的「COLLAPSING 提交失敗」與 journal restore 類寫入失敗後的記憶體內退避，使用同一套 per-session 退避；§6 的權威收斂重試使用同一數列，但以權威為單位。

### 11.3 Nether／End

- **新的選擇**：session 的 `SourceFamilyRef.role()` 不是 OVERWORLD 時，`CandidateDoorInteraction` 拒絕側門選擇。
  - 回 FAIL，actionbar「此維度的宇宙門尚未開放。」
  - 不寫 SELECTED，走廊照常可以行走（M2 行為不變）。
- **M4 時期已存在的 MEASURED receipt**，來源 role 不是 OVERWORLD：
  - SOURCE 候選：照 §7.1／§7.4 正常完成。
  - NEW／EXISTING 候選：明確的安全取消。
    1. 不提交 COLLAPSING。先依 M4 的保留式 recovery 完成返還與幾何清理，到 DORMANT。
    2. 一觀察到 flushed DORMANT（開服 attach 或執行期間皆同），不必等參與者，直接 checked 終局（§5），記 INFO「不支援的來源維度，已安全取消」。
    3. 不配置宇宙。原艙解除封鎖。
- 這是 M5 唯一的取消路徑，而且不會重抽。

## 12. 重啟、斷線與 crash 窗口

| 狀態 | 重啟時 | 參與者斷線／重新加入 |
| --- | --- | --- |
| schema 1／2 | 不變：strict 的 return-only | 不變 |
| SELECTABLE（schema 3／4） | 不變：沿用 M4 | 不變 |
| MEASURED，非 DORMANT，OVERWORLD 或 SOURCE 候選 | attach 後提交 COLLAPSING；提交失敗時走 M4 的保留式 recovery，並退避 | 未提交前沿用 M4 |
| MEASURED，Nether／End，NEW／EXISTING | 依 §11.3：保留式 recovery → DORMANT → 取消 | 沿用 M4 |
| DORMANT | 等 §7.1 的邊緣觸發 | 不影響 |
| COLLAPSING | 依推導階段續做。門的狀態在下一個 tick 重新套用。ledger、catalog、物化、投影都是 idempotent | 未返還的人斷線：照舊凍結；重新加入時，在 JOIN 之後的下一個 tick 返還到原艙（沿用 M2／M4 機制）。已返還的人斷線：沒有影響 |
| PASSAGE_READY | 開服時直接結束通道：checked 終局；門在下一個 tick 正規化為一般開門。已跨過的人在目的地，沒跨過的人在原艙 | 未跨過的人斷線：沒有影響，重新加入後若通道仍開著可以再試。已跨過的人：在目的世界重新加入（開服時已物化） |
| SEALED | 保留；開服後第一次觸發即可重試（可重試的原因）。`TRANSFER_RECOVERY_REQUIRED` 與其他永久原因不重試 | 不影響 |
| allocation／projection ledger 或 discovery 損壞或遺失（journal 已是 schema 4，§6） | UNIVERSE 目的地進 SEALED(`AUTHORITY_CORRUPT`)，不配置任何東西 | — |
| 存檔第一次用 M5 開啟（journal schema 1–3 或不存在） | M5 authority 初始化補建缺少的空 ledger；journal 已 `candidateInitialized` 時，之後第一次 checked 寫入升為 schema 4（空 journal 或只有 legacy record 時仍寫 2，§5） | 不變 |
| allocation／projection ledger 或 discovery 讀取時 IO 例外 | UNIVERSE 目的地進 SEALED(`AUTHORITY_UNREADABLE`)；下次開服後重試一次 | — |
| catalog 損壞 | 依 M3：開服 bootstrap 讓 server 停止（`main/universe/UniverseLifecycleService.java:52-56`、`:280-282`），不會走到 SEALED | — |

Cross-JVM 要覆蓋的 crash 窗口（每一個都要證明：同一個 UniverseId、catalog 最多只多 1 筆、沒有重抽、不會有人被困住）：

1. W1：COLLAPSING 提交之後，返還完成之前。
2. W2：allocation ledger flush 之後，catalog flush 之前。
3. W3：catalog flush 之後，物化之前（重啟時由 `EAGER_ENABLED` 物化）。
4. W4：投影為 PENDING，寫到一半。
5. W4b：投影方塊已寫完並驗證，但目的 chunks 的原生存檔 checkpoint 尚未完成（COMPLETE 尚未寫入）。
6. W5：discovery 追加之後，投影之前。
7. W6：投影 COMPLETE 之後，PASSAGE_READY 之前。
8. W7：PASSAGE_READY 期間，已有一人跨過。重啟後通道結束，而且同一個 server 內，原艙可以再開新 session（沒有殘留的 runtime、ticket 或 RETURNING）。
9. W8：DORMANT 續行，跨越重啟到達 READY。
10. W9：物化失敗 → 已改為 `DISABLED`、SEALED → 重啟 → 開服不停止 → 重試成功。
11. W10：Nether／End 的 NEW 收據（schema 3 fixture）→ 取消：catalog 筆數不變、原艙解除封鎖。同一批 fixture 的 SOURCE 收據則正常完成。
12. W11：M4 schema 3 存檔（discovery 非空）第一次用 M5 開啟，M5 authority 初始化只建立了一個 ledger 空檔就 crash → 重啟時 journal 仍是 schema 3，補建另一個，不被判為遺失；之後第一次 NEW 照常到達 READY。
13. W12：catalog 配置寫入的 atomic move 已完成、讀回失敗（注入）→ roll-forward 保留配置並保持 dirty、確認前不物化 → 重啟前與重啟後，都只會得到同一個 UniverseId，catalog 最多多 1 筆（§6 收斂規則）。同一個 phase 另注入 discovery append 的讀回失敗，確認已落地的 record 不會被移除。

## 13. 對 M4 行為的改變

### 13.1 行為

- **MEASURED 變成過渡狀態。** M4 spec §11「封鎖該 Chamber 等待 M5 接管」由本切片接手；M4 的保留式 recovery 仍是 fallback。
- **既有 M4 測試的處置**：凡是假設 MEASURED 會一直等待的 M4 GameTest 與 recovery probe phase，都改為在 testmod 的「collapse suspended」hook 下執行。
  - 這個 hook 的語意是「暫停」：被 hook 的 session，`CollapseService` 完全不嘗試提交，也不觸發 fallback。這等同 production 可以到達的「live MEASURED、走廊仍在、guard 未閒置時延後提交」（§7.1），只是時間拉長。
  - 不用「模擬提交失敗」：提交失敗會立即走 M4 的保留式 recovery、玩家返還，與 M4 斷言（例如選門後 record、mapping、原生快照都不變）矛盾。真正的提交失敗 fallback 另由 M5 的測試覆蓋（§15）。
  - M4 的斷言不放寬。
  - 同一次修改一併處理 M4 test 的收尾路徑（延後清單「M4 test 收尾」）與 q7。
- **Nether／End 的側門選擇改為拒絕**（新規則）。
- `ALREADY_SELECTED` 的可達性不變。
- journal 寫出 schema 4 之後不可降版。

### 13.2 必須改用 `receiptRetained` 或另外處理 M5 狀態的既有分支

照目前的程式，durable 變成 COLLAPSING 之後，runtime 仍是 MEASURED；這時若 Buff 到期，會呼叫 `requestMeasuredRecovery`，而它對非 MEASURED 一律丟例外（`main/corridor/CorridorPageManager.java:219`），catch 區塊又再呼叫一次（`main/superposition/SuperpositionSessionManager.java:179-184`），例外就會從 `END_SERVER_TICK` 拋出。因此 F3 必須逐一處理下列呼叫點（plan 逐一核對行號）：

| 檔案 | 位置 | 目前的行為 | M5 的處理 |
| --- | --- | --- | --- |
| `main/superposition/SuperpositionSessionManager.java` | `:59-64`、`:387-394`（attach） | 非 MEASURED 一律轉為 RETURNING | M5 狀態不轉 RETURNING：COLLAPSING 交給 `CollapseService`，PASSAGE_READY 依 §12 結束，SEALED 保留 |
| 同上 | `:66-76`（`presence`）、`:81`（runtime） | 沒有 runtime 時回 UNKNOWN（`:73`）；只認 MEASURED | 三個 M5 狀態在 `:70-73` 之前只依 collapse view 推導：COLLAPSING、SEALED 回 `COLLAPSE`，PASSAGE_READY 回 `PASSAGE`（§10.7）；MEASURED 維持原樣 |
| 同上 | `:178-186`（tick） | MEASURED 時請求 measured recovery | 只對 MEASURED 請求；M5 狀態由 `CollapseService` 負責，絕不呼叫 `requestMeasuredRecovery` |
| 同上 | `:203`（measured 收尾的清理迴圈） | 只處理 MEASURED | COLLAPSING 的 release 收尾使用延伸後的同一路徑 |
| 同上 | `:315-345`（`returnToOrigin`，含 `:323` 的 MEASURED 分支） | MEASURED 時只請求 recovery，不寫 RETURNING | M5 狀態一律沒有副作用、回 false，不寫 RETURNING。斷電的後果全部由 `CollapseService` 依即時訊號處理：RESOLVE 不受斷電影響；PREPARE 依 §7.3 退回 WAITING；PASSAGE_READY 依 §10.6 (d) 結束；SEALED 保留 |
| `main/persistence/SessionRecoveryManager.java` | `:52`（`blocks`） | RETURNING／MEASURED 未返還者凍結 | 加入 COLLAPSING 未返還者 |
| 同上 | `:59`、`:81-84`、`:210-213`（`onJoin`、`onDisconnect`、`recordFor`） | `recordFor` 只排除 DORMANT，取 `flushedRecords()` 的 `findFirst()`，順序不固定 | 改用 §5 的 `occupies` 謂詞，只回傳仍佔用該玩家的 record。由於唯一性規則，這種 record 最多只有一筆 |
| 同上 | `:94`、`:185`、`:216-222`、`:230`（`markReturning`、全員返還後的 release） | MEASURED 時請求 recovery、走 `releaseMeasured` | COLLAPSING 的返還與 release 改走 M5 的延伸路徑；PASSAGE_READY 與 SEALED 不寫入 |
| `main/persistence/SessionRecoveryState.java` | `:179`（`remove` 守門） | 拒絕 MEASURED | 改用 `receiptRetained`；只有 §5 的終局 API 可以移除 |
| 同上 | `:146-151`、`:246-258`（`dormant`、`participantsAvailable`、`validateOwnership`） | 只豁免 MEASURED DORMANT | 依 §5 的唯一性規則 |
| `main/corridor/CorridorPageManager.java` | `:89-92`（attach）、`:219-232`（`requestMeasuredRecovery`／`releaseMeasured`）、`:306`、`:321`、`:424`、`:837-842`（measured release） | 只認 MEASURED | COLLAPSING 的 RETURN／RELEASE 使用延伸後的同一路徑；其他 M5 狀態沒有 space |
| `main/persistence/MeasuredWorldSaveCheckpoint.java` | `:23` | 只接受 MEASURED 的 RELEASE_GEOMETRY | 也接受 COLLAPSING 的 RELEASE_GEOMETRY（H2 改為通用元件） |
| `main/persistence/SessionRecoveryState.java` | `:78-85`（版本白名單、exact keys、依 schema 解碼）、`:83`、`:192`（envelope schema） | 只接受 1–3；candidate 初始化後寫 schema 3 | 白名單加入 4，schema 4 的 exact keys 與解碼；依 §5 的式子以 `m5Evidence` 決定寫出 4，之後不降回；讀到 schema 4 時 `candidateInitialized` 與 `m5Evidence` 為真，`m5ReadyThisInstance` 不受影響 |
| `main/chamber/ChamberSessionGateway.java`、`ChamberState.java`、`ChamberStatusSignal.java` | `:10`、整檔、整檔 | 沒有 M5 的 presence 與 PASSAGE | 新增 `Presence.COLLAPSE`、`Presence.PASSAGE`、`ChamberState.PASSAGE`→15（§10.7） |
| `main/chamber/ChamberPowerCoordinator.java` | `:111-156`（`reconcile`） | UNKNOWN／RETURNING 一律 pending；ACTIVE→ARMED；`current==ARMED` 短路 | `COLLAPSE`／`PASSAGE` 不走 pending、不呼叫 `returnToOrigin`；power state 只設 POWERED 或 RETURNING；`PASSAGE`＋供電＋enabled→15，其餘→11；同時修 R2 M5 |
| `main/chamber/ChamberControllerBlock.java` | `:87` 附近（開關門訊息的窮舉 switch） | 沒有 PASSAGE | 補上 PASSAGE 的訊息 |

## 14. 真 client blocker（F1）

M3 spec §8 把它列為正式 passage 的前置 blocker。F1 排在 T1、H1–H4 之後、任何 M5 玩法程式之前。

- **工具**：只存在於 testmod、預設關閉的 probe 指令，只在帶 testmod 的開發 client／server run 中可用。它沿用既有的 M3 test access，配置並物化一個測試宇宙，再把真實 client 的玩家傳送過去並傳回。
- **檢查項目**（由 Claude 代操作，留截圖與 log 證據）：
  - 單人（integrated）與 dedicated server＋client 各至少一次，進出動態世界都不斷線、不 crash。
  - 天空、時間、天氣正常，地形可見；可以移動、放置與破壞方塊。
  - 在動態世界中重新登入，以及重啟 server，都回到同一個世界。
  - 在動態世界死亡：記錄實際的原版重生行為，作為已知限制的證據。
  - Sodium＋Iris 在光影開與關時都能進出，不 crash，光影不會被強制關閉。
- **失敗時**：立即停止 M5 的玩法實作，回報使用者，回頭處理 1.21 動態維度的 client 同步問題。

## 15. 測試與 gate

**Pure JVM**：

- schema 4 codec 的 strictness 與 rejection fixtures。
- 合法狀態矩陣、`sameAuthority` 的狀態邊與 self-loop、不可變欄位、唯一性規則；終局 API 的 CAS、失敗放回與 tombstone。
- allocation ledger：固定的推導 test vectors、只能追加、idempotency。
- 遺失與損壞的判定表（§6 的初始化規則），至少包含：
  - 全新存檔第一次 NEW 到達 READY，不被判為遺失。
  - M4 schema 3 存檔、discovery 非空（fixture）：升級後第一次 NEW 不被判為遺失。
  - 初始化只建立一個 ledger 就中斷：journal 仍是 schema 3 時補建另一個。
  - journal 已是 schema 4 時刪除 projection ledger 檔（或 allocation ledger 檔）：不重建，進 `AUTHORITY_CORRUPT`；只有 EXISTING 造訪過的存檔也一樣；已 COMPLETE 的投影 interior 不會被重寫。
  - 初始化失敗時 journal 不升 schema 4、不提交 COLLAPSING。
  - 確定遺失時進 `AUTHORITY_CORRUPT`；IO 例外時進 `AUTHORITY_UNREADABLE`。
- 既有紀錄的驗證：allocation entry、catalog record、discovery record（`gameplayEligible=false` 或 `firstObserver` 不符）、projection entry 不符時都進 `AUTHORITY_CORRUPT`，且不改寫既有紀錄。projection 的實體交叉檢查：ledger 沒有 entry、目的地已有一座艙 → `PROJECTION_CONFLICT`，interior 不被清除。
- checked 寫入失敗的收斂：注入「atomic move 完成、讀回失敗」。
  - roll-forward 類（allocation、catalog 配置與 `DISABLED`、discovery、projection、journal SEALED 與 `Crossed`）：current 保留新內容、之後的寫出帶著它，已落地的 append 不會被移除；未確認期間不做新的配置、物化或 READY 判斷。
  - restore 類（COLLAPSING 提交、READY 提交、終局、SEALED→COLLAPSING、改回 `EAGER_ENABLED`）：current 還原、保持 dirty，下一次寫出覆寫磁碟。
  - §6 表中每一種內容（新或舊）重新讀取後都被接受。
- schema 的寫出式子：空 journal、只有 legacy、`candidateInitialized` 為假時都不會寫出 4。
- collapse view：roll-forward 尚未確認的 SEALED 立即讓 presence 變成 `COLLAPSE`、停止跨越。`Crossed` 落盤失敗之後，其他參與者不被傳送、通道不終局、結束條件不評估；收斂之後恢復，若已到期則依 (c) 結束。
- 兩個旗標：讀到 schema 4、但本次初始化失敗時，`m5Evidence` 為真、`m5ReadyThisInstance` 為假，不提交新的 COLLAPSING。
- 終局之後 `SuperpositionSessionManager` 沒有殘留的 runtime 與來源 ticket；重啟時 attach 不為 PASSAGE_READY、SEALED 建立來源 ticket。
- `occupies` 謂詞與 `recordFor` 的查找。
- projection ledger；discovery append 的 idempotency；catalog 配置失敗時的還原。
- `reconcile`：presence 為 `PASSAGE`＋供電＋enabled 時輸出 15；`COLLAPSE`／`PASSAGE` 不呼叫 `returnToOrigin`；registry 已是 RETURNING 時仍能到達 15；斷電時 power state 設為 RETURNING（保護不解除）、輸出 11；終局後解除。
- 狀態邊：PASSAGE_READY→SEALED 只接受 `TRANSFER_RECOVERY_REQUIRED`，並保留 `Crossed` 與 opened／expires；它沒有 SEALED→COLLAPSING 的重試邊。
- 推導階段與 PREPARE 閘門；四個朝向的跨越幾何；`PassageTransferPolicy` 的決策表（含跳躍、水、岩漿時的驗證與回滾資格）。
- SEALED 的原因分類與退避。

**GameTest**（需要原生 checkpoint 的用 `_windows`，其餘盡量跨平台）：

- 選門 → 下一個 tick 進 COLLAPSING → 全員返還 → 量子膜（sealed）→ NEW 的 READY → 比較器 15 → 參與者跨過，在目的投影艙的門口得到同 XYZ、yaw、pitch → `Crossed` 已記錄。
- 兩人：一人跨過、一人留下 → 到期後門恢復一般開門、record 已移除 → 同一座艙可以開新 session。
- 參與者在 READY 時關門；參與者在 PREPARE 時關門（終局、宇宙保留且已 discovered、沒有重抽）。PREPARE 一開始（預載尚未完成）就關門的 NEW：catalog 恰好多 1 筆、discovery 有紀錄，證明 RESOLVE 先於 PREPARE。
- 艙內有非參與者時不套用 sealed 量子膜；sealed 期間出現在艙內的非參與者（以指令傳送進去）可以把門改為一般開門，塌縮不結束，他離開之後量子膜重新套用。
- 投影寫入：目的 volume 有玩家時不寫入、等到他離開；有非玩家 entity（例如生成的動物）時照常寫入、不移除 entity。
- 注入 `FAILED_RECOVERY_REQUIRED`（testmod 的 native move fault）→ record 進 SEALED(`TRANSFER_RECOVERY_REQUIRED`)、`RecoveryRequired` 為該玩家、`Crossed` 保留、比較器 11、門可開、沒有終局。再注入 SEALED 的落盤失敗：通道仍立即關閉，下一次其他 session 的 flush 把 SEALED 寫出。
- 沒有 runtime 的 DORMANT 續行（以 M4 receipt fixture 開始）到達 READY、比較器 15，而且撐過強制的 Controller 刷新與 membrane 造成的 neighbor update；WAITING 期間斷電再恢復之後，READY 不會被結束。
- 艙內有非參與者時，不套用 sealed 量子膜，但仍能提交 READY。
- 返還期間有人離線：已返還的人可以開門離開；離線的人重新加入後完成返還，才開始準備。
- SOURCE 的 fast path：catalog 不變，門為一般開門。
- EXISTING：用 discovery fixture，到達同一個 UniverseId；另一座艙的 session 也能到達同一個宇宙（基本重逢）。fixture 必須同時寫入一致的 allocation 與 projection ledger，或使用不會觸發 §6 遺失判定的資料。
- 非參與者與騎乘中的參與者不觸發傳送。Nether／End 的選門被拒絕。
- 危險目的地：在水、岩漿、石頭地形的 fixture 中，以及在門口跳躍時，照樣抵達，不被判為失敗。
- 投影保護：破壞、放置、爆炸、活塞、流體都無法改動不變量 cell；EXISTING 再次造訪時，interior 的修改仍保留；投影衝突進 SEALED。
- 注入物化失敗 → `DISABLED`＋SEALED，玩家安全、門可開；同一個 server instance 內不重試。
- COLLAPSING 提交失敗 → M4 fallback → DORMANT → 邊緣觸發續行，失敗時有退避（不會每個 tick 重試）。
- 未供電或已停用的 DORMANT 艙：參與者進入，不觸發配置。
- 跨 session 唯一性：
  - 已返還的參與者可以在別的艙開 session。
  - DORMANT 續行不因其他參與者在別的 session 而失敗。
  - 玩家同時是 SEALED A 的已返還參與者、又是活動 B 的參與者，斷線後 B 正確進入 RETURNING。
- PREPARE 進行到一半時斷電：門恢復一般開門、退回 WAITING；恢復供電並由參與者觸發之後，續做到 READY，而且不會配置第二個宇宙。
- 門口有人時：不套用 sealed 量子膜，也不關門（改為一般開門）。
- `AUTHORITY_UNREADABLE`（注入 IO 例外）→ SEALED → 重啟之後重試成功。
- catalog 筆數：NEW 恰好加 1，SOURCE／EXISTING 加 0。
- H4 的凍結位置修正。

**Cross-JVM**：

- testmod 新增 M5 recovery probe，覆蓋 §12 的 W1–W12。
- `scripts/verification/` 新增 `m5-collapse-probe.ps1`，納入 `run-all.ps1` 與 summary 檢查，並經 review。

**回歸與 CI**：

- 開工前，先在乾淨的 worktree 跑一次 `run-all.ps1` 作為基線（T0）。之後每個 task 的完整回歸範圍依 plan。
- 新的 `_windows` test 加入 `.github/workflows/build.yml` 的必要清單。
- RED 的證據一律同時記錄 HEAD 與 `git status --porcelain`（M4 spec Nit 1）。

**真 client**：F1，以及收尾時的 manual smoke（§18）。

## 16. Task 切分（暫定，以 plan 為準）

1. **T0** 基線：在乾淨 worktree 跑 `run-all.ps1`，確認 harness 在 clean clone 狀態下可用；把 M2 留下的 31 項 runtime noise 分類記入 SDD ledger。不改程式。
2. **T1** GameTest 順序依賴（§4）。
3. **H1**、**H2**、**H3**、**H4**（§4）。
4. **F1** 真 client blocker（§14）。
5. **F2** schema 4（含 PASSAGE_READY→SEALED 的狀態邊）、M5 authority 初始化與 schema 4 升版、唯一性規則、終局 API、allocation ledger、projection ledger、discovery append、既有紀錄的驗證、checked 寫入失敗的收斂（roll-forward 與 restore）與 collapse view（pure JVM）。
6. **F3** `CollapseService`：
   - MEASURED→COLLAPSING、§13.2 的所有分支、返還與 release 的延伸（含 R2 M6、WARN cause）、推導階段（含 RESOLVE 的位置）、WAITING 與 PREPARE 閘門、套用量子膜的條件與非參與者的門規則、SOURCE fast path、COLLAPSING 凍結、PREPARE 期間關門。
   - Nether／End 規則與取消。
   - M4 測試的 collapse-suspended hook（含 M4 test 收尾與 q7）。
7. **F4** 目的地：
   - RESOLVE：lifecycle 的 runtime 物化與查詢 API、EXISTING 重驗、NEW 配置、物化失敗改為 `DISABLED`。
   - PREPARE：非同步預載；投影的寫入（含既有 entity 的處置）、驗證與 checkpoint；投影的索引、保護、退化規則與載入辨識；SEALED 的原因與退避。
8. **F5** 通道：量子膜的方塊狀態、模型、貼圖與 client render layer；PASSAGE_READY；跨越判定與 `PassageTransferPolicy`；`FAILED_RECOVERY_REQUIRED`→SEALED；結束條件；`Presence.COLLAPSE`／`PASSAGE`、`reconcile` 與比較器 15（含 R2 M5）；訊息。
9. **F6** 復原：DORMANT 續行與 SEALED 重試的觸發、重啟與斷線矩陣、M5 cross-JVM probe 與 harness 擴充、CI 清單。
10. **F7** 文件：implementation note、README 與玩家說明、設計文件狀態、`docs/implementation-notes/compatibility.md`（設計 §32.3 要求記錄實測的 render stack）、manual smoke 清單。

收尾順序依 AGENTS.md：automated gates → required manual smoke → task＋whole-branch review → docs truth → push → CI 雙平台 → `--ff-only` 合併 `main` → tag `m5` → worktree 退役。

## 17. 延後清單 triage

來源：[M1–M4 整合審查紀錄](../../implementation-notes/2026-09-29-m1-m4-integration-review.md)「延後清單」與「Deferred triage 結論」，以及 [M4 紀錄](../../implementation-notes/2026-09-21-m4-candidate-doors.md)「已知 deferred 項目」。

原則：只納入本切片會碰到的項目；沒碰到的不趁機重構。

| 項目 | 決定 | 理由／位置 |
| --- | --- | --- |
| M5 hardening (1) codec strictness | 納入 | H1 |
| M5 hardening (2) checkpoint revision | 納入 | H2；同時做成投影可重用的通用元件 |
| M5 hardening (3) `abortUnchecked`（q1–q3） | 納入 | H3 |
| q4 雙重故障的全域暫停 | 納入 | H3：同一個收斂機制 |
| q5、`abandonInitial` 不 flush、WARN 重複 | 納入 | H3 |
| 凍結時沒有位置修正 | 納入 | H4；COLLAPSING 也會凍結 |
| testmod ±0 斷言 | 納入 | H4 的測試品質子項 |
| testmod mod name | 納入 | H4 的測試品質子項；M5 會修改 testmod |
| mixin `defaultRequire: 1` | 納入 | H4：同一個 task 會改 mixin |
| `ALREADY_SELECTED` 的可達性 | 納入（文件） | §10.8、§18、F7；不改程式 |
| GameTest 順序依賴 | 納入 | T1：H2–H4 與 F 系列新增的 test 會跨過 96 個 batch key |
| 新 WARN 缺 cause（`SessionRecoveryManager` 的「返還保留 pending」） | 納入 | F3：COLLAPSING 的返還沿用這條路徑 |
| R2 M6 返還落點碰撞 | 納入 | F3：每次塌縮都使用這個落點 |
| R2 M5 `ChamberPowerCoordinator` 的 ARMED 短路 | 納入 | F5：同一段 reconcile 要加入 PASSAGE |
| M4 test 收尾、q7 | 納入 | F3：M4 測試改用 collapse-suspended hook 時會碰到 |
| M4 quality M1 `authorityHistory` 無上限 | 納入 | F2：checked 終局是自然的修剪點 |
| M4 quality M6 `RETAIN_RECEIPT` | 納入（僅此項） | F3：延伸推導階段時會碰到。M6 的其餘項目延後 |
| M4 quality Nit 3 `describe()` cause 環 | 納入 | H3：同一段失敗 log |
| M4 spec Nit 1（RED 要同時記 HEAD 與 `git status`） | 納入（流程規則） | §15 |
| M2 的 31 項 runtime noise | 納入（只分類） | T0：這是下一次 fresh gate；不改程式 |
| N-4 正常結束的 log 分類 | 部分 | M5 新增的正常結束一律記 INFO；既有的 N-4 案例延後 |
| 騎乘載具遺失 | 延後 | M5 不改 `start()` 入場條件；通道本身拒絕騎乘中的傳送（§10.3） |
| 指令成功訊息 | 關閉 | 原版指令層的外觀問題，修正需要侵入指令系統 |
| 舊存檔既有的 entity | 延後 | 與塌縮無關 |
| checkpoint 預檢範圍 | 延後 | 與塌縮無關 |
| `TestHooks` 自我抑制 | 延後 | 除非 F3 的收尾修正改到 `TestHooks`，否則不會碰到 |
| N-3 `recover()` 的行為 | 延後 | COLLAPSING 沿用 `recover()`，但不改它的落點語意；改動牽涉 checkpoint 語意，需另行決策 |
| R2 M7 全域 pin 上限 | 延後 | 與塌縮無關 |
| R2 M8 死碼（restore 分支、`QuantumEffectTransaction.commit`） | 延後 | 不會碰到 |
| R3 m-3 M2 RETURNING／retirement 的 checkpoint | 延後 | COLLAPSING 用 MEASURED 的 checkpoint 路徑；M2 的 RETURNING 不變 |
| R3 m-4 RED XML 亂碼 | 延後 | harness 可讀性問題 |
| M4 quality M2 idle tick 全量重掃 | 延後 | 效能問題，要先量 MSPT |
| M4 quality M5 `sideDoorCells` | 延後 | 不會碰到 |
| M4 spec Nit 2、quality Nit 1／2、round 4 Nits | 延後 | 不會碰到 |
| 整合審查「Nits」列（policy test 名稱過寬、`@Unique` 缺漏、`WARNED` 為 JVM 範圍） | 延後 | 不會碰到；若 H4 的 mixin 修改碰到缺 `@Unique` 的欄位，就在 H4 一併補上 |
| M3 queue observer 的專屬 regression | 延後；F4 若修改 `ChamberControllerLoadSyncQueue` 則納入 | 原 triage：「M5 動到 queue 時補」 |
| Task 9 harness 的 PID 過濾 | 關閉 | 2026-10-01 已在 `scripts/verification/gates.ps1` 完成 |
| harness tracked 化 | 關閉 | 2026-10-01 已完成 |
| spec m-1、q6、s3 | 關閉 | 先前的 triage 已關閉 |

## 18. 人工可見界線與 required manual smoke

本切片完成後，玩家可以看到：

- 選門之後，走廊收掉、回到原艙；準備好之前，門口是不能穿過的量子膜。
- 量子膜可穿過時，比較器為 15；穿過去就在同座標到達新的宇宙（地形是存檔 seed 的原始地形），身後是投影艙。
- 通道在 60 秒到期、全員跨過、有人關門或斷電時結束。

玩家不會看到：正規回程、Nether／End family、目的地預覽、「在當前宇宙重生」。§2 所列的非正規回程（死亡、原版傳送門）仍然存在。

Required manual smoke 由 Claude 代操作，回報給使用者時用白話，項目、做法與預期寫在一起：

1. 單人，兩輪（單人跨過之後就符合「全員都已跨過」，通道立即結束，等不到 60 秒到期）：
   - 跨過：選門 → 回艙 → 量子膜 → 穿過，F3 座標前後一致；之後以唯讀指令確認 record 已移除、原艙的門為一般開門（結束條件 (b)）。
   - 到期：另一次選門，到量子膜可穿過之後不跨過，等 60 秒，門恢復一般開門（結束條件 (c)）。
2. DORMANT 續行：從外部備份複製 M1–M4 的驗收世界 `QC-accept-WM4`。只用複本，不碰備份本身。
   - 寫預期之前，先以唯讀方式讀出三座艙的收據（候選類型 SOURCE／NEW／EXISTING）與供電狀態，再依收據寫預期。
   - 進入有 DORMANT 收據、已供電的艙，通道照原收據開啟（SOURCE 則是一般開門）。
3. 多人：兩個 client，一人跨過、一人留下。
4. 光影開啟時穿過量子膜。
5. 在目的宇宙重新登入，以及重啟 server，都回到同一個宇宙。

SOURCE 與 EXISTING 由 GameTest 覆蓋；人工 smoke 若剛好抽到，就記錄下來。

## 19. 完成判準

**可以宣稱完成，需要全部成立**：

- 所有 task 與 whole-branch review 的 Critical／Important 都為 0。
- `run-all.ps1`（含 M5 probe）全綠。
- GitHub Actions 雙平台通過。
- F1 與 required manual smoke 都已記錄在 tracked note。
- docs truth 已完成。

**不能宣稱完成的事**：雙向通道或正規回程、Nether／End、在當前宇宙重生、全域投影、多組 cohort 的完整重逢語意。

以下都不能當作完成的證據：只有 embedded connection 的 server-side 傳送、編譯成功、region 檔存在、同一個 JVM 讀回記憶體值。

## 20. 獨立 spec review 的處置

### 20.1 第一輪（v1 → v2）

| Finding | 處置 |
| --- | --- |
| C1 COLLAPSING 期間被困住 | §7.3：返還與清理階段門維持一般狀態；PREPARE 才上量子膜，而且門口沒有實體時才套用；PREPARE 期間參與者關門即結束（設計 §20.4）；§2 補上不變量 24 的例外 |
| I1 跨 session 唯一性 | §5：M5 狀態只佔用尚未返還的參與者；§7.1 改為邊緣觸發 |
| I2 以 MEASURED 為條件的分支 | §5 的 `receiptRetained` 謂詞；§13.2 呼叫點表；§12 新增斷線與 JOIN 欄 |
| I3 物化失敗無法重試、開服停止 | §8.3 改為 `DISABLED`＋重啟後重試；§6.3 同 JVM 不重試；§2 已知限制 |
| I4 重試忙迴圈 | §11.2 原因分類、邊緣觸發、指數退避 |
| I5 投影耐久性 | §9.2 COMPLETE 之前先做原生存檔 checkpoint；§12 W4b |
| I6 驗證與回滾仍含安全檢查 | §10.4 明定三個階段都不採用 |
| I7 觸發未檢查供電與 enabled | §7.1、§7.3 的閘門；§15 測試 |
| I8 取消路徑沒有測試 | §11.3 改為一觀察到 DORMANT 就取消；§12 修正對應列並新增 W10；§15 |
| Minor M1 保護熱路徑與註冊順序 | §9.3 記憶體索引、註冊順序、退化規則 |
| Minor M2 方塊實作 | §10.1 逐狀態覆寫、client render layer；§7.3 門口沒有實體才套用、下一個 tick 套用 |
| Minor M3 門切換只改 OPEN | §10.1、§10.5 |
| Minor M4 狀態邊用語 | §5 |
| Minor M5 終局失敗語意、提交前提 | §5、§7.1 |
| Minor M6 catalog 損壞 | §12 |
| Minor M7 原版傳送門回程 | §2、§18 |
| Minor M8 預載範圍 | §9.1 |
| Minor M9 readiness 第 7 項、compatibility note | §10.2、§16 F7 |
| Minor M10 Origin 拆除與投影衝突 | §9.2、§9.3 |
| Minor M11 測試 fixture、順序依賴的位置 | §15、§4 T1 |
| Minor M12 catalog ordinal 與 dirty | §6.2 |
| Minor M13 lifecycle 查詢 API | §6.3 |
| Nits | RFC 9562（§6.1）；Nits 列（§17）；§1 流程補上 (d)；`/tick freeze`（§2）；smoke 第 2 項先讀收據（§18）。篇幅：本文同時是 spec 與交接依據，所以保留完整的狀態與呼叫點表 |

### 20.2 Re-review（v2 → v3）

| Finding | 處置 |
| --- | --- |
| N1 discovery 時機與遺失判定矛盾 | §6 改為明確的建檔順序（先 projection 空檔，再 allocation entry），遺失判定只依這個順序推得的證據；§6.1、§6.5、§8.2 同步；§12 新增 W11；§15 新增判定表。（v4 改用 schema 4 envelope 作為證據，見 §20.4） |
| N2 `recordFor` 可能挑錯 record | §5 定義單一的 `occupies` 謂詞；§13.2 新增 `onJoin`／`onDisconnect`／`recordFor` 列；§15 新增測試 |
| N3 暫時性 IO 被當成永久 | 拆成 `AUTHORITY_CORRUPT`（永久）與 `AUTHORITY_UNREADABLE`（重啟後重試）；§6、§9.3、§11.2、§12 同步；退化保護在讀取成功後自動解除 |
| m1 runtime 物化失敗的記錄 | §6.3 |
| m2 退化保護不是完整超集 | §9.3 改寫用語，並記為已知缺口 |
| m3 PREPARE 中途閘門失效 | §7.3：釋放 ticket、門恢復一般開門、退回 WAITING |
| m4 非參與者被困 | §7.3、§2 記為已知限制。（v4 改為不會被困，見 §20.4） |
| m5 關門時門口有人 | §7.3 的門口規則涵蓋所有會讓門有碰撞的轉換 |
| m6 catalog dirty 與還原 API | §6.2 |
| m7 重試時 crash 會停服 | §8.3、§2 |
| m8 行號標註 | §13.2 把 `:203` 與 `:323` 分列 |

### 20.3 第三輪（v3 修正）

| Finding | 處置 |
| --- | --- |
| N4 建檔會蓋掉遺失的證據 | §6 的建檔步驟改為以遺失判定為前提，判定為遺失時不建檔；§8.2 第 1 步同步；§15 新增「刪除 ledger 後不重建」的測試。（v4 的初始化規則保留同一個保證：schema 4 之後不建檔） |
| Minor：PREPARE 的參與者條件是否持續 | §7.3：只是進入 PREPARE 的條件，不是持續條件 |
| Minor：discovery 追加失敗、`changeAvailability` flush 失敗 | §6.4、§8.3：還原 current，並以既有原因進 SEALED |
| Minor：「某個」推導值的用語 | §6 改為「本 session 的 NEW 推導出的 UniverseId」 |
| Minor：關門時門口有人 | §10.6、§11.2 加註，依 §7.3 改為一般開門 |

### 20.4 第四輪（使用者轉交，`d9b3249` → v4）

逐項對照 `a7e1be2` 的程式碼核實，9 項都成立。

| Finding | 核實 | 處置 |
| --- | --- | --- |
| Critical：回滾失敗後不可刪除 recovery authority（原 §10.3） | `FAILED_RECOVERY_REQUIRED` 代表原生移動後驗證失敗、回滾也沒有成功（`main/universe/UniverseTransferService.java:183-216`），玩家實際位置未確認；原文直接終局，會刪掉唯一的 record | §10.3 改為 PASSAGE_READY→SEALED(`TRANSFER_RECOVERY_REQUIRED`)，永久、保留 `Crossed` 與 opened／expires；§5 新增狀態邊與合法組合；§11.2、§12、§15 同步；SEALED 寫入失敗時的記憶體內關閉與三重故障窗口寫入 §10.3 |
| Important：首次 M5 升級會誤判 allocation ledger 遺失（原 §6.1） | 遺失證據原本依 discovery 非空；M4 時期 discovery 已非空的存檔（fixture、驗收世界）第一次 NEW 就會永久 `AUTHORITY_CORRUPT`。同類問題：只有 EXISTING 造訪過的存檔，projection ledger 被刪後會被當成空檔重建 | §6 改用 M4 既有做法：journal 的 schema 4 envelope 是 M5 authority 初始化的不可撤銷證據（`SessionRecoveryState.java:38-39`）；初始化在開服時補建空 ledger；§5、§6.1、§6.5、§8.2、§12（W11 改寫）、§15 同步 |
| Important：PREPARE 提前關門可能消耗尚不存在的 NEW Universe（原 §7.3） | 原文的配置在 PREPARE 內進行，PREPARE 一開始就關門會終局，NEW 收據被消耗、宇宙卻從未建立 | 新增推導階段 RESOLVE，排在 PREPARE 之前、不需要供電；§1、§5、§7.3、§8、§11.1、§15 同步 |
| Important：現有 gateway 無法讓比較器輸出 15（原 §5） | `Presence` 只有 NONE／ARMING／ACTIVE／RETURNING／UNKNOWN，`reconcile` 把 ACTIVE 對應到 ARMED（`ChamberSessionGateway.java:10`、`ChamberPowerCoordinator.java:139-141`） | §10.7 新增 `Presence.PASSAGE` 與對應；§5、§10.2、§13.2、§15 同步。（v5 補上沒有 runtime 時的推導與 `reconcile` 的 pending 路徑，見 §20.5） |
| Important：只還原記憶體不能收斂 checked write 失敗（原 §6.2） | catalog 寫入在 atomic move 之後才讀回（`UniverseCatalogStore.java:36-42`），讀回失敗時磁碟已是新內容 | §6 新增「checked 寫入失敗的收斂」：還原並保持 dirty、未確認期間不做新決策、列出每一種已落地內容在重啟後為何合法；journal 寫入失敗的處理；§12 新增 W12。（v5 改為 roll-forward 與 restore 兩個方向，見 §20.5） |
| Important：Discovery idempotency 需要驗證既有 record（原 §6.4） | 原文「已存在就不改動」，會接受 `gameplayEligible=false` 或不屬於本 session 的紀錄 | §6.4 驗證 `gameplayEligible` 與 `firstObserver`；§6 新增「既有紀錄的驗證」，同樣套用到 allocation、catalog、projection |
| Important：Projection 覆寫未定義既有 entity 的處置（原 §9.2） | 原文只定義方塊與 block entity | §9.2：有玩家就等待、絕不寫在玩家身上；非玩家 entity 不處理、交由原版；§2 已知限制 |
| Important：Fail-closed 不應無限困住非參與者（原 §2） | 原文接受「預載卡住＋全員離線＋外人在艙內」時外人被困 | §7.3：艙內有非參與者時不套用量子膜；sealed 期間出現在艙內的非參與者可以把門改回一般開門；刪除 §2 的對應限制 |
| Minor：單人 smoke 無法等到 60 秒到期（原 §18） | 單人跨過後即符合 (b) 全員跨過，通道立即結束 | §18 拆成「跨過」與「到期」兩輪 |

### 20.5 第五輪（獨立 review，`2c24d51` → v5）

v4 的 9 項中 6 項關閉、3 項部分關閉，另新發現 Important 3、Minor 10。controller 對照程式碼核實 I-1（`SuperpositionSessionManager.java:73`、`ChamberPowerCoordinator.java:117-137`、`ChamberProtectionService.java` 只在 OFF 放行、`ChamberControllerBlock.java:106-111`），三項 Important 都成立。

| Finding | 處置 |
| --- | --- |
| I-1 沒有 runtime 的 M5 session 拿不到 PASSAGE；`reconcile` 的 pending 路徑會在供電中結束通道 | §5、§10.7：新增 `Presence.COLLAPSE`，M5 狀態在 runtime 判斷之前只依 collapse view 推導；`reconcile` 對 M5 presence 不走 pending、不呼叫 `returnToOrigin`，power state 只設 POWERED 或 RETURNING（保護不解除）；M5 的 `returnToOrigin` 沒有副作用；(d) 與閘門失效只由 `CollapseService` 依即時訊號判斷（§10.6）；§13.2 改列 `:66-76`、`:315-345`；§15 新增沒有 runtime 的 DORMANT 續行到 15 的測試 |
| I-2 TRR 的 SEALED 寫入失敗時，收斂會把它蓋回 PASSAGE_READY | §6 的 roll-forward 涵蓋 journal 進入 SEALED；§10.3 改寫：保留 SEALED 為 current、不 CAS 還原、依 collapse view 立即關閉通道；只剩「磁碟仍是 READY 且任何寫出成功前就 crash」的窗口 |
| I-3 收斂一律還原，對 append-only 與保守方向的權威是錯的 | §6 把收斂分成 roll-forward（allocation、catalog 配置與 `DISABLED`、discovery、projection、journal SEALED 與 `Crossed`）與 restore（COLLAPSING／READY 提交、終局、SEALED→COLLAPSING、改回 `EAGER_ENABLED`）；§6.2 不再需要新的還原 API；§6.4、§8.3 同步；W12 加上 discovery |
| m1 殘留與矛盾 | §6.2 dirty 用語；§8.3；§11.2 重試改為 RESOLVE 與 PREPARE；§13.2 寫明 RESOLVE 不受斷電影響；§3 改寫確認狀態；§2 補 `PROJECTION_CONFLICT`；§11.2 狀態用語；§13.2 補 `ChamberControllerBlock` 的 switch |
| m2 已落地表不完整、權威退避 | §6 表補 projection PENDING、改回 `EAGER_ENABLED`、COLLAPSING 提交與 SEALED→COLLAPSING；權威收斂使用以權威為單位的退避 |
| m3 schema 寫出規則歧義 | §5 改為明確式子：schema 4 只在 `candidateInitialized` 為真時出現 |
| m4 「每個 JVM」 | 改為每個 server instance，並定義單人重開世界也算重啟 |
| m5 journal 遺失或回退 | §6.5 新增實體交叉檢查（進 `PROJECTION_CONFLICT`）；§2 記為已知限制 |
| m6 初始化失敗分類不一致 | §6：任何原因的初始化失敗都不提交新的 COLLAPSING |
| m7 TRR 欄位 | §5 新增 `RecoveryRequired`；欄位規則改為雙向「必須」 |
| m8 投影的玩家檢查範圍過寬 | §9.2 只檢查這次要寫入的 cell |
| m9 RESOLVE／WAITING 期間關門 | §3 新增詮釋 3：binding 從 PREPARE 開始 |
| m10 外人在艙內時沒有 sealed 量子膜 | §2 已知限制；§15 新增「外人在艙內仍能提交 READY」 |

### 20.6 第五輪 re-review（`eb914e3` → v5.1）

I-1～I-3 與 m1–m10 都已關閉。新發現 Important 1、Minor 7；plan review 另指出 §13.1 hook 用語與 M4 斷言矛盾。

| Finding | 處置 |
| --- | --- |
| N-A roll-forward／collapse view 與 §7「寫入前 current 必須等於 flushed」矛盾 | §7：有尚未確認的 roll-forward journal 寫入時，該 session 只重試同一份寫出，跨越暫停、結束條件與終局延後；`CollapseService` 改為讀 collapse view；§6 的 collapse view 同步；§15 新增測試 |
| n1 終局後殘留 runtime 與來源 ticket | §5：終局同時移除 runtime 與 ticket；attach 只為 COLLAPSING 建立 ticket；W7 斷言原艙可以再開 session |
| n2 「已供電」的定義 | §7：一律指即時紅石輸入，不看 `ChamberPowerState` |
| n3 `m5Initialized` 兩種意思 | §5 拆成 `m5Evidence`（schema）與 `m5ReadyThisInstance`（提交）；§7.1、§13.2（補 `:78-85`）、§15 同步 |
| n4 COLLAPSING 進度寫入的分類 | §6：列為 roll-forward（沿用 M4 現行做法） |
| n5 TRR 的不可變性 | §5：SEALED(TRR) 完全凍結，`RecoveryRequired` 只寫一次 |
| n6 投影方塊寫在 PENDING 確認之前 | §6：投影方塊只在 PENDING 確認之後寫；§6.5 的判定改為「投影 Controller 位置已有 Controller 方塊」；§2 用語 |
| n7 門狀態依 durable | §7.3：依 collapse view |
| plan review I4：hook 用語 | §13.1：hook 改為「暫停」語意，提交失敗 fallback 另由 M5 測試覆蓋 |

v5.1 的輕量確認：Critical 0、Important 0、Minor 5（p1–p5），controller 直接修正：

| Finding | 處置 |
| --- | --- |
| p1 暫停期間的開關門 | §7：仍由 `CollapseService` 攔截；PREPARE／READY 一律拒絕、不改門 |
| p2 schema 4 的升版用語 | §6「升到」；§12 補上 `candidateInitialized` 條件 |
| p3 COLLAPSING 進度寫入的引用 | §6 表改引 `SessionRecoveryManager.java:168-169`、`:93-108`，並說明真正的收斂來源 |
| p4 §13.1 的類比 | 改為「guard 未閒置時延後提交」 |
| p5 SEALED 期間的來源 ticket | §5：進入 SEALED 時釋放，重試時再取得 |

### 20.7 H2 實作的 spec review（v5.2 → v5.3）

| Finding | 處置 |
| --- | --- |
| Important：§4 H2「無關 chunk 的存檔失敗不再阻擋」在原版語意下不是無條件成立。原版 flush 時仍 pending 的寫入失敗，會由 `completeAll().join()` 讓 `server.save` 直接丟例外（javap 驗證 `ServerWorld.save`、`MinecraftServer.save` 沒有 catch） | §4 H2 改為明列「不再阻擋」與「仍 fail-closed」兩類，並把原版 pending 寫入記為界線；F4b 投影沿用同一元件與界線 |
| Minor：region 檔 header 由同一 region 的 chunks 共用，無關 chunk 寫 header 失敗可能波及相關 chunk | §4 H2 把判定單位改為「同一 storage 的同一個 region 檔」，偏 fail-closed |

### 20.8 使用者裁示：到達目的宇宙的提示（v5.3 → v5.4，2026-10-08）

- 背景：使用者實際試玩時，穿過量子膜後已經被傳送到目的宇宙，但地形與原世界相同（§2 :44），身邊也沒有任何提示，所以看不出自己已經換了世界。
- 裁示：跨越成功之後，對**到達的那位玩家**以 actionbar 顯示到達提示，並依候選種類區分：
  - NEW：「你已進入新的宇宙。」
  - EXISTING：「你已進入另一個宇宙。」使用者選擇區分這兩種，這樣重逢到既有宇宙時，字面也正確。
- 規則：
  - 只在傳送結果是 MOVED、而且玩家已在目的世界時顯示；
  - 不顯示 Universe ID；
  - 用 `Text.translatable`，key 放在 `message.quantumchamber.m5.*`，zh_tw 與 en_us 兩份語言檔都要有；
  - `FAILED_ROLLED_BACK` 與 `FAILED_RECOVERY_REQUIRED` 不顯示這則提示。
- 不受影響的部分：其他參與者的訊息與比較器。
