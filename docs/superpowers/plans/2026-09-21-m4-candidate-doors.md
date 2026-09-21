# M4 Candidate Doors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Every task must complete RED → GREEN → focused regression → spec review → code-quality review → commit before the next task starts.

**Goal:** 為 M2 左右走廊的每個完整邏輯側門建立跨 page recycle、server restart 與多人互動皆穩定的候選權威，並以 checked `SELECTED` receipt 原子鎖定第一扇被接受的門，作為 M5 collapse／passage 的唯一輸入。

**Architecture:** `DoorKey` 只描述 session、signed logical station 與牆面；`CandidateEntropyState` 提供 save-level 256-bit secret，`CandidateResolver` 以 domain-separated HMAC-SHA256 配合凍結的 `CandidatePolicySnapshot` 與 `UniverseDiscoveryState` 產生候選。候選與選擇寫入 schema 3 `SessionRecoveryRecord`，由 `CandidateLedgerService` 完成 commit-before-expose；`CorridorPageManager` 只保存由 flushed journal 投影出的可重建 physical selectable index。第一個合法互動以 server-thread CAS 同時提交 `SELECTED` 與 `MEASURED`，M4 不開門、不配置 Universe、不傳送玩家。

**Tech Stack:** Java 21、Minecraft 1.21、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、Fabric Loom 1.7.4、JDK `SecureRandom`／`HmacSHA256`／`MessageDigest`、NBT checked persistence、JUnit 5、Fabric GameTest、PowerShell cross-JVM probes。

**Spec:** `docs/superpowers/specs/2026-09-21-m4-candidate-doors-design.md`

## Global Constraints

- M4 只能建立候選與 checked selection；不得 materialize／load／unload 新 `ServerWorld`，不得配置永久 `UniverseId` 給 `NEW`，不得建立 Projection、collapse、passage、teleport、client packet或renderer。
- 所有候選、ledger、selection與physical selectable index mutation只可在server thread發生；任何checked write／readback失敗都必須fail closed並安全返還。
- Gameplay lookup只信 `SessionRecoveryState.flushedRecords()`；current dirty record不得使門可選，也不得讓UI回報選擇成功。
- `DoorKey` 不得包含physical position、page、slot、mapping epoch、anchor或facing；signed logical station使用`long`與`Math.floorDiv`。
- 每session candidate上限16,384筆、每tick最多解析256個新DoorKey；超限不得刪除、覆用或重抽舊candidate。
- `SOURCE`指凍結的session source family，不是 `quantumchamber:superposition`；`DISCOVERED`排除current且空pool回退`NEW`。
- M4 v1 `NEW`只允許 `VANILLA_OVERWORLD_SHARED_SEED_V1`／profile version 1；M4不得呼叫 `UniverseRegistry.allocate` 或 `DynamicDimensionBackend.materialize`。
- `MEASURED`必與`SELECTED`同時存在；選擇後側門保持closed、玩家與走廊不移動、comparator不得使用passage-ready值15。
- schema 1／2仍strict read且只走既有return-only recovery；不得猜測或補造candidate。
- 不合併`main`。只有各task review、M4 whole-branch review與所有自動gate為綠，才可宣告M4完成並開始M5。

## Review Focus

以下五項是容易被一般happy-path覆蓋漏掉的高風險輸入／失敗窗口；每項都綁定到擁有它的task測試，review不得只靠文件宣告：

1. **負logical station與Java整數除法差異**：Task 1以 `-1`、`-8`、`-9` 與兩側牆驗證 `Math.floorDiv`、25格normalize與physical page independence。
2. **entropy遺失時被悄悄重生**：Task 2建立schema3 evidence後刪除／截斷entropy檔，驗證第二JVM fail closed且檔案沒有被覆寫。
3. **discovered pool順序或current重複造成權重漂移**：Task 3用反向插入順序、相同ordinal tie、watermark、current排除與空pool驗證固定5/20/75語意。
4. **current dirty selection被誤當durable成功**：Task 5／7注入flush與readback故障，驗證門未publish、未顯示成功、第二互動不能看到未落盤`SELECTED`。
5. **MEASURED recovery被既有release路徑刪除receipt**：Task 8跨JVM驗證玩家與幾何已安全返還，但schema3 record、candidate ledger與selection receipt仍完整保留且Chamber被封鎖給M5接管。

---

## File Map

### Candidate domain／persistence

