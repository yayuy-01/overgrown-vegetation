package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class StenodictyaLobataModel extends LodGeoModel<StenodictyaLobataEntity> {

    @Override
    public ResourceLocation getModelResource(StenodictyaLobataEntity animatable) {
        return OvergrownVegetation.id("geo/stenodictya_lobata.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(StenodictyaLobataEntity animatable) {
        return MobVariantTextures.of(animatable, "stenodictya_lobata"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(StenodictyaLobataEntity animatable) {
        return OvergrownVegetation.id("animations/stenodictya_lobata.animation.json");
    }
}
