package dev.quantumchamber.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Allocation;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.DiskBlock;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Projection;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlockEntities;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.dimension.DimensionType;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：一條 chain 的 fixture。原艙（Controller 在 (8,70,8)、朝北）、參與者（清單；
 * 可以多人：建立、JOIN、移動、跨越與位置的斷言都對清單裡的每一位，或對 {@link Who} 指定的子集）、MEASURED 收據，與這條 chain 的 id（session、原艙、
 * UniverseId、allocation token、crash 前的 catalog 筆數）。crash phase 建立這些值，restart phase 由 crash phase 的 receipt 讀回（{@link #readCrashReceipt}）。
 * 選門者由收據的 selection 推導（{@link #selectorId}，F6-2c），不依清單順序；清單的順序用在收據的 cohort 與 {@code receiptIdentity} 的比對，以及 JOIN、門口位置與
 * {@link #first} 的順序（F6-2d Step 0：{@code Who} 的 Set 的迭代順序未定義，要依順序時經 {@link #inListOrder}）。
 * 另有各 chain 共用的查詢（U、走廊、磁碟上的方塊與權威）與兩個收斂的斷言（{@link #expectCatalog}、{@link #expectOneAllocation}）。
 */
final class M5CollapseFixture implements M5CollapseRun.Context {
    /** 原艙：Controller 在 (8,70,8)、朝北（與 M4 recovery probe 相同的位置）。 */
    static final ChamberFrame FRAME = new ChamberFrame(new BlockPos(8, 70, 8), Direction.NORTH);
    static final String BEDROCK = "minecraft:bedrock";
    static final String GLASS = "minecraft:glass";
    static final String GOLD_BLOCK = "minecraft:gold_block";

    private final M5CollapseRun run;
    private SessionRecoveryRecord receipt;
    private final List<UUID> participants = new ArrayList<>();
    private UUID chamber, sid;
    private CandidateBytes token;
    private UniverseId universe;
    private int catalogBefore = -1;

    M5CollapseFixture(M5CollapseRun run) {
        this.run = run;
        run.context(this);
    }

    UUID sid() { return sid; }

    UUID chamber() { return chamber; }

    UniverseId universe() { return universe; }

    SessionRecoveryRecord receipt() { return receipt; }

    int catalogBefore() { return catalogBefore; }

    /** 參與者（建立或讀回的順序；收據的 cohort 也是這個順序）。選門者不依這個順序，見 {@link #selectorId}。 */
    List<UUID> participants() { return List.copyOf(participants); }

    /**
     * 參與者的子集，在 step 執行時才決定（F6-2c：restart 的參與者與選門者在開服時才由收據讀回，建立 step 的 {@code onInitialize} 時還不知道）。結果都必須是這條 chain 的
     * 參與者（{@link #members}）。
     */
    @FunctionalInterface interface Who { Set<UUID> of(M5CollapseFixture fixture); }

    /** 全部參與者。 */
    static final Who EVERYONE = fixture -> Set.copyOf(fixture.participants());
    /** 沒有人。 */
    static final Who NOBODY = fixture -> Set.of();
    /** 選門者（收據的 selection）。 */
    static final Who SELECTOR = fixture -> Set.of(fixture.selectorId());
    /** 選門者以外的參與者。 */
    static final Who OTHERS = fixture -> fixture.participants().stream().filter(id -> !id.equals(fixture.selectorId())).collect(Collectors.toUnmodifiableSet());

    /** {@code who} 這時的成員；有不是這條 chain 參與者的 UUID 時丟出。 */
    Set<UUID> members(Who who) {
        var ids = Set.copyOf(who.of(this));
        if (!participants.containsAll(ids)) throw new IllegalArgumentException("不是這條 chain 的參與者：" + ids + "（參與者 " + participants + "）");
        return ids;
    }

    /** 同 {@link #members}，依參與者清單的順序（F6-2d Step 0，F6-2c quality 給 F6-2d 的建議：{@code Who} 的 Set 的迭代順序未定義）。 */
    List<UUID> inListOrder(Who who) {
        var ids = members(who);
        return participants.stream().filter(ids::contains).toList();
    }

    /** {@code who} 依清單順序的第一位（F6-2d Step 0）；沒有人時丟出。 */
    UUID first(Who who) {
        var ids = inListOrder(who);
        if (ids.isEmpty()) throw new IllegalArgumentException("這時沒有符合的參與者（參與者 " + participants + "）");
        return ids.getFirst();
    }

    /**
     * 選門者：收據的 selection（{@code CandidateSelection.Selected.selectedBy}；F6-2c，F6-2a quality 給多人的建議）。還沒有收據，或收據沒有選中的候選時丟出：選門之前
     * （W1 的 crash phase）由 chain 自己決定誰去選門。
     */
    UUID selectorId() {
        if (receipt == null) throw new IllegalStateException("selector：這條 chain 還沒有收據（選門之前由 chain 決定誰去選門）");
        return receipt.candidateSelection().filter(CandidateSelection.Selected.class::isInstance).map(selection -> ((CandidateSelection.Selected) selection).selectedBy())
                .orElseThrow(() -> new IllegalStateException("selector：收據沒有選中的候選：" + receipt.candidateSelection()));
    }

    /** live session（W1）：供電之後 checked ARMING 的 session。 */
    void session(UUID session) { sid = session; }

    // ---- fresh 世界、原艙、參與者 ----

    void requireFreshWorld() throws Exception {
        run.expect("freshJournal", run.journal().flushedRecords().isEmpty(), () -> "journal=" + run.journal().flushedRecords().keySet());
        run.expect("freshCatalog", UniverseRegistryState.get(run.server()).flushedRecords().isEmpty(),
                () -> "catalog=" + UniverseRegistryState.get(run.server()).flushedRecords().keySet());
        run.expect("freshAllocationLedger", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty(), () -> "allocation ledger 不是空的");
    }

    /**
     * 蓋原艙，移走原艙附近的非玩家 entity（{@link #clearAroundTheOrigin}，F6-2e-1；移走的數目依類型記在 proof 的 {@code clearedAroundTheOrigin}），再連線 {@code count} 位參與者並移到
     * 艙外。
     */
    void buildOrigin(int count) {
        run.source().getChunk(0, 0);
        placeChamber();
        run.proof.put("clearedAroundTheOrigin", clearAroundTheOrigin(run.source(), FRAME));
        for (int index = 0; index < count; index++) {
            var id = UUID.randomUUID();
            var connection = connect(id);
            participants.add(id);
            M5GameTestSupport.moveOutside(connection.player(), FRAME);
        }
    }

    /** M4 recovery probe 的原艙寫法（基岩外殼、bulkhead 門、Controller、上方紅石塊），另把門外的通道清成空氣（參與者在門外等待，不能卡在地形裡）。 */
    private void placeChamber() {
        var source = run.source();
        for (int x = -1; x <= 7; x++) for (int y = 0; y <= 9; y++) for (int z = -6; z < 0; z++) {
            source.setBlockState(ChamberSpaceCoordinates.block(FRAME, x, y, z), Blocks.AIR.getDefaultState(), 3);
        }
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
                boolean shell = x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6;
                var block = shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) block = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                source.setBlockState(ChamberGeometry.localToWorld(FRAME, x, y, z), block, 3);
            }
            source.setBlockState(FRAME.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, Direction.NORTH), 3);
        });
        source.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
    }

    /** {@link #clearAroundTheOrigin} 的水平半徑（格，以 Controller 為中心）。 */
    static final int CLEAR_RADIUS = 32;

    /**
     * F6-2e-1（F6-2e Step 0 review 交下的測試缺陷）：移走 {@code world} 裡原艙 {@code frame} 附近的非玩家 entity（世界生成的被動生物等；原艙蓋好之後、收據之前呼叫，這時 probe 還沒有放任何
     * entity），回傳移走的數目（依 entity 類型）。範圍是 Controller 水平 ±{@value #CLEAR_RADIUS} 格、y −16～+32：涵蓋 production 的門口（{@code OriginDoors.doorwayBox}：門 25 格的外框）、
     * 門外的通道、整座艙與參與者等待的位置。只看已載入的 entity（fresh 世界的 spawn chunks 在這時已載入）。原本世界生成的動物留在門口時，restart 由存檔把它載回門口：sealdoorway 的
     * {@code standRemovedFromTheDoorway} 把它算進門口（F6-2e Step 0 的 run-all-1，移走 2 個 entity）；production 在門口有 entity 時正確地改為一般開門、不套用量子膜，預期關門或量子膜的
     * 斷言（例如 {@code closedOnceAfterTheRestart}、{@code closedFromTheMembrane}）也會誤判。restart 不再清一次：開服時門口的 entity storage 不一定已載入（sealdoorway 記下
     * {@code doorwayEntitiesLoadedAtStart}），之後的 tick 移除 entity 又會與 production 同一個 tick 的門口判斷交錯；crash phase 清過之後，存檔裡的門口只有 probe 自己放的 entity。
     */
    static Map<String, Integer> clearAroundTheOrigin(ServerWorld world, ChamberFrame frame) {
        var center = frame.controllerPos();
        var box = new Box(center.getX() - CLEAR_RADIUS, center.getY() - 16, center.getZ() - CLEAR_RADIUS, center.getX() + CLEAR_RADIUS + 1, center.getY() + 32,
                center.getZ() + CLEAR_RADIUS + 1);
        var removed = new TreeMap<String, Integer>();
        for (var entity : world.getOtherEntities(null, box, entity -> !(entity instanceof PlayerEntity))) {
            removed.merge(Registries.ENTITY_TYPE.getId(entity.getType()).toString(), 1, Integer::sum);
            entity.discard();
        }
        return removed;
    }

    /** step：原艙已註冊、IDLE；記下 chamber UUID。 */
    boolean registered() {
        if (!M5GameTestSupport.registeredIdle(run.source(), FRAME)) return false;
        chamber = M5GameTestSupport.controller(run.source(), FRAME).chamberUuid();
        return true;
    }

    /** 原艙已註冊（{@link M5GameTestSupport#registeredIdle}）時記下 chamber UUID 並回傳 Controller；還沒有時回 null。 */
    ChamberControllerBlockEntity registeredController() {
        if (!M5GameTestSupport.registeredIdle(run.source(), FRAME)) return null;
        var controller = M5GameTestSupport.controller(run.source(), FRAME);
        chamber = controller.chamberUuid();
        return controller;
    }

    /** 以 {@code id} 連線一位參與者（在來源世界；停服時關閉連線）。 */
    ConnectedGameTestPlayer connect(UUID id) {
        var connection = new ConnectedGameTestPlayer(run.source(), new GameProfile(id, "m5-" + id.toString().substring(0, 8)));
        run.onStopping(() -> {
            try {
                connection.close();
            } catch (RuntimeException failure) {
                run.failures.add("closePlayer：" + failure);
            }
        });
        connection.confirmTeleport();
        connection.player().setNoGravity(true);
        return connection;
    }

    /**
     * F6-2e-1（W10）：以 {@code id} 連線一位參與者並加入清單（{@link #buildOrigin} 之外建立參與者：原艙不在來源世界的 chain，由 chain 自己把他移到原艙附近）。收據的 cohort、
     * {@link #recordReceipt} 與 restart 的 {@code receiptIdentity} 都依這份清單。
     */
    ConnectedGameTestPlayer connectParticipant(UUID id) {
        var connection = connect(id);
        participants.add(id);
        return connection;
    }

    /** 參與者在來源世界 JOIN 時，預期在艙內還是艙外（{@link #joinInTheSourceWorld}；F6-2d Step 0 由 boolean 改成 enum，呼叫端讀得出意思）。 */
    enum Inside {
        /** 在艙內（playerdata 是參與者進入艙內之後存的，例如 PASSAGE_READY 期間的存檔、退化的 setup）。 */
        INSIDE,
        /** 在艙外（playerdata 是觸發之前存的，參與者在艙外等待）。 */
        OUTSIDE
    }

    /**
     * 全部參與者真 JOIN 在來源世界（{@link #joinInTheSourceWorld}，依清單順序）。crash phase 的 playerdata 多半是觸發之前存的（參與者在艙外，{@link Inside#OUTSIDE}）；PASSAGE_READY
     * 期間存過檔的窗口與退化的 setup 是在艙內（{@link Inside#INSIDE}）。F6-2d-1（F6-2d Step 0 quality Minor 7）：由 {@code joinAll} 改名，與 {@code End.inTheSourceWorld}、
     * 斷言名 {@code joinedInTheSourceWorld} 的用語一致，讀得出在哪個世界（{@code joinAllInTheSourceWorld(Inside.OUTSIDE)}）。
     */
    void joinAllInTheSourceWorld(Inside where) {
        joinInTheSourceWorld(EVERYONE, where);
    }

    /**
     * {@code who} 的每一位（依清單順序）真 JOIN，斷言 JOIN 在 U（F6-2c：已跨過、playerdata 存在 U 的參與者，spec §12 的「已跨過的人在目的世界重新加入」）、沒有被 recovery
     * 攔截。F6-2d Step 0（F6-2c quality Minor 4）：由 {@code join(Who, Who, boolean)} 拆出（原本兩個 {@code Who} 換位置也能編譯，boolean 也看不出意思）；同一個 step 裡與
     * {@link #joinInTheSourceWorld} 先後呼叫，就是同一個 tick JOIN。
     */
    void joinInTheUniverse(Who who) {
        for (var id : inListOrder(who)) {
            var player = joinOne(id);
            run.expect("joinedInTheUniverse", isUniverse(player.getServerWorld()), () -> "已跨過的參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
            expectNotBlocked(id);
        }
    }

    /**
     * {@code who} 的每一位（依清單順序）真 JOIN，斷言 JOIN 在來源世界、在艙內或艙外（{@code where}），沒有被 recovery 攔截（F6-2d Step 0，同 {@link #joinInTheUniverse}；
     * F6-2d-1 由 {@code joinInTheSource} 改名，同 {@link #joinAllInTheSourceWorld}）。
     */
    void joinInTheSourceWorld(Who who, Inside where) {
        Objects.requireNonNull(where, "where");
        for (var id : inListOrder(who)) {
            var player = joinOne(id);
            run.expect("joinedInTheSourceWorld", player.getServerWorld() == run.source(), () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
            if (where == Inside.INSIDE) run.expect("joinedInsideTheChamber", inside(player), () -> "參與者 JOIN 在艙外：" + player.getPos());
            else run.expect("joinedOutsideTheChamber", !inside(player), () -> "參與者 JOIN 在艙內：" + player.getPos());
            expectNotBlocked(id);
        }
    }

    /** 以 {@code id} 連線、觀察到 JOIN（{@code nativeJoin}），回傳玩家。 */
    private ServerPlayerEntity joinOne(UUID id) {
        var connection = connect(id);
        run.expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
        return connection.player();
    }

    private void expectNotBlocked(UUID id) {
        run.expect("participantNotBlocked", !SessionRecoveryManager.blocks(id, run.server()), () -> "已返還的參與者被 recovery 攔截");
    }

    /**
     * 原艙註冊（IDLE）之後，比照 M4 recovery probe 的 arm（F6-2c 由 {@code M5CollapseLiveChain} 搬來，W1 與 W7 共用）：拿掉 Controller 上方的紅石塊（LOW）、給
     * {@code who} Buff，readiness 通過之後存他們的 checkpoint 與原生存檔，再放回紅石塊（HIGH）→ 必須立即有這座艙的 checked ARMING（{@code armingFlushed}：這座艙恰一筆
     * flushed record，是 ARMING）。回傳 ARMING 的 record；原艙還不是 IDLE、readiness 還沒有通過時回 null（下一個 tick 再試）。不改這條 chain 的 session。
     */
    SessionRecoveryRecord arm(Who who) throws Exception {
        var controller = registeredController();
        if (controller == null) return null;
        var arming = members(who);
        var players = participants.stream().filter(arming::contains).map(this::player).toList();
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
        for (var player : players) {
            if (!player.hasStatusEffect(ModEffects.QUANTUM_STATE)) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
        }
        if (!new ChamberActivationService().evaluateReadiness(run.source(), controller).accepted()) return null;
        for (var player : players) PlayerCheckpointStore.saveAndVerify(run.server(), player, Optional.empty());
        run.expect("nativeSaveBeforeArming", run.server().save(false, true, true), () -> "原生存檔失敗");
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        var records = run.journal().flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(chamber)).toList();
        run.expect("armingFlushed", records.size() == 1 && records.getFirst().state() == SessionState.ARMING,
                () -> "供電之後這座艙的 flushed record：" + records.stream().map(record -> record.state().name()).toList());
        return records.getFirst();
    }

    /** 參與者在線上時回傳它的玩家；不在線上時回 null。 */
    ServerPlayerEntity onlinePlayer(UUID id) {
        return run.server().getPlayerManager().getPlayer(id);
    }

    /** 沒有任何參與者在線上。 */
    boolean noneOnline() {
        return participants.stream().allMatch(id -> onlinePlayer(id) == null);
    }

    ServerPlayerEntity player(UUID id) {
        var player = onlinePlayer(id);
        if (player == null) throw new AssertionError("participantOnline：參與者 " + id + " 不在線上");
        return player;
    }

    /** 每位參與者的玩家（依清單順序；任何一位不在線上時丟出 {@code participantOnline}）。 */
    List<ServerPlayerEntity> players() {
        return participants.stream().map(this::player).toList();
    }

    /** 選門者（{@link #selectorId}：收據的 selection）的玩家。 */
    ServerPlayerEntity selector() {
        return player(selectorId());
    }

    void moveInside() {
        for (var player : players()) M5GameTestSupport.moveInside(player, FRAME);
    }

    /** 只把 {@code who} 移到艙內（依清單順序；F6-2c fix round 1：已跨過的人在 U，不能跟著移，{@code M5GameTestSupport.moveInside} 在玩家目前的世界移動）。 */
    void moveInside(Who who) {
        var moving = members(who);
        for (var id : participants) if (moving.contains(id)) M5GameTestSupport.moveInside(player(id), FRAME);
    }

    void moveOutside() {
        for (var player : players()) M5GameTestSupport.moveOutside(player, FRAME);
    }

    // ---- 收據 ----

    /** 全部參與者（依清單順序）都是 {@code returned} 的對照表，給收據的建構用。 */
    private Map<UUID, Boolean> everyone(boolean returned) {
        var people = new LinkedHashMap<UUID, Boolean>();
        for (var id : participants) people.put(id, returned);
        return people;
    }

    /** DORMANT 的 NEW 收據（參與者都已返還、沒有 lease）。選門者是清單的第一位（{@code M4CandidateTestAccess.measuredReceipt}）。只建立，不寫入。 */
    SessionRecoveryRecord dormantNewReceipt() {
        return M4CandidateTestAccess.measuredReceipt(run.server(), M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), everyone(true), List.of(), true);
    }

    /**
     * 同 {@link #dormantNewReceipt}，但選門者（{@code CandidateSelection.Selected.selectedBy}）是清單的<b>第二位</b>（F6-2c fix round 1，spec review M-4：chain 實際驗到
     * 選門者由收據推導、不依清單順序；{@code measuredReceipt} 一律以第一位為選門者，所以只換 selection 的 {@code selectedBy}，其餘欄位不變）。至少要有兩位參與者。只建立，不寫入。
     */
    SessionRecoveryRecord dormantNewReceiptSelectedByTheSecond() {
        if (participants.size() < 2) throw new IllegalStateException("選門者是第二位的收據至少要有兩位參與者：" + participants);
        var first = dormantNewReceipt();
        var selected = (CandidateSelection.Selected) first.candidateSelection().orElseThrow();
        var bySecond = new CandidateSelection.Selected(selected.doorKey(), selected.candidateId(), participants.get(1), selected.selectedAtGameTime(), selected.selectionRevision());
        return new SessionRecoveryRecord(first.sessionUuid(), first.chamberUuid(), first.origin(), first.participants(), first.spaceLeases(), first.state(),
                first.restoreEntryEffectOnReturn(), first.semantics(), first.candidateContext(), first.candidateLedger(), Optional.of(bySecond));
    }

    /** DORMANT 的 EXISTING 收據（參與者都已返還、沒有 lease），候選是 NEW 那一段建立的 U。只建立，不寫入。 */
    SessionRecoveryRecord existingReceiptForTheUniverse() {
        return M5GameTestSupport.existingReceipt(run.server(), M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), everyone(true), universe);
    }

    /** 收據 put＋flush、核對 flushed 與收據 exact 相同，再以它設定這條 chain 的 session、UniverseId 與 allocation token（{@link #adopt}）。 */
    void putReceipt(SessionRecoveryRecord measured) {
        run.journal().put(measured);
        run.journal().flush(run.server());
        run.expect("receiptFlushed", measured.equals(run.journal().flushedRecords().get(measured.sessionUuid())), () -> "flushed 與收據不同");
        adopt(measured);
    }

    /**
     * crash 之前的收據（DORMANT：參與者已返還、沒有 lease）：put＋flush，記下 crash 前的 catalog 筆數，寫出 {@link M5CollapseRun#RECEIPT_NBT}（restart 以它比對
     * 「沒有重抽」），再原生存檔（原艙、registry、journal、參與者的 playerdata 與 U 的 chunks 都在磁碟上）。
     */
    void writeCrashReceipt(SessionRecoveryRecord measured) throws Exception {
        putReceipt(measured);
        recordReceipt();
        run.expect("nativeSaveBeforeTrigger", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
    }

    /** 這條 chain 的 MEASURED 收據（{@link #receipt}，已 flushed）：記下 crash 前的 catalog 筆數，寫出 {@link M5CollapseRun#RECEIPT_NBT}，摘要記在 receipt 的 {@code receipt}。 */
    void recordReceipt() throws Exception {
        var measured = receipt;
        catalogBefore = UniverseRegistryState.get(run.server()).flushedRecords().size();
        var file = run.config.evidence().resolve(M5CollapseRun.RECEIPT_NBT);
        M5CollapseRun.require(!Files.exists(file), "receipt NBT 已存在：" + file);
        var single = new SessionRecoveryState();
        single.put(measured);
        var wrapped = new NbtCompound();
        wrapped.put("data", single.writeNbt(new NbtCompound()));
        NbtIo.writeCompressed(wrapped, file);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("sessionUuid", sid.toString());
        evidence.put("chamberUuid", chamber.toString());
        evidence.put("participants", participants.stream().map(UUID::toString).toList());
        evidence.put("universeId", universe.value().toString());
        evidence.put("allocationToken", HexFormat.of().formatHex(token.bytes()));
        evidence.put("candidate", created() ? "NEW" : "EXISTING");
        evidence.put("catalogBefore", catalogBefore);
        evidence.put("receiptNbt", M5CollapseRun.RECEIPT_NBT);
        evidence.put("receiptNbtSha256", M5CollapseDiskEvidence.sha256(file));
        evidence.put("candidateSha256", M5CollapseDiskEvidence.sha256(wrapped));
        run.proof.put("receipt", evidence);
    }

    /**
     * 以收據設定這條 chain 的 session 與 UniverseId：NEW 由 intent 的 allocation token 推導；EXISTING 是候選的 UniverseId，allocation token 沿用建立 U 的那一筆
     * （同一個 JVM 由 NEW 那一段設定，restart 由 halt receipt 讀回）。
     */
    void adopt(SessionRecoveryRecord measured) {
        receipt = measured;
        sid = measured.sessionUuid();
        // F6-2e-1（W10）：原艙也由收據設定（W10 的原艙在 Nether，不經 registered()）。其他 chain 在這之前已由 registered() 設成同一個值，restart 的 readCrashReceipt 原本就在這之後設成它。
        chamber = measured.chamberUuid();
        var candidate = measured.selectedCandidate().orElseThrow();
        if (candidate instanceof QuantumCandidate.New created) {
            token = created.intent().allocationToken();
            universe = UniverseAllocationLedger.deriveUniverseId(token);
        } else {
            universe = ((QuantumCandidate.Existing) candidate).universeId();
        }
    }

    /** 這條 chain 的收據選的是 NEW（catalog 恰多 1 筆）；EXISTING 時 catalog 不變。 */
    boolean created() {
        return receipt.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New;
    }

    /** catalog 相對於 crash 前可以多幾筆：NEW 1、EXISTING 0（spec §12）。 */
    int catalogGrowth() {
        return created() ? 1 : 0;
    }

    /**
     * restart phase 的開頭：讀回 crash phase（{@code crashPhase}）的 {@code halt.json} 與 {@link M5CollapseRun#RECEIPT_NBT}，核對 halt receipt 的 status、窗口、
     * phase、nonce，乾淨的 halt（第一次 hit、crash 點沒有失敗、failures 是空的），startupNonce 與這個 JVM 不同，receipt NBT 的 SHA-256 與 halt receipt 記的相同；再以收據設定這條 chain 的 fixture（session、原艙、參與者、
     * UniverseId、crash 前的 catalog 筆數）。回傳 halt receipt。{@code window} 為 null 時，前一個 phase 是正常停止的 setup（退化的 chain）：改讀它的
     * {@code final.json}（{@link M5CollapseRun#readStoppedReceipt}：status PASS、ending STOP、phase、nonce），其餘相同。
     */
    JsonObject readCrashReceipt(String crashPhase, CrashWindow window) throws Exception {
        var crashEvidence = run.phaseEvidence(crashPhase);
        var haltPath = crashEvidence.resolve(window == null ? "final.json" : "halt.json");
        JsonObject halt;
        if (window == null) {
            halt = run.readStoppedReceipt(crashPhase, "setup");
        } else {
            run.expect("crashReceiptPresent", Files.exists(haltPath) && !Files.exists(crashEvidence.resolve("final.json")), () -> "crash phase 沒有 halt receipt：" + crashEvidence);
            halt = JsonParser.parseString(Files.readString(haltPath)).getAsJsonObject();
            run.expect("crashReceiptMatches", "HALT".equals(M5CollapseRun.text(halt, "status")) && window.name().equals(M5CollapseRun.text(halt, "window"))
                    && crashPhase.equals(M5CollapseRun.text(halt, "phase")) && run.config.nonce().equals(M5CollapseRun.text(halt, "nonce")),
                    () -> "halt receipt 不符：status=" + M5CollapseRun.text(halt, "status") + " window=" + M5CollapseRun.text(halt, "window"));
            run.expect("crashReceiptClean", halt.has("hit") && halt.get("hit").getAsInt() == 1 && halt.has("failures") && halt.getAsJsonArray("failures").isEmpty()
                    && "none".equals(M5CollapseRun.text(halt, "crashPointFailure")),
                    () -> "halt 不乾淨：hit=" + halt.get("hit") + " failures=" + halt.get("failures") + " crashPointFailure=" + M5CollapseRun.text(halt, "crashPointFailure"));
            run.expectDifferentStartupNonce(halt);
        }
        var noted = halt.getAsJsonObject("receipt");
        var receiptFile = crashEvidence.resolve(M5CollapseRun.RECEIPT_NBT);
        run.expect("receiptNbtIntact", M5CollapseDiskEvidence.sha256(receiptFile).equals(M5CollapseRun.text(noted, "receiptNbtSha256")),
                () -> "receipt NBT 的 SHA-256 與 halt receipt 不同");
        var decoded = SessionRecoveryState.fromNbt(NbtIo.readCompressed(receiptFile, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data"));
        decoded.requireHealthy();
        var measured = decoded.flushedRecords().get(UUID.fromString(M5CollapseRun.text(noted, "sessionUuid")));
        run.expect("receiptDecoded", measured != null && measured.state() == SessionState.MEASURED, () -> "receipt NBT 裡沒有這個 session 的 MEASURED 收據");
        adopt(measured);
        if (!created()) {
            // EXISTING：allocation token 是建立 U 的那一筆（halt receipt 記下的），它推導出的必須就是候選的 UniverseId。
            token = new CandidateBytes(HexFormat.of().parseHex(M5CollapseRun.text(noted, "allocationToken")));
            run.expect("existingUniverseWasAllocated", UniverseAllocationLedger.deriveUniverseId(token).equals(universe),
                    () -> "halt receipt 的 allocation token 推導出的 UniverseId 不是候選的 " + universe.value());
        }
        chamber = measured.chamberUuid();
        participants.clear();
        measured.participants().forEach(person -> participants.add(person.playerUuid()));
        catalogBefore = noted.get("catalogBefore").getAsInt();
        var notedParticipants = new ArrayList<String>();
        noted.getAsJsonArray("participants").forEach(element -> notedParticipants.add(element.getAsString()));
        run.expect("receiptIdentity", universe.value().toString().equals(M5CollapseRun.text(noted, "universeId"))
                        && participants.stream().map(UUID::toString).toList().equals(notedParticipants),
                () -> "receipt NBT 與 halt receipt 的 UniverseId 或參與者不同");
        String prefix = window == null ? "final" : "halt";
        run.proof.put(window == null ? "setupReceipt" : "crashReceipt", Map.of("phase", crashPhase, prefix + "Sha256", M5CollapseDiskEvidence.sha256(haltPath),
                prefix + "Pid", halt.get("pid").getAsLong(), prefix + "StartupNonce", M5CollapseRun.text(halt, "startupNonce"),
                prefix + "StartTimeUtc", M5CollapseRun.text(halt, "startTimeUtc")));
        run.proof.put("universeId", universe.value().toString());
        return halt;
    }

    /** step：塌縮已提交 COLLAPSING（flushed），Destination 是這條 chain 的 UniverseId（NEW 是推導出的，EXISTING 是候選的）。 */
    boolean committed() {
        var record = run.journal().flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.COLLAPSING) return false;
        run.expect(created() ? "committedToTheDerivedUniverse" : "committedToTheExistingUniverse", destination(record).equals(Optional.of(universe)),
                () -> "Destination=" + record.collapse().destination());
        return true;
    }

    // ---- 收斂的斷言 ----

    /** catalog 相對於 crash 前的增量（spec §12）：NEW 恰多 1 筆、EXISTING 不變，而且 U 是 EAGER_ENABLED。NEW 時名稱是 {@code grownName}，EXISTING 一律 {@code catalogUnchanged}。 */
    void expectCatalog(String grownName) {
        expectCatalog(grownName, catalogGrowth());
    }

    /** 同 {@link #expectCatalog(String)}，增量由呼叫端指定（{@code growth} 為 0 時名稱一律 {@code catalogUnchanged}）。 */
    void expectCatalog(String grownName, int growth) {
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords();
        run.expect(growth == 0 ? "catalogUnchanged" : grownName, catalog.size() == catalogBefore + growth && eagerEnabled(catalog.get(universe)),
                () -> "catalog=" + catalog);
    }

    /** allocation ledger（磁碟）恰一筆：這條 chain 的 token → UniverseId（沒有重抽）。 */
    void expectOneAllocation() {
        run.expect("oneAllocationForTheToken", () -> M5CollapseDiskEvidence.allocations(run.data()).equals(List.of(allocation())),
                () -> "allocation ledger=" + allocationsOrError());
    }

    /**
     * 同 {@link #expectOneAllocation}，但 ledger 另有 {@code others}（依追加的順序，在這條 chain 的那一筆之後；F6-2d-1：w9io 注入的另一個 session 的追加，spec §6 roll-forward 不移除）：
     * 這條 chain 的 token 恰一筆、推導出同一個 UniverseId（沒有重抽），其餘恰為 {@code others}。
     */
    void expectAllocationsWithOthers(List<Allocation> others) {
        expectAllocationsWithOthers("oneAllocationForTheTokenWithTheOthers", others);
    }

    /**
     * 同 {@link #expectAllocationsWithOthers(List)}，斷言名稱是 {@code name}（F6-2d-2 Step 0，F6-2d-1 quality nit：w9io 的 {@code otherAppendLanded} 原本另寫一份同樣的比對）。
     */
    void expectAllocationsWithOthers(String name, List<Allocation> others) {
        var expected = new ArrayList<Allocation>(List.of(allocation()));
        expected.addAll(others);
        run.expect(name, () -> M5CollapseDiskEvidence.allocations(run.data()).equals(expected), () -> "allocation ledger=" + allocationsOrError() + "，預期 " + expected);
    }

    /** catalog 相對於 crash 前恰多 U 一筆（NEW），而且 U 是 DISABLED（F6-2d-1，W9：§8.3 已完成；名稱是 {@code name}）。 */
    void expectCatalogDisabled(String name) {
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords();
        var record = catalog.get(universe);
        run.expect(name, catalog.size() == catalogBefore + catalogGrowth() && record != null && record.desiredAvailability() == DesiredAvailability.DISABLED,
                () -> "catalog=" + catalog);
    }

    // ---- 查詢 ----

    CorridorPageManager pages() { return CorridorPageManager.forServer(run.server()); }

    ServerWorld corridor() { return run.server().getWorld(SuperpositionWorld.KEY); }

    /** 來源世界（原艙所在；窗口表的條件只拿得到 fixture，F6-2d-2 B1 的 {@code READY_HELD} 要看原艙門）。 */
    ServerWorld source() { return run.source(); }

    /** 這個 session 有走廊 space（{@code currentMappings} 對沒有 space 的 session 丟 {@link IllegalArgumentException}）。 */
    boolean spaceExists() {
        try {
            pages().currentMappings(sid);
            return true;
        } catch (IllegalArgumentException missing) {
            return false;
        }
    }

    Optional<ServerWorld> universeWorld() {
        return UniverseLifecycleService.resolveActive(run.server(), universe);
    }

    boolean isUniverse(ServerWorld world) {
        return universeWorld().filter(active -> active == world).isPresent();
    }

    /** {@code world} 的存檔目錄（raw MCA 的上層）。 */
    Path dimensionDirectory(ServerWorld world) {
        return DimensionType.getSaveDirectory(world.getRegistryKey(), run.saveRoot());
    }

    /** {@code world} 在 {@code pos} 的方塊，以 raw MCA 從磁碟讀（{@link M5CollapseDiskEvidence#block}）。 */
    DiskBlock diskBlock(ServerWorld world, BlockPos pos) throws IOException {
        return M5CollapseDiskEvidence.block(dimensionDirectory(world), pos);
    }

    /** 磁碟上這一格是 Controller，而且有 Controller 的 block entity。 */
    static boolean controllerBlock(DiskBlock disk) {
        return Registries.BLOCK.getId(ModBlocks.CHAMBER_CONTROLLER).toString().equals(disk.name())
                && String.valueOf(Registries.BLOCK_ENTITY_TYPE.getId(ModBlockEntities.CHAMBER_CONTROLLER)).equals(disk.blockEntity());
    }

    /** inert 的 PROJECTION（比照 {@code M5UniverseGameTests}）：仍在世界上、PROJECTION、INVALID、比較器 0、沒有 chamber UUID。 */
    static boolean inertProjection(ServerWorld world, ChamberControllerBlockEntity controller) {
        return !controller.isRemoved() && world.getBlockEntity(controller.getPos()) == controller && controller.instanceKind() == ChamberInstanceKind.PROJECTION
                && controller.chamberState() == ChamberState.INVALID && controller.chamberUuid() == null
                && world.getBlockState(controller.getPos()).getComparatorOutput(world, controller.getPos()) == 0;
    }

    /** 原艙（與 U 的投影，同 XYZ、同朝向）在區域座標 (x, y, z) 的世界座標。 */
    static BlockPos local(int x, int y, int z) {
        return ChamberGeometry.localToWorld(FRAME, x, y, z);
    }

    /** W6x 改壞的 shell cell：區域 (6,3,3)（側牆，不是門也不是 Controller）。 */
    static BlockPos damagedShell() { return local(6, 3, 3); }

    /** W6x 與退化的 chain 的 interior 標記：區域 (1,1,5)（interior 的角落）。 */
    static BlockPos interiorMarker() { return local(1, 1, 5); }

    Allocation allocation() {
        return new Allocation(HexFormat.of().formatHex(token.bytes()), universe.value());
    }

    Projection projection() {
        return projection("COMPLETE");
    }

    /** 這座艙在 U 的 projection entry，狀態為 {@code status}（PENDING 或 COMPLETE）。 */
    Projection projection(String status) {
        return new Projection(chamber, universe.value(), status);
    }

    String allocationsOrError() {
        try { return M5CollapseDiskEvidence.allocations(run.data()).toString(); } catch (IOException | RuntimeException failure) { return "UNREADABLE: " + failure; }
    }

    String projectionsOrError() {
        try { return M5CollapseDiskEvidence.projections(run.data()).toString(); } catch (IOException | RuntimeException failure) { return "UNREADABLE: " + failure; }
    }

    /** 參與者在艙內（區域座標 1..6 的內部空間）。 */
    static boolean inside(ServerPlayerEntity player) {
        var local = ChamberSpaceCoordinates.localPosition(FRAME, player.getPos());
        return local.x >= 1 && local.x < 6 && local.y >= 1 && local.y < 6 && local.z >= 1 && local.z < 6;
    }

    /**
     * {@code record} 的塌縮相關欄位的文字摘要（F6-2c：halt 當下與 restart 開服時比對相同）：狀態、Collapse 的每個欄位（Destination、opened／expires、Crossed、
     * RecoveryRequired、SEALED 的原因）、每位參與者的 UUID 與是否已返還、lease 的筆數。候選的 context、ledger 與 selection 另由 {@link #sameCandidate} 比對。
     * 不經 journal 的 NBT：塌縮狀態的 record 放不進一份新的 journal（M5 authority 尚未初始化時拒絕），{@code toNbt} 也不是 public。
     */
    static String recordDigest(SessionRecoveryRecord record) {
        return record.state() + " " + record.collapse() + " participants=" + record.participants().stream().map(person -> person.playerUuid() + ":" + person.returned()).toList()
                + " leases=" + record.spaceLeases().size();
    }

    /** 這個 session 從 {@code SERVER_STARTED} 起有沒有 {@code level} 而且含有全部 {@code fragments} 的 log（{@code quantumchamber} logger）。 */
    boolean sessionLogged(org.apache.logging.log4j.Level level, String... fragments) {
        var all = java.util.Arrays.copyOf(fragments, fragments.length + 1);
        all[fragments.length] = sid.toString();
        return !run.capture().matching(level, all).isEmpty();
    }

    /** 來源 Controller 目前是 {@code state}，而且比較器輸出是 {@code signal}（spec §10.7）。 */
    boolean controllerSignal(ChamberState state, int signal) {
        var pos = FRAME.controllerPos();
        return run.source().getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller && controller.chamberState() == state
                && run.source().getBlockState(pos).getComparatorOutput(run.source(), pos) == signal;
    }

    static Optional<UniverseId> destination(SessionRecoveryRecord record) {
        return record != null && record.collapse().destination() instanceof CollapseDestination.Universe target ? Optional.of(target.id()) : Optional.empty();
    }

    static boolean eagerEnabled(UniverseRecord record) {
        return record != null && record.desiredAvailability() == DesiredAvailability.EAGER_ENABLED;
    }

    /** 沒有重抽：session、原艙、ORIGIN、參與者與候選的 context、ledger、selection 都與 crash 前的收據 exact 相同。 */
    static boolean sameCandidate(SessionRecoveryRecord before, SessionRecoveryRecord after) {
        return after != null && before.sessionUuid().equals(after.sessionUuid()) && before.chamberUuid().equals(after.chamberUuid())
                && before.origin().equals(after.origin())
                && before.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList()
                        .equals(after.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList())
                && before.candidateContext().equals(after.candidateContext()) && before.candidateLedger().equals(after.candidateLedger())
                && before.candidateSelection().equals(after.candidateSelection());
    }

    /** 以 probe 的 ticket（{@link ProbeTicket}，持有到 JVM 結束）載入投影所在的 chunk。 */
    void holdProjectionChunk(ServerWorld world) {
        var chunk = new ChunkPos(FRAME.controllerPos());
        world.getChunkManager().addTicket(ProbeTicket.TYPE, chunk, 0, chunk);
        world.getChunk(chunk.x, chunk.z);
    }

    /**
     * F6-2c fix round 1：以 probe 的 ticket（{@link ProbeTicket}，持有到 JVM 結束）讓 {@code world} 以原艙（投影）所在的 chunk 為中心、半徑 {@code radius} 的 chunk 都載入到 FULL
     * （生成時連同它們需要的較低狀態的外圈）；回傳是否已全部 FULL。同一個 ticket 重複加入不會多一張（ticket 以類型、等級與 argument 比對）。
     */
    boolean loadedAroundTheChamber(ServerWorld world, int radius) {
        return fullAroundTheChamber(world, holdAroundTheChamber(world, radius), radius);
    }

    /**
     * F6-2d-1：同 {@link #loadedAroundTheChamber}（probe 的 ticket，持有到 JVM 結束），另在 server thread 上同步載入那些 chunk（{@code world.getChunk}），回傳是否已全部 FULL。
     * restart 開服時（第一個 tick 之前）呼叫：塌縮流程第一次觀察時來源艙已載入（§7.1／§11.2 觸發成立的條件之一），觸發不會因為 chunk 還在載入而落空。F6-2d-2 Step 0
     * （F6-2d-1 quality nit）：ticket 只在同步載入之前加一次（原本最後經 {@link #loadedAroundTheChamber} 再加一次同一張，是冗餘的呼叫；同一張 ticket 重複加入不會多一張）。
     */
    boolean loadAroundTheChamberNow(ServerWorld world, int radius) {
        var center = holdAroundTheChamber(world, radius);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) world.getChunk(center.x + x, center.z + z);
        return fullAroundTheChamber(world, center, radius);
    }

    /** 加上 probe 的 ticket（以原艙所在的 chunk 為中心、半徑 {@code radius}，持有到 JVM 結束），回傳中心的 chunk。 */
    private static ChunkPos holdAroundTheChamber(ServerWorld world, int radius) {
        var center = new ChunkPos(FRAME.controllerPos());
        world.getChunkManager().addTicket(ProbeTicket.TYPE, center, radius, center);
        return center;
    }

    /** 以 {@code center} 為中心、半徑 {@code radius} 的 chunk 在 {@code world} 是否都是 FULL（只查詢）。 */
    private static boolean fullAroundTheChamber(ServerWorld world, ChunkPos center, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (world.getChunkManager().getWorldChunk(center.x + x, center.z + z) == null) return false;
        }
        return true;
    }

    /**
     * probe 自己的 chunk ticket（level FULL、不持久化；只在需要時建立，probe 關閉時不碰 ChunkTicketType）。加上之後一直持有到 JVM 結束，不移除：
     * 開發時在 W6 的 restart 於 load-sync 之後移除，之後 PREPARE 的預載又以 production 的 ticket 載入同一個 chunk，而這個 phase 沒有任何 flush 存檔（修復 0 格，
     * 不做 checkpoint）；停服時原版的 {@code MinecraftServer.shutdown → ServerChunkLoadingManager.unloadChunks} 每次都忙等卡住（半徑 2 與 0 都是），不加 ticket 時則
     * 正常停服。持有到底就沒有這個卸載再載入的循環，也不影響 production 的判斷（PREPARE 只以 {@code getWorldChunk} 查 FULL）。
     * <p>上面寫的是 H5（{@code f25a37d}）之前的行為：這個忙等是原版 chunk 卸載的 livelock（根因見 implementation note 的 F6-2b 一節），H5 的 production mixin
     * {@code ServerChunkLoadingManagerStaleUnloadMixin} 已修正，回歸測試是 {@link M5CollapseUnloadChain}。ticket 仍持有到 JVM 結束，是為了不改變既有 phase 的條件
     * （F6-2c Step 0，H5 quality Minor 5）。
     */
    private static final class ProbeTicket {
        static final ChunkTicketType<ChunkPos> TYPE = ChunkTicketType.create("quantumchamber_m5_collapse_probe", Comparator.comparingLong(ChunkPos::toLong));
    }

    // ---- runner 的 Context ----

    @Override public String diagnostic() {
        return "session=" + sid + " state=" + (sid == null ? "-" : M5GameTestSupport.state(run.journal(), sid)) + " catalog="
                + UniverseRegistryState.get(run.server()).records().keySet();
    }

    @Override public boolean universeActive() {
        return universe != null && universeWorld().isPresent();
    }

    @Override public Map<String, Object> diskSummary() {
        return M5CollapseDiskEvidence.summary(run.saveRoot(), sid);
    }
}
