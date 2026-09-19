package dev.quantumchamber.chamber;

import dev.quantumchamber.chamber.ChamberLoadSyncOutcome.Receipt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** 僅 testmod 使用，觀察 queue 是否真的釋放原 BE 與 server 項目。 */
public final class ChamberLoadSyncTestAccess {
    private ChamberLoadSyncTestAccess() {}

    public static Observation observe(ServerWorld world, ChamberControllerBlockEntity controller) {
        return observe(world, controller, ignored -> {});
    }

    public static Observation observe(ServerWorld world, ChamberControllerBlockEntity controller,
                                      Consumer<Receipt> callback) {
        return new Observation(world, controller, callback);
    }

    public static void discardWorld(ServerWorld expected) {
        ChamberControllerLoadSyncQueue.discardWorld(expected);
    }

    /** 測試才保留歷史；讀取或關閉時必須將 callback 故障轉成測試失敗。 */
    public static final class Observation implements AutoCloseable {
        private final List<Receipt> receipts = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final AutoCloseable registration;

        private Observation(ServerWorld world, ChamberControllerBlockEntity controller, Consumer<Receipt> callback) {
            registration = ChamberControllerLoadSyncQueue.observe(world.getServer(), receipt -> {
                if (receipt.controller() != controller) return;
                try {
                    if (receipt.server() != world.getServer() || receipt.world() != world
                            || !receipt.pos().equals(controller.getPos())) {
                        throw new AssertionError("Load-sync receipt 的 exact server/world/controller/pos 不符");
                    }
                    receipts.add(receipt);
                    callback.accept(receipt);
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                    throw failure;
                }
            });
        }

        public List<ChamberLoadSyncOutcome> outcomes() {
            assertHealthy();
            return receipts.stream().map(Receipt::outcome).toList();
        }

        public void assertHealthy() {
            if (!failures.isEmpty()) throw new AssertionError("Load-sync observer callback 失敗", failures.getFirst());
        }

        @Override
        public void close() {
            try {
                registration.close();
            } catch (Exception failure) {
                throw new AssertionError("Load-sync observer 無法關閉", failure);
            }
            assertHealthy();
        }
    }

    public static boolean hasPending(MinecraftServer server, ChamberControllerBlockEntity controller) {
        return ChamberControllerLoadSyncQueue.hasPending(server, controller);
    }

    public static int pendingCount(MinecraftServer server) {
        return ChamberControllerLoadSyncQueue.pendingCount(server);
    }
}
