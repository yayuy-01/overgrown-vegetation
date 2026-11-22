package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 倒木(コルダイテスの幹が寝たもの。2026-11-01)。横倒しの原木と違い、面ごとに別の絵を貼る:
 * 上 = 苔の乗った上面、下 = 湿った下面、横 = 側面(a/b)、端 = 切り口(a = 炭化した芯 / b = うろ)。変種は blockstate の乱数。
 * {@link #AXIS} は水平の 2 向きだけ。{@link #ROOT} = true は跳ね上がった根の皿(全部の面が根の皿の絵)。
 * 木の一部ではない({@code #logs} に入れない)ので、木の生成や樹冠の判定は倒木を地面・障害物として扱う。
 */
public class FallenLogBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty ROOT = BooleanProperty.create("root");

    public FallenLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(ROOT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, ROOT);
    }

    /** 手で置くと、見ている向きに沿って寝かせる。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, context.getHorizontalDirection().getAxis());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        return state;
    }
}
