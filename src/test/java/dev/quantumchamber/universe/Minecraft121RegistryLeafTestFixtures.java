package dev.quantumchamber.universe;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;

/**
 * C1d-D1b static registry leaf測試共用的fixture組裝（LeafTest與Canon共用）：ROOT、BLOCK／ITEM的REGISTRY node key，以及合成的
 * 單一item模板。這裡只放fixture的組裝；預期值的helper（expectedBlockLeaf、expectedItemLeaf、literal）維持在各測試類別內各自獨立，
 * 才能保留oracle的獨立性。不引用ModBlocks／ModItems，也不讀寫任何全域狀態。
 */
final class Minecraft121RegistryLeafTestFixtures {
    private Minecraft121RegistryLeafTestFixtures() {}

    /** 圖的根節點（OPTIONS minecraft:settings／minecraft:root）。 */
    static final Minecraft121SettingsGraph.Key ROOT=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.OPTIONS,
            "minecraft:settings","minecraft:root");

    static Minecraft121SettingsGraph.Key blockNode(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.BLOCK.getValue().toString(),id);
    }
    static Minecraft121SettingsGraph.Key itemNode(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.ITEM.getValue().toString(),id);
    }

    /**
     * 合成模板：1×1×1、只有一個container block entity，內含一個不帶raw components的item（Slot 0、count 1）。
     * DataVersion取目前版本，不觸發datafix；palette只寫Name，由原生toBlockState取default state。
     */
    static NbtCompound singleItemTemplate(String block,String item) {
        var template=new NbtCompound();
        template.putInt("DataVersion",net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId());
        var size=new NbtList();var pos=new NbtList();
        for(int i=0;i<3;i++){size.add(NbtInt.of(1));pos.add(NbtInt.of(0));}
        template.put("size",size);
        var state=new NbtCompound();state.putString("Name",block);var palette=new NbtList();palette.add(state);template.put("palette",palette);
        var stack=new NbtCompound();stack.putByte("Slot",(byte)0);stack.putString("id",item);stack.putInt("count",1);
        var items=new NbtList();items.add(stack);
        var blockEntity=new NbtCompound();blockEntity.putString("id",block);blockEntity.put("Items",items);
        var info=new NbtCompound();info.putInt("state",0);info.put("pos",pos);info.put("nbt",blockEntity);
        var blocks=new NbtList();blocks.add(info);template.put("blocks",blocks);template.put("entities",new NbtList());
        return template;
    }
}
