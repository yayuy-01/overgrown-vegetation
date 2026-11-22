package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Blockbench(GeckoLib 形式 {@code geo.json})製の立体モデルを使う、汚染ブロックの上面から生える植物
 * (丸まったシダ)。
 *
 * <p>{@link CorruptedGroundGrowthBlock} と生存条件は同じ(上面が塞がっていれば生える)。
 * 2026-09-11 までは {@code BaseEntityBlock} + GeckoLib の {@code GeoBlockRenderer} で 1 本ずつ毎フレーム
 * 描画していたが、地表の植物の約半分を占めるうえ描画が重いので、通常のブロックと同じく
 * チャンクメッシュへ焼き込む方式に変えた。geo.json は
 * {@link com.yayuy.overgrownvegetation.client.GeoStaticGeometry}(カスタムのモデル読み込み器)が
 * そのまま読んで焼き込むので、形とテクスチャは GeckoLib 描画と同一。
 *
 * <p>個体ごとの「向き」と「大きさ」は、以前はブロックエンティティに連続値で持っていたが、
 * 今は blockstate の重み付きバリアント(向き 16 段階 × 大きさ 4 段階 = 64 モデル)から
 * ブロック座標のハッシュで選ぶ(バニラの草の向きと同じ仕組み)。データを持たないので
 * 既存ワールドの個体もそのまま表示され、同じ場所は常に同じ見た目になる。
 */
public class CorruptedGeoGrowthBlock extends Block {


    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 16.0, 13.0);

    public CorruptedGeoGrowthBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                      LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
