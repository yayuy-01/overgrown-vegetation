package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.GunTurretClaims;
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
 * ({@link GunTurretTargeting})。目標を失ったときに選び直す。撃てる目標を持っている間は、
 * {@code retargetIntervalTicks} ごとに「優先度が上の相手」だけを探して乗り換える(2026-09-25、{@link #STICK_TICKS})。
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
public class GunTurretBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, GeoBlockEntity, com.yayuy.overgrownvegetation.defense.MachineBattery.Holder {

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
     * 目標が無いあいだ({@code targetKind == 0})、次に探すまでの間隔(tick)。仮決め 2026-09-24。
     *
     * <p>2026-09-24 軽量化(監査 87): 前は目標が無いと毎 tick {@code retargetCooldown} を 0 に戻していたので、
     * 待機中の砲台は<b>毎 tick</b> {@link GunTurretTargeting#pick}(索引引き 2 回・候補づくり・遮られた候補への clip)を走らせていた。
     * いまは「見失った tick」「撃てるようになった tick」「射程に最優先の相手 —— 胞子弾・落下中の爆弾・ヴェロフィル ——
     * がいる間」({@link GunTurretTargeting#interceptInRange})は前と同じく即座に探し、
     * それ以外の待機中はこの間隔ごと(位相は座標から作る = 並べた砲台が同じ tick に探さない)。
     * 待機中の砲台が新しく射程に入った<b>普通の敵</b>に気づくのが最大 {@code IDLE_PICK_INTERVAL - 1} tick 遅れるだけ
     * (その数 tick に 2 体が入ってきたら、先に入った方ではなく pick の順位 = 近い方を先に狙うことがある。
     * 前でも次の選び直し({@code retargetIntervalTicks} 後)にはその近い方へ移っていた)。
     */
    private static final int IDLE_PICK_INTERVAL = 4;

    /**
     * 目標を捕まえてから(最後に撃ってから)これだけ撃てずにいたら、同じ優先度の相手へも乗り換えてよい(tick)。
     * 旋回(秒間 22.5 度)で 45 度ほど回る時間。仮決め 2026-09-25。
     */
    private static final long STICK_TICKS = 40L;

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
    /** 地脈電池(2026-10-16、㉔。defense/MachineBattery。10-22 に改名)。基地が置いた砲台は電池なしで動く。 */
    private int batteryCharge;
    private boolean hasBattery;
    private boolean baseSupplied;
    /** 目標を選び直すまでの残り tick。 */
    private int retargetCooldown;
    /** 最後に撃った時刻(描画の反動用)。クライアントは自分の予測で入れる。 */
    private long lastFiredGameTime = Long.MIN_VALUE;
    /** いまの目標を捕まえた時刻(サーバーだけ。保存しない)。 */
    private long acquiredGameTime = Long.MIN_VALUE;

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

    /**
     * いまの目標が、いまのモードで狙ってよい相手か(ユーザー要望 2026-09-26 の射撃モード)。
     * 飛んでくる胞子弾は「空の目標」なので、対地モードでは手放す。
     */
    private boolean wantsCurrentTarget(ServerLevel level, GunTurretMode mode) {
        if (targetKind == GunTurretTargeting.KIND_SHELL) {
            return mode.engagesAir();
        }
        Entity entity = level.getEntity(targetId);
        return entity == null || GunTurretTargeting.wants(mode, entity);
    }

    /** いま立てている札({@link GunTurretClaims})を外す。目標を替える・失うときに呼ぶ。 */
    private void releaseClaim(ServerLevel level) {
        if (targetKind != 0) {
            GunTurretClaims.release(level, worldPosition, targetKind, targetId);
        }
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
        // 電池が無いと動かない(プレイヤーが置いた砲台。2026-10-16 ユーザー指定)。
        armed = !powered && !getBlockState().getValue(GunTurretBlock.OVERGROWN) && ammoCount() > 0
                && com.yayuy.overgrownvegetation.defense.MachineBattery.hasEnergy(this);
        if (!armed) {
            releaseClaim(level);
            targetKind = 0;
            targetId = -1;
            // 撃てるようになった tick には(前と同じく)その場で探す。待機中の間引き(監査 87)を持ち越さない。
            retargetCooldown = 0;
            advanceAim(null);
            syncIfChanged(wasArmed, wasKind, wasId);
            return;
        }

        GunTurretMode mode = getBlockState().getValue(GunTurretBlock.MODE);
        Vec3 target = targetPoint(1.0F);
        // モードに合わない相手は手放す(防空なら地上を、対地なら空を。ユーザー指定 2026-09-26)。
        if (target != null && !wantsCurrentTarget(level, mode)) {
            target = null;
        }
        // いまの目標がまだ撃てるか(射程は相手による — ヴェロフィルだけ長い)。撃てないならその場で選び直す。
        if (target == null
                || !GunTurretTargeting.canEngage(level, worldPosition, azimuth(), target, currentTargetRange())) {
            // 2026-09-24 軽量化(監査 87): 選び直しを急ぐのは「この tick に見失った」ときと、
            // 射程に最優先の相手(胞子弾・落下中の爆弾・ヴェロフィル)がいる間(迎撃の反応を前と同じにする)だけ。
            // もともと目標が無い待機中は、下の pick の後で入れた間隔({@link #IDLE_PICK_INTERVAL})を守る —— 前は毎 tick 0 に戻していた。
            if (targetKind != 0 || (retargetCooldown > 0
                    && GunTurretTargeting.interceptInRange(level, worldPosition, azimuth(), mode))) {
                retargetCooldown = 0;
            }
            target = null;
            releaseClaim(level);
            targetKind = 0;
            targetId = -1;
        }
        if (retargetCooldown > 0) {
            retargetCooldown--;
        } else {
            long now = level.getGameTime();
            // 撃てる目標を持っていれば離さない: 乗り換えるのは優先度が上の相手(ヴェロフィル・爆弾・胞子弾)が出たときだけ。
            // ただし捕まえてから(最後に撃ってから) STICK_TICKS 撃てていなければ(旋回が追いつかない相手)、同じ優先度でも選び直す。
            // 2026-09-25 ユーザー報告「ずっと目標を切り替え続け、敵が近くにいるのに撃たない」(GunTurretTargeting#engageCost)。
            boolean sticky = target != null
                    && (acquiredGameTime >= now - STICK_TICKS || lastFiredGameTime >= now - STICK_TICKS);
            GunTurretTargeting.Target picked;
            if (sticky) {
                int rank = GunTurretTargeting.rankOf(level, targetKind, targetId);
                picked = rank > GunTurretTargeting.RANK_INTERCEPT
                        && GunTurretTargeting.interceptInRange(level, worldPosition, azimuth(), mode)
                        ? GunTurretTargeting.pick(level, worldPosition, azimuth(), elevation(), rank, mode) : null;
            } else {
                picked = GunTurretTargeting.pick(level, worldPosition, azimuth(), elevation(),
                        Integer.MAX_VALUE, mode);
            }
            if (picked != null && (picked.kind() != targetKind || picked.id() != targetId)) {
                releaseClaim(level);
                targetKind = picked.kind();
                targetId = picked.id();
                acquiredGameTime = now;
            }
            target = picked != null ? picked.point() : target;
            // 目標を持っていれば前と同じ間隔、見つからなければ待機中の間隔(座標の位相に揃える)。
            retargetCooldown = targetKind != 0
                    ? DesignData.defense().turretRetargetIntervalTicks()
                    : idleRetryCooldown(level.getGameTime());
        }

        // ここまでで target(null でなければ)は、この向きの砲耳から「遮られていない」と確かめてある
        // (canEngage か pick の中の canEngage)。旋回で方位が 0.1 度も動かなければ、発砲の線はそれとまったく同じ。
        // 「いまこの相手は自分が撃っている」札を立て直す(ほかの砲台が同じ相手に寄らないように。2026-09-26)。
        if (targetKind != 0) {
            GunTurretClaims.claim(level, worldPosition, targetKind, targetId);
        }

        int checkedAzimuthTenths = azimuthTenths;
        advanceAim(target);
        if (targetKind != 0) {
            com.yayuy.overgrownvegetation.defense.MachineBattery.drain(this, 1); // 敵を狙っている間だけ減る
        }

        if (target != null && isFireTick(level.getGameTime())
                && aimError(target) <= DesignData.defense().turretAimTolerance()) {
            fire(level, target, azimuthTenths == checkedAzimuthTenths);
        }
        syncIfChanged(wasArmed, wasKind, wasId);
    }

    /**
     * 待機中(目標なし)に次に探すまでの残り tick。次の探索が
     * {@code (時刻 + 座標の位相) % IDLE_PICK_INTERVAL == 0} の tick に来るように数える
     * (カウンタが 0 の tick に探すので、次の探索は この値 + 1 tick 後)。
     */
    private int idleRetryCooldown(long gameTime) {
        long phase = Math.floorMod(worldPosition.hashCode(), IDLE_PICK_INTERVAL);
        return (int) Math.floorMod(-(gameTime + 1L + phase), (long) IDLE_PICK_INTERVAL);
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

    /**
     * 1 発撃つ。弾は即着弾(機関砲なので弾道は持たない)。
     *
     * @param lineChecked この tick にまったく同じ線(同じ方位の砲耳 → {@code target})を引いて、
     *                    遮られていないと分かっているか。true なら引き直さない(2026-09-24 軽量化、監査 1263)。
     */
    private void fire(ServerLevel level, Vec3 target, boolean lineChecked) {
        if (!consumeRound()) {
            return;
        }
        lastFiredGameTime = level.getGameTime();
        Vec3 muzzle = muzzle();

        // 地形に遮られていたら壁に当たるだけ(命中しない)。線は砲耳から引く —— 銃口は倍率しだいで
        // 3 ブロック以上前に出るので、そこを起点にすると近い相手や見下ろす角度で話が合わなくなる。
        // 同じ tick・同じ方位・同じ点で確かめ済みなら、結果も同じ(null)なので clip を省く。
        Vec3 blocked = lineChecked ? null : GunTurretTargeting.blockedAt(level, worldPosition, azimuth(), target);
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
            // 撃った点(砲耳)を添える。スクティムネスの盾が正面からの弾を弾くのに使うだけ(ノックバックは位置なしのまま。ModDamageTypes)。
            entity.hurt(ModDamageTypes.autocannon(level, trunnion()), (float) DesignData.defense().turretDamage());
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

    // --- 電池(MachineBattery.Holder) ---

    @Override
    public int batteryCharge() {
        return batteryCharge;
    }

    @Override
    public void setBatteryCharge(int charge) {
        batteryCharge = charge;
    }

    @Override
    public boolean hasBattery() {
        return hasBattery;
    }

    @Override
    public void setHasBattery(boolean has) {
        hasBattery = has;
    }

    @Override
    public boolean baseSupplied() {
        return baseSupplied;
    }

    /** 同盟軍の基地が置いた砲台の印(電池なしで動く)。 */
    public void setBaseSupplied(boolean supplied) {
        baseSupplied = supplied;
        setChanged();
    }

    @Override
    public void batteryChanged() {
        setChanged();
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

    /** いま狙っているエンティティの id(エンティティを狙っていなければ -1)。GameTest 用。 */
    public int targetEntityId() {
        return targetKind == GunTurretTargeting.KIND_ENTITY ? targetId : -1;
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

    /**
     * 目標が変わったときの更新パケット。2026-09-24 軽量化(監査 1285): {@link #onDataPacket} が読むのは
     * 目標と「撃てる状態か」だけなので、向き(Elevation / Azimuth)は載せない。チャンクを送るときの
     * {@link #getUpdateTag} / {@link #handleUpdateTag} は今までどおり向きも運ぶ。
     */
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this, (blockEntity, registries) -> {
            CompoundTag tag = new CompoundTag();
            tag.putInt("TargetKind", targetKind);
            tag.putInt("TargetId", targetId);
            tag.putBoolean("Armed", armed);
            return tag;
        });
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
        com.yayuy.overgrownvegetation.defense.MachineBattery.load(this, tag);
        baseSupplied = tag.getBoolean("BaseSupplied");
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
        com.yayuy.overgrownvegetation.defense.MachineBattery.save(this, tag);
        tag.putBoolean("BaseSupplied", baseSupplied);
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
