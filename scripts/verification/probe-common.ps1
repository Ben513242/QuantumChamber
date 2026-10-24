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

function Assert-OwnedRunAncestor($Context) {
    try {
        $project = [string]$Context.Project
        if (-not [IO.Path]::IsPathFullyQualified($project)) { throw 'Project 必須是絕對路徑' }
        $full = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($project))
        $supplied = [IO.Path]::TrimEndingDirectorySeparator($project.Replace([IO.Path]::AltDirectorySeparatorChar, [IO.Path]::DirectorySeparatorChar))
        if (-not $full.Equals($supplied, [StringComparison]::OrdinalIgnoreCase)) { throw "Project 不是 canonical 路徑：$project" }
        if (-not (Test-Path -LiteralPath $full -PathType Container)) { throw "Project 目錄不存在：$full" }
        $run = Join-Path $full 'run'
        $cursor = $run
        while ($cursor) {
            # Get-Item 亦可辨認目標已遺失的 junction；不能只依 Test-Path 略過它。
            $item = Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue -ErrorVariable itemError
            if ($item) {
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "拒絕 reparse：$cursor" }
                if (-not $item.PSIsContainer) { throw "祖先不是目錄：$cursor" }
            } elseif ($itemError -and $itemError[0].CategoryInfo.Category -ne 'ObjectNotFound') {
                throw $itemError[0]
            }
            $parent = [IO.Path]::GetDirectoryName($cursor)
            if ($parent -eq $cursor) { break }
            $cursor = $parent
        }
        return $run
    } catch { throw "OWNED_ROOT_ANCESTOR_UNSAFE：$($_.Exception.Message)" }
}

function New-OwnedRoot($Context, [string]$Suffix) {
    $run = Assert-OwnedRunAncestor -Context $Context
    $nonce = "$($Context.RunId)-$Suffix"
    $root = [IO.Path]::GetFullPath((Join-Path $run "$($Context.RootPrefix)-$nonce"))
    if (-not [IO.Path]::GetDirectoryName($root).Equals($run, [StringComparison]::OrdinalIgnoreCase)) { throw "OWNED_ROOT_ANCESTOR_UNSAFE：root 必須直接位於 run 內：$root" }
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

# 來源宣告分開：server latest.log 是 UTF-8；Gradle 是 launcher 明確選定的 console code page。
function Get-SharedText([string]$Path, [Text.Encoding]$Encoding = [Text.Encoding]::UTF8) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        try { $reader = [IO.StreamReader]::new($stream, $Encoding, $true); return $reader.ReadToEnd() } finally { $stream.Dispose() }
    } catch { return "（讀不出來：$_）" }
}

function Get-SharedTail([string]$Path, [int]$Count, [Text.Encoding]$Encoding = [Text.Encoding]::UTF8) {
    $text = Get-SharedText -Path $Path -Encoding $Encoding
    return @($text -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -Last $Count)
}

# 最新 CIM CreationDate 是獨立觀察；容許 Windows CIM 微秒精度造成的小於 1ms 差異。
# 已持有的 handle 必須仍存活，命令 token 與 PID 亦必須相符。任何讀取失敗都拒絕終止。
function Get-OwnedIdentity($Process, [DateTimeOffset]$Started, [string]$Name, [string]$CommandToken) {
    $check = [ordered]@{matched=$false; pid=$Process.Id; observedStartTimeUtc=$null; nameMatched=$false; commandLineMatched=$false; startTimeMatched=$false; queryError=$null}
    if ($Process.HasExited) { return $check }
    try {
        $live = Get-CimInstance Win32_Process -Filter "ProcessId=$($Process.Id)"
        if ($null -eq $live) { return $check }
        $freshStart = ([DateTimeOffset]$live.CreationDate).ToUniversalTime()
        $check.observedStartTimeUtc = $freshStart.ToString('o')
        $check.nameMatched = $live.Name -eq $Name
        $check.commandLineMatched = $live.CommandLine -cmatch ([regex]::Escape($CommandToken) + '(?=\s|$|")')
        $check.startTimeMatched = [Math]::Abs(($freshStart - $Started).TotalMilliseconds) -lt 1
        $check.matched = $check.nameMatched -and $check.commandLineMatched -and $check.startTimeMatched -and -not $Process.HasExited
    } catch { $check.queryError = $_.Exception.Message }
    return $check
}

function Get-PhaseFailureNote($Context, $State) {
    $note = Get-ReceiptNote $State.PhaseEvidence
    $lines = @( (Get-SharedText -Path (Join-Path $State.Root 'logs/latest.log') -Encoding $Context.LogEncoding) -split "`r?`n" )
    for ($i=0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notlike '*Could not execute entrypoint*') { continue }
        $block = [Collections.Generic.List[string]]::new()
        $block.Add($lines[$i])
        for ($j=$i+1; $j -lt $lines.Count; $j++) {
            if ($lines[$j] -match '^\[\d\d:\d\d:\d\d\]') { break }
            if ($j - $i -lt 80 -or $lines[$j] -match 'Caused by:') { $block.Add($lines[$j]) }
        }
        $note += "；entrypoint latest.log：" + ($block -join "`n")
        break
    }
    return $note
}

