package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 汚染死体ブロック(仮実装。専用モデル完成後に差し替え予定)。
 *
 * <p>「胞子感染」({@link com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect})で力尽きた
 * Mob が死んだ場所に設置される。blight_fern と同じく {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader}
 * を使ってこの場所を起点に(ただし小さめの半径で)侵食を広げる。実処理は
 * {@link CorruptedCorpseBlockEntity} を参照。
 *
 * <p>この基底クラスは {@code getRenderShape} が {@code MODEL}(通常のブロックモデル描画)。
 * 牛(Blockbench製の Java モデル)、ゾンビ・クモ(仮テクスチャの立方体)がこれを使う。
 * 羊・豚・クリーパーは GeckoLib 立体モデルで描くサブクラス {@link GeoCorruptedCorpseBlock}
 * ({@code getRenderShape} = {@code INVISIBLE})。{@link BaseEntityBlock} を継承しているのは、
 * 侵食を進める {@link CorruptedCorpseBlockEntity} の tick 処理を持たせるため
 * (加えて Geo 版は同 BlockEntity を {@code GeoBlockEntity} として描画に使う)。
 */
public class CorruptedCorpseBlock extends BaseEntityBlock {

    public static final MapCodec<CorruptedCorpseBlock> CODEC = simpleCodec(CorruptedCorpseBlock::new);

    public CorruptedCorpseBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CorruptedCorpseBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.CORRUPTED_CORPSE.get(), CorruptedCorpseBlockEntity::serverTick);
    }
}
