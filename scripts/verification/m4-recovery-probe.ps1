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
#   -IdleSeconds        JVM 無新 log 的上限；預設 90，與 M5 共用同一個 idle 契約
#   -PhaseMinutes       phase 總上限；預設 8，保留原本的 8 分鐘
#   -GradleCodePage     launcher stdout/stderr 的明確編碼；預設目前文化的 OEM code page（zh-TW 為 950）
# phase receipt 位置由 testmod M4CandidateRecoveryProbe 固定為 <repo>/.superpowers/sdd/2026-09-21-m4-candidate-doors/recovery-<nonce>/<phase>，不受 -EvidenceRoot 影響。
param([switch]$ChainOnly, [switch]$NativeFailureOnly, [string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '', [int]$IdleSeconds = 90, [int]$PhaseMinutes = 8, [int]$GradleCodePage = [Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
# whole-branch fix1：-Only 可只跑指定 chain／scenario（RED 用）；未指定時跑全部 24 phases。
$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($marker in 'build.gradle', 'settings.gradle') { if (-not (Test-Path -LiteralPath (Join-Path $taskProject $marker) -PathType Leaf)) { throw "repo root 缺少 ${marker}：$taskProject" } }
if (-not $Gradle) { $dist = 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat'; $Gradle = if (Test-Path -LiteralPath $dist -PathType Leaf) { $dist } else { Join-Path $taskProject 'gradlew.bat' } }
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) { throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）" }
$taskGradle = $Gradle
# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$probeContext = [pscustomobject]@{Project=$taskProject; Gradle=$taskGradle; InitScript=(Join-Path $PSScriptRoot 'runtime.init.gradle'); RunId=''; RootPrefix='m4-recovery'; EvidencePrefix='recovery'; EvidenceOwner=''; Task='runM4Recovery'; PropertyPrefix='m4Recovery'; NonceProperty='quantumchamber.m4recovery.startupNonce'; IdleSeconds=$IdleSeconds; PhaseMinutes=$PhaseMinutes; GradleEncoding=[Text.Encoding]::GetEncoding($GradleCodePage); LogEncoding=[Text.Encoding]::UTF8}
if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $taskProject '.superpowers/verification' }
$EvidenceRoot = Resolve-Evidence -Context $probeContext -Path $EvidenceRoot
$probeOwner = Resolve-Evidence -Context $probeContext -Path (Join-Path $taskProject '.superpowers/sdd/2026-09-21-m4-candidate-doors')
$taskRunId = [guid]::NewGuid().ToString('N')
$probeContext.RunId=$taskRunId; $probeContext.EvidenceOwner=$probeOwner
$taskResults = [System.Collections.Generic.List[object]]::new()

function Invoke-M4Phase([string]$Nonce, [string]$Phase, [string]$Root) {
    $state=Invoke-OwnedPhase -Context $probeContext -Nonce $Nonce -Phase $Phase -Root $Root
    $phaseEvidence=$state.PhaseEvidence; $startupNonce=$state.StartupNonce; $launcher=$state.Launcher
    $javaProcess=$state.JavaProcess; $javaId=$state.JavaId; $javaStart=$state.JavaStart
    $note = Get-PhaseFailureNote -Context $probeContext -State $state
    if ($launcher.ExitCode -ne 0) { throw "Gradle 非零退出：$($launcher.ExitCode)（$note）；保留 root：$Root" }
    $finalPath = Join-Path $phaseEvidence 'final.json'
    if (-not (Test-Path -LiteralPath $finalPath)) { throw "未產生正常 stop receipt：$phaseEvidence" }
    $receipt = Get-Content -LiteralPath $finalPath -Raw | ConvertFrom-Json -DateKind String
    if ($receipt.status -ne 'PASS' -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen -or -not $receipt.stopRequested) { throw "probe 未通過：$phaseEvidence（$note）" }
    if ($receipt.runtimeScope -ne 'testmod-present' -or 'quantumchamber-testmod' -notin $receipt.loadedModIds) { throw 'recovery receipt 必須明確標示 testmod runtime，不能冒充 main-only' }
    if ($null -eq $javaId -or $javaId -ne $receipt.pid -or $receipt.startupNonce -ne $startupNonce) { throw 'JVM identity receipt 不符' }
    $recordedStart = [DateTimeOffset]::Parse($receipt.startTimeUtc)
    if ([Math]::Abs(($recordedStart - $javaStart).TotalSeconds) -gt 1) { throw 'JVM StartTime receipt 不符' }
    if (-not $javaProcess.HasExited) { throw '前一 JVM 尚未停止' }
    $javaProcess.Dispose()
    $lockPath = Join-Path $Root 'world/session.lock'
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lock.Dispose()
    $auxiliaryLogs = Copy-OwnedLogs -State $state
    Get-ChildItem -LiteralPath (Join-Path $Root 'world/data') -File | ForEach-Object { [ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'data-hashes.json') -Encoding UTF8
    $identity = [ordered]@{ phase=$Phase; nonce=$Nonce; startupNonce=$startupNonce; javaPid=$javaId; javaStartTimeUtc=$javaStart.ToString('o'); gradleExitCode=$launcher.ExitCode; normalStop=$true; lockReleased=$true; root=$Root; receipt=$finalPath; receiptSha256=(Get-FileHash -LiteralPath $finalPath -Algorithm SHA256).Hash }
    $identity.auxiliaryLogs = $auxiliaryLogs
    $identity.logEncodings = [ordered]@{latestLog=$probeContext.LogEncoding.WebName; gradle=$probeContext.GradleEncoding.WebName; gradleCodePage=$probeContext.GradleEncoding.CodePage}
    $identity | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'launcher.json') -Encoding UTF8
    $taskResults.Add([pscustomobject]$identity)
    Write-Output "PASS $Phase pid=$javaId root=$Root"
}


function Want([string]$Name) { return $Only.Count -eq 0 -or $Name -in $Only }
if (-not $NativeFailureOnly -and (Want 'chain')) {
$chain = New-OwnedRoot -Context $probeContext -Suffix 'chain'
$chainStart = $taskResults.Count
# dormant-reentry：同一 root 的真實 DORMANT receipt 參與者啟動另一座 Chamber（W-I2）。
foreach ($phase in @('select','recover','verify','dormant-reentry')) { Invoke-M4Phase $chain.nonce $phase $chain.root }
$chainIds = @($taskResults[$chainStart..($taskResults.Count-1)] | Select-Object -ExpandProperty javaPid -Unique)
if ($chainIds.Count -ne 4) { throw '四階段必須使用四個不同 JVM PID' }
$second = Get-Content -LiteralPath $taskResults[$chainStart+1].receipt -Raw | ConvertFrom-Json -DateKind String
$third = Get-Content -LiteralPath $taskResults[$chainStart+2].receipt -Raw | ConvertFrom-Json -DateKind String
if ($second.finalJournalHash -ne $third.finalJournalHash) { throw '第三 JVM 改動 dormant journal bytes' }
}
if (-not $ChainOnly -and (Want 'native-save')) {
    $native = New-OwnedRoot -Context $probeContext -Suffix 'native-save'
    foreach ($phase in @('select','native-write-fail','recover-after-write-fail','verify')) { Invoke-M4Phase $native.nonce $phase $native.root }
}
if (-not $ChainOnly -and -not $NativeFailureOnly) {
    foreach ($scenario in @('low','buff','disconnect','dirty-candidate','dirty-selection','entropy-missing','entropy-corrupt','discovery-corrupt','journal-corrupt')) {
        if (-not (Want $scenario)) { continue }
        $fault = New-OwnedRoot -Context $probeContext -Suffix $scenario
        Invoke-M4Phase $fault.nonce $scenario $fault.root
    }
    # W-I1／spec m-3：selection checked 提交失敗後，同 process 其他 flush／原生 save／stop 與重啟都不得承認 SELECTED。
    foreach ($scenario in @('selection-flush-fault','readback-fault')) {
        if (-not (Want $scenario)) { continue }
        $fault = New-OwnedRoot -Context $probeContext -Suffix $scenario
        foreach ($phase in @($scenario,'selection-fault-restart')) { Invoke-M4Phase $fault.nonce $phase $fault.root }
    }
    # readback 已寫入但未確認、還原後尚未寫出 RETURNING 即 crash：重啟依磁碟 MEASURED+SELECTED 走 retained recovery。
    if (Want 'readback-crash') {
        $fault = New-OwnedRoot -Context $probeContext -Suffix 'readback-crash'
        foreach ($phase in @('readback-crash','recover','verify')) { Invoke-M4Phase $fault.nonce $phase $fault.root }
    }
}
$summaryPath = Join-Path $EvidenceRoot "recovery-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "recovery 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M4 recovery evidence: $summaryPath"
