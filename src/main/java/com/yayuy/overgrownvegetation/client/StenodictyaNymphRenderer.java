package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class StenodictyaNymphRenderer extends GeoEntityRenderer<StenodictyaNymphEntity> {

    public StenodictyaNymphRenderer(EntityRendererProvider.Context context) {
        super(context, new StenodictyaNymphModel());
        this.shadowRadius = 0.3F;
    }
}
