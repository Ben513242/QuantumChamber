package dev.quantumchamber.chamber;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** M1.2 的 session 邊界；M2 才安裝實際空間服務。 */
public interface ChamberSessionGateway {
    enum Presence { NONE, ACTIVE, RETURNING, UNKNOWN }
    enum StartResult { ARMED_ONLY, STARTED, REJECTED }

    Presence presence(MinecraftServer server, UUID chamberUuid);
    StartResult start(ServerWorld originWorld, ChamberControllerBlockEntity controller, List<UUID> participantUuids);
    boolean returnToOrigin(ServerWorld originWorld, ChamberControllerBlockEntity controller);
}
