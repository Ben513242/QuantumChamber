# ci-rule-check.ps1：把 workflow 某個 step 的 `run: |` 原文逐字抽出，在 fixture 目錄（build/ 與 src/testmod/java 的複本）
# 以與 GitHub Actions pwsh 相同的方式執行（前置 $ErrorActionPreference='stop'，dot-source）。
# Mutation／SourceMutation 只改 fixture 內的複本：XML 缺漏／skip／fail，或在 @GameTest 與方法之間插入另一個 annotation（宣告格式漂移）。
# 只讀輸入、只寫本 root；本檔不因 FAIL 而 throw，結果見 <root>/result.json 的 verdict（呼叫端自行判定預期 PASS／FAIL）。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/ifix3-ci-rule-check.ps1（SHA-256 279b7b9f46d729ec78bfe72de0e201c09d84a772c50d99751c6e0614f4ded808），
# 其前身 ifix2-ci-rule-check.ps1 沒有 SourceMutation。
# 相對來源的改動：root 改為 <EvidenceRoot>/ci-<Label>-<GUID>；輸出標記改為 CI_RULE。
# 參數：
#   -Label -Workflow -Step -GameTestXml -JUnitDir -SourceRoot  必填；Step 為 workflow 內唯一的 step 名稱
#   -Mutation drop｜skip｜fail＋-MutationTarget（XML testcase name）；-SourceMutation interpose-annotation＋-SourceTarget（方法名稱）
#   -EvidenceRoot  root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label,
    [Parameter(Mandatory)][string]$Workflow,
    [Parameter(Mandatory)][string]$Step,
    [Parameter(Mandatory)][string]$GameTestXml,
    [Parameter(Mandatory)][string]$JUnitDir,
    [Parameter(Mandatory)][string]$SourceRoot,
    [ValidateSet('none','drop','skip','fail')][string]$Mutation='none',
    [string]$MutationTarget='',
    [ValidateSet('none','interpose-annotation')][string]$SourceMutation='none',
    [string]$SourceTarget='',
    [string]$EvidenceRoot=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach($marker in 'build.gradle','settings.gradle'){if(-not(Test-Path -LiteralPath (Join-Path $repo $marker) -PathType Leaf)){throw "repo root 缺少 ${marker}：$repo"}}
# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo '.superpowers/verification'}
$EvidenceRoot=[IO.Path]::GetFullPath($EvidenceRoot)
if(-not $EvidenceRoot.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "evidence 目錄必須在 repo 內：$EvidenceRoot"}
$cursor=$EvidenceRoot
while($cursor){ if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "拒絕 reparse：$cursor"}; $parent=[IO.Path]::GetDirectoryName($cursor); if($parent -eq $cursor){break}; $cursor=$parent }
git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$EvidenceRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw "evidence 目錄未被 gitignore：$EvidenceRoot"}
if(-not(Test-Path -LiteralPath $EvidenceRoot)){New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null}
$root=Join-Path $EvidenceRoot ("ci-$Label-"+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$lines=@(Get-Content -LiteralPath $Workflow -Encoding utf8)
$nameIndex=-1
for($i=0;$i -lt $lines.Count;$i++){ if($lines[$i] -match '^\s*- name:\s*(.+?)\s*$' -and $Matches[1] -ceq $Step){ if($nameIndex -ge 0){throw "step 名稱不唯一：$Step"}; $nameIndex=$i } }
if($nameIndex -lt 0){throw "workflow 找不到 step：$Step"}
$stepIndent=($lines[$nameIndex] -replace '^(\s*)-.*$','$1').Length
$runIndex=-1
for($i=$nameIndex+1;$i -lt $lines.Count;$i++){
    if($lines[$i] -match '^\s*- ' -and ($lines[$i] -replace '^(\s*)-.*$','$1').Length -le $stepIndent){break}
    if($lines[$i] -match '^(\s*)run:\s*\|\s*$'){ $runIndex=$i; $runIndent=$Matches[1].Length; break }
}
if($runIndex -lt 0){throw "step 沒有 run: | 區塊：$Step"}
$body=[Collections.Generic.List[string]]::new()
for($i=$runIndex+1;$i -lt $lines.Count;$i++){
    $line=$lines[$i]
    if($line.Trim().Length -eq 0){$body.Add('');continue}
    $indent=($line -replace '^(\s*).*$','$1').Length
    if($indent -le $runIndent){break}
    $body.Add($line)
}
$common=($body | Where-Object {$_.Length -gt 0} | ForEach-Object {($_ -replace '^(\s*).*$','$1').Length} | Measure-Object -Minimum).Minimum
$script=($body | ForEach-Object { if($_.Length -ge $common){$_.Substring($common)}else{$_} }) -join "`n"
$fixture=Join-Path $root 'fixture'
New-Item -ItemType Directory -Path (Join-Path $fixture 'build/test-results/test') -Force | Out-Null
Copy-Item -Path (Join-Path $JUnitDir 'TEST-*.xml') -Destination (Join-Path $fixture 'build/test-results/test')
New-Item -ItemType Directory -Path (Join-Path $fixture 'src/testmod') -Force | Out-Null
Copy-Item -LiteralPath $SourceRoot -Destination (Join-Path $fixture 'src/testmod/java') -Recurse
$sourceMutated=$null
if($SourceMutation -ne 'none'){
    if($SourceTarget -notmatch '^\w+$'){throw "SourceTarget 必須是方法名稱：$SourceTarget"}
    $needle="public void $SourceTarget(TestContext"
    $hits=@(Get-ChildItem -LiteralPath (Join-Path $fixture 'src/testmod/java') -Recurse -File -Filter '*.java' | Where-Object {(Get-Content -LiteralPath $_.FullName -Raw).Contains($needle)})
    if($hits.Count -ne 1){throw "source mutation 目標必須恰出現在一個檔案：$SourceTarget（$($hits.Count)）"}
    $text=Get-Content -LiteralPath $hits[0].FullName -Raw
    if(([regex]::Matches($text,[regex]::Escape($needle))).Count -ne 1){throw "source mutation 目標在檔案內不唯一：$SourceTarget"}
    # interpose-annotation：@GameTest(...) 與方法宣告之間插入另一個 annotation（合法 Java，GameTest 仍會註冊）。
    $text=$text.Replace($needle,"@SuppressWarnings(`"unused`")`n    $needle")
    [IO.File]::WriteAllText($hits[0].FullName,$text,[Text.UTF8Encoding]::new($false))
    $sourceMutated=[IO.Path]::GetRelativePath($fixture,$hits[0].FullName)
}
[xml]$xml=Get-Content -LiteralPath $GameTestXml -Raw
if($Mutation -ne 'none'){
    $node=$xml.SelectSingleNode("//testcase[@name='$MutationTarget']")
    if($null -eq $node){throw "mutation 目標不在 XML：$MutationTarget"}
    switch($Mutation){
        'drop' {[void]$node.ParentNode.RemoveChild($node)}
        'skip' {[void]$node.AppendChild($xml.CreateElement('skipped'))}
        'fail' {$failure=$xml.CreateElement('failure');$failure.SetAttribute('message','CI 規則檢查刻意注入的失敗');[void]$node.AppendChild($failure)}
    }
}
$xml.Save((Join-Path $fixture 'build/gametest-results.xml'))
$stepFile=Join-Path $root 'step.ps1'
Set-Content -LiteralPath $stepFile -Value ("`$ErrorActionPreference = 'stop'`n"+$script) -Encoding utf8
$info=[Diagnostics.ProcessStartInfo]::new('pwsh')
foreach($argument in @('-NoProfile','-NonInteractive','-Command',". '$($stepFile.Replace("'","''"))'")){$info.ArgumentList.Add($argument)}
$info.WorkingDirectory=$fixture;$info.UseShellExecute=$false;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$process=[Diagnostics.Process]::Start($info)
$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEnd();$process.WaitForExit()
$output=$stdout.Result+$stderr
Set-Content -LiteralPath (Join-Path $root 'output.log') -Value $output -Encoding utf8
$windowsCases=@($xml.SelectNodes('//testcase') | Where-Object {$_.name -like '*_windows'}).Count
$result=[ordered]@{label=$Label;workflow=(Resolve-Path -LiteralPath $Workflow).Path;workflowSha256=(Get-FileHash -LiteralPath $Workflow -Algorithm SHA256).Hash;step=$Step
    gameTestXml=(Resolve-Path -LiteralPath $GameTestXml).Path;gameTestXmlSha256=(Get-FileHash -LiteralPath $GameTestXml -Algorithm SHA256).Hash
    junitDir=$JUnitDir;sourceRoot=$SourceRoot;mutation=$Mutation;mutationTarget=$MutationTarget;sourceMutation=$SourceMutation;sourceTarget=$SourceTarget;sourceMutatedFile=$sourceMutated
    fixtureWindowsCases=$windowsCases;exitCode=$process.ExitCode;verdict=$(if($process.ExitCode -eq 0){'PASS'}else{'FAIL'});output=$output.Trim();time=[DateTimeOffset]::Now.ToString('o')}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
Write-Output "CI_RULE label=$Label step=$Step mutation=$Mutation target=$MutationTarget sourceMutation=$SourceMutation sourceTarget=$SourceTarget exit=$($process.ExitCode) root=$root"
