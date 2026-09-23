package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 「ファルキムネス」の描画。 */
public class FalcimnesRenderer extends GeoEntityRenderer<FalcimnesEntity> {

    public FalcimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new FalcimnesModel());
        this.shadowRadius = 1.7F; // 体格 1.8 倍に合わせて(GeoEntityRenderer は SCALE で影を広げない)
    }
}
