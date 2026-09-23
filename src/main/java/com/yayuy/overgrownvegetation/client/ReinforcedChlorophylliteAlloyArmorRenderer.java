package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ReinforcedChlorophylliteAlloyArmorItem;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 「強化クロロフィライト合金装備」を、着用者の上に GeckoLib 立体モデルで描画する。
 * 生成は {@link ReinforcedChlorophylliteAlloyArmorItem#createGeoRenderer} からクライアント限定で行われる。
 */
public class ReinforcedChlorophylliteAlloyArmorRenderer
        extends GeoArmorRenderer<ReinforcedChlorophylliteAlloyArmorItem> {

    public ReinforcedChlorophylliteAlloyArmorRenderer() {
        super(new ReinforcedChlorophylliteAlloyArmorModel());
    }
}
