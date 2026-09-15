package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent M1 registry of origin chamber anchors. */
public final class ChamberRegistryState extends PersistentState {
    public static final String STATE_ID = "quantumchamber_chambers";
    public static final PersistentState.Type<ChamberRegistryState> TYPE = new PersistentState.Type<>(
            ChamberRegistryState::new,
            ChamberRegistryState::fromNbt,
            DataFixTypes.LEVEL);

    private static final int SCHEMA_VERSION = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    private final ChamberRegistry registry;
    private final String loadError;

    public ChamberRegistryState() {
        this(null);
    }

    private ChamberRegistryState(String loadError) {
        this.loadError = loadError;
        this.registry = new ChamberRegistry(this::markDirty);
    }

    public static ChamberRegistryState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, STATE_ID);
    }

    public ChamberRegistry registry() {
        return registry;
    }

    public Optional<String> loadError() {
        return Optional.ofNullable(loadError);
    }

    public static ChamberRegistryState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        return fromNbt(nbt);
    }

    /** NBT codec entry point for tests and for the PersistentState deserializer. */
    public static ChamberRegistryState fromNbt(NbtCompound nbt) {
        int schemaVersion = nbt.getInt("SchemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            return failed("Unsupported chamber registry schema version: " + schemaVersion);
        }

        ChamberRegistryState state = new ChamberRegistryState();
        try {
            NbtList records = nbt.getList("Records", NbtElement.COMPOUND_TYPE);
            for (int index = 0; index < records.size(); index++) {
                ChamberRecord record = decodeRecord(records.getCompound(index));
                if (record.instanceKind() != ChamberInstanceKind.ORIGIN) {
                    throw new IllegalArgumentException("M1 registry cannot load non-origin chamber records");
                }
                state.registry.putLoaded(record);
            }
            return state;
        } catch (RuntimeException exception) {
            return failed("Unable to decode chamber registry schema v1: " + exception.getMessage());
        }
    }

    public NbtCompound writeNbt(NbtCompound nbt) {
        if (loadError != null) {
            throw new IllegalStateException("Refusing to overwrite unreadable chamber registry: " + loadError);
        }
        nbt.putInt("SchemaVersion", SCHEMA_VERSION);
        NbtList records = new NbtList();
        for (ChamberRecord record : registry.records().values()) {
            records.add(encodeRecord(record));
        }
        nbt.put("Records", records);
        return nbt;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        return writeNbt(nbt);
    }

    private static ChamberRegistryState failed(String error) {
        LOGGER.error(error);
        return new ChamberRegistryState(error);
    }

    private static NbtCompound encodeRecord(ChamberRecord record) {
        NbtCompound nbt = new NbtCompound();
        nbt.putUuid("ChamberUuid", record.chamberUuid());
        nbt.putString("OriginWorldKey", record.originWorldKey().toString());
        nbt.putString("OriginDimensionRole", record.originDimensionRole().name());
        nbt.putLong("AnchorPos", record.anchorPos().asLong());
        nbt.putString("Facing", record.facing().name());
        nbt.putString("InstanceKind", record.instanceKind().name());
        nbt.putBoolean("Enabled", record.enabled());
        nbt.putBoolean("Destroyed", record.destroyed());
        return nbt;
    }

    private static ChamberRecord decodeRecord(NbtCompound nbt) {
        if (!nbt.containsUuid("ChamberUuid")) {
            throw new IllegalArgumentException("ChamberUuid is missing");
        }
        Identifier worldKey = Identifier.tryParse(nbt.getString("OriginWorldKey"));
        if (worldKey == null) {
            throw new IllegalArgumentException("OriginWorldKey is invalid");
        }
        return new ChamberRecord(
                nbt.getUuid("ChamberUuid"),
                worldKey,
                DimensionRole.valueOf(nbt.getString("OriginDimensionRole")),
                BlockPos.fromLong(nbt.getLong("AnchorPos")),
                Direction.valueOf(nbt.getString("Facing")),
                ChamberInstanceKind.valueOf(nbt.getString("InstanceKind")),
                nbt.getBoolean("Enabled"),
                nbt.getBoolean("Destroyed"));
    }
}
