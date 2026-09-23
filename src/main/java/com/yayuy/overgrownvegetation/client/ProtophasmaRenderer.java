package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ProtophasmaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ProtophasmaRenderer extends GeoEntityRenderer<ProtophasmaEntity> {

    public ProtophasmaRenderer(EntityRendererProvider.Context context) {
        super(context, new ProtophasmaModel());
        this.shadowRadius = 0.3F;
    }
}
