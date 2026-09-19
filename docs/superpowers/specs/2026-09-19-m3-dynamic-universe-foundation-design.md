# M3：動態 Universe 基底設計

日期：2026-09-19。基準：`06f6823bbc031458bcbebec1ab669e2e70e87bf8`，既有功能分支 `feature/m1-chamber`。

狀態：使用者已核准自研 `DynamicDimensionBackend`、不新增第三方維度依賴，並要求先以阻擋測試證明重啟讀回與同 key 卸載／替換，再實作最小單一 Overworld Universe。本文件是實作規格，不代表 M3 已完成；M2 人工八項與整分支 final review 仍留在整體實作收尾。

## 1. 決策與終局方向

採用「domain registry 與 Minecraft 1.21 內部接點隔離」：Universe identity、descriptor、持久化與生命週期狀態屬於 `dev.quantumchamber.universe`；所有 `MinecraftServer` private world map／storage session／worker executor 接點只存在於版本鎖定的 backend／mixin package。Chamber、corridor、候選門及 renderer 不可直接存取這些內部欄位。

```text
UniverseRegistry + checked catalog
                ↓
UniverseLifecycleService
                ↓
DynamicDimensionBackend（穩定介面）
                ↓
Minecraft121DynamicDimensionBackend
                ↓
MinecraftServer private accessors / ServerWorld
```

不採直接讓 gameplay 操作 live world map；也不採目前版本未對齊的 DimLib。靜態預先配置大量 dimensions 只能作災難回退選項，不能滿足 runtime Universe allocation。

專案終局仍明確包含：候選門隨機選擇、開門塌縮、完整 Overworld／Nether／End family、邏輯上無限的 Universe 池、正式跨 Universe passage 與複雜 client renderer。但這些不在 M3 同批實作。這裡的「無限」是可持續配置 UUID、按需物化／卸載的邏輯池，不是同時把無限 `ServerWorld` 留在記憶體。

## 2. M3 成功畫面

M3 完成時，fresh dedicated server 能以正式 catalog 建立一個替代 Universe：

1. `UniverseRegistry` 先建立並 checked-flush 一筆 immutable definition 與分離的 desired availability。
2. backend 在 server thread 建立一個非 vanilla key 的 Overworld-role `ServerWorld`，使用獨立 storage path，publish 後明確發出 Fabric `LOAD`。
3. 在世界 A 寫入的原生 block／block entity 經正常保存後，第二個 JVM 只靠 catalog descriptor 重建同一 key，從原生 region storage 讀回；不得由測試 manifest 或 fallback `setBlockState` 補資料。
4. 世界 A 經明確 save、UNLOAD、expected-instance remove、close 後，可用同 key 建立世界 B 並讀回原資料；A 的 queue、listener、ticket、protection 或 world reference 不得串到 B。
5. 只有 backend 回報 destination `ACTIVE` 且 exact identity 成立時，M3-B 低階 transfer service 才可把 server-side 測試玩家以同座標移入替代 Overworld，再安全返回 vanilla Overworld。
6. 伺服器正常停止後沒有重複 UNLOAD、未關閉共用 `LevelStorage.Session`、未遺留本 server 的 runtime owner。

M3 不出現候選門、隨機選擇、collapse gameplay、Chamber projection、Nether／End sibling、走廊開門 passage 或新 renderer。玩家不會因完成 M3 就自動從走廊門進入 Universe。

## 3. 已知技術界線

Pinned Minecraft 1.21 的 `ServerWorld` constructor 是 public，但 `MinecraftServer.worlds`、`session`、`workerExecutor` 沒有公開動態維度 lifecycle API。vanilla `createWorlds` 只列舉 `RegistryKeys.DIMENSION`，不掃描 `dimensions/`；live map entry 不會自動保存 dimension definition。

因此 catalog 是定義權威，region files 只是世界內容。每次 server 啟動都必須先 strict-read catalog，再明確重建 descriptor。`ServerWorld.close()` 只關閉 world/chunk/entity storage，不會移除 map entry，也不會代替 Fabric dynamic `UNLOAD`。

先前 spike 已直接證明 server-side create、publish、write、save、normal close 可達；尚未證明第二 JVM readback、同 key replacement、玩家 transfer、獨立 seed/time/weather。這些全部保留為 M3 gate，不能由設計文件推定成功。

