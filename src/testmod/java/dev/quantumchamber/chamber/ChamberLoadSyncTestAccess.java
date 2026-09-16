package dev.quantumchamber.chamber;

import net.minecraft.server.MinecraftServer;

/** 僅 testmod 使用，觀察 queue 是否真的釋放原 BE 與 server 項目。 */
public final class ChamberLoadSyncTestAccess {
    private ChamberLoadSyncTestAccess() {}

    public static boolean hasPending(MinecraftServer server, ChamberControllerBlockEntity controller) {
        return ChamberControllerLoadSyncQueue.hasPending(server, controller);
    }

    public static int pendingCount(MinecraftServer server) {
        return ChamberControllerLoadSyncQueue.pendingCount(server);
    }
}
