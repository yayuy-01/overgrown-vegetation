package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.SporeAlarms;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 胞子警報器の中身(2026-09-19)。データは持たず、ロード/アンロードで {@link SporeAlarms} の台帳へ
 * 出入りするためだけに居る。
 */
public class SporeAlarmBlockEntity extends BlockEntity {

    public SporeAlarmBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPORE_ALARM.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            SporeAlarms.register(this.level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) {
            SporeAlarms.unregister(this.level, this.worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null && !this.level.isClientSide) {
            SporeAlarms.unregister(this.level, this.worldPosition);
        }
    }
}
