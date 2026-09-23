package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;

/**
 * <b>その場で体をぐるぐる回さない</b>ための体幹制御(ユーザー要望 2026-09-14)。
 *
 * <p>バニラの {@link BodyRotationControl} は、立ち止まっている Mob の体を<b>頭の向きへ 10 tick で
 * 全部持っていく</b>({@code rotateHeadTowardsFront})。首だけの小さな Mob ならそれでいいが、
 * フィロセンスのように背の高い大きなモデルだと、{@code RandomLookAroundGoal} が 1〜2 秒おきに
 * <b>まったくの別方向</b>を見るたびに、体ごと独楽のように回ってしまう。
 *
 * <p>ここでは中身はバニラのままにして、<b>1 tick に回れる角度だけ上限を掛ける</b>。
 * 歩いている間は素通し —— 歩行中の {@code yBodyRot} は進行方向({@code yRot})そのもので、
 * ここで遅らせると<b>体が横を向いたまま歩く</b>ことになる。進行方向の回転を抑えるのは
 * {@link SlowTurnMoveControl} の役目。
 *
 * <p>立ち止まっている間は {@code yRot}(＝体の正面)も一緒に連れて行く。そうしないと、
 * ゆっくり向き直ったあと歩き出した瞬間に、バニラが {@code yBodyRot} を古い {@code yRot} へ
 * 引き戻して<b>体だけ跳ねる</b>。
 */
public class SlowBodyRotationControl extends BodyRotationControl {

    private final Mob mob;
    /** 1 tick に回れる角度(度)。 */
    private final float maxStep;

    public SlowBodyRotationControl(Mob mob, float maxStepDegrees) {
        super(mob);
        this.mob = mob;
        this.maxStep = maxStepDegrees;
    }

    @Override
    public void clientTick() {
        if (isMoving()) {
            super.clientTick();
            return;
        }
        float before = this.mob.yBodyRot;
        super.clientTick();
        float delta = Mth.degreesDifference(before, this.mob.yBodyRot);
        float yaw = before + Mth.clamp(delta, -this.maxStep, this.maxStep);
        this.mob.yBodyRot = yaw;
        this.mob.setYRot(yaw);
    }

    /** バニラ {@code BodyRotationControl#isMoving} と同じ判定(水平に動いたか)。 */
    private boolean isMoving() {
        double dx = this.mob.getX() - this.mob.xo;
        double dz = this.mob.getZ() - this.mob.zo;
        return dx * dx + dz * dz > 2.5000003E-7D;
    }
}
