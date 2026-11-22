package com.yayuy.overgrownvegetation.client.vent;

import com.yayuy.overgrownvegetation.survival.vent.VentBlockItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** 地脈の換気機のアイテムの見た目(置いた機械と同じ模型。縮め方は {@code models/item/ley_ventilator.json} の display)。 */
public class VentItemRenderer extends GeoItemRenderer<VentBlockItem> {

    public VentItemRenderer() {
        super(new VentModel<>());
    }
}
