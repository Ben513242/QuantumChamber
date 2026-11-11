package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.universe.UniverseRespawnService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * spec §5（Task R）：只換原版 {@code getRespawnTarget} 兩個 canonical fallback（固定 1.21 bytecode offset 99 的 missingSpawnBlock、
 * offset 116 的無重生點）所用的世界；有效床／錨在這之前就 return，位置與 TeleportTarget 其他欄位照原版。
 */
@Mixin(ServerPlayerEntity.class)
abstract class ServerPlayerUniverseRespawnMixin {
    @WrapOperation(method="getRespawnTarget(ZLnet/minecraft/world/TeleportTarget$PostDimensionTransition;)Lnet/minecraft/world/TeleportTarget;",
            at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;getOverworld()Lnet/minecraft/server/world/ServerWorld;"),require=2)
    private ServerWorld quantumchamber$respawnInDeathUniverse(MinecraftServer server, Operation<ServerWorld> original) {
        return UniverseRespawnService.fallbackWorld((ServerPlayerEntity)(Object)this, original.call(server));
    }
}
