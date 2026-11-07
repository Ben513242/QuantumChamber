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
# （原因、PID、jstack 檔名、最後的 log 行），判 FAIL；最新 CIM CreationDate 獨立核對身分。
# JVM 尚未找到時會重新查 nonce；確認沒有該 JVM 且 launcher 身分相符，才結束本次 launcher handle。
# 參數：
#   -Only          只跑指定 chain（例如 -Only w2,w3；以 pwsh -File 呼叫時也接受逗號分隔的單一字串）；未指定時跑全部
#   -EvidenceRoot  彙整 collapse-run-<runId>.json 的位置；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        gradle.bat／gradlew.bat；預設 Gradle 8.8 wrapper dist，不存在時用 <repo>/gradlew.bat
#   -IdleSeconds   JVM 沒有任何新輸出多久判為卡住；預設 90（正常 phase 的 log 間隔最長約 6 秒）
#   -PhaseMinutes  一個 phase 的總上限；預設 8
#   -GradleCodePage launcher stdout/stderr 的明確編碼；預設目前文化的 OEM code page（zh-TW 為 950），latest.log 固定 UTF-8
# phase receipt 位置由 testmod M5CollapseRecoveryProbe 固定為 <repo>/.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>，不受 -EvidenceRoot 影響。
# 新增 phase：testmod 的 M5CollapseRecoveryProbe.phases() 加 entry，這裡的 $chains 加同名的 phase 與預期的結束（README「M5 collapse probe」）。
# 結構比照 m4-recovery-probe.ps1（owned root、phase evidence、launcher.json、彙整 JSON）。
param([string]$StopAfterPhase = '', [string]$ImportW10Crash = '', [switch]$ImportOnly, [string]$ResumeImportedW10 = '', [string]$ImportedBridgeSha256 = '', [string[]]$Only = @(), [string]$EvidenceRoot = '', [string]$Gradle = '', [int]$IdleSeconds = 90, [int]$PhaseMinutes = 8, [int]$GradleCodePage = [Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
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
    # F6-2c：通道與終局的重啟窗口。W7：PASSAGE_READY 期間兩人中選門者已跨過（Crossed 已確認；probe 在 TICK_END 取 atHalt 後 halt，不額外存檔；production 仍依跨越流程存 playerdata）；重啟之後通道在開服時結束，同一個 server 內原艙再開新 session。
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


# W10 可達性 spike：只在固定舊 checkout 產生 partial，現版明示匯入。
# 不替代 full69；原 halt bytes 與舊 root 欄位完整保留。
function Assert-W10Path([string]$Path, [switch]$Absent) {
    if (-not [IO.Path]::IsPathFullyQualified($Path)) { throw "W10_PATH：必須絕對路徑 $Path" }
    $full=[IO.Path]::GetFullPath($Path)
    if ($full -cne $Path.Replace('/', '\')) { throw "W10_PATH：不是 canonical $Path" }
    $cursor=$full
    while ($cursor) {
        $item=Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue -ErrorVariable pathError
        if ($item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "W10_REPARSE：$cursor" }
            if ($cursor -ne $full -and -not $item.PSIsContainer) { throw "W10_PARENT：$cursor" }
        } elseif ($pathError -and $pathError[0].CategoryInfo.Category -ne 'ObjectNotFound') { throw $pathError[0] }
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }; $cursor=$parent
    }
    if ($Absent -and (Get-Item -LiteralPath $full -Force -ErrorAction SilentlyContinue)) { throw "W10_EXISTS：$full" }
    return $full
}
function Assert-W10Idle([string]$Project, [string]$Nonce, [string]$Root) {
    # 保守拒絕任何非 daemon Java；讀不到命令列同樣拒絕，不猜測 ownership。
    $live=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        -not $_.CommandLine -or $_.CommandLine -notmatch 'org\.gradle\.launcher\.daemon\.bootstrap\.GradleDaemon' -or
        $_.CommandLine.Contains($Project) -or $_.CommandLine.Contains($Nonce)
    })
    if ($live.Count) { throw "W10_OWNER：Java owner 尚存 $($live.ProcessId -join ',')" }
    if ($Root) {
        foreach ($file in @(Get-ChildItem -LiteralPath (Join-Path $Root 'world') -Recurse -Force -File -Filter session.lock)) {
            $lock=[IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
            $lock.Dispose()
        }
    }
}
function Get-W10Pin([string]$Project, [switch]$Producer) {
    $head=(& git -C $Project rev-parse HEAD).Trim(); if ($LASTEXITCODE) { throw 'W10_GIT：HEAD' }
    $status=@(& git -C $Project status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -or @($status | Where-Object { $_ -cne ' M scripts/verification/m5-collapse-probe.ps1' }).Count) { throw 'W10_GIT：僅允 runner 未stage窄patch' }
    $tag=(& git -C $Project rev-parse 'm5^{commit}').Trim()
    if ($LASTEXITCODE -or $tag -cne 'a2d2b404329d2574469fca064a663e21fe89f333' -or ($Producer -and $head -cne $tag)) { throw 'W10_GIT：m5 pin 不符' }
    $tagObject=(& git -C $Project rev-parse m5).Trim()
    if ($LASTEXITCODE -or $tagObject -cne '15c2943ce37950f468b030c5d194485a328c1705') { throw 'W10_GIT：annotated tag 不符' }
    $patch=(@(& git -C $Project diff --no-ext-diff --binary HEAD -- scripts/verification/m5-collapse-probe.ps1) -join "`n")
    if ($LASTEXITCODE) { throw 'W10_GIT：diff' }
    [ordered]@{codeSha=$head;tagObject=$tagObject;tagTarget=$tag;runnerSha256=(Get-FileHash -LiteralPath (Join-Path $Project 'scripts/verification/m5-collapse-probe.ps1')).Hash;
        runnerPatchSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($patch)));runnerPatch=$patch;gitStatus=$status}
}
function Get-W10Files([string]$Root, [string]$Crash) {
    $files=[Collections.Generic.List[object]]::new()
    foreach ($pair in @(@{base=$Root;prefix='root';paths=@('world','eula.txt','server.properties')},@{base=$Crash;prefix='crash';paths=@('')})) {
        foreach ($rel in $pair.paths) {
            $path=if ($rel) { Join-Path $pair.base $rel } else { $pair.base }
            $null=Assert-W10Path $path
            $first=Get-Item -LiteralPath $path -Force
            # 先逐層走訪並檢 reparse，絕不先遞迴跟隨不明連結。
            $queue=[Collections.Generic.Queue[object]]::new(); $queue.Enqueue($first)
            while ($queue.Count) {
                $item=$queue.Dequeue(); $null=Assert-W10Path $item.FullName
                if ($item.PSIsContainer) { foreach ($child in Get-ChildItem -LiteralPath $item.FullName -Force) { $queue.Enqueue($child) }; continue }
                if ($item.Attributes -band [IO.FileAttributes]::Device) { throw "W10_FILE：不是一般檔 $($item.FullName)" }
                $files.Add([pscustomobject]@{area=$pair.prefix;relative=[IO.Path]::GetRelativePath($pair.base,$item.FullName);length=$item.Length;sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash})
            }
        }
    }
    foreach ($required in @('world\level.dat','world\session.lock','world\data\quantumchamber_sessions.dat')) {
        if (-not @($files | Where-Object { $_.area -eq 'root' -and $_.relative -ceq $required }).Count) { throw "W10_MISSING：$required" }
    }
    foreach ($required in @('halt.json','launcher.json','receipt.nbt','w10-batch.nbt')) {
        if (-not @($files | Where-Object { $_.area -eq 'crash' -and $_.relative -ceq $required }).Count) { throw "W10_MISSING：$required" }
    }
    if (-not @($files | Where-Object { $_.area -eq 'root' -and $_.relative -match '^world\\playerdata\\.+\.dat$' }).Count) { throw 'W10_MISSING：playerdata' }
    return @($files | Sort-Object area,relative)
}
function Assert-W10Files($Expected, $Actual) {
    if (($Expected | ConvertTo-Json -Depth 6 -Compress) -cne ($Actual | ConvertTo-Json -Depth 6 -Compress)) { throw 'W10_HASH：檔案集合／長度／SHA 改變' }
}
function Write-W10NewJson([string]$Path, $Value) {
    $null=Assert-W10Path $Path -Absent
    $stream=[IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $bytes=[Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 16)); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}
