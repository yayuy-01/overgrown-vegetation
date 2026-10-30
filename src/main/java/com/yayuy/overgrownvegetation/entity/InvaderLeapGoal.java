package com.yayuy.overgrownvegetation.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * グループ4(拡大種)が、追跡中の相手へ<b>飛びついて</b>距離を詰める Goal。
 * アルボラの「ちょくちょく標的に対して飛びつくような行動をとる」ぶん。
 *
 * <p>{@link PredatorPounceGoal}(グループ3の捕食者版)と同じ形だが、こちらは
 * {@link AbstractOvergrownInvaderEntity} 用で、飛びかかっている間の状態が
 * {@link AbstractOvergrownInvaderEntity#STATE_LEAP}(= {@code leap} アニメーション)になる。
 *
 * <p>接地していて相手と {@link #MIN}〜{@link #maxRange} ブロック離れ、視線が通っているとき、
 * {@code cooldownTicks} tick ごとに 1 回だけ跳ぶ。空中で相手に触れたらその場で 1 回殴って終了。
 *
 * <h2>届く範囲は跳ぶ勢いから逆算する</h2>
 * <p>索敵する距離({@link #maxRange})と、相手が上にいてよい高さ({@link #maxRise})は
 * 定数ではなく、コンストラクタで受け取った {@code horizontalSpeed} / {@code lift} から
 * <b>バニラの空中の物理をそのまま回して</b>求める({@link LeapArc#reach})。
 * こうしておくと「飛距離を伸ばしたい」ときに勢いの 2 つの数字を変えるだけで済み、
 * 索敵範囲だけ広がって届かない・跳べる高さの上限だけ古いまま、といったズレが起きない
 * (前者は 2026-09-14 に実際に起きた:届かない高さの相手へ跳び続けて壁に体当たりしていた)。
 *
 * <p><b>ただし相手が突進に合わせて正面で盾を構え直していれば弾かれる</b>({@link ShieldParry}) —
 * 構えてから 0.5 秒以内に当たったときだけで、構えっぱなしでは弾けない。弾かれるとダメージは通らず、
 * 自分の勢いのまま後ろへ跳ね返され、次の飛びつきまでの待ちが
 * {@link #PARRIED_COOLDOWN_MULTIPLIER} 倍に延びる。
 */
public class InvaderLeapGoal extends Goal {

    /** これより近い相手には跳ばない(普通に殴れる距離)。 */
    private static final double MIN = 3.0D;
    /** 相手が下にいるときの上限(落ちるぶんには跳べる)。 */
    private static final double MAX_DOWN = 3.5D;
    private static final int MAX_TICKS = 24;

    /**
     * 跳び上がれる高さのうち、実際に「上にいる相手へ跳んでよい」とみなす割合。
     * 最高到達点ぎりぎりの相手には届かない(横に進むあいだに落ち始める)ので少し余裕を取る。
     */
    private static final double RISE_MARGIN = 0.9D;

    /** 弾かれたあとは次の飛びつきまでの待ちをこの倍率だけ延ばす(隙を作る)。 */
    private static final double PARRIED_COOLDOWN_MULTIPLIER = 1.8D;

    private final AbstractOvergrownInvaderEntity mob;
    private final double horizontalSpeed;
    private final double lift;
    private final int cooldownTicks;

    /** この勢いで跳んだときに届く水平距離(ブロック)。索敵の上限になる。 */
    private final double maxRange;
    /** この勢いで跳んだときの最高到達点(ブロック)。上にいる相手を狙ってよい高さの元になる。 */
    private final double maxRise;

    private int ticks;
    private int nextTime;
    private LivingEntity victim;
    private boolean dealt;
    private boolean parried;

    public InvaderLeapGoal(AbstractOvergrownInvaderEntity mob, double horizontalSpeed, double lift, int cooldownTicks) {
        this.mob = mob;
        this.horizontalSpeed = horizontalSpeed;
        this.lift = lift;
        this.cooldownTicks = cooldownTicks;
        double[] reach = LeapArc.reach(horizontalSpeed, lift, MAX_TICKS);
        this.maxRange = reach[0];
        this.maxRise = reach[1];
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    /** この勢いで届く水平距離(ブロック)。 */
    public double maxRange() {
        return this.maxRange;
    }

    @Override
    public boolean canUse() {
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        if (this.mob.tickCount < this.nextTime || !this.mob.onGround() || this.mob.isInWater()) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // 跳び上がれる高さより上にいる相手へ跳んでも届かず、壁に体当たりして跳び直し続けてしまう。
        double dy = target.getY() - this.mob.getY();
        if (dy > this.maxRise * RISE_MARGIN || dy < -MAX_DOWN) {
            return false;
        }
        double dist = this.mob.distanceTo(target);
        // 視線は Sensing 経由(2026-09-24 軽量化、報告 1692)。同じ tick の中で同じ相手への答えを覚えているだけで、
        // 中身は同じ hasLineOfSight(毎 tick の AI の頭で忘れる)。索敵の Goal が同じ tick にもう聞いていれば、
        // 壁越しの相手へ 2 tick ごとにレイキャストし直さずに済む。
        return dist >= MIN && dist <= this.maxRange && this.mob.getSensing().hasLineOfSight(target);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.victim == null || this.dealt || this.ticks > MAX_TICKS) {
            return false;
        }
        // 跳んだ直後の数 tick は接地判定が残るので終了しない。
        return this.ticks <= 3 || !this.mob.onGround();
    }

    @Override
    public void start() {
        this.victim = this.mob.getTarget();
        this.ticks = 0;
        this.dealt = false;
        this.parried = false;
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_LEAP);

        double dx = this.victim.getX() - this.mob.getX();
        double dz = this.victim.getZ() - this.mob.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 dir = len > 1.0E-4D ? new Vec3(dx / len, 0.0D, dz / len) : this.mob.getLookAngle();
        this.mob.setDeltaMovement(dir.x * this.horizontalSpeed, this.lift, dir.z * this.horizontalSpeed);
        this.mob.hasImpulse = true;

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        this.mob.setYRot(yaw);
        this.mob.yBodyRot = yaw;
    }

    @Override
    public void stop() {
        this.victim = null;
        int cooldown = this.parried
                ? (int) (this.cooldownTicks * PARRIED_COOLDOWN_MULTIPLIER)
                : this.cooldownTicks;
        this.nextTime = this.mob.tickCount + cooldown;
        if (this.mob.getActionState() == AbstractOvergrownInvaderEntity.STATE_LEAP) {
            this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_IDLE);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.ticks++;
        if (this.victim == null || !this.victim.isAlive()) {
            return;
        }
        this.mob.getLookControl().setLookAt(this.victim, 30.0F, 30.0F);
        if (!this.mob.getBoundingBox().inflate(0.3D).intersects(this.victim.getBoundingBox())) {
            return;
        }
        // 正面で盾を構えていれば、ダメージは通らず勢いごと弾き返される。
        if (ShieldParry.canParry(this.victim, this.mob)) {
            ShieldParry.applyParry(this.mob, this.victim);
            this.parried = true;
            this.dealt = true;
            return;
        }
        this.mob.doHurtTarget(this.victim);
        this.dealt = true;
    }
}
