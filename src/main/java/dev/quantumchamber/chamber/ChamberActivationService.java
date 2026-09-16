package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.universe.DimensionRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

/** Server-side M1 prerequisite orchestration.  State transition to ARMED belongs to the caller. */
public final class ChamberActivationService {
    private final ChamberDetector detector;
    private final ChamberOccupantService occupants;
    private final ChamberActivationEvaluator evaluator;

    public ChamberActivationService() {
        this(new ChamberDetector(), new ChamberOccupantService(), new ChamberActivationEvaluator());
    }

    ChamberActivationService(
            ChamberDetector detector, ChamberOccupantService occupants, ChamberActivationEvaluator evaluator) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.occupants = Objects.requireNonNull(occupants, "occupants");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public ArmAttemptResult attemptArm(ServerWorld world, ChamberControllerBlockEntity controller) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(controller, "controller");

        Optional<DimensionRole> role = DimensionRole.fromVanillaKey(world.getRegistryKey());
        if (role.isEmpty()) {
            return rejected(ChamberState.INVALID, List.of(), ArmAttemptResult.Failure.UNSUPPORTED_DIMENSION);
        }

        ChamberFrame frame = new ChamberFrame(controller.getPos(), facing(controller));
        ChamberStructureResult structure = detector.validate(new WorldChamberBlockView(world), frame);
        if (!structure.valid()) {
            return rejected(ChamberState.INVALID, List.of(), ArmAttemptResult.Failure.INVALID_STRUCTURE);
        }

        ChamberRegistryState registryState = ChamberRegistryState.get(world.getServer());
        OriginResolution origin = registerOrResolveOrigin(
                new PersistentRegistryPort(registryState),
                world.getRegistryKey().getValue(),
                role.get(),
                frame,
                controller::setChamberUuid);
        if (!origin.usable()) {
            return new ArmAttemptResult(false, ChamberState.INVALID, List.of(), origin.failureReasons());
        }
        ChamberRecord record = origin.record().orElseThrow();
        List<ServerPlayerEntity> participants = occupants.findParticipants(world, frame);
        List<UUID> participantUuids = participants.stream().map(ServerPlayerEntity::getUuid).toList();
        List<ChamberActivationSnapshot.ParticipantEligibility> eligibility = participants.stream()
                .map(player -> new ChamberActivationSnapshot.ParticipantEligibility(
                        player.getUuid(), player.hasStatusEffect(ModEffects.QUANTUM_STATE)))
                .toList();
        ChamberActivationSnapshot snapshot = new ChamberActivationSnapshot(
                record.enabled(), structure.valid(), structure.sealed(), eligibility);
        ChamberState readiness = evaluator.evaluate(snapshot);
        if (readiness == ChamberState.READY) {
            return new ArmAttemptResult(true, readiness, participantUuids, Set.of());
        }
        return new ArmAttemptResult(false, readiness, participantUuids, failuresFor(snapshot));
    }

    private static Direction facing(ChamberControllerBlockEntity controller) {
        DirectionProperty property = ChamberControllerBlock.FACING;
        return controller.getCachedState().get(property);
    }

    static OriginResolution registerOrResolveOrigin(
            ChamberRegistryPort registry,
            Identifier worldKey,
            DimensionRole role,
            ChamberFrame frame,
            ChamberUuidSink chamberUuidSink) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(chamberUuidSink, "chamberUuidSink");
        if (registry.loadError().isPresent()) {
            return OriginResolution.rejected(ArmAttemptResult.Failure.REGISTRY_UNAVAILABLE);
        }

        ChamberRegistrationResult registration = registry.registerOrigin(worldKey, role, frame);
        if (registration.status() == ChamberRegistrationResult.Status.OVERLAP) {
            return OriginResolution.rejected(ArmAttemptResult.Failure.ORIGIN_OVERLAP);
        }

        Optional<ChamberRecord> record = registry.record(registration.chamberUuid());
        if (record.isEmpty()) {
            return OriginResolution.rejected(ArmAttemptResult.Failure.REGISTRY_UNAVAILABLE);
        }
        chamberUuidSink.setChamberUuid(registration.chamberUuid());
        return new OriginResolution(record, Set.of());
    }

    private static ArmAttemptResult rejected(
            ChamberState readiness, List<UUID> participants, ArmAttemptResult.Failure failure) {
        return new ArmAttemptResult(false, readiness, participants, Set.of(failure));
    }

    private static Set<ArmAttemptResult.Failure> failuresFor(ChamberActivationSnapshot snapshot) {
        EnumSet<ArmAttemptResult.Failure> failures = EnumSet.noneOf(ArmAttemptResult.Failure.class);
        if (!snapshot.enabled()) failures.add(ArmAttemptResult.Failure.DISABLED);
        if (!snapshot.sealed()) failures.add(ArmAttemptResult.Failure.UNSEALED);
        if (snapshot.participants().isEmpty()) failures.add(ArmAttemptResult.Failure.NO_PARTICIPANTS);
        if (snapshot.participants().stream().anyMatch(participant -> !participant.quantumState())) {
            failures.add(ArmAttemptResult.Failure.MISSING_QUANTUM_STATE);
        }
        return Set.copyOf(failures);
    }

    interface ChamberRegistryPort {
        Optional<String> loadError();

        ChamberRegistrationResult registerOrigin(Identifier worldKey, DimensionRole role, ChamberFrame frame);

        Optional<ChamberRecord> record(UUID chamberUuid);
    }

    @FunctionalInterface
    interface ChamberUuidSink {
        void setChamberUuid(UUID chamberUuid);
    }

    record OriginResolution(Optional<ChamberRecord> record, Set<ArmAttemptResult.Failure> failureReasons) {
        OriginResolution {
            record = Objects.requireNonNull(record, "record");
            failureReasons = Set.copyOf(Objects.requireNonNull(failureReasons, "failureReasons"));
            if (record.isPresent() == !failureReasons.isEmpty()) {
                throw new IllegalArgumentException("origin resolution must contain either a record or a failure");
            }
        }

        static OriginResolution rejected(ArmAttemptResult.Failure failure) {
            return new OriginResolution(Optional.empty(), Set.of(failure));
        }

        boolean usable() {
            return record.isPresent();
        }
    }

    private record PersistentRegistryPort(ChamberRegistryState state) implements ChamberRegistryPort {
        private PersistentRegistryPort {
            Objects.requireNonNull(state, "state");
        }

        @Override
        public Optional<String> loadError() {
            return state.loadError();
        }

        @Override
        public ChamberRegistrationResult registerOrigin(Identifier worldKey, DimensionRole role, ChamberFrame frame) {
            return state.registry().registerOrigin(worldKey, role, frame);
        }

        @Override
        public Optional<ChamberRecord> record(UUID chamberUuid) {
            return Optional.ofNullable(state.registry().records().get(chamberUuid));
        }
    }
}
