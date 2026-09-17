# M2 供電式走廊與斷電返還實作計畫

> **執行代理要求：** 使用 superpowers:subagent-driven-development，逐任務執行、測試與評審；不在任務之間重複詢問是否繼續。

**Goal:** 單人外部先供電、進入關門補齊 QuantumState 後看見真實延伸走廊；斷電安全回同一原艙，走廊結束後才解除原艙保護。

**Architecture:** 一個固定 `quantumchamber:superposition` 世界，版本化恢復 journal、共享 cohort、普通實體幾何與有限局部頁面實例。透過 M1.2 gateway 接入活動／返還狀態；所有玩家與恢復修改在伺服器執行緒序列化。

**Tech Stack:** Minecraft 1.21／Fabric／Java21／Gradle8.8／JUnit5／Fabric GameTest／Netty EmbeddedChannel 測試fixture。

**Spec:** `docs/superpowers/specs/2026-09-17-m2-powered-corridor-design.md`、`docs/superpowers/specs/2026-09-17-m1.2-powered-origin-design.md`。

## Global Constraints

- Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、Java 21、Gradle Wrapper 8.8。
- 單一 Fabric module，main／client 分離、三種建材、7×7×7／5×5×5、Controller local(3,6,0)、25格門與青紫造型不變。
- 僅固定靜態 Superposition Dimension；無 UniverseRegistry、runtime allocation、Universe candidate selection、跨宇宙passage／projection materialization。
- 無 mandatory renderer／portal／dimension library、無自訂 packet、無 LICENSE；不push／merge main、不改玩家存檔、不刪世界。
- 玩家至少一人、排除spectator、完整bbox在interior、全員buff；凍結cohort，成功時一次消耗全員效果，失敗不消耗。
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

環境沿用 M1.2：Java21、GRADLE_USER_HOME=C:/Users/Ben/.gradle、require_escalated操作快取、offline聚焦與提交前完整測試；Git per-command safe.directory。先完成M1.2自動gate再dispatch M2 Task1。只讀API查核的完整證據位置記入本計畫ledger。

### Task 1: 固定世界、版本化恢復資料與checked journal

**Files:**
- Create: `src/main/resources/data/quantumchamber/dimension/superposition.json`
- Create: `src/main/resources/data/quantumchamber/dimension_type/superposition.json`
- Create: `src/main/java/dev/quantumchamber/superposition/SuperpositionWorld.java`
- Create: `src/main/java/dev/quantumchamber/superposition/SessionState.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Create: `src/main/java/dev/quantumchamber/persistence/SessionJournalStore.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStateTest.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionJournalStoreTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`
- Modify: `src/testmod/resources/fabric.mod.json`

**Interfaces:**
- Produces: `SuperpositionWorld.KEY` 為 RegistryKey<World>，ID quantumchamber:superposition。
- Produces: `SessionState { ARMING, SUPERPOSITION, RETURNING }`。
- Produces: recovery record含UUID session/chamber、完整ChamberOriginAuthority、List<Participant>、Set<Integer>physicalSlots、state；Participant含UUID、sourcePosition/velocity、yaw/pitch、QuantumState NBT快照、returned。集合與NBT複製，不持久化Java/world物件。
- Produces: `SessionRecoveryState.get(MinecraftServer)`、`records()`、`put(SessionRecoveryRecord)`、`remove(UUID)`、`requireHealthy()`、`flush(MinecraftServer)`；STATE_ID quantumchamber_sessions、schema1。
- Produces: `SessionJournalStore.write(Path target, NbtCompound wrapped)`／`read(Path target)`，compressed NBT，write失敗拋明確例外，temp與target皆在同一data目錄；不吞錯、不清dirty假成功。

- [ ] **Step 1: 原生已知world缺失先RED。** 不引用新class：

```java
RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD,
        Identifier.of("quantumchamber", "superposition"));
context.assertTrue(context.getWorld().getServer().getWorld(key) != null,
        "固定Superposition世界必須真實存在");
