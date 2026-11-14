package dev.quantumchamber.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.*;
import static dev.quantumchamber.persistence.JournalNbt.*;

/** 只按指定 SID 查詢的不可變回程歷史。 */
public final class HomeReceiptStore {
    private final Path save;
    public HomeReceiptStore(Path save) throws IOException {
        // 呼叫者提供的世界根可經過短名或連結。只以這個已解析的存檔根作為收據邊界。
        this.save = save.toRealPath();
    }
    private Path directory(boolean create) throws IOException {
        Path target=save.resolve("data").resolve("quantumchamber_home_receipts");
        Path cursor=save;
        for(Path component:save.relativize(target)) {
            cursor=cursor.resolve(component);
            BasicFileAttributes attrs;
            try { attrs=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch(NoSuchFileException absent) {
                if(!create) throw absent;
                try { Files.createDirectory(cursor); } catch(FileAlreadyExistsException raced) { }
                attrs=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            }
            if(reparse(cursor) || !attrs.isDirectory() || attrs.isOther() || attrs.isSymbolicLink() || !cursor.toRealPath().equals(cursor))
                throw new IOException("收據目錄不符合存檔實體路徑："+cursor);
        }
        return target;
    }
    private static boolean reparse(Path path) throws IOException {
        if(!com.sun.jna.Platform.isWindows() || path.getFileSystem()!=FileSystems.getDefault()) return false;
        int flags=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if(flags == -1) throw new IOException("無法確認收據路徑檔案屬性："+path);
        return (flags & 0x400) != 0;
    }
    public Optional<HomeSessionRecord> lookup(UUID sid) throws IOException {
        return read(sid).map(Receipt::row);
    }
    private record Receipt(NbtCompound wrapped,HomeSessionRecord row) {}
    private Optional<Receipt> read(UUID sid) throws IOException {
        Path dir;
        try { dir=directory(false); } catch(NoSuchFileException absent) { return Optional.empty(); }
        Path target=dir.resolve(sid+".dat");
        try { Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
        catch(NoSuchFileException absent) { return Optional.empty(); }
        if(reparse(target) || !target.toRealPath().equals(target)) throw new IOException("收據檔案不是原生普通檔案");
        var wrapped=SessionJournalStore.readReceipt(target);
        try {
            var d=compound(wrapped,"data"); keys(d,"SchemaVersion","SessionUuid","CloseIntent","Session");
            HomeJournalCodec.fixed(d,"SchemaVersion",1);
            var normalized=HomeJournalCodec.decode(compound(d,"Session"));
            var intent=HomeJournalCodec.close(compound(d,"CloseIntent"));
            if(!sid.equals(uuid(d,"SessionUuid")) || !sid.equals(normalized.sessionUuid())
                    || !(normalized.home().closeIntent() instanceof HomeSessionRecord.None)
                    || !(intent instanceof HomeSessionRecord.Close)) throw new IOException("收據凍結身分不符");
            return Optional.of(new Receipt(wrapped,normalized.withClose(intent)));
        } catch(RuntimeException invalid) { throw new IOException("收據權威不合法",invalid); }
    }
    public void requireAdmissible(UUID sid) throws IOException {
        var dir=directory(true);
        if(lookup(sid).isPresent()) throw new IOException("旅程識別碼已有歷史");
        var temporary=Files.createTempFile(dir,"probe-",".tmp");
        Path link=dir.resolve("probe-"+UUID.randomUUID()+".link");
        Files.createLink(link,temporary);
        if(!Files.isSameFile(link,temporary)) throw new IOException("無法確認同卷發布");
        Files.delete(link); Files.delete(temporary);
    }
    static NbtCompound wrapper(HomeSessionRecord frozen) {
        if(!(frozen.home().closeIntent() instanceof HomeSessionRecord.Close)) throw new IllegalArgumentException("收據必須結案");
        var wrapped=new NbtCompound(); NbtHelper.putDataVersion(wrapped); var d=new NbtCompound();
        d.putInt("SchemaVersion",1); d.putUuid("SessionUuid",frozen.sessionUuid());
        d.put("CloseIntent",HomeJournalCodec.close(frozen.home().closeIntent()));
        d.put("Session",HomeJournalCodec.encode(frozen.withClose(new HomeSessionRecord.None())));
        wrapped.put("data",d); return wrapped;
    }
    public void publishOrVerify(HomeSessionRecord frozen) throws IOException {
        var expected=wrapper(frozen);
        var dir=directory(true);
        var existing=read(frozen.sessionUuid());
        if(existing.isPresent()) { verify(existing,expected); return; }
        var temporary=Files.createTempFile(dir,"publish-",".tmp");
        NbtIo.writeCompressed(expected,temporary);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        if(!SessionJournalStore.readReceipt(temporary).equals(expected)) throw new IOException("收據寫前驗讀不符");
        var target=dir.resolve(frozen.sessionUuid()+".dat");
        try { Files.createLink(target,temporary); }
        catch(FileAlreadyExistsException raced) { /* 同 SID 競爭只驗實際贏家。 */ }
        verify(read(frozen.sessionUuid()),expected);
        Files.delete(temporary);
    }
    void requireExact(HomeSessionRecord frozen) throws IOException {
        verify(read(frozen.sessionUuid()),wrapper(frozen));
    }
    void verifyActive(JournalSession active) throws IOException {
        var stored=read(active.sessionUuid());
        if(stored.isEmpty()) return;
        if(!(active instanceof HomeSessionRecord h) || !(h.home().closeIntent() instanceof HomeSessionRecord.Close))
            throw new IOException("既有回程歷史不能與未結案熱列共存");
        verify(stored,wrapper(h));
    }
    private static void verify(Optional<Receipt> actual,NbtCompound expected) throws IOException {
        if(actual.isEmpty() || !actual.get().wrapped().equals(expected)) throw new IOException("既有 SID 收據與結案快照不符");
    }
}
