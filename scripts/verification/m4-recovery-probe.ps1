# m4-recovery-probe.ps1：M4 retained recovery 跨 JVM probe（runM4Recovery）。每個 chain／scenario 使用新的 owned root
# run/m4-recovery-<runId>-<suffix>，每個 phase 一個獨立 JVM；核對 PID／StartTime／startupNonce、正常停止與 session.lock 釋放。
# 未指定 -Only 時跑全部 25 phases；任何失敗都保留 root 與 phase evidence，禁止在同 root 重跑。
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
# whole-branch fix1：-Only 可只跑指定 chain／scenario（RED 用）；未指定時跑全部 25 phases。
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
    if($Phase -ceq 'readback-fault'){Complete-M4Pending -State $state -Nonce $Nonce;return}
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


# 唯一預期停寫 phase：不產生／接受正常 final.json。
function Complete-M4Pending($State,[string]$Nonce) {
    $e=$State.PhaseEvidence;$root=$State.Root;$java=$State.JavaProcess
    if($State.Launcher.ExitCode -notin @(0,1) -or $java.ExitCode -notin @(0,1) -or -not $java.HasExited){throw 'M4_PENDING_EXIT：非預期退出'}
    if(Test-Path -LiteralPath (Join-Path $e 'final.json')){throw 'M4_PENDING_FINAL：不得冒正常final'}
    $prePath=Join-Path $e 'pending-prestop.json';$pre=Get-Content -LiteralPath $prePath -Raw|ConvertFrom-Json -DateKind String
    if($pre.status -cne 'PENDING_PRESTOP' -or $pre.phase -cne 'readback-fault' -or $pre.nonce -cne $Nonce -or $pre.root -cne $root -or $pre.pid -ne $State.JavaId -or $pre.startupNonce -cne $State.StartupNonce -or [Math]::Abs(([DateTimeOffset]$pre.startTimeUtc-$State.JavaStart).TotalSeconds) -gt 1){throw 'M4_PENDING_IDENTITY：prestop來源不符'}
    if($pre.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -cnotin $pre.loadedModIds -or @($pre.failures).Count -ne 0 -or $pre.faultCount -ne 1 -or -not $pre.stopRequested -or -not $pre.noUniverseAllocation -or -not $pre.flushedUnchanged -or -not $pre.currentNotSelected){throw 'M4_PENDING_PRESTOP：權威或fault不符'}
    $events=@(Get-Content -LiteralPath (Join-Path $e 'events.jsonl') | ForEach-Object {$_|ConvertFrom-Json})
    if(($events.event -join ',') -cne 'initialized,pending-prestop' -or @($events|Where-Object {$_.pid -ne $State.JavaId -or $_.startupNonce -cne $State.StartupNonce}).Count){throw 'M4_PENDING_EVENTS：非預期生命週期'}
    $aux=Copy-OwnedLogs -State $State
    $logPath=Join-Path $e 'latest.log';$log=Get-Content -LiteralPath $logPath -Raw -Encoding utf8
    $exact=[regex]::Match($log,'(?s)java.lang.IllegalStateException: 候選選擇 checked 提交失敗.*?(?=\r?\n\[)')
    $stop=[regex]::Match($log,'(?s)Exception stopping the server\r?\n(?<stack>.*)$')
    if(-not $exact.Success -or $exact.Value -notmatch 'CandidateDoorInteraction.failSession' -or $exact.Value -notmatch 'JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED' -or -not $stop.Success -or $stop.Value -notmatch 'JOURNAL_PENDING_RESTART_REQUIRED' -or $stop.Value -notmatch 'MinecraftServer.shutdown' -or $stop.Value -match 'JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED'){throw 'M4_PENDING_STACK：首次exact或stop拒寫不符'}
    if([regex]::Matches($log,'JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED').Count -ne 1 -or [regex]::Matches($log,'Exception stopping the server').Count -ne 1 -or $log -match 'M4 recovery probe 失敗'){throw 'M4_PENDING_STACK：額外非預期fault'}
    $errors=@([regex]::Matches($log,'(?m)^.*?/ERROR\].*$')|ForEach-Object Value)
    if($errors.Count -ne 2 -or @($errors|Where-Object {$_ -notmatch '走廊保留租約等待安全返還：|Exception stopping the server'}).Count){throw 'M4_PENDING_STACK：存在額外ERROR'}
    [IO.File]::WriteAllText((Join-Path $e 'pending-exact-stack.txt'),$exact.Value,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $e 'pending-stop-stack.txt'),$stop.Value,[Text.UTF8Encoding]::new($false))
    $files=@('pending-prestop.json','pending-injected-fault.txt','pending-native-refusal.txt','pending-other-refusal.txt','pending-exact-stack.txt','pending-stop-stack.txt','latest.log','events.jsonl','process.json')
    $hashes=@();foreach($name in $files){$path=Join-Path $e $name;$hashes+=[pscustomobject]@{path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash}}
    foreach($label in @('native','other')){
        $path=Join-Path $e "pending-$label-refusal.txt";$text=Get-Content -LiteralPath $path -Raw
        if($text -notmatch 'JOURNAL_PENDING_RESTART_REQUIRED' -or $text -match 'JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED' -or (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $pre."${label}RefusalSha256" -or $pre."${label}JournalHash" -cne $pre.journalHash){throw "M4_PENDING_STACK：$label 具名拒寫缺失"}
    }
    if((Get-Content -LiteralPath (Join-Path $e 'pending-native-refusal.txt') -Raw) -notmatch 'MinecraftServer.save' -or (Get-Content -LiteralPath (Join-Path $e 'pending-other-refusal.txt') -Raw) -notmatch 'SessionRecoveryState.flush'){throw 'M4_PENDING_STACK：native/other呼叫來源不符'}
    if((Get-Content -LiteralPath (Join-Path $e 'pending-injected-fault.txt') -Raw) -notmatch 'beforeJournalReadback'){throw 'M4_PENDING_FAULT：缺正式callback stack'}
    $journal=Join-Path $root 'world/data/quantumchamber_sessions.dat'
    if((Get-FileHash -LiteralPath $journal).Hash.ToLowerInvariant() -cne $pre.journalHash){throw 'M4_PENDING_DISK：stop覆寫exact attempt'}
    Copy-Item -LiteralPath $journal -Destination (Join-Path $e 'pending-journal.dat')
    if((Get-FileHash -LiteralPath (Join-Path $e 'pending-journal.dat')).Hash.ToLowerInvariant() -cne $pre.journalHash){throw 'M4_PENDING_DISK：snapshot複製不符'}
    $hashes+=[pscustomobject]@{path=(Join-Path $e 'pending-journal.dat');sha256=(Get-FileHash -LiteralPath (Join-Path $e 'pending-journal.dat')).Hash}
    $lock=[IO.File]::Open((Join-Path $root 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$lock.Dispose()
    if(@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -like "*$($State.StartupNonce)*"}).Count){throw 'M4_PENDING_OWNER：Java仍存活'}
    $result=[ordered]@{status='EXPECTED_PENDING_RESTART_REQUIRED';phase='readback-fault';nonce=$Nonce;startupNonce=$State.StartupNonce;pid=$State.JavaId;startTimeUtc=$State.JavaStart.ToString('o');runtimeScope='testmod-present';gradleExitCode=$State.Launcher.ExitCode;javaExitCode=$java.ExitCode;normalStop=$false;ownerAbsent=$true;lockReleased=$true;journalHash=$pre.journalHash;evidence=$hashes}
    $path=Join-Path $e 'pending-oracle.json';$result|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $path -Encoding utf8
    $identity=[ordered]@{phase='readback-fault';nonce=$Nonce;startupNonce=$State.StartupNonce;javaPid=$State.JavaId;javaStartTimeUtc=$State.JavaStart.ToString('o');gradleExitCode=$State.Launcher.ExitCode;javaExitCode=$java.ExitCode;normalStop=$false;lockReleased=$true;root=$root;receipt=$path;receiptSha256=(Get-FileHash -LiteralPath $path).Hash;auxiliaryLogs=$aux}
    $identity|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $e 'launcher.json') -Encoding utf8
    $taskResults.Add([pscustomobject]$identity);$java.Dispose();Write-Output "EXPECTED_PENDING_RESTART_REQUIRED readback-fault root=$root"
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
    foreach ($scenario in @('selection-flush-fault')) {
        if (-not (Want $scenario)) { continue }
        $fault = New-OwnedRoot -Context $probeContext -Suffix $scenario
        foreach ($phase in @($scenario,'selection-fault-restart')) { Invoke-M4Phase $fault.nonce $phase $fault.root }
    }
    if(Want 'readback-fault'){
        $fault=New-OwnedRoot -Context $probeContext -Suffix 'readback-fault'
        foreach($phase in @('readback-fault','recover','verify')){Invoke-M4Phase $fault.nonce $phase $fault.root}
    }
    # readback 已寫入但未確認、還原後尚未寫出 RETURNING 即 crash：重啟依磁碟 MEASURED+SELECTED 走 retained recovery。
    if (Want 'readback-crash') {
        $fault = New-OwnedRoot -Context $probeContext -Suffix 'readback-crash'
        foreach ($phase in @('readback-crash','recover','verify')) { Invoke-M4Phase $fault.nonce $phase $fault.root }
    }
}
if($Only.Count -eq 0 -and -not $ChainOnly -and -not $NativeFailureOnly) {
    $expected=@('chain:select','chain:recover','chain:verify','chain:dormant-reentry','native-save:select','native-save:native-write-fail','native-save:recover-after-write-fail','native-save:verify',
        'low:low','buff:buff','disconnect:disconnect','dirty-candidate:dirty-candidate','dirty-selection:dirty-selection','entropy-missing:entropy-missing','entropy-corrupt:entropy-corrupt','discovery-corrupt:discovery-corrupt','journal-corrupt:journal-corrupt',
        'selection-flush-fault:selection-flush-fault','selection-flush-fault:selection-fault-restart','readback-fault:readback-fault','readback-fault:recover','readback-fault:verify','readback-crash:readback-crash','readback-crash:recover','readback-crash:verify')
    $observed=@($taskResults|ForEach-Object {($_.nonce -replace '^[0-9a-f]{32}-','')+':'+$_.phase})
    if(($observed -join ',') -cne ($expected -join ',') -or $taskResults.Count -ne 25){throw 'M4_RECOVERY_SCOPE：全25phase順序不符'}
}
if(@($taskResults|ForEach-Object {"$($_.javaPid)@$($_.javaStartTimeUtc)"}|Sort-Object -Unique).Count -ne $taskResults.Count -or @($taskResults|ForEach-Object startupNonce|Sort-Object -Unique).Count -ne $taskResults.Count){throw 'M4_RECOVERY_IDENTITY：每phase必須獨立JVM/nonce'}
foreach($phase in $taskResults){if((Get-FileHash -LiteralPath $phase.receipt).Hash -cne $phase.receiptSha256){throw 'M4_RECOVERY_HASH：receipt已變更'}}
$summaryPath = Join-Path $EvidenceRoot "recovery-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "recovery 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M4 recovery evidence: $summaryPath"
