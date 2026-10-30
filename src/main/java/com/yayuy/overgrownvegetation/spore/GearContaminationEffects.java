package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;

/**
 * 「胞子汚染」({@link GearContamination})を受けた装備の<b>性能を実際に下げる</b>ところ。
 * 下がり方は種類({@link GearContamination.Kind})ごと(2026-10-14 ユーザー指定)。耐久値の減りだけは
 * {@link com.yayuy.overgrownvegetation.mixin.ItemStackDurabilityMixin}。
 *
 * <ul>
 *     <li>{@link ItemAttributeModifierEvent} — 武器は攻撃力と攻撃速度を、手に持ったときの合計
 *     (素手の 1 / 4 を含む)が倍率ぶんになるよう基本の修飾を書き換える。リーチ・薙ぎ払いなど他の修飾は触らない。
 *     防具・その他は今までどおり<b>正の加算値</b>を倍率ぶん減らす。道具・盾・弓の属性は触らない。</li>
 *     <li>{@link PlayerEvent.BreakSpeed} — 道具の採掘速度。</li>
 *     <li>{@link EntityJoinLevelEvent} — 弓・クロスボウで撃った矢の初速(飛距離 −70% にするため、初速は √0.3 倍)。</li>
 *     <li>{@link LivingIncomingDamageEvent} — 矢・投げたトライデントのダメージ。矢は遅いぶんだけ弱くなるので、
 *     その分を戻してから攻撃力の低下を掛ける。</li>
 * </ul>
 * 盾で防げるダメージ量は下げない(前はここで下げていて、汚染された盾が攻撃を防がなくなった)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GearContaminationEffects {

    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0D;
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0D;

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
        switch (GearContamination.kindOf(stack)) {
            case WEAPON -> weakenWeapon(event, stack);
            case OTHER -> weakenPositive(event, GearContamination.factor(stack));
            default -> {
                // 道具・盾・弓は属性を下げない(採掘速度・耐久値・矢で下げる)。
            }
        }
    }

    /** 武器: 攻撃力 −35%・攻撃速度 −20%(汚染 100% のとき)。 */
    private static void weakenWeapon(ItemAttributeModifierEvent event, ItemStack stack) {
        double s = GearContamination.severity(stack);
        double damageFactor = 1.0D - GearContamination.spec().weaponAttackDamageMax() * s;
        double speedFactor = 1.0D - GearContamination.spec().weaponAttackSpeedMax() * s;
        if (damageFactor >= 1.0D && speedFactor >= 1.0D) {
            return;
        }
        List<ItemAttributeModifiers.Entry> entries = List.copyOf(event.getModifiers());
        for (ItemAttributeModifiers.Entry entry : entries) {
            AttributeModifier modifier = entry.modifier();
            if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) {
                continue;
            }
            double amount;
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE)) {
                amount = scaleTotal(entry.attribute(), modifier, Item.BASE_ATTACK_DAMAGE_ID.equals(modifier.id()), damageFactor);
            } else if (entry.attribute().is(Attributes.ATTACK_SPEED)) {
                amount = scaleTotal(entry.attribute(), modifier, Item.BASE_ATTACK_SPEED_ID.equals(modifier.id()), speedFactor);
            } else {
                continue;
            }
            event.replaceModifier(entry.attribute(),
                    new AttributeModifier(modifier.id(), amount, modifier.operation()), entry.slot());
        }
    }

    /**
     * 基本の修飾(剣の「+6」など)は、素手の値(攻撃力 1・攻撃速度 4)を足した合計が {@code factor} 倍になるよう書き換える。
     * 攻撃速度の基本の修飾は負(剣なら −2.4)なので、修飾だけを縮めると逆に速くなる —— だから合計で掛ける。
     * それ以外の正の加算値はそのまま縮める。
     */
    private static double scaleTotal(Holder<Attribute> attribute, AttributeModifier modifier, boolean base, double factor) {
        if (base) {
            // 素手の値はプレイヤーの基本値(攻撃力 1・攻撃速度 4)。属性の既定値ではない(攻撃力の既定値は 2)。
            double bare = attribute.is(Attributes.ATTACK_DAMAGE) ? PLAYER_BASE_ATTACK_DAMAGE : PLAYER_BASE_ATTACK_SPEED;
            return (bare + modifier.amount()) * factor - bare;
        }
        return modifier.amount() > 0.0D ? modifier.amount() * factor : modifier.amount();
    }

    /** 防具・その他: 正の加算値(防御力・防具強度・ノックバック耐性など)を {@code factor} 倍。 */
    private static void weakenPositive(ItemAttributeModifierEvent event, double factor) {
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

    /** 道具の採掘速度 −35%(汚染 100% のとき)。武器(剣でクモの巣を切るなど)は下げない。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        double factor = miningSpeedFactor(event.getEntity().getMainHandItem());
        if (factor < 1.0D) {
            event.setNewSpeed((float) (event.getNewSpeed() * factor));
        }
    }

    /** 採掘速度に掛ける倍率。道具だけ。 */
    public static double miningSpeedFactor(ItemStack tool) {
        if (GearContamination.percent(tool) <= 0.0F || GearContamination.kindOf(tool) != GearContamination.Kind.TOOL) {
            return 1.0D;
        }
        return 1.0D - GearContamination.spec().toolMiningSpeedMax() * GearContamination.severity(tool);
    }

    /**
     * 弓・クロスボウの矢の初速に掛ける倍率。飛距離はおおよそ初速の 2 乗に比例するので、
     * 飛距離を (1 − 0.7) 倍にするには初速を √(1 − 0.7) 倍にする。
     */
    public static double arrowVelocityFactor(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty() || GearContamination.percent(weapon) <= 0.0F
                || GearContamination.kindOf(weapon) != GearContamination.Kind.BOW) {
            return 1.0D;
        }
        double range = 1.0D - GearContamination.spec().bowRangeMax() * GearContamination.severity(weapon);
        return Math.sqrt(Math.max(0.01D, range));
    }

    /** 撃たれた矢の初速を落とす。読み込み直しの矢は保存された速さのままなので触らない。 */
    @SubscribeEvent
    public static void onArrowJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof AbstractArrow arrow) || arrow instanceof ThrownTrident) {
            return;
        }
        double factor = arrowVelocityFactor(arrow.getWeaponItem());
        if (factor < 1.0D) {
            arrow.setDeltaMovement(arrow.getDeltaMovement().scale(factor));
        }
    }

    /** 矢・投げたトライデントのダメージ。 */
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
        if (weapon == null || weapon.isEmpty() || GearContamination.percent(weapon) <= 0.0F) {
            return;
        }
        double s = GearContamination.severity(weapon);
        double factor = switch (GearContamination.kindOf(weapon)) {
            // 矢のダメージは速さに比例するので、初速を落としたぶんを戻してから −35% を掛ける。
            case BOW -> (1.0D - GearContamination.spec().bowDamageMax() * s) / arrowVelocityFactor(weapon);
            case WEAPON -> 1.0D - GearContamination.spec().weaponAttackDamageMax() * s;
            default -> 1.0D;
        };
        if (factor != 1.0D) {
            event.setAmount((float) (event.getAmount() * factor));
        }
    }

    private GearContaminationEffects() {
    }
}