- `src/main/java/dev/quantumchamber/corridor/DoorKey.java`：stable logical door identity與`DoorWallSide`。
- `src/main/java/dev/quantumchamber/candidate/CandidateBytes.java`：32-byte defensive value與constant-time equality。
- `src/main/java/dev/quantumchamber/candidate/CandidateId.java`：候選識別。
- `src/main/java/dev/quantumchamber/candidate/NewUniverseIntent.java`：allocation token、profile與seed material。
- `src/main/java/dev/quantumchamber/candidate/QuantumCandidate.java`：`SOURCE`／`EXISTING`／`NEW` sealed hierarchy。
- `src/main/java/dev/quantumchamber/candidate/SourceFamilyRef.java`：vanilla或catalog source family。
- `src/main/java/dev/quantumchamber/candidate/CandidatePolicySnapshot.java`：活動session凍結policy。
- `src/main/java/dev/quantumchamber/candidate/CandidateEntropyState.java`：checked save secret ownership。
- `src/main/java/dev/quantumchamber/candidate/CandidateDerivation.java`：canonical HMAC輸入與domain streams。
- `src/main/java/dev/quantumchamber/candidate/UniverseDiscoveryRecord.java`、`UniverseDiscoveryState.java`：discovery/gameplay eligibility authority。
- `src/main/java/dev/quantumchamber/candidate/CandidateResolver.java`：純候選決策。
- `src/main/java/dev/quantumchamber/candidate/CandidateLedgerEntry.java`、`CandidateSelection.java`：journal domain model。
- `src/main/java/dev/quantumchamber/candidate/CandidateLedgerService.java`：batch commit-before-expose與selection CAS。

### Existing runtime integration

- `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`：schema3 candidate context、ledger與selection strict codec。
- `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`：schema3 envelope、monotonic authority與checked flush/readback。
- `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`：MEASURED保留式安全返還。
- `src/main/java/dev/quantumchamber/superposition/SessionState.java`：新增`MEASURED`。
- `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`：session建立policy context、selection後runtime freeze。
- `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`：完整門掃描、flushed selectable index、operation guard與remap freeze。
- `src/main/java/dev/quantumchamber/chamber/QuantumBulkheadBlock.java`：side-door interaction先行routing。
- `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`：candidate/discovery lifecycle attach順序。

### Tests／evidence

- `src/test/java/dev/quantumchamber/corridor/DoorKeyTest.java`
- `src/test/java/dev/quantumchamber/candidate/CandidateEntropyStateTest.java`
- `src/test/java/dev/quantumchamber/candidate/CandidateDerivationTest.java`
- `src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`
- `src/test/java/dev/quantumchamber/candidate/UniverseDiscoveryStateTest.java`
- `src/test/java/dev/quantumchamber/persistence/SessionRecoverySchema3Test.java`
- `src/test/java/dev/quantumchamber/candidate/CandidateLedgerServiceTest.java`
- `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`
- `src/testmod/java/dev/quantumchamber/gametest/M4CandidateRecoveryProbe.java`
- `.superpowers/sdd/2026-09-21-m4-candidate-doors/run-m4-recovery-probe.ps1`
- `docs/implementation-notes/2026-09-21-m4-candidate-doors.md`

---

### Task 1: Stable DoorKey 與完整門幾何

**Files:**
- Modify: `src/main/java/dev/quantumchamber/corridor/DoorKey.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorGeometry.java`
- Create: `src/test/java/dev/quantumchamber/corridor/DoorKeyTest.java`
- Modify: existing callers/tests referencing `DoorKey.Side`

**Interfaces:**

```java
public record DoorKey(UUID sessionUuid, long logicalStationIndex, DoorWallSide wallSide) {
    public enum DoorWallSide { NEGATIVE_LATERAL, POSITIVE_LATERAL }
    public static DoorKey fromBlock(UUID sessionUuid, long blockLogicalZ, DoorWallSide wallSide);
}

public static Optional<DoorKey> sideDoorKey(
        UUID sessionUuid, MappingView mapping, SessionSemantics semantics, BlockPos physicalPos);
public static List<LogicalAddress> sideDoorCells(long logicalStationIndex, DoorWallSide wallSide);
```

- [ ] **Step 1: 寫RED tests**

  驗證`logicalStationIndex`為`Math.floorDiv(blockLogicalZ, 8)`；至少覆蓋`-9→-2`、`-8→-1`、`-1→-1`、`0→0`、`7→0`、`8→1`。驗證station內`z=+1..+5`、`y=1..5`、local x=0/6共25格都normalize到同一key；框架、地板、天花板、入口牆與門外格回empty。

