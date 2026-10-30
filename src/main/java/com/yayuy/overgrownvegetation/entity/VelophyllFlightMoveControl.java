package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * ヴェロフィルの<b>飛行機のような</b>飛び方(ユーザー要望 2026-09-14:
 * 「その場でくるっと回らず大きく旋回するように」)。バニラのファントムの移動制御と同じ考え方で、
 * {@link net.minecraft.world.entity.ai.control.FlyingMoveControl} とは根本的に違う。
 *
 * <h2>なぜ差し替えたか</h2>
 * {@code FlyingMoveControl} は「目的地の方角へ毎 tick 最大 {@code maxTurn} 度まで向きを変え、
 * 向いた方へ推力を入れる」だけなので、<b>止まったまま向きだけ変える</b>ことができてしまう。
 * 経路探索の折り返し地点でその場コマのように回るのはこれが原因。
 *
 * <h2>やっていること</h2>
 * <ol>
 *     <li><b>向きは 1 tick に {@code velophyllTurnRate}(既定 2.2)度までしか曲げない</b> —
 *         これが旋回半径を決める。速度 v・角速度 ω[rad/tick] の弧の半径は v/ω なので、
 *         巡航 1.5・2.2 度/tick ならおよそ 20 ブロックの弧になる。</li>
 *     <li><b>速度は「まっすぐ飛べているか」で上下する</b> — ほぼ直進している tick は
 *         {@code velophyllCruiseSpeed} まで少しずつ加速し、きつく曲がっている tick は落ちる。
 *         旋回で失速して、立て直しながらまた加速する = 飛行機の挙動。</li>
 *     <li><b>速度は常に機首方向へ入れる</b> — 横滑りしない。目的地が真横にあっても、
 *         回り込むしかない。</li>
 * </ol>
 * 高さのずれは水平の寄り具合を弱めて機首の上下(ピッチ)に回すので、急降下も自然に弧を描く。
 *
 * <p>壁にぶつかったら向きを大きく変えて離れる(ファントムと同じ。経路探索をしていないので、
 * これが唯一の障害物対策)。
 *
 * <h2>群れの速さの指示(空の群れ、2026-09-25)</h2>
 * 空の群れ({@code swarm/}、ヴェロフィルだけの群れ)で隊形を保つための口が {@link #setFormationSpeed}。
 * この機体は<b>行き先までの距離では速さを変えない</b>(直進なら巡航まで上げるだけ)ので、先頭の横に並ぶ点を行き先にしても
 * 追い越すか置いて行かれるかのどちらかで、隊形にならない(レビュー指摘: 最低速度 0.6・旋回 3 度/tick で旋回半径は
 * 巡航でおよそ 25 ブロック、近い点を指すと輪を描いて回るだけ)。そこで後続は行き先を先頭の<b>ずっと先</b>に置いて並んで飛び
 * ({@link SwarmAirFollowGoal})、前後のずれはこの指示で<b>速さ</b>として直す。先頭は同じ口で自分の速さを巡航より少し落とし、
 * 遅れた後続が追いつけるようにする({@link VelophyllWanderGoal})。
 * 指示は {@code [MIN_SPEED, 巡航]} に丸め、期限({@code ttlTicks})が切れたら今までどおり巡航まで上げる
 * (指示を出した Goal が止まっても残り続けないように)。旋回中の失速はそのまま(物理は変えない)。
 */
public class VelophyllFlightMoveControl extends MoveControl {

    /** 加速の速さ(1 tick あたり)。 */
    private static final float ACCELERATION = 0.03F;
    /** 旋回中に失速していく速さ(1 tick あたり)。 */
    private static final float STALL = 0.018F;
    /** 失速したときの下限速度。 */
    private static final float MIN_SPEED = 0.6F;
    /**
     * 「ほぼ直進している」とみなす 1 tick の向きの変化を、旋回上限に対する割合で表したもの。
     *
     * <p><b>固定値にしてはいけない。</b> 最初 1.2 度の固定値にしていたが、旋回上限
     * ({@code velophyllTurnRate})が 2.2 度なので<b>舵を切っているあいだは必ず失速判定になり</b>、
     * しかも失速(0.035)が加速(0.012)の 3 倍速かったため、実質ずっと最低速度で飛んでいた
     * (2026-09-14「飛行速度をあげれますか」の原因)。上限に対する割合にしておけば、
     * <b>目一杯曲げているときだけ</b>失速して、細かい舵の修正では落ちない。
     */
    private static final float STRAIGHT_FRACTION = 0.8F;
    /** 速度を実際の移動量へ反映する割合(急に変えず滑らかに)。 */
    private static final double BLEND = 0.18D;

    /** 壁にぶつかったあと、離脱の向きを保つ tick 数。 */
    private static final int AVOID_TICKS = 25;
    /** 離脱するときに機首をずらす角度(度)。 */
    private static final float AVOID_TURN = 130.0F;
    /** 離脱の行き先をどれだけ先に置くか(ブロック)。 */
    private static final double AVOID_DISTANCE = 30.0D;
    /** 離脱の行き先をどれだけ上に置くか(ブロック)。木や崖は上へ抜けるのがいちばん早い。 */
    private static final double AVOID_CLIMB = 10.0D;

    private final VelophyllEntity velophyll;
    private float speed = MIN_SPEED;

    /** 離脱中の残り tick。 */
    private int avoidTicks;
    /** 離脱の向き(コンパス角、度)。ぶつかった瞬間に決めて、離脱の間は変えない。 */
    private float avoidHeading;

    /** 群れの速さの指示(直進中に目指す速さ)。NaN なら指示なし = 巡航まで上げる。 */
    private double formationSpeed = Double.NaN;
    /** 指示が切れる {@code tickCount}。 */
    private int formationUntil;

    public VelophyllFlightMoveControl(VelophyllEntity mob) {
        super(mob);
        this.velophyll = mob;
    }

    /**
     * 直進中に目指す速さを指示する(空の群れの隊形用。上の「群れの速さの指示」参照)。
     * {@code [MIN_SPEED, velophyllCruiseSpeed]} に丸めて使い、{@code ttlTicks} tick 後に切れる。
     * 単位はこのクラスの速さ({@link #currentSpeed} と同じ。実際の移動量はおよそその 0.55〜0.7 倍)。
     */
    public void setFormationSpeed(double speed, int ttlTicks) {
        this.formationSpeed = speed;
        this.formationUntil = this.velophyll.tickCount + Math.max(1, ttlTicks);
    }

    /** 速さの指示を取り消す(巡航まで上げる元の飛び方に戻る)。 */
    public void clearFormationSpeed() {
        this.formationSpeed = Double.NaN;
    }

    /** いまの速さ(指示・旋回の失速を反映した値。後続が先頭の速さを知るのに使う)。 */
    public float currentSpeed() {
        return this.speed;
    }

    /** 失速したときの下限速度(= 速さの指示の下限)。 */
    public static float minSpeed() {
        return MIN_SPEED;
    }

    /**
     * 壁にぶつかったあとの逃げ場所 —— 機首から {@value #AVOID_TURN} 度ずらした斜め後ろの、
     * {@value #AVOID_CLIMB} ブロック上。
     *
     * <p>向きを<b>その場で書き換えない</b>のが肝。以前は {@code setYRot(+140)} で機首を
     * 瞬間的にひねっていたが、木の枝を擦るたびにこれが起きるので<b>見た目がガクガク跳ねていた</b>
     * (2026-09-14 のユーザー報告)。行き先を置き換えるだけにしておけば、
     * 実際の曲がり方は通常どおり {@code velophyllTurnRate} の制限を通るので跳ねない。
     * ぶつかった瞬間に向きを決めて {@value #AVOID_TICKS} tick 保つので、擦り続けても向きは暴れない。
     */
    private Vec3 escapeTarget() {
        float heading = this.avoidHeading * Mth.DEG_TO_RAD;
        return new Vec3(
                this.velophyll.getX() + Mth.cos(heading) * AVOID_DISTANCE,
                this.velophyll.getY() + AVOID_CLIMB,
                this.velophyll.getZ() + Mth.sin(heading) * AVOID_DISTANCE);
    }

    @Override
    public void tick() {
        Vec3 destination = this.velophyll.getMoveTargetPoint();
        if (destination == null) {
            return;
        }
        // 壁にぶつかったら、しばらく「斜め後ろ上方」を目指して離脱する。
        if (this.velophyll.horizontalCollision && this.avoidTicks <= 0) {
            this.avoidTicks = AVOID_TICKS;
            this.avoidHeading = this.velophyll.getYRot() + 90.0F
                    + (this.velophyll.getRandom().nextBoolean() ? AVOID_TURN : -AVOID_TURN);
            this.speed = MIN_SPEED;
        }
        if (this.avoidTicks > 0) {
            this.avoidTicks--;
            destination = escapeTarget();
        }

        double dx = destination.x - this.velophyll.getX();
        double dy = destination.y - this.velophyll.getY();
        double dz = destination.z - this.velophyll.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-5D) {
            return;
        }

        // 高さのずれが大きいほど水平の寄りを弱める = そのぶんピッチに回る。
        double lean = Math.max(0.15D, 1.0D - Math.abs(dy * 0.7D) / horizontal);
        dx *= lean;
        dz *= lean;
        horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(dx * dx + dz * dz + dy * dy);

        float turnRate = OvergrownVegetationConfig.COMMON.velophyllTurnRate.get().floatValue();
        float cruiseSpeed = OvergrownVegetationConfig.COMMON.velophyllCruiseSpeed.get().floatValue();

        float previousYRot = this.velophyll.getYRot();
        float wantedYRot = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG);
        // yRot は「+Z が 0 度」なので、atan2(dz, dx) 系の角度とは 90 度ずれている。
        float currentCompass = Mth.wrapDegrees(this.velophyll.getYRot() + 90.0F);
        this.velophyll.setYRot(Mth.approachDegrees(currentCompass, Mth.wrapDegrees(wantedYRot), turnRate) - 90.0F);
        this.velophyll.yBodyRot = this.velophyll.getYRot();

        if (Mth.degreesDifferenceAbs(previousYRot, this.velophyll.getYRot()) < turnRate * STRAIGHT_FRACTION) {
            // 群れの速さの指示があれば、巡航ではなくその速さへ寄せる(下げるときは失速と同じ速さで落とす)。
            float wanted = cruiseSpeed;
            if (!Double.isNaN(this.formationSpeed)) {
                if (this.velophyll.tickCount < this.formationUntil) {
                    wanted = (float) Math.min(Math.max(this.formationSpeed, MIN_SPEED), cruiseSpeed);
                } else {
                    this.formationSpeed = Double.NaN;
                }
            }
            this.speed = Mth.approach(this.speed, wanted, this.speed < wanted ? ACCELERATION : STALL);
        } else {
            this.speed = Mth.approach(this.speed, MIN_SPEED, STALL);
        }

        float pitch = (float) -(Mth.atan2(-dy, horizontal) * Mth.RAD_TO_DEG);
        this.velophyll.setXRot(pitch);

        // 速度は必ず機首方向へ。横滑りはしない。
        float heading = (this.velophyll.getYRot() + 90.0F) * Mth.DEG_TO_RAD;
        double vx = this.speed * Mth.cos(heading) * Math.abs(dx / distance);
        double vz = this.speed * Mth.sin(heading) * Math.abs(dz / distance);
        double vy = this.speed * Mth.sin(pitch * Mth.DEG_TO_RAD) * Math.abs(dy / distance);
        Vec3 current = this.velophyll.getDeltaMovement();
        this.velophyll.setDeltaMovement(
                current.add(new Vec3(vx, vy, vz).subtract(current).scale(BLEND)));
    }
}
