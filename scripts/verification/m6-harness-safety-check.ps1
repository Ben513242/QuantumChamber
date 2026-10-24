# M6 harness 安全檢查：只操作本次唯一 fixture，所有成功與失敗現場都保留。
param([Parameter(Mandatory)][ValidateSet('owned-root','move-fresh')][string]$Case,
      [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'probe-common.ps1')
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$owner = [pscustomobject]@{Project=$project}
$taskDirectory = if ($Case -eq 'owned-root') { 'task-01' } else { 'task-02' }
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
        $results.Add([ordered]@{name=$scenario;passed=[bool]$ok;originalExisted=$originalExisted;exactRestored=[bool]$exactRestored;hashUnchanged=($before -ceq $after);locksAvailable=$locksAvailable;backupPaths=@($backups | ForEach-Object FullName);error=$(if($caught){$caught.Exception.ToString()}else{$null});fixture=$gate})
        if (-not $ok) { throw "MOVE_FRESH_RESTORE_MISSING case=$scenario originalError=$caught backupCount=$($backups.Count)" }
    }
}

try {
    if ($Case -eq 'move-fresh') { Invoke-MoveFreshChecks } else {
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
