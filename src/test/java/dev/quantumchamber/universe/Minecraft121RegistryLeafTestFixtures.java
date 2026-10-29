package dev.quantumchamber.universe;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;

/**
 * C1d-D1b static registry leaf測試共用的fixture組裝（LeafTest與Canon共用）：ROOT、BLOCK／ITEM的REGISTRY node key、ITEM leaf
 * 需要的lease registries，以及合成的單一item模板。這裡只放fixture的組裝；預期值的helper（expectedBlockLeaf、expectedItemLeaf、
 * literal）維持在各測試類別內各自獨立，才能保留oracle的獨立性。不引用ModBlocks／ModItems，也不寫入全域狀態（只讀全域static
 * registry；局部registry都是新物件，不登記到Registries.REGISTRIES）。singleItemTemplate讀SharedConstants.getGameVersion()，
 * 呼叫前須先SharedConstants.createGameVersion()（兩個測試類別都在@BeforeAll完成）。
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
     * ITEM leaf的component codec要求lease持有的全域static registry（D1b-3a的固定identity聯集）：ITEM、DATA_COMPONENT_TYPE、BLOCK、
     * ATTRIBUTE、STATUS_EFFECT本身。測試端獨立列出，不讀production的清單：production若多要求一個registry，用這組fixture的測試會失敗。
     */
    static List<Registry<?>> itemLeafStaticRegistries() {
        return List.<Registry<?>>of(Registries.ITEM,Registries.DATA_COMPONENT_TYPE,Registries.BLOCK,Registries.ATTRIBUTE,Registries.STATUS_EFFECT);
    }
    /** others加上itemLeafStaticRegistries()組成的lease形狀registries；others不得含聯集內的key（ImmutableImpl遇重複key會丟例外）。 */
    static DynamicRegistryManager withItemLeafRegistries(Registry<?>... others) {
        var all=new ArrayList<Registry<?>>(List.of(others));all.addAll(itemLeafStaticRegistries());
        return new DynamicRegistryManager.ImmutableImpl(all);
    }
    /** itemLeafStaticRegistries()扣掉key那一個（identity負例：lease缺該registry）。 */
    static DynamicRegistryManager itemLeafRegistriesWithout(RegistryKey<? extends Registry<?>> key) {
        return new DynamicRegistryManager.ImmutableImpl(itemLeafStaticRegistries().stream().filter(r->!r.getKey().equals(key)).toList());
    }
    /** itemLeafStaticRegistries()中key那一個換成同key的局部空registry（identity負例：同key、不同物件；不改動全域registry）。 */
    static DynamicRegistryManager itemLeafRegistriesWithForeign(RegistryKey<? extends Registry<?>> key) {
        return new DynamicRegistryManager.ImmutableImpl(itemLeafStaticRegistries().stream()
                .map(r->r.getKey().equals(key)?localRegistry(key):r).toList());
    }
    /** 同key的局部空registry：新物件，已freeze，不登記到Registries.REGISTRIES。 */
    @SuppressWarnings({"rawtypes","unchecked"})
    static Registry<?> localRegistry(RegistryKey<? extends Registry<?>> key) {
        var local=new SimpleRegistry((RegistryKey)key,com.mojang.serialization.Lifecycle.stable());
        local.freeze();
        return local;
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
