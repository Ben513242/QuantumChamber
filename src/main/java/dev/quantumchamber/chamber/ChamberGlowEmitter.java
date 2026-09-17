package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 僅使用已發布原艙身分與原生粒子輸出。 */
public final class ChamberGlowEmitter {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private ChamberGlowEmitter() { }

    public static void emit(ServerWorld world, BlockPos controllerPos) {
        try {
            emitKnownOrigin(world, controllerPos);
        } catch (RuntimeException failure) {
            // 外觀失敗不能影響已完成的供電協調、保護、返還或下次排程。
            LOGGER.debug("艙體 {} 的原生輝光輸出略過", controllerPos, failure);
        }
    }

    private static void emitKnownOrigin(ServerWorld world, BlockPos controllerPos) {
        if (!world.getServer().isOnThread()) return;
        var chunk = world.getChunkManager().getWorldChunk(controllerPos.getX() >> 4, controllerPos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(controllerPos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld() != world
                || !controller.getPos().equals(controllerPos) || !controller.powerInitialized()
                || !controller.wasPowered() || controller.instanceKind() != ChamberInstanceKind.ORIGIN) return;
        var block = chunk.getBlockState(controllerPos);
        if (!block.isOf(ModBlocks.CHAMBER_CONTROLLER) || !block.equals(controller.getCachedState())) return;
        var origin = ChamberProtectionService.get().originAt(world, controllerPos).orElse(null);
        if (origin == null || !origin.chamberUuid().equals(controller.chamberUuid())
                || origin.facing() != block.get(ChamberControllerBlock.FACING)
                || origin.powerState() != ChamberPowerState.POWERED) return;
        var bounds = ChamberGeometry.bounds(new ChamberFrame(origin.anchorPos(), origin.facing()));
        for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++) {
            for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++) {
                if (world.getChunkManager().getWorldChunk(x, z) == null) return;
            }
        }
        for (var sample : ChamberGlowPattern.sample(bounds, world.getTime())) {
            var particlePos = BlockPos.ofFloored(sample.x(), sample.y(), sample.z());
            if (!world.isInBuildLimit(particlePos)
                    || world.getChunkManager().getWorldChunk(particlePos.getX() >> 4, particlePos.getZ() >> 4) == null) continue;
            float strength = sample.intensity();
            ParticleEffect effect = sample.spark() ? ParticleTypes.END_ROD
                    : new DustColorTransitionParticleEffect(
                            new Vector3f(0.35f, 0.85f, 1.0f).mul(strength),
                            new Vector3f(0.65f, 0.9f, 1.0f).mul(strength), 0.6f + strength * 2);
            // 原生非強制 API 保留 32 格距離／玩家粒子設定；count=0 將 offset 精確當作速度。
            world.spawnParticles(effect, sample.x(), sample.y(), sample.z(), 0,
                    sample.velocityX(), sample.velocityY(), sample.velocityZ(), 1);
        }
    }
}
