package dev.quantumchamber.universe;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * 所有操作限 owning server thread。物化前的 durable catalog 檢查由
 * {@link UniverseMaterializationService} 負責，backend 不讀取 current／dirty catalog。
 */
public interface DynamicDimensionBackend {
    /** 建立、expected-absence publish、LOAD 完成後才回報 ACTIVE；失敗需提供 rollback 結果。 */
    MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor);

    /** 僅接受 exact ACTIVE world；完整 drain/save/UNLOAD/expected-remove/close 後才算 UNLOADED。 */
    UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld expectedWorld);

    /** 必須同時確認 owning server、descriptor key、live map 與 exact world instance。 */
    Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseWorldDescriptor descriptor);
}
