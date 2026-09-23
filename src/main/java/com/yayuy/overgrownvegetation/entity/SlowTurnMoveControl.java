package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * <b>進行方向を急に変えない</b>移動制御(ユーザー要望 2026-09-14)。
 *
 * <p>バニラの {@link MoveControl} は経路の次の点へ向けて <b>1 tick に 90 度</b>まで回る
 * ({@code MAX_TURN})。つまり 2 tick(0.1 秒)で真後ろを向ける。小さい Mob では気にならないが、
 * 大きなモデルでは「その場で高速に回る」ようにしか見えない。
 *
 * <p>{@link SlowBodyRotationControl} が<b>立ち止まっているとき</b>の回転を抑えるのに対して、
 * こちらは<b>歩いているとき</b>の回転を抑える。上限を掛けるだけなので経路そのものは変わらず、
 * 曲がりきるまでのわずかな間だけ外側へ膨らんで歩く。
 */
public class SlowTurnMoveControl extends MoveControl {

    /** 1 tick に変えられる進行方向(度)。バニラは 90。 */
    private final float maxTurn;

    public SlowTurnMoveControl(Mob mob, float maxTurnDegrees) {
        super(mob);
        this.maxTurn = maxTurnDegrees;
    }

    @Override
    public void tick() {
        float before = this.mob.getYRot();
        super.tick();
        float delta = Mth.degreesDifference(before, this.mob.getYRot());
        this.mob.setYRot(before + Mth.clamp(delta, -this.maxTurn, this.maxTurn));
    }
}
