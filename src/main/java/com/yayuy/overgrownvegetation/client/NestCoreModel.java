package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.NestCoreEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「巣の核」の GeoModel(2026-09-20)。ハイヴノードの geo / アニメーションを写し、
 * テクスチャだけ琥珀寄りに振ってある<b>仮の見た目</b>(見分けが付くように)。
 * {@code geo/nest_core.geo.json} / {@code animations/nest_core.animation.json} /
 * {@code textures/entity/nest_core.png} を差し替えれば作り直せる。
 */
public class NestCoreModel extends LodGeoModel<NestCoreEntity> {

    @Override
    public ResourceLocation getModelResource(NestCoreEntity animatable) {
        return OvergrownVegetation.id("geo/nest_core.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(NestCoreEntity animatable) {
        return OvergrownVegetation.id("textures/entity/nest_core.png");
    }

    @Override
    public ResourceLocation getAnimationResource(NestCoreEntity animatable) {
        return OvergrownVegetation.id("animations/nest_core.animation.json");
    }
}
