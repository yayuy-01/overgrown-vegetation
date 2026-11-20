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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
 * <p>擬態: 今は形が仮(流木に見える箱。{@code client/DriftMimicRenderer})。近づいたときに正体を見せる合図は、モデル制作の形が決まってから。
 * 攻撃はしない・プレイヤーを探さない(探すなら {@code PlantArmor.isHost} を通す決まり)。沖で叩けば沈む(体力は mobs.json)。
 */
public class DriftMimicEntity extends Monster implements ParasitizedMob {

    public static final String ID = "drift_mimic";

    public enum Mode { DRIFT, SEEK, FERRY }

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

    public DriftMimicEntity(EntityType<? extends DriftMimicEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = MobStats.xpReward(ID, 3);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
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
    public boolean canDrownInFluidType(net.neoforged.neoforge.fluids.FluidType type) {
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
     * 席: 2 列 × 3 段(6 席、ユーザー決定 11-20)。仮の箱の上に並べる。モデル制作の形(座の骨 seat_0〜5)が届いたらその骨の位置へ寄せる。
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float partialTick) {
        int i = Math.max(0, getPassengers().indexOf(passenger));
        double side = getPassengers().size() <= 1 ? 0.0D : (i % 2 == 0 ? -0.35D : 0.35D);
        int rows = (getPassengers().size() + 1) / 2;
        double along = ((i / 2) - (rows - 1) / 2.0D) * 0.7D;
        return new Vec3(side, dimensions.height() * 0.85D, along).yRot(-getYRot() * Mth.DEG_TO_RAD);
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
        if (level().isClientSide || !isInWater()) {
            super.travel(input);
            return;
        }
        DriftTable.Drift t = DesignData.drift().drift;
        DriftWind.now((ServerLevel) level(), forcedWind, wind);
        double s = Math.min(t.windCap, Math.hypot(wind[0], wind[1]));
        double wl = Math.hypot(wind[0], wind[1]);
        double dx = wl > 1e-9 ? wind[0] / wl * s * t.blocksPerWind : 0.0D, dz = wl > 1e-9 ? wind[1] / wl * s * t.blocksPerWind : 0.0D;
        if (target != null && (mode == Mode.SEEK || mode == Mode.FERRY)) {
            double tx = target.getX() + 0.5D - getX(), tz = target.getZ() + 0.5D - getZ();
            double d = Math.hypot(tx, tz);
            if (d > 1e-6) {
                dx = dx * 0.3D + tx / d * t.paddleSpeed;
                dz = dz * 0.3D + tz / d * t.paddleSpeed;
            }
        }
        // 横の速さは自分で持つ(バニラは毎 tick 0.003 未満の速さを 0 にするので、deltaMovement でゆっくり加速させると動き出せない)
        Vec3 v = getDeltaMovement();
        double k = Mth.clamp(t.inertia, 0.0D, 1.0D);
        driftVx += (dx - driftVx) * k;
        driftVz += (dz - driftVz) * k;
        double fh = getFluidHeight(FluidTags.WATER);
        double vy = v.y * 0.7D + (fh - getBbHeight() * 0.55D) * 0.08D;
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
                if (d < 1.0D + getBbWidth() * 0.5D || horizontalCollision && d < 4.0D) {
                    rootAt(level, target);
                    return;
                }
            } else if (horizontalCollision) {
                // 風に流されて岸にぶつかった
                BlockPos hit = landAhead(level);
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

    /** 進む向きの 1〜2 ブロック先の陸の列(水面の段で見る)。無ければ null。 */
    @Nullable
    private BlockPos landAhead(ServerLevel level) {
        int wy = DriftShore.waterSurfaceY(level, blockPosition());
        if (wy == Integer.MIN_VALUE) return null;
        Vec3 v = driftVelocity();
        double l = Math.sqrt(v.horizontalDistanceSqr());
        if (l < 1e-6) return null;
        for (double r = 1.0D; r <= 2.0D; r += 0.5D) {
            int x = Mth.floor(getX() + v.x / l * (r + getBbWidth() * 0.5D)), z = Mth.floor(getZ() + v.z / l * (r + getBbWidth() * 0.5D));
            if (refused.contains(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4))) return null;
            if (DriftShore.column(level, x, z, wy) == DriftShore.Col.LAND) return new BlockPos(x, wy, z);
        }
        return null;
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
        discard();
        return true;
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

    @Override
    public void die(DamageSource source) {
        // 沈められた: 乗せていた Mob は水に落ちる
        if (!level().isClientSide) ejectRiders(null);
        super.die(source);
    }

    // ------------------------------------------------------------ 保存

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("DriftMode", mode.name());
        if (target != null) tag.put("DriftTarget", NbtUtils.writeBlockPos(target));
        tag.putLong("DriftBorn", born);
        tag.putLongArray("DriftRefused", refused.toLongArray());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        try {
            mode = Mode.valueOf(tag.getString("DriftMode"));
        } catch (IllegalArgumentException e) {
            mode = Mode.DRIFT;
        }
        target = NbtUtils.readBlockPos(tag, "DriftTarget").orElse(null);
        born = tag.contains("DriftBorn") ? tag.getLong("DriftBorn") : Long.MIN_VALUE;
        refused.clear();
        for (long k : tag.getLongArray("DriftRefused")) refused.add(k);
    }

    /** 近くにプレイヤー(観戦を除く)がいるか。 */
    static boolean playerNear(ServerLevel level, Vec3 at, double r) {
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.position().distanceToSqr(at) < r * r) return true;
        }
        return false;
    }
}
