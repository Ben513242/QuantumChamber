param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Label)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$owner = Join-Path $repo '.superpowers/sdd/2026-10-01-m5-collapse-passage/f7/step0'
$root = Join-Path $owner ("source-init-$Label-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null
$source = Join-Path $PSScriptRoot 'source-remap-check.ps1'
$results = @()
foreach ($scenario in @('fresh','existing','reparse','outside')) {
    $fixture = Join-Path $root $scenario
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'scripts/verification')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $fixture 'mappings')) | Out-Null
    foreach ($relative in @('build.gradle','mappings/quantumchamber-codec-identity.tiny','scripts/verification/SourceRemapOracle.java','scripts/verification/source-remap-check.ps1')) {
        Copy-Item -LiteralPath (Join-Path $repo $relative) -Destination (Join-Path $fixture $relative)
    }
    @('@echo off','echo SOURCE_REMAP_ORACLE PASS: stub','echo BUILD SUCCESSFUL','echo launched>gradle-started.txt','exit /b 0') |
        Set-Content -LiteralPath (Join-Path $fixture 'gradlew.bat') -Encoding ascii
    $ignored = Join-Path $fixture '.superpowers'
    if ($scenario -ceq 'existing') {
        [IO.Directory]::CreateDirectory($ignored) | Out-Null
        'keep' | Set-Content -LiteralPath (Join-Path $ignored 'marker.txt')
    }
    if ($scenario -ceq 'reparse') {
        $target = Join-Path $fixture 'junction-target'
        [IO.Directory]::CreateDirectory($target) | Out-Null
        New-Item -ItemType Junction -Path $ignored -Target $target | Out-Null
    }
    $args = @('-NoProfile','-File',(Join-Path $fixture 'scripts/verification/source-remap-check.ps1'),'-Label',"$Label-$scenario")
    if ($scenario -ceq 'outside') { $args += @('-EvidenceRoot','outside') }
    $output = @(& pwsh @args 2>&1 | ForEach-Object { "$_" })
    $exit = $LASTEXITCODE
    $output | Set-Content -LiteralPath (Join-Path $fixture 'console.log') -Encoding utf8
    $launched = Test-Path -LiteralPath (Join-Path $fixture 'gradle-started.txt')
    $present = Test-Path -LiteralPath $ignored
    $markerKept = Test-Path -LiteralPath (Join-Path $ignored 'marker.txt')
    $message = $output -join "`n"
    $pass = switch ($scenario) {
        'fresh' { $exit -eq 0 -and $present -and $launched -and $message.Contains('GRADLE_EXIT=0') }
        'existing' { $exit -eq 0 -and $markerKept -and $launched -and $message.Contains('GRADLE_EXIT=0') }
        'reparse' { $exit -ne 0 -and -not $launched -and $message.Contains('.superpowers 是 reparse point') }
        'outside' { $exit -ne 0 -and -not $launched -and $message.Contains('EvidenceRoot 必須在本工作樹的 .superpowers 下') }
    }
    $results += [ordered]@{scenario=$scenario;exit=$exit;gradleLaunched=$launched;ignoredPresent=$present;markerKept=$markerKept;pass=$pass;console=(Join-Path $fixture 'console.log')}
}
$receipt = [ordered]@{sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash;results=$results;pass=(@($results | Where-Object {-not $_.pass}).Count -eq 0)}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
Write-Output "ROOT=$root PASS=$($receipt.pass)"
if (-not $receipt.pass) { exit 1 }
