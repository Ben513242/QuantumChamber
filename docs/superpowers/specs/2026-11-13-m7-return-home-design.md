# M7 正規回程：binding spec（v1.3，save邊界技術澄清待窄確認）

日期：2026-11-13。基準工作樹：`codex/m7-return-home`，從 main `2255027` 開出。
狀態：v1.0玩法/資料契約已第三輪C0/I0/M0採用，v1.1驗證平台勘誤亦曾採用。v1.2僅為使用者新裁示的T1 M6同等驗證收斂修訂，原同席C0/I0/M1後採用；玩法不改，本文不是實作/測試/人工/整合結果。
v1.3狀態：2026-11-14真user巡檢明確canonical save邊界；僅本技術澄清/測試矩陣待原同席窄確認，不重開其他已採用玩法或資料契約。來源原文見M7 note最上方「fix3只守canonical save內」。本文不代表fix3已實作/驗證。
範圍唯一來源：[M7 note](../../implementation-notes/2026-11-13-m7-return-home.md) 的使用者裁示。
未明列改變的 M2／M4／M5／M6 契約維持；建議稿不是另一份 binding spec。

## 2026-11-13 T1驗證收斂勘誤（原同席C0/I0/M1後採用）

使用者原文見[M7 note「T1收斂、M6同等」](../../implementation-notes/2026-11-13-m7-return-home.md)。本次取代v1.1的T1 PoC18/T2T3守門/退休流程：T1只JUnit大小上限/硬link＋GT真入口守門＋CI接線，取消全部nativePoC；T4 Windows正式39不變，沿已驗M6 PID+start/receipt hash/readonly summary+SelfTest，不另造更嚴框架。Ubuntu仍JUnit/GT，沒有native/main-only/Linuxowner。超M6驗證要求列殘留不擋、不默認轉T4；基本失敗原root/XML/log保全仍拘束。schema6/HomeJournalBudget等正式設計義務不改，I2固定lease預留反證移T2，不能以取消T1 test當作已解。玩法/資料/恢復矩陣及V/H要求不變，僅驗證階段/標準/平台依新裁示收斂。

## 1. 先回答建議稿 §15.3 七題

| 題 | 本規格的可實作答案 | 主要證明 |
| --- | --- | --- |
| 1. B 如何證明同 Chamber 的 A？ | 用 checked COMPLETE projection ledger 的 exact `(ChamberUuid, UniverseId, OVERWORLD, ControllerPos, OutwardFacing)`，交叉核對 live B、catalog、既有 ORIGIN registry 及 A 實體；不從 B 的 BE UUID 配身分，不呼叫 `registerOrigin`。 | §4、V02／V03 |
| 2. Root 與 Entry 如何分開？ | 新版本列同時保存 `RootAnchor=A`、`EntryEndpoint=B`；參與者快照、來源票、走廊返還及門操作全部使用 Entry。HOME 才用 Root；SOURCE 原意不變。 | §3、§5、V04 |
| 3. canonical A 如何成為 target？ | 新 `HOME` 目的分派用 `server.getOverworld()` 與 exact ORIGIN 權威；catalog `UNIVERSE` 分派維持原路。HOME 不建立 descriptor、UniverseRecord、allocation、discovery 或 projection。 | §8、V07 |
| 4. 何時取得唯一使用權？ | 在 server thread、A 暫載票取得前，先以既有 `sessions` 的 chamber key 放入 provisional owner；checked ARMING 接手持久所有權。journal current／flushed 舊收據、CloseIntent及未確認清理也阻擋各端。 | §6、V05 |
| 5. 各 crash 邊界如何續行？ | 沿 M5 checked／roll-forward／restore、native move→Crossed→playerdata 契約；未選回 Entry，已選不換 HOME，READY與終局收據重啟均不重播。HOME 不經物化；保留既有 playerdata 窗口。 | §9、§10、V10 |
| 6. A 的票誰持有、多少、多久？ | HOME 協調器單一 owner，沿既有覆蓋計算與 pin accounting；每 SID 最多一份、4 個 chunk refs，server 最多 64 份／256 refs。檢查／準備各 200 ticks，通道僅原 1200 ticks；全部釋放路徑見 §7。 | §7、V06 |
| 7. 舊資料與版本如何分派？ | journal envelope schema6、單SID歷史receipt schema1；舊active列仍走schema5 exact形狀，新列明示`RecordVersion=2`、`TravelCapabilityVersion=1`；舊reader保持、未知形狀拒絕，FamilyCapabilityVersion=0。 | §5、V01／V11 |

上表的資料與 owner 分工是 **controller 設計提案 [D]**，不是使用者逐欄指定。
七題須由下面矩陣的小型測試證明後，才可放寬 projection activation guard。

## 2. 來源、交付與不做範圍

來源標記：`[Nn]`＝M7 note「範圍裁示」第 n 題；`[Na]`～`[Ne]`＝第14題採用的 a～e；
`[I]`＝note「沿用與推論」，沒有單獨逐題核可；`[D]`＝controller 技術設計提案；
`[R1]`＝note「Codex 接手／新增玩法裁示：選定後 A 被停用或拆除」：停止回程並保留選擇；
`[R2]`＝note「追加玩法裁示：停止回程的終局與 A 保護」第1題：收據保留作紀錄、解鎖、另開新旅程；原說明的重新喝藥由[R4]釐清；
`[R3]`＝同日補充「維持保護到這趟收尾」：B旅程期間A原艙維持保護，停用仍可切換。
`[R4]`＝同日釐清「可沿用未到期藥效」：不強制再喝一瓶，舊SID結束，重新操作以新SID出發。
引用 M5 的地方指 [M5 spec](2026-10-01-m5-collapse-passage-design.md) 現行契約；
family 範圍沿 [M6 spec v1.7 §1](2026-10-24-m6-universe-generation-design.md)。

| 玩家可見條款 | 本次交付 | 來源 |
| --- | --- | --- |
| B 的既有投影可以回家 | 代理同一 Chamber，家永遠是 A 原艙；不在 B 註冊新 Origin。任何本艙合法投影端都適用。 | [N1][Nc] |
| 操作路徑 | B 供電、全員具 QuantumState、完整入艙並關門→走廊→唯一 HOME 門→回 B→B 量子膜→A 原艙同座標。 | [N2][N4][I] |
| M7 B 走廊 | 只有一扇可辨識 HOME 門，一般門位置是牆，沒有「即將開放」文字。正常 A 新 session 繼續既有探索。 | [N3][N7][D：限縮套用於 B] |
| A 的條件 | B 啟動先驗 A；選 HOME 再驗 A 即時電位、enabled、身分與結構。未載入是 UNKNOWN，先有界暫載。 | [N4][N5][N6][Na][Nd] |
| 選定後 | A 斷電不影響本趟；A 停用／拆除／結構失效則結束本趟，保留 HOME 終局紀錄、解除占用。已跨者不回拉，未跨者留 B；新SID可沿用未到期藥效。 | [N5][R1][R2][R4] |
| 取消與藥效 | 未選定 HOME 的走廊取消、牛奶或任一凍結成員藥效到期，整組回 B；藥效不退款。 | [Nb][I：沿 M2] |
| 隊伍與膜 | 只凍結一起進艙者；膜約60秒，跨過的人到 A，未跨者留 B。選定後沿 M5，不再以 Buff 失效撤銷收據。 | [I：沿 M5] |
| A 的既有內容 | 失效時不重建 A；成功時同樣不寫／清空 A 的艙體或內容，不執行 FIRST_WRITE 或修復。 | [N6][I] |
| 多端並行 | 同 Chamber 全部端點合計只能一趟；舊 SEALED／DORMANT 也佔用該 Chamber。 | [N8][Ne][D：沿既有鎖] |
| 原艙保護 | B旅程收尾完成前A仍受保護，即使A斷電；不讓一般玩家拆除，管理員停用仍觸發停止。 | [R3] |
| 提示與外觀 | 只補回程需要的提示；7×7×7及一格外殼不變，HOME 門僅做最小區別。 | [N9][N10] |

