package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M5 H6（spec §4 H6、§20.12；根因見 M5 implementation note 的 H6 Step 0 一節）：結構範本快取的同時使用的單元層級回歸測試。
 * <p>原版的 {@code StructureTemplate.PalettedBlockInfoList.getAllOf} 在第一次用到某個方塊時，以 {@code HashMap.computeIfAbsent} 計算並插入。來源世界與 U 共用 server
 * 的 {@code StructureTemplateManager}，也就共用同一個 template；兩個世界的 worldgen mailbox 在不同的 worker 上同時第一次計算時，先算完的一方插入，後算完的一方丟
 * {@link ConcurrentModificationException}（同一個 key 或不同的 key 都會）。production 的 {@code PalettedBlockInfoListConcurrentCacheMixin} 把這個 map 換成
 * {@code ConcurrentHashMap}；fabric-loader-junit 會套用 production mixin（同 {@code NativeSaveFailuresTest}），所以這裡驗的是 production 的類別。
 * <p>作法：template 以 public 的 {@code readNbt} 由合成的 NBT 建立（{@value #SX}×{@value #SY}×{@value #SZ}：70% stone，jigsaw、structure_block、glass、oak_planks
 * 各 7.5%），每一輪一個新的 template（快取是空的）。工作執行緒以輪次的計數器 spin 等待，同一輪同時呼叫 {@code getInfosForBlock}（每個執行緒一個 key）；主執行緒等它們
 * 做完，再單執行緒核對五個 key。三個情境各 {@value #ROUNDS} 輪。判準：CME 0、內容錯誤 0、其他例外 0、事後的單執行緒核對錯誤 0、全部輪次都做完；每個等待都有期限，
 * 逾時判失敗。
 * <p>沒有修正時，單輪 CME 的機率至少約 86%（H6 Step 0 的 u-red-1），{@value #ROUNDS} 輪都沒有 CME 的機率可忽略。「兩個執行緒的計算確實重疊」只是診斷：與
 * {@code availableProcessors}、快取的實際型別一起輸出到 stdout（{@code H6_TEMPLATE_CACHE}，test report 的 system-out），不當通過的條件，因為核心數少的機器不一定重疊。
 * 只有一個處理器時看不出 RED。
 */
final class StructureTemplateCacheConcurrencyTest {
    private static final int SX = 64, SY = 2, SZ = 64;
    private static final int ROUNDS = 200;
    /** 主執行緒等一輪做完的期限。 */
    private static final long ROUND_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** 工作執行緒等下一輪的期限（主執行緒失敗或停住時，工作執行緒自己結束）。 */
    private static final long WORKER_IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** 結束之後等工作執行緒結束的期限。 */
    private static final long JOIN_TIMEOUT_MILLIS = 5_000;
    private static Block[] palette;
    private static int[] expected;
    private static NbtCompound nbt;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        palette = new Block[] {Blocks.STONE, Blocks.JIGSAW, Blocks.STRUCTURE_BLOCK, Blocks.GLASS, Blocks.OAK_PLANKS};
        expected = new int[palette.length];
        for (int i = 0; i < SX * SY * SZ; i++) expected[stateAt(i)]++;
        nbt = templateNbt();
    }

    @Test
    void concurrentFirstUseOfJigsawAndStructureBlockIsSafe() throws InterruptedException {
        expectSafe("jigsaw+structure_block", Blocks.JIGSAW, Blocks.STRUCTURE_BLOCK);
    }

    @Test
    void concurrentFirstUseOfTheSameBlockIsSafe() throws InterruptedException {
        expectSafe("jigsaw+jigsaw", Blocks.JIGSAW, Blocks.JIGSAW);
    }

    @Test
    void concurrentFirstUseOfFourBlocksIsSafe() throws InterruptedException {
        expectSafe("jigsaw+structure_block+glass+oak_planks", Blocks.JIGSAW, Blocks.STRUCTURE_BLOCK, Blocks.GLASS, Blocks.OAK_PLANKS);
    }

    private static void expectSafe(String scenario, Block... keys) throws InterruptedException {
        var result = race(keys);
        System.out.println("H6_TEMPLATE_CACHE scenario=" + scenario + " availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + " cacheMap=" + result.cacheMap + " " + result);
        assertAll(scenario,
                () -> assertEquals(0, result.cme, () -> "PalettedBlockInfoList 的快取在同時第一次使用時丟出 ConcurrentModificationException：" + result),
                () -> assertEquals(0, result.wrong, () -> "同時第一次使用時回傳的內容錯誤：" + result),
                () -> assertEquals(0, result.other, () -> "同時第一次使用時丟出其他例外：" + result + " first=" + result.firstOther),
                () -> assertEquals(0, result.postWrong, () -> "事後的單執行緒核對錯誤：" + result),
                () -> assertEquals(0, result.timeouts, () -> "等待逾時：" + result),
                () -> assertEquals(ROUNDS, result.rounds, () -> "沒有做完全部輪次：" + result),
                () -> assertEquals(0, result.workersAlive, () -> "工作執行緒沒有結束：" + result));
    }

    /** 一個情境的結果；{@link #toString} 是 stdout 的診斷。 */
    private static final class Result {
        long cme, roundsWithCme, wrong, other, postWrong, timeouts, roundsWithOverlap, rounds, workersAlive, elapsedMillis;
        Throwable firstOther;
        String cacheMap = "?";

        @Override public String toString() {
            return "rounds=" + rounds + " cme=" + cme + " roundsWithCme=" + roundsWithCme + " wrong=" + wrong + " other=" + other + " postWrong=" + postWrong
                    + " timeouts=" + timeouts + " workersAlive=" + workersAlive + " roundsWithOverlap=" + roundsWithOverlap + " elapsedMs=" + elapsedMillis;
        }
    }

    /** {@code keys.length} 個工作執行緒，每一輪對同一個新的 template 同時第一次呼叫 {@code getInfosForBlock}（執行緒 t 用 {@code keys[t]}）。 */
    private static Result race(Block[] keys) throws InterruptedException {
        int n = keys.length;
        var result = new Result();
        var current = new AtomicReference<StructureTemplate>();
        var round = new AtomicInteger();
        var done = new AtomicInteger();
        var stop = new AtomicBoolean();
        var cme = new AtomicLong();
        var wrong = new AtomicLong();
        var other = new AtomicLong();
        var workerTimeouts = new AtomicLong();
        var firstOther = new AtomicReference<Throwable>();
        // 每個執行緒這一輪呼叫的開始與結束（工作執行緒在 done.incrementAndGet 之前寫，主執行緒在看到 done == n 之後讀）。
        var starts = new long[n];
        var ends = new long[n];
        var threads = new Thread[n];
        for (int t = 0; t < n; t++) {
            final int index = t;
            threads[t] = new Thread(() -> {
                int seen = 0;
                while (true) {
                    long idleSince = System.nanoTime();
                    int r;
                    while ((r = round.get()) == seen) {
                        if (stop.get()) return;
                        if (System.nanoTime() - idleSince > WORKER_IDLE_TIMEOUT_NANOS) {
                            workerTimeouts.incrementAndGet();
                            return;
                        }
                        Thread.onSpinWait();
                    }
                    seen = r;
                    var template = current.get();
                    long start = System.nanoTime();
                    try {
                        var infos = template.getInfosForBlock(BlockPos.ORIGIN, new StructurePlacementData(), keys[index]);
                        if (!valid(infos, keys[index])) wrong.incrementAndGet();
                    } catch (ConcurrentModificationException e) {
                        cme.incrementAndGet();
                    } catch (Throwable e) {
                        other.incrementAndGet();
                        firstOther.compareAndSet(null, e);
                    }
                    starts[index] = start;
                    ends[index] = System.nanoTime();
                    done.incrementAndGet();
                }
            }, "H6TemplateCache-" + t);
            threads[t].setDaemon(true);
            threads[t].start();
        }
        long began = System.nanoTime();
        try {
            for (int i = 1; i <= ROUNDS; i++) {
                var template = new StructureTemplate();
                template.readNbt(Registries.BLOCK.getReadOnlyWrapper(), nbt);
                current.set(template);
                long before = cme.get();
                done.set(0);
                round.set(i);
                long deadline = System.nanoTime() + ROUND_TIMEOUT_NANOS;
                while (done.get() < n) {
                    if (System.nanoTime() - deadline > 0) {
                        result.timeouts++;
                        return result;
                    }
                    Thread.onSpinWait();
                }
                result.rounds++;
                if (cme.get() != before) result.roundsWithCme++;
                if (overlapped(starts, ends)) result.roundsWithOverlap++;
                for (var key : palette) {
                    if (!valid(template.getInfosForBlock(BlockPos.ORIGIN, new StructurePlacementData(), key), key)) result.postWrong++;
                }
                if (i == 1) result.cacheMap = cacheMapClass(template);
            }
            return result;
        } finally {
            result.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            stop.set(true);
            for (var thread : threads) thread.join(JOIN_TIMEOUT_MILLIS);
            for (var thread : threads) if (thread.isAlive()) result.workersAlive++;
            result.cme = cme.get();
            result.wrong = wrong.get();
            result.other = other.get();
            result.timeouts += workerTimeouts.get();
            result.firstOther = firstOther.get();
        }
    }

    /** 這一輪有沒有任兩個執行緒的呼叫時間重疊（診斷，不當通過的條件）。 */
    private static boolean overlapped(long[] starts, long[] ends) {
        for (int a = 0; a < starts.length; a++) {
            for (int b = a + 1; b < starts.length; b++) {
                if (starts[a] < ends[b] && starts[b] < ends[a]) return true;
            }
        }
        return false;
    }

    /** 診斷：第一個 {@code PalettedBlockInfoList} 的快取實際的類別（以反射讀；讀不到時回傳原因）。 */
    private static String cacheMapClass(StructureTemplate template) {
        try {
            var lists = StructureTemplate.class.getDeclaredField("blockInfoLists");
            lists.setAccessible(true);
            var first = ((List<?>) lists.get(template)).getFirst();
            var cache = first.getClass().getDeclaredField("blockToInfos");
            cache.setAccessible(true);
            return cache.get(first).getClass().getName();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable:" + e;
        }
    }

    private static boolean valid(List<StructureTemplate.StructureBlockInfo> infos, Block key) {
        if (infos.size() != expected[indexOf(key)]) return false;
        for (var info : infos) if (!info.state().isOf(key)) return false;
        return true;
    }

    private static int indexOf(Block block) {
        for (int i = 0; i < palette.length; i++) if (palette[i] == block) return i;
        throw new IllegalArgumentException(String.valueOf(block));
    }

    /** 位置 i 的方塊在 palette 的索引：70% stone，其餘依序 jigsaw、structure_block、glass、oak_planks 各 7.5%。 */
    private static int stateAt(int i) {
        int m = i % 40;
        return m < 28 ? 0 : 1 + (m - 28) / 3;
    }

    private static NbtCompound templateNbt() {
        var root = new NbtCompound();
        root.put("size", ints(SX, SY, SZ));
        var states = new NbtList();
        for (var block : palette) states.add(NbtHelper.fromBlockState(block.getDefaultState()));
        root.put("palette", states);
        var blocks = new NbtList();
        int i = 0;
        for (int y = 0; y < SY; y++) {
            for (int x = 0; x < SX; x++) {
                for (int z = 0; z < SZ; z++) {
                    var entry = new NbtCompound();
                    entry.put("pos", ints(x, y, z));
                    entry.putInt("state", stateAt(i++));
                    blocks.add(entry);
                }
            }
        }
        root.put("blocks", blocks);
        root.put("entities", new NbtList());
        return root;
    }

    private static NbtList ints(int... values) {
        var list = new NbtList();
        for (int value : values) list.add(NbtInt.of(value));
        return list;
    }
}
