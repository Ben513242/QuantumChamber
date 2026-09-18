# M2 供電式走廊與斷電返還實作計畫

> **執行代理要求：** 使用 superpowers:subagent-driven-development，逐任務執行、測試與評審；不在任務之間重複詢問是否繼續。

**Goal:** 單人外部先供電、進入關門補齊 QuantumState 後看見真實延伸走廊；斷電安全回同一原艙，走廊結束後才解除原艙保護。

**Architecture:** 一個固定 `quantumchamber:superposition` 世界，版本化恢復 journal、共享 cohort、普通實體幾何與有限局部頁面實例。透過 M1.2 gateway 接入活動／返還狀態；所有玩家與恢復修改在伺服器執行緒序列化。

**Tech Stack:** Minecraft 1.21／Fabric／Java21／Gradle8.8／JUnit5／Fabric GameTest／Netty EmbeddedChannel 測試fixture。

**Spec:** Task1–5歷史為 `docs/superpowers/specs/2026-09-17-m2-powered-corridor-design.md` 與M1.2；Task6起依已核准 `docs/superpowers/specs/2026-09-18-m2-lateral-buff-maintained-design.md`。2026-09-18使用者要求完成M2自動gate後進M3，人工checklist／整分支final review留實作收尾，main仍不得未驗合併。

## Global Constraints

- Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、Java 21、Gradle Wrapper 8.8。
- 單一 Fabric module，main／client 分離、三種建材、7×7×7／5×5×5、Controller local(3,6,0)、25格門與青紫造型不變。
- 僅固定靜態 Superposition Dimension；無 UniverseRegistry、runtime allocation、Universe candidate selection、跨宇宙passage或跨宇宙projection materialization。固定空間的入口replica允許PROJECTION標記，沒有新Universe origin／跨宇宙投影。
- 無 mandatory renderer／portal／dimension library、無自訂 packet、無 LICENSE；只有全部驗收gate與最終review無問題後依使用者授權push／merge main，未驗不猜通過；不改既有玩家存檔、不刪世界。
- 玩家至少一人、排除spectator、完整bbox在interior、全員buff；凍結cohort。Task1–5為歷史入場消耗；Task6起新session入場保留Buff、不重置duration／hiddenEffect，任一人失效整組返還，HIGH保持原艙保護；schema1保留舊恢復政策。
- 外部斷電：先安全返還、結束session、再解除原艙保護。離線／返還失敗／錯身分仍保護，不可猜其他world或床／spawn。
- 火把使用已核准可選client方案；不改原生world光照、不替代QuantumState，照明與GPU相容性人工待驗。
- 所有說明與註解使用繁體中文；只用apply_patch修改來源；逐任務RED／GREEN／精確commit／獨立spec+quality評審。

## 已查核 API 與幾何澄清

- `Entity.teleportTo(TeleportTarget)` 回傳 Entity；移動後仍核對真實world、座標／bbox。`ServerPlayerEntity.teleport(...Set<PositionFlag>...)` 的boolean無條件true，不能當交易確認。
- `TeleportTarget(ServerWorld, Vec3d position, Vec3d velocity, float yaw, float pitch, PostDimensionTransition)`，使用 `NO_OP`，不送portal動畫。
- 效果完整快照用 `StatusEffectInstance.writeNbt()`／`fromNbt(NbtCompound)`；copy constructor 不複製hiddenEffect，不能用於完整rollback。
- JOIN早於登入完成；只入列，下一server tick才返還。DISCONNECT可能在Netty執行緒；擷取UUID／server後server.execute序列化，不送packets。
- `addTicket`／`removeTicket` 的int是radius；使用session唯一UUID與對稱操作，radius2使entity ticking。不得把level31當radius，不用setChunkForced。
- STOPPING時world尚live，可保存／移除tickets；STOPPED僅清refs／queue。
- 原生PersistentState.save吞IOException且清dirty；journal必須有可觀測的checked／atomic落盤，不把manager.save返回當作持久化成功。
- logical page=96、door spacing=8不變；physical slot表示有限affine實例，可包含多頁外觀alias。近玩家同映射，遠距群體獨立；不是whole-session單一視窗。不同slot間距至少160且完整AABB不相交。
- occupied±1是邏輯生命週期，另有兩端576格有限外觀apron，覆蓋Base32chunk／512格設計視距；不建立額外候選資料。無任意shader／擴大視距無縫保證，人工gate另記。
- 每tick方塊建造／清理預算4096；occupied page上限128、局部實例上限64、管理entity pin上限256。資源不足走安全返還，不刪有價物品、不提交未準備映射。

- 全域ticket覆蓋聯集上限4096chunks，與同值的block-write budget分開；計所有provisional/current/prepared/retiring／bootstrap leases bounds轉chunk rectangles各外擴2之唯一聯集。中心票依session/chunk另refcount。單rectangle／聯集超量在展開或加票前拒絕，long checked算術、7×7截面／高度／world bounds先驗；new配置拒絕／上層安全返還，非法或超量attach保留journal拒絕，不能加票後等OOM。不是64instances全量可用或任意視距／硬體效能保證。

環境沿用 M1.2：Java21、GRADLE_USER_HOME=C:/Users/Ben/.gradle、require_escalated操作快取、offline聚焦與提交前完整測試；Git per-command safe.directory。先完成M1.2自動gate再dispatch M2 Task1。只讀API查核的完整證據位置記入本計畫ledger。

## 已核准修訂：原生 HANDLE checkpoint（2026-09-17）

使用者已同意重用Minecraft內附JNA實作，並授權全部gate通過後push／合併main；人工未驗不當作已通過。

- 公開 `PlayerCheckpointStore.saveAndVerify(MinecraftServer,ServerPlayerEntity,Optional<PlayerRecoveryCheckpoint>) throws IOException` 不變。只驗原生save結果，不自行重寫UUID.dat、不外露可指定path的public API。
- 重用MC1.21 runtime已有 `jna/jna-platform 5.14.0`；不加依賴/新JNI DLL/moduleopens/系統權限。Win32薄封裝精確函式/struct以官方5.14.0/Microsoft與本機binary核對，不猜。
- 同existing正式UUID.dat的自有Win32 HANDLE讀全部compressed bytes→原生NBT完整expected snapshot等值（含DataVersion/unknownmodkeys）→同HANDLE `FlushFileBuffers`→同HANDLE再讀不變。以native volume/File ID比held與正式path metadata probe，不用nullkey/bytes/時間戳/path字串冒充identity。
- volume root為信任錨，逐層hold普通ancestor directory HANDLE，OPEN_REPARSE_POINT/拒絕reparse與非directory/拒絕WRITE與DELETE分享，核對opened path與File ID，才開leaf；不能只Java precheck再open而重現junction ABA。目錄READ_ATTRIBUTES，leafexistingREAD+WRITE/noCREATE/noTRUNCATE。所有HANDLE finally對稱close；sharing/未知ID/JNA/Win32錯→IOException保留權威。
- 第一個必驗是本機Windows11/NTFS/JDK21；UNC/remote/ReFS/未知provider不猜支持。非Windows若不能窄地提供同handle原生identity就fail closed並明列runtime限制，不能留已否定的path-fileKey原子假說或用CI skip冒稱實測。保留64MiB compressed-size上限；不承諾跨檔原子/整機斷電。
- Task1可Create `src/main/java/dev/quantumchamber/persistence/WindowsPlayerCheckpointVerifier.java`（鎖鏈/readback/identity/flush）、`WindowsCheckpointNative.java`（package-private JNA接口/struct/錯誤/close），Test `src/test/java/dev/quantumchamber/persistence/WindowsPlayerCheckpointVerifierTest.java`。只有窄平台封裝，不PlayerRepository/大native框架；Task3/4 API不變。
- 聚焦TDD：nullkey本機正向完整NBT；missing/stale/corrupt/backup拒絕；同handle read/flush不變；leaf/ancestor rename、junction ABA、第二writer、identity/flush失敗、close無leak。僅ownfreshfixtures，效應/座標/inventory不變nativeGT仍綠。不mocks冒稱native、不OSskips冒稱跨平台。
- 若volume/ancestor鎖本機拒絕或需改support/interface，具體NEEDS_CONTEXT由root裁定，不content-only放寬。聚焦後完整clean build/GT一次、XML/JAR/main-only四world正向、獨立spec+quality評審。推送/合併只全部gate無問題後。

### Task 1: 固定世界、版本化恢復資料與checked journal

**Files:**
- Create: `src/main/resources/data/quantumchamber/dimension/superposition.json`
- Create: `src/main/resources/data/quantumchamber/dimension_type/superposition.json`
- Create: `src/main/java/dev/quantumchamber/superposition/SuperpositionWorld.java`
- Create: `src/main/java/dev/quantumchamber/superposition/SessionState.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionJournalStore.java`
- Create: `src/main/java/dev/quantumchamber/persistence/PlayerRecoveryCheckpoint.java`
- Create: `src/main/java/dev/quantumchamber/persistence/PlayerRecoveryCheckpointAccess.java`
- Create: `src/main/java/dev/quantumchamber/persistence/PlayerCheckpointStore.java`
- Create: `src/main/java/dev/quantumchamber/mixin/ServerPlayerRecoveryCheckpointMixin.java`
- Create: `src/main/java/dev/quantumchamber/mixin/PlayerManagerSaveInvoker.java`
- Modify: `src/main/resources/quantumchamber.mixins.json`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStateTest.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionJournalStoreTest.java`
- Test: `src/test/java/dev/quantumchamber/persistence/PlayerRecoveryCheckpointTest.java`
- Test: `src/test/java/dev/quantumchamber/persistence/PlayerCheckpointStoreTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/StaticDimensionsTestServerMixin.java`
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`