- [ ] **Step 2: 執行RED**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.corridor.DoorKeyTest'
```

Expected：`DoorWallSide`／`fromBlock`／完整門API尚不存在而compile fail。

- [ ] **Step 3: 實作stable identity**

  將`Side.LEFT/RIGHT`改為`DoorWallSide.NEGATIVE_LATERAL/POSITIVE_LATERAL`，欄位改名`logicalStationIndex`。`DoorKey`只接受non-null session與side；不加入physical metadata。用既有`CorridorBasis`／`CorridorGeometry`將physical block轉logical cell；兩個不同slot、epoch、anchor與四個facing對同一logical cell必產生相同key。

- [ ] **Step 4: 加入complete-door純判定**

  `sideDoorCells`固定回25個canonical sorted logical cells，供後續mapping completeness判斷；此task不讀world、不建立selectable index。

- [ ] **Step 5: GREEN、focused regression、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.corridor.DoorKeyTest' --tests 'dev.quantumchamber.corridor.CorridorLayoutTest' --tests 'dev.quantumchamber.corridor.CorridorBasisTest'
git diff --check
git add src/main/java/dev/quantumchamber/corridor src/test/java/dev/quantumchamber/corridor
git commit -m "refactor: stabilize logical corridor door keys"
```

Task review必確認Review Focus 1、舊LEFT/RIGHT引用歸零，以及identity沒有physical欄位。

---

### Task 2: Checked Candidate Entropy 與 HMAC衍生

**Files:**
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateBytes.java`
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateEntropyState.java`
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateDerivation.java`
- Create: `src/test/java/dev/quantumchamber/candidate/CandidateEntropyStateTest.java`
- Create: `src/test/java/dev/quantumchamber/candidate/CandidateDerivationTest.java`

**Interfaces:**

```java
public final class CandidateEntropyState {
    public static CandidateEntropyState loadOrCreate(Path saveRoot, boolean m4EvidenceExists);
    public CandidateBytes entropyFingerprint();
    CandidateBytes derive(DerivationDomain domain, DoorKey key, int candidateSchemaVersion);
}

public enum DerivationDomain {
    CANDIDATE_ID, BUCKET, EXISTING_INDEX, ALLOCATION_TOKEN, GENERATION_SEED_MATERIAL
}
```

- [ ] **Step 1: 寫固定向量RED tests**

  以固定32-byte secret、固定UUID、signed station與兩wall side建立HMAC vectors；測試canonical big-endian byte layout、domain separation、defensive copy、32-byte長度與`MessageDigest.isEqual`語意。Expected values直接寫成hex constant，不能由production helper在測試中反算自己。

- [ ] **Step 2: 寫persistence RED tests**

  使用temp save root驗首次產生、same-directory temp＋atomic replace後readback、第二次exact reload、檔案截斷／型別錯誤／schema錯誤拒絕。建立`m4EvidenceExists=true`後缺檔必拋checked unhealthy exception且不得生成新檔。

- [ ] **Step 3: 執行RED**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.candidate.CandidateEntropyStateTest' --tests 'dev.quantumchamber.candidate.CandidateDerivationTest'
```

- [ ] **Step 4: 實作checked state**

  檔案固定為`<save>/data/quantumchamber_candidate_entropy.dat`，schema 1與exact 32-byte payload。只在沒有任何schema3 candidate/discovery evidence時用`SecureRandom`產生；沿用`SessionJournalStore`的atomic durability pattern，但不得把secret寫log、exception或session NBT。

- [ ] **Step 5: 實作canonical HMAC**

  固定encode順序：domain UTF-8長度+內容、UUID MSB/LSB、signed station long、side byte、candidate schema int。`BUCKET`從unsigned digest取mod 100；existing index使用獨立digest與無偏或明確unsigned remainder，不重用bucket bytes。

