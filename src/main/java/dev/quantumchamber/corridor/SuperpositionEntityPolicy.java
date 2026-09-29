package dev.quantumchamber.corridor;

import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ArmorStandItem;
import net.minecraft.item.BoatItem;
import net.minecraft.item.DecorationItem;
import net.minecraft.item.EndCrystalItem;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.LeadItem;
import net.minecraft.item.LingeringPotionItem;
import net.minecraft.item.MinecartItem;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/**
 * 固定 Superposition world 只承載走廊 pin／換頁／返還會管理的 entity：玩家、掉落物、投射物與經驗球。
 * 其他 entity 先在使用物品階段拒絕（物品邏輯尚未執行，不消耗物品），再於加入世界與跨維度進入時以 backstop 拒絕。
 * 範圍取整個 Superposition world 而非只限 lease：該 world 只用於走廊，lease 外是無人管理的 void，任何 entity 在那裡都無法被 pin 或返還。
 */
public final class SuperpositionEntityPolicy {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private SuperpositionEntityPolicy() {}

    /** 走廊 pin、換頁搬移與返還共同承認的 entity 種類。 */
    public static boolean managed(Entity entity) {
        return entity instanceof PlayerEntity || entity instanceof ItemEntity || entity instanceof ProjectileEntity
                || entity instanceof ExperienceOrbEntity;
    }

    /** 只限定本 server 已登錄的固定 Superposition world；client world 與其他 world 不受影響。 */
    public static boolean restricted(World world) {
        return world instanceof ServerWorld server && server.getRegistryKey() == SuperpositionWorld.KEY
                && server.getServer().getWorld(SuperpositionWorld.KEY) == server;
    }

    /**
     * Minecraft 1.21 中使用時會生成非管理 entity 的物品：船／箱船、各式礦車、盔甲架、物品展示框／螢光物品展示框／畫、
     * 生怪蛋、裝有生物的桶、終界水晶、拴繩，以及落地產生藥水雲的滯留型藥水。
     */
    public static boolean spawnsUnmanagedEntity(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof BoatItem || item instanceof MinecartItem || item instanceof ArmorStandItem || item instanceof DecorationItem
                || item instanceof SpawnEggItem || item instanceof EntityBucketItem || item instanceof EndCrystalItem || item instanceof LeadItem
                || item instanceof LingeringPotionItem;
    }

    /** 使用物品被拒時告知玩家，並重送 inventory，避免 client 端預測扣除的數量殘留在畫面上。 */
    public static void denyItemUse(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
        serverPlayer.sendMessage(Text.literal("量子走廊內不能放置船、盔甲架、展示框、生物等實體；物品未消耗。"), true);
        serverPlayer.currentScreenHandler.syncState();
    }

    /** backstop 拒絕紀錄：同一種 entity 與路徑只記一次 WARN。 */
    public static void rejected(Entity entity, String route) {
        String type = EntityType.getId(entity.getType()).toString();
        if (WARNED.add(route + "|" + type)) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn(
                    "量子走廊世界拒絕非管理 entity（{}；只允許玩家、掉落物、投射物與經驗球）：type={} pos={}；同種類同路徑後續不再重複記錄",
                    route, type, entity.getBlockPos());
        }
    }
}
