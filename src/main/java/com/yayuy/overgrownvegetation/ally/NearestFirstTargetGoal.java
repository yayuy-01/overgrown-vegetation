package com.yayuy.overgrownvegetation.ally;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@link NearestAttackableTargetGoal} と同じ相手を選ぶ標的探し(2026-10-10、0.1.9 の前の軽量化)。
 *
 * <p>バニラは範囲の全員を確かめて(見えるかの視線の判定も全員ぶん)から、通った中でいちばん近い相手を選ぶ。
 * ここでは近い順に並べて確かめ、最初に通った相手で止める —— いちばん近い相手から見るので選ばれる相手は同じ
 * (同じ距離なら元の並びの先が勝つのもバニラと同じ。並べ替えは安定)。兵士は 48 ブロック先まで見るので、敵や汚染シダが多いと
 * 視線の判定が数十回になっていた。
 */
public final class NearestFirstTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {

    public NearestFirstTargetGoal(Mob mob, Class<T> targetType, int randomInterval, boolean mustSee, boolean mustReach,
                                  Predicate<LivingEntity> targetPredicate) {
        super(mob, targetType, randomInterval, mustSee, mustReach, targetPredicate);
    }

    @Override
    protected void findTarget() {
        if (this.targetType == Player.class || this.targetType == ServerPlayer.class) {
            super.findTarget();
            return;
        }
        List<T> candidates = this.mob.level().getEntitiesOfClass(this.targetType,
                this.getTargetSearchArea(this.getFollowDistance()), e -> true);
        double x = this.mob.getX();
        double y = this.mob.getEyeY();
        double z = this.mob.getZ();
        candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(x, y, z)));
        this.target = null;
        for (T candidate : candidates) {
            if (this.targetConditions.test(this.mob, candidate)) {
                this.target = candidate;
                return;
            }
        }
    }
}
