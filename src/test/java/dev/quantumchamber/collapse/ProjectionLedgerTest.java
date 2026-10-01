package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.collapse.ProjectionLedger.Entry.Status;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ProjectionLedgerTest {
    static final UniverseId UNIVERSE = UniverseId.of(UUID.fromString("3e80969f-13f2-8df7-aca6-87f2863b0480"));

    @Test void codecRoundtripsEveryFacingAndStatusExactlyInWriteOrder() {
        var ledger = ProjectionLedger.empty();
        int index = 0;
        for (var facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            var entry = entry(index++, facing, Status.PENDING);
            ledger = ledger.with(entry);
            if (facing.getAxis() == Direction.Axis.X) ledger = ledger.with(entry.withStatus(Status.COMPLETE));
        }
        var nbt = ledger.toNbt();
        assertEquals(Set.of("SchemaVersion", "Entries"), nbt.getKeys());
        var row = ((NbtList) nbt.get("Entries")).getCompound(0);
        assertEquals(Set.of("ChamberUuid", "UniverseId", "Role", "ControllerPos", "OutwardFacing", "Status"), row.getKeys());
        assertTrue(row.contains("ChamberUuid", NbtElement.INT_ARRAY_TYPE) && row.contains("UniverseId", NbtElement.INT_ARRAY_TYPE)
                && row.contains("Role", NbtElement.STRING_TYPE) && row.contains("ControllerPos", NbtElement.INT_ARRAY_TYPE)
                && row.contains("OutwardFacing", NbtElement.STRING_TYPE) && row.contains("Status", NbtElement.STRING_TYPE));
        var decoded = ProjectionLedger.fromNbt(nbt);
        assertEquals(ledger.entries(), decoded.entries());
        assertEquals(List.of(Status.PENDING, Status.PENDING, Status.COMPLETE, Status.COMPLETE),
                decoded.entries().stream().map(ProjectionLedger.Entry::status).toList(), "PENDING→COMPLETE 原位取代，順序不變");
        assertEquals(nbt, decoded.toNbt());
        var empty = ProjectionLedger.empty().toNbt();
        assertEquals(NbtElement.END_TYPE, ((NbtList) empty.get("Entries")).getHeldType());
        assertEquals(ProjectionLedger.empty(), ProjectionLedger.fromNbt(empty));
    }

    @Test void transitionsAreOnlyPendingThenCompleteAndKeysAreUnique() {
        var pending = entry(1, Direction.NORTH, Status.PENDING);
        var one = ProjectionLedger.empty().with(pending);
        var complete = one.with(pending.withStatus(Status.COMPLETE));
        assertEquals(List.of(pending), one.entries(), "寫入回傳新 ledger，原本的不變");
        assertEquals(Status.COMPLETE, complete.find(pending.chamberUuid(), UNIVERSE).orElseThrow().status());
        assertTrue(complete.find(pending.chamberUuid(), UniverseId.of(UUID.randomUUID())).isEmpty());
        var moved = new ProjectionLedger.Entry(pending.chamberUuid(), UNIVERSE, DimensionRole.OVERWORLD, pending.controllerPos().up(), Direction.NORTH, Status.COMPLETE);
        var turned = new ProjectionLedger.Entry(pending.chamberUuid(), UNIVERSE, DimensionRole.OVERWORLD, pending.controllerPos(), Direction.EAST, Status.COMPLETE);
        var nether = new ProjectionLedger.Entry(pending.chamberUuid(), UNIVERSE, DimensionRole.NETHER, pending.controllerPos(), Direction.NORTH, Status.COMPLETE);
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> ProjectionLedger.empty().with(pending.withStatus(Status.COMPLETE)), "新 key 只能寫 PENDING"),
            () -> assertThrows(IllegalArgumentException.class, () -> one.with(pending), "同狀態重寫不是轉換"),
            () -> assertThrows(IllegalArgumentException.class, () -> complete.with(pending), "COMPLETE 不能退回 PENDING"),
            () -> assertThrows(IllegalArgumentException.class, () -> complete.with(pending.withStatus(Status.COMPLETE)), "COMPLETE 不再寫"),
            () -> assertThrows(IllegalArgumentException.class, () -> one.with(moved), "ControllerPos 不可改變"),
            () -> assertThrows(IllegalArgumentException.class, () -> one.with(turned), "OutwardFacing 不可改變"),
            () -> assertThrows(IllegalArgumentException.class, () -> one.with(nether), "Role 不可改變"),
            () -> assertEquals(List.of(pending), one.entries()));
        assertTrue(pending.sameSite(pending.withStatus(Status.COMPLETE)));
        assertFalse(pending.sameSite(moved));
        assertFalse(pending.sameSite(turned));
        assertFalse(pending.sameSite(nether));
    }

    @Test void entryRequiresAHorizontalFacing() {
        assertThrows(IllegalArgumentException.class, () -> entry(1, Direction.UP, Status.PENDING));
        assertThrows(IllegalArgumentException.class, () -> entry(1, Direction.DOWN, Status.PENDING));
        assertThrows(NullPointerException.class, () -> new ProjectionLedger.Entry(null, UNIVERSE, DimensionRole.OVERWORLD, BlockPos.ORIGIN, Direction.NORTH, Status.PENDING));
        var mutable = new BlockPos.Mutable(1, 2, 3);
        var entry = new ProjectionLedger.Entry(UUID.randomUUID(), UNIVERSE, DimensionRole.OVERWORLD, mutable, Direction.NORTH, Status.PENDING);
        mutable.set(9, 9, 9);
        assertEquals(new BlockPos(1, 2, 3), entry.controllerPos(), "ControllerPos 存不可變副本");
    }

    @Test void strictDecodeRejectsMalformedLedgers() throws Exception {
        var valid = ProjectionLedger.empty().with(entry(1, Direction.NORTH, Status.PENDING)).with(entry(2, Direction.EAST, Status.PENDING)).toNbt();
        var cases = new ArrayList<Executable>();
        rejects(cases, "schema 2", valid, nbt -> nbt.putInt("SchemaVersion", 2));
        rejects(cases, "schema 型別", valid, nbt -> nbt.putString("SchemaVersion", "1"));
        rejects(cases, "缺 Entries", valid, nbt -> nbt.remove("Entries"));
        rejects(cases, "root 多 key", valid, nbt -> nbt.putInt("Unexpected", 1));
        rejects(cases, "Entries 內容不是 compound", valid, nbt -> { var list = new NbtList(); list.add(NbtString.of("bad")); nbt.put("Entries", list); });
        var emptyCompound = UniverseAllocationLedgerTest.emptyList(NbtElement.COMPOUND_TYPE);
        rejects(cases, "空 Entries 必須是 END held type", valid, nbt -> nbt.put("Entries", emptyCompound));
        for (var key : List.of("ChamberUuid", "UniverseId", "Role", "ControllerPos", "OutwardFacing", "Status")) {
            rejects(cases, "缺 " + key, valid, nbt -> row(nbt, 1).remove(key));
            rejects(cases, key + " 型別", valid, nbt -> row(nbt, 1).putDouble(key, 1.0));
        }
        rejects(cases, "entry 多 key", valid, nbt -> row(nbt, 1).putInt("Unexpected", 1));
        rejects(cases, "ChamberUuid 三個 int", valid, nbt -> row(nbt, 1).putIntArray("ChamberUuid", new int[3]));
        rejects(cases, "UniverseId 五個 int", valid, nbt -> row(nbt, 1).putIntArray("UniverseId", new int[5]));
        rejects(cases, "ControllerPos 兩個 int", valid, nbt -> row(nbt, 1).putIntArray("ControllerPos", new int[2]));
        rejects(cases, "ControllerPos 四個 int", valid, nbt -> row(nbt, 1).putIntArray("ControllerPos", new int[4]));
        rejects(cases, "未知 Role", valid, nbt -> row(nbt, 1).putString("Role", "AETHER"));
        rejects(cases, "OutwardFacing 小寫", valid, nbt -> row(nbt, 1).putString("OutwardFacing", "east"));
        rejects(cases, "OutwardFacing 垂直", valid, nbt -> row(nbt, 1).putString("OutwardFacing", "UP"));
        rejects(cases, "未知 Status", valid, nbt -> row(nbt, 1).putString("Status", "DONE"));
        rejects(cases, "(ChamberUuid, UniverseId) 重複", valid, nbt -> row(nbt, 1).putUuid("ChamberUuid", row(nbt, 0).getUuid("ChamberUuid")));
        assertAll(cases);
    }

    @Test void entryCountIsCheckedBeforeDecodingAnyEntry() {
        int limit = ProjectionLedger.MAX_ENTRIES;
        assertEquals(16384, limit);
        var rows = new NbtList();
        for (int index = 0; index < limit; index++) rows.add(row(ProjectionLedger.empty().with(entry(index, Direction.SOUTH, Status.PENDING)).toNbt(), 0).copy());
        var atLimit = new NbtCompound(); atLimit.putInt("SchemaVersion", 1); atLimit.put("Entries", rows);
        var ledger = ProjectionLedger.fromNbt(atLimit);
        assertEquals(limit, ledger.entries().size());
        assertEquals(atLimit, ledger.toNbt(), "恰好上限照常 exact 寫回");
        var overflow = assertThrows(IllegalArgumentException.class, () -> ledger.with(entry(limit, Direction.SOUTH, Status.PENDING)));
        assertTrue(overflow.getMessage().contains("上限"), overflow.getMessage());
        var oneMore = atLimit.copy(); ((NbtList) oneMore.get("Entries")).add(rows.getCompound(0).copy());
        var tooMany = assertThrows(IllegalArgumentException.class, () -> ProjectionLedger.fromNbt(oneMore));
        assertTrue(tooMany.getMessage().contains("上限"), tooMany.getMessage());
        var blanks = new NbtList();
        for (int index = 0; index <= limit; index++) blanks.add(new NbtCompound());
        var empties = new NbtCompound(); empties.putInt("SchemaVersion", 1); empties.put("Entries", blanks);
        var early = assertThrows(IllegalArgumentException.class, () -> ProjectionLedger.fromNbt(empties));
        assertTrue(early.getMessage().contains("上限"), "必須在逐筆解碼前就以上限拒絕：" + early.getMessage());
    }

    static ProjectionLedger.Entry entry(int seed, Direction facing, Status status) {
        return new ProjectionLedger.Entry(new UUID(0x1000L, seed), UNIVERSE, DimensionRole.OVERWORLD, new BlockPos(seed * 16, 64, -seed), facing, status);
    }

    private static NbtCompound row(NbtCompound nbt, int index) { return ((NbtList) nbt.get("Entries")).getCompound(index); }

    private static void rejects(List<Executable> cases, String name, NbtCompound valid, Consumer<NbtCompound> mutation) {
        var mutated = valid.copy();
        mutation.accept(mutated);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> ProjectionLedger.fromNbt(mutated), name));
    }
}
