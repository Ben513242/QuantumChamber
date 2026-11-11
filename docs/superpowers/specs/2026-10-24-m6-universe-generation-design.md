# M6 Overworld 宇宙生成多樣性：binding spec（v1.7）

日期：2026-10-24。基準：`0d05f889da6f8b82b97af756aaa50066b3d42854`。本文定義 M6 的新行為；未明列變更的 M4／M5 checked 權威與遊戲行為維持原約定。本文尚非實作或驗收結果。

### v1.7 有效範圍（2026-11-10：宇宙只有主世界）

使用者原話：「宇宙只有主世界，Nether／End 共用原版，意思就是平行宇宙共用原有的Nether／End」「在宇宙裡死亡（沒有床）要去哪裡:留在宇宙這樣才符合量子艙的概念」。本版優先於較早 B+C 範圍快照；B+C 的流程與資料安全關卡保留。本次唯一範圍修訂已經獨立窄審（同席定向關閉殘留 active family 承諾 I1，終局 C0／I0／M0）採用，報告 SHA256 `062ac1acd07c6f073517c50f3cf69adfbaac7ab47ef6bad3e31ec13eb27cff3c`；尚非重生實作或人工通過（當時；重生已於 2026-11-11 實作，見 §5 末，人工仍未執行）。

**Task R 開工前修訂已採用（2026-11-10）**：本次只修 spec／plan／人工清單，唯一獨立窄審 C0／I0／M0，報告 SHA256 `1a40624a243630e9d832cecadc967b41a05785cae6ee4e328401bcdf45fe9ee0`。T13 結案不變；本輪窄審已清零，可依使用者本次授權開始 T14，Task R 當時尚未實作（2026-11-11 已實作，狀態見 §5 末）。

- T13 已結案，`270ea3f` 已核對；本輪三檔修正經唯一窄審 C／I 清零後，依使用者授權開始 T14。M6 依序 T14 normal↔amplified→宇宙內重生（取代 T20）→8-H triage→收尾。`FamilyCapabilityVersion` 維持 0，僅物化 U:OVERWORLD。
- T15–T19、T21 暫時排除，不排 M6 或 M7；不再承諾三 role family、專屬 Nether／End、portal／gateway routing、同 role 艙或 U→U 全量比較。只在 plan 保留一段未排程構想。catalog 未物化 Nether／End descriptor 的處理、維度 key 不一致待辦一併取消；不改程式、不刪改現有 catalog／ledger／frozen snapshot。c7d／e1d 及所有原 FAIL 均為保留的歷史，不是未來 T21 關卡。
- 宇宙共用原版 Nether／End。地獄門回程一律落在原版主世界；終界出口照原版看床，床在 U 就回 U，沒床回原版主世界，不加 portal hook。這是明列的預期行為，尚非本輪實測結果。
- M6 只出 SHALLOW_V1。8-H 依「只有主世界」重新提必要項與 waiver，交使用者裁示。收尾仍完整回歸、整分支審查、docs truth、CI，單人／多人 smoke 各一輪並加一輪宇宙內死亡重生。合併 main、tag、人工代操作各自另問。

狀態（v1.4，2026-11-02）：依使用者 2026-11-02 裁示「我想先裁示縮 Task 8 範圍，至少本模組功能都完成後才修必須的任務切片」。為落實裁示，**controller 設計預設（非使用者原話）**：新增 §3.1.3 `SHALLOW_V1` 最小閉包並把 §3.1.1 完整閉包綁定到 ProviderVersion≥2（Task 8-H），§3.1.2 增 8-core 最小 lifecycle，§3.1／§3.2／§4／§8／§9 相應調整；M6 正式版是否只出 `SHALLOW_V1`，在 §8 的 8-H triage 交使用者裁示。**v1.4 已採用**：首輪獨立 spec review C0/I4/M11（SHA256 `c1ab9ec35a6e6c9dfba14f7f9532a2dfcca655019c974446c4ecf41df0963be8`）、plan review C0/I5/M11（SHA256 `5aa3302026c10312b9d1c415b471d44db7bd18b5a5789e2d823f1ba719120e12`）、窄複審一 C0/I1/M3（`ff9ae0b788bc9ba4b2704c25166db6ba87f24e93149c815eac0a1007388868f1`）、窄複審二 C0/I1/M2（`e52e6a80755a7faceb6d193242010f951be0d0d792f02194702376e2f2a0c920`）、窄複審三 C0/I0/M0（`943626daecfb517da91a74828d02412c4866c2c81e2f702e8d75c30ccb04ebb2`）；全部 Important 關閉後採用，本檔採用版 SHA256 記於 plan 第 11 行與 M6 note 2026-11-02 條目。v1.3 以下內容、既有 8A／8B 局部結案證據與所有 retained FAIL 原判不變。

狀態：v1.3已依設計層獨立review `3E61305D9BE716F29C4249F8EB59D366AB468298FA6574F983BA14C5033987D5`（C0/I0/M1，鍵數文字已修正）採用exact Single真absence／只讀cache與resolved template契約；使用者裁示snapshot期間限正常遊戲操作，外部改檔走reload／重啟。v1.2 RecipesUsed及plan r3/r4平台硬gate保留。本次是設計採用，不是A7、8A native gate或8B實作通過；正式進度以plan/note為準。

## 1. 目標、來源與交付順序

M6 依序交付①測試腳本安全與生成競態、②random seed、③same-seed fresh／generation profile（僅 Overworld，T13／T14）與宇宙內重生；family 不排 M6／M7。已完成的三類結構競態防護與原始證據保留，不因縮範圍撤回。驗證流程依 §8 的 B+C 分流，C／I 清零才前進。

**主控設計預設的具體預設**在 §4 標明；設計 §7.4 的 50／30／20 是示例，使用者尚未選定權重，也未指定 mutation 類型。M6 不改目前 `SOURCE`／`EXISTING`／`NEW` 的候選權重，不改選門後的候選身分；只讓新產生的 `NEW` 意圖可選 M6 profile。兩項獨立 harness Minor：`probe-common.ps1:17–24` 的 `run/` 祖先 canonical／reparse 守門，以及 `gates.ps1:80–102` 的 Move-Fresh「move 成功、建 fresh root 失敗」可復原且保留原錯誤；各自 RED／GREEN、獨立 commit，不能混進生成 production commit。

| 切片 | 成功畫面／必要證據 | 必須先有 |
| --- | --- | --- |
| ①安全與競態 | fresh root 失敗仍可 exact 還原；三類結構並行生成無共享狀態污染，原版單世界輸出不變 | native 1.21 路徑盤點、三類各自定向 RED |
| ②random | 兩個 `NEW` 可有不同 terrain／biome／structure seed，重啟與重訪不變；舊世界不變。原版 portal／死亡仍可能非正規回到 canonical，沿 M5 已知限制 | §3 schema／seed owner 與提交鏈 |
| ③same-seed／profile | 新 UUID、空白歷史、同 seed 地形；至少一種可量測的原版 profile mutation；registry 查詢能回報精確 id／version／參數。原版 portal／死亡非正規回程仍未修 | ②、原版 preset 與 worker 實證 |
| 宇宙內重生（取代 T20） | 床／錨依原版優先；exact live U 主世界死亡且走原版 canonical fallback 時，只把世界換成同一 U，位置照原版（2026-11-11 裁示）；權威不成立時沿原版並記錄 | §5 的 getRespawnTarget 兩個 canonical fallback 呼叫窄調整，原 production gate |

不做 M7、玩家可控制的權重 UI、複雜 renderer、目的地預覽、雙向量子通道、全域 eager 投影、世界池卸載或版本升級。固定 Minecraft 1.21／Yarn `1.21+build.9`／Loader `0.17.2`／API `0.102.0+1.21`／Loom `1.7.4`／Java 21。AccessDenied 維持既有 fail-closed／退避；不宣稱原版 playerdata 吞下 IOException 已解決。

## 2. 現行真相與不可變身分

現行 `CandidateResolver` 的 `NEW` 固定 `VANILLA_OVERWORLD_SHARED_SEED_V1`，`generationSeedMaterial` 已凍結卻未使用；`NewUniverseIntent` 限制 profile v1，`CandidateJournalCodec` 對意圖 exact keys。`DestinationResolver` 先 allocation ledger 再 catalog；`UniverseAllocationLedger.Entry` 固定 token→UUID／profile／version；`CollapseAuthorities.catalogRecordMatches` 用 `allocateOverworld` 的 schema1 模板 exact 比較。`UniverseRecord` schema1 沒有實際 seed，且其 decoder 雖查型別，並未對每層要求 exact key；`UniverseDefinition`、`UniverseKeys`、backend、factory、transfer／lifecycle 目前只接受 Overworld，`DimensionRole` enum 的三值不是 family 已實作的證據。

schema1 factory 用 canonical `DimensionOptions.OVERWORLD`、存檔的 generator seed 做 `BiomeAccess.hashSeed`、`UnmodifiableLevelProperties(saveProperties, mainWorldProperties)`，並共用主世界 `getRandomSequences()`。因此只換 factory 的 biome seed 不能證明 terrain noise、structure、biome、random sequences 或 world properties 已隔離。schema1 已生成的 UUID、Overworld key、seed、地形、storage、投影、M4 凍結意圖／selection／receipt 必須保留原語意；不得重抽、覆寫或刪除。舊 `NEW` 意圖即使在 M6 首次 collapse 也依凍結的 legacy profile 建立 schema1，不得偷用 `generationSeedMaterial` 改 random。舊 `EXISTING` 仍重驗 exact record 與 gameplay eligibility。

## 3. M6 權威、schema 與不可逆順序

### 3.1 單一權威

以下格式為 M6 的唯一新格式，不由實作者另選版本。所有新欄位採 NBT 明確型別；`ProfileId`、設定 id、role key 必須是 canonical namespaced identifier。每層 schema2／schema5 新 compound 都要求 exact key、無重複、無隱式預設。舊 schema1／journal schema3–4／allocation v1 的**合法**解碼方式保持既有契約。新舊記錄混合時由 entry 自身版本或欄位形狀嚴格分派，不用目前 runtime capability 猜。

| 權威 | 新版本與 exact 新格式 | 舊資料處置 |
| --- | --- | --- |
| journal envelope | schema5；既有 schema4 欄位與狀態不變。新 `NEW.Intent` 恰含 `AllocationToken`(32 bytes)、`GenerationSeedMaterial`(32 bytes)、`PolicyVersion`(int)、`ProfileId`(string)、`ProfileVersion`(int)、`SourceRootWorldSeed`(long)、`SourceSettingsSnapshot`(下表 compound)、`TargetSettingsSnapshot`(下表 compound)、`FamilyCapabilityVersion`(int)。舊 intent 仍是原四欄。 | schema3／4 原樣讀；schema5 允許 legacy entry 與新 entry 混合，依 `PolicyVersion` 有無嚴格區分兩種 exact 欄位形狀。新 intent 只在 schema5 寫出。 |
| allocation ledger | envelope schema2；`Entries` 的新 row 恰含 `EntryVersion=2`、`AllocationToken`、`UniverseId`、`PolicyVersion`、`ProfileId`、`ProfileVersion`、`RootWorldSeed`、`TargetSettingsFingerprint`(32 bytes)。 | 既有 schema1 ledger checked 轉成 schema2 後才 append M6 row；舊 row 保持原四欄且無 `EntryVersion`，以原 exact decoder 讀。轉換只改 envelope，不能改舊 row 值／次序。 |
| catalog | envelope schema2；新 `UniverseRecord` schema2 保留 `UniverseUuid`／`AllocationOrdinal`／`DesiredAvailability`，新增 `RootWorldSeed`(long)、`ProfileId`、`ProfileVersion`、`TargetSettingsSnapshot`(下表 compound)、`Provenance`(enum)。`Provenance=MIGRATED_SCHEMA1` 時另有必填 `LegacySchemaOneDigest`(32 bytes)，`NEW_M6` 時此欄禁止。`Worlds` 恰三筆，形狀依下表。 | 外 envelope schema1 可含舊 record；首次需寫 schema2 record 前 checked 升 envelope schema2，舊 record 保持 schema1。後續僅按 §3.2 的 migration 將個別舊 record 向前轉換。 |