不做：B 的 SOURCE／EXISTING／NEW 門、A→B→C、在 B 新蓋 Origin、自選航路、永久共用走廊、
任意 entity／載具旅行、可逆佔位、完整提示／探險紀錄、外觀改版、專屬 Nether／End、世界池卸載。[N3][N10][N15]
`TravelCapabilityVersion=1` 僅表示 COMPLETE projection 可走 HOME；不表示 M8 或 family 能力。[Ne][D]
人工代操作、main 合併、tag 與 worktree 退役仍各自另問；正常 push 授權及 spec／plan 審查清零後續行依本次流程指示。

## 3. 身分、責任與路由 [D]

```text
RootAnchorRef = A 的既有 canonical OVERWORLD ORIGIN
EntryEndpointRef = 本趟 B 的 catalog OVERWORLD COMPLETE projection
HOME = A；SOURCE = 本趟 Entry（B），M7 的 B 走廊不提供 SOURCE
```

- Chamber UUID 由既有根錨身分及 checked ledger 取得；位置相同、seed 相同或 BE 外觀相同都不足以取得身分。
- `SessionSemantics.LATERAL_BUFF_MAINTAINED` 繼續只管藥效與方向；不得靠改 enum 舊值的意思偷換 Root／Entry。
- 舊 `SessionRecoveryRecord.origin()` 仍是它原有的來源；新列用顯式 Entry accessor，禁止把動態 B 塞進只允許 canonical ORIGIN 的舊建構子。
- journal owner、operation guard、返還／幾何 checkpoint、原生移動與比較器可共用；新 HOME 列採獨立驗證分支，不偽造候選 ledger。
- `globalChamberBusy(chamber)` 只判斷互斥；`localPresence(endpoint)` 判斷哪一端擁有本趟門與來源條件。兩者不得混用。
- B 的門、膜、來源供電／結構與返還由 B owner 處理。A 的協調不能以 B 的 PASSAGE presence 在 A 開膜、關膜或呼叫 `returnToOrigin`。
- A回報busy並維持原艙保護到checked收尾；B session的A斷電不得經一般ORIGIN協調分支被間接取消。[R3]
- HOME route 不呼叫 `DestinationResolver` 的 NEW／EXISTING 或 `ProjectionWriter`；原 catalog route 保持原 exact 門檻。

## 4. 啟動與固定 HOME 門 [D；玩法來源見 §2]

### 4.1 B activation

1. server thread 核 exact live B world、Controller instance／block／facing，B 艙結構、供電鄰域 FULL、即時供電、封艙與 cohort 資格。
2. 健康且已確認的 projection ledger，以 B 的 exact Universe／Controller site 找到唯一 COMPLETE row；PENDING、歧義、索引退化或 authority 未確認均拒絕。
3. B catalog descriptor 必須是該 Universe 的 OVERWORLD、EAGER_ENABLED，lifecycle 解析出同一 live world。B 不得僅憑可推導的 world key 冒充 catalog authority。
4. 核根錨 registry 的 exact Chamber UUID、canonical OVERWORLD、ORIGIN、位置／朝向且未 destroyed；再依 §6 原子取得 provisional owner，才取得 A 檢查票。
5. A 未載入先顯示「正在檢查原艙…」；有界載入後讀 actual Controller／原 registry 身分、enabled、結構與即時電位。失敗按原因拒絕，沒有幾何、效果扣除或玩家移動。
6. 檢查完成後，在相同 SID／owner 及 operation guard 下重驗 B、全部 cohort、藥效、電位與關門；此時才凍結快照、租走廊、checked ARMING。
7. ARMING 讀回確認前，不建立幾何、不傳玩家、不宣稱 session 已建立。其後沿既有準備與 checked SUPERPOSITION 交易入場。

不能只刪除 `ChamberPowerCoordinator` 的 PROJECTION／role 防線。
`projectionControllerAt()` 包含 PENDING，只供辨識，不是 activation 允許票。
目前 projection BE 的 Chamber UUID 可以為 null；不得寫入新 Origin UUID，也不得依它認根。
投影 activation 新路徑須同時覆蓋 readiness、session source authority、return、door interception、load-sync、保護與提示。

### 4.2 B 走廊與選擇

- 固定門為 `HomeDoorKey=(SessionUuid, LogicalStationIndex=1, WallSide=POSITIVE_LATERAL)`；station0保留lateral入口overlay，不得作HOME。入口返還門維持既有操作邊界。
- 只有此 key 產生完整 HOME 門；其餘側門 cell 全是走廊牆，左右延伸／換頁不再生成另一扇 HOME。
- 幾何 builder、`refreshCandidates`、互動處理與換頁三者共同使用 HOME mode；不得只藏模型而仍背景追加一般候選。
- HOME 不進 `CandidateResolver`，不消耗 entropy、不抽機率、不追加 M4 ledger；DoorKey 是權威 key，不靠 client 外觀識別。
- 完整門互動必須是該 SID exact 在線、存活、非旁觀的 frozen cohort，runtime 仍為 SUPERPOSITION、尚未選擇且 B 來源權威成立。
- 互動先取得 A 選門檢查租約；讀取 A 的 enabled／身分／結構／即時電位。未通過時保持未選定，門鎖住並提示原因。
- A暫載完成僅表示資源FULL；回調排回server thread，在唯一operation guard內重取原互動的exact MappingRef／generation／實體門owner及25格完整門，禁止同key remap後沿用舊請求。
- guard內再次核原player instance、world/entity identity、存活／非旁觀／未removed、bbox完整在唯一當前mapping且仍可互動原門、全部cohort仍有效Buff且未Returned、ready leases、current=flushed=SUPERPOSITION未選定，以及B/A身分／B供電封艙與A此刻enabled／結構／即時電位；全通過才checked MEASURED＋HOME。
- 取消、藥效到期、玩家離線、B 失效或另一個已確認狀態讓檢查失效時，舊結果只釋放票，不可晚到提交或重開 session。
- checked HOME 是 first-wins；同 tick 重入或其他參與者後到只讀同一收據，不能改 Root、Entry、cohort 或選門者。

## 5. journal schema6 與 exact 資料契約 [D]

### 5.1 分派與升版

