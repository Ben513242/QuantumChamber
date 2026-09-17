package dev.quantumchamber.corridor;

import dev.quantumchamber.chamber.ChamberProtectionService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** 只使用 authority 已發布的租約索引，不在方塊寫入路徑讀 journal。 */
public final class SessionSpaceProtection {
    private static boolean initialized;
    private SessionSpaceProtection() {}
    public static void initialize() {
        if (initialized) return;
        initialized=true;
        ChamberProtectionService.registerAdditionalGuard((world,pos) -> !isProtected(world,pos));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> new CorridorPageManager().attach(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> CorridorPageManager.forServer(server).tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(CorridorPageManager::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(CorridorPageManager::detach);
    }
    public static boolean isProtected(ServerWorld world, BlockPos pos) {
        return CorridorPageManager.protectedPosition(world,pos);
    }
}
