package com.yayuy.overgrownvegetation.survival.expedition;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ExpeditionSpec;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>寝袋</b>(2026-11-16 サバイバルの背骨 段 4、決定 40)。野営の兵や鉱山の測量士が使う、毛布を革で巻いた寝具【仮】。
 * 地面に 1 マスの敷物として置き、右クリックで横になる。
 *
 * <ul>
 *     <li>どこでも横になれる(ネザー・エンドでも爆発しない。ベッドの {@code ServerPlayer#startSleepInBed} を通さない)。</li>
 *     <li>時刻は昼でも夜でも飛ばさない: 寝ている間は {@code FatigueTracker#holdDayRest} で「深く眠った」に届かせない。</li>
 *     <li>疲労は昼の休みと同じ速さで戻る({@code fatigue.restPerSecond}。眠っていれば戻る、の仕組みをそのまま使う)。</li>
 *     <li>帰りの地点は変えない(ベッドの処理を通さないので、決め直しが起きない)。</li>
 *     <li>8 ブロック以内(上下 5)に敵(バニラの敵・植物側の感染させる側)がいると横になれない(11-16 夜ユーザー、案 a)。</li>
 * </ul>
 *
 * <p>バニラの眠りの続き(ブロックが残っているか・起きたときに立つ位置)が働くよう、{@code isBed} を真にし、ベッドと同じ
 * {@code FACING}・{@code OCCUPIED} を持つ(NeoForge の既定の {@code setBedOccupied}・{@code getBedDirection} がそれを読む)。
 */
public class SleepingBagBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<SleepingBagBlock> CODEC = simpleCodec(SleepingBagBlock::new);
    public static final BooleanProperty OCCUPIED = BlockStateProperties.OCCUPIED;
    private static final VoxelShape SHAPE = box(0.0D, 0.0D, 0.0D, 16.0D, 3.0D, 16.0D);

    public SleepingBagBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(OCCUPIED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OCCUPIED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, net.minecraft.core.Direction dir, BlockState neighbor,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return dir == net.minecraft.core.Direction.DOWN && !canSurvive(state, level, pos)
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, LivingEntity sleeper) {
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.CONSUME;
        return tryLieDown(player, pos) ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }

    /** {@code player} を {@code pos} の寝袋に寝かせる(GameTest からも呼ぶ)。横になれなければ理由を出して false。 */
    public static boolean tryLieDown(Player player, BlockPos pos) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SleepingBagBlock)) return false;
        if (player.isSleeping() || !player.isAlive()) return false;
        if (state.getValue(OCCUPIED)) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.occupied"), true);
            return false;
        }
        Vec3 c = Vec3.atBottomCenterOf(pos);
        if (Math.abs(player.getX() - c.x) > 3.0D || Math.abs(player.getY() - c.y) > 2.0D || Math.abs(player.getZ() - c.z) > 3.0D) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.too_far_away"), true);
            return false;
        }
        ExpeditionSpec s = DesignData.sporeField().expedition();
        if (s.sleepingBagNeedsSafety() && !player.isCreative() && enemiesNear(player, pos, s.sleepingBagMonsterRadius())) {
            player.displayClientMessage(Component.translatable("block.minecraft.bed.not_safe"), true);
            return false;
        }
        player.startSleeping(pos);
        if (level instanceof ServerLevel server) server.updateSleepingPlayerList();
        return true;
    }

    /** {@code player} が寝袋で眠っているか。 */
    public static boolean isInBag(Player player) {
        return player.isSleeping() && player.getSleepingPos()
                .map(p -> player.level().getBlockState(p).getBlock() instanceof SleepingBagBlock)
                .orElse(false);
    }

    /** 横になれない敵: バニラの「休めない」敵と、植物側の感染させる側(G1・G2〜G8。モンスターの作りでない種もいる)。 */
    private static boolean enemiesNear(Player player, BlockPos pos, double radius) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        AABB box = new AABB(c.x - radius, c.y - 5.0D, c.z - radius, c.x + radius, c.y + 5.0D, c.z + radius);
        return !player.level().getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive()
                && ((e instanceof Monster m && m.isPreventingPlayerRest(player)) || PlantSideRules.isInfector(e))).isEmpty();
    }
}
