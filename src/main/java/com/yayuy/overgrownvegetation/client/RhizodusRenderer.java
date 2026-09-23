package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class RhizodusRenderer extends GeoEntityRenderer<RhizodusEntity> {

    public RhizodusRenderer(EntityRendererProvider.Context context) {
        super(context, new RhizodusModel());
        this.shadowRadius = 0.7F;
    }
}
