package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;

/**
 * C1d-D1b static registry leaf測試共用的fixture組裝（LeafTest與Canon共用）：ROOT、BLOCK／ITEM／ATTRIBUTE／STATUS_EFFECT的REGISTRY
 * node key、ITEM leaf需要的lease registries、合成的單一item模板，以及canonical bytes的測試端讀取（canonicalNodes）。這裡只放fixture
 * 的組裝與讀取；預期值的helper（expectedBlockLeaf、expectedItemLeaf、literal）維持在各測試類別內各自獨立，才能保留oracle的獨立性。
 * 不引用ModBlocks／ModItems，也不寫入全域狀態（只讀全域static registry；局部registry都是新物件，不登記到Registries.REGISTRIES）。
 * singleItemTemplate讀SharedConstants.getGameVersion()，呼叫前須先SharedConstants.createGameVersion()（兩個測試類別都在@BeforeAll完成）。
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
    static Minecraft121SettingsGraph.Key attributeNode(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.ATTRIBUTE.getValue().toString(),id);
    }
    static Minecraft121SettingsGraph.Key effectNode(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.STATUS_EFFECT.getValue().toString(),id);
    }

    record CanonicalEdge(Minecraft121SettingsGraph.Key from,String path,Minecraft121SettingsGraph.Key target,String aliasScope,String use) {}
    record CanonicalGraph(java.util.Map<Minecraft121SettingsGraph.Key,byte[]> nodes,List<CanonicalEdge> edges) {}
    /**
     * 測試用的canonical讀取：依Graph.finish的格式（DOMAIN、node數、每個node的key與payload、edge數、每條edge）
     * 與Encoder.nbt的編碼，解出node key、payload bytes與edges；必須恰好讀完全部bytes。
     */
    static CanonicalGraph canonicalNodes(byte[] bytes) {
        var in=java.nio.ByteBuffer.wrap(bytes);
        assertEquals("quantumchamber:m6:settings-graph:v1",canonicalText(in));
        var nodes=new java.util.LinkedHashMap<Minecraft121SettingsGraph.Key,byte[]>();
        for(int i=0,count=in.getInt();i<count;i++) {
            var key=canonicalKey(in);int start=in.position();skipCanonicalNbt(in);
            assertNull(nodes.put(key,Arrays.copyOfRange(bytes,start,in.position())),"node key不得重複");
        }
        var edges=new ArrayList<CanonicalEdge>();
        for(int i=0,count=in.getInt();i<count;i++)
            edges.add(new CanonicalEdge(canonicalKey(in),canonicalText(in),canonicalKey(in),canonicalText(in),canonicalText(in)));
        assertFalse(in.hasRemaining(),"canonical bytes須恰好讀完");
        return new CanonicalGraph(nodes,edges);
    }
    private static String canonicalText(java.nio.ByteBuffer in) {
        byte[] value=new byte[in.getInt()];in.get(value);return new String(value,java.nio.charset.StandardCharsets.UTF_8);
    }
    private static Minecraft121SettingsGraph.Key canonicalKey(java.nio.ByteBuffer in) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.valueOf(canonicalText(in)),canonicalText(in),canonicalText(in));
    }
    private static void skipCanonicalNbt(java.nio.ByteBuffer in) {
        byte type=in.get();
        switch(type) {
            case NbtElement.BYTE_TYPE -> in.get();
            case NbtElement.SHORT_TYPE -> in.getShort();
            case NbtElement.INT_TYPE,NbtElement.FLOAT_TYPE -> in.getInt();
            case NbtElement.LONG_TYPE,NbtElement.DOUBLE_TYPE -> in.getLong();
            case NbtElement.STRING_TYPE -> canonicalText(in);
            case NbtElement.BYTE_ARRAY_TYPE -> {int count=in.getInt();in.position(in.position()+count);}
            case NbtElement.INT_ARRAY_TYPE -> {int count=in.getInt();in.position(in.position()+4*count);}
            case NbtElement.LONG_ARRAY_TYPE -> {int count=in.getInt();in.position(in.position()+8*count);}
            case NbtElement.LIST_TYPE -> {for(int i=0,count=in.getInt();i<count;i++)skipCanonicalNbt(in);}
            case NbtElement.COMPOUND_TYPE -> {for(int i=0,count=in.getInt();i<count;i++){canonicalText(in);skipCanonicalNbt(in);}}
            default -> fail("未知的canonical NBT type "+type);
        }
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
