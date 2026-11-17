package com.yayuy.overgrownvegetation.organ;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 器官の光る筋(2026-11-17)。光る苔のように面に這う。根元から芯へ向かう線。呼吸の明滅はテクスチャのアニメ
 * (光の量は一定 = 明滅で光の計算をしない)。芯を壊すと {@link #WITHERED}(消える)。広がらない。
 */
public class OrganVeinBlock extends MultifaceBlock {

    public static final MapCodec<OrganVeinBlock> CODEC = simpleCodec(OrganVeinBlock::new);
    public static final BooleanProperty WITHERED = OrganTissueBlock.WITHERED;
    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public OrganVeinBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WITHERED, false));
    }

    @Override
    protected MapCodec<? extends MultifaceBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WITHERED);
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return spreader;
    }
}
