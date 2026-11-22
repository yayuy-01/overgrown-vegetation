package com.yayuy.overgrownvegetation.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 狐火茸(2026-11-03 地下の植生 1、U4): 洞窟の床にまばらに立つ小さな光る茸。{@link #WOOD} が false = 小さな茸の群れ(下絵 a)、
 * true = 朽ちた根の切れ端に並ぶ茸(下絵 b。材の中の菌糸も光る)。両方使う(仮決め)。本物の光量 3(仮)、見た目の光は点光源({@code CaveLights} の FOXFIRE)。
 */
public class FoxfireBlock extends CorruptedGroundGrowthBlock {

    public static final BooleanProperty WOOD = BooleanProperty.create("wood");

    public FoxfireBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(WOOD, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WOOD);
    }
}
