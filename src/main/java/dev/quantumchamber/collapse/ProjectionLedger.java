package dev.quantumchamber.collapse;

import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * spec §6.5 的 projection ledger：{@code <save>/data/quantumchamber_projections.dat}，schema 1，exact keys。
 * {@code (ChamberUuid, UniverseId)} 唯一；Status 只能 PENDING→COMPLETE；不刪除 entry。
 * <p>本類別是不可變的內容（strict codec 與狀態轉換）；檔案的 checked 寫入、讀回、初始化與收斂由 {@link CollapseAuthorities} 負責，
 * 它也負責把不合法的寫入對應成結果狀態。不是 PersistentState（spec §6 收斂共通第 1 點）。實體交叉檢查（§6.5）屬於 F4b。
 */
public final class ProjectionLedger {
    public static final String FILE_NAME = "quantumchamber_projections.dat";
    static final int SCHEMA_VERSION = 1;
    /** 逐筆解碼前檢查的筆數上限，理由與數值同 {@link UniverseAllocationLedger#MAX_ENTRIES}；寫入新 key 時也先檢查。 */
    public static final int MAX_ENTRIES = UniverseAllocationLedger.MAX_ENTRIES;
    private static final Set<String> ROOT_KEYS = Set.of("SchemaVersion", "Entries");
    private static final Set<String> ENTRY_KEYS = Set.of("ChamberUuid", "UniverseId", "Role", "ControllerPos", "OutwardFacing", "Status");
    private static final ProjectionLedger EMPTY = new ProjectionLedger(List.of());

    /** 一座來源艙在一個宇宙的投影。outwardFacing 必須是水平方向（與來源艙的 Origin 相同）。 */
    public record Entry(UUID chamberUuid, UniverseId universeId, DimensionRole role, BlockPos controllerPos,
            Direction outwardFacing, Status status) {
        public enum Status { PENDING, COMPLETE }

        public Entry {
            Objects.requireNonNull(chamberUuid, "chamberUuid");
            Objects.requireNonNull(universeId, "universeId");
            Objects.requireNonNull(role, "role");
            controllerPos = Objects.requireNonNull(controllerPos, "controllerPos").toImmutable();
            Objects.requireNonNull(outwardFacing, "outwardFacing");
            Objects.requireNonNull(status, "status");
            if (outwardFacing.getAxis().isVertical()) throw new IllegalArgumentException("投影的 OutwardFacing 必須是水平方向");
        }

        public Entry withStatus(Status next) {
            return new Entry(chamberUuid, universeId, role, controllerPos, outwardFacing, next);
        }

        /** 同一個 (ChamberUuid, UniverseId) 的投影位置是否相同：Role、ControllerPos、OutwardFacing（spec §6.5 的既有 entry 驗證），不比 Status。 */
        public boolean sameSite(Entry other) {
            return chamberUuid.equals(other.chamberUuid) && universeId.equals(other.universeId) && role == other.role
                    && controllerPos.equals(other.controllerPos) && outwardFacing == other.outwardFacing;
        }
    }

    private final List<Entry> entries;

