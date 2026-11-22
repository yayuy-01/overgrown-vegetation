package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.corruption.NetherTubeTrees;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>管の樹の羽根</b> {@code tube_tree_plume}(2026-11-12)。管の先ごとに大きな房 1 つ。動く模型は使わず、姿(blockstate)をぱっと切り替える
 * (密な群れなので重さのため。ユーザー了承)。
 * <ul>
 *     <li>{@code open}: 昼。羽根を開き、先が淡い青に光る(力を使う光 = 青)</li>
 *     <li>{@code closed}: 夜。閉じて暗い(光るのは根元の継ぎ目の黄緑だけ)</li>
 *     <li>{@code spitting}: 満月の夜。開いて胞子を吐く(見た目は open と同じ + 胞子の粒)。ネザーの時計は止まっているので、
 *     クライアントが満月を知るために姿に持たせた</li>
 *     <li>{@code retracted}: プレイヤーが近い。管の中へ引っ込み、管の口だけが見える</li>
 * </ul>
 * 姿はランダム tick で今の時刻(地上の時計)に合わせる(朝夕にばらばらに開け閉めする)。引っ込みは {@link NetherTubeTrees} がプレイヤーのまわりを見て付け外す。
 */
public class TubeTreePlumeBlock extends Block implements SimpleWaterloggedBlock {

    public enum Pose implements StringRepresentable {
        OPEN("open"), CLOSED("closed"), SPITTING("spitting"), RETRACTED("retracted");

        private final String name;

        Pose(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public boolean isOpen() {
            return this == OPEN || this == SPITTING;
        }
    }

    public static final EnumProperty<Pose> POSE = EnumProperty.create("pose", Pose.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /** 開いた羽根の光(仮。光と霧の段 C で見直す)。 */
    public static final int OPEN_LIGHT = 6;

    private static final VoxelShape OPEN_SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);
    private static final VoxelShape CLOSED_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 10.0, 12.0);
    private static final VoxelShape RETRACTED_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 3.0, 12.0);

    public TubeTreePlumeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POSE, Pose.OPEN).setValue(WATERLOGGED, false));
    }

    public static int lightOf(BlockState state) {
        return state.getValue(POSE).isOpen() ? OPEN_LIGHT : 0;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POSE, WATERLOGGED);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(POSE)) {
            case OPEN, SPITTING -> OPEN_SHAPE;
            case CLOSED -> CLOSED_SHAPE;
            case RETRACTED -> RETRACTED_SHAPE;
        };
    }

    /** 羽根は柔らかいので通り抜けられる(管の口の縁だけ当たる)。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(POSE) == Pose.RETRACTED ? RETRACTED_SHAPE : Shapes.empty();
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(ModBlocks.TUBE_TREE_STEM.get());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (dir == Direction.DOWN && !canSurvive(state, level, pos)) {
            return state.getValue(WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(WATERLOGGED);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    /** 今の時刻(地上の時計)の姿に合わせる。プレイヤーが近い間は引っ込んだまま。 */
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        NetherTubeTrees.settle(level, pos, state);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(POSE) != Pose.SPITTING) return;
        // 胞子の噴き出し(萌芽体と同じ粒)を房の先から上へ。ほかに青い胞子をときどき(2026-11-12 仮)
        for (int i = 0; i < 2; i++) {
            double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6;
            double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6;
            level.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_JET.get(), x, pos.getY() + 0.85, z,
                    (random.nextDouble() - 0.5) * 0.04, 0.08 + random.nextDouble() * 0.08, (random.nextDouble() - 0.5) * 0.04);
        }
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.WARPED_SPORE, pos.getX() + random.nextDouble(), pos.getY() + 1.0, pos.getZ() + random.nextDouble(),
                    0, 0.03, 0);
        }
    }
}
