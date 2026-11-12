# QuantumChamber M6：Codex 接續提示（2026-11-02，Task 8 縮範圍裁示）

**2026-11-12 M6 已以 `--ff-only` 合併 main，並建立 tag `m6`（使用者同意）。下一步：main 是否同步到本 docs commit、M6 工作樹的去留、下一個 milestone 的範圍，都等使用者裁示。** 人工 smoke：使用者選「由 Claude 代操作（建議）」，並授權在測試世界改檔與下載 Fabric 官方 server launcher。
- 人工清單 10 列全部 PASS：
  - 單人三輪 NEW 抽到 same-seed、toggle（amplified）、random 三種 profile；
  - 重進與完全重啟都回到同一 U；
  - 宇宙內重生、共用 Nether／End、DISABLED（床在另一個 U 的情況未驗）；
  - 多人 dedicated 兩人同 U、重啟後各回原位；
  - 單人與 dedicated 的 `/reload`；
  - release JAR＋Fabric Loader 0.17.2 開啟 named 世界，mixin 錯誤與指紋不符都是 0。
- 沒有改程式，code pin 仍 `6965301`。證據在 repo 外 `C:\Users\Ben\Documents\QC-acceptance-evidence\2026-11-12-m6\`；細節見人工清單與 M6 note「人工 smoke 實際操作與結果」節。
- 8-H waiver 的後果仍適用：`main` 是開發快照，不適合未備份的正式世界。
- 結果 docs `4ffadab` 的 CI 43176799947 attempt 1 三 job 全綠，plan Task 22 最後一項已勾。
- 勾選 commit `9c6bec1` 的 CI 43182315379 attempt 1：build、windows-native success，m6-native 遇到 PID 回收誤判 FAIL（原判保留）。依使用者裁示，m6-native 以 attempt 2 重跑一次，並在合併前修正 `m6-universe-probe.ps1` 的 JVM 身分比對（只改驗證腳本，production code pin 仍 `6965301`）。細節見 M6 note「PID 回收誤判」節。
- `9c6bec1` 的 m6-native attempt 2 success（9 鏈 27 phase／479 反例）。腳本修正 `53573fc` 的 CI 43190436125 attempt 1 三 job 全綠：兩平台 default 166、M5 60、M6 35、legacy 1，Windows normal 13，m6-native `M6_NATIVE_VERIFIED PASS chains=9 phases=27 negativeChecks=479 HEAD=53573fc…`。
- 整合（2026-11-12，使用者原話「main同意--ff-only 前進到 53573fc，可以m6 tag」）：
  - main 從 `0d05f88` 快轉到 `53573fc17fa665d254ed08e9327bb843a5af93e9`，已 push；`ls-remote` 讀回 main 與 feature 都是 `53573fc`。
  - annotated tag `m6`：object `54a8f66f744ed741056785980c8904ef95b0a25f`，peeled `53573fc`，已 push 並讀回。
  - 本筆整合狀態 docs commit 只在 feature 分支；main 是否再同步到這筆，另問使用者。tag 固定在 `53573fc`，不移動。

下段是 fix1 結案時的紀錄。

**2026-11-12 Task 22 fix1 closed；下一步人工 smoke。** CI 43154751486（head `408ff3d`，code pin `6965301`）attempt 1 三 job 全 success：
- Windows：default 166、normal 13、M5 60、M6 35、legacy 1；
- Ubuntu：default 166、M5 60、M6 35、legacy 1；
- m6-native：9 條鏈 27 phase。

Task 22 接下來卡在使用者：先做人工 smoke（單人、多人、宇宙內重生、release JAR、/reload，見 `docs/manual-acceptance/2026-10-24-m6-manual-smoke.md`），要使用者操作或另行授權代操作。smoke 之後由 controller 補 docs truth，並在確定不再改程式後勾 plan 的「最終完整回歸」；main 合併、tag 各自另問。細節見 M6 note「Task 22 收尾與 fix1」節末。下段是 fix1 結案前的紀錄。

**2026-11-12 Task 22 收尾中：fix1（reload 期間物化不停機、journal 不卡）待本 commit CI。**
- 固定指紋值測試已完成：CI 43123630195（head `373f67b`）三 job 全 success。
- 第一次最終 full `run-all-01f3a7de…`（code pin `a9871e8`）FAIL，M5 已知的 AccessDenied，原判保留。
- 整分支審查三席的 Important：reload 期間 NEW 物化會停服、journal 寫不進去，controller 依 8-H 第 3 題選項說明的條件判定，開 production 修正 fix1（code `6965301`；觸發證據是整分支審查與 GameTest RED，不是人工 `/reload`）；mixin 揭露與 AGENTS 舊段標歷史以文件處理。
- fix1 本機：
  - 具名 RED、mutation 8/8、雙 focused；
  - 完整回歸：第一次 `run-all-38b59668…` 遇已知 AccessDenied FAIL（原判保留），重跑 `run-all-831ad7e2…` 自然 `END ALL exit=0`，M6 35/35；這個 pin 的唯一一次重跑已用掉；
  - spec ✅ C0/I0/M4、quality Approved C0/I0/M5。Minor 都不改 src：spec 席 M-4 補設計勘誤，其餘記為殘留。
  - 細節見 M6 note「Task 22 收尾與 fix1」節。
- 下一步：本 commit 同 SHA CI 三 job 全綠後，進人工 smoke（單人、多人、宇宙內重生、release JAR、/reload）。client、人工 smoke、main 合併、tag 都未啟動，各自另問。

下段是 8-H triage 時的紀錄。

**2026-11-12 8-H triage 已裁示；固定指紋值測試 `a9871e8` 待本 commit CI。** 使用者四題都選建議項。這裡只列選項標籤，題目與選項說明的原文以 plan「Task 8-H triage」節為準：
- 「只出簡易版（建議）」：M6 只出 SHALLOW_V1，H1、H2 waiver。
- 「人工一次＋自動指紋測試（建議）」：H3 只做 release JAR 實跑一次（人工）加固定指紋值 JUnit（`a9871e8`）。
- 「人工 smoke 加驗（建議）」：H4 只做單人與 dedicated 各一次 `/reload` 後啟動量子艙（人工）；重疊 reload 列已知限制。
- 「全部不做，寫入文件（建議）」：H5、H6 與其他殘留 waiver。

人工清單已新增兩列與 waiver 段；`main` 是開發快照，不適合未備份的正式世界。CI 全綠後進 Task 22 收尾：最終 full、整分支審查、docs truth、CI、人工 smoke。client、人工 smoke、main 合併、tag 都未啟動，各自另問。下段是 Task R 結案時的紀錄。

**2026-11-12 Task R（宇宙內重生）closed；下一步 8-H triage。** CI 43079591580（head `33dc315`，code pin `1ead254`）attempt 1 三 job 全 success：
- Windows：default 166、normal 13、M5 60、M6 34、legacy 1；
- Ubuntu：default 166、M5 60、M6 34、legacy 1；
- m6-native：9 條鏈 27 phase。

plan 的「重啟登入仍在同一 U、床點有效」與海上 spawn 落海底，留給人工 smoke。client、人工 smoke、main 合併、tag 都未啟動。細節見 M6 note 的 2026-11-11「Task R 實作」段末。下段是結案前的紀錄。

**2026-11-11 Task R（宇宙內重生）已實作，待本 commit CI，仍 OPEN。**
- code pin `1ead254`（實作 `e9fdbf5` 加審查修正）。production 是 `ServerPlayerUniverseRespawnMixin` 與 `UniverseRespawnService`：exact live U 主世界死亡、原版要退回主世界時，改留在同一 U，位置照原版。
- 本機證據：
  - RED 13 案中 4 案 FAIL；
  - M1–M8 mutation 全部 killed；
  - 雙 focused：M6 34/34、JUnit 12/12；
  - 完整回歸 `run-all-c3f5f4705d584008b26d2b6b0505cc1e` 自然 `END ALL exit=0`。
- 審查：spec、quality 複審都是 C0／I0。
- 使用者裁示「接受，照原版落海底」：U spawn 在海上時，沒床死亡會落到海底，列為已知行為。
- 同 SHA 三 job CI 全綠才記結案，失敗就停下回報。之後是 8-H triage 與 M6 收尾；client、人工 smoke、main 合併、tag 都未啟動。細節見 M6 note 的 2026-11-11「Task R 實作」段。下段是 Task R 設計當時的紀錄。

**2026-11-11 Task R（宇宙內重生）設計中，待窄審；8-H 走廊等待項目已結案。**
- 8-H：CI 43003044905（code pin `c410975`）三 job 全 success，plan 已勾選。
- Task R 落點：使用者在 AskUserQuestion 選「只換世界，位置照原版（建議）」。fallback 只把目標世界換成同一 U，位置由原版重生規則計算，不寫自訂安全 Y。spec §5、plan Task R、人工清單已依此修訂。
  - 下方較早段落中「原版 spawn 同 XZ、同 U 安全 Y／安全處」「兩個 proposed 注入點」等說法都已作廢。
- 設計：SDD `task-r-design-20261111.md`；固定 1.21 native gate 已用 javap 核對。
- 尚未實作。下段是 8-H 實作當時的紀錄。

**2026-11-11 8-H 必要項「M2 走廊等待餘量與失敗連坐」已實作，待本 commit CI；之後進 Task R。** 使用者原話「現在開始排 8-H 的走廊等待修正然後再進Task R」。
- 修正 `c410975`（只改 testmod 的 `M2CorridorGameTests.java`）：
  - 兩個預設 `when` 由 10 秒改為 60 秒；
  - `NativeEntry` 案例在等待逾時時，走既有的正式收尾清掉自己的 session，再丟出原錯，避免連坐。
- 設計窄審複審 C0/I0/M2，程式一席審查 C0/I0/M3。
- 本機證據：
  - 未修正版強制一案逾時：5 案 FAIL，其中 4 案連坐；
  - 修正版：只有強制的 1 案 FAIL，收尾 `phase=CLEAN`；
  - 乾淨 pin：default 166/166、M6 21/21。
- 殘留：一般 `TrustedSpace` 案逾時仍不自清（`TrustedSpaceRun` 有自己的清理）。
- production、案例與斷言都不變。細節見 M6 note 的 2026-11-11「8-H 必要項」段。Task R 尚未開始。

**2026-11-11 Task14 closed；M6 目前由 Claude 實作（使用者原話「由你繼續接手實作」），下一步 Task R（宇宙內重生），尚未開始。** CI 42978379270（head `715c476`，code pin `d73de9c`）attempt 2 三 job 全 success：Windows default 166、normal 13、M5 60、M6 21 全過。

attempt 1 的 windows-native 失敗（default 8 案 M2 走廊連坐，M6 已 21/21）保留原判。使用者裁示兩項，選項原文如下：
- 「重跑失敗 job 一次（建議）」：用掉 d73de9c 唯一一次已知偶發重跑額度。
- 「可以，列為 8-H 必要項（建議）」：題目是「根治 Windows default 偶發時，可以調整 M2 走廊測試共用的 10 秒牆鐘等待嗎？」，選項說明原文是「在 M6 收尾前做：放寬或改寫這些等待，並讓逾時案例清掉自己的 session 以免連坐。數值依 CI 實測耗時定，經審查後才採用，只改測試。」詳見 plan 8-H 節。

8-H triage、client、人工 smoke、main 合併、tag 未啟動。下段是 T14 結案前的候選紀錄。

**2026-11-11 Task14 候選修正：待本 commit CI，仍 OPEN（改由 Claude 接手實作）。** CI42820518452 Windows M6 的 `formal_entry_freezes_policy_three_without_changing_older_sessions` 逾時已查到根因，這是依 CI log 與耗時對照的推論，待本 commit CI 確認：
- 它和 `toggle_owner_settings_match_both_frozen_presets` 同在 batch `m6_universe_profile`。
- 後者在 server thread 同步物化四個 U（CI 約 7.4 秒，XML 7.356；本機 0.6 秒），佔掉前者固定 10 秒的牆鐘返還等待。

修正 `d73de9c` 只把該案移到獨立 batch `m6_universe_profile_entry`，案例、斷言、timeout 與 production 都不變。依 B+C 純測試改動：
- 同 pin M6 GameTest focused 兩輪 21/21 PASS，一席審查 C0/I0/M2；
- 不另跑 mutation 或本機 full，production 基準仍是 `5ab3aa4` 的 `run-all-a45fe11f…` END ALL 0。

本 commit 三 job CI 全綠才記 T14 closed，失敗就停下回報。舊 FAIL、BUDGET_FAIL 與 UNRESOLVED 全保原判。Task R、8-H triage、client、人工 smoke、main 合併、tag 未啟動。細節見 M6 implementation note 的 2026-11-11 段；下方「根因未查／不修碼」是當時的停點。

**2026-11-10 Task14 OPEN：同SHA CI的Windows M6正式入口案失敗，已止損，不修碼／不重跑。** code pin `5ab3aa4fe6f4ddb63e0469b7c2148b56b8f1b9ff`，遠端候選 `c1b4ea78115e3bbe81bdd2ecb258e39cf4cbf4e0`；[CI42820518452](https://github.com/Ben513242/QuantumChamber/actions/runs/42820518452) attempt1終局：build success、m6-native success、windows-native failure。具名 `m6universegametests.formal_entry_freezes_policy_three_without_changing_older_sessions`，21案僅1失敗：有限等待逾時，FULL／loaded／ticking／tickingFuture=false；own slot0、chunks182、FULL=0／loaded=0／ticking=0。這是目前觀察到的就緒等待簽章，根因未查，不歸已知AccessDenied／AABB偶發。Windows M6執行與集合驗證兩步失敗，2/3 job不能抵全綠；T14從未closed。Windows FAIL artifact與raw XML/log已保存、digest讀回相符，見本plan SDD `task14-ci-c1b4ea7-20261110/terminal-ci-verification.json`（SHA `951b62f929d124bf7a9e49197b8cbf32f0542743ae9ebc28bc95f62a47a6a130`）。同clean5ab雙focused／8個production mutation KILLED及byte-exact REVERT／完整 `run-all-a45fe11f8f8b4ff8bf5218966b65bf1f` 自然END ALL0／outer0與spec C0/I0/M1、quality C0/I0/M0原判保留。首full e016的AccessDenied FAIL及唯一重跑1/1不洗，舊FAIL／UNRESOLVED不改判。為遵守本輪停止指示，交回文件只在本機commit，不再push觸發新CI；最新Git/Java/鎖狀態以ignored STATUS首段與交回索引為準。下一步只等使用者裁示此CI失敗處置；不開Task R、8-H triage、client、人工smoke、main合併或tag。

**2026-11-10 最新裁示續行：SelfTest900秒／8-H A兩案清理已targeted通過，Task14仍OPEN。** user改SelfTest預算900秒，CI m6-native唯一SelfTest5→15分鐘，本機沿inline owned readonly wrapper900，不刪479反例、不再perf優化；一席選撤5ef兩效能改，原PS功能恢復cdb045。原598.8306秒與300deadline兩BUDGET_FAIL根原判不改。M2 offline同UUID真重連正式返還、pin-cap自身清理與全overflow唯一space；維持原unique batch、71M2宣告／default166／所有案例名期待tickLimit/timeout/production容量。最後combined2/2 PASS，兩案space/lease/protectedSlots均0且releaseAck；原primary/partialspawn控制保FAIL並清自己。單席本輪code review C0/I0/M0（050d0e…8c39），900完整回放/CI與Task14原production mutation、雙focused、fullENDALL0、spec/quality兩席及同SHA三job仍待。僅checkpoint不當結案，未新R/其他8H/client/main/tag/人工。TaskR若加native鏈須先估SelfTest>900先報，不能自調。以下較早停止／300不可改均是當時原判與歷史裁示，不覆最新user900授權。

**2026-11-10 最新交回：Task14 OPEN，SelfTest 預算兩次未達，停止後續 gate。** 使用者要求的 Task R 三文件修正已 commit／push `e366571`，CI42756204211 三 job 真成功，T13 既有結案維持。Task14 已依授權開工：本機未 push 候選 `cdb589eb6f84ae8eaccb4aa634cbfb1d5e319540`，六 production owner 完成 policy3／toggle；前置118 JUnit／8 M6 GameTest／13 M4 normal 與完整九鏈27 phase均 PASS，但不能結案。完整 SelfTest479（原277＋202）首輪598.8306253秒超原300，原 BUDGET_FAIL 保留；只 PS1 兩處等價窄修經一席 C0/I0/M0 後，唯一唯讀回放仍在300.0827308秒標 BUDGET_FAIL並停止 exact 自有子程序，wrapper終局300.3782108秒、outer1；無終局輸出，完成 case／首末 baseline UNKNOWN。沒有第三次嘗試或後續 mutation／雙focused／full／兩席 code 審／Task14 CI。PS1修正停未提交 WIP（SHA5efbaeca…67b1），這三份交回文件也保未提交，不為狀態 push 另觸發 CI。九 root 鎖可獨占、最終 Java0，原426原件／舊 FAIL不改判；27 runtime ending 正確為18正常exit0＋9原表預定halt77，不能稱27全部正常停服。R未實作、8-H A/B容量方案仍待使用者裁示，無client／人工／main／tag。最精確原件在 ignored STATUS首段／progress末筆及本輪兩份delivery index。以下較早「最新」均為歷史快照。

**2026-11-10 最新範圍已採用：宇宙只有主世界。** Nether／End 共用原版，T15–T19、T21 不排 M6 或 M7，只在 plan 留未排程構想；未物化 descriptor／維度 key 待辦取消，現有 code／catalog 不改，c7d／e1d 原 FAIL 為歷史。M6 順序 T13→T14→宇宙內重生（取代 T20）→依只有主世界重提 8-H→收尾。U 主世界死亡同 U 有效床優先，否則原版 spawn 同 XZ 的同 U 安全處；U 不可用退 canonical 並記錄，其他維度死亡原版，重啟登入仍同 U。兩個 proposed production 注入點已列 plan 並先回報，尚未實作。人工清單新增共用維度回 canonical 的行為與死亡重生一輪，未執行。B+C 流程／資料安全仍有效；本輪唯一窄審終局 C0／I0／M0 已採用（SHA256 `062ac1acd07c6f073517c50f3cf69adfbaac7ab47ef6bad3e31ec13eb27cff3c`），T13 的 4e600a6 CI 仍獨立追蹤，沒有改判或新 runtime。以下早期移交 M7 等均為歷史快照。

**2026-11-10 T13 候選結案：待本 commit CI，仍 OPEN。** B+C 的 spec v1.5／plan 與三份交接文件已經唯一獨立窄審 C0／I0／M0 採用（審查 SHA256 `938656a1beec0f9eb25034d94fcc28dc9a0de4d155db0ad26636a55f79c82315`）。本次只改五份文件；production／testmod／驗證腳本／build／workflow 不變。採計 0abd50e 最終 17 focused 與同 SHA 三 job CI42675304922、既有 code／final-data 審查；依新 C 不新增本機 full 或 mutation，80f9／f308 原 full／CI FAIL 不改判、已用重跑次數不重置。這份候選文件的同 SHA 三 job CI 真成功後，才在 ignored STATUS／ledger 與交付記 T13 closed，停 T14 前；若 FAIL 仍按止損回報，不提前宣告完成。M6 僅 Overworld／capability0，T15–T21 移 M7，8-H 與 M6 收尾及人工／main／tag 門檻照下方 B+C 有效條款。

**2026-11-10 最新裁示：M6 採 B+C，僅交付 Overworld。** 使用者原話「可以B+C」：T13 收尾與 T14 normal↔amplified mutation 完成後，提前進行 8-H triage 及縮範圍 T22；T15–T21 整段移交 M7，`FamilyCapabilityVersion` 維持 0。8-H 依只出 `SHALLOW_V1`／Overworld 提必要項與 waiver，由使用者裁定。production 原門檻不變；僅測試／腳本／文件採一席審查、免 mutation 與本機完整回歸，CI 照常，純文件 CI 若僅已知偶發失敗不重開 task。資料安全關卡及舊 FAIL 全保；同 code pin 的已知 AccessDenied／走廊容量連坐最多保留原根後重跑一次，文件 SHA 不重置額度。先改 spec／plan 並經一次獨立窄審 C／I 清零，再接續 T13；目前 T13 仍 OPEN，未啟 T14。M6 收尾仍須完整回歸、整分支審查、CI 及單人／多人 smoke 各一輪；main 合併、tag、人工代操作須分別另獲授權。以下較早「最新」均為歷史快照。

**2026-11-10最新止損交回：本次一次完整重跑已FAIL，T13仍OPEN，不再重跑。** 四docs已commit/push `f30853a2ca6429050e890568cb35dc5ef1501af0`，除四docs外550tree entries對0abd50e全exact。新full50cee6a578f8476f81e1bc1ef10a11bf原w10exit0，但default runtime逾時、stdin stop未完成、Restore因liveowner拒絕，outer1／無ENDALL／後續gate未啟；default新XML缺席，不造166或完整fail數。live具名trusted_publish_checkpoint_failure_preserves_false_windows及east_complete_doors_require_checked_ledger_windows皆AABB容量不足，更早pin>256只列特徵。sameSHA CI42732476087亦終局FAIL（Windows default／缺defaultXML連帶step9，south prepareRemap與north constructor容量錯），build/native成功不抵銷。已依user本輪任何failure停報；無再gate／source／OS修正，原80f9及所有FAIL不改。唯一owned orphan已精確身分核對後收尾，failedfresh完整保留、鎖釋放，原harness restore拒絕不改成exact restore。最新細節在ignored STATUS與本輪兩份index；下方准一次重跑等是當時裁示，額度已使用。T14仍等使用者、新CU-Uonly-client未准，未製作結案commit。

**2026-11-10最新裁示：80f9符合Windows原子移動AccessDenied已知限制，本輪准新nonce完整full一次。** 80f9原root/FAIL不改；不修production、不改Defender／索引／OS。先提交並push本次四份失敗交回文件，核code與0abd50e完全相同，再以該乾淨文件HEAD完整run-all；80f9在m5-gametest後未執行的gate一律不能沿用。這次任何AccessDenied或其他FAIL均停報、不再重跑。END ALL0與同SHA三jobCI都過才做結案文件；結案文件先寫「待本 commit CI」，其CI真正通過才算T13結案。仍停T14前，不啟新canonical＋U／U-only／client；每commit／push／gate終局更新ignored STATUS首段。下方較早止損是當時快照，本條例外僅限已確認簽章。

**2026-11-10最新交回：T13 OPEN，依止損停下，未結案。** 程式pin0abd50e92592e9d50a1c2fa0fe1c944409608c95已push；17個最終focused與同SHA CI42675304922三job均PASS，但本機Windows新full80f9ddf169964559a7a787101133cd9d在M5 GameTest60案中1FAIL：`m5universeprojectiongametests.prepare_seals_a_conflict_when_another_chamber_completed_an_overlapping_projection`。原atomic move projections.dat.<nonce>.tmp→projections.dat拋AccessDeniedException，checkedWrite回WRITE_FAILED，fixture的index.confirmed嚴拒；outer1／無ENDALL，M6及後續未啟。這不屬指定Windowsdefault十案，不自行歸偶發／waiver／rerun，不改M5／production／Defender／索引／retry。來源只讀查證已完成，鎖持有者原因未證；原全FAIL保。完整資料在本plan ignored STATUS首段及full-explicit-utf8-supplement-index，T14仍等使用者、新CU-Uonly-client未准。本次只交回失敗事實，沒有結案文件commit；以下較早可定版／待C4等為當時快照。

**2026-11-10目前：M2兩席code C/I已清，最終C4待驗。** 最終候選source `51fbf9198f1732dc604698fd73eb4afad35ba760b03c6fa15b5b2d2cd359c17e`，PS1 f12固定、1100compiled exact；fix4正常9／唯一default166真PASS與fix5正常9真PASS各依原source pin分列，不冒最終C4。原finish logger I1由fresh第5輪最小+9/-7修正，兩席限定複審C0／I0／M0；所有原FAIL／UNRESOLVED與scope-tainted控制／checker SETUP原判保。接著只定版此source，最終同乾淨pin原雙focused／完整full／同SHA三jobCI待，T13仍OPEN；結案文件commit另先寫「待本 commit CI」，其CI真過才閉，嚴守最新止損與T14等使用者。

**2026-11-09最新止損裁示（優先於下方較早流程）：** 本輪只完成已審七案，七案外10案不修、不新設計或加觀測。最終固定pin完整full與同SHA三jobCI全過，才依原門檻收尾；若失敗僅Windows default該10案或同類偶發，保原件、列平台／job／具名測試／失敗特徵後停等使用者裁示，不重跑湊綠；其他失敗停查、不得歸偶發。T13結案文件commit先寫「待本 commit CI」，該commit CI通過才算結案，不預寫closed或在CI過後再遞迴發狀態commit。每次commit、push、gate終局更新本plan ignored `STATUS.md`首段（現在已補正，後文舊快照保留）；T14開工仍須使用者回覆，新canonical＋U／U-only／client未授權，全部舊FAIL原判保留。

**2026-11-09最新：T13 OPEN，fix3候選兩席0、新pin C4待驗。** 43cb99b純四docs CI42608921665終局build成功、兩Windows job失敗：native21phase成功後SelfTest原300秒逾時；Windows原default166中15FAIL，normal13／legacy1／M5 60／M6 19零bad，step9為default scope連帶拒。15案因果與case級cleanup完成未證，原件已保存。PS1-only fix3 source f12d80ee…69bf60已依受審設計完成候選、真3ea全7／21／277＋末baseline152.704秒、兩席code C0／I0／M0；新pin focused／full／同SHA真CI待，不把本地結果當新CI。使用者已明確新增M2CorridorGameTests這批TrustedSpace等待／失敗cleanup有界授權：原tickLimit內條件等待、原ready／功能斷言、正式cleanup，唯一設計已獨立終複審C0／I0／M0並採用，開始七案限定實作／驗證；不production／外部observer，尚未最終C4驗收。d0原PASS、43新FAIL與formal報告各保原判，T21不變、不進T14。以下較早結案／初報段均為歷史快照。

**同日fix3採用狀態：** 原XML現已分scope核：default166案15fail（10個有限等待逾時、5個局部實例／完整AABB容量不足），normal13／legacy1／M5 60／M6 19零bad、JUnit1107零fail/error且既有skip2；step9因default scope連帶拒，未證M6案例本身失敗。PS1-only fix3唯一設計8557…10cb＋plan已窄審C0I0M0，只准manifest cheap前置與SelfTest exact phase-scope，保真Verify全7／21、277、原300秒及全部criteria。實作／真單run完整277 bench已完成152.704秒／原300秒，兩席code C0／I0／M0；新pin gates仍待，M2已獲上述新窄授權，唯一設計終複審C0／I0／M0，現受限實作／驗證中，不新增外部observer；T13仍OPEN。

**2026-11-09 T13 正式結案，依裁示停在 T14 前。** 程式pin `d0aafb47d574f5fff24f712246bbf3508ec2a635`：C3原雙focused與兩preset各雙fresh native鏈全過，完整 `run-all-3eaeb70caecd4e39b4e22935f05cd53d` 自然END ALL0／outer0，summary SHA `9bf3ceb82469b993d4e6b3205e8b08fc32cda86db86933c3e1831553a2bf9c05`；原native-green與末段verify各277通過，沒有新增standalone。CI42595046917同SHA build／windows-native／m6-native三job終局success，真SelfTest258秒／原300秒。formal spec／quality各C0／I0／M0，可關閉T13；source／1095class／554tracked前後exact。final1440rows與全部差格已獨審，producerUNRESOLVED／NOT_CLASSIFIED保留，不推根因、不採baseline或容差。舊FAIL不翻案、T21 exact不變，T14與新增canonical＋U／U-only／client皆未啟動。後續只四份結案狀態文件，程式驗收pin固定；文件HEAD／push以Git實際為準。詳M6 note「T13正式結案」與本檔§8；下方舊「最新／待驗」均為歷史快照。

**2026-11-09 C2 full 原判 FAIL，continuity evidence-base fix2 待出口驗證。** code `d973710cf21c89fa5f491dc3b9874e2bdf11058c` 的 CI42581484000 三 job 終局 success（真 SelfTest273秒／原300秒、7鏈21phase／277反例），C2 雙 focused 全綠；本機短路徑 `run-all-f8eefde1119b4f55b24fde36668368db` 所有 runtime gate exit0，最後 `ci-rule native-green` 因 continuity 從 fixture `$project` 推錯 CARVERS 路徑而 FAIL／outer1／無 END ALL。原 sidecar 在且 SHA 相同，並非少複製檔案；原 failed fixture 與較早深路徑 preflight FAIL 全保留。fix2 只既有 PS1 明傳 caller 已驗的三 phase receipt dirname，不減守門／不加 fallback／不改 timeout。真 f8 AllEnabled073c輸入全 Verify＋277 自然203.610秒通過，僅屬唯讀 parser 回歸；新fixture e25355a8… replay亦自然PASS（196.995秒／outer與step0／277），同兩席fix2窄審C0／I0／M0；新pin C3／full／同SHA CI尚待。T13 OPEN，不進 T14；Java／A20／T21／舊 FAIL 不變，新增 canonical＋U／U-only／client 未授權。以下較早「待驗／最新」均為歷史快照。

**2026-11-09 verifier fix1候選已窄審，C須新pin重驗。** code4593 CI42572502698整體FAIL：native七鏈21phase PASS但SavedSummary-SelfTest超既有5分鐘，其他兩job PASS；原判不改。C舊pin僅compile+四class雙JUnit92 exact，其餘未啟即停，不能挪作新SHA。fix1只既有PS1抽global/chain共用流程，真Verify仍全7鏈、局部SelfTest仍global+受影響chain、原277反例/所有predicate/前後fullbaseline保留，無PASS cache/無提高timeout。parser-only合成bench189.947s（不是functional/CI重播）與同席spec/qualityC0/I0/M0齊；真正新code同SHA CI及C full仍待，T13OPEN/no14，T21/Java/CARVERS/舊FAIL不變，CU/Uonly/client未授權。

**2026-11-09 最新停點：Task13 未結，僅 T13 後收尾。** feature code pin `1332c527b829b4a228471de541aff493cdcc7e49` 已 push／遠端同 SHA、porcelain 空；乾淨 milk/no-entrance 與 same-seed owner/selector 各雙 focused 2/2、SelfTest 70、正式 spec／quality 各 C0/I0/M1。同 pin CI `42499957714` 三 job success：Ubuntu／Windows M6 XML 各 19 案含 T13 三案零 bad、JUnit 各 1107 零 bad；Windows 原生七鏈 21 phase PASS，Ubuntu 只證 `UNSUPPORTED` 拒絕。本機 `run-all-dd57e94d52274671b4cfc506b390a9ab` 在 default、normal、legacy、M5／M6 GameTest、main、M3、recovery、M5 collapse 69 phase 自然 exit 0 後，M6 native canonical normal→U `new` 在 `(527,72,512)` source air／target spruce leaves 具名 `M6_SAME_FUNCTIONAL_FAILED`；outer exit 1、無 `END ALL`，summary SHA-256 `26804DFD00281087765A0F3D0EE1ABEDC5FD328E571C1B2E94C34230AE53B6B8`。比較收據 SHA-256 `22C9D5EDD7AB4997D5ECEF044AC53E290C02F68CCE10A2176316D467D9A12B37`：兩側 3×3 virgin PASS，四階段在 BOTH_CENTER 首見差且後續不變，target native consumer exact，canonical source consumer `NOT_COVERED`；根因未證。舊 `35f5` amplified FAIL、Task21 U→U c7d/e1d 及全部歷史 FAIL／NOT_KILLED／UNRESOLVED 均不改判；CI PASS 不抵銷本機功能 FAIL。不得改測樣本或請求順序湊綠；下一步先有界查生成寫入者／source owner，再設計與獨審可驗修法。Task14、人工 smoke、main／tag 均未放行；本對話依使用者指示在此收尾，不問裁示。

**2026-11-09 最新接續點：Task13 E1/E2／amplified 候選未提交，milk handoff 已窄修。** 基準 docs HEAD `9b04f6d08d9234c4c8ad6a53143319bf49cb7ef1`，三既有程式／測試檔與 Task13 plan WIP。E1合成時戳SelfTest70 PASS，正式 freshness未放寬；E2候選先遇 trustedTeardown 同步／異步交接 Important，專用設計三輪修至 C0/I0/M0 後同M2檔實作。同步before／after／partial LOW、異步condition／ready／Error、post-return／close、有界INCOMPLETE、跨tick多錯各有獨立負控及原錯／terminal／stop／正式清理收據；故意stop-throw不冒自然停服，首Error收集UNRESOLVED及各SETUP原判保留。same-Throwable close 的RED→GREEN→撤旗標mutation已具名並REVERT；未注入的最後 milk＋no-entrance focused2/2、default166/166零bad、世界exact restore。提前程式審 C0/I0/M1（診斷log在LOW fence後若自身拋錯會保守INCOMPLETE／停服），非正式Task13放行。amplified唯一新根三phasePASS、四階段一致且target owner exact，但舊 `35f5`煤礦／石頭FUNCTIONAL FAIL仍原因未明，不能追認。下一步精確三檔交付與四份docs truth審查、code commit立即push、乾淨雙focused/full/兩席review/同版CI；只處理Task13後收尾，不進Task14，舊FAIL／NOT_KILLED／UNRESOLVED不改判。

**2026-11-08 最新接續點：Task13 `b704102` full／CI 各保 FAIL，後續設計已審未實作。** 本機乾淨 `b7041021260547117852249779a16c6c337152b7` 的六個 A/C focused、M6Compile、SelfTest70、正式 spec／quality各 C0/I0/M0；完整 `run-all-35f5c298641f4b7cb043de022d548f76` 的前各 gate exit0，M6 native amplified 真玩家 `new` 在 `(527,64,512)` source coal ore／target stone 而 FAIL（summary SHA `0E685B40FE3DE047CD3DBB7CC65017F651A47DC6EDDDBD1E6B593D9097756A94`），無 `END ALL`。這是 Task13 保留的 canonical→U 全量驗收，不能移 Task21；18格 virgin／同請求／taiga／FULL／seed相同仍不足以判原版或 production 根因。遠端 CI `42476033674` Windows default166/27FAIL、首敗 custom milk remap晚於10秒；M4 normal與M5 success；M6 SelfTest synthetic 時戳早136微秒，正式Gradle未啟，workflow FAIL。E1 fixture、E2 milk等待與清理、amplified 四階段／target owner 的三個**既有檔**窄補設計及plan均獨審修後 C0/I0/M0，程式尚未改。舊本機／CI FAIL、U→U c7d/e1d與 NOT_KILLED 原判保留。先完成四份 tracked docs truth 審查／commit／push，才派一個 implementer 做新根 mutation與驗證；Task13/14、人工、main／tag未放行。

**2026-11-08 最新接續點：Task13 A/B/C 候選已局部 GREEN，尚未 code commit／結案。** 已採計畫 `f28ebe4d7b241c7408d1c2e84580a0c06fea6ef8` 之上只四既有 testmod／驗證檔 WIP，無 production。A 的 default／normal 新 session 政策 RED、mutation-only 殺手與逐位元 REVERT保根；正常 default1/1、normal5/5及normal全13/13 PASS。B 在同一既有腳本的受控 self-test59項 PASS：M6+legacy own-scope 先留收據，原 default／legacy／M5 終局 exact 守門保留；缺／錯／舊輸入和寫入延後 mutation具名殺／REVERT，這仍未證新 code pin 的遠端 CI。C 的 stage0 強制未就緒負控60秒後具名 `KILLED_CLEAN` 且正式LOW／return／release／close與逐位元還原；正常單案1/1及 default 全scope166/166零bad PASS、世界 exact restore。首 B 自測 SETUP FAIL與首 A 收集器 `NOT_KILLED` 都保留原判。提前程式審查的 B 重用 `OutputRoot` 殘舊 full PASS Important 已候選窄修：正式 launched 前空根與共用尾段舊收據雙守門，final SelfTest `t13-b-reuse-final-570cabda9a7b4477b88a79fc36954d5a` 70項PASS、兩接線mutation殺／REVERT，原審查者窄複核 C0/I0/M0；這仍是純fixture，真CI待code pin。設計 SHA `F36FCB11AB7E621D8117E01EB9F1517BA2990172DBD87553AAE9485B7B3B39B6`。舊 `a92` full FAIL、`42458754297`／`42460563636`／`42462792915`／`42467124106` Windows CI FAIL、Task21的 `c7d`／`e1d` U→U FAIL 不追認。交付清單核對後，先四份 tracked docs 窄審，再四檔 code commit並立即push；乾淨雙focused／full／兩席review與同版CI仍待。Task13、14及M6整體未完成。

**2026-11-08 最新接續點：Task13 CI 相容設計已獨審，待實作與重新驗證。** code pin `a92acb942c3e455c5f2daa37ff5dd23d0a8d49cd` 已 push；四個 JUnit 類別各雙 focused、owner／selector GameTest 雙 focused、canonical normal／amplified 各三 phase 原生鏈均在此乾淨 pin PASS，正式 spec／quality 各 C0/I0/M0。完整 `run-all-a872681b0343473e883590ee9f6e13ac` 首段 default 166案只有 `native_start_freezes_candidate_context_before_geometry_windows` 的 policy1期待／實policy2 FAIL，原 root及world exact restore保留。CI `42462792915` Windows default逾時、normal13/13 FAIL、M6 raw XML19案0 bad但驗證腳本讀缺 default XML而step FAIL，整workflow FAIL；Ubuntu build及Windows m6-native success不能抵銷。`42458754297`／`42460563636` 舊FAIL另留，皆非 T12 已知。Task13 plan 既有檔 A/B/C 相容窄補設計初 C0/I3，fix1後 C0/I0/M0，**尚未改 code**：A 新session policy2的default／normal舊測試；B先留本次M6+legacy自有收據、完整四scope守門仍在；C單一M2 no-entrance兩段等待改既有60秒具名追蹤與正式清理，完整逾時因果仍UNKNOWN。此純docs採用後才派 test-only/script 修正；不改production、不重判任何FAIL。Task13、Task14–21、人工、main／tag均未完成；U→U c7d/e1d仍留Task21。

**2026-11-08 Task13 spec Minor 窄修提交前快照，尚未結案。** code pin `860da7bf6ddda9971f890ad819fdd20c3465450b` 已正常 push／遠端同SHA，正式 spec 審查 C0/I0/M1；唯一 Minor 是直接 selector GameTest 名稱誤稱正式入場。原 implementer 僅在既有 `M6UniverseGameTests.java` 更名、改斷言訊息及註解，compile `eb373502...` PASS、focused `gate-t13-selector-name-focused-6287faa2648040628c2120293a3e3ed7` owner＋selector 2/2 PASS；最終精確名稱根 `gate-t13-selector-precision-726ca36ed9034d36ba2bc23b8f985eb2` 另2/2 PASS、世界精確還原、Java owner0；窄修與三份 docs truth 未 commit／push。正式玩家入場以 Windows canonical normal／amplified 原生鏈與既有 Windows guarded GameTest 採計；Ubuntu `m6_universe_same_seed` 只證 owner／設定及 selector 控制，不冒玩家入場。純docs `ec60547` CI `42458754297` Windows default GameTest 先有 M2／M4 AABB 容量例外，最後 M5 具名 batch 未完成而30分鐘逾時；該 job／workflow FAIL 原判保留，不歸 T12，詳細 log 及初因見 M6 note 末節。舊 `c7d`／`e1d` U→U FAIL 保持，Task21 再啟 full test。下一步窄修新 code pin 立即 push，後跑乾淨雙 focused／完整回歸／獨立 quality／同版 CI；Task13、14、M6 整體未結。

**2026-11-08 最新接續點：Task13 裁示已採、testmod 候選 GREEN，尚未結案。** 使用者選「U→U 全量比較移 Task21；T13 保留 canonical→U 真玩家鏈與雙平台 owner／設定測試」。spec／plan 純文件 `ec605477890e044e112fe4b2b2a43d099be96e13` 獨審修後 C0/I0/M0、push／遠端同 SHA；CI `42458754297` 以即時狀態為準。原 18 檔 Task13 WIP 未 stage／commit／push；只在既列兩個 testmod 檔分拆 owner／full，舊 full helper、1440 樣本、structure／建築／edits／stage 逐位元保留，full 方法暫不標 `@GameTest`，Task21 重新啟用。新錯 seed 負控 `a94b746...` 具名 `SAME_NATIVE_CONSUMER_EXACT` 且 REVERT；新正常 focused `gate-t13-owner-green-d2a052f4236f4db2a15ef7b544c436da` owner＋直接 selector 控制 2/2 PASS（後者非真玩家入場）、normal／amplified 四列 consumer PASS，均為 dirty 候選。原 `c7d`／`e1d` seagrass／water FAIL 保留給 Task21，不判 PASS；Task21 仍不開動態 U-origin 真艙。先建立 code pin 並依持續授權立即 push，再做同乾淨 HEAD 雙 focused／full、獨立 spec／quality 與同版雙平台 CI。Task14–21、人工、main／tag 尚待。

**2026-11-08 最新接續點：Task13 WIP、U→U GameTest 範圍裁示待回。** 本停點提交前的 Task13 code base HEAD `5e9dc8fb7a2274858dc874ad517114bdf7cd014b`，遠端 feature 仍 `f77f9d608206bd61c938cf08b320f8eb17fb83ec`；18檔白名單 WIP 未 stage／commit／push，位元組保全於 ignored `task13-scope-pending-20261108/manifest.json` SHA `1340A33D9BB60EF8FD3FF3C15C2FF0CBE9984B0C0F57A67F0883C73B58F07F8A`。候選 JUnit1107／正式入口GameTest、production與testmod負控有局部PASS／具名殺；Windows原生 `m6-native-run-c9eb67b128a4421a87954f6b25e1ba4f.json` 七鏈21phase PASS／Summary SelfTest255，均屬 dirty候選。**同 JVM direct catalog U→U GameTest 仍FAIL**：c7d原根的 seagrass/water 已入raw MCA、兩側九格 virgin 與actual consumer對 frozen expected均PASS；e1d四階段根在兩中心剛FULL時即見差，後兩鄰域階段未變，structure／建築／edit因terrain先FAIL未執行。不能判純vanilla或production根因，也不能以canonical normal／amplified真玩家 native PASS抵此GameTest。Task21前正式U-origin艙不可達；已向使用者請 U→U全量留Task13查因、移Task21改驗收分工，或縮binding terrain語意的具體裁示。答覆前不刪原樣本、不改接受條件、不啟新Java；所有FAIL/NOT_KILLED原判保留。最精確根與限制見M6 note末節、plan Task13最新、ignored STATUS首段／progress末筆。Task13 clean雙focused/full/正式兩席review/雙平台CI與Task14–21仍待；M6人工/main/tag未放行。

**2026-11-08 最新接續點：Task12 自動切片驗收完成，下一片 Task13。** test-only code pin `76111e8e0486baeb9c7cf3835e0ab397eab576e0` 已正常 push／遠端同SHA，production未改；唯一設計獨審及程式spec／quality各C0/I0/M1，同一Minor為固定16格bedrock只證有限儲存樣本。乾淨 M5三案雙focused各3/3、partial四phase雙輪PASS；本機 `run-all-7c079c8613c54067a5b5094bc62823fa` 自然 `END ALL exit=0`（summary SHA `7A345D621A422BA639EA8E4DB27868FB558BC893288EC7D43762EC7B9C0860D9`、JUnit1100、M5 60、M6 17、collapse69、native15），五世界精確還原／owner0／3116 locks可獨占。同SHA CI `42436349061` Ubuntu build、Windows windows-native、Windows m6-native及workflow全部success，四artifact名已讀未解壓。舊PREPARE／wire／partial、首本機full及本片SETUP／未殺根全維持原判；wire A1/A2歷史 `UNRESOLVED`，依使用者裁示原四tick自然熔岩不可觸發，這次以寫前非流體與B驗收。partial新16格僅有限storage樣本，seed/noise/biome另驗，不能稱整chunk天然地形不變。四份 tracked docs truth 正在獨審／待純docs commit，通過後依 Task13 已採用 plan 派 policy2／same-seed implementer；不重跑相同 Task12 focused。Task13–21、人工 smoke、整體 M6、main／tag 仍待。

**2026-11-08 稍後最新接續順序：** 使用者裁定 Task12 的 PREPARE／wire／partial 是測試判定沿用平地假設，授權只改測試、不改 production、舊 FAIL 保判：PREPARE 在 tickLimit 內等條件而不預熱；wire 寫入前四周非流體直接斷言＋負控 B，A 在原四 tick 不可觸發；partial 比 crash 前已完全生成區塊，加 seed／noise／biome 確定性查詢。Windows 30 分鐘無具名結果逾時列 `UNKNOWN`，自本裁示後再發一次才查，不擋三項。**Task13 plan 補充先完成同一次獨立窄審並採用 → Task12 三項一份合併設計、一次獨審 C/I0 即實作收尾 → Task13 程式實作**；不再追加 Task12 觀測診斷。前面「先 T13、T12 保留未結」是同日較早裁示，已被更具體的本順序取代。既有 K1–K3 CI 指紋與所有失敗 root 原判保留，不是當前跳過 Task12 的授權。

**2026-11-08 Task13 CI 新裁示：** plan Task13 同輪補列 T12 已知失敗 K1–K3，僅同平台／job／具名測項／特徵相符才可視為既有阻擋；Windows 30 分鐘無具名結果逾時是 `UNKNOWN`，不得列入。T13 新增 JUnit／GameTest 必須 Ubuntu、Windows 各自 PASS；`same-seed-restart` 只在現有 Windows `m6-native` 跑，Ubuntu `-CheckUnsupportedNative` 僅驗拒絕契約。清單外 FAIL 停下查因；T12 暫停新增診斷，T13 後先交包含預熱設計 I1/I2 解法的根因／修法選項供裁示，不再逐項加觀測。

**2026-11-08 新裁示／Task13 準備：** 使用者原話「先實作 T13，T12 保持未結（建議）」。到場 local／origin HEAD `048e8359c9cd61a611f9e43b298ab4b757a54533`、porcelain 空、無非 daemon Java owner。純文件 CI `42419184480` 亦 completed/failure：Ubuntu／Windows M5 PREPARE 同案仍有非 FULL chunk，Windows default 另有30分鐘逾時；三者原判分開，`m6-native` success。Task12 的所有既有 FAIL、wire A `UNRESOLVED` 與 code CI `42411190339` 原判均保留。Task13 plan 白名單的三個 strict production guard 與三個測試漏檔已草擬補充，需獨立窄審採用後派 Task13 implementer；不新增 production 注入點、新驗證腳本或外部 JVM observer。Task13 自身門檻照跑；若整體 CI 仍受 Task12 影響，不可冒稱分支 PASS，main／tag／人工驗收不放行。細節見 plan Task13、M6 note 末節、ignored STATUS／progress 最新筆。下方較早的「最新」段落都是歷史快照。

**2026-11-08 最新接續點：Task12 未結／Task13 未開始。** 最新 code pin `5c7edc99097e8b2e94ad69b76d2c661b1764b78f` 已正常 push／遠端讀回；M5 wire fixture 增量的 spec／quality 程式 C0/I0、新 B 負控補證 C0/I0/M0，A 熔岩負控仍 `UNRESOLVED`，不能稱 wire 修復結案。乾淨雙 focused 各 1/1 PASS；本機 `run-all-402db178e3d64e6194630859f1acce24` 自然 `END ALL exit=0`（JUnit1100、M5 60、M6 17、collapse69、native15），五世界精確還原、HEAD／porcelain 乾淨、lock可獨占。前根 `run-all-9dddc0a0642a43fca91af08082b8ae87` 在 M4 recovery 因額外 Yggdrasil 公鑰 ERROR 整根 FAIL，永保原判。同 pin CI `42411190339`：Windows `windows-native` 與 `m6-native` success，Ubuntu `build` 的 M5 `prepare_waits_while_another_projection_write_is_unconfirmed` 在 400 tick 四票 level33／三格非 FULL 而 FAIL，workflow 整體 FAIL；該 run 的新 wire 案 PASS。partial-overworld 的其它自然 FAIL 仍保判。精確四格同步預熱設計獨審 C0/I2/M1，未採用；下一步先處理 PREPARE 與 partial，或待使用者明確裁示 Task12 保留 FAIL 時是否先進 Task13。最精確的本輪原件與後續條件見本檔 §8 最新交回紀錄、M6 note 末節、ignored STATUS.md 首段／progress.md 末筆。下方 2026-11-06 的「最新」段落均為歷史快照。

全程使用繁體中文（台灣）、精簡回報。先核原件，再推論。本檔取代 [`2026-10-31-m6-codex-resume-prompt.md`](2026-10-31-m6-codex-resume-prompt.md) 成為唯一接手入口；10-31 檔保留為歷史，其「下次工作順序」一節已被本檔第 3 節取代，其餘停點事實與原 FAIL 原判仍有效。

**2026-11-06 最新接續點：Task11 結案，Task12 待派。** code pin `232359a9eccf93b7d939c0f05ebe0f400d66146e` 本機 `run-all-a08ab0aec02c49239ff32334cd0b9d74` 自然END ALL0／外層exit0、JUnit1093/0/0 skip2、M6 11/11、main-onlyPASS、recovery24、collapse69、native診斷2＋catalog功能4＋seed功能2；雙 focused JUnit7／GameTest1／native兩phase，spec／quality各C0/I0/M1。feature docs HEAD `3452e311058647aa8998436d72cac26612156a2c` 已push/ls-remote同SHA，GitHub run `42195022397` 同SHA completed/success，Ubuntu build、Windows windows-native雙job及M6/main-only/精確集合/原生案例/證據上傳關鍵步驟成功，三artifact清單讀回未解壓。Task11只是生成seed/settings owner；Task12 Step0先修Backend:38過時Javadoc，再由正式入口令新session凍結policy1、NEW-only lazy source、checked COLLAPSING→allocation2→catalog2→EAGER_ENABLED→物化／ACTIVE／discovery及兩跨JVM功能鏈；**玩家random NEW目前仍不可玩**。Task12唯讀預查判原plan單片可行、未見必需plan外新注入/腳本/observer；本四份閉幕docs truth獨立窄審C0/I0/M0；待純docs結案commit/push後派Task12。H5/H6後置，原FAIL不改判；main合併/tag/PR/退役仍另守門檻。以下舊接續點及§0/§9派工文均為歷史。

**2026-11-06 最新接續點：Task11 本機／雙審過、遠端codeCI待。** 最終code pin `232359a9eccf93b7d939c0f05ebe0f400d66146e`，乾淨雙 focused JUnit7/GameTest1/native construct-reopen兩phase，完整 `run-all-a08ab0aec02c49239ff32334cd0b9d74` 自然END ALL0且外層exit0，summary SHA256 `8228208F1231EB909E96944019AEE26D4F20F8D2C33BD424E4A2869CA02B9361`，JUnit1093/0/0 skip2、M6 11/11、main-onlyPASS、recovery24、collapse69、native診斷2＋catalog功能4＋seed功能2；五restore exact／2552鎖釋放／非daemonJava0，最終索引SHA256 `4EB3AEE67C7955D2148A6641CD84EE9E66C11BC14AF9FF3847B963BE20B0D1BB`。world-map I1 同原生map短monitor修正，原席窄複審ADDRESSED C0/I0；整段spec／quality各C0/I0/M1，同一Minor是backend:38過時Javadoc，Task12 Step0接。外部mod造owner呼public setter殘餘列Task8-H H6，未防護、不冒PASS。Task11只驗seed/settings/locator owner；正式玩家random NEW仍待Task12。本五份tracked docs truth已獨立窄審C0/I0/M0；待commit／正常push、等同SHA Ubuntu/Windows雙job success才結Task11／進Task12。原 `9ebe351` full只屬前pin；錯importSETUP、錯seed/locator RED、flat GameTest FAIL、初NoSuch與其它retained FAIL不改判。main合併/tag/PR/退役另守門檻。以下較舊接續點與§0／§9派工文均為歷史。

**2026-11-06 最新裁示與接續點：** 使用者原話「同意 Task 11 窄補（建議）」。固定1.21 原生 `ServerWorld.locateStructure` 另讀全域 `GenerateStructures`，會讓新 U 凍結值與全域相反時的終界之眼／探索地圖等原版用途錯誤；獨立唯讀 native 審已證原計畫 constructor／worker／getSeed注入無法涵蓋。spec §4／plan Task11 已草擬**唯一**新 `ServerWorldUniverseSeedMixin` callsite，exact checked schema2 `NEW_M6` 回 frozen bool，其餘原版；雙方向可定位fixture、眼睛／地圖witness、mutation與remap/native gate必備。兩份tracked草稿獨立設計窄審C0/I0/M0，五份docs truth亦獨立窄審C0/I0/M0，待純docs commit採用後放行此注入；Task11 code仍是未提交 WIP，尚無完整RED/GREEN/full或審查，不能稱Task11完成。其它 plan 外production注入／新腳本／外部observer仍待各自使用者裁示。Task10已結案，Task11 seed/backend與Task12玩家random NEW仍待；H5後置，原FAIL不改判。以下較早接續點與§0／§9派工文均為歷史。

**2026-11-06 最新接續點：Task10 結案，Task11 待派。** code pin `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c` 的本機 `run-all-7f5d72f098a242efb685cea1f9135d4a` 自然 END ALL0／外層exit0，JUnit1086/0/0 skip2、M6 10/10、main-onlyPASS、recovery24、collapse69、native診斷2＋功能4；三類雙focused126案次＋catalog功能四phase雙輪，spec／quality修後各C0/I0/M0。feature docs HEAD `cceacd5140f1e1a6e8fedab2430cd67d772ad0f9` 已push／ls-remote同SHA，GitHub run `42163888020` 同SHA completed/success，Ubuntu `build`、Windows `windows-native`雙job與M6/main-only/集合/原生案例/上傳關鍵步驟成功，三artifact清單讀回未解壓。Task10只保存真 `DISABLED` checked catalog2、首tick前halt77，不物化；Task11才做backend／seed owner，Task12才讓玩家random NEW可玩。Task11 functional probe入口計畫窄補已獨審C0/I0/M0；本四份docs truth獨立窄審C0/I0/M0，待純docs整合commit／push後依plan白名單派Task11。首full `run-all-9f3ac0e3f01d4fe58d654ea9853fb293` INTERRUPTED、前pin `95f676dc` 外層晚檢FAIL、fix1首GREEN gzip片段FAIL與所有原FAIL不改判，H5待Task21後triage。main合併、tag、PR、退役另守門檻。以下舊接續點與§0／§9派工文均為歷史。

**2026-11-06 最新接續點：Task10 本機與兩席已過，遠端 code CI 待。** code pin `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c`，三類乾淨雙 focused126案次＋FUNCTIONAL catalog四phase雙輪；完整 `run-all-7f5d72f098a242efb685cea1f9135d4a` 自然 END ALL0且外層exit0，summary SHA256 `B12B46A3E89C892C2A80815F35C2ABAD17CA42AC3DFF8DD005B78711FFD282ED`，JUnit1086/0/0 skip2、M6 10/10、main-onlyPASS、recovery24、collapse69、native診斷2＋功能4，五restore exact／locks2421釋放／Java0；最終索引SHA256 `37981288E398C28065BF20995BAA43B53BCAFD5922C8E7C0ABAE04ECDF915DF3`。spec初C0/I1（舊schema1 Extra）→fix1 C0/I0/M0；quality初C0/I1（未支援 `MIGRATED_SCHEMA1`）→fix2 C0/I0/M0。Task10只保存真 `DISABLED` 的checked allocation2/catalog2及首tick前四phase，不物化世界、不讓玩家random NEW；Task11 backend/seed與Task12正式入口待。本四份docs truth已獨立窄審C0/I0/M0；待commit／正常push，等同SHA Ubuntu/Windows雙job success才結Task10、進Task11。原full `run-all-9f3ac0e3f01d4fe58d654ea9853fb293` Windows更新 INTERRUPTED、前pin `95f676dc` 外層晚檢FAIL、fix1首GREEN gzip片段FAIL及其它原FAIL不改判；H5仍後置，main合併/tag/PR/退役另守門檻。以下舊接續點與§0/§9派工文均為歷史。

**2026-11-05 最新接續點：Task9 結案，Task10 待派。** code pin `73266221f8334bd1dbb4ca5c0193e239e8395a93` 本機完整 `run-all-7c709cf9aa4f4f4eb2f036a8b1631fb1` 自然END ALL0／JUnit1073/0/0 skip2／M6 10/10／main-only PASS，四類雙 focused 共216案次；spec 初C0/I2→fix2 C0/I1→fix3 I1/I2關閉C0/I0/M0，quality C0/I0/M0。feature docs HEAD `870e2947fa02a8fd3196bcde62cd818a02ca0ca4` 已push/ls-remote同SHA；GitHub run `42035809807` 同SHA completed/success，Ubuntu `build`、Windows `windows-native`雙job與M6/main-only/精確集合/證據上傳關鍵步驟成功，三artifact清單讀回未解壓。Task9 只加schema5格式／policy1解析，**random NEW到Task12才可玩**。Task10 的 candidate writer 容量項改採Task9已驗前置的plan窄補已獨審C0/I0/M0，待本次純docs整合commit／push後依plan Task10白名單派工；不要提前稱Task10已開工。首full六敗、4096全NEW原FAIL、舊v3 slot root及所有retained FAIL不改判。H5仍待Task21後triage；main合併／tag／PR／工作樹退役另守門檻。以下較舊接續點與§0／§9派工文均為歷史。

**2026-11-05 最新接續點（以下較舊接續點及 §0／§9 起手文均為歷史）：** Task9 code pin `73266221f8334bd1dbb4ca5c0193e239e8395a93`，本機四類乾淨雙 focused 共216案次；`run-all-7c709cf9aa4f4f4eb2f036a8b1631fb1` 自然 END ALL0，JUnit1073/0/0 skip2、default179、M5 60、M6 10、main-only PASS、recovery24、collapse69，summary SHA256 `C1E7CF8E1DF4EE34264D336DFB0D83832EEDD870B56B7579A2310010EAAF6709`；pre/post 同 pin、porcelain空、owner0、M6 lock可獨占。獨立 spec 初 C0/I2、fix2 後 C0/I1、fix3 同席窄複審 I1/I2 ADDRESSED 最終C0/I0/M0；獨立 quality C0/I0/M0。**尚未 push 此 code 或讀回其遠端 CI**；四份 tracked docs truth 獨立窄審 C0/I0/M0；逐檔 commit／正常push，待同SHA Ubuntu/Windows 雙job success才結Task9並進Task10。正式玩家入口仍產 M4 Context，本片只加schema5格式與policy1解析，random NEW要到Task12才可玩。首full `run-all-d0b5d7fa10a64433892471e08d37d93d` 六敗及所有 retained FAIL 不改判；H5仍Task21後triage。主分支合併、tag、PR、工作樹退役另守門檻。

**2026-11-05 最新停點（以下舊接續點、§0 舊起手句、§9 舊派工文均為歷史）：** Task8-core 已結；裁示前 feature HEAD `743ffbf891b81b585e12dbbd3fc25d09656b089b` 是舊 Task9 slot 即時攔截設計 docs commit，Task9 程式仍 WIP，未提交 code、未達乾淨雙 focused／full／兩席／CI。使用者原話「我同意先推主線」指功能優先；最新裁示把三個原生 hook、相關 permit／負控／mutation 與 JDI intermediary gate 移到 Task8-H H5，Task21 後 triage。Task9 只保留唯讀 slot 四態與本模組下次 get/save 偵測停寫；承認其間 vanilla autosave 可能寫 foreign state 的已知風險。縮範圍五份文件獨立窄審初 C0/I1/M0，plan 通則措辭修正後同席重核 C0/I0/M0；純 docs commit/push 之後依新 HEAD 接續 Task9 WIP。舊 slot RED／GREEN root 原判保留，不能冒充新版證據。正常 push 已授權，main 合併／tag／PR／工作樹退役另守門檻。Task10–21 的其他 mod 專屬碰撞只作事後 fail closed；plan 外新增 production 注入點／驗證腳本／外部 JVM observer 先回報裁示。

**2026-11-05 最新接續點：Task 8-core 已結案。** 末code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667` 的 `run-all-f9344a453d914fc2b22bae056c0fed1e` 自然END ALL0／JUnit1014／M6 10/10／mainOnlyPASS，S1a Full strict10案十事件PASS；最終docs HEAD `0f02011b97e9b03b043afbfe33d14aa203d88708` 已push，GitHub run `41918108739` 同SHA Ubuntu／Windows雙job success，三artifact清單已讀未解壓。8C-3 handoff§4摘要I-1已修、窄複審 ADDRESSED C0/I0/M1；M1為8C-2已揭露異常foreign END／dispatch未永久poison。下一步Task9 v2設計已複審C0/I0/M0，tracked plan窄補已獨審C0/I0/M0（報告SHA256 832EB19BF104F7C2A31804A407654680F551F5487640341D78CAA191F09BEF50），隨本docs commit採用後派schema5／policy1；8-H H1–H4待Task21後triage，無waiver、原FAIL不改判。這筆是純狀態文件更新，後續舊接續點皆為歷史。