- [ ] **Step 6: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.candidate.*'
git diff --check
git add src/main/java/dev/quantumchamber/candidate src/test/java/dev/quantumchamber/candidate
git commit -m "feat: persist candidate entropy and derivation"
```

Task review必確認Review Focus 2、沒有`Random`／`hashCode`／time seed、secret沒有可列印`toString`。

---

### Task 3: Discovery Authority、Policy Snapshot 與 Candidate Resolver

**Files:**
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateId.java`
- Create: `src/main/java/dev/quantumchamber/candidate/SourceFamilyRef.java`
- Create: `src/main/java/dev/quantumchamber/candidate/NewUniverseIntent.java`
- Create: `src/main/java/dev/quantumchamber/candidate/QuantumCandidate.java`
- Create: `src/main/java/dev/quantumchamber/candidate/CandidatePolicySnapshot.java`
- Create: `src/main/java/dev/quantumchamber/candidate/UniverseDiscoveryRecord.java`
- Create: `src/main/java/dev/quantumchamber/candidate/UniverseDiscoveryState.java`
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateResolver.java`
- Create: `src/test/java/dev/quantumchamber/candidate/CandidateResolverTest.java`
- Create: `src/test/java/dev/quantumchamber/candidate/UniverseDiscoveryStateTest.java`

**Interfaces:**

```java
public sealed interface QuantumCandidate permits SourceCandidate, ExistingCandidate, NewCandidate {
    CandidateId candidateId();
}

public record NewUniverseIntent(
        CandidateBytes allocationToken,
        GeneratorProfile profile,
        int profileVersion,
        CandidateBytes generationSeedMaterial) {}

public record CandidatePolicySnapshot(
        Identifier policyId, int policyVersion, int candidateSchemaVersion,
        int sourceWeight, int discoveredWeight, int newWeight,
        long discoveryWatermark, int maxCandidateEntries,
        CandidateBytes entropyFingerprint, SourceFamilyRef sourceFamilyRef) {}

public QuantumCandidate resolve(
        DoorKey doorKey, CandidatePolicySnapshot policy, List<UniverseDiscoveryRecord> eligiblePool);
```

- [ ] **Step 1: 寫domain validation RED tests**

  覆蓋exact 32-byte candidate/token/seed、defensive copies、SOURCE無UniverseId、EXISTING要求有效`UniverseId`、NEW只接受`VANILLA_OVERWORLD_SHARED_SEED_V1`與version 1。`SourceFamilyRef.VANILLA`需保存world key與`DimensionRole`；`CATALOG`保存UniverseId與role但不放寬runtime activation。

- [ ] **Step 2: 寫policy／pool RED tests**

  驗sum=100、固定5/20/75、watermark、cap=16384、entropy fingerprint。Discovery state用monotonic ordinal、reject duplicate UniverseId／ordinal regression，並提供canonical eligible snapshot：`gameplayEligible && ordinal<=watermark && id!=source`，sort by ordinal then UniverseId。

- [ ] **Step 3: 寫resolver RED tests**

  覆蓋bucket 0/4→SOURCE、5/24→EXISTING、25/99→NEW；空eligible pool時5..24回NEW而非SOURCE。以反向insert順序與相同ordinal tie驗結果不變；profile/token/seed與candidate id跨resolver instance相同。

- [ ] **Step 4: 實作checked discovery store與resolver**

  `UniverseDiscoveryState`為獨立checked authority，不從`UniverseRegistry.records()`自動匯入。M4只提供strict load/save與snapshot；正式discovery mutation留給M5成功collapse。Resolver不得呼叫Universe allocation/materialization API。

- [ ] **Step 5: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.candidate.CandidateResolverTest' --tests 'dev.quantumchamber.candidate.UniverseDiscoveryStateTest'
git diff --check
git add src/main/java/dev/quantumchamber/candidate src/test/java/dev/quantumchamber/candidate
git commit -m "feat: resolve durable quantum candidates"
```

Task review必確認Review Focus 3，以及production code沒有把catalog當discovered pool。

---

### Task 4: Session Journal Schema 3 Strict Codec

**Files:**
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateLedgerEntry.java`
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateSelection.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SessionState.java`
- Create: `src/test/java/dev/quantumchamber/persistence/SessionRecoverySchema3Test.java`
- Modify: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStateTest.java`
- Modify: `src/test/java/dev/quantumchamber/persistence/SessionJournalStoreTest.java`

**Interfaces:**

```java
public sealed interface CandidateSelection {
    record Selectable() implements CandidateSelection {}
    record Selected(DoorKey doorKey, CandidateId candidateId, UUID selectedBy,
                    long selectedAtGameTime, int selectionRevision) implements CandidateSelection {}
}

public record SessionRecoveryRecord(
    /* existing fields */,
    Optional<CandidatePolicySnapshot> candidateContext,
    List<CandidateLedgerEntry> candidateLedger,
    Optional<CandidateSelection> candidateSelection) {}