- schema6 envelope恰為 `{SchemaVersion:int=6, Records:list<compound>}`；歷史不加入熱journal，獨立格式見§5.3。
- schema1–5 仍由原版本 reader 讀取；其合法性、legacy return-only、candidate 及 M6 intent 分派不放寬。
- schema6 row **沒有 `RecordVersion`**：必須恰為現有 schema5 的12個 keys，交原 schema5 decoder；只能是有 candidate context 的舊列。
- schema6 row **有 `RecordVersion`**：型別必須 int 且值為2，keys 必須恰為 §5.2；不接受缺／多欄、不回退舊 reader。
- 新列不能出現在 schema1–5；新舊欄位拼裝、未知版本／enum、錯 type 一律使 journal fail closed，不猜預設。
- 新 HOME 與舊 candidate 列可同存 schema6，所有列共用 chamber／player／slot ownership 驗證。仍有 schema1／2 legacy 列時，拒絕首次 HOME，先完成原 return-only；保留原本禁止 legacy／candidate 混裝的界線。
- 歷史不參與active ownership；receipt與active同SID並存僅允許active已有exact對應CloseIntent，絕不可因receipt存在重建／傳送session。
- 首次 HOME checked ARMING 前，必須已完成本 instance M5 authority 初始化且無其他未確認寫入；checked 升6只改 envelope／新列，不改舊 session 的值或意思。
- schema6不得含HomeReceipts或其他未知欄；readback／pending-write比對完整wrapper、envelope及active列，CloseIntent也屬authority，不能只比刪除SID便宣稱整個close完成。
- 舊 schema3 列轉為既有12欄時，`Collapse` 依原升版規則為空；其餘原欄保持。已確認 schema6 永不降版，空 journal 也保留6。
- 升6使用既有 checked atomic write、provenance、exact readback 與未知結果收斂；不能只改 serializer 的版本常數。舊 build 讀6必須拒絕，README 要明列不可降版。
- schema6及單SID receipt都以同一handle擷取的同一份bytes做版本probe、raw鍵檢重、NBT解碼、strict驗證及exact readback；NBT tracker固定16MiB、容器深度≤512，禁止unlimited reader。schema6 wrapper／data／新HOME所有內層與receipt所有compound原始重鍵皆拒絕；舊row保持既有raw檢查而不放寬。
- 所有共用journal writer須維護16MiB預留不變量：`實際整份用量＋各active HOME尚需最大成長餘額≤16MiB`，包含SELECTED、StopReason、25個Crossed、1個RecoveryRequired及最長合法CloseIntent／Evidence；任何舊候選append、其他session或schema升版都不得吃掉此餘額。
- **ARMING／SUPERPOSITION未選定**：計入合法remap尚未退休的舊＋新lease聯集，不得假設lease只減。admission直接保留既有allocator每SID至多64個slot的保守上界，以同一tracker實測每個exact `SlotId:int＋Bounds:int[6]` lease row與list祖先成本；換頁、checkpoint退休前後都在此上界內，其他writer不能借用未實用的lease餘額。
- **已選定後**才依其已凍結lease集合計只減不增，並保留後續stop／close最大餘額；RETURNING也只減。remap配置／提交前核共享預留已納入新舊聯集；不符時保原mapping／SID及close餘額並受控pending，不得以超限flush進fail取消已接受的團隊。
- admission在checked ARMING前以正式編碼／有界decode量測該不變量，所有後續寫入再核。不得用筆數、壓縮檔長度或估計字串長代替；shared wrapper/list祖先成本納入。固定來源快照後可快取已驗上界，但每次共用寫入要重新核實總額。plan須以定向spike固定計量器與邊界fixtures，再實作activation。
- admission另驗單SID最壞receipt（含獨立wrapper、SID與CLOSE evidence）也能在16MiB解碼；歷史不占熱檔預留。量測列舉合法有限形狀，或提供經同一tracker驗證的保守上界；不能以語義非法的合成row通過strict preflight冒充可寫。
- `TravelCapabilityVersion` 不從 envelope／目前 runtime 猜；新列固定1。`FamilyCapabilityVersion` 不新增到 HOME 假資料，既有 M6 欄位仍固定0。

### 5.2 新列 exact keys

| 欄位 | NBT 型別／限制 |
| --- | --- |
| `RecordVersion` | int，固定2 |
| `SessionUuid`、`ChamberUuid` | 各為原生 UUID int[4]；SID 非重用 |
| `RootAnchor` | compound，exact keys：`ChamberUuid`、`WorldKey`、`Role`、`ControllerPos`、`Facing`、`InstanceKind`；型別同原 `Origin`。WorldKey=`minecraft:overworld`、Role=`OVERWORLD`、InstanceKind=`ORIGIN`，UUID等於外層。 |
| `EntryEndpoint` | compound，exact keys：`ChamberUuid`(UUID)、`UniverseId`(UUID)、`WorldKey`(canonical string)、`Role`(string=`OVERWORLD`)、`ControllerPos`(int[3])、`Facing`(水平 enum string)、`InstanceKind`(string=`PROJECTION`)；UUID等於外層，位置／朝向與 Root 相同。 |
| `Participants` | 與現有 exact participant row 相同：`PlayerUuid`、`SourcePosition`、`SourceVelocity`、`Yaw`、`Pitch`、`QuantumState`、`Returned`；位置／效果快照取 B。1..25人、UUID唯一、有限值與±0正規化沿原 codec。 |
| `SpaceLeases` | 與現有 exact lease row 相同：`SlotId`(int)、`Bounds`(int[6])；沿原 slot／bounds 驗證與容量。 |
| `State` | 原 `SessionState` enum string；新列合法矩陣見 §9。 |
| `RestoreEntryEffectOnReturn` | NBT byte，固定0；不恢復／退款入場藥效。 |
| `SessionSemantics` | string，固定 `LATERAL_BUFF_MAINTAINED`。 |
| `TravelCapabilityVersion` | int，固定1。 |
| `Home` | compound，exact keys 見下表。禁止 `Origin`、`CandidateContext`、`CandidateLedger`、`CandidateSelection`、`Collapse` 出現在新列。 |

Entry 的 UniverseId／WorldKey 關係必須由持久 catalog exact descriptor 與 live world 重驗，不以字串自我證明。
UUID／int array／浮點／boolean／空 list held type 的 strictness 全沿原 codec；new row 每層 exact，沒有 NBT 寬鬆 coercion。

| `Home` 的 exact 欄位 | 規則 |
| --- | --- |
| `HomeRevision` | int，固定1。 |
| `Selection` | compound：未選恰為 `{Kind:string="UNSELECTED"}`；已選恰為 `{Kind:string="HOME", DoorKey:compound, SelectedBy:UUID, SelectedAtGameTime:long}`。DoorKey 恰為原 codec 的 `SessionUuid`、`LogicalStationIndex`、`WallSide`，且只能等於 §4.2 固定 key；SelectedBy屬 cohort，time≥0。 |
| `CommittedAtGameTime` | long，未 collapse 為-1；首次 MEASURED→COLLAPSING時寫≥0，之後不變。 |
| `PassageOpenedAtGameTime`、`PassageExpiresAtGameTime` | long，尚未READY皆-1；一旦寫入即不變，expires=opened+1200，檢查 long overflow。 |
| `Crossed` | UUID list，唯一且是 participants 子集，只增不減；尚未READY必空。 |
| `RecoveryRequired` | UUID list，只有 `TRANSFER_RECOVERY_REQUIRED` 恰一位、屬 cohort且不在Crossed；其餘必空。 |
| `StopReason`、`StoppedAtGameTime` | string及long；未停止必為空字串／-1。選定後root失效，checked latch為§5.3的ROOT原因與≥0時間，一經設定不可清除；即使A先修復也不復活本趟。 |
| `CloseIntent` | compound，恰為`{Kind:"NONE"}`或§5.3的CLOSE形狀；所有未結案路徑必NONE，一旦CLOSE即凍結整列，不允許再傳送、retry或改任何進度。 |
| `SealedReason` | string，非SEALED必為空；SEALED只能是 `ENTRY_AUTHORITY_INVALID|AUTHORITY_CORRUPT|AUTHORITY_UNREADABLE|TRANSFER_RECOVERY_REQUIRED`，不接受ROOT終局原因。 |

