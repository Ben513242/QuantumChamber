# M6 宇宙生成多樣性與完整維度家族：binding spec（待獨立審查）

日期：2026-10-24。基準：`0d05f889da6f8b82b97af756aaa50066b3d42854`。本文定義 M6 的新行為；未明列變更的 M4／M5 checked 權威與遊戲行為維持原約定。本文尚非實作或驗收結果。

## 1. 目標、來源與交付順序

**使用者已確認**完整 M6，依序為：①測試腳本安全與生成競態；②random seed；③same-seed fresh／generation profile；④完整 Nether／End family、portal／gateway、respawn。每段單獨驗收；spec 與 plan 經獨立 review、Critical／Important 歸零後直接實作。`StrongholdGenerator`、`NetherFortressGenerator`、`EndCityGenerator` 的共享可變狀態各自調查與修正。M7 literal branch／snapshot 不在範圍。

**主控提案的具體預設**在 §4 標明；設計 §7.4 的 50／30／20 是示例，使用者尚未選定權重，也未指定 mutation 類型。M6 不改目前 `SOURCE`／`EXISTING`／`NEW` 的候選權重，不改選門後的候選身分；只讓新產生的 `NEW` 意圖可選 M6 profile。兩項獨立 harness Minor：`probe-common.ps1:17–24` 的 `run/` 祖先 canonical／reparse 守門，以及 `gates.ps1:80–102` 的 Move-Fresh「move 成功、建 fresh root 失敗」可復原且保留原錯誤；各自 RED／GREEN、獨立 commit，不能混進生成 production commit。

| 切片 | 成功畫面／必要證據 | 必須先有 |
| --- | --- | --- |
| ①安全與競態 | fresh root 失敗仍可 exact 還原；三類結構並行生成無共享狀態污染，原版單世界輸出不變 | native 1.21 路徑盤點、三類各自定向 RED |
| ②random | 兩個 `NEW` 可有不同 terrain／biome／structure seed，重啟與重訪不變；舊世界不變 | §3 schema／seed owner 與提交鏈 |
| ③same-seed／profile | 新 UUID、空白歷史、同 seed 地形；至少一種可量測的原版 profile mutation；registry 查詢能回報精確 id／version／參數 | ②、原版 preset 與 worker 實證 |
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
| journal envelope | schema5；既有 schema4 欄位與狀態不變。新 `NEW.Intent` 恰含 `AllocationToken`(32 bytes)、`GenerationSeedMaterial`(32 bytes)、`PolicyVersion`(int)、`ProfileId`(string)、`ProfileVersion`(int)、`SourceRootWorldSeed`(long)、`SourceGenerationSettingsId`(string)、`FamilyCapabilityVersion`(int)。舊 intent 仍是原四欄。 | schema3／4 原樣讀；schema5 允許 legacy entry 與新 entry 混合，依 `PolicyVersion` 有無嚴格區分兩種 exact 欄位形狀。新 intent 只在 schema5 寫出。 |
| allocation ledger | envelope schema2；`Entries` 的新 row 恰含 `EntryVersion=2`、`AllocationToken`、`UniverseId`、`PolicyVersion`、`ProfileId`、`ProfileVersion`、`RootWorldSeed`、`GenerationSettingsId`。 | 既有 schema1 ledger checked 轉成 schema2 後才 append M6 row；舊 row 保持原四欄且無 `EntryVersion`，以原 exact decoder 讀。轉換只改 envelope，不能改舊 row 值／次序。 |
| catalog | envelope schema2；新 `UniverseRecord` schema2 保留 `UniverseUuid`／`AllocationOrdinal`／`DesiredAvailability`，新增 `RootWorldSeed`(long)、`ProfileId`、`ProfileVersion`、`GenerationSettingsId`、`Provenance`(enum)，`Worlds` 恰三筆含 `WorldKey`／`Role`／`GeneratorProfile`／`SeedPolicy`／`StoragePolicyVersion`／`RoleGenerationSettingsId`。`Provenance=MIGRATED_SCHEMA1` 時另有必填 `LegacySchemaOneDigest`(32 bytes)，`NEW_M6` 時此欄禁止。 | 外 envelope schema1 可含舊 record；首次需寫 schema2 record 前 checked 升 envelope schema2，舊 record 保持 schema1。後續僅按 §3.3 的 migration 將個別舊 record 向前轉換。 |

