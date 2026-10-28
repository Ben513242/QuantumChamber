# M6 宇宙生成多樣性與完整維度家族：binding spec（v1.3）

日期：2026-10-24。基準：`0d05f889da6f8b82b97af756aaa50066b3d42854`。本文定義 M6 的新行為；未明列變更的 M4／M5 checked 權威與遊戲行為維持原約定。本文尚非實作或驗收結果。

狀態：v1.3已依設計層獨立review `3E61305D9BE716F29C4249F8EB59D366AB468298FA6574F983BA14C5033987D5`（C0/I0/M1，鍵數文字已修正）採用exact Single真absence／只讀cache與resolved template契約；使用者裁示snapshot期間限正常遊戲操作，外部改檔走reload／重啟。v1.2 RecipesUsed及plan r3/r4平台硬gate保留。本次是設計採用，不是A7、8A native gate或8B實作通過；正式進度以plan/note為準。

## 1. 目標、來源與交付順序

**使用者已確認**完整 M6，依序為：①測試腳本安全與生成競態；②random seed；③same-seed fresh／generation profile；④完整 Nether／End family、portal／gateway、respawn。每段單獨驗收；spec 與 plan 經獨立 review、Critical／Important 歸零後直接實作。`StrongholdGenerator`、`NetherFortressGenerator`、`EndCityGenerator` 的共享可變狀態各自調查與修正。M7 literal branch／snapshot 不在範圍。

**主控設計預設的具體預設**在 §4 標明；設計 §7.4 的 50／30／20 是示例，使用者尚未選定權重，也未指定 mutation 類型。M6 不改目前 `SOURCE`／`EXISTING`／`NEW` 的候選權重，不改選門後的候選身分；只讓新產生的 `NEW` 意圖可選 M6 profile。兩項獨立 harness Minor：`probe-common.ps1:17–24` 的 `run/` 祖先 canonical／reparse 守門，以及 `gates.ps1:80–102` 的 Move-Fresh「move 成功、建 fresh root 失敗」可復原且保留原錯誤；各自 RED／GREEN、獨立 commit，不能混進生成 production commit。

| 切片 | 成功畫面／必要證據 | 必須先有 |
| --- | --- | --- |
| ①安全與競態 | fresh root 失敗仍可 exact 還原；三類結構並行生成無共享狀態污染，原版單世界輸出不變 | native 1.21 路徑盤點、三類各自定向 RED |
| ②random | 兩個 `NEW` 可有不同 terrain／biome／structure seed，重啟與重訪不變；舊世界不變。原版 portal／死亡仍可能非正規回到 canonical，沿 M5 已知限制 | §3 schema／seed owner 與提交鏈 |
| ③same-seed／profile | 新 UUID、空白歷史、同 seed 地形；至少一種可量測的原版 profile mutation；registry 查詢能回報精確 id／version／參數。原版 portal／死亡非正規回程仍未修 | ②、原版 preset 與 worker 實證 |
| ④family | 每個 U 有獨立三 role；家族內 portal／gateway／死亡正確且不漏回 canonical；同 role 艙投影／跨越 | ③、三 role storage 與 routing gate |

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

`SettingsSnapshot` v1 的 exact keys 是 `SnapshotVersion=1`(int)、`ProviderId`(canonical namespaced string)、`ProviderVersion`(int)、`Parameters`(compound)、`OutputFingerprint`(32 bytes)。來源與目標是**兩份獨立 snapshot**，不得把 mutation 後參數當來源。內建 provider 的 `Parameters` 恰有 `OverworldPreset`(string，`normal|amplified`) 與 `GenerateStructures`(NBT byte，僅0或1，對應false/true)。缺欄、錯type或其他byte值拒絕，不默認true；Nether／End的原生preset設定固定，三role使用同一凍結的GenerateStructures值；受信任擴充須在註冊時宣告 bounded、strict、canonical 的參數 schema，拒絕額外鍵／型別或無法序列化的值。snapshot 的 canonical NBT 大小上限 512 bytes（含欄名與值），超限不得成為候選；provider id 長度及參數 bytes 亦受此限。`OutputFingerprint=SHA-256(UTF8("quantumchamber:m6:settings:v1") || canonical resolved output)`；resolved output 依 role 順序涵蓋固定 Minecraft 1.21 與受支援 adapter／runtime 契約版本、實際 `DimensionOptions`／chunk generator／biome source／noise settings／structure placements，及 §3.1.1 的完整生成設定資料閉包；只排除另存的 root seed。canonical encoding 依 §3.1.1 保留型別、語意次序及明確 byte order。native gate 必先證明從實際輸出可取得全部受支援資料閉包，且每個 preset／role 都各自通過；未滿足者不可建立可持久化 snapshot。開檔時用 snapshot 的 provider id/version/parameters 重建，重算 fingerprint 並 exact 比對；provider 缺失、參數未知、fingerprint 不符或設定無法建立時該 U fail closed，不用預設設定替代。

