package dev.quantumchamber.gametest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.util.collection.SortedArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 僅供 scoped testmod 讀取原生集合後建立 immutable witness；禁止修改集合。 */
@Mixin(ChunkTicketManager.class)
public interface ChunkTicketManagerTestAccessor {
    @Accessor("ticketsByPosition")
    Long2ObjectOpenHashMap<SortedArraySet<ChunkTicket<?>>> quantumchamberTest$getTicketsByPosition();
}
