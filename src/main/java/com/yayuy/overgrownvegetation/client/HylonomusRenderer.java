package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class HylonomusRenderer extends GeoEntityRenderer<HylonomusEntity> {

    public HylonomusRenderer(EntityRendererProvider.Context context) {
        super(context, new HylonomusModel());
        this.shadowRadius = 0.25F;
    }
}
