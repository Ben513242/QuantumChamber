# 具名 south cohort 的開發驗證入口；原 GameTest FAIL 與獨立oracle結論分開保存。
#Requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('partial-exception','timeout-false','slow-old10','slow-new60','request-fault','witness-fault','late-crosswall')][string]$Scenario,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label='local',
    [string]$EvidenceRoot='',
    [string]$Gradle='',
    [switch]$PrepareOnly,
    [switch]$OracleOnly,
    [switch]$SourceGuardSelfTest,
    [string]$GateRoot='',
    [string]$FixtureRoot='',
    [string]$OracleOutput=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source=Join-Path $repo 'src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java'
$adapter=Join-Path $PSScriptRoot 'm2-cohort-failure-adapter.ps1'
$oracle=Join-Path $PSScriptRoot 'm2-cohort-failure-oracle.py'
$gate=Join-Path $PSScriptRoot 'gates.ps1'
$readme=Join-Path $PSScriptRoot 'README.md'
foreach($path in @($source,$adapter,$oracle,$gate)){if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "缺少正式檔案：$path"}}
if(@(@($PrepareOnly,$OracleOnly,$SourceGuardSelfTest) | Where-Object {$_}).Count -gt 1){throw 'PrepareOnly／OracleOnly／SourceGuardSelfTest互斥'}
function Checked-Path([string]$value) {
    $full=[IO.Path]::GetFullPath($value)
    if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "路徑不在本worktree：$full"}
    $cursor=$full
    while($cursor){
        if(Test-Path -LiteralPath $cursor){
            $item=Get-Item -LiteralPath $cursor -Force
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "拒絕reparse：$cursor"}
        }
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if($parent -eq $cursor){break};$cursor=$parent
    }
    return $full
}
function Save-Json([string]$path,$data){$data|ConvertTo-Json -Depth 12|Set-Content -LiteralPath $path -Encoding utf8}
function Bytes-Sha([byte[]]$bytes){return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()}
function Bytes-Equal([byte[]]$left,[byte[]]$right){return [System.Collections.StructuralComparisons]::StructuralEqualityComparer.Equals($left,$right)}
function Guarded-SourceTransition([string]$path,[byte[]]$expected,[byte[]]$desired,[string]$phase,[string]$evidence,[bool]$allowDesired) {
    $full=Checked-Path $path
    # 比較與寫入共用一個file handle；其期間只允許別人讀，不允許寫入／替換。
    $stream=[IO.FileStream]::new($full,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    try {
        if((Checked-Path $full) -cne $full){throw "source路徑在開啟期間改變：$full"}
        if($stream.Length -gt [int]::MaxValue){throw "source過大：$full"}
        $observed=New-Object byte[] ([int]$stream.Length)
        $offset=0
        while($offset -lt $observed.Length){
            $count=$stream.Read($observed,$offset,$observed.Length-$offset)
            if($count -le 0){throw "source讀取未完成：$full"}
            $offset+=$count
        }
        $observedSha=Bytes-Sha $observed
        if($allowDesired -and (Bytes-Equal $observed $desired)){
            return [pscustomobject]@{phase=$phase;status='ALREADY_BASELINE';wrote=$false;observedSha256=$observedSha;observedCopy=$null}
        }
        if(-not(Bytes-Equal $observed $expected)){
            $copy=Join-Path $evidence "source-observed-$phase.bin"
            if(Test-Path -LiteralPath $copy){throw "衝突見證已存在：$copy"}
            [IO.File]::WriteAllBytes($copy,$observed)
            return [pscustomobject]@{phase=$phase;status='CONFLICT';wrote=$false;observedSha256=$observedSha;observedCopy=$copy}
        }
        $stream.Position=0
        $stream.Write($desired,0,$desired.Length)
        $stream.SetLength($desired.Length)
        $stream.Flush($true)
        return [pscustomobject]@{phase=$phase;status=($(if($phase -ceq 'apply'){'APPLIED'}else{'RESTORED'}));wrote=$true;observedSha256=$observedSha;observedCopy=$null}
    } finally {$stream.Dispose()}
}
function Complete-SourceRun([string]$path,[byte[]]$baseline,[byte[]]$mutation,[string]$fixture,[string]$applyState,$applyError,$gateError,$applyGuard) {
    $restoreGuard=$null;$restoreError=$null
    if($applyState -in @('APPLIED','ATTEMPTED')){
        try {$restoreGuard=Guarded-SourceTransition $path $mutation $baseline 'restore' $fixture $true}
        catch {$restoreError=$_.ToString()}
    }
    $afterSha=$null;$afterBlob=$null;$afterStatus=@();$observeError=$null
    try {
        $checked=Checked-Path $path
        if(Test-Path -LiteralPath $checked -PathType Leaf){
            $afterSha=(Get-FileHash -LiteralPath $checked -Algorithm SHA256).Hash.ToLowerInvariant()
            $afterBlob=(& git -C $repo hash-object -- $checked).Trim()
        } else {$observeError='source不存在'}
        if($checked -ceq $source){$afterStatus=@(git -C $repo status --porcelain=v1 -- 'src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java')}
    } catch {$observeError=$_.ToString()}
    $originalSha=Bytes-Sha $baseline
    $mutationSha=Bytes-Sha $mutation
    $restored=($applyState -ceq 'APPLIED' -and $null -ne $restoreGuard -and
        $restoreGuard.status -in @('RESTORED','ALREADY_BASELINE') -and -not $restoreError -and -not $observeError -and
        $afterSha -ceq $originalSha -and $afterStatus.Count -eq 0)
    $receipt=[ordered]@{
        sourceRestored=$restored;baselineSha256=$originalSha;mutationSha256=$mutationSha
        applyState=$applyState;applyGuard=$applyGuard;applyError=$applyError
        restoreGuard=$restoreGuard;restoreError=$restoreError;observeError=$observeError
        afterSha256=$afterSha;afterBlob=$afterBlob;afterPorcelain=@($afterStatus);gateError=$gateError
    }
    Save-Json (Join-Path $fixture 'restore.json') $receipt
    return [pscustomobject]$receipt
}
$python=Get-Command python -ErrorAction SilentlyContinue
if($null -eq $python){throw '找不到Python 3.8+；本fixture只使用標準函式庫，不下載依賴'}
$pythonPath=$python.Source
$versionText=(& $pythonPath --version 2>&1 | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $versionText -notmatch '^Python\s+(\d+)\.(\d+)\.(\d+)') {throw "無法確認Python版本：$versionText"}
if([int]$Matches[1] -ne 3 -or [int]$Matches[2] -lt 8){throw "至少需要Python 3.8：$versionText"}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo '.superpowers/verification/m2-cohort-failure'}
$EvidenceRoot=Checked-Path $EvidenceRoot
git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$EvidenceRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw "證據根未被gitignore：$EvidenceRoot"}
if(-not(Test-Path -LiteralPath $EvidenceRoot)){New-Item -ItemType Directory -Path $EvidenceRoot|Out-Null}
if($SourceGuardSelfTest){
    $testRoot=Checked-Path (Join-Path $EvidenceRoot ('source-guard-selftest-'+[Guid]::NewGuid().ToString('N')))
    New-Item -ItemType Directory -Path $testRoot|Out-Null
    $baseline=[Text.Encoding]::UTF8.GetBytes("baseline`r`n")
    $changed=[Text.Encoding]::UTF8.GetBytes("mutation`r`n")
    $outside=[Text.Encoding]::UTF8.GetBytes("external-edit`r`n")
    $checks=[ordered]@{}
    function New-TestCase([string]$name,[byte[]]$bytes){
        $dir=Join-Path $testRoot $name
        New-Item -ItemType Directory -Path $dir|Out-Null
        $path=Join-Path $dir 'disposable-source.java'
        [IO.File]::WriteAllBytes($path,$bytes)
        return $path
    }
    $path=New-TestCase 'normal' $baseline
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status $null $null $guard
    $checks.normal=$guard.status -ceq 'APPLIED' -and $receipt.sourceRestored -and (Bytes-Equal ([IO.File]::ReadAllBytes($path)) $baseline)
    $path=New-TestCase 'changed-before-apply' $baseline
    [IO.File]::WriteAllBytes($path,$outside)
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status 'source在套用前已改變' $null $guard
    $checks.preApplyConflict=$guard.status -ceq 'CONFLICT' -and -not $receipt.sourceRestored -and (Bytes-Equal ([IO.File]::ReadAllBytes($path)) $outside)
    $path=New-TestCase 'changed-before-restore' $baseline
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    [IO.File]::WriteAllBytes($path,$outside)
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status $null 'GameTest gate exit=1' $guard
    $copy=Join-Path (Split-Path $path) 'source-observed-restore.bin'
    $checks.restoreConflict=$receipt.restoreGuard.status -ceq 'CONFLICT' -and -not $receipt.sourceRestored -and
        $receipt.gateError -ceq 'GameTest gate exit=1' -and (Bytes-Equal ([IO.File]::ReadAllBytes($path)) $outside) -and
        (Bytes-Equal ([IO.File]::ReadAllBytes($copy)) $outside)
    $path=New-TestCase 'already-baseline' $baseline
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    [IO.File]::WriteAllBytes($path,$baseline)
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status $null $null $guard
    $checks.alreadyBaseline=$receipt.restoreGuard.status -ceq 'ALREADY_BASELINE' -and -not $receipt.restoreGuard.wrote -and $receipt.sourceRestored
    $path=New-TestCase 'missing-before-restore' $baseline
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    Remove-Item -LiteralPath $path
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status $null 'GameTest gate exit=1' $guard
    $checks.missingPreserved=-not $receipt.sourceRestored -and $null -ne $receipt.restoreError -and -not(Test-Path -LiteralPath $path) -and $receipt.gateError -ceq 'GameTest gate exit=1'
    $path=New-TestCase 'read-only-before-restore' $baseline
    $guard=Guarded-SourceTransition $path $baseline $changed 'apply' (Split-Path $path) $false
    [IO.File]::SetAttributes($path,[IO.FileAttributes]::ReadOnly)
    $receipt=Complete-SourceRun $path $baseline $changed (Split-Path $path) $guard.status $null $null $guard
    $checks.readOnlyPreserved=-not $receipt.sourceRestored -and $null -ne $receipt.restoreError -and (Bytes-Equal ([IO.File]::ReadAllBytes($path)) $changed)
    Save-Json (Join-Path $testRoot 'selftest.json') ([ordered]@{head=(& git -C $repo rev-parse HEAD).Trim();checks=$checks;passed=(@($checks.Values|Where-Object {-not $_}).Count -eq 0)})
    if(@($checks.Values|Where-Object {-not $_}).Count){throw "source guard selftest FAIL：$testRoot"}
    Write-Output "SOURCE_GUARD_SELFTEST_ROOT=$testRoot"
    Write-Output 'SOURCE_GUARD_SELFTEST=PASS'
    return
}
if($OracleOnly){
    if(-not $GateRoot -or -not $FixtureRoot -or -not $OracleOutput){throw 'OracleOnly需GateRoot、FixtureRoot、OracleOutput'}
    $GateRoot=Checked-Path $GateRoot;$FixtureRoot=Checked-Path $FixtureRoot;$OracleOutput=Checked-Path $OracleOutput
    if(Test-Path -LiteralPath $OracleOutput){throw "拒絕覆寫oracle：$OracleOutput"}
    & $pythonPath -X utf8 $oracle --scenario $Scenario --gate $GateRoot --fixture $FixtureRoot --output $OracleOutput | Out-Null
    if($LASTEXITCODE -ne 0){throw "獨立oracle執行失敗：$OracleOutput"}
    $result=Get-Content -LiteralPath $OracleOutput -Raw|ConvertFrom-Json -DateKind String
    Write-Output "ORIGINAL_GATE_EXIT=$($result.originalGateExit)"
    Write-Output "ORACLE_VERDICT=$($result.oracleVerdict)"
    Write-Output "ORACLE_OUTPUT=$OracleOutput"
    if($result.oracleVerdict -cne 'PASS'){throw "獨立oracle拒絕，原gate結果不改：$OracleOutput"}
    return
}
$source=Checked-Path $source
$sourceStatus=@(git -C $repo status --porcelain=v1 -- 'src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java')
if($sourceStatus.Count){throw "source已dirty，不套用暫時adapter：$($sourceStatus -join ',')"}
$head=(& git -C $repo rev-parse HEAD).Trim()
$expectedBlob=(& git -C $repo rev-parse 'HEAD:src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java').Trim()
$originalBlob=(& git -C $repo hash-object -- $source).Trim()
if($originalBlob -cne $expectedBlob){throw "source bytes與HEAD blob不同：$originalBlob != $expectedBlob"}
$original=[IO.File]::ReadAllBytes($source)
$originalSha=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$scriptPins=[ordered]@{}
foreach($item in @(@('fixture','m2-cohort-failure-fixture.ps1'),@('adapter','m2-cohort-failure-adapter.ps1'),@('oracle','m2-cohort-failure-oracle.py'),@('readme','README.md'))){
    $path=Join-Path $PSScriptRoot $item[1]
    $scriptPins[$item[0]]=[ordered]@{path=$item[1];sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
}
$nonce=[Guid]::NewGuid().ToString('N')
$fixture=Checked-Path (Join-Path $EvidenceRoot "fixture-$Scenario-$Label-$nonce")
if(Test-Path -LiteralPath $fixture){throw "fixture碰撞：$fixture"}
New-Item -ItemType Directory -Path $fixture|Out-Null
[IO.File]::WriteAllBytes((Join-Path $fixture 'source-original.bin'),$original)
$pinRoot=Join-Path $fixture 'verification-sources'
New-Item -ItemType Directory -Path $pinRoot|Out-Null
foreach($item in @(@('fixture','m2-cohort-failure-fixture.ps1'),@('adapter','m2-cohort-failure-adapter.ps1'),@('oracle','m2-cohort-failure-oracle.py'),@('readme','README.md'))){
    [IO.File]::WriteAllBytes((Join-Path $pinRoot $item[1]),[IO.File]::ReadAllBytes((Join-Path $PSScriptRoot $item[1])))
}
$mutation=Join-Path $fixture 'source-mutated.bin'
$adapterReport=Join-Path $fixture 'adapter.json'
Save-Json (Join-Path $fixture 'setup.json') ([ordered]@{scenario=$Scenario;label=$Label;head=$head;branch=(& git -C $repo branch --show-current).Trim();python=$versionText;source=$source;sourceSha256=$originalSha;sourceBlob=$originalBlob;expectedBlob=$expectedBlob;scriptPins=$scriptPins;prepareOnly=[bool]$PrepareOnly})
& $adapter -Scenario $Scenario -Source $source -Output $mutation -Report $adapterReport
if(-not(Test-Path -LiteralPath $mutation -PathType Leaf)){throw 'adapter未產出暫時source'}
$adapterState=Get-Content -LiteralPath $adapterReport -Raw|ConvertFrom-Json -DateKind String
if(-not $adapterState.nonInjectedPredicateKept -or -not $adapterState.ordinaryWhen10SecondsKept){throw 'adapter未保持非注入predicate或普通10秒'}
$mutated=[IO.File]::ReadAllBytes($mutation)
Write-Output "FIXTURE_ROOT=$fixture"
if($PrepareOnly){Write-Output 'PREPARED_ONLY=true';return}
$gateLabel="m2cohort-$Label-$($nonce.Substring(0,8))"
Save-Json (Join-Path $fixture 'active.json') ([ordered]@{head=$head;scenario=$Scenario;gateLabel=$gateLabel;evidenceRoot=$EvidenceRoot;sourceSha256=$originalSha;sourceBlob=$originalBlob;mutationSha256=(Bytes-Sha $mutated)})
$gateError=$null;$applyError=$null;$applyGuard=$null;$applyState='NOT_STARTED';$final=$null
try {
    $applyState='ATTEMPTED'
    try {$applyGuard=Guarded-SourceTransition $source $original $mutated 'apply' $fixture $false;$applyState=$applyGuard.status}
    catch {$applyError=$_.ToString()}
    if($applyState -ceq 'APPLIED'){
        $args=@{Mode='GameTest';Label=$gateLabel;EvidenceRoot=$EvidenceRoot}
        if($Gradle){$args.Gradle=$Gradle}
        try {& $gate @args *> (Join-Path $fixture 'gate-console.txt')}
        catch {$gateError=$_}
    } elseif(-not $applyError){$applyError="source套用拒絕：$($applyGuard.status)"}
} finally {
    $gateErrorText=if($null -ne $gateError){$gateError.ToString()}else{$null}
    $final=Complete-SourceRun $source $original $mutated $fixture $applyState $applyError $gateErrorText $applyGuard
}
if(-not $final.sourceRestored){
    $guardFailure=[InvalidOperationException]::new("source guard拒絕或無法還原；apply=$($final.applyError) restore=$($final.restoreError) observe=$($final.observeError) root=$fixture")
    if($null -ne $gateError){throw [AggregateException]::new('原gate錯與source guard錯均保留',[Exception[]]@($gateError.Exception,$guardFailure))}
    throw $guardFailure
}
$roots=@(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Filter "gate-$gateLabel-*")
if($roots.Count -ne 1){throw "正式gate root數量$($roots.Count)，fixture=$fixture"}
$gateRoot=$roots[0].FullName
Save-Json (Join-Path $fixture 'gate-root.json') ([ordered]@{gateRoot=$gateRoot;gateLabel=$gateLabel;head=$head;scenario=$Scenario})
$expectedPass=$Scenario -ceq 'slow-new60'
$expectedError=if($expectedPass){$null}else{'GameTest gate exit=1'}
$formalError=$null;$gateReceipt=$null;$execution=$null;$executionExit=$null;$formalGateOk=$false
try {
    $gateReceipt=Get-Content -LiteralPath (Join-Path $gateRoot 'gate.json') -Raw|ConvertFrom-Json -AsHashtable -DateKind String
    $execution=Get-Content -LiteralPath (Join-Path $gateRoot 'execution/execution.json') -Raw|ConvertFrom-Json -AsHashtable -DateKind String
    foreach($key in @('mode','status','evidence')){if($null -eq $gateReceipt -or $gateReceipt.Keys -notcontains $key){throw "gate.json缺少$key"}}
    if($expectedPass -and $gateReceipt.Keys -contains 'error'){throw 'PASS gate.json不應有error欄位'}
    if(-not $expectedPass -and $gateReceipt.Keys -notcontains 'error'){throw 'FAIL gate.json缺少error欄位'}
    if($null -eq $execution -or $execution.Keys -notcontains 'exitCode'){throw 'execution.json缺少exitCode'}
    $executionExit=$execution['exitCode']
    if($executionExit -isnot [int] -and $executionExit -isnot [long]){throw 'execution exitCode型別錯誤'}
    if($gateReceipt['evidence'] -isnot [string] -or -not $gateReceipt['evidence']){throw 'gate.json evidence路徑無效'}
    $formalGateOk=($gateReceipt['mode'] -ceq 'GameTest' -and
        $gateReceipt['status'] -ceq $(if($expectedPass){'PASS'}else{'FAIL'}) -and
        $(if($expectedPass){$true}else{$gateReceipt['error'] -ceq $expectedError}) -and
        $gateErrorText -ceq $expectedError -and
        $executionExit -eq $(if($expectedPass){0}else{1}) -and
        [IO.Path]::GetFullPath($gateReceipt['evidence']) -ceq $gateRoot)
} catch {$formalError=$_.ToString()}
Save-Json (Join-Path $fixture 'gate-control.json') ([ordered]@{accepted=$formalGateOk;expectedPass=$expectedPass;expectedError=$expectedError;gateError=$gateErrorText;parseError=$formalError;gateReceipt=$gateReceipt;executionExit=$executionExit;gateRoot=$gateRoot})
if(-not $formalGateOk){
    $controlError=[InvalidOperationException]::new("正式gate結果不符預期：$fixture")
    if($null -ne $gateError){throw [AggregateException]::new('原gate錯與正式gate裁決均保留',[Exception[]]@($gateError.Exception,$controlError))}
    throw $controlError
}
$oracleOutput=Join-Path $fixture 'oracle.json'
& $pythonPath -X utf8 $oracle --scenario $Scenario --gate $gateRoot --fixture $fixture --output $oracleOutput | Out-Null
if($LASTEXITCODE -ne 0){throw "獨立oracle執行失敗：$oracleOutput"}
$result=Get-Content -LiteralPath $oracleOutput -Raw|ConvertFrom-Json -DateKind String
Save-Json (Join-Path $fixture 'run-result.json') ([ordered]@{head=$head;scenario=$Scenario;gateRoot=$gateRoot;gateError=$gateError;originalGateExit=$result.originalGateExit;oracleVerdict=$result.oracleVerdict;sourceRestored=$true})
Write-Output "GATE_ROOT=$gateRoot"
Write-Output "ORIGINAL_GATE_EXIT=$($result.originalGateExit)"
Write-Output "ORACLE_VERDICT=$($result.oracleVerdict)"
Write-Output "ORACLE_OUTPUT=$oracleOutput"
if($result.oracleVerdict -cne 'PASS'){throw "獨立oracle未通過；原gate verdict不變：$fixture"}
