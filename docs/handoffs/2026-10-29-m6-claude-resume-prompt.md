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

### 2026-10-29：C1d Step0（D1a Minor 收斂）結案

- D1a 遠端 CI：run `40836148542`（`307565f`）兩 job success。
- Step0 code `242c20b`：處理 D1a 審查的 spec M1～M4 與 quality Q1～Q6，只改 RegistryClosure 與 CanonicalizerTest，不擴充 leaf／consumer。spec M5 屬 docs truth，D1a 文件已寫成降低風險；BUDGET_EDGES 第一個 lease 的冷成本是殘餘風險，歸 live hold I-1。驗證與審查：具名 RED→GREEN 93/93，乾淨雙 focused 93/93，完整 `run-all-41d76dffa3104602a75c8836ff2184ae` 自然 END ALL0／JUnit855；fresh spec C0/I0/M0、quality C0/I0/M4。Q1 的突變證明曾暫改範圍外的 Graph 檔，已還原、核對 hash，未進 commit。
- 下一步：D1b（BLOCK／ITEM 有效值與 default／effective component）。先依 ignored `task-08/8b2-c1d-design/SEQUENCE-PROPOSAL.md`（未審順序提案）、固定 1.21 JAR 的 consumer 與 D0-O 原始分母寫設計提案，經獨立審查後才實作；Step0 新增的 4 項 Minor（S1～S4）與兩項既有測試問題併入 D1b 的 Step0。清單見 M6 note 末節。`242c20b` 的遠端 CI 要等本筆 docs push 後讀回；本機 CI oracle 不等於遠端 CI。

### 2026-10-29：D1b 設計放行、D1b-0（Step0）結案

- Step0 遠端 CI run `40845641782`（`0998f67`）兩 job success。
- D1b 設計：v1 經全新設計審查 C0/I1/M7。I-1 是測試計畫中有兩項全域 registry 變更無法在 finally 還原，完整 JUnit 共用同一個 JVM，會汙染其他測試。v2 修訂後，經窄複審 C0/I0/M5 放行。Q1～Q10 由 controller 定案，無需使用者裁示；N1 採嚴格 fail closed。設計文件與 SHA 見 M6 note 末節；設計審查要求的 7 項 C1d 待辦已寫入 note。
- D1b-0 code `0efb058`：具名 RED→GREEN 95/95，乾淨雙 focused 95/95，完整 `run-all-359153a6930a42c7a8b9a05df4901f9e` 自然 END ALL0／JUnit857；spec C0/I0/M1、quality C0/I0/M2。
- 下一步：D1b-1（BLOCK leaf），依 v2 §3.2、§3.6、§5.1、§5.2、§5.4、§5.5 實作。複審的 5 項 Minor 與 D1b-0 審查的 3 項 Minor 隨派工修正。`0efb058` 的遠端 CI 要等本筆 push 後讀回。

### 2026-10-29：D1b-1（BLOCK static leaf）結案

- D1b-0 遠端 CI run `40863093293`（`b7d539a`）兩 job success。
- D1b-1 code `58a2ff9`：新增 `Minecraft121RegistryLeafContracts` 與其測試，BLOCK leaf payload 為 Contract／DefaultState／PropertyDomains／RequiredFeatures。驗證與審查：具名 RED→GREEN，6 個突變見證；乾淨 Canon focused 103/103×2、LeafTest 7/7×2，完整 `run-all-9b4906c116d14c6ab5b42da9723209f3` 自然 END ALL0／JUnit872；spec C0/I0/M2、quality C0/I0/M5。
- 設計前提更正：Canon 的 EndFixture 會 freeze 全域 static registry，freeze 後無法註冊；ModBlocks 改在 Canon `@BeforeAll` 初始化。`ModItems` 受同一機制影響，D1b-2 不引用。
- 下一步：D1b-2（ITEM leaf，含真 chest 模板；第一步是 `ItemAccessor` 的 setup probe）。複審 Minor #1、#3、#4、#5 的 ITEM 部分，與 D1b-1 quality 審查的 M1～M4 併入派工。`58a2ff9` 的遠端 CI 要等本筆 push 後讀回。

### 2026-10-29：D1b-2（ITEM static leaf）結案

