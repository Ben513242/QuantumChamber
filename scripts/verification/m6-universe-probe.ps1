# M6跨JVM原生診斷。全新owned root永久保留；每phase新JVM，不移動既有世界。
param([string]$Chain='native-seed-diagnostic',[switch]$AllEnabled,
      [string]$ExpectedMode='DIAGNOSTIC',[switch]$RequireIndependentSeed,
      [string]$EvidenceRoot='',[string]$Gradle='',
      [int]$IdleSeconds=90,[int]$PhaseMinutes=3,[int]$BlockMinutes=60,
      [int]$GradleCodePage=[Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'probe-common.ps1')
# 已啟用集合是正式來源；Task11須新增另一條FUNCTIONAL chain，不能改判舊diagnostic。
$manifest=@([pscustomobject]@{chain='native-seed-diagnostic';expectedMode='DIAGNOSTIC';phases=@('baseline','save-reopen')})
if(-not $IsWindows){throw 'M6_NATIVE_PLATFORM_UNSUPPORTED：owned Win32_Process原生鏈僅支援Windows'}
if($IdleSeconds -ne 90 -or $PhaseMinutes -ne 3 -or $BlockMinutes -ne 60){throw 'M6_NATIVE_BUDGET_MISMATCH：phase3分/idle90秒/block60分'}
if($AllEnabled -and $PSBoundParameters.ContainsKey('Chain')){throw 'M6_CHAIN_SELECTION_MIXED：AllEnabled不能同時指定Chain'}
$selected=@(if($AllEnabled){$manifest}else{$manifest | Where-Object chain -CEQ $Chain})
if($selected.Count -eq 0){throw "M6_CHAIN_UNKNOWN：$Chain"}
if(@($selected | Where-Object expectedMode -CNE $ExpectedMode).Count){throw "M6_EXPECTED_MODE_MIXED：$ExpectedMode"}
if(-not $Gradle){$fixed='C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat';$Gradle=if(Test-Path -LiteralPath $fixed){$fixed}else{Join-Path $project 'gradlew.bat'}}
if(-not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "M6_GRADLE_MISSING：$Gradle"}
$head=(git -C $project rev-parse HEAD).Trim()
$context=[pscustomobject]@{Project=$project;Gradle=$Gradle;InitScript=(Join-Path $PSScriptRoot 'runtime.init.gradle');RunId=([guid]::NewGuid().ToString('N'));RootPrefix='m6-universe';EvidencePrefix='m6-native';EvidenceOwner='';Task='runM6UniverseProbe';PropertyPrefix='m6Universe';NonceProperty='quantumchamber.m6universe.startupNonce';IdleSeconds=$IdleSeconds;PhaseMinutes=$PhaseMinutes;GradleEncoding=[Text.Encoding]::GetEncoding($GradleCodePage);LogEncoding=[Text.Encoding]::UTF8}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $project '.superpowers/verification'}
$EvidenceRoot=Resolve-Evidence -Context $context -Path $EvidenceRoot
$context.EvidenceOwner=Resolve-Evidence -Context $context -Path (Join-Path $project '.superpowers/verification')
$summaryPath=Join-Path $EvidenceRoot "m6-native-run-$($context.RunId).json"
$live=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -notmatch 'org.gradle.launcher.daemon.bootstrap.GradleDaemon'})
if($live.Count){throw 'M6_NATIVE_OWNER_BUSY：已有非daemon Java，禁止併行gate/probe'}
$results=[Collections.Generic.List[object]]::new()
$chains=[Collections.Generic.List[object]]::new()
$deadline=[DateTimeOffset]::UtcNow.AddMinutes($BlockMinutes)
$failure=$null
function Assert-DiagnosticReceipt($receipt,[string]$Phase,[string]$Nonce,[string]$StartupNonce){
    if($receipt.m6ProbeSchema -ne 1 -or $receipt.expectedMode -cne 'DIAGNOSTIC' -or $receipt.chain -cne 'native-seed-diagnostic' -or $receipt.phase -cne $Phase -or $receipt.nonce -cne $Nonce -or $receipt.startupNonce -cne $StartupNonce){throw 'M6_RECEIPT_IDENTITY_MISMATCH：schema/mode/chain/phase/nonce'}
    if($receipt.status -cne 'PASS' -or @($receipt.failures).Count -or -not $receipt.diagnosticPassed -or -not $receipt.catalogChecked){throw 'M6_DIAGNOSTIC_ORACLE_FAILED：Java斷言未通過'}
    if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds){throw 'M6_RUNTIME_SCOPE_MISMATCH'}
    if(-not $receipt.stopRequested -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen){throw 'M6_NORMAL_STOP_MISSING'}
    if(@($receipt.worlds).Count -ne 5 -or @($receipt.descriptors).Count -ne 2 -or @($receipt.seedHints).Count -ne 2){throw 'M6_WORLD_SET_MISMATCH'}
    $expected=@('minecraft:overworld','minecraft:the_nether','minecraft:the_end','quantumchamber:universe/67000000-0000-4000-8000-000000000001/overworld','quantumchamber:universe/67000000-0000-4000-8000-000000000002/overworld')
    if((@($receipt.worlds.worldKey) -join '|') -cne ($expected -join '|') -or @($receipt.worlds.worldKey | Sort-Object -Unique).Count -ne 5){throw 'M6_WORLD_SET_MISMATCH：exact keys'}
    if($receipt.seedHints[0] -ceq $receipt.seedHints[1] -or $receipt.saveSeed -cin $receipt.seedHints){throw 'M6_SEED_HINTS_NOT_DISTINCT'}
    foreach($world in $receipt.worlds){
        if($world.getSeed -cne $receipt.saveSeed -or $world.locatorSeed -cne $receipt.saveSeed -or $world.structureSeed -cne $world.expectedStructureSeed -or $world.concentricRingSeed -cne $world.expectedConcentricRingSeed -or -not $world.noiseControlExact -or -not $world.biomeControlExact -or -not $world.structureControlExact){throw "M6_WORLD_ORACLE_FAILED：$($world.worldKey)"}
        if($world.worker.serverThread -or $world.worker.getSeed -cne $world.getSeed -or (@($world.worker.noiseSamples) -join '|') -cne (@($world.noiseSamples) -join '|') -or (@($world.worker.biomeSamples) -join '|') -cne (@($world.biomeSamples) -join '|')){throw 'M6_WORKER_ORACLE_FAILED'}
    }
    if(@($receipt.randomSequences).Count -ne 5){throw 'M6_SEQUENCE_SET_MISMATCH'}
    foreach($row in $receipt.randomSequences){if(-not $row.continuityExact -or $row.value -cne $row.expectedValue -or $row.stateSeed -cne $receipt.saveSeed){throw 'M6_SEQUENCE_CONTINUITY_MISMATCH'}}
    foreach($index in @(3,4)){if(-not $receipt.randomSequences[$index].sharedCanonical -or $receipt.randomSequences[$index].stateIdentity -ne $receipt.randomSequences[0].stateIdentity){throw 'M6_DIAGNOSTIC_SHARED_IDENTITY_MISSING'}}
    if(-not $receipt.diagnosticAssertions.newWorldStillReadsSaveSeed -or -not $receipt.diagnosticAssertions.newWorldSharesCanonicalRandomSequences -or -not $receipt.diagnosticAssertions.canonicalControlsMatch){throw 'M6_DIAGNOSTIC_OBSERVATION_MISSING'}
    if($receipt.functionalAssertions.newWorldHasIndependentSeed -ne $false -or $receipt.functionalAssertions.newWorldHasIndependentRandomSequences -ne $false){throw 'M6_DIAGNOSTIC_FUNCTIONAL_RED_CHANGED'}
    if($Phase -ceq 'save-reopen' -and -not $receipt.diagnosticAssertions.reopenExact){throw 'M6_REOPEN_ORACLE_MISSING'}
}
try {
    foreach($chainSpec in $selected){
        if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
        $owned=New-OwnedRoot -Context $context -Suffix $chainSpec.chain
        $chainStart=$results.Count
        foreach($phase in $chainSpec.phases){
            if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
            $state=Invoke-OwnedPhase -Context $context -Nonce $owned.nonce -Phase $phase -Root $owned.root -ExtraProperties ([ordered]@{Chain=$chainSpec.chain;ExpectedMode=$chainSpec.expectedMode})
            $receiptPath=Join-Path $state.PhaseEvidence 'final.json'
            if(-not(Test-Path -LiteralPath $receiptPath)){throw "M6_RECEIPT_MISSING：$(Get-PhaseFailureNote -Context $context -State $state)"}
            $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json -DateKind String
            if($state.Launcher.ExitCode -ne 0 -or $state.JavaProcess.ExitCode -ne 0){throw 'M6_PHASE_EXIT_NONZERO'}
            Assert-DiagnosticReceipt -Receipt $receipt -Phase $phase -Nonce $owned.nonce -StartupNonce $state.StartupNonce
            if($receipt.pid -ne $state.JavaId -or $receipt.root -cne $owned.root){throw 'M6_JVM_IDENTITY_MISMATCH'}
            if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-$state.JavaStart).TotalSeconds) -gt 1){throw 'M6_JVM_STARTTIME_MISMATCH'}
            if(-not $state.JavaProcess.HasExited){throw 'M6_JVM_STILL_LIVE'}
            $lock=[IO.File]::Open((Join-Path $owned.root 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$lock.Dispose()
            $logs=Copy-OwnedLogs -State $state
            foreach($key in @('classload','latest')){if(-not $logs[$key].copied){throw "M6_RUNTIME_LOG_MISSING：$key"}}
            $runtime=Get-SharedText -Path (Join-Path $state.PhaseEvidence 'latest.log') -Encoding ([Text.Encoding]::UTF8)
            if([regex]::Matches($runtime,'M6_NATIVE_BEGIN chain=').Count -ne 1 -or [regex]::Matches($runtime,'M6_NATIVE_END chain=').Count -ne 1){throw 'M6_RUNTIME_LOG_IDENTITY_MISMATCH'}
            $row=[ordered]@{m6ProbeSchema=1;status='PASS';chain=$chainSpec.chain;phase=$phase;expectedMode=$chainSpec.expectedMode;nonce=$owned.nonce;startupNonce=$state.StartupNonce;root=$owned.root;javaPid=$state.JavaId;javaStartTimeUtc=$state.JavaStart.ToString('o');gradleExitCode=$state.Launcher.ExitCode;javaExitCode=$state.JavaProcess.ExitCode;normalStop=$true;lockReleased=$true;receipt=$receiptPath;receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash;auxiliaryLogs=$logs}
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'launcher.json') -Encoding utf8
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'phase.json') -Encoding utf8
            $results.Add($row);$state.JavaProcess.Dispose();$state.Launcher.Dispose()
            Write-Output "M6_PHASE PASS chain=$($chainSpec.chain) phase=$phase pid=$($row.javaPid) mode=$($chainSpec.expectedMode) ROOT=$($owned.root)"
        }
        $current=@($results.ToArray() | Select-Object -Skip $chainStart)
        if(@($current.javaPid | Select-Object -Unique).Count -ne $chainSpec.phases.Count -or @($current.startupNonce | Select-Object -Unique).Count -ne $chainSpec.phases.Count){throw 'M6_PHASE_JVM_REUSED'}
        [ordered]@{strategy='NEW_OWNED_ROOT';existingWorldsMoved=0;existingWorldsChanged=$false;retainedRoot=$true;root=$owned.root;normalStop=$true;lockReleased=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $owned.root 'preservation.json') -Encoding utf8
        $chains.Add([ordered]@{chain=$chainSpec.chain;expectedMode=$chainSpec.expectedMode;phases=@($chainSpec.phases);root=$owned.root;preservation=(Join-Path $owned.root 'preservation.json')})
    }
    if((git -C $project rev-parse HEAD).Trim() -cne $head){throw 'M6_HEAD_CHANGED_DURING_PROBE'}
} catch {$failure=$_.Exception.Message}
[ordered]@{status=$(if($failure){'FAIL'}else{'PASS'});head=$head;runId=$context.RunId;allEnabled=[bool]$AllEnabled;enabledManifest=$manifest;selectedChains=@($selected.chain);expectedMode=$ExpectedMode;phaseMinutes=$PhaseMinutes;idleSeconds=$IdleSeconds;blockMinutes=$BlockMinutes;phaseCount=$results.Count;phases=@($results.ToArray());chains=@($chains.ToArray());failure=$failure;functionalAssertionsRemainRed=($null -eq $failure -and $results.Count -gt 0);requireIndependentSeed=[bool]$RequireIndependentSeed} |
    ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Output "M6_NATIVE_SUMMARY=$summaryPath"
if($failure){throw $failure}
# 明示功能負向要求在正常stop/lock核對後才拒絕；receipt/chain仍始終是DIAGNOSTIC。
if($RequireIndependentSeed){throw "M6_FUNCTIONAL_RED：newWorldHasIndependentSeed=false；DIAGNOSTIC receipt保留於$summaryPath"}
Write-Output "M6_NATIVE PASS diagnostic-only HEAD=$head phases=$($results.Count)"
