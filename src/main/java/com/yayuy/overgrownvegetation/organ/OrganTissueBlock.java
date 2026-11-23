package com.yayuy.overgrownvegetation.organ;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 器官の肉(2026-11-17): 嚢のかたまり(膜){@code organ_sac}・卵の房と標本の嚢のかたまり {@code organ_egg_mass}。
 * 芯を壊すと {@link #WITHERED}(しぼむ・枯れ色)になる。絵は organs セッション(Fable)。
 */
public class OrganTissueBlock extends Block {

    public static final BooleanProperty WITHERED = BooleanProperty.create("withered");

    public OrganTissueBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WITHERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WITHERED);
    }
}
