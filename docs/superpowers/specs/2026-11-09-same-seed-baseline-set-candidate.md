# same-seed canonical-only 基線集合候選（2026-11-09）

**歷史候選，未生效；2026-11-09 最新裁示：T13 不採 baseline 集合作為 gate，T21 另裁。** T13 改依 binding spec §4.1／§4.2 的嚴格 actual CARVERS 層與完整 final 診斷；本文以下保留原候選、反例與當時待裁示文字，不解讀為目前 T13 路線。已見 hash 僅診斷分類，新 final hash 標 UNRESOLVED 送審，不自動加入集合、不使任何舊 FAIL 轉為 PASS。歷史核對基準 `c19aec704f71c93d9edf7de3b41aa8b29a2b3553`；Task13仍OPEN、不進Task14。本文未實作或執行下列集合 oracle 測試。

## 1. 目前證據及可支持範圍

現行規格為 [M6 spec §4.1／§4.2](2026-10-24-m6-universe-generation-design.md)；控制結果見 [M6 note 最末 normal 報告](../../implementation-notes/2026-10-24-m6-universe-generation.md)。normal批次 `t13vc-mw8nd4zy-r2` 的獨立audit原件為 ignored `task13-control-independent-audit-20261109.json`，SHA-256 `5651b20184fde6bf11854e2d350e6aa276783011e0552bd38513120a9381342f`。

| 項目 | 觀察 | 界線 |
| --- | --- | --- |
| canonical normal／seed20260921 | 十個世界資料有兩種完整1440格hash，82e9×9／704b×1 | 原02外層SETUP錯誤保留；不稱原批次十根全PASS |
| 兩個hash | `82e90855546ea91cb34dfaa3483689655e9688cb3d2aeca2322a511725d0ccf6`、`704bcc53c0544f0448eba13a870a9ca64d4beab23b99097cd99fc123a784b0d4` | 只是候選見證，未成為允許集合 |
| 差格 | index1217/1337：leaves/air；1375：coal_ore/granite | 完整rows保留，不訂差格數／比例容差 |
| strict對照 | biome、post-FULL raw density、provider FP相同；無動態U LOAD/storage，catalog/allocation為空 | 合法EMPTY檔可存在；runtime仍含本模組、testmod及既有superposition世界，不是無模組純vanilla |
| 歷史連結 | 第10根1440列與兩個舊normal 704b來源收據相同 | 不證所有舊FAIL因果、不覆蓋amplified或U→U |
| amplified-only十次 `t13ac-mw8oturs` | 10/10有效，CENTER_FULL與FULL_3X3均3580×10，未觀察1a3c | 僅本批未重現另一型；不替舊target FAIL或U→U改判，沒有核准集合 |

normal的三格差在CENTER_FULL已存在，各根到FULL_3X3零變動，見 `task13-normal-center-full-audit-20261109.json`（SHA-256 `67181d7b809e47156a7073ec9abf6b1e7f02f7da11e5348b4b5d833113662962`）。amplified獨立audit `task13-amplified-independent-audit-20261109.json`（SHA-256 `6f11b69be301d1e39d9a37c70616bc2dbeb091766095e0763113ad06c4737b4a`）核30份原件及330class；與舊target1a3c相比恰index1216 `(527,64,512)` coal_ore/stone一差，這不是canonical-only已見1a3c的證據。

post-FULL `finalDensity` raw bits不是NOISE階段方塊，既有FP是SHALLOW_V1，不能據此宣稱feature前block或完整設定閉包相同。

## 2. 候選語意：固定受審集合，不自動學習

每個有界分類C有一份唯讀、已釘SHA與版本的基線集合B(C)。集合每筆保存完整1440座標／block rows及其hash，不只保存hash字串；只有有完整來源proof、獨立重算通過並經使用者裁示／獨立spec review明列納入的canonical-only見證，才可成為正式B(C)。採集到新hash不自帶授權。

未有符合分類的受審集合即 `BASELINE_CLASS_UNAPPROVED`，不能借最近seed、另一preset或另一task的集合。執行者不能自行擴集合、覆寫既有檔、以當次U輸出註冊baseline或在FAIL後重跑改判。更新集合必另開版本、列原始新見證與理由、重新審查／裁示；歷史版本與FAIL原件不動。

