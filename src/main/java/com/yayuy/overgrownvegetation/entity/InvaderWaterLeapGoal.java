package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * グループ4(拡大種)が<b>川や池をとびかかりで跳び越える</b> Goal(ユーザー要望 2026-09-14)。
 *
 * <p>拡大種は水を経路に使わない({@code PathType.WATER} は malus -1 = 入らない)ので、
 * そのままだと川にぶつかった時点で岸を行ったり来たりしてしまう。この Goal は
 * <b>向こう岸が跳んで届く範囲にある</b>とき、水面越しに跳んで渡る。汀に着いてからでなくてよく、
 * 水まで {@value #APPROACH_RANGE} マス手前からでも跳ぶ(2026-09-17) —— 川へ向かって歩いている
 * 途中で足が止まってしまうことがあったため。届くかどうかは跳ぶ地点からの放物線で見ているので、
 * 手前から跳んでも対岸の手前に落ちることはない。
 *
 * <h2>どこへ跳ぶか</h2>
 * <ol>
 *     <li>行きたい向き = 標的がいればその方向、無ければ今引いている経路の終点、
 *     それも無ければ体の向き。</li>
 *     <li>その前方 180 度を {@link #DIRECTIONS} 方向に分けて、1 マスずつ外へ辿る。
 *     <b>水が {@link #MIN_WATER_GAP} マス以上続いたあとに着地できる陸がある</b>方向を探す
 *     ({@link #findLanding})。行きたい向きに近い方向から順に見て、最初に見つかったものを使う。</li>
 *     <li>届く距離と高さは跳ぶ勢いから逆算する({@link LeapArc}) — 勢いを変えれば探す距離も
 *     自動で変わる。着地点ごとに「その放物線でそこへ届くか」を {@link LeapArc#canReach} で確かめる
 *     ので、遠くて高い場所を取り違えない。アルボラは<b>横 24 ブロック・高さ 5 ブロック</b>まで
 *     (ユーザー指定 2026-09-14。勢いは横 2.44 / 上 0.91)。</li>
 * </ol>
 *
 * <p>着地地点は<b>体がまるごと入る</b>ことを確かめてから跳ぶので、向こう岸の壁にめり込まない。
 * 跳んでいる間の状態は {@link AbstractOvergrownInvaderEntity#STATE_LEAP}(= {@code leap} アニメ)。
 */
public class InvaderWaterLeapGoal extends Goal {

    /** 前方をいくつの方向に分けて探すか(左右 ±90 度)。 */
    private static final int DIRECTIONS = 9;
    /** これだけ水が続いていなければ「跳んで渡る」対象にしない(歩いて回り込める段差は対象外)。 */
    private static final int MIN_WATER_GAP = 2;
    /** 着地点として許す落差(跳び上がれる高さは勢いから逆算するので上限だけ定数)。 */
    private static final int MAX_LANDING_DROP = 6;
    /** 空中にいるとみなす最大 tick。24 ブロック跳ぶと 23 tick ほど飛んでいる。 */
    private static final int MAX_TICKS = 40;
    /** 渡れる場所を探す間隔(tick)。毎 tick 地形を舐めると重いので間引く。 */
    private static final int SCAN_INTERVAL = 10;
    /** 渡れる場所が見つからなかったときは、次に探すまでもっと間を置く。 */
    private static final int FAILED_SCAN_INTERVAL = 40;
    /** 「もう汀に立っている」とみなす、水までの距離(マス)。 */
    private static final int EDGE_CHECK_RANGE = 2;
    /**
     * <b>行きたい向きの先</b>に水を探す距離(マス)。汀まで歩き着く前 —— 川へ向かっている途中でも
     * 渡れる場所を見つけて跳べるようにするための距離(ユーザー要望 2026-09-17)。
     * 着地点を探すときに「手前の陸」を何マスまで見逃すかもこの値。
     */
    private static final int APPROACH_RANGE = 6;
    /** 水かどうかを見るときに足元から下へ何マス見るか(岸が水面より高いことがある)。 */
    private static final int WATER_PROBE_DEPTH = 3;

    private final AbstractOvergrownInvaderEntity mob;
    private final double horizontalSpeed;
    private final double lift;
    private final int cooldownTicks;
    /** この勢いで届く水平距離。 */
    private final double maxRange;
    /** この勢いで跳び上がれる高さ(着地点として許す上限)。 */
    private final int maxRise;

    private int ticks;
    private int nextTime;
    /** 次に渡れる場所を探してよい tick(毎 tick 地形を舐めないための間引き)。 */
    private int nextScanTick;
    private Vec3 jumpDir;

    public InvaderWaterLeapGoal(AbstractOvergrownInvaderEntity mob, double horizontalSpeed, double lift,
                                int cooldownTicks) {
        this.mob = mob;
        this.horizontalSpeed = horizontalSpeed;
        this.lift = lift;
        this.cooldownTicks = cooldownTicks;
        double[] reach = LeapArc.reach(horizontalSpeed, lift, MAX_TICKS);
        this.maxRange = reach[0];
        this.maxRise = Mth.floor(reach[1]);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.mob.tickCount < this.nextTime) {
            return false;
        }
        if (this.mob.getActionState() != AbstractOvergrownInvaderEntity.STATE_IDLE) {
            return false;
        }
        if (!this.mob.onGround() || this.mob.isInWater() || this.mob.isClimbing() || this.mob.isOnCeiling()) {
            return false;
        }
        if (!mustCross()) {
            return false;
        }
        // 地形を舐めるのは間引く + そもそも岸に立っていなければ何もしない(負荷対策)。
        if (this.mob.tickCount < this.nextScanTick) {
            return false;
        }
        this.nextScanTick = this.mob.tickCount + SCAN_INTERVAL;
        Vec3 wanted = wantedDirection();
        // 汀に立っているか、行きたい向きの先に水があるか。どちらでもなければ地形は舐めない。
        if (!atWaterEdge() && !waterAhead(wanted)) {
            return false;
        }
        this.jumpDir = findCrossing(wanted);
        if (this.jumpDir == null) {
            this.nextScanTick = this.mob.tickCount + FAILED_SCAN_INTERVAL;
            return false;
        }
        return true;
    }

    /**
     * <b>どうしても水を越えないと相手に届かないか</b>(ユーザー要望 2026-09-19:
     * 「基本的に水を避けて動き、敵を追いかける経路にどうしても水を越えなければならない際にのみ
     * 飛び越えるようにしてほしい」)。
     *
     * <p>条件は 2 つ。
     * <ol>
     *     <li><b>敵を追いかけている</b> —— 標的が無ければ跳ばない。
     *     それまでは標的も経路も無いとき「体の向き」を行きたい向きにしていたので、
     *     川のそばをうろついているだけの個体が<b>用も無く対岸へ跳んでいた</b>。</li>
     *     <li><b>陸路が引けていない</b>({@code invaderWaterLeapDelayTicks} 以上)。
     *     橋でも浅瀬でも回り道でも、歩いて辿り着けるなら跳ばずにそちらを行く。</li>
     * </ol>
     *
     * <p>2 つ目は{@link AbstractOvergrownInvaderEntity#noApproachTicks()} を見るだけで、
     * ここで経路探索をやり直さない。おかげで<b>水際に立っている個体が毎 tick A* を回す</b>
     * ことにはならない。
     *
     * <p>副作用としてこの Goal は<b>汀に着いてから</b>動くようになった ——
     * 陸路が尽きる(= 歩いてきた経路が終わる)のは、ふつう水際に着いたときなので。
     * 手前から跳ぶための仕掛け({@value #APPROACH_RANGE} マス先の水を見る)はそのまま残してあるが、
     * 実際に効くのは「経路が最初から引けなかった」場合だけになる。
     */
    private boolean mustCross() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return this.mob.noApproachTicks()
                >= OvergrownVegetationConfig.COMMON.invaderWaterLeapDelayTicks.get();
    }

    @Override
    public boolean canContinueToUse() {
        // 跳んだ直後の数 tick は接地判定が残るので終了しない。
        return this.ticks <= MAX_TICKS && (this.ticks <= 3 || !this.mob.onGround());
    }

    @Override
    public void start() {
        this.ticks = 0;
        this.mob.getNavigation().stop();
        this.mob.setActionState(AbstractOvergrownInvaderEntity.STATE_LEAP);
        this.mob.setDeltaMovement(this.jumpDir.x * this.horizontalSpeed, this.lift,
                this.jumpDir.z * this.horizontalSpeed);
        this.mob.hasImpulse = true;
        float yaw = (float) (Mth.atan2(this.jumpDir.z, this.jumpDir.x) * (180.0D / Math.PI)) - 90.0F;
        this.mob.setYRot(yaw);
        this.mob.yBodyRot = yaw;
    }

    @Override
    public void stop() {
        this.jumpDir = null;
        this.nextTime = this.mob.tickCount + this.cooldownTicks;
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
    }

    /** 水際に立っているか(周囲 {@value #EDGE_CHECK_RANGE} マスに水があるか)の安い前判定。 */
    private boolean atWaterEdge() {
        Level level = this.mob.level();
        BlockPos foot = this.mob.blockPosition();
        for (int dx = -EDGE_CHECK_RANGE; dx <= EDGE_CHECK_RANGE; dx++) {
            for (int dz = -EDGE_CHECK_RANGE; dz <= EDGE_CHECK_RANGE; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (isWaterColumn(level, foot.offset(dx, 0, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 行きたい向きの先 {@value #APPROACH_RANGE} マスに水があるか。{@link #atWaterEdge()} が
     * 周囲を四角く舐めるのに対して、こちらは 1 直線ぶんしか読まない(前判定なので安く保つ)。
     */
    private boolean waterAhead(Vec3 wanted) {
        Level level = this.mob.level();
        BlockPos foot = this.mob.blockPosition();
        for (int d = 1; d <= APPROACH_RANGE; d++) {
            if (isWaterColumn(level, foot.offset(Mth.floor(wanted.x * d + 0.5D), 0,
                    Mth.floor(wanted.z * d + 0.5D)))) {
                return true;
            }
        }
        return false;
    }

    /** 行きたい向きに近い順に前方を探して、跳んで渡れる方向(水平の単位ベクトル)を返す。 */
    private Vec3 findCrossing(Vec3 wanted) {
        float baseYaw = (float) (Mth.atan2(wanted.z, wanted.x) * (180.0D / Math.PI));
        for (int i = 0; i < DIRECTIONS; i++) {
            // 0, +20, -20, +40, -40 ... の順に、行きたい向きの近くから外へ広げて見る。
            int step = (i + 1) / 2;
            float offset = (i % 2 == 0 ? step : -step) * (180.0F / (DIRECTIONS - 1));
            double rad = Math.toRadians(baseYaw + offset);
            Vec3 dir = new Vec3(Math.cos(rad), 0.0D, Math.sin(rad));
            if (findLanding(dir)) {
                return dir;
            }
        }
        return null;
    }

    /** 標的 → 経路の終点 → 体の向き、の順で「行きたい向き」を決める。 */
    private Vec3 wantedDirection() {
        LivingEntity target = this.mob.getTarget();
        if (target != null) {
            Vec3 toTarget = new Vec3(target.getX() - this.mob.getX(), 0.0D, target.getZ() - this.mob.getZ());
            if (toTarget.lengthSqr() > 1.0E-4D) {
                return toTarget.normalize();
            }
        }
        Path path = this.mob.getNavigation().getPath();
        if (path != null) {
            BlockPos end = path.getTarget();
            Vec3 toEnd = new Vec3(end.getX() + 0.5D - this.mob.getX(), 0.0D, end.getZ() + 0.5D - this.mob.getZ());
            if (toEnd.lengthSqr() > 1.0E-4D) {
                return toEnd.normalize();
            }
        }
        Vec3 look = this.mob.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        return flat.lengthSqr() > 1.0E-4D ? flat.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    /**
     * {@code dir} の方向に、<b>水が {@value #MIN_WATER_GAP} マス以上続いたあとに着地できる陸</b>が
     * 跳んで届く距離の中にあるか。
     */
    private boolean findLanding(Vec3 dir) {
        Level level = this.mob.level();
        BlockPos foot = this.mob.blockPosition();
        int max = Mth.floor(this.maxRange - 0.5D);
        int water = 0;
        for (int d = 1; d <= max; d++) {
            BlockPos ahead = foot.offset(Mth.floor(dir.x * d + 0.5D), 0, Mth.floor(dir.z * d + 0.5D));
            if (isWaterColumn(level, ahead)) {
                water++;
                continue;
            }
            if (water == 0) {
                // まだ水に入っていない = 岸の上。汀まで数マスは見逃して先を見る。
                if (d > APPROACH_RANGE) {
                    return false;
                }
                continue;
            }
            if (water < MIN_WATER_GAP) {
                // 水たまり程度なら跳ばずに回り込めばよい。
                return false;
            }
            for (int dy = this.maxRise; dy >= -MAX_LANDING_DROP; dy--) {
                // 放物線がその高さに届くか + 体がまるごと入るか。
                if (LeapArc.canReach(this.horizontalSpeed, this.lift, MAX_TICKS, d, dy)
                        && canLandOn(ahead.above(dy))) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * その柱が水か。足元の高さから下へ {@value #WATER_PROBE_DEPTH} マス見て、
     * <b>先に見つかったのが水なら水・固い地面なら陸</b>とみなす(岸が水面より高くても汀を拾える)。
     */
    private boolean isWaterColumn(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i <= WATER_PROBE_DEPTH; i++) {
            if (!level.getFluidState(cursor).isEmpty()) {
                return level.getFluidState(cursor).is(FluidTags.WATER);
            }
            if (level.getBlockState(cursor).blocksMotion()) {
                return false;
            }
            cursor.move(0, -1, 0);
        }
        return false;
    }

    /** そこへ着地できるか(足場があり、体がまるごと入る)。 */
    private boolean canLandOn(BlockPos pos) {
        Level level = this.mob.level();
        if (!level.getBlockState(pos.below()).blocksMotion()) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        AABB box = this.mob.getBoundingBox()
                .move(pos.getX() + 0.5D - this.mob.getX(), pos.getY() - this.mob.getY(),
                        pos.getZ() + 0.5D - this.mob.getZ());
        return level.noCollision(this.mob, box);
    }
}
