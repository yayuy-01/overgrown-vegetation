package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「アルボラ」(グループ4・拡大種)の GeoModel。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class ArboraModel extends LodGeoModel<ArboraEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/arbora.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/arbora.animation.json");

    @Override
    public ResourceLocation getModelResource(ArboraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ArboraEntity animatable) {
        return MobVariantTextures.of(animatable, "arbora"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ArboraEntity animatable) {
        return ANIMATION;
    }
}
