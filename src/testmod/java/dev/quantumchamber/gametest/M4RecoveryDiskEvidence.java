package dev.quantumchamber.gametest;

import dev.quantumchamber.persistence.SessionRecoveryRecord;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.*;
import net.minecraft.util.math.ChunkPos;

/** 僅 testmod：以唯讀 MCA／NBT 讀取正式磁碟，避開 world 與 IO worker 的記憶體快取。 */
final class M4RecoveryDiskEvidence {
    private M4RecoveryDiskEvidence() { }
    static boolean geometryEmpty(Path runRoot,List<SessionRecoveryRecord.SpaceLease> leases) throws IOException {
        var chunks=new HashMap<Long,NbtCompound>();
        for(var lease : leases) {
            var b=lease.bounds();
            for(int x=b.getMinX();x<=b.getMaxX();x++) for(int z=b.getMinZ();z<=b.getMaxZ();z++) {
                var pos=new ChunkPos(x>>4,z>>4); var nbt=chunks.get(pos.toLong());
                if(nbt==null) { nbt=read(runRoot,pos); chunks.put(pos.toLong(),nbt); }
                for(int y=b.getMinY();y<=b.getMaxY();y++) if(!air(nbt,x,y,z)) return false;
            }
        }
        return true;
    }
    private static NbtCompound read(Path root,ChunkPos pos) throws IOException {
        var path=root.resolve("world/dimensions/quantumchamber/superposition/region/r."+(pos.x>>5)+"."+(pos.z>>5)+".mca");
        try(var file=new RandomAccessFile(path.toFile(),"r")) {
            file.seek(4L*((pos.x&31)+(pos.z&31)*32)); int location=file.readInt();
            int sector=location>>>8,count=location&255;
            if(sector<2 || count==0) throw new IOException("正式 corridor chunk 不存在："+pos);
            file.seek(sector*4096L); int size=file.readInt(),compression=file.readUnsignedByte();
            if(size<=1 || size>count*4096-4 || (compression&128)!=0) throw new IOException("不支援或損壞的 fixture MCA entry");
            var payload=new byte[size-1]; file.readFully(payload);
            InputStream input=new ByteArrayInputStream(payload);
            input=switch(compression) { case 1 -> new GZIPInputStream(input); case 2 -> new InflaterInputStream(input); case 3 -> input; default -> throw new IOException("未知 MCA compression"); };
            try(var data=new DataInputStream(input)) {
                var nbt=NbtIo.readCompound(data,NbtSizeTracker.of(16L*1024*1024));
                if(nbt.getInt("xPos")!=pos.x || nbt.getInt("zPos")!=pos.z) throw new IOException("正式 chunk 座標不符");
                return nbt;
            }
        }
    }
    private static boolean air(NbtCompound nbt,int x,int y,int z) throws IOException {
        for(var raw : nbt.getList("sections",NbtElement.COMPOUND_TYPE)) {
            var section=(NbtCompound)raw; if(section.getByte("Y")!=(y>>4)) continue;
            if(!section.contains("block_states",NbtElement.COMPOUND_TYPE)) return true;
            var states=section.getCompound("block_states"); var palette=states.getList("palette",NbtElement.COMPOUND_TYPE);
            if(palette.isEmpty()) throw new IOException("缺少磁碟 block palette");
            int index=0;
            if(palette.size()>1) {
                int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1)),perLong=64/bits;
                int cell=((y&15)<<8)|((z&15)<<4)|(x&15); var packed=states.getLongArray("data");
                if(cell/perLong>=packed.length) throw new IOException("磁碟 block data 長度不足");
                index=(int)((packed[cell/perLong]>>>((cell%perLong)*bits))&((1L<<bits)-1));
            }
            if(index>=palette.size()) throw new IOException("磁碟 palette index 不符");
            return palette.getCompound(index).getString("Name").equals("minecraft:air");
        }
        return true;
    }
}