function Assert-W10Halt([string]$Root,[string]$Crash,[string]$Nonce) {
    if ($Nonce -cnotmatch '^[0-9a-f]{32}-w10$') { throw 'W10_NONCE：格式錯誤' }
    if (Test-Path -LiteralPath (Join-Path $Crash 'final.json')) { throw 'W10_HALT：存在 final.json' }
    $h=Get-Content -LiteralPath (Join-Path $Crash 'halt.json') -Raw | ConvertFrom-Json -DateKind String
    $l=Get-Content -LiteralPath (Join-Path $Crash 'launcher.json') -Raw | ConvertFrom-Json -DateKind String
    if ($h.status -cne 'HALT' -or $h.phase -cne 'w10-crash' -or $h.ending -cne 'HALT' -or $h.window -cne 'M4_RECEIPT_STEP' -or
        $h.nonce -cne $Nonce -or $h.root -cne $Root -or $h.hit -ne 1 -or $null -eq $h.failures -or @($h.failures).Count -ne 0 -or
        $h.crashPointFailure -cne 'none' -or ($h.phaseTable -join ',') -cne ($tablePhases -join ',') -or
        $h.runtimeScope -cne 'testmod-present' -or 'quantumchamber-testmod' -cnotin $h.loadedModIds) { throw 'W10_HALT：receipt 不符' }
    if ($l.phase -cne 'w10-crash' -or $l.ending -cne 'halt' -or $l.window -cne 'M4_RECEIPT_STEP' -or $l.root -cne $Root -or $l.nonce -cne $Nonce -or
        $l.javaExitCode -ne 77 -or $l.gradleExitCode -eq 0 -or $l.lockReleased -ne $true -or $l.javaPid -ne $h.pid -or $h.pid -le 0 -or
        $h.startupNonce -cnotmatch '^[0-9a-f]{32}$' -or $l.startupNonce -cne $h.startupNonce -or
        [Math]::Abs(([DateTimeOffset]::Parse($l.javaStartTimeUtc)-[DateTimeOffset]::Parse($h.startTimeUtc)).TotalSeconds) -gt 1 -or
        $l.receiptSha256 -cne (Get-FileHash -LiteralPath (Join-Path $Crash 'halt.json')).Hash) { throw 'W10_IDENTITY：launcher 不符' }
    return $l
}
$w10OldProject=[IO.Path]::GetFullPath('C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-w10-m5-producer')
$w10NewProject=[IO.Path]::GetFullPath('C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation')