Root、Entry、SID、Chamber、semantics、travel capability與 participant source snapshots 從 ARMING 即不可變。
選定後 Selection完全不可變；Returned只增、lease只可原樣或全清；HOME收據永不轉SOURCE／UNIVERSE。
舊 `sameAuthority`／`occupies` 保留原實作語義；新列獨立驗證相同粒度的單步邊，再由共用 journal CAS／ownership owner提交。

### 5.3 單SID不可變歷史與CloseIntent [D；落實R2]

- 檔案固定`<save>/data/quantumchamber_home_receipts/<canonical-lowercase-SID>.dat`，只按已知SID exact lookup，不全目錄掃描、不載入全部歷史、不設世界終身次數上限。單檔沿§5.1同bytes／16MiB／raw strict契約。
- admission對新候選SID查同名檔，存在即拒絕重用，不靠全歷史索引。檔案／目錄須為已驗canonical非reparse且在指定save內；不是可由client提供的任意路徑。
- **canonical save邊界（2026-11-14真user澄清）**：`HomeReceiptStore`構造時對傳入save根呼叫`toRealPath()`一次，以所得canonical save作保存邊界。save以上祖先可有junction／symbolic link／Windows 8.3短名，**不檢查save以上祖先**；不能以尚未展開的合法拼寫與realPath字面不同拒絕。之後只檢查該save內的`data`、`quantumchamber_home_receipts`及收據檔，任何save內reparse（含junction／symlink）仍拒；固定子路徑、containment、普通目錄／檔案、同handle strict bytes、完整wrapper exact、原子不覆寫均不變。此保存邊界不改worktree／probe的既有路徑安全規則。
- 正向具名證據須在Windows用真`GetShortPathName`和ancestor junction構成原始save路徑，經正式owner發佈及查詢成功；負向是save內`data`為junction仍拒且不寫出save，並以直接owner mutation證守門。其他OS以真實對應的ancestor symlink成功／save內link拒絕等契約與精確集合驗證，不以skip充PASS，不建立新FS/alias或Linux程序管理框架。
- receipt wrapper沿既有`DataVersion:int`＋`data:compound`；data恰為`{SchemaVersion:int=1, SessionUuid:UUID, CloseIntent:compound, Session:compound}`，未知版本／多缺鍵／錯type／原始重鍵均拒絕。
- CLOSE恰為`{Kind:string="CLOSE", CloseRevision:int=1, CloseReason:string, ClosedAtGameTime:long≥0, DoorGoal:string, Evidence:compound}`。reason、合法來源state、證據、時序與DoorGoal完全依§9.2共同矩陣，不可各writer另猜。
- receipt.Session是凍結active整列**僅把Home.CloseIntent正規化為NONE**的canonical快照；外層CloseIntent保留CLOSE，避免遞迴／自hash。SID／Root／Entry／Selection／Crossed／原timing全保留；全Returned且空lease。
- 交接固定：checked latch必要StopReason→返B／清lease→checked CLOSE凍結列→exact B依DoorGoal改門並完成原生chunk checkpoint→checked發佈／exact驗既有單SID receipt→再次確定B門目標與checkpoint有效→checked移除active→釋runtime／票／鎖與M7額外A保護。
- CLOSE先於任何終局門改動；checkpoint必涵蓋B門的相關chunk，普通關門也必真正改OPEN而非因membrane=none直接DONE。PARTICIPANT_CLOSED維持CLOSE_IF_CLEAR_ELSE_OPEN防夾；其他矩陣原因為OPEN。缺載入／exact B不符／checkpoint未知皆pending，不修A、不假確認。
- receipt寫入採同目錄完整temp＋force後以**原子不覆寫**方式發佈（例如同volume硬連結createLink，target已存在只能讀驗exact）；不可用ATOMIC_MOVE會覆寫existing的語義冒充no-clobber。admission先驗存檔volume支援；無支援受控拒新HOME，T1真volume發佈/大小JUnit驗Windows與Ubuntu；取消T1 native PoC，native crash證明由T4正式Windows十三cut×三門態39列負責，Ubuntu集合外項不記skip/PASS。
- 暫存與失敗原件保留；已存在receipt只驗exact，錯配／損壞保鎖交管理員，不覆寫、不刪除。寫入結果不明時只查同SID單檔；receipt成功但active移除失敗仍只從CLOSE續做，絕不再傳送。
- CLOSE與receipt可並存但必exact匹配；每次恢復移除active前均重新確保B門與checkpoint，不依process內舊ack。移除確認後不再從歷史重開門，之後玩家新操作不被舊工作覆蓋。

## 6. 同 Chamber 原子鎖與 local presence [D]

- 唯一 owner 沿 `SuperpositionSessionManager.sessions` 的 Chamber UUID key；擴充其值以表示 provisional／active，禁止另設一份可與它分歧的 mutable busy lock。
- A 原有探索 start 與 B HOME start 都先檢查 runtime provisional、journal current、flushed、quarantine／未確認移除；任一有同 Chamber即拒絕。
- B 在 §4.1第4步以同一 server-thread不可重入步驟占位；從這一刻包括A在內都busy。先占位再加票／等待，不能讓重入各自認為空閒。
- provisional 尚未checked：沒有移動或幾何；拒絕、逾時、條件失效時，先使回調owner失效並釋放票，確認沒有journal／reservation殘留才解除占位。
- ARMING寫出結果不明時，沿原 abort／quarantine／checked收斂；未確認殘留已移除前繼續持鎖，不能只因 start 回傳REJECTED而放行另一端。
- checked ARMING後，持久journal本身是鎖；SEALED／MEASURED DORMANT即使無runtime仍busy。所有端都用同一ownership謂詞，不能只查投影本地BE。
- 只有checked終局，或未選定 RETURNING完整返還＋checkpoint＋租約／journal刪除確認後才釋放；終局失敗保持busy並退避。
- 重啟先完成journal strict load與ownership驗證，再開activation；舊收據和新HOME列一起重建busy，沒有「等玩家上線才鎖」窗口。
- 單SID歷史只讀、不重建busy；active CloseIntent在B門checkpoint、receipt及active刪除全部checked完成前繼續持鎖／A保護，完成後才可依現有資格開新SID。[R2][R4]
- 新列玩家占用沿M5：ARMING／SUPERPOSITION／RETURNING及非DORMANT MEASURED占全部；DORMANT不占玩家；COLLAPSING／READY／SEALED只占尚未Returned者。Chamber仍全程占用。
- 各端比較器只顯示自己的local階段：B READY為15、等待／封存為11；其他端busy為11而不取得門控制權。未核實投影保持0並提示拒絕，不能假裝READY。

## 7. A 暫載與資源生命週期 [D]