| schema2 `Worlds` 分派 | exact row keys／SeedPolicy | 設定來源 |
| --- | --- | --- |
| `NEW_M6` 三 role，及 `MIGRATED_SCHEMA1` 新增的 Nether／End | `WorldKey`、`Role`、`SeedPolicy=EXPLICIT_ROOT_SEED_V1`、`StoragePolicyVersion=1`；**沒有** `GeneratorProfile` | 該 record 的 `TargetSettingsSnapshot`，按 role 解析。 |
| `MIGRATED_SCHEMA1` 舊 Overworld | schema1 原五欄 `WorldKey`、`Role`、`GeneratorProfile=VANILLA_OVERWORLD_SHARED_SEED_V1`、`SeedPolicy=SHARED_SAVE_SEED_V1`、`StoragePolicyVersion=1`；沒有新欄 | 原 factory／save seed；snapshot 僅供新增 role 的設定與相容性驗證，不改舊 world。 |

`SettingsSnapshot` v1 的 exact keys 是 `SnapshotVersion=1`(int)、`ProviderId`(canonical namespaced string)、`ProviderVersion`(int)、`Parameters`(compound)、`OutputFingerprint`(32 bytes)。來源與目標是**兩份獨立 snapshot**，不得把 mutation 後參數當來源。內建 provider 的 `Parameters` 恰有 `OverworldPreset`(string，`normal|amplified`) 與 `GenerateStructures`(NBT byte，僅0或1，對應false/true)。缺欄、錯type或其他byte值拒絕，不默認true；Nether／End的原生preset設定固定，三role使用同一凍結的GenerateStructures值；受信任擴充須在註冊時宣告 bounded、strict、canonical 的參數 schema，拒絕額外鍵／型別或無法序列化的值。snapshot 的 canonical NBT 大小上限 512 bytes（含欄名與值），超限不得成為候選；provider id 長度及參數 bytes 亦受此限。`OutputFingerprint=SHA-256(UTF8("quantumchamber:m6:settings:v1") || canonical resolved output)`；resolved output 的閉包模式由該 snapshot 的 `ProviderId`＋`ProviderVersion` 綁定（v1.4）：內建 provider 恰兩個 ProviderId：`quantumchamber:vanilla_normal_v1`（Parameters `OverworldPreset` 必為 `normal`）與 `quantumchamber:vanilla_amplified_v1`（必為 `amplified`），ProviderVersion 1，同一實作註冊兩次，id 與 preset 不符由 provider schema 拒；`GenerationSettingsSnapshot.isBuiltin` 的內建 id 集合恰為這兩個（既有程式已如此）。`quantumchamber:m6_profiles_v1` 只是 Context 的 `PolicyId`，不是 ProviderId，不得由其一推導另一。內建 version 1 使用 §3.1.3 `SHALLOW_V1`，只涵蓋三 role 實際 `DimensionOptions` 的原生 codec 編碼（dimension type、chunk generator、biome source、noise settings 等 registry 引用一律以 canonical id 表示，不展開內容）與凍結的 GenerateStructures；§3.1.1 的完整生成設定資料閉包（noise settings 內容、structure sets／placements、tag、template、loot、component 等）綁定於未來的 ProviderVersion≥2，屬 Task 8-H。兩種模式都只排除另存的 root seed。canonical encoding 依 §3.1.1 保留型別、語意次序及明確 byte order。「native gate 必先證明從實際輸出可取得全部受支援資料閉包，且每個 preset／role 都各自通過」只約束 ProviderVersion≥2，未滿足者不可建立 ProviderVersion≥2 的可持久化 snapshot；`SHALLOW_V1` 的門檻見 §3.1.3。開檔時用 snapshot 的 provider id/version/parameters 重建，重算 fingerprint 並 exact 比對；provider 缺失、參數未知、fingerprint 不符或設定無法建立時該 U fail closed，不用預設設定替代。

`CandidatePolicySnapshot` 的新形狀也屬**凍結權威**：schema5 的 `Context` 保留既有 `PolicyId`、`PolicyVersion`、`CandidateSchemaVersion`、`SourceWeight`、`DiscoveredWeight`、`NewWeight`、`DiscoveryWatermark`、`MaxCandidateEntries`、`EntropyFingerprint`、`SourceFamilyRef` exact 欄位，另加唯一 `FamilyCapabilityVersion`(int)。M6 context 固定 `PolicyId=quantumchamber:m6_profiles_v1`、`CandidateSchemaVersion=2`、`SourceWeight/DiscoveredWeight/NewWeight=5/20/75`、`MaxCandidateEntries=4096`；`PolicyVersion` 取 §4 的 1／2／3，資料格式保留 `FamilyCapabilityVersion` 的 0／1 編碼，但本產品新 Context／intent 固定 0，不承諾啟用 1。新 `NEW.Intent.PolicyVersion`／`FamilyCapabilityVersion` **必須恰等於該 Context**，profile selection 要可由 Context＋frozen material 重算並 exact 比對。`SOURCE`／`EXISTING` 沒有 intent，但它們的 family 能力同樣只從 Context 的 frozen `FamilyCapabilityVersion` 讀，不得由現行 server 功能旗標補值。舊 M4 context（`m4_v1`、version 1、candidate schema 1、無 family 欄、上限 16384）按舊 strict codec 讀，視為 capability 0；不得因新 envelope 改成 1。M6 context 一旦寫入，同一 session 不可改 policy 或 capability。schema5 journal／allocation2 的 writer 在 checked 寫出前，須以候選完整 NBT 經正式 strict reader 與相同 `NbtSizeTracker` 16 MiB 預算實際重讀、確認可解碼與 exact 比對；失敗即拒絕新增並保留既有權威。序列化 byte 長度不能代替 `NbtSizeTracker` 的解碼計量。4096 筆與單 snapshot 512 bytes 是設計上限，不代表已證明可讀；plan 的 codec task 須用最壞形狀 fixture 與實際讀回驗證 writer 可寫者皆可讀。

`PolicyVersion` 只允許 1／2／3；`ProfileVersion` 本輪只允許 1；`FamilyCapabilityVersion` 只允許 0／1。schema2 validator 必按上表 `Provenance × role` 分派 row shape 與 SeedPolicy；`GeneratorProfile` 舊 enum 只用於舊 row／遷移的 Overworld，新 `ProfileId` 不得以 enum ordinal 編碼。新 row 的 role 設定由啟動期凍結的 provider、持久 snapshot 與 fingerprint 解出，不得從當前 config 重算。所有新 allocation row 的 `UniverseId` 仍依 M5 token 推導公式；由 frozen intent 及 §4 policy 算出 `RootWorldSeed`／目標 fingerprint，必須在 ledger、catalog exact 比對相同。`SourceRootWorldSeed` 是 profile 演算輸入，不是來源 U 的持久資料新 owner。

`NEW` 的凍結 candidate intent 是 profile 選擇與 allocation token 的唯一來源；allocation ledger 是 token→Universe UUID／profile id／version 與目標 fingerprint 的配置權威；catalog 是已配置 Universe 的 root seed、**目標** settings snapshot、descriptor、role key、availability 的唯一持久權威。**來源** snapshot 只在 frozen intent，供重試與同 seed 衍生比對；現成 U 以其 catalog 目標 snapshot 作下一個候選的來源。runtime registry／`ServerWorld` 僅是 catalog 的投影，不得重抽。每個新 schema2 definition 恰有一個不可變 `RootWorldSeed`、`ProfileId`、`ProfileVersion`、目標 snapshot 與三個可推導的 role key；`Worlds` 的 exact shape 以上表為準。嚴格檢查型別、canonical namespace／路徑、唯一 role／key、三 role 完整性、profile 白名單與版本、seed 政策及 fingerprint；未知欄位或 profile fail closed。schema1 既有合法 bytes 照原解碼契約讀取；schema2 新格式從首版即 exact-key。catalog 外層 schema 的 envelope／record 版本須由同一 checked write／strict readback 升級，不靜默改寫舊 bytes；未知較新版本 fail closed，禁止自動降版。

`rootWorldSeed` 指向三 role 的世界生成 root；同 U 的三 role 相同，role 專屬原版 generator 設定決定細節。canonical 世界仍由 Minecraft 存檔權威持有。schema1 的 seed 仍取同一存檔原有 generator seed，不能重新定義成 M6 random；schema1 的既有 Overworld 原版 options／properties／randomSequences 行為維持。family 所需的 schema1→2 唯一路徑與首次遷移前外部改 seed 的不可偵測界線，明定於 §3.2。

**journal 格式界線**：M6 新意圖使用上表 schema5 的完整 exact 欄位，legacy intent 仍以舊 exact 欄位解碼；schema5 可同時容納兩者，不改原 `CandidateId`、`DoorKey`、SELECTED 與 MEASURED。首次 schema5 checked 寫出前，先確認 M5 ledger 初始化且沒有未確認寫入；寫出後不可降回 schema4。目前只產生 `FamilyCapabilityVersion=0`，不開 Nether／End 量子艙；`NEW` 的 intent 欄位仍須與 Context exact 一致。保留 codec 編碼範圍不代表啟用 capability 1，不從程式版本補舊 frozen 能力。

**Task 9 journal 原生 slot 權威與已知限制（2026-11-05 使用者縮範圍裁示）**：`quantumchamber_sessions.dat` 的正式首次載入仍須由本模組對同一份 compressed bytes 做版本探測與 schema5 有界解碼，不得先借 `PersistentStateManager.get/getOrCreate` 的未快取 unlimited 檔案路徑。新 `PersistentStateManagerJournalCacheMixin` 本片只以 `@Shadow loadedStates` 提供不外洩 mutable map 的唯讀 ABSENT／PRESENT_NULL／SAME／OTHER 四態與私有 fence；State 首次只在 ABSENT 登記 LOADING，安裝前再核 ABSENT、用原生 `set` 安裝、安裝後核 SAME 才標 INSTALLED。首次遇已佔槽（含 null）拒而不覆寫；後續本模組每次 get 與 owned save 先核 exact manager／slot SAME，不符即拒回、拒寫並停止本模組寫入，不丟 dirty／current／flushed／pending。弱 binding、LOADING 重入拒、雙 server 隔離仍守。此模式**不在當下攔截**其他 mod 對同一保留 id 的公開 `set`，也不攔其以其他 type 的公開 `get` 回填；本模組只在下一次 get/save 發現後 fail closed。在那之前，原生 autosave 可能已把 foreign state 寫進同名檔，此為使用者本次明確接受的風險，不能宣稱事前保護已成立。只有其他 mod 碰此保留 id 才可觸發的三個原生 hook（get HEAD／set Map.put／get回填 Map.put）與專用真 intermediary/JDI gate 移到 Task 8-H H5，M6 收尾前與 H1–H4 一起 triage，不預先waiver。既有 M4 獨立空schema3冷載測試仍只准testmod在全空／無未確認權威時精確撤自己的舊 slot/binding，再經正式有界 reader重載；不提供 production 任意 reset/adopt。此只讀 mixin 依 compile／remapJar、具名 GameTest、完整run-all及雙平台CI驗證，不另設Task9 production intermediary gate；不以這些驗證冒稱 H5 已完成。

