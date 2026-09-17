package dev.quantumchamber.gametest;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** 只標記 M1 foundation 的來源位置；M2 必須明確移除標記。 */
public final class M1FoundationSessionFixture {
    private static final Map<MinecraftServer,Set<Key>> FIXTURES=new IdentityHashMap<>();
    static { ServerLifecycleEvents.SERVER_STOPPED.register(FIXTURES::remove); }
    private M1FoundationSessionFixture() {}
    public static void set(ServerWorld world,BlockPos pos,boolean foundation) {
        requireThread(world);
        var entries=FIXTURES.computeIfAbsent(world.getServer(),ignored -> new HashSet<>());
        var key=new Key(world,pos.toImmutable());
        if(foundation) entries.add(key); else entries.remove(key);
    }
    public static boolean contains(ServerWorld world,BlockPos pos) {
        requireThread(world);
        var entries=FIXTURES.get(world.getServer());
        return entries!=null && entries.contains(new Key(world,pos));
    }
    private static void requireThread(ServerWorld world) {
        if(!world.getServer().isOnThread() || world.getServer().getWorld(world.getRegistryKey())!=world)
            throw new IllegalStateException("fixture 必須在 own server/world thread");
    }
    private record Key(ServerWorld world,BlockPos pos) {}
}