function Assert-W10Mode([string[]]$Selected, [string]$Stop, [string]$Import, [bool]$OnlyImport, [string]$Resume, [string]$BridgeSha) {
    if (-not ($Stop -or $Import -or $OnlyImport -or $Resume -or $BridgeSha)) { return }
    $modes=@(@($Stop,$Import,$Resume) | Where-Object { $_ }).Count
    if ($Selected.Count -ne 1 -or $Selected[0] -cne 'w10' -or $modes -ne 1 -or
        ([bool]$Import -ne $OnlyImport) -or ([bool]$Resume -ne [bool]$BridgeSha) -or
        ($Resume -and $BridgeSha -cnotmatch '^[0-9A-Fa-f]{64}$')) { throw 'W10_MODE：需唯一模式，import須ImportOnly，resume須外部bridge SHA' }
}
function Assert-W10Same($Expected, $Actual, [string]$Label) {
    if (($Expected | ConvertTo-Json -Depth 16 -Compress) -cne ($Actual | ConvertTo-Json -Depth 16 -Compress)) { throw "W10_IDENTITY：$Label 改變" }
}
function Assert-W10SourcePin($Pin) {
    if ($Pin.codeSha -cne 'a2d2b404329d2574469fca064a663e21fe89f333' -or
        $Pin.tagTarget -cne 'a2d2b404329d2574469fca064a663e21fe89f333' -or
        $Pin.tagObject -cne '15c2943ce37950f468b030c5d194485a328c1705' -or
        $Pin.runnerSha256 -cne '6036C012B890080DFD15168B6E90D42856698B062BAA2405BBB44B38F3E7FE7E' -or
        $Pin.runnerPatchSha256 -cne '54993C33BE32E74FEF571804C91E527C912C721440F8E7B17FEDD3115EA01670') { throw 'W10_SOURCE_PIN：只允已審v2 producer' }
}
function Get-W10Source([string]$Root) {
    $nonce='625a9f816af14087bc50e66896bd137b-w10'
    $rootPath=Assert-W10Path $Root
    if ($rootPath -cne (Join-Path $w10OldProject "run/m5-collapse-$nonce")) { throw 'W10_SOURCE：只允已審exact nonce/root' }
    $ep=Join-Path $w10OldProject ".superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-$nonce"
    $crash=Join-Path $ep 'w10-crash'
    $manifestPath=Assert-W10Path (Join-Path $ep 'producer.json')
    $null=Assert-W10Path (Join-Path $ep 'w10-restart') -Absent
    $manifestHash='CE944EC478FFD3F1D83C495963CEFFA4FF89940D2E8D6974382121217B049EDB'
    if ((Get-FileHash -LiteralPath $manifestPath).Hash -cne $manifestHash) { throw 'W10_PRODUCER：原manifest SHA不符' }
    $producer=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -DateKind String
    $pin=Get-W10Pin $w10OldProject -Producer
    Assert-W10SourcePin $pin
    Assert-W10SourcePin $producer.pin
    Assert-W10Same $producer.pin $pin 'source pin三方核對'
    if ($producer.status -cne 'HALT_ONLY' -or $producer.completeChain -ne $false -or $producer.nonce -cne $nonce -or
        $producer.sourceRoot -cne $rootPath -or $producer.sourceEvidence -cne $ep -or @($producer.files).Count -ne 63) { throw 'W10_PRODUCER：provenance 不符' }
    $files=Get-W10Files $rootPath $crash
    Assert-W10Files $producer.files $files
    $old=Assert-W10Halt $rootPath $crash $nonce
    Assert-W10Same $producer.phase $old '舊phase'
    if ($old.receiptSha256 -cne 'BE60910D55F3875CBA94B65EF79EF2E3BCA3868194031A87D6C31E1F2D8BAFEB' -or
        $old.javaPid -ne 5208 -or $old.startupNonce -cne 'b4ec87ba3d434cc6b51e6906f2fa2f78') { throw 'W10_PRODUCER：已審halt身分不符' }
    Assert-W10Idle $w10OldProject $nonce $rootPath
    if ((Get-FileHash -LiteralPath $manifestPath).Hash -cne $manifestHash) { throw 'W10_PRODUCER：讀取中manifest改變' }
    return [pscustomobject]@{nonce=$nonce;root=$rootPath;evidence=$ep;crash=$crash;pin=$pin;files=$files;phase=$old;manifestSha=$manifestHash}
}
function Get-W10ResumeState([string]$Root, [string]$ReviewedSha, [string]$AttemptSha = '') {
    if ($ReviewedSha -cnotmatch '^[0-9A-Fa-f]{64}$') { throw 'W10_BRIDGE_SHA：缺外部釘選SHA' }
    $nonce='625a9f816af14087bc50e66896bd137b-w10'
    $dest=Assert-W10Path $Root
    if ($dest -cne (Join-Path $taskProject "run/m5-collapse-$nonce")) { throw 'W10_DEST：exact root/nonce不符' }
    $ep=Assert-W10Path (Join-Path $probeOwner "collapse-$nonce")
    $bridgePath=Assert-W10Path (Join-Path $ep 'bridge-import.json')
    if ((Get-FileHash -LiteralPath $bridgePath).Hash -cne $ReviewedSha.ToUpperInvariant()) { throw 'W10_BRIDGE_SHA：不符審查釘選' }
    $bridge=Get-Content -LiteralPath $bridgePath -Raw | ConvertFrom-Json -DateKind String
    if ($bridge.bridgeContract -cne 'w10-v2-to-v3-two-stage-1' -or $bridge.status -cne 'IMPORTED_ONLY' -or
        $bridge.completeChain -ne $false -or $bridge.spikeOnly -ne $true -or $bridge.restartStarted -ne $false -or
        $bridge.nonce -cne $nonce -or $bridge.destRoot -cne $dest -or $bridge.destEvidence -cne $ep) { throw 'W10_BRIDGE：contract不符' }
    $source=Get-W10Source (Join-Path $w10OldProject "run/m5-collapse-$nonce")
    if ($bridge.sourceRoot -cne $source.root -or $bridge.sourceEvidence -cne $source.evidence -or
        $bridge.producerManifestSha256 -cne $source.manifestSha -or $bridge.oldHaltSha256 -cne $source.phase.receiptSha256) { throw 'W10_BRIDGE：source映射不符' }
    Assert-W10Same $source.pin $bridge.producerPin 'bridge producer pin'
    Assert-W10Same $source.phase $bridge.oldPhase 'bridge old phase'
    Assert-W10Files $source.files $bridge.files
    $pin=Get-W10Pin $taskProject
    Assert-W10Same $bridge.consumerPin $pin 'consumer pin'
    $crash=Join-Path $ep 'w10-crash'
    Assert-W10Files $source.files (Get-W10Files $dest $crash)
    # copied halt仍指舊root；不能把sourceRoot換成dest再序列化。
    $null=Assert-W10Halt $source.root $crash $nonce
    foreach ($leaf in 'w10-restart','bridge-result.json','resume-fail.json','import-fail.json') { $null=Assert-W10Path (Join-Path $ep $leaf) -Absent }
    $attempt=Join-Path $ep 'resume-attempt.json'
    if ($AttemptSha) {
        $null=Assert-W10Path $attempt
        if ((Get-FileHash -LiteralPath $attempt).Hash -cne $AttemptSha) { throw 'W10_ATTEMPT：本次預留標記改變' }
    } else { $null=Assert-W10Path $attempt -Absent }
    Assert-W10Idle $taskProject $nonce $dest
    if ((Get-FileHash -LiteralPath $bridgePath).Hash -cne $ReviewedSha.ToUpperInvariant()) { throw 'W10_BRIDGE_SHA：重核期間改變' }
    Assert-W10Same $pin (Get-W10Pin $taskProject) '重核後consumer pin'
    return [pscustomobject]@{nonce=$nonce;root=$dest;evidence=$ep;crash=$crash;bridgePath=$bridgePath;bridge=$bridge;source=$source;pin=$pin}
}
Assert-W10Mode $Only $StopAfterPhase $ImportW10Crash ([bool]$ImportOnly) $ResumeImportedW10 $ImportedBridgeSha256
if ($StopAfterPhase -or $ImportW10Crash -or $ResumeImportedW10) {
    if ($StopAfterPhase -and ($StopAfterPhase -cne 'w10-crash' -or $taskProject -cne $w10OldProject)) { throw 'W10_MODE：HALT_ONLY只允固定舊checkout' }
    if (($ImportW10Crash -or $ResumeImportedW10) -and $taskProject -cne $w10NewProject) { throw 'W10_MODE：import/resume只允現版checkout' }
    $null=Assert-W10Path $taskProject
    $null=Assert-W10Path (Join-Path $taskProject 'run')
    $w10Pin=Get-W10Pin $taskProject -Producer:([bool]$StopAfterPhase)
}

# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$probeContext = [pscustomobject]@{Project=$taskProject; Gradle=$taskGradle; InitScript=(Join-Path $PSScriptRoot 'runtime.init.gradle'); RunId=''; RootPrefix='m5-collapse'; EvidencePrefix='collapse'; EvidenceOwner=''; Task='runM5Collapse'; PropertyPrefix='m5Collapse'; NonceProperty='quantumchamber.m5collapse.startupNonce'; IdleSeconds=$IdleSeconds; PhaseMinutes=$PhaseMinutes; GradleEncoding=[Text.Encoding]::GetEncoding($GradleCodePage); LogEncoding=[Text.Encoding]::UTF8}
if (-not $EvidenceRoot) { $EvidenceRoot = Join-Path $taskProject '.superpowers/verification' }
$EvidenceRoot = Resolve-Evidence -Context $probeContext -Path $EvidenceRoot
$probeOwner = Resolve-Evidence -Context $probeContext -Path (Join-Path $taskProject '.superpowers/sdd/2026-10-01-m5-collapse-passage')
$taskRunId = [guid]::NewGuid().ToString('N')
$probeContext.RunId=$taskRunId; $probeContext.EvidenceOwner=$probeOwner
$taskResults = [System.Collections.Generic.List[object]]::new()

# 共用 launcher／timeout 在 probe-common.ps1；本 wrapper 保留 phase 表、receipt 與世界鎖 oracle。
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


