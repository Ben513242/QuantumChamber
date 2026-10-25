# M6 harness 安全檢查：只操作本次唯一 fixture，所有成功與失敗現場都保留。
param([Parameter(Mandatory)][ValidateSet('owned-root','move-fresh','ci-cwd-preflight','optional-probe-properties')][string]$Case,
      [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label, [switch]$InjectResidualEmptyDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$owner = [pscustomobject]@{Project=$project}
$taskDirectory = switch ($Case) { 'owned-root' {'task-01'} 'move-fresh' {'task-02'} default {'task-07'} }
$evidence = Resolve-Evidence -Context $owner -Path (Join-Path $project ".superpowers/sdd/2026-10-24-m6-universe-generation/$taskDirectory/$Label-$([guid]::NewGuid().ToString('N'))")
$results = [Collections.Generic.List[object]]::new()
$head = (git -C $project rev-parse HEAD).Trim()
$failure = $null

function Invoke-Fixture([string]$Name, [string]$Kind, [bool]$Unsafe) {
    $fixture = Join-Path $evidence $Name
    $fake = Join-Path $fixture 'project'
    $outside = Join-Path $fixture 'outside'
    New-Item -ItemType Directory -Path $fake, $outside | Out-Null
    '必須保持原樣' | Set-Content -LiteralPath (Join-Path $outside 'sentinel.txt') -Encoding UTF8
    $context = [pscustomobject]@{Project=$fake; RunId='fixed'; RootPrefix='m6-safety'}
    $suffix = 'case'
    switch ($Kind) {
        'existing' { New-Item -ItemType Directory -Path (Join-Path $fake 'run') | Out-Null }
        'fresh' { }
        'run-junction' { New-Item -ItemType Junction -Path (Join-Path $fake 'run') -Target $outside | Out-Null }
        'ancestor-junction' {
            New-Item -ItemType Directory -Path (Join-Path $outside 'nested') | Out-Null
            New-Item -ItemType Junction -Path (Join-Path $fake 'alias') -Target $outside | Out-Null
            $context.Project = Join-Path $fake 'alias/nested'
        }
        'noncanonical' {
            New-Item -ItemType Directory -Path (Join-Path $fake 'child') | Out-Null
            $context.Project = Join-Path $fake 'child/..'
        }
        'relative' { $context.Project = [IO.Path]::GetRelativePath((Get-Location).Path, $fake) }
        'escape-prefix' { $context.RootPrefix = '../../outside/escaped' }
        'escape-suffix' { $suffix = '../../../outside/escaped' }
    }
    $before = @(Get-ChildItem -LiteralPath $outside -Force -Recurse | ForEach-Object { $_.FullName })
    $sentinel = (Get-FileHash -LiteralPath (Join-Path $outside 'sentinel.txt')).Hash
    $caught = $null; $owned = $null
    try { $owned = New-OwnedRoot -Context $context -Suffix $suffix } catch { $caught = $_.Exception.Message }
    $after = @(Get-ChildItem -LiteralPath $outside -Force -Recurse | ForEach-Object { $_.FullName })
    $outsideUnchanged = @((Compare-Object -ReferenceObject $before -DifferenceObject $after)).Count -eq 0 -and $sentinel -eq (Get-FileHash -LiteralPath (Join-Path $outside 'sentinel.txt')).Hash
    if ($Unsafe) {
        $ok = $null -ne $caught -and $caught -like '*OWNED_ROOT_ANCESTOR_UNSAFE*' -and $outsideUnchanged
        # 被拒絕的輸入不得先建立任何 owned root 或設定檔。
        $created = @(Get-ChildItem -LiteralPath $fixture -Force -Recurse | Where-Object { $_.Name -in @('eula.txt','server.properties') })
        $ok = $ok -and $created.Count -eq 0
    } else {
        $expected = Join-Path $fake 'run/m6-safety-fixed-case'
        $ok = $null -eq $caught -and $null -ne $owned -and $owned.root -eq $expected -and $owned.nonce -eq 'fixed-case' -and $outsideUnchanged
        if ($ok) {
            $ok = (Get-Content -LiteralPath (Join-Path $expected 'eula.txt') -Raw).Trim() -eq 'eula=true' -and
                (Get-Content -LiteralPath (Join-Path $expected 'server.properties')) -contains 'level-name=world'
        }
    }
    $results.Add([ordered]@{name=$Name;kind=$Kind;unsafe=$Unsafe;passed=[bool]$ok;error=$caught;outsideUnchanged=$outsideUnchanged;fixture=$fixture})
    if (-not $ok) { throw "OWNED_ROOT_ANCESTOR_UNSAFE assertion FAIL case=$Name error=$caught outsideUnchanged=$outsideUnchanged" }
}

function Invoke-MoveFreshChecks {
    # 只抽出正式函式及其真實相依，不執行 gates 的入口。
    $repo = $project
    $tokens = $null; $parseErrors = $null
    $source = Join-Path $PSScriptRoot 'gates.ps1'
    $ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "gates 解析失敗：$($parseErrors[0])" }
    foreach ($name in @('Save-Json','Check-Path','Files-Hash','Lock-State','Java-Processes','Assert-Idle','Move-Fresh','Restore-Root')) {
        $definition = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name}, $false))
        if ($definition.Count -ne 1) { throw "正式函式數量不符：$name" }
        Invoke-Expression $definition[0].Extent.Text
    }
    foreach ($scenario in @('create-fails','partial-create-fails','restore-fails','normal-existing','normal-absent','absent-create-fails')) {
        $gate = Join-Path $evidence $scenario
        Microsoft.PowerShell.Management\New-Item -ItemType Directory -Path $gate | Out-Null
        $id = [guid]::NewGuid().ToString('N')
        $target = Join-Path $gate 'runtime'
        $originalExisted = $scenario -notin @('normal-absent','absent-create-fails')
        if ($originalExisted) {
            Microsoft.PowerShell.Management\New-Item -ItemType Directory -Path (Join-Path $target 'world/nested') | Out-Null
            [IO.File]::WriteAllBytes((Join-Path $target 'world/session.lock'), [byte[]]@(0,1,2,255))
            '原世界資料' | Set-Content -LiteralPath (Join-Path $target 'world/nested/data.txt') -Encoding UTF8
        }
        $before = @(Files-Hash $target) | ConvertTo-Json -Depth 4 -Compress
        $createError = [IO.IOException]::new("INJECTED_FRESH_CREATE_FAILURE $scenario")
        $restoreError = [IO.IOException]::new("INJECTED_RESTORE_FAILURE $scenario")
        # 僅攔截目標 fresh 的建立，以及指定的備份還原；其餘 move/hash/lock 均為真實 I/O。
        function New-Item {
            param($ItemType, $Path)
            if ($Path -eq $target -and $scenario -notlike 'normal-*') {
                if ($scenario -eq 'partial-create-fails') {
                    Microsoft.PowerShell.Management\New-Item -ItemType Directory -Path $Path | Out-Null
                    '部分 fresh 現場' | Set-Content -LiteralPath (Join-Path $Path 'partial.txt')
                }
                throw $createError
            }
            Microsoft.PowerShell.Management\New-Item -ItemType $ItemType -Path $Path
        }
        function Move-Item {
            param($LiteralPath, $Destination)
            if ($scenario -eq 'restore-fails' -and $LiteralPath -like '*.task9-backup-*' -and $Destination -eq $target) { throw $restoreError }
            Microsoft.PowerShell.Management\Move-Item -LiteralPath $LiteralPath -Destination $Destination
        }
        $caught = $null; $swap = $null
        try {
            $swap = Move-Fresh $target 'fixture'
            '成功 fresh 現場' | Set-Content -LiteralPath (Join-Path $target 'fresh.txt')
            Restore-Root $swap
        } catch { $caught = $_ }
        if ($InjectResidualEmptyDirectory -and $scenario -eq 'normal-absent') { Microsoft.PowerShell.Management\New-Item -ItemType Directory -Path $target | Out-Null }
        $observedExists = Test-Path -LiteralPath $target
        $after = @(Files-Hash $target) | ConvertTo-Json -Depth 4 -Compress
        $backups = @(Get-ChildItem -LiteralPath $gate -Directory | Where-Object Name -like '*.task9-backup-*')
        $locksAvailable = @(Lock-State $target | Where-Object { -not $_.exclusiveAvailable }).Count -eq 0
        $restoreReceipt = Join-Path $gate 'fixture-restore.json'
        $exactRestored = if (Test-Path -LiteralPath $restoreReceipt) { (Get-Content -LiteralPath $restoreReceipt -Raw | ConvertFrom-Json).exactRestored } else { $false }
        if ($scenario -eq 'restore-fails') {
            $ok = $null -ne $caught -and $caught.Exception.ToString().Contains($createError.Message) -and
                $caught.Exception.ToString().Contains($restoreError.Message) -and $backups.Count -eq 1 -and -not $exactRestored
            if ($backups.Count -eq 1) { $ok = $ok -and ((@(Files-Hash $backups[0].FullName) | ConvertTo-Json -Depth 4 -Compress) -ceq $before) }
        } elseif ($scenario -like 'normal-*') {
            $ok = $null -eq $caught -and $before -ceq $after -and $exactRestored -and $backups.Count -eq 0 -and $locksAvailable -and
                (Test-Path -LiteralPath (Join-Path $gate 'fixture-fresh/fresh.txt'))
        } else {
            $ok = $null -ne $caught -and [object]::ReferenceEquals($caught.Exception,$createError) -and $before -ceq $after -and
                $backups.Count -eq 0 -and $locksAvailable -and $exactRestored
            if ($scenario -eq 'partial-create-fails') { $ok = $ok -and (Test-Path -LiteralPath (Join-Path $gate 'fixture-fresh/partial.txt')) }
        }
        if ($scenario -ne 'restore-fails') { $ok = $ok -and ($observedExists -eq $originalExisted) }
        $results.Add([ordered]@{name=$scenario;observedExists=[bool]$observedExists;passed=[bool]$ok;originalExisted=$originalExisted;exactRestored=[bool]$exactRestored;hashUnchanged=($before -ceq $after);locksAvailable=$locksAvailable;backupPaths=@($backups | ForEach-Object FullName);error=$(if($caught){$caught.Exception.ToString()}else{$null});fixture=$gate})
        if (-not $ok) { throw "MOVE_FRESH_RESTORE_MISSING case=$scenario observedExists=$observedExists expectedExists=$originalExisted originalError=$caught backupCount=$($backups.Count)" }
    }
}

