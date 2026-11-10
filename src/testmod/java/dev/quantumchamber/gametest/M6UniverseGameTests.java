package dev.quantumchamber.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** 同 server 的真 schema2 backend 與 canonical／legacy 控制組。 */
public final class M6UniverseGameTests implements FabricGameTest {
    /** Task21 恢復測試標註；保留全量 U→U 斷言與原失敗起點，不由 Task13 owner 測試代驗。 */
    public void same_seed_fresh_copies_settings_not_blocks(TestContext context) throws Exception {
        M6ProfileProbePhases.verifyGameTest(context.getWorld().getServer());
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_profile", tickLimit=1200)
    public void toggle_owner_settings_match_both_frozen_presets(TestContext context) throws Exception {
        M6ProfileProbePhases.verifyToggleOwnerSettingsGameTest(context.getWorld().getServer());
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_same_seed", tickLimit=1200)
    public void same_seed_owner_settings_match_frozen_snapshots(TestContext context) throws Exception {
        M6ProfileProbePhases.verifyOwnerSettingsGameTest(context.getWorld().getServer());
        context.complete();
    }

    /** 僅直接呼叫 selector 的控制組；正式玩家入場另由 Windows 拉桿測試及 canonical 原生鏈驗證。 */
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_same_seed", tickLimit=1200)
    public void selector_freezes_policy_two_for_new_session(TestContext context) throws Exception {
        var server = context.getWorld().getServer();
        var root = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        var entropy = dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, false);
        var policy = dev.quantumchamber.candidate.M6CandidatePolicySelector.forNewSession(server).freeze(-1,
                entropy.entropyFingerprint(), new dev.quantumchamber.candidate.SourceFamilyRef.Vanilla(
                        net.minecraft.world.World.OVERWORLD, dev.quantumchamber.universe.DimensionRole.OVERWORLD));
        context.assertEquals(policy.policyVersion(), 3, "selectorFreezesPolicyThreeForNewSession");
        context.assertEquals(0, policy.familyCapabilityVersion(), "新 session 固定 family0");
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_retry_high", tickLimit=100000)
    public void unknown_preset_high_requires_real_low_before_one_retry(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        new RetryHighProbe(context,false).start();
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_retry_callback", tickLimit=100000)
    public void unknown_preset_callback_failure_still_returns_safely(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        new RetryHighProbe(context,true).start();
    }

    /** 原失敗仍先經LOW與正式返還清理，避免RED自身變成反覆入場風暴。 */
    private static final class RetryHighProbe {
        private final TestContext context;
        private final M2CorridorGameTests.NativeEntry fixture;
        private final LogCapture logs;
        private final M2CorridorGameTests.TestHooks hooks = new M2CorridorGameTests.TestHooks();
        private final java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        private final long deadline = System.nanoTime() + 60_000_000_000L;
        private dev.quantumchamber.persistence.SessionRecoveryRecord attempt;
        private boolean returning;
        private int stage, stableAt = -1;
        private byte[] stableJournal;
        private net.minecraft.nbt.NbtCompound stableController;
        private final boolean callbackFault;
        private final M2CorridorGameTests.NativeEntry.TeardownFault cleanupFault;
        private final dev.quantumchamber.chamber.ChamberSessionGateway originalGateway;
        private dev.quantumchamber.chamber.ChamberSessionGateway decorator;
        private int callbacks,retryStartedAt;
        private boolean terminal;
        private boolean manualCloseDone;
        private Throwable originalFailure;
        private dev.quantumchamber.chamber.ChamberControllerBlockEntity controllerIdentity;
        RetryHighProbe(TestContext context,boolean callbackFault) {
            this.context=context;this.callbackFault=callbackFault;
            cleanupFault=callbackFault?M2CorridorGameTests.NativeEntry.TeardownFault.valueOf(System.getProperty("quantumchamber.test.m6RetryCleanupFault","NONE")):M2CorridorGameTests.NativeEntry.TeardownFault.NONE;
            originalGateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
            fixture=new M2CorridorGameTests.NativeEntry(context,1);
            controllerIdentity=fixture.controller();
            logs=new LogCapture("quantumchamber");
            try {
                hooks.add(this::verifyDetached);
                hooks.add(logs::close);
                hooks.add(this::restoreGateway);
            } catch(RuntimeException | Error failure) {
                try {logs.close();}catch(RuntimeException | Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
                hooks.releaseAfter(failure);throw failure;
            }
        }
        void start() {
            schedule(2, () -> {
                if(callbackFault) {
                    decorator=new dev.quantumchamber.chamber.ChamberSessionGateway() {
                        public Presence presence(net.minecraft.server.MinecraftServer server,java.util.UUID chamber){return originalGateway.presence(server,chamber);}
                        public StartResult start(net.minecraft.server.world.ServerWorld world,dev.quantumchamber.chamber.ChamberControllerBlockEntity controller,java.util.List<java.util.UUID> people){return originalGateway.start(world,controller,people);}
                        public boolean returnToOrigin(net.minecraft.server.world.ServerWorld world,dev.quantumchamber.chamber.ChamberControllerBlockEntity controller){return originalGateway.returnToOrigin(world,controller);}
                        public void onUnknownSourcePreset(net.minecraft.server.MinecraftServer server,dev.quantumchamber.persistence.SessionRecoveryRecord checked) {
                            callbacks++;
                            boolean wrongServer=false,staleRecord=false;
                            try {originalGateway.onUnknownSourcePreset(null,checked);}catch(IllegalStateException expected){wrongServer=true;}
                            try {originalGateway.onUnknownSourcePreset(server,attempt);}catch(IllegalStateException expected){staleRecord=true;}
                            context.assertTrue(wrongServer && staleRecord && !fixture.controller().candidateRetryRequiresLow(),"錯owner／舊record不能設旗標");
                            throw new IllegalStateException("TEST_UNKNOWN_PRESET_CALLBACK_FAILURE");
                        }
                    };
                    dev.quantumchamber.chamber.ChamberSessions.install(decorator);
                }
                controllerIdentity=fixture.controller();fixture.power();attempt=fixture.record();
                if(attempt==null){finish("RETRY_INITIAL_ARMING_MISSING",3);return;}
                ids.add(attempt.sessionUuid());poll(3);
            });
        }
        private void poll(int tick) {
            schedule(tick, () -> {
                if(terminal)return;
                try {
                    if(System.nanoTime()>=deadline){finish("RETRY_WALL_DEADLINE stage="+stage,tick+1);return;}
                    context.assertTrue(fixture.controller()==controllerIdentity,"必須同一exact controller instance");
                    if(stage>=2 && tick-retryStartedAt>400){finish("RETRY_TICK_DEADLINE",tick+1);return;}
                    var current=fixture.record();
                    if(current!=null) {
                        ids.add(current.sessionUuid());
                        if(ids.size()>(stage<2?1:2)){finish("UNKNOWN_PRESET_HIGH_RESTARTED ids="+ids.size(),tick+1);return;}
                        context.assertTrue(current.state()!=dev.quantumchamber.superposition.SessionState.MEASURED
                                && !(current.candidateSelection().orElseThrow() instanceof dev.quantumchamber.candidate.CandidateSelection.Selected),"拒絕不可checked SELECTED");
                        if(stage==2 && !current.sessionUuid().equals(attempt.sessionUuid())) {attempt=current;returning=false;stableAt=-1;stage=3;}
                        if(current.sessionUuid().equals(attempt.sessionUuid()) && current.state()==dev.quantumchamber.superposition.SessionState.RETURNING) {
                            context.assertEquals(current,dev.quantumchamber.persistence.SessionRecoveryState.get(fixture.server).records().get(current.sessionUuid()),"RETURNING current=flushed");
                            context.assertTrue(logs.entries().stream().anyMatch(entry -> exactNewWitness(fixture.server,attempt,entry.message())),"當次NEW/key/fingerprint與RETURNING同sid");
                            returning=true;
                            if(callbackFault) {
                                context.assertEquals(1,callbacks,"callback恰一次，沒有假ack");
                                context.assertTrue(!fixture.controller().candidateRetryRequiresLow(),"throw前不冒稱已設旗標");
                                context.assertTrue(logs.entries().stream().anyMatch(entry -> entry.thrown()!=null && String.valueOf(entry.thrown().getMessage()).contains("TEST_UNKNOWN_PRESET_CALLBACK_FAILURE")),"callback原錯進正式返還路徑");
                                finish(null,tick+1);return;
                            }
                        }
                    }
                    var presence=dev.quantumchamber.chamber.ChamberSessions.gateway().presence(fixture.server,fixture.controller().chamberUuid());
                    if(stage==1) {
                        context.assertTrue(!fixture.controller().createNbt(fixture.server.getRegistryManager()).getBoolean("CandidateRetryRequiresLow"),"實際LOW解除旗標");
                        fixture.power();stage=2;retryStartedAt=tick;stableAt=-1;poll(tick+1);return;
                    }
                    if((stage==0 || stage==3) && returning && current==null && presence==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE) {
                        if(stableAt<0){stableAt=tick;poll(tick+1);return;}
                        if(tick==stableAt+1) {stableJournal=journalBytes();stableController=fixture.controller().createNbt(fixture.server.getRegistryManager());}
                        context.assertTrue(java.util.Arrays.equals(stableJournal,journalBytes()),"HIGH安定期不得新checked寫入");
                        context.assertEquals(stableController,fixture.controller().createNbt(fixture.server.getRegistryManager()),"HIGH同controller保持");
                        if(tick-stableAt>=201) {
                            context.assertTrue(stableController.getBoolean("CandidateRetryRequiresLow"),"未知preset旗標保持至LOW");
                            fixture.assertInventory();
                            if(stage==0){fixture.power();stage=1;poll(tick+1);return;}
                            context.assertEquals(2,ids.size(),"LOW→HIGH只有一次新session且無第三次");finish(null,tick+1);return;
                        }
                    }
                    poll(tick+1);
                } catch(RuntimeException | Error failure) {
                    actionFailed(failure,tick);
                }
            });
        }
        private byte[] journalBytes() {
            try {
                return java.nio.file.Files.readAllBytes(fixture.server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                        .resolve("data/quantumchamber_sessions.dat"));
            } catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        }
        private void finish(String message,int tick) {finishOnce(message==null?null:new IllegalStateException(message),tick);}
        private void finishOnce(Throwable failure,int tick) {
            if(terminal){if(failure!=null)releaseAndThrow(failure);return;}
            terminal=true;
            if(failure!=null){originalFailure=failure;hooks.prefer(failure);}
            Runnable done=() -> {
                var primary=hooks.preferred();
                if(primary!=null){releaseAndThrow(primary);return;}
                hooks.release();context.complete();
            };
            try {
                SessionView view;
                try {view=sessionView();}catch(RuntimeException | Error uncertain) {
                    if(hooks.preferred()==null){originalFailure=uncertain;hooks.prefer(uncertain);}
                    else hooks.primary(uncertain);
                    view=SessionView.PENDING;
                }
                if(view==SessionView.NONE) {
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("M6_RETRY_NO_SESSION_VERIFIED pid={} session={} presence=NONE currentEmpty=true flushedEmpty=true",ProcessHandle.current().pid(),attempt==null?"UNASSIGNED":attempt.sessionUuid());
                    closeNoSessionOnce();done.run();
                } else {
                    if(view==SessionView.PENDING) {
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").warn("M6_RETRY_PENDING_RETURN pid={} session={}",ProcessHandle.current().pid(),attempt==null?"UNASSIGNED":attempt.sessionUuid());
                        requestReturn();
                    }
                    fixture.trustedTeardown(tick,done,hooks,cleanupFault);
                }
            } catch(RuntimeException | Error original) {
                releaseAndThrow(original);
            }
        }
        private enum SessionView { CHECKED, NONE, PENDING }
        private void requireFixtureOwner() {
            var world=context.getWorld();
            if(!fixture.server.isOnThread() || fixture.server.getWorld(world.getRegistryKey())!=world
                    || controllerIdentity==null || fixture.controller()!=controllerIdentity || controllerIdentity.isRemoved()
                    || controllerIdentity.getWorld()!=world)throw new IllegalStateException("M6_RETRY_FIXTURE_OWNER_UNCERTAIN");
            if(attempt!=null && !attempt.chamberUuid().equals(controllerIdentity.chamberUuid()))throw new IllegalStateException("M6_RETRY_CHAMBER_IDENTITY_CHANGED");
            var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
            if(gateway!=originalGateway && gateway!=decorator)throw new IllegalStateException("M6_RETRY_GATEWAY_OWNER_UNCERTAIN");
        }
        private SessionView sessionView() {
            requireFixtureOwner();
            var chamber=controllerIdentity.chamberUuid();
            if(chamber==null)return SessionView.PENDING;
            var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(fixture.server);
            java.util.function.Predicate<dev.quantumchamber.persistence.SessionRecoveryRecord> belongs=row->row.chamberUuid().equals(chamber)
                    || row.origin().worldKey().equals(context.getWorld().getRegistryKey().getValue()) && row.origin().controllerPos().equals(fixture.frame.controllerPos());
            var checked=journal.flushedRecords().values().stream().filter(belongs).toList();
            if(checked.size()>1)throw new IllegalStateException("M6_RETRY_AMBIGUOUS_CHECKED_SESSION");
            if(!checked.isEmpty()) {
                var row=checked.getFirst();
                if(!row.chamberUuid().equals(chamber) || !row.origin().worldKey().equals(context.getWorld().getRegistryKey().getValue())
                        || !row.origin().controllerPos().equals(fixture.frame.controllerPos()))throw new IllegalStateException("M6_RETRY_CHECKED_ORIGIN_MISMATCH");
                // START故障可能早於attempt賦值；從exact checked原件綁回同一SID。
                attempt=row;return SessionView.CHECKED;
            }
            boolean currentEmpty=journal.records().values().stream().noneMatch(belongs);
            var presence=dev.quantumchamber.chamber.ChamberSessions.gateway().presence(fixture.server,chamber);
            return currentEmpty && presence==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE?SessionView.NONE:SessionView.PENDING;
        }
        private void requestReturn() {
            requireFixtureOwner();
            if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos()))fixture.power();
            dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(context.getWorld(),controllerIdentity);
        }
        private void closeNoSessionOnce() {
            // 僅由同一同步交易中已驗證NONE的唯一呼叫點進入；不再查核後另走跳過返還的例外路徑。
            if(!manualCloseDone){manualCloseDone=true;fixture.close();}
        }
        private void actionFailed(Throwable failure,int tick) {
            if(terminal){releaseAndThrow(failure);return;}
            finishOnce(failure,tick+1);
        }
        private void releaseAndThrow(Throwable failure) {
            var primary=hooks.primary(failure);hooks.releaseAfter(primary);throwOriginal(primary);
        }
        private void schedule(int tick,Runnable action) {
            try {
                context.runAtTick(tick,() -> {
                    try {action.run();}catch(RuntimeException | Error failure){actionFailed(failure,Math.toIntExact(context.getTick()));}
                });
            } catch(RuntimeException | Error failure){actionFailed(failure,Math.toIntExact(context.getTick()));}
        }
        private static void throwOriginal(Throwable failure) {
            if(failure instanceof RuntimeException runtime)throw runtime;
            if(failure instanceof Error error)throw error;
            throw new AssertionError(failure);
        }
        private void restoreGateway() {
            var current=dev.quantumchamber.chamber.ChamberSessions.gateway();
            if(current==originalGateway)return;
            if(decorator==null || current!=decorator)throw new IllegalStateException("M6_RETRY_FOREIGN_GATEWAY_NOT_OVERWRITTEN");
            dev.quantumchamber.chamber.ChamberSessions.install(originalGateway);
        }
        private void verifyDetached() {
            boolean gateway=dev.quantumchamber.chamber.ChamberSessions.gateway()==originalGateway;
            var logger=(org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getLogger("quantumchamber");
            boolean absent=!logger.getContext().getConfiguration().getRootLogger().getAppenders().containsKey(logs.getName());
            boolean stopped=!logs.isStarted();
            var observed=(java.util.Map<?,?>)M4CandidateTestAccess.get(ConnectedGameTestPlayer.class,"OBSERVED");
            boolean removed=true,unobserved=true,queuesEmpty=true;
            var connections=new java.util.ArrayList<>(fixture.players);connections.add(fixture.operator);
            for(var connection:connections) {
                var id=connection.player().getUuid();
                removed &= fixture.server.getPlayerManager().getPlayer(id)==null;
                unobserved &= !observed.containsKey(id);
                var channel=(io.netty.channel.embedded.EmbeddedChannel)M4CandidateTestAccess.get(connection,"channel");
                var inbound=(java.util.Queue<?>)M4CandidateTestAccess.get(channel,"inboundMessages");
                var outbound=(java.util.Queue<?>)M4CandidateTestAccess.get(channel,"outboundMessages");
                queuesEmpty &= (inbound==null || inbound.isEmpty()) && (outbound==null || outbound.isEmpty());
            }
            boolean detached=gateway&&absent&&stopped&&removed&&unobserved&&queuesEmpty;
            if(originalFailure!=null) {
                boolean same=hooks.preferred()==originalFailure;
                boolean timeout=java.util.Arrays.stream(originalFailure.getSuppressed()).anyMatch(error->String.valueOf(error.getMessage()).contains("有限等待逾時"));
                org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("M6_RETRY_PRIMARY_RETAINED pid={} session={} sameObject={} message={} timeoutSuppressed={}",
                        ProcessHandle.current().pid(),attempt==null?"UNASSIGNED":attempt.sessionUuid(),same,originalFailure.getMessage(),timeout);
                detached &= same;
            }
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("M6_RETRY_CLEANUP_DETACHED status={} pid={} session={} fault={} gateway={} appenderRemoved={} captureStopped={} playersRemoved={} observedRemoved={} queuesEmpty={}",
                    detached?"PASS":"FAIL",ProcessHandle.current().pid(),attempt==null?"UNASSIGNED":attempt.sessionUuid(),cleanupFault,gateway,absent,stopped,removed,unobserved,queuesEmpty);
            context.assertTrue(detached,"M6_RETRY_CLEANUP_DETACH_FAILED");
        }
    }

    /** 獨立 resolver owner 控制組，不冒充正式玩家跨 JVM 功能鏈。 */
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void source_and_existing_do_not_resolve_unknown_preset(TestContext context) throws Exception {
        verifyLazyBranches(context, false);
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void existing_legacy_new_never_becomes_random(TestContext context) throws Exception {
        verifyLazyBranches(context, true);
        context.complete();
    }

    private static void verifyLazyBranches(TestContext context, boolean legacy) throws Exception {
        var root = context.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        var entropy = dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, false);
        var policy = new dev.quantumchamber.candidate.CandidatePolicySnapshot(net.minecraft.util.Identifier.of(
                legacy ? "quantumchamber:m4_v1" : "quantumchamber:m6_profiles_v1"), 1, legacy ? 1 : 2,
                5, 20, 75, 0, legacy ? 16384 : 4096, entropy.entropyFingerprint(),
                new dev.quantumchamber.candidate.SourceFamilyRef.Vanilla(net.minecraft.world.World.OVERWORLD,
                        dev.quantumchamber.universe.DimensionRole.OVERWORLD));
        int[] sourceCalls = {0}, acquireCalls = {0};
        var resolver = new dev.quantumchamber.candidate.CandidateResolver(entropy,
                dev.quantumchamber.universe.Minecraft121SettingsLifecycle.registry(), () -> {
                    acquireCalls[0]++; throw new IllegalStateException("UNKNOWN_PRESET_ACQUIRE");
                });
        java.util.function.Supplier<dev.quantumchamber.candidate.SourceGenerationSnapshot> unknown = () -> {
            sourceCalls[0]++; throw new IllegalStateException("UNKNOWN_PRESET_OWNER_CONTROL");
        };
        var pool = java.util.List.of(new dev.quantumchamber.candidate.UniverseDiscoveryRecord(
                new dev.quantumchamber.universe.UniverseId(java.util.UUID.randomUUID()), 0, 0, java.util.Optional.empty(), true));
        var derive = dev.quantumchamber.candidate.CandidateEntropyState.class.getDeclaredMethod("derive",
                dev.quantumchamber.candidate.DerivationDomain.class, dev.quantumchamber.corridor.DoorKey.class, int.class);
        derive.setAccessible(true);
        boolean sourceSeen = false, existingSeen = false, newSeen = false;
        var sid = java.util.UUID.randomUUID();
        for (int station = 0; station < 4096 && !(sourceSeen && existingSeen && newSeen); station++) {
            var key = new dev.quantumchamber.corridor.DoorKey(sid, station,
                    dev.quantumchamber.corridor.DoorKey.DoorWallSide.NEGATIVE_LATERAL);
            var bytes = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.BUCKET, key, policy.candidateSchemaVersion());
            int bucket = new java.math.BigInteger(1, bytes.bytes()).mod(java.math.BigInteger.valueOf(100)).intValueExact();
            if (bucket < 5 && !sourceSeen) {
                context.assertTrue(resolver.resolve(key, policy, pool, unknown) instanceof dev.quantumchamber.candidate.QuantumCandidate.Source,
                        "SOURCE bucket 必須獨立於未知 preset");
                sourceSeen = true;
            } else if (bucket >= 5 && bucket < 25 && !existingSeen) {
                context.assertTrue(resolver.resolve(key, policy, pool, unknown) instanceof dev.quantumchamber.candidate.QuantumCandidate.Existing,
                        "EXISTING bucket 必須獨立於未知 preset");
                existingSeen = true;
            } else if (bucket >= 25 && !newSeen) {
                if (legacy) {
                    var candidate = (dev.quantumchamber.candidate.QuantumCandidate.New)resolver.resolve(key, policy, pool, unknown);
                    context.assertTrue(!candidate.intent().isM6(), "LEGACY_NEW_CHANGED：舊 frozen NEW 不升 random");
                    context.assertEquals(candidate, resolver.resolve(key, policy, pool, unknown), "舊 frozen候選再次解析完全一致");
                } else {
                    boolean rejected = false;
                    try { resolver.resolve(key, policy, pool, unknown); }
                    catch (IllegalStateException failure) { rejected = failure.getMessage().contains("UNKNOWN_PRESET_OWNER_CONTROL"); }
                    context.assertTrue(rejected, "NEW 正控制必須真正呼叫未知來源 supplier 並拒絕");
                }
                newSeen = true;
            }
        }
        context.assertTrue(sourceSeen && existingSeen && newSeen, "三個可控bucket均須實際命中");
        context.assertEquals(legacy ? 0 : 1, sourceCalls[0], "只有M6 NEW可求來源 supplier");
        context.assertEquals(0, acquireCalls[0], "SOURCE／EXISTING／legacy與未知NEW拒絕都不偷取得lease");
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_profile", tickLimit=100000)
    public void formal_entry_freezes_policy_three_without_changing_older_sessions(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1);
        context.runAtTick(2, () -> {
            fixture.power();
            var initial = fixture.record();
            context.assertTrue(initial != null, "真拉桿入場須同步寫入 ARMING");
            // 先走既有正式收尾；預期policy斷言失敗不得留下活動fixture。
            fixture.trustedTeardown(3, () -> {
                var policy = initial.candidateContext().orElseThrow();
                context.assertEquals(dev.quantumchamber.superposition.SessionState.ARMING, initial.state(), "正式 ARMING");
                // 舊checked policy1/2的磁碟不升版由SessionJournalSchemaFiveTest具名守住；本案驗正式current入口。
                context.assertEquals(policy.policyVersion(), 3, "FORMAL_ENTRY_POLICY_THREE：正式新session必須凍結policy3");
                context.assertEquals(0, policy.familyCapabilityVersion(), "固定 family0");
                context.assertTrue(initial.candidateLedger().isEmpty(), "ARMING 不偷產 snapshot");
                context.complete();
            });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void formal_entry_freezes_policy_two_on_flat(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1);
        context.runAtTick(2, () -> {
            fixture.power();
            var initial = fixture.record();
            context.assertTrue(initial != null, "真拉桿入場須同步寫入 ARMING");
            fixture.trustedTeardown(3, () -> {
                context.assertEquals(dev.quantumchamber.superposition.SessionState.ARMING, initial.state(), "正式 ARMING");
                var policy = initial.candidateContext().orElseThrow();
                context.assertTrue(policy.isM6(), "FORMAL_ENTRY_POLICY_THREE：新玩家 session 必須凍結 M6 policy3");
                context.assertEquals(policy.policyVersion(), 3, "固定 policy3");
                context.assertEquals(0, policy.familyCapabilityVersion(), "固定 family0");
                context.assertTrue(initial.candidateLedger().isEmpty(), "ARMING 不偷產 snapshot");
                context.complete();
            });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void flat_unknown_new_fails_closed_and_returns(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1);
        var capture = new LogCapture("quantumchamber");
        context.runAtTick(2, () -> {
            fixture.power();
            var initial = fixture.record();
            context.assertTrue(initial != null, "未知 preset 仍允許正式 ARMING");
            awaitUnknownNew(context, fixture, capture, initial, 3, System.nanoTime() + 60_000_000_000L);
        });
    }

    /** 真 batch 的 NEW 失敗須帶分支／候選見證；看到拒因立即斷電，避免重入。 */
    private static void awaitUnknownNew(TestContext context, M2CorridorGameTests.NativeEntry fixture,
            LogCapture capture, dev.quantumchamber.persistence.SessionRecoveryRecord initial, int tick, long deadline) {
        var sid = initial.sessionUuid();
        context.runAtTick(tick, () -> {
            var failures = capture.entries().stream().filter(entry -> entry.message().contains(sid.toString())
                    && entry.message().contains("不支援來源 preset")).toList();
            if (failures.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    fixture.trustedTeardown(tick + 1, () -> {
                        try { context.assertTrue(false, "FLAT_NEW_REJECTION_MISSING：未觀察到正式 NEW 拒因"); }
                        finally { capture.close(); }
                    });
                } else awaitUnknownNew(context, fixture, capture, initial, tick + 1, deadline);
                return;
            }
            fixture.trustedTeardown(tick + 1, () -> {
                try {
                    var event = failures.stream().map(LogCapture.Entry::message)
                            .filter(message -> exactNewWitness(fixture.server, initial, message)).findFirst();
                    context.assertTrue(event.isPresent(), "FLAT_NEW_WITNESS_MISSING：真 batch 的同 SID／DoorKey／bucket／ID fingerprint 必須精確相符");
                    var message = event.orElseThrow();
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replace(sid.toString(), java.util.UUID.randomUUID().toString())),
                            "錯誤 SID 不可採計");
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replaceFirst("candidateIdSha256=[0-9a-f]{64}", "candidateIdSha256=" + "0".repeat(64))),
                            "錯誤 fingerprint 不可採計");
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replaceFirst("bucket=[0-9]+", "bucket=100")),
                            "錯誤 bucket 不可採計");
                    var lease = dev.quantumchamber.universe.Minecraft121SettingsLifecycle.acquire(fixture.server);
                    context.assertTrue(dev.quantumchamber.universe.Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(lease).isEmpty(),
                            "真 flat 不能辨識成 normal/amplified");
                    context.assertTrue(!dev.quantumchamber.persistence.SessionRecoveryState.get(fixture.server).flushedRecords().containsKey(sid),
                            "安全返還後 checked 移除 session");
                    fixture.assertInventory();
                    context.complete();
                } finally { capture.close(); }
            });
        });
    }

    /** 僅 testmod：以既存 entropy 的正式 derive 重算，不讀 secret、不輸出原 ID。 */
    private static boolean exactNewWitness(net.minecraft.server.MinecraftServer server,
            dev.quantumchamber.persistence.SessionRecoveryRecord initial, String message) {
        var pattern = java.util.regex.Pattern.compile("NEW_BUCKET bucket=([0-9]+) door=DoorKey\\[sessionUuid=([0-9a-f-]{36}), logicalStationIndex=(-?[0-9]+), wallSide=(NEGATIVE_LATERAL|POSITIVE_LATERAL)\\] candidateIdSha256=([0-9a-f]{64})");
        var match = pattern.matcher(message);
        if (!match.find() || !message.contains(" <- " + dev.quantumchamber.candidate.CandidateLedgerService.UnknownSourcePresetException.class.getName() + ": 不支援來源 preset，禁止 NEW")
                || !initial.sessionUuid().toString().equals(match.group(2))) return false;
        try {
            var policy = initial.candidateContext().orElseThrow();
            var key = new dev.quantumchamber.corridor.DoorKey(initial.sessionUuid(), Long.parseLong(match.group(3)),
                    dev.quantumchamber.corridor.DoorKey.DoorWallSide.valueOf(match.group(4)));
            var root = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            var entropy = dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, true);
            var derive = dev.quantumchamber.candidate.CandidateEntropyState.class.getDeclaredMethod("derive",
                    dev.quantumchamber.candidate.DerivationDomain.class, dev.quantumchamber.corridor.DoorKey.class, int.class);
            derive.setAccessible(true);
            var bucketBytes = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.BUCKET, key, policy.candidateSchemaVersion());
            int bucket = new java.math.BigInteger(1, bucketBytes.bytes()).mod(java.math.BigInteger.valueOf(100)).intValueExact();
            var pool = dev.quantumchamber.candidate.UniverseDiscoveryState.load(root)
                    .eligibleSnapshot(policy.discoveryWatermark(), policy.sourceFamilyRef());
            if (bucket != Integer.parseInt(match.group(1)) || bucket < policy.sourceWeight()
                    || bucket < policy.sourceWeight() + policy.discoveredWeight() && !pool.isEmpty()) return false;
            var id = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.CANDIDATE_ID, key, policy.candidateSchemaVersion());
            var expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.bytes()));
            return expected.equals(match.group(5));
        } catch (ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("NEW witness 無法重核既存權威", failure);
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_seed_owner", tickLimit=1200)
    public void new_universe_native_seed_settings_and_random_sequences(TestContext context) throws Exception {
        M6RandomProbePhases.verifyGameTest(context.getWorld().getServer());
        context.complete();
    }
}
