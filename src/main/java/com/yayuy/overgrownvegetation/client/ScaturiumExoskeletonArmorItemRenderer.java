package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ScaturiumExoskeletonArmorItem;

/**
 * 「スカトゥリウムの外骨格」防具のアイテムアイコンを、平らなスプライトではなく
 * 着用時と同じ GeckoLib 立体モデルで描画する(ユーザー要望 2026-09-16)。
 *
 * <p>部位の出し分け・枠への収め方・共有モデルの後始末は {@link GeoArmorIconRenderer} 側。
 */
public class ScaturiumExoskeletonArmorItemRenderer extends GeoArmorIconRenderer<ScaturiumExoskeletonArmorItem> {

    public ScaturiumExoskeletonArmorItemRenderer() {
        super(new ScaturiumExoskeletonArmorModel());
    }
}
