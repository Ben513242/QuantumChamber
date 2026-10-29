package dev.quantumchamber.universe;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.Component;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
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
import net.minecraft.util.Rarity;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * C1d-D1b static registry leaf的owner；admit BLOCK與ITEM兩種，leaf都沒有出發邊。
 * <ul>
 * <li>BLOCK payload（Contract=quantumchamber:m6-static-block-leaf-v1）是生成consumer實際解碼用到的state definition與
 * default state，加上RequiredFeatures。</li>
 * <li>ITEM payload（Contract=quantumchamber:m6-static-item-leaf-v1）是白名單內的default ComponentMap（原生ComponentMap.CODEC
 * 編碼），加上RequiredFeatures。BlockItem的RequiredFeatures委派getBlock().getRequiredFeatures()，所以這個值由Block推導；
 * 生成consumer不讀BlockItem.getBlock，leaf也不建Item→Block邊。default component會引用其他registry的型別（tool、非空
 * attribute_modifiers、food、potion_contents、container等）一律fail closed，屬D1b-3。</li>
 * </ul>
 *
 * <p>exact原生class加上CodeSource相同，只證明這個value執行的程式碼來自固定1.21 JAR，不證明instance的Settings
 * （hardness、luminance、mapColor…）或constructor參數是vanilla值：以原生class註冊在非minecraft namespace的block或item會被admit，
 * 它的Settings與constructor參數不在FP可偵測的保證內。Block／Item的Java行為、BlockTypes.CODEC、Block方法中hardcoded的tag讀取、
 * asItem、recipeRemainder、翻譯鍵與state id都不入值。
 *
 * <p>檢查順序固定：先registry identity，再class／source（步驟2），步驟2通過之後才呼叫value的virtual method；ITEM的步驟3
 * （BlockItem的block class、SpawnEggItem的EntityType class）本身就要呼叫getBlock()／getEntityType()，所以只在item class
 * 已驗為原生之後才呼叫。兩個seam（blockPayload／itemPayload）開頭另自行驗同一組source，不依賴呼叫端。內部require丟出的Failure
 * 原樣交回；其他RuntimeException才改成該步驟的具名碼並以initCause保留原因。寬catch的try內只放該碼所指的來源讀取，以及
 * 前置require已保證不丟例外的本地操作；checkpoint、graph與其他codec等可能丟非Failure例外的呼叫一律放在try外，否則會被改碼。
 */
final class Minecraft121RegistryLeafContracts {
    private static final String BLOCK_CONTRACT="quantumchamber:m6-static-block-leaf-v1";
    private static final String ITEM_CONTRACT="quantumchamber:m6-static-item-leaf-v1";
    private final Minecraft121SettingsGraph graph;
    private final DynamicRegistryManager registries;

    Minecraft121RegistryLeafContracts(Minecraft121SettingsGraph graph,DynamicRegistryManager registries) {
        this.graph=Objects.requireNonNull(graph);
        this.registries=Objects.requireNonNull(registries);
    }

