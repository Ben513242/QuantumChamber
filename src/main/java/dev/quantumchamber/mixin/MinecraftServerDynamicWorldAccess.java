package dev.quantumchamber.mixin;

import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerDynamicWorldAccess {
    @Accessor("worlds")
    Map<RegistryKey<World>, ServerWorld> quantumchamber$getWorlds();

    @Accessor("session")
    LevelStorage.Session quantumchamber$getSession();

    @Accessor("workerExecutor")
    Executor quantumchamber$getWorkerExecutor();
}