`CandidatePolicySnapshot` 的新形狀也屬**凍結權威**：schema5 的 `Context` 保留既有 `PolicyId`、`PolicyVersion`、`CandidateSchemaVersion`、`SourceWeight`、`DiscoveredWeight`、`NewWeight`、`DiscoveryWatermark`、`MaxCandidateEntries`、`EntropyFingerprint`、`SourceFamilyRef` exact 欄位，另加唯一 `FamilyCapabilityVersion`(int)。M6 context 固定 `PolicyId=quantumchamber:m6_profiles_v1`、`CandidateSchemaVersion=2`、`SourceWeight/DiscoveredWeight/NewWeight=5/20/75`、`MaxCandidateEntries=16384`；`PolicyVersion` 取 §4 的 1／2／3，`FamilyCapabilityVersion` 取 0／1。新 `NEW.Intent.PolicyVersion`／`FamilyCapabilityVersion` **必須恰等於該 Context**，profile selection 要可由 Context＋frozen material 重算並 exact 比對。`SOURCE`／`EXISTING` 沒有 intent，但它們的 family 能力同樣只從 Context 的 frozen `FamilyCapabilityVersion` 讀，不得由現行 server 功能旗標補值。舊 M4 context（`m4_v1`、version 1、candidate schema 1、無 family 欄）按舊 strict codec 讀，視為 capability 0；不得因新 envelope 改成 1。M6 context 一旦寫入，同一 session 不可改 policy 或 capability。

`PolicyVersion` 只允許 1／2／3；`ProfileVersion` 本輪只允許 1；`FamilyCapabilityVersion` 只允許 0／1。`GeneratorProfile` 舊 enum 只用於舊 row／legacy descriptor，新 `ProfileId` 不得以 enum ordinal 編碼。新 schema2 的 `SeedPolicy=EXPLICIT_ROOT_SEED_V1`；`GenerationSettingsId` 與各 role 設定須由啟動期凍結的 profile provider 白名單解出，不能從當前 config 重算。所有新 row 的 `UniverseId` 仍依 M5 token 推導公式；由 frozen intent 及 §4 policy 算出 `RootWorldSeed`／`GenerationSettingsId`，必須在 ledger、catalog exact 比對相同。`SourceRootWorldSeed` 是 profile 演算輸入，不是來源 U 的持久資料新 owner。

`NEW` 的凍結 candidate intent 是 profile 選擇與 allocation token 的唯一來源；allocation ledger 是 token→Universe UUID／profile id／version 的唯一配置權威；catalog 是已配置 Universe 的 root seed、profile 參數、descriptor、role key、availability 的唯一持久權威。runtime registry／`ServerWorld` 僅是 catalog 的投影，不得重抽。每個新 schema2 definition 恰有一個不可變 `RootWorldSeed`（signed 64-bit）、`ProfileId`、`ProfileVersion`、明列的 `GenerationSettingsId`／參數版本與三個可推導的 role key；`Worlds` 中每個 descriptor 恰有 role、key、generator 設定 id、seed policy、storage policy。寫入整數、enum、UUID、key 時 strict 型別、exact keys、canonical namespace／路徑、唯一 role／key、三 role 完整性、已知 profile 白名單與版本、seed 政策一致性；未知欄位或未知 profile fail closed，不默認回 legacy。schema1 既有合法 bytes 照原解碼契約讀取（不以新 exact-key 規則追溯拒絕）；schema2 新格式一開始即 exact-key。catalog 外層 schema 的 envelope／record 版本須由同一 checked write／strict readback 升級，不以新解碼器吞舊資料後靜默改寫。未知較新版本 fail closed，禁止自動降版。

`rootWorldSeed` 指向三 role 的世界生成 root；同 U 的三 role 相同，role 專屬原版 generator 設定決定細節。canonical 世界仍由 Minecraft 存檔權威持有。schema1 的 seed 仍取同一存檔原有 generator seed，不能重新定義成 M6 random；schema1 的既有 Overworld 原版 options／properties／randomSequences 行為維持。family 所需的 schema1→2 唯一路徑與首次遷移前外部改 seed 的不可偵測界線，明定於 §3.3。

