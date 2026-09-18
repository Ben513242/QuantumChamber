package dev.quantumchamber.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** 使用原生玩家與嵌入式連線；不冒充原生紅石入場資格檢查。 */
public final class ConnectedGameTestPlayer implements AutoCloseable {
    private static final java.util.Map<UUID,ConnectedGameTestPlayer> OBSERVED=new java.util.HashMap<>();
    static {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler,sender,server) -> {
            var fixture=OBSERVED.get(handler.player.getUuid());
            if(fixture!=null) fixture.joinTick=server.getTicks();
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {
            var fixture=OBSERVED.get(handler.player.getUuid());
            if(fixture!=null) { fixture.disconnectTick=server.getTicks(); fixture.disconnectOnThread=server.isOnThread(); }
        });
    }
    private final ServerPlayerEntity player;
    private final EmbeddedChannel channel;
    private final ClientConnection connection;
    private int joinTick=-1,disconnectTick=-1;
    private boolean disconnectOnThread;
    public ConnectedGameTestPlayer(ServerWorld world) {
        this(world,newProfile());
    }
    public ConnectedGameTestPlayer(ServerWorld world,GameProfile profile) {
        var data = ConnectedClientData.createDefault(profile, false);
        player = new ServerPlayerEntity(world.getServer(), world, data.gameProfile(), data.syncedOptions());
        connection = new ClientConnection(NetworkSide.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        OBSERVED.put(player.getUuid(),this);
        world.getServer().getPlayerManager().onPlayerConnect(connection, player, data);
    }
    private static GameProfile newProfile() {
        UUID uuid=UUID.randomUUID(); return new GameProfile(uuid,"m2-"+uuid.toString().substring(0,8));
    }
    public ServerPlayerEntity player() { return player; }
    public int joinTick() { return joinTick; }
    public int disconnectTick() { return disconnectTick; }
    public boolean disconnectOnThread() { return disconnectOnThread; }
    /** 關閉真 channel，再執行原生 NetworkIo 在 server tick 使用的 disconnect dispatch。 */
    public void disconnect() {
        if(!player.getServer().isOnThread()) throw new IllegalStateException("native disconnect dispatch 必須 server thread");
        channel.close().syncUninterruptibly(); connection.handleDisconnection(); channel.finishAndReleaseAll();
    }
    public void confirmTeleport() {
        channel.runPendingTasks(); Integer teleport=null; Object outbound;
        while((outbound=channel.readOutbound())!=null) {
            if(outbound instanceof net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket position) teleport=position.getTeleportId();
            io.netty.util.ReferenceCountUtil.release(outbound);
        }
        if(teleport==null) throw new IllegalStateException("沒有原生登入 teleport packet，不能假 ACK");
        player.networkHandler.onTeleportConfirm(new net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket(teleport));
    }
    @Override public void close() {
        if(player.getServer().getPlayerManager().getPlayer(player.getUuid())==player) player.getServer().getPlayerManager().remove(player);
        OBSERVED.remove(player.getUuid(),this);
        channel.finishAndReleaseAll();
    }
}
