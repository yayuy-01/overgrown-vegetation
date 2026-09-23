package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「アルボラの卵」({@code arbora_egg}、2026-09-20)。巣の孵化室の床に並ぶ。
 *
 * <p>{@link ArboraEggBlockEntity} が {@code nest.json} の {@code eggHatchIntervalTicks} ごとに 1 回、
 * 孵化を試みる。孵ったアルボラは<b>必ず亜種</b>(硬化 / 毒性 / 耐火)になる —— 孵化室が世界に
 * 1 室でもあると亜種が解禁される、という決まりと対になっている(ユーザー指定 2026-09-19)。
 *
 * <p>壊すと何も落とさない。孵化室を無力化したければ卵を壊して回るのがいちばん早い、という設計。
 */
public class ArboraEggBlock extends BaseEntityBlock {

    public static final MapCodec<ArboraEggBlock> CODEC = simpleCodec(ArboraEggBlock::new);

    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 8.0D, 13.0D);

    public ArboraEggBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** 床の上にしか置けない(掘った天井から卵がぶら下がらないように)。 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == Direction.DOWN && !canSurvive(state, level, pos)
                ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArboraEggBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, com.yayuy.overgrownvegetation.registry.ModBlockEntities.ARBORA_EGG.get(),
                ArboraEggBlockEntity::serverTick);
    }
}
