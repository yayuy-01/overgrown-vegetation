package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * 「胞子汚染」({@link GearContamination})を受けた装備の<b>性能を実際に下げる</b>ところ。
 *
 * <ul>
 *     <li>{@link ItemAttributeModifierEvent} — そのアイテムが持つ属性修飾のうち<b>正の加算値</b>
 *     (攻撃力・防御力・防具強度・ノックバック耐性など)を倍率ぶん減らす。攻撃速度のような
 *     負の修飾はそのまま(減らすと逆に速くなってしまうため)。</li>
 *     <li>{@link PlayerEvent.BreakSpeed} — 採掘速度。道具の {@code Tool} コンポーネント由来なので属性では下がらない。</li>
 *     <li>{@link LivingShieldBlockEvent} — 盾で防げるダメージ量。</li>
 *     <li>{@link LivingIncomingDamageEvent} — 弓・クロスボウ・トライデントで撃った矢のダメージ
 *     (矢の {@code baseDamage} は発射時に決まるので、当たった側で撃った武器を見て掛ける)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GearContaminationEffects {

    /**
     * アイテムの属性修飾を汚染ぶん弱める。{@link ItemAttributeModifierEvent} は
     * {@code ItemStack#getAttributeModifiers()} のたびに(両サイドで)飛ぶので、
     * まず「コンポーネントが載っているか」だけを見て即抜けする。
     */
    @SubscribeEvent
    public static void onItemAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        if (GearContamination.percent(stack) <= 0.0F) {
            return;
        }
        double factor = GearContamination.factor(stack);
        if (factor >= 1.0D) {
            return;
        }
        // replaceModifier がビルダーを作って一覧を差し替えるので、先に控えを取ってから回す。
        List<ItemAttributeModifiers.Entry> entries = List.copyOf(event.getModifiers());
        for (ItemAttributeModifiers.Entry entry : entries) {
            AttributeModifier modifier = entry.modifier();
            if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE || modifier.amount() <= 0.0D) {
                continue;
            }
            event.replaceModifier(entry.attribute(),
                    new AttributeModifier(modifier.id(), modifier.amount() * factor, modifier.operation()),
                    entry.slot());
        }
    }

    /** 採掘速度。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        ItemStack tool = event.getEntity().getMainHandItem();
        double factor = GearContamination.factor(tool);
        if (factor < 1.0D) {
            event.setNewSpeed((float) (event.getNewSpeed() * factor));
        }
    }

    /** 盾の防御量。 */
    @SubscribeEvent
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        if (!event.getBlocked()) {
            return;
        }
        ItemStack shield = event.getEntity().getUseItem();
        double factor = GearContamination.factor(shield);
        if (factor < 1.0D) {
            event.setBlockedDamage((float) (event.getBlockedDamage() * factor));
        }
    }

    /** 弓・クロスボウ・トライデントで撃った矢のダメージ。 */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        ItemStack weapon = arrow.getWeaponItem();
        if ((weapon == null || weapon.isEmpty()) && arrow instanceof ThrownTrident) {
            // 投げたトライデントは firedFromWeapon を持たない(トライデント自身が武器)。
            weapon = arrow.getPickupItemStackOrigin();
        }
        if (weapon == null || weapon.isEmpty()) {
            return;
        }
        double factor = GearContamination.factor(weapon);
        if (factor < 1.0D) {
            event.setAmount((float) (event.getAmount() * factor));
        }
    }

    private GearContaminationEffects() {
    }
}
