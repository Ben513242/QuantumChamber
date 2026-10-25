# M6 跨平台 CI 啟動與結果 oracle；本機 VerifyOnly 只核對指定原件，不冒稱遠端 CI。
param([Parameter(Mandatory)][ValidateSet('Windows','Ubuntu')][string]$Platform,
      [switch]$VerifyOnly, [switch]$CheckUnsupportedNative, [switch]$SelfTest,
      [string]$Gradle='', [string]$GameTestXml='', [string]$RuntimeLog='', [string]$RuntimeRoot='',
      [string]$OutputRoot='', [string]$SourceRoot='', [string]$Batch='', [string[]]$Artifacts=@(), [string]$UnsupportedNativeReceipt='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
# 僅新 M6 CI 路徑：目標與每層祖先直接看項目，斷鏈仍能看到 reparse。
function Assert-M6DirectoryPath([string]$Path,[string]$Code='M6_OUTPUT_UNSAFE'){
    $full=[IO.Path]::GetFullPath($Path)
    $comparison=if($IsWindows){[StringComparison]::OrdinalIgnoreCase}else{[StringComparison]::Ordinal}
    if(-not $full.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,$comparison)){throw "${Code}：目標必須在 checkout 內：$full"}
    $cursor=$full
    while($cursor){
        $item=$null
        try{$item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop}
        catch [Management.Automation.ItemNotFoundException] { }
        catch {throw "${Code}：無法證明目錄安全：$cursor；$($_.Exception.Message)"}
        if($null -ne $item){
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "${Code}：拒絕 reparse：$cursor"}
            if(-not $item.PSIsContainer){throw "${Code}：拒絕非目錄：$cursor"}
        }
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}
function Assert-M6FreshWorld([string]$World){
    $full=Assert-M6DirectoryPath $World 'M6_FRESH_ROOT_UNSAFE'
    $children=@()
    try{$children=@(Get-ChildItem -LiteralPath $full -Force -ErrorAction Stop)}
    catch [Management.Automation.ItemNotFoundException] { }
    catch {throw "M6_FRESH_ROOT_UNSAFE：無法證明世界為空：$full；$($_.Exception.Message)"}
    if($children.Count){throw "M6_FRESH_ROOT_REQUIRED：世界必須為空或不存在：$full"}
    return $full
}
if(-not $OutputRoot){$OutputRoot=Join-Path $repo 'build/m6-verification'}
$OutputRoot=Assert-M6DirectoryPath $OutputRoot
git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$OutputRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw "M6_OUTPUT_NOT_IGNORED：首次寫入前無法證明輸出被gitignore：$OutputRoot"}
if(-not(Test-Path -LiteralPath $OutputRoot)){New-Item -ItemType Directory -Path $OutputRoot | Out-Null}
$gate=$OutputRoot
if(-not $SourceRoot){$SourceRoot=Join-Path $repo 'src/testmod/java'}
$head=(git -C $repo rev-parse HEAD).Trim()
# 只載入同 checkout 的五個具名純 oracle 函式；不執行 Windows runtime／move 入口。
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'gates.ps1'),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'M6_ORACLE_PARSE_FAILED'}
foreach($name in @('Save-Json','Distinct-Count','Batch-Scope','Declared-GameTests','Assert-Scope')){
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
            Xml-Fixture (@($names)+@('m6_first')) $xml
            [xml]$crossScope=Get-Content -LiteralPath $xml -Raw
            $crossNames=@($crossScope.SelectNodes('//testcase') | ForEach-Object name)
            if($crossNames.Count -ne 2 -or ('fixture.'+@($names)[0]) -cnotin $crossNames -or 'fixture.m6_first' -cnotin $crossNames){throw "M6_ORACLE_SELFTEST_FAILED：reject-m6-in-$scope 沒有保留合法case與獨立M6case：[$($crossNames -join ',')]"}
            Reject "reject-m6-in-$scope" 'M6_SCOPE_MISMATCH' {Assert-Scope $xml $scope $source}
            $crossReceipt=Get-Content -LiteralPath (Join-Path $gate 'scope.json') -Raw | ConvertFrom-Json
            if($crossReceipt.actual -ne 2 -or @($crossReceipt.missing).Count -ne 0 -or @($crossReceipt.extra).Count -ne 1 -or $crossReceipt.extra[0] -cne 'quantumchamber:m1_empty|fixture.m6_first'){throw 'M6_ORACLE_SELFTEST_FAILED：跨scope負例不精準'}
            $checks.Add([ordered]@{name="cross-scope-fixture-$scope";passed=$true;actual=$crossReceipt.actual;actualCases=$crossReceipt.actualCases;missing=$crossReceipt.missing;extra=$crossReceipt.extra})
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
    foreach($entryName in @('dev/quantumchamber/chamber/ChamberLoadSyncTestAccess.class','dev/quantumchamber/chamber/ChamberLoadSyncTestAccess.java')){
        $rawJar=Join-Path $fixture ('raw-test-access-'+$checks.Count+'.jar')
        $zip=[IO.Compression.ZipFile]::Open($rawJar,[IO.Compression.ZipArchiveMode]::Create)
        try{[void]$zip.CreateEntry($entryName)}finally{$zip.Dispose()}
        Reject ('unmarked-testmod-leak-'+$checks.Count) 'M6_TESTMOD_LEAK' {Assert-M6Artifact $rawJar}
    }
    # 新CI可寫目錄：所有案例只觸及本nonce fixture，連結與失敗現場保留。
    $paths=Join-Path $fixture 'path-guards';New-Item -ItemType Directory -Path $paths | Out-Null
    $outside=Join-Path $paths 'controlled-outside';New-Item -ItemType Directory -Path $outside | Out-Null
    $before=@(Get-ChildItem -LiteralPath $outside -Force).Count
    $absent=Join-Path $paths 'absent/world';Assert-M6FreshWorld $absent | Out-Null
    $empty=Join-Path $paths 'empty/world';New-Item -ItemType Directory -Path $empty -Force | Out-Null
    Assert-M6FreshWorld $empty | Out-Null
    $absentPreserved=-not(Test-Path -LiteralPath $absent);$emptyPreserved=@(Get-ChildItem -LiteralPath $empty -Force).Count -eq 0
    if(-not $absentPreserved -or -not $emptyPreserved){throw 'M6_ORACLE_SELFTEST_FAILED：fresh守門不得建立absent或改變empty'}
    $checks.Add([ordered]@{name='fresh-physical-empty-and-absent';passed=$true;absentPreserved=$absentPreserved;emptyPreserved=$emptyPreserved})
    $nonempty=Join-Path $paths 'nonempty/world';New-Item -ItemType Directory -Path $nonempty -Force | Out-Null
    $sentinel=Join-Path $nonempty 'sentinel.txt';'保留原資料' | Set-Content -LiteralPath $sentinel -Encoding utf8
    $sentinelHash=(Get-FileHash -LiteralPath $sentinel).Hash
    Reject 'fresh-reject-nonempty-world' 'M6_FRESH_ROOT_REQUIRED' {Assert-M6FreshWorld $nonempty}
    $file=Join-Path $paths 'world-file';'不是目錄' | Set-Content -LiteralPath $file -Encoding utf8
    Reject 'fresh-reject-file-world' 'M6_FRESH_ROOT_UNSAFE' {Assert-M6FreshWorld $file}
    $linkKind=if($IsWindows){'Junction'}else{'SymbolicLink'}
    $linked=Join-Path $paths 'linked/world';New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($linked)) | Out-Null
    New-Item -ItemType $linkKind -Path $linked -Target $outside | Out-Null
    Reject 'fresh-reject-world-reparse' 'M6_FRESH_ROOT_UNSAFE' {Assert-M6FreshWorld $linked}
    $ancestor=Join-Path $paths 'ancestor';New-Item -ItemType $linkKind -Path $ancestor -Target $outside | Out-Null
    Reject 'fresh-reject-ancestor-reparse' 'M6_FRESH_ROOT_UNSAFE' {Assert-M6FreshWorld (Join-Path $ancestor 'world')}
    Reject 'output-reject-target-reparse' 'M6_OUTPUT_UNSAFE' {Assert-M6DirectoryPath $ancestor}
    Reject 'output-reject-ancestor-reparse' 'M6_OUTPUT_UNSAFE' {Assert-M6DirectoryPath (Join-Path $ancestor 'child')}
    $missingTarget=Join-Path $paths 'target-to-retain';New-Item -ItemType Directory -Path $missingTarget | Out-Null
    $dangling=Join-Path $paths 'dangling';New-Item -ItemType $linkKind -Path $dangling -Target $missingTarget | Out-Null
    # 只搬本fixture剛建的空目錄；驗絕對路徑在owned範圍，不刪任何項目。
    $retained=Join-Path $paths 'retained-target'
    foreach($path in @($missingTarget,$retained)){if([IO.Path]::GetFullPath($path) -cne $path -or -not $path.StartsWith($paths+[IO.Path]::DirectorySeparatorChar,[StringComparison]::Ordinal)){throw 'M6_ORACLE_SELFTEST_FAILED：fixture move 路徑不符'}}
    if(Test-Path -LiteralPath $retained){throw 'M6_ORACLE_SELFTEST_FAILED：fixture保留目標碰撞'}
    Move-Item -LiteralPath $missingTarget -Destination $retained
    Reject 'fresh-reject-dangling-reparse' 'M6_FRESH_ROOT_UNSAFE' {Assert-M6FreshWorld $dangling}
    Reject 'output-reject-dangling-ancestor' 'M6_OUTPUT_UNSAFE' {Assert-M6DirectoryPath (Join-Path $dangling 'child')}
    $ioPath=Join-Path $paths 'controlled-io-error'
    function Get-Item {
        param([string]$LiteralPath,[switch]$Force,[string]$ErrorAction)
        if($LiteralPath -ceq $ioPath){throw [IO.IOException]::new('INJECTED_M6_PATH_IO_FAILURE')}
        Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath -Force:$Force -ErrorAction $ErrorAction
    }
    Reject 'fresh-reject-unproven-io' 'M6_FRESH_ROOT_UNSAFE' {Assert-M6FreshWorld $ioPath}
    Reject 'output-reject-unproven-io' 'M6_OUTPUT_UNSAFE' {Assert-M6DirectoryPath $ioPath}
    if(@(Get-ChildItem -LiteralPath $outside -Force).Count -ne $before -or (Get-FileHash -LiteralPath $sentinel).Hash -cne $sentinelHash -or (Test-Path -LiteralPath $missingTarget) -or -not(Test-Path -LiteralPath $retained)){throw 'M6_ORACLE_SELFTEST_FAILED：受控外側／原檔／dangling target狀態改變'}
    $checks.Add([ordered]@{name='path-guard-outside-and-original-preserved';passed=$true;outside=$outside;outsideItems=$before;sentinelSha256=$sentinelHash;linkKind=$linkKind;danglingTargetRetained=$retained})
    $nonignored=Join-Path $repo ('m6-unignored-control-'+[guid]::NewGuid().ToString('N'))
    if(Test-Path -LiteralPath $nonignored){throw 'M6_ORACLE_SELFTEST_FAILED：控制目標碰撞'}
    # 直接呼叫正式入口；VerifyOnly避免守門回歸時遞迴跑SelfTest，也不啟動Gradle。
    Reject 'output-reject-nonignored-before-first-write' 'M6_OUTPUT_NOT_IGNORED' {& $PSCommandPath -Platform $Platform -VerifyOnly -OutputRoot $nonignored}
    if(Test-Path -LiteralPath $nonignored){throw "M6_ORACLE_SELFTEST_FAILED：非ignored輸出先被建立：$nonignored"}
    $checks.Add([ordered]@{name='nonignored-output-remains-absent';passed=$true;path=$nonignored;observedExists=$false})
    if((git -C $repo rev-parse HEAD).Trim() -cne $head){throw 'M6_HEAD_CHANGED：SelfTest期間HEAD改變'}
    # 最後一個負例不可污染正式 scope receipt。
    $processHarness=if($IsWindows){
        $deadlineRoot=Join-Path $OutputRoot 'deadline-harness'
        & (Join-Path $PSScriptRoot 'm6-deadline-check.ps1') -OutputRoot $deadlineRoot | Out-Null
        $receipt=Get-Content -LiteralPath (Join-Path $deadlineRoot 'summary.json') -Raw | ConvertFrom-Json
        if($receipt.status -cne 'PASS' -or $receipt.head -cne $head -or $receipt.minecraftLaunched -ne $false){throw 'M6_DEADLINE_CONTRACT_MISMATCH：正式helper不是同HEAD純process檢查'}
        [ordered]@{status='PASS';executed=$true;receipt=(Join-Path $deadlineRoot 'summary.json');receiptSha256=(Get-FileHash -LiteralPath (Join-Path $deadlineRoot 'summary.json')).Hash}
    }else{[ordered]@{status='UNSUPPORTED';executed=$false;reason='WINDOWS_PROCESS_HARNESS_ONLY';minecraftLaunched=$false}}
    Save-Json (Join-Path $OutputRoot 'selftest.json') ([ordered]@{status='PASS';head=$head;checks=@($checks.ToArray());multiCaseBatch=2;windowsProcessHarness=$processHarness})
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
    $RuntimeRoot=Join-Path $repo 'run/gametest-m6'
    $legacyRoot=Join-Path $repo 'run/gametest-legacy'
    Assert-M6FreshWorld (Join-Path $legacyRoot 'world') | Out-Null
    Assert-M6FreshWorld (Join-Path $RuntimeRoot 'world') | Out-Null
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