`CandidatePolicySnapshot` 的新形狀也屬**凍結權威**：schema5 的 `Context` 保留既有 `PolicyId`、`PolicyVersion`、`CandidateSchemaVersion`、`SourceWeight`、`DiscoveredWeight`、`NewWeight`、`DiscoveryWatermark`、`MaxCandidateEntries`、`EntropyFingerprint`、`SourceFamilyRef` exact 欄位，另加唯一 `FamilyCapabilityVersion`(int)。M6 context 固定 `PolicyId=quantumchamber:m6_profiles_v1`、`CandidateSchemaVersion=2`、`SourceWeight/DiscoveredWeight/NewWeight=5/20/75`、`MaxCandidateEntries=4096`；`PolicyVersion` 取 §4 的 1／2／3，`FamilyCapabilityVersion` 取 0／1。新 `NEW.Intent.PolicyVersion`／`FamilyCapabilityVersion` **必須恰等於該 Context**，profile selection 要可由 Context＋frozen material 重算並 exact 比對。`SOURCE`／`EXISTING` 沒有 intent，但它們的 family 能力同樣只從 Context 的 frozen `FamilyCapabilityVersion` 讀，不得由現行 server 功能旗標補值。舊 M4 context（`m4_v1`、version 1、candidate schema 1、無 family 欄、上限 16384）按舊 strict codec 讀，視為 capability 0；不得因新 envelope 改成 1。M6 context 一旦寫入，同一 session 不可改 policy 或 capability。schema5 journal／allocation2 的 writer 在 checked 寫出前，須以候選完整 NBT 經正式 strict reader 與相同 `NbtSizeTracker` 16 MiB 預算實際重讀、確認可解碼與 exact 比對；失敗即拒絕新增並保留既有權威。序列化 byte 長度不能代替 `NbtSizeTracker` 的解碼計量。4096 筆與單 snapshot 512 bytes 是設計上限，不代表已證明可讀；plan 的 codec task 須用最壞形狀 fixture 與實際讀回驗證 writer 可寫者皆可讀。

`PolicyVersion` 只允許 1／2／3；`ProfileVersion` 本輪只允許 1；`FamilyCapabilityVersion` 只允許 0／1。schema2 validator 必按上表 `Provenance × role` 分派 row shape 與 SeedPolicy；`GeneratorProfile` 舊 enum 只用於舊 row／遷移的 Overworld，新 `ProfileId` 不得以 enum ordinal 編碼。新 row 的 role 設定由啟動期凍結的 provider、持久 snapshot 與 fingerprint 解出，不得從當前 config 重算。所有新 allocation row 的 `UniverseId` 仍依 M5 token 推導公式；由 frozen intent 及 §4 policy 算出 `RootWorldSeed`／目標 fingerprint，必須在 ledger、catalog exact 比對相同。`SourceRootWorldSeed` 是 profile 演算輸入，不是來源 U 的持久資料新 owner。

`NEW` 的凍結 candidate intent 是 profile 選擇與 allocation token 的唯一來源；allocation ledger 是 token→Universe UUID／profile id／version 與目標 fingerprint 的配置權威；catalog 是已配置 Universe 的 root seed、**目標** settings snapshot、descriptor、role key、availability 的唯一持久權威。**來源** snapshot 只在 frozen intent，供重試與同 seed 衍生比對；現成 U 以其 catalog 目標 snapshot 作下一個候選的來源。runtime registry／`ServerWorld` 僅是 catalog 的投影，不得重抽。每個新 schema2 definition 恰有一個不可變 `RootWorldSeed`、`ProfileId`、`ProfileVersion`、目標 snapshot 與三個可推導的 role key；`Worlds` 的 exact shape 以上表為準。嚴格檢查型別、canonical namespace／路徑、唯一 role／key、三 role 完整性、profile 白名單與版本、seed 政策及 fingerprint；未知欄位或 profile fail closed。schema1 既有合法 bytes 照原解碼契約讀取；schema2 新格式從首版即 exact-key。catalog 外層 schema 的 envelope／record 版本須由同一 checked write／strict readback 升級，不靜默改寫舊 bytes；未知較新版本 fail closed，禁止自動降版。

`rootWorldSeed` 指向三 role 的世界生成 root；同 U 的三 role 相同，role 專屬原版 generator 設定決定細節。canonical 世界仍由 Minecraft 存檔權威持有。schema1 的 seed 仍取同一存檔原有 generator seed，不能重新定義成 M6 random；schema1 的既有 Overworld 原版 options／properties／randomSequences 行為維持。family 所需的 schema1→2 唯一路徑與首次遷移前外部改 seed 的不可偵測界線，明定於 §3.2。

