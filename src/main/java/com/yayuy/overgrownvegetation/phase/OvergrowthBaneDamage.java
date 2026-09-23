package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * <b>汚染特効</b> — {@code #overgrown_vegetation:overgrowth_bane} タグの武器で
 * グループ1〜10({@link MobGroup})の Mob を殴ったとき、ダメージを
 * {@code gear.overgrowthBaneMultiplier}(既定 1.5)倍にする(ユーザー要望 2026-09-16)。
 *
 * <p>既定でタグに入っているのはクロロフィライトとクロロフィライト合金の剣・斧。
 * 対象の増減は {@code data/overgrown_vegetation/tags/item/overgrowth_bane.json} で行う。
 *
 * <h2>掛かる範囲</h2>
 * <ul>
 *     <li><b>直接殴ったぶんだけ</b>。{@code getDirectEntity() != 攻撃者} のダメージ
 *     (矢・爆発など、武器を手に持っていても間接的に入るもの)には掛からない。</li>
 *     <li>相手がグループ1〜10 に属していないと掛からない(バニラ Mob・プレイヤーには効かない)。</li>
 *     <li>攻撃者はプレイヤーに限っていない。汚染側どうしは
 *     {@link MobGroupFriendlyFire} が先にダメージを打ち消すので、実際に効くのはプレイヤー側だけ。</li>
 * </ul>
 *
 * <h2>装備の胞子汚染との関係</h2>
 * <p>汚染による弱体化は武器の<b>属性修飾</b>を削る形で効く
 * ({@link com.yayuy.overgrownvegetation.spore.GearContaminationEffects})。
 * つまり弱まったあとの攻撃力にこの倍率が掛かるので、汚染された武器の特効も弱くなる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrowthBaneDamage {

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || MobGroup.of(victim) == null) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)
                || event.getSource().getDirectEntity() != attacker) {
            // 間接ダメージ(矢・爆発など)は対象外。
            return;
        }
        if (!attacker.getMainHandItem().is(ModTags.OVERGROWTH_BANE)) {
            return;
        }
        double multiplier = OvergrownVegetationConfig.COMMON.overgrowthBaneMultiplier.get();
        if (multiplier == 1.0D) {
            return;
        }
        event.setAmount((float) (event.getAmount() * multiplier));
    }

    private OvergrowthBaneDamage() {
    }
}
