package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Optional;

/** Origin 維護提交邊界；世界移除成功以前不解除紀錄與保護。 */
public final class ChamberLifecycleService {
    private final ChamberRegistry registry;

    public ChamberLifecycleService(ChamberRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public boolean setEnabled(UUID chamberUuid, boolean enabled) {
        return registry.setEnabled(chamberUuid, enabled);
    }

    public boolean setOriginEnabled(ChamberOriginAuthority authority, boolean enabled) {
        return resolveAuthorizedOrigin(authority)
                .map(record -> registry.setEnabled(record.chamberUuid(), enabled)).orElse(false);
    }

    public boolean dismantleOrigin(ChamberOriginAuthority authority, BooleanSupplier removeController) {
        Objects.requireNonNull(removeController, "removeController");
        ChamberRecord record = resolveAuthorizedOrigin(authority).orElse(null);
        if (record == null || record.enabled()) return false;
        if (!removeController.getAsBoolean()) return false;
        return registry.removeOrigin(record.chamberUuid());
    }

    private Optional<ChamberRecord> resolveAuthorizedOrigin(ChamberOriginAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        ChamberRecord record = registry.records().get(authority.chamberUuid());
        if (record == null || record.destroyed()
                || record.instanceKind() != ChamberInstanceKind.ORIGIN
                || authority.instanceKind() != ChamberInstanceKind.ORIGIN
                || !record.originWorldKey().equals(authority.worldKey())
                || record.originDimensionRole() != authority.role()
                || !record.anchorPos().equals(authority.controllerPos())
                || record.facing() != authority.facing()) return Optional.empty();
        return Optional.of(record);
    }
}
