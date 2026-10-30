package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 風見の羽根の向き(クライアントだけが使う。保存しない)。 */
public class WindVaneBlockEntity extends BlockEntity {

    /** 描画している向き(ラジアン、羽根の矢の先が指す向き)。NaN = まだ。 */
    public float shownAngle = Float.NaN;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIND_VANE.get(), pos, state);
    }
}
