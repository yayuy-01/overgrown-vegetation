package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 天井から垂れる根の簾の<b>中間</b>(2026-11-03 地下の植生 2・U5。先は {@link DanglingRootsBlock})。 */
public class DanglingRootsPlantBlock extends GrowingPlantBodyBlock {

    public static final MapCodec<DanglingRootsPlantBlock> CODEC = simpleCodec(DanglingRootsPlantBlock::new);
    private static final VoxelShape SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);

    public DanglingRootsPlantBlock(BlockBehaviour.Properties properties) {
        super(properties, Direction.DOWN, SHAPE, false);
    }

    @Override
    public MapCodec<DanglingRootsPlantBlock> codec() {
        return CODEC;
    }

    @Override
    protected GrowingPlantHeadBlock getHeadBlock() {
        return (GrowingPlantHeadBlock) ModBlocks.DANGLING_ROOTS.get();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return DanglingRootsBlock.hangsFrom(level, pos);
    }
}
