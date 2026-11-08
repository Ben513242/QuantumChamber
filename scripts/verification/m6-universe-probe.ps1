# M6跨JVM原生診斷。全新owned root永久保留；每phase新JVM，不移動既有世界。
param([string]$Chain='native-seed-diagnostic',[switch]$AllEnabled,
      [string]$ExpectedMode='DIAGNOSTIC',[switch]$RequireIndependentSeed,
      [string]$VerifySummary='',[string]$ExpectedHead='',[switch]$SelfTest,
      [string]$EvidenceRoot='',[string]$Gradle='',
      [int]$IdleSeconds=90,[int]$PhaseMinutes=3,[int]$BlockMinutes=60,
      [int]$GradleCodePage=[Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($SelfTest -and -not $VerifySummary){throw 'M6_VERIFY_SCOPE：SelfTest僅供唯讀VerifySummary'}
if($VerifySummary -and ($AllEnabled -or $PSBoundParameters.ContainsKey('Chain') -or $PSBoundParameters.ContainsKey('ExpectedMode') -or $RequireIndependentSeed)){throw 'M6_VERIFY_SCOPE：唯讀驗證不得混入runtime選擇'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'probe-common.ps1')
# 已啟用集合是正式來源；Task11須新增另一條FUNCTIONAL chain，不能改判舊diagnostic。
$manifest=@(
    [pscustomobject]@{chain='native-seed-diagnostic';m6ProbeSchema=1;expectedMode='DIAGNOSTIC';phases=@('baseline','save-reopen')}
    [pscustomobject]@{chain='catalog-readback';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('intent','allocation','atomic-move-readback','restart')}
    [pscustomobject]@{chain='native-seed-functional';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('construct','reopen')}
    [pscustomobject]@{chain='intent-allocation';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('intent','allocation','restart')}
    [pscustomobject]@{chain='partial-overworld';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('intent','catalog','partial','restart')}
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
if(-not $VerifySummary -and -not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "M6_GRADLE_MISSING：$Gradle"}
$head=(git -C $project rev-parse HEAD).Trim()
if($ExpectedHead){$head=$ExpectedHead}
if(-not $VerifySummary) {
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
}
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
function Assert-GameplayReceipt($receipt,$chain,$phase,$owned,$state) {
    if($receipt.m6ProbeSchema -ne 1 -or $receipt.expectedMode -cne 'FUNCTIONAL' -or $receipt.chain -cne $chain -or $receipt.phase -cne $phase -or $receipt.nonce -cne $owned.nonce -or $receipt.startupNonce -cne $state.StartupNonce){throw 'M6_FORMAL_RECEIPT_IDENTITY'}
    if($receipt.status -cne 'PASS' -or @($receipt.failures).Count -or -not $receipt.functionalPassed){throw 'M6_FORMAL_BEHAVIOR_FAILED'}
    if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds){throw 'M6_FORMAL_RUNTIME_SCOPE'}
    if(-not $receipt.formalLeverEntry -or -not $receipt.formalBatch -or -not $receipt.formalSelected -or -not $receipt.journalChecked -or $receipt.sourcePreset -cne 'normal'){throw 'M6_FORMAL_ENTRY_REQUIRED'}
    foreach($field in @('providerACreateRejected','providerAResolveRejected','providerBLoadRejected','providerBPreflightRejected','providerAuthorityUnchanged','providerRegistrationRestored')) {
        if($receipt.$field -ne $true){throw "M6_FORMAL_PROVIDER_BASELINE:$field"}
    }
    if($phase -ceq 'intent') {
        $aSame=($receipt.providerABefore | ConvertTo-Json -Compress) -ceq ($receipt.providerAAfter | ConvertTo-Json -Compress)
        $b1Same=($receipt.providerBeforeHashes | ConvertTo-Json -Compress) -ceq ($receipt.providerB1AfterHashes | ConvertTo-Json -Compress)
        $b2Same=($receipt.providerBeforeHashes | ConvertTo-Json -Compress) -ceq ($receipt.providerB2AfterHashes | ConvertTo-Json -Compress)
        $journalSame=(Get-FileHash -LiteralPath (Join-Path $state.PhaseEvidence 'provider-before-journal.dat')).Hash.ToLowerInvariant() -ceq $receipt.providerJournalSha256
        if(-not $aSame -or -not $b1Same -or -not $b2Same -or -not $journalSame){throw 'M6_FORMAL_PROVIDER_ORIGINAL_CHANGED'}
    }
    if($receipt.allocationToken -notmatch '^[0-9a-f]{64}$' -or $receipt.sourceFingerprint -notmatch '^[0-9a-f]{64}$' -or $receipt.targetFingerprint -notmatch '^[0-9a-f]{64}$' -or $receipt.rootWorldSeed -ceq $receipt.sourceRootWorldSeed -or $receipt.profileId -cne 'quantumchamber:random_vanilla_v1'){throw 'M6_FORMAL_FROZEN_INTENT'}
    if($phase -ceq 'intent') {if($receipt.allocationCount -ne 0 -or $receipt.allocationChecked -or -not $receipt.worldAndStorageAbsent){throw 'M6_FORMAL_INTENT_ORDER'}}
    else {if($receipt.allocationCount -ne 1 -or -not $receipt.allocationChecked){throw 'M6_FORMAL_ALLOCATION_ORDER'}}
    if($phase -ceq 'allocation' -and -not $receipt.worldAndStorageAbsent){throw 'M6_FORMAL_STORAGE_EARLY'}
    if($phase -ceq 'catalog' -and (-not $receipt.catalogChecked -or $receipt.catalogAvailability -cne 'DISABLED' -or -not $receipt.worldAndStorageAbsent -or $receipt.discoveryCount -ne 0)){throw 'M6_FORMAL_CATALOG_ORDER'}
    if($phase -ceq 'partial' -and (-not $receipt.loadWindow -or $receipt.runtimeState -cne 'MATERIALIZING' -or $receipt.active -or $receipt.discoveryCount -ne 0 -or -not $receipt.catalogChecked -or $receipt.catalogAvailability -cne 'EAGER_ENABLED' -or -not $receipt.partialStorageVerified -or $receipt.chunkRequests -ne 1 -or $receipt.partialElapsedMs -ge 60000)){throw 'M6_FORMAL_PARTIAL_WINDOW'}
    if($phase -ceq 'restart' -and (-not $receipt.active -or $receipt.discoveryCount -ne 1 -or -not $receipt.catalogChecked -or $receipt.catalogAvailability -cne 'EAGER_ENABLED' -or $receipt.nativeSeed -cne $receipt.rootWorldSeed -or -not $receipt.stopRequested -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen)){throw 'M6_FORMAL_RESTART_FAILED'}
    if($chain -ceq 'partial-overworld' -and $phase -ceq 'restart' -and (-not $receipt.partialProtectedSamplesExact)){throw 'M6_FORMAL_PARTIAL_RESTART'}
    if($chain -ceq 'partial-overworld' -and $phase -cin @('partial','restart')) { Assert-PartialSamples $receipt }
}
function Assert-PartialSamples($receipt) {
    $expected=@(foreach($x in 0,5,10,15){foreach($z in 0,5,10,15){"$x,-64,$z=minecraft:bedrock{}"}})
    if($receipt.storageSampleKind -cne 'VANILLA_BOTTOM_BEDROCK_V1' -or $receipt.storageSampleCount -ne 16 -or $receipt.protectedChunkX -ne 0 -or $receipt.protectedChunkZ -ne 0 -or $receipt.protectedChunkStatus -cne 'minecraft:full' -or ($receipt.protectedSamples | ConvertTo-Json -Compress) -cne ($expected | ConvertTo-Json -Compress)){throw 'PARTIAL_PROTECTED_SAMPLE_EXACT'}
    $native=$receipt.partialNativeConsumers
    if(-not $native.exact -or $native.seed -cne $receipt.rootWorldSeed -or $native.worldKey -cne "quantumchamber:universe/$($receipt.uuid)/overworld" -or $native.targetFingerprint -cne $receipt.targetFingerprint -or $native.settingsKey -cne 'minecraft:overworld' -or @($native.samples).Count -ne 4){throw 'PARTIAL_NATIVE_CONSUMER_EXACT'}
    foreach($sample in $native.samples){if($sample -cnotmatch '^[0-9a-f]{1,16}:minecraft:[a-z0-9_/]+$'){throw 'PARTIAL_NATIVE_CONSUMER_SAMPLE'}}
}
function Assert-PartialStorage($receipt,$owned) {
    $uuid=[guid]::Parse($receipt.uuid).ToString()
    $expected=[IO.Path]::GetFullPath((Join-Path $owned.root "world/dimensions/quantumchamber/universe/$uuid/overworld"))
    if([IO.Path]::GetFullPath($receipt.storage) -cne $expected -or -not(Test-Path -LiteralPath $expected -PathType Container)){throw 'M6_PARTIAL_STORAGE_IDENTITY'}
    $rows=@($receipt.partialHashes.PSObject.Properties)
    if(-not $rows.Count -or 'region/r.0.0.mca' -notin $rows.Name){throw 'M6_PARTIAL_STORAGE_EMPTY'}
    foreach($row in $rows) {
        $path=[IO.Path]::GetFullPath((Join-Path $expected $row.Name))
        if(-not $path.StartsWith($expected+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'M6_PARTIAL_STORAGE_PATH'}
        $ancestor=$path
        while($ancestor.Length -ge $owned.root.Length) {
            if((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'M6_PARTIAL_STORAGE_REPARSE'}
            $ancestor=[IO.Path]::GetDirectoryName($ancestor)
        }
        if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.Value){throw 'M6_PARTIAL_STORAGE_HASH'}
    }
    if(@(Get-ChildItem -LiteralPath $expected -File -Recurse).Count -ne $rows.Count){throw 'M6_PARTIAL_STORAGE_FILE_SET'}
    return $rows.Count
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
function Assert-CatalogReceipt($receipt,$phase,$owned,$state,[bool]$Historical=$false) {
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
        if($Historical) {
            if($name -ceq 'catalogHash' -and -not $hasCatalog){if($receipt.$name -cne 'ABSENT'){throw 'M6_CATALOG_EARLY_FILE'}}
            elseif($receipt.$name -cnotmatch '^[0-9a-f]{64}$'){throw 'M6_CATALOG_AUTHORITY_HASH'}
            continue
        }
        if($name -ceq 'catalogHash' -and -not $hasCatalog){
            if($receipt.$name -cne 'ABSENT' -or (Test-Path -LiteralPath $path)){throw 'M6_CATALOG_EARLY_FILE'}
        } elseif((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $receipt.$name){throw 'M6_CATALOG_AUTHORITY_HASH'}
    }
}
function Assert-SavedNativeSummary($native,$receiptOverrides=@{},$preservationOverrides=@{}) {
    $phaseTotal=(@($manifest | ForEach-Object {$_.phases.Count}) | Measure-Object -Sum).Sum
    if($native.status -cne 'PASS' -or $native.head -cne $head -or -not $native.allEnabled -or $native.requireIndependentSeed -or $native.expectedMode -cne 'PER_CHAIN' -or $native.phaseCount -ne $phaseTotal -or $native.phaseMinutes -ne 3 -or $native.idleSeconds -ne 90 -or $native.blockMinutes -ne 60 -or $native.functionalAssertionsRemainRed -ne $true){throw 'M6_SAVED_SUMMARY'}
    if(@($native.enabledManifest).Count -ne $manifest.Count -or @($native.chains).Count -ne $manifest.Count -or @($native.phases).Count -ne $phaseTotal -or ($native.selectedChains -join ',') -cne ($manifest.chain -join ',')){throw 'M6_SAVED_MANIFEST'}
    if(@($native.phases.startupNonce | Sort-Object -Unique).Count -ne $phaseTotal -or @($native.chains.root | Sort-Object -Unique).Count -ne $manifest.Count){throw 'M6_SAVED_OWNER_REUSED'}
    $offset=0
    for($i=0;$i -lt $manifest.Count;$i++) {
        $spec=$manifest[$i];$entry=$native.enabledManifest[$i];$chainRow=$native.chains[$i]
        if($entry.chain -cne $spec.chain -or $entry.expectedMode -cne $spec.expectedMode -or $entry.m6ProbeSchema -ne 1 -or ($entry.phases -join ',') -cne ($spec.phases -join ',') -or $chainRow.chain -cne $spec.chain -or $chainRow.expectedMode -cne $spec.expectedMode -or ($chainRow.phases -join ',') -cne ($spec.phases -join ',')){throw 'M6_SAVED_MANIFEST'}
        if((Get-FileHash -LiteralPath $chainRow.preservation).Hash -cne $chainRow.preservationSha256){throw 'M6_SAVED_PRESERVATION_HASH'}
        $preserve=if($preservationOverrides.ContainsKey($i)){$preservationOverrides[$i]}else{Get-Content -LiteralPath $chainRow.preservation -Raw | ConvertFrom-Json -DateKind String}
        if($preserve.root -cne $chainRow.root -or $preserve.strategy -cne 'NEW_OWNED_ROOT' -or $preserve.existingWorldsMoved -ne 0 -or $preserve.existingWorldsChanged -or -not $preserve.retainedRoot -or -not $preserve.lockReleased -or $preserve.normalStop -ne ($spec.chain -cne 'catalog-readback')){throw 'M6_SAVED_PRESERVATION'}
        $receipts=@();$pids=@()
        foreach($phaseName in $spec.phases) {
            $row=$native.phases[$offset]
            if((Get-FileHash -LiteralPath $row.receipt).Hash -cne $row.receiptSha256){throw 'M6_SAVED_RECEIPT_HASH'}
            $receipt=if($receiptOverrides.ContainsKey($offset)){$receiptOverrides[$offset]}else{Get-Content -LiteralPath $row.receipt -Raw | ConvertFrom-Json -DateKind String}
            if($row.status -cne 'PASS' -or $row.m6ProbeSchema -ne 1 -or $row.chain -cne $spec.chain -or $row.phase -cne $phaseName -or $row.expectedMode -cne $spec.expectedMode -or $row.root -cne $chainRow.root -or $receipt.status -cne 'PASS' -or $receipt.m6ProbeSchema -ne 1 -or $receipt.chain -cne $spec.chain -or $receipt.phase -cne $phaseName -or $receipt.expectedMode -cne $spec.expectedMode -or $receipt.pid -ne $row.javaPid -or $receipt.root -cne $row.root -or $receipt.startupNonce -cne $row.startupNonce -or $receipt.nonce -cne $row.nonce -or -not $row.lockReleased){throw 'M6_SAVED_RECEIPT_IDENTITY'}
            if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-[DateTimeOffset]::Parse($row.javaStartTimeUtc)).TotalSeconds) -gt 1){throw 'M6_SAVED_JVM_START'}
            $halt=$spec.chain -ceq 'catalog-readback' -or ($spec.chain -cin @('intent-allocation','partial-overworld') -and $phaseName -cne 'restart')
            if($row.normalStop -eq $halt -or $row.javaExitCode -ne $(if($halt){77}else{0}) -or ($halt -and $row.gradleExitCode -eq 0) -or (-not $halt -and $row.gradleExitCode -ne 0)){throw 'M6_SAVED_EXIT'}
            $owned=[pscustomobject]@{nonce=$row.nonce;root=$row.root};$state=[pscustomobject]@{StartupNonce=$row.startupNonce;PhaseEvidence=[IO.Path]::GetDirectoryName($row.receipt)}
            switch -CaseSensitive ($spec.chain) {
                'native-seed-diagnostic' {Assert-DiagnosticReceipt $receipt $phaseName $row.nonce $row.startupNonce}
                'catalog-readback' {Assert-CatalogReceipt $receipt $phaseName $owned $state $true}
                'native-seed-functional' {Assert-SeedReceipt $receipt $phaseName $owned $state}
                default {Assert-GameplayReceipt $receipt $spec.chain $phaseName $owned $state}
            }
            $receipts+=,$receipt;$pids+=,$row.javaPid;$offset++
        }
        if(@($pids | Sort-Object -Unique).Count -ne $spec.phases.Count){throw 'M6_SAVED_JVM_REUSED'}
        if($spec.chain -ceq 'native-seed-diagnostic') {
            if($receipts[0].catalogSha256 -cne $receipts[1].catalogSha256 -or -not $receipts[1].diagnosticAssertions.reopenExact){throw 'M6_SAVED_DIAGNOSTIC_REOPEN'}
        } elseif($spec.chain -ceq 'native-seed-functional') {
            for($w=0;$w -lt 2;$w++){foreach($field in @('worldKey','seed','frozenStructures','accessorStructures','settingsKey','samples','positivePosition')) {
                if(($receipts[0].worlds[$w].$field | ConvertTo-Json -Compress) -cne ($receipts[1].worlds[$w].$field | ConvertTo-Json -Compress)){throw 'M6_SAVED_SEED_REOPEN'}
            }}
        } else {
            $fields=if($spec.chain -ceq 'catalog-readback'){@('uuid','session','allocationToken','rootWorldSeed','targetFingerprint','profileId','profileVersion','journalHash')}else{@('session','uuid','worldKey','allocationToken','rootWorldSeed','sourceRootWorldSeed','sourceFingerprint','targetFingerprint','profileId','storage')}
            foreach($field in $fields){if(@($receipts | ForEach-Object {$_.$field} | Sort-Object -Unique).Count -ne 1){throw "M6_SAVED_RESTART_IDENTITY:$field"}}
            if($spec.chain -ceq 'catalog-readback' -and ($receipts[1].allocationHash -cne $receipts[2].allocationHash -or $receipts[2].allocationHash -cne $receipts[3].allocationHash -or $receipts[2].catalogHash -cne $receipts[3].catalogHash)){throw 'M6_SAVED_CATALOG_REOPEN'}
            if($spec.chain -ceq 'catalog-readback'){Assert-CatalogReceipt $receipts[-1] 'restart' $owned $state}
            if($spec.chain -ceq 'partial-overworld') {
                $before=Get-Content -LiteralPath (Join-Path $chainRow.root 'partial-before-restart.json') -Raw | ConvertFrom-Json -DateKind String
                $partialPath=Join-Path $chainRow.root 'partial-storage.json'
                if($before.status -cne 'PASS' -or -not $before.beforeRestart -or $before.root -cne $chainRow.root -or $before.nonce -cne $receipts[2].nonce -or $before.fileCount -ne @($receipts[2].partialHashes.PSObject.Properties).Count -or $before.partialManifestSha256 -cne (Get-FileHash -LiteralPath $partialPath).Hash -or ($receipts[2].protectedSamples | ConvertTo-Json -Compress) -cne ($receipts[3].protectedSamples | ConvertTo-Json -Compress) -or ($receipts[2].partialNativeConsumers | ConvertTo-Json -Compress) -cne ($receipts[3].partialNativeConsumers | ConvertTo-Json -Compress)){throw 'M6_SAVED_PARTIAL_REOPEN'}
            }
        }
    }
}
if($VerifySummary) {
    $native=Get-Content -LiteralPath $VerifySummary -Raw | ConvertFrom-Json -DateKind String
    Assert-SavedNativeSummary $native
    $checks=0
    if($SelfTest) {
        for($index=0;$index -lt $manifest.Count;$index++) {
            foreach($field in @('expectedMode','m6ProbeSchema','chain','phases')) {
                $bad=$native | ConvertTo-Json -Depth 30 | ConvertFrom-Json -DateKind String
                $bad.enabledManifest[$index].$field=if($field -ceq 'm6ProbeSchema'){2}elseif($field -ceq 'phases'){@('WRONG')}else{'WRONG'}
                $rejected=$false;try{Assert-SavedNativeSummary $bad}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_MANIFEST_NEGATIVE_MISSED'};$checks++
            }
            $bad=Get-Content -LiteralPath $native.chains[$index].preservation -Raw | ConvertFrom-Json -DateKind String;$bad.retainedRoot=$false
            $rejected=$false;try{Assert-SavedNativeSummary $native @{} @{$index=$bad}}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_RESTORE_NEGATIVE_MISSED'};$checks++
        }
        for($index=0;$index -lt $native.phases.Count;$index++) {
            foreach($field in @('expectedMode','m6ProbeSchema','chain','phase','status')) {
                $bad=Get-Content -LiteralPath $native.phases[$index].receipt -Raw | ConvertFrom-Json -DateKind String
                $bad.$field=if($field -ceq 'm6ProbeSchema'){2}else{'WRONG'}
                $rejected=$false;try{Assert-SavedNativeSummary $native @{$index=$bad}}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_RECEIPT_NEGATIVE_MISSED'};$checks++
            }
            $original=Get-Content -LiteralPath $native.phases[$index].receipt -Raw | ConvertFrom-Json -DateKind String
            if($original.chain -ceq 'partial-overworld' -and $original.phase -cin @('partial','restart')) {
                foreach($variant in @('kind','count','missing','duplicate','y','air','full','seed','settings','owner','fingerprint')) {
                    $bad=$original | ConvertTo-Json -Depth 30 | ConvertFrom-Json -DateKind String
                    switch($variant) {
                        'kind' {$bad.storageSampleKind='WRONG'}
                        'count' {$bad.storageSampleCount=48}
                        'missing' {$bad.protectedSamples=@($bad.protectedSamples)[0..14]}
                        'duplicate' {$bad.protectedSamples[1]=$bad.protectedSamples[0]}
                        'y' {$bad.protectedSamples[0]='0,-32,0=minecraft:bedrock{}'}
                        'air' {$bad.protectedSamples[0]='0,-64,0=minecraft:air{}'}
                        'full' {$bad.protectedChunkStatus='minecraft:features'}
                        'seed' {$bad.partialNativeConsumers.seed='WRONG'}
                        'settings' {$bad.partialNativeConsumers.settingsKey='minecraft:amplified'}
                        'owner' {$bad.partialNativeConsumers.worldKey='minecraft:overworld'}
                        'fingerprint' {$bad.partialNativeConsumers.targetFingerprint='0'*64}
                    }
                    $rejected=$false;try{Assert-PartialSamples $bad}catch{$rejected=$true}
                    if(-not $rejected){throw "M6_PARTIAL_NEGATIVE_MISSED:$variant"};$checks++
                }
            }
            $bad=$native | ConvertTo-Json -Depth 30 | ConvertFrom-Json -DateKind String;$bad.phases[$index].receiptSha256='0'*64
            $rejected=$false;try{Assert-SavedNativeSummary $bad}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_HASH_NEGATIVE_MISSED'};$checks++
        }
    }
    Write-Output "M6_NATIVE_VERIFIED PASS chains=$($manifest.Count) phases=$($native.phaseCount) negativeChecks=$checks HEAD=$head"
    return
}
try {
    foreach($chainSpec in $selected){
        if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
        $owned=New-OwnedRoot -Context $context -Suffix $chainSpec.chain
        if($chainSpec.chain -ceq 'native-seed-functional'){'level-type=minecraft:amplified' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        if($chainSpec.chain -cin @('intent-allocation','partial-overworld')){'level-type=minecraft:normal' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        $chainStart=$results.Count
        foreach($phase in $chainSpec.phases){
            if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
            if($chainSpec.chain -ceq 'partial-overworld' -and $phase -ceq 'restart') {
                $partial=Get-Content -LiteralPath (Join-Path $owned.root 'partial-storage.json') -Raw | ConvertFrom-Json -DateKind String
                $verifiedFiles=Assert-PartialStorage $partial $owned
                [ordered]@{status='PASS';nonce=$owned.nonce;root=$owned.root;beforeRestart=$true;fileCount=$verifiedFiles;partialManifestSha256=(Get-FileHash -LiteralPath (Join-Path $owned.root 'partial-storage.json')).Hash} |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $owned.root 'partial-before-restart.json') -Encoding utf8
            }
            $state=Invoke-OwnedPhase -Context $context -Nonce $owned.nonce -Phase $phase -Root $owned.root -ExtraProperties ([ordered]@{Chain=$chainSpec.chain;ExpectedMode=$chainSpec.expectedMode})
            $functional=$chainSpec.chain -ceq 'catalog-readback'
            $gameplay=$chainSpec.chain -cin @('intent-allocation','partial-overworld')
            $haltPhase=$functional -or ($gameplay -and $phase -cne 'restart')
            $receiptName=if($haltPhase){'halt.json'}else{'final.json'}
            $receiptPath=Join-Path $state.PhaseEvidence $receiptName
            if($gameplay -and -not(Test-Path -LiteralPath $receiptPath) -and (Test-Path -LiteralPath (Join-Path $state.PhaseEvidence 'halt.json'))) {
                $failed=Get-Content -LiteralPath (Join-Path $state.PhaseEvidence 'halt.json') -Raw | ConvertFrom-Json -DateKind String
                throw "M6_FORMAL_BEHAVIOR_FAILED：$($failed.failures -join '; ')"
            }
            if(-not(Test-Path -LiteralPath $receiptPath)){throw "M6_RECEIPT_MISSING：$(Get-PhaseFailureNote -Context $context -State $state)"}
            $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json -DateKind String
            if($gameplay -and $receipt.status -cne 'PASS'){throw "M6_FORMAL_BEHAVIOR_FAILED：$($receipt.failures -join '; ')"}
            if($haltPhase){
                if($state.Launcher.ExitCode -eq 0 -or $state.JavaProcess.ExitCode -ne 77 -or (Test-Path -LiteralPath (Join-Path $state.PhaseEvidence 'final.json'))){throw 'M6_CATALOG_HALT_MISMATCH'}
                $gradleOutput=(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stdout.log') -Encoding $context.GradleEncoding)+(Get-SharedText -Path (Join-Path $state.PhaseEvidence 'stderr.log') -Encoding $context.GradleEncoding)
                if($gradleOutput -notmatch 'finished with non-zero exit value 77'){throw 'M6_CATALOG_GRADLE_EXIT_MISMATCH'}
                if($gameplay){Assert-GameplayReceipt $receipt $chainSpec.chain $phase $owned $state}
                else {Assert-CatalogReceipt $receipt $phase $owned $state}
            } else {
                if($state.Launcher.ExitCode -ne 0 -or $state.JavaProcess.ExitCode -ne 0){throw 'M6_PHASE_EXIT_NONZERO'}
                if($gameplay){Assert-GameplayReceipt $receipt $chainSpec.chain $phase $owned $state}
                elseif($chainSpec.chain -ceq 'native-seed-functional'){
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
            if($gameplay -and $phase -ceq 'partial'){Assert-PartialStorage $receipt $owned | Out-Null}
            if([Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-$state.JavaStart).TotalSeconds) -gt 1){throw 'M6_JVM_STARTTIME_MISMATCH'}
            if(-not $state.JavaProcess.HasExited){throw 'M6_JVM_STILL_LIVE'}
            $lock=[IO.File]::Open((Join-Path $owned.root 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$lock.Dispose()
            $logs=Copy-OwnedLogs -State $state
            foreach($key in @('classload','latest')){if(-not $logs[$key].copied){throw "M6_RUNTIME_LOG_MISSING：$key"}}
            $runtime=Get-SharedText -Path (Join-Path $state.PhaseEvidence 'latest.log') -Encoding ([Text.Encoding]::UTF8)
            if([regex]::Matches($runtime,'M6_NATIVE_BEGIN chain=').Count -ne 1 -or [regex]::Matches($runtime,'M6_NATIVE_END chain=').Count -ne 1){throw 'M6_RUNTIME_LOG_IDENTITY_MISMATCH'}
            $row=[ordered]@{m6ProbeSchema=1;status='PASS';chain=$chainSpec.chain;phase=$phase;expectedMode=$chainSpec.expectedMode;nonce=$owned.nonce;startupNonce=$state.StartupNonce;root=$owned.root;javaPid=$state.JavaId;javaStartTimeUtc=$state.JavaStart.ToString('o');gradleExitCode=$state.Launcher.ExitCode;javaExitCode=$state.JavaProcess.ExitCode;normalStop=(-not $haltPhase);lockReleased=$true;receipt=$receiptPath;receiptSha256=(Get-FileHash -LiteralPath $receiptPath).Hash;auxiliaryLogs=$logs}
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'launcher.json') -Encoding utf8
            $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $state.PhaseEvidence 'phase.json') -Encoding utf8
            $results.Add($row);$state.JavaProcess.Dispose();$state.Launcher.Dispose()
            Write-Output "M6_PHASE PASS chain=$($chainSpec.chain) phase=$phase pid=$($row.javaPid) mode=$($chainSpec.expectedMode) ROOT=$($owned.root)"
        }
        $current=@($results.ToArray() | Select-Object -Skip $chainStart)
        if($gameplay) {
            $proofs=@($current | ForEach-Object {Get-Content -LiteralPath $_.receipt -Raw | ConvertFrom-Json -DateKind String})
            foreach($field in @('session','uuid','worldKey','allocationToken','rootWorldSeed','sourceRootWorldSeed','sourceFingerprint','targetFingerprint','profileId','storage')) {
                if(@($proofs | ForEach-Object {$_.$field} | Sort-Object -Unique).Count -ne 1){throw "M6_FORMAL_RESTART_IDENTITY：$field"}
            }
            if($chainSpec.chain -ceq 'partial-overworld' -and ((($proofs[2].protectedSamples | ConvertTo-Json -Compress) -cne ($proofs[3].protectedSamples | ConvertTo-Json -Compress)) -or (($proofs[2].partialNativeConsumers | ConvertTo-Json -Compress) -cne ($proofs[3].partialNativeConsumers | ConvertTo-Json -Compress)))){throw 'M6_FORMAL_PARTIAL_PROTECTED_CHANGED'}
        }
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
        $chains.Add([ordered]@{chain=$chainSpec.chain;expectedMode=$chainSpec.expectedMode;phases=@($chainSpec.phases);root=$owned.root;preservation=(Join-Path $owned.root 'preservation.json');preservationSha256=(Get-FileHash -LiteralPath (Join-Path $owned.root 'preservation.json')).Hash})
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
