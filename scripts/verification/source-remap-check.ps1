param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Label,
    [string]$SourcesJar,
    [string]$ReleaseJar,
    [ValidateSet('PASS','FAIL')][string]$Expected = 'PASS',
    [ValidateSet('SOURCE_REMAP_FIELD_REFERENCE_MISMATCH','SOURCE_REMAP_ENTRY_MISSING_OR_DUPLICATE')][string]$ExpectedFailureCode,
    [string]$EvidenceRoot
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gradle = Join-Path $repo $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
$ignored = [IO.Path]::GetFullPath((Join-Path $repo '.superpowers'))
if ($Expected -eq 'FAIL' -and -not $ExpectedFailureCode) { throw '預期 FAIL 時必須指定 ExpectedFailureCode' }
if ($Expected -eq 'PASS' -and $ExpectedFailureCode) { throw 'PASS 不接受 ExpectedFailureCode' }
if ((Get-Item -LiteralPath $repo).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'repo 是 reparse point' }
if ((Get-Item -LiteralPath $ignored).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '.superpowers 是 reparse point' }
$base = if ($EvidenceRoot) { [IO.Path]::GetFullPath((Join-Path $repo $EvidenceRoot)) } else { Join-Path $ignored 'source-remap' }
if (-not $base.StartsWith($ignored + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'EvidenceRoot 必須在本工作樹的 .superpowers 下' }
$root = [IO.Path]::GetFullPath((Join-Path $base ("$Label-" + [guid]::NewGuid().ToString('N'))))
if (Test-Path -LiteralPath $root) { throw '新 root 已存在' }
[IO.Directory]::CreateDirectory($root) | Out-Null
$inputs = Join-Path $root 'inputs'
[IO.Directory]::CreateDirectory($inputs) | Out-Null
$pins = @()
foreach ($relative in @('build.gradle','mappings/quantumchamber-codec-identity.tiny','scripts/verification/SourceRemapOracle.java','scripts/verification/source-remap-check.ps1')) {
    $file = Join-Path $repo $relative
    $item = Get-Item -LiteralPath $file
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "工具是 reparse point: $relative" }
    $copy = Join-Path $inputs ($relative.Replace('/','-'))
    Copy-Item -LiteralPath $file -Destination $copy
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $hash) { throw "工具快照不符: $relative" }
    $pins += [ordered]@{relative=$relative;bytes=$item.Length;sha256=$hash}
}
$pins | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'tool-pins.json') -Encoding utf8
$args = @('--no-daemon','--console=plain','verifySourceRemap','--rerun-tasks')
if ($SourcesJar) {
    $source = (Resolve-Path -LiteralPath $SourcesJar).Path
    $copy = Join-Path $inputs 'override-sources.jar'
    Copy-Item -LiteralPath $source -Destination $copy
    $args += '-PsourceOracleSources=' + $copy
}
if ($ReleaseJar) {
    $source = (Resolve-Path -LiteralPath $ReleaseJar).Path
    $copy = Join-Path $inputs 'override-release.jar'
    Copy-Item -LiteralPath $source -Destination $copy
    $args += '-PsourceOracleRelease=' + $copy
}
$record = [ordered]@{label=$Label;expected=$Expected;expectedFailureCode=$ExpectedFailureCode;head=(& git -C $repo rev-parse HEAD).Trim();porcelainBefore=@(& git -C $repo status --porcelain=v1);args=$args;startedAt=(Get-Date).ToUniversalTime().ToString('o')}
$record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'input.json') -Encoding utf8
Push-Location $repo
try { & $gradle @args *> (Join-Path $root 'gradle.log'); $record['gradleExit'] = $LASTEXITCODE }
finally { Pop-Location }
$log = Get-Content -LiteralPath (Join-Path $root 'gradle.log') -Raw -Encoding utf8
$record['oraclePassed'] = $log.Contains('SOURCE_REMAP_ORACLE PASS:')
$record['oracleFailedWithExpectedCode'] = $Expected -eq 'FAIL' -and $log.Contains('> Task :verifySourceRemap FAILED') -and $log.Contains($ExpectedFailureCode)
$record['buildSuccessful'] = $log.Contains('BUILD SUCCESSFUL')
$record['artifacts'] = @()
if ($log.Contains('> Task :remapJar') -and $log.Contains('> Task :remapSourcesJar')) {
    $artifactsDir = Join-Path $root 'artifacts'
    [IO.Directory]::CreateDirectory($artifactsDir) | Out-Null
    foreach ($name in @('quantumchamber-0.1.0-SNAPSHOT.jar','quantumchamber-0.1.0-SNAPSHOT-sources.jar')) {
        $source = Join-Path $repo (Join-Path 'build/libs' $name)
        if (-not (Test-Path -LiteralPath $source)) { throw "缺少本輪產物: $name" }
        $copy = Join-Path $artifactsDir $name
        Copy-Item -LiteralPath $source -Destination $copy
        $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $hash) { throw "產物快照不符: $name" }
        $record['artifacts'] += [ordered]@{name=$name;bytes=(Get-Item -LiteralPath $copy).Length;sha256=$hash}
    }
}
$record['finishedAt'] = (Get-Date).ToUniversalTime().ToString('o')
$record['finalHead'] = (& git -C $repo rev-parse HEAD).Trim()
$record['porcelainAfter'] = @(& git -C $repo status --porcelain=v1)
$record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
if ($record['finalHead'] -ne $record['head']) { throw "HEAD changed during source-remap check: $root" }
if ($Expected -eq 'PASS' -and ($record['gradleExit'] -ne 0 -or -not $record['oraclePassed'] -or -not $record['buildSuccessful'])) { throw "source-remap check failed: $root" }
if ($Expected -eq 'FAIL' -and ($record['gradleExit'] -eq 0 -or -not $record['oracleFailedWithExpectedCode'])) { throw "預期的語意負控未被oracle拒絕: $root" }
Write-Output "ROOT=$root GRADLE_EXIT=$($record['gradleExit']) EXPECTED=$Expected"
