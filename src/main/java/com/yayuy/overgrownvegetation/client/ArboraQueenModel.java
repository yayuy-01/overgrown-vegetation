package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArboraQueenEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「アルボラの女王」の GeoModel(2026-09-20)。
 *
 * <p><b>現状は仮の見た目</b> —— アルボラの geo とアニメーションを写して色をずらしただけで、
 * 「後部が膨らむ」形はまだ入っていない。{@code geo/arbora_queen.geo.json} /
 * {@code animations/arbora_queen.animation.json} / {@code textures/entity/arbora_queen.png} を
 * 差し替えれば Java を触らずに作り直せる。アニメーション名は
 * {@code idle} / {@code walk} / {@code run} / {@code attack} / {@code shockwave} / {@code scythe}
 * ({@code shockwave} と {@code scythe} は今のところ {@code attack} の写し)。
 */
public class ArboraQueenModel extends LodGeoModel<ArboraQueenEntity> {

    @Override
    public ResourceLocation getModelResource(ArboraQueenEntity animatable) {
        return OvergrownVegetation.id("geo/arbora_queen.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ArboraQueenEntity animatable) {
        return MobVariantTextures.of(animatable, "arbora_queen");
    }

    @Override
    public ResourceLocation getAnimationResource(ArboraQueenEntity animatable) {
        return OvergrownVegetation.id("animations/arbora_queen.animation.json");
    }
}
