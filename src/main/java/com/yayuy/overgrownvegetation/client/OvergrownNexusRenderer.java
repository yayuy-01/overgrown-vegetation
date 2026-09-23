package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class OvergrownNexusRenderer extends GeoEntityRenderer<OvergrownNexusEntity> {

    public OvergrownNexusRenderer(EntityRendererProvider.Context context) {
        super(context, new OvergrownNexusModel());
        this.shadowRadius = 2.0F;
    }
}
