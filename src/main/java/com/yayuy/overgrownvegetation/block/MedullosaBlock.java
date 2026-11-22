package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * メドゥロサ(ヤシに似た高さ 2〜3 の種子シダ。2026-11-01)の幹と冠。下から 幹(根元)・(幹)・冠 と積む多段の植物で、苗木は無い。
 * 幹は枝の継ぎ目と同じ 6 px 角の棒。冠は 1 マスの模型で、芯(10 px 角)のまわりへ板の葉を箱の外まで張り出す(隣のマスは使わない)。
 * {@link #FLAG} は幹なら根元(絵 b)、冠なら高さ 3 の冠(横 a + 垂れる葉。false は高さ 2 の冠 = 横 b)。
 * 下が支えでなくなったら次の tick に壊れる(竹やサボテンと同じく上へ順に崩れる)。
 */
public class MedullosaBlock extends Block {

    public static final MapCodec<MedullosaBlock> CODEC = simpleCodec(MedullosaBlock::new);
    public static final BooleanProperty FLAG = BooleanProperty.create("flag");

    private static final VoxelShape TRUNK = Block.box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0);
    private static final VoxelShape CROWN = Shapes.or(Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0), Block.box(3.0, 6.0, 3.0, 13.0, 16.0, 13.0));

    public MedullosaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FLAG, false));
    }

    @Override
    protected MapCodec<? extends MedullosaBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FLAG);
    }

    private boolean isCrown() {
        return this == ModBlocks.MEDULLOSA_CROWN.get();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return isCrown() ? CROWN : TRUNK;
    }

    /** 幹は塞がった上面か幹の上、冠は幹の上だけ。 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        if (under.is(ModBlocks.MEDULLOSA_TRUNK.get())) {
            return true;
        }
        return !isCrown() && under.isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