**journal 格式界線**：M6 新意圖使用上表 schema5 的完整 exact 欄位，legacy intent 仍以舊 exact 欄位解碼；schema5 可同時容納兩者，不改原 `CandidateId`、`DoorKey`、SELECTED 與 MEASURED。首次 schema5 checked 寫出前，先確認 M5 ledger 初始化且沒有未確認寫入；寫出後不可降回 schema4。僅新 Context 凍結的 `FamilyCapabilityVersion=1` 可讓該收據選 Nether／End，`NEW` 另須 intent 欄位一致；不能用當前程式版本推斷舊 frozen entry 的能力。

### 3.3 schema1 的唯一遷移路徑

切片②／③不遷移 schema1 record；legacy `NEW` 可照舊配置 schema1。切片④在第一次需要 legacy U 的 Nether／End 前，依 catalog schema1 原值逐欄建一筆 schema2 `MIGRATED_SCHEMA1`，`LegacySchemaOneDigest=SHA-256`（canonical 編碼之**遷移前** schema1 definition，含 UUID／ordinal／原 Overworld descriptor，不含可變 availability），`RootWorldSeed` 取當時存檔 generator seed，`GenerationSettingsId=quantumchamber:legacy_overworld_v1`。原 Overworld descriptor 的五個**語意欄位**／key／storage 與 factory 路徑保留；schema2 序列化必填的 `RoleGenerationSettingsId=quantumchamber:legacy_overworld_v1` 是表示層附欄，不改原 generator。再附加以同一 UUID 推導的 Nether／End descriptor；checked catalog 讀回後才允許新 role storage。不得先改磁碟 chunk 或 live Overworld。舊 receipt／allocation row 查到遷移後 record 時，重建預期 schema1 definition，要求 digest、所有原欄位、root seed＝canonical save seed、兩個附加 role descriptor 恰等於規定值；只有這個有證據的 schema2 等價型可代替舊 schema1 exact match。任何其它 schema2 或 digest 不符仍 `AUTHORITY_CORRUPT`。若已有 partial 新 role storage，先守 orphan／owner gate，不改寫 catalog 以掩蓋它。

schema1 只有目前存檔 seed，沒有舊 seed 副本，因此**首次 M6 遷移前若外部已改存檔 seed，無法偵測，亦不支援恢復原地形**；本輪的可判定拒絕條件是：schema1 record 欄位／Overworld storage 身分不符、既有 profile 非 legacy、已有非預期 role storage，或已遷移 record 的 frozen seed 與當前 save seed 不符。首次遷移會以當時 save seed 當 legacy 原值；人工改 seed 後再遷移的 save 屬未支援輸入，不能宣稱自動 fail closed。遷移一旦 checked，禁止回到舊版或按新 seed 重寫。舊 build 遇到 schema2 envelope 必須拒絕，不降版。

### 3.2 提交與失敗窗口

先 checked 落盤 M4 `MEASURED+SELECTED` 的 exact receipt，再 checked `COLLAPSING` intent，然後 checked allocation ledger entry，最後 checked catalog schema2 的 seed／profile／三 role identity；在 catalog 讀回與 exact 比對前，不建立任何新 role storage 或 `ServerWorld`，不產生 chunk。`NEW` 的新 profile 意圖在候選生成時一次決定並由 journal exact 保存；同 token 重試只可回到同 UUID、同 seed、同 profile。seed 由 frozen material 決定，不能在 catalog 重試時讀當下亂數。allocation entry 與 intent、catalog 與 entry／意圖／seed／role descriptors 任一不一致，標成 authority corrupt／SEALED 並保留原檔，不接受現有 storage 冒充正確紀錄。

在 intent 前 crash：只看已確認的 M4 authority；intent 後、allocation 前：同 token 續配；allocation 後、catalog 前：同 UUID、seed 與 profile 補 catalog，先檢查 orphan storage，存在即 fail closed；catalog atomic move 完成但讀回失敗：current 保留未確認，停止物化，重啟或下次寫入先 strict 收斂，不能另配；catalog 已確認但 role storage／世界建立中 crash：依 catalog 同 key 續作，不刪玩家世界，對 partial storage 做 ownership／格式檢查，無法證明安全即停服或 `DISABLED` 並保留證據；profile 參數或某 role 損壞：不可退回 vanilla 或 canonical，也不可重配 UUID，受影響 U 不可進入。`EXISTING` 只接受已確認且完整、相容的 catalog 定義。每個轉移的 flush、force、atomic move、strict readback、exact compare 與 roll-forward／restore 分類沿用 M5 §6；不要以 memory current 代替 flushed 權威。

