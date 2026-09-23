package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ヴェロフィル」(グループ4・爆撃役)の GeoModel。
 * 動きの確認は {@code tools/anim-preview}(モーションビューア)で。
 */
public class VelophyllModel extends LodGeoModel<VelophyllEntity> {

    @Override
    public ResourceLocation getModelResource(VelophyllEntity animatable) {
        return OvergrownVegetation.id("geo/velophyll.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(VelophyllEntity animatable) {
        return MobVariantTextures.of(animatable, "velophyll"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(VelophyllEntity animatable) {
        return OvergrownVegetation.id("animations/velophyll.animation.json");
    }
}
