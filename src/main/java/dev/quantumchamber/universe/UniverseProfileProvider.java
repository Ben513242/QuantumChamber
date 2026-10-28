package dev.quantumchamber.universe;

import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.dimension.DimensionOptions;

/** 啟動期宣告的 profile；只回傳設定資料，不提供指紋。 */
public interface UniverseProfileProvider {
    String id();
    int version();
    ParameterSchema parameterSchema();
    String adapterCapabilities();
    ResolvedSettings resolveGenerationSettings(NbtCompound parameters, ServerSettingsContext context);

    interface ParameterSchema {
        void validate(NbtCompound parameters);
    }

    record ResolvedSettings(Map<DimensionRole, DimensionOptions> roles, boolean generateStructures) {
        public ResolvedSettings {
            roles = Map.copyOf(roles);
        }
    }
}
