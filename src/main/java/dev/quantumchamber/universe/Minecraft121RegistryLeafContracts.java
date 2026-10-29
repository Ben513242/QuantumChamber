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
 * （BlockItem的block class）本身就要呼叫getBlock()，所以只在item class已驗為原生之後才呼叫。內部require丟出的Failure
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
        require(registries.getOptional(RegistryKeys.BLOCK).orElse(null)==Registries.BLOCK,"STATIC_REGISTRY_OWNER_MISMATCH");
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
     */
    static NbtCompound blockPayload(Block block,BlockState state) {
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
     * block class）。殘餘風險：「刪除整個迴圈」（連同item class的檢查）沒有任何測試能抓到，因為測試不能建立或註冊foreign
     * Item／BlockItem（{@code Item.<init>}會寫入全域registry）；這一點靠spec review逐行確認。
     */
    NbtCompound item(Key node,RegistryKey<?> key,RegistryEntry.Reference<?> entry) {
        // 1. registry identity：只接受全域static ITEM registry本身。
        require(registries.getOptional(RegistryKeys.ITEM).orElse(null)==Registries.ITEM,"STATIC_REGISTRY_OWNER_MISMATCH");
        // 2. class／source：通過之前不呼叫value的任何virtual method。3. BlockItem另驗block class（executedOwnerClasses只在
        // item class已是原生時才呼叫getBlock()）。兩步同一個迴圈。
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
     * leaf擷取時會執行其程式碼的owner class：第一項是item class（只用final的getClass()取得）；若是BlockItem，RequiredFeatures
     * 委派getBlock().getRequiredFeatures()，所以再加上block class。getBlock()是virtual，只在item class已是原生source時才呼叫；
     * 否則只回傳item class，讓item(...)的迴圈在第一項就拒絕。
     */
    static List<Class<?>> executedOwnerClasses(Item item) {
        Class<?> itemClass=item.getClass();
        if(!(item instanceof BlockItem blockItem)||!nativeSource(itemClass))return List.of(itemClass);
        var block=blockItem.getBlock();
        require(block!=null,"NATIVE_DEFINITION_OWNER");
        return List.of(itemClass,block.getClass());
    }

    /**
     * ITEM leaf的純函式seam：production一律傳入item.getComponents()；測試可傳同一個item的其他ComponentMap（builder複本），
     * 證明payload隨值改變而不必改動全域狀態。呼叫前須已由item(...)驗過executedOwnerClasses的source，因為RequiredFeatures會執行它們。
     *
     * <p>codec用只含全域static registries的RegistryOps（{@code DynamicRegistryManager.of(Registries.REGISTRIES)}）：static leaf的值
     * 不依賴dynamic registry。白名單內的型別都不需要registry lookup；日後若放入需要dynamic registry的型別，會在codec失敗而fail closed。
     */
    static NbtCompound itemPayload(Item item,ComponentMap components) {
        // 5. feature：只admit vanilla子集；同一個值寫入RequiredFeatures。
        var features=item.getRequiredFeatures();
        requireVanillaFeatures(features);
        // 6. default component白名單：逐項依(type,value)判斷，排在codec之前。
        require(components!=null,"UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        for(var component:components)requireReviewedDefault(component);
        // 7. 原生ComponentMap.CODEC的encode→parse→encode。
        var ops=DynamicRegistryManager.of(Registries.REGISTRIES).getOps(NbtOps.INSTANCE);
        NbtElement encoded,again;
        try {
            // 寬catch前提：try內只放本碼所指的原生ComponentMap.CODEC encode／parse／encode（getOrThrow把DataResult的錯誤轉成例外）；
            // ops的建立、比較與graph都在try外。
            encoded=ComponentMap.CODEC.encodeStart(ops,components).getOrThrow();
            again=ComponentMap.CODEC.encodeStart(ops,ComponentMap.CODEC.parse(ops,encoded).getOrThrow()).getOrThrow();
        } catch(Failure rejected) {
            throw rejected;
        } catch(RuntimeException bad) {
            throw failure("UNSUPPORTED_ITEM_COMPONENT_CODEC",bad);
        }
        require(encoded instanceof NbtCompound,"UNSUPPORTED_ITEM_COMPONENT_CODEC");
        require(encoded.equals(again),"NATIVE_CODEC_ROUNDTRIP_CHANGED");
        // 8.
        var payload=new NbtCompound();
        payload.putString("Contract",ITEM_CONTRACT);
        payload.put("DefaultComponents",encoded);
        payload.put("RequiredFeatures",featureIds(features));
        return payload;
    }

    /**
     * 步驟6的白名單，與TemplateValueContracts.components對有效component的白名單相同，但扣掉container_loot（那是模板BE的consumer，
     * 不是item default）：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（Integer）、RARITY（exact Rarity），以及
     * ENCHANTMENTS／LORE／ATTRIBUTE_MODIFIERS（exact原生class且為空）。其餘型別或非空值一律UNSUPPORTED_ITEM_DEFAULT_COMPONENT；
     * 會引用其他registry的型別屬D1b-3，不在本白名單擴充。
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
     * 唯一的原生source判斷：class的CodeSource位置須與固定1.21 JAR（SharedConstants所在）相同。只看class，不看namespace或id，
     * 也不證明instance的值；JDK類別的CodeSource為null，一律拒絕。
     */
    static void requireNativeSource(Class<?> type) {
        require(nativeSource(type),"NATIVE_DEFINITION_SOURCE");
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
