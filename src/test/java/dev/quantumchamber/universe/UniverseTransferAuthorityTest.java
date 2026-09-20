package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.*;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class UniverseTransferAuthorityTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UniverseTransferPoint SOURCE = new UniverseTransferPoint(
            World.OVERWORLD, new Vec3d(15.5, 81, -0.5), new Vec3d(0.1, 0.2, -0.3), 75, -25);
    private final UniverseTransferAuthority authority = new UniverseTransferAuthority();

    @Test
    void rejectsEveryMissingAuthorityBeforeAllowingMove() {
        var decisions = new UniverseTransferAuthority.Decision[] {
            REJECT_SERVER_THREAD, REJECT_PLAYER_UUID, REJECT_PLAYER_IDENTITY,
            REJECT_SOURCE_IDENTITY, REJECT_TARGET_NOT_ACTIVE, REJECT_TARGET_IDENTITY,
            REJECT_TARGET_NOT_FULL, REJECT_TARGET_UNSAFE
        };
        for (int i = 0; i < decisions.length; i++) {
            var flags = validFlags();
            flags[i] = false;
            assertEquals(decisions[i], authority.evaluate(snapshot(flags)), "guard " + i);
        }
    }

    @Test
    void earlierFailureWinsWhenLaterAuthoritiesAlsoFail() {
        var decisions = new UniverseTransferAuthority.Decision[] {
            REJECT_SERVER_THREAD, REJECT_PLAYER_UUID, REJECT_PLAYER_IDENTITY,
            REJECT_SOURCE_IDENTITY, REJECT_TARGET_NOT_ACTIVE, REJECT_TARGET_IDENTITY,
            REJECT_TARGET_NOT_FULL, REJECT_TARGET_UNSAFE
        };
        for (int first = 0; first < decisions.length; first++) {
            var flags = validFlags();
            Arrays.fill(flags, first, flags.length, false);
            assertEquals(decisions[first], authority.evaluate(snapshot(flags)), "first guard " + first);
        }
    }

    @Test
    void allowsCompleteSnapshotAndPreservesFrozenSourcePose() {
        var frozen = snapshot(validFlags());
        assertEquals(ALLOW, authority.evaluate(frozen));
        assertEquals(World.OVERWORLD, frozen.source().worldKey());
        assertEquals(new Vec3d(15.5, 81, -0.5), frozen.source().position());
        assertEquals(new Vec3d(0.1, 0.2, -0.3), frozen.source().velocity());
        assertEquals(75, frozen.source().yaw());
        assertEquals(-25, frozen.source().pitch());
    }

    @Test
    void rejectsNonFinitePositionVelocityAndRotation() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (Vec3d vector : new Vec3d[] {new Vec3d(invalid, 1, 2), new Vec3d(1, invalid, 2), new Vec3d(1, 2, invalid)}) {
                assertThrows(IllegalArgumentException.class, () -> new UniverseTransferPoint(World.OVERWORLD, vector, Vec3d.ZERO, 0, 0));
                assertThrows(IllegalArgumentException.class, () -> new UniverseTransferPoint(World.OVERWORLD, Vec3d.ZERO, vector, 0, 0));
            }
            assertThrows(IllegalArgumentException.class, () -> new UniverseTransferPoint(World.OVERWORLD, Vec3d.ZERO, Vec3d.ZERO, (float) invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new UniverseTransferPoint(World.OVERWORLD, Vec3d.ZERO, Vec3d.ZERO, 0, (float) invalid));
        }
    }

    @Test
    void receiptRejectsMismatchedUniverseDestinationAndInstanceIdentity() {
        var universe = UniverseId.of(OTHER);
        var key = UniverseKeys.world(universe, DimensionRole.OVERWORLD);
        var destination = new UniverseTransferPoint(key, SOURCE.position(), SOURCE.velocity(), SOURCE.yaw(), SOURCE.pitch());
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferReceipt(
                PLAYER, universe, SOURCE, destination, World.NETHER, PLAYER, OTHER));
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferReceipt(
                PLAYER, UniverseId.of(PLAYER), SOURCE, destination, key, PLAYER, OTHER));
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferReceipt(
                PLAYER, universe, SOURCE, destination, key, PLAYER, PLAYER));
    }

    @Test
    void observationRequiresExactIdentityAndCompletePose() {
        assertTrue(observed(SOURCE.position(), SOURCE.velocity(), 435, -25, true, true, true).matches(SOURCE));
        assertFalse(observed(new Vec3d(15.5, 82, -0.5), SOURCE.velocity(), 75, -25, true, true, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), Vec3d.ZERO, 75, -25, true, true, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), SOURCE.velocity(), 76, -25, true, true, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), SOURCE.velocity(), 75, -26, true, true, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), SOURCE.velocity(), 75, -25, false, true, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), SOURCE.velocity(), 75, -25, true, false, true).matches(SOURCE));
        assertFalse(observed(SOURCE.position(), SOURCE.velocity(), 75, -25, true, true, false).matches(SOURCE));
        assertFalse(observed(new Vec3d(Double.NaN, 81, -0.5), SOURCE.velocity(), 75, -25, true, true, true).matches(SOURCE));
    }

    @Test
    void resultCannotClaimSuccessOrRecoveryWithoutMatchingEvidence() {
        var actual = observed(SOURCE.position(), SOURCE.velocity(), 75, -25, true, true, true);
        var unattempted = new UniverseTransferResult.MoveEvidence(UniverseTransferResult.NativeOutcome.NOT_ATTEMPTED, actual, "");
        var noRollback = new UniverseTransferResult.RollbackEvidence(UniverseTransferResult.RollbackOutcome.NOT_ATTEMPTED,
                UniverseTransferResult.NativeOutcome.NOT_ATTEMPTED, actual, "");
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferResult(
                UniverseTransferResult.Outcome.MOVED, ALLOW, SOURCE, unattempted, noRollback, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferResult(
                UniverseTransferResult.Outcome.RETURNED, ALLOW, SOURCE, unattempted, noRollback, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferResult(
                UniverseTransferResult.Outcome.FAILED_ROLLED_BACK, POST_MOVE_VERIFICATION_FAILED, SOURCE, unattempted, noRollback, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new UniverseTransferResult(
                UniverseTransferResult.Outcome.FAILED_RECOVERY_REQUIRED, POST_MOVE_VERIFICATION_FAILED, SOURCE, unattempted, noRollback, null, ""));
    }

    private static UniverseTransferResult.Observation observed(Vec3d position, Vec3d velocity,
            float yaw, float pitch, boolean playerExact, boolean worldExact, boolean entityExact) {
        return new UniverseTransferResult.Observation(PLAYER, World.OVERWORLD, position, velocity,
                yaw, pitch, playerExact, worldExact, entityExact);
    }

    private static boolean[] validFlags() {
        var flags = new boolean[8];
        Arrays.fill(flags, true);
        return flags;
    }

    private static UniverseTransferAuthority.Snapshot snapshot(boolean[] flags) {
        return new UniverseTransferAuthority.Snapshot(flags[0], PLAYER, flags[1] ? PLAYER : OTHER,
                flags[2], flags[3], flags[4], flags[5], flags[6], flags[7], SOURCE);
    }
}
