package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class HylonomusRenderer extends GeoEntityRenderer<HylonomusEntity> {

    public HylonomusRenderer(EntityRendererProvider.Context context) {
        super(context, new HylonomusModel());
        this.shadowRadius = 0.6F; // 体格 2.5 倍に合わせて(GeoEntityRenderer は SCALE で影を広げない。2026-10-10)
    }
}
