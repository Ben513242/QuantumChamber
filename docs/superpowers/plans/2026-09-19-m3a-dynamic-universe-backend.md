# M3-A Dynamic Universe Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立一個可持久化、跨 JVM 重建、可安全同 key 卸載／替換的單一 alternate Overworld backend，並以 Gate A／Gate B runtime evidence 關閉先前 spike 的未證範圍。

**Architecture:** `UniverseRegistryState` 是 checked catalog 權威，`UniverseLifecycleService` 只經 `DynamicDimensionBackend` 操作 live worlds；Minecraft 1.21 private accessors 全部封裝在 `universe.minecraft121`。M3-A 不接候選門、collapse、Projection 或玩家傳送，後者由 M3-B 計畫處理。

**Tech Stack:** Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、Java 21、Gradle 8.8、JUnit 5、Fabric GameTest、PowerShell runtime harness。

**Spec:** `docs/superpowers/specs/2026-09-19-m3-dynamic-universe-foundation-design.md`

## Global Constraints

- 不新增第三方維度 library、renderer、packet、LICENSE、candidate、collapse、full family 或 passage。
- 所有正式 mutation 只能在 owning Minecraft server thread；所有 publish/remove 都驗 exact server/key/world instance。
- Schema 1 只支援一個 Overworld-role definition profile：`VANILLA_OVERWORLD_SHARED_SEED_V1` + `SHARED_SAVE_SEED_V1`。
- Catalog 先 checked flush 並 exact readback，才能 materialize；catalog 損壞或 orphan owned storage 一律 fail closed。
- Gate A 未通過不得實作 early unload；Gate B 未通過不得接 lifecycle bootstrap 或 M3-B。
- 所有 runtime probe 只操作 fresh nonce-owned loopback world；不 HALT、不 kill、不刪 world/evidence、不由 manifest 補 sentinel。
- M2 人工八項、整分支 final review、main merge與M3 push均不在本計畫授權內。

---

## File Map

**Domain/catalog**

- `src/main/java/dev/quantumchamber/universe/UniverseId.java`：UUID identity value object。
- `src/main/java/dev/quantumchamber/universe/UniverseKeys.java`：由 UUID/role 產生 deterministic world key。
- `src/main/java/dev/quantumchamber/universe/GeneratorProfile.java`、`SeedPolicy.java`、`DesiredAvailability.java`：schema 1 封閉列舉。
- `src/main/java/dev/quantumchamber/universe/UniverseWorldDescriptor.java`、`UniverseDefinition.java`、`UniverseRecord.java`：immutable definition 與 mutable desired state 分離。
- `src/main/java/dev/quantumchamber/universe/UniverseRegistry.java`：in-memory invariants與查詢。
- `src/main/java/dev/quantumchamber/universe/UniverseCatalogStore.java`：atomic checked NBT I/O。
- `src/main/java/dev/quantumchamber/universe/UniverseRegistryState.java`：server-owned current/flushed state與strict codec。

**Backend/lifecycle**

- `src/main/java/dev/quantumchamber/universe/DynamicDimensionBackend.java`：穩定 backend contract。
- `src/main/java/dev/quantumchamber/universe/DynamicWorldRuntimeState.java`、`MaterializeResult.java`、`UnloadResult.java`：明確結果與狀態。
- `src/main/java/dev/quantumchamber/universe/UniverseRuntimeRegistry.java`：每 server exact instance owner。
- `src/main/java/dev/quantumchamber/universe/UniverseMaterializationService.java`：checked catalog 與 backend 之間的權威協調器。
- `src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`：啟動重建、正常停止與 health gate。
- `src/main/java/dev/quantumchamber/universe/UniverseRoleResolver.java`：vanilla + catalog exact key role resolution。
- `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121DynamicDimensionBackend.java`：pinned runtime orchestration。
- `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121ServerWorldFactory.java`：唯一 `ServerWorld` constructor owner。
- `src/main/java/dev/quantumchamber/mixin/MinecraftServerDynamicWorldAccess.java`：world map/session/executor accessors。

**Queue/lifecycle integration**

