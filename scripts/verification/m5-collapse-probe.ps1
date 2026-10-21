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
param([string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '', [int]$IdleSeconds = 90, [int]$PhaseMinutes = 8, [int]$GradleCodePage = [Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
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
    # F6-2c：通道與終局的重啟窗口。W7：PASSAGE_READY 期間兩人中選門者已跨過（Crossed 已確認，halt 前存檔）；重啟之後通道在開服時結束，同一個 server 內原艙再開新 session。
    'w7' = @(@{phase='w7-crash'; ending='halt'; window='CROSSED_COMMITTED'}, @{phase='w7-restart'; ending='stop'; window=''})
    # F6-2-16：PASSAGE_READY 提交之後、門改為 passable 之前（halt 前存檔：sealed 的門與艙內的參與者在磁碟上）。
    'readycommit' = @(@{phase='readycommit-crash'; ending='halt'; window='READY_COMMITTED'}, @{phase='readycommit-restart'; ending='stop'; window=''})
    # F6-2-13：通道的終局之後、改門之前（磁碟上的門帶著量子膜，重啟之後由 load-sync 的刷新排入正規化）。
    'terminaldoor' = @(@{phase='terminaldoor-crash'; ending='halt'; window='PASSAGE_TERMINATED'}, @{phase='terminaldoor-restart'; ending='stop'; window=''})
    # F6-2-17：玩家已在目的世界、Crossed 的寫出沒有確認。
    'crossedpending' = @(@{phase='crossedpending-crash'; ending='halt'; window='CROSSED_UNCONFIRMED'}, @{phase='crossedpending-restart'; ending='stop'; window=''})
    # F6-2-12：SEALED(TRANSFER_RECOVERY_REQUIRED) 跨重啟；以及 TRR 的寫入被拒（pendingRecovery）期間重啟。
    'trr' = @(@{phase='trr-crash'; ending='halt'; window='RECOVERY_SEALED'}, @{phase='trr-restart'; ending='stop'; window=''})
    'trrpending' = @(@{phase='trrpending-crash'; ending='halt'; window='RECOVERY_REJECTED'}, @{phase='trrpending-restart'; ending='stop'; window=''})
    # H6（不是 crash 窗口）：結構範本快取的同時使用。不經塌縮：物化一個 U，兩個世界在同一個呼叫裡生成同一批 jigsaw 結構的 start，必須都完成、正常停服。
    'h6race' = @(@{phase='h6race-starts'; ending='stop'; window=''})
    # F6-2d-1：重試與續行跨重啟。W8：參與者在艙內、DORMANT 的收據沒有觸發時 crash；重啟之後開服的第一次觀察就提交，續行到 READY。
    'w8' = @(@{phase='w8-crash'; ending='halt'; window='DORMANT_STEP'}, @{phase='w8-restart'; ending='stop'; window=''})
    # W9：物化失敗 → DISABLED、SEALED(MATERIALIZE_FAILED) 已確認時 crash；重啟之後開服不停止、重試成功。
    'w9' = @(@{phase='w9-crash'; ending='halt'; window='MATERIALIZE_SEALED'}, @{phase='w9-restart'; ending='stop'; window=''})
    # W9 (a)：同 W9 的 crash；重啟之後的重試先因另一個 session 的 allocation 讀回失敗封存為 ALLOCATION_IO_FAILED，退避之後的重試仍改回 EAGER_ENABLED。
    'w9io' = @(@{phase='w9io-crash'; ending='halt'; window='MATERIALIZE_SEALED'}, @{phase='w9io-restart'; ending='stop'; window=''})
    # W9 (b)：§8.3 的 DISABLED 寫入之後、SEALED 之前 crash（開服時 COLLAPSING＋DISABLED）。
    'w9cd' = @(@{phase='w9cd-crash'; ending='halt'; window='CATALOG_DISABLED'}, @{phase='w9cd-restart'; ending='stop'; window=''})
    # F6-2-07：SEALED(AUTHORITY_UNREADABLE)，同一個 instance 內退避期過後的邊緣觸發也不重試之後 crash；重啟之後重試成功。
    'unreadable' = @(@{phase='unreadable-crash'; ending='halt'; window='SEALED_STEP'}, @{phase='unreadable-restart'; ending='stop'; window=''})
    # H7：開服時不預先載入原艙附近、原艙卸載之後才 JOIN 在艙內；不離開再進入就續行（w8late，DORMANT）／重試（unreadablelate，SEALED(AUTHORITY_UNREADABLE)）。
    'w8late' = @(@{phase='w8late-crash'; ending='halt'; window='DORMANT_STEP'}, @{phase='w8late-restart'; ending='stop'; window=''})
    'unreadablelate' = @(@{phase='unreadablelate-crash'; ending='halt'; window='SEALED_STEP'}, @{phase='unreadablelate-restart'; ending='stop'; window=''})
    # H7 的 WAITING 版：crash 同 w5、level.dat 帶著半徑 0 的 spawn chunks；開服時原艙附近沒有載入、參與者已在艙內，不離開再進入就進 PREPARE。
    'waitinglate' = @(@{phase='waitinglate-crash'; ending='halt'; window='DISCOVERY_APPENDED'}, @{phase='waitinglate-restart'; ending='stop'; window=''})
    # F6-2d-2 B1：PREPARE 的原艙門跨重啟（共用 READY_HELD：PASSAGE_READY 的提交被暫緩、門是 sealed 量子膜時 crash）。waitingdoor：重啟之後停在 WAITING、門改為一般開門；
    # preparedoor：PREPARE 中重啟、艙內有非參與者時維持一般開門，他離開之後的下一個 tick 重新套用 sealed。
    'waitingdoor' = @(@{phase='waitingdoor-crash'; ending='halt'; window='READY_HELD'}, @{phase='waitingdoor-restart'; ending='stop'; window=''})
    'preparedoor' = @(@{phase='preparedoor-crash'; ending='halt'; window='READY_HELD'}, @{phase='preparedoor-restart'; ending='stop'; window=''})
    # F6-2d-2 B2：SEALED(AUTHORITY_UNREADABLE) 的寫入已確認、關門之前 crash。sealclose：開服時門開著，重啟之後只關一次；sealdoorway：門口有盔甲座，重啟之後改為一般開門。
    'sealclose' = @(@{phase='sealclose-crash'; ending='halt'; window='UNREADABLE_SEALED'}, @{phase='sealclose-restart'; ending='stop'; window=''})
    'sealdoorway' = @(@{phase='sealdoorway-crash'; ending='halt'; window='UNREADABLE_SEALED_DOORWAY'}, @{phase='sealdoorway-restart'; ending='stop'; window=''})
    # F6-2d-2 B2：投影寫入失敗 → SEALED(PROJECTION_FAILED) 的寫入已確認、門是 sealed 量子膜時 crash。sealmembrane：開服時沒有參與者；sealmembranejoined：參與者開服時就在艙內。
    'sealmembrane' = @(@{phase='sealmembrane-crash'; ending='halt'; window='PROJECTION_SEALED'}, @{phase='sealmembrane-restart'; ending='stop'; window=''})
    'sealmembranejoined' = @(@{phase='sealmembranejoined-crash'; ending='halt'; window='PROJECTION_SEALED'}, @{phase='sealmembranejoined-restart'; ending='stop'; window=''})
    # F6-2e-1（W10）：M4 時期的 Nether／End 收據（schema 3 的 journal），M5 第一次處理之前 crash；restart 取消 NEW、完成 SOURCE。
    'w10' = @(@{phase='w10-crash'; ending='halt'; window='M4_RECEIPT_STEP'}, @{phase='w10-restart'; ending='stop'; window=''})
    # F6-2e-2（W11）：M4 時期的 schema 3 存檔第一次以 M5 開服，初始化只建立 allocation ledger 就 crash；第一次重啟讀既有的 ledger 失敗、不補建，第二次重啟補建、不判為遺失，NEW 到達 READY。
    'w11' = @(@{phase='w11-setup'; ending='stop'; window=''}, @{phase='w11-crash'; ending='halt'; window='FIRST_LEDGER_CREATED'}, @{phase='w11-unreadable'; ending='stop'; window=''}, @{phase='w11-restart'; ending='stop'; window=''})
    # F6-2e-3（W12）：catalog 配置與 discovery 追加的「atomic move 已完成、讀回失敗」各一次，都在同一個 JVM 收斂（roll-forward、確認之前不物化、已落地的 record 不移除）之後 crash；restart 同 W5，續行到 READY。
    'w12' = @(@{phase='w12-crash'; ending='halt'; window='DISCOVERY_CONVERGED'}, @{phase='w12-restart'; ending='stop'; window=''})
}
# chain 表的全部 phase（依 chain 的順序）：與每個 receipt 的 phaseTable（probe 的 phase 表）逐一比對，任何一邊多出或少了 phase 都判 FAIL。
$tablePhases = @($chains.Values | ForEach-Object { $_ } | ForEach-Object { $_.phase })
$Only = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($name in $Only) { if (-not $chains.Contains($name)) { throw "未知的 chain：$name（可用：$($chains.Keys -join ', ')）" } }

# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$probeContext = [pscustomobject]@{Project=$taskProject; Gradle=$taskGradle; InitScript=(Join-Path $PSScriptRoot 'runtime.init.gradle'); RunId=''; RootPrefix='m5-collapse'; EvidencePrefix='collapse'; EvidenceOwner=''; Task='runM5Collapse'; PropertyPrefix='m5Collapse'; NonceProperty='quantumchamber.m5collapse.startupNonce'; IdleSeconds=$IdleSeconds; PhaseMinutes=$PhaseMinutes; GradleEncoding=[Text.Encoding]::GetEncoding($GradleCodePage); LogEncoding=[Text.Encoding]::UTF8}
if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $taskProject '.superpowers/verification' }
$EvidenceRoot = Resolve-Evidence -Context $probeContext -Path $EvidenceRoot
$probeOwner = Resolve-Evidence -Context $probeContext -Path (Join-Path $taskProject '.superpowers/sdd/2026-10-01-m5-collapse-passage')
$taskRunId = [guid]::NewGuid().ToString('N')
$probeContext.RunId=$taskRunId; $probeContext.EvidenceOwner=$probeOwner
$taskResults = [System.Collections.Generic.List[object]]::new()

# phase evidence 裡 receipt 的摘要（status 與 failures；halt.json 另有 hit、crashPointFailure、refused），附在失敗訊息上：不論哪一項檢查失敗，都先看得到 probe 判定的原因。

# 以共用讀取開啟（log4j 與 Gradle 仍開著寫入）讀檔案長度；不存在或讀不到時回 -1。長度以 handle 讀，不用目錄項（寫入中的檔案，目錄項的大小可能過期）。

# 檔案最後 $Count 個非空行（共用讀取）；不存在時回空陣列。

# phase 逾時的處置：核對 PID 仍是本次的 JVM（commandline 含 startupNonce、StartTime 相符）之後先 jstack，再只結束那一個 java PID；Gradle（daemon 與 client）不結束，
# JVM 結束之後由 Gradle 自行收尾。寫 timeout.json 之後 throw（判 FAIL）。JVM 還沒有找到時（例如 Gradle 還在編譯）只記錄、不結束任何程序。

function Invoke-M5Phase([string]$Nonce, [string]$Phase, [string]$Root, [string]$Ending, [string]$Window) {
    $state=Invoke-OwnedPhase -Context $probeContext -Nonce $Nonce -Phase $Phase -Root $Root
    $phaseEvidence=$state.PhaseEvidence; $startupNonce=$state.StartupNonce; $launcher=$state.Launcher
    $javaProcess=$state.JavaProcess; $javaId=$state.JavaId; $javaStart=$state.JavaStart; $javaExit=$javaProcess.ExitCode
    $stdout=Join-Path $phaseEvidence 'stdout.log'; $stderr=Join-Path $phaseEvidence 'stderr.log'
    $gradleOutput = (Get-SharedText -Path $stdout -Encoding $probeContext.GradleEncoding) + (Get-SharedText -Path $stderr -Encoding $probeContext.GradleEncoding)
    $finalPath = Join-Path $phaseEvidence 'final.json'
    $haltPath = Join-Path $phaseEvidence 'halt.json'
    $note = Get-PhaseFailureNote -Context $probeContext -State $state
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
        if ($receipt.status -ne 'HALT' -or $receipt.window -cne $Window -or $receipt.phase -ne $Phase) { throw "halt receipt 不符：status=$($receipt.status) window=$($receipt.window)（預期 $Window；$note）" }
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
    $auxiliaryLogs = Copy-OwnedLogs -State $state
    Get-ChildItem -LiteralPath (Join-Path $Root 'world/data') -File | ForEach-Object { [ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash} } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'data-hashes.json') -Encoding UTF8
    $identity = [ordered]@{ phase=$Phase; ending=$Ending; window=$Window; nonce=$Nonce; startupNonce=$startupNonce; javaPid=$javaId; javaStartTimeUtc=$javaStart.ToString('o'); javaExitCode=$javaExit; gradleExitCode=$launcher.ExitCode; lockReleased=$true; root=$Root; receipt=$receiptPath; receiptSha256=(Get-FileHash -LiteralPath $receiptPath -Algorithm SHA256).Hash }
    $identity.auxiliaryLogs = $auxiliaryLogs
    $identity.logEncodings = [ordered]@{latestLog=$probeContext.LogEncoding.WebName; gradle=$probeContext.GradleEncoding.WebName; gradleCodePage=$probeContext.GradleEncoding.CodePage}
    $identity | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $phaseEvidence 'launcher.json') -Encoding UTF8
    $taskResults.Add([pscustomobject]$identity)
    Write-Output "PASS $Phase ending=$Ending pid=$javaId javaExit=$javaExit root=$Root"
}


foreach ($chain in $chains.Keys) {
    if ($Only.Count -gt 0 -and $chain -notin $Only) { continue }
    $owned = New-OwnedRoot -Context $probeContext -Suffix $chain
    $chainStart = $taskResults.Count
    foreach ($entry in $chains[$chain]) { Invoke-M5Phase $owned.nonce $entry.phase $owned.root $entry.ending $entry.window }
    $chainResults = @($taskResults[$chainStart..($taskResults.Count-1)])
    $identities = @($chainResults | ForEach-Object { "$($_.javaPid)@$($_.javaStartTimeUtc)" } | Select-Object -Unique)
    $nonces = @($chainResults | Select-Object -ExpandProperty startupNonce -Unique)
    if ($identities.Count -ne $chainResults.Count -or $nonces.Count -ne $chainResults.Count) { throw "chain $chain 的每個 phase 必須是不同的 JVM（PID@StartTime 與 startupNonce）" }
}
$summaryPath = Join-Path $EvidenceRoot "collapse-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "collapse 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M5 collapse evidence: $summaryPath"
