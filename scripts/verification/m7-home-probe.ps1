# M7 test-only CLOSE 跨 JVM runner。每列 fresh root、兩 phase；不調整既有 M6 預算。
param([string]$InspectHistorical='', [switch]$AllEnabled,[string]$Only='', [string]$VerifySummary='', [switch]$SelfTest,[switch]$LegacyFlatFixture,
      [ValidateSet('Windows','Ubuntu')][string]$Platform=$(if($IsWindows){'Windows'}else{'Ubuntu'}),
      [string]$Gradle='', [string]$OutputRoot='', [string]$ExpectedHead='', [string]$AggregateRoot='', [string]$BundleRoot='', [string]$FrozenConsumers='', [string]$FixedNonce='', [string[]]$StartupNonces=@(), [string[]]$OwnerNonces=@(),
      [ValidateRange(0,38)][int]$Shard=0,[ValidateRange(1,39)][int]$ShardCount=1,
      [ValidateRange(30,1200)][int]$PhaseSeconds=300)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim()
if(-not $ExpectedHead){$ExpectedHead=$head}
if($ExpectedHead -cne $head -and -not $VerifySummary -and -not $AggregateRoot){throw 'M7_HEAD'}
. (Join-Path $PSScriptRoot 'm7-contract.ps1')
if(-not $IsWindows){throw 'M7_NATIVE_WINDOWS_ONLY'}
if($InspectHistorical){$historical=Get-M7Json $InspectHistorical;if($historical.head-cne$ExpectedHead){throw 'M7_HISTORICAL_HEAD'};@{status='HISTORICAL_NOT_REQUALIFIED';head=$historical.head;path=$InspectHistorical;sha256=(Get-FileHash $InspectHistorical).Hash}|ConvertTo-Json;return}
$inventory=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json') -Raw | ConvertFrom-Json
Assert-M7Manifest $inventory $repo
$platformRows=@($inventory.native | Where-Object {$_.platform -ceq $Platform -and $_.stage -ceq 'spike-close'})
if($platformRows.Count -ne 18 -or @($platformRows.chain | Sort-Object -Unique).Count -ne 18){throw 'M7_MANIFEST'}
function Write-M7Json([string]$Path,$Value){$Value|ConvertTo-Json -Depth 40|Set-Content -LiteralPath $Path -Encoding utf8}
function Assert-M7Path([string]$Path){
    $full=[IO.Path]::GetFullPath($Path);$comparison=if($IsWindows){[StringComparison]::OrdinalIgnoreCase}else{[StringComparison]::Ordinal}
    if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,$comparison)){throw 'M7_ROOT_OUTSIDE'}
    $cursor=$full
    while($cursor){$item=Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue;if($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'M7_ROOT_REPARSE'};$cursor=[IO.Path]::GetDirectoryName($cursor)}
    return $full
}
function Assert-M7Lock([string]$Root){
    $path=Join-Path $Root 'world/session.lock'
    if(-not(Test-Path -LiteralPath $path)){throw 'M7_LOCK_MISSING'}
    $stream=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try{$stream.Lock(0,1);$stream.Unlock(0,1)}finally{$stream.Dispose()}
}
function Resolve-M7Bundle([string]$Path,[string]$Project){
    if(-not $BundleRoot){return $Path}
    $full=$Path.Replace('\','/');$prefix=$Project.Replace('\','/').TrimEnd('/')+'/'
    if(-not $full.StartsWith($prefix,[StringComparison]::Ordinal)){throw 'M7_SUMMARY_BUNDLE_PATH'}
    $relative=$full.Substring($prefix.Length)
    if(@($relative.Split('/')|Where-Object {$_ -cin @('','.','..')}).Count){throw 'M7_SUMMARY_BUNDLE_PATH'}
    return Join-Path $BundleRoot $relative
}
function Read-M7Summary([string]$Path){
    $summary=Get-Content -LiteralPath $Path -Raw|ConvertFrom-Json -DateKind String
    if($summary.schema -ne 1 -or $summary.head -cne $ExpectedHead){throw 'M7_SUMMARY_HEAD'}
    if($summary.platform -cne $Platform -or $summary.status -cne 'PASS' -or $summary.stage -cne 'spike-close'){throw 'M7_SUMMARY_STATUS'}
    if($summary.inventoryHash -cne (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json')).Hash){throw 'M7_SUMMARY_INVENTORY_HASH'}
    $sourceProject=if($BundleRoot){$summary.evidenceRoot}elseif($summary.PSObject.Properties.Name -ccontains 'projectRoot'){$summary.projectRoot}elseif(-not $BundleRoot){$repo}else{throw 'M7_SUMMARY_PROJECT_ROOT'}
    $consumerPath=Resolve-M7Bundle $summary.consumerManifest $sourceProject
    if((Get-FileHash -LiteralPath $consumerPath).Hash -cne $summary.consumerManifestHash){throw 'M7_SUMMARY_CONSUMER_HASH'}
    $consumers=Get-Content -LiteralPath $consumerPath -Raw|ConvertFrom-Json
    foreach($file in $consumers.files){if((Get-FileHash -LiteralPath (Resolve-M7Bundle $file.copy $sourceProject)).Hash -cne $file.sha256){throw 'M7_SUMMARY_CONSUMER_COPY_HASH'}}
    $expected=@($platformRows | Where-Object {($platformRows.IndexOf($_)%[int]$summary.shardCount) -eq [int]$summary.shard})
    if($summary.only){$expected=@($expected|Where-Object {$_.chain -ceq $summary.only})}
    if(@($summary.chains).Count -ne $expected.Count -or $expected.Count -eq 0){throw 'M7_SUMMARY_CHAIN_SET'}
    $identities=@();$roots=@();$nonces=@()
    for($i=0;$i -lt $expected.Count;$i++){
        $chain=$summary.chains[$i]
        if($chain.chain -cne $expected[$i].chain -or $chain.platform -cne $Platform -or @($chain.phases).Count -ne 2){throw 'M7_SUMMARY_PHASE_SET'}
        $root=$chain.root;if(-not $BundleRoot){Assert-M7Path $root|Out-Null};$roots+=$root;$nonces+=$chain.nonce
        foreach($phase in @('crash','reopen')){
            $row=@($chain.phases|Where-Object {$_.phase -ceq $phase})
            if($row.Count -ne 1){throw 'M7_SUMMARY_PHASE_SET'};$row=$row[0]
            if($row.status -cne 'PASS' -or $row.head -cne $ExpectedHead -or $row.chain -cne $chain.chain -or $row.root -cne $root -or $row.nonce -cne $chain.nonce -or -not $row.lockReleased){throw 'M7_SUMMARY_IDENTITY'}
            $receiptPath=Resolve-M7Bundle $row.receipt $sourceProject
            $receipt=Get-Content -LiteralPath $receiptPath -Raw|ConvertFrom-Json -DateKind String
            if((Get-FileHash -LiteralPath $receiptPath).Hash -cne $row.receiptHash){throw 'M7_SUMMARY_HASH'}
            if($receipt.schema -ne 1 -or $receipt.status -cne 'PASS' -or $receipt.head -cne $ExpectedHead -or $receipt.chain -cne $row.chain -or $receipt.phase -cne $row.phase -or $receipt.nonce -cne $row.nonce -or $receipt.root -cne $row.root -or $receipt.pid -ne $row.pid -or $receipt.startTimeUtc -cne $row.startTimeUtc -or $receipt.startupNonce -cne $row.startupNonce){throw 'M7_SUMMARY_RECEIPT_IDENTITY'}
            Assert-M7SavedPhase $row $receipt $chain $phase $sourceProject $BundleRoot
            $expectedExit=if($phase -ceq 'crash'){77}else{0}
            if($row.javaExit -ne $expectedExit -or $receipt.expectedExit -ne $expectedExit -or $row.launcherKind -cne 'DIRECT_EXPORTED_JVM' -or $row.launcherExit -ne $expectedExit -or $row.gradleExportExit -ne 0){throw 'M7_SUMMARY_EXIT'}
            if($receipt.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -cnotin $receipt.loadedMods){throw 'M7_SUMMARY_RUNTIME'}
            $prelaunch=Resolve-M7Bundle $row.prelaunch $sourceProject
            if((Get-FileHash -LiteralPath $prelaunch).Hash -cne $row.prelaunchHash){throw 'M7_SUMMARY_PRELAUNCH_HASH'}
            $inputs=Get-Content -LiteralPath $prelaunch -Raw|ConvertFrom-Json
            if($inputs.head -cne $ExpectedHead -or $inputs.root -cne $chain.root -or $inputs.startupNonce -cne $row.startupNonce -or $inputs.consumerManifestHash -cne $summary.consumerManifestHash -or $inputs.nonce -cne $chain.nonce -or $inputs.phase -cne $phase -or ([DateTimeOffset]$inputs.recordedBeforeJvmUtc) -gt ([DateTimeOffset]$row.startTimeUtc)){throw 'M7_SUMMARY_PRELAUNCH_IDENTITY'}
            $dliArg=@($inputs.arguments|Where-Object {$_ -clike '-Dfabric.dli.config=*'});$dliCopies=@($inputs.files|Where-Object {$_.copy.EndsWith('loom-launch.cfg',[StringComparison]::Ordinal)});if($dliArg.Count-ne1 -or $dliCopies.Count-ne1 -or $inputs.files.Count-lt2 -or $dliArg[0].Substring('-Dfabric.dli.config='.Length).Replace('@@0020',' ') -cne $dliCopies[0].copy){throw 'M7_SUMMARY_DLI_BINDING'}
            foreach($file in $inputs.files){if((Get-FileHash -LiteralPath (Resolve-M7Bundle $file.copy $sourceProject)).Hash -cne $file.sha256){throw 'M7_SUMMARY_ARGFILE_HASH'}}
            foreach($file in $row.disk){if((Get-FileHash -LiteralPath (Resolve-M7Bundle $file.path $sourceProject)).Hash -cne $file.sha256){throw 'M7_SUMMARY_DISK_HASH'}}
            if(-not $BundleRoot){Assert-M7Lock $root}
            $identities+="$($row.pid)@$($row.startTimeUtc)";$identities+=$row.startupNonce
        }
    }
    if(@($roots|Sort-Object -Unique).Count -ne $expected.Count -or @($nonces|Sort-Object -Unique).Count -ne $expected.Count -or @($identities|Sort-Object -Unique).Count -ne 4*$expected.Count){throw 'M7_SUMMARY_JVM_REUSED'}
    return $summary
}
function Invoke-M7Negatives([string]$Path){
    $source=Get-Content -LiteralPath $Path -Raw
    $cases=@('phase','pid','root','hash','head','pass','skip','platform','disk')
    $results=@()
    foreach($case in $cases){
        $changed=$source|ConvertFrom-Json -DateKind String
        switch($case){
            'phase'{$changed.chains[0].phases=@($changed.chains[0].phases[0])}
            'pid'{$changed.chains[0].phases[1].pid=$changed.chains[0].phases[0].pid;$changed.chains[0].phases[1].startTimeUtc=$changed.chains[0].phases[0].startTimeUtc}
            'root'{$changed.chains[0].phases[0].root+='-wrong'}
            'hash'{$changed.chains[0].phases[0].receiptHash='0'*64}
            'head'{$changed.head='0'*40}
            'pass'{$changed.chains[0].phases[0].status='FAKE_PASS'}
            'skip'{$changed.chains=@()}
            'disk'{$changed.chains[0].phases[0].disk=@()}
            'platform'{$changed.platform=if($Platform -ceq 'Windows'){'Ubuntu'}else{'Windows'}}
        }
        $negativeRoot=Join-Path (Split-Path (Split-Path $Path)) ('readonly-negative-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $negativeRoot|Out-Null
        $candidate=Join-Path $negativeRoot ("negative-$case-"+[guid]::NewGuid().ToString('N')+'.json');Write-M7Json $candidate $changed
        $reason='';try{Read-M7Summary $candidate|Out-Null}catch{$reason=$_.Exception.Message}
        if($reason -cne @{phase='M7_SUMMARY_PHASE_SET';pid='M7_SUMMARY_RECEIPT_IDENTITY';root='M7_SUMMARY_IDENTITY';hash='M7_SUMMARY_HASH';head='M7_SUMMARY_HEAD';pass='M7_SUMMARY_IDENTITY';skip='M7_SUMMARY_CHAIN_SET';platform='M7_SUMMARY_STATUS';disk='M7_SUMMARY_DISK_REQUIRED'}[$case]){throw "M7_NEGATIVE_NOT_KILLED $case $reason"}
        $results+=@{case=$case;reason=$reason;status='KILLED'}
    }
    $resultPath=if($VerifySummary){Join-Path $negativeRoot 'negative-results.json'}else{Join-Path (Split-Path $Path) 'negative-results.json'}
    Write-M7Json $resultPath $results
    return $results.Count
}
if($AggregateRoot){ Invoke-M7WindowsAggregate $AggregateRoot $OutputRoot $ExpectedHead $inventory;return }
if($VerifySummary){Assert-M7EvidenceSeal (Split-Path ([IO.Path]::GetFullPath($VerifySummary))) $ExpectedHead; $saved=Read-M7Summary $VerifySummary;$count=0;if($SelfTest){$count=Invoke-M7Negatives $VerifySummary};"M7_NATIVE_VERIFIED PASS chains=$(@($saved.chains).Count) phases=$(@($saved.chains).Count*2) negatives=$count HEAD=$head";return}
if($Platform -cne 'Windows'){throw 'M7_NATIVE_WINDOWS_ONLY'}
if(-not $Gradle){$Gradle=if($IsWindows){Join-Path $repo 'gradlew.bat'}else{Join-Path $repo 'gradlew'}}
if(-not $OutputRoot){$OutputRoot=Join-Path $repo ('.superpowers/m7-native-'+[guid]::NewGuid().ToString('N'))}
$OutputRoot=Assert-M7Path $OutputRoot
if(Test-Path -LiteralPath $OutputRoot){throw 'M7_OUTPUT_EXISTS'};New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$selected=@($platformRows|Where-Object {($platformRows.IndexOf($_)%$ShardCount) -eq  $Shard})
if($Only){$selected=@($selected|Where-Object {$_.chain -ceq $Only})}elseif(-not $AllEnabled){throw 'M7_EXPLICIT_SELECTION'}
if($selected.Count -eq 0){throw 'M7_EMPTY_SELECTION'}
if($IsWindows){
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'probe-common.ps1'),[ref]$tokens,[ref]$errors)
    $helper=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-OwnedIdentity'},$false));if($errors.Count -or $helper.Count -ne 1){throw 'M7_OWNER_HELPER'};Invoke-Expression $helper[0].Extent.Text
}
function Invoke-M7OwnerSelfTest { Invoke-M7WindowsOwnerControls $OutputRoot $head $OwnerNonces }
if($SelfTest){Invoke-M7OwnerSelfTest}
function New-M7ConsumerSnapshot {
    param([switch]$SourceOnly)
    $destination=Join-Path $OutputRoot $(if($SourceOnly){'source-snapshot'}else{'consumer-snapshot'});New-Item -ItemType Directory -Path $destination|Out-Null
    $sources=@();foreach($directory in @('src','scripts/verification','.github/workflows','mappings')){$sources+=@(Get-ChildItem -LiteralPath (Join-Path $repo $directory) -Recurse -File)}
    foreach($file in @('build.gradle','settings.gradle','gradle.properties')){$sources+=Get-Item -LiteralPath (Join-Path $repo $file)}
    if(-not $SourceOnly){foreach($directory in @('build/classes','build/resources','build/libs')){if(Test-Path -LiteralPath (Join-Path $repo $directory)){$sources+=@(Get-ChildItem -LiteralPath (Join-Path $repo $directory) -Recurse -File)}}}
    $files=@();foreach($file in @($sources|Sort-Object FullName -Unique)){
        $relative=[IO.Path]::GetRelativePath($repo,$file.FullName);$copy=Join-Path $destination $relative;New-Item -ItemType Directory -Path (Split-Path $copy) -Force|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $copy
        $hash=(Get-FileHash -LiteralPath $file.FullName).Hash;if((Get-FileHash -LiteralPath $copy).Hash -cne $hash){throw 'M7_SOURCE_COPY_HASH'}
        $files+=@{source=$file.FullName;copy=$copy;sha256=$hash;bytes=$file.Length}
    }
    $manifest=Join-Path $destination 'manifest.json'
    Write-M7Json $manifest @{schema=2;copyRoot=$destination;projectRoot=$repo;roots=$(if($SourceOnly){@('src','scripts/verification','.github/workflows','mappings')}else{@('src','scripts/verification','.github/workflows','mappings','build/classes','build/resources','build/libs')});head=$head;gitStatus=@(git -c "safe.directory=$repo" -C $repo status --porcelain=v1);files=$files}
    return $manifest
}
function Assert-M7Consumers([string]$Manifest){
    $saved=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json
    Assert-M7MemberSet $saved $repo
    foreach($file in $saved.files){if((Get-FileHash -LiteralPath $file.source).Hash -cne (Get-M7SourceHash $file) -or (Get-FileHash -LiteralPath $file.copy).Hash -cne $file.sha256){throw "M7_CONSUMER_DRIFT $($file.source)"}}
}
function Read-M7PublishedStartup([string]$Path,[string]$Evidence){
    $raw=[IO.File]::ReadAllBytes($Path);$readAt=[DateTime]::UtcNow.ToString('o');$rawPath=Join-Path $Evidence 'startup-observed.json';[IO.File]::WriteAllBytes($rawPath,$raw)
    Write-M7Json (Join-Path $Evidence 'startup-observation.json') @{readAtUtc=$readAt;path=$Path;length=$raw.Length;sha256=(Get-FileHash -LiteralPath $rawPath).Hash}
    if($raw.Length -eq 0){throw 'M7_STARTUP_EMPTY_PUBLISHED'}
    $document=$null
    try{
        $document=[Text.Json.JsonDocument]::Parse([Text.Encoding]::UTF8.GetString($raw));$names=@($document.RootElement.EnumerateObject()|ForEach-Object Name)
        if(@($names|Sort-Object -Unique).Count -ne $names.Count){throw 'duplicate'}
        $identity=[Text.Encoding]::UTF8.GetString($raw)|ConvertFrom-Json -DateKind String
    }catch{throw 'M7_STARTUP_INVALID_PUBLISHED'}finally{if($null -ne $document){$document.Dispose()}}
    foreach($name in @('schema','head','nonce','chain','phase','root','startupNonce','pid','startTimeUtc','javaExecutable','loadedMods')){if($identity.PSObject.Properties.Name -cnotcontains $name){throw "M7_STARTUP_INVALID_PUBLISHED missing=$name"}}
    Assert-M7Startup $identity
    return $identity
}
$chains=@();$watch=[Diagnostics.Stopwatch]::StartNew();$dliBaseline='';$sourceManifest=New-M7ConsumerSnapshot -SourceOnly;$consumerManifest=$FrozenConsumers
foreach($spec in $selected){
    $nonce=if($FixedNonce){$FixedNonce}else{[guid]::NewGuid().ToString('N')};$root=Assert-M7Path (Join-Path $repo "run/m7-home-$nonce")
    if(Test-Path -LiteralPath $root){throw 'M7_ROOT_EXISTS'};New-Item -ItemType Directory -Path $root|Out-Null
    'eula=true'|Set-Content -LiteralPath (Join-Path $root 'eula.txt') -Encoding ascii
    @('online-mode=false','server-port=0','level-name=world','spawn-protection=0','view-distance=2','simulation-distance=2','sync-chunk-writes=true','level-seed=20261113','max-tick-time=120000','level-type=minecraft:flat','generate-structures=false','generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}')|Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ascii
    if($LegacyFlatFixture){$properties=@(Get-Content -LiteralPath (Join-Path $root 'server.properties')|Where-Object {-not $_.StartsWith('generator-settings=')});$properties|Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ascii}
    $phases=@();$chainWatch=[Diagnostics.Stopwatch]::StartNew()
    foreach($phase in @('crash','reopen')){
        if($consumerManifest){Assert-M7Consumers $consumerManifest}
        $startup=if($StartupNonces.Count){$StartupNonces[$(if($phase -ceq 'crash'){0}else{1})]}else{[guid]::NewGuid().ToString('N')};$evidence=Join-Path $OutputRoot "$nonce-$phase";New-Item -ItemType Directory -Path $evidence|Out-Null
        $exportPath=Join-Path $evidence 'launch-export.json'
        $exportArgs=@('-I',(Join-Path $PSScriptRoot 'm7-launch-export.init.gradle'),'runM7HomeProbe','-Pm7ExportTask=runM7HomeProbe',"-Pm7ExportFile=$exportPath","-Pm7ProbeNonce=$nonce","-Pm7Chain=$($spec.chain)","-Pm7Phase=$phase","-Pm7Root=$root","-Pm7Head=$head","-Pm7StartupNonce=$startup",'--console=plain')
        & $Gradle @exportArgs *> (Join-Path $evidence 'gradle-export.log');$exportExit=$LASTEXITCODE
        if($exportExit -ne 0){throw "M7_LAUNCH_EXPORT_FAIL root=$root"}
        Assert-M7Consumers $sourceManifest
        $launch=Get-Content -LiteralPath $exportPath -Raw|ConvertFrom-Json
        $arguments=@();$inputFiles=@();$argumentIndex=0
        foreach($arg in $launch.jvmArgs){
            if($arg.StartsWith('@',[StringComparison]::Ordinal)){
                $original=$arg.Substring(1);$copy=Join-Path $evidence "jvm-argfile-$argumentIndex.args";Copy-Item -LiteralPath $original -Destination $copy
                $inputFiles+=@{source=$original;copy=$copy;sha256=(Get-FileHash -LiteralPath $copy).Hash};$arguments+='@'+$copy;$argumentIndex++
            }else{$arguments+=$arg}
        }
        if($argumentIndex -lt 1){throw 'M7_LAUNCH_ARGFILE_REQUIRED'}
        $arguments+=@($launch.main);$arguments+=@($launch.args)
        $dliSource=@($arguments|Where-Object {$_ -clike '-Dfabric.dli.config=*'})[0].Substring('-Dfabric.dli.config='.Length).Replace('@@0020',' ');$dliCopy=Join-Path $evidence 'loom-launch.cfg';Copy-Item -LiteralPath $dliSource -Destination $dliCopy
        $actualDli=@($arguments|Where-Object {$_ -clike '-Dfabric.dli.config=*'})[0].Substring('-Dfabric.dli.config='.Length).Replace('@@0020',' ');$dliHash=(Get-FileHash $actualDli).Hash;if($dliBaseline -and $dliBaseline -cne $dliHash){throw 'M7_DLI_DRIFT'};$dliBaseline=$dliHash
        $arguments=Freeze-M7DliArguments $arguments $evidence $inputFiles
        $inputFiles+=@{source=$dliSource;copy=$dliCopy;sha256=(Get-FileHash -LiteralPath $dliCopy).Hash}
        $arguments=Freeze-M7Classpath $arguments $launch.classpath $evidence $repo (Join-Path $OutputRoot 'frozen-classpath');foreach($file in $inputFiles){$file.sourceHash=(Get-FileHash $file.source).Hash;$file.sha256=(Get-FileHash $file.copy).Hash};$actualArg=Join-Path $evidence 'actual-classpath.args';$inputFiles+=@{source=$actualArg;copy=$actualArg;sha256=(Get-FileHash $actualArg).Hash}
        if(-not $consumerManifest){$consumerManifest=New-M7ConsumerSnapshot}
        Assert-M7Consumers $consumerManifest
        $manifestPath=Join-Path $evidence 'prelaunch-inputs.json'
        Write-M7Json $manifestPath @{schema=1;head=$head;nonce=$nonce;phase=$phase;root=$root;startupNonce=$startup;consumerManifest=$consumerManifest;consumerManifestHash=(Get-FileHash -LiteralPath $consumerManifest).Hash;executable=$launch.executable;arguments=$arguments;files=$inputFiles;recordedBeforeJvmUtc=[DateTime]::UtcNow.ToString('o')}
        $info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$launch.executable;$info.WorkingDirectory=$root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
        $java=[Diagnostics.Process]::Start($info);$null=$java.Handle;$javaStarted=([DateTimeOffset]$java.StartTime).ToUniversalTime();$outputTask=$java.StandardOutput.ReadToEndAsync();$errorTask=$java.StandardError.ReadToEndAsync()
        $timer=[Diagnostics.Stopwatch]::StartNew();$identity=$null;$capsule=$null;$startupPath=Join-Path $root "evidence/$phase/startup.json"
        try {
        while(-not $java.HasExited){
            if($null -eq $identity -and (Test-Path -LiteralPath $startupPath)){
                $identity=Read-M7PublishedStartup $startupPath $evidence
                $startMismatch=$IsWindows -and [Math]::Abs((([DateTimeOffset]$identity.startTimeUtc)-$javaStarted).TotalMilliseconds) -ge  1
                if($identity.pid -ne $java.Id -or $startMismatch -or $identity.nonce -cne $nonce -or $identity.startupNonce -cne $startup -or $identity.root -cne $root -or $identity.chain -cne $spec.chain -or $identity.phase -cne $phase -or $identity.head -cne $head){throw 'M7_STARTUP_IDENTITY'}
                $capsule=New-M7OwnerCapsule $java $launch.executable $root "-Dquantumchamber.m7home.startupNonce=$startup" "-Dquantumchamber.m7home.root=$root"
                $ownerObservation=Assert-M7WindowsOwner $capsule;if(-not $capsule.executable.Equals($identity.javaExecutable,[StringComparison]::OrdinalIgnoreCase)){throw 'M7_STARTUP_EXECUTABLE'}
                Write-M7Json (Join-Path $evidence 'owner.json') $ownerObservation
            }
            if($timer.Elapsed.TotalSeconds -gt $PhaseSeconds){
                Save-M7PartialRuntime $root $evidence
                Save-M7ProcessLogs $outputTask $errorTask $evidence $false
                $stopped=$false;if($null -ne $capsule){$stopped=Stop-M7WindowsOwner $capsule $evidence}
                Write-M7Json (Join-Path $evidence 'timeout.json') @{root=$root;nonce=$nonce;stopped=$stopped;pid=$java.Id;startupNonce=$startup}
                throw "M7_PHASE_TIMEOUT preserved=$root stopped=$stopped"
            }
            Start-Sleep -Milliseconds 100
        }
        } finally { if(-not $java.HasExited){if($null -eq $capsule){$capsule=New-M7OwnerCapsule $java $launch.executable $root "-Dquantumchamber.m7home.startupNonce=$startup" "-Dquantumchamber.m7home.root=$root"};Stop-M7WindowsOwner $capsule $evidence|Out-Null};Save-M7ProcessLogs $outputTask $errorTask $evidence $true }
        $java.WaitForExit();$java.Refresh();$exit=$java.ExitCode
        [IO.File]::WriteAllText((Join-Path $evidence 'stdout.log'),$outputTask.GetAwaiter().GetResult());[IO.File]::WriteAllText((Join-Path $evidence 'stderr.log'),$errorTask.GetAwaiter().GetResult())
        if($null -eq $identity){throw "M7_JVM_NOT_OBSERVED preserved=$root exit=$exit"}
        $receipt=Join-Path $root "evidence/$phase/receipt.json";if(-not(Test-Path -LiteralPath $receipt)){throw "M7_RECEIPT_MISSING preserved=$root exit=$exit"}
        $r=Get-Content -LiteralPath $receipt -Raw|ConvertFrom-Json -DateKind String
        if($r.status -cne 'PASS' -or $exit -ne $(if($phase -ceq 'crash'){77}else{0})){throw "M7_RUNTIME_FAIL exit=$exit root=$root"}
        Assert-M7FrozenClasspath $evidence;Assert-M7Lock $root;Assert-M7Consumers $consumerManifest
        foreach($file in $inputFiles){if((Get-FileHash -LiteralPath $file.copy).Hash -cne $file.sha256 -or (Get-FileHash -LiteralPath $file.source).Hash -cne (Get-M7SourceHash $file)){throw 'M7_PHASE_INPUT_DRIFT'}}
        $diskRoot=Join-Path $evidence 'disk';New-Item -ItemType Directory -Path $diskRoot|Out-Null
        $disk=@();foreach($file in @(Get-ChildItem -LiteralPath (Join-Path $root 'world') -Recurse -File|Where-Object {$_.Extension -cin @('.dat','.mca','.tmp')})){
            $relative=[IO.Path]::GetRelativePath((Join-Path $root 'world'),$file.FullName);$copy=Join-Path $diskRoot $relative;New-Item -ItemType Directory -Path (Split-Path $copy) -Force|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $copy;$disk+=@{path=$copy;sha256=(Get-FileHash -LiteralPath $copy).Hash}
        }
        $receiptCopy=Join-Path $evidence 'receipt.json';Copy-Item -LiteralPath $receipt -Destination $receiptCopy;$startupCopy=Join-Path $evidence 'startup-observed.json'
        $phases+=@{schema=1;status='PASS';head=$head;chain=$spec.chain;phase=$phase;nonce=$nonce;startupNonce=$startup;root=$root;pid=$r.pid;startTimeUtc=$r.startTimeUtc;javaExit=$exit;launcherKind='DIRECT_EXPORTED_JVM';launcherExit=$exit;gradleExportExit=$exportExit;lockReleased=$true;receipt=$receiptCopy;receiptHash=(Get-FileHash -LiteralPath $receiptCopy).Hash;startup=$startupCopy;startupHash=(Get-FileHash -LiteralPath $startupCopy).Hash;owner=(Join-Path $evidence 'owner.json');ownerHash=(Get-FileHash -LiteralPath (Join-Path $evidence 'owner.json')).Hash;disk=$disk;prelaunch=$manifestPath;prelaunchHash=(Get-FileHash -LiteralPath $manifestPath).Hash;elapsedSeconds=$timer.Elapsed.TotalSeconds}
        Write-Output "M7_PHASE PASS chain=$($spec.chain) phase=$phase exit=$exit pid=$($r.pid) elapsed=$($timer.Elapsed.TotalSeconds) root=$root"
    }
    $chains+=@{chain=$spec.chain;platform=$Platform;nonce=$nonce;root=$root;phases=$phases;elapsedSeconds=$chainWatch.Elapsed.TotalSeconds}
}
Assert-M7Consumers $consumerManifest;Assert-M7Consumers $sourceManifest
$summaryPath=Join-Path $OutputRoot 'summary.json';Write-M7Json $summaryPath @{schema=1;status='PASS';evidenceRoot=$OutputRoot;projectRoot=$repo;head=$head;platform=$Platform;stage='spike-close';only=$Only;shard=$Shard;shardCount=$ShardCount;chains=$chains;consumerManifest=$consumerManifest;consumerManifestHash=(Get-FileHash -LiteralPath $consumerManifest).Hash;elapsedSeconds=$watch.Elapsed.TotalSeconds;clockBoundary='after-owner-controls-before-summary-negatives-and-artifact-seal';inventoryHash=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json')).Hash}
Read-M7Summary $summaryPath|Out-Null
if($SelfTest){$count=Invoke-M7Negatives $summaryPath;"M7_NEGATIVES PASS count=$count"}
Write-M7EvidenceSeal $OutputRoot $summaryPath $head
"M7_NATIVE PASS summary=$summaryPath chains=$($chains.Count) elapsed=$($watch.Elapsed.TotalSeconds)"
