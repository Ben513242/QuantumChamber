package dev.quantumchamber.corridor;

import java.util.Optional;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class SessionEntranceDoorService {
    private SessionEntranceDoorService() {}
    public record ToggleResult(boolean changed,String message) {}
    public static Optional<ToggleResult> tryToggle(ServerWorld world,BlockPos pos) {
        if (!SessionSpaceProtection.isProtected(world,pos)) return Optional.empty();
        return CorridorPageManager.forServer(world.getServer()).toggleEntrance(world,pos)
                .or(() -> Optional.of(new ToggleResult(false,"未知或恢復中的入口身分，保持保護並拒絕操作。")));
    }
}
