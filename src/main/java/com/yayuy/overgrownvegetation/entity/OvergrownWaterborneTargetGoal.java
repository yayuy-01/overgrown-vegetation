package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 水生の捕食者({@link AbstractOvergrownAquaticPredatorEntity})が「水中・水面にいる相手」を狙う索敵 Goal。
 *
 * <p>{@link OvergrownHunterTargetGoal}({@link Predation#isWaterborneTarget})に加えて、
 * <b>追跡中の相手が水から出た瞬間に見失う</b>。これにより「海岸・川辺の陸上に逃げた相手は
 * それ以上追わない」(ユーザー要望 2026-09-08)を満たす。捕食対象({@link Predation#isPredatorOf})に
 * 登録されている相手は水陸問わず追い続ける(そちらは別 Goal だが、こちらでも継続を許可する)。
 */
public class OvergrownWaterborneTargetGoal extends OvergrownHunterTargetGoal {

    public OvergrownWaterborneTargetGoal(Mob mob) {
        super(mob, Predation::isWaterborneTarget);
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()) {
            return false;
        }
        LivingEntity current = this.mob.getTarget();
        if (current == null) {
            return true;
        }
        return Predation.isPredatorOf(this.mob, current)
                || AquaticOvergrownMob.inWaterOrAtSurface(current);
    }
}
