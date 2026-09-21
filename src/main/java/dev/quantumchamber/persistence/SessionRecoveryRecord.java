package dev.quantumchamber.persistence;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.candidate.CandidateId;
import dev.quantumchamber.candidate.CandidateLedgerEntry;
import dev.quantumchamber.candidate.CandidatePolicySnapshot;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 恢復所需的純資料；任何可變 NBT 與空間邊界皆不洩漏內部參照。 */
public record SessionRecoveryRecord(UUID sessionUuid, UUID chamberUuid, ChamberOriginAuthority origin,
        List<Participant> participants, List<SpaceLease> spaceLeases, SessionState state, boolean restoreEntryEffectOnReturn,
        SessionSemantics semantics, Optional<CandidatePolicySnapshot> candidateContext,
        List<CandidateLedgerEntry> candidateLedger, Optional<CandidateSelection> candidateSelection) {
    private static final Comparator<CandidateLedgerEntry> LEDGER_ORDER = Comparator
            .comparingLong((CandidateLedgerEntry entry) -> entry.doorKey().logicalStationIndex())
            .thenComparing(entry -> entry.doorKey().wallSide());
    /** 舊呼叫端只建立 legacy record；不推測 candidate context。 */
    public SessionRecoveryRecord(UUID sessionUuid,UUID chamberUuid,ChamberOriginAuthority origin,
            List<Participant> participants,List<SpaceLease> spaceLeases,SessionState state,boolean restoreEntryEffectOnReturn,
            SessionSemantics semantics) {
        this(sessionUuid,chamberUuid,origin,participants,spaceLeases,state,restoreEntryEffectOnReturn,semantics,
                Optional.empty(),List.of(),Optional.empty());
    }
    public SessionRecoveryRecord(UUID sessionUuid,UUID chamberUuid,ChamberOriginAuthority origin,
            List<Participant> participants,List<SpaceLease> spaceLeases,SessionState state,boolean restoreEntryEffectOnReturn) {
        this(sessionUuid,chamberUuid,origin,participants,spaceLeases,state,restoreEntryEffectOnReturn,SessionSemantics.LEGACY_FORWARD_CONSUMED);
    }
    /** 新 candidate session 必須顯式提供凍結 context，且從 SELECTABLE 與空 ledger 開始。 */
    public static SessionRecoveryRecord candidateAware(UUID sessionUuid, UUID chamberUuid, ChamberOriginAuthority origin,
            List<Participant> participants, List<SpaceLease> spaceLeases, SessionState state, boolean restoreEntryEffectOnReturn,
            SessionSemantics semantics, CandidatePolicySnapshot context) {
        return new SessionRecoveryRecord(sessionUuid,chamberUuid,origin,participants,spaceLeases,state,restoreEntryEffectOnReturn,
                semantics,Optional.of(context),List.of(),Optional.of(new CandidateSelection.Selectable()));
    }
    public SessionRecoveryRecord {
        Objects.requireNonNull(sessionUuid, "sessionUuid");
        Objects.requireNonNull(chamberUuid, "chamberUuid");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(state, "state");
        if (!chamberUuid.equals(origin.chamberUuid()) || origin.instanceKind() != ChamberInstanceKind.ORIGIN
                || origin.facing().getAxis().isVertical()) {
            throw new IllegalArgumentException("來源艙權威身分不合法");
        }
        Identifier expectedWorld = switch (origin.role()) {
            case OVERWORLD -> World.OVERWORLD.getValue();
            case NETHER -> World.NETHER.getValue();
            case END -> World.END.getValue();
        };
        if (!expectedWorld.equals(origin.worldKey())) throw new IllegalArgumentException("來源必須對應原生世界角色");
        Objects.requireNonNull(semantics,"semantics").validateEffectPolicy(state,restoreEntryEffectOnReturn);
        participants = List.copyOf(participants);
        spaceLeases = List.copyOf(spaceLeases);
        if (participants.isEmpty() || spaceLeases.isEmpty()) throw new IllegalArgumentException("參與者與空間 lease 不得為空");
        var players = new HashSet<UUID>();
        for (var participant : participants) {
            if (!players.add(participant.playerUuid())) throw new IllegalArgumentException("參與者 UUID 重複");
        }
        var slots = new HashSet<Integer>();
        for (var lease : spaceLeases) {
            if (!slots.add(lease.slotId())) throw new IllegalArgumentException("空間 slot 重複");
        }
        Objects.requireNonNull(candidateContext, "candidateContext");
        Objects.requireNonNull(candidateSelection, "candidateSelection");
        candidateLedger = List.copyOf(candidateLedger);
        if (candidateContext.isEmpty()) {
            if (!candidateLedger.isEmpty() || candidateSelection.isPresent() || state == SessionState.MEASURED) {
                throw new IllegalArgumentException("legacy record 不得包含候選或測量狀態");
            }
        } else {
            var selection = candidateSelection.orElseThrow(() -> new IllegalArgumentException("候選 session 必須包含 selection"));
            if ((state == SessionState.MEASURED) != (selection instanceof CandidateSelection.Selected)) {
                throw new IllegalArgumentException("session 狀態與 selection 不一致");
            }
            var doors = new HashSet<DoorKey>(); var ids = new HashSet<CandidateId>();
            for (var entry : candidateLedger) {
                if (!sessionUuid.equals(entry.doorKey().sessionUuid()) || !doors.add(entry.doorKey())
                        || !ids.add(entry.candidate().candidateId())) {
                    throw new IllegalArgumentException("ledger 的 session、DoorKey 或 CandidateId 不合法");
                }
            }
            if (selection instanceof CandidateSelection.Selected selected) {
                if (!sessionUuid.equals(selected.doorKey().sessionUuid()) || candidateLedger.stream().noneMatch(entry ->
                        entry.doorKey().equals(selected.doorKey()) && entry.candidate().candidateId().equals(selected.candidateId()))) {
                    throw new IllegalArgumentException("選擇必須精確對應同一 session 的 ledger entry");
                }
            }
            candidateLedger = candidateLedger.stream().sorted(LEDGER_ORDER).toList();
        }
    }

    /** 只更新恢復進度，完整保留已承認的候選 context、ledger 與 selection。 */
    public SessionRecoveryRecord withProgress(List<Participant> people, List<SpaceLease> leases,
            SessionState phase, boolean restoreEffect) {
        return new SessionRecoveryRecord(sessionUuid,chamberUuid,origin,people,leases,phase,restoreEffect,semantics,
                candidateContext,candidateLedger,candidateSelection);
    }

    public record Participant(UUID playerUuid, Vec3d sourcePosition, Vec3d sourceVelocity,
            float yaw, float pitch, NbtCompound quantumStateSnapshot, boolean returned) {
        public Participant {
            Objects.requireNonNull(playerUuid, "playerUuid");
            finite(sourcePosition); finite(sourceVelocity);
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("視角必須為有限值");
            quantumStateSnapshot = Objects.requireNonNull(quantumStateSnapshot, "quantumStateSnapshot").copy();
        }
        @Override public NbtCompound quantumStateSnapshot() { return quantumStateSnapshot.copy(); }
    }

    /** 方向性的 authority 檢查：next 只能保留或追加 previous 已承認的候選事實。 */
    public static boolean sameAuthority(SessionRecoveryRecord previous,SessionRecoveryRecord next) {
        return previous.sessionUuid().equals(next.sessionUuid()) && previous.chamberUuid().equals(next.chamberUuid())
                && previous.origin().equals(next.origin()) && previous.semantics()==next.semantics()
                && sameParticipantSources(previous.participants(),next.participants())
                && previous.candidateContext().equals(next.candidateContext())
                && new HashSet<>(next.candidateLedger()).containsAll(previous.candidateLedger())
                && (previous.candidateSelection().orElse(null) instanceof CandidateSelection.Selectable
                    || previous.candidateSelection().equals(next.candidateSelection()));
    }

    /** 以 UUID 比較凍結來源；順序與 returned 進度不是來源快照的一部分。 */
    public static boolean sameParticipantSources(List<Participant> expected, List<Participant> actual) {
        if (expected.size()!=actual.size()) return false;
        var sources=new java.util.HashMap<UUID,Participant>();
        for (var person : expected) if (sources.put(person.playerUuid(),person)!=null) return false;
        for (var person : actual) {
            var source=sources.remove(person.playerUuid());
            if (source==null || !source.sourcePosition().equals(person.sourcePosition()) || !source.sourceVelocity().equals(person.sourceVelocity())
                    || Float.compare(source.yaw(),person.yaw())!=0 || Float.compare(source.pitch(),person.pitch())!=0
                    || !source.quantumStateSnapshot().equals(person.quantumStateSnapshot())) return false;
        }
        return sources.isEmpty();
    }

    public record SpaceLease(int slotId, BlockBox bounds) {
        public SpaceLease {
            if (slotId < 0) throw new IllegalArgumentException("slotId 不得為負數");
            bounds = copyBox(Objects.requireNonNull(bounds, "bounds"));
        }
        @Override public BlockBox bounds() { return copyBox(bounds); }
        private static BlockBox copyBox(BlockBox box) {
            return new BlockBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        }
    }

    NbtCompound toNbt() {
        var nbt = new NbtCompound();
        nbt.putUuid("SessionUuid", sessionUuid); nbt.putUuid("ChamberUuid", chamberUuid);
        var source = new NbtCompound();
        source.putUuid("ChamberUuid", origin.chamberUuid());
        source.putString("WorldKey", origin.worldKey().toString()); source.putString("Role", origin.role().name());
        var pos = origin.controllerPos();
        source.putIntArray("ControllerPos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
        source.putString("Facing", origin.facing().name()); source.putString("InstanceKind", origin.instanceKind().name());
        nbt.put("Origin", source);
        var people = new NbtList();
        for (var participant : participants) {
            var person = new NbtCompound();
            person.putUuid("PlayerUuid", participant.playerUuid());
            person.put("SourcePosition", vector(participant.sourcePosition()));
            person.put("SourceVelocity", vector(participant.sourceVelocity()));
            person.putFloat("Yaw", participant.yaw()); person.putFloat("Pitch", participant.pitch());
            person.put("QuantumState", participant.quantumStateSnapshot());
            person.putBoolean("Returned", participant.returned()); people.add(person);
        }
        nbt.put("Participants", people);
        var leases = new NbtList();
        for (var lease : spaceLeases) {
            var encoded = new NbtCompound(); var box = lease.bounds();
            encoded.putInt("SlotId", lease.slotId());
            encoded.putIntArray("Bounds", new int[] {box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()});
            leases.add(encoded);
        }
        nbt.put("SpaceLeases", leases);
        nbt.putString("State", state.name()); nbt.putBoolean("RestoreEntryEffectOnReturn", restoreEntryEffectOnReturn);
        nbt.putString("SessionSemantics",semantics.name());
        if (candidateContext.isPresent()) {
            nbt.put("CandidateContext", CandidateJournalCodec.context(candidateContext.orElseThrow()));
            var ledger = new NbtList();
            candidateLedger.forEach(entry -> ledger.add(CandidateJournalCodec.entry(entry)));
            nbt.put("CandidateLedger", ledger);
            nbt.put("CandidateSelection", CandidateJournalCodec.selection(candidateSelection.orElseThrow()));
        }
        return nbt;
    }

    static SessionRecoveryRecord fromNbt(NbtCompound nbt,int envelopeSchema) {
        if (envelopeSchema!=1 && envelopeSchema!=2 && envelopeSchema!=3) throw new IllegalArgumentException("不支援 journal schema");
        if (envelopeSchema == 3) {
            CandidateJournalCodec.keys(nbt, "SessionUuid", "ChamberUuid", "Origin", "Participants", "SpaceLeases", "State",
                    "RestoreEntryEffectOnReturn", "SessionSemantics", "CandidateContext", "CandidateLedger", "CandidateSelection");
        }
        if (envelopeSchema<3 && (nbt.contains("CandidateContext") || nbt.contains("CandidateLedger") || nbt.contains("CandidateSelection"))) {
            throw new IllegalArgumentException("legacy schema 不得攜帶候選 payload");
        }
        var semantics=envelopeSchema==1 ? SessionSemantics.LEGACY_FORWARD_CONSUMED
                : SessionSemantics.valueOf(string(nbt,"SessionSemantics"));
        var source = compound(nbt, "Origin");
        int[] pos = ints(source, "ControllerPos", 3);
        var origin = new ChamberOriginAuthority(uuid(source, "ChamberUuid"), Identifier.of(string(source, "WorldKey")),
                DimensionRole.valueOf(string(source, "Role")), new BlockPos(pos[0], pos[1], pos[2]),
                Direction.valueOf(string(source, "Facing")), ChamberInstanceKind.valueOf(string(source, "InstanceKind")));
        var people = new ArrayList<Participant>();
        for (var raw : compounds(nbt, "Participants", envelopeSchema)) {
            var person = (NbtCompound) raw;
            requireType(person, "Yaw", NbtElement.FLOAT_TYPE); requireType(person, "Pitch", NbtElement.FLOAT_TYPE);
            people.add(new Participant(uuid(person, "PlayerUuid"), vector(person, "SourcePosition"),
                    vector(person, "SourceVelocity"), person.getFloat("Yaw"), person.getFloat("Pitch"),
                    compound(person, "QuantumState"), bool(person, "Returned")));
        }
        var leases = new ArrayList<SpaceLease>();
        for (var raw : compounds(nbt, "SpaceLeases", envelopeSchema)) {
            var lease = (NbtCompound) raw; requireType(lease, "SlotId", NbtElement.INT_TYPE);
            int[] bounds = ints(lease, "Bounds", 6);
            // BlockBox 會自行交換反向座標；必須在建構前拒絕壞資料。
            if (bounds[0] > bounds[3] || bounds[1] > bounds[4] || bounds[2] > bounds[5]) {
                throw new IllegalArgumentException("空間邊界反向");
            }
            leases.add(new SpaceLease(lease.getInt("SlotId"), new BlockBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])));
        }
        var context = Optional.<CandidatePolicySnapshot>empty();
        var ledger = new ArrayList<CandidateLedgerEntry>();
        var selection = Optional.<CandidateSelection>empty();
        if (envelopeSchema == 3) {
            context = Optional.of(CandidateJournalCodec.context(compound(nbt, "CandidateContext")));
            CandidateLedgerEntry previous = null;
            for (var raw : compounds(nbt, "CandidateLedger", envelopeSchema)) {
                var entry = CandidateJournalCodec.entry((NbtCompound) raw);
                if (previous != null && LEDGER_ORDER.compare(previous, entry) >= 0) {
                    throw new IllegalArgumentException("schema 3 ledger 必須依 station 與 wall side 嚴格遞增");
                }
                ledger.add(entry); previous = entry;
            }
            selection = Optional.of(CandidateJournalCodec.selection(compound(nbt, "CandidateSelection")));
        }
        return new SessionRecoveryRecord(uuid(nbt, "SessionUuid"), uuid(nbt, "ChamberUuid"), origin, people, leases,
                SessionState.valueOf(string(nbt, "State")), bool(nbt, "RestoreEntryEffectOnReturn"),semantics,context,ledger,selection);
    }

    static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) throw new IllegalArgumentException(key + " 缺失或 NBT 型別錯誤");
    }
    static UUID uuid(NbtCompound nbt, String key) {
        if (!nbt.containsUuid(key)) throw new IllegalArgumentException(key + " 必須是有效 UUID");
        return nbt.getUuid(key);
    }
    static String string(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.STRING_TYPE); return nbt.getString(key);
    }
    static NbtCompound compound(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.COMPOUND_TYPE); return nbt.getCompound(key);
    }
    static NbtList compounds(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.LIST_TYPE);
        var list = (NbtList) nbt.get(key);
        if (list.getHeldType() != NbtElement.COMPOUND_TYPE && !(list.isEmpty() && list.getHeldType() == NbtElement.END_TYPE)) {
            throw new IllegalArgumentException(key + " 必須包含 compound；空 list 僅允許 END 或 COMPOUND held type");
        }
        return list;
    }
    static NbtList compounds(NbtCompound nbt, String key, int envelopeSchema) {
        var list = compounds(nbt, key);
        if (envelopeSchema == 3 && list.isEmpty() && list.getHeldType() != NbtElement.END_TYPE) {
            throw new IllegalArgumentException(key + " 的 schema 3 空 list 必須使用 END held type");
        }
        return list;
    }
    private static int[] ints(NbtCompound nbt, String key, int length) {
        requireType(nbt, key, NbtElement.INT_ARRAY_TYPE); var values = nbt.getIntArray(key);
        if (values.length != length) throw new IllegalArgumentException(key + " 座標數量錯誤");
        return values;
    }
    private static boolean bool(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.BYTE_TYPE); byte value = nbt.getByte(key);
        if (value != 0 && value != 1) throw new IllegalArgumentException(key + " 必須為 0 或 1");
        return value == 1;
    }
    private static void finite(Vec3d vector) {
        Objects.requireNonNull(vector, "vector");
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException("向量必須為有限值");
        }
    }
    private static NbtList vector(Vec3d vector) {
        var list = new NbtList(); list.add(NbtDouble.of(vector.x)); list.add(NbtDouble.of(vector.y)); list.add(NbtDouble.of(vector.z)); return list;
    }
    private static Vec3d vector(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.LIST_TYPE); var list = (NbtList) nbt.get(key);
        if (list.size() != 3 || list.getHeldType() != NbtElement.DOUBLE_TYPE) throw new IllegalArgumentException(key + " 必須為三個 double");
        return new Vec3d(list.getDouble(0), list.getDouble(1), list.getDouble(2));
    }
}