```

- [ ] **Step 1: 寫schema3 round-trip RED tests**

  建立SOURCE／EXISTING／NEW ledger與SELECTABLE／SELECTED fixtures；驗canonical station+side排序、exact bytes、SourceFamilyRef兩variant、empty與non-empty typed list、完整round-trip equality。

- [ ] **Step 2: 寫strict rejection RED tests**

  逐項破壞schema、held list type、32-byte lengths、unknown enum/profile/policy/version、weights、duplicate DoorKey、duplicate CandidateId、selection missing ledger、CandidateId mismatch、`SUPERPOSITION+SELECTED`、`MEASURED+SELECTABLE`；全部必拒絕整份state，不可忽略壞entry。

- [ ] **Step 3: 驗schema1／2 compatibility**

  既有fixtures strict load後candidate欄位empty，state語意不變；寫新record固定schema3。legacy fixture hash／return-only tests保持通過。

- [ ] **Step 4: 實作codec與MEASURED state**

  新增`SessionState.MEASURED`。保留舊constructor作明確legacy adapter，正式新session constructor必要求CandidateContext與SELECTABLE。所有list／byte array／enum使用strict helpers；不接受unknown optional payload。

- [ ] **Step 5: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.persistence.SessionRecoverySchema3Test' --tests 'dev.quantumchamber.persistence.SessionRecoveryStateTest' --tests 'dev.quantumchamber.persistence.SessionJournalStoreTest'
git diff --check
git add src/main/java/dev/quantumchamber/candidate src/main/java/dev/quantumchamber/persistence src/main/java/dev/quantumchamber/superposition/SessionState.java src/test/java/dev/quantumchamber/persistence
git commit -m "feat: persist schema three candidate sessions"
```

Task review必檢查所有invalid state組合fail closed，以及schema1/2沒有被推測性升級。

---

### Task 5: Monotonic Ledger Authority 與 Checked Selection CAS

**Files:**
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateLedgerService.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryRecord.java`
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryState.java`
- Create: `src/test/java/dev/quantumchamber/candidate/CandidateLedgerServiceTest.java`
- Modify: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryStateTest.java`

**Interfaces:**

```java
public record CandidateBatchResult(List<DoorKey> committedKeys, boolean sessionFailed) {}
public enum SelectionOutcome { SELECTED, ALREADY_SELECTED, NOT_SELECTABLE, REJECTED }

public CandidateBatchResult commitCandidates(
        MinecraftServer server, UUID sessionUuid, List<DoorKey> completeKeys);
public SelectionOutcome trySelect(
        MinecraftServer server, UUID sessionUuid, DoorKey doorKey, UUID playerUuid, long gameTime);
```

- [ ] **Step 1: 寫append-only RED tests**

  `sameAuthority`與`put`允許只追加canonical entries，不允許刪除、改candidate、換context、降低cap／watermark或改selection。測試current record與flushed record都會限制下一次put；同一state中的remove→reinsert相同session UUID不得重設authority history。

- [ ] **Step 2: 寫batch commit RED tests**

  輸入去重、canonical排序、reuse existing、最多256個新keys、總量最多16384。一次batch只呼叫一次flush，flush後必從`flushedRecords()`strict readback再回傳`committedKeys`。注入flush/readback failure時回session failure，沒有committed key。

- [ ] **Step 3: 寫selection CAS RED tests**

  SELECTABLE + flushed candidate才可轉`MEASURED+SELECTED`；candidate／player／time／revision固定。第一個成功、第二個`ALREADY_SELECTED`。注入`put`、flush、readback窗口失敗時不得回`SELECTED`，不得讓current dirty selection成為gameplay truth。

- [ ] **Step 4: 實作monotonic authority與service**

  `CandidateLedgerService`只接受server thread；derivation與batch組裝可純運算，但journal mutation必在owner thread。保留本process authority history阻止remove/reinsert；production session UUID只由fresh random建立，M4 measured UUID永不remove。陣列與ledger皆immutable defensive copy。

- [ ] **Step 5: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.candidate.CandidateLedgerServiceTest' --tests 'dev.quantumchamber.persistence.SessionRecoveryStateTest'
git diff --check
git add src/main/java/dev/quantumchamber/candidate src/main/java/dev/quantumchamber/persistence src/test/java/dev/quantumchamber/candidate src/test/java/dev/quantumchamber/persistence
git commit -m "feat: commit monotonic candidate selections"
```

Task review必確認Review Focus 4的unit fault windows與真正checked readback，不接受僅驗`put`成功。

---

### Task 6: Candidate Context 建立與 Complete-Door Commit-Before-Expose

