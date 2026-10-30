package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ヴェロフィル」(グループ4・爆撃役)の GeoModel。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class VelophyllModel extends LodGeoModel<VelophyllEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/velophyll.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/velophyll.animation.json");

    @Override
    public ResourceLocation getModelResource(VelophyllEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(VelophyllEntity animatable) {
        return MobVariantTextures.of(animatable, "velophyll"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(VelophyllEntity animatable) {
        return ANIMATION;
    }
}