**角色物化政策**：schema2 三 role identity 在 catalog checked 時一次配置；ServerWorld／chunks 依實際進入、portal、gateway、respawn 需求 lazy 物化。schema1 既有 Overworld 的 M3 eager 開服契約維持；已配置 schema2 若沿用 eager enabled 啟動，僅 eager 建立現有 Overworld，Nether／End lazy。開服前驗 catalog 完整、所有已存在 role storage 與 ownership；任何必要 role 物化失敗不得 fallback 到 canonical。新 role 在首次寫入前先確認目錄不存在或屬於 exact descriptor，建立時留下可恢復狀態，重啟可 idempotent 繼續；不刪 partial 世界當 rollback。

## 4. 生成策略與選擇（主控提案，待 review）

可行作法：A 從凍結的 `generationSeedMaterial` 決定 seed／profile，窄且可重現；B 另設 profile 抽選 ledger，較易動態調權但增加跨檔交易；C 配置時用 runtime RNG，重試會漂移。選 A。profile registry/API 最低契約是 `register(ProfileProvider{id, version, validateParameters, resolveGenerationSettings})`、`freeze()`、`resolve(id,version,parameters)`：只允許本 mod 或受信任的 Java 擴充在**server 開始讀任何 M6 catalog／candidate 以前**註冊；相同 id＋version 重複註冊即拒絕啟動，不以最後一個覆蓋；freeze 後禁止註冊或改 provider。每個 provider 必須 deterministic、無世界修改副作用，對同輸入給相同 descriptor；開啟 save 時找不到 provider／版本或設定映射不符，受影響 U fail closed，不用其他 provider、enum 預設或 canonical 設定替代。registry 可供實作 API 查詢可用 id/version 與嚴格解析 descriptor，不執行 NBT 指定的任意程式碼。M6 只內建下表三種新 profile，舊 profile 不改。

| 新 `NEW` profile（提案） | root seed／差異 | 驗收 |
| --- | --- | --- |
| `RANDOM_VANILLA_V1` | 按下文固定雜湊取不同於來源的 root seed；三 role 使用已驗的 `quantumchamber:vanilla_normal_v1` 設定 | 同一意圖重啟完全相同；和來源 seed 不同，生成有可觀察差異 |
| `SAME_SEED_FRESH_V1` | 精確複製 source root seed **及來源各 role 的已驗 generation settings snapshot**；新 UUID／空 storage，不複製方塊、實體或玩家歷史 | 同 seed 同設定的原始 terrain／biome／structure 相符，來源玩家建築不存在；日後修改互不影響 |
| `SAME_SEED_TOGGLE_AMPLIFIED_V1` | 同 source root seed；來源 Overworld 為 normal 就改 amplified、為 amplified 就改 normal；Nether／End 設定照來源 snapshot | 相同 seed，Overworld 有可重現地形差異；三 role 重啟不變 |

**分階段 policy snapshot（主控提案）**：切片②新 intent 固定 `PolicyVersion=1`，只選 random；切片③前半新 intent 用 `PolicyVersion=2`，從 random／same-seed 各一半選；切片③ mutation 驗收後的新 intent 才用 `PolicyVersion=3`，三類等量。切片②／③的 `FamilyCapabilityVersion=0`；僅切片④ family／routing／respawn 驗收後新產生的 intent 固定為 1，舊 intent 不升版。每一階段只凍結當時已驗的 profile；既有 intent 永不升 policy 或重抽。三類等量是為了讓玩家在少量新宇宙中能實際看見三種結果，並簡化第一輪驗證，不冒稱最終平衡。choice 取 `SHA-256(UTF8("quantumchamber:m6:profile-choice:v1") || int32BE(PolicyVersion) || GenerationSeedMaterial[32])` 第一個 unsigned 64-bit 大端整數，對當版 profile 數取餘數；固定排序是 random、same-seed、toggle-amplified。random seed 取 `SHA-256(UTF8("quantumchamber:m6:root-seed:v1") || GenerationSeedMaterial[32] || AllocationToken[32])` 第一個 signed 64-bit 大端整數；若恰等於 `SourceRootWorldSeed`，與 `0x9e3779b97f4a7c15` XOR 一次，必不同。same-seed／mutation 直接用 `SourceRootWorldSeed`。

