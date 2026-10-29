package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.Minecraft121RegistryLeafContracts.*;
import static dev.quantumchamber.universe.Minecraft121RegistryLeafTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.util.Identifier;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * C1d-D1b-1 BLOCK與D1b-2 ITEM static registry leaf的單元測試與成本診斷（含D1b-3-0 Step0的殘餘風險收斂、D1b-3a的lease
 * registries identity與8型literal／空DEFAULT component，以及D1b-3b的單一分派型別契約、ITEM leaf出邊與ATTRIBUTE／STATUS_EFFECT leaf）。
 * 只讀全域static registry：不註冊Block／Item，不populate tag；不引用ModBlocks／ModItems。局部物件只有新建的局部registry（不登記到
 * Registries.REGISTRIES）、foreign的EntityAttribute／StatusEffect子類（建構子不碰registry），以及以Unsafe配置、不跑建構子的foreign
 * Item（不呼叫Registries.ITEM.createEntry）。唯一的instance欄位變更是itemLeafDefaultComponentMutationIsCapturedAndRestored（設計§5.5
 * 選配，條件寫在該測試），finally以identity還原並assertSame。共用的fixture組裝（ROOT、各REGISTRY node、ITEM leaf的lease registries、
 * singleItemTemplate、canonicalNodes）在Minecraft121RegistryLeafTestFixtures。
 */
