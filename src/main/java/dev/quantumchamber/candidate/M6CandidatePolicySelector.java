package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.Minecraft121SettingsLifecycle;
import dev.quantumchamber.universe.UniverseLifecycleService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

/** 僅新入場選定已驗收的 policy；不接觸既有 session 的 frozen context。 */
public final class M6CandidatePolicySelector {
    private M6CandidatePolicySelector() {}

    public static M6CandidatePolicySelector forNewSession(MinecraftServer server) {
        if (!server.isOnThread() || !UniverseLifecycleService.ready(server))
            throw new IllegalStateException("M6 新 session 的 server 尚未 ready");
        Minecraft121SettingsLifecycle.acquire(server).requireValid();
        return new M6CandidatePolicySelector();
    }

    public CandidatePolicySnapshot freeze(long watermark, CandidateBytes fingerprint, SourceFamilyRef source) {
        return new CandidatePolicySnapshot(Identifier.of("quantumchamber:m6_profiles_v1"), 3, 2,
                5, 20, 75, watermark, 4096, fingerprint, source, 0);
    }
}
