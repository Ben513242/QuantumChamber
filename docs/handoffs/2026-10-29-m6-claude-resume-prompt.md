# QuantumChamber M6：交給 Claude Code 的接續提示詞（2026-10-29）

請接續 QuantumChamber M6，使用繁體中文（台灣）精簡回報，先驗證原件再推論。工作區是 C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation，分支 codex/m6-universe-generation。不要在主 checkout 或 main 實作，不重做 M5／A7，不重跑已驗的相同 23 案 focused，也不可把 v4 父端 FAIL 追認為整體 PASS。

## 接手時先讀

1. 本工作區 AGENTS.md 與本檔全文；再讀 docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md、docs/superpowers/plans/2026-10-24-m6-universe-generation.md 的 Task 8–22、docs/implementation-notes/2026-10-24-m6-universe-generation.md 末節。
2. 本機 ignored 接力：.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md 最前段，以及 task-08/8b2-c1d-d1a-evidence/IMPLEMENTER-HANDOFF.md。ignored 原件不在 Git；若在別台機器工作，先確認能取得它們，不可補造收據。
3. 先執行 git status、rev-parse HEAD、ls-remote 與 worktree list；檢查是否有 live Java／Gradle owner、session.lock 是否釋放。保留所有 dirty worktree、世界、失敗 root；不用 reset --hard、checkout --、clean、add -A、force-add。

## 交接時的 Git 與證據真相

- 最後程式提交是 165b8b9c9d1db6999b65b39a201f35bc9397bdd8（D1a fix1）；其前一原候選 877d20f041b5d853ab227fc444ea9c6413924fbb。兩筆都只改 RegistryClosure.java 與 Minecraft121SettingsCanonicalizerTest.java。製作本交接時 tracked 工作樹乾淨，遠端 feature 仍是 f1e0bef261473afc48ab174dd83e720962cee8ca；若本檔另有文件提交／push，請以實際 Git ref 為準，別把文件 HEAD 當程式驗證 pin。
- 原候選 877d20f 的乾淨雙 focused 各 85/85；完整 .superpowers/verification/run-all-d4822a217c9d48f2894cfc83bf2a1bc0 自然 END ALL exit=0，JUnit847、0 fail／0 error／2既有skip、default179、M5 60、M6 8、M4 recovery24、M5 collapse69、M6 native DIAGNOSTIC2。它仍有獨立 spec／quality 各 C0/I1/M1：同大小 List.set 可讓 processor 清單複製出混合版本；舊候選不能結案。
- fix1 165b8b9 的確定性同大小替換 RED 為 88案1fail，GREEN 為88/88；設計補件 I1-CAPTURE-ADDENDUM-v2.md 的固定原生 decoder／RegistryLoader 證據已獨立 C0/I0/M0。fix1 乾淨 M6Compile 與第一次 focused 88/88 通過；第二次 focused 及一次新 nonce 補驗均 88案1fail，皆是既有 templateStateEdgeResourceRawCanonicalBudgetOverflowRejected 案先碰 100ms TEMPLATE_SNAPSHOT_NOT_READY，原失敗 root 保留。fix1 的完整 run-all 尚未啟動，且尚無 fix1 最終 spec／quality review；不可稱 D1a 已完成。
- 本機 ignored IMPLEMENTER-HANDOFF.md 有上述各 root、XML、SHA 與程序退出／鎖證據。最後補驗 session95788 自然 exit1；交接時無正在執行的測試 session，不需強停閒置 Gradle daemon。

## Claude 第一件實作工作：完成 D1a fix2

1. 先核上述 Git／原件與無 live owner。保留兩次 100ms FAIL，不在同 root 重跑，不調高 100ms、raw cap 或 production 預算，也不在本輪修無關驗證框架。
2. 針對既有 CanonicalizerTest 約第1095–1103行的 BUDGET_TOTAL_RAW 子案，先加具名子案標籤確認精確失敗位置。現 fixture 設 totalRaw=512、同 lease 排10次相同 absence template 重建，先撞 100ms。只在原測試 owner 窄修：以 Minecraft121TemplateValueContracts.measure(new StructureTemplate().writeNbt(new NbtCompound()),0) 算一份原生 blank 的真 charge；total cap 設該值，單次 capture 為正例、兩次相同 template occurrence 為累積超限負例。保持其他 raw／100ms／production 行為不變。先留具名 RED，再新 nonce GREEN。
3. fix2 建議獨立測試 commit，逐檔 stage；同乾淨 HEAD 跑 M6Compile、CanonicalizerTest 兩次 focused 與預設短 root 的完整 run-all，核 END ALL／XML／main-only／release JAR／所有原 probe。若仍失敗，保原 root，先查根因，不重送碰運氣。之後讓獨立 spec 與 quality reviewer 關閉原 I1／M1 與 fix2，更新 tracked docs truth，再依既有持續授權正常 push；不要把 877d20f 的 full 冒充 fix2 full。

## 後續 M6 順序與硬門檻

