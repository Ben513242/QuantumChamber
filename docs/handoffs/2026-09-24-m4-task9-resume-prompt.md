# 可直接貼給新對話的接手提示詞

> 狀態（2026-09-24）：已完成，請勿再使用這段提示詞。Task 9 於 `38d901e` 關閉；M4 whole-branch review clean，code HEAD `752ada1`；現行入口見 AGENTS.md。以下內容保留為歷史紀錄。

```text
你要接手 QuantumChamber 的 M4 Candidate Doors，從 Task 9 fix round 1 的既有WIP續作；不要重做Tasks 1–8，也不要清理dirty worktree。

工作目錄：
C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m1-chamber

開始前完整讀取：
1. AGENTS.md
2. docs/handoffs/2026-09-24-m4-task9-resume.md
3. docs/superpowers/specs/2026-09-21-m4-candidate-doors-design.md
4. docs/superpowers/plans/2026-09-21-m4-candidate-doors.md
5. .superpowers/sdd/2026-09-21-m4-candidate-doors/progress.md
6. .superpowers/sdd/2026-09-21-m4-candidate-doors/task-9-report.md
7. .superpowers/sdd/2026-09-21-m4-candidate-doors/task-9-review.diff

Git事實：
- branch = feature/m1-chamber
- HEAD = 8917f84044031b627d1955bebbd62a75ead4f556
- dirty檔只有：
  M src/testmod/java/dev/quantumchamber/gametest/M4CandidateDoorGameTests.java
  ?? src/testmod/java/dev/quantumchamber/gametest/M4PerTestUniverseProbe.java
- production沒有未提交改動。

前一agent因usage limit中斷。保留兩個WIP檔，不得reset/checkout/delete。它已把13個M4具名tests接到per-test Universe begin/complete helper，但尚未交付完整RED/GREEN、report或commit。先核對diff與scratch artifacts，不要相信半成品宣告。

Task 9 review尚未關閉：
1. Important：必須讓全部13個M4 tests各自保存並exact比較catalog existence/records hash/runtime handles/world keys；每個test有唯一receipt，差異使該testFAIL。Suite-level snapshot不能替代。
2. Minor：main-only scratch oracle須把精確 Missing data pack quantumchamber-testmod 當獨立stale warning，不得FAIL；真mod/path/class hit仍FAIL。

依Subagent-Driven Development執行：先由fresh implementer完成本fix，接著fresh scoped reviewer。RED需證明synthetic Universe delta只打紅指定M4 test；stale oracle要有clean、exact warning、true mod、true path、true class五個fixtures。移除mutation後，用fresh可恢復world重跑clean test runGameTest build --rerun-tasks及legacy，要求default130/130、legacy1/1、JUnit零failure、13份per-test receipts全PASS、JAR testmod=0。

更新gitignored Task 9 reports/hash index，只committracked testmod檔；不要force-add .superpowers。Fix base是8917f84044031b627d1955bebbd62a75ead4f556。Scoped re-review clean後關閉Task9，再完成Task10與M4 whole-branch review。Critical/Important為0後push feature/m1-chamber，不合併main；之後才開始M5設計/計畫。

不要詢問是否繼續；除非遇到不可逆操作、安全敏感動作、未授權push/merge，或計畫完全無法判讀，否則持續執行並以ledger保存進度。
```
