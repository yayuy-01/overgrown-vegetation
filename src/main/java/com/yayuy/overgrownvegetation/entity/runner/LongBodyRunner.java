package com.yayuy.overgrownvegetation.entity.runner;

import com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「② 大型の地上を走る型」の土台(2026-11-14、ヴェナムネスを入れるときにファルキムネスの作りから種に依らない所を切り出した)。
 * 体長 8〜11 ブロックの長い体で、細い芯だけが地形とぶつかり、幹の間を縫って走る Mob。
 *
 * <h2>持っているもの(どれもファルキムネスで作り、ユーザーがゲーム内で確かめた作りの写し)</h2>
 * <ul>
 *   <li><b>旋回</b>: 向き({@code yRot} = 体 = 頭)はこの個体の {@link #heading} だけが正。移動・視線は {@link #wantFacing} で希望を出し、
 *       tick の終わりに {@link #maxTurnPerTick} 度まで回す。進むのは前へだけ(向きのずれの cos 倍。{@link #maxMoveOff} を超えたらその場で回る)。</li>
 *   <li><b>経路</b>: {@link LongBodyNavigation}(長い体の重み + 回り道の下見と予算)。</li>
 *   <li><b>体の曲げ</b>: {@link BodyBend}(首は先の経路、尾は通った跡)。描画は {@code client/runner/BodyBendRig}。</li>
 *   <li><b>足の置き場</b>: {@link FootPlanter}(クライアント、近くで描かれている個体だけ)。脚の IK は {@code client/runner/LegChainRig}。</li>
 *   <li><b>うろつき</b>: {@link StrollGoal}(バニラのうろつきは創造・観戦の人のそばで始まらない)。</li>
 * </ul>
 * 種ごとに決めるのは抽象メソッド(脚の置き場・曲げの付け根・経路の重み・旋回の速さ)。<b>どれも static の定数を返すこと</b>
 * (経路と曲げは {@code Mob} の構築子の中で作るので、種のクラスのフィールドはまだ無い)。
 *
 * <p>まだ切り出していない(ファルキムネスにだけある): 叩ける部位・サーバーの足の置き方(坂の部位の傾き)・狭い所の構え・性格・
 * 葉の押し分け・下草の欠片。ファルキムネス自身はまだこの土台を使っていない(差し替えは GameTest を流して別の段で)。
 */
public abstract class LongBodyRunner extends Monster {

    private static final EntityDataAccessor<Float> DATA_TURN = SynchedEntityData.defineId(LongBodyRunner.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> DATA_BEND = SynchedEntityData.defineId(LongBodyRunner.class, EntityDataSerializers.LONG);

    protected static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");

    /** 向きの希望の強さ。強いものが勝つ(同じ強さなら後から出したもの)。 */
    public static final int TURN_LOOK = 0, TURN_HUNT = 1, TURN_MOVE = 2, TURN_ACTION = 3;

    /** うろつきを始める、プレイヤーからの距離(ブロック)【仮、ファルキムネスと同じ】。 */
    public static final double STROLL_RANGE = 64.0D;
    /** うろつきの間隔(平均の tick)と行き先の範囲(横・縦、ブロック)【仮、ファルキムネスと同じ】。 */
    static final int STROLL_INTERVAL = 80, STROLL_XZ = 12, STROLL_Y = 7;

    private float heading = Float.NaN;
    private float turnSpeed;
    private float wantedYaw;
    private int wantedPriority = -1;

    private final BodyBend bend = new BodyBend(bendFront(), bendBack());

    @Nullable
    private FootPlanter footPlanter;
    private double[][] legSolution;
    private int legSolvedTick = -100, legsRenderedTick = -100;
    private boolean clientProceduralLegs;

    protected LongBodyRunner(EntityType<? extends LongBodyRunner> type, Level level) {
        super(type, level);
        this.moveControl = new TankMoveControl();
        this.lookControl = new TankLookControl();
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.LEAVES, -1.0F);
        this.getNavigation().setCanFloat(false);
    }

    // ------------------------------------------------------------------ 種ごとに決める(static の定数を返す)

    /** 脚の基準の置き場: 体の左へ(ブロック、体格 1)。 */
    protected abstract double[] legHomeLeft();

    /** 脚の基準の置き場: 体の前へ(ブロック、体格 1)。 */
    protected abstract double[] legHomeFwd();

    /** 脚の踏み替えの組み(0 か 1)。4 本なら対角、2 本なら左右。 */
    protected abstract int[] legGroups();

    /** 曲げの前の付け根(体の前へ、ブロック、体格 1、4 つ)。 */
    protected abstract double[] bendFront();

    /** 曲げの後ろの付け根(体の後ろへ、ブロック、体格 1、4 つ)。 */
    protected abstract double[] bendBack();

    /** 経路の長い体の重み。 */
    protected abstract OvergrownNodeEvaluator.LongBody longBodyWeights();

    /** 1 tick に回れる角度の上限(度)。 */
    protected abstract float maxTurnPerTick();

    /** 角速度を行きたい速さへ寄せる割合(1 tick あたり)。 */
    protected float turnSmoothing() {
        return 0.3F;
    }

    /** 向きのずれがこれ(度)を超えたら進まずにその場で回る。 */
    protected float maxMoveOff() {
        return 70.0F;
    }

    /** 向きがずれている間の前へ進む割合の下限(0 = ファルキムネスと同じ cos のまま)。 */
    protected float minTurnAdvance() {
        return 0.0F;
    }

    /** 移動の速さに掛ける倍率(移動の速さ × 経路の速さ倍率 × これ)。サーバーで毎 tick 読む。 */
    protected double gaitBoost() {
        return 1.0D;
    }

    /** 技の最中か(体の曲げ・足の置き方を止める)。段 1 では技が無い。 */
    public boolean isActing() {
        return false;
    }

    // ------------------------------------------------------------------ 登録

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new LongBodyNavigation(this, level, longBodyWeights(), () -> true);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new TankBodyControl(this);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_TURN, 0.0F);
        builder.define(DATA_BEND, 0L);
    }

    /** うろつく・プレイヤーを見る・見回すだけ(段 1)。標的の Goal は種が足す。 */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(6, new StrollGoal(this, strollSpeed()));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /** うろつきの速さ(移動の速さに掛ける)。 */
    protected double strollSpeed() {
        return 0.8D;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) {
            tickFootPlanter();
        }
    }

    @Override
    public void aiStep() {
        if (this.level().isClientSide()) {
            super.aiStep();
            this.bend.clientTick(this.entityData.get(DATA_BEND));
            return;
        }
        long t0 = System.nanoTime();
        if (!Float.isNaN(this.heading)) {
            applyFacing();
        }
        super.aiStep();
        this.entityData.set(DATA_BEND, this.bend.serverTick(this, legsFree()));
        if (this.isAlive()) {
            tickRunner();
            tickTurn();
        }
        Perf.add(this.level().getGameTime(), System.nanoTime() - t0);
    }

    /**
     * 重さの計測(サーバー、この型の Mob 全部): 1 tick ぶんの aiStep(Goal・経路・移動・当たり・曲げ・速さ)の合計の、最悪と平均。
     * {@link #reset} から数える。開発用のログ({@code -Dov.devbridge})では 200 tick ごとに書く。
     */
    public static final class Perf {
        private static long tick = Long.MIN_VALUE, acc, worst, sum, ticks, mobs, mobsNow, winWorst, winSum, winMobs, winOver;

        static synchronized void add(long gameTime, long ns) {
            if (gameTime != tick) {
                flush();
                tick = gameTime;
            }
            acc += ns;
            mobsNow++;
        }

        private static void flush() {
            if (tick == Long.MIN_VALUE || mobsNow == 0) return;
            worst = Math.max(worst, acc);
            sum += acc;
            winWorst = Math.max(winWorst, acc);
            winSum += acc;
            winMobs = Math.max(winMobs, mobsNow);
            if (acc > 5_000_000L) winOver++;
            ticks++;
            mobs = Math.max(mobs, mobsNow);
            if (DEV_HOOKS && ticks % 200 == 0) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "RUNNER_PERF %d tick まで: この 200 tick は最大 %d 体 平均 %.3f ms 最悪 %.3f ms 5 ms 超え %d tick(全体の最悪 %.3f ms)経路づくり %d 回 計 %.1f ms 1 回の最悪 %.2f ms 回り道 %d 回", ticks, winMobs, winSum / 1e6 / 200.0D, winWorst / 1e6, winOver, worst / 1e6,
                        LongBodyNavigation.pathCalls, LongBodyNavigation.pathNanos / 1e6, LongBodyNavigation.pathWorst / 1e6, LongBodyNavigation.detourCalls));
                winWorst = winSum = winMobs = winOver = 0;
                LongBodyNavigation.pathCalls = LongBodyNavigation.pathNanos = LongBodyNavigation.pathWorst = LongBodyNavigation.detourCalls = 0;
            }
            acc = 0;
            mobsNow = 0;
        }

        public static synchronized void reset() {
            tick = Long.MIN_VALUE;
            acc = worst = sum = ticks = mobs = mobsNow = 0;
        }

        /** {平均 ms, 最悪 ms, 数えた tick, 1 tick の最大の体数}(今の tick はまだ入っていない)。 */
        public static synchronized double[] stats() {
            return new double[]{ticks == 0 ? 0 : sum / 1e6 / ticks, worst / 1e6, ticks, mobs};
        }
    }

    /** 種ごとのサーバーの tick(移動の後・旋回の前)。 */
    protected void tickRunner() {
    }

    /** 手続きの脚・曲げが効いてよいか(死・乗り物・水の中・技の最中でない)。 */
    protected boolean legsFree() {
        return !this.isDeadOrDying() && !this.isPassenger() && !this.isInWater() && !isActing();
    }

    // ------------------------------------------------------------------ 足の置き場(クライアント)

    private void tickFootPlanter() {
        if (this.footPlanter == null) {
            this.footPlanter = new FootPlanter(legHomeLeft(), legHomeFwd(), legGroups());
            this.legSolution = new double[legHomeLeft().length][];
            configurePlanter(this.footPlanter);
        }
        boolean keyedOnly = !legsFree();
        String devName = DEV_HOOKS && this.hasCustomName() ? this.getCustomName().getString() : "";
        if (devName.equals("keyed")) keyedOnly = true;   // 確かめ用: 手付けの歩きのまま
        boolean near = this.tickCount - this.legsRenderedTick <= 5;
        tunePlanter(this.footPlanter);
        this.footPlanter.tick(this, near && !keyedOnly, footScale());
        this.clientProceduralLegs = this.footPlanter.weight(1.0F) > 0.0F && !keyedOnly;
        if (devName.equals("feet") && this.footPlanter.ready()) {
            for (int i = 0; i < this.footPlanter.legs(); i++) {
                Vec3 f = this.footPlanter.foot(i, 1.0F);
                this.level().addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, f.x, f.y + 0.05D, f.z, 0, 0, 0);
            }
        }
    }

    /** 足の置き方の決まった値(作ったときに 1 回)。 */
    protected void configurePlanter(FootPlanter fp) {
    }

    /** 足の置き方の毎 tick の値(歩幅など。速さで変えるとき)。 */
    protected void tunePlanter(FootPlanter fp) {
    }

    /** 足の置き方に渡す体格(体の大きさ。脚の置き場・踏み替えのずれに掛かる)。 */
    protected double footScale() {
        return this.getScale();
    }

    @Nullable
    public FootPlanter footPlanter() {
        return this.footPlanter;
    }

    public double[][] legSolution() {
        return this.legSolution;
    }

    /** 前のフレームの解が新しい(2 tick 以内)か。 */
    public boolean legSolutionFresh() {
        return this.tickCount - this.legSolvedTick <= 2;
    }

    public void markLegSolution() {
        this.legSolvedTick = this.tickCount;
    }

    public void markLegsRendered() {
        this.legsRenderedTick = this.tickCount;
    }

    /** 描画: 脚を手続き(IK)で置いているか。true の間、脚の層は待機の形を流す。 */
    public boolean proceduralLegs() {
        return this.clientProceduralLegs;
    }

    public BodyBend bend() {
        return this.bend;
    }

    // ------------------------------------------------------------------ 旋回

    /** この tick に向きたい向き(度)。強さ {@code priority} の大きいものが勝つ。回すのは tick の終わり。 */
    public void wantFacing(float yaw, int priority) {
        if (priority >= this.wantedPriority) {
            this.wantedYaw = yaw;
            this.wantedPriority = priority;
        }
    }

    private void tickTurn() {
        if (Float.isNaN(this.heading)) this.heading = this.getYRot();
        if (this.wantedPriority >= 0 && this.wantedPriority < TURN_ACTION && isActing()) this.wantedPriority = -1;
        float max = maxTurnPerTick();
        float delta = this.wantedPriority >= 0 ? Mth.wrapDegrees(this.wantedYaw - this.heading) : 0.0F;
        float want = Mth.clamp(delta, -max, max);
        this.turnSpeed += (want - this.turnSpeed) * turnSmoothing();
        float step = Math.abs(this.turnSpeed) > Math.abs(delta) ? delta : this.turnSpeed;
        if (Math.abs(this.turnSpeed) < 1.0E-3F && this.wantedPriority < 0) this.turnSpeed = 0.0F;
        this.heading = Mth.wrapDegrees(this.heading + step);
        this.wantedPriority = -1;
        applyFacing();
        this.entityData.set(DATA_TURN, Math.round(step * 20.0F) / 20.0F);
    }

    private void applyFacing() {
        this.setYRot(this.heading);
        this.yBodyRot = this.heading;
        this.yHeadRot = this.heading;
    }

    /** 直前の tick に回った角度(度、+ が右回り)。クライアントでも読める。 */
    public float getTurnRate() {
        return this.entityData.get(DATA_TURN);
    }

    /** 向きをいますぐ変える(湧かせた直後・GameTest 用)。 */
    public void snapFacing(float yaw) {
        this.heading = yaw;
        this.turnSpeed = 0.0F;
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yHeadRot = yaw;
        this.yHeadRotO = yaw;
    }

    @Override
    public void moveTo(double x, double y, double z, float yRot, float xRot) {
        super.moveTo(x, y, z, yRot, xRot);
        this.heading = Float.NaN;
        this.turnSpeed = 0.0F;
    }

    @Override
    public void lookAt(Entity entity, float maxYRotIncrease, float maxXRotIncrease) {
        wantFacing((float) (Mth.atan2(entity.getZ() - this.getZ(), entity.getX() - this.getX()) * Mth.RAD_TO_DEG) - 90.0F, TURN_HUNT);
    }

    /** この tick に体が前へ進んだ量(ブロック、クライアントの補間した位置で)。後ろ・横へ押されたぶんは数えない。 */
    public double clientForwardSpeed() {
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return Math.max(0.0D, (this.getX() - this.xo) * -Mth.sin(yaw) + (this.getZ() - this.zo) * Mth.cos(yaw));
    }

    /** この個体から {@code range} ブロック以内にプレイヤー(観戦も含む)がいるか。 */
    public boolean playerWithin(double range) {
        double r2 = range * range;
        for (Player p : this.level().players()) {
            if (p.distanceToSqr(this) <= r2) return true;
        }
        return false;
    }

    /** 体長に合わせて描画の外接箱を広げる(芯の箱だけだと、首・尾が画面に残っているのに消える)。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        double r = cullingRadius();
        return this.getBoundingBox().inflate(r, 0.5D, r);
    }

    /** 描画の外接箱を左右前後へ広げる量(ブロック)。体長の半分くらい。 */
    protected abstract double cullingRadius();

    @Override
    public int getMaxHeadXRot() {
        return 20;
    }

    // ------------------------------------------------------------------ 制御

    /** 移動: 向きは希望を出すだけ。進むのは前へだけ(ずれの cos 倍、下限 {@link #minTurnAdvance})。 */
    final class TankMoveControl extends MoveControl {
        TankMoveControl() {
            super(LongBodyRunner.this);
        }

        @Override
        public void tick() {
            LongBodyRunner f = LongBodyRunner.this;
            this.moving = this.operation == Operation.MOVE_TO;   // この tick に行き先へ進んだか(MOVE_TO はすぐ WAIT に戻すので、後から読む側のために覚える)
            if (this.operation == Operation.MOVE_TO) {
                this.operation = Operation.WAIT;
                double dx = this.wantedX - f.getX();
                double dz = this.wantedZ - f.getZ();
                double dy = this.wantedY - f.getY();
                if (dx * dx + dy * dy + dz * dz < 2.5000003E-7F) {
                    f.setZza(0.0F);
                    return;
                }
                float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                f.wantFacing(want, TURN_MOVE);
                float off = Math.abs(Mth.wrapDegrees(want - f.getYRot()));
                float speed = (float) (this.speedModifier * f.getAttributeValue(Attributes.MOVEMENT_SPEED) * f.gaitBoost());
                f.setSpeed(speed);
                float advance = off > f.maxMoveOff() ? 0.0F : Math.max(Mth.cos(off * Mth.DEG_TO_RAD), f.minTurnAdvance());
                f.setZza(speed * advance);
                f.setXxa(0.0F);
                BlockPos pos = f.blockPosition();
                BlockState state = f.level().getBlockState(pos);
                VoxelShape shape = state.getCollisionShape(f.level(), pos, CollisionContext.of(f));
                if (dy > (double) f.maxUpStep() && dx * dx + dz * dz < (double) Math.max(1.0F, f.getBbWidth())
                        || !shape.isEmpty() && f.getY() < shape.max(Direction.Axis.Y) + (double) pos.getY()
                        && !state.is(BlockTags.DOORS) && !state.is(BlockTags.FENCES)) {
                    f.getJumpControl().jump();
                    this.operation = Operation.JUMPING;
                }
            } else if (this.operation == Operation.JUMPING) {
                f.setSpeed((float) (this.speedModifier * f.getAttributeValue(Attributes.MOVEMENT_SPEED)));
                if (f.onGround()) this.operation = Operation.WAIT;
            } else {
                this.operation = Operation.WAIT;
                f.setZza(0.0F);
                f.setXxa(0.0F);
            }
        }

        /** 今の経路の速さ倍率(navto・うろつきが渡したもの)。 */
        /** この tick に行き先へ進んだか。 */
        boolean moving;

        double speedModifier() {
            return this.speedModifier;
        }
    }

    /** 今の移動の速さ倍率(経路を進んでいる間。止まっていれば 0)。 */
    protected double currentSpeedModifier() {
        return this.moveControl instanceof TankMoveControl t && t.moving && !this.getNavigation().isDone() ? t.speedModifier() : 0.0D;
    }

    /** 視線: 頭だけ振り向かず、見たい向きを {@link #TURN_LOOK} の希望として出す。 */
    final class TankLookControl extends LookControl {
        TankLookControl() {
            super(LongBodyRunner.this);
        }

        @Override
        public void tick() {
            LongBodyRunner f = LongBodyRunner.this;
            if (this.lookAtCooldown > 0) {
                this.lookAtCooldown--;
                this.getYRotD().ifPresent(yaw -> f.wantFacing(yaw, TURN_LOOK));
                this.getXRotD().ifPresent(pitch -> f.setXRot(this.rotateTowards(f.getXRot(),
                        Mth.clamp(pitch, -f.getMaxHeadXRot(), f.getMaxHeadXRot()), this.xMaxRotAngle)));
            } else {
                f.setXRot(this.rotateTowards(f.getXRot(), 0.0F, 5.0F));
            }
            f.yHeadRot = f.getYRot();
        }
    }

    /** 体・頭は常に {@code yRot} に揃える。{@code Mob} の構築子の中で作られるので、このクラスのフィールドに触れない。 */
    static final class TankBodyControl extends BodyRotationControl {
        private final LongBodyRunner mob;

        TankBodyControl(LongBodyRunner mob) {
            super(mob);
            this.mob = mob;
        }

        @Override
        public void clientTick() {
            this.mob.yBodyRot = this.mob.getYRot();
            this.mob.yHeadRot = this.mob.getYRot();
        }
    }

    /**
     * 標的のいない間、森の中を歩き回る(ファルキムネスの ForestStrollGoal と同じ)。バニラのうろつきは「動きの無い時間」が 100 を超えると始まらず、
     * それを 0 に戻すのはサバイバルのプレイヤーだけなので、ここでは代わりに {@link #STROLL_RANGE} 以内にだれか(創造・観戦も)いるときだけ始める。
     * 確かめ用: 名前が {@code nostroll} で始まる個体はうろつかない(テストクライアントの navto を上書きしない)。
     */
    public static class StrollGoal extends RandomStrollGoal {
        private final LongBodyRunner runner;

        public StrollGoal(LongBodyRunner mob, double speed) {
            super(mob, speed, STROLL_INTERVAL, false);
            this.runner = mob;
        }

        @Override
        public boolean canUse() {
            if (this.runner.hasCustomName() && this.runner.getCustomName().getString().startsWith("nostroll")) return false;
            return !this.runner.isActing() && this.runner.playerWithin(STROLL_RANGE) && super.canUse();
        }

        @Nullable
        @Override
        protected Vec3 getPosition() {
            Vec3 p = LandRandomPos.getPos(this.mob, STROLL_XZ, STROLL_Y);
            return p == null && this.mob.isInWaterOrBubble() ? super.getPosition() : p;
        }
    }
}
