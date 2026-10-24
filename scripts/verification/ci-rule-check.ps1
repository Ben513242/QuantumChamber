# ci-rule-check.ps1：把 workflow 某個 step 的 `run: |` 原文逐字抽出，在 fixture 目錄（build/ 與 src/testmod/java 的複本）
# 以與 GitHub Actions pwsh 相同的方式執行（前置 $ErrorActionPreference='stop'，dot-source）。
# fixture 同時放 default 與 M5 兩份 GameTest XML（build/gametest-results.xml、build/gametest-m5-results.xml；M5 F3c）。
# Mutation／SourceMutation 只改 fixture 內的複本：XML 缺漏／skip／fail（-MutationXml 選 default 或 m5），在 @GameTest 與方法之間插入另一個
# annotation（宣告格式漂移），或把方法改名為 <名稱>_windows（XML 中對應的 testcase 一起改名；用來驗證 _windows 規則的納入與排除）。
# 只讀輸入、只寫本 root；本檔不因 FAIL 而 throw，結果見 <root>/result.json 的 verdict（呼叫端自行判定預期 PASS／FAIL）。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/ifix3-ci-rule-check.ps1（SHA-256 279b7b9f46d729ec78bfe72de0e201c09d84a772c50d99751c6e0614f4ded808），
# 其前身 ifix2-ci-rule-check.ps1 沒有 SourceMutation。
# 相對來源的改動：root 改為 <EvidenceRoot>/ci-<Label>-<GUID>；輸出標記改為 CI_RULE；M5 F3c 加上 M5 XML、-MutationXml 與 rename-windows。
# 參數：
#   -Label -Workflow -Step -GameTestXml -M5GameTestXml -JUnitDir -SourceRoot  必填；Step 為 workflow 內唯一的 step 名稱
#   -Mutation drop｜skip｜fail＋-MutationTarget（XML testcase name）＋-MutationXml default｜m5（預設 default）
#   -SourceMutation interpose-annotation｜rename-windows＋-SourceTarget（方法名稱）；rename-windows 之後，-MutationTarget 用改名後的名稱
#   -EvidenceRoot  root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label,
    [Parameter(Mandatory)][string]$Workflow,
    [Parameter(Mandatory)][string]$Step,
    [Parameter(Mandatory)][string]$GameTestXml,
    [Parameter(Mandatory)][string]$M5GameTestXml,
    [string]$M6GameTestXml='',
    [string]$M6RuntimeLog='',
    [string]$M6RuntimeRoot='',
    [string[]]$M6Artifacts=@(),
    [string]$MainReceipt='',
    [ValidateSet('none','missing-receipt','wrong-head','gametest','testmod','stop','restore','missing-evidence')][string]$MainMutation='none',
    [Parameter(Mandatory)][string]$JUnitDir,
    [Parameter(Mandatory)][string]$SourceRoot,
    [ValidateSet('none','drop','skip','fail')][string]$Mutation='none',
    [string]$MutationTarget='',
    [ValidateSet('default','m5','m6')][string]$MutationXml='default',
    [ValidateSet('none','interpose-annotation','rename-windows')][string]$SourceMutation='none',
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
[xml]$xml=Get-Content -LiteralPath $GameTestXml -Raw
[xml]$m5Xml=Get-Content -LiteralPath $M5GameTestXml -Raw
$m6Xml=$null
if($M6GameTestXml){
    [xml]$m6Xml=Get-Content -LiteralPath $M6GameTestXml -Raw
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts/verification'),(Join-Path $fixture 'run/gametest-m6/logs'),(Join-Path $fixture 'build/libs') -Force | Out-Null
    foreach($scriptName in @('gates.ps1','m6-ci-gametest.ps1','m6-ci-main-only.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $scriptName) -Destination (Join-Path $fixture 'scripts/verification')}
    Copy-Item -LiteralPath $M6RuntimeLog -Destination (Join-Path $fixture 'run/gametest-m6/logs/latest.log')
    foreach($artifact in $M6Artifacts){Copy-Item -LiteralPath $artifact -Destination (Join-Path $fixture 'build/libs')}
    if($Step -ceq '確認 Ubuntu M6 精確集合與隔離'){
        # 僅本機workflow規則fixture：literal合法排除；不是實際Ubuntu執行證據。
        New-Item -ItemType Directory -Path (Join-Path $fixture 'build/m6-verification') -Force | Out-Null
        [ordered]@{head=(git -C $repo rev-parse HEAD).Trim();platform='Ubuntu';actualPlatform='Ubuntu';status='UNSUPPORTED';reason='M6_NATIVE_WINDOWS_REQUIRED';supported=$false;nativeExecuted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixture 'build/m6-verification/unsupported-native.json') -Encoding utf8
    }
    foreach($boundary in @(Get-ChildItem -LiteralPath $M6RuntimeRoot -Filter 'm4-boundary-*.json' -File)){Copy-Item -LiteralPath $boundary.FullName -Destination (Join-Path $fixture 'run/gametest-m6')}
}
$mainOriginalHash=''
if($MainReceipt){
    & (Join-Path $PSScriptRoot 'm6-ci-main-only.ps1') -VerifyOnly -Receipt $MainReceipt -ExpectedHead (git -C $repo rev-parse HEAD).Trim() | Out-Null
    $mainOriginalHash=(Get-FileHash -LiteralPath $MainReceipt).Hash
    $main=Get-Content -LiteralPath $MainReceipt -Raw | ConvertFrom-Json -DateKind String
    $originalBundle=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($MainReceipt))
    $mainBundle=Join-Path $fixture 'build/m6-main-only';New-Item -ItemType Directory -Path $mainBundle | Out-Null
    foreach($file in $main.files){
        $destination=Join-Path $mainBundle $file.path
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $originalBundle $file.path) -Destination $destination
    }
    function Main-Path([string]$Kind){$f=@($main.files | Where-Object kind -ceq $Kind);if($f.Count -ne 1){throw 'Main fixture引用不唯一'};return Join-Path $mainBundle $f[0].path}
    switch($MainMutation){
        'wrong-head' {$main.headBefore='0000000000000000000000000000000000000000';$main.headAfter=$main.headBefore}
        'gametest' {$p=Main-Path 'gate';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.mode='M6GameTest';$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'testmod' {$p=Main-Path 'oracle';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.layer2RuntimePathHits=@('build/classes/java/testmod');$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'stop' {$p=Main-Path 'execution';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.stdinStop=$false;$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'restore' {$p=Main-Path 'restore';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.exactRestored=$false;$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'missing-evidence' {Move-Item -LiteralPath (Main-Path 'classload') -Destination (Join-Path $mainBundle 'preserved-classload.log')}
    }
    # 突變後重算外層hash，確認語意守門確實拒絕，不能只靠舊hash碰巧失敗。
    foreach($f in $main.files){$p=Join-Path $mainBundle $f.path;if(Test-Path -LiteralPath $p -PathType Leaf){$f.sha256=(Get-FileHash -LiteralPath $p).Hash}}
    $main | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $mainBundle 'main-ci-receipt.json') -Encoding utf8
    if($MainMutation -eq 'missing-receipt'){Move-Item -LiteralPath (Join-Path $mainBundle 'main-ci-receipt.json') -Destination (Join-Path $mainBundle 'preserved-receipt.json')}
}elseif($MainMutation -ne 'none'){throw 'MainMutation須有有效MainReceipt作為原件'}
$sourceMutated=$null
if($SourceMutation -ne 'none'){
    if($SourceTarget -notmatch '^\w+$'){throw "SourceTarget 必須是方法名稱：$SourceTarget"}
    $needle="public void $SourceTarget(TestContext"
    $hits=@(Get-ChildItem -LiteralPath (Join-Path $fixture 'src/testmod/java') -Recurse -File -Filter '*.java' | Where-Object {(Get-Content -LiteralPath $_.FullName -Raw).Contains($needle)})
    if($hits.Count -ne 1){throw "source mutation 目標必須恰出現在一個檔案：$SourceTarget（$($hits.Count)）"}
    $text=Get-Content -LiteralPath $hits[0].FullName -Raw
    if(([regex]::Matches($text,[regex]::Escape($needle))).Count -ne 1){throw "source mutation 目標在檔案內不唯一：$SourceTarget"}
    if($SourceMutation -eq 'interpose-annotation'){
        # interpose-annotation：@GameTest(...) 與方法宣告之間插入另一個 annotation（合法 Java，GameTest 仍會註冊）。
        $text=$text.Replace($needle,"@SuppressWarnings(`"unused`")`n    $needle")
    } else {
        # rename-windows：方法改名為 <名稱>_windows，兩份 XML 中對應的 testcase（恰一個）一起改名，模擬一個實際通過的 _windows 案例。
        if($SourceTarget -like '*_windows'){throw "rename-windows 的目標不可已是 _windows：$SourceTarget"}
        $text=$text.Replace($needle,"public void ${SourceTarget}_windows(TestContext")
        $caseName=$hits[0].BaseName.ToLowerInvariant()+'.'+$SourceTarget
        $cases=@(@($xml,$m5Xml,$m6Xml) | Where-Object {$null -ne $_} | ForEach-Object {$_.SelectNodes("//testcase[@name='$caseName']")} | Where-Object {$null -ne $_})
        if($cases.Count -ne 1){throw "rename-windows 的 testcase 必須恰出現一次：$caseName（$($cases.Count)）"}
        $cases[0].SetAttribute('name',$caseName+'_windows')
    }
    [IO.File]::WriteAllText($hits[0].FullName,$text,[Text.UTF8Encoding]::new($false))
    $sourceMutated=[IO.Path]::GetRelativePath($fixture,$hits[0].FullName)
}
if($Mutation -ne 'none'){
    $target=if($MutationXml -eq 'm6'){$m6Xml}elseif($MutationXml -eq 'm5'){$m5Xml}else{$xml}
    $node=$target.SelectSingleNode("//testcase[@name='$MutationTarget']")
    if($null -eq $node){throw "mutation 目標不在 $MutationXml XML：$MutationTarget"}
    switch($Mutation){
        'drop' {[void]$node.ParentNode.RemoveChild($node)}
        'skip' {[void]$node.AppendChild($target.CreateElement('skipped'))}
        'fail' {$failure=$target.CreateElement('failure');$failure.SetAttribute('message','CI 規則檢查刻意注入的失敗');[void]$node.AppendChild($failure)}
    }
}
$xml.Save((Join-Path $fixture 'build/gametest-results.xml'))
$m5Xml.Save((Join-Path $fixture 'build/gametest-m5-results.xml'))
if($m6Xml){$m6Xml.Save((Join-Path $fixture 'build/gametest-m6-results.xml'))}
$stepFile=Join-Path $root 'step.ps1'
Set-Content -LiteralPath $stepFile -Value ("`$ErrorActionPreference = 'stop'`n"+$script) -Encoding utf8
$info=[Diagnostics.ProcessStartInfo]::new('pwsh')
foreach($argument in @('-NoProfile','-NonInteractive','-Command',". '$($stepFile.Replace("'","''"))'")){$info.ArgumentList.Add($argument)}
$info.Environment['GITHUB_SHA']=(git -C $repo rev-parse HEAD).Trim()
$info.WorkingDirectory=$fixture;$info.UseShellExecute=$false;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$process=[Diagnostics.Process]::Start($info)
$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEnd();$process.WaitForExit()
$output=$stdout.Result+$stderr
Set-Content -LiteralPath (Join-Path $root 'output.log') -Value $output -Encoding utf8
$windowsCases=@($xml.SelectNodes('//testcase') | Where-Object {$_.name -like '*_windows'}).Count
$m5WindowsCases=@($m5Xml.SelectNodes('//testcase') | Where-Object {$_.name -like '*_windows'}).Count
$result=[ordered]@{label=$Label;workflow=(Resolve-Path -LiteralPath $Workflow).Path;workflowSha256=(Get-FileHash -LiteralPath $Workflow -Algorithm SHA256).Hash;step=$Step
    gameTestXml=(Resolve-Path -LiteralPath $GameTestXml).Path;gameTestXmlSha256=(Get-FileHash -LiteralPath $GameTestXml -Algorithm SHA256).Hash
    m5GameTestXml=(Resolve-Path -LiteralPath $M5GameTestXml).Path;m5GameTestXmlSha256=(Get-FileHash -LiteralPath $M5GameTestXml -Algorithm SHA256).Hash
    head=(git -C $repo rev-parse HEAD).Trim();m6GameTestXmlSha256=$(if($M6GameTestXml){(Get-FileHash -LiteralPath $M6GameTestXml).Hash}else{''});m6RuntimeLogSha256=$(if($M6RuntimeLog){(Get-FileHash -LiteralPath $M6RuntimeLog).Hash}else{''})
    mainReceipt=$MainReceipt;mainReceiptSha256=$mainOriginalHash;mainMutation=$MainMutation;mainFixtureRuntimeLaunched=$false
    junitDir=$JUnitDir;sourceRoot=$SourceRoot;mutation=$Mutation;mutationTarget=$MutationTarget;mutationXml=$MutationXml;sourceMutation=$SourceMutation;sourceTarget=$SourceTarget;sourceMutatedFile=$sourceMutated
    fixtureWindowsCases=$windowsCases;fixtureM5WindowsCases=$m5WindowsCases;exitCode=$process.ExitCode;verdict=$(if($process.ExitCode -eq 0){'PASS'}else{'FAIL'});output=$output.Trim();time=[DateTimeOffset]::Now.ToString('o')}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
Write-Output "CI_RULE label=$Label step=$Step mutation=$Mutation target=$MutationTarget mutationXml=$MutationXml sourceMutation=$SourceMutation sourceTarget=$SourceTarget exit=$($process.ExitCode) root=$root"
