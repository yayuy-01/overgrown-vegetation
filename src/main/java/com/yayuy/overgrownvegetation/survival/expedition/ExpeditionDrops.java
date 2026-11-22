package com.yayuy.overgrownvegetation.survival.expedition;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ExpeditionSpec;
import com.yayuy.overgrownvegetation.registry.ModExpedition;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDropsEvent;

/**
 * 濃い所の素材のドロップ(2026-11-16 段 4、決定 39 の「倒す道」)。G2・G3 は種がこれからも増えるので、ルートテーブルではなくグループのタグ
 * ({@code group_2}・{@code group_3})で落とす。プレイヤーが倒したときだけ【仮】。
 * <ul>
 *     <li>G3 → 硬い樹皮片 {@code barkDropChance} 30% + ドロップ増加 1 段ごとに {@code barkLootingBonus} 5%【仮】</li>
 *     <li>G2 → 休眠嚢 {@code cureDropChance} 25% + ドロップ増加 1 段ごとに {@code cureLootingBonus} 5%【仮】(11-16 夜ユーザー了承)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ExpeditionDrops {

    private ExpeditionDrops() {
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        // 直接の原因がプレイヤーか、プレイヤーが直前(5 秒以内)に傷を入れていれば落とす(11-18 見直し ②11・ユーザー決定 4:
        // 胞子弾・焼き切りの刃で火を付けて、燃えて倒れたときも落とす。バニラの「プレイヤーに倒された」と同じ見方)
        if (!(event.getSource().getEntity() instanceof Player) && !event.isRecentlyHit()) return;
        ItemStack drop = rollDrop(entity, looting(level, event), level.random.nextDouble());
        if (!drop.isEmpty()) {
            event.getDrops().add(new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), drop));
        }
    }

    /** 倒した {@code entity} が落とす素材(無ければ空)。{@code roll} は 0〜1 の乱数(GameTest から決めて呼ぶ)。 */
    public static ItemStack rollDrop(LivingEntity entity, int looting, double roll) {
        ExpeditionSpec s = DesignData.sporeField().expedition();
        if (entity.getType().is(ModTags.GROUP_3)) {
            double chance = s.barkDropChance() + s.barkLootingBonus() * looting;
            if (roll < chance) return new ItemStack(ModExpedition.HARD_BARK_SHARD.get());
        }
        if (entity.getType().is(ModTags.GROUP_2)) {
            double chance = s.cureDropChance() + s.cureLootingBonus() * looting;
            if (roll < chance) return new ItemStack(ModExpedition.DORMANCY_SAC.get());
        }
        return ItemStack.EMPTY;
    }

    private static int looting(ServerLevel level, LivingDropsEvent event) {
        if (!(event.getSource().getEntity() instanceof LivingEntity killer)) return 0;
        return event.getLootingLevel(); // 1.20.1: Forge の知らせが倒した物のドロップ増加を持つ
    }
}
