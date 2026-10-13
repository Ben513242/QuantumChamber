package dev.quantumchamber.gametest;

import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRegistryState;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkLevels;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

/**
 * 僅 testmod（M5 H6，spec §4 H6、§20.12；根因見 implementation note 的 H6 Step 0 一節）：結構範本快取的同時使用的整合 chain {@code h6race}（一個 stop phase
 * {@code h6race-starts}）。不經 M5 的塌縮流程：原版的 race 只需要「兩個世界共用 template、同 seed、同時生成同一個 jigsaw 結構的 start」。
 * <p>fresh 世界裡以 production 的 {@code UniverseRegistryState.allocateOverworld} 與 {@code UniverseLifecycleService.materializeAllocated} 物化一個 U（與來源世界共用
 * Overworld 的 {@code DimensionOptions} 與 seed，同 M3），等兩個世界的生成都閒置（{@code idle}），再對 {@link #STRUCTURES} 的每一種 jigsaw 結構依序：
 * <ol>
 * <li>{@code find-*}：以 structure set 的 {@link RandomSpreadStructurePlacement}（{@code getStartChunk}、{@code shouldGenerate}）與 {@code getValidStructurePosition}
 *     在遠離 spawn 的 chunk {@link #CENTER} 附近找一個 start chunk（不生成任何 chunk；{@code locateStructure} 會在來源世界同步生成，所以不用），兩個世界都加上 level 為
 *     {@code STRUCTURE_STARTS} 的 ticket（只讓 holder 存在：1.21 高於 FULL 的 ticket 不會自己排生成）。</li>
 * <li>{@code load-*}（下一個 tick）：<b>同一個呼叫裡</b>對兩個世界的 holder {@code load(STRUCTURE_STARTS)}，接著兩邊各自 {@code updateChunks()}，立即排入各自的
 *     worldgen mailbox。兩個世界的開始時間差在 µs 級，兩邊以同一個順序（同 seed）第一次計算同一批 template 的 JIGSAW key。</li>
 * <li>{@code generated-*}：等兩個世界都到 {@code STRUCTURE_STARTS}（step 的上限之內沒有到達就 FAIL），比對兩邊的 structure start，移除 ticket。</li>
 * </ol>
 * 斷言：每一種結構都找到 start（{@code startFound}）、兩個世界在 load 之前都還沒有生成（{@code notGeneratedBeforeLoad}）、都到 {@code STRUCTURE_STARTS}（step
 * {@code generated-*}）、兩邊都有這個結構的 start（{@code structureStartInBothWorlds}），而且相同（{@code sameStructureStartInBothWorlds}：piece 的類型、bounding box 與
 * pool element 依序相同；同 seed 的 lockstep 是 race 的前提，這個斷言確認兩邊真的生成了同一個 jigsaw 結構）；之後正常停服（stop phase 的 {@code final.json} PASS）。
 * <p>修正之前（沒有 production 的 {@code PalettedBlockInfoListConcurrentCacheMixin}）：H6 Step 0 的最小重現在原版時序下 5/5 在第一個結構丟出
 * {@code ConcurrentModificationException}，之後停服卡住，由 {@code m5-collapse-probe.ps1} 的逾時處置判 FAIL（{@code timeout.json}）。原版的 worldgen worker pool 是
 * 邏輯核心數減 1、最少 1（{@code Util.java:201}）：只有一條 worker 的機器上兩個 mailbox 不會同時執行，這個 chain 在那種機器上 PASS 不能當成修正有效的證據
 * （receipt 的 {@code availableProcessors}）；確定性的回歸是 JUnit 的 {@code StructureTemplateCacheConcurrencyTest}。
 */
