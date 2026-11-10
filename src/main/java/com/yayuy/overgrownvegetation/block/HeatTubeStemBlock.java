package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 管の樹 A の細い管(2026-11-10 熱水の森の木の段階)。6 画素角の柱を縦に積み、先に管の羽根({@code heat_tube_plume})を載せる。
 * 当たり判定は柱の太さ。管虫の管が鉱物で固まったもの、という見立て。
 */
public class HeatTubeStemBlock extends Block {

    public static final MapCodec<HeatTubeStemBlock> CODEC = simpleCodec(HeatTubeStemBlock::new);
    private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0);

    public HeatTubeStemBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HeatTubeStemBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** 上に羽根が立てるよう、支えとしては丸ごとの面にする(羽根は下の面が塞がった所にしか立たない)。 */
    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return net.minecraft.world.phys.shapes.Shapes.block();
    }
}