**Files:**
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSession.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`

**Interfaces:**

```java
public Optional<DoorKey> selectableDoor(ServerWorld world, BlockPos pos);
public Optional<QuantumCandidate> flushedCandidate(DoorKey key);
public Set<DoorKey> selectableDoors(UUID sessionUuid);
```

- [ ] **Step 1: 寫GameTest RED cases**

  四個source facing、正負station、兩牆面，25格normalize為同DoorKey。Incomplete door、pending mapping、retiring mapping、lease未FULL/ticking、geometry build未完成都不可選。完整committed門在ledger flush前不可選，flush readback後才出現在physical index。

- [ ] **Step 2: 寫page lifecycle RED cases**

  page recycle、split、merge與96-block seam後，同DoorKey candidate保持完全相同；old/new mapping切換期間不可同時擁有兩個physical selectable owner。物理index移除不得刪ledger。

- [ ] **Step 3: 建立session CandidateContext**

  新session在ARMING journal初次checked write前取得entropy與discovery snapshot，凍結`quantumchamber:m4_v1`、5/20/75、watermark、cap、fingerprint與vanilla `SourceFamilyRef`。若任一authority不健康，start回REJECTED且不建立session／space。

- [ ] **Step 4: 接入batch resolver與selectable projection**

  `CorridorPageManager.tick`在幾何與mapping committed後收集完整門，每tick至多256個missing keys，交給唯一`CandidateLedgerService`做一次checked batch，再從flushed ledger建立index。pending／retiring／releasing／failed／operations>0／非SUPERPOSITION都禁止publish。

- [ ] **Step 5: 驗證無Universe副作用**

  GameTest前後比較Universe catalog records、dynamic runtime handles、server worlds與player world；candidate materialization不得增加任何一項。

- [ ] **Step 6: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.candidate.*' --tests 'dev.quantumchamber.persistence.SessionRecoverySchema3Test'
./gradlew.bat runGameTest --rerun-tasks
git diff --check
git add src/main/java/dev/quantumchamber src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java
git commit -m "feat: expose checked corridor candidates"
```

Task review必確認physical index只是flushed projection、batch limits生效且沒有M3 backend gameplay call。

---

### Task 7: Bulkhead Interaction、First-Wins Selection 與 MEASURED Freeze

**Files:**
- Create: `src/main/java/dev/quantumchamber/candidate/CandidateDoorInteraction.java`
- Modify: `src/main/java/dev/quantumchamber/chamber/QuantumBulkheadBlock.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorRepositionService.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`

**Interfaces:**

```java
public Optional<ActionResult> onBulkheadUse(
        ServerWorld world, BlockPos pos, ServerPlayerEntity player);
```

- [ ] **Step 1: 寫interaction RED cases**

  合法participant右鍵任一25格可normalize並選中；outsider、spectator、錯world、bbox不在session、錯mapping、incomplete/pending/retiring、buff失效、RETURNING、failed、未flushedcandidate全部拒絕。非走廊側門回empty，讓既有Origin／入口門流程繼續。

- [ ] **Step 2: 寫多人first-wins RED case**

  兩位participant同server tick依序互動不同門；第一個取得`SELECTED`，第二個只得`ALREADY_SELECTED`。Journal readback必為`MEASURED+SELECTED`且selectedBy／time／door／candidate固定。

- [ ] **Step 3: 寫freeze與side-effect RED cases**

  選擇後新candidate batch、prepare/begin/commit remap與所有door interaction皆拒絕；現有door block states維持`OPEN=false`、玩家pose/world不變、corridor mappings不變、catalog/world count不變、comparator不變為15。

- [ ] **Step 4: 實作server-first routing與operation guard**

  `QuantumBulkheadBlock.onUse`先交給`CandidateDoorInteraction`；client只回對應consume/success預測，不持有authority。Interaction在同一session operation guard內重新讀flushed record與latest mapping，再呼叫checked CAS；成功readback後才發actionbar「量子候選已鎖定，等待塌縮」。失敗窗口不得顯示成功。

- [ ] **Step 5: 實作MEASURED freeze**

  `CorridorPageManager`與`CorridorRepositionService`對MEASURED不再建頁、解析candidate或remap；保留現有lease、ticket、cohort與protection等待M5。`SuperpositionSessionManager.presence`將MEASURED視為已被session占用，不可重新啟動。

- [ ] **Step 6: GREEN、review、commit**

```powershell
./gradlew.bat test
./gradlew.bat runGameTest --rerun-tasks
git diff --check
git add src/main/java/dev/quantumchamber src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java
git commit -m "feat: atomically select corridor candidates"
```

