package dev.quantumchamber.universe;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.*;
import static dev.quantumchamber.universe.Minecraft121SettingsGraph.*;

/**
 * C1d-D1b static registry leaf的owner；本切片只admit BLOCK。payload（Contract=quantumchamber:m6-static-block-leaf-v1）
 * 是生成consumer實際解碼用到的state definition與default state，加上RequiredFeatures；leaf沒有出發邊。
 *
 * <p>exact原生class加上CodeSource相同，只證明這個value執行的程式碼來自固定1.21 JAR，不證明instance的Settings
 * （hardness、luminance、mapColor…）或constructor參數是vanilla值：以原生class註冊在非minecraft namespace的block會被admit，
 * 它的Settings與constructor參數不在FP可偵測的保證內。Block的Java行為、BlockTypes.CODEC、Block方法中hardcoded的tag讀取、
 * asItem與state id都不入值。
 *
 * <p>檢查順序固定：先registry identity，再class／source，通過之後才呼叫value的virtual method。內部require丟出的Failure
 * 原樣交回；其他RuntimeException才改成該步驟的具名碼並以initCause保留原因。寬catch的try內只放該碼所指的來源讀取，以及
 * 前置require已保證不丟例外的本地操作；checkpoint、graph與其他codec等可能丟非Failure例外的呼叫一律放在try外，否則會被改碼。
 */
final class Minecraft121RegistryLeafContracts {
    static final String BLOCK_CONTRACT="quantumchamber:m6-static-block-leaf-v1";
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
        // 3. intrusive identity：value的registry entry就是closure解析出的entry，且key相同。
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
        // 6. default state：state須屬於這個block；Name須等於Key的id（fromBlockState經DefaultedRegistry取id，缺值時回air）；
        // NbtHelper roundtrip須回到同一個state。
        require(state.getBlock()==block,"STATIC_LEAF_IDENTITY_MISMATCH");
        // production的步驟3已證block.getRegistryEntry()就是Graph Key所來自的entry，所以這個id即Key的id。
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
    private static Failure failure(String code,RuntimeException cause) {
        var failure=new Failure(code);
        failure.initCause(cause);
        return failure;
    }
    private static void require(boolean value,String code) {
        if(!value)throw new Failure(code);
    }
}
