# M7 專用精確集合與實際 workflow step fixture；不採計舊 scope 的成功。
param([ValidateSet('Windows','Ubuntu')][string]$Platform=$(if($IsWindows){'Windows'}else{'Ubuntu'}),
      [switch]$CheckWorkflow,[switch]$Fixture,[switch]$VerifyOnly,[switch]$SelfTest,
      [string]$Workflow='', [string]$GameTestXml='', [string]$JUnitDir='', [string]$RuntimeLog='',
      [string]$OutputRoot='',[string]$Gradle='',[string]$ExpectedHead='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $Workflow){$Workflow=Join-Path $repo '.github/workflows/build.yml'}
if($CheckWorkflow){
    $text=Get-Content -LiteralPath $Workflow -Raw
    if($text -cnotmatch '(?m)^\s*- name: 確認 M7 精確集合與隔離\s*$' -or $text -cnotmatch 'm7_home'){throw 'M7_MISSING_SCOPE'}
    'M7_WORKFLOW_SCOPE PASS';return
}

$head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim()
if(-not $ExpectedHead){$ExpectedHead=if($env:GITHUB_SHA){$env:GITHUB_SHA}else{$head}}
if($ExpectedHead -cne $head){throw 'M7_HEAD'}
if(-not $OutputRoot){$OutputRoot=Join-Path $repo ('.superpowers/m7-verification-'+[guid]::NewGuid().ToString('N'))}
if(-not $GameTestXml){$GameTestXml=Join-Path $repo 'build/gametest-m7-results.xml'}
if(-not $JUnitDir){$JUnitDir=Join-Path $repo 'build/test-results/test'}
if(-not $RuntimeLog){$RuntimeLog=Join-Path $repo 'run/gametest-m7/logs/latest.log'}
$inventory=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json') -Raw|ConvertFrom-Json
$expectedGame=@($inventory.portable.gametest)+@($inventory.($Platform.ToLowerInvariant()).gametest)
$expectedJUnit=@($inventory.portable.junit)+@($inventory.($Platform.ToLowerInvariant()).junit)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'gates.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'M7_ORACLE_PARSE'}
foreach($name in @('Save-Json','Distinct-Count','Batch-Scope','Declared-GameTests')){
    $definition=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$false))
    if($definition.Count -ne 1){throw 'M7_ORACLE_MISSING'};Invoke-Expression $definition[0].Extent.Text
}
# 沿用既有遞迴 JAR oracle；不執行其 runtime 或 Windows 入口。
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'm6-ci-gametest.ps1'),[ref]$tokens,[ref]$errors)
foreach($name in @('Assert-M6Artifact','Assert-M6Runtime')){
    $definition=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$false))
    if($errors.Count -or $definition.Count -ne 1){throw 'M7_ARTIFACT_ORACLE_MISSING'};Invoke-Expression $definition[0].Extent.Text
}
$SourceRoot=Join-Path $repo 'src/testmod/java'
function Assert-M7Exact([string[]]$Expected,[string[]]$Actual,[string]$Code){
    if($Expected.Count -eq 0 -or $Expected.Count -ne $Actual.Count -or @($Actual|Sort-Object -Unique).Count -ne $Actual.Count -or @(Compare-Object ($Expected|Sort-Object) ($Actual|Sort-Object) -CaseSensitive).Count){throw $Code}
}
function Assert-M7Files([string]$Xml,[string]$Junit,[string]$Log){
    $declared=@(Declared-GameTests $SourceRoot|Where-Object {$_.scope -ceq 'm7_home' -and ($_.batch -notmatch '_(windows|ubuntu)$' -or $_.batch.EndsWith('_'+$Platform.ToLowerInvariant(),[StringComparison]::Ordinal))}|ForEach-Object identity)
    Assert-M7Exact $expectedGame $declared 'M7_DECLARATION_SET'
    $source=Get-Content -LiteralPath (Join-Path $repo 'src/test/java/dev/quantumchamber/persistence/M7BudgetSpikeTest.java') -Raw
    $publish=Get-Content -LiteralPath (Join-Path $repo 'src/test/java/dev/quantumchamber/persistence/M7PublishSpikeTest.java') -Raw
    $methods=@();foreach($pair in @(@('M7BudgetSpikeTest',$source),@('M7PublishSpikeTest',$publish))){foreach($match in [regex]::Matches($pair[1],'@Test\s+void\s+(\w+)\(')){$methods+='dev.quantumchamber.persistence.'+$pair[0]+'.'+$match.Groups[1].Value}}
    Assert-M7Exact $expectedJUnit $methods 'M7_JUNIT_DECLARATION_SET'
    [xml]$report=Get-Content -LiteralPath $Xml -Raw
    $nodes=@($report.SelectNodes('//testcase'))
    Assert-M7Exact $expectedGame @($nodes|ForEach-Object {$_.classname+'|'+$_.name}) 'M7_GAME_SET'
    if(@($report.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_GAME_NOT_PASS'}
    $junitCases=@();foreach($file in Get-ChildItem -LiteralPath $Junit -Filter 'TEST-dev.quantumchamber.persistence.M7*.xml'){
        [xml]$j=Get-Content -LiteralPath $file.FullName -Raw
        if(@($j.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_JUNIT_NOT_PASS'}
        $junitCases+=@($j.SelectNodes('//testcase')|ForEach-Object {$_.classname+'.'+($_.name -replace '\(\)$','')})
    }
    Assert-M7Exact $expectedJUnit $junitCases 'M7_JUNIT_SET'
    $text=Get-Content -LiteralPath $Log -Raw
    if(-not $text.Contains('quantumchamber-testmod') -or $text.Contains('M5_UNIVERSE_SUITE_END')){throw 'M7_RUNTIME_SCOPE'}
    $run=Split-Path (Split-Path $Log)
    $runtime=Get-Content -LiteralPath (Join-Path $run 'm7-gametest-runtime.json') -Raw|ConvertFrom-Json
    if($runtime.scope -cne 'm7_home' -or 'quantumchamber-testmod' -cnotin $runtime.loadedMods -or '-Dquantumchamber.test.m7Home=true' -cnotin $runtime.flags -or @($runtime.flags|Where-Object {$_ -cmatch '^-Dquantumchamber.gametest.(m6Only|m5Only|legacyOnly|m4NormalOnly|defaultNormal)=true$'}).Count){throw 'M7_RUNTIME_SCOPE'}
    $pace=Assert-M6Runtime $Log $run
    return @{game=$nodes.Count;junit=$junitCases.Count;pace=$pace;pid=$runtime.pid;startTimeUtc=$runtime.startTimeUtc}
}
if(-not $VerifyOnly -and -not $Fixture){
    if($Platform -cne $(if($IsWindows){'Windows'}else{'Ubuntu'})){throw 'M7_ACTUAL_PLATFORM'}
    $world=Join-Path $repo 'run/gametest-m7'
    if(Test-Path -LiteralPath $world){throw 'M7_FRESH_WORLD_REQUIRED：既有世界保留；另行可恢復move後才可重跑'}
    if(-not $Gradle){$Gradle=if($IsWindows){Join-Path $repo 'gradlew.bat'}else{Join-Path $repo 'gradlew'}}
    $start=[DateTime]::UtcNow
    & $Gradle test --tests dev.quantumchamber.persistence.M7BudgetSpikeTest --tests dev.quantumchamber.persistence.M7PublishSpikeTest runGameTestM7 "-Pm7Head=$head" --console=plain
    if($LASTEXITCODE -ne 0){throw 'M7_RUNTIME_EXIT'}
    foreach($file in @($GameTestXml,$RuntimeLog)){if((Get-Item -LiteralPath $file).LastWriteTimeUtc -lt $start){throw 'M7_STALE_RUNTIME'}}
}
if(-not(Test-Path -LiteralPath $OutputRoot)){New-Item -ItemType Directory -Path $OutputRoot|Out-Null}
$proof=Assert-M7Files $GameTestXml $JUnitDir $RuntimeLog
$jars=@(Get-ChildItem -LiteralPath (Join-Path $repo 'build/libs') -Filter '*.jar'|Where-Object {$_.Name -notmatch '-dev[.]jar$'})
if($jars.Count -ne 2){throw 'M7_ARTIFACT_COUNT'}
$artifacts=@($jars|ForEach-Object {Assert-M6Artifact $_.FullName})
$summary=@{schema=1;scope='m7_home';status='PASS';head=$head;platform=$Platform;fixture=[bool]$Fixture;proof=$proof;xmlHash=(Get-FileHash -LiteralPath $GameTestXml).Hash;logHash=(Get-FileHash -LiteralPath $RuntimeLog).Hash;artifacts=$artifacts}
$summary|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $OutputRoot 'summary.json') -Encoding utf8
if($SelfTest){
    $cases=@('drop','skip','wrong-scope','platform-success','platform-rejection')
    $negatives=@()
    foreach($case in $cases){
        [xml]$changed=Get-Content -LiteralPath $GameTestXml -Raw
        $node=$changed.SelectSingleNode('//testcase')
        switch($case){
            'drop'{$null=$node.ParentNode.RemoveChild($node)}
            'skip'{$null=$node.AppendChild($changed.CreateElement('skipped'))}
            'wrong-scope'{$node.SetAttribute('name','m6universegametests.not_m7')}
            'platform-success'{$n=$changed.SelectSingleNode("//testcase[@name='m7homespikegametests.checkpoint_capability_remains_rejected_on_ubuntu']");if($null -eq $n){$n=$changed.SelectSingleNode("//testcase[@name='m7homespikegametests.m7_scope_keeps_testmod_and_close_fixture_exact']")};$n.SetAttribute('name','m7homespikegametests.checkpoint_capability_is_confirmed_on_windows')}
            'platform-rejection'{$n=$changed.SelectSingleNode("//testcase[@name='m7homespikegametests.checkpoint_capability_remains_rejected_on_ubuntu']");if($null -eq $n){$n=$node};$null=$n.ParentNode.RemoveChild($n)}
        }
        $path=Join-Path $OutputRoot ("negative-$case.xml");$changed.Save($path)
        $reason='';try{Assert-M7Files $path $JUnitDir $RuntimeLog|Out-Null}catch{$reason=$_.Exception.Message}
        if($reason -cnotmatch '^M7_(GAME_SET|GAME_NOT_PASS)'){throw "M7_NEGATIVE_NOT_KILLED $case $reason"}
        $negatives+=@{case=$case;reason=$reason;status='KILLED'}
    }
    $negatives|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $OutputRoot 'negative-results.json') -Encoding utf8
}
"M7_SCOPE PASS platform=$Platform game=$($proof.game) junit=$($proof.junit) head=$head fixture=$Fixture"

