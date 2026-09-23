package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedPig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ParasitizedPigRenderer extends GeoEntityRenderer<ParasitizedPig> {

    public ParasitizedPigRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedPigModel());
        this.shadowRadius = 0.7F;
    }
}
