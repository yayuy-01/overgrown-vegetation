package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class HylonomusModel extends LodGeoModel<HylonomusEntity> {

    @Override
    public ResourceLocation getModelResource(HylonomusEntity animatable) {
        return OvergrownVegetation.id("geo/hylonomus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(HylonomusEntity animatable) {
        return MobVariantTextures.of(animatable, "hylonomus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(HylonomusEntity animatable) {
        return OvergrownVegetation.id("animations/hylonomus.animation.json");
    }
}