**Interfaces:**
- Produces: `SuperpositionWorld.KEY` 為 RegistryKey<World>，ID quantumchamber:superposition。
- TestServer 靜態資料bootstrap：pinned `TestServer.method_40377(LevelInfo, SaveLoading.LoadContextSupplierContext)` 使用空DIMENSION registry而丟棄datapack世界；僅testmod `@Redirect`其 `DimensionOptionsRegistryHolder.toConfig(Registry<DimensionOptions>): DimensionsConfig` 入參，依原生dedicated Main路徑改為 `context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION)`。handler附加enclosing `LevelInfo`／`LoadContextSupplierContext`，保留FLAT holder；不new ServerWorld/runtime allocate/production hook。此Mixin只testmod註冊，release不得包含。
- Produces: `SessionState { ARMING, SUPERPOSITION, RETURNING }`。
- Produces: recovery record含UUID session/chamber、完整ChamberOriginAuthority、List<Participant>、List<SpaceLease>、state、boolean restoreEntryEffectOnReturn；nested SpaceLease含int slotId及防禦性複製的BlockBox bounds，確保重啟能保護舊空間、不提早reuse。Participant含UUID、sourcePosition/velocity、yaw/pitch、QuantumState NBT快照、returned；集合與NBT複製，不持久化Java/world物件。缺lease／錯bounds／重複slot均拒絕，不以空slots猜安全。
- Produces: schema1 的 RestoreEntryEffectOnReturn 為必填 boolean，ARMING 必須 true、SUPERPOSITION 必須 false；RETURNING 沿用原權威決策，不能按目前 state／是否有 buff 重算。false 表示完全不修改當下 QuantumState，不能刪除走廊中後來新喝的效果。缺欄位／型別錯誤／非法 state-policy 組合皆拒絕。
- Produces: `public static SessionRecoveryState get(MinecraftServer)`、`public Map<UUID,SessionRecoveryRecord> records()`、`public void put(SessionRecoveryRecord)`、`public boolean remove(UUID)`、`public void requireHealthy()`、`public void flush(MinecraftServer)`；STATE_ID quantumchamber_sessions、schema1。
- Produces: `public Map<UUID,SessionRecoveryRecord> flushedRecords()` 深度不可變已確認落盤snapshot：正常load健康資料或checkedflush成功才更新；put/remove記憶體dirty資料不冒充durable。Main initializer在SERVER_STARTED明確get/requireHealthy，損壞journal不得進入正常tick交易；此任務不install入場backend。
- Produces: `public static void SessionJournalStore.write(Path target, NbtCompound wrapped)`／`public static NbtCompound read(Path target)`，compressed NBT，write失敗拋明確例外，temp與target皆在同一data目錄；不吞錯、不清dirty假成功。
- Produces: `public record PlayerRecoveryCheckpoint(UUID sessionUuid, AppliedPolicy appliedPolicy)`，nested enum `RESTORE_ENTRY/KEEP_CURRENT`；namespaced 玩家 NBT compound `quantumchamber:recovery_checkpoint`、schema1，單一 marker、不累積歷史。嚴格 UUID／policy／型別／schema；native load 遇壞 marker 不得靜默轉 Optional.empty 或讓原生例外捕捉使其假成新玩家。getter 延後回報健康失敗，保留原始 marker NBT，正常 auto-save/copyFrom 不覆寫未知 marker。
- Produces: `public interface PlayerRecoveryCheckpointAccess`，`Optional<PlayerRecoveryCheckpoint> quantumchamber$getRecoveryCheckpoint()`、`void quantumchamber$setRecoveryCheckpoint(PlayerRecoveryCheckpoint)`；setter拒絕null，getter對損壞 marker 明確拋健康例外。Mixin 只保存／複製這個 marker；若 copyFrom 需要健康／原始 marker 狀態，可增加 marker-only copy method 並在report列精確簽名，不暴露任意玩家 NBT。
- Produces: `public static void PlayerCheckpointStore.saveAndVerify(MinecraftServer,ServerPlayerEntity,Optional<PlayerRecoveryCheckpoint>) throws IOException`；單一 protected PlayerManager.savePlayerData invoker、server-thread-only。Optional.empty 僅供入場消耗 checkpoint，不新增返還 marker；Optional.of 要求正確 runtime marker。原生保存後讀正式 UUID.dat（不是dat_old）的完整原生 NBT，與當下原生 snapshot 語意相等比較，包含所有其他mod keys；原生DataVersion等metadata按已核對pinned bytecode建完整expected，不剔除未知欄位。Windows以同一opened原生HANDLE完整讀回／FlushFileBuffers／native File ID/正式path probe與ancestor namespace鎖鏈／flush後不變，依已核准原生修訂；不能用path-fileKey/nullkey/bytes冒充handleidentity；缺檔、stale、replacement、parse或force失敗皆拋IOException，不自行改寫玩家檔、不假成功。
- Checkpoint路徑只從目前 server 的原生玩家資料路徑與 UUID 組成；先核對 WorldSavePath 實際常數名，不使用玩家提供路徑。純IO測試可測小 package-private 檔案核對核心，native serializer／read/write/copyFrom另有GameTest，不拿假serializer當live gate。saveAndVerify不等於跨檔原子提交或整機斷電保證。

- [x] **Step 1: 原生已知world缺失先RED。** 不引用新class：

```java
RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD,
        Identifier.of("quantumchamber", "superposition"));
context.assertTrue(context.getWorld().getServer().getWorld(key) != null,
        "固定Superposition世界必須真實存在");
```

- [x] **Step 2: 跑原生RED，另寫journal不可寫／錯schema／NBT roundtrip測試。** 期望M1.2沒有固定world，GameTest該項失敗；NBT缺source資料不得偷偷丟掉participant。
- [x] **Step 3: 實作靜態JSON與模型／codec／atomic store。** dimension使用minecraft:flat、layers=[]、biome=minecraft:the_void、structure_overrides=[]、features/lakes=false；type所有必要字段含has_raids/piglin_safe、無skylight、ambient0、min_y0、height/logical_height256、coordinate_scale1、bed/anchor=false、monster_spawn_light_level/block_light_limit0、effects=minecraft:overworld。Data pack48／resource pack34勿混用。保存wrapper data＋DataVersion，force temporary file後atomic replace；不支援atomic時拒絕交易，不unsafe覆蓋舊journal。flush可用save override呼叫checked store，不能讓原生save吞錯。
- [x] **Step 3b: 保存實體回收與lease足夠資料。** 開新幾何前先flush對應lease bounds；重啟保留已分配空間至相關participant返還完成。ARMING失敗才恢復QuantumState快照；正常SUPERPOSITION結束不重新給一劑效果。同一record重讀不會重複消耗或補發。直接測兩個 RETURNING roundtrip：restoreEntryEffectOnReturn=true 與 false 都不能遺失／互換；另測缺欄位及 ARMING,false／SUPERPOSITION,true 必須失敗。玩家 checkpoint 與 returned flag 的跨檔冪等由 Task4 覆蓋，不把此 codec 測試冒充 crash checkpoint 證據。
- [x] **Step 4: GREEN。** 世界實際getWorld、world codecs、journal寫入讀回／不可寫保留舊資料、重複UUID／非vanilla source role拒絕，完整clean build+GT。
- [x] **Step 4b: checkpoint GREEN。** 純 codec／完整 NBT 相等與 stale/壞檔負面；native marker write/read/copyFrom、無 marker舊玩家、raw壞 marker保留；真原生 player save 後正式.dat readback＋force。不移動玩家或消耗效果，只提供Task3/4需用的窄保存原語。
- [x] **Step 5: 自評／提交。** `feat: add static superposition world and durable recovery journal`；目前不傳送、不消耗藥水，report精確說明API與錯誤路徑。

**Task 1 自動完成證據：** source `1a21dcd`、平台修正 `395906c`，獨立評審的唯一 Important 已經限定複審確認 ADDRESSED，無新回歸。真 Windows 非快取 clean build＋GameTest：138 JUnit 宣告／137 實跑成功／1 非 Windows 專屬條件略過，12 必要 native 案例零略過，61 GameTest 零失敗。Production main-only Done→console stop→四世界保存→Java／wrapper exit0；沒有傳送／效果消耗／走廊實作。

**平台驗證接續約束：** 保留 Ubuntu 基礎建置與平台契約 GameTest，另設 Windows 原生 gate，必要 native XML 必須實跑且零略過。非 Windows 的受控拒絕不是 checkpoint 保存成功；Windows 上 os.name 路由探測不是 Linux OS 實測。真正遠端 Actions、跨檔恢復 consumer、人工／GPU gate 仍待驗，不授予 main 合併通過。

### Task 2: 邏輯頁、局部affine配置、replica及session保護

**凍結cohort的持久防線：** 同SID的恢復進度更新須保持完整UUID名單與不可變source position／velocity／yaw／pitch／完整QuantumState snapshot；僅returned進度可更新，列表順序可不同。`SessionRecoveryState.put` 替換前同時核對current與上一份flushed權威，拒絕不改records／dirty／flushed；PageManager破壞性清理與完成收據前仍重驗凍結來源。窄共用等值helper不改schema／平台，不能只runtime拒絕而讓短名單已落盤後重啟遺失離線玩家。

**已核准入口切邊契約（2026-09-18）：** 只有 alias 完整包含 replica 0..6 及前後連接格 −1／7、且連接格不在端 cap，才物化入口。端 cap 在 aliasStart／aliasEnd−1 時，精確條件為 `aliasStartBlock <= -2 && aliasEndBlock >= 9`。其餘建普通封端走廊、不排除 343 格；保存前後門 OPEN 意圖，回頭完整包含入口時重建。無 current 入口時 `entrance(UUID)` 明確拒絕；consumer 使用 typed mappings 與保存的來源 frame，不暗中 reserve。

**原生 bootstrap 負向證據：** default-off testmod probe 只於明確 case／canonical owned fresh root，掛 `MinecraftServer.loadWorld` RETURN、健康 attach 前，以 CREATE_NEW 建立指定非法租約 journal。獨立 native main JVM 驗拒啟原因、normal tick=0、before／after journal hash 不變、票對稱清理；Java exit 0 不等於拒啟通過。既有 guard 補證據不是 retroactive RED；不得改既有玩家資料。

