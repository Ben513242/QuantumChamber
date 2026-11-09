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
    [pscustomobject]@{chain='same-seed-restart';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('source','new','restart')}
    [pscustomobject]@{chain='same-seed-amplified-restart';m6ProbeSchema=1;expectedMode='FUNCTIONAL';phases=@('source','new','restart')}
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
    if($receipt.armingPolicy -ne 2 -or $receipt.policyVersion -ne 2 -or $receipt.familyCapability -ne 0){throw 'M6_FORMAL_POLICY_TWO'}
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
# 固定 1.21 generation API 表；與 Java 實際回傳逐欄核對，不採地形 hash baseline。
function Get-CarversMetadataReference {
    return ('{"carversDependencyRadius":9,"centerX":1024,"stages":[{"accumulated":[],"maxLevel":0,"writeRadius":-1,"direct":[],"status":"minecraft:empty"},{"accumulated":["minecraft:empty"],"maxLevel":0,"writeRadius":-1,"direct":["minecraft:empty"],"status":"minecraft:structure_starts"},{"accumulated":["minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":8,"writeRadius":-1,"direct":["minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:structure_references"},{"accumulated":["minecraft:structure_references","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":8,"writeRadius":-1,"direct":["minecraft:structure_references","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:biomes"},{"accumulated":["minecraft:biomes","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":9,"writeRadius":0,"direct":["minecraft:biomes","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:noise"},{"accumulated":["minecraft:noise","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":9,"writeRadius":0,"direct":["minecraft:noise","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:surface"},{"accumulated":["minecraft:surface","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":9,"writeRadius":0,"direct":["minecraft:surface","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:carvers"},{"accumulated":["minecraft:carvers","minecraft:carvers","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":10,"writeRadius":1,"direct":["minecraft:carvers","minecraft:carvers","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"status":"minecraft:features"},{"accumulated":["minecraft:features","minecraft:carvers","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":10,"writeRadius":-1,"direct":["minecraft:features"],"status":"minecraft:initialize_light"},{"accumulated":["minecraft:initialize_light","minecraft:initialize_light","minecraft:carvers","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":11,"writeRadius":-1,"direct":["minecraft:initialize_light","minecraft:initialize_light"],"status":"minecraft:light"},{"accumulated":["minecraft:light","minecraft:initialize_light","minecraft:carvers","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":11,"writeRadius":-1,"direct":["minecraft:light","minecraft:biomes"],"status":"minecraft:spawn"},{"accumulated":["minecraft:spawn","minecraft:initialize_light","minecraft:carvers","minecraft:biomes","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts","minecraft:structure_starts"],"maxLevel":11,"writeRadius":-1,"direct":["minecraft:spawn"],"status":"minecraft:full"}],"formula":"Rc+Rf+W+1","centerZ":1024,"fullDependencyRadius":11,"haloRadius":22,"maximumNonnegativeWriteRadius":1}' | ConvertFrom-Json)
}
function Get-ProfileListHash([string[]]$Values) {
    $text=ConvertTo-Json -InputObject $Values -Compress
    $text=$text.Replace('=','\u003d').Replace('<','\u003c').Replace('>','\u003e').Replace('&','\u0026').Replace("'",'\u0027').Replace([string][char]0x2028,'\u2028').Replace([string][char]0x2029,'\u2029')
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text))).ToLowerInvariant()
}
function Read-ProfileJsonLink([string]$Base,[string]$Relative,[string]$Expected,[string]$Sha,[switch]$Binary) {
    if($Relative -cne $Expected -or $Sha -cnotmatch '^[0-9a-f]{64}$'){throw 'M6_CARVERS_LINK_PATH'}
    $path=[IO.Path]::GetFullPath((Join-Path $Base $Relative))
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'M6_CARVERS_LINK_MISSING'}
    $cursor=$path
    while($cursor){if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'M6_CARVERS_LINK_REPARSE'};$cursor=[IO.Path]::GetDirectoryName($cursor)}
    if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $Sha){throw 'M6_CARVERS_LINK_SHA'}
    if($Binary){return}
    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -DateKind String
}
function Assert-ProfileSample($Sample,[int]$Offset,[string]$Status) {
    try {
        if(@($Sample.rows).Count -ne 1440 -or $Sample.sampleCount -ne 1440 -or $Sample.chunkStatus -cne $Status){throw 'M6_CARVERS_SAMPLE_COUNT_STAGE'}
        $i=0
        for($x=$Offset;$x -lt $Offset+16;$x+=3){for($z=$Offset;$z -lt $Offset+16;$z+=3){for($y=-64;$y -lt 256;$y+=8){
            $row=$Sample.rows[$i]
            if($row.index -ne $i -or $row.x -ne $x -or $row.y -ne $y -or $row.z -ne $z -or $row.block -cnotmatch '^Block\{minecraft:' -or $row.biome -cnotmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$'){throw 'M6_CARVERS_SAMPLE_COORDINATES'}
            $i++
        }}}
        if((Get-ProfileListHash ([string[]]$Sample.rows.block)) -cne $Sample.blockHash -or (Get-ProfileListHash ([string[]]$Sample.rows.biome)) -cne $Sample.biomeHash){throw 'M6_CARVERS_SAMPLE_HASH'}
        if($Status -ceq 'minecraft:carvers' -and ($Sample.chunkClass -cne 'net.minecraft.world.chunk.ProtoChunk' -or $Sample.biomeKind -cne 'STORED_CHUNK_NOISE_BIOME')){throw 'M6_CARVERS_SAMPLE_KIND'}
    } catch {throw "M6_CARVERS_SAMPLE:$($_.Exception.Message)"}
}
function Assert-CarversMetadata($Metadata) {
    $reference=Get-CarversMetadataReference
    if(@($Metadata.stages).Count -ne 12){throw 'M6_CARVERS_METADATA_STAGES'}
    for($i=0;$i -lt 12;$i++) {
        foreach($key in @('status','writeRadius','maxLevel')){if($Metadata.stages[$i].$key -cne $reference.stages[$i].$key){throw "M6_CARVERS_METADATA:$key"}}
        foreach($key in @('direct','accumulated')){if(($Metadata.stages[$i].$key -join '|') -cne ($reference.stages[$i].$key -join '|')){throw "M6_CARVERS_METADATA:$key"}}
    }
    $rc=($Metadata.stages | Where-Object status -CEQ 'minecraft:carvers').maxLevel
    $rf=($Metadata.stages | Where-Object status -CEQ 'minecraft:full').maxLevel
    $write=($Metadata.stages.writeRadius | Measure-Object -Maximum).Maximum
    foreach($key in @('carversDependencyRadius','fullDependencyRadius','maximumNonnegativeWriteRadius','haloRadius','centerX','centerZ','formula')){if($Metadata.$key -cne $reference.$key){throw "M6_CARVERS_METADATA:$key"}}
    if($rc+$rf+$write+1 -ne $Metadata.haloRadius){throw 'M6_CARVERS_HALO_FORMULA'}
}
function Assert-CarversHalo($Halo,[string]$WorldKey,[string]$Root,[bool]$Virgin) {
    $h=(Get-CarversMetadataReference).haloRadius
    $region=if($WorldKey -ceq 'minecraft:overworld'){Join-Path $Root 'world/region'}else{Join-Path $Root ('world/dimensions/'+$WorldKey.Replace(':','/')+'/region')}
    $region=[IO.Path]::GetFullPath($region)
    if($Halo.status -cne 'PASS' -or $Halo.haloRadius -ne $h -or $Halo.virginRequired -ne $Virgin -or $Halo.worldKey -cne $WorldKey -or $Halo.region -cne $region -or @($Halo.cells).Count -ne (2*$h+1)*(2*$h+1) -or $Halo.nearestRawFullDistanceLowerBoundExclusive -ne $h -or $Halo.rawFullClaim -cne 'HALO_ONLY_LOWER_BOUND_NOT_GLOBAL_NEAREST'){throw 'M6_CARVERS_HALO_SCHEMA'}
    $allowed=@('ABSENT','minecraft:empty','minecraft:structure_starts','minecraft:structure_references','minecraft:biomes','minecraft:noise','minecraft:surface','minecraft:carvers')
    $i=0
    for($x=1024-$h;$x -le 1024+$h;$x++){for($z=1024-$h;$z -le 1024+$h;$z++){
        $cell=$Halo.cells[$i++];$file=Join-Path $region ("r.$($x -shr 5).$($z -shr 5).mca")
        if($cell.x -ne $x -or $cell.z -ne $z -or $cell.regionFile -cne $file -or $cell.canonicalNonReparse -ne $true -or $cell.headerValid -ne $true -or $cell.raw -cnotin $allowed -or (($cell.entryLocation -eq 0) -ne ($cell.raw -ceq 'ABSENT'))){throw 'M6_CARVERS_HALO_CELL'}
        if($Virgin -and ($cell.currentHolder -ne $false -or $cell.unloadingHolder -ne $false -or $cell.raw -cne 'ABSENT')){throw 'M6_CARVERS_HALO_VIRGIN'}
    }}
    $distances=@()
    foreach($holder in $Halo.liveHolders) {
        $distance=[Math]::Max([Math]::Abs($holder.x-1024),[Math]::Abs($holder.z-1024))
        if($holder.distance -ne $distance){throw 'M6_CARVERS_HOLDER_DISTANCE'}
        $full=$holder.worldChunk -or $holder.actualStatus -ceq 'minecraft:full' -or $holder.latestStatus -ceq 'minecraft:full'
        if($full){$distances+=$distance}
        if($distance -le $h -and ($full -or $holder.actualStatus -cnotin (@('NONE')+$allowed) -or $holder.latestStatus -cnotin (@('NONE')+$allowed))){throw 'M6_CARVERS_HALO_STATUS'}
    }
    if($distances.Count){$nearest=($distances|Measure-Object -Minimum).Minimum;if($nearest -le $h -or $Halo.nearestLiveFullDistance -ne $nearest -or $Halo.nearestLiveFull.distance -ne $nearest){throw 'M6_CARVERS_NEAREST_FULL'}}
    elseif($Halo.nearestLiveFullDistance -cne 'NONE_OBSERVED' -or $Halo.nearestLiveFull -cne 'NONE_OBSERVED'){throw 'M6_CARVERS_NEAREST_FULL'}
}
function Assert-ProfileDifferences($Before,$After,$Differences) {
    $expected=[Collections.Generic.List[object]]::new()
    for($i=0;$i -lt 1440;$i++){
        $a=$Before[$i];$b=$After[$i]
        if($a.block -cne $b.block -or $a.biome -cne $b.biome){$expected.Add([ordered]@{index=$i;x=$a.x;y=$a.y;z=$a.z;beforeBlock=$a.block;afterBlock=$b.block;beforeBiome=$a.biome;afterBiome=$b.biome})}
    }
    $actual=@($Differences)
    if($actual.Count -ne $expected.Count){throw 'M6_FINAL_DIFF_COMPLETE'}
    for($i=0;$i -lt $actual.Count;$i++){foreach($key in @('index','x','y','z','beforeBlock','afterBlock','beforeBiome','afterBiome')){if($actual[$i].$key -cne $expected[$i][$key]){throw 'M6_FINAL_DIFF_CONTENT'}}}
}
function Assert-ProfileFinalDiagnostic($Diagnostic,$Row,$Prior=$null) {
    if($Diagnostic.schema -ne 1 -or $Diagnostic.kind -cne 'T13_FINAL_DIAGNOSTIC' -or $Diagnostic.classificationScope -cne 'NO_HISTORICAL_MEMBERSHIP_CHECK'){throw 'M6_FINAL_DIAGNOSTIC_SCHEMA'}
    Assert-ProfileSample $Diagnostic.source 512 'minecraft:full';Assert-ProfileSample $Diagnostic.target 512 'minecraft:full'
    if($Diagnostic.source.worldKey -cne $Row.sourceIdentity -or $Diagnostic.target.worldKey -cne "quantumchamber:universe/$($Row.uuid)/overworld" -or $Diagnostic.source.seed -cne $Row.seed -or $Diagnostic.target.seed -cne $Row.seed -or $Diagnostic.source.blockHash -cne $Row.terrain -or $Diagnostic.target.blockHash -cne $Row.targetTerrain -or $Diagnostic.source.biomeHash -cne $Row.biomes -or $Diagnostic.target.biomeHash -cne $Row.targetBiomes -or $Row.biomes -cne $Row.targetBiomes){throw 'M6_FINAL_DIAGNOSTIC_IDENTITY_HASH'}
    Assert-ProfileDifferences $Diagnostic.source.rows $Diagnostic.target.rows $Diagnostic.differences
    if(@($Diagnostic.reviewItems).Count -ne 2){throw 'M6_FINAL_REVIEW_ITEMS'}
    for($i=0;$i -lt 2;$i++){$side=@('source','target')[$i];$item=$Diagnostic.reviewItems[$i];if($item.side -cne $side -or $item.blockHash -cne $Diagnostic.$side.blockHash -or $item.classification -cne 'UNRESOLVED' -or $item.novelty -cne 'NOT_CLASSIFIED' -or $item.reviewStatus -cne 'UNRESOLVED'){throw 'M6_FINAL_REVIEW_ITEMS'}}
    if($null -ne $Prior){Assert-ProfileDifferences $Prior.source.rows $Diagnostic.source.rows $Diagnostic.newToRestartDifferences.source;Assert-ProfileDifferences $Prior.target.rows $Diagnostic.target.rows $Diagnostic.newToRestartDifferences.target}
}
function Assert-ProfileStartupAuthority($Receipt,$Row,$State) {
    $captured=Read-ProfileJsonLink $State.PhaseEvidence $Row.startupAuthority.path 'carvers-startup-authority.json' $Row.startupAuthority.sha256
    foreach($key in @('root','nonce','chain','phase','startupNonce','pid','startTimeUtc')){if($captured.$key -cne $Receipt.$key){throw "M6_STARTUP_AUTHORITY_IDENTITY:$key"}}
    foreach($key in @('preset','session','sourceIdentity','uuid','seed','allocationToken','snapshot','sourceRecordSha256')){if($captured.$key -cne $Row.$key){throw "M6_STARTUP_AUTHORITY_IDENTITY:$key"}}
    if($captured.schema -ne 1 -or $captured.kind -cne 'CHECKED_STARTUP_SESSION_AUTHORITY' -or $captured.currentEqualsFlushed -ne $true -or $captured.frozenSelectedIntentChecked -ne $true -or $captured.notQuarantinedAndNoRollForward -ne $true -or $captured.startupState -cnotin @('COLLAPSING','PASSAGE_READY')){throw 'M6_STARTUP_AUTHORITY_SCHEMA'}
    Read-ProfileJsonLink $State.PhaseEvidence $captured.nbtPath 'carvers-startup-authority.nbt' $captured.nbtSha256 -Binary
    $authority=$Row.sessionAuthority
    if($authority.startupState -cne $captured.startupState -or $authority.notQuarantinedAndNoRollForward -ne $true){throw 'M6_SAME_SESSION_AUTHORITY'}
    if($authority.kind -ceq 'CHECKED_PASSAGE_ENDED') {
        if($Receipt.phase -cne 'restart' -or $authority.startupState -cne 'PASSAGE_READY' -or $authority.currentState -cne 'ABSENT' -or $authority.terminatedThisInstance -ne $true -or $authority.currentAndFlushedAbsent -ne $true){throw 'M6_SAME_SESSION_TERMINATION'}
    } elseif($authority.kind -cne 'LIVE_CHECKED' -or $authority.currentState -cin @('SEALED','ABSENT') -or $authority.currentAndFlushedAbsent -ne $false){throw 'M6_SAME_SESSION_AUTHORITY'}
}
function Assert-ProfileCarversLayer($Layer,$Receipt,$Row,$State,$Owned) {
    foreach($key in @('root','nonce','chain','phase','startupNonce','pid','startTimeUtc')){if($Layer.$key -cne $Receipt.$key){throw "M6_CARVERS_IDENTITY:$key"}}
    foreach($key in @('preset','session','sourceIdentity','uuid','seed','allocationToken','snapshot','sourceRecordSha256')){if($Layer.$key -cne $Row.$key){throw "M6_CARVERS_IDENTITY:$key"}}
    if($Layer.schema -ne 1 -or $Layer.layer -cne 'ACTUAL_CARVERS_V1' -or $Layer.status -cne 'PASS' -or $Layer.blocksExact -ne $true -or $Layer.biomesExact -ne $true -or $Layer.targetIdentity -cne "quantumchamber:universe/$($Row.uuid)/overworld" -or @($Layer.sides).Count -ne 2){throw 'M6_CARVERS_LAYER_SCHEMA'}
    Assert-CarversMetadata $Layer.generationMetadata
    if($Layer.startupAuthority.path -cne $Row.startupAuthority.path -or $Layer.startupAuthority.sha256 -cne $Row.startupAuthority.sha256){throw 'M6_STARTUP_AUTHORITY_LINK'}
    Assert-ProfileStartupAuthority $Receipt $Row $State
    $source=Read-ProfileJsonLink $State.PhaseEvidence $Layer.sourceFinal.path '../source/final.json' $Layer.sourceFinal.sha256
    if($source.phase -cne 'source' -or $source.status -cne 'PASS' -or $source.root -cne $Receipt.root -or $source.nonce -cne $Receipt.nonce -or $source.chain -cne $Receipt.chain -or $source.startupNonce -cne $Layer.sourceFinal.startupNonce -or $source.pid -ne $Layer.sourceFinal.pid){throw 'M6_CARVERS_SOURCE_FINAL'}
    foreach($key in @('preset','session','sourceIdentity','uuid','seed','allocationToken','snapshot','sourceRecordSha256')){if($source.variants[0].$key -cne $Row.$key){throw 'M6_CARVERS_SOURCE_VARIANT'}}
    $nbt=Join-Path $Owned.root "same-seed-$($Row.preset)-source.nbt"
    if((Get-FileHash -LiteralPath $nbt).Hash.ToLowerInvariant() -cne $Layer.sourceRecordSha256){throw 'M6_CARVERS_SOURCE_NBT_SHA'}
    for($i=0;$i -lt 2;$i++) {
        $side=$Layer.sides[$i];$label=@('source','target')[$i];$worldKey=@($Layer.sourceIdentity,$Layer.targetIdentity)[$i]
        if($side.side -cne $label -or $side.worldKey -cne $worldKey -or $side.seed -cne $Row.seed -or $side.frozenSettings -cne $Row.snapshot){throw 'M6_CARVERS_SIDE_IDENTITY'}
        Assert-CarversHalo $side.initialHalo $worldKey $Owned.root ($Receipt.phase -ceq 'new')
        Assert-CarversHalo $side.beforeSampleHalo $worldKey $Owned.root $false;Assert-CarversHalo $side.afterSampleHalo $worldKey $Owned.root $false
        $absent=@($side.initialHalo.cells|Where-Object {$_.raw -cne 'ABSENT' -or $_.currentHolder -ne $false -or $_.unloadingHolder -ne $false}).Count -eq 0
        $provenance=if($Receipt.phase -ceq 'new'){'VIRGIN_NEW'}elseif($absent){'FRESH_REGENERATED'}else{'REOPENED_OR_COMPLETED_PARTIAL'}
        if($side.provenance -cne $provenance){throw 'M6_CARVERS_PROVENANCE'}
        if($side.request.x -ne 1024 -or $side.request.z -ne 1024 -or $side.request.status -cne 'minecraft:carvers' -or $side.request.create -ne $true -or $side.request.explicitCount -ne 1 -or $side.returnedOwner.holderLatestIsReturnedChunk -ne $true -or $side.returnedOwner.holderStatus -cne 'minecraft:carvers' -or $side.returnedOwner.returnedStatus -cne 'minecraft:carvers' -or $side.returnedOwner.returnedClass -cne 'net.minecraft.world.chunk.ProtoChunk'){throw 'M6_CARVERS_REQUEST_OWNER'}
        Assert-ProfileSample $side.sample 16384 'minecraft:carvers'
    }
    foreach($field in @('block','biome')){if((Get-ProfileListHash ([string[]]$Layer.sides[0].sample.rows.$field)) -cne (Get-ProfileListHash ([string[]]$Layer.sides[1].sample.rows.$field))){throw 'M6_CARVERS_PAIRWISE_EXACT'}}
    if($Receipt.phase -ceq 'restart') {
        $newFinal=Read-ProfileJsonLink $State.PhaseEvidence $Layer.newWitness.newFinalPath '../new/final.json' $Layer.newWitness.newFinalSha256
        $newLayer=Read-ProfileJsonLink $State.PhaseEvidence $Layer.newWitness.path '../new/carvers-new.json' $Layer.newWitness.sha256
        $newRow=$newFinal.variants[0]
        if($newFinal.phase -cne 'new' -or $newFinal.status -cne 'PASS' -or $newRow.carversReceiptPath -cne 'carvers-new.json' -or $newRow.carversReceiptSha256 -cne $Layer.newWitness.sha256 -or $newRow.newSnapshotSha256 -cne $Layer.newWitness.newSnapshotSha256){throw 'M6_CARVERS_NEW_WITNESS'}
        $rootNew=Read-ProfileJsonLink $Owned.root $Layer.newWitness.newSnapshotPath "same-seed-$($Row.preset)-new.json" $Layer.newWitness.newSnapshotSha256
        foreach($key in @('preset','session','sourceIdentity','uuid','seed','allocationToken','snapshot','sourceRecordSha256')){if($rootNew.$key -cne $Row.$key -or $newRow.$key -cne $Row.$key){throw 'M6_CARVERS_NEW_WITNESS_IDENTITY'}}
        $newState=[pscustomobject]@{PhaseEvidence=[IO.Path]::GetFullPath((Join-Path $State.PhaseEvidence '../new'))}
        Assert-ProfileCarversLayer $newLayer $newFinal $newRow $newState $Owned
        for($i=0;$i -lt 2;$i++){foreach($field in @('block','biome')){if((Get-ProfileListHash ([string[]]$Layer.sides[$i].sample.rows.$field)) -cne (Get-ProfileListHash ([string[]]$newLayer.sides[$i].sample.rows.$field))){throw 'M6_CARVERS_RESTART_ACTUAL_EXACT'}}}
        Assert-ProfileFinalDiagnostic $Row.finalDiagnostic $Row $newRow.finalDiagnostic
        if($Row.finalDiagnostic.newFinalSha256 -cne $Layer.newWitness.newFinalSha256){throw 'M6_FINAL_RESTART_LINK'}
    }
}
function Assert-ProfileCarvers($Receipt,$Row,$State,$Owned) {
    if($Receipt.root -cne $Owned.root){throw 'M6_CARVERS_ROOT'}
    if($Receipt.phase -ceq 'source') {
        $pre=Read-ProfileJsonLink $State.PhaseEvidence $Row.carversPreflightPath 'carvers-source-preflight.json' $Row.carversPreflightSha256
        Assert-CarversMetadata $pre.generationMetadata;Assert-CarversHalo $pre.sourceHalo 'minecraft:overworld' $Owned.root $true
        if($pre.generated -ne $false -or $pre.seed -cne $Row.seed -or $pre.worldKey -cne 'minecraft:overworld' -or $pre.sourceRecordSha256 -cne $Row.sourceRecordSha256){throw 'M6_CARVERS_SOURCE_PREFLIGHT'}
    } else {
        if($Row.carversReceiptPhase -cne $Receipt.phase){throw 'M6_CARVERS_PHASE_LINK'}
        $layer=Read-ProfileJsonLink $State.PhaseEvidence $Row.carversReceiptPath "carvers-$($Receipt.phase).json" $Row.carversReceiptSha256
        Assert-ProfileCarversLayer $layer $Receipt $Row $State $Owned
        Assert-ProfileFinalDiagnostic $Row.finalDiagnostic $Row
    }
}
function Test-ProfileCarversNegatives($Receipt,$Owned,$State) {
    if($Receipt.phase -ceq 'source'){return 0}
    $row=$Receipt.variants[0];$layer=Read-ProfileJsonLink $State.PhaseEvidence $row.carversReceiptPath "carvers-$($Receipt.phase).json" $row.carversReceiptSha256
    $checks=0
    foreach($case in @('missing-row','wrong-stage','wrong-uuid')) {
        $bad=$layer|ConvertTo-Json -Depth 40|ConvertFrom-Json -DateKind String
        switch($case){
            'missing-row' {$bad.sides[0].sample.rows=@($bad.sides[0].sample.rows)[0..1438]}
            'wrong-stage' {$bad.sides[0].sample.chunkStatus='minecraft:full'}
            'wrong-uuid' {$bad.uuid='00000000-0000-0000-0000-000000000000'}
        }
        $rejected=$false;try{Assert-ProfileCarversLayer $bad $Receipt $row $State $Owned}catch{if($_.Exception.Message -notmatch 'M6_CARVERS_'){throw};$rejected=$true}
        if(-not $rejected){throw "M6_CARVERS_NEGATIVE_MISSED:$case"};$checks++
    }
    $bad=$Receipt|ConvertTo-Json -Depth 40|ConvertFrom-Json -DateKind String;$bad.variants[0].carversReceiptSha256='0'*64
    $rejected=$false;try{Assert-ProfileCarvers $bad $bad.variants[0] $State $Owned}catch{if($_.Exception.Message -notmatch 'M6_CARVERS_LINK_SHA'){throw};$rejected=$true}
    if(-not $rejected){throw 'M6_CARVERS_NEGATIVE_MISSED:wrong-sha'};$checks++
    if($Receipt.phase -ceq 'restart'){
        $bad=$layer|ConvertTo-Json -Depth 40|ConvertFrom-Json -DateKind String;$bad.newWitness.sha256='0'*64
        $rejected=$false;try{Assert-ProfileCarversLayer $bad $Receipt $row $State $Owned}catch{if($_.Exception.Message -notmatch 'M6_CARVERS_LINK_SHA'){throw};$rejected=$true}
        if(-not $rejected){throw 'M6_CARVERS_NEGATIVE_MISSED:replace-new-witness'};$checks++
    }
    # 純 parser 副本：保證有一個差格，再刻意漏掉；不冒充 functional runtime。
    $before=$row.finalDiagnostic.source.rows|ConvertTo-Json -Depth 8|ConvertFrom-Json
    $after=$before|ConvertTo-Json -Depth 8|ConvertFrom-Json;$after[0].block='Block{minecraft:diamond_block}'
    if($before[0].block -ceq $after[0].block){$after[0].block='Block{minecraft:bedrock}'}
    $rejected=$false;try{Assert-ProfileDifferences $before $after @()}catch{if($_.Exception.Message -notmatch 'M6_FINAL_DIFF_COMPLETE'){throw};$rejected=$true}
    if(-not $rejected){throw 'M6_CARVERS_NEGATIVE_MISSED:missing-final-difference'};$checks++
    return $checks
}
function Assert-ProfileReceipt($receipt,$phase,$owned,$state,[string]$ExpectedChain) {
    if($receipt.chain -cne $ExpectedChain -or $receipt.m6ProbeSchema -ne 1 -or $receipt.expectedMode -cne 'FUNCTIONAL' -or $receipt.chain -cnotin @('same-seed-restart','same-seed-amplified-restart') -or $receipt.phase -cne $phase -or $receipt.nonce -cne $owned.nonce -or $receipt.startupNonce -cne $state.StartupNonce){throw 'M6_SAME_RECEIPT_IDENTITY'}
    if($receipt.status -cne 'PASS' -or @($receipt.failures).Count -or -not $receipt.functionalPassed -or -not $receipt.stopRequested -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen){throw "M6_SAME_FUNCTIONAL_FAILED:$($receipt.failures -join '; ')"}
    if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds){throw 'M6_SAME_RUNTIME_SCOPE'}
    $preset=if($receipt.chain -ceq 'same-seed-restart'){'normal'}else{'amplified'}
    if(@($receipt.variants).Count -ne 1 -or $receipt.variants[0].preset -cne $preset){throw 'M6_SAME_VARIANT_SET'}
    foreach($row in $receipt.variants) {
        Assert-ProfileCarvers $receipt $row $state $owned
        if($row.sourceInitialRequest -cne '128,128=minecraft:full'){throw 'M6_SAME_SOURCE_REQUEST'}
        if($phase -cin @('source','new')) {
            $preflight=$row.samplePreflight
            $worldCount=if($phase -ceq 'source'){1}else{2}
            if($preflight.status -cne 'PASS' -or $preflight.phase -cne $phase -or @($preflight.worlds).Count -ne $worldCount -or @($preflight.violations).Count){throw 'SAME_SAMPLE_PRIOR_GENERATION:receipt'}
            $keys=@('minecraft:overworld');if($phase -ceq 'new'){$keys+="quantumchamber:universe/$($row.uuid)/overworld"}
            if(($preflight.worlds.worldKey -join '|') -cne ($keys -join '|')){throw 'SAME_SAMPLE_PRIOR_GENERATION:owner'}
            foreach($world in $preflight.worlds) {
                $expected=@(foreach($x in 31..33){foreach($z in 31..33){"$x,$z"}})
                if(@($world.cells).Count -ne 9 -or (($world.cells | ForEach-Object {"$($_.x),$($_.z)"}) -join '|') -cne ($expected -join '|')){throw 'SAME_SAMPLE_PRIOR_GENERATION:coordinates'}
                foreach($cell in $world.cells){
                    if($cell.currentHolder -ne $false -or $cell.unloadingHolder -ne $false -or $cell.liveFull -ne $false -or $cell.raw -cne 'ABSENT' -or $cell.canonicalNonReparse -ne $true -or $cell.headerValid -ne $true -or $cell.entryLocation -ne 0){throw 'SAME_SAMPLE_PRIOR_GENERATION:cell'}
                }
            }
        }
        if(-not $row.formalLeverEntry -or -not $row.formalBatch -or -not $row.formalSelected -or -not $row.emptyStorageBeforeAllocation -or -not $row.sourceTargetExact -or $row.policyVersion -ne 2 -or $row.profileId -cne 'quantumchamber:same_seed_fresh_v1' -or $row.sourceIdentity -cne 'minecraft:overworld' -or $row.uuid -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $row.allocationToken -cnotmatch '^[0-9a-f]{64}$'){throw 'M6_SAME_FORMAL_ENTRY'}
        if($phase -cne 'source') {
            if(-not $row.catalogChecked -or -not $row.allocationChecked -or -not $row.buildingAbsent -or -not $row.editsIndependent -or $row.matchedMaturity -cne 'FULL_3X3' -or $row.sampleCount -ne 1440 -or $row.terrain -cnotmatch '^[0-9a-f]{64}$' -or $row.biomes -cnotmatch '^[0-9a-f]{64}$' -or @($row.structures).Count -ne 3){throw 'M6_SAME_GENERATION_OR_ISOLATION'}
            $requests=@('32,32=minecraft:full')+@(foreach($x in 31..33){foreach($z in 31..33){"$x,$z=minecraft:full"}})
            if(($row.sourceRequests -join '|') -cne ($requests -join '|') -or ($row.targetRequests -join '|') -cne ($requests -join '|')){throw 'M6_SAME_REQUEST_SEQUENCE'}
            if($phase -ceq 'restart' -and -not $row.restartExact){throw 'M6_SAME_RESTART'}
        }
    }
}
function Assert-ProfileContinuity($receipts,[string[]]$EvidenceBases) {
    if(@($receipts).Count -ne 3 -or @($EvidenceBases).Count -ne 3){throw 'M6_SAME_CONTINUITY_CONTEXT'}
    $phases=@('source','new','restart');$bases=@();$ownerBase=$null
    for($phaseIndex=0;$phaseIndex -lt 3;$phaseIndex++) {
        $r=$receipts[$phaseIndex];$given=$EvidenceBases[$phaseIndex]
        if([string]::IsNullOrWhiteSpace($given) -or -not [IO.Path]::IsPathFullyQualified($given)){throw 'M6_SAME_CONTINUITY_CONTEXT'}
        $base=[IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($given));$parent=[IO.Path]::GetDirectoryName($base)
        if($r.phase -cne $phases[$phaseIndex] -or [IO.Path]::GetFileName($base) -cne $phases[$phaseIndex] -or [IO.Path]::GetFileName($parent) -cne "m6-native-$($r.nonce)" -or $r.nonce -cne $receipts[0].nonce -or $r.root -cne $receipts[0].root -or $r.chain -cne $receipts[0].chain -or -not(Test-Path -LiteralPath $base -PathType Container)){throw 'M6_SAME_CONTINUITY_CONTEXT'}
        if($null -eq $ownerBase){$ownerBase=$parent}elseif(-not $parent.Equals($ownerBase,[StringComparison]::OrdinalIgnoreCase)){throw 'M6_SAME_CONTINUITY_CONTEXT'}
        $bases+=,$base
    }
    for($i=0;$i -lt 1;$i++) {
        foreach($field in @('preset','session','sourceIdentity','uuid','seed','allocationToken','snapshot','policyVersion','profileId')) {
            if(@($receipts | ForEach-Object {$_.variants[$i].$field} | Sort-Object -Unique).Count -ne 1){throw "M6_SAME_RESTART_IDENTITY:$field"}
        }
        $layers=@(foreach($phaseIndex in 1..2) {
            $r=$receipts[$phaseIndex]
            Read-ProfileJsonLink $bases[$phaseIndex] $r.variants[$i].carversReceiptPath "carvers-$($r.phase).json" $r.variants[$i].carversReceiptSha256
        })
        for($side=0;$side -lt 2;$side++){foreach($field in @('block','biome')) {
            if((Get-ProfileListHash ([string[]]$layers[0].sides[$side].sample.rows.$field)) -cne (Get-ProfileListHash ([string[]]$layers[1].sides[$side].sample.rows.$field))){throw "M6_SAME_RESTART_CARVERS:$field"}
        }}
        foreach($field in @('biomes','structures')) {
            if(($receipts[1].variants[$i].$field | ConvertTo-Json -Compress) -cne ($receipts[2].variants[$i].$field | ConvertTo-Json -Compress)){throw "M6_SAME_RESTART_GENERATION:$field"}
        }
    }
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
function Assert-SavedNativeGlobal($native) {
    $phaseTotal=(@($manifest | ForEach-Object {$_.phases.Count}) | Measure-Object -Sum).Sum
    if($native.status -cne 'PASS' -or $native.head -cne $head -or -not $native.allEnabled -or $native.requireIndependentSeed -or $native.expectedMode -cne 'PER_CHAIN' -or $native.phaseCount -ne $phaseTotal -or $native.phaseMinutes -ne 3 -or $native.idleSeconds -ne 90 -or $native.blockMinutes -ne 60 -or $native.functionalAssertionsRemainRed -ne $true){throw 'M6_SAVED_SUMMARY'}
    if(@($native.enabledManifest).Count -ne $manifest.Count -or @($native.chains).Count -ne $manifest.Count -or @($native.phases).Count -ne $phaseTotal -or ($native.selectedChains -join ',') -cne ($manifest.chain -join ',')){throw 'M6_SAVED_MANIFEST'}
    if(@($native.phases.startupNonce | Sort-Object -Unique).Count -ne $phaseTotal -or @($native.chains.root | Sort-Object -Unique).Count -ne $manifest.Count){throw 'M6_SAVED_OWNER_REUSED'}
}
function Get-SavedNativeChainOffset([int]$ChainIndex) {
    if($ChainIndex -lt 0 -or $ChainIndex -ge $manifest.Count){throw 'M6_SAVED_OVERRIDE_SCOPE'}
    $offset=0;for($index=0;$index -lt $ChainIndex;$index++){$offset+=$manifest[$index].phases.Count};return $offset
}
function Get-SavedNativeChainIndex([int]$PhaseIndex) {
    $offset=0
    for($index=0;$index -lt $manifest.Count;$index++) {
        if($PhaseIndex -ge $offset -and $PhaseIndex -lt $offset+$manifest[$index].phases.Count){return $index}
        $offset+=$manifest[$index].phases.Count
    }
    throw 'M6_SAVED_OVERRIDE_SCOPE'
}
# full與SelfTest共用原chain判定；不保存或重用任何PASS結論。
function Assert-SavedNativeChain($native,[int]$ChainIndex,$receiptOverrides=@{},$preservationOverrides=@{}) {
    $offset=Get-SavedNativeChainOffset $ChainIndex
    $i=$ChainIndex
    $spec=$manifest[$i];$entry=$native.enabledManifest[$i];$chainRow=$native.chains[$i]
    if($entry.chain -cne $spec.chain -or $entry.expectedMode -cne $spec.expectedMode -or $entry.m6ProbeSchema -ne 1 -or ($entry.phases -join ',') -cne ($spec.phases -join ',') -or $chainRow.chain -cne $spec.chain -or $chainRow.expectedMode -cne $spec.expectedMode -or ($chainRow.phases -join ',') -cne ($spec.phases -join ',')){throw 'M6_SAVED_MANIFEST'}
    if((Get-FileHash -LiteralPath $chainRow.preservation).Hash -cne $chainRow.preservationSha256){throw 'M6_SAVED_PRESERVATION_HASH'}
    $preserve=if($preservationOverrides.ContainsKey($i)){$preservationOverrides[$i]}else{Get-Content -LiteralPath $chainRow.preservation -Raw | ConvertFrom-Json -DateKind String}
    if($preserve.root -cne $chainRow.root -or $preserve.strategy -cne 'NEW_OWNED_ROOT' -or $preserve.existingWorldsMoved -ne 0 -or $preserve.existingWorldsChanged -or -not $preserve.retainedRoot -or -not $preserve.lockReleased -or $preserve.normalStop -ne ($spec.chain -cne 'catalog-readback')){throw 'M6_SAVED_PRESERVATION'}
    $receipts=@();$pids=@();$phaseEvidenceBases=@()
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
            'same-seed-restart' {Assert-ProfileReceipt $receipt $phaseName $owned $state $spec.chain}
            'same-seed-amplified-restart' {Assert-ProfileReceipt $receipt $phaseName $owned $state $spec.chain}
            default {Assert-GameplayReceipt $receipt $spec.chain $phaseName $owned $state}
        }
        $receipts+=,$receipt;$pids+=,$row.javaPid;$phaseEvidenceBases+=,$state.PhaseEvidence;$offset++
    }
    if(@($pids | Sort-Object -Unique).Count -ne $spec.phases.Count){throw 'M6_SAVED_JVM_REUSED'}
    if($spec.chain -ceq 'native-seed-diagnostic') {
        if($receipts[0].catalogSha256 -cne $receipts[1].catalogSha256 -or -not $receipts[1].diagnosticAssertions.reopenExact){throw 'M6_SAVED_DIAGNOSTIC_REOPEN'}
    } elseif($spec.chain -ceq 'native-seed-functional') {
        for($w=0;$w -lt 2;$w++){foreach($field in @('worldKey','seed','frozenStructures','accessorStructures','settingsKey','samples','positivePosition')) {
            if(($receipts[0].worlds[$w].$field | ConvertTo-Json -Compress) -cne ($receipts[1].worlds[$w].$field | ConvertTo-Json -Compress)){throw 'M6_SAVED_SEED_REOPEN'}
        }}
    } elseif($spec.chain -cin @('same-seed-restart','same-seed-amplified-restart')) {
        Assert-ProfileContinuity $receipts $phaseEvidenceBases
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
function Assert-SavedNativeSummary($native,$receiptOverrides=@{},$preservationOverrides=@{}) {
    Assert-SavedNativeGlobal $native
    for($index=0;$index -lt $manifest.Count;$index++) {
        Assert-SavedNativeChain $native $index $receiptOverrides $preservationOverrides
    }
}
# 只供baseline已完整核過後的局部記憶體override；跨鏈條件仍每次重核。
function Assert-SavedNativeOverride($native,[int]$ChainIndex,$receiptOverrides=@{},$preservationOverrides=@{}) {
    Assert-SavedNativeGlobal $native
    $offset=Get-SavedNativeChainOffset $ChainIndex
    foreach($key in $receiptOverrides.Keys){if($key -isnot [int] -or $key -lt $offset -or $key -ge $offset+$manifest[$ChainIndex].phases.Count){throw 'M6_SAVED_OVERRIDE_SCOPE'}}
    foreach($key in $preservationOverrides.Keys){if($key -isnot [int] -or $key -ne $ChainIndex){throw 'M6_SAVED_OVERRIDE_SCOPE'}}
    Assert-SavedNativeChain $native $ChainIndex $receiptOverrides $preservationOverrides
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
            $rejected=$false;try{Assert-SavedNativeOverride $native $index @{} @{$index=$bad}}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_RESTORE_NEGATIVE_MISSED'};$checks++
        }
        for($index=0;$index -lt $native.phases.Count;$index++) {
            $chainIndex=Get-SavedNativeChainIndex $index
            foreach($field in @('expectedMode','m6ProbeSchema','chain','phase','status')) {
                $bad=Get-Content -LiteralPath $native.phases[$index].receipt -Raw | ConvertFrom-Json -DateKind String
                $bad.$field=if($field -ceq 'm6ProbeSchema'){2}else{'WRONG'}
                $rejected=$false;try{Assert-SavedNativeOverride $native $chainIndex @{$index=$bad}}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_RECEIPT_NEGATIVE_MISSED'};$checks++
            }
            $original=Get-Content -LiteralPath $native.phases[$index].receipt -Raw | ConvertFrom-Json -DateKind String
            if($original.chain -cin @('same-seed-restart','same-seed-amplified-restart')) {
                $fields=@('formalLeverEntry','formalBatch','formalSelected','emptyStorageBeforeAllocation','sourceTargetExact','policyVersion','sourceInitialRequest')
                if($original.phase -cne 'source'){$fields+=@('catalogChecked','allocationChecked','buildingAbsent','editsIndependent','sampleCount','sourceRequests','targetRequests')}
                if($original.phase -ceq 'restart'){$fields+='restartExact'}
                $profileOwned=[pscustomobject]@{root=$original.root;nonce=$original.nonce}
                $profileState=[pscustomobject]@{PhaseEvidence=(Split-Path -Parent $native.phases[$index].receipt)}
                $checks+=Test-ProfileCarversNegatives $original $profileOwned $profileState
                foreach($field in $fields) {
                    $bad=$original | ConvertTo-Json -Depth 30 | ConvertFrom-Json -DateKind String
                    $bad.variants[0].$field=$false
                    $rejected=$false;try{Assert-SavedNativeOverride $native $chainIndex @{$index=$bad}}catch{$rejected=$true}
                    if(-not $rejected){throw "M6_SAME_NEGATIVE_MISSED:$field"};$checks++
                }
            }
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
            $rejected=$false;try{Assert-SavedNativeOverride $bad $chainIndex}catch{$rejected=$true};if(-not $rejected){throw 'M6_SAVED_HASH_NEGATIVE_MISSED'};$checks++
        }
    }
    # SelfTest不改原件；結束再完整核七鏈，拒絕期間持續的檔案或跨鏈變造。
    if($SelfTest){Assert-SavedNativeSummary $native}
    Write-Output "M6_NATIVE_VERIFIED PASS chains=$($manifest.Count) phases=$($native.phaseCount) negativeChecks=$checks HEAD=$head"
    return
}
try {
    foreach($chainSpec in $selected){
        if([DateTimeOffset]::UtcNow -ge $deadline){throw 'M6_NATIVE_BLOCK_BUDGET_EXCEEDED'}
        $owned=New-OwnedRoot -Context $context -Suffix $chainSpec.chain
        if($chainSpec.chain -ceq 'native-seed-functional'){'level-type=minecraft:amplified' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        if($chainSpec.chain -cin @('intent-allocation','partial-overworld')){'level-type=minecraft:normal' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        if($chainSpec.chain -ceq 'same-seed-restart'){'level-type=minecraft:normal' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        if($chainSpec.chain -ceq 'same-seed-amplified-restart'){'level-type=minecraft:amplified' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII}
        if($chainSpec.chain -cin @('same-seed-restart','same-seed-amplified-restart')){
            'initial-enabled-packs=vanilla,fabric,fabric-gametest-api-v1,quantumchamber,fabric-convention-tags-v2,quantumchamber-testmod' | Add-Content -LiteralPath (Join-Path $owned.root 'server.properties') -Encoding ASCII
        }
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
                elseif($chainSpec.chain -cin @('same-seed-restart','same-seed-amplified-restart')){Assert-ProfileReceipt $receipt $phase $owned $state $chainSpec.chain}
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
        if($chainSpec.chain -cin @('same-seed-restart','same-seed-amplified-restart')) {
            Assert-ProfileContinuity @($current | ForEach-Object {Get-Content -LiteralPath $_.receipt -Raw | ConvertFrom-Json -DateKind String}) @($current | ForEach-Object {[IO.Path]::GetDirectoryName($_.receipt)})
        }
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
