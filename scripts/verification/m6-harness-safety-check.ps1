# M6 harness 安全檢查：只操作本次唯一 fixture，所有成功與失敗現場都保留。
param([Parameter(Mandatory)][ValidateSet('owned-root')][string]$Case,
      [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$owner = [pscustomobject]@{Project=$project}
$evidence = Resolve-Evidence -Context $owner -Path (Join-Path $project ".superpowers/sdd/2026-10-24-m6-universe-generation/task-01/$Label-$([guid]::NewGuid().ToString('N'))")
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

try {
    Invoke-Fixture 'normal-existing-run' 'existing' $false
    Invoke-Fixture 'normal-fresh-run' 'fresh' $false
    Invoke-Fixture 'reject-run-junction' 'run-junction' $true
    Invoke-Fixture 'reject-project-ancestor-junction' 'ancestor-junction' $true
    Invoke-Fixture 'reject-noncanonical-project' 'noncanonical' $true
    Invoke-Fixture 'reject-relative-project' 'relative' $true
    Invoke-Fixture 'reject-escaping-prefix' 'escape-prefix' $true
    Invoke-Fixture 'reject-escaping-suffix' 'escape-suffix' $true
} catch { $failure = $_.Exception.Message }
[ordered]@{case=$Case;label=$Label;head=$head;evidence=$evidence;passed=($null -eq $failure);failure=$failure;results=@($results.ToArray())} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'receipt.json') -Encoding UTF8
Write-Output "EVIDENCE=$evidence HEAD=$head"
if ($failure) { throw $failure }
Write-Output "PASS owned-root cases=$($results.Count) HEAD=$head"
