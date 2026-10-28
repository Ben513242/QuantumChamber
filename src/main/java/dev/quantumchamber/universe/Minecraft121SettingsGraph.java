package dev.quantumchamber.universe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.util.Identifier;

/** C1a 的 owned typed 資料圖；不取得 live source、不產生或發布快照。 */
final class Minecraft121SettingsGraph {
    enum Kind { OPTIONS, REGISTRY, TAG, TEMPLATE, RESOLVED }
    record Key(Kind kind, String registry, String id) {
        Key {
            Objects.requireNonNull(kind, "kind");
            identifier(registry); identifier(id);
        }
    }
    record Occurrence(long ordinal, Key target, String aliasScope, String use) {
        Occurrence {
            Objects.requireNonNull(target, "target");
            label(aliasScope); label(use);
        }
    }
    private record Edge(Key from, String path, Occurrence occurrence) {}
    record Limits(long nodes, long edges, long states, long resources, long raw,
            long totalRaw, long canonical, int depth) {
        Limits {
            if (nodes <= 0 || nodes > 32768 || edges <= 0 || edges > 1000000
                    || states <= 0 || states > 131072 || resources <= 0 || resources > 2048
                    || raw <= 0 || raw > 8388608 || totalRaw <= 0 || totalRaw > 134217728
                    || canonical <= 0 || canonical > 268435456 || depth <= 0 || depth > 128)
                throw new IllegalArgumentException("GRAPH_LIMITS_INVALID");
        }
        static Limits standard() { return new Limits(32768, 1000000, 131072, 2048,
                8388608, 134217728, 268435456, 128); }
    }
    static final class Failure extends IllegalStateException {
        Failure(String code) { super(code); }
    }
    private static final Comparator<String> TEXT_ORDER = Minecraft121SettingsGraph::compareUtf8;
    private static final String DOMAIN = "quantumchamber:m6:settings-graph:v1";
    private static final Comparator<Key> KEY_ORDER = Comparator.comparing((Key k) -> k.kind().name())
            .thenComparing(Key::registry).thenComparing(Key::id);
    private final Limits limits;
    private final Map<Key, byte[]> nodes = new TreeMap<>(KEY_ORDER);
    private final Set<Key> requested = new HashSet<>();
    private final Set<Key> resources = new HashSet<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Set<Occurrence> states = new HashSet<>();
    private final Set<Occurrence> completed = new HashSet<>();
    private long totalRaw;
    private long retainedBytes;
    private long nativeStateCharge;
    private long canonicalBytes = 4 + DOMAIN.length() + 8;
    private boolean failed;
    private byte[] finished;

    Minecraft121SettingsGraph() { this(Limits.standard()); }
    Minecraft121SettingsGraph(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    void payload(Key key, NbtElement value) { checked(() -> { payloadUnchecked(key,value); return null; }); }
    Occurrence reference(Key from, String path, Key target, String aliasScope, String use) {
        return checked(() -> referenceUnchecked(from,path,target,aliasScope,use));
    }
    void expanded(Occurrence occurrence) { checked(() -> { expandedUnchecked(occurrence); return null; }); }
    void resource(Key key) { checked(() -> { resourceUnchecked(key); return null; }); }
    void decoded(long bytes) { checked(() -> { decodedUnchecked(bytes); return null; }); }
    /** 原生狀態每次使用收費；與輸出共用上限，但不改canonical輸出長度／格式。 */
    void nativeState(long bytes) {
        checked(() -> {
            mutable();require(bytes >= 0,"BUDGET_NATIVE_STATE");
            require(bytes <= limits.canonical() - nativeStateCharge - canonicalBytes,"BUDGET_CANONICAL");
            decodedUnchecked(bytes);nativeStateCharge += bytes;return null;
        });
    }
    byte[] finish() { return checked(this::finishUnchecked); }
    void requireOpen() { checked(() -> { mutable(); return null; }); }
    void invalidate() { failed = true; }

    /** 任一驗證／編碼失敗均使本次圖失效，原例外照原樣交回。 */
    private <T> T checked(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException | Error error) { failed = true; throw error; }
    }

    private void payloadUnchecked(Key key, NbtElement value) {
        mutable(); reserve(key);
        long available = nodes.containsKey(key) ? limits.canonical() - nativeStateCharge : limits.canonical() - nativeStateCharge - canonicalBytes;
        var output = new Encoder(Math.min(available, limits.totalRaw()));
        output.nbt(Objects.requireNonNull(value), 0);
        byte[] encoded = output.bytes();
        byte[] previous = nodes.get(key);
        require(previous == null || Arrays.equals(previous, encoded), "DATA_NODE_CHANGED");
        if (previous == null) {
            require(encoded.length <= limits.totalRaw() - retainedBytes, "BUDGET_OWNED_BYTES");
            canonicalBytes += encoded.length;
            retainedBytes += encoded.length;
            nodes.put(key, encoded);
        }
    }

