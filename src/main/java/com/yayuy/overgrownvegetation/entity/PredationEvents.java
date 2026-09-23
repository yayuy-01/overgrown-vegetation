package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * グループ3の捕食者({@link PredatorMob})が獲物を倒したときの処理。
 *
 * <ul>
 *     <li>対応するグループ2の Mob({@link Predation})を倒したら捕食数(=ステータス上昇)を加算する。</li>
 *     <li>捕食者が過繁茂 Mob(グループ1〜3)を倒したときは、その死体からアイテムを一切
 *     ドロップさせない —— 捕食は「食べられて消える」表現であって、倒すたびに肉・骨・
 *     素材アイテムがそこら中に散らばるのは不自然という要望。プレイヤーが関与しない
 *     Mob 同士の捕食で地面がアイテムまみれになるのを防ぐ。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PredationEvents {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getSource().getEntity() instanceof Mob killer
                && killer instanceof PredatorMob predator
                && (Predation.isPrey(killer.getType(), event.getEntity().getType())
                        || Predation.isInvaderPrey(killer, event.getEntity()))) {
            predator.registerPreyKill();
        }
    }

    /**
     * グループ3の捕食者に倒された過繁茂 Mob(グループ1〜3)は、通常のドロップを出さない。
     * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionDropSuppressor} と同じく
     * {@link LivingDropsEvent} をキャンセルするだけ({@link net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent}
     * は、Mob 同士の killing では経験値がそもそも出ないので触らない)。
     */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (isPredationKill(event.getSource(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** {@code victim} が過繁茂 Mob(グループ1〜3)で、かつ捕食者({@link PredatorMob})に倒されたか。 */
    private static boolean isPredationKill(DamageSource source, LivingEntity victim) {
        if (!(source.getEntity() instanceof LivingEntity killer)) {
            return false;
        }
        // グループ3 の捕食者に加えて、グループ4(拡大種)が食ったぶんも消す(2026-09-15)。
        // ステータスが上がるのはアルボラとスカトゥリウムだけだが、<b>食い散らかさない</b>のは
        // グループ4 全体の話 —— そうしないと群れが通ったあとが肉と骨だらけになる。
        if (!(killer instanceof PredatorMob) && MobGroup.of(killer.getType()) != MobGroup.GROUP_4) {
            return false;
        }
        MobGroup group = MobGroup.of(victim.getType());
        return group == MobGroup.GROUP_1 || group == MobGroup.GROUP_2 || group == MobGroup.GROUP_3;
    }

    private PredationEvents() {
    }
}
