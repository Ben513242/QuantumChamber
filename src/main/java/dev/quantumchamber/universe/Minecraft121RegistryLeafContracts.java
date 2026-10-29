package dev.quantumchamber.universe;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentMap;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.*;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * C1d-D1b static registry leaf的owner；admit BLOCK與ITEM兩種，leaf都沒有出發邊。
 * <ul>
 * <li>BLOCK payload（Contract=quantumchamber:m6-static-block-leaf-v1）是生成consumer實際解碼用到的state definition與
 * default state，加上RequiredFeatures。</li>
 * <li>ITEM payload（Contract=quantumchamber:m6-static-item-leaf-v1）是已審型別表（Minecraft121ItemComponentContracts）內的
 * default ComponentMap（原生ComponentMap.CODEC以lease registries的ops編碼），加上RequiredFeatures。RequiredFeatures有兩種原生
 * 委派：BlockItem委派getBlock().getRequiredFeatures()，SpawnEggItem委派建構時EntityType的getRequiredFeatures()，所以這個值由
 * Block或EntityType推導；生成consumer不讀BlockItem.getBlock，leaf也不建Item→Block或Item→EntityType邊。D1b-3a起，literal與
 * 空DEFAULT的8型也已審（fire_resistant、enchantment_glint_override、ominous_bottle_amplifier；potion_contents、
 * suspicious_stew_effects、container、banner_patterns、charged_projectiles只收空值）；會引用其他registry或tag的型別（tool、
 * 非空attribute_modifiers、food、jukebox_playable等）仍fail closed，屬D1b-3b～3d。</li>
 * </ul>
 *
 * <p>exact原生class加上CodeSource相同，只證明這個value執行的程式碼來自固定1.21 JAR，不證明instance的Settings
 * （hardness、luminance、mapColor…）或constructor參數是vanilla值：以原生class註冊在非minecraft namespace的block或item會被admit，
 * 它的Settings與constructor參數不在FP可偵測的保證內。Block／Item的Java行為、BlockTypes.CODEC、Block方法中hardcoded的tag讀取、
 * asItem、recipeRemainder、翻譯鍵與state id都不入值。
 *
 * <p>檢查順序固定：先registry identity（BLOCK leaf只驗BLOCK；ITEM leaf驗Minecraft121ItemComponentContracts的固定static registry
 * 聯集；lease缺席為REQUIRED_REGISTRY_MISSING，同key另一個物件為STATIC_REGISTRY_OWNER_MISMATCH），再class／source（步驟2），
 * 步驟2通過之後才呼叫value的virtual method；ITEM的步驟3（BlockItem的block class、SpawnEggItem的EntityType class）本身就要呼叫
 * getBlock()／getEntityType()，所以只在item class已驗為原生之後才呼叫。兩個seam（blockPayload／itemCapture）開頭另自行驗同一組
 * source（itemCapture也自驗registry identity聯集），不依賴呼叫端。內部require丟出的Failure原樣交回；其他RuntimeException才改成
 * 該步驟的具名碼並以initCause保留原因。寬catch的try內只放該碼所指的來源讀取，以及前置require已保證不丟例外的本地操作；
 * checkpoint、graph與其他codec等可能丟非Failure例外的呼叫一律放在try外，否則會被改碼。
 */
final class Minecraft121RegistryLeafContracts {
    private static final String BLOCK_CONTRACT="quantumchamber:m6-static-block-leaf-v1";
    private static final String ITEM_CONTRACT="quantumchamber:m6-static-item-leaf-v1";
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    /**
     * ITEM component codec的ops：建構時（closure建構時，lease之外）由lease registries建一次，跨leaf共用。RegistryOps對各registry的
     * 解析是lazy的：第一次查詢某registry時才取wrapper並快取，所以實際解析發生在lease內、itemCapture步驟7的try內（D1b-3a quality
     * M1(b)）。
     */
    private final RegistryOps<NbtElement> ops;
    /** ITEM leaf出邊的sink：item(...)寫入payload之後，對itemCapture抽出的每個ref依序呼叫一次（from是該ITEM node）。 */
    private final BiConsumer<Key,Minecraft121ItemComponentContracts.Ref> references;

