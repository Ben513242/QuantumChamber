package dev.quantumchamber.persistence;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import dev.quantumchamber.persistence.WindowsCheckpointNative.FileInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/** 從受信任 volume root 逐層持有普通目錄，封住 ancestor junction ABA，再確認同一 leaf HANDLE。 */
final class WindowsPlayerCheckpointVerifier {
    private final WindowsCheckpointNative io;

    WindowsPlayerCheckpointVerifier(WindowsCheckpointNative io) { this.io = Objects.requireNonNull(io, "io"); }

    void verify(Path path, NbtCompound expected) throws IOException {
        try {
            Path absolute = supportedPath(path);
            try (var owned = new OwnedHandles()) {
                Path cursor = absolute.getRoot();
                var pins = new ArrayList<Pin>();
                Pin root = pin(cursor, true, owned); pins.add(root);
                int volume = io.requireLocalNtfs(cursor);
                if (volume != root.info().volume()) throw new IOException("volume root 身分不符");
                for (int i = 0; i < absolute.getNameCount() - 1; i++) {
                    cursor = cursor.resolve(absolute.getName(i));
                    Pin ancestor = pin(cursor, true, owned);
                    if (ancestor.info().volume() != volume) throw new IOException("祖先目錄跨越未核准 volume");
                    pins.add(ancestor);
                }
                Pin leaf = pin(absolute, false, owned); pins.add(leaf);
                if (leaf.info().volume() != volume) throw new IOException("玩家檔跨越未核准 volume");
                long length = leaf.info().size();
                if (length < 1 || length > 64L * 1024 * 1024) throw new IOException("玩家 compressed 檔超出 64 MiB 邊界");
                checkNamespace(pins);
                byte[] before = io.read(leaf.handle(), (int) length);
                NbtCompound actual = NbtIo.readCompressed(new ByteArrayInputStream(before), NbtSizeTracker.ofUnlimitedBytes());
                if (!expected.equals(actual)) throw new IOException("正式玩家檔仍是舊資料或完整 NBT 不相等");
                checkNamespace(pins);
                io.flush(leaf.handle());
                checkNamespace(pins);
                byte[] after = io.read(leaf.handle(), (int) length);
                if (!Arrays.equals(before, after)) throw new IOException("FlushFileBuffers 後同 HANDLE 內容改變");
                checkNamespace(pins);
            }
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("Windows checkpoint 原生 API／NBT 無法安全驗證", exception);
        }
    }

    private static Path supportedPath(Path path) throws IOException {
        if (!Platform.isWindows() || path.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("checkpoint 僅支援 Windows 預設本機檔案系統");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null || !root.toString().matches("[A-Za-z]:\\\\") || absolute.getNameCount() == 0) {
            throw new IOException("checkpoint 不支援 UNC／遠端／未知 namespace");
        }
        for (Path part : absolute) {
            String value = part.toString();
            if (value.contains(":") || value.endsWith(".") || value.endsWith(" ")) throw new IOException("checkpoint 路徑含不明 stream 或正規化別名");
        }
        return absolute;
    }

    private Pin pin(Path path, boolean directory, OwnedHandles owned) throws IOException {
        HANDLE handle = directory ? io.openDirectory(path) : io.openLeaf(path);
        owned.handles.add(handle);
        FileInfo info = io.info(handle);
        ordinary(info, directory);
        if (!path.equals(io.finalPath(handle))) throw new IOException("opened HANDLE 路徑不符，拒絕別名或重導");
        return new Pin(path, handle, info, directory);
    }

    private void checkNamespace(List<Pin> pins) throws IOException {
        for (Pin pin : pins) {
            same(pin, io.info(pin.handle()));
            if (!pin.path().equals(io.finalPath(pin.handle()))) throw new IOException("held HANDLE 路徑已變動");
            // Probe 只讀 metadata 並分享全部存取，不解除任何既有 ancestor／leaf 鎖。
            try (var probe = new OwnedHandles()) {
                HANDLE handle = io.openProbe(pin.path()); probe.handles.add(handle);
                same(pin, io.info(handle));
                if (!pin.path().equals(io.finalPath(handle))) throw new IOException("正式 path probe 路徑不符");
            }
        }
    }

    private static void ordinary(FileInfo info, boolean directory) throws IOException {
        if ((info.attributes() & 0x400) != 0) throw new IOException("checkpoint 拒絕 reparse point");
        if (((info.attributes() & 0x10) != 0) != directory) throw new IOException("checkpoint 目錄／正式檔案型別不符");
        if (info.fileId() == 0) throw new IOException("未知 File ID");
    }

    private static void same(Pin pin, FileInfo now) throws IOException {
        ordinary(now, pin.directory());
        if (pin.info().volume() != now.volume() || pin.info().fileId() != now.fileId()) {
            throw new IOException("held HANDLE 與正式 path 的 native volume／File ID 不符");
        }
        if (!pin.directory() && pin.info().size() != now.size()) throw new IOException("同 HANDLE 玩家檔長度變更");
    }

    private record Pin(Path path, HANDLE handle, FileInfo info, boolean directory) {}

    private final class OwnedHandles implements AutoCloseable {
        private final List<HANDLE> handles = new ArrayList<>();
        @Override public void close() throws IOException {
            IOException failure = null;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try { io.close(handles.get(i)); }
                catch (IOException | RuntimeException | LinkageError exception) {
                    IOException checked = exception instanceof IOException ioFailure ? ioFailure
                            : new IOException("CloseHandle 原生呼叫異常，仍嘗試關閉其餘 HANDLE", exception);
                    if (failure == null) failure = checked; else failure.addSuppressed(checked);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
