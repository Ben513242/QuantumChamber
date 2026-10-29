package dev.quantumchamber.universe;

import java.util.List;
import net.minecraft.component.Component;
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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Rarity;
import net.minecraft.util.Unit;

/**
 * C1d-D1b-3 ITEM default component型別契約的唯一owner：已審型別與逐型值條件表，以及原生ComponentMap.CODEC（含巢狀codec）
 * 可能經全域registry解析的static registry固定聯集。ITEM leaf（Minecraft121RegistryLeafContracts）在codec之前對這組聯集逐一證明
 * lease identity，再逐項套用本表。D1c的loot function與D2b的模板effective component應重用本表，不另立第三份白名單；
 * TemplateValueContracts.components()目前另有自己的表，交D2b收斂。
 *
 * <p>值條件只呼叫固定JAR內exact class的record欄位或原生accessor，而且只在exact原生class成立之後才呼叫。容器或巢狀List自稱的
 * 內容可以與codec實際迭代的內容不同，所以呼叫端必須對roundtrip重建的原生map再套一次本表。
 */
final class Minecraft121ItemComponentContracts {
    private Minecraft121ItemComponentContracts() {}

    /** 一個static registry的key與它的全域物件。 */
    record StaticRegistry(RegistryKey<? extends Registry<?>> key,Registry<?> global) {}

    /**
     * 原生ComponentMap.CODEC可能經全域registry解析的static registry固定聯集（D1b-3設計審查M1，controller C1）：component型別鍵
     * （DATA_COMPONENT_TYPE）、ItemStack與item引用（ITEM）、tool rule的block（BLOCK）、attribute modifier（ATTRIBUTE）與status effect
     * （STATUS_EFFECT）。這些codec綁定全域registry物件、不看ops，所以只能以「lease持有的就是全域物件」證明全域解析等於lease解析。
     * 聯集固定，不隨已審型別增減，免得某型漏列一個registry時靜默走全域解析。ITEM排第一。D1b-3d起加SOUND_EVENT；dynamic的
     * JUKEBOX_SONG只能經lease解析，不在本表。
     */
    static List<StaticRegistry> staticRegistries() {
        return List.of(new StaticRegistry(RegistryKeys.ITEM,Registries.ITEM),
                new StaticRegistry(RegistryKeys.DATA_COMPONENT_TYPE,Registries.DATA_COMPONENT_TYPE),
                new StaticRegistry(RegistryKeys.BLOCK,Registries.BLOCK),
                new StaticRegistry(RegistryKeys.ATTRIBUTE,Registries.ATTRIBUTE),
                new StaticRegistry(RegistryKeys.STATUS_EFFECT,Registries.STATUS_EFFECT));
    }

    /**
     * 單一default component是否屬於已審型別且符合該型的值條件；不是已審型別、值的型別不符或不為空一律false。
     * <ul>
     * <li>D1b-2：MAX_STACK_SIZE／MAX_DAMAGE／DAMAGE／REPAIR_COST（Integer）、RARITY（exact Rarity）；ENCHANTMENTS、LORE、
     * ATTRIBUTE_MODIFIERS（exact原生class且為空）。</li>
     * <li>D1b-3a的literal（D1b-3設計§2.2）：FIRE_RESISTANT（值須是固定JAR內單例enum net.minecraft.util.Unit.INSTANCE）、
     * ENCHANTMENT_GLINT_OVERRIDE（Boolean）、OMINOUS_BOTTLE_AMPLIFIER（Integer；原生範圍0..4由codec強制）。Boolean與Integer是JDK
     * 的final類別（CodeSource為null），不能套exact原生source，只驗型別。</li>
     * <li>D1b-3a只收空DEFAULT：POTION_CONTENTS（potion、customColor、customEffects三欄皆空）、SUSPICIOUS_STEW_EFFECTS（effects為空）、
     * BANNER_PATTERNS（layers為空）、CHARGED_PROJECTILES（isEmpty）、CONTAINER（就是DEFAULT本身：原生fromSlots與fromStacks遇空時都
     * 回傳DEFAULT，所以原生產生的空container一定是這個單例）。非空值會引用POTION、STATUS_EFFECT、ITEM或dynamic的BANNER_PATTERN，
     * 不在D1b-3a。</li>
     * </ul>
     * 會引用其他registry或tag的型別（tool、非空attribute_modifiers、food、jukebox_playable等）屬D1b-3b～3d，不在本表。
     * 巢狀List的isEmpty由該List自己回報，所以呼叫端要對重建的map再套一次。
     */
    static boolean reviewedDefault(Component<?> component) {
        var type=component.type();Object value=component.value();
        if(type==DataComponentTypes.MAX_STACK_SIZE||type==DataComponentTypes.MAX_DAMAGE
                ||type==DataComponentTypes.DAMAGE||type==DataComponentTypes.REPAIR_COST)return value instanceof Integer;
        if(type==DataComponentTypes.RARITY)return nativeExact(value,Rarity.class);
        if(type==DataComponentTypes.ENCHANTMENTS)return nativeExact(value,ItemEnchantmentsComponent.class)
                &&((ItemEnchantmentsComponent)value).isEmpty();
        if(type==DataComponentTypes.LORE)return nativeExact(value,LoreComponent.class)
                &&((LoreComponent)value).lines().isEmpty()&&((LoreComponent)value).styledLines().isEmpty();
        if(type==DataComponentTypes.ATTRIBUTE_MODIFIERS)return nativeExact(value,AttributeModifiersComponent.class)
                &&((AttributeModifiersComponent)value).modifiers().isEmpty();
        if(type==DataComponentTypes.FIRE_RESISTANT)return value==Unit.INSTANCE;
        if(type==DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)return value instanceof Boolean;
        if(type==DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER)return value instanceof Integer;
        if(type==DataComponentTypes.POTION_CONTENTS)return nativeExact(value,PotionContentsComponent.class)
                &&((PotionContentsComponent)value).potion().isEmpty()&&((PotionContentsComponent)value).customColor().isEmpty()
                &&((PotionContentsComponent)value).customEffects().isEmpty();
        if(type==DataComponentTypes.SUSPICIOUS_STEW_EFFECTS)return nativeExact(value,SuspiciousStewEffectsComponent.class)
                &&((SuspiciousStewEffectsComponent)value).effects().isEmpty();
        if(type==DataComponentTypes.CONTAINER)return value==ContainerComponent.DEFAULT;
        if(type==DataComponentTypes.BANNER_PATTERNS)return nativeExact(value,BannerPatternsComponent.class)
                &&((BannerPatternsComponent)value).layers().isEmpty();
        if(type==DataComponentTypes.CHARGED_PROJECTILES)return nativeExact(value,ChargedProjectilesComponent.class)
                &&((ChargedProjectilesComponent)value).isEmpty();
        return false;
    }
    /** exact class且source為原生，之後才可呼叫該值的方法。 */
    private static boolean nativeExact(Object value,Class<?> type) {
        return value!=null&&value.getClass()==type&&Minecraft121RegistryLeafContracts.nativeSource(type);
    }
}
