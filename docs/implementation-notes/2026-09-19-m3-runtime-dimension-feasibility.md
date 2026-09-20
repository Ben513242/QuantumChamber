# M3 runtime-dimension 可行性探查

日期：2026-09-19。基準：`d0bd235ae2dc4a9f36b3cf7fff89a7d851ffb3b9`，Minecraft1.21／Yarn1.21+build.9／Loader0.17.2／FabricAPI0.102.0+1.21／Loom1.7.4／Java21／Gradle8.8。

## 結論

**公開原生 API 不足；後續正式 M3-A backend 已通過不同 JVM 原生讀回、同 key 卸載／替換、production lifecycle 與完整自動驗證。**

2026-09-20 更新：正式 catalog/backend 的四階段 final gate、286 項 JUnit（285 passed／1 Windows 平台 skip）、118 項 GameTest、空／有效 catalog 的 main-only 啟停與 artifact audit 均通過，詳見 [M3-A 實作與證據](2026-09-19-m3a-dynamic-universe-backend.md)。Task 9 獨立 review 與 whole-branch review 尚待後續核定，不能由本文件提前宣告整體 M3 完成。

Minecraft1.21有public `ServerWorld`建構器，實測能用test-only accessor取得同server的world map／storage session／worker executor，建立真正foreign world、publish、寫原生region、參與save/shutdown，並觀察LOAD／UNLOAD與M1 STOPPED cleanup。但MinecraftServer沒有public add/register/remove/unload/replace world API；live map也不會把dynamic definition寫回DIMENSION registry。正式backend必須自行持久化descriptor並在每次啟動重建。

以下保留原 spike 的歷史結果；原始 FAIL 沒有改判。當時的 throwaway probe 未進 release 或 tracked source；後續 M3-A 是正式 catalog/backend 與 default-off testmod probe 的獨立實作，仍不代表玩家能跨 Universe。

## 原 spike 已直接實證

- foreign key與world/server/map identity成立；原四world之外新增一個Overworld type的runtime ServerWorld。
- canonical storage位於own fresh world的`dimensions/quantumchamber_spike/<foreign-key>`。
- diamond sentinel與Chamber Controller已寫入；foreign world有原生region tree。
- 正常stop確實保存五個world；foreign world正常close，LOAD1／UNLOAD1。
- 真`BLOCK_ENTITY_LOAD`當下曾使production load-sync queue count=1。
- STOPPING仍有foreign map identity；STOPPED後queue server key移除，ChamberProtectionService exact server/registry detach。
- runtime probe與production classes前後hash一致；Gradle0沒有被當作phase PASS。

## 原 spike 的正式失敗與當時界線

phase1最後為FAIL：production queue在下一END tick前已把entry移除，probe原本要求它仍pending，故fail-loud並正常停止。此現象不能直接判成production bug；現有證據沒有區分consume或其他drop。

因當時閘門要求每 phase PASS 後才可前進，原 spike 沒有執行以下項目；其中重啟、replacement 與 lock 釋放已由後續正式 M3-A 的全新 evidence 補證：

- 第二JVM sentinel／controller原生載回；
- 同key A→B expected-instance unload／replacement；
- storage lock完整釋放；
- final第三次重建；
- crash-during-materialization。

原 spike 本身不足以宣稱完整內部 backend 已可行；後續正式 M3-A 的成功不回填這次失敗收據。client registry sync、玩家 transfer、packet、renderer、portal、獨立 seed/time/weather、Nether/End family 與 crash-during-materialization 的原生故障注入仍未因此通過。

## API／生命週期發現

- vanilla `createWorlds`只從`RegistryKeys.DIMENSION`列舉；不掃live world map或`dimensions/`目錄。
- foreign key的world data使用`<save>/dimensions/<namespace>/<path>`；region存在不等於下次自動註冊。
- dynamic LOAD不由map put自動發出；backend須明確dispatch。提前UNLOAD同樣由backend負責，事件本身不是unload實作。
- `ServerWorld.close()`關閉world/chunk/entity storage，不移除server map，也不能關共用LevelStorage session。
- world map是private live map；任何正式封裝都必須server-thread-only、expected-instance publish/remove且避開iteration。

## 原 spike 建議與後續落實

以下架構方向已落實於正式 M3-A；第 4 項阻擋測試已通過。scratch 未被直接升格：

1. `DynamicDimensionBackend`隔離所有version-sensitive access，不把Minecraft internals漏進domain model。
2. 版本化、不可變world descriptor保存world key、role、seed/generator profile與materialization state；啟動時明確reconstruct。
3. lifecycle transaction需涵蓋publish、LOAD、save/drain、UNLOAD、expected-instance remove、close、failure recovery、border/ticket/queue cleanup。
4. 第一批阻擋測試就是本次未完成的不同JVM reload、同key replacement、foreign identity guards及load-sync cleanup。
5. 只做一個alternate Overworld；M4候選、M5 collapse/passage、完整Nether/End family後續再做。

M2人工八項與wholebranch final仍按既定安排最後驗；main未合併，未新增LICENSE。
