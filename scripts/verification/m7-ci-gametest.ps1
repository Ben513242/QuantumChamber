# M7薄接線：只跑JUnit/GT，沿用M6純oracle與既有Windows gates owner。
param([ValidateSet('Windows','Ubuntu')][string]$Platform=$(if($IsWindows){'Windows'}else{'Ubuntu'}),
      [switch]$VerifyOnly,[switch]$SelfTest,[switch]$PassThru,[string]$OutputRoot='', [string]$Gradle='', [string]$ExpectedHead='', [string]$GameNonce='')
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();if(-not $ExpectedHead){$ExpectedHead=$head}
$actualPlatform=if($IsWindows){'Windows'}else{'Ubuntu'}
if($Platform -cne $actualPlatform){throw 'M7_ACTUAL_PLATFORM'}
# 只讀載入M6已驗的具名純函式；不執行該script或改其原碼。
foreach($row in @(@('gates.ps1',@('Save-Json','Distinct-Count','Batch-Scope','Declared-GameTests')),@('m6-ci-gametest.ps1',@('Assert-M6DirectoryPath','Assert-M6FreshWorld','Assert-M6OutputFresh','Assert-M6Artifact','Assert-M6Runtime')))){
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $row[0]),[ref]$tokens,[ref]$errors);if($errors.Count){throw 'M7_ORACLE_PARSE'}
    foreach($name in $row[1]){$d=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$false));if($d.Count -ne 1){throw 'M7_ORACLE_FUNCTION'};Invoke-Expression $d[0].Extent.Text}
}
if(-not $OutputRoot){$OutputRoot=Join-Path $repo ('.superpowers/m7-'+[guid]::NewGuid().ToString('N'))}
$OutputRoot=Assert-M6DirectoryPath $OutputRoot
git -c "safe.directory=$repo" -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$OutputRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw 'M7_OUTPUT_NOT_IGNORED'}
$SourceRoot=Join-Path $repo 'src/testmod/java'
$inventory=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'm7-inventory.json') -Raw|ConvertFrom-Json
$expectedGame=@($inventory.portable.gametest)+@($inventory.($Platform.ToLowerInvariant()).gametest)
$expectedJUnit=@($inventory.portable.junit)+@($inventory.($Platform.ToLowerInvariant()).junit)
function Exact-M7([string[]]$Expected,[string[]]$Actual,[string]$Code){
    if(-not $Expected.Count -or $Expected.Count -ne $Actual.Count -or @($Actual|Sort-Object -Unique).Count -ne $Actual.Count -or @(Compare-Object ($Expected|Sort-Object) ($Actual|Sort-Object) -CaseSensitive).Count){throw $Code}
}
# live發布必須重讀真正HEAD；歷史VerifyOnly不走此守門。
function Assert-M7LiveHead([string]$Pin){
    $observed=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0 -or $observed -cne $Pin){throw 'M7_HEAD_CHANGED'}
}
function Assert-M7SavedSummary($Saved,$Proof){
    foreach($field in @('schema','status','head','platform','actualPlatform','scope','inputsSha256')){
        if($Saved.PSObject.Properties.Name -cnotcontains $field -or $Saved.$field -cne $Proof[$field]){throw 'M7_SAVED_SUMMARY'}
    }
    Exact-M7 @($Proof.junitCases) @($Saved.junitCases) 'M7_SAVED_JUNIT_CASES'
    Exact-M7 @($Proof.gameCases) @($Saved.gameCases) 'M7_SAVED_GAME_CASES'
    foreach($field in @('ticks','waited','tps','minTickMs')){if($Saved.pacing.PSObject.Properties.Name -cnotcontains $field -or $Saved.pacing.$field -ne $Proof.pacing[$field]){throw 'M7_SAVED_PACING'}}
    if((@($Saved.loadedMods|Sort-Object)|ConvertTo-Json -Compress) -cne (@($Proof.loadedMods|Sort-Object)|ConvertTo-Json -Compress)){throw 'M7_SAVED_MODS'}
    # 複本可搬位置；artifact身份按檔名＋raw重算hash/entries驗，最終消費者使用fresh proof的實際path。
    $savedNames=@($Saved.artifacts|ForEach-Object {[IO.Path]::GetFileName($_.path)});$proofNames=@($Proof.artifacts|ForEach-Object {[IO.Path]::GetFileName($_.path)})
    Exact-M7 $proofNames $savedNames 'M7_SAVED_ARTIFACT'
    foreach($artifact in $Proof.artifacts){$match=@($Saved.artifacts|Where-Object {[IO.Path]::GetFileName($_.path) -ceq [IO.Path]::GetFileName($artifact.path)})[0];foreach($field in @('sha256','entries','testmodHits')){if($match.$field -cne $artifact[$field]){throw 'M7_SAVED_ARTIFACT'}}}
}
function Save-M7Inputs([DateTime]$Start,[string]$Runtime){
    $raw=Join-Path $OutputRoot 'raw';New-Item -ItemType Directory -Path $raw,(Join-Path $raw 'junit'),(Join-Path $raw 'artifacts'),(Join-Path $raw 'runtime') -Force|Out-Null
    $records=@();$candidates=@(@{kind='gametest';source=(Join-Path $repo 'build/gametest-m7-results.xml');target=(Join-Path $raw 'gametest.xml')})
    foreach($file in @(Get-ChildItem (Join-Path $repo 'build/test-results/test') -Filter 'TEST-dev.quantumchamber.persistence.M7*.xml' -File -ErrorAction SilentlyContinue)){$candidates+=@{kind='junit';source=$file.FullName;target=(Join-Path $raw ('junit/'+$file.Name))}}
    foreach($file in @(Get-ChildItem (Join-Path $repo 'build/libs') -Filter '*.jar' -File -ErrorAction SilentlyContinue|Where-Object {$_.Name -notmatch '-dev[.]jar$'})){$candidates+=@{kind='artifact';source=$file.FullName;target=(Join-Path $raw ('artifacts/'+$file.Name))}}
    if(Test-Path (Join-Path $Runtime 'logs')){Copy-Item -LiteralPath (Join-Path $Runtime 'logs') -Destination (Join-Path $raw 'runtime') -Recurse}
    foreach($file in @(Get-ChildItem $Runtime -Filter 'm4-boundary-*.json' -File -ErrorAction SilentlyContinue)){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $raw 'runtime')}
    foreach($candidate in $candidates){if(Test-Path -LiteralPath $candidate.source -PathType Leaf){Copy-Item -LiteralPath $candidate.source -Destination $candidate.target;$item=Get-Item -LiteralPath $candidate.source;$records+=@{kind=$candidate.kind;relative=[IO.Path]::GetRelativePath($OutputRoot,$candidate.target).Replace('\','/');sha256=(Get-FileHash $candidate.target).Hash;original=$candidate.source;originalWriteUtc=$item.LastWriteTimeUtc.ToString('o');fresh=($item.LastWriteTimeUtc -ge $Start)}}}
    foreach($file in @(Get-ChildItem (Join-Path $raw 'runtime') -Recurse -File)){$records+=@{kind='runtime';relative=[IO.Path]::GetRelativePath($OutputRoot,$file.FullName).Replace('\','/');sha256=(Get-FileHash $file.FullName).Hash;original=$Runtime;originalWriteUtc=$file.LastWriteTimeUtc.ToString('o');fresh=($file.LastWriteTimeUtc -ge $Start)}}
    $manifest=[ordered]@{schema=1;head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;runtime=$Runtime;nonce=$GameNonce;launchStartUtc=$Start.ToString('o');inputs=$records}
    Save-Json (Join-Path $OutputRoot 'inputs.json') $manifest
}
function Verify-M7([string]$Root){
    $manifest=Get-Content -LiteralPath (Join-Path $Root 'inputs.json') -Raw|ConvertFrom-Json -DateKind String
    if($manifest.schema -ne 1 -or $manifest.head -cne $ExpectedHead -or $manifest.platform -cne $Platform -or $manifest.actualPlatform -cne $actualPlatform){throw 'M7_INPUT_IDENTITY'}
    $inputs=@($manifest.inputs);foreach($input in $inputs){$path=[IO.Path]::GetFullPath((Join-Path $Root $input.relative));if(-not $path.StartsWith($Root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'M7_INPUT_PATH'};if((Get-FileHash $path).Hash -cne $input.sha256){throw 'M7_INPUT_HASH'}}
    $xmls=@($inputs|Where-Object {$_.kind -ceq 'gametest'});$junit=@($inputs|Where-Object {$_.kind -ceq 'junit'});$logs=@($inputs|Where-Object {$_.relative -ceq 'raw/runtime/logs/latest.log'})
    if($xmls.Count -ne 1 -or $junit.Count -ne 2 -or $logs.Count -ne 1 -or @(@($xmls)+@($junit)+@($logs)|Where-Object {-not $_.fresh}).Count){throw 'M7_NOT_CURRENT'}
    $declarations=@(Declared-GameTests $SourceRoot|Where-Object {$_.scope -ceq 'm7_home' -and ($_.batch -notmatch '_(windows|ubuntu)$' -or $_.batch.EndsWith('_'+$Platform.ToLowerInvariant(),[StringComparison]::Ordinal))}|ForEach-Object identity)
    Exact-M7 $expectedGame $declarations 'M7_DECLARATION_SET'
    $declaredJUnit=@();foreach($name in @('M7BudgetSpikeTest','M7PublishSpikeTest')){$text=Get-Content -LiteralPath (Join-Path $repo "src/test/java/dev/quantumchamber/persistence/$name.java") -Raw;foreach($m in [regex]::Matches($text,'@Test\s+void\s+(\w+)\(')){$declaredJUnit+="dev.quantumchamber.persistence.$name."+$m.Groups[1].Value}}
    Exact-M7 $expectedJUnit $declaredJUnit 'M7_JUNIT_DECLARATION_SET'
    [xml]$gt=Get-Content -LiteralPath (Join-Path $Root $xmls[0].relative) -Raw
    Exact-M7 $expectedGame @($gt.SelectNodes('//testcase')|ForEach-Object {$_.classname+'|'+$_.name}) 'M7_GAME_SET'
    if(@($gt.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_GAME_NOT_PASS'}
    $cases=@();foreach($file in $junit){[xml]$j=Get-Content -LiteralPath (Join-Path $Root $file.relative) -Raw;if(@($j.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_JUNIT_NOT_PASS'};$cases+=@($j.SelectNodes('//testcase')|ForEach-Object {$_.classname+'.'+($_.name -replace '\(\)$','')})}
    Exact-M7 $expectedJUnit $cases 'M7_JUNIT_SET'
    $runtime=Join-Path $Root 'raw/runtime';$log=Join-Path $runtime 'logs/latest.log';$pace=Assert-M6Runtime $log $runtime
    $mods=@([regex]::Matches((Get-Content -LiteralPath $log -Raw),'(?m)^\s*[-|+\\ ]+\s+(?<id>[a-z0-9_-]+)\s+[^\r\n ]+\s*$')|ForEach-Object {$_.Groups['id'].Value})
    if('quantumchamber-testmod' -cnotin $mods -or 'quantumchamber' -cnotin $mods){throw 'M7_RUNTIME_MODS'}
    $artifacts=@($inputs|Where-Object {$_.kind -ceq 'artifact'});if(@($artifacts|Where-Object {-not $_.fresh}).Count -or $artifacts.Count -ne 2 -or @($artifacts|Where-Object {$_.relative.EndsWith('-sources.jar',[StringComparison]::Ordinal)}).Count -ne 1){throw 'M7_ARTIFACT_ROLES'}
    foreach($artifact in $artifacts){$zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $Root $artifact.relative));try{$classCount=@($zip.Entries|Where-Object {$_.FullName -cmatch '[.]class$'}).Count;$sourceCount=@($zip.Entries|Where-Object {$_.FullName -cmatch '[.]java$'}).Count;if($artifact.relative.EndsWith('-sources.jar',[StringComparison]::Ordinal)){if($sourceCount -eq 0 -or $classCount -ne 0){throw 'M7_ARTIFACT_ROLES'}}elseif($classCount -eq 0 -or $sourceCount -ne 0){throw 'M7_ARTIFACT_ROLES'}}finally{$zip.Dispose()}}
    $jars=@(foreach($artifact in $artifacts){Assert-M6Artifact (Join-Path $Root $artifact.relative)})
    return [ordered]@{schema=1;status='PASS';head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;scope='m7_home';junitCases=$cases;gameCases=$expectedGame;pacing=$pace;loadedMods=$mods;inputsSha256=(Get-FileHash (Join-Path $Root 'inputs.json')).Hash;artifacts=$jars}
}
function SelfTest-M7([string]$Root){
    $controls=Join-Path $Root ('selftest-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $controls|Out-Null;$results=@()
    foreach($case in @('positive','drop','skip','wrong-scope','wrong-head','hash','summary-game-cases','summary-junit-cases','summary-pacing','summary-artifact')){
        $copy=Join-Path $controls $case;New-Item -ItemType Directory $copy|Out-Null;Copy-Item -LiteralPath (Join-Path $Root 'raw') -Destination $copy -Recurse;Copy-Item -LiteralPath (Join-Path $Root 'inputs.json') -Destination $copy;Copy-Item -LiteralPath (Join-Path $Root 'summary.json') -Destination $copy
        $manifest=Get-Content -LiteralPath (Join-Path $copy 'inputs.json') -Raw|ConvertFrom-Json -DateKind String
        if($case -cin @('drop','skip','wrong-scope')){$input=@($manifest.inputs|Where-Object {$_.kind -ceq 'gametest'})[0];$path=Join-Path $copy $input.relative;[xml]$xml=Get-Content -LiteralPath $path -Raw;$node=$xml.SelectSingleNode('//testcase');switch($case){'drop'{$null=$node.ParentNode.RemoveChild($node)};'skip'{$null=$node.AppendChild($xml.CreateElement('skipped'))};'wrong-scope'{$node.SetAttribute('name','m6universegametests.wrong_scope')}};$xml.Save($path);$input.sha256=(Get-FileHash $path).Hash}
        if($case -ceq 'wrong-head'){$manifest.head='0'*40};if($case -ceq 'hash'){$manifest.inputs[0].sha256='0'*64}
        if($case.StartsWith('summary-',[StringComparison]::Ordinal)){
            $changed=Get-Content -LiteralPath (Join-Path $copy 'summary.json') -Raw|ConvertFrom-Json
            switch($case){'summary-game-cases'{$changed.gameCases=@()};'summary-junit-cases'{$changed.junitCases=@()};'summary-pacing'{$changed.pacing.waited=[long]$changed.pacing.waited+1};'summary-artifact'{$changed.artifacts[0].sha256='0'*64}}
            Save-Json (Join-Path $copy 'summary.json') $changed
        }
        # 正例維持原始inputs/XML bytes；只有負例重寫自己的metadata副本。
        if($case -cin @('drop','skip','wrong-scope','wrong-head','hash')){Save-Json (Join-Path $copy 'inputs.json') $manifest}
        $expected=@{positive='';drop='M7_GAME_SET';skip='M7_GAME_NOT_PASS';'wrong-scope'='M7_GAME_SET';'wrong-head'='M7_INPUT_IDENTITY';hash='M7_INPUT_HASH';'summary-game-cases'='M7_SAVED_GAME_CASES';'summary-junit-cases'='M7_SAVED_JUNIT_CASES';'summary-pacing'='M7_SAVED_PACING';'summary-artifact'='M7_SAVED_ARTIFACT'}[$case];$reason='';try{$recomputed=Verify-M7 $copy;$candidate=Get-Content -LiteralPath (Join-Path $copy 'summary.json') -Raw|ConvertFrom-Json;Assert-M7SavedSummary $candidate $recomputed}catch{$reason=$_.Exception.Message}
        if($reason -cne $expected){throw "M7_SELFTEST $case expected=$expected got=$reason"};$results+=@{case=$case;expected=$expected;status=$(if($expected){'KILLED'}else{'PASS'})}
    }
    $reason='';try{Assert-M7LiveHead ('0'*40)}catch{$reason=$_.Exception.Message};if($reason -cne 'M7_HEAD_CHANGED'){throw 'M7_SELFTEST live-head-mismatch'};$results+=@{case='live-head-mismatch';expected='M7_HEAD_CHANGED';status='KILLED'}
    Save-Json (Join-Path $controls 'result.json') ([ordered]@{fixture=$true;head=$ExpectedHead;platform=$Platform;cases=$results})
    Verify-M7 $Root|Out-Null
}
$launched=-not $VerifyOnly
if($launched){
    if(-not(Test-Path $OutputRoot)){New-Item -ItemType Directory $OutputRoot|Out-Null};Assert-M6OutputFresh $OutputRoot
    if($head -cne $ExpectedHead){throw 'M7_HEAD'};if(-not $GameNonce){$GameNonce=[guid]::NewGuid().ToString('N')};if($GameNonce -cnotmatch '^[0-9a-f]{32}$'){throw 'M7_NONCE'}
    $runtime=Assert-M6FreshWorld (Join-Path $repo "run/gametest-m7-$GameNonce")
    if(-not $Gradle){$Gradle=if($IsWindows){Join-Path $repo 'gradlew.bat'}else{Join-Path $repo 'gradlew'}}
    $start=[DateTime]::UtcNow;$failure=$null
    try{
        if($IsWindows){& (Join-Path $PSScriptRoot 'gates.ps1') -Mode M7Runtime -Label m7-runtime -EvidenceRoot $OutputRoot -Gradle $Gradle -M7Nonce $GameNonce}
        else{& $Gradle test --tests dev.quantumchamber.persistence.M7BudgetSpikeTest --tests dev.quantumchamber.persistence.M7PublishSpikeTest runGameTestM7 remapJar remapSourcesJar "-Pm7GameNonce=$GameNonce" "-Pm7Head=$head" --rerun-tasks --console=plain *> (Join-Path $OutputRoot 'gradle.log');if($LASTEXITCODE -ne 0){throw "M7_RUNTIME_EXIT $LASTEXITCODE"}}
    }catch{$failure=$_}
    finally{Save-M7Inputs $start $runtime}
    if($failure){Save-Json (Join-Path $OutputRoot 'failure.json') @{head=$ExpectedHead;platform=$Platform;status='FAIL';message=$failure.Exception.Message};throw $failure}
    $proof=Verify-M7 $OutputRoot;Assert-M7LiveHead $ExpectedHead;Save-Json (Join-Path $OutputRoot 'summary.json') $proof
}else{
    $saved=Get-Content -LiteralPath (Join-Path $OutputRoot 'summary.json') -Raw|ConvertFrom-Json
    $proof=Verify-M7 $OutputRoot
    Assert-M7SavedSummary $saved $proof
}
if($SelfTest){SelfTest-M7 $OutputRoot}
$finalProof=Verify-M7 $OutputRoot
if($launched){Assert-M7LiveHead $ExpectedHead}
"M7_VERIFIED PASS platform=$Platform junit=$($expectedJUnit.Count) gametest=$($expectedGame.Count) launched=$launched HEAD=$ExpectedHead root=$OutputRoot"
if($PassThru){[pscustomobject]$finalProof}
