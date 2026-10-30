package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.EryopsEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class EryopsRenderer extends GeoEntityRenderer<EryopsEntity> {

    public EryopsRenderer(EntityRendererProvider.Context context) {
        super(context, new EryopsModel());
        this.shadowRadius = 0.6F;
    }
}
