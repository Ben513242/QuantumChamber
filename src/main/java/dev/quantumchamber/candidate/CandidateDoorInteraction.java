package dev.quantumchamber.candidate;

import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.Optional;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/** 伺服器側門互動：辨識保護範圍後一律消耗，只有 checked SELECTED 才公告成功。 */
public final class CandidateDoorInteraction {
    private final CandidateLedgerService ledger = new CandidateLedgerService();

    public Optional<ActionResult> onBulkheadUse(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        if (!world.getRegistryKey().equals(SuperpositionWorld.KEY)) return Optional.empty();
        var server = world.getServer();
        if (!server.isOnThread()) return Optional.of(ActionResult.FAIL);
        try {
            var pages = CorridorPageManager.forServer(server);
            var location = pages.candidateDoor(world, pos);
            if (location.isEmpty()) return Optional.empty();
            if (!(ChamberSessions.gateway() instanceof SuperpositionSessionManager sessions)) return Optional.of(ActionResult.FAIL);
            var outcome = pages.exclusiveOperation(location.get().key().sessionUuid(), () -> {
                // guard 內重新定位，不能信任互動初始讀到的 physical owner。
                var latest = pages.candidateDoor(world, pos);
                if (!latest.equals(location)) return CandidateLedgerService.SelectionOutcome.REJECTED;
                var record = pages.candidateSelectionRecord(latest.orElseThrow(), player);
                if (record.isEmpty() || !sessions.candidateSourceAuthorized(server, record.get()))
                    return CandidateLedgerService.SelectionOutcome.REJECTED;
                var result = ledger.trySelect(server, record.get().sessionUuid(), latest.orElseThrow().key(), player.getUuid(), world.getTime());
                if (result == CandidateLedgerService.SelectionOutcome.SELECTED) sessions.candidateMeasured(server, record.get().sessionUuid());
                return result;
            });
            if (outcome == CandidateLedgerService.SelectionOutcome.SELECTED) {
                player.sendMessage(Text.literal("量子候選已鎖定，等待塌縮"), true);
                return Optional.of(ActionResult.SUCCESS);
            }
            if (outcome == CandidateLedgerService.SelectionOutcome.ALREADY_SELECTED)
                player.sendMessage(Text.literal("候選已鎖定。"), true);
            return Optional.of(ActionResult.FAIL);
        } catch (RuntimeException rejected) {
            return Optional.of(ActionResult.FAIL);
        }
    }
}
