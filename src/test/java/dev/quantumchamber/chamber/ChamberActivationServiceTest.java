package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.universe.DimensionRole;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberActivationServiceTest {
    private static final Identifier OVERWORLD = Identifier.of("minecraft", "overworld");
    private static final ChamberFrame FIRST_FRAME = new ChamberFrame(new BlockPos(40, 80, -20), Direction.NORTH);

    @Test
    void originLookupIsReadOnlyAndRequiresEveryIdentityCoordinate() {
        int[] mutations = {0};
        ChamberRegistry registry = new ChamberRegistry(() -> mutations[0]++);
        assertTrue(registry.findOrigin(OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME).isEmpty());
        assertEquals(0, mutations[0]);
        UUID uuid = registry.registerOrigin(OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME).chamberUuid();
        assertEquals(uuid, registry.findOrigin(OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME)
                .orElseThrow().chamberUuid());
        assertTrue(registry.findOrigin(Identifier.of("other", "world"), DimensionRole.OVERWORLD, FIRST_FRAME).isEmpty());
        assertTrue(registry.findOrigin(OVERWORLD, DimensionRole.NETHER, FIRST_FRAME).isEmpty());
        assertTrue(registry.findOrigin(OVERWORLD, DimensionRole.OVERWORLD,
                new ChamberFrame(new BlockPos(41, 80, -20), Direction.NORTH)).isEmpty());
        assertTrue(registry.findOrigin(OVERWORLD, DimensionRole.OVERWORLD,
                new ChamberFrame(new BlockPos(40, 80, -20), Direction.SOUTH)).isEmpty());
        assertEquals(1, registry.records().size());
        assertEquals(1, mutations[0]);
    }

    @Test
    void rejectsUnreadableRegistryWithoutMutationOrUuidWrite() {
        InMemoryRegistry port = new InMemoryRegistry(Optional.of("unsupported schema"));
        RecordingUuidSink uuidSink = new RecordingUuidSink();

        ChamberActivationService.OriginResolution result = ChamberActivationService.registerOrResolveOrigin(
                port, OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME, uuidSink);

        assertFalse(result.usable());
        assertTrue(result.failureReasons().contains(ArmAttemptResult.Failure.REGISTRY_UNAVAILABLE));
        assertEquals(0, port.recordCount());
        assertFalse(uuidSink.wasCalled());
    }

    @Test
    void writesSameStableUuidForCreatedThenExistingOrigin() {
        InMemoryRegistry port = new InMemoryRegistry(Optional.empty());
        RecordingUuidSink createdSink = new RecordingUuidSink();
        RecordingUuidSink existingSink = new RecordingUuidSink();

        ChamberActivationService.OriginResolution created = ChamberActivationService.registerOrResolveOrigin(
                port, OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME, createdSink);
        ChamberActivationService.OriginResolution existing = ChamberActivationService.registerOrResolveOrigin(
                port, OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME, existingSink);

        assertTrue(created.usable());
        assertTrue(existing.usable());
        assertEquals(created.record().orElseThrow().chamberUuid(), createdSink.uuid());
        assertEquals(createdSink.uuid(), existing.record().orElseThrow().chamberUuid());
        assertEquals(createdSink.uuid(), existingSink.uuid());
        assertEquals(1, port.recordCount());
    }

    @Test
    void rejectsOverlappingOriginWithoutWritingUuid() {
        InMemoryRegistry port = new InMemoryRegistry(Optional.empty());
        port.registry.registerOrigin(OVERWORLD, DimensionRole.OVERWORLD, FIRST_FRAME);
        RecordingUuidSink uuidSink = new RecordingUuidSink();
        ChamberFrame overlappingFrame = new ChamberFrame(new BlockPos(41, 80, -20), Direction.NORTH);

        ChamberActivationService.OriginResolution result = ChamberActivationService.registerOrResolveOrigin(
                port, OVERWORLD, DimensionRole.OVERWORLD, overlappingFrame, uuidSink);

        assertFalse(result.usable());
        assertTrue(result.failureReasons().contains(ArmAttemptResult.Failure.ORIGIN_OVERLAP));
        assertEquals(1, port.recordCount());
        assertFalse(uuidSink.wasCalled());
    }

    private static final class InMemoryRegistry implements ChamberActivationService.ChamberRegistryPort {
        private final ChamberRegistry registry = new ChamberRegistry();
        private final Optional<String> loadError;

        private InMemoryRegistry(Optional<String> loadError) {
            this.loadError = loadError;
        }

        @Override
        public Optional<String> loadError() {
            return loadError;
        }

        @Override
        public ChamberRegistrationResult registerOrigin(Identifier worldKey, DimensionRole role, ChamberFrame frame) {
            return registry.registerOrigin(worldKey, role, frame);
        }

        @Override
        public Optional<ChamberRecord> record(UUID chamberUuid) {
            return Optional.ofNullable(registry.records().get(chamberUuid));
        }

        private int recordCount() {
            return registry.records().size();
        }
    }

    private static final class RecordingUuidSink implements ChamberActivationService.ChamberUuidSink {
        private UUID uuid;

        @Override
        public void setChamberUuid(UUID chamberUuid) {
            uuid = chamberUuid;
        }

        private UUID uuid() {
            return uuid;
        }

        private boolean wasCalled() {
            return uuid != null;
        }
    }
}
