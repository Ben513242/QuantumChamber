# 共用 owned-phase 機制；dot-source 只定義函式。Context 與 State 都由 caller 明確傳入。

function Resolve-Evidence($Context, [string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($Context.Project + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "evidence 目錄必須在 repo 內：$full" }
    $cursor = $full
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "拒絕 reparse：$cursor" }
        $parent = [IO.Path]::GetDirectoryName($cursor); if ($parent -eq $cursor) { break }; $cursor = $parent
    }
    git -C $Context.Project check-ignore -q -- ([IO.Path]::GetRelativePath($Context.Project, $full).Replace('\', '/') + '/x')
    if ($LASTEXITCODE -ne 0) { throw "evidence 目錄未被 gitignore：$full" }
    if (-not (Test-Path -LiteralPath $full)) { New-Item -ItemType Directory -Path $full | Out-Null }
    return $full
}

function New-OwnedRoot($Context, [string]$Suffix) {
    $nonce = "$($Context.RunId)-$Suffix"
    $root = Join-Path $Context.Project "run/$($Context.RootPrefix)-$nonce"
    if (Test-Path -LiteralPath $root) { throw "root 已存在：$root" }
    New-Item -ItemType Directory -Path $root | Out-Null
    'eula=true' | Set-Content -LiteralPath (Join-Path $root 'eula.txt') -Encoding ASCII
    @('online-mode=false', 'server-port=0', 'level-name=world', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2', 'sync-chunk-writes=true', 'level-seed=20260921', 'max-tick-time=120000') | Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ASCII
    return [pscustomobject]@{nonce=$nonce; root=(Resolve-Path -LiteralPath $root).Path}
}

function Get-ReceiptNote([string]$PhaseEvidence) {
    $notes = @()
    foreach ($name in 'final.json', 'halt.json') {
        $path = Join-Path $PhaseEvidence $name
        if (-not (Test-Path -LiteralPath $path)) { continue }
        try {
            $r = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -DateKind String
            $note = "$name status=$($r.status) failures=[$(@($r.failures) -join ' | ')]"
            if ($name -eq 'halt.json') { $note += " hit=$($r.hit) crashPointFailure=$($r.crashPointFailure) refused=[$(@($r.refused) -join ' | ')]" }
            $notes += $note
        } catch { $notes += "$name 讀不出來：$_" }
    }
    if ($notes.Count -eq 0) { return '沒有 final.json 也沒有 halt.json' }
    return ($notes -join '；')
}

function Get-SharedLength([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return -1 }
    try { $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete) } catch { return -1 }
    try { return $stream.Length } finally { $stream.Dispose() }
}

function Get-SharedTail([string]$Path, [int]$Count) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        try { $text = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8).ReadToEnd() } finally { $stream.Dispose() }
        return @($text -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -Last $Count)
    } catch { return @("（讀不出來：$_）") }
}

function Stop-TimedOutPhase($Context, $State, [string]$Reason) {
    $Phase=$State.Phase; $PhaseEvidence=$State.PhaseEvidence; $Root=$State.Root; $StartupNonce=$State.StartupNonce
    $JavaProcess=$State.JavaProcess; $JavaId=$State.JavaId; $JavaStart=$State.JavaStart; $Launcher=$State.Launcher
    $Started=$State.Started; $LastActivity=$State.LastActivity; $IdleSeconds=$Context.IdleSeconds; $PhaseMinutes=$Context.PhaseMinutes

    $now = [DateTimeOffset]::UtcNow
    $record = [ordered]@{ phase=$Phase; reason=$Reason; idleLimitSeconds=$IdleSeconds; phaseLimitMinutes=$PhaseMinutes; timeUtc=$now.ToString('o')
        elapsedSeconds=[Math]::Round(($now - $Started).TotalSeconds, 1); idleSeconds=$(if ($null -ne $LastActivity) { [Math]::Round(($now - $LastActivity).TotalSeconds, 1) } else { $null })
        root=$Root; startupNonce=$StartupNonce; pid=$JavaId; javaStartTimeUtc=$(if ($null -ne $JavaStart) { $JavaStart.ToString('o') } else { $null })
        commandLineMatched=$false; jstack=$null; jstackExitCode=$null; killed=$false; javaExited=$null; javaExitCode=$null; launcherExited=$null; launcherExitCode=$null
        lastLogLines=@(); lastGradleLines=@() }
    if ($null -ne $JavaId -and -not $JavaProcess.HasExited) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$JavaId"
        $sameStart = ([DateTimeOffset]$JavaProcess.StartTime).ToUniversalTime() -eq $JavaStart
        $matched = $null -ne $process -and $process.Name -eq 'java.exe' -and $process.CommandLine -like "*$($Context.NonceProperty)=$StartupNonce*" -and $sameStart
        $record.commandLineMatched = [bool]$matched
        if ($matched) {
            $jstack = Join-Path (Split-Path -Parent $process.ExecutablePath) 'jstack.exe'
            if (Test-Path -LiteralPath $jstack -PathType Leaf) {
                $dump = Join-Path $PhaseEvidence "jstack-$JavaId.txt"
                $dumper = Start-Process -FilePath $jstack -ArgumentList @('-l', "$JavaId") -RedirectStandardOutput $dump -RedirectStandardError "$dump.err" -WindowStyle Hidden -PassThru
                if ($dumper.WaitForExit(60000)) { $record.jstack = Split-Path -Leaf $dump; $record.jstackExitCode = $dumper.ExitCode } else { $record.jstack = 'TIMEOUT（60 秒沒有結束）' }
            } else { $record.jstack = "沒有 jstack：$jstack" }
            Stop-Process -Id $JavaId -Force
            $record.killed = $true
            $record.javaExited = $JavaProcess.WaitForExit(30000)
            if ($record.javaExited) { $record.javaExitCode = $JavaProcess.ExitCode }
        }
    }
    $record.launcherExited = $Launcher.WaitForExit(60000)
    if ($record.launcherExited) { $record.launcherExitCode = $Launcher.ExitCode }
    $record.lastLogLines = Get-SharedTail (Join-Path $Root 'logs/latest.log') 20
    $record.lastGradleLines = Get-SharedTail (Join-Path $PhaseEvidence 'stdout.log') 20
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PhaseEvidence 'timeout.json') -Encoding UTF8
    throw "owned phase 逾時（$Reason）：pid=$JavaId commandLineMatched=$($record.commandLineMatched) jstack=$($record.jstack) killed=$($record.killed)；timeout.json 在 $PhaseEvidence；禁止同 root 重跑：$Root phase=$Phase"
}

