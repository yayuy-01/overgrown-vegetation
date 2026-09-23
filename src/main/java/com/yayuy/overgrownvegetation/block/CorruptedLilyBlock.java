package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 水面に浮かぶ、スイレンのような汚染植物(花の有無違いで2種)。
 *
 * <p>バニラの {@code WaterlilyBlock} と同じく、水(または氷)の真上の空気マスに置かれる。
 * 自分自身は水を含まない。花の咲いたバリエーションもモデル/テクスチャが違うだけで
 * このクラスを使い回す。
 */
public class CorruptedLilyBlock extends BushBlock {

    public static final MapCodec<CorruptedLilyBlock> CODEC = simpleCodec(CorruptedLilyBlock::new);

    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 1.5, 15.0);

    public CorruptedLilyBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getFluidState().is(FluidTags.WATER)
                || state.getBlock() instanceof IceBlock
                || state.is(Blocks.WATER);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        BlockState below = level.getBlockState(belowPos);
        return (below.getFluidState().is(FluidTags.WATER) || below.getBlock() instanceof IceBlock)
                && level.getFluidState(pos).getType() == Fluids.EMPTY;
    }
}