class Minecraft121RegistryLeafContractsTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();net.minecraft.Bootstrap.initialize();
    }
    private static void rejects(String code,org.junit.jupiter.api.function.Executable action) {
        assertEquals(code,assertThrows(Minecraft121SettingsGraph.Failure.class,action).getMessage());
    }
    private static NbtList strings(String... values) {
        var list=new NbtList();for(String value:values)list.add(NbtString.of(value));return list;
    }
    /** 以registries建LeafContracts；ITEM leaf的出邊依序收進emitted（from與ref成對），其他leaf不呼叫sink。 */
    private static Minecraft121RegistryLeafContracts leaves(Minecraft121SettingsGraph graph,DynamicRegistryManager registries,
            java.util.List<java.util.Map.Entry<Minecraft121SettingsGraph.Key,Minecraft121ItemComponentContracts.Ref>> emitted) {
        return new Minecraft121RegistryLeafContracts(graph,registries,(from,ref)->emitted.add(java.util.Map.entry(from,ref)));
    }
    private static Minecraft121RegistryLeafContracts leaves(Minecraft121SettingsGraph graph,DynamicRegistryManager registries) {
        return leaves(graph,registries,new java.util.ArrayList<>());
    }
    /** 走production的block(...)接線：registries只放全域static BLOCK registry，entry以key自registry解析（同closure）。 */
    private static NbtCompound leaf(Block block) {
        var key=block.getRegistryEntry().registryKey();var entry=Registries.BLOCK.getEntry(key).orElseThrow();
        var graph=new Minecraft121SettingsGraph();
        var leaves=leaves(graph,new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK)));
        var payload=leaves.block(blockNode(key.getValue().toString()),key,entry);
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
        // registries用ITEM leaf的lease聯集（含BLOCK）：ITEM leaf的registry identity排在node檢查之前。
        var registries=withItemLeafRegistries();
        var stoneKey=Blocks.STONE.getRegistryEntry().registryKey();var stone=Registries.BLOCK.getEntry(stoneKey).orElseThrow();
        var diamondKey=Items.DIAMOND.getRegistryEntry().registryKey();var diamond=Registries.ITEM.getEntry(diamondKey).orElseThrow();
        java.util.function.Function<Minecraft121SettingsGraph.Key,org.junit.jupiter.api.function.Executable> block=
                n->()->leaves(new Minecraft121SettingsGraph(),registries).block(n,stoneKey,stone);
        java.util.function.Function<Minecraft121SettingsGraph.Key,org.junit.jupiter.api.function.Executable> item=
                n->()->leaves(new Minecraft121SettingsGraph(),registries).item(n,diamondKey,diamond);
        var kind=Minecraft121SettingsGraph.Kind.REGISTRY;
        assertAll(
                ()->assertDoesNotThrow(block.apply(blockNode("minecraft:stone")),"正向對照：BLOCK"),
                ()->assertDoesNotThrow(item.apply(itemNode("minecraft:diamond")),"正向對照：ITEM"),
                ()->assertAll("subcase=BLOCK id不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",block.apply(blockNode("minecraft:granite")))),
                ()->assertAll("subcase=BLOCK registry不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        block.apply(new Minecraft121SettingsGraph.Key(kind,"minecraft:item","minecraft:stone")))),
                ()->assertAll("subcase=BLOCK kind不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        block.apply(new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.TAG,"minecraft:block","minecraft:stone")))),
                ()->assertAll("subcase=ITEM id不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",item.apply(itemNode("minecraft:emerald")))),
                ()->assertAll("subcase=ITEM registry不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",item.apply(blockNode("minecraft:diamond")))),
                ()->assertAll("subcase=ITEM kind不符",()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",
                        item.apply(new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.RESOLVED,"minecraft:item","minecraft:diamond")))));
    }

    // ===== C1d-D1b-2：ITEM static registry leaf =====
    /** ITEM leaf的lease形狀registries：fixture獨立列出的全域static registry聯集（ITEM、DATA_COMPONENT_TYPE、BLOCK、ATTRIBUTE、STATUS_EFFECT）。 */
    private static DynamicRegistryManager itemRegistries() {
        return withItemLeafRegistries();
    }
    /**
     * ITEM seam的單一入口，測試只經這裡呼叫seam。D1b-3a起seam是instance itemCapture：以registries建LeafContracts（建構時建好
     * lease ops），codec之前先證明固定static registry identity。graph只是建構參數，seam不寫入它，也不呼叫sink。
     */
    private static Minecraft121RegistryLeafContracts.ItemCapture capture(DynamicRegistryManager registries,Item item,ComponentMap components) {
        return leaves(new Minecraft121SettingsGraph(),registries).itemCapture(item,components);
    }
    private static Minecraft121RegistryLeafContracts.ItemCapture capture(Item item,ComponentMap components) {
        return capture(itemRegistries(),item,components);
    }
    private static NbtCompound seam(DynamicRegistryManager registries,Item item,ComponentMap components) {
        return capture(registries,item,components).payload();
    }
    private static NbtCompound seam(Item item,ComponentMap components) {
        return seam(itemRegistries(),item,components);
    }
    /** 走production的item(...)接線：registries為itemRegistries()，entry以key自全域ITEM registry解析（同closure）；出邊收進emitted。 */
    private static NbtCompound itemLeaf(Item item,
            java.util.List<java.util.Map.Entry<Minecraft121SettingsGraph.Key,Minecraft121ItemComponentContracts.Ref>> emitted) {
        var key=item.getRegistryEntry().registryKey();var entry=Registries.ITEM.getEntry(key).orElseThrow();
        var graph=new Minecraft121SettingsGraph();
        var payload=leaves(graph,itemRegistries(),emitted).item(itemNode(key.getValue().toString()),key,entry);
        assertTrue(graph.finish().length>0,"payload須已寫入圖");
        return payload;
    }
    private static NbtCompound itemLeaf(Item item) {
        return itemLeaf(item,new java.util.ArrayList<>());
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
        // D1b-3a起potion（potion_contents）與chest（container）的空DEFAULT已審，移到itemLeafAcceptsLiteralAndEmptyDefaultComponents；
        // 拒絕面改用仍未支援的型別：stored_enchantments（ENCHANTMENT是dynamic）與bees（非空值帶entity NBT，屬D2）。
        // D1b-3b起golden_apple（food）與非空attribute_modifiers已審，移到itemLeafCapturesStaticEntryRefsFromReviewedDefaults；
        // 帶tool tag rule的item（iron_axe、iron_sword、shears）屬D1b-3c，在那之前仍拒（D1b-3設計§3.1的3b列「iron_sword仍拒」）。
        String code="UNSUPPORTED_ITEM_DEFAULT_COMPONENT";
        java.util.function.Predicate<Item> tagRule=item->item.getComponents().get(DataComponentTypes.TOOL).rules().stream()
                .anyMatch(rule->rule.blocks().getTagKey().isPresent());
        assertAll(
                ()->assertAll("subcase=iron_axe（tool tag rule；另帶非空attribute_modifiers）",
                        ()->assertTrue(tagRule.test(Items.IRON_AXE),"fixture前提：有tag rule"),
                        ()->rejects(code,()->itemLeaf(Items.IRON_AXE)),
                        ()->rejects(code,()->seam(Items.IRON_AXE,single(component(Items.IRON_AXE,DataComponentTypes.TOOL))))),
                ()->assertAll("subcase=iron_sword（direct cobweb加上#sword_efficient）",
                        ()->assertTrue(tagRule.test(Items.IRON_SWORD),"fixture前提：有tag rule"),
                        ()->rejects(code,()->itemLeaf(Items.IRON_SWORD)),
                        ()->rejects(code,()->seam(Items.IRON_SWORD,single(component(Items.IRON_SWORD,DataComponentTypes.TOOL))))),
                ()->assertAll("subcase=shears（direct、#leaves、#wool）",
                        ()->assertTrue(tagRule.test(Items.SHEARS),"fixture前提：有tag rule"),
                        ()->rejects(code,()->itemLeaf(Items.SHEARS))),
                ()->assertAll("subcase=enchanted_book（stored_enchantments；另帶已審的enchantment_glint_override）",
                        ()->assertTrue(Items.ENCHANTED_BOOK.getComponents().contains(DataComponentTypes.STORED_ENCHANTMENTS),"fixture前提"),
                        ()->rejects(code,()->itemLeaf(Items.ENCHANTED_BOOK)),
                        ()->rejects(code,()->seam(Items.ENCHANTED_BOOK,single(component(Items.ENCHANTED_BOOK,DataComponentTypes.STORED_ENCHANTMENTS))))),
                ()->assertAll("subcase=bee_nest（bees）",
                        ()->assertTrue(Items.BEE_NEST.getComponents().contains(DataComponentTypes.BEES),"fixture前提"),
                        ()->rejects(code,()->itemLeaf(Items.BEE_NEST))));
    }
    private static String verdict(org.junit.jupiter.api.function.Executable action,String rejectedCode) {
        try {action.execute();return "ACCEPTED";}
        catch(Minecraft121SettingsGraph.Failure failure) {return failure.getMessage().equals(rejectedCode)?"REJECTED":failure.getMessage();}
        catch(Throwable other) {return other.getClass().getName();}
    }
    @Test void itemDefaultWhitelistCoversTemplateEffectiveWhitelist() {
        // 一邊是leaf的default白名單，一邊是TemplateValueContracts.capture對「只含該item的合成chest模板」的有效component白名單；
        // reference consumer是stub，只比accept／reject，不finish，也不填假payload。chest（container）是設計外補充。
        // D1b-3設計§3.3（controller C13）：模板端白名單在D2b之前不擴充，所以只斷言「模板ACCEPTED ⇒ leaf ACCEPTED」，另以預期分歧表
        // 列出leaf接受、模板仍拒的item。每個D1b-3子片同步更新分歧表；D2b須把TemplateValueContracts.components()收斂到同一套已審型別契約。
        var registries=withItemLeafRegistries();
        var items=java.util.List.of(Items.DIAMOND,Items.ENDER_PEARL,Items.WATER_BUCKET,Items.GOLDEN_APPLE,Items.WOODEN_SHOVEL,
                Items.IRON_AXE,Items.POTION,Items.CHEST);
        var leaf=new java.util.LinkedHashMap<String,String>();var template=new java.util.LinkedHashMap<String,String>();
        for(var item:items) {
            var id=Registries.ITEM.getId(item);
            leaf.put(id.toString(),verdict(()->itemLeaf(item),"UNSUPPORTED_ITEM_DEFAULT_COMPONENT"));
            var node=new Minecraft121SettingsGraph.Key(Minecraft121SettingsGraph.Kind.TEMPLATE,"minecraft:structure","test:whitelist/"+id.getPath());
            template.put(id.toString(),verdict(()->new Minecraft121TemplateValueContracts(new Minecraft121SettingsGraph(),registries,(occurrence,key)->{})
                    .capture(node,singleItemTemplate("minecraft:chest",id.toString())),"UNSUPPORTED_TEMPLATE_COMPONENT"));
        }
        var expectedTemplate=new java.util.LinkedHashMap<String,String>();
        for(var item:items)expectedTemplate.put(Registries.ITEM.getId(item).toString(),"REJECTED");
        for(String id:java.util.List.of("minecraft:diamond","minecraft:ender_pearl","minecraft:water_bucket"))expectedTemplate.put(id,"ACCEPTED");
        // 預期分歧表（leaf ACCEPTED、模板REJECTED）：D1b-3a＝potion（potion_contents空DEFAULT）、chest（container空DEFAULT）；
        // D1b-3b＝golden_apple（food）。wooden_shovel與iron_axe的tool帶tag rule，屬D1b-3c，leaf仍拒（設計§3.3把它們列在3b是
        // 漏看了tag rule）。
        var expectedDivergence=java.util.List.of("minecraft:golden_apple","minecraft:potion","minecraft:chest");
        var uncovered=new java.util.ArrayList<String>();var divergence=new java.util.ArrayList<String>();
        for(var id:leaf.keySet()) {
            if(template.get(id).equals("ACCEPTED")&&!leaf.get(id).equals("ACCEPTED"))uncovered.add(id);
            if(leaf.get(id).equals("ACCEPTED")&&template.get(id).equals("REJECTED"))divergence.add(id);
        }
        assertAll(
                ()->assertEquals(expectedTemplate,template,"模板端判定（D2b之前不變）：接受與拒絕兩邊都有"),
                ()->assertEquals(java.util.List.of(),uncovered,"模板ACCEPTED ⇒ leaf ACCEPTED"),
                ()->assertEquals(expectedDivergence,divergence,"預期分歧表"),
                ()->assertTrue(leaf.values().stream().allMatch(v->v.equals("ACCEPTED")||v.equals("REJECTED")),
                        "leaf結果只有ACCEPTED或白名單拒絕："+leaf));
    }
    @Test void itemLeafDefaultComponentsChangePayloadWithoutGlobalMutation() {
        var diamond=Items.DIAMOND;var original=diamond.getComponents();
        var changed=ComponentMap.builder().addAll(original).add(DataComponentTypes.MAX_STACK_SIZE,16).build();
        var base=seam(diamond,original);var other=seam(diamond,changed);
        // D1b-3b起food已審，未審面改用stored_enchantments（空DEFAULT也不在已審表；ENCHANTMENT是dynamic）。
        var withStored=ComponentMap.builder().addAll(original).add(DataComponentTypes.STORED_ENCHANTMENTS,
                net.minecraft.component.type.ItemEnchantmentsComponent.DEFAULT).build();
        assertAll(
                ()->assertNotEquals(base,other,"同一id改了default component值，payload須跟著改變"),
                ()->assertEquals(16,other.getCompound("DefaultComponents").getInt("minecraft:max_stack_size")),
                ()->assertEquals(base.get("RequiredFeatures"),other.get("RequiredFeatures")),
                ()->assertEquals(itemLeaf(diamond),base,"production接線傳入getComponents()，結果須與seam相同"),
                ()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(diamond,withStored)));
        assertSame(original,diamond.getComponents(),"seam測試不得改動全域狀態");
    }
    @Test void blockItemLeafExecutedOwnersIncludeBlockAndHasNoBlockEdge() {
        // 斷言的是executedOwnerClasses的輸出；item(...)與itemCapture開頭都對它逐項requireNativeSource。見證3把「不回傳block class」
        // 當突變，由下方等值斷言抓到。殘餘風險：「兩處檢查同時被刪除」（連同item class檢查）沒有任何測試能抓到：測試不能建立或註冊
        // foreign Item／BlockItem（Item.<init>會寫入全域registry），靠spec review逐行確認。
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
                    var payload=leaves(graph,itemRegistries()).item(node,key,entry);
                    var expected=new Minecraft121SettingsGraph();expected.payload(node,payload);
                    assertArrayEquals(expected.finish(),graph.finish(),"只有ITEM node本身，沒有指向BLOCK的edge");
                });
    }
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
        // 這是編譯期的直接引用：Fabric API若搬移或改名ItemAccessor，壞掉的是整個test source set的編譯（所有JUnit都跑不起來），不只本測試。
        // 條件：單一method、單執行緒；變更前暖機；時序固定為「舊closure在變更前drain → 變更 → 舊圖第一次finish → 新closure（不開lease）
        // drain後finish → finally以identity還原」，最後assertSame。變更期間不建立ItemStack，也不呼叫其他API。
        var item=Items.DIAMOND;var id="minecraft:diamond";var key=item.getRegistryEntry().registryKey();var original=item.getComponents();
        var changed=ComponentMap.builder().addAll(original).add(DataComponentTypes.MAX_STACK_SIZE,16).build();
        // setup：mixin沒有套用時在這裡以setup失敗結束，不會在try／finally的cast丟CCE而蓋掉原因。
        var accessor=assertInstanceOf(net.fabricmc.fabric.mixin.item.ItemAccessor.class,item,"setup：ItemAccessor mixin");
        // 暖機：變更前對同一item完整走一次seam（原值與變更值）、leaf capture與closure drain／finish。
        var expectedOriginal=expectedItemGraph(id,seam(item,original));
        var expectedChanged=expectedItemGraph(id,seam(item,changed));
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
                accessor.setComponents(changed);
                oldBytes=oldGraph.finish();
                newClosure.drain();newBytes=newGraph.finish();
            } finally {
                accessor.setComponents(original);
            }
            assertSame(original,item.getComponents(),"finally已以identity還原原物件");
            assertAll(
                    ()->assertArrayEquals(expectedOriginal,oldBytes,"舊圖在變更前已擷取，第一次finish不受變更影響"),
                    ()->assertArrayEquals(expectedChanged,newBytes,"新圖擷取到變更後的default component"));
        }
    }

    // ===== C1d-D1b-3-0（Step0）：D1b-2殘餘風險的具名RED與拒絕原因gating；只用seam與測試類別，不改動全域狀態 =====
    /** 會說謊的巢狀List：size()==0、isEmpty()==true（AbstractList預設看size），iterator()卻吐出一行Text。 */
    private static final class LyingLines extends java.util.AbstractList<net.minecraft.text.Text> {
        @Override public net.minecraft.text.Text get(int index){throw new IndexOutOfBoundsException(index);}
        @Override public int size(){return 0;}
        @Override public java.util.Iterator<net.minecraft.text.Text> iterator(){
            return java.util.List.<net.minecraft.text.Text>of(net.minecraft.text.Text.literal("hidden")).iterator();
        }
    }
    /** 自稱size()==0（通過LoreComponent建構子的256上限檢查），但isEmpty()／iterator()／get()一律丟ISE。 */
    private static final class ThrowingLines extends java.util.AbstractList<net.minecraft.text.Text> {
        @Override public net.minecraft.text.Text get(int index){throw new IllegalStateException("throwing lore get");}
        @Override public int size(){return 0;}
        @Override public boolean isEmpty(){throw new IllegalStateException("throwing lore isEmpty");}
        @Override public java.util.Iterator<net.minecraft.text.Text> iterator(){throw new IllegalStateException("throwing lore iterator");}
    }
    /** 測試自訂的foreign ComponentMap：第一次iterator()只吐first的component，之後的iterator()改吐later（兩次迭代內容不同）。 */
    private static final class ShiftingComponentMap implements ComponentMap {
        private final ComponentMap first,later;private int iterations;
        ShiftingComponentMap(ComponentMap first,ComponentMap later){this.first=first;this.later=later;}
        @Override public <T> T get(net.minecraft.component.ComponentType<? extends T> type){return later.get(type);}
        @Override public java.util.Set<net.minecraft.component.ComponentType<?>> getTypes(){return later.getTypes();}
        @Override public java.util.Iterator<net.minecraft.component.Component<?>> iterator(){return (iterations++==0?first:later).iterator();}
    }
    @Test void itemLeafRejectsLyingNestedListAfterRoundtrip() {
        // S0-1 RED①：原生LoreComponent放在原生SimpleComponentMap裡；巢狀List自稱為空，codec的迭代卻吐出一行。
        // LoreComponent(List)以Lists.transform建立styledLines view，所以lines()與styledLines()的isEmpty都會被騙。
        var lore=new LoreComponent(new LyingLines());
        var components=single(new net.minecraft.component.Component<>(DataComponentTypes.LORE,lore));
        assertAll("fixture前提",
                ()->assertTrue(lore.lines().isEmpty()&&lore.styledLines().isEmpty(),"兩個isEmpty都回報為空"),
                ()->assertTrue(lore.lines().iterator().hasNext(),"iterator卻吐出元素"),
                ()->assertEquals(1,((NbtCompound)ComponentMap.CODEC.encodeStart(NbtOps.INSTANCE,components).getOrThrow())
                        .getList("minecraft:lore",NbtElement.STRING_TYPE).size(),"原生codec（plain NbtOps）實際編出一行lore"),
                ()->assertDoesNotThrow(()->requireNativeSource(components.getClass()),"外層是原生容器"));
        rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(Items.DIAMOND,components));
    }
    @Test void itemLeafRejectsForeignComponentMapContainer() {
        // S0-1 RED②：步驟6的白名單與codec各迭代容器一次；foreign容器可以讓兩次內容不同（第二次多出一個component）。
        // D1b-3b起food已審（原本第二次多出的是food），所以「重建map的再驗」子案改用仍未審的stored_enchantments；多出已審food的情況
        // 另列一個子案，由原值與重建值的ref比對擋下。
        var base=Items.DIAMOND.getComponents();
        var withStored=ComponentMap.builder().addAll(base).add(DataComponentTypes.STORED_ENCHANTMENTS,
                net.minecraft.component.type.ItemEnchantmentsComponent.DEFAULT).build();
        var withFood=ComponentMap.builder().addAll(base).add(DataComponentTypes.FOOD,component(Items.GOLDEN_APPLE,DataComponentTypes.FOOD).value()).build();
        var probe=new ShiftingComponentMap(base,withStored);
        var firstTypes=new java.util.HashSet<net.minecraft.component.ComponentType<?>>();for(var c:probe)firstTypes.add(c.type());
        var laterTypes=new java.util.HashSet<net.minecraft.component.ComponentType<?>>();for(var c:probe)laterTypes.add(c.type());
        assertAll("fixture前提",
                ()->assertFalse(firstTypes.contains(DataComponentTypes.STORED_ENCHANTMENTS),"第一次迭代不含stored_enchantments"),
                ()->assertTrue(laterTypes.contains(DataComponentTypes.STORED_ENCHANTMENTS),"第二次迭代含stored_enchantments"));
        // D1b-3-0 quality M1：容器source檢查只擋直接foreign的容器；原生包裝ComponentMapImpl（changedComponents為空時iterator直接
        // 委派base）能通過，它的內容一致性由roundtrip後重建map的再驗保證。codec那次迭代吐出stored_enchantments，重建map含它，再驗時拒絕。
        var wrapped=new net.minecraft.component.ComponentMapImpl(new ShiftingComponentMap(base,withStored));
        // D1b-3b（設計審查M2）：第二次迭代多出的是已審的food，重建map的再驗會通過；但步驟6自原值抽出的ref（沒有food）與重建值的ref
        // （golden_apple的兩個effect）不同，以NATIVE_CODEC_ROUNDTRIP_CHANGED拒絕，不會讓「原值迭代看不到的ref」靜默進圖。
        var wrappedFood=new net.minecraft.component.ComponentMapImpl(new ShiftingComponentMap(base,withFood));
        // D1b-3c-0（quality Q-M2）：兩次迭代的food各帶不同的effect type（件數與path相同、key不同）是內容改變，不是owner不符，所以是
        // NATIVE_CODEC_ROUNDTRIP_CHANGED；只有同key、不同物件才是REGISTRY_ENTRY_OWNER_MISMATCH（itemLeafRejectsForeignReferenceWithSameId）。
        // 原值的effect type是未綁定key的intrusive Reference時（registryKey()丟ISE），同樣是key不同；intrusive(...)只建新物件，不寫入registry。
        java.util.function.Function<RegistryEntry<net.minecraft.entity.effect.StatusEffect>,ComponentMap> foodWith=effect->ComponentMap.builder()
                .addAll(base).add(DataComponentTypes.FOOD,new FoodComponent(1,0.1f,false,1.6f,Optional.empty(),
                        List.of(new FoodComponent.StatusEffectEntry(new StatusEffectInstance(effect,100),1f)))).build();
        var shiftedEffect=new net.minecraft.component.ComponentMapImpl(new ShiftingComponentMap(foodWith.apply(StatusEffects.SPEED),
                foodWith.apply(StatusEffects.REGENERATION)));
        var unbound=RegistryEntry.Reference.intrusive(Registries.STATUS_EFFECT.getEntryOwner(),StatusEffects.SPEED.value());
        var unboundFirst=new net.minecraft.component.ComponentMapImpl(new ShiftingComponentMap(foodWith.apply(unbound),
                foodWith.apply(StatusEffects.SPEED)));
        assertThrows(IllegalStateException.class,unbound::registryKey,"fixture前提：intrusive Reference沒有綁定key");
        assertAll(
                ()->assertAll("subcase=直接foreign容器",()->rejects("NATIVE_DEFINITION_SOURCE",
                        ()->seam(Items.DIAMOND,new ShiftingComponentMap(base,withStored)))),
                ()->assertAll("subcase=原生ComponentMapImpl包裝foreign容器（第二次多出未審型別）",
                        ()->assertDoesNotThrow(()->requireNativeSource(wrapped.getClass()),"fixture前提：外層是原生容器"),
                        ()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(Items.DIAMOND,wrapped))),
                ()->assertAll("subcase=原生ComponentMapImpl包裝foreign容器（第二次多出帶ref的已審food）",
                        ()->rejects("NATIVE_CODEC_ROUNDTRIP_CHANGED",()->seam(Items.DIAMOND,wrappedFood))),
                ()->assertAll("subcase=原生ComponentMapImpl包裝foreign容器（兩次迭代的food effect type不同：speed→regeneration）",
                        ()->rejects("NATIVE_CODEC_ROUNDTRIP_CHANGED",()->seam(Items.DIAMOND,shiftedEffect))),
                ()->assertAll("subcase=原生ComponentMapImpl包裝foreign容器（原值的effect type是未綁定key的intrusive Reference）",()->{
                    var failure=assertThrows(Minecraft121SettingsGraph.Failure.class,()->seam(Items.DIAMOND,unboundFirst));
                    assertEquals("NATIVE_CODEC_ROUNDTRIP_CHANGED",failure.getMessage());
                    assertInstanceOf(IllegalStateException.class,failure.getCause(),"原因是未綁定key的registryKey()，以initCause保留");
                    assertFalse(failure.getCause() instanceof Minecraft121SettingsGraph.Failure,"cause不是另一個Failure");
                }));
    }
    @Test void itemLeafNestedListExceptionBecomesNamedCode() {
        // S0-1 RED③：foreign容器會先在容器source檢查被擋，所以丟例外的是原生LoreComponent內的巢狀List，外層是原生SimpleComponentMap。
        var components=single(new net.minecraft.component.Component<>(DataComponentTypes.LORE,new LoreComponent(new ThrowingLines())));
        assertDoesNotThrow(()->requireNativeSource(components.getClass()),"fixture前提：外層是原生容器");
        var failure=assertThrows(Minecraft121SettingsGraph.Failure.class,()->seam(Items.DIAMOND,components));
        // Failure本身也是IllegalStateException的子類，所以以訊息把cause釘在巢狀List的isEmpty，並排除Failure包Failure。
        assertAll(
                ()->assertEquals("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",failure.getMessage()),
                ()->assertEquals("throwing lore isEmpty",failure.getCause().getMessage(),"原因以initCause保留"),
                ()->assertFalse(failure.getCause() instanceof Minecraft121SettingsGraph.Failure,"cause不是另一個Failure"));
    }
    @Test void spawnEggItemExecutedOwnersIncludeEntityTypeClass() {
        // S0-2 RED：原生SpawnEggItem的getRequiredFeatures委派type.getRequiredFeatures()（EntityType的method可被覆寫），
        // 所以leaf擷取時也會執行該EntityType的class。委派本身的證據是javap：SpawnEggItem.getRequiredFeatures offset 0-7為
        // getfield type → invokevirtual EntityType.getRequiredFeatures（D1b-3設計§5 S0-2，class SHA256 ff9846974b03…）。
        var egg=(net.minecraft.item.SpawnEggItem)Items.PIG_SPAWN_EGG;
        var type=egg.getEntityType(net.minecraft.item.ItemStack.EMPTY);
        assertAll("fixture前提",
                ()->assertSame(net.minecraft.entity.EntityType.PIG,type,"ENTITY_DATA為空時回傳建構時的type"),
                ()->assertSame(type.getRequiredFeatures(),egg.getRequiredFeatures(),
                        "一致性檢查（vanilla的EntityType與Item共用同一個VANILLA_FEATURES實例，不鑑別委派；委派由javap證明）"));
        assertEquals(java.util.List.of(net.minecraft.item.SpawnEggItem.class,net.minecraft.entity.EntityType.class),
                executedOwnerClasses(Items.PIG_SPAWN_EGG),"SpawnEggItem的EntityType class也會被執行");
    }
    /**
     * 測試端獨立列出的已審ITEM default型別與值條件（不呼叫production的判斷），依子片分表。以method建立，避免在@BeforeAll的
     * Bootstrap之前觸發DataComponentTypes的class init。D1b-3各子片擴充leaf白名單時，同步新增該子片的表。
     *
     * <p>D1b-2共8型：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（值為Integer）、RARITY（值為Rarity），其中ENCHANTMENTS、LORE、
     * ATTRIBUTE_MODIFIERS這3型另帶「必須為空」條件。
     */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> d1b2ReviewedItemDefaults() {
        return java.util.Map.of(
                DataComponentTypes.MAX_STACK_SIZE,value->value instanceof Integer,
                DataComponentTypes.MAX_DAMAGE,value->value instanceof Integer,
                DataComponentTypes.DAMAGE,value->value instanceof Integer,
                DataComponentTypes.REPAIR_COST,value->value instanceof Integer,
                DataComponentTypes.RARITY,value->value instanceof net.minecraft.util.Rarity,
                DataComponentTypes.ENCHANTMENTS,value->value instanceof net.minecraft.component.type.ItemEnchantmentsComponent enchantments
                        &&enchantments.getSize()==0,
                DataComponentTypes.LORE,value->value instanceof LoreComponent lore&&lore.lines().isEmpty(),
                DataComponentTypes.ATTRIBUTE_MODIFIERS,value->value instanceof net.minecraft.component.type.AttributeModifiersComponent modifiers
                        &&modifiers.modifiers().isEmpty());
    }
    /**
     * D1b-3a新增8型（設計§2.2）：fire_resistant（MC的net.minecraft.util.Unit）、enchantment_glint_override（Boolean）、
     * ominous_bottle_amplifier（Integer）是literal；potion_contents、suspicious_stew_effects、container、banner_patterns、
     * charged_projectiles只收空DEFAULT。空值以各型DEFAULT.equals判定（與production逐欄位isEmpty是不同的表示法）。
     */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> d1b3aReviewedItemDefaults() {
        return java.util.Map.of(
                DataComponentTypes.FIRE_RESISTANT,value->value instanceof net.minecraft.util.Unit,
                DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,value->value instanceof Boolean,
                DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,value->value instanceof Integer,
                DataComponentTypes.POTION_CONTENTS,net.minecraft.component.type.PotionContentsComponent.DEFAULT::equals,
                DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,net.minecraft.component.type.SuspiciousStewEffectsComponent.DEFAULT::equals,
                DataComponentTypes.CONTAINER,net.minecraft.component.type.ContainerComponent.DEFAULT::equals,
                DataComponentTypes.BANNER_PATTERNS,net.minecraft.component.type.BannerPatternsComponent.DEFAULT::equals,
                DataComponentTypes.CHARGED_PROJECTILES,net.minecraft.component.type.ChargedProjectilesComponent.DEFAULT::equals);
    }
    /**
     * D1b-3b新增與放寬的3型（設計§2.2）：非空ATTRIBUTE_MODIFIERS（放寬D1b-2的「必須為空」；每個attribute都是named Reference）、
     * FOOD（using_converts_to的ItemStack沒有patch且count為1；每個effect的type是named Reference）、TOOL（每個rule都是direct list，沒有
     * tag rule）。以typed record與instanceof判定（與production的exact class判定是不同的表示法）。hidden_effect不列條件：
     * StatusEffectEntry.effect()回傳不含hidden_effect的copy（javap），公開API下觀察不到，也不會被編碼或使用。
     */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> d1b3bReviewedItemDefaults() {
        return java.util.Map.of(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,value->value instanceof net.minecraft.component.type.AttributeModifiersComponent modifiers
                        &&modifiers.modifiers().stream().allMatch(entry->entry.attribute() instanceof net.minecraft.registry.entry.RegistryEntry.Reference<?>),
                DataComponentTypes.FOOD,value->value instanceof net.minecraft.component.type.FoodComponent food
                        &&food.usingConvertsTo().map(stack->stack.getCount()==1&&stack.getComponentChanges().isEmpty()).orElse(true)
                        &&food.effects().stream().allMatch(entry->entry.effect().getEffectType() instanceof net.minecraft.registry.entry.RegistryEntry.Reference<?>),
                DataComponentTypes.TOOL,value->value instanceof net.minecraft.component.type.ToolComponent tool
                        &&tool.rules().stream().allMatch(rule->rule.blocks().getTagKey().isEmpty()));
    }
    private static boolean hasHiddenEffect(net.minecraft.entity.effect.StatusEffectInstance effect) {
        return ((NbtCompound)net.minecraft.entity.effect.StatusEffectInstance.CODEC.encodeStart(NbtOps.INSTANCE,effect).getOrThrow()).contains("hidden_effect");
    }
    /**
     * 依子片合併已審型別表（D1b-3a quality F1）：後片與前片同鍵時，該鍵必須列在relaxed（明確宣告「後片放寬前片的條件」），否則丟例外；
     * relaxed裡的鍵也必須真的與前片同鍵。不用putAll靜默覆蓋。
     */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> merge(
            java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> base,
            java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> later,
            java.util.Set<net.minecraft.component.ComponentType<?>> relaxed) {
        var merged=new java.util.HashMap<>(base);
        for(var entry:later.entrySet()) {
            if(base.containsKey(entry.getKey())!=relaxed.contains(entry.getKey()))
                throw new IllegalStateException("子片表重疊未宣告或宣告不實："+typeName(entry.getKey()));
            merged.put(entry.getKey(),entry.getValue());
        }
        for(var key:relaxed)if(!later.containsKey(key))throw new IllegalStateException("relaxed不在後片表："+typeName(key));
        return merged;
    }
    /** D1b-3a為止已審的型別：D1b-2加上D1b-3a（兩表不重疊）。 */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> d1b3aCumulativeItemDefaults() {
        return merge(d1b2ReviewedItemDefaults(),d1b3aReviewedItemDefaults(),java.util.Set.of());
    }
    /** 目前已審的全部型別：D1b-3a為止的表，加上D1b-3b（ATTRIBUTE_MODIFIERS明確放寬D1b-2的「必須為空」）。 */
    private static java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> reviewedItemDefaults() {
        return merge(d1b3aCumulativeItemDefaults(),d1b3bReviewedItemDefaults(),java.util.Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS));
    }
    /** default的每個component都符合reviewed的值條件；不符的型別id加入unreviewed（可為null）。 */
    private static boolean allReviewed(Item item,java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>> reviewed,
            java.util.Set<String> unreviewed) {
        boolean all=true;
        for(var component:item.getComponents()) {
            var condition=reviewed.get(component.type());
            if(condition==null||!condition.test(component.value())) {
                all=false;
                if(unreviewed!=null)unreviewed.add(String.valueOf(Registries.DATA_COMPONENT_TYPE.getId(component.type())));
            }
        }
        return all;
    }
    @Test void everyVanillaItemLeafIsAcceptedOrRejectedOnlyForUnreviewedTypes() {
        // S0-3（D1b-2 quality M2(b)）：拒絕原因層級的gating，不斷言數量。只篩minecraft namespace（full中先init的mod item會被篩掉）。
        // 每個item的leaf結果只能是ACCEPTED或UNSUPPORTED_ITEM_DEFAULT_COMPONENT，而且「被拒」⇔「default含上表以外的型別或值」。
        // 非Failure的例外不攔，直接讓本測試失敗。
        var reviewed=reviewedItemDefaults();
        var mismatched=new java.util.TreeMap<String,String>();int checked=0;
        for(var entry:(Iterable<net.minecraft.registry.entry.RegistryEntry.Reference<Item>>)Registries.ITEM.streamEntries()::iterator) {
            var id=entry.registryKey().getValue();
            if(!id.getNamespace().equals("minecraft"))continue;
            checked++;
            var unreviewed=new java.util.TreeSet<String>();
            allReviewed(entry.value(),reviewed,unreviewed);
            String actual;
            try {itemLeaf(entry.value());actual="ACCEPTED";}
            catch(Minecraft121SettingsGraph.Failure failure){actual=failure.getMessage();}
            String expected=unreviewed.isEmpty()?"ACCEPTED":"UNSUPPORTED_ITEM_DEFAULT_COMPONENT";
            if(!expected.equals(actual))mismatched.put(id.toString(),"expected="+expected+" actual="+actual+" unreviewed="+unreviewed);
        }
        int total=checked;
        var nonEmptyLore=single(new net.minecraft.component.Component<>(DataComponentTypes.LORE,
                new LoreComponent(java.util.List.of(net.minecraft.text.Text.literal("line")))));
        assertAll(
                ()->assertTrue(total>0,"fixture前提：有minecraft item"),
                ()->assertEquals(java.util.Map.of(),mismatched,"每個vanilla ITEM只能ACCEPTED，或因白名單外的型別或值被拒"),
                // vanilla的lore全為空，所以「LORE不再檢查是否為空」這類突變不會改變上面任何item的結果；以seam子案補上。
                ()->assertAll("subcase=seam非空lore",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(Items.DIAMOND,nonEmptyLore))));
    }

    // ===== C1d-D1b-3a：component codec改用lease registries（codec之前固定證明static registry identity），以及8型literal／空DEFAULT =====
    @Test void itemLeafCodecUsesLeaseRegistriesWithIdentity() {
        // 設計§1.5第4點與設計審查M1（controller C1）：ITEM leaf在codec之前固定證明ITEM、DATA_COMPONENT_TYPE、BLOCK、ATTRIBUTE、
        // STATUS_EFFECT都是lease持有的全域registry本身；缺席→REQUIRED_REGISTRY_MISSING，同key另一個物件→STATIC_REGISTRY_OWNER_MISMATCH。
        // item(...)與seam各自都要擋。codecFails能過白名單、但會在codec失敗（MAX_STACK_SIZE的原生範圍是1..99）：identity若排在codec
        // 之後，這個子案會得到UNSUPPORTED_ITEM_COMPONENT_CODEC。
        var diamond=Items.DIAMOND;var key=diamond.getRegistryEntry().registryKey();var entry=Registries.ITEM.getEntry(key).orElseThrow();
        var node=itemNode(key.getValue().toString());
        var codecFails=ComponentMap.builder().addAll(diamond.getComponents()).add(DataComponentTypes.MAX_STACK_SIZE,0).build();
        java.util.function.Function<DynamicRegistryManager,org.junit.jupiter.api.function.Executable> leafWith=
                registries->()->leaves(new Minecraft121SettingsGraph(),registries).item(node,key,entry);
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        checks.add(()->assertAll("正向對照與fixture前提",
                ()->assertDoesNotThrow(leafWith.apply(itemRegistries())),
                ()->assertDoesNotThrow(()->seam(diamond,diamond.getComponents())),
                ()->rejects("UNSUPPORTED_ITEM_COMPONENT_CODEC",()->seam(diamond,codecFails))));
        for(var registry:itemLeafStaticRegistries()) {
            var registryKey=registry.getKey();String name=registryKey.getValue().toString();
            var missing=itemLeafRegistriesWithout(registryKey);var foreign=itemLeafRegistriesWithForeign(registryKey);
            checks.add(()->assertAll("subcase=lease缺"+name,
                    ()->rejects("REQUIRED_REGISTRY_MISSING",leafWith.apply(missing)),
                    ()->rejects("REQUIRED_REGISTRY_MISSING",()->seam(missing,diamond,diamond.getComponents())),
                    ()->rejects("REQUIRED_REGISTRY_MISSING",()->seam(missing,diamond,codecFails))));
            checks.add(()->assertAll("subcase=lease的"+name+"是同key的另一個物件",
                    ()->rejects("STATIC_REGISTRY_OWNER_MISMATCH",leafWith.apply(foreign)),
                    ()->rejects("STATIC_REGISTRY_OWNER_MISMATCH",()->seam(foreign,diamond,diamond.getComponents())),
                    ()->rejects("STATIC_REGISTRY_OWNER_MISMATCH",()->seam(foreign,diamond,codecFails))));
        }
        // BLOCK leaf共用同一個registry identity helper：lease缺BLOCK時同樣是REQUIRED_REGISTRY_MISSING（換源之前是STATIC_REGISTRY_OWNER_MISMATCH）。
        var stoneKey=Blocks.STONE.getRegistryEntry().registryKey();var stone=Registries.BLOCK.getEntry(stoneKey).orElseThrow();
        checks.add(()->assertAll("subcase=BLOCK leaf的lease缺BLOCK",()->rejects("REQUIRED_REGISTRY_MISSING",
                ()->leaves(new Minecraft121SettingsGraph(),itemLeafRegistriesWithout(RegistryKeys.BLOCK))
                        .block(blockNode("minecraft:stone"),stoneKey,stone))));
        assertAll(checks);
    }
    @Test void itemLeafAcceptsLiteralAndEmptyDefaultComponents() {
        // 設計§2.2與§3.1的3a：8型的vanilla default是literal或空DEFAULT，不引用任何registry。encoded值逐型寫成字面值（不經ComponentMap.CODEC）。
        // 真vanilla item從REJECTED變ACCEPTED：每型一個只帶該型（其餘都是D1b-2已審型別）的item。ominous_bottle與suspicious_stew另帶food，
        // D1b-3a時只能以seam的單一component驗；D1b-3b起food已審，這兩件也以整個item驗。
        record Case(Item carrier,net.minecraft.component.ComponentType<?> type,NbtElement literal,boolean wholeItem) {}
        var cases=java.util.List.of(
                new Case(Items.NETHERITE_INGOT,DataComponentTypes.FIRE_RESISTANT,new NbtCompound(),true),
                new Case(Items.EXPERIENCE_BOTTLE,DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,NbtByte.ONE,true),
                new Case(Items.OMINOUS_BOTTLE,DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,NbtInt.of(0),true),
                new Case(Items.POTION,DataComponentTypes.POTION_CONTENTS,new NbtCompound(),true),
                new Case(Items.SUSPICIOUS_STEW,DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,new NbtList(),true),
                new Case(Items.BARREL,DataComponentTypes.CONTAINER,new NbtList(),true),
                new Case(Items.SHIELD,DataComponentTypes.BANNER_PATTERNS,new NbtList(),true),
                new Case(Items.CROSSBOW,DataComponentTypes.CHARGED_PROJECTILES,new NbtList(),true));
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var c:cases) {
            String type=String.valueOf(Registries.DATA_COMPONENT_TYPE.getId(c.type()));String carrier=Registries.ITEM.getId(c.carrier()).toString();
            checks.add(()->assertAll("subcase="+type+"（"+carrier+"）",
                    ()->{var defaults=seam(Items.DIAMOND,single(component(c.carrier(),c.type()))).getCompound("DefaultComponents");
                        assertEquals(java.util.Set.of(type),defaults.getKeys(),"seam單一component");
                        assertEquals(c.literal(),defaults.get(type),"encoded值");},
                    ()->{if(c.wholeItem())assertEquals(c.literal(),itemLeaf(c.carrier()).getCompound("DefaultComponents").get(type),
                            "整個item的leaf ACCEPTED，含該型的encoded值");}));
        }
        // 值條件只認型別；ominous_bottle_amplifier的原生範圍0..4由codec強制，超出時在codec拒，不寫入payload。
        checks.add(()->assertAll("subcase=ominous_bottle_amplifier=5（超出原生範圍）",()->rejects("UNSUPPORTED_ITEM_COMPONENT_CODEC",
                ()->seam(Items.DIAMOND,ComponentMap.builder().add(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,5).build()))));
        assertAll(checks);
    }
    /** raw cast製造的heap pollution：值的型別與ComponentType的型別參數不符（測試只送進seam）。 */
    @SuppressWarnings({"rawtypes","unchecked"})
    private static ComponentMap mistyped(net.minecraft.component.ComponentType<?> type,Object value) {
        return ComponentMap.builder().add((net.minecraft.component.ComponentType)type,value).build();
    }
    private static <T> ComponentMap only(net.minecraft.component.ComponentType<T> type,T value) {
        return ComponentMap.builder().add(type,value).build();
    }
    @Test void itemLeafRejectsNonEmptyOrMistypedReviewedComponents() {
        // 設計審查M4：8型的值條件逐型鑑別。5型只收空值：每個非空值仍拒（potion_contents的三個欄位各一案）；3型literal：型別不符的值
        // 仍拒。全部只經seam，值都是新建的局部物件（direct banner pattern entry、新ItemStack），不改動全域狀態。
        var speed=net.minecraft.entity.effect.StatusEffects.SPEED;
        var pattern=net.minecraft.registry.entry.RegistryEntry.of(new net.minecraft.block.entity.BannerPattern(
                net.minecraft.util.Identifier.ofVanilla("base"),"block.minecraft.banner.base"));
        var cases=new java.util.LinkedHashMap<String,ComponentMap>();
        cases.put("potion_contents的potion非空",only(DataComponentTypes.POTION_CONTENTS,
                new net.minecraft.component.type.PotionContentsComponent(net.minecraft.potion.Potions.WATER)));
        cases.put("potion_contents的customColor非空",only(DataComponentTypes.POTION_CONTENTS,new net.minecraft.component.type.PotionContentsComponent(
                java.util.Optional.empty(),java.util.Optional.of(0x385DC6),java.util.List.of())));
        cases.put("potion_contents的customEffects非空",only(DataComponentTypes.POTION_CONTENTS,new net.minecraft.component.type.PotionContentsComponent(
                java.util.Optional.empty(),java.util.Optional.empty(),java.util.List.of(new net.minecraft.entity.effect.StatusEffectInstance(speed)))));
        cases.put("suspicious_stew_effects非空",only(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,new net.minecraft.component.type.SuspiciousStewEffectsComponent(
                java.util.List.of(new net.minecraft.component.type.SuspiciousStewEffectsComponent.StewEffect(speed,160)))));
        cases.put("container非空",only(DataComponentTypes.CONTAINER,
                net.minecraft.component.type.ContainerComponent.fromStacks(java.util.List.of(new net.minecraft.item.ItemStack(Items.STONE)))));
        cases.put("banner_patterns非空",only(DataComponentTypes.BANNER_PATTERNS,new net.minecraft.component.type.BannerPatternsComponent(
                java.util.List.of(new net.minecraft.component.type.BannerPatternsComponent.Layer(pattern,net.minecraft.util.DyeColor.RED)))));
        cases.put("charged_projectiles非空",only(DataComponentTypes.CHARGED_PROJECTILES,
                net.minecraft.component.type.ChargedProjectilesComponent.of(new net.minecraft.item.ItemStack(Items.ARROW))));
        cases.put("fire_resistant的值不是Unit.INSTANCE",mistyped(DataComponentTypes.FIRE_RESISTANT,Boolean.TRUE));
        cases.put("enchantment_glint_override的值不是Boolean",mistyped(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,"true"));
        cases.put("ominous_bottle_amplifier的值不是Integer",mistyped(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,0L));
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var c:cases.entrySet())checks.add(()->assertAll("subcase="+c.getKey(),
                ()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(Items.DIAMOND,c.getValue()))));
        assertAll(checks);
    }

    // ===== C1d-D1b-3b：單一分派的ITEM default component型別契約（D1b-3a quality M3(a)的硬性前置） =====
    private static String typeName(net.minecraft.component.ComponentType<?> type) {
        return String.valueOf(Registries.DATA_COMPONENT_TYPE.getId(type));
    }
    private static java.util.TreeSet<String> typeNames(java.util.Collection<? extends net.minecraft.component.ComponentType<?>> types) {
        var names=new java.util.TreeSet<String>();for(var type:types)names.add(typeName(type));return names;
    }
    /**
     * 測試端獨立列出「已接受的值會產生ref」的已審型別；其餘已審型別都必須明確宣告NO_REFS。D1b-3各子片增減型別時同步更新。
     * D1b-3a為止沒有；D1b-3b為非空attribute_modifiers（ATTRIBUTE）、food（STATUS_EFFECT、ITEM）與只有direct rule的tool（BLOCK）。
     * 新增帶引用的型別時，production契約的refs與registries、本清單與independentRefs三處要同步（quality Q-M3）；三處同時漏列時，
     * 由M9的NBT交叉檢查（payloadRegistryIdStrings）抓到。
     */
    private static java.util.Set<net.minecraft.component.ComponentType<?>> refBearingItemDefaultTypes() {
        return java.util.Set.of(DataComponentTypes.ATTRIBUTE_MODIFIERS,DataComponentTypes.FOOD,DataComponentTypes.TOOL);
    }
    @Test void itemComponentContractTableIsSingleDispatchWithExplicitRefs() {
        // 每個已審型別在同一筆契約宣告值條件、ref抽取與codec會查詢的static registry（ItemComponentContracts的單一分派表）。結構斷言：
        // 表的型別集合等於測試端獨立列出的已審型別；會產生ref的型別恰為refBearingItemDefaultTypes()（有抽取器且宣告查詢的registry），
        // 其餘都明確宣告NO_REFS且不查registry；各型宣告的registry都屬於固定static registry聯集；固定聯集等於fixture獨立列出的那組。
        var contracts=Minecraft121ItemComponentContracts.contracts();
        var refBearing=refBearingItemDefaultTypes();
        var union=itemLeafStaticRegistries().stream().map(r->(Object)r.getKey()).toList();
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        checks.add(()->assertEquals(typeNames(reviewedItemDefaults().keySet()),typeNames(contracts.keySet()),
                "表的型別集合等於測試端獨立列出的已審型別"));
        checks.add(()->assertTrue(contracts.keySet().containsAll(refBearing),"會產生ref的型別都在表內："+typeNames(refBearing)));
        checks.add(()->assertEquals(itemLeafStaticRegistries(),Minecraft121ItemComponentContracts.staticRegistries(),
                "production的固定聯集等於fixture獨立列出的聯集（含次序，ITEM第一）"));
        for(var entry:contracts.entrySet()) {
            var contract=entry.getValue();boolean bearing=refBearing.contains(entry.getKey());
            checks.add(()->assertAll("type="+typeName(entry.getKey()),
                    ()->assertNotNull(contract.value(),"值條件須宣告"),
                    ()->assertNotNull(contract.refs(),"refs須明確宣告"),
                    ()->assertEquals(bearing,contract.refs()!=Minecraft121ItemComponentContracts.NO_REFS,
                            bearing?"會產生ref的型別須有抽取器，不得是NO_REFS":"literal／只收空值的型別須明確宣告NO_REFS"),
                    ()->assertEquals(bearing,!contract.registries().isEmpty(),"有ref ⇔ 宣告codec會查詢的static registry"),
                    ()->assertTrue(union.containsAll(contract.registries()),"宣告的registry都屬於固定聯集："+contract.registries())));
        }
        assertAll(checks);
    }

    // ===== C1d-D1b-3b：非空attribute_modifiers、food與只有direct rule的tool；ITEM leaf開始產生出邊 =====
    /**
     * ref的描述與指向的named entry：對sealed Ref窮舉（quality Q-M4(c)），D1b-3c加Tag時這裡沒有對應分支就無法編譯，不會在runtime才丟
     * ClassCastException。describeRef、assertRefs與M9 oracle都經這兩個helper取用ref。
     */
    private static String describeRef(Minecraft121ItemComponentContracts.Ref ref) {
        return switch(ref) {
            case Minecraft121ItemComponentContracts.Ref.Entry entry -> {
                var key=entry.entry().registryKey();
                yield ref.path()+" -> "+key.getRegistry()+"|"+key.getValue();
            }
        };
    }
    private static RegistryEntry.Reference<?> refEntry(Minecraft121ItemComponentContracts.Ref ref) {
        return switch(ref) {
            case Minecraft121ItemComponentContracts.Ref.Entry entry -> entry.entry();
        };
    }
    private static List<String> describeRefs(List<Minecraft121ItemComponentContracts.Ref> refs) {
        return refs.stream().map(Minecraft121RegistryLeafContractsTest::describeRef).toList();
    }
    /** 預期的一條ref：path與指向的named entry（entry以identity比對）。 */
    private record ExpectedRef(String path,RegistryEntry<?> entry) {
        String describe() {
            var key=entry.getKey().orElseThrow();
            return path+" -> "+key.getRegistry()+"|"+key.getValue();
        }
    }
    private static void assertRefs(List<ExpectedRef> expected,List<Minecraft121ItemComponentContracts.Ref> actual,String message) {
        assertEquals(expected.stream().map(ExpectedRef::describe).toList(),describeRefs(actual),message+"：path、registry、id與次序");
        for(int i=0;i<expected.size();i++)assertSame(expected.get(i).entry(),refEntry(actual.get(i)),message+"：第"+i+"條ref是同一個named entry物件");
    }
    /** plain NbtOps獨立編碼的default ComponentMap（不經production的RegistryOps、白名單與roundtrip）。 */
    private static NbtElement plainDefaults(Item item) {
        return ComponentMap.CODEC.encodeStart(NbtOps.INSTANCE,item.getComponents()).getOrThrow();
    }
    @Test void itemLeafCapturesStaticEntryRefsFromReviewedDefaults() {
        // D1b-3設計§3.1的3b列：golden_apple（food的effect→STATUS_EFFECT）、beetroot_soup（food的using_converts_to→ITEM bowl）、
        // mace（非空attribute_modifiers→ATTRIBUTE；tool的rules為空，不產生ref）從REJECTED變ACCEPTED並帶出ref。ref的path對應原生codec的
        // 欄位，依component型別id排序、同型內依list次序；entry以identity比對vanilla常數。item(...)經sink送出的ref與seam相同，from是
        // 該ITEM node。
        var food="components/minecraft:food/";var modifiers="components/minecraft:attribute_modifiers/modifiers/";
        var cases=new java.util.LinkedHashMap<Item,List<ExpectedRef>>();
        cases.put(Items.GOLDEN_APPLE,List.of(new ExpectedRef(food+"effects/0/effect/id",StatusEffects.REGENERATION),
                new ExpectedRef(food+"effects/1/effect/id",StatusEffects.ABSORPTION)));
        cases.put(Items.BEETROOT_SOUP,List.of(new ExpectedRef(food+"using_converts_to/id",Items.BOWL.getRegistryEntry())));
        cases.put(Items.MACE,List.of(new ExpectedRef(modifiers+"0/type",EntityAttributes.GENERIC_ATTACK_DAMAGE),
                new ExpectedRef(modifiers+"1/type",EntityAttributes.GENERIC_ATTACK_SPEED)));
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        checks.add(()->assertTrue(Items.MACE.getComponents().get(DataComponentTypes.TOOL).rules().isEmpty(),"fixture前提：mace的tool rules為空"));
        for(var c:cases.entrySet()) {
            var item=c.getKey();String id=Registries.ITEM.getId(item).toString();
            checks.add(()->assertAll("subcase="+id,()->{
                var capture=capture(item,item.getComponents());
                assertRefs(c.getValue(),capture.references(),"seam");
                assertEquals(plainDefaults(item),capture.payload().getCompound("DefaultComponents"),"DefaultComponents等於plain NbtOps獨立編碼的default");
                var emitted=new java.util.ArrayList<java.util.Map.Entry<Minecraft121SettingsGraph.Key,Minecraft121ItemComponentContracts.Ref>>();
                assertEquals(capture.payload(),itemLeaf(item,emitted),"production接線的payload與seam相同");
                assertEquals(describeRefs(capture.references()),emitted.stream().map(e->describeRef(e.getValue())).toList(),"item(...)經sink送出同一組ref");
                assertTrue(emitted.stream().allMatch(e->e.getKey().equals(itemNode(id))),"from是該ITEM node");
            }));
        }
        assertAll(checks);
    }
    @Test void itemLeafToolDirectRulesCaptureBlockRefsAndSameIdStaysTyped() {
        // 只有direct rule的tool（D1b-3b）：每個rule的每個block各一條ref，path為rules/i/blocks/j。vanilla沒有這種default（mace與trident的
        // rules為空，其他工具都帶tag rule），所以以seam的局部值驗；RegistryEntryList.of只複製block的intrusive entry，不改動全域狀態。
        // 同id不同registry不混：direct block的stone與food using_converts_to的item stone是兩條registry不同的ref。
        // direct加上tag rule（iron_sword的default tool）仍拒，tag rule屬D1b-3c。
        var rules="components/minecraft:tool/rules/";
        var tool=new ToolComponent(List.of(ToolComponent.Rule.ofAlwaysDropping(List.of(Blocks.COBWEB),15f),
                ToolComponent.Rule.of(List.of(Blocks.VINE,Blocks.GLOW_LICHEN),2f)),1f,1);
        var stoneFood=new FoodComponent(1,0.1f,false,1.6f,Optional.of(new ItemStack(Items.STONE)),List.of());
        var stoneTool=new ToolComponent(List.of(ToolComponent.Rule.of(List.of(Blocks.STONE),1f)),1f,1);
        var mixed=ComponentMap.builder().add(DataComponentTypes.TOOL,stoneTool).add(DataComponentTypes.FOOD,stoneFood).build();
        var swordTool=component(Items.IRON_SWORD,DataComponentTypes.TOOL);
        assertAll(
                ()->assertAll("subcase=direct rules",()->assertRefs(List.of(new ExpectedRef(rules+"0/blocks/0",Blocks.COBWEB.getRegistryEntry()),
                        new ExpectedRef(rules+"1/blocks/0",Blocks.VINE.getRegistryEntry()),new ExpectedRef(rules+"1/blocks/1",Blocks.GLOW_LICHEN.getRegistryEntry())),
                        capture(Items.DIAMOND,only(DataComponentTypes.TOOL,tool)).references(),"seam")),
                ()->assertAll("subcase=空rules",()->assertEquals(List.of(),
                        capture(Items.DIAMOND,only(DataComponentTypes.TOOL,new ToolComponent(List.of(),1f,2))).references())),
                ()->assertAll("subcase=同id的BLOCK與ITEM",()->assertRefs(List.of(
                        new ExpectedRef("components/minecraft:food/using_converts_to/id",Items.STONE.getRegistryEntry()),
                        new ExpectedRef(rules+"0/blocks/0",Blocks.STONE.getRegistryEntry())),capture(Items.DIAMOND,mixed).references(),"seam")),
                ()->assertAll("subcase=direct加上tag rule（iron_sword）",
                        ()->assertTrue(swordTool.value().rules().stream().anyMatch(rule->rule.blocks().getTagKey().isEmpty())
                                &&swordTool.value().rules().stream().anyMatch(rule->rule.blocks().getTagKey().isPresent()),"fixture前提：direct與tag rule並存"),
                        ()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",()->seam(Items.DIAMOND,single(swordTool)))));
    }
    @Test void itemLeafRejectsForeignReferenceWithSameId() {
        // 設計審查M2（Entry部分）：只從重建的map抽ref，會把foreign的Reference靜默換成registry的物件。原值與重建值要逐一比對、要求每個ref
        // 是同一物件，不同時為REGISTRY_ENTRY_OWNER_MISMATCH。foreign Reference以Reference.standAlone(全域owner, 同一個key)建立：encode只
        // 寫key（ATTRIBUTE與STATUS_EFFECT的entry codec不比owner；BLOCK的RegistryFixedCodec比owner，但這裡用的就是全域owner），parse後換成
        // registry的物件，所以只有identity比對擋得住。standAlone不寫入任何registry。正向對照：同一個值改用全域entry時ACCEPTED。
        var foreignAttribute=RegistryEntry.Reference.standAlone(Registries.ATTRIBUTE.getEntryOwner(),
                EntityAttributes.GENERIC_ATTACK_DAMAGE.getKey().orElseThrow());
        var foreignEffect=RegistryEntry.Reference.standAlone(Registries.STATUS_EFFECT.getEntryOwner(),StatusEffects.SPEED.getKey().orElseThrow());
        var foreignBlock=RegistryEntry.Reference.standAlone(Registries.BLOCK.getEntryOwner(),Blocks.COBWEB.getRegistryEntry().registryKey());
        var modifier=new EntityAttributeModifier(Identifier.ofVanilla("base_attack_damage"),3.0,EntityAttributeModifier.Operation.ADD_VALUE);
        java.util.function.Function<RegistryEntry<net.minecraft.entity.attribute.EntityAttribute>,ComponentMap> attribute=entry->only(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,new AttributeModifiersComponent(List.of(new AttributeModifiersComponent.Entry(entry,modifier,
                        AttributeModifierSlot.MAINHAND)),true));
        java.util.function.Function<RegistryEntry<net.minecraft.entity.effect.StatusEffect>,ComponentMap> effect=entry->only(DataComponentTypes.FOOD,
                new FoodComponent(1,0.1f,false,1.6f,Optional.empty(),List.of(new FoodComponent.StatusEffectEntry(new StatusEffectInstance(entry,100),1f))));
        java.util.function.Function<RegistryEntry<Block>,ComponentMap> block=entry->only(DataComponentTypes.TOOL,new ToolComponent(List.of(
                new ToolComponent.Rule(RegistryEntryList.of(List.of(entry)),Optional.of(15f),Optional.of(true))),1f,1));
        assertAll(
                ()->assertAll("fixture前提：同key、不同物件",
                        ()->assertNotSame(EntityAttributes.GENERIC_ATTACK_DAMAGE,foreignAttribute),
                        ()->assertNotSame(StatusEffects.SPEED,foreignEffect),
                        ()->assertNotSame(Blocks.COBWEB.getRegistryEntry(),foreignBlock),
                        ()->assertEquals(Blocks.COBWEB.getRegistryEntry().registryKey(),foreignBlock.registryKey())),
                ()->assertAll("subcase=attribute_modifiers的ATTRIBUTE",
                        ()->rejects("REGISTRY_ENTRY_OWNER_MISMATCH",()->seam(Items.DIAMOND,attribute.apply(foreignAttribute))),
                        ()->assertDoesNotThrow(()->seam(Items.DIAMOND,attribute.apply(EntityAttributes.GENERIC_ATTACK_DAMAGE)),"正向對照")),
                ()->assertAll("subcase=food effect的STATUS_EFFECT",
                        ()->rejects("REGISTRY_ENTRY_OWNER_MISMATCH",()->seam(Items.DIAMOND,effect.apply(foreignEffect))),
                        ()->assertDoesNotThrow(()->seam(Items.DIAMOND,effect.apply(StatusEffects.SPEED)),"正向對照")),
                ()->assertAll("subcase=tool direct rule的BLOCK",
                        ()->rejects("REGISTRY_ENTRY_OWNER_MISMATCH",()->seam(Items.DIAMOND,block.apply(foreignBlock))),
                        ()->assertDoesNotThrow(()->seam(Items.DIAMOND,block.apply(Blocks.COBWEB.getRegistryEntry())),"正向對照")));
    }
    @Test void itemLeafRejectsMissingTargetAndInlineReferences() {
        // 缺target：standAlone的key不在registry；encode只寫id，parse找不到，在codec拒（UNSUPPORTED_ITEM_COMPONENT_CODEC），不進payload。
        // inline：RegistryEntry.of建立的Direct entry不是exact原生Reference，值條件在codec之前就拒（UNSUPPORTED_ITEM_DEFAULT_COMPONENT）。
        var modifier=new EntityAttributeModifier(Identifier.ofVanilla("base_attack_damage"),3.0,EntityAttributeModifier.Operation.ADD_VALUE);
        java.util.function.Function<RegistryEntry<net.minecraft.entity.attribute.EntityAttribute>,ComponentMap> attribute=entry->only(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,new AttributeModifiersComponent(List.of(new AttributeModifiersComponent.Entry(entry,modifier,
                        AttributeModifierSlot.MAINHAND)),true));
        java.util.function.Function<RegistryEntry<net.minecraft.entity.effect.StatusEffect>,ComponentMap> effect=entry->only(DataComponentTypes.FOOD,
                new FoodComponent(1,0.1f,false,1.6f,Optional.empty(),List.of(new FoodComponent.StatusEffectEntry(new StatusEffectInstance(entry,100),1f))));
        java.util.function.Function<RegistryEntry<Block>,ComponentMap> block=entry->only(DataComponentTypes.TOOL,new ToolComponent(List.of(
                new ToolComponent.Rule(RegistryEntryList.of(List.of(entry)),Optional.of(15f),Optional.of(true))),1f,1));
        var missing=Identifier.of("test","missing");
        assertAll(
                ()->assertAll("subcase=缺ATTRIBUTE target",()->rejects("UNSUPPORTED_ITEM_COMPONENT_CODEC",()->seam(Items.DIAMOND,attribute.apply(
                        RegistryEntry.Reference.standAlone(Registries.ATTRIBUTE.getEntryOwner(),RegistryKey.of(RegistryKeys.ATTRIBUTE,missing)))))),
                ()->assertAll("subcase=缺STATUS_EFFECT target",()->rejects("UNSUPPORTED_ITEM_COMPONENT_CODEC",()->seam(Items.DIAMOND,effect.apply(
                        RegistryEntry.Reference.standAlone(Registries.STATUS_EFFECT.getEntryOwner(),RegistryKey.of(RegistryKeys.STATUS_EFFECT,missing)))))),
                ()->assertAll("subcase=缺BLOCK target",()->rejects("UNSUPPORTED_ITEM_COMPONENT_CODEC",()->seam(Items.DIAMOND,block.apply(
                        RegistryEntry.Reference.standAlone(Registries.BLOCK.getEntryOwner(),RegistryKey.of(RegistryKeys.BLOCK,missing)))))),
                ()->assertAll("subcase=inline ATTRIBUTE",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",
                        ()->seam(Items.DIAMOND,attribute.apply(RegistryEntry.of(EntityAttributes.GENERIC_ARMOR.value()))))),
                ()->assertAll("subcase=inline STATUS_EFFECT",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",
                        ()->seam(Items.DIAMOND,effect.apply(RegistryEntry.of(StatusEffects.SPEED.value()))))),
                ()->assertAll("subcase=inline BLOCK",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",
                        ()->seam(Items.DIAMOND,block.apply(RegistryEntry.of(Blocks.COBWEB))))));
    }
    /**
     * 計數的StatusEffectInstance子類：getter與Object method分開計數。getter是codec的asParameters與食用時會呼叫的method；hashCode／
     * equals是FOOD codec外層的CodecCache（以value為key）經record的ObjectMethods可能呼叫到的method（D1b-3c-0起必須是0次）。
     */
    static final class CountingEffectInstance extends StatusEffectInstance {
        static final java.util.concurrent.atomic.AtomicInteger GETTERS=new java.util.concurrent.atomic.AtomicInteger();
        static final java.util.concurrent.atomic.AtomicInteger OBJECT_METHODS=new java.util.concurrent.atomic.AtomicInteger();
        CountingEffectInstance(RegistryEntry<net.minecraft.entity.effect.StatusEffect> type,int duration) {super(type,duration);}
        @Override public RegistryEntry<net.minecraft.entity.effect.StatusEffect> getEffectType() {GETTERS.incrementAndGet();return super.getEffectType();}
        @Override public int getDuration() {GETTERS.incrementAndGet();return super.getDuration();}
        @Override public int getAmplifier() {GETTERS.incrementAndGet();return super.getAmplifier();}
        @Override public boolean isAmbient() {GETTERS.incrementAndGet();return super.isAmbient();}
        @Override public boolean shouldShowParticles() {GETTERS.incrementAndGet();return super.shouldShowParticles();}
        @Override public boolean shouldShowIcon() {GETTERS.incrementAndGet();return super.shouldShowIcon();}
        @Override public boolean equals(Object other) {OBJECT_METHODS.incrementAndGet();return super.equals(other);}
        @Override public int hashCode() {OBJECT_METHODS.incrementAndGet();return super.hashCode();}
    }
    @Test void itemLeafFoodRejectsPatchedOrCountedConvertsToAndReadsEffectsThroughNativeCopy() {
        // food的值條件（設計§2.2、設計審查M5）：using_converts_to的ItemStack必須沒有patch，且count為1（UNCOUNTED_CODEC不編count，
        // 非1的count不會進payload，所以拒）。effect：固定JAR的StatusEffectEntry.effect()回傳new StatusEffectInstance(this.effect)，copy
        // 只以getfield讀欄位、不複製hiddenEffect（javap）；codec與食用都經這個copy。所以hidden_effect與原物件的class在公開API下都觀察
        // 不到：帶hidden_effect的effect被接受，payload等於不帶它的同值；子類的effect也被接受，payload等於原生同值，而且擷取全程不呼叫
        // 子類的getter。D1b-3c-0（spec M1＋quality Q-M1）：codec之前以effect()的copy重建StatusEffectEntry，FOOD codec外層的CodecCache
        // （以value為key）不再經record的ObjectMethods呼叫到原物件的hashCode／equals，所以子類的Object method也必須是0次。
        // 前提（quality Q-M1的測試附帶問題）：每次seam都經itemRegistries()新建lease manager（RegistryOps以identity比對），hidden與
        // counting子案的payload不會被CodecCache跨seam短路；fixture前提斷言這一點。
        // 值都是局部新物件（ItemStack.set只改該stack自己的ComponentMapImpl），不改動全域狀態。
        var patched=new ItemStack(Items.BOWL);patched.set(DataComponentTypes.MAX_STACK_SIZE,1);
        var plain=new StatusEffectInstance(StatusEffects.SPEED,100,0,false,true,true);
        var hidden=new StatusEffectInstance(StatusEffects.SPEED,100,0,false,true,true,new StatusEffectInstance(StatusEffects.SPEED,200));
        var counting=new CountingEffectInstance(StatusEffects.SPEED,100);
        java.util.function.BiFunction<Optional<ItemStack>,List<FoodComponent.StatusEffectEntry>,ComponentMap> food=(convertsTo,effects)->
                only(DataComponentTypes.FOOD,new FoodComponent(6,7.2f,false,1.6f,convertsTo,effects));
        java.util.function.Function<StatusEffectInstance,ComponentMap> withEffect=effect->food.apply(Optional.empty(),
                List.of(new FoodComponent.StatusEffectEntry(effect,1f)));
        var bowl=Optional.of(new ItemStack(Items.BOWL));
        assertAll(
                ()->assertAll("fixture前提",
                        ()->assertFalse(patched.getComponentChanges().isEmpty(),"patched有patch"),
                        ()->assertTrue(hasHiddenEffect(hidden),"hidden本身帶hidden_effect"),
                        ()->assertFalse(hasHiddenEffect(new FoodComponent.StatusEffectEntry(hidden,1f).effect()),"effect()的copy不含hidden_effect"),
                        ()->assertNotSame(StatusEffectInstance.class,counting.getClass(),"counting不是exact class"),
                        ()->assertNotSame(itemRegistries(),itemRegistries(),"每次seam都新建lease manager，CodecCache不跨seam共用")),
                ()->assertAll("subcase=正向對照",()->assertRefs(List.of(
                        new ExpectedRef("components/minecraft:food/using_converts_to/id",Items.BOWL.getRegistryEntry()),
                        new ExpectedRef("components/minecraft:food/effects/0/effect/id",StatusEffects.SPEED)),
                        capture(Items.DIAMOND,food.apply(bowl,List.of(new FoodComponent.StatusEffectEntry(new StatusEffectInstance(StatusEffects.SPEED,100),1f))))
                                .references(),"seam")),
                ()->assertAll("subcase=using_converts_to有patch",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",
                        ()->seam(Items.DIAMOND,food.apply(Optional.of(patched),List.of())))),
                ()->assertAll("subcase=using_converts_to的count為2",()->rejects("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",
                        ()->seam(Items.DIAMOND,food.apply(Optional.of(new ItemStack(Items.BOWL,2)),List.of())))),
                ()->assertAll("subcase=effect帶hidden_effect（effect()的copy不含它）",()->assertEquals(seam(Items.DIAMOND,withEffect.apply(plain)),
                        seam(Items.DIAMOND,withEffect.apply(hidden)),"payload等於不帶hidden_effect的同值")),
                ()->assertAll("subcase=StatusEffectInstance的子類（effect()的copy是exact原生）",()->{
                    var expected=seam(Items.DIAMOND,withEffect.apply(plain));
                    var map=withEffect.apply(counting);
                    CountingEffectInstance.GETTERS.set(0);CountingEffectInstance.OBJECT_METHODS.set(0);
                    var payload=seam(Items.DIAMOND,map);
                    assertAll(()->assertEquals(expected,payload,"payload等於原生同值"),
                            ()->assertEquals(0,CountingEffectInstance.GETTERS.get(),"擷取全程不呼叫子類的getter"),
                            ()->assertEquals(0,CountingEffectInstance.OBJECT_METHODS.get(),
                                    "CodecCache不得呼叫子類的hashCode／equals（codec只看重建的原生copy）"));}));
    }
    /**
     * 會說謊的foreign List（quality Q-M1）：內容是real，equals／hashCode卻宣稱等於claimed；兩者的呼叫次數記在calls。CodecCache的
     * key以value的equals／hashCode比對，所以把它放進已審record，可以讓encode命中claimed那個值已快取的編碼。
     */
    private static final class ClaimingList<E> extends java.util.AbstractList<E> {
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        private final List<E> real;private final List<?> claimed;
        ClaimingList(List<E> real,List<?> claimed){this.real=real;this.claimed=claimed;}
        @Override public E get(int index){return real.get(index);}
        @Override public int size(){return real.size();}
        @Override public boolean equals(Object other){calls.incrementAndGet();return claimed.equals(other);}
        @Override public int hashCode(){calls.incrementAndGet();return claimed.hashCode();}
    }
    @Test void itemLeafCodecEncodesRebuiltNativeCopiesNotForeignLists() {
        // D1b-3c-0（spec M1＋quality Q-M1）：attribute_modifiers、tool、food的codec由DataComponentTypes.CACHE（CodecCache，以
        // (codec, value, ops)為key的全域cache）包裝。三型的record不複製List，所以呼叫端可以放進foreign List：它的hashCode／equals會在
        // cache內、白名單再驗之前執行；若它宣稱等於另一個已快取的同型值，encode就會命中那個值的編碼（等值替換），而ref相同時再驗與
        // ref比對都抓不到。每個子案以同一份LeafContracts（同一份lease ops，production中同一closure的各ITEM leaf也共用）先擷取誠實值，
        // 再擷取「內容不同、但以ClaimingList宣稱等於誠實值」的本值。修正後codec只編碼以List.copyOf與已驗元素重建的原生copy：payload
        // 等於本值的獨立編碼（另一份lease manager），而且ClaimingList的equals／hashCode一次都不被呼叫。
        var id=Identifier.ofVanilla("base_attack_damage");
        java.util.function.Function<Double,List<AttributeModifiersComponent.Entry>> modifiers=amount->List.of(new AttributeModifiersComponent.Entry(
                EntityAttributes.GENERIC_ATTACK_DAMAGE,new EntityAttributeModifier(id,amount,EntityAttributeModifier.Operation.ADD_VALUE),
                AttributeModifierSlot.MAINHAND));
        java.util.function.Function<Float,List<ToolComponent.Rule>> rules=speed->List.of(new ToolComponent.Rule(
                RegistryEntryList.of(List.of(Blocks.COBWEB.getRegistryEntry())),Optional.of(speed),Optional.of(true)));
        java.util.function.Function<Integer,List<FoodComponent.StatusEffectEntry>> effects=duration->List.of(
                new FoodComponent.StatusEffectEntry(new StatusEffectInstance(StatusEffects.SPEED,duration),1f));
        java.util.function.Function<List<AttributeModifiersComponent.Entry>,ComponentMap> attribute=list->only(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                new AttributeModifiersComponent(list,true));
        java.util.function.Function<List<ToolComponent.Rule>,ComponentMap> tool=list->only(DataComponentTypes.TOOL,new ToolComponent(list,1f,1));
        java.util.function.Function<List<FoodComponent.StatusEffectEntry>,ComponentMap> food=list->only(DataComponentTypes.FOOD,
                new FoodComponent(6,7.2f,false,1.6f,Optional.empty(),list));
        record Case(String name,ComponentMap honest,ComponentMap own,ComponentMap lying,ClaimingList<?> list,Object lyingValue,Object honestValue) {}
        var cases=new java.util.ArrayList<Case>();
        {var lie=new ClaimingList<>(modifiers.apply(9.0),modifiers.apply(3.0));var honest=attribute.apply(modifiers.apply(3.0));var lying=attribute.apply(lie);
            cases.add(new Case("attribute_modifiers（amount 9.0宣稱等於3.0）",honest,attribute.apply(modifiers.apply(9.0)),lying,lie,
                    lying.get(DataComponentTypes.ATTRIBUTE_MODIFIERS),honest.get(DataComponentTypes.ATTRIBUTE_MODIFIERS)));}
        {var lie=new ClaimingList<>(rules.apply(4f),rules.apply(15f));var honest=tool.apply(rules.apply(15f));var lying=tool.apply(lie);
            cases.add(new Case("tool（rule speed 4宣稱等於15）",honest,tool.apply(rules.apply(4f)),lying,lie,
                    lying.get(DataComponentTypes.TOOL),honest.get(DataComponentTypes.TOOL)));}
        {var lie=new ClaimingList<>(effects.apply(999),effects.apply(100));var honest=food.apply(effects.apply(100));var lying=food.apply(lie);
            cases.add(new Case("food（effect duration 999宣稱等於100）",honest,food.apply(effects.apply(999)),lying,lie,
                    lying.get(DataComponentTypes.FOOD),honest.get(DataComponentTypes.FOOD)));}
        var checks=new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for(var c:cases)checks.add(()->assertAll("subcase="+c.name(),()->{
            // fixture前提：本值與誠實值的獨立編碼不同；CodecCache的key比對（probe.equals(stored)、hashCode）會把本值當成誠實值。
            var expected=seam(Items.DIAMOND,c.own());
            assertNotEquals(seam(Items.DIAMOND,c.honest()),expected,"fixture前提：本值與誠實值的編碼不同");
            assertTrue(c.lyingValue().equals(c.honestValue())&&c.lyingValue().hashCode()==c.honestValue().hashCode(),
                    "fixture前提：本值以ClaimingList宣稱等於誠實值");
            var leaves=leaves(new Minecraft121SettingsGraph(),itemRegistries());
            leaves.itemCapture(Items.DIAMOND,c.honest());
            c.list().calls.set(0);
            var payload=leaves.itemCapture(Items.DIAMOND,c.lying()).payload();
            int calls=c.list().calls.get();
            assertAll(()->assertEquals(expected,payload,"payload是本值的編碼，不是另一個值的快取編碼"),
                    ()->assertEquals(0,calls,"foreign List的equals／hashCode不得被呼叫"));
        }));
        assertAll(checks);
    }

    /**
     * 測試自訂的foreign Item子類（M5見證用）：只以Unsafe.allocateInstance建立，不跑建構子與欄位初始化，所以不會呼叫
     * Registries.ITEM.createEntry，也不註冊、不碰任何registry。它冒充bowl的registry entry；每個被覆寫的virtual method都計數。
     */
    static final class ForeignConvertsToItem extends Item {
        static final java.util.concurrent.atomic.AtomicInteger CALLS=new java.util.concurrent.atomic.AtomicInteger();
        /** 只為通過編譯，永不呼叫：實例一律以allocateForeign建立（呼叫它會經Item.&lt;init&gt;寫入全域registry）。 */
        private ForeignConvertsToItem() {super(new Item.Settings());}
        @Override public ComponentMap getComponents() {CALLS.incrementAndGet();return ComponentMap.EMPTY;}
        @Override public RegistryEntry.Reference<Item> getRegistryEntry() {CALLS.incrementAndGet();return Items.BOWL.getRegistryEntry();}
        @Override public void postProcessComponents(ItemStack stack) {CALLS.incrementAndGet();}
    }
    /** 以Unsafe.allocateInstance建立type的實例：不跑建構子與欄位初始化（D1b-3b D11），所以foreign Item不碰任何registry。 */
    private static <T> T allocateForeign(Class<T> type) throws Exception {
        // 反射取得sun.misc.Unsafe（jdk.unsupported模組open了sun.misc），不在編譯期引用內部API。
        var unsafeClass=Class.forName("sun.misc.Unsafe");
        var field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    @Test void itemLeafFoodConvertsToItemSourceIsCheckedBeforeCodec() throws Exception {
        // 設計審查M5：解碼using_converts_to時，ItemStack建構子會先執行目標item的postProcessComponents（virtual），所以codec之前要先對
        // 目標item跑executedOwnerClasses的source檢查。foreign item冒充bowl的registry entry：沒有事前檢查時，encode寫出minecraft:bowl、
        // parse得到真bowl，原值與重建值的ref也是同一個bowl entry，會被靜默接受；有事前檢查時在codec之前以NATIVE_DEFINITION_SOURCE拒，
        // 且全程不呼叫foreign item的任何virtual method。
        var foreign=allocateForeign(ForeignConvertsToItem.class);
        var stack=new ItemStack(foreign);
        assertAll("fixture前提",
                ()->assertFalse(nativeSource(ForeignConvertsToItem.class),"foreign item class不是原生source"),
                ()->assertSame(foreign,stack.getItem()),
                ()->assertEquals(1,stack.getCount()),
                ()->assertTrue(stack.getComponentChanges().isEmpty(),"沒有patch"),
                ()->assertSame(Items.BOWL.getRegistryEntry(),stack.getRegistryEntry(),"冒充bowl的registry entry"));
        var food=only(DataComponentTypes.FOOD,new FoodComponent(6,7.2f,false,1.6f,Optional.of(stack),List.of()));
        ForeignConvertsToItem.CALLS.set(0);
        var failure=assertThrows(Minecraft121SettingsGraph.Failure.class,()->seam(Items.DIAMOND,food));
        assertAll(
                ()->assertEquals("NATIVE_DEFINITION_SOURCE",failure.getMessage()),
                ()->assertEquals(0,ForeignConvertsToItem.CALLS.get(),"source檢查之前不得呼叫foreign item的任何virtual method"));
    }
    /**
     * 測試自訂的foreign Item子類（S0-5 ITEM seam自驗的行為見證用）：同樣只以allocateForeign建立，不註冊、不碰任何registry。
     * getRequiredFeatures回傳vanilla子集：拿掉seam的source自驗時，seam會一路接受，而不是在feature檢查改碼。每個被覆寫的virtual
     * method都計數。
     */
    static final class ForeignSeamItem extends Item {
        static final java.util.concurrent.atomic.AtomicInteger CALLS=new java.util.concurrent.atomic.AtomicInteger();
        /** 只為通過編譯，永不呼叫：實例一律以allocateForeign建立（呼叫它會經Item.&lt;init&gt;寫入全域registry）。 */
        private ForeignSeamItem() {super(new Item.Settings());}
        @Override public FeatureSet getRequiredFeatures() {CALLS.incrementAndGet();return FeatureFlags.VANILLA_FEATURES;}
        @Override public ComponentMap getComponents() {CALLS.incrementAndGet();return Items.DIAMOND.getComponents();}
        @Override public RegistryEntry.Reference<Item> getRegistryEntry() {CALLS.incrementAndGet();return Items.DIAMOND.getRegistryEntry();}
    }
    @Test void itemCaptureSeamChecksItemSourceItself() throws Exception {
        // S0-5（D1b-3設計§5；D1b-3b spec review的「交給後續」）：ITEM seam開頭自行對executedOwnerClasses(item)逐項驗原生source，
        // 不依賴item(...)的步驟2。以Unsafe配置的foreign Item不在任何registry，closure到不了它，所以直接呼叫seam，傳入diamond的
        // default component。seam自驗在時：NATIVE_DEFINITION_SOURCE，且全程不呼叫foreign item的任何virtual method。拿掉seam自驗時
        // （見證W-ITEM-SEAM），seam會呼叫getRequiredFeatures，並以diamond的default component接受這個foreign item。
        var foreign=allocateForeign(ForeignSeamItem.class);
        assertAll("fixture前提",
                ()->assertFalse(nativeSource(ForeignSeamItem.class),"foreign item class不是原生source"),
                ()->assertEquals(List.of(ForeignSeamItem.class),executedOwnerClasses(foreign),"非原生item只回傳item class本身"));
        ForeignSeamItem.CALLS.set(0);
        var failure=assertThrows(Minecraft121SettingsGraph.Failure.class,()->capture(foreign,Items.DIAMOND.getComponents()));
        assertAll(
                ()->assertEquals("NATIVE_DEFINITION_SOURCE",failure.getMessage()),
                ()->assertEquals(0,ForeignSeamItem.CALLS.get(),"source自驗之前不得呼叫foreign item的任何virtual method"));
    }

    /** foreign的EntityAttribute子類：行為正常，只有source不是固定JAR；建構子只寫欄位，不碰任何registry（javap）。 */
    static final class ForeignAttribute extends net.minecraft.entity.attribute.EntityAttribute {
        ForeignAttribute() {super("attribute.test.foreign",1.0);}
    }
    /** foreign的StatusEffect子類：同上；建構子只建本物件自己的map與欄位，不碰任何registry（javap）。 */
    static final class ForeignEffect extends net.minecraft.entity.effect.StatusEffect {
        ForeignEffect() {super(net.minecraft.entity.effect.StatusEffectCategory.NEUTRAL,0);}
    }
    /** 局部registry（新物件，已freeze，不登記到Registries.REGISTRIES）中key對應value的entry。 */
    @SuppressWarnings({"rawtypes","unchecked"})
    private static RegistryEntry.Reference<?> localEntry(net.minecraft.registry.RegistryKey<? extends net.minecraft.registry.Registry<?>> registry,
            RegistryKey<?> key,Object value) {
        var local=new net.minecraft.registry.SimpleRegistry((RegistryKey)registry,com.mojang.serialization.Lifecycle.stable());
        local.add((RegistryKey)key,value,net.minecraft.registry.entry.RegistryEntryInfo.DEFAULT);local.freeze();
        return (RegistryEntry.Reference<?>)local.getEntry((RegistryKey)key).orElseThrow();
    }
    @Test void attributeAndStatusEffectLeavesCheckSourceBeforeIdentity() {
        // D1b-3設計審查M3：兩個leaf的步驟是registry identity → class／source（instanceof基底類別加上value class的原生source）→ node與
        // entry identity →（STATUS_EFFECT）feature → payload。vanilla使用子類（generic.armor是ClampedEntityAttribute、regeneration是
        // RegenerationStatusEffect），所以正向對照用子類。foreign子類放在局部registry，lease仍是全域registry，所以步驟1通過、停在步驟2的
        // NATIVE_DEFINITION_SOURCE；拿掉source檢查時會落到步驟3的STATIC_LEAF_IDENTITY_MISMATCH（全域registry裡沒有這個value）。
        var registries=withItemLeafRegistries();
        var armorKey=EntityAttributes.GENERIC_ARMOR.getKey().orElseThrow();var armor=Registries.ATTRIBUTE.getEntry(armorKey).orElseThrow();
        var regenerationKey=StatusEffects.REGENERATION.getKey().orElseThrow();
        var regeneration=Registries.STATUS_EFFECT.getEntry(regenerationKey).orElseThrow();
        var armorNode=attributeNode("minecraft:generic.armor");var regenerationNode=effectNode("minecraft:regeneration");
        var attributeLeaf=new NbtCompound();attributeLeaf.putString("Contract","quantumchamber:m6-static-attribute-leaf-v1");
        var effectLeaf=new NbtCompound();effectLeaf.putString("Contract","quantumchamber:m6-static-status-effect-leaf-v1");
        effectLeaf.put("RequiredFeatures",strings("minecraft:vanilla"));
        java.util.function.Function<DynamicRegistryManager,Minecraft121RegistryLeafContracts> leavesWith=r->leaves(new Minecraft121SettingsGraph(),r);
        assertAll(
                ()->assertAll("fixture前提：vanilla值是子類",
                        ()->assertInstanceOf(net.minecraft.entity.attribute.ClampedEntityAttribute.class,armor.value()),
                        ()->assertNotSame(net.minecraft.entity.effect.StatusEffect.class,regeneration.value().getClass())),
                ()->assertAll("subcase=正向對照",
                        ()->assertEquals(attributeLeaf,leavesWith.apply(registries).attribute(armorNode,armorKey,armor)),
                        ()->assertEquals(effectLeaf,leavesWith.apply(registries).statusEffect(regenerationNode,regenerationKey,regeneration))),
                ()->assertAll("subcase=foreign子類（局部registry的entry）",
                        ()->rejects("NATIVE_DEFINITION_SOURCE",()->leavesWith.apply(registries).attribute(armorNode,armorKey,
                                localEntry(RegistryKeys.ATTRIBUTE,armorKey,new ForeignAttribute()))),
                        ()->rejects("NATIVE_DEFINITION_SOURCE",()->leavesWith.apply(registries).statusEffect(regenerationNode,regenerationKey,
                                localEntry(RegistryKeys.STATUS_EFFECT,regenerationKey,new ForeignEffect())))),
                ()->assertAll("subcase=vanilla值放在局部registry的entry（不是全域registry的entry）",
                        ()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",()->leavesWith.apply(registries).attribute(armorNode,armorKey,
                                localEntry(RegistryKeys.ATTRIBUTE,armorKey,armor.value()))),
                        ()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",()->leavesWith.apply(registries).statusEffect(regenerationNode,regenerationKey,
                                localEntry(RegistryKeys.STATUS_EFFECT,regenerationKey,regeneration.value())))),
                ()->assertAll("subcase=node與key不符",
                        ()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",()->leavesWith.apply(registries).attribute(attributeNode("minecraft:generic.luck"),armorKey,armor)),
                        ()->rejects("STATIC_LEAF_IDENTITY_MISMATCH",()->leavesWith.apply(registries).statusEffect(itemNode("minecraft:regeneration"),
                                regenerationKey,regeneration))),
                ()->assertAll("subcase=lease的registry缺席或是另一個物件",
                        ()->rejects("REQUIRED_REGISTRY_MISSING",()->leavesWith.apply(itemLeafRegistriesWithout(RegistryKeys.ATTRIBUTE)).attribute(armorNode,armorKey,armor)),
                        ()->rejects("STATIC_REGISTRY_OWNER_MISMATCH",()->leavesWith.apply(itemLeafRegistriesWithForeign(RegistryKeys.ATTRIBUTE)).attribute(armorNode,armorKey,armor)),
                        ()->rejects("REQUIRED_REGISTRY_MISSING",()->leavesWith.apply(itemLeafRegistriesWithout(RegistryKeys.STATUS_EFFECT))
                                .statusEffect(regenerationNode,regenerationKey,regeneration)),
                        ()->rejects("STATIC_REGISTRY_OWNER_MISMATCH",()->leavesWith.apply(itemLeafRegistriesWithForeign(RegistryKeys.STATUS_EFFECT))
                                .statusEffect(regenerationNode,regenerationKey,regeneration))));
    }

    /** 測試端獨立走訪（typed record accessor，不呼叫production的契約或抽取器）：一條ref寫成「path | registry | id」。 */
    private static String refTriple(String path,RegistryEntry<?> entry) {
        var key=entry.getKey().orElseThrow();
        return path+" | "+key.getRegistry()+" | "+key.getValue();
    }
    /**
     * M9的ref獨立走訪：只走帶引用的三型（與refBearingItemDefaultTypes()、production的型別契約是同一份設計分析）。新增帶引用的型別時，
     * production契約的refs與registries、refBearingItemDefaultTypes()與本method三處要同步；漏了三處時的最後一道防線是M9的NBT交叉檢查
     * （payloadRegistryIdStrings，與型別模型無關）。
     */
    private static java.util.Map<String,RegistryEntry<?>> independentRefs(Item item) {
        var refs=new java.util.TreeMap<String,RegistryEntry<?>>();var components=item.getComponents();
        var modifiers=components.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if(modifiers!=null)for(int i=0;i<modifiers.modifiers().size();i++)
            refs.put("components/minecraft:attribute_modifiers/modifiers/"+i+"/type",modifiers.modifiers().get(i).attribute());
        var food=components.get(DataComponentTypes.FOOD);
        if(food!=null) {
            food.usingConvertsTo().ifPresent(stack->refs.put("components/minecraft:food/using_converts_to/id",stack.getRegistryEntry()));
            for(int i=0;i<food.effects().size();i++)
                refs.put("components/minecraft:food/effects/"+i+"/effect/id",food.effects().get(i).effect().getEffectType());
        }
        var tool=components.get(DataComponentTypes.TOOL);
        if(tool!=null)for(int i=0;i<tool.rules().size();i++) {
            var blocks=tool.rules().get(i).blocks();
            for(int j=0;j<blocks.size();j++)refs.put("components/minecraft:tool/rules/"+i+"/blocks/"+j,blocks.get(j));
        }
        return refs;
    }
    /**
     * 已知的literal Identifier位置（相對DefaultComponents的NBT path）：attribute modifier的id以Identifier.CODEC編碼、不綁registry
     * （設計§2.2）。交叉檢查只排除這些位置。
     */
    private static final java.util.regex.Pattern LITERAL_IDENTIFIER_PATHS=java.util.regex.Pattern.compile(
            "minecraft:attribute_modifiers/modifiers/\\d+/id");
    /**
     * quality Q-M3的交叉檢查（與型別模型無關）：遞迴走訪DefaultComponents NBT，收集每個NbtString值（compound的鍵是component型別id，
     * 不收），回傳其中能parse成Identifier、且存在於固定聯集某個registry的值（path→id）；LITERAL_IDENTIFIER_PATHS的位置排除。
     */
    private static java.util.Map<String,String> payloadRegistryIdStrings(NbtCompound defaults) {
        var strings=new java.util.TreeMap<String,String>();collectStrings("",defaults,strings);
        var ids=new java.util.TreeMap<String,String>();
        for(var entry:strings.entrySet()) {
            if(LITERAL_IDENTIFIER_PATHS.matcher(entry.getKey()).matches())continue;
            var id=Identifier.tryParse(entry.getValue());
            if(id!=null&&itemLeafStaticRegistries().stream().anyMatch(registry->registry.containsId(id)))ids.put(entry.getKey(),id.toString());
        }
        return ids;
    }
    private static void collectStrings(String path,NbtElement element,java.util.Map<String,String> out) {
        switch(element) {
            case NbtString string -> out.put(path,string.asString());
            case NbtCompound compound -> {for(String key:compound.getKeys())collectStrings(path.isEmpty()?key:path+"/"+key,compound.get(key),out);}
            case NbtList list -> {for(int i=0;i<list.size();i++)collectStrings(path+"/"+i,list.get(i),out);}
            default -> {}
        }
    }
    @Test void everyAcceptedVanillaItemLeafRefsMatchIndependentWalk() {
        // 設計審查M9：ref抽取的全族群獨立oracle。對每個測試端判定為已審的minecraft item，以typed record獨立走訪default component，
        // 算出(path, registry, id)集合，逐項比對itemCapture的輸出與item(...)經sink送出的ref；entry另以identity比對。漏抽一條ref不會
        // fail closed，只會讓閉包不完整，所以要靠這個oracle守。不斷言數量，只要求至少有一件帶ref。
        // D1b-3c-0（quality Q-M3）：上面的走訪與結構測試用的是同一份人工型別模型；另加與模型無關的交叉檢查：payload的DefaultComponents
        // 中，凡是能parse成Identifier、且存在於固定聯集某個registry的字串值（已知literal位置除外），都必須是該item某條ref的id。新型別
        // 被宣告成NO_REFS、又同時漏列在refBearingItemDefaultTypes()與independentRefs時，只有這道檢查會失敗（見證W-M9-CROSS）。
        var reviewed=reviewedItemDefaults();var mismatched=new java.util.TreeMap<String,String>();int[] counts=new int[3];
        for(var entry:(Iterable<RegistryEntry.Reference<Item>>)Registries.ITEM.streamEntries()::iterator) {
            var id=entry.registryKey().getValue();var item=entry.value();
            if(!id.getNamespace().equals("minecraft")||!allReviewed(item,reviewed,null))continue;
            counts[0]++;
            var expected=independentRefs(item);if(!expected.isEmpty())counts[1]++;
            var expectedTriples=expected.entrySet().stream().map(e->refTriple(e.getKey(),e.getValue())).sorted().toList();
            Minecraft121RegistryLeafContracts.ItemCapture capture;
            var emitted=new java.util.ArrayList<java.util.Map.Entry<Minecraft121SettingsGraph.Key,Minecraft121ItemComponentContracts.Ref>>();
            try {capture=capture(item,item.getComponents());itemLeaf(item,emitted);}
            catch(Minecraft121SettingsGraph.Failure failure){mismatched.put(id.toString(),"failure="+failure.getMessage());continue;}
            var captured=capture.references();
            var capturedTriples=captured.stream().map(ref->refTriple(ref.path(),refEntry(ref))).sorted().toList();
            var emittedTriples=emitted.stream().map(e->refTriple(e.getValue().path(),refEntry(e.getValue()))).sorted().toList();
            var problems=new java.util.ArrayList<String>();
            if(!expectedTriples.equals(capturedTriples))problems.add("capture="+capturedTriples+" expected="+expectedTriples);
            if(!capturedTriples.equals(emittedTriples))problems.add("sink="+emittedTriples);
            if(!emitted.stream().allMatch(e->e.getKey().equals(itemNode(id.toString()))))problems.add("sink的from不是該ITEM node");
            for(var ref:captured)if(expected.get(ref.path())!=refEntry(ref))problems.add("identity:"+ref.path());
            // 與型別模型無關的交叉檢查（quality Q-M3）：payload中的registry id字串都必須是某條ref的id。
            var refIds=new java.util.TreeSet<String>();for(var ref:captured)refIds.add(refEntry(ref).registryKey().getValue().toString());
            var registryIds=payloadRegistryIdStrings(capture.payload().getCompound("DefaultComponents"));
            if(!registryIds.isEmpty())counts[2]++;
            for(var string:registryIds.entrySet())if(!refIds.contains(string.getValue()))
                problems.add("crossCheck:payload的"+string.getKey()+"="+string.getValue()+"不是任何ref的id");
            if(!problems.isEmpty())mismatched.put(id.toString(),String.join("; ",problems));
        }
        assertAll(
                ()->assertTrue(counts[0]>0,"fixture前提：有已審的minecraft item"),
                ()->assertTrue(counts[1]>0,"fixture前提：至少一件已審item帶ref"),
                ()->assertTrue(counts[2]>0,"fixture前提：交叉檢查至少在一件item的payload找到registry id字串（不是空轉）"),
                ()->assertEquals(java.util.Map.of(),mismatched,"每個已審vanilla item抽出的ref恰等於測試端獨立走訪的結果，且涵蓋payload中的registry id"));
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
        // 不gating：空陣列（例如突變下沒有任何ACCEPTED）只回報count=0，不丟例外（D1b-3-0 spec M1）。
        if(values.length==0)return "count=0";
        var sorted=values.clone();java.util.Arrays.sort(sorted);long total=0;for(long value:sorted)total+=value;
        java.util.function.IntFunction<Long> at=p->sorted[Math.min(sorted.length-1,(int)Math.ceil(p/100.0*sorted.length)-1)];
        return "count="+sorted.length+" min="+sorted[0]+" p50="+at.apply(50)+" p90="+at.apply(90)+" p99="+at.apply(99)
                +" max="+sorted[sorted.length-1]+" mean="+(total/sorted.length)+" total="+total;
    }
    /** 一次leaf擷取；measureLeafCosts只計時這個呼叫（graph與leaves在計時之外建立）。 */
    @FunctionalInterface private interface LeafCapture<T> {
        NbtCompound capture(Minecraft121RegistryLeafContracts leaves,Minecraft121SettingsGraph.Key node,
                net.minecraft.registry.RegistryKey<T> key,net.minecraft.registry.entry.RegistryEntry.Reference<T> entry);
    }
    /**
     * pass0的結果碼、冷耗時、payload bytes、payload與單節點canonical的SHA-256（被拒為null），以及pass1的暖耗時；陣列與entries同序。
     */
    private record LeafCosts<T>(java.util.List<net.minecraft.registry.entry.RegistryEntry.Reference<T>> entries,String[] result,
            long[] cold,long[] warm,long[] bytes,NbtCompound[] payloads,String[] digests) {}
    private static String sha256(byte[] bytes) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException missing) {throw new IllegalStateException(missing);}
    }
    /**
     * BLOCK與ITEM診斷共用的冷暖量測：registry的每個entry各自建graph與leaves（不開lease、不跨leaf共用），逐一擷取兩輪。
     * 非Failure的RuntimeException只記class名（診斷不gating）。
     */
    private static <T> LeafCosts<T> measureLeafCosts(net.minecraft.registry.Registry<T> registry,DynamicRegistryManager registries,
            java.util.function.Function<String,Minecraft121SettingsGraph.Key> nodeOf,LeafCapture<T> capture) {
        var entries=new java.util.ArrayList<net.minecraft.registry.entry.RegistryEntry.Reference<T>>();
        registry.streamEntries().forEach(entries::add);
        int count=entries.size();long[] cold=new long[count],warm=new long[count],bytes=new long[count];
        String[] result=new String[count],digests=new String[count];var payloads=new NbtCompound[count];
        for(int pass=0;pass<2;pass++) {
            for(int i=0;i<count;i++) {
                var entry=entries.get(i);var key=entry.registryKey();var node=nodeOf.apply(key.getValue().toString());
                var graph=new Minecraft121SettingsGraph();var leaves=leaves(graph,registries);
                String code="ACCEPTED";NbtCompound payload=null;
                long start=System.nanoTime();
                try {payload=capture.capture(leaves,node,key,entry);}
                catch(Minecraft121SettingsGraph.Failure failure){code=failure.getMessage();}
                catch(RuntimeException other){code=other.getClass().getName();}
                long elapsed=System.nanoTime()-start;
                if(pass==0) {
                    cold[i]=elapsed;result[i]=code;payloads[i]=payload;
                    if(payload!=null){var finished=graph.finish();bytes[i]=payloadBytes(finished,node);digests[i]=sha256(finished);}
                }
                else warm[i]=elapsed;
            }
        }
        return new LeafCosts<>(entries,result,cold,warm,bytes,payloads,digests);
    }
    /**
     * C1d-D1b-1 BLOCK leaf成本診斷，不gating：不開lease、不斷言數量或時間，也不用Assumptions跳過；輸出寫進system-out，供I-1評估
     * static leaf是否移出lease。pass0是本測試第一次擷取每個block，但同JVM的其他測試（JUnit預設method次序不保證）可能已先擷取
     * 部分block，所以只是本測試的冷值；pass1是同一JVM再擷取一次的暖值。BLOCK的可用性gating由everyVanillaBlockLeafIsAccepted負責。
     *
     * <p>退場條件（D1b-2 quality M2(a)）：D1b-3結案或I-1定案（以先到者為準）時，刪除逐行的D1B1_LEAF_COST_ENTRY，只保留摘要行
     * （SUMMARY、CODES、COLD／WARM等）；evidence CSV改由當輪focused XML擷取。不用system property開關：test JVM拿不到Gradle的-D，
     * 要改build.gradle，超出owner。
     */
    @Test void staticBlockLeafCostDiagnostic() {
        var costs=measureLeafCosts(Registries.BLOCK,new DynamicRegistryManager.ImmutableImpl(java.util.List.of(Registries.BLOCK)),
                Minecraft121RegistryLeafTestFixtures::blockNode,(leaves,node,key,entry)->leaves.block(node,key,entry));
        var entries=costs.entries();int count=entries.size();
        String[] result=costs.result();long[] cold=costs.cold(),warm=costs.warm(),bytes=costs.bytes();
        int[] properties=new int[count],values=new int[count];
        for(int i=0;i<count;i++)if(costs.payloads()[i]!=null) {
            var domains=costs.payloads()[i].getCompound("PropertyDomains");properties[i]=domains.getSize();
            for(String name:domains.getKeys())values[i]+=domains.getList(name,NbtElement.STRING_TYPE).size();
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
    }
    /**
     * C1d-D1b-2 ITEM leaf成本診斷，同樣不gating；pass0／pass1的意義與BLOCK相同。另對minecraft namespace每個item的每個default
     * component，以只含該component的map走seam（itemCapture，DIAMOND當feature載體），得到逐型別判定。REJECTED_TYPES是「含該型別被拒的
     * item數」，ACCEPTED_TYPES是「該型別被接受的component數」。拒絕原因的gating由everyVanillaItemLeafIsAcceptedOrRejectedOnlyForUnreviewedTypes負責。
     *
     * <p>D1b-3c-0起ENTRY行另帶leafCanonicalSha256（該item單節點圖canonical bytes的SHA-256；被拒為NA），ACCEPTED_DIGEST是依id排序的
     * 「id=hash」逐行串接後的SHA-256：只比payload長度不夠（D1b-3a spec M4），各子片以這兩者逐件比對既有ACCEPTED件的payload不退化。
     *
     * <p>退場條件同BLOCK：D1b-3結案或I-1定案（以先到者為準）時，刪除逐行的D1B2_ITEM_LEAF_COST_ENTRY，只保留摘要行
     * （SUMMARY、ACCEPTED_DIGEST、CODES、REJECTED_TYPES、COLD／WARM等）。
     */
    @Test void staticItemLeafCostDiagnostic() {
        var costs=measureLeafCosts(Registries.ITEM,itemRegistries(),Minecraft121RegistryLeafTestFixtures::itemNode,
                (leaves,node,key,entry)->leaves.item(node,key,entry));
        var entries=costs.entries();int count=entries.size();
        String[] result=costs.result(),digests=costs.digests();long[] cold=costs.cold(),warm=costs.warm(),bytes=costs.bytes();
        var acceptedDigests=new java.util.TreeMap<String,String>();
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
                try {seam(Items.DIAMOND,single(component));verdict="ACCEPTED";}
                catch(Minecraft121SettingsGraph.Failure failure){verdict=failure.getMessage();}
                catch(RuntimeException other){verdict=other.getClass().getName();}
                if(verdict.equals("ACCEPTED"))acceptedTypes.merge(type,1,Integer::sum);else rejected.add(type);
            }
            for(String type:rejected)rejectedTypes.merge(type,1,Integer::sum);
            if(!result[i].equals("ACCEPTED"))for(String type:rejected)
                rejectedTypesByCode.computeIfAbsent(result[i],k->new java.util.TreeMap<>()).merge(type,1,Integer::sum);
            if(digests[i]!=null)acceptedDigests.put(id.toString(),digests[i]);
            System.out.println("D1B2_ITEM_LEAF_COST_ENTRY id="+id+" result="+result[i]+" coldNs="+cold[i]+" warmNs="+warm[i]
                    +" payloadBytes="+bytes[i]+" leafCanonicalSha256="+(digests[i]==null?"NA":digests[i])+" rejectedTypes="+rejected);
        }
        var digestLines=new StringBuilder();
        for(var digest:acceptedDigests.entrySet())digestLines.append(digest.getKey()).append('=').append(digest.getValue()).append('\n');
        System.out.println("D1B2_ITEM_LEAF_COST_SUMMARY leaves="+count+" accepted="+accepted+" rejected="+(count-accepted)+" totalPayloadBytes="+totalBytes);
        System.out.println("D1B2_ITEM_LEAF_COST_ACCEPTED_DIGEST accepted="+acceptedDigests.size()+" sha256="
                +sha256(digestLines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+" note=sha256-of-sorted-id=leafCanonicalSha256-lines");
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
    /**
     * C1d-D1b-3 closure層級的ITEM成本診斷（設計§6.3），不gating：不開lease、不斷言數量或時間，也不用Assumptions跳過；drain的Failure
     * 只記碼。一個graph、一個closure（production形狀：LeafContracts在closure建構時建立，跨leaf共用），對選定的minecraft ITEM各
     * reference一次後drain並finish；計時涵蓋closure建構、reference、drain與finish，不含之後的canonical解碼。
     *
     * <p>取樣的調整（D1b-3a spec M2）：設計§6.3寫「對全部minecraft ITEM執行reference」，但只要有一個被拒item，drain就會fail closed、
     * 整輪量不到東西，所以三組item都由測試端oracle選出（不呼叫production，選取本身不暖機codec）：d1b2-reviewed只含D1b-2已審型別
     * （換源前後可比的同一組），d1b3a-reviewed是D1b-3a為止的已審型別（3b之前的那一組），reviewed是目前全部已審型別。node與edge數
     * 從finish後的canonical解出（量測值，不是由item數推算）；失敗時印NA。另印reviewed組中ITEM node出邊依target registry的條數。
     * pass0是本測試第一次closure（同JVM其他測試可能已暖機部分codec，所以只是本測試的冷值），pass1是暖值。另量ops建立成本：換源前
     * 每個leaf都建一次只含全域static registries的ops，換源後每個closure只建一次lease ops（兩者都是lazy，只量到物件配置）。D1b-3b
     * 起ITEM leaf有出邊（ATTRIBUTE、STATUS_EFFECT、ITEM、BLOCK），但仍沒有tag，不涉及H-TAG的下界。
     */
    @Test void staticItemClosureCostDiagnostic() throws Exception {
        var groups=new java.util.LinkedHashMap<String,java.util.List<net.minecraft.registry.RegistryKey<Item>>>();
        var tables=new java.util.LinkedHashMap<String,java.util.Map<net.minecraft.component.ComponentType<?>,java.util.function.Predicate<Object>>>();
        tables.put("d1b2-reviewed",d1b2ReviewedItemDefaults());tables.put("d1b3a-reviewed",d1b3aCumulativeItemDefaults());
        tables.put("reviewed",reviewedItemDefaults());
        for(var name:tables.keySet())groups.put(name,new java.util.ArrayList<>());
        for(var entry:(Iterable<net.minecraft.registry.entry.RegistryEntry.Reference<Item>>)Registries.ITEM.streamEntries()::iterator) {
            if(!entry.registryKey().getValue().getNamespace().equals("minecraft"))continue;
            for(var table:tables.entrySet())if(allReviewed(entry.value(),table.getValue(),null))groups.get(table.getKey()).add(entry.registryKey());
        }
        try(var resources=new net.minecraft.resource.LifecycledResourceManagerImpl(net.minecraft.resource.ResourceType.SERVER_DATA,java.util.List.of())) {
            for(var group:groups.entrySet())for(int pass=0;pass<2;pass++) {
                var keys=group.getValue();String code="ACCEPTED";byte[] finished=null;
                long start=System.nanoTime();
                try {
                    var graph=new Minecraft121SettingsGraph();graph.payload(ROOT,NbtInt.of(1));
                    var closure=new Minecraft121RegistryClosure(graph,itemRegistries(),resources);
                    for(int i=0;i<keys.size();i++)closure.reference(ROOT,"items/"+i,keys.get(i));
                    closure.drain();finished=graph.finish();
                } catch(Minecraft121SettingsGraph.Failure failure){code=failure.getMessage();}
                long elapsed=System.nanoTime()-start;
                String nodes="NA",edges="NA",itemOutEdges="NA";
                if(finished!=null) {
                    var canonical=canonicalNodes(finished);nodes=String.valueOf(canonical.nodes().size());edges=String.valueOf(canonical.edges().size());
                    var byTarget=new java.util.TreeMap<String,Integer>();
                    for(var edge:canonical.edges())if(edge.from().kind()==Minecraft121SettingsGraph.Kind.REGISTRY
                            &&edge.from().registry().equals("minecraft:item"))byTarget.merge(edge.target().registry(),1,Integer::sum);
                    itemOutEdges=byTarget.toString();
                }
                System.out.println("D1B3_ITEM_CLOSURE_COST group="+group.getKey()+" pass="+(pass==0?"cold":"warm")+" items="+keys.size()
                        +" nodes="+nodes+" edges="+edges+" itemOutEdgesByTarget="+itemOutEdges+" result="+code+" ns="+elapsed
                        +" canonicalBytes="+(finished==null?0:finished.length)+" countSource=measured-from-canonical");
            }
        }
        int rounds=10000;long sink=0;var lease=itemRegistries();
        long start=System.nanoTime();
        for(int i=0;i<rounds;i++)sink+=System.identityHashCode(DynamicRegistryManager.of(Registries.REGISTRIES).getOps(NbtOps.INSTANCE));
        long staticOps=System.nanoTime()-start;
        start=System.nanoTime();
        for(int i=0;i<rounds;i++)sink+=System.identityHashCode(lease.getOps(NbtOps.INSTANCE));
        long leaseOps=System.nanoTime()-start;
        System.out.println("D1B3_ITEM_OPS_COST rounds="+rounds+" staticOpsTotalNs="+staticOps+" leaseOpsTotalNs="+leaseOps
                +" sink="+(sink&1)+" note=before-D1b-3a-static-ops-built-per-leaf;after-one-lease-ops-per-closure");
    }
}