**2026-11-05 最新接續點**：8C-2 docs HEAD `e1eb9fcdda9c38496db803c0797a7477a7b363c1` 的 GitHub run `41911139872` Ubuntu／Windows雙job success；最終code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667` 的完整 `run-all-f9344a453d914fc2b22bae056c0fed1e` 自然END ALL0／JUnit1014／M6 10/10，S1a Full strict另PASS；其後只有四份docs。8C-3唯讀本機審核C0/I1/M1：handoff§4過時legacy摘要已修待複審，M1是8C-2已揭露foreign END／dispatch未永久poison。8C-3最終docs truth／commit／遠端CI尚待；通過才進Task9。H1–H4仍排Task21後triage，原FAIL不改判。以下8C-2接續點與§9派工文為歷史。

**2026-11-05 最新接續點**：8C-1 docs HEAD `443221e634142e9ee39bc1cb5b0123c6c08cf059` 的遠端 run `41863993829` Ubuntu／Windows雙job success；8C-2 thread／ticket與GameTest entrypoint兩次窄設計已審，docs base `f8f8d847b5509e02a84cde2c1685986d687c5780`。8C-2 code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667` 本機七檔、Lifecycle12×2／自然settings GameTest1×2 focused PASS、完整 `run-all-f9344a453d914fc2b22bae056c0fed1e` END ALL0／JUnit1014／M6 10/10，S1a Full strict10案十事件 PASS；spec C0/I0/M1、quality Approved C0/I0/M1。Minor為異常 foreign END／dispatch 未永久poison，符合tracked最小短路契約但弱於ignored addendum，須記受限偏離。此筆 8C-2 docs truth／push／遠端CI與8C-3終局尚待；Task9未開始。原8C-1接續點與§9舊派工文為歷史。

