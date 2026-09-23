package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;

/**
 * {@link FlyingMoveControl} の上下方向だけをなめらかにした移動コントロール。
 *
 * <h2>なぜ必要か</h2>
 * バニラの {@code FlyingMoveControl} は「目標より上か下か」だけを見て、上下の推力を毎 tick
 * <b>全開</b>で入れる({@code setYya(±speed)} —— 高度差が 0.01 でも 10 でも同じ強さ)。
 * ところが {@code LivingEntity#travel} の空気抵抗は上下方向だけ弱く(y は ×0.98、x/z は ×0.91)、
 * 上下の速度ばかりが積み上がる。結果、目標の高さを大きく行き過ぎ → 跨いだ瞬間に逆向きの全開推力 →
 * また行き過ぎ、を繰り返す。獲物を追いかけるときに体が上下へガクガク揺れるのはこれが原因。
 *
 * <h2>やっていること</h2>
 * 上下の推力を、「高度差から決めた目標上昇/下降速度」と「いまの上下速度」の<b>差</b>に比例させる。
 * 目標の高さに近づくほど、またすでに十分な速度が出ているほど推力が弱まるので行き過ぎない。
 * 水平方向はバニラのままなので、飛び方の性格は変わらない。
 */
public class SmoothFlyingMoveControl extends FlyingMoveControl {

    /** 高度差 1 ブロックあたり、どれだけの上昇/下降速度(ブロック/tick)を狙うか。 */
    private static final double CLIMB_GAIN = 0.2D;
    /** 上昇/下降速度の上限(ブロック/tick)。水平の終端速度と釣り合う程度に抑える。 */
    private static final double MAX_CLIMB_SPEED = 0.3D;
    /** 速度の誤差がこれ以上あれば上下の推力を全開にする(ブロック/tick)。 */
    private static final double FULL_THRUST_ERROR = 0.08D;

    public SmoothFlyingMoveControl(Mob mob, int maxTurn, boolean hoversInPlace) {
        super(mob, maxTurn, hoversInPlace);
    }

    @Override
    public void tick() {
        // super.tick() は先頭で operation を WAIT に落としてしまうので、動いていたかを先に控える。
        boolean moving = this.operation == Operation.MOVE_TO;
        super.tick();
        if (!moving) {
            return;
        }
        double dx = this.wantedX - this.mob.getX();
        double dy = this.wantedY - this.mob.getY();
        double dz = this.wantedZ - this.mob.getZ();
        if (dx * dx + dy * dy + dz * dz < 2.5000003E-7D) {
            // 目標にほぼ到達していて super が推力を切っている。触らない。
            return;
        }
        double wantedClimb = Mth.clamp(dy * CLIMB_GAIN, -MAX_CLIMB_SPEED, MAX_CLIMB_SPEED);
        double error = wantedClimb - this.mob.getDeltaMovement().y;
        double factor = Mth.clamp(error / FULL_THRUST_ERROR, -1.0D, 1.0D);
        // super が入れた ±全開の推力を、向きごと比例制御で置き換える
        // (getSpeed() は super が入れた「速度倍率 × FLYING_SPEED」)。
        this.mob.setYya((float) (this.mob.getSpeed() * factor));
    }
}
