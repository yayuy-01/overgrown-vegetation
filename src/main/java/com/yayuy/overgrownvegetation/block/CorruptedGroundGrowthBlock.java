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
 * 汚染ブロックの上面から生える下草。
 *
 * <p>シダ・苔・胞子キノコ・茨など「地面から生えるタイプ」の汚染植物は
 * 見た目(テクスチャ/モデル)が違うだけで、当たり判定や生存条件はすべて共通なので
 * このクラス 1 つを複数の {@link com.yayuy.overgrownvegetation.registry.ModBlocks} エントリで使い回す。
 *
 * <p>土に限らずどんなブロックの上面でも(それが汚染ブロックであっても)塞がっていれば生える。
 */
public class CorruptedGroundGrowthBlock extends BushBlock {

    public static final MapCodec<CorruptedGroundGrowthBlock> CODEC = simpleCodec(CorruptedGroundGrowthBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 14.0, 14.0);

    public CorruptedGroundGrowthBlock(BlockBehaviour.Properties properties) {
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
