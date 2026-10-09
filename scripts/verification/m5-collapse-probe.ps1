# m5-collapse-probe.ps1：M5 跨 JVM 的塌縮 crash／restart probe（runM5Collapse；spec §12 的 crash 窗口，M5 F6-1）。每條 chain 使用新的 owned root
# run/m5-collapse-<runId>-<chain>，每個 phase 一個獨立 JVM。chain 表的每個 phase 標記預期的結束：
#   stop：Gradle exit 0、JVM exit code 0、final.json status PASS（stoppingSeen／stoppedSeen／stopRequested），沒有 halt.json；
#   halt：Gradle 非 0、JVM exit code 77（以 JVM 的 process handle 讀取，另以 Gradle 輸出的 "finished with non-zero exit value 77" 交叉核對）、
#         halt.json status HALT 且窗口相符、乾淨（hit 1、failures 空、crashPointFailure none），沒有 final.json。
# 兩種結束都核對 PID／StartTime／startupNonce 與 receipt 相符、JVM 已不存在、world/session.lock 可獨占開啟；同一條 chain 的 PID@StartTime 與 startupNonce 都不同。
# 三份表的一致性（F6-2a）：receipt 的 ending 必須等於這裡 chain 表的預期結束，receipt 的 phaseTable（probe 的 phase 表）必須與 chain 表的全部 phase 依序相同。
# 任何失敗都 throw（訊息先附上 final.json／halt.json 的 status 與 failures），保留 root 與 phase evidence，禁止在同 root 重跑。
# 逾時（F6-2a）：找到 JVM 之後，latest.log 與 Gradle 輸出都超過 -IdleSeconds 秒沒有新內容，或整個 phase 超過 -PhaseMinutes 分鐘，就先以 jstack 取 thread dump
# （phase evidence 的 jstack-<pid>.txt），再只結束 commandline 含本次 startupNonce、StartTime 相符的那一個 java PID（不碰 Gradle daemon），寫 timeout.json
# （原因、PID、jstack 檔名、最後的 log 行），判 FAIL。
# 參數：
#   -Only          只跑指定 chain（例如 -Only w2,w3；以 pwsh -File 呼叫時也接受逗號分隔的單一字串）；未指定時跑全部
#   -EvidenceRoot  彙整 collapse-run-<runId>.json 的位置；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
#   -IdleSeconds   JVM 沒有任何新輸出多久判為卡住；預設 90（正常 phase 的 log 間隔最長約 6 秒）
#   -PhaseMinutes  一個 phase 的總上限；預設 8
# phase receipt 位置由 testmod M5CollapseRecoveryProbe 固定為 <repo>/.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>，不受 -EvidenceRoot 影響。
# 新增 phase：testmod 的 M5CollapseRecoveryProbe.phases() 加 entry，這裡的 $chains 加同名的 phase 與預期的結束（README「M5 collapse probe」）。
# 結構比照 m4-recovery-probe.ps1（owned root、phase evidence、launcher.json、彙整 JSON）。
param([string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '', [int]$IdleSeconds = 90, [int]$PhaseMinutes = 8)
$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($marker in 'build.gradle', 'settings.gradle') { if (-not (Test-Path -LiteralPath (Join-Path $taskProject $marker) -PathType Leaf)) { throw "repo root 缺少 ${marker}：$taskProject" } }
if (-not $Gradle) { $dist = 'C:/Users/Ben/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat'; $Gradle = if (Test-Path -LiteralPath $dist -PathType Leaf) { $dist } else { Join-Path $taskProject 'gradlew.bat' } }
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) { throw "找不到 Gradle：$Gradle（以 -Gradle 指定 gradle.bat 或 gradlew.bat）" }
$taskGradle = $Gradle

