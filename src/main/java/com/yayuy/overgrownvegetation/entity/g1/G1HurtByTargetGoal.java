package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * 殴ってきた相手へ向き直る(バニラの {@link HurtByTargetGoal})。ただし G1〜G10 どうしでは仕返ししない(流れ弾・巻き込みで同士討ちを始めない)。
 */
public class G1HurtByTargetGoal extends HurtByTargetGoal {

    public G1HurtByTargetGoal(AbstractParasitizedMonster mob) {
        super(mob);
    }

    @Override
    public boolean canUse() {
        LivingEntity attacker = this.mob.getLastHurtByMob();
        return attacker != null && G1TargetGoal.isPrey(this.mob, attacker) && super.canUse();
    }
}
