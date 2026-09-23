package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ArthropleuraModel extends LodGeoModel<ArthropleuraEntity> {

    @Override
    public ResourceLocation getModelResource(ArthropleuraEntity animatable) {
        return OvergrownVegetation.id("geo/arthropleura.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ArthropleuraEntity animatable) {
        return MobVariantTextures.of(animatable, "arthropleura"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ArthropleuraEntity animatable) {
        return OvergrownVegetation.id("animations/arthropleura.animation.json");
    }
}
