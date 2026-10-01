# gates.ps1：M1–M4 runtime gate harness（Windows、PowerShell 7）。fresh world 以可恢復 move＋逐檔 hash exact restore 處理；
# 失敗 root 一律保留、不在同 root 重跑。gate root＝<EvidenceRoot>/gate-<Label>-<GUID>，結束時印 GATE_ROOT=<path>。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-gates.ps1（SHA-256 34eaecabf9d4685771d53482130c04d496bbdf3e396f6c2d6fc06055249851e8）。
# 相對來源的改動：repo root、gate root、Gradle、init script 改為參數或本目錄；移除 RedOwner（Task 9 owner 修正前的一次性 RED）；
# classload／m4-boundary 只複製本次觀察到的 JVM PID（整合審查 R1）；Inventory 輸出改寫到 gate root。
# 參數：
#   -Mode          Inventory｜Main｜GameTest｜Full｜Legacy｜Windows｜M3
#   -Label         gate root 標籤（英數與連字號）
#   -Only          M3 只跑 universe 或 transfer（空字串＝兩者）
#   -EvidenceRoot  gate root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
# M3 probe 的 evidence owner 由 testmod ProbeEvidenceOwner 固定為 <repo>/.superpowers/sdd/2026-09-21-m4-candidate-doors，不受 -EvidenceRoot 影響。
param(
    [Parameter(Mandatory)][ValidateSet('Inventory','Main','GameTest','Full','Legacy','Windows','M3')][string]$Mode,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label='run',
    [ValidateSet('','universe','transfer')][string]$Only='',
    [string]$EvidenceRoot='',
    [string]$Gradle=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach($marker in 'build.gradle','settings.gradle'){if(-not(Test-Path -LiteralPath (Join-Path $repo $marker) -PathType Leaf)){throw "repo root 缺少 ${marker}：$repo"}}
if(-not $Gradle){$dist='C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat';$Gradle=if(Test-Path -LiteralPath $dist -PathType Leaf){$dist}else{Join-Path $repo 'gradlew.bat'}}
if(-not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）"}
# testmod 固定的 M3 probe evidence owner（canonical 原生路徑）。
$probeOwner=[IO.Path]::GetFullPath((Join-Path $repo '.superpowers/sdd/2026-09-21-m4-candidate-doors'))
$id=[Guid]::NewGuid().ToString('N')
Set-Location -LiteralPath $repo
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
    New-Item -ItemType Directory -Path $root | Out-Null
    return [pscustomobject]@{root=$root;backup=$backup;existed=$existed;before=$before;tag=$tag}
}
function Restore-Root($swap) {
    Assert-Idle $swap.root
    $fresh=Check-Path (Join-Path $gate ($swap.tag+'-fresh'))
    if(Test-Path -LiteralPath $fresh){throw 'fresh evidence 碰撞'}
    Move-Item -LiteralPath $swap.root -Destination $fresh
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
    foreach($name in @('test-results','reports','gametest-results.xml','gametest-legacy-results.xml','libs')) {
        $source=Join-Path $repo "build/$name"
        if(Test-Path -LiteralPath $source){Copy-Item -LiteralPath $source -Destination $dest -Recurse}
    }
}
function Invoke-Gradle([string[]]$tasks,[string]$evidence,[string]$runtimeRoot,[string]$stopMode='',[int]$timeout=600) {
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
    [void]$process.Start();$start=[DateTimeOffset]::Now;$nextPoll=$start;$done=$null;$stopAt=$null;$stopSent=$false;$debugCount=0
    $stdout=$process.StandardOutput.ReadLineAsync();$stderr=$process.StandardError.ReadLineAsync();$outEnd=$false;$errEnd=$false
    try {
        while(-not $process.HasExited -or -not $outEnd -or -not $errEnd) {
            while(-not $outEnd -and $stdout.IsCompleted) {
                $line=$stdout.GetAwaiter().GetResult()
                if($null -eq $line){$outEnd=$true;break}
                $outFile.WriteLine($line);$outFile.Flush()
                if($line -match 'Done \([0-9.,]+s\)!') {$done=[DateTimeOffset]::Now;Write-Output "Done：$evidence"}
                if($line -match 'Saved the game') {$debugCount++}
                $stdout=$process.StandardOutput.ReadLineAsync()
            }
            while(-not $errEnd -and $stderr.IsCompleted) {
                $line=$stderr.GetAwaiter().GetResult()
                if($null -eq $line){$errEnd=$true;break}
                $errFile.WriteLine($line);$errFile.Flush();$stderr=$process.StandardError.ReadLineAsync()
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
            if(-not $process.HasExited -and ([DateTimeOffset]::Now-$start).TotalSeconds -gt $timeout) {
                if(-not $stopSent){$process.StandardInput.WriteLine('stop');$process.StandardInput.Flush();$stopSent=$true}
                if(([DateTimeOffset]::Now-$start).TotalSeconds -gt ($timeout+60)) {throw "逾時且 stdin stop 未完成：$evidence"}
            }
            Start-Sleep -Milliseconds 100
        }
        $process.WaitForExit()
    } finally {$outFile.Dispose();$errFile.Dispose()}
    $exit=$process.ExitCode
    Save-Json (Join-Path $evidence 'execution.json') ([ordered]@{exitCode=$exit;start=$start.ToString('o');end=[DateTimeOffset]::Now.ToString('o');command=$info.Arguments;launcherPid=$process.Id;doneSeen=($null-ne $done);stdinStop=$stopSent;nativeSaves=$debugCount;status=$(if($exit-eq 0){'EXIT_ZERO'}else{'FAIL'})})
    Save-Json (Join-Path $evidence 'after.json') (Root-State $runtimeRoot)
    Save-Json (Join-Path $evidence 'locks-after.json') @(Lock-State $runtimeRoot)
    if(Test-Path -LiteralPath (Join-Path $runtimeRoot 'logs')){Copy-Item -LiteralPath (Join-Path $runtimeRoot 'logs') -Destination (Join-Path $evidence 'runtime-logs') -Recurse}
    # 只複製本次觀察到的 JVM（$known）產出的 classload／boundary；runDir 累積的舊檔不進 gate root（整合審查 R1）。
    foreach($file in @(Get-ChildItem -LiteralPath $runtimeRoot -Filter 'classload-*.log' -File -ErrorAction SilentlyContinue)){if($file.Name -match '^classload-(\d+)\.log$' -and $known.ContainsKey($Matches[1])){Copy-Item -LiteralPath $file.FullName -Destination $evidence}}
    foreach($file in @(Get-ChildItem -LiteralPath $runtimeRoot -Filter 'm4-boundary-*.json' -File -ErrorAction SilentlyContinue)){$owner=[regex]::Match([string](Get-Content -LiteralPath $file.FullName -Raw),'"pid"\s*:\s*(\d+)');if($owner.Success -and $known.ContainsKey($owner.Groups[1].Value)){Copy-Item -LiteralPath $file.FullName -Destination $evidence}}
    foreach($directory in @(Get-ChildItem -LiteralPath $runtimeRoot -Filter 'm4-per-test-*' -Directory -ErrorAction SilentlyContinue)) {
        if($directory.Name -match '^m4-per-test-(\d+)-' -and $known.ContainsKey($Matches[1])) {Copy-Item -LiteralPath $directory.FullName -Destination $evidence -Recurse}
    }
    $launch=Join-Path $repo '.gradle/loom-cache/launch.cfg'
    if(Test-Path -LiteralPath $launch){Copy-Item -LiteralPath $launch -Destination (Join-Path $evidence 'loom-launch.cfg')}
    Write-Output "Gradle exit=$exit：$evidence"
    return $exit
}
function Fresh-Game([bool]$full,[bool]$legacy) {
    $run=Join-Path $repo $(if($legacy){'run/gametest-legacy'}else{'run/gametest'})
    $swap=Move-Fresh (Join-Path $run 'world') 'world'
    try {
        Copy-Build (Join-Path $gate 'prior-build')
        $task=if($legacy){'runGameTestLegacy'}else{'runGameTest'}
        $tasks=if($full){@('clean','test',$task,'build','--rerun-tasks')}else{@($task,'--rerun-tasks')}
        $result=@(Invoke-Gradle $tasks (Join-Path $gate 'execution') $run '' 1200)[-1]
        Copy-Build (Join-Path $gate 'result-build')
        if($result -ne 0){throw "GameTest gate exit=$result"}
        $server=@(Get-Content -LiteralPath (Join-Path $gate 'execution/processes.json') -Raw | ConvertFrom-Json | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
        $boundary=@(Get-ChildItem -LiteralPath (Join-Path $gate 'execution') -Filter 'm4-boundary-*.json' | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json} | Where-Object {$_.pid -eq $server[0].pid})
        if($boundary.Count -ne 1 -or $boundary[0].status -ne 'PASS'){throw 'GameTest Universe boundary oracle 缺失或 FAIL'}
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
        'GameTest' {Fresh-Game $false $false}
        'Full' {Fresh-Game $true $false}
        'Legacy' {Fresh-Game $false $true}
        'Windows' {
            Copy-Build (Join-Path $gate 'prior-build')
            $exit=@(Invoke-Gradle @('test','--tests','dev.quantumchamber.persistence.WindowsPlayerCheckpointVerifierTest','--tests','dev.quantumchamber.persistence.PlayerCheckpointStoreTest','--tests','dev.quantumchamber.persistence.PlayerRecoveryCheckpointTest','--rerun-tasks') (Join-Path $gate 'execution') (Join-Path $repo 'run/testworlds'))[-1]
            Copy-Build (Join-Path $gate 'result-build');if($exit-ne 0){throw "Windows gate exit=$exit"}
        }
        'M3' {if($Only-ne 'transfer'){Probe-Chain 'universe' @('create-save','reload-read','unload-replace','final-verify')};if($Only-ne 'universe'){Probe-Chain 'transfer' @('setup-catalog','success-roundtrip','target-not-full','post-move-authority-loss','stale-service-receipt')}}
    }
    Save-Json (Join-Path $gate 'gate.json') ([ordered]@{mode=$Mode;label=$Label;status='PASS';evidence=$gate})
} catch {
    Save-Json (Join-Path $gate 'gate.json') ([ordered]@{mode=$Mode;label=$Label;status='FAIL';error=$_.ToString();evidence=$gate})
    throw
} finally {Write-Output "GATE_ROOT=$gate"}