**2026-11-04 最新接續點**：8C-0 docs HEAD `551607cc69a64ecebd5ebc31e2aa0afd5739a33a` 的遠端 run `41833239533` Ubuntu／Windows 雙 job success；8C-1 本機 code pin `d38178b467e6412aa070091ad8228fd22366347d`，兩檔、九具名 RED、四個最終 mutation killer、乾淨雙 focused、`run-all-01dd2ce3b8b844178b291d4869ef00d5` 自然 `END ALL exit=0`／JUnit1002／M6 9/9；spec C0/I0/M1、quality Approved C0/I0/M1。Minor 是 after-lease 首輪存活診斷 raw 被覆寫，僅 summary 保留；修後殺手另有原件，不追認首輪。此筆 8C-1 docs truth／push／遠端 CI 待；雙平台成功後派 8C-2。原 8C-0 接續點與 §9 派工簡報均為歷史，Task9 尚未開始。

**2026-11-04 目前接續點**：8C-0 本機 code pin `d39fef1530bcf30102731e15aca1478657429c30`，三個 code commit、九檔；具名 RED／GREEN、四個原計畫 mutation 加 depth／detect-shape 兩個修正 mutation、乾淨雙 focused 與完整 `run-all-3e4a5c71830d4a5ea0e642176c7576b2` 自然 `END ALL exit=0`（JUnit993、M6 9/9）。spec I-1 修後 C0/I0/M0；quality Approved C0/I0/M1，Minor 為測試 fixture 清理雙重失敗可能遮蔽原錯誤。此筆撰寫時 docs truth／feature push／本 code 的遠端 CI 尚待；確認雙平台成功後接 8C-1。原 `run-all-8b67…` FAIL 與所有既有 FAIL 不改判。以下原 2026-11-02 停點與 §9 派工簡報保留為歷史，不再當未開工指示。