**Files:**
- Create: `src/main/java/dev/quantumchamber/corridor/LogicalAddress.java`
- Create: `src/main/java/dev/quantumchamber/corridor/DoorKey.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorLayout.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SlotAllocator.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorGeometry.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SessionEntranceAllocator.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SessionSpaceProtection.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SessionEntranceDoorService.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/ConnectedGameTestPlayer.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/DoorWriteFault.java`（既有 testmod observer 的 block-write 計數與限定 clear 拒絕）
- Modify: `.github/workflows/build.yml`（Windows 必要原生案例 XML 守門，追加 Task2 GT）
- Create: `src/testmod/java/dev/quantumchamber/gametest/M2LeaseBootstrapProbe.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/M2LeaseBootstrapFixtureMixin.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberProtectionService.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberControllerBlock.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Test: `src/test/java/dev/quantumchamber/corridor/CorridorLayoutTest.java`
- Test: `src/test/java/dev/quantumchamber/corridor/SlotAllocatorTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`

**Interfaces:**
- Produces: `LogicalAddress(long pageIndex, double localZ)`、`static LogicalAddress from(double logicalZ)`；floorDiv／finite／range驗證。
- Produces: `DoorKey(UUID sessionUuid, long logicalDoorIndex, Side side)`、Side LEFT/RIGHT；index=floorDiv(blockLogicalZ,8)。
- Produces: `public static List<LayoutComponent> CorridorLayout.plan(Set<Long> occupiedPages, int apron)`，nested `LayoutComponent(long firstCorePage, long lastCorePage, long aliasStartBlock, long aliasEndBlock)`，core頁端點inclusive／aliasEnd exclusive；排序依firstCorePage，回傳局部不跨空隙的分量，logical↔physical可逆、近entities同affine。
- Produces: `public Optional<SlotLease> SlotAllocator.reserve(BlockBox relativeBounds)`、`public void release(int slotId)`，nested `SlotLease(int slotId, BlockPos origin, BlockBox bounds)`；完整AABB不交、中心間距>=160，不用歷史logical distance決定physical位置。
- Produces: `public void CorridorPageManager.attach(MinecraftServer)`、`public void prepare(UUID sessionUuid, UUID chamberUuid, Direction facing, Set<Long> occupied)`、`public void tick()`、`public boolean ready(UUID)`、`public ChamberFrame entrance(UUID)`、`public Vec3d toPhysical(UUID,double lateral,double y,double logicalZ)`、`public void release(UUID)`；prepare只入列，ready才可commit，資料不存在／容量拒絕用明確例外，不回傳假frame。
- Produces: `public static CorridorPageManager forServer(MinecraftServer)` 回傳已由bootstrap attach的唯一authority manager，server/world身分不符拒絕，不自動建立第二個allocator／tick loop。舊四參數prepare只解析完全匹配的既有reservation並委派typed prepare，不偷偷reserve或構造缺cohort紀錄。

- Typed mapping契約如下；所有record集合／BlockBox防禦性複製，token必須匹配manager自己持有內容，不能由caller偽造slotId取得權限。current只已發布，prepared不授權正常操作；每session至多一pending，64上限計入current＋pending＋retiring。

```java
public static record MappingRef(UUID sessionUuid, long instanceEpoch, int slotId) {}

public static record MappingView(
        MappingRef ref,
        long firstCorePage,
        long lastCorePage,
        long aliasStartBlock,
        long aliasEndBlock,
        long logicalAnchorBlock,
        BlockPos localBlockOrigin,
        Direction outwardFacing,
        BlockBox bounds) {}

public static record MappingSet(long epoch, List<MappingView> instances) {}

public static record PreparedMappings(
        UUID token,
        UUID sessionUuid,
        long baseEpoch,
        MappingSet target) {}

public static record PhysicalPose(
        Vec3d position, Vec3d velocity, float yaw, float pitch) {}

public static record LogicalPose(
        double lateral,
        double height,
        double logicalZ,
        Vec3d localVelocity,
        float localYaw,
        float pitch) {}

public static record EntityMove(
        UUID entityUuid,
        MappingRef source,
        MappingRef target,
        LogicalPose logicalPose,
        PhysicalPose before,
        PhysicalPose after) {}

public static record RemapBatch(
        UUID token,
        PreparedMappings prepared,
        List<EntityMove> moves) {}

public static record RetiredMappings(
        UUID token,
        UUID sessionUuid,
        List<MappingRef> instances) {}

public MappingSet currentMappings(UUID sessionUuid);
public PreparedMappings prepareRemap(
        UUID sessionUuid, long expectedCurrentEpoch, Set<Long> occupiedPages);
public boolean ready(PreparedMappings prepared);
public LogicalPose toLogical(MappingRef source, PhysicalPose physicalPose);
public PhysicalPose toPhysical(MappingRef target, LogicalPose logicalPose);
public RemapBatch beginRemap(
        PreparedMappings prepared, Map<UUID, MappingRef> affectedEntityOwners);
public RetiredMappings commitRemap(RemapBatch batch);
public RetiredMappings cancelPrepared(PreparedMappings prepared);
public void retire(RetiredMappings retired);
public PreparedMappings reserveInitial(UUID sessionUuid, UUID chamberUuid,
        Direction facing, Set<Long> occupiedPages);