**journal 格式界線**：M6 新意圖使用上表 schema5 的完整 exact 欄位，legacy intent 仍以舊 exact 欄位解碼；schema5 可同時容納兩者，不改原 `CandidateId`、`DoorKey`、SELECTED 與 MEASURED。首次 schema5 checked 寫出前，先確認 M5 ledger 初始化且沒有未確認寫入；寫出後不可降回 schema4。僅新 Context 凍結的 `FamilyCapabilityVersion=1` 可讓該收據選 Nether／End，`NEW` 另須 intent 欄位一致；不能用當前程式版本推斷舊 frozen entry 的能力。

### 3.1.1 完整設定資料閉包與執行相容性

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

Context publication必以固定Fabric／Minecraft的真實生命週期證據釘選：初次資料refresh／tag binding與資源就緒後、M6 journal/catalog/candidate第一次需解析snapshot之前；不能靠listener註冊順序猜。Reload開始先原子失效所有舊lease；成功且各資料來源確為同epoch後才發布新context；失敗維持unavailable，不能混合前後registry/tag/resource。Server stop立即失效並釋放本mod的owner引用，不關閉借用的server resource manager。Server-thread限制不能取代reload非同步視窗的epoch協定；首版不跨解析呼叫快取fingerprint，單次解析內共享節點可去重。Legacy schema1／M4路徑維持既有契約，不因mod-init提前解析尚不存在的M6 context而被改寫或無故阻斷。

Native gate須在每次launch前凍結並保存source/class/JAR與classpath hash，結束後重核；不以batch後快照補造launch-time證據。對normal/amplified的三role逐一保存完整／失敗判定，任一role未閉合就不可發布全family fingerprint或snapshot，不只檢最後的全域map。Native資料閉包、context時序可行性、後續正式provider/512-byte/32-byte/TDD/雙GREEN/full與獨立task review各有門檻；prototype診斷指紋不等於正式snapshot或production核准。

### 3.2 schema1 的唯一遷移路徑

切片②／③不遷移 schema1 record；legacy `NEW` 可照舊配置 schema1。切片④在第一次需要 legacy U 的 Nether／End 前，依 catalog schema1 原值逐欄建一筆 schema2 `MIGRATED_SCHEMA1`，`LegacySchemaOneDigest=SHA-256`（canonical 編碼之**遷移前** schema1 definition，含 UUID／ordinal／原 Overworld descriptor，不含可變 availability），`RootWorldSeed` 取當時存檔 generator seed，GenerateStructures取當時exact有效原生值並寫入目標snapshot參數。Schema1未保存的歷史控制值，首次遷移前若已遭外部更動則無法倒推；此限制不得冒稱已偵測或還原歷史。M5 factory 實際沿 canonical `DimensionOptions.OVERWORLD`，故先讀回並驗證該 save 的實際 Overworld 生成設定；若 exact 對應本 spec 已支援的原版 normal 或 amplified，就由相應 provider 產生 `TargetSettingsSnapshot` 並 checked 保存，遷移後原 Overworld 繼續走原 factory 與原設定。若不是這兩種可證實相符的設定，**只拒絕 family 遷移**，保留原 schema1 Overworld 可讀，不用猜的設定配置新 role。原 Overworld row 五欄／key／storage 與 factory 路徑原樣保留；新增 Nether／End row 依 §3.1 的四欄形狀、`EXPLICIT_ROOT_SEED_V1` 與同一 snapshot 推導。checked catalog 讀回後才允許新 role storage，不先改磁碟 chunk 或 live Overworld。舊 receipt／allocation row 查到遷移後 record 時，重建預期 schema1 definition，要求 digest、所有原欄位、root seed＝canonical save seed、新兩 role row 恰等於規定值、snapshot fingerprint 仍可驗；只有這種 schema2 等價型可代替舊 schema1 exact match。其它 schema2 或 digest 不符仍 `AUTHORITY_CORRUPT`。若已有 partial 新 role storage，先守 orphan／owner gate，不改寫 catalog 掩蓋它。

