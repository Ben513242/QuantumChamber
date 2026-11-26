# M7薄接線：只跑JUnit/GT，沿用M6純oracle與既有Windows gates owner。
# -Segment（T4 B2b-5，使用者 2026-11-18 裁示「規模測試獨立一段」）：Main＝M7 JUnit（不含規模測試）＋GameTest＋兩 JAR；
# Scale＝只跑 m7-inventory.json scale 集合的 JUnit（M7HomeHistoryScaleTest），自己的 Gradle 呼叫、自己的 OutputRoot。兩段的 JUnit class 互斥，聯集等於原始碼宣告的全部 M7 JUnit。
# GameTest 拆段（使用者 2026-11-25 裁示「拆成兩段」）：GameTest 依 m7-inventory.json gametestSegments 的類別分段（段名與各段類別以清單為準，目前是 Main、Corridor、Select；
# 本腳本只讀清單，CI 的 step 依段名寫死，加段時見 README「M7 GameTest 拆段」）。
# Main 保留分到的 GameTest、全部非規模 JUnit 與兩 JAR；其他段只跑自己的 GameTest（JVM 內由 M4LegacyGameTestFilterMixin 依同一份清單選類別）。
# 各段一次 Gradle 呼叫、自己的 OutputRoot、exact 集合與隔離、SelfTest；各段類別互斥、聯集等於原始碼宣告的全部 M7 GameTest 類別。
# -Segment 必須明確指定（Scale 或 gametestSegments 的段名，區分大小寫）；不給或不在清單都 fail closed，不會靜默只跑其中一段。
param([ValidateSet('Windows','Ubuntu')][string]$Platform=$(if($IsWindows){'Windows'}else{'Ubuntu'}),
      [string]$Segment='',
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
if($inventory.schema -ne 5){throw 'M7_INVENTORY_SCHEMA：m7-inventory.json 必須是 schema 5'}
$platformKey=$Platform.ToLowerInvariant()
$mainJUnit=@($inventory.portable.junit)+@($inventory.$platformKey.junit)
$scaleJUnit=@($inventory.scale.portable.junit)+@($inventory.scale.$platformKey.junit)
$gameSegmentTable=$inventory.gametestSegments
$gameSegmentNames=@(if($null -ne $gameSegmentTable){$gameSegmentTable.PSObject.Properties.Name})
if(-not $Segment){throw "M7_SEGMENT_REQUIRED：-Segment 必須明確指定（Scale、$($gameSegmentNames -join '、')）"}
function Exact-M7([string[]]$Expected,[string[]]$Actual,[string]$Code){
    if(-not $Expected.Count -or $Expected.Count -ne $Actual.Count -or @($Actual|Sort-Object -Unique).Count -ne $Actual.Count -or @(Compare-Object ($Expected|Sort-Object) ($Actual|Sort-Object) -CaseSensitive).Count){throw $Code}
}
# 段名：Scale 或 gametestSegments 的鍵（區分大小寫）；不在清單即 fail closed。
function Assert-M7SegmentName([object]$Table,[string]$Name){
    $names=@(if($null -ne $Table){$Table.PSObject.Properties.Name})
    if($Name -cne 'Scale' -and $Name -cnotin $names){throw "M7_SEGMENT_UNKNOWN：$Name"}
}
# GameTest identity（template|類別小寫.方法，與 XML 的 classname|name 同形）取類別小寫。
function M7-GameClass([string]$Identity){$name=$Identity.Substring($Identity.IndexOf('|')+1);$dot=$name.IndexOf('.');if($dot -le 0){throw "M7_GAME_SEGMENT_FORM：$Identity"};return $name.Substring(0,$dot)}
# GameTest 段分配（m7-inventory.json gametestSegments）：每段至少一個合法類別名、段間互斥（不分大小寫，同段重複也算）；各段聯集必須恰等於原始碼宣告
# m7_home GameTest 的類別（不分平台）：漏列是 CLASS_UNLISTED，列了卻沒有宣告是 CLASS_UNDECLARED。回傳指定段的類別、本段與其他段的小寫類別
# （XML testcase 名稱的前綴）與本平台的 exact 集合；本段在本平台沒有任何案例是 M7_GAME_SEGMENT_EMPTY。
function Get-M7GameSegment([object]$Table,[string]$Name,[object[]]$Declared,[string[]]$PlatformGame){
    Assert-M7SegmentName $Table $Name
    if($Name -ceq 'Scale'){throw 'M7_GAME_SEGMENT_FORM：Scale 不是 GameTest 段'}
    $owner=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal);$listed=@()
    foreach($property in $Table.PSObject.Properties){
        $classes=@(if($property.Value -is [pscustomobject] -and $property.Value.PSObject.Properties.Name -ccontains 'classes'){$property.Value.classes})
        if($property.Name -cnotmatch '^[A-Z][A-Za-z0-9]*$' -or $property.Name -ceq 'Scale' -or -not $classes.Count -or @($classes|Where-Object {$_ -isnot [string] -or $_ -cnotmatch '^M7[A-Za-z0-9]*GameTests$'}).Count){throw "M7_GAME_SEGMENT_FORM：$($property.Name)"}
        foreach($class in $classes){$lower=$class.ToLowerInvariant();if($owner.ContainsKey($lower)){throw "M7_GAME_SEGMENT_OVERLAP：$class"};$owner[$lower]=$property.Name;$listed+=$class}
    }
    $declaredClasses=@($Declared|Where-Object {$_.scope -ceq 'm7_home'}|ForEach-Object {$_.class}|Sort-Object -Unique -CaseSensitive)
    foreach($class in $declaredClasses){if($class -cnotin $listed){throw "M7_GAME_SEGMENT_CLASS_UNLISTED：$class"}}
    foreach($class in $listed){if($class -cnotin $declaredClasses){throw "M7_GAME_SEGMENT_CLASS_UNDECLARED：$class"}}
    $own=@($Table.PSObject.Properties[$Name].Value.classes)
    $ownLower=@($own|ForEach-Object {$_.ToLowerInvariant()})
    $otherLower=@($owner.Keys|Where-Object {$owner[$_] -cne $Name})
    $expected=@($PlatformGame|Where-Object {(M7-GameClass $_) -cin $ownLower})
    if(-not $expected.Count){throw "M7_GAME_SEGMENT_EMPTY：$Name"}
    return [pscustomobject]@{name=$Name;classes=$own;ownLower=$ownLower;otherLower=$otherLower;expected=$expected;expectedClasses=@($expected|ForEach-Object {M7-GameClass $_}|Sort-Object -Unique -CaseSensitive)}
}
Assert-M7SegmentName $gameSegmentTable $Segment
function M7-Classes([string[]]$Cases){return @($Cases|ForEach-Object {$_.Substring(0,$_.LastIndexOf('.'))}|Sort-Object -Unique -CaseSensitive)}
function Assert-M7JUnitDeclarations([string]$Source,[string[]]$Expected){
    $expectedClasses=@(M7-Classes $Expected)
    $declaredClasses=@()
    $declaredCases=@()
    $commentsAndLiterals='(?s)"""(?:\\.|(?!""").)*"""|"(?:\\.|[^"\\\r\n])*"|''(?:\\.|[^''\\\r\n])*''|//[^\r\n]*|/\*.*?\*/'
    $annotationName='[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*'
    $testMethod=[regex]::new('\G\s*(?:\(\s*\)\s*)?(?:(?:@'+$annotationName+'(?:\s*\([^()]*\))?|public|protected|final|synchronized|strictfp)\s+)*void\s+(?<name>[A-Za-z_$][\w$]*)\s*\(\s*\)')
    $testAnnotations=@('Test','org.junit.jupiter.api.Test')
    $metadata=@('BeforeAll','AfterAll','BeforeEach','AfterEach','DisplayName','Order','Tag','TestInstance','TestMethodOrder','Timeout','Disabled')
    $allowedAnnotations=$metadata + @($metadata|ForEach-Object {'org.junit.jupiter.api.'+$_}) +
        @('Override','java.lang.Override','SuppressWarnings','java.lang.SuppressWarnings','TempDir','org.junit.jupiter.api.io.TempDir')
    foreach($file in Get-ChildItem -LiteralPath $Source -Filter 'M7*Test.java' -Recurse -File){
        $raw=Get-Content -LiteralPath $file.FullName -Raw
        $java=[regex]::Replace([string]$raw,$commentsAndLiterals,{param($token) $token.Value -replace '[^\r\n]',' '})
        $package=[regex]::Match($java,'(?m)^\s*package\s+(?<name>[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)\s*;')
        if(-not $package.Success){throw "M7_JUNIT_DECLARATION_FORM：$($file.Name) 缺少可辨識的 package"}
        $class=($package.Groups['name'].Value -replace '\s','')+'.'+$file.BaseName
        if($class -cnotin $expectedClasses){throw "M7_JUNIT_CLASS_UNLISTED：$class"}
        $declaredClasses+=$class
        $methodCount=0
        foreach($annotation in [regex]::Matches($java,'@\s*(?<name>'+$annotationName+')')){
            $name=$annotation.Groups['name'].Value -replace '\s',''
            if($name -cin $testAnnotations){
                $method=$testMethod.Match($java,$annotation.Index+$annotation.Length)
                if(-not $method.Success){throw "M7_JUNIT_DECLARATION_FORM：$class @$name 需要可辨識的無參數 void 方法"}
                $declaredCases+="$class."+$method.Groups['name'].Value
                $methodCount++
            }elseif($name -cnotin $allowedAnnotations){
                throw "M7_JUNIT_DECLARATION_FORM：$class @$name"
            }
        }
        if($methodCount -eq 0){throw "M7_JUNIT_DECLARATION_FORM：$class 沒有可辨識的 @Test 方法"}
    }
    Exact-M7 $expectedClasses $declaredClasses 'M7_JUNIT_CLASS_SET'
    Exact-M7 $Expected $declaredCases 'M7_JUNIT_DECLARATION_SET'
}
# Gradle 以 class 篩選，所以兩段的 class 必須互斥（同一個 class 的方法不得分在兩段）。
$mainClasses=@(M7-Classes $mainJUnit);$scaleClasses=@(M7-Classes $scaleJUnit)
if(-not $mainClasses.Count -or -not $scaleClasses.Count -or @($mainClasses|Where-Object {$_ -cin $scaleClasses}).Count){throw 'M7_JUNIT_SEGMENT_OVERLAP'}
$junitSource=Join-Path $repo 'src/test/java'
$allJUnit=@($mainJUnit)+@($scaleJUnit)
$gameSegment=$Segment -cne 'Scale'
# 本平台全部 M7 GameTest（各 GameTest 段的聯集；仍與原始碼宣告 exact，M7_DECLARATION_SET）。
$platformGame=@($inventory.portable.gametest)+@($inventory.$platformKey.gametest)
if(-not $gameSegment){$expectedGame=@();$expectedJUnit=$scaleJUnit;$testClasses=$scaleClasses;$proofScope='m7_scale'}
else{
    $declaredGame=@(Declared-GameTests $SourceRoot)
    $segmentInfo=Get-M7GameSegment $gameSegmentTable $Segment $declaredGame $platformGame
    $expectedGame=@($segmentInfo.expected);$proofScope='m7_home'
    # 只有 Main 段執行非規模 JUnit 與兩個 JAR；其他 GameTest 段沒有 JUnit。
    if($Segment -ceq 'Main'){$expectedJUnit=$mainJUnit;$testClasses=$mainClasses}else{$expectedJUnit=@();$testClasses=@()}
}
# live發布必須重讀真正HEAD；歷史VerifyOnly不走此守門。
function Assert-M7LiveHead([string]$Pin){
    $observed=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0 -or $observed -cne $Pin){throw 'M7_HEAD_CHANGED'}
}
function Assert-M7SavedSummary($Saved,$Proof){
    $identity=@('schema','status','head','platform','actualPlatform','scope','inputsSha256');if($gameSegment){$identity+='segment'}
    foreach($field in $identity){
        if($Saved.PSObject.Properties.Name -cnotcontains $field -or $Saved.$field -cne $Proof[$field]){throw 'M7_SAVED_SUMMARY'}
    }
    if($Proof.Contains('junitCases')){Exact-M7 @($Proof.junitCases) @($Saved.junitCases) 'M7_SAVED_JUNIT_CASES'}
    # Scale 段的 summary 只有 JUnit：欄位集合必須恰等於重算的 proof（不得夾帶 GameTest／pacing／mods／JAR）。
    if($Segment -ceq 'Scale'){if((@($Saved.PSObject.Properties.Name|Sort-Object) -join ',') -cne (@($Proof.Keys|Sort-Object) -join ',')){throw 'M7_SAVED_SUMMARY'};return}
    Exact-M7 @($Proof.gameCases) @($Saved.gameCases) 'M7_SAVED_GAME_CASES'
    foreach($field in @('ticks','waited','tps','minTickMs')){if($Saved.pacing.PSObject.Properties.Name -cnotcontains $field -or $Saved.pacing.$field -ne $Proof.pacing[$field]){throw 'M7_SAVED_PACING'}}
    if((@($Saved.loadedMods|Sort-Object)|ConvertTo-Json -Compress) -cne (@($Proof.loadedMods|Sort-Object)|ConvertTo-Json -Compress)){throw 'M7_SAVED_MODS'}
    # Main 以外的 GameTest 段沒有 JUnit 與 JAR：欄位集合必須恰等於重算的 proof。
    if($Segment -cne 'Main'){if((@($Saved.PSObject.Properties.Name|Sort-Object) -join ',') -cne (@($Proof.Keys|Sort-Object) -join ',')){throw 'M7_SAVED_SUMMARY'};return}
    # 複本可搬位置；artifact身份按檔名＋raw重算hash/entries驗，最終消費者使用fresh proof的實際path。
    $savedNames=@($Saved.artifacts|ForEach-Object {[IO.Path]::GetFileName($_.path)});$proofNames=@($Proof.artifacts|ForEach-Object {[IO.Path]::GetFileName($_.path)})
    Exact-M7 $proofNames $savedNames 'M7_SAVED_ARTIFACT'
    foreach($artifact in $Proof.artifacts){$match=@($Saved.artifacts|Where-Object {[IO.Path]::GetFileName($_.path) -ceq [IO.Path]::GetFileName($artifact.path)})[0];foreach($field in @('sha256','entries','testmodHits')){if($match.$field -cne $artifact[$field]){throw 'M7_SAVED_ARTIFACT'}}}
}
function Save-M7Inputs([DateTime]$Start,[string]$Runtime){
    # Scale 段只收 JUnit XML（同一個 glob：任何殘留的其他 M7 XML 都會被收進來，由 Verify-M7 以數量／fresh／exact 集合拒絕）。
    # Main 以外的 GameTest 段（使用者 2026-11-25「拆成兩段」）只收 GameTest XML 與 runtime：該段不跑 JUnit、不封裝，build 內前一段留下的 JUnit XML 與 JAR 不收。
    $main=$Segment -ceq 'Main';$junitSegment=$main -or -not $gameSegment
    $raw=Join-Path $OutputRoot 'raw';New-Item -ItemType Directory -Path $raw -Force|Out-Null
    if($junitSegment){New-Item -ItemType Directory -Path (Join-Path $raw 'junit') -Force|Out-Null}
    if($main){New-Item -ItemType Directory -Path (Join-Path $raw 'artifacts') -Force|Out-Null}
    if($gameSegment){New-Item -ItemType Directory -Path (Join-Path $raw 'runtime') -Force|Out-Null}
    $records=@();$candidates=@();if($gameSegment){$candidates+=@{kind='gametest';source=(Join-Path $repo 'build/gametest-m7-results.xml');target=(Join-Path $raw 'gametest.xml')}}
    if($junitSegment){foreach($file in @(Get-ChildItem (Join-Path $repo 'build/test-results/test') -Filter 'TEST-dev.quantumchamber.*.M7*.xml' -File -ErrorAction SilentlyContinue)){$candidates+=@{kind='junit';source=$file.FullName;target=(Join-Path $raw ('junit/'+$file.Name))}}}
    if($main){foreach($file in @(Get-ChildItem (Join-Path $repo 'build/libs') -Filter '*.jar' -File -ErrorAction SilentlyContinue|Where-Object {$_.Name -notmatch '-dev[.]jar$'})){$candidates+=@{kind='artifact';source=$file.FullName;target=(Join-Path $raw ('artifacts/'+$file.Name))}}}
    if($gameSegment){
        if(Test-Path (Join-Path $Runtime 'logs')){Copy-Item -LiteralPath (Join-Path $Runtime 'logs') -Destination (Join-Path $raw 'runtime') -Recurse}
        foreach($file in @(Get-ChildItem $Runtime -Filter 'm4-boundary-*.json' -File -ErrorAction SilentlyContinue)){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $raw 'runtime')}
    }
    foreach($candidate in $candidates){if(Test-Path -LiteralPath $candidate.source -PathType Leaf){Copy-Item -LiteralPath $candidate.source -Destination $candidate.target;$item=Get-Item -LiteralPath $candidate.source;$records+=@{kind=$candidate.kind;relative=[IO.Path]::GetRelativePath($OutputRoot,$candidate.target).Replace('\','/');sha256=(Get-FileHash $candidate.target).Hash;original=$candidate.source;originalWriteUtc=$item.LastWriteTimeUtc.ToString('o');fresh=($item.LastWriteTimeUtc -ge $Start)}}}
    if($gameSegment){foreach($file in @(Get-ChildItem (Join-Path $raw 'runtime') -Recurse -File)){$records+=@{kind='runtime';relative=[IO.Path]::GetRelativePath($OutputRoot,$file.FullName).Replace('\','/');sha256=(Get-FileHash $file.FullName).Hash;original=$Runtime;originalWriteUtc=$file.LastWriteTimeUtc.ToString('o');fresh=($file.LastWriteTimeUtc -ge $Start)}}}
    $manifest=[ordered]@{schema=1;head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;segment=$Segment;runtime=$Runtime;nonce=$GameNonce;launchStartUtc=$Start.ToString('o');inputs=$records}
    Save-Json (Join-Path $OutputRoot 'inputs.json') $manifest
}
function Verify-M7([string]$Root){
    $manifest=Get-Content -LiteralPath (Join-Path $Root 'inputs.json') -Raw|ConvertFrom-Json -DateKind String
    if($manifest.schema -ne 1 -or $manifest.head -cne $ExpectedHead -or $manifest.platform -cne $Platform -or $manifest.actualPlatform -cne $actualPlatform -or $manifest.PSObject.Properties.Name -cnotcontains 'segment' -or $manifest.segment -cne $Segment){throw 'M7_INPUT_IDENTITY'}
    $inputs=@($manifest.inputs);foreach($input in $inputs){$path=[IO.Path]::GetFullPath((Join-Path $Root $input.relative));if(-not $path.StartsWith($Root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'M7_INPUT_PATH'};if((Get-FileHash $path).Hash -cne $input.sha256){throw 'M7_INPUT_HASH'}}
    $expectedClasses=@($expectedJUnit | ForEach-Object {$_.Substring(0,$_.LastIndexOf('.'))} | Sort-Object -Unique)
    $xmls=@($inputs|Where-Object {$_.kind -ceq 'gametest'});$junit=@($inputs|Where-Object {$_.kind -ceq 'junit'});$logs=@($inputs|Where-Object {$_.relative -ceq 'raw/runtime/logs/latest.log'})
    if($Segment -ceq 'Scale'){
        # Scale 段只有本次的 JUnit XML：不得有 GameTest XML、runtime log 或 JAR。
        if(@($inputs|Where-Object {$_.kind -cne 'junit'}).Count -or $junit.Count -ne $expectedClasses.Count -or @($junit|Where-Object {-not $_.fresh}).Count){throw 'M7_NOT_CURRENT'}
    }elseif($Segment -ceq 'Main'){
        if($xmls.Count -ne 1 -or $junit.Count -ne $expectedClasses.Count -or $logs.Count -ne 1 -or @(@($xmls)+@($junit)+@($logs)|Where-Object {-not $_.fresh}).Count){throw 'M7_NOT_CURRENT'}
    }elseif(@($inputs|Where-Object {$_.kind -cnotin @('gametest','runtime')}).Count -or $xmls.Count -ne 1 -or $logs.Count -ne 1 -or @(@($xmls)+@($logs)|Where-Object {-not $_.fresh}).Count){
        # Main 以外的 GameTest 段只有本次的 GameTest XML 與 runtime：不得有 JUnit XML 或 JAR。
        throw 'M7_NOT_CURRENT'
    }
    if($gameSegment){
        # 本平台全部 M7 GameTest 仍與原始碼宣告 exact；本段的 exact 集合是其中屬於本段類別的部分（Get-M7GameSegment）。
        $declarations=@(Declared-GameTests $SourceRoot|Where-Object {$_.scope -ceq 'm7_home' -and ($_.batch -notmatch '_(windows|ubuntu)$' -or $_.batch.EndsWith('_'+$Platform.ToLowerInvariant(),[StringComparison]::Ordinal))}|ForEach-Object identity)
        Exact-M7 $platformGame $declarations 'M7_DECLARATION_SET'
    }
    Assert-M7JUnitDeclarations $junitSource $allJUnit
    if($gameSegment){
        [xml]$gt=Get-Content -LiteralPath (Join-Path $Root $xmls[0].relative) -Raw
        $gameCases=@($gt.SelectNodes('//testcase')|ForEach-Object {$_.classname+'|'+$_.name})
        Assert-M7GameSegmentCases $gameCases
        Exact-M7 $expectedGame $gameCases 'M7_GAME_SET'
        if(@($gt.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_GAME_NOT_PASS'}
    }
    $cases=@()
    if($expectedJUnit.Count){
        foreach($file in $junit){[xml]$j=Get-Content -LiteralPath (Join-Path $Root $file.relative) -Raw;if(@($j.SelectNodes('//testcase/failure|//testcase/error|//testcase/skipped')).Count){throw 'M7_JUNIT_NOT_PASS'};$cases+=@($j.SelectNodes('//testcase')|ForEach-Object {$_.classname+'.'+($_.name -replace '\(\)$','')})}
        Exact-M7 $expectedJUnit $cases 'M7_JUNIT_SET'
    }
    if($Segment -ceq 'Scale'){return [ordered]@{schema=1;status='PASS';head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;scope=$proofScope;junitCases=$cases;inputsSha256=(Get-FileHash (Join-Path $Root 'inputs.json')).Hash}}
    $runtime=Join-Path $Root 'raw/runtime';$log=Join-Path $runtime 'logs/latest.log';$pace=Assert-M6Runtime $log $runtime
    $mods=@([regex]::Matches((Get-Content -LiteralPath $log -Raw),'(?m)^\s*[-|+\\ ]+\s+(?<id>[a-z0-9_-]+)\s+[^\r\n ]+\s*$')|ForEach-Object {$_.Groups['id'].Value})
    if('quantumchamber-testmod' -cnotin $mods -or 'quantumchamber' -cnotin $mods){throw 'M7_RUNTIME_MODS'}
    if($Segment -cne 'Main'){return [ordered]@{schema=1;status='PASS';head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;scope=$proofScope;segment=$Segment;gameCases=$expectedGame;pacing=$pace;loadedMods=$mods;inputsSha256=(Get-FileHash (Join-Path $Root 'inputs.json')).Hash}}
    $artifacts=@($inputs|Where-Object {$_.kind -ceq 'artifact'});if(@($artifacts|Where-Object {-not $_.fresh}).Count -or $artifacts.Count -ne 2 -or @($artifacts|Where-Object {$_.relative.EndsWith('-sources.jar',[StringComparison]::Ordinal)}).Count -ne 1){throw 'M7_ARTIFACT_ROLES'}
    foreach($artifact in $artifacts){$zip=[IO.Compression.ZipFile]::OpenRead((Join-Path $Root $artifact.relative));try{$classCount=@($zip.Entries|Where-Object {$_.FullName -cmatch '[.]class$'}).Count;$sourceCount=@($zip.Entries|Where-Object {$_.FullName -cmatch '[.]java$'}).Count;if($artifact.relative.EndsWith('-sources.jar',[StringComparison]::Ordinal)){if($sourceCount -eq 0 -or $classCount -ne 0){throw 'M7_ARTIFACT_ROLES'}}elseif($classCount -eq 0 -or $sourceCount -ne 0){throw 'M7_ARTIFACT_ROLES'}}finally{$zip.Dispose()}}
    $jars=@(foreach($artifact in $artifacts){Assert-M6Artifact (Join-Path $Root $artifact.relative)})
    return [ordered]@{schema=1;status='PASS';head=$ExpectedHead;platform=$Platform;actualPlatform=$actualPlatform;scope=$proofScope;segment=$Segment;junitCases=$cases;gameCases=$expectedGame;pacing=$pace;loadedMods=$mods;inputsSha256=(Get-FileHash (Join-Path $Root 'inputs.json')).Hash;artifacts=$jars}
}
# 分段隔離（使用者 2026-11-25「拆成兩段」）：先以類別核對，再核 exact 集合（M7_GAME_SET）。XML 混入其他段的類別是 M7_GAME_SEGMENT_FOREIGN，
# 不屬於本段（也不屬於其他段）的 M7 類別是 M7_GAME_SEGMENT_CLASS_EXTRA，本段在本平台應有的類別缺席是 M7_GAME_SEGMENT_CLASS_MISSING；非 M7 類別留給 M7_GAME_SET。
function Assert-M7GameSegmentCases([string[]]$Cases){
    $present=@()
    foreach($case in $Cases){
        $name=$case.Substring($case.IndexOf('|')+1);$dot=$name.IndexOf('.');$class=if($dot -gt 0){$name.Substring(0,$dot)}else{$name}
        if($class -cin $segmentInfo.otherLower){throw "M7_GAME_SEGMENT_FOREIGN：$case"}
        if($class.StartsWith('m7',[StringComparison]::Ordinal) -and $class -cnotin $segmentInfo.ownLower){throw "M7_GAME_SEGMENT_CLASS_EXTRA：$case"}
        $present+=$class
    }
    foreach($class in $segmentInfo.expectedClasses){if($class -cnotin $present){throw "M7_GAME_SEGMENT_CLASS_MISSING：$class"}}
}
function SelfTest-M7([string]$Root){
    $controls=Join-Path $Root ('selftest-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $controls|Out-Null;$results=@()
    # Scale 段沒有 GameTest／pacing／JAR，反例改在 JUnit XML 與 summary 欄位集合上。
    # GameTest 段（使用者 2026-11-25「拆成兩段」）另有：錯段（換成另一個 GameTest 段）、混入另一段的類別、少一個本段類別、多一個不屬於任何段的 M7 類別、
    # summary 的段名；Main 以外的段另核不得有 JUnit 輸入與 summary 不得夾帶 JUnit 欄位。
    # stale-gametest（T5-5 Step 0，拆段審查 M7）：manifest 的 gametest 輸入標成不是本次寫出（fresh=false），必須 M7_NOT_CURRENT；各段共用 build/gametest-m7-results.xml。
    $segmentCases=@('wrong-game-segment','foreign-class','class-missing','class-extra','summary-segment','stale-gametest')
    $caseList=if($Segment -ceq 'Scale'){@('positive','junit-drop','junit-skip','wrong-segment','wrong-head','hash','summary-junit-cases','summary-scope','summary-extra-field')}
    elseif($Segment -ceq 'Main'){@('positive','drop','skip','wrong-scope','wrong-segment','wrong-head','hash','summary-game-cases','summary-junit-cases','summary-pacing','summary-artifact')+$segmentCases}
    else{@('positive','drop','skip','wrong-scope','wrong-segment','wrong-head','hash','summary-game-cases','summary-pacing')+$segmentCases+@('junit-present','summary-extra-field')}
    $otherSegment='';$foreign='';$missingClass='';$extraCase=''
    if($gameSegment){
        $otherSegment=[string]@($gameSegmentNames|Where-Object {$_ -cne $Segment})[0]
        $foreign=[string]@($platformGame|Where-Object {(M7-GameClass $_) -cin $segmentInfo.otherLower})[0]
        if(-not $otherSegment -or -not $foreign){throw 'M7_SELFTEST_FIXTURE：GameTest 段的反例需要另一段與它在本平台的案例'}
        $missingClass=[string]@($segmentInfo.expectedClasses)[0]
    }
    foreach($case in $caseList){
        $copy=Join-Path $controls $case;New-Item -ItemType Directory $copy|Out-Null;Copy-Item -LiteralPath (Join-Path $Root 'raw') -Destination $copy -Recurse;Copy-Item -LiteralPath (Join-Path $Root 'inputs.json') -Destination $copy;Copy-Item -LiteralPath (Join-Path $Root 'summary.json') -Destination $copy
        $manifest=Get-Content -LiteralPath (Join-Path $copy 'inputs.json') -Raw|ConvertFrom-Json -DateKind String
        if($case -cin @('drop','skip','wrong-scope')){$input=@($manifest.inputs|Where-Object {$_.kind -ceq 'gametest'})[0];$path=Join-Path $copy $input.relative;[xml]$xml=Get-Content -LiteralPath $path -Raw;$node=$xml.SelectSingleNode('//testcase');switch($case){'drop'{$null=$node.ParentNode.RemoveChild($node)};'skip'{$null=$node.AppendChild($xml.CreateElement('skipped'))};'wrong-scope'{$node.SetAttribute('name','m6universegametests.wrong_scope')}};$xml.Save($path);$input.sha256=(Get-FileHash $path).Hash}
        if($case -cin @('junit-drop','junit-skip')){$junitInput=@($manifest.inputs|Where-Object {$_.kind -ceq 'junit'})[0];$path=Join-Path $copy $junitInput.relative;[xml]$xml=Get-Content -LiteralPath $path -Raw;$node=$xml.SelectSingleNode('//testcase');if($case -ceq 'junit-drop'){$null=$node.ParentNode.RemoveChild($node)}else{$null=$node.AppendChild($xml.CreateElement('skipped'))};$xml.Save($path);$junitInput.sha256=(Get-FileHash $path).Hash}
        if($case -cin @('foreign-class','class-missing','class-extra')){
            $gameInput=@($manifest.inputs|Where-Object {$_.kind -ceq 'gametest'})[0];$path=Join-Path $copy $gameInput.relative;[xml]$xml=Get-Content -LiteralPath $path -Raw;$nodes=@($xml.SelectNodes('//testcase'))
            switch($case){
                'foreign-class'{$clone=$nodes[0].CloneNode($true);$clone.SetAttribute('classname',$foreign.Substring(0,$foreign.IndexOf('|')));$clone.SetAttribute('name',$foreign.Substring($foreign.IndexOf('|')+1));$null=$nodes[0].ParentNode.AppendChild($clone)}
                'class-extra'{$clone=$nodes[0].CloneNode($true);$clone.SetAttribute('name','m7selftestextragametests.extra_case');$null=$nodes[0].ParentNode.AppendChild($clone);$extraCase=$clone.GetAttribute('classname')+'|m7selftestextragametests.extra_case'}
                'class-missing'{foreach($node in $nodes){if($node.GetAttribute('name').StartsWith($missingClass+'.',[StringComparison]::Ordinal)){$null=$node.ParentNode.RemoveChild($node)}}}
            }
            $xml.Save($path);$gameInput.sha256=(Get-FileHash $path).Hash
        }
        if($case -ceq 'junit-present'){
            $fake=Join-Path $copy 'raw/junit/TEST-dev.quantumchamber.selftest.M7SelfTestTest.xml';New-Item -ItemType Directory -Path (Split-Path $fake) -Force|Out-Null
            Set-Content -LiteralPath $fake -Value '<testsuite name="dev.quantumchamber.selftest.M7SelfTestTest"><testcase classname="dev.quantumchamber.selftest.M7SelfTestTest" name="proof()"/></testsuite>' -Encoding utf8
            $manifest.inputs=@($manifest.inputs)+@([pscustomobject]@{kind='junit';relative='raw/junit/TEST-dev.quantumchamber.selftest.M7SelfTestTest.xml';sha256=(Get-FileHash $fake).Hash;original='selftest';originalWriteUtc=[DateTime]::UtcNow.ToString('o');fresh=$true})
        }
        if($case -ceq 'stale-gametest'){$staleInput=@($manifest.inputs|Where-Object {$_.kind -ceq 'gametest'})[0];$staleInput.fresh=$false}
        if($case -ceq 'wrong-segment'){$manifest.segment=$(if($Segment -ceq 'Scale'){'Main'}else{'Scale'})}
        if($case -ceq 'wrong-game-segment'){$manifest.segment=$otherSegment}
        if($case -ceq 'wrong-head'){$manifest.head='0'*40};if($case -ceq 'hash'){$manifest.inputs[0].sha256='0'*64}
        if($case.StartsWith('summary-',[StringComparison]::Ordinal)){
            $changed=Get-Content -LiteralPath (Join-Path $copy 'summary.json') -Raw|ConvertFrom-Json
            switch($case){'summary-game-cases'{$changed.gameCases=@()};'summary-junit-cases'{$changed.junitCases=@()};'summary-pacing'{$changed.pacing.waited=[long]$changed.pacing.waited+1};'summary-artifact'{$changed.artifacts[0].sha256='0'*64};'summary-scope'{$changed.scope='m7_home'};'summary-segment'{$changed.segment=$otherSegment};'summary-extra-field'{$changed|Add-Member -NotePropertyName $(if($Segment -ceq 'Scale'){'gameCases'}else{'junitCases'}) -NotePropertyValue @()}}
            Save-Json (Join-Path $copy 'summary.json') $changed
        }
        # 正例維持原始inputs/XML bytes；只有負例重寫自己的metadata副本。
        if($case -cin @('drop','skip','wrong-scope','junit-drop','junit-skip','foreign-class','class-missing','class-extra','junit-present','stale-gametest','wrong-segment','wrong-game-segment','wrong-head','hash')){Save-Json (Join-Path $copy 'inputs.json') $manifest}
        $expected=@{positive='';drop='M7_GAME_SET';skip='M7_GAME_NOT_PASS';'wrong-scope'='M7_GAME_SET';'junit-drop'='M7_JUNIT_SET';'junit-skip'='M7_JUNIT_NOT_PASS';'wrong-segment'='M7_INPUT_IDENTITY';'wrong-game-segment'='M7_INPUT_IDENTITY';'wrong-head'='M7_INPUT_IDENTITY';hash='M7_INPUT_HASH';'foreign-class'="M7_GAME_SEGMENT_FOREIGN：$foreign";'class-missing'="M7_GAME_SEGMENT_CLASS_MISSING：$missingClass";'class-extra'="M7_GAME_SEGMENT_CLASS_EXTRA：$extraCase";'junit-present'='M7_NOT_CURRENT';'stale-gametest'='M7_NOT_CURRENT';'summary-game-cases'='M7_SAVED_GAME_CASES';'summary-junit-cases'='M7_SAVED_JUNIT_CASES';'summary-pacing'='M7_SAVED_PACING';'summary-artifact'='M7_SAVED_ARTIFACT';'summary-scope'='M7_SAVED_SUMMARY';'summary-segment'='M7_SAVED_SUMMARY';'summary-extra-field'='M7_SAVED_SUMMARY'}[$case];$reason='';try{$recomputed=Verify-M7 $copy;$candidate=Get-Content -LiteralPath (Join-Path $copy 'summary.json') -Raw|ConvertFrom-Json;Assert-M7SavedSummary $candidate $recomputed}catch{$reason=$_.Exception.Message}
        if($reason -cne $expected){throw "M7_SELFTEST $case expected=$expected got=$reason"};$results+=@{case=$case;expected=$expected;status=$(if($expected){'KILLED'}else{'PASS'})}
    }
    $reason='';try{Assert-M7LiveHead ('0'*40)}catch{$reason=$_.Exception.Message};if($reason -cne 'M7_HEAD_CHANGED'){throw 'M7_SELFTEST live-head-mismatch'};$results+=@{case='live-head-mismatch';expected='M7_HEAD_CHANGED';status='KILLED'}
    $junitControls=@(
        @{case='junit-declared-public';expected='';body='@Test public final void proof() {} // @ParameterizedTest'},
        @{case='junit-unlisted-class';expected='M7_JUNIT_CLASS_UNLISTED：dev.quantumchamber.control.M7ControlTest';body='@Test public void proof() {}'},
        @{case='junit-unsupported-annotation';expected='M7_JUNIT_DECLARATION_FORM：dev.quantumchamber.control.M7ControlTest @ParameterizedTest';body='@Test void proof() {} @ParameterizedTest void hidden(int value) {}'}
    )
    foreach($control in $junitControls){
        $source=Join-Path $controls $control.case
        New-Item -ItemType Directory $source|Out-Null
        Set-Content -LiteralPath (Join-Path $source 'M7ControlTest.java') -Value "package dev.quantumchamber.control; class M7ControlTest { $($control.body)`n}" -Encoding utf8
        $expected=@('dev.quantumchamber.control.M7ControlTest.proof')
        if($control.case -ceq 'junit-unlisted-class'){$expected=@('dev.quantumchamber.control.M7OtherTest.proof')}
        $reason='';try{Assert-M7JUnitDeclarations $source $expected}catch{$reason=$_.Exception.Message}
        if($reason -cne $control.expected){throw "M7_SELFTEST $($control.case) expected=$($control.expected) got=$reason"}
        $results+=@{case=$control.case;expected=$control.expected;status=$(if($control.expected){'KILLED'}else{'PASS'})}
    }
    # 分段清單的負控（使用者 2026-11-25「拆成兩段」）：只改 gametestSegments 的記憶體複本或本平台集合，原 inventory 與證據不動。
    if($gameSegment){
        $ownClasses=@($segmentInfo.classes);$otherFirst=[string]@($gameSegmentTable.PSObject.Properties[$otherSegment].Value.classes)[0]
        $segmentControls=@(
            @{case='segment-positive';expected='';name=$Segment;edit=$null;game=$platformGame},
            @{case='segment-unknown';expected='M7_SEGMENT_UNKNOWN：Nope';name='Nope';edit=$null;game=$platformGame},
            @{case='segment-unknown-case';expected="M7_SEGMENT_UNKNOWN：$($Segment.ToLowerInvariant())";name=$Segment.ToLowerInvariant();edit=$null;game=$platformGame},
            @{case='segment-overlap';expected="M7_GAME_SEGMENT_OVERLAP：$otherFirst";name=$Segment;edit={param($t)$t.PSObject.Properties[$Segment].Value.classes=@($ownClasses)+@($otherFirst)};game=$platformGame},
            @{case='segment-class-missing';expected="M7_GAME_SEGMENT_CLASS_UNLISTED：$($ownClasses[-1])";name=$Segment;edit={param($t)$t.PSObject.Properties[$Segment].Value.classes=@($ownClasses|Select-Object -SkipLast 1)};game=$platformGame},
            @{case='segment-class-extra';expected='M7_GAME_SEGMENT_CLASS_UNDECLARED：M7SelfTestPhantomGameTests';name=$Segment;edit={param($t)$t.PSObject.Properties[$Segment].Value.classes=@($ownClasses)+@('M7SelfTestPhantomGameTests')};game=$platformGame},
            @{case='segment-form';expected="M7_GAME_SEGMENT_FORM：$Segment";name=$Segment;edit={param($t)$t.PSObject.Properties[$Segment].Value.classes=@()};game=$platformGame},
            @{case='segment-empty';expected="M7_GAME_SEGMENT_EMPTY：$Segment";name=$Segment;edit=$null;game=@($platformGame|Where-Object {(M7-GameClass $_) -cnotin $segmentInfo.ownLower})}
        )
        foreach($control in $segmentControls){
            $table=$gameSegmentTable|ConvertTo-Json -Depth 10|ConvertFrom-Json
            if($control.edit){& $control.edit $table}
            $reason='';$again=$null;try{$again=Get-M7GameSegment $table $control.name $declaredGame $control.game}catch{$reason=$_.Exception.Message}
            if($reason -cne $control.expected){throw "M7_SELFTEST $($control.case) expected=$($control.expected) got=$reason"}
            # 正例：未改的清單複本必須得出與本次相同的 exact 集合。
            if(-not $control.expected -and ((@($again.expected)|Sort-Object) -join ',') -cne ((@($expectedGame)|Sort-Object) -join ',')){throw 'M7_SELFTEST segment-positive 集合不符'}
            $results+=@{case=$control.case;expected=$control.expected;status=$(if($control.expected){'KILLED'}else{'PASS'})}
        }
    }
    Save-Json (Join-Path $controls 'result.json') ([ordered]@{fixture=$true;head=$ExpectedHead;platform=$Platform;segment=$Segment;cases=$results})
    Verify-M7 $Root|Out-Null
}
$launched=-not $VerifyOnly
if($launched){
    Assert-M7JUnitDeclarations $junitSource $allJUnit
    if(-not(Test-Path $OutputRoot)){New-Item -ItemType Directory $OutputRoot|Out-Null};Assert-M6OutputFresh $OutputRoot
    if($head -cne $ExpectedHead){throw 'M7_HEAD'}
    if($Segment -ceq 'Scale'){if($GameNonce){throw 'M7_NONCE：Scale 段沒有 GameTest，不接受 -GameNonce'};$runtime=''}
    else{if(-not $GameNonce){$GameNonce=[guid]::NewGuid().ToString('N')};if($GameNonce -cnotmatch '^[0-9a-f]{32}$'){throw 'M7_NONCE'};$runtime=Assert-M6FreshWorld (Join-Path $repo "run/gametest-m7-$GameNonce")}
    if(-not $Gradle){$Gradle=if($IsWindows){Join-Path $repo 'gradlew.bat'}else{Join-Path $repo 'gradlew'}}
    # JUnit 依該段 exact 集合的 class 篩選（m7-inventory.json）；Windows 經 gates.ps1 的 Invoke-Gradle（總時限 600 秒、idle 90 秒，每段各自計算）。
    # GameTest 段以 -Pm7GameSegment 傳段名（JVM 內依同一份清單選類別）；Main 另跑 JUnit 與兩個 JAR，其他段只跑 runGameTestM7。
    $testFilter=@($testClasses|ForEach-Object {'--tests';$_})
    $start=[DateTime]::UtcNow;$failure=$null
    try{
        if($Segment -ceq 'Scale'){
            if($IsWindows){& (Join-Path $PSScriptRoot 'gates.ps1') -Mode M7ScaleRuntime -Label m7-scale-runtime -EvidenceRoot $OutputRoot -Gradle $Gradle -M7Tests $testClasses}
            else{& $Gradle test @testFilter --rerun-tasks --console=plain *> (Join-Path $OutputRoot 'gradle.log');if($LASTEXITCODE -ne 0){throw "M7_SCALE_RUNTIME_EXIT $LASTEXITCODE"}}
        }
        elseif($IsWindows){& (Join-Path $PSScriptRoot 'gates.ps1') -Mode M7Runtime -Label m7-runtime -EvidenceRoot $OutputRoot -Gradle $Gradle -M7Nonce $GameNonce -M7Segment $Segment -M7Tests $testClasses}
        elseif($Segment -ceq 'Main'){& $Gradle test @testFilter runGameTestM7 remapJar remapSourcesJar "-Pm7GameNonce=$GameNonce" "-Pm7Head=$head" "-Pm7GameSegment=$Segment" --rerun-tasks --console=plain *> (Join-Path $OutputRoot 'gradle.log');if($LASTEXITCODE -ne 0){throw "M7_RUNTIME_EXIT $LASTEXITCODE"}}
        else{& $Gradle runGameTestM7 "-Pm7GameNonce=$GameNonce" "-Pm7Head=$head" "-Pm7GameSegment=$Segment" --rerun-tasks --console=plain *> (Join-Path $OutputRoot 'gradle.log');if($LASTEXITCODE -ne 0){throw "M7_RUNTIME_EXIT $LASTEXITCODE"}}
    }catch{$failure=$_}
    finally{Save-M7Inputs $start $runtime}
    if($failure){Save-Json (Join-Path $OutputRoot 'failure.json') @{head=$ExpectedHead;platform=$Platform;segment=$Segment;status='FAIL';message=$failure.Exception.Message};throw $failure}
    $proof=Verify-M7 $OutputRoot;Assert-M7LiveHead $ExpectedHead;Save-Json (Join-Path $OutputRoot 'summary.json') $proof
}else{
    $saved=Get-Content -LiteralPath (Join-Path $OutputRoot 'summary.json') -Raw|ConvertFrom-Json
    $proof=Verify-M7 $OutputRoot
    Assert-M7SavedSummary $saved $proof
}
if($SelfTest){SelfTest-M7 $OutputRoot}
$finalProof=Verify-M7 $OutputRoot
if($launched){Assert-M7LiveHead $ExpectedHead}
if($Segment -ceq 'Scale'){"M7_SCALE_VERIFIED PASS platform=$Platform junit=$($expectedJUnit.Count) launched=$launched HEAD=$ExpectedHead root=$OutputRoot"}
else{"M7_VERIFIED PASS platform=$Platform segment=$Segment junit=$($expectedJUnit.Count) gametest=$($expectedGame.Count) launched=$launched HEAD=$ExpectedHead root=$OutputRoot"}
if($PassThru){[pscustomobject]$finalProof}
