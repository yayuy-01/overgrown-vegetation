package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedSheep;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ParasitizedSheepRenderer extends GeoEntityRenderer<ParasitizedSheep> {

    public ParasitizedSheepRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedSheepModel());
        this.shadowRadius = 0.7F;
    }
}
