package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ArthropleuraRenderer extends GeoEntityRenderer<ArthropleuraEntity> {

    public ArthropleuraRenderer(EntityRendererProvider.Context context) {
        super(context, new ArthropleuraModel());
        this.shadowRadius = 0.7F;
    }
}
