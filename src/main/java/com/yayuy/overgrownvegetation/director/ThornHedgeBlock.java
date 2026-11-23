package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HedgeTable;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>棘の生垣</b>(森の防衛設備。2026-11-20 ユーザー決定「いったん棘の生垣だけ」「有刺鉄線のような物」「植物側の Mob はダメージを受けない」)。
 * 当たり判定は無く、踏み込んだ植物側でない生き物を鈍らせ、{@code damageIntervalTicks} ごとに傷つける(甘い実の茂みの傷)。
 * 植物側の Mob は傷つかず、{@code plantSidePasses} なら鈍らずに通る(森の側の道【仮】)。
 * 置くのは司令塔({@link ThornHedges}、バイオマスで置き直す)。ランダム tick で少しずつ自分で伸びる(予算なし・密集させない・器官の輪の外へは伸びない・
 * 押し返す道具の効く所へは伸びない)。ブロックなのでデスポーンせず、プレイヤーの近く(ランダム tick の届く所)でしか動かない。絵は仮。
 */
public class ThornHedgeBlock extends Block {

    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 14.0D, 15.0D);

    public ThornHedgeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, net.minecraft.core.Direction dir, BlockState neighbor,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !state.canSurvive(level, pos) ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living)) return;
        HedgeTable t = DesignData.director().hedge;
        boolean plant = PlantSideRules.isPlantSide(living);
        if (plant && t.plantSidePasses) return; // 森の側は棘に掛からない
        if (t.slow < 1.0D) entity.makeStuckInBlock(state, new Vec3(t.slow, 0.75D, t.slow));
        if (plant || !(level instanceof ServerLevel sl) || t.damage <= 0.0D) return;
        if (Math.floorMod(level.getGameTime() + entity.getId(), (long) t.damageIntervalTicks) != 0L) return; // 個体ごとに tick をずらす
        living.hurt(sl.damageSources().sweetBerryBush(), (float) t.damage);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        HedgeTable t = DesignData.director().hedge;
        if (!t.enabled || random.nextDouble() >= t.growChance) return;
        ThornHedges.grow(level, pos, random);
    }
}
