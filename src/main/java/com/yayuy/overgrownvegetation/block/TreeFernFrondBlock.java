package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 木生シダ(プサロニウス)の葉の板(2026-11-01)。仕組みは封印木の向き付きの板 {@link SigillariaFringeBlock} そのまま
 * (芯が下 = ゼンマイ、上 = 垂れる古い葉、横 = 横へ伸びる羽状の葉、角)。絵は blockstate で替え、燃え方だけバニラの葉と同じ(60 / 30)。
 */
public class TreeFernFrondBlock extends SigillariaFringeBlock {

    public static final MapCodec<TreeFernFrondBlock> CODEC = simpleCodec(TreeFernFrondBlock::new);

    public TreeFernFrondBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends SigillariaFringeBlock> codec() {
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
