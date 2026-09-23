package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BlightFernRenderer extends GeoEntityRenderer<BlightFernEntity> {

    public BlightFernRenderer(EntityRendererProvider.Context context) {
        super(context, new BlightFernModel());
        this.shadowRadius = 2.0F;
    }
}
