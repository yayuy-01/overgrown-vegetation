package com.yayuy.overgrownvegetation.ecosystem.life;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 梢の繭(2026-11-18、環境生物の棲み処の 2 つ目・羽化。11-16「羽化は続ける」・11-18「羽化は繭を通す」)。
 * ステノディクティアの幼虫が育ち切ると葉の下面に作って入り、{@link #EMERGE_TICKS} で成虫が出る。繭は段 {@link #STAGE} 0→2 で膨らむ。
 * 壊すと(押し返しで消えても)幼虫が出てくる。tick しない(予定の tick を 3 回だけ)。絵は仮(Claude 11-18)。
 */
public class EcoCocoonBlock extends Block {

    public static final MapCodec<EcoCocoonBlock> CODEC = simpleCodec(EcoCocoonBlock::new);
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 2);
    /** 繭に入ってから成虫が出るまで(1 日)【仮】。 */
    public static final int EMERGE_TICKS = 24000;
    private static final VoxelShape[] SHAPES = {
            Block.box(5.5D, 4.5D, 5.0D, 10.5D, 16.0D, 11.0D),
            Block.box(5.0D, 4.0D, 5.0D, 11.0D, 16.0D, 11.0D),
            Block.box(4.5D, 3.0D, 4.5D, 11.5D, 16.0D, 11.5D)};
    /** 羽化で消すとき(幼虫を出さない)。 */
    private static final ThreadLocal<Boolean> EMERGING = ThreadLocal.withInitial(() -> false);

    public EcoCocoonBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(STAGE, 0));
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
        return SHAPES[state.getValue(STAGE)];
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.above()).is(BlockTags.LEAVES);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return dir == Direction.UP && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState() : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    /** 置かれたら、3 回に分けて膨らみ、最後に羽化する予定を入れる。 */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (!old.is(this)) level.scheduleTick(pos, this, EMERGE_TICKS / 3);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int s = state.getValue(STAGE);
        if (s < 2) {
            level.setBlock(pos, state.setValue(STAGE, s + 1), Block.UPDATE_CLIENTS);
            level.scheduleTick(pos, this, EMERGE_TICKS / 3);
        } else {
            emerge(level, pos);
        }
    }

    /** 羽化: 繭を消して、成虫をその場(葉の下面)に出す。出た成虫を返す。 */
    @javax.annotation.Nullable
    public static Mob emerge(ServerLevel level, BlockPos pos) {
        EMERGING.set(true);
        try {
            level.removeBlock(pos, false);
        } finally {
            EMERGING.set(false);
        }
        level.sendParticles(ParticleTypes.WHITE_ASH, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 8, 0.2D, 0.3D, 0.2D, 0.0D);
        Mob adult = spawn(level, pos, "stenodictya_lobata");
        if (adult instanceof com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity l) l.emergeFrom(pos);   // ぶら下がった姿で emerge を流す
        return adult;
    }

    @javax.annotation.Nullable
    static Mob spawn(ServerLevel level, BlockPos pos, String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id(id));
        if (!(type.create(level) instanceof Mob m)) return null;
        m.moveTo(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.CONVERSION, null);
        m.addTag(EcoDens.DEN_TAG);
        return level.addFreshEntity(m) ? m : null;
    }

    /** 壊れた・押し返しで消えた: 中の幼虫が出てくる。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!newState.is(this) && !EMERGING.get() && level instanceof ServerLevel sl) spawn(sl, pos, "stenodictya_nymph");
        super.onRemove(state, level, pos, newState, moved);
    }

    /** 膨らんで揺れる印: 時々、繭から細かな粉が落ちる。 */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) != 0) return;
        level.addParticle(ParticleTypes.WHITE_ASH, pos.getX() + 0.4D + random.nextDouble() * 0.2D, pos.getY() + 0.35D, pos.getZ() + 0.4D + random.nextDouble() * 0.2D, 0.0D, -0.02D, 0.0D);
    }
}
