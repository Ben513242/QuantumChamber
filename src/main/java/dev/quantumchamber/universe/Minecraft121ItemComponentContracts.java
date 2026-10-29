package dev.quantumchamber.universe;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.type.SuspiciousStewEffectsComponent;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Rarity;
import net.minecraft.util.Unit;

/**
 * C1d-D1b-3 ITEM default component型別契約的唯一owner：已審型別的單一分派表（{@link #contract}），以及ITEM leaf在codec之前逐一
 * 證明lease identity的static registry固定聯集（{@link #staticRegistries}）。每個已審型別在表內同一處宣告三件事：值條件、ref抽取
 * （literal與只收空值的型別明確宣告{@link #NO_REFS}），以及已接受的值經codec會查詢的static registry。呼叫端只經同一筆契約取用
 * 值條件與ref抽取，所以不會另有一張ref分派表與值條件表漂移，造成「型別被接受、卻沒抽出ref」的靜默少邊。D1c的loot function與
 * D2b的模板effective component應重用本表，不另立第三份白名單；TemplateValueContracts.components()目前另有自己的表，交D2b收斂。
 *
 * <p>值條件只呼叫固定JAR內exact class的record欄位或原生accessor，而且只在exact原生class成立之後才呼叫；ref抽取只在同一個值的
 * 值條件成立之後呼叫。容器或巢狀List自稱的內容可以與codec實際迭代的內容不同，所以呼叫端必須對roundtrip重建的原生map再套一次
 * 本表（值條件與ref抽取都是）。
 */
final class Minecraft121ItemComponentContracts {
    private Minecraft121ItemComponentContracts() {}

    /**
     * ITEM leaf從default component抽出的引用（D1b-3設計§1.5第2點）。path是相對ITEM node的完整路徑
     * （components/&lt;component型別id&gt;/…）。目前只有指向named registry entry的Entry；tool的tag rule（Tag）屬D1b-3c。
     */
    sealed interface Ref permits Ref.Entry {
        String path();
        /** 指向named registry entry的引用；entry是exact原生RegistryEntry.Reference（值條件已驗）。 */
        record Entry(String path,RegistryEntry.Reference<?> entry) implements Ref {
            public Entry {
                Objects.requireNonNull(path);
                Objects.requireNonNull(entry);
            }
        }
    }
    /** 一型的ref抽取：只在該型值條件對同一個值成立之後呼叫；sink收到相對於該component的path（例如effects/0/effect/id）與entry。 */
    @FunctionalInterface interface Refs {
        void extract(Object value,BiConsumer<String,RegistryEntry.Reference<?>> sink);
    }
    /** literal與只收空值型別的明確宣告：已接受的值不含任何引用。 */
    static final Refs NO_REFS=(value,sink)->{};
    /**
     * 一個已審型別的契約。value是值條件；refs是ref抽取（NO_REFS或抽取器，不得為null）；registries是已接受的值經codec會查詢的static
     * registry，只作宣告，由結構測試核對它屬於staticRegistries()。production的identity證明一律用固定聯集，不依本欄增減。
     */
    record Contract(Predicate<Object> value,Refs refs,List<RegistryKey<? extends Registry<?>>> registries) {
        Contract {
            Objects.requireNonNull(value);
            Objects.requireNonNull(refs);
            registries=List.copyOf(registries);
        }
    }

    /**
     * 已審型別（含D1b-3c規劃的tool tag rule）的codec會查詢的static registry固定聯集（D1b-3設計審查M1，controller C1），ITEM排第一。
     * ITEM（ItemStack與item引用）、DATA_COMPONENT_TYPE（component型別鍵）、ATTRIBUTE與STATUS_EFFECT經ops無關的codec解析
     * （Registry.getEntryCodec／getCodec綁定全域registry物件，不看ops），所以只能以「lease持有的就是全域物件」證明全域解析等於lease
     * 解析。BLOCK（tool rule的direct list與tag）經ops解析，但tool default持有的是全域BLOCK的Reference／Named，encode時以lease的
     * owner做ownerEquals，所以同樣要求lease的BLOCK就是全域物件。聯集固定，不隨已審型別增減，免得某型漏列一個registry時靜默走全域
     * 解析；各型自己宣告的registries只由結構測試核對它屬於本聯集。未審型別若經原生包裝容器到達codec，可能查詢聯集外的registry
     * （例如非空potion_contents的POTION），但必在重建map的再驗被拒，不進payload。D1b-3d起加SOUND_EVENT；dynamic的JUKEBOX_SONG
     * 只能經lease解析，不在本表。每次回傳新的List，元素是static final的全域物件；key一律由getKey()取得。
     */
    static List<Registry<?>> staticRegistries() {
        return List.of(Registries.ITEM,Registries.DATA_COMPONENT_TYPE,Registries.BLOCK,Registries.ATTRIBUTE,Registries.STATUS_EFFECT);
    }

    /** type的已審契約；不是已審型別時回傳null。 */
    static Contract contract(ComponentType<?> type) {
        return Table.CONTRACTS.get(type);
    }
    /** 整張已審型別表（不可修改的identity map），供結構測試逐型核對。 */
    static Map<ComponentType<?>,Contract> contracts() {
        return Table.CONTRACTS;
    }

