package com.yayuy.overgrownvegetation.g1;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * 蔦で掴んで引き寄せる(蔦腕のゾンビ・蔦の処刑者・根の打ち手。G1 の作り直し 第 1 段)。
 * 殴れる距離より遠く {@code range} 以内で見えている標的を、自分の方へ {@code pull} の速さで引く。
 */
public final class VineWhip {

    /** 殴れる距離より近ければ引かない。 */
    private static final double MIN_DISTANCE = 2.5D;

    /** 今 引ける相手か(見えていて、殴れる距離より遠く range 以内)。蔦の処刑者は蔦を伸ばし始める前にこれで決める(10-23)。 */
    public static boolean canPull(Mob mob, LivingEntity target, double range) {
        if (target == null || !target.isAlive() || !mob.hasLineOfSight(target)) {
            return false;
        }
        double dist = mob.distanceTo(target);
        return dist >= MIN_DISTANCE && dist <= range;
    }

    /** 引けたら true。 */
    public static boolean tryPull(Mob mob, LivingEntity target, double range, double pull) {
        if (!canPull(mob, target, range)) {
            return false;
        }
        Vec3 dir = mob.position().subtract(target.position()).normalize();
        target.setDeltaMovement(target.getDeltaMovement().scale(0.2D).add(dir.x * pull, 0.25D + 0.1D * pull, dir.z * pull));
        target.hasImpulse = true;
        if (target instanceof ServerPlayer player) {
            player.hurtMarked = true; // 速さを手元へ送る
        }
        mob.swing(InteractionHand.MAIN_HAND);
        mob.playSound(SoundEvents.WEEPING_VINES_BREAK, 1.0F, 0.6F);
        return true;
    }

    private VineWhip() {
    }
}
