package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Optional;
import dev.quantumchamber.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Origin 維護提交邊界；世界移除成功以前不解除紀錄與保護。 */
public final class ChamberLifecycleService {
    private final ChamberRegistry registry;

    public ChamberLifecycleService(ChamberRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public boolean setEnabled(UUID chamberUuid, boolean enabled) {
        return registry.setEnabled(chamberUuid, enabled);
    }

    public boolean setOriginEnabled(ChamberOriginAuthority authority, boolean enabled) {
        return resolveAuthorizedOrigin(authority)
                .map(record -> registry.setEnabled(record.chamberUuid(), enabled)).orElse(false);
    }

    public boolean dismantleOrigin(ChamberOriginAuthority authority, BooleanSupplier removeController) {
        Objects.requireNonNull(removeController, "removeController");
        ChamberRecord record = resolveAuthorizedOrigin(authority).orElse(null);
        if (record == null || record.powerState() != ChamberPowerState.OFF) return false;
        if (!removeController.getAsBoolean()) return false;
        // 原生 setter hook 可能已先完成同一筆清理。
        return !registry.records().containsKey(record.chamberUuid()) || completeRemoval(authority);
    }

    boolean completeRemoval(ChamberOriginAuthority authority) {
        var record = resolveAuthorizedOrigin(authority).orElse(null);
        return record != null && record.powerState() == ChamberPowerState.OFF && registry.removeOrigin(record.chamberUuid());
    }

    /** 只在 known Origin 的已載入 chunk 擷取身分；原始 setter 成功返回後才清理。 */
    public static boolean mutateController(ServerWorld world, BlockPos pos, BlockState replacement, BooleanSupplier mutation) {
        var protection = ChamberProtectionService.get();
        var record = replacement.isOf(ModBlocks.CHAMBER_CONTROLLER) ? null : protection.originAt(world, pos).orElse(null);
        ChamberControllerBlockEntity controller = null;
        ChamberOriginAuthority authority = null;
        if (record != null && record.powerState() == ChamberPowerState.OFF) {
            var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && chunk.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)
                    && chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity found
                    && !found.isRemoved() && found.getWorld() == world && found.getPos().equals(pos)
                    && found.instanceKind() == ChamberInstanceKind.ORIGIN
                    && record.chamberUuid().equals(found.chamberUuid())
                    && found.getCachedState().equals(chunk.getBlockState(pos))
                    && found.getCachedState().get(ChamberControllerBlock.FACING) == record.facing()) {
                controller = found;
                authority = new ChamberOriginAuthority(record.chamberUuid(), record.originWorldKey(), record.originDimensionRole(),
                        pos, record.facing(), found.instanceKind());
            }
        }
        // 區域變數隨呼叫結束釋放，巢狀 setter／false／throw 不共用暫存身分。
        boolean changed = mutation.getAsBoolean();
        if (changed && authority != null) {
            var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && !chunk.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)
                    && chunk.getBlockEntity(pos) != controller && controller.isRemoved()
                    && protection.completeOriginRemoval(authority)) {
                ChamberControllerLoadSyncQueue.discard(world, controller);
            }
        }
        return changed;
    }

    private Optional<ChamberRecord> resolveAuthorizedOrigin(ChamberOriginAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        ChamberRecord record = registry.records().get(authority.chamberUuid());
        if (record == null || record.destroyed()
                || record.instanceKind() != ChamberInstanceKind.ORIGIN
                || authority.instanceKind() != ChamberInstanceKind.ORIGIN
                || !record.originWorldKey().equals(authority.worldKey())
                || record.originDimensionRole() != authority.role()
                || !record.anchorPos().equals(authority.controllerPos())
                || record.facing() != authority.facing()) return Optional.empty();
        return Optional.of(record);
    }
}
