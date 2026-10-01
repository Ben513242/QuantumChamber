package dev.quantumchamber.chamber;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** M1.2 的 session 邊界；M2 才安裝實際空間服務。 */
public interface ChamberSessionGateway {
    /**
     * COLLAPSE 與 PASSAGE 是 M5 的塌縮狀態（spec §5、§10.7）：COLLAPSING、SEALED 為 COLLAPSE，PASSAGE_READY 為 PASSAGE。
     * 兩者都只由 record（collapse view）推導、不看 runtime，而且都讓該原艙不能開新 session；不走返還流程。
     */
    enum Presence { NONE, ARMING, ACTIVE, RETURNING, UNKNOWN, COLLAPSE, PASSAGE }
    enum StartResult { ARMED_ONLY, STAGING, STARTED, REJECTED }

    Presence presence(MinecraftServer server, UUID chamberUuid);
    StartResult start(ServerWorld originWorld, ChamberControllerBlockEntity controller, List<UUID> participantUuids);
    boolean returnToOrigin(ServerWorld originWorld, ChamberControllerBlockEntity controller);
}
