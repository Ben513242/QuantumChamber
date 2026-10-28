package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.junit.jupiter.api.Test;

class GenerationSettingsSnapshotTest {
    private static NbtCompound valid() {
        var parameters = new NbtCompound();
        parameters.putString("OverworldPreset", "normal");
        parameters.putByte("GenerateStructures", (byte) 1);
        var nbt = new NbtCompound();
        nbt.putInt("SnapshotVersion", 1);
        nbt.putString("ProviderId", "quantumchamber:vanilla_normal_v1");
        nbt.putInt("ProviderVersion", 1);
        nbt.put("Parameters", parameters);
        nbt.putByteArray("OutputFingerprint", new byte[32]);
        return nbt;
    }

    @Test void snapshotRejectsUnknownParameter() {
        var nbt = valid();
        nbt.getCompound("Parameters").putInt("Unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRejectsOversize512() {
        var nbt = valid();
        nbt.putString("ProviderId", "quantumchamber:" + "a".repeat(500));
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRejectsLargePayloadBeforePersistence() {
        var nbt = valid();
        nbt.putString("ProviderId", "quantumchamber:" + "a".repeat(16_000));
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRejectsMissingGenerateStructures() {
        var nbt = valid();
        nbt.getCompound("Parameters").remove("GenerateStructures");
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRejectsWrongGenerateStructuresType() {
        var nbt = valid();
        nbt.getCompound("Parameters").putInt("GenerateStructures", 1);
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRejectsNonBooleanGenerateStructuresByte() {
        var nbt = valid();
        nbt.getCompound("Parameters").putByte("GenerateStructures", (byte) 2);
        assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(nbt));
    }

    @Test void snapshotRoundtripAndDefensiveCopies() {
        var nbt = valid();
        var snapshot = GenerationSettingsSnapshot.fromNbt(nbt);
        nbt.getCompound("Parameters").putString("OverworldPreset", "amplified");
        nbt.putByteArray("OutputFingerprint", new byte[3]);
        var first = snapshot.toNbt();
        assertEquals("normal", first.getCompound("Parameters").getString("OverworldPreset"));
        assertEquals(32, first.getByteArray("OutputFingerprint").length);
        first.getCompound("Parameters").putString("OverworldPreset", "amplified");
        first.putByteArray("OutputFingerprint", new byte[1]);
        assertEquals("normal", snapshot.toNbt().getCompound("Parameters").getString("OverworldPreset"));
        assertEquals(32, snapshot.toNbt().getByteArray("OutputFingerprint").length);
        var parameters = snapshot.parameters();
        parameters.putString("OverworldPreset", "amplified");
        var fingerprint = snapshot.outputFingerprint();
        fingerprint[0] = 42;
        assertEquals("normal", snapshot.parameters().getString("OverworldPreset"));
        assertEquals(0, snapshot.outputFingerprint()[0]);
        assertEquals(snapshot.toNbt(), GenerationSettingsSnapshot.fromNbt(snapshot.toNbt()).toNbt());
    }
}
