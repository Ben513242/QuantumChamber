# gates.ps1：M1–M4 runtime gate harness（Windows、PowerShell 7）。fresh world 以可恢復 move＋逐檔 hash exact restore 處理；
# 失敗 root 一律保留、不在同 root 重跑。gate root＝<EvidenceRoot>/gate-<Label>-<GUID>，結束時印 GATE_ROOT=<path>。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-gates.ps1（SHA-256 34eaecabf9d4685771d53482130c04d496bbdf3e396f6c2d6fc06055249851e8）。
# 相對來源的改動：repo root、gate root、Gradle、init script 改為參數或本目錄；移除 RedOwner（Task 9 owner 修正前的一次性 RED）；
# classload／m4-boundary 只複製本次觀察到的 JVM PID（整合審查 R1）；Inventory 輸出改寫到 gate root。
# M5 F3c（plan 決策 6）：新增 M5GameTest（runGameTestM5，獨立的 fresh 世界 run/gametest-m5）；三個 GameTest run 都核對 XML 是本次 run 寫出的，
# 而且 testcase 集合等於原始碼依 batch 前綴矩陣應屬於該 run 的集合（scope.json）。M5 run 不檢查 M4 boundary oracle，改核對每個 M5 test
# 自己斷言的 catalog 增量，以及 suite 結束時的 catalog（m5-oracle.json；F3c fix round 1 加上 suite 結束）。
# 參數：
#   -Mode          Inventory｜Main｜GameTest｜Full｜Legacy｜M5GameTest｜Windows｜M3
#   -Label         gate root 標籤（英數與連字號）
#   -Only          M3 只跑 universe 或 transfer（空字串＝兩者）
#   -EvidenceRoot  gate root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
# M3 probe 的 evidence owner 由 testmod ProbeEvidenceOwner 固定為 <repo>/.superpowers/sdd/2026-09-21-m4-candidate-doors，不受 -EvidenceRoot 影響。
param(
    [Parameter(Mandatory)][ValidateSet('Inventory','Main','GameTest','Full','Legacy','M5GameTest','Windows','M3','M6Compile','M6JUnit','M6GameTest')][string]$Mode,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label='run',
    [ValidateSet('','universe','transfer')][string]$Only='',
    [string]$EvidenceRoot='',
    [string]$TestClass='',
    [string]$Batch='',
    [string]$Gradle=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'gate-evidence.ps1')
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach($marker in 'build.gradle','settings.gradle'){if(-not(Test-Path -LiteralPath (Join-Path $repo $marker) -PathType Leaf)){throw "repo root 缺少 ${marker}：$repo"}}
if(-not $Gradle){$dist='C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat';$Gradle=if(Test-Path -LiteralPath $dist -PathType Leaf){$dist}else{Join-Path $repo 'gradlew.bat'}}
if(-not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）"}
# testmod 固定的 M3 probe evidence owner（canonical 原生路徑）。
$probeOwner=[IO.Path]::GetFullPath((Join-Path $repo '.superpowers/sdd/2026-09-21-m4-candidate-doors'))
$id=[Guid]::NewGuid().ToString('N')
Set-Location -LiteralPath $repo
$priorJavaOptions=$env:JAVA_TOOL_OPTIONS
if($Batch){
    if($Mode -ne 'M6GameTest' -or $Batch -notmatch '^m6_universe[\w-]*(,m6_universe[\w-]*)*$'){throw 'M6_SCOPE_MISMATCH：Batch 僅供 M6GameTest 的 M6 batch'}
    # 環境變更在最外層 try/finally 內才套用。
}
function Save-Json($path,$value) { $value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path -Encoding utf8 }
function Check-Path([string]$path) {
    $full=[IO.Path]::GetFullPath($path)
    if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw "目標不在 worktree：$full" }
    $cursor=$full
    while($cursor) {
        if(Test-Path -LiteralPath $cursor) {
            $item=Get-Item -LiteralPath $cursor -Force
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "拒絕 reparse：$cursor" }
        }
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if($parent -eq $cursor) { break }; $cursor=$parent
    }
    return $full
}
# evidence 目錄必須被 gitignore，不得落在 tracked tree。
function Assert-Ignored([string]$path) {
    git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$path).Replace('\','/')+'/x')
    if($LASTEXITCODE -ne 0) { throw "evidence 目錄未被 gitignore：$path" }
}
function Files-Hash([string]$root) {
    if(-not(Test-Path -LiteralPath $root)) { return @() }
    return @(Get-ChildItem -LiteralPath $root -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($root,$_.FullName);length=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
}
function Root-State([string]$root) {
    $catalog=Join-Path $root 'world/data/quantumchamber_universes.dat'
    $dims=Join-Path $root 'world/dimensions'
    return [ordered]@{root=$root;worldExists=(Test-Path -LiteralPath (Join-Path $root 'world'));catalogExists=(Test-Path -LiteralPath $catalog);catalogSha256=$(if(Test-Path -LiteralPath $catalog){(Get-FileHash -LiteralPath $catalog -Algorithm SHA256).Hash}else{'ABSENT'});dimensionFiles=$(if(Test-Path -LiteralPath $dims){@(Get-ChildItem -LiteralPath $dims -File -Recurse | ForEach-Object {[IO.Path]::GetRelativePath($dims,$_.FullName)})}else{@()})}
}
function Lock-State([string]$root) {
    $locks=@(Get-ChildItem -LiteralPath $root -Filter session.lock -File -Recurse -Force -ErrorAction SilentlyContinue)
    return @($locks | ForEach-Object {
        $available=$false
        try { $stream=[IO.File]::Open($_.FullName,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$stream.Dispose();$available=$true } catch [IO.IOException] { }
        [ordered]@{path=$_.FullName;exclusiveAvailable=$available;sha256=$(if($available){(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}else{'LOCKED'})}
    })
}
function Java-Processes { return @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Select-Object ProcessId,ParentProcessId,CommandLine,CreationDate) }
function Assert-Idle([string]$root) {
    Check-Path $root | Out-Null
    $live=@(Java-Processes | Where-Object { $_.CommandLine -and ($_.CommandLine.Contains($root) -or $_.CommandLine.Contains($root.Replace('\','/')) -or ($_.CommandLine.Contains($repo.Replace(' ','@@0020')) -and $_.CommandLine.Contains('-Dfabric.dli.env=server') -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main'))) })
    if($live.Count) { throw "root 有 live owner：$root" }
    if(@(Lock-State $root | Where-Object { -not $_.exclusiveAvailable }).Count) { throw "root 有 live lock：$root" }
}
function Move-Fresh([string]$root,[string]$tag) {
    $root=Check-Path $root; Assert-Idle $root
    Save-Json (Join-Path $gate "$tag-preflight.json") ([ordered]@{canonicalRoot=$root;reparse=$false;liveOwners=@();locks=@(Lock-State $root);javaProcessesChecked=@(Java-Processes);time=[DateTimeOffset]::Now.ToString('o')})
    $backup="$root.task9-backup-$id-$tag"
    $before=@(Files-Hash $root)
    Save-Json (Join-Path $gate "$tag-original-files.json") $before
    $existed=Test-Path -LiteralPath $root
    if($existed) { Check-Path $backup | Out-Null; if(Test-Path -LiteralPath $backup){throw 'backup 碰撞'};Move-Item -LiteralPath $root -Destination $backup }
    $swap=[pscustomobject]@{root=$root;backup=$backup;existed=$existed;before=$before;tag=$tag}
    try { New-Item -ItemType Directory -Path $root | Out-Null }
    catch {
        $createFailure=$_
        try { Restore-Root $swap }
        catch {
            # 保留兩個原因與可恢復位置；復原失敗不可宣稱 exact restore。
            $combined=[AggregateException]::new("fresh 建立與復原皆失敗；original=$root；backup=$backup；freshEvidence=$(Join-Path $gate ($tag+'-fresh'))", [Exception[]]@($createFailure.Exception,$_.Exception))
            throw $combined
        }
        throw
    }
    return $swap
}
function Restore-Root($swap) {
    Assert-Idle $swap.root
    $fresh=Check-Path (Join-Path $gate ($swap.tag+'-fresh'))
    if(Test-Path -LiteralPath $fresh){throw 'fresh evidence 碰撞'}
    if(Test-Path -LiteralPath $swap.root) { Move-Item -LiteralPath $swap.root -Destination $fresh }
    if($swap.existed) {
        Check-Path $swap.backup | Out-Null
        Move-Item -LiteralPath $swap.backup -Destination $swap.root
        $after=@(Files-Hash $swap.root)
        if(($swap.before|ConvertTo-Json -Depth 4 -Compress) -cne ($after|ConvertTo-Json -Depth 4 -Compress)){throw 'original restore hash 不符'}
    }
    Save-Json (Join-Path $gate ($swap.tag+'-restore.json')) ([ordered]@{exactRestored=$true;originalExisted=$swap.existed;original=$swap.root;freshEvidence=$fresh;deleted=$false})
}
function Server-Files([string]$root) {
    'eula=true' | Set-Content -LiteralPath (Join-Path $root 'eula.txt') -Encoding ascii
    @('online-mode=false','server-port=0','level-name=world','spawn-protection=0','view-distance=2','simulation-distance=2','sync-chunk-writes=true','level-seed=20260921','max-tick-time=120000') | Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ascii
}
function Copy-Build([string]$dest) {
    New-Item -ItemType Directory -Path $dest | Out-Null
    foreach($name in @('test-results','reports','gametest-results.xml','gametest-legacy-results.xml','gametest-m5-results.xml','gametest-m6-results.xml','libs')) {
        $source=Join-Path $repo "build/$name"
        if(Test-Path -LiteralPath $source){Copy-Item -LiteralPath $source -Destination $dest -Recurse}
    }
}
function Invoke-Gradle([string[]]$tasks,[string]$evidence,[string]$runtimeRoot,[string]$stopMode='',[int]$timeout=600,[ValidateSet('minecraft','junit','compile-only')][string]$runtimeLogMode='minecraft',[ValidateRange(1,3600)][int]$idleSeconds=90) {
    # 只有明確不啟動 Minecraft 的 JUnit／編譯 invocation 可以豁免當次日誌；不可拿它略過任何 run task。
    if($runtimeLogMode -cne 'minecraft' -and @($tasks | Where-Object {($_ -split ':')[-1] -cmatch '^run'}).Count){throw '日誌不適用模式不可包含 run task'}
    $m6Mode=$Mode -in @('M6Compile','M6JUnit','M6GameTest')
    $idleTimeout=if($m6Mode){$idleSeconds}else{0}
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null
    $taskArgs=@('--no-daemon','--console=plain','--max-workers=2','-I',(Join-Path $PSScriptRoot 'runtime.init.gradle'))+$tasks
    $quoted=@($taskArgs | ForEach-Object {if($_.Contains('"')){throw '拒絕未處理引號'};'"'+$_+'"'}) -join ' '
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$env:ComSpec;$info.Arguments='/d /s /c ""'+$gradle+'" '+$quoted+'"';$info.WorkingDirectory=$repo
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    $before=Root-State $runtimeRoot; Save-Json (Join-Path $evidence 'before.json') $before
    Save-Json (Join-Path $evidence 'locks-before.json') @(Lock-State $runtimeRoot)
    $known=@{};$baseline=@(Java-Processes);$baselineIds=@($baseline | ForEach-Object {$_.ProcessId})
    $outFile=[IO.StreamWriter]::new((Join-Path $evidence 'stdout.log'),$false,[Text.UTF8Encoding]::new($false))
    $errFile=[IO.StreamWriter]::new((Join-Path $evidence 'stderr.log'),$false,[Text.UTF8Encoding]::new($false))
    $logBefore=@(Get-GateLogSnapshot (Join-Path $runtimeRoot 'logs'))
    $xmlBefore=@(Get-GateXmlSnapshot (Join-Path $repo 'build'))
    Save-Json (Join-Path $evidence 'logs-before.json') $logBefore
    Save-Json (Join-Path $evidence 'xml-before.json') $xmlBefore
    $start=[DateTimeOffset]::Now;$lastActivity=$start;$nextPoll=$start;$done=$null;$stopAt=$null;$stopSent=$false;$debugCount=0;$failure=$null;$exit=$null;$launcherPid=$null;$deadline=$null;$deadlineAt=$null
    try {
        [void]$process.Start();$launcherPid=$process.Id
        $stdout=$process.StandardOutput.ReadLineAsync();$stderr=$process.StandardError.ReadLineAsync();$outEnd=$false;$errEnd=$false
        while(-not $process.HasExited -or -not $outEnd -or -not $errEnd) {
            while(-not $outEnd -and $stdout.IsCompleted) {
                $line=$stdout.GetAwaiter().GetResult()
                if($null -eq $line){$outEnd=$true;break}
                $outFile.WriteLine($line);$outFile.Flush();$lastActivity=[DateTimeOffset]::Now
                if($line -match 'Done \([0-9.,]+s\)!') {$done=[DateTimeOffset]::Now;Write-Output "Done：$evidence"}
                if($line -match 'Saved the game') {$debugCount++}
                $stdout=$process.StandardOutput.ReadLineAsync()
            }
            while(-not $errEnd -and $stderr.IsCompleted) {
                $line=$stderr.GetAwaiter().GetResult()
                if($null -eq $line){$errEnd=$true;break}
                $errFile.WriteLine($line);$errFile.Flush();$lastActivity=[DateTimeOffset]::Now;$stderr=$process.StandardError.ReadLineAsync()
            }
            if(-not $process.HasExited -and [DateTimeOffset]::Now -ge $nextPoll) {
                foreach($java in (Java-Processes | Where-Object {$_.ProcessId -notin $baselineIds})) {
                    $key=[string]$java.ProcessId
                    if(-not $known.ContainsKey($key)) {
                        $native=Get-Process -Id $java.ProcessId -ErrorAction SilentlyContinue
                        if($native){$known[$key]=[ordered]@{pid=$java.ProcessId;parentPid=$java.ParentProcessId;commandLine=$java.CommandLine;startTime=([DateTimeOffset]$native.StartTime).ToString('o');startTimeUtc=([DateTimeOffset]$native.StartTime).ToUniversalTime().ToString('o')};Save-Json (Join-Path $evidence 'processes.json') @($known.Values)}
                        if($java.CommandLine){foreach($match in [regex]::Matches($java.CommandLine,'(?:@|\-Dfabric\.dli\.config=)(?:"([^"]+)"|([^\s]+))')) {
                            $path=if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}
                            $path=[regex]::Replace($path,'@@([0-9a-fA-F]{4})',{param($encoded)[char][Convert]::ToInt32($encoded.Groups[1].Value,16)})
                            if(Test-Path -LiteralPath $path){Copy-Item -LiteralPath $path -Destination (Join-Path $evidence ("process-$key-"+[IO.Path]::GetFileName($path))) -Force}
                        }}
                    }
                }
                Save-Json (Join-Path $evidence 'locks-running.json') @(Lock-State $runtimeRoot)
                $nextPoll=[DateTimeOffset]::Now.AddSeconds(1)
            }
            if($done -and $stopMode -and -not $stopSent -and -not $process.HasExited) {
                $elapsed=([DateTimeOffset]::Now-$done).TotalSeconds
                if($stopMode -eq 'main') {
                    if($null -eq $stopAt){$process.StandardInput.WriteLine('save-all flush');$process.StandardInput.Flush();$stopAt=[DateTimeOffset]::Now}
                    elseif($debugCount -ge 1 -and $elapsed -ge 4 -and $debugCount -lt 2 -and $stopAt -ne [DateTimeOffset]::MinValue){$process.StandardInput.WriteLine('save-all flush');$process.StandardInput.Flush();$stopAt=[DateTimeOffset]::MinValue}
                    if($debugCount -ge 2 -and $elapsed -ge 6){$process.StandardInput.WriteLine('stop');$process.StandardInput.Flush();$stopSent=$true}
                } elseif($elapsed -ge 3){$process.StandardInput.WriteLine('stop');$process.StandardInput.Flush();$stopSent=$true}
            }
            if(-not $process.HasExited -and (([DateTimeOffset]::Now-$start).TotalSeconds -gt $timeout -or ($idleTimeout -gt 0 -and ([DateTimeOffset]::Now-$lastActivity).TotalSeconds -gt $idleTimeout))) {
                if($m6Mode -and $null -eq $deadline){
                    $deadlineAt=[DateTimeOffset]::Now
                    $deadline=[ordered]@{reason=$(if(($deadlineAt-$start).TotalSeconds -gt $timeout){'total'}else{'idle'});observedAt=$deadlineAt.ToUniversalTime().ToString('o');elapsedSeconds=($deadlineAt-$start).TotalSeconds;idleSeconds=($deadlineAt-$lastActivity).TotalSeconds;observedAfterExit=$false}
                }
                if(-not $stopSent){$process.StandardInput.WriteLine('stop');$process.StandardInput.Flush();$stopSent=$true}
                if($m6Mode -and $deadlineAt -and ([DateTimeOffset]::Now-$deadlineAt).TotalSeconds -gt 60){throw "M6_PHASE_DEADLINE_EXCEEDED reason=$($deadline.reason)；grace後尚未退出：$evidence"}
                if(-not $m6Mode -and ([DateTimeOffset]::Now-$start).TotalSeconds -gt ($timeout+60)) {throw "逾時且 stdin stop 未完成：$evidence"}
            }
            Start-Sleep -Milliseconds 100
        }
        $process.WaitForExit()
        $exit=$process.ExitCode
        # 即使輪詢剛好跨過程序退出，總deadline仍不可因exit0被抹掉。
        if($m6Mode -and $null -eq $deadline -and ([DateTimeOffset]::Now-$start).TotalSeconds -gt $timeout){
            $deadlineAt=[DateTimeOffset]::Now
            $deadline=[ordered]@{reason='total';observedAt=$deadlineAt.ToUniversalTime().ToString('o');elapsedSeconds=($deadlineAt-$start).TotalSeconds;idleSeconds=($deadlineAt-$lastActivity).TotalSeconds;observedAfterExit=$true}
        }
        if($m6Mode -and $null -ne $deadline){throw "M6_PHASE_DEADLINE_EXCEEDED reason=$($deadline.reason)；已安全退出但逾時仍FAIL：$evidence"}
    } catch {$failure=$_;throw}
    finally {
        $outFile.Dispose();$errFile.Dispose()
        $identity=[ordered]@{exitCode=$exit;start=$start.ToUniversalTime().ToString('o');end=[DateTimeOffset]::UtcNow.ToString('o');command=$info.Arguments;launcherPid=$launcherPid;processes=@($known.Values);runtimeRoot=$runtimeRoot;doneSeen=($null-ne $done);stdinStop=$stopSent;nativeSaves=$debugCount;status=$(if($null -eq $failure -and $exit -eq 0){'EXIT_ZERO'}else{'FAIL'});error=$(if($failure){$failure.Exception.Message}else{$null})}
        $identity.runtimeLogMode=$runtimeLogMode
        if($m6Mode){$identity.configuredTimeoutSeconds=$timeout;$identity.configuredIdleSeconds=$idleTimeout;$identity.phaseDeadline=$deadline}
        $captureErrors=@()
        foreach($capture in @(
            {Save-Json (Join-Path $evidence 'execution.json') $identity},
            {Save-Json (Join-Path $evidence 'after.json') (Root-State $runtimeRoot)},
            {Save-Json (Join-Path $evidence 'locks-after.json') @(Lock-State $runtimeRoot)},
            {if(Test-Path -LiteralPath (Join-Path $runtimeRoot 'logs')){Copy-Item -LiteralPath (Join-Path $runtimeRoot 'logs') -Destination (Join-Path $evidence 'runtime-logs') -Recurse}},
            {Save-GateRuntimeLog -Evidence $evidence -Before $logBefore -Identity $identity;Read-GateRuntimeLog -Evidence $evidence | Out-Null},
            {if($failure -or $exit -ne 0){Save-GateFailureArtifacts -Evidence $evidence -BuildRoot (Join-Path $repo 'build') -Before $xmlBefore -Identity $identity}}
        )){try{& $capture}catch{$captureErrors+=$_.Exception.Message}}
        if($captureErrors.Count){
            try{Save-Json (Join-Path $evidence 'capture-errors.json') ([ordered]@{originalError=$identity.error;errors=$captureErrors})}catch{Write-Warning "取證診斷寫入失敗：$($_.Exception.Message)"}
            if(-not $failure -and $exit -eq 0){throw ("gate 取證失敗："+($captureErrors -join '; '))}
        }
    }
    # 只複製本次觀察到的 JVM（$known）產出的 classload／boundary；runDir 累積的舊檔不進 gate root（整合審查 R1）。
    foreach($file in @(Get-ChildItem -LiteralPath $runtimeRoot -Filter 'classload-*.log' -File -ErrorAction SilentlyContinue)){if($file.Name -match '^classload-(\d+)\.log$' -and $known.ContainsKey($Matches[1])){Copy-Item -LiteralPath $file.FullName -Destination $evidence}}
    $boundaries=@(Get-GateBoundaryReceipt -Root $runtimeRoot -Processes @($known.Values))
    Save-Json (Join-Path $evidence 'boundary-selection.json') ([ordered]@{receipts=@($boundaries | Select-Object path,matched,reason,startDeltaMilliseconds,toleranceMillisecondsExclusive)})
    foreach($boundary in $boundaries | Where-Object matched){Copy-Item -LiteralPath $boundary.path -Destination $evidence}
    foreach($directory in @(Get-ChildItem -LiteralPath $runtimeRoot -Filter 'm4-per-test-*' -Directory -ErrorAction SilentlyContinue)) {
        if($directory.Name -match '^m4-per-test-(\d+)-' -and $known.ContainsKey($Matches[1])) {Copy-Item -LiteralPath $directory.FullName -Destination $evidence -Recurse}
    }
    $launch=Join-Path $repo '.gradle/loom-cache/launch.cfg'
    if(Test-Path -LiteralPath $launch){Copy-Item -LiteralPath $launch -Destination (Join-Path $evidence 'loom-launch.cfg')}
    Write-Output "Gradle exit=$exit：$evidence"
    return $exit
}
function Distinct-Count($items) { $set=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal); foreach($item in $items){[void]$set.Add($item)}; return $set.Count }
# plan 決策 6 的 batch 前綴矩陣（與 testmod M4LegacyGameTestFilterMixin 相同）：m4_legacy_runtime 屬 legacy run，前綴 m5_universe 屬 M5 run，其餘屬 default run。
function Batch-Scope([string]$batch) { if($batch -ceq 'm4_legacy_runtime'){return 'legacy'}; if($batch.StartsWith('m5_universe',[StringComparison]::Ordinal)){return 'm5'}; if($batch.StartsWith('m6_universe',[StringComparison]::Ordinal)){return 'm6'}; return 'default' }
# testmod 原始碼宣告的全部 GameTest：XML testcase 名稱（class 小寫＋'.'＋方法名稱）、batchId（沒寫時是原版預設 defaultBatch）與所屬 run。
# 先去掉註解行（去掉前導空白後以 //、/*、* 開頭的行，含 Javadoc 內的 {@code @GameTest}），再掃描；每個檔的 @GameTest 數必須等於可解析的
# 宣告數，不一致代表宣告格式漂移（例如 @GameTest 與方法之間多了 annotation），直接失敗。
# 「哪些 test 屬於哪個 run」另有兩份實作在 .github/workflows/build.yml 的兩個檢查 step；三處的註解排除、嚴格／寬鬆掃描與前綴規則必須同步（README）。
function Declared-GameTests([string]$SourceRoot=(Join-Path $repo 'src/testmod/java')) {
    $cases=@()
    foreach($file in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Filter '*.java' | Sort-Object FullName) {
        $text=(@((Get-Content -LiteralPath $file.FullName -Raw) -split '\r?\n') | Where-Object {$_ -notmatch '^\s*(//|/\*|\*)'}) -join "`n"
        $strict=@([regex]::Matches($text,'(?s)@GameTest\((?<annotation>[^)]*)\)\s*public void (?<name>\w+)\(TestContext'))
        $loose=([regex]::Matches($text,'@GameTest\b')).Count
        if($strict.Count -ne $loose){throw "GameTest 宣告格式漂移（$($file.Name)）：@GameTest $loose 個，可解析 $($strict.Count) 個"}
        foreach($match in $strict) {
            $batchMatch=[regex]::Match($match.Groups['annotation'].Value,'batchId\s*=\s*"(?<batch>[^"]*)"')
            $batch=if($batchMatch.Success){$batchMatch.Groups['batch'].Value}else{'defaultBatch'}
            $templateMatch=[regex]::Match($match.Groups['annotation'].Value,'templateName\s*=\s*"(?<template>[^"]+)"')
            if(-not $templateMatch.Success){throw "M6_SCOPE_MISMATCH：GameTest 缺少可解析 templateName：$($file.Name)"}
            $cases+=[pscustomobject]@{identity=$templateMatch.Groups['template'].Value+'|'+$file.BaseName.ToLowerInvariant()+'.'+$match.Groups['name'].Value;case=$file.BaseName.ToLowerInvariant()+'.'+$match.Groups['name'].Value;name=$match.Groups['name'].Value;class=$file.BaseName;batch=$batch;scope=(Batch-Scope $batch)}
        }
    }
    if((Distinct-Count @($cases | ForEach-Object case)) -ne $cases.Count){throw 'GameTest testcase 名稱不唯一'}
    return $cases
}
# 本 run 的 XML testcase 集合必須恰好等於原始碼中屬於本 run 的宣告（以 JAVA_TOOL_OPTIONS 的 onlyBatches 取證時，再與那些 batch 取交集）。
# 結果寫入 <gate>/scope.json；回傳本 run 應執行的宣告。
function Assert-Scope([string]$xmlPath,[string]$scope,[string]$SourceRoot=(Join-Path $repo 'src/testmod/java'),[string[]]$OnlyBatches=@()) {
    $declared=@(Declared-GameTests $SourceRoot)
    $expected=@($declared | Where-Object {$_.scope -ceq $scope})
    $only=@($OnlyBatches | Where-Object {-not [string]::IsNullOrWhiteSpace($_)})
    if(-not $only.Count -and $env:JAVA_TOOL_OPTIONS -match '-Dquantumchamber\.gametest\.onlyBatches=(?<batches>\S+)'){$only=@($Matches['batches'].Split(','))}
    if($only.Count){$expected=@($expected | Where-Object {$_.batch -cin $only})}
    [xml]$report=Get-Content -LiteralPath $xmlPath -Raw
    $actual=@($report.SelectNodes('//testcase') | ForEach-Object {$_.classname+'|'+$_.name})
    $expectedNames=@($expected | ForEach-Object identity)
    $missing=@($expectedNames | Where-Object {$_ -cnotin $actual})
    $extra=@($actual | Where-Object {$_ -cnotin $expectedNames})
    $status=if($expected.Count -gt 0 -and $missing.Count -eq 0 -and $extra.Count -eq 0 -and $actual.Count -eq $expected.Count -and (Distinct-Count $actual) -eq $actual.Count -and @($report.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count -eq 0){'PASS'}else{'FAIL'}
    Save-Json (Join-Path $gate 'scope.json') ([ordered]@{scope=$scope;status=$status;onlyBatches=$only;xml=$xmlPath
        declared=[ordered]@{default=@($declared | Where-Object {$_.scope -ceq 'default'}).Count;legacy=@($declared | Where-Object {$_.scope -ceq 'legacy'}).Count;m5=@($declared | Where-Object {$_.scope -ceq 'm5'}).Count;m6=@($declared | Where-Object {$_.scope -ceq 'm6'}).Count}
        head=(git -C $repo rev-parse HEAD).Trim();expected=$expected.Count;actual=$actual.Count;expectedCases=$expectedNames;actualCases=$actual;missing=$missing;extra=$extra})
    if($status -ne 'PASS'){throw "M6_SCOPE_MISMATCH：GameTest run 範圍不符（$scope）：expected=$($expected.Count) actual=$($actual.Count) 缺 [$($missing -join ', ')] 多 [$($extra -join ', ')]"}
    return $expected
}
# M5 run 的 GameTest class（F6-3c-2 由 M5UniverseGameTests 依主題拆成四個；testmod fabric.mod.json 的 fabric-gametest entrypoint 同步列出，README「新增 M5 GameTest」）。
$M5RunClasses=@('M5UniverseGameTests','M5UniverseProjectionGameTests','M5UniverseDoorGameTests','M5UniversePassageGameTests')
# M5 run 的 oracle（取代 default run 的 M4 boundary oracle）：JVM 帶 m5Only、沒有 boundary receipt；m5_universe 的 test 都宣告在 $M5RunClasses 的 class、
# 各自一個 batch；每個 test 都留下恰一行 PASS 的 M5_UNIVERSE_CATALOG_DELTA。依 log 順序串接：fresh 世界從 0 筆開始，前一個 test 的 after（筆數與
# digest）等於下一個 test 的 before；suite 結束時（SERVER_STOPPED）恰一行 M5_UNIVERSE_SUITE_END，筆數與 digest 等於最後一個 test 的 after、
# converged=true、沒有還沒通過的 test（catalog 只在各 test 自己斷言的範圍內改變，最後一個 test 之後也沒有改變）。結果寫入 <gate>/m5-oracle.json。
# 正式完整當次日誌同時要求 HOLD=0、節流結束恰一行且 waited>0/tps<=100；SUITE_END 另逐項要求 initialized=true、四個 unconfirmed=false。
function Assert-M5Run($server,$expected) {
    if(-not $server.commandLine.Contains('-Dquantumchamber.gametest.m5Only=true')){throw 'M5 run 的 server JVM 沒有 -Dquantumchamber.gametest.m5Only=true'}
    $boundary=@(Get-ChildItem -LiteralPath (Join-Path $gate 'execution') -Filter 'm4-boundary-*.json')
    if($boundary.Count){throw "M5 run 不得留下 M4 boundary receipt：$($boundary.Name -join ', ')"}
    $outside=@($expected | Where-Object {$_.class -cnotin $M5RunClasses})
    if($outside.Count){throw "m5_universe batch 只能宣告在 M5 run 的 GameTest class（$($M5RunClasses -join '、')）：$(@($outside | ForEach-Object case) -join ', ')"}
    if((Distinct-Count @($expected | ForEach-Object batch)) -ne $expected.Count){throw 'M5 test 必須各自一個 batch（逐 test 的 catalog 增量才只屬於該 test）'}
    $stdout=Read-GateRuntimeLog -Evidence (Join-Path $gate 'execution')
    if($stdout.Contains('M5_UNIVERSE_HOLD')){throw 'M5 全部 PASS 的 run 不得有 M5_UNIVERSE_HOLD'}
    $paceLines=@($stdout -split '\r?\n' | Where-Object {$_.Contains('GameTest tick 節流 結束：')})
    if($paceLines.Count -ne 1){throw "M5 節流結束列必須恰一行（$($paceLines.Count)）"}
    $pace=[regex]::Match($paceLines[0],'GameTest tick 節流 結束：ticks=(?<ticks>\d+) waited=(?<waited>\d+) waitedMs=(?<waitedMs>\d+) elapsedMs=(?<elapsedMs>\d+) tps=(?<tps>\d+(?:\.\d+)?) minTickMs=(?<minTickMs>\d+)\s*$')
    if(-not $pace.Success){throw 'M5 節流結束列格式不符'}
    $pacing=[ordered]@{ticks=[long]$pace.Groups['ticks'].Value;waited=[long]$pace.Groups['waited'].Value;waitedMs=[long]$pace.Groups['waitedMs'].Value;elapsedMs=[long]$pace.Groups['elapsedMs'].Value;tps=[double]::Parse($pace.Groups['tps'].Value,[Globalization.CultureInfo]::InvariantCulture);minTickMs=[long]$pace.Groups['minTickMs'].Value}
    if($pacing.waited -le 0 -or $pacing.tps -gt 100.0){throw "M5 節流必須 waited>0 且 tps<=100.0：waited=$($pacing.waited) tps=$($pacing.tps)"}
    $deltas=@([regex]::Matches($stdout,'M5_UNIVERSE_CATALOG_DELTA name=(?<name>\w+) before=(?<before>\d+) after=(?<after>\d+) expected=(?<expected>-?\d+) actual=(?<actual>-?\d+) converged=(?<converged>\w+) preserved=(?<preserved>\w+) beforeDigest=(?<beforeDigest>[0-9a-f]{64}) afterDigest=(?<afterDigest>[0-9a-f]{64}) status=(?<status>\w+)') | ForEach-Object {
        [pscustomobject]@{name=$_.Groups['name'].Value;before=[int]$_.Groups['before'].Value;after=[int]$_.Groups['after'].Value;expected=[int]$_.Groups['expected'].Value;converged=$_.Groups['converged'].Value;preserved=$_.Groups['preserved'].Value;beforeDigest=$_.Groups['beforeDigest'].Value;afterDigest=$_.Groups['afterDigest'].Value;status=$_.Groups['status'].Value}
    })
    foreach($name in @($expected | ForEach-Object name)) {
        $hit=@($deltas | Where-Object {$_.name -ceq $name})
        if($hit.Count -ne 1 -or $hit[0].status -cne 'PASS' -or ($hit[0].after - $hit[0].before) -ne $hit[0].expected){throw "M5 test 的 catalog 增量缺失、重複或不符：$name"}
    }
    if($deltas.Count -ne $expected.Count){throw "M5_UNIVERSE_CATALOG_DELTA 有 $($deltas.Count) 行，M5 test 有 $($expected.Count) 個"}
    $previous=0; $previousDigest=$null
    foreach($delta in $deltas){
        if($delta.before -ne $previous -or ($null -ne $previousDigest -and $delta.beforeDigest -cne $previousDigest)){throw "catalog 在 test 自己斷言的範圍之外改變：$($delta.name) before=$($delta.before)，預期 $previous（digest 也必須接續）"}
        $previous=$delta.after; $previousDigest=$delta.afterDigest
    }
    if([regex]::Matches($stdout,'M5_UNIVERSE_SUITE_END(?:\s|$)').Count -ne 1 -or $stdout.Contains('M5_UNIVERSE_SUITE_END_FAILED')){throw 'M5_UNIVERSE_SUITE_END 四權威結束列缺失、重複或讀取失敗'}
    $ends=@([regex]::Matches($stdout,'(?m)M5_UNIVERSE_SUITE_END records=(?<records>\d+) digest=(?<digest>[0-9a-f]{64}) converged=(?<converged>\w+) unfinished=\[(?<unfinished>[^\]]*)\] initialized=(?<initialized>true|false) allocationUnconfirmed=(?<allocation>true|false) projectionUnconfirmed=(?<projection>true|false) discoveryUnconfirmed=(?<discovery>true|false) catalogUnconfirmed=(?<catalog>true|false)\s*$') | ForEach-Object {
        [pscustomobject]@{records=[int]$_.Groups['records'].Value;digest=$_.Groups['digest'].Value;converged=$_.Groups['converged'].Value;unfinished=$_.Groups['unfinished'].Value;initialized=$_.Groups['initialized'].Value;allocationUnconfirmed=$_.Groups['allocation'].Value;projectionUnconfirmed=$_.Groups['projection'].Value;discoveryUnconfirmed=$_.Groups['discovery'].Value;catalogUnconfirmed=$_.Groups['catalog'].Value}
    })
    if($ends.Count -ne 1){throw 'M5_UNIVERSE_SUITE_END 四權威欄位缺失或格式不符'}
    $suiteEnd=$ends[0]
    if($suiteEnd.initialized -cne 'true' -or $suiteEnd.allocationUnconfirmed -cne 'false' -or $suiteEnd.projectionUnconfirmed -cne 'false' -or $suiteEnd.discoveryUnconfirmed -cne 'false' -or $suiteEnd.catalogUnconfirmed -cne 'false'){throw 'M5_UNIVERSE_SUITE_END 四權威必須已初始化且全部確認'}
    if($suiteEnd.records -ne $previous -or $suiteEnd.digest -cne $previousDigest -or $suiteEnd.converged -cne 'true' -or $suiteEnd.unfinished.Length -ne 0){
        throw "suite 結束時的 catalog 與最後一個 test 的結果不符：records=$($suiteEnd.records)（最後一個 test 之後是 $previous）digest 相同=$($suiteEnd.digest -ceq $previousDigest) converged=$($suiteEnd.converged) 還沒通過=[$($suiteEnd.unfinished)]"
    }
    Save-Json (Join-Path $gate 'm5-oracle.json') ([ordered]@{status='PASS';serverPid=$server.pid;boundaryReceipts=0;tests=$expected.Count;holds=0;pacing=$pacing;deltas=$deltas;finalRecordCount=$previous;suiteEnd=$suiteEnd})
}
function Fresh-Game([bool]$full,[string]$scope) {
    $run=Join-Path $repo $(if($scope -ceq 'legacy'){'run/gametest-legacy'}elseif($scope -ceq 'm5'){'run/gametest-m5'}elseif($scope -ceq 'm6'){'run/gametest-m6'}else{'run/gametest'})
    $task=if($scope -ceq 'legacy'){'runGameTestLegacy'}elseif($scope -ceq 'm5'){'runGameTestM5'}elseif($scope -ceq 'm6'){'runGameTestM6'}else{'runGameTest'}
    $report=if($scope -ceq 'legacy'){'gametest-legacy-results.xml'}elseif($scope -ceq 'm5'){'gametest-m5-results.xml'}elseif($scope -ceq 'm6'){'gametest-m6-results.xml'}else{'gametest-results.xml'}
    $swap=Move-Fresh (Join-Path $run 'world') 'world'
    try {
        Copy-Build (Join-Path $gate 'prior-build')
        $tasks=if($full){@('clean','test',$task,'build','--rerun-tasks')}else{@($task,'--rerun-tasks')}
        $runStart=[DateTime]::UtcNow
        $result=@(Invoke-Gradle $tasks (Join-Path $gate 'execution') $run '' $(if($scope -ceq 'm6'){180}else{1200}))[-1]
        Copy-Build (Join-Path $gate 'result-build')
        if($result -ne 0){throw "GameTest gate exit=$result"}
        # 只認本次 run 寫出的 XML，不讀上一次留下的舊檔。
        $written=Join-Path $repo "build/$report"
        if(-not(Test-Path -LiteralPath $written -PathType Leaf) -or (Get-Item -LiteralPath $written).LastWriteTimeUtc -lt $runStart){throw "GameTest XML 不是本次 run 寫出的：$report"}
        $server=@(Get-Content -LiteralPath (Join-Path $gate 'execution/processes.json') -Raw | ConvertFrom-Json -DateKind String | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
        if($scope -ceq 'm5') {
            if($server.Count -ne 1){throw "M5 run 必須恰有一個 server JVM（$($server.Count)）"}
            $expected=@(Assert-Scope (Join-Path $gate "result-build/$report") 'm5')
            Assert-M5Run $server[0] $expected
        } elseif($scope -ceq 'm6') {
            if($server.Count -ne 1){throw 'M6 run 必須恰有一個 server JVM'}
            if(-not $server[0].commandLine.Contains('-Dquantumchamber.gametest.m6Only=true') -or $server[0].commandLine -match '-Dquantumchamber[.]gametest[.](m5Only|legacyOnly)=true'){throw 'M6_SCOPE_MISMATCH：server JVM scope flags 不符'}
            & (Join-Path $PSScriptRoot 'm6-ci-gametest.ps1') -Platform Windows -VerifyOnly -GameTestXml (Join-Path $gate "result-build/$report") -RuntimeLog (Join-Path $gate 'execution/runtime-logs/latest.log') -RuntimeRoot $run -OutputRoot $gate -Batch $Batch
            $m6Scope=Get-Content -LiteralPath (Join-Path $gate 'scope.json') -Raw | ConvertFrom-Json
            if($m6Scope.status -ne 'PASS'){throw 'M6_SCOPE_MISMATCH：M6 scope 非 PASS'}
        } else {
            if($server.Count -ne 1){throw "GameTest run 必須恰有一個 server JVM（$($server.Count)）"}
            $boundary=@(Get-GateBoundaryReceipt -Root (Join-Path $gate 'execution') -Processes $server | Where-Object matched | ForEach-Object receipt)
            if($boundary.Count -ne 1 -or $boundary[0].status -ne 'PASS'){throw 'GameTest Universe boundary oracle 缺失或 FAIL'}
            Assert-Scope (Join-Path $gate "result-build/$report") $scope | Out-Null
        }
    } finally {Restore-Root $swap}
}
function Probe-Chain([string]$kind,[string[]]$phases) {
    Check-Path $probeOwner | Out-Null; Assert-Ignored $probeOwner
    if(-not(Test-Path -LiteralPath $probeOwner)){New-Item -ItemType Directory -Path $probeOwner | Out-Null}
    $nonce='m4-task9-'+[Guid]::NewGuid().ToString('N')
    $runtime=Join-Path $repo ("run/m3-$kind-$nonce")
    Check-Path $runtime | Out-Null
    if(Test-Path -LiteralPath $runtime){throw 'fresh root 碰撞'}
    New-Item -ItemType Directory -Path $runtime | Out-Null;Server-Files $runtime
    foreach($phase in $phases) {
        $prefix=if($kind -eq 'universe'){'task-9-final-'}else{'transfer-final-'}
        $evidence=Join-Path $probeOwner "$prefix$nonce/$phase"
        New-Item -ItemType Directory -Path $evidence | Out-Null
        $startup=[Guid]::NewGuid().ToString('N')
        $task=if($kind -eq 'universe'){'runM3Universe'}else{'runM3Transfer'}
        $nonceProperty=if($kind -eq 'universe'){'m3UniverseNonce'}else{'m3TransferNonce'}
        $tasks=@('-I',(Join-Path $PSScriptRoot 'probe.init.gradle'),$task,"-P$nonceProperty=$nonce","-Ptask9.phase=$phase","-Ptask9.nonce=$nonce","-Ptask9.startupNonce=$startup","-Ptask9.root=$runtime","-Ptask9.evidenceOwner=$probeOwner")
        $result=@(Invoke-Gradle $tasks $evidence $runtime '' 300)[-1]
        $final=Join-Path $evidence 'final.json'
        Save-Json (Join-Path $evidence 'expected.json') ([ordered]@{phase=$phase;nonce=$nonce;startupNonce=$startup;evidenceOwner=$probeOwner;root=$runtime;expectedStatus='PASS'})
        if($result -ne 0 -or -not(Test-Path -LiteralPath $final)){throw "probe gate 失敗且停止 chain：$evidence"}
        # 沿用 Task 9 recovery launcher 裁決：PowerShell 7 JSON 日期以 -DateKind String 保留 ISO 字串，再顯式轉 DateTimeOffset 比較。
        $receipt=Get-Content -LiteralPath $final -Raw | ConvertFrom-Json -DateKind String
        if($receipt.status -ne 'PASS' -or $receipt.startupNonce -ne $startup -or -not $receipt.stoppedSeen){throw "probe receipt FAIL：$evidence"}
        $observed=@(Get-Content -LiteralPath (Join-Path $evidence 'processes.json') -Raw | ConvertFrom-Json -DateKind String | Where-Object {$_.pid -eq $receipt.pid})
        if($observed.Count -ne 1 -or $receipt.evidenceOwner -ne $probeOwner -or [Math]::Abs(([DateTimeOffset]::Parse($receipt.startTimeUtc)-[DateTimeOffset]::Parse($observed[0].startTimeUtc)).TotalSeconds) -gt 1){throw 'probe PID／DateTimeOffset StartTime／owner 不符'}
        if(-not $observed[0].commandLine.Contains($startup)){throw 'startupNonce 不在實際 JVM commandline'}
        Assert-Idle $runtime
        Save-Json (Join-Path $evidence 'root-hashes-after.json') @(Files-Hash (Join-Path $runtime 'world/data'))
        Copy-Item -LiteralPath $final -Destination (Join-Path $gate "$kind-$phase.json")
    }
}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo '.superpowers/verification'}
$EvidenceRoot=Check-Path $EvidenceRoot; Assert-Ignored $EvidenceRoot
if(-not(Test-Path -LiteralPath $EvidenceRoot)){New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null}
$gate=Join-Path $EvidenceRoot "gate-$Label-$id"
New-Item -ItemType Directory -Path $gate | Out-Null
try {
    if($Batch){$env:JAVA_TOOL_OPTIONS=("$priorJavaOptions -Dquantumchamber.gametest.onlyBatches=$Batch").Trim()}
    switch($Mode) {
        'Inventory' {
            $cases=@()
            foreach($file in Get-ChildItem -LiteralPath 'src/testmod/java/dev/quantumchamber/gametest' -Filter '*GameTests.java') {
                $text=Get-Content -LiteralPath $file.FullName -Raw
                foreach($match in [regex]::Matches($text,'(?s)@GameTest\((?<annotation>.*?)\)\s+public void (?<name>\w+)\(TestContext context\)')) {
                    $cases += [ordered]@{class=$file.BaseName;name=$match.Groups['name'].Value;annotation=$match.Groups['annotation'].Value;source=[IO.Path]::GetRelativePath($repo,$file.FullName);line=($text.Substring(0,$match.Index).Split("`n").Count);legacy=($match.Groups['name'].Value -match 'legacy')}
                }
            }
            Save-Json (Join-Path $gate 'coverage-inventory.json') $cases
            Write-Output "具名 inventory：$($cases.Count)；M4=$(@($cases|Where-Object {$_.class-eq 'M4CandidateDoorGameTests'}).Count)"
        }
        'Main' {
            $swap=Move-Fresh (Join-Path $repo 'run/server') 'server'
            try {Server-Files $swap.root;$exit=@(Invoke-Gradle @('runServer') (Join-Path $gate 'execution') $swap.root 'main' 240)[-1];if($exit-ne 0){throw "main-only exit=$exit"}}
            finally {Restore-Root $swap}
        }
        'GameTest' {Fresh-Game $false 'default'}
        'Full' {Fresh-Game $true 'default'}
        'Legacy' {Fresh-Game $false 'legacy'}
        'M5GameTest' {Fresh-Game $false 'm5'}
        'M6GameTest' {Fresh-Game $false 'm6'}
        'M6Compile' {
            $exit=@(Invoke-Gradle @('classes','clientClasses','testClasses','testmodClasses','--rerun-tasks') (Join-Path $gate 'execution') (Join-Path $repo 'run/testworlds') -timeout 180 -runtimeLogMode compile-only)[-1]
            if($exit -ne 0){throw "M6_COMPILE_FAILED exit=$exit"}
        }
        'M6JUnit' {
            if($TestClass -and $TestClass -notmatch '^dev\.quantumchamber\.[A-Za-z0-9_.$]+$'){throw 'M6_JUNIT_SCOPE_MISMATCH：TestClass 必須為完整 class 名稱'}
            $tasks=@('test');if($TestClass){$tasks+=@('--tests',$TestClass)};$tasks+='--rerun-tasks'
            $exit=@(Invoke-Gradle $tasks (Join-Path $gate 'execution') (Join-Path $repo 'run/testworlds') -timeout 180 -runtimeLogMode junit)[-1]
            Copy-Build (Join-Path $gate 'result-build')
            if($exit -ne 0){throw "M6_JUNIT_FAILED exit=$exit"}
            $cases=@();$suites=@()
            foreach($file in Get-ChildItem -LiteralPath (Join-Path $gate 'result-build/test-results/test') -Filter 'TEST-*.xml') {
                [xml]$xml=Get-Content -LiteralPath $file.FullName -Raw
                $suites+=([string]$xml.testsuite.name);$cases+=@($xml.SelectNodes('//testcase'))
            }
            if(-not $cases.Count -or @($cases | Where-Object {$_.SelectSingleNode('failure|error')}).Count -or ($TestClass -and ($suites.Count -ne 1 -or $suites[0] -cne $TestClass -or @($cases | Where-Object {$_.SelectSingleNode('skipped')}).Count))){throw 'M6_JUNIT_SCOPE_MISMATCH：JUnit 集合缺失／失敗／非指定 class'}
            Save-Json (Join-Path $gate 'junit-scope.json') ([ordered]@{status='PASS';head=(git -C $repo rev-parse HEAD).Trim();testClass=$TestClass;classes=$suites;cases=@($cases | ForEach-Object {$_.classname+'|'+$_.name})})
        }
        'Windows' {
            Copy-Build (Join-Path $gate 'prior-build')
            $exit=@(Invoke-Gradle @('test','--tests','dev.quantumchamber.persistence.WindowsPlayerCheckpointVerifierTest','--tests','dev.quantumchamber.persistence.PlayerCheckpointStoreTest','--tests','dev.quantumchamber.persistence.PlayerRecoveryCheckpointTest','--rerun-tasks') (Join-Path $gate 'execution') (Join-Path $repo 'run/testworlds') -runtimeLogMode junit)[-1]
            Copy-Build (Join-Path $gate 'result-build');if($exit-ne 0){throw "Windows gate exit=$exit"}
        }
        'M3' {if($Only-ne 'transfer'){Probe-Chain 'universe' @('create-save','reload-read','unload-replace','final-verify')};if($Only-ne 'universe'){Probe-Chain 'transfer' @('setup-catalog','success-roundtrip','target-not-full','post-move-authority-loss','stale-service-receipt')}}
    }
    Save-Json (Join-Path $gate 'gate.json') ([ordered]@{mode=$Mode;label=$Label;status='PASS';evidence=$gate})
} catch {
    Save-Json (Join-Path $gate 'gate.json') ([ordered]@{mode=$Mode;label=$Label;status='FAIL';error=$_.ToString();evidence=$gate})
    throw
} finally {$env:JAVA_TOOL_OPTIONS=$priorJavaOptions;Write-Output "GATE_ROOT=$gate"}
