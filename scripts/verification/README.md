# M1–M4 驗證 harness

M1–M4 automated gates 的 tracked 版本。原本只放在 gitignored 的 SDD workspace `.superpowers/sdd/2026-09-21-m4-candidate-doors/`，刪除 worktree 或 `.superpowers/` 後就無法重跑；這裡的腳本只改路徑、參數與名稱，安全行為（fresh world move／逐檔 hash exact restore、owned roots、拒絕覆寫、PID 過濾、main-only 三層 oracle、JAR testmod 檢查）與原始檔相同；少數邏輯差異逐項列在「來源對照」。

## 環境需求

- Windows，repo 位於本機固定 NTFS 磁碟（Windows checkpoint 只接受本機固定 NTFS，`_windows` GameTest 與 checkpoint JUnit 都依賴它）。
- PowerShell 7（`pwsh`）；`git` 在 PATH。`m4-recovery-probe.ps1` 另以 Windows PowerShell（`powershell.exe`）啟動 Gradle。
- Java 21。本機驗證使用 Eclipse Adoptium `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`（`JAVA_HOME`）。
- Gradle 8.8：預設 `C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat`，不存在時改用 repo 的 `gradlew.bat`；也可用 `-Gradle` 指定。兩者都不存在時直接失敗。
- 執行期間不得有 Minecraft client／server 或其他 Java 程序使用 `run/`。harness 會檢查 live owner 與 `session.lock`，有就拒絕。
- 磁碟空間：一次 `run-all.ps1` 約 0.85 GB。evidence 約 420 MB（`-EvidenceRoot` 下約 260 MB，testmod 固定位置約 160 MB），`run/` 新增的 owned roots 約 420 MB；harness 都不刪除。

## 檔案

| 檔案 | 用途 |
| --- | --- |
| `gates.ps1` | gate 主體。`-Mode Full`（`clean test runGameTest build --rerun-tasks`）、`Legacy`（`runGameTestLegacy`）、`Main`（main-only `runServer`）、`M3`（lifecycle 4 phases＋transfer 5 phases）、`GameTest`（只跑 `runGameTest`）、`Windows`（3 個 Windows checkpoint JUnit class）、`Inventory`（靜態列出 `@GameTest`） |
| `main-oracle.ps1` | 讀 `-Mode Main` 的 gate root：Fabric mod list、runtime classpath／argfiles／DLI、exact server PID class-load 三層都不得有 testmod；另核對 world keys、正常停止與 exact restore |
| `m4-recovery-probe.ps1` | M4 retained recovery 跨 JVM probe，24 phases，每個 phase 一個 JVM |
| `ci-rule-check.ps1` | 從 `.github/workflows/build.yml` 逐字抽出 step 的 `run: \|`，在 fixture 複本上以 pwsh 執行；可加 XML／原始碼 mutation 證明規則會失敗 |
| `runtime.init.gradle` | 所有 gate 的 Gradle 呼叫都以 `-I` 載入：run* 接 stdin、寫 `classload-<pid>.log` |
| `probe.init.gradle` | M3 gate 另外載入：把 `-Ptask9.*` 轉成 probe 的 system property |
| `run-all.ps1` | 依序跑 Full、Legacy、Main＋oracle、M3、M4 recovery、CI 綠燈規則（Windows／Ubuntu 兩個 step），最後以唯讀 summary 核對 |

## 執行

全部 gates（約 14 分鐘；以下指令在 repo 根目錄執行，腳本本身依檔案位置找 repo root）：

```powershell
pwsh -NoProfile -File scripts/verification/run-all.ps1
```

成功時最後一行是 `END ALL exit=0 HEAD=<sha>`，倒數第二步印出 `SUMMARY ...`。任何步驟失敗都會印 `END <step> FAIL <原因>` 並停止。

個別執行：

```powershell
$v = 'scripts/verification'
& "$v/gates.ps1" -Mode Full -Label full            # 印出 GATE_ROOT=<gate root>
& "$v/gates.ps1" -Mode Legacy -Label legacy
& "$v/gates.ps1" -Mode Main -Label main-only
& "$v/main-oracle.ps1" -Gate <Main 的 GATE_ROOT>
& "$v/gates.ps1" -Mode M3 -Label m3                # -Only universe 或 -Only transfer 只跑一條
& "$v/m4-recovery-probe.ps1"                       # -Only chain,low 只跑指定 chain／scenario
& "$v/ci-rule-check.ps1" -Label win-green -Workflow .github/workflows/build.yml `
    -Step '確認 Windows 原生案例確實執行' -GameTestXml <Full gate>/result-build/gametest-results.xml `
    -JUnitDir <Full gate>/result-build/test-results/test -SourceRoot src/testmod/java
```