# chain 表：chain 名稱 → 依序執行的 phase（名稱必須與 M5CollapseRecoveryProbe.phases() 相同）、預期的結束與 halt 的窗口。
$chains = [ordered]@{
    # W1：live session 選門之後，MEASURED→COLLAPSING 提交之後、返還之前（重啟時走廊 space 重建、不經請求就返還）。
    'w1' = @(@{phase='w1-crash'; ending='halt'; window='COLLAPSING_COMMITTED'}, @{phase='w1-restart'; ending='stop'; window=''})
    # W2：allocation ledger flush 之後、catalog flush 之前。
    'w2' = @(@{phase='w2-crash'; ending='halt'; window='ALLOCATION_APPENDED'}, @{phase='w2-restart'; ending='stop'; window=''})
    # W3：catalog flush 之後、物化之前（重啟時由 EAGER_ENABLED 物化）。
    'w3' = @(@{phase='w3-crash'; ending='halt'; window='CATALOG_ALLOCATED'}, @{phase='w3-restart'; ending='stop'; window=''})
    # W5：discovery 追加之後、投影之前。
    'w5' = @(@{phase='w5-crash'; ending='halt'; window='DISCOVERY_APPENDED'}, @{phase='w5-restart'; ending='stop'; window=''})
    # W4：投影寫到 Controller 那一格（halt 前刻意存檔：磁碟上是寫到一半的投影）。
    'w4' = @(@{phase='w4-crash'; ending='halt'; window='PROJECTION_CELL_WRITTEN'}, @{phase='w4-restart'; ending='stop'; window=''})
    # W4b：投影寫完、目的 chunks 的 checkpoint 之前（ledger PENDING、磁碟上沒有投影）。
    'w4b' = @(@{phase='w4b-crash'; ending='halt'; window='DESTINATION_CHECKPOINT'}, @{phase='w4b-restart'; ending='stop'; window=''})
    # W6：projection ledger 寫入 COMPLETE 之後、PASSAGE_READY 之前。
    'w6' = @(@{phase='w6-crash'; ending='halt'; window='PROJECTION_COMPLETE'}, @{phase='w6-restart'; ending='stop'; window=''})
    # W6 的 EXISTING 再造訪：REPAIR 寫了格之後、checkpoint 之前。
    'w6x' = @(@{phase='w6x-crash'; ending='halt'; window='PROJECTION_REPAIRED'}, @{phase='w6x-restart'; ending='stop'; window=''})
    # 開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本，不是 crash 窗口）：setup 提交 COLLAPSING 的同一個 tick 正常停服，restart 開服前移走 ledger。
    'degraded' = @(@{phase='deg-setup'; ending='stop'; window=''}, @{phase='deg-restart'; ending='stop'; window=''})
    # H5（不是 crash 窗口）：chunk 卸載的 livelock 防護。takeback：被取回的 holder 照常卸載、存檔；shutdown：raw MCA 讀回修改，殭屍＋停服時仍在載入，必須正常停服。
    'unload' = @(@{phase='unload-takeback'; ending='stop'; window=''}, @{phase='unload-shutdown'; ending='stop'; window=''})
}
# chain 表的全部 phase（依 chain 的順序）：與每個 receipt 的 phaseTable（probe 的 phase 表）逐一比對，任何一邊多出或少了 phase 都判 FAIL。
$tablePhases = @($chains.Values | ForEach-Object { $_ } | ForEach-Object { $_.phase })
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

# phase evidence 裡 receipt 的摘要（status 與 failures；halt.json 另有 hit、crashPointFailure、refused），附在失敗訊息上：不論哪一項檢查失敗，都先看得到 probe 判定的原因。
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

# 以共用讀取開啟（log4j 與 Gradle 仍開著寫入）讀檔案長度；不存在或讀不到時回 -1。長度以 handle 讀，不用目錄項（寫入中的檔案，目錄項的大小可能過期）。
function Get-SharedLength([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return -1 }
    try { $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete) } catch { return -1 }
    try { return $stream.Length } finally { $stream.Dispose() }
}

# 檔案最後 $Count 個非空行（共用讀取）；不存在時回空陣列。
function Get-SharedTail([string]$Path, [int]$Count) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        try { $text = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8).ReadToEnd() } finally { $stream.Dispose() }
        return @($text -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -Last $Count)
    } catch { return @("（讀不出來：$_）") }
}