- `src/main/java/dev/quantumchamber/chamber/ChamberLoadSyncOutcome.java`：branch-specific queue receipt。
- `src/main/java/dev/quantumchamber/chamber/ChamberControllerLoadSyncQueue.java`：逐項 outcome、observer與expected-world cleanup。
- `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`：最後才註冊 M3 lifecycle。
- `src/main/resources/quantumchamber.mixins.json`：只加入 Minecraft server accessor。

**Tests/probes**

- `src/test/java/dev/quantumchamber/universe/*Test.java`：domain、codec、registry、runtime state、fake backend。
- `src/testmod/java/dev/quantumchamber/chamber/ChamberLoadSyncTestAccess.java`：test-only outcome observer。
- `src/testmod/java/dev/quantumchamber/gametest/M3UniverseRuntimeProbe.java`：Gate A/B native runtime phases。
- `src/testmod/resources/fabric.mod.json`：註冊 default-off probe entrypoint。
- `build.gradle`：新增 property-gated `m3UniverseProbe` server run。
- `.superpowers/sdd/2026-09-19-m3-dynamic-universe/`：gitignored harness、raw evidence、reviews與 receipts。

---

### Task 1: Universe identity、definition 與 strict codec

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/UniverseId.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseKeys.java`
- Create: `src/main/java/dev/quantumchamber/universe/GeneratorProfile.java`
- Create: `src/main/java/dev/quantumchamber/universe/SeedPolicy.java`
- Create: `src/main/java/dev/quantumchamber/universe/DesiredAvailability.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseWorldDescriptor.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseDefinition.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseRecord.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseDefinitionTest.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseKeysTest.java`

**Interfaces:**
- Produces: `UniverseId.of(UUID)`, `UniverseKeys.world(UniverseId, DimensionRole)`, `UniverseRecord.toNbt()`, `UniverseRecord.fromNbt(NbtCompound)`。
- Invariant: schema 1 definition 恰有一個 `OVERWORLD`；world key 必須等於 `quantumchamber:universe/<uuid>/overworld`。

- [ ] **Step 1: 寫 identity/key RED tests**

```java
@Test void worldKeyIsStableAndRoleQualified() {
    var id=UniverseId.of(UUID.fromString("00000000-0000-0000-0000-000000000123"));
    assertEquals("quantumchamber:universe/00000000-0000-0000-0000-000000000123/overworld",
            UniverseKeys.world(id,DimensionRole.OVERWORLD).getValue().toString());
}

@Test void schemaOneRejectsNonOverworldAndMismatchedKey() {
    assertThrows(IllegalArgumentException.class,() -> fixture(DimensionRole.NETHER));
    assertThrows(IllegalArgumentException.class,() -> fixtureWithKey(Identifier.of("quantumchamber","wrong")));
}
```

- [ ] **Step 2: 執行 RED**

Run:

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.UniverseDefinitionTest' --tests 'dev.quantumchamber.universe.UniverseKeysTest'
```

Expected: compile FAIL，因上述 types 尚不存在。

- [ ] **Step 3: 實作最小封閉模型**

```java
public record UniverseId(UUID value) {
    public UniverseId { Objects.requireNonNull(value,"value"); }
    public static UniverseId of(UUID value) { return new UniverseId(value); }
}

public enum GeneratorProfile { VANILLA_OVERWORLD_SHARED_SEED_V1 }
public enum SeedPolicy { SHARED_SAVE_SEED_V1 }
public enum DesiredAvailability { EAGER_ENABLED, DISABLED }
```

`UniverseDefinition` constructor 必須 defensive-copy `EnumMap`，驗一個 OVERWORLD entry、UUID/key/role/profile/policy exact match；`UniverseRecord` codec 使用固定欄位 `SchemaVersion`、`UniverseUuid`、`AllocationOrdinal`、`DesiredAvailability`、`Worlds`，每個欄位先驗 NBT type，未知 enum/schema拒絕。

- [ ] **Step 4: 執行 GREEN 與完整 universe unit tests**

