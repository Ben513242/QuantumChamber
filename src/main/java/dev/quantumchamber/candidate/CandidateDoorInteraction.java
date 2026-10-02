package dev.quantumchamber.candidate;

import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.DimensionRole;
import java.util.Optional;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/** 伺服器側門互動：辨識保護範圍後一律消耗，只有 checked SELECTED 才公告成功。 */
public final class CandidateDoorInteraction {
    /** spec §10.8「Nether／End 選門」。 */
    public static final String MESSAGE_DIMENSION_DOOR_UNAVAILABLE = "message.quantumchamber.m5.dimension_door_unavailable";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("quantumchamber");
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
            var sessionUuid = location.get().key().sessionUuid();
            boolean[] unsupportedDimension = {false};
            var outcome = pages.exclusiveOperation(sessionUuid, () -> {
                // guard 內重新定位，不能信任互動初始讀到的 physical owner。
                var latest = pages.candidateDoor(world, pos);
                if (!latest.equals(location)) return CandidateLedgerService.SelectionOutcome.REJECTED;
                var record = pages.candidateSelectionRecord(latest.orElseThrow(), player);
                if (record.isEmpty() || !sessions.candidateSourceAuthorized(server, record.get()))
                    return CandidateLedgerService.SelectionOutcome.REJECTED;
                // spec §11.3：來源不是 OVERWORLD 的新選擇一律拒絕，不寫 SELECTED；走廊照常可走。已是 MEASURED 的 M4 receipt 維持原行為。
                if (record.get().state() == SessionState.SUPERPOSITION
                        && record.get().candidateContext().orElseThrow().sourceFamilyRef().role() != DimensionRole.OVERWORLD) {
                    unsupportedDimension[0] = true;
                    return CandidateLedgerService.SelectionOutcome.REJECTED;
                }
                var result = ledger.trySelect(server, record.get().sessionUuid(), latest.orElseThrow().key(), player.getUuid(), world.getTime());
                if (result == CandidateLedgerService.SelectionOutcome.SELECTED) sessions.candidateMeasured(server, record.get().sessionUuid());
                return result;
            });
            if (outcome == CandidateLedgerService.SelectionOutcome.SELECTED) {
                player.sendMessage(Text.literal("量子候選已鎖定，等待塌縮"), true);
                return Optional.of(ActionResult.SUCCESS);
            }
            if (unsupportedDimension[0]) player.sendMessage(Text.translatable(MESSAGE_DIMENSION_DOOR_UNAVAILABLE), true);
            if (outcome == CandidateLedgerService.SelectionOutcome.ALREADY_SELECTED)
                player.sendMessage(Text.literal("候選已鎖定。"), true);
            if (outcome == CandidateLedgerService.SelectionOutcome.COMMIT_FAILED) failSession(pages, sessionUuid);
            return Optional.of(ActionResult.FAIL);
        } catch (RuntimeException rejected) {
            LOGGER.debug("側門互動拒絕：{}: {}", rejected.getClass().getName(), rejected.getMessage());
            return Optional.of(ActionResult.FAIL);
        }
    }

    /** guard 已釋放後同步標記 failure；不發任何成功訊息，也不依賴 refresh、斷線或重啟收斂。 */
    private static void failSession(CorridorPageManager pages, java.util.UUID sessionUuid) {
        LOGGER.warn("側門選擇 checked 提交失敗，session={} 以 flushed authority 標記 failure 並安全返還", sessionUuid);
        try { pages.failCandidateSession(sessionUuid, new IllegalStateException("候選選擇 checked 提交失敗")); }
        catch (RuntimeException failure) {
            LOGGER.error("候選選擇失敗後無法標記 session failure，session={}：{}: {}", sessionUuid,
                    failure.getClass().getName(), failure.getMessage());
        }
    }
}
