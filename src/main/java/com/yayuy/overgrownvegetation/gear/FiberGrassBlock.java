package com.yayuy.overgrownvegetation.gear;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>繊維の草</b>(段 1 の素材。2026-11-17 決定 17)。縁(繁茂)に生え({@link FiberGrassSpread})、刈ると丈夫な繊維(既存の {@code plant_fiber})が取れる。
 * 布・紐・弦の材料。上がしっかりした面なら土でも石でも立つ(汚染の地面は土のタグに入っていない物が多いので)。葉の上には立たない。
 */
public class FiberGrassBlock extends BushBlock {

    public static final MapCodec<FiberGrassBlock> CODEC = simpleCodec(FiberGrassBlock::new);
    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 13.0D, 14.0D);

    public FiberGrassBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.is(BlockTags.LEAVES) && state.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