**已遷移後續使用守門（僅 `MIGRATED_SCHEMA1`）**：每次載入／物化該 U，及將其舊 Overworld 用作 `EXISTING`、M6 候選來源、passage／family route 等目的世界之前，先以既有 provider id／version／參數重算整份 frozen snapshot 的 output fingerprint；再獨立取目前 save／registry 的 canonical `DimensionOptions.OVERWORLD` 實際生成設定 closure，與 frozen snapshot **Overworld component** exact 比對（含 generator、biome source、noise、structure 相關定義）。若 exact-owned 的 U:OVERWORLD 已是 live world，同時讀其實際 chunk generator／相關設定 closure 與同一 frozen Overworld component 比對，並驗它仍是原 exact world instance。seed 仍須等於 frozen `RootWorldSeed`；canonical與exact live legacy Overworld的有效GenerateStructures亦須等於snapshot凍結值。任一不符、無法取得必要 closure 或 owner 無法確認時，該 U／本次候選 fail closed：不新建、不續產生 chunk、不傳送；開服 eager 物化時依既有啟動失敗契約停止，既有 live world 不重建、不重綁、不改寫，catalog 亦不以新 snapshot 覆蓋。此額外實際設定守門只比較 **Overworld** component；canonical Nether／End 的無關變動不作為「legacy Overworld 已變」的證據。整份 provider fingerprint 的獨立檢查仍守 catalog 目標 family 定義，其失敗應記為目標設定不符，不混稱 Overworld 實際設定變更。未遷移 schema1 維持 M5 行為，不套本守門。

plan 必具名驗證：在 frozen seed 不變時把 canonical Overworld preset normal→amplified、amplified→normal，已遷移 U 的載入／來源候選／相關使用均拒絕；實際設定相同時正常通過；已有 live U 不被重建、重綁或改寫；只變 canonical Nether／End 的無關設定不誤觸本 Overworld 守門；未遷移 schema1 不受新守門影響。native gate 須證明能取兩邊實際 closure，測試不得只比 provider 自身重算結果。

schema1 只有目前存檔 seed，沒有舊 seed 副本，因此**首次 M6 遷移前若外部已改存檔 seed，無法偵測，亦不支援恢復原地形**；本輪的可判定拒絕條件是：schema1 record 欄位／Overworld storage 身分不符、既有 profile 非 legacy、已有非預期 role storage，或已遷移 record 的 frozen seed 與當前 save seed 不符。首次遷移會以當時 save seed 當 legacy 原值；人工改 seed 後再遷移的 save 屬未支援輸入，不能宣稱自動 fail closed。遷移一旦 checked，禁止回到舊版或按新 seed 重寫。舊 build 遇到 schema2 envelope 必須拒絕，不降版。

### 3.3 提交與失敗窗口

**候選生成時**先以已 freeze provider 與 exact source family 解析 `SourceSettingsSnapshot`；依 frozen `PolicyVersion` 算 profile、目標參數及 `TargetSettingsSnapshot`，逐份驗 512-byte 上限、重算 fingerprint、嚴格 checked 寫入 schema5 intent；此時不配置 U 或生成 chunk。選門時先 checked 落盤 M4 `MEASURED+SELECTED` 的 exact receipt，再 checked `COLLAPSING` intent，然後 checked allocation ledger schema2 entry（先做必要的 v1→v2 envelope upgrade），最後 checked catalog schema2 的 seed／profile／**目標 snapshot**／三 role identity（先做必要的 v1→v2 envelope upgrade）；每一步 strict 讀回，比對 intent→ledger fingerprint→catalog snapshot。catalog 確認前不建立任何新 role storage 或 `ServerWorld`。同 token 重試只可回同 UUID、seed、profile 與 target fingerprint；source snapshot 必仍可由 provider 重算，不能以重啟當下玩家世界或 runtime RNG 取代。任一 authority 不一致或 provider 缺失／內容變動，標成 authority corrupt／SEALED，保留原檔，不接受 orphan storage 冒充正確紀錄。

在 intent 前 crash：只看已確認的 M4 authority；intent 後、allocation 前：同 token 續配；allocation 後、catalog 前：同 UUID、seed 與 profile 補 catalog，先檢查 orphan storage，存在即 fail closed；catalog atomic move 完成但讀回失敗：current 保留未確認，停止物化，重啟或下次寫入先 strict 收斂，不能另配；catalog 已確認但 role storage／世界建立中 crash：依 catalog 同 key 續作，不刪玩家世界，對 partial storage 做 ownership／格式檢查，無法證明安全即停服或 `DISABLED` 並保留證據；profile 參數或某 role 損壞：不可退回 vanilla 或 canonical，也不可重配 UUID，受影響 U 不可進入。`EXISTING` 只接受已確認且完整、相容的 catalog 定義。每個轉移的 flush、force、atomic move、strict readback、exact compare 與 roll-forward／restore 分類沿用 M5 §6；不要以 memory current 代替 flushed 權威。

