package com.yayuy.overgrownvegetation.corruption.pushback;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>灰と石灰の帯</b>(押し返し 2026-11-18 夜 ユーザー決定)。 地面に線を引くように撒く薄い粉。力は要らない。汚染はこの線を越えられない({@link RingWards}。11-18 夜の直しで「輪」から「線」へ)。
 * 何度か侵入を止めると消える({@code ring.bandHits}【仮 16】回。撒き直す)。壊しても何も落ちない(撒いた粉なので。拾い直して回数を戻せないように)。昔ながらの素朴な手(灰と石灰は畑や家の境に撒かれてきた)。
 * 当たり判定は無い(レッドストーンの粉と同じく歩いて越えられる)。
 */
public class AshLimeBandBlock extends Block {

    public static final MapCodec<AshLimeBandBlock> CODEC = simpleCodec(AshLimeBandBlock::new);
    /** 止めた回数(すり減り)。 */
    public static final IntegerProperty WEAR = IntegerProperty.create("wear", 0, 15);
    /** くっついているブロックの向き(下 = 地面・上 = 天井・横 = 壁)。11-18 夜 ユーザー「壁とかブロックの底面にも設置できるように」。 */
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty ATTACH = BlockStateProperties.FACING;
    private static final java.util.Map<Direction, VoxelShape> SHAPES = new java.util.EnumMap<>(java.util.Map.of(
            Direction.DOWN, Block.box(0, 0, 0, 16, 1, 16), Direction.UP, Block.box(0, 15, 0, 16, 16, 16),
            Direction.NORTH, Block.box(0, 0, 0, 16, 16, 1), Direction.SOUTH, Block.box(0, 0, 15, 16, 16, 16),
            Direction.WEST, Block.box(0, 0, 0, 1, 16, 16), Direction.EAST, Block.box(15, 0, 0, 16, 16, 16)));

    public AshLimeBandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WEAR, 0).setValue(ATTACH, Direction.DOWN));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEAR, ATTACH);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(ATTACH));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction d = state.getValue(ATTACH);
        BlockPos support = pos.relative(d);
        return level.getBlockState(support).isFaceSturdy(level, support, d.getOpposite());
    }

    /** 撒いた面にくっつく(地面・壁・天井)。くっつけない面なら置けない。 */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
        BlockState s = defaultBlockState().setValue(ATTACH, ctx.getClickedFace().getOpposite());
        return canSurvive(s, ctx.getLevel(), ctx.getClickedPos()) ? s : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return dir == state.getValue(ATTACH) && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server && !old.is(this)) RingWards.addBand(server, pos, state.getValue(ATTACH));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        if (level instanceof ServerLevel server && !now.is(this)) RingWards.removeBand(server, pos);
        super.onRemove(state, level, pos, now, moved);
    }

    /** 1 回止めた。回数に届いたら消える(灰が舞う)。 */
    static void wear(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof AshLimeBandBlock)) return;
        // MOD の設定「押し返す道具の効き」(11-19): 止められる回数 × %。印(WEAR)は 16 段までなので、16 回を超える分は
        // 1 回ごとに 16 ÷ 回数 の確かさで 1 段進める(平均でその回数もつ)
        double hits = Math.max(1.0D, RingWards.table().bandHits * com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.pushbackEfficiency());
        int max = (int) Math.max(1, Math.min(16, Math.round(hits)));
        if (hits > 16.0D && level.random.nextDouble() >= 16.0D / hits) return;
        int next = s.getValue(WEAR) + 1;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D,
                4, 0.3D, 0.05D, 0.3D, 0.02D);
        if (next >= max) {
            level.removeBlock(pos, false);
            level.playSound(null, pos, SoundEvents.SAND_BREAK, SoundSource.BLOCKS, 0.6F, 0.8F);
        } else {
            level.setBlock(pos, s.setValue(WEAR, next), 2);
        }
    }
}
