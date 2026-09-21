package dev.quantumchamber.gametest;

import java.io.IOException;
import java.nio.file.Path;

/** 既有 M3 預設保持不變；M4 回歸只允許本 worktree 的精確 plan owner。 */
final class ProbeEvidenceOwner {
    private static final String M4_OWNER = ".superpowers/sdd/2026-09-21-m4-candidate-doors";

    private ProbeEvidenceOwner() { }

    static Path resolve(Path root, String prefix, String defaultOwner) throws IOException {
        Path worktree = root.getParent().getParent();
        String configured = System.getProperty(prefix + "evidenceOwner");
        Path owner;
        if (configured == null) {
            owner = worktree.resolve(defaultOwner);
        } else {
            owner = Path.of(configured);
            if (!owner.isAbsolute() || !owner.equals(worktree.resolve(M4_OWNER))) {
                throw new IllegalArgumentException("evidenceOwner 必須精確位於本 worktree 的 M4 plan");
            }
        }
        if (!owner.toRealPath().equals(owner)) {
            throw new IllegalArgumentException("evidenceOwner 必須是 canonical 原生路徑");
        }
        return owner;
    }
}
