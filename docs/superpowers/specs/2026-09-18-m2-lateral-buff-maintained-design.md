# M2 修訂：左右走廊與藥效維持

日期：2026-09-18。基準：`96c3514fe6c41b41ef78c567d7b8395c1075b7b4`，既有功能分支 `feature/m1-chamber`。

狀態：使用者已逐項確認下述玩法，並要求繼續實作；本文件整理技術與存檔契約，尚待書面規格審閱。新規則尚未實作，不以舊測試結果宣告完成。

本修訂取代 [原 M2 規格](2026-09-17-m2-powered-corridor-design.md) 的前後延伸、成功入場立即消耗效果與活動 session 不依藥效倒數結束三項規則；其餘原艙權威、有限資源、共享參與者、checked journal、native checkpoint、離線及安全清理契約維持。

## 1. 已確認的成功畫面

1. 建立7×7×7原艙，三種建材、Controller local(3,6,0)、25格正面門及5×5×5 interior不變。
2. 外部紅石供電讓有效原艙註冊並受保護；玩家可開門進入、關門後喝藥，或先有QuantumState。
3. 喝藥按原生規則消耗藥瓶並得到Buff；創造模式物品例外仍遵循原生，不額外改背包。成功入場不移除Buff、不重置duration／amplifier／hiddenEffect。
4. 全員資格成立後自動入場；以原艙門為基準，走廊向左右延伸。原艙正面門朝向不變，不靠轉動玩家鏡頭或旋轉整個艙體冒充。
5. 任一凍結參與者的Buff自然到期或被解除，整組安全回同一原艙；走廊收尾完成後看回普通盒子。仍供電時原艙保持保護。
6. 全員再次有Buff、原艙關門且條件成立，可再建立一個新session；不要求第二次紅石rising edge，不重用已結束session UUID。
7. 外部斷電仍先安全返還／收尾，再解除原艙保護；只有原建立world有合法維護／拆除權。

候選門開啟、測量塌縮、選定宇宙與跨宇宙passage仍留到M3／M4；本修訂不假開側門來冒充完成。

## 2. 方案與選擇

採用「原艙朝向不變、走廊獨立左右基底」：原艙幾何與identity維持，走廊pose／bounds／mapping明確承載自己的基底。代價是需要更新入口覆寫、入場姿態與租約驗證，但可逐項驗證四朝向及舊資料。

另一方案是將整個入口replica旋轉90度重用舊幾何；雖較少改轉換程式，卻改變正面門／Controller朝向，不能滿足本次以原艙門為基準的需求，因此不採用。

存檔採明確schema2與每筆session semantics；不另起互不協調的第二份journal。兩份journal方案會讓slot／來源保護／玩家所有權分裂，需要第二套協調與遷移，不符合單一權威邊界。

## 3. 左右基底與來源艙分離

新session使用來源Chamber的local +x作為正向縱軸；雙向延伸所以左右都可行走。走廊負向朝向為來源outwardFacing.rotateYClockwise()；正向為其opposite。

| 原艙outwardFacing | 走廊負向朝向 | 正向縱軸 |
| --- | --- | --- |
| NORTH | EAST | WEST |
| EAST | SOUTH | NORTH |
| SOUTH | WEST | EAST |
| WEST | NORTH | SOUTH |

來源艙座標helper保持原local(x,y,z)與原facing；來源返還槽位、相對排序、普通實體返還中心點不能改用走廊基底。至少明確分離兩種語意，不以一個可變全域開關改寫CorridorGeometry所有caller。

左右模式的軸轉換是正交旋轉，不是只交換x/z造成反射：來源block索引(x,y,z)對應走廊(lateral=6-z,height=y,longitudinal=x)；連續位置對應(lateral=7-z,height=y,longitudinal=x)，因block索引0..6而房間連續邊界0..7。速度對應(-sourceVz,sourceVy,sourceVx)；yaw用方向向量與兩個明確基底轉換，pitch不變。方塊中心、完整bbox、速度與yaw往返需實測四朝向，不用teleport的boolean當成功。

現有logicalZ識別字若保留，必須註明是歷史名稱，代表走廊縱向邏輯座標而非原艙z或世界Z。logical page96、門站8、floor division、logical DoorKey與多人split／merge演算法維持。

