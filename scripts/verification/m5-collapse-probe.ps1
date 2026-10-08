# m5-collapse-probe.ps1：M5 跨 JVM 的塌縮 crash／restart probe（runM5Collapse；spec §12 的 crash 窗口，M5 F6-1）。每條 chain 使用新的 owned root
# run/m5-collapse-<runId>-<chain>，每個 phase 一個獨立 JVM。chain 表的每個 phase 標記預期的結束：
#   stop：Gradle exit 0、JVM exit code 0、final.json status PASS（stoppingSeen／stoppedSeen／stopRequested），沒有 halt.json；
#   halt：Gradle 非 0、JVM exit code 77（以 JVM 的 process handle 讀取，另以 Gradle 輸出的 "finished with non-zero exit value 77" 交叉核對）、
#         halt.json status HALT 且窗口相符，沒有 final.json。
# 兩種結束都核對 PID／StartTime／startupNonce 與 receipt 相符、JVM 已不存在、world/session.lock 可獨占開啟；同一條 chain 的 PID@StartTime 與 startupNonce 都不同。
# 任何失敗都 throw，保留 root 與 phase evidence，禁止在同 root 重跑。
# 參數：
#   -Only          只跑指定 chain（例如 -Only w2,w3；以 pwsh -File 呼叫時也接受逗號分隔的單一字串）；未指定時跑全部
#   -EvidenceRoot  彙整 collapse-run-<runId>.json 的位置；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
# phase receipt 位置由 testmod M5CollapseRecoveryProbe 固定為 <repo>/.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>，不受 -EvidenceRoot 影響。
# 新增 phase：testmod 的 M5CollapseRecoveryProbe.phases() 加 entry，這裡的 $chains 加同名的 phase 與預期的結束（README「M5 collapse probe」）。
# 結構比照 m4-recovery-probe.ps1（owned root、phase evidence、launcher.json、彙整 JSON）。
param([string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '')
$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($marker in 'build.gradle', 'settings.gradle') { if (-not (Test-Path -LiteralPath (Join-Path $taskProject $marker) -PathType Leaf)) { throw "repo root 缺少 ${marker}：$taskProject" } }
if (-not $Gradle) { $dist = 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat'; $Gradle = if (Test-Path -LiteralPath $dist -PathType Leaf) { $dist } else { Join-Path $taskProject 'gradlew.bat' } }
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) { throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）" }
$taskGradle = $Gradle

# chain 表：chain 名稱 → 依序執行的 phase（名稱必須與 M5CollapseRecoveryProbe.phases() 相同）、預期的結束與 halt 的窗口。
$chains = [ordered]@{
}
$Only = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($name in $Only) { if (-not $chains.Contains($name)) { throw "未知的 chain：$name（可用：$($chains.Keys -join ', ')）" } }

# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
function Resolve-Evidence([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($taskProject + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "evidence 目錄必須在 repo 內：$full" }
    $cursor = $full
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "拒絕 reparse：$cursor" }
        $parent = [IO.Path]::GetDirectoryName($cursor); if ($parent -eq $cursor) { break }; $cursor = $parent
    }
    git -C $taskProject check-ignore -q -- ([IO.Path]::GetRelativePath($taskProject, $full).Replace('\', '/') + '/x')
    if ($LASTEXITCODE -ne 0) { throw "evidence 目錄未被 gitignore：$full" }
    if (-not (Test-Path -LiteralPath $full)) { New-Item -ItemType Directory -Path $full | Out-Null }
    return $full
}
if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $taskProject '.superpowers/verification' }
$EvidenceRoot = Resolve-Evidence $EvidenceRoot
$probeOwner = Resolve-Evidence (Join-Path $taskProject '.superpowers/sdd/2026-10-01-m5-collapse-passage')
$taskRunId = [guid]::NewGuid().ToString('N')
$taskResults = [System.Collections.Generic.List[object]]::new()

function Invoke-OwnedPhase([string]$Nonce, [string]$Phase, [string]$Root, [string]$Ending, [string]$Window) {
    $phaseEvidence = Join-Path $probeOwner "collapse-$Nonce/$Phase"
    if (Test-Path -LiteralPath $phaseEvidence) { throw "phase evidence 已存在，禁止重跑：$phaseEvidence" }
    New-Item -ItemType Directory -Path $phaseEvidence | Out-Null
    $startupNonce = [guid]::NewGuid().ToString('N')
    $arguments = @('-I', (Join-Path $PSScriptRoot 'runtime.init.gradle'), 'runM5Collapse', "-Pm5CollapseNonce=$Nonce", "-Pm5CollapsePhase=$Phase", "-Pm5CollapseStartupNonce=$startupNonce", "-Pm5CollapseRoot=$Root", '--console=plain')
    $quotedArguments = $arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $command = "Set-Location -LiteralPath '" + $taskProject.Replace("'", "''") + "'; & '" + $taskGradle.Replace("'", "''") + "' " + ($quotedArguments -join ' ') + '; exit $LASTEXITCODE'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $stdout = Join-Path $phaseEvidence 'stdout.log'; $stderr = Join-Path $phaseEvidence 'stderr.log'
    $launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(8)
    $javaId = $null
    $javaStart = $null
    $javaProcess = $null
    while (-not $launcher.HasExited) {
        if ($null -eq $javaId) {
            $candidate = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*quantumchamber.m5collapse.startupNonce=$startupNonce*" }
            if (@($candidate).Count -gt 1) { throw '同一 startupNonce 出現多個 JVM' }
            if ($candidate) {
                $javaId = [int]$candidate.ProcessId
                $javaProcess = [Diagnostics.Process]::GetProcessById($javaId)
                # 先取得 handle：JVM 是 Gradle daemon 的子程序，結束之後仍要以這個 handle 讀 ExitCode（halt 的 77）。
                $null = $javaProcess.Handle
                $javaStart = ([DateTimeOffset]$javaProcess.StartTime).ToUniversalTime()
                [ordered]@{pid=$javaId;startTime=([DateTimeOffset]$javaProcess.StartTime).ToString('o');commandLine=$candidate.CommandLine} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'process.json') -Encoding UTF8
            }
        }
        if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "owned phase 逾時；禁止同 root 重跑：$Root phase=$Phase" }
        Start-Sleep -Milliseconds 250
        $launcher.Refresh()
    }
    $launcher.WaitForExit()
    if ($null -eq $javaProcess) { throw "沒有找到 phase 的 JVM（startupNonce=$startupNonce）；保留 root：$Root" }
    if (-not $javaProcess.WaitForExit(60000)) { throw "Gradle 已結束但 JVM 仍在執行：pid=$javaId" }
    $javaExit = $javaProcess.ExitCode
    $gradleOutput = (Get-Content -LiteralPath $stdout -Raw) + (Get-Content -LiteralPath $stderr -Raw)
    $finalPath = Join-Path $phaseEvidence 'final.json'
    $haltPath = Join-Path $phaseEvidence 'halt.json'
    if ($Ending -eq 'stop') {
        if ($launcher.ExitCode -ne 0) { throw "Gradle 非零退出：$($launcher.ExitCode)；保留 root：$Root" }
        if ($javaExit -ne 0) { throw "JVM exit code $javaExit（預期正常停止的 0）；保留 root：$Root" }
        if (Test-Path -LiteralPath $haltPath) { throw "預期正常停止的 phase 留下 halt receipt：$haltPath" }
        if (-not (Test-Path -LiteralPath $finalPath)) { throw "未產生正常 stop receipt：$phaseEvidence" }
        $receiptPath = $finalPath
        $receipt = Get-Content -LiteralPath $finalPath -Raw | ConvertFrom-Json -DateKind String
        if ($receipt.status -ne 'PASS' -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen -or -not $receipt.stopRequested) { throw "probe 未通過：$phaseEvidence failures=$($receipt.failures -join ' | ')" }
    } elseif ($Ending -eq 'halt') {
        if ($launcher.ExitCode -eq 0) { throw "預期 halt 的 phase 以 Gradle exit 0 結束；保留 root：$Root" }
        if (Test-Path -LiteralPath $finalPath) {
            $failed = Get-Content -LiteralPath $finalPath -Raw | ConvertFrom-Json -DateKind String
            throw "預期 halt 的 phase 正常停止了（final.json status=$($failed.status) failures=$($failed.failures -join ' | ')）：$phaseEvidence"
        }
        if ($javaExit -ne 77) { throw "JVM exit code $javaExit（預期 halt 的 77）；保留 root：$Root" }
        if ($gradleOutput -notmatch 'finished with non-zero exit value 77') { throw "Gradle 輸出沒有 'finished with non-zero exit value 77'：$phaseEvidence" }
        if (-not (Test-Path -LiteralPath $haltPath)) { throw "未產生 halt receipt：$phaseEvidence" }
        $receiptPath = $haltPath
        $receipt = Get-Content -LiteralPath $haltPath -Raw | ConvertFrom-Json -DateKind String
        if ($receipt.status -ne 'HALT' -or $receipt.window -ne $Window -or $receipt.phase -ne $Phase) { throw "halt receipt 不符：status=$($receipt.status) window=$($receipt.window)（預期 $Window）" }
    } else { throw "未知的結束方式：$Ending" }
    if ($receipt.runtimeScope -ne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds) { throw 'collapse receipt 必須明確標示 testmod runtime，不能冒充 main-only' }
    if ($javaId -ne $receipt.pid -or $receipt.startupNonce -ne $startupNonce) { throw 'JVM identity receipt 不符' }
    $recordedStart = [DateTimeOffset]::Parse($receipt.startTimeUtc)
    if ([Math]::Abs(($recordedStart - $javaStart).TotalSeconds) -gt 1) { throw 'JVM StartTime receipt 不符' }
    # 以持有的 handle 判斷（同一個 PID 的程序物件），不以 PID 列舉：handle 還開著時，已結束的程序物件仍可能出現在列舉裡。
    if (-not $javaProcess.HasExited) { throw '前一 JVM 尚未停止' }
    $javaProcess.Dispose()
    $lockPath = Join-Path $Root 'world/session.lock'
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lock.Dispose()
    $classload = Join-Path $Root "classload-$javaId.log"
    if (Test-Path -LiteralPath $classload) { Copy-Item -LiteralPath $classload -Destination (Join-Path $phaseEvidence "classload-$javaId.log") }
    $latest = Join-Path $Root 'logs/latest.log'
    if (Test-Path -LiteralPath $latest) { Copy-Item -LiteralPath $latest -Destination (Join-Path $phaseEvidence 'latest.log') }
    Get-ChildItem -LiteralPath (Join-Path $Root 'world/data') -File | ForEach-Object { [ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'data-hashes.json') -Encoding UTF8
    $identity = [ordered]@{ phase=$Phase; ending=$Ending; window=$Window; nonce=$Nonce; startupNonce=$startupNonce; javaPid=$javaId; javaStartTimeUtc=$javaStart.ToString('o'); javaExitCode=$javaExit; gradleExitCode=$launcher.ExitCode; lockReleased=$true; root=$Root; receipt=$receiptPath; receiptSha256=(Get-FileHash -LiteralPath $receiptPath -Algorithm SHA256).Hash }
    $identity | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'launcher.json') -Encoding UTF8
    $taskResults.Add([pscustomobject]$identity)
    Write-Output "PASS $Phase ending=$Ending pid=$javaId javaExit=$javaExit root=$Root"
}

