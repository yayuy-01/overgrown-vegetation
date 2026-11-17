package com.yayuy.overgrownvegetation.organ;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 器官の芯(2026-11-17)。光る塊。どれか 1 つでも壊れると器官が枯れる({@link ForestOrgans#onCoreRemoved})。
 * 育つ前の芽にも 1 つ置く(壊すと芽吹きが止まる)。枯れた後に残った芯は {@link #WITHERED}(暗い)。
 */
public class OrganCoreBlock extends Block {

    public static final MapCodec<OrganCoreBlock> CODEC = simpleCodec(OrganCoreBlock::new);
    public static final BooleanProperty WITHERED = OrganTissueBlock.WITHERED;

    public OrganCoreBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WITHERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WITHERED);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // 同じブロックの状態の変わり目(枯れ)は数えない。器官の手順が置き換えるときも数えない(OrganWorld.placing)
        if (!newState.is(this) && !state.getValue(WITHERED) && level instanceof ServerLevel server && !OrganWorld.placing()) {
            ForestOrgans.onCoreRemoved(server, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