## 4. Domain model 與持久化

### 4.1 `UniverseId` 與 key

`UniverseId` 是 UUID value object。M3 world key 固定由 descriptor 產生：

```text
quantumchamber:universe/<universe-uuid>/overworld
```

不得由顯示名稱、陣列索引或啟動順序產生 key。相同 UUID／role 永遠得到相同 key，不同 UUID 不得共用 storage path。

### 4.2 `UniverseDefinition` 與 `UniverseRecord`

不可變的 `UniverseDefinition` 至少保存：

- `schemaVersion`
- `universeUuid`
- `allocationOrdinal`，只供 deterministic 排序，不作 identity
- immutable `worlds` map，key 為 `DimensionRole`
- descriptor 建立版本與 generator profile

`UniverseWorldDescriptor` 至少保存：

- exact `worldKey`
- `DimensionRole`
- `GeneratorProfile`
- `SeedPolicy`
- storage policy 版本

M3 只接受一個 `OVERWORLD` entry，profile 為 `VANILLA_OVERWORLD_SHARED_SEED_V1`。它擁有獨立 storage，初始 terrain 可與來源存檔使用相同 seed；M3 不冒稱已完成獨立 universe seed。未來 `OWN_SEED_V2` 必須另經 native generation grounding／測試，不能用忽略的欄位假裝支援。

`UniverseRecord` 將 immutable definition 與可變 `DesiredAvailability` 分開。Schema 1 只接受 `EAGER_ENABLED` 與 `DISABLED`；前者表示 server 啟動時必須重建，後者保留 catalog/storage 但不物化。未來 lazy pool 必須新增明確 materialization policy 與 schema migration，不能把 M3 的 `EAGER_ENABLED` 偷換成 lazy 語意。

資料結構不得硬編碼「最多一個 Universe」；M3 gameplay／bootstrap 只配置一筆，但 registry 以 UUID map 表達，為後續邏輯無限池保留擴充性。未知 schema、未知 role/profile/policy、重複 UUID、重複 world key、key 與 UUID/role 不一致、非 `quantumchamber` namespace 或非法 NBT 型別均 fail closed。

### 4.3 `UniverseRegistry` 與 checked catalog

正式狀態檔使用：

```text
<save>/data/quantumchamber_universes.dat
```

沿用 `SessionRecoveryState`／`SessionJournalStore` 的安全原則：同目錄 temporary、compressed NBT、file `force(true)`、atomic replace、strict wrapper、正式檔已存在但損壞時不得退回空 registry。Catalog 需保留 `current` 與 `flushed` snapshot；definition identity／world key 一經 durable 不得以 remove→reinsert 偷換。`DesiredAvailability` 只能經 checked mutation 改變，不得改寫 definition。

配置新 Universe 的順序是：validate candidate → put registry → checked flush → strict readback exact definition／availability → materialize。Flush 或 readback 失敗時不能建立 world；materialize 失敗時保留 durable record 與本 JVM 的 runtime failure receipt，下一次啟動仍依 `EAGER_ENABLED` 診斷／重試，不刪 region 或 catalog。Failure receipt 在 schema 1 不持久化，不能與 durable definition 混為一談。

本 checked flush 可承諾 temporary file 已 `force(true)`、atomic replace 已成功返回且正式檔可 exact readback；它不宣稱在所有檔案系統／Windows 上已對 parent directory entry 做可攜式 fsync，也不宣稱整機瞬間斷電完全無 orphan 風險。完整 power-loss consistency 不在 M3 gate。若啟動時發現 `quantumchamber:universe/...` owned storage 存在但 catalog 沒有相符 definition，必須標為 orphan 並 fail closed：不得自動採用、覆寫或刪除。Catalog 有 definition但尚無 region則可正常 materialize；兩者都有則按 definition重建。

持久化只保存期望定義，不保存 Java `ServerWorld` reference。`MATERIALIZING`、`ACTIVE`、`UNLOADING` 等狀態是每個 server instance 的 runtime state；重啟一律從 descriptor reconstruction，不信任前次 JVM 的 volatile state。

## 5. 介面與責任

### 5.1 `DynamicDimensionBackend`

穩定介面只使用 domain descriptor、`MinecraftServer` 與明確結果型別：