### 3.1.1 完整設定資料閉包與執行相容性

**適用範圍（v1.4）**：本節的完整閉包契約（每 role 資料閉包、alias mapping、template 真absence／只讀解析、C1c 例外、RecipesUsed 邊界、Model B、exact resolved template encoding、native gate 門檻）自 v1.4 起綁定 ProviderVersion≥2，排程於 Task 8-H；內建 version 1 的閉包深度依 §3.1.3。以下四項對所有 ProviderVersion 皆為 binding：「GenerateStructures唯一owner」整段；canonical 編碼的 key 排序／型別／次序／byte order 規則；「不能由codec表示的 Java 語意不是可偵測保證」段；「Fingerprint exact 相同只保證…不進fingerprint…不得持久化」段（「本節完整設定資料圖」對 v1 讀作「§3.1.3 envelope 所涵蓋資料」）。本節條文、既有 8A／8B2 局部結案證據與所有 retained FAIL 原判不因此改變，也不得以 §3.1.3 的通過冒稱本節已驗。

本節明確區分可驗證的生成設定資料與 Java 執行語意；不是允許省略已知生成依賴。每 role 從實際 DimensionOptions 起始，包含其原生序列化設定、原版實際相關的 structure sets／placements、全部可達 dynamic／reloadable registry definitions、實際 tag 內容，以及生成設定引用的 structure template 等資料資源與其轉遞引用。RegistryEntry、RegistryKey、TagKey、Identifier-only、硬編碼的可能資源選擇及 template NBT 引用，都須由固定 1.21、經 review 的有型別 adapter 解析；不能以全域 id 字串掃描或 factory callsite 數目冒充完整性。不是 DimensionOptions 欄位、但原生生成路徑實際讀取的資料控制值也不得漏掉，例如 GeneratorOptions.shouldGenerateStructures；只排除另存的 root seed，不能把其他 seed／salt／noise／placement 參數一起移除。

**GenerateStructures唯一owner**：對M6內建profile，來源與目標各自的SettingsSnapshot.Parameters保存該值，target再由checked catalog持有，不另建立重複mutable權威。來源canonical或legacy family取當時exact有效原生控制值；來源schema2 family取已驗catalog snapshot的凍結值並核native owner一致。random、same-seed、toggle三策略都保留來源GenerateStructures；random仍只換root seed與Overworld preset為normal，toggle仍只翻轉Overworld preset。重建既有snapshot以凍結參數為準，不改採開服當下canonical global flag；lease的live控制資料用於來源採集／實際比對，不能覆蓋frozen值。Provider回傳的完整設定資料須包含此控制，canonicalizer納入fingerprint；自訂provider也必須能從其凍結參數／受審版本重建所宣告控制，不得悄悄依賴最新global值。

保留原設定與 alias mapping 本文；symbolic alias source 只能按來源物件型別、欄位角色及該次 edge occurrence 判定，不得把某 id 加進整 role 的全域豁免集合。所有可能 target 必入圖，不以單次 seed 選中的分支代替。相同 id 若也是 target 或普通引用，仍須解析。Required reference 缺失必 fail closed；只有原版／受審 adapter 明確允許的 optional 空值可成立，且要記錄 typed presence／empty／resolution 狀態，不能靜默略過。

資源解析必與受支援原生生成路徑的實際來源／優先序相符，不能拿不同來源的同名 vanilla 檔替代目前有效內容。若涉及 saved／cached template 覆寫，須證明能取得有效、可重建的設定資料，或將該情境具名判為不支援；不得默默只讀datapack便稱 actual closure。Pre-authored generation template 及其 jigsaw pool／loot／component 設定是生成輸入，不等於複製玩家既有 chunk/entity 的 M7 literal snapshot。

**原生furnace歷史欄位的精確邊界**：對固定1.21原生furnace／blast_furnace／smoker owner的template block-entity根欄位RecipesUsed，依AbstractFurnaceBlockEntity的契約視為「recipe id→已完成燒製次數」literal history。完整有效template NBT仍納入canonical payload，該compound的存在／空值、每個原始key及型別／int值都保留；不得刪除、只留key、改成零或用重解碼預設值代替。本版支援RecipesUsed為NbtCompound、key可由原生Identifier.of解析、值為NbtInt；其他形狀具名unsupported，不借原生getInt容錯默默改型別。合法但目前不存在的recipe id可以保留，因原生讀入不查RecipeManager，後續領XP時的get(id)為Optional；不得因此欄位建立required recipe-definition edge或加入整份recipe registry。未知owner／其他occurrence的同名欄位不能獲全域豁免，仍依其真正consumer契約接納或拒絕。

本條只界定歷史欄位，不排除真正生成配置：Items的item／component依賴、BurnTime／CookTime／CookTimeTotal及其他raw NBT完整保留，原有jigsaw／loot／component required edges照舊。此generation settings snapshot凍結生成設定與模板初始資料，**不凍結生成後的持續燒製、XP、配方解鎖或後續遊玩recipe selection**；有料／燃燒中的furnace後續行為仍依當時遊戲環境。不能把這項界線說成任何世界變動／生成相關caller都不讀recipes（例如替換既有furnace可發XP）。若另有真正生成設定或受支援adapter把recipe definition作生成輸入，必驗actual effective RecipeManager／lookup的同epoch來源與可重建typed edges，或具名拒絕；不能只讀同名vanilla JSON或泛略所有recipes。這不新增M7玩家世界複製或完整後續遊戲模擬保證。

Canonical map key、節點索引以明列的穩定規則排序；列表及可能影響原生隨機選取／迭代的次序必保留。Tag 不能一概視為無序集合：對 RegistryEntryList 等原生以 list/index/getRandom 消費的資料，保留實際有效成員次序；僅在已證明無次序語意時才能排序。Visited 依節點種類、registry/resource 類型與 canonical id 分派，同 id 不同類型不能合併；共享／cycle 每節點編碼一次並保留引用，不截斷未展開邊。所有有限的節點／edge／resource解碼／總canonical bytes預算必在實作前由 native gate 量測、明列並經 review；超限、缺 required target、解碼失敗、未知 codec/type/reference 語意都拒建立／解析snapshot，不用預設值替代。每 role component與全family輸出使用同一資料契約，不因全registry/resource dump把無關Nether/End資料塞入Overworld。

Static registry的原生可編碼值與block state properties按實際值納入。不能由codec表示的Java implementation、static mutable值、opaque callback行為不是fingerprint的可偵測保證。可持久化provider限受審adapter與明列的runtime相容性前提：生成相關static/dispatch行為須符合受支援1.21語意；已知／宣告的改寫若無受審adapter、相容性版本與必要資料則拒絕。未知或檢查無法確認的相容性狀態不得冒充已支援；固定版本、namespace、JAR/extension身分與啟動檢查只是相容性證據，不證明任意Java值或語意從未改變。受信任Java擴充也不能自行提供fingerprint來免除此門檻。

Fingerprint exact相同只保證本節完整設定資料圖相同；生成重現另以相同root seed、受支援執行環境及相同生成前提為條件。它不保證偵測任意同id/version的Java/mixin/startup改寫，不包含玩家編輯、既有chunk/entity歷史或M7 literal snapshot。以實際資料內容／canonical id及adapter版本形成穩定表示；server identity、epoch、物件身分、PID、nonce、實體檔案位置與runtime mapping名稱只作驗證證據，不進fingerprint。資料無法跨同版本重啟精確重建者不得持久化。

#### 原生template真absence與只讀解析（v1.3）

固定1.21 exact SinglePoolElement的Identifier location經原生getStructure→StructureTemplateManager.getTemplateOrBlank時，僅「當次所有適用來源可權威證明真不存在」可用quantumchamber:m6-template-resolution-v1表示ABSENT_NATIVE_BLANK。這不是全域template optional；未知consumer／implementation、required用途及任何當次source error／unknown／不一致仍fail closed。ignore-first與absence是兩個獨立能力，不互相推導。共享data node不能省略每use＋既有alias scope的能力／結果驗證；Single與unknown兩種先後順序皆不得污染。

**live cache觀測只有五態**：UNOBSERVABLE、NO_ENTRY、NEGATIVE（entry存在且Optional.empty）、PRESENT_CONSTRUCTOR_BLANK、OTHER_PRESENT。借用manager禁止getTemplate/getTemplateOrBlank/unloadTemplate/setResourceManager及任何填入／清除cache行為；不得用會產negative cache的getter當只讀觀測。觀測器只讀exact native cache的key存在、entry／template identity及受支援資料；不可觀測拒。原生建構／重建只能在隔離owner，不共用live manager/cache/可變template instance；不能把隔離OrBlank的副作用施到live。

**C1c私有隔離擷取例外**：live template仍以matching同manager exclusive lease及association守門；隔離manager/template只能由manager safe consumer在該live窗口內私有直接建立、登記且不escape，以有效live lease＋私有ownership的`ISOLATED_OWNED`能力讀四欄/完整NBT，不取得第二exclusive lease、不放寬一般跨manager拒絕。公開消費方法只收permit、當次Session及owned值參數，只回深owned DTO；禁止raw getter或公開callback/factory/supplier外傳RM/DataFixer/lookup/manager/template。此例外須先經獨立窄審C/I0才可實作，不能當已通過runtime。

隔離ctor的RM必與live manager/context同identity，Session必與context同identity且GENERATED.normalize匹配live generatedPath；DataFixer/blockLookup只在manager safe consumer內讀private shadow並以原identity傳入ctor，DataFixer限固定原生Schemas.getFixer()、lookup直接重用live manager原filtered實例，其原生Delegating.getBase()須與context BLOCK read-only base以==相同，isolated實際lookup另須==原filtered實例；Context.templates須綁server實際manager identity（正式發布由8B3驗），不解讀opaque predicate、不接受外部自選或重建等值wrapper。PRESENT只呼隔離原生createTemplate後讀四欄/writeNbt，ABSENT只用私有新constructor blank；隔離重建不得使用live manager/cache/template或呼隔離get/save等來源副作用。前後重核source/cache identity與內容、Session/path及epoch/inflight/stop；錯配、未知或漂移均拒。finally先失效permit/清除隔離登記再釋放live lease。raw來源引用不得經helper的return/欄位/closure逸出，100ms單次live hold I-1仍未關、不得發布snapshot/FP。

PRESENT_CONSTRUCTOR_BLANK必須exact固定原生StructureTemplate class／已核class身分，blockInfoLists為0項、entities為0項、size三int皆0、author為原生constructor的"?"；本固定class的四個instance欄位均已核。另完整native writeNbt與隔離新constructor的NBT exact；不能只比writeNbt。0個palette與1個空palette不得視為同一internal shape，即使writeNbt恰相等；未知subclass/新增設定欄位拒。author是本版保守constructor admission條件，不宣稱Single原生會讀author，也不另把class/path字串塞FP。

當次source先按原優先序Saved generated→development SNBT（適用時）→實際ResourceManager，區分PRESENT／PROVEN_ABSENT／ERROR／UNKNOWN。只探測到高優先present且成功時採該完整值；高優先error不能以較低來源或cache遮掩；未知來源、不可讀、FNF來源原因不明、stat父路徑不可判、decode錯、epoch/source不穩定皆拒。ResourceManager.getResource present但open FNF／IO必為error，不是真absence。不可把native吞錯fallback當來源權威。

