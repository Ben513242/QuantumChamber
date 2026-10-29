package dev.quantumchamber.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.type.SuspiciousStewEffectsComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.util.Rarity;
import net.minecraft.util.Unit;

/**
 * C1d-D1b-3 ITEM default component型別契約的唯一owner：已審型別的單一分派表（{@link #contract}），以及ITEM leaf在codec之前逐一
 * 證明lease identity的static registry固定聯集（{@link #staticRegistries}）。每個已審型別在表內同一處宣告四件事：值條件、codec前的
 * copy重建（LORE以外的literal與只收空值型別明確宣告{@link #AS_IS}）、ref抽取（literal與只收空值的型別明確宣告{@link #NO_REFS}），
 * 以及已接受的值經codec會查詢的static registry。呼叫端只經同一筆契約取用這些，所以不會另有一張ref或copy分派表與值條件表漂移，
 * 造成「型別被接受、卻沒抽出ref」的靜默少邊。D1c的loot function與D2b的模板effective component應重用本表，不另立第三份白名單；
 * TemplateValueContracts.components()目前另有自己的表，交D2b收斂。
 *
 * <p>值條件只呼叫固定JAR內exact class的record欄位或原生accessor，而且只在exact原生class成立之後才呼叫；copy重建只在同一個值的
 * 值條件成立之後呼叫，ref抽取只對已再驗值條件的copy呼叫，兩者都自己再對巢狀元素驗一次exact原生class（巢狀List可以在兩次迭代間吐出
 * 不同元素），不呼叫未驗物件的任何方法。codec只編碼copy（D1b-3c-0），CodecCache的key由LeafContracts identity私有的ops隔離（D1b-3c-0b；
 * 兩個方向的保證見{@link Copy}）；容器或巢狀List自稱的內容仍可以與另一次迭代的內容不同，所以
 * 呼叫端對容器做第二次迭代來建codec要編碼的copy，對roundtrip重建的原生map再套一次本表，並逐一比對第一次迭代與重建值抽出的ref
 * （D1b-3設計審查M2）。遊戲之後使用的仍是原物件：foreign List在之後的迭代吐出不同內容，屬spec §3.1.1的Java implementation殘餘
 * （與D1b-3-0 S0-1同類）。
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
    /**
     * ref抽取的接收端（D1b-3b quality Q-M4(a)）：entry收named registry entry的引用；tag收tool tag rule的Named list，預留給D1b-3c。
     * D1b-3c之前沒有抽取器呼叫tag，唯一的實作（LeafContracts.requireReviewedDefaults）對tag一律fail closed，不會靜默丟掉tag引用。
     */
    interface RefSink {
        void entry(String path,RegistryEntry.Reference<?> entry);
        void tag(String path,RegistryEntryList.Named<?> tag);
    }
    /**
     * 一型的ref抽取：只對值條件已成立的copy呼叫；sink收到相對於該component的path（例如effects/0/effect/id）。新增帶引用的型別時，
     * 三處要同步（D1b-3b quality Q-M3）：本表該型的refs（不得是NO_REFS）與registries、LeafTest的refBearingItemDefaultTypes()，以及
     * LeafTest的independentRefs（M9）；三處同時漏列時，由M9與型別模型無關的NBT交叉檢查抓到。
     */
    @FunctionalInterface interface Refs {
        void extract(Object value,RefSink sink);
    }
    /** literal與只收空值型別的明確宣告：已接受的值不含任何引用。 */
    static final Refs NO_REFS=(value,sink)->{};
    /**
     * 一型的codec前copy重建（D1b-3b spec M1＋quality Q-M1，D1b-3c-0）：只在該型值條件對同一個值成立之後呼叫，回傳codec實際要編碼的值；
     * 呼叫端對回傳值再套一次值條件。「重建」的意思是：巢狀容器換成以已驗元素建立的JDK不可變List（List.copyOf），只收空值的型別正規化
     * 為原生DEFAULT，Optional literal先驗內容是exact Float／Boolean再重建Optional；named的Reference（與D1b-3c的Named list）保持identity，
     * 兩者都沒有覆寫equals／hashCode。帶List的已審record（attribute_modifiers、tool、food）以此重建；LORE，以及D1b-3c-0b（spec M2）起的
     * potion_contents、suspicious_stew_effects、banner_patterns正規化為各自的DEFAULT。
     *
     * <p>DataComponentTypes.CACHE（CodecCache，以(codec identity, value, ops)為key的全域Guava cache；Key.equals依序比codec identity、
     * value.equals、ops.equals，查詢key是receiver）的等值替換有兩個方向，保證範圍如下（javap，D1B3C0B-JAVAP-FACTS）：
     * <ul>
     * <li>探測端（本值是foreign）：codec只看到copy，查詢時的hashCode與「copy.equals(已存值)」的receiver都是固定JAR與JDK的程式碼，所以
     * foreign的本值無法讓encode命中另一個值的快取編碼（D1b-3c-0）。DFU optionalFieldOf編碼時的Objects.equals(value, default)是另一條
     * 等值捷徑，同樣只看到copy。</li>
     * <li>快取端（已存的值是foreign）：JDK List.equals迭代的是對方的iterator，所以只要ops相等，已存的foreign List仍能讓誠實的copy命中，
     * 而且它的程式碼會在本encode內執行（quality Q-M1）。copy關不掉這個方向；LeafContracts因此以identity私有的WrapperLookup建立ops
     * （D1b-3c-0b），不與遊戲或其他encode的ops相等，能命中的只有同一個LeafContracts自己存入的entry。那些值都是copy或roundtrip重建的
     * 原生map；帶CodecCache卻宣告AS_IS的型別（enchantments、charged_projectiles、container）的理由見{@link #AS_IS}。</li>
     * </ul>
     */
    @FunctionalInterface interface Copy {
        Object rebuild(Object value);
    }
    /**
     * 不重建的明確宣告。literal（Integer、Boolean、Rarity、Unit.INSTANCE）的值本身就是不可變的原生或JDK物件，codec也不帶CodecCache；
     * CONTAINER帶CodecCache，但值條件只收DEFAULT單例本身。
     *
     * <p>enchantments與charged_projectiles也帶CodecCache，卻宣告AS_IS（LeafContracts私有ops的前提「帶快取的型別都不是AS_IS，或已另外
     * 處理」，D1b-3c-0b quality Q-M1(B)）。理由：
     * <ul>
     * <li>兩者的建構子都不是public（javap）：ItemEnchantmentsComponent的Builder、codec與withShowInTooltip都建出或沿用原生
     * Object2IntOpenHashMap；ChargedProjectilesComponent.of以List.copyOf複製。要放進foreign容器，得繞過存取限制。</li>
     * <li>即使繞過：值條件只收空值，其餘欄位（show_in_tooltip）是原生final欄位，而私有ops下能命中的entry只來自同一個LeafContracts
     * 自己的capture。所以命中時，payload只會是同一個空值的編碼，或是重建map再驗會拒的非空編碼；後者表示存入它的那件item本身已被拒，
     * 整個closure fail closed。</li>
     * <li>殘餘：foreign容器的方法仍可能在之後的查詢內執行；那是同一個物件在它自己的值條件裡已執行過的程式碼。</li>
     * </ul>
     * potion_contents、suspicious_stew_effects、banner_patterns的record以public建構子原樣收List，D1b-3c-0b（spec M2）起改為正規化成
     * DEFAULT，不再是AS_IS。
     */
    static final Copy AS_IS=value->value;
    /**
     * 一個已審型別的契約。
     * <ul>
     * <li>value：值條件。除了回傳false，也可以丟具名Failure：FOOD在using_converts_to的目標item不是原生source時丟
     * NATIVE_DEFINITION_SOURCE（D1b-3b quality Q-M4(e)；呼叫端原樣交回）。</li>
     * <li>copy：codec前的copy重建（AS_IS或重建器，不得為null）。</li>
     * <li>refs：ref抽取（NO_REFS或抽取器，不得為null）。</li>
     * <li>registries：已接受的值經codec會查詢的static registry，只作宣告，由結構測試核對它屬於staticRegistries()；production的identity
     * 證明一律用固定聯集，不依本欄增減。本欄只列static registry（D1b-3b quality Q-M4(b)）：D1b-3d的jukebox_playable引用dynamic的
     * JUKEBOX_SONG（只要求存在，不在固定聯集），屆時結構測試的「有ref ⇔ registries非空」與「registries屬於固定聯集」要重新定義
     * （3d待辦）。</li>
     * </ul>
     */
    record Contract(Predicate<Object> value,Copy copy,Refs refs,List<RegistryKey<? extends Registry<?>>> registries) {
        Contract {
            Objects.requireNonNull(value);
            Objects.requireNonNull(copy);
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
    /**
     * 整張已審型別表（不可修改的identity map），供結構測試逐型核對。迭代次序取決於System.identityHashCode，每次啟動JVM都可能不同
     * （D1b-3b quality Q-M4(d)）：不得用來產生canonical輸出，也不得用來決定錯誤的先後；D1c與D2b重用本表時一律以contract(type)查表。
     */
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
            // D1b-2：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（Integer）、RARITY（exact Rarity）；ENCHANTMENTS、LORE只收
            // exact原生class且為空，LORE另正規化為DEFAULT（見emptyLore）。D1b-2的ATTRIBUTE_MODIFIERS（只收空值）由下方D1b-3b的契約放寬取代。
            put(table,DataComponentTypes.MAX_STACK_SIZE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.MAX_DAMAGE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.DAMAGE,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.REPAIR_COST,noRefs(value->value instanceof Integer));
            put(table,DataComponentTypes.RARITY,noRefs(value->nativeExact(value,Rarity.class)));
            put(table,DataComponentTypes.ENCHANTMENTS,noRefs(value->nativeExact(value,ItemEnchantmentsComponent.class)
                    &&((ItemEnchantmentsComponent)value).isEmpty()));
            put(table,DataComponentTypes.LORE,new Contract(Minecraft121ItemComponentContracts::emptyLore,value->LoreComponent.DEFAULT,
                    NO_REFS,List.of()));
            // D1b-3a的literal（D1b-3設計§2.2）：FIRE_RESISTANT的值須是固定JAR內單例enum net.minecraft.util.Unit.INSTANCE；
            // ENCHANTMENT_GLINT_OVERRIDE（Boolean）、OMINOUS_BOTTLE_AMPLIFIER（Integer；原生範圍0..4由codec強制）。Boolean與Integer
            // 是JDK的final類別（CodeSource為null），不能套exact原生source，只驗型別。
            put(table,DataComponentTypes.FIRE_RESISTANT,noRefs(value->value==Unit.INSTANCE));
            put(table,DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,noRefs(value->value instanceof Boolean));
            put(table,DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER,noRefs(value->value instanceof Integer));
            // D1b-3a只收空DEFAULT：POTION_CONTENTS（三欄皆空）、SUSPICIOUS_STEW_EFFECTS、BANNER_PATTERNS、CHARGED_PROJECTILES；
            // CONTAINER就是DEFAULT本身：經ComponentMap.CODEC（fromSlots）或fromStacks產生的空container都是這個單例；其他原生路徑
            // （例如PACKET_CODEC）產生的非DEFAULT空container會被誤拒，方向是fail closed。非空值會引用POTION、STATUS_EFFECT、ITEM或
            // dynamic的BANNER_PATTERN，不在D1b-3a。D1b-3c-0b（spec M2）：POTION_CONTENTS、SUSPICIOUS_STEW_EFFECTS、BANNER_PATTERNS的record以
            // public建構子原樣收List（javap），所以比照LORE以emptyList確認真的沒有元素，copy正規化為各自的DEFAULT；CHARGED_PROJECTILES的
            // 建構子是private，維持AS_IS（理由見AS_IS）。
            put(table,DataComponentTypes.POTION_CONTENTS,emptyDefault(Minecraft121ItemComponentContracts::emptyPotionContents,PotionContentsComponent.DEFAULT));
            put(table,DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,emptyDefault(value->nativeExact(value,SuspiciousStewEffectsComponent.class)
                    &&emptyList(((SuspiciousStewEffectsComponent)value).effects()),SuspiciousStewEffectsComponent.DEFAULT));
            put(table,DataComponentTypes.CONTAINER,noRefs(value->value==ContainerComponent.DEFAULT));
            put(table,DataComponentTypes.BANNER_PATTERNS,emptyDefault(value->nativeExact(value,BannerPatternsComponent.class)
                    &&emptyList(((BannerPatternsComponent)value).layers()),BannerPatternsComponent.DEFAULT));
            put(table,DataComponentTypes.CHARGED_PROJECTILES,noRefs(value->nativeExact(value,ChargedProjectilesComponent.class)
                    &&((ChargedProjectilesComponent)value).isEmpty()));
            // D1b-3b的static引用（D1b-3設計§2.2）：非空ATTRIBUTE_MODIFIERS（→ATTRIBUTE）、FOOD（→STATUS_EFFECT、ITEM）、只有direct
            // rule的TOOL（→BLOCK）。引用一律須是exact原生RegistryEntry.Reference（inline的Direct entry在codec之前就拒）。tool的tag
            // rule屬D1b-3c、jukebox_playable屬D1b-3d，仍不在本表。三型的record都不複製List，codec之前以已驗元素重建（D1b-3c-0）。
            put(table,DataComponentTypes.ATTRIBUTE_MODIFIERS,new Contract(Minecraft121ItemComponentContracts::attributeModifiers,
                    Minecraft121ItemComponentContracts::attributeModifiersCopy,Minecraft121ItemComponentContracts::attributeModifierRefs,
                    List.of(RegistryKeys.ATTRIBUTE)));
            put(table,DataComponentTypes.FOOD,new Contract(Minecraft121ItemComponentContracts::food,
                    Minecraft121ItemComponentContracts::foodCopy,Minecraft121ItemComponentContracts::foodRefs,
                    List.of(RegistryKeys.STATUS_EFFECT,RegistryKeys.ITEM)));
            put(table,DataComponentTypes.TOOL,new Contract(Minecraft121ItemComponentContracts::directTool,
                    Minecraft121ItemComponentContracts::directToolCopy,Minecraft121ItemComponentContracts::directToolRefs,
                    List.of(RegistryKeys.BLOCK)));
            return Collections.unmodifiableMap(table);
        }
        private static void put(Map<ComponentType<?>,Contract> table,ComponentType<?> type,Contract contract) {
            if(table.put(Objects.requireNonNull(type),contract)!=null)throw new IllegalStateException("duplicate item component contract");
        }
        /** literal或只收空值的型別：明確宣告AS_IS（不重建；只收空值者的理由見AS_IS）與NO_REFS，codec不查任何registry。 */
        private static Contract noRefs(Predicate<Object> value) {
            return new Contract(value,AS_IS,NO_REFS,List.of());
        }
        /**
         * 只收空值、copy正規化為原生DEFAULT的型別（D1b-3c-0b，spec M2）：codec只看到empty，所以值條件必須自己確認原值真的沒有元素
         * （emptyList）；NO_REFS，codec不查任何registry。
         */
        private static Contract emptyDefault(Predicate<Object> value,Object empty) {
            Objects.requireNonNull(empty);
            return new Contract(value,ignored->empty,NO_REFS,List.of());
        }
    }

    /**
     * 非空ATTRIBUTE_MODIFIERS（放寬D1b-2的「必須為空」）：exact原生AttributeModifiersComponent，每個entry是exact原生Entry，其attribute
     * 是exact原生Reference。modifier（id、amount、operation）與slot、show_in_tooltip是literal（Identifier.CODEC不綁registry）。
     */
    private static boolean attributeModifiers(Object value) {
        if(!nativeExact(value,AttributeModifiersComponent.class))return false;
        var modifiers=((AttributeModifiersComponent)value).modifiers();
        if(modifiers==null)return false;
        for(var entry:modifiers)
            if(!nativeExact(entry,AttributeModifiersComponent.Entry.class)||!nativeExact(entry.attribute(),RegistryEntry.Reference.class))return false;
        return true;
    }
    /**
     * copy：以List.copyOf重建modifiers。元素是exact原生Entry（record；attribute由值條件對copy驗為exact原生Reference，modifier是final
     * record，slot是enum），所以Entry本身保留，hashCode／equals只執行固定JAR與JDK的程式碼。
     */
    private static Object attributeModifiersCopy(Object value) {
        var component=(AttributeModifiersComponent)value;
        var entries=new ArrayList<AttributeModifiersComponent.Entry>();
        for(var element:component.modifiers())entries.add(exact(element,AttributeModifiersComponent.Entry.class));
        return new AttributeModifiersComponent(List.copyOf(entries),component.showInTooltip());
    }
    /** modifiers/i/type → ATTRIBUTE（codec欄位名type）。 */
    private static void attributeModifierRefs(Object value,RefSink sink) {
        int i=0;
        for(var element:((AttributeModifiersComponent)value).modifiers()) {
            var entry=exact(element,AttributeModifiersComponent.Entry.class);
            sink.entry("modifiers/"+i+"/type",reference(entry.attribute()));
            i++;
        }
    }
    /**
     * FOOD：exact原生FoodComponent；nutrition、saturation、can_always_eat、eat_seconds是literal。using_converts_to若存在，見
     * convertsToStack；每個effect是exact原生StatusEffectEntry，effect type是exact原生Reference。
     *
     * <p>固定1.21 JAR的事實（javap）：StatusEffectEntry.effect()不是單純accessor，而是回傳new StatusEffectInstance(this.effect)；copy
     * 建構子與copyFrom只以getfield讀type、duration、amplifier、ambient、show_particles、show_icon，不複製hiddenEffect，也不呼叫原物件的
     * 任何method。CODEC與PACKET_CODEC的getter、食用時套用effect都經這個copy，所以原物件的class與hidden_effect在公開API下都觀察不到，
     * 也不會被編碼或使用。D1b-3設計§2.2「hidden_effect必須缺席」因此在本JAR恆成立，不另設檢查；這裡對copy驗exact class，是
     * 「呼叫方法之前先驗exact」的通則，在本JAR恆真。
     *
     * <p>CodecCache（D1b-3b controller D2與spec M1，D1b-3c-0與D1b-3c-0b修正）：FOOD的codec由DataComponentTypes.CACHE（以(codec identity,
     * value, ops)為key的全域Guava cache）包裝，encode時會呼叫FoodComponent的hashCode／equals，經record的ObjectMethods一路呼叫到effects
     * List與StatusEffectEntry私有欄位裡的物件；原物件的class在公開API下無法驗證。所以codec只編碼foodCopy重建的copy，這只關掉「本值是
     * foreign」的方向；「已存的值是foreign」由LeafContracts identity私有的ops隔離（兩個方向見Copy）。
     */
    private static boolean food(Object value) {
        if(!nativeExact(value,FoodComponent.class))return false;
        var food=(FoodComponent)value;
        var convertsTo=food.usingConvertsTo();var effects=food.effects();
        if(convertsTo==null||effects==null||convertsTo.isPresent()&&!convertsToStack(convertsTo.get()))return false;
        for(var entry:effects) {
            if(!nativeExact(entry,FoodComponent.StatusEffectEntry.class))return false;
            var effect=((FoodComponent.StatusEffectEntry)entry).effect();
            if(!nativeExact(effect,StatusEffectInstance.class)||!nativeExact(effect.getEffectType(),RegistryEntry.Reference.class))return false;
        }
        return true;
    }
    /**
     * using_converts_to的ItemStack：exact原生（ItemStack是final），count為1（UNCOUNTED_CODEC不編count，非1的count不會進payload，所以拒），
     * 沒有patch。D1b-3設計審查M5：解碼時ItemStack建構子會先呼叫目標item的postProcessComponents（virtual），encode也會呼叫它的
     * getRegistryEntry，所以在codec之前、在呼叫目標item的任何virtual method之前，先對executedOwnerClasses(目標item)逐項驗原生source
     * （不符為NATIVE_DEFINITION_SOURCE，與item leaf步驟2的碼相同）。
     */
    private static boolean convertsToStack(Object value) {
        if(!nativeExact(value,ItemStack.class))return false;
        var stack=(ItemStack)value;
        if(stack.getCount()!=1)return false;
        for(var type:Minecraft121RegistryLeafContracts.executedOwnerClasses(stack.getItem()))Minecraft121RegistryLeafContracts.requireNativeSource(type);
        return stack.getComponentChanges().isEmpty();
    }
    /**
     * copy：每個effect以effect()的copy（固定JAR回傳不帶hiddenEffect的新原生StatusEffectInstance）與probability重建
     * StatusEffectEntry，再List.copyOf。using_converts_to保留原Optional與原ItemStack：Optional與ItemStack都是final，ItemStack沒有覆寫
     * equals／hashCode（javap，CodecCache只比identity），目標item的source已由值條件在codec之前驗過。但ItemStack是可變物件，copy與原值
     * 共用同一個stack（D1b-3c-0b quality Q-M4(c)）：6b之後若有foreign程式碼改了它的patch，payload的安全靠7b對重建map再驗
     * getComponentChanges().isEmpty()；count不編碼（UNCOUNTED_CODEC），所以count的改變不影響payload。literal欄位照抄。
     */
    private static Object foodCopy(Object value) {
        var food=(FoodComponent)value;
        var effects=new ArrayList<FoodComponent.StatusEffectEntry>();
        for(var element:food.effects()) {
            var entry=exact(element,FoodComponent.StatusEffectEntry.class);
            effects.add(new FoodComponent.StatusEffectEntry(entry.effect(),entry.probability()));
        }
        return new FoodComponent(food.nutrition(),food.saturation(),food.canAlwaysEat(),food.eatSeconds(),food.usingConvertsTo(),
                List.copyOf(effects));
    }
    /** using_converts_to/id → ITEM；effects/i/effect/id → STATUS_EFFECT（codec欄位次序：using_converts_to在effects之前）。 */
    private static void foodRefs(Object value,RefSink sink) {
        var food=(FoodComponent)value;
        var convertsTo=food.usingConvertsTo();
        if(convertsTo.isPresent())sink.entry("using_converts_to/id",reference(exact(convertsTo.get(),ItemStack.class).getRegistryEntry()));
        int i=0;
        for(var element:food.effects()) {
            var effect=exact(exact(element,FoodComponent.StatusEffectEntry.class).effect(),StatusEffectInstance.class);
            sink.entry("effects/"+i+"/effect/id",reference(effect.getEffectType()));
            i++;
        }
    }
    /**
     * 只有direct rule的TOOL：exact原生ToolComponent，每個rule是exact原生Rule，blocks是exact原生RegistryEntryList.Direct（tag rule的
     * Named屬D1b-3c，拒），每個block是exact原生Reference。speed、correct_for_drops、default_mining_speed、damage_per_block是literal：
     * speed與correct_for_drops是Optional&lt;Float&gt;／Optional&lt;Boolean&gt;，泛型經erasure，內容可以被unchecked cast換成任意物件（heap
     * pollution），所以要求Optional非null、內容存在時class恰為Float／Boolean（jdkLiteral；D1b-3c-0b spec M1）。direct list經ops相關的
     * RegistryFixedCodec解析（owner須是lease的BLOCK，固定聯集已證明它就是全域物件）。
     */
    private static boolean directTool(Object value) {
        if(!nativeExact(value,ToolComponent.class))return false;
        var rules=((ToolComponent)value).rules();
        if(rules==null)return false;
        for(var rule:rules) {
            if(!nativeExact(rule,ToolComponent.Rule.class)||!nativeExact(rule.blocks(),RegistryEntryList.Direct.class))return false;
            if(!jdkLiteral(rule.speed(),Float.class)||!jdkLiteral(rule.correctForDrops(),Boolean.class))return false;
            var blocks=(RegistryEntryList.Direct<?>)rule.blocks();
            for(int j=0;j<blocks.size();j++)if(!nativeExact(blocks.get(j),RegistryEntry.Reference.class))return false;
        }
        return true;
    }
    /**
     * Optional literal（D1b-3c-0b，spec M1）：Optional非null，內容存在時class恰為type。Optional是JDK final，isEmpty／get與getClass()都是
     * final方法，所以不會呼叫內容的任何方法；參數型別是Optional&lt;?&gt;，get()的結果直接當Object用，不經checkcast。Float與Boolean是JDK
     * final類別（CodeSource為null），不能套原生source，只驗exact class。
     */
    private static boolean jdkLiteral(Optional<?> value,Class<?> type) {
        return value!=null&&(value.isEmpty()||value.get().getClass()==type);
    }
    /** jdkLiteral的copy：再驗一次，回傳新的Optional（內容是已驗的Float／Boolean本身，JDK final且不可變）。 */
    private static <T> Optional<T> jdkLiteralCopy(Optional<?> value,Class<T> type) {
        if(!jdkLiteral(value,type))throw new Minecraft121SettingsGraph.Failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        return value.isEmpty()?Optional.empty():Optional.of(type.cast(value.get()));
    }
    /**
     * copy：每個rule的direct list以RegistryEntryList.of(List.copyOf(已驗的blocks))重建（Direct的equals／hashCode委派它持有的List），
     * speed與correct_for_drops以jdkLiteralCopy重建Optional，再List.copyOf rules。
     */
    @SuppressWarnings("unchecked")
    private static Object directToolCopy(Object value) {
        var tool=(ToolComponent)value;
        var rules=new ArrayList<ToolComponent.Rule>();
        for(var element:tool.rules()) {
            var rule=exact(element,ToolComponent.Rule.class);
            var direct=exact(rule.blocks(),RegistryEntryList.Direct.class);
            var blocks=new ArrayList<RegistryEntry<Block>>();
            for(int j=0;j<direct.size();j++)blocks.add((RegistryEntry<Block>)reference(direct.get(j)));
            rules.add(new ToolComponent.Rule(RegistryEntryList.of(List.copyOf(blocks)),jdkLiteralCopy(rule.speed(),Float.class),
                    jdkLiteralCopy(rule.correctForDrops(),Boolean.class)));
        }
        return new ToolComponent(List.copyOf(rules),tool.defaultMiningSpeed(),tool.damagePerBlock());
    }
    /** rules/i/blocks/j → BLOCK（j是direct list內的次序，與單一元素時encode成字串無關）。 */
    private static void directToolRefs(Object value,RefSink sink) {
        int i=0;
        for(var element:((ToolComponent)value).rules()) {
            var blocks=exact(exact(element,ToolComponent.Rule.class).blocks(),RegistryEntryList.Direct.class);
            for(int j=0;j<blocks.size();j++)sink.entry("rules/"+i+"/blocks/"+j,reference(blocks.get(j)));
            i++;
        }
    }
    /**
     * LORE只收空值（D1b-2），copy正規化為LoreComponent.DEFAULT（D1b-3c-0）。codec不再迭代原值的lines，所以除了兩個isEmpty（排在前面：
     * 丟例外時cause落在isEmpty，見D1b-3-0 S0-1 RED③），還要以iterator確認兩個List真的沒有元素：自稱為空、迭代卻吐出元素的List
     * （S0-1 RED①）在值條件就拒，不會被正規化成空值接受。
     */
    private static boolean emptyLore(Object value) {
        if(!nativeExact(value,LoreComponent.class))return false;
        var lore=(LoreComponent)value;
        return lore.lines().isEmpty()&&lore.styledLines().isEmpty()&&!lore.lines().iterator().hasNext()&&!lore.styledLines().iterator().hasNext();
    }
    /**
     * POTION_CONTENTS只收空值（D1b-3a）：potion與custom_color兩個Optional為空，custom_effects真的沒有元素；copy正規化為DEFAULT（D1b-3c-0b）。
     * 固定JAR的事實（javap）：customEffects()不是單純accessor，而是回傳Lists.transform(原List, StatusEffectInstance的copy建構子)的view，
     * view的迭代走原List的listIterator()；codec的custom_effects getter用的就是這個view。遊戲套用效果時（forEachEffect）與getEffects()
     * 迭代的卻是原List自己的iterator()，而potion為空時getEffects()直接回傳原List。所以兩條迭代路徑都要確認：emptyList(customEffects())
     * 驗isEmpty與listIterator，getEffects().iterator()驗原List的iterator。
     */
    private static boolean emptyPotionContents(Object value) {
        if(!nativeExact(value,PotionContentsComponent.class))return false;
        var potion=(PotionContentsComponent)value;
        return potion.potion()!=null&&potion.potion().isEmpty()&&potion.customColor()!=null&&potion.customColor().isEmpty()
                &&emptyList(potion.customEffects())&&!potion.getEffects().iterator().hasNext();
    }
    /**
     * 只收空值的巢狀List（D1b-3c-0b，spec M2；比照LORE）：copy正規化為DEFAULT之後，codec、CodecCache與DFU optionalFieldOf都不再看到原值的
     * List，也就不會再迭代它，所以除了isEmpty（排在前面），還要以iterator確認真的沒有元素：自稱為空、迭代卻吐出元素的List（D1b-3-0 S0-1
     * RED①的形狀）在值條件就拒，不會被正規化成空值接受。不呼叫List的equals／hashCode。
     */
    private static boolean emptyList(List<?> list) {
        return list!=null&&list.isEmpty()&&!list.iterator().hasNext();
    }

    /** exact class且source為原生，之後才可呼叫該值的方法。 */
    private static boolean nativeExact(Object value,Class<?> type) {
        return value!=null&&value.getClass()==type&&Minecraft121RegistryLeafContracts.nativeSource(type);
    }
    /**
     * ref抽取端的防線：巢狀List可以在值條件與抽取兩次迭代間吐出不同元素，所以抽取端對每個要呼叫方法的物件再驗exact原生class，
     * 不符為UNSUPPORTED_ITEM_DEFAULT_COMPONENT。
     */
    private static <T> T exact(Object value,Class<T> type) {
        if(!nativeExact(value,type))throw new Minecraft121SettingsGraph.Failure("UNSUPPORTED_ITEM_DEFAULT_COMPONENT");
        return type.cast(value);
    }
    private static RegistryEntry.Reference<?> reference(Object entry) {
        return exact(entry,RegistryEntry.Reference.class);
    }
}