```

- [ ] **Step 2: 跑原生RED，另寫journal不可寫／錯schema／NBT roundtrip測試。** 期望M1.2沒有固定world，GameTest該項失敗；NBT缺source資料不得偷偷丟掉participant。
- [ ] **Step 3: 實作靜態JSON與模型／codec／atomic store。** dimension使用minecraft:flat、layers=[]、biome=minecraft:the_void、structure_overrides=[]、features/lakes=false；type所有必要字段含has_raids/piglin_safe、無skylight、ambient0、min_y0、height/logical_height256、coordinate_scale1、bed/anchor=false、monster_spawn_light_level/block_light_limit0、effects=minecraft:overworld。Data pack48／resource pack34勿混用。保存wrapper data＋DataVersion，force temporary file後atomic replace；不支援atomic時拒絕交易，不unsafe覆蓋舊journal。flush可用save override呼叫checked store，不能讓原生save吞錯。
- [ ] **Step 4: GREEN。** 世界實際getWorld、world codecs、journal寫入讀回／不可寫保留舊資料、重複UUID／非vanilla source role拒絕，完整clean build+GT。
- [ ] **Step 5: 自評／提交。** `feat: add static superposition world and durable recovery journal`；目前不傳送、不消耗藥水，report精確說明API與錯誤路徑。

### Task 2: 邏輯頁、局部affine配置、replica及session保護

**Files:**
- Create: `src/main/java/dev/quantumchamber/corridor/LogicalAddress.java`
- Create: `src/main/java/dev/quantumchamber/corridor/DoorKey.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorLayout.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SlotAllocator.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorGeometry.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SessionEntranceAllocator.java`
- Create: `src/main/java/dev/quantumchamber/corridor/SessionSpaceProtection.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberProtectionService.java`
- Test: `src/test/java/dev/quantumchamber/corridor/CorridorLayoutTest.java`
- Test: `src/test/java/dev/quantumchamber/corridor/SlotAllocatorTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`

**Interfaces:**
- Produces: `LogicalAddress(long pageIndex, double localZ)`、`static LogicalAddress from(double logicalZ)`；floorDiv／finite／range驗證。
- Produces: `DoorKey(UUID sessionUuid, long logicalDoorIndex, Side side)`、Side LEFT/RIGHT；index=floorDiv(blockLogicalZ,8)。
- Produces: `public static List<LayoutComponent> CorridorLayout.plan(Set<Long> occupiedPages, int apron)`，nested `LayoutComponent(long firstCorePage, long lastCorePage, long aliasStartBlock, long aliasEndBlock)`，core頁端點inclusive／aliasEnd exclusive；排序依firstCorePage，回傳局部不跨空隙的分量，logical↔physical可逆、近entities同affine。
- Produces: `public Optional<SlotLease> SlotAllocator.reserve(BlockBox relativeBounds)`、`public void release(int slotId)`，nested `SlotLease(int slotId, BlockPos origin, BlockBox bounds)`；完整AABB不交、中心間距>=160，不用歷史logical distance決定physical位置。
- Produces: `public void CorridorPageManager.attach(MinecraftServer)`、`public void prepare(UUID sessionUuid, UUID chamberUuid, Direction facing, Set<Long> occupied)`、`public void tick()`、`public boolean ready(UUID)`、`public ChamberFrame entrance(UUID)`、`public Vec3d toPhysical(UUID,double lateral,double y,double logicalZ)`、`public void release(UUID)`；prepare只入列，ready才可commit，資料不存在／容量拒絕用明確例外，不回傳假frame。
- Produces: `SessionEntranceAllocator` 入口同來源facing，CBE標PROJECTION＋來源chamber UUID；只在全員入場成功後開replica後牆的5×5孔通往走廊，不改原艙。
- Produces: `ChamberProtectionService.registerAdditionalGuard(BiPredicate<ServerWorld,BlockPos>)`，SessionSpaceProtection使用精確server/world實例與slot index；registered once、STOPPED detach。

- [ ] **Step 1: 數學RED，手算字面fixture。** from(-1)page=-1/local95、from(96)page1/local0；occupied={0,1000000}不生成中間gap；near positions95.5/96.5距離保持1且DoorKey across remap不變。

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
- [ ] **Step 2: 聚焦RED，建立entity/page pin與AABB collision案例。** 不用同一builder算expected、不測mock本身。
- [ ] **Step 3: 最小配置／geometry。** 5×5 interior、7×7外框、8格左右門站、576apron；近interval合併、遠分量獨立，映射epoch更新整群，不單獨讓近玩家跳到另一slot。source orientation全四方向，完整bounds驗證。每tick4096寫入預算，頁準備前保留舊映射與pin；沒有玩家／items／projectiles／操作租約才回收。入口ChamberGeometry只用0..6，走廊另建座標helper。
- [ ] **Step 4: 原生GREEN。** replica shell與門／CBE kind正確，known固定world普通setBlock被擋、authorized geometry可寫；跨頁碰撞地板／apron、遠玩家布局與slot資源界限。loop native服務尚不傳送玩家，視覺無終點人工待驗。
- [ ] **Step 5: 自評與提交。** `feat: build bounded paged corridor spaces with guarded replicas`；報告實際bounds／budget與geometry完成時間。

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
- Create: `src/testmod/java/dev/quantumchamber/gametest/ConnectedGameTestPlayer.java`

**Interfaces:**
- Consumes: M1.2 ChamberSessionGateway.Presence／StartResult／start／returnToOrigin的精確簽名與ChamberSessions.install。
- Produces: `SuperpositionSessionManager implements ChamberSessionGateway`、`initialize()`、`tick(MinecraftServer)`，authority/runtime state不落client。
- Produces: `SessionTransferService.move(ServerPlayerEntity, ServerWorld, Vec3d, Vec3d, float, float)` 回傳實際核對成功，不信teleport boolean。
- Produces: `ChamberReturnPlacement.plan(ChamberFrame,List<UUID>)` UUID確定性5×5floor slots，最多25人；capacity不足入場前拒絕。
- Produces: `QuantumEffectTransaction` 以NBT snapshot／restore與完整cohort一次commit，不提供Universe傳送API。

- [ ] **Step 1: 原生先供電進人入場RED。** 沿用M1真ServerPlayer連線fixture，native lever高位／關門／喝藥後需進SuperpositionWorld且效果消耗；現有ARMED_ONLY adapter不移動，該斷言失敗。

```java
context.assertTrue(player.getServerWorld() == server.getWorld(SuperpositionWorld.KEY),
        "完整cohort必須進入真實固定世界");
