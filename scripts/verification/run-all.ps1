# run-all.ps1：在目前 HEAD 依序 fresh 執行全部 M1–M4 automated gates（Full、Legacy、Main＋main-only oracle、M3、M4 recovery、
# CI 綠燈規則 Windows／Ubuntu 兩個 step），最後以唯讀 summary 彙整並核對數字；任何步驟失敗即停止，保留所有 root。
# 本次 run root＝<EvidenceRoot>/run-all-<GUID>，console.log、各 gate root、recovery 彙整、CI root 與 summary.json 都在其中。
# 結束時印 END ALL exit=0 HEAD=<sha>。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/ifix4-final-green.ps1（SHA-256 dcdc2bbbd7c7461cea3712c0f4bc3e67c91d033437c6ee8f2d82464ffdd2b53d）；
# summary 改寫自同目錄 ifix3-build-summary.ps1（SHA-256 63de6da09187a39a4f4b2f472daa2143bca78ff36ae98d6b0daa5b659fab7bc5）§1–§8，
# 移除該輪固定數量、RED/GREEN、pre-commit CI、舊 workflow 對照與 evidence manifest 等一次性核對；預期數字見 README。
# 參數：
#   -EvidenceRoot  run root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
#   -Gradle        傳給 gates.ps1／m4-recovery-probe.ps1（空字串＝各自預設）
param([string]$EvidenceRoot='',[string]$Gradle='')
$ErrorActionPreference='Stop'
# 不設定 onlyBatches（先移除 JAVA_TOOL_OPTIONS），default／legacy 皆為完整 suite。
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
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
$runRoot=Join-Path $EvidenceRoot ('run-all-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
$log=Join-Path $runRoot 'console.log'
if(Test-Path -LiteralPath $log){throw "console log 已存在，禁止覆寫：$log"}
function Say([string]$text){ $text | Add-Content -LiteralPath $log -Encoding utf8; Write-Output $text }
$startHead=(git -C $repo rev-parse HEAD).Trim()
Say "START $([DateTimeOffset]::Now.ToString('o')) HEAD=$startHead"
Say "STATUS $((@(git -C $repo status --porcelain --untracked-files=no)) -join ' | ')"
Say "RUN_ROOT=$runRoot"
foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -File | Sort-Object Name){ Say "HARNESS $($file.Name) sha256=$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())" }
function Step([string]$name,[scriptblock]$body) {
    Say "BEGIN $name $([DateTimeOffset]::Now.ToString('o'))"
    try { & $body 2>&1 | ForEach-Object { Say "$_" }; Say "END $name exit=0 $([DateTimeOffset]::Now.ToString('o'))" }
    catch { Say "END $name FAIL $($_.Exception.Message)"; throw }
}
# root 名稱＝前綴＋32 位 GUID（＋副檔名）；本次 run root 內必須恰一個。
function One([string]$prefix,[string]$suffix=''){ $d=@(Get-ChildItem -LiteralPath $runRoot | Where-Object {$_.Name -match ('^'+[regex]::Escape($prefix)+'[0-9a-f]{32}'+[regex]::Escape($suffix)+'$')}); if($d.Count -ne 1){throw "root 必須恰一個：$prefix（$($d.Count)）"}; return $d[0].FullName }
function Read-Json([string]$path){return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -DateKind String}
function Hash([string]$path){return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Canon($value) {
    if($null -eq $value){return 'null'}
    if($value -is [System.Management.Automation.PSCustomObject]) {
        $names=@($value.PSObject.Properties.Name | Sort-Object)
        return '{'+(($names | ForEach-Object {'"'+$_+'":'+(Canon $value.$_)}) -join ',')+'}'
    }
    if($value -is [System.Collections.IList]){return '['+((@($value) | ForEach-Object {Canon $_}) -join ',')+']'}
    return ($value | ConvertTo-Json -Compress)
}
function Xml-Cases([string]$path){[xml]$x=Get-Content -LiteralPath $path -Raw;return @($x.SelectNodes('//testcase'))}
function Server-Pid([string]$gate) {
    $server=@(Read-Json (Join-Path $gate 'execution/processes.json') | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
    if($server.Count -ne 1){throw "必須恰有一個 server JVM：$gate"}
    return $server[0]
}
function Receipts([string]$gate,$server,[string[]]$expected) {
    $dirs=@(Get-ChildItem -LiteralPath (Join-Path $gate 'execution') -Directory -Filter "m4-per-test-$($server.pid)-*")
    if($dirs.Count -ne 1){throw "per-test receipt 目錄必須恰一個：$gate"}
    $nonce=$dirs[0].Name.Substring(("m4-per-test-$($server.pid)-").Length)
    $finals=@(Get-ChildItem -LiteralPath $dirs[0].FullName -File -Filter '*.json' | Where-Object {$_.Name -notlike '*.before.json'})
    $befores=@(Get-ChildItem -LiteralPath $dirs[0].FullName -File -Filter '*.before.json')
    $names=@($finals | ForEach-Object {$_.BaseName} | Sort-Object)
    if(($names -join ',') -cne (($expected | Sort-Object) -join ',') -or $befores.Count -ne $expected.Count){throw "receipt 名稱集合不符：$gate"}
    $stdout=Get-Content -LiteralPath (Join-Path $gate 'execution/stdout.log') -Raw
    foreach($file in $finals | Sort-Object Name) {
        $r=Read-Json $file.FullName
        $b=Read-Json (Join-Path $dirs[0].FullName ($file.BaseName+'.before.json'))
        if($r.testName -cne $file.BaseName -or $r.pid -ne $server.pid -or $r.startupNonce -ne $nonce -or $r.runtimeScope -ne 'testmod-present'){throw "receipt 身分不符：$($file.FullName)"}
        if((Canon $b.before) -cne (Canon $r.before) -or $b.status -ne 'BEFORE'){throw "before receipt 不一致：$($file.FullName)"}
        if($r.status -ne 'PASS' -or $r.snapshotExactUnchanged -ne $true -or $r.runtimeHandlesExactUnchanged -ne $true -or (Canon $r.before) -cne (Canon $r.after)){throw "receipt 非 exact PASS：$($file.FullName)"}
        if([regex]::Matches($stdout,"M4_PER_TEST_BEGIN name=$($file.BaseName) receipt=").Count -ne 1 -or [regex]::Matches($stdout,"M4_PER_TEST_END name=$($file.BaseName) status=PASS receipt=").Count -ne 1){throw "per-test log 不唯一：$($file.BaseName)"}
    }
    return $finals.Count
}
$gates=Join-Path $PSScriptRoot 'gates.ps1'
Step 'full' { & $gates -Mode Full -Label full -EvidenceRoot $runRoot -Gradle $Gradle }
Step 'legacy' { & $gates -Mode Legacy -Label legacy -EvidenceRoot $runRoot -Gradle $Gradle }
Step 'main' { & $gates -Mode Main -Label main-only -EvidenceRoot $runRoot -Gradle $Gradle }
Step 'main-oracle' {
    $main=One 'gate-main-only-'
    Write-Output "MAIN_GATE=$main"
    & (Join-Path $PSScriptRoot 'main-oracle.ps1') -Gate $main | Out-Null
    Write-Output "MAIN_ORACLE=$(Join-Path $main 'main-only-oracle.json')"
}
Step 'm3' { & $gates -Mode M3 -Label m3 -EvidenceRoot $runRoot -Gradle $Gradle }
Step 'recovery' { & (Join-Path $PSScriptRoot 'm4-recovery-probe.ps1') -EvidenceRoot $runRoot -Gradle $Gradle }
Step 'ci-rule' {
    $full=One 'gate-full-'
    $xml=Join-Path $full 'result-build/gametest-results.xml'; $junit=Join-Path $full 'result-build/test-results/test'
    $workflow=Join-Path $repo '.github/workflows/build.yml'; $src=Join-Path $repo 'src/testmod/java'
    $check=Join-Path $PSScriptRoot 'ci-rule-check.ps1'
    & $check -Label win-green -Workflow $workflow -Step '確認 Windows 原生案例確實執行' -GameTestXml $xml -JUnitDir $junit -SourceRoot $src -EvidenceRoot $runRoot
    & $check -Label ubuntu-green -Workflow $workflow -Step '確認非 Windows 跨平台案例確實執行' -GameTestXml $xml -JUnitDir $junit -SourceRoot $src -EvidenceRoot $runRoot
    # ci-rule-check.ps1 不因 FAIL 而 throw；綠燈核對在此判定。
    foreach($label in 'win-green','ubuntu-green'){ $r=Read-Json (Join-Path (One "ci-$label-") 'result.json'); if($r.verdict -ne 'PASS'){throw "CI 規則 $label 未通過：exit=$($r.exitCode)"} }
}
Step 'summary' {
    # 唯讀：只讀本次 run root 的原始 XML／receipt／JAR／oracle／CI 結果，任何不符直接 throw；不改寫任何 gate root。
    $head=(git -C $repo rev-parse HEAD).Trim()
    if($head -cne $startHead){throw "HEAD 在執行期間改變：$startHead -> $head"}
    $full=One 'gate-full-'; $legacy=One 'gate-legacy-'; $main=One 'gate-main-only-'; $m3=One 'gate-m3-'; $recoveryFile=One 'recovery-run-' '.json'
    foreach($gate in @([pscustomobject]@{root=$full;mode='Full'},[pscustomobject]@{root=$legacy;mode='Legacy'},[pscustomobject]@{root=$main;mode='Main'},[pscustomobject]@{root=$m3;mode='M3'})) {
        $g=Read-Json (Join-Path $gate.root 'gate.json'); if($g.status -ne 'PASS' -or $g.mode -ne $gate.mode){throw "gate 狀態不符：$($gate.root)"}
    }
    foreach($swap in @([pscustomobject]@{root=$full;tag='world'},[pscustomobject]@{root=$legacy;tag='world'},[pscustomobject]@{root=$main;tag='server'})) {
        $r=Read-Json (Join-Path $swap.root "$($swap.tag)-restore.json"); if(-not $r.exactRestored -or $r.deleted){throw "restore 不符：$($swap.root)"}
    }
    $fullExec=Read-Json (Join-Path $full 'execution/execution.json'); $legacyExec=Read-Json (Join-Path $legacy 'execution/execution.json')
    if($fullExec.exitCode -ne 0 -or $legacyExec.exitCode -ne 0){throw 'gate exit 非0'}
    if($fullExec.command -notmatch '"clean" "test" "runGameTest" "build" "--rerun-tasks"' -or $legacyExec.command -notmatch '"runGameTestLegacy" "--rerun-tasks"'){throw 'gate 指令不符'}
    # 1. GameTest XML：全部 case 無 failure／error／skipped，名稱唯一。
    $defaultXml=Join-Path $full 'result-build/gametest-results.xml'; $legacyXml=Join-Path $legacy 'result-build/gametest-legacy-results.xml'
    $defaultCases=@(Xml-Cases $defaultXml); $legacyCases=@(Xml-Cases $legacyXml)
    $bad=@($defaultCases+$legacyCases | Where-Object {$_.SelectSingleNode('failure') -or $_.SelectSingleNode('error') -or $_.SelectSingleNode('skipped')})
    if($defaultCases.Count -eq 0 -or $legacyCases.Count -eq 0 -or $bad.Count -ne 0){throw "GameTest XML 不符：default=$($defaultCases.Count) legacy=$($legacyCases.Count) bad=$($bad.Count)"}
    if(@($defaultCases | ForEach-Object name | Sort-Object -Unique).Count -ne $defaultCases.Count){throw 'default XML case 名稱不唯一'}
    $windowsCases=@($defaultCases | Where-Object {$_.name -like '*_windows'}).Count
    # 2. per-test Universe probe receipts：每個呼叫 M4PerTestUniverseProbe.begin 的 GameTest 都有 exact PASS receipt。
    $pattern='(?s)@GameTest\((?<a>[^)]*)\)\s+public void (?<name>\w+)\(TestContext context\)\s*\{\s*(?<gate>if\(!Platform\.isWindows\(\)\)\s*\{[^{}]*\}\s*)?M4PerTestUniverseProbe\.begin\(context,"(?<begin>\w+)"\);'
    $tests=@(); $beginCalls=0
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo 'src/testmod/java') -Recurse -File -Filter '*.java') {
        $source=Get-Content -LiteralPath $file.FullName -Raw
        $beginCalls+=([regex]::Matches($source,'M4PerTestUniverseProbe\.begin\(')).Count
        foreach($m in [regex]::Matches($source,$pattern)) {
            if($m.Groups['name'].Value -cne $m.Groups['begin'].Value){throw "begin 名稱不符：$($m.Groups['name'].Value)"}
            if($m.Groups['gate'].Success -and $m.Groups['gate'].Value -notmatch 'context\.complete\(\)|platformSkip\(context'){throw "平台 gate 不是單純略過：$($m.Groups['name'].Value)"}
            $tests+=[pscustomobject]@{class=$file.BaseName;name=$m.Groups['name'].Value;legacy=($m.Groups['a'].Value -match 'batchId="m4_legacy_runtime"')}
        }
    }
    if($tests.Count -eq 0 -or $tests.Count -ne $beginCalls){throw "per-test begin 呼叫 $beginCalls 個，可解析的宣告 $($tests.Count) 個"}
    $defaultNames=@($tests | Where-Object {-not $_.legacy} | ForEach-Object name | Sort-Object)
    $legacyNames=@($tests | Where-Object {$_.legacy} | ForEach-Object name | Sort-Object)
    foreach($t in @($tests | Where-Object {-not $_.legacy})){if(@($defaultCases | Where-Object {$_.name -eq ($t.class.ToLowerInvariant()+'.'+$t.name)}).Count -ne 1){throw "default XML 缺 $($t.name)"}}
    foreach($t in @($tests | Where-Object {$_.legacy})){if(@($legacyCases | Where-Object {$_.name -eq ($t.class.ToLowerInvariant()+'.'+$t.name)}).Count -ne 1){throw "legacy XML 缺 $($t.name)"}}
    $fullReceipts=Receipts $full (Server-Pid $full) $defaultNames
    $legacyReceipts=Receipts $legacy (Server-Pid $legacy) $legacyNames
    # 3. JUnit：0 failure／error；skip 只允許 NonWindowsPlayerCheckpointStoreTest；Windows checkpoint 三個 class 必須實際執行。
    $junit=@(); $skips=@(); $failures=0; $errors=0
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $full 'result-build/test-results/test') -Filter 'TEST-*.xml') {
        [xml]$x=Get-Content -LiteralPath $file.FullName -Raw
        $failures+=[int]$x.testsuite.failures; $errors+=[int]$x.testsuite.errors
        $junit+=[pscustomobject]@{class=$x.testsuite.name;tests=[int]$x.testsuite.tests;skipped=[int]$x.testsuite.skipped}
        foreach($case in $x.SelectNodes('//testcase')){if($case.SelectSingleNode('skipped')){$skips+=[pscustomobject]@{class=$case.classname;name=$case.name}}}
    }
    $junitTotal=($junit | Measure-Object tests -Sum).Sum; $junitSkipped=($junit | Measure-Object skipped -Sum).Sum
    if($junit.Count -eq 0 -or $failures -ne 0 -or $errors -ne 0){throw "JUnit failures=$failures errors=$errors"}
    if($skips.Count -ne $junitSkipped -or @($skips | Where-Object {$_.class -ne 'dev.quantumchamber.persistence.NonWindowsPlayerCheckpointStoreTest'}).Count -ne 0){throw 'JUnit skip 只允許 NonWindowsPlayerCheckpointStoreTest'}
    $windowsClasses=@('dev.quantumchamber.persistence.WindowsPlayerCheckpointVerifierTest','dev.quantumchamber.persistence.PlayerCheckpointStoreTest','dev.quantumchamber.persistence.PlayerRecoveryCheckpointTest')
    $windows=@($junit | Where-Object {$_.class -in $windowsClasses})
    $windowsTests=($windows | Measure-Object tests -Sum).Sum
    if($windows.Count -ne 3 -or ($windows | Measure-Object skipped -Sum).Sum -ne 0){throw 'Windows checkpoint 三個 test class 必須實際執行'}
    # 4. JAR：testmod／gametest／probe 命中為0（production compat/ModPresenceProbe 為已知合法）。
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $testPaths=@(Get-ChildItem -LiteralPath (Join-Path $repo 'src/testmod/java') -Recurse -File -Filter '*.java' | ForEach-Object {[IO.Path]::GetRelativePath((Join-Path $repo 'src/testmod/java'),$_.FullName).Replace('\','/').Replace('.java','')})
    $allowedProbe=@('dev/quantumchamber/compat/ModPresenceProbe.class','dev/quantumchamber/compat/ModPresenceProbe.java')
    $jars=@()
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $full 'result-build/libs') -File -Filter '*.jar') {
        $zip=[IO.Compression.ZipFile]::OpenRead($file.FullName)
        try {
            $entries=@($zip.Entries.FullName | Sort-Object); $hits=@(); $probeHits=@()
            foreach($entry in $entries){
                $entryClass=$entry -replace '\.(class|java)$',''
                if($entry -match '(?i)testmod|gametest|quantumchamber-test\.mixins|m1_empty\.nbt' -or @($testPaths | Where-Object {$entryClass -eq $_ -or $entryClass.StartsWith($_+'$')}).Count){$hits+=$entry}
                if($entry -match '(?i)probe' -and $entry -notin $allowedProbe){$probeHits+=$entry}
            }
            if($hits.Count -or $probeHits.Count){throw "JAR testmod／gametest／probe entry 污染：$($file.Name) $(($hits+$probeHits) -join ',')"}
            $jars+=[pscustomobject]@{name=$file.Name;sources=($file.Name -like '*-sources.jar');entries=$entries.Count;classes=@($entries | Where-Object {$_ -like '*.class'}).Count;testmodHits=0;sha256=Hash $file.FullName}
        } finally {$zip.Dispose()}
    }
    if($jars.Count -ne 2 -or @($jars | Where-Object {$_.sources}).Count -ne 1){throw '必須恰有 release 與 sources 兩個 JAR'}
    $release=@($jars | Where-Object {-not $_.sources})[0]; $sources=@($jars | Where-Object {$_.sources})[0]
    # 5. main-only 三層 oracle＋world key oracle。
    $oracle=Read-Json (Join-Path $main 'main-only-oracle.json')
    if($oracle.status -ne 'PASS' -or $oracle.layer1TestmodLoaded -or @($oracle.layer2RuntimePathHits).Count -ne 0 -or @($oracle.layer3ClassLoadHits).Count -ne 0 -or -not $oracle.worldKeysExact -or -not $oracle.exactRestore){throw 'main-only oracle 不是完整 PASS'}
    # 6. M3 lifecycle 與 transfer receipts（evidenceOwner＝testmod 固定的 M4 plan 目錄）。
    $probeOwner=[IO.Path]::GetFullPath((Join-Path $repo '.superpowers/sdd/2026-09-21-m4-candidate-doors'))
    $m3Names=@('universe-create-save','universe-reload-read','universe-unload-replace','universe-final-verify','transfer-setup-catalog','transfer-success-roundtrip','transfer-target-not-full','transfer-post-move-authority-loss','transfer-stale-service-receipt')
    foreach($name in $m3Names){ $r=Read-Json (Join-Path $m3 "$name.json"); if($r.status -ne 'PASS' -or $r.evidenceOwner -ne $probeOwner){throw "M3 receipt 不符：$name"} }
    # 7. M4 recovery：全部 phases PASS、各自獨立 JVM、receipt hash 對上 launcher。
    $recovery=Read-Json $recoveryFile
    if($recovery.status -ne 'PASS' -or @($recovery.only).Count -ne 0){throw 'recovery summary 不是完整 PASS'}
    $phases=@($recovery.phases)
    $expectedPhases=@('chain:select','chain:recover','chain:verify','chain:dormant-reentry','native-save:select','native-save:native-write-fail','native-save:recover-after-write-fail','native-save:verify',
        'low:low','buff:buff','disconnect:disconnect','dirty-candidate:dirty-candidate','dirty-selection:dirty-selection','entropy-missing:entropy-missing','entropy-corrupt:entropy-corrupt','discovery-corrupt:discovery-corrupt','journal-corrupt:journal-corrupt',
        'selection-flush-fault:selection-flush-fault','selection-flush-fault:selection-fault-restart','readback-fault:readback-fault','readback-fault:selection-fault-restart',
        'readback-crash:readback-crash','readback-crash:recover','readback-crash:verify')
    $observed=@($phases | ForEach-Object {($_.nonce -replace '^[0-9a-f]{32}-','')+':'+$_.phase})
    if(($observed -join ',') -cne ($expectedPhases -join ',')){throw "recovery phases 不符：$($observed -join ',')"}
    if(@($phases | ForEach-Object {"$($_.javaPid)@$($_.javaStartTimeUtc)"} | Sort-Object -Unique).Count -ne $phases.Count){throw 'recovery phases 必須各自使用不同 JVM (PID, StartTime)'}
    if(@($phases | ForEach-Object startupNonce | Sort-Object -Unique).Count -ne $phases.Count){throw 'recovery phases 必須各自使用不同 startupNonce'}
    foreach($phase in $phases) {
        $receipt=Read-Json $phase.receipt
        if($receipt.status -ne 'PASS' -or (Hash $phase.receipt) -ne $phase.receiptSha256.ToLowerInvariant() -or $receipt.pid -ne $phase.javaPid -or $receipt.startupNonce -ne $phase.startupNonce -or $receipt.runtimeScope -ne 'testmod-present'){throw "recovery receipt 不符：$($phase.receipt)"}
    }
    # 8. CI 綠燈規則：以 HEAD workflow 原文逐字執行，對本次 full gate XML。
    $workflowHash=(Get-FileHash -LiteralPath (Join-Path $repo '.github/workflows/build.yml') -Algorithm SHA256).Hash
    $ci=[ordered]@{}
    foreach($label in 'win-green','ubuntu-green'){
        $r=Read-Json (Join-Path (One "ci-$label-") 'result.json')
        if($r.verdict -ne 'PASS' -or $r.workflowSha256 -ne $workflowHash -or $r.gameTestXmlSha256.ToLowerInvariant() -ne (Hash $defaultXml)){throw "CI 規則結果不符：$label verdict=$($r.verdict)"}
        $ci[$label]=$r.verdict
    }
    $summary=[ordered]@{status='PASS';head=$head;runRoot=$runRoot
        gameTest=[ordered]@{default=$defaultCases.Count;defaultPassed=$defaultCases.Count-$bad.Count;windows=$windowsCases;legacy=$legacyCases.Count;defaultXmlSha256=Hash $defaultXml;legacyXmlSha256=Hash $legacyXml;perTestReceipts=[ordered]@{default=$fullReceipts;legacy=$legacyReceipts}}
        junit=[ordered]@{total=$junitTotal;failures=$failures;errors=$errors;skipped=$junitSkipped;skippedCases=$skips;windowsCheckpointTests=$windowsTests}
        jars=$jars;mainOnly=[ordered]@{status=$oracle.status;fabricLoadedModIds=@($oracle.fabricLoadedModIds);staleDatapackWarnings=$oracle.warningCount}
        m3=[ordered]@{lifecycle=4;transfer=5;evidenceOwner=$probeOwner};recovery=[ordered]@{summary=$recoveryFile;runId=$recovery.runId;phaseCount=$phases.Count};ci=$ci
        roots=[ordered]@{full=$full;legacy=$legacy;main=$main;m3=$m3}}
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8
    Write-Output "SUMMARY default=$($defaultCases.Count-$bad.Count)/$($defaultCases.Count) windows=$windowsCases legacy=$($legacyCases.Count)/$($legacyCases.Count) perTest=$fullReceipts+$legacyReceipts junit=$junitTotal failures=$failures errors=$errors skipped=$junitSkipped windowsCheckpoint=$windowsTests release=$($release.entries)entries/$($release.classes)classes sources=$($sources.entries) testmodHits=0 mainOnly=$($oracle.status) m3=4+5 recovery=$($phases.Count) ci=win:$($ci['win-green']),ubuntu:$($ci['ubuntu-green'])"
}
Say "END ALL exit=0 HEAD=$((git -C $repo rev-parse HEAD).Trim())"
