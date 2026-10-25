package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StrongholdGenerator;
import net.minecraft.structure.StructureContext;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.structure.StrongholdStructure;
import net.minecraft.world.gen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fabric JUnit 套用正式 mixin；使用兩個原生生成 context，真 ServerWorld 由 M6 GameTest 另證。 */
final class StrongholdGenerationConcurrencyTest {
    private static FlatChunkGenerator generator;

    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        var lookup = BuiltinRegistries.createWrapperLookup();
        var biome = lookup.getWrapperOrThrow(RegistryKeys.BIOME).getOrThrow(BiomeKeys.PLAINS);
        generator = new FlatChunkGenerator(new FlatChunkGeneratorConfig(Optional.empty(), biome, List.of()));
    }

    private static Structure.Context context(long seed, int x, int z) {
        return new Structure.Context(DynamicRegistryManager.EMPTY, generator, generator.getBiomeSource(), null, null,
                new ChunkRandom(new CheckedRandom(0)), seed, new ChunkPos(x, z), HeightLimitView.create(-64, 384), biome -> true);
    }

    @Test void twoWorldStrongholdsDoNotSharePieceCounts() throws InterruptedException {
        var result = NativeHarness.race(context(8128, 32, 32), context(481516, -32, -32));
        result.requireSafe();
    }

    @Test void singleWorldStrongholdLayoutUnchanged() {
        var baseline = NativeHarness.generate(context(8128, 32, 32));
        var expected = NativeHarness.layout(baseline);
        assertEquals("a80452213409b05c039da03127cc9864ae6f779722541e1256336131bb9bd21a", expected, "修正前原版固定seed的完整piece NBT基準");
        assertEquals(98, baseline.toList().pieces().size(), "修正前原版piece總數");
        System.out.println("M6_STRONGHOLD_BASELINE seed=8128 chunk=32,32 sha256=" + expected + " count=" + baseline.toList().pieces().size() + " counts=" + NativeHarness.pieceCounts(baseline));
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32))), "同一世界的固定seed布局漂移");
        }
        assertTrue(baseline.toList().pieces().stream().anyMatch(piece -> piece.getClass().getSimpleName().equals("PortalRoom")), "原版重試必須產生PortalRoom");
    }

    @Test void exceptionReleasesInvocationAndWorkerCanBeReused() throws InterruptedException {
        var expected = NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32)));
        var failure = new AtomicReference<Throwable>();
        var actual = new AtomicReference<String>();
        var worker = NativeHarness.worker("M6Stronghold-reused-worker", () -> {
            var injected = assertThrows(IllegalArgumentException.class, () -> NativeHarness.generate(context(8128, 32, 32), new StructurePiecesCollector() {
                @Override public void addPiece(StructurePiece piece) { super.addPiece(piece); throw new IllegalArgumentException("M6_CONTROLLED_COLLECTOR_FAILURE"); }
            }));
            assertEquals("M6_CONTROLLED_COLLECTOR_FAILURE", injected.getMessage());
            actual.set(NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32))));
            assertEquals(expected, actual.get(), "例外後同worker的下一任務布局漂移");
        }, failure);
        NativeHarness.join(worker); NativeHarness.rethrow(failure.get());
        // 改用另一worker也須完成，防止例外僅在同一可重入monitor owner上掩蓋漏鎖。
        var other = NativeHarness.worker("M6Stronghold-after-exception-worker", () -> actual.set(NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32)))), failure);
        NativeHarness.join(other); NativeHarness.rethrow(failure.get());
        assertEquals(expected, actual.get(), "例外後worker重用或另一worker產生布局漂移");
    }

    /** 僅JUnit的原版呼叫/有界collector屏障；不改正式classpath或測試mod載入。 */
    public static final class NativeHarness {
        private static final long WAIT_SECONDS = 10;
        private static final Method ADD_PIECES = method();
        private static final Field ALL = field(StrongholdGenerator.class, "ALL_PIECES");
        private static final Field POSSIBLE = field(StrongholdGenerator.class, "possiblePieces");
        private static final Field ACTIVE = field(StrongholdGenerator.class, "activePieceType");
        private static final Field WEIGHT = field(StrongholdGenerator.class, "totalWeight");
        private static final Field COUNT = field(ALL.getType().getComponentType(), "generatedCount");
        private static final StructureContext NBT_CONTEXT = new StructureContext(ResourceManager.Empty.INSTANCE, DynamicRegistryManager.EMPTY, null);

        private NativeHarness() { }

        private static Method method() {
            try {
                var method = StrongholdStructure.class.getDeclaredMethod("addPieces", StructurePiecesCollector.class, Structure.Context.class);
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

        public static <T extends StructurePiecesCollector> T generate(Structure.Context context, T collector) {
            try { ADD_PIECES.invoke(null, collector, context); return collector; }
            catch (InvocationTargetException failure) { rethrow(failure.getCause()); throw new AssertionError("unreachable"); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
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
            var expectedA = layout(generate(first)); var expectedB = layout(generate(second));
            var paused = new PausedCollector();
            var actualB = new AtomicReference<String>(); var failure = new AtomicReference<Throwable>();
            var bDone = new AtomicBoolean();
            var a = worker("M6Stronghold-world-A", () -> generate(first, paused), failure);
            Thread b = null;
            String observed;
            try {
                await(paused.paused, "A 尚未到原生 count 屏障");
                b = worker("M6Stronghold-world-B", () -> {
                    try { actualB.set(layout(generate(second))); } finally { bDone.set(true); }
                }, failure);
                observed = awaitBlockedOrFinished(b, bDone, a, "two-contexts");
            } finally {
                paused.release.countDown();
                join(a); if (b != null) join(b);
            }
            rethrow(failure.get());
            var result = new RaceResult(paused.before, paused.after, expectedA, layout(paused), expectedB, actualB.get(), observed);
            System.out.println("M6_STRONGHOLD_NATIVE " + result + " countsA=" + pieceCounts(paused));
            return result;
        }

        public record RaceResult(String before, String after, String expectedA, String actualA,
                                 String expectedB, String actualB, String observation) {
            public void requireSafe() {
                if (!before.equals(after)) throw new AssertionError("STRONGHOLD_SHARED_STATE_CROSSED：" + this);
                if (!expectedA.equals(actualA) || !expectedB.equals(actualB)) throw new AssertionError("STRONGHOLD_LAYOUT_CHANGED：" + this);
            }
        }

        public static final class PausedCollector extends StructurePiecesCollector {
            public final CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
            public volatile String before, after;
            private int added;
            private boolean held;
            @Override public void addPiece(StructurePiece piece) {
                super.addPiece(piece);
                if (!held && ++added == 12) {
                    held = true; before = state(); paused.countDown();
                    try { await(release, "A count 屏障未被釋放"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    after = state();
                }
            }
        }

        @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }

        public static Thread worker(String name, ThrowingRunnable action, AtomicReference<Throwable> failure) {
            var thread = new Thread(() -> { try { action.run(); } catch (Throwable problem) { failure.compareAndSet(null, problem); } }, name);
            thread.setDaemon(true); thread.start(); return thread;
        }

        public static void await(CountDownLatch latch, String message) throws InterruptedException {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("STRONGHOLD_TIMEOUT：" + message);
        }

        public static String awaitBlockedOrFinished(Thread thread, AtomicBoolean done, Thread owner, String label) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!done.get()) {
                if (thread.getState() == Thread.State.BLOCKED && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(StrongholdStructure.class.getName()) && frame.getMethodName().contains("addPieces"))) {
                    var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), Integer.MAX_VALUE);
                    try {
                        var monitor = field(StrongholdStructure.class, "quantumchamber$invocationLock").get(null);
                        if (info == null || info.getLockInfo() == null || info.getLockOwnerId() != owner.threadId()
                                || info.getLockInfo().getIdentityHashCode() != System.identityHashCode(monitor)) {
                            throw new AssertionError("STRONGHOLD_WRONG_MONITOR_OWNER：" + info);
                        }
                    } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
                    System.out.println("M6_STRONGHOLD_THREAD label=" + label + " thread=" + thread.getName() + " owner=" + owner.getName() + " monitor=" + info.getLockInfo() + " info=" + info);
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
}