Run: 同 Step 2，再執行：

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.*'
```

Expected: PASS；wrong NBT type、duplicate role、unknown schema/profile/policy 全部被拒絕。

- [ ] **Step 5: 獨立 spec review、quality review、commit**

```powershell
git add src/main/java/dev/quantumchamber/universe src/test/java/dev/quantumchamber/universe
git commit -m "feat: add M3 universe definition model"
```

---

### Task 2: Checked Universe catalog 與 allocation API

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/UniverseRegistry.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseCatalogStore.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseRegistryState.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseRegistryTest.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseCatalogStoreTest.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseRegistryStateTest.java`

**Interfaces:**
- Consumes: `UniverseRecord` codec from Task 1。
- Produces: `UniverseRegistry.allocateOverworld(UUID,long)`, `find(UniverseId)`, `findByWorldKey(RegistryKey<World>)`, `changeAvailability(...)`；`UniverseRegistryState.flush(MinecraftServer)` 與 exact `flushedRecords()`。

- [ ] **Step 1: 寫 registry/store RED tests**

```java
@Test void allocationIsIdempotentOnlyForTheSameDefinition() {
    var registry=new UniverseRegistry();
    var first=registry.allocateOverworld(ID,7);
    assertSame(first,registry.allocateOverworld(ID,7));
    assertThrows(IllegalArgumentException.class,() -> registry.allocateOverworld(ID,8));
}

@Test void corruptExistingCatalogNeverFallsBackToEmpty(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("quantumchamber_universes.dat"),"not nbt");
    var loaded=UniverseRegistryState.load(root.resolve("quantumchamber_universes.dat"));
    assertThrows(IllegalStateException.class,loaded::requireHealthy);
}
```

- [ ] **Step 2: 執行 RED**

Run:

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.UniverseRegistryTest' --tests 'dev.quantumchamber.universe.UniverseCatalogStoreTest' --tests 'dev.quantumchamber.universe.UniverseRegistryStateTest'
```

Expected: FAIL，catalog classes 尚不存在。

- [ ] **Step 3: 實作 atomic store 與 current/flushed authority**

`UniverseCatalogStore.write` 必須：create same-directory temp → compressed NBT → `FileChannel.force(true)` → `ATOMIC_MOVE|REPLACE_EXISTING` → strict readback比對完整 NBT。`UniverseRegistryState` 正式路徑固定為：

```java
server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("quantumchamber_universes.dat")
```

Mutation 流程固定為：copy candidate → validate cross-record duplicate keys → assign current → mark dirty；`flush` 成功後才更新 immutable `flushed`。失敗保留上一個 flushed snapshot與 dirty state。

- [ ] **Step 4: 補 orphan 判定與 availability tests**

以 pure path inventory helper 驗：有 catalog/no region允許、catalog+region允許、owned `dimensions/quantumchamber/universe/...` storage無 catalog明確 `ORPHANED`；測試不得刪 orphan。

- [ ] **Step 5: 執行 GREEN、舊 persistence regression**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.*' --tests 'dev.quantumchamber.persistence.*'
```

Expected: PASS；Windows-only tests僅允許既有具名OS skip。

- [ ] **Step 6: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/universe src/test/java/dev/quantumchamber/universe
git commit -m "feat: persist checked universe catalog"
```

---

### Task 3: Backend contract 與 runtime state machine

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/DynamicDimensionBackend.java`
- Create: `src/main/java/dev/quantumchamber/universe/DynamicWorldRuntimeState.java`
- Create: `src/main/java/dev/quantumchamber/universe/MaterializeResult.java`
- Create: `src/main/java/dev/quantumchamber/universe/UnloadResult.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseRuntimeRegistry.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseMaterializationService.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseRuntimeRegistryTest.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseBackendContractTest.java`

**Interfaces:**
- Consumes: `UniverseWorldDescriptor`。
- Produces: exact backend signatures from the design spec；`UniverseRuntimeRegistry.beginMaterialize/activate/beginUnload/release/fail`；`UniverseMaterializationService.materializeChecked(MinecraftServer, UniverseRecord, UniverseWorldDescriptor)`。

- [ ] **Step 1: 寫 state transition RED tests**

