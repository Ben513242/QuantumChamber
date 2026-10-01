package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class UniverseAllocationLedgerTest {
    /**
     * 固定 test vectors：期望值由獨立工具（Python 3.11 hashlib，見 implementation note F2b 一節）以
     * SHA-256(UTF-8("quantumchamber:universe-id:v1") ‖ token) 的前 16 bytes、設定 version 8 與 variant 10 算出，不經 production helper。
     * "one" 的原始 byte 6／8 是 0xad／0x6c，鎖住 version 與 variant 兩個位元遮罩；其餘三個的原始 byte 8 已是 10xxxxxx。
     */
    private static final String[][] VECTORS = {
        {"0000000000000000000000000000000000000000000000000000000000000000", "99a21863-8079-8d81-adcf-d8b547656a24"},
        {"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "da2b7e94-5767-8933-9bc7-40395d0f2854"},
        {"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "504cc474-b379-8629-9d03-5f901e452b40"},
        {"0100000000000000000000000000000000000000000000000000000000000000", "3e80969f-13f2-8df7-aca6-87f2863b0480"},
    };

    @Test void derivedUniverseIdsMatchTheIndependentVectors() {
        var cases = new ArrayList<Executable>();
        for (var vector : VECTORS) cases.add(() -> {
            var derived = UniverseAllocationLedger.deriveUniverseId(new CandidateBytes(HexFormat.of().parseHex(vector[0])));
            assertEquals(UUID.fromString(vector[1]), derived.value(), "token " + vector[0]);
            assertEquals(8, derived.value().version(), "RFC 9562 version 8");
            assertEquals(2, derived.value().variant(), "RFC 9562 variant 10");
        });
        assertAll(cases);
    }

    @Test void entryForAnIntentUsesTheDerivedIdAndTheIntentProfile() {
        var intent = intent(token(1));
        var entry = UniverseAllocationLedger.Entry.of(intent);
        assertEquals(intent.allocationToken(), entry.allocationToken());
        assertEquals(UniverseAllocationLedger.deriveUniverseId(token(1)), entry.universeId());
        assertEquals(GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, entry.profile());
        assertEquals(1, entry.profileVersion());
    }

    @Test void appendOnlyKeepsOrderAndRejectsDuplicateTokensAndUniverseIds() {
        var first = entry(1); var second = entry(2);
        var empty = UniverseAllocationLedger.empty();
        var one = empty.append(first);
        var two = one.append(second);
        assertEquals(List.of(), empty.entries(), "追加回傳新 ledger，原本的不變");
        assertEquals(List.of(first), one.entries());
        assertEquals(List.of(first, second), two.entries());
        assertEquals(first, two.byToken(token(1)).orElseThrow());
        assertEquals(second, two.byUniverseId(second.universeId()).orElseThrow());
        var sameToken = new UniverseAllocationLedger.Entry(token(1), UniverseId.of(UUID.randomUUID()), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1);
        var sameId = new UniverseAllocationLedger.Entry(token(3), first.universeId(), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1);
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> two.append(sameToken), "token 唯一"),
            () -> assertThrows(IllegalArgumentException.class, () -> two.append(sameId), "UniverseId 唯一"),
            () -> assertEquals(List.of(first, second), two.entries(), "被拒的追加不改變 ledger"));
    }

    @Test void codecRoundtripsExactlyInAppendOrderWithExactKeys() {
        var ledger = UniverseAllocationLedger.empty().append(entry(9)).append(entry(1)).append(entry(5));
        var nbt = ledger.toNbt();
        assertEquals(Set.of("SchemaVersion", "Entries"), nbt.getKeys());
        assertEquals(1, nbt.getInt("SchemaVersion"));
        var row = ((NbtList) nbt.get("Entries")).getCompound(0);
        assertEquals(Set.of("AllocationToken", "UniverseId", "Profile", "ProfileVersion"), row.getKeys());
        assertTrue(row.contains("AllocationToken", NbtElement.BYTE_ARRAY_TYPE) && row.contains("UniverseId", NbtElement.INT_ARRAY_TYPE)
                && row.contains("Profile", NbtElement.STRING_TYPE) && row.contains("ProfileVersion", NbtElement.INT_TYPE));
        var decoded = UniverseAllocationLedger.fromNbt(nbt);
        assertEquals(ledger, decoded);
        assertEquals(ledger.entries(), decoded.entries(), "保留追加順序，不重新排序");
        assertEquals(nbt, decoded.toNbt());
        var empty = UniverseAllocationLedger.empty().toNbt();
        assertEquals(NbtElement.END_TYPE, ((NbtList) empty.get("Entries")).getHeldType());
        assertEquals(UniverseAllocationLedger.empty(), UniverseAllocationLedger.fromNbt(empty));
    }

    @Test void strictDecodeRejectsMalformedLedgers() throws Exception {
        var valid = UniverseAllocationLedger.empty().append(entry(1)).append(entry(2)).toNbt();
        var cases = new ArrayList<Executable>();
        rejects(cases, "schema 2", valid, nbt -> nbt.putInt("SchemaVersion", 2));
        rejects(cases, "schema 型別", valid, nbt -> nbt.putLong("SchemaVersion", 1));
        rejects(cases, "缺 Entries", valid, nbt -> nbt.remove("Entries"));
        rejects(cases, "缺 SchemaVersion", valid, nbt -> nbt.remove("SchemaVersion"));
        rejects(cases, "root 多 key", valid, nbt -> nbt.putInt("Unexpected", 1));
        rejects(cases, "Entries 型別", valid, nbt -> nbt.putString("Entries", "bad"));
        rejects(cases, "Entries 內容不是 compound", valid, nbt -> { var list = new NbtList(); list.add(NbtString.of("bad")); nbt.put("Entries", list); });
        var emptyCompound = emptyList(NbtElement.COMPOUND_TYPE);
        rejects(cases, "空 Entries 必須是 END held type", valid, nbt -> nbt.put("Entries", emptyCompound));
        for (var key : List.of("AllocationToken", "UniverseId", "Profile", "ProfileVersion")) {
            rejects(cases, "缺 " + key, valid, nbt -> row(nbt, 1).remove(key));
            rejects(cases, key + " 型別", valid, nbt -> row(nbt, 1).putDouble(key, 1.0));
        }
        rejects(cases, "entry 多 key", valid, nbt -> row(nbt, 1).putInt("Unexpected", 1));
        rejects(cases, "token 31 bytes", valid, nbt -> row(nbt, 1).putByteArray("AllocationToken", new byte[31]));
        rejects(cases, "token 33 bytes", valid, nbt -> row(nbt, 1).putByteArray("AllocationToken", new byte[33]));
        rejects(cases, "UniverseId 三個 int", valid, nbt -> row(nbt, 1).putIntArray("UniverseId", new int[3]));
        rejects(cases, "未知 Profile", valid, nbt -> row(nbt, 1).putString("Profile", "VANILLA_NETHER_V1"));
        rejects(cases, "Profile 小寫", valid, nbt -> row(nbt, 1).putString("Profile", "vanilla_overworld_shared_seed_v1"));
        rejects(cases, "token 重複", valid, nbt -> row(nbt, 1).putByteArray("AllocationToken", token(1).bytes()));
        rejects(cases, "UniverseId 重複", valid, nbt -> row(nbt, 1).putUuid("UniverseId", entry(1).universeId().value()));
        assertAll(cases);
    }

    /** strict decode 只驗結構；UniverseId 推導值與 profile 版本由 lookup 驗證（spec §6.1、§6「既有紀錄的驗證」），不讓整份 ledger 失效。 */
    @Test void decodeKeepsNonDerivedEntriesForLookupVerification() {
        var forged = new UniverseAllocationLedger.Entry(token(7), UniverseId.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 2);
        var decoded = UniverseAllocationLedger.fromNbt(UniverseAllocationLedger.empty().append(forged).toNbt());
        assertEquals(List.of(forged), decoded.entries());
        assertNotEquals(UniverseAllocationLedger.Entry.of(intent(token(7))), decoded.byToken(token(7)).orElseThrow());
    }

    @Test void entryCountIsCheckedBeforeDecodingAnyEntry() {
        int limit = UniverseAllocationLedger.MAX_ENTRIES;
        assertEquals(16384, limit);
        var full = new ArrayList<UniverseAllocationLedger.Entry>();
        var rows = new NbtList();
        for (int index = 0; index < limit; index++) {
            var entry = entry(index); full.add(entry);
            rows.add(row(UniverseAllocationLedger.empty().append(entry).toNbt(), 0).copy());
        }
        var atLimit = new NbtCompound(); atLimit.putInt("SchemaVersion", 1); atLimit.put("Entries", rows);
        var ledger = UniverseAllocationLedger.fromNbt(atLimit);
        assertEquals(full, ledger.entries(), "恰好上限照常讀回");
        assertEquals(atLimit, ledger.toNbt(), "恰好上限照常 exact 寫回");
        var overflow = assertThrows(IllegalArgumentException.class, () -> ledger.append(entry(limit)), "追加也不能超過上限");
        assertTrue(overflow.getMessage().contains("上限"), overflow.getMessage());
        var valid = atLimit.copy(); ((NbtList) valid.get("Entries")).add(atLimit.getList("Entries", NbtElement.COMPOUND_TYPE).getCompound(0).copy());
        var oneMore = assertThrows(IllegalArgumentException.class, () -> UniverseAllocationLedger.fromNbt(valid));
        assertTrue(oneMore.getMessage().contains("上限"), oneMore.getMessage());
        var blanks = new NbtList();
        for (int index = 0; index <= limit; index++) blanks.add(new NbtCompound());
        var empties = new NbtCompound(); empties.putInt("SchemaVersion", 1); empties.put("Entries", blanks);
        var early = assertThrows(IllegalArgumentException.class, () -> UniverseAllocationLedger.fromNbt(empties));
        assertTrue(early.getMessage().contains("上限"), "必須在逐筆解碼前就以上限拒絕：" + early.getMessage());
    }

    static CandidateBytes token(int seed) {
        var bytes = new byte[32];
        bytes[0] = (byte) seed; bytes[1] = (byte) (seed >>> 8); bytes[31] = 0x5a;
        return new CandidateBytes(bytes);
    }

    static NewUniverseIntent intent(CandidateBytes token) {
        return new NewUniverseIntent(token, GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, new CandidateBytes(new byte[32]));
    }

    static UniverseAllocationLedger.Entry entry(int seed) { return UniverseAllocationLedger.Entry.of(intent(token(seed))); }

    private static NbtCompound row(NbtCompound nbt, int index) { return ((NbtList) nbt.get("Entries")).getCompound(index); }

    private static void rejects(List<Executable> cases, String name, NbtCompound valid, Consumer<NbtCompound> mutation) {
        var mutated = valid.copy();
        mutation.accept(mutated);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> UniverseAllocationLedger.fromNbt(mutated), name));
    }

    /** 以原始 NBT bytes 建立指定 held type 的空 list（NbtList 沒有公開的建構方式；比照 SessionRecoverySchema3Test）。 */
    static NbtList emptyList(byte type) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF(""); out.writeByte(9); out.writeUTF("List"); out.writeByte(type); out.writeInt(0); out.writeByte(0);
        var list = (NbtList) NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), NbtSizeTracker.ofUnlimitedBytes()).get("List");
        assertNotNull(list); assertEquals(type, list.getHeldType()); return list;
    }
}
