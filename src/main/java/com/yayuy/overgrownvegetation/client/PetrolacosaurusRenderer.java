package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PetrolacosaurusRenderer extends GeoEntityRenderer<PetrolacosaurusEntity> {

    public PetrolacosaurusRenderer(EntityRendererProvider.Context context) {
        super(context, new PetrolacosaurusModel());
        this.shadowRadius = 0.8F; // 体格 2 倍に合わせて(GeoEntityRenderer は SCALE で影を広げない。2026-10-10)
    }
}
