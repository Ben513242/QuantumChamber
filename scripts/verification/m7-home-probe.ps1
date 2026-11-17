# M7 T4 Phase B1：Windows 原生 close 矩陣 close-{ordinary|sealed|passable}-{cut}（39 列）。每列一個全新 owned root（永久保留），
# 兩個 phase 各一個獨立 JVM：crash 在該 cut 預定 halt(77)，reopen 以新 JVM 重開、正常停服 exit 0。沿 probe-common.ps1 的 owned phase 機制
# （PID＋啟動時間身分、逾時停止、log 收集）與 m6-universe-probe.ps1 的 readonly summary＋VerifySummary＋SelfTest 做法。
# 參數：
#   -Only <列名,...>     只跑指定列（manifest 順序）；-DoorState ordinary|sealed|passable 只跑該門態的 13 列（B2 CI 分片；三片聯集恰為 39 列）
#   -VerifySummary <檔>  唯讀重驗已存 summary（逐列、逐 phase、逐退出碼與 receipt hash／內容）；-SelfTest 另跑負控（不改原件）
#   -ExpectedHead <sha>  summary 的 HEAD；未給時用目前 HEAD
# phase receipt 位置由 testmod M7HomeProbe 固定為 <repo>/.superpowers/verification/m7-home-<nonce>/<phase>/（halt.json 或 final.json）。
param([string[]]$Only=@(),[ValidateSet('','ordinary','sealed','passable')][string]$DoorState='',
      [string]$VerifySummary='',[string]$ExpectedHead='',[switch]$SelfTest,
      [string]$EvidenceRoot='',[string]$Gradle='',
      [int]$IdleSeconds=90,[int]$PhaseMinutes=5,[int]$BlockMinutes=240,
      [int]$GradleCodePage=[Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$Only=@($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if($SelfTest -and -not $VerifySummary){throw 'M7_HOME_VERIFY_SCOPE：SelfTest 僅供唯讀 VerifySummary'}
if($VerifySummary -and ($Only.Count -or $DoorState)){throw 'M7_HOME_VERIFY_SCOPE：唯讀驗證不得混入 runtime 選擇'}
if($Only.Count -and $DoorState){throw 'M7_HOME_SELECTION_MIXED：-Only 與 -DoorState 不能同時指定'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'probe-common.ps1')

# 固定矩陣：三門態 × 十三 cut（逐字沿 plan Task 4）。Java phase 表（M7HomeClosurePhases.rows）與 inventory 的 windows.native 必須與它逐項相同。
$doors=@('ordinary','sealed','passable')
$cuts=@('before-close','after-close','before-door','after-door','before-checkpoint','after-checkpoint','after-temp-force','before-publish','after-publish','after-readback','after-recheck','before-remove','after-remove')
$initialDoor=@{ordinary='CLOSED/NONE';sealed='OPEN/SEALED_X';passable='OPEN/PASSABLE_X'}
$manifest=@(foreach($door in $doors){for($i=0;$i -lt $cuts.Count;$i++){[pscustomobject]@{row="close-$door-$($cuts[$i])";door=$door;cut=$cuts[$i];index=$i}}})
$inventory=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json') -Raw | ConvertFrom-Json
if(@($inventory.windows.native).Count -ne 39 -or (@($inventory.windows.native) -join ',') -cne ($manifest.row -join ',')){throw 'M7_HOME_INVENTORY：m7-inventory.json windows.native 與固定矩陣不符'}
$shards=@(foreach($door in $doors){,@($manifest | Where-Object door -CEQ $door | ForEach-Object row)})
if(((@($shards | ForEach-Object {$_.Count})) -join ',') -cne '13,13,13' -or ((@($shards | ForEach-Object {$_}) | Sort-Object) -join ',') -cne (($manifest.row | Sort-Object) -join ',') -or @($shards | ForEach-Object {$_} | Sort-Object -Unique).Count -ne 39){throw 'M7_HOME_SHARDS：三個 -DoorState 分片的聯集必須恰為 39 列且互不重疊'}

function Get-M7Group($spec) {
    if($spec.index -eq 0){return 'A'}
    if($spec.index -le $cuts.IndexOf('before-publish')){return 'B'}
    if($spec.index -le $cuts.IndexOf('before-remove')){return 'C'}
    return 'D'
}
function Get-M7CrashEvents($spec) { return @($cuts[0..$spec.index]) }
function Get-M7ReopenEvents($spec) {
    switch(Get-M7Group $spec){
        'A' {return @($cuts)}
        'B' {return @($cuts[2..12])}
        'C' {return @('before-door','after-door','before-checkpoint','after-checkpoint','verify-existing','after-recheck','before-remove','after-remove')}
        default {return @()}
    }
}
function Get-M7Selection {
    if($Only.Count){
        foreach($name in $Only){if($name -cnotin $manifest.row){throw "M7_HOME_ROW_UNKNOWN：$name"}}
        if(@($Only | Sort-Object -Unique).Count -ne $Only.Count){throw 'M7_HOME_ROW_DUPLICATE'}
        return [pscustomobject]@{mode='only';doorState='';rows=@($manifest | Where-Object {$_.row -cin $Only} | ForEach-Object row)}
    }
    if($DoorState){return [pscustomobject]@{mode='doorState';doorState=$DoorState;rows=@($manifest | Where-Object door -CEQ $DoorState | ForEach-Object row)}}
    return [pscustomobject]@{mode='all';doorState='';rows=@($manifest.row)}
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
function Assert-M7Checkpoint($call,$setup,[bool]$ReceiptPresent) {
    $worlds=@($call.worlds)
    if($worlds.Count -ne 1 -or $worlds[0].world -cne $setup.entryWorld -or (@($worlds[0].chunks) -join '|') -cne (@($setup.homeChunks) -join '|') -or $call.activeClose -ne $true -or $call.bDoor -cne 'OPEN/NONE' -or $call.receiptPresent -ne $ReceiptPresent){throw 'M7_HOME_CHECKPOINT'}
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
        if($crashPhase){Assert-M7Crash $receipt $spec $rowEntry.nonce $phase.startupNonce}else{Assert-M7Reopen $receipt $spec $receipts[0] $rowEntry.nonce $phase.startupNonce}
        $receipts+=,$receipt
    }
    if("$($phases[0].javaPid)@$($phases[0].javaStartTimeUtc)" -ceq "$($phases[1].javaPid)@$($phases[1].javaStartTimeUtc)" -or $phases[0].startupNonce -ceq $phases[1].startupNonce){throw "M7_HOME_SAVED_JVM_REUSED：$($rowEntry.row)"}
}
function Assert-M7SavedGlobal($summary) {
    if($summary.status -cne 'PASS' -or $summary.schema -ne 1 -or $summary.head -cne $head -or (@($summary.rowTable) -join ',') -cne ($manifest.row -join ',')){throw 'M7_HOME_SAVED_SUMMARY'}
    $expected=@(switch -CaseSensitive ($summary.selection.mode){
        'all' {@($manifest.row)}
        'doorState' {if($summary.selection.doorState -cnotin $doors){throw 'M7_HOME_SAVED_SELECTION'};@($manifest | Where-Object door -CEQ $summary.selection.doorState | ForEach-Object row)}
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
function Copy-M7Json($value) { return $value | ConvertTo-Json -Depth 40 | ConvertFrom-Json -DateKind String }
# 負控必須以預期的具名錯因被拒（不是任意例外）。
function Test-M7Rejected([scriptblock]$Check,[string]$Case,[string]$Code) {
    $reason=''
    try {& $Check} catch {$reason=$_.Exception.Message}
    if($reason -cnotmatch ('^'+[regex]::Escape($Code)+'(：|$)')){throw "M7_HOME_SELFTEST_MISSED：$Case 預期 $Code，實際（$reason）"}
}

$head=(git -c "safe.directory=$project" -C $project rev-parse HEAD).Trim()
if($ExpectedHead){$head=$ExpectedHead}
if($VerifySummary) {
    $summary=Read-M7Receipt $VerifySummary
    Assert-M7SavedSummary $summary
    $checks=0
    if($SelfTest) {
        # 整份 summary 的負控：列缺少、多出、改名、phase 改名、選擇與 HEAD 不符。
        $global=[ordered]@{
            'row-missing'=@('M7_HOME_SAVED_ROWS',{param($s) $s.rows=@(@($s.rows) | Select-Object -SkipLast 1)})
            'row-extra'=@('M7_HOME_SAVED_ROWS',{param($s) $s.rows=@(@($s.rows)+@(@($s.rows)[0]))})
            'row-renamed'=@('M7_HOME_SAVED_ROWS',{param($s) @($s.rows)[0].row=@($s.rows)[0].row+'-renamed'})
            'row-swapped'=@('M7_HOME_SAVED_ROWS',{param($s) if(@($s.rows).Count -ge 2){$first=@($s.rows)[0].row;@($s.rows)[0].row=@($s.rows)[1].row;@($s.rows)[1].row=$first}else{@($s.rows)[0].row='close-ordinary-renamed'}})
            'phase-renamed'=@('M7_HOME_SAVED_PHASES',{param($s) @(@($s.rows)[0].phases)[0].phase='restart'})
            'selection-mismatch'=@('M7_HOME_SAVED_SELECTION',{param($s) $s.selection=[pscustomobject]@{mode='doorState';doorState=$(if(@($s.rows)[0].door -ceq 'ordinary'){'sealed'}else{'ordinary'});only=@()}})
            'head-mismatch'=@('M7_HOME_SAVED_SUMMARY',{param($s) $s.head='0'*40})
        }
        foreach($case in $global.Keys){$bad=Copy-M7Json $summary;& $global[$case][1] $bad;Test-M7Rejected {Assert-M7SavedSummary $bad} $case $global[$case][0];$checks++}
        # 逐列：兩個 phase 的退出碼、receipt hash 與 receipt 內容（cut、事件序列、狀態）；只重驗該列，原件不改。
        for($i=0;$i -lt @($summary.rows).Count;$i++){
            foreach($p in 0,1){
                $bad=Copy-M7Json $summary;@(@($bad.rows)[$i].phases)[$p].javaExitCode=$(if($p -eq 0){0}else{77})
                Test-M7Rejected {Assert-M7SavedGlobal $bad;Assert-M7SavedRow $bad $i} "exit-$i-$p" 'M7_HOME_SAVED_EXIT';$checks++
                $bad=Copy-M7Json $summary;@(@($bad.rows)[$i].phases)[$p].receiptSha256='0'*64
                Test-M7Rejected {Assert-M7SavedGlobal $bad;Assert-M7SavedRow $bad $i} "hash-$i-$p" 'M7_HOME_SAVED_RECEIPT_HASH';$checks++
            }
            $crashReceipt=Read-M7Receipt @(@($summary.rows)[$i].phases)[0].receipt
            $reopenReceipt=Read-M7Receipt @(@($summary.rows)[$i].phases)[1].receipt
            $bad=Copy-M7Json $crashReceipt;$bad.cut=$(if($bad.cut -ceq 'before-close'){'after-close'}else{'before-close'})
            Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/crash"=$bad}} "crash-cut-$i" 'M7_HOME_RECEIPT_IDENTITY';$checks++
            $bad=Copy-M7Json $crashReceipt;$bad.events=@(@($bad.events)+@(@($bad.events)[-1]))
            Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/crash"=$bad}} "crash-events-$i" 'M7_HOME_CRASH_EVENTS';$checks++
            $bad=Copy-M7Json $reopenReceipt;$bad.status='FAIL'
            Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/reopen"=$bad}} "reopen-status-$i" 'M7_HOME_REOPEN_FAILED';$checks++
            $bad=Copy-M7Json $reopenReceipt;$bad.events=@(@($bad.events)+@([pscustomobject]@{event='before-door';serverTick=0;gameTime=0;detail='OPEN/NONE'}))
            Test-M7Rejected {Assert-M7SavedRow $summary $i @{"$i/reopen"=$bad}} "reopen-events-$i" 'M7_HOME_REOPEN_EVENTS';$checks++
        }
        # 負控不改原件：結束再完整重驗一次。
        Assert-M7SavedSummary $summary
    }
    Write-Output "M7_HOME_VERIFIED PASS rows=$(@($summary.rows).Count) phases=$($summary.phaseCount) negativeChecks=$checks selection=$($summary.selection.mode) HEAD=$head"
    return
}

if(-not $IsWindows){throw 'M7_HOME_PLATFORM_UNSUPPORTED：owned Win32_Process 原生鏈僅支援 Windows'}
if(-not $Gradle){$fixed='C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat';$Gradle=if(Test-Path -LiteralPath $fixed){$fixed}else{Join-Path $project 'gradlew.bat'}}
if(-not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "M7_HOME_GRADLE_MISSING：$Gradle"}
$selection=Get-M7Selection
$selected=@($selection.rows | ForEach-Object {Get-M7Spec $_})
$runId=[guid]::NewGuid().ToString('N').Substring(0,12)
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
        Assert-M7Crash $receipt $spec $owned.nonce $state.StartupNonce
    } else {
        if(Test-Path -LiteralPath $haltPath){throw "M7_HOME_REOPEN_HALTED：$($state.PhaseEvidence)"}
        if($state.Launcher.ExitCode -ne 0 -or $state.JavaProcess.ExitCode -ne 0){throw "M7_HOME_REOPEN_EXIT：gradle=$($state.Launcher.ExitCode) java=$($state.JavaProcess.ExitCode)（$note）"}
        if(-not(Test-Path -LiteralPath $finalPath)){throw "M7_HOME_REOPEN_RECEIPT_MISSING：$($state.PhaseEvidence)"}
        $receiptPath=$finalPath;$receipt=Read-M7Receipt $finalPath
        Assert-M7Reopen $receipt $spec $crashReceipt $owned.nonce $state.StartupNonce
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
$summary=[ordered]@{schema=1;status=$(if($failure){'FAIL'}else{'PASS'});head=$head;treeClean=$treeClean;runId=$runId;selection=[ordered]@{mode=$selection.mode;doorState=$selection.doorState;only=@($Only)};rowTable=@($manifest.row);selectedRows=@($selection.rows);rowCount=$rows.Count;phaseCount=2*$rows.Count;idleSeconds=$IdleSeconds;phaseMinutes=$PhaseMinutes;blockMinutes=$BlockMinutes;totalSeconds=[Math]::Round(([DateTimeOffset]::UtcNow-$runStart).TotalSeconds,1);rows=@($rows.ToArray());failure=$failure}
$summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Output "M7_HOME_SUMMARY=$summaryPath"
if($failure){throw $failure}
Write-Output "M7_HOME PASS rows=$($rows.Count) phases=$(2*$rows.Count) selection=$($selection.mode) seconds=$($summary.totalSeconds) HEAD=$head"
