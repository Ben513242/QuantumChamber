package dev.quantumchamber.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 預設關閉的 own-server 平台路由：只讓 production PlayerCheckpointStore 的平台判斷在本 server thread 回報「非 Windows」，
 * 用於在 Windows 本機重現不支援平台；Ubuntu 上真實平台本來即為非 Windows。release JAR 不含此 fixture。
 */
public final class CheckpointPlatformFault implements AutoCloseable {
    private static volatile CheckpointPlatformFault active;
    static { ServerLifecycleEvents.SERVER_STOPPED.register(server -> { var fault=active; if(fault!=null && fault.server==server) active=null; }); }
    private final MinecraftServer server;
    private int consulted;

    private CheckpointPlatformFault(MinecraftServer server) { this.server=server; }
    static CheckpointPlatformFault simulateUnsupported(MinecraftServer server) {
        if(!server.isOnThread()) throw new IllegalStateException("平台模擬必須在 own server thread 安裝");
        var fault=new CheckpointPlatformFault(server);
        synchronized(CheckpointPlatformFault.class) {
            if(active!=null) throw new IllegalStateException("平台模擬不可重複安裝");
            active=fault;
        }
        return fault;
    }
    /** production 平台判斷的 testmod 路由；只在安裝者的 server thread 生效。 */
    public static boolean platformUnsupported() {
        var fault=active;
        if(fault==null || !fault.server.isOnThread()) return false;
        fault.consulted++;
        return true;
    }
    int consulted() { return consulted; }
    @Override public void close() { synchronized(CheckpointPlatformFault.class) { if(active==this) active=null; } }
}
