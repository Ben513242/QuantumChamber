# M3-B Server Transfer Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M3-A exact backend gate 通過後，證明原生 server-side `ServerPlayerEntity` 能以同座標安全進入單一 alternate Overworld並返回，且所有失敗路徑保留可恢復來源姿態。

**Architecture:** `UniverseTransferService` 是 `SessionTransferService` 之上的 authority wrapper；它只接受 M3-A exact ACTIVE handle、先凍結來源 pose，再執行原生 teleport與實際 identity驗證。此計畫不註冊 command、門、item、collapse或client renderer，真client sync仍是 M5 passage前置人工 gate。

**Tech Stack:** 與 M3-A相同；重用 `SessionTransferService`、JUnit 5、Fabric GameTest與default-off dedicated probe。

**Spec:** `docs/superpowers/specs/2026-09-19-m3-dynamic-universe-foundation-design.md`

## Global Constraints

- 前置條件：M3-A Gate A/B、完整 regression與獨立 review均通過；否則本計畫不得開始。
- 不新增玩家可呼叫的 command、item、door interaction、candidate、collapse、Projection或packet。
- Transfer只在server thread，要求source/destination/player exact identity；boolean teleport不是成功證據。
- 目標使用同XYZ，但Y需經world border/build-height與loaded safe floor檢查；不得把玩家塞入方塊或虛空。
- M3-B只證server-side原生player流程；真client連線與畫面切換不得被宣稱通過。
- 不push、不合併main；M2人工gate維持待驗。

---

## File Map

- `src/main/java/dev/quantumchamber/universe/UniverseTransferPoint.java`：immutable world/key/pose/velocity/yaw/pitch來源快照。
- `src/main/java/dev/quantumchamber/universe/UniverseTransferReceipt.java`：player、source、destination與結果權威。
- `src/main/java/dev/quantumchamber/universe/UniverseTransferResult.java`：拒絕、成功、rollback與recovery-required結果。
- `src/main/java/dev/quantumchamber/universe/UniverseTransferAuthority.java`：無live Minecraft object的純guard決策。
- `src/main/java/dev/quantumchamber/universe/UniverseTransferService.java`：ACTIVE guard、safe target、native move、return。
- `src/test/java/dev/quantumchamber/universe/UniverseTransferAuthorityTest.java`：pure authority/ordering tests。
- `src/testmod/java/dev/quantumchamber/universe/UniverseTransferTestAccess.java`：同package建立故障verifier，不改正式預設路徑。
- `src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferGameTests.java`：真server player、world identity與failure windows。
- `src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferProbe.java`：不同JVM default-off round-trip。
- `src/testmod/resources/fabric.mod.json`、`build.gradle`：只加入property-gated probe。
- `docs/implementation-notes/2026-09-19-m3b-server-transfer-readiness.md`：證據與限制。

---

### Task 1: Transfer authority model 與 fail-closed ordering

**Files:**
- Create: `src/main/java/dev/quantumchamber/universe/UniverseTransferPoint.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseTransferReceipt.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseTransferResult.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseTransferAuthority.java`
- Create: `src/main/java/dev/quantumchamber/universe/UniverseTransferService.java`
- Test: `src/test/java/dev/quantumchamber/universe/UniverseTransferAuthorityTest.java`

**Interfaces:**
- Consumes: `DynamicDimensionBackend.resolveActive`、`UniverseRuntimeRegistry`、既有 `SessionTransferService.move`。
- Produces: `UniverseTransferResult moveToUniverse(ServerPlayerEntity, UniverseWorldDescriptor)` 與 `UniverseTransferResult returnToSource(ServerPlayerEntity, UniverseTransferReceipt)`。

- [ ] **Step 1: 寫 authority RED tests**

```java
@Test void rejectsInactiveOrReplacedDestinationBeforeMoving() {
    assertEquals(Decision.REJECT_TARGET_NOT_ACTIVE,authority.evaluate(snapshot().targetActive(false).build()));
    assertEquals(Decision.REJECT_TARGET_IDENTITY,authority.evaluate(snapshot().targetExact(false).build()));
}

@Test void sourceSnapshotIsFrozenBeforeNativeMove() {
    assertEquals(Decision.ALLOW,authority.evaluate(snapshot().build()));
    assertEquals(SOURCE_KEY,snapshot().build().sourceKey());
    assertEquals(ENTRY_POSITION,snapshot().build().sourcePosition());
}
```

- [ ] **Step 2: 執行RED**

Run targeted `UniverseTransferAuthorityTest`；Expected compile FAIL。

- [ ] **Step 3: 實作 immutable snapshots/results**

`UniverseTransferPoint` 保存 exact `RegistryKey<World>`、position、velocity、yaw、pitch；constructor拒絕NaN/Infinity。Receipt保存player UUID、UniverseId、source、destination、destination exact key，不保存 `ServerWorld`跨重啟reference。

`UniverseTransferResult` 必有 outcome：`REJECTED_BEFORE_MOVE`、`MOVED`、`FAILED_ROLLED_BACK`、`FAILED_RECOVERY_REQUIRED`、`RETURNED`。它永遠保存frozen source；原生move曾發生時另保存actual world key/pose與rollback outcome。只有`MOVED`含success receipt；不得用empty/false遺失post-move evidence。