`ci-rule-check.ps1` 不因規則 FAIL 而 throw，判定看 `<root>/result.json` 的 `verdict`。要證明規則會擋下缺漏或宣告漂移，加 `-Mutation drop|skip|fail -MutationTarget <XML testcase name>` 或 `-SourceMutation interpose-annotation -SourceTarget <方法名稱>`，預期 `verdict=FAIL`。

## Evidence 位置

- `-EvidenceRoot`：預設 `<repo>/.superpowers/verification/`（gitignored）。只接受 repo 內、路徑無 reparse point、且被 gitignore 的目錄，否則在建立任何東西之前就拒絕。
- `run-all.ps1` 每次建立新的 `run-all-<GUID>/`，內含 `console.log`、`gate-<label>-<GUID>/`、`recovery-run-<runId>.json`、`ci-<label>-<GUID>/` 與 `summary.json`。個別腳本直接在 `-EvidenceRoot` 下建立自己的 root。
- 由 testmod 固定、不受 `-EvidenceRoot` 影響的位置（`ProbeEvidenceOwner`、`M4CandidateRecoveryProbe` 只接受這個路徑）：M3 receipt 在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/task-9-final-<nonce>/`、`transfer-final-<nonce>/`，M4 recovery receipt 在同目錄 `recovery-<nonce>/`。目錄不存在時 harness 會建立。M3 gate root 另存每個 phase 的 receipt 複本。
- `run/` 內：GameTest 與 main-only 把 `run/gametest/world`、`run/gametest-legacy/world`、`run/server` 移到 `<原路徑>.task9-backup-<GUID>-<tag>`，跑完後把 fresh 結果移進 gate root 的 `*-fresh/`，再把原檔移回並逐檔比對 hash（結果在 `<tag>-restore.json`）。M3 與 M4 recovery 每次新建 `run/m3-universe-m4-task9-<GUID>`、`run/m3-transfer-m4-task9-<GUID>`、`run/m4-recovery-<GUID>-<scenario>`，跑完保留。
- 失敗時保留全部 root 與 receipt，修正後以新的 root 重跑，不在失敗 root 上改判。若 restore 失敗，原檔仍在 `.task9-backup-*`，先查明原因再手動移回，不要刪除。

## 預期結果（code `ba854e8`）

`ba854e8` 之後只有文件 commit。2026-10-01 在 HEAD `e55b7f9` 以 `run-all.ps1` 實跑（約 14 分鐘：Full 約 3.5 分、M3 約 2.5 分、M4 recovery 約 6.5 分），`SUMMARY` 與 2026-09-30 integration fix round 4（`ifix4-final-green.ps1`）的數字一致。程式或測試有變更時，數字以新的 run 為準。

| 項目 | 預期 |
| --- | --- |
| default GameTest | 146/146，其中 `_windows` 73；0 failure／error／skipped |
| per-test Universe probe receipts | default 28＋legacy 1，全部 exact PASS |
| JUnit | 403，0 failure／error，2 skipped（只限 `NonWindowsPlayerCheckpointStoreTest`）；Windows checkpoint 三個 class 共 17 個實際執行 |
| legacy GameTest | 1/1 |
| main-only oracle | PASS（三層 testmod 命中 0、world keys exact、exact restore） |
| M3 | lifecycle 4＋transfer 5 receipts PASS |
| M4 recovery | 24/24 phases，各自獨立 JVM |
| CI 綠燈規則 | Windows、Ubuntu 兩個 step 都 PASS |
| JAR | release 299 entries（256 classes）、sources 190；testmod／gametest／probe 命中 0（`dev/quantumchamber/compat/ModPresenceProbe` 是合法的 production class） |

## 來源對照

原始檔都在 `.superpowers/sdd/2026-09-21-m4-candidate-doors/`，保持原狀未修改。

| tracked 檔 | 原始檔 | 原始檔 SHA-256 | 相對原始檔的改動 |
| --- | --- | --- | --- |
| `gates.ps1` | `task9-gates.ps1` | `34eaecabf9d4685771d53482130c04d496bbdf3e396f6c2d6fc06055249851e8` | 路徑與參數化；gate root 改名 `gate-<Label>-<GUID>`，輸出 `GATE_ROOT=`；移除 `RedOwner` 模式（Task 9 owner 修正前的一次性 RED；現行 testmod 已接受 evidenceOwner，該 RED 的前提不再成立）；classload／m4-boundary 只複製本次觀察到的 JVM PID（整合審查 R1 指定併入本 task）；`-Mode` 必填，`-Label`／`-Only` 加輸入驗證 |
| `main-oracle.ps1` | `task9-main-oracle.ps1` | `7175c03aff38e3c600e06c75248f3a81c660b6a671bbe142c627a54e48bba96b` | 只改 repo root |
| `m4-recovery-probe.ps1` | `run-m4-recovery-probe.ps1` | `00fca9fe186d5efac7d6df5d30dd7503c15d0d37f8a8b718977faccdb0bb0874` | 路徑與參數化；彙整 JSON 改寫到 `-EvidenceRoot`，已存在時拒絕覆寫 |
| `ci-rule-check.ps1` | `ifix3-ci-rule-check.ps1`（取代 `ifix2-ci-rule-check.ps1`） | `279b7b9f46d729ec78bfe72de0e201c09d84a772c50d99751c6e0614f4ded808` | root 改為 `-EvidenceRoot`；輸出標記 `CI_RULE`；`-Label` 加輸入驗證 |
| `runtime.init.gradle` | `task9-runtime.init.gradle` | `40cccdea23acc6520e92ab03198acd2881132b54962dfca53216e4679dbe75e0` | 只加註解 |
| `probe.init.gradle` | `task9-probe.init.gradle` | `3d14ca4f399a8cc75797b12f4d38c73b02ca149500ef024c53f212634e3c678d` | 只加註解 |
| `run-all.ps1` | `ifix4-final-green.ps1`＋`ifix3-build-summary.ps1` §1–§8 | `dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d`／`63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5` | 原本以名稱前綴在 SDD 目錄取最新建立的 root，改為在本次 run root 內要求恰一個；CI 綠燈結果改為 `verdict` 非 PASS 即失敗；summary 去掉該輪固定數量與一次性 RED/GREEN 核對，只保留通用不變式並輸出數字；核對執行前後 HEAD 相同 |

## 未 tracked 的腳本

下列檔案被 tracked 文件引用（含 `ifix*-gradle.ps1` 等 glob 寫法），或屬於 AGENTS 要求盤點的 `.tools/`；它們是特定一輪的一次性腳本、已被上表取代，或已不適用目前 code，所以不 tracked。原始檔留在原處；路徑相對於 `.superpowers/sdd/`（`.tools/` 除外）。

| 檔案 | SHA-256 | 不 tracked 的理由 |
| --- | --- | --- |
| `2026-09-21-m4-candidate-doors/ifix2-ci-rule-check.ps1` | `81aba12a98f7f2ef1e0f6c7dded500631abfccf6968849612c63d752853c9153` | 被 `ifix3-ci-rule-check.ps1`（→ `ci-rule-check.ps1`）取代，後者多了 SourceMutation |
| `2026-09-21-m4-candidate-doors/ifix1-build-summary.ps1` | `6767126e35e6b977c57ddc4cbbdda9bc6efa353b16e9e70e21906a23a31cc28a` | round 1 一次性彙整，寫死該輪數量與 RED/GREEN roots |
| `2026-09-21-m4-candidate-doors/ifix2-build-summary.ps1` | `253678540c8461d64684ceb847da8213a0151b7f23a2d83f95f892f9a1009933` | 同上（round 2） |
| `2026-09-21-m4-candidate-doors/ifix3-build-summary.ps1` | `63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5` | 同上（round 3）；通用核對已併入 `run-all.ps1` summary |
| `2026-09-21-m4-candidate-doors/wfix1-build-summary.ps1` | `ca074270b6dfbf1d4924db8a914dbaf3968f2ff8aeef8d4ab57867fd6f871f1e` | M4 whole-branch fix1 一次性彙整 |
| `2026-09-21-m4-candidate-doors/task10-phase2-verify.ps1` | `7aed86be691b3cf128255a323c720ccf46a77f5489f2ca099209fb3b1f9281ec` | Task 10 一次性核對，寫死 `752ada1` 與當時 roots |
| `2026-09-21-m4-candidate-doors/ifix1-gradle.ps1` | `cf5ecb5868031bb79dc8f4fd32c527ec41028f89a986dc165d42d1288086febf` | 只包一層 Gradle 編譯／JUnit 呼叫並記錄 console，focused 測試可直接用 Gradle 或 `gates.ps1 -Mode Windows` |
| `2026-09-21-m4-candidate-doors/ifix2-gradle.ps1` | `7c9ed4d959f1d88c905bb66018ed4885250d773b553f707019c4e90971cf5bad` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix3-gradle.ps1` | `7b2241ba9542272f36fcb543714359eff0aef84a9c39be4f34874598bcb2f152` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix1-final-green.ps1` | `d414e20e92a90466bfe91fdd000d2acd648af617edb35d9415ba71e895ac02a3` | 該輪 wrapper，寫死 label 與 console 名稱；由 `run-all.ps1` 取代 |
| `2026-09-21-m4-candidate-doors/ifix1-final-green2.ps1` | `f6a2570ae173de1b21589ce5d478edefa9ad8e51d4d51f7400962d329eecb276` | 同上 |
| `2026-09-21-m4-candidate-doors/ifix2-final-green.ps1` | `34d4ead3b41c783f7c29b27054dd10b6bf610d7e61b83f1595ba98bfa5fd3c26` | 同上，另含該輪 CI mutation 組合 |
| `2026-09-21-m4-candidate-doors/ifix3-final-green.ps1` | `b7afb1245db37cfeb80172d795e416eaa039a27698988d356f077b108e411455` | 同上，另含舊 workflow（`e20cd9d`）對照 |
| `2026-09-21-m4-candidate-doors/ifix4-final-green.ps1` | `dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d` | 一般化為 `run-all.ps1` |
| `2026-09-21-m4-candidate-doors/wfix1-final-green.ps1` | `6a7c420945060d017c8a2d8891f46c62c1a2dbba4f9b30a0bede74dea7627d2e` | M4 whole-branch fix1 wrapper；由 `run-all.ps1` 取代 |
| `2026-09-21-m4-candidate-doors/wfix1-final-green2.ps1` | `89fa90b826a7caf286cf25311ebf2ca9b53a10f34bc0822a055adc4f07560f2c` | 同上（M3／recovery 續跑） |
| `2026-09-21-m4-candidate-doors/task9-fix1-green.ps1` | `1ee5a74380c375ccc84334aca51b9f8f2911167b6134f595ea2c2a3eeac5cf9b` | 兩行 wrapper，寫死 fix1 labels |
| `2026-09-21-m4-candidate-doors/task9-fix1-oracle-fixtures.ps1` | `a7e9277efdaffd78aa8ac4e394514136767c753e111ee52e13107aa17f89ebf2` | main-oracle 的一次性負向 fixture，來源寫死某個歷史 gate root |
| `2026-09-19-m3a-dynamic-universe-backend/run-gate-a.ps1` | `55c2e883f842020ae485334e03eb0a4d85d5c40a93ab7c83c398c1a57b333ddc` | 需要每個 nonce 預先匯出的 Loom runtime JSON，GateB 寫死 nonce 與 receipt hash；同樣 4 phases 已由 `gates.ps1 -Mode M3`（Gradle `runM3Universe`）取代 |
| `2026-09-19-m3b-server-transfer-readiness/run-transfer-probe.ps1` | `65f018604ff3414833ad16af93c8f3a444b49119b53b256ce3a615d314237609` | 需要預先匯出的 runtime JSON；transfer 5 phases 已由 `gates.ps1 -Mode M3` 取代，各 phase 的結果判定由 probe 內 `require` 決定 receipt status |
| `.tools/m1-server-smoke.ps1` | `5cf237004616bf1622299d03e1776d456fbf9a1f9fc35005b5f5894460ba9438` | M1 時期 smoke：直接在現有 `run/server` 世界放方塊、沒有 fresh move／restore，且讀固定的 `m1-smoke` 存檔；由 `gates.ps1 -Mode Main` 與 GameTest 取代 |
| `.tools/read-m1-registry.py` | `ce3407f0f76cd76a4ff7dd48c413ed0940d9ea9b74cc14b5354dbfaf1527789f` | 斷言 registry `SchemaVersion == 1`，目前 `ChamberRegistryState` 是 schema 2；沒有 tracked 文件引用 |
| `.tools/m12-lighting-audit/lambdynamiclights-3.1.4+1.21.1.jar` | `c91de6935c866c85a68f96ac051dabae6a23b402e345b7141b6fb3c39998612b` | 第三方 JAR 的唯讀 audit 副本，不是腳本；建置以 `gradle.properties` 的 SHA-512 從 Maven 解析 |
