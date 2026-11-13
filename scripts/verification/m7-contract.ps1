# M7 Windows合約共用owner；dot-source僅定義函式，批次核准前不得呼叫controls。
function Get-M7Json([string]$Path){
    $bytes=[IO.File]::ReadAllBytes($Path);if(-not $bytes.Length){throw 'M7_JSON_EMPTY'}
    $doc=$null;try{$doc=[Text.Json.JsonDocument]::Parse([Text.Encoding]::UTF8.GetString($bytes));$names=@($doc.RootElement.EnumerateObject()|ForEach-Object Name);if(@($names|Sort-Object -Unique).Count -ne $names.Count){throw 'duplicate'};return [Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json -DateKind String}catch{throw 'M7_JSON_INVALID'}finally{if($doc){$doc.Dispose()}}
}
function Assert-M7Startup($Value){
    foreach($name in @('schema','proofOfConcept','runtimeScope','head','nonce','chain','phase','root','startupNonce','pid','startTimeUtc','javaExecutable','loadedMods','platform')){if($Value.PSObject.Properties.Name -cnotcontains $name){throw 'M7_STARTUP_FIELDS'}}
    if($Value.PSObject.Properties.Name.Count-ne14){throw 'M7_STARTUP_FIELDS'}
    if(($Value.schema -isnot [long] -and $Value.schema -isnot [int]) -or $Value.schema-ne1){throw 'M7_STARTUP_SCHEMA'}
    if($Value.platform -cne 'Windows' -or $Value.runtimeScope -cne 'testmod-present' -or $Value.proofOfConcept -isnot [bool] -or -not $Value.proofOfConcept){throw 'M7_STARTUP_SCOPE'}
    if($Value.head -isnot [string] -or $Value.head -cnotmatch '^[0-9a-f]{40}$' -or $Value.nonce -isnot [string] -or $Value.nonce -cnotmatch '^[0-9a-f]{32}$' -or $Value.startupNonce -isnot [string] -or $Value.startupNonce -cnotmatch '^[0-9a-f]{32}$'){throw 'M7_STARTUP_NONCE'}
    if(($Value.pid -isnot [long] -and $Value.pid -isnot [int]) -or $Value.pid-le0 -or $Value.startTimeUtc -isnot [string]){throw 'M7_STARTUP_PROCESS'}
    try{[DateTimeOffset]::ParseExact($Value.startTimeUtc,@('yyyy-MM-ddTHH:mm:ss.fffZ','yyyy-MM-ddTHH:mm:ssZ','o'),[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None)|Out-Null}catch{throw 'M7_STARTUP_TIME'}
    if($Value.loadedMods -isnot [array] -or @($Value.loadedMods|Where-Object {$_ -isnot [string]}).Count -or @($Value.loadedMods|Sort-Object -Unique).Count -ne $Value.loadedMods.Count -or 'quantumchamber-testmod' -cnotin $Value.loadedMods -or 'quantumchamber' -cnotin $Value.loadedMods){throw 'M7_STARTUP_MODS'}
    if([IO.Path]::GetFileName($Value.javaExecutable) -cne 'java.exe'){throw 'M7_STARTUP_EXECUTABLE'}
    if($Value.phase -cnotin @('crash','reopen') -or $Value.chain -cnotmatch '^spike-close-(ordinary|sealed|passable)-[a-z-]+$' -or -not [IO.Path]::IsPathFullyQualified($Value.root) -or [IO.Path]::GetFullPath($Value.root) -cne $Value.root -or -not [IO.Path]::IsPathFullyQualified($Value.javaExecutable)){throw 'M7_STARTUP_IDENTITY'}
}
function Initialize-M7Argv {
    if(-not $IsWindows){throw 'M7_OWNER_WINDOWS_ONLY'}
    if(-not ('M7WindowsArgv' -as [type])){
        Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;public static class M7WindowsArgv {
[DllImport("shell32.dll",SetLastError=true)]static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)]string command,out int count);
[DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr value);
public static string[] Parse(string command){int count;IntPtr value=CommandLineToArgvW(command,out count);if(value==IntPtr.Zero)throw new InvalidOperationException();try{var result=new string[count];for(int i=0;i<count;i++)result[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(value,i*IntPtr.Size));return result;}finally{LocalFree(value);}}
}
"@
    }
}
function New-M7OwnerCapsule($Process,[string]$Executable,[string]$Root,[string]$NonceToken,[string]$RootToken){
    $null=$Process.Handle
    return @{process=$Process;pid=$Process.Id;start=([DateTimeOffset]$Process.StartTime).ToUniversalTime();executable=[IO.Path]::GetFullPath($Executable);root=[IO.Path]::GetFullPath($Root);nonceToken=$NonceToken;rootToken=$RootToken}
}
function Assert-M7WindowsOwner($Owner){
    Initialize-M7Argv
    if($Owner.process.HasExited -or $Owner.process.Id -ne $Owner.pid){throw 'M7_OWNER_EXITED'}
    $live=Get-CimInstance Win32_Process -Filter "ProcessId=$($Owner.pid)";if(-not $live){throw 'M7_OWNER_MISSING'}
    if([Math]::Abs((([DateTimeOffset]$live.CreationDate)-$Owner.start).TotalMilliseconds) -ge 1){throw 'M7_OWNER_START'}
    if(-not [IO.Path]::GetFullPath($live.ExecutablePath).Equals($Owner.executable,[StringComparison]::OrdinalIgnoreCase)){throw 'M7_OWNER_EXECUTABLE'}
    $argv=[M7WindowsArgv]::Parse($live.CommandLine)
    if(@($argv|Where-Object {$_ -ceq $Owner.nonceToken}).Count-ne1){throw 'M7_OWNER_NONCE'}
    if(@($argv|Where-Object {$_ -ceq $Owner.rootToken}).Count-ne1){throw 'M7_OWNER_ROOT'}
    if(-not(Get-Command Get-OwnedIdentity -ErrorAction SilentlyContinue)){$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'probe-common.ps1'),[ref]$tokens,[ref]$errors);$d=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Get-OwnedIdentity'},$false));if($errors.Count -or $d.Count -ne 1){throw 'M7_OWNER_BASE_HELPER'};Invoke-Expression $d[0].Extent.Text}
    $base=Get-OwnedIdentity $Owner.process $Owner.start ([IO.Path]::GetFileName($Owner.executable)) $Owner.nonceToken;if(-not $base.matched){throw 'M7_OWNER_BASE_IDENTITY'}
    return @{pid=$Owner.pid;startTimeUtc=$Owner.start.ToString('o');executable=$live.ExecutablePath;root=$Owner.root;nonceToken=$Owner.nonceToken;rootToken=$Owner.rootToken;argv=$argv;matched=$true;observedUtc=[DateTime]::UtcNow.ToString('o')}
}
function Stop-M7WindowsOwner($Owner,[string]$Evidence){
    $before=Assert-M7WindowsOwner $Owner;$before|ConvertTo-Json -Depth 8|Set-Content (Join-Path $Evidence ("owner-"+$Owner.pid+"-before-stop.json")) -Encoding utf8
    $Owner.process.Kill();if(-not $Owner.process.WaitForExit(30000)){throw 'M7_OWNER_STOP_TIMEOUT'}
    @{pid=$Owner.pid;exited=$true;exit=$Owner.process.ExitCode;observedUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content (Join-Path $Evidence ("owner-"+$Owner.pid+"-after-stop.json")) -Encoding utf8;return $true
}
function Save-M7ProcessLogs($OutTask,$ErrTask,[string]$Evidence,[bool]$Complete){
    if($Complete -or $OutTask.IsCompleted){[IO.File]::WriteAllText((Join-Path $Evidence 'stdout.log'),$OutTask.GetAwaiter().GetResult())}
    if($Complete -or $ErrTask.IsCompleted){[IO.File]::WriteAllText((Join-Path $Evidence 'stderr.log'),$ErrTask.GetAwaiter().GetResult())}
}
function Invoke-M7WindowsOwnerControls([string]$Root,[string]$Head,[string[]]$Nonces=@()){
    $out=Join-Path $Root 'owner-controls';New-Item -ItemType Directory $out|Out-Null
    # 使用無害pwsh等待程序；完全不開遊戲/Java列，exact owner驗證與正式Java共用。
    $processes=@();$results=@()
    try{foreach($kind in @('owned','foreign')){$nonce=if($Nonces.Count){$Nonces[$processes.Count]}else{[guid]::NewGuid().ToString('N')};$script=Join-Path $out "$kind.ps1";[IO.File]::WriteAllText($script,'param([string]$Nonce,[string]$Root);$stream=[IO.File]::Open((Join-Path $Root ($Nonce+".lock")),"OpenOrCreate","ReadWrite","None");[IO.File]::WriteAllText((Join-Path $Root ($Nonce+".ready")),"READY");while($true){Start-Sleep -Milliseconds 100}');$info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=(Get-Process -Id $PID).Path;$info.WorkingDirectory=$out;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;foreach($arg in @('-NoProfile','-File',$script,'-Nonce',$nonce,'-Root',$out)){$info.ArgumentList.Add($arg)};$p=[Diagnostics.Process]::Start($info);$cap=New-M7OwnerCapsule $p $info.FileName $out $nonce $out;$processes+=,$cap;$cap.lock=Join-Path $out ($nonce+'.lock');$ready=Join-Path $out ($nonce+'.ready');$wait=[Diagnostics.Stopwatch]::StartNew();while(-not(Test-Path $ready)){if($p.HasExited -or $wait.Elapsed.TotalSeconds-gt10){throw 'M7_OWNER_FIXTURE_READY'};Start-Sleep -Milliseconds 100};}
        $owner=$processes[0];$foreign=$processes[1];Assert-M7WindowsOwner $owner|Out-Null
        foreach($case in @('stale','foreign','root','executable')){$copy=@{}+$owner;switch($case){'stale'{$copy.start=$owner.start.AddSeconds(-1)};'foreign'{$copy.process=$foreign.process;$copy.pid=$foreign.pid;$copy.start=$foreign.start};'root'{$copy.rootToken+='-wrong'};'executable'{$copy.executable+='-wrong'}};$reason='';try{Assert-M7WindowsOwner $copy|Out-Null}catch{$reason=$_.Exception.Message};$expected=@{stale='M7_OWNER_START';foreign='M7_OWNER_NONCE';root='M7_OWNER_ROOT';executable='M7_OWNER_EXECUTABLE'}[$case];if($reason -cne $expected){throw "M7_OWNER_CONTROL $case expected=$expected got=$reason"};$results+=@{case=$case;status='KILLED';reason=$reason};if($owner.process.HasExited -or $foreign.process.HasExited){throw 'M7_OWNER_FOREIGN_KILLED'}}
        Stop-M7WindowsOwner $owner $out|Out-Null;$released=[IO.File]::Open($owner.lock,'Open','ReadWrite','None');$released.Dispose();$results+=@{case='owned-timeout';status='PASS';foreignSurvived=(-not $foreign.process.HasExited);lockReleased=$true}
    }finally{foreach($cap in $processes){if(-not $cap.process.HasExited){Stop-M7WindowsOwner $cap $out|Out-Null}}}
    @{schema=1;status='PASS';head=$Head;platform='Windows';cases=$results}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $Root 'owner-selftest.json') -Encoding utf8
}
function Assert-M7MemberSet($Saved,[string]$Repo){
    $actual=@();foreach($directory in $Saved.roots){$path=Join-Path $Repo $directory;if(Test-Path $path){$actual+=@(Get-ChildItem -LiteralPath $path -Recurse -File|ForEach-Object FullName)}}
    foreach($file in @('build.gradle','settings.gradle','gradle.properties')){$actual+=Join-Path $Repo $file}
    $expected=@($Saved.files|ForEach-Object source)
    $actualCopies=@(Get-ChildItem -LiteralPath $Saved.copyRoot -Recurse -File|Where-Object {$_.FullName -cne (Join-Path $Saved.copyRoot 'manifest.json')}|ForEach-Object FullName);$expectedCopies=@($Saved.files|ForEach-Object copy);if(@(Compare-Object ($actualCopies|Sort-Object) ($expectedCopies|Sort-Object) -CaseSensitive).Count){throw 'M7_CONSUMER_COPY_MEMBERS'}
    if(@(Compare-Object ($expected|Sort-Object -Unique) ($actual|Sort-Object -Unique) -CaseSensitive).Count){throw 'M7_CONSUMER_MEMBERS'}
}
function Freeze-M7DliArguments([string[]]$Arguments,[string]$Evidence,$Files){
    $matched=@($Arguments|Where-Object {$_ -clike '-Dfabric.dli.config=*'});if($matched.Count-ne1){throw 'M7_DLI_ARGUMENT'}
    $actual=$matched[0].Substring('-Dfabric.dli.config='.Length).Replace('@@0020',' ');$target=Join-Path $Evidence 'loom-launch.cfg'
    Copy-Item -LiteralPath $actual -Destination $target
    return @($Arguments|ForEach-Object {if($_ -ceq $matched[0]){'-Dfabric.dli.config='+$target.Replace(' ','@@0020')}else{$_}})
}
function Assert-M7Manifest($Inventory,[string]$Repo){
    $cuts=@('before-close','after-door','after-checkpoint','after-temp-force','after-publish','after-remove');$all=@('before-close','after-close','before-door','after-door','before-checkpoint','after-checkpoint','after-temp-force','before-publish','after-publish','after-readback','after-recheck','before-remove','after-remove');$doors=@('ordinary','sealed','passable')
    if($Inventory.schema-ne2 -or $Inventory.native.Count-ne57){throw 'M7_MANIFEST_COUNT'}
    $java=Get-Content -LiteralPath (Join-Path $Repo 'src/testmod/java/dev/quantumchamber/gametest/M7CloseSpikePhases.java') -Raw
    foreach($item in @(@('POC_CUTS',$cuts),@('CUTS',$all),@('DOORS',$doors))){$m=[regex]::Match($java,('\b'+$item[0]+'=List[.]of\(([^;]+)\);'));$values=@([regex]::Matches($m.Groups[1].Value,'"([^"]+)"')|ForEach-Object {$_.Groups[1].Value});if(($values|ConvertTo-Json -Compress) -cne ($item[1]|ConvertTo-Json -Compress)){throw 'M7_MANIFEST_JAVA'}}
    $expected=@();foreach($stage in @('spike-close','close')){foreach($door in $doors){foreach($cut in $(if($stage -ceq 'spike-close'){$cuts}else{$all})){$expected+="$stage-$door-$cut"}}}
    if(@(Compare-Object ($expected|Sort-Object) (@($Inventory.native.chain)|Sort-Object) -CaseSensitive).Count -or @($Inventory.native.chain|Sort-Object -Unique).Count-ne57){throw 'M7_MANIFEST_CHAIN'}
    foreach($row in $Inventory.native){if(@(Compare-Object (@($row.PSObject.Properties.Name)|Sort-Object) (@('platform','stage','door','cut','chain','implemented','enabled','phases')|Sort-Object) -CaseSensitive).Count){throw 'M7_MANIFEST_FIELDS'};if($row.platform -cne 'Windows' -or $row.chain -cne "$($row.stage)-$($row.door)-$($row.cut)" -or $row.door -cnotin $doors -or $row.phases.Count-ne2 -or $row.phases[0] -cne 'crash' -or $row.phases[1] -cne 'reopen' -or $row.implemented -isnot [bool] -or $row.enabled -isnot [bool] -or $row.implemented -ne ($row.stage -ceq 'spike-close') -or $row.enabled -ne ($row.stage -ceq 'spike-close')){throw 'M7_MANIFEST_FIELDS'}}
}
function Assert-M7SavedPhase($Row,$Receipt,$Chain,[string]$Phase,[string]$Project,[string]$Bundle){
    foreach($name in @('startup','startupHash','owner','ownerHash','prelaunch','prelaunchHash','disk')){if($Row.PSObject.Properties.Name -cnotcontains $name){throw 'M7_SUMMARY_REQUIRED'}}
    $startup=Get-M7Json (Resolve-M7Bundle $Row.startup $Project);Assert-M7Startup $startup
    if((Get-FileHash (Resolve-M7Bundle $Row.startup $Project)).Hash -cne $Row.startupHash){throw 'M7_SUMMARY_STARTUP_HASH'}
    $owner=Get-M7Json (Resolve-M7Bundle $Row.owner $Project)
    if((Get-FileHash (Resolve-M7Bundle $Row.owner $Project)).Hash -cne $Row.ownerHash -or -not $owner.matched -or $owner.pid -ne $startup.pid -or [Math]::Abs((([DateTimeOffset]$owner.startTimeUtc)-([DateTimeOffset]$startup.startTimeUtc)).TotalMilliseconds) -ge 1 -or $owner.root -cne $startup.root -or -not $owner.executable.Equals($startup.javaExecutable,[StringComparison]::OrdinalIgnoreCase) -or $startup.pid -ne $Receipt.pid -or $startup.startTimeUtc -cne $Receipt.startTimeUtc -or $startup.javaExecutable -cne $Receipt.javaExecutable -or $startup.startupNonce -cne $Receipt.startupNonce -or $startup.root -cne $Receipt.root -or $startup.root -cne $Chain.root -or $startup.nonce -cne $Chain.nonce -or $startup.phase -cne $Phase -or $startup.chain -cne $Chain.chain){throw 'M7_SUMMARY_OWNER'}
    if($Phase -ceq 'crash' -and $Receipt.hit -cne 'before-close' -and (-not $Receipt.entityAntiClampWitness -or $Receipt.checkpointRefusalWitness-ne1)){throw 'M7_SUMMARY_TRANSACTION_WITNESS'}
    if(@($owner.argv|Where-Object {$_ -ceq ("-Dquantumchamber.m7home.startupNonce="+$startup.startupNonce)}).Count-ne1 -or @($owner.argv|Where-Object {$_ -ceq ("-Dquantumchamber.m7home.root="+$startup.root)}).Count-ne1){throw 'M7_SUMMARY_OWNER_ARGV'}
    $required=@{crash=$Chain.chain.Substring(('spike-close-').Length).Substring($Chain.chain.Substring(('spike-close-').Length).IndexOf('-')+1);reopen=''}[$Phase]
    if($Phase -ceq 'crash' -and $Receipt.hit -cne $required -or $Phase -ceq 'reopen' -and (-not $Receipt.nativeCheckpoint -or $Receipt.finalDoors.Count-ne25 -or @($Receipt.finalDoors|Where-Object {$_ -cnotmatch 'membrane=none,open=false'}).Count)){throw 'M7_SUMMARY_DISK_SEMANTICS'}
    $door=$Chain.chain.Substring('spike-close-'.Length).Split('-')[0];$observed=if($Phase -ceq 'crash'){$Receipt.doorsAtCut}else{$Receipt.diskInitialDoors};$initial=($Chain.chain.EndsWith('-before-close',[StringComparison]::Ordinal) -or $Phase -ceq 'reopen' -and $Chain.chain.EndsWith('-after-door',[StringComparison]::Ordinal));$membrane=if($initial){@{ordinary='none';sealed='sealed_x';passable='passable_x'}[$door]}else{'none'};$open=if($initial){'true'}else{'false'};if($observed.Count-ne25 -or @($observed|Where-Object {$_ -cnotmatch ('membrane='+$membrane+',open='+$open)}).Count){throw 'M7_SUMMARY_DOOR_WITNESS'}
    if(@($Row.disk).Count-eq0 -or @($Row.disk|Where-Object {$_.path.EndsWith('.mca',[StringComparison]::Ordinal)}).Count-eq0){throw 'M7_SUMMARY_DISK_REQUIRED'}
    foreach($role in @(@('active.dat','journalHash'),@('receipt.dat','receiptHash'),@('receipt.complete.tmp','tempHash'))){
        $files=@($Row.disk|Where-Object {([IO.Path]::GetFileName($_.path)) -ceq $role[0]});$hash=if($Phase -ceq 'crash'){$Receipt.($role[1])}elseif($role[0] -ceq 'active.dat'){'ABSENT'}else{$Receipt.receiptHash}
        if($hash -ceq 'ABSENT'){if($files.Count){throw 'M7_SUMMARY_DISK_PRESENCE'}}elseif($files.Count-ne1 -or $files[0].sha256 -cne $hash){throw 'M7_SUMMARY_DISK_ROLE'}
    }
}
function Write-M7EvidenceSeal([string]$Root,[string]$Summary,[string]$Head){
    $required=@('summary.json','owner-selftest.json','negative-results.json');foreach($name in $required){if(-not(Test-Path (Join-Path $Root $name))){throw 'M7_SEAL_REQUIRED'}}
    $all=@(Get-ChildItem -LiteralPath $Root -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($Root,$_.FullName).Replace('\','/');sha256=(Get-FileHash $_.FullName).Hash}})
    $all|ConvertTo-Json -Depth 6|Set-Content ($Root+'.files.json') -Encoding utf8
    [IO.Compression.ZipFile]::CreateFromDirectory($Root,$Root+'.zip')
    @{schema=1;head=$Head;platform='Windows';artifact=$Root+'.zip';artifactSha256=(Get-FileHash ($Root+'.zip')).Hash;manifest=$Root+'.files.json';manifestSha256=(Get-FileHash ($Root+'.files.json')).Hash;summarySha256=(Get-FileHash $Summary).Hash}|ConvertTo-Json|Set-Content ($Root+'.descriptor.json') -Encoding utf8
}
function Invoke-M7WindowsAggregate([string]$Root,[string]$Output,[string]$Head,$Inventory){
    $descriptors=@(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.descriptor.json');if( -not $descriptors.Count){throw 'M7_AGGREGATE_ARTIFACT_REQUIRED'};$chains=@();$nonces=@();$roots=@();$jvms=@()
    foreach($descriptor in $descriptors){$d=Get-M7Json $descriptor.FullName;$zip=Join-Path $descriptor.DirectoryName ([IO.Path]::GetFileName($d.artifact));if($d.head -cne $Head -or $d.platform -cne 'Windows' -or (Get-FileHash $zip).Hash -cne $d.artifactSha256){throw 'M7_AGGREGATE_DIGEST'};$target=Join-Path $Output ('bundle-'+[guid]::NewGuid().ToString('N'));$manifestPath=Join-Path $descriptor.DirectoryName ([IO.Path]::GetFileName($d.manifest));if((Get-FileHash $manifestPath).Hash -cne $d.manifestSha256){throw 'M7_AGGREGATE_MANIFEST_DIGEST'};$manifest=Get-Content $manifestPath -Raw|ConvertFrom-Json
        $archive=[IO.Compression.ZipFile]::OpenRead($zip);try{foreach($entry in $archive.Entries){if([IO.Path]::IsPathRooted($entry.FullName) -or @($entry.FullName.Replace('\','/').Split('/')|Where-Object {$_ -ceq '..'}).Count){throw 'M7_AGGREGATE_ARCHIVE_PATH'}}}finally{$archive.Dispose()}
        [IO.Compression.ZipFile]::ExtractToDirectory($zip,$target);$actual=@(Get-ChildItem $target -Recurse -File|ForEach-Object {[IO.Path]::GetRelativePath($target,$_.FullName).Replace('\','/')});if(@(Compare-Object ($actual|Sort-Object) (@($manifest.path)|Sort-Object) -CaseSensitive).Count){throw 'M7_AGGREGATE_MEMBERS'};foreach($file in $manifest){if((Get-FileHash (Join-Path $target $file.path)).Hash -cne $file.sha256){throw 'M7_AGGREGATE_RAW_HASH'}};Assert-M7SavedSelfTests $target $Head;$summary=Get-M7Json (Join-Path $target 'summary.json');if((Get-FileHash (Join-Path $target 'summary.json')).Hash -cne $d.summarySha256){throw 'M7_AGGREGATE_BINDING'}
        foreach($chain in $summary.chains){$chains+=$chain.chain;$nonces+=$chain.nonce;$roots+=$chain.root;foreach($phase in $chain.phases){$jvms+="$($phase.pid)@$($phase.startTimeUtc)"}}
        # 完整原件驗證仍由同一saved-summary oracle負責，不能用metadata成功取代。
        $script:BundleRoot=$target;Read-M7Summary (Join-Path $target 'summary.json')|Out-Null;$script:BundleRoot=''
    }
    $expected=@($Inventory.native|Where-Object {$_.stage -ceq 'spike-close'}|ForEach-Object chain);if($chains.Count-ne18 -or @($chains|Sort-Object -Unique).Count-ne18 -or @($roots|Sort-Object -Unique).Count-ne18 -or @($nonces|Sort-Object -Unique).Count-ne18 -or @($jvms|Sort-Object -Unique).Count-ne36 -or @(Compare-Object ($chains|Sort-Object) ($expected|Sort-Object) -CaseSensitive).Count){throw 'M7_AGGREGATE_EXACT_SET'}
    @{schema=1;head=$Head;status='PASS';platform='Windows';chains=18;phases=36}|ConvertTo-Json|Set-Content (Join-Path $Output 'summary.json') -Encoding utf8
}
function Get-M7JarRoles([string[]]$Paths){
    $result=@();foreach($path in $Paths){$zip=[IO.Compression.ZipFile]::OpenRead($path);try{$names=@($zip.Entries.FullName);$classes=@($names|Where-Object {$_ -cmatch '[.]class$'}).Count;$sources=@($names|Where-Object {$_ -cmatch '[.]java$'}).Count;$isSources=[IO.Path]::GetFileName($path).EndsWith('-sources.jar',[StringComparison]::Ordinal);if($isSources -and $sources-gt0 -and $classes-eq0){$role='sources'}elseif( -not $isSources -and $classes-gt0 -and $sources-eq0){$role='release'}else{throw 'M7_JAR_ROLE'}}finally{$zip.Dispose()};$result+=@{role=$role;path=$path;sha256=(Get-FileHash $path).Hash}}
    if($result.Count-ne2 -or @($result|Where-Object {$_.role -ceq 'release'}).Count-ne1 -or @($result|Where-Object {$_.role -ceq 'sources'}).Count-ne1){throw 'M7_JAR_ROLES'};return $result
}
function Assert-M7RawSummary([string]$Root,[string]$Head){
    $summary=Get-M7Json (Join-Path $Root 'summary.json');if($summary.status -cne 'PASS' -or $summary.head -cne $Head){throw 'M7_FINAL_SUMMARY'}
    $files=Get-Content (Join-Path $Root 'raw-files.json') -Raw|ConvertFrom-Json;if(-not@($files).Count){throw 'M7_FINAL_RAW_REQUIRED'};foreach($file in $files){if((Get-FileHash $file.path).Hash -cne $file.sha256){throw 'M7_FINAL_RAW_HASH'}}
    $roles=Get-M7JarRoles @((Get-ChildItem (Join-Path $Root 'raw/artifacts') -Filter '*.jar').FullName);return $summary
}
function Assert-M7EvidenceSeal([string]$Root,[string]$Head){
    $descriptor=Get-M7Json ($Root+'.descriptor.json');if($descriptor.head -cne $Head -or $descriptor.platform -cne 'Windows'){throw 'M7_SEAL_IDENTITY'}
    if((Get-FileHash ($Root+'.zip')).Hash -cne $descriptor.artifactSha256 -or (Get-FileHash ($Root+'.files.json')).Hash -cne $descriptor.manifestSha256){throw 'M7_SEAL_DIGEST'}
    Assert-M7SavedSelfTests $Root $Head
    $manifest=Get-Content ($Root+'.files.json') -Raw|ConvertFrom-Json;$actual=@(Get-ChildItem $Root -Recurse -File|ForEach-Object {[IO.Path]::GetRelativePath($Root,$_.FullName).Replace('\','/')});if(@(Compare-Object (@($manifest.path)|Sort-Object) ($actual|Sort-Object) -CaseSensitive).Count){throw 'M7_SEAL_MEMBERS'}
    foreach($file in $manifest){if((Get-FileHash (Join-Path $Root $file.path)).Hash -cne $file.sha256){throw 'M7_SEAL_RAW_HASH'}}
    $owner=Get-M7Json (Join-Path $Root 'owner-selftest.json');if($owner.status -cne 'PASS' -or $owner.head -cne $Head -or $owner.platform -cne 'Windows' -or $owner.cases.Count-ne5){throw 'M7_SEAL_OWNER_CONTROLS'}
    $cases=Get-Content (Join-Path $Root 'negative-results.json') -Raw|ConvertFrom-Json;$expected=@{phase='M7_SUMMARY_PHASE_SET';pid='M7_SUMMARY_RECEIPT_IDENTITY';root='M7_SUMMARY_IDENTITY';hash='M7_SUMMARY_HASH';head='M7_SUMMARY_HEAD';pass='M7_SUMMARY_IDENTITY';skip='M7_SUMMARY_CHAIN_SET';platform='M7_SUMMARY_STATUS';disk='M7_SUMMARY_DISK_REQUIRED'}
    if($cases.Count -ne $expected.Count -or @($cases.case|Sort-Object -Unique).Count -ne $expected.Count){throw 'M7_SEAL_NEGATIVE_SET'};foreach($case in $cases){if($case.status -cne 'KILLED' -or $case.reason -cne $expected[$case.case]){throw 'M7_SEAL_NEGATIVE_REASON'}}
}
function Invoke-M7FrozenGame([string]$Gradle,[string]$Repo,[string]$Root,[string]$Evidence,[string]$Nonce,[string]$Head){
    $export=Join-Path $Evidence 'game-launch.json';New-Item -ItemType Directory -Path $Evidence -Force|Out-Null
    & $Gradle -I (Join-Path $Repo 'scripts/verification/m7-launch-export.init.gradle') runGameTestM7 '-Pm7ExportTask=runGameTestM7' "-Pm7ExportFile=$export" "-Pm7GameNonce=$Nonce" "-Pm7Head=$Head" --console=plain *> (Join-Path $Evidence 'game-export.log');if($LASTEXITCODE-ne0){throw 'M7_GAME_EXPORT'}
    $launch=Get-M7Json $export;$arguments=@();$files=@();$n=0;foreach($argument in $launch.jvmArgs){if($argument.StartsWith('@',[StringComparison]::Ordinal)){$copy=Join-Path $Evidence "game-$n.args";Copy-Item -LiteralPath $argument.Substring(1) -Destination $copy;$arguments+='@'+$copy;$files+=@{path=$copy;sha256=(Get-FileHash $copy).Hash};$n++}else{$arguments+=$argument}}
    if($n-lt1){throw 'M7_GAME_ARGFILE'};$arguments=Freeze-M7DliArguments $arguments $Evidence $files;$arguments=Freeze-M7Classpath $arguments $launch.classpath $Evidence $Repo (Join-Path $Evidence 'frozen-classpath');foreach($file in $files){$file.sha256=(Get-FileHash $file.path).Hash};$arguments+=@($launch.main);$arguments+=@($launch.args);$dli=Join-Path $Evidence 'loom-launch.cfg';$files+=@{path=$dli;sha256=(Get-FileHash $dli).Hash}
    @{head=$Head;nonce=$Nonce;root=$Root;arguments=$arguments;files=$files;recordedBeforeJvmUtc=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json -Depth 8|Set-Content (Join-Path $Evidence 'game-prelaunch.json') -Encoding utf8
    New-Item -ItemType Directory -Path $Root -Force|Out-Null;$info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$launch.executable;$info.WorkingDirectory=$Root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;foreach($argument in $arguments){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::Start($info);$owner=New-M7OwnerCapsule $process $launch.executable $Root "-Dquantumchamber.m7game.nonce=$Nonce" "-Dquantumchamber.m7game.root=$Root";$out=$process.StandardOutput.ReadToEndAsync();$err=$process.StandardError.ReadToEndAsync();$watch=[Diagnostics.Stopwatch]::StartNew()
    try{Assert-M7WindowsOwner $owner|ConvertTo-Json -Depth 8|Set-Content (Join-Path $Evidence 'game-owner.json') -Encoding utf8;while( -not $process.HasExited){if($watch.Elapsed.TotalSeconds-gt300){throw 'M7_GAME_TIMEOUT'};Start-Sleep -Milliseconds 100}}
    finally{if( -not $process.HasExited){Stop-M7WindowsOwner $owner $Evidence|Out-Null};Save-M7ProcessLogs $out $err $Evidence $true}
    Assert-M7FrozenClasspath $Evidence
    if($process.ExitCode-ne0){throw 'M7_GAME_EXIT'};foreach($file in $files){if((Get-FileHash $file.path).Hash -cne $file.sha256){throw 'M7_GAME_INPUT_DRIFT'}}
}
function Assert-M7SavedSelfTests([string]$Root,[string]$Head){
    $owner=Get-M7Json (Join-Path $Root 'owner-selftest.json');$ownerExpected=@{stale='M7_OWNER_START';foreign='M7_OWNER_NONCE';root='M7_OWNER_ROOT';executable='M7_OWNER_EXECUTABLE'}
    if($owner.status -cne 'PASS' -or $owner.head -cne $Head -or $owner.platform -cne 'Windows' -or $owner.cases.Count-ne5){throw 'M7_SELFTEST_OWNER_SET'}
    foreach($name in $ownerExpected.Keys){$r=@($owner.cases|Where-Object {$_.case -ceq $name});if($r.Count-ne1 -or $r[0].status -cne 'KILLED' -or $r[0].reason -cne $ownerExpected[$name]){throw 'M7_SELFTEST_OWNER_REASON'}}
    $timeout=@($owner.cases|Where-Object {$_.case -ceq 'owned-timeout'});if($timeout.Count-ne1 -or $timeout[0].status -cne 'PASS' -or  -not $timeout[0].foreignSurvived){throw 'M7_SELFTEST_OWNER_TIMEOUT'}
    $negative=Get-Content (Join-Path $Root 'negative-results.json') -Raw|ConvertFrom-Json;$expected=@{phase='M7_SUMMARY_PHASE_SET';pid='M7_SUMMARY_RECEIPT_IDENTITY';root='M7_SUMMARY_IDENTITY';hash='M7_SUMMARY_HASH';head='M7_SUMMARY_HEAD';pass='M7_SUMMARY_IDENTITY';skip='M7_SUMMARY_CHAIN_SET';platform='M7_SUMMARY_STATUS';disk='M7_SUMMARY_DISK_REQUIRED'}
    if($negative.Count-ne9 -or @($negative.case|Sort-Object -Unique).Count-ne9){throw 'M7_SELFTEST_NEGATIVE_SET'};foreach($r in $negative){if($r.status -cne 'KILLED' -or $r.reason -cne $expected[$r.case]){throw 'M7_SELFTEST_NEGATIVE_REASON'}}
}
function Invoke-M7OfflineControls([string]$Root,[string]$Repo,[string]$Head){
    New-Item -ItemType Directory $Root|Out-Null;$results=@();$valid=[pscustomobject]@{schema=1;proofOfConcept=$true;runtimeScope='testmod-present';head=$Head;nonce='e7a1ee0077a14bcba8d23c01d0171a10';chain='spike-close-ordinary-after-checkpoint';phase='crash';root=(Join-Path $Repo 'run/m7-home-e7a1ee0077a14bcba8d23c01d0171a10');startupNonce='c9812222de934002a1f1bbaaccdd0011';pid=123;startTimeUtc='2026-11-13T00:00:00.123Z';javaExecutable=(Join-Path $env:JAVA_HOME 'bin/java.exe');loadedMods=@('quantumchamber','quantumchamber-testmod');platform='Windows'}
    Assert-M7Startup $valid;$results+=@{case='startup-valid';status='PASS';scope='offline'}
    foreach($name in @('schema','mods','nonce','pid','executable')){$value=$valid|ConvertTo-Json -Depth 8|ConvertFrom-Json -DateKind String;switch($name){'schema'{$value.schema='1'};'mods'{$value.loadedMods='quantumchamber-testmod'};'nonce'{$value.nonce=1};'pid'{$value.pid='123'};'executable'{$value.javaExecutable=(Join-Path $env:JAVA_HOME 'bin/not-java.exe')}};$reason='';try{Assert-M7Startup $value}catch{$reason=$_.Exception.Message};$expected=@{schema='M7_STARTUP_SCHEMA';mods='M7_STARTUP_MODS';nonce='M7_STARTUP_NONCE';pid='M7_STARTUP_PROCESS';executable='M7_STARTUP_EXECUTABLE'}[$name];if($reason-cne$expected){throw "M7_OFFLINE_STARTUP $name expected=$expected got=$reason"};$results+=@{case="startup-$name";status='KILLED';reason=$reason}}
    $inventory=Get-Content (Join-Path $Repo 'scripts/verification/m7-inventory.json') -Raw|ConvertFrom-Json;Assert-M7Manifest $inventory $Repo;$results+=@{case='manifest-valid';status='PASS';scope='offline'}
    foreach($name in @('phase','cut','implemented','platform')){$value=$inventory|ConvertTo-Json -Depth 20|ConvertFrom-Json;switch($name){'phase'{$value.native[0].phases=@('crash')};'cut'{$value.native[0].cut='wrong'};'implemented'{$value.native[0].implemented=$false};'platform'{$value.native[0].platform='Ubuntu'}};$reason='';try{Assert-M7Manifest $value $Repo}catch{$reason=$_.Exception.Message};if($reason-cne'M7_MANIFEST_FIELDS'){throw "M7_OFFLINE_MANIFEST $name got=$reason"};$results+=@{case="manifest-$name";status='KILLED';reason=$reason}}
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $Repo 'scripts/verification/m7-ci-main-only.ps1'),[ref]$tokens,[ref]$errors);if($errors.Count){throw 'M7_OFFLINE_MAIN_PARSE'};$definition=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name-ceq'Assert-M7MainLayers'},$false));if($definition.Count-ne1){throw 'M7_OFFLINE_MAIN_FUNCTION'};Invoke-Expression $definition[0].Extent.Text
    $class='[0.100s][info][class,load] dev.quantumchamber.QuantumSuperpositionMod source: fixture-production.jar';Assert-M7MainLayers @('quantumchamber') 'fixture-production.jar' $class
    foreach($case in @('empty','foreign')){$reason='';try{if($case-ceq'empty'){Assert-M7MainLayers @('quantumchamber') 'fixture-production.jar' ''}else{Assert-M7MainLayers @('quantumchamber') 'fixture-production.jar' ($class+"`n[0s][info][class,load] dev.quantumchamber.gametest.M7HomeProbe source: fixture")}}catch{$reason=$_.Exception.Message};$expected=if($case-ceq'empty'){'M7_MAIN_CLASS_ANCHOR'}else{'M7_MAIN_CLASS_LOAD'};if($reason-cne$expected){throw 'M7_OFFLINE_MAIN_CONTROL'};$results+=@{case="class-$case";status='KILLED';reason=$reason}}
    $jars=@((Get-ChildItem (Join-Path $Repo 'build/libs') -Filter '*.jar').FullName);Get-M7JarRoles $jars|Out-Null;$release=@($jars|Where-Object {-not $_.EndsWith('-sources.jar',[StringComparison]::Ordinal)})[0];$reason='';try{Get-M7JarRoles @($release,$release)|Out-Null}catch{$reason=$_.Exception.Message};if($reason-cne'M7_JAR_ROLES'){throw 'M7_OFFLINE_JAR_CONTROL'};$results+=@{case='jar-duplicate-role';status='KILLED';reason=$reason}
    $results|ConvertTo-Json -Depth 7|Set-Content (Join-Path $Root 'results.json') -Encoding utf8
}
function Invoke-M7ConsumerCopyControls([string]$Manifest,[string]$Root,[string]$Repo){
    New-Item -ItemType Directory $Root|Out-Null;$saved=Get-M7Json $Manifest;Assert-M7MemberSet $saved $Repo
    $results=@(@{case='consumer-members-valid';status='PASS';scope='offline'})
    # 只改新建的fixture copy，永不改原source、compiled或先前原件。
    $copyRoot=Join-Path $Root 'copies';New-Item -ItemType Directory $copyRoot|Out-Null
    $model=[pscustomobject]@{copyRoot=$copyRoot;roots=@();files=@([pscustomobject]@{source=(Join-Path $Repo 'build.gradle');copy=(Join-Path $copyRoot 'build.gradle')},[pscustomobject]@{source=(Join-Path $Repo 'settings.gradle');copy=(Join-Path $copyRoot 'settings.gradle')},[pscustomobject]@{source=(Join-Path $Repo 'gradle.properties');copy=(Join-Path $copyRoot 'gradle.properties')})}
    foreach($file in $model.files){Copy-Item -LiteralPath $file.source -Destination $file.copy};Assert-M7MemberSet $model $Repo
    [IO.File]::WriteAllText((Join-Path $copyRoot 'unexpected.class'),'negative');$reason='';try{Assert-M7MemberSet $model $Repo}catch{$reason=$_.Exception.Message};if($reason-cne'M7_CONSUMER_COPY_MEMBERS'){throw 'M7_CONSUMER_CONTROL_ADD'};$results+=@{case='consumer-add-copy';status='KILLED';reason=$reason}
    $results|ConvertTo-Json|Set-Content (Join-Path $Root 'results.json') -Encoding utf8
}
function Invoke-M7ArtifactDigestControl([string]$Root,[string]$Native,[string]$Head){
    New-Item -ItemType Directory $Root|Out-Null;$target=Join-Path $Root 'tampered';foreach($extension in @('.zip','.files.json','.descriptor.json')){Copy-Item -LiteralPath ($Native+$extension) -Destination ($target+$extension)}
    [IO.File]::AppendAllText(($target+'.zip'),'EXPECTED_NEGATIVE');$reason='';try{Assert-M7EvidenceSeal $target $Head}catch{$reason=$_.Exception.Message};if($reason-cne'M7_SEAL_DIGEST'){throw "M7_DIGEST_CONTROL expected=M7_SEAL_DIGEST got=$reason"}
    @{case='artifact-body-tamper';status='KILLED';reason=$reason;head=$Head}|ConvertTo-Json|Set-Content (Join-Path $Root 'result.json') -Encoding utf8
}
function Invoke-M7ConsumerDeltaControls([string]$Root,[string]$Repo){
    New-Item -ItemType Directory $Root|Out-Null;$results=@()
    foreach($kind in @('add','delete','content')){
        $case=Join-Path $Root $kind;$source=Join-Path $case 'source';$copy=Join-Path $case 'copy';New-Item -ItemType Directory (Join-Path $source 'classes'),(Join-Path $copy 'classes') -Force|Out-Null
        foreach($name in @('build.gradle','settings.gradle','gradle.properties','classes/keep.class')){[IO.File]::WriteAllText((Join-Path $source $name),'fixture');Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $copy $name)}
        $model=[pscustomobject]@{copyRoot=$copy;roots=@('classes');files=@(Get-ChildItem $source -Recurse -File|ForEach-Object {@{source=$_.FullName;copy=(Join-Path $copy ([IO.Path]::GetRelativePath($source,$_.FullName)));sha256=(Get-FileHash $_.FullName).Hash}})}
        Assert-M7MemberSet $model $source
        switch($kind){'add'{[IO.File]::WriteAllText((Join-Path $source 'classes/added.class'),'negative')};'delete'{Move-Item -LiteralPath (Join-Path $source 'classes/keep.class') -Destination (Join-Path $case 'retained-removed.class')};'content'{[IO.File]::AppendAllText((Join-Path $copy 'classes/keep.class'),'negative')}}
        $reason='';try{Assert-M7MemberSet $model $source;foreach($file in $model.files){if((Get-FileHash $file.copy).Hash-cne$file.sha256){throw 'M7_CONSUMER_CONTENT'}}}catch{$reason=$_.Exception.Message};$expected=if($kind-ceq'content'){'M7_CONSUMER_CONTENT'}else{'M7_CONSUMER_MEMBERS'};if($reason-cne$expected){throw "M7_CONSUMER_DELTA $kind got=$reason"};$results+=@{case=$kind;status='KILLED';reason=$reason;scope='offline-fixture-only'}
    };$results|ConvertTo-Json|Set-Content (Join-Path $Root 'result.json') -Encoding utf8
}
function Save-M7PartialRuntime([string]$Root,[string]$Evidence){
    $path=Join-Path $Root 'logs/latest.log';if(Test-Path $path){$input=[IO.File]::Open($path,'Open','Read','ReadWrite');try{$output=[IO.File]::Create((Join-Path $Evidence 'runtime-before-stop.log'));try{$input.CopyTo($output)}finally{$output.Dispose()}}finally{$input.Dispose()}}
}
function Freeze-M7Classpath([string[]]$Arguments,[string]$Classpath,[string]$Evidence,[string]$Repo,[string]$Store){
    New-Item -ItemType Directory -Path $Store -Force|Out-Null;$mapping=@{};$manifest=@()
    foreach($directory in @('build/classes','build/resources')){$source=Join-Path $Repo $directory;$copy=Join-Path $Store $directory;if(-not(Test-Path $copy)){New-Item -ItemType Directory -Path (Split-Path $copy) -Force|Out-Null;Copy-Item -LiteralPath $source -Destination $copy -Recurse};$mapping[$source]=$copy;foreach($file in Get-ChildItem $source -Recurse -File){$target=Join-Path $copy ([IO.Path]::GetRelativePath($source,$file.FullName));if((Get-FileHash $target).Hash-cne(Get-FileHash $file.FullName).Hash){throw 'M7_CLASSPATH_FROZEN_DRIFT'};$manifest+=@{source=$file.FullName;copy=$target;sha256=(Get-FileHash $target).Hash}}}
    $paths=@();foreach($source in $Classpath.Split([IO.Path]::PathSeparator)){
        if(-not$source){continue};if(Test-Path $source -PathType Leaf){$hash=(Get-FileHash $source).Hash;$copy=Join-Path $Store ('dependencies/'+$hash+'.jar');if(-not(Test-Path $copy)){New-Item -ItemType Directory -Path (Split-Path $copy) -Force|Out-Null;Copy-Item -LiteralPath $source -Destination $copy};$mapping[$source]=$copy;$manifest+=@{source=$source;copy=$copy;sha256=$hash};$paths+=$copy}
        else{$mapped=$source;foreach($key in @($mapping.Keys|Sort-Object Length -Descending)){if($source.StartsWith($key,[StringComparison]::OrdinalIgnoreCase)){$mapped=$mapping[$key]+$source.Substring($key.Length);break}};if($mapped-ceq$source){throw 'M7_CLASSPATH_UNKNOWN_DIRECTORY'};$paths+=$mapped}
    }
    $file=Join-Path $Evidence 'actual-classpath.args';$quoted='"'+(($paths-join[IO.Path]::PathSeparator).Replace('\','\\').Replace('"','\"'))+'"';[IO.File]::WriteAllText($file,"-classpath`n$quoted`n",[Text.UTF8Encoding]::new($false))
    $result=@();$replaced=$false;foreach($argument in $Arguments){if($argument.StartsWith('@',[StringComparison]::Ordinal)){if($replaced){throw 'M7_CLASSPATH_MULTIPLE_ARGFILES'};$result+='@'+$file;$replaced=$true}else{$result+=$argument}}
    $dliArgument=@($result|Where-Object {$_ -clike '-Dfabric.dli.config=*'})[0];$dli=$dliArgument.Substring('-Dfabric.dli.config='.Length).Replace('@@0020',' ');$text=[IO.File]::ReadAllText($dli)
    $referenced=@();foreach($line in ($text -split '\r?\n')){if($line -match '^\s*fabric[.]classPathGroups=(.+)$'){$referenced+=@($Matches[1].Split([IO.Path]::PathSeparator)|Where-Object {$_})}elseif($line -match '^\s*fabric[.]gameJarPath(?:[.]client)?=(.+)$'){$referenced+=$Matches[1]}}
    foreach($source in $referenced){if((Test-Path $source -PathType Leaf) -and -not $mapping.ContainsKey($source)){$hash=(Get-FileHash $source).Hash;$copy=Join-Path $Store ('dependencies/'+$hash+'.jar');if(-not(Test-Path $copy)){Copy-Item -LiteralPath $source -Destination $copy};$mapping[$source]=$copy;$manifest+=@{source=$source;copy=$copy;sha256=$hash}}}
    foreach($key in @($mapping.Keys|Sort-Object Length -Descending)){$text=$text.Replace($key,$mapping[$key])}
    foreach($match in [regex]::Matches($text,'(?m)^\s*(?:fabric[.]remapClasspathFile|log4j[.]configurationFile)=(.+)\r?$')){$source=$match.Groups[1].Value.Trim();if(Test-Path $source -PathType Leaf){$copy=Join-Path $Evidence ([IO.Path]::GetFileName($source));$body=[IO.File]::ReadAllText($source);foreach($key in @($mapping.Keys|Sort-Object Length -Descending)){$body=$body.Replace($key,$mapping[$key])};[IO.File]::WriteAllText($copy,$body);$text=$text.Replace($source,$copy);$manifest+=@{source=$source;copy=$copy;sha256=(Get-FileHash $copy).Hash}}}
    foreach($entry in $manifest){$entry.sourceHash=(Get-FileHash $entry.source).Hash}
    $collection=@($manifest|ForEach-Object {$_.source+'|'+$_.sourceHash}|Sort-Object);$baseline=$Store+'.collection.json';if(Test-Path $baseline){$before=@(Get-Content $baseline -Raw|ConvertFrom-Json);if(@(Compare-Object $before $collection -CaseSensitive).Count){throw 'M7_CLASSPATH_COLLECTION_DRIFT'}}else{$collection|ConvertTo-Json|Set-Content $baseline -Encoding utf8}
    [IO.File]::WriteAllText($dli,$text);$manifest|ConvertTo-Json -Depth 6|Set-Content (Join-Path $Evidence 'frozen-classpath.json') -Encoding utf8
    return $result
}
function Get-M7SourceHash($File){
    if($File -is [Collections.IDictionary]){if($File.Contains('sourceHash')){return $File.sourceHash}}elseif($null-ne$File.PSObject.Properties['sourceHash']){return $File.sourceHash};return $File.sha256
}
function Assert-M7FrozenClasspath([string]$Evidence){
    foreach($file in (Get-Content (Join-Path $Evidence 'frozen-classpath.json') -Raw|ConvertFrom-Json)){if((Get-FileHash $file.copy).Hash-cne$file.sha256 -or (Get-FileHash $file.source).Hash-cne$file.sourceHash){throw 'M7_FROZEN_CLASSPATH_DRIFT'}}
}