```java
@Test void replacementIsBlockedUntilExactOldInstanceIsReleased() {
    var runtime=new UniverseRuntimeRegistry();
    runtime.beginMaterialize(SERVER,DEF);
    runtime.activate(SERVER,DEF,WORLD_A);
    assertThrows(IllegalStateException.class,() -> runtime.beginMaterialize(SERVER,DEF));
    runtime.beginUnload(SERVER,DEF,WORLD_A);
    assertThrows(IllegalArgumentException.class,() -> runtime.release(SERVER,DEF,WORLD_B));
    runtime.release(SERVER,DEF,WORLD_A);
    assertEquals(DynamicWorldRuntimeState.ABSENT,runtime.state(SERVER,DEF));
}
```

- [ ] **Step 2: 執行 RED，再實作結果型別與狀態機**

`MaterializeResult`：`MATERIALIZED`、`ALREADY_ACTIVE`、`REJECTED`、`FAILED_ROLLED_BACK`、`FAILED_UNHEALTHY`；成功才含 exact `ServerWorld`。`UnloadResult`：`UNLOADED`、`REJECTED_BUSY`、`UNLOAD_UNSUPPORTED`、`FAILED_UNHEALTHY`。

- [ ] **Step 3: 以 fake backend 驗 ordering**

Fake backend本身的事件順序必須精確為 `CONSTRUCTED → PUBLISHED → LOAD → ACTIVE`；rollback則為 `UNLOAD → EXPECTED_REMOVE → CLOSE → FAILED_ROLLED_BACK`。`UniverseMaterializationService` 先驗 `UniverseRegistryState.flushedRecords()` 含有完全相同的record/descriptor，才呼叫backend；其service-level順序為 `CATALOG_FLUSHED → BACKEND_CALLED`。禁止只相信current/dirty record，也禁止key-only remove。

- [ ] **Step 4: 執行 GREEN**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.UniverseRuntimeRegistryTest' --tests 'dev.quantumchamber.universe.UniverseBackendContractTest'
```

- [ ] **Step 5: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/universe src/test/java/dev/quantumchamber/universe
git commit -m "feat: define dynamic dimension backend contract"
```

---

### Task 4: Load-sync branch receipts 與 expected-world cleanup

**Files:**
- Create: `src/main/java/dev/quantumchamber/chamber/ChamberLoadSyncOutcome.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberControllerLoadSyncQueue.java`
- Modify: `src/testmod/java/dev/quantumchamber/chamber/ChamberLoadSyncTestAccess.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M1ChamberGameTests.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/ChamberNoForceGameTests.java`

**Interfaces:**
- Produces: package-private `observe(MinecraftServer, Consumer<Receipt>)`、`discardWorld(ServerWorld expected)`；receipt 包含 exact server/world/controller/pos 與 outcome。
- Outcomes: `CONSUMED`、`REQUEUED_NOT_DUE`、`REQUEUED_NEIGHBOR_NOT_READY`、`DROPPED_SERVER_IDENTITY`、`DROPPED_WORLD_IDENTITY`、`DROPPED_REMOVED_BE`、`DROPPED_NO_FULL_CHUNK`、`DROPPED_REPLACED_BE`、`DISCARDED_WORLD_UNLOAD`。

- [ ] **Step 1: 為既有三條路徑加 RED outcome assertions**

在既有 unloaded-chunk、replaced-BE、missing-neighbor tests 安裝 observer，分別斷言 `DROPPED_NO_FULL_CHUNK`、`DROPPED_REPLACED_BE`、`REQUEUED_NEIGHBOR_NOT_READY → CONSUMED`；只看 `hasPending` 不再足夠。

- [ ] **Step 2: 執行聚焦 GameTests 證 RED**

Run `runGameTest`，預期上述具名案例因 observer API不存在而compile FAIL；不得先改 assertion 成寬鬆 count。

- [ ] **Step 3: 重構單筆處理回傳 outcome**

`processDue` 每個分支先建立 receipt，再通知 server-scoped optional observer；observer預設不存在，正式 runtime不保留歷史。`AutoCloseable` 關閉 observer時只移除同 instance。Observer exception不得改變 queue權威，測試 observer exception需被記錄並使該test失敗。

