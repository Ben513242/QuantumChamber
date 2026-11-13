# M7 可攜 main-only 真 JVM；直接使用正式 runServer 的 Loom 匯出命令，無 testmod。
param([string]$Gradle='',[string]$OutputRoot='',[string]$ExpectedHead='', [switch]$SelfTest)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'm7-contract.ps1')
if(-not $IsWindows){throw 'M7_MAIN_WINDOWS_ONLY'}
$head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();if(-not $ExpectedHead){$ExpectedHead=$head};if($head -cne $ExpectedHead){throw 'M7_MAIN_HEAD'}
$nonce=[guid]::NewGuid().ToString('N')
if(-not $OutputRoot){$OutputRoot=Join-Path $repo ".superpowers/m7-main-$nonce"};$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(Test-Path -LiteralPath $OutputRoot){throw 'M7_MAIN_OUTPUT_EXISTS'};New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$root=Join-Path $repo "run/m7-main-$nonce";if(Test-Path -LiteralPath $root){throw 'M7_MAIN_ROOT_EXISTS'};New-Item -ItemType Directory -Path $root|Out-Null
'eula=true'|Set-Content (Join-Path $root 'eula.txt') -Encoding ascii
@('online-mode=false','server-port=0','level-name=world','view-distance=2','simulation-distance=2','level-seed=20261113','level-type=minecraft:flat','generate-structures=false','generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}')|Set-Content (Join-Path $root 'server.properties') -Encoding ascii
if(-not $Gradle){$Gradle=if($IsWindows){Join-Path $repo 'gradlew.bat'}else{Join-Path $repo 'gradlew'}}
$export=Join-Path $OutputRoot 'launch.json'
& $Gradle -I (Join-Path $PSScriptRoot 'm7-launch-export.init.gradle') runServer "-Pm7ExportTask=runServer" "-Pm7ExportRoot=$root" "-Pm7ExportNonce=$nonce" "-Pm7ExportFile=$export" --console=plain *> (Join-Path $OutputRoot 'gradle-export.log')
if($LASTEXITCODE -ne 0){throw 'M7_MAIN_EXPORT_FAILED'}
$launch=Get-Content -LiteralPath $export -Raw|ConvertFrom-Json
$args=@();$files=@();$index=0
foreach($arg in $launch.jvmArgs){
    if($arg.StartsWith('@',[StringComparison]::Ordinal)){
        $source=$arg.Substring(1);$copy=Join-Path $OutputRoot "argfile-$index.args";Copy-Item -LiteralPath $source -Destination $copy;$files+=@{kind='argfile';path=$copy;sha256=(Get-FileHash $copy).Hash};$args+='@'+$copy;$index++
    }else{$args+=$arg}
}
if($index -lt 1){throw 'M7_MAIN_ARGFILE_MISSING'}
$args=Freeze-M7DliArguments $args $OutputRoot $files
$args=Freeze-M7Classpath $args $launch.classpath $OutputRoot $repo (Join-Path $OutputRoot 'frozen-classpath');foreach($file in $files){$file.sha256=(Get-FileHash $file.path).Hash}
$args+=@($launch.main);$args+=@($launch.args)
$dli=Join-Path $repo '.gradle/loom-cache/launch.cfg';$dliCopy=Join-Path $OutputRoot 'loom-launch.cfg';Copy-Item -LiteralPath $dli -Destination $dliCopy
$files+=@{kind='dli';path=$dliCopy;sha256=(Get-FileHash $dliCopy).Hash}
$command=@{executable=$launch.executable;args=$args;root=$root;head=$head;nonce=$nonce};$command|ConvertTo-Json -Depth 8|Set-Content (Join-Path $OutputRoot 'command.json') -Encoding utf8
$info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$launch.executable;$info.WorkingDirectory=$root;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
foreach($arg in $args){$info.ArgumentList.Add($arg)}
$process=[Diagnostics.Process]::Start($info);$null=$process.Handle;$started=([DateTimeOffset]$process.StartTime).ToUniversalTime()
$owner=New-M7OwnerCapsule $process $launch.executable $root "-Dquantumchamber.m7MainNonce=$nonce" "-Dquantumchamber.m7MainRoot=$root"
$out=$process.StandardOutput.ReadToEndAsync();$err=$process.StandardError.ReadToEndAsync();$watch=[Diagnostics.Stopwatch]::StartNew();$stopSent=$false;$done=$false
try {
$observation=Assert-M7WindowsOwner $owner;$observation|ConvertTo-Json -Depth 8|Set-Content (Join-Path $OutputRoot 'owner.json') -Encoding utf8
while(-not $process.HasExited){
    $log=Join-Path $root 'logs/latest.log';$text='';if(Test-Path -LiteralPath $log){try{$stream=[IO.File]::Open($log,'Open','Read','ReadWrite');try{$reader=[IO.StreamReader]::new($stream);$text=$reader.ReadToEnd()}finally{$stream.Dispose()}}catch{}}
    if(-not $stopSent -and $text -match 'Done \('){$done=$true;$process.StandardInput.WriteLine('stop');$process.StandardInput.Flush();$stopSent=$true}
    if($watch.Elapsed.TotalSeconds -gt 180){
        Save-M7PartialRuntime $root $OutputRoot
                Save-M7ProcessLogs $out $err $OutputRoot $false
        Stop-M7WindowsOwner $owner $OutputRoot|Out-Null
        throw "M7_MAIN_TIMEOUT root=$root"
    }
    Start-Sleep -Milliseconds 100
}
} finally {if(-not $process.HasExited){Stop-M7WindowsOwner $owner $OutputRoot|Out-Null};Save-M7ProcessLogs $out $err $OutputRoot $true}
$process.WaitForExit();$stdout=$out.GetAwaiter().GetResult();$stderr=$err.GetAwaiter().GetResult();[IO.File]::WriteAllText((Join-Path $OutputRoot 'stdout.log'),$stdout);[IO.File]::WriteAllText((Join-Path $OutputRoot 'stderr.log'),$stderr)
$mods=@([regex]::Matches($stdout,'(?m)^\s*[-|+\\ ]+\s+(?<id>[a-z0-9_-]+)\s+(?<version>[^\r\n ]+)\s*$')|ForEach-Object {$_.Groups['id'].Value})
$classPath=($args -join "`n")+"`n"+(($files|ForEach-Object {Get-Content -LiteralPath $_.path -Raw}) -join "`n")
$classOriginal=Join-Path $root "classload-$($process.Id).log";$classLog=Join-Path $OutputRoot "classload-$($process.Id).log";Copy-Item -LiteralPath $classOriginal -Destination $classLog;$classes=Get-Content -LiteralPath $classLog -Raw
function Assert-M7MainLayers([string[]]$Mods,[string]$PathText,[string]$ClassText){
    if('quantumchamber' -cnotin $Mods -or 'quantumchamber-testmod' -cin $Mods){throw 'M7_MAIN_MOD_LIST'}
    if($PathText -match '(?i)testmod|client[-_.]?probe'){throw 'M7_MAIN_CLASSPATH'}
    $records=@([regex]::Matches($ClassText,'(?m)\[class,load\]\s+(?<type>\S+)\s+source:\s+(?<source>[^\r\n]+)'));if(-not $records.Count -or @($records|Where-Object {$_.Groups['type'].Value -ceq 'dev.quantumchamber.QuantumSuperpositionMod'}).Count-ne1){throw 'M7_MAIN_CLASS_ANCHOR'}
    foreach($match in [regex]::Matches($ClassText,'(?m)\[class,load\]\s+(?<type>\S+)\s+source:\s+(?<source>[^\r\n]+)')){
        if($match.Groups['type'].Value.StartsWith('dev.quantumchamber.gametest.',[StringComparison]::Ordinal) -or $match.Groups['source'].Value -match '(?i)(?:/|\\)testmod(?:/|\\)|client[-_.]?probe'){throw 'M7_MAIN_CLASS_LOAD'}
    }
}
Assert-M7FrozenClasspath $OutputRoot
Assert-M7MainLayers $mods $classPath $classes
if($process.ExitCode -ne 0 -or -not $done -or -not $stopSent -or -not $stdout.Contains('All dimensions are saved') -or -not $stdout.Contains('M3_UNIVERSE_STOPPED_VERIFIED')){throw 'M7_MAIN_STOP'}
$lock=[IO.File]::Open((Join-Path $root 'world/session.lock'),'Open','ReadWrite','None');try{$lock.Lock(0,1);$lock.Unlock(0,1)}finally{$lock.Dispose()}
foreach($f in $files){if((Get-FileHash -LiteralPath $f.path).Hash -cne $f.sha256){throw 'M7_MAIN_INPUT_DRIFT'}}
$negatives=@()
if($SelfTest){foreach($case in @('mods','classpath','classload','empty-classlog')){$reason='';try{switch($case){'mods'{Assert-M7MainLayers ($mods+@('quantumchamber-testmod')) $classPath $classes};'classpath'{Assert-M7MainLayers $mods ($classPath+"`ntestmod") $classes};'empty-classlog'{Assert-M7MainLayers $mods $classPath ''};'classload'{Assert-M7MainLayers $mods $classPath ($classes+"`n[0s][info][class,load] dev.quantumchamber.gametest.M7HomeProbe source: injected-negative")}}}catch{$reason=$_.Exception.Message};if($reason -cne @{mods='M7_MAIN_MOD_LIST';classpath='M7_MAIN_CLASSPATH';classload='M7_MAIN_CLASS_LOAD';'empty-classlog'='M7_MAIN_CLASS_ANCHOR'}[$case]){throw 'M7_MAIN_NEGATIVE_NOT_KILLED'};$negatives+=@{case=$case;reason=$reason;status='KILLED'}}}
@{schema=1;head=$head;status='PASS';platform=$(if($IsWindows){'Windows'}else{'Ubuntu'});runtimeScope='main-only';pid=$process.Id;startTimeUtc=$started.ToString('o');root=$root;nonce=$nonce;exit=$process.ExitCode;normalStop=$true;lockReleased=$true;mods=$mods;files=$files;classLog=$classLog;classLogHash=(Get-FileHash $classLog).Hash;negative=$negatives}|ConvertTo-Json -Depth 12|Set-Content (Join-Path $OutputRoot 'summary.json') -Encoding utf8
"M7_MAIN_ONLY PASS root=$root pid=$($process.Id) negatives=$($negatives.Count)"
