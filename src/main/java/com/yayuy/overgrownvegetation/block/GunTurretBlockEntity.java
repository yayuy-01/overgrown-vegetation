package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.GunTurretGeometry;
import com.yayuy.overgrownvegetation.defense.GunTurretTargeting;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * 機関砲台の中身(ユーザー要望 2026-09-21)。
 *
 * <p>エンティティではなく<b>自分で動くブロック</b>。弾({@link ModItems#AUTOCANNON_ROUND})を
 * 9 スロット持ち、射程に入った敵を自動で撃つ。数値はすべて {@code design/defense.json} の
 * {@code gunTurret}(既定: 1 発 15・毎分 240 発・半径 32(ヴェロフィルだけ 64)・仰角 +85/俯角 −28 度)。
 *
 * <h2>何を狙うか</h2>
 * 優先するのは<b>落としに来るもの</b> —— ヴェロフィル、落下中の胞子爆弾、攻城戦ボスの曲射
 * (増援を運ぶ胞子弾を含む)。それが無ければ射程かつ仰俯角の可動域に入っている敵
 * ({@link GunTurretTargeting})。目標は {@code retargetIntervalTicks} ごと、
 * および目標を失ったときに選び直す。
 *
 * <h2>撃ち方</h2>
 * 弾道は持たない(機関砲なので<b>即着弾</b>)。砲耳から目標まで地形で遮られていなければ命中
 * ({@link GunTurretTargeting#blockedAt} —— 銃身は倍率しだいで 3 ブロック以上伸びるので、線は銃口ではなく砲耳から引く)。
 * 相手が飛行中の胞子弾なら {@link ShellTracker#intercept} で空中で割る ——
 * 増援を運ぶ弾だった場合はそこで小さな胞子爆弾が散る。
 *
 * <h2>同期していないもの</h2>
 * 砲身の向きと発砲のタイミングは<b>サーバーとクライアントが同じ式で出す</b>ので、毎 tick の
 * 同期パケットが要らない({@code SiegeCannonBlockEntity} と同じ考え方)。
 * <ul>
 *   <li>向き: 両側とも「いまの目標へ速度制限つきで追従」。<b>目標の id だけ</b>を
 *       ブロック更新で配る(変わったときだけ)。弾の位置はクライアントも
 *       {@code ClientShells} が同じ放物線の式で持っている。</li>
 *   <li>発砲の瞬間: {@code (ゲーム内時刻 + 台ごとの位相) % 発射間隔 == 0}。位相は座標から作るので
 *       両側で同じになり、隣り合う砲台の発砲がきれいに重ならない。</li>
 * </ul>
 */
public class GunTurretBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, GeoBlockEntity {

    /** 弾倉のスロット数(ユーザー指定: 9 スタック)。 */
    public static final int SLOTS = 9;
    private static final int[] ALL_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8};

    /** 発射の反動で銃身が後退する長さ(ブロック)と、その時間(tick)。描画だけ。 */
    public static final float RECOIL_BLOCKS = 0.10F;
    public static final float RECOIL_TICKS = 4.0F;

    /** 飛行中の胞子弾に当たったと見なす半径(ブロック)。弾そのものには当たり判定が無いので決め打ち。 */
    private static final double SHELL_HIT_RADIUS = 1.0D;

    /**
     * 旋回のなめらかさ。角速度が「行きたい速度」へ 1 tick でこの割合だけ寄る
     * (0.18 ≒ 5 tick で 7 割)。小さいほど重く、動き出しと止まりがゆっくりになる。
     */
    private static final double SLEW_SMOOTHING = 0.18D;

    /**
     * クライアントで飛行中の胞子弾の位置を引くための窓口。{@code OvergrownVegetationClient} が
     * {@code ClientShells} を差す({@code client} パッケージをサーバー側から触らないため)。
     */
    public interface ShellLocator {
        @Nullable
        Vec3 position(int shellId, long gameTime, float partialTick);
    }

    private static ShellLocator clientShells = (id, gameTime, partialTick) -> null;

    public static void setClientShellLocator(ShellLocator locator) {
        clientShells = locator;
    }

    /**
     * 見える曳光弾({@code client/GunTracerRenderer})の置き場。こちらも {@code OvergrownVegetationClient} が
     * 差す —— 当たり判定は即着弾なので、飛んでいく弾は<b>各クライアントが自分の予測から描くだけ</b>で、
     * パケットは 1 つも飛ばない。
     */
    public interface TracerSink {
        void add(Vec3 from, Vec3 to, long gameTime);
    }

    private static TracerSink clientTracers = (from, to, gameTime) -> {
    };

    public static void setClientTracerSink(TracerSink sink) {
        clientTracers = sink;
    }

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    /** 仰角(0.1 度単位、上が正)。 */
    private int elevationTenths;
    /** 方位(0.1 度単位、0 = 北、[0, 3600))。 */
    private int azimuthTenths;
    /** 前 tick の仰角・方位(描画のフレーム間補間用。同期も保存もしない)。 */
    private double prevElevation;
    private double prevAzimuth;
    /**
     * いまの角速度(度/tick)。なめらかに回すために持つ({@link #advanceAim})。
     * 同期も保存もしない —— 両側が同じ入力から同じように積み上げるし、失っても数 tick で揃う。
     */
    private double azimuthSpeed;
    private double elevationSpeed;

    /** いまの目標({@link GunTurretTargeting#KIND_ENTITY} / {@code KIND_SHELL})。0 = 無し。同期する。 */
    private int targetKind;
    private int targetId = -1;
    /** 弾があり、レッドストーンで止められてもいない(= 目標を見つけたら撃つ)。同期する。 */
    private boolean armed;
    /** レッドストーン信号(来ていたら撃たない)。 */
    private boolean powered;
    /** 目標を選び直すまでの残り tick。 */
    private int retargetCooldown;
    /** 最後に撃った時刻(描画の反動用)。クライアントは自分の予測で入れる。 */
    private long lastFiredGameTime = Long.MIN_VALUE;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public GunTurretBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GUN_TURRET.get(), pos, state);
    }

    // ------------------------------------------------------------------ 照準

    /** 保存されている仰角(度)。 */
    public double elevation() {
        return elevationTenths / 10.0D;
    }

    /** 保存されている方位(度、0 = 北)。 */
    public double azimuth() {
        return azimuthTenths / 10.0D;
    }

    /** 描画用: フレーム間を補間した仰角。 */
    public double aimElevation(float partialTick) {
        return Mth.lerp(partialTick, prevElevation, elevation());
    }

    /** 描画用: フレーム間を補間した方位。 */
    public double aimAzimuth(float partialTick) {
        return CannonBallistics.normalizeAzimuth(
                prevAzimuth + Mth.wrapDegrees(azimuth() - prevAzimuth) * partialTick);
    }

    public void setAzimuth(double degrees) {
        azimuthTenths = (int) Math.floorMod(Math.round(CannonBallistics.normalizeAzimuth(degrees) * 10.0D), 3600L);
        prevAzimuth = azimuth();
        azimuthSpeed = 0.0D;
        setChanged();
    }

    /** 仰角を直接入れる(可動域でクランプ)。据え付けと GameTest 用。 */
    public void setElevation(double degrees) {
        elevationTenths = (int) Math.round(GunTurretGeometry.clampElevation(degrees) * 10.0D);
        prevElevation = elevation();
        elevationSpeed = 0.0D;
        setChanged();
    }

    /** 砲耳(俯仰の回転中心)のワールド座標。 */
    public Vec3 trunnion() {
        return GunTurretGeometry.trunnion(worldPosition, azimuth());
    }

    /** 銃口のワールド座標(弾の出る点)。 */
    public Vec3 muzzle() {
        return GunTurretGeometry.muzzle(worldPosition, elevation(), azimuth());
    }

    /**
     * いま狙っている点。目標が消えていれば {@code null}。
     * クライアントでもサーバーとまったく同じ点が出るように書いてある。
     */
    @Nullable
    public Vec3 targetPoint(float partialTick) {
        if (level == null || targetKind == 0) {
            return null;
        }
        if (targetKind == GunTurretTargeting.KIND_ENTITY) {
            Entity entity = level.getEntity(targetId);
            if (entity == null || !entity.isAlive()) {
                return null;
            }
            return GunTurretTargeting.aimPoint(entity, partialTick);
        }
        if (level.isClientSide) {
            return clientShells.position(targetId, level.getGameTime(), partialTick);
        }
        return ShellTracker.get((ServerLevel) level).shellPosition((ServerLevel) level, targetId);
    }

    /**
     * 銃身を目標へ 1 tick ぶん寄せる。サーバーとクライアントが同じ式で回すので、向きの同期は要らない。
     *
     * <h2>なめらかに回す(2026-09-21)</h2>
     * <p>「行きたい向きへ毎 tick 最高速度で動く」だと、動き出しと止まりが角ばって見える。ここでは
     * <b>角速度そのものを持って</b>、行きたい速度({@code 残り角度を上限で切ったもの})へ
     * {@link #SLEW_SMOOTHING} の割合で寄せる —— 動き出しで加速し、目標に近づくと
     * 残り角度が小さくなるぶん自然に減速して、行き過ぎずに止まる。
     */
    private void advanceAim(@Nullable Vec3 target) {
        prevElevation = elevation();
        prevAzimuth = azimuth();

        double traverse = DesignData.defense().turretTraverseSpeed();
        double elevate = DesignData.defense().turretElevationSpeed();
        double deltaAz = 0.0D;
        double deltaEl = 0.0D;
        if (target != null) {
            double[] aim = GunTurretGeometry.aimAt(trunnion(), target);
            deltaAz = CannonBallistics.azimuthDelta(azimuth(), aim[1]);
            deltaEl = GunTurretGeometry.clampElevation(aim[0]) - elevation();
        }
        // 行きたい速度。目標に近づくほど小さくなるので、そのまま減速になる。
        // 目標を失ったときは 0 へ寄せる = 惰性で止まる(その場で固まらない)。
        double wantAzSpeed = Mth.clamp(deltaAz, -traverse, traverse);
        double wantElSpeed = Mth.clamp(deltaEl, -elevate, elevate);
        azimuthSpeed += (wantAzSpeed - azimuthSpeed) * SLEW_SMOOTHING;
        elevationSpeed += (wantElSpeed - elevationSpeed) * SLEW_SMOOTHING;
        if (Math.abs(azimuthSpeed) < 1.0E-4D && Math.abs(elevationSpeed) < 1.0E-4D) {
            azimuthSpeed = 0.0D;
            elevationSpeed = 0.0D;
            return;
        }
        // 残りより大きくは進めない —— 速度に遅れがあるぶん、そのままだと目標を通り過ぎて
        // 行ったり来たりする(銃身が細かく震えて見える)。
        double stepAz = Math.abs(azimuthSpeed) > Math.abs(deltaAz) ? deltaAz : azimuthSpeed;
        double stepEl = Math.abs(elevationSpeed) > Math.abs(deltaEl) ? deltaEl : elevationSpeed;
        double nextAz = azimuth() + stepAz;
        double nextEl = elevation() + stepEl;
        azimuthTenths = (int) Math.floorMod(Math.round(CannonBallistics.normalizeAzimuth(nextAz) * 10.0D), 3600L);
        elevationTenths = (int) Math.round(GunTurretGeometry.clampElevation(nextEl) * 10.0D);
    }

    /** いま狙っている相手に使う射程。ヴェロフィルだけ長い({@code gunTurret.velophyllRange})。 */
    private double currentTargetRange() {
        if (level != null && targetKind == GunTurretTargeting.KIND_ENTITY) {
            return GunTurretTargeting.rangeFor(level.getEntity(targetId));
        }
        return DesignData.defense().turretRange();
    }

    /** 銃口がいま目標からどれだけ外れているか(度)。 */
    private double aimError(Vec3 target) {
        double[] aim = GunTurretGeometry.aimAt(trunnion(), target);
        return GunTurretTargeting.aimError(elevation(), azimuth(),
                GunTurretGeometry.clampElevation(aim[0]), aim[1]);
    }

    // ------------------------------------------------------------------ tick

    public static void serverTick(Level level, BlockPos pos, BlockState state, GunTurretBlockEntity be) {
        be.tickServer((ServerLevel) level);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, GunTurretBlockEntity be) {
        be.tickClient(level);
    }

    private void tickServer(ServerLevel level) {
        boolean wasArmed = armed;
        int wasKind = targetKind;
        int wasId = targetId;

        // 過繫茂に覆われた砲台は動かない(ユーザー指定 2026-09-21)。弾はそのまま残る。
        armed = !powered && !getBlockState().getValue(GunTurretBlock.OVERGROWN) && ammoCount() > 0;
        if (!armed) {
            targetKind = 0;
            targetId = -1;
            advanceAim(null);
            syncIfChanged(wasArmed, wasKind, wasId);
            return;
        }

        Vec3 target = targetPoint(1.0F);
        // いまの目標がまだ撃てるか(射程は相手による — ヴェロフィルだけ長い)。撃てないならその場で選び直す。
        if (target == null
                || !GunTurretTargeting.canEngage(level, worldPosition, azimuth(), target, currentTargetRange())) {
            target = null;
            targetKind = 0;
            targetId = -1;
            retargetCooldown = 0;
        }
        if (retargetCooldown > 0) {
            retargetCooldown--;
        } else {
            retargetCooldown = DesignData.defense().turretRetargetIntervalTicks();
            GunTurretTargeting.Target picked = GunTurretTargeting.pick(level, worldPosition, azimuth());
            if (picked != null && (picked.kind() != targetKind || picked.id() != targetId)) {
                targetKind = picked.kind();
                targetId = picked.id();
            }
            target = picked != null ? picked.point() : target;
        }

        advanceAim(target);

        if (target != null && isFireTick(level.getGameTime())
                && aimError(target) <= DesignData.defense().turretAimTolerance()) {
            fire(level, target);
        }
        syncIfChanged(wasArmed, wasKind, wasId);
    }

    private void tickClient(Level level) {
        Vec3 target = armed ? targetPoint(1.0F) : null;
        advanceAim(target);
        // 発砲はサーバーと同じ式で予測する(パケットは飛んでこない)。反動と発砲炎のためだけ。
        if (target != null && isFireTick(level.getGameTime())
                && aimError(target) <= DesignData.defense().turretAimTolerance()) {
            lastFiredGameTime = level.getGameTime();
            Vec3 muzzle = muzzle();
            spawnMuzzleFlash(level, muzzle);
            clientTracers.add(muzzle, impactPoint(level, target), level.getGameTime());
        }
    }

    /** 曳光弾がどこまで飛ぶか(壁に遮られていればその手前)。判定はサーバーの発砲と同じ線。 */
    private Vec3 impactPoint(Level level, Vec3 target) {
        Vec3 blocked = GunTurretTargeting.blockedAt(level, worldPosition, azimuth(), target);
        return blocked != null ? blocked : target;
    }

    /**
     * この tick に撃つか。位相を座標から作るので、サーバーとクライアントで同じ瞬間になり、
     * 隣り合う砲台どうしの発砲もずれる。
     */
    private boolean isFireTick(long gameTime) {
        int interval = DesignData.defense().turretFireIntervalTicks();
        int phase = Math.floorMod(worldPosition.hashCode(), interval);
        return Math.floorMod(gameTime + phase, interval) == 0L;
    }

    // ------------------------------------------------------------------ 発射

    /** 1 発撃つ。弾は即着弾(機関砲なので弾道は持たない)。 */
    private void fire(ServerLevel level, Vec3 target) {
        if (!consumeRound()) {
            return;
        }
        lastFiredGameTime = level.getGameTime();
        Vec3 muzzle = muzzle();

        // 地形に遮られていたら壁に当たるだけ(命中しない)。線は砲耳から引く —— 銃口は倍率しだいで
        // 3 ブロック以上前に出るので、そこを起点にすると近い相手や見下ろす角度で話が合わなくなる。
        Vec3 blocked = GunTurretTargeting.blockedAt(level, worldPosition, azimuth(), target);
        if (blocked == null) {
            hit(level, target);
        }
        playFireEffects(level, muzzle, blocked != null ? blocked : target);
    }

    /** 命中の処理。相手が飛行中の胞子弾なら空中で割り、そうでなければダメージ。 */
    private void hit(ServerLevel level, Vec3 target) {
        if (targetKind == GunTurretTargeting.KIND_SHELL) {
            // 弾は「点」なので、銃口から目標へ引いた線がその点の近くを通っていれば命中。
            Vec3 now = ShellTracker.get(level).shellPosition(level, targetId);
            if (now != null && now.distanceToSqr(target) <= SHELL_HIT_RADIUS * SHELL_HIT_RADIUS
                    && ShellTracker.get(level).intercept(level, targetId)) {
                targetKind = 0;
                targetId = -1;
                retargetCooldown = 0;
            }
            return;
        }
        Entity entity = level.getEntity(targetId);
        if (entity != null && entity.isAlive()) {
            entity.hurt(ModDamageTypes.autocannon(level), (float) DesignData.defense().turretDamage());
        }
    }

    /** 発砲の音(サーバー)と、着弾の砂煙。発砲炎そのものは各クライアントが予測で出す。 */
    private void playFireEffects(ServerLevel level, Vec3 muzzle, Vec3 impact) {
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.BLOCKS, 0.7F, 1.8F + level.random.nextFloat() * 0.2F);
        level.sendParticles(ParticleTypes.CRIT, impact.x, impact.y, impact.z, 3, 0.1D, 0.1D, 0.1D, 0.02D);
    }

    /** クライアント側の発砲炎(飛んでいく弾そのものは {@code client/GunTracerRenderer})。 */
    private void spawnMuzzleFlash(Level level, Vec3 muzzle) {
        Vec3 direction = CannonBallistics.direction(elevation(), azimuth());
        level.addParticle(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z,
                direction.x * 0.3D, direction.y * 0.3D, direction.z * 0.3D);
        level.addParticle(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z,
                direction.x * 0.1D, direction.y * 0.1D + 0.02D, direction.z * 0.1D);
    }

    /** 描画の反動(銃身が後退している長さ、ブロック)。 */
    public float recoil(long gameTime, float partialTick) {
        if (lastFiredGameTime == Long.MIN_VALUE) {
            return 0.0F;
        }
        float age = (float) (gameTime - lastFiredGameTime) + partialTick;
        if (age < 0.0F || age >= RECOIL_TICKS) {
            return 0.0F;
        }
        return Mth.sin((float) Math.PI * age / RECOIL_TICKS) * RECOIL_BLOCKS;
    }

    // ------------------------------------------------------------------ 弾

    /** 弾倉に入っている弾の数。 */
    public int ammoCount() {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(ModItems.AUTOCANNON_ROUND.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 弾を 1 発使う。 */
    private boolean consumeRound() {
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.is(ModItems.AUTOCANNON_ROUND.get())) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * 手に持った弾をそのまま押し込む(GUI を開かずに装填する)。
     *
     * @return 入った数
     */
    public int loadFromHand(ItemStack held) {
        int moved = 0;
        for (int slot = 0; slot < items.size() && !held.isEmpty(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) {
                int take = Math.min(held.getCount(), held.getMaxStackSize());
                items.set(slot, held.split(take));
                moved += take;
            } else if (ItemStack.isSameItemSameComponents(stack, held)) {
                int space = stack.getMaxStackSize() - stack.getCount();
                int take = Math.min(space, held.getCount());
                if (take > 0) {
                    stack.grow(take);
                    held.shrink(take);
                    moved += take;
                }
            }
        }
        if (moved > 0) {
            setChanged();
        }
        return moved;
    }

    /** レッドストーン信号(来ているあいだは撃たない)。 */
    public void setPowered(boolean nowPowered) {
        if (powered != nowPowered) {
            powered = nowPowered;
            setChanged();
        }
    }

    public boolean isArmed() {
        return armed;
    }

    /** 目標を狙っているか(描画で使う)。 */
    public boolean hasTarget() {
        return targetKind != 0;
    }

    // ------------------------------------------------------------------ 同期

    /** 目標か「撃てる状態か」が変わったときだけ、まわりのクライアントへ配る。 */
    private void syncIfChanged(boolean wasArmed, int wasKind, int wasId) {
        if (wasArmed != armed || wasKind != targetKind || wasId != targetId) {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Elevation", elevationTenths);
        tag.putInt("Azimuth", azimuthTenths);
        tag.putInt("TargetKind", targetKind);
        tag.putInt("TargetId", targetId);
        tag.putBoolean("Armed", armed);
        return tag;
    }

    /** チャンクが見え始めたとき(最初の 1 回)。ここでは向きもそのまま受け取る。 */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        elevationTenths = tag.getInt("Elevation");
        azimuthTenths = tag.getInt("Azimuth");
        prevElevation = elevation();
        prevAzimuth = azimuth();
        targetKind = tag.getInt("TargetKind");
        targetId = tag.getInt("TargetId");
        armed = tag.getBoolean("Armed");
    }

    /**
     * 目標が変わったときの更新。<b>向きは受け取らない</b>(2026-09-21)——
     * クライアントはサーバーとまったく同じ式で毎 tick 回しているので、ここで届いた角度を
     * 当てはめると<b>旋回の途中でカクッと飛ぶ</b>。両側とも同じ目標へ寄っていくので、
     * 多少ずれても勝手に揃う。
     */
    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        if (tag == null) {
            return;
        }
        targetKind = tag.getInt("TargetKind");
        targetId = tag.getInt("TargetId");
        armed = tag.getBoolean("Armed");
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ 台帳(スカトゥリウムの狙い)

    /**
     * 動いている砲台の台帳({@link com.yayuy.overgrownvegetation.defense.GunTurrets})へ出入りする。
     * 過繫茂したスカトゥリウムはここを引いて迫撃砲を撃ち込んでくる。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            com.yayuy.overgrownvegetation.defense.GunTurrets.sync(this.level, this.worldPosition, getBlockState());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) {
            com.yayuy.overgrownvegetation.defense.GunTurrets.unregister(this.level, this.worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null && !this.level.isClientSide) {
            com.yayuy.overgrownvegetation.defense.GunTurrets.unregister(this.level, this.worldPosition);
        }
    }

    // ------------------------------------------------------------------ Container

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.gun_turret");
    }

    /** 3×3 の弾倉。バニラのディスペンサーと同じ画面をそのまま使う。 */
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new DispenserMenu(containerId, inventory, this);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    /** 入るのは機関砲弾だけ。 */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return stack.is(ModItems.AUTOCANNON_ROUND.get());
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return canPlaceItem(slot, stack);
    }

    /** ホッパーで抜かれると弾切れになるので、下から吸い出させない。 */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        elevationTenths = tag.getInt("Elevation");
        azimuthTenths = tag.getInt("Azimuth");
        powered = tag.getBoolean("Powered");
        prevElevation = elevation();
        prevAzimuth = azimuth();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("Elevation", elevationTenths);
        tag.putInt("Azimuth", azimuthTenths);
        tag.putBoolean("Powered", powered);
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // アニメーションファイルは使わない。向き・反動は GunTurretGeoModel がボーンへ直接入れる。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
