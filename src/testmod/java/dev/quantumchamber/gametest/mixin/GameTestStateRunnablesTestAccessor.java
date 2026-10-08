package dev.quantumchamber.gametest.mixin;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.test.GameTestState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 唯讀原版 {@code GameTestState} 的 runAtTick 表（{@code ticksByRunnables}；M5 F6-0，F4a-0 quality re-review Minor 3）：取代 {@code M5UniverseGameTests.scheduledCount}
 * 的反射。只供 harness test 讀項目數；禁止修改表。
 */
@Mixin(GameTestState.class)
public interface GameTestStateRunnablesTestAccessor {
    @Accessor("ticksByRunnables") Object2LongMap<Runnable> quantumchamberTest$getTicksByRunnables();
}
