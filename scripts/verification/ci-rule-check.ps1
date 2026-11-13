# ci-rule-check.ps1：把 workflow 某個 step 的 `run: |` 原文逐字抽出，在 fixture 目錄（build/ 與 src/testmod/java 的複本）
# 以與 GitHub Actions pwsh 相同的方式執行（前置 $ErrorActionPreference='stop'，dot-source）。
# fixture 同時放 default 與 M5 兩份 GameTest XML（build/gametest-results.xml、build/gametest-m5-results.xml；M5 F3c）。
# Mutation／SourceMutation 只改 fixture 內的複本：XML 缺漏／skip／fail（-MutationXml 選 default 或 m5），在 @GameTest 與方法之間插入另一個
# annotation（宣告格式漂移），或把方法改名為 <名稱>_windows（XML 中對應的 testcase 一起改名；用來驗證 _windows 規則的納入與排除）。
# 只讀輸入、只寫本 root；本檔不因 FAIL 而 throw，結果見 <root>/result.json 的 verdict（呼叫端自行判定預期 PASS／FAIL）。
# 來源：.superpowers/sdd/2026-09-21-m4-candidate-doors/ifix3-ci-rule-check.ps1（SHA-256 279b7b9f46d729ec78bfe72de0e201c09d84a772c50d99751c6e0614f4ded808），
# 其前身 ifix2-ci-rule-check.ps1 沒有 SourceMutation。
# 相對來源的改動：root 改為 <EvidenceRoot>/ci-<Label>-<GUID>；輸出標記改為 CI_RULE；M5 F3c 加上 M5 XML、-MutationXml 與 rename-windows。
# 參數：
#   -Label -Workflow -Step -GameTestXml -M5GameTestXml -JUnitDir -SourceRoot  必填；Step 為 workflow 內唯一的 step 名稱
#   -Mutation drop｜skip｜fail＋-MutationTarget（XML testcase name）＋-MutationXml default｜m5（預設 default）
#   -SourceMutation interpose-annotation｜rename-windows＋-SourceTarget（方法名稱）；rename-windows 之後，-MutationTarget 用改名後的名稱
#   -EvidenceRoot  root 的上層；必須在 repo 內且被 gitignore，預設 <repo>/.superpowers/verification
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]*$')][string]$Label,
    [Parameter(Mandatory)][string]$Workflow,
    [Parameter(Mandatory)][string]$Step,
    [Parameter(Mandatory)][string]$GameTestXml,
    [Parameter(Mandatory)][string]$M5GameTestXml,
    [string]$M4NormalGameTestXml='',
    [string]$DefaultRuntimeLog='',
    [string]$LegacyRuntimeLog='',
    [string]$M4NormalRuntimeLog='',
    [string]$M5RuntimeLog='',
    [switch]$PresetFixture,
    [ValidateSet('none','missing-marker','wrong-flag','wrong-m5-only','wrong-m5-normal','wrong-config','rollover','duplicate','split-marker','format-drift','bad-gzip','wrong-name','bad-date','unexpected-log','cross-root','old-piece','truncated-gzip','missing-one','middle-truncated','crc','isize','flags','extra-tail','duplicate-trailer','second-member','compressed-limit','expanded-limit','original-truncated')][string]$PresetMutation='none',
    [ValidateSet('default','legacy','normal','m5','m6')][string]$PresetMutationScope='default',
    [string]$M7GameTestXml='',
    [string]$M7RuntimeLog='',
    [ValidateSet('Windows','Ubuntu')][string]$M7Platform='Windows',
    [string]$M6GameTestXml='',
    [string]$M6RuntimeLog='',
    [string]$M6RuntimeRoot='',
    [string[]]$M6Artifacts=@(),
    [string]$MainReceipt='',
    [string]$M6NativeSummary='',
    [ValidateSet('none','mode','schema','chain','phase','receipt','restore','hash')][string]$NativeMutation='none',
    [ValidateSet('none','missing-receipt','wrong-head','gametest','testmod','stop','restore','missing-evidence')][string]$MainMutation='none',
    [Parameter(Mandatory)][string]$JUnitDir,
    [Parameter(Mandatory)][string]$SourceRoot,
    [ValidateSet('none','drop','skip','fail','wrong-scope')][string]$Mutation='none',
    [string]$MutationTarget='',
    [ValidateSet('default','normal','m5','m6','m7_home')][string]$MutationXml='default',
    [ValidateSet('none','interpose-annotation','rename-windows')][string]$SourceMutation='none',
    [string]$SourceTarget='',
    [string]$EvidenceRoot=''
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach($marker in 'build.gradle','settings.gradle'){if(-not(Test-Path -LiteralPath (Join-Path $repo $marker) -PathType Leaf)){throw "repo root 缺少 ${marker}：$repo"}}
# evidence 目錄必須在 repo 內、路徑無 reparse，且被 gitignore（不得落在 tracked tree）。
if(-not $EvidenceRoot){$EvidenceRoot=Join-Path $repo '.superpowers/verification'}
$EvidenceRoot=[IO.Path]::GetFullPath($EvidenceRoot)
if(-not $EvidenceRoot.StartsWith($repo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "evidence 目錄必須在 repo 內：$EvidenceRoot"}
$cursor=$EvidenceRoot
while($cursor){ if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "拒絕 reparse：$cursor"}; $parent=[IO.Path]::GetDirectoryName($cursor); if($parent -eq $cursor){break}; $cursor=$parent }
git -c "safe.directory=$repo" -C $repo check-ignore -q -- ([IO.Path]::GetRelativePath($repo,$EvidenceRoot).Replace('\','/')+'/x')
if($LASTEXITCODE -ne 0){throw "evidence 目錄未被 gitignore：$EvidenceRoot"}
if(-not(Test-Path -LiteralPath $EvidenceRoot)){New-Item -ItemType Directory -Path $EvidenceRoot | Out-Null}
$root=Join-Path $EvidenceRoot ("ci-$Label-"+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$lines=@(Get-Content -LiteralPath $Workflow -Encoding utf8)
$nameIndex=-1
for($i=0;$i -lt $lines.Count;$i++){ if($lines[$i] -match '^\s*- name:\s*(.+?)\s*$' -and $Matches[1] -ceq $Step){ if($nameIndex -ge 0){throw "step 名稱不唯一：$Step"}; $nameIndex=$i } }
if($nameIndex -lt 0){throw "workflow 找不到 step：$Step"}
$stepIndent=($lines[$nameIndex] -replace '^(\s*)-.*$','$1').Length
$runIndex=-1
for($i=$nameIndex+1;$i -lt $lines.Count;$i++){
    if($lines[$i] -match '^\s*- ' -and ($lines[$i] -replace '^(\s*)-.*$','$1').Length -le $stepIndent){break}
    if($lines[$i] -match '^(\s*)run:\s*\|\s*$'){ $runIndex=$i; $runIndent=$Matches[1].Length; break }
}
if($runIndex -lt 0){throw "step 沒有 run: | 區塊：$Step"}
$body=[Collections.Generic.List[string]]::new()
for($i=$runIndex+1;$i -lt $lines.Count;$i++){
    $line=$lines[$i]
    if($line.Trim().Length -eq 0){$body.Add('');continue}
    $indent=($line -replace '^(\s*).*$','$1').Length
    if($indent -le $runIndent){break}
    $body.Add($line)
}
$common=($body | Where-Object {$_.Length -gt 0} | ForEach-Object {($_ -replace '^(\s*).*$','$1').Length} | Measure-Object -Minimum).Minimum
$script=($body | ForEach-Object { if($_.Length -ge $common){$_.Substring($common)}else{$_} }) -join "`n"
if($M7GameTestXml){
    if($Step -cne '確認 M7 精確集合與隔離'){throw 'M7_FIXTURE_STEP'}
    $fixture=Join-Path $root 'fixture';New-Item -ItemType Directory -Path $fixture|Out-Null
    foreach($directory in @('scripts/verification','src/testmod','src/test/java/dev/quantumchamber/persistence','build/test-results/test','build/libs','run/gametest-m7/logs')){New-Item -ItemType Directory -Path (Join-Path $fixture $directory) -Force|Out-Null}
    foreach($name in @('m7-contract.ps1','m7-ci-gametest.ps1','m7-inventory.json','m6-ci-gametest.ps1','gates.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fixture 'scripts/verification')}
    Copy-Item -LiteralPath $SourceRoot -Destination (Join-Path $fixture 'src/testmod/java') -Recurse
    foreach($name in @('M7BudgetSpikeTest.java','M7PublishSpikeTest.java')){Copy-Item -LiteralPath (Join-Path $repo "src/test/java/dev/quantumchamber/persistence/$name") -Destination (Join-Path $fixture 'src/test/java/dev/quantumchamber/persistence')}
    Copy-Item -Path (Join-Path $JUnitDir 'TEST-dev.quantumchamber.persistence.M7*.xml') -Destination (Join-Path $fixture 'build/test-results/test')
    Copy-Item -Path (Join-Path $repo 'build/libs/*.jar') -Destination (Join-Path $fixture 'build/libs')
    Copy-Item -LiteralPath $M7RuntimeLog -Destination (Join-Path $fixture 'run/gametest-m7/logs/latest.log')
    Copy-Item -LiteralPath (Join-Path (Split-Path (Split-Path $M7RuntimeLog)) 'm7-gametest-runtime.json') -Destination (Join-Path $fixture 'run/gametest-m7')
    [xml]$m7=Get-Content -LiteralPath $M7GameTestXml -Raw
    if($Mutation -cne 'none'){
        $node=$m7.SelectSingleNode("//testcase[@name='$MutationTarget']");if($null -eq $node){throw 'M7_FIXTURE_TARGET'}
        switch($Mutation){'drop'{$null=$node.ParentNode.RemoveChild($node)};'skip'{$null=$node.AppendChild($m7.CreateElement('skipped'))};'fail'{$null=$node.AppendChild($m7.CreateElement('failure'))};'wrong-scope'{$node.SetAttribute('name','m6universegametests.wrong_scope')}}
    }
    $m7.Save((Join-Path $fixture 'build/gametest-m7-results.xml'))
    if($SourceMutation -cne 'none'){
        $file=Join-Path $fixture 'src/testmod/java/dev/quantumchamber/gametest/M7HomeSpikeGameTests.java';$text=Get-Content -LiteralPath $file -Raw
        $needle="public void $SourceTarget(TestContext";if(-not $text.Contains($needle)){throw 'M7_FIXTURE_SOURCE_TARGET'}
        $replacement=if($SourceMutation -ceq 'interpose-annotation'){"@SuppressWarnings(`"unused`")`n    $needle"}else{"public void ${SourceTarget}_windows(TestContext"}
        $text.Replace($needle,$replacement)|Set-Content -LiteralPath $file -Encoding utf8
    }
    $m7Output=Join-Path $fixture '.superpowers/m7-own';$m7Raw=Join-Path $m7Output 'raw';New-Item -ItemType Directory -Path (Join-Path $m7Raw 'junit'),(Join-Path $m7Raw 'runtime/logs'),(Join-Path $m7Raw 'artifacts') -Force|Out-Null
    Copy-Item -LiteralPath (Join-Path $fixture 'build/gametest-m7-results.xml') -Destination (Join-Path $m7Raw 'gametest.xml');Copy-Item -Path (Join-Path $fixture 'build/test-results/test/TEST-dev.quantumchamber.persistence.M7*.xml') -Destination (Join-Path $m7Raw 'junit');Copy-Item -LiteralPath (Join-Path $fixture 'run/gametest-m7/logs/latest.log') -Destination (Join-Path $m7Raw 'runtime/logs/latest.log');Copy-Item -LiteralPath (Join-Path $fixture 'run/gametest-m7/m7-gametest-runtime.json') -Destination (Join-Path $m7Raw 'runtime');Copy-Item -Path (Join-Path $fixture 'build/libs/*.jar') -Destination (Join-Path $m7Raw 'artifacts')
    @(Get-ChildItem $m7Raw -Recurse -File|ForEach-Object {@{path=$_.FullName;sha256=(Get-FileHash $_.FullName).Hash}})|ConvertTo-Json -Depth 5|Set-Content (Join-Path $m7Output 'raw-files.json') -Encoding utf8
    $originalSummary=Join-Path (Split-Path (Split-Path ([IO.Path]::GetFullPath($M7GameTestXml)))) 'summary.json';Copy-Item -LiteralPath $originalSummary -Destination (Join-Path $m7Output 'summary.json')
    $runner=Join-Path $root 'step.ps1';[IO.File]::WriteAllText($runner,"`$ErrorActionPreference='Stop'`n"+$script+"`nif(`$LASTEXITCODE -and `$LASTEXITCODE -ne 0){exit `$LASTEXITCODE}")
    $before=$env:M7_PLATFORM;$beforeHead=$env:GITHUB_SHA
    try{
        $env:M7_PLATFORM=$M7Platform;$env:GITHUB_SHA=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();Push-Location $fixture
        try{$output=(& pwsh -NoProfile -File $runner 2>&1|Out-String);$code=$LASTEXITCODE}finally{Pop-Location}
    }finally{$env:M7_PLATFORM=$before;$env:GITHUB_SHA=$beforeHead}
    $verdict=if($code -eq 0){'PASS'}else{'FAIL'}
    [ordered]@{schema=1;fixture=$true;platform=$M7Platform;head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();workflowSha256=(Get-FileHash -LiteralPath $Workflow).Hash;step=$Step;mutation=$Mutation;sourceMutation=$SourceMutation;verdict=$verdict;exitCode=$code;output=$output;m7GameTestXmlSha256=(Get-FileHash -LiteralPath $M7GameTestXml).Hash}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
    "M7_CI_FIXTURE verdict=$verdict root=$root";return
}
$fixture=Join-Path $root 'fixture'
New-Item -ItemType Directory -Path (Join-Path $fixture 'build/test-results/test') -Force | Out-Null
Copy-Item -Path (Join-Path $JUnitDir 'TEST-*.xml') -Destination (Join-Path $fixture 'build/test-results/test')
New-Item -ItemType Directory -Path (Join-Path $fixture 'src/testmod') -Force | Out-Null
Copy-Item -LiteralPath $SourceRoot -Destination (Join-Path $fixture 'src/testmod/java') -Recurse
[xml]$xml=Get-Content -LiteralPath $GameTestXml -Raw
[xml]$m5Xml=Get-Content -LiteralPath $M5GameTestXml -Raw
$normalXml=$null
if($M4NormalGameTestXml){[xml]$normalXml=Get-Content -LiteralPath $M4NormalGameTestXml -Raw}
$m6Xml=$null
if($M6GameTestXml){
    [xml]$m6Xml=Get-Content -LiteralPath $M6GameTestXml -Raw
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts/verification'),(Join-Path $fixture 'run/gametest-m6/logs'),(Join-Path $fixture 'build/libs') -Force | Out-Null
    foreach($scriptName in @('gates.ps1','m6-ci-gametest.ps1','m6-ci-main-only.ps1')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $scriptName) -Destination (Join-Path $fixture 'scripts/verification')}
    Copy-Item -LiteralPath $M6RuntimeLog -Destination (Join-Path $fixture 'run/gametest-m6/logs/latest.log')
    foreach($artifact in $M6Artifacts){Copy-Item -LiteralPath $artifact -Destination (Join-Path $fixture 'build/libs')}
    if($Step -ceq '確認 Ubuntu M6 精確集合與隔離'){
        # 僅本機workflow規則fixture：literal合法排除；不是實際Ubuntu執行證據。
        New-Item -ItemType Directory -Path (Join-Path $fixture 'build/m6-verification') -Force | Out-Null
        [ordered]@{head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();platform='Ubuntu';actualPlatform='Ubuntu';status='UNSUPPORTED';reason='M6_NATIVE_WINDOWS_REQUIRED';supported=$false;nativeExecuted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixture 'build/m6-verification/unsupported-native.json') -Encoding utf8
    }
    foreach($boundary in @(Get-ChildItem -LiteralPath $M6RuntimeRoot -Filter 'm4-boundary-*.json' -File)){Copy-Item -LiteralPath $boundary.FullName -Destination (Join-Path $fixture 'run/gametest-m6')}
}
# 正式full輸入必須由同runRoot的唯一gate/本次XML推回log；離線跨root原件須明示fixture。
. (Join-Path $PSScriptRoot 'gate-evidence.ps1')
$presetSources=@()
New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts/verification') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'gate-evidence.ps1') -Destination (Join-Path $fixture 'scripts/verification')
function Write-PresetPiece([string]$Path,[string]$Text) {
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes($Text)
    if($Path.EndsWith('.gz',[StringComparison]::Ordinal)) {
        $output=[IO.File]::Create($Path); $gzip=[IO.Compression.GZipStream]::new($output,[IO.Compression.CompressionMode]::Compress)
        try {$gzip.Write($bytes,0,$bytes.Length)} finally {$gzip.Dispose();$output.Dispose()}
    } else {[IO.File]::WriteAllBytes($Path,$bytes)}
}
if($Step -cin @('確認 Windows 原生案例確實執行','確認非 Windows 跨平台案例確實執行')) {
    $presetScopes=if($Step -ceq '確認 Windows 原生案例確實執行'){@('default','legacy','normal','m5','m6')}else{@('default','legacy','m5','m6')}
    if($PresetMutation -ne 'none' -and $PresetMutationScope -cnotin $presetScopes){throw 'M4_PRESET_INPUT：mutation scope未在此平台執行'}
    $defaultGate=Split-Path (Split-Path (Resolve-Path -LiteralPath $GameTestXml).Path)
    $sameRun=Split-Path $defaultGate
    foreach($presetScope in $presetScopes) {
        $runtimeLog=switch($presetScope){'default'{$DefaultRuntimeLog};'legacy'{$LegacyRuntimeLog};'normal'{$M4NormalRuntimeLog};'m5'{$M5RuntimeLog};'m6'{$M6RuntimeLog}}
        $xmlInput=switch($presetScope){'default'{$GameTestXml};'normal'{$M4NormalGameTestXml};'m5'{$M5GameTestXml};default{''}}
        $gateRoot='';$xmlHash='';$sourceHead='';$selectedPieces=@()
        if(-not $PresetFixture) {
            if($runtimeLog){throw 'M4_PRESET_INPUT：跨root顯式log必須標示PresetFixture'}
            if($xmlInput){$xmlInput=(Resolve-Path -LiteralPath $xmlInput).Path;$gateRoot=Split-Path (Split-Path $xmlInput)}
            else {
                $prefix=if($presetScope -ceq 'legacy'){'gate-legacy-'}else{'gate-m6-gametest-'}
                $matches=@(Get-ChildItem -LiteralPath $sameRun -Directory | Where-Object {$_.Name -cmatch ('^'+$prefix+'[0-9a-f]{32}$')})
                if($matches.Count -ne 1){throw "M4_PRESET_INPUT：$presetScope 必須同runRoot唯一gate"}
                $gateRoot=$matches[0].FullName
                $xmlInput=Join-Path $gateRoot $(if($presetScope -ceq 'legacy'){'result-build/gametest-legacy-results.xml'}else{'result-build/gametest-m6-results.xml'})
            }
            if((Split-Path $gateRoot) -cne $sameRun){throw 'M4_PRESET_INPUT：gate不屬於同runRoot'}
            $gateReceipt=Get-Content -LiteralPath (Join-Path $gateRoot 'gate.json') -Raw|ConvertFrom-Json
            $scopeReceipt=Get-Content -LiteralPath (Join-Path $gateRoot 'scope.json') -Raw|ConvertFrom-Json
            $execution=Get-Content -LiteralPath (Join-Path $gateRoot 'execution/execution.json') -Raw|ConvertFrom-Json -DateKind String
            if($gateReceipt.evidence -cne $gateRoot -or $scopeReceipt.xml -cne $xmlInput){throw 'M4_PRESET_INPUT：receipt不是exact gate/XML路徑'}
            $sourceHead=$scopeReceipt.head
            if($gateReceipt.status -cne 'PASS' -or $scopeReceipt.status -cne 'PASS' -or $scopeReceipt.scope -cne $presetScope -or $sourceHead -cne (git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim() -or $execution.exitCode -ne 0){throw 'M4_PRESET_INPUT：gate/scope/HEAD/exit不符'}
            $xmlTime=(Get-Item -LiteralPath $xmlInput).LastWriteTimeUtc
            if($xmlTime -lt ([DateTimeOffset]$execution.start).UtcDateTime -or $xmlTime -gt ([DateTimeOffset]$execution.end).UtcDateTime){throw 'M4_PRESET_INPUT：XML不是當次execution原件'}
            $xmlHash=(Get-FileHash -LiteralPath $xmlInput).Hash
            $runtimeLog=Join-Path $gateRoot 'execution/runtime-logs/latest.log'
            $null=Read-GateRuntimeLog -Evidence (Join-Path $gateRoot 'execution')
            $logManifest=Get-Content -LiteralPath (Join-Path $gateRoot 'execution/runtime-log.json') -Raw|ConvertFrom-Json -DateKind String
            $selectedPieces=@($logManifest.pieces | Where-Object {$_.selected} | ForEach-Object {Join-Path (Join-Path $gateRoot 'execution') $_.path})
            $latest=@($logManifest.pieces | Where-Object {$_.name -ceq 'latest.log' -and $_.selected})
            if($latest.Count -ne 1 -or $logManifest.identity.start -cne $execution.start -or $logManifest.identity.end -cne $execution.end -or $logManifest.identity.launcherPid -ne $execution.launcherPid -or $latest[0].sha256 -cne (Get-FileHash -LiteralPath $runtimeLog).Hash){throw 'M4_PRESET_INPUT：log不是同execution的已核原件'}

        } elseif(-not $runtimeLog){throw "M4_PRESET_INPUT：fixture缺少 $presetScope log原件"}
        $runtimeLog=(Resolve-Path -LiteralPath $runtimeLog).Path
        $sourceHash=(Get-FileHash -LiteralPath $runtimeLog).Hash
        $runName=switch($presetScope){'default'{'gametest'};'normal'{'gametest-m4-normal'};'legacy'{'gametest-legacy'};'m5'{'gametest-m5'};'m6'{'gametest-m6'}}
        $dest=Join-Path $fixture "run/$runName/logs/latest.log"
        New-Item -ItemType Directory -Path (Split-Path $dest) -Force|Out-Null
        if($PresetFixture){$selectedPieces=@($runtimeLog)}
        $fixturePieces=@()
        foreach($pieceSource in $selectedPieces) {
            $pieceSource=(Resolve-Path -LiteralPath $pieceSource).Path
            $pieceDest=Join-Path (Split-Path $dest) ([IO.Path]::GetFileName($pieceSource))
            $pieceHash=(Get-FileHash -LiteralPath $pieceSource).Hash
            Copy-Item -LiteralPath $pieceSource -Destination $pieceDest
            $fixturePieces+=$pieceDest
            $presetSources+=[pscustomobject]@{scope=$presetScope;source=$pieceSource;sha256=$pieceHash;gate=$gateRoot;xml=$xmlInput;xmlSha256=$xmlHash;head=$sourceHead;fixture=[bool]$PresetFixture}
        }
        if($PresetMutation -ne 'none' -and $PresetMutationScope -ceq $presetScope){
            $pattern='M4_TEST_PRESET defaultNormal=(true|false) normalScope=(true|false) m5Only=(true|false) m5Normal=(true|false) flatConfig=(true|false) normalConfig=(true|false)'
            $owners=@();$strictTotal=0;$looseTotal=0
            foreach($piece in $fixturePieces) {
                $pieceText=[Text.UTF8Encoding]::new($false,$true).GetString((Read-GateLogBytes $piece))
                $count=[regex]::Matches($pieceText,$pattern).Count
                $strictTotal+=$count;$looseTotal+=[regex]::Matches($pieceText,'M4_TEST_PRESET(?:\s|$)').Count
                if($count -gt 0){$owners+=$piece}
            }
            if($strictTotal -ne 1 -or $looseTotal -ne 1 -or $owners.Count -ne 1){throw 'M4_PRESET_INPUT：mutation前selected pieces marker必須恰一'}
            $dest=$owners[0];$text=[Text.UTF8Encoding]::new($false,$true).GetString((Read-GateLogBytes $dest))
            $marker=[regex]::Match($text,$pattern).Value
            $logs=Split-Path $dest;$latestDest=Join-Path $logs 'latest.log'
            $rolloverDest=Join-Path $logs '2026-11-06-2147483647.log.gz'
            if(Test-Path -LiteralPath $rolloverDest){throw 'M4_PRESET_INPUT：受控rollover名稱已存在'}
            switch($PresetMutation){
                'missing-marker' {$text=[regex]::Replace($text,$pattern,'REMOVED_PRESET_MARKER')}
                'rollover' {Write-PresetPiece $rolloverDest ($marker+"`n");$text=$text.Replace($marker,'ROLLED_PRESET')}
                'duplicate' {Write-PresetPiece $rolloverDest ($marker+"`n")}
                'split-marker' {Write-PresetPiece $rolloverDest 'M4_TEST_PRESET ';$text=$text.Replace($marker,$marker.Substring('M4_TEST_PRESET '.Length))}
                'format-drift' {$text=$text.Replace('M4_TEST_PRESET defaultNormal=','M4_TEST_PRESET  defaultNormal=')}
                {$_ -cin @('missing-one','middle-truncated','crc','isize','flags','extra-tail','duplicate-trailer','second-member','compressed-limit','expanded-limit','original-truncated')} {
                    Write-PresetPiece $rolloverDest 'framing fixture'
                    $raw=[IO.File]::ReadAllBytes($rolloverDest)
                    switch($PresetMutation) {
                        'missing-one' {$raw=$raw[0..($raw.Length-2)]}
                        'middle-truncated' {$raw=$raw[0..([int]($raw.Length/2))]}
                        'crc' {$raw[$raw.Length-8]=$raw[$raw.Length-8] -bxor 1}
                        'isize' {$raw[$raw.Length-4]=$raw[$raw.Length-4] -bxor 1}
                        'flags' {$raw[3]=1}
                        'extra-tail' {$raw=$raw+[byte[]]@(1)}
                        'duplicate-trailer' {$raw=$raw+$raw[($raw.Length-8)..($raw.Length-1)]}
                        'second-member' {$raw=$raw+$raw}
                        'original-truncated' {$raw=[Convert]::FromHexString('1F8B080000000000000A4B4A4DCB2F4AD5CDCD4CC9CB4CCF280100')}
                    }
                    [IO.File]::WriteAllBytes($rolloverDest,[byte[]]$raw)
                    if($PresetMutation -ceq 'compressed-limit') {$stream=[IO.File]::OpenWrite($rolloverDest);try{$stream.SetLength(16*1024*1024+1)}finally{$stream.Dispose()}}
                    if($PresetMutation -ceq 'expanded-limit') {
                        $stream=[IO.File]::Create($rolloverDest);$gzip=[IO.Compression.GZipStream]::new($stream,[IO.Compression.CompressionMode]::Compress)
                        try {$chunk=[byte[]]::new(8192);for($i=0;$i -lt 8192;$i++){$gzip.Write($chunk)};$gzip.WriteByte(0)}finally{$gzip.Dispose();$stream.Dispose()}
                    }
                }
                'truncated-gzip' {Write-PresetPiece $rolloverDest 'before-midnight';$raw=[IO.File]::ReadAllBytes($rolloverDest);[IO.File]::WriteAllBytes($rolloverDest,$raw[0..($raw.Length-9)])}
                'bad-gzip' {[IO.File]::WriteAllBytes($rolloverDest,[byte[]]@(1,2,3,4))}
                'wrong-name' {Write-PresetPiece (Join-Path $logs 'wrong.log.gz') ($marker+"`n");$text=$text.Replace($marker,'REMOVED_PRESET')}
                'bad-date' {Write-PresetPiece (Join-Path $logs '2026-99-99-1.log.gz') ($marker+"`n");$text=$text.Replace($marker,'REMOVED_PRESET')}
                'unexpected-log' {Write-PresetPiece (Join-Path $logs 'other.log') 'unexpected'}
                'cross-root' {Write-PresetPiece (Join-Path $fixture 'foreign-root.log') ($marker+"`n");$text=$text.Replace($marker,'REMOVED_PRESET')}
                'old-piece' {Write-PresetPiece (Join-Path $logs '2000-01-01-1.log.gz') ($marker+"`n")}

                'wrong-flag' {$oldFlag=[regex]::Match($text,'M4_TEST_PRESET defaultNormal=(true|false)').Groups[1].Value;$newFlag=if($oldFlag -ceq 'true'){'false'}else{'true'};$text=$text.Replace("M4_TEST_PRESET defaultNormal=$oldFlag","M4_TEST_PRESET defaultNormal=$newFlag")}
                'wrong-m5-only' {$oldFlag=[regex]::Match($text,'m5Only=(true|false)').Groups[1].Value;$newFlag=if($oldFlag -ceq 'true'){'false'}else{'true'};$text=$text.Replace("m5Only=$oldFlag","m5Only=$newFlag")}
                'wrong-m5-normal' {$oldFlag=[regex]::Match($text,'m5Normal=(true|false)').Groups[1].Value;$newFlag=if($oldFlag -ceq 'true'){'false'}else{'true'};$text=$text.Replace("m5Normal=$oldFlag","m5Normal=$newFlag")}
                'wrong-config' {$oldConfig=[regex]::Match($text,'flatConfig=(true|false) normalConfig=(true|false)').Value;$newConfig=if($oldConfig -ceq 'flatConfig=false normalConfig=true'){'flatConfig=true normalConfig=false'}else{'flatConfig=false normalConfig=true'};$text=$text.Replace($oldConfig,$newConfig)}
            }
            Write-PresetPiece $dest $text
        }

    }
}
if($Step -cin @('非 Windows GameTest 平台契約 日誌空目錄守門','Windows 原生建置與 GameTest 日誌空目錄守門') -and $PresetMutation -ceq 'old-piece') {
    $oldRoot=Join-Path $fixture 'run/gametest-m5/logs'
    New-Item -ItemType Directory -Path $oldRoot -Force | Out-Null
    Write-PresetPiece (Join-Path $oldRoot '2000-01-01-1.log.gz') '舊 invocation 日誌'
}
foreach($source in $presetSources){if((Get-FileHash -LiteralPath $source.source).Hash -cne $source.sha256){throw 'M4_PRESET_INPUT：原件被變更'}}
$mainOriginalHash=''
if($M6NativeSummary) {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts/verification') -Force | Out-Null
    foreach($name in @('m6-universe-probe.ps1','probe-common.ps1')) {Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fixture "scripts/verification/$name")}
    $native=Get-Content -LiteralPath $M6NativeSummary -Raw | ConvertFrom-Json -DateKind String
    switch($NativeMutation) {
        'mode' {$native.enabledManifest[0].expectedMode='FUNCTIONAL'}
        'schema' {$native.enabledManifest[0].m6ProbeSchema=2}
        'chain' {$native.enabledManifest[0].chain='WRONG'}
        'phase' {$native.phases[0].phase='WRONG'}
        'hash' {$native.phases[0].receiptSha256='0'*64}
        'receipt' {
            $changed=Get-Content -LiteralPath $native.phases[0].receipt -Raw | ConvertFrom-Json -DateKind String;$changed.status='FAIL'
            $path=Join-Path $fixture 'build/native-mutated-receipt.json';$changed | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path
            $native.phases[0].receipt=$path;$native.phases[0].receiptSha256=(Get-FileHash -LiteralPath $path).Hash
        }
        'restore' {
            $changed=Get-Content -LiteralPath $native.chains[0].preservation -Raw | ConvertFrom-Json -DateKind String;$changed.retainedRoot=$false
            $path=Join-Path $fixture 'build/native-mutated-preservation.json';$changed | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $path
            $native.chains[0].preservation=$path;$native.chains[0].preservationSha256=(Get-FileHash -LiteralPath $path).Hash
        }
    }
    $native | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $fixture 'build/m6-native-summary.json') -Encoding utf8
}
if($MainReceipt){
    & (Join-Path $PSScriptRoot 'm6-ci-main-only.ps1') -VerifyOnly -Receipt $MainReceipt -ExpectedHead (git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim() | Out-Null
    $mainOriginalHash=(Get-FileHash -LiteralPath $MainReceipt).Hash
    $main=Get-Content -LiteralPath $MainReceipt -Raw | ConvertFrom-Json -DateKind String
    $originalBundle=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($MainReceipt))
    $mainBundle=Join-Path $fixture 'build/m6-main-only';New-Item -ItemType Directory -Path $mainBundle | Out-Null
    foreach($file in $main.files){
        $destination=Join-Path $mainBundle $file.path
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $originalBundle $file.path) -Destination $destination
    }
    function Main-Path([string]$Kind){$f=@($main.files | Where-Object kind -ceq $Kind);if($f.Count -ne 1){throw 'Main fixture引用不唯一'};return Join-Path $mainBundle $f[0].path}
    switch($MainMutation){
        'wrong-head' {$main.headBefore='0000000000000000000000000000000000000000';$main.headAfter=$main.headBefore}
        'gametest' {$p=Main-Path 'gate';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.mode='M6GameTest';$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'testmod' {$p=Main-Path 'oracle';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.layer2RuntimePathHits=@('build/classes/java/testmod');$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'stop' {$p=Main-Path 'execution';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.stdinStop=$false;$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'restore' {$p=Main-Path 'restore';$v=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json -DateKind String;$v.exactRestored=$false;$v | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $p -Encoding utf8}
        'missing-evidence' {Move-Item -LiteralPath (Main-Path 'classload') -Destination (Join-Path $mainBundle 'preserved-classload.log')}
    }
    # 突變後重算外層hash，確認語意守門確實拒絕，不能只靠舊hash碰巧失敗。
    foreach($f in $main.files){$p=Join-Path $mainBundle $f.path;if(Test-Path -LiteralPath $p -PathType Leaf){$f.sha256=(Get-FileHash -LiteralPath $p).Hash}}
    $main | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $mainBundle 'main-ci-receipt.json') -Encoding utf8
    if($MainMutation -eq 'missing-receipt'){Move-Item -LiteralPath (Join-Path $mainBundle 'main-ci-receipt.json') -Destination (Join-Path $mainBundle 'preserved-receipt.json')}
}elseif($MainMutation -ne 'none'){throw 'MainMutation須有有效MainReceipt作為原件'}
$sourceMutated=$null
if($SourceMutation -ne 'none'){
    if($SourceTarget -notmatch '^\w+$'){throw "SourceTarget 必須是方法名稱：$SourceTarget"}
    $needle="public void $SourceTarget(TestContext"
    $hits=@(Get-ChildItem -LiteralPath (Join-Path $fixture 'src/testmod/java') -Recurse -File -Filter '*.java' | Where-Object {(Get-Content -LiteralPath $_.FullName -Raw).Contains($needle)})
    if($hits.Count -ne 1){throw "source mutation 目標必須恰出現在一個檔案：$SourceTarget（$($hits.Count)）"}
    $text=Get-Content -LiteralPath $hits[0].FullName -Raw
    if(([regex]::Matches($text,[regex]::Escape($needle))).Count -ne 1){throw "source mutation 目標在檔案內不唯一：$SourceTarget"}
    if($SourceMutation -eq 'interpose-annotation'){
        # interpose-annotation：@GameTest(...) 與方法宣告之間插入另一個 annotation（合法 Java，GameTest 仍會註冊）。
        $text=$text.Replace($needle,"@SuppressWarnings(`"unused`")`n    $needle")
    } else {
        # rename-windows：方法改名為 <名稱>_windows，兩份 XML 中對應的 testcase（恰一個）一起改名，模擬一個實際通過的 _windows 案例。
        if($SourceTarget -like '*_windows'){throw "rename-windows 的目標不可已是 _windows：$SourceTarget"}
        $text=$text.Replace($needle,"public void ${SourceTarget}_windows(TestContext")
        $caseName=$hits[0].BaseName.ToLowerInvariant()+'.'+$SourceTarget
        $cases=@(@($xml,$normalXml,$m5Xml,$m6Xml) | Where-Object {$null -ne $_} | ForEach-Object {$_.SelectNodes("//testcase[@name='$caseName']")} | Where-Object {$null -ne $_})
        if($cases.Count -ne 1){throw "rename-windows 的 testcase 必須恰出現一次：$caseName（$($cases.Count)）"}
        $cases[0].SetAttribute('name',$caseName+'_windows')
    }
    [IO.File]::WriteAllText($hits[0].FullName,$text,[Text.UTF8Encoding]::new($false))
    $sourceMutated=[IO.Path]::GetRelativePath($fixture,$hits[0].FullName)
}
if($Mutation -ne 'none'){
    $target=if($MutationXml -eq 'normal'){$normalXml}elseif($MutationXml -eq 'm6'){$m6Xml}elseif($MutationXml -eq 'm5'){$m5Xml}else{$xml}
    $node=$target.SelectSingleNode("//testcase[@name='$MutationTarget']")
    if($null -eq $node){throw "mutation 目標不在 $MutationXml XML：$MutationTarget"}
    switch($Mutation){
        'wrong-scope' { if($MutationXml -ne 'normal'){throw 'wrong-scope 僅供 normal'}; [void]$xml.DocumentElement.AppendChild($xml.ImportNode($node,$true)); [void]$node.ParentNode.RemoveChild($node) }
        'drop' {[void]$node.ParentNode.RemoveChild($node)}
        'skip' {[void]$node.AppendChild($target.CreateElement('skipped'))}
        'fail' {$failure=$target.CreateElement('failure');$failure.SetAttribute('message','CI 規則檢查刻意注入的失敗');[void]$node.AppendChild($failure)}
    }
}
$xml.Save((Join-Path $fixture 'build/gametest-results.xml'))
$m5Xml.Save((Join-Path $fixture 'build/gametest-m5-results.xml'))
if($normalXml){$normalXml.Save((Join-Path $fixture 'build/gametest-m4-normal-results.xml'))}
if($m6Xml){$m6Xml.Save((Join-Path $fixture 'build/gametest-m6-results.xml'))}
$stepFile=Join-Path $root 'step.ps1'
Set-Content -LiteralPath $stepFile -Value ("`$ErrorActionPreference = 'stop'`n"+$script) -Encoding utf8
$info=[Diagnostics.ProcessStartInfo]::new('pwsh')
foreach($argument in @('-NoProfile','-NonInteractive','-Command',". '$($stepFile.Replace("'","''"))'")){$info.ArgumentList.Add($argument)}
$info.Environment['GITHUB_SHA']=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim()
$info.WorkingDirectory=$fixture;$info.UseShellExecute=$false;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$process=[Diagnostics.Process]::Start($info)
$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEnd();$process.WaitForExit()
foreach($source in $presetSources){if((Get-FileHash -LiteralPath $source.source).Hash -cne $source.sha256){throw 'M4_PRESET_INPUT：執行後原件被變更'}}
$output=$stdout.Result+$stderr
Set-Content -LiteralPath (Join-Path $root 'output.log') -Value $output -Encoding utf8
$windowsCases=@($xml.SelectNodes('//testcase') | Where-Object {$_.name -like '*_windows'}).Count
$m5WindowsCases=@($m5Xml.SelectNodes('//testcase') | Where-Object {$_.name -like '*_windows'}).Count
$result=[ordered]@{label=$Label;workflow=(Resolve-Path -LiteralPath $Workflow).Path;workflowSha256=(Get-FileHash -LiteralPath $Workflow -Algorithm SHA256).Hash;step=$Step
    gameTestXml=(Resolve-Path -LiteralPath $GameTestXml).Path;gameTestXmlSha256=(Get-FileHash -LiteralPath $GameTestXml -Algorithm SHA256).Hash
    m5GameTestXml=(Resolve-Path -LiteralPath $M5GameTestXml).Path;m5GameTestXmlSha256=(Get-FileHash -LiteralPath $M5GameTestXml -Algorithm SHA256).Hash
    m4NormalGameTestXmlSha256=$(if($M4NormalGameTestXml){(Get-FileHash -LiteralPath $M4NormalGameTestXml).Hash}else{''})
    head=(git -c "safe.directory=$repo" -C $repo rev-parse HEAD).Trim();m6GameTestXmlSha256=$(if($M6GameTestXml){(Get-FileHash -LiteralPath $M6GameTestXml).Hash}else{''});m6RuntimeLogSha256=$(if($M6RuntimeLog){(Get-FileHash -LiteralPath $M6RuntimeLog).Hash}else{''})
    mainReceipt=$MainReceipt;mainReceiptSha256=$mainOriginalHash;mainMutation=$MainMutation;mainFixtureRuntimeLaunched=$false
    nativeSummary=$M6NativeSummary;nativeSummarySha256=$(if($M6NativeSummary){(Get-FileHash -LiteralPath $M6NativeSummary).Hash}else{''});nativeMutation=$NativeMutation;nativeFixtureRuntimeLaunched=$false
    presetFixture=[bool]$PresetFixture;presetMutation=$PresetMutation;presetMutationScope=$PresetMutationScope;presetSources=$presetSources
    junitDir=$JUnitDir;sourceRoot=$SourceRoot;mutation=$Mutation;mutationTarget=$MutationTarget;mutationXml=$MutationXml;sourceMutation=$SourceMutation;sourceTarget=$SourceTarget;sourceMutatedFile=$sourceMutated
    fixtureWindowsCases=$windowsCases;fixtureM5WindowsCases=$m5WindowsCases;exitCode=$process.ExitCode;verdict=$(if($process.ExitCode -eq 0){'PASS'}else{'FAIL'});output=$output.Trim();time=[DateTimeOffset]::Now.ToString('o')}
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'result.json') -Encoding utf8
Write-Output "CI_RULE label=$Label step=$Step mutation=$Mutation target=$MutationTarget mutationXml=$MutationXml sourceMutation=$SourceMutation sourceTarget=$SourceTarget exit=$($process.ExitCode) root=$root"
