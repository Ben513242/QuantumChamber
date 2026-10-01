package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HexFormat;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class CandidateEntropyStateTest {
    @TempDir Path root;

    @Test void firstCreationPersistsExactPayloadAndReloadDoesNotRewrite() throws Exception {
        var first = CandidateEntropyState.loadOrCreate(root, false);
        Path file = target(root);
        byte[] before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        var nbt = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        assertTrue(nbt.contains("SchemaVersion", NbtElement.INT_TYPE));
        assertEquals(1, nbt.getInt("SchemaVersion"));
        assertTrue(nbt.contains("CandidateEntropy", NbtElement.BYTE_ARRAY_TYPE));
        assertEquals(32, nbt.getByteArray("CandidateEntropy").length);
        assertEquals(first.entropyFingerprint(), CandidateEntropyState.loadOrCreate(root, true).entropyFingerprint());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
        try (var files = Files.list(file.getParent())) { assertEquals(1, files.count()); }
    }

    @Test void fixedPayloadReadbackHasIndependentFingerprint() throws Exception {
        var state = fixedState(root, CandidateDerivationTest.SECRET);
        assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd",
            HexFormat.of().formatHex(state.entropyFingerprint().bytes()));
        assertFalse(state.toString().contains(HexFormat.of().formatHex(CandidateDerivationTest.SECRET)));
    }

    @Test void missingEntropyWithEvidenceFailsCheckedWithoutCreatingDirectories() {
        assertThrows(CandidateEntropyState.UnhealthyEntropyException.class,
            () -> CandidateEntropyState.loadOrCreate(root, true));
        assertFalse(Files.exists(root.resolve("data")));
    }

    @Test void truncatedFileFailsClosedWithoutOverwritingBytes() throws Exception {
        Files.createDirectories(target(root).getParent());
        byte[] truncated = {31, -117, 8, 0};
        Files.write(target(root), truncated);
        assertThrows(CandidateEntropyState.UnhealthyEntropyException.class, () -> CandidateEntropyState.loadOrCreate(root, false));
        assertArrayEquals(truncated, Files.readAllBytes(target(root)));
    }

    @Test void wrongTypesSchemasAndLengthsAreNeverRegenerated() throws Exception {
        var wrongSchemaType = valid(); wrongSchemaType.putString("SchemaVersion", "1");
        var wrongPayloadType = valid(); wrongPayloadType.putString("CandidateEntropy", "do-not-expose-this-secret");
        var wrongSchema = valid(); wrongSchema.putInt("SchemaVersion", 2);
        var shortPayload = valid(); shortPayload.putByteArray("CandidateEntropy", new byte[31]);
        var longPayload = valid(); longPayload.putByteArray("CandidateEntropy", new byte[33]);
        var missing = new NbtCompound();
        for (var invalid : new NbtCompound[] {wrongSchemaType, wrongPayloadType, wrongSchema, shortPayload, longPayload, missing}) {
            Files.createDirectories(target(root).getParent());
            NbtIo.writeCompressed(invalid, target(root));
            byte[] before = Files.readAllBytes(target(root));
            var exception = assertThrows(CandidateEntropyState.UnhealthyEntropyException.class,
                () -> CandidateEntropyState.loadOrCreate(root, false));
            assertFalse(exception.toString().contains("do-not-expose-this-secret"));
            assertNull(exception.getCause());
            assertArrayEquals(before, Files.readAllBytes(target(root)));
        }
    }

    /** H1：root 必須恰為 {SchemaVersion, CandidateEntropy}；多出的 key 一律 fail closed，且不換發 secret。 */
    @Test void extraRootKeysFailClosedWithoutRegeneration() throws Exception {
        var extraString = valid(); extraString.putString("Comment", "do-not-expose-this-secret");
        var extraBytes = valid(); extraBytes.putByteArray("CandidateEntropyBackup", new byte[32]);
        var cases = new ArrayList<Executable>();
        for (boolean evidence : new boolean[] {false, true}) for (var invalid : new NbtCompound[] {extraString, extraBytes}) cases.add(() -> {
            Files.createDirectories(target(root).getParent());
            NbtIo.writeCompressed(invalid, target(root));
            byte[] before = Files.readAllBytes(target(root));
            var exception = assertThrows(CandidateEntropyState.UnhealthyEntropyException.class,
                () -> CandidateEntropyState.loadOrCreate(root, evidence));
            // 固定訊息：不帶位置、key 名稱或內容。
            assertEquals("候選 entropy 欄位缺失或含未知欄位", exception.getMessage());
            assertFalse(exception.toString().contains("do-not-expose-this-secret"));
            assertNull(exception.getCause());
            assertArrayEquals(before, Files.readAllBytes(target(root)));
            try (var files = Files.list(target(root).getParent())) { assertEquals(1, files.count()); }
        });
        // 逐一判定（依序執行），RED 時列出每一個仍被接受的 fixture。
        assertAll(cases);
    }

    @Test void directoryTargetAndBlockedDataParentFailChecked() throws Exception {
        Files.createDirectories(target(root));
        assertThrows(CandidateEntropyState.UnhealthyEntropyException.class, () -> CandidateEntropyState.loadOrCreate(root, false));
        Path other = root.resolve("other");
        Files.createDirectories(other);
        Files.write(other.resolve("data"), new byte[] {1});
        assertThrows(CandidateEntropyState.UnhealthyEntropyException.class, () -> CandidateEntropyState.loadOrCreate(other, false));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(other.resolve("data")));
    }

    static CandidateEntropyState fixedState(Path root, byte[] secret) throws Exception {
        Files.createDirectories(target(root).getParent());
        var nbt = valid(); nbt.putByteArray("CandidateEntropy", secret.clone());
        NbtIo.writeCompressed(nbt, target(root));
        return CandidateEntropyState.loadOrCreate(root, true);
    }

    private static NbtCompound valid() {
        var nbt = new NbtCompound();
        nbt.putInt("SchemaVersion", 1);
        nbt.putByteArray("CandidateEntropy", new byte[32]);
        return nbt;
    }

    private static Path target(Path root) { return root.resolve("data/quantumchamber_candidate_entropy.dat"); }
}
