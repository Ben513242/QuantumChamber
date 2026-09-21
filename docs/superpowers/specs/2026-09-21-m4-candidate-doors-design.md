# M4 Candidate Doors 設計

日期：2026-09-21。基準：`687cff95efbb2cc127083107b94f8be0f56408c4` 之後的功能分支。狀態：使用者已核准方案A、確定性HMAC候選、惰性candidate ledger、commit-before-expose、`SOURCE`不要求Universe UUID、`NewUniverseIntent` allocation token，以及checked `SELECTED` receipt；並授權M4完成review後立即接M5。

## 1. 目標與成功畫面

每個完整邏輯側門對應一個穩定 `QuantumCandidate`。同一session的所有玩家共享候選與選擇權威；任何物理page recycle、slot relocation、多人split/merge、server restart都不能讓同一 `DoorKey` 變成另一候選。

M4成功畫面：

```text
玩家進入既有M2左右走廊
→ 完整側門物化
→ candidate checked落盤後門才可選
→ 玩家可自由挑任一扇門右鍵
→ 第一個server-thread成功交易寫入SELECTED
→ 其他門與同tick後續互動得到ALREADY_SELECTED
→ 側門保持關閉、沒有Universe allocation／teleport／collapse
```

M4是權威與持久化里程碑，不是可獨立發布的passage玩法。M4 review通過後立即接M5，由M5消費 `SELECTED` receipt。

## 2. 明確不在M4

- 不配置永久 `UniverseId` 給 `NEW` candidate。
- 不建立、載入或卸載額外 `ServerWorld`。
- 不物化Chamber Projection，不覆寫目的world方塊。
- 不切換走廊、返還cohort、collapse或建立passage。
- 不新增client packet、portal surface、door preview renderer、shader或bloom。
- 不實作Nether／End family、不同seed、branch snapshot或無限pool管理。
- 不顯示Universe ID或allocation token給玩家。

所有M4測試必核對Universe catalog筆數、dynamic world數量與玩家world不因候選／選擇而改變。

## 3. Stable Door Identity

現有 `DoorKey.Side` 改為牆面語意：

```java
DoorKey(
    UUID sessionUuid,
    long logicalStationIndex,
    DoorWallSide wallSide
)

enum DoorWallSide {
    NEGATIVE_LATERAL, // corridor local x=0
    POSITIVE_LATERAL  // corridor local x=6
}
```

`logicalStationIndex = floorDiv(blockLogicalZ, 8)`，必須保留signed `long`。DoorKey不可包含physical `BlockPos`、slot、mapping epoch、page、anchor或world facing。

一扇完整門的logical cells為同一station內 `z=station*8+1..5`、`y=1..5`、指定lateral wall，共25格。只有目前committed mapping完整包含25格、其lease已loaded+ticking且沒有pending remap／retirement時，該DoorKey才可能進候選解析。任一門格右鍵都normalize到同一DoorKey。

## 4. Save-level Candidate Entropy

新增獨立checked state：

```text
<save>/data/quantumchamber_candidate_entropy.dat
SchemaVersion = 1
CandidateEntropy = exactly 32 bytes
```

第一次建立M4 session，且save中不存在schema3 session、candidate ledger或discovery資料時，以 `SecureRandom` 產生一次256-bit entropy。必須same-directory temporary、file force、atomic replace、strict readback後才可建立session。

若任何M4資料已存在而entropy檔缺失、損壞、型別錯誤或長度不是32，candidate subsystem fail closed；絕不重新生成。Entropy不寫入session、log、client或錯誤訊息。Session只保存 `SHA-256(entropy)` fingerprint，用來拒絕錯save／錯secret。

HMAC固定使用JDK `HmacSHA256` 與canonical big-endian encoding：

```text
domainTag length + UTF-8 domainTag
session UUID MSB + LSB
signed logicalStationIndex
wallSide byte
candidateSchemaVersion int
```

禁止Java `hashCode()`、`Random`、啟動時間、physical position或JVM-local entropy。不同用途使用不同domain tag，至少分為candidate id、bucket、existing index、allocation token與generation seed material。

## 5. Discovery Authority 與 Policy Snapshot

