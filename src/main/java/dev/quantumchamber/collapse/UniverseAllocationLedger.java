package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * spec §6.1 的 allocation ledger：{@code <save>/data/quantumchamber_universe_allocations.dat}，schema 1，只能追加；
 * AllocationToken 唯一、UniverseId 唯一、exact keys。
 * <p>本類別是不可變的內容（推導、strict codec、追加與唯一性）；檔案的 checked 寫入、讀回、初始化與收斂由 {@link CollapseAuthorities} 負責。
 * 它不是 PersistentState（spec §6 收斂共通第 1 點）。
 * <p>strict decode 只驗結構（exact keys、型別、長度、唯一性、筆數上限）；entry 是否「正是本 session 會寫出的內容」（UniverseId 等於推導值、
 * OVERWORLD profile v1）在 lookup 時以 {@link Entry#of} exact 比對（spec §6「既有紀錄的驗證」）。
 */
public final class UniverseAllocationLedger {
    public static final String FILE_NAME = "quantumchamber_universe_allocations.dat";
    static final int SCHEMA_VERSION = 1;
    /**
     * 逐筆解碼前檢查的筆數上限（沿用 H1 為 M4 candidate ledger 加的做法：先比對 list 長度，再逐筆解碼）。數值沿用 M4 的
     * {@code MaxCandidateEntries}（16384）：每筆對應一個 EAGER_ENABLED 的宇宙，每次開服都會全部物化，實際上遠低於此值。
     * 寫滿時以 {@code NbtSizeTracker} 實測的解碼計量：allocation 約 10.0 MB、projection 約 13.4 MB，都在讀檔的 16 MiB 預算之內，
     * 寫滿仍讀得回來。追加時也先檢查，不寫出讀不回來的檔案。
     */
    public static final int MAX_ENTRIES = 16384;
    private static final Set<String> ROOT_KEYS = Set.of("SchemaVersion", "Entries");
    private static final Set<String> ENTRY_KEYS = Set.of("AllocationToken", "UniverseId", "Profile", "ProfileVersion");
    private static final byte[] DOMAIN = "quantumchamber:universe-id:v1".getBytes(StandardCharsets.UTF_8);
    private static final UniverseAllocationLedger EMPTY = new UniverseAllocationLedger(List.of());

    /** ledger 的一筆配置。只驗非 null；與推導值、profile 的一致性由 lookup 以 {@link #of} exact 比對。 */
    public record Entry(CandidateBytes allocationToken, UniverseId universeId, GeneratorProfile profile, int profileVersion) {
        public Entry {
            Objects.requireNonNull(allocationToken, "allocationToken");
            Objects.requireNonNull(universeId, "universeId");
            Objects.requireNonNull(profile, "profile");
        }

        /** 本 session 依 NEW 候選會寫出的 exact entry：UniverseId 為 token 的推導值，profile 與版本取自 intent（M4 已限定 OVERWORLD v1）。 */
        public static Entry of(NewUniverseIntent intent) {
            Objects.requireNonNull(intent, "intent");
            return new Entry(intent.allocationToken(), deriveUniverseId(intent.allocationToken()), intent.profile(), intent.profileVersion());
        }
    }

    private final List<Entry> entries;

    private UniverseAllocationLedger(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var tokens = new HashSet<CandidateBytes>();
        var ids = new HashSet<UniverseId>();
        for (var entry : this.entries) {
            if (!tokens.add(entry.allocationToken())) throw new IllegalArgumentException("allocation ledger 的 AllocationToken 重複");
            if (!ids.add(entry.universeId())) throw new IllegalArgumentException("allocation ledger 的 UniverseId 重複");
        }
        if (this.entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("allocation ledger 超過 " + MAX_ENTRIES + " 筆");
    }

    public static UniverseAllocationLedger empty() { return EMPTY; }

    /**
     * spec §6.1 的 UniverseId 推導：{@code SHA-256(UTF-8("quantumchamber:universe-id:v1") ‖ token)} 取前 16 bytes，
     * 設定 RFC 9562 的 version 8（byte 6 的高 4 位元為 1000）與 variant（byte 8 的高 2 位元為 10）。
     * 推導讓 COLLAPSING 在任何配置之前就能寫下 exact 的 Destination；idempotency 的權威仍是 ledger。
     */
    public static UniverseId deriveUniverseId(CandidateBytes allocationToken) {
        Objects.requireNonNull(allocationToken, "allocationToken");
        byte[] digest;
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update(DOMAIN);
            sha.update(allocationToken.bytes());
            digest = sha.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("UniverseId 推導需要 SHA-256", unavailable);
        }
        digest[6] = (byte) ((digest[6] & 0x0F) | 0x80);
        digest[8] = (byte) ((digest[8] & 0x3F) | 0x80);
        var buffer = ByteBuffer.wrap(digest, 0, 16);
        return UniverseId.of(new UUID(buffer.getLong(), buffer.getLong()));
    }

    /** 依追加順序的全部 entry（不可變）。 */
    public List<Entry> entries() { return entries; }

    public Optional<Entry> byToken(CandidateBytes allocationToken) {
        return entries.stream().filter(entry -> entry.allocationToken().equals(allocationToken)).findFirst();
    }

    public Optional<Entry> byUniverseId(UniverseId universeId) {
        return entries.stream().filter(entry -> entry.universeId().equals(universeId)).findFirst();
    }

    /** 只能追加：回傳多了一筆的新 ledger；token 或 UniverseId 重複、或已達上限時丟 {@link IllegalArgumentException}，本 ledger 不變。 */
    UniverseAllocationLedger append(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("allocation ledger 已達 " + MAX_ENTRIES + " 筆上限");
        var next = new ArrayList<>(entries);
        next.add(entry);
        return new UniverseAllocationLedger(next);
    }

    NbtCompound toNbt() {
        var list = new NbtList();
        for (var entry : entries) {
            var row = new NbtCompound();
            row.putByteArray("AllocationToken", entry.allocationToken().bytes());
            row.putUuid("UniverseId", entry.universeId().value());
            row.putString("Profile", entry.profile().name());
            row.putInt("ProfileVersion", entry.profileVersion());
            list.add(row);
        }
        var root = new NbtCompound();
        root.putInt("SchemaVersion", SCHEMA_VERSION);
        root.put("Entries", list);
        return root;
    }

    /** strict decode：root 與每筆 entry 都必須恰為 exact keys；筆數上限在逐筆解碼之前檢查。任何不符丟 {@link IllegalArgumentException}。 */
    static UniverseAllocationLedger fromNbt(NbtCompound nbt) {
        Objects.requireNonNull(nbt, "nbt");
        if (!nbt.getKeys().equals(ROOT_KEYS)) throw new IllegalArgumentException("allocation ledger root 欄位缺失或含未知欄位");
        LedgerNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        if (nbt.getInt("SchemaVersion") != SCHEMA_VERSION) throw new IllegalArgumentException("不支援的 allocation ledger schema");
        var list = LedgerNbt.compounds(nbt, "Entries", MAX_ENTRIES);
        var decoded = new ArrayList<Entry>(list.size());
        for (int index = 0; index < list.size(); index++) {
            var row = list.getCompound(index);
            if (!row.getKeys().equals(ENTRY_KEYS)) throw new IllegalArgumentException("allocation ledger Entries[" + index + "] 欄位缺失或含未知欄位");
            LedgerNbt.requireType(row, "AllocationToken", NbtElement.BYTE_ARRAY_TYPE);
            byte[] token = row.getByteArray("AllocationToken");
            if (token.length != 32) throw new IllegalArgumentException("allocation ledger Entries[" + index + "].AllocationToken 必須恰為 32 bytes");
            var universeId = UniverseId.of(LedgerNbt.uuid(row, "UniverseId"));
            var profile = LedgerNbt.enumValue(GeneratorProfile.class, row, "Profile");
            LedgerNbt.requireType(row, "ProfileVersion", NbtElement.INT_TYPE);
            decoded.add(new Entry(new CandidateBytes(token), universeId, profile, row.getInt("ProfileVersion")));
        }
        return new UniverseAllocationLedger(decoded);
    }

    @Override public boolean equals(Object other) {
        return other instanceof UniverseAllocationLedger ledger && entries.equals(ledger.entries);
    }

    @Override public int hashCode() { return entries.hashCode(); }

    @Override public String toString() { return "UniverseAllocationLedger[" + entries.size() + " entries]"; }
}
