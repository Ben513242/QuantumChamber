# m4-recovery-probe.ps1：M4 retained recovery 跨 JVM probe（runM4Recovery）。每個 chain／scenario 使用新的 owned root
# run/m4-recovery-<runId>-<suffix>，每個 phase 一個獨立 JVM；核對 PID／StartTime／startupNonce、正常停止與 session.lock 釋放。
# 未指定 -Only 時跑全部 24 phases；任何失敗都保留 root 與 phase evidence，禁止在同 root 重跑。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/run-m4-recovery-probe.ps1（SHA-256 00fca9fe186d5efac7d6df5d30dd7503c15d0d37f8a8b718977faccdb0bb0874）。
# 相對來源的改動：repo root、Gradle、init script、彙整 JSON 位置改為參數或本目錄。
# 參數：
#   -ChainOnly           只跑 chain（select／recover／verify／dormant-reentry）
#   -NativeFailureOnly   只跑 native-save chain
#   -Only                只跑指定 chain／scenario 名稱（RED 用）
#   -EvidenceRoot        彙整 recovery-run-<runId>.json 的位置；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle              gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
# phase receipt 位置由 testmod M4CandidateRecoveryProbe 固定為 <repo>/.superpowers/sdd/2026-09-21-m4-candidate-doors/recovery-<nonce>/<phase>，不受 -EvidenceRoot 影響。
param([switch]$ChainOnly, [switch]$NativeFailureOnly, [string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '')
# whole-branch fix1：-Only 可只跑指定 chain／scenario（RED 用）；未指定時跑全部 24 phases。
$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($marker in 'build.gradle', 'settings.gradle') { if (-not (Test-Path -LiteralPath (Join-Path $taskProject $marker) -PathType Leaf)) { throw "repo root 缺少 ${marker}：$taskProject" } }
if (-not $Gradle) { $dist = 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat'; $Gradle = if (Test-Path -LiteralPath $dist -PathType Leaf) { $dist } else { Join-Path $taskProject 'gradlew.bat' } }
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) { throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）" }
$taskGradle = $Gradle
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
$probeOwner = Resolve-Evidence (Join-Path $taskProject '.superpowers/sdd/2026-09-21-m4-candidate-doors')
$taskRunId = [guid]::NewGuid().ToString('N')
$taskResults = [System.Collections.Generic.List[object]]::new()

function Invoke-OwnedPhase([string]$Nonce, [string]$Phase, [string]$Root) {
    $phaseEvidence = Join-Path $probeOwner "recovery-$Nonce/$Phase"
    if (Test-Path -LiteralPath $phaseEvidence) { throw "phase evidence 已存在，禁止重跑：$phaseEvidence" }
    New-Item -ItemType Directory -Path $phaseEvidence | Out-Null
    $startupNonce = [guid]::NewGuid().ToString('N')
    $arguments = @('-I', (Join-Path $PSScriptRoot 'runtime.init.gradle'), 'runM4Recovery', "-Pm4RecoveryNonce=$Nonce", "-Pm4RecoveryPhase=$Phase", "-Pm4RecoveryStartupNonce=$startupNonce", "-Pm4RecoveryRoot=$Root", '--console=plain')
    $quotedArguments = $arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
    $command = "Set-Location -LiteralPath '" + $taskProject.Replace("'", "''") + "'; & '" + $taskGradle.Replace("'", "''") + "' " + ($quotedArguments -join ' ') + '; exit $LASTEXITCODE'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $launcher = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-EncodedCommand', $encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $phaseEvidence 'stdout.log') -RedirectStandardError (Join-Path $phaseEvidence 'stderr.log')
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(8)
    $javaId = $null
    $javaStart = $null
    while (-not $launcher.HasExited) {
        if ($null -eq $javaId) {
            $candidate = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*quantumchamber.m4recovery.startupNonce=$startupNonce*" }
            if (@($candidate).Count -gt 1) { throw '同一 startupNonce 出現多個 JVM' }
            if ($candidate) {
                $javaId = [int]$candidate.ProcessId
                $native = Get-Process -Id $javaId
                $javaStart = ([DateTimeOffset]$native.StartTime).ToUniversalTime()
                [ordered]@{pid=$javaId;startTime=([DateTimeOffset]$native.StartTime).ToString('o');commandLine=$candidate.CommandLine} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'process.json') -Encoding UTF8
            }
        }
        if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "owned phase 逾時；禁止同 root 重跑：$Root phase=$Phase" }
        Start-Sleep -Milliseconds 250
        $launcher.Refresh()
    }
    $launcher.WaitForExit()
    if ($launcher.ExitCode -ne 0) { throw "Gradle 非零退出：$($launcher.ExitCode)；保留 root：$Root" }
    $finalPath = Join-Path $phaseEvidence 'final.json'
    if (-not (Test-Path -LiteralPath $finalPath)) { throw "未產生正常 stop receipt：$phaseEvidence" }
    $receipt = Get-Content -LiteralPath $finalPath -Raw | ConvertFrom-Json -DateKind String
    if ($receipt.status -ne 'PASS' -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen -or -not $receipt.stopRequested) { throw "probe 未通過：$phaseEvidence" }
    if ($receipt.runtimeScope -ne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds) { throw 'recovery receipt 必須明確標示 testmod runtime，不能冒充 main-only' }
    if ($null -eq $javaId -or $javaId -ne $receipt.pid -or $receipt.startupNonce -ne $startupNonce) { throw 'JVM identity receipt 不符' }
    $recordedStart = [DateTimeOffset]::Parse($receipt.startTimeUtc)
    if ([Math]::Abs(($recordedStart - $javaStart).TotalSeconds) -gt 1) { throw 'JVM StartTime receipt 不符' }
    if (Get-Process -Id $javaId -ErrorAction SilentlyContinue) { throw '前一 JVM 尚未停止' }
    $lockPath = Join-Path $Root 'world/session.lock'
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lock.Dispose()
    Copy-Item -LiteralPath (Join-Path $Root "classload-$javaId.log") -Destination (Join-Path $phaseEvidence "classload-$javaId.log")
    Copy-Item -LiteralPath (Join-Path $Root 'logs/latest.log') -Destination (Join-Path $phaseEvidence 'latest.log')
    Get-ChildItem -LiteralPath (Join-Path $Root 'world/data') -File | ForEach-Object { [ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'data-hashes.json') -Encoding UTF8
    $identity = [ordered]@{ phase=$Phase; nonce=$Nonce; startupNonce=$startupNonce; javaPid=$javaId; javaStartTimeUtc=$javaStart.ToString('o'); gradleExitCode=$launcher.ExitCode; normalStop=$true; lockReleased=$true; root=$Root; receipt=$finalPath; receiptSha256=(Get-FileHash -LiteralPath $finalPath -Algorithm SHA256).Hash }
    $identity | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'launcher.json') -Encoding UTF8
    $taskResults.Add([pscustomobject]$identity)
    Write-Output "PASS $Phase pid=$javaId root=$Root"
}

