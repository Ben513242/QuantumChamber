package dev.quantumchamber.universe;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.ComponentType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.*;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * C1d-D1b static registry leaf的owner；admit BLOCK、ITEM、ATTRIBUTE與STATUS_EFFECT四種。只有ITEM leaf有出發邊（D1b-3b起）。
 * <ul>
 * <li>BLOCK payload（Contract=quantumchamber:m6-static-block-leaf-v1）是生成consumer實際解碼用到的state definition與
 * default state，加上RequiredFeatures。</li>
 * <li>ITEM payload（Contract=quantumchamber:m6-static-item-leaf-v1）是已審型別表（Minecraft121ItemComponentContracts）內的
 * default ComponentMap，以同一張表在codec之前重建的copy（D1b-3c-0；原生ComponentMap.CODEC以lease registries建立、identity私有的ops
 * 編碼，D1b-3c-0b），加上
 * RequiredFeatures。vanilla item的copy與原值編碼相同（D1b-3c-0以ITEM成本診斷的逐件leafCanonicalSha256核對；診斷不gating）。
 * RequiredFeatures有兩種原生委派：BlockItem委派getBlock().getRequiredFeatures()，SpawnEggItem委派建構時EntityType的
 * getRequiredFeatures()，所以這個值由Block或EntityType推導；生成consumer不讀BlockItem.getBlock，leaf也不建Item→Block或
 * Item→EntityType邊。D1b-3a起，literal與空DEFAULT的8型也已審（fire_resistant、enchantment_glint_override、
 * ominous_bottle_amplifier；potion_contents、suspicious_stew_effects、container、banner_patterns、charged_projectiles只收空值）。
 * D1b-3b起，非空attribute_modifiers、food與只有direct rule的tool也已審，它們的static引用（ATTRIBUTE、STATUS_EFFECT、ITEM、BLOCK）
 * 以同一筆型別契約從default component抽出，經建構時傳入的sink成為該ITEM node的出邊。tool的tag rule（D1b-3c）與
 * jukebox_playable（D1b-3d）等仍fail closed。</li>
 * <li>ATTRIBUTE payload（Contract=quantumchamber:m6-static-attribute-leaf-v1）與STATUS_EFFECT payload
 * （Contract=quantumchamber:m6-static-status-effect-leaf-v1，加上RequiredFeatures）：兩者沒有value codec，Java值不入FP。</li>
 * </ul>
 *
 * <p>exact原生class加上CodeSource相同，只證明這個value執行的程式碼來自固定1.21 JAR，不證明instance的Settings
 * （hardness、luminance、mapColor…）或constructor參數是vanilla值：以原生class註冊在非minecraft namespace的block或item會被admit，
 * 它的Settings與constructor參數不在FP可偵測的保證內。Block／Item的Java行為、BlockTypes.CODEC、Block方法中hardcoded的tag讀取、
 * asItem、recipeRemainder、翻譯鍵與state id都不入值。
 *
 * <p>檢查順序固定：先registry identity（BLOCK leaf只驗BLOCK；ITEM leaf驗Minecraft121ItemComponentContracts的固定static registry
 * 聯集；ATTRIBUTE與STATUS_EFFECT leaf只驗各自的registry；lease缺席為REQUIRED_REGISTRY_MISSING，同key另一個物件為
 * STATIC_REGISTRY_OWNER_MISMATCH），再class／source（步驟2），步驟2通過之後才呼叫value的virtual method；ITEM的步驟3（BlockItem
 * 的block class、SpawnEggItem的EntityType class）本身就要呼叫getBlock()／getEntityType()，所以只在item class已驗為原生之後才
 * 呼叫。兩個seam（blockPayload／itemCapture）開頭另自行驗同一組source（itemCapture也自驗registry identity聯集），不依賴呼叫端。
 * 內部require丟出的Failure原樣交回；其他RuntimeException才改成該步驟的具名碼並以initCause保留原因。寬catch的try內只放該碼所指的
 * 來源讀取，以及前置require已保證不丟例外的本地操作；checkpoint、graph與其他codec等可能丟非Failure例外的呼叫一律放在try外，否則
 * 會被改碼。
 */
