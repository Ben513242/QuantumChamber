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
$manifest=@(
    [pscustomobject]@{chain='native-seed-diagnostic';m6ProbeSchema=1;expectedMode='DIAGNOSTIC';phases=@('baseline','save-reopen')}
    [pscustomobject]@{chain='catalog-readback';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('intent','allocation','atomic-move-readback','restart')}
    [pscustomobject]@{chain='native-seed-functional';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('construct','reopen')}
)
if(-not $IsWindows){throw 'M6_NATIVE_PLATFORM_UNSUPPORTED：owned Win32_Process原生鏈僅支援Windows'}
if($IdleSeconds -ne 90 -or $PhaseMinutes -ne 3 -or $BlockMinutes -ne 60){throw 'M6_NATIVE_BUDGET_MISMATCH：phase3分/idle90秒/block60分'}
if($AllEnabled -and $PSBoundParameters.ContainsKey('Chain')){throw 'M6_CHAIN_SELECTION_MIXED：AllEnabled不能同時指定Chain'}
$selected=@(if($AllEnabled){$manifest}else{$manifest | Where-Object chain -CEQ $Chain})
if($selected.Count -eq 0){throw "M6_CHAIN_UNKNOWN：$Chain"}
if($AllEnabled -and $PSBoundParameters.ContainsKey('ExpectedMode')){throw 'M6_EXPECTED_MODE_MIXED：全鏈逐一採用各自模式'}
if($RequireIndependentSeed -and ($AllEnabled -or $Chain -cne 'native-seed-diagnostic')){throw 'M6_FUNCTIONAL_NEGATIVE_SCOPE'}
if(-not $AllEnabled -and @($selected | Where-Object expectedMode -CNE $ExpectedMode).Count){throw "M6_EXPECTED_MODE_MIXED：$ExpectedMode"}
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
    if(@($receipt.worlds).Count -ne 5 -or @($receipt.descriptors).Count -ne 2){throw 'M6_WORLD_SET_MISMATCH'}
    $expected=@('minecraft:overworld','minecraft:the_nether','minecraft:the_end','quantumchamber:universe/67000000-0000-4000-8000-000000000001/overworld','quantumchamber:universe/67000000-0000-4000-8000-000000000002/overworld')
    if((@($receipt.worlds.worldKey) -join '|') -cne ($expected -join '|') -or @($receipt.worlds.worldKey | Sort-Object -Unique).Count -ne 5){throw 'M6_WORLD_SET_MISMATCH：exact keys'}
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
function Assert-SeedReceipt($receipt,$phase,$owned,$state) {
    if($receipt.m6ProbeSchema -ne 1 -or $receipt.expectedMode -cne 'FUNCTIONAL' -or $receipt.chain -cne 'native-seed-functional' -or $receipt.phase -cne $phase -or $receipt.nonce -cne $owned.nonce -or $receipt.startupNonce -cne $state.StartupNonce){throw 'M6_SEED_RECEIPT_IDENTITY'}
    if($receipt.status -cne 'PASS' -or @($receipt.failures).Count -or -not $receipt.nativeConstructorFailed -or -not $receipt.constructionCleared -or -not $receipt.functionalPassed){throw 'M6_SEED_FUNCTIONAL_ASSERTION_FAILED'}
    if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds){throw 'M6_SEED_RUNTIME_SCOPE'}
    if($receipt.globalAtStart -ne ($phase -ceq 'construct') -or $receipt.globalForNextStart -ne $false){throw 'M6_SEED_REOPEN_GLOBAL'}
    if(-not $receipt.stopRequested -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen -or -not $receipt.catalogChecked -or -not $receipt.legacyControl -or -not $receipt.independentSequences){throw 'M6_SEED_OWNER_CONTROL'}
    if(@($receipt.vanillaControls).Count -ne 4 -or @($receipt.vanillaControls | Where-Object {-not $_.workerExact -or -not $_.locatorExact -or -not $_.mapExact}).Count){throw 'M6_SEED_VANILLA_CONTROL'}
    if($receipt.canonicalSettingsKey -cne 'minecraft:amplified' -or -not $receipt.worldMonitorReadBlocked -or $receipt.worldMonitorReadAttempts -ne 2 -or -not $receipt.thirdUniversePublishedAndUnloaded -or @($receipt.thirdUniverseWorkerSamples).Count -ne 2 -or @($receipt.thirdUniverseWorkerSamples | Where-Object {$_ -le 0}).Count -or -not $receipt.staleInstanceControls -or @($receipt.parallelWorkers).Count -ne 4 -or @($receipt.parallelWorkers | Where-Object {-not $_.exact}).Count){throw 'M6_SEED_PARALLEL_OWNER'}
    if(@($receipt.worlds).Count -ne 2){throw 'M6_SEED_WORLD_SET'}
    $seeds=@('712345678901','-99123456789')
    for($i=0;$i -lt 2;$i++) {
        $w=$receipt.worlds[$i]
        if($w.worldKey -cne @('quantumchamber:universe/00000000-0000-0000-0000-0000000105b9/overworld','quantumchamber:universe/00000000-0000-0000-0000-0000000105ba/overworld')[$i] -or $w.settingsKey -cne 'minecraft:overworld' -or $w.seed -cne $seeds[$i] -or $w.frozenStructures -ne ($i -eq 0) -or $w.accessorStructures -ne ($i -eq 0) -or -not $w.nativeConsumersExact -or $w.sequenceValue -cne $w.sequenceExpected -or @($w.samples).Count -ne 4 -or -not $w.workerExact -or $w.workerGenerated -ne ($i -eq 0) -or -not $w.locatorExact -or -not $w.mapExact -or $w.mapFilled -ne ($i -eq 0)){throw 'M6_SEED_NATIVE_CONSUMER_MISMATCH'}
    }
}
function Assert-CatalogReceipt($receipt,$phase,$owned,$state) {
    if($receipt.m6ProbeSchema -ne 1 -or $receipt.expectedMode -cne 'FUNCTIONAL' -or $receipt.chain -cne 'catalog-readback' -or $receipt.phase -cne $phase -or $receipt.nonce -cne $owned.nonce -or $receipt.startupNonce -cne $state.StartupNonce){throw 'M6_CATALOG_RECEIPT_IDENTITY'}
    if($receipt.status -cne 'PASS' -or @($receipt.failures).Count -or $receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds -or $receipt.startedCallbacks -ne 1 -or $receipt.endTicks -ne 0 -or $receipt.stoppingEvents -ne 0 -or $receipt.movesAfterFault -ne 0 -or $receipt.worldAndStorageAbsent -ne $true){throw 'M6_CATALOG_PHASE_BOUNDARY'}
    $afterMove=$phase -ceq 'atomic-move-readback'
    $hasCatalog=$afterMove -or $phase -ceq 'restart'
    if($receipt.faultCount -ne [int]$afterMove -or $receipt.envelopeMoves -ne [int]$afterMove -or $receipt.catalogDirty -ne $afterMove -or $receipt.allocationCount -ne [int]($phase -cne 'intent') -or $receipt.catalogCurrentCount -ne [int]$hasCatalog -or $receipt.catalogFlushedCount -ne [int]($phase -ceq 'restart')){throw 'M6_CATALOG_PHASE_STATE'}
    if($receipt.uuid -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' -or $receipt.targetFingerprint -cnotmatch '^[0-9a-f]{64}$' -or $receipt.allocationToken -cnotmatch '^[0-9a-f]{64}$' -or $receipt.profileId -cne 'quantumchamber:random_vanilla_v1' -or $receipt.profileVersion -ne 1){throw 'M6_CATALOG_FROZEN_IDENTITY'}
    $keys=@('overworld','nether','end') | ForEach-Object {"quantumchamber:universe/$($receipt.uuid)/$_"}
    if((@($receipt.roleKeys) -join '|') -cne ($keys -join '|')){throw 'M6_CATALOG_ROLE_KEYS'}
    foreach($key in $keys){if(Test-Path -LiteralPath (Join-Path "$($owned.root)/world/dimensions" $key.Replace(':','/'))){throw 'M6_CATALOG_STORAGE_PRESENT'}}
    $files=@{journalHash='quantumchamber_sessions.dat';allocationHash='quantumchamber_universe_allocations.dat';catalogHash='quantumchamber_universes.dat'}
    foreach($name in $files.Keys){
        $path=Join-Path "$($owned.root)/world/data" $files[$name]
        if($name -ceq 'catalogHash' -and -not $hasCatalog){
            if($receipt.$name -cne 'ABSENT' -or (Test-Path -LiteralPath $path)){throw 'M6_CATALOG_EARLY_FILE'}
        } elseif((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $receipt.$name){throw 'M6_CATALOG_AUTHORITY_HASH'}
    }
}
try {
    foreach($chainSpec in $selected){
        if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
        $owned=New-OwnedRoot -Context $context -Suffix $chainSpec.chain
        if($chainSpec.chain -ceq 'native-seed-functional'){'level-type=minecraft:amplified' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        $chainStart=$results.Count
        foreach($phase in $chainSpec.phases){
            if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
            $state=Invoke-OwnedPhase -Context $context -Nonce $owned.nonce -Phase $phase -Root $owned.root -ExtraProperties ([ordered]@{Chain=$chainSpec.chain;ExpectedMode=$chainSpec.expectedMode})
            $functional=$chainSpec.chain -ceq 'catalog-readback'
            $receiptName=if($functional){'halt.json'}else{'final.json'}
            $receiptPath=Join-Path $state.PhaseEvidence $receiptName
            if(-not(Test-Path -LiteralPath $receiptPath)){throw "M6_RECEIPT_MISSING：$(Get-PhaseFailureNote -Context $context -State $state)"}
            $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json -DateKind String
            if($functional){
                if($state.Launcher.ExitCode -eq 0 -or $state.JavaProcess.ExitCode -ne 77 -or (Test-Path -LiteralPath (Join-Path $state.PhaseEvidence 'final.json'))){throw 'M6_CATALOG_HALT_MISMATCH'}
                $gradleOutput=(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stdout.log') -Encoding $context.GradleEncoding)+(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stderr.log') -Encoding $context.GradleEncoding)
                if($gradleOutput -notmatch 'finished with non-zero exit value 77'){throw 'M6_CATALOG_GRADLE_EXIT_MISMATCH'}
                Assert-CatalogReceipt $receipt $phase $owned $state
            } else {
                if($state.Launcher.ExitCode -ne 0 -or $state.JavaProcess.ExitCode -ne 0){throw 'M6_PHASE_EXIT_NONZERO'}
                if($chainSpec.chain -ceq 'native-seed-functional'){
                    Assert-SeedReceipt $receipt $phase $owned $state
                    foreach($field in @('worldMonitorReadBlocked','thirdUniversePublishedAndUnloaded')) {
                        $bad=$receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json -DateKind String
                        $bad.$field=$false;$rejected=$false
                        try {Assert-SeedReceipt $bad $phase $owned $state} catch {if($_.Exception.Message -ceq 'M6_SEED_PARALLEL_OWNER'){$rejected=$true}else{throw}}
                        if(-not $rejected){throw "M6_SEED_MONITOR_NEGATIVE_MISSED：$field"}
                    }
                    foreach($field in @('workerExact','locatorExact','mapExact','nativeConsumersExact')) {
                        $bad=$receipt | ConvertTo-Json -Depth 20 | ConvertFrom-Json -DateKind String
                        $bad.worlds[0].$field=$false;$rejected=$false
                        try {Assert-SeedReceipt $bad $phase $owned $state} catch {if($_.Exception.Message -ceq 'M6_SEED_NATIVE_CONSUMER_MISMATCH'){$rejected=$true}else{throw}}
                        if(-not $rejected){throw "M6_SEED_NEGATIVE_MISSED：$field"}
                    }
                }
                else {Assert-DiagnosticReceipt -Receipt $receipt -Phase $phase -Nonce $owned.nonce -StartupNonce $state.StartupNonce}
            }
            if($receipt.pid -ne $state.JavaId -or $receipt.root -cne $owned.root){throw 'M6_JVM_IDENTITY_MISMATCH'}
            if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-$state.JavaStart).TotalSeconds) -gt 1){throw 'M6_JVM_STARTTIME_MISMATCH'}
            if(-not $state.JavaProcess.HasExited){throw 'M6_JVM_STILL_LIVE'}
            $lock=[IO.File]::Open((Join-Path $owned.root 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$lock.Dispose()
            $logs=Copy-OwnedLogs -State $state
            foreach($key in @('classload','latest')){if(-not $logs[$key].copied){throw "M6_RUNTIME_LOG_MISSING：$key"}}
            $runtime=Get-SharedText -Path (Join-Path $state.PhaseEvidence 'latest.log') -Encoding ([Text.Encoding]::UTF8)
            if([regex]::Matches($runtime,'M6_NATIVE_BEGIN chain=').Count -ne 1 -or [regex]::Matches($runtime,'M6_NATIVE_END chain=').Count -ne 1){throw 'M6_RUNTIME_LOG_IDENTITY_MISMATCH'}
            $row=[ordered]@{m6ProbeSchema=1;status='PASS';chain=$chainSpec.chain;phase=$phase;expectedMode=$chainSpec.expectedMode;nonce=$owned.nonce;startupNonce=$state.StartupNonce;root=$owned.root;javaPid=$state.JavaId;javaStartTimeUtc=$state.JavaStart.ToString('o');gradleExitCode=$state.Launcher.ExitCode;javaExitCode=$state.JavaProcess.ExitCode;normalStop=(-not $functional);lockReleased=$true;receipt=$receiptPath;receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash;auxiliaryLogs=$logs}
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'launcher.json') -Encoding utf8
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'phase.json') -Encoding utf8
            $results.Add($row);$state.JavaProcess.Dispose();$state.Launcher.Dispose()
            Write-Output "M6_PHASE PASS chain=$($chainSpec.chain) phase=$phase pid=$($row.javaPid) mode=$($chainSpec.expectedMode) ROOT=$($owned.root)"
        }
        $current=@($results.ToArray() | Select-Object -Skip $chainStart)
        if(@($current.javaPid | Select-Object -Unique).Count -ne $chainSpec.phases.Count -or @($current.startupNonce | Select-Object -Unique).Count -ne $chainSpec.phases.Count){throw 'M6_PHASE_JVM_REUSED'}
        [ordered]@{strategy='NEW_OWNED_ROOT';existingWorldsMoved=0;existingWorldsChanged=$false;retainedRoot=$true;root=$owned.root;normalStop=(-not $functional);lockReleased=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $owned.root 'preservation.json') -Encoding utf8
        if($functional){
            $proofs=@($current | ForEach-Object {Get-Content -LiteralPath $_.receipt -Raw | ConvertFrom-Json -DateKind String})
            foreach($field in @('uuid','session','allocationToken','rootWorldSeed','targetFingerprint','profileId','profileVersion','journalHash')){
                if(@($proofs | ForEach-Object {$_.$field} | Sort-Object -Unique).Count -ne 1){throw "M6_CATALOG_RESTART_IDENTITY：$field"}
            }
            if($proofs[1].allocationHash -cne $proofs[2].allocationHash -or $proofs[2].allocationHash -cne $proofs[3].allocationHash -or $proofs[2].catalogHash -cne $proofs[3].catalogHash){throw 'M6_CATALOG_RESTART_HASH'}
        }
        if($chainSpec.chain -ceq 'native-seed-functional') {
            $a=Get-Content -Raw -LiteralPath $current[0].receipt | ConvertFrom-Json -DateKind String
            $b=Get-Content -Raw -LiteralPath $current[1].receipt | ConvertFrom-Json -DateKind String
            for($i=0;$i -lt 2;$i++) {
                foreach($field in @('worldKey','seed','frozenStructures','accessorStructures','settingsKey','samples','positivePosition')) {
                    if(($a.worlds[$i].$field | ConvertTo-Json -Compress) -cne ($b.worlds[$i].$field | ConvertTo-Json -Compress)){throw "M6_SEED_REOPEN_MISMATCH：$field"}
                }
            }
        }
        $chains.Add([ordered]@{chain=$chainSpec.chain;expectedMode=$chainSpec.expectedMode;phases=@($chainSpec.phases);root=$owned.root;preservation=(Join-Path $owned.root 'preservation.json')})
    }
    if((git -C $project rev-parse HEAD).Trim() -cne $head){throw 'M6_HEAD_CHANGED_DURING_PROBE'}
} catch {$failure=$_.Exception.Message}
[ordered]@{status=$(if($failure){'FAIL'}else{'PASS'});head=$head;runId=$context.RunId;allEnabled=[bool]$AllEnabled;enabledManifest=$manifest;selectedChains=@($selected.chain);expectedMode=$(if($AllEnabled){'PER_CHAIN'}else{$ExpectedMode});phaseMinutes=$PhaseMinutes;idleSeconds=$IdleSeconds;blockMinutes=$BlockMinutes;phaseCount=$results.Count;phases=@($results.ToArray());chains=@($chains.ToArray());failure=$failure;functionalAssertionsRemainRed=($null -eq $failure -and 'native-seed-diagnostic' -in $selected.chain);requireIndependentSeed=[bool]$RequireIndependentSeed} |
    ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Output "M6_NATIVE_SUMMARY=$summaryPath"
if($failure){throw $failure}
# 明示功能負向要求在正常stop/lock核對後才拒絕；receipt/chain仍始終是DIAGNOSTIC。
if($RequireIndependentSeed){throw "M6_FUNCTIONAL_RED：newWorldHasIndependentSeed=false；DIAGNOSTIC receipt保留於$summaryPath"}
Write-Output "M6_NATIVE PASS chains=$($selected.chain -join ',') HEAD=$head phases=$($results.Count)"