- HOME協調器為A票唯一owner；key為`(server instance,SID,用途)`，用途是START_CHECK、SELECT_CHECK或HOME_PREPARE_PASSAGE，同SID最多一份。
- chunk set＝`PassageTransferPolicy.preloadChunks(rootFrame)` ∪ Controller水平±3供電鄰域；去重且最多4個chunk refs。超限屬計算／相容性不符，受控拒絕，不擴大無界半徑。
- 以既有FULL ticket語義加票，每tick用非建立查詢看FULL，不在server thread阻塞`getChunk(create=true)`；供電查詢只在完整鄰域FULL之後執行。
- server同時最多64份／256個root chunk refs，並納入既有4096 coverage／pin預算，不能另外偷加額度；coverage key含exact world＋chunk，A與走廊同XZ不能去重，須擴充既有allocator accounting而非假設原SlotAllocator已算A。不同SID共用chunk仍分別記責任，不能釋放他人的ref。
- START_CHECK／SELECT_CHECK各自最多200 server ticks；UNKNOWN／預載未完成逾時分別拒絕啟動／保持未選定並提示「原艙檢查逾時，請稍後重試」。不把UNKNOWN寫成OFF或DESTROYED。
- HOME準備單次A票最多200ticks；未FULL或UNKNOWN逾時只釋票、保原SID／HOME並進可恢復HOME_CHECK_WAIT，依200→12000ticks退避再有界檢查，不寫ROOT StopReason／CloseIntent。READY的UNKNOWN等待仍沿原expires計時，不因重試加時。
- 選門成功讀回後釋放SELECT_CHECK票；PREPARE另取得目的票。通道最長原1200ticks，停服、超時、離開PREPARE、SEALED或終局均釋放A票。
- acquire部分成功、例外、取消、stale回調、start放棄、roll-forward封存、reload中止檢查與SERVER_STOPPING全部走冪等finally／owner cleanup；資源釋放不等journal落盤成功。
- 已checked收據／busy可以無票保留；SEALED與DORMANT不強載A。HOME_CHECK_WAIT只在符合原出發觸發與退避時重新有限取得；每次逾時都釋放，禁止常駐票／逐tick重加。純UNKNOWN與真正root事件分開，不能把技術等待改成放棄選擇。
- B的來源票及走廊lease仍由原owner持有；新Entry分派只把來源指到B，不雙重取得。所有票均為process資源，不將live token寫進NBT。

## 8. HOME 準備與傳送 [D；沿 M5 passage policy]

- checked HOME之後先沿原保留式返還把全員送回Entry=B，離線者等JOIN下一tick；全員Returned、走廊幾何清理與checkpoint確認後才準備HOME。
- HOME target authority只接受Root exact canonical OVERWORLD、`server.getOverworld()`同一live instance、原ORIGIN UUID／frame／Controller／enabled與結構。取得的live token只限本次server instance。
- 每次PREPARE、READY提交及每一位真正跨越之前重驗A身分／enabled／結構；選後不再把A電位當閘門。A變成UNKNOWN時停止跨越，依§7有界重驗，不能以快取true放行。
- B的PREPARE閘門、sealed膜套用時不壓到entity、非參與者能離開、門操作順序、B即時供電與來源失效處置沿M5；所有source解析都使用Entry。
- READY要求A預載FULL、A合法、B原閘門成立、current=flushed且無未確認authority；checked寫opened／expires後，只有B的門變passable、local比較器15。
- HOME全程不調用A的FIRST_WRITE、COMPLETE修復、interior清空、投影checkpoint或門／量子膜寫入。不得為了READY重建／打開／清空A。
- 不新增UniverseRecord、不新增allocation／discovery／projection row，不變更A或B的seed／profile／availability。既有projection row只讀並重驗。
- 傳送API明確區分`CatalogUniverseTarget`與`CanonicalHomeTarget`；只共用原生移動、exact玩家／source／target核對、逐欄位postcheck與rollback，不捏造UniverseWorldDescriptor讓A走catalog。
- B膜跨越幾何、只限cohort、非騎乘、同XYZ／yaw／pitch／速度、FULL／邊界／高度／有限值檢查沿M5。三個階段都不新增碰撞／流體／地板檢查，不找安全Y。
- HOME採窄typed結果：MOVED攜帶canonical target身分及移動／回滾證據；保留舊`UniverseTransferResult`要求非null `UniverseTransferReceipt`與receipt的catalog不變量。不能假造A的UniverseId，也不能用強制M3_SAFE_LANDING的舊`returnToSource()`。
- HOME outcome恰為`REJECTED_BEFORE_MOVE|MOVED|FAILED_ROLLED_BACK|FAILED_RECOVERY_REQUIRED`。前置拒絕不做native move／rollback、不改Crossed，通道依原期限繼續；off-thread才可無source／觀測，非有限來源保raw觀測，其餘必有完整source與move／rollback觀測。
- MOVED要求native成功、postcheck exact且無rollback；FAILED_ROLLED_BACK要求有move嘗試、rollback native成功且實際pose核回source；FAILED_RECOVERY_REQUIRED保留move與失敗／不可能rollback的實際證據，不可拿預期pose補成成功。沿M5 typed evidence invariants，HOME成功值只換成canonical權威型別。
- MOVED後checked加入Crossed；確認後立即savePlayerData。Crossed未確認時只收斂，停止其他移動／終局；不為HOME新增第二套move intent交易。
- FAILED_ROLLED_BACK者留B，可在同一仍開的通道重試；FAILED_RECOVERY_REQUIRED保留證據進永久SEALED，不自動重送或拉回。
- 已跨者到達A時給title「已回到原本世界。」；未跨者依原到期／關門／B斷電等終局留B。到達者不再收到通道關閉actionbar。[N9][I]

## 9. HOME 狀態機、提交與失效矩陣 [D]

```text
provisional檢查 → checked ARMING → checked SUPERPOSITION
  未選：取消／藥效／來源中止 → RETURNING → 全部返B＋清lease → checked移除
  選門：checked MEASURED＋HOME（first-wins）
    → checked COLLAPSING → 返B／清lease → HOME準備 → checked PASSAGE_READY
       ├ A停用／拆除／結構失效 → 清完返還與lease → checked終局收據＋移除active → 解鎖
       ├ 技術可恢復失敗 → SEALED → 同一HOME收據依原退避重試
       ├ 永久失效／transfer recovery → SEALED（不自動重試）
       └ 正常通道結束 → checked終局
```

### 9.1 合法欄位與提交