    /** 每條邊保留；資料節點共用，但 alias/use 的完成狀態獨立。 */
    private Occurrence referenceUnchecked(Key from, String path, Key target, String aliasScope, String use) {
        mutable();
        long edgeBytes = keyBytes(from) + textBytes(path) + keyBytes(target)
                + textBytes(aliasScope) + textBytes(use);
        require(edgeBytes <= limits.canonical() - nativeStateCharge - canonicalBytes, "BUDGET_CANONICAL");
        label(path);
        var occurrence = new Occurrence(edges.size(), target, aliasScope, use);
        require(edges.size() < limits.edges(), "BUDGET_EDGES");
        require(states.size() < limits.states(), "BUDGET_STATES");
        reserve(from); reserve(target);
        require(edgeBytes <= limits.canonical() - nativeStateCharge - canonicalBytes, "BUDGET_CANONICAL");
        canonicalBytes += edgeBytes;
        edges.add(new Edge(from, path, occurrence));
        states.add(occurrence);
        return occurrence;
    }

    private void expandedUnchecked(Occurrence occurrence) {
        mutable();
        require(states.contains(occurrence) && nodes.containsKey(occurrence.target()), "OCCURRENCE_UNRESOLVED");
        completed.add(occurrence);
    }

    private void resourceUnchecked(Key key) {
        mutable(); Objects.requireNonNull(key);
        require(resources.contains(key) || resources.size() < limits.resources(), "BUDGET_RESOURCES");
        resources.add(key);
    }

    /** reader 每次實際解碼均計費；重讀同一資源也不能豁免。 */
    private void decodedUnchecked(long bytes) {
        mutable();
        require(bytes >= 0 && bytes <= limits.raw(), "BUDGET_RAW");
        require(bytes <= limits.totalRaw() - totalRaw, "BUDGET_TOTAL_RAW");
        totalRaw += bytes;
    }

