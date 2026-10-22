# 具名 south cohort 的開發驗證入口；原 GameTest FAIL 與獨立oracle結論分開保存。
#Requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('partial-exception','timeout-false','slow-old10','slow-new60','request-fault','witness-fault','late-crosswall')][string]$Scenario,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label='local',
    [string]$EvidenceRoot='',
    [string]$Gradle='',
    [switch]$PrepareOnly,
    [switch]$OracleOnly,
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
if($PrepareOnly -and $OracleOnly){throw 'PrepareOnly與OracleOnly互斥'}
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
Write-Output "FIXTURE_ROOT=$fixture"
if($PrepareOnly){Write-Output 'PREPARED_ONLY=true';return}
$gateLabel="m2cohort-$Label-$($nonce.Substring(0,8))"
Save-Json (Join-Path $fixture 'active.json') ([ordered]@{head=$head;scenario=$Scenario;gateLabel=$gateLabel;evidenceRoot=$EvidenceRoot;sourceSha256=$originalSha;sourceBlob=$originalBlob})
$gateError=$null
try {
    [IO.File]::WriteAllBytes($source,[IO.File]::ReadAllBytes($mutation))
    try {
        $args=@{Mode='GameTest';Label=$gateLabel;EvidenceRoot=$EvidenceRoot}
        if($Gradle){$args.Gradle=$Gradle}
        & $gate @args *> (Join-Path $fixture 'gate-console.txt')
    } catch {$gateError=$_.ToString()}
} finally {
    [IO.File]::WriteAllBytes($source,$original)
    $afterSha=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $afterBlob=(& git -C $repo hash-object -- $source).Trim()
    $afterStatus=@(git -C $repo status --porcelain=v1 -- 'src/testmod/java/dev/quantumchamber/gametest/M2CorridorGameTests.java')
    $restored=$afterSha -ceq $originalSha -and $afterBlob -ceq $originalBlob -and $afterStatus.Count -eq 0
    Save-Json (Join-Path $fixture 'restore.json') ([ordered]@{sourceRestored=$restored;afterSha256=$afterSha;afterBlob=$afterBlob;afterPorcelain=@($afterStatus);gateError=$gateError})
    if(-not $restored){throw "source原bytes/blob還原失敗，停止：$fixture"}
}
$roots=@(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Filter "gate-$gateLabel-*")
if($roots.Count -ne 1){throw "正式gate root數量$($roots.Count)，fixture=$fixture"}
$gateRoot=$roots[0].FullName
Save-Json (Join-Path $fixture 'gate-root.json') ([ordered]@{gateRoot=$gateRoot;gateLabel=$gateLabel;head=$head;scenario=$Scenario})
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
