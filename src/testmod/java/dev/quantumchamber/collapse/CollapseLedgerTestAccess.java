package dev.quantumchamber.collapse;

import java.util.List;
import net.minecraft.nbt.NbtCompound;

/**
 * 僅 testmod（M5 F6-2a）：讓跨 JVM 的塌縮 probe（{@code M5CollapseDiskEvidence}）以 production 的嚴格 decode 讀磁碟上的 allocation 與 projection ledger。
 * 兩個 {@code fromNbt} 是 package-private，所以放在同一個 package（split package，比照 {@code dev.quantumchamber.chamber.ChamberLoadSyncTestAccess}）。
 * root 與每一筆都必須恰為 exact keys、{@code SchemaVersion} 必須是支援的版本、筆數不超過上限；不符時丟 {@link IllegalArgumentException}（不會把格式不符讀成空的
 * ledger）。release 與 sources JAR 不含這個類別。
 */
public final class CollapseLedgerTestAccess {
    private CollapseLedgerTestAccess() {}

    /** allocation ledger 檔的 root（{@code SchemaVersion}、{@code Entries}）以 {@link UniverseAllocationLedger#fromNbt} 嚴格 decode。 */
    public static List<UniverseAllocationLedger.Entry> allocations(NbtCompound root) {
        return UniverseAllocationLedger.fromNbt(root).entries();
    }

    /** projection ledger 檔的 root（{@code SchemaVersion}、{@code Entries}）以 {@link ProjectionLedger#fromNbt} 嚴格 decode。 */
    public static List<ProjectionLedger.Entry> projections(NbtCompound root) {
        return ProjectionLedger.fromNbt(root).entries();
    }
}
