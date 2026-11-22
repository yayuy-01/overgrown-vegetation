package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * シャジクモ(W2、2026-11-01)。川底・池底に 1〜3 段で立つ車軸藻。水中専用(バニラの海草と同じく自分が水源で、水の外には置けない)。
 *
 * <p>{@link #PART} は「何段の株の何段目か」(1 段 = 上の絵だけ、2 段 = 下 + 上、3 段 = 下 + 中 + 上)。揺れの重みを段の境目でつなぐため、
 * 株の高さごとに別の値にしている({@code SwayModels})。どの段が欠けても株ごと崩れて水に戻る(バニラの 2 段の草と同じ)。
 * {@link #KIND} は A(細く疎ら・明るい)と B(密・石灰の灰緑)。絵は {@code tools/water-plants}。
 */
public class CharaBlock extends Block implements LiquidBlockContainer {

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);

    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

    public CharaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(PART, Part.SINGLE).setValue(KIND, Kind.A));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, KIND);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return fluid.is(Fluids.WATER) && fluid.isSource() ? defaultBlockState() : null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Part part = state.getValue(PART);
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        boolean grounded = part.index == 0
                ? under.isFaceSturdy(level, below, Direction.UP) && !under.is(Blocks.MAGMA_BLOCK)
                : under.is(this) && under.getValue(PART) == part.previous();
        if (!grounded) {
            return false;
        }
        if (part.isTop()) {
            return true;
        }
        BlockState over = level.getBlockState(pos.above());
        return over.is(this) && over.getValue(PART) == part.next();
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 崩れるときは空気ではなく水へ戻す(水中に空気の穴を作らない)。
        return !state.canSurvive(level, pos)
                ? Blocks.WATER.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        return false;
    }

    /** 高さ {@code height}(1〜3)の株を下から置く。全部の段が水源で、根元が置ける所のときだけ置いて true。 */
    public static boolean placeStack(LevelAccessor level, BlockPos base, int height, Kind kind, Block block) {
        height = net.minecraft.util.Mth.clamp(height, 1, 3);
        Part[] parts = Part.stack(height);
        for (int i = 0; i < height; i++) {
            FluidState f = level.getFluidState(base.above(i));
            if (!level.getBlockState(base.above(i)).is(Blocks.WATER) || !f.isSource()) {
                return false;
            }
        }
        BlockState first = block.defaultBlockState().setValue(PART, parts[0]).setValue(KIND, kind);
        BlockPos below = base.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        // 根元から順に置く(置いた段が 1 つ下の段の形の更新を起こし、その時には上の段が揃っている)。
        for (int i = 0; i < height; i++) {
            level.setBlock(base.above(i), first.setValue(PART, parts[i]), Block.UPDATE_ALL);
        }
        return true;
    }

    /** 株の何段目か。{@code index} は根元から 0、{@code height} は株の段数。 */
    public enum Part implements StringRepresentable {
        SINGLE("single", 0, 1),
        BOTTOM_OF_2("bottom_of_2", 0, 2),
        TOP_OF_2("top_of_2", 1, 2),
        BOTTOM_OF_3("bottom_of_3", 0, 3),
        MIDDLE_OF_3("middle_of_3", 1, 3),
        TOP_OF_3("top_of_3", 2, 3);

        private final String name;
        public final int index;
        public final int height;

        Part(String name, int index, int height) {
            this.name = name;
            this.index = index;
            this.height = height;
        }

        public boolean isTop() {
            return index == height - 1;
        }

        Part previous() {
            return of(index - 1, height);
        }

        Part next() {
            return of(index + 1, height);
        }

        static Part of(int index, int height) {
            for (Part p : values()) {
                if (p.index == index && p.height == height) {
                    return p;
                }
            }
            return SINGLE;
        }

        static Part[] stack(int height) {
            Part[] out = new Part[height];
            for (int i = 0; i < height; i++) {
                out[i] = of(i, height);
            }
            return out;
        }

        /** 揺れの重みの範囲 {下の端, 上の端}(株全体で 0 → 1)。 */
        public float[] swayRange() {
            return new float[]{(float) index / height, (float) (index + 1) / height};
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** A = 細く疎らで明るい、B = 密で石灰の灰緑。 */
    public enum Kind implements StringRepresentable {
        A("a"),
        B("b");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