候選判讀順序：

1. 驗證strict權威、runtime條件、完整rows與分類一致；任一錯先FAIL。
2. 由實際1440 rows依固定編碼獨立重算final block hash。
3. hash須在B(C)，且rows必與該受審成員完整rows逐項相同；known hash不能跳過rows或strict檢查。
4. 新hash立即 `SAME_SEED_BASELINE_UNKNOWN_HASH`／FAIL；相對全部受審成員產出逐格diff（index／XYZ／實際及期望block／biome／density），不挑有利子集、不刪差格、不自動加入集合。無可用成員時保實際rows及分類失敗原因，不假造reference。

## 3. 分類C與證據身份

**跨fresh root可比較的分類鍵C**至少釘下列生成條件與適用範圍；任何缺失、漂移、未知型別或不匹配均不能套用。root/nonce/PID不屬於這些跨根相等鍵：

- task／方向（T13 canonical→U或T21 direct catalog U→U）、normal/amplified、root seed精確long、role、GenerateStructures。
- Minecraft1.21／Yarn1.21+build.9、Loader/Fabric/API版本、實際mod清單與JAR/class SHA、有效datapack內容manifest及啟用順序；不能只比包名。
- 來源／目標generation snapshot內容、provider id/version、Parameters、FP、closure contract及原生consumer owner需符合的契約；SHALLOW_V1內容變動檢不出，故另釘資源內容身份且保其已知限制。每根實際owner identity與驗證收據另歸下方provenance，不要求不同JVM的物件identity相等。
- 固定座標：x外圈512..527 step3，z內圈同範圍，y最內-64..<256 step8，索引0..1439；每列block用原BlockState.toString；既有Gson有序string list→UTF-8無BOM/換行→SHA-256小寫hex，附座標／編碼版本。
- 原九格virgin、3×3 FULL、中心32,32及鄰域31..33、顯式請求序列與global交錯、各採樣階段；canonical-only只具每側請求投影，不能假稱重現雙世界global交錯。

**每筆見證的provenance**另保存root／nonce／startupNonce／pid／launch pin、採樣producer/source SHA、編譯class manifest SHA、實際owner identity、完整preflight/sample/final/properties/process及獨立audit SHA。每筆必在自己的root內核identity、時間、內容SHA與receipt引用鏈，且producer/class/config須符合C所釘版本；缺欄、錯根、混nonce、借別次owner或斷SHA鏈仍FAIL。這些唯一身份及個別proof SHA不必與待驗run或另一baseline root逐值相等；不同身份是不同見證，不能因跨根不同就拒絕同類生成條件，也不能因此放棄每根內部身份核驗。

canonical-only與正式鏈的玩家／艙／重啟／全球交錯差異須有具名適用性論證，才可在裁示時指定其對哪一方向有效；不能因seed/preset相同就略過這些差異。

## 4. T13與T21分開評估

| 方向 | 候選如何用集合 | 尚未證明及採用門檻 |
| --- | --- | --- |
| T13 canonical→U | canonical來源與新U各自final rows/hash都須屬同一已核准C的B；兩側可命中不同受審成員，strict biome/density/structure仍須相符 | normal有兩型、amplified本批僅3580見證且未重現舊target1a3c；兩preset皆尚未核准替代來源/目標block exact，不能共用hash池 |
| T21 direct catalog U→U | 若另獲裁示，兩個U各自對適用於T21的canonical-only B核對；不能把U→U產物本身當canonical baseline | 現有normal canonical-only不證兩U各自符合canonical生成分布；需該方向的owner／fixture／請求適用性證據及獨立spec review，不由T13通過推定 |

這是有限驗收集合候選，不是證明「所有同seed生成都應落此集合」。原T21 c7d/e1d與T13 dd57/35f5維持原判；歷史新舊root不能因新集合而回填通過。T21 direct catalog測試也不開放spec §6延後的動態U-origin真艙。

## 5. 保持不變的strict條件