public void prepare(PreparedMappings initial);
public void commitInitial(UUID sessionUuid, Set<UUID> cohort);
```

- initial：先freeze source snapshots/authority，reserveInitial只預留／回bounds、不寫geometry或publish；建立完整lease的ARMING,true並checkedflush，prepare自行核對durable record與reservation bounds/authority才入列。只可證實零geometry且無journal才provisional cancel；原子結果不明保留。commitInitial只在全員真world/bbox、effect commit/player checkpoint/durable SUPERPOSITION,false之後，自己重驗live cohort再publish epoch1；後孔25writes服從budget。

- commitInitial 可逐人再次原生 saveAndVerify(Optional.empty()) 並重驗全群真身分／pose後發布，不新增返還marker或通用收據系統；額外I/O需記錄。原來Task3 checkpoint-before-false順序不省略。false尚未checked提交的失敗為RETURNING,true；false已durable提交後的核對／publish失敗則安全RETURNING,false，保留lease／保護，不補發已提交藥效。
- remap：prepareRemap保持old current並先flush新舊lease union，ready後beginRemap才擷取最新live全群pose/pins；同ticknative搬移所有affected players/items/projectiles，commitRemap自行重驗全群actual result才publish target/owner/epoch。未受影響分量保留instance epoch/lease；少列entity／偽token／過期epoch／newpin皆拒絕。部分失敗保留batch唯一source/target owner與兩邊lease，不用finally無條件cancel；一次受控rollback仍失敗即RETURNING。
- retire：先pin／有價物品安全處理→同4096全域budget清幾何→checkedflush移除舊SpaceLease→allocator.release。durable清單只包含尚未安全釋放的current/prepared/retiring，不保留所有行走歷史；任何failure保守留reservation、達cap安全返還，不能無界加lease。release(UUID)為提出退休請求，不同步刪still-pinned空間。

- 最後 lease 收尾：不寫空 spaceLeases；精確同 server 的 flushed RETURNING record 全 participant returned=true、無 live pins／有價 items 已安全處理、budget 清理完成，才 remove 整筆 journal＋checked flush，確認 durable 無 SID 後 release allocator。新增 `public boolean releaseComplete(UUID sessionUuid)` 與 `public void acknowledgeRelease(UUID sessionUuid)`，只對已知真正完成的有限當次收尾發布收據，未知／in-memory 缺失／flush 失敗均不 complete；上層消費後 acknowledge、不保留所有已結束 SID。Task4 consumer 最後確認並解除原艙保護，不能泛化「查不到紀錄」為返還成功。

- Corridor lease tickets由唯一PageManager持有：checked durable lease後，完整finite bounds footprint以session UUID/radius2維持entity-ticking，session＋chunk去重/refcount，涵蓋prepared/current/retiring及bootstrap未釋leases。FULL非create＋world.isChunkLoaded(long)／shouldTick(ChunkPos)皆真才pin掃描／清理；同tickpose用iterateEntities真bbox/UUID，不以延後區段索引空判無pin。checked lease移除後才remove票，STOPPING對稱remove、STOPPED清refs／必要journal保留；Task3只source票。bounds／溢位／finite cap先驗，不對任意NBT巨box無界加票，chunk／IO／等待成本如實報告。

- Produces: `SessionEntranceAllocator` 入口同來源facing，CBE標PROJECTION＋來源chamber UUID；只在全員入場成功後開replica後牆的5×5孔通往走廊，不改原艙。
- Geometry ownership：初次 core pages=-1..1／alias=[-672,768)；replica local x/y/z=0..6 與走廊同一 SlotLease。只有完整物化入口時 base writer 排除這343格，replica writer獨佔 overlay，不另reserve造成AABB重疊。後孔成功後OPEN狀態由session保存，重建沿用，Controller與前門不能被base writer蓋掉；入口沒有永久pin，遠離或切邊缺連接區時可不物化，回頭完整包含時重建同一overlay狀態。occupied={7}／alias=[0,1440) 是普通封端走廊，回到 occupied={6} 才恢復完整入口；舊／退休Controller不獲門授權。
- Produces: `public static Optional<SessionEntranceDoorService.ToggleResult> tryToggle(ServerWorld,BlockPos)`，nested `public record ToggleResult(boolean changed,String message)`；僅已發布SUPERPOSITION、同server/world/current mapping、projection CBE UUID/facing與入口吻合才處理普通Controller右鍵，原子交易整面25格前門接既有負向走廊。empty表示非此服務目標而走原艙流程；known入口但ARMING/RETURNING/錯身分則handled拒絕，不回落Origin註冊或maintenance。操作租約阻止retire，成功才保存前門OPEN狀態供rebuild；不自動開／刪前門、不改來源前門、不給投影停用／拆除權。交易或rollback失敗保持safe保護並交session返還協調，不猜門已成功。
- 連續位置字面：C=(1000,70,2000)，replica feet local(3.5,1,5.5) 在 N/S/E/W 分別為(1000.5,65,2005.5)/(1000.5,65,1995.5)/(995.5,65,2000.5)/(1005.5,65,2000.5)。負向軸需cell-boundary offset，velocity只旋轉不平移。NORTH洞口block(1000,67,2006)與走廊(1000,67,2007)相鄰；第二NORTH C=(1192,70,2000)中心距192且完整不交，只沿z移160仍AABB相交必須拒絕。
- Produces: `ChamberProtectionService.registerAdditionalGuard(BiPredicate<ServerWorld,BlockPos>)`，SessionSpaceProtection使用精確server/world實例與slot index；registered once、STOPPED detach。
- Bootstrap：Main initializer明確呼叫 `public static void SessionSpaceProtection.initialize()`，一次註冊known-space guard/lifecycle與PageManager tick；SERVER_STARTED在journal healthy後attach真固定world並保護所有未釋放leases，STOPPED清refs。Task3 session manager不重複註冊PageManager tick，4096是全域建造/退休/overlay/入口門的共同budget。
- Geometry-only GT使用真Player但trusted recovery orchestration：reserveInitial→建立合法非空cohort/完整lease的ARMING,true並checkedflush→prepare typed token；無journal/只有put/錯bounds/RETURNING皆零writes。ready只sealed replica、epoch0；正向commit測例由trusted orchestration真移動/核對/效果與playercheckpoint→durableSUPERPOSITION,false→commitInitial，不冒充native lever-qualified入場。ConnectedGameTestPlayer此任務產出，後續Task3/4用同一helper；結尾有valid journal者需RETURNING/安全retire，不能直接release。

- [x] **Step 1: 數學RED，手算字面fixture。** from(-1)page=-1/local95、from(96)page1/local0；occupied={0,1000000}不生成中間gap；near positions95.5/96.5距離保持1且DoorKey across remap不變。

```java
LogicalAddress negative = LogicalAddress.from(-1);
assertEquals(-1, negative.pageIndex());
assertEquals(95.0, negative.localZ());
List<CorridorLayout.LayoutComponent> parts = CorridorLayout.plan(Set.of(0L, 1_000_000L), 576);
assertEquals(2, parts.size());
assertEquals(-1, parts.get(0).firstCorePage());
assertEquals(1, parts.get(0).lastCorePage());
assertEquals(-672, parts.get(0).aliasStartBlock());
assertEquals(768, parts.get(0).aliasEndBlock());
assertEquals(999_999, parts.get(1).firstCorePage());
```
- [x] **Step 2: 聚焦RED，建立entity/page pin與AABB collision案例。** 不用同一builder算expected、不測mock本身。
- [x] **Step 3: 最小配置／geometry。** 5×5 interior、7×7外框、8格左右門站、576apron；近interval合併、遠分量獨立，映射epoch更新整群，不單獨讓近玩家跳到另一slot。source orientation全四方向，完整bounds驗證。每tick4096寫入預算，頁準備前保留舊映射與pin；沒有玩家／items／projectiles／操作租約才回收。入口ChamberGeometry只用0..6，走廊另建座標helper。
- [x] **Step 4: 原生GREEN。** replica shell與門／CBE kind正確，known固定world普通setBlock被擋、authorized geometry可寫；跨頁碰撞地板／apron、遠玩家布局與slot資源界限。loop native服務尚不傳送玩家，視覺無終點人工待驗。
- [x] **Step 5: 自評與提交。** `feat: build bounded paged corridor spaces with guarded replicas`；報告實際bounds／budget與geometry完成時間。實作`b7490e7`／R1`c7a0e96`，原完整153 declared JUnit（152實跑／1平台略過）＋73 GT；R1 covering27 JUnit＋75 GT、成品與四world啟停均核對，scoped review I1結案。尚未紅石entry／完整返還backend，不宣告整M2完成。

### Task 3: 全員入場、效果交易與M1.2 gateway整合

**Files:**
- Create: `src/main/java/dev/quantumchamber/superposition/SuperpositionSession.java`
- Create: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Create: `src/main/java/dev/quantumchamber/transfer/SessionTransferService.java`
- Create: `src/main/java/dev/quantumchamber/transfer/QuantumEffectTransaction.java`
- Create: `src/main/java/dev/quantumchamber/transfer/ChamberReturnPlacement.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Test: `src/test/java/dev/quantumchamber/transfer/ChamberReturnPlacementTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberSessionGateway.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberPowerCoordinator.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberOccupantService.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberControllerBlockEntity.java`（僅既有activationBlocked唯讀getter改public，stage重驗故障，不setter／清旗標）
- Test: `src/test/java/dev/quantumchamber/chamber/ChamberPowerCoordinatorTest.java`
- Modify: `.github/workflows/build.yml`（Windows必要entry／fault／expiry／staging／return-idempotence GT XML守門，平台能力不變）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/ChamberGameTestBuilder.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/M1FoundationSessionFixture.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/M1FoundationSessionGatewayMixin.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/SessionTransferFault.java`（default-off，owned server／world／SID／player／case限定）
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionTransferFaultMixin.java`（partial move／false提交後checkpoint故障證據，不production seam）
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`

**Interfaces:**
- Consumes: M1.2 ChamberSessionGateway.Presence／StartResult／start／returnToOrigin的精確簽名與ChamberSessions.install。
- Consumes: Task2 `ConnectedGameTestPlayer`（真連線fixture，原先未啟用恢復事件）。
- Produces: `SuperpositionSessionManager implements ChamberSessionGateway`、`public static void initialize()` 註冊一次lifecycle/tick hooks、`public void tick(MinecraftServer)` 僅同attached server/thread執行，authority/runtime state不落client。
- Produces: gateway簽名不變，additive `Presence.ARMING`、`StartResult.STAGING` 支援4096budget非同步準備；coordinator在ARMING/STAGING維持READY=7／POWERED保護而不重複start，SUPERPOSITION才ACTIVE/11。低位／管理停用仍走原先return，ActivationBlocked故障旗標不清除。純測 staging重複刷新只start1、低位取消仍先return、false/throw仍保護。
- Foundation測試隔離：現有M1 nativeGT明確驗ARMED_ONLY／不消耗／不傳送；testmod-only fixture map以同server/world/controller位置身分限定M1 foundation backend，SuperpositionSessionManager.start的testmod mixin只對已標記fixture回ARMED_ONLY。ChamberGameTestBuilder原build在寫方塊前標記Foundation，新增buildForSession供M2明確移除該位置Foundation標記並走真backend；不保持跨tick global gateway override，不攔M2 case，不以Foundation結果聲稱M2 live。STOPPED清map，release無fixture/mixin。
- Produces: `public boolean SessionTransferService.move(ServerPlayerEntity, ServerWorld, Vec3d, Vec3d, float, float)` 回傳實際核對成功，不信teleport boolean。
- Produces: `public static Map<UUID,Vec3d> ChamberReturnPlacement.plan(ChamberFrame,List<UUID>)` 保留 UUID 確定性5×5floor slots；另有 `public static Map<UUID,Vec3d> plan(ChamberFrame,List<UUID>,Map<UUID,Vec3d> sourcePositions)`，先按來源 frame 的 local z／x 排序，再以 UUID 破同值，返還時保留來源相對排序（不是距離），不作為entry位置。Task4的真session返還使用三參數版本，兩參數版本只在沒有來源pose時提供明確fallback；最多25人，capacity不足入場前拒絕。Task3僅產出與測試返還API；entry採精確typed affine，pre-commit原pose rollback未確認則保持RETURNING,true，不用量化槽位假稱姿態未變。
- Produces: `QuantumEffectTransaction` 以NBT snapshot／restore與完整cohort一次commit，不提供Universe傳送API。
- Produces: 將既有 `ChamberOccupantService.contains(Box outer,Box inner)` 簽名公開成 `public static boolean`，重用現有六邊界完整bbox判定；算法不重寫、spectator篩選不變，供M2 transfer／恢復與nativeGT使用，不杜撰 Box.contains(Box) overload。

- [x] **Step 1: 原生先供電進人入場RED。** 沿用M1真ServerPlayer連線fixture，native lever高位／關門／喝藥後需進SuperpositionWorld且效果消耗；現有ARMED_ONLY adapter不移動，該斷言失敗。

```java
context.assertTrue(player.getServerWorld() == server.getWorld(SuperpositionWorld.KEY),
        "完整cohort必須進入真實固定世界");
context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE), "成功入場才消耗藥效");
context.assertTrue(player.getInventory().getStack(0).isOf(Items.TORCH), "入場保留攜帶物品");
```
- [x] **Step 2: 保存RED；測少一人buff、zero／spectator、容量、重複刷新。** 無效case全員仍在來源且buff未移除，held-high只一session。
- [x] **Step 3: 接gateway與ARMING交易。** 凍結cohort及source positions／effects，durable ARMING,true journal先於geometry/teleport提交；準備期間重驗來源／全員資格，offline／名單改變拒絕。全部world/positions核對成功才消耗效果並durable SUPERPOSITION,false、publish active、開replica後牆；false提交前失敗rollback、保存RETURNING,true pending，不留下半cohort。false已durable提交後若核對／publish失敗則安全RETURNING,false，不倒退補發效果。從已提交活動轉返還亦保存RETURNING,false，不還原入場快照也不刪除新藥效。active時source chamber空／藥效已耗不降級3。保持來源Controller的具體chunk session tickets，radius2且對稱remove。
- [x] **Step 4: GREEN跨world。** 真server/world身份、relative pose／inventory不變、NBT hidden-effect rollback、partial-move failure不解鎖，缺buff無人不創造新world；core JUnit+GT全過。
- [x] **Step 5: 自評／提交。** `feat: start shared superposition sessions from powered chambers`；實作`bdce032`完整162 declared JUnit（161實跑／1平台略過）＋90 GT；R1`941cd37` covering24 JUnit＋95 GT、成品與四world啟停已核對，Controller即時身分／錯source不rollback兩項scoped review結案。尚待Task4完整斷電／登入恢復，不宣告整個M2完成。

### Task 4: 斷電、離線重登、重啟恢復及頁面移動

**Files:**
- Create: `src/main/java/dev/quantumchamber/mixin/ServerPlayNetworkHandlerRecoveryMixin.java`
- Modify: `src/main/resources/quantumchamber.mixins.json`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/ConnectedGameTestPlayer.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionTransferFaultMixin.java`（玩家 move 多載 descriptor 與核准的 remap LOW 觀察，保留原 observer）
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorRepositionService.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/M2PersistenceProbe.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/CheckpointWindowFaultMixin.java`（W1 成功觀察／W2 A 真確認後 B 窄拒絕）
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionJournalWindowFaultMixin.java`（W1 窗口與核准的斷線 IO 拒絕）
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/PlayerNativeSaveWindowFaultMixin.java`（stale save 真完整 readback 拒絕）
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/transfer/SessionTransferService.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryManagerTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`

