package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.StakeWards;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 受け流しの杭の登録係(2026-10-16、⑭)。中身は無く、読み込まれた・外された・アンロードされたときに {@link StakeWards} の台帳を出し入れするだけ
 * (忌避灯と同じやり方)。
 */
public class DeflectingStakeBlockEntity extends BlockEntity {

    public DeflectingStakeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEFLECTING_STAKE.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) {
            StakeWards.register(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            StakeWards.unregister(level, worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide()) {
            StakeWards.unregister(level, worldPosition);
        }
    }
}