context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE), "成功入場才消耗藥效");
context.assertTrue(player.getInventory().getStack(0).isOf(Items.TORCH), "入場保留攜帶物品");
```
- [ ] **Step 2: 保存RED；測少一人buff、zero／spectator、容量、重複刷新。** 無效case全員仍在來源且buff未移除，held-high只一session。
- [ ] **Step 3: 接gateway與ARMING交易。** 凍結cohort及source positions／effects，durable ARMING journal先於geometry/teleport提交；準備期間重驗來源／全員資格，offline／名單改變拒絕。全部world/positions核對成功才消耗效果並durable SUPERPOSITION、publish active、開replica後牆；任一步失敗rollback、保存RETURNING pending，不留下半cohort。active時source chamber空／藥效已耗不降級3。保持來源Controller的具體chunk session tickets，radius2且對稱remove。
- [ ] **Step 4: GREEN跨world。** 真server/world身份、relative pose／inventory不變、NBT hidden-effect rollback、partial-move failure不解鎖，缺buff無人不創造新world；core JUnit+GT全過。
- [ ] **Step 5: 自評／提交。** `feat: start shared superposition sessions from powered chambers`；尚待Task4完整斷電／登入恢復，不宣告整個M2完成。

### Task 4: 斷電、離線重登、重啟恢復及頁面移動

**Files:**
- Create: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`
- Create: `src/main/java/dev/quantumchamber/corridor/CorridorRepositionService.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/transfer/SessionTransferService.java`
- Test: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryManagerTest.java`
- Test: `src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java`

**Interfaces:**
- Consumes: durable recovery records、page mapping leases與gateway.returnToOrigin。
- Produces: `SessionRecoveryManager.onJoin(UUID,MinecraftServer)` queue、`onDisconnect(UUID,MinecraftServer)`、`tick(MinecraftServer)`、`returnComplete(UUID)`；manager/gateway只在全cohort／資料確認後complete。
- Produces: `CorridorRepositionService.tick` 管理邏輯位置、items/projectiles tag／pin、近群split/merge預備／commit／retire；相關entity每次只有一個authority mapping。

- [ ] **Step 1: 原生來源斷電返還RED。** 在來源world移除電源，先確認RETURNING仍保護，再確認全部player原world/interior、session結束、原艙才可變更。不能只helper return true。

```java
sourceWorld.setBlockState(sourceController.getPos().up(), Blocks.AIR.getDefaultState(), 3);
context.assertTrue(player.getServerWorld() == sourceWorld, "斷電回到同一來源world實例");
context.assertTrue(ChamberGeometry.interiorBox(sourceFrame).contains(player.getBoundingBox()),
        "全身回到有限原艙內");