    /** ITEM leaf seam的回傳：payload與從default component抽出的ref（依component型別id排序，同型內依抽取次序）。 */
    record ItemCapture(NbtCompound payload,List<Minecraft121ItemComponentContracts.Ref> references) {
        ItemCapture {
            Objects.requireNonNull(payload);
            references=List.copyOf(references);
        }
    }

    Minecraft121RegistryLeafContracts(Minecraft121SettingsGraph graph,DynamicRegistryManager registries,
            BiConsumer<Key,Minecraft121ItemComponentContracts.Ref> references) {
        this.graph=Objects.requireNonNull(graph);
        this.registries=Objects.requireNonNull(registries);
        this.references=Objects.requireNonNull(references);
        this.ops=registries.getOps(NbtOps.INSTANCE);
    }

    /**
     * 由closure經definitions去重後呼叫：同一typed id只擷取一次，每條occurrence的edge由closure保留。
     * entry是closure已由同一個registry解析出的named entry；回傳寫入圖的payload。
     */
    NbtCompound block(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1. registry identity：只接受全域static BLOCK registry本身。
        requireStaticRegistry(Registries.BLOCK);
        // 2. class／source：通過之前不呼叫value的任何virtual method。
        Object value=entry.value();
        require(value instanceof Block,"NATIVE_DEFINITION_OWNER");
        requireNativeSource(value.getClass());
        var block=(Block)value;
        // 3. identity：payload要寫入的node就是這個key的REGISTRY node；value的registry entry就是closure解析出的entry，且key相同。
        requireLeafNode(node,key);
        require(block.getRegistryEntry()==entry&&entry.registryKey().equals(key),"STATIC_LEAF_IDENTITY_MISMATCH");
        // 4～6由seam完成；production一律傳入getDefaultState()。
        var payload=blockPayload(block,block.getDefaultState());
        // 7.
        graph.payload(node,payload);
        return payload;
    }

