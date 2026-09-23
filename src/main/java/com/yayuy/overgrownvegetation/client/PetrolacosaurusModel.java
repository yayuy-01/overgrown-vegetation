package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class PetrolacosaurusModel extends LodGeoModel<PetrolacosaurusEntity> {

    @Override
    public ResourceLocation getModelResource(PetrolacosaurusEntity animatable) {
        return OvergrownVegetation.id("geo/petrolacosaurus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PetrolacosaurusEntity animatable) {
        return MobVariantTextures.of(animatable, "petrolacosaurus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PetrolacosaurusEntity animatable) {
        return OvergrownVegetation.id("animations/petrolacosaurus.animation.json");
    }
}