## 0. 貼給 Codex 的起手句

```
請以 Codex 身分接手 QuantumChamber M6。先讀 AGENTS.md，再讀 docs/handoffs/2026-11-02-m6-codex-resume-prompt.md 全文並依其第 1 節順序讀完其餘文件。使用者 2026-11-02 已裁示縮 Task 8 範圍：Task 8-core 已完成，接續 Task 9–21 全部功能，之後才做 Task 8-H 中經 triage 判為必須的切片。最終 8C code pin 16b833cc43278fe8fb56e8e75f2aed4beee49667、本機full與最終docs HEAD0f02011b97e9b03b043afbfe33d14aa203d88708 的 GitHub run41918108739 Ubuntu／Windows雙job success 均已有原件。到場核 Git、ignored STATUS/progress、原FAIL與Task9前置設計後，Task9 v2設計已複審C0/I0/M0，先核本docs commit已採獨審C0/I0/M0的Task9 plan窄補，再依plan派Task9 schema5／policy1；不重做8C-0～8C-3，不在main實作。全程繁體中文（台灣）精簡回報，先證據後結論，使用Subagent-Driven Development。
```

## 1. 工作區與先讀

- Worktree：`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation`；分支 `codex/m6-universe-generation`。不在主 checkout 或 `main` 實作。
- 依序讀：本工作區 `AGENTS.md`；本檔全文；`docs/implementation-notes/2026-10-24-m6-universe-generation.md` 末四節（含 2026-11-02 裁示條目）；spec `docs/superpowers/specs/2026-10-24-m6-universe-generation-design.md` §3.1.1、§3.1.2、§8；plan `docs/superpowers/plans/2026-10-24-m6-universe-generation.md` Task 8 整段與 Task 9–22；10-31 接手檔「停點事實」與「尚未完成與原 FAIL」兩節；ignored `.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md` 首段與 `progress.md` 末筆。ignored 原件不在 Git，換機先確認存在，不可補造。
- 首先重核 `git status --porcelain --untracked-files=all`、`git rev-parse HEAD`、`git ls-remote origin refs/heads/codex/m6-universe-generation`、`git worktree list`、workspace Java owner，以及 `run/gametest-m6/world/session.lock` 可獨占。