- ARMING／SUPERPOSITION／RETURNING：Selection=UNSELECTED、commit=-1、未開膜、Crossed／RecoveryRequired空、StopReason／SealedReason空；空lease不合法。
- MEASURED：Selection=HOME、commit=-1、timing=-1、Crossed／RecoveryRequired／SealedReason空；StopReason可空或不可清root latch。允許返還進度；全Returned＋空lease是HOME DORMANT，仍保選擇／busy。
- COLLAPSING：Selection=HOME、commit≥selectedAt、timing=-1、Crossed／RecoveryRequired／SealedReason空，StopReason可空或root latch；允許未返還者與原lease，空lease蘊含全Returned。
- PASSAGE_READY：全Returned、空lease、opened≥0／expires=opened+1200、Crossed子集、RecoveryRequired／SealedReason空。
- SEALED：全Returned、空lease、Selection=HOME、commit≥0；未開膜則timing=-1且Crossed空；已開膜則原timing及Crossed完整保留，不因封存清空。
- 全部selected狀態在CloseIntent=NONE時都允許**同state單步**寫StopReason／StoppedAt（原兩值空/-1→root reason/time≥selectedAt），其餘欄位完全不變；含MEASURED返還途中／DORMANT、COLLAPSING與READY，無B runtime也可。TRR永久frozen及已CLOSE列例外，不能藉root事件清理掉其證據。
- root停用成功、根身分撤銷由正式lifecycle提交點同步通知HOME owner，不依PREPARE／EntryWatch／電位輪詢；結構失效在實際觀察時通知。普通LOW不發root事件。同tick停用再啟用仍保原stop事件；復啟不能撤銷latch。
- 有未確認journal時事件進server-owned perSID first-wins queue且保守view立即停旅行，先收斂前筆再checked latch；成功回報停用／根撤銷前須確認latch，未完成顯示pending／不回報已完成並拒同根復啟跨過此fence。queue不能因現在enabled=true而丟棄；重啟以已checked stop／CloseIntent續行，不以未知結果假稱durable成功。
- **開服重建fence**：先strict載入journal與根registry並完成ownership，再由同一HOME root-fence服務只掃active selected HOME（含MEASURED DORMANT、無B runtime；不掃receipt）。在任何maintenance enable、HOME續行／選擇或對外互動caller放行前完成此掃描；所有lifecycle入口（含直接setEnabled／setOriginEnabled）及HOME owner皆守同一服務，不能各自以registry目前布林值繞過。
- 掃描發現持久registry已確認Root disabled／destroyed／exact身分撤銷，即同步重建不可復啟的perRoot stop fence，先保守停止原SID，再checked latch原原因；journal pending時維持enable pending，確認stop持久化後才放行復啟，原SID只可收尾。各root需先完成掃描才能開放，初始化未完成維持拒絕／pending，不能等B入艙才補。
- 健康registry的明確撤銷與未讀到／chunk未載入的UNKNOWN分開；UNKNOWN保持既有技術等待，不mint DESTROYED。TRR／永久authority仍守最高優先的永久停止與人工處理，不以startup fence改寫或歸檔其證據。兩owner不是原子檔：本開服fence專門補回「registry已落disabled、HOME stop尚未落盤」的記憶體通知窗口。
- state邊、返還進度、清lease、Crossed分開單步提交；狀態邊不得夾帶來源／cohort／選擇更動。所有寫入位於既有session operation guard，前置current=flushed=expected。
- HOME選擇、COLLAPSING、READY及可重試SEALED狀態邊沿restore CAS；返還／清lease／Crossed、StopReason與CloseIntent latch及進SEALED沿roll-forward。CloseIntent未知期間只收斂，不改門；一旦checked便沿§5.3恢復，active移除沿restore CAS，失敗不得恢復旅行能力。
- root失效先停旅行／釋A票，完成必要返還／清理後提交CLOSE；不是SEALED等待。CloseIntent凍結後僅容許同一列重寫收斂或§5.3 checked移除，不容許重寫時間／reason／Evidence；active來源state保留供decoder查核。
- 所有root停止的B開門及正常終局門目標均由CLOSE owner在解鎖**前**持久完成；不得沿ownerless膜佇列代替普通關門的收尾。終局後不掃歷史補工作。

### 9.2 原因、重試與選後 A 失效

| 情境／`SealedReason` | 分類與動作 |
| --- | --- |
| A在啟動／選門前OFF、disabled、無合法艙 | 拒絕啟動／保持UNSELECTED並鎖HOME，說明原因；不提交選擇。 |
| 選後A只斷電 | 不影響準備、READY或後續跨越；不重查電位作允許條件。[N5] |
| `ROOT_DISABLED`／`ROOT_INVALID_STRUCTURE` | 本趟終局，保留HOME、Crossed與原deadline作不可變紀錄；B普通開門、checked後解鎖，不等A修復。[R1][R2] |
| UNKNOWN／200tick載入逾時 | 可恢復HOME_CHECK_WAIT，不是root事件、不進ROOT終局；原SID及選擇不變，READY期限不變。[N5][N6][D] |
| `ROOT_DESTROYED`／`ROOT_IDENTITY_MISMATCH` | 本趟終局；原根已失效，不接受同位置新艙冒名。舊U／投影／已跨玩家不刪除；global占用解除，未來仍須真實合法根才能新出發。 |
| `ENTRY_AUTHORITY_INVALID`／`AUTHORITY_CORRUPT` | 持久Entry／ledger／catalog錯配或損壞，永久封存，不猜另一個端點。 |
| `AUTHORITY_UNREADABLE` | 沿M5僅重啟後每instance一次重試；當次不把IO錯誤當資料不存在。 |
| `TRANSFER_RECOVERY_REQUIRED` | 永久；恰一RecoveryRequired，保留Crossed與timing，不自動移動。 |

- A失效先checked latch不可清除的StopReason／時間；未確認先停移動只收斂。若返還／清走廊未完成，仍把尚未Returned者送回B並完成清理，再checked封存終局；清理pending時保busy／A保護，不因A先恢復而續行。
- ROOT_DISABLED／結構失效發生於READY時，先停止尚未跨越者，已跨者留A；checked歸檔後修復root也不清Crossed、不再傳一次。
- AUTHORITY_UNREADABLE僅重啟後每instance一次驗證：未開膜、無stop則checked SEALED→COLLAPSING，只清SealedReason；曾READY者禁止在新instance重開，清讀取問題後走RESTART_CLOSED並保原timing／Crossed。永久SEALED無retry／正常close邊。
- 優先序：不確定move／TRR永久最高→未確認寫入收斂→既有CLOSE→StopReason／排隊root事件→普通allCrossed／expiry／B事件。root事件不能清TRR、RecoveryRequired或永久authority證據；無法驗證exact B即技術pending，不能改判root毀壞。
- 部分返還時無法解析exact B，沿既有pending託管／凍結；不改回A、spawn或其他U。未知／損壞不授權刪收據。[Ne]

下表是CLOSE與receipt **同一份**原因驗證表；全部要求Selection=HOME、全Returned、lease空、RecoveryRequired空，永久SEALED一律拒絕。ClosedAt≥選定／commit／opened中所有非負時間；NONE→CLOSE只接受server owner在該事件guard內mint的Evidence，reader再核shape／相互時序，不能以任意NBT自稱事件已發生。