|cache態|當次來源|可接受結果|
|---|---|---|
|UNOBSERVABLE|任何|拒|
|NO_ENTRY|真absence／present可重建|分別ABSENT_NATIVE_BLANK／PRESENT|
|NEGATIVE|真absence|ABSENT_NATIVE_BLANK；當次present（即使空）拒不一致|
|PRESENT_CONSTRUCTOR_BLANK|所有來源當次真absence，完整native等價且coherent|ABSENT_NATIVE_BLANK；當次present拒|
|OTHER_PRESENT|當次present且native完整重建exact|PRESENT；當次absence或不一致拒|

當次ERROR／UNKNOWN／不穩定優先於以上所有可接受列。cached genuine constructor blank＋當次全absence可接納，**不追查歷史建立原因**；過去error來源已移出且當次已真absence，不永久污染snapshot。當次error仍存在必拒。保留歷史error receipt是證據保全，非新的provenance owner或FP欄位。


**Model B正常遊戲操作的可執行候選**：依plan Task8已選per-manager gate，現有server/context lease持有exact server、server-thread、manager、Session與ResourceManager identity及同reload epoch；同步解析窗口不yield/pump server tasks、不等worker/future。正常server-thread結構方塊保存、dev import/export與reload apply不會與同server-thread snapshot交錯。外部檔案變更須先退出snapshot窗口，再經reload／重啟建立新epoch；stat/hash僅附加違約偵測，不宣稱抓到任意外部ABA。

正常worldgen worker的getTemplate/OrBlank會填cache，仍須協調；不能因外部ABA排除就省cache gate。foreign/未知source/writer不自動admit。支援條件不是目錄唯讀：正常可寫saved/dev目錄可通；但有已知繞gate async writer／改cache或template的模組、未知manager/source implementation且無受審adapter時，該context拒TEMPLATE_WRITER_UNSUPPORTED或TEMPLATE_SOURCE_ADAPTER_UNSUPPORTED。無epoch/owner/gate能力則TEMPLATE_CONTEXT_UNBOUND，不默用vanilla或固定pack替代。

來源變更以正常操作/同owner gate與epoch協調，不另建歷史provenance。窗口前後仍核identity/content/epoch；native受控writer-token若變便拒SOURCE_UNSTABLE／CACHE_UNSTABLE／LEASE_STALE。外部不合作ABA不是支援保證，不能把前後hash等於證明其不存在。

**exact resolved encoding**：每個template data node以同一envelope存一次，exact四鍵，禁止額外鍵：

|鍵|NBT型別／值|
|---|---|
|Contract|NBT string，恰quantumchamber:m6-template-resolution-v1|
|TemplateId|NBT string，Identifier.of(x).toString()==x的canonical id|
|Resolution|NBT string，恰PRESENT或ABSENT_NATIVE_BLANK|
|NativeTemplate|NBT compound，完整實際native writeNbt資料；不刪任何tag/type/order語意|

沿既有canonical NBT規則編碼（compound鍵canonical順序、保NBT型別與list順序及固定byte order）。Contract承擔既有resolved adapter版本角色，不加snapshot第五鍵以外欄位／Parameters，不升尚未發布的ProviderVersion。發布後不能同contract靜默改encoding。PRESENT恰空與ABSENT_NATIVE_BLANK必因Resolution不同bytes。完整NativeTemplate仍交原有typed NBT adapter保全部pool/loot/component等真refs；不能只保envelope或丟掉原payload。OS/path/mapping、source proof/stat/error文字與cache identity只入證據，不入FP。

ABSENT重建依Resolution建立新的exact native constructor blank，再驗完整四欄／NBT；不能把NBT反解的一個空palette當constructor blank。PRESENT重建走原生createTemplate且保既有actual/rebuild完整比對。source/absence改變即resolved bytes與FP可辨；外層snapshot仍exact五鍵、512bytes、32byteFP，沒有歷史error欄。

### 3.1.2 Server-bound context 與完整 gate

Provider metadata在啟動註冊階段凍結；這不代表server的registry、tags或resources已ready。實際createSnapshot／resolve需明確的只讀server-bound lease，綁exact server identity與同一reload epoch的dynamic registry、reloadable lookup、有效resource／template解析來源及必要生成控制資料；不提供root seed、原始MinecraftServer或無限制mutable lookup給provider。Acquire與解析限受支援server thread；return前再次核epoch有效，server A lease不可用於B。不得以Builtin未綁tags的lookup或全域fallback冒充實際context。

Context publication必以固定Fabric／Minecraft的真實生命週期證據釘選：對 ProviderVersion≥2，須在初次資料refresh／tag binding與資源就緒後；對 8-core／`SHALLOW_V1`，「資料就緒」定義為 lease registries 與 `server.getRegistryManager()` 同 identity 且 `GeneratorOptions.shouldGenerateStructures` 可讀（見下方 8-core 最小 lifecycle）；兩者皆須在 M6 journal/catalog/candidate 第一次需解析 snapshot 之前，不能靠 listener 註冊順序猜。Reload開始先原子失效所有舊lease；成功且各資料來源確為同epoch後才發布新context；失敗維持unavailable，不能混合前後registry/tag/resource。Server stop立即失效並釋放本mod的owner引用，不關閉借用的server resource manager。Server-thread限制不能取代reload非同步視窗的epoch協定；首版不跨解析呼叫快取fingerprint，單次解析內共享節點可去重。Legacy schema1／M4路徑維持既有契約，不因mod-init提前解析尚不存在的M6 context而被改寫或無故阻斷。

（ProviderVersion≥2／Task 8-H）Native gate須在每次launch前凍結並保存source/class/JAR與classpath hash，結束後重核；不以batch後快照補造launch-time證據。對normal/amplified的三role逐一保存完整／失敗判定，任一role未閉合就不可發布 ProviderVersion≥2 的全family fingerprint或snapshot，不只檢最後的全域map。Native資料閉包、context時序可行性、後續正式provider/512-byte/32-byte/TDD/雙GREEN/full與獨立task review各有門檻；prototype診斷指紋不等於正式snapshot或production核准。

**8-core 最小 lifecycle（v1.4）**：Task 8-core 只要求以下可驗事實：(a) mod init 註冊兩個內建 provider 並立即 `freeze()`；本輪不接受擴充 provider 註冊（見 §4）。(b) 以固定 Fabric 事件透過 `Minecraft121SettingsLifecycle` 接 `Minecraft121SettingsContexts`，四事件的 thread／ticket 執行細節依下方 2026-11-05 窄修；發布時 `Sources` 的 registries／resources／templates／session 取自同一 exact server 的實際實例，GenerateStructures 取 `GeneratorOptions.shouldGenerateStructures` 當時值。(c) `SHALLOW_V1` 所需資料只有 dynamic registry manager 與該 bool，兩者在 server 物件建構時已就緒，故發布點釘於 `SERVER_STARTING`；GameTest 必以 `==` 斷言 lease registries 與 `server.getRegistryManager()` 同 identity，不以 listener 註冊順序或程式碼位置推論。(d) lease 對 provider／canonicalizer 只暴露 `registries()`（immutable `DynamicRegistryManager.Immutable` 或等價只讀 view，來自 `Sources.registries`，型別不符即 `CONTEXT_UNAVAILABLE`）與 `sourceGenerateStructures()`，兩者先 `requireValid()`；不暴露 server、ResourceManager、template manager、Session、root seed 或 mutable lookup。(e) 一個 GameTest scenario 覆蓋自然 startup 後 `acquire` 成功、registries identity、`createSnapshot`／`resolve` normal 與 amplified 各一；此 GameTest **不得**觸發 `reloadResources` 或替換 RM，因為既有 S1a 自然啟動 observer（retained，不改）會把同一 server 的任何 reload 記為 `UNEXPECTED_RELOAD`。lifecycle owner 分三層：純核心（`Object server` 鍵的 per-server Contexts 狀態機）、typed helper `Sources.of(MinecraftServer)`、Fabric 接線層；接線層經可注入的 registrar seam（`register(Event<T>, T)`，production 實作呼 `event.register`）與可注入的 `Function<MinecraftServer, Sources>`（production 為 `Sources::of`）登記恰四個事件。JUnit 以 recording registrar 斷言登記的事件 identity 恰為 `SERVER_STARTING`／`START_DATA_PACK_RELOAD`／`END_DATA_PACK_RELOAD`／`SERVER_STOPPING`，並以 null server 與假 Sources factory 呼叫擷取到的四個 listener，斷言委派到 recording core 的對應方法與同一 server 參數；真 server 的 `Sources.of` 與 registries identity 由 GameTest `==` 斷言守。核心層的 reload／stop 狀態轉移由 lifecycle 純核心 JUnit 守，Contexts 本身的 reload／stop 語意由既有 Contexts JUnit 守；mutation 各指對應層的具名 killer。真 server 上的 reload 情境、多 inflight 先序、controlled future 失敗、off-thread 交錯、refresh seal、source bridge、consumer 全圖與三 bundle 屬 Task 8-H（H2／H4），8-core 不宣稱已驗。

**8-core 事件執行緒窄修（2026-11-05）**：上段 (b) 的四事件箭頭表示生命週期語意，不要求直呼 `Minecraft121SettingsContexts` 的 owner-thread legacy reload／stop 方法。固定 Fabric `START_DATA_PACK_RELOAD` 可在呼叫 `reloadResources` 的外部 thread 同步觸發；Lifecycle 必在 callback 返回、任何 server 排程之前，以 exact server 的短 identity-map monitor 與既有 `RevocationHandle.revokeReloadStart()` 同步撤銷舊 lease，再由 owner 以 exact ticket `adoptReload`。兩次 START 重疊時永久 unavailable，不依 FIFO、最新 ticket 或 RM identity 猜 END 配票。`END_DATA_PACK_RELOAD` 沒有 request ticket；只有 live、未重疊、唯一未完成票且在 owner thread 才可用原 prepare claim 完成，不能在 final finish 重新 claim。失敗 END 不取 `Sources` 且維持 unavailable；下一次獨立單票成功可恢復。成功 END 先守 live/owner/ticket，才從同 server 延後擷取 `Sources`，回到短鎖內再核 exact entry／原票／generation 後發布；factory 失敗或中途 stop／overlap 均不發布。stopped／poisoned／無 entry／異常 foreign END 在 factory 前短路。`SERVER_STOPPING` 不論 callback thread，須先同步 `revokeStopAndDetach()` 再移除 exact entry；不關閉借用的 server RM，不靠待執行的 queued task 撤銷。初次 `SERVER_STARTING` 在取 `Sources` 前預留 entry，factory 後再核 stop／票狀態；queued action 只持弱 entry／ticket metadata，不保活 server/native/owner thread。

此窄修仍只有 8-core 的 `SHALLOW_V1` 最小來源契約：registries 與 exact server 的 `getRegistryManager()` 同 identity、GenerateStructures 可讀，真自然 startup GameTest 證首用。接線層除 registrar／`Sources` factory 外，可注入最小 owner 判斷與 dispatch 函式供 null recording seam；JUnit 須由真正擷取的 binder listener 殺掉 stopped／poisoned／foreign END 提早呼 factory，另驗 foreign START 撤銷早於 owner dispatch、foreign STOP 立即斷邊、重疊不能重發、單次失敗後單票恢復。最終 finish 必傳原 claim action。這些是 adapter 拒絕與 metadata 協定，不是 8-H 的真 server reload、多 inflight 成功、tag／loot／template 同 epoch、refresh seal、source bridge 或三 bundle 證據。

### 3.1.3 `SHALLOW_V1` 最小閉包（v1.4，內建 ProviderVersion 1）

