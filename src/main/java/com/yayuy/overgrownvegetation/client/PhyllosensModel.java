package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「フィロセンス」(グループ4・索敵役)の GeoModel。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class PhyllosensModel extends LodGeoModel<PhyllosensEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/phyllosens.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/phyllosens.animation.json");

    @Override
    public ResourceLocation getModelResource(PhyllosensEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PhyllosensEntity animatable) {
        return MobVariantTextures.of(animatable, "phyllosens"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PhyllosensEntity animatable) {
        return ANIMATION;
    }
}
