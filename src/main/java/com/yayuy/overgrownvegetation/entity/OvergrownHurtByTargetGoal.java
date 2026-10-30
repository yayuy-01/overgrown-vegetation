package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
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
 *
 * <p><b>群れの一員では動かない</b>(群れ v2、2026-09-25)。殴られた一員の反撃は司令塔が決める
 * ({@link SwarmManager#onMemberHurt}: 役割の距離にいればその一員だけ 100 tick 反撃、群れの標的を替えるかは優先順で)。
 * ここで反撃すると、司令塔が渡した標的を毎回奪い合う。
 */
public class OvergrownHurtByTargetGoal extends HurtByTargetGoal {

    public OvergrownHurtByTargetGoal(PathfinderMob mob, Class<?>... toIgnoreDamage) {
        super(mob, toIgnoreDamage);
        this.setUnseenMemoryTicks(Integer.MAX_VALUE);
    }

    @Override
    public boolean canUse() {
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return super.canUse();
    }

    /** 群れに入った個体では止める(覚えている相手を {@code TargetGoal#canContinueToUse} が付け直さないように)。 */
    @Override
    public boolean canContinueToUse() {
        if (SwarmMember.isInSwarm(this.mob)) {
            return false;
        }
        return super.canContinueToUse();
    }

    /** 群れの一員では標的を消さない(司令塔が渡した標的を残す)。 */
    @Override
    public void stop() {
        if (SwarmMember.isInSwarm(this.mob)) {
            this.targetMob = null;
            return;
        }
        super.stop();
    }
}
