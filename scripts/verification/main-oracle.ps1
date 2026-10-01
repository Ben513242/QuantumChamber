# main-oracle.ps1：main-only 三層 oracle（Fabric mod list、runtime classpath／argfiles／DLI、exact server PID class-load）＋world key／正常停止／exact restore。
# 只讀 gates.ps1 -Mode Main 的 gate root，結果寫入 <Gate>/main-only-oracle.json；任何一層命中 testmod 即 FAIL（throw）。
# 精確的 `Missing data pack quantumchamber-testmod` 只記為 stale save metadata warning，不算命中。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/task9-main-oracle.ps1（SHA-256 7175c03aff38e3c600e06c75248f3a81c660b6a671bbe142c627a54e48bba96b）。
# 相對來源的改動：repo root 改為本檔上兩層並核對 build.gradle／settings.gradle。
# 參數：
#   -Gate  gates.ps1 -Mode Main 產生的 gate root（含 execution/ 與 server-restore.json）
param([Parameter(Mandatory)][string]$Gate)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach($marker in 'build.gradle','settings.gradle'){if(-not(Test-Path -LiteralPath (Join-Path $repo $marker) -PathType Leaf)){throw "repo root 缺少 ${marker}：$repo"}}
$evidence=Join-Path $Gate 'execution'
$runtime=Join-Path $Gate 'server-fresh'
$out=Get-Content -LiteralPath (Join-Path $evidence 'stdout.log') -Raw
$execution=Get-Content -LiteralPath (Join-Path $evidence 'execution.json') -Raw | ConvertFrom-Json
$processes=@(Get-Content -LiteralPath (Join-Path $evidence 'processes.json') -Raw | ConvertFrom-Json)
$server=@($processes | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
if($server.Count -ne 1){throw 'main-only 必須恰有一個實際 server JVM'}
$javaPid=$server[0].pid
$mods=@([regex]::Matches($out,'(?m)^\s*[-|+\\ ]+\s+(?<id>[a-z0-9_-]+)\s+(?<version>[^\r\n ]+)\s*$') | ForEach-Object {$_.Groups['id'].Value})
$loaded=$mods -contains 'quantumchamber-testmod'
$stale=@([regex]::Matches($out,'(?m)^\[[0-9]{2}:[0-9]{2}:[0-9]{2}\] \[(?:main|Server thread)/WARN\] \(Minecraft\) Missing data pack quantumchamber-testmod(?=\r?$)') | ForEach-Object {$_.Value})
$argFiles=@(Get-ChildItem -LiteralPath $evidence -Filter "process-$javaPid-*" -File)
$cpText=$server[0].commandLine+"`n"+((@($argFiles | ForEach-Object {Get-Content -LiteralPath $_.FullName -Raw})) -join "`n")
$launch=Join-Path $evidence 'loom-launch.cfg'
if(Test-Path -LiteralPath $launch){$cpText+="`n"+(Get-Content -LiteralPath $launch -Raw)}
$classpathHits=@([regex]::Matches($cpText,'(?i)[^\s";]*testmod[^\s";]*') | ForEach-Object {$_.Value} | Sort-Object -Unique)
$classFile=Join-Path $evidence "classload-$javaPid.log"
if(-not(Test-Path -LiteralPath $classFile)){throw '缺少 exact server PID class-load log'}
$classText=Get-Content -LiteralPath $classFile -Raw
$testTypes=@()
foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo 'src/testmod/java') -File -Recurse -Filter '*.java') {
    $source=Get-Content -LiteralPath $file.FullName -Raw
    $package=[regex]::Match($source,'(?m)^package ([^;]+);').Groups[1].Value
    $testTypes+=$package+'.'+$file.BaseName
}
$classHits=@()
foreach($line in $classText -split "`n") {
    if($line -match '\[class,load\]\s+(\S+)\s+source:\s+(.*)$') {
        $type=$Matches[1];$location=$Matches[2]
        if($type.StartsWith('dev.quantumchamber.gametest.') -or @($testTypes | Where-Object {$type-eq $_ -or $type.StartsWith($_+'$')}).Count -gt 0 -or $location -match '(?i)testmod') {$classHits+=$line}
    }
}
$worldSnapshots=@()
foreach($save in [regex]::Matches($out,'(?s)Saving the game.*?Saved the game')) {
    $storageNames=@([regex]::Matches($save.Value,'ThreadedAnvilChunkStorage \(([^)]+)\): All chunks are saved') | ForEach-Object {$_.Groups[1].Value} | Sort-Object -Unique)
    $mapping=@{world='minecraft:overworld';DIM1='minecraft:the_end';'DIM-1'='minecraft:the_nether';superposition='quantumchamber:superposition'}
    $keys=@($storageNames | ForEach-Object {if(-not $mapping.ContainsKey($_)){throw "未預期的原生 storage：$_"};$mapping[$_]} | Sort-Object)
    $worldSnapshots += [ordered]@{report='native save-all flush storage names, cross-checked against exact shutdown world keys';observedStorageNames=$storageNames;worldKeys=$keys;rawLines=$save.Value}
}
$shutdown=$out.Substring($out.IndexOf('Stopping server'))
$shutdownKeys=@([regex]::Matches($shutdown,"Saving chunks for level '[^\r\n]+'/([^\r\n]+)") | ForEach-Object {$_.Groups[1].Value.Trim()} | Sort-Object -Unique)
$before=Get-Content -LiteralPath (Join-Path $evidence 'before.json') -Raw | ConvertFrom-Json
$after=Get-Content -LiteralPath (Join-Path $evidence 'after.json') -Raw | ConvertFrom-Json
$restore=Get-Content -LiteralPath (Join-Path $Gate 'server-restore.json') -Raw | ConvertFrom-Json
$locks=@(Get-Content -LiteralPath (Join-Path $evidence 'locks-after.json') -Raw | ConvertFrom-Json)
$stopOk=$execution.exitCode -eq 0 -and $execution.doneSeen -and $execution.stdinStop -and $shutdown.Contains('All dimensions are saved') -and $shutdown.Contains('M3_UNIVERSE_STOPPED_VERIFIED worlds=0 runtimeReceipts=0 quarantine=0 contextDetached=true') -and @($locks | Where-Object {-not $_.exclusiveAvailable}).Count-eq 0
$expectedKeys=@('minecraft:overworld','minecraft:the_end','minecraft:the_nether','quantumchamber:superposition')
$worldOk=$worldSnapshots.Count-eq 2 -and (($worldSnapshots[0].worldKeys|ConvertTo-Json -Compress)-ceq ($expectedKeys|ConvertTo-Json -Compress)) -and (($worldSnapshots[1].worldKeys|ConvertTo-Json -Compress)-ceq ($expectedKeys|ConvertTo-Json -Compress)) -and (($shutdownKeys|ConvertTo-Json -Compress)-ceq ($expectedKeys|ConvertTo-Json -Compress))
$catalogOk= -not $before.catalogExists -and -not $after.catalogExists
$report=[ordered]@{status=$(if(-not $loaded -and $mods.Contains('quantumchamber') -and $classpathHits.Count-eq 0 -and $argFiles.Count-ge 1 -and $classHits.Count-eq 0 -and $stopOk -and $worldOk -and $catalogOk -and $restore.exactRestored){'PASS'}else{'FAIL'});server=$server[0];fabricLoadedModIds=$mods;layer1TestmodLoaded=$loaded;layer2RuntimePathHits=$classpathHits;layer2Argfiles=@($argFiles.Name);layer3ClassLoadHits=$classHits;testmodOnlyTypeCount=$testTypes.Count;staleDatapackWarnings=$stale;warningCount=$stale.Count;normalStop=$stopOk;worldSnapshots=$worldSnapshots;shutdownWorldKeys=$shutdownKeys;worldKeysExact=$worldOk;universeCatalogAbsentBeforeAfter=$catalogOk;catalogRecordsBefore=@();catalogRecordsAfter=@();catalogRecordsSha256='4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945';catalogRecordsEvidence='ABSENT catalog plus production bootstrap worlds=0; hash is canonical empty JSON array';exactRestore=$restore.exactRestored;classLogSha256=(Get-FileHash -LiteralPath $classFile -Algorithm SHA256).Hash}
$report|ConvertTo-Json -Depth 15|Set-Content -LiteralPath (Join-Path $Gate 'main-only-oracle.json') -Encoding utf8
$report|ConvertTo-Json -Depth 15
if($report.status-ne 'PASS'){throw 'main-only 三層 oracle 未全部通過'}
