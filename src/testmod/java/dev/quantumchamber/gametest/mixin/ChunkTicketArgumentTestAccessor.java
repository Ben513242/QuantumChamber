package dev.quantumchamber.gametest.mixin;

import net.minecraft.server.world.ChunkTicket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 唯讀原生 ticket argument；不提供 setter 或正式 API。 */
@Mixin(ChunkTicket.class)
public interface ChunkTicketArgumentTestAccessor {
    @Accessor("argument") Object quantumchamberTest$getArgument();
}
