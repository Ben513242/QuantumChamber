package dev.quantumchamber.universe;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerLootComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.util.Rarity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.util.Identifier;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * 固定1.21受審模板值切片。只處理owned NBT；未知consumer拒絕，不代表完整設定閉包。
 * required引用交同一次圖的registry owner解析；本類不以字串掃描推測資料相依性。
 */
final class Minecraft121TemplateValueContracts {
    private static final Set<String> FURNACES=Set.of("minecraft:furnace","minecraft:smoker","minecraft:blast_furnace");
    private static final Set<String> LOOTABLE=Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:barrel",
            "minecraft:dispenser","minecraft:dropper","minecraft:hopper");
    private static final Set<String> COMMON=Set.of("id","x","y","z","keepPacked","components");
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    private final BiConsumer<Occurrence,RegistryKey<?>> references;
    private String aliasScope="template";

    Minecraft121TemplateValueContracts(Minecraft121SettingsGraph graph,DynamicRegistryManager registries,
            BiConsumer<Occurrence,RegistryKey<?>> references) {
        this.graph=Objects.requireNonNull(graph);this.registries=Objects.requireNonNull(registries);
        this.references=Objects.requireNonNull(references);
    }

    /** 所有失敗使圖失效；資料節點可共用，每次呼叫仍完整驗consumer並保留全部occurrence。 */
    NbtCompound capture(Key node,NbtCompound template) {return capture(node,template,template);}
    NbtCompound captureEnvelope(Key node,NbtCompound envelope,String scope) {
        aliasScope=Objects.requireNonNull(scope);
        return capture(node,envelope.getCompound("NativeTemplate"),envelope);
    }
    private NbtCompound capture(Key node,NbtCompound template,NbtCompound payload) {
        try {
            graph.requireOpen();require(node.kind()==Kind.TEMPLATE,"TEMPLATE_NODE_KIND");
            graph.resource(node);graph.decoded(measure(template,0));
            // bounded encoder先驗完整raw資料，才deep copy或進native codec；後續失敗不可finish。
            graph.payload(node,payload);
            var owned=template.copy();
            require(owned.getKeys().stream().allMatch(Set.of("size","palette","palettes","blocks","entities","DataVersion","author")::contains),
                    "UNSUPPORTED_TEMPLATE_FIELD");
            intVector(owned.get("size"));require(owned.get("DataVersion") instanceof NbtInt,"TEMPLATE_SHAPE_INVALID");
            require(owned.contains("palette")!=owned.contains("palettes"),"TEMPLATE_PALETTE_SHAPE_INVALID");
            var palettes=new ArrayList<NbtList>();
            if(owned.contains("palette"))palettes.add(list(owned.get("palette"),NbtElement.COMPOUND_TYPE));
            else for(var p:list(owned.get("palettes"),NbtElement.LIST_TYPE))palettes.add(list(p,NbtElement.COMPOUND_TYPE));
            var blocks=list(owned.get("blocks"),NbtElement.COMPOUND_TYPE);
            var entities=list(owned.get("entities"),NbtElement.COMPOUND_TYPE);
            require(entities.isEmpty(),"UNSUPPORTED_TEMPLATE_ENTITY");
            require(!palettes.isEmpty()||blocks.isEmpty(),"TEMPLATE_PALETTE_SHAPE_INVALID");
            for(int pi=0;pi<palettes.size();pi++) {
                var palette=palettes.get(pi);var blockIds=new ArrayList<String>();var blockEntityStates=new ArrayList<Boolean>();
                for(int si=0;si<palette.size();si++) {
                    var state=palette.getCompound(si);
                    require(state.getKeys().stream().allMatch(Set.of("Name","Properties")::contains),"TEMPLATE_BLOCK_STATE_INVALID");
                    String id=canonical(string(state,"Name"));
                    require(Registries.BLOCK.containsId(Identifier.of(id)),"TEMPLATE_BLOCK_MISSING");
                    var decoded=BlockState.CODEC.parse(registries.getOps(NbtOps.INSTANCE),state).getOrThrow();
                    nativeSource(decoded.getBlock().getClass());
                    require(Registries.BLOCK.getId(decoded.getBlock()).toString().equals(id),"TEMPLATE_BLOCK_STATE_INVALID");
                    reference(node,"palettes/"+pi+"/"+si,RegistryKey.of(RegistryKeys.BLOCK,Identifier.of(id)));
                    blockIds.add(id);blockEntityStates.add(decoded.hasBlockEntity());
                }
                for(int bi=0;bi<blocks.size();bi++) {
                    var block=blocks.getCompound(bi);
                    require(block.getKeys().stream().allMatch(Set.of("state","pos","nbt")::contains),"TEMPLATE_BLOCK_SHAPE_INVALID");
                    require(block.get("state") instanceof NbtInt,"TEMPLATE_PALETTE_INDEX_INVALID");
                    int index=block.getInt("state");require(index>=0&&index<blockIds.size(),"TEMPLATE_PALETTE_INDEX_INVALID");
                    intVector(block.get("pos"));
                    require(!blockEntityStates.get(index)||block.contains("nbt"),"TEMPLATE_BLOCK_ENTITY_NBT_REQUIRED");
                    if(block.contains("nbt")) {
                        require(block.get("nbt") instanceof NbtCompound,"TEMPLATE_BLOCK_SHAPE_INVALID");
                        block(node,"palettes/"+pi+"/blocks/"+bi,blockIds.get(index),block.getCompound("nbt"));
                    }
                }
            }
            return owned;
        } catch(RuntimeException|Error failure) {graph.invalidate();throw failure;}
    }

    private void block(Key node,String path,String blockId,NbtCompound nbt) {
        history(blockId,nbt);
        boolean furnace=FURNACES.contains(blockId),lootable=LOOTABLE.contains(blockId),jigsaw=blockId.equals("minecraft:jigsaw");
        require(furnace||lootable||jigsaw,"UNSUPPORTED_TEMPLATE_BLOCK_ENTITY");
        String expectedOwner=blockId.equals("minecraft:trapped_chest")?"minecraft:chest":blockId;
        require(nbt.get("id") instanceof NbtString&&canonical(nbt.getString("id")).equals(expectedOwner),"TEMPLATE_BLOCK_ENTITY_OWNER_MISMATCH");
        var fields=new HashSet<>(COMMON);
        if(furnace)fields.addAll(Set.of("RecipesUsed","BurnTime","CookTime","CookTimeTotal","Items"));
        if(lootable)fields.addAll(Set.of("LootTable","LootTableSeed","Items","TransferCooldown"));
        if(jigsaw)fields.addAll(Set.of("pool","name","target","final_state","joint","selection_priority","placement_priority"));
        require(fields.containsAll(nbt.getKeys()),"UNSUPPORTED_TEMPLATE_BLOCK_ENTITY_FIELD");
        if(jigsaw) {
            String pool=nbt.contains("pool")?string(nbt,"pool"):"minecraft:empty";
            reference(node,path+"/pool",RegistryKey.of(RegistryKeys.TEMPLATE_POOL,Identifier.of(pool)));
            // final_state能帶block entity NBT，尚未admit該consumer，不能當literal略過。
            require(!nbt.contains("final_state"),"UNSUPPORTED_JIGSAW_FINAL_STATE");
        }
        if(nbt.contains("LootTable")) {
            require(lootable,"UNSUPPORTED_TEMPLATE_LOOT_OWNER");
            reference(node,path+"/LootTable",RegistryKey.of(RegistryKeys.LOOT_TABLE,Identifier.of(string(nbt,"LootTable"))));
        }
        if(nbt.contains("Items")) {
            var items=list(nbt.get("Items"),NbtElement.COMPOUND_TYPE);
            for(int i=0;i<items.size();i++)item(node,path+"/Items/"+i,items.getCompound(i));
        }
        if(nbt.contains("components")) {
            require(nbt.get("components") instanceof NbtCompound,"TEMPLATE_COMPONENT_SHAPE_INVALID");
            var components=nbt.getCompound("components");
            rawComponents(components,false);
            var decoded=ComponentMap.CODEC.parse(registries.getOps(NbtOps.INSTANCE),components).getOrThrow();
            components(node,path+"/components",decoded);
        }
    }

    private void history(String owner,NbtCompound nbt) {
        if(!nbt.contains("RecipesUsed"))return;
        require(FURNACES.contains(owner),"UNSUPPORTED_RECIPES_USED_OWNER");
        require(nbt.get("RecipesUsed") instanceof NbtCompound,"UNSUPPORTED_RECIPES_USED_CONTAINER");
        var history=nbt.getCompound("RecipesUsed");
        for(String key:history.getKeys()) {
            try {Identifier.of(key);}catch(RuntimeException bad) {throw new Failure("UNSUPPORTED_RECIPES_USED_ID");}
            require(history.get(key) instanceof NbtInt,"UNSUPPORTED_RECIPES_USED_VALUE");
        }
    }

    private void item(Key node,String path,NbtCompound nbt) {
        require(Set.of("id","count","components","Slot").containsAll(nbt.getKeys()),"UNSUPPORTED_TEMPLATE_ITEM_FIELD");
        if(nbt.contains("components")) {
            require(nbt.get("components") instanceof NbtCompound,"TEMPLATE_COMPONENT_SHAPE_INVALID");
            rawComponents(nbt.getCompound("components"),true);
        }
        var stack=ItemStack.CODEC.parse(registries.getOps(NbtOps.INSTANCE),nbt).getOrThrow();
        require(!stack.isEmpty(),"UNSUPPORTED_EMPTY_TEMPLATE_ITEM");
        nativeSource(stack.getItem().getClass());
        reference(node,path+"/id",RegistryKey.of(RegistryKeys.ITEM,Registries.ITEM.getId(stack.getItem())));
        components(node,path+"/components",stack.getComponents());
    }

    /** 先守raw形狀，不能用原生CODEC忽略未知鍵的行為冒充已審consumer。 */
    private static void rawComponents(NbtCompound raw,boolean item) {
        for(String id:raw.getKeys()) {
            if(id.equals("minecraft:container_loot")) {
                require(raw.get(id) instanceof NbtCompound,"TEMPLATE_COMPONENT_SHAPE_INVALID");
                var loot=raw.getCompound(id);
                require(Set.of("loot_table","seed").containsAll(loot.getKeys())&&loot.get("loot_table") instanceof NbtString
                        &&(!loot.contains("seed")||loot.get("seed") instanceof NbtLong),"TEMPLATE_COMPONENT_SHAPE_INVALID");
            } else if(item&&Set.of("minecraft:max_stack_size","minecraft:max_damage","minecraft:damage","minecraft:repair_cost").contains(id)) {
                require(raw.get(id) instanceof NbtInt,"TEMPLATE_COMPONENT_SHAPE_INVALID");
            } else if(item&&id.equals("minecraft:rarity")) {
                require(raw.get(id) instanceof NbtString,"TEMPLATE_COMPONENT_SHAPE_INVALID");
            } else throw new Failure("UNSUPPORTED_TEMPLATE_COMPONENT");
        }
    }

    private void components(Key node,String path,ComponentMap map) {
        for(var component:map) {
            var type=component.type();var value=component.value();
            if(type==DataComponentTypes.CONTAINER_LOOT) {
                require(value!=null&&value.getClass()==ContainerLootComponent.class,"UNSUPPORTED_TEMPLATE_COMPONENT");
                nativeSource(value.getClass());
                reference(node,path+"/container_loot",((ContainerLootComponent)value).lootTable());
            } else if(type==DataComponentTypes.MAX_STACK_SIZE||type==DataComponentTypes.MAX_DAMAGE
                    ||type==DataComponentTypes.DAMAGE||type==DataComponentTypes.REPAIR_COST) {
                require(value instanceof Integer,"UNSUPPORTED_TEMPLATE_COMPONENT");
            } else if(type==DataComponentTypes.RARITY) {
                require(value!=null&&value.getClass()==Rarity.class,"UNSUPPORTED_TEMPLATE_COMPONENT");
                nativeSource(value.getClass());
            } else if(type==DataComponentTypes.ENCHANTMENTS) {
                require(value!=null&&value.getClass()==ItemEnchantmentsComponent.class
                        &&((ItemEnchantmentsComponent)value).isEmpty(),"UNSUPPORTED_TEMPLATE_COMPONENT");
                nativeSource(value.getClass());
            } else if(type==DataComponentTypes.LORE) {
                require(value!=null&&value.getClass()==LoreComponent.class&&((LoreComponent)value).lines().isEmpty()
                        &&((LoreComponent)value).styledLines().isEmpty(),"UNSUPPORTED_TEMPLATE_COMPONENT");
                nativeSource(value.getClass());
            } else if(type==DataComponentTypes.ATTRIBUTE_MODIFIERS) {
                require(value!=null&&value.getClass()==AttributeModifiersComponent.class
                        &&((AttributeModifiersComponent)value).modifiers().isEmpty(),"UNSUPPORTED_TEMPLATE_COMPONENT");
                nativeSource(value.getClass());
            } else throw new Failure("UNSUPPORTED_TEMPLATE_COMPONENT");
        }
        var ops=registries.getOps(NbtOps.INSTANCE);
        var encoded=ComponentMap.CODEC.encodeStart(ops,map).getOrThrow();
        var rebuilt=ComponentMap.CODEC.parse(ops,encoded).getOrThrow();
        require(encoded.equals(ComponentMap.CODEC.encodeStart(ops,rebuilt).getOrThrow()),"TEMPLATE_COMPONENT_REBUILD_MISMATCH");
        graph.decoded(measure(encoded,0));
        // 原始NBT與有效item預設component各自保留；不能只記item id漏掉有效預設值。
        var resolved=new Key(Kind.RESOLVED,"quantumchamber:template_components",node.id()+"/"+path.toLowerCase(Locale.ROOT));
        var occurrence=graph.reference(node,path+"/effective",resolved,"template","effective-components");
        graph.payload(resolved,encoded);graph.expanded(occurrence);
    }

    private void reference(Key node,String path,RegistryKey<?> target) {
        var key=new Key(Kind.REGISTRY,target.getRegistry().toString(),target.getValue().toString());
        var occurrence=graph.reference(node,path,key,aliasScope,"required");
        references.accept(occurrence,target);
    }
    private static String string(NbtCompound value,String key) {
        require(value.get(key) instanceof NbtString,"TEMPLATE_REFERENCE_TYPE");return value.getString(key);
    }
    private static String canonical(String id) {
        var parsed=Identifier.of(id);require(parsed.toString().equals(id),"TEMPLATE_IDENTIFIER_NONCANONICAL");return id;
    }
    private static NbtList list(NbtElement value,int type) {
        require(value instanceof NbtList,"TEMPLATE_SHAPE_INVALID");var list=(NbtList)value;
        require(list.isEmpty()||list.getHeldType()==type,"TEMPLATE_SHAPE_INVALID");return list;
    }
    private static void intVector(NbtElement value) {
        require(list(value,NbtElement.INT_TYPE).size()==3,"TEMPLATE_SHAPE_INVALID");
    }
    /** 保守記憶體計量，逐節點檢查，禁止先無界序列化或deep copy。 */
    static long measure(NbtElement value,int depth) {
        require(depth<=128,"BUDGET_DEPTH");long total=16;
        if(value instanceof NbtCompound c)for(String key:c.getKeys()) {
            total+=8L+4L*key.length()+measure(c.get(key),depth+1);require(total<=8388608,"BUDGET_RAW");
        } else if(value instanceof NbtList l)for(var child:l) {total+=measure(child,depth+1);require(total<=8388608,"BUDGET_RAW");}
        else if(value instanceof NbtString s)total+=4L*s.asString().length();
        else if(value instanceof NbtByteArray a)total+=a.size();
        else if(value instanceof NbtIntArray a)total+=4L*a.size();
        else if(value instanceof NbtLongArray a)total+=8L*a.size();
        require(total<=8388608,"BUDGET_RAW");return total;
    }
    private static void nativeSource(Class<?> type) {
        require(type.getProtectionDomain().getCodeSource()!=null&&SharedConstants.class.getProtectionDomain().getCodeSource()!=null
                &&type.getProtectionDomain().getCodeSource().getLocation().equals(SharedConstants.class.getProtectionDomain().getCodeSource().getLocation()),
                "TEMPLATE_NATIVE_SOURCE");
    }
    private static void require(boolean condition,String code) {if(!condition)throw new Failure(code);}
}
