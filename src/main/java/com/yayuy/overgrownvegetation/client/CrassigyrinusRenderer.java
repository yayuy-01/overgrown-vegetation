package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.CrassigyrinusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class CrassigyrinusRenderer extends GeoEntityRenderer<CrassigyrinusEntity> {

    public CrassigyrinusRenderer(EntityRendererProvider.Context context) {
        super(context, new CrassigyrinusModel());
        this.shadowRadius = 0.5F;
    }
}
