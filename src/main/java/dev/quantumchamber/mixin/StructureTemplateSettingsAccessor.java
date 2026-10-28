package dev.quantumchamber.mixin;

import dev.quantumchamber.universe.Minecraft121TemplateSettingsAdapter;
import dev.quantumchamber.universe.Minecraft121TemplateSettingsAdapter.*;
import java.util.List;
import net.minecraft.nbt.*;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.*;

/** 完整四欄只在matching permit內轉為owned NBT；沒有raw List/template getter。 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateSettingsAccessor implements Minecraft121TemplateSettingsAdapter.TemplateAccess {
    @Shadow @Final private List<StructureTemplate.PalettedBlockInfoList> blockInfoLists;
    @Shadow @Final private List<StructureTemplate.StructureEntityInfo> entities;
    @Shadow private Vec3i size;
    @Shadow private String author;

    @Override @Unique
    public OwnedTemplate quantumchamber$captureSettings(CapturePermit permit) {
        if(permit==null)throw new IllegalStateException("TEMPLATE_CAPTURE_PERMIT_REQUIRED");
        var self=(StructureTemplate)(Object)this;permit.requireTemplate(self);
        if(blockInfoLists==null||entities==null||size==null||author==null)throw Minecraft121TemplateSettingsAdapter.rejected("TEMPLATE_SHAPE_INVALID");
        var shape=new NbtCompound();var palettes=new NbtList();long copied=0;
        for(var palette:blockInfoLists) {
            permit.checkpoint();Minecraft121TemplateSettingsAdapter.nativeType(palette,StructureTemplate.PalettedBlockInfoList.class);
            copied+=16;quantumchamber$limit(copied);var blocks=new NbtList();
            for(var info:palette.getAll()) {
                permit.checkpoint();Minecraft121TemplateSettingsAdapter.nativeType(info,StructureTemplate.StructureBlockInfo.class);
                var n=new NbtCompound();n.put("Pos",quantumchamber$ints(info.pos().getX(),info.pos().getY(),info.pos().getZ()));
                n.put("State",NbtHelper.fromBlockState(info.state()));
                if(info.nbt()!=null){copied+=quantumchamber$size(info.nbt(),0);quantumchamber$limit(copied);n.put("Nbt",info.nbt().copy());}
                copied+=quantumchamber$size(n,0);quantumchamber$limit(copied);blocks.add(n);
            }
            palettes.add(blocks);
        }
        var entityValues=new NbtList();
        for(var info:entities) {
            permit.checkpoint();Minecraft121TemplateSettingsAdapter.nativeType(info,StructureTemplate.StructureEntityInfo.class);
            var n=new NbtCompound();var pos=new NbtList();pos.add(NbtDouble.of(info.pos.x));pos.add(NbtDouble.of(info.pos.y));pos.add(NbtDouble.of(info.pos.z));n.put("Pos",pos);
            n.put("BlockPos",quantumchamber$ints(info.blockPos.getX(),info.blockPos.getY(),info.blockPos.getZ()));
            copied+=quantumchamber$size(info.nbt,0);quantumchamber$limit(copied);n.put("Nbt",info.nbt.copy());
            copied+=quantumchamber$size(n,0);quantumchamber$limit(copied);entityValues.add(n);
        }
        shape.put("Palettes",palettes);shape.put("Entities",entityValues);shape.put("Size",quantumchamber$ints(size.getX(),size.getY(),size.getZ()));shape.putString("Author",author);
        permit.chargeOwned(quantumchamber$size(shape,0));permit.checkpoint();
        var nativeNbt=self.writeNbt(new NbtCompound());permit.chargeOwned(quantumchamber$size(nativeNbt,0));permit.checkpoint();
        return new OwnedTemplate(shape,nativeNbt);
    }
    @Unique private static NbtList quantumchamber$ints(int x,int y,int z) {
        var list=new NbtList();list.add(NbtInt.of(x));list.add(NbtInt.of(y));list.add(NbtInt.of(z));return list;
    }
    @Unique private static void quantumchamber$limit(long value) {if(value>8388608)throw Minecraft121TemplateSettingsAdapter.rejected("BUDGET_RAW");}
    @Unique private static long quantumchamber$size(NbtElement value,int depth) {
        if(value==null||depth>128)throw Minecraft121TemplateSettingsAdapter.rejected("BUDGET_DEPTH");long bytes=16;
        if(value instanceof NbtCompound c)for(String key:c.getKeys()){bytes+=8L+4L*key.length()+quantumchamber$size(c.get(key),depth+1);quantumchamber$limit(bytes);}
        else if(value instanceof NbtList l)for(var child:l){bytes+=quantumchamber$size(child,depth+1);quantumchamber$limit(bytes);}
        else if(value instanceof NbtString s)bytes+=4L*s.asString().length();
        else if(value instanceof NbtByteArray a)bytes+=a.size();else if(value instanceof NbtIntArray a)bytes+=4L*a.size();else if(value instanceof NbtLongArray a)bytes+=8L*a.size();
        quantumchamber$limit(bytes);return bytes;
    }
}
