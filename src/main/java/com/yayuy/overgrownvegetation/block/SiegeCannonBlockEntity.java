package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.menu.SiegeCannonMenu;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * {@link SiegeCannonBlock} の中身。
 * <ul>
 *   <li>スロット {@link #SLOT_SHELL}(0): 砲弾({@link ModItems#SIEGE_SHELL})。</li>
 *   <li>スロット {@link #SLOT_POWDER}(1): 火薬(装薬)。1 発に装薬ぶん消費し、多いほど初速が上がる。</li>
 * </ul>
 *
 * <h2>照準 = 騎乗</h2>
 * <p>数値入力ではなく、<b>砲台に乗って一人称で照準する</b>。乗ると操作盤
 * ({@link SiegeCannonGeometry#seat})に {@code SiegeCannonSeatEntity} が湧き、プレイヤーはその上に立つ。
 * 騎乗者の視線は砲塔への<b>指示</b>で、実際の砲の向きはそこへ<b>速度制限つきで追従</b>する
 * ({@link #advanceAim}、{@code cannonTraverseSpeed} / {@code cannonElevationSpeed})。重い砲塔なので
 * すぐには追いつかない。
 * <ul>
 *   <li>指示の方位(台座ボーン {@code dodai}) = ヨー + 180(コンパス方位、0 = 北)</li>
 *   <li>指示の仰角(砲身ボーン {@code housin}) = −ピッチ を config の可動域(既定 0〜40 度)でクランプ</li>
 * </ul>
 * <p>追従の式はサーバーとクライアントで同じで、入力(騎乗者の向き)はバニラが配ってくれるので
 * <b>照準のための同期パケットは要らない</b>。指示へ向かう一方向の収束なので、多少ずれても勝手に揃う。
 * 降りたら砲はその向きで止まり、そのときだけ最後の角度をブロック更新で配る。
 *
 * <p>発射は騎乗中の攻撃キー({@code CannonControlPayload})か、レッドストーン信号の立ち上がり
 * ({@link #setPowered})。砲弾の飛行以降は {@link ShellTracker}。
 */
public class SiegeCannonBlockEntity extends BaseContainerBlockEntity
        implements WorldlyContainer, GeoBlockEntity, SiegeStructureCore, com.yayuy.overgrownvegetation.defense.MachineBattery.Holder {

    public static final int SLOT_SHELL = 0;
    public static final int SLOT_POWDER = 1;
    private static final int[] ALL_SLOTS = {SLOT_SHELL, SLOT_POWDER};

    public static final int DATA_ELEVATION = 0;
    public static final int DATA_AZIMUTH = 1;
    public static final int DATA_CHARGE = 2;
    public static final int DATA_RELOAD = 3;
    public static final int DATA_RELOAD_TOTAL = 4;
    public static final int DATA_MAX_CHARGE = 5;
    public static final int DATA_COUNT = 6;

    /** 発射の反動で砲身が後退する長さ(ブロック)と、その時間(tick)。描画だけ。 */
    public static final float RECOIL_BLOCKS = 0.55F;
    public static final float RECOIL_TICKS = 14.0F;

    private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    /** 仰角(0.1 度単位)。騎乗中は毎 tick 騎乗者の視線で上書きされる。 */
    private int elevationTenths;
    /** 方位(0.1 度単位、0 = 北、[0, 3600))。 */
    private int azimuthTenths;
    /** 装薬(1 発に使う火薬の数)。使うときに config の範囲へクランプする。 */
    private int charge = 4;
    /** 装填の残り tick。0 なら撃てる。 */
    private int reloadTicks;
    /** 最後に撃ったゲーム内時刻(描画の反動用)。 */
    private long lastFiredGameTime = Long.MIN_VALUE;
    /** レッドストーンの前回の状態(立ち上がり検出用)。 */
    private boolean powered;
    /** 地脈電池(2026-10-16、㉔。10-22 に蓄電の実の電池から改名)。1 発ごとに MachineBattery.SHOT_COST 減る。 */
    private int batteryCharge;
    private boolean hasBattery;

    /**
     * 照準器(騎乗中の HUD)に出すための弾薬数。サーバーでは {@link #serverTick} が中身から更新し、
     * 変わったときだけブロック更新で配る。クライアントではその受け取った値そのもの。
     */
    private int shellsForDisplay;
    private int powderForDisplay;

    /** 前 tick の仰角・方位（描画のフレーム間補間用。同期も保存もしない）。 */
    private double prevElevation;
    private double prevAzimuth;

    /**
     * いま砲手として乗っているエンティティ。座席({@code SiegeCannonSeatEntity})が毎 tick
     * サーバー・クライアントの両方で入れる(この参照自体は同期しない — 座席エンティティが
     * 普通に同期されるので、両側で同じ砲手に行き着く)。
     */
    @Nullable
    private Entity rider;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 当たり判定の形の作り置き（向きを 5 度刻みに量子化した鍵でまとめて作る）。 */
    private final ShapeCache shapeCache = new ShapeCache();
    /** 部品ブロックを敷くマス。拡大率が変わらない限り同じなので、最初に 1 回だけ作る。 */
    private java.util.List<BlockPos> template;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_ELEVATION -> elevationTenths;
                case DATA_AZIMUTH -> azimuthTenths;
                case DATA_CHARGE -> charge();
                case DATA_RELOAD -> reloadTicks;
                case DATA_RELOAD_TOTAL -> OvergrownVegetationConfig.COMMON.cannonReloadTicks.get();
                case DATA_MAX_CHARGE -> OvergrownVegetationConfig.COMMON.cannonMaxCharge.get();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case DATA_ELEVATION -> elevationTenths = value;
                case DATA_AZIMUTH -> azimuthTenths = value;
                case DATA_CHARGE -> charge = value;
                case DATA_RELOAD -> reloadTicks = value;
                default -> {
                }
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public SiegeCannonBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIEGE_CANNON.get(), pos, state);
    }

    // ------------------------------------------------------------------ 照準

    /** 保存されている仰角(度)。いまの向きは {@link #aimElevation}。 */
    public double elevation() {
        return elevationTenths / 10.0D;
    }

    /** 保存されている方位(度、0 = 北)。いまの向きは {@link #aimAzimuth}。 */
    public double azimuth() {
        return azimuthTenths / 10.0D;
    }

    /**
     * いまの仰角(度)。砲身は騎乗者の視線へ<b>速度制限つきで</b>追従するので、視線そのものではなく
     * 実際の角度を返す。描画のためフレーム間は前 tick の値と補間する。
     *
     * @param partialTick 補間(描画は 0〜1、処理は 1)
     */
    public double aimElevation(float partialTick) {
        return Mth.lerp(partialTick, prevElevation, elevation());
    }

    /** いまの方位(度、0 = 北)。 */
    public double aimAzimuth(float partialTick) {
        return CannonBallistics.normalizeAzimuth(
                prevAzimuth + Mth.wrapDegrees(azimuth() - prevAzimuth) * partialTick);
    }

    /** 砲手が命じている仰角(= 視線)。騎乗者がいなければ現在の仰角(= 動かない)。 */
    public double desiredElevation() {
        Entity gunner = gunner();
        return gunner != null
                ? SiegeCannonGeometry.elevationFromPitch(gunner.getXRot())
                : SiegeCannonGeometry.clampElevation(elevation());
    }

    /** 砲手が命じている方位(= 視線)。 */
    public double desiredAzimuth() {
        Entity gunner = gunner();
        return gunner != null ? SiegeCannonGeometry.azimuthFromYaw(gunner.getYRot()) : azimuth();
    }

    /**
     * 砲手(いなければ null)。参照を持っているだけでは足りない — <b>本当にこの砲台の座席に
     * 乗ったままか</b>を毎回確かめる。降車のコールバックはクライアントでは呼ばれないことがあり、
     * 古い参照を信じると「降りたのに砲が視線を追い続ける」ことになる。
     */
    @Nullable
    public Entity gunner() {
        Entity current = rider;
        if (current == null) {
            return null;
        }
        if (current.isRemoved() || current.level() != level
                || !(current.getVehicle() instanceof SiegeCannonSeatEntity seat)
                || !seat.cannonPos().equals(worldPosition)) {
            rider = null;
            return null;
        }
        return current;
    }

    /**
     * 砲身を目標角へ 1 tick ぶん寄せる。サーバーとクライアントの<b>両方</b>が同じ式で回すので、
     * 旋回のための同期パケットは要らない(目標 = 騎乗者の視線で、それはバニラが配ってくれる)。
     * 指数収束ではなく<b>一定の角速度</b>なので、重い砲塔らしくじりじり動く。
     */
    public void advanceAim() {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        prevElevation = elevation();
        prevAzimuth = azimuth();

        double traverse = Math.max(0.01D, cfg.cannonTraverseSpeed.get());
        double elevate = Math.max(0.01D, cfg.cannonElevationSpeed.get());
        double deltaAz = CannonBallistics.azimuthDelta(azimuth(), desiredAzimuth());
        double deltaEl = SiegeCannonGeometry.clampElevation(desiredElevation()) - elevation();
        if (Math.abs(deltaAz) < 1.0E-4D && Math.abs(deltaEl) < 1.0E-4D) {
            return;
        }
        double nextAz = azimuth() + Mth.clamp(deltaAz, -traverse, traverse);
        double nextEl = elevation() + Mth.clamp(deltaEl, -elevate, elevate);
        azimuthTenths = azimuthToTenths(nextAz);
        elevationTenths = (int) Math.round(SiegeCannonGeometry.clampElevation(nextEl) * 10.0D);
    }

    public boolean isManned() {
        return gunner() != null;
    }

    /** 座席エンティティが毎 tick 呼ぶ。 */
    public void setRider(@Nullable Entity rider) {
        this.rider = rider;
    }

    /** 降りた(または座席が消えた): 砲はその向きのまま止まる。最後の角度を配って両側を揃える。 */
    public void clearRider() {
        if (rider != null) {
            rider = null;
            markAimChanged();
        }
    }

    private static int azimuthToTenths(double azimuthDeg) {
        return (int) Math.floorMod(Math.round(CannonBallistics.normalizeAzimuth(azimuthDeg) * 10.0D), 3600L);
    }

    public int charge() {
        return Mth.clamp(charge, 1, Math.max(1, OvergrownVegetationConfig.COMMON.cannonMaxCharge.get()));
    }

    public long lastFiredGameTime() {
        return lastFiredGameTime;
    }

    public void setAzimuth(double degrees) {
        azimuthTenths = azimuthToTenths(degrees);
        markAimChanged();
    }

    public void setElevation(double degrees) {
        elevationTenths = (int) Math.round(SiegeCannonGeometry.clampElevation(degrees) * 10.0D);
        markAimChanged();
    }

    public void adjustCharge(int delta) {
        int max = Math.max(1, OvergrownVegetationConfig.COMMON.cannonMaxCharge.get());
        int next = Mth.clamp(charge() + delta, 1, max);
        if (next == charge) {
            return;
        }
        charge = next;
        setChanged();
        broadcast();
    }

    /** 照準器に出す砲弾の数(クライアントでも読める)。 */
    public int shellsForDisplay() {
        return shellsForDisplay;
    }

    /** 照準器に出す火薬の数(クライアントでも読める)。 */
    public int powderForDisplay() {
        return powderForDisplay;
    }

    /**
     * 装填の残り tick。発射時刻から計算するのでクライアントでも同じ値が出る
     * (サーバーの {@link #reloadTicks} と同じになる)。
     */
    public int reloadRemaining(long gameTime) {
        if (lastFiredGameTime == Long.MIN_VALUE) {
            return 0;
        }
        long total = Math.max(0, OvergrownVegetationConfig.COMMON.cannonReloadTicks.get());
        return (int) Mth.clamp(lastFiredGameTime + total - gameTime, 0L, total);
    }

    /** 砲身の旋回軸(砲耳)のワールド座標。 */
    public Vec3 trunnion(double azimuthDeg) {
        return SiegeCannonGeometry.trunnion(worldPosition, azimuthDeg);
    }

    /** 砲口のワールド座標(砲弾の発射位置)。 */
    public Vec3 muzzle(double elevationDeg, double azimuthDeg) {
        return SiegeCannonGeometry.muzzle(worldPosition, elevationDeg, azimuthDeg);
    }

    /** 描画の反動(砲身が後退している長さ、ブロック)。 */
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

    // ------------------------------------------------------------------ 発射

    public boolean hasShell() {
        return items.get(SLOT_SHELL).is(ModItems.SIEGE_SHELL.get());
    }

    public int powderCount() {
        ItemStack powder = items.get(SLOT_POWDER);
        return powder.is(Items.GUNPOWDER) ? powder.getCount() : 0;
    }

    public boolean canFire() {
        return reloadTicks <= 0 && hasShell() && powderCount() >= charge();
    }

    public int reloadTicks() {
        return reloadTicks;
    }

    /**
     * 撃つ。砲弾 1 個と火薬 {@link #charge()} 個を消費し、いまの照準に {@code cannonDispersion} の誤差を
     * 乗せた向きへ {@link CannonBallistics#speedForCharge} の初速で {@link ShellTracker#fire}。
     *
     * @return 撃てたら true(装填中・弾切れ・火薬不足なら false。理由はプレイヤーへ通知)
     */
    public boolean tryFire(ServerLevel level, @Nullable Player player) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (reloadTicks > 0) {
            notify(player, "gui.overgrown_vegetation.cannon.reloading", (int) Math.ceil(reloadTicks / 20.0D));
            return false;
        }
        if (!hasShell()) {
            notify(player, "gui.overgrown_vegetation.cannon.no_shell");
            return false;
        }
        // 電池が無いと動かない(2026-10-16 ユーザー指定)。
        if (!com.yayuy.overgrownvegetation.defense.MachineBattery.hasEnergy(this, com.yayuy.overgrownvegetation.defense.MachineBattery.SHOT_COST)) {
            notify(player, "message.overgrown_vegetation.battery.needed");
            return false;
        }
        int charge = charge();
        if (powderCount() < charge) {
            notify(player, "gui.overgrown_vegetation.cannon.no_powder", charge);
            return false;
        }
        items.get(SLOT_SHELL).shrink(1);
        items.get(SLOT_POWDER).shrink(charge);
        com.yayuy.overgrownvegetation.defense.MachineBattery.drain(this, com.yayuy.overgrownvegetation.defense.MachineBattery.SHOT_COST);

        double dispersion = cfg.cannonDispersion.get();
        double elevation = aimElevation(1.0F) + level.random.nextGaussian() * dispersion;
        double azimuth = aimAzimuth(1.0F) + level.random.nextGaussian() * dispersion;
        Vec3 direction = CannonBallistics.direction(elevation, azimuth);
        Vec3 origin = muzzle(elevation, azimuth);
        Vec3 velocity = direction.scale(CannonBallistics.speedForCharge(charge));
        ShellTracker.get(level).fire(level, origin, velocity);

        reloadTicks = Math.max(0, cfg.cannonReloadTicks.get());
        lastFiredGameTime = level.getGameTime();
        level.playSound(null, worldPosition, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS,
                4.0F, 0.5F + level.random.nextFloat() * 0.1F);
        level.sendParticles(ParticleTypes.EXPLOSION, origin.x, origin.y, origin.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, origin.x, origin.y, origin.z, 16,
                direction.x * 0.6D, direction.y * 0.6D, direction.z * 0.6D, 0.08D);
        // 反動と弾薬の減りは全クライアントで見せたいので、騎乗中でもここだけはブロック更新を送る。
        shellsForDisplay = items.get(SLOT_SHELL).is(ModItems.SIEGE_SHELL.get()) ? items.get(SLOT_SHELL).getCount() : 0;
        powderForDisplay = powderCount();
        setChanged();
        broadcast();
        return true;
    }

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
    public void batteryChanged() {
        setChanged();
    }

    /** レッドストーン信号の変化。立ち上がり(off → on)で撃つ。 */
    public void setPowered(ServerLevel level, boolean nowPowered) {
        if (nowPowered && !powered) {
            tryFire(level, null);
        }
        powered = nowPowered;
    }

    private static void notify(@Nullable Player player, String key, Object... args) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    /** クライアント側の毎 tick。旋回・俯仰をサーバーとまったく同じ式で進める。 */
    public static void clientTick(Level level, BlockPos pos, BlockState state, SiegeCannonBlockEntity be) {
        be.advanceAim();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SiegeCannonBlockEntity be) {
        be.advanceAim();
        if (be.reloadTicks > 0) {
            be.reloadTicks--;
            if (be.reloadTicks == 0) {
                be.setChanged();
            }
        }
        // 弾薬の数が変わったときだけ、照準器のためにクライアントへ配る(ホッパー投入にも追従する)。
        int shells = be.items.get(SLOT_SHELL).is(ModItems.SIEGE_SHELL.get()) ? be.items.get(SLOT_SHELL).getCount() : 0;
        int powder = be.powderCount();
        if (shells != be.shellsForDisplay || powder != be.powderForDisplay) {
            be.shellsForDisplay = shells;
            be.powderForDisplay = powder;
            be.broadcast();
        }
    }

    /** 照準器・砲身の描画に要る値を周囲のクライアントへ配る。 */
    private void broadcast() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 仰角・方位が変わった: 保存対象にし、砲身の描画のために周囲のクライアントへも送る。
     * 騎乗中は各クライアントが騎乗者の視線から同じ角度を出せるので送らない(毎 tick の
     * ブロック更新を避けるため)。
     */
    private void markAimChanged() {
        setChanged();
        if (!isManned()) {
            broadcast();
        }
    }

    // ------------------------------------------------------------------ クライアント同期(砲身の描画)

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Elevation", elevationTenths);
        tag.putInt("Azimuth", azimuthTenths);
        tag.putLong("LastFired", lastFiredGameTime);
        tag.putInt("Charge", charge);
        tag.putInt("Shells", shellsForDisplay);
        tag.putInt("Powder", powderForDisplay);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ 当たり判定（部品ブロック）

    @Override
    public BlockPos structurePos() {
        return worldPosition;
    }

    @Override
    public Level structureLevel() {
        return level;
    }

    @Override
    public java.util.List<BlockPos> structureTemplate() {
        if (template == null) {
            template = SiegeCannonGeometry.template();
        }
        return template;
    }

    @Override
    public java.util.List<com.yayuy.overgrownvegetation.siege.SiegeStructureShape.Box> structureBoxes() {
        return SiegeCannonGeometry.COLLISION_BOXES;
    }

    @Override
    public double structureScale() {
        return SiegeCannonGeometry.scale();
    }

    @Override
    public double structureAzimuth() {
        return azimuth();
    }

    @Override
    public ShapeCache structureShapeCache() {
        return shapeCache;
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 旋回・俯仰・反動は SiegeCannonGeoModel#setCustomAnimations で直接ボーンへ入れるので、
        // アニメーションコントローラーは要らない。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ------------------------------------------------------------------ Container

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.siege_cannon");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new SiegeCannonMenu(containerId, inventory, this, data, worldPosition, this);
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

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == SLOT_SHELL ? stack.is(ModItems.SIEGE_SHELL.get()) : stack.is(Items.GUNPOWDER);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Items")) {
            items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, items, registries);
        }
        if (tag.contains("Elevation")) {
            elevationTenths = tag.getInt("Elevation");
        }
        if (tag.contains("Azimuth")) {
            azimuthTenths = tag.getInt("Azimuth");
        }
        if (tag.contains("Charge")) {
            charge = tag.getInt("Charge");
        }
        if (tag.contains("Reload")) {
            reloadTicks = tag.getInt("Reload");
        }
        if (tag.contains("LastFired")) {
            lastFiredGameTime = tag.getLong("LastFired");
        }
        if (tag.contains("Powered")) {
            powered = tag.getBoolean("Powered");
            com.yayuy.overgrownvegetation.defense.MachineBattery.load(this, tag);
        }
        if (tag.contains("Shells")) {
            shellsForDisplay = tag.getInt("Shells");
        }
        if (tag.contains("Powder")) {
            powderForDisplay = tag.getInt("Powder");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("Elevation", elevationTenths);
        tag.putInt("Azimuth", azimuthTenths);
        tag.putInt("Charge", charge);
        tag.putInt("Reload", reloadTicks);
        tag.putLong("LastFired", lastFiredGameTime);
        tag.putBoolean("Powered", powered);
        com.yayuy.overgrownvegetation.defense.MachineBattery.save(this, tag);
    }
}
