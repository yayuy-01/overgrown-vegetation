package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「アルボラ」(G4・拡大種)の GeoModel(2026-11-05 parked から戻した。亜種の絵 {@code MobVariantTextures} は parked のままなので素の絵だけ)。
 */
public class ArboraModel extends LodGeoModel<ArboraEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/arbora.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/arbora.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/arbora.animation.json");

    /** アニメは 1 tick に 1 回だけ計算して補間する(大群の軽量化 C、2026-11-18。手続きの動きが無い種)。 */
    @Override
    protected boolean tickRateAnimation() {
        return true;
    }

    @Override
    public ResourceLocation getModelResource(ArboraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ArboraEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ArboraEntity animatable) {
        return ANIMATION;
    }
}
