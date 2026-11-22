package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 壁から少しだけ出っ張る小さな立体の壁の植物(2026-11-08): 胞子嚢のこぶ {@code wall_spore_sacs}・小さな菌の棚 {@code wall_bracket_fungus}。
 * 形は blockstate の重み付きの模型(座標で選ぶ)で 2〜3 通り。当たり判定は壁から 4 px の薄い箱(どの形も収まる)。
 * 付き方と剥がれ方は {@link CorruptedWallGrowthBlock} と同じ。
 */
public class SmallWallGrowthBlock extends CorruptedWallGrowthBlock {


    private static final VoxelShape NORTH = Block.box(1, 1, 12, 15, 15, 16);
    private static final VoxelShape SOUTH = Block.box(1, 1, 0, 15, 15, 4);
    private static final VoxelShape WEST = Block.box(12, 1, 1, 16, 15, 15);
    private static final VoxelShape EAST = Block.box(0, 1, 1, 4, 15, 15);

    public SmallWallGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }
}
