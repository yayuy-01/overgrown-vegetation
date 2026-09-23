package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PetrolacosaurusRenderer extends GeoEntityRenderer<PetrolacosaurusEntity> {

    public PetrolacosaurusRenderer(EntityRendererProvider.Context context) {
        super(context, new PetrolacosaurusModel());
        this.shadowRadius = 0.4F;
    }
}
