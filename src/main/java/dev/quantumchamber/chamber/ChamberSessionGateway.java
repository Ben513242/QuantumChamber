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
     * BUSY（M7）：同艙另一端的回程（暫占或 HOME 列）占用這座艙；本端不取得門控制權，也不交出量子膜正規化。
     */
    enum Presence { NONE, ARMING, ACTIVE, RETURNING, UNKNOWN, COLLAPSE, PASSAGE, BUSY }
    enum StartResult { ARMED_ONLY, STAGING, STARTED, REJECTED }

    Presence presence(MinecraftServer server, UUID chamberUuid);
    default Presence localPresence(ServerWorld world, net.minecraft.util.math.BlockPos pos) {
        return world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity be && be.chamberUuid()!=null
                ? presence(world.getServer(),be.chamberUuid()) : Presence.NONE;
    }
    StartResult start(ServerWorld originWorld, ChamberControllerBlockEntity controller, List<UUID> participantUuids);
    boolean returnToOrigin(ServerWorld originWorld, ChamberControllerBlockEntity controller);

    /** 正常返回代表exact原艙已設未知preset專用旗標；非正式adapter不得假ack。 */
    default void onUnknownSourcePreset(MinecraftServer server, dev.quantumchamber.persistence.SessionRecoveryRecord checked) {
        throw new UnsupportedOperationException("session gateway 未接未知來源preset拒絕通知");
    }
}