- [ ] **Step 4: 實作 `discardWorld(expected)`**

只移除 `entry.server()==expected.getServer() && entry.world()==expected`，每筆發 `DISCARDED_WORLD_UNLOAD`；不得只比 world key，以免清到replacement。

- [ ] **Step 5: 執行完整 GameTests 與 JUnit**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test runGameTest
```

Expected: 所有既有 GT 通過；receipt 能區分 spike 曾混淆的 consume/drop。

- [ ] **Step 6: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/chamber src/testmod/java/dev/quantumchamber/chamber src/testmod/java/dev/quantumchamber/gametest
git commit -m "test: expose exact chamber load-sync outcomes"
```

---

### Task 5: Pinned Minecraft 1.21 materialize backend

**Files:**
- Create: `src/main/java/dev/quantumchamber/mixin/MinecraftServerDynamicWorldAccess.java`
- Create: `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121ServerWorldFactory.java`
- Create: `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121DynamicDimensionBackend.java`
- Modify: `src/main/resources/quantumchamber.mixins.json`
- Test: `src/test/java/dev/quantumchamber/universe/Minecraft121BackendGuardTest.java`

**Interfaces:**
- Consumes: Task 3 backend contract/runtime registry。
- Produces: materialize與resolveActive；`unload` 在 Task 7 前固定回 `UNLOAD_UNSUPPORTED`。

- [ ] **Step 1: 寫 version/path/identity guard RED tests**

Tests 至少覆蓋：非1.21拒絕、vanilla key拒絕、map已有不同instance拒絕、storage canonical不在`dimensions/quantumchamber/universe`拒絕。Catalog未flushed由Task 3的`UniverseMaterializationService`拒絕，backend不得自行讀current catalog或重複另一套authority。

- [ ] **Step 2: 建立只讀/寫 accessor mixin**

```java
@Mixin(MinecraftServer.class)
public interface MinecraftServerDynamicWorldAccess {
    @Accessor("worlds") Map<RegistryKey<World>,ServerWorld> quantumchamber$getWorlds();
    @Accessor("session") LevelStorage.Session quantumchamber$getSession();
    @Accessor("workerExecutor") Executor quantumchamber$getWorkerExecutor();
}
```

Accessor 不 Inject tick/save/constructor；Mixin config只有這個新項目。

- [ ] **Step 3: 實作 pinned factory**

Factory 使用同 server registry manager 的 Overworld `DimensionOptions`、`UnmodifiableLevelProperties`、同 session/executor、`BiomeAccess.hashSeed(generatorOptions.seed)`、空 spawners、`shouldTickTime=false`、Overworld random sequences。這是 shared-seed profile，不增加未證的獨立 seed。

- [ ] **Step 4: 實作 materialize transaction**

順序固定：guards → construct → border listener → `putIfAbsent` → identity checks → explicit `ServerWorldEvents.LOAD.invoker().onWorldLoad` → runtime active。LOAD dispatch前失敗可expected-remove並close尚未對observer公開的world；LOAD dispatch已開始後若失敗，在Task 7 Gate B前不得使用未證early unload，必須保留exact world於map、標記`FAILED_UNHEALTHY`並請求正常stop，交給vanilla shutdown save/UNLOAD/close。Task 7證明quiesce後才補完整publish後rollback。

- [ ] **Step 5: 編譯、unit tests與main-only server smoke**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test build
```

再啟動fresh main-only dedicated server，確認空 catalog時仍只有既有四world、Done→stop→save，無dynamic LOAD。

- [ ] **Step 6: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/mixin src/main/java/dev/quantumchamber/universe src/main/resources/quantumchamber.mixins.json src/test/java/dev/quantumchamber/universe
git commit -m "feat: materialize pinned Minecraft 1.21 worlds"
```

---

### Task 6: Gate A — checked catalog 的第二 JVM native readback

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M3UniverseRuntimeProbe.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `build.gradle`
- Create: `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/run-gate-a.ps1`
- Create: `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/task-6-gate-a-report.md`

**Interfaces:**
- Phase `create-save`：正式 registry allocate+flush後materialize、寫sentinel/controller。
- Phase `reload-read`：正式 catalog strict-load/reconstruct，只讀原生storage。

