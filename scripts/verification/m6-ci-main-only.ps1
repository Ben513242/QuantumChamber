# Windows CI Main-only薄接線：沿用既有Main runner與三層oracle，不以GameTest替代。
param([switch]$VerifyOnly,[string]$Receipt='',[string]$EvidenceRoot='',[string]$Gradle='',[string]$ExpectedHead='',[switch]$Ci)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not $IsWindows){throw 'M6_MAIN_WINDOWS_ONLY：此Main process-harness只在Windows執行'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$head=(git -C $repo rev-parse HEAD).Trim()
if(-not $ExpectedHead){$ExpectedHead=if($env:GITHUB_SHA){$env:GITHUB_SHA}else{$head}}
if($head -cne $ExpectedHead){throw 'M6_MAIN_HEAD_MISMATCH：checkout與預期SHA不符'}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'm6-ci-gametest.ps1'),[ref]$tokens,[ref]$errors)
foreach($name in @('Assert-M6DirectoryPath','Assert-M6FreshWorld')){
 $d=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$false))
 if($errors.Count -or $d.Count -ne 1){throw 'M6_MAIN_SETUP_FAILED：安全helper缺失'}
 Invoke-Expression $d[0].Extent.Text
}
function Read-MainReceipt([string]$Path){
 if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'M6_MAIN_RECEIPT_MISSING：必要Main receipt不存在'}
 $bundle=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path))
 try{$r=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -DateKind String}catch{throw 'M6_MAIN_RECEIPT_INVALID：receipt不是JSON'}
 if($r.schema -ne 1 -or $r.status -cne 'PASS'){throw 'M6_MAIN_RECEIPT_INVALID：不是完成的PASS receipt'}
 if($r.headBefore -cne $ExpectedHead -or $r.headAfter -cne $ExpectedHead){throw 'M6_MAIN_HEAD_MISMATCH：Main不是同SHA'}
 if($r.runtimeScope -cne 'main-only' -or $r.platform -cne 'Windows'){throw 'M6_MAIN_SCOPE_MISMATCH：不得借GameTest或其他平台當Main'}
 if($r.gate -notmatch '^gate-main-only-[0-9a-f]{32}$'){throw 'M6_MAIN_SCOPE_MISMATCH：gate不是唯一Main owner'}
 $data=@{};$paths=@{}
 foreach($f in $r.files){
  if($paths.ContainsKey($f.kind) -or [IO.Path]::IsPathRooted($f.path) -or $f.path.Contains('\') -or @($f.path.Split('/') | Where-Object {$_ -in @('..','.','')}).Count){throw 'M6_MAIN_EVIDENCE_INVALID：檔案引用重複／越界'}
  if(($f.kind -eq 'start' -and $f.path -cne 'main-ci-start.json') -or ($f.kind -ne 'start' -and -not $f.path.StartsWith($r.gate+'/',[StringComparison]::Ordinal))){throw 'M6_MAIN_SCOPE_MISMATCH：原件不屬同一Main gate'}
  $full=[IO.Path]::GetFullPath((Join-Path $bundle $f.path))
  if(-not $full.StartsWith($bundle+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'M6_MAIN_EVIDENCE_INVALID：引用不屬bundle'}
  Assert-M6DirectoryPath ([IO.Path]::GetDirectoryName($full)) | Out-Null
  try{$item=Get-Item -LiteralPath $full -Force -ErrorAction Stop}catch{throw "M6_MAIN_EVIDENCE_MISSING：$($f.kind)"}
  if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'M6_MAIN_EVIDENCE_INVALID：引用必須為實體檔案'}
  if((Get-FileHash -LiteralPath $full).Hash -cne $f.sha256){throw "M6_MAIN_EVIDENCE_HASH_MISMATCH：$($f.kind)"}
  $paths[$f.kind]=$full
 }
 foreach($kind in @('start','gate','execution','oracle','restore','preflight','runtimeLog','runtimeManifest','stdout','before','after','processes','locksAfter','classload')){
  if(-not $paths.ContainsKey($kind)){throw "M6_MAIN_EVIDENCE_MISSING：$kind"}
 }
 foreach($kind in @('start','gate','execution','oracle','restore')){$data[$kind]=Get-Content -LiteralPath $paths[$kind] -Raw | ConvertFrom-Json -DateKind String}
 $s=$data.start;$g=$data.gate;$e=$data.execution;$o=$data.oracle;$restore=$data.restore
 if($s.head -cne $ExpectedHead -or $s.runtimeScope -cne 'main-only'){throw 'M6_MAIN_HEAD_MISMATCH：啟動receipt不符'}
 if($g.mode -cne 'Main' -or $g.status -cne 'PASS' -or $e.command -notmatch '"runServer"' -or $e.command -match 'runGameTest|-Dfabric-api[.]gametest'){throw 'M6_MAIN_SCOPE_MISMATCH：不是獨立Main invocation'}
 if($o.status -cne 'PASS' -or $o.layer1TestmodLoaded -ne $false -or $o.layer1ClientProbeLoaded -ne $false -or @($o.fabricLoadedModIds | Where-Object {$_ -eq 'quantumchamber-testmod' -or $_ -match 'client[-_.]?probe'}).Count -or @($o.layer2RuntimePathHits).Count -or @($o.layer3ClassLoadHits).Count){throw 'M6_MAIN_TESTMOD_LEAK：三層oracle不是完整無污染PASS'}
 if($e.exitCode -ne 0 -or -not $e.doneSeen -or -not $e.stdinStop -or $e.nativeSaves -lt 2 -or -not $o.normalStop){throw 'M6_MAIN_STOP_MISMATCH：未正常停止／存檔'}
 if(-not $restore.exactRestored -or $restore.deleted -or -not $o.exactRestore -or -not $o.worldKeysExact -or -not $o.universeCatalogAbsentBeforeAfter){throw 'M6_MAIN_RESTORE_MISMATCH：restore／world keys／catalog不符'}
 $servers=@(Get-Content -LiteralPath $paths.processes -Raw | ConvertFrom-Json -DateKind String | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
 if($servers.Count -ne 1 -or $servers[0].pid -ne $o.server.pid -or $servers[0].commandLine -match '-Dfabric-api[.]gametest'){throw 'M6_MAIN_SCOPE_MISMATCH：server PID／profile不符'}
 if((Get-FileHash -LiteralPath $paths.classload).Hash -cne $o.classLogSha256 -or [IO.Path]::GetFileName($paths.classload) -cne "classload-$($o.server.pid).log"){throw 'M6_MAIN_EVIDENCE_INVALID：classload不是exact server PID原件'}
 if(-not @($o.layer2Argfiles).Count){throw 'M6_MAIN_EVIDENCE_MISSING：沒有runtime argfiles'}
 foreach($arg in $o.layer2Argfiles){if(@($paths.Values | Where-Object {[IO.Path]::GetFileName($_) -ceq $arg}).Count -ne 1){throw 'M6_MAIN_EVIDENCE_MISSING：argfile缺失'}}
 return $r
}
if($VerifyOnly){if(-not $Receipt){throw 'M6_MAIN_RECEIPT_MISSING：必須指定Receipt'};Read-MainReceipt $Receipt | Out-Null;Write-Output "M6_MAIN_VERIFY PASS HEAD=$head RECEIPT=$Receipt";return}
if($Receipt){throw 'M6_MAIN_SETUP_FAILED：Run由EvidenceRoot產生receipt'}
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo 'build/m6-main-only'}
$EvidenceRoot=Assert-M6DirectoryPath $EvidenceRoot
git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$EvidenceRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw 'M6_OUTPUT_NOT_IGNORED：Main證據首次寫入前須gitignored'}
if(Test-Path -LiteralPath (Join-Path $EvidenceRoot 'main-ci-start.json')){throw 'M6_MAIN_EVIDENCE_COLLISION：須用全新證據根'}
if($Ci){
 $wrapper=Join-Path $repo 'gradlew.bat';if(-not $Gradle){$Gradle=$wrapper}
 if([IO.Path]::GetFullPath($Gradle) -cne $wrapper -or -not(Test-Path -LiteralPath $Gradle -PathType Leaf)){throw 'M6_CI_WRAPPER_REQUIRED：CI須用checkout gradlew.bat'}
 if((Get-Content -LiteralPath (Join-Path $repo 'gradle/wrapper/gradle-wrapper.properties') -Raw) -notmatch 'gradle-8[.]8-bin[.]zip'){throw 'M6_CI_WRAPPER_REQUIRED：distro必須8.8'}
 $version=(& java -version 2>&1 | Out-String);if($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21[.]'){throw 'M6_JAVA_21_REQUIRED'}
 Assert-M6FreshWorld (Join-Path $repo 'run/server/world') | Out-Null
}
if(-not(Test-Path -LiteralPath $EvidenceRoot)){New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null}
$startFile=Join-Path $EvidenceRoot 'main-ci-start.json';$Receipt=Join-Path $EvidenceRoot 'main-ci-receipt.json'
[ordered]@{head=$head;runtimeScope='main-only';platform='Windows';started=[DateTimeOffset]::UtcNow.ToString('o');ciFreshPrecondition=[bool]$Ci} | ConvertTo-Json | Set-Content -LiteralPath $startFile -Encoding utf8
$before=@(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Filter 'gate-main-only-*' | ForEach-Object Name)
try{
 & (Join-Path $PSScriptRoot 'gates.ps1') -Mode Main -Label main-only -EvidenceRoot $EvidenceRoot -Gradle $Gradle
 $new=@(Get-ChildItem -LiteralPath $EvidenceRoot -Directory -Filter 'gate-main-only-*' | Where-Object {$_.Name -cnotin $before})
 if($new.Count -ne 1){throw 'M6_MAIN_SCOPE_MISMATCH：本次Main gate必須恰一個'}
 $gate=$new[0].FullName
 & (Join-Path $PSScriptRoot 'main-oracle.ps1') -Gate $gate | Out-Null
 $oracle=Get-Content -LiteralPath (Join-Path $gate 'main-only-oracle.json') -Raw | ConvertFrom-Json
 $map=[ordered]@{start=$startFile;gate=(Join-Path $gate 'gate.json');execution=(Join-Path $gate 'execution/execution.json');oracle=(Join-Path $gate 'main-only-oracle.json');restore=(Join-Path $gate 'server-restore.json');preflight=(Join-Path $gate 'server-preflight.json');runtimeLog=(Join-Path $gate 'execution/runtime-full.log');runtimeManifest=(Join-Path $gate 'execution/runtime-log.json');stdout=(Join-Path $gate 'execution/stdout.log');before=(Join-Path $gate 'execution/before.json');after=(Join-Path $gate 'execution/after.json');processes=(Join-Path $gate 'execution/processes.json');locksAfter=(Join-Path $gate 'execution/locks-after.json');classload=(Join-Path $gate "execution/classload-$($oracle.server.pid).log")}
 if(Test-Path -LiteralPath (Join-Path $gate 'server-original-files.json')){$map.originalFiles=Join-Path $gate 'server-original-files.json'}
 if(Test-Path -LiteralPath (Join-Path $gate 'execution/loom-launch.cfg')){$map.loomLaunch=Join-Path $gate 'execution/loom-launch.cfg'}
 $i=0;foreach($arg in $oracle.layer2Argfiles){$map["argfile$i"]=Join-Path $gate "execution/$arg";$i++}
 $files=@(foreach($entry in $map.GetEnumerator()){[ordered]@{kind=$entry.Key;path=[IO.Path]::GetRelativePath($EvidenceRoot,$entry.Value).Replace('\','/');sha256=(Get-FileHash -LiteralPath $entry.Value).Hash}})
 $after=(git -C $repo rev-parse HEAD).Trim();if($after -cne $ExpectedHead){throw 'M6_MAIN_HEAD_MISMATCH：Main期間HEAD改變'}
 [ordered]@{schema=1;status='PASS';headBefore=$head;headAfter=$after;runtimeScope='main-only';platform='Windows';gate=[IO.Path]::GetRelativePath($EvidenceRoot,$gate).Replace('\','/');files=$files} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Receipt -Encoding utf8
 Read-MainReceipt $Receipt | Out-Null
 Write-Output "M6_MAIN_RUN PASS HEAD=$head RECEIPT=$Receipt"
}catch{
 [ordered]@{schema=1;status='FAIL';headBefore=$head;headAfter=(git -C $repo rev-parse HEAD).Trim();runtimeScope='main-only';platform='Windows';error=$_.Exception.Message} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'main-ci-failure.json') -Encoding utf8
 throw
}