```java
interface DynamicDimensionBackend {
    MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor);
    UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor,
                        ServerWorld expectedWorld);
    Optional<ServerWorld> resolveActive(MinecraftServer server,
                                        UniverseWorldDescriptor descriptor);
}
```

所有方法只能在 owning server thread 呼叫。`resolveActive` 必須同時驗證 runtime owner、world key、`server.getWorld(key)` 與 exact instance；只看 key 或 storage path 不算 active。

### 5.2 Minecraft 1.21 backend

`Minecraft121DynamicDimensionBackend` 擁有：

- `MinecraftServerDynamicWorldAccess` mixin accessors：world map、session、worker executor。
- constructor arguments 與 pinned 1.21 bytecode 的版本 guard。
- expected-instance `putIfAbsent`／`remove(key, expectedWorld)`。
- storage path canonical containment 驗證。
- dynamic LOAD／early UNLOAD dispatch。
- world border listener 的建立與移除。
- 每個 server／key 的 runtime owner map。

Mixin 只 expose 欄位，不 Inject tick、save 或 constructor 行為。啟動時若 Fabric Loader 回報 Minecraft 不是精確 `1.21`，或 accessor／constructor invariants 不成立，backend fail loud，不以 reflection 猜欄位或降級繼續。

### 5.3 `UniverseLifecycleService`

Lifecycle service 是唯一 orchestration owner：

- `SERVER_STARTED`：strict-load catalog，按 ordinal／UUID deterministic reconstruct 所有 schema 1 `EAGER_ENABLED` records；任一 reconstruction 失敗即阻止 Universe gameplay並請求正常停止。若未來一次重建多筆，失敗時須反向 early-unload 本輪已成功物化者；任一反向清理失敗即保持 unhealthy、正常停止，不繼續 gameplay。M3 單筆也沿用同一契約。
- 正常 server shutdown：動態 world 留在 live map，由 vanilla save/close 與 Fabric shutdown UNLOAD 處理；backend 不再手動發第二次 UNLOAD。`SERVER_STOPPED` 只清 volatile owner 並驗 exact server detach。
- early unload：只在安全 server tick 邊界、無 player transfer／session／外部票據時進行；失敗即標記 backend unhealthy，拒絕 replacement。

同 key replacement 是 backend lifecycle 的阻擋測試，不是玩家 gameplay。正式世界平時保持 materialized；M3 不實作自動 LRU eviction。

### 5.4 Role resolution

保留 `DimensionRole.fromVanillaKey` 的純 vanilla 契約，另新增 server-scoped resolver：先辨識 vanilla key，再由健康的 `UniverseRegistry` 以 exact dynamic world key 找 role。未知 foreign key 仍回 empty。

M1/M2 現有 activation/protection 不在 M3 全面改成 dynamic Chamber 支援。只有 M3 新 backend、transfer guard 與測試使用 resolver；Projection Chamber 的 materialization 留後續里程碑，避免把「foreign world 有 OVERWORLD role」誤當成可建立 Origin。

## 6. Materialize transaction

每個 descriptor 的 transaction 依序執行：

1. server thread、backend healthy、catalog flushed、descriptor enabled。
2. world registry key 不得是 vanilla key；live map 與 runtime owner 均不得已有 key。
3. `session.getWorldDirectory(key)` canonical path 必須位於該 save root 的 `dimensions/quantumchamber/...`，不得接受 reparse/symlink escape 或自訂外部路徑。
4. 以同 server registry manager、session、executor、Overworld dimension type／generator profile建立 `ServerWorld`；不另開或關閉 `LevelStorage.Session`。
5. 建立並記住 world-border sync listener。
6. expected-absence publish；立即驗 `server.getWorld(key) == created`、server identity、world key、storage identity。
7. 明確 dispatch 一次 Fabric `ServerWorldEvents.LOAD`。
8. publish runtime `ACTIVE` receipt。

若 constructor 後、publish 前失敗，關閉新 world 並保持 map absent。若 publish 後失敗，於同一安全邊界依序 dispatch UNLOAD（若 LOAD 已發出）、expected-instance remove、移除 listener、close；任何 rollback failure 都令 backend unhealthy並停止後續 gameplay。不得用 key-only remove 清掉未知 replacement。

## 7. Early unload 與 replacement

Early unload 只接受 exact ACTIVE instance，並要求：

