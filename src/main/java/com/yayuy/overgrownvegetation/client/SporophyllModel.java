package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SporophyllEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「スポロフィル」(グループ4・支援役)の GeoModel。
 * 体はフィロセンスの流用なので、{@code idle} / {@code walk} / {@code run} は
 * {@code phyllosens.animation.json} と同じ中身になっている。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class SporophyllModel extends LodGeoModel<SporophyllEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/sporophyll.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/sporophyll.animation.json");

    @Override
    public ResourceLocation getModelResource(SporophyllEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SporophyllEntity animatable) {
        return MobVariantTextures.of(animatable, "sporophyll"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(SporophyllEntity animatable) {
        return ANIMATION;
    }
}
