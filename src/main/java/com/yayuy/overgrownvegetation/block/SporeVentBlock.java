package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.nest.NestVents;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「胞子の噴出孔」({@code spore_vent}、2026-09-20)。巣の噴気室から地上へ抜けた煙突の口。
 *
 * <p>生きている間、半径 {@code ventRadius}(既定 16)に:
 * <ul>
 *     <li><b>胞子濃度を上乗せ</b>する。上乗せは 0〜100 の濃度とは<b>別の値</b>で持ち、
 *     表示だけが 100% を超える(ユーザー指定 2026-09-19)。過繫茂 Mob の湧き率も同じだけ上がる。</li>
 *     <li><b>「噴き出した胞子」のデバフ</b>を掛ける —— 採掘速度の低下と、自然回復の鈍化
 *     ({@link NestVents})。</li>
 * </ul>
 *
 * <p>育ち具合({@link #GROWTH} 0〜3)は見た目だけの段階で、実際の % は
 * {@link SporeVentBlockEntity} が持つ。胞子ノードを壊すと枯れる。
 */
public class SporeVentBlock extends BaseEntityBlock {

    public static final MapCodec<SporeVentBlock> CODEC = simpleCodec(SporeVentBlock::new);

    /** 見た目の段階。0 = 生えたて、3 = 育ちきり。 */
    public static final IntegerProperty GROWTH = IntegerProperty.create("growth", 0, 3);

    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 12.0D, 14.0D);

    public SporeVentBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(GROWTH, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(GROWTH);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // 足元は要求しない —— 噴出孔は<b>煙突の口に蓋をするように</b>建つので、真下は空洞。
    // 足場を要求すると生成された端から壊れる(2026-09-20)。

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SporeVentBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, com.yayuy.overgrownvegetation.registry.ModBlockEntities.SPORE_VENT.get(),
                SporeVentBlockEntity::serverTick);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            NestVents.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