**定義**：對 lease 的 immutable registry view，provider 依 Parameters 以 lease registries 的 **Reference entry** 建立三 role `DimensionOptions`，不讀 world preset：Overworld 為 dimension type `minecraft:overworld`、noise settings 依 preset `minecraft:overworld`／`minecraft:amplified`、multi_noise biome source preset `minecraft:overworld`；Nether 為 `minecraft:the_nether`／noise settings `minecraft:nether`／multi_noise preset `minecraft:nether`；End 為 `minecraft:the_end`／noise settings `minecraft:end`／`minecraft:the_end` biome source。canonicalizer 以 `DimensionOptions.CODEC` 經同一 lease registries 的 `RegistryOps`（NBT）encode 每個 role，registry 引用保持 canonical id 字串，不做 transitive 展開，不讀 template、tag、loot、component、structure set 內容；encode 後逐 role 結構檢查 `type`、`generator.settings`、`generator.biome_source.preset`（適用時）必為 NBT string 且 `Identifier.of(x).toString().equals(x)`，`biome_source` 不得含 `biomes` 直列，任一欄非 id 字串即 `SHALLOW_INLINE_ELEMENT` 拒。canonical envelope 恰四鍵：`Contract`(string，恰 `quantumchamber:m6-settings-shallow-v1`)、`Roles`(compound，鍵恰 `overworld`、`the_nether`、`the_end`，值為各 role 的 canonical NBT)、`GenerateStructures`(byte 0／1，恰等於 Parameters 的值，不取 lease live 值)、`Parameters`(compound，與 snapshot Parameters 相同)。canonical NBT 規則沿 §3.1.1 對所有版本 binding 的編碼規則：compound 鍵以 Unicode scalar 次序（等同無號 UTF-8 bytes 字典序）排序、list 保留原生順序、保留 NBT 型別、big-endian；唯一 owner 是既有 `Minecraft121SettingsGraph` 的 NBT 編碼器抽出的共用純函式，不改其既有行為。canonical 長度以整份 envelope 的 canonical bytes（含欄名）計，設計上限 8192 bytes，超限拒；8C 結案 note 須記錄實測三 role canonical 長度。snapshot 外層仍 exact 五鍵、512 bytes、32 byte FP。

**fail closed**：`OverworldPreset` 非 `normal|amplified`、id 與 preset 不符、任一 role 缺失或 options 為 null、lease 無效（建立前與回傳前各核一次）、lease registries 缺所需任一元素（如 datapack 移除 `minecraft:amplified`）、codec encode 失敗、`SHALLOW_INLINE_ELEMENT`、canonical 超限，均不得建立 snapshot；`createSnapshot` 對未綁定閉包模式的任何 (ProviderId, ProviderVersion)（含已成功 `register` 的受信任擴充）一律拒，直到該 provider 的閉包模式經本 spec 修訂與獨立 review；`resolve` 時 provider id／version 不存在、Parameters 不過 schema、重算 FP 不等，該 U fail closed，不用預設設定替代。canonicalizer 不取得 template gate exclusive，不呼叫 `Minecraft121TemplateSettingsAdapter`／`Minecraft121RegistryClosure` 的 live 路徑；100ms live hold I-1 不在本模式路徑上。

**已知限制（controller 依裁示推定接受；M6 正式版是否只出 `SHALLOW_V1` 在 §8 的 8-H triage 交使用者裁示；須寫入 Task 22 人工清單）**：`SHALLOW_V1` 的 FP 只對上述各 id 字串、chunk generator／biome source 型別 id、Parameters 與 GenerateStructures 敏感；對**任何被引用 registry 元素的內容**（dimension type、noise settings、density function、noise parameters、biome、structure set／placement、tag、template、loot、component、world preset）一律不敏感，開檔時不會偵測。因此 v1.4 下三種內建 profile 的「已驗設定」與「同 seed 同設定地形相符」都以來源與目標開服時 datapack／mod 配置相同為前提；完整偵測屬 ProviderVersion≥2（Task 8-H H1）。此限制不得隱藏或改寫為已偵測。

**門檻**：JUnit 具名 RED→GREEN 至少涵蓋：兩次各自真載入 registries 的 canonical bytes 與 FP 逐 byte 相同；normal／amplified FP 不同且 canonical 分別含 `minecraft:overworld`／`minecraft:amplified` settings id；GenerateStructures 只翻 Parameters 而 lease 值固定時 FP 不同；`Contract` 值精確；`Roles` 鍵為 canonical 排序而非宣告序；未知 preset、id 與 preset 不符、缺 role、無效 lease 拒；以 Direct entry 建構的 role 拒（`SHALLOW_INLINE_ELEMENT`）；canonical 不含 noise settings 內容鍵（如 `noise_router`、`sea_level`）；canonical 超限拒；以及一個**限制見證**：第二份 registries 的 `minecraft:amplified` 內容確實不同（例如 `sea_level`）而 canonical bytes／FP 相同。技法界線：超限 RED 得以 package-private 純函式直接餵 oversize envelope，不得為此放寬 provider 路徑；限制見證與兩次載入比較都須以 `RegistryLoader.loadFromResource` 真載入（限制見證另加覆寫 `minecraft:amplified` 的測試 pack），走同一 codec 路徑取 FP，不得改 NBT 後重算冒充。GameTest 依 §3.1.2 8-core 最小 lifecycle (e)。不要求 8B3-F 三 bundle 與跨 mapping 逐 byte 比較；但 §8 的 Task 8-H triage 須評估 intermediary main-only 與 named 的 `SHALLOW_V1` FP 是否相同。

### 3.2 schema1 與既有 catalog 相容

本次不遷移 schema1、不物化 Nether／End descriptor、不修未物化 role 的維度 key；上述待辦依使用者裁示取消。既有 catalog／allocation／frozen settings 資料形狀保持現狀，舊 UUID、Overworld key、seed／storage 與 checked 身分不得改寫。現存 schema／provider 可表達三 role 不構成三 role 功能或 future 排程承諾；M6 新 session／intent 的 FamilyCapabilityVersion 持續為 0。涉及已保存資料的相容與 fail-closed 仍依正式 reader／owner 門檻。

### 3.3 提交與失敗窗口

**候選生成時**先以已 freeze provider 與 exact source family 解析 `SourceSettingsSnapshot`；依 frozen `PolicyVersion` 算 profile、目標參數及 `TargetSettingsSnapshot`，逐份驗 512-byte 上限、重算 fingerprint、嚴格 checked 寫入 schema5 intent；此時不配置 U 或生成 chunk。選門時先 checked 落盤 M4 `MEASURED+SELECTED` 的 exact receipt，再 checked `COLLAPSING` intent，然後 checked allocation ledger schema2 entry（先做必要的 v1→v2 envelope upgrade），最後 checked catalog schema2 的 seed／profile／**目標 snapshot**／三 role identity（先做必要的 v1→v2 envelope upgrade）；每一步 strict 讀回，比對 intent→ledger fingerprint→catalog snapshot。catalog 確認前不建立任何新 role storage 或 `ServerWorld`。同 token 重試只可回同 UUID、seed、profile 與 target fingerprint；source snapshot 必仍可由 provider 重算，不能以重啟當下玩家世界或 runtime RNG 取代。任一 authority 不一致或 provider 缺失／內容變動，標成 authority corrupt／SEALED，保留原檔，不接受 orphan storage 冒充正確紀錄。

在 intent 前 crash：只看已確認的 M4 authority；intent 後、allocation 前：同 token 續配；allocation 後、catalog 前：同 UUID、seed 與 profile 補 catalog，先檢查 orphan storage，存在即 fail closed；catalog atomic move 完成但讀回失敗：current 保留未確認，停止物化，重啟或下次寫入先 strict 收斂，不能另配；catalog 已確認但 role storage／世界建立中 crash：依 catalog 同 key 續作，不刪玩家世界，對 partial storage 做 ownership／格式檢查，無法證明安全即停服或 `DISABLED` 並保留證據；profile 參數或某 role 損壞：不可退回 vanilla 或 canonical，也不可重配 UUID，受影響 U 不可進入。`EXISTING` 只接受已確認且完整、相容的 catalog 定義。每個轉移的 flush、force、atomic move、strict readback、exact compare 與 roll-forward／restore 分類沿用 M5 §6；不要以 memory current 代替 flushed 權威。

**provider 缺失時「authority corrupt／SEALED」的界線（2026-11-06）**：上文為安全拒絕結果分類，不能在 provider 已無法解析真 checked schema5 M6 intent 時承諾同一 journal 必能持久追加 `SEALED`。正式 `SessionRecoveryState` 的有界 load 會對所有 M6 intent 用 server-bound validator 重算來源／目標 snapshot；provider 不可用時狀態 unhealthy、拒回而保原檔，checked write 的候選 preflight／readback 也會拒，不能繞過 validator 來寫 SEALED、改用預設 provider、重抽或另配 U。只有 validator 可通過、正式 resolver 實際回 `ResolveResult.Sealed` 且 checked 寫入成功的情境，才可稱持久 SEALED。Task12 驗收須分首次取 snapshot 前缺 provider（不得產生 checked M6 intent）及真正式 receipt 已 checked 後才缺 provider（正式 load 拒、journal bytes 不變、無新 allocation/catalog/storage），不得把 testmod 故障注入冒稱自然跨版本重啟已驗。

**未知來源 preset 的持續供電拒絕（2026-11-06 Task12 full 根因）**：flat 或其他無法精確重建的來源，只有 M6 `NEW` bucket 在求來源 snapshot 時拒絕，`SOURCE`／`EXISTING` 照原規則。該 session 仍經正式安全返還與租約清理；若持續 HIGH，返還後不得立刻再建立下一個相同的新 session。只有核實同一原艙及已載入供電鄰域、實際讀到 LOW，才解除這次未知 preset 專用的重試阻擋；其後再次 HIGH 可重新嘗試。正常成功、一般 IO／provider 錯誤、其它拒絕與 M5 終局不得套用此阻擋，亦不得藉 presence `NONE`、load sync 或保存的 `wasPowered` 清除。阻擋以原艙 Controller NBT 正常保存／載入，但未完成存檔前的任意 crash 不保證持久；不得冒稱 checked journal durability。缺 callback 或 owner 身分不符不得假稱已設阻擋，也不得阻斷當前 session 的安全返還。
**角色物化政策**：只物化 checked catalog 對應的 U:OVERWORLD，開服／按需物化沿既有 lifecycle 契約。未物化的 Nether／End descriptor 不啟用，處理 descriptor／維度 key 不一致的待辦已取消；本次不改資料格式／既有 row，也不遷移 schema1。所有目前 U 的 storage／UUID／live owner 仍須 exact，失敗世界不得刪除或假裝 rollback。

即使 catalog／snapshot 現有形狀保留三個 role 資料，也只啟用主世界；所有新 session／intent 的 FamilyCapabilityVersion 持續為 0，不排家族物化或升 capability 1。U 經門使用共用原版 Nether／End；地獄門回程一律落在原版主世界，終界出口照原版看床。只有 §5 的 exact live U 主世界死亡 fallback 套用新重生 task，登入與其他維度死亡仍沿既有路徑。

## 4. 生成策略與選擇（主控設計預設）

