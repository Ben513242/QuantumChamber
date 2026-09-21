package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/** Save-scoped entropy 的 checked 儲存；任何不健康狀態都禁止自動換發 secret。 */
public final class CandidateEntropyState {
    private final byte[] secret;

    private CandidateEntropyState(byte[] secret) { this.secret = secret.clone(); }

    public static synchronized CandidateEntropyState loadOrCreate(Path saveRoot, boolean m4EvidenceExists)
            throws UnhealthyEntropyException {
        Path target = saveRoot.toAbsolutePath().normalize().resolve("data/quantumchamber_candidate_entropy.dat");
        try {
            try {
                return read(target);
            } catch (NoSuchFileException missing) {
                if (m4EvidenceExists) throw new UnhealthyEntropyException("已有 M4 證據，但候選 entropy 檔案遺失");
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            try {
                writeAtomic(target, generated);
                CandidateEntropyState persisted = read(target);
                if (!MessageDigest.isEqual(generated, persisted.secret)) {
                    throw new UnhealthyEntropyException("候選 entropy 寫入後讀回驗證失敗");
                }
                return persisted;
            } finally {
                Arrays.fill(generated, (byte) 0);
            }
        } catch (UnhealthyEntropyException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            // NBT 解析與檔案系統錯誤只回報固定訊息，不攜帶 payload 或原始 cause。
            throw new UnhealthyEntropyException("候選 entropy 儲存不健康，拒絕載入或建立");
        }
    }

    public CandidateBytes entropyFingerprint() {
        try {
            return new CandidateBytes(MessageDigest.getInstance("SHA-256").digest(secret));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("候選 entropy 指紋演算法不可用");
        }
    }

    CandidateBytes derive(DerivationDomain domain, DoorKey key, int candidateSchemaVersion) {
        return CandidateDerivation.hmac(secret, domain, key, candidateSchemaVersion);
    }

    private static CandidateEntropyState read(Path target) throws IOException {
        var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new UnhealthyEntropyException("候選 entropy 必須是一般檔案");
        // 正常 payload 遠小於此上限；損壞檔案不可要求無界限 NBT 配置。
        NbtCompound nbt = NbtIo.readCompressed(target, NbtSizeTracker.of(4096));
        if (!nbt.contains("SchemaVersion", NbtElement.INT_TYPE) || nbt.getInt("SchemaVersion") != 1
                || !nbt.contains("CandidateEntropy", NbtElement.BYTE_ARRAY_TYPE)) {
            throw new UnhealthyEntropyException("候選 entropy schema 或型別不正確");
        }
        byte[] payload = nbt.getByteArray("CandidateEntropy");
        if (payload.length != 32) throw new UnhealthyEntropyException("候選 entropy 長度不正確");
        return new CandidateEntropyState(payload);
    }

    private static void writeAtomic(Path target, byte[] secret) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            var nbt = new NbtCompound();
            nbt.putInt("SchemaVersion", 1);
            nbt.putByteArray("CandidateEntropy", secret);
            NbtIo.writeCompressed(nbt, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override public String toString() { return "CandidateEntropyState[redacted]"; }

    public static final class UnhealthyEntropyException extends IOException {
        private UnhealthyEntropyException(String message) { super(message); }
    }
}
