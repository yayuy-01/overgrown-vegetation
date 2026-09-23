package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.nest.NestNodes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
 * 「胞子ノード」({@code spore_node}、2026-09-20)。巣の噴気室の中心に 1 つ置かれる。
 *
 * <h2>役割</h2>
 * <ul>
 *     <li><b>噴出孔を育てる</b> —— {@code ventGrowthIntervalTicks} ごとに 1%、地上へ伸びた噴出孔
 *     ({@link SporeVentBlock})を強くする。1 本が上限({@code ventBonusPerVentPercent})に達して
 *     さらに 1 間隔たつと次の 1 本が生え、{@code ventMaxCount} 本まで増える。</li>
 *     <li><b>汚染を速める</b> —— 世界にあるノードの数 × {@code nodeCorruptionPerNode} だけ
 *     汚染の広がりが速くなる({@link NestNodes#corruptionMultiplier})。数えるのは
 *     {@code nodeCorruptionMaxNodes} 個まで(ユーザー指定 2026-09-19)。</li>
 * </ul>
 *
 * <p>壊すと育った噴出孔も一緒に枯れる。女王室の奥の巣の核を倒しても止まる
 * ({@code nest/NestCoreEntity})。
 */
public class SporeNodeBlock extends BaseEntityBlock {

    public static final MapCodec<SporeNodeBlock> CODEC = simpleCodec(SporeNodeBlock::new);

    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 14.0D, 15.0D);

    public SporeNodeBlock(Properties properties) {
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SporeNodeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, com.yayuy.overgrownvegetation.registry.ModBlockEntities.SPORE_NODE.get(),
                SporeNodeBlockEntity::serverTick);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SporeNodeBlockEntity node) {
                node.wither(level);                 // 育てた噴出孔も枯らす
            }
            NestNodes.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
