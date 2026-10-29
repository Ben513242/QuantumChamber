package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.Minecraft121RegistryLeafContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
 * C1d-D1b-1 BLOCK static registry leaf的單元測試與成本診斷。
 * 只讀全域static registry：不建立或註冊Block／Item，不populate tag，也不改任何instance欄位；不引用ModBlocks／ModItems。
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

    /** 單節點圖的finish長度扣掉固定開銷（DOMAIN、node數、key、edge數），即該node的payload bytes。 */
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
        System.out.println("D1B1_LEAF_COST_FIRST_LEAF_NS id="+entries.get(0).registryKey().getValue()+" coldNs="+cold[0]+"（本JVM中本測試的第一個leaf，最冷）");
        System.out.println("D1B1_LEAF_COST_COLD_NS "+stats(cold));
        System.out.println("D1B1_LEAF_COST_WARM_NS "+stats(warm));
        System.out.println("D1B1_LEAF_COST_REFERENCE owPaletteOccurrences=17262 owPaletteUniqueBlocks=375 owAllConsumerBlocks=924 unionPaletteUniqueBlocks=401"
                +" source=B28 census（D1B-PROPOSAL-v2 §2、§6 的常數，不是本測試量的）");
    }
}
