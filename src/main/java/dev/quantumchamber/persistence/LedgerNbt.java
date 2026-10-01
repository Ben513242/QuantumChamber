package dev.quantumchamber.persistence;

import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

/**
 * 裸 NBT 權威檔（M5 的 allocation／projection ledger 與 M4 的 discovery）共用的 strict NBT helper：型別、4-int UUID、enum 名稱，以及
 * compound list 的 held type（空 list 必須是 END）與筆數上限。只做格式檢查，不認得任何權威的語意；任何不符丟 {@link IllegalArgumentException}。
 * 與讀寫這些檔的 {@link CheckedNbtFile} 放在同一個 package。
 * <p>session journal 另有自己的 {@code JournalNbt}（package-private）：它的 list 規則依 journal envelope 的 schema 而不同，訊息也指向 journal，所以不共用。
 */
public final class LedgerNbt {
    private LedgerNbt() {}

    public static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) throw new IllegalArgumentException(key + " 缺失或 NBT 型別錯誤");
    }

    public static UUID uuid(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.INT_ARRAY_TYPE);
        if (nbt.getIntArray(key).length != 4) throw new IllegalArgumentException(key + " 必須是四個 int 的 UUID");
        return nbt.getUuid(key);
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.STRING_TYPE);
        var raw = nbt.getString(key);
        for (var constant : type.getEnumConstants()) if (constant.name().equals(raw)) return constant;
        throw new IllegalArgumentException(key + " 不是合法的 " + type.getSimpleName());
    }

    /** compound list：空 list 必須是 END held type，非空必須是 COMPOUND。沒有筆數上限（由讀檔的 NBT 預算限制）。 */
    public static NbtList compounds(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.LIST_TYPE);
        var list = (NbtList) nbt.get(key);
        int heldType = list.isEmpty() ? NbtElement.END_TYPE : NbtElement.COMPOUND_TYPE;
        if (list.getHeldType() != heldType) throw new IllegalArgumentException(key + " 必須包含 compound；空 list 必須使用 END held type");
        return list;
    }

    /** 同 {@link #compounds(NbtCompound, String)}，另在逐筆解碼之前檢查筆數上限（H1 的做法）。 */
    public static NbtList compounds(NbtCompound nbt, String key, int maxEntries) {
        var list = compounds(nbt, key);
        if (list.size() > maxEntries) throw new IllegalArgumentException(key + " 超過 " + maxEntries + " 筆上限");
        return list;
    }
}
