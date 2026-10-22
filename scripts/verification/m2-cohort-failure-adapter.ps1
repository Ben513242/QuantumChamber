# 只產生 M2 具名 south cohort 的暫時 testmod bytes；不執行 Gradle、不寫 source。
#Requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('partial-exception','timeout-false','slow-old10','slow-new60','request-fault','witness-fault','late-crosswall')][string]$Scenario,
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Output,
    [Parameter(Mandatory)][string]$Report
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not(Test-Path -LiteralPath $Source -PathType Leaf)){throw "source不存在：$Source"}
if(Test-Path -LiteralPath $Output){throw "拒絕覆寫adapter輸出：$Output"}
if(Test-Path -LiteralPath $Report){throw "拒絕覆寫adapter報告：$Report"}
$bytes=[IO.File]::ReadAllBytes($Source)
$utf8=[Text.UTF8Encoding]::new($false,$true)
$raw=$utf8.GetString($bytes)
if($raw -notmatch '\r\n' -or $raw.Contains("`n") -and $raw.Replace("`r`n",'').Contains("`n")){throw 'Java source不是一致CRLF，拒絕暫時重編碼'}
function Replace-Once([string]$value,[string]$pattern,[string]$replacement) {
    $matches=[regex]::Matches($value,$pattern)
    if($matches.Count -ne 1){throw "adapter anchor應恰一處，實際$($matches.Count)：$pattern"}
    $replacement=$replacement.Replace("`r`n","`n").Replace("`n","`r`n")
    return [regex]::Replace($value,$pattern,[Text.RegularExpressions.MatchEvaluator]{param($m)$replacement})
}
function Replace-TextOnce([string]$value,[string]$old,[string]$replacement) {
    $old=$old.Replace("`n","`r`n")
    $replacement=$replacement.Replace("`n","`r`n")
    if($value.Split([string[]]@($old),[StringSplitOptions]::None).Count -ne 2){throw "adapter literal anchor不是唯一：$($old.Substring(0,[Math]::Min(60,$old.Length)))"}
    return $value.Replace($old,$replacement)
}
$condition='when\(fixture\.context,tick,\(\) -> fixture\.pages\.currentMappings\(sid\)\.epoch\(\)==index\+2\s*&& fixture\.record\(\)\.spaceLeases\(\)\.size\(\)==fixture\.pages\.currentMappings\(sid\)\.instances\(\)\.size\(\),readyTick -> \{'
$partial=@'
when(fixture.context,tick,() -> {
            var ciCurrent=fixture.pages.currentMappings(sid);
            var ciRecord=fixture.record();
            if(fixture.frame.outwardFacing()==Direction.SOUTH && index==3 && ciRecord!=null
                    && ciCurrent.epoch()==index+1 && ciRecord.spaceLeases().size()>ciCurrent.instances().size()) {
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_INJECT_PARTIAL sid={} stage={} expected={} current={} instances={} leases={} own={}",
                        sid,index,index+2,ciCurrent.epoch(),ciCurrent.instances().size(),ciRecord.spaceLeases().size(),waitingLeaseEvidence(fixture.context));
                throw new IllegalStateException("CI_W1_PARTIAL_WAIT_FAIL sid="+sid+" stage="+index);
            }
            return fixture.pages.currentMappings(sid).epoch()==index+2
                    && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();
        },readyTick -> {
'@
$timeout=@'
var ciTimeoutHeld=new boolean[1];
        when(fixture.context,tick,() -> {
            if(fixture.frame.outwardFacing()==Direction.SOUTH && index==3) {
                if(!ciTimeoutHeld[0]) {
                    var ciCurrent=fixture.pages.currentMappings(sid);
                    var ciRecord=fixture.record();
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_TIMEOUT_HOLD sid={} stage={} expected={} current={} instances={} leases={} own={}",
                            sid,index,index+2,ciCurrent.epoch(),ciCurrent.instances().size(),ciRecord==null ? -1 : ciRecord.spaceLeases().size(),waitingLeaseEvidence(fixture.context));
                    ciTimeoutHeld[0]=true;
                }
                return false;
            }
            return fixture.pages.currentMappings(sid).epoch()==index+2
                    && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();
        },readyTick -> {
'@
$slow=@'
var ciPaused=new boolean[1];
        when(fixture.context,tick,() -> {
            var ciCurrent=fixture.pages.currentMappings(sid);
            var ciRecord=fixture.record();
            if(fixture.frame.outwardFacing()==Direction.SOUTH && index==3 && !ciPaused[0] && ciRecord!=null
                    && ciCurrent.epoch()==index+1 && ciCurrent.instances().size()==1 && ciRecord.spaceLeases().size()==3) {
                var ciCurrentSlots=new java.util.HashSet<Integer>();
                for(var ciView : ciCurrent.instances()) ciCurrentSlots.add(ciView.ref().slotId());
                boolean ciNewUnderReady=false;
                for(var ciLease : ciRecord.spaceLeases()) if(!ciCurrentSlots.contains(ciLease.slotId())) {
                    var ciBox=ciLease.bounds(); int ciTotal=0,ciFull=0,ciLoaded=0,ciTicking=0;
                    for(int x=ciBox.getMinX()>>4;x<=ciBox.getMaxX()>>4;x++) for(int z=ciBox.getMinZ()>>4;z<=ciBox.getMaxZ()>>4;z++) {
                        ciTotal++; var ciChunk=new net.minecraft.util.math.ChunkPos(x,z);
                        if(fixture.target.getChunkManager().getWorldChunk(x,z)!=null) ciFull++;
                        if(fixture.target.isChunkLoaded(ciChunk.toLong())) ciLoaded++;
                        if(fixture.target.shouldTick(ciChunk)) ciTicking++;
                    }
                    if(ciFull<ciTotal || ciLoaded<ciTotal || ciTicking<ciTotal) ciNewUnderReady=true;
                }
                if(ciNewUnderReady) {
                    ciPaused[0]=true;
                    boolean ciBefore=fixture.pages.currentMappings(sid).epoch()==index+2
                            && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();
                    long ciStart=System.nanoTime(),ciStartTick=fixture.server.getTicks();
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_E1_PAUSE_START sid={} stage={} expected={} current={} instances={} leases={} newUnderReady={} predicate={} startNanos={} serverTick={} own={}",
                            sid,index,index+2,ciCurrent.epoch(),ciCurrent.instances().size(),ciRecord.spaceLeases().size(),ciNewUnderReady,ciBefore,ciStart,ciStartTick,waitingLeaseEvidence(fixture.context));
                    try { Thread.sleep(25_000); }
                    catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("CI_W1_E1_SLEEP_INTERRUPTED",interrupted); }
                    long ciEnd=System.nanoTime(),ciEndTick=fixture.server.getTicks();
                    var ciAfterCurrent=fixture.pages.currentMappings(sid);
                    var ciAfterRecord=fixture.record();
                    boolean ciAfter=fixture.pages.currentMappings(sid).epoch()==index+2
                            && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();
                    boolean ciNewUnderReadyAfter=false;
                    for(var ciLease : ciAfterRecord.spaceLeases()) if(!ciCurrentSlots.contains(ciLease.slotId())) {
                        var ciBox=ciLease.bounds(); int ciTotal=0,ciFull=0,ciLoaded=0,ciTicking=0;
                        for(int x=ciBox.getMinX()>>4;x<=ciBox.getMaxX()>>4;x++) for(int z=ciBox.getMinZ()>>4;z<=ciBox.getMaxZ()>>4;z++) {
                            ciTotal++; var ciChunk=new net.minecraft.util.math.ChunkPos(x,z);
                            if(fixture.target.getChunkManager().getWorldChunk(x,z)!=null) ciFull++;
                            if(fixture.target.isChunkLoaded(ciChunk.toLong())) ciLoaded++;
                            if(fixture.target.shouldTick(ciChunk)) ciTicking++;
                        }
                        if(ciFull<ciTotal || ciLoaded<ciTotal || ciTicking<ciTotal) ciNewUnderReadyAfter=true;
                    }
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_E1_PAUSE_END sid={} stage={} expected={} current={} instances={} leases={} newUnderReady={} predicate={} startNanos={} endNanos={} elapsedMs={} startServerTick={} endServerTick={} deadlineExpired={} own={}",
                            sid,index,index+2,ciAfterCurrent.epoch(),ciAfterCurrent.instances().size(),ciAfterRecord.spaceLeases().size(),ciNewUnderReadyAfter,ciAfter,ciStart,ciEnd,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(ciEnd-ciStart),ciStartTick,ciEndTick,ciEnd-trace.deadlineNanos>=0,waitingLeaseEvidence(fixture.context));
                }
            }
            return fixture.pages.currentMappings(sid).epoch()==index+2
                    && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();
        },readyTick -> {
'@
$chosen=switch($Scenario){
    'partial-exception'{$partial}
    'request-fault'{$partial}
    'witness-fault'{$partial}
    'late-crosswall'{$partial}
    'timeout-false'{$timeout}
    'slow-old10'{$slow}
    'slow-new60'{$slow}
}
$mutated=Replace-Once $raw $condition $chosen
if($Scenario -ceq 'slow-old10'){
    $old="final long deadlineNanos=startNanos+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);`r`n        boolean readyLogged;"
    $replacement="final long deadlineNanos=startNanos+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);`r`n        boolean readyLogged;"
    if($mutated.Split([string[]]@($old),[StringSplitOptions]::None).Count -ne 2){throw '具名cohort 60秒budget anchor不是唯一'}
    $mutated=$mutated.Replace($old,$replacement)
}
# 失敗情境的直接資源見證：原錯報給GameTest前，以及GameTest已FAIL且跨入下一batch後。
$observerPattern='when\(context,3,fixture::activeReady,activeTick -> \{\s*var sid=fixture\.record\(\)\.sessionUuid\(\);'
$observer=@'
when(context,3,fixture::activeReady,activeTick -> {
            var sid=fixture.record().sessionUuid();
            if(facing==Direction.SOUTH) {
                var ciTest=M5GameTestSupport.gameTestState(context);
                var ciChamber=fixture.controller().chamberUuid();
                var ciFailedAt=new long[]{-1};
                var ciLogged=new boolean[1];
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(owner -> {
                    if(owner!=fixture.server || ciLogged[0] || !ciTest.isFailed()) return;
                    if(ciFailedAt[0]<0) ciFailedAt[0]=owner.getTicks();
                    if(owner.getTicks()-ciFailedAt[0]<100) return;
                    try {
                        var ciJournal=SessionRecoveryState.get(owner);
                        var ciManager=owner.getPlayerManager();
                        boolean ciPlayersClosed=fixture.players.stream().allMatch(c -> ciManager.getPlayer(c.player().getUuid())==null);
                        boolean ciOperatorClosed=ciManager.getPlayer(fixture.operator.player().getUuid())==null;
                        var ciPresence=dev.quantumchamber.chamber.ChamberSessions.gateway().presence(owner,ciChamber);
                        boolean ciSpaceAbsent=false;
                        try { fixture.pages.currentMappings(sid); }
                        catch(IllegalArgumentException unknown) { ciSpaceAbsent="未知 session".equals(unknown.getMessage()); }
                        org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_ADAPTER_POSTFAIL sid={} failedAt={} now={} presence={} flushed={} current={} playersClosed={} operatorClosed={} spaceAbsent={} receiptOutstanding={}",
                                sid,ciFailedAt[0],owner.getTicks(),ciPresence,ciJournal.flushedRecords().containsKey(sid),ciJournal.records().containsKey(sid),
                                ciPlayersClosed,ciOperatorClosed,ciSpaceAbsent,fixture.pages.releaseComplete(sid));
                        ciLogged[0]=true;
                    } catch(RuntimeException observed) {
                        org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_ADAPTER_POSTFAIL_ERROR sid={} error={}",sid,observed.toString());
                        ciLogged[0]=true;
                    }
                });
            }
'@
$mutated=Replace-Once $mutated $observerPattern $observer
$preAnchor=@'
                info("FINAL",tick);
                throw original;
'@
$preReplacement=@'
                info("FINAL",tick);
                if(fixture.frame.outwardFacing()==Direction.SOUTH) {
                    try {
                        var ciJournal=SessionRecoveryState.get(fixture.server);
                        var ciManager=fixture.server.getPlayerManager();
                        var ciSuppressed=original.getSuppressed();
                        boolean ciSourceBbox=fixture.players.stream().allMatch(c -> c.player().getServerWorld()==fixture.context.getWorld()
                                && dev.quantumchamber.chamber.ChamberOccupantService.contains(
                                        dev.quantumchamber.chamber.ChamberGeometry.interiorBox(fixture.frame),c.player().getBoundingBox()));
                        boolean ciPlayersClosed=fixture.players.stream().allMatch(c -> ciManager.getPlayer(c.player().getUuid())==null);
                        boolean ciOperatorClosed=ciManager.getPlayer(fixture.operator.player().getUuid())==null;
                        boolean ciSpaceAbsent=false;
                        try { fixture.pages.currentMappings(sid); }
                        catch(IllegalArgumentException unknown) { ciSpaceAbsent="未知 session".equals(unknown.getMessage()); }
                        org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_ADAPTER_PREFAIL sid={} phase={} presence={} flushed={} current={} sourceBbox={} playersClosed={} operatorClosed={} mayMutate={} spaceAbsent={} receiptOutstanding={} suppressed={} firstSuppressed={}",
                                sid,phase,dev.quantumchamber.chamber.ChamberSessions.gateway().presence(fixture.server,chamber),
                                ciJournal.flushedRecords().containsKey(sid),ciJournal.records().containsKey(sid),ciSourceBbox,ciPlayersClosed,ciOperatorClosed,
                                dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(fixture.context.getWorld(),fixture.frame.controllerPos()),
                                ciSpaceAbsent,fixture.pages.releaseComplete(sid),ciSuppressed.length,ciSuppressed.length==0 ? "NONE" : ciSuppressed[0].getMessage());
                    } catch(RuntimeException observed) { org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_ADAPTER_PREFAIL_ERROR sid={} error={}",sid,observed.toString()); }
                }
                throw original;
'@
$preAnchor=$preAnchor.Replace("`n","`r`n")
if($mutated.Split([string[]]@($preAnchor),[StringSplitOptions]::None).Count -ne 2){throw '原FAIL前直接見證anchor不是唯一'}
$mutated=$mutated.Replace($preAnchor,$preReplacement.Replace("`n","`r`n"))
if($Scenario -ceq 'request-fault'){
    $anchor='                dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(fixture.context.getWorld(),fixture.controller());'
    $replacement=@'
                if(fixture.frame.outwardFacing()==Direction.SOUTH) throw new IllegalStateException("CI_W1_CLEANUP_FAULT");
                dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(fixture.context.getWorld(),fixture.controller());
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
}
if($Scenario -ceq 'witness-fault'){
    $anchor=@'
        private boolean cleanWitness(dev.quantumchamber.chamber.ChamberSessionGateway gateway) {
            phase="WITNESS";
'@
    $replacement=@'
        private boolean cleanWitness(dev.quantumchamber.chamber.ChamberSessionGateway gateway) {
            phase="WITNESS";
            if(fixture.frame.outwardFacing()==Direction.SOUTH) throw new IllegalStateException("CI_W1_WITNESS_FAULT");
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
    $anchor=@'
            phase="INCOMPLETE";
            info("FINAL",tick);
            throw original;
'@
    $replacement=@'
            phase="INCOMPLETE";
            info("FINAL",tick);
            if(fixture.frame.outwardFacing()==Direction.SOUTH) {
                var ciSuppressed=original.getSuppressed();
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_ADAPTER_WITNESS_FAILURE sid={} originalType={} originalMessage={} cause={} suppressed={} firstSuppressed={} lastSuppressed={} tick={} deadlineTick={}",
                        sid,original.getClass().getName(),original.getMessage(),String.valueOf(original.getCause()),ciSuppressed.length,
                        ciSuppressed.length==0 ? "NONE" : ciSuppressed[0].getMessage(),
                        ciSuppressed.length==0 ? "NONE" : ciSuppressed[ciSuppressed.length-1].getMessage(),tick,deadlineTick);
            }
            throw original;
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
}
if($Scenario -ceq 'late-crosswall'){
    $anchor='        private String phase="START";'
    $replacement=@'
        private String phase="START";
        private boolean ciLateDelayed;
        private int ciRequestCount,ciRequestAfterDeadline,ciScheduleAfterDeadline;
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
    $anchor='        private void schedule(int tick) {'
    $replacement=@'
        private void schedule(int tick) {
            if(fixture.frame.outwardFacing()==Direction.SOUTH && (System.nanoTime()-deadlineNanos>=0 || tick>deadlineTick)) ciScheduleAfterDeadline++;
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
    $anchor='                dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(fixture.context.getWorld(),fixture.controller());'
    $replacement=@'
                if(fixture.frame.outwardFacing()==Direction.SOUTH) {
                    ciRequestCount++;
                    if(System.nanoTime()-deadlineNanos>=0) ciRequestAfterDeadline++;
                }
                dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(fixture.context.getWorld(),fixture.controller());
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
    $anchor='            return !fixture.pages.releaseComplete(sid);'
    $replacement=@'
            boolean ciReceiptAcked=!fixture.pages.releaseComplete(sid);
            if(ciReceiptAcked && fixture.frame.outwardFacing()==Direction.SOUTH && !ciLateDelayed) {
                ciLateDelayed=true;
                long ciStart=System.nanoTime();
                long ciRemaining=deadlineNanos-ciStart;
                long ciSleepMs=Math.max(0,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(ciRemaining)+250);
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_LATE_SLEEP_BEGIN sid={} tick={} remainingMs={} sleepMs={} requestCount={}",
                        sid,fixture.server.getTicks(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(ciRemaining),ciSleepMs,ciRequestCount);
                try { Thread.sleep(ciSleepMs); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("CI_W1_LATE_SLEEP_INTERRUPTED",interrupted); }
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("CI_W1_LATE_SLEEP_END sid={} tick={} overdueMs={} requestAfterDeadline={}",
                        sid,fixture.server.getTicks(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-deadlineNanos),ciRequestAfterDeadline);
            }
            return ciReceiptAcked;
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
    $anchor='                info("FINAL",tick);'
    $replacement=@'
                info("FINAL",tick);
                if(fixture.frame.outwardFacing()==Direction.SOUTH) org.slf4j.LoggerFactory.getLogger("quantumchamber").info(
                        "CI_W1_ADAPTER_LATE sid={} phase={} suppressed={} firstSuppressed={} requestCount={} requestAfterDeadline={} scheduleAfterDeadline={} delayed={} tick={} deadlineTick={}",
                        sid,phase,original.getSuppressed().length,
                        original.getSuppressed().length==0 ? "NONE" : original.getSuppressed()[0].getMessage(),
                        ciRequestCount,ciRequestAfterDeadline,ciScheduleAfterDeadline,ciLateDelayed,tick,deadlineTick);
'@
    $mutated=Replace-TextOnce $mutated $anchor $replacement
}
$predicate='return fixture.pages.currentMappings(sid).epoch()==index+2' + "`r`n" +
        '                    && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size();'
if(-not $mutated.Contains($predicate)){throw '非注入路徑的原predicate未逐字保留'}
$ordinary='whenUntil(context,tick,condition,ready,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10));'
if(-not $mutated.Contains($ordinary)){throw '普通when的10秒語意變更，拒絕輸出'}
[IO.File]::WriteAllBytes($Output,$utf8.GetBytes($mutated))
[ordered]@{
    schema=1;scenario=$Scenario;source=$Source;output=$Output
    sourceSha256=(Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
    mutatedSha256=(Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash
    originalPredicate='fixture.pages.currentMappings(sid).epoch()==index+2 && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size()'
    nonInjectedPredicateKept=$true
    ordinaryWhen10SecondsKept=$true
    budgetOverride=($Scenario -ceq 'slow-old10')
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $Report -Encoding utf8
Write-Output "ADAPTER_OUTPUT=$Output"
Write-Output "ADAPTER_REPORT=$Report"
