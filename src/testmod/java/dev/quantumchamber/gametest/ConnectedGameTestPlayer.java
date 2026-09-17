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
    private final ServerPlayerEntity player;
    private final EmbeddedChannel channel;
    public ConnectedGameTestPlayer(ServerWorld world) {
        UUID uuid = UUID.randomUUID();
        var data = ConnectedClientData.createDefault(new GameProfile(uuid, "m2-" + uuid.toString().substring(0,8)), false);
        player = new ServerPlayerEntity(world.getServer(), world, data.gameProfile(), data.syncedOptions());
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        world.getServer().getPlayerManager().onPlayerConnect(connection, player, data);
    }
    public ServerPlayerEntity player() { return player; }
    @Override public void close() {
        player.getServer().getPlayerManager().remove(player);
        channel.finishAndReleaseAll();
    }
}
