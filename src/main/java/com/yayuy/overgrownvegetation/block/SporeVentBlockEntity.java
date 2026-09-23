package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.nest.NestVents;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * {@link SporeVentBlock} の中身(2026-09-20)。育ち具合(%)を持ち、台帳
 * ({@link NestVents})に自分を出し入れするだけ。効果の配布は台帳側が
 * <b>まとめて</b>行う —— 噴出孔ごとにプレイヤーを走査すると、3 本あれば 3 倍の走査になる。
 */
public class SporeVentBlockEntity extends BlockEntity {

    /** パーティクルを出す間隔(tick)。 */
    private static final int PARTICLE_INTERVAL = 10;

    /** 胞子濃度への上乗せ(%)。0 なら効果なし。 */
    private int percent;
    /** 育ての親の胞子ノード。壊れた噴出孔を辿るのに使う。 */
    @Nullable
    private BlockPos source;
    private boolean registered;

    public SporeVentBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPORE_VENT.get(), pos, state);
    }

    public int percent() {
        return percent;
    }

    @Nullable
    public BlockPos source() {
        return source;
    }

    public void setSource(BlockPos source) {
        this.source = source.immutable();
        setChanged();
    }

    /** 育ち具合を変える。見た目の段階({@link SporeVentBlock#GROWTH})もここで合わせる。 */
    public void setPercent(Level level, int percent) {
        int max = Math.max(1, DesignData.nest().ventBonusPerVentPercent());
        this.percent = Mth.clamp(percent, 0, max);
        BlockState state = getBlockState();
        if (state.hasProperty(SporeVentBlock.GROWTH)) {
            int stage = Mth.clamp(this.percent * 4 / max, 0, 3);
            if (state.getValue(SporeVentBlock.GROWTH) != stage) {
                level.setBlock(worldPosition, state.setValue(SporeVentBlock.GROWTH, stage), Block.UPDATE_ALL);
            }
        }
        NestVents.register(level, worldPosition, this.percent);
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SporeVentBlockEntity vent) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!vent.registered) {
            NestVents.register(level, pos, vent.percent);
            vent.registered = true;
        }
        if (level.getGameTime() % PARTICLE_INTERVAL != 0 || vent.percent <= 0) {
            return;
        }
        int count = 1 + vent.percent / 3;
        serverLevel.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR,
                pos.getX() + 0.5D, pos.getY() + 0.9D, pos.getZ() + 0.5D,
                count, 0.25D, 0.15D, 0.25D, 0.02D);
    }

    @Override
    public void setRemoved() {
        if (level != null) {
            NestVents.unregister(level, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Percent", percent);
        if (source != null) {
            tag.putLong("Source", source.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        percent = tag.getInt("Percent");
        source = tag.contains("Source") ? BlockPos.of(tag.getLong("Source")) : null;
    }
}
