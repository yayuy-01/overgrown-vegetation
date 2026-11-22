package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * コルダイテスの板根(2026-11-02、下絵 32 の案 A。ユーザー指定)。幹の面から段で下がる厚さ 4 px の板。
 * {@link #FACING} = 幹から外へ向かう向き。{@link #PART} = 段(幹から離れるほど低い)と、幹を這い上がる箱。
 * 箱は「幹側からの深さ(px)× 高さ(px)」で、当たり判定も箱のとおり(低い段は乗り越えられる)。{@code #logs} には入れない。
 */
public class CordaitesButtressBlock extends Block {

    public static final MapCodec<CordaitesButtressBlock> CODEC = simpleCodec(CordaitesButtressBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** 板の厚さ(px、マスの真ん中)。仮決め。 */
    public static final double THICKNESS = 4.0;

    /**
     * 段。箱は {深さの始め, 深さの終わり, 下, 上}(px。深さ 0 = 幹の面)。数は仮決め(下絵の報告のとおり)。
     * 1 列の幹 = NEAR → TIP(+ NEAR の上に CLIMB_TOP)、2×2 の幹 = BIG → MID → TIP(+ BIG の上に CLIMB → CLIMB_TOP = 24 px)。
     * 地面が 1 段下がる列は DROP。
     */
    public enum Part implements StringRepresentable {
        /** 2×2 の 1 段目(内 16 / 外 12)。 */
        BIG("big", new double[][]{{0, 8, 0, 16}, {8, 16, 0, 12}}),
        /** 2×2 の 2 段目(9 / 6)。 */
        MID("mid", new double[][]{{0, 8, 0, 9}, {8, 16, 0, 6}}),
        /** 1 列の 1 段目(12 / 9)。這い上がりへ繋ぐため幹側 6 px だけ 16 まで。 */
        NEAR("near", new double[][]{{0, 8, 0, 12}, {8, 16, 0, 9}, {0, 6, 12, 16}}),
        /** 先(4 / 2)。 */
        TIP("tip", new double[][]{{0, 8, 0, 4}, {8, 16, 0, 2}}),
        /** 幹を這い上がる 16 px(2×2 の 1 段目の上)。 */
        CLIMB("climb", new double[][]{{0, 6, 0, 16}}),
        /** 這い上がりの先 8 px。 */
        CLIMB_TOP("climb_top", new double[][]{{0, 4, 0, 8}}),
        /**
         * 地面が 1 段下がる列の段(2026-11-02 浮きの直し)。幹側の段差の面に沿って 16 px 降り(手前の板の下の端に届く)、外へ 5 px で続く。
         * 2 段目以降のどの段の代わりにもなる。形は仮決め。
         */
        DROP("drop", new double[][]{{0, 6, 0, 16}, {6, 16, 0, 5}});

        private final String name;
        final double[][] boxes;

        Part(String name, double[][] boxes) {
            this.name = name;
            this.boxes = boxes;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private static final Map<Direction, Map<Part, VoxelShape>> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Map<Part, VoxelShape> byPart = new EnumMap<>(Part.class);
            for (Part part : Part.values()) {
                VoxelShape shape = Shapes.empty();
                for (double[] b : part.boxes) {
                    shape = Shapes.or(shape, box(facing, b[0], b[1], b[2], b[3]));
                }
                byPart.put(part, shape);
            }
            SHAPES.put(facing, byPart);
        }
    }

    /** 外向き {@code facing} の板の箱(深さ d0..d1 は幹の側 = facing の反対の面から測る)。 */
    private static VoxelShape box(Direction facing, double d0, double d1, double y0, double y1) {
        double c0 = 8 - THICKNESS / 2, c1 = 8 + THICKNESS / 2;
        return switch (facing) {
            case NORTH -> Block.box(c0, y0, 16 - d1, c1, y1, 16 - d0);
            case SOUTH -> Block.box(c0, y0, d0, c1, y1, d1);
            case WEST -> Block.box(16 - d1, y0, c0, 16 - d0, y1, c1);
            default -> Block.box(d0, y0, c0, d1, y1, c1);
        };
    }

    public CordaitesButtressBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.TIP));
    }

    @Override
    protected MapCodec<? extends CordaitesButtressBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    /** 手で置くと、見ている向きへ下がる先の段。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING)).get(state.getValue(PART));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