**Interfaces:**
- Consumes: durable recovery records、page mapping leases與gateway.returnToOrigin。
- Consumes: `CorridorPageManager.returnEntityPins(UUID)`／`affectedEntityOwners(PreparedMappings)`／`currentEntityOwners(UUID)` 唯讀 immutable 結果；same-server-thread、held token／epoch／durable frozen source／leases 與全部 footprint FULL／entityLoaded／ticking 查核不放寬，未 ready 不當沒有 entities；retained 映射不搬，不新增票或 tick。
- Consumes: Recovery constructor 的 `Predicate<SessionRecoveryRecord> sourceAuthority`；live runtime 驗原始 Controller instance，重啟依 immutable origin 核對真 world／Controller UUID／ORIGIN／facing／healthy registry；身分驗證獨立於 power／enabled／buff，錯來源維持 pending／保護／leases。
- Produces: `ServerPlayNetworkHandlerRecoveryMixin` 只在原生 forceMainThread 之後，限制同 server／player pending recovery 的 onPlayerMove／onVehicleMove／onPlayerInteractBlock；不限制登入／teleport confirm／disconnect，不新增 packet／第二 tick。測試 helper 的 DISCONNECT／重登證據使用真 channel close／Fabric event／正常 playerdata load，不拿 remove 或 manifest 回填代替。
- Consumes: Task1 PlayerRecoveryCheckpoint／PlayerCheckpointStore、flushedRecords；return保留原restoreEntryEffectOnReturn，不用RETURNING重算。確認真來源world/interior後才true還原／false不觸碰當下效果，再stamp單一session/policy marker；同session同policy marker跳過效果修改，錯policy／壞marker保守拒絕。saveAndVerify成功才flush returned=true，任一失敗保持保護／lease；登入queue前阻止正常移動／門操作，不能在JOIN callback直接teleport。
- Produces: instance方法 `public void SessionRecoveryManager.onJoin(UUID,MinecraftServer)` queue、`public void onDisconnect(UUID,MinecraftServer)`、`public void tick(MinecraftServer)`、`public boolean returnComplete(UUID)`；manager/gateway只在全cohort／資料確認後complete。Netty DISCONNECT捕捉UUID/server後序列化，JOIN只queue下一tick，不用server.execute誤當下一tick。

- Consumes: Task2 `releaseComplete(UUID)`／`acknowledgeRelease(UUID)`；同程序全 cohort checked returned 與已知最後 lease 收尾確認後才結束 session／回 complete，再移除上層 runtime 並 acknowledge。原艙 OFF／解除保護仍由來源 coordinator 最後確認；未知 SID／in-memory 缺失不是完成。重啟只恢復 healthy journal 尚存在的 records，不重建已 durable 移除的舊 session。
- Produces: instance方法 `public void CorridorRepositionService.tick(MinecraftServer)` 管理邏輯位置、items/projectiles tag／pin、近群split/merge預備／commit／retire；相關entity每次只有一個authority mapping。
- Consumes: `CorridorRepositionService(MinecraftServer, Predicate<SessionRecoveryRecord> managedActiveSession)` 的最小 constructor 接點，由 Manager private map 核對同 server／SID 與 runtime／record SUPERPOSITION；只管理本 gateway 活動 session，不新 repository／tick／ticket，來源 guard 與完整 cohort 不變。
- Produces: M2PersistenceProbe 此任務只先建 checkpoint W1/W2 的 default-off條件phase與必要testmod-only fault mixin（各server/world/session/player限定，files/API列report），後續Task5擴充正常phase；release無probe/fault。不得以production test-only setter代替原生邊界。
- Consumes: 三個窗口 fault 的 pinned 完整 descriptor 與 native hit／formal playerdata／journal witnesses；default-off 且 server-thread／world／SID／profile／canonical owned runRoot／nonce／case 限定。HALT 只在外部核對 ready witness 的 PID／nonce 與所持 child Process、canonical fresh root 全一致後中止該唯一 JVM，不按 process name／群組猜目標，不走 STOPPING 或回填 manifest NBT；不承諾整機斷電一致性。
- Consumes: W2 精確中止窗口可在既有 B checkpoint HEAD 使用 owned-only 單次有界 30 秒 barrier；A 真確認、B 尚未 native save、全 cohort 真 fixed／已耗藥、checked ARMING,true 才產生 formal witness 並等待外部精確中止。此為 checkpoint 受控阻擋尚未返回，不稱 IOException 已拋出後仍 ARMING；deadline 到期的 run 拋 IOException 並標非 crash 證據，不算 W2 gate。
- Produces: 同一既有 State.flush HEAD 的 default-off DisconnectJournalFault，限定明確安裝的 server／source+fixed world／SID／完整 cohort，flushed SUPERPOSITION,false 而 current 轉 RETURNING 才拒絕；獨立 batchId、不與其他 journal cases 並行。native removal 不被 IO 例外中斷、唯一 tick 重試、staged RETURNING 仍 pending 限制移動、故障撤除後返還／receipt／OFF，以及另存 STOPPED sentinel；不新 target／getter／tick，不把共享 flush 說成單 SID 並行拒絕。
- Produces: 同一既有玩家 move RETURN 的 default-off RemapPowerLoss，只在 own SID／完整 cohort 的 actual move 成功且 pose 位於新 prepared lease 非 current、flushed SUPERPOSITION,false 時，第一次真外部 lever LOW；記實際 UUID／pose／tick，驗同 tick RETURNING 保護與 partial batch 後完整返還／checkpoint／receipt／OFF，不新 target／API，不改原 observer 或成功判定。
- Test scheduling: 五個既有 trusted heavy geometry case 各自獨立 batchId，保留原方法名稱、tickLimit100000、helper10秒／90000tick、全部FULL／loaded／ticking與多人／多SID／AABB／shared budget／cap斷言；只排除defaultBatch同時五份長廊載入的fixture資源干擾，不宣稱五Chambers並行或硬體效能通過，其餘測例不全套序列化。
- Produces: `public boolean SessionTransferService.move(Entity,ServerWorld,Vec3d,Vec3d,float,float)` overload，處理items/projectiles的native傳送／同world重定位；核對真target Entity UUID/world/pose，可能新Entity物件需以UUID取得，不把舊removed物件當成功。

- [x] **Step 1: 原生來源斷電返還RED。** 在來源world移除電源，先確認RETURNING仍保護，再確認全部player原world/interior、session結束、原艙才可變更。不能只helper return true。

```java
sourceWorld.setBlockState(sourceController.getPos().up(), Blocks.AIR.getDefaultState(), 3);
context.assertTrue(player.getServerWorld() == sourceWorld, "斷電回到同一來源world實例");
context.assertTrue(ChamberOccupantService.contains(ChamberGeometry.interiorBox(sourceFrame), player.getBoundingBox()),
        "全身回到有限原艙內");
context.assertTrue(ChamberProtectionService.get().mayMutate(sourceWorld, sourceController.getPos()),
        "返還確認後原艙才解除保護");
```

上述斷言於返還完成的waitAndRun／runAtTick執行；來源斷電時另以returnPending驗證仍保護，不把同一tick同步helper當真恢復事件。
- [x] **Step 2: 失敗與離線queue RED。** 真close EmbeddedChannel／ClientConnection觸發DISCONNECT，不以PlayerManager.remove冒充；重登callback尚未完成不得跨world，下一tick才恢復。錯source身分／missingworld／journal不可寫保留保護與slots。
- [x] **Step 3: 完整返還／恢復。** pending每人returned flags持久化後再清session；offline不當已returned。重啟全部舊session標RETURNING，恢復前禁止正常移動／門操作；已在原艙者可冪等確認，不重複消耗buff。STOPPING保存／釋放tickets，STOPPED清runtime；cleanup前先返還有價items、不unsafe清玩家腳下。高位重供電遇pending先完成舊返還。
- [x] **Step 4: GREEN頁面與群體。** 正負向長走／回頭、兩人跨96seam相近／遠分裂重聚、items／projectiles跨seam、移動途中斷電、source四朝向，底板與collision保持。資源不足不拆舊映射或遺失物品，記錄錯誤並安全返還。
- [x] **Step 4b: crash checkpoint GREEN。** W1 ARMING rollback 的RETURNING,true/returned=false，entry QS2400/amp1/hidden600/amp0已restore並持久marker；player checkpoint成功但returned flush拒絕，40ticks後再保存並中止owned fresh測試JVM；下JVM從真player.dat marker載入不重置回2400，重試成功才解鎖。committed分支新QS900/amp2同窗口保持新劑量而非entry snapshot。W2 journal ARMING,true、已真移動並消耗、A player checkpoint已保存但B checkpoint或SUPERPOSITION flush失敗，中止後全員回原艙並還原各entry1200/0與1800/1，第二次重啟不重套。另測native save正常返回但正式檔stale必拒絕。不得用STOPPING自動rollback修補窗口後宣稱crash proof；只中止本次建立且canonical runDir證實的測試JVM，不碰其他process。
- [x] **Step 5: 自評／提交。** `feat: recover corridor participants safely on power loss and reconnect`；逐項列真正event coverage與純seam coverage，未測visual保持待驗。

Task4 implementation `e4509fc` 已獨立 spec／quality Approved（Critical0／Important0，兩項診斷／annotation warning Minor 留待 final）。最後非快取 clean build＋105 GameTests 綠；167 declared JVM tests＝166 實跑／1 本機 Windows 平台略過。最後 classes 的 W1 true／keep／W2 三個受控中止恢復鏈與 stale save 鏈綠，正式 NBT 分別保留2360／hidden560、860、W2 entry1200／1800與相同 marker，不補發／重套。main-only 無 testmod 的四 world Done→stop→save／exit0、JAR 邊界已驗；正常 Task5 phase／有價 entities 跨 JVM／部分票例外、遠端 CI 與人工 GPU／玩法未被此結果取代。未 push／合併 main。

### Task 5: 真live restart、production smoke、玩法指引與final review