有限入口replica仍按來源艙facing覆寫同一7³體積，Controller及正面25格門保持原朝向。活動時連通入口兩側local x=0/6的5×5通道；正面門仍由原Controller門交易處理，後牆不再當左右走廊的連接面。普通來源原艙的牆與門不因此被拆除。

原logical0..6入口完整物化條件、兩側連接格與端cap保護沿用；apron576／視距設計32chunks不擴大。新builder須把入口覆寫與走廊base cell ownership分離，不能用新走廊frame直接放原艙Controller。

## 4. 藥效維持與共同返還

新session凍結完整cohort，至少一人、排除起始spectator，所有人完整bbox在interior且持有QuantumState。來源有效、enabled、sealed、供電與無衝突規則不變。

新入場流程：完整恢復資料checked落盤→幾何與chunk就緒→全員真native move→確認實際world／pose／bbox與全員仍有Buff→逐人原生saveAndVerify→確認群體→checked SUPERPOSITION提交→頁面publish。不得在任何一步remove、重新給藥或重置剩餘藥效；準備期間duration照原生tick遞減。

PageManager原checkInitialCohort中的「仍有QuantumState則拒絕」需按明確semantics分流：新模式要求全員有Buff，legacy資料保留舊驗證語意，不靠去掉所有effect驗證來放行。

每個權威tick在活動移動／重分頁前檢查完整cohort的實際player身分、Buff及原來源identity。任一人失去Buff，先checked標記RETURNING並阻止新操作，再走既有整組返還；途中重新喝藥不撤銷已開始的RETURNING，新入場必等舊session正式結束。

以原生實際是否仍持有有效QuantumState為準；同類效果的hiddenEffect接續仍算有Buff，不將高階效果降級誤判成完全失效。保持原生飲用、牛奶與duration ticking行為。

prepared remap期間與publish前仍重驗Buff；若移動途中失效，不publish半個cohort，也不拆正在占用的舊映射。使用已核准的default-off testmod真移動觀察驗此窗口，不新建production fault setter。

死亡／斷線／重啟仍使用既有共同返還與offline pending策略，不移除凍結名單。返還只套KEEP_CURRENT marker；對還有Buff的人保留當下完整效果／自然duration，不刪除、不退款、不還原入場snapshot。已到期者仍沒有Buff。

失敗回滾的pose與effect政策必須分開：新模式即使效果政策是KEEP_CURRENT，提交前部分移動失敗仍在正確原始Controller identity可確認時嘗試回原pose；未確認就保留RETURNING。不能沿用舊「restoreEntryEffectOnReturn=false就不回滾pose」的耦合；新模式永不重套舊效果snapshot。

## 5. 供電、保護與再次入場

Buff結束不是外部斷電，也不是Origin lifecycle停用。Registry仍enabled、Chamber UUID保留，供電HIGH時保護保留；不能因回普通盒子就讓玩家拆掉仍供電的原艙。

返還仍按全員checked checkpoint／durable returned→有價items安全返還→所有pins與幾何清理→整筆journal checked移除→已知release receipt→上層移除runtime/source票並ack→來源coordinator重新評估電力與資格。

現有ChamberPowerCoordinator已有HIGH遇RETURNING也呼叫returnToOrigin的握手，不需要第二套完成權威；須測試最後receipt被消費後回POWERED／IDLE，而非卡ARMED或誤發布OFF。供電LOW／未知／壞來源／offline的保守保護不放寬。

全員補Buff、正面門關閉後可再進；保留一個來源艙至多一個session、一個玩家至多一個、不同SID完整空間不交、held-high不複製活動session。新的Buff不製造假edge。

## 6. 舊journal與明確schema2

同一 `quantumchamber_sessions.dat` 支援strict讀取schema1與schema2；不靠檔案存在就假設新模式。未知schema、缺欄位、錯NBT型別、未知semantics或不合法policy均fail closed，保留正式資料與必要保護。

schema2每筆record必有明確`SessionSemantics`：

- `LEGACY_FORWARD_CONSUMED`：舊前後軸、入場消耗效果的歷史模式。ARMING的restoreEntryEffectOnReturn必true、SUPERPOSITION必false、RETURNING保留原決策。
- `LATERAL_BUFF_MAINTAINED`：新左右軸、效果持續維持。ARMING／SUPERPOSITION／RETURNING的restoreEntryEffectOnReturn都false；來源snapshot仍保留完整不可變資料，但不拿來退款／延長藥效。