context.assertTrue(ChamberProtectionService.get().mayMutate(sourceWorld, sourceController.getPos()),
        "返還確認後原艙才解除保護");
```

上述斷言於返還完成的waitAndRun／runAtTick執行；來源斷電時另以returnPending驗證仍保護，不把同一tick同步helper當真恢復事件。
- [ ] **Step 2: 失敗與離線queue RED。** 真close EmbeddedChannel／ClientConnection觸發DISCONNECT，不以PlayerManager.remove冒充；重登callback尚未完成不得跨world，下一tick才恢復。錯source身分／missingworld／journal不可寫保留保護與slots。
- [ ] **Step 3: 完整返還／恢復。** pending每人returned flags持久化後再清session；offline不當已returned。重啟全部舊session標RETURNING，恢復前禁止正常移動／門操作；已在原艙者可冪等確認，不重複消耗buff。STOPPING保存／釋放tickets，STOPPED清runtime；cleanup前先返還有價items、不unsafe清玩家腳下。高位重供電遇pending先完成舊返還。
- [ ] **Step 4: GREEN頁面與群體。** 正負向長走／回頭、兩人跨96seam相近／遠分裂重聚、items／projectiles跨seam、移動途中斷電、source四朝向，底板與collision保持。資源不足不拆舊映射或遺失物品，記錄錯誤並安全返還。
- [ ] **Step 5: 自評／提交。** `feat: recover corridor participants safely on power loss and reconnect`；逐項列真正event coverage與純seam coverage，未測visual保持待驗。

### Task 5: 真live restart、production smoke、玩法指引與final review

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M2PersistenceProbe.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Create: `docs/implementation-notes/m2-corridor.md`
- Modify: `docs/implementation-notes/m1-player-build-verification.md`
- Modify: `README.md`
- Modify: `docs/plans/2026-09-17-m2-powered-corridor.md`

**Interfaces:**
- Produces: property quantumchamber.m2.phase、fresh run/m2-persistence／run/m2-production-smoke；default不啟用probe，release不含testmod。
- Consumes: Tasks1–4的真固定world／nativepowered activation／journal與完整關閉流程。

- [ ] **Step 1: 新testworld的不同Java process證明session中斷／pending-return重啟。** 保存UUID／sourceworld／原位置／實際fixed-world participant與journal狀態，正常stop與重啟／重登確認原world返還、不重建舊session、不生成Universe。若使用trusted setup須明確區分native activation證據，不能用探針直接構造紀錄假裝live玩家。
- [ ] **Step 2: production-only dedicated。** 無testmod／client mods，Done→console stop→四world全部save→exit0；無client-loading error／Dynamic Universe code。
- [ ] **Step 3: 最後非快取clean build／GT／XML／JAR與common/client檢查。** 原版baseline、light依賴解析與照明JAR hash分開；不啟動或覆寫使用者world。
- [ ] **Step 4: 更新單人不指令指引與人工gate。** 外部拉桿→進艙→關門→已有buff或喝藥→真走廊；外部預設計時斷電電路→回同一普通盒子→可Creative拆。寫start-client.bat light、存檔備份人工複製、主副手torch、32chunk遠望／回頭、shader／resource reload、近玩家群組 seam清單；未實測必須標待驗。
- [ ] **Step 5: whole-feature獨立final review與交付。** 包含main merge-base至HEAD完整diff、所有deferred／rulings、真測試證據；先fix blockers再交付本機分支。人工gate未關不得合併main，不假稱M3已完成。
