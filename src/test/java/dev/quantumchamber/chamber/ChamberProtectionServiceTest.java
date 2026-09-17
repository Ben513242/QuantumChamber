package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberProtectionServiceTest {
    private static final ChamberFrame FRAME = new ChamberFrame(new BlockPos(0, 70, 0), Direction.NORTH);
    private static final BlockPos PROTECTED_POS = new BlockPos(0, 70, 0);
    private static final BlockPos OUTSIDE_POS = new BlockPos(20, 70, 0);

    @Test
    void onlyOffOriginWithMatchingWorldKeyMayMutateItsIndexedVolume() {
        for (ChamberPowerState power : ChamberPowerState.values()) {
            ChamberRegistry registry = new ChamberRegistry();
            registry.putLoaded(new ChamberRecord(UUID.randomUUID(), World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                    PROTECTED_POS, Direction.NORTH, ChamberInstanceKind.ORIGIN, false, false, power));
            ChamberProtectionService protection = new ChamberProtectionService();
            protection.attach(registry);
            org.junit.jupiter.api.Assertions.assertEquals(power == ChamberPowerState.OFF,
                    protection.mayMutate(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, PROTECTED_POS));
            assertFalse(protection.mayMutate(Identifier.of("foreign", "other"), DimensionRole.OVERWORLD, PROTECTED_POS));
            assertTrue(registry.findAt(DimensionRole.OVERWORLD, PROTECTED_POS).isPresent());
        }
    }

    @Test
    void destroyedOffOriginDoesNotUnlock() {
        ChamberRegistry registry = new ChamberRegistry();
        registry.putLoaded(new ChamberRecord(UUID.randomUUID(), World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                PROTECTED_POS, Direction.NORTH, ChamberInstanceKind.ORIGIN, true, true, ChamberPowerState.OFF));
        ChamberProtectionService protection = new ChamberProtectionService();
        protection.attach(registry);
        assertFalse(protection.mayMutate(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    @Test
    void allowsMutationBeforeARegistryIsAttached() {
        ChamberProtectionService protection = new ChamberProtectionService();

        assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    @Test
    void allowsMutationWhenTheAttachedRegistryHasNoIndexedVolumes() {
        ChamberProtectionService protection = new ChamberProtectionService();
        protection.attach(new ChamberRegistry());

        assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    @Test
    void rejectsMutationInsideARegisteredVolumeButAllowsOutsideIt() {
        ChamberProtectionService protection = attachedProtection();

        assertFalse(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
        assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, OUTSIDE_POS));
    }

    @Test
    void keepsDimensionRolesIsolated() {
        ChamberProtectionService protection = attachedProtection();

        assertTrue(protection.mayMutate(DimensionRole.NETHER, PROTECTED_POS));
    }

    @Test
    void protectsDisabledChamberVolumes() {
        ChamberRegistry registry = registeredRegistry();
        ChamberProtectionService protection = new ChamberProtectionService();
        protection.attach(registry);
        ChamberLifecycleService lifecycle = new ChamberLifecycleService(registry);
        var chamberUuid = registry.records().keySet().iterator().next();

        assertTrue(lifecycle.setEnabled(chamberUuid, false));

        assertFalse(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    @Test
    void permitsOnlyTheActiveAuthorizedMutationScope() {
        ChamberProtectionService protection = attachedProtection();

        assertTrue(protection.authorizedMutation(
                () -> protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS)));
        assertFalse(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    @Test
    void restoresAuthorizationDepthAfterNestedAndExceptionalScopes() {
        ChamberProtectionService protection = attachedProtection();

        protection.authorizedMutation(() -> {
            assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
            protection.authorizedMutation(() ->
                    assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS)));
            assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
        });
        assertFalse(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));

        assertThrows(IllegalStateException.class, () -> protection.authorizedMutation(() -> {
            assertTrue(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
            throw new IllegalStateException("expected test failure");
        }));
        assertFalse(protection.mayMutate(DimensionRole.OVERWORLD, PROTECTED_POS));
    }

    private static ChamberProtectionService attachedProtection() {
        ChamberProtectionService protection = new ChamberProtectionService();
        protection.attach(registeredRegistry());
        return protection;
    }

    private static ChamberRegistry registeredRegistry() {
        ChamberRegistry registry = new ChamberRegistry();
        registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME);
        return registry;
    }
}
