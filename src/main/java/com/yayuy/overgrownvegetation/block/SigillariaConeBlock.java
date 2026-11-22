package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * 封印木の球果(垂れる胞子嚢。案 7、2026-11-01)。原木の側面に付き、{@link #FACING} は原木から外へ向く向き(原木は逆側)。
 * 原木が無くなると落ちる(壊れて自分を落とす)。
 *
 * <p>胞子を吐く(どちらも仮決め):
 * <ul>
 *     <li>サーバー: ランダムティック(平均 68 秒に 1 回)の {@value #EMIT_ONE_IN} 回に 1 回 = 平均 3.4 分に 1 回、胞子の場へ点の放出
 *     {@value #EMIT_AMOUNT}(過繁茂 1 チャンクの 1 日の放出が 1)。汚染を止めているとき({@code /ov corruption false})は出さない</li>
 *     <li>クライアント: {@link #animateTick}(近くのブロックは平均 2 秒ほどに 1 回呼ばれる)の {@value #PUFF_ONE_IN} 回に 1 回 = 平均 3〜4 分に 1 回、
 *     先から黄緑(#ace300)の粒を数個ふわっと出す。サーバーの放出とは別の抽選なので、粒の出た時と場へ足した時は揃わない</li>
 * </ul>
 */
public class SigillariaConeBlock extends HorizontalDirectionalBlock {


    /** ランダムティックのうち胞子の場へ放出する割合(1/n。仮決め)。 */
    static final int EMIT_ONE_IN = 3;
    /** 1 回の放出の量(仮決め。萌芽体 1 体の上乗せ sproutEmitBonus が 1 日 0.5、1 個の球果は 1 日 0.003 ほど)。 */
    static final double EMIT_AMOUNT = 0.0005;
    /** {@link #animateTick} のうち粒を出す割合(1/n。仮決め)。 */
    static final int PUFF_ONE_IN = 100;
    /** 胞子の粒の色(パレットの #ace300)と大きさ。 */
    private static final DustParticleOptions SPORE = new DustParticleOptions(new Vector3f(0xac / 255F, 0xe3 / 255F, 0F), 0.7F);

    // 原木に寄せた細い当たり(当たり判定は無く、狙い・壊すときの枠だけ)。
    private static final VoxelShape NORTH_SHAPE = Block.box(4.0, 0.0, 10.0, 12.0, 16.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(4.0, 0.0, 0.0, 12.0, 16.0, 6.0);
    private static final VoxelShape WEST_SHAPE = Block.box(10.0, 0.0, 4.0, 16.0, 16.0, 12.0);
    private static final VoxelShape EAST_SHAPE = Block.box(0.0, 0.0, 4.0, 6.0, 16.0, 12.0);

    public SigillariaConeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    /** 背中側が原木({@code #logs})なら付いていられる(仮決め: 封印木の原木に限らない)。 */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).is(BlockTags.LOGS);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction.getOpposite() == state.getValue(FACING) && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState();
        for (Direction direction : context.getNearestLookingDirections()) {
            if (direction.getAxis().isHorizontal()) {
                state = state.setValue(FACING, direction.getOpposite());
                if (state.canSurvive(context.getLevel(), context.getClickedPos())) {
                    return state;
                }
            }
        }
        return null;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(EMIT_ONE_IN) == 0 && !com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, pos, EMIT_AMOUNT);
        }
    }

    /** 先(模型の下端、原木から 3 画素離れた所)から黄緑の粒を 3〜5 個。粒はゆっくり昇りながら漂う。 */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(PUFF_ONE_IN) != 0) {
            return;
        }
        puff(state, level, pos, random);
    }

    /** 粒を出す(開発用の撮影からも呼べるように分けた)。 */
    public static void puff(BlockState state, Level level, BlockPos pos, RandomSource random) {
        Direction facing = state.getValue(FACING);
        double x = pos.getX() + 0.5 - facing.getStepX() * 0.3;
        double z = pos.getZ() + 0.5 - facing.getStepZ() * 0.3;
        double y = pos.getY() + 0.1;
        int n = 3 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            level.addParticle(SPORE,
                    x + (random.nextDouble() - 0.5) * 0.25, y + random.nextDouble() * 0.2, z + (random.nextDouble() - 0.5) * 0.25,
                    (random.nextDouble() - 0.5) * 0.02 + facing.getStepX() * 0.01, 0.01 + random.nextDouble() * 0.02,
                    (random.nextDouble() - 0.5) * 0.02 + facing.getStepZ() * 0.01);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