    private ProjectionLedger(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var keys = new HashSet<List<Object>>();
        for (var entry : this.entries) {
            if (!keys.add(List.of(entry.chamberUuid(), entry.universeId()))) {
                throw new IllegalArgumentException("projection ledger 的 (ChamberUuid, UniverseId) 重複");
            }
        }
        if (this.entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("projection ledger 超過 " + MAX_ENTRIES + " 筆");
    }

    public static ProjectionLedger empty() { return EMPTY; }

    /** 依寫入順序的全部 entry（不可變）。 */
    public List<Entry> entries() { return entries; }

    public Optional<Entry> find(UUID chamberUuid, UniverseId universeId) {
        return entries.stream().filter(entry -> entry.chamberUuid().equals(chamberUuid) && entry.universeId().equals(universeId)).findFirst();
    }

    /**
     * 合法的單筆寫入，回傳新 ledger：新 key 只能寫 PENDING（追加在尾端）；既有 key 的 site 必須相同，而且只能 PENDING→COMPLETE（原位取代）。
     * 其他情況（含同狀態重寫、已達上限）丟 {@link IllegalArgumentException}，本 ledger 不變。
     */
    ProjectionLedger with(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        var existing = find(entry.chamberUuid(), entry.universeId());
        var next = new ArrayList<>(entries);
        if (existing.isEmpty()) {
            if (entry.status() != Entry.Status.PENDING) throw new IllegalArgumentException("新的投影只能先寫 PENDING");
            if (entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("projection ledger 已達 " + MAX_ENTRIES + " 筆上限");
            next.add(entry);
        } else {
            var previous = existing.orElseThrow();
            if (!previous.sameSite(entry)) throw new IllegalArgumentException("既有投影的 Role／ControllerPos／OutwardFacing 不可改變");
            if (previous.status() != Entry.Status.PENDING || entry.status() != Entry.Status.COMPLETE) {
                throw new IllegalArgumentException("投影 Status 只能 PENDING→COMPLETE");
            }
            next.set(entries.indexOf(previous), entry);
        }
        return new ProjectionLedger(next);
    }

    NbtCompound toNbt() {
        var list = new NbtList();
        for (var entry : entries) {
            var row = new NbtCompound();
            row.putUuid("ChamberUuid", entry.chamberUuid());
            row.putUuid("UniverseId", entry.universeId().value());
            row.putString("Role", entry.role().name());
            var pos = entry.controllerPos();
            row.putIntArray("ControllerPos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            row.putString("OutwardFacing", entry.outwardFacing().name());
            row.putString("Status", entry.status().name());
            list.add(row);
        }
        var root = new NbtCompound();
        root.putInt("SchemaVersion", SCHEMA_VERSION);
        root.put("Entries", list);
        return root;
    }

    /** strict decode：root 與每筆 entry 都必須恰為 exact keys；筆數上限在逐筆解碼之前檢查。任何不符丟 {@link IllegalArgumentException}。 */
    static ProjectionLedger fromNbt(NbtCompound nbt) {
        Objects.requireNonNull(nbt, "nbt");
        if (!nbt.getKeys().equals(ROOT_KEYS)) throw new IllegalArgumentException("projection ledger root 欄位缺失或含未知欄位");
        LedgerNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        if (nbt.getInt("SchemaVersion") != SCHEMA_VERSION) throw new IllegalArgumentException("不支援的 projection ledger schema");
        var list = LedgerNbt.compounds(nbt, "Entries", MAX_ENTRIES);
        var decoded = new ArrayList<Entry>(list.size());
        for (int index = 0; index < list.size(); index++) {
            var row = list.getCompound(index);
            if (!row.getKeys().equals(ENTRY_KEYS)) throw new IllegalArgumentException("projection ledger Entries[" + index + "] 欄位缺失或含未知欄位");
            var chamber = LedgerNbt.uuid(row, "ChamberUuid");
            var universeId = UniverseId.of(LedgerNbt.uuid(row, "UniverseId"));
            var role = LedgerNbt.enumValue(DimensionRole.class, row, "Role");
            LedgerNbt.requireType(row, "ControllerPos", NbtElement.INT_ARRAY_TYPE);
            int[] pos = row.getIntArray("ControllerPos");
            if (pos.length != 3) throw new IllegalArgumentException("projection ledger Entries[" + index + "].ControllerPos 必須是三個 int");
            var facing = LedgerNbt.enumValue(Direction.class, row, "OutwardFacing");
            var status = LedgerNbt.enumValue(Entry.Status.class, row, "Status");
            decoded.add(new Entry(chamber, universeId, role, new BlockPos(pos[0], pos[1], pos[2]), facing, status));
        }
        return new ProjectionLedger(decoded);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ProjectionLedger ledger && entries.equals(ledger.entries);
    }

    @Override public int hashCode() { return entries.hashCode(); }

    @Override public String toString() { return "ProjectionLedger[" + entries.size() + " entries]"; }
}
