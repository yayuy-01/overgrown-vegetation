package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class OrthacanthusModel extends LodGeoModel<OrthacanthusEntity> {

    @Override
    public ResourceLocation getModelResource(OrthacanthusEntity animatable) {
        return OvergrownVegetation.id("geo/orthacanthus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(OrthacanthusEntity animatable) {
        return MobVariantTextures.of(animatable, "orthacanthus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(OrthacanthusEntity animatable) {
        return OvergrownVegetation.id("animations/orthacanthus.animation.json");
    }
}
