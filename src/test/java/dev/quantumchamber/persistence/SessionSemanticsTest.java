package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.superposition.SessionState;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class SessionSemanticsTest {
    @Test void lateralAlwaysKeepsCurrentEffectIncludingArming() {
        for (var state : SessionState.values()) {
            assertDoesNotThrow(() -> SessionSemantics.LATERAL_BUFF_MAINTAINED.validateEffectPolicy(state,false));
            assertThrows(IllegalArgumentException.class,() -> SessionSemantics.LATERAL_BUFF_MAINTAINED.validateEffectPolicy(state,true));
        }
    }

    @Test void legacyRetainsStrictArmingAndSuperpositionWithBothReturningDecisions() {
        var legacy=SessionSemantics.LEGACY_FORWARD_CONSUMED;
        assertDoesNotThrow(() -> legacy.validateEffectPolicy(SessionState.ARMING,true));
        assertThrows(IllegalArgumentException.class,() -> legacy.validateEffectPolicy(SessionState.ARMING,false));
        assertDoesNotThrow(() -> legacy.validateEffectPolicy(SessionState.SUPERPOSITION,false));
        assertThrows(IllegalArgumentException.class,() -> legacy.validateEffectPolicy(SessionState.SUPERPOSITION,true));
        assertDoesNotThrow(() -> legacy.validateEffectPolicy(SessionState.RETURNING,false));
        assertDoesNotThrow(() -> legacy.validateEffectPolicy(SessionState.RETURNING,true));
    }

    @Test void facingMapsUseIndependentCardinalExpectations() {
        Direction[][] pairs={{Direction.NORTH,Direction.EAST},{Direction.EAST,Direction.SOUTH},
                {Direction.SOUTH,Direction.WEST},{Direction.WEST,Direction.NORTH}};
        for (var pair : pairs) {
            assertEquals(pair[1],SessionSemantics.LATERAL_BUFF_MAINTAINED.corridorFacing(pair[0]));
            assertEquals(pair[0],SessionSemantics.LATERAL_BUFF_MAINTAINED.sourceFacing(pair[1]));
            assertEquals(pair[0],SessionSemantics.LEGACY_FORWARD_CONSUMED.corridorFacing(pair[0]));
            assertEquals(pair[0],SessionSemantics.LEGACY_FORWARD_CONSUMED.sourceFacing(pair[0]));
        }
    }

    @Test void verticalFacingAndMissingStateCannotSelectAnyMode() {
        for (var semantics : SessionSemantics.values()) {
            for (var vertical : new Direction[] {Direction.UP,Direction.DOWN}) {
                assertThrows(IllegalArgumentException.class,() -> semantics.corridorFacing(vertical));
                assertThrows(IllegalArgumentException.class,() -> semantics.sourceFacing(vertical));
            }
            assertThrows(NullPointerException.class,() -> semantics.validateEffectPolicy(null,false));
        }
    }
}
