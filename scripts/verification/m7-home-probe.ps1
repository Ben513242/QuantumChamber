# M7 T4 Phase B1／B2a／P1：Windows 原生跨 JVM 鏈。close 矩陣 close-{ordinary|sealed|passable}-{cut}（39 列，B1）與 B2a 的其餘鏈（restart-*、receipt-*，5 列），
# 以及 T4 P1 的 receipt-mismatch-stop（收據不符時正常停服；halt 點在停服完成之後）與 T4 P2 的 receipt-write-fail-stop（journal 正式寫出故障期間正常停服）。
# 每列一個全新 owned root（永久保留），兩個 phase 各一個獨立 JVM：crash 在預定點 halt(77)，reopen 以新 JVM 重開、正常停服 exit 0。沿 probe-common.ps1 的 owned phase 機制
# （PID＋啟動時間身分、逾時停止、log 收集）與 m6-universe-probe.ps1 的 readonly summary＋VerifySummary＋SelfTest 做法。
# 參數：
#   -Only <列名,...>     只跑指定列（manifest 順序）；-DoorState ordinary|sealed|passable 只跑 close 矩陣該門態的 13 列（B2 CI 分片）；
#   -Family close|restart|receipt 只跑該族的列（close＝39 列）。三個 -DoorState 分片與 restart、receipt 兩族的聯集恰為全部列、互不重疊。
#   -VerifySummary <檔>  唯讀重驗已存 summary（逐列、逐 phase、逐退出碼與 receipt hash／內容）；-SelfTest 另跑負控（不改原件）。只有選擇 all、恰為 windows.native 全部 46 列、
#                        treeClean=true 的那一份才輸出 M7_HOME_VERIFIED_COMPLETE PASS（T4 完整 PASS）；其他（-Only、-DoorState、-Family）只輸出 M7_HOME_VERIFIED_SHARD PASS
#                        （分片 PASS）。-RequireComplete 時分片以 M7_HOME_NOT_COMPLETE 失敗。treeClean 不是 true 的 summary 一律以 M7_HOME_SAVED_TREE_DIRTY 拒絕。
#   -VerifyAggregate <檔,...>  唯讀彙總多份分片 summary（T4 B2b-2）：成員全部 treeClean、同一個 HEAD、各自通過單份驗證，列的聯集恰為 windows.native、沒有重疊；
#                        合法組合只有「三個 -DoorState 分片＋restart＋receipt」與「close＋restart＋receipt」。PASS 輸出 M7_HOME_AGGREGATE_COMPLETE PASS（T4 完整 PASS）。
#                        -SelfTest 另跑彙總負控與每一份成員的單份負控（總數上限 479，與 M6 同）。
#   -ExpectedHead <sha>  summary 的 HEAD；未給時用目前 HEAD
# phase receipt 位置由 testmod M7HomeProbe 固定為 <repo>/.superpowers/verification/m7-home-<nonce>/<phase>/（halt.json 或 final.json）。
param([string[]]$Only=@(),[ValidateSet('','ordinary','sealed','passable')][string]$DoorState='',[ValidateSet('','close','restart','receipt')][string]$Family='',
      [string]$VerifySummary='',[string[]]$VerifyAggregate=@(),[switch]$RequireComplete,[string]$ExpectedHead='',[switch]$SelfTest,
      [string]$EvidenceRoot='',[string]$Gradle='',
      [int]$IdleSeconds=90,[int]$PhaseMinutes=5,[int]$BlockMinutes=240,
      [int]$GradleCodePage=[Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$Only=@($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$VerifyAggregate=@($VerifyAggregate | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if($SelfTest -and -not $VerifySummary -and -not $VerifyAggregate.Count){throw 'M7_HOME_VERIFY_SCOPE：SelfTest 僅供唯讀 VerifySummary／VerifyAggregate'}
if($RequireComplete -and -not $VerifySummary){throw 'M7_HOME_VERIFY_SCOPE：RequireComplete 僅供 VerifySummary（VerifyAggregate 本身就要求完整）'}
if($VerifySummary -and $VerifyAggregate.Count){throw 'M7_HOME_VERIFY_SCOPE：VerifySummary 與 VerifyAggregate 只能擇一'}
if(($VerifySummary -or $VerifyAggregate.Count) -and ($Only.Count -or $DoorState -or $Family)){throw 'M7_HOME_VERIFY_SCOPE：唯讀驗證不得混入 runtime 選擇'}
if(@(@(($Only.Count -gt 0),[bool]$DoorState,[bool]$Family) | Where-Object {$_}).Count -gt 1){throw 'M7_HOME_SELECTION_MIXED：-Only、-DoorState、-Family 只能指定一個'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'probe-common.ps1')

# 固定矩陣：三門態 × 十三 cut（逐字沿 plan Task 4），之後是 B2a 的其餘鏈。Java 的 rowTable（M7HomeClosurePhases.rows）與 inventory 的 windows.native 必須與它逐項相同。
$doors=@('ordinary','sealed','passable')
$cuts=@('before-close','after-close','before-door','after-door','before-checkpoint','after-checkpoint','after-temp-force','before-publish','after-publish','after-readback','after-recheck','before-remove','after-remove')
$initialDoor=@{ordinary='CLOSED/NONE';sealed='OPEN/SEALED_X';passable='OPEN/PASSABLE_X'}
$fromExisting=@('before-door','after-door','before-checkpoint','after-checkpoint','verify-existing','after-recheck','before-remove','after-remove')
# B2a 的其餘鏈（M7HomeChainPhases.Chain；預期終局見 SDD t4b2a-report.md）：crash 的 halt 點與事件、reopen 的事件與第一次改門的 outcome、兩個 phase 必須成立的 Java 斷言。
$extraSetup=@('originRegistered','projectionWritten','entryValid','bDoorInitial','checkedArming','checkedDormant','participantInB','baselineShape')
$readySetup=@('checkedReturned2','checkedCollapsing','checkedReady','checkedPartialCrossed')
$extraReopen=@('crashReceiptMatches','homeChunksStable','journalBytesFromCrash','registryBytesFromCrash','receiptsFromCrash','bMaterializedAtStart','bDoorDiskState',
    'participantLoginPose','participantStill','activeRemoved','reopenEvents','reopenDoorOutcome','reopenCheckpoint','noEarlyUnlock','receiptClose','receiptSession','receiptFiles',
    'bDoorClosedOut','aDoorUntouched','registryEnd','resourcesReleased','playerClosedDoorKept','noRepairAfterRemoval')
$extras=@(
    [pscustomobject]@{row='restart-enable-before-entry';family='restart';door='ordinary';cut='registry-saved';index=-1;crash=@();reopen=@($cuts);outcome=@('APPLIED')
        crashObserved=@($extraSetup+@('baselineEnabled','disableNotAcked','registryDisabledInMemory','stopNotWritten','eventQueued','journalFileUnchanged','registryFileRewritten'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','playerClosedDoor','earlyRowFromCrash','earlyRegistryDisabled','enableBeforeScanPending','scanLatchedStop','registryStillDisabled','resumeRejected','repairAfterScan'))}
    [pscustomobject]@{row='restart-ready-stop-crossed';family='restart';door='passable';cut='repaired-saved';index=-1;crash=@();reopen=@($cuts);outcome=@('APPLIED')
        crashObserved=@($extraSetup+$readySetup+@('disableAcked','readyStopLatched','repairAcked','stopKeptAfterRepair','baselineRepaired','aDoorUnchanged'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','playerClosedDoor','readyStopFromCrash','registryRepaired','inStartupSet','crossedKept'))}
    [pscustomobject]@{row='restart-ready-low-no-root';family='restart';door='passable';cut='low-saved';index=-1;crash=@();reopen=@($cuts);outcome=@('APPLIED')
        crashObserved=@($extraSetup+$readySetup+@('spawnChunkRadiusZero','spawnSearchAvoidsA','aPowered','poweredNoLatch','aLow','lowNoLatch','baselineLow','aDoorUnchanged'))
        reopenObserved=@($extraReopen+@('playerClosedDoor','readyLowFromCrash','scanNoLatchOnLow','aNotLoadedAtStart','spawnSearchAvoidsA','aNotLoadedAfterLogin','inStartupSet','crossedKept','aNeverLoadedDuringClose'))}
    [pscustomobject]@{row='receipt-existing';family='receipt';door='ordinary';cut='remove-rejected';index=-1;crash=@($cuts[0..11]+@('remove-rejected'));reopen=$fromExisting;outcome=@('UNCHANGED')
        crashObserved=@($extraSetup+@('disableAcked','stopLatched'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','playerClosedDoor','closeFromCrash','busyAtStart'))}
    [pscustomobject]@{row='receipt-player-closed';family='receipt';door='ordinary';cut='player-closed';index=-1;crash=@($cuts);reopen=@();outcome=@()
        crashObserved=@($extraSetup+@('disableAcked','stopLatched','participantStill','crashEventsFull','crashReceiptRoot','crashClosedOutOpen','crashPlayerClosedDoor'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','journalEmptyAtStart','notBusyAtStart','closedDoorKeptAfterRestart'))}
    # T4 P1：熱列移除失敗（RESTORED，journal dirty）之後竄改收據、放標記與 gamerule，正常停服；halt 點 stopped 在停服完成（SERVER_STOPPED）之後。
    [pscustomobject]@{row='receipt-mismatch-stop';family='receipt';door='ordinary';cut='stopped';index=-1;crash=@($cuts[0..11]+@('remove-rejected'));reopen=$fromExisting;outcome=@('UNCHANGED')
        crashObserved=@($extraSetup+@('disableAcked','stopLatched','removalRestored','receiptPublishedExact','receiptTampered','markersPlaced','stopCompleted','journalCleanAfterStop',
            'journalRowAfterStop','receiptKeptAfterStop','adminErrorOnce','receiptRestored'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','playerClosedDoor','closeFromCrash','busyAtStart','stopSavedLevel','stopSavedMarkers'))}
    # T4 P2：熱列移除失敗（RESTORED，journal dirty）之後武裝 journal 正式寫出的 atomic move 故障，autosave 一次（失敗、保留 pending）、放標記與 gamerule，正常停服；
    # halt 點 stopped 在停服完成（SERVER_STOPPED）之後。
    [pscustomobject]@{row='receipt-write-fail-stop';family='receipt';door='ordinary';cut='stopped';index=-1;crash=@($cuts[0..11]+@('remove-rejected'));reopen=$fromExisting;outcome=@('UNCHANGED')
        crashObserved=@($extraSetup+@('disableAcked','stopLatched','removalRestored','receiptPublishedExact','autosaveNoThrow','autosaveKeptPending','markersPlaced','stopCompleted',
            'stopRetriedWrite','journalHeldAfterStop','journalFileUnchangedAfterStop','journalRowAfterStop','receiptKeptAfterStop','nativeErrorOnce','noSessionError'))
        reopenObserved=@($extraReopen+@('aDoorAtStart','playerClosedDoor','closeFromCrash','busyAtStart','stopSavedLevel','stopSavedMarkers'))}
)
$manifest=@(@(foreach($door in $doors){for($i=0;$i -lt $cuts.Count;$i++){[pscustomobject]@{row="close-$door-$($cuts[$i])";family='close';door=$door;cut=$cuts[$i];index=$i}}})+$extras)
$inventory=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json') -Raw | ConvertFrom-Json
if(@($inventory.windows.native).Count -ne $manifest.Count -or (@($inventory.windows.native) -join ',') -cne ($manifest.row -join ',')){throw 'M7_HOME_INVENTORY：m7-inventory.json windows.native 與固定矩陣不符'}
$shards=@(@(foreach($door in $doors){,@($manifest | Where-Object {$_.family -ceq 'close' -and $_.door -ceq $door} | ForEach-Object row)})+@(foreach($name in 'restart','receipt'){,@($manifest | Where-Object family -CEQ $name | ForEach-Object row)}))
if(((@($shards | ForEach-Object {$_.Count})) -join ',') -cne '13,13,13,3,4' -or ((@($shards | ForEach-Object {$_}) | Sort-Object) -join ',') -cne (($manifest.row | Sort-Object) -join ',') -or @($shards | ForEach-Object {$_} | Sort-Object -Unique).Count -ne $manifest.Count){throw 'M7_HOME_SHARDS：三個 -DoorState 分片與 restart、receipt 兩族的聯集必須恰為全部列且互不重疊'}

function Get-M7Group($spec) {
    if($spec.family -cne 'close'){return $spec.family.ToUpperInvariant()}
    if($spec.index -eq 0){return 'A'}
    if($spec.index -le $cuts.IndexOf('before-publish')){return 'B'}
    if($spec.index -le $cuts.IndexOf('before-remove')){return 'C'}
    return 'D'
}
function Get-M7CrashEvents($spec) { if($spec.family -cne 'close'){return @($spec.crash)}; return @($cuts[0..$spec.index]) }
function Get-M7ReopenEvents($spec) {
    if($spec.family -cne 'close'){return @($spec.reopen)}
    switch(Get-M7Group $spec){
        'A' {return @($cuts)}
        'B' {return @($cuts[2..12])}
        'C' {return $fromExisting}
        default {return @()}
    }
}
function Get-M7Selection {
    if($Only.Count){
        foreach($name in $Only){if($name -cnotin $manifest.row){throw "M7_HOME_ROW_UNKNOWN：$name"}}
        if(@($Only | Sort-Object -Unique).Count -ne $Only.Count){throw 'M7_HOME_ROW_DUPLICATE'}
        return [pscustomobject]@{mode='only';doorState='';family='';rows=@($manifest | Where-Object {$_.row -cin $Only} | ForEach-Object row)}
    }
    if($DoorState){return [pscustomobject]@{mode='doorState';doorState=$DoorState;family='';rows=@($manifest | Where-Object {$_.family -ceq 'close' -and $_.door -ceq $DoorState} | ForEach-Object row)}}
    if($Family){return [pscustomobject]@{mode='family';doorState='';family=$Family;rows=@($manifest | Where-Object family -CEQ $Family | ForEach-Object row)}}
    return [pscustomobject]@{mode='all';doorState='';family='';rows=@($manifest.row)}
}
function Get-M7Spec([string]$Name) {
    $spec=@($manifest | Where-Object row -CEQ $Name)
    if($spec.Count -ne 1){throw "M7_HOME_ROW_UNKNOWN：$Name"}
    return $spec[0]
}
function Test-M7Json($a,$b) { return (ConvertTo-Json -InputObject $a -Depth 20 -Compress) -ceq (ConvertTo-Json -InputObject $b -Depth 20 -Compress) }
function Get-M7Names($files) { return @(@($files) | ForEach-Object name) }
function Assert-M7Identity($receipt,$spec,[string]$Phase,[string]$Nonce,[string]$StartupNonce) {
    if($receipt.producer -cne 'M7HomeProbe' -or $receipt.m7ProbeSchema -ne 1 -or $receipt.row -cne $spec.row -or $receipt.door -cne $spec.door -or $receipt.cut -cne $spec.cut -or $receipt.group -cne (Get-M7Group $spec) -or $receipt.phase -cne $Phase -or $receipt.nonce -cne $Nonce -or $receipt.startupNonce -cne $StartupNonce){throw "M7_HOME_RECEIPT_IDENTITY：$($spec.row)/$Phase"}
    if((@($receipt.rowTable) -join ',') -cne ($manifest.row -join ',')){throw "M7_HOME_ROW_TABLE：$($spec.row)/$Phase"}
    if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -cnotin @($receipt.loadedModIds)){throw "M7_HOME_RUNTIME_SCOPE：$($spec.row)/$Phase"}
    if(@($receipt.failures).Count){throw "M7_HOME_JAVA_FAILURES：$($spec.row)/$Phase $(@($receipt.failures) -join ' | ')"}
}
# 正式 checkpoint 呼叫：relevant 集合只含 B、等於 homeChunks，而且涵蓋 B 門 25 格所在的每一個 chunk（setup.doorChunks 由 Java 以幾何獨立計算，不經 production，B1 審查 M2）。
function Assert-M7Checkpoint($call,$setup,[bool]$ReceiptPresent) {
    $worlds=@($call.worlds)
    if($worlds.Count -ne 1 -or $worlds[0].world -cne $setup.entryWorld -or (@($worlds[0].chunks) -join '|') -cne (@($setup.homeChunks) -join '|') -or $call.activeClose -ne $true -or $call.bDoor -cne 'OPEN/NONE' -or $call.receiptPresent -ne $ReceiptPresent){throw 'M7_HOME_CHECKPOINT'}
    $door=@($setup.doorChunks)
    if($door.Count -eq 0 -or @($door | Where-Object {$_ -cnotin @($worlds[0].chunks)}).Count){throw 'M7_HOME_CHECKPOINT_DOOR_CHUNKS'}
}
# crash：halt 乾淨、事件序列恰為 cut 之前的前綴、crash 當下的磁碟／記憶體狀態依預期終局表。
function Assert-M7Crash($receipt,$spec,[string]$Nonce,[string]$StartupNonce) {
    Assert-M7Identity $receipt $spec 'crash' $Nonce $StartupNonce
    if($receipt.status -cne 'HALT' -or $receipt.ending -cne 'HALT' -or $receipt.hit -ne 1 -or @($receipt.refused).Count){throw "M7_HOME_HALT_NOT_CLEAN：$($spec.row)"}
    $names=@($receipt.events | ForEach-Object event)
    if(($names -join ',') -cne ((Get-M7CrashEvents $spec) -join ',')){throw "M7_HOME_CRASH_EVENTS：$($spec.row) $($names -join ',')"}
    $door=@($receipt.events | Where-Object event -CEQ 'after-door' | ForEach-Object detail)
    if($spec.index -ge $cuts.IndexOf('after-door') -and ($door -join ',') -cne 'APPLIED'){throw "M7_HOME_CRASH_DOOR_OUTCOME：$($spec.row)"}
    $b=$receipt.baseline;$h=$receipt.atHalt;$setup=$receipt.setup
    if($b.bDoorInitial -cne $initialDoor[$spec.door] -or $b.registryEnabled -ne $false -or $b.busy -ne $true -or @($b.receipts).Count -ne 0){throw "M7_HOME_BASELINE：$($spec.row)"}
    $closeKind=if($spec.index -eq 0){'NONE'}elseif($spec.index -eq 12){'ABSENT'}else{'CLOSE'}
    $flushedKind=if($h.flushed.present){$h.flushed.close.kind}else{'ABSENT'}
    if($flushedKind -cne $closeKind -or $h.current.present -ne $h.flushed.present -or $h.pending -or $h.rollForwardPending -or $h.quarantined){throw "M7_HOME_CRASH_JOURNAL：$($spec.row) $flushedKind"}
    if($closeKind -ceq 'CLOSE' -and ($h.flushed.close.reason -cne 'ROOT_DISABLED' -or $h.flushed.close.doorGoal -cne 'OPEN' -or $h.flushed.stopReason -cne 'ROOT_DISABLED')){throw "M7_HOME_CRASH_CLOSE：$($spec.row)"}
    $dat="$($setup.sid).dat";$names=Get-M7Names $h.receipts
    $kinds=@(foreach($name in $names){if($name -ceq $dat){'dat'}elseif($name -cmatch '^publish-.*\.tmp$'){'tmp'}else{"other:$name"}})
    $expectedKinds=@(if($spec.index -lt 6){}elseif($spec.index -lt 8){'tmp'}elseif($spec.index -lt 10){'dat','tmp'}else{'dat'})
    if(($kinds -join ',') -cne ($expectedKinds -join ',')){throw "M7_HOME_CRASH_RECEIPTS：$($spec.row) $($kinds -join ',')"}
    if($expectedKinds.Count -eq 2 -and @($h.receipts)[0].sha256 -cne @($h.receipts)[1].sha256){throw "M7_HOME_CRASH_RECEIPT_LINK：$($spec.row)"}
    if([bool]$h.receipt.present -ne ($spec.index -ge 8)){throw "M7_HOME_CRASH_RECEIPT_LOOKUP：$($spec.row)"}
    $bDoor=if($spec.index -ge $cuts.IndexOf('after-door')){'OPEN/NONE'}else{$initialDoor[$spec.door]}
    if($h.bDoor -cne $bDoor -or $h.aDoor -cne $b.aDoor -or -not (Test-M7Json $h.pose $b.pose) -or $h.busy -ne ($spec.index -ne 12)){throw "M7_HOME_CRASH_STATE：$($spec.row) bDoor=$($h.bDoor)"}
    $calls=@($receipt.checkpoints)
    if($calls.Count -ne $(if($spec.index -ge $cuts.IndexOf('before-checkpoint')){1}else{0})){throw "M7_HOME_CRASH_CHECKPOINTS：$($spec.row)"}
    foreach($call in $calls){Assert-M7Checkpoint $call $setup $false}
}
# reopen：從磁碟續做的事件序列、收據與檔案、兩側門格、資源、零移動與「移除後不修舊門」依預期終局表。
function Assert-M7Reopen($receipt,$spec,$crash,[string]$Nonce,[string]$StartupNonce) {
    Assert-M7Identity $receipt $spec 'reopen' $Nonce $StartupNonce
    if($receipt.status -cne 'PASS' -or $receipt.ending -cne 'STOP' -or $receipt.stopRequested -ne $true -or $receipt.stoppingSeen -ne $true -or $receipt.stoppedSeen -ne $true){throw "M7_HOME_REOPEN_FAILED：$($spec.row)"}
    if(-not (Test-M7Json $receipt.setup $crash.setup)){throw "M7_HOME_REOPEN_SETUP：$($spec.row)"}
    $group=Get-M7Group $spec
    $names=@($receipt.events | ForEach-Object event)
    if(($names -join ',') -cne ((Get-M7ReopenEvents $spec) -join ',')){throw "M7_HOME_REOPEN_EVENTS：$($spec.row) $($names -join ',')"}
    $door=@($receipt.events | Where-Object event -CEQ 'after-door' | ForEach-Object detail)
    $outcome=if($group -ceq 'D'){''}elseif($spec.index -ge $cuts.IndexOf('after-checkpoint')){'UNCHANGED'}else{'APPLIED'}
    if(($door -join ',') -cne $outcome){throw "M7_HOME_REOPEN_DOOR_OUTCOME：$($spec.row) $($door -join ',')"}
    if($receipt.startFiles.journalSha256 -cne $crash.atHalt.journalSha256 -or -not (Test-M7Json @($receipt.startFiles.receipts) @($crash.atHalt.receipts))){throw "M7_HOME_REOPEN_START_FILES：$($spec.row)"}
    $diskDoor=if($spec.index -ge $cuts.IndexOf('after-checkpoint')){'OPEN/NONE'}else{$initialDoor[$spec.door]}
    $s=$receipt.start
    if($s.bDoor -cne $diskDoor -or $s.aDoor -cne $crash.baseline.aDoor -or $s.busy -ne ($group -cne 'D') -or -not (Test-M7Json $s.pose $crash.baseline.pose)){throw "M7_HOME_REOPEN_START：$($spec.row) bDoor=$($s.bDoor)"}
    $e=$receipt.end
    if($e.current.present -or $e.flushed.present -or $e.pending -or $e.rollForwardPending -or $e.quarantined -or $e.journalSessions -ne 0 -or $e.bDoor -cne 'OPEN/NONE' -or $e.aDoor -cne $crash.baseline.aDoor -or $e.busy -or $e.rootTicketLease -or -not (Test-M7Json $e.pose $crash.baseline.pose)){throw "M7_HOME_REOPEN_END：$($spec.row)"}
    $close=$e.receipt.close
    if(-not $e.receipt.present -or $close.kind -cne 'CLOSE' -or $close.reason -cne 'ROOT_DISABLED' -or $close.doorGoal -cne 'OPEN' -or $close.evidence -cne "RootStop[reason=ROOT_DISABLED, observedAtGameTime=$($crash.baseline.stoppedAt)]" -or $e.receipt.session -cne $crash.baseline.stoppedRow){throw "M7_HOME_REOPEN_RECEIPT：$($spec.row)"}
    switch($group){
        'A' {if([long]$close.closedAt -lt [long]$s.gameTime){throw "M7_HOME_REOPEN_CLOSE_ORIGIN：$($spec.row)"}}
        'D' {if(-not (Test-M7Json $close $crash.atHalt.receipt.close)){throw "M7_HOME_REOPEN_CLOSE_ORIGIN：$($spec.row)"}}
        default {if(-not (Test-M7Json $close $crash.atHalt.flushed.close)){throw "M7_HOME_REOPEN_CLOSE_ORIGIN：$($spec.row)"}}
    }
    $dat="$($crash.setup.sid).dat";$end=@($e.receipts);$atCrash=@($crash.atHalt.receipts)
    $filesOk=switch($group){
        'A' {(Get-M7Names $end) -join ',' -ceq $dat}
        'B' {
            if($atCrash.Count -eq 0){(Get-M7Names $end) -join ',' -ceq $dat}
            else{$end.Count -eq 2 -and $atCrash.Count -eq 1 -and (Test-M7Json @($end | Where-Object name -CNE $dat) @($atCrash)) -and @($end | Where-Object name -CEQ $dat)[0].sha256 -ceq $atCrash[0].sha256}
        }
        default {Test-M7Json $end $atCrash}
    }
    if(-not $filesOk){throw "M7_HOME_REOPEN_RECEIPT_FILES：$($spec.row)"}
    $calls=@($receipt.checkpoints)
    if($calls.Count -ne $(if($group -ceq 'D'){0}else{1})){throw "M7_HOME_REOPEN_CHECKPOINTS：$($spec.row)"}
    foreach($call in $calls){Assert-M7Checkpoint $call $crash.setup ($group -ceq 'C')}
    if($receipt.quiet.bDoor -cne 'CLOSED/NONE' -or $receipt.quiet.ticks -ne 40){throw "M7_HOME_REOPEN_QUIET：$($spec.row)"}
    foreach($key in @('journalBytesFromCrash','receiptsFromCrash','journalRowFromCrash','bDoorDiskState','participantLoginPose','activeRemoved','reopenEvents','reopenCheckpoint','receiptClose','receiptCloseOrigin','receiptSession','receiptFiles','resourcesReleased','noRepairAfterRemoval')){
        if($receipt.observed.PSObject.Properties.Name -cnotcontains $key -or $receipt.observed.$key -ne $true){throw "M7_HOME_REOPEN_OBSERVED：$($spec.row) $key"}
    }
}
function Assert-M7Observed($receipt,$spec,[string]$Phase,[string[]]$Keys,[string]$Code) {
    foreach($key in $Keys){
        if($receipt.observed.PSObject.Properties.Name -cnotcontains $key -or $receipt.observed.$key -ne $true){throw "$($Code)：$($spec.row) $key"}
    }
}
function Get-M7Events($receipt,[string]$Name) { return @(@($receipt.events) | Where-Object event -CEQ $Name) }
# B2a 其餘鏈的 crash：halt 乾淨、事件序列恰為預定、各列 crash 當下的磁碟／記憶體狀態依 t4b2a-report.md 的預期終局表。
function Assert-M7ExtraCrash($receipt,$spec,[string]$Nonce,[string]$StartupNonce) {
    Assert-M7Identity $receipt $spec 'crash' $Nonce $StartupNonce
    if($receipt.status -cne 'HALT' -or $receipt.ending -cne 'HALT' -or $receipt.hit -ne 1 -or @($receipt.refused).Count){throw "M7_HOME_HALT_NOT_CLEAN：$($spec.row)"}
    $names=@($receipt.events | ForEach-Object event)
    if(($names -join ',') -cne ((Get-M7CrashEvents $spec) -join ',')){throw "M7_HOME_CRASH_EVENTS：$($spec.row) $($names -join ',')"}
    Assert-M7Observed $receipt $spec 'crash' $spec.crashObserved 'M7_HOME_CRASH_OBSERVED'
    $b=$receipt.baseline;$h=$receipt.atHalt;$setup=$receipt.setup
    if($setup.chain -cne $spec.row -or $b.bDoor -cne $initialDoor[$spec.door] -or @($b.receipts).Count -ne 0 -or $b.busy -ne $true){throw "M7_HOME_BASELINE：$($spec.row)"}
    $dat="$($setup.sid).dat"
    $ok=switch($spec.row){
        'restart-enable-before-entry' {
            $h.flushed.present -and $h.flushed.stopReason -ceq 'NONE' -and $h.flushed.close.kind -ceq 'NONE' -and $h.current.text -ceq $h.flushed.text -and $h.flushed.text -ceq $b.row -and
            $h.queued -eq $true -and $h.registryEnabled -eq $false -and $b.registryEnabled -eq $true -and $h.journalSha256 -ceq $b.journalSha256 -and $h.registrySha256 -cne $b.registrySha256 -and
            @($h.receipts).Count -eq 0 -and $h.busy -eq $true -and $h.bDoor -ceq 'CLOSED/NONE'
        }
        'restart-ready-stop-crossed' {
            $h.flushed.present -and $h.flushed.state -ceq 'PASSAGE_READY' -and $h.flushed.stopReason -ceq 'ROOT_DISABLED' -and $h.flushed.close.kind -ceq 'NONE' -and $h.flushed.text -ceq $b.row -and
            $h.queued -eq $false -and $h.registryEnabled -eq $true -and @($b.crossed).Count -eq 1 -and @($b.crossed)[0] -ceq $setup.crossedOffline -and @($h.receipts).Count -eq 0 -and $h.busy -eq $true -and $h.bDoor -ceq 'OPEN/PASSABLE_X'
        }
        'restart-ready-low-no-root' {
            $h.flushed.present -and $h.flushed.state -ceq 'PASSAGE_READY' -and $h.flushed.stopReason -ceq 'NONE' -and $h.flushed.text -ceq $b.row -and $h.queued -eq $false -and
            $h.registryEnabled -eq $true -and $h.registryPowerState -ceq 'RETURNING' -and @($b.crossed).Count -eq 1 -and @($h.receipts).Count -eq 0 -and $h.busy -eq $true -and $h.bDoor -ceq 'OPEN/PASSABLE_X'
        }
        'receipt-existing' {
            $h.flushed.present -and $h.current.present -and $h.current.text -ceq $h.flushed.text -and $h.flushed.close.kind -ceq 'CLOSE' -and $h.flushed.close.reason -ceq 'ROOT_DISABLED' -and
            -not $h.pending -and -not $h.rollForwardPending -and -not $h.quarantined -and ((Get-M7Names $h.receipts) -join ',') -ceq $dat -and $h.receipt.present -eq $true -and
            $h.bDoor -ceq 'OPEN/NONE' -and $h.busy -eq $true -and ((@(Get-M7Events $receipt 'after-door') | ForEach-Object detail) -join ',') -ceq 'APPLIED' -and
            ((@(Get-M7Events $receipt 'remove-rejected') | ForEach-Object detail) -join ',') -ceq 'RESTORED'
        }
        'receipt-player-closed' {
            -not $h.flushed.present -and -not $h.current.present -and $h.journalSessions -eq 0 -and ((Get-M7Names $h.receipts) -join ',') -ceq $dat -and $h.receipt.present -eq $true -and
            $h.bDoor -ceq 'CLOSED/NONE' -and $h.busy -eq $false
        }
        'receipt-mismatch-stop' {
            # 記憶體欄位是停服前的快照；journal／registry／收據清單是停服、還原收據之後的檔案。停服時收據仍是竄改後那份、管理員 ERROR 恰一則；之後還原成原本那份。
            $st=$receipt.stop
            $h.flushed.present -and $h.current.present -and $h.current.text -ceq $h.flushed.text -and $h.flushed.close.kind -ceq 'CLOSE' -and $h.flushed.close.reason -ceq 'ROOT_DISABLED' -and
            -not $h.pending -and -not $h.rollForwardPending -and -not $h.quarantined -and ((Get-M7Names $h.receipts) -join ',') -ceq $dat -and $h.receipt.present -eq $true -and
            $h.bDoor -ceq 'OPEN/NONE' -and $h.busy -eq $true -and ((@(Get-M7Events $receipt 'after-door') | ForEach-Object detail) -join ',') -ceq 'APPLIED' -and
            ((@(Get-M7Events $receipt 'remove-rejected') | ForEach-Object detail) -join ',') -ceq 'RESTORED' -and $receipt.stoppingSeen -eq $true -and $receipt.stoppedSeen -eq $true -and
            $st.tamperedReceiptSha256 -cne $st.originalReceiptSha256 -and $st.receiptAfterStopSha256 -ceq $st.tamperedReceiptSha256 -and @($h.receipts)[0].sha256 -ceq $st.originalReceiptSha256 -and
            $st.adminErrors -eq 1 -and @($st.adminErrorEntries).Count -eq 1 -and @($st.adminErrorEntries)[0].StartsWith('QUANTUMCHAMBER_COLLAPSE|',[StringComparison]::Ordinal)
        }
        'receipt-write-fail-stop' {
            # 記憶體欄位是停服前的快照（autosave 的寫出已失敗、保留 pending）；journal／registry／收據清單是停服、解除故障之後的檔案。停服時的存檔重試寫出又失敗：
            # journal 檔與停服前相同、收據不變、原生存檔的 ERROR（TICK marker）恰一則、這個 SID 沒有收據 ERROR。
            $st=$receipt.stop
            $h.flushed.present -and $h.current.present -and $h.current.text -ceq $h.flushed.text -and $h.flushed.close.kind -ceq 'CLOSE' -and $h.flushed.close.reason -ceq 'ROOT_DISABLED' -and
            $h.pending -eq $true -and -not $h.rollForwardPending -and -not $h.quarantined -and ((Get-M7Names $h.receipts) -join ',') -ceq $dat -and $h.receipt.present -eq $true -and
            $h.bDoor -ceq 'OPEN/NONE' -and $h.busy -eq $true -and ((@(Get-M7Events $receipt 'after-door') | ForEach-Object detail) -join ',') -ceq 'APPLIED' -and
            ((@(Get-M7Events $receipt 'remove-rejected') | ForEach-Object detail) -join ',') -ceq 'RESTORED' -and $receipt.stoppingSeen -eq $true -and $receipt.stoppedSeen -eq $true -and
            $st.autosaveThrown -ceq 'NONE' -and $st.faultHits -eq 2 -and $st.journalBeforeSha256 -ceq $st.journalAfterStopSha256 -and $h.journalSha256 -ceq $st.journalBeforeSha256 -and
            $st.originalReceiptSha256 -ceq $st.receiptAfterStopSha256 -and @($h.receipts)[0].sha256 -ceq $st.originalReceiptSha256 -and $st.sessionErrors -eq 0 -and
            $st.nativeErrors -eq 1 -and @($st.nativeErrorEntries).Count -eq 1 -and @($st.nativeErrorEntries)[0].StartsWith('QUANTUMCHAMBER_COLLAPSE_TICK|',[StringComparison]::Ordinal)
        }
        default {$false}
    }
    if(-not $ok){throw "M7_HOME_CRASH_STATE：$($spec.row)"}
    if(-not (Test-M7Json $h.pose $b.pose)){throw "M7_HOME_CRASH_POSE：$($spec.row)"}
    $calls=@($receipt.checkpoints)
    if($calls.Count -ne $(if($spec.family -ceq 'receipt'){1}else{0})){throw "M7_HOME_CRASH_CHECKPOINTS：$($spec.row)"}
    foreach($call in $calls){Assert-M7Checkpoint $call $setup $false}
}
# B2a 其餘鏈的 reopen：開服掃描前後、從磁碟續做的事件序列、收據與檔案、兩側門格、資源、零移動與收尾後玩家關門不被舊旅程打開。
function Assert-M7ExtraReopen($receipt,$spec,$crash,[string]$Nonce,[string]$StartupNonce) {
    Assert-M7Identity $receipt $spec 'reopen' $Nonce $StartupNonce
    if($receipt.status -cne 'PASS' -or $receipt.ending -cne 'STOP' -or $receipt.stopRequested -ne $true -or $receipt.stoppingSeen -ne $true -or $receipt.stoppedSeen -ne $true){throw "M7_HOME_REOPEN_FAILED：$($spec.row)"}
    if(-not (Test-M7Json $receipt.setup $crash.setup)){throw "M7_HOME_REOPEN_SETUP：$($spec.row)"}
    $names=@($receipt.events | ForEach-Object event)
    if(($names -join ',') -cne ((Get-M7ReopenEvents $spec) -join ',')){throw "M7_HOME_REOPEN_EVENTS：$($spec.row) $($names -join ',')"}
    if(((@(Get-M7Events $receipt 'after-door') | ForEach-Object detail) -join ',') -cne (@($spec.outcome) -join ',')){throw "M7_HOME_REOPEN_DOOR_OUTCOME：$($spec.row)"}
    $sf=$receipt.startFiles
    if($sf.journalSha256 -cne $crash.atHalt.journalSha256 -or $sf.registrySha256 -cne $crash.atHalt.registrySha256 -or -not (Test-M7Json @($sf.receipts) @($crash.atHalt.receipts))){throw "M7_HOME_REOPEN_START_FILES：$($spec.row)"}
    $e0=$receipt.early;$s=$receipt.start;$e=$receipt.end;$b=$crash.baseline
    $existing=$spec.row -cin @('receipt-existing','receipt-mismatch-stop','receipt-write-fail-stop')
    $diskDoor=if($existing){'OPEN/NONE'}else{$initialDoor[$spec.door]}
    if($s.bDoor -cne $diskDoor -or $s.busy -ne ($spec.row -cne 'receipt-player-closed') -or -not (Test-M7Json $s.pose $b.pose)){throw "M7_HOME_REOPEN_START：$($spec.row) bDoor=$($s.bDoor)"}
    $earlyOk=switch($spec.row){
        'restart-enable-before-entry' {$e0.stopped -eq $false -and $e0.registryEnabled -eq $false -and $e0.enableBeforeScan -eq $false -and $e0.registryEnabledAfterEnable -eq $false -and $e0.rowAfterEnable -ceq $b.row}
        'restart-ready-low-no-root' {$e0.stopped -eq $false -and $e0.aLoaded -eq $false -and $e0.registryEnabled -eq $true}
        'restart-ready-stop-crossed' {$e0.stopped -eq $true -and $e0.registryEnabled -eq $true}
        default {$true}
    }
    if(-not $earlyOk){throw "M7_HOME_REOPEN_EARLY：$($spec.row)"}
    $expectedDoor=if($spec.row -ceq 'receipt-player-closed'){'CLOSED/NONE'}else{'OPEN/NONE'}
    $repaired=$spec.family -ceq 'restart'
    if($e.current.present -or $e.flushed.present -or $e.pending -or $e.rollForwardPending -or $e.quarantined -or $e.journalSessions -ne 0 -or $e.bDoor -cne $expectedDoor -or $e.busy -or $e.rootTicketLease -or
        $e.registryEnabled -ne $repaired -or -not (Test-M7Json $e.pose $b.pose)){throw "M7_HOME_REOPEN_END：$($spec.row)"}
    $close=$e.receipt.close
    $reason=if($spec.row -ceq 'restart-ready-low-no-root'){'RESTART_CLOSED'}else{'ROOT_DISABLED'}
    if(-not $e.receipt.present -or $close.kind -cne 'CLOSE' -or $close.reason -cne $reason -or $close.doorGoal -cne 'OPEN'){throw "M7_HOME_REOPEN_RECEIPT：$($spec.row)"}
    $originOk=switch($spec.row){
        'restart-enable-before-entry' {$close.evidence -ceq "RootStop[reason=ROOT_DISABLED, observedAtGameTime=$($e0.gameTime)]" -and [long]$close.closedAt -ge [long]$e0.gameTime}
        'restart-ready-stop-crossed' {$close.evidence -ceq "RootStop[reason=ROOT_DISABLED, observedAtGameTime=$($b.stoppedAt)]" -and [long]$close.closedAt -ge [long]$e0.gameTime}
        'restart-ready-low-no-root' {$close.evidence -ceq "RestartClosed[startupInstanceUuid=$($receipt.startupInstance), observedAtGameTime=$($close.closedAt)]" -and [long]$close.closedAt -ge [long]$e0.gameTime}
        'receipt-existing' {Test-M7Json $close $crash.atHalt.flushed.close}
        'receipt-mismatch-stop' {Test-M7Json $close $crash.atHalt.flushed.close}
        'receipt-write-fail-stop' {Test-M7Json $close $crash.atHalt.flushed.close}
        'receipt-player-closed' {Test-M7Json $close $crash.atHalt.receipt.close}
        default {$false}
    }
    if(-not $originOk){throw "M7_HOME_REOPEN_CLOSE_ORIGIN：$($spec.row)"}
    $dat="$($crash.setup.sid).dat"
    $filesOk=if($spec.family -ceq 'restart'){((Get-M7Names $e.receipts) -join ',') -ceq $dat}else{Test-M7Json @($e.receipts) @($crash.atHalt.receipts)}
    if(-not $filesOk){throw "M7_HOME_REOPEN_RECEIPT_FILES：$($spec.row)"}
    $calls=@($receipt.checkpoints)
    if($calls.Count -ne $(if($spec.row -ceq 'receipt-player-closed'){0}else{1})){throw "M7_HOME_REOPEN_CHECKPOINTS：$($spec.row)"}
    foreach($call in $calls){Assert-M7Checkpoint $call $crash.setup $existing}
    if(@(Get-M7Events $receipt 'before-remove' | Where-Object {$_.busy -ne $true}).Count -or @(Get-M7Events $receipt 'after-remove' | Where-Object {$_.busy -ne $false}).Count){throw "M7_HOME_REOPEN_UNLOCK：$($spec.row)"}
    if($spec.row -ceq 'restart-ready-low-no-root' -and (@(@($receipt.events) | Where-Object {$_.aLoaded -ne $false}).Count -or $e0.aLoaded -ne $false)){throw "M7_HOME_REOPEN_A_LOADED：$($spec.row)"}
    if($receipt.quiet.bDoor -cne 'CLOSED/NONE' -or $receipt.quiet.ticks -ne 40){throw "M7_HOME_REOPEN_QUIET：$($spec.row)"}
    Assert-M7Observed $receipt $spec 'reopen' $spec.reopenObserved 'M7_HOME_REOPEN_OBSERVED'
}
function Assert-M7PhaseReceipt($receipt,$spec,[string]$Phase,$crash,[string]$Nonce,[string]$StartupNonce) {
    $close=$spec.family -ceq 'close'
    if($Phase -ceq 'crash'){if($close){Assert-M7Crash $receipt $spec $Nonce $StartupNonce}else{Assert-M7ExtraCrash $receipt $spec $Nonce $StartupNonce}}
    else{if($close){Assert-M7Reopen $receipt $spec $crash $Nonce $StartupNonce}else{Assert-M7ExtraReopen $receipt $spec $crash $Nonce $StartupNonce}}
}
function Read-M7Receipt([string]$Path) { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -DateKind String }
# 已存 summary 的一列：phase 名稱、退出碼、JVM 身分、receipt hash 與內容逐一重驗（full 與 SelfTest 共用）。
function Assert-M7SavedRow($summary,[int]$Index,$receiptOverrides=@{}) {
    $rowEntry=@($summary.rows)[$Index];$spec=Get-M7Spec $rowEntry.row
    if($rowEntry.door -cne $spec.door -or $rowEntry.cut -cne $spec.cut -or $rowEntry.group -cne (Get-M7Group $spec)){throw "M7_HOME_SAVED_ROW：$($rowEntry.row)"}
    $phases=@($rowEntry.phases)
    if($phases.Count -ne 2 -or $phases[0].phase -cne 'crash' -or $phases[1].phase -cne 'reopen'){throw "M7_HOME_SAVED_PHASES：$($rowEntry.row)"}
    if((Get-FileHash -LiteralPath $rowEntry.preservation).Hash -cne $rowEntry.preservationSha256){throw "M7_HOME_SAVED_PRESERVATION_HASH：$($rowEntry.row)"}
    $preserve=Read-M7Receipt $rowEntry.preservation
    if($preserve.root -cne $rowEntry.root -or $preserve.strategy -cne 'NEW_OWNED_ROOT' -or $preserve.existingWorldsMoved -ne 0 -or $preserve.retainedRoot -ne $true -or $preserve.lockReleased -ne $true){throw "M7_HOME_SAVED_PRESERVATION：$($rowEntry.row)"}
    $receipts=@()
    for($p=0;$p -lt 2;$p++){
        $phase=$phases[$p];$key="$Index/$($phase.phase)"
        if((Get-FileHash -LiteralPath $phase.receipt).Hash -cne $phase.receiptSha256){throw "M7_HOME_SAVED_RECEIPT_HASH：$($rowEntry.row)/$($phase.phase)"}
        $receipt=if($receiptOverrides.ContainsKey($key)){$receiptOverrides[$key]}else{Read-M7Receipt $phase.receipt}
        if($phase.row -cne $rowEntry.row -or $phase.nonce -cne $rowEntry.nonce -or $phase.root -cne $rowEntry.root -or $phase.lockReleased -ne $true -or $receipt.pid -ne $phase.javaPid -or $receipt.root -cne $rowEntry.root){throw "M7_HOME_SAVED_IDENTITY：$($rowEntry.row)/$($phase.phase)"}
        if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-[DateTimeOffset]::Parse($phase.javaStartTimeUtc)).TotalSeconds) -gt 1){throw "M7_HOME_SAVED_JVM_START：$($rowEntry.row)/$($phase.phase)"}
        $crashPhase=$phase.phase -ceq 'crash'
        $receiptName=if($crashPhase){'halt.json'}else{'final.json'}
        if([IO.Path]::GetFileName($phase.receipt) -cne $receiptName -or $phase.normalStop -eq $crashPhase -or $phase.javaExitCode -ne $(if($crashPhase){77}else{0}) -or ($crashPhase -and $phase.gradleExitCode -eq 0) -or (-not $crashPhase -and $phase.gradleExitCode -ne 0)){throw "M7_HOME_SAVED_EXIT：$($rowEntry.row)/$($phase.phase)"}
        Assert-M7PhaseReceipt $receipt $spec $phase.phase $(if($crashPhase){$null}else{$receipts[0]}) $rowEntry.nonce $phase.startupNonce
        $receipts+=,$receipt
    }
    if("$($phases[0].javaPid)@$($phases[0].javaStartTimeUtc)" -ceq "$($phases[1].javaPid)@$($phases[1].javaStartTimeUtc)" -or $phases[0].startupNonce -ceq $phases[1].startupNonce){throw "M7_HOME_SAVED_JVM_REUSED：$($rowEntry.row)"}
}
function Assert-M7SavedGlobal($summary) {
    if($summary.status -cne 'PASS' -or $summary.schema -ne 1 -or $summary.head -cne $head -or (@($summary.rowTable) -join ',') -cne ($manifest.row -join ',')){throw 'M7_HOME_SAVED_SUMMARY'}
    # T4 B2b-2（B1-M4）：帶著未提交改動跑出的 PASS 不能歸到該 HEAD。
    if($summary.treeClean -ne $true){throw 'M7_HOME_SAVED_TREE_DIRTY：summary 不是在乾淨工作樹跑出（treeClean 不是 true）'}
    $expected=@(switch -CaseSensitive ($summary.selection.mode){
        'all' {@($manifest.row)}
        'doorState' {if($summary.selection.doorState -cnotin $doors){throw 'M7_HOME_SAVED_SELECTION'};@($manifest | Where-Object {$_.family -ceq 'close' -and $_.door -ceq $summary.selection.doorState} | ForEach-Object row)}
        'family' {if($summary.selection.family -cnotin @('close','restart','receipt')){throw 'M7_HOME_SAVED_SELECTION'};@($manifest | Where-Object family -CEQ $summary.selection.family | ForEach-Object row)}
        'only' {@($manifest | Where-Object {$_.row -cin @($summary.selection.only)} | ForEach-Object row)}
        default {throw 'M7_HOME_SAVED_SELECTION'}
    })
    if($expected.Count -eq 0 -or (@($summary.selectedRows) -join ',') -cne ($expected -join ',')){throw 'M7_HOME_SAVED_SELECTION'}
    $rows=@($summary.rows)
    if($rows.Count -ne $expected.Count -or (@($rows | ForEach-Object row) -join ',') -cne ($expected -join ',') -or $summary.rowCount -ne $expected.Count -or $summary.phaseCount -ne 2*$expected.Count){throw 'M7_HOME_SAVED_ROWS'}
    $allPhases=@($rows | ForEach-Object {@($_.phases)})
    if(@($allPhases | ForEach-Object {"$($_.javaPid)@$($_.javaStartTimeUtc)"} | Sort-Object -Unique).Count -ne $allPhases.Count -or @($allPhases | ForEach-Object startupNonce | Sort-Object -Unique).Count -ne $allPhases.Count -or @($rows | ForEach-Object root | Sort-Object -Unique).Count -ne $rows.Count){throw 'M7_HOME_SAVED_OWNER_REUSED'}
}
function Assert-M7SavedSummary($summary) {
    Assert-M7SavedGlobal $summary
    for($i=0;$i -lt @($summary.rows).Count;$i++){Assert-M7SavedRow $summary $i}
}
# T4 B2b-2（B1-M4、B2a-M5）：單份 summary 只有「選擇 all、列恰為 windows.native 全部列、treeClean=true」才是 T4 完整 PASS；其他都只是分片 PASS。
# 先經 Assert-M7SavedSummary 驗過內容，這裡只判範圍。
function Test-M7Complete($summary) {
    return $summary.selection.mode -ceq 'all' -and $summary.treeClean -eq $true -and (@(@($summary.rows) | ForEach-Object row) -join ',') -ceq (@($inventory.windows.native) -join ',')
}
function Assert-M7Complete($summary) {
    if(-not (Test-M7Complete $summary)){throw "M7_HOME_NOT_COMPLETE：selection=$($summary.selection.mode) rows=$(@($summary.rows).Count)／$(@($inventory.windows.native).Count)（分片 PASS 不是 T4 完整 PASS；分片請以 -VerifyAggregate 彙總）"}
}
# 多份分片的彙總：只接受 -DoorState 與 -Family 分片；成員全部 treeClean、同一個 HEAD、各自通過單份驗證；列的聯集恰為 windows.native、沒有重疊。
# 在這些條件下合法組合只有兩種（下表）；最後仍明確比對，防止日後加了新的分片種類卻漏改這裡。
$aggregateCombinations=[ordered]@{
    doorState=@('doorState:ordinary','doorState:sealed','doorState:passable','family:restart','family:receipt')
    family=@('family:close','family:restart','family:receipt')
}
function Get-M7ShardKey($summary) {
    switch -CaseSensitive ($summary.selection.mode){
        'doorState' {return "doorState:$($summary.selection.doorState)"}
        'family' {return "family:$($summary.selection.family)"}
        default {throw "M7_HOME_AGGREGATE_SHARD：彙總只接受 -DoorState 或 -Family 分片，實際 selection=$($summary.selection.mode)"}
    }
}
function Assert-M7Aggregate($members) {
    $members=@($members)
    if($members.Count -lt 2){throw "M7_HOME_AGGREGATE_INPUT：彙總至少要兩份分片 summary，實際 $($members.Count) 份"}
    $keys=@();$rows=@()
    for($i=0;$i -lt $members.Count;$i++){
        $s=$members[$i].summary;$name=$members[$i].path
        if($s.treeClean -ne $true){throw "M7_HOME_AGGREGATE_TREE：第 $i 份（$name）不是乾淨工作樹跑出"}
        if($s.head -cne $head){throw "M7_HOME_AGGREGATE_HEAD：第 $i 份（$name）head=$($s.head)，預期 $head"}
        $keys+=Get-M7ShardKey $s
        try {Assert-M7SavedSummary $s | Out-Null} catch {throw "M7_HOME_AGGREGATE_MEMBER：第 $i 份（$name）：$($_.Exception.Message)"}
        $rows+=@(@($s.rows) | ForEach-Object row)
    }
    if(@($keys | Sort-Object -Unique -CaseSensitive).Count -ne $keys.Count){throw "M7_HOME_AGGREGATE_OVERLAP：分片重複：$($keys -join ',')"}
    $duplicated=@($rows | Group-Object -CaseSensitive | Where-Object Count -gt 1 | ForEach-Object Name)
    if($duplicated.Count){throw "M7_HOME_AGGREGATE_OVERLAP：列重疊：$($duplicated -join ',')"}
    $expected=@($inventory.windows.native)
    $missing=@($expected | Where-Object {$_ -cnotin $rows});$extra=@($rows | Where-Object {$_ -cnotin $expected})
    if($missing.Count -or $extra.Count -or $rows.Count -ne $expected.Count){throw "M7_HOME_AGGREGATE_UNION：列的聯集不等於 windows.native（缺 $($missing -join ',')；多 $($extra -join ',')）"}
    $sortedKeys=(@($keys) | Sort-Object -CaseSensitive) -join ','
    $combination=@($aggregateCombinations.Keys | Where-Object {((@($aggregateCombinations[$_]) | Sort-Object -CaseSensitive) -join ',') -ceq $sortedKeys})
    if($combination.Count -ne 1){throw "M7_HOME_AGGREGATE_COMBINATION：$sortedKeys"}
    return $combination[0]
}
function Copy-M7Json($value) { return $value | ConvertTo-Json -Depth 40 | ConvertFrom-Json -DateKind String }
# 負控必須以預期的具名錯因被拒（不是任意例外）。
function Test-M7Rejected([scriptblock]$Check,[string]$Case,[string]$Code) {
    $reason=''
    try {& $Check} catch {$reason=$_.Exception.Message}
    if($reason -cnotmatch ('^'+[regex]::Escape($Code)+'(：|$)')){throw "M7_HOME_SELFTEST_MISSED：$Case 預期 $Code，實際（$reason）"}
}

# 單份 summary 的負控（-VerifySummary 與 -VerifyAggregate 的每一份成員共用）；計數累加到 $script:m7Checks。
function Invoke-M7SummarySelfTest($summary) {
    # 整份 summary 的負控：列缺少、多出、改名、phase 改名、選擇與 HEAD 不符、工作樹不乾淨。
    $global=[ordered]@{
        'row-missing'=@('M7_HOME_SAVED_ROWS',{param($s) $s.rows=@(@($s.rows) | Select-Object -SkipLast 1)})
        'row-extra'=@('M7_HOME_SAVED_ROWS',{param($s) $s.rows=@(@($s.rows)+@(@($s.rows)[0]))})
        'row-renamed'=@('M7_HOME_SAVED_ROWS',{param($s) @($s.rows)[0].row=@($s.rows)[0].row+'-renamed'})
        'row-swapped'=@('M7_HOME_SAVED_ROWS',{param($s) if(@($s.rows).Count -ge 2){$first=@($s.rows)[0].row;@($s.rows)[0].row=@($s.rows)[1].row;@($s.rows)[1].row=$first}else{@($s.rows)[0].row='close-ordinary-renamed'}})
        'phase-renamed'=@('M7_HOME_SAVED_PHASES',{param($s) @(@($s.rows)[0].phases)[0].phase='restart'})
        'selection-mismatch'=@('M7_HOME_SAVED_SELECTION',{param($s) $s.selection=[pscustomobject]@{mode='doorState';doorState=$(if(@($s.rows)[0].door -ceq 'ordinary'){'sealed'}else{'ordinary'});family='';only=@()}})
        'head-mismatch'=@('M7_HOME_SAVED_SUMMARY',{param($s) $s.head='0'*40})
        'tree-dirty'=@('M7_HOME_SAVED_TREE_DIRTY',{param($s) $s.treeClean=$false})
    }
    foreach($case in $global.Keys){$bad=Copy-M7Json $summary;& $global[$case][1] $bad;Test-M7Rejected {Assert-M7SavedSummary $bad} $case $global[$case][0];$script:m7Checks++}
    # 分片冒充完整（T4 B2b-2）：把分片的選擇改成 all 會被選擇核對拒絕；分片本身不能通過完整性判定。
    if(-not (Test-M7Complete $summary)){
        $bad=Copy-M7Json $summary;$bad.selection=[pscustomobject]@{mode='all';doorState='';family='';only=@()}
        Test-M7Rejected {Assert-M7SavedSummary $bad;Assert-M7Complete $bad} 'shard-claims-complete' 'M7_HOME_SAVED_SELECTION';$script:m7Checks++
        Test-M7Rejected {Assert-M7Complete $summary} 'shard-not-complete' 'M7_HOME_NOT_COMPLETE';$script:m7Checks++
    }
    # 逐列：兩個 phase 的退出碼、receipt hash 與 receipt 內容（cut、事件序列、狀態）；只重驗該列，原件不改。
    for($i=0;$i -lt @($summary.rows).Count;$i++){
        foreach($p in 0,1){
            $bad=Copy-M7Json $summary;@(@($bad.rows)[$i].phases)[$p].javaExitCode=$(if($p -eq 0){0}else{77})
            Test-M7Rejected {Assert-M7SavedGlobal $bad;Assert-M7SavedRow $bad $i} "exit-$i-$p" 'M7_HOME_SAVED_EXIT';$script:m7Checks++
            $bad=Copy-M7Json $summary;@(@($bad.rows)[$i].phases)[$p].receiptSha256='0'*64
            Test-M7Rejected {Assert-M7SavedGlobal $bad;Assert-M7SavedRow $bad $i} "hash-$i-$p" 'M7_HOME_SAVED_RECEIPT_HASH';$script:m7Checks++
        }
        $crashReceipt=Read-M7Receipt @(@($summary.rows)[$i].phases)[0].receipt
        $reopenReceipt=Read-M7Receipt @(@($summary.rows)[$i].phases)[1].receipt
        $bad=Copy-M7Json $crashReceipt;$bad.cut=$(if($bad.cut -ceq 'before-close'){'after-close'}else{'before-close'})
        Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/crash"=$bad}} "crash-cut-$i" 'M7_HOME_RECEIPT_IDENTITY';$script:m7Checks++
        $bad=Copy-M7Json $crashReceipt;$bad.events=@(@($bad.events)+@([pscustomobject]@{event='extra-event';serverTick=0;gameTime=0;detail='NONE'}))
        Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/crash"=$bad}} "crash-events-$i" 'M7_HOME_CRASH_EVENTS';$script:m7Checks++
        $bad=Copy-M7Json $reopenReceipt;$bad.status='FAIL'
        Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/reopen"=$bad}} "reopen-status-$i" 'M7_HOME_REOPEN_FAILED';$script:m7Checks++
        $bad=Copy-M7Json $reopenReceipt;$bad.events=@(@($bad.events)+@([pscustomobject]@{event='before-door';serverTick=0;gameTime=0;detail='OPEN/NONE'}))
        Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/reopen"=$bad}} "reopen-events-$i" 'M7_HOME_REOPEN_EVENTS';$script:m7Checks++
    }
    # 負控不改原件：結束再完整重驗一次。
    Assert-M7SavedSummary $summary
}
# 彙總的負控（T4 B2b-2）：缺片、重疊、不同 HEAD、treeClean=false、分片冒充完整（成員自稱 all）、only 成員、只給一份、列從一片搬到另一片（聯集不變但成員不符）。
function Invoke-M7AggregateSelfTest($members) {
    $cases=[ordered]@{
        'aggregate-missing'=@('M7_HOME_AGGREGATE_UNION',{param($m) @($m | Select-Object -SkipLast 1)})
        'aggregate-duplicate'=@('M7_HOME_AGGREGATE_OVERLAP',{param($m) @($m)+@($m[0])})
        'aggregate-head'=@('M7_HOME_AGGREGATE_HEAD',{param($m) $m[-1].summary.head='0'*40;$m})
        'aggregate-tree-dirty'=@('M7_HOME_AGGREGATE_TREE',{param($m) $m[-1].summary.treeClean=$false;$m})
        'aggregate-shard-claims-complete'=@('M7_HOME_AGGREGATE_SHARD',{param($m) $m[0].summary.selection.mode='all';$m})
        'aggregate-only-member'=@('M7_HOME_AGGREGATE_SHARD',{param($m) $m[0].summary.selection.mode='only';$m})
        'aggregate-single'=@('M7_HOME_AGGREGATE_INPUT',{param($m) @($m[0])})
        'aggregate-row-moved'=@('M7_HOME_AGGREGATE_MEMBER',{param($m) $moved=@($m[0].summary.rows)[-1];$m[0].summary.rows=@(@($m[0].summary.rows) | Select-Object -SkipLast 1);$m[1].summary.rows=@(@($m[1].summary.rows)+@($moved));$m})
    }
    foreach($case in $cases.Keys){
        $copy=@(@($members) | ForEach-Object {[pscustomobject]@{path=$_.path;summary=(Copy-M7Json $_.summary)}})
        $bad=@(& $cases[$case][1] $copy)
        Test-M7Rejected {Assert-M7Aggregate $bad | Out-Null} $case $cases[$case][0];$script:m7Checks++
    }
}

$head=(git -c "safe.directory=$project" -C $project rev-parse HEAD).Trim()
if($ExpectedHead){$head=$ExpectedHead}
$selfTestBudget=479
$script:m7Checks=0
if($VerifyAggregate.Count) {
    $members=@(foreach($path in $VerifyAggregate){[pscustomobject]@{path=$path;summary=(Read-M7Receipt $path)}})
    $combination=Assert-M7Aggregate $members
    if($SelfTest) {
        Invoke-M7AggregateSelfTest $members
        foreach($member in $members){Invoke-M7SummarySelfTest $member.summary}
        if($script:m7Checks -gt $selfTestBudget){throw "M7_HOME_SELFTEST_BUDGET：負控 $($script:m7Checks) 個超過上限 $selfTestBudget"}
        # 負控不改原件：結束再完整重驗一次彙總。
        $null=Assert-M7Aggregate $members
    }
    $rowTotal=@($members | ForEach-Object {@($_.summary.rows)}).Count
    Write-Output "M7_HOME_AGGREGATE_COMPLETE PASS combination=$combination summaries=$($members.Count) rows=$rowTotal phases=$(2*$rowTotal) negativeChecks=$($script:m7Checks) HEAD=$head（T4 完整 PASS：分片彙總）"
    return
}
if($VerifySummary) {
    $summary=Read-M7Receipt $VerifySummary
    Assert-M7SavedSummary $summary
    if($RequireComplete){Assert-M7Complete $summary}
    if($SelfTest) {
        Invoke-M7SummarySelfTest $summary
        if($script:m7Checks -gt $selfTestBudget){throw "M7_HOME_SELFTEST_BUDGET：負控 $($script:m7Checks) 個超過上限 $selfTestBudget"}
    }
    $scope=$(switch -CaseSensitive ($summary.selection.mode){'doorState'{"doorState:$($summary.selection.doorState)"};'family'{"family:$($summary.selection.family)"};default{$summary.selection.mode}})
    if(Test-M7Complete $summary){Write-Output "M7_HOME_VERIFIED_COMPLETE PASS rows=$(@($summary.rows).Count) phases=$($summary.phaseCount) negativeChecks=$($script:m7Checks) selection=$scope HEAD=$head（T4 完整 PASS）"}
    else{Write-Output "M7_HOME_VERIFIED_SHARD PASS rows=$(@($summary.rows).Count)／$(@($inventory.windows.native).Count) phases=$($summary.phaseCount) negativeChecks=$($script:m7Checks) selection=$scope HEAD=$head（分片 PASS，不是 T4 完整 PASS；完整請用 -VerifyAggregate 彙總）"}
    return
}

if(-not $IsWindows){throw 'M7_HOME_PLATFORM_UNSUPPORTED：owned Win32_Process 原生鏈僅支援 Windows'}
if(-not $Gradle){$fixed='C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat';$Gradle=if(Test-Path -LiteralPath $fixed){$fixed}else{Join-Path $project 'gradlew.bat'}}
if(-not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "M7_HOME_GRADLE_MISSING：$Gradle"}
$selection=Get-M7Selection
$selected=@($selection.rows | ForEach-Object {Get-M7Spec $_})
$runId=[guid]::NewGuid().ToString('N').Substring(0,12)
# 路徑預算（B1 審查 M7）：Windows MAX_PATH 是 260（含結尾 NUL，可用 259 字元）。root 之下已知最長的子路徑是 B 世界的 entity region
# \world\dimensions\quantumchamber\universe\<36 字元 UUID>\overworld\entities\r.-1.-1.mca，共 110 字元（B1 實測：全長 240＝root 130＋110）；另預留到 120 字元給 region 座標變長與其他檔名。
# 啟動前對每一個選到的列先算 root 長度，超過就以具名錯誤拒絕，不在執行中途才撞到上限。
$pathLimit=259;$pathReserve=120
foreach($spec in $selected){
    $rootLength=(Join-Path (Join-Path $project 'run') "m7-home-$runId-$($spec.row)").Length
    if($rootLength+$pathReserve -gt $pathLimit){throw "M7_HOME_PATH_BUDGET：$($spec.row) 的 root 長度 $rootLength＋預留 $pathReserve 超過 $pathLimit；請改用較短的 checkout 路徑"}
}
$context=[pscustomobject]@{Project=$project;Gradle=$Gradle;InitScript=(Join-Path $PSScriptRoot 'runtime.init.gradle');RunId=$runId;RootPrefix='m7-home';EvidencePrefix='m7-home';EvidenceOwner='';Task='runM7HomeProbe';PropertyPrefix='m7Home';NonceProperty='quantumchamber.m7home.startupNonce';IdleSeconds=$IdleSeconds;PhaseMinutes=$PhaseMinutes;GradleEncoding=[Text.Encoding]::GetEncoding($GradleCodePage);LogEncoding=[Text.Encoding]::UTF8}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $project '.superpowers/verification'}
$EvidenceRoot=Resolve-Evidence -Context $context -Path $EvidenceRoot
$context.EvidenceOwner=Resolve-Evidence -Context $context -Path (Join-Path $project '.superpowers/verification')
$summaryPath=Join-Path $EvidenceRoot "m7-home-run-$runId.json"
if(Test-Path -LiteralPath $summaryPath){throw "M7_HOME_SUMMARY_EXISTS：$summaryPath"}
$live=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -notmatch 'org.gradle.launcher.daemon.bootstrap.GradleDaemon'})
if($live.Count){throw 'M7_HOME_OWNER_BUSY：已有非 daemon Java，禁止併行 gate／probe'}
$treeClean=@(git -c "safe.directory=$project" -C $project status --porcelain --untracked-files=no).Count -eq 0
$rows=[Collections.Generic.List[object]]::new()
$deadline=[DateTimeOffset]::UtcNow.AddMinutes($BlockMinutes)
$runStart=[DateTimeOffset]::UtcNow
$failure=$null

# 一個 phase：owned JVM、退出碼與 receipt、JVM 身分、world/session.lock、log 收集；回傳 summary 的 phase 列。
function Invoke-M7Phase($owned,$spec,[string]$Phase,$crashReceipt) {
    $phaseStart=[DateTimeOffset]::UtcNow
    $state=Invoke-OwnedPhase -Context $context -Nonce $owned.nonce -Phase $Phase -Root $owned.root
    $crashPhase=$Phase -ceq 'crash'
    $finalPath=Join-Path $state.PhaseEvidence 'final.json';$haltPath=Join-Path $state.PhaseEvidence 'halt.json'
    $note=Get-PhaseFailureNote -Context $context -State $state
    if($crashPhase){
        if(Test-Path -LiteralPath $finalPath){throw "M7_HOME_CRASH_STOPPED：預期 halt 的 phase 正常停止（$note）：$($state.PhaseEvidence)"}
        if($state.Launcher.ExitCode -eq 0 -or $state.JavaProcess.ExitCode -ne 77){throw "M7_HOME_CRASH_EXIT：gradle=$($state.Launcher.ExitCode) java=$($state.JavaProcess.ExitCode)（$note）"}
        $gradleOutput=(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stdout.log') -Encoding $context.GradleEncoding)+(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stderr.log') -Encoding $context.GradleEncoding)
        if($gradleOutput -notmatch 'finished with non-zero exit value 77'){throw "M7_HOME_CRASH_GRADLE_EXIT：$($state.PhaseEvidence)"}
        if(-not(Test-Path -LiteralPath $haltPath)){throw "M7_HOME_CRASH_RECEIPT_MISSING：$($state.PhaseEvidence)"}
        $receiptPath=$haltPath;$receipt=Read-M7Receipt $haltPath
        Assert-M7PhaseReceipt $receipt $spec 'crash' $null $owned.nonce $state.StartupNonce
    } else {
        if(Test-Path -LiteralPath $haltPath){throw "M7_HOME_REOPEN_HALTED：$($state.PhaseEvidence)"}
        if($state.Launcher.ExitCode -ne 0 -or $state.JavaProcess.ExitCode -ne 0){throw "M7_HOME_REOPEN_EXIT：gradle=$($state.Launcher.ExitCode) java=$($state.JavaProcess.ExitCode)（$note）"}
        if(-not(Test-Path -LiteralPath $finalPath)){throw "M7_HOME_REOPEN_RECEIPT_MISSING：$($state.PhaseEvidence)"}
        $receiptPath=$finalPath;$receipt=Read-M7Receipt $finalPath
        Assert-M7PhaseReceipt $receipt $spec 'reopen' $crashReceipt $owned.nonce $state.StartupNonce
    }
    if($receipt.pid -ne $state.JavaId -or $receipt.root -cne $owned.root){throw 'M7_HOME_JVM_IDENTITY'}
    if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-$state.JavaStart).TotalSeconds) -gt 1){throw 'M7_HOME_JVM_STARTTIME'}
    if(-not $state.JavaProcess.HasExited){throw 'M7_HOME_JVM_STILL_LIVE'}
    $lock=[IO.File]::Open((Join-Path $owned.root 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$lock.Dispose()
    $logs=Copy-OwnedLogs -State $state
    foreach($key in @('classload','latest')){if(-not $logs[$key].copied){throw "M7_HOME_RUNTIME_LOG_MISSING：$key"}}
    $runtime=Get-SharedText -Path (Join-Path $state.PhaseEvidence 'latest.log') -Encoding ([Text.Encoding]::UTF8)
    if([regex]::Matches($runtime,'M7_HOME_BEGIN row=').Count -ne 1 -or [regex]::Matches($runtime,'M7_HOME_END row=').Count -ne $(if($crashPhase){0}else{1})){throw 'M7_HOME_RUNTIME_LOG_IDENTITY'}
    $seconds=[Math]::Round(([DateTimeOffset]::UtcNow-$phaseStart).TotalSeconds,1)
    $row=[ordered]@{m7ProbeSchema=1;status='PASS';row=$spec.row;phase=$Phase;nonce=$owned.nonce;startupNonce=$state.StartupNonce;root=$owned.root;javaPid=$state.JavaId;javaStartTimeUtc=$state.JavaStart.ToString('o');gradleExitCode=$state.Launcher.ExitCode;javaExitCode=$state.JavaProcess.ExitCode;normalStop=(-not $crashPhase);lockReleased=$true;receipt=$receiptPath;receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash;seconds=$seconds;auxiliaryLogs=$logs}
    $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'launcher.json') -Encoding utf8
    $state.JavaProcess.Dispose();$state.Launcher.Dispose()
    return [pscustomobject]@{Row=$row;Receipt=$receipt}
}

try {
    foreach($spec in $selected){
        if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M7_HOME_BLOCK_BUDGET_EXCEEDED'}
        $rowStart=[DateTimeOffset]::UtcNow
        $owned=New-OwnedRoot -Context $context -Suffix $spec.row
        $crashResult=Invoke-M7Phase $owned $spec 'crash' $null
        Write-Output "M7_HOME_PHASE PASS row=$($spec.row) phase=crash pid=$($crashResult.Row.javaPid) seconds=$($crashResult.Row.seconds) ROOT=$($owned.root)"
        $reopenResult=Invoke-M7Phase $owned $spec 'reopen' $crashResult.Receipt
        Write-Output "M7_HOME_PHASE PASS row=$($spec.row) phase=reopen pid=$($reopenResult.Row.javaPid) seconds=$($reopenResult.Row.seconds) ROOT=$($owned.root)"
        $preservation=Join-Path $owned.root 'preservation.json'
        [ordered]@{strategy='NEW_OWNED_ROOT';existingWorldsMoved=0;existingWorldsChanged=$false;retainedRoot=$true;root=$owned.root;lockReleased=$true} | ConvertTo-Json | Set-Content -LiteralPath $preservation -Encoding utf8
        $rows.Add([ordered]@{row=$spec.row;door=$spec.door;cut=$spec.cut;group=(Get-M7Group $spec);nonce=$owned.nonce;root=$owned.root;preservation=$preservation;preservationSha256=(Get-FileHash -LiteralPath $preservation).Hash;phases=@($crashResult.Row,$reopenResult.Row);seconds=[Math]::Round(([DateTimeOffset]::UtcNow-$rowStart).TotalSeconds,1)})
        Write-Output "M7_HOME_ROW PASS row=$($spec.row) seconds=$($rows[-1].seconds)"
    }
    if((git -c "safe.directory=$project" -C $project rev-parse HEAD).Trim() -cne $head){throw 'M7_HOME_HEAD_CHANGED_DURING_PROBE'}
} catch {$failure=$_.Exception.Message}
$summary=[ordered]@{schema=1;status=$(if($failure){'FAIL'}else{'PASS'});head=$head;treeClean=$treeClean;runId=$runId;selection=[ordered]@{mode=$selection.mode;doorState=$selection.doorState;family=$selection.family;only=@($Only)};rowTable=@($manifest.row);selectedRows=@($selection.rows);rowCount=$rows.Count;phaseCount=2*$rows.Count;idleSeconds=$IdleSeconds;phaseMinutes=$PhaseMinutes;blockMinutes=$BlockMinutes;totalSeconds=[Math]::Round(([DateTimeOffset]::UtcNow-$runStart).TotalSeconds,1);rows=@($rows.ToArray());failure=$failure}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Output "M7_HOME_SUMMARY=$summaryPath"
if($failure){throw $failure}
Write-Output "M7_HOME PASS rows=$($rows.Count) phases=$(2*$rows.Count) selection=$($selection.mode) seconds=$($summary.totalSeconds) HEAD=$head"
