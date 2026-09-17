package dev.quantumchamber.chamber;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** 預設只提供 ARMED，沒有 session、傳送或效果消耗。 */
public final class ChamberSessions {
    private static ChamberSessionGateway gateway = new ChamberSessionGateway() {
        @Override public Presence presence(MinecraftServer server, UUID uuid) { return Presence.NONE; }
        @Override public StartResult start(ServerWorld world, ChamberControllerBlockEntity controller, List<UUID> participants) {
            return StartResult.ARMED_ONLY;
        }
        @Override public boolean returnToOrigin(ServerWorld world, ChamberControllerBlockEntity controller) { return true; }
    };

    private ChamberSessions() { }
    public static ChamberSessionGateway gateway() { return gateway; }
    public static void install(ChamberSessionGateway replacement) { gateway = Objects.requireNonNull(replacement, "gateway"); }
}
