package dev.quantumchamber.persistence;

import static dev.quantumchamber.persistence.JournalNbt.*;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

/** schema 3／4 journal payload（候選 context、ledger、selection 與 schema 4 的 Collapse）的局部 strict codec；不執行 ledger service 或狀態遷移。 */
final class CandidateJournalCodec {
    private CandidateJournalCodec() {}

    /** schema 4 的 Collapse：M4 狀態為空 compound，塌縮狀態恰有 spec §5 表列的 8 個 key。 */
    static NbtCompound collapse(CollapseFields collapse) {
        var nbt = new NbtCompound();
        if (collapse.isEmpty()) return nbt;
        nbt.putInt("CollapseRevision", collapse.revision()); nbt.putLong("CommittedAtGameTime", collapse.committedAtGameTime());
        var destination = new NbtCompound();
        switch (collapse.destination()) {
            case CollapseDestination.Source ignored -> destination.putString("Kind", "SOURCE");
            case CollapseDestination.Universe universe -> {
                destination.putString("Kind", "UNIVERSE"); destination.putUuid("UniverseId", universe.id().value());
            }
        }
        nbt.put("Destination", destination);
        nbt.putLong("PassageOpenedAtGameTime", collapse.passageOpenedAtGameTime());
        nbt.putLong("PassageExpiresAtGameTime", collapse.passageExpiresAtGameTime());
        nbt.put("Crossed", uuids(collapse.crossed())); nbt.put("RecoveryRequired", uuids(collapse.recoveryRequired()));
        nbt.putString("SealedReason", collapse.sealedReason().map(Enum::name).orElse(""));
        return nbt;
    }

    static CollapseFields collapse(NbtCompound nbt) {
        if (nbt.isEmpty()) return CollapseFields.EMPTY;
        keys("Collapse", nbt, "CollapseRevision", "CommittedAtGameTime", "Destination", "PassageOpenedAtGameTime",
                "PassageExpiresAtGameTime", "Crossed", "RecoveryRequired", "SealedReason");
        var raw = compound(nbt, "Destination");
        CollapseDestination destination = switch (string(raw, "Kind")) {
            case "SOURCE" -> { keys("Collapse.Destination", raw, "Kind"); yield new CollapseDestination.Source(); }
            case "UNIVERSE" -> {
                keys("Collapse.Destination", raw, "Kind", "UniverseId");
                yield new CollapseDestination.Universe(UniverseId.of(uuid(raw, "UniverseId")));
            }
            default -> throw new IllegalArgumentException("未知 Destination variant");
        };
        var reason = string(nbt, "SealedReason");
        return new CollapseFields(integer(nbt, "CollapseRevision"), longValue(nbt, "CommittedAtGameTime"), destination,
                longValue(nbt, "PassageOpenedAtGameTime"), longValue(nbt, "PassageExpiresAtGameTime"), uuids(nbt, "Crossed"),
                uuids(nbt, "RecoveryRequired"), reason.isEmpty() ? Optional.empty() : Optional.of(SealedReason.valueOf(reason)));
    }

