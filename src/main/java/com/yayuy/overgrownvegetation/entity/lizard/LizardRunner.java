package com.yayuy.overgrownvegetation.entity.lizard;

import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyNavigation;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 「トカゲ型」の移動の土台(2026-11-15、ピリムネスを入れるときに作った。シメリカワにも使い回す)。
 * 低く這う体・横へ張った 4 本の脚・背骨の左右のくねり・<b>走っては止まって見回す</b>動き。地上だけ(幹へは張り付かない。11-15 ユーザー決定)。
 *
 * <p>旋回・長い体の経路・体の曲げ・足の置き場・うろつきは「② 大型の地上を走る型」の土台 {@link LongBodyRunner} をそのまま使い、ここで足すのは:
 * <ul>
 *   <li><b>走っては止まる</b>(サーバー): 経路を進む間、{@link #dashMin()}〜{@link #dashMax()} ブロック走ったら {@link #pauseMin()}〜{@link #pauseMax()} tick
 *       止まり、首を上げて見回してから続きを走る。止まっている間は向きも変えない。走り出しは {@link #DASH_RAMP_TICKS} tick で速さを上げ、止まる前の
 *       {@link #BRAKE_DIST} ブロックで落とす。</li>
 *   <li><b>幹・岩の脇で止まる</b>(サーバー): 走り出すときに経路の先を見て、止まる予定の所の前後(−{@link #COVER_BEFORE}〜+{@link #COVER_AFTER} ブロック)に、
 *       体の横 2〜3 ブロックに高さ 2 の固いブロック(幹・岩・塀)がある点があれば、そこで止まるように走る距離を合わせる。
 *       設定: 遠くから棘を撃つ型なので、物陰の脇で止まって狙う(能力の段の下地)。弱み: 開けた所では隠れる所が無く、止まった所が丸見え。</li>
 *   <li><b>段差</b>: 1 ブロックは乗り越え(段差の高さ 1)、2 ブロックは跳び上がる。経路を引く間だけ段差の高さを {@link #hopHeight()} として見せ、
 *       2 段の上がりを経路に入れる(体を動かすときの段差は 1 のまま → 移動の制御が跳ぶ。跳ぶ力は種の属性 JUMP_STRENGTH)。</li>
 *   <li><b>背骨のくねり</b>(クライアント): {@link SpineWave}。足の運び(対角の脚の前後のずれ)から胸と腰を逆へ振り、尾へ波を送る。止まっている間の見回しも。</li>
 * </ul>
 * 脚は 4 本で、並びは 左前・右前・左後ろ・右後ろ(対角の 左前 + 右後ろ が組 0、右前 + 左後ろ が組 1)。描画は {@code client/lizard/SprawlLegRig}・{@code SpineWaveRig}。
 * 数値は全部【仮・Claude 11-15】。
 */
public abstract class LizardRunner extends LongBodyRunner {

    private static final EntityDataAccessor<Boolean> DATA_PAUSED = SynchedEntityData.defineId(LizardRunner.class, EntityDataSerializers.BOOLEAN);
    /** 今の走りが急ぎ(走りのアニメ)か、ゆっくり(歩きのアニメ)か。走り出すときに 1 回だけ決める(11-15 段 2)。 */
    private static final EntityDataAccessor<Boolean> DATA_RUN = SynchedEntityData.defineId(LizardRunner.class, EntityDataSerializers.BOOLEAN);

    /** 脚の踏み替えの組(対角)。並びは 左前・右前・左後ろ・右後ろ。 */
    public static final int[] DIAGONAL_GROUPS = {0, 1, 1, 0};

    /** 走り出してから速さが上がりきるまで(tick)と、そのときの最初の倍率【仮】。 */
    static final int DASH_RAMP_TICKS = 6;
    static final double DASH_START = 0.4D;
    /** 止まる前に速さを落とし始める残り(ブロック)と、落とした下限の倍率【仮】。 */
    static final double BRAKE_DIST = 0.9D, BRAKE_MIN = 0.3D;
    /** 経路の残りがこれより短ければ止まらずに着く(ブロック)【仮】。 */
    static final double FINISH_DIST = 2.5D;
    /** 物陰を探す範囲: 止まる予定の所の手前・先(ブロック)【仮】。 */
    public static final double COVER_BEFORE = 1.5D, COVER_AFTER = 2.5D;
    /** 物陰とみる横の距離(体の横へ、ブロック)。脚は横へ 1.7 ブロック張るので、その外【仮】。 */
    static final int[] COVER_SIDE = {2, 3};

    /** サーバー: 止まっている残りの tick(0 = 走っている)。 */
    private int pauseTicks;
    /** サーバー: 今の走りの残りの距離(NaN = まだ決めていない)と、走り出してからの tick。 */
    private double dashLeft = Double.NaN;
    private int dashTicks;
    /** サーバー: 今の走りの止まる所に物陰があるか(確かめ用)。 */
    private boolean coverPlanned;
    /** 確かめ用(GameTest): 止まった回数と、そのうち物陰の脇で止まった回数。 */
    public int pauses, coverPauses;
    /** 経路を引いている間か(段差の高さを跳べる高さとして見せる)。 */
    private boolean pathing;

    /** クライアント: 背骨のくねりと見回し。 */
    private final SpineWave spine = new SpineWave();
    private final double[] legFwd = new double[4];

    protected LizardRunner(EntityType<? extends LizardRunner> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------ 種ごとに決める(既定は【仮】)

    /** 1 回に走る距離の下限・上限(ブロック)。 */
    protected double dashMin() {
        return 3.0D;
    }

    protected double dashMax() {
        return 6.0D;
    }

    /** 1 回に止まる長さの下限・上限(tick)。 */
    protected int pauseMin() {
        return 10;
    }

    protected int pauseMax() {
        return 30;
    }

    /** 経路を引くときの段差の高さ(ブロック。体を動かすときは属性の段差のまま)。 */
    protected float hopHeight() {
        return 2.0F;
    }

    /** 走りのアニメにする経路の速さ倍率の下限(これより遅い走りは歩きのアニメ)【仮】。 */
    protected double runModifier() {
        return 0.95D;
    }

    /**
     * 止まっている間の見回しをアニメ(look_around)でするか。false ならコードの見回し({@link SpineWave} の首上げ・頭の往復)。
     * 二重にしないため、どちらか片方だけ。ピリムネスはアニメ版に決まった(11-15 ユーザー)。コードの見回しはアニメの無い種と切り替え用。
     */
    public boolean lookByAnimation() {
        return false;
    }

    @Override
    protected final int[] legGroups() {
        return DIAGONAL_GROUPS;
    }

    // ------------------------------------------------------------------ 登録

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_PAUSED, false);
        this.entityData.define(DATA_RUN, false);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new LongBodyNavigation(this, level, longBodyWeights(), () -> true) {
            @Override
            protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange) {
                LizardRunner.this.pathing = true;
                try {
                    return super.createPath(targets, regionOffset, offsetUpward, accuracy, followRange);
                } finally {
                    LizardRunner.this.pathing = false;
                }
            }

            /**
             * 止まって見回している間は、バニラの「詰まり」と「時間切れ」を数えない(11-16)。バニラの時間切れの数え({@code timeoutTimer})は経路を
             * 引いた時にしか 0 に戻らず、止まっている間も増える。走っては止まるので 20 ブロックの道で 4〜6 回止まると、数えが「次の点までの時間 × 3」
             * (速さ 0.3 で約 200 tick)を超えて経路が途中で捨てられ、目標の 3〜4 ブロック手前で立ち止まっていた(GameTest が時々時間切れになった原因)。
             */
            @Override
            protected void doStuckDetection(Vec3 position) {
                if (LizardRunner.this.pauseTicks > 0) {
                    this.lastTimeoutCheck = LizardRunner.this.level().getGameTime();
                    this.lastStuckCheck = this.tick;
                    this.lastStuckCheckPos = position;
                    return;
                }
                super.doStuckDetection(position);
            }
        };
    }

    /** 経路を引く間だけ 2 段を上がれるものとして見せる(体を動かすときは属性の値。上がりが段差を超えると移動の制御が跳ぶ)。 */
    @Override
    public float maxUpStep() {
        return this.pathing ? Math.max(hopHeight(), super.maxUpStep()) : super.maxUpStep();
    }

    // ------------------------------------------------------------------ 走っては止まる(サーバー)

    @Override
    protected final void tickRunner() {
        tickStopGo();
        tickLizard();
        if (DEV_HOOKS && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /** 種ごとのサーバーの tick(走っては止まるの後・旋回の前)。 */
    protected void tickLizard() {
    }

    private void tickStopGo() {
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            wantFacing(this.getYRot(), TURN_ACTION);   // 止まっている間は向きを変えない(見回すのは首だけ)
            if (this.pauseTicks == 0 || this.getNavigation().isDone()) {
                this.pauseTicks = 0;
                this.entityData.set(DATA_PAUSED, false);
                this.dashLeft = Double.NaN;
            }
            return;
        }
        if (this.getNavigation().isDone() || isActing() || this.isInWater()) {
            this.dashLeft = Double.NaN;
            return;
        }
        if (Double.isNaN(this.dashLeft)) {
            planDash();
        }
        double moved = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
        this.dashLeft -= moved;
        this.dashTicks++;
        if (this.dashLeft <= 0.0D && this.onGround()) {
            if (remainingPath(FINISH_DIST + 0.5D) > FINISH_DIST) {
                this.pauseTicks = Mth.nextInt(this.random, pauseMin(), pauseMax());
                this.entityData.set(DATA_PAUSED, true);
                this.pauses++;
                if (this.coverPlanned) this.coverPauses++;
            } else {
                this.dashLeft = Double.POSITIVE_INFINITY;   // 着くまで走る
            }
        }
    }

    /** 次の走りの距離を決める。止まる予定の所の近くに物陰の脇があれば、そこまでにする。 */
    private void planDash() {
        double want = Mth.nextDouble(this.random, dashMin(), dashMax());
        this.dashTicks = 0;
        this.entityData.set(DATA_RUN, currentSpeedModifier() >= runModifier());   // 1 回の走りに 1 本(速さで毎 tick 選ぶと走り出し・止まり際でぱたつく)
        this.coverPlanned = false;
        Path path = this.getNavigation().getPath();
        if (path == null) {
            this.dashLeft = want;
            return;
        }
        double acc = 0.0D, best = Double.NaN, bestGap = Double.MAX_VALUE;
        Vec3 at = this.position();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            Vec3 p = new Vec3(n.x + 0.5D, n.y, n.z + 0.5D);
            double seg = Math.sqrt(sq(p.x - at.x) + sq(p.z - at.z));
            if (seg > 1e-4) {
                double dx = (p.x - at.x) / seg, dz = (p.z - at.z) / seg;
                acc += seg;
                if (acc > want + COVER_AFTER) break;
                if (acc >= want - COVER_BEFORE && Math.abs(acc - want) < bestGap && coverBeside(n.asBlockPos(), dx, dz)) {
                    best = acc;
                    bestGap = Math.abs(acc - want);
                }
            }
            at = p;
        }
        if (!Double.isNaN(best)) {
            this.dashLeft = best;
            this.coverPlanned = true;
        } else {
            this.dashLeft = want;
        }
    }

    /** その点の体の横({@link #COVER_SIDE} ブロック)に、高さ 2 の固いブロック(幹・岩・塀)があるか。 */
    public boolean coverBeside(BlockPos p, double dirX, double dirZ) {
        double sx = -dirZ, sz = dirX;
        for (int side = -1; side <= 1; side += 2) {
            for (int d : COVER_SIDE) {
                BlockPos q = BlockPos.containing(p.getX() + 0.5D + sx * side * d, p.getY(), p.getZ() + 0.5D + sz * side * d);
                if (solid(q) && solid(q.above())) return true;
            }
        }
        return false;
    }

    private boolean solid(BlockPos q) {
        BlockState s = this.level().getBlockState(q);
        return s.isCollisionShapeFullBlock(this.level(), q);
    }

    /** 経路の残りの長さ(ブロック、{@code cap} まで数えたら打ち切る)。 */
    private double remainingPath(double cap) {
        Path path = this.getNavigation().getPath();
        if (path == null) return 0.0D;
        double acc = 0.0D;
        Vec3 at = this.position();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount() && acc <= cap; i++) {
            Node n = path.getNode(i);
            acc += Math.sqrt(sq(n.x + 0.5D - at.x) + sq(n.z + 0.5D - at.z));
            at = new Vec3(n.x + 0.5D, n.y, n.z + 0.5D);
        }
        return acc;
    }

    /** 速さの倍率: 止まっている間 0、走り出しで上げ、止まる前で落とす。種の倍率 {@link #dashBoost()} を掛ける。 */
    @Override
    protected final double gaitBoost() {
        if (this.pauseTicks > 0) return 0.0D;
        double k = 1.0D;
        if (!Double.isNaN(this.dashLeft)) {
            k = DASH_START + (1.0D - DASH_START) * Math.min(1.0D, this.dashTicks / (double) DASH_RAMP_TICKS);
            if (this.dashLeft < BRAKE_DIST) k *= Math.max(BRAKE_MIN, this.dashLeft / BRAKE_DIST);
        }
        return k * dashBoost();
    }

    /** 種ごとの速さの倍率(既定 1)。 */
    protected double dashBoost() {
        return 1.0D;
    }

    /** 止まって見回しているか(クライアントでも読める)。 */
    public boolean isPaused() {
        return this.entityData.get(DATA_PAUSED);
    }

    /** 今の走りを走りのアニメで見せるか(走り出すときに決まる。クライアントでも読める)。 */
    public boolean isDashRun() {
        return this.entityData.get(DATA_RUN);
    }

    /** クライアント: これまでに止まった回数(見回しの左右を選ぶ)。 */
    public int pauseCount() {
        return this.pauseCount;
    }

    private int pauseCount;
    private boolean wasPaused;

    /** サーバー: 今の走りの残りの距離(確かめ用)。 */
    public double dashLeft() {
        return this.dashLeft;
    }

    // ------------------------------------------------------------------ くねり(クライアント)

    @Override
    public void tick() {
        super.tick();   // 足の置き場はこの中で進む
        if (!this.level().isClientSide()) return;
        FootPlanter fp = footPlanter();
        boolean ready = fp != null && fp.ready() && fp.legs() >= 4;
        if (ready) {
            float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), s = Math.max(this.getScale(), 0.05F);
            double[] home = legHomeFwd();
            for (int i = 0; i < 4; i++) {
                Vec3 f = fp.foot(i, 1.0F);
                this.legFwd[i] = ((f.x - this.getX()) * fx + (f.z - this.getZ()) * fz) / s - home[i];
            }
        }
        double speed = clientForwardSpeed() / Math.max(this.getScale(), 0.05F);
        boolean paused = isPaused();
        if (paused && !this.wasPaused) this.pauseCount++;
        this.wasPaused = paused;
        this.spine.tick(ready && proceduralLegs() ? this.legFwd : null, speed, paused && !lookByAnimation(), this.tickCount);
        // 動いている間は胴を低く(這う)。止まると休めの高さへ戻る
        boolean upright = DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("upright");   // 確かめ用: 低くしない
        double want = ready && proceduralLegs() && speed > 0.02D && !upright ? crouchDepth() * Math.max(this.getScale(), 0.05F) : 0.0D;
        this.crouchPrev = this.crouchNow;
        this.crouchNow += (want - this.crouchNow) * CROUCH_FOLLOW;
    }

    /** 動いている間に胴を下げる量(ブロック、体格 1)【仮】。 */
    protected double crouchDepth() {
        return 0.3D;
    }

    /** 胴を下げる量が寄る割合(1 tick)【仮】。 */
    static final double CROUCH_FOLLOW = 0.2D;
    private double crouchNow, crouchPrev;

    /** 描画: 今 胴を下げている量(ブロック)。 */
    public double crouch(float pt) {
        return Mth.lerp(pt, this.crouchPrev, this.crouchNow);
    }

    public SpineWave spine() {
        return this.spine;
    }

    // ------------------------------------------------------------------ 確かめ用

    /**
     * 確かめ用(テストクライアントだけ): 名前が {@code nostroll_run} で始まる個体は経路が尽きるたびに 16〜28 ブロック先の陸へ速さ 1.0 で走る。
     * 名前に {@code trace} を含む個体は 5 tick ごとに速さ・止まり・物陰を書く。
     */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 28, 6);
            if (p != null && p.distanceToSqr(this.position()) > 16.0D * 16.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 5 == 0) {
            double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo));
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "LIZARD %s pos %.1f %.1f %.1f 速さ %.3f 止まり %d 残り %.2f 物陰 %s 止まった %d(物陰 %d)回る %.1f 経路 %s", name, this.getX(), this.getY(),
                    this.getZ(), v, this.pauseTicks, this.dashLeft, this.coverPlanned, this.pauses, this.coverPauses, getTurnRate(),
                    this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    protected static double sq(double v) {
        return v * v;
    }
}