**Files:**
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`（僅未完成attach的stopping／detach安全清理）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2PersistenceProbe.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2LeaseBootstrapProbe.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionTransferFaultMixin.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/M2TicketAcquireFaultMixin.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/ChunkTicketManagerTestAccessor.java`（必要時唯讀 native ticketsByPosition）
- Create: `src/testmod/java/dev/quantumchamber/gametest/mixin/ChunkTicketArgumentTestAccessor.java`（必要時唯讀 argument SID）
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`
- Modify: `src/testmod/resources/fabric.mod.json`
- Create: `docs/implementation-notes/m2-corridor.md`
- Modify: `docs/implementation-notes/m1-player-build-verification.md`
- Modify: `README.md`
- Modify: `docs/plans/2026-09-17-m2-powered-corridor.md`

**Interfaces:**
- Produces: property quantumchamber.m2.phase、fresh run/m2-persistence／run/m2-production-smoke；default不啟用probe，release不含testmod。
- Native ticket fault：default-off owned partial-acquire bootstrap case，目標第一張真native加票後第二張HEAD拒絕；唯讀真native membership證明目標對稱remove／零殘票、另一sentinel SID保留且無誤remove。合法trusted journal原hash不變／tick0／external verifier，finally只清自己的sentinel，不以observer計數或六個加票前拒啟案例替代。
- Attach failure cleanup：未attach Manager 的 STOPPING no-op；matching partial owner的票清理與nullable recovery detach保持thread／identity、不依journal健康而中斷native shutdown。正常gateway／tick guard不放寬，不建新權威或改journal；partial真fault仍須STOPPED／tick0／hash verifier成功，最後classes重啟鏈與正常四world關閉再驗。
- Normal active-save fixture：同server／canonical nonce root／SID／frozen A+B／原world與fixedworld，A在線、B真DC offline且flushed RETURNING,false後，只在既有transfer target拒A與真五鑽石item返還，以穩定正常stop前保存窗口。native入場與消耗／DISCONNECT維持；new JVM故障關閉、正常player與entitysave load，真UUID/count＋pending/protection與安全返還必要，不回填manifestNBT、不fake成功或改雙DC替代。
- Native reload observer：active-resume-one只在同一Entity move HEAD唯讀記真fixed item UUID/count、held lease FULL/loaded/ticking與pin，因world tick載入後同tickRecovery END可能已返還。不得cancel／寫NBT／改結果，不把observer當fault hit；上一phase正式entityregion與move後真source entity／bbox、完整cohort／durable completion仍必要。
- Consumes: Tasks1–4的真固定world／nativepowered activation／journal與完整關閉流程。
- Dedicated harness只用fresh case子目錄、loopback server-ip=127.0.0.1／server-port=0；各phase上一JVM完全exit後才啟動下一個，property未知phase立即明確失敗。每JVM PID＋startup nonce、same canonical save root、source/chamber/session/player UUID、authority、effect/marker、flushed journal與停止後playerdata readback保留。探針只印一次 M2_PHASE_READY_FOR_STOP，harness收到才console stop並確認四world save／exit0；probe不自行無條件stop。

| fresh case | phase依序（各為不同JVM） | 必要斷言與停止條件 |
| --- | --- | --- |
| active-pending | active-save → active-resume-one → active-resume-last | 真native入場cohort A/B＋消耗；B真DISCONNECT仍pending。重啟只A真JOIN下一tick回來源並returned durable，B offline與保護保留；再次重啟B真JOIN完成，無舊active重建／snapshot補發，先全返還後解鎖 |
| return-disconnect | return-disconnect-save → return-disconnect-resume | 原生斷電→RETURNING/protected→B在未返回時真close channel→A先回Bpending。重登下一tickB回真原world/bbox→checkpoint/returned→session end→OFF，不能用先disconnect後斷電的case替代 |
| arming-rollback | arming-save → arming-resume-one → arming-resume-last | native資格建立durableARMING,true，自然geometry跨tick窗口或窄testmod故障留pending；A還原完整hidden NBT後returned、B offline，RETURNING再次重啟仍true；B完成、A不重套duration。不得事後構造假ARMING當native啟動 |
| origin-missing | origin-missing-save → origin-missing-resume | 真native session後只在本fresh fixture authorized移除source C注入故障；兩JVM皆不得去spawn/床/別world，pending與lease/protection保留，正常stop只證明安全拒絕，不假稱返還成功 |
| checkpoint-w1/w2 | owned受控中止 → recovery-resume → idempotency-resume | Task4兩個崩潰窗口真checkpoint/journal readback；中止前不走STOPPING修補，不中止共享或使用者JVM；下JVM用正常load／JOIN，不回填manifest位置/效果 |

- 窄testmod故障可用scoped mixin包覆真move／checkpoint／checked journal flush，精確限定server/world/session/player/case，finally或STOPPED清；無fakebackend、無production test-only setter、不直接塞record假native coverage。每個fault/trusted setup與native evidence分開標示。JOIN callback早於normal connect完成，callback只觀察入列不移動，下一server tick才返還；PlayerManager.remove不是DISCONNECT證據，必有真channel close→Fabric事件→server-thread更新。

- [x] **Step 1: 新testworld的不同Java process證明session中斷／pending-return重啟。** 保存UUID／sourceworld／原位置／實際fixed-world participant與journal狀態，正常stop與重啟／重登確認原world返還、不重建舊session、不生成Universe。若使用trusted setup須明確區分native activation證據，不能用探針直接構造紀錄假裝live玩家。
- [x] **Step 2: production-only dedicated。** 無testmod／client mods，Done→console stop→四world全部save→exit0；無client-loading error／Dynamic Universe code。
- [x] **Step 3: 最後非快取clean build／GT／XML／JAR與common/client檢查。** 原版baseline、light依賴解析與照明JAR hash分開；不啟動或覆寫使用者world。
- [x] **Step 4: 更新單人不指令指引與人工gate。** 外部拉桿→進艙→關門→已有buff或喝藥→真走廊；外部預設計時斷電電路→回同一普通盒子→可Creative拆。寫start-client.bat light、存檔備份人工複製、主副手torch、32chunk遠望／回頭、shader／resource reload、近玩家群組 seam清單；未實測必須標待驗。
- [ ] **Step 5: whole-feature獨立final review與交付。** 包含main merge-base至HEAD完整diff、所有deferred／rulings、真測試證據；先fix blockers再交付本機分支。人工gate未關不得合併main，不假稱M3已完成。

Task5原版checkpoint96c3514已依使用者特定要求推feature供審查，未合併main；獨立spec/quality Approved、0Critical/0Important/3Minor。原本機167declared/166pass/1OSskip、105GT與29JVM證據保留，只證明舊方向／效果契約。遠端CI35313938269 Ubuntu build與非Windows契約成功、Windows167test/7fail/1skip／GT未開始，原因邊界為ancestor HANDLEpath mismatch、原始名稱差異尚缺證據；Task9須實際結案。歷史Minor診斷／六annotationwarnings／預期faultstack／文件時序不丟棄，whole-feature與人工留最後，不預先勾Step5。

## 2026-09-18 修訂執行與gate

Task6–10接續同一plan owner，單一implementation worker與Gradle/native序列；CI只允許唯讀診斷並行。共用checkpoint或fixture修改序列化，不能同時編譯M2與CI source。自動gate與獨立taskreview關閉後執行M3可行性探查；M3 production backend不得先跳過Minecraft1.21技術研究，不混M4候選／M5塌縮passage／M6完整family。人工8項留收尾，main仍需人工與wholebranch review。

### Task 6: schema2 semantics、strict legacy與凍結權威

**Files:**
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorRepositionService.java`（僅RETURNING copy保留durable.semantics）
- Create: `src/main/java/dev/quantumchamber/persistence/SessionSemantics.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`（record複製carry semantics／durable freeze）
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`（record複製carry semantics）
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`（record複製carry semantics，native creation仍legacy直到Task8）
- Test: `src/test/java/dev/quantumchamber/persistence/SessionSemanticsTest.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStateTest.java`

**Interfaces:**
- Produces: canonical `SessionRecoveryRecord(...,SessionState state,boolean restoreEntryEffectOnReturn,SessionSemantics semantics)`；原7參數overload只明確LEGACY_FORWARD_CONSUMED相容，不能憑缺參數選new。record accessor `semantics()`不可變。
- Produces: `SessionSemantics { LEGACY_FORWARD_CONSUMED, LATERAL_BUFF_MAINTAINED }`，`public void validateEffectPolicy(SessionState,boolean)`、`public Direction corridorFacing(Direction sourceFacing)`、`public Direction sourceFacing(Direction corridorFacing)`；水平驗證，legacy同朝向，lateral corridorFacing=sourceCW／inverseCCW。
- Produces: schema1按完整舊codec合法性解析後memory legacy；schema2必填string SessionSemantics，未知／缺／錯型別拒絕。writer schema2只正常checked進度寫，不讀取先改檔、不旋轉舊bounds。Record package-private `fromNbt(NbtCompound,int envelopeSchema)`、`toNbt()`明確metadata。
- Produces: `public static boolean SessionRecoveryRecord.sameAuthority(SessionRecoveryRecord,SessionRecoveryRecord)` 檢查SID/chamber/origin/semantics與完整同UUID來源snapshots；returned/state/leases不是來源identity。State.put同時current/flushed與PageManager破壞性清理前共用，不允remove-replay換mode／origin／cohort。Space持不可變semantics，bootstrap取strict record、原freshreservation明確legacy，不從目前Buff猜mode；Task7可消費這份同一權威。
- Consumes: 原checked journal、原PlayerRecoveryCheckpoint schema1／KEEP_CURRENT/RESTORE_ENTRY、不改PStore/native verifier。fresh native保持原legacy行為，此task不創建左右session或改幾何。

- [x] **Step 1: 先寫enum policy／NBT／authority負向測試。** 刪掉lateral ARMING false允許分支會令此測試失敗：

```java
assertDoesNotThrow(() -> SessionSemantics.LATERAL_BUFF_MAINTAINED
        .validateEffectPolicy(SessionState.ARMING, false));
assertThrows(IllegalArgumentException.class, () -> SessionSemantics.LEGACY_FORWARD_CONSUMED
        .validateEffectPolicy(SessionState.ARMING, false));
assertThrows(IllegalArgumentException.class, () -> SessionSemantics.LATERAL_BUFF_MAINTAINED
        .validateEffectPolicy(SessionState.RETURNING, true));
```

增加schema1缺policy／ARMING,false拒絕；schema2lateral三state=false roundtrip、未知mode／缺mode拒絕；同SIDmode/origin/chamber/sourceNBT改寫不改records/dirty/flushed，returned進度／排序可合法更新。fixture完整source/leases來自existing test已查helper，不用production builder算expected。
- [x] **Step 2: 聚焦跑RED。** `./gradlew.bat --offline --console=plain test --tests '*SessionSemanticsTest' --tests '*SessionRecoveryStateTest'`；新API初compile缺失是前置，不當behaviorRED。加最小API殼後讓新的合法lateral codec／freeze mutation測例真失敗，保存命令/XML。
- [x] **Step 3: 最小policy與codec。** enum policy核心：

```java
boolean invalid = this == LATERAL_BUFF_MAINTAINED
        ? restoreEntryEffectOnReturn
        : state == SessionState.ARMING && !restoreEntryEffectOnReturn
                || state == SessionState.SUPERPOSITION && restoreEntryEffectOnReturn;
if (invalid) throw new IllegalArgumentException("狀態與效果恢復政策不一致");
```

schema2嚴格decode、完整defensive copies；每個existing new SessionRecoveryRecord copy傳原semantics，failure不消失mode；freeze checks在mutation前。legacy原缺欄位拒絕保留，load不落盤、不建新world。
- [x] **Step 4: GREEN／回歸。** 聚焦policy/state/XML綠後單序列非快取clean build＋fresh nonce全GT一次，必要Windows12cases零skip、本機合法NonWindows skip分開、JAR testmod/commonclient檢查。記schema2metadata不代表新玩法已實作；PStore平台CI仍待Task9。
- [x] **Step 5: 精確commit／self-review。** `feat: 新增 M2 session semantics 與 strict legacy 相容`；只本task files和必要publicplan狀態，report RED/GREEN、全constructor copy/原mode、immutable拒絕與未驗列表。獨立review通過後才Task7。

