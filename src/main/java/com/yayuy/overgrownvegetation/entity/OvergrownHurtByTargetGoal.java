package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * {@link HurtByTargetGoal} の「視線を無視する」版。過繁茂 Mob 用。
 *
 * <p>バニラの {@link HurtByTargetGoal} は {@code mustSee=true} なので、殴ってきた相手の視線(LOS)が
 * 60 tick(3 秒)通らないと反撃対象を捨てる。過繁茂バイオームは葉や地形で視線がすぐ切れるため、
 * 「殴られたのに反撃せず歩き去る」が起きる。{@code unseenMemoryTicks} を実質無限にして、
 * {@code FOLLOW_RANGE} 内にいる限りは視線が切れても反撃対象を保持し続ける
 * (実際の近接ヒットには依然 {@code MeleeAttackGoal} 側で視線・間合いが要る)。
 */
public class OvergrownHurtByTargetGoal extends HurtByTargetGoal {

    public OvergrownHurtByTargetGoal(PathfinderMob mob, Class<?>... toIgnoreDamage) {
        super(mob, toIgnoreDamage);
        this.setUnseenMemoryTicks(Integer.MAX_VALUE);
    }
}
