param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Label)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $PSScriptRoot 'gates.ps1'
$owner = Join-Path $repo '.superpowers/sdd/2026-10-01-m5-collapse-passage/f7/step0'
$root = Join-Path $owner ("task-guard-$Label-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null

# 只載入正式腳本中的 Invoke-Gradle 函式；第一個檔案操作攔截為啟動前見證。
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "gates.ps1 解析失敗: $($errors[0])" }
$function = @($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-Gradle'}, $false))
if ($function.Count -ne 1) { throw 'Invoke-Gradle 定義數量不符' }
Invoke-Expression $function[0].Extent.Text
function New-Item { throw 'WOULD_CREATE_EVIDENCE_BEFORE_GRADLE' }

$cases = @(
    @{ task='runGameTest'; mode='junit'; blocked=$true },
    @{ task=':runGameTest'; mode='compile-only'; blocked=$true },
    @{ task=':sub:runServer'; mode='junit'; blocked=$true },
    @{ task='test'; mode='junit'; blocked=$false },
    @{ task=':test'; mode='compile-only'; blocked=$false }
)
$results = @()
foreach ($case in $cases) {
    $message = ''
    try { Invoke-Gradle @($case.task) (Join-Path $root 'unused') $root -runtimeLogMode $case.mode | Out-Null }
    catch { $message = $_.Exception.Message }
    $expected = if ($case.blocked) { '日誌不適用模式不可包含 run task' } else { 'WOULD_CREATE_EVIDENCE_BEFORE_GRADLE' }
    $results += [ordered]@{task=$case.task;mode=$case.mode;expected=$expected;actual=$message;pass=($message -ceq $expected)}
}
$receipt = [ordered]@{sourceSha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash;results=$results;pass=(@($results | Where-Object {-not $_.pass}).Count -eq 0)}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
Write-Output "ROOT=$root PASS=$($receipt.pass)"
if (-not $receipt.pass) { exit 1 }