- [ ] **Step 1: 寫 Gate A harness 與 RED phase assertions**

Build run 使用 repo-relative `run/m3-universe-$nonce`，避免 spike 的 Windows absolute Loom `runDir` 錯誤。Properties必含 phase、nonce、startupNonce、canonical root；缺一不註冊callback。

- [ ] **Step 2: 先執行 `create-save`，預期因 probe orchestration/entrypoint 尚未完成而 RED**

保留PID、StartTime、source/classes hashes、stdout/stderr、events.jsonl、final.json、region hash。任何fail即停止，不啟動第二phase。

- [ ] **Step 3: 補最小 probe orchestration，不改 sentinel oracle**

`create-save` 必須從空catalog配置固定UUID、flush/readback後經`UniverseMaterializationService.materializeChecked`直接驅動backend；不得依賴Task 8 lifecycle bootstrap。寫diamond與真Controller後正常`server.stop(false)`。結果要求LOAD1/UNLOAD1、正式catalog與region存在。

- [ ] **Step 4: 執行 `reload-read`**

新PID啟動前 live map無key、DIMENSION registry無foreign definition；由正式catalog重建。只讀diamond/controller/NBT；禁止phase參數攜帶sentinel value。Queue receipt 必須明確顯示真 BE LOAD後的實際分支，registry不得新增 Chamber origin。

- [ ] **Step 5: 取得 Gate A PASS 或保留正式 blocker**

只有兩phase正常stop、native readback、exact identities、hash不變才PASS。若FAIL，停止 M3-A，不修改oracle重跑到綠。

- [ ] **Step 6: review runtime evidence、commit tracked harness/code**

```powershell
git add build.gradle src/testmod/java/dev/quantumchamber/gametest/M3UniverseRuntimeProbe.java src/testmod/resources/fabric.mod.json
git commit -m "test: prove dynamic universe restart readback"
```

---

### Task 7: Early unload quiesce 與 Gate B同 key replacement

**Files:**
- Modify: `src/main/java/dev/quantumchamber/universe/minecraft121/Minecraft121DynamicDimensionBackend.java`
- Modify: `src/main/java/dev/quantumchamber/universe/UniverseRuntimeRegistry.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/ChamberControllerLoadSyncQueue.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M3UniverseRuntimeProbe.java`
- Modify: `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/run-gate-a.ps1`
- Create: `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/task-7-gate-b-report.md`

**Interfaces:**
- Produces: `unload(...expectedWorld)` with exact `UNLOADED` receipt；同 key replacement只接受該receipt。

- [ ] **Step 1: 寫 Gate B RED phase**

Phase `unload-replace` 先讀sentinel，再要求 A→unload receipt→map absent→B same key/different instance→sentinel readback。Phase `final-verify` 在第三JVM由catalog重建並再次只讀。

- [ ] **Step 2: 實作 quiesce fail-loud**

順序固定：阻止新backend操作 → 驗players為空、forced chunks為空、自有tickets可撤 → remove自有tickets/listener → 重複 `ServerChunkManager.executeQueuedTasks()` 到明確無進度且受上限保護 → 以pinned 1.21 primary source確認會等待chunk/entity storage的 `world.save(null,true,false)` final flush → 再執行一次queued-task check。`executeQueuedTasks()==false` 單獨絕不算quiesced；缺primary-source-backed blocking flush receipt、flush拋錯或post-flush仍有工作，都回 `UNLOAD_UNSUPPORTED`，不remove map。

- [ ] **Step 3: 實作 destructive boundary**

Final flush後：explicit UNLOAD once → `discardWorld(expected)` → `worlds.remove(key,expected)` → `expected.close()` → verify absent/release owner。Remove或close後失敗一律 `FAILED_UNHEALTHY`，禁止replacement並正常停止；不得重新掛回半close world。

- [ ] **Step 4: 執行 Gate B**

使用Gate A同一owned world依序跑 `unload-replace`、`final-verify`，每phase新PID且前一個完整退出。要求LOAD/UNLOAD exact count、A/B identity、storage lock釋放、舊queue receipt為world unload/identity drop、region hash可解釋。

