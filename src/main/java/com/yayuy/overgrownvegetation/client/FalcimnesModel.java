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

    @Override
    public ResourceLocation getModelResource(FalcimnesEntity animatable) {
        return OvergrownVegetation.id("geo/falcimnes.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(FalcimnesEntity animatable) {
        return OvergrownVegetation.id("textures/entity/falcimnes.png");
    }

    @Override
    public ResourceLocation getAnimationResource(FalcimnesEntity animatable) {
        return OvergrownVegetation.id("animations/falcimnes.animation.json");
    }
}