- Task8B2-C1c-T/A 已局部結案：T code d0bf69b；A code bf46e97，完整 run-all-55870333f3bb422b9f53535574e302ec END ALL0，兩席審查 C/I0。它們只接納模板來源／cache／exact Single 與窄 typed NBT；正式 createSnapshot/resolve 仍 fail closed。
- C1d 的 D0-O 六組原始分母已在 run-89ca6200-a1fb-4856-bc0c-b24a8ea76b6a 自然 RAW_CENSUS_COMPLETE，獨立結果審查 C0/I0/M0：2,406 memberships、1,174 unique templates、97,494 template edges；sourceProvenance／consumerClassification／productionAdmission 都是 false。先前兩個 O full FAIL 與 prepare FAIL 原判保留。D1a 只開 named PROCESSOR_LIST empty／NOP／LAVA；BLOCK／ITEM／LOOT_TABLE、Vault／TrialSpawner／entity／component、非模板 Overworld／Nether 完整閉包仍待。
- D0-N-METHOD-v2.md 的靜態小族群方法已獨立 C0/I0/M0；N-R 來源 JVM 仍須另包審查。jigsaw N-S 局部固定 JAR 規則 C0/I0/M1，但缺獨立 alias／use 分母，完整 N-S launch blocked。SCOPE-USE-INVENTORY-v2.md 設計 C0/I0/M1，只允許 ignored 純 Node codec／小 fixture；其 codec v2 包已獨立 C0/I0/M0，但真 B28 callsite、事件容量與唯一 JVM replay 均未放行。不要把合成 23/23 當 native 結果。
- 完整 C1d 還需三 role canonical bytes／FP、provider 與正式 snapshot／resolve，並以真 server 關閉 100ms 單次 live hold I-1；8B3 必須完成 lifecycle 與 Windows named、Ubuntu named、Windows intermediary 三 bundle 逐 byte 驗證。Task8 整體通過後，才按 plan 接 Task9–12 random seed（Task12才可玩）、Task13 same-seed fresh、Task14 profile、Task15–20 完整維度家族／portal／gateway／respawn；人工 smoke、整分支 review、main fast-forward／tag 各有獨立門檻。
- Task9 的唯讀 NBT streaming 版本探測準備在 ignored task-09/version-probe-readonly.md。不可提前實作 Task9 以繞過 Task8 gate。

正常 feature push 已持續授權；force push、PR、main 合併、tag、人工代驗或 worktree 退役沒有因此獲得新授權。每次停下更新本接手入口、M6 note 與 ignored STATUS，保留原始 FAIL 與精確 code／CI pin。

## 交回紀錄

### 2026-10-29：Claude 完成 D1a fix2，D1a 結案

- 接手實核：local／origin feature 同 `62a2f72`、tracked clean，只有閒置 Gradle daemon；fix1 兩次原 FAIL 原件保留，不改判。
- 本檔「Claude 第一件實作工作」一節建議的窄修，其前提經具名診斷證明不成立：原 BUDGET_TOTAL_RAW fixture 只做 2 次 capture，不是 10 次。不暖機的 DIAG2 量到第一個 capture 子案的 lease 內約有 23.6ms 冷成本，可以移出 lease；但原失敗子案未能定位，以測試耗時比例粗估的失敗時 lease 也只約 62～71ms，仍低於 100ms，不足以認定是原 FAIL 的成因。DIAG2 的預設判讀規則未成立（STOP_NO_FIX），fix2 是 controller 之後以降低風險為由裁示續作。fix2 code `9d97cd0` 改在任何 lease 前算 blank charge 兼暖機，BUDGET_TOTAL_RAW 改為正例 1 次 occurrence＋負例 2 次 occurrence；只降低 100ms 偶發風險，不宣稱根治。
- D1a 結案只限 named PROCESSOR_LIST empty／NOP／LAVA 的局部閉包；這段 closure 尚無 production caller，`createSnapshot/resolve` 仍 fail closed。fix1 兩次原 FAIL、v4 父 FAIL 與 B27 FAIL 原判保留。
- 驗證與審查：乾淨 `9d97cd0` M6Compile、CanonicalizerTest 雙 focused 88/88、完整 `run-all-3990c81a33b84ee9961ae54b63f4b752` 自然 END ALL0（JUnit850 f0e0 skip2）；整段 `f1e0bef..9d97cd0` fresh spec C0/I0/M5、quality C0/I0/M6。原件與 root 見 M6 note 末節及 ignored `task-08/8b2-c1d-d1a-evidence/FIX2-*`。
- 下一步：下一個 C1d 切片先做 Step0，處理 11 項 Minor（清單見 M6 note 末節）。之後依已審的 D0-O 原始分母（ignored `task-08/8b2-c1d-census-offline/run-89ca6200-a1fb-4856-bc0c-b24a8ea76b6a`，方法繼承到 `task-08/8b2-c1d-design/D0-METHOD-v4.md`）、`D0-N-METHOD-v2.md`（N 門靜態方法）及 `N-GAPS.json` 的 source／consumer／admission 缺口擴充 leaf／consumer；N-R 來源 JVM 仍要另包審查，需要新設計時先寫設計並獨立審查。仍待的門檻：C1d 其餘 leaf 與三 role FP／provider／snapshot／resolve、100ms live hold I-1、8B3 三 bundle、Task8 終局、Task9–20、人工與整分支。若同一個 budget 測試再出現 NOT_READY，視為新的 FAIL、保留 root，不重送改判。
