package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.corridor.DoorKey.DoorWallSide;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateDerivationTest {
    @TempDir Path root;
    static final byte[] SECRET = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test void independentVectorsBindEveryDomainAndBothWallSides() throws Exception {
        var state = CandidateEntropyStateTest.fixedState(root, SECRET);
        // Python 標準函式庫 hmac/struct 獨立預算，固定 secret、UUID、station=-9、schema=3。
        String[][] vectors = {
            {"3a1083931111b9d146f5537e65f224bd8d6ec043e13c5897d2c120ee1e884b55", "8cbd8b377962e3eaded5d74daf0677b9586ed8c8fe88d5da6c658461eb8f5e6e"},
            {"bee09257a205c6524b16d93bffed2dd1d8c3c9304b9d0f66ae2ac113584e0e3b", "ebcff78be2eadb1f910eefe5acb078c1e926087133a41133ebf55fe677cecd8d"},
            {"3c20ee53c78a8d2d638aa574709e0614c0436781db7d5aa03a8ea46c0dbfa8a6", "4c02a178e7413c39627dd812b1107284e125b08c7743510ee5ff03e92989a315"},
            {"b3be795823fba6d02b0c9a1f3aafc36366ed8fa25ed15905da872f0caad794e2", "aa7c4a9cee82909ad69a300ef941ec8da6d82fd2a5b343506c58bc4c1b4ce9f6"},
            {"e5ce44e6469820ea8bcb1efd3660ff8b73d1344ce8f8380744b30c1047d42ec0", "feaf5774f99b2ae254b94d4c56692ceb0e81dbbda456961a07693ce23670e2f4"}
        };
        var domains = new DerivationDomain[] {DerivationDomain.CANDIDATE_ID, DerivationDomain.BUCKET,
            DerivationDomain.EXISTING_INDEX, DerivationDomain.ALLOCATION_TOKEN, DerivationDomain.GENERATION_SEED_MATERIAL};
        var sides = new DoorWallSide[] {DoorWallSide.NEGATIVE_LATERAL, DoorWallSide.POSITIVE_LATERAL};
        for (int domain = 0; domain < domains.length; domain++) {
            for (int side = 0; side < sides.length; side++) {
                assertArrayEquals(HexFormat.of().parseHex(vectors[domain][side]),
                    state.derive(domains[domain], key(sides[side]), 3).bytes());
            }
        }
    }

    @Test void canonicalEncodingUsesExplicitBigEndianLengthsSignedLongAndSideByte() {
        assertEquals("0000001e7175616e74756d6368616d6265723a63616e6469646174652d69643a7631"
                + "00112233445566778899aabbccddeefffffffffffffffff70000000003",
            HexFormat.of().formatHex(CandidateDerivation.canonicalBytes(
                DerivationDomain.CANDIDATE_ID, key(DoorWallSide.NEGATIVE_LATERAL), 3)));
    }

    @Test void bucketAndIndexUseWholeUnsignedIndependentDigests() throws Exception {
        var derivation = new CandidateDerivation(CandidateEntropyStateTest.fixedState(root, SECRET));
        assertEquals(79, derivation.bucket(key(DoorWallSide.NEGATIVE_LATERAL), 3));
        assertEquals(97, derivation.bucket(key(DoorWallSide.POSITIVE_LATERAL), 3));
        assertEquals(3, derivation.existingIndex(key(DoorWallSide.NEGATIVE_LATERAL), 3, 17));
        assertEquals(5, derivation.existingIndex(key(DoorWallSide.POSITIVE_LATERAL), 3, 17));
        assertEquals(0, derivation.existingIndex(key(DoorWallSide.POSITIVE_LATERAL), 3, 1));
        assertThrows(IllegalArgumentException.class, () -> derivation.existingIndex(key(DoorWallSide.NEGATIVE_LATERAL), 3, 0));
        assertThrows(IllegalArgumentException.class, () -> derivation.existingIndex(key(DoorWallSide.NEGATIVE_LATERAL), 3, -1));
    }

    @Test void schemaStationAndSessionRemainPartOfIdentity() throws Exception {
        var state = CandidateEntropyStateTest.fixedState(root, SECRET);
        var key = key(DoorWallSide.NEGATIVE_LATERAL);
        var original = state.derive(DerivationDomain.CANDIDATE_ID, key, 3);
        assertNotEquals(original, state.derive(DerivationDomain.CANDIDATE_ID, key, 4));
        assertNotEquals(original, state.derive(DerivationDomain.CANDIDATE_ID, new DoorKey(SESSION, 9, key.wallSide()), 3));
        assertNotEquals(original, state.derive(DerivationDomain.CANDIDATE_ID, new DoorKey(new UUID(0, 0), -9, key.wallSide()), 3));
        assertNotEquals(state.derive(DerivationDomain.CANDIDATE_ID, new DoorKey(SESSION, Long.MIN_VALUE, key.wallSide()), 3),
            state.derive(DerivationDomain.CANDIDATE_ID, new DoorKey(SESSION, Long.MAX_VALUE, key.wallSide()), 3));
    }

    @Test void byteValueCopiesBothDirectionsAndMatchesDigestEqualitySemantics() {
        byte[] input = SECRET.clone();
        var value = new CandidateBytes(input);
        input[0] ^= 1;
        byte[] returned = value.bytes();
        returned[31] ^= 1;
        assertArrayEquals(SECRET, value.bytes());
        assertEquals(value, new CandidateBytes(SECRET));
        assertEquals(value.hashCode(), new CandidateBytes(SECRET).hashCode());
        for (int i = 0; i < 32; i++) {
            byte[] changed = SECRET.clone(); changed[i] ^= 1;
            assertEquals(MessageDigest.isEqual(SECRET, changed), value.equals(new CandidateBytes(changed)));
        }
        assertNotEquals(null, value);
        assertNotEquals("candidate", value);
        assertThrows(IllegalArgumentException.class, () -> new CandidateBytes(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> new CandidateBytes(new byte[33]));
        assertThrows(NullPointerException.class, () -> new CandidateBytes(null));
        assertFalse(value.toString().contains(HexFormat.of().formatHex(SECRET)));
    }

    private static DoorKey key(DoorWallSide side) { return new DoorKey(SESSION, -9, side); }
}