    /** UUID 清單以 int array（與 putUuid 相同的 4 個 int）編碼；空清單沿用 END held type。 */
    private static NbtList uuids(List<UUID> values) {
        var list = new NbtList(); values.forEach(value -> list.add(NbtHelper.fromUuid(value))); return list;
    }
    private static List<UUID> uuids(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.LIST_TYPE);
        var list = (NbtList) nbt.get(key);
        if (list.isEmpty()) {
            if (list.getHeldType() != NbtElement.END_TYPE) throw new IllegalArgumentException(key + " 的空 list 必須使用 END held type");
            return List.of();
        }
        if (list.getHeldType() != NbtElement.INT_ARRAY_TYPE) throw new IllegalArgumentException(key + " 必須包含 UUID int array");
        var result = new ArrayList<UUID>();
        for (var element : list) {
            int[] value = ((NbtIntArray) element).getIntArray();
            if (value.length != 4) throw new IllegalArgumentException(key + " 的 UUID 必須是 4 個 int");
            result.add(Uuids.toUuid(value));
        }
        return result;
    }

    static NbtCompound context(CandidatePolicySnapshot context) {
        var nbt = new NbtCompound(); nbt.putString("PolicyId", context.policyId().toString());
        nbt.putInt("PolicyVersion", context.policyVersion()); nbt.putInt("CandidateSchemaVersion", context.candidateSchemaVersion());
        nbt.putInt("SourceWeight", context.sourceWeight()); nbt.putInt("DiscoveredWeight", context.discoveredWeight());
        nbt.putInt("NewWeight", context.newWeight()); nbt.putLong("DiscoveryWatermark", context.discoveryWatermark());
        nbt.putInt("MaxCandidateEntries", context.maxCandidateEntries()); nbt.putByteArray("EntropyFingerprint", context.entropyFingerprint().bytes());
        var source = new NbtCompound(); source.putString("Role", context.sourceFamilyRef().role().name());
        switch (context.sourceFamilyRef()) {
            case SourceFamilyRef.Vanilla vanilla -> {
                source.putString("Kind", "VANILLA"); source.putString("WorldKey", vanilla.worldKey().getValue().toString());
            }
            case SourceFamilyRef.Catalog catalog -> {
                source.putString("Kind", "CATALOG"); source.putUuid("UniverseId", catalog.universeId().value());
            }
        }
        nbt.put("SourceFamilyRef", source); return nbt;
    }

    static CandidatePolicySnapshot context(NbtCompound nbt) {
        keys(nbt, "PolicyId", "PolicyVersion", "CandidateSchemaVersion", "SourceWeight", "DiscoveredWeight", "NewWeight",
                "DiscoveryWatermark", "MaxCandidateEntries", "EntropyFingerprint", "SourceFamilyRef");
        var source = compound(nbt, "SourceFamilyRef");
        var role = DimensionRole.valueOf(string(source, "Role"));
        SourceFamilyRef ref = switch (string(source, "Kind")) {
            case "VANILLA" -> {
                keys(source, "Kind", "Role", "WorldKey");
                yield new SourceFamilyRef.Vanilla(RegistryKey.of(RegistryKeys.WORLD, canonicalIdentifier("SourceFamily", source, "WorldKey")), role);
            }
            case "CATALOG" -> {
                keys(source, "Kind", "Role", "UniverseId");
                yield new SourceFamilyRef.Catalog(UniverseId.of(uuid(source, "UniverseId")), role);
            }
            default -> throw new IllegalArgumentException("未知 SourceFamilyRef variant");
        };
        return new CandidatePolicySnapshot(Identifier.of(string(nbt, "PolicyId")), integer(nbt, "PolicyVersion"),
                integer(nbt, "CandidateSchemaVersion"), integer(nbt, "SourceWeight"), integer(nbt, "DiscoveredWeight"),
                integer(nbt, "NewWeight"), longValue(nbt, "DiscoveryWatermark"), integer(nbt, "MaxCandidateEntries"),
                bytes(nbt, "EntropyFingerprint"), ref);
    }

    static NbtCompound entry(CandidateLedgerEntry entry) {
        var nbt = new NbtCompound(); nbt.put("DoorKey", door(entry.doorKey()));
        var candidate = new NbtCompound(); candidate.putByteArray("CandidateId", entry.candidate().candidateId().value().bytes());
        switch (entry.candidate()) {
            case QuantumCandidate.Source ignored -> candidate.putString("Kind", "SOURCE");
            case QuantumCandidate.Existing existing -> {
                candidate.putString("Kind", "EXISTING"); candidate.putUuid("UniverseId", existing.universeId().value());
            }
            case QuantumCandidate.New fresh -> {
                candidate.putString("Kind", "NEW"); var intent = new NbtCompound();
                intent.putByteArray("AllocationToken", fresh.intent().allocationToken().bytes());
                intent.putString("Profile", fresh.intent().profile().name()); intent.putInt("ProfileVersion", fresh.intent().profileVersion());
                intent.putByteArray("GenerationSeedMaterial", fresh.intent().generationSeedMaterial().bytes()); candidate.put("Intent", intent);
            }
        }
        nbt.put("Candidate", candidate); return nbt;
    }

    static CandidateLedgerEntry entry(NbtCompound nbt) {
        keys(nbt, "DoorKey", "Candidate"); var candidate = compound(nbt, "Candidate");
        var id = new CandidateId(bytes(candidate, "CandidateId"));
        QuantumCandidate value = switch (string(candidate, "Kind")) {
            case "SOURCE" -> {
                keys(candidate, "Kind", "CandidateId"); yield new QuantumCandidate.Source(id);
            }
            case "EXISTING" -> {
                keys(candidate, "Kind", "CandidateId", "UniverseId");
                yield new QuantumCandidate.Existing(id, UniverseId.of(uuid(candidate, "UniverseId")));
            }
            case "NEW" -> {
                keys(candidate, "Kind", "CandidateId", "Intent"); var intent = compound(candidate, "Intent");
                keys(intent, "AllocationToken", "Profile", "ProfileVersion", "GenerationSeedMaterial");
                yield new QuantumCandidate.New(id, new NewUniverseIntent(bytes(intent, "AllocationToken"),
                        GeneratorProfile.valueOf(string(intent, "Profile")), integer(intent, "ProfileVersion"), bytes(intent, "GenerationSeedMaterial")));
            }
            default -> throw new IllegalArgumentException("未知 candidate variant");
        };
        return new CandidateLedgerEntry(door(compound(nbt, "DoorKey")), value);
    }

    static NbtCompound selection(CandidateSelection selection) {
        var nbt = new NbtCompound();
        switch (selection) {
            case CandidateSelection.Selectable ignored -> nbt.putString("Kind", "SELECTABLE");
            case CandidateSelection.Selected selected -> {
                nbt.putString("Kind", "SELECTED"); nbt.put("DoorKey", door(selected.doorKey()));
                nbt.putByteArray("CandidateId", selected.candidateId().value().bytes()); nbt.putUuid("SelectedBy", selected.selectedBy());
                nbt.putLong("SelectedAtGameTime", selected.selectedAtGameTime()); nbt.putInt("SelectionRevision", selected.selectionRevision());
            }
        }
        return nbt;
    }

    static CandidateSelection selection(NbtCompound nbt) {
        return switch (string(nbt, "Kind")) {
            case "SELECTABLE" -> { keys(nbt, "Kind"); yield new CandidateSelection.Selectable(); }
            case "SELECTED" -> {
                keys(nbt, "Kind", "DoorKey", "CandidateId", "SelectedBy", "SelectedAtGameTime", "SelectionRevision");
                yield new CandidateSelection.Selected(door(compound(nbt, "DoorKey")), new CandidateId(bytes(nbt, "CandidateId")),
                        uuid(nbt, "SelectedBy"), longValue(nbt, "SelectedAtGameTime"), integer(nbt, "SelectionRevision"));
            }
            default -> throw new IllegalArgumentException("未知 selection variant");
        };
    }

    private static NbtCompound door(DoorKey door) {
        var nbt = new NbtCompound(); nbt.putUuid("SessionUuid", door.sessionUuid());
        nbt.putLong("LogicalStationIndex", door.logicalStationIndex()); nbt.putString("WallSide", door.wallSide().name()); return nbt;
    }
    private static DoorKey door(NbtCompound nbt) {
        keys(nbt, "SessionUuid", "LogicalStationIndex", "WallSide");
        return new DoorKey(uuid(nbt, "SessionUuid"), longValue(nbt, "LogicalStationIndex"), DoorKey.DoorWallSide.valueOf(string(nbt, "WallSide")));
    }
    private static int integer(NbtCompound nbt, String key) { requireType(nbt, key, NbtElement.INT_TYPE); return nbt.getInt(key); }
    private static long longValue(NbtCompound nbt, String key) { requireType(nbt, key, NbtElement.LONG_TYPE); return nbt.getLong(key); }
    private static CandidateBytes bytes(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.BYTE_ARRAY_TYPE); return new CandidateBytes(nbt.getByteArray(key));
    }
}