function Invoke-OwnedPhase($Context, [string]$Nonce, [string]$Phase, [string]$Root) {
    $phaseEvidence = Join-Path $Context.EvidenceOwner "$($Context.EvidencePrefix)-$Nonce/$Phase"
    if (Test-Path -LiteralPath $phaseEvidence) { throw "phase evidence 已存在，禁止重跑：$phaseEvidence" }
    New-Item -ItemType Directory -Path $phaseEvidence | Out-Null
    $startupNonce = [guid]::NewGuid().ToString('N')
    $arguments = @('-I', $Context.InitScript, $Context.Task, "-P$($Context.PropertyPrefix)Nonce=$Nonce", "-P$($Context.PropertyPrefix)Phase=$Phase", "-P$($Context.PropertyPrefix)StartupNonce=$startupNonce", "-P$($Context.PropertyPrefix)Root=$Root", '--console=plain')
    $quotedArguments = $arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $command = "Set-Location -LiteralPath '" + $Context.Project.Replace("'", "''") + "'; & '" + $Context.Gradle.Replace("'", "''") + "' " + ($quotedArguments -join ' ') + '; exit $LASTEXITCODE'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $stdout = Join-Path $phaseEvidence 'stdout.log'; $stderr = Join-Path $phaseEvidence 'stderr.log'
    $launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $started = [DateTimeOffset]::UtcNow
    $deadline = $started.AddMinutes($Context.PhaseMinutes)
    $lastActivity = $null
    $lastSizes = ''
    $javaId = $null
    $javaStart = $null
    $javaProcess = $null
    $state = [pscustomobject]@{Phase=$Phase; PhaseEvidence=$phaseEvidence; Root=$Root; StartupNonce=$startupNonce; Launcher=$launcher; Started=$started; LastActivity=$null; JavaProcess=$null; JavaId=$null; JavaStart=$null}
    while (-not $launcher.HasExited) {
        if ($null -eq $javaId) {
            $candidate = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$($Context.NonceProperty)=$startupNonce*" }
            if (@($candidate).Count -gt 1) { throw '同一 startupNonce 出現多個 JVM' }
            if ($candidate) {
                $javaId = [int]$candidate.ProcessId
                $javaProcess = [Diagnostics.Process]::GetProcessById($javaId)
                # 先取得 handle：JVM 是 Gradle daemon 的子程序，結束之後仍要以這個 handle 讀 ExitCode（halt 的 77）。
                $null = $javaProcess.Handle
                $javaStart = ([DateTimeOffset]$javaProcess.StartTime).ToUniversalTime()
                $state.JavaProcess=$javaProcess; $state.JavaId=$javaId; $state.JavaStart=$javaStart
                [ordered]@{pid=$javaId;startTime=([DateTimeOffset]$javaProcess.StartTime).ToString('o');commandLine=$candidate.CommandLine} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'process.json') -Encoding UTF8
            }
        }
        $now = [DateTimeOffset]::UtcNow
        if ($null -ne $javaId) {
            # 有新內容就是還在動：latest.log（server 的 log）與 Gradle 的輸出（JVM 的 stdout 經 Gradle 轉出）任一變長。
            $sizes = "$(Get-SharedLength (Join-Path $Root 'logs/latest.log'))/$(Get-SharedLength $stdout)/$(Get-SharedLength $stderr)"
            if ($null -eq $lastActivity -or $sizes -ne $lastSizes) { $lastActivity = $now; $lastSizes = $sizes }
            $state.LastActivity=$lastActivity
            if (($now - $lastActivity).TotalSeconds -gt $Context.IdleSeconds) {
                Stop-TimedOutPhase -Context $Context -State $state -Reason "idle：$($Context.IdleSeconds) 秒沒有新的 log"
            }
        }
        if ($now -gt $deadline) { Stop-TimedOutPhase -Context $Context -State $state -Reason "total：超過 $($Context.PhaseMinutes) 分鐘" }
        Start-Sleep -Milliseconds 250
        $launcher.Refresh()
    }
    $launcher.WaitForExit()
    if ($null -eq $javaProcess) { throw "沒有找到 phase 的 JVM（startupNonce=$startupNonce）；保留 root：$Root" }
    if (-not $javaProcess.WaitForExit(60000)) { throw "Gradle 已結束但 JVM 仍在執行：pid=$javaId" }
    $javaExit = $javaProcess.ExitCode
    return $state
}