    /**
     * BLOCK leaf的純函式seam：production一律傳入block.getDefaultState()；測試可傳同一個block的其他state，
     * 證明payload隨值改變而不必改動全域狀態。不屬於這個block的state一律拒絕。
     *
     * <p>開頭自行驗block class的source（冪等；block(...)原有的步驟2保留，維持設計次序），seam不依賴呼叫端已驗source。
     */
    static NbtCompound blockPayload(Block block,BlockState state) {
        // 步驟2的seam自驗：通過之前不呼叫block的任何virtual method。
        requireNativeSource(block.getClass());
        // 4. feature：只admit vanilla子集；同一個值寫入RequiredFeatures。
        var features=block.getRequiredFeatures();
        requireVanillaFeatures(features);
        // 5. property：逐個驗證，排在default state roundtrip之前（withProperty在parse失敗時只warn、不丟例外）。
        var manager=block.getStateManager();
        var domains=new NbtCompound();
        try {
            // 寬catch前提：try內只放state definition的來源讀取（getProperties／getName／getProperty）與propertyDomain
            // （它的RuntimeException已自行改碼），以及前置require已保證不丟例外的本地操作（HashSet.add、NbtCompound.put）。
            var names=new HashSet<String>();
            for(var property:manager.getProperties()) {
                require(property!=null,"UNSUPPORTED_STATIC_PROPERTY");
                // 先驗exact原生class，之後才呼叫property的virtual method。
                var domain=propertyDomain(property);
                String name=property.getName();
                require(name!=null&&names.add(name)&&manager.getProperty(name)==property,"UNSUPPORTED_STATIC_PROPERTY");
                domains.put(name,domain);
            }
        } catch(Failure rejected) {
            // 已編碼的拒絕原樣交回；Failure是RuntimeException子類，須先catch。
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_STATIC_PROPERTY",bad);
        }
        // 6. default state：state須屬於這個block；Name須等於這個block的registry id（fromBlockState經DefaultedRegistry取id，
        // 缺值時回air）；NbtHelper roundtrip須回到同一個state。
        require(state.getBlock()==block,"STATIC_LEAF_IDENTITY_MISMATCH");
        // seam不帶key，所以比對block自己的registry entry的id；production的步驟3已證這個entry的key就是node所對應的key。
        String id=block.getRegistryEntry().registryKey().getValue().toString();
        NbtCompound encoded;
        try {
            // 寬catch前提：try內只放原生NbtHelper的default state roundtrip（本碼所指的來源讀取），以及取Name與比較等本地操作。
            encoded=NbtHelper.fromBlockState(state);
            require(encoded.getString("Name").equals(id),"STATIC_LEAF_IDENTITY_MISMATCH");
            require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(),encoded)==state,"STATIC_BLOCK_DEFAULT_STATE_ROUNDTRIP");
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("STATIC_BLOCK_DEFAULT_STATE_ROUNDTRIP",bad);
        }
        var payload=new NbtCompound();
        payload.putString("Contract",BLOCK_CONTRACT);
        payload.put("DefaultState",encoded);
        payload.put("PropertyDomains",domains);
        payload.put("RequiredFeatures",featureIds(features));
        return payload;
    }

    /**
     * 單一property的值域：依getValues()的原生次序列出值名。只接受四種exact原生property class（Boolean／Int／Enum／Direction）；
     * Enum與Direction另驗值型別的source，因為name(v)會執行enum的asString。Int／Boolean的值型別是JDK類別（CodeSource為null），不驗。
     */
    static NbtList propertyDomain(Property<?> property) {
        var type=property.getClass();
        require((type==BooleanProperty.class||type==IntProperty.class||type==EnumProperty.class||type==DirectionProperty.class)
                &&nativeSource(type),"UNSUPPORTED_STATIC_PROPERTY");
        try {
            // 寬catch前提：try內只放已驗exact原生property的來源讀取（getType／getValues／name／parse），以及前置require已保證
            // 不丟例外的本地操作（HashSet.add、Optional.of、NbtString.of）。
            if(type==EnumProperty.class||type==DirectionProperty.class)
                require(nativeSource(property.getType()),"UNSUPPORTED_STATIC_PROPERTY");
            return values(property);
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_STATIC_PROPERTY",bad);
        }
    }
    private static <T extends Comparable<T>> NbtList values(Property<T> property) {
        var names=new HashSet<String>();
        var list=new NbtList();
        for(T value:property.getValues()) {
            require(value!=null,"UNSUPPORTED_STATIC_PROPERTY");
            String name=property.name(value);
            require(name!=null&&names.add(name)&&property.parse(name).equals(Optional.of(value)),"UNSUPPORTED_STATIC_PROPERTY");
            list.add(NbtString.of(name));
        }
        return list;
    }

    /**
     * ITEM leaf；與block(...)相同，由closure經definitions去重後呼叫，回傳寫入圖的payload。
     *
     * <p>步驟2～3寫死成單一迴圈：對executedOwnerClasses(item)的每一項呼叫requireNativeSource（item class，以及BlockItem的
     * block class、SpawnEggItem的EntityType class）；itemCapture開頭另有同樣的seam自驗。殘餘風險：「這兩處檢查同時被刪除」
     * （連同item class的檢查）沒有任何測試能抓到，因為測試不能建立或註冊foreign Item／BlockItem（{@code Item.<init>}會寫入
     * 全域registry）；這一點靠spec review逐行確認。
     */
    NbtCompound item(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1. registry identity：component codec可能查詢的static registry固定聯集（ITEM排第一）都須是lease持有的全域物件本身。
        requireItemComponentRegistries();
        // 2. class／source：通過之前不呼叫value的任何virtual method。3. BlockItem另驗block class、SpawnEggItem另驗EntityType class
        // （executedOwnerClasses只在item class已是原生時才呼叫getBlock()／getEntityType()）。兩步同一個迴圈。
        Object value=entry.value();
        require(value instanceof Item,"NATIVE_DEFINITION_OWNER");
        var item=(Item)value;
        for(var type:executedOwnerClasses(item))requireNativeSource(type);
        // 4. identity：payload要寫入的node就是這個key的REGISTRY node；value的registry entry就是closure解析出的entry，且key相同。
        requireLeafNode(node,key);
        require(item.getRegistryEntry()==entry&&entry.registryKey().equals(key),"STATIC_LEAF_IDENTITY_MISMATCH");
        // 5～7由seam完成；production一律傳入getComponents()。
        var capture=itemCapture(item,item.getComponents());
        // 8. 先寫payload，再把ref交給sink（只是入closure的FIFO），所以自指或循環的occurrence也能完成。
        graph.payload(node,capture.payload());
        for(var ref:capture.references())references.accept(node,ref);
        return capture.payload();
    }

    /**
     * leaf擷取時會執行其程式碼的owner class：第一項是item class（只用final的getClass()取得）。item class已是原生source時，
     * 再依原生委派補上第二項：BlockItem的RequiredFeatures委派getBlock().getRequiredFeatures()，所以加上block class；
     * SpawnEggItem的RequiredFeatures委派建構時type的getRequiredFeatures()（getEntityType(ItemStack.EMPTY)在ENTITY_DATA為空時
     * 就回傳該type），所以加上該EntityType的class（mod若以EntityType子類建立spawn egg，會在source檢查fail closed）。
     * getBlock()／getEntityType()是virtual，只在item class已是原生source時才呼叫；否則只回傳item class，讓呼叫端的迴圈在第一項
     * 就拒絕。依固定JAR的全量掃描（D1b-3設計附錄B b7），宣告getRequiredFeatures()的Item子類只有Item、BlockItem、SpawnEggItem。
     */
    static List<Class<?>> executedOwnerClasses(Item item) {
        Class<?> itemClass=item.getClass();
        if(!nativeSource(itemClass))return List.of(itemClass);
        if(item instanceof BlockItem blockItem) {
            var block=blockItem.getBlock();
            require(block!=null,"NATIVE_DEFINITION_OWNER");
            return List.of(itemClass,block.getClass());
        }
        if(item instanceof SpawnEggItem egg) {
            var type=egg.getEntityType(ItemStack.EMPTY);
            require(type!=null,"NATIVE_DEFINITION_OWNER");
            return List.of(itemClass,type.getClass());
        }
        return List.of(itemClass);
    }

    /**
     * ITEM leaf的seam：production一律傳入item.getComponents()；測試可傳同一個item的其他ComponentMap（builder複本），
     * 證明payload隨值改變而不必改動全域狀態。是instance method：codec用本物件的lease registries與建構時建好的ops。
     *
     * <p>開頭自行驗步驟1的registry identity聯集，以及對executedOwnerClasses(item)逐項驗source（冪等；item(...)原有的檢查保留，
     * 維持設計次序），因為codec與RequiredFeatures會執行它們；seam不依賴呼叫端。殘餘風險：source自驗沒有行為層級的測試（測試
     * 不能建立foreign Item），「item(...)與本seam兩處source檢查同時被刪除」靠spec review逐行確認；BLOCK seam的自驗則有Canon的
     * 具名測試。
     *
     * <p>default component白名單（已審型別表Minecraft121ItemComponentContracts）跑兩次：步驟6在codec之前先驗容器class的source、
     * 再逐項判斷輸入的map；步驟7的roundtrip之後，對parse重建的原生map再跑一次。容器或巢狀List自稱的內容可以與codec實際迭代的
     * 內容不同，重建的map才是payload實際寫入的內容。
     *
     * <p>codec用lease registries的ops（D1b-3設計§1.5；M6 spec §3.1.2：不得以全域fallback冒充實際context）。component型別鍵、
     * ItemStack、attribute與status effect等codec綁定全域registry物件、不看ops，所以步驟1先證明lease持有的就是那些全域物件，
     * 全域解析才等於lease解析，證明不成立就在codec之前fail closed；經ops查詢的引用（tool的block tag與direct list、dynamic的
     * jukebox song）直接由lease解析。回傳ItemCapture（設計§1.5第2點）：payload，以及以同一筆型別契約從重建的map抽出的ref。
     */
    ItemCapture itemCapture(Item item,ComponentMap components) {
        // 步驟1的seam自驗：codec之前證明固定static registry聯集。
        requireItemComponentRegistries();
        // 步驟2～3的seam自驗：通過之前不呼叫item（及其block或EntityType）的任何virtual method。
        for(var type:executedOwnerClasses(item))requireNativeSource(type);
        // 5. feature：只admit vanilla子集；同一個值寫入RequiredFeatures。
        var features=item.getRequiredFeatures();
        requireVanillaFeatures(features);
        // 6. default component白名單：容器class先驗source（只擋直接foreign的容器），再逐項依單一分派表判斷，排在codec之前。
        requireReviewedDefaults(components);
        // 7. 原生ComponentMap.CODEC以lease ops做encode→parse→encode；保留parse重建的原生map。
        NbtElement encoded,again;ComponentMap rebuilt;
        try {
            // 寬catch前提：try內只放本碼所指的原生ComponentMap.CODEC encode／parse／encode（getOrThrow把DataResult的錯誤轉成例外）；
            // ops物件在建構時建立，但它對各registry的解析是lazy的，發生在本try內：查不到時由codec回傳錯誤，改碼為本碼。比較、
            // 白名單與graph都在try外。
            encoded=ComponentMap.CODEC.encodeStart(ops,components).getOrThrow();
            rebuilt=ComponentMap.CODEC.parse(ops,encoded).getOrThrow();
            again=ComponentMap.CODEC.encodeStart(ops,rebuilt).getOrThrow();
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_COMPONENT_CODEC",bad);
        }
        require(encoded instanceof NbtCompound,"UNSUPPORTED_ITEM_COMPONENT_CODEC");
        require(encoded.equals(again),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
        // 白名單（值條件與ref抽取，同一筆契約）對重建的原生map再驗一次：已知encoded等於again，所以驗到的就是payload實際寫入的內容。
        var references=requireReviewedDefaults(rebuilt);
        // 8.
        var payload=new NbtCompound();
        payload.putString("Contract",ITEM_CONTRACT);
        payload.put("DefaultComponents",encoded);
        payload.put("RequiredFeatures",featureIds(features));
        return new ItemCapture(payload,references);
    }

    /**
     * 步驟6的白名單（codec前的輸入map與roundtrip重建的map共用）：容器class須為原生source，之後才呼叫它的iterator；再逐項以
     * Minecraft121ItemComponentContracts的單一分派表取該型的契約：不屬已審型別或值條件不符一律UNSUPPORTED_ITEM_DEFAULT_COMPONENT；
     * 值條件成立之後，才以同一筆契約的ref抽取取出該值的ref（path加上components/&lt;型別id&gt;/前綴）。回傳的ref依型別id排序、同型內
     * 依抽取次序，與容器的迭代次序無關。容器的source檢查只擋直接foreign的容器class；原生包裝（ComponentMapImpl、
     * ComponentMap.of／filtered）仍可能委派foreign的map或predicate，它們的內容一致性由roundtrip後對重建map的再驗保證。迭代、
     * 判斷與抽取包在try內：Failure原樣交回，其他RuntimeException改成UNSUPPORTED_ITEM_DEFAULT_COMPONENT並以initCause保留原因。
     */
    private static List<Minecraft121ItemComponentContracts.Ref> requireReviewedDefaults(ComponentMap components) {
        require(components!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        requireNativeSource(components.getClass());
        var byType=new TreeMap<String,List<Minecraft121ItemComponentContracts.Ref>>();
        try {
            // 寬catch前提：try內只放本碼所指的來源讀取（容器的iterator，component的type／value，值條件與ref抽取對巢狀值的讀取，
            // 以及型別id的查詢），以及前置require已保證不丟例外的本地操作（class比較、source判斷、TreeMap與ArrayList的寫入）。
            for(var component:components) {
                var type=component.type();Object value=component.value();
                var contract=Minecraft121ItemComponentContracts.contract(type);
                require(contract!=null&&contract.value().test(value),"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                contract.refs().extract(value,(path,entry)->{
                    var id=Registries.DATA_COMPONENT_TYPE.getId(type);
                    require(id!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                    byType.computeIfAbsent(id.toString(),k->new ArrayList<>())
                            .add(new Minecraft121ItemComponentContracts.Ref.Entry("components/"+id+"/"+path,entry));
                });
            }
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",bad);
        }
        var references=new ArrayList<Minecraft121ItemComponentContracts.Ref>();
        for(var refs:byType.values())references.addAll(refs);
        return references;
    }

    /** 只admit vanilla feature子集（含空集合）。world enabled features屬D3／D4的必要輸入；在那之前這是暫時的fail closed。 */
    static void requireVanillaFeatures(FeatureSet features) {
        require(features!=null&&features.isSubsetOf(FeatureFlags.VANILLA_FEATURES),"UNSUPPORTED_FEATURE_GATED_LEAF");
    }
    private static NbtList featureIds(FeatureSet features) {
        var ids=new ArrayList<String>();
        for(var id:FeatureFlags.FEATURE_MANAGER.toId(features))ids.add(id.toString());
        Collections.sort(ids);
        var list=new NbtList();
        for(String id:ids)list.add(NbtString.of(id));
        return list;
    }

    /**
     * 原生source判斷，是Closure、LeafContracts與ItemComponentContracts共用的唯一實作：class的CodeSource位置須與固定1.21 JAR
     * （SharedConstants所在）相同。
     * 只看class，不看namespace或id，也不證明instance的值；JDK類別的CodeSource為null，一律拒絕。TemplateValueContracts、
     * TemplateSettingsAdapter與NativeValueContracts另有既有複本，交D2收斂。
     */
    static void requireNativeSource(Class<?> type) {
        require(nativeSource(type),"NATIVE_DEFINITION_SOURCE");
    }
    /**
     * ITEM leaf的步驟1：Minecraft121ItemComponentContracts.staticRegistries()的每一個（依表的次序，ITEM第一）都須是lease持有的
     * 全域物件本身。聯集固定，不依admit的型別增減。
     */
    private void requireItemComponentRegistries() {
        for(var registry:Minecraft121ItemComponentContracts.staticRegistries())requireStaticRegistry(registry);
    }
    /**
     * 步驟1的registry identity：lease中global.getKey()的registry須就是全域static registry本身；缺席為REQUIRED_REGISTRY_MISSING
     * （與closure解析registry時的碼相同），同key的另一個物件為STATIC_REGISTRY_OWNER_MISMATCH。key一律由global.getKey()取得，
     * 不另外傳入，所以不會出現key與物件配錯（D1b-3a quality M3(c)）。
     */
    private void requireStaticRegistry(Registry<?> global) {
        RegistryKey<? extends Registry<?>> key=global.getKey();
        var actual=registries.getOptional(key).orElse(null);
        require(actual!=null,"REQUIRED_REGISTRY_MISSING");
        require(actual==global,"STATIC_REGISTRY_OWNER_MISMATCH");
    }
    /** 原生source的布林判斷（requireNativeSource與Minecraft121ItemComponentContracts的exact原生判斷共用）。 */
    static boolean nativeSource(Class<?> type) {
        var source=type.getProtectionDomain().getCodeSource();
        var game=SharedConstants.class.getProtectionDomain().getCodeSource();
        return source!=null&&game!=null&&source.getLocation().equals(game.getLocation());
    }
    /** payload要寫入的node須是這個key的REGISTRY node（kind、registry與id都相符），否則STATIC_LEAF_IDENTITY_MISMATCH。 */
    private static void requireLeafNode(Key node,RegistryKey<?> key) {
        require(node!=null&&key!=null&&node.kind()==Kind.REGISTRY&&node.registry().equals(key.getRegistry().toString())
                &&node.id().equals(key.getValue().toString()),"STATIC_LEAF_IDENTITY_MISMATCH");
    }
    private static Failure failure(String code,RuntimeException cause) {
        var failure=new Failure(code);
        failure.initCause(cause);
        return failure;
    }
    private static void require(boolean value,String code) {
        if(!value)throw new Failure(code);
    }
}
