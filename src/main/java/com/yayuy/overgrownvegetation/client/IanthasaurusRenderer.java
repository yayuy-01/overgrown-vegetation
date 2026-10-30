package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class IanthasaurusRenderer extends GeoEntityRenderer<IanthasaurusEntity> {

    public IanthasaurusRenderer(EntityRendererProvider.Context context) {
        super(context, new IanthasaurusModel());
        this.shadowRadius = 0.9F; // 体格 2 倍に合わせて(GeoEntityRenderer は SCALE で影を広げない。2026-10-10)
    }
}
