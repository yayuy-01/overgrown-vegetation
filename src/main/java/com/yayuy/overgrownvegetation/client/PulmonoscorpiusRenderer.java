package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PulmonoscorpiusRenderer extends GeoEntityRenderer<PulmonoscorpiusEntity> {

    public PulmonoscorpiusRenderer(EntityRendererProvider.Context context) {
        super(context, new PulmonoscorpiusModel());
        this.shadowRadius = 0.5F;
    }
}
