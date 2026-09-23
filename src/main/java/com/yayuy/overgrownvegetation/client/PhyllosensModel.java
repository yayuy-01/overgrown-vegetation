package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「フィロセンス」(グループ4・索敵役)の GeoModel。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class PhyllosensModel extends LodGeoModel<PhyllosensEntity> {

    @Override
    public ResourceLocation getModelResource(PhyllosensEntity animatable) {
        return OvergrownVegetation.id("geo/phyllosens.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PhyllosensEntity animatable) {
        return MobVariantTextures.of(animatable, "phyllosens"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PhyllosensEntity animatable) {
        return OvergrownVegetation.id("animations/phyllosens.animation.json");
    }
}
