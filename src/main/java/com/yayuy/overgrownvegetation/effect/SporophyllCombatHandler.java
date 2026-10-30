package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 独自バフ {@link ModMobEffects#SPOROPHYLL_SPORES 「スポロフィルの胞子」} の効き目。
 * スポロフィルの傘の下で強化された味方が殴ったときだけ働く。
 *
 * <ol>
 *     <li><b>防御力を {@code sporophyllArmorPiercing}(effects.json の既定 20%)無視する</b> —
 *         ダメージ計算に入る<b>直前</b>に、殴られた側の {@code ARMOR} と {@code ARMOR_TOUGHNESS} を
 *         一時的な修飾子で削り、計算が終わったら外す。</li>
 *     <li><b>防具の耐久値を「与ダメージ × {@code sporophyllArmorDurabilityFactor}」だけ削る</b> —
 *         身につけている防具<b>1 枚ずつ</b>に。バニラは 1 発で(ダメージ/4)しか減らないので、
 *         これはかなり速く壊れる(ユーザー指定「攻撃力の値分防具の耐久値を削る」)。</li>
 * </ol>
 *
 * <h2>なぜ属性を一時的に削るのか</h2>
 * バニラのダメージ計算({@code CombatRules.getDamageAfterAbsorb})は<b>防具貫通を引数に取らない</b>
 * ({@code bypasses_armor} で全部素通しするか、防具を効かせるかの二択)。「半分だけ貫通」を
 * 素直に表現する入口が無いので、<b>殴られた側の防御力そのもの</b>を計算の前後だけ下げる。
 * {@link LivingIncomingDamageEvent} は計算前、{@link LivingDamageEvent.Post} は計算後に来るので、
 * この 2 つで挟めば他の攻撃には一切影響しない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporophyllCombatHandler {

    /** 防御力を一時的に削るための修飾子の id。 */
    private static final net.minecraft.resources.ResourceLocation PIERCE_ARMOR =
            OvergrownVegetation.id("sporophyll_pierce_armor");
    private static final net.minecraft.resources.ResourceLocation PIERCE_TOUGHNESS =
            OvergrownVegetation.id("sporophyll_pierce_toughness");

    /** ダメージ計算の直前: 殴られた側の防御力を割合で削る。 */
    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!isSporophyllAttack(event.getSource().getEntity())) {
            return;
        }
        double pierce = com.yayuy.overgrownvegetation.data.DesignData.effects().sporophyllArmorPiercing();
        if (pierce <= 0.0D) {
            return;
        }
        LivingEntity victim = event.getEntity();
        applyPierce(victim, Attributes.ARMOR, PIERCE_ARMOR, pierce);
        applyPierce(victim, Attributes.ARMOR_TOUGHNESS, PIERCE_TOUGHNESS, pierce);
    }

    /** ダメージ計算の直後: 防御力を元に戻し、防具の耐久値を削る。 */
    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity victim = event.getEntity();
        // 貫通の後始末は「攻撃者が誰か」に関係なく必ず行う(付いていなければ何も起きない)。
        removePierce(victim, Attributes.ARMOR, PIERCE_ARMOR);
        removePierce(victim, Attributes.ARMOR_TOUGHNESS, PIERCE_TOUGHNESS);

        if (!isSporophyllAttack(event.getSource().getEntity())) {
            return;
        }
        double factor = com.yayuy.overgrownvegetation.data.DesignData.effects().sporophyllArmorDurabilityFactor();
        if (factor <= 0.0D) {
            return;
        }
        int wear = (int) Math.round(event.getNewDamage() * factor);
        if (wear <= 0) {
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            ItemStack stack = victim.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                stack.hurtAndBreak(wear, victim, slot);
            }
        }
    }

    private static boolean isSporophyllAttack(net.minecraft.world.entity.Entity attacker) {
        return attacker instanceof LivingEntity living && living.hasEffect(ModMobEffects.SPOROPHYLL_SPORES);
    }

    private static void applyPierce(LivingEntity victim,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    net.minecraft.resources.ResourceLocation id, double pierce) {
        AttributeInstance instance = victim.getAttribute(attribute);
        if (instance == null || instance.getModifier(id) != null) {
            return;
        }
        instance.addTransientModifier(new AttributeModifier(id, -pierce,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void removePierce(LivingEntity victim,
                                     net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                     net.minecraft.resources.ResourceLocation id) {
        AttributeInstance instance = victim.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    private SporophyllCombatHandler() {
    }
}
