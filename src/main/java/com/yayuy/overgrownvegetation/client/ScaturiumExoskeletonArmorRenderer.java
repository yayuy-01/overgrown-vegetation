package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ScaturiumExoskeletonArmorItem;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 「スカトゥリウムの外骨格」防具を、着用者の上に GeckoLib 立体モデルで描画する。
 * 生成は {@link ScaturiumExoskeletonArmorItem#createGeoRenderer} からクライアント限定で行われる。
 */
public class ScaturiumExoskeletonArmorRenderer extends GeoArmorRenderer<ScaturiumExoskeletonArmorItem> {

    public ScaturiumExoskeletonArmorRenderer() {
        super(new ScaturiumExoskeletonArmorModel());
    }
}
