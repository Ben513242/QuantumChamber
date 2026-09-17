package dev.quantumchamber.chamber;

import java.util.List;
import java.util.Objects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

/** Finds non-spectator players completely contained in a chamber interior. */
public final class ChamberOccupantService {
    public List<ServerPlayerEntity> findParticipants(ServerWorld world, ChamberFrame frame) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(frame, "frame");
        Box interior = ChamberGeometry.interiorBox(frame);
        return List.copyOf(world.getPlayers(player -> isParticipant(
                interior, player.getBoundingBox(), player.isSpectator())));
    }

    static boolean isParticipant(Box interior, Box playerBounds, boolean spectator) {
        return !spectator && contains(interior, playerBounds);
    }

    public static boolean contains(Box outer, Box inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }
}
