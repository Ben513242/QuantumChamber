package dev.quantumchamber.persistence;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
        List<Participant> participants, List<SpaceLease> spaceLeases, SessionState state, boolean restoreEntryEffectOnReturn) {
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
        if ((state == SessionState.ARMING && !restoreEntryEffectOnReturn)
                || (state == SessionState.SUPERPOSITION && restoreEntryEffectOnReturn)) {
            throw new IllegalArgumentException("狀態與效果恢復權威決策不一致");
        }
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
        return nbt;
    }

    static SessionRecoveryRecord fromNbt(NbtCompound nbt) {
        var source = compound(nbt, "Origin");
        int[] pos = ints(source, "ControllerPos", 3);
        var origin = new ChamberOriginAuthority(uuid(source, "ChamberUuid"), Identifier.of(string(source, "WorldKey")),
                DimensionRole.valueOf(string(source, "Role")), new BlockPos(pos[0], pos[1], pos[2]),
                Direction.valueOf(string(source, "Facing")), ChamberInstanceKind.valueOf(string(source, "InstanceKind")));
        var people = new ArrayList<Participant>();
        for (var raw : compounds(nbt, "Participants")) {
            var person = (NbtCompound) raw;
            requireType(person, "Yaw", NbtElement.FLOAT_TYPE); requireType(person, "Pitch", NbtElement.FLOAT_TYPE);
            people.add(new Participant(uuid(person, "PlayerUuid"), vector(person, "SourcePosition"),
                    vector(person, "SourceVelocity"), person.getFloat("Yaw"), person.getFloat("Pitch"),
                    compound(person, "QuantumState"), bool(person, "Returned")));
        }
        var leases = new ArrayList<SpaceLease>();
        for (var raw : compounds(nbt, "SpaceLeases")) {
            var lease = (NbtCompound) raw; requireType(lease, "SlotId", NbtElement.INT_TYPE);
            int[] bounds = ints(lease, "Bounds", 6);
            // BlockBox 會自行交換反向座標；必須在建構前拒絕壞資料。
            if (bounds[0] > bounds[3] || bounds[1] > bounds[4] || bounds[2] > bounds[5]) {
                throw new IllegalArgumentException("空間邊界反向");
            }
            leases.add(new SpaceLease(lease.getInt("SlotId"), new BlockBox(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])));
        }
        return new SessionRecoveryRecord(uuid(nbt, "SessionUuid"), uuid(nbt, "ChamberUuid"), origin, people, leases,
                SessionState.valueOf(string(nbt, "State")), bool(nbt, "RestoreEntryEffectOnReturn"));
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
        if (!list.isEmpty() && list.getHeldType() != NbtElement.COMPOUND_TYPE) throw new IllegalArgumentException(key + " 必須包含 compound");
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
