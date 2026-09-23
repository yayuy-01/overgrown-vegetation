package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ProtophasmaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ProtophasmaModel extends LodGeoModel<ProtophasmaEntity> {

    @Override
    public ResourceLocation getModelResource(ProtophasmaEntity animatable) {
        return OvergrownVegetation.id("geo/protophasma.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ProtophasmaEntity animatable) {
        return MobVariantTextures.of(animatable, "protophasma"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ProtophasmaEntity animatable) {
        return OvergrownVegetation.id("animations/protophasma.animation.json");
    }
}
