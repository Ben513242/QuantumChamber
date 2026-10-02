package dev.quantumchamber.gametest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 唯讀原生 autosave 的倒數（M5 F3d fix round 3）：{@code MinecraftServer.tick} 在 {@code tickWorlds} 之後把它減一，≤0 時重設並
 * {@code saveAll}。只供 GameTest 判斷「接下來多少個 tick 內不會有原生 autosave」；不提供 setter。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAutosaveTestAccessor {
    @Accessor("ticksUntilAutosave") int quantumchamberTest$getTicksUntilAutosave();
}