只有兩個明確組合，不允許任意geometry與effect旗標交叉猜測。schema1按完整舊codec驗證後才在記憶體標記legacy；既有欄位缺失或ARMING,false等原本非法的資料仍拒絕，不因新版放寬舊解析。

新程式啟動一律取消persisted活動session並返還，不重建舊活動狀態。legacy record只用原facing與persisted lease.bounds驗票／loaded+ticking／pin掃描、安全返還及清理；不將舊bounds旋轉、不當新模式remap、不重建左右入口。舊restore／keep-current與durable marker冪等契約保留，包括hiddenEffect與到期duration不重套。

只有正常恢復進度的checked落盤可將已驗證legacy record以明確semantics寫入schema2 envelope；來源UUID／role／world／anchor／facing、完整cohort snapshots、bounds／返還policy不轉置或猜改。讀取本身不先覆寫檔案；write失敗保留上一flushed權威。正式schema2存檔不能再交不支援它的舊binary使用；指引要求使用者人工備份，不擅自移動或刪存檔。

State.put及PageManager破壞性清理前，至少凍結同SID的來源identity、cohort來源snapshots、SessionSemantics；合法state／lease／returned進度按契約更新。不能remove後重放換semantics來繞過flushed防線。

PlayerRecoveryCheckpoint schema1及RESTORE_ENTRY／KEEP_CURRENT標記不擴欄位；新模式只KEEP_CURRENT，legacy使用原policy。入場Optional.empty不要求清掉前次合法marker；真native完整NBT保存／同HANDLE驗證及Windows NTFS支援限制維持。

## 7. 驗證與交付

保留Minecraft1.21／Yarn1.21+build.9／Loader0.17.2／API0.102.0+1.21／Loom1.7.4／Java21／Gradle8.8、單一module／client分離、三種建材／青紫造型、原生粒子與可選手持火把方案。不加mandatory renderer／packet／library／LICENSE，不引入UniverseRegistry／runtime allocation／measurement／passage。

逐任務TDD與獨立spec＋quality評審；已存在Task5固定範圍review須結案後才能新production任務。舊105GT／166JVM＋1OSskip／29JVM只證明舊契約，不能當這份修訂通過。

必要新證據：

- 四朝向原艙／左右基底：block中心／連續pose／速度／yaw／bbox雙向正確、Controller與正門朝向不變、local x兩側通道、正負長走／回頭／96 seam、近split/merge／items/projectiles與遠入口還原。
- 真飲用後入場保留Buff及hiddenEffect；準備／成功checkpoint不重置duration，缺Buff全組拒絕。
- 自然到期、真牛奶解除，以及任一人失效的整組RETURNING→原艙；其他人當下Buff不被刪／退款，HIGH仍保護／IDLE；再喝／sealed／held-high建立新SID。
- Buff失效與partial remap、斷電同tick、返還中再喝的明確序列；不publish半群、不重用空間或先解鎖。
- fresh schema1的ARMING／SUPERPOSITION／RETURNING true與false進不同JVM恢復；正式NBT／marker／完整effect/hidden／lease bounds保留，legacy只return-only，第二次restart不重套。
- schema2新ARMING,false／SUPERPOSITION,false／RETURNING,false正常restart與已知marker；無效果退款／延長、無舊active重建；semantics／origin／cohort變更拒絕不改current/flushed/正式hash。
- 部分ticket acquire／非法bootstrap保持真native membership／對稱回收／原hash／tick0與終末witness，default-off probe／fault不進release。
- 最後非快取clean build／全GT／XML與略過理由、release/sources JAR污染與common/client邊界、fresh main-only四world Done→stop→save、最後classes對應的重啟／checkpoint窗口及正式player/entity資料讀回。

全部harness只owned fresh loopback nonce worlds，PID／startup nonce／canonical storage與直接child身分保留，不回填manifest NBT、不操作使用者／共享JVM，不刪既有world／journal。

人工待驗：單人免指令新流程、日夜輝光、HUD主副手火把、32chunk遠望／回頭／seam、Sodium／Iris／shader／resource reload。GPU／Linux／任意foreignworld／硬體效能與整機斷電一致性不由本機build冒稱；真正foreignworld仍M3 gate。

只在使用者特定要求時推feature審查checkpoint；main合併仍須所有必要自動／獨立review與人工gate關閉，不因本次繼續實作授權自動合併。
