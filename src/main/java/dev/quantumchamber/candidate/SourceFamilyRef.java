package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 凍結的來源參照；Catalog 型別本身不授權動態 Origin 啟動。 */
public sealed interface SourceFamilyRef permits SourceFamilyRef.Vanilla, SourceFamilyRef.Catalog {
    DimensionRole role();

    record Vanilla(RegistryKey<World> worldKey, DimensionRole role) implements SourceFamilyRef {
        public Vanilla {
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(role, "role");
            if (DimensionRole.fromVanillaKey(worldKey).filter(role::equals).isEmpty()) {
                throw new IllegalArgumentException("Vanilla 來源必須使用相符的原版 world key 與 role");
            }
        }
    }

    record Catalog(UniverseId universeId, DimensionRole role) implements SourceFamilyRef {
        public Catalog {
            Objects.requireNonNull(universeId, "universeId");
            Objects.requireNonNull(role, "role");
        }
    }
}