`UniverseRegistry` 是技術catalog，不代表玩家已發現。新增checked `UniverseDiscoveryState`：

```text
UniverseId
discoveryOrdinal: monotonic long
discoveredAtGameTime
firstObserver: optional player UUID
gameplayEligible: boolean
```

M4不因catalog已有record自動標為discovered。M5成功collapse後才追加／更新discovery record。

Session建立時凍結：

```text
CandidatePolicySnapshot
  policyId = quantumchamber:m4_v1
  policyVersion = 1
  candidateSchemaVersion = 1
  sourceWeight = 5
  discoveredWeight = 20
  newWeight = 75
  discoveryWatermark = current max discoveryOrdinal
  maxCandidateEntries = 16384
  entropyFingerprint
  sourceFamilyRef
```

權重來源可由server datapack/resource policy載入，但必須sum=100且M4 v1只允許上述profile集合；值被完整複製到session，reload或datapack reload不得改活動session。

Eligible discovered pool：

```text
gameplayEligible
AND discoveryOrdinal <= frozen watermark
AND UniverseId != source UniverseId（若source具有UniverseId）
```

依 `discoveryOrdinal, UniverseId` canonical排序。Bucket語意：

```text
0..4   SOURCE
5..24  EXISTING；pool空時改為NEW
25..99 NEW
```

20%空pool不得回流SOURCE。選existing index使用獨立HMAC stream，不能因HashMap順序改變。

## 6. Source Family Reference

`SOURCE` candidate不要求 `UniverseId`。Session凍結：

```text
SourceFamilyRef =
  VANILLA(worldKey, DimensionRole)
  或 CATALOG(UniverseId, DimensionRole)
```

M4 v1的實際M2來源仍只允許既有vanilla Origin；`CATALOG`型別先保留strict codec與pure tests，不放寬M1/M2 activation接受dynamic Origin。SOURCE candidate本身不重複保存來源payload，而是引用同一session的immutable `SourceFamilyRef`。

## 7. QuantumCandidate Schema

每個候選都有32-byte `CandidateId`：

```text
QuantumCandidate =
  SOURCE(candidateId)

  EXISTING(candidateId, UniverseId)

  NEW(candidateId, NewUniverseIntent)

NewUniverseIntent
  allocationToken: exactly 32 bytes
  profile: VANILLA_OVERWORLD_SHARED_SEED_V1
  profileVersion: 1
  generationSeedMaterial: exactly 32 bytes
```

M4不將allocation token登記到Universe catalog，也不配置UUID。M5必新增 `AllocationToken -> UniverseId` checked idempotency authority；相同token永遠回同一UniverseId。

M4 v1不產生different-seed、mutated、branch-copy或backend未證profile。`generationSeedMaterial`仍先凍結供未來profile使用，但M5 v1只能materialize已證明的shared-seed Overworld profile。

## 8. Session Journal Schema 3

`quantumchamber_sessions.dat` 新寫schema3；schema1／2 strict讀取保持原return-only契約，不做候選推測性migration。

Schema3在既有session authority旁新增：

```text
CandidateContext = CandidatePolicySnapshot

CandidateLedger = [
  {
    LogicalStationIndex: long,
    WallSide: NEGATIVE_LATERAL | POSITIVE_LATERAL,
    Candidate: strict QuantumCandidate
  }
]

Selection =
  SELECTABLE
  或 SELECTED {
    DoorKey,
    CandidateId,
    SelectedBy: player UUID,
    SelectedAtGameTime: long,
    SelectionRevision: 1
  }
```

Ledger canonical排序為station index後wall side。即使list為空，也必驗canonical held type；byte array長度、enum、weights、watermark、policy/profile/version、source ref、duplicate DoorKey／CandidateId全部strict驗證。

候選ledger是append-only：既有DoorKey不可移除或改Candidate。Selection只允許 `SELECTABLE -> SELECTED` 一次；DoorKey必存在ledger且CandidateId exact match。SELECTED receipt不可更換player、時間、door或candidate。

`SessionRecoveryRecord.sameAuthority`／`SessionRecoveryState.put` 必保護CandidateContext immutable、ledger單調增長與Selection單向轉移；remove→reinsert不能繞過上一份flushed authority。

