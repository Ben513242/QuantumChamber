package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberOriginAuthority;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/** 先加真正的票再拋錯，見證部分取得後的原生清理。 */
public final class HomeTicketFaultFixture {
    private HomeTicketFaultFixture() {}
    public static int failAfterAdding(MinecraftServer server, UUID sid, ChamberOriginAuthority root) {
        int[] observed={0};
        var backend=new HomeRootTickets.Backend() {
            @Override public void add(ServerWorld world,ChunkPos pos,UUID id) {
                HomeRootTickets.NATIVE.add(world,pos,id);
                observed[0]=count(world,id);
                throw new IllegalStateException("M7_PARTIAL_ROOT_ADD");
            }
            @Override public void remove(ServerWorld world,ChunkPos pos,UUID id) {
                HomeRootTickets.NATIVE.remove(world,pos,id);
            }
        };
        try { HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.START_CHECK,backend); }
        catch(IllegalStateException fault) {
            if ("M7_PARTIAL_ROOT_ADD".equals(fault.getMessage())) return observed[0];
            throw fault;
        }
        throw new AssertionError("未進入原生票後的故障點");
    }
    public static int count(ServerWorld world,UUID sid) {
        var index=((dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor)
                world.getChunkManager().chunkLoadingManager.getTicketManager()).quantumchamberTest$getTicketsByPosition();
        int count=0;
        for(var tickets:index.values()) for(var ticket:tickets)
            if(ticket.getType().toString().equals("quantumchamber_home_root") && ticket.toString().contains(sid.toString())) count++;
        return count;
    }
}