final class M5CollapseTemplateRaceChain implements M5CollapseRun.Context {
    /** U 的 UUID（allocation ordinal 0；fresh 世界的 catalog 是空的）。 */
    private static final UUID UNIVERSE = UUID.fromString("6c1d2e3f-4a5b-4c6d-8e7f-0123456789ab");
    /** testmod 自己的 ticket（level STRUCTURE_STARTS、不持久化；只在這條 chain 建立）。 */
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create("quantumchamber_h6race", Comparator.comparingLong(ChunkPos::toLong));
    /** 會在 structure_starts 計算 template 的 JIGSAW key 的 jigsaw 結構（H6 Step 0 的最小重現）。 */
    static final List<String> STRUCTURES = List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga",
            "pillager_outpost", "trail_ruins", "ancient_city", "trial_chambers");
    /** 找 start 的中心（遠離 spawn）。 */
    static final ChunkPos CENTER = new ChunkPos(256, 256);
    /** 找 start 的範圍：以 structure set 的 spacing 為單位的環數。 */
    private static final int MAX_RING = 24;
    /** ticket 的半徑：ticket 的 level（33 − 半徑，33 是原版 FULL 的 level，{@code ChunkLevels.FULL} 是 private）等於 STRUCTURE_STARTS 的 level。 */
    private static final int RADIUS = 33 - ChunkLevels.getLevelFromStatus(ChunkStatus.STRUCTURE_STARTS);
    /** 兩個世界的 holder 數與狀態連續幾個 tick 不變才算閒置。 */
    private static final int IDLE_TICKS = 40;

    private final M5CollapseRun run;
    private ServerWorld universe;
    private int index = -1;
    private ChunkPos target;
    private Structure structure;
    private long requestedTick;
    private int stable;
    private long lastSignature = Long.MIN_VALUE;
    private final List<Map<String, Object>> results = new ArrayList<>();

    M5CollapseTemplateRaceChain(M5CollapseRun run) {
        this.run = run;
        run.context(this);
    }

    /** {@code h6race-starts}：見類別說明。 */
    Phase phase() {
        var steps = new ArrayList<Step>();
        steps.add(new Step("materialize", 200, this::materialize));
        steps.add(new Step("idle", 2400, this::idle));
        for (var name : STRUCTURES) {
            steps.add(new Step("find-" + name, 1, this::find));
            steps.add(new Step("load-" + name, 1, this::load));
            steps.add(new Step("generated-" + name, 600, this::generated));
        }
        return Phase.stop(steps).started(() -> run.proof.put("availableProcessors", Runtime.getRuntime().availableProcessors()))
                .afterStop(() -> run.proof.put("h6race", results));
    }

    private boolean materialize() {
        var server = run.server();
        if (!UniverseLifecycleService.ready(server)) return false;
        var catalog = UniverseRegistryState.get(server);
        var record = catalog.allocateOverworld(UNIVERSE, 0);
        catalog.flush(server);
        var outcome = UniverseLifecycleService.materializeAllocated(server, record.definition().universeId());
        run.expect("universeMaterialized", outcome.world().isPresent(), outcome::toString);
        universe = outcome.world().orElseThrow();
        return true;
    }

    /** 兩個世界的 holder 數與狀態連續 {@value #IDLE_TICKS} 個 tick 不變（開服的 spawn 與 U 物化時的生成都停下）。 */
    private boolean idle() {
        long signature = signature(run.source()) * 31 + signature(universe);
        stable = signature == lastSignature ? stable + 1 : 0;
        lastSignature = signature;
        return stable >= IDLE_TICKS;
    }

    /** 載入中的 holder 數，與每個 holder 已完成的最高狀態（沒有時 −1）的索引加總。 */
    private static long signature(ServerWorld world) {
        var holders = holders(world);
        long sum = 0;
        for (var holder : holders.values()) {
            var latest = holder.getLatestStatus();
            sum += latest == null ? -1 : latest.getIndex();
        }
        return ((long) holders.size() << 32) + sum;
    }

    private static Long2ObjectLinkedOpenHashMap<ChunkHolder> holders(ServerWorld world) {
        return ((ServerChunkLoadingManagerTestAccessor) world.getChunkManager().chunkLoadingManager).quantumchamberTest$currentChunkHolders();
    }

    private boolean find() {
        index++;
        var name = STRUCTURES.get(index);
        var result = new LinkedHashMap<String, Object>();
        result.put("structure", name);
        results.add(result);
        var key = RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.ofVanilla(name));
        var entry = run.server().getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(key).orElse(null);
        structure = entry == null ? null : entry.value();
        target = structure == null ? null : findStart(key, structure, result);
        run.expect("startFound", target != null, () -> name + "：" + result);
        result.put("chunk", target.x + "," + target.z);
        run.source().getChunkManager().addTicket(TICKET, target, RADIUS, target);
        universe.getChunkManager().addTicket(TICKET, target, RADIUS, target);
        return true;
    }

    /** 由 {@link #CENTER} 往外一環一環找第一個 start chunk（只算 placement 與 {@code getValidStructurePosition}，不生成任何 chunk）；找不到時回 null。 */
    private ChunkPos findStart(RegistryKey<Structure> key, Structure value, Map<String, Object> result) {
        var server = run.server();
        var source = run.source();
        var manager = source.getChunkManager();
        var calculator = manager.getStructurePlacementCalculator();
        var generator = manager.getChunkGenerator();
        for (var set : calculator.getStructureSets()) {
            if (set.value().structures().stream().noneMatch(weighted -> weighted.structure().matchesKey(key))) continue;
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement placement)) continue;
            int spacing = placement.getSpacing();
            for (int ring = 0; ring <= MAX_RING; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        var start = placement.getStartChunk(calculator.getStructureSeed(), CENTER.x + dx * spacing, CENTER.z + dz * spacing);
                        if (!placement.shouldGenerate(calculator, start.x, start.z)) continue;
                        var context = new Structure.Context(server.getRegistryManager(), generator, generator.getBiomeSource(), manager.getNoiseConfig(),
                                server.getStructureTemplateManager(), calculator.getStructureSeed(), start, source, value.getValidBiomes()::contains);
                        if (value.getValidStructurePosition(context).isPresent()) {
                            result.put("ring", ring);
                            return start;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 同一個呼叫裡對兩個世界的 holder 要求 {@code STRUCTURE_STARTS}，並立即排入各自的 worldgen。 */
    private boolean load() {
        var source = run.source();
        var sourceHolder = holders(source).get(target.toLong());
        var universeHolder = holders(universe).get(target.toLong());
        run.expect("holdersExist", sourceHolder != null && universeHolder != null, () -> "source=" + sourceHolder + " universe=" + universeHolder);
        run.expect("notGeneratedBeforeLoad", sourceHolder.getLatestStatus() == null && universeHolder.getLatestStatus() == null,
                () -> "source=" + sourceHolder.getLatestStatus() + " universe=" + universeHolder.getLatestStatus());
        long t0 = System.nanoTime();
        sourceHolder.load(ChunkStatus.STRUCTURE_STARTS, source.getChunkManager().chunkLoadingManager);
        universeHolder.load(ChunkStatus.STRUCTURE_STARTS, universe.getChunkManager().chunkLoadingManager);
        source.getChunkManager().chunkLoadingManager.updateChunks();
        universe.getChunkManager().chunkLoadingManager.updateChunks();
        var result = results.getLast();
        result.put("scheduleMicros", (System.nanoTime() - t0) / 1000);
        requestedTick = run.server().getTicks();
        result.put("requestedTick", requestedTick);
        M5CollapseRun.LOGGER.info("H6RACE load {} chunk={} tick={}", STRUCTURES.get(index), target, requestedTick);
        return true;
    }

    /** 兩個世界都到 {@code STRUCTURE_STARTS} 之後：比對兩邊的 structure start，移除 ticket。 */
    private boolean generated() {
        var source = run.source();
        if (!reached(source) || !reached(universe)) return false;
        var result = results.getLast();
        result.put("generatedTick", run.server().getTicks());
        var inSource = startIn(source);
        var inUniverse = startIn(universe);
        run.expect("structureStartInBothWorlds", inSource != null && inSource.hasChildren() && inUniverse != null && inUniverse.hasChildren(),
                () -> STRUCTURES.get(index) + "：source=" + inSource + " universe=" + inUniverse);
        var sourcePieces = pieces(inSource);
        var universePieces = pieces(inUniverse);
        result.put("pieces", sourcePieces.size());
        run.expect("sameStructureStartInBothWorlds", sourcePieces.equals(universePieces),
                () -> STRUCTURES.get(index) + "：source=" + sourcePieces + " universe=" + universePieces);
        source.getChunkManager().removeTicket(TICKET, target, RADIUS, target);
        universe.getChunkManager().removeTicket(TICKET, target, RADIUS, target);
        M5CollapseRun.LOGGER.info("H6RACE generated {}", result);
        return true;
    }

    private boolean reached(ServerWorld world) {
        var holder = holders(world).get(target.toLong());
        var latest = holder == null ? null : holder.getLatestStatus();
        return latest != null && latest.isAtLeast(ChunkStatus.STRUCTURE_STARTS);
    }

    private StructureStart startIn(ServerWorld world) {
        var chunk = holders(world).get(target.toLong()).getLatest();
        return chunk == null ? null : chunk.getStructureStart(structure);
    }

    /** start 的 bounding box 與每個 piece 的類型、bounding box、pool element（依序）。 */
    private static List<String> pieces(StructureStart start) {
        var out = new ArrayList<String>();
        if (start == null) return out;
        out.add("start " + start.getBoundingBox());
        for (var piece : start.getChildren()) {
            var element = piece instanceof PoolStructurePiece pool ? " " + pool.getPoolElement() : "";
            out.add(Registries.STRUCTURE_PIECE.getId(piece.getType()) + " " + piece.getBoundingBox() + element);
        }
        return out;
    }

    @Override public String diagnostic() {
        try {
            return "h6race index=" + index + " target=" + target + " universe=" + (universe == null ? "-" : universe.getRegistryKey().getValue())
                    + (target == null || universe == null ? "" : " source=" + describe(run.source()) + " universeHolder=" + describe(universe)) + " results=" + results;
        } catch (RuntimeException failure) {
            return "（診斷失敗：" + failure + "）";
        }
    }

    private String describe(ServerWorld world) {
        var holder = holders(world).get(target.toLong());
        return holder == null ? "noHolder" : "level=" + holder.getLevel() + " latest=" + holder.getLatestStatus() + " refs=" + holder.getRefCount();
    }

    @Override public boolean universeActive() { return universe != null; }

    @Override public Map<String, Object> diskSummary() { return Map.of(); }
}
