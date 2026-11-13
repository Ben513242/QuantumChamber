package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T1 僅驗證用 PoC；不接 production、不提供 replace fallback。 */
class M7PublishSpikeTest {
    @TempDir Path directory;
    @Test void startupPublicationIsInvisibleUntilForcedAndPublished()throws Exception {
        Path target=directory.resolve("startup.json");var created=new CountDownLatch(1);var finish=new CountDownLatch(1);
        byte[] complete="{\"schema\":1,\"nonce\":\"00112233445566778899aabbccddeeff\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var pool=Executors.newSingleThreadExecutor()){
            var writer=pool.submit(()->{dev.quantumchamber.gametest.M7HomeProbe.publishDurable(target,complete,temporary->{created.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));});return true;});
            assertTrue(created.await(5,TimeUnit.SECONDS));assertFalse(Files.exists(target),"零byte暫存檔不得被讀者當成published startup");
            finish.countDown();assertTrue(writer.get());assertArrayEquals(complete,Files.readAllBytes(target));readStartup(target);
        }
        for(String invalid:List.of("","{","{}","{\"schema\":1,\"nonce\":\"wrong\"}")){
            Path bad=directory.resolve(UUID.randomUUID()+".json");Files.writeString(bad,invalid,StandardOpenOption.CREATE_NEW);
            assertThrows(IOException.class,()->readStartup(bad),"published損壞不得略過nonce或延時重試洗判");
        }
    }
    private static void readStartup(Path path)throws IOException {
        byte[] same=Files.readAllBytes(path);
        try{var parsed=com.google.gson.JsonParser.parseString(new String(same,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(!parsed.keySet().equals(Set.of("schema","nonce"))||parsed.get("schema").getAsInt()!=1||!parsed.get("nonce").getAsString().matches("[0-9a-f]{32}"))throw new IOException("M7_STARTUP_INVALID");
        }catch(RuntimeException failure){throw new IOException("M7_STARTUP_INVALID",failure);}
    }
    @Test void publishesCompleteBytesWithoutClobber() throws Exception {
        byte[] first=new byte[131072], second=new byte[131072];
        Arrays.fill(first,(byte)0x31); Arrays.fill(second,(byte)0x72);
        Path target=directory.resolve("receipt.dat");
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(byte[] bytes:List.of(first,second)) futures.add(pool.submit(() -> {
                Path temporary=completeTemp(directory,bytes); ready.countDown(); go.await();
                try { Files.createLink(target,temporary); return true; }
                catch(FileAlreadyExistsException occupied) { return false; }
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(futures.get(0).get(),futures.get(1).get());
        }
        byte[] winner=Files.readAllBytes(target);
        assertTrue(Arrays.equals(first,winner)||Arrays.equals(second,winner));
        Path identity=directory.resolve("winner-identity.link");Files.createLink(identity,target);
        assertTrue(Files.isSameFile(identity,target),"用保留的硬連結核對原生檔案身分；Windows fileKey 可為 null");
        for(byte[] existing:List.of(winner,second,new byte[]{0,1,2})) {
            Path occupied=directory.resolve(UUID.randomUUID()+".dat");
            Files.write(occupied,existing,StandardOpenOption.CREATE_NEW);
            Path before=directory.resolve(UUID.randomUUID()+".identity");Files.createLink(before,occupied);
            Path temporary=completeTemp(directory,first);
            assertThrows(FileAlreadyExistsException.class,()->Files.createLink(occupied,temporary));
            assertArrayEquals(existing,Files.readAllBytes(occupied));
            assertTrue(Files.isSameFile(before,occupied));
        }
        // 呼叫結果未知時，只能按 SID 讀回原件；不能改名覆寫它。
        assertArrayEquals(winner,Files.readAllBytes(target));
        assertTrue(Files.isSameFile(identity,target));
        System.out.println("M7_PUBLISH_VOLUME os="+System.getProperty("os.name")+" store="+Files.getFileStore(directory)+" identity="+identity);
    }
    @Test void unsupportedVolumeRejectsAdmissionWithoutReplacement() throws Exception {
        Path zip=directory.resolve("unsupported.zip");
        try(var fs=FileSystems.newFileSystem(java.net.URI.create("jar:"+zip.toUri()),Map.of("create","true"))) {
            Path temporary=fs.getPath("/temporary"); Files.write(temporary,new byte[]{1,2,3});
            assertThrows(UnsupportedOperationException.class,()->Files.createLink(fs.getPath("/receipt"),temporary));
            assertFalse(Files.exists(fs.getPath("/receipt")));
        }
    }
    private static Path completeTemp(Path parent,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(parent,"m7-force-",".tmp");
        Files.write(temporary,bytes,StandardOpenOption.WRITE);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
        assertArrayEquals(bytes,Files.readAllBytes(temporary));return temporary;
    }
}