# phase 逾時的處置：核對 PID 仍是本次的 JVM（commandline 含 startupNonce、StartTime 相符）之後先 jstack，再只結束那一個 java PID；Gradle（daemon 與 client）不結束，
# JVM 結束之後由 Gradle 自行收尾。寫 timeout.json 之後 throw（判 FAIL）。JVM 還沒有找到時（例如 Gradle 還在編譯）只記錄、不結束任何程序。
function Stop-TimedOutPhase([string]$Reason, [string]$Phase, [string]$PhaseEvidence, [string]$Root, [string]$StartupNonce, $JavaProcess, $JavaId, $JavaStart, $Launcher,
        [DateTimeOffset]$Started, $LastActivity) {
    $now = [DateTimeOffset]::UtcNow
    $record = [ordered]@{ phase=$Phase; reason=$Reason; idleLimitSeconds=$IdleSeconds; phaseLimitMinutes=$PhaseMinutes; timeUtc=$now.ToString('o')
        elapsedSeconds=[Math]::Round(($now - $Started).TotalSeconds, 1); idleSeconds=$(if ($null -ne $LastActivity) { [Math]::Round(($now - $LastActivity).TotalSeconds, 1) } else { $null })
        root=$Root; startupNonce=$StartupNonce; pid=$JavaId; javaStartTimeUtc=$(if ($null -ne $JavaStart) { $JavaStart.ToString('o') } else { $null })
        commandLineMatched=$false; jstack=$null; jstackExitCode=$null; killed=$false; javaExited=$null; javaExitCode=$null; launcherExited=$null; launcherExitCode=$null
        lastLogLines=@(); lastGradleLines=@() }
    if ($null -ne $JavaId -and -not $JavaProcess.HasExited) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$JavaId"
        $sameStart = ([DateTimeOffset]$JavaProcess.StartTime).ToUniversalTime() -eq $JavaStart
        $matched = $null -ne $process -and $process.Name -eq 'java.exe' -and $process.CommandLine -like "*quantumchamber.m5collapse.startupNonce=$StartupNonce*" -and $sameStart
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
    $started = [DateTimeOffset]::UtcNow
    $deadline = $started.AddMinutes($PhaseMinutes)
    $lastActivity = $null
    $lastSizes = ''
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
        $now = [DateTimeOffset]::UtcNow
        if ($null -ne $javaId) {
            # 有新內容就是還在動：latest.log（server 的 log）與 Gradle 的輸出（JVM 的 stdout 經 Gradle 轉出）任一變長。
            $sizes = "$(Get-SharedLength (Join-Path $Root 'logs/latest.log'))/$(Get-SharedLength $stdout)/$(Get-SharedLength $stderr)"
            if ($null -eq $lastActivity -or $sizes -ne $lastSizes) { $lastActivity = $now; $lastSizes = $sizes }
            if (($now - $lastActivity).TotalSeconds -gt $IdleSeconds) {
                Stop-TimedOutPhase "idle：$IdleSeconds 秒沒有新的 log" $Phase $phaseEvidence $Root $startupNonce $javaProcess $javaId $javaStart $launcher $started $lastActivity
            }
        }
        if ($now -gt $deadline) { Stop-TimedOutPhase "total：超過 $PhaseMinutes 分鐘" $Phase $phaseEvidence $Root $startupNonce $javaProcess $javaId $javaStart $launcher $started $lastActivity }
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
    $note = Get-ReceiptNote $phaseEvidence
    if ($Ending -eq 'stop') {
        if ($launcher.ExitCode -ne 0) { throw "Gradle 非零退出：$($launcher.ExitCode)（$note）；保留 root：$Root" }
        if ($javaExit -ne 0) { throw "JVM exit code $javaExit（預期正常停止的 0；$note）；保留 root：$Root" }
        if (Test-Path -LiteralPath $haltPath) { throw "預期正常停止的 phase 留下 halt receipt（$note）：$haltPath" }
        if (-not (Test-Path -LiteralPath $finalPath)) { throw "未產生正常 stop receipt：$phaseEvidence" }
        $receiptPath = $finalPath
        $receipt = Get-Content -LiteralPath $finalPath -Raw | ConvertFrom-Json -DateKind String
        if ($receipt.status -ne 'PASS' -or -not $receipt.stoppingSeen -or -not $receipt.stoppedSeen -or -not $receipt.stopRequested) { throw "probe 未通過：$phaseEvidence failures=$($receipt.failures -join ' | ')" }
    } elseif ($Ending -eq 'halt') {
        # 先看 final.json：halt phase 最常見的失敗是窗口沒有到達或斷言失敗，probe 照常停止並寫 FAIL 的 final.json（這時 Gradle 是 exit 0）。
        if (Test-Path -LiteralPath $finalPath) { throw "預期 halt 的 phase 正常停止了（$note）：$phaseEvidence" }
        if ($launcher.ExitCode -eq 0) { throw "預期 halt 的 phase 以 Gradle exit 0 結束（$note）；保留 root：$Root" }
        if ($javaExit -ne 77) { throw "JVM exit code $javaExit（預期 halt 的 77；$note）；保留 root：$Root" }
        if ($gradleOutput -notmatch 'finished with non-zero exit value 77') { throw "Gradle 輸出沒有 'finished with non-zero exit value 77'：$phaseEvidence" }
        if (-not (Test-Path -LiteralPath $haltPath)) { throw "未產生 halt receipt：$phaseEvidence" }
        $receiptPath = $haltPath
        $receipt = Get-Content -LiteralPath $haltPath -Raw | ConvertFrom-Json -DateKind String
        if ($receipt.status -ne 'HALT' -or $receipt.window -ne $Window -or $receipt.phase -ne $Phase) { throw "halt receipt 不符：status=$($receipt.status) window=$($receipt.window)（預期 $Window）" }
        # 乾淨的 halt（F6-2a）：crash 點的第一次 hit、crash 點沒有記下失敗、failures 是空的（probe 端也守門：不乾淨時不以 77 結束）。
        if ($receipt.hit -ne 1 -or @($receipt.failures).Count -ne 0 -or $receipt.crashPointFailure -ne 'none') { throw "halt 不乾淨：hit=$($receipt.hit) failures=$(@($receipt.failures) -join ' | ') crashPointFailure=$($receipt.crashPointFailure)：$phaseEvidence" }
    } else { throw "未知的結束方式：$Ending" }
    # 三份表的一致性：receipt 的 ending 是 probe 的 phase 宣告的結束；phaseTable 是 probe 的整張 phase 表。
    if ($receipt.ending -cne $Ending.ToUpperInvariant()) { throw "receipt 的 ending=$($receipt.ending) 與 chain 表的 $Ending 不同：$receiptPath" }
    $receiptTable = @($receipt.phaseTable)
    if (($receiptTable -join ',') -cne ($tablePhases -join ',')) {
        $onlyJava = @($receiptTable | Where-Object { $_ -cnotin $tablePhases })
        $onlyTable = @($tablePhases | Where-Object { $_ -cnotin $receiptTable })
        throw "probe 的 phase 表與 chain 表不一致（順序也必須相同）：只在 probe=[$($onlyJava -join ',')] 只在 chain 表=[$($onlyTable -join ',')]：$receiptPath"
    }
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