function Copy-OwnedLogs($State) {
    $logs = [ordered]@{}
    foreach ($item in @(@{key='classload';relative="classload-$($State.JavaId).log"}, @{key='latest';relative='logs/latest.log'})) {
        $source = Join-Path $State.Root $item.relative
        $present = Test-Path -LiteralPath $source -PathType Leaf
        $logs[$item.key] = [ordered]@{source=$source; present=[bool]$present; copied=$false}
        if ($present) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $State.PhaseEvidence (Split-Path -Leaf $source))
            $logs[$item.key].copied = $true
        }
    }
    return $logs
}

function Stop-TimedOutPhase($Context, $State, [string]$Reason) {
    $now = [DateTimeOffset]::UtcNow
    $record = [ordered]@{phase=$State.Phase; reason=$Reason; idleLimitSeconds=$Context.IdleSeconds; phaseLimitMinutes=$Context.PhaseMinutes; timeUtc=$now.ToString('o')
        elapsedSeconds=[Math]::Round(($now - $State.Started).TotalSeconds, 1); idleSeconds=$(if ($null -ne $State.LastActivity) { [Math]::Round(($now - $State.LastActivity).TotalSeconds, 1) } else { $null })
        root=$State.Root; startupNonce=$State.StartupNonce; pid=$State.JavaId; javaStartTimeUtc=$(if ($null -ne $State.JavaStart) { $State.JavaStart.ToString('o') } else { $null })
        commandLineMatched=$false; jstack=$null; jstackExitCode=$null; killed=$false; javaExited=$null; javaExitCode=$null; launcherExited=$null; launcherExitCode=$null
        identityCheck=$null; identityBeforeKill=$null; discoveryError=$null; launcherPid=$State.Launcher.Id; launcherIdentity=$null; launcherKilled=$false
        encodings=@{latestLog=$Context.LogEncoding.WebName; gradle=$Context.GradleEncoding.WebName; gradleCodePage=$Context.GradleEncoding.CodePage}; lastLogLines=@(); lastGradleLines=@()}
    $noJavaConfirmed = $false
    # timeout 當下重新找一次 nonce；沒有舊 JavaId 不等於程序沒有啟動。
    if ($null -eq $State.JavaId) {
        try {
            $candidates = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -cmatch ([regex]::Escape("$($Context.NonceProperty)=$($State.StartupNonce)") + '(?=\s|$|")') })
            if ($candidates.Count -eq 0) { $noJavaConfirmed = $true }
            elseif ($candidates.Count -eq 1) {
                $State.JavaId = [int]$candidates[0].ProcessId
                $State.JavaProcess = [Diagnostics.Process]::GetProcessById($State.JavaId)
                $null = $State.JavaProcess.Handle
                $State.JavaStart = ([DateTimeOffset]$State.JavaProcess.StartTime).ToUniversalTime()
                $record.pid=$State.JavaId; $record.javaStartTimeUtc=$State.JavaStart.ToString('o')
            } else { $record.discoveryError='同一 nonce 出現多個 JVM，無法證實單一 owner' }
        } catch { $record.discoveryError=$_.Exception.Message }
    }
    if ($null -ne $State.JavaProcess -and -not $State.JavaProcess.HasExited) {
        $token = "$($Context.NonceProperty)=$($State.StartupNonce)"
        $check = Get-OwnedIdentity -Process $State.JavaProcess -Started $State.JavaStart -Name 'java.exe' -CommandToken $token
        $record.identityCheck=$check; $record.commandLineMatched=[bool]$check.matched
        if ($check.matched) {
            try {
                $live = Get-CimInstance Win32_Process -Filter "ProcessId=$($State.JavaId)"
                $jstack = Join-Path (Split-Path -Parent $live.ExecutablePath) 'jstack.exe'
                if (Test-Path -LiteralPath $jstack -PathType Leaf) {
                    $dump = Join-Path $State.PhaseEvidence "jstack-$($State.JavaId).txt"
                    $dumper = Start-Process -FilePath $jstack -ArgumentList @('-l', "$($State.JavaId)") -RedirectStandardOutput $dump -RedirectStandardError "$dump.err" -WindowStyle Hidden -PassThru
                    if ($dumper.WaitForExit(60000)) { $record.jstack=Split-Path -Leaf $dump; $record.jstackExitCode=$dumper.ExitCode } else { $record.jstack='TIMEOUT（60 秒沒有結束）' }
                } else { $record.jstack="沒有 jstack：$jstack" }
            } catch { $record.jstack="取證失敗：$($_.Exception.Message)" }
            $record.identityBeforeKill = Get-OwnedIdentity -Process $State.JavaProcess -Started $State.JavaStart -Name 'java.exe' -CommandToken $token
            if ($record.identityBeforeKill.matched) {
                $State.JavaProcess.Kill()
                $record.killed=$true; $record.javaExited=$State.JavaProcess.WaitForExit(30000)
                if ($record.javaExited) { $record.javaExitCode=$State.JavaProcess.ExitCode }
            }
        }
    }
    # 只有當下沒有 nonce JVM、且新 CIM 身分仍吻合時，才結束本次 launcher handle；不掃殺子程序或 daemon。
    if ($noJavaConfirmed -and -not $State.Launcher.HasExited) {
        $record.launcherIdentity = Get-OwnedIdentity -Process $State.Launcher -Started $State.LauncherStart -Name 'powershell.exe' -CommandToken $State.LauncherCommandToken
        if ($record.launcherIdentity.matched) { $State.Launcher.Kill(); $record.launcherKilled=$true }
    }
    $record.launcherExited=$State.Launcher.WaitForExit(60000)
    if ($record.launcherExited) { $record.launcherExitCode=$State.Launcher.ExitCode }
    $record.lastLogLines=Get-SharedTail -Path (Join-Path $State.Root 'logs/latest.log') -Count 20 -Encoding $Context.LogEncoding
    $record.lastGradleLines=Get-SharedTail -Path (Join-Path $State.PhaseEvidence 'stdout.log') -Count 20 -Encoding $Context.GradleEncoding
    $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $State.PhaseEvidence 'timeout.json') -Encoding UTF8
    throw "owned phase 逾時（$Reason）：pid=$($State.JavaId) commandLineMatched=$($record.commandLineMatched) jstack=$($record.jstack) killed=$($record.killed) launcherKilled=$($record.launcherKilled)；timeout.json 在 $($State.PhaseEvidence)；禁止同 root 重跑：$($State.Root) phase=$($State.Phase)"
}

