package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 林床に敷く高さ 1/16 の板(2026-11-01): 小葉類の敷物 {@code lycopsid_carpet} と泥炭の上の屑 {@code peat_litter}。
 * 苔の絨毯と同じく向きは blockstate の y 回転 4 つを乱数で選び、繰り返しを見せない。
 * 当たり判定は無く(下草と同じ手触り)、下の面が塞がっていれば何の上にも敷ける。
 */
public class FloorCarpetBlock extends BushBlock {

    public static final MapCodec<FloorCarpetBlock> CODEC = simpleCodec(FloorCarpetBlock::new);

    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);

    public FloorCarpetBlock(BlockBehaviour.Properties properties) {
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
        return state.isFaceSturdy(level, pos, Direction.UP);
    }
}
