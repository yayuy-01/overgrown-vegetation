package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.LungfishEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class LungfishRenderer extends GeoEntityRenderer<LungfishEntity> {

    public LungfishRenderer(EntityRendererProvider.Context context) {
        super(context, new LungfishModel());
        this.shadowRadius = 0.3F;
    }
}
