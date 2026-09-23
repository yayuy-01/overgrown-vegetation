package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.XenacanthusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class XenacanthusModel extends LodGeoModel<XenacanthusEntity> {

    @Override
    public ResourceLocation getModelResource(XenacanthusEntity animatable) {
        return OvergrownVegetation.id("geo/xenacanthus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(XenacanthusEntity animatable) {
        return MobVariantTextures.of(animatable, "xenacanthus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(XenacanthusEntity animatable) {
        return OvergrownVegetation.id("animations/xenacanthus.animation.json");
    }
}
