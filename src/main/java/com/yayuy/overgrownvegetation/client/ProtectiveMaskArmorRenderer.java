package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 「防護マスク」を、被っている人の頭の上に GeckoLib 立体モデルで描画する。
 * 生成は {@link ProtectiveMaskItem#createGeoRenderer} からクライアント限定で行われる。
 */
public class ProtectiveMaskArmorRenderer extends GeoArmorRenderer<ProtectiveMaskItem> {

    public ProtectiveMaskArmorRenderer() {
        super(new ProtectiveMaskModel());
        addRenderLayer(new com.yayuy.overgrownvegetation.client.decon.DirtyGeoArmorLayer<>(this)); // 汚れの粉(2026-11-14 段 3b、決定 37)
    }
}
