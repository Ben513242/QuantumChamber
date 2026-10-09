package dev.quantumchamber.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
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
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlockEntities;
import dev.quantumchamber.registry.ModBlocks;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.dimension.DimensionType;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：一條 chain 的 fixture。原艙（Controller 在 (8,70,8)、朝北）、參與者（清單；
 * 可以多人：建立、JOIN、移動、跨越與位置的斷言都對清單裡的每一位，或對 {@link Who} 指定的子集）、MEASURED 收據，與這條 chain 的 id（session、原艙、
 * UniverseId、allocation token、crash 前的 catalog 筆數）。crash phase 建立這些值，restart phase 由 crash phase 的 receipt 讀回（{@link #readCrashReceipt}）。
 * 選門者由收據的 selection 推導（{@link #selectorId}，F6-2c），不依清單順序；清單的順序只用在收據的 cohort 與 {@code receiptIdentity} 的比對。
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

    /** 蓋原艙，連線 {@code count} 位參與者並移到艙外。 */
    void buildOrigin(int count) {
        run.source().getChunk(0, 0);
        placeChamber();
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
     * 每位參與者真 JOIN，在來源世界、沒有被 recovery 攔截。crash phase 的 playerdata 是觸發之前存的（參與者在艙外）；退化的 setup 是在參與者進入艙內之後正常停服，
     * 所以 JOIN 在艙內（{@code insideExpected}）。
     */
    void joinAll(boolean insideExpected) {
        for (var id : participants) {
            var connection = connect(id);
            var player = connection.player();
            run.expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
            run.expect("joinedInTheSourceWorld", player.getServerWorld() == run.source(), () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
            if (insideExpected) run.expect("joinedInsideTheChamber", inside(player), () -> "參與者 JOIN 在艙外：" + player.getPos());
            else run.expect("joinedOutsideTheChamber", !inside(player), () -> "參與者 JOIN 在艙內：" + player.getPos());
            run.expect("participantNotBlocked", !SessionRecoveryManager.blocks(id, run.server()), () -> "已返還的參與者被 recovery 攔截");
        }
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

    /** DORMANT 的 NEW 收據（參與者都已返還、沒有 lease）。只建立，不寫入。 */
    SessionRecoveryRecord dormantNewReceipt() {
        return M4CandidateTestAccess.measuredReceipt(run.server(), M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), everyone(true), List.of(), true);
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

    // ---- 查詢 ----

    CorridorPageManager pages() { return CorridorPageManager.forServer(run.server()); }

    ServerWorld corridor() { return run.server().getWorld(SuperpositionWorld.KEY); }

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
