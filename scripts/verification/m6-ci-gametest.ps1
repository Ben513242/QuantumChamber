# M6 跨平台 CI 啟動與結果 oracle；本機 VerifyOnly 只核對指定原件，不冒稱遠端 CI。
param([Parameter(Mandatory)][ValidateSet('Windows','Ubuntu')][string]$Platform,
      [switch]$VerifyOnly, [switch]$CheckUnsupportedNative, [switch]$SelfTest,
      [string]$Gradle='', [string]$GameTestXml='', [string]$RuntimeLog='', [string]$RuntimeRoot='',
      [string]$OutputRoot='', [string]$SourceRoot='', [string]$Batch='', [string[]]$Artifacts=@(), [string]$UnsupportedNativeReceipt='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $OutputRoot){$OutputRoot=Join-Path $repo 'build/m6-verification'}
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not $OutputRoot.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)){throw 'M6_OUTPUT_UNSAFE：輸出必須在 checkout 內'}
$cursor=$OutputRoot
while($cursor){if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'M6_OUTPUT_UNSAFE：拒絕 reparse'};$cursor=[IO.Path]::GetDirectoryName($cursor)}
if(-not(Test-Path -LiteralPath $OutputRoot)){New-Item -ItemType Directory -Path $OutputRoot | Out-Null}
$gate=$OutputRoot
if(-not $SourceRoot){$SourceRoot=Join-Path $repo 'src/testmod/java'}
$head=(git -C $repo rev-parse HEAD).Trim()
# 只載入同 checkout 的五個具名純 oracle 函式；不執行 Windows runtime／move 入口。
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'gates.ps1'),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'M6_ORACLE_PARSE_FAILED'}
foreach($name in @('Save-Json','Check-Path','Distinct-Count','Batch-Scope','Declared-GameTests','Assert-Scope')){
    $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$false))
    if($definitions.Count -ne 1){throw "M6_ORACLE_FUNCTION_MISSING：$name"}
    Invoke-Expression $definitions[0].Extent.Text
}
function Assert-M6Artifact([string]$Path){
    $hits=[Collections.Generic.List[string]]::new()
    $testPaths=@(Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -Filter '*.java' | ForEach-Object {([IO.Path]::GetRelativePath($SourceRoot,$_.FullName).Replace('\','/') -replace '[.]java$','')})
    function Inspect-Zip($zip,[string]$prefix){
        foreach($entry in $zip.Entries){
            $name=$entry.FullName
            $classPath=$name -replace '[.](class|java)$',''
            if(@($testPaths | Where-Object {$classPath -ceq $_ -or $classPath.StartsWith($_+'$',[StringComparison]::Ordinal)}).Count -or $name -match '(?i)testmod|gametest|quantumchamber-test[.]mixins|m1_empty[.]nbt' -or ($name -match '(?i)probe' -and $name -notin @('dev/quantumchamber/compat/ModPresenceProbe.class','dev/quantumchamber/compat/ModPresenceProbe.java'))){$hits.Add($prefix+$name)}
            if($name -eq 'fabric.mod.json'){
                $stream=[IO.StreamReader]::new($entry.Open());try{$manifest=$stream.ReadToEnd()}finally{$stream.Dispose()}
                if($manifest -match '(?i)testmod|gametest|client[-_.]?probe'){$hits.Add($prefix+'fabric.mod.json:entrypoint')}
            }
            if($name.EndsWith('.jar',[StringComparison]::OrdinalIgnoreCase)){
                $memory=[IO.MemoryStream]::new();$stream=$entry.Open()
                try{$stream.CopyTo($memory);$memory.Position=0;$nested=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Read,$true);try{Inspect-Zip $nested ($prefix+$name+'!')}finally{$nested.Dispose()}}finally{$stream.Dispose();$memory.Dispose()}
            }
        }
    }
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try{Inspect-Zip $zip ''; $count=$zip.Entries.Count}finally{$zip.Dispose()}
    if($hits.Count){throw "M6_TESTMOD_LEAK：$Path entries=[$($hits -join ',')]"}
    return [ordered]@{path=$Path;entries=$count;sha256=(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash;testmodHits=0}
}
function Assert-M6Runtime([string]$Log,[string]$Root){
    $text=Get-Content -LiteralPath $Log -Raw
    $boundaries=@(Get-ChildItem -LiteralPath $Root -Filter 'm4-boundary-*.json' -File -ErrorAction SilentlyContinue)
    if($boundaries.Count -or $text.Contains('M5_UNIVERSE_SUITE_END')){throw 'M6_SCOPE_MISMATCH：M6 runtime 出現 M4／M5 observer'}
    $lines=@($text -split '\r?\n' | Where-Object {$_.Contains('GameTest tick 節流 結束：')})
    if($lines.Count -ne 1){throw 'M6_PACE_MISMATCH：結束 pace 必須恰一行'}
    $m=[regex]::Match($lines[0],'ticks=(?<ticks>\d+) waited=(?<waited>\d+) waitedMs=(?<waitedMs>\d+) elapsedMs=(?<elapsedMs>\d+) tps=(?<tps>\d+(?:[.]\d+)?) minTickMs=(?<minTickMs>\d+)\s*$')
    if(-not $m.Success){throw 'M6_PACE_MISMATCH：pace 格式不符'}
    $pace=[ordered]@{ticks=[long]$m.Groups['ticks'].Value;waited=[long]$m.Groups['waited'].Value;tps=[double]::Parse($m.Groups['tps'].Value,[Globalization.CultureInfo]::InvariantCulture);minTickMs=[int]$m.Groups['minTickMs'].Value}
    if($pace.waited -le 0 -or $pace.tps -gt 100.0 -or $pace.minTickMs -ne 10){throw 'M6_PACE_MISMATCH：必須 waited>0、tps<=100.0、minTickMs=10'}
    return $pace
}
function Invoke-M6OracleSelfTest {
    # 四個 scope 的 literal fixture；預期識別字不從待測 scanner 反算。
    $fixture=Join-Path $OutputRoot 'fixtures';New-Item -ItemType Directory -Path $fixture | Out-Null
    $source=Join-Path $fixture 'source';New-Item -ItemType Directory -Path $source | Out-Null
    $declarations=@('default_case:defaultBatch','legacy_case:m4_legacy_runtime','m5_case:m5_universe_fixture','m6_first:m6_universe_gate','m6_second:m6_universe_gate')
    $java=foreach($row in $declarations){$parts=$row.Split(':');'@GameTest(templateName="quantumchamber:m1_empty", batchId="'+$parts[1]+'") public void '+$parts[0]+'(TestContext context) {}'}
    Set-Content -LiteralPath (Join-Path $source 'Fixture.java') -Value ($java -join "`n") -Encoding utf8
    function Xml-Fixture([string[]]$Names,[string]$Path,[string]$Class='quantumchamber:m1_empty'){
        $nodes=foreach($name in $Names){'<testcase classname="'+$Class+'" name="fixture.'+$name+'"/>'}
        Set-Content -LiteralPath $Path -Value ('<testsuite>'+($nodes -join '')+'</testsuite>') -Encoding utf8
    }
    $checks=[Collections.Generic.List[object]]::new()
    function Reject([string]$Name,[string]$Code,[scriptblock]$Body){
        $errorText=$null;try{& $Body | Out-Null}catch{$errorText=$_.Exception.Message}
        if(-not $errorText -or -not $errorText.Contains($Code)){throw "M6_ORACLE_SELFTEST_FAILED：$Name 沒有以 $Code 拒絕：$errorText"}
        $checks.Add([ordered]@{name=$Name;expected=$Code;rejected=$true;error=$errorText});Write-Output "EXPECTED_RED $Name $Code"
    }
    $xml=Join-Path $fixture 'scope.xml'
    foreach($scope in @('default','legacy','m5','m6')){
        $names=switch($scope){'default'{@('default_case')};'legacy'{@('legacy_case')};'m5'{@('m5_case')};'m6'{@('m6_first','m6_second')}}
        Xml-Fixture $names $xml
        $selected=@(Assert-Scope $xml $scope $source)
        if($selected.Count -ne @($names).Count){throw 'M6_ORACLE_SELFTEST_FAILED：正例集合不符'}
        $checks.Add([ordered]@{name="positive-$scope";passed=$true;cases=$names})
        if($scope -ne 'm6'){
            Xml-Fixture @($names+'m6_first') $xml
            Reject "reject-m6-in-$scope" 'M6_SCOPE_MISMATCH' {Assert-Scope $xml $scope $source}
        }
    }
    Xml-Fixture @('m6_first','m6_second') $xml
    Assert-Scope $xml 'm6' $source $null | Out-Null
    Assert-Scope $xml 'm6' $source @('m6_universe_gate') | Out-Null
    Xml-Fixture @('m6_first','m6_second') $xml 'wrong:template'
    Reject 'wrong-classname' 'M6_SCOPE_MISMATCH' {Assert-Scope $xml 'm6' $source}
    foreach($names in @(@('m6_first'),@('m6_first','m6_second','default_case'),@('m6_first','m6_first'))){
        Xml-Fixture $names $xml
        Reject ('wrong-set-'+$checks.Count) 'M6_SCOPE_MISMATCH' {Assert-Scope $xml 'm6' $source}
    }
    $jar=Join-Path $fixture 'leaking.jar';$zip=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
    try{[void]$zip.CreateEntry('dev/quantumchamber/gametest/M6Leak.class')}finally{$zip.Dispose()}
    Reject 'release-testmod-leak' 'M6_TESTMOD_LEAK' {Assert-M6Artifact $jar}
    $okJar=Join-Path $fixture 'allowed.jar';$zip=[IO.Compression.ZipFile]::Open($okJar,[IO.Compression.ZipArchiveMode]::Create)
    try{[void]$zip.CreateEntry('dev/quantumchamber/compat/ModPresenceProbe.class')}finally{$zip.Dispose()}
    Assert-M6Artifact $okJar | Out-Null
    foreach($name in @('legacy_case','m5_case')){
        Xml-Fixture @('m6_first','m6_second',$name) $xml
        Reject "reject-$name-in-m6" 'M6_SCOPE_MISMATCH' {Assert-Scope $xml 'm6' $source}
    }
    Xml-Fixture @('m6_first','m6_second') $xml
    [xml]$skip=Get-Content -LiteralPath $xml -Raw
    [void]$skip.SelectSingleNode('//testcase').AppendChild($skip.CreateElement('skipped'));$skip.Save($xml)
    Reject 'reject-skipped-case' 'M6_SCOPE_MISMATCH' {Assert-Scope $xml 'm6' $source}
    $outer=Join-Path $fixture 'nested.jar';$zip=[IO.Compression.ZipFile]::Open($outer,[IO.Compression.ZipArchiveMode]::Create)
    try{[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$jar,'META-INF/jars/inner.jar')}finally{$zip.Dispose()}
    Reject 'nested-testmod-leak' 'M6_TESTMOD_LEAK' {Assert-M6Artifact $outer}
    $log=Join-Path $fixture 'runtime.log';$root=Join-Path $fixture 'run';New-Item -ItemType Directory -Path $root | Out-Null
    $valid='GameTest tick 節流 結束：ticks=20 waited=19 waitedMs=190 elapsedMs=200 tps=100.0 minTickMs=10'
    Set-Content -LiteralPath $log -Value $valid;Assert-M6Runtime $log $root | Out-Null
    foreach($bad in @($valid.Replace('waited=19','waited=0'),$valid.Replace('tps=100.0','tps=100.1'),($valid+"`n"+$valid))){
        Set-Content -LiteralPath $log -Value $bad
        Reject ('invalid-pace-'+$checks.Count) 'M6_PACE_MISMATCH' {Assert-M6Runtime $log $root}
    }
    Set-Content -LiteralPath $log -Value ($valid+"`nM5_UNIVERSE_SUITE_END")
    Reject 'm5-observer-in-m6' 'M6_SCOPE_MISMATCH' {Assert-M6Runtime $log $root}
    Set-Content -LiteralPath $log -Value $valid;Set-Content -LiteralPath (Join-Path $root 'm4-boundary-fixture.json') -Value '{}'
    Reject 'm4-observer-in-m6' 'M6_SCOPE_MISMATCH' {Assert-M6Runtime $log $root}
    # 最後一個負例不可污染正式 scope receipt。
    Save-Json (Join-Path $OutputRoot 'selftest.json') ([ordered]@{status='PASS';head=$head;checks=@($checks.ToArray());multiCaseBatch=2})
    Write-Output "M6_ORACLE_SELFTEST_PASS checks=$($checks.Count) HEAD=$head"
}
if($SelfTest){Invoke-M6OracleSelfTest;return}
$actualPlatform=if($IsWindows){'Windows'}elseif($IsLinux){'Ubuntu'}else{'Unsupported'}
if($CheckUnsupportedNative){
    $valid=$Platform -eq 'Ubuntu' -and $actualPlatform -eq 'Ubuntu'
    Save-Json (Join-Path $OutputRoot 'unsupported-native.json') ([ordered]@{head=$head;platform=$Platform;actualPlatform=$actualPlatform;status=$(if($valid){'UNSUPPORTED'}else{'FAIL'});reason='M6_NATIVE_WINDOWS_REQUIRED';supported=$false;nativeExecuted=$false})
    if(-not $valid){throw 'M6_PLATFORM_UNSUPPORTED：合法排除只限實際 Ubuntu 的 Windows native chains'}
    Write-Output 'M6_NATIVE_WINDOWS_REQUIRED：Ubuntu native chains 不支援，未執行也未宣稱 PASS';return
}
$native='NOT_REQUESTED'
if($VerifyOnly -and $Platform -eq 'Ubuntu'){
    if(-not $UnsupportedNativeReceipt -or -not(Test-Path -LiteralPath $UnsupportedNativeReceipt -PathType Leaf)){throw 'M6_NATIVE_EXCLUSION_MISSING：Ubuntu 必須有明確不支援 receipt'}
    $unsupported=Get-Content -LiteralPath $UnsupportedNativeReceipt -Raw | ConvertFrom-Json
    if($unsupported.head -cne $head -or $unsupported.platform -cne 'Ubuntu' -or $unsupported.actualPlatform -cne 'Ubuntu' -or $unsupported.status -cne 'UNSUPPORTED' -or $unsupported.reason -cne 'M6_NATIVE_WINDOWS_REQUIRED' -or $unsupported.supported -ne $false -or $unsupported.nativeExecuted -ne $false){throw 'M6_NATIVE_EXCLUSION_MISMATCH：不得把排除冒充 native PASS'}
    $native='UNSUPPORTED'
}
$launched=-not $VerifyOnly
if($launched){
    if($Platform -cne $actualPlatform){throw 'M6_PLATFORM_UNSUPPORTED：不可模擬其他平台的 CI'}
    $expected=Join-Path $repo $(if($IsWindows){'gradlew.bat'}else{'gradlew'})
    if(-not $Gradle){$Gradle=$expected}
    if([IO.Path]::GetFullPath($Gradle) -cne [IO.Path]::GetFullPath($expected) -or -not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw 'M6_CI_WRAPPER_REQUIRED：CI 只能使用 checkout wrapper'}
    $wrapper=Get-Content -LiteralPath (Join-Path $repo 'gradle/wrapper/gradle-wrapper.properties') -Raw
    if($wrapper -notmatch 'gradle-8[.]8-bin[.]zip'){throw 'M6_CI_WRAPPER_REQUIRED：wrapper distro 必須是 8.8'}
    $version=(& java -version 2>&1 | Out-String);if($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21[.]'){throw 'M6_JAVA_21_REQUIRED'}
    $RuntimeRoot=Check-Path (Join-Path $repo 'run/gametest-m6')
    $legacyRoot=Check-Path (Join-Path $repo 'run/gametest-legacy')
    if((Test-Path -LiteralPath (Join-Path $legacyRoot 'world')) -and @(Get-ChildItem -LiteralPath (Join-Path $legacyRoot 'world') -Force).Count){throw 'M6_FRESH_ROOT_REQUIRED：CI legacy 世界必須為空或不存在'}
    if((Test-Path -LiteralPath (Join-Path $RuntimeRoot 'world')) -and @(Get-ChildItem -LiteralPath (Join-Path $RuntimeRoot 'world') -Force).Count){throw 'M6_FRESH_ROOT_REQUIRED：CI M6 世界必須為空或不存在'}
    $launchStart=[DateTime]::UtcNow
    & $Gradle test runGameTestLegacy runGameTestM6 --rerun-tasks --no-daemon --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'gradle.log')
    $exit=$LASTEXITCODE
    $snapshot=Join-Path $OutputRoot 'originals';New-Item -ItemType Directory -Path $snapshot | Out-Null
    foreach($path in @('build/gametest-results.xml','build/gametest-legacy-results.xml','build/gametest-m5-results.xml','build/gametest-m6-results.xml','run/gametest-m6/logs/latest.log','run/gametest-legacy/logs/latest.log')){
        $source=Join-Path $repo $path
        if(Test-Path -LiteralPath $source -PathType Leaf){$target=Join-Path $snapshot $path;New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null;Copy-Item -LiteralPath $source -Destination $target}
    }
    Save-Json (Join-Path $OutputRoot 'execution.json') ([ordered]@{head=$head;platform=$Platform;wrapper=$Gradle;javaVersion=$version;tasks=@('test','runGameTestLegacy','runGameTestM6');exitCode=$exit;start=$launchStart.ToString('o');end=[DateTime]::UtcNow.ToString('o')})
    if($exit -ne 0){throw "M6_CI_GRADLE_FAILED exit=$exit"}
    $GameTestXml=Join-Path $repo 'build/gametest-m6-results.xml';$RuntimeLog=Join-Path $RuntimeRoot 'logs/latest.log'
    if((Get-Item -LiteralPath $GameTestXml).LastWriteTimeUtc -lt $launchStart -or (Get-Item -LiteralPath $RuntimeLog).LastWriteTimeUtc -lt $launchStart){throw 'M6_SCOPE_MISMATCH：XML／日誌不是本次 invocation'}
    $Artifacts=@(Get-ChildItem -LiteralPath (Join-Path $repo 'build/libs') -File -Filter '*.jar' | ForEach-Object FullName)
    if($Artifacts.Count -ne 2){throw 'M6_TESTMOD_LEAK：必須核對 release 與 sources 兩個 JAR'}
}
if(-not $GameTestXml -or -not $RuntimeLog -or -not $RuntimeRoot){throw 'M6_ORACLE_INPUT_REQUIRED：必須指定本次 XML、完整日誌與 run root'}
$only=if($Batch){@($Batch.Split(','))}else{@()}
Assert-Scope $GameTestXml 'm6' $SourceRoot $only | Out-Null
$pace=Assert-M6Runtime $RuntimeLog $RuntimeRoot
$jars=@(foreach($artifact in $Artifacts){Assert-M6Artifact $artifact})
$scopes=[ordered]@{m6=(Get-Content -LiteralPath (Join-Path $OutputRoot 'scope.json') -Raw | ConvertFrom-Json)}
if($launched){
    foreach($row in @('default:gametest-results.xml','legacy:gametest-legacy-results.xml','m5:gametest-m5-results.xml')){
        $parts=$row.Split(':');$gate=Join-Path $OutputRoot $parts[0];New-Item -ItemType Directory -Path $gate | Out-Null
        Assert-Scope (Join-Path $repo ('build/'+$parts[1])) $parts[0] $SourceRoot | Out-Null
        $scopes[$parts[0]]=Get-Content -LiteralPath (Join-Path $gate 'scope.json') -Raw | ConvertFrom-Json
    }
    $gate=$OutputRoot
}
if((git -C $repo rev-parse HEAD).Trim() -cne $head){throw 'M6_HEAD_CHANGED：執行期間 HEAD 改變'}
Save-Json (Join-Path $OutputRoot 'm6-summary.json') ([ordered]@{status='PASS';head=$head;platform=$Platform;actualPlatform=$actualPlatform;remoteCi=($env:GITHUB_ACTIONS -ceq 'true');launched=$launched;runtimeScope='testmod-present';scope=$scopes;pacing=$pace;boundaryReceipts=0;m5SuiteEnds=0;xmlSha256=(Get-FileHash -LiteralPath $GameTestXml).Hash;logSha256=(Get-FileHash -LiteralPath $RuntimeLog).Hash;artifacts=$jars;native=$native})
Write-Output "M6_SUMMARY PASS HEAD=$head waited=$($pace.waited) tps=$($pace.tps) output=$OutputRoot"