可行作法：A 從凍結的 `generationSeedMaterial` 決定 seed／profile，窄且可重現；B 另設 profile 抽選 ledger，較易動態調權但增加跨檔交易；C 配置時用 runtime RNG，重試會漂移。選 A。profile registry/API 最低契約是 `register(ProfileProvider{id, version, parameterSchema, adapterCapabilities, resolveGenerationSettings})`、`freeze()`、`createSnapshot(id,version,parameters,lease)` 與 `resolve(snapshot,lease)`；canonical output／fingerprint由受審 canonicalizer計算，provider只回設定資料：本 mod 與受信任 Java 擴充只能在**server 讀 M6 catalog／candidate 前**註冊；相同 id＋version 重複即拒絕啟動，freeze 後禁止更改。（v1.4：本輪 `freeze()` 於本 mod init，故實際只有本 mod 的兩個內建 provider 能註冊；擴充註冊窗口與 freeze 時點留待未來 policy review 一併修訂。）provider deterministic、無世界副作用，回傳可依 §3.1 canonical encoding 取 fingerprint 的完整三 role output；存檔所指 provider 缺失或 fingerprint 不符則 U fail closed。API 可查詢及嚴格解析已註冊 provider；M6 候選 policy **只選下表三種內建策略**，擴充 provider 不會被偷偷納入權重，需未來經 review 的新 policy 才能配置它的 U。此 API 能在啟動期實際註冊、驗參數與解析 snapshot，不是只包 enum；不建立通用腳本／插件執行器。

| 新 `NEW` profile（本版採用） | root seed／差異 | 驗收 |
| --- | --- | --- |
| `RANDOM_VANILLA_V1` | 按下文固定雜湊取不同於來源的 root seed；三 role 使用內建 provider `quantumchamber:vanilla_normal_v1` v1（`OverworldPreset=normal`）的設定，`SHALLOW_V1` 下「已驗」僅指 id 層級（§3.1.3 已知限制），GenerateStructures保留來源值 | 同一意圖重啟完全相同；和來源 seed 不同，生成有可觀察差異 |
| `SAME_SEED_FRESH_V1` | 精確複製 source root seed **及來源各 role 的已驗 generation settings snapshot**；新 UUID／空 storage，不複製方塊、實體或玩家歷史 | 同 seed 同設定的原始 terrain／biome／structure 相符，來源玩家建築不存在；日後修改互不影響 |
| `SAME_SEED_TOGGLE_AMPLIFIED_V1` | 同 source root seed；來源 Overworld 為 normal 就改 amplified、為 amplified 就改 normal（目標 ProviderId 與 `OverworldPreset` 一併切換為對應的內建 id）；Nether／End 設定照來源 snapshot | 相同 seed，Overworld 有可重現地形差異；Overworld 重啟不變；未物化 role 僅保留既有 snapshot 資料形狀 |

**分階段 policy snapshot（主控設計預設）**：切片②新 intent 固定 `PolicyVersion=1`，只選 random；切片③前半新 intent 用 `PolicyVersion=2`，從 random／same-seed 各一半選；切片③ mutation 驗收後的新 intent 才用 `PolicyVersion=3`，三類等量。切片②／③的 `FamilyCapabilityVersion=0`；僅切片④ family／routing／respawn 驗收後新產生的 intent 固定為 1，舊 intent 不升版。每一階段只凍結當時已驗的 profile；既有 intent 永不升 policy 或重抽。三類等量是為了讓玩家在少量新宇宙中能實際看見三種結果，並簡化第一輪驗證，不冒稱最終平衡。choice 取 `SHA-256(UTF8("quantumchamber:m6:profile-choice:v1") || int32BE(PolicyVersion) || GenerationSeedMaterial[32])` 第一個 unsigned 64-bit 大端整數，對當版 profile 數取餘數；固定排序是 random、same-seed、toggle-amplified。random seed 取 `SHA-256(UTF8("quantumchamber:m6:root-seed:v1") || GenerationSeedMaterial[32] || AllocationToken[32])` 第一個 signed 64-bit 大端整數；若恰等於 `SourceRootWorldSeed`，與 `0x9e3779b97f4a7c15` XOR 一次，必不同。same-seed／mutation 直接用 `SourceRootWorldSeed`。

source seed 與**來源**各 role settings 在候選生成時按 exact source family 凍結為 `SourceSettingsSnapshot`；canonical source 取存檔 generator seed 與已驗原版設定，舊 U 取其 legacy seed/settings，schema2 U 取其 catalog `TargetSettingsSnapshot`。profile provider 再以 source snapshot／策略產生**目標** `TargetSettingsSnapshot`：random→normal且保留來源GenerateStructures；same-seed→來源原樣；toggle→只翻轉 normal/amplified Overworld（目標 ProviderId 與 `OverworldPreset` 一併切換為對應內建 id）並保留GenerateStructures。兩份各存 provider id/version、參數與 output fingerprint，下一次開服各自重算，不能只保存同名 id。M6 內建僅支援能精確重建的 vanilla normal／amplified 設定；來源有其他自訂／未知 preset 時不產生 M6 `NEW` intent，不能暗改 normal；`SOURCE`／`EXISTING` 仍按舊候選規則。固定 1.21 registry gate 要證明 amplified preset、biome／structure 與獨立 ServerWorld 可用；否則先修本文並獨立 review。terrain noise、biome、structures 都用持久 root seed，不只換 `BiomeAccess.hashSeed`。

**native 1.21 實作前 gate**：逐一定位原版 `ServerWorld`／`DimensionOptions`／`ChunkGenerator`／`GeneratorOptions`／`BiomeAccess`／`RandomSequences`、`UnmodifiableLevelProperties`、End dragon／gateway persistent state 的 owner 與讀寫時序。先用只讀 decompile 或原始碼與最小 runtime probe 證明：各 role 的 generator／noise／structure／biome seed 取 catalog root；properties／randomSequences 不被另一 U 或 canonical 共用可變 instance（schema1 legacy 例外保留舊語意）；存檔與重啟可精確還原，且 registry 重新開啟不依賴已卸載的 transient 物件。原版 API 未經證實前不得稱「已支援」或僅憑 constructor 參數推論隔離。要改變 schema1 已生成世界的 generation options 時先停下修訂 spec。

固定 1.21 原版 JAR 的 bytecode 盤點已見：`ServerChunkLoadingManager` 由 `ServerWorld.getSeed()` 建 noise 與 structure placement calculator；`ServerWorld` constructor 又直接由全域 `SaveProperties` seed 建 structure locator／End dragon fight；`ServerWorld.getSeed()` 本身**每次呼叫**都讀 `server.getSaveProperties().getGeneratorOptions().getSeed()`，不只在建構期。故新 schema2 世界須有只作用於**exact server＋world key＋catalog descriptor**的長期 seed owner，讓建構期間及建構後每次 `getSeed()` 都回 frozen root，所有原生消費者一致；建構期的暫時 context 若使用，須核 server thread／worker 時序並在 `finally` 清理，且不能是唯一的 seed 修正。無匹配 descriptor 時完全走原版；不得暫時替換全域 save properties、generator registry 或 canonical `DimensionOptions`，更不得讓同時建構的 U 共用錯誤 context。實際 mixin 注入點／數量由 native gate 定位後寫入 plan，單世界原版與多世界並行 probe 必須驗 owner 不洩漏。schema1 舊 Overworld 仍使用舊路徑；若無法隔離所有 seed 消費者，切片②不得宣稱完成。

固定1.21另有可表達的非DimensionOptions控制：ChunkGenerating.generateStructures在worker流程直接讀全域GeneratorOptions.shouldGenerateStructures，ServerWorld constructor也把GeneratorOptions交StructureAccessor。Task11必讓exact schema2 NEW_M6及遷移後新Nether/End的相關constructor／持續worker消費使用catalog snapshot的frozen GenerateStructures；不能只把bool加入FP而仍借canonical global決定是否生成，也不能暫換全域GeneratorOptions/SaveProperties。未匹配M6 owner、canonical及未遷移schema1完全沿原版；MIGRATED_SCHEMA1舊Overworld維持legacy path，但依§3.2守實際控制相符。需具名驗true/false、重啟後切canonical global仍保持NEW_M6 frozen值、legacy不變、constructor/worker與source capture一致；native消費未隔離前切片②不得結案。

**Task11 原版結構定位 consumer 窄補（2026-11-06 使用者裁示）**：固定 1.21／Yarn build.9 的 `ServerWorld.locateStructure(TagKey,BlockPos,int,boolean)` 在進入 `ChunkGenerator` 之前另讀全域 `GeneratorOptions.shouldGenerateStructures()`，false 直接回 null。原版 `EnderEyeItem`、探索地圖 loot、村民售圖與海豚尋寶會走此入口；`/locate structure` 直接呼 `ChunkGenerator`，不能拿它的成功冒稱此入口已修。若 canonical global=false／新 U frozen=true，既定 constructor／worker 注入即使讓結構正確生成，原版眼睛／地圖仍被提早拒；反向 global=true／frozen=false 會執行本應立即拒絕的定位搜尋，既有 structure start 可能被回傳。使用者同意在 Task11 原計畫已有的 `ServerWorldUniverseSeedMixin` **新增恰一處** `ServerWorld.locateStructure` 內 `GeneratorOptions.shouldGenerateStructures()Z` 呼叫的包裝；只在已由 checked catalog 綁定 exact server＋world key＋descriptor＋live instance 的 schema2 `NEW_M6` 回其 frozen `GenerateStructures`，無匹配、canonical 與 schema1 原呼叫照走，不改全域 `SaveProperties` 或定位演算法。owner 必在 world 對外發布前建立且建構失敗 `finally` 清理；建構期暫時 context 不能作此長期查詢的唯一來源。原 Task15 的遷移與新增 role 待辦已取消，不改既有 Task11 owner 或舊 Overworld 相容保證。Task11 須以 global/frozen 兩方向、可定位的真結構正控制、原版眼睛或地圖至少一條整合 witness、canonical/schema1/雙 U／重啟反控制與具名 mutation 證明此唯一 callsite；`require=1` 的精確命中、compile／remapJar、真 native consumer 與完整回歸缺一不結片。不新增第三個 mixin class、驗證腳本或外部 JVM observer；其他 plan 外 production 注入點仍依 2026-11-05 裁示先請使用者決定。

**Task11 外部 mod 直接安裝 owner 的已知限制（2026-11-06）**：本模組正式 factory 只在 exact 建構 context 安裝 `WorldGenerationOwnerAccess` 的 `GenerationOwner`；原版與本模組正常路徑不會對 canonical world 安裝它。若**其他 mod** 在原本為 null 的 public setter 先造出並裝入同 server／world key／live instance 的偽 owner，現行後續 exact 檢查可能採到錯誤 frozen seed／結構旗標。Task11 不為此 mod-only 碰撞新增事前 setter 攔截或專用 runtime oracle；依使用者 2026-11-05「只有其他 mod 才觸發者先推主線」裁示，列 Task8-H H6 於 M6 收尾前 triage。這不表示此殘餘已受保護，也不放寬本模組／原版可達的 exact owner 與世界表並行驗收。

### 4.1 Task 13 same-seed 驗收與 U→U 比較取消

使用者原話：「U→U 全量比較移 Task21；T13 保留 canonical→U 真玩家鏈與雙平台 owner／設定測試（建議）」。`SAME_SEED_FRESH_V1` 的同 seed、同已驗設定、新 UUID／空 storage、來源玩家建築不存在與後續修改互不影響之產品契約仍依上表。2026-11-09 最新裁示只將 **Task13** 地形驗收分為下列嚴格 CARVERS 層與完整 final 診斷層；不修改 Task21 原文或任何舊 FAIL 判決。

