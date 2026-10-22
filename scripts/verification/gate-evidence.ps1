# gate-evidence.ps1：gate 的唯讀身分與取證 helper；載入只定義函式，不啟動程序或操作世界。
# 所有執行身分／路徑由 caller 傳入，不依賴呼叫端的全域變數。
function Get-GateBoundaryReceipt {
    param([Parameter(Mandatory)][string]$Root,[Parameter(Mandatory)][AllowEmptyCollection()][object[]]$Processes)
    foreach($file in @(Get-ChildItem -LiteralPath $Root -Filter 'm4-boundary-*.json' -File -ErrorAction SilentlyContinue)) {
        $receipt=$null; $delta=$null; $reason=''; $matched=$false
        try {
            $receipt=Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -DateKind String
            $fields=@($receipt.PSObject.Properties.Name)
            if(@('pid','startupNonce','startTimeUtc','runtimeScope') | Where-Object {$_ -cnotin $fields}){throw 'missing-identity'}
            if([string]$receipt.pid -cnotmatch '^[1-9][0-9]*$' -or $receipt.runtimeScope -cne 'testmod-present'){throw 'invalid-identity'}
            if([string]$receipt.startupNonce -cnotmatch '^[0-9a-f]{32}$' -or $file.Name -cne ('m4-boundary-'+$receipt.startupNonce+'.json')){throw 'filename-nonce-mismatch'}
            $owners=@($Processes | Where-Object {$_.pid -eq $receipt.pid})
            if($owners.Count -ne 1){throw 'process-not-observed-once'}
            $receiptTime=[DateTimeOffset]::MinValue; $processTime=[DateTimeOffset]::MinValue
            foreach($value in @([string]$receipt.startTimeUtc,[string]$owners[0].startTimeUtc)) {
                if($value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,7})?(Z|\+00:00)$'){throw 'invalid-utc-start-time'}
            }
            if(-not [DateTimeOffset]::TryParse($receipt.startTimeUtc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$receiptTime) -or
                -not [DateTimeOffset]::TryParse($owners[0].startTimeUtc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::None,[ref]$processTime)){throw 'invalid-utc-start-time'}
            # Java ProcessHandle 的 Windows startInstant 以毫秒表示，.NET StartTime 保留更細 FILETIME；只容許截斷造成的小於 1 ms 差異。
            $delta=[Math]::Abs(($receiptTime-$processTime).TotalMilliseconds)
            if($delta -ge 1){throw 'process-start-time-mismatch'}
            $matched=$true; $reason='pid-start-time-filename-nonce-matched'
        } catch { $reason=$_.Exception.Message }
        [pscustomobject]@{path=$file.FullName;matched=$matched;reason=$reason;startDeltaMilliseconds=$delta;toleranceMillisecondsExclusive=1;receipt=$receipt}
    }
}