function Invoke-CiCwdPreflightChecks {
    $beforeJava=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId,ParentProcessId,CreationDate,CommandLine)
    $runPath=Join-Path $project 'run';$beforeExists=Test-Path -LiteralPath $runPath
    $beforeRun=@(if($beforeExists){Get-ChildItem -LiteralPath $runPath -Force | ForEach-Object Name | Sort-Object})
    $entryPids=[Collections.Generic.List[int]]::new();$entryTargets=[Collections.Generic.List[string]]::new()
    # 純guard驗參數配對；不直接啟動缺停止旗標的run-all。
    $tokens=$null;$parseErrors=$null;$runner=Join-Path $PSScriptRoot 'run-all.ps1'
    $runnerAst=[Management.Automation.Language.Parser]::ParseFile($runner,[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw 'M6_CI_PREFLIGHT_RUNNER_PARSE_FAILED'}
    $guards=@($runnerAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-CiCwdProbeTestMode'},$false))
    if($guards.Count -ne 1){throw 'M6_CI_PREFLIGHT_GUARD_COUNT'}
    Invoke-Expression $guards[0].Extent.Text
    Assert-CiCwdProbeTestMode -Mode None -StopAfterPreflight $false
    foreach($mode in @('RootSuccess','StartFailure')){
        $caught=$null
        try {Assert-CiCwdProbeTestMode -Mode $mode -StopAfterPreflight $false}catch{$caught=$_.Exception.Message}
        $ok=$caught -like 'M6_CI_CWD_TEST_REQUIRES_PREFLIGHT_ONLY*'
        $results.Add([ordered]@{name="reject-unbounded-adapter-$mode";passed=$ok;message=$caught;childStarted=$false;guardSource=$runner;guardSourceSha256=(Get-FileHash -LiteralPath $runner).Hash})
        if(-not $ok){throw 'M6_CI_PREFLIGHT_UNBOUNDED_ADAPTER_ACCEPTED'}
    }
    foreach($scenario in @([pscustomobject]@{name='forced-success-bounded';mode='RootSuccess';success=$true},[pscustomobject]@{name='controlled-start-failure-bounded';mode='StartFailure';success=$false},[pscustomobject]@{name='real-short-preflight-only';mode='None';success=$true})){
        $kind=$scenario.name
        $target=Join-Path $project '.superpowers/verification'
        $info=[Diagnostics.ProcessStartInfo]::new((Join-Path $PSHOME 'pwsh.exe'))
        foreach($argument in @('-NoProfile','-NonInteractive','-File',$runner,'-EvidenceRoot',$target,'-PreflightOnly','-CiCwdProbeTestMode',$scenario.mode)){$info.ArgumentList.Add($argument)}
        $info.WorkingDirectory=$project;$info.UseShellExecute=$false;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $rootsBefore=@(if(Test-Path -LiteralPath $target){Get-ChildItem -LiteralPath $target -Directory -Filter 'run-all-*' | ForEach-Object FullName})
        # 所有child在guard後停止；不再以某種路徑必定拒絕作安全前提。
        $process=[Diagnostics.Process]::Start($info)
        $entryPids.Add($process.Id);$entryTargets.Add($target)
        $out=$process.StandardOutput.ReadToEndAsync();$err=$process.StandardError.ReadToEndAsync()
        # 150秒僅為process-only收斂界線（11×10秒＋40秒bootstrap），安全本體是PreflightOnly。
        if(-not $process.WaitForExit(150000)){
            $observedRoots=@(if(Test-Path -LiteralPath $target){Get-ChildItem -LiteralPath $target -Directory -Filter 'run-all-*' | Where-Object {$_.FullName -notin $rootsBefore} | ForEach-Object FullName})
            [ordered]@{kind=$kind;pid=$process.Id;startTimeUtc=([DateTimeOffset]$process.StartTime).ToUniversalTime().ToString('o');evidenceRoot=$target;observedRunRoots=$observedRoots;arguments=@($info.ArgumentList);waitLimitSeconds=150;killed=$false;nextRuntimeAllowed=$false} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence "$kind-timeout.json") -Encoding utf8
            throw "M6_CI_PREFLIGHT_SELFTEST_TIMEOUT：pid=$($process.Id)；保留現場，確認owned helper收斂前禁止下一runtime"
        }
        $stdout=$out.GetAwaiter().GetResult();$stderr=$err.GetAwaiter().GetResult();$exit=$process.ExitCode
        [IO.File]::WriteAllText((Join-Path $evidence "$kind.stdout.log"),$stdout)
        [IO.File]::WriteAllText((Join-Path $evidence "$kind.stderr.log"),$stderr)
        $roots=@([regex]::Matches($stdout,'(?m)^RUN_ROOT=(.+)\r?$') | ForEach-Object {$_.Groups[1].Value.Trim()})
        if($roots.Count -ne 1){throw "M6_CI_PREFLIGHT_TEST_FAILED：root不唯一 kind=$kind"}
        $receipt=Get-Content -LiteralPath (Join-Path $roots[0] 'ci-cwd-preflight.json') -Raw | ConvertFrom-Json
        $noRuntime=$stdout -notmatch '(?m)^BEGIN ' -and @($receipt.worldOperations).Count -eq 0 -and -not $receipt.gradleStarted
        $modeExact=$receipt.testMode -ceq $scenario.mode
        $ok=if($scenario.success){$exit -eq 0 -and $receipt.status -ceq 'PASS' -and @($receipt.rows).Count -eq 11 -and $stdout.Contains('END PREFLIGHT exit=0')}
            else{$exit -ne 0 -and $receipt.status -ceq 'FAIL' -and ($stdout+$stderr).Contains('M6_CI_CWD_UNSUPPORTED') -and @($receipt.rows).Count -eq 1 -and -not $receipt.rows[0].started -and $null -ne $receipt.rows[0].nativeErrorCode -and -not $receipt.rows[0].effectiveCwdExisted -and -not $stdout.Contains('END ALL')}
        if($scenario.mode -ceq 'RootSuccess'){$ok=$ok -and @($receipt.rows | Where-Object {-not $_.effectiveCwdExisted -or $_.effectiveWorkingDirectory -cne [IO.Path]::GetPathRoot($project) -or -not $_.started -or $_.exitCode -ne 0}).Count -eq 0 -and $receipt.layoutEvidence -ceq 'CONTROLLED_TRANSPORT_ONLY'}
        if($scenario.mode -ceq 'None'){$ok=$ok -and @($receipt.rows | Where-Object {$_.requestedWorkingDirectory -cne $_.effectiveWorkingDirectory}).Count -eq 0 -and $receipt.layoutEvidence -ceq 'REAL_PROJECTED_CWD'}
        $results.Add([ordered]@{name=$kind;passed=[bool]($ok -and $noRuntime -and $modeExact);exitCode=$exit;pid=$process.Id;runRoot=$roots[0];preflightOnly=$true;testMode=$scenario.mode;noRuntime=$noRuntime;receiptSha256=(Get-FileHash -LiteralPath (Join-Path $roots[0] 'ci-cwd-preflight.json')).Hash})
        $process.Dispose()
        if(-not $ok -or -not $noRuntime -or -not $modeExact){throw "M6_CI_PREFLIGHT_TEST_FAILED：kind=$kind exit=$exit"}
    }
    $afterJava=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select-Object ProcessId,ParentProcessId,CreationDate,CommandLine)
    $afterExists=Test-Path -LiteralPath $runPath
    $afterRun=@(if($afterExists){Get-ChildItem -LiteralPath $runPath -Force | ForEach-Object Name | Sort-Object})
    # 舊daemon可以退出；無關Java變動只記錄，不能冒稱是本case副作用。
    $ownedJava=@($afterJava | Where-Object {
        $candidate=$_
        $candidate.ParentProcessId -in $entryPids -or @($entryTargets | Where-Object {$candidate.CommandLine -and $candidate.CommandLine.Contains($_)}).Count -gt 0
    })
    $sameRun=$beforeExists -eq $afterExists -and ($beforeRun -join '|') -ceq ($afterRun -join '|')
    $results.Add([ordered]@{name='process-only-no-world-operations';passed=($ownedJava.Count -eq 0 -and $sameRun);javaBefore=$beforeJava;javaAfter=$afterJava;ownedJava=$ownedJava;entryPids=@($entryPids.ToArray());runExistedBefore=$beforeExists;runExistsAfter=$afterExists;runNamesUnchanged=$sameRun})
    if($ownedJava.Count -or -not $sameRun){throw 'M6_CI_PREFLIGHT_TEST_FAILED：本case有Java owner或run目錄改變'}
}