2026-09-18 Task6 證據：實作 `dfc650f`、固定範圍 `3ed2ecb..dfc650f` 獨立規格✅／品質Approved、Critical0／Important0。正式聚焦RED22／8fail→GREEN22；最後非快取clean fullgate177 JUnit declared／176通過／1合法Windows平台略過（NonWindows測試），必要Windows12零skip；fresh GameTest105／0failure/error/skip。雙JAR全部testmod交集與污染0、common client refs0；新nonce主程式專用伺服器Done→console stop→四世界save，Java/wrapper0。schema2 metadata與strict legacy已完成，native factory仍legacy；左右／Buff維持由Task7–8接續，遠端CI由Task9、跨JVM由Task10驗證。報告OS annotation名稱已按source修正；既有warnings／預期fault留final triage，人工與整分支final仍待驗，不提前合併main。

### Task 7: 左右基底、來源座標分離與入口覆寫

**Files:**
- Create: `src/main/java/dev/quantumchamber/chamber/ChamberSpaceCoordinates.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorBasis.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorGeometry.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/SessionEntranceAllocator.java`
- Modify: `src/main/java/dev/quantumchamber/transfer/ChamberReturnPlacement.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`（明確entry basis，creation仍legacy）
- Test: `src/test/java/dev/quantumchamber/corridor/CorridorBasisTest.java`
- Test: `src/test/java/dev/quantumchamber/chamber/ChamberSpaceCoordinatesTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`

**Interfaces:**
- Consumes: Task6 semantics/mode freeze；Mode不可由callerbool猜，Space按durable record。
- Produces: source helper `public static BlockPos block(ChamberFrame,int,int,int)`、`position(ChamberFrame,double,double,double)`／`vector(ChamberFrame,Vec3d)`／`localPosition(ChamberFrame,Vec3d)`／`localVector(ChamberFrame,Vec3d)`，extract既有source affine語意，finite/horizontal驗證；不擴有限ChamberGeometry公開0..6 contract。
- Produces: `public record CorridorBasis(Direction sourceFacing,SessionSemantics semantics)`，`Direction corridorFacing()`；`Vec3d toCorridorPosition(Vec3d sourceLocal)`／`toSourcePosition(Vec3d corridorLocal)`／`toCorridorVector(Vec3d sourceLocalVector)`／`toSourceVector(Vec3d corridorLocalVector)`，`float toCorridorYaw(float sourceLocalYaw)`／`toSourceYaw(float corridorLocalYaw)`，finite/horizontal。
- Geometry helper保留generic corridor frame算法，但Mode-aware入口 `static ChamberFrame entrance(MappingView,SessionSemantics)`；legacy用原C mapping、new sourceC在corridor block(lateral6,y6,long3-anchor)、來源facing=semantics.sourceFacing(view.outwardFacing())。
- MappingView.outwardFacing表示actualcorridorFacing；Space.origin仍sourceFacing。reserveInitial additive `(...Direction sourceFacing,Set<Long>,SessionSemantics)`，原4參數明確legacy相容；typedtoken/epoch/durableMode完全匹配，bounds窄軸按actualcorridorFacing。舊record只return-only清舊bounds，不rotate／重新build。
- Initial prepare依frozen Space.semantics驗policy：legacy ARMING,true／lateral ARMING,false；保留durable同一權威、完整cohort與typedepoch等guards，不刪policy條件。Task7 trusted lateral geometry暫按既有效果消耗gate無Buff，Task8切新Buff guard時同步更新fixture，兩者都不冒稱真飲用。
- SessionEntranceAllocator `cell(...frontOpen,connectionOpen,SessionSemantics)`，legacy後孔、new source localx0/6 y/z1..5AIR；source正門z0/Controllerfacing保持，new不開後牆。base/overlay ownership互斥，mode持久carry。

- [x] **Step 1: 手算幾何RED。** 不使用helper算expected；此new旋轉保留cell/point boundary：

```java
var basis = new CorridorBasis(Direction.NORTH, SessionSemantics.LATERAL_BUFF_MAINTAINED);
assertEquals(Direction.EAST, basis.corridorFacing());
assertEquals(new Vec3d(1.5, 1, 3.5), basis.toCorridorPosition(new Vec3d(3.5, 1, 5.5)));
assertEquals(new Vec3d(-2, 3, 1), basis.toCorridorVector(new Vec3d(1, 3, 2)));
```

新增四朝向literal worldfeet/sourcefloor表、continuous正負roundtrip、速度/yaw/pitch/bbox、new sourceC/正門與兩側connection、legacy bounds/return位置不變。Mode nativefactory尚未new，只trustedgeometry案例明確標示。
- [x] **Step 2: 跑聚焦RED／native fixture。** `test --tests '*CorridorBasisTest' --tests '*ChamberSpaceCoordinatesTest' --tests '*ChamberReturnPlacementTest'`，fresh nonceGT新lateral controllerfacing/兩側portal/cap邊界失敗；stubcompile不是行為RED。
- [x] **Step 3: source與corridor正交helper。** newbasis核心：

```java
return semantics == SessionSemantics.LATERAL_BUFF_MAINTAINED
        ? new Vec3d(7 - sourceLocal.z, sourceLocal.y, sourceLocal.x) : sourceLocal;
```

inverseposition=(long,y,7-lateral)，vector=(-z,y,x)，inverse=(long,y,-lateral)；yaw由(-sin,0,cos)經vector轉換再atan2(-x,z)，MathHelper.wrapDegrees，pitch不改。SourceReturnPlacement與Recovery普通entity中心必用sourcehelper；entry從sourcehelper再basis轉到typedphysical，不共享可變globalmode。
- [x] **Step 4: mode-aware geometry GREEN。** 新controller/source方向、原門25交易、localx兩側connection；全樣式96seam正負走／0與16 split／9 merge、items/projectile、入口切邊{7}與{6}rebuild；fullbudget/caps/ticket門檻不放寬。最後非快取full/GT一次＋JAR／main-only，不以trustedgeometry當真喝藥native。
- [x] **Step 5: commit／review。** `feat: 分離原艙座標與左右走廊基底`，精確本task與report，明列legacy return-only與basisproducer/nativecreation仍legacy，獨立gate後Task8。

2026-09-18 Task7 證據：`747baac`，固定範圍 `7ff8826..747baac` 獨立spec✅／qualityApproved、Critical0／Important0。聚焦RED8／4fail→GREEN8；native prepare與mapping方向RED分開保存，capacity連鎖失敗不冒稱幾何RED。最後非快取clean fullgate182 JUnit declared／181通過／1合法OSskip、必要Windows12零skip；fresh GameTest109／0failure/error/skip，四朝向新增lateral幾何案例覆蓋正負seam／split／merge／入口切邊與items/arrow。雙JAR污染與全部testmod交集0、common client refs0；main-only新nonceDone→console stop→四世界save、Java/wrapper0。factory/checkInitialCohort仍legacy，trusted lateral fixture不是Buff維持證據；Task8更新fixture並實作真飲用。native非零velocity覆蓋Minor帶入Task8，既有warnings留finaltriage；Task9/10、GPU／人工／wholebranch仍待，不提前mainmerge。

### Task 8: 真飲用Buff維持、共同到期返還與HIGH再進

