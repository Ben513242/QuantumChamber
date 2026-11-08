package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.persistence.LedgerNbt;
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
 * spec §6.1 的 allocation ledger：{@code <save>/data/quantumchamber_universe_allocations.dat}，schema 1/2，只能追加；
 * AllocationToken 唯一、UniverseId 唯一、exact keys。
 * <p>本類別是不可變的內容（推導、strict codec、追加與唯一性）；檔案的 checked 寫入、讀回、初始化與收斂由 {@link CollapseAuthorities} 負責。
 * 它不是 PersistentState（spec §6 收斂共通第 1 點）。
 * <p>strict decode 只驗結構（exact keys、型別、長度、唯一性、筆數上限）；entry 是否「正是本 session 會寫出的內容」（UniverseId 等於推導值、
 * 預期 profile、種子與指紋）在 lookup 時以 {@link Entry#of} exact 比對（spec §6「既有紀錄的驗證」）。
 */
public final class UniverseAllocationLedger {
    public static final String FILE_NAME = "quantumchamber_universe_allocations.dat";
    private static final int LEGACY_SCHEMA = 1;
    private static final int M6_SCHEMA = 2;
    /**
     * 逐筆解碼前檢查的筆數上限（沿用 H1 為 M4 candidate ledger 加的做法：先比對 list 長度，再逐筆解碼）。數值沿用 M4 的
     * {@code MaxCandidateEntries}（16384）：每筆對應一個 EAGER_ENABLED 的宇宙，每次開服都會全部物化，實際上遠低於此值。
     * 舊 schema 寫滿時以 {@code NbtSizeTracker} 實測的解碼計量：allocation 約 10.0 MB、projection 約 13.4 MB，都在讀檔的 16 MiB 預算之內，
     * schema 2 的欄位較多；筆數與解碼容量為分開判定的條件。
     */
    public static final int MAX_ENTRIES = 16384;
    private static final Set<String> ROOT_KEYS = Set.of("SchemaVersion", "Entries");
    private static final Set<String> ENTRY_KEYS = Set.of("AllocationToken", "UniverseId", "Profile", "ProfileVersion");
    private static final Set<String> M6_ENTRY_KEYS = Set.of("EntryVersion", "AllocationToken", "UniverseId",
            "PolicyVersion", "ProfileId", "ProfileVersion", "RootWorldSeed", "TargetSettingsFingerprint");
    private static final byte[] DOMAIN = "quantumchamber:universe-id:v1".getBytes(StandardCharsets.UTF_8);
    private static final UniverseAllocationLedger EMPTY = new UniverseAllocationLedger(LEGACY_SCHEMA, List.of());

    public record M6Fields(int policyVersion, String profileId, long rootWorldSeed, CandidateBytes targetSettingsFingerprint) {
        public M6Fields {
            if ((policyVersion != 1 && policyVersion != 2)
                    || !(NewUniverseIntent.RANDOM_PROFILE_ID.equals(profileId)
                    || policyVersion == 2 && NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(profileId))) {
                throw new IllegalArgumentException("未知的 M6 allocation policy 或 profile");
            }
            Objects.requireNonNull(targetSettingsFingerprint, "targetSettingsFingerprint");
        }
    }

    /** 舊列保留原四欄。新列使用凍結的 M6 欄位組；lookup 透過 {@link #of} 比對完整列。 */
    public record Entry(CandidateBytes allocationToken, UniverseId universeId, GeneratorProfile profile, int profileVersion, M6Fields m6) {
        public Entry(CandidateBytes allocationToken, UniverseId universeId, GeneratorProfile profile, int profileVersion) {
            this(allocationToken, universeId, profile, profileVersion, null);
        }

        public Entry {
            Objects.requireNonNull(allocationToken, "allocationToken");
            Objects.requireNonNull(universeId, "universeId");
            if (m6 == null) {
                Objects.requireNonNull(profile, "profile");
            } else if (profile != null || profileVersion != 1) {
                throw new IllegalArgumentException("M6 allocation 不可含舊 profile，且只支援 profile version 1");
            }
        }

        /** 兩種 NEW 都從凍結的 intent 取得完整列，UUID 皆依 allocation token 推導。 */
        public static Entry of(NewUniverseIntent intent) {
            Objects.requireNonNull(intent, "intent");
            var id = deriveUniverseId(intent.allocationToken());
            if (intent.isM6()) {
                var fields = new M6Fields(intent.policyVersion(), intent.profileId(), intent.rootWorldSeed(),
                        new CandidateBytes(intent.targetSettingsSnapshot().outputFingerprint()));
                return new Entry(intent.allocationToken(), id, null, intent.profileVersion(), fields);
            }
            return new Entry(intent.allocationToken(), id, intent.profile(), intent.profileVersion());
        }
    }

    private final List<Entry> entries;
    private final int schemaVersion;

    private UniverseAllocationLedger(int schemaVersion, List<Entry> entries) {
        this.schemaVersion = schemaVersion;
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

    boolean isSchemaTwo() { return schemaVersion == M6_SCHEMA; }

    void requireReadable() throws java.io.IOException {
        var expected = toNbt();
        var bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(expected, bytes);
        var decoded = dev.quantumchamber.persistence.CheckedNbtFile.parse(bytes.toByteArray());
        if (!expected.equals(decoded) || !equals(fromNbt(decoded))) {
            throw new IllegalArgumentException("allocation 候選於 strict 讀回後不相符");
        }
    }

    UniverseAllocationLedger upgradeEnvelope() {
        return schemaVersion == M6_SCHEMA ? this : new UniverseAllocationLedger(M6_SCHEMA, entries);
    }

    /** 只能追加：回傳多了一筆的新 ledger；token 或 UniverseId 重複、或已達上限時丟 {@link IllegalArgumentException}，本 ledger 不變。 */
    UniverseAllocationLedger append(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("allocation ledger 已達 " + MAX_ENTRIES + " 筆上限");
        if (entry.m6() != null && schemaVersion != M6_SCHEMA) {
            throw new IllegalStateException("追加 M6 row 前須先 checked 升級 envelope");
        }
        var next = new ArrayList<>(entries);
        next.add(entry);
        return new UniverseAllocationLedger(schemaVersion, next);
    }

    NbtCompound toNbt() {
        var list = new NbtList();
        for (var entry : entries) {
            var row = new NbtCompound();
            row.putByteArray("AllocationToken", entry.allocationToken().bytes());
            row.putUuid("UniverseId", entry.universeId().value());
            if (entry.m6() == null) {
                row.putString("Profile", entry.profile().name());
            } else {
                var fields = entry.m6();
                row.putInt("EntryVersion", 2);
                row.putInt("PolicyVersion", fields.policyVersion());
                row.putString("ProfileId", fields.profileId());
                row.putLong("RootWorldSeed", fields.rootWorldSeed());
                row.putByteArray("TargetSettingsFingerprint", fields.targetSettingsFingerprint().bytes());
            }
            row.putInt("ProfileVersion", entry.profileVersion());
            list.add(row);
        }
        var root = new NbtCompound();
        root.putInt("SchemaVersion", schemaVersion);
        root.put("Entries", list);
        return root;
    }

    /** strict decode：root 與每筆 entry 都必須恰為 exact keys；筆數上限在逐筆解碼之前檢查。任何不符丟 {@link IllegalArgumentException}。 */
    static UniverseAllocationLedger fromNbt(NbtCompound nbt) {
        Objects.requireNonNull(nbt, "nbt");
        if (!nbt.getKeys().equals(ROOT_KEYS)) throw new IllegalArgumentException("allocation ledger root 欄位缺失或含未知欄位");
        LedgerNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        int schema = nbt.getInt("SchemaVersion");
        if (schema != LEGACY_SCHEMA && schema != M6_SCHEMA) throw new IllegalArgumentException("不支援的 allocation ledger schema");
        var list = LedgerNbt.compounds(nbt, "Entries", MAX_ENTRIES);
        var decoded = new ArrayList<Entry>(list.size());
        for (int index = 0; index < list.size(); index++) {
            var row = list.getCompound(index);
            boolean m6 = schema == M6_SCHEMA && row.contains("EntryVersion");
            if (!row.getKeys().equals(m6 ? M6_ENTRY_KEYS : ENTRY_KEYS)) throw new IllegalArgumentException("allocation ledger Entries[" + index + "] 欄位缺失或含未知欄位");
            LedgerNbt.requireType(row, "AllocationToken", NbtElement.BYTE_ARRAY_TYPE);
            byte[] token = row.getByteArray("AllocationToken");
            if (token.length != 32) throw new IllegalArgumentException("allocation ledger Entries[" + index + "].AllocationToken 必須恰為 32 bytes");
            var universeId = UniverseId.of(LedgerNbt.uuid(row, "UniverseId"));
            LedgerNbt.requireType(row, "ProfileVersion", NbtElement.INT_TYPE);
            int profileVersion = row.getInt("ProfileVersion");
            if (m6) {
                LedgerNbt.requireType(row, "EntryVersion", NbtElement.INT_TYPE);
                LedgerNbt.requireType(row, "PolicyVersion", NbtElement.INT_TYPE);
                LedgerNbt.requireType(row, "ProfileId", NbtElement.STRING_TYPE);
                LedgerNbt.requireType(row, "RootWorldSeed", NbtElement.LONG_TYPE);
                LedgerNbt.requireType(row, "TargetSettingsFingerprint", NbtElement.BYTE_ARRAY_TYPE);
                if (row.getInt("EntryVersion") != 2) throw new IllegalArgumentException("未知的 allocation row 版本");
                var fields = new M6Fields(row.getInt("PolicyVersion"), row.getString("ProfileId"),
                        row.getLong("RootWorldSeed"), new CandidateBytes(row.getByteArray("TargetSettingsFingerprint")));
                decoded.add(new Entry(new CandidateBytes(token), universeId, null, profileVersion, fields));
            } else {
                var profile = LedgerNbt.enumValue(GeneratorProfile.class, row, "Profile");
                decoded.add(new Entry(new CandidateBytes(token), universeId, profile, profileVersion));
            }
        }
        return new UniverseAllocationLedger(schema, decoded);
    }

    @Override public boolean equals(Object other) {
        return other instanceof UniverseAllocationLedger ledger && schemaVersion == ledger.schemaVersion && entries.equals(ledger.entries);
    }

    @Override public int hashCode() { return Objects.hash(schemaVersion, entries); }

    @Override public String toString() { return "UniverseAllocationLedger[" + entries.size() + " entries]"; }
}
