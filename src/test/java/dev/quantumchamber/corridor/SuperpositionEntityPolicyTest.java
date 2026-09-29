package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.List;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class SuperpositionEntityPolicyTest {
    /** 與 client 端 PacketByteBuf.readRegistryKey（登入／重生封包的 dimension）相同的建構方式：RegistryKey.of(WORLD, id)。 */
    private static RegistryKey<World> decoded(String namespace, String path) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(namespace, path));
    }

    @Test void clientCorridorWorldKeepsShoulderEntitiesWithoutServerRegistration() {
        var corridor = decoded("quantumchamber", "superposition");
        assertSame(SuperpositionWorld.KEY, corridor, "封包解出的 world key 與常數為同一 interned 實例");
        // client world 不是 ServerWorld，restricted() 必為 false；client 端只能依 world key 判定。
        assertTrue(SuperpositionEntityPolicy.keepsShoulderEntities(true, corridor, false));
    }

    @Test void clientOtherWorldsDropShoulderEntitiesAsVanilla() {
        for (var key : List.of(decoded("minecraft", "overworld"), decoded("minecraft", "the_nether"), decoded("minecraft", "the_end"),
                decoded("quantumchamber", "universe/00000000-0000-0000-0000-000000000005/overworld"))) {
            assertFalse(SuperpositionEntityPolicy.keepsShoulderEntities(true, key, false), key.toString());
        }
    }

    @Test void serverDecisionStaysOnRegisteredRestrictedWorldOnly() {
        // server 端沿用 restricted()：同 key 但未登錄為本 server 固定 world 時照原版放下。
        assertFalse(SuperpositionEntityPolicy.keepsShoulderEntities(false, SuperpositionWorld.KEY, false));
        assertTrue(SuperpositionEntityPolicy.keepsShoulderEntities(false, SuperpositionWorld.KEY, true));
        assertFalse(SuperpositionEntityPolicy.keepsShoulderEntities(false, decoded("minecraft", "overworld"), false));
    }
}
