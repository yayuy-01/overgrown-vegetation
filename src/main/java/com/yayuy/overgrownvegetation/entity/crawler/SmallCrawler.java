package com.yayuy.overgrownvegetation.entity.crawler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 移動の型「小さな地這いの虫」の土台(2026-11-14〜15、1 体目はグレガリア。ストロビラ・ヴェシカリア・トリツキも継ぐ予定)。
 * 種に依らない部品だけを持つ。種は値(回る速さ・うろつきの速さ・小走りの長さ)を返す。
 *
 * <h2>型の決まり(11-15 ユーザー了承。値は全部【仮・Claude】)</h2>
 * <ul>
 *   <li><b>脚は手付け</b>: 1 本ずつの IK はしない(11-06「小さな虫は軽い版」・1 万の桁の群れ)。歩きのアニメを実際の速さに合わせて流すのは種の側。</li>
 *   <li><b>経路はバニラの地面のナビ</b>。壁は登らない。1 ブロックの段は跳ばずに這い上がる(段差の属性 1.0)。柵・2 段の壁は越えられない = 弱み。
 *       水は避け、落ちたら浮く({@link FloatGoal})。</li>
 *   <li><b>回るのが速い</b>: 1 tick に {@link #turnPerTick()} 度まで。向きが大きくずれている間は前へ出る速さを落とす(横へ滑らない)。
 *       体・頭は常に進む向き(虫は首を振らない)。</li>
 *   <li><b>うろつきは小走りと一時停止のくり返し</b>({@link ScurryGoal})。近くにプレイヤー(創造・観戦も)がいるときだけ。</li>
 *   <li><b>仲間どうしは押し合わない</b>(少し重なってよい・詰め込みのダメージも仲間は数えない)。真ん中が近すぎるときだけ弱く離す。
 *       プレイヤー・ほかの Mob とは今までどおり押し合う。</li>
 *   <li><b>段の上り下りで体を前後に傾ける</b>(クライアント・見た目だけ)。足元の前後の地面の高さから。段を這い上がる 1 tick の跳ねも描画でならす。</li>
 * </ul>
 * 群れ(swarm/ は parked)はまだ。群れが舵を取るときは {@link #isSteered()} を真にしてうろつきを止める口だけある。
 */
public abstract class SmallCrawler extends Monster {

    /** テストクライアントの命令を拾う口(-Dov.devbridge=true のときだけ)。 */
    protected static final boolean DEV_HOOKS = Boolean.getBoolean("ov.devbridge");

    /** うろつく(=重さを使う)のはこの距離にプレイヤーがいるときだけ(ブロック)【仮】。 */
    public static final double STROLL_RANGE = 64.0D;
    /** 体を傾ける計算をするのは、いちばん近いプレイヤーからこの距離まで(クライアント)【仮】。 */
    static final double TILT_RANGE = 48.0D;
    /** 傾きの上限(度)・寄る速さ(1 tick あたりの割合)【仮】。 */
    static final float TILT_MAX = 40.0F, TILT_RATE = 0.35F;
    /** 段を這い上がった 1 tick の跳ねを、描画で何 tick かけてならすか【仮】。 */
    static final float LIFT_TICKS = 4.0F;
    /** 仲間を離す: 真ん中どうしがこれ(体の幅に掛ける)より近いときだけ、弱く押す【仮】。 */
    static final double SEPARATE_SHARE = 0.45D, SEPARATE_PUSH = 0.012D;

    // --- クライアント: 傾き・段のならし ---
    private float tilt, tiltO;
    private float lift, liftO;
    private double lastY = Double.NaN;
    private float moveSpeed, moveSpeedO;

    protected SmallCrawler(EntityType<? extends SmallCrawler> type, Level level) {
        super(type, level);
        this.moveControl = new CrawlerMoveControl(this);
        this.setPathfindingMalus(PathType.WATER, -1.0F);
        this.setPathfindingMalus(PathType.WATER_BORDER, 4.0F);
    }

    // ------------------------------------------------------------------ 種が決める値

    /** 1 tick に回れる角度(度)。 */
    protected abstract float turnPerTick();

    /** うろつきの速さ(移動の速さの属性に掛ける)。 */
    protected abstract double strollSpeed();

    /** 小走り 1 回の長さ(tick)の幅 {最小, 最大}。 */
    protected int[] dashTicks() {
        return new int[]{10, 30};
    }

    /** 小走りの間の一時停止の長さ(tick)の幅 {最小, 最大}。 */
    protected int[] pauseTicks() {
        return new int[]{6, 24};
    }

    /**
     * 急ぎ(追う・逃げる・突撃など、うろつきより速い移動)の速さ(移動の速さの属性に掛ける)。既定 1.0。
     * 種ごとに変える口(11-15 ヴェシカリアの段 1 で足した。土台の中ではまだ誰も使わない = 今までの動きは変わらない)。
     */
    public double rushSpeed() {
        return 1.0D;
    }

    /** 群れなどが舵を取っている間は真(うろつきを止める)。群れ({@link CrawlerPack})の後続・相手のいる群れでは真。 */
    public boolean isSteered() {
        CrawlerPack p = this.pack;
        return p != null && !p.members.isEmpty() && (p.target() != null || p.leader() != this);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        if (usesPack()) {
            this.goalSelector.addGoal(3, new CrawlerPack.ChaseGoal(this));
            this.goalSelector.addGoal(5, new CrawlerPack.FollowGoal(this));
        }
        this.goalSelector.addGoal(6, new ScurryGoal(this));
    }

    // ------------------------------------------------------------------ 群れ(使う種だけ。2026-11-16)

    /** 群れ({@link CrawlerPack})に入るか。既定 false(ストロビラ・ヴェシカリアの動きは変えない)。 */
    protected boolean usesPack() {
        return false;
    }

    /**
     * 噛むなどで、群れの囲む位置でなく決まった所へ舵を切る間の行き先(null = ふつうに囲む)。今いる所を返すとその場で止まる。既定 null。
     */
    @Nullable
    protected Vec3 lungePoint(LivingEntity target) {
        return null;
    }

    /** 群れが狙う相手か。既定: プレイヤーと植物側以外の生き物(ほかの G4・G5 と同じ)。 */
    protected boolean isPackPrey(LivingEntity e) {
        return com.yayuy.overgrownvegetation.entity.AcumnesEntity.isPrey(this, e);
    }

    /** 名前に notarget を含む個体は相手を探さない・nopack は群れに入らない(GameTest で隣のテストの Mob を狙わないように)。 */
    boolean huntsDisabled() {
        return com.yayuy.overgrownvegetation.entity.DevNames.has(this, "notarget");
    }

    private boolean packDisabled() {
        return com.yayuy.overgrownvegetation.entity.DevNames.has(this, "nopack");
    }

    @Nullable
    private CrawlerPack pack;
    @Nullable
    private UUID packId;

    /** 入っている群れ(サーバー。無ければ null)。 */
    @Nullable
    public CrawlerPack pack() {
        return this.pack;
    }

    /** 群れの毎 tick(サーバー): 入る・組み直す・近くの群れへ合わさる・先導役なら相手を見直す・群れの標的を自分の標的にする。 */
    private void packTick(ServerLevel level) {
        // 群れに入っているかの確かめは 20 tick に 1 回(contains は群れの大きさぶん見る。外れるのは群れが消えたときだけ)
        if (this.pack == null || (this.tickCount + this.getId()) % 20 == 0 && !this.pack.members.contains(this)) {
            if (this.packId == null) {
                CrawlerPack near = CrawlerPack.nearby(level, this, null);
                this.packId = near != null ? near.id : UUID.randomUUID();
            }
            this.pack = CrawlerPack.obtain(level, this.packId, this.getType());
            this.pack.add(this);
        }
        CrawlerPack p = this.pack;
        p.prune(level);
        if (p.size() == 1 && (this.tickCount + this.getId()) % CrawlerPack.JOIN_EVERY == 0) {
            CrawlerPack other = CrawlerPack.nearby(level, this, p);
            if (other != null) {
                p.remove(level, this);
                other.add(this);
                this.pack = other;
                this.packId = other.id;
                p = other;
            }
        }
        if (p.leader() == this) p.leaderTick(level, this, (self, e) -> self.isPackPrey(e));
        LivingEntity t = p.target();
        if (t != this.getTarget()) this.setTarget(t);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (usesPack() && !packDisabled() && this.level() instanceof ServerLevel sl) packTick(sl);
    }

    /** 叩かれたら叩いた相手を群れの標的にする。 */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && this.pack != null && source.getEntity() instanceof LivingEntity a && isPackPrey(a)) this.pack.setTarget(a);
        return hurt;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.pack != null && this.level() instanceof ServerLevel sl) this.pack.remove(sl, this);
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.packId != null) tag.putUUID("OvPack", this.packId);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("OvPack")) this.packId = tag.getUUID("OvPack");
    }

    // ------------------------------------------------------------------ 向き

    /** 体・頭は進む向きにそろえる(虫は首を振らない。バニラの体の遅れも無くす)。 */
    @Override
    protected float tickHeadTurn(float yRot, float animStep) {
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
        return animStep;
    }

    @Override
    public int getMaxHeadYRot() {
        return 0;
    }

    /** 置いた直後などに向きを一度にそろえる(テスト用)。 */
    public void snapFacing(float yaw) {
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yHeadRot = yaw;
        this.yHeadRotO = yaw;
    }

    // ------------------------------------------------------------------ 押し合い

    /**
     * バニラの押し合いから仲間(同じ型の虫)を外す。仲間は詰め込みの数にも入れない。真ん中が近すぎる仲間だけ弱く離す
     * (1 回の問い合わせで両方を済ませる。問い合わせの箱は体の箱そのまま = バニラと同じ重さ)。
     */
    @Override
    protected void pushEntities() {
        Level level = this.level();
        if (level.isClientSide()) {
            super.pushEntities();   // クライアントはプレイヤーだけ(バニラのまま)
            return;
        }
        List<Entity> list = level.getEntities(this, this.getBoundingBox(), EntitySelector.pushableBy(this));
        if (list.isEmpty()) return;
        int others = 0;
        for (Entity e : list) {
            if (!(e instanceof SmallCrawler) && !e.isPassenger()) others++;
        }
        int cram = level.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
        if (cram > 0 && others > cram - 1 && this.random.nextInt(4) == 0) {
            this.hurt(this.damageSources().cramming(), 6.0F);
        }
        double near = this.getBbWidth() * SEPARATE_SHARE;
        double sx = 0.0D, sz = 0.0D;
        for (Entity e : list) {
            if (e instanceof SmallCrawler) {
                double dx = this.getX() - e.getX(), dz = this.getZ() - e.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 >= near * near) continue;
                if (d2 < 1.0E-6D) {
                    float a = this.random.nextFloat() * Mth.TWO_PI;
                    dx = Mth.cos(a);
                    dz = Mth.sin(a);
                    d2 = 1.0D;
                }
                double d = Math.sqrt(d2);
                sx += dx / d;
                sz += dz / d;
            } else {
                this.doPush(e);
            }
        }
        // 近すぎる仲間の向きを足して、合わせて SEPARATE_PUSH だけ押す(何体重なっていても押しの強さは変わらない)
        double s = Math.sqrt(sx * sx + sz * sz);
        if (s > 1.0E-6D) this.push(sx / s * SEPARATE_PUSH, 0.0D, sz / s * SEPARATE_PUSH);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide()) tickClientPose();
    }

    private float tiltGoal;
    private final BlockPos.MutableBlockPos groundPos = new BlockPos.MutableBlockPos();

    private void tickClientPose() {
        this.tiltO = this.tilt;
        this.liftO = this.lift;
        this.moveSpeedO = this.moveSpeed;
        double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
        this.moveSpeed = (float) Math.sqrt(dx * dx + dz * dz);
        // 段を這い上がった・下りた tick の跳ね: 描画では今の高さとの差を何 tick かかけて消す
        double y = this.getY();
        if (!Double.isNaN(this.lastY) && this.onGround()) {
            double dy = y - this.lastY;
            if (Math.abs(dy) > 0.3D && Math.abs(dy) < 1.3D) this.lift -= (float) dy;
        }
        this.lastY = y;
        this.lift = approach(this.lift, 0.0F, 1.0F / LIFT_TICKS);
        // 観戦も数える(getNearestPlayer は観戦を外すので、観戦で見ていると傾かなかった。11-15)
        // 地面の見直しは 4 tick に 1 回(個体ごとにずらす。間は前の目標へ寄せ続ける。11-16 見直し 第 3 便: 数百体で毎 tick 2 列ずつ見ていた)
        if (((this.tickCount + this.getId()) & 3) == 0) this.tiltGoal = playerWithin(TILT_RANGE) ? groundTilt() : 0.0F;
        this.tilt += (this.tiltGoal - this.tilt) * TILT_RATE;
    }

    private static float approach(float v, float to, float step) {
        return v > to ? Math.max(to, v - step) : Math.min(to, v + step);
    }

    /** 体の前後(体長の半分ずつ)の地面の高さの差から、前を上げる角度(度・正 = 頭が上)。 */
    private float groundTilt() {
        if (!this.onGround()) return this.tilt * 0.9F;
        float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        double half = bodyHalfLength();
        double front = groundAt(this.getX() + fx * half, this.getZ() + fz * half);
        double back = groundAt(this.getX() - fx * half, this.getZ() - fz * half);
        if (Double.isNaN(front) || Double.isNaN(back)) return 0.0F;
        float deg = (float) Math.toDegrees(Math.atan2(front - back, half * 2.0D));
        return Mth.clamp(deg, -TILT_MAX, TILT_MAX);
    }

    /** 体長の半分(ブロック)。傾きの前後の点の距離。 */
    protected double bodyHalfLength() {
        return 0.5D;
    }

    /** (x, z) の列で、今の足の高さ ±1.2 の中のいちばん上の固い面の高さ。無ければ NaN。 */
    private double groundAt(double x, double z) {
        Level level = this.level();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        double feet = this.getY();
        BlockPos.MutableBlockPos pos = this.groundPos;
        for (int by = Mth.floor(feet + 1.2D); by >= Mth.floor(feet - 1.2D); by--) {
            pos.set(bx, by, bz);
            BlockState s = level.getBlockState(pos);
            VoxelShape shape = s.getCollisionShape(level, pos);
            if (shape.isEmpty()) continue;
            double top = by + shape.max(net.minecraft.core.Direction.Axis.Y);
            if (top <= feet + 1.2D) return top;
        }
        return Double.NaN;
    }

    /** 描画: 体の前を上げる角度(度)。 */
    public float tilt(float pt) {
        return Mth.lerp(pt, this.tiltO, this.tilt);
    }

    /** 描画: 段のならし(ブロック。足の高さに足す)。 */
    public float lift(float pt) {
        return Mth.lerp(pt, this.liftO, this.lift);
    }

    /** クライアント: この tick に水平に進んだ量(ブロック)。 */
    public float clientMoveSpeed(float pt) {
        return Mth.lerp(pt, this.moveSpeedO, this.moveSpeed);
    }

    // ------------------------------------------------------------------ 近くのプレイヤー

    /** 創造・観戦も数える(バニラのうろつきはこれを数えず、見ていると止まって見える)。 */
    public boolean playerWithin(double range) {
        double r2 = range * range;
        for (Player p : this.level().players()) {
            if (p.distanceToSqr(this) <= r2) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 移動の手当て

    /**
     * バニラの MoveControl の回る速さ(90 度/tick)を {@link #turnPerTick()} に抑え、向きが大きくずれている間は前へ出る速さを落とす
     * (ずれ 0 で 1 倍・90 度で 0.15 倍)。そのほか(段の判定・跳ぶ)はバニラのまま。
     */
    static final class CrawlerMoveControl extends MoveControl {
        private final SmallCrawler crawler;

        CrawlerMoveControl(SmallCrawler mob) {
            super(mob);
            this.crawler = mob;
        }

        @Override
        public void tick() {
            boolean moving = this.operation == Operation.MOVE_TO;
            float before = this.mob.getYRot();
            double wx = this.wantedX, wz = this.wantedZ;
            super.tick();
            if (!moving) return;
            double dx = wx - this.mob.getX(), dz = wz - this.mob.getZ();
            if (dx * dx + dz * dz < 1.0E-6D) return;
            float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            float limit = this.crawler.turnPerTick();
            float yaw = before + Mth.clamp(Mth.wrapDegrees(want - before), -limit, limit);
            this.mob.setYRot(yaw);
            float off = Math.abs(Mth.wrapDegrees(want - yaw));
            float k = off >= 90.0F ? 0.15F : Math.max(0.15F, Mth.cos(off * Mth.DEG_TO_RAD));
            this.mob.setSpeed(this.mob.getSpeed() * k);
        }
    }

    /**
     * うろつき = 小走りと一時停止のくり返し(虫らしさと「足元がざわめく」ため。11-15 ユーザー了承)。
     * 1 回動き出すと、近くの点(8 ブロック内)へ向けて {@link #dashTicks()} 走り、{@link #pauseTicks()} 止まる、を 2〜5 回くり返す。
     * 止まるたびに行き先を選び直すので、向きがぱっと変わる。
     */
    public static class ScurryGoal extends Goal {
        protected final SmallCrawler mob;
        private int bursts;
        private int timer;
        private boolean dashing;

        public ScurryGoal(SmallCrawler mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (this.mob.isSteered() || this.mob.isVehicle() || this.mob.getTarget() != null) return false;
            if (com.yayuy.overgrownvegetation.entity.DevNames.noStroll(this.mob)) return false;
            if (this.mob.getRandom().nextInt(reducedTickDelay(40)) != 0) return false;
            return this.mob.playerWithin(STROLL_RANGE);
        }

        @Override
        public boolean canContinueToUse() {
            return this.bursts > 0 && !this.mob.isSteered() && this.mob.getTarget() == null;
        }

        @Override
        public void start() {
            this.bursts = 2 + this.mob.getRandom().nextInt(4);
            startDash();
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
            this.bursts = 0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (--this.timer > 0 && !(this.dashing && this.mob.getNavigation().isDone())) return;
            if (this.dashing) {
                this.mob.getNavigation().stop();
                this.dashing = false;
                int[] p = this.mob.pauseTicks();
                this.timer = p[0] + this.mob.getRandom().nextInt(Math.max(1, p[1] - p[0] + 1));
                this.bursts--;
            } else {
                startDash();
            }
        }

        private void startDash() {
            Vec3 p = pickSpot();
            int[] d = this.mob.dashTicks();
            this.timer = d[0] + this.mob.getRandom().nextInt(Math.max(1, d[1] - d[0] + 1));
            this.dashing = p != null && this.mob.getNavigation().moveTo(p.x, p.y, p.z, this.mob.strollSpeed());
            if (!this.dashing) this.timer = 10;
        }

        @Nullable
        protected Vec3 pickSpot() {
            return LandRandomPos.getPos(this.mob, 8, 3);
        }
    }
}