    /**
     * 單一分派表。lazy holder：第一次查表時才初始化，此時Bootstrap已完成（production在server啟動後，測試在@BeforeAll之後），
     * 不在本類載入時觸發DataComponentTypes的class init。key以identity比對（ComponentType的原生實作沒有覆寫equals）。
     */
    private static final class Table {
        static final Map<ComponentType<?>,Contract> CONTRACTS=build();
        private static Map<ComponentType<?>,Contract> build() {
            var table=new IdentityHashMap<ComponentType<?>,Contract>();
            // D1b-2：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（Integer）、RARITY（exact Rarity）；ENCHANTMENTS、LORE、
            // ATTRIBUTE_MODIFIERS只收exact原生class且為空。
            put(table,DataComponentTypes.MAX_STACK_SIZE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.MAX_DAMAGE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.DAMAGE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.REPAIR_COST,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.RARITY,noRefs(value->nativeExact(value,Rarity.class)));
            put(table,DataComponentTypes.ENCHANTMENTS,noRefs(value->nativeExact(value,ItemEnchantmentsComponent.class)
                    &&((ItemEnchantmentsComponent)value).isEmpty()));
            put(table,DataComponentTypes.LORE,noRefs(value->nativeExact(value,LoreComponent.class)
                    &&((LoreComponent)value).lines().isEmpty()&&((LoreComponent)value).styledLines().isEmpty()));
            put(table,DataComponentTypes.ATTRIBUTE_MODIFIERS,noRefs(value->nativeExact(value,AttributeModifiersComponent.class)
                    &&((AttributeModifiersComponent)value).modifiers().isEmpty()));
            // D1b-3a的literal（D1b-3設計§2.2）：FIRE_RESISTANT的值須是固定JAR內單例enum net.minecraft.util.Unit.INSTANCE；
            // ENCHANTMENT_GLINT_OVERRIDE（Boolean）、OMINOUS_BOTTLE_AMPLIFIER（Integer；原生範圍0..4由codec強制）。Boolean與Integer
            // 是JDK的final類別（CodeSource為null），不能套exact原生source，只驗型別。
            put(table,DataComponentTypes.FIRE_RESISTANT,noRefs(value->value==Unit.INSTANCE));
            put(table,DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,noRefs(value->value instanceof Boolean));
            put(table,DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,noRefs(value->value instanceof Integer));
            // D1b-3a只收空DEFAULT：POTION_CONTENTS（三欄皆空）、SUSPICIOUS_STEW_EFFECTS、BANNER_PATTERNS、CHARGED_PROJECTILES；
            // CONTAINER就是DEFAULT本身：經ComponentMap.CODEC（fromSlots）或fromStacks產生的空container都是這個單例；其他原生路徑
            // （例如PACKET_CODEC）產生的非DEFAULT空container會被誤拒，方向是fail closed。非空值會引用POTION、STATUS_EFFECT、ITEM或
            // dynamic的BANNER_PATTERN，不在D1b-3a。
            put(table,DataComponentTypes.POTION_CONTENTS,noRefs(value->nativeExact(value,PotionContentsComponent.class)
                    &&((PotionContentsComponent)value).potion().isEmpty()&&((PotionContentsComponent)value).customColor().isEmpty()
                    &&((PotionContentsComponent)value).customEffects().isEmpty()));
            put(table,DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,noRefs(value->nativeExact(value,SuspiciousStewEffectsComponent.class)
                    &&((SuspiciousStewEffectsComponent)value).effects().isEmpty()));
            put(table,DataComponentTypes.CONTAINER,noRefs(value->value==ContainerComponent.DEFAULT));
            put(table,DataComponentTypes.BANNER_PATTERNS,noRefs(value->nativeExact(value,BannerPatternsComponent.class)
                    &&((BannerPatternsComponent)value).layers().isEmpty()));
            put(table,DataComponentTypes.CHARGED_PROJECTILES,noRefs(value->nativeExact(value,ChargedProjectilesComponent.class)
                    &&((ChargedProjectilesComponent)value).isEmpty()));
            // 會引用其他registry或tag的型別（tool、非空attribute_modifiers、food、jukebox_playable等）屬D1b-3b～3d，不在本表。
            return Collections.unmodifiableMap(table);
        }
        private static void put(Map<ComponentType<?>,Contract> table,ComponentType<?> type,Contract contract) {
            if(table.put(Objects.requireNonNull(type),contract)!=null)throw new IllegalStateException("duplicate item component contract");
        }
        /** literal或只收空值的型別：明確宣告NO_REFS，codec不查任何registry。 */
        private static Contract noRefs(Predicate<Object> value) {
            return new Contract(value,NO_REFS,List.of());
        }
    }
    /** exact class且source為原生，之後才可呼叫該值的方法。 */
    private static boolean nativeExact(Object value,Class<?> type) {
        return value!=null&&value.getClass()==type&&Minecraft121RegistryLeafContracts.nativeSource(type);
    }
}