兩方向共通的root seed、frozen source與target settings、provider/FP、各自應具的catalog UUID及exact原生noise/biome/structure consumer owner均不降低。**T13正式入口**另保checked MEASURED+SELECTED、凍結policy/profile、allocation token與catalog持久鏈；**T21 direct catalog fixture**保其直接配置的frozen settings、profile/owner、UUID/catalog證據及原helper斷言，不要求該fixture不存在的正式玩家入口或MEASURED+SELECTED收據，也不稱它完成正式入場。

biome維持原1440逐項exact。noise-density需從具名actual NoiseConfig及frozen seed/settings的獨立expected路徑重算同座標raw bits，不只信producer寫的densityHash；此證據仍只稱density，不冒稱NOISE block。

非空structure正例、位置／bounding box／children、來源建築不複製、雙向修改隔離、storage ownership均照原oracle。restart保持同root／同UUID／同凍結權威與既有持久rows/hash exact，不以「也是集合另一成員」容許已保存地形在重啟漂移。其他GameTest／JUnit／main-only／CI門檻照舊。

## 6. 未來具名反例與驗證計畫（尚未執行）

| 測試名 | 必要結果 |
| --- | --- |
| `baselineRejectsWrongSeed` | 即使餵known block hash，frozen/actual seed錯仍FAIL |
| `baselineRejectsWrongPreset` | normal集合不能接amplified；錯provider／參數亦FAIL |
| `baselineRejectsInjectedBlockOutsideSet` | 人工改一個樣本block，從rows重算新hash，列完整diff並FAIL |
| `INJECT_KNOWN_BASELINE_VARIANT` | 把normal82e9的index1217/1337由leaves改air、1375由coal_ore改granite，正好成704b完整rows；seed/settings/biome/density/structure不變，純membership預期存活，必如實記NOT_KILLED／候選缺口 |
| `baselineRejectsUnknownHashWithoutLearning` | 新hash拒絕；前後baseline bytes/SHA/版本不變 |
| `baselineKnownHashDoesNotBypassStrictOwner` | known hash配錯noise/structure owner、biome或density，逐類具名FAIL |
| `baselineRejectsRewrittenOrUnapprovedManifest` | 改成員、rows或分類／來源proof、掉欄、改SHA或未核准版本皆FAIL |
| `baselineRestartCannotSwitchKnownMember` | 同root restart改落另一known成員仍FAIL |
| `baselineIndependentRowsRecalculation` | 獨立實作從rows重建原編碼，假hash或錯座標序必FAIL |

所有負控用副本或testmod輸入，不更動正式世界／舊收據；保具名RED/GREEN與mutation-only＋REVERT、新根、patch及SHA。未有使用者新裁定前不實作oracle；裁定後先修binding spec/plan並獨立spec review C/I0，才按原SDD、乾淨雙focused/full、獨立quality與同pin CI實作驗證。

## 7. 限制與對binding節的影響

有限B可能false-fail：合法生成結果尚未被見證也會FAIL，不能以此直接斷產品bug。更具體的已知反例是 `INJECT_KNOWN_BASELINE_VARIANT`：將82e9三格人工改為704b時，hash與全部rows都能精確命中另一受審成員，而所有列出的strict條件可同時保持原值。這不是SHA碰撞，是落入另一合法variant。純membership **不能保證所有人工注入方塊變動都FAIL**；禁止以負控旗標直接判FAIL，假裝資料oracle殺到此反例。T13來源或新U、T21任一U都具有同樣限制，另一側對比不能在允許不同成員後自動恢復這項保證。

樣本外修改亦不被1440點hash覆蓋。strict owner/權威、完整rows及canonical來源proof只能增加判斷依據，不能辨識本次U產出是否人工改成known variant。使用者需裁定接受的保證強度（有限已觀測形狀檢查，仍存在此漏判），或要求另外能辨識生成來源／修改事實的獨立證據後再採用；本候選尚未提供後者，也未滿足「人工注入全部FAIL」的強保證。不能把這個缺口透過措辭改成已驗。

若獲採用，§4.1將須明確裁定哪些方向／preset的final block pairwise exact改為固定B membership，並保持上述strict項；T21不得隨T13自動放寬。§4.2目前只授權有界控制、保正式標準且禁止比例容差，未授權B成員。因此本文件不能當成對兩節的隱式修訂。採用前兩節原PASS標準完整有效；本次amplified-only結果也只新增見證，不自動改驗收。
