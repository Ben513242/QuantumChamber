package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;

import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberPowerState;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;

/**
 * M7 T4A（spec v1.6 D、V15）：永久封存的最小解除路徑。艙的每一列都是已 checked 的永久原因 SEALED 時，原艙停用而且實際斷電，供電協調把 power state 設為 OFF
 * （比較器 0）並照既有流程核對，之後可沿既有拆除路徑移除 Controller；封存列與各 ledger 都不改寫。
 */
public final class M7SealReleaseGameTests implements FabricGameTest {
    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /**
     * 舊列永久封存（EXISTING_INELIGIBLE）：停用、斷電 → OFF、比較器 0、艙體可寫 → 原生 setter 移除 Controller、registry 不再有這座艙；封存列在 current、flushed 與磁碟上
     * 都不變，projection／allocation／discovery ledger 與 HOME 收據目錄不變；封存列仍標記這個 UUID busy。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_seal_release_dismantle", tickLimit = 600)
    public void permanent_sealed_origin_dismantles_after_disable_and_power_off(TestContext context) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = origin.frame;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var registry = ChamberRegistryState.get(server).registry();
            SessionRecoveryRecord sealed = M5GameTestSupport.sealedFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                    Map.of(UUID.randomUUID(), true), SealedReason.EXISTING_INELIGIBLE, origin.owned);
            var sid = sealed.sessionUuid();
            var before = Evidence.of(server, sid);
            witness.put("diskRowPresent", !before.row().equals(Evidence.ABSENT));
            context.assertTrue(new ChamberLifecycleService(registry).setEnabled(chamber, false), "管理員停用原艙");
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, registered + 3, hooks, () -> {
                var record = registry.records().get(chamber);
                var power = record == null ? "ABSENT" : record.powerState().name();
                int comparator = M7RearmGameTests.comparator(world, frame);
                boolean writable = ChamberProtectionService.get().mayMutate(world, frame.controllerPos().down(3));
                if (record == null || record.powerState() != ChamberPowerState.OFF || comparator != 0 || !writable) {
                    // 訊息先組好再收尾：收尾會以終局 API 移除封存列。
                    var row = journal.flushedRecords().get(sid);
                    var failure = new GameTestException("PERMANENT_SEAL_NOT_RELEASED：永久封存的原艙停用並斷電之後仍受保護：power=" + power + " comparator=" + comparator
                            + " 可寫=" + writable + " 封存列=" + (row == null ? "ABSENT" : row.state() + "/" + row.collapse().sealedReason()));
                    hooks.releaseAfter(failure);
                    throw failure;
                }
                witness.put("dismantledByTheNativeSetter", world.setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState())
                        && !world.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER));
                witness.put("registryForgotTheChamber", !registry.records().containsKey(chamber));
                witness.put("sealedRowUnchangedInMemory", sealed.equals(journal.flushedRecords().get(sid)) && sealed.equals(journal.records().get(sid)));
                var after = Evidence.of(server, sid);
                witness.put("sealedRowAndLedgersUnchangedOnDisk", before.equals(after));
                witness.put("uuidStillBusy", gate().globalChamberBusy(server, chamber));
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "永久封存停用斷電後可拆：" + witness + " before=" + before + " after=" + after);
                context.complete();
            });
        });
    }

    /**
     * 封存列與各 ledger 的磁碟證據：journal 檔中這個 SID 的列（SNBT 與 SHA-256），projection／allocation／discovery 等 {@code quantumchamber_} 資料檔的 SHA-256
     * （registry 與 journal 本身除外：registry 會因 OFF 與移除而改寫，journal 只比對這一列），以及 HOME 收據目錄的檔名。
     */
    record Evidence(String row, Map<String, String> files) {
        static final String ABSENT = "ABSENT";

        static Evidence of(MinecraftServer server, UUID sid) {
            var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
            var files = new TreeMap<String, String>();
            try (Stream<Path> list = Files.list(data)) {
                for (var file : list.sorted().toList()) {
                    var name = file.getFileName().toString();
                    if (Files.isDirectory(file)) {
                        if (name.equals("quantumchamber_home_receipts")) try (Stream<Path> receipts = Files.list(file)) {
                            files.put(name + "/", String.join(",", receipts.map(entry -> entry.getFileName().toString()).sorted().toList()));
                        }
                        continue;
                    }
                    if (!name.startsWith("quantumchamber_") || name.equals("quantumchamber_chambers.dat") || name.equals(SessionRecoveryState.STATE_ID + ".dat")) continue;
                    files.put(name, sha256(Files.readAllBytes(file)));
                }
                return new Evidence(diskRow(data.resolve(SessionRecoveryState.STATE_ID + ".dat"), sid), files);
            } catch (IOException failure) { throw new IllegalStateException("讀不到磁碟證據", failure); }
        }

        private static String diskRow(Path journal, UUID sid) throws IOException {
            var rows = NbtIo.readCompressed(journal, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data").getList("Records", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.getCompound(i);
                if (row.containsUuid("SessionUuid") && row.getUuid("SessionUuid").equals(sid)) return row + " sha256=" + sha256(bytes(row));
            }
            return ABSENT;
        }

        private static byte[] bytes(NbtCompound row) throws IOException {
            var out = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(out)) { NbtIo.write(row, data); }
            return out.toByteArray();
        }

        private static String sha256(byte[] bytes) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
