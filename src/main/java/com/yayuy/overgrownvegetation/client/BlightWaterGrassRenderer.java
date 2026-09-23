package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.BlightWaterGrassEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BlightWaterGrassRenderer extends GeoEntityRenderer<BlightWaterGrassEntity> {

    public BlightWaterGrassRenderer(EntityRendererProvider.Context context) {
        super(context, new BlightWaterGrassModel());
        this.shadowRadius = 2.0F;
    }
}