`UniverseTransferAuthority.Snapshot` 只含不可變值：server-thread、player UUID identity、source exact、target ACTIVE/exact、target FULL/safe、source key/pose。`evaluate` 固定依此順序回明確拒絕原因或 `ALLOW`，讓 unit tests 不需mock `MinecraftServer`／`ServerPlayerEntity`。

- [ ] **Step 4: 實作 transfer guard**

順序：server thread → player manager exact identity → source live map exact identity → backend ACTIVE exact destination → same XYZ clamp/validate → explicit target chunk fixture已FULL → freeze source → `SessionTransferService.move` → actual destination world/player/pose/bbox驗證 → result。移動前失敗回`REJECTED_BEFORE_MOVE`與source snapshot；若native move後驗證失敗，只有source exact identity仍成立才立即回滾並回`FAILED_ROLLED_BACK`，否則回`FAILED_RECOVERY_REQUIRED`並附actual point。

- [ ] **Step 5: 實作 return guard**

Receipt player UUID必須相同、source world exact live、destination仍為player actual world；原生move回source後再驗world/pose/velocity/yaw/pitch。成功回`RETURNED`；任何失敗回含source/actual/rollback狀態的明確outcome，不偽造returned。

- [ ] **Step 6: tests/review/commit**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' test --tests 'dev.quantumchamber.universe.UniverseTransferAuthorityTest'
git add src/main/java/dev/quantumchamber/universe src/test/java/dev/quantumchamber/universe/UniverseTransferAuthorityTest.java
git commit -m "feat: guard native universe player transfers"
```

---

### Task 2: 真 server player round-trip 與 failure windows

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferGameTests.java`
- Create: `src/testmod/java/dev/quantumchamber/universe/UniverseTransferTestAccess.java`
- Modify: `src/testmod/resources/fabric.mod.json`

**Interfaces:**
- Consumes: M3-A ACTIVE world、Task 1 transfer service。
- Produces: GameTests for success、replaced destination、target not FULL、post-move verification failure、return source replacement。

- [ ] **Step 1: 建立真player fixture RED test**

沿用既有testmod native connection/player fixture pattern，建立一位由 `PlayerManager`擁有的 `ServerPlayerEntity`；不是mock。來源為vanilla Overworld，目的為M3-A dynamic world。

- [ ] **Step 2: 成功往返 assertions**

斷言進入後：同一player object、player manager exact identity、destination `getEntity(uuid)==player`、world instance/key、position/velocity/yaw/pitch與bbox安全；返回後完整對稱。

- [ ] **Step 3: failure window assertions**

- destination map被replacement：移動前拒絕。
- target chunk非FULL：不得強載、不得移動。
- native move後驗證故障：`UniverseTransferTestAccess` 透過package-private verifier seam只把post-move verification改成false，要求立即回source；不修改原生teleport結果。
- source在return前被replacement：不得送往同key不同instance，回`FAILED_RECOVERY_REQUIRED`並保留destination player與source/actual evidence。

- [ ] **Step 4: run full GameTests**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' runGameTest
```

Expected: 新舊全部PASS；無未具名force-load或player duplication。

- [ ] **Step 5: 兩階段review、commit**

```powershell
git add src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferGameTests.java src/testmod/java/dev/quantumchamber/universe/UniverseTransferTestAccess.java src/testmod/resources/fabric.mod.json
git commit -m "test: verify native universe player round trips"
```

---

### Task 3: Different-JVM transfer probe 與 M3-B final gate

**Files:**
- Create: `src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferProbe.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `build.gradle`
- Create: `.superpowers/sdd/2026-09-19-m3b-server-transfer-readiness/run-transfer-probe.ps1`
- Create: `.superpowers/sdd/2026-09-19-m3b-server-transfer-readiness/m3b-final-report.md`
- Create: `docs/implementation-notes/2026-09-19-m3b-server-transfer-readiness.md`

**Interfaces:**
- Produces: exact M3-B commit、round-trip receipt、normal stop與known client limitation。

- [ ] **Step 1: default-off RED probe**

Fresh owned server由catalog重建alternate world，建立native server player fixture與safe target，執行Overworld→alternate→Overworld。每段都原生save player NBT並驗Dimension key；缺property時entrypoint完全不動作。

- [ ] **Step 2: 執行一次正式probe**

保存PID、StartTime、startupNonce、world identities、player UUID、兩段pose、正式player NBT、catalog/region hash、stdout/stderr、normal stop。不得重跑同nonce。

- [ ] **Step 3: 無快取全回歸**

```powershell
& 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test runGameTest build --rerun-tasks
```

再重跑M3-A Gate A/B於另一fresh root，確認transfer changes未破壞lifecycle。

- [ ] **Step 4: 文件與宣告邊界**

文件只宣告server-side transfer readiness；明列真client sync、候選門、collapse、Projection、family、正式passage與renderer仍未完成。

- [ ] **Step 5: final independent reviews**

M3-B固定commit range做spec compliance與quality review；Critical/Important為零才可宣稱整體M3自動gate完成。人工client仍保留給M5前置驗證。

- [ ] **Step 6: commit，不push**

```powershell
git add build.gradle src/testmod/java/dev/quantumchamber/gametest/M3UniverseTransferProbe.java src/testmod/resources/fabric.mod.json docs/implementation-notes/2026-09-19-m3b-server-transfer-readiness.md
git commit -m "docs: record M3-B transfer readiness evidence"
```