**角色物化政策**：schema2 三 role identity 在 catalog checked 時一次配置；ServerWorld／chunks 依實際進入、portal、gateway、respawn 需求 lazy 物化。schema1 既有 Overworld 的 M3 eager 開服契約維持；已配置 schema2 若沿用 eager enabled 啟動，僅 eager 建立現有 Overworld，Nether／End lazy。開服前驗 catalog 完整、所有已存在 role storage 與 ownership；任何必要 role 物化失敗不得 fallback 到 canonical。新 role 在首次寫入前先確認目錄不存在或屬於 exact descriptor，建立時留下可恢復狀態，重啟可 idempotent 繼續；不刪 partial 世界當 rollback。

切片②／③即使 schema2 catalog 已保留三個 role identity，也**禁止物化**新 U 的 Nether／End role，且只驗 seed／profile；玩家從新 U Overworld 使用原版 portal 或死亡時，仍可能照 M5 已知限制非正規回到 canonical。這個中間階段不宣稱家族 routing 或重生完成，也不為它提前改 portal／死亡玩法。切片④的 routing／respawn、role owner 與 no-leak 驗收全過後，才開 `FamilyCapabilityVersion=1` 的新候選與 Nether／End 物化；既有 capability 0 的 Overworld U 仍可依已驗 catalog family descriptor 開角色供原版 portal／respawn 使用，但不升級其舊量子艙收據權限。

## 4. 生成策略與選擇（主控設計預設）

可行作法：A 從凍結的 `generationSeedMaterial` 決定 seed／profile，窄且可重現；B 另設 profile 抽選 ledger，較易動態調權但增加跨檔交易；C 配置時用 runtime RNG，重試會漂移。選 A。profile registry/API 最低契約是 `register(ProfileProvider{id, version, parameterSchema, adapterCapabilities, resolveGenerationSettings})`、`freeze()`、`createSnapshot(id,version,parameters,lease)` 與 `resolve(snapshot,lease)`；canonical output／fingerprint由受審 canonicalizer計算，provider只回設定資料：本 mod 與受信任 Java 擴充只能在**server 讀 M6 catalog／candidate 前**註冊；相同 id＋version 重複即拒絕啟動，freeze 後禁止更改。provider deterministic、無世界副作用，回傳可依 §3.1 canonical encoding 取 fingerprint 的完整三 role output；存檔所指 provider 缺失或 fingerprint 不符則 U fail closed。API 可查詢及嚴格解析已註冊 provider；M6 候選 policy **只選下表三種內建策略**，擴充 provider 不會被偷偷納入權重，需未來經 review 的新 policy 才能配置它的 U。此 API 能在啟動期實際註冊、驗參數與解析 snapshot，不是只包 enum；不建立通用腳本／插件執行器。

| 新 `NEW` profile（本版採用） | root seed／差異 | 驗收 |
| --- | --- | --- |
| `RANDOM_VANILLA_V1` | 按下文固定雜湊取不同於來源的 root seed；三 role 使用已驗的 `quantumchamber:vanilla_normal_v1` 設定，GenerateStructures保留來源值 | 同一意圖重啟完全相同；和來源 seed 不同，生成有可觀察差異 |
| `SAME_SEED_FRESH_V1` | 精確複製 source root seed **及來源各 role 的已驗 generation settings snapshot**；新 UUID／空 storage，不複製方塊、實體或玩家歷史 | 同 seed 同設定的原始 terrain／biome／structure 相符，來源玩家建築不存在；日後修改互不影響 |
| `SAME_SEED_TOGGLE_AMPLIFIED_V1` | 同 source root seed；來源 Overworld 為 normal 就改 amplified、為 amplified 就改 normal；Nether／End 設定照來源 snapshot | 相同 seed，Overworld 有可重現地形差異；三 role 重啟不變 |

**分階段 policy snapshot（主控設計預設）**：切片②新 intent 固定 `PolicyVersion=1`，只選 random；切片③前半新 intent 用 `PolicyVersion=2`，從 random／same-seed 各一半選；切片③ mutation 驗收後的新 intent 才用 `PolicyVersion=3`，三類等量。切片②／③的 `FamilyCapabilityVersion=0`；僅切片④ family／routing／respawn 驗收後新產生的 intent 固定為 1，舊 intent 不升版。每一階段只凍結當時已驗的 profile；既有 intent 永不升 policy 或重抽。三類等量是為了讓玩家在少量新宇宙中能實際看見三種結果，並簡化第一輪驗證，不冒稱最終平衡。choice 取 `SHA-256(UTF8("quantumchamber:m6:profile-choice:v1") || int32BE(PolicyVersion) || GenerationSeedMaterial[32])` 第一個 unsigned 64-bit 大端整數，對當版 profile 數取餘數；固定排序是 random、same-seed、toggle-amplified。random seed 取 `SHA-256(UTF8("quantumchamber:m6:root-seed:v1") || GenerationSeedMaterial[32] || AllocationToken[32])` 第一個 signed 64-bit 大端整數；若恰等於 `SourceRootWorldSeed`，與 `0x9e3779b97f4a7c15` XOR 一次，必不同。same-seed／mutation 直接用 `SourceRootWorldSeed`。