Task review必確認Review Focus 4在live interaction成立，且沒有在M4開門、teleport或呼叫Universe allocation。

---

### Task 8: MEASURED Crash Recovery 與 Durable Receipt Retention

**Files:**
- Modify: `src/main/java/dev/quantumchamber/persistence/SessionRecoveryManager.java`
- Modify: `src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java`
- Modify: `src/main/java/dev/quantumchamber/superposition/SuperpositionSessionManager.java`
- Modify: `src/test/java/dev/quantumchamber/persistence/SessionRecoveryManagerTest.java`
- Create: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateRecoveryProbe.java`
- Modify: `src/testmod/resources/fabric.mod.json`
- Modify: `build.gradle`
- Create: `.superpowers/sdd/2026-09-21-m4-candidate-doors/run-m4-recovery-probe.ps1`

**Interfaces:**

```java
public enum MeasuredRecoveryPhase { RETURN_PLAYERS, RELEASE_GEOMETRY, RETAIN_RECEIPT, DORMANT }
public boolean measuredRecoveryComplete(UUID sessionUuid);
```

- [ ] **Step 1: 寫pure recovery RED tests**

  schema3 SELECTABLE restart走未測量安全返還，不重抽candidate；schema3 MEASURED+SELECTED restart要求return players與release geometry，但不得`journal.remove`。LOW／buff失效觸發相同保留式return。schema1/2仍走現有return-only並可正常移除。

- [ ] **Step 2: 寫default-off cross-JVM probe**

  Phase A在fresh root建立entropy、session、flushed ledger與SELECTED receipt，記錄catalog hash、entropy hash、candidate ids與session journal hash後controlled stop。Phase B重新啟動，驗同entropy fingerprint、same candidates、same selection，玩家返原艙、corridor geometry/tickets清理、record仍存在、participants marked returned、Chamber presence仍blocked。第三次啟動再驗dormant state idempotent且不重選／不重抽。

- [ ] **Step 3: 寫故障矩陣**

  分別建立dirty candidate current、dirty selection current、entropy missing/corrupt、discovery corrupt、journal corrupt fresh roots。每個root只跑一次；只信上一份flushed authority，無transient門或selection，無Universe allocation，controlled unhealthy receipt且不得覆寫損壞檔。

- [ ] **Step 4: 實作retained measured recovery**

  將existing recovery的「玩家返還」「幾何釋放」「record刪除」拆開。MEASURED path只做前兩者並以checked update保存returned flags與原candidate/selection authority，最後進dormant；不得經`CorridorPageManager.tickRelease`的普通`journal.remove`分支。M5完成collapse或明確checked cancel前不能清除此record。

- [ ] **Step 5: 執行probe一次並固定evidence**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers/sdd/2026-09-21-m4-candidate-doors/run-m4-recovery-probe.ps1
```

任一phase失敗後不得在同root修補重跑；修code後換新nonce/root完整重跑。

- [ ] **Step 6: GREEN、review、commit**

```powershell
./gradlew.bat test --tests 'dev.quantumchamber.persistence.SessionRecoveryManagerTest' --tests 'dev.quantumchamber.persistence.SessionRecoverySchema3Test'
git diff --check
git add build.gradle src/main/java/dev/quantumchamber src/test/java/dev/quantumchamber/persistence src/testmod/java/dev/quantumchamber/gametest/M4CandidateRecoveryProbe.java src/testmod/resources/fabric.mod.json .superpowers/sdd/2026-09-21-m4-candidate-doors
git commit -m "feat: retain measured candidate receipts on recovery"
```

Task review必確認Review Focus 5，尤其「geometry清完」不等於「selection record可刪」。

---

### Task 9: M4 GameTest Coverage、Artifact Boundaries 與 Runtime Oracle