- D1b-1 遠端 CI run `40875796265`（`4b16cb0`）兩 job success。
- D1b-2 code `3fbe626`：ITEM leaf payload 為 Contract／DefaultComponents／RequiredFeatures。驗證與審查：`ItemAccessor` setup probe 成立；具名 RED→GREEN；見證 3／4／7；乾淨 Canon focused 109/109×2、LeafTest 15/15×2；完整 `run-all-cb0f99493892472ab1f63860f3ed3ad4` 自然 END ALL0／JUnit886；spec C0/I0/M3、quality C0/I0/M5。1333 個 vanilla ITEM 中 1158 個 ACCEPTED，175 個因白名單外的 default component 依設計 fail closed。
- 設計表矛盾：chest 的 ITEM 帶 `container`，依白名單會被拒，所以測試改用 dark_oak_sapling；真 vanilla 模板的 GREEN 仍有實證。
- 下一步：D1b-3，處理會引用其他 registry 的 default component（tool、attribute、food、potion 等）。須先寫設計並獨立審查。前置決策是 component codec 改用 lease 的 registries，或證明與全域 static registry 等價（spec 第 123 行）。D1b-3 的 Step0 要併入 D1b-2 兩席審查的 Minor 與殘餘風險：ComponentMap 容器與巢狀 List、SpawnEggItem 委派、ITEM 拒絕原因 gating、診斷退場條件等。清單見 M6 note 末節。`3fbe626` 的遠端 CI 要等本筆 push 後讀回。

### 2026-10-29：D1b-3 設計放行

- D1b-2 遠端 CI run `40888610474`（`b6004de`）兩 job success。
- D1b-3 設計（ignored `task-08/8b2-c1d-design/D1B3-PROPOSAL.md`）經全新設計審查 C0/I0/M10 放行，C1～C15 由 controller 定案，無需使用者裁示。
  - component codec 改用 lease registries，並固定證明 static registry 的 identity。
  - 切分：3-0（Step0，9 項）→ 3a（8 型 literal）→ 3b（attribute／food／直接 tool）→ 3c（tool tag，TAG 只展開一次，tag 測試技法）→ 3d（jukebox）。
- 下一步：D1b-3-0。各子片要帶的審查 Minor 見 M6 note 末節。

### 2026-10-30：D1b-3-0（Step0）結案

- D1b-3-0 code `2823985` 已收斂 D1b-2 的殘餘風險：
  - roundtrip 之後，對重建的原生 map 再驗一次白名單，關閉「容器說謊」與「巢狀 List 說謊」兩種繞過；
  - SpawnEggItem 委派的 EntityType 也納入 source 檢查；
  - 新增 ITEM 拒絕原因的 gating；
  - 兩個 seam 都自驗 source。
- 驗證與審查：具名 RED→GREEN，5 個突變見證；乾淨 Canon focused 110/110×2、LeafTest 21/21×2；完整 `run-all-42034bb2a7144d8bb4841f9a10b242c4` 自然 END ALL0／JUnit893；spec C0/I0/M2、quality C0/I0/M3。
- 下一步：3a。component codec 改用 lease registries，並固定證明 identity（ITEM、DATA_COMPONENT_TYPE、BLOCK、ATTRIBUTE、STATUS_EFFECT）；接受 8 型 literal。派工要帶設計審查的 M1、M4，以及 D1b-3-0 兩席審查的 5 項 Minor（見 M6 note）。`2823985` 的遠端 CI 要等本筆 push 後讀回。

### 2026-10-30：D1b-3a 結案

- D1b-3-0 遠端 CI run `40911589298`／`40911636980` success。
- D1b-3a code `36fdd36` 的內容：
  - component codec 改用 lease registries；
  - 固定證明 ITEM／DATA_COMPONENT_TYPE／BLOCK／ATTRIBUTE／STATUS_EFFECT 的 identity；
  - 新增 owner `Minecraft121ItemComponentContracts`；
  - 接受 8 型 literal／空 DEFAULT，ITEM 被拒數從 175 降為 109。

  驗證與審查：具名 RED→GREEN、6 個見證；乾淨 Canon focused 112/112×2、LeafTest 25/25×2；完整 `run-all-8aba2dc201f94d959e0e886b6be6ea4f` 自然 END ALL0／JUnit899；spec C0/I0/M4、quality C0/I0/M3。
- 殘餘：W-OPS 是等價突變，只有 3d 的 dynamic JUKEBOX_SONG 能殺掉，已列為 3d 的結案條件。
- 下一步：3b，處理 attribute_modifiers、food，以及只有直接引用的 tool；新增 ATTRIBUTE 與 STATUS_EFFECT leaf；ITEM leaf 開始產生出邊。
  - **硬性前置**：已審型別表要先改成單一分派的 per-type contract，值條件與 refs 寫在同一處，以免 ref 抽取漂移而造成 fail-open。
  - 要帶入的 Minor 見 M6 note「D1b-3a 結案」一節。
  - `36fdd36` 的遠端 CI 要等本筆 push 之後讀回。

### 2026-10-30：D1b-3b 結案

