package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 無 I/O 的候選衍生；所有用途使用各自的 HMAC domain。 */
public final class CandidateDerivation {
    private final CandidateEntropyState entropy;

    CandidateDerivation(CandidateEntropyState entropy) {
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    CandidateBytes derive(DerivationDomain domain, DoorKey key, int candidateSchemaVersion) {
        return entropy.derive(domain, key, candidateSchemaVersion);
    }

    int bucket(DoorKey key, int candidateSchemaVersion) {
        return unsignedRemainder(derive(DerivationDomain.BUCKET, key, candidateSchemaVersion), 100);
    }

    int existingIndex(DoorKey key, int candidateSchemaVersion, int poolSize) {
        if (poolSize <= 0) throw new IllegalArgumentException("既有候選池大小必須大於零");
        return unsignedRemainder(derive(DerivationDomain.EXISTING_INDEX, key, candidateSchemaVersion), poolSize);
    }

    // 明確使用完整 256-bit unsigned digest 取餘數；不宣稱 rejection sampling 的無偏抽樣。
    private static int unsignedRemainder(CandidateBytes digest, int divisor) {
        return new BigInteger(1, digest.bytes()).mod(BigInteger.valueOf(divisor)).intValueExact();
    }

    static CandidateBytes hmac(byte[] secret, DerivationDomain domain, DoorKey key, int candidateSchemaVersion) {
        byte[] message = canonicalBytes(domain, key, candidateSchemaVersion);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return new CandidateBytes(mac.doFinal(message));
        } catch (GeneralSecurityException exception) {
            // 不傳遞可能由 provider 夾帶敏感資料的例外內容。
            throw new IllegalStateException("候選 HMAC 衍生不可用");
        }
    }

    static byte[] canonicalBytes(DerivationDomain domain, DoorKey key, int candidateSchemaVersion) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(key, "key");
        byte[] tag = domain.tag().getBytes(StandardCharsets.UTF_8);
        byte side = switch (key.wallSide()) {
            case NEGATIVE_LATERAL -> 0;
            case POSITIVE_LATERAL -> 1;
        };
        return ByteBuffer.allocate(Integer.BYTES + tag.length + 3 * Long.BYTES + 1 + Integer.BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(tag.length).put(tag)
            .putLong(key.sessionUuid().getMostSignificantBits())
            .putLong(key.sessionUuid().getLeastSignificantBits())
            .putLong(key.logicalStationIndex()).put(side).putInt(candidateSchemaVersion).array();
    }
}
