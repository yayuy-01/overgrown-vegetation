package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.GeoCorpseBlockItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 死体アイテム({@link GeoCorpseBlockItem})を、ブロックと同じ GeckoLib 立体モデルで
 * 手持ち/インベントリ/落下アイテムとして描画する。
 *
 * <p>GUI/手持ちでの向き・位置・大きさは、アイテムモデル JSON
 * ({@code assets/overgrown_vegetation/models/item/overgrown_(種族)_corpse.json})の
 * {@code display} 部分で調整する({@code ClientHooks.handleCameraTransforms} が
 * この BEWLR に渡す前に適用するため)。インベントリでの照明は 3D モデル向けに切り替える。
 */
public class CorruptedCorpseItemRenderer extends GeoItemRenderer<GeoCorpseBlockItem> {

    public CorruptedCorpseItemRenderer() {
        super(new CorruptedCorpseItemModel());
        useAlternateGuiLighting();
    }
}
