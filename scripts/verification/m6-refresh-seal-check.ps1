# S1a 只讀終局 oracle；只接受自然 startup 的單一 GameTest 與固定十事件。
param([Parameter(Mandatory)][string]$GateRoot,[ValidateSet('Focused','Full')][string]$Scope='Focused',[string]$OutputPath='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Require($ok,[string]$code){if(-not $ok){throw "S1A_ORACLE_$code"}}
function Read-Json([string]$relative){$p=Join-Path $GateRoot $relative;Require (Test-Path -LiteralPath $p -PathType Leaf) ('MISSING_'+$relative);Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String}
$GateRoot=[IO.Path]::GetFullPath($GateRoot)
if(-not $OutputPath){$OutputPath=$GateRoot+'-s1a-oracle-'+[guid]::NewGuid().ToString('N')+'.json'}
Require (-not(Test-Path -LiteralPath $OutputPath)) 'OUTPUT_EXISTS'
$receipt=Read-Json 'gate.json';Require ($receipt.status -ceq 'PASS' -and $receipt.mode -ceq 'M6GameTest') 'GATE'
$execution=Read-Json 'execution/execution.json'
Require ($execution.status -ceq 'EXIT_ZERO' -and $execution.exitCode -eq 0 -and $null -eq $execution.phaseDeadline -and $execution.nativeExitTimeUtc) 'EXECUTION'
$processes=@(Read-Json 'execution/processes.json' | Where-Object {$_.commandLine -match 'net.fabricmc.devlaunchinjector.Main'})
Require ($processes.Count -eq 1 -and $processes[0].commandLine.Contains('-Dquantumchamber.gametest.m6Only=true')) 'PROCESS'
$locks=@(Read-Json 'execution/locks-after.json');Require (@($locks | Where-Object {-not $_.exclusiveAvailable}).Count -eq 0) 'LOCK'
$restore=Read-Json 'world-restore.json';Require ($restore.exactRestored -eq $true -and $restore.deleted -eq $false -and $restore.freshEvidence) 'RESTORE'
$summary=Read-Json 'm6-summary.json';Require ($summary.status -ceq 'PASS' -and $summary.runtimeScope -ceq 'testmod-present') 'SUMMARY'
$selection=Read-Json 'scope.json';$expectedCount=if($Scope -ceq 'Focused'){1}else{9}
$target='quantumchamber:m1_empty|m6refreshsealgametests.naturalstartuprefreshsealsexactboundsources'
Require ($selection.scope -ceq 'm6' -and $selection.status -ceq 'PASS' -and $selection.expected -eq $expectedCount -and $selection.actual -eq $expectedCount) 'SCOPE'
Require (@($selection.missing).Count -eq 0 -and @($selection.extra).Count -eq 0 -and @($selection.expectedCases | Where-Object {$_ -ceq $target}).Count -eq 1 -and @($selection.actualCases | Where-Object {$_ -ceq $target}).Count -eq 1) 'CASES'
if($Scope -ceq 'Focused'){Require (@($selection.onlyBatches).Count -eq 1 -and $selection.onlyBatches[0] -ceq 'm6_universe_refresh_seal') 'BATCH'}else{Require (@($selection.onlyBatches).Count -eq 0) 'FULL_BATCH'}
$xmlPath=Join-Path $GateRoot 'result-build/gametest-m6-results.xml';Require (Test-Path -LiteralPath $xmlPath -PathType Leaf) 'XML_MISSING'
[xml]$xml=Get-Content -LiteralPath $xmlPath -Raw
$cases=@($xml.SelectNodes('//testcase'));Require ($cases.Count -eq $expectedCount -and @($xml.SelectNodes('//failure|//error|//skipped')).Count -eq 0) 'XML'
Require (@($cases | Where-Object {($_.classname+'|'+$_.name) -ceq $target}).Count -eq 1) 'XML_TARGET'
Require ((Get-FileHash -LiteralPath $xmlPath -Algorithm SHA256).Hash -ieq $summary.xmlSha256) 'XML_HASH'
$logPath=Join-Path $GateRoot 'execution/runtime-logs/latest.log';Require (Test-Path -LiteralPath $logPath -PathType Leaf) 'LOG_MISSING'
Require ((Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash -ieq $summary.logSha256) 'LOG_HASH'
$lines=@(Get-Content -LiteralPath $logPath | Where-Object {$_.Contains('QC_M6_SEAL_S1A ')})
$order=@('FACTORY_PIN','ARM_EXACT','REFRESH_HEAD','BEFORE_WORK','REFRESH_RETURN','REFRESH_CALL_RETURNED','HOLDER_INSTALL','GAMETEST_ASSERTED','STOPPING_DETACHED','STOPPED')
Require ($lines.Count -eq $order.Count) 'EVENT_COUNT'
$events=@();$nonce=$null;$pidValue=$null;$startValue=$null
$fields=@('allWeakSlotsCleared','event','failure','pid','predicate','seq','startInstant','startupNonce')
for($i=0;$i -lt $lines.Count;$i++){
    $line=$lines[$i];$prefix='QC_M6_SEAL_S1A ';$offset=$line.IndexOf($prefix)
    $payload=$line.Substring($offset+$prefix.Length);Require ([Text.Encoding]::UTF8.GetByteCount($prefix+$payload) -le 16384) 'EVENT_BYTES'
    $document=[Text.Json.JsonDocument]::Parse($payload)
    try {
        $properties=@($document.RootElement.EnumerateObject())
        Require ($properties.Count -eq 8 -and @($properties.Name | Sort-Object -Unique).Count -eq 8) 'JSON_DUPLICATE_FIELDS'
        foreach($property in $properties){
            $kind=if($property.Name -in @('predicate','allWeakSlotsCleared')){'True','False'}elseif($property.Name -in @('pid','seq')){@('Number')}else{@('String')}
            Require ($property.Value.ValueKind.ToString() -cin $kind) 'JSON_TYPE'
        }
    } finally {$document.Dispose()}
    $e=$payload | ConvertFrom-Json -DateKind String
    Require ((@($e.PSObject.Properties.Name | Sort-Object) -join '|') -ceq ($fields -join '|')) 'EVENT_FIELDS'
    Require ($e.seq -eq ($i+1) -and $e.event -ceq $order[$i] -and $e.failure -ceq '' -and $e.predicate -eq $true -and $e.allWeakSlotsCleared -eq ($i -eq 9)) 'EVENT_VALUE'
    if($i -eq 0){$nonce=[string]$e.startupNonce;$pidValue=$e.pid;$startValue=[string]$e.startInstant;Require ($nonce -match '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') 'NONCE'}
    Require ($e.startupNonce -ceq $nonce -and $e.pid -eq $pidValue -and $e.startInstant -ceq $startValue) 'EVENT_IDENTITY'
    $events+=$e
}
Require ($pidValue -eq $processes[0].pid) 'PID'
# 沿用外部 process StartTime 的秒級容差，雙方先轉 UTC；不使用時間字串相等。
$delta=[Math]::Abs(([DateTimeOffset]::Parse($startValue).ToUniversalTime()-[DateTimeOffset]::Parse($processes[0].startTimeUtc).ToUniversalTime()).TotalMilliseconds)
Require ($delta -le 1000) 'START_TIME'
Require ([DateTimeOffset]::Parse($execution.start) -le [DateTimeOffset]::Parse($startValue) -and [DateTimeOffset]::Parse($execution.end) -ge [DateTimeOffset]::Parse($startValue)) 'INVOCATION_TIME'
# 既有 JSON 沒有 post-owner0 欄。此結論明確採信 runner：Restore-Root 首步 Assert-Idle，成功才寫 restore 與 gate PASS。
$result=[ordered]@{status='PASS';scope=$Scope;gate=$GateRoot;startupNonce=$nonce;pid=$pidValue;startDeltaMilliseconds=$delta;tests=$expectedCount;sealTests=1;beforeWorkAssertion='naturalStartupDoesNotSealBeforeNativeWork';events=$events;stopSeen=$true;ownerZeroEvidence='trusted Restore-Root Assert-Idle control flow at restore time; no independent post-owner JSON field';lockEvidence='execution/locks-after.json plus Restore-Root Assert-Idle';exactRestored=$true;xmlSha256=(Get-FileHash -LiteralPath $xmlPath).Hash;logSha256=(Get-FileHash -LiteralPath $logPath).Hash}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8
(Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash | Set-Content -LiteralPath ($OutputPath+'.sha256') -Encoding ascii
Write-Output "S1A_ORACLE PASS $OutputPath"
