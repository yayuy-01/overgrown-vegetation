package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ErebomnesEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「エレボムネス」のモデル定義。モデル・テクスチャ・アニメは {@code tools/g5-models/out/erebomnes/} の低キューブ版
 * (生成物。手で json を直さない。直すなら生成器を直して作り直す)。
 *
 * <p>コードで動かす骨は無い(2026-10-01)。抱えた獲物の位置は骨を読まず、実体の固定の値({@code ErebomnesEntity#GRAB_FORWARD}・
 * {@code GRAB_UP} = 抱えた姿勢の {@code grab_point})で決める —— bite_hold の間 grab_point は動かないので(NOTES)、
 * サーバーだけで決まり、クライアントの姿勢と食い違わない。
 */
public class ErebomnesModel extends LodGeoModel<ErebomnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/erebomnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/erebomnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/erebomnes.animation.json");

    @Override
    public ResourceLocation getModelResource(ErebomnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ErebomnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ErebomnesEntity animatable) {
        return ANIMATION;
    }
}
