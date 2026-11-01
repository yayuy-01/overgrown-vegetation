package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 木生シダ(プサロニウス)の葉の芯(2026-11-01)。中身は今の葉と同じ {@link OvergrownLeavesBlock}(木の一部としての instanceof 判定に乗る)で、
 * 燃え方だけバニラの葉と同じ(燃えやすさ 60・広がりやすさ 30)。
 */
public class TreeFernLeavesBlock extends OvergrownLeavesBlock {

    public static final MapCodec<TreeFernLeavesBlock> CODEC = simpleCodec(TreeFernLeavesBlock::new);

    public TreeFernLeavesBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends OvergrownLeavesBlock> codec() {
        return CODEC;
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 60;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 30;
    }
}