## 2. 使用者 2026-11-02 裁示（binding）

使用者原話：「我想先裁示縮 Task 8 範圍，至少本模組功能都完成後才修必須的任務切片」。同日稍早的問句是「看一下m6分支到底是卡在哪裡還不進入Task8 仍有門檻，Task9–22 尚未開始」。

controller 解讀（非使用者原話；Codex 在 spec／plan 修訂時若認為解讀有誤，先向使用者確認再動）：

1. Task 8 拆成 **Task 8-core** 與 **Task 8-H**。8-core 只做讓 Task 9–14 能用的最小 snapshot／provider／context 接線；8-H 收納目前所有未完成的完整資料閉包、lifecycle 證明與跨 mapping bundle（第 5 節）。
2. 「本模組功能」指 plan Task 9–21 的全部遊戲功能：random seed、same-seed fresh、profile mutation、完整 Nether／End family、portal／gateway、respawn、同 role 艙。這些各自通過 plan 原門檻之後，才回頭做 8-H。
3. 「必須的任務切片」指 8-H 中經 triage 判為 M6 合併 `main` 前必須完成的項目；其餘以明確 gate waiver 記錄並移到 M7 或之後。哪些為必須，由 triage review 提案、使用者裁示，不由 controller 自行認定。
4. 本裁示不改變：Subagent-Driven Development 與每片獨立 review、retained FAIL 不改判、push 持續授權、不建 PR、不在 `main` 實作、不 force push，以及 `main` 合併、tag、worktree 退役各自門檻。

## 3. 新工作順序（取代 10-31 檔「下次工作順序」）

1. 核 Git／CI／ignored 原件（第 1 節）。【已完成，見第 6 節 2026-11-02 稍後事實】
2. 【已完成：spec v1.4 與 plan 8C／8-H 經兩席獨審加三輪窄複審採用，最終 C0/I0/M0；commit 鏈見第 6 節】**只改文件，不動程式**：spec 升 v1.4（§3.1.1 增「SHALLOW_V1 最小 closure 與已知限制」小節、§3.1.2 標明 8-core 的最小 lifecycle、§8 門檻對應調整）；plan Task 8 重構為 8-core 與 8-H 兩段，8B2／8B3 既有已結案切片紀錄原文保留為歷史、不刪。送獨立 spec review 與獨立 plan review；Critical／Important 清零才進第 3 步。第 4 節是 controller 建議的界線，不是已審設計。
3. **目前下一步**：Task 8-core 的8C-0～8C-3已通過本機full、獨立審查、docs truth、正常push及最終雙平台CI；先核現場Git/ignored原件，再核本docs commit已採Task9 v2設計C0/I0/M0的tracked plan窄補，才依plan實作schema5／policy1。第9節8C-0派工簡報僅供歷史核對；8-H待Task21後triage，不能提前waiver。
4. Task 9 → 10 → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19 → 20 → 21，依 plan 原文與各自門檻；不因 Task 8 縮範圍而放寬任何 Task 9–21 的 RED／雙 focused／full／兩席 review／CI。
5. Task 8-H triage：列出第 5 節全部項目的當時狀態，提案哪些為「必須」、哪些 waiver，送獨立 review 後交使用者裁示；只實作裁示為必須者。
6. Task 22 收尾（含人工 smoke、whole-branch review、docs truth、CI），之後 `main` 合併、tag、退役仍另守門檻。

## 4. Task 8-core 範圍界線（已由 spec v1.4 §3.1.2／§3.1.3 與 plan 8C 定案；本節是摘要，細節以 spec／plan 為準）

**保留不動**：所有已 commit 的 Task 8 owner 與測試繼續編譯並在 run-all 內通過（10-31 基線 JUnit 971、M6 GameTest 9/9）。不刪、不改名、不為縮範圍而放寬任何既有 fail-closed 斷言。Template access gate 的 mixin、Contexts 的 stop fence／owner Cell／initial ticket、S1a 的 `DataPackContentsRefreshSealMixin` 診斷都保留；它們不進 8-core 的成功路徑。

**最小 closure（spec §3.1.3 `SHALLOW_V1`，已採用）**：

- 內建 provider（`quantumchamber:vanilla_normal_v1`／`vanilla_amplified_v1`，ProviderVersion 1；`m6_profiles_v1` 是 PolicyId，不是 ProviderId）的 closure 定義為 `SHALLOW_V1`：三 role 的 `DimensionOptions` 以 lease 的 registry ops 做原生 codec encode，registry 引用保留為 id、不做 transitive 展開、不讀 template／tag／loot／component；加上 Parameters 的 `OverworldPreset` 與 `GenerateStructures`。canonical 編碼為固定 key 排序的 NBT；SHALLOW_V1 envelope 上限 8192 bytes，snapshot 外層五鍵上限 512 bytes，FP 為 32 bytes。
- 完整資料閉包是未來另一個 ProviderVersion（例如 version 2），不是改 version 1 的語意；snapshot 外層五欄 schema 不變。
- 明列已知限制：`SHALLOW_V1` 的 FP 偵測不到 datapack 改寫 `minecraft:amplified` 等 noise_settings 內容、tag 內容或 template 內容。這是本裁示接受的風險，寫進 spec 已知限制與 Task 22 人工清單，不隱藏。
- `createSnapshot`／`resolve` 對 version 1 走真路徑；對未知 provider、version 或 closure 仍 fail closed。
- 不取得 template gate exclusive，不呼叫 `Minecraft121TemplateSettingsAdapter`／`Minecraft121RegistryClosure` 的 live 路徑；因此 100ms live hold I-1 不在 8-core 路徑上，歸 8-H。

**最小 lifecycle**：

- mod init：註冊內建 provider → freeze metadata → 由 `Minecraft121SettingsLifecycle` 接固定四個 Fabric 事件，早於 journal／M5 authority 第一次需要 resolve。`SERVER_STARTING` 在 exact server 建 context；reload START 同步撤舊 lease、owner 依原 ticket 接續；END 先驗唯一原 claim 再取 Sources 並完成；STOP 同步 detach。這是最小 `SHALLOW_V1` 接線，細節以 spec §3.1.2／plan 8C-2 已採 thread／ticket 窄修為準。發布時點仍由自然 GameTest 的 registries identity 證明，不靠 listener 註冊順序猜；真 reload 完整同代仍歸 8-H。
- 一個 M6 GameTest scenario：自然 startup 後 `acquire` 成功、registries identity、`createSnapshot`／`resolve`；**不做真 reload**，因為 retained 的 S1a observer 會把同 server 任何 reload 記為 `UNEXPECTED_RELOAD`。reload／STOPPING 接線由 lifecycle JUnit（可注入 registrar seam 核四個 Fabric 事件的註冊、假 Sources factory 核委派）加 mutation 守；真 server reload、多 inflight、controlled future 失敗、off-thread 交錯、refresh seal、source bridge 都歸 8-H。
- 不新增三 bundle oracle；CI 仍是現有 Ubuntu `build`／Windows `windows-native` 雙 job。

**8-core 完成門檻**：同 plan 每批協定：候選 compile → 具名 RED → GREEN → mutation-only＋REVERT → 乾淨同 pin 雙 focused → 完整 run-all 自然 `END ALL exit=0` → fresh spec／quality 各 C0/I0 → docs truth → 正常 push → 雙平台 CI success。全部通過才進 Task 9。

## 5. Task 8-H 延後清單（triage 候選；現在不做）

