package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 根付いた萌芽体の中身(2026-10-23 萌芽体のモデル)。歩く萌芽体と<b>同じ模型</b>(geo/blight_fern.geo.json)を GeckoLib で描き、
 * 根付いた姿のアニメ(unfurl → rooted_idle)を流す。持つのは見た目だけ: 歩いていたときの向き(歩く姿とつなぐため)と、
 * 根付いたばかりか(巻きが開くアニメから始めるか)。胞子嚢がしぼむ瞬間の噴き出しは描画側
 * ({@code client/BlightFernRootedRenderer}、rooted_idle の timeline の {@code puff:<番号>})。
 */
public class BlightFernRootedBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final RawAnimation UNFURL_THEN_IDLE = RawAnimation.begin().thenPlay("unfurl").thenLoop("rooted_idle");
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("rooted_idle");
    /** unfurl(2.5 秒)を流してよい、根付いてからの長さ(tick)。これより後に初めて見えたものは開いた姿から。 */
    private static final long FRESH_TICKS = 60L;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** 歩いていたときの体の向き(度)。NaN = 決まっていない(チャンクを読んだときに置かれた分 → 場所から決める)。 */
    private float yaw = Float.NaN;
    /** 根付いたゲーム時刻(0 = 不明)。 */
    private long plantedAt;
    /** まわりの汚染を広げ終えたか(サーバー。終えた株はチャンクを読み直しても広げ直さない。胞子の場 10-23 A1)。 */
    private boolean spreadDone;
    /** クライアントで最初に選んだアニメ(途中で切り替えると流れ直すので 1 回だけ決める)。 */
    private RawAnimation chosen;

    public BlightFernRootedBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BLIGHT_FERN_ROOTED.get(), pos, state);
    }

    /** 萌芽体が根付いたときに呼ぶ(向きと、開くアニメから始めるか)。 */
    public void plant(float yaw, boolean fresh) {
        this.yaw = yaw;
        this.plantedAt = fresh && this.level != null ? this.level.getGameTime() : 0L;
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    public boolean spreadDone() {
        return this.spreadDone;
    }

    public void markSpreadDone() {
        this.spreadDone = true;
        this.setChanged();
    }

    /** 描く向き(度)。決まっていなければ場所から散らす(並んだ株が同じ向きにならない)。 */
    public float yaw() {
        if (!Float.isNaN(this.yaw)) {
            return this.yaw;
        }
        long h = this.worldPosition.asLong() * 0x9E3779B97F4A7C15L;
        return (float) ((h >>> 40) % 360L);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 0, state -> {
            if (this.chosen == null) {
                boolean fresh = this.plantedAt > 0L && this.level != null && this.level.getGameTime() - this.plantedAt < FRESH_TICKS;
                this.chosen = fresh ? UNFURL_THEN_IDLE : IDLE;
            }
            return state.setAndContinue(this.chosen);
        }));
        // 胞子嚢がしぼむ瞬間の噴き出し(timeline の puff:<番号>)は画面の演出と一緒に 2026-10-30 に parked/ へ(BlightFernRootedPuffs)
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Float.isNaN(this.yaw)) {
            tag.putFloat("Yaw", this.yaw);
        }
        if (this.plantedAt > 0L) {
            tag.putLong("PlantedAt", this.plantedAt);
        }
        if (this.spreadDone) {
            tag.putBoolean("SpreadDone", true);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.yaw = tag.contains("Yaw") ? tag.getFloat("Yaw") : Float.NaN;
        this.plantedAt = tag.getLong("PlantedAt");
        this.spreadDone = tag.getBoolean("SpreadDone");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
