param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Label,
    [string]$SourcesJar,
    [string]$ReleaseJar,
    [ValidateSet('PASS','FAIL')][string]$Expected = 'PASS'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gradle = Join-Path $repo $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
$root = Join-Path $repo ("build/verification/source-remap/$Label-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null
$args = @('--no-daemon','--console=plain','verifySourceRemap','--rerun-tasks')
if ($SourcesJar) { $args += '-PsourceOracleSources=' + (Resolve-Path -LiteralPath $SourcesJar).Path }
if ($ReleaseJar) { $args += '-PsourceOracleRelease=' + (Resolve-Path -LiteralPath $ReleaseJar).Path }
$record = [ordered]@{label=$Label;expected=$Expected;head=(& git -C $repo rev-parse HEAD).Trim();args=$args;startedAt=(Get-Date).ToUniversalTime().ToString('o')}
$record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'input.json') -Encoding utf8
Push-Location $repo
try { & $gradle @args *> (Join-Path $root 'gradle.log'); $record['gradleExit'] = $LASTEXITCODE }
finally { Pop-Location }
$record['finishedAt'] = (Get-Date).ToUniversalTime().ToString('o')
$record['finalHead'] = (& git -C $repo rev-parse HEAD).Trim()
$record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
if ($record['finalHead'] -ne $record['head']) { throw "HEAD changed during source-remap check: $root" }
if ($Expected -eq 'PASS' -and $record['gradleExit'] -ne 0) { throw "source-remap check failed: $root" }
if ($Expected -eq 'FAIL' -and $record['gradleExit'] -eq 0) { throw "source-remap negative control unexpectedly passed: $root" }
Write-Output "ROOT=$root GRADLE_EXIT=$($record['gradleExit']) EXPECTED=$Expected"