source seed 與**來源**各 role settings 在候選生成時按 exact source family 凍結為 `SourceSettingsSnapshot`；canonical source 取存檔 generator seed 與已驗原版設定，舊 U 取其 legacy seed/settings，schema2 U 取其 catalog `TargetSettingsSnapshot`。profile provider 再以 source snapshot／策略產生**目標** `TargetSettingsSnapshot`：random→normal且保留來源GenerateStructures；same-seed→來源原樣；toggle→只翻轉 normal/amplified Overworld並保留GenerateStructures。兩份各存 provider id/version、參數與 output fingerprint，下一次開服各自重算，不能只保存同名 id。M6 內建僅支援能精確重建的 vanilla normal／amplified 設定；來源有其他自訂／未知 preset 時不產生 M6 `NEW` intent，不能暗改 normal；`SOURCE`／`EXISTING` 仍按舊候選規則。固定 1.21 registry gate 要證明 amplified preset、biome／structure 與獨立 ServerWorld 可用；否則先修本文並獨立 review。terrain noise、biome、structures 都用持久 root seed，不只換 `BiomeAccess.hashSeed`。

**native 1.21 實作前 gate**：逐一定位原版 `ServerWorld`／`DimensionOptions`／`ChunkGenerator`／`GeneratorOptions`／`BiomeAccess`／`RandomSequences`、`UnmodifiableLevelProperties`、End dragon／gateway persistent state 的 owner 與讀寫時序。先用只讀 decompile 或原始碼與最小 runtime probe 證明：各 role 的 generator／noise／structure／biome seed 取 catalog root；properties／randomSequences 不被另一 U 或 canonical 共用可變 instance（schema1 legacy 例外保留舊語意）；存檔與重啟可精確還原，且 registry 重新開啟不依賴已卸載的 transient 物件。原版 API 未經證實前不得稱「已支援」或僅憑 constructor 參數推論隔離。要改變 schema1 已生成世界的 generation options 時先停下修訂 spec。

固定 1.21 原版 JAR 的 bytecode 盤點已見：`ServerChunkLoadingManager` 由 `ServerWorld.getSeed()` 建 noise 與 structure placement calculator；`ServerWorld` constructor 又直接由全域 `SaveProperties` seed 建 structure locator／End dragon fight；`ServerWorld.getSeed()` 本身**每次呼叫**都讀 `server.getSaveProperties().getGeneratorOptions().getSeed()`，不只在建構期。故新 schema2 世界須有只作用於**exact server＋world key＋catalog descriptor**的長期 seed owner，讓建構期間及建構後每次 `getSeed()` 都回 frozen root，所有原生消費者一致；建構期的暫時 context 若使用，須核 server thread／worker 時序並在 `finally` 清理，且不能是唯一的 seed 修正。無匹配 descriptor 時完全走原版；不得暫時替換全域 save properties、generator registry 或 canonical `DimensionOptions`，更不得讓同時建構的 U 共用錯誤 context。實際 mixin 注入點／數量由 native gate 定位後寫入 plan，單世界原版與多世界並行 probe 必須驗 owner 不洩漏。schema1 舊 Overworld 仍使用舊路徑；若無法隔離所有 seed 消費者，切片②不得宣稱完成。

固定1.21另有可表達的非DimensionOptions控制：ChunkGenerating.generateStructures在worker流程直接讀全域GeneratorOptions.shouldGenerateStructures，ServerWorld constructor也把GeneratorOptions交StructureAccessor。Task11必讓exact schema2 NEW_M6及遷移後新Nether/End的相關constructor／持續worker消費使用catalog snapshot的frozen GenerateStructures；不能只把bool加入FP而仍借canonical global決定是否生成，也不能暫換全域GeneratorOptions/SaveProperties。未匹配M6 owner、canonical及未遷移schema1完全沿原版；MIGRATED_SCHEMA1舊Overworld維持legacy path，但依§3.2守實際控制相符。需具名驗true/false、重啟後切canonical global仍保持NEW_M6 frozen值、legacy不變、constructor/worker與source capture一致；native消費未隔離前切片②不得結案。

## 5. 完整 family 與 routing

