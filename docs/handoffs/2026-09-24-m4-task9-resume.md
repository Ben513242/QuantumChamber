# M4 Task 9 續接狀態（2026-09-24）

> 狀態（2026-09-24）：已完成。Task 9 於 `38d901e` 關閉；M4 whole-branch review clean，code HEAD `752ada1`；現行入口見 AGENTS.md。以下內容保留為歷史紀錄。

## 結論

工作沒有遺失，但曾中斷一次。前一個Task 9 fix-round implementer在用量上限時停止，留下可辨識、未提交的testmod WIP。Tasks 1–8均已逐task review完成；Task 9初版已提交並完成多層runtime gates，目前只差fix round 1的GREEN／commit／scoped re-review。完成後才進Task 10與M4 whole-branch review。

不需要重做M4，也不需要另開新repository或worktree。

## Git 真實狀態

工作目錄：

```text
C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m1-chamber
```

分支與HEAD：

```text
feature/m1-chamber
HEAD = 8917f84044031b627d1955bebbd62a75ead4f556
subject = test: prove M4 candidate door authority
relative to origin/feature/m1-chamber = ahead 15
```

目前dirty檔案只有：

```text
 M src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java
?? src/testmod/java/dev/quantumchamber/gametest/M4PerTestUniverseProbe.java
```

不要reset、checkout或刪除這兩個檔案。Production目前沒有未提交變更。

## 已完成里程碑

- Tasks 1–8：完成、各自task review clean；相關commit已在目前branch。
- Task 9初版commit：`8917f84`。
- 初版自動證據：JUnit 383（0 failure、1 Windows skip）、default GameTest 130/130、legacy 1/1、Windows checkpoint 14/14、main-only三層oracle、M3 lifecycle 4/4、M3 transfer 5/5、M4 recovery 16/16、release/sources JAR testmod=0。
- Task 9初版review：Critical 0；Important 1；Minor 1；Assessment Needs fixes。

## Task 9 review尚未關閉的findings

### Important：13個M4 tests的逐測試Universe證據不足

初版報告宣稱全部13個具名M4 tests都有per-test catalog/runtime/world snapshots，但實際只有5個。

Fix要求：

- 每個test開始保存：catalog存在性、catalog／records hash、runtime handles、server world keys。
- 每個成功出口在`context.complete()`前exact比較。
- 每個test有唯一receipt與log。
- 任一Universe差異必讓該test FAIL。
- Suite-level SERVER_STARTED／STOPPED snapshot不能替代per-test receipt。

目前WIP已做到：13個入口均呼叫`M4PerTestUniverseProbe.begin(...)`，成功出口改走`M4PerTestUniverseProbe.complete(...)`。必須先review目前diff與RED artifacts，不能直接假設完成。

### Minor：stale datapack warning分類錯誤

Scratch `task9-main-oracle.ps1`原本把`$stale.Count -eq 0`放進PASS條件，導致只有精確：

```text
Missing data pack quantumchamber-testmod
```

也會FAIL。正確行為是：另列warning且`warningCount=1`，不能設`testmodLoaded=true`；但真mod list、runtime path或class-load命中仍須FAIL。

## 中斷時保留的WIP與scratch

Tracked WIP：

- `src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java`
- `src/testmod/java/dev/quantumchamber/gametest/M4PerTestUniverseProbe.java`

Gitignored scratch（不要force-add）：

- `.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-main-oracle.ps1`
- `.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-fix1-oracle-fixtures.ps1`
- `.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-fix1-green.ps1`
- `.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-fix1-per-test-red-0ca52ae9bb0b41eaa1e954fe79b01236/`
- `.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-fix1-oracle-fixtures-5a5507e492ae4f05ab32eb9c5ced75e6/`

前一implementer最後只回報「fresh RED正在跑」；尚未提供final RED／GREEN摘要、沒有append fix report、沒有commit。新agent必須讀原始XML/log/exit，不可把目錄存在當成PASS。

## 續接的必要順序

1. 讀`AGENTS.md`、M4 spec、plan、完整SDD ledger、Task 9 report/review package。
2. 執行：

   ```powershell
   git status --short --branch
   git diff --check
   git diff -- src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java
   ```

   並完整讀取`M4PerTestUniverseProbe.java`。
3. 核對per-test RED artifact：synthetic Universe delta應只使原本缺snapshot的指定M4 test失敗；mutation不得留在final tracked code。
4. 核對stale oracle五種fixture：
   - clean → PASS
   - exact stale warning → PASS、warningCount=1
   - true mod hit → FAIL
   - true runtime path hit → FAIL
   - true class-load hit → FAIL
5. 修正任何test helper缺陷後，使用fresh、可恢復world跑：

   ```powershell
   & 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' clean test runGameTest build --rerun-tasks
   & 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat' runGameTestLegacy --rerun-tasks
   ```

6. GREEN最低證據：default 130/130、legacy 1/1、JUnit零failure、release/sources JAR testmod=0、13份per-test final receipts全部PASS且名稱唯一、before/after exact相同。
7. 更新gitignored：
   - `task-9-report.md`
   - `m4-automated-gate-report.md`
   - 機器可讀summary/hash index
8. 只stage tracked testmod檔；`git diff --cached --check`後commit。建議subject：

   ```text
   test: require per-test M4 universe invariants
   ```

9. 以fix base `8917f84044031b627d1955bebbd62a75ead4f556`產生scoped review package，派fresh reviewer逐項判定兩個findings。
10. Review clean後，在ledger寫：

    ```text
    Task 9: fix round 1/5 (...)
    Task 9: complete (..., review clean)
    ```

11. 執行Task 10：tracked implementation note、final review report、完整fresh gate、M4 whole-branch review。Critical／Important必為0。
12. M4 review clean後依既有授權push `feature/m1-chamber`，不合併main；再進M5設計／計畫。M5不得把完整Nether／End family或complex renderer偷偷混入最小collapse/passage切片。

## 可重用與必須重跑的證據

若本fix只改`M4CandidateDoorGameTests`與新per-test helper：

- 必須重跑：full clean unit/default GameTest/build、legacy、JAR boundaries、per-test receipts。
- 可保留但須在report標示commit範圍：Task 9已完成的main-only、M3 lifecycle/transfer、M4 recovery process receipts；它們的tracked probes沒有被本fix修改。

若修改到M3／M4 recovery probes、build.gradle、production或mixin config，對應runtime chain必須fresh重跑。

## 重要禁令

- 不讀寫其他plan的`.superpowers/sdd` workspace。
- 不把GameTest/testmod process稱為main-only。
- 不在失敗root修一修重跑；換新nonce/root。
- 不force-add本plan scratch。
- 不改production來讓test oracle通過。
- 不push或merge，直到Task 9 re-review與Task 10 whole-branch review完成。
