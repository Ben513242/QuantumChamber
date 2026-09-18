package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M2LeaseBootstrapProbe;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 預設關閉；只對核准 own bootstrap SID 的第二次 native add 注入失敗。 */
@Mixin(ServerChunkManager.class)
abstract class M2TicketAcquireFaultMixin {
    @Inject(method="addTicket(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V",at=@At("HEAD"))
    private void quantumchamberTest$before(ChunkTicketType<?> type,ChunkPos chunk,int radius,Object argument,CallbackInfo callback) {
        M2LeaseBootstrapProbe.beforeTicket((ServerChunkManager)(Object)this,type,chunk,radius,argument);
    }
    @Inject(method="addTicket(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V",at=@At("RETURN"))
    private void quantumchamberTest$after(ChunkTicketType<?> type,ChunkPos chunk,int radius,Object argument,CallbackInfo callback) {
        M2LeaseBootstrapProbe.afterTicket((ServerChunkManager)(Object)this,type,chunk,radius,argument);
    }
    @Inject(method="removeTicket(Lnet/minecraft/server/world/ChunkTicketType;Lnet/minecraft/util/math/ChunkPos;ILjava/lang/Object;)V",at=@At("RETURN"))
    private void quantumchamberTest$removed(ChunkTicketType<?> type,ChunkPos chunk,int radius,Object argument,CallbackInfo callback) {
        M2LeaseBootstrapProbe.afterRemoveTicket((ServerChunkManager)(Object)this,type,chunk,radius,argument);
    }
}