身份 `(Universe UUID, DimensionRole)`，role 恰為 `OVERWORLD|NETHER|END`；keys 為 `quantumchamber:universe/<uuid>/overworld|the_nether|the_end`，各有獨立 region／entities／POI storage、持久化、ticket 與 lifecycle。End dragon fight、gateway、結束後狀態與 End portal 資料由同 U:END 所有；不得共用 canonical End 或另一 U 的持久 state。原版 1.21 `ServerWorld.<init>` 會讀全域 `SaveProperties.getDragonFight()`，`saveLevel()` 會寫回全域 `setDragonFight()`；custom End 必須把兩端導向同 U 的獨立 persistent owner，canonical End 無 context 時仍原版。原版傳入非 null `RandomSequencesState` 會直接共用；M6 新 U 各 role 應交由自己的 persistent state manager 建立／存回，禁止傳 canonical instance；schema1 舊 Overworld 行為另守相容。世界共用 global player inventory 等既有玩家資料政策照舊，但位置／目前 U 身分與 respawn 綁定精確 role。runtime index 必須雙向驗 exact key→U/role→同一活 world instance，unknown／stale key fail closed。

`DimensionFamilyRoutingService` 攔截從 U 家族出發的原版 Nether portal、End portal、End gateway／exit；進入 Nether 與回程維持**同 U**且只有家族內 portal 使用 8:1；量子艙跨 U 維持同 XYZ，不受 scaling。End 入口取 U:END，exit／通關 credits 後回 U:OVERWORLD 的合規位置，End gateway 只在 U:END 中定位／生成，dragon 終局狀態持久保留。對 canonical 出發仍走原版 canonical family。對 U 出發任何 path 找不到或準備失敗，一律拒絕並保留來源位置與狀態，不能退回 `minecraft:overworld|the_nether|the_end`；原版可能經非 portal 的傳送入口也要盤點並以 exact world owner 守門。跨 role／跨世界的玩家與非玩家 entity 都要測；原版允許的非玩家 portal 行為仍在同 U，量子膜仍只送 frozen cohort 的非騎乘玩家，不把非玩家帶進 passage。

固定 1.21 原版 bytecode 初盤顯示 `NetherPortalBlock.createTeleportTarget`／`EndPortalBlock.createTeleportTarget` 直接比較 singleton `World.NETHER/END` 並取 canonical key，`ServerPlayerEntity.getRespawnTarget` 無有效床／錨時取 `server.getOverworld()`；routing／respawn 必在這些實際 sink 前攔住。End gateway、dragon／credits、非玩家 entry point 尚需 native gate 定位，不能因只修上述三個方法便宣稱 family 完整。

`RespawnRoutingService` 依玩家**死亡時所屬 U**及其持久位置／角色紀錄查 bed 或 anchor：有效 bed／anchor 依原版 role 規則在同一 U 的對應 role 重生；失效、耗盡或不存在時退到 `U:OVERWORLD` 安全 spawn。canonical 玩家照原版；若 U authority 未確認、role 不可物化或玩家 U 身分損壞，保持 fail closed／待人工復原，不默默放到 canonical spawn。跨 U 通道的 `Crossed` checked 後沿用 M5 立即呼叫 playerdata 存檔，但原版吞 IOException 的限制仍在：重啟時實際玩家位置以最後一次成功保存的 playerdata 為準，不能憑 `Crossed` 聲稱保證落在目的 U。死亡、斷線、重連、停服重啟、床被拆、錨耗盡與玩家處於 Nether／End 的組合都要驗。

## 6. 量子艙相容與版本界線

新建立的、具 M6 profile／family 的 session，可在**既有 vanilla Overworld／Nether／End Origin**選 `SOURCE`／`NEW`／`EXISTING`；候選與來源必同 `DimensionRole`，EXISTING 要具相同 role 且 gameplay eligible。目的地只在同 role 的同 XYZ 投影並走 M5 PREPARE／PASSAGE 原有 checked、碰撞、封存與關門語意；投影仍 inert，不加入 Origin registry，不因家族啟動新艙。其他 role 不會因這次艙自動投影。**動態 U 內新建並啟動真正 Origin（Catalog Origin）仍延後**；本輪只讓 vanilla 三 role Origin 使用 M6 family，不打開動態世界 activation／renderer／Origin registry 的額外範圍。`SOURCE` 仍原世界一般開門；沒有正規回程；portal／死亡是另一套原版入口，不改通道單向性。