- [ ] **Step 5: 完整 regression**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test runGameTest build
```

- [ ] **Step 6: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/universe src/main/java/dev/quantumchamber/chamber src/testmod/java/dev/quantumchamber/gametest/M3UniverseRuntimeProbe.java
git commit -m "feat: unload and replace dynamic worlds safely"
```

---

### Task 8: Lifecycle bootstrap 與 exact dynamic role resolution

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/UniverseLifecycleService.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseRoleResolver.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseRoleResolverTest.java`
- Testmod: `src/testmod/java/dev/quantumchamber/gametest/M3UniverseLifecycleGameTests.java`

**Interfaces:**
- Consumes: Gate A/B-proven backend。
- Produces: `UniverseLifecycleService.initialize()`；`UniverseRoleResolver.resolve(MinecraftServer, RegistryKey<World>)`。

- [ ] **Step 1: 寫 resolver/bootstrap RED tests**

驗 vanilla三key維持原role、健康catalog exact dynamic key回OVERWORLD、unknown foreign empty、server mismatch empty、corrupt catalog throws。Bootstrap按ordinal/UUID排序；第二筆失敗時逆序unload本輪第一筆。

- [ ] **Step 2: 實作 lifecycle event registration**

`SERVER_STARTED` strict-load catalog並重建 `EAGER_ENABLED`；失敗後反向cleanup並 `server.stop(false)`。正常shutdown不手動UNLOAD，讓vanilla/Fabric做一次；`SERVER_STOPPED`只清runtime owners與驗detach。

- [ ] **Step 3: 僅在 M3 owner service 使用 resolver**

不要改 `DimensionRole.fromVanillaKey`；不要改 M1 activation/protection接受dynamic Origin。此task不建立 Projection。

- [ ] **Step 4: 執行 tests/GameTests/main-only smoke**

確認空catalog仍正常；有一筆catalog會重建；損壞catalog正常停止且不建立world。

- [ ] **Step 5: 兩階段 review、commit**

```powershell
git add src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java src/main/java/dev/quantumchamber/universe src/test/java/dev/quantumchamber/universe src/testmod/java/dev/quantumchamber/gametest/M3UniverseLifecycleGameTests.java
git commit -m "feat: bootstrap persistent dynamic universes"
```

---

### Task 9: M3-A final automated gate、文件與獨立 review

**Files:**
- Modify: `docs/implementation-notes/2026-09-19-m3-runtime-dimension-feasibility.md`
- Create: `docs/implementation-notes/2026-09-19-m3a-dynamic-universe-backend.md`
- Create: `.superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend/final-gate-report.md`

**Interfaces:**
- Produces: 可供 M3-B 信任的 exact commit、Gate A/B receipts與known limitations。

- [ ] **Step 1: 無快取完整 gate**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test runGameTest build --rerun-tasks
```

解析JUnit XML與GameTest XML；報告 declared/passed/failed/errors/skipped與每個skip理由，不只記exit0。

- [ ] **Step 2: 重跑正式 Gate A/B 全鏈一次於新的 fresh owner root**

四phase只能依序各一次；保存不同PID/StartTime/startupNonce、catalog hash、region hash、world key/storage、LOAD/UNLOAD receipts、queue outcomes、source/class hashes與normal stop。

- [ ] **Step 3: dedicated main-only regression與artifact audit**

空catalog與一筆catalog各做Done→stop；audit release/sources JAR無testmod/client leakage，common不載client classes。

- [ ] **Step 4: 更新文件且不誇大**

只在證據全綠時把原spike「未證」更新為M3-A已證；仍明列shared seed、無client sync、無player transfer、無candidate/collapse/family/passage/renderer。

- [ ] **Step 5: final spec review + code quality review**

Reviewer固定比較本M3-A起始commit到exact HEAD；Critical/Important為零才可進M3-B。

- [ ] **Step 6: commit，不push**

```powershell
git add docs/implementation-notes
git commit -m "docs: record M3-A dynamic universe evidence"
```

保持feature branch本機ahead；等待使用者明確要求才push。
