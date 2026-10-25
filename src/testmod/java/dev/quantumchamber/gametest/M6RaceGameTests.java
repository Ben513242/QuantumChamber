package dev.quantumchamber.gametest;

import dev.quantumchamber.gametest.M6RaceGameTests.NativeHarness.Kind;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StrongholdGenerator;
import net.minecraft.structure.NetherFortressGenerator;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StrongholdStructure;
import net.minecraft.world.gen.structure.NetherFortressStructure;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

/** M6 結構的原生整輪競態；所有屏障只在測試 collector，沒有 production 故障 hook。 */
public final class M6RaceGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_stronghold", tickLimit=1200)
    public void strongholds_in_two_real_worlds_keep_native_state(TestContext context) {
        realWorldRace(context, Kind.STRONGHOLD);
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_fortress", tickLimit=1200)
    public void fortresses_in_two_real_worlds_keep_native_state(TestContext context) {
        realWorldRace(context, Kind.FORTRESS);
    }

    private void realWorldRace(TestContext context, Kind kind) {
        try {
            var overworld = context.getWorld().getServer().getOverworld();
            var nether = context.getWorld().getServer().getWorld(World.NETHER);
            context.assertTrue(nether != null && overworld != nether, "Stronghold 必須使用兩個實際 ServerWorld");
            var result = NativeHarness.race(worldContext(overworld, 8128, new ChunkPos(32, 32)),
                    worldContext(nether, 481516, new ChunkPos(-32, -32)), kind);
            System.out.println("M6_" + kind + "_REAL_WORLDS worlds=" + overworld.getRegistryKey().getValue() + "," + nether.getRegistryKey().getValue() + " " + result);
            result.requireSafe();
            context.complete();
        } catch (Exception failure) { throw new IllegalStateException("M6 " + kind + " 雙真世界驗證失敗", failure); }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_stronghold", tickLimit=1200)
    public void place_on_server_thread_and_worker_finish_without_deadlock(TestContext context) {
        placeWithWorker(context, Kind.STRONGHOLD);
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_fortress", tickLimit=1200)
    public void fortress_place_on_server_thread_and_worker_finish_without_deadlock(TestContext context) {
        placeWithWorker(context, Kind.FORTRESS);
    }

    private void placeWithWorker(TestContext context, Kind kind) {
        var server = context.getWorld().getServer();
        var world = kind == Kind.FORTRESS ? server.getWorld(World.NETHER) : server.getOverworld();
        var workerWorld = kind == Kind.FORTRESS ? server.getOverworld() : server.getWorld(World.NETHER);
        context.assertTrue(server.isOnThread() && world != null && workerWorld != null && world != workerWorld, "真 /place 必須在 server thread，worker 使用另一實際世界");
        try {
            var pos = new BlockPos(1024, 64, 1024);
            preparePlaceChunks(context, world, kind == Kind.STRONGHOLD ? StructureKeys.STRONGHOLD : StructureKeys.FORTRESS, pos, kind.toString());
            var workerContext = worldContext(workerWorld, 481516, new ChunkPos(-32, -32));
            var expected = NativeHarness.layout(NativeHarness.generate(workerContext, kind));
            var paused = new NativeHarness.PausedCollector(kind);
            var failure = new AtomicReference<Throwable>();
            var worker = NativeHarness.worker("M6" + kind + "-place-worker", () -> NativeHarness.generate(workerContext, paused, kind), failure);
            var commandDone = new AtomicBoolean();
            var serverThread = Thread.currentThread();
            var monitor = new AtomicReference<Thread>();
            var value = new int[1];
            NativeHarness.withCleanup(() -> {
                NativeHarness.await(paused.paused, "worker 尚未到原生 count 屏障");
                monitor.set(NativeHarness.worker("M6" + kind + "-place-observer", () -> {
                    try { NativeHarness.awaitBlockedOrFinished(serverThread, commandDone, worker, "place", kind); }
                    finally { paused.release.countDown(); }
                }, failure));
                value[0] = server.getCommandManager().getDispatcher().execute(
                        "place structure minecraft:" + (kind == Kind.STRONGHOLD ? "stronghold" : "fortress") + " 1024 64 1024", server.getCommandSource().withWorld(world).withLevel(4));
            }, () -> { commandDone.set(true); paused.release.countDown(); },
                    () -> NativeHarness.join(worker), () -> { if (monitor.get() != null) NativeHarness.join(monitor.get()); });
            NativeHarness.rethrow(failure.get());
            System.out.println("M6_" + kind + "_PLACE_RESULT serverThread=" + serverThread.getName() + " result=" + value[0] + " expected=" + expected + " actual=" + NativeHarness.layout(paused) + " stateBefore=" + paused.before + " stateAfter=" + paused.after);
            context.assertTrue(value[0] == 1 && paused.before.equals(paused.after) && expected.equals(NativeHarness.layout(paused)),
                    "M6 " + kind + " /place 汙染 worker state/layout");
            System.out.println("M6_" + kind + "_PLACE PASS serverThread=" + serverThread.getName() + " worker=" + worker.getName() + " result=" + value[0] + " stateBefore=" + paused.before + " stateAfter=" + paused.after);
            context.complete();
        } catch (Exception failure) { throw new IllegalStateException("M6 " + kind + " /place 交錯驗證失敗", failure); }
    }


    private static void preparePlaceChunks(TestContext context, ServerWorld world,
            net.minecraft.registry.RegistryKey<Structure> structureKey, BlockPos pos, String kind) {
        // 在 worker 屏障之前預載原版 StructureStart 的 exact bbox；place內不補等 chunk。
        var generator = world.getChunkManager().getChunkGenerator();
        var structure = world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(structureKey).orElseThrow().value();
        var prepared = structure.createStructureStart(world.getRegistryManager(), generator, generator.getBiomeSource(),
                world.getChunkManager().getNoiseConfig(), world.getStructureTemplateManager(), world.getSeed(), new ChunkPos(pos), 0, world, biome -> true);
        // StructureStart還會依terrain adaptation擴張box；不可只載入collector的piece box。
        var box = prepared.getBoundingBox();
        world.getChunk(pos);
        System.out.println("M6_" + kind + "_PLACE_PREFLIGHT targetLoaded=" + world.isChunkLoaded(pos) + " box=" + box);
        for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) {
            for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) world.getChunk(x, z);
        }
        for (int x = box.getMinX() >> 4; x <= box.getMaxX() >> 4; x++) {
            for (int z = box.getMinZ() >> 4; z <= box.getMaxZ() >> 4; z++) {
                context.assertTrue(world.isChunkLoaded(new BlockPos(x << 4, 64, z << 4)), "原版/place需要的bbox chunk未載入");
            }
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_endcity", tickLimit=1200)
    public void end_cities_in_two_real_worlds_keep_ship_choice(TestContext context) {
        var server = context.getWorld().getServer();
        var first = server.getWorld(World.END); var second = server.getOverworld();
        context.assertTrue(first != null && first != second, "End city 必須使用兩個實際 ServerWorld");
        try {
            var result = EndCityHarness.race(worldContext(first, 481516, new ChunkPos(-32, -32)),
                    worldContext(second, 8128, new ChunkPos(32, 32)));
            System.out.println("M6_END_CITY_REAL_WORLDS worlds=" + first.getRegistryKey().getValue() + "," + second.getRegistryKey().getValue() + " " + result);
            result.requireSafe(); context.complete();
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_race_endcity", tickLimit=1200)
    public void end_city_place_on_server_thread_and_worker_finish_without_deadlock(TestContext context) {
        var server = context.getWorld().getServer(); var world = server.getWorld(World.END); var workerWorld = server.getOverworld();
        context.assertTrue(server.isOnThread() && world != null && world != workerWorld, "真End city/place必須在server thread");
        try {
            var pos = new BlockPos(1024, 80, 1024);
            preparePlaceChunks(context, world, StructureKeys.END_CITY, pos, "END_CITY");
            var workerContext = worldContext(workerWorld, 481516, new ChunkPos(-32, -32));
            var expected = EndCityHarness.generate(workerContext).signature();
            var paused = new EndCityHarness.PausedRandom(); var actual = new AtomicReference<String>();
            var failure = new AtomicReference<Throwable>();
            var worker = NativeHarness.worker("M6EndCity-place-worker", () -> actual.set(EndCityHarness.generate(workerContext, paused).signature()), failure);
            var value = new int[1];
            NativeHarness.withCleanup(() -> {
                NativeHarness.await(paused.paused, "End city worker未到船隻RNG屏障");
                value[0] = server.getCommandManager().getDispatcher().execute("place structure minecraft:end_city 1024 80 1024",
                        server.getCommandSource().withWorld(world).withLevel(4));
                context.assertTrue(paused.release.getCount() == 1, "server /place 必須在worker仍暫停時完成");
            }, paused.release::countDown, () -> NativeHarness.join(worker));
            NativeHarness.rethrow(failure.get());
            context.assertTrue(value[0] == 1 && paused.before == paused.after && expected.equals(actual.get()), "End city/place 污染worker船隻/NBT/RNG或未完成");
            System.out.println("M6_END_CITY_PLACE PASS serverThread=" + Thread.currentThread().getName() + " worker=" + worker.getName()
                    + " result=" + value[0] + " stateBefore=" + paused.before + " stateAfter=" + paused.after + " expected=" + expected + " actual=" + actual.get());
            context.complete();
        } catch (Exception failure) { throw new IllegalStateException("M6 End city /place 驗證失敗", failure); }
    }

    private static Structure.Context worldContext(ServerWorld world, long seed, ChunkPos pos) {
        var generator = world.getChunkManager().getChunkGenerator();
        return new Structure.Context(world.getRegistryManager(), generator, generator.getBiomeSource(),
                world.getChunkManager().getNoiseConfig(), world.getStructureTemplateManager(),
                new ChunkRandom(new CheckedRandom(0)), seed, pos, world, biome -> true);
    }

    /** JUnit 與真 GameTest 共用原版呼叫/屏障；不含 generator 的複製實作或 JUnit 依賴。 */
    public static final class NativeHarness {
        private static final long WAIT_SECONDS = 10;
        public enum Kind {
            STRONGHOLD(StrongholdStructure.class), FORTRESS(NetherFortressStructure.class);
            private final Class<?> structureClass;
            private final Method addPieces;
            Kind(Class<?> structureClass) { this.structureClass = structureClass; this.addPieces = method(structureClass); }
        }
        private static final Field ALL = field(StrongholdGenerator.class, "ALL_PIECES");
        private static final Field POSSIBLE = field(StrongholdGenerator.class, "possiblePieces");
        private static final Field ACTIVE = field(StrongholdGenerator.class, "activePieceType");
        private static final Field WEIGHT = field(StrongholdGenerator.class, "totalWeight");
        private static final Field COUNT = field(ALL.getType().getComponentType(), "generatedCount");
        private static final Field BRIDGE = field(NetherFortressGenerator.class, "ALL_BRIDGE_PIECES");
        private static final Field CORRIDOR = field(NetherFortressGenerator.class, "ALL_CORRIDOR_PIECES");
        private static final Field FORTRESS_COUNT = field(BRIDGE.getType().getComponentType(), "generatedCount");
        private static final StructureContext NBT_CONTEXT = new StructureContext(ResourceManager.Empty.INSTANCE, DynamicRegistryManager.EMPTY, null);

        private NativeHarness() { }

        private static Method method(Class<?> owner) {
            try {
                var method = owner.getDeclaredMethod("addPieces", StructurePiecesCollector.class, Structure.Context.class);
                method.setAccessible(true); return method;
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }

        private static Field field(Class<?> owner, String name) {
            try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }

        public static StructurePiecesCollector generate(Structure.Context context) {
            return generate(context, new StructurePiecesCollector());
        }

        public static StructurePiecesCollector generate(Structure.Context context, Kind kind) {
            return generate(context, new StructurePiecesCollector(), kind);
        }

        public static <T extends StructurePiecesCollector> T generate(Structure.Context context, T collector) {
            return generate(context, collector, Kind.STRONGHOLD);
        }

        public static <T extends StructurePiecesCollector> T generate(Structure.Context context, T collector, Kind kind) {
            // Fortress不重設傳入random；每輪使用原版9參數constructor的setCarverSeed契約。
            var invocation = kind == Kind.FORTRESS ? new Structure.Context(context.dynamicRegistryManager(),
                    context.chunkGenerator(), context.biomeSource(), context.noiseConfig(), context.structureTemplateManager(),
                    context.seed(), context.chunkPos(), context.world(), context.biomePredicate()) : context;
            try { kind.addPieces.invoke(null, collector, invocation); return collector; }
            catch (InvocationTargetException failure) { rethrow(failure.getCause()); throw new AssertionError("unreachable"); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        private static String counts(Field array, Field count) throws IllegalAccessException {
            var all = (Object[]) array.get(null);
            var counts = new int[all.length];
            for (int i = 0; i < all.length; i++) counts[i] = count.getInt(all[i]);
            return Arrays.toString(counts);
        }

        public static String state(Kind kind) {
            if (kind == Kind.STRONGHOLD) return state();
            try { return "bridge=" + counts(BRIDGE, FORTRESS_COUNT) + ";corridor=" + counts(CORRIDOR, FORTRESS_COUNT); }
            catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }

        public static String state() {
            try {
                var all = (Object[]) ALL.get(null);
                var counts = new int[all.length];
                for (int i = 0; i < all.length; i++) counts[i] = COUNT.getInt(all[i]);
                var possible = (List<?>) POSSIBLE.get(null);
                var active = (Class<?>) ACTIVE.get(null);
                return "counts=" + Arrays.toString(counts) + ";possible=" + possible.size()
                        + ";listIdentity=" + System.identityHashCode(possible) + ";active=" + (active == null ? "null" : active.getSimpleName())
                        + ";weight=" + WEIGHT.getInt(null);
            } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }

        public static String layout(StructurePiecesCollector collector) {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                for (var piece : collector.toList().pieces()) {
                    digest.update(piece.toNbt(NBT_CONTEXT).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
        }

        public static Map<String, Integer> pieceCounts(StructurePiecesCollector collector) {
            var counts = new TreeMap<String, Integer>();
            for (var piece : collector.toList().pieces()) counts.merge(piece.getClass().getSimpleName(), 1, Integer::sum);
            return Map.copyOf(counts);
        }

        public static RaceResult race(Structure.Context first, Structure.Context second) throws InterruptedException {
            return race(first, second, Kind.STRONGHOLD);
        }

        public static RaceResult race(Structure.Context first, Structure.Context second, Kind kind) throws InterruptedException {
            var expectedA = layout(generate(first, kind)); var expectedB = layout(generate(second, kind));
            var paused = new PausedCollector(kind);
            var actualB = new AtomicReference<String>(); var failure = new AtomicReference<Throwable>();
            var bDone = new AtomicBoolean();
            var a = worker("M6" + kind + "-world-A", () -> generate(first, paused, kind), failure);
            var b = new AtomicReference<Thread>();
            var observed = new AtomicReference<String>();
            withCleanup(() -> {
                await(paused.paused, "A 尚未到原生 count 屏障");
                b.set(worker("M6" + kind + "-world-B", () -> {
                    try { actualB.set(layout(generate(second, kind))); } finally { bDone.set(true); }
                }, failure));
                observed.set(awaitBlockedOrFinished(b.get(), bDone, a, "two-contexts", kind));
            }, paused.release::countDown, () -> join(a), () -> { if (b.get() != null) join(b.get()); });
            rethrow(failure.get());
            var result = new RaceResult(kind, paused.before, paused.after, expectedA, layout(paused), expectedB, actualB.get(), observed.get());
            System.out.println("M6_" + kind + "_NATIVE " + result + " countsA=" + pieceCounts(paused));
            return result;
        }

        public record RaceResult(Kind kind, String before, String after, String expectedA, String actualA,
                                 String expectedB, String actualB, String observation) {
            public void requireSafe() {
                if (!before.equals(after)) throw new AssertionError(kind + "_SHARED_STATE_CROSSED：" + this);
                if (!expectedA.equals(actualA) || !expectedB.equals(actualB)) throw new AssertionError(kind + "_LAYOUT_CHANGED：" + this);
            }
        }

        public static final class PausedCollector extends StructurePiecesCollector {
            public final CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
            public volatile String before, after;
            private final Kind kind;
            private int added;
            private boolean held;
            public PausedCollector() { this(Kind.STRONGHOLD); }
            public PausedCollector(Kind kind) { this.kind = kind; }
            @Override public void addPiece(StructurePiece piece) {
                super.addPiece(piece);
                if (!held && ++added == 12) {
                    held = true; before = state(kind); paused.countDown();
                    try { await(release, "A count 屏障未被釋放"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    after = state(kind);
                }
            }
        }

        @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }

        /** 統一測試屏障的失敗清理；每個 join 仍有原本的有界等待。 */
        public static void withCleanup(ThrowingRunnable action, Runnable release, ThrowingRunnable... joins) throws InterruptedException {
            Throwable problem = null;
            try { action.run(); } catch (Throwable failure) { problem = failure; }
            try { release.run(); } catch (Throwable failure) { problem = appendFailure(problem, failure); }
            for (var join : joins) {
                try { join.run(); } catch (Throwable failure) { problem = appendFailure(problem, failure); }
            }
            if (problem instanceof InterruptedException interrupted) throw interrupted;
            rethrow(problem);
        }

        private static Throwable appendFailure(Throwable primary, Throwable cleanup) {
            if (primary == null) return cleanup;
            if (primary != cleanup) primary.addSuppressed(cleanup);
            return primary;
        }

        public static Thread worker(String name, ThrowingRunnable action, AtomicReference<Throwable> failure) {
            var thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.compareAndSet(null, problem); } }, name);
            thread.setDaemon(true); thread.start(); return thread;
        }

        public static void await(CountDownLatch latch, String message) throws InterruptedException {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("STRONGHOLD_TIMEOUT：" + message);
        }

        public static String awaitBlockedOrFinished(Thread thread, AtomicBoolean done, Thread owner, String label) {
            return awaitBlockedOrFinished(thread, done, owner, label, Kind.STRONGHOLD);
        }

        public static String awaitBlockedOrFinished(Thread thread, AtomicBoolean done, Thread owner, String label, Kind kind) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!done.get()) {
                if (thread.getState() == Thread.State.BLOCKED && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(kind.structureClass.getName()) && frame.getMethodName().contains("addPieces"))) {
                    var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), Integer.MAX_VALUE);
                    try {
                        var monitor = field(kind.structureClass, "quantumchamber$invocationLock").get(null);
                        if (info == null || info.getLockInfo() == null || info.getLockOwnerId() != owner.threadId()
                                || info.getLockInfo().getIdentityHashCode() != System.identityHashCode(monitor)) {
                            throw new AssertionError(kind + "_WRONG_MONITOR_OWNER：" + info);
                        }
                    } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
                    System.out.println("M6_" + kind + "_THREAD label=" + label + " thread=" + thread.getName() + " owner=" + owner.getName() + " monitor=" + info.getLockInfo() + " info=" + info);
                    return "BLOCKED_PRODUCTION_MONITOR_OWNER_A";
                }
                if (System.nanoTime() > deadline) throw new AssertionError("STRONGHOLD_TIMEOUT：等待 invocation 阻擋或完成 " + label);
                Thread.onSpinWait();
            }
            return "FINISHED_WHILE_A_PAUSED";
        }

        public static void join(Thread thread) throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            if (thread.isAlive()) throw new AssertionError("STRONGHOLD_WORKER_LEAK：" + thread.getName());
        }

        public static void rethrow(Throwable failure) {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException(failure);
        }
    }

    /** End city 保留五參數原版入口；共用既有 worker/cleanup，屏障只在委派 RNG。 */
    public static final class EndCityHarness {
        private static final Field BRIDGE = NativeHarness.field(net.minecraft.structure.EndCityGenerator.class, "BRIDGE_PIECE");
        private static final Field SHIP = NativeHarness.field(bridge().getClass(), "shipGenerated");
        private EndCityHarness() { }

        private static Object bridge() {
            try { return BRIDGE.get(null); } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }

        public static boolean shipState() {
            try {
                boolean nativeValue = SHIP.getBoolean(bridge());
                try {
                    var state = Class.forName("dev.quantumchamber.universe.EndCityInvocationState");
                    return (boolean) state.getMethod("read", boolean.class).invoke(null, nativeValue);
                } catch (ClassNotFoundException originalRuntime) { return nativeValue; }
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        public record City(StructurePiecesCollector collector, long rngTail, net.minecraft.util.BlockRotation rotation) {
            public String signature() {
                return NativeHarness.layout(collector) + ";count=" + collector.toList().pieces().size()
                        + ";ships=" + ships() + ";rng=" + rngTail + ";rotation=" + rotation;
            }
            public long ships() {
                return collector.toList().pieces().stream().filter(piece -> piece.toNbt(NativeHarness.NBT_CONTEXT).getString("Template").equals("ship")).count();
            }
        }

        public static City generate(Structure.Context context) { return generate(context, null); }

        public static City generate(Structure.Context context, PausedRandom observer) {
            // 與原版 EndCityStructure 同樣先用 fresh carver random 選 rotation；位置固定測試 y80。
            var invocation = new Structure.Context(context.dynamicRegistryManager(), context.chunkGenerator(), context.biomeSource(),
                    context.noiseConfig(), context.structureTemplateManager(), context.seed(), context.chunkPos(), context.world(), context.biomePredicate());
            var random = invocation.random();
            var rotation = net.minecraft.util.BlockRotation.random(random);
            var pos = new BlockPos(context.chunkPos().getOffsetX(7), 80, context.chunkPos().getOffsetZ(7));
            var pieces = new java.util.ArrayList<StructurePiece>();
            if (observer != null) observer.delegate = random;
            net.minecraft.structure.EndCityGenerator.addPieces(context.structureTemplateManager(), pos, rotation, pieces,
                    observer == null ? random : observer);
            var collector = new StructurePiecesCollector(); pieces.forEach(collector::addPiece);
            return new City(collector, random.nextLong(), rotation);
        }

        public record CityRace(boolean before, boolean after, String expectedA, String actualA, String expectedB, String actualB) {
            public void requireSafe() {
                if (before != after) throw new AssertionError("END_CITY_SHARED_SHIP_STATE_CROSSED：" + this);
                if (!expectedA.equals(actualA) || !expectedB.equals(actualB)) throw new AssertionError("END_CITY_SHIP_LAYOUT_OR_RNG_CHANGED：" + this);
            }
        }

        public static CityRace race(Structure.Context first, Structure.Context second) throws InterruptedException {
            var expectedA = generate(first).signature(); var expectedB = generate(second).signature();
            var paused = new PausedRandom();
            var actualA = new AtomicReference<String>(); var actualB = new AtomicReference<String>();
            var failure = new AtomicReference<Throwable>(); var b = new AtomicReference<Thread>();
            var a = NativeHarness.worker("M6EndCity-world-A", () -> actualA.set(generate(first, paused).signature()), failure);
            NativeHarness.withCleanup(() -> {
                NativeHarness.await(paused.paused, "End city A 尚未產船並到下一個 RNG 呼叫");
                b.set(NativeHarness.worker("M6EndCity-world-B", () -> actualB.set(generate(second).signature()), failure));
                NativeHarness.join(b.get());
            }, paused.release::countDown, () -> NativeHarness.join(a), () -> { if (b.get() != null) NativeHarness.join(b.get()); });
            NativeHarness.rethrow(failure.get());
            var result = new CityRace(paused.before, paused.after, expectedA, actualA.get(), expectedB, actualB.get());
            System.out.println("M6_END_CITY_NATIVE " + result); return result;
        }

        /** 不改值、不多消耗 RNG；ship=true 後下一個原版 RNG 呼叫才暫停。 */
        public static final class PausedRandom implements net.minecraft.util.math.random.Random {
            public final CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
            public volatile boolean before, after;
            private net.minecraft.util.math.random.Random delegate;
            private boolean held;
            private final Runnable onShip;
            public PausedRandom() { this(null); }
            public PausedRandom(Runnable onShip) { this.onShip = onShip; }
            private void beforeNext() {
                if (!held && shipState()) {
                    held = true; before = shipState(); paused.countDown();
                    try { if (onShip == null) NativeHarness.await(release, "End city RNG 屏障未被釋放"); else onShip.run(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    after = shipState();
                }
            }
            public net.minecraft.util.math.random.Random split() { beforeNext(); return delegate.split(); }
            public net.minecraft.util.math.random.RandomSplitter nextSplitter() { beforeNext(); return delegate.nextSplitter(); }
            public void setSeed(long seed) { delegate.setSeed(seed); }
            public int nextInt() { beforeNext(); return delegate.nextInt(); }
            public int nextInt(int bound) { beforeNext(); return delegate.nextInt(bound); }
            public long nextLong() { beforeNext(); return delegate.nextLong(); }
            public boolean nextBoolean() { beforeNext(); return delegate.nextBoolean(); }
            public float nextFloat() { beforeNext(); return delegate.nextFloat(); }
            public double nextDouble() { beforeNext(); return delegate.nextDouble(); }
            public double nextGaussian() { beforeNext(); return delegate.nextGaussian(); }
        }
    }
}