- **H1 完整資料閉包 provider v2**：C1d 其餘（D1c LOOT_TABLE；D1c-0 設計已審 C0/I0/M2、D1c-1 未放行）、三 role 完整 canonical／FP、100ms live hold I-1 的量測與獨審、T2 額外 100ms FAIL。
- **H2 正式 lifecycle 證明**：S1b 異常 startup／舊 seal 撤銷／重入／overlap（S1b-0 v2 草案 SHA256 `6ba74d6fc88bf1cfd8d653fe706bfefde109d30eadbb440109999ce0e83841ff` 未獨審）、S2 integrated RM 同源（提案 SHA256 `1eb5e823675a11fdaea0dff0562b95ebc36393f8516693c0596c8f0ea2a76ca5` 未獨審、結論 STOP）、正式 source bridge／同票同 seal publication／consumer 最終接受、LT2 STOP-PENDING `6bc60fc91584008b983aa668ebad1f00` 真 late 的安全性。
- **H3 8B3-F 三 bundle**：Windows named／Ubuntu named／Windows intermediary 的 canonical fixture 逐 byte 比對、`m6-settings-receipt-check`／`mapping-probe`／`compare` 三腳本、`ci-rule-check` 負例、workflow 改動、production runtime 具名準備核。
- **H4 完整 reload GameTests**：多 inflight 先序、controlled future 失敗後恢復、owned datapack tag／loot marker 同 epoch 比對。
- triage 預設建議（非裁示）：若 M6 正式只出 `SHALLOW_V1`，H1、H4 可 waiver 到 M7；H3 至少要證 Windows intermediary main-only 與 named 的 `SHALLOW_V1` FP 相同；H2 只在 refresh seal 真的進入正式路徑時才必須。最終以使用者裁示為準。
- 所有 retained FAIL（v4 父、B27、3b AccessDenied、T2 額外 100ms、LT2 STOP-PENDING、S1a 的 setup／RED／突變 FAIL）原判不變，不因延後而改寫。

## 6. 2026-11-02 核對事實（Claude 只讀盤點）