# 只讀原 bytes；gzip 展開不以 timestamp 篩行，保留 stack trace 等沒有時間戳的行。
function Read-GateLogBytes([string]$Path) {
    $raw=[IO.File]::ReadAllBytes($Path)
    if($Path.EndsWith('.gz',[StringComparison]::OrdinalIgnoreCase)) {
        $input=[IO.MemoryStream]::new($raw);$output=[IO.MemoryStream]::new()
        $gzip=[IO.Compression.GZipStream]::new($input,[IO.Compression.CompressionMode]::Decompress)
        try {$gzip.CopyTo($output);return ,$output.ToArray()} finally {$gzip.Dispose();$input.Dispose();$output.Dispose()}
    }
    return ,$raw
}
function Get-GateLogSnapshot([string]$LogRoot) {
    foreach($file in @(Get-ChildItem -LiteralPath $LogRoot -File -ErrorAction SilentlyContinue | Sort-Object Name)) {
        if($file.Name -cne 'latest.log' -and $file.Name -cnotmatch '^\d{4}-\d{2}-\d{2}-\d+\.log\.gz$'){continue}
        $content=Read-GateLogBytes $file.FullName
        [pscustomobject]@{name=$file.Name;length=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash;contentSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($content));lastWriteTimeUtc=$file.LastWriteTimeUtc.ToString('o')}
    }
}
function Save-GateRuntimeLog {
    param([string]$Evidence,[AllowEmptyCollection()][object[]]$Before,[object]$Identity)
    $logRoot=Join-Path $Evidence 'runtime-logs'
    $after=@(Get-GateLogSnapshot $logRoot)
    $pieces=@();$order=0
    $sorted=@($after|Sort-Object @{Expression={if($_.name -ceq 'latest.log'){'9999-99-99'}else{$_.name.Substring(0,10)}}},@{Expression={if($_.name -ceq 'latest.log'){[int]::MaxValue}else{[int]([regex]::Match($_.name,'-(\d+)\.log\.gz$').Groups[1].Value)}}})
    $full=[IO.MemoryStream]::new()
    try {
        foreach($file in $sorted) {
            $prior=@($Before|Where-Object {$_.name -ceq $file.name})
            $selected=$false
            if($prior.Count -eq 1 -and $prior[0].sha256 -ceq $file.sha256){$reason='unchanged-before-run'}
            elseif(@($Before|Where-Object {$_.contentSha256 -ceq $file.contentSha256}).Count){$reason='pre-run-content-rotated'}
            else{$selected=$true;$reason='new-or-changed-content-during-invocation'}
            $pieceOrder=$null;if($selected){$pieceOrder=$order;$order+=1}
            $piece=[ordered]@{name=$file.name;path=('runtime-logs/'+$file.name);selected=$selected;reason=$reason;order=$pieceOrder;length=$file.length;sha256=$file.sha256;contentSha256=$file.contentSha256;lastWriteTimeUtc=$file.lastWriteTimeUtc;writeDeltaMilliseconds=([DateTimeOffset]::Parse($file.lastWriteTimeUtc)-[DateTimeOffset]::Parse($Identity.start)).TotalMilliseconds}
            $pieces+=[pscustomobject]$piece
            if($selected){$bytes=Read-GateLogBytes (Join-Path $logRoot $file.name);$full.Write($bytes,0,$bytes.Length)}
        }
        $bytes=$full.ToArray();[IO.File]::WriteAllBytes((Join-Path $Evidence 'runtime-full.log'),$bytes)
        $required=$Identity.runtimeLogMode -ceq 'minecraft' -and $Identity.status -ceq 'EXIT_ZERO'
        $currentLogStatus=if($Identity.status -cne 'EXIT_ZERO'){'not-applicable-invocation-failed'}elseif($Identity.runtimeLogMode -cne 'minecraft'){'not-applicable-'+$Identity.runtimeLogMode}elseif($pieces.Count -eq 0){'missing-log-files'}elseif($order -eq 0){'no-current-pieces'}elseif($bytes.Length -eq 0){'empty-current-pieces'}else{'nonempty-current-pieces'}
        $manifest=[ordered]@{schema=2;selectionBasis='pre-run snapshot content delta; filesystem UTC is diagnostic because file clock can precede precise wall-clock';identity=$Identity;before=$Before;pieces=$pieces;selectedCount=$order;currentLogRequired=$required;currentLogStatus=$currentLogStatus;encoding='UTF-8; original expanded bytes concatenated without line filtering';fullPath='runtime-full.log';fullLength=$bytes.Length;fullSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))}
        $manifest|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $Evidence 'runtime-log.json') -Encoding utf8
    } finally {$full.Dispose()}
}
function Read-GateRuntimeLog([string]$Evidence) {
    $manifest=Get-Content -LiteralPath (Join-Path $Evidence 'runtime-log.json') -Raw|ConvertFrom-Json -DateKind String
    # schema 1 只供已封存的歷史證據讀回；當次 caller 一律由 Save-GateRuntimeLog 寫 schema 2。
    if($manifest.schema -notin @(1,2) -or $manifest.fullPath -cne 'runtime-full.log'){throw '完整日誌 manifest schema/path 不符'}
    $expected=[IO.MemoryStream]::new();$order=0
    try {
        foreach($piece in $manifest.pieces) {
            if($piece.name -cnotmatch '^(latest\.log|\d{4}-\d{2}-\d{2}-\d+\.log\.gz)$' -or $piece.path -cne ('runtime-logs/'+$piece.name)){throw '完整日誌 piece path 不符'}
            $path=Join-Path $Evidence $piece.path
            if((Get-FileHash -LiteralPath $path).Hash -cne $piece.sha256 -or (Get-Item -LiteralPath $path).Length -ne $piece.length){throw "完整日誌原 piece SHA/大小不符：$($piece.name)"}
            $bytes=Read-GateLogBytes $path
            if([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)) -cne $piece.contentSha256){throw "完整日誌展開 SHA 不符：$($piece.name)"}
            if($piece.selected){if($piece.order -ne $order){throw '完整日誌 piece 順序不符'};$order+=1;$expected.Write($bytes,0,$bytes.Length)}
        }
        $bytes=[IO.File]::ReadAllBytes((Join-Path $Evidence $manifest.fullPath))
        $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
        if($order -ne $manifest.selectedCount -or $bytes.Length -ne $manifest.fullLength -or $hash -cne $manifest.fullSha256 -or $hash -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($expected.ToArray()))){throw '完整日誌輸出與原 pieces 不符'}
        if($manifest.schema -eq 2){
            if($manifest.identity.runtimeLogMode -cnotin @('minecraft','junit','compile-only')){throw '完整日誌 invocation 模式不符'}
            $required=$manifest.identity.runtimeLogMode -ceq 'minecraft' -and $manifest.identity.status -ceq 'EXIT_ZERO'
            $status=if($manifest.identity.status -cne 'EXIT_ZERO'){'not-applicable-invocation-failed'}elseif($manifest.identity.runtimeLogMode -cne 'minecraft'){'not-applicable-'+$manifest.identity.runtimeLogMode}elseif(@($manifest.pieces).Count -eq 0){'missing-log-files'}elseif($order -eq 0){'no-current-pieces'}elseif($bytes.Length -eq 0){'empty-current-pieces'}else{'nonempty-current-pieces'}
            if($manifest.currentLogRequired -isnot [bool] -or $manifest.currentLogRequired -ne $required -or $manifest.currentLogStatus -cne $status){throw '完整日誌 applicability/status 不符'}
            if($required -and ($order -eq 0 -or $bytes.Length -eq 0)){throw "成功 Minecraft invocation 缺少非空當次日誌：$status"}
        }
        return [Text.Encoding]::UTF8.GetString($bytes)
    } finally {$expected.Dispose()}
}
# XML 只辨識本次寫出及完整度；current-complete 不代表失敗 runtime 通過。
function Get-GateXmlSnapshot([string]$BuildRoot) {
    foreach($file in @(Get-ChildItem -LiteralPath $BuildRoot -Filter '*.xml' -File -Recurse -ErrorAction SilentlyContinue)) {
        $relative=[IO.Path]::GetRelativePath($BuildRoot,$file.FullName).Replace('\','/')
        if($relative -cnotmatch '^(gametest[^/]*\.xml|test-results/.*\.xml)$'){continue}
        [pscustomobject]@{path=$relative;length=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName).Hash;lastWriteTimeUtc=$file.LastWriteTimeUtc.ToString('o')}
    }
}
function Save-GateFailureArtifacts {
    param([string]$Evidence,[string]$BuildRoot,[AllowEmptyCollection()][object[]]$Before,[object]$Identity)
    $rows=@();$errors=@()
    foreach($file in @(Get-GateXmlSnapshot $BuildRoot)) {
        $row=[ordered]@{path=$file.path;sha256=$file.sha256;length=$file.length;lastWriteTimeUtc=$file.lastWriteTimeUtc;classification='stale';copied=$false;error=$null}
        try {
            $prior=@($Before|Where-Object {$_.path -ceq $file.path})
            $fresh=($prior.Count -eq 0 -or $prior[0].sha256 -cne $file.sha256 -or $prior[0].lastWriteTimeUtc -cne $file.lastWriteTimeUtc)
            if($fresh){$row.classification='current-complete'}
            try{[xml]$xml=Get-Content -LiteralPath (Join-Path $BuildRoot $file.path) -Raw;if(-not $xml.DocumentElement){throw 'missing document element'}}catch{$row.classification='incomplete'}
            $destination=Join-Path $Evidence ('failure-xml/'+$file.path);[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
            Copy-Item -LiteralPath (Join-Path $BuildRoot $file.path) -Destination $destination
            if((Get-FileHash -LiteralPath $destination).Hash -cne $file.sha256){throw 'XML copy SHA 不符'}
            $row.copied=$true
        } catch {$row.error=$_.Exception.Message;$errors+=$row.error}
        $rows+=[pscustomobject]$row
    }
    [ordered]@{runtimePassed=$false;identity=$Identity;xml=$rows;copyErrors=$errors;stdout='stdout.log';stderr='stderr.log'}|ConvertTo-Json -Depth 30|Set-Content -LiteralPath (Join-Path $Evidence 'failure-artifacts.json') -Encoding utf8
}
