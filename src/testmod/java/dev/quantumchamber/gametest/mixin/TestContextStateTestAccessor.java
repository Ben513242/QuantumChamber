package dev.quantumchamber.gametest.mixin;

import net.minecraft.test.GameTestState;
import net.minecraft.test.TestContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 唯讀原版 {@code TestContext} 背後的 {@code GameTestState}（M5 F6-0，F4a-0 quality re-review Minor 3）：取代 {@code M5GameTestSupport.gameTestState} 與
 * {@code M5UniverseGameTests.scheduledCount} 各寫一份的反射。只由 {@code M5GameTestSupport.gameTestState} 讀；不提供 setter。
 */
@Mixin(TestContext.class)
public interface TestContextStateTestAccessor {
    @Accessor("test") GameTestState quantumchamberTest$getTest();
}