- world 內無玩家；所有 M2 session／transfer 已離開。
- backend 自有 ticket／listener／pending operation 可完整列舉並為零或可撤銷；開始後先封鎖此 world 的新工作。
- `ChamberControllerLoadSyncQueue` 提供 expected-world cleanup，不能只等 `SERVER_STOPPED`。
- 沒有未完成 materialize／save／transfer。

交易順序：標記 `UNLOADING` 並封鎖新工作 → 移除 backend 自有 ticket／border listener → drain／quiesce 已排程的 chunk、entity、save work並取得明確 receipt → 最後一次 native world save(flush) → 明確 dispatch UNLOAD 一次 → expected-instance remove → `ServerWorld.close()` → 驗 map absent／owner released → `ABSENT` receipt。

若 pinned 1.21 沒有足以證明 drain／quiesce 的安全接點，backend 必須回 `UNLOAD_UNSUPPORTED`，Gate B 保持紅燈；不能以一次 `save(flush)` 後立刻移除 ticket 取代 drain 證據。

這不是可回滾交易。remove 或 close 後任何失敗都標記 `UNLOAD_FAILED`，同 key 不得 replacement，server 應正常停止並保留 storage／log。絕不刪世界資料來「修復」鎖或重試。

Replacement 只有前述 unload 完整 receipt 後才可開始。新 instance 必須與舊 instance 不同、storage path 相同、sentinel 可原生讀回；舊 instance 的 BE queue 或 callback 必須因 exact world identity 被拒絕。

## 8. 最小 transfer gate

M3-B 的 transfer 只是證明動態世界可被原生 server player 流程使用，不是門或 collapse：

```text
vanilla Overworld safe pose
        ↓ exact ACTIVE destination + loaded safe target
alternate Overworld same XYZ
        ↓ native save/identity confirmation
return to recorded vanilla pose
```

只允許 testmod/default-off integration path 呼叫；production 不註冊 command、item、door interaction。Transfer 前保存來源 world/key/pose，目的 chunk 只由明確 fixture ticket 載入；傳送後驗實際 player world instance、座標與 server identity。失敗時在來源 exact identity 仍成立才回滾；否則 fail closed 並保留 recovery evidence。

M3 分成兩個明確完成層級：

- **M3-A Backend Foundation**：Gate A/B、catalog、lifecycle、identity與cleanup完整通過。
- **M3-B Transfer Readiness**：在 M3-A 之上，原生 server-side `ServerPlayerEntity` 同座標往返與 recovery驗證通過。

整體 M3 需同時完成 M3-A 與 M3-B。真 client連線、runtime world key／dimension registry packet／respawn sequence的人工驗證是正式 passage 的前置 blocker，留給 M5 前關閉；它不由 server-side test冒稱通過，也不阻塞 M3-A。若 M3-B server-side transfer失敗，整體 M3 不得宣告完成。

## 9. Blocking tests 與 TDD 順序

先建立會失敗的測試／harness oracle與能驅動它們的最小 catalog/backend skeleton；只有以下兩個 runtime blockers 真綠才可繼續 gameplay/bootstrap integration。

### Gate A：第二 JVM 重建讀回

1. JVM A 從 checked catalog descriptor materialize world A，寫 diamond sentinel 與真 Chamber Controller，正常 save/stop。
2. JVM B 啟動前證 live map 沒有該 key、vanilla DIMENSION registry 不含 definition；由正式 catalog（不是 hardcoded phase參數）重建。
3. 只讀載入 chunk，讀回 sentinel/controller及原生 BE NBT；禁止 fallback write。
4. 真 `BLOCK_ENTITY_LOAD` 當下證 queue 曾 enqueue；下一 END tick 必須透過 branch-specific processing receipt或 test-only instrumentation，辨識 `CONSUMED`、server/world identity drop、removed/replaced BE、no FULL chunk drop、missing-neighbor pending等實際分支。不接受只以 `pendingCount == 0` 猜測結果，也不再錯誤要求 entry 必須保持 pending。Unknown foreign Chamber 不得新增 Chamber record。

### Gate B：同 key unload／replace

1. exact A save、early UNLOAD、remove、close receipt 全部成立。
2. map absent、舊 queue/listener/owner清空且沒有強載未知 chunk。
3. 同 key 建 B，`A != B`、`server.getWorld(key) == B`、storage path相同，原 sentinel 可讀。
4. A 的舊 BE entry 即使座標／key相同也不得同步 B。
5. B 正常 stop 後第三 JVM再重建並讀回，證 replacement 沒破壞 storage lock或資料。