    /**
     * 由closure經definitions去重後呼叫：同一typed id只擷取一次，每條occurrence的edge由closure保留。
     * entry是closure已由同一個registry解析出的named entry；回傳寫入圖的payload。
     */
    NbtCompound block(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1. registry identity：只接受全域static BLOCK registry本身。
        requireStaticRegistry(RegistryKeys.BLOCK,Registries.BLOCK);
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
     * block class、SpawnEggItem的EntityType class）；itemPayload開頭另有同樣的seam自驗。殘餘風險：「這兩處檢查同時被刪除」
     * （連同item class的檢查）沒有任何測試能抓到，因為測試不能建立或註冊foreign Item／BlockItem（{@code Item.<init>}會寫入
     * 全域registry）；這一點靠spec review逐行確認。
     */
    NbtCompound item(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1. registry identity：只接受全域static ITEM registry本身。
        requireStaticRegistry(RegistryKeys.ITEM,Registries.ITEM);
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
        var payload=itemPayload(item,item.getComponents());
        // 8.
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
     * ITEM leaf的純函式seam：production一律傳入item.getComponents()；測試可傳同一個item的其他ComponentMap（builder複本），
     * 證明payload隨值改變而不必改動全域狀態。
     *
     * <p>開頭自行對executedOwnerClasses(item)逐項驗source（冪等；item(...)原有的迴圈保留，維持設計次序），因為RequiredFeatures
     * 會執行它們；seam不依賴呼叫端已驗source。殘餘風險：本seam的自驗沒有行為層級的測試（測試不能建立foreign Item），
     * 「item(...)與本seam兩處檢查同時被刪除」靠spec review逐行確認；BLOCK seam的自驗則有Canon的具名測試。
     *
     * <p>default component白名單跑兩次：步驟6在codec之前先驗容器class的source、再逐項判斷輸入的map；步驟7的roundtrip之後，
     * 對parse重建的原生map再跑一次。容器或巢狀List自稱的內容可以與codec實際迭代的內容不同，重建的map才是payload實際寫入的內容。
     *
     * <p>codec用只含全域static registries的RegistryOps（{@code DynamicRegistryManager.of(Registries.REGISTRIES)}），不是lease的
     * registries。白名單內的型別都不需要registry lookup；需要dynamic registry的型別會在codec失敗而fail closed；但引用static
     * registry的型別（例如tool的BLOCK、非空attribute_modifiers的ATTRIBUTE）會經全域static registry成功解析，不會失敗。依spec
     * 第123行，全域registry不得冒充實際context，所以這類型別在D1b-3a換源（lease ops加static registry identity）之前不得放入白名單；
     * 換源後依該設計改寫本段。
     */
    static NbtCompound itemPayload(Item item,ComponentMap components) {
        // 步驟2～3的seam自驗：通過之前不呼叫item（及其block或EntityType）的任何virtual method。
        for(var type:executedOwnerClasses(item))requireNativeSource(type);
        // 5. feature：只admit vanilla子集；同一個值寫入RequiredFeatures。
        var features=item.getRequiredFeatures();
        requireVanillaFeatures(features);
        // 6. default component白名單：容器class先驗source，再逐項依(type,value)判斷，排在codec之前。
        requireReviewedDefaults(components);
        // 7. 原生ComponentMap.CODEC的encode→parse→encode；保留parse重建的原生map。
        var ops=DynamicRegistryManager.of(Registries.REGISTRIES).getOps(NbtOps.INSTANCE);
        NbtElement encoded,again;ComponentMap rebuilt;
        try {
            // 寬catch前提：try內只放本碼所指的原生ComponentMap.CODEC encode／parse／encode（getOrThrow把DataResult的錯誤轉成例外）；
            // ops的建立、比較、白名單與graph都在try外。
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
        // 白名單對重建的原生map再驗一次：已知encoded等於again，所以驗到的就是payload實際寫入的內容。
        requireReviewedDefaults(rebuilt);
        // 8.
        var payload=new NbtCompound();
        payload.putString("Contract",ITEM_CONTRACT);
        payload.put("DefaultComponents",encoded);
        payload.put("RequiredFeatures",featureIds(features));
        return payload;
    }

    /**
     * 步驟6的白名單（codec前的輸入map與roundtrip重建的map共用）：容器class須為原生source，之後才呼叫它的iterator；再逐項判斷。
     * 迭代與判斷包在try內：Failure原樣交回，其他RuntimeException改成UNSUPPORTED_ITEM_DEFAULT_COMPONENT並以initCause保留原因。
     */
    private static void requireReviewedDefaults(ComponentMap components) {
        require(components!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        requireNativeSource(components.getClass());
        try {
            // 寬catch前提：try內只放本碼所指的來源讀取（容器的iterator，以及component的type／value與巢狀值的isEmpty等讀取），
            // 以及前置require已保證不丟例外的本地操作（class比較、source判斷）。
            for(var component:components)requireReviewedDefault(component);
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT",bad);
        }
    }
    /**
     * 單一component的判斷，與TemplateValueContracts.components對有效component的白名單相同，但扣掉container_loot（那是模板BE的
     * consumer，不是item default）：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（Integer）、RARITY（exact Rarity），以及
     * ENCHANTMENTS／LORE／ATTRIBUTE_MODIFIERS（exact原生class且為空）。其餘型別或非空值一律UNSUPPORTED_ITEM_DEFAULT_COMPONENT；
     * 會引用其他registry的型別屬D1b-3，不在本白名單擴充。巢狀List的isEmpty由該List自己回報，所以重建的map要再驗一次。
     */
    private static void requireReviewedDefault(Component<?> component) {
        var type=component.type();Object value=component.value();boolean reviewed;
        if(type==DataComponentTypes.MAX_STACK_SIZE||type==DataComponentTypes.MAX_DAMAGE
                ||type==DataComponentTypes.DAMAGE||type==DataComponentTypes.REPAIR_COST)reviewed=value instanceof Integer;
        else if(type==DataComponentTypes.RARITY)reviewed=nativeExact(value,Rarity.class);
        else if(type==DataComponentTypes.ENCHANTMENTS)reviewed=nativeExact(value,ItemEnchantmentsComponent.class)
                &&((ItemEnchantmentsComponent)value).isEmpty();
        else if(type==DataComponentTypes.LORE)reviewed=nativeExact(value,LoreComponent.class)
                &&((LoreComponent)value).lines().isEmpty()&&((LoreComponent)value).styledLines().isEmpty();
        else if(type==DataComponentTypes.ATTRIBUTE_MODIFIERS)reviewed=nativeExact(value,AttributeModifiersComponent.class)
                &&((AttributeModifiersComponent)value).modifiers().isEmpty();
        else reviewed=false;
        require(reviewed,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
    }
    /** exact class且source為原生，之後才可呼叫該值的方法。 */
    private static boolean nativeExact(Object value,Class<?> type) {
        return value!=null&&value.getClass()==type&&nativeSource(type);
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
     * 原生source判斷，是Closure與LeafContracts共用的唯一實作：class的CodeSource位置須與固定1.21 JAR（SharedConstants所在）相同。
     * 只看class，不看namespace或id，也不證明instance的值；JDK類別的CodeSource為null，一律拒絕。TemplateValueContracts、
     * TemplateSettingsAdapter與NativeValueContracts另有既有複本，交D2收斂。
     */
    static void requireNativeSource(Class<?> type) {
        require(nativeSource(type),"NATIVE_DEFINITION_SOURCE");
    }
    /** 步驟1的registry identity：lease中該key的registry須就是全域static registry本身；缺席或另一個物件都是STATIC_REGISTRY_OWNER_MISMATCH。 */
    private void requireStaticRegistry(RegistryKey<? extends Registry<?>> key,Registry<?> global) {
        require(registries.getOptional(key).orElse(null)==global,"STATIC_REGISTRY_OWNER_MISMATCH");
    }
    private static boolean nativeSource(Class<?> type) {
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
