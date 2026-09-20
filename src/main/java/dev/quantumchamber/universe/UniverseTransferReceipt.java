package dev.quantumchamber.universe;

import java.util.UUID;
import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 僅供本 service 執行期返還使用；instance token 不可跨重啟重建成有效憑據。 */
public record UniverseTransferReceipt(UUID playerId, UniverseId universeId,
        UniverseTransferPoint source, UniverseTransferPoint destination,
        RegistryKey<World> destinationKey, UUID sourceInstanceId, UUID destinationInstanceId) {
    public UniverseTransferReceipt {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(destinationKey, "destinationKey");
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(destinationInstanceId, "destinationInstanceId");
        if (!destinationKey.equals(destination.worldKey())
                || !destinationKey.equals(UniverseKeys.world(universeId, DimensionRole.OVERWORLD))
                || source.worldKey().equals(destinationKey)
                || sourceInstanceId.equals(destinationInstanceId)) {
            throw new IllegalArgumentException("Universe、世界 key 或 runtime instance 憑據不一致");
        }
    }
}