- local／origin feature 同為 `5b2bafa67790916e6d9dfac9a71c1d3e60a47442`，純 docs commit；領先 `origin/main` 50 個 commit。GitHub Build run [41149366263](https://github.com/Ben513242/QuantumChamber/actions/runs/41149366263) 同 SHA completed/success，Ubuntu `build`／Windows `windows-native` 雙 job success；CI 沒有執行 `S1A_ORACLE`，也沒有三 bundle。
- 程式碼真相：`UniverseProfileRegistry.createSnapshot`／`resolve` 仍無條件 throw `IllegalStateException("M6 context/canonicalizer 尚未就緒")`；`src/main` 沒有 `Minecraft121SettingsCanonicalizer.java`（同名 test 測的是 Graph／Closure／Contexts／LeafContracts）；`QuantumSuperpositionMod` 沒有任何 Settings／Profile／Contexts 接線；plan 列的 8B3 五個 testmod lifecycle owner 與 `m6-settings-receipt-check.ps1`、`m6-settings-mapping-probe.ps1`、`m6-settings-compare.ps1` 不存在，只有 `m6-refresh-seal-check.ps1`；Task 9–22 的 owner 檔全部不存在。
- 存在的 Task 8 main owner：`GenerationSettingsSnapshot`、`UniverseProfileProvider`、`UniverseProfileRegistry`、`ServerSettingsContext`、`Minecraft121SettingsContexts`、`Minecraft121SettingsGraph`、`Minecraft121RegistryClosure`、`Minecraft121RegistryLeafContracts`、`Minecraft121ItemComponentContracts`、`Minecraft121NativeValueContracts`、`Minecraft121TemplateValueContracts`、`Minecraft121TemplateSettingsAdapter`、`Minecraft121TemplateAccessGate`、`GeneratorProfile`；testmod 有 `M6RefreshSeal*`、`M6SettingsTrace*`、`M6RaceGameTests`、`M6GateCanaryGameTests`、`M6UniverseRecoveryProbe`。
- 此盤點在沒有 ignored 原件的 clone 上完成，未核任何 SHA256 原件，未跑 Gradle／run-all。10-31 檔的本機 full／oracle 數字沿用原紀錄，沒有新驗證。
- 進度判讀：Task 1–7 於 10-25 兩天完成；Task 8 自 10-26 到 10-31 六天，依 owner 檔數估 8B2 約六成、8B3 不到兩成；每片結案都新增 UNKNOWN 或 STOP，整合端未動。這是本裁示的背景，不是新的門檻。

### 6b. 2026-11-02 稍後事實（spec／plan 採用輪）

- commit 鏈：`d8c5897` 裁示與接手檔 → `20338be` v1.4 草案（待獨審）→ `4ca1303` 首輪 fix（spec C0/I4/M11、plan C0/I5/M11 全處理）→ `4a50629` 第二輪 fix（窄複審一 C0/I1/M3，I-N1 registrar seam）→ `2b1764e` 第三輪 fix（窄複審二 C0/I1/M2，I-N2 Sources factory）→ 本檔所在的採用 commit（窄複審三 C0/I0/M0）。review 報告 SHA256：spec `c1ab9ec35a6e6c9dfba14f7f9532a2dfcca655019c974446c4ecf41df0963be8`、plan `5aa3302026c10312b9d1c415b471d44db7bd18b5a5789e2d823f1ba719120e12`、窄複審一 `ff9ae0b788bc9ba4b2704c25166db6ba87f24e93149c815eac0a1007388868f1`、二 `e52e6a80755a7faceb6d193242010f951be0d0d792f02194702376e2f2a0c920`、三 `943626daecfb517da91a74828d02412c4866c2c81e2f702e8d75c30ccb04ebb2`；原件只在雲端 session scratchpad，要旨記於 M6 note。
- 採用版 spec SHA256 `e9dbaac9736a83a38dde96605c2ffd411a7b716b35e241eaa209d4943748dad2`、plan SHA256 `e087f43c1717fa63ea6857220544443ca8d528930d71cf107acb5424001646a5`。
- 遠端 CI：`d8c5897` run 41409147739、`20338be` run 41429481418、`4ca1303` run 41432221278 皆 Ubuntu `build`／Windows `windows-native` 雙 job success；`4a50629` run 41433659755、`2b1764e` run 41434595336 於撰寫時 in_progress；採用 commit 的 run 待讀回。全部純 docs，CI 只證既有 gate 未壞。
- 本輪 controller 是 Claude Code 雲端 session，子代理為 Claude（只讀 reviewer）。雲端容器網路政策擋 `maven.fabricmc.net`、`libraries.minecraft.net`、`piston-meta.mojang.com`，且無 pwsh，故**未寫任何 8C 程式、未跑任何 Gradle／focused／run-all**；8C 實作必須在 Windows 工作樹（或同等可 build、可跑 PowerShell gate 的環境）進行。
- 使用者 2026-11-02 另問是否提供 ignored 的 SDD ledger／STATUS；controller 回覆只需 `progress.md` Rulings 段與 `STATUS.md` 首段作唯讀參考，本輪未取得，因此 v1.4 未與 ignored ledger 交叉核對；Codex 到場第一步須補核此點，若 ledger 有與 v1.4 衝突的舊裁示，先向使用者確認再動。

## 7. 授權與邊界（沿用）

- 子代理只用 `gpt-5.6-sol`、`gpt-6-sol` 或 `gpt-6-astra`。一個 implementer 對應一片，之後獨立 spec／quality；C/I 清零才往下一片。
- 正常 push 已持續授權。force push、PR、`main` fast-forward、tag、人工代驗與 worktree 退役不在本授權。不得 `git add -A`、`reset --hard`、`checkout --`、`git clean`、force-add ignored 證據或修改主 checkout。
- retained FAIL 不改判；不重跑相同 focused 改判；不以改名或合併 task 省略門檻。
- 任何文件不得提前寫「已合併 main」「已 push」「人工驗收已通過」。

## 8. 交回紀錄

- 2026-11-10：R三docs修正e366571/CI42756204211三job真success，之後已開始T14。未push候選cdb589e的118/8/13與9鏈27phase真PASS，但479-SelfTest首輪598.8306253s BUDGET_FAIL；一席已審純PS窄修後唯一回放300.0827308s立FAIL／安全停止owned唯讀child，終局300.3782108s／完成數UNKNOWN。停第三次/其他gate，Task14OPEN，PS1與交回docs保WIP不push；原FAIL/426原件/9locks/Java0保，R/8H候選不碼。

- 2026-11-10 B+C 唯一窄審 C0／I0／M0 已採用；本次五 docs 是 T13 候選結案，待本 commit CI、仍 OPEN。未改 source／腳本，不新 local full／mutation；等本 commit 三 job 真綠才 ignored STATUS／ledger 記 closed，停 T14 前。原 full／CI FAIL 及重跑次數不洗。

- 2026-11-10 B+C 新裁示：M6 僅 Overworld，T13→T14→8-H triage→縮範圍 T22；T15–T21 移 M7、capability 0。先一次 spec／plan 窄審，通過才繼續 T13；測試／腳本／文件一席、免 mutation／本機 full，CI 與安全門檻照守。8-H waiver、人工代操作、main／tag 仍須使用者各自裁定。已知偶發只保根重跑一次，舊次數與 FAIL 不洗。

- 2026-11-10本輪最新（一次重跑已耗用）：四docs f30853a已commitpush、程式對0abd全同；一次新full50cee原default timeout/stdinStop未完/原restore拒liveowner，outer1/noENDALL/後續未啟。defaultXML缺席只報live兩具名AABB錯，不造fail數；CI42732476087 Windows default/缺XMLguard亦FAIL，rawsouth/north容量錯分列。已止損不重跑；唯一owned orphan精確雙核後只殺本MC，Gradle鏈自然退，failedworld663檔22目錄lossless保gate/world-fresh，原manifest/backup缺席與非自然restore界限分列。source不改、oldFAIL保，T13OPEN/T14未准。這四份新交回文件保WIP，不再commitpush觸發另一CI。

- 2026-11-10最新（使用者AccessDenied裁示）：80f9指定AccessDenied atomic move簽章視為2026-10-21已知限制，從先前「其他失敗停查」排除；原FAIL保留不改，無production或OS變更。准先四docs提交push並證code等同0abd50e，再於該cleanHEAD新nonce完整full一次，同SHA3CI必過；這次任何FAIL停報不重跑。結案docs先待本commitCI，CI過才閉。T14–T21同codepin若full唯一為此簽章，可自動freshnonce重跑一次，第二次停報；混有其他FAIL不適用。T14與新控制仍等另行授權。

- 2026-11-09最新（使用者M2新增授權）：使用者已明確回覆「授權此 M2 testmod 窄修」，範圍只限M2CorridorGameTests.java這批TrustedSpace等待／正式失敗cleanup，先一份設計與獨立窄審C/I0後實作，保原ready／功能斷言，以原tickLimit內條件等待，不production／外部observer。15案是否同因未證。controller派fresh設計者先寫唯一ignored設計，未准碼；PS1 fix3已兩席0，將先checkpoint提交，待M2受審候選完成後在最終固定pin跑原C4／同SHA CI，不以舊d0或checkpoint結果代最終驗收。

- 2026-11-09稍後（Codex，T13重開初報快照；最新見本檔首段）：43cb99b純四docs push自動CI42608921665出現Windows default步驟FAIL與native SavedSummary-SelfTest300秒timeout；runtime21phase PASS不能代後者過關。已立即回報使用者並保存完成job原件，Windows具名case仍待job終局／原XML。無重送、無timeout／oracle／source變更；同一implementer只先量測PS1成本與提出保全部criteria／277的窄修候選，尚不實作。下方結案是d0當時真full／CI與兩席已通過的歷史，原件不翻案；T13最新狀態OPEN、不進T14。

- 2026-11-09（Codex，T13正式結案）：依當日分層裁示，A normal／amplified各10個canonical-only CARVERS控制分別單一957bf…／742a4…，只有模組載入／無動態U的控制範圍，不稱純vanilla。程式pin `d0aafb47d574f5fff24f712246bbf3508ec2a635` 的C3完整雙focused、12個正常native phase、完整3ea END ALL0／outer0、原兩次各277及同SHA CI42595046917三job success均成立；formal spec／quality各C0／I0／M0。C3 index SHA `2b8a2cbc037a5a883251d1bed7c9a0cbbc0d9049a62e85474837b35d77cfd82e`，8804檔manifest與immutable source/class pin保在本plan SDD；完整索引／hash／counts見M6 note末節。normal／amplified final差格逐格已獨審，UNRESOLVED原值與dd57／35f5／4593CI／f8 full等全部舊FAIL保留；沒有把已見hash當關卡或歸因FEATURES時序。Task13完成，Task14未開工；T21另裁、deferred ledger事項到對應task前處理，新增canonical＋U／U-only／client、main／tag／PR未執行。本次只交付與文件收尾，後續程式工作等使用者新裁示。

Codex 每次交回在此追加：日期、code pin、range、RED／GREEN、雙 focused、full nonce、兩席 review、CI run、下一步。

- 2026-11-09（Codex，Task13 乾淨驗證與未結收尾）：code range `9b04f6d08d9234c4c8ad6a53143319bf49cb7ef1..1332c527b829b4a228471de541aff493cdcc7e49` 的 E1／E2／四階段見證已 commit/push，遠端同 pin、porcelain 空。原具名 RED／GREEN、11 項 KILLED mutation＋逐位元 REVERT、三 SETUP、首 Error `NOT_KILLED_OR_UNRESOLVED`、stop-throw `NO_STOP_PROOF` 依 ignored `task13-milk-handoff-delivery-bed56fe7212248eb82be69de474f1aac/index.json` 原判；clean milk/no-entrance、same-seed GameTest 雙 focused 各 2/2，M6Compile、SelfTest70 PASS，正式 spec／quality各 C0/I0/M1。CI `42499957714` Ubuntu／Windows／Windows native 三 job success、artifact raw 讀回；Windows native summary SHA-256 `8511AFEC15DABE68863D0A37906644C904449ED20268004987552D1865A96D10` 七鏈 21 phase PASS。本機 full 根 `run-all-dd57e94d52274671b4cfc506b390a9ab` M6 native 前 11 個 END gate exit 0，M6 native normal same-seed `new` 地形 air／spruce leaves FAIL，summary SHA-256 `26804DFD00281087765A0F3D0EE1ABEDC5FD328E571C1B2E94C34230AE53B6B8`、比較 SHA-256 `22C9D5EDD7AB4997D5ECEF044AC53E290C02F68CCE10A2176316D467D9A12B37`，outer1／無 END ALL；原根保 FAIL。舊 `35f5` amplified FAIL 與本根都在東界 x527，但具體 feature writer／內部排程、canonical source owner 尚無原件；只屬假說。下一步限 T13 有界根因與獨審修法，不能改順序湊綠或進 T14；M6 人工／main／tag 均未放行。

- 2026-11-08（Codex，Task13 範圍採用與 owner 候選）：使用者同意 U→U full 移 Task21，Task13 保 canonical normal／amplified 真玩家原生鏈及雙平台 owner／設定。spec／plan `ec605477890e044e112fe4b2b2a43d099be96e13` 初審 C0/I1、修後 C0/I0/M0，已 push／遠端同SHA；CI `42458754297` 查即時狀態。原 T13 18 WIP 未 commit；新 owner testmod 拆分 compile `84a6af0d...` PASS，負控 `a94b746...` 的預期 frozen seed XOR1 由 `SAME_NATIVE_CONSUMER_EXACT` 具名殺、mutation patchSHA `53B8FF87D1C635B9FFE0B72619C9BD80F29DB66445BD196153F937E0A3F396D5`、byteEqual REVERT；正常 focused `d2a052f4...` owner／直接 selector 控制 2/2 PASS（當時 XML 名為 formal_entry），normal／amplified 四列 native consumer PASS、世界 exact restore。舊 c7d／e1d U→U FAIL 不追認，full helper／1440樣本／結構／建築／修改逐位元保留且未在 T13 發現，Task21 重新啟用；不開 §6 動態 U-origin。下一步：Task13 code commit＋立即 push，乾淨雙 focused／full／兩席審查／同版 CI；Task14 未放行。

- 2026-11-08（Codex，Task13 U→U 範圍裁示待回／WIP 停點）：本停點提交前HEAD `5e9dc8fb7a2274858dc874ad517114bdf7cd014b`、remote `f77f9d608206bd61c938cf08b320f8eb17fb83ec`；Task13 plan純docs commit `1afd4d1`（原生summary/README既有檔）、`c2e5b10`（舊random原生橋）、`2e96891`（normal/amp各canonical三JVM）、`3fadba0`（U→U GameTest virgin/consumer）、`5e9dc8f`（海草四階段只讀見證）都已獨審採用、尚未push。18檔程式／workflow／測試 WIP未stage/commit/push，ignored保全manifestSHA `1340A33D9BB60EF8FD3FF3C15C2FF0CBE9984B0C0F57A67F0883C73B58F07F8A`，tracked patchSHA `02402496A046918FAC61BAF766F68F7C1B0A9227EFAB37444887E919978CDBD8`、新helperSHA `2EC64FDDBCDE896C77F5D63126149E6AE114BD4BE957AB9E389A105C81E9132B`。候選JUnit1107/0/0 skip2、same GameTest早期2/2、四組policy/建築/修改mutation真殺；原生AllEnabled `c9eb67b128a4421a87954f6b25e1ba4f` 七鏈21phase PASS／SelfTest255，均非clean code pin。c7d原GameTest seagrass/water XMLSHA `4BB410179ED5ED86532A028B3979BA498657455ACCAF01ECE92C493FAACEF87F` FAIL，雙U virgin/actual consumer已PASS且raw MCA差；e1d四階段 XMLSHA `153CC9949384192E3F97578F87F547854962E916AB8727BF0E93178F5E4C1001`、logSHA `3CB948BCB78533A0DB18C7C699939691030005BD2D0BE83ECE13BA81A9D147B5` 仍FAIL，差在BOTH_CENTER首次可比時已見且後兩階段不變，原1440方塊/biome、結構、建築、edit斷言不放寬。固定JAR FEATURES RNG已讀未見UUID直接混seed；海草兩路與後更新仍UNOBSERVABLE，不定production或vanilla根因。使用者三選範圍問題待回，依回覆才可留T13做testmod因果見證或修binding/plan移Task21/縮地形；沒有回覆前停依賴改動。舊所有FAIL保判，README七鏈只是dirty候選；Task13、Task14及M6整體均未結，下一步先核使用者裁示再做可審實作，不重跑舊根湊PASS。

- 2026-11-08（Codex，Task12 測試判定自動切片結案）：range `7bbdcdfeeb462129354c5db7e1ca8aa059a14f39..76111e8e0486baeb9c7cf3835e0ab397eab576e0`，其中純docs計畫 `12a158d`、test-only code `76111e8`；code已正常push、remote讀回同SHA、只四白名單測試端檔。唯一合併設計 C0/I0/M1，程式spec／quality各C0/I0/M1；Minor是16格基岩只證有限storage。具名RED沿舊PREPARE／wire／partial原FAIL，750／780、流體前提、wire B、真WARN capture、partial樣本／seed／settings等 mutation-only真殺／REVERT；首SETUP、b20e／75c、首B採集漏XML、首錯logger NOT_KILLED、歷史wire A1/A2 `UNRESOLVED`均原判保留。乾淨M5三案雙focused各3/3、partial雙輪各四phase；`run-all-7c079c8613c54067a5b5094bc62823fa` 自然 END ALL0／outer0，summarySHA `7A345D621A422BA639EA8E4DB27868FB558BC893288EC7D43762EC7B9C0860D9`、consoleSHA `8899052515C3EACD3AC61A2087E2275D843E55AA274DBC7A990E601C20F9F3CC`，M5 60/M6 17/JUnit1100/collapse69/native15、五world exactRestore、owner0/3116 locks可獨占。同SHA CI `42436349061` Ubuntu build、Windows windows-native、Windows m6-native三job及M5/M6/原生驗證/證據上傳步驟全success，四artifact名稱讀回未下載解壓。依最新使用者裁示 wire A四tick不可觸發不追認原root；partial撤舊48格，16格bedrock只作有限storage，frozen seed/noise/biome另守生成。Task12僅自動切片結案；tracked四docs truth待獨立窄審／commit／push，之後直接Task13，人工smoke／main／tag不放行。

- 2026-11-08（Codex，最新 Task12 測試判定裁示）：使用者指定 PREPARE、wire、partial 只改測試的三項修正，Windows無具名30分鐘逾時保 UNKNOWN 並自本裁示後再現一次才查；舊 FAIL 不改判。Task13 plan 的排序／白名單／有限 CI 清單與平台矩陣先經同一次獨立窄審採用，再做 Task12 的一份合併設計、一次獨審 C/I0、實作收尾，最後才做 Task13 程式。已派唯一 Task12 測試判定設計稿，未跑新診斷／未改 production。較早「先實作 T13、T12 未結」排序已被本次明確覆蓋；目前 code 仍 `5c7edc9`、基礎 docs HEAD `048e835`，四份最新裁示文件 WIP 待同席窄審／commit／push。K1–K3 與所有舊 FAIL 是歷史原件，不是新順序的豁免。

- 2026-11-08（Codex，Task13 CI 判定補充）：使用者三點原意及平台矩陣澄清已寫入 tracked Task13 plan 同輪窄審稿：K1 Ubuntu M5 PREPARE、K2 Windows M5 PREPARE、K3 Windows `m6-native` partial/restart 是有限已知清單；Windows 30分鐘 default timeout `UNKNOWN` 不可豁免。T13 新增 JUnit／GameTest 兩平台具名 PASS，原生鏈只在 Windows，Ubuntu僅拒絕契約；任何清單外 FAIL 停下查因。T12 停新診斷，T13 後交根因與修法選項（含預熱 I1/I2 及2／4格前提）供裁示。本筆尚為 docs 補充待同席獨審／commit，沒有 T13 code 或 gate 結論。

- 2026-11-08（Codex，Task13 排序裁示與派工前置）：使用者原話「先實作 T13，T12 保持未結（建議）」，僅放行 feature 分支先做 policy2／same-seed，Task12 所有 FAIL／wire A `UNRESOLVED` 保留，main/tag/人工門檻不變。到場 clean docs HEAD `048e8359c9cd61a611f9e43b298ab4b757a54533` 已在 origin；docs-only CI `42419184480` 整體 FAIL：Ubuntu build M5 PREPARE 在400tick預載2格中1格非FULL，Windows先 default 30分鐘逾時、後 M5 同案4格中2格非FULL，`m6-native` success，兩份 ignored job logs 與 SHA 見 M6 note 最新節。Task13 plan 原 Files 漏 `CandidatePolicySnapshot`、`UniverseAllocationLedger`、`UniverseRecord` 三處 strict policy1/random guard 及 `CandidateJournalCodecTest`、`UniverseAllocationLedgerTest`、`UniverseDefinitionTest` 三個讀回測試；已起草 tracked plan／AGENTS／note／本接手檔的裁示與白名單補充，待獨立窄審 C/I0 後純docs commit，然後派一位 Task13 implementer。此筆未修改程式／跑 T13 gate，Task13 尚未實作或結案；T12 原根不重跑改判。

- 2026-11-08（Codex，Task12 M5 wire 未結案增量）：range `c8cc314f3f9cbad94f10e0915680632ac5b8b87c..5c7edc99097e8b2e94ad69b76d2c661b1764b78f` 含純 plan `afef8fe` 與唯一 testmod code `5c7edc9`，已正常 push／遠端同 SHA。原 Ubuntu wire FAIL `42358264048`／`42401517613` 保判；候選 GREEN 兩根各1/1，B 新具名 mutation `gate-t12-wire-mutb-e124a33e-b1e5928f8d474487bbb1f0a086facf71` 有三行 mutation-only patch SHA `CCD0E3B105EFCAE73500E13484703D6B4CF58610E0FF1CFC45C8617B17A53431`、前後 SHA／逐位元 REVERT、原 wire 斷言具名 FAIL，獨立 spec／quality 程式各 C0/I0、B 補證窄複審 C0/I0/M0；A1/A2 不殺原四 tick 線路，`UNRESOLVED`。乾淨 code pin M5 單案兩根各1/1 PASS／exact restore；首完整根 `run-all-9dddc0a0642a43fca91af08082b8ae87` 在 M4 recovery 被外部 Yggdrasil 額外 ERROR 擋成 FAIL，原判保留；新 nonce `run-all-402db178e3d64e6194630859f1acce24` 自然 `END ALL exit=0`／summarySHA `BB522C7470583A28FAC7F3A6A8385BF4D115026A0DB209569DD0D0ED7E04051E`，default166／normal13／legacy1／M5 60／M6 17／JUnit1100／collapse69／native15／main-only PASS、五世界 exact restore、owner0／3052 locks可獨占。CI `42411190339` 同 code pin Windows `windows-native`／`m6-native` success、Ubuntu build M5 `prepare_unconfirmed_first` 四票level33但三格非FULL，workflow FAIL；新 wire 案在此CI PASS。Ubuntu logSHA `DD5A13860FD7E4007CB07B81296A1B15D091DAA62BE80EEF2BF36D7A6033460F`。四格同步預熱候選 ignored 設計 SHA `B42E6BADFB411B9D63877E4496563E2261C782A7A3B720FC1B094877D9102864` 獨審 C0/I2/M1，未採用；partial-overworld 多份功能 FAIL 仍保判，本機一次 PASS 不抵銷。下一步關 Task12 的 PREPARE／partial，或等使用者明確裁示排序例外才先做 Task13；絕不稱 Task12／M6 結案、不動 main／tag／PR／工作樹退役。

- 2026-11-06（Codex，Task11遠端結案）：最終code pin `232359a9eccf93b7d939c0f05ebe0f400d66146e`，雙focused JUnit7/GameTest1/native construct-reopen×2、`run-all-a08ab0aec02c49239ff32334cd0b9d74` END ALL0/outer0/JUnit1093/M6 11/mainOnlyPASS；world-map I1原席ADDRESSED C0/I0，整段spec/quality各C0/I0/M1（Backend:38 Javadoc交Task12 Step0）。docs HEAD `3452e311058647aa8998436d72cac26612156a2c` push/ls-remote同SHA，GitHub run `42195022397` Ubuntu/Windows雙job success、關鍵步驟與三artifact清單讀回。原SETUP/seed/locator雙向RED、flat GameTest FAIL、NoSuch root及全部retained FAIL原判不變；mod-only setter殘餘留8-H H6。Task12預查單片可行，本純docs結案commit/push後派工，不把Task11的native世界當正式玩家random NEW已開。

- 2026-11-06（Codex，Task11本機終局、遠端待）：range `3ba2e1ac4f3ae3858a9e4f5ae43e295178da1e31..232359a9eccf93b7d939c0f05ebe0f400d66146e`，本體 `9ebe351` 恰13白名單檔，world-map並行fix `232359a` 恰6白名單檔。owner JUnit具名RED5/3→GREEN5/0，真native錯seedRED、locator global/frozen兩方向RED與原版探索圖witness；九個本體mutation、fix三個map鎖mutation各具名殺/REVERT，SETUP/flat/NoSuch原FAIL保留。最終乾淨雙JUnit7／GameTest1／native construct-reopen，完整 `run-all-a08ab0aec02c49239ff32334cd0b9d74` END ALL0／outer0／JUnit1093／M6 11／mainOnlyPASS，summarySHA `8228208F1231EB909E96944019AEE26D4F20F8D2C33BD424E4A2869CA02B9361`、最終indexSHA `4EB3AEE67C7955D2148A6641CD84EE9E66C11BC14AF9FF3847B963BE20B0D1BB`。world-map I1原席ADDRESSED C0/I0，整段spec與quality各C0/I0/M1，過時Javadoc交Task12 Step0；public setter mod-only殘餘列8-H H6。未push/新codeCI未採；下一步本五docs review→commit→push→同SHA雙jobCI→Task12。

- 2026-11-06（Codex，Task11 locator範圍裁示）：使用者同意原計畫已有 `ServerWorldUniverseSeedMixin` 新增唯一 `locateStructure`→`GeneratorOptions.shouldGenerateStructures()Z` 包裝；fixed Yarn build.9 javap、原版眼睛／探索地圖等直接callers及命令不同入口由ignored `task11-locate-design/design.md` 與原件釘選。tracked spec/plan窄補已獨審C0/I0/M0，docs commit／push待；Task11 implementer僅先做計畫內API／JUnit RED，不含新增locator注入。後續依裁示採同owner frozen bool及雙向native witness，不擴通用mixins；其它新增production注入仍需再裁示。Task10 code pin `309f0d22` 的遠端CI run `42163888020` 雙job success已採，Task11未結。

- 2026-11-06（Codex，Task10遠端結案）：code pin `309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c`，乾淨三類雙focused126案次、catalog四phase功能鏈×2、`run-all-7f5d72f098a242efb685cea1f9135d4a` END ALL0/outer0/JUnit1086/M6 10/mainOnlyPASS；spec 舊schema1 Extra I1 fix1關閉、quality未支援MIGRATED profile I1 fix2關閉，各C0/I0/M0。docs HEAD `cceacd5140f1e1a6e8fedab2430cd67d772ad0f9` push/ls-remote同SHA，GitHub run `42163888020` Ubuntu/Windows雙jobsuccess，關鍵步驟與三artifact清單讀回。原9f3 Windows更新INTERRUPTED、95f外層晚檢FAIL、gzip首GREEN FAIL及各mutation不完整root保原判。Task11功能probe改用已列 `m6-universe-probe.ps1 -ExpectedMode FUNCTIONAL` 的plan窄補獨審C0/I0/M0；待純docs整合commit/push後派Task11，不提前宣稱seed/玩家入口可用。

- 2026-11-06（Codex，Task10本機終局、遠端待）：range `c6ed3e23315edd7a6796192a81475626df8132ca..309f0d224ea53dd8bf73f4ccfff3c3e45e27c62c`，本體20白名單檔 `b7bff070`、schema1合法Extra fix1 `b01abc54`、未支援migration profile fail-closed fix2 `309f0d22`。具名RED→GREEN、mutation-only＋REVERT、原FUNCTIONAL四phase首tick前halt77、最終三類雙focused126案次＋功能四phase×2；`run-all-7f5d72f098a242efb685cea1f9135d4a` END ALL0／外層0／JUnit1086/0/0 skip2／M6 10/mainOnlyPASS，summary SHA256 `B12B46A3E89C892C2A80815F35C2ABAD17CA42AC3DFF8DD005B78711FFD282ED`、最終索引 SHA256 `37981288E398C28065BF20995BAA43B53BCAFD5922C8E7C0ABAE04ECDF915DF3`。spec I1 fix1後ADDRESSED C0/I0/M0，quality I1 fix2後ADDRESSED C0/I0/M0。原中斷 `9f3ac0e3`、前pinfull `95f676dc` 外層晚檢FAIL、兩個mutation取證不完整root、fix1首GREEN gzip bytes FAIL與所有既有FAIL保原判。本code未push/遠端CI未採；下一步四份docs審/commit/push/同SHA雙jobCI，然後Task11。

- 2026-11-05（Codex，Task9 遠端結案）：最終 code pin `73266221f8334bd1dbb4ca5c0193e239e8395a93`，同pin雙focused216案次、`run-all-7c709cf9aa4f4f4eb2f036a8b1631fb1` END ALL0/JUnit1073/M6 10/mainOnlyPASS，spec I1/I2 fix3關閉 C0/I0/M0、quality C0/I0/M0。docs HEAD `870e2947fa02a8fd3196bcde62cd818a02ca0ca4` push/ls-remote同SHA，GitHub run `42035809807` Ubuntu/Windows雙jobsuccess；關鍵步驟成功，三artifact清單讀回未解壓。原 `run-all-d0b5d7fa10a64433892471e08d37d93d` 六FAIL、4096全NEW容量FAIL與舊slot root保原判。Task10 capacity RED名稱與Task9已驗writer上限重複，plan窄補採Task9前置、Task10新catalog超限仍另立RED，獨審C0/I0/M0；待純docs整合commit/push後派Task10。Task9只是格式解析，玩家random NEW仍待Task12。

- 2026-11-05（Codex，Task9 本機終局、遠端待）：範圍 `d52936d7dc6cf583f2eaeb4dd6d3950a6576914f..73266221f8334bd1dbb4ca5c0193e239e8395a93`，code commit `9ef4936`、測試相容fix1 `d414b60`、spec fix2 `ef7b2e5`、fix3 `7326622`，中間純plan窄補 `01be38c`。縮後 slot RED42/2→GREEN42/42，五mutation殺且REVERT；fix2 RED47/5→GREEN47/47與兩mutation殺；fix3 RED48/1→GREEN48/48與一mutation殺。最終四類雙focused各14/5/48/41×2、完整 `run-all-7c709cf9aa4f4f4eb2f036a8b1631fb1` END ALL0／JUnit1073/0/0 skip2／M6 10/10／main-only PASS。獨立spec初C0/I2→fix2 C0/I1→fix3 C0/I0/M0；獨立quality C0/I0/M0。首full `run-all-d0b5d7fa10a64433892471e08d37d93d` JUnit六FAIL，4096全NEW容量原FAIL、舊v3 hook各root仍原判；不挪成縮後PASS。最終證據索引 SHA256 `150E671F6DC917065DE1107F1CD13DF008BE60E391C67CB3802262B2C721AD5D`，fix3 RED/GREEN/突變索引 SHA256 `BF5760A0DEB3AB3CA5F558E27175403A71A0CCA2EF18B6EACCBB394187F19C9C`。此code未push/遠端CI未採；下一步本docs commit→push→CI→Task10，不在main實作。

- 2026-11-05（Codex，Task9 縮範圍裁示採用中）：使用者原話「我同意先推主線」，並裁示三個 reserved slot 原生 hook、相關 permit／負控／mutation 與專用 intermediary/JDI gate 移 Task8-H H5，Task21 後 triage；接受其他 mod 碰同 id 後至下次本模組 get/save 前可能被 vanilla autosave 寫入 foreign state。開始時 local/origin HEAD `743ffbf891b81b585e12dbbd3fc25d09656b089b` 為被取代的舊設計 docs commit；Task9 code 仍未提交 WIP。A/B/C 局部 RED／GREEN 與舊 slot root 均保原件原判，不作新版雙 focused/full／兩席／CI 採計。此筆修 spec／plan／M6 note／AGENTS／接手入口；獨立窄審初 C0/I1/M0（Task10–21「不寫壞」措辭過強），plan 改為偵測後本模組停寫、明示偵測前 vanilla autosave 風險，同席重核 C0/I0/M0。純 docs commit/push 後恢復縮後 Task9；main 合併、tag、PR、工作樹退役尚未授權。

- 2026-11-02（Claude 雲端 controller）：只有文件變更。spec v1.4／plan 8C 與 8-H／接手檔／AGENTS／M6 note；review 鏈與 CI 見第 6b 節。8C-0 未開工。下一步：Codex 依第 9 節派工 8C-0。
- 2026-11-04（Codex，8C-0）：code range `071bdf0..d39fef1`（`1c10b6a` 本體、`a8b8d20` Graph depth fix、`d39fef1` preset detect I-1 fix），九檔。具名 RED／GREEN、六個 mutation-only＋REVERT；最終乾淨 pin 三 class 各兩次 focused：Provider 5、Shallow 14、Contexts 44；depth fix pin 另有舊 CanonicalizerTest 123×2。新 full `run-all-3e4a5c71830d4a5ea0e642176c7576b2` 自然 `END ALL exit=0`／JUnit993／M6 9/9／mainOnly PASS；先前 `run-all-8b67…` JUnit 1 FAIL 原判保留。spec I-1 修後 C0/I0/M0、quality Approved C0/I0/M1；Minor 為測試 fixture 清理失敗的原錯誤保留，記於後續 fixture 整理。此筆尚未 push、無本 code 遠端 CI；下一步先 docs truth／push／CI，再 8C-1。
- 2026-11-04（Codex，8C-1）：8C-0 docs HEAD `551607c` 的 GitHub run `41833239533` Ubuntu／Windows雙job success。8C-1 code range `551607cc69a64ecebd5ebc31e2aa0afd5739a33a..d38178b467e6412aa070091ad8228fd22366347d` 恰 Registry／RegistryTest 兩檔；九具名 RED（12案9敗0錯）、四個最終 production mutation 具名被殺／REVERT，after-lease 首輪存活僅 summary 留存、raw 已不可恢復並有 ignored 勘誤。乾淨同 pin Registry12×2、Snapshot7×2、Shallow14×2 focused PASS；完整 `run-all-01dd2ce3b8b844178b291d4869ef00d5` 自然 END ALL0／JUnit1002／M6 9/9／mainOnly PASS。spec C0/I0/M1、quality Approved C0/I0/M1，同一 Minor 為首輪 survivor raw 保全缺口；原 FAIL 不改判。此筆 docs truth／push／本 code 遠端 CI 尚待；通過後派 8C-2。

- 2026-11-05（Codex，8C-2）：8C-1 docs HEAD `443221e` 的 GitHub run `41863993829` Ubuntu／Windows雙job success。兩次thread／ticket及GameTest entrypoint設計窄修後，base `f8f8d847b5509e02a84cde2c1685986d687c5780`，8C-2 code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667` 恰七檔；自然 startup acquire 具名 RED、Lifecycle具名RED、初次 factory失敗新RED→GREEN，最終Lifecycle12×2、settings GameTest1×2乾淨focused PASS。完整 `run-all-f9344a453d914fc2b22bae056c0fed1e` 自然END ALL0／JUnit1014／M6 10/10／mainOnlyPASS，另S1a Full strict10案固定十事件PASS；少案checker負例精確 `S1A_ORACLE_SCOPE`。spec C0/I0/M1、quality Approved C0/I0/M1；同一Minor為異常foreign END／dispatch未永久poison，符合tracked最小契約但弱於ignored addendum。早期FQCN／batch空／單參數編譯setup FAIL、stop mutation首輪存活、wrong-registry整root附S1a EVENT_ORDER FAIL、舊round2逐輪REVERT JSON缺口各保原判／限制，不補造。此筆docs truth／push／本code遠端CI尚待；通過後8C-3，Task9仍未開始。

- 2026-11-05（Codex，8C-3 終局準備）：8C-2 docs HEAD `e1eb9fcdda9c38496db803c0797a7477a7b363c1` 的 GitHub run `41911139872` Ubuntu／Windows雙job success，三artifact清單讀回、未解壓raw audit；採code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667`。同code本機 full `run-all-f9344a453d914fc2b22bae056c0fed1e` END ALL0／JUnit1014／M6 10/10／mainOnlyPASS、S1a strict Full 10案十事件PASS；code後只有四份docs，未重跑相同focused。本機8C-3獨立唯讀審核 C0/I1/M1（SHA256 `14E7689190DEFD7071F55A2B0852C5004677388DFF0825797785532FA1F92A48`），I1 為本檔 §4 過時直呼 legacy 摘要，已窄修待複審；M1 foreign END／dispatch 未永久poison 的受限偏離已在8C-2 note。H1完整閉包／100ms、H2正式source/refresh/consumer與LT2、H3三bundle、H4真reload仍待Task21後triage，沒有提前waiver或改判原FAIL。8C-3最終 docs commit／CI尚待，Task9未開始。

- 2026-11-05（Codex，8C-core終局）：末code pin `16b833cc43278fe8fb56e8e75f2aed4beee49667` 的本機 `run-all-f9344a453d914fc2b22bae056c0fed1e` 自然 END ALL0／JUnit1014／M6 10/10、S1a Full strict10案十事件PASS；code後只有四份docs。最終docs HEAD `0f02011b97e9b03b043afbfe33d14aa203d88708` 已正常push／ls-remote同SHA，GitHub run `41918108739` 同SHA completed/success，Ubuntu build／Windows windows-native雙job與M6／main-only／隔離／上傳步驟success，三artifact清單已讀未解壓。8C-3本機審核原I1為§4 legacy直呼摘要，已修且窄複審 ADDRESSED C0/I0/M1（SHA256 `E74D0653CD254E4BAB135E851924F219DD8D58EB14317BB6378A614AE1151140`）。此筆純狀態文件無code/build/workflow差異，正常push後Task9為下一片；其v2設計及本plan窄補均已獨審C0/I0/M0，隨本docs commit採用；Task9–21、8-H與M6整體均未完成，所有原FAIL保原判。

## 9. 8C-0 派工簡報（給 Codex implementer；binding 以 plan 8C-0 與 spec §3.1.3 原文為準）

**前置**：Windows 工作樹、porcelain 空、HEAD 為本檔所在 commit 或其後 docs commit；先 `gates.ps1 -Mode M6Compile -Label 8c0-baseline` 確認基線可編譯。先讀 plan 8C 總說與 8C-0、spec §3.1.2 (d)／§3.1.3、M6 note D1b-1（`ModBlocks` 須在 registries fixture freeze 前初始化）。

**允許檔案**：main `ServerSettingsContext.java`、`Minecraft121SettingsContexts.java`（只加 lease 兩個讀面）、`Minecraft121SettingsGraph.java`（只抽出 NBT canonical 純函式，行為不變）、新 `Minecraft121ShallowSettingsCanonicalizer.java`、新 `Minecraft121VanillaPresetProfileProvider.java`；test 新 `Minecraft121WorldgenRegistryFixture.java`、新 `Minecraft121ShallowSettingsCanonicalizerTest.java`、新 `Minecraft121VanillaPresetProfileProviderTest.java`、既有 `Minecraft121SettingsContextsTest.java`。不碰 `GenerationSettingsSnapshot.java`、`UniverseProfileRegistry.java`（8C-1）、任何 testmod／scripts／build。

**介面要點**：
- `ServerSettingsContext` 公開方法恰三個：既有 `requireValid()`，新 `DynamicRegistryManager.Immutable registries()`、`boolean sourceGenerateStructures()`。Lease 實作在既有 `synchronized(cell.fence){ validateLocked() }` 之後讀 `Sources.registries`，非 `DynamicRegistryManager.Immutable` 即拋既有 `unavailable()`（`CONTEXT_UNAVAILABLE`）；`Sources` record 型別不變。
- provider 同一類別以兩個 id 註冊：`quantumchamber:vanilla_normal_v1`（preset 必 normal）、`quantumchamber:vanilla_amplified_v1`（必 amplified），version 1，`adapterCapabilities` 恰 `quantumchamber:m6-settings-shallow-v1`；`ParameterSchema` 先 `GenerationSettingsSnapshot.validateBuiltinParameters` 再核 id↔preset。三 role 以 lease registries 的 Reference entry 建構（spec §3.1.3 精確 id）；`generateStructures` 取 Parameters。另 `Optional<String> detectOverworldPreset(lease)`。
- canonicalizer 輸入 `ResolvedSettings`＋Parameters＋lease；`RegistryOps.of(NbtOps.INSTANCE, lease.registries())`，encode 前後各 `requireValid()`；encode 後逐 role 結構檢查 id 字串（`SHALLOW_INLINE_ELEMENT`）；四鍵 envelope；canonical 由 Graph 抽出的純函式編碼（鍵序＝Unicode scalar 次序、list 保序、big-endian）；FP＝SHA-256(`quantumchamber:m6:settings:v1` UTF-8 ‖ canonical)；上限 8192 bytes。
- 固定 1.21 API 落點（實作時以 Yarn `1.21+build.9` 實查，不以此處為已驗）：`DimensionOptions(RegistryEntry<DimensionType>, ChunkGenerator)`、`NoiseChunkGenerator(BiomeSource, RegistryEntry<ChunkGeneratorSettings>)`、`MultiNoiseBiomeSource.create(RegistryEntry<MultiNoiseBiomeSourceParameterList>)`、`TheEndBiomeSource.createVanilla(RegistryEntryLookup<Biome>)`、keys `ChunkGeneratorSettings.OVERWORLD／AMPLIFIED／NETHER／END`、`MultiNoiseBiomeSourceParameterLists.OVERWORLD／NETHER`、`DimensionTypes.OVERWORLD／THE_NETHER／THE_END`、`DimensionOptions.CODEC`、`RegistryOps.of`。

**fixture**：`Minecraft121WorldgenRegistryFixture` 以 `ServerDynamicRegistryType.createCombinedDynamicRegistries()` 與 `RegistryLoader.loadFromResource(rm, preceding(WORLDGEN), RegistryLoader.DYNAMIC_REGISTRIES)` 真載入原版 worldgen registries（技法同 `Minecraft121SettingsCanonicalizerTest.EndFixture`，但不得 import 該 private record）；提供第二個入口以額外測試 pack 覆寫 `minecraft:amplified` noise settings（改 `sea_level`），供限制見證。每次載入都是獨立 registries；provider 與 canonicalizer 必用同一 lease 的 registries。

**具名 RED 清單**：依 plan 8C-0 第 4 點十七個名稱逐字使用；RED 須是具名斷言失敗，不是 compile error。mutation 四個與 killer 對應見 plan 8C-0 第 5 點；每輪 mutation-only diff、REVERT 後 `git hash-object` 比對原 blob。

**驗證命令**：`gates.ps1 -Mode M6Compile -Label 8c0-compile`；`m6-focused.ps1 -Suite JUnit -TestClass dev.quantumchamber.universe.Minecraft121ShallowSettingsCanonicalizerTest -Label 8c0-canon-{{1,2}}`、同法 `…Minecraft121VanillaPresetProfileProviderTest`、`…Minecraft121SettingsContextsTest`；完整 `run-all.ps1` 看 `END ALL exit=0`。結案 note 記實測三 role canonical 長度、JUnit 數、M6 GameTest 仍 9/9。之後 fresh spec／quality 各一席，C/I 清零才進 8C-1。
