package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.siege.BeamShieldGeometry;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * {@link BeamShieldBlock} の中身。
 *
 * <ul>
 *   <li><b>方位</b>({@link #azimuthTenths}) — ハンドルを回して変える。0 = 北。
 *       <b>たたんでいるときだけ</b>変えられる。</li>
 *   <li><b>展開</b>({@link #deployTicks}) — 0 からせり上がり、{@code shieldDeployTicks} で完全展開。
 *       <b>完全に展開しきっているあいだだけ</b>ビームを遮る。</li>
 * </ul>
 *
 * <p>遮蔽の判定は {@link BeamShieldGeometry#blocks}(厚みの無い長方形と線分の交差)。
 * 花のビームのダメージを入れる直前に {@link #shielded} を呼び、ビームの発射点と被害者を結ぶ線分が
 * どれかの盾に遮られていればダメージを飛ばす。盾を探す範囲は被害者の周り
 * {@code shieldSearchRadius} ブロック — 遠くの盾は影が届かないので見る必要がない。
 */
public class BeamShieldBlockEntity extends BlockEntity implements GeoBlockEntity, SiegeStructureCore {

    /** 方位(0.1 度単位、0 = 北、[0, 3600))。 */
    private int azimuthTenths;
    /** 展開の進み(tick)。0 = たたんだ状態、{@code shieldDeployTicks} = 完全展開。 */
    private int deployTicks;
    /** いま展開しようとしているか(false ならたたむ方向へ動く)。 */
    private boolean deploying;
    /** レッドストーンの前回の状態(立ち上がりで展開・立ち下がりでたたむ)。 */
    private boolean powered;
    /** ハンドルを回した時刻(描画でハンドルをくるっと回すため)。 */
    private long lastHandleGameTime = Long.MIN_VALUE;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 当たり判定の形の作り置き（向きと展開の進みを量子化した鍵でまとめて作る）。 */
    private final ShapeCache shapeCache = new ShapeCache();
    private java.util.List<BlockPos> template;

    public BeamShieldBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BEAM_SHIELD.get(), pos, state);
    }

    // ------------------------------------------------------------------ 状態

    public double azimuth() {
        return azimuthTenths / 10.0D;
    }

    public void setAzimuth(double degrees) {
        azimuthTenths = (int) Math.floorMod(Math.round(CannonBallistics.normalizeAzimuth(degrees) * 10.0D), 3600L);
        markChanged();
    }

    /** 展開の進み(0〜1)。 */
    public float deployProgress() {
        int total = Math.max(1, OvergrownVegetationConfig.COMMON.shieldDeployTicks.get());
        return Mth.clamp(deployTicks / (float) total, 0.0F, 1.0F);
    }

    /** 完全に展開しきっているか(= ビームを遮るか)。 */
    public boolean isDeployed() {
        return deployTicks >= Math.max(1, OvergrownVegetationConfig.COMMON.shieldDeployTicks.get());
    }

    /** たたみきっているか(= 向きを変えられるか)。 */
    public boolean isFolded() {
        return deployTicks <= 0;
    }

    public boolean isDeploying() {
        return deploying;
    }

    public long lastHandleGameTime() {
        return lastHandleGameTime;
    }

    /** ハンドル: 方位を刻みぶん回す。展開中・展開済みは動かせない。 */
    public boolean turnHandle(int direction) {
        if (!isFolded()) {
            return false;
        }
        double step = OvergrownVegetationConfig.COMMON.shieldHandleStep.get() * Math.signum(direction);
        setAzimuth(azimuth() + step);
        if (level != null) {
            lastHandleGameTime = level.getGameTime();
        }
        markChanged();
        return true;
    }

    /** ボタン: 展開 / たたむ を切り替える。 */
    public void setDeploying(boolean deploy) {
        if (deploying != deploy) {
            deploying = deploy;
            markChanged();
        }
    }

    /** レッドストーン: 立ち上がりで展開、立ち下がりでたたむ。 */
    public void setPowered(boolean nowPowered) {
        if (nowPowered != powered) {
            powered = nowPowered;
            setDeploying(nowPowered);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BeamShieldBlockEntity be) {
        int total = Math.max(1, OvergrownVegetationConfig.COMMON.shieldDeployTicks.get());
        int next = Mth.clamp(be.deployTicks + (be.deploying ? 1 : -1), 0, total);
        if (next != be.deployTicks) {
            boolean wasEnd = be.deployTicks == 0 || be.deployTicks == total;
            be.deployTicks = next;
            if (!level.isClientSide && (wasEnd || next == 0 || next == total)) {
                // 端に着いた・端から動き出したときだけ配る。途中はクライアントが自分で進める。
                be.markChanged();
            }
        }
    }

    private void markChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ------------------------------------------------------------------ 遮蔽

    /**
     * 線分 {@code from → to} が、{@code to} の近くにある展開済みの盾に遮られるか。
     * ビームのダメージを入れる直前に呼ぶ。
     */
    public static boolean shielded(ServerLevel level, Vec3 from, Vec3 to) {
        if (!OvergrownVegetationConfig.COMMON.shieldBlocksBeam.get()) {
            return false;
        }
        double radius = OvergrownVegetationConfig.COMMON.shieldSearchRadius.get();
        int minX = Mth.floor((to.x - radius) / 16.0D);
        int maxX = Mth.floor((to.x + radius) / 16.0D);
        int minZ = Mth.floor((to.z - radius) / 16.0D);
        int maxZ = Mth.floor((to.z + radius) / 16.0D);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof BeamShieldBlockEntity shield && shield.isDeployed()
                            && BeamShieldGeometry.blocks(shield.getBlockPos(), shield.azimuth(), from, to)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** デバッグ・演出用: この盾の遮蔽面の中心(下端)。 */
    public Vec3 panelBase() {
        return BeamShieldGeometry.panelBase(worldPosition, azimuth());
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
            template = BeamShieldGeometry.template();
        }
        return template;
    }

    @Override
    public java.util.List<com.yayuy.overgrownvegetation.siege.SiegeStructureShape.Box> structureBoxes() {
        return BeamShieldGeometry.collisionBoxes(deployProgress());
    }

    @Override
    public double structureScale() {
        return BeamShieldGeometry.scale();
    }

    @Override
    public double structureAzimuth() {
        return azimuth();
    }

    @Override
    public ShapeCache structureShapeCache() {
        return shapeCache;
    }

    /** 展開の進みでも形が変わるので、向きだけでなく進み具合も鍵に混ぜる。 */
    @Override
    public long shapeKey() {
        return Math.round(azimuth() / SHAPE_STEP_DEGREES) * 64L + Math.round(deployProgress() * 32.0F);
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 旋回と展開は BeamShieldGeoModel#setCustomAnimations で直接ボーンへ入れる。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ------------------------------------------------------------------ 同期・NBT

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Azimuth", azimuthTenths);
        tag.putInt("Deploy", deployTicks);
        tag.putBoolean("Deploying", deploying);
        tag.putLong("Handle", lastHandleGameTime);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Azimuth")) {
            azimuthTenths = tag.getInt("Azimuth");
        }
        if (tag.contains("Deploy")) {
            deployTicks = tag.getInt("Deploy");
        }
        if (tag.contains("Deploying")) {
            deploying = tag.getBoolean("Deploying");
        }
        if (tag.contains("Powered")) {
            powered = tag.getBoolean("Powered");
        }
        if (tag.contains("Handle")) {
            lastHandleGameTime = tag.getLong("Handle");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Azimuth", azimuthTenths);
        tag.putInt("Deploy", deployTicks);
        tag.putBoolean("Deploying", deploying);
        tag.putBoolean("Powered", powered);
        tag.putLong("Handle", lastHandleGameTime);
    }
}