**Files:**
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`
- Modify: `src/testmod/java/dev/quantumchamber/gametest/M4CandidateRecoveryProbe.java`
- Modify: `.superpowers/sdd/2026-09-21-m4-candidate-doors/run-m4-recovery-probe.ps1`
- Create: `.superpowers/sdd/2026-09-21-m4-candidate-doors/m4-automated-gate-report.md`

- [ ] **Step 1: 補齊完整GameTest matrix**

  四facing、正負station、兩wall、25格normalize、incomplete/pending/retiring、page recycle/split/merge/seam、多人同tickfirst-wins、所有拒絕身分、closed door、remap freeze與無Universe副作用均需有具名test。測試數量與名稱寫入report，不只記總數。

- [ ] **Step 2: 強化cross-JVM runtime oracle**

  每phase使用nonce-owned root、PID、process `DateTimeOffset StartTime`與startup nonce；驗main-only Fabric mod list不含`quantumchamber-testmod`、runtime classpath不含testmod output/JAR、class-load evidence不含testmod entrypoint/classes。精確`Missing data pack quantumchamber-testmod`只列stale save metadata warning，不當成runtime load。

- [ ] **Step 3: Artifact與backend boundary audit**

  檢查release/sources JAR無testmod；搜尋M4 commit range不得新增對`UniverseRegistry.allocate`、`DynamicDimensionBackend.materialize`、`UniverseTransferService`、projection/teleport/client renderer的gameplay呼叫。執行前後固定Universe catalog與world key清單。

- [ ] **Step 4: 無快取回歸**

```powershell
./gradlew.bat clean test runGameTest build --rerun-tasks
```

另跑既有Windows checkpoint tests、M3 lifecycle/transfer probes於fresh roots；M4不得破壞M3-A/M3-B evidence contracts。

- [ ] **Step 5: review、commit**

```powershell
git diff --check
git add src/testmod .superpowers/sdd/2026-09-21-m4-candidate-doors/m4-automated-gate-report.md
git commit -m "test: prove M4 candidate door authority"
```

Task review需逐項核對report與原始log/receipt；不能把testmod-present的GameTest runtime冒充main-only證據。

---

### Task 10: Implementation Notes、Whole-Branch Review 與 M5 Handoff Gate

**Files:**
- Create: `docs/implementation-notes/2026-09-21-m4-candidate-doors.md`
- Create: `.superpowers/sdd/2026-09-21-m4-candidate-doors/m4-final-review.md`
- Modify: applicable project status/readme only when current claims require it

- [ ] **Step 1: 文件化code-truth與runtime-truth**

  記錄exact commit range、schema3、entropy/discovery位置、DoorKey公式、weights/fallback、batch/cap、selection CAS、MEASURED recovery、GameTest與cross-JVM receipts。明列M4沒有Universe allocation、門仍closed、沒有collapse/passage/teleport/renderer。

- [ ] **Step 2: Spec compliance review**

  由未實作該task的review agent對固定M4 commit range逐節核對design與本plan；任何Critical/Important必回到擁有task修正並重新跑相關gate。

- [ ] **Step 3: Code quality review**

  檢查authority ownership、thread confinement、strict codecs、immutability、bounded collections、fault windows、log secret leakage、legacy compatibility與M3 backend boundary。Critical/Important為0才可前進。

- [ ] **Step 4: Whole-branch final verification**

```powershell
git status --short
git diff --check origin/feature/m1-chamber...HEAD
./gradlew.bat clean test runGameTest build --rerun-tasks
```

  固定HEAD與所有receipt hash；確認worktree只含預期evidence，release artifact main-only。

- [ ] **Step 5: Commit reviewed docs與推送feature branch**

```powershell
git add docs/implementation-notes/2026-09-21-m4-candidate-doors.md .superpowers/sdd/2026-09-21-m4-candidate-doors/m4-final-review.md
git commit -m "docs: record M4 candidate door evidence"
git push origin feature/m1-chamber
```

- [ ] **Step 6: M5 handoff acceptance**

  M5只能從checked `MEASURED+SELECTED`開始，依candidate variant解析：SOURCE→frozen `SourceFamilyRef`、EXISTING→exact eligible `UniverseId`、NEW→checked idempotent allocation token mapping。M5在任何allocation前必先checked提交自己的`COLLAPSING` intent；不得重新抽candidate或覆寫M4 receipt。

---

## Completion Evidence

M4只在下列證據同時成立時完成：

- 每個task都有RED、GREEN、focused regression、spec review、quality review與獨立commit。
- fixed HMAC vectors、schema3 strict codec、append-only ledger、first-wins CAS與MEASURED retention純測試全綠。
- M4 GameTests完整覆蓋door geometry、page lifecycle、多人競爭、拒絕矩陣與無Universe副作用。
- fresh-root cross-JVM證明entropy/candidate/selection穩定，MEASURED安全返還但receipt保留。
- `clean test runGameTest build --rerun-tasks`綠，既有M1/M2/M3 gates未退步，release artifact無testmod。
- M4 whole-branch review Critical/Important為0，feature branch已推送但未合併main。
- 對使用者的狀態宣告仍明確：M4可選門且鎖定候選，但真正門開啟、新世界、collapse與passage尚待M5。