Operational cap為每session 16,384個candidate entries；cap值凍結在CandidateContext。超限不得丟舊candidate或循環使用DoorKey，必fail session並走安全返還。這是資源安全上限，不改邏輯station identity。

## 9. Candidate Resolve／Expose Transaction

當完整側門進入committed mapping，`CandidateLedgerService`按batch處理；每tick最多解析256個新DoorKey，每batch只做一次checked flush。

```text
collect complete DoorKeys from committed mapping
→ reject pending／retiring／incomplete physical doors
→ reuse exact flushed candidate if present
→ deterministic resolve missing candidates
→ SessionRecoveryState.put(candidate batch)
→ checked flush
→ strict readback from flushedRecords
→ only then publish DoorKey into selectable physical index
```

物理Bulkhead可能已可見，但candidate durable前保持不可選。Flush／readback失敗時不得使用in-memory candidate；session標記failure並安全RETURNING。所有gameplay lookup只信 `flushedRecords` 與由其建立的selectable index。

Page recycle移除physical index，不刪ledger；同DoorKey再次物化必取得原candidate。Remap期間old/new mapping皆不可讓同DoorKey出現兩個可選physical owner；publish後以exact mapping epoch切換。

## 10. Door Interaction 與 Atomic Selection

`QuantumBulkheadBlock.onUse`在server端先詢問 `CandidateDoorInteraction`：

1. block必在固定Superposition world的protected session space。
2. block必normalize到目前committed、完整、selectable的side-door DoorKey。
3. player必為該session凍結participant、在線、非spectator、actual world／bbox合法。
4. session durable state必為 `SUPERPOSITION + SELECTABLE`，buff／source authority仍有效，沒有pending remap／return／failure。
5. candidate必已在flushed ledger。

非走廊側門才繼續既有Origin／入口門邏輯。

`trySelectDoor`只在server thread執行compare-and-set語意：

```text
read exact flushed SELECTABLE
→ build SELECTED receipt
→ same transaction set SessionState.MEASURED
→ journal put + checked flush + strict readback
→ freeze page remap／new candidate batches／all door interactions
→ return SELECTED
```

同tick第二個interaction看到flushed SELECTED，只能得到 `ALREADY_SELECTED`，不得改門或配置第二個候選。選擇成功前不發成功actionbar；成功後只顯示一般訊息「量子候選已鎖定，等待塌縮」，不顯示ID/profile/token。

M4不把25格Bulkhead設為OPEN；走廊、門與玩家都保持原位。Comparator不輸出15，因passage尚未ready。

## 11. MEASURED 邊界與 Crash Recovery

新增 `SessionState.MEASURED`。它表示checked SELECTED已成立、M5尚未提交collapse。M4選擇交易必同時持久化MEASURED與receipt，不允許SUPERPOSITION＋SELECTED或MEASURED＋SELECTABLE。

正常runtime進入MEASURED後：

- 停止CorridorRepositionService remap與新頁面配置。
- 停止candidate resolution與所有門互動。
- 保留現有leases／tickets／cohort與保護，等待M5 owner。
- 若LOW、buff失效或其他安全條件觸發返還，selection receipt仍不可丟失。

重啟矩陣：

| Durable狀態 | 處理 |
| --- | --- |
| schema1／2 | 既有strict return-only，無candidate推測 |
| schema3 SELECTABLE | 不重抽已存candidate；按未測量session安全返還／清理 |
| schema3 MEASURED＋SELECTED | 不重選、不改intent；先安全返還玩家與清走廊，但保留dormant session record與selection receipt，封鎖該Chamber等待M5接管 |
| candidate current dirty但未flushed | 對玩家不可用，只信上一份flushed ledger |
| selection current dirty但未flushed | 不回SELECTED；按flushed SELECTABLE處理或安全返還 |
| entropy／discovery／journal損壞 | candidate subsystem fail closed，不配置Universe |

M4的SessionRecoveryManager不得在MEASURED安全返還完成後刪除record；participants可全部標returned、geometry/tickets可清理，但凍結來源、candidate ledger與selection receipt保留。M5只有在checked collapse/passsage終局或明確安全取消receipt後才可移除。