function Invoke-OwnedPhase($Context, [string]$Nonce, [string]$Phase, [string]$Root) {
    $phaseEvidence = Join-Path $Context.EvidenceOwner "$($Context.EvidencePrefix)-$Nonce/$Phase"
    if (Test-Path -LiteralPath $phaseEvidence) { throw "phase evidence 已存在，禁止重跑：$phaseEvidence" }
    New-Item -ItemType Directory -Path $phaseEvidence | Out-Null
    $startupNonce = [guid]::NewGuid().ToString('N')
    $arguments = @('-I', $Context.InitScript, $Context.Task, "-P$($Context.PropertyPrefix)Nonce=$Nonce", "-P$($Context.PropertyPrefix)Phase=$Phase", "-P$($Context.PropertyPrefix)StartupNonce=$startupNonce", "-P$($Context.PropertyPrefix)Root=$Root", '--console=plain')
    $quotedArguments = $arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $command = '$OutputEncoding=[Text.Encoding]::GetEncoding(' + $Context.GradleEncoding.CodePage + '); [Console]::OutputEncoding=$OutputEncoding; ' + "Set-Location -LiteralPath '" + $Context.Project.Replace("'", "''") + "'; & '" + $Context.Gradle.Replace("'", "''") + "' " + ($quotedArguments -join ' ') + '; exit $LASTEXITCODE'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $stdout = Join-Path $phaseEvidence 'stdout.log'; $stderr = Join-Path $phaseEvidence 'stderr.log'
    $launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null = $launcher.Handle
    $launcherStart = ([DateTimeOffset]$launcher.StartTime).ToUniversalTime()
    $started = [DateTimeOffset]::UtcNow
    $deadline = $started.AddMinutes($Context.PhaseMinutes)
    $lastActivity = $null
    $lastSizes = ''
    $javaId = $null
    $javaStart = $null
    $javaProcess = $null
    $state = [pscustomobject]@{Phase=$Phase; PhaseEvidence=$phaseEvidence; Root=$Root; StartupNonce=$startupNonce; Launcher=$launcher; LauncherStart=$launcherStart; LauncherCommandToken=$encoded; Started=$started; LastActivity=$null; JavaProcess=$null; JavaId=$null; JavaStart=$null}
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
    if ($null -eq $javaProcess) { throw "沒有找到 phase 的 JVM（startupNonce=$startupNonce；$(Get-PhaseFailureNote -Context $Context -State $state)）；保留 root：$Root" }
    if (-not $javaProcess.WaitForExit(60000)) { throw "Gradle 已結束但 JVM 仍在執行：pid=$javaId" }
    $javaExit = $javaProcess.ExitCode
    return $state
}
