package dev.quantumchamber.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import net.minecraft.nbt.*;
import static dev.quantumchamber.persistence.JournalNbt.*;

/**
 * 只按指定 SID 查詢的不可變回程歷史。
 * <p>查詢規則（M7 spec §5.3、V10）：只按已知的 SID 開它自己的 {@code <SID>.dat}（{@code receiptPath}），禁止列舉收據目錄（{@code newDirectoryStream}、
 * {@code Files.list}／{@code walk}／{@code find}、{@code File.list} 等），也不碰其他 SID 的收據檔。收據沒有數量上限，列舉的成本會隨歷史一直成長。
 * 讀取的單一入口是 {@code read}：{@code lookup}、{@code requireExact}、{@code verifyActive}、admission 與發布都經它，{@code SessionRecoveryState}
 * 的開服載入與存檔前核對也是呼叫這些方法。
 * <p>守門：{@code M7HomeHistoryScaleTest} 的 {@code ListingProbe} 記錄經「測試傳入的這個 store」的每一次目錄列舉與越界存取，必須為 0。它只包住那個
 * store，若在 {@code SessionRecoveryState} 或其他類別另寫目錄列舉，那個測試看不到；新增讀取路徑時請經 {@code read}，不要自己列舉（T4 B2b-3，B2b2-M2）。
 */
public final class HomeReceiptStore {
    private final Path save;
    public HomeReceiptStore(Path save) throws IOException {
        // 呼叫者提供的世界根可經過短名或連結。只以這個已解析的存檔根作為收據邊界。
        this.save = save.toRealPath();
    }
    /**
     * 存檔根 {@code saveRoot} 底下 SID {@code sid} 的收據檔路徑（M7 spec §5.3：{@code <save>/data/quantumchamber_home_receipts/<canonical-lowercase-SID>.dat}）。
     * 純計算：不碰檔案系統、不建立目錄、不解析連結，也不做 reparse 與 containment 檢查（那些由讀寫入口經 {@link #directory} 負責）。收據的讀寫與
     * journal 的收據 ERROR（{@code SessionRecoveryState}）共用這一個來源，兩處不會各自組路徑（T4 B2b-2，P2-M5 第 4 點）。
     */
    static Path receiptPath(Path saveRoot, UUID sid) { return receiptDirectory(saveRoot).resolve(sid+".dat"); }
    private static Path receiptDirectory(Path saveRoot) { return saveRoot.resolve("data").resolve("quantumchamber_home_receipts"); }
    private Path directory(boolean create) throws IOException {
        Path target=receiptDirectory(save);
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
        try { directory(false); } catch(NoSuchFileException absent) { return Optional.empty(); }
        Path target=receiptPath(save,sid);
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
        var target=receiptPath(save,frozen.sessionUuid());
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
