package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * 「過繁茂したスカトゥリウム」の<b>迫撃砲</b>(ユーザー要望 2026-09-15)。
 * 背中の胞子ポッドから、ヴェロフィルと同じ胞子爆弾({@link VelophyllBombEntity})を
 * <b>山なりに撃ち上げる</b>。
 *
 * <p>突進({@link ShielderChargeGoal})と<b>同じ役割の枠</b>——「引き離された相手への遠距離の手」——
 * を分け合っている。どちらを出すかは {@link OvergrownShielderEntity#shouldMortarNext()} が持つ札で
 * 交互に切り替わり、<b>見通しが無いときは必ず迫撃砲</b>になる(突進は壁越しには届かないが、
 * 弾は山なりに越えていく)。タンクが前線を張ったまま、遠い相手にも手が出せるようにするための分担。
 *
 * <h2>撃ち方</h2>
 * <ol>
 *     <li>その場で足を止め、相手を向く。</li>
 *     <li><b>斉射</b> {@code shielderMortarShots} 発を {@code shielderMortarShotIntervalTicks} おきに。
 *     出どころは<b>背中のポッドの高さ</b>で、左右に振り分ける。</li>
 *     <li>撃ち終えたら {@code shielderMortarCooldownTicks} 待つ。</li>
 * </ol>
 *
 * <h2>アニメーションと発射の噛み合わせ</h2>
 * {@code mortar} は<b>反動で沈み込む</b> 1.0 秒のループ(ユーザー提供の原案、2026-09-15)。
 * 0.125 秒で体が 2px 沈んで脚が縮み、残り 0.875 秒でゆっくり戻る。
 * <b>1 ループ = 1 発</b>になるよう、既定値を
 * <ul>
 *     <li>{@code shielderMortarShotIntervalTicks} = 20（= 1.0 秒。アニメーションの長さと同じ）</li>
 *     <li>{@code shielderMortarWindupTicks} = 3（沈み込みが底を打つ 2.5 tick に 1 発目を合わせる）</li>
 * </ul>
 * に揃えてある。この 2 つを動かすと<b>反動と発射が食い違い、弾の出ない空撃ちが見える</b>ので注意。
 * {@code shielderMortarWindupTicks} は<b>アニメの 0 秒目から</b>数えた値で、アニメが始まるまでの補間
 * （{@link OvergrownShielderEntity#TRANSITION_TICKS}）はコードで足す。2026-09-23 までは足しておらず、
 * 1 発目が沈み込む前（アニメの 0 秒目）に出ていた。
 *
 * <h2>初速の求め方</h2>
 * 弾の落ち方(重力 {@link VelophyllBombEntity#GRAVITY}・空気抵抗 {@link VelophyllBombEntity#DRAG})は
 * 毎 tick の掛け算なので、閉じた式で逆算すると抵抗のぶんだけ必ずずれる。そこで
 * <b>打ち上げ角を {@value #LAUNCH_PITCH} 度に固定し、初速を二分探索する</b> ——
 * 候補の初速で弾道を実際に 1 tick ずつ回し、相手の水平距離に達した時点の高さを見て、
 * 高すぎれば遅く・低すぎれば速くする。角度を固定すると「速いほど着弾点が高い」と単調になるので
 * 二分探索が必ず収束する。抵抗も段差も込みで合うのが利点。
 *
 * <p>狙いは<b>相手の今いる場所そのものではない</b> —— 飛翔時間ぶんだけ進んだ先を
 * {@value #LEAD_FRACTION} だけ見越して撃ち、そこへ {@code shielderMortarScatter} の散らばりを足す。
 * 完全に見越すと避けようがなく、まったく見越さないと歩いている相手に一生当たらない。
 */
public class ShielderMortarGoal extends Goal {

    /** 打ち上げ角(度)。山なりに越えさせたいので高めに固定する。 */
    private static final double LAUNCH_PITCH = 52.0D;
    /** 初速の二分探索の範囲と回数。 */
    private static final double MIN_LAUNCH_SPEED = 0.2D;
    private static final double MAX_LAUNCH_SPEED = 4.0D;
    private static final int SOLVE_STEPS = 18;
    /** 弾道を回す上限(tick)。これを超えたら「届かない」。 */
    private static final int MAX_FLIGHT_TICKS = 200;
    /** 相手の進行方向をどれだけ見越すか。 */
    private static final double LEAD_FRACTION = 0.6D;
    /** 弾が出る高さ(背中のポッド。足元からのブロック数)。 */
    private static final double POD_HEIGHT = 1.9D;
    /** 弾が出る位置の左右の振り分け(ブロック)。 */
    private static final double POD_SIDE = 0.45D;

    private final OvergrownShielderEntity mob;

    private int windupLeft;
    private int shotsLeft;
    private int shotTimer;
    @Nullable
    private LivingEntity quarry;

    public ShielderMortarGoal(OvergrownShielderEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.mob.isBusy() || !this.mob.canMortarNow()) {
            return false;
        }
        if (!acquireTarget()) {
            return false;
        }
        Vec3 aim = aimBase(0);
        double distance = Math.sqrt(this.mob.distanceToSqr(aim));
        if (distance < OvergrownVegetationConfig.COMMON.shielderMortarMinRange.get()
                || distance > OvergrownVegetationConfig.COMMON.shielderMortarMaxRange.get()) {
            forgetTarget();
            return false;
        }
        // 見通しが無いなら迫撃砲の出番(突進は壁を越えられない)。見えているなら札の順番に従う。
        if (prefersCharge()) {
            forgetTarget();
            return false;
        }
        // そもそも弾が届かない距離・高さなら撃たない(構えだけ取って無駄打ちしない)。
        // 渡すのは<b>発射点からの差分</b> —— ワールド座標をそのまま渡すと、原点から遠い場所では
        // 「数千ブロック先」を解こうとして必ず届かない判定になる(2026-09-21 に修正)。
        if (solve(aim.subtract(podOrigin(0))) == null) {
            forgetTarget();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isAlive()
                && targetStillValid()
                && this.mob.getActionState() == OvergrownShielderEntity.STATE_MORTAR;
    }

    // --- 狙う相手。ここだけ差し替えれば別のものを撃てる(ShielderTurretMortarGoal) ---

    /** 狙う相手を決める。見つからなければ false。既定は「いまの攻撃目標」。 */
    protected boolean acquireTarget() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        this.quarry = target;
        return true;
    }

    /** 構えを続けてよいか。 */
    protected boolean targetStillValid() {
        return this.quarry != null && this.quarry.isAlive();
    }

    /** 狙うのをやめる。 */
    protected void forgetTarget() {
        this.quarry = null;
    }

    /** 突進の方を優先するか(相手が見えていて、札が突進のとき)。 */
    protected boolean prefersCharge() {
        return this.quarry != null && this.mob.hasLineOfSight(this.quarry) && !this.mob.shouldMortarNext();
    }

    /** 撃っているあいだ向く先。 */
    protected void lookAtTarget() {
        if (this.quarry != null) {
            this.mob.getLookControl().setLookAt(this.quarry, 30.0F, 20.0F);
        }
    }

    /**
     * 狙点(ワールド座標)。{@code flightTicks} が分かっているときはその時間ぶん相手の進行方向を
     * 見越し、{@code shielderMortarScatter} の範囲で散らす。
     */
    protected Vec3 aimBase(int flightTicks) {
        return this.quarry != null ? aimPoint(this.quarry, flightTicks) : this.mob.position();
    }

    @Override
    public void start() {
        // 1 発目は mortar の沈み込みの底に合わせる。アニメは補間のぶん遅れて始まるので、その tick を足す。
        this.windupLeft = OvergrownShielderEntity.TRANSITION_TICKS
                + OvergrownVegetationConfig.COMMON.shielderMortarWindupTicks.get();
        this.shotsLeft = OvergrownVegetationConfig.COMMON.shielderMortarShots.get();
        this.shotTimer = 0;
        this.mob.setActionState(OvergrownShielderEntity.STATE_MORTAR);
        this.mob.getNavigation().stop();
        // 腹に力を込める唸り。ここから先は 1 秒ごとに沈み込んで 1 発ずつ撃つ。
        this.mob.playSound(SoundEvents.RAVAGER_ROAR, 1.0F, 1.4F);
        // 撃ったら次は突進。交互に出す。
        this.mob.setMortarNext(false);
    }

    @Override
    public void stop() {
        forgetTarget();
        if (this.mob.getActionState() == OvergrownShielderEntity.STATE_MORTAR) {
            this.mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        }
        this.mob.startMortarCooldown();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!targetStillValid()) {
            return;
        }
        lookAtTarget();
        // 撃っている間は足を止める(迫撃砲は据えて撃つもの)。
        this.mob.getNavigation().stop();

        // 1 発目までの数 tick。アニメーションの沈み込みに合わせるためだけの待ちで、構えではない。
        if (this.windupLeft > 0) {
            this.windupLeft--;
            return;
        }
        if (--this.shotTimer > 0) {
            return;
        }
        this.shotTimer = OvergrownVegetationConfig.COMMON.shielderMortarShotIntervalTicks.get();
        fireOne();
        if (--this.shotsLeft <= 0) {
            this.mob.setActionState(OvergrownShielderEntity.STATE_NONE);
        }
    }

    /** 1 発撃つ。届かない狙点になっていたらその発は捨てる(構えは崩さない)。 */
    private void fireOne() {
        int index = OvergrownVegetationConfig.COMMON.shielderMortarShots.get() - this.shotsLeft;
        Vec3 origin = podOrigin(index);
        // 1 度目は「いまの位置」で飛翔時間を測り、その時間ぶん見越した点へ撃ち直す。
        Vec3 rough = aimBase(0);
        Solution first = solve(rough.subtract(origin));
        if (first == null) {
            return;
        }
        Vec3 aim = aimBase(first.flightTicks());
        Solution shot = solve(aim.subtract(origin));
        if (shot == null) {
            shot = first;
        }
        this.mob.level().addFreshEntity(
                new VelophyllBombEntity(this.mob.level(), origin, shot.velocity(), this.mob));
        this.mob.level().playSound(null, origin.x, origin.y, origin.z, SoundEvents.SHULKER_SHOOT,
                SoundSource.HOSTILE, 1.4F, 0.5F);
    }

    /** 弾が出る位置(背中のポッド)。発ごとに左右へ振り分ける。 */
    private Vec3 podOrigin(int index) {
        double scale = this.mob.getScale();
        float yaw = this.mob.yBodyRot * Mth.DEG_TO_RAD;
        double side = ((index % 2 == 0) ? POD_SIDE : -POD_SIDE) * scale;
        // yaw 0 は +Z 向き。右手方向は (cos, 0, sin)。
        return new Vec3(this.mob.getX() + Mth.cos(yaw) * side,
                this.mob.getY() + POD_HEIGHT * scale,
                this.mob.getZ() + Mth.sin(yaw) * side);
    }

    /**
     * 狙う点。{@code flightTicks} が分かっているときはその時間ぶん相手の進行方向を見越し、
     * {@code shielderMortarScatter} の範囲で散らす。
     */
    private Vec3 aimPoint(LivingEntity target, int flightTicks) {
        Vec3 base = target.position();
        if (flightTicks > 0) {
            Vec3 velocity = target.position().subtract(target.xo, target.yo, target.zo);
            base = base.add(velocity.scale(flightTicks * LEAD_FRACTION));
        }
        double scatter = OvergrownVegetationConfig.COMMON.shielderMortarScatter.get();
        if (scatter > 0.0D) {
            base = base.add((this.mob.getRandom().nextDouble() * 2.0D - 1.0D) * scatter, 0.0D,
                    (this.mob.getRandom().nextDouble() * 2.0D - 1.0D) * scatter);
        }
        return base;
    }

    /** 初速と、その初速で狙点に届くまでの tick 数。 */
    private record Solution(Vec3 velocity, int flightTicks) {
    }

    /**
     * {@code delta}(発射点 → 狙点)へ届く初速を、打ち上げ角固定で二分探索する。
     * 届かなければ {@code null}。
     */
    @Nullable
    private Solution solve(Vec3 delta) {
        double horizontal = delta.horizontalDistance();
        if (horizontal < 1.0E-3D) {
            return null;
        }
        double dirX = delta.x / horizontal;
        double dirZ = delta.z / horizontal;
        double pitch = LAUNCH_PITCH * Mth.DEG_TO_RAD;
        double flat = Math.cos(pitch);
        double up = Math.sin(pitch);

        double low = MIN_LAUNCH_SPEED;
        double high = MAX_LAUNCH_SPEED;
        // 最大速度でも届かないなら、そもそも撃つ意味が無い。
        Flight reach = simulate(dirX * flat * high, up * high, dirZ * flat * high, horizontal);
        if (reach == null || reach.height() < delta.y) {
            return null;
        }
        Flight best = reach;
        for (int i = 0; i < SOLVE_STEPS; i++) {
            double mid = (low + high) * 0.5D;
            Flight flight = simulate(dirX * flat * mid, up * mid, dirZ * flat * mid, horizontal);
            if (flight == null || flight.height() < delta.y) {
                // 届かない / 低すぎる → もっと速く。
                low = mid;
            } else {
                high = mid;
                best = flight;
            }
        }
        double speed = high;
        return new Solution(new Vec3(dirX * flat * speed, up * speed, dirZ * flat * speed),
                best.ticks());
    }

    /** 弾道を回した結果 — 水平距離 {@code horizontal} に達したときの高さと、そこまでの tick 数。 */
    private record Flight(double height, int ticks) {
    }

    /**
     * 初速 {@code (vx, vy, vz)} の弾を、{@link VelophyllBombEntity} とまったく同じ式で回す。
     * 水平距離 {@code horizontal} に達した時点で打ち切る。達しなければ {@code null}。
     */
    @Nullable
    private static Flight simulate(double vx, double vy, double vz, double horizontal) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (int tick = 1; tick <= MAX_FLIGHT_TICKS; tick++) {
            vy = (vy - VelophyllBombEntity.GRAVITY) * VelophyllBombEntity.DRAG;
            vx *= VelophyllBombEntity.DRAG;
            vz *= VelophyllBombEntity.DRAG;
            x += vx;
            y += vy;
            z += vz;
            if (Math.sqrt(x * x + z * z) >= horizontal) {
                return new Flight(y, tick);
            }
        }
        return null;
    }
}
