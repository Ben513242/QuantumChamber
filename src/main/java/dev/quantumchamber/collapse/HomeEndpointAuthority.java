package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.*;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 以當下的投影、宇宙與原艙登記核對回程兩端。查詢不建立世界或區塊。 */
public final class HomeEndpointAuthority {
    public enum EntryStatus { VALID, UNKNOWN, ENTRY_AUTHORITY_INVALID, AUTHORITY_CORRUPT, AUTHORITY_UNREADABLE }
    public enum RootStatus { READY, UNKNOWN, LOW, DISABLED, INVALID_STRUCTURE, DESTROYED, IDENTITY_MISMATCH }

    private HomeEndpointAuthority() {}

    public static Optional<EntryEndpoint> resolveEntry(ServerWorld world, ChamberControllerBlockEntity controller) {
        var server = world.getServer();
        if (!server.isOnThread() || server.getWorld(world.getRegistryKey()) != world || controller.getWorld() != world
                || controller.isRemoved() || controller.instanceKind() != ChamberInstanceKind.PROJECTION) return Optional.empty();
        try {
            var chunk = world.getChunkManager().getWorldChunk(controller.getPos().getX() >> 4, controller.getPos().getZ() >> 4);
            if (chunk == null || chunk.getBlockEntity(controller.getPos()) != controller) return Optional.empty();
            var site = ProjectionProtectionIndex.get(server).uniqueSite(world.getRegistryKey(),controller.getPos());
            if (site.isEmpty()) return Optional.empty();
            var row = site.get();
            var endpoint = new EntryEndpoint(row.chamberUuid(),row.universeId(),world.getRegistryKey().getValue(),
                    row.role(),row.controllerPos(),row.outwardFacing(),ChamberInstanceKind.PROJECTION);
            return inspectEntry(server,endpoint) == EntryStatus.VALID ? Optional.of(endpoint) : Optional.empty();
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }

    public static EntryStatus inspectEntry(MinecraftServer server, EntryEndpoint endpoint) {
        if (!server.isOnThread()) throw new IllegalStateException("必須在 server thread 核對投影");
        try {
            var index = ProjectionProtectionIndex.get(server);
            var degradation = index.degradation();
            if (degradation.isPresent()) return degradation.get() == SealedReason.AUTHORITY_CORRUPT
                    ? EntryStatus.AUTHORITY_CORRUPT : EntryStatus.AUTHORITY_UNREADABLE;
            var authority = CollapseAuthorities.get(server);
            if (authority.unconfirmed(CollapseAuthorities.Authority.PROJECTION_LEDGER)
                    || authority.unconfirmed(CollapseAuthorities.Authority.CATALOG)) return EntryStatus.AUTHORITY_UNREADABLE;
            var key = RegistryKey.of(RegistryKeys.WORLD,endpoint.worldKey());
            var expected = new ProjectionLedger.Entry(endpoint.chamberUuid(),endpoint.universeId(),DimensionRole.OVERWORLD,
                    endpoint.controllerPos(),endpoint.facing(),ProjectionLedger.Entry.Status.COMPLETE);
            if (index.uniqueSite(key,endpoint.controllerPos()).filter(expected::equals).isEmpty()) return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var ledger = authority.checkedProjection(endpoint.chamberUuid(),endpoint.universeId());
            if (!ledger.succeeded()) return failure(ledger);
            if (ledger.value().filter(expected::equals).isEmpty()) return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var catalog = authority.catalogRecord(endpoint.universeId());
            if (!catalog.succeeded()) return failure(catalog);
            var record = catalog.value().orElse(null);
            if (record == null || record.desiredAvailability() != DesiredAvailability.EAGER_ENABLED) return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
            if (descriptor == null || !descriptor.worldKey().equals(key)) return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var world = server.getWorld(key);
            if (world == null) return EntryStatus.UNKNOWN;
            if (UniverseLifecycleService.resolveActive(server,endpoint.universeId()).orElse(null) != world)
                return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var root = new ChamberOriginAuthority(endpoint.chamberUuid(),World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                    endpoint.controllerPos(),endpoint.facing(),ChamberInstanceKind.ORIGIN);
            var registered = registered(server,root);
            if (registered == null || registered.destroyed() || !matches(registered,root)) return EntryStatus.ENTRY_AUTHORITY_INVALID;
            var frame = new ChamberFrame(endpoint.controllerPos(),endpoint.facing());
            if (!PassageTransferPolicy.homeChunks(frame).stream().allMatch(c -> world.getChunkManager().getWorldChunk(c.x,c.z) != null))
                return EntryStatus.UNKNOWN;
            var controller = actualController(world,frame);
            if (controller == null || controller.instanceKind() != ChamberInstanceKind.PROJECTION
                    || controller.chamberUuid()!=null && !controller.chamberUuid().equals(endpoint.chamberUuid()))
                return EntryStatus.ENTRY_AUTHORITY_INVALID;
            return new ChamberDetector().validate(new WorldChamberBlockView(world),frame).valid()
                    ? EntryStatus.VALID : EntryStatus.ENTRY_AUTHORITY_INVALID;
        } catch (RuntimeException unreadable) { return EntryStatus.AUTHORITY_UNREADABLE; }
    }

    private static EntryStatus failure(CollapseAuthorities.Lookup<?> lookup) {
        return lookup.status() == CollapseAuthorities.Lookup.Status.CORRUPT ? EntryStatus.AUTHORITY_CORRUPT : EntryStatus.AUTHORITY_UNREADABLE;
    }

    public static RootStatus checkRoot(MinecraftServer server, ChamberOriginAuthority root, boolean requirePower) {
        if (!server.isOnThread()) throw new IllegalStateException("必須在 server thread 核對原艙");
        if (!canonical(root)) return RootStatus.IDENTITY_MISMATCH;
        try {
            var record = registered(server,root);
            if (record == null || record.destroyed()) return RootStatus.DESTROYED;
            if (!matches(record,root)) return RootStatus.IDENTITY_MISMATCH;
            var world = server.getOverworld();
            var frame = new ChamberFrame(root.controllerPos(), root.facing());
            if (world == null || !PassageTransferPolicy.homeChunks(frame).stream()
                    .allMatch(c -> world.getChunkManager().getWorldChunk(c.x,c.z) != null)) return RootStatus.UNKNOWN;
            var controller = actualController(world,frame);
            if (controller == null || controller.instanceKind() != ChamberInstanceKind.ORIGIN
                    || !root.chamberUuid().equals(controller.chamberUuid())) return RootStatus.IDENTITY_MISMATCH;
            if (!record.enabled()) return RootStatus.DISABLED;
            if (!new ChamberDetector().validate(new WorldChamberBlockView(world),frame).valid()) return RootStatus.INVALID_STRUCTURE;
            return requirePower && !world.isReceivingRedstonePower(root.controllerPos()) ? RootStatus.LOW : RootStatus.READY;
        } catch (RuntimeException unreadable) { return RootStatus.UNKNOWN; }
    }

    static boolean canonical(ChamberOriginAuthority root) {
        return root.worldKey().equals(World.OVERWORLD.getValue()) && root.role()==DimensionRole.OVERWORLD
                && root.instanceKind()==ChamberInstanceKind.ORIGIN && root.facing().getAxis().isHorizontal();
    }

    static boolean registeredRoot(MinecraftServer server,ChamberOriginAuthority root) {
        try {
            var row=registered(server,root);
            return row!=null && !row.destroyed() && matches(row,root);
        } catch (RuntimeException unreadable) { return false; }
    }
    private static ChamberRecord registered(MinecraftServer server, ChamberOriginAuthority root) {
        var state = ChamberRegistryState.get(server);
        state.requireHealthy();
        return state.registry().records().get(root.chamberUuid());
    }

    private static boolean matches(ChamberRecord record, ChamberOriginAuthority root) {
        return record.chamberUuid().equals(root.chamberUuid()) && record.originWorldKey().equals(root.worldKey())
                && record.originDimensionRole()==root.role() && record.instanceKind()==root.instanceKind()
                && record.anchorPos().equals(root.controllerPos()) && record.facing()==root.facing();
    }

    private static ChamberControllerBlockEntity actualController(ServerWorld world, ChamberFrame frame) {
        BlockPos pos = frame.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4,pos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld()!=world || !controller.getPos().equals(pos)) return null;
        var block = chunk.getBlockState(pos);
        return block.isOf(ModBlocks.CHAMBER_CONTROLLER) && block.equals(controller.getCachedState())
                && block.get(ChamberControllerBlock.FACING)==frame.outwardFacing() ? controller : null;
    }
}