function Invoke-OptionalProbePropertyChecks {
    foreach($prefix in @('m4Recovery','m5Collapse')){
        $context=[pscustomobject]@{InitScript='C:/fixture with spaces/runtime.init.gradle';Task=$(if($prefix -ceq 'm4Recovery'){'runM4Recovery'}else{'runM5Collapse'});PropertyPrefix=$prefix}
        $expected=@('-I','C:/fixture with spaces/runtime.init.gradle',$context.Task,"-P${prefix}Nonce=nonce-fixed","-P${prefix}Phase=baseline","-P${prefix}StartupNonce=0123456789abcdef0123456789abcdef","-P${prefix}Root=C:/fixture with spaces/root",'--console=plain')
        $actual=@(Get-OwnedPhaseArguments -Context $context -Nonce nonce-fixed -Phase baseline -Root 'C:/fixture with spaces/root' -StartupNonce 0123456789abcdef0123456789abcdef)
        $expectedBytes=[Text.Encoding]::UTF8.GetBytes([string]::Join([char]0,$expected));$actualBytes=[Text.Encoding]::UTF8.GetBytes([string]::Join([char]0,$actual))
        $exact=[Convert]::ToHexString($expectedBytes) -ceq [Convert]::ToHexString($actualBytes)
        $results.Add([ordered]@{name="$prefix-byte-exact";passed=$exact;arguments=$actual;utf8Hex=[Convert]::ToHexString($actualBytes)})
        if(-not $exact){throw "M6_OPTIONAL_PROPERTIES_REGRESSION：$prefix"}
    }
    $context=[pscustomobject]@{InitScript='init';Task='runM6UniverseProbe';PropertyPrefix='m6Universe'}
    $actual=@(Get-OwnedPhaseArguments -Context $context -Nonce n -Phase baseline -Root root -StartupNonce startup -ExtraProperties ([ordered]@{Chain='native-seed-diagnostic';ExpectedMode='DIAGNOSTIC'}))
    $exact=($actual[-3..-1] -join '|') -ceq '-Pm6UniverseChain=native-seed-diagnostic|-Pm6UniverseExpectedMode=DIAGNOSTIC|--console=plain'
    $results.Add([ordered]@{name='m6-optional-pair';passed=$exact;arguments=$actual})
    if(-not $exact){throw 'M6_OPTIONAL_PROPERTIES_REGRESSION：M6配對'}
    foreach($extras in @([ordered]@{Chain='x'},[ordered]@{Chain='x';Unknown='y'},[ordered]@{Chain='';ExpectedMode='DIAGNOSTIC'})){
        $caught=$null
        try {Get-OwnedPhaseArguments -Context $context -Nonce n -Phase baseline -Root root -StartupNonce startup -ExtraProperties $extras | Out-Null}catch{$caught=$_.Exception.Message}
        $ok=$caught -like 'M6_EXTRA_PROPERTIES_*';$results.Add([ordered]@{name='reject-malformed-extra';passed=$ok;message=$caught})
        if(-not $ok){throw 'M6_OPTIONAL_PROPERTIES_REJECTION_MISSING'}
    }
    foreach($arguments in @(@{Chain='unknown'},@{ExpectedMode='FUNCTIONAL'},@{AllEnabled=$true;Chain='native-seed-diagnostic'})){
        $caught=$null
        try {& (Join-Path $PSScriptRoot 'm6-universe-probe.ps1') @arguments | Out-Null}catch{$caught=$_.Exception.Message}
        $ok=$caught -match '^M6_(CHAIN_UNKNOWN|EXPECTED_MODE_MIXED|CHAIN_SELECTION_MIXED)'
        $results.Add([ordered]@{name='reject-chain-mode-before-runtime';passed=$ok;message=$caught})
        if(-not $ok){throw 'M6_CHAIN_MODE_REJECTION_MISSING'}
    }
}

