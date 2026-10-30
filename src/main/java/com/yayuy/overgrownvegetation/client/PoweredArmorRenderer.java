package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import software.bernie.geckolib.renderer.GeoArmorRenderer;

/**
 * 「パワードアーマー」を着用者の上に GeckoLib の立体モデルで描く。
 * 生成は {@link PoweredArmorItem#createGeoRenderer} からクライアント限定で行われる。
 */
public class PoweredArmorRenderer extends GeoArmorRenderer<PoweredArmorItem> {

    public PoweredArmorRenderer() {
        super(new PoweredArmorModel());
    }
}
