package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.Minecraft121RegistryLeafContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * C1d-D1b-1 BLOCK與D1b-2 ITEM static registry leaf的單元測試與成本診斷。
 * 只讀全域static registry：不建立或註冊Block／Item，不populate tag；不引用ModBlocks／ModItems。唯一的instance欄位變更是
 * itemLeafDefaultComponentMutationIsCapturedAndRestored（設計§5.5選配，條件寫在該測試），finally以identity還原並assertSame。
 */
class Minecraft121RegistryLeafContractsTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();
    }
    private static void rejects(String code,org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(Minecraft121SettingsGraph.Failure.class,action).getMessage());
    }
    private static Minecraft121SettingsGraph.Key node(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.BLOCK.getValue().toString(),id);
    }
    private static NbtList strings(String... values) {
        var list=new NbtList();for(String value:values)list.add(NbtString.of(value));return list;
    }
    /** 走production的block(...)接線：registries只放全域static BLOCK registry，entry以key自registry解析（同closure）。 */
    private static NbtCompound leaf(Block block) {
        var key=block.getRegistryEntry().registryKey();var entry=Registries.BLOCK.getEntry(key).orElseThrow();
        var graph=new Minecraft121SettingsGraph();
        var leaves=new Minecraft121RegistryLeafContracts(graph,new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK)));
        var payload=leaves.block(node(key.getValue().toString()),key,entry);
        assertTrue(graph.finish().length>0,"payload須已寫入圖");
        return payload;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static NbtList nativeValueNames(Property<?> property) {
        var list=new NbtList();
        for(var value:property.getValues())list.add(NbtString.of(((Property)property).name((Comparable)value)));
        return list;
    }

    @Test void blockLeafPayloadIsExactStateDefinition() {
        var keys=java.util.Set.of("Contract","DefaultState","PropertyDomains","RequiredFeatures");
        for(var block:java.util.List.of(Blocks.STONE,Blocks.CHEST,Blocks.OAK_STAIRS)) {
            String id=Registries.BLOCK.getId(block).toString();var payload=leaf(block);
            var domains=payload.getCompound("PropertyDomains");
            assertAll(id,
                    ()->assertEquals(keys,payload.getKeys(),"payload的鍵恰為4個"),
                    ()->assertEquals("quantumchamber:m6-static-block-leaf-v1",payload.getString("Contract")),
                    ()->assertEquals(NbtHelper.fromBlockState(block.getDefaultState()),payload.getCompound("DefaultState"),"DefaultState取default state"),
                    ()->assertEquals(id,payload.getCompound("DefaultState").getString("Name"),"DefaultState.Name等於id"),
                    ()->assertEquals(strings("minecraft:vanilla"),payload.getList("RequiredFeatures",NbtElement.STRING_TYPE)),
                    ()->{
                        var names=new java.util.HashSet<String>();
                        for(var property:block.getStateManager().getProperties()) {
                            names.add(property.getName());
                            assertEquals(nativeValueNames(property),domains.getList(property.getName(),NbtElement.STRING_TYPE),
                                    property.getName()+"的值域次序等於getValues()");
                        }
                        assertEquals(names,domains.getKeys(),"PropertyDomains恰為該block的property");
                    });
        }
        var stone=leaf(Blocks.STONE);var chest=leaf(Blocks.CHEST);var stairs=leaf(Blocks.OAK_STAIRS);
        var stoneDefault=new NbtCompound();stoneDefault.putString("Name","minecraft:stone");
        var stairsProperties=new NbtCompound();stairsProperties.putString("facing","north");stairsProperties.putString("half","bottom");
        stairsProperties.putString("shape","straight");stairsProperties.putString("waterlogged","false");
        assertAll("literal",
                ()->assertEquals(stoneDefault,stone.getCompound("DefaultState"),"stone沒有Properties"),
                ()->assertTrue(stone.getCompound("PropertyDomains").isEmpty()),
                ()->assertEquals(strings("north","south","west","east"),chest.getCompound("PropertyDomains").getList("facing",NbtElement.STRING_TYPE),
                        "chest的facing是原生次序，不是字母序"),
                ()->assertEquals(strings("single","left","right"),chest.getCompound("PropertyDomains").getList("type",NbtElement.STRING_TYPE)),
                ()->assertEquals(strings("true","false"),chest.getCompound("PropertyDomains").getList("waterlogged",NbtElement.STRING_TYPE)),
                ()->assertEquals(stairsProperties,stairs.getCompound("DefaultState").getCompound("Properties"),"oak_stairs的default state"),
                ()->assertNotEquals(NbtHelper.fromBlockState(Blocks.OAK_STAIRS.getStateManager().getStates().get(0)),stairs.getCompound("DefaultState"),
                        "fixture前提：oak_stairs的第一個state不是default"));
    }
    @Test void blockLeafDefaultStateChangesPayloadWithoutGlobalMutation() {
        var stairs=Blocks.OAK_STAIRS;var original=stairs.getDefaultState();
        var east=original.with(Properties.HORIZONTAL_FACING,net.minecraft.util.math.Direction.EAST);
        assertNotSame(original,east,"fixture前提：default的facing不是east");
        var base=blockPayload(stairs,original);var changed=blockPayload(stairs,east);
        assertAll(
                ()->assertNotEquals(base,changed,"同一id改了state值，payload須跟著改變"),
                ()->assertEquals("east",changed.getCompound("DefaultState").getCompound("Properties").getString("facing")),
                ()->assertEquals(base.get("PropertyDomains"),changed.get("PropertyDomains"),"state definition不隨所選state改變"),
                ()->assertEquals(leaf(stairs),base,"production接線傳入getDefaultState()，結果須與seam相同"),
                ()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",()->blockPayload(stairs,Blocks.STONE.getDefaultState())));
        assertSame(original,stairs.getDefaultState(),"seam測試不得改動全域狀態");
    }
    @Test void blockLeafPayloadExcludesSettings() {
        assertNotEquals(Blocks.STONE.getHardness(),Blocks.OBSIDIAN.getHardness(),"fixture前提：兩者hardness不同");
        assertTrue(Blocks.STONE.getStateManager().getProperties().isEmpty()&&Blocks.OBSIDIAN.getStateManager().getProperties().isEmpty(),
                "fixture前提：兩者都沒有property");
        var stone=leaf(Blocks.STONE);var obsidian=leaf(Blocks.OBSIDIAN);
        assertNotEquals(stone,obsidian);
        var a=stone.copy();a.getCompound("DefaultState").remove("Name");
        var b=obsidian.copy();b.getCompound("DefaultState").remove("Name");
        assertEquals(a,b,"兩者payload只差DefaultState.Name：Settings不在FP內，class／source只限定程式碼來源");
    }
    @Test void staticSourcePredicateIsCodeSourceOnly() {
        // 判斷只看class的CodeSource，不接受namespace或id作輸入；全程不建立任何Item或Block實例（class literal不觸發<clinit>）。
        Object anonymous=new Object(){};
        assertNull(String.class.getProtectionDomain().getCodeSource(),"fixture前提：JDK類別的CodeSource為null");
        assertAll(
                ()->assertDoesNotThrow(()->requireNativeSource(Block.class)),
                ()->assertDoesNotThrow(()->requireNativeSource(net.minecraft.item.BlockItem.class)),
                ()->assertDoesNotThrow(()->requireNativeSource(net.minecraft.block.ChestBlock.class)),
                ()->assertAll("subcase=測試class",()->rejects("NATIVE_DEFINITION_SOURCE",()->requireNativeSource(Minecraft121RegistryLeafContractsTest.class))),
                ()->assertAll("subcase=匿名類別",()->rejects("NATIVE_DEFINITION_SOURCE",()->requireNativeSource(anonymous.getClass()))),
                ()->assertAll("subcase=JDK類別",()->rejects("NATIVE_DEFINITION_SOURCE",()->requireNativeSource(String.class))),
                ()->assertAll("subcase=mod的Block子類",()->rejects("NATIVE_DEFINITION_SOURCE",
                        ()->requireNativeSource(dev.quantumchamber.chamber.QuantumBulkheadBlock.class))));
    }
    /** foreign值型別：行為正常的StringIdentifiable enum，只有source不是固定JAR。 */
    enum ForeignValue implements net.minecraft.util.StringIdentifiable {
        A,B;
        @Override public String asString(){return name().toLowerCase(java.util.Locale.ROOT);}
    }
    @Test void blockLeafPropertyContractRejectsForeignProperty() {
        // 匿名Property子類的行為完全正常（值名唯一、parse可回到原值），只有class不是原生：鑑別的是class檢查本身。
        var anonymous=new Property<Boolean>("test",Boolean.class) {
            @Override public java.util.Collection<Boolean> getValues(){return java.util.List.of(true,false);}
            @Override public String name(Boolean value){return value.toString();}
            @Override public java.util.Optional<Boolean> parse(String name){
                return "true".equals(name)?java.util.Optional.of(true):"false".equals(name)?java.util.Optional.of(false):java.util.Optional.empty();
            }
        };
        var subclass=new BooleanProperty("test"){};
        var foreignEnum=EnumProperty.of("test",ForeignValue.class);
        assertAll(
                ()->assertEquals(strings("north","south","west","east"),propertyDomain(Properties.HORIZONTAL_FACING)),
                ()->assertEquals(strings("true","false"),propertyDomain(Properties.WATERLOGGED)),
                ()->assertEquals(strings("0","1","2","3"),propertyDomain(Properties.AGE_3)),
                ()->assertEquals(strings("x","y","z"),propertyDomain(Properties.AXIS)),
                ()->assertAll("subcase=匿名Property子類",()->rejects("UNSUPPORTED_STATIC_PROPERTY",()->propertyDomain(anonymous))),
                ()->assertAll("subcase=原生BooleanProperty的匿名子類",()->rejects("UNSUPPORTED_STATIC_PROPERTY",()->propertyDomain(subclass))),
                ()->assertAll("subcase=foreign enum值型別",()->rejects("UNSUPPORTED_STATIC_PROPERTY",()->propertyDomain(foreignEnum))));
    }
    @Test void staticLeafFeatureGateAcceptsOnlyVanillaSubset() {
        var manager=FeatureFlags.FEATURE_MANAGER;
        assertAll(
                ()->assertDoesNotThrow(()->requireVanillaFeatures(FeatureFlags.VANILLA_FEATURES)),
                ()->assertDoesNotThrow(()->requireVanillaFeatures(FeatureSet.empty())),
                ()->assertAll("subcase=BUNDLE",()->rejects("UNSUPPORTED_FEATURE_GATED_LEAF",()->requireVanillaFeatures(manager.featureSetOf(FeatureFlags.BUNDLE)))),
                ()->assertAll("subcase=VANILLA+BUNDLE",()->rejects("UNSUPPORTED_FEATURE_GATED_LEAF",
                        ()->requireVanillaFeatures(manager.featureSetOf(FeatureFlags.VANILLA,FeatureFlags.BUNDLE)))),
                ()->assertAll("subcase=TRADE_REBALANCE",()->rejects("UNSUPPORTED_FEATURE_GATED_LEAF",
                        ()->requireVanillaFeatures(manager.featureSetOf(FeatureFlags.TRADE_REBALANCE)))));
    }
    @Test void everyVanillaBlockLeafIsAccepted() {
        // D1b-1 quality M3：可用性回歸的gating。只篩minecraft namespace，所以與class執行順序無關（full中先init的mod block會被篩掉）；
        // 不斷言數量或時間。非Failure的例外直接讓本測試失敗。ITEM不加同類測試：白名單外的default component依設計fail closed（D1b-3）。
        var rejected=new java.util.TreeMap<String,String>();int checked=0;
        for(var entry:(Iterable<net.minecraft.registry.entry.RegistryEntry.Reference<Block>>)Registries.BLOCK.streamEntries()::iterator) {
            var id=entry.registryKey().getValue();
            if(!id.getNamespace().equals("minecraft"))continue;
            checked++;
            try {leaf(entry.value());}
            catch(Minecraft121SettingsGraph.Failure failure) {rejected.put(id.toString(),failure.getMessage());}
        }
        assertTrue(checked>0,"fixture前提：有minecraft block");
        assertEquals(java.util.Map.of(),rejected,"每個vanilla BLOCK leaf都須ACCEPTED");
    }
    @Test void staticLeafRejectsNodeNotMatchingKey() {
        // D1b-1 quality M1：payload要寫入的node須是這個key的REGISTRY node；這是caller不變式之外的防線。
        var registries=new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK,Registries.ITEM));
        var stoneKey=Blocks.STONE.getRegistryEntry().registryKey();var stone=Registries.BLOCK.getEntry(stoneKey).orElseThrow();
        var diamondKey=Items.DIAMOND.getRegistryEntry().registryKey();var diamond=Registries.ITEM.getEntry(diamondKey).orElseThrow();
        java.util.function.Function<Minecraft121SettingsGraph.Key,org.junit.jupiter.api.function.Executable> block=
                n->()->new Minecraft121RegistryLeafContracts(new Minecraft121SettingsGraph(),registries).block(n,stoneKey,stone);
        java.util.function.Function<Minecraft121SettingsGraph.Key,org.junit.jupiter.api.function.Executable> item=
                n->()->new Minecraft121RegistryLeafContracts(new Minecraft121SettingsGraph(),registries).item(n,diamondKey,diamond);
        var kind=Minecraft121SettingsGraph.Kind.REGISTRY;
        assertAll(
                ()->assertDoesNotThrow(block.apply(node("minecraft:stone")),"正向對照：BLOCK"),
                ()->assertDoesNotThrow(item.apply(itemNode("minecraft:diamond")),"正向對照：ITEM"),
                ()->assertAll("subcase=BLOCK id不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",block.apply(node("minecraft:granite")))),
                ()->assertAll("subcase=BLOCK registry不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        block.apply(new Minecraft121SettingsGraph.Key(kind,"minecraft:item","minecraft:stone")))),
                ()->assertAll("subcase=BLOCK kind不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        block.apply(new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.TAG,"minecraft:block","minecraft:stone")))),
                ()->assertAll("subcase=ITEM id不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",item.apply(itemNode("minecraft:emerald")))),
                ()->assertAll("subcase=ITEM registry不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",item.apply(node("minecraft:diamond")))),
                ()->assertAll("subcase=ITEM kind不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        item.apply(new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.RESOLVED,"minecraft:item","minecraft:diamond")))));
    }

    // ===== C1d-D1b-2：ITEM static registry leaf =====
    private static Minecraft121SettingsGraph.Key itemNode(String id) {
        return new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.REGISTRY,RegistryKeys.ITEM.getValue().toString(),id);
    }
    private static DynamicRegistryManager itemRegistries() {
        return new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.ITEM));
    }
    /** 走production的item(...)接線：registries只放全域static ITEM registry，entry以key自registry解析（同closure）。 */
    private static NbtCompound itemLeaf(Item item) {
        var key=item.getRegistryEntry().registryKey();var entry=Registries.ITEM.getEntry(key).orElseThrow();
        var graph=new Minecraft121SettingsGraph();
        var payload=new Minecraft121RegistryLeafContracts(graph,itemRegistries()).item(itemNode(key.getValue().toString()),key,entry);
        assertTrue(graph.finish().length>0,"payload須已寫入圖");
        return payload;
    }
    /** 只含單一component的map：診斷與測試用來逐型別判定白名單（以feature為vanilla的item當載體）。 */
    private static <T> ComponentMap single(net.minecraft.component.Component<T> component) {
        return ComponentMap.builder().add(component.type(),component.value()).build();
    }
    private static <T> net.minecraft.component.Component<T> component(Item item,net.minecraft.component.ComponentType<T> type) {
        T value=item.getComponents().get(type);assertNotNull(value,"fixture前提：default帶"+Registries.DATA_COMPONENT_TYPE.getId(type));
        return new net.minecraft.component.Component<>(type,value);
    }

    @Test void itemLeafPayloadIsDefaultComponentMap() {
        // 預期值是B28 diamond的實際DefaultComponents，逐鍵寫成字面值（不經ComponentMap.CODEC）。
        var defaults=new NbtCompound();
        var modifiers=new NbtCompound();modifiers.put("modifiers",new NbtList());defaults.put("minecraft:attribute_modifiers",modifiers);
        var enchantments=new NbtCompound();enchantments.put("levels",new NbtCompound());defaults.put("minecraft:enchantments",enchantments);
        defaults.put("minecraft:lore",new NbtList());defaults.putInt("minecraft:max_stack_size",64);
        defaults.putString("minecraft:rarity","common");defaults.putInt("minecraft:repair_cost",0);
        var diamond=itemLeaf(Items.DIAMOND);var pearl=itemLeaf(Items.ENDER_PEARL);
        assertAll(
                ()->assertEquals(java.util.Set.of("Contract","DefaultComponents","RequiredFeatures"),diamond.getKeys(),"payload的鍵恰為3個"),
                ()->assertEquals("quantumchamber:m6-static-item-leaf-v1",diamond.getString("Contract")),
                ()->assertEquals(defaults,diamond.getCompound("DefaultComponents"),"diamond的default ComponentMap"),
                ()->assertEquals(strings("minecraft:vanilla"),diamond.getList("RequiredFeatures",NbtElement.STRING_TYPE)),
                ()->assertEquals(16,pearl.getCompound("DefaultComponents").getInt("minecraft:max_stack_size"),"ender_pearl讀的是自己的default"),
                ()->assertNotEquals(diamond,pearl,"不同item的payload須不同：抓「沒有讀實際item」的接線突變"));
    }
    @Test void itemLeafRejectsUnreviewedDefaultComponents() {
        String code="UNSUPPORTED_ITEM_DEFAULT_COMPONENT";
        assertAll(
                ()->assertAll("subcase=golden_apple（food）",
                        ()->assertTrue(Items.GOLDEN_APPLE.getComponents().contains(DataComponentTypes.FOOD),"fixture前提"),
                        ()->rejects(code,()->itemLeaf(Items.GOLDEN_APPLE))),
                ()->assertAll("subcase=iron_axe（tool與非空attribute_modifiers）",
                        ()->rejects(code,()->itemLeaf(Items.IRON_AXE)),
                        ()->rejects(code,()->itemPayload(Items.IRON_AXE,single(component(Items.IRON_AXE,DataComponentTypes.TOOL)))),
                        ()->{var modifiers=component(Items.IRON_AXE,DataComponentTypes.ATTRIBUTE_MODIFIERS);
                            assertFalse(modifiers.value().modifiers().isEmpty(),"fixture前提：attribute_modifiers非空");
                            rejects(code,()->itemPayload(Items.IRON_AXE,single(modifiers)));}),
                ()->assertAll("subcase=potion（potion_contents）",
                        ()->assertTrue(Items.POTION.getComponents().contains(DataComponentTypes.POTION_CONTENTS),"fixture前提"),
                        ()->rejects(code,()->itemLeaf(Items.POTION))),
                // 設計外補充：chest的ITEM default帶container（probe證據D1B2-PROBE），同樣不在D1b白名單。
                ()->assertAll("subcase=chest（container）",
                        ()->assertTrue(Items.CHEST.getComponents().contains(DataComponentTypes.CONTAINER),"fixture前提"),
                        ()->rejects(code,()->itemLeaf(Items.CHEST))));
    }
    /** 合成chest模板（與Canon的singleItemTemplate同形，各自獨立）：一個不帶raw components的item。 */
    private static NbtCompound chestTemplate(String item) {
        var template=new NbtCompound();
        template.putInt("DataVersion",net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId());
        var size=new NbtList();var pos=new NbtList();
        for(int i=0;i<3;i++){size.add(NbtInt.of(1));pos.add(NbtInt.of(0));}
        template.put("size",size);
        var state=new NbtCompound();state.putString("Name","minecraft:chest");var palette=new NbtList();palette.add(state);template.put("palette",palette);
        var stack=new NbtCompound();stack.putByte("Slot",(byte)0);stack.putString("id",item);stack.putInt("count",1);
        var items=new NbtList();items.add(stack);
        var blockEntity=new NbtCompound();blockEntity.putString("id","minecraft:chest");blockEntity.put("Items",items);
        var info=new NbtCompound();info.putInt("state",0);info.put("pos",pos);info.put("nbt",blockEntity);
        var blocks=new NbtList();blocks.add(info);template.put("blocks",blocks);template.put("entities",new NbtList());
        return template;
    }
    private static String verdict(org.junit.jupiter.api.function.Executable action,String rejectedCode) {
        try {action.execute();return "ACCEPTED";}
        catch(Minecraft121SettingsGraph.Failure failure) {return failure.getMessage().equals(rejectedCode)?"REJECTED":failure.getMessage();}
        catch(Throwable other) {return other.getClass().getName();}
    }
    @Test void itemDefaultWhitelistMatchesTemplateEffectiveWhitelist() {
        // 一邊是leaf的default白名單，一邊是TemplateValueContracts.capture對「只含該item的合成chest模板」的有效component白名單；
        // reference consumer是stub，只比accept／reject，不finish，也不填假payload。chest（container）是設計外補充。
        var registries=new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK,Registries.ITEM));
        var items=java.util.List.of(Items.DIAMOND,Items.ENDER_PEARL,Items.WATER_BUCKET,Items.GOLDEN_APPLE,Items.WOODEN_SHOVEL,
                Items.IRON_AXE,Items.POTION,Items.CHEST);
        var leaf=new java.util.LinkedHashMap<String,String>();var template=new java.util.LinkedHashMap<String,String>();
        for(var item:items) {
            var id=Registries.ITEM.getId(item);
            leaf.put(id.toString(),verdict(()->itemLeaf(item),"UNSUPPORTED_ITEM_DEFAULT_COMPONENT"));
            var node=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.TEMPLATE,"minecraft:structure","test:whitelist/"+id.getPath());
            template.put(id.toString(),verdict(()->new Minecraft121TemplateValueContracts(new Minecraft121SettingsGraph(),registries,(occurrence,key)->{})
                    .capture(node,chestTemplate(id.toString())),"UNSUPPORTED_TEMPLATE_COMPONENT"));
        }
        var expected=new java.util.LinkedHashMap<String,String>();
        for(String id:java.util.List.of("minecraft:diamond","minecraft:ender_pearl","minecraft:water_bucket"))expected.put(id,"ACCEPTED");
        for(String id:java.util.List.of("minecraft:golden_apple","minecraft:wooden_shovel","minecraft:iron_axe","minecraft:potion","minecraft:chest"))
            expected.put(id,"REJECTED");
        assertAll(
                ()->assertEquals(template,leaf,"兩份白名單逐項同判"),
                ()->assertEquals(expected,leaf,"fixture前提：接受與拒絕兩邊都有"));
    }
    @Test void itemLeafDefaultComponentsChangePayloadWithoutGlobalMutation() {
        var diamond=Items.DIAMOND;var original=diamond.getComponents();
        var changed=ComponentMap.builder().addAll(original).add(DataComponentTypes.MAX_STACK_SIZE,16).build();
        var base=itemPayload(diamond,original);var other=itemPayload(diamond,changed);
        var withFood=ComponentMap.builder().addAll(original).add(DataComponentTypes.FOOD,component(Items.GOLDEN_APPLE,DataComponentTypes.FOOD).value()).build();
        assertAll(
                ()->assertNotEquals(base,other,"同一id改了default component值，payload須跟著改變"),
                ()->assertEquals(16,other.getCompound("DefaultComponents").getInt("minecraft:max_stack_size")),
                ()->assertEquals(base.get("RequiredFeatures"),other.get("RequiredFeatures")),
                ()->assertEquals(itemLeaf(diamond),base,"production接線傳入getComponents()，結果須與seam相同"),
                ()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->itemPayload(diamond,withFood)));
        assertSame(original,diamond.getComponents(),"seam測試不得改動全域狀態");
    }
    @Test void blockItemLeafChecksBlockSourceAndHasNoBlockEdge() {
        // item(...)寫死成對executedOwnerClasses(item)逐項requireNativeSource；見證3把「不回傳block class」當突變，由下方等值斷言抓到。
        // 殘餘風險：「刪除整個迴圈」（連同item class檢查）沒有任何測試能抓到：測試不能建立或註冊foreign Item／BlockItem
        // （Item.<init>會寫入全域registry），靠spec review逐行確認。
        var sapling=Items.DARK_OAK_SAPLING;
        assertAll(
                ()->assertEquals(java.util.List.of(BlockItem.class,net.minecraft.block.ChestBlock.class),executedOwnerClasses(Items.CHEST),
                        "BlockItem的block class也會被執行（RequiredFeatures委派getBlock()）"),
                ()->assertEquals(java.util.List.of(net.minecraft.item.AliasedBlockItem.class,net.minecraft.block.CropBlock.class),
                        executedOwnerClasses(Items.WHEAT_SEEDS),"BlockItem子類同樣帶block class"),
                ()->assertEquals(java.util.List.of(Item.class),executedOwnerClasses(Items.DIAMOND),"非BlockItem只有item class"),
                ()->assertEquals(((BlockItem)sapling).getBlock().getRequiredFeatures(),sapling.getRequiredFeatures(),
                        "fixture前提：BlockItem的RequiredFeatures由Block推導"),
                ()->{
                    // leaf不產生occurrence：dark_oak_sapling（BlockItem＋SaplingBlock，default只有baseline）只寫入自己的ITEM node。
                    var key=sapling.getRegistryEntry().registryKey();var entry=Registries.ITEM.getEntry(key).orElseThrow();
                    var node=itemNode(key.getValue().toString());var graph=new Minecraft121SettingsGraph();
                    var payload=new Minecraft121RegistryLeafContracts(graph,itemRegistries()).item(node,key,entry);
                    var expected=new Minecraft121SettingsGraph();expected.payload(node,payload);
                    assertArrayEquals(expected.finish(),graph.finish(),"只有ITEM node本身，沒有指向BLOCK的edge");
                });
    }
    private static final Minecraft121SettingsGraph.Key ROOT=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.OPTIONS,
            "minecraft:settings","minecraft:root");
    /** 獨立組出「ROOT一條named reference指向該ITEM node、payload為給定值」的完整圖bytes。 */
    private static byte[] expectedItemGraph(String id,NbtCompound payload) {
        var graph=new Minecraft121SettingsGraph();graph.payload(ROOT,NbtInt.of(1));graph.payload(itemNode(id),payload);
        graph.expanded(graph.reference(ROOT,"item",itemNode(id),"ordinary","required"));
        return graph.finish();
    }
    @Test void itemLeafDefaultComponentMutationIsCapturedAndRestored() throws Exception {
        // 設計§5.5選配，只限分支A：setup probe成立（證據D1B2-PROBE：Items.DIAMOND instanceof ItemAccessor，寫回同一物件後assertSame）。
        // 依賴Fabric的內部mixin net.fabricmc.fabric.mixin.item.ItemAccessor（不是API；fabric-item-api-v1 11.0.0+afdfc921d1，
        // class SHA256 b56a5426cf8a9510d49caa08f00a0acb78d1ff3395ff0c844f69d2769b3fb099）；版本固定，升級Fabric時要重驗。
        // 條件：單一method、單執行緒；變更前暖機；時序固定為「舊closure在變更前drain → 變更 → 舊圖第一次finish → 新closure（不開lease）
        // drain後finish → finally以identity還原」，最後assertSame。變更期間不建立ItemStack，也不呼叫其他API。
        var item=Items.DIAMOND;var id="minecraft:diamond";var key=item.getRegistryEntry().registryKey();var original=item.getComponents();
        var changed=ComponentMap.builder().addAll(original).add(DataComponentTypes.MAX_STACK_SIZE,16).build();
        // 暖機：變更前對同一item完整走一次seam（原值與變更值）、leaf capture與closure drain／finish。
        var expectedOriginal=expectedItemGraph(id,itemPayload(item,original));
        var expectedChanged=expectedItemGraph(id,itemPayload(item,changed));
        try(var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of())) {
            var warm=new Minecraft121SettingsGraph();warm.payload(ROOT,NbtInt.of(1));
            var warmClosure=new Minecraft121RegistryClosure(warm,itemRegistries(),resources);warmClosure.reference(ROOT,"item",key);warmClosure.drain();
            assertArrayEquals(expectedOriginal,warm.finish(),"暖機：變更前closure結果等於獨立組出的圖");
            var oldGraph=new Minecraft121SettingsGraph();oldGraph.payload(ROOT,NbtInt.of(1));
            var oldClosure=new Minecraft121RegistryClosure(oldGraph,itemRegistries(),resources);oldClosure.reference(ROOT,"item",key);oldClosure.drain();
            var newGraph=new Minecraft121SettingsGraph();newGraph.payload(ROOT,NbtInt.of(1));
            var newClosure=new Minecraft121RegistryClosure(newGraph,itemRegistries(),resources);newClosure.reference(ROOT,"item",key);
            byte[] oldBytes,newBytes;
            try {
                ((net.fabricmc.fabric.mixin.item.ItemAccessor)item).setComponents(changed);
                oldBytes=oldGraph.finish();
                newClosure.drain();newBytes=newGraph.finish();
            } finally {
                ((net.fabricmc.fabric.mixin.item.ItemAccessor)item).setComponents(original);
            }
            assertSame(original,item.getComponents(),"finally已以identity還原原物件");
            assertAll(
                    ()->assertArrayEquals(expectedOriginal,oldBytes,"舊圖在變更前已擷取，第一次finish不受變更影響"),
                    ()->assertArrayEquals(expectedChanged,newBytes,"新圖擷取到變更後的default component"));
        }
    }

    /**
     * 單節點圖的finish長度扣掉固定開銷（DOMAIN、node數、key、edge數），即該node的payload bytes。這依賴Graph.finish的版面：
     * 格式改變時此欄會失真而不會報錯，所以只用在不gating的診斷。
     */
    private static long payloadBytes(byte[] finished,Minecraft121SettingsGraph.Key key) {
        long text=4+"quantumchamber:m6:settings-graph:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long keyBytes=0;
        for(String part:java.util.List.of(key.kind().name(),key.registry(),key.id()))keyBytes+=4+part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return finished.length-text-4-keyBytes-4;
    }
    private static String stats(long[] values) {
        var sorted=values.clone();java.util.Arrays.sort(sorted);long total=0;for(long value:sorted)total+=value;
        java.util.function.IntFunction<Long> at=p->sorted[Math.min(sorted.length-1,(int)Math.ceil(p/100.0*sorted.length)-1)];
        return "count="+sorted.length+" min="+sorted[0]+" p50="+at.apply(50)+" p90="+at.apply(90)+" p99="+at.apply(99)
                +" max="+sorted[sorted.length-1]+" mean="+(total/sorted.length)+" total="+total;
    }
    @Test void staticLeafCostDiagnostic() {
        // 不gating：不開lease、不斷言數量或時間，也不用Assumptions跳過；輸出寫進system-out，供I-1評估static leaf是否移出lease。
        // pass0是本測試第一次擷取每個block（同class其他測試可能已先擷取stone、chest、oak_stairs、obsidian）；pass1是同一JVM的暖值。
        var registries=new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK));
        var entries=new java.util.ArrayList<net.minecraft.registry.entry.RegistryEntry.Reference<Block>>();
        Registries.BLOCK.streamEntries().forEach(entries::add);
        int count=entries.size();long[] cold=new long[count],warm=new long[count];long[] bytes=new long[count];
        String[] result=new String[count];int[] properties=new int[count],values=new int[count];
        for(int pass=0;pass<2;pass++) {
            for(int i=0;i<count;i++) {
                var entry=entries.get(i);var key=entry.registryKey();var node=node(key.getValue().toString());
                var graph=new Minecraft121SettingsGraph();var leaves=new Minecraft121RegistryLeafContracts(graph,registries);
                String code="ACCEPTED";NbtCompound payload=null;
                long start=System.nanoTime();
                try {payload=leaves.block(node,key,entry);}
                catch(Minecraft121SettingsGraph.Failure failure){code=failure.getMessage();}
                catch(RuntimeException other){code=other.getClass().getName();}
                long elapsed=System.nanoTime()-start;
                if(pass==0) {
                    cold[i]=elapsed;result[i]=code;
                    if(payload!=null) {
                        bytes[i]=payloadBytes(graph.finish(),node);var domains=payload.getCompound("PropertyDomains");properties[i]=domains.getSize();
                        for(String name:domains.getKeys())values[i]+=domains.getList(name,NbtElement.STRING_TYPE).size();
                    }
                } else warm[i]=elapsed;
            }
        }
        var codes=new java.util.TreeMap<String,Integer>();var namespaces=new java.util.TreeMap<String,Integer>();
        long totalBytes=0;int totalProperties=0,totalValues=0;
        for(int i=0;i<count;i++) {
            var id=entries.get(i).registryKey().getValue();
            codes.merge(result[i],1,Integer::sum);namespaces.merge(id.getNamespace(),1,Integer::sum);
            totalBytes+=bytes[i];totalProperties+=properties[i];totalValues+=values[i];
            System.out.println("D1B1_LEAF_COST_ENTRY id="+id+" result="+result[i]+" coldNs="+cold[i]+" warmNs="+warm[i]
                    +" payloadBytes="+bytes[i]+" properties="+properties[i]+" values="+values[i]);
        }
        System.out.println("D1B1_LEAF_COST_SUMMARY leaves="+count+" totalPayloadBytes="+totalBytes+" properties="+totalProperties+" values="+totalValues);
        System.out.println("D1B1_LEAF_COST_CODES "+codes);
        System.out.println("D1B1_LEAF_COST_NAMESPACES "+namespaces);
        // system-out走測試JVM的平台編碼，標記一律用ASCII（與既有TEMPLATE_CANONICAL_L等相同）。
        System.out.println("D1B1_LEAF_COST_FIRST_LEAF_NS id="+entries.get(0).registryKey().getValue()+" coldNs="+cold[0]+" note=first-leaf-of-this-test");
        System.out.println("D1B1_LEAF_COST_COLD_NS "+stats(cold));
        System.out.println("D1B1_LEAF_COST_WARM_NS "+stats(warm));
        System.out.println("D1B1_LEAF_COST_REFERENCE owPaletteOccurrences=17262 owPaletteUniqueBlocks=375 owAllConsumerBlocks=924 unionPaletteUniqueBlocks=401"
                +" source=B28-census-constants-not-measured-here");
        itemLeafCostDiagnostic();
    }
    /**
     * D1b-2 ITEM段，同樣不gating。每個item的leaf結果碼與冷暖耗時；另對minecraft namespace每個item的每個default component，
     * 以只含該component的map走itemPayload（DIAMOND當feature載體），得到逐型別判定。REJECTED_TYPES是「含該型別被拒的item數」，
     * ACCEPTED_TYPES是「該型別被接受的component數」。
     */
    private static void itemLeafCostDiagnostic() {
        var registries=itemRegistries();
        var entries=new java.util.ArrayList<net.minecraft.registry.entry.RegistryEntry.Reference<Item>>();
        Registries.ITEM.streamEntries().forEach(entries::add);
        int count=entries.size();long[] cold=new long[count],warm=new long[count],bytes=new long[count];String[] result=new String[count];
        for(int pass=0;pass<2;pass++) {
            for(int i=0;i<count;i++) {
                var entry=entries.get(i);var key=entry.registryKey();var node=itemNode(key.getValue().toString());
                var graph=new Minecraft121SettingsGraph();var leaves=new Minecraft121RegistryLeafContracts(graph,registries);
                String code="ACCEPTED";NbtCompound payload=null;
                long start=System.nanoTime();
                try {payload=leaves.item(node,key,entry);}
                catch(Minecraft121SettingsGraph.Failure failure){code=failure.getMessage();}
                catch(RuntimeException other){code=other.getClass().getName();}
                long elapsed=System.nanoTime()-start;
                if(pass==0){cold[i]=elapsed;result[i]=code;if(payload!=null)bytes[i]=payloadBytes(graph.finish(),node);}
                else warm[i]=elapsed;
            }
        }
        var codes=new java.util.TreeMap<String,Integer>();var namespaces=new java.util.TreeMap<String,Integer>();
        var rejectedTypes=new java.util.TreeMap<String,Integer>();var acceptedTypes=new java.util.TreeMap<String,Integer>();
        var rejectedTypesByCode=new java.util.TreeMap<String,java.util.TreeMap<String,Integer>>();
        long totalBytes=0;int accepted=0;var acceptedCold=new java.util.ArrayList<Long>();var acceptedWarm=new java.util.ArrayList<Long>();
        for(int i=0;i<count;i++) {
            var id=entries.get(i).registryKey().getValue();
            codes.merge(result[i],1,Integer::sum);namespaces.merge(id.getNamespace(),1,Integer::sum);totalBytes+=bytes[i];
            if(result[i].equals("ACCEPTED")){accepted++;acceptedCold.add(cold[i]);acceptedWarm.add(warm[i]);}
            var rejected=new java.util.TreeSet<String>();
            if(id.getNamespace().equals("minecraft"))for(var component:entries.get(i).value().getComponents()) {
                String type=String.valueOf(Registries.DATA_COMPONENT_TYPE.getId(component.type()));
                String verdict;
                try {itemPayload(Items.DIAMOND,single(component));verdict="ACCEPTED";}
                catch(Minecraft121SettingsGraph.Failure failure){verdict=failure.getMessage();}
                catch(RuntimeException other){verdict=other.getClass().getName();}
                if(verdict.equals("ACCEPTED"))acceptedTypes.merge(type,1,Integer::sum);else rejected.add(type);
            }
            for(String type:rejected)rejectedTypes.merge(type,1,Integer::sum);
            if(!result[i].equals("ACCEPTED"))for(String type:rejected)
                rejectedTypesByCode.computeIfAbsent(result[i],k->new java.util.TreeMap<>()).merge(type,1,Integer::sum);
            System.out.println("D1B2_ITEM_LEAF_COST_ENTRY id="+id+" result="+result[i]+" coldNs="+cold[i]+" warmNs="+warm[i]
                    +" payloadBytes="+bytes[i]+" rejectedTypes="+rejected);
        }
        System.out.println("D1B2_ITEM_LEAF_COST_SUMMARY leaves="+count+" accepted="+accepted+" rejected="+(count-accepted)+" totalPayloadBytes="+totalBytes);
        System.out.println("D1B2_ITEM_LEAF_COST_CODES "+codes);
        System.out.println("D1B2_ITEM_LEAF_COST_NAMESPACES "+namespaces);
        System.out.println("D1B2_ITEM_LEAF_COST_REJECTED_TYPES itemsContainingRejectedType="+rejectedTypes);
        System.out.println("D1B2_ITEM_LEAF_COST_REJECTED_TYPES_BY_CODE "+rejectedTypesByCode);
        System.out.println("D1B2_ITEM_LEAF_COST_ACCEPTED_TYPES acceptedComponents="+acceptedTypes);
        System.out.println("D1B2_ITEM_LEAF_COST_FIRST_LEAF_NS id="+entries.get(0).registryKey().getValue()+" coldNs="+cold[0]+" note=first-item-leaf-of-this-test");
        System.out.println("D1B2_ITEM_LEAF_COST_COLD_NS "+stats(cold));
        System.out.println("D1B2_ITEM_LEAF_COST_WARM_NS "+stats(warm));
        System.out.println("D1B2_ITEM_LEAF_COST_ACCEPTED_COLD_NS "+stats(acceptedCold.stream().mapToLong(Long::longValue).toArray()));
        System.out.println("D1B2_ITEM_LEAF_COST_ACCEPTED_WARM_NS "+stats(acceptedWarm.stream().mapToLong(Long::longValue).toArray()));
        System.out.println("D1B2_ITEM_LEAF_COST_REFERENCE templateItemUnion=10 templateItemBaselineOnly=7 owAllConsumerItems=290 unionAllConsumerItems=314"
                +" source=B28-census-constants-not-measured-here");
    }
}
