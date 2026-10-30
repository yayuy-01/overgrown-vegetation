package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ファルキムネス」のモデル定義。モデルはユーザー製、テクスチャは {@code tools/mob-texture/MobTex.java} の生成物
 * (翅の裏面を表からの写しで埋め、葉の穴の黒い点を周りの色で埋めてある)、アニメーションは
 * {@code tools/falcimnes-anim/FalcAnim.java} の生成物(手で直さない)。
 */
public class FalcimnesModel extends LodGeoModel<FalcimnesEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/falcimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/falcimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/falcimnes.animation.json");

    @Override
    public ResourceLocation getModelResource(FalcimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(FalcimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FalcimnesEntity animatable) {
        return ANIMATION;
    }
}
