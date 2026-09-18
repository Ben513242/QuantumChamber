package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsPlayerCheckpointVerifierTest {
    @TempDir Path directory;
    private Path originalDirectory;

    @BeforeEach void captureFixtureLocation() throws IOException {
        var attributes = Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        System.err.println("CHECKPOINT_FIXTURE java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " tempDir=" + directory + " absolute=" + directory.toAbsolutePath().normalize()
                + " directory=" + attributes.isDirectory() + " symbolicLink=" + attributes.isSymbolicLink()
                + " other=" + attributes.isOther() + " fileKey=" + attributes.fileKey());
        originalDirectory = directory;
        directory = directory.toRealPath();
        System.err.println("CHECKPOINT_CANONICAL original=" + originalDirectory + " fixture=" + directory);
    }

    @Test void realNativeReadFlushAndFormalIdentitySucceedEvenWhenJavaFileKeyIsNull() throws Exception {
        Path file = fixture(); var io = new TrackingNative();
        new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot());
        assertEquals(1, io.flushes);
        assertEquals(2, io.reads);
        assertTrue(io.opened > file.getNameCount());
        assertEquals(io.opened, io.closed);
        assertEquals(snapshot(), NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()));
        if (!originalDirectory.equals(directory)) {
            Path alias = originalDirectory.resolve("playerdata/player.dat");
            var aliasIo = new TrackingNative();
            IOException error = assertThrows(IOException.class,
                    () -> new WindowsPlayerCheckpointVerifier(aliasIo).verify(alias, snapshot()));
            assertTrue(error.getMessage().contains("opened HANDLE 路徑不符"));
            assertTrue(aliasIo.firstMismatch);
            assertEquals(0, aliasIo.reads);
            assertEquals(aliasIo.opened, aliasIo.closed);
        }
    }

    @Test void lockedLeafAndOrdinaryAncestorsDenyRenameSecondWriterAndJunctionAbaFirstStep() throws Exception {
        Path file = fixture(); var attempted = new AtomicBoolean();
        var io = new TrackingNative() {
            @Override void flush(HANDLE handle) throws IOException {
                attempted.set(true);
                assertThrows(IOException.class, () -> Files.move(file, file.resolveSibling("replaced.dat")));
                // ABA 的第一步必須失敗；沒有先移走目錄，就無法換入 junction 再偷偷換回。
                assertThrows(IOException.class, () -> Files.move(file.getParent(), directory.resolve("moved")));
                HANDLE writer = Kernel32.INSTANCE.CreateFile(file.toString(), 0x40000000, 7, null, 3, 0, null);
                boolean denied = WinBase.INVALID_HANDLE_VALUE.equals(writer);
                if (!denied) Kernel32.INSTANCE.CloseHandle(writer);
                assertTrue(denied, "同一正式檔的第二寫入者必須被 Win32 拒絕");
                super.flush(handle);
            }
        };
        new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot());
        assertTrue(attempted.get()); assertEquals(io.opened, io.closed);
        Files.move(file.getParent(), directory.resolve("after-close"));
    }

    @Test void preexistingJunctionAncestorIsRejectedWithoutFollowingItsPlayerFile() throws Exception {
        Path file = fixture(); Path junction = directory.resolve("junction");
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), file.getParent().toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        assertEquals(0, process.waitFor(), output);
        var io = new TrackingNative();
        IOException error = assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io)
                .verify(junction.resolve(file.getFileName()), snapshot()));
        assertTrue(error.getMessage().contains("reparse"));
        assertEquals(0, io.reads); assertEquals(io.opened, io.closed);
        assertEquals(snapshot(), NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()));
    }

    @Test void sharingConflictFailsAndReleasesEveryPreviouslyOpenedAncestor() throws Exception {
        Path file = fixture(); var io = new TrackingNative();
        HANDLE writer = Kernel32.INSTANCE.CreateFile(file.toString(), 0x40000000, 7, null, 3, 0, null);
        assertNotEquals(WinBase.INVALID_HANDLE_VALUE, writer);
        try {
            assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
            assertTrue(io.opened > 0); assertEquals(io.opened, io.closed);
        } finally { assertTrue(Kernel32.INSTANCE.CloseHandle(writer)); }
        Files.move(file.getParent(), directory.resolve("after-sharing-failure"));
    }

    @Test void realReadbackPrecedesInjectedFlushFailureAndAllHandlesClose() throws Exception {
        Path file = fixture(); var reached = new AtomicBoolean();
        var io = new TrackingNative() {
            @Override void flush(HANDLE handle) throws IOException { reached.set(true); throw new IOException("注入 FlushFileBuffers 失敗"); }
        };
        IOException error = assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
        assertTrue(reached.get()); assertTrue(error.getMessage().contains("FlushFileBuffers"));
        assertEquals(1, io.reads); assertEquals(io.opened, io.closed);
        Files.move(file.getParent(), directory.resolve("after-flush-failure"));
    }

    @Test void nativeProbeIdentityMismatchCannotBecomeContentOnlySuccess() throws Exception {
        Path file = fixture(); var reached = new AtomicBoolean();
        var io = new TrackingNative() {
            private HANDLE wrongProbe;
            @Override HANDLE openProbe(Path path) throws IOException {
                var handle = super.openProbe(path);
                if (path.equals(file)) wrongProbe = handle;
                return handle;
            }
            @Override FileInfo info(HANDLE handle) throws IOException {
                var actual = super.info(handle);
                if (handle.equals(wrongProbe)) {
                    reached.set(true);
                    return new FileInfo(actual.attributes(), actual.volume(), actual.fileId() ^ 1L, actual.size());
                }
                return actual;
            }
        };
        assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
        assertTrue(reached.get()); assertEquals(0, io.flushes); assertEquals(io.opened, io.closed);
    }

    @Test void postFlushReadChangeFailsEvenWithUnchangedNativeIdentity() throws Exception {
        Path file = fixture();
        var io = new TrackingNative() {
            @Override byte[] read(HANDLE handle, int length) throws IOException {
                byte[] actual = super.read(handle, length);
                if (reads == 2) actual[0] ^= 1;
                return actual;
            }
        };
        assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
        assertEquals(1, io.flushes); assertEquals(2, io.reads); assertEquals(io.opened, io.closed);
    }

    @Test void corruptNbtAndOversizedCompressedFileFailBeforeFlushWithoutHandleLeak() throws Exception {
        Path file = fixture(); Files.write(file, new byte[] {1,2,3});
        var io = new TrackingNative();
        assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
        assertEquals(0, io.flushes); assertEquals(io.opened, io.closed);
        try (var sized = new java.io.RandomAccessFile(file.toFile(), "rw")) { sized.setLength(64L * 1024 * 1024 + 1); }
        var oversized = new TrackingNative();
        assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(oversized).verify(file, snapshot()));
        assertEquals(0, oversized.reads); assertEquals(oversized.opened, oversized.closed);
    }

    @Test void uncAndNonDefaultProvidersAreRejectedBeforeAnyHandleOpen() throws Exception {
        var io = new TrackingNative(); var verifier = new WindowsPlayerCheckpointVerifier(io);
        assertThrows(IOException.class, () -> verifier.verify(Path.of("\\\\localhost\\share\\player.dat"), snapshot()));
        var zip = directory.resolve("provider.zip");
        try (var fs = FileSystems.newFileSystem(java.net.URI.create("jar:" + zip.toUri()), java.util.Map.of("create", "true"))) {
            assertThrows(IOException.class, () -> verifier.verify(fs.getPath("/player.dat"), snapshot()));
        }
        assertEquals(0, io.opened);
    }

    @Test void nativeCloseRuntimeFailureStillAttemptsEveryOwnedAncestorClose() throws Exception {
        Path file = fixture();
        var io = new TrackingNative() {
            private HANDLE leaf;
            @Override HANDLE openLeaf(Path path) throws IOException { leaf = super.openLeaf(path); return leaf; }
            @Override void close(HANDLE handle) throws IOException {
                super.close(handle);
                if (handle.equals(leaf)) throw new IllegalStateException("注入 JNA close runtime 失敗");
            }
        };
        try {
            assertThrows(IOException.class, () -> new WindowsPlayerCheckpointVerifier(io).verify(file, snapshot()));
            assertEquals(io.opened, io.closed, "leaf close 例外不得略過其他祖先 HANDLE");
        } finally {
            // RED 也只清理此 fixture 自己開啟的 HANDLE，避免測試故意注入的失敗留下鎖。
            for (HANDLE handle : java.util.Set.copyOf(io.active)) new WindowsCheckpointNative().close(handle);
        }
    }

    private Path fixture() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("playerdata"));
        Path file = folder.resolve("player.dat"); NbtIo.writeCompressed(snapshot(), file); return file;
    }
    private static NbtCompound snapshot() {
        var root = new NbtCompound(); root.putInt("DataVersion", 3953); root.putInt("foodLevel", 20);
        var extra = new NbtCompound(); extra.putString("Value", "完整保留其他模組欄位"); root.put("othermod:data", extra); return root;
    }
    private static class TrackingNative extends WindowsCheckpointNative {
        int opened, closed, flushes, reads;
        final java.util.Set<HANDLE> active = new java.util.HashSet<>();
        final java.util.Map<HANDLE, Path> openedPaths = new java.util.HashMap<>();
        boolean firstMismatch;
        @Override HANDLE openDirectory(Path path) throws IOException { return captureOpen("directory", path, super.openDirectory(path)); }
        @Override HANDLE openLeaf(Path path) throws IOException { return captureOpen("leaf", path, super.openLeaf(path)); }
        @Override HANDLE openProbe(Path path) throws IOException { return captureOpen("probe", path, super.openProbe(path)); }
        private HANDLE captureOpen(String kind, Path path, HANDLE handle) {
            opened++; active.add(handle); openedPaths.put(handle, path);
            System.err.println("CHECKPOINT_OPEN kind=" + kind + " handle=" + handle + " expected=" + path);
            return handle;
        }
        @Override int requireLocalNtfs(Path root) throws IOException {
            int volume = super.requireLocalNtfs(root);
            System.err.println("CHECKPOINT_VOLUME root=" + root + " filesystem=NTFS volume=" + Integer.toUnsignedString(volume));
            return volume;
        }
        @Override FileInfo info(HANDLE handle) throws IOException {
            FileInfo actual = super.info(handle);
            System.err.println("CHECKPOINT_INFO handle=" + handle + " expected=" + openedPaths.get(handle)
                    + " attributes=0x" + Integer.toHexString(actual.attributes())
                    + " volume=" + Integer.toUnsignedString(actual.volume())
                    + " fileId=" + Long.toUnsignedString(actual.fileId()) + " size=" + actual.size());
            return actual;
        }
        @Override Path finalPath(HANDLE handle) throws IOException {
            Path actual = super.finalPath(handle);
            Path expected = openedPaths.get(handle);
            System.err.println("CHECKPOINT_NAME handle=" + handle + " expected=" + expected + " actual=" + actual);
            if (!firstMismatch && !actual.equals(expected)) {
                firstMismatch = true;
                System.err.println("CHECKPOINT_FIRST_MISMATCH handle=" + handle + " expected=" + expected + " actual=" + actual);
            }
            return actual;
        }
        @Override void close(HANDLE handle) throws IOException {
            super.close(handle); closed++; active.remove(handle); openedPaths.remove(handle);
        }
        @Override void flush(HANDLE handle) throws IOException { super.flush(handle); flushes++; }
        @Override byte[] read(HANDLE handle, int length) throws IOException { var bytes = super.read(handle, length); reads++; return bytes; }
    }
}
