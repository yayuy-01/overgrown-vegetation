package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * 倒木の側面に付く立体の棚状の菌(2026-11-01。形はユーザーの Blockbench 模型: 笠 4×1×2 + 柄 2×0.5×1 が 5 個)。
 * {@link #FACING} は壁から離れる向き(壁ボタンと同じ)。5 個の位置は {@link #LAYOUT} で選ぶ配置の表 {@link #LAYOUTS}。
 * 置くとき(生成でも手でも)に配置を乱数で決める。当たり判定は笠の箱の和。
 *
 * <p>模型は {@code tools/floor/FloorModels.java} がこの表から作る(表を書き換えたら生成器を回す)。
 */
public class BracketFungusBlock extends HorizontalDirectionalBlock {


    /**
     * 笠の左下の角 (x, y)。北向き(壁は南 z 14〜16)で、笠は x..x+4・y..y+1・z 14..16、柄は笠の真ん中の下 y-0.5..y・z 15..16。
     * 0 番はユーザーの模型そのまま、1〜4 番は同じ決まり(笠どうしが重ならない)で Claude が散らした仮決め。
     */
    // LAYOUTS-BEGIN
    public static final int[][][] LAYOUTS = {
            {{7, 12}, {11, 2}, {1, 5}, {1, 13}, {9, 8}},
            {{2, 11}, {9, 13}, {10, 6}, {3, 3}, {6, 8}},
            {{0, 9}, {5, 12}, {11, 10}, {8, 4}, {2, 2}},
            {{4, 14}, {12, 12}, {1, 7}, {7, 5}, {11, 1}},
            {{6, 13}, {0, 12}, {12, 8}, {5, 6}, {1, 1}},
    };
    // LAYOUTS-END

    public static final IntegerProperty LAYOUT = IntegerProperty.create("layout", 0, LAYOUTS.length - 1);

    private static final Map<Direction, VoxelShape[]> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape[] shapes = new VoxelShape[LAYOUTS.length];
            for (int i = 0; i < LAYOUTS.length; i++) {
                VoxelShape shape = Shapes.empty();
                for (int[] cap : LAYOUTS[i]) {
                    shape = Shapes.or(shape, rotated(facing, cap[0], cap[1], cap[1] + 1, 14.0, cap[0] + 4, 16.0));
                }
                shapes[i] = shape.optimize();
            }
            SHAPES.put(facing, shapes);
        }
    }

    /** 北向きの箱を {@code facing} へ回す(blockstate の y 回転と同じ: 北 → 東 → 南 → 西)。 */
    private static VoxelShape rotated(Direction facing, double x1, double y1, double y2, double z1, double x2, double z2) {
        return switch (facing) {
            case EAST -> Block.box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case SOUTH -> Block.box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case WEST -> Block.box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            default -> Block.box(x1, y1, z1, x2, y2, z2);
        };
    }

    public BracketFungusBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LAYOUT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LAYOUT);
    }

    /** 壁の向きと配置を決めた状態。配置は座標から決める(同じ所なら同じ形)。 */
    public static BlockState forWall(Block block, Direction facing, BlockPos pos) {
        int layout = (int) Math.floorMod(Mth.getSeed(pos), (long) LAYOUTS.length);
        return block.defaultBlockState().setValue(FACING, facing).setValue(LAYOUT, layout);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) {
            return null;
        }
        BlockState state = forWall(this, face, context.getClickedPos());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos wall = pos.relative(facing.getOpposite());
        return level.getBlockState(wall).isFaceSturdy(level, wall, facing);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING))[state.getValue(LAYOUT)];
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
