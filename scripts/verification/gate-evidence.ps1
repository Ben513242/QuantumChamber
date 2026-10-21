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
