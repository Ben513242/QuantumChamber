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
    public static final class DelayedFull implements HomeRootTickets.Backend {
        private final MinecraftServer server;
        private final int start;
        public int polls;
        public int lastMissing;
        public int firstFull=-1;

        public DelayedFull(MinecraftServer server) { this.server=server; start=server.getTicks(); }
        public void add(ServerWorld world,ChunkPos pos,UUID sid) { HomeRootTickets.NATIVE.add(world,pos,sid); }
        public void remove(ServerWorld world,ChunkPos pos,UUID sid) { HomeRootTickets.NATIVE.remove(world,pos,sid); }
        @Override public boolean full(ServerWorld world,ChunkPos pos) {
            int ordinal=server.getTicks()-start+1;
            polls++;
            if (ordinal<=200) { lastMissing=ordinal; return false; }
            boolean actual=world.getChunkManager().getWorldChunk(pos.x,pos.z)!=null;
            if (actual && firstFull<0) firstFull=ordinal;
            return actual;
        }
        public HomeRootTickets.Lease acquire(UUID sid,ChamberOriginAuthority root) {
            return HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE,this).orElseThrow();
        }
    }

    public static HomeRootTickets.Lease failFirstRemoval(MinecraftServer server,UUID sid,ChamberOriginAuthority root) {
        var backend=new HomeRootTickets.Backend() {
            boolean failed;
            public void add(ServerWorld world,ChunkPos pos,UUID id) { HomeRootTickets.NATIVE.add(world,pos,id); }
            public void remove(ServerWorld world,ChunkPos pos,UUID id) {
                if (!failed) { failed=true; throw new IllegalStateException("M7_REMOVE_ONCE"); }
                HomeRootTickets.NATIVE.remove(world,pos,id);
            }
        };
        return HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.START_CHECK,backend).orElseThrow();
    }

    public static void reloadTickets(MinecraftServer server) {
        try {
            var method=HomeRootTickets.class.getDeclaredMethod("closeAll",MinecraftServer.class,boolean.class);
            method.setAccessible(true);
            method.invoke(null,server,false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    public static java.util.Set<Long> positions(ServerWorld world,UUID sid) {
        var index=((dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor)
                world.getChunkManager().chunkLoadingManager.getTicketManager()).quantumchamberTest$getTicketsByPosition();
        var cells=new java.util.HashSet<Long>();
        for(var entry:index.long2ObjectEntrySet()) for(var ticket:entry.getValue())
            if(ticket.getType().toString().equals("quantumchamber_home_root") && ticket.toString().contains(sid.toString()))
                cells.add(entry.getLongKey());
        return cells;
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
