package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.DiplocaulusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class DiplocaulusRenderer extends GeoEntityRenderer<DiplocaulusEntity> {

    public DiplocaulusRenderer(EntityRendererProvider.Context context) {
        super(context, new DiplocaulusModel());
        this.shadowRadius = 0.45F;
    }
}