try {
    if ($Case -eq 'optional-probe-properties') { Invoke-OptionalProbePropertyChecks } elseif ($Case -eq 'ci-cwd-preflight') { Invoke-CiCwdPreflightChecks } elseif ($Case -eq 'move-fresh') { Invoke-MoveFreshChecks } else {
    Invoke-Fixture 'normal-existing-run' 'existing' $false
    Invoke-Fixture 'normal-fresh-run' 'fresh' $false
    Invoke-Fixture 'reject-run-junction' 'run-junction' $true
    Invoke-Fixture 'reject-project-ancestor-junction' 'ancestor-junction' $true
    Invoke-Fixture 'reject-noncanonical-project' 'noncanonical' $true
    Invoke-Fixture 'reject-relative-project' 'relative' $true
    Invoke-Fixture 'reject-escaping-prefix' 'escape-prefix' $true
    Invoke-Fixture 'reject-escaping-suffix' 'escape-suffix' $true
    }
} catch { $failure = $_.Exception.Message }
[ordered]@{case=$Case;label=$Label;head=$head;evidence=$evidence;passed=($null -eq $failure);failure=$failure;results=@($results.ToArray())} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'receipt.json') -Encoding UTF8
Write-Output "EVIDENCE=$evidence HEAD=$head"
if ($failure) { throw $failure }
Write-Output "PASS $Case cases=$($results.Count) HEAD=$head"
