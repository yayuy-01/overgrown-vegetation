package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 汚染された原木({@code overgrown_log})。2026-10-31 に見た目の変種を 2 つ足した(どちらも同じブロックのまま。木 1 本を {@code state.is(OVERGROWN_LOG)} で辿る
 * {@code TreeVineConnector}・{@code SwampTreeFall}・{@code GiantTreeFeature}・{@code CorruptionRegistry} を壊さないため、別ブロックにしない):
 * <ul>
 *     <li>{@link #SKIRT} … 幹の苔のスカート(案 H の 31)。根元の 2〜3 段を苔と着生植物の付いた幹の絵に(湿った林床に近いほど緑が濃い)</li>
 *     <li>{@link #HOLLOW} … 木の洞(案 H の 43)。立った幹の 1 面に小さな穴。近づくと虫が飛び出し、ときどき穴の中で目が光る(住人の気配。音は付けない)</li>
 * </ul>
 * 置くのは {@code worldgen/ForcedTreeFeature}(生成する木だけ)。汚染で原木から化けた幹や手で置いた原木は変種にならない(既定値)。
 */
public class OvergrownLogBlock extends RotatedPillarBlock {

    public static final BooleanProperty SKIRT = BooleanProperty.create("skirt");
    public static final EnumProperty<Hollow> HOLLOW = EnumProperty.create("hollow", Hollow.class);

    public OvergrownLogBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SKIRT, false).setValue(HOLLOW, Hollow.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SKIRT, HOLLOW);
    }

    /** 構造物の回転で洞の向きも回す(軸は親が回す)。 */
    @Override
    public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        BlockState out = super.rotate(state, rotation);
        Hollow hollow = state.getValue(HOLLOW);
        return hollow == Hollow.NONE ? out : out.setValue(HOLLOW, Hollow.of(rotation.rotate(hollow.facing())));
    }

    /** 洞の気配(クライアントのランダム表示 tick。プレイヤーが近いときだけ)。描くのは {@code client/atmosphere/HollowDwellers}。 */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        Hollow hollow = state.getValue(HOLLOW);
        if (hollow != Hollow.NONE && level.isClientSide) {
            com.yayuy.overgrownvegetation.client.atmosphere.HollowDwellers.near(level, pos, hollow.facing(), random);
        }
    }

    /** 洞の穴の向き(無し・東西南北)。穴は立った幹({@code axis=y})にだけ開ける。 */
    public enum Hollow implements StringRepresentable {
        NONE("none", null),
        NORTH("north", Direction.NORTH),
        SOUTH("south", Direction.SOUTH),
        WEST("west", Direction.WEST),
        EAST("east", Direction.EAST);

        private final String name;
        private final Direction facing;

        Hollow(String name, Direction facing) {
            this.name = name;
            this.facing = facing;
        }

        public Direction facing() {
            return facing;
        }

        public static Hollow of(Direction d) {
            return switch (d) {
                case NORTH -> NORTH;
                case SOUTH -> SOUTH;
                case WEST -> WEST;
                case EAST -> EAST;
                default -> NONE;
            };
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