    /** 僅回傳本圖 canonical bytes；不代表受審來源或完整三 role。 */
    private byte[] finishUnchecked() {
        require(!failed, "GRAPH_FAILED");
        if (finished != null) return finished.clone();
        require(!nodes.isEmpty() && nodes.keySet().containsAll(requested)
                && completed.containsAll(states), "GRAPH_UNCLOSED");
        var output = new Encoder(limits.canonical() - nativeStateCharge);
        output.text(DOMAIN);
        output.integer(nodes.size());
        for (var entry : nodes.entrySet()) {
            output.key(entry.getKey()); output.raw(entry.getValue());
        }
        var sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparing(Edge::from, KEY_ORDER).thenComparing(Edge::path, TEXT_ORDER)
                .thenComparing(e -> e.occurrence().target(), KEY_ORDER)
                .thenComparing(e -> e.occurrence().aliasScope(), TEXT_ORDER)
                .thenComparing(e -> e.occurrence().use(), TEXT_ORDER));
        output.integer(sorted.size());
        for (var edge : sorted) {
            output.key(edge.from()); output.text(edge.path()); output.key(edge.occurrence().target());
            output.text(edge.occurrence().aliasScope()); output.text(edge.occurrence().use());
        }
        finished = output.bytes();
        return finished.clone();
    }

    private void reserve(Key key) {
        Objects.requireNonNull(key);
        require(requested.contains(key) || requested.size() < limits.nodes(), "BUDGET_NODES");
        if (requested.add(key)) {
            long bytes = keyBytes(key);
            require(bytes <= limits.canonical() - nativeStateCharge - canonicalBytes, "BUDGET_CANONICAL");
            canonicalBytes += bytes;
        }
    }
    private long keyBytes(Key key) {
        Objects.requireNonNull(key);
        return textBytes(key.kind().name()) + textBytes(key.registry()) + textBytes(key.id());
    }
    /** 不配置UTF-8陣列；先拒長度／surrogate，再讓編碼與排序使用。 */
    private long textBytes(String value) {
        Objects.requireNonNull(value);
        require(4L + value.length() <= limits.canonical(), "BUDGET_CANONICAL");
        long count = 4;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("GRAPH_TEXT_INVALID");
                count += 4;
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("GRAPH_TEXT_INVALID");
            else count += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
            require(count <= limits.canonical(), "BUDGET_CANONICAL");
        }
        return count;
    }
    /** 有效Unicode scalar的次序等同無符號UTF-8，無比較時的byte[]配置。 */
    private static int compareUtf8(String a, String b) {
        int i=0, j=0;
        while (i<a.length() && j<b.length()) {
            int left=a.codePointAt(i), right=b.codePointAt(j);
            if (left != right) return Integer.compare(left,right);
            i += Character.charCount(left); j += Character.charCount(right);
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }
    private void mutable() {
        require(!failed, "GRAPH_FAILED");
        require(finished == null, "GRAPH_FINISHED");
    }
    private void require(boolean condition, String code) {
        if (!condition) { failed = true; throw new Failure(code); }
    }
    private static void identifier(String value) {
        Identifier parsed = value == null ? null : Identifier.tryParse(value);
        if (parsed == null || !value.contains(":") || !parsed.toString().equals(value))
            throw new IllegalArgumentException("GRAPH_IDENTIFIER_INVALID");
    }
    private static void label(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("GRAPH_LABEL_INVALID");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("GRAPH_TEXT_INVALID");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("GRAPH_TEXT_INVALID");
        }
    }

    /** 固定 big-endian、UTF-8 無符號 key 排序；列表與數值原型別保留。 */
    private final class Encoder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buffer);
        private final long cap;
        Encoder(long cap) { this.cap = cap; }
        private void room(long count) { require(count >= 0 && count <= cap - buffer.size(), "BUDGET_CANONICAL"); }
        private void io(IoAction action) {
            try { action.write(); } catch (IOException error) { failed = true; throw new UncheckedIOException(error); }
        }
        void integer(int value) { room(4); io(() -> out.writeInt(value)); }
        void raw(byte[] value) { room(value.length); io(() -> out.write(value)); }
        void text(String value) {
            room(textBytes(value));
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            integer(bytes.length); raw(bytes);
        }
        void key(Key value) { text(value.kind().name()); text(value.registry()); text(value.id()); }
        byte[] bytes() { return buffer.toByteArray(); }
        void nbt(NbtElement value, int depth) {
            require(depth <= limits.depth(), "BUDGET_DEPTH");
            room(1); io(() -> out.writeByte(value.getType()));
            switch (value.getType()) {
                case NbtElement.BYTE_TYPE -> { room(1); io(() -> out.writeByte(((NbtByte)value).byteValue())); }
                case NbtElement.SHORT_TYPE -> { room(2); io(() -> out.writeShort(((NbtShort)value).shortValue())); }
                case NbtElement.INT_TYPE -> integer(((NbtInt)value).intValue());
                case NbtElement.LONG_TYPE -> { room(8); io(() -> out.writeLong(((NbtLong)value).longValue())); }
                case NbtElement.FLOAT_TYPE -> integer(Float.floatToRawIntBits(((NbtFloat)value).floatValue()));
                case NbtElement.DOUBLE_TYPE -> { room(8); io(() -> out.writeLong(Double.doubleToRawLongBits(((NbtDouble)value).doubleValue()))); }
                case NbtElement.STRING_TYPE -> text(value.asString());
                case NbtElement.BYTE_ARRAY_TYPE -> {
                    byte[] array = ((NbtByteArray)value).getByteArray(); room(4L + array.length);
                    integer(array.length); raw(array);
                }
                case NbtElement.INT_ARRAY_TYPE -> {
                    int[] array = ((NbtIntArray)value).getIntArray(); room(4L + 4L * array.length);
                    integer(array.length); for (int item : array) integer(item);
                }
                case NbtElement.LONG_ARRAY_TYPE -> {
                    long[] array = ((NbtLongArray)value).getLongArray(); room(4L + 8L * array.length);
                    integer(array.length); for (long item : array) io(() -> out.writeLong(item));
                }
                case NbtElement.LIST_TYPE -> {
                    var list = (NbtList)value; room(4L + list.size()); integer(list.size());
                    for (var item : list) nbt(item, depth + 1);
                }
                case NbtElement.COMPOUND_TYPE -> {
                    var compound = (NbtCompound)value; room(4L + 5L * compound.getSize());
                    long keyBytes = 4;
                    for (String key : compound.getKeys()) { keyBytes += textBytes(key) + 1; room(keyBytes); }
                    var keys = new ArrayList<>(compound.getKeys()); keys.sort(TEXT_ORDER); integer(keys.size());
                    for (String key : keys) { text(key); nbt(compound.get(key), depth + 1); }
                }
                default -> require(false, "UNSUPPORTED_NBT_TYPE");
            }
        }
    }
    @FunctionalInterface private interface IoAction { void write() throws IOException; }
}
