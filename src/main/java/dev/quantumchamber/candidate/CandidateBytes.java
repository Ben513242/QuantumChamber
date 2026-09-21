package dev.quantumchamber.candidate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** 固定 32 bytes 的不可變候選值；比對委由 MessageDigest.isEqual。 */
public final class CandidateBytes {
    private final byte[] value;

    public CandidateBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != 32) throw new IllegalArgumentException("候選值必須恰為 32 bytes");
        this.value = value.clone();
    }

    public byte[] bytes() { return value.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof CandidateBytes bytes && MessageDigest.isEqual(value, bytes.value);
    }

    // 僅維持集合的 equals/hashCode 契約，不參與候選亂數衍生。
    @Override public int hashCode() { return Arrays.hashCode(value); }

    @Override public String toString() { return "CandidateBytes[32 bytes]"; }
}