function New-OwnedRoot([string]$Suffix) {
    $nonce = "$taskRunId-$Suffix"
    $root = Join-Path $taskProject "run/m4-recovery-$nonce"
    if (Test-Path -LiteralPath $root) { throw "root 已存在：$root" }
    New-Item -ItemType Directory -Path $root | Out-Null
    'eula=true' | Set-Content -LiteralPath (Join-Path $root 'eula.txt') -Encoding ASCII
    @('online-mode=false', 'server-port=0', 'level-name=world', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2', 'sync-chunk-writes=true', 'level-seed=20260921', 'max-tick-time=120000') | Set-Content -LiteralPath (Join-Path $root 'server.properties') -Encoding ASCII
    return [pscustomobject]@{nonce=$nonce; root=(Resolve-Path -LiteralPath $root).Path}
}

function Want([string]$Name) { return $Only.Count -eq 0 -or $Name -in $Only }
if (-not $NativeFailureOnly -and (Want 'chain')) {
$chain = New-OwnedRoot 'chain'
$chainStart = $taskResults.Count
# dormant-reentry：同一 root 的真實 DORMANT receipt 參與者啟動另一座 Chamber（W-I2）。
foreach ($phase in @('select','recover','verify','dormant-reentry')) { Invoke-OwnedPhase $chain.nonce $phase $chain.root }
$chainIds = @($taskResults[$chainStart..($taskResults.Count-1)] | Select-Object -ExpandProperty javaPid -Unique)
if ($chainIds.Count -ne 4) { throw '四階段必須使用四個不同 JVM PID' }
$second = Get-Content -LiteralPath $taskResults[$chainStart+1].receipt -Raw | ConvertFrom-Json -DateKind String
$third = Get-Content -LiteralPath $taskResults[$chainStart+2].receipt -Raw | ConvertFrom-Json -DateKind String
if ($second.finalJournalHash -ne $third.finalJournalHash) { throw '第三 JVM 改動 dormant journal bytes' }
}
if (-not $ChainOnly -and (Want 'native-save')) {
    $native = New-OwnedRoot 'native-save'
    foreach ($phase in @('select','native-write-fail','recover-after-write-fail','verify')) { Invoke-OwnedPhase $native.nonce $phase $native.root }
}
if (-not $ChainOnly -and -not $NativeFailureOnly) {
    foreach ($scenario in @('low','buff','disconnect','dirty-candidate','dirty-selection','entropy-missing','entropy-corrupt','discovery-corrupt','journal-corrupt')) {
        if (-not (Want $scenario)) { continue }
        $fault = New-OwnedRoot $scenario
        Invoke-OwnedPhase $fault.nonce $scenario $fault.root
    }
    # W-I1／spec m-3：selection checked 提交失敗後，同 process 其他 flush／原生 save／stop 與重啟都不得承認 SELECTED。
    foreach ($scenario in @('selection-flush-fault','readback-fault')) {
        if (-not (Want $scenario)) { continue }
        $fault = New-OwnedRoot $scenario
        foreach ($phase in @($scenario,'selection-fault-restart')) { Invoke-OwnedPhase $fault.nonce $phase $fault.root }
    }
    # readback 已寫入但未確認、還原後尚未寫出 RETURNING 即 crash：重啟依磁碟 MEASURED+SELECTED 走 retained recovery。
    if (Want 'readback-crash') {
        $fault = New-OwnedRoot 'readback-crash'
        foreach ($phase in @('readback-crash','recover','verify')) { Invoke-OwnedPhase $fault.nonce $phase $fault.root }
    }
}
$summaryPath = Join-Path $EvidenceRoot "recovery-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "recovery 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M4 recovery evidence: $summaryPath"