M5 的 Nether／End 選門拒絕與 M4 舊 `MEASURED` 的 NEW／EXISTING 安全取消是當時 binding 行為，不能因 M6 啟用 family 就復活已取消的 receipt。**版本門檻**：僅 schema5 Context `FamilyCapabilityVersion=1`、在 family 驗收後產生並凍結的收據可走 Nether／End；`NEW` 還要 intent capability 一致，`SOURCE`／`EXISTING` 由共用 Context 判定。所有舊 schema3／4 凍結或 M5 已取消收據仍走原 M5 recovery／取消規則。legacy Overworld receipt 亦沿既有 profile 與 destination，不變 random。不得以伺服器 build 版本或當下 role 猜測。`CollapseAuthorities` 的 catalog exact 比對須依**意圖版本**分派：legacy schema1 使用原模板；若該 record 曾 checked 遷移至 schema2，須保存及驗證原 schema1 definition 的 digest 與 Overworld descriptor／key／seed 語意完全相同，允許的差異僅為已審查的 family 附加欄位，然後才接受舊意圖；新意圖比較 schema2 frozen seed／profile／roles。不放寬成「UUID 相同就接受」。投影 ledger 與 passage 恢復仍以 exact U／role/world key 驗證，跨 JVM 證明同 role 舊、新兩種路徑。

## 7. 並行 worldgen 防護

三個獨立根因調查：`StrongholdGenerator` 的 static `possiblePieces`／`activePieceType`／`totalWeight`、`ALL_PIECES.generatedCount`；`NetherFortressGenerator` 的兩組 static piece count；`EndCityGenerator` 的 static `Part.shipGenerated`。這是現有 source 靜態線索，後兩者與 Stronghold 均**未由 M6 runtime RED 證實**。先列原版所有寫入與讀取入口、初始化與遞迴呼叫鏈、server-thread `/place` 等同步呼叫者；各類用 barrier／stress 的兩世界 concurrent probe 及單世界對照取得定向 RED（錯誤計數、競態或輸出污染），原 root 永留。若未能重現，保留可證明的共享狀態不變量破壞與測試限制，不虛稱 crash 已重現。

防護以每次生成 invocation 私有狀態為首選；如需 ThreadLocal，必證 worker 任務／遞迴／同執行緒序列任務安全重設與 `finally` 清除；如需鎖，明列鎖階序、可重入與 server thread／worker 等待關係，禁止引入 `/place` 或 shutdown 死鎖。不得以 H5 chunk unload、H6 template cache、H8 mapping eager init 當作三類已修。各自 RED→窄修改→兩次同乾淨 HEAD GREEN，單世界原版 seed 的 structure layout／計數回歸與跨世界並行 smoke；必要時再做多世界 cross-JVM 重啟。

## 8. 驗證與交付門檻

每個 task／fix round 有有界派工、RED、兩次同乾淨 code HEAD 的 focused GREEN、完整回歸與 commit range；每個風險批次 full，fix round 必有 full。JUnit 驗 codec／seed determinism／exact 身分／race；GameTest 驗 role storage、投影／passage、portal、respawn、dragon／gateway 可觀察狀態；cross-JVM 驗 intent→allocation→catalog→partial role→重啟與損壞／rollback 窗口；人工 smoke 分四段實際遊玩，與自動證據分開。失敗 root／receipt 原封保留，修正另用 nonce/root，不覆蓋判決。先更新 `scripts/verification/` 的正式 gate 與 oracle，單一時間只允許一個 Gradle／gate。不能以 GameTest runtime 冒充 main-only；release／sources JAR 不得含 testmod、gametest、fault mixin，production `ModPresenceProbe` 例外；main-only 核 Fabric mod list、classpath／argfiles／DLI 與 class-load。

既有 M5 本機基準是 code `6b116e6` 的 `run-all` `END ALL exit=0`，CI 是 `f99ce66` 雙平台成功，兩者不是 M6 證據。新的 full 依 `pwsh -NoProfile -File scripts/verification/run-all.ps1`，比對 SUMMARY、精確 testcase class/name、release／sources、WARN／ERROR、pin、worldgen 例外與 CI；純 docs 只查 diff／`git diff --check`，不重跑舊 code 基準。每段獨立 spec 與 quality review，Critical／Important 關閉才前進；最後 automated gates → required manual smoke（tracked note）→ whole-branch review → docs truth → feature push／雙平台 CI。正常 push 依既有持續授權；M6 合併 main、建立 tag、退役 worktree 另守各自門檻，不建立 PR／LICENSE，也不在 main 實作。

## 9. 實作前驗證與支援界線

1. 主控設計預設的三類等量預設與 `SAME_SEED_TOGGLE_AMPLIFIED_V1`，不是使用者指定的平衡規則；如原版 1.21 registry gate 不成立，須明列替代 mutation 再 review。
2. schema1 舊世界首次升級前若存檔 seed 曾被外部變更，舊 catalog 沒有 seed 副本，無法純靠它證明歷史 seed；須在 native／fixture gate 確認可採的證據與 fail-closed 條件。
3. Bed／anchor、dragon／gateway 的原版 owner 與資料格式、非玩家 portal 路徑先以固定版本 source／runtime 核實，不能由高層設計推稱已有 API。
