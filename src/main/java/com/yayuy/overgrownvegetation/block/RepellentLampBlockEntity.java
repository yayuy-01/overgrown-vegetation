package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 忌避灯の中身(2026-09-19)。データは持たず、ロード/アンロードで {@link RepellentLamps} の台帳へ
 * 出入りするためだけに居る(点いている灯りだけ登録する)。
 */
public class RepellentLampBlockEntity extends BlockEntity {

    public RepellentLampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REPELLENT_LAMP.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide && RepellentLampBlock.isActive(getBlockState())) {
            RepellentLamps.register(this.level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) {
            RepellentLamps.unregister(this.level, this.worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null && !this.level.isClientSide) {
            RepellentLamps.unregister(this.level, this.worldPosition);
        }
    }
}