if ($ImportW10Crash) {
    # 此區在建立任何 destination leaf 前只讀 source；不同 RunId 僅供診斷，不用來命名匯入世界。
    $destinationReserved=$false; $destRoot=$null; $destEvidence=$null
    try {
        $source=Get-W10Source $ImportW10Crash
        $sourceRoot=$source.root; $nonce=$source.nonce; $sourceEvidence=$source.evidence; $sourceCrash=$source.crash
        $sourcePin=$source.pin; $producerManifestHash=$source.manifestSha; $files=$source.files; $oldIdentity=$source.phase
        $destRoot=Assert-W10Path (Join-Path $taskProject "run/m5-collapse-$nonce") -Absent
        $destEvidence=Assert-W10Path (Join-Path $probeOwner "collapse-$nonce") -Absent
        Assert-W10Idle $taskProject $nonce ''
        # New-Item 無 Force：已存在即錯，絕不覆寫或借用普通 New-OwnedRoot。
        $null=New-Item -ItemType Directory -Path $destRoot
        $destinationReserved=$true
        $null=New-Item -ItemType Directory -Path $destEvidence
        $null=Assert-W10Path $destRoot; $null=Assert-W10Path $destEvidence
        Assert-W10Files $files (Get-W10Files $sourceRoot $sourceCrash)
        Assert-W10Idle $w10OldProject $nonce $sourceRoot
        $destCrash=Join-Path $destEvidence 'w10-crash'
        $null=New-Item -ItemType Directory -Path $destCrash
        foreach ($file in $files) {
            $srcBase=if ($file.area -ceq 'root') { $sourceRoot } else { $sourceCrash }
            $dstBase=if ($file.area -ceq 'root') { $destRoot } else { $destCrash }
            $src=Assert-W10Path (Join-Path $srcBase $file.relative)
            $dst=Assert-W10Path (Join-Path $dstBase $file.relative) -Absent
            if (-not $dst.StartsWith($dstBase+'\',[StringComparison]::Ordinal)) { throw 'W10_COPY：relative 路徑逸出' }
            $parent=[IO.Path]::GetDirectoryName($dst)
            if (-not (Test-Path -LiteralPath $parent)) { $null=New-Item -ItemType Directory -Path $parent }
            $null=Assert-W10Path $dst -Absent
            $sourceStream=[IO.File]::Open($src,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
            try {
                $output=[IO.File]::Open($dst,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
                try { $sourceStream.CopyTo($output) } finally { $output.Dispose() }
            } finally { $sourceStream.Dispose() }
            if ((Get-Item -LiteralPath $dst).Length -ne $file.length -or (Get-FileHash -LiteralPath $dst).Hash -cne $file.sha256) { throw "W10_COPY_HASH：$dst" }
        }
        Assert-W10Files $files (Get-W10Files $destRoot $destCrash)
        Assert-W10Files $files (Get-W10Files $sourceRoot $sourceCrash)
        Assert-W10Idle $w10OldProject $nonce $sourceRoot
        Assert-W10Idle $taskProject $nonce $destRoot
        $null=Assert-W10Path (Join-Path $destEvidence 'w10-restart') -Absent
        if ((Get-FileHash -LiteralPath (Join-Path $sourceEvidence 'producer.json')).Hash -cne $producerManifestHash) { throw 'W10_PRODUCER：manifest 執行中改變' }
        foreach ($pinCheck in @(@{before=$sourcePin;after=(Get-W10Pin $w10OldProject -Producer)},@{before=$w10Pin;after=(Get-W10Pin $taskProject)})) {
            if (($pinCheck.before | ConvertTo-Json -Depth 6 -Compress) -cne ($pinCheck.after | ConvertTo-Json -Depth 6 -Compress)) { throw 'W10_PIN：匯入期間 code/runner 改變' }
        }
        # 映射記在新檔；copy 的 halt.root 仍為 sourceRoot，不做 JSON round-trip。
        Write-W10NewJson (Join-Path $destEvidence 'bridge-import.json') ([ordered]@{
            status='IMPORTED_ONLY';completeChain=$false;spikeOnly=$true;restartStarted=$false;bridgeContract='w10-v2-to-v3-two-stage-1';
            nonce=$nonce;sourceRoot=$sourceRoot;destRoot=$destRoot;
            sourceEvidence=$sourceEvidence;destEvidence=$destEvidence;producerPin=$sourcePin;consumerPin=$w10Pin;
            producerManifestSha256=$producerManifestHash;oldHaltSha256=$oldIdentity.receiptSha256;
            oldPhase=$oldIdentity;files=$files;sourceOwnerCount=0;destOwnerCount=0;locksReleased=$true
        })
        $bridgePath=Join-Path $destEvidence 'bridge-import.json'
        $bridgeHash=(Get-FileHash -LiteralPath $bridgePath).Hash
        $null=Get-W10ResumeState $destRoot $bridgeHash
    } catch {
        $failure=[ordered]@{status='IMPORT_FAIL';completeChain=$false;source=$ImportW10Crash;destRoot=$destRoot;destEvidence=$destEvidence;reserved=$destinationReserved;error=$_.Exception.Message}
        # 預留一半也保留；診斷位置用唯一 runId，不能覆寫任何 source/destination。
        Write-W10NewJson (Join-Path $EvidenceRoot "w10-import-fail-$taskRunId.json") $failure
        if ($destinationReserved -and (Test-Path -LiteralPath $destEvidence -PathType Container)) { Write-W10NewJson (Join-Path $destEvidence 'import-fail.json') $failure }
        throw
    }
    Write-Output "W10 IMPORTED_ONLY bridge=$bridgePath SHA256=$bridgeHash（未啟Java，待獨立審查及另行resume授權）"
    return
}
if ($ResumeImportedW10) {
    $attemptWritten=$false; $resume=$null
    try {
        $resume=Get-W10ResumeState $ResumeImportedW10 $ImportedBridgeSha256
        $attemptPath=Join-Path $resume.evidence 'resume-attempt.json'
        Write-W10NewJson $attemptPath ([ordered]@{status='ATTEMPT_RESERVED';completeChain=$false;nonce=$resume.nonce;
            bridgeSha256=$ImportedBridgeSha256.ToUpperInvariant();producerPin=$resume.source.pin;consumerPin=$resume.pin;runId=$taskRunId})
        $attemptWritten=$true
        $attemptHash=(Get-FileHash -LiteralPath $attemptPath).Hash
        # 單次標記建立後，再核世界、source/pin與鎖；仍不預建restart evidence。
        $checked=Get-W10ResumeState $ResumeImportedW10 $ImportedBridgeSha256 $attemptHash
        $probeContext.RunId=$checked.nonce.Substring(0,32)
        Invoke-M5Phase $checked.nonce 'w10-restart' $checked.root 'stop' ''
        $newIdentity=$taskResults[0]
        if ($newIdentity.startupNonce -ceq $checked.source.phase.startupNonce -or $newIdentity.javaPid -eq $checked.source.phase.javaPid) { throw 'W10_BRIDGE：必須全新PID/startupNonce' }
        # 成功marker必須最後寫；source讀回涵蓋63檔、manifest、halt、pin與owner/lock。
        $finalSource=Get-W10Source $checked.source.root
        Assert-W10Same $checked.source $finalSource 'restart後source'
        Assert-W10Same $checked.pin (Get-W10Pin $taskProject) 'restart後consumer pin'
        if ((Get-FileHash -LiteralPath $checked.bridgePath).Hash -cne $ImportedBridgeSha256.ToUpperInvariant() -or
            (Get-FileHash -LiteralPath $attemptPath).Hash -cne $attemptHash) { throw 'W10_BRIDGE_SHA：restart後bridge/attempt改變' }
        $postFiles=Get-W10Files $checked.root $checked.crash
        Assert-W10Files @($checked.source.files | Where-Object area -eq 'crash') @($postFiles | Where-Object area -eq 'crash')
        $null=Assert-W10Halt $checked.source.root $checked.crash $checked.nonce
        Assert-W10Idle $taskProject $checked.nonce $checked.root
        Write-W10NewJson (Join-Path $checked.evidence 'bridge-result.json') ([ordered]@{
            status='RESTART_ONLY_IMPORT';completeChain=$false;spikeOnly=$true;nonce=$checked.nonce;
            sourceRoot=$checked.source.root;destRoot=$checked.root;bridgeSha256=$ImportedBridgeSha256.ToUpperInvariant();
            producerPin=$checked.source.pin;consumerPin=$checked.pin;producerPhase=$checked.source.phase;consumerPhase=$newIdentity;
            sourceUnchanged=$true;ownerCount=0;locksReleased=$true
        })
    } catch {
        $failure=[ordered]@{status=$(if ($attemptWritten) {'RESUME_FAIL'} else {'RESUME_PREFLIGHT_FAIL'});completeChain=$false;
            root=$ResumeImportedW10;attemptReserved=$attemptWritten;error=$_.Exception.Message}
        Write-W10NewJson (Join-Path $EvidenceRoot "w10-resume-fail-$taskRunId.json") $failure
        if ($attemptWritten) { Write-W10NewJson (Join-Path $resume.evidence 'resume-fail.json') $failure }
        throw
    }
    Write-Output "W10 RESTART_ONLY_IMPORT spike：$($checked.evidence)（非full69／Task12結案）"
    return
}

foreach ($chain in $chains.Keys) {
    if ($Only.Count -gt 0 -and $chain -notin $Only) { continue }
    if ($StopAfterPhase) {
        $freshNonce="$taskRunId-w10"
        $null=Assert-W10Path (Join-Path $taskProject "run/m5-collapse-$freshNonce") -Absent
        $null=Assert-W10Path (Join-Path $probeOwner "collapse-$freshNonce") -Absent
        Assert-W10Idle $taskProject $freshNonce ''
    }
    $owned = New-OwnedRoot -Context $probeContext -Suffix $chain
    $chainStart = $taskResults.Count
    foreach ($entry in $chains[$chain]) {
        Invoke-M5Phase $owned.nonce $entry.phase $owned.root $entry.ending $entry.window
        if ($StopAfterPhase -and $entry.phase -ceq $StopAfterPhase) {
            $phaseParent=Join-Path $probeOwner "collapse-$($owned.nonce)"
            $crash=Join-Path $phaseParent 'w10-crash'
            $files=Get-W10Files $owned.root $crash
            $identity=Assert-W10Halt $owned.root $crash $owned.nonce
            Assert-W10Idle $taskProject $owned.nonce $owned.root
            $afterPin=Get-W10Pin $taskProject -Producer
            if (($w10Pin | ConvertTo-Json -Depth 6 -Compress) -cne ($afterPin | ConvertTo-Json -Depth 6 -Compress)) { throw 'W10_PIN：producer 執行中改變' }
            Write-W10NewJson (Join-Path $phaseParent 'producer.json') ([ordered]@{
                status='HALT_ONLY';completeChain=$false;spikeOnly=$true;nonce=$owned.nonce;sourceRoot=$owned.root;
                sourceEvidence=$phaseParent;pin=$w10Pin;phase=$identity;ownerCount=0;locksReleased=$true;files=$files
            })
            Write-Output "W10 HALT_ONLY partial：$phaseParent（未跑 restart）"
            return
        }
    }
    $chainResults = @($taskResults[$chainStart..($taskResults.Count-1)])
    $identities = @($chainResults | ForEach-Object { "$($_.javaPid)@$($_.javaStartTimeUtc)" } | Select-Object -Unique)
    $nonces = @($chainResults | Select-Object -ExpandProperty startupNonce -Unique)
    if ($identities.Count -ne $chainResults.Count -or $nonces.Count -ne $chainResults.Count) { throw "chain $chain 的每個 phase 必須是不同的 JVM（PID@StartTime 與 startupNonce）" }
}
$summaryPath = Join-Path $EvidenceRoot "collapse-run-$taskRunId.json"
if (Test-Path -LiteralPath $summaryPath) { throw "collapse 彙整已存在，禁止覆寫：$summaryPath" }
[ordered]@{status='PASS'; runId=$taskRunId; only=@($Only); phaseCount=$taskResults.Count; phases=@($taskResults)} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Output "M5 collapse evidence: $summaryPath"
