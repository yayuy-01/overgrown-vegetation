package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.GiantSnailEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class GiantSnailRenderer extends GeoEntityRenderer<GiantSnailEntity> {

    public GiantSnailRenderer(EntityRendererProvider.Context context) {
        super(context, new GiantSnailModel());
        this.shadowRadius = 0.4F;
    }
}