final class Minecraft121RegistryLeafContracts {
    private static final String BLOCK_CONTRACT="quantumchamber:m6-static-block-leaf-v1";
    private static final String ITEM_CONTRACT="quantumchamber:m6-static-item-leaf-v1";
    private static final String ATTRIBUTE_CONTRACT="quantumchamber:m6-static-attribute-leaf-v1";
    private static final String STATUS_EFFECT_CONTRACT="quantumchamber:m6-static-status-effect-leaf-v1";
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;
    /**
     * ITEM component codec的ops：建構時（closure建構時，lease之外）由lease registries建一次，跨本物件的各ITEM leaf共用。RegistryOps對
     * 各registry的解析是lazy的：第一次查詢某registry時才取wrapper並快取，所以實際解析發生在lease內、itemCapture步驟7的try內（D1b-3a
     * quality M1(b)）。
     *
     * <p>identity私有（D1b-3c-0b，quality Q-M1(B)；javap，D1B3C0B-JAVAP-FACTS）：DataComponentTypes.CACHE的key是(codec identity, value,
     * ops)；RegistryOps.equals比delegate與registryInfoGetter，WrapperLookup.getOps建出的CachedRegistryInfoGetter的equals再委派
     * registriesLookup.equals，所以凡以同一個manager getOps建出的ops都共用同一組key，遊戲或其他encode存入的foreign值會被本leaf命中。
     * 本欄因此以RegistryOps.of(NbtOps, PrivateLookup)建立：同一條CachedRegistryInfoGetter路徑（compute＝
     * getOptionalWrapper(key).map(RegistryInfo::fromWrapper)），PrivateLookup只把getOptionalWrapper委派給lease registries，所以registry
     * 解析、owner（ownerEquals只經getOwner）與lazy的時點都不變；只有ops的identity不同，不與任何其他ops相等。能命中的只剩本物件自己
     * 存入的entry（值的範圍見Minecraft121ItemComponentContracts.Copy）。CodecCache$Key.equals先比value再比ops，所以只有完整hash相同時
     * 才會對他人存入的值呼叫equals（之後ops不相等，仍不命中）；本ops的hash含PrivateLookup的identity hash，要刻意湊出同一個hash，得先
     * 以反射讀出本物件的private欄位。
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
        this.ops=RegistryOps.of(NbtOps.INSTANCE,new PrivateLookup(registries));
    }
    /**
     * 只供ops使用的WrapperLookup（D1b-3c-0b）：兩個抽象方法原樣委派lease registries；不覆寫equals／hashCode，所以以它建出的
     * CachedRegistryInfoGetter與RegistryOps只等於自己（identity）。
     */
    private static final class PrivateLookup implements RegistryWrapper.WrapperLookup {
        private final DynamicRegistryManager registries;
        PrivateLookup(DynamicRegistryManager registries) {this.registries=registries;}
        @Override public java.util.stream.Stream<RegistryKey<? extends Registry<?>>> streamAllRegistryKeys() {
            return registries.streamAllRegistryKeys();
        }
        @Override public <T> Optional<RegistryWrapper.Impl<T>> getOptionalWrapper(RegistryKey<? extends Registry<? extends T>> registryRef) {
            return registries.getOptionalWrapper(registryRef);
        }
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
     * block class、SpawnEggItem的EntityType class）；itemCapture開頭另有同樣的seam自驗，D1b-3c-0起有行為見證（LeafTest以Unsafe配置
     * 的foreign Item直接呼叫seam），所以刪除seam自驗（不論是否連同本method的檢查）都會被抓到。殘餘風險：只刪除本method的步驟2～3
     * 是等價突變（seam自驗以同一個碼擋下），但步驟4的getRegistryEntry()（virtual）會在source檢查之前對已註冊的foreign item執行；
     * 測試不能註冊foreign Item／BlockItem（{@code Item.<init>}會寫入全域registry，而closure只解析registry裡的entry），這一點靠spec
     * review逐行確認。
     *
     * <p>殘餘風險（D1b-3a quality M2）：步驟1與itemCapture的seam自驗丟出同一個碼，所以單獨刪除本method的步驟1時，DATA_COMPONENT_TYPE、
     * BLOCK、ATTRIBUTE、STATUS_EFFECT四個key是等價突變；只有ITEM key抓得到（lease的局部ITEM解析出的entry會先在步驟4得到
     * STATIC_LEAF_IDENTITY_MISMATCH，Canon的itemLeafRejectsForeignRegistryInstance預期STATIC_REGISTRY_OWNER_MISMATCH）。步驟1排在
     * entry.value()之前這件事在行為上無法觀察，靠逐行審查確認。
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
     * ATTRIBUTE leaf（D1b-3b；payload只有Contract=quantumchamber:m6-static-attribute-leaf-v1）：EntityAttribute沒有value codec，
     * Java值（fallback、clamp範圍、tracked、category）不入FP（spec §3.1.1）。由closure經definitions去重後呼叫，回傳寫入圖的payload。
     * 步驟（D1b-3設計審查M3）：1. registry identity（lease的ATTRIBUTE須是全域物件本身）→ 2. class／source（instanceof EntityAttribute
     * 加上value class的原生source；vanilla有ClampedEntityAttribute等子類，所以不要求exact class）→ 3. node與key對應，且全域registry
     * 中這個value的entry就是closure解析出的entry、key相同 → 4. payload。EntityAttribute沒有feature。
     */
    NbtCompound attribute(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1.
        requireStaticRegistry(Registries.ATTRIBUTE);
        // 2. 通過之前不呼叫value的任何virtual method。
        Object value=entry.value();
        require(value instanceof EntityAttribute,"NATIVE_DEFINITION_OWNER");
        requireNativeSource(value.getClass());
        // 3.
        requireLeafNode(node,key);
        require(Registries.ATTRIBUTE.getEntry((EntityAttribute)value)==entry&&entry.registryKey().equals(key),"STATIC_LEAF_IDENTITY_MISMATCH");
        // 4.
        var payload=new NbtCompound();
        payload.putString("Contract",ATTRIBUTE_CONTRACT);
        graph.payload(node,payload);
        return payload;
    }
    /**
     * STATUS_EFFECT leaf（D1b-3b；payload是Contract=quantumchamber:m6-static-status-effect-leaf-v1加上RequiredFeatures）：StatusEffect
     * 沒有value codec，Java值（category、color、attribute modifier、粒子）不入FP（spec §3.1.1）。步驟（D1b-3設計審查M3）：
     * 1. registry identity（全域STATUS_EFFECT）→ 2. class／source（instanceof StatusEffect加上value class的原生source；vanilla有
     * RegenerationStatusEffect等十餘個子類）→ 3. node與key對應，且全域registry中這個value的entry就是closure解析出的entry → 4. feature
     * （getRequiredFeatures不是final，所以在source之後才呼叫；只admit vanilla子集）→ 5. payload。
     */
    NbtCompound statusEffect(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1.
        requireStaticRegistry(Registries.STATUS_EFFECT);
        // 2. 通過之前不呼叫value的任何virtual method。
        Object value=entry.value();
        require(value instanceof StatusEffect,"NATIVE_DEFINITION_OWNER");
        requireNativeSource(value.getClass());
        var effect=(StatusEffect)value;
        // 3.
        requireLeafNode(node,key);
        require(Registries.STATUS_EFFECT.getEntry(effect)==entry&&entry.registryKey().equals(key),"STATIC_LEAF_IDENTITY_MISMATCH");
        // 4.
        var features=effect.getRequiredFeatures();
        requireVanillaFeatures(features);
        // 5.
        var payload=new NbtCompound();
        payload.putString("Contract",STATUS_EFFECT_CONTRACT);
        payload.put("RequiredFeatures",featureIds(features));
        graph.payload(node,payload);
        return payload;
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
     * 維持設計次序），因為codec與RequiredFeatures會執行它們；seam不依賴呼叫端。source自驗的行為見證（D1b-3c-0，S0-5）：LeafTest
     * 以Unsafe配置未註冊的foreign Item直接呼叫本seam，得NATIVE_DEFINITION_SOURCE，且不呼叫它的任何virtual method；BLOCK seam的
     * 自驗則有Canon的具名測試。
     *
     * <p>default component白名單（已審型別表Minecraft121ItemComponentContracts）跑三次：步驟6在codec之前先驗容器class的source、
     * 再逐項判斷輸入的map並抽出原值的ref；步驟6b對容器做第二次迭代，以同一張表重建codec要編碼的copy（D1b-3c-0，取代原本codec自己
     * 對容器的那次迭代）；步驟7的roundtrip之後，對parse重建的原生map再跑一次。容器或巢狀List自稱的內容可以與另一次迭代的內容不同，
     * 重建的map才是payload實際寫入的內容。
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
        // 6. default component白名單（第一次迭代）：容器class先驗source（只擋直接foreign的容器），再逐項依單一分派表判斷值條件、
        // 重建copy並對copy再驗，從copy抽出原值的ref；排在codec之前。food的using_converts_to目標item在這裡驗source（M5）。
        var originalRefs=requireReviewedDefaults(components).references();
        // 6b. codec前的copy重建（第二次迭代，D1b-3b spec M1＋quality Q-M1）：codec只編碼這份以已驗元素重建、或正規化為DEFAULT的原生map，
        // 所以foreign的本值無法讓encode命中另一個值的快取編碼（探測端）；已存的值是foreign的方向由ops的私有identity隔離（D1b-3c-0b，
        // 見ops欄位）。這次迭代取代原本codec自己對容器的迭代：吐出未審型別或不符值條件的值時在這裡就拒；帶ref的內容與第一次不同時由
        // 7c的ref比對擋下。
        var copy=requireReviewedDefaults(components).copy();
        // 7. 原生ComponentMap.CODEC以lease ops對copy做encode→parse→encode；保留parse重建的原生map。
        NbtElement encoded,again;ComponentMap rebuilt;
        try {
            // 寬catch前提：try內只放本碼所指的原生ComponentMap.CODEC encode／parse／encode（getOrThrow把DataResult的錯誤轉成例外）；
            // ops物件在建構時建立，但它對各registry的解析是lazy的，發生在本try內：查不到時由codec回傳錯誤，改碼為本碼。比較、
            // 白名單與graph都在try外。
            encoded=ComponentMap.CODEC.encodeStart(ops,copy).getOrThrow();
            rebuilt=ComponentMap.CODEC.parse(ops,encoded).getOrThrow();
            again=ComponentMap.CODEC.encodeStart(ops,rebuilt).getOrThrow();
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_COMPONENT_CODEC",bad);
        }
        require(encoded instanceof NbtCompound,"UNSUPPORTED_ITEM_COMPONENT_CODEC");
        require(encoded.equals(again),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
        // 7b. 白名單（值條件、copy與ref抽取，同一筆契約）對重建的原生map再驗一次：已知encoded等於again，所以驗到的就是payload實際
        // 寫入的內容。
        var references=requireReviewedDefaults(rebuilt).references();
        // 7c. 原值與重建值的ref逐一比對（D1b-3設計審查M2）：只從重建的map抽ref，會把foreign的Reference（同key、不同物件）靜默換成
        // registry的物件；所以要求兩邊的path與key逐一相同、entry是同一物件。
        requireSameReferences(originalRefs,references);
        // 8.
        var payload=new NbtCompound();
        payload.putString("Contract",ITEM_CONTRACT);
        payload.put("DefaultComponents",encoded);
        payload.put("RequiredFeatures",featureIds(features));
        return new ItemCapture(payload,references);
    }

    /** 步驟6、6b與7b的白名單結果：以同一張表重建的原生map（copy），以及從copy抽出的ref（依型別id排序，同型內依抽取次序）。 */
    private record Reviewed(ComponentMap copy,List<Minecraft121ItemComponentContracts.Ref> references) {}

    /**
     * 步驟6、6b與7b共用的白名單（codec前對輸入map的兩次迭代，以及roundtrip重建的map）：容器class須為原生source，之後才呼叫它的
     * iterator；再逐項以Minecraft121ItemComponentContracts的單一分派表取該型的契約：不屬已審型別、原值或copy不符值條件，一律
     * UNSUPPORTED_ITEM_DEFAULT_COMPONENT。值條件對原值成立之後，才以同一筆契約重建copy（D1b-3c-0）並對copy再套一次值條件，再從copy
     * 抽出ref（path加上components/&lt;型別id&gt;/前綴；ref sink的tag在D1b-3c之前一律fail closed）。值條件可丟出具名Failure（food的
     * using_converts_to目標item不是原生source時為NATIVE_DEFINITION_SOURCE）。回傳的ref依型別id排序、同型內依抽取次序，與容器的
     * 迭代次序無關。容器的source檢查只擋直接foreign的容器class；原生包裝（ComponentMapImpl、ComponentMap.of／filtered）仍可能委派
     * foreign的map或predicate，它們的內容一致性由步驟6b的第二次迭代、roundtrip後對重建map的再驗與ref比對保證。迭代、判斷、重建與
     * 抽取包在try內：Failure原樣交回，其他RuntimeException改成UNSUPPORTED_ITEM_DEFAULT_COMPONENT並以initCause保留原因。
     */
    @SuppressWarnings({"rawtypes","unchecked"})
    private static Reviewed requireReviewedDefaults(ComponentMap components) {
        require(components!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        requireNativeSource(components.getClass());
        var byType=new TreeMap<String,List<Minecraft121ItemComponentContracts.Ref>>();
        var copies=ComponentMap.builder();
        try {
            // 寬catch前提：try內只放本碼所指的來源讀取（容器的iterator，component的type／value，值條件、copy重建與ref抽取對巢狀值的
            // 讀取，以及型別id的查詢），以及前置require已保證不丟例外的本地操作（class比較、source判斷、TreeMap、ArrayList與原生
            // builder的寫入；builder只以type為key放入值，不呼叫值的任何method）。
            for(var component:components) {
                var type=component.type();Object value=component.value();
                var contract=Minecraft121ItemComponentContracts.contract(type);
                require(contract!=null&&contract.value().test(value),"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                Object copy=contract.copy().rebuild(value);
                require(contract.value().test(copy),"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                contract.refs().extract(copy,new Minecraft121ItemComponentContracts.RefSink() {
                    @Override public void entry(String path,RegistryEntry.Reference<?> entry) {
                        var id=Registries.DATA_COMPONENT_TYPE.getId(type);
                        require(id!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                        byType.computeIfAbsent(id.toString(),k->new ArrayList<>())
                                .add(new Minecraft121ItemComponentContracts.Ref.Entry("components/"+id+"/"+path,entry));
                    }
                    @Override public void tag(String path,RegistryEntryList.Named<?> tag) {
                        // D1b-3c之前沒有抽取器呼叫tag，Ref也還沒有Tag：一律fail closed，不留可被誤用的空殼路徑。
                        throw new Failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
                    }
                });
                copies.add((ComponentType)type,copy);
            }
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",bad);
        }
        var references=new ArrayList<Minecraft121ItemComponentContracts.Ref>();
        for(var refs:byType.values())references.addAll(refs);
        return new Reviewed(copies.build(),references);
    }
    /**
     * 步驟7c：原值與重建值抽出的ref逐一比對（D1b-3b quality Q-M2）。件數、path或指向的key不同（容器或巢狀List在兩次迭代間改變了
     * 帶ref的內容）為NATIVE_CODEC_ROUNDTRIP_CHANGED；path與key都相同、entry卻不是同一物件（foreign的同key Reference被codec換成
     * registry的物件）才是REGISTRY_ENTRY_OWNER_MISMATCH。switch對sealed Ref窮舉，D1b-3c加Tag時沒有比對規則就無法編譯。key的讀取包在
     * try內：原值的Reference可能沒有綁定key（registryKey()丟ISE），那也是key不同，改成NATIVE_CODEC_ROUNDTRIP_CHANGED並以initCause
     * 保留原因。
     */
    private static void requireSameReferences(List<Minecraft121ItemComponentContracts.Ref> original,
            List<Minecraft121ItemComponentContracts.Ref> rebuilt) {
        try {
            // 寬catch前提：try內只放本碼所指的ref讀取（path，以及exact原生Reference的registryKey()），以及前置require已保證不丟例外的
            // 本地操作（本地List的size／get、String與RegistryKey的equals、identity比較）。
            require(original.size()==rebuilt.size(),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
            for(int i=0;i<original.size();i++) {
                var before=original.get(i);var after=rebuilt.get(i);
                require(before.path().equals(after.path()),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
                switch(before) {
                    case Minecraft121ItemComponentContracts.Ref.Entry entry -> {
                        if(!(after instanceof Minecraft121ItemComponentContracts.Ref.Entry other))throw new Failure("NATIVE_CODEC_ROUNDTRIP_CHANGED");
                        require(entry.entry().registryKey().equals(other.entry().registryKey()),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
                        require(entry.entry()==other.entry(),"REGISTRY_ENTRY_OWNER_MISMATCH");
                    }
                }
            }
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("NATIVE_CODEC_ROUNDTRIP_CHANGED",bad);
        }
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
