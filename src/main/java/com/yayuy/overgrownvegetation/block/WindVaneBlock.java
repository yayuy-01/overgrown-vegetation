package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>風見</b>(2026-10-23 胞子の場と風 第 2 段 H)。棒の上の羽根が、世界に 1 つの卓越風(サーバーと同じ式、
 * {@code client/ClientPrevailingWind})の吹いてくる方を矢の先で指す。一日の風の行き先が分かる —— 風下側を固める・
 * フィルターを替える日を決める道具。羽根はブロックエンティティの描画({@code client/WindVaneRenderer})。モデルと絵は仮。
 */
public class WindVaneBlock extends BaseEntityBlock {

    public static final MapCodec<WindVaneBlock> CODEC = simpleCodec(WindVaneBlock::new);
    private static final VoxelShape SHAPE = Block.box(6.5D, 0.0D, 6.5D, 9.5D, 16.0D, 9.5D);

    public WindVaneBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WindVaneBlockEntity(pos, state);
    }
}