- **Task 13**：保留 policy2 resolver／codec／正式入口的具名 RED、嚴格選擇／持久化、新 session 的 `FamilyCapabilityVersion=0` 與舊 policy1 不升版。Windows 既有 `m6-native` job 的 canonical normal 與 canonical amplified 兩條獨立真玩家鏈，各自從正式來源艙的 checked `MEASURED+SELECTED` 走 source→new→restart。相同 datapack／mod 配置下，seed、frozen source／target settings、provider id／version／Parameters／fingerprint、biome、非空正例 structure、來源建築不複製、雙向修改隔離、UUID／catalog／allocation 及重啟權威全部維持嚴格。兩世界另在離 FULL 影響範圍足夠遠的未生成區，原生只請求到 `CARVERS`，固定 1440 點的 **actual block states 逐格 exact**；NOISE／SURFACE／CARVERS 的 `blockStateWriteRadius` 必各為 0，實際依賴半徑、virgin halo 與最近 FULL 距離須有收據；不以 density、容差或 hash 集合替代。原 final 1440 點、完整 rows、全部差格及四階段收據保留作診斷，final block pairwise／跨重啟 hash 不再是 full 回歸關卡；新 final hash 標 `UNRESOLVED` 送審，不自動學習 baseline。Ubuntu `build` 與 Windows `windows-native` 的 `m6_universe_same_seed` GameTest 各自具名驗 normal／amplified 的 direct catalog fixture：來源與目標的 exact frozen seed、provider id／version、preset、Parameters、fingerprint，以及實際 noise／biome／structure owner 與 frozen 設定一致；此雙平台測試不在 Task 13 對 direct catalog U→U 斷言全量 terrain／biome 相同。拆分後須以 testmod-only 錯 frozen seed／owner 負控具名殺 `SAME_NATIVE_CONSUMER_EXACT` 並逐位元還原，確保 owner-only oracle 仍有效。正式入場的 policy2 凍結、舊 policy1 與錯配拒絕仍須由具名測試守住；原 U→U c7d/e1d FAIL 不充當 Task 13 的 RED。
U→U 全量比較隨 T21 取消，不排 M6 或 M7；c7d／e1d seagrass／water 的 raw MCA、階段收據及原 FAIL 全保留為歷史，不改判或冒用為 T13 RED。既有 direct catalog helper 不在本次刪改，但不再列未來 T21 驗收承諾。

### 4.2 Task 13 分層 CARVERS 驗收（2026-11-09 最新裁示；取代本節較早的候選停點）

順序固定為 A→B→C。一份設計與一次獨立窄審 Critical／Important 0 後直接實作，不另加核可流程。**A** 先只改既有 `M6ProfileProbePhases.java`／`M6UniverseRecoveryProbe.java`，沿既有 owned-phase 互動入口做 canonical normal／amplified 各十個 fresh root／獨立 JVM；seed `20260921`、原 pack／mod 配置、只請求遠區中心至 CARVERS，直接從 actual Chunk 取固定 1440 方塊與 biome。每 preset 的 CARVERS block hash 必單一；任一不單一立即保留全部原件並停止回報，不調 oracle、座標或 seed 求綠。背景 startup FULL 如實記錄，不稱純 vanilla。**B** 僅 A 的兩 preset 均成立才接既有 T13 正式兩條 native 鏈與具名負控。**C** 依 §8 分流：production 仍守原雙 focused／完整回歸／獨立 spec 與 quality／同 SHA CI；僅測試、腳本、文件則一席審查、針對性驗證與 CI，不新增 mutation 或本機 full。歷史 A／B／C 證據與舊 FAIL 均保留。T21 已取消排程；新增 canonical＋U、U-only、client 實驗未授權，既有正式 T13 native 鏈與 run-all 仍按原授權執行。

遠區中心採設計預設 chunk `(1024,1024)`，相對 x／z 為 0,3,6,9,12,15，y=-64..<256 step8，索引順序 x→z→y；不得因失敗換點。由固定依賴讀 `ChunkGenerationSteps.GENERATION` 的 CARVERS／FULL accumulated dependency 半徑與各 stage 寫入半徑，建立保守隔離 halo。生成前 halo 全部無 live／unloading holder 及 raw entry；唯讀掃 holder 所見最近 FULL 與 halo raw 證明共同排除 FULL 影響。請求只用 `getChunk(x,z,CARVERS,true)`；直接 `Chunk.getBlockState` 取樣，取樣前後皆須 exact CARVERS，halo 不得升至 FEATURES 或更後 stage。不新增 mixin、production hook、驗證腳本、observer 或 job。

`source` 只驗遠區 virgin，不生成該區；`new` 在兩世界各自 virgin 的遠區生成 actual CARVERS，保存完整不可覆寫 rows／hash／隔離證據，與 source→new receipt 的 root／nonce／startupNonce／seed／settings／world key／UUID／allocation token／SHA 鏈連結。原版不保證純 CARVERS ProtoChunk 一定落盤，不為保存升 FULL 或反射寫 chunk。`restart` 必先嚴格讀回 new immutable receipt，再唯讀核各側 raw／live halo；任何 >CARVERS、損毀／錯型／IO 或缺 receipt 均 FAIL。全 halo ABSENT 才稱 `FRESH_REGENERATED`，有 <=CARVERS 資料稱 `REOPENED_OR_COMPLETED_PARTIAL`；原生請求至 CARVERS 後，actual rows 各自對 new 同側及兩世界彼此逐格 exact。不冒稱所有 chunk 已持久保存，也不把重建稱原 virgin 生成證據。兩條權威鏈、biome／structure、建築／雙向 edit 與 storage ownership 的原嚴格重啟斷言不減。

final 診斷保存兩側原 1440 rows／hash、全部差格及 new→restart 全部差格；Java `Flow.verifyMaterialized` 與 PS1 `Assert-ProfileContinuity` 同步撤 final terrain equality，仍嚴格驗 CARVERS actual rows／receipt 鏈及原 biome／structure／權威。已見 hash 僅作診斷分類，已見舊 FAIL hash 不翻案；未知 final hash 標 `UNRESOLVED` 交 spec／quality 審查，不能成為自動 PASS／FAIL 的 membership 關卡，亦不能隱藏或自動結案。缺 rows／漏差格／假 hash 仍是證據完整性 FAIL。共用 `compareAndEdit` 由明確 T13 formal flow 選擇分層分支；既有 default exact helper 不在本次修改，取消 T21 排程不回寫其舊 FAIL。

必須以錯 seed、錯 preset 與 **在 actual CARVERS Chunk 直接人工改一個樣本 block 再重新取樣** 做具名 FAIL；後者不能只改 receipt 或靠故障旗標假殺。保原根、RED／GREEN、mutation-only patch／SHA 與逐位元 REVERT。原 dd57／35f5、T21 c7d／e1d 與全部舊 FAIL／NOT_KILLED／UNRESOLVED 原判保留，Task13 完整出口未達前不進 Task14。

**以下是本日較早 canonical-only final 控制的歷史紀錄；其「正式 PASS 標準不改／停等裁示／候選未生效」僅描述當時，不覆蓋上方最新 CARVERS 裁示。**

本輪先調查既有 native terrain FAIL 的驗收前提，不修改 generation production、T13／T21 正式 PASS 標準或舊 receipt。 使用者最新補充已授權 amplified-only 十次，比照 normal 的獨立條件；normal 批次已交付，本輪另做 amplified 批次。canonical＋U、U-only、client smoke 未授權，不執行；交付與獨立窄審完成後停下等裁示。停止 feature writer 內部時序追查；只對各批指定的 canonical normal／amplified 取樣、完全不物化動態 U，seed `20260921`，十個全新 root／獨立 JVM，以原固定1440點、preset、pack／mod配置與每側顯式請求序列做有界控制。一份設計與一次獨立窄審 C/I0 後實作；只改既有 testmod，不新增 production mixin、驗證腳本、外部 JVM observer 或 workflow job。

每根保存 HEAD／JVM／fresh root、實際world keys與無動態U、seed／設定／provider、3×3 virgin、status／請求序列、完整1440 block／biome及SHA，以及不額外載chunk的noise證據。noise raw density若於FULL快照後取樣，明示其範圍，不能稱為feature前方塊。無法成立的條件記SETUP／INCONCLUSIVE，不湊有效樣本。

- 多種hash只支持「目前模組運行環境內，單世界最終方塊存在變動」，再核是否重現歷史exact hash；不能直接推論純vanilla或全部舊FAIL原因。
- 十次全同只記未觀察到差異；不得直接判跨世界bug。後續只提出最小U-only／canonical+U控制候選，區分共享狀態、排程及fixture差異。normal結果不能替amplified判PASS；已授權的amplified-only另列獨立結果，亦不外推U→U。
- 原T13／T21完整1440逐格比較與全部舊FAIL原判保留。本輪**不採14格或1%容差**；較早同日自動採容差／全同真bug的分支由本段取代。

交付「證據→可支持結論→未排除假設→驗收契約候選」。若提出分層oracle，seed、frozen settings、biome、可證noise／density、structure authority仍嚴格一致，final FEATURES差異逐格保留分類，允許範圍必須有獨立控制證據；建築、修改隔離、UUID／catalog／restart不降低。「同seed／同preset的canonical-only基線集合」納入候選評估，T13與T21分開，含分類身份、反例、§4.1／本節影響及獨審計畫；候選不生效、不實作、不訂數字容差。任何正式acceptance改動須先交使用者裁定並經獨立spec review，不能由本次實驗直接改判。T13原完整回歸／兩席審查／同版CI未真正滿足前仍OPEN，不進T14。

## 5. 宇宙內重生（取代原 T20；2026-11-10 開工前裁示）

有效的床或重生錨不論位於哪個世界，一律優先依原版結果；不另做床合法性判斷，不改重生錨消耗。僅在 `ServerPlayerEntity.getRespawnTarget(boolean, TeleportTarget.PostDimensionTransition)` 內原版要走兩個 `getOverworld()` fallback，且死亡當下世界為 checked authority 對應的 exact live U:OVERWORLD，才將 fallback 目標窄調整為同一 U。

**落點照原版（2026-11-11 使用者裁示）**：fallback 只把目標世界換成同一 U，位置仍由原版 `TeleportTarget` 經 `ServerPlayerEntity.getWorldSpawnPos` 在該 U 計算，與在原版主世界重生的規則相同：
- 基準是 U 的 spawn。U 的 level properties 包住主世界 properties，所以座標與原版主世界 spawn 相同。
- 依 `spawnRadius` 在附近找地面；`spawnRadius` 為 0 時，原版在同一 block XZ 的 column 上下調整。
- 不寫自訂安全搜尋，不改全域 spawn，不造平台，不破壞方塊。

使用者在 AskUserQuestion「在宇宙裡死亡且沒有有效重生點時，落點要怎麼決定？」選「只換世界，位置照原版（建議）」，取代先前「同 block XZ、有界安全 Y」的策略。

若同一 U 的 checked catalog 權威或 exact live 條件不成立，保留原版 fallback，並記錄玩家、world key、原因與實際目標。

**已知行為：海上出生點（2026-11-11 使用者裁示）**
- U 沿用原版主世界的 spawn 座標，但 U 的地形不同，那裡可能是海。
- 若 spawnRadius 範圍內都找不到地面，原版會退回 spawn 座標，並在同一 column 往下落到碰撞面。水沒有碰撞，所以玩家會在海底重生，要自己游上來。
- 有效的床不受影響。
- Task R quality 審查以 javap 佐證：`SpawnLocating.findOverworldSpawn` 對水柱回傳 null；`ServerPlayerEntity.getWorldSpawnPos` 全部候選失敗時退回 basePos。
- 使用者在 AskUserQuestion「宇宙的出生點若在海上，沒床死亡會照原版落到海底重生…要怎麼處理？」選「接受，照原版落海底」，不另加注入點。

