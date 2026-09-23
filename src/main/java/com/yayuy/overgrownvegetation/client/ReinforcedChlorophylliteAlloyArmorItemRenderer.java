package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ReinforcedChlorophylliteAlloyArmorItem;

/**
 * 「強化クロロフィライト合金装備」のアイテムアイコンを、平らなスプライトではなく
 * 着用時と同じ GeckoLib 立体モデルで描画する。
 *
 * <p>部位の出し分け・枠への収め方・共有モデルの後始末は {@link GeoArmorIconRenderer} 側。
 */
public class ReinforcedChlorophylliteAlloyArmorItemRenderer
        extends GeoArmorIconRenderer<ReinforcedChlorophylliteAlloyArmorItem> {

    public ReinforcedChlorophylliteAlloyArmorItemRenderer() {
        super(new ReinforcedChlorophylliteAlloyArmorModel());
    }
}
