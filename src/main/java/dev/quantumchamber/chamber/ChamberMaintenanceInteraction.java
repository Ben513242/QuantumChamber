package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 只在伺服器核對當下世界身分，再提交 Origin 維護。 */
public final class ChamberMaintenanceInteraction {
    private ChamberMaintenanceInteraction() { }

    public static void initialize() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> attack(player, world, pos));
    }

    public static ActionResult toggleEnabled(ServerWorld world, BlockPos pos, PlayerEntity player) {
        OriginContext context = resolve(world, pos, player);
        if (context == null) return result(player, false, "無法維護：需要有效的原始控制器身分。");
        boolean enabled = !context.record().enabled();
        if (!context.lifecycle().setOriginEnabled(context.authority(), enabled)) {
            return result(player, false, "維護失敗：控制器身分已變更。");
        }
        // 管理停用也經過同一返還交易；高電位不解除艙體保護。
        new ChamberRedstoneService().onLoad(world, pos);
        return result(player, true, enabled ? "原始艙體已啟用；供電後補齊關門、玩家與藥水條件即可啟動。"
                : "原始艙體已停用；仍供電或返還中會保留保護，斷電返還完成後可拆改。");
    }

    private static ActionResult attack(PlayerEntity player, World world, BlockPos pos) {
        if (world.isClient) {
            if (!world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)) return ActionResult.PASS;
            if (player.isSpectator()) return ActionResult.FAIL;
            return world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller
                    && (controller.chamberUuid() != null || controller.instanceKind() != ChamberInstanceKind.ORIGIN)
                    ? ActionResult.SUCCESS : ActionResult.PASS;
        }
        if (!(world instanceof ServerWorld serverWorld)) return ActionResult.PASS;
        // 非法封包先交回原生 guard；非 PASS 會觸發 Fabric 座標同步，不能用它拒絕未載入遠端目標。
        if (player.getWorld() != world || !player.canInteractWithBlockAt(pos, 1.0)
                || pos.getY() >= world.getTopY() || !world.canPlayerModifyAt(player, pos)
                || !world.isChunkLoaded(pos)) return ActionResult.PASS;
        if (!world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)) return ActionResult.PASS;
        var role = DimensionRole.fromVanillaKey(world.getRegistryKey());
        var state = ChamberRegistryState.get(serverWorld.getServer());
        var entity = world.getBlockEntity(pos);
        if (state.loadError().isEmpty() && role.isPresent()
                && entity instanceof ChamberControllerBlockEntity controller
                && controller.chamberUuid() == null && controller.instanceKind() == ChamberInstanceKind.ORIGIN
                && state.registry().findAt(role.get(), pos).isEmpty()) return ActionResult.PASS;
        if (!player.isCreative() || player.isSpectator()) {
            return result(player, false, "無法拆除：請先斷電完成返還，再使用創造模式。");
        }
        OriginContext context = resolve(serverWorld, pos, player);
        if (context == null) return result(player, false, "無法拆除：原始控制器身分不符。");
        if (context.record().powerState() != ChamberPowerState.OFF) return result(player, false, "請先斷電並等待返還完成。");
        boolean removed = context.lifecycle().dismantleOrigin(context.authority(), () ->
                ChamberProtectionService.get().authorizedMutation(serverWorld, pos, () -> serverWorld.removeBlock(pos, false)));
        // 非 PASS 取消原生後續 break，避免同一輸入再執行第二次 mutation。
        return result(player, removed, removed ? "控制器已拆除；艙體保護已解除。" : "拆除失敗；艙體保護仍保留。");
    }

    private static OriginContext resolve(ServerWorld world, BlockPos pos, PlayerEntity player) {
        if (player.isSpectator() || player.getWorld() != world || !world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)) return null;
        var role = DimensionRole.fromVanillaKey(world.getRegistryKey());
        var state = ChamberRegistryState.get(world.getServer());
        if (role.isEmpty() || state.loadError().isPresent() || world.getServer().getWorld(world.getRegistryKey()) != world) return null;
        if (!(world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld() != world || !controller.getPos().equals(pos)
                || controller.chamberUuid() == null || controller.instanceKind() != ChamberInstanceKind.ORIGIN
                || !controller.getCachedState().isOf(ModBlocks.CHAMBER_CONTROLLER)) return null;
        var facing = world.getBlockState(pos).get(ChamberControllerBlock.FACING);
        if (controller.getCachedState().get(ChamberControllerBlock.FACING) != facing) return null;
        var record = state.registry().findOrigin(world.getRegistryKey().getValue(), role.get(), new ChamberFrame(pos, facing)).orElse(null);
        if (record == null || record.destroyed() || record.instanceKind() != ChamberInstanceKind.ORIGIN
                || !record.chamberUuid().equals(controller.chamberUuid())) return null;
        var authority = new ChamberOriginAuthority(controller.chamberUuid(), world.getRegistryKey().getValue(), role.get(),
                pos, facing, controller.instanceKind());
        return new OriginContext(controller, record, authority, new ChamberLifecycleService(state.registry()));
    }

    private static ActionResult result(PlayerEntity player, boolean accepted, String message) {
        player.sendMessage(Text.literal(message), true);
        return accepted ? ActionResult.SUCCESS : ActionResult.FAIL;
    }

    private record OriginContext(ChamberControllerBlockEntity controller, ChamberRecord record,
            ChamberOriginAuthority authority, ChamberLifecycleService lifecycle) { }
}