| CloseReason | 合法原state／必要證據（Evidence exact keys） | Stop／timing／Crossed；DoorGoal |
| --- | --- | --- |
| `ALL_CROSSED` | READY；`{Kind:"ALL_CROSSED"}`，CAS核全cohort=Crossed | Stop空；已開膜；全Crossed；OPEN |
| `EXPIRED` | READY；`{Kind:"EXPIRED",ObservedAtGameTime:long}`，observed≥原expires且closed≥observed | Stop空；已開膜；Crossed任意子集；OPEN |
| `PARTICIPANT_CLOSED` | COLLAPSING的實際PREPARE或READY；`{Kind:"PARTICIPANT_CLOSED",Actor:UUID,Phase:"PREPARE"或"PASSAGE",ObservedAtGameTime:long}`，actor屬cohort，phase對state，closed≥observed≥commit／opened | Stop空；PREPARE timing=-1且Crossed空，READY保原值；CLOSE_IF_CLEAR_ELSE_OPEN |
| `ENTRY_STOPPED` | READY；`{Kind:"ENTRY_STOPPED",Signal:"LOW"或"DISABLED"或"INVALID",ObservedAtGameTime:long}`，核實B當下事件且closed≥observed≥opened | Stop空；已開膜，Crossed子集；OPEN |
| `RESTART_CLOSED` | startup集合既有READY，或曾READY且AUTHORITY_UNREADABLE已驗恢復的SEALED；`{Kind:"RESTART_CLOSED",StartupInstanceUuid:UUID,ObservedAtGameTime:long}`，只由startup owner mint，closed≥observed≥opened | Stop空；保原timing／Crossed，不要求等expiry；OPEN |
| `ROOT_DISABLED`／`ROOT_INVALID_STRUCTURE`／`ROOT_DESTROYED`／`ROOT_IDENTITY_MISMATCH` | MEASURED／COLLAPSING／READY，或非永久SEALED；`{Kind:"ROOT_STOP",Reason:string,ObservedAtGameTime:long}`，reason=StopReason，observed=StoppedAt，closed≥stopped≥selectedAt | 必有同一root latch；原state timing／Crossed保持；OPEN |

`TRANSFER_RECOVERY_REQUIRED`、`ENTRY_AUTHORITY_INVALID`、`AUTHORITY_CORRUPT`不可用任何close reason歸檔；不是把永久原因改名為ROOT。UNKNOWN不得mint ROOT_STOP。CLOSE／receipt decoder拒原因置換、錯state／phase、早於期限的EXPIRED、Crossed或RecoveryRequired不符、未知Evidence鍵與非法DoorGoal。

### 9.3 舊旅程結束與新旅程 [R2][R4]

- root終局不清Buff、不新增飲用token／playerdata fence；未到期QuantumState可沿用，過期才須補喝。
- root終局後B門為一般開門；只有玩家重新操作關門、完整在艙內並符合既有資格時才建立新SID，不額外要求先出艙。正常PARTICIPANT_CLOSED的門目標沿M5，不被此條改成開門。A恢復或receipt存在本身不構成start／resume事件。
- 新SID從UNSELECTED開始，重做A啟動檢查、HOME選門與選門檢查；舊單SID receipt永不修改或重播。

## 10. 重啟、離線與 reload 矩陣 [D；沿 M5 已知界線]

| 邊界／持久證據 | 重啟／斷線／reload結果 |
| --- | --- |
| provisional、尚無checked ARMING | 無玩家／幾何副作用；程序結束失去runtime票，不復活檢查。未確認寫入由既有journal收斂判定，不能憑callback補寫。 |
| checked ARMING／SUPERPOSITION，未選 | 重啟走RETURNING，只回B；離線者JOIN下一tick返還。保持原效果／checkpoint契約。 |
| A檢查完成、HOME尚未checked | 只有成功讀回的Selection算數；stale結果丟棄，不能猜已選。 |
| HOME已checked，COLLAPSING前 | MEASURED／DORMANT無stop則原目的續行；有stop只返B／清lease／CLOSE，不抽門。不要求B runtime存在才接收root事件。 |
| COLLAPSING返還／清lease／A預載間 | 無stop由Returned／lease／HOME續行，重新解析live實例；UNKNOWN超時只釋票退避，後來FULL仍可續同SID，不重建A。 |
| READY已checked、尚無移動 | 開服不重開／不重播。已checked stop走ROOT close；沒有已確認root事件一律RESTART_CLOSED，不為了改歸ROOT再等A FULL；只需完成exact B收尾。 |
| native move完成、Crossed未確認 | 沿M5窗口：玩家依最後成功存下的playerdata登入；不自動補傳、不用位置猜Crossed。保守view待確認期間不再移動。 |
| Crossed已checked、playerdata尚未成功存下 | Crossed保留，但登入位置仍可能是B；不重新傳送。這是沿用的已知限制，不宣稱跨兩檔原子性。 |
| Crossed及playerdata已成功存下 | 玩家留A；重啟READY按上列終局，Crossed永不回拉。 |
| root StopReason已latch、尚未終局 | 只續返B／清lease／歸檔，不再準備或傳送；A已修復也不復活。 |
| CLOSE已checked，改B門／checkpoint前後 | 無論B普通關門／sealed／passable均依DoorGoal冪等套用並重新checkpoint；門口entity按原防夾規則。不操作A，不因膜已NONE假確認。 |
| receipt發佈／exact讀回前後、active未移除 | 只查同SID；不存在則不覆寫發佈，存在只驗exact。重做B門／checkpoint後刪active；錯配或未知維持鎖與原件，絕不傳送。 |
| active移除已checked | CLOSE完成，票／busy／M7額外保護可冪等釋放。沒有待補門工作，不掃receipt開門；新玩家刻意關門不會被舊SID打開。 |
| AUTHORITY_UNREADABLE SEALED | 未開膜且本次驗恢復可回COLLAPSING；曾READY保原Crossed／timing，startup以RESTART_CLOSED收尾，即使原deadline未到也不重開。 |
| 永久SEALED／transfer recovery | 持鎖且不取永久票，無retry／close邊；每instance一次管理員ERROR。root事件不能吞掉不確定move／永久authority證據。 |
| `/reload`與A檢查重疊 | 未選定檢查取消／釋放票，重新互動再查；已checked HOME不依賴生成snapshot新擷取，保留收據且不重新抽樣。 |

正常玩家斷線不延長已開通道期限；已Returned者不再被本session凍結，未Returned者依舊等待。
不持久化`UniverseTransferReceipt`的live instance token作返航票；HOME每次只從Root持久身分重新取得當次canonical instance。
保留舊M5 native move無法回滾／playerdata存檔失敗的實際界線，不宣稱HOME已解決所有跨檔crash原子性。

## 11. 提示、驗收與完成門檻

提示沿translatable key、zh_tw／en_us；顯示可核實原因，不向玩家印UUID、schema或token。[N9][D]
必要訊息：原艙未供電、停用、已拆除／結構不完整、正在檢查／檢查逾時、
「這座艙已有旅程進行中」、回程已停止且選擇保留、傳送失敗仍在B，以及成功回家title。
不用完整G1介面，不更動既有比較器的其他數值。[N9]

