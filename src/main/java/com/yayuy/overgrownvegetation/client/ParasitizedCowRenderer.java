package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedCow;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ParasitizedCowRenderer extends GeoEntityRenderer<ParasitizedCow> {

    public ParasitizedCowRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedCowModel());
        this.shadowRadius = 0.7F;
    }
}
