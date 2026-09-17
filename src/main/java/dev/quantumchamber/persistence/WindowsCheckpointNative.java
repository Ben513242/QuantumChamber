package dev.quantumchamber.persistence;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Path;

/** 只包裝 checkpoint 所需 Win32 呼叫，重用 Minecraft 內附的 JNA 5.14.0。 */
class WindowsCheckpointNative {
    private static final Kernel32 KERNEL = Kernel32.INSTANCE;
    private static final FileApi FILE = Native.load("kernel32", FileApi.class);
    private static final int READ_ATTRIBUTES = 0x80;
    private static final int OPEN_REPARSE_POINT = 0x00200000;
    private static final int BACKUP_SEMANTICS = 0x02000000;

    /** JNA 5.14.0 未宣告的三個 Win32 函式；DWORD/LONG 都是 32 位元。 */
    public interface FileApi extends StdCallLibrary {
        boolean GetFileInformationByHandle(HANDLE handle, HandleInformation information);
        int GetFinalPathNameByHandleW(HANDLE handle, char[] path, int length, int flags);
        int SetFilePointer(HANDLE handle, int distance, IntByReference high, int method);
    }

    /** Microsoft BY_HANDLE_FILE_INFORMATION，固定 52 bytes；沒有 Java 路徑屬性替代品。 */
    @Structure.FieldOrder({"attributes", "creation", "access", "write", "volume", "sizeHigh", "sizeLow", "links", "indexHigh", "indexLow"})
    public static class HandleInformation extends Structure {
        public int attributes;
        public WinBase.FILETIME creation, access, write;
        public int volume, sizeHigh, sizeLow, links, indexHigh, indexLow;
    }

    record FileInfo(int attributes, int volume, long fileId, long size) {}

    int requireLocalNtfs(Path root) throws IOException {
        if (KERNEL.GetDriveType(root.toString()) != 3) throw new IOException("checkpoint 僅支援本機固定磁碟");
        var volume = new IntByReference(); var maximum = new IntByReference(); var flags = new IntByReference();
        char[] filesystem = new char[64];
        if (!KERNEL.GetVolumeInformation(root.toString(), null, 0, volume, maximum, flags, filesystem, filesystem.length)) {
            throw failed("GetVolumeInformation");
        }
        if (!"NTFS".equals(Native.toString(filesystem))) throw new IOException("checkpoint 僅驗證本機 NTFS；其他檔案系統保守拒絕");
        return volume.getValue();
    }

    HANDLE openDirectory(Path path) throws IOException { return open(path, READ_ATTRIBUTES, 1, true); }
    HANDLE openLeaf(Path path) throws IOException { return open(path, 0xC0000000, 1, false); }
    HANDLE openProbe(Path path) throws IOException { return open(path, READ_ATTRIBUTES, 7, true); }

    private HANDLE open(Path path, int access, int sharing, boolean directory) throws IOException {
        // 僅 OPEN_EXISTING，不建立／截斷。正式檔與祖先只分享讀取，拒絕寫入及刪除分享。
        HANDLE handle = KERNEL.CreateFile("\\\\?\\" + path, access, sharing, null, 3,
                OPEN_REPARSE_POINT | (directory ? BACKUP_SEMANTICS : 0), null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw failed("CreateFile " + path);
        return handle;
    }

    FileInfo info(HANDLE handle) throws IOException {
        var raw = new HandleInformation();
        if (raw.size() != 52) throw new IOException("BY_HANDLE_FILE_INFORMATION ABI 不符");
        if (!FILE.GetFileInformationByHandle(handle, raw)) throw failed("GetFileInformationByHandle");
        long id = ((long) raw.indexHigh << 32) | Integer.toUnsignedLong(raw.indexLow);
        if (id == 0) throw new IOException("Win32 未提供可確認的 File ID");
        return new FileInfo(raw.attributes, raw.volume, id, ((long) raw.sizeHigh << 32) | Integer.toUnsignedLong(raw.sizeLow));
    }

    Path finalPath(HANDLE handle) throws IOException {
        char[] path = new char[32768];
        int length = FILE.GetFinalPathNameByHandleW(handle, path, path.length, 0);
        if (length == 0) throw failed("GetFinalPathNameByHandleW");
        if (length >= path.length) throw new IOException("原生 HANDLE 路徑過長");
        String value = new String(path, 0, length);
        if (!value.startsWith("\\\\?\\") || value.startsWith("\\\\?\\UNC\\")) throw new IOException("未知或遠端 HANDLE namespace");
        return Path.of(value.substring(4)).toAbsolutePath().normalize();
    }

    byte[] read(HANDLE handle, int length) throws IOException {
        if (length < 1 || length > 64 * 1024 * 1024) throw new IOException("玩家檔大小超出可安全驗證範圍");
        // 只需回到 0，且檔案有 64 MiB 上限；不依賴 LARGE_INTEGER 按值傳遞的 ABI 猜測。
        if (FILE.SetFilePointer(handle, 0, null, 0) != 0) throw failed("SetFilePointer");
        byte[] result = new byte[length]; byte[] chunk = new byte[Math.min(length, 65536)];
        int offset = 0;
        while (offset < length) {
            int wanted = Math.min(chunk.length, length - offset);
            var count = new IntByReference();
            if (!KERNEL.ReadFile(handle, chunk, wanted, count, null)) throw failed("ReadFile");
            if (count.getValue() < 1 || count.getValue() > wanted) throw new IOException("原生玩家檔讀回提前 EOF 或長度錯誤");
            System.arraycopy(chunk, 0, result, offset, count.getValue()); offset += count.getValue();
        }
        return result;
    }

    void flush(HANDLE handle) throws IOException {
        if (!KERNEL.FlushFileBuffers(handle)) throw failed("FlushFileBuffers");
    }
    void close(HANDLE handle) throws IOException {
        if (!KERNEL.CloseHandle(handle)) throw failed("CloseHandle");
    }
    private static IOException failed(String operation) {
        return new IOException(operation + " 失敗，Win32=" + KERNEL.GetLastError());
    }
}
