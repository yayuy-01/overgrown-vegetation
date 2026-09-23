package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class OrthacanthusRenderer extends GeoEntityRenderer<OrthacanthusEntity> {

    public OrthacanthusRenderer(EntityRendererProvider.Context context) {
        super(context, new OrthacanthusModel());
        this.shadowRadius = 0.7F;
    }
}
