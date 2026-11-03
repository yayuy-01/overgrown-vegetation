package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 封印木の根 = スティグマリア(2026-11-02、下絵 32 の案 B。ユーザー指定)。幹の根元から地面を這う太い根で、先ほど低く地面に沈む。
 * {@link #DIRECTION} = 幹から外へ向かう 8 向き(0 = 北、1 = 北東 … 時計回り)。斜めは模型の中で 45° に回した箱(マスの対角の長さ)で、
 * 隣の斜めのマスの箱と角で端どうしが接する。{@link #STAGE} 0 / 1 / 2 = 高さ 9 / 6 / 3.5 px・太さ 12 / 11 / 10 px(2 は長さ半分)。数は仮決め。
 * 当たり判定は真っすぐなら箱のとおり、斜めは真ん中の 8 px 角(回した箱は形にできないので)。{@code #logs} には入れない。
 */
public class StigmariaBlock extends Block {

    public static final MapCodec<StigmariaBlock> CODEC = simpleCodec(StigmariaBlock::new);
    public static final IntegerProperty DIRECTION = IntegerProperty.create("direction", 0, 7);
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 2);
    /**
     * 地面が 1 段下がる列(2026-11-02 浮きの直し)。幹側の段差の面に沿って 16 px 降りる縦の箱(太さはその段・奥行き {@value #DROP_DEPTH} px)+ その段の箱。
     * 真っすぐの向きだけ(斜めは段差で根を止める)。形は仮決め。
     */
    public static final BooleanProperty DROP = BooleanProperty.create("drop");
    public static final double DROP_DEPTH = 9.0;

    /** 段ごとの高さ・太さ(px)。仮決め。 */
    public static final double[] HEIGHT = {9.0, 6.0, 3.5};
    public static final double[] WIDTH = {12.0, 11.0, 10.0};

    /** [向き][段][落ちる部品なら 1]。 */
    private static final VoxelShape[][][] SHAPES = new VoxelShape[8][3][2];

    static {
        for (int stage = 0; stage < 3; stage++) {
            double h = HEIGHT[stage], a = 8 - WIDTH[stage] / 2, b = 8 + WIDTH[stage] / 2;
            // 真っすぐ: 長さ 16(段 2 は幹の側の半分)。幹は外向きの反対側
            double far = stage == 2 ? 8 : 0, dd = DROP_DEPTH;
            SHAPES[0][stage][0] = Block.box(a, 0, far, b, h, 16);          // 北へ: 幹は南
            SHAPES[2][stage][0] = Block.box(0, 0, a, 16 - far, h, b);      // 東へ: 幹は西
            SHAPES[4][stage][0] = Block.box(a, 0, 0, b, h, 16 - far);      // 南へ: 幹は北
            SHAPES[6][stage][0] = Block.box(far, 0, a, 16, h, b);          // 西へ: 幹は東
            SHAPES[0][stage][1] = Shapes.or(SHAPES[0][stage][0], Block.box(a, 0, 16 - dd, b, 16, 16));
            SHAPES[2][stage][1] = Shapes.or(SHAPES[2][stage][0], Block.box(0, 0, a, dd, 16, b));
            SHAPES[4][stage][1] = Shapes.or(SHAPES[4][stage][0], Block.box(a, 0, 0, b, 16, dd));
            SHAPES[6][stage][1] = Shapes.or(SHAPES[6][stage][0], Block.box(16 - dd, 0, a, 16, 16, b));
            VoxelShape diagonal = Block.box(4, 0, 4, 12, h, 12);
            for (int d = 1; d < 8; d += 2) {
                SHAPES[d][stage][0] = diagonal;
                SHAPES[d][stage][1] = diagonal;
            }
        }
    }

    public StigmariaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(DIRECTION, 0).setValue(STAGE, 0).setValue(DROP, false));
    }

    @Override
    protected MapCodec<? extends StigmariaBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DIRECTION, STAGE, DROP);
    }

    /** 手で置くと、見ている向きへ伸びる付け根の段。 */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return defaultBlockState().setValue(DIRECTION, directionOf(context.getHorizontalDirection()));
    }

    /** 水平の 4 向きの {@link #DIRECTION} の値。 */
    public static int directionOf(net.minecraft.core.Direction d) {
        return switch (d) {
            case EAST -> 2;
            case SOUTH -> 4;
            case WEST -> 6;
            default -> 0;
        };
    }

    /** {@link #DIRECTION} の値の水平の歩み {dx, dz}。 */
    public static int[] step(int direction) {
        return switch (direction) {
            case 1 -> new int[]{1, -1};
            case 2 -> new int[]{1, 0};
            case 3 -> new int[]{1, 1};
            case 4 -> new int[]{0, 1};
            case 5 -> new int[]{-1, 1};
            case 6 -> new int[]{-1, 0};
            case 7 -> new int[]{-1, -1};
            default -> new int[]{0, -1};
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(DIRECTION)][state.getValue(STAGE)][state.getValue(DROP) ? 1 : 0];
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        int quarter = switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
        return state.setValue(DIRECTION, (state.getValue(DIRECTION) + quarter * 2) % 8);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        int d = state.getValue(DIRECTION);
        return switch (mirror) {
            case LEFT_RIGHT -> state.setValue(DIRECTION, (12 - d) % 8); // 南北を入れ替える
            case FRONT_BACK -> state.setValue(DIRECTION, (8 - d) % 8);  // 東西を入れ替える
            default -> state;
        };
    }
}
