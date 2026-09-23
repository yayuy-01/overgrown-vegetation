package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class RhizodusModel extends LodGeoModel<RhizodusEntity> {

    @Override
    public ResourceLocation getModelResource(RhizodusEntity animatable) {
        return OvergrownVegetation.id("geo/rhizodus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(RhizodusEntity animatable) {
        return MobVariantTextures.of(animatable, "rhizodus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(RhizodusEntity animatable) {
        return OvergrownVegetation.id("animations/rhizodus.animation.json");
    }
}
