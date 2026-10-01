package dev.quantumchamber.persistence;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

/** session journal 共用的 strict NBT helper；只做型別、key 與格式檢查，不認得任何 record 或候選語意。 */
final class JournalNbt {
    private JournalNbt() {}

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
        if (envelopeSchema >= 3 && list.isEmpty() && list.getHeldType() != NbtElement.END_TYPE) {
            throw new IllegalArgumentException(key + " 的 schema 3 空 list 必須使用 END held type");
        }
        return list;
    }
    static void keys(NbtCompound nbt, String... expected) {
        if (!nbt.getKeys().equals(Set.of(expected))) throw new IllegalArgumentException("schema 3 payload 欄位缺失或含未知欄位");
    }
    /**
     * 帶位置標籤的 exact key 檢查：訊息以原句開頭，附 where、缺少的 key 與未知 key 的數量。
     * 未知 key 的名稱來自存檔內容，只報數量、不輸出名稱。
     */
    static void keys(String where, NbtCompound nbt, String... expected) {
        var wanted = Set.of(expected); var actual = nbt.getKeys();
        if (actual.equals(wanted)) return;
        var missing = new TreeSet<>(wanted); missing.removeAll(actual);
        long unknown = actual.stream().filter(key -> !wanted.contains(key)).count();
        throw new IllegalArgumentException("schema 3 payload 欄位缺失或含未知欄位：" + where + " missing=" + missing + " unknown=" + unknown);
    }
    /**
     * schema 3 的 Identifier 欄位（目前用於 SourceFamilyRef 與 Origin 的 WorldKey）：必須帶 namespace，且 Identifier 讀回後
     * 與原字串完全相同（拒絕 "overworld"、":overworld"）。where 是錯誤訊息的位置標籤，不輸出原字串。
     */
    static Identifier canonicalIdentifier(String where, NbtCompound nbt, String key) {
        var raw = string(nbt, key); var parsed = Identifier.tryParse(raw);
        if (parsed == null) throw new IllegalArgumentException(where + "." + key + " 不是合法 identifier");
        if (!parsed.toString().equals(raw)) throw new IllegalArgumentException(where + "." + key + " 必須帶 namespace 且為 canonical 形式");
        return parsed;
    }
}
