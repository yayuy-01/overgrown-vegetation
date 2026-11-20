package com.yayuy.overgrownvegetation.organ;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 器官の肋(2026-11-20 洞窟の作り)。原木と同じく向き({@link #AXIS})を持つ。年輪の面(organ_rib_top)は向きの両端にだけ出る。
 * 既定は縦(y)で、器官の組み立て・触手の絵はこれまでどおり。亀裂の巣1 縫い目の肋は、割れ目をまたぐ向きに寝かせて置く
 * (縦のままだと上から年輪が見えて、2 段の切り株に見えた。11-20 ユーザー「形も直そう」)。
 */
public class OrganRibBlock extends OrganTissueBlock {

    public static final MapCodec<OrganRibBlock> CODEC = simpleCodec(OrganRibBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    public OrganRibBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(AXIS, ctx.getClickedFace().getAxis());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction.Axis a = state.getValue(AXIS);
        if (a == Direction.Axis.Y || rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) return state;
        return state.setValue(AXIS, a == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }
}
