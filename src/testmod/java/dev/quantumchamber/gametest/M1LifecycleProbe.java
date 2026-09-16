package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberProtectionService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.LoggerFactory;

/** 僅 testmod 使用：在真正 SERVER_STOPPED 後驗證先前受保護的 world 已 detach。 */
public final class M1LifecycleProbe implements ModInitializer {
    private static ServerWorld observedWorld;
    private static BlockPos observedPos;

    public static void observeProtectedPosition(ServerWorld world, BlockPos pos) {
        if (!world.getServer().isOnThread()) throw new IllegalStateException("GameTest 必須在 server thread 執行");
        if (ChamberProtectionService.get().mayMutate(world, pos)) throw new IllegalStateException("probe 必須先確認 attached protection");
        observedWorld = world;
        observedPos = pos.toImmutable();
    }

    @Override
    public void onInitialize() {
        // 所有 mod initializer 完成後才註冊，確保觀察順序在 production detach listener 之後。
        ServerLifecycleEvents.SERVER_STARTED.register(started -> ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (server != started) return;
            if (observedWorld == null || observedWorld.getServer() != server) return;
            if (!ChamberProtectionService.get().mayMutate(observedWorld, observedPos)) {
                throw new IllegalStateException("M1 lifecycle detach 驗證失敗");
            }
            LoggerFactory.getLogger("quantumchamber-testmod").info("M1 lifecycle detach verified after SERVER_STOPPED");
            observedWorld = null;
            observedPos = null;
        }));
    }
}
