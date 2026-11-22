package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 漂流する擬態 Mob {@code drift_mimic}(2026-11-20。植物種・型は拡散。危険度は低め【仮・全体管理】)。
 * 流木・藻の塊に化けて海を渡り、着いた岸に根を下ろして汚染の出発点(芯なし)になる。Vault「地形に合った汚染の生え方(11-19)」11-20 の節。
 *
 * <h2>動き(Goal は使わない。{@link #travel}・{@link #customServerAiStep} に置いた)</h2>
 * <ul>
 *   <li><b>浮く</b>: 体の位置の水の深さで浮き沈みする(海面の高さ 63 は見ない)。</li>
 *   <li><b>流れる</b>: ふだんは卓越風({@link DriftWind})の風下へ、{@link DriftTable.Drift#blocksPerWind} の速さで。</li>
 *   <li><b>寄る</b>: {@link DriftTable.Drift#seekEvery} tick ごとに半径 {@link DriftTable.Drift#seekRadius} の岸を探し、見つけたら水の中の根で漕いで寄る。
 *       流れ始めてしばらくは汚染されたチャンクの岸(出てきた岸)を選ばない。押し返しで断られた岸のチャンクも選ばない。</li>
 *   <li><b>根付く</b>: 岸に着いたら {@link #rootAt} —— 乗せていた Mob を降ろし、萌芽体の根付いた姿(芯なし)と芽の点を置き、
 *       そのチャンクへ胞子を降らせ、島なら腫れた島({@link SwollenIslands})を始め、自分は消える(【仮】根付いて消える。根付いた株が出発点として残る)。</li>
 *   <li><b>押し返しの効く岸</b>({@link RingWards#guarded})には根付かず、向きを変えて流れ続ける【仮】。</li>
 *   <li><b>乗せて運ぶ</b>({@link Mode#FERRY}): 行き先の岸へまっすぐ漕ぐ。乗せる席は {@link DriftTable.Ferry#seats}。</li>
 *   <li><b>読み込みの外</b>: 流れていく先がエンティティの動かない所で、近くにプレイヤーがいなければ、{@link DriftLedger} の帳面へ移って
 *       読み込みの外を風で粗く流れ、読み込まれた所に着いたら姿に戻る。</li>
 * </ul>
 *
 * <p>形はモデル制作の B 改 6(11-22 ユーザー決定。{@code client/DriftMimicRenderer})。ふだんは藻と流木の筏に見え、観戦でないプレイヤー
 * (植物側の者は除く)が {@link DriftTable.Drift#revealDistance} 以内に来ると胞子嚢の塊が膨らんで光り、鉤が上がる(正体を見せる。11-22 全体管理２)。
 * 攻撃はしない。当たりは真ん中の胞子嚢の塊だけで、壊す = 倒す(沈んで、乗せていた Mob は水へ落ちる)。
 * 岸に着いたら鉤で岸をつかみ(grip 2.5 秒)、塊が破裂して(burst)胞子を撒き、根付いて消える。
 */
public class DriftMimicEntity extends Monster implements ParasitizedMob, GeoEntity {

    public static final String ID = "drift_mimic";

    public enum Mode { DRIFT, SEEK, FERRY }

    // ------------------------------------------------------------ 見た目の状態(サーバーが決めて送る。アニメと光はこれで流す)

    static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(DriftMimicEntity.class, EntityDataSerializers.BYTE);
    /** 正体を見せている(嚢が膨らんで光る・鉤が上がる)。 */
    static final byte F_REVEALED = 1;
    /** 岸へ漕いでいる(swim)。 */
    static final byte F_PADDLING = 2;
    /** 岸に鉤を掛けている(grip)。 */
    static final byte F_GRIPPING = 4;
    /** 胞子嚢の塊が破裂している(burst)。 */
    static final byte F_BURSTING = 8;

    /** 着いてからの段取り(tick): 鉤を掛ける grip 2.5 秒 → 破裂 burst 3.0 秒。破裂のしぼむ瞬間(burst の 1.45 秒)に胞子を撒いて根付く。 */
    public static final int GRIP_TICKS = 50, BURST_PEAK_TICKS = 29, BURST_TICKS = 60;

    /** 着いた岸(破裂を待っている間)。null なら海の上。 */
    @Nullable
    BlockPos arrivalShore, arrivalStand;
    int arrivalTick;

    Mode mode = Mode.DRIFT;
    /** 寄っていく岸の列(y は水面の段)。 */
    @Nullable
    BlockPos target;
    /** 生まれた時刻(gameTime)。 */
    long born = Long.MIN_VALUE;
    int stranded;
    /** 押し返しで断られた・立てる所が無かった岸のチャンク。 */
    final LongOpenHashSet refused = new LongOpenHashSet();
    /** 確かめ用: 風を差し替える(ブロック/tick ではなく、風の強さ × 向き)。null なら卓越風。 */
    @Nullable
    public Vec3 forcedWind;
    /** 確かめ用: 岸探しの半径を差し替える(負なら設計値)。 */
    public int seekRadiusOverride = -1;
    /** 確かめ用: 読み込みの外へ移らない。 */
    public boolean stayMaterial;
    /** 確かめ用: 要る水の深さ・体の半径・鉤の届く距離を差し替える(負なら設計値。テスト場の池は浅い)。 */
    public int minDepthOverride = -1;
    public double bodyRadiusOverride = -1.0D, gripReachOverride = -1.0D;
    /** この tick、進む先が浅くて止まった。 */
    boolean shallowAhead;

    int minDepth() {
        return minDepthOverride >= 0 ? minDepthOverride : DesignData.drift().drift.minDepth;
    }

    double bodyRadius() {
        return bodyRadiusOverride >= 0 ? bodyRadiusOverride : DesignData.drift().drift.bodyRadius;
    }

    double gripReach() {
        return gripReachOverride >= 0 ? gripReachOverride : DesignData.drift().drift.gripReach;
    }

    public DriftMimicEntity(EntityType<? extends DriftMimicEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = MobStats.xpReward(ID, 3);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_FLAGS, (byte) 0);
    }

    boolean flag(byte f) {
        return (entityData.get(DATA_FLAGS) & f) != 0;
    }

    void setFlag(byte f, boolean on) {
        byte v = entityData.get(DATA_FLAGS);
        byte n = (byte) (on ? v | f : v & ~f);
        if (n != v) entityData.set(DATA_FLAGS, n);
    }

    /** 正体を見せているか。 */
    public boolean revealed() {
        return flag(F_REVEALED);
    }

    /** 岸で鉤を掛けている印(描画・GameTest から)。 */
    public boolean gripping() {
        return flag(F_GRIPPING);
    }

    /** 破裂している印(描画・GameTest から)。 */
    public boolean bursting() {
        return flag(F_BURSTING);
    }

    /** 岸に着いて、鉤を掛けている・破裂している最中か。 */
    public boolean arriving() {
        return arrivalStand != null || flag(F_GRIPPING) || flag(F_BURSTING);
    }

    // ------------------------------------------------------------ 状態

    public Mode mode() {
        return mode;
    }

    @Nullable
    public BlockPos target() {
        return target;
    }

    /** 乗せて運ぶ: 行き先の岸の列へまっすぐ漕ぐ(y は水面の段)。 */
    public void ferryTo(BlockPos shoreColumn) {
        this.mode = Mode.FERRY;
        this.target = shoreColumn.immutable();
    }

    /** 断った岸のチャンクの数(確かめ用)。 */
    public int refusedCount() {
        return refused.size();
    }

    long age() {
        return born == Long.MIN_VALUE ? 0L : level().getGameTime() - born;
    }

    // ------------------------------------------------------------ 体

    @Override
    protected void registerGoals() {
        // 動きは travel / customServerAiStep に置く(Goal は使わない)
    }

    @Override
    public boolean canDrownInFluidType(net.minecraftforge.fluids.FluidType type) {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < Math.max(0, DesignData.drift().ferry.seats);
    }

    /** 乗った Mob に舵を取らせない(Mob の既定は 1 人目の Mob が舵を取る)。 */
    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }

    /**
     * 席 6 つ(ユーザー決定 11-20)。位置は形 B 改 6(座は B 改 4 から変わらない)の座の骨 seat_0〜5(1/16 ブロック。前 = −z、左 = +x、水面 = y 0。モデル制作の報告)。
     * 体の当たりの底は水面の {@link #SUBMERGE} 下にある。
     */
    static final double[][] SEATS = {{82, 7, 2}, {86, 7, 34}, {80, 7, 64}, {-84, 4, 22}, {-86, 4, 54}, {-6, 6, 86}};
    /** 当たりの底が水面より沈む深さ(ブロック)。 */
    static final double SUBMERGE = 0.2D;
    /**
     * 形の胞子嚢の塊(sac_cluster、body の (0, 3, −14))は体の真ん中より 14/16 前にある。当たり(= 塊)を本体の位置の真ん中に置くので、
     * 形はこの分だけ後ろへずらして描く(描画 {@code client/DriftMimicRenderer})。席も同じだけずらす。
     */
    public static final double MODEL_BACK = 14.0D / 16.0D;

    /**
     * 描くかどうかを決める箱の広さ(11-22 見直し 13)。当たりは塊の 4.5 だけなので、そのままだと塊が画面の外へ出たとたんに、
     * まだ見えている筏の縁・水中の腕が消える。形 B 改 6 の geo で数えた: 横は {@link #MODEL_BACK} のずらしを入れて半径 約 9.8、
     * 腕は水面の 約 8.8 下まで、上は 約 1.1。アニメの揺れの分を少し足す。
     */
    static final double CULL_RADIUS = 10.5D, CULL_DOWN = 9.5D, CULL_UP = 2.0D;

    @Override
    public AABB getBoundingBoxForCulling() {
        double x = getX(), y = getY(), z = getZ();
        return new AABB(x - CULL_RADIUS, y - CULL_DOWN, z - CULL_RADIUS, x + CULL_RADIUS, y + CULL_UP, z + CULL_RADIUS);
    }

    /**
     * 1.20.1: 乗り手の位置の決め方(getPassengerAttachmentPoint)が無いので、positionRider で同じ所に置く。
     * 乗り手の側の差し引き(1.20.5 の getVehicleAttachmentPoint)は、1.20.1 の {@code getMyRidingOffset} で近づける。
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction callback) {
        if (!hasPassenger(passenger)) return;
        Vec3 v = getPassengerAttachmentPoint(passenger, getDimensions(getPose()), 1.0F);
        callback.accept(passenger, getX() + v.x, getY() + v.y + passenger.getMyRidingOffset(), getZ() + v.z);
    }

    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        int i = Math.max(0, getPassengers().indexOf(passenger)) % SEATS.length;
        double[] s = SEATS[i];
        // 体の向き: 本体の +z が前(バニラ)。形の前は −z なので z を裏返す
        return new Vec3(s[0] / 16.0D, SUBMERGE + s[1] / 16.0D, -s[2] / 16.0D - MODEL_BACK).yRot(-getYRot() * Mth.DEG_TO_RAD);
    }

    // ------------------------------------------------------------ 動き

    private final double[] wind = new double[2];
    /** 自分で持つ横の速さ(ブロック/tick)。 */
    double driftVx, driftVz;

    /** 今の横の流れ(読みの外へ移る向き・ぶつかった先を見る)。 */
    public Vec3 driftVelocity() {
        return new Vec3(driftVx, 0.0D, driftVz);
    }

    @Override
    public void travel(Vec3 input) {
        if (!level().isClientSide && isDeadOrDying()) {
            // 倒れた: 沈む(11-22 ユーザー決定。沈む姿勢のアニメ death はモデル制作から。移動はここ)
            setDeltaMovement(0.0D, -SINK_SPEED, 0.0D);
            move(MoverType.SELF, getDeltaMovement());
            return;
        }
        if (level().isClientSide || !isInWater()) {
            super.travel(input);
            return;
        }
        DriftTable.Drift t = DesignData.drift().drift;
        DriftWind.now((ServerLevel) level(), forcedWind, wind);
        double s = Math.min(t.windCap, Math.hypot(wind[0], wind[1]));
        double wl = Math.hypot(wind[0], wind[1]);
        double dx = wl > 1e-9 ? wind[0] / wl * s * t.blocksPerWind : 0.0D, dz = wl > 1e-9 ? wind[1] / wl * s * t.blocksPerWind : 0.0D;
        boolean holding = arrivalStand != null; // 鉤を掛けている・破裂している間はその場で浮く
        if (holding) {
            dx = 0.0D;
            dz = 0.0D;
        }
        if (!holding && target != null && (mode == Mode.SEEK || mode == Mode.FERRY)) {
            double tx = target.getX() + 0.5D - getX(), tz = target.getZ() + 0.5D - getZ();
            double d = Math.hypot(tx, tz);
            if (d > 1e-6) {
                dx = dx * 0.3D + tx / d * t.paddleSpeed;
                dz = dz * 0.3D + tz / d * t.paddleSpeed;
            }
        }
        // 浅瀬の手前で止まる(体の水中の房が底に着く所へは進まない)
        shallowAhead = false;
        double ml = Math.hypot(dx, dz);
        if (ml > 1e-9) {
            int wy = DriftShore.waterSurfaceY((ServerLevel) level(), blockPosition());
            double r = bodyRadius() + 1.0D;
            if (wy != Integer.MIN_VALUE && DriftShore.depth((ServerLevel) level(), Mth.floor(getX() + dx / ml * r), Mth.floor(getZ() + dz / ml * r), wy, minDepth()) < minDepth()) {
                shallowAhead = true;
                dx = 0.0D;
                dz = 0.0D;
            }
        }
        // 横の速さは自分で持つ(バニラは毎 tick 0.003 未満の速さを 0 にするので、deltaMovement でゆっくり加速させると動き出せない)
        Vec3 v = getDeltaMovement();
        double k = Mth.clamp(t.inertia, 0.0D, 1.0D);
        driftVx += (dx - driftVx) * k;
        driftVz += (dz - driftVz) * k;
        double fh = getFluidHeight(FluidTags.WATER);
        double vy = v.y * 0.7D + (fh - SUBMERGE) * 0.08D;
        setDeltaMovement(driftVx, Mth.clamp(vy, -0.1D, 0.1D), driftVz);
        move(MoverType.SELF, getDeltaMovement());
        // 体の向きは流れる向きへゆっくり(流木は向きをすぐ変えない)
        Vec3 m = driftVelocity();
        if (m.horizontalDistanceSqr() > 1e-6) {
            float want = (float) (Mth.atan2(m.z, m.x) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.approachDegrees(getYRot(), want, 1.5F);
            setYRot(yaw);
            setYBodyRot(yaw);
            setYHeadRot(yaw);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        ServerLevel level = (ServerLevel) level();
        if (born == Long.MIN_VALUE) born = level.getGameTime();
        DriftTable.Drift t = DesignData.drift().drift;
        if (arrivalStand != null) {
            tickArrival(level);
            return;
        }
        if ((tickCount + getId()) % 10 == 0) setFlag(F_REVEALED, revealTo(level, t.revealDistance));
        setFlag(F_PADDLING, target != null && (mode == Mode.SEEK || mode == Mode.FERRY) && !shallowAhead);
        if (age() > (long) (t.lifetimeDays * 24000.0D)) {
            ejectRiders(null);
            discard(); // 岸に着けないまま沈んだ
            return;
        }
        if (isInWater()) {
            stranded = 0;
            if (mode != Mode.FERRY && (tickCount + getId()) % Math.max(1, t.seekEvery) == 0) seek(level, t);
            if (target != null) {
                double d = Math.hypot(target.getX() + 0.5D - getX(), target.getZ() + 0.5D - getZ());
                if (d <= gripReach()) {
                    rootAt(level, target); // 鉤の房が届く: 岸をつかんで根付く
                    return;
                }
                if (shallowAhead || horizontalCollision) {
                    // 浅瀬に阻まれて鉤が届かない: 届く岸があればそこをつかむ。無ければ(寄る岸なら)この岸を断る
                    BlockPos near = landWithinGrip(level);
                    if (near != null) {
                        rootAt(level, near);
                        return;
                    }
                    if (mode == Mode.SEEK) {
                        refused.add(net.minecraft.world.level.ChunkPos.asLong(target.getX() >> 4, target.getZ() >> 4));
                        target = null;
                        mode = Mode.DRIFT;
                        DriftStats.refused++;
                    }
                }
            } else if (shallowAhead || horizontalCollision) {
                // 風に流されて浅瀬・岸に着いた: 鉤の届く岸をつかむ
                BlockPos hit = landWithinGrip(level);
                if (hit != null) {
                    rootAt(level, hit);
                    return;
                }
            }
            if (!stayMaterial && (tickCount + getId()) % 20 == 0 && DriftLedger.shouldGoVirtual(level, this)) {
                DriftLedger.store(level, this);
            }
        } else if (onGround()) {
            if (++stranded >= t.strandedRootTicks) {
                BlockPos at = blockPosition();
                rootAt(level, new BlockPos(at.getX(), at.getY() - 1, at.getZ()));
            }
        }
    }

    private void seek(ServerLevel level, DriftTable.Drift t) {
        long t0 = System.nanoTime();
        int wy = DriftShore.waterSurfaceY(level, blockPosition());
        if (wy == Integer.MIN_VALUE) return;
        boolean young = age() < t.ignoreHomeTicks;
        PhaseData pd = young ? PhaseData.get(level.getServer().overworld()) : null;
        int radius = seekRadiusOverride >= 0 ? seekRadiusOverride : t.seekRadius;
        BlockPos found = radius <= 0 ? null : DriftShore.findShore(level, getX(), getZ(), wy, radius, t.seekRingStep,
                key -> refused.contains(key) || pd != null && pd.isOvergrownKey(key));
        target = found;
        mode = found != null ? Mode.SEEK : Mode.DRIFT;
        DriftStats.seek(System.nanoTime() - t0);
    }

    /** 鉤の房が届く(体の真ん中から {@link #gripReach} 以内の)いちばん近い岸の列。断った岸は除く。無ければ null。 */
    @Nullable
    private BlockPos landWithinGrip(ServerLevel level) {
        int wy = DriftShore.waterSurfaceY(level, blockPosition());
        if (wy == Integer.MIN_VALUE) return null;
        return DriftShore.findShore(level, getX(), getZ(), wy, (int) Math.floor(gripReach()), 1, refused::contains);
    }

    // ------------------------------------------------------------ 根付く

    /**
     * 岸の列 {@code shore}(y は水面の段)に根付く。根付いたら true(自分は消える)。押し返しの効く岸・立てる所の無い岸は断って
     * そのチャンクを {@link #refused} に入れ、流れ続ける。
     */
    public boolean rootAt(ServerLevel level, BlockPos shore) {
        DriftTable.Root t = DesignData.drift().root;
        BlockPos stand = DriftShore.standOn(level, shore.getX(), shore.getZ(), shore.getY(), 6);
        long chunk = net.minecraft.world.level.ChunkPos.asLong(shore.getX() >> 4, shore.getZ() >> 4);
        if (stand == null || RingWards.guarded(level, stand) || RingWards.guarded(level, stand.below())) {
            refused.add(chunk);
            target = null;
            if (mode != Mode.FERRY) mode = Mode.DRIFT;
            // 岸から離れる向きへ押し返す
            Vec3 away = new Vec3(getX() - (shore.getX() + 0.5D), 0.0D, getZ() - (shore.getZ() + 0.5D));
            if (away.lengthSqr() > 1e-6) {
                driftVx = away.normalize().x * 0.08D;
                driftVz = away.normalize().z * 0.08D;
            }
            DriftStats.refused++;
            if (mode == Mode.FERRY) ejectRiders(stand); // 行き先が守られている: 降ろせる所なら降ろす
            return false;
        }
        ejectRiders(stand);
        // 鉤の房で岸をつかみ(grip)、胞子嚢の塊が破裂して(burst)根付く。破裂のしぼむ瞬間に burstAndRoot
        arrivalShore = shore.immutable();
        arrivalStand = stand.immutable();
        arrivalTick = 0;
        target = null;
        driftVx = 0.0D;
        driftVz = 0.0D;
        setFlag(F_PADDLING, false);
        setFlag(F_REVEALED, true);
        setFlag(F_GRIPPING, true);
        return true;
    }

    /** 着いてからの段取りを 1 tick 進める(grip → burst → 消える)。 */
    void tickArrival(ServerLevel level) {
        arrivalTick++;
        if (arrivalTick == GRIP_TICKS) {
            setFlag(F_GRIPPING, false);
            setFlag(F_REVEALED, false);
            setFlag(F_BURSTING, true);
        }
        if (arrivalTick == GRIP_TICKS + BURST_PEAK_TICKS && arrivalShore != null) burstAndRoot(level, arrivalShore, arrivalStand);
        if (arrivalTick >= GRIP_TICKS + BURST_TICKS) discard();
    }

    /**
     * 胞子嚢の塊が破裂して、根付く所に胞子を撒く(11-22 ユーザー決定「弱点 + 着いたら胞子」)。破裂は植物側の死の破裂と同じ口
     * (汚染の球・升の浮遊・胞子の雲)。萌芽体の株と芽の点はその後に置く(球に上書きされないように)。
     */
    void burstAndRoot(ServerLevel level, BlockPos shore, BlockPos stand) {
        DriftTable.Root t = DesignData.drift().root;
        if (RingWards.guarded(level, stand)) return; // 鉤を掛けている間に押し返しの道具が置かれた: 根付かない
        com.yayuy.overgrownvegetation.corpse.G1DeathBurst.burst(level, Vec3.atBottomCenterOf(stand), getBbWidth(), getBbHeight());
        DriftStats.bursts++;
        BlockPos spot = SporeSprouts.rootedSpot(level, stand);
        if (spot != null) SporeSprouts.placeRootedAt(level, spot, getYRot(), true);
        // 陸の奥へ芽の点を足す(岸から陸の向き)
        double ax = stand.getX() + 0.5D - getX(), az = stand.getZ() + 0.5D - getZ();
        double al = Math.hypot(ax, az);
        if (al < 1e-6) {
            ax = 1.0D;
            al = 1.0D;
        }
        ax /= al;
        az /= al;
        for (int i = 0; i < t.extraPoints; i++) {
            double r = 2.0D + random.nextDouble() * Math.max(0, t.extraPointReach - 2);
            double sp = (random.nextDouble() - 0.5D) * 1.2D;
            int x = Mth.floor(stand.getX() + 0.5D + (ax * Math.cos(sp) - az * Math.sin(sp)) * r);
            int z = Mth.floor(stand.getZ() + 0.5D + (ax * Math.sin(sp) + az * Math.cos(sp)) * r);
            BlockPos p = DriftShore.standOn(level, x, z, shore.getY(), 8);
            if (p != null && !RingWards.guarded(level, p)) SporeSprouts.addPoint(level, p);
        }
        SporeFieldServer.depositPoint(level, stand, t.deposit);
        DriftShore.Island island = DriftShore.island(level, shore.getX(), shore.getZ(), shore.getY(), t.islandMaxColumns, t.islandScanRadius);
        if (island != null) SwollenIslands.start(level, island, shore.getY());
        DriftStats.rooted++;
        lastRoot = stand;
    }

    /** 確かめ用: 最後に根付いた所。 */
    @Nullable
    public static volatile BlockPos lastRoot;

    /** 乗せていた Mob を降ろす({@code at} が null なら体の上にそのまま)。 */
    void ejectRiders(@Nullable BlockPos at) {
        List<Entity> riders = new ArrayList<>(getPassengers());
        int i = 0;
        for (Entity e : riders) {
            e.stopRiding();
            if (at != null) {
                double ox = (i - (riders.size() - 1) / 2.0D) * 0.8D;
                e.teleportTo(at.getX() + 0.5D + ox, at.getY(), at.getZ() + 0.5D);
            }
            i++;
        }
    }

    /** 倒れてから沈む速さ(ブロック/tick)と、消えるまでの tick【仮】。 */
    static final double SINK_SPEED = 0.05D;
    static final int SINK_TICKS = 60;

    /**
     * 倒れたら破裂せずに沈む(11-22 ユーザー決定。植物側の死の破裂からはタグ death_burst_exempt で外してある)。
     * バニラの 20 tick の白い煙は出さず、{@link #SINK_TICKS} 沈んでから消える。
     */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= SINK_TICKS && !level().isClientSide && !isRemoved()) {
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void die(DamageSource source) {
        // 沈められた: 乗せていた Mob は水に落ちる
        if (!level().isClientSide) ejectRiders(null);
        super.die(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hit = super.hurt(source, amount);
        if (hit && !level().isClientSide && isAlive()) triggerAnim("hurt", "hurt");
        return hit;
    }

    /**
     * 正体を見せる相手がいるか: 観戦でないプレイヤーが {@code r} 以内(植物側の者 = 植物の装備の宿主などは除く)。
     * 11-22 全体管理２の了承(距離は【仮】で drift.json の revealDistance)。
     */
    boolean revealTo(ServerLevel level, double r) {
        if (r <= 0) return false;
        for (Player p : level.players()) {
            if (p.isSpectator() || com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(p)) continue;
            if (p.distanceToSqr(this) < r * r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ アニメ(形 B 改 6・モデル制作の正本。11-22)

    static final RawAnimation A_DRIFT = RawAnimation.begin().thenLoop("drift");
    static final RawAnimation A_SWIM = RawAnimation.begin().thenLoop("swim");
    static final RawAnimation A_REVEAL = RawAnimation.begin().thenPlay("reveal").thenLoop("reveal_hold");
    static final RawAnimation A_GRIP = RawAnimation.begin().thenPlayAndHold("grip");
    static final RawAnimation A_BURST = RawAnimation.begin().thenPlayAndHold("burst");
    static final RawAnimation A_DEATH = RawAnimation.begin().thenPlayAndHold("death");
    static final RawAnimation A_HURT = RawAnimation.begin().thenPlay("hurt");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /**
     * 層の順(後に登録した層が同じ骨の同じチャンネルで勝つ。GeckoLib 4.7.6): 体全体(漂う・漕ぐ・破裂・沈む)→ 嚢と鉤(正体・つかむ)→ 傷。
     * 破裂と沈む姿は全身なので、その間は嚢と鉤の層を止める(嚢の拡大が掛け算にならないように。モデル制作の「重ね方の前提」)。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 10, this::mainState));
        controllers.add(new AnimationController<>(this, "sacs", 5, this::sacState));
        controllers.add(new AnimationController<>(this, "hurt", 0, s -> PlayState.STOP).triggerableAnim("hurt", A_HURT));
    }

    private PlayState mainState(AnimationState<DriftMimicEntity> state) {
        if (isDeadOrDying()) return state.setAndContinue(A_DEATH);
        if (flag(F_BURSTING)) return state.setAndContinue(A_BURST);
        return state.setAndContinue(flag(F_PADDLING) ? A_SWIM : A_DRIFT);
    }

    private PlayState sacState(AnimationState<DriftMimicEntity> state) {
        if (isDeadOrDying() || flag(F_BURSTING)) return PlayState.STOP;
        if (flag(F_GRIPPING)) return state.setAndContinue(A_GRIP);
        if (flag(F_REVEALED)) return state.setAndContinue(A_REVEAL);
        return PlayState.STOP;
    }

    // 光(クライアント): モデル制作の「光る区間」の表のとおり
    private long revealSince = Long.MIN_VALUE, burstSince = Long.MIN_VALUE;

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == DATA_FLAGS && level() != null) {
            long now = level().getGameTime();
            if (flag(F_REVEALED) || flag(F_GRIPPING)) {
                if (revealSince == Long.MIN_VALUE) revealSince = now;
            } else if (!flag(F_BURSTING)) {
                revealSince = Long.MIN_VALUE;
            }
            if (flag(F_BURSTING)) {
                if (burstSince == Long.MIN_VALUE) burstSince = now;
            } else {
                burstSince = Long.MIN_VALUE;
            }
        }
    }

    /** 光の層の強さ 0〜1(描画から)。 */
    public float glow(float partialTick) {
        float now = level().getGameTime() + partialTick;
        if (isDeadOrDying()) return revealSince == Long.MIN_VALUE ? 0.0F : Math.max(0.0F, 1.0F - (deathTime + partialTick) / 18.0F);
        if (burstSince != Long.MIN_VALUE) {
            float t = (now - burstSince) / 20.0F;
            if (t < 1.3F) return Mth.clamp(t / 1.3F, 0.0F, 1.0F) * (0.75F + 0.25F * Mth.sin(t * 40.0F)); // 震えに合わせて明滅
            return t < 1.45F ? 1.0F : 0.0F;
        }
        if (flag(F_GRIPPING)) return 1.0F;
        if (revealSince != Long.MIN_VALUE) {
            float t = (now - revealSince) / 20.0F;
            if (t < 0.3F) return 0.0F;
            float g = Math.min(1.0F, (t - 0.3F) / 0.9F);
            return t > 1.75F ? g * (0.9F + 0.1F * Mth.sin(t * Mth.PI)) : g; // reveal_hold の脈に合わせてゆらぐ
        }
        return 0.0F;
    }

    // ------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DriftMode", mode.name());
        if (target != null) tag.put("DriftTarget", NbtUtils.writeBlockPos(target));
        tag.putLong("DriftBorn", born);
        tag.putLongArray("DriftRefused", refused.toLongArray());
        if (arrivalStand != null && arrivalShore != null) {
            tag.put("DriftArrivalShore", NbtUtils.writeBlockPos(arrivalShore));
            tag.put("DriftArrivalStand", NbtUtils.writeBlockPos(arrivalStand));
            tag.putInt("DriftArrivalTick", arrivalTick);
        }
    }

    /** 1.20.1: NbtUtils#readBlockPos(tag, key) が無い(1.20.1 の writeBlockPos は {X,Y,Z} の CompoundTag)。 */
    @Nullable
    private static BlockPos readPos(CompoundTag tag, String key) {
        return tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND) ? NbtUtils.readBlockPos(tag.getCompound(key)) : null;
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            mode = Mode.valueOf(tag.getString("DriftMode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.DRIFT;
        }
        target = readPos(tag, "DriftTarget");
        born = tag.contains("DriftBorn") ? tag.getLong("DriftBorn") : Long.MIN_VALUE;
        refused.clear();
        for (long k : tag.getLongArray("DriftRefused")) refused.add(k);
        arrivalShore = readPos(tag, "DriftArrivalShore");
        arrivalStand = arrivalShore == null ? null : readPos(tag, "DriftArrivalStand");
        arrivalTick = tag.getInt("DriftArrivalTick");
        if (arrivalStand != null) {
            // 見た目の印は保存しない(同期するだけ)ので、着いてからの tick から立て直す(11-22 見直し 15)。
            // 立て直さないと、鉤を掛けている・破裂している途中で読み込み直したとき、漂う絵のまま破裂する。
            boolean gripping = arrivalTick < GRIP_TICKS;
            setFlag(F_PADDLING, false);
            setFlag(F_REVEALED, gripping);
            setFlag(F_GRIPPING, gripping);
            setFlag(F_BURSTING, !gripping);
        }
    }

    /** 近くにプレイヤー(観戦を除く)がいるか。 */
    static boolean playerNear(ServerLevel level, Vec3 at, double r) {
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.position().distanceToSqr(at) < r * r) return true;
        }
        return false;
    }
}