- 3a 遠端 CI run `40926054933` success。
- D1b-3b code `ff3aecc` 的內容：
  - 已審型別表改成單一分派 per-type contract，加結構測試；
  - 接受非空 attribute_modifiers、food、只有直接引用的 tool；
  - 新增 ATTRIBUTE 與 STATUS_EFFECT leaf；
  - ITEM leaf 開始產生出邊，原值與重建值兩邊的 ref 逐一比對。

  ITEM 被拒數從 109 降為 66。
- 驗證與審查：具名 RED→GREEN，8 個見證；乾淨 Canon focused 115/115×2、LeafTest 34/34×2。首輪 full 因 `quantumchamber_sessions.dat` 原子移動遇到 AccessDenied 而 FAIL，屬 M5 已知的外部鎖檔限制，與 3b 無關；原 root 保留，改用新 nonce 的 `run-all-090bcb63a6e04cff9650b9aaba5281bd` 自然 END ALL0／JUnit911。spec C0/I0/M4、quality C0/I0/M4。
- controller 裁示：
  - hidden_effect 不可能被觀察，改為斷言它被丟棄；
  - CodecCache 是全域的，存在等值替換的殘餘風險，修正排到 3c Step0：codec 前用已驗元素重建 attribute／tool／food 三型 record。
- 下一步：3c。先做 Step0，清單見 M6 note「D1b-3b 結案」的「交 3c Step0 的事項」；之後做 tool 的 tag rule、TAG 節點只展開一次、tag 測試技法。wooden_shovel 與 iron_axe 會在 3c 翻轉。`ff3aecc` 的遠端 CI 要等本筆 push 之後讀回。

### 2026-10-30：D1b-3c-0（Step0）結案

- 3b 遠端 CI run `40948195881` success。
- D1b-3c-0 code `be9d408` 的內容：
  - codec 之前，以已驗元素重建 attribute_modifiers／food／tool 三型 record，LORE 正規化。具名 RED 實測證實了修正前的等值替換，例如 food 預期 999 卻得到 100。
  - ref 先比 key。
  - M9 加上與型別模型無關的交叉檢查。
  - 新增 `RefSink` 介面。
  - 用 Unsafe 補上 ITEM seam 自驗的見證。

  1267 件 ACCEPTED 的逐件內容 hash 不變。乾淨 Canon focused 116/116×2、LeafTest 36/36×2，完整 `run-all-a465f127b08a431db4f24eeda83ebf8c` 自然 END ALL0／JUnit914；spec C0/I0/M3、quality C0/I0/M4。
- 下一步是 D1b-3c-0b（硬化），清單見 M6 note「D1b-3c-0 結案」的「殘餘與下一片」，其中最重要的是 CodecCache 的快取端缺口。這些路徑只有第三方 mod 刻意構造時才會觸發，vanilla 不受影響。3c-0b 之後才進 3c 本體：tool tag rule、TAG 節點只展開一次、tag 測試技法。`be9d408` 的遠端 CI 要等本筆 push 之後讀回。

### 2026-10-30：D1b-3c-0b（硬化）結案

- 3c-0 遠端 CI run `40962495545` success。
- D1b-3c-0b code `ae25614`：
  - 以私有 `PrivateLookup` 建立 ops，讓 identity 獨立，隔離 CodecCache 的快取端；具名 RED 證實修正前 payload 會被替換。
  - tool Optional 的內容 class 驗證。
  - potion／stew／banner 正規化為 DEFAULT，並加 iterator 檢查。
  - copy 保真度的全族群 gating。
  - M9 改為逐件雙向比對。

  1267 件 ACCEPTED 的逐件 hash 不變。驗證與審查：乾淨 Canon focused 116/116×2、LeafTest 41/41×2，完整 `run-all-89f998ff5a984fe4ac109f88431f6da9` 自然 END ALL0／JUnit919；spec C0/I0/M2、quality C0/I0/M4。
- **controller 範圍裁示**：必須以反射、access widener 或 mixin 繞過存取限制才能觸發的殘餘，只寫進文件與待辦，不再另開硬化切片。
- 下一步：3c 本體，包括 tool tag rule、TAG 節點只展開一次、tag 測試技法 T1＋T2；wooden_shovel 與 iron_axe 屆時會翻轉。另外要一併處理 3c-0b quality 的 M1～M4（測試與文件），清單見 M6 note。`ae25614` 的遠端 CI 要等本筆 push 之後讀回。

### 2026-10-30：Claude 交回 Codex（本檔到此為止）

- 3c-0b 遠端 CI run `40975183995` success。
- D1b-3c 本體 code `fe76d49` 已 commit，但未驗證、未審查。
- 使用者指示收尾並交回 Codex。後續入口改為 [`2026-10-30-m6-codex-resume-prompt.md`](2026-10-30-m6-codex-resume-prompt.md)；本檔只保留為 Claude 期間的歷史紀錄。
