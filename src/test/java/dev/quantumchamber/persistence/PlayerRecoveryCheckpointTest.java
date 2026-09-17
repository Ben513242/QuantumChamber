package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class PlayerRecoveryCheckpointTest {
    @Test void eachPolicyRoundtripsWithoutAccumulatedHistory() {
        for (var policy : PlayerRecoveryCheckpoint.AppliedPolicy.values()) {
            var checkpoint = new PlayerRecoveryCheckpoint(UUID.randomUUID(), policy);
            assertEquals(checkpoint, PlayerRecoveryCheckpoint.fromNbt(checkpoint.toNbt()));
            assertEquals(3, checkpoint.toNbt().getKeys().size());
        }
    }
    @Test void strictUuidSchemaPolicyAndTypesRejectUnknownMarkers() {
        var good = new PlayerRecoveryCheckpoint(UUID.randomUUID(), PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY).toNbt();
        assertThrows(IllegalArgumentException.class, () -> PlayerRecoveryCheckpoint.fromNbt(NbtInt.of(1)));
        for (String field : new String[] {"SchemaVersion", "SessionUuid", "AppliedPolicy"}) {
            var missing = good.copy(); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> PlayerRecoveryCheckpoint.fromNbt(missing));
            var wrong = good.copy(); wrong.putFloat(field, 1.0f);
            assertThrows(IllegalArgumentException.class, () -> PlayerRecoveryCheckpoint.fromNbt(wrong));
        }
        var unknown = good.copy(); unknown.putInt("SchemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> PlayerRecoveryCheckpoint.fromNbt(unknown));
        var invalid = good.copy(); invalid.putString("AppliedPolicy", "RETRY");
        assertThrows(IllegalArgumentException.class, () -> PlayerRecoveryCheckpoint.fromNbt(invalid));
        assertThrows(NullPointerException.class, () -> new PlayerRecoveryCheckpoint(null, PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT));
    }
}
