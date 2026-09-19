package dev.quantumchamber.universe;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/** 唯一負責 durable catalog authority 檢查的 materialization 入口。 */
public final class UniverseMaterializationService {
    private final DynamicDimensionBackend backend;

    public UniverseMaterializationService(DynamicDimensionBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public MaterializeResult materializeChecked(MinecraftServer server, UniverseRecord record,
            UniverseWorldDescriptor descriptor) {
        if (!server.isOnThread()) {
            throw new IllegalStateException("Universe materialization 必須在伺服器執行緒操作");
        }
        return materializeChecked(UniverseRegistryState.get(server), record, descriptor,
                () -> backend.materialize(server, descriptor),
                new MaterializeResult(MaterializeResult.Status.REJECTED, null));
    }

    static <R> R materializeChecked(UniverseRegistryState catalog, UniverseRecord record,
            UniverseWorldDescriptor descriptor, Supplier<R> materializer, R rejected) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(materializer, "materializer");
        var flushed = catalog.flushedRecords().get(record.definition().universeId());
        if (!record.equals(flushed)
                || record.desiredAvailability() != DesiredAvailability.EAGER_ENABLED
                || !descriptor.equals(record.definition().worlds().get(descriptor.role()))) {
            return rejected;
        }
        return materializer.get();
    }
}