原版主世界與共用 Nether／End 死亡保持原版。End→主世界本來走 `getRespawnTarget(false)`，不能以 boolean 當死亡／End 出口的區別；必須以死亡當下 exact live U 主世界條件隔開 canonical End。終界出口照原版看床，床在 U 就回 U，沒床回原版主世界，不加 portal hook。

刪除原提案注入點 2（`PlayerManager.onPlayerConnect`），Task R 不改 `UniverseLifecycleService`。既有 `SERVER_STARTED` bootstrap 會物化全部 `EAGER_ENABLED` U，執行中不卸載（`UniverseLifecycleService.java:449–464`）；重啟後 U 仍被物化，由既有 restart 鏈證明；登入仍在同一 U、床點有效則依賴原版 `getWorld(U key)`，由人工清單驗證，不宣稱自動測試已涵蓋（2026-11-11 Task R 窄審修訂）。`DISABLED` U 不在登入時物化，避免繞過 M5 §8.3，維持既有行為。UUID／catalog／frozen seed／settings 與 playerdata IO 限制不變。

唯一 production 提案範圍為上述 method 內兩個 canonical fallback 呼叫的窄 target 調整；不做整個 method 的獨立床政策 wrapper。

固定 1.21 native gate 已於 2026-11-11 以 javap 核對，證據在 SDD `task-r-design-20261111.md`：
- descriptor `(ZLnet/minecraft/world/TeleportTarget$PostDimensionTransition;)Lnet/minecraft/world/TeleportTarget;`；
- `getOverworld()` 正好兩處，在 offset 99 與 116；
- 呼叫者只有 `PlayerManager.respawnPlayer` 與 `EndPortalBlock.createTeleportTarget`。

**實作（2026-11-11，code pin `1ead254`）**：依上述範圍實作，注入位置沒有變更。本機 RED、8 個 production mutation、雙 focused、完整回歸與 spec／quality 兩席審查都已通過，待本 commit 同 SHA CI；CI 三 job 全綠才算結案。細節見 M6 note 的 2026-11-11「Task R 實作」段。之後新增或改變注入位置仍要先回報，不擴 portal、gateway 或 family。

## 6. 共用原版維度與量子艙界線

平行宇宙只有主世界。Nether／End 共用 `minecraft:the_nether`／`minecraft:the_end`，不為每 U 配專屬維度，不開 capability 1 或 Nether／End 的同 role 量子艙；既有舊收據與 inert projection 的安全規則維持。地獄門回程一律落在原版主世界；終界出口照原版看床，床在 U 就回 U，沒床回原版主世界。人工清單分別驗 Nether 回程與 End 出口／credits，End 出口因 U 床返回 U 是預期行為，不加 portal hook 或重開 T19。

## 7. 並行 worldgen 防護

三個獨立根因調查：`StrongholdGenerator` 的 static `possiblePieces`／`activePieceType`／`totalWeight`、`ALL_PIECES.generatedCount`；`NetherFortressGenerator` 的兩組 static piece count；`EndCityGenerator` 的 static `Part.shipGenerated`。這是現有 source 靜態線索，後兩者與 Stronghold 均**未由 M6 runtime RED 證實**。先列原版所有寫入與讀取入口、初始化與遞迴呼叫鏈、server-thread `/place` 等同步呼叫者；各類用 barrier／stress 的兩世界 concurrent probe 及單世界對照取得定向 RED（錯誤計數、競態或輸出污染），原 root 永留。若未能重現，保留可證明的共享狀態不變量破壞與測試限制，不虛稱 crash 已重現。

防護以每次生成 invocation 私有狀態為首選；如需 ThreadLocal，必證 worker 任務／遞迴／同執行緒序列任務安全重設與 `finally` 清除；如需鎖，明列鎖階序、可重入與 server thread／worker 等待關係，禁止引入 `/place` 或 shutdown 死鎖。不得以 H5 chunk unload、H6 template cache、H8 mapping eager init 當作三類已修。各自 RED→窄修改→兩次同乾淨 HEAD GREEN，單世界原版 seed 的 structure layout／計數回歸與跨世界並行 smoke；必要時再做多世界 cross-JVM 重啟。

## 8. 驗證與交付門檻（B+C 為有效流程）

1. **資料安全不減**：世界移動前核 canonical／non-reparse／owned root、無 live Java owner、session.lock 已釋放；fresh world 可恢復 move 與逐檔 hash 還原照守，失敗 root／receipt 原封保存、修正用新 nonce。testmod 不進 release／sources，main-only 必獨立核 Fabric mod list、classpath／argfiles／DLI 及 class-load，GameTest 不冒 main-only。任何時間只一個 Gradle／gate。
2. **production 改動**：原有 RED、必要 mutation、同乾淨 code HEAD 雙 focused、完整 run-all、獨立 spec／quality C／I 清零、docs truth 與同 SHA CI 全保；不以切成文件 commit 規避程式關卡。
3. **僅測試／腳本／文件**：一席獨立審查，不做 mutation，不跑本機完整回歸；按變更需要做針對性驗證，CI 照常。純文件 commit 的 CI 若只因已知偶發失敗，不重開已結 task；CI 原 FAIL 仍保留，不寫成 PASS，也不據此把尚 OPEN 的 task 自動結案。
4. **已知偶發**：Windows 原子移動 AccessDenied；Windows default 的 AABB／走廊容量連坐在 testmod 處理完成前亦列入。同一 code pin 可保留原 root，以新 nonce 重跑一次，第二次就停下回報；文件 SHA 不重置既有次數，不以多次重跑湊綠。有其他失敗則不能用本項概括。
5. **設計與 oracle**：一份設計、一次獨立窄審，C／I 清零後就做；同一問題不另加多輪觀測。受原版非確定性影響的判定從一開始就分層，最終 FEATURES 方塊不作逐格相等關卡。T13 保留 §4.1／§4.2 嚴格 CARVERS／權威與完整 final 診斷；T14 在實作前定義比較層級，T21 已取消排程，舊比較結果只作歷史。既有反例與 FAIL 全保，不追加本輪 mutation。

**2026-11-10 測試／CI 窄修裁示**：Windows default offline／pin-cap採自身正式清理並維持既有各自唯一batch，提前整合到T14完整回歸；default166、全部原斷言／timeout及production容量不變，不宣稱JVM隔離或4096根因已證。pin-cap admission在原業務期限內等待無他live Space，admitted後overflow全窗口只許自身，含pending／provisional／retiring，不能用pin0或journalEmpty代替。offline同UUID真重連後正式返還／release／ack；pin-cap保有價物品、失敗只清自身，保primary與cleanup錯。精確設計在plan 8-H A段。此測試片依B+C一席、必要targeted與CI，不豁免T14 production完整出口。SavedSummary SelfTest改900秒（CI步驟15分鐘、本機既有inline wrapper），完整479反例與末baseline保留；原兩次300秒BUDGET_FAIL原件原判不動，效能WIP撤回不再優化。Task R新增native鏈前先估SelfTest，超900先回報，不自行提高預算。

**8-H triage**：8-core 已完成部分及歷史 8-H 證據保留。T13／T14／宇宙內重生完成後、M6 收尾前，按「只有主世界、共用原版 Nether／End、SHALLOW_V1」逐項提出 H1 完整閉包、H2 lifecycle、H3 三 bundle、H4 reload、H5 reserved journal slot、H6 foreign owner setter之當時狀態（Windows default AABB／走廊容量兩案自身清理已依上段另裁，保留結果與根因未證界線）、必要性及 waiver 候選，交使用者裁定。只實作裁示為必須者；waiver 不改原 FAIL，須列於人工清單及接手入口；是否排後續 milestone 由使用者裁定，不自動新增 M7 排程。


**Windows default AABB／走廊容量候選（待使用者裁示、未實作）**：既有只讀原件支持 global pin overflow 可連坐其他 space、offline case 完成後可留 pending；不證明後段 AABB 的當刻 4096 expanded coverage 已滿。四個 local 終局 NBT 僅見 offline SID，pin-cap SID 不在 Records，不能稱兩 SID 永久持有，也不能由最高 slot6 推定根因。候選 A 為 testmod 自身清理修正（來源可達的清理缺口候選，並非已證 AABB 根因）：`m2_native_offline` 保留原 cohort／KEEP_CURRENT／epoch0／保護等全部斷言後，以同 GameProfile／UUID 真重連，走正式返還、releaseComplete 與 ack 後才 complete；pin-cap 保留 256 合法／257 拒絕原斷言與原 timeout，只補自身全段失敗清理及 overflow 窗口的無其他 live space 檢核，不清他案。僅可用既有 `M4CandidateTestAccess.get(pages,"spaces")` 唯讀檢核，不能 mutate map／reset／dropSpace，且須覆蓋整個 overflow 窗口而非單次空集合。候選 B 為將原 offline／pin-cap case 隔離到專用 batch，保留每個 case／期待／timeout，default scope 仍完整執行它們並核 XML 集合，不能減案求綠。A 的預計 testmod 白名單為 `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`，若確需共用 helper 才另裁 `src/testmod/java/dev/quantumchamber/gametest/ConnectedGameTestPlayer.java`／`M4CandidateTestAccess.java`；B 除同一 case 檔的 batch 標註外，若需 runner／build／harness 接線，先列 exact 路徑與完整 scope 證明再請使用者裁示，不在本次授權。兩候選均不改 production 容量／行為、原案例與 timeout、old FAIL，不新增觀測或 observer；裁示及窄審後才可實作。T22 最終 full 必自然 `END ALL exit=0`，同 pin 最多重跑一次，第二次停報。

**縮範圍 T22**：M6 收尾仍須在最終程式 pin 完成本範圍完整回歸（run-all 自然 END ALL exit=0、必要 M6 原生鏈、M5／legacy、main-only／artifact isolation）、整分支審查、docs truth 與 CI。單人、多人各一輪並加一輪宇宙內死亡重生的人工 smoke 依 §1／§5 清單保存實際版本、世界、操作者與 receipt／log；未實玩不得填通過。合併 main、建立 tag、人工代操作分別另問使用者；正常 push 仍依持續授權，不建立 PR／LICENSE。此處 milestone 完整回歸不因逐次測試／文件免本機 full 而省略。

## 9. 實作前驗證與支援界線

1. 主控設計預設的三類等量預設與 `SAME_SEED_TOGGLE_AMPLIFIED_V1`，不是使用者指定的平衡規則；如原版 1.21 registry gate 不成立，須明列替代 mutation 再 review。
2. schema1 舊世界首次升級前若存檔 seed 曾被外部變更，舊 catalog 沒有 seed 副本，無法純靠它證明歷史 seed；須在 native／fixture gate 確認可採的證據與 fail-closed 條件。
3. 宇宙內重生只先驗 getRespawnTarget 的兩個 canonical fallback 與 exact live 死亡世界條件；床／錨及 End 出口沿原版。登入／重啟床點驗既有 EAGER_ENABLED bootstrap，不增加登入物化 hook 或修改 lifecycle。
4. v1.4 `SHALLOW_V1`（controller 設計預設）下，datapack／mod 改寫任何被引用 registry 元素的內容（dimension type、noise settings、density function、noise parameters、biome、structure set、tag、template、loot、component、world preset）不會改變 FP；三種內建 profile 的「已驗設定」與 same-seed／toggle 的地形相符保證都以兩端 datapack／mod 配置相同為前提，Task 22 人工清單須如實標示，Task 8-H H1 才補完整偵測。