function New-OwnedRoot([string]$Suffix) {
    $nonce = "$taskRunId-$Suffix"
    $root = Join-Path $taskProject "run/m5-collapse-$nonce"
    if (Test-Path -LiteralPath $root) { throw "root 已存在：$root" }
    New-Item -ItemType Directory -Path $root | Out-Null
    'eula=true' | Set-Content -LiteralPath (Join-Path $root 'eula.txt') -Encoding ASCII
    @('online-mode=false', 'server-port=0', 'level-name=world', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2', 'sync-chunk-writes=true', 'level-seed=20260921', 'max-tick-time=120000') | Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ASCII
    return [pscustomobject]@{nonce=$nonce; root=(Resolve-Path -LiteralPath $root).Path}
}

foreach ($chain in $chains.Keys) {
    if ($Only.Count -gt 0 -and $chain -notin $Only) { continue }
    $owned = New-OwnedRoot $chain
    $chainStart = $taskResults.Count
    foreach ($entry in $chains[$chain]) { Invoke-OwnedPhase $owned.nonce $entry.phase $owned.root $entry.ending $entry.window }
    $chainResults = @($taskResults[$chainStart..($taskResults.Count-1)])
    $identities = @($chainResults | ForEach-Object { "$($_.javaPid)@$($_.javaStartTimeUtc)" } | Select-Object -Unique)
    $nonces = @($chainResults | Select-Object -ExpandProperty startupNonce -Unique)
    if ($identities.Count -ne $chainResults.Count -or $nonces.Count -ne $chainResults.Count) { throw "chain $chain 的每個 phase 必須是不同的 JVM（PID@StartTime 與 startupNonce）" }
}
$summaryPath = Join-Path $EvidenceRoot "collapse-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "collapse 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M5 collapse evidence: $summaryPath"