這使以下窗口安全：

```text
SELECTED committed
→ crash
→ restart
→ same DoorKey / same Candidate / same NewUniverseIntent remains
→ M5 performs idempotent allocation or fail-closed recovery
```

## 12. Runtime Ownership 與 Limits

- `CandidateEntropyState`、`UniverseDiscoveryState`、session journal全部只由server thread mutation。
- 每個session只有一個CandidateLedger runtime owner；不得由CorridorPageManager與SessionManager各維護一份可分歧map。
- Physical selectable index屬CorridorPageManager，但內容只能由flushed ledger投影，可重建、不可成為持久權威。
- 每session最多16,384 entries；每tick最多解析256個新DoorKey；flush前不得publish。
- Door interaction期間持有session operation guard，remap／retire／return不能交錯提交。
- CandidateId、allocation token與entropy比較使用constant-time byte equality where practical；任何陣列對外均defensive copy。

## 13. 測試與 Gate

### Pure JVM

- 固定HMAC test vectors、big-endian encoding、負station與兩wall side。
- 不同physical page／slot／epoch／facing得到同DoorKey候選。
- domain separation：CandidateId／bucket／existing index／allocation token／seed material互不相同。
- 5/20/75邊界、空discovered fallback NEW、current排除、watermark與canonical pool順序。
- SOURCE不要求UniverseId；NEW token/profile/seed material跨JVM穩定。
- schema3 strict codec、empty/non-empty list held type、32-byte lengths、duplicate key／candidate、unknown enum/version拒絕。
- ledger append-only、selection CAS、MEASURED合法組合、sameAuthority與remove/reinsert反例。
- cap／batch budget與flush failure不publish。

### GameTests

- 四個source facing、正負logical station、兩牆面25格normalize到正確DoorKey。
- incomplete／pending／retiring door不可選；complete door只有checked flush後可選。
- page recycle／split／merge／96 seam後同DoorKey candidate不變。
- 兩位participant同tick不同門，server接受順序第一個勝；第二個ALREADY_SELECTED。
- outsider／spectator／錯world／錯mapping／buff失效／RETURNING拒絕。
- 選擇後門保持closed、remap凍結、catalog筆數與world數不變、無teleport／projection／collapse。
- candidate journal故障、observer故障與rollback不產生transient selectable門。

### Cross-JVM／Recovery

- Save entropy首次建立→第二JVM exact readback；遺失／損壞且已有schema3資料時拒絕再生。
- SELECTABLE ledger跨JVM exact；物理page不同仍同candidate。
- MEASURED＋SELECTED crash後安全返還、geometry清理、record／receipt保留、Chamber封鎖。
- Dirty current窗口只使用flushed authority；第二次restart不重抽、不重選。
- schema1／2 fixtures保持既有return-only與原hash語意。

### 完整 regression／artifact

- fresh `clean test runGameTest build --rerun-tasks`。
- Windows checkpoint必要tests與既有118 GameTests不得退步。
- main-only空save不會因M4建立Universe；release／sources JAR不得含testmod。
- dedicated server啟停、client/common boundary、M2人工通過項目保持。

M4完成必有逐task spec／quality review與M4 whole-branch review，Critical／Important為0。M4完成仍不能宣稱新世界、passage、collapse或client preview可用。

## 14. M5 Handoff Contract

M5只可從checked `MEASURED + SELECTED` 開始：

```text
SOURCE
  → resolve frozen SourceFamilyRef

EXISTING
  → resolve exact gameplay-eligible UniverseId

NEW
  → idempotent allocationToken lookup-or-create UniverseId
```

M5在任何Universe allocation前，必先把自己的 `COLLAPSING` intent checked落盤；之後才可Projection、corridor collapse與passage。M4不預先定義M5的block overwrite、client sync或passage renderer實作，但其schema不得要求重新抽candidate。

## 15. 人工可見界線

M4單獨人工驗證只會看到：側門可被選擇一次、收到「候選已鎖定」訊息、其他門被拒絕，門仍關閉且玩家仍在走廊。真正「走廊消失、回原艙、門後是新世界」必須等待M5完成；不得把M4中間狀態回報成原需求已完成。