**Files:**
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Conditional modify（僅需求缺口）: `src/main/java/dev/quantumchamber/superposition/SuperpositionSession.java`
- Modify: `src/main/java/dev/quantumchamber/transfer/QuantumEffectTransaction.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorRepositionService.java`
- Conditional modify（僅握手缺口）: `src/main/java/dev/quantumchamber/chamber/ChamberPowerCoordinator.java`（只既有HIGHRETURNING握手必要接點，不第二authority）
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/SessionTransferFault.java`
- Conditional modify（僅既有hook不足）: `src/testmod/java/dev/quantumchamber/gametest/mixin/SessionTransferFaultMixin.java`（既有exacttarget scoped observation；新target先root核准）
- Test: `src/test/java/dev/quantumchamber/chamber/ChamberPowerCoordinatorTest.java`
- Conditional追加／既有regression重驗: `src/test/java/dev/quantumchamber/persistence/SessionSemanticsTest.java`

**Interfaces:**
- Consumes: Task6new ARMINGfalse／Task7lateralbasis/typed initial／原source identity、cohort完整snapshot及checkedreceipt。
- Produces: fresh native start只LATERAL_BUFF_MAINTAINED，reserve同mode→checkedARMINGfalse→prepare→全nativecohortmove→retain/verifyBuff→playercheckpoint→checkedSUPERfalse→publish；legacy只restore/read/return兼容。
- Produces: `public void QuantumEffectTransaction.verifyCurrent(MinecraftServer)`只validate全員仍alive/online同server且QuantumState，沒有remove/add或duration改寫；既有legacy commit/restore保留給明確legacy。snapshotimmutable。
- Produces: `public static boolean SessionRecoveryManager.blocks(UUID,MinecraftServer)`仍同thread current+flushedRETURNING failclosed。Runtime Buff缺／失效→checkedReturningfalse，全組固定；alreadyRETURNING不因redrink撤回。
- Produces: `public boolean CorridorPageManager.activeCohortHasBuff(UUID)`只sameauthority/durable SUPER/newmode/完整UUID nativeplayerworld/query真Buff；legacyconsumer不用此getter猜權威。不新增tick/tickets，Manager與Reposition在begin/move/commit前重驗，partial失效不publish半群。
- Pose rollback與effectpolicy分開：durable ARMING或未提交先原pose rollback（same originalC必要），新效果KEEP_CURRENT；durableSUPER後只safeReturn保留當下效果。不能把restorefalse當「已提交」旗標。

- [x] **Step 1: 真飲用native RED。** vanilla QuantumState potion.finishUsing／正常玩家inventory，不直接addBuff冒充drink；新完整入場後斷言：

```java
context.assertTrue(player.getServerWorld() == server.getWorld(SuperpositionWorld.KEY), "真固定world入場");
context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE), "入場保留QuantumState");
context.assertTrue(player.getStatusEffect(ModEffects.QUANTUM_STATE).getDuration() > 0, "原生remaining持續");
```

保存完整NBT/hidden與elapsednativeTicks對照，不單純>0當未重置。單人／兩人任一shortduration自然expire；vanilla MilkBucketItem.finishUsing移除真Buff；others效果不退款；HIGH source仍protected/POWERED、journal/receipt清後IDLE，再真喝／sealed新SID入場；sourceLOW最後OFF才能Creative拆。

原生custom PotionContents的長／短效與hidden fixture允許並明確標示；不得在正式飲用後直接addStatusEffect延長。正式ModPotions3600仍需至少一個完整飲用→入場→剩餘duration未重置案例，可用已完成custom長效／milk全組返還／checked清理的同來源warm cycle，在HIGH正常再喝正式藥水進新SID；不降server tick／改藥水數值／強載／fake進度。
- [x] **Step 2: RED矩陣。** 缺一Buff／zero／spectator／26人／變來源C／UUID／offline保持原guard，prepare期間expire、partialactualmove/remap後失Buff、新劑hiddenEffect接續不誤判expire、RETURNING中redrink不撤銷。原移動callbackscopedfault與所有rawsentinel分開標native/trusted。
- [x] **Step 3: runtime核心分流。** creation顯式newmode/false，verifyCurrent不consume；active完整cohort任一!hasStatusEffect先Returning，再reposition/recovery。checkInitialCohort根據recordmode要求Buff仍有或legacy已耗，不blanket刪effect條件。HIGH ack原gateway→registryPOWERED重新readiness，LOW已完成→OFF。
- [x] **Step 4: GREEN／全套。** 聚焦newnative案例綠、mode/JUnit純tests，原known-world/UUID/floor/leases/budget保守門檻；legacyfixtures可繼續透過明確Mode構造，但native creation期待改new且不得以forcedlegacyfake真飲用。最後唯一full/GT／class/JAR／main-onlygate一次，WindowsCI未綠不放行M3。
- [x] **Step 5: 精確commit／review。** `feat: 以 QuantumState 維持共享左右走廊並安全返還`；報Buff nativeNBT/tick、sourceHIGHPowered/LowOFF順序／SID再進，HUD/GPU仍人工；獨立gate後Task9。

2026-09-18 Task8 證據：`3c695e4`固定範圍`a28c258..3c695e4`獨立spec✅／qualityApproved、0Critical／0Important。最後單次非快取clean fullgate183declared JUnit／182通過／1合法NonWindows OSskip，必要Windows12與fresh GT116零failure/error/skip。正式3600 inventory/finishUsing→入場完整NBT保留、7+1顯式原生effect ticks為3592；milk/單雙人自然expiry/hidden接續→全組KEEP_CURRENT返還；HIGH清理ack後新SID、LOW完成OFF後Creative拆除；partial entry pose/refund分離、partial remap milk真hit維持epoch1，不半publish。四向非零worldvelocity字面斷言補Task7Minor。雙JAR/testmod交集/COMMON-client污染0、fresh main-only四worldsave/Java-wrapper0。normal NetworkIo倒數/HUD與GPU人工仍待；初RED XML覆蓋與Gradle截斷stdout誠實記錄，非重建歷史。正常expiry記WARN噪音Minor留finaltriage；Task9CI／Task10所有模式跨JVM／人工／wholebranch尚未放行，M3等待必要gate，不提前mainmerge。

### Task 9: WindowsCI實值診斷與安全fixture／原生gate

**Files:**
- Modify: `src/test/java/dev/quantumchamber/persistence/WindowsPlayerCheckpointVerifierTest.java`
- Modify: `src/test/java/dev/quantumchamber/persistence/PlayerCheckpointStoreTest.java`
- Modify: `.github/workflows/build.yml`（只已證實平台fixture或診斷／證據上傳，不skip必要cases）
- Modify: `build.gradle`（僅真實查明tmp/runtime boundary時的taskfixture設定）
- Production `WindowsCheckpointNative`／verifier若需改，列精確API與root裁定先核准，不預先允放寬。

**Interfaces:**
- Consumes: root已讀run35313938269：7fail皆ancestor HANDLE name mismatch，NTFS已過，不是ReFS；unknown expected/actual／firstancestor不能當短名已證實。
- Produces: test-onlyTrackingNative openPath/HANDLE/finalPath、java.io.tmpdir/@TempDir、firstMismatch／attributes／NTFS證據；單一正向nativecase可重現。新診斷若CI才可見，featurepush/re-run屬外部write先依user特定授權處理，不自動mainmerge。
- 正向fixture在自有普通NTFS已解析longname位置；若本機native短名能力可用，額外alias拒絕case具實際兩個names與handle witness。縮短/長名是假說直到真值；不能FILE_NAME_OPENED／dropancestor／取消FileID/NBT/flush／skip or assumeCIcounter。

- [ ] **Step 1: 寫最小diagnostic characterization。** TrackingNative覆寫真method只捕捉與輸出，不fake返回：

```java
@Override Path finalPath(HANDLE handle) throws IOException {
    Path actual = super.finalPath(handle);
    System.err.println("CHECKPOINT_NAME handle=" + handle + " actual=" + actual);
    return actual;
}
```

openDirectory覆寫先super成功再storeexpected(handle/path)，故capture不解鎖／跟隨junction；所有HANDLE cleanup仍原Verifier。只有!expected.equals(actual)印first差異，source非test-onlysetter。
- [ ] **Step 2: 跑單一正向native。** `test --tests '*WindowsPlayerCheckpointVerifierTest.realNativeReadFlushAndFormalIdentitySucceedEvenWhenJavaFileKeyIsNull' --rerun-tasks`，保留rawexpected/actual/nativeFileID/volume及@TempDir，失敗原因與CIexactsignature核對。環境不能重現則下一stageownedCI diagnostic，不猜修復。
- [ ] **Step 3: 唯一已證實原因最小修補。** 若names證實是fixture alias，fixture使用自有ordinary NTFS `temp.toRealPath()`只在測試setup，並驗native canonicalnames一致、原alias拒絕保留；如果toRealPath仍未長名或涉及reparse，報NEEDS_CONTEXT不加第三種fallback。若production namespace解析契約需改先root正式Ruling及反例tests；未知仍failclosed。
- [ ] **Step 4: 原12必要cases零skip／XML與remote綠。** 本機native positive＋rename/junction/writer/identity/read/flush錯誤皆全實跑；最後noncachedfull105以上GT、Windowsremote對應code必要XML守門與Linux受控拒絕保留。CIred不能local綠冒稱remote綠。
- [ ] **Step 5: commit／review。** `fix: 在已證實原生名稱邊界下修復 Windows CI fixture`，若只是diagnostic無修復明示狀態，不聲稱gate完成；majorreview先fix，reportrawXML/CI網址與SHA。

### Task 10: 新M2持久化全gate、8項指引與M3交接

**Files:**
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2PersistenceProbe.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M2LeaseBootstrapProbe.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `src/testmod/resources/quantumchamber-test.mixins.json`（必要scoped同target，新增先root核准）
- Modify: `docs/implementation-notes/m2-corridor.md`
- Modify: `docs/implementation-notes/m1-player-build-verification.md`
- Modify: `docs/implementation-notes/2026-09-18-m2-revision-status.md`
- Modify: `README.md`
- Modify: `docs/plans/2026-09-17-m2-powered-corridor.md`

**Interfaces:**
- Consumes: Tasks6–9真newnative與legacy-return-only、新schema2freeze、HIGH/LOWreceipt／Buffcurrent語意、remoteCIgreen。
- Produces: default-offphase `quantumchamber.m2.phase`保持unknown fail，ownednonce/canonical PID/startupnonce證明新schema2nativeactive/ARMING/returning跨不同JVM正常load；純schema1完整trustedlegacyfixture（CREATE_NEW）與true/false marker恢復讀回分開，不冒充newnativeentry。
- caseGroup：newactive-save→resumeone→resumelast；newarming interrupted→resumeone→last（KEEP_CURRENT不退款）；returnwhilemilk/expiry→offlineB normalJOIN；neworiginmissing safe refusal；legacy ARMINGtrue、SUPERfalse、RETURNtrue/false各formalboundssave/load→2ndrestart idempotent。manifest只UUID，不回填NBT/座標/物品。

- [ ] **Step 1: phase與effect policy RED。** 新native源期待retainBuff／newsemantics，oldphase期望consumed的失敗是契約更新不是regression濾掉；legacytrusted導入明確打印來源codec與unmodifiedbounds。必要case斷言例如：

```java
context.assertTrue(record.semantics() == SessionSemantics.LATERAL_BUFF_MAINTAINED, "新native模式");
context.assertTrue(!record.restoreEntryEffectOnReturn(), "新session不退款藥效");
context.assertTrue(record.participants().size() == 2, "離線者不丟名單");
```

- [ ] **Step 2: 不同JVM與精確checkpoint窗口。** 正常stop後read真正player.dat／compressedjournal／source/fixedentityregion、sameUUID五鑽石、newmodebuff/hidden自然tick無reset。W1新KEEP_CURRENT checkpoint-before-returnedflush、W2新ARMINGfalse部分保存→取消全組，oldRESTORElegacyfixture另驗；HALT仍原heldchild/nonce/root/barrier規範，不能用STOPPING修補當crash。
- [ ] **Step 3: 最後全套與產物。** 唯一final --rerun-tasks cleanbuild/freshGT/XML／skip理由、nativePipeline與schema1/2rawreadback／主程式與testmodclassfingerprints、release/sources污染/class/source交集0/mainclient0、officiallightSHA與main-only四worldDone-stop-save。任何最後改code重驗affectedchain不混oldSHA。
- [ ] **Step 4: 8項單人/多人指引與status。** 真飲用→左右廊→HUDretained→任一Buff自然expiry全組回→HIGHPowered保護／全員redrink sealed新SID→LOWsafeReturnOFF；32chunk512block遠望／回頭／pair96seam近/遠、Iris/shader與resource reload列人工pending；原生particles非bloom、optionalLight主副手torch不混server。M2自動＋taskreviews先綠再M3spike，人工/wholebranch收尾，不假已完成。
- [ ] **Step 5: commit／review／handoff。** `test: 完成新版 M2 持久化 gate 與驗收指引`，只有本task獨立review；wholebranchreview仍在後續整體收尾一次，所有Minor/rulings/CI證據需交接。M3開始前文件明列沒有候選選擇/塌縮/family全量/passage。main不merge／無LICENSE／不刪owner或userworld直到所有必要gate明確關閉。
