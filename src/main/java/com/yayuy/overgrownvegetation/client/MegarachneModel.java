package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class MegarachneModel extends LodGeoModel<MegarachneEntity> {

    @Override
    public ResourceLocation getModelResource(MegarachneEntity animatable) {
        return OvergrownVegetation.id("geo/megarachne.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MegarachneEntity animatable) {
        return MobVariantTextures.of(animatable, "megarachne"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(MegarachneEntity animatable) {
        return OvergrownVegetation.id("animations/megarachne.animation.json");
    }
}
