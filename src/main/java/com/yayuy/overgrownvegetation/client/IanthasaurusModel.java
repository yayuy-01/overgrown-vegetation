package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class IanthasaurusModel extends LodGeoModel<IanthasaurusEntity> {

    @Override
    public ResourceLocation getModelResource(IanthasaurusEntity animatable) {
        return OvergrownVegetation.id("geo/ianthasaurus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(IanthasaurusEntity animatable) {
        return MobVariantTextures.of(animatable, "ianthasaurus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(IanthasaurusEntity animatable) {
        return OvergrownVegetation.id("animations/ianthasaurus.animation.json");
    }
}
