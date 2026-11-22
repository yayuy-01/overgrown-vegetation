package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 木生シダ(プサロニウス)の幹(2026-11-01)。木材でなく根のマント(縦の細い根の束)なので {@code #logs} には入れない(板材も無し)。
 * 木 1 本を辿る所({@code TreeCanopyGroundFinder} など)では {@code instanceof} で木の一部に数える。
 * {@link #BASE} = 根元(太い根と土・苔の絵 b)。上は細い根の絵 a。上下の面は根の束の断面。燃え方はバニラの原木と同じ(5 / 5)。
 */
public class TreeFernTrunkBlock extends RotatedPillarBlock {

    public static final BooleanProperty BASE = BooleanProperty.create("base");

    public TreeFernTrunkBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(BASE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BASE);
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 5;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 5;
    }
}