### 後續測試

- 純 JVM：schema strictness、duplicate UUID/key、immutable authority、deterministic key、role resolver、runtime state machine、expected-instance guard、錯誤回復。
- GameTest：dynamic LOAD/UNLOAD exact-once、queue world cleanup、foreign role isolation、無 force-load、normal shutdown cleanup。
- default-off dedicated probes：不同 PID／StartTime／startup nonce、fresh owned root、正常 stop、原始 stdout/stderr、events/final JSON、region hash與 source/class hash。
- transfer：先 server-only fake player/native entity，再真 client手動驗證；兩者證據分開。
- regressions：既有 M1/M2 JUnit、GameTests、clean build、Windows checkpoint tests、Ubuntu/Windows CI 均不可退步。

Probe 只能操作 nonce-owned fresh world；不 HALT、不 kill、不掃 process tree、不刪使用者 world、journal 或舊 evidence。任一關鍵 phase fail 即保留現場並停止，不以新 nonce重跑到綠。

## 10. 實作切片

1. **Catalog domain／codec**：value objects、schema1 strict NBT、checked state/store、純 JVM RED→GREEN。
2. **Backend contract／runtime state**：無 Minecraft private access 的 fake backend，驗 transaction ordering與 failure state。
3. **Pinned accessors／materialize**：最小 accessor、版本 guard、one-world create/publish/LOAD、rollback。
4. **Gate A**：以正式 catalog 完成 create-save→reload-read；未通過不得進下一片。
5. **Early unload cleanup**：border、queue、ticket、expected-instance remove、close與 fail-loud。
6. **Gate B**：同 key A→B→第三 JVM，證 storage／identity／queue。
7. **Universe lifecycle bootstrap**：SERVER_STARTED deterministic reconstruction、normal shutdown exact-once cleanup。
8. **最小 role resolver／transfer gate**：只供 default-off驗證，不接候選門。
9. **整體 regression／文件**：CI、dedicated server、證據 note、人工 client blocker狀態。

每一片均需獨立 spec compliance 與 code quality review。Gate A/B 不能平行修改同一 backend；catalog純測試、Windows CI診斷或文件可在不共享檔案時並行。

## 11. 後續里程碑相容性

M3 完成後的建議演進順序：

- **M4 Candidate Doors**：由 logical door key 對 `UniverseRegistry` query，使用持久 deterministic random seed 選出候選 Universe；關門前只顯示候選，不改世界權威。
- **M5 Collapse／Passage**：第一個成功開門交易原子地凍結 collapse result，安全返還／清除 corridor後才 materialize destination projection並傳送完整 cohort；重啟可恢復或返還。
- **M6 Universe Family／Pool**：每個 Universe 擴為 Overworld／Nether／End sibling descriptors，加入 independent seed profile、lazy allocation、載入上限與可回收 runtime cache；catalog identity邏輯上不設總數上限。
- **M7 Client Presentation**：候選門畫面、portal surface、複雜 renderer、shader/resource reload與fallback；renderer只消費已確認的 server projection/collapse state，不擁有 Universe選擇權。

候選選擇只保存 `UniverseId`／door key，不保存 `ServerWorld` reference；collapse transaction只呼叫 lifecycle service，不接觸 private map；full family只增加 descriptor roles，不改 Universe identity。如此 M3 不會封死終局需求。

## 12. 完成與非完成判準

M3-A 只有在 Gate A、Gate B、完整 automated regression、不同 JVM evidence、normal shutdown、獨立 review都通過後，才能宣稱「單一持久 alternate Overworld backend foundation完成」。整體 M3 還必須通過 M3-B原生server player往返；真client sync未人工通過時仍不得宣稱正式跨Universe passage。

以下均不能當 M3完成證據：constructor成功、live map出現key、Gradle exit0、region檔存在、同一 JVM重新讀記憶體值、測試硬編碼descriptor、手動補 sentinel、只看UNLOAD log、或第三方mod可做到。

本規格不授權推送M3 commit、建立PR、合併main、加入LICENSE、刪除spike證據或變更M2人工gate。推送仍需使用者另行指定；main合併需到所有必要自動、review與人工驗收完成。