| ID | 自動驗收（每列至少一項精確斷言） |
| --- | --- |
| V01 | save上層合法短名／junction／symlink可用、Windows真GetShortPathName／ancestor junction發佈查詢成功、save內data junction／reparse拒且mutation守門；schema1–5fixtures不變；schema6／receipt exact keys／type／raw重鍵／未知版拒絕、同bytes provenance與16MiB臨界NBT；合法25人大快照、接近16MiB時舊session成長＋HOME remap新舊lease並存不低估／不侵占close餘額，所有共用writer皆驗，舊build拒6。 |
| V02 | COMPLETE＋live B才能啟動；PENDING／foreign／偽BE UUID／錯U／錯frame／ledger遺失／退化各拒絕，B不註冊Origin。 |
| V03 | A未載入→UNKNOWN→FULL後判ON／OFF／disabled／missing；選後OFF仍回A；201tick才FULL保同SID續行，READY不加時；同tick關啟、未返完／DORMANT無runtime停用、partial Crossed與stop重啟保latch。跨JVM使registry disabled已存而HOME stop未存，先嘗試enable再觸發B，原SID仍不能續行；普通LOW不latch。 |
| V04 | B取消、牛奶、cohort任一Buff失效、離線與重啟返還皆回exact B；HOME才到A，舊SOURCE fast path不變。 |
| V05 | A/B及B/B同tick／refresh重入只一owner；同key remap owner／generation更換、玩家換instance／離開／死／旁觀、cohort Buff失效尚未取消、A FULL後入隊再斷電皆不得SELECTED；ARMING未知／old DORMANT／SEALED仍busy。普通玩家busy時拆A被拒；root收尾成功才解鎖／解除額外保護。 |
| V06 | A每SID≤4refs、server≤64份／256refs及全域coverage預算；200tick逾時、取消、例外、stale、SEALED、終局、stop、reload後ref全釋放。 |
| V07 | canonical target exact前後核對；preflight拒絕零native／rollback、原生半改後成功rollback與rollback失敗皆驗完整evidence。HOME配置／投影寫入0，A內容／關門不變、FIRST_WRITE／repair=0，不改XYZ／不加碰撞拒絕。 |
| V08 | 四朝向station1 POSITIVE的實體25格完整HOME、與入口25格零交集且可實選；station0入口操作不變，其他頁／位置是牆且無candidate追加；正常A既有候選／profile／SOURCE不變。 |
| V09 | B膜四朝向、跳躍／流體／無支撐沿M5 policy；兩人一跨一留、非參與者／騎乘拒傳、60秒／關門／B斷電終局、A斷電不誤收尾。 |
| V10 | 跨JVM覆蓋CLOSE→普通關門／sealed／passable改門→checkpoint→receipt發佈／讀回→active移除每縫隙；故障保busy／原件、只B冪等目標且不掃歷史，>4096趟仍可新HOME、舊單檔可讀且不占用／不寫A。驗原因置換、偽造時間、permanent/TRR誤close拒絕；修A不復活，新SID可沿Buff。 |
| V11 | TravelCapabilityVersion=1且FamilyCapabilityVersion仍0；Nether／End維持共用原版，舊session語義與M6宇宙內重生回歸不變。 |

V10另覆蓋曾READY AUTHORITY_UNREADABLE恢復／重啟、原deadline已過、startup無root事件、receipt與active錯配／未知結果；每次均驗Crossed不重播。B普通關門ROOT close後才可由玩家新操作開始，PARTICIPANT_CLOSED仍按原M5門目標。
V10必須另以獨立JVM具名覆蓋§10三個移動存檔窗口：`native move完成但Crossed未確認`、`Crossed已確認但playerdata未成功存下`、`Crossed及playerdata皆成功存下`；如實斷言各自登入位置界線與不重播，不以close階段的crash鏈代替。

| ID | required manual smoke（執行前另獲代操作授權）[N11] |
| --- | --- |
| H01 | 單人完成A探索→B投影啟動→唯一HOME→回B→膜→A；確認到達提示、同座標、A容器內容；另以A原門關閉具名觀察同XYZ落點行為；重登／重啟仍在實際存下的世界。 |
| H02 | A未載入時檢查成功；A預先OFF拒B啟動；走廊內A轉OFF鎖HOME；選定後A轉OFF仍成功；selected後disabled／管理員拆除終局，B普通開門、占用解除、歷史選擇不變；A修復不復活舊SID，重操作可沿未到期Buff開新SID。 |
| H03 | dedicated兩人一跨一留；另一端同時要求啟動被busy拒絕；原deadline到期不拉回已跨者，未跨者留B。 |
| H04 | B牛奶／藥效到期回B；B／A位置不同世界實證；B與正常A各做一次`/reload`後新session，A一般探索仍正常。 |
| H05 | release JAR main-only實跑HOME；真client觀察門的最小區別、單一HOME／牆、膜與回家title，不靠testmod冒充release。 |

M7 T1只兩平台各自JUnit／GameTest exact集合與CI，沿M6既有scope/JAR/raw/summary做法；不新增M7 native/main-only/完整consumer框架門檻，既有M6 native/main-only與全部required jobs保留。正式Windows跨JVM39留T4，Ubuntu不含native/main-only/Linuxowner，未驗原生能力列限制、不skip假PASS；沒有T1 PoC退休流程。
production task依AGENTS保RED／GREEN、production-owner mutation、focused、完整回歸與commit range；
只改測試／腳本／文件沿M7唯一B+C條款：一席獨立review、定向驗證與CI。
新增跨JVM oracle須tracked、驗失敗反例、納入run-all及CI；舊FAIL與root保留，未獲授權不套M6單次重跑額度。
W10 preflight前照verification README重建producer並用UTF-8 console；不是本草案已執行結果。
全部task與whole-branch review C/I清零、完整gates／同SHA CI、manual smoke與docs truth齊全才可宣稱M7完成。

## 12. 待裁示與明列界線

- 本草案未新增未知玩法裁示；§2的[I]保留原note「沿用與推論」身分，不冒充逐題原話。
- 選HOME後A停用／拆除已由[R1][R2]裁示終局並留歷史，並由[R3]維持A保護至收尾、[R4]允許沿未到期Buff；不得回退為SEALED等修復，也不暗加額外飲用要求。
- HOME沿M5同XYZ與不做碰撞安全判定；不為A開門或改落點。A門若關閉時的真client可見行為須在人工驗收記錄，若需要改玩法，先回報使用者，不暗加修復／安全Y。
- M8規格與優先序未確認；即使新資料保存Entry，也不因此在M7開放一般門或宣稱連續穿越完成。

## 13. 第一輪獨立審查處置（待複審）

| Finding | v0.2處置 |
| --- | --- |
| C1 | §4.2 HOME改station1 POSITIVE；V08核四朝向25格完整、入口零交集與實選。 |
| I1 | §9.1明列所有selected單步stop latch、root lifecycle事件與pending序列化；TRR最高優先，V03驗同tick關啟／DORMANT／返還途中。 |
| I2 | §5.3先持久CloseIntent、B門與原生checkpoint，再receipt與active移除；§10/V10覆蓋普通關門與兩種膜的crash縫隙，不掃歷史。 |
| I3 | §5.1/§5.3改單SID不可變有界檔、16MiB及最壞成長餘額，取消終身筆數cap；V01/V10驗臨界NBT／>4096旅程。 |
| I4 | §4.2在唯一guard重驗物理owner／mapping generation／25格／玩家／cohort／B／A當下電位；V05補stale反例。 |
| I5 | §7 UNKNOWN逾時只釋票退避；§9/§10保SID、原READY期限與RESTART_CLOSED，V03驗遲到FULL。 |
| I6 | §9.2共用close reason／origin state／Evidence／timing／Crossed／DoorGoal矩陣，禁止永久SEALED歸檔，明列曾READY讀取失敗重啟路徑。 |
| M1 | §8明列四種HOME outcome與move／rollback證據，V07補移動前拒絕與部分移動回滾。 |

第二輪定向修正（待複審）：I1由§9.1開服重建root fence及V03跨檔窗口補足；I3由§5.1分階段lease預留及V01 remap負控補足；Minor在V05／V10補回B/B競爭、普通玩家拆A被拒與三個具名跨JVM移動／存檔窗口。沒有新增玩法待裁示。
