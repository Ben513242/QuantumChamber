package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Persistent M1 registry of origin chamber anchors. */
public final class ChamberRegistryState extends PersistentState {
    public static final String STATE_ID = "quantumchamber_chambers";
    public static final PersistentState.Type<ChamberRegistryState> TYPE = new PersistentState.Type<>(
            ChamberRegistryState::new,
            ChamberRegistryState::fromNbt,
            DataFixTypes.LEVEL);

    private static final int SCHEMA_VERSION = 2;
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

    public void requireHealthy() {
        if (loadError != null) {
            throw new IllegalStateException("Chamber registry is unreadable; restore or repair " + STATE_ID
                    + " before starting the server: " + loadError);
        }
    }

    public static ChamberRegistryState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        return fromNbt(nbt);
    }

    /** NBT codec entry point for tests and for the PersistentState deserializer. */
    public static ChamberRegistryState fromNbt(NbtCompound nbt) {
        if (!nbt.contains("SchemaVersion", NbtElement.INT_TYPE)) {
            return failed("Chamber registry SchemaVersion is missing or has the wrong NBT type");
        }
        int schemaVersion = nbt.getInt("SchemaVersion");
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            return failed("Unsupported chamber registry schema version: " + schemaVersion);
        }
        if (!nbt.contains("Records", NbtElement.LIST_TYPE)) {
            return failed("Chamber registry Records is missing or has the wrong NBT type");
        }
        NbtElement rawRecords = nbt.get("Records");
        if (!(rawRecords instanceof NbtList records)) {
            return failed("Chamber registry Records is not an NbtList");
        }

        ChamberRegistryState state = new ChamberRegistryState();
        try {
            if (!records.isEmpty() && records.getHeldType() != NbtElement.COMPOUND_TYPE) {
                throw new IllegalArgumentException("Records must contain compounds");
            }
            Set<UUID> chamberUuids = new HashSet<>();
            for (int index = 0; index < records.size(); index++) {
                if (records.get(index).getType() != NbtElement.COMPOUND_TYPE) {
                    throw new IllegalArgumentException("Records entry " + index + " is not a compound");
                }
                ChamberRecord record = decodeRecord(records.getCompound(index), schemaVersion);
                if (!chamberUuids.add(record.chamberUuid())) {
                    throw new IllegalArgumentException("Records contains duplicate ChamberUuid " + record.chamberUuid());
                }
                if (record.instanceKind() != ChamberInstanceKind.ORIGIN) {
                    throw new IllegalArgumentException("M1 registry cannot load non-origin chamber records");
                }
                state.registry.putLoaded(record);
            }
            return state;
        } catch (RuntimeException exception) {
            return failed("Unable to decode chamber registry schema v" + schemaVersion + ": " + exception.getMessage());
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
        nbt.putIntArray("AnchorPos", new int[] {
                record.anchorPos().getX(), record.anchorPos().getY(), record.anchorPos().getZ()});
        nbt.putString("Facing", record.facing().name());
        nbt.putString("InstanceKind", record.instanceKind().name());
        nbt.putBoolean("Enabled", record.enabled());
        nbt.putBoolean("Destroyed", record.destroyed());
        nbt.putString("PowerState", record.powerState().name());
        return nbt;
    }

    private static ChamberRecord decodeRecord(NbtCompound nbt, int schemaVersion) {
        if (!nbt.containsUuid("ChamberUuid")) {
            throw new IllegalArgumentException("ChamberUuid is missing or has the wrong NBT type");
        }
        requireType(nbt, "OriginWorldKey", NbtElement.STRING_TYPE);
        Identifier worldKey = Identifier.tryParse(nbt.getString("OriginWorldKey"));
        if (worldKey == null) {
            throw new IllegalArgumentException("OriginWorldKey is invalid");
        }

        requireType(nbt, "OriginDimensionRole", NbtElement.STRING_TYPE);
        DimensionRole role = parseEnum(DimensionRole.class, nbt.getString("OriginDimensionRole"), "OriginDimensionRole");
        if (!isVanillaWorldForRole(worldKey, role)) {
            throw new IllegalArgumentException("OriginWorldKey must be the vanilla world identifier for OriginDimensionRole");
        }

        requireType(nbt, "AnchorPos", NbtElement.INT_ARRAY_TYPE);
        int[] coordinates = nbt.getIntArray("AnchorPos");
        if (coordinates.length != 3) {
            throw new IllegalArgumentException("AnchorPos must contain exactly three coordinates");
        }

        requireType(nbt, "Facing", NbtElement.STRING_TYPE);
        Direction facing = parseEnum(Direction.class, nbt.getString("Facing"), "Facing");
        if (!isHorizontal(facing)) {
            throw new IllegalArgumentException("Facing must be horizontal");
        }

        requireType(nbt, "InstanceKind", NbtElement.STRING_TYPE);
        ChamberInstanceKind instanceKind = parseEnum(
                ChamberInstanceKind.class, nbt.getString("InstanceKind"), "InstanceKind");

        boolean enabled = requiredBoolean(nbt, "Enabled");
        boolean destroyed = requiredBoolean(nbt, "Destroyed");
        ChamberPowerState powerState = ChamberPowerState.UNKNOWN;
        if (schemaVersion == 2) {
            requireType(nbt, "PowerState", NbtElement.STRING_TYPE);
            powerState = parseEnum(ChamberPowerState.class, nbt.getString("PowerState"), "PowerState");
        }
        return new ChamberRecord(
                nbt.getUuid("ChamberUuid"),
                worldKey,
                role,
                new BlockPos(coordinates[0], coordinates[1], coordinates[2]),
                facing,
                instanceKind,
                enabled,
                destroyed,
                powerState);
    }

    private static void requireType(NbtCompound nbt, String key, byte type) {
        if (!nbt.contains(key, type)) {
            throw new IllegalArgumentException(key + " is missing or has the wrong NBT type");
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String key) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " has an invalid enum value", exception);
        }
    }

    private static boolean requiredBoolean(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.BYTE_TYPE);
        byte value = nbt.getByte(key);
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException(key + " must be encoded as boolean byte 0 or 1");
        }
        return value == 1;
    }

    private static boolean isHorizontal(Direction direction) {
        return direction == Direction.NORTH || direction == Direction.EAST
                || direction == Direction.SOUTH || direction == Direction.WEST;
    }

    private static boolean isVanillaWorldForRole(Identifier worldKey, DimensionRole role) {
        return switch (role) {
            case OVERWORLD -> World.OVERWORLD.getValue().equals(worldKey);
            case NETHER -> World.NETHER.getValue().equals(worldKey);
            case END -> World.END.getValue().equals(worldKey);
        };
    }
}
