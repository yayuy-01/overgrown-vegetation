package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 段 3 の地脈の装甲の能力(2026-11-18 決定 19)。力は<b>胴の結晶</b>から払い、切れたら能力だけ止まる(防御は残る)。
 * <ul>
 *   <li>脚: <b>押されない</b> —— ノックバックを消す。1 回 {@code knockbackCost} FE。</li>
 *   <li>足: <b>落ちても痛まない</b> —— 落下ダメージを消す。1 ダメージにつき {@code fallCostPerDamage} FE(足りなければ払える分だけ減らす)。</li>
 *   <li>胴: <b>バフ</b>(11-18 ユーザー決定 = 案 B): 常に再生 I と満腹の減りを遅く({@code hungerReliefPerSecond})。1 秒 {@code regenCostPerSecond} FE(10 万 FE で約 28 分)。</li>
 *   <li>頭: マスクと一体(フィルターを使う。{@link TierHelmetItem})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyArmorPowers {

    private LeyArmorPowers() {
    }

    private static GearTierTable.LeyArmorPower p() {
        return DesignData.gearTiers().leyArmorPower;
    }

    /** 着ている胴の結晶(地脈の装甲の胴でなければ空)。 */
    public static ItemStack crystal(LivingEntity e) {
        ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof LeyChestItem ? chest : ItemStack.EMPTY;
    }

    static boolean wears(LivingEntity e, EquipmentSlot slot) {
        ItemStack s = e.getItemBySlot(slot);
        return s.getItem() instanceof TierArmorItem t && t.spec() == DesignData.gearTiers().leyArmor && t.slotMatches(slot);
    }

    private static boolean spend(LivingEntity e, ItemStack crystal, int fe) {
        if (e instanceof Player pl && pl.getAbilities().instabuild) return true;
        return LeyCrystal.spend(crystal, fe);
    }

    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !wears(e, EquipmentSlot.LEGS)) return;
        ItemStack c = crystal(e);
        if (!c.isEmpty() && spend(e, c, p().knockbackCost)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !event.getSource().is(DamageTypeTags.IS_FALL) || !wears(e, EquipmentSlot.FEET)) return;
        ItemStack c = crystal(e);
        if (c.isEmpty()) return;
        event.setAmount(absorbFall(e, c, event.getAmount()));
        if (event.getAmount() <= 0.0F) event.setCanceled(true);
    }

    /** 落下ダメージ {@code amount} を結晶で消し、残りのダメージを返す(GameTest からも)。 */
    public static float absorbFall(LivingEntity e, ItemStack crystal, float amount) {
        int per = Math.max(1, p().fallCostPerDamage);
        if (e instanceof Player pl && pl.getAbilities().instabuild) return 0.0F;
        int afford = LeyCrystal.stored(crystal) / per;
        int cancel = (int) Math.min(afford, Math.ceil(amount));
        if (cancel <= 0) return amount;
        LeyCrystal.spend(crystal, cancel * per);
        return Math.max(0.0F, amount - cancel);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        GearTierTable.LeyArmorPower pw = p();
        if (!pw.regenEnabled || player.level().isClientSide || player.tickCount % 20 != 0) return;
        tickBuff(player);
    }

    /**
     * 胴のバフを 1 秒ぶん(案 B: 常に再生 + 満腹の減りを遅く)。払えたら true。結晶が切れたらバフだけ止まる(防御は残る)。GameTest からも。
     */
    public static boolean tickBuff(Player player) {
        GearTierTable.LeyArmorPower pw = p();
        ItemStack c = crystal(player);
        if (c.isEmpty() || player.getHealth() > player.getMaxHealth() * pw.regenBelowHealth) return false;
        if (!spend(player, c, pw.regenCostPerSecond)) return false;
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, pw.regenAmplifier, true, false, true));
        var food = player.getFoodData();
        food.setExhaustion((float) Math.max(0.0D, food.getExhaustionLevel() - pw.hungerReliefPerSecond));
        return true;
    }
}
