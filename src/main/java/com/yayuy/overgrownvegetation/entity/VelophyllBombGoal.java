package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * ヴェロフィルの<b>爆撃</b>。爆弾を落とせるかどうかで動きが 2 つに分かれる(ユーザー指定)。
 *
 * <ul>
 *     <li><b>落とせるとき</b> — 急降下爆撃の要領で標的へ寄り、<b>真上
 *     {@code velophyllDropAltitude}(既定 8)ブロック</b>まで降りてから
 *     {@code velophyllBombsPerSalvo} 発を<b>1 発ずつ順に</b>落とす。まとめては落とさない。</li>
 *     <li><b>落とせないとき</b>(撃ち切ってからのクールタイム
 *     {@code velophyllBombCooldownTicks}、既定 20 秒)— <b>標的の上空を旋回して待つ</b>。</li>
 * </ul>
 *
 * <h2>森の上では急降下しない(2026-09-14)</h2>
 * ユーザー報告「森だと木につっかえる」への対処。投下高度を
 * <b>「標的の 8 ブロック上」と「木の天蓋の {@code velophyllCanopyClearance} ブロック上」の高いほう</b>
 * にしてある({@link #computeReleaseY})。<b>モードの切り替えは無く、式が 1 本あるだけ</b>で、
 * 開けた土地では前者が勝って急降下爆撃、森の上では後者が勝って水平爆撃になる。
 *
 * <p>爆弾を離すのは標的が見えているときが基本だが、<b>見えないまま
 * {@value #BLIND_DROP_DELAY} tick 接近を続けたら、見えなくても落とす</b>。
 * 森の中の相手は上から見えないので、視線を必須にすると一発も落とさなくなるため。
 *
 * <h2>照準(2026-09-14)</h2>
 * 飛行速度を上げたぶん、<b>標的の真上で離したのでは 20 ブロック以上先へ飛んでしまう</b>。
 * 落下時間ぶんの前進を見越して<b>手前で離す</b>({@link #releaseDistance})。
 *
 * <p>地上で胞子をばらまいている最中に標的が現れた場合は、この Goal の {@code start()} が
 * 飛び立たせる({@link VelophyllEntity#beginTakeoff})。
 */
public class VelophyllBombGoal extends Goal {

    /** 投下点(照準が合う距離)の許容幅(ブロック)。 */
    private static final double DROP_WINDOW_HORIZONTAL = 8.0D;
    /** 機首が標的を向いているとみなす内積の下限(0.86 ≒ 左右 30 度以内)。 */
    private static final double AIM_COSINE = 0.86D;
    /** 落下中の空気抵抗で、水平移動が縮む割合のおおよその値。 */
    private static final double DRAG_OVER_FALL = 0.96D;
    /** 投下態勢に入る高さのずれ(ブロック)。 */
    private static final double DROP_WINDOW_VERTICAL = 7.0D;
    /** 行き先を置き直す間隔(tick)。標的は動くので追従させる。 */
    private static final int REPATH_INTERVAL = 10;
    /**
     * 旋回するとき、いまの位置から円周上の<b>どれだけ先</b>に行き先を置くか(度)。
     *
     * <p>「いま真横にある点」を指すと機首がそちらを向いて円の内側へ切り込んでしまう。
     * 進行方向の先を指し続けることで円周に沿って回る。
     */
    private static final double CIRCLE_LEAD_DEGREES = 55.0D;
    /** 投下点よりさらに手前で投下高度へ降りきっておく余裕(ブロック)。 */
    private static final double LEVEL_OFF_MARGIN = 12.0D;
    /** 爆弾を離す横位置(体の中心から左右へ、ブロック)。左右の爆弾架から交互に落とす。 */
    private static final double BAY_OFFSET = 0.55D;
    /** 投下高度を測り直す間隔(tick)。毎 tick 地形を走査する必要はない。 */
    private static final int ALTITUDE_REFRESH = 20;
    /** 天蓋の高さを測るときの間引き(ブロック)。全マス見る必要はない。 */
    private static final int CANOPY_STEP = 3;
    /**
     * 標的が見えないまま投下態勢に入っていられる上限(tick)。これを過ぎたら<b>見えなくても落とす</b>。
     *
     * <p>森の中の相手は上から見えないので、視線を必須にすると<b>延々と接近をやり直すだけで
     * 一発も落とさない</b>。天蓋の上で爆ぜても爆風(半径 6)は下へ届くし、木ごと汚染する。
     * 数えているのは<b>接近中に見えていない時間</b>で、見えた瞬間に 0 へ戻る。
     */
    private static final int BLIND_DROP_DELAY = 40;

    private final VelophyllEntity mob;

    /** いまの投下高度(絶対 Y)。{@link #ALTITUDE_REFRESH} tick ごとに測り直す。 */
    private double releaseY;
    /** 次に投下高度を測り直すまでの残り tick。 */
    private int altitudeTimer;
    /** 接近中、標的が見えていない状態が続いている tick。見えた瞬間に 0 へ戻る。 */
    private int blindTicks;

    /** 旋回の向き(+1 / -1)。取り付いたときに決めて、その爆撃の間は変えない。 */
    private int circleDirection = 1;
    /** 次に行き先を置き直す tick。 */
    private int repathTimer;
    /** 投下態勢に入っているか。 */
    private boolean dropping;
    /** この爆撃で落とした数。 */
    private int dropped;
    /** 次の 1 発までの残り tick。 */
    private int dropTimer;

    public VelophyllBombGoal(VelophyllEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // 地下の相手・空中の相手など「爆弾が届かない標的」は居ないものとして扱う
        // (hasAttackTarget の説明を参照)。空中の相手は VelophyllBiteGoal が受け持つ。
        return this.mob.hasBombTarget() && !this.mob.isInWater();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.circleDirection = this.mob.getRandom().nextBoolean() ? 1 : -1;
        this.repathTimer = 0;
        this.dropping = false;
        this.dropped = 0;
        this.dropTimer = 0;
        this.altitudeTimer = 0;
        this.blindTicks = 0;
        this.releaseY = this.mob.getY();
        // 地面にいたなら飛び立つところから。
        if (this.mob.isGrounded()) {
            this.mob.setGrounded(false);
            this.mob.beginTakeoff();
        }
    }

    @Override
    public void stop() {
        this.dropping = false;
        this.dropped = 0;
        this.mob.setMoveTargetPoint(null);
        if (this.mob.getActionState() == VelophyllEntity.STATE_DIVE) {
            this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        }
    }

    @Override
    public void tick() {
        if (!hasAim()) {
            return;
        }
        Vec3 target = aimPos();
        lookAtAim();
        // 飛び立つモーションの間は動かさない(状態は本体が戻す)。
        if (this.mob.getActionState() == VelophyllEntity.STATE_TAKEOFF) {
            return;
        }

        if (this.dropping) {
            tickDropRun(target);
        } else if (this.mob.canBombNow()) {
            tickApproach(target);
        } else {
            tickCircle(target);
        }
    }

    /**
     * 爆弾を落とせないあいだ、標的の上空を巡航高度で回り続ける。
     *
     * <p>行き先は「いま自分がいる方角」ではなく、そこから {@value #CIRCLE_LEAD_DEGREES} 度
     * <b>先</b>の円周上に置く。真横の点を指すと機首がそちらを向いて円の内側へ切り込んでしまうため。
     */
    // --- 狙い(2026-09-19 に抽象化) ---
    // 既定の狙いは「いまの標的(LivingEntity)」。忌避灯を爆撃する VelophyllLampBombGoal はブロック座標で上書きする。
    // 以下の 4 つ以外は標的が生き物かブロックかを知らない。

    /** 狙いがあるか(無ければ tick は何もしない)。 */
    protected boolean hasAim() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }

    /** 狙いの座標。 */
    protected Vec3 aimPos() {
        LivingEntity target = this.mob.getTarget();
        return target != null ? target.position() : this.mob.position();
    }

    /** 狙いが見えているか(見えないまま {@link #BLIND_DROP_DELAY} tick 続いたら見えなくても落とす)。 */
    protected boolean aimVisible() {
        LivingEntity target = this.mob.getTarget();
        return target != null && this.mob.getSensing().hasLineOfSight(target);
    }

    /** 狙いの方を向く。 */
    protected void lookAtAim() {
        LivingEntity target = this.mob.getTarget();
        if (target != null) {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private void tickCircle(Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        refreshReleaseY(target);
        if (--this.repathTimer > 0) {
            return;
        }
        this.repathTimer = REPATH_INTERVAL;
        double radius = cfg.velophyllCircleRadius.get();
        double here = Math.atan2(this.mob.getZ() - target.z, this.mob.getX() - target.x);
        double ahead = here + this.circleDirection * Math.toRadians(CIRCLE_LEAD_DEGREES);
        double x = target.x + Math.cos(ahead) * radius;
        double z = target.z + Math.sin(ahead) * radius;
        // 旋回高度も天蓋より上に取る(森の上で巡航高度が枝の中に入らないように)。
        double y = Math.max(target.y + cfg.velophyllCruiseAltitude.get(), this.releaseY + 6.0D);
        this.mob.setMoveTargetPoint(new Vec3(x, y, z));
    }

    /**
     * 投下高度({@link #releaseY})へ寄る。<b>開けた土地なら急降下爆撃、森の上なら水平爆撃</b>に
     * 自然と変わる —— 高度の決め方 {@link #computeReleaseY} を参照。
     */
    private void tickApproach(Vec3 target) {
        refreshReleaseY(target);
        // 実際にはっきり降りているあいだだけ急降下の姿勢にする。
        // 森の上では投下高度まで降りる必要がないので、そのまま水平に飛んで通過する。
        this.mob.setActionState(this.mob.getY() > this.releaseY + 4.0D
                ? VelophyllEntity.STATE_DIVE
                : VelophyllEntity.STATE_GLIDE);

        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.mob.setMoveTargetPoint(approachAim(target));
        }

        // 森の中の相手は上から見えない。見えない時間が続いたら視線を条件から外す。
        if (aimVisible()) {
            this.blindTicks = 0;
        } else {
            this.blindTicks++;
        }
        boolean mayDrop = this.blindTicks == 0 || this.blindTicks >= BLIND_DROP_DELAY;

        if (mayDrop
                && Math.abs(this.mob.getY() - this.releaseY) <= DROP_WINDOW_VERTICAL
                && atReleasePoint(target)) {
            this.blindTicks = 0;
            this.dropping = true;
            this.dropped = 0;
            this.dropTimer = 0;
        }
    }

    /**
     * 爆撃進入の行き先。
     *
     * <p><b>投下点より手前で投下高度まで降りきっておく</b>のが肝。標的そのものを指すと、
     * 降下と接近が同時に終わる = <b>投下高度に着くのが標的の真上になり、投下点
     * ({@link #releaseDistance} ブロック手前)を高いまま素通りしてしまう</b>。
     * 投下点よりさらに {@value #LEVEL_OFF_MARGIN} ブロック手前の、投下高度の点を指すことで、
     * そこから先は水平飛行のまま照準に入れる。
     */
    private Vec3 approachAim(Vec3 target) {
        double toX = target.x - this.mob.getX();
        double toZ = target.z - this.mob.getZ();
        double distance = Math.hypot(toX, toZ);
        double runIn = releaseDistance(target) + LEVEL_OFF_MARGIN;
        if (distance <= runIn || distance < 1.0E-4D) {
            // もう水平区間。標的の上を通過する形で抜ける。
            return new Vec3(target.x, this.releaseY, target.z);
        }
        double f = (distance - runIn) / distance;
        return new Vec3(this.mob.getX() + toX * f, this.releaseY, this.mob.getZ() + toZ * f);
    }

    /**
     * <b>照準</b> —— いま離せば標的に当たる位置・向きにいるか。
     *
     * <p>爆弾は体の速度をそのまま引き継いで落ちるので、<b>標的の真上で離したのでは大きく先へ飛ぶ</b>。
     * 巡航 1.3 ブロック/tick・高度 8 ブロックなら、着弾するまでに 20 ブロック以上前へ進んでしまう。
     * そこで<b>手前で離す</b>({@link #releaseDistance})。機首が標的を向いていることも条件 ——
     * 真横や後ろを向いたまま離しても当たらない。
     */
    private boolean atReleasePoint(Vec3 target) {
        double toX = target.x - this.mob.getX();
        double toZ = target.z - this.mob.getZ();
        double distance = Math.hypot(toX, toZ);
        if (distance < 1.0E-4D) {
            // 真上。もう手遅れ(投下点は通り過ぎている)。
            return false;
        }
        float heading = (this.mob.getYRot() + 90.0F) * Mth.DEG_TO_RAD;
        double alignment = (toX * Mth.cos(heading) + toZ * Mth.sin(heading)) / distance;
        if (alignment < AIM_COSINE) {
            return false;
        }
        return Math.abs(distance - releaseDistance(target)) <= DROP_WINDOW_HORIZONTAL;
    }

    /**
     * 標的まであとどれだけのところで 1 発目を離すか(ブロック)。
     *
     * <p>内訳は 2 つ:
     * <ul>
     *     <li><b>落下中の前進</b> —— 落下時間は {@code t = sqrt(2h/g)}、その間に爆弾は
     *         機体の速度で前へ流れる(空気抵抗ぶん少しだけ縮む)。</li>
     *     <li><b>撃ち切るまでの前進の半分</b> —— 4 発を落としきるまでにも機体は進むので、
     *         その半分だけ余計に手前で始めると<b>弾着が標的を挟む</b>
     *         (前半が手前・後半が奥へ落ちる)。</li>
     * </ul>
     */
    private double releaseDistance(Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double speed = this.mob.getDeltaMovement().horizontalDistance();
        double fallHeight = Math.max(1.0D, this.releaseY - target.y);
        double fallTicks = Math.sqrt(2.0D * fallHeight / VelophyllBombEntity.GRAVITY);
        double lead = speed * fallTicks * DRAG_OVER_FALL;
        com.yayuy.overgrownvegetation.data.MobStat.Bomb bomb = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll");
        double stickLength = (bomb.perSalvo() - 1) * bomb.intervalTicks() * speed;
        return lead + stickLength * 0.5D;
    }

    /** {@link #ALTITUDE_REFRESH} tick ごとに投下高度を測り直す。 */
    private void refreshReleaseY(Vec3 target) {
        if (--this.altitudeTimer > 0) {
            return;
        }
        this.altitudeTimer = ALTITUDE_REFRESH;
        this.releaseY = computeReleaseY(target);
    }

    /**
     * 爆弾を落とす高さ(絶対 Y)。
     *
     * <p><b>「標的の {@code velophyllDropAltitude} ブロック上」と「木の天蓋の
     * {@code velophyllCanopyClearance} ブロック上」の高いほう。</b>
     * 開けた土地では前者が勝って今までどおりの急降下爆撃になり、森の上では後者が勝って
     * 木の上を水平に通過しながら落とす形になる —— <b>モードの切り替えは無く、式が 1 本あるだけ</b>。
     *
     * <p>ユーザー報告(2026-09-14)「森だと木につっかえる」への対処。標的の真上 8 ブロックまで
     * 降りようとすると、天蓋がそれより高い森では枝葉の中へ突っ込んでしまい、
     * {@link VelophyllFlightMoveControl} が壁として反応して暴れていた。
     *
     * <p>見るのは標的の周り {@code velophyllCanopyScanRadius} ブロック —— 爆撃コースは標的の上を
     * <b>通過</b>するので、進入路ぶんも含めて広めに取る。走査は {@value #CANOPY_STEP} ブロック
     * 間隔に間引いてあり、{@link Heightmap.Types#MOTION_BLOCKING} は<b>葉を含む</b>高さを返す。
     */
    private double computeReleaseY(Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double byTarget = target.y + cfg.velophyllDropAltitude.get();

        int radius = cfg.velophyllCanopyScanRadius.get();
        if (radius <= 0) {
            return byTarget;
        }
        int cx = Mth.floor(target.x);
        int cz = Mth.floor(target.z);
        int highest = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += CANOPY_STEP) {
            for (int dz = -radius; dz <= radius; dz += CANOPY_STEP) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                highest = Math.max(highest,
                        this.mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING, cx + dx, cz + dz));
            }
        }
        if (highest == Integer.MIN_VALUE) {
            return byTarget;
        }
        return Math.max(byTarget, highest + cfg.velophyllCanopyClearance.get());
    }

    /**
     * 1 発ずつ落とす区間。<b>その場に留まって落とすのではなく、標的の上を通過しながら落とす</b>ので、
     * 爆弾は体の速度を引き継いで一直線に並んで落ちる(狙いすましてはいない、という見え方)。
     */
    private void tickDropRun(Vec3 target) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        this.mob.setActionState(VelophyllEntity.STATE_DIVE);
        // 投下中は機首を上げずに、投下高度のまま標的の向こう側へ抜ける。
        if (--this.repathTimer <= 0) {
            this.repathTimer = REPATH_INTERVAL;
            this.mob.setMoveTargetPoint(new Vec3(target.x, this.releaseY, target.z));
        }

        if (--this.dropTimer > 0) {
            return;
        }
        this.dropTimer = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll").intervalTicks();
        releaseBomb();
        this.dropped++;
        if (this.dropped >= com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll").perSalvo()) {
            this.dropping = false;
            this.dropped = 0;
            this.mob.startBombCooldown();
            this.mob.setActionState(VelophyllEntity.STATE_GLIDE);
        }
    }

    /** 左右の爆弾架({@code leftbomb_bey} / {@code rightbomb_bey})から交互に 1 発。 */
    private void releaseBomb() {
        if (this.mob.level().isClientSide()) {
            return;
        }
        float yaw = this.mob.yBodyRot * ((float) Math.PI / 180.0F);
        double side = (this.dropped % 2 == 0) ? BAY_OFFSET : -BAY_OFFSET;
        double x = this.mob.getX() + Math.cos(yaw) * side;
        double z = this.mob.getZ() + Math.sin(yaw) * side;
        double y = this.mob.getY() + 0.1D;
        // 体の速度をそのまま渡す(+ 少しだけ下向きの初速で架から離す)。
        Vec3 velocity = this.mob.getDeltaMovement().add(0.0D, -0.12D, 0.0D);
        VelophyllBombEntity bomb =
                new VelophyllBombEntity(this.mob.level(), new Vec3(x, y, z), velocity, this.mob);
        this.mob.level().addFreshEntity(bomb);
        this.mob.level().playSound(null, x, y, z, SoundEvents.SHULKER_SHOOT,
                SoundSource.HOSTILE, 1.0F, 0.7F);
    }
}
