package com.yayuy.overgrownvegetation.block;

import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 種族専用の GeckoLib 立体モデルで描画する汚染死体ブロック(羊・豚・クリーパー)。
 *
 * <p>侵食を広げる/侵食完了時に対応する Mob か汚染シダを湧かせる挙動は
 * {@link CorruptedCorpseBlock} と全く同じ({@link CorruptedCorpseBlockEntity} を共用)。
 * 違いは見た目だけで、ワールドでの描画は {@code RenderShape.INVISIBLE}
 * (チャンクメッシュには焼き込まれない)にして、実際の描画は GeckoLib の
 * {@code CorruptedCorpseRenderer}(GeoBlockRenderer)が 1 個体ずつ行う。
 * これにより
 * 「近づかないと描画されない」(遠くの個体は描画距離の外)挙動になる。
 */
public class GeoCorruptedCorpseBlock extends CorruptedCorpseBlock {


    public GeoCorruptedCorpseBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // 実際の描画は GeoBlockRenderer が行うので、通常のブロックモデル描画はしない。
        return RenderShape.INVISIBLE;
    }
}
