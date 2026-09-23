package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;

/**
 * 「防護マスク」のアイテムアイコンを、平らなスプライトではなく着用時と同じ GeckoLib 立体モデルで
 * 描画する(ユーザー要望 2026-09-17)。
 *
 * <p>部位の出し分け・枠への収め方・共有モデルの後始末は {@link GeoArmorIconRenderer} 側。
 */
public class ProtectiveMaskItemRenderer extends GeoArmorIconRenderer<ProtectiveMaskItem> {

    public ProtectiveMaskItemRenderer() {
        super(new ProtectiveMaskModel());
    }
}
