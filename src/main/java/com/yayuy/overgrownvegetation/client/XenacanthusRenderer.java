package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.XenacanthusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class XenacanthusRenderer extends GeoEntityRenderer<XenacanthusEntity> {

    public XenacanthusRenderer(EntityRendererProvider.Context context) {
        super(context, new XenacanthusModel());
        this.shadowRadius = 0.7F;
    }
}
