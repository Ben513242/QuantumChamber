# M6 Windows process-harness 回歸：真 Invoke-Gradle＋會自然 exit0 的受控假Gradle；不啟動Minecraft。
param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not $IsWindows){throw 'M6_DEADLINE_HARNESS_WINDOWS_ONLY：需要Win32_Process與cmd，不是M6 native chain'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$head=(git -C $repo rev-parse HEAD).Trim()
$tokens=$null;$errors=$null
$ciAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'm6-ci-gametest.ps1'),[ref]$tokens,[ref]$errors)
$guard=@($ciAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-M6DirectoryPath'},$false))
if($errors.Count -or $guard.Count -ne 1){throw 'M6_DEADLINE_FIXTURE_SETUP_FAILED：安全helper缺失'}
Invoke-Expression $guard[0].Extent.Text
$OutputRoot=Assert-M6DirectoryPath $OutputRoot
git -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$OutputRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw 'M6_OUTPUT_NOT_IGNORED：deadline證據首次寫入前必須gitignored'}
if(Test-Path -LiteralPath $OutputRoot){throw 'M6_DEADLINE_EVIDENCE_COLLISION：使用全新root'}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
. (Join-Path $PSScriptRoot 'gate-evidence.ps1')
$source=Join-Path $PSScriptRoot 'gates.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'M6_DEADLINE_FIXTURE_SETUP_FAILED：gates解析失敗'}
$functionTexts=@();$functionHashes=@()
foreach($name in @('Save-Json','Root-State','Lock-State','Java-Processes','Invoke-Gradle')){
    $definition=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$false))
    if($definition.Count -ne 1){throw "M6_DEADLINE_FIXTURE_SETUP_FAILED：$name"}
    $text=$definition[0].Extent.Text;$functionTexts+=$text
    $functionHashes+=[ordered]@{name=$name;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($text)))}
}
# 原函式原文存檔dot-source，保留PSScriptRoot上下文；init檔逐字複製，沒有改寫函式body。
$import=Join-Path $OutputRoot 'imported-functions.ps1'
$functionTexts -join "`n" | Set-Content -LiteralPath $import -Encoding utf8
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'runtime.init.gradle') -Destination (Join-Path $OutputRoot 'runtime.init.gradle')
. $import
$scenarios=@(
 [pscustomobject]@{name='m6-total';mode='M6Compile';timeout=1;idle=90;reason='total';sleep=2200;pollPause=0;loopPause=0;late=$false},
 [pscustomobject]@{name='m6-idle';mode='M6JUnit';timeout=60;idle=1;reason='idle';sleep=2200;pollPause=0;loopPause=0;late=$false},
 [pscustomobject]@{name='m6-within-budget';mode='M6GameTest';timeout=60;idle=60;reason='';sleep=2200;pollPause=0;loopPause=0;late=$false},
 [pscustomobject]@{name='legacy-grace-unchanged';mode='Windows';timeout=1;idle=1;reason='';sleep=2200;pollPause=0;loopPause=0;late=$false},
 [pscustomobject]@{name='idle-exit-during-poll';mode='M6JUnit';timeout=60;idle=1;reason='idle';sleep=2200;pollPause=4000;loopPause=0;late=$false},
 [pscustomobject]@{name='idle-late-output-live';mode='M6JUnit';timeout=60;idle=1;reason='idle';sleep=2200;pollPause=0;loopPause=4000;late=$true},
 [pscustomobject]@{name='exit-before-total-poll-slow';mode='M6Compile';timeout=3;idle=90;reason='';sleep=800;pollPause=5000;loopPause=0;late=$false}
)
$realJavaProcesses=(Get-Command Java-Processes).ScriptBlock
$results=@()
foreach($scenario in $scenarios){
 $caseRoot=Join-Path $OutputRoot $scenario.name;New-Item -ItemType Directory -Path $caseRoot | Out-Null
 $runtime=Join-Path $caseRoot 'runtime';New-Item -ItemType Directory -Path $runtime | Out-Null
 $child=Join-Path $caseRoot 'child.ps1'
 $childLines=@("Write-Output ('CHILD_START_UTC=' + [DateTimeOffset]::UtcNow.ToString('o'))",("Start-Sleep -Milliseconds "+$scenario.sleep))
 if($scenario.late){$childLines+=@("Write-Output ('LATE_OUTPUT_UTC=' + [DateTimeOffset]::UtcNow.ToString('o'))","1..16 | ForEach-Object {Start-Sleep -Milliseconds 250; Write-Output ('HEARTBEAT_UTC=' + [DateTimeOffset]::UtcNow.ToString('o'))}")}
 $childLines+="Write-Output ('CHILD_EXIT_UTC=' + [DateTimeOffset]::UtcNow.ToString('o'))"
 $childLines | Set-Content -LiteralPath $child -Encoding utf8
 $gradle=Join-Path $caseRoot 'fake-gradle.cmd';$launch='"'+(Join-Path $PSHOME 'pwsh.exe')+'" -NoProfile -NonInteractive -File "'+$child+'"'
 @('@echo off','echo M6_DEADLINE_CONTROLLED_FIXTURE',$launch,'exit /b 0') | Set-Content -LiteralPath $gradle -Encoding ascii
 $script:discoveryCalls=0;$script:blockedPoll=@();$script:blockedLoop=@();$script:loopPaused=$false;$script:deadlineCase=$scenario
 function Java-Processes {
  if(-not $script:deadlineCase.pollPause){return @(& $realJavaProcesses)}
  $script:discoveryCalls++
  if($script:discoveryCalls -eq 2){$at=[DateTimeOffset]::UtcNow;Microsoft.PowerShell.Utility\Start-Sleep -Milliseconds $script:deadlineCase.pollPause;$script:blockedPoll=@($at.ToString('o'),[DateTimeOffset]::UtcNow.ToString('o'))}
  return @()
 }
 function Start-Sleep {
  param([int]$Milliseconds)
  if($Milliseconds -eq 100 -and $script:deadlineCase.loopPause -and -not $script:loopPaused){$script:loopPaused=$true;$at=[DateTimeOffset]::UtcNow;Microsoft.PowerShell.Utility\Start-Sleep -Milliseconds $script:deadlineCase.loopPause;$script:blockedLoop=@($at.ToString('o'),[DateTimeOffset]::UtcNow.ToString('o'))}
  else{Microsoft.PowerShell.Utility\Start-Sleep -Milliseconds $Milliseconds}
 }
 $Mode=$scenario.mode;$caught=$null;$returned=$null
 try{$returned=@(Invoke-Gradle @('controlled-fixture') (Join-Path $caseRoot 'execution') $runtime -timeout $scenario.timeout -idleSeconds $scenario.idle -runtimeLogMode compile-only)[-1]}catch{$caught=$_.Exception.Message}
 $execution=Get-Content -LiteralPath (Join-Path $caseRoot 'execution/execution.json') -Raw | ConvertFrom-Json -DateKind String
 $before=Get-Content -LiteralPath (Join-Path $caseRoot 'execution/before.json') -Raw | ConvertFrom-Json
 $after=Get-Content -LiteralPath (Join-Path $caseRoot 'execution/after.json') -Raw | ConvertFrom-Json
 $out=Get-Content -LiteralPath (Join-Path $caseRoot 'execution/stdout.log') -Raw
 $exitMarker=[regex]::Match($out,'CHILD_EXIT_UTC=([^\r\n]+)');$late=[regex]::Match($out,'LATE_OUTPUT_UTC=([^\r\n]+)')
 $ok=$execution.exitCode -eq 0 -and $exitMarker.Success -and -not $before.worldExists -and -not $after.worldExists -and @($execution.processes | Where-Object {$null -ne $_}).Count -eq 0
 if($scenario.reason){$ok=$ok -and $caught -and $caught.Contains('M6_PHASE_DEADLINE_EXCEEDED') -and $execution.status -ceq 'FAIL' -and $execution.phaseDeadline.reason -ceq $scenario.reason}
 else{$ok=$ok -and $null -eq $caught -and $returned -eq 0 -and $execution.status -ceq 'EXIT_ZERO';if($Mode -like 'M6*'){$ok=$ok -and $null -eq $execution.phaseDeadline}else{$ok=$ok -and 'phaseDeadline' -cnotin $execution.PSObject.Properties.Name}}
 if($Mode -like 'M6*'){
  $ok=$ok -and $execution.configuredTimeoutSeconds -eq $scenario.timeout -and $execution.configuredIdleSeconds -eq $scenario.idle -and $execution.nativeExitTimeUtc
  $nativeExit=[DateTimeOffset]::Parse($execution.nativeExitTimeUtc)
  if($scenario.late){$ok=$ok -and $late.Success -and $script:blockedLoop.Count -eq 2 -and [DateTimeOffset]::Parse($late.Groups[1].Value) -lt [DateTimeOffset]::Parse($script:blockedLoop[1]) -and [DateTimeOffset]::Parse($script:blockedLoop[1]) -lt $nativeExit -and -not $execution.phaseDeadline.observedAfterExit}
  if($scenario.name -eq 'idle-exit-during-poll'){$ok=$ok -and $execution.phaseDeadline.observedAfterExit -and $nativeExit -lt [DateTimeOffset]::Parse($execution.phaseDeadline.observedAt)}
  if($scenario.name -eq 'exit-before-total-poll-slow'){$due=[DateTimeOffset]::Parse($execution.start).AddSeconds($scenario.timeout);$ok=$ok -and $nativeExit -lt $due -and [DateTimeOffset]::Parse($execution.end) -gt $due}
 }
 $results+=[ordered]@{name=$scenario.name;mode=$Mode;passed=[bool]$ok;expectedReason=$scenario.reason;nativeExit=$execution.exitCode;nativeExitTimeUtc=$(if($Mode -like 'M6*'){$execution.nativeExitTimeUtc}else{$null});observedStatus=$execution.status;blockedPoll=$script:blockedPoll;blockedLoop=$script:blockedLoop;childExitMarkerUtc=$exitMarker.Groups[1].Value;lateOutputUtc=$late.Groups[1].Value;error=$caught;execution=(Join-Path $caseRoot 'execution/execution.json');minecraftLaunched=$false;worldBefore=$before.worldExists;worldAfter=$after.worldExists}
 if(-not $ok){throw "M6_DEADLINE_CONTRACT_MISMATCH case=$($scenario.name) error=$caught"}
}
if((git -C $repo rev-parse HEAD).Trim() -cne $head){throw 'M6_HEAD_CHANGED：deadline檢查期間HEAD改變'}
[ordered]@{status='PASS';head=$head;fixture=$true;runtimeScope='windows-process-harness-only';minecraftLaunched=$false;source=$source;sourceSha256=(Get-FileHash -LiteralPath $source).Hash;functionHashes=$functionHashes;cases=$results} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputRoot 'summary.json') -Encoding utf8
Write-Output "M6_DEADLINE_CHECK PASS cases=$($results.Count) HEAD=$head ROOT=$OutputRoot"
