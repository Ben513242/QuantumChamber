# M6 定向入口；每次先驗 oracle 反例與 Move-Fresh／owned-root 回歸，再啟動單一 runtime。
param([Parameter(Mandatory)][ValidateSet('JUnit','GameTest','Probe')][string]$Suite,
      [string]$TestClass='', [string]$Batch='', [string]$Chain='',
      [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label='m6-focused',
      [string]$EvidenceRoot='', [string]$Gradle='', [string]$ExpectedMode='DIAGNOSTIC', [switch]$RequireIndependentSeed)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

if(($Suite -ne 'Probe' -and ($Chain -or $RequireIndependentSeed -or $ExpectedMode -cne 'DIAGNOSTIC')) -or ($Suite -eq 'JUnit' -and $Batch) -or ($Suite -eq 'GameTest' -and $TestClass)){throw 'M6_SCOPE_MISMATCH：參數不屬於選定 suite'}
if($Suite -eq 'Probe' -and ($Batch -or $TestClass)){throw 'M6_SCOPE_MISMATCH：Probe不接受Batch/TestClass'}
if($Batch -and $Batch -notmatch '^m6_universe[\w-]*(,m6_universe[\w-]*)*$'){throw 'M6_SCOPE_MISMATCH：僅接受 M6 batch'}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo '.superpowers/verification'}
$root=Join-Path $EvidenceRoot ("focused-$Label-"+[guid]::NewGuid().ToString('N'))
# CI oracle 在首次建檔前先驗 canonical／reparse／gitignored；gate 再核對自身證據前提。
& (Join-Path $PSScriptRoot 'm6-ci-gametest.ps1') -Platform Windows -SelfTest -OutputRoot (Join-Path $root 'oracle')
foreach($case in @('move-fresh','owned-root')){
    & (Join-Path $PSScriptRoot 'm6-harness-safety-check.ps1') -Case $case -Label "$Label-$case" | Tee-Object -FilePath (Join-Path $root "$case.log")
}
$negative=$null
try {& (Join-Path $PSScriptRoot 'm6-harness-safety-check.ps1') -Case move-fresh -Label "$Label-residual" -InjectResidualEmptyDirectory | Out-File (Join-Path $root 'residual-negative.log')}
catch {$negative=$_.Exception.Message;Add-Content -LiteralPath (Join-Path $root 'residual-negative.log') -Value $negative}
if(-not $negative -or -not $negative.Contains('MOVE_FRESH_RESTORE_MISSING case=normal-absent observedExists=True expectedExists=False')){throw 'M6_ORACLE_SELFTEST_FAILED：殘留空目錄沒有被存在性斷言拒絕'}
$previous=$env:JAVA_TOOL_OPTIONS
try {
    if($Suite -ceq 'Probe'){
        if(-not $Chain){throw 'M6_CHAIN_MISSING'}
        & (Join-Path $PSScriptRoot 'm6-harness-safety-check.ps1') -Case optional-probe-properties -Label "$Label-optional"
        & (Join-Path $PSScriptRoot 'm6-universe-probe.ps1') -Chain $Chain -ExpectedMode $ExpectedMode -RequireIndependentSeed:$RequireIndependentSeed -EvidenceRoot $root -Gradle $Gradle
        Write-Output "M6_FOCUSED PASS diagnostic-only HEAD=$((git -C $repo rev-parse HEAD).Trim()) ROOT=$root"
        return
    }
    $mode=if($Suite -eq 'JUnit'){'M6JUnit'}else{'M6GameTest'}
    # Batch 由 gates 的正式參數接線，避免入口行為分歧。
    & (Join-Path $PSScriptRoot 'gates.ps1') -Mode $mode -TestClass $TestClass -Batch $Batch -Label $Label -EvidenceRoot $root -Gradle $Gradle
    Write-Output "M6_FOCUSED PASS HEAD=$((git -C $repo rev-parse HEAD).Trim()) ROOT=$root"
} finally {$env:JAVA_TOOL_OPTIONS=$previous}
