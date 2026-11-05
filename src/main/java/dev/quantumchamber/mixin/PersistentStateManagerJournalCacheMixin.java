package dev.quantumchamber.mixin;

import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryState.JournalSlot;
import dev.quantumchamber.persistence.SessionRecoveryState.JournalSlotView;
import java.util.Map;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** 只觀察本 mod 的 journal slot；不公開原生 map 或私有 fence。 */
@Mixin(PersistentStateManager.class)
public abstract class PersistentStateManagerJournalCacheMixin implements JournalSlotView {
    @Shadow @Final private Map<String, PersistentState> loadedStates;
    @Unique private final Object quantumchamber$journalSlotFence = new Object();
    @Unique private JournalSlot quantumchamber$peek(SessionRecoveryState expected) {
        if (!loadedStates.containsKey(SessionRecoveryState.STATE_ID)) return JournalSlot.ABSENT;
        var value = loadedStates.get(SessionRecoveryState.STATE_ID);
        if (value == null) return JournalSlot.PRESENT_NULL;
        return expected != null && value == expected ? JournalSlot.SAME : JournalSlot.OTHER;
    }
    @Override public final JournalSlot quantumchamber$inspectJournalSlot(SessionRecoveryState expected) {
        synchronized (quantumchamber$journalSlotFence) { return quantumchamber$peek(expected); }
    }
}
