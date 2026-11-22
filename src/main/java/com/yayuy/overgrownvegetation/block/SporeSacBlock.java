package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 胞子嚢の袋(S6、2026-11-01。id は hanging_spore_sac = 素材のアイテム spore_sac と別)。腕・枝の下面に吊り下がる袋。{@link #STAGE}: 小 → 大へ数分で育ち、大のまま。
 * 破れた後({@code burst})の絵と状態だけ先に用意した。破裂(G2: 触れる・矢・時間で破れて胞子を撒く)は次の段で作る(ユーザー指定)ので、
 * 今は {@code burst} へは誰も替えない。
 *
 * <p>育つ速さ: ランダムティックは 1 ブロックに平均約 68 秒に 1 回(randomTickSpeed 3)。{@link #GROW_CHANCE} 1/3 で平均約 3.4 分(仮決め)。
 */
public class SporeSacBlock extends Block {

    public static final MapCodec<SporeSacBlock> CODEC = simpleCodec(SporeSacBlock::new);

    public enum Stage implements StringRepresentable {
        SMALL("small"), LARGE("large"), BURST("burst");

        private final String name;

        Stage(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Stage> STAGE = EnumProperty.create("stage", Stage.class);
    /** 1 回のランダムティックで小が大になる確率(仮決め)。 */
    public static final float GROW_CHANCE = 1.0F / 3.0F;

    private static final VoxelShape SMALL_SHAPE = Block.box(4.0, 6.0, 4.0, 12.0, 16.0, 12.0);
    private static final VoxelShape LARGE_SHAPE = Block.box(2.0, 1.0, 2.0, 14.0, 16.0, 14.0);

    public SporeSacBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STAGE, Stage.SMALL));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(STAGE) == Stage.SMALL ? SMALL_SHAPE : LARGE_SHAPE;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.getValue(STAGE) == Stage.SMALL;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(STAGE) == Stage.SMALL && random.nextFloat() < GROW_CHANCE) {
            level.setBlock(pos, state.setValue(STAGE, Stage.LARGE), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        return level.getBlockState(abovePos).isFaceSturdy(level, abovePos, Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
