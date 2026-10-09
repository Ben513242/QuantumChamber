package dev.quantumchamber.gametest.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Queue;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 僅 testmod（M5 H5）：M5 collapse probe 的 unload chain 讀原版的 holder 集合與卸載佇列（只讀，不得修改）。 */
@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerTestAccessor {
    @Accessor("currentChunkHolders")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> quantumchamberTest$currentChunkHolders();

    @Accessor("chunksToUnload")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> quantumchamberTest$chunksToUnload();

    @Accessor("unloadTaskQueue")
    Queue<Runnable> quantumchamberTest$unloadTaskQueue();
}