source seed 與各 role generation settings 在**候選生成時**按 exact source family 凍結；canonical source 取存檔 generator seed 與原版 settings，舊 U 取其 legacy seed/settings。`SourceGenerationSettingsId` 指向一份已驗、持久、可按 id＋版本重建的設定快照，包含三 role 所需原版 preset／參數；不得只存名稱而後來由當前 config 改出不同 generator。M6 內建僅支援可證實精確重建的 `quantumchamber:vanilla_normal_v1` 與 `quantumchamber:vanilla_amplified_v1`，若來源有其他自訂／未知 preset，不能建立 same-seed／mutation intent：候選產生應 fail closed，不能默默降成 normal；random 亦需明確可驗的來源 root seed。`EXISTING` 不抽 profile。固定 1.21 registry 必須證明 amplified preset、biome／structure 及獨立 ServerWorld 可用；否則先修本文並獨立 review。terrain noise、biome、structures 都用持久 root seed，不只換 `BiomeAccess.hashSeed`。

**native 1.21 實作前 gate**：逐一定位原版 `ServerWorld`／`DimensionOptions`／`ChunkGenerator`／`GeneratorOptions`／`BiomeAccess`／`RandomSequences`、`UnmodifiableLevelProperties`、End dragon／gateway persistent state 的 owner 與讀寫時序。先用只讀 decompile 或原始碼與最小 runtime probe 證明：各 role 的 generator／noise／structure／biome seed 取 catalog root；properties／randomSequences 不被另一 U 或 canonical 共用可變 instance（schema1 legacy 例外保留舊語意）；存檔與重啟可精確還原，且 registry 重新開啟不依賴已卸載的 transient 物件。原版 API 未經證實前不得稱「已支援」或僅憑 constructor 參數推論隔離。要改變 schema1 已生成世界的 generation options 時先停下修訂 spec。

固定 1.21 原版 JAR 的 bytecode 盤點已見：`ServerChunkLoadingManager` 由 `ServerWorld.getSeed()` 建 noise 與 structure placement calculator；`ServerWorld` constructor 又直接由全域 `SaveProperties` seed 建 structure locator／End dragon fight；`ServerWorld.getSeed()` 本身**每次呼叫**都讀 `server.getSaveProperties().getGeneratorOptions().getSeed()`，不只在建構期。故新 schema2 世界須有只作用於**exact server＋world key＋catalog descriptor**的長期 seed owner，讓建構期間及建構後每次 `getSeed()` 都回 frozen root，所有原生消費者一致；建構期的暫時 context 若使用，須核 server thread／worker 時序並在 `finally` 清理，且不能是唯一的 seed 修正。無匹配 descriptor 時完全走原版；不得暫時替換全域 save properties、generator registry 或 canonical `DimensionOptions`，更不得讓同時建構的 U 共用錯誤 context。實際 mixin 注入點／數量由 native gate 定位後寫入 plan，單世界原版與多世界並行 probe 必須驗 owner 不洩漏。schema1 舊 Overworld 仍使用舊路徑；若無法隔離所有 seed 消費者，切片②不得宣稱完成。

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

## 9. 尚待設計審查核定的窄項

1. 主控提案的三類等量預設與 `SAME_SEED_TOGGLE_AMPLIFIED_V1`，不是使用者指定的平衡規則；如原版 1.21 registry gate 不成立，須明列替代 mutation 再 review。
2. schema1 舊世界首次升級前若存檔 seed 曾被外部變更，舊 catalog 沒有 seed 副本，無法純靠它證明歷史 seed；須在 native／fixture gate 確認可採的證據與 fail-closed 條件。
3. Bed／anchor、dragon／gateway 的原版 owner 與資料格式、非玩家 portal 路徑先以固定版本 source／runtime 核實，不能由高層設計推稱已有 API。
