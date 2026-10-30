package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「スクティムネス」のモデル定義。モデルはユーザー製(identifier だけ付け直したもの)、テクスチャは
 * {@code tools/scutimnes-texture/} の生成物、アニメーションは {@code tools/scutimnes-anim/} の生成物(手で直さない)。
 *
 * <p>モデルの初期姿勢(レストポーズ)は<b>盾を下ろした</b>姿勢で、ふだんの盾を上げた姿勢(待機アニメ)と逆。
 * 遠くでアニメを止めるとこの姿勢で固まるので、{@link MobRenderLod} では常に動かす側に入れてある(盾の上げ下げは遠くからも見えてほしい)。
 */
public class ScutimnesModel extends LodGeoModel<ScutimnesEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/scutimnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/scutimnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/scutimnes.animation.json");

    @Override
    public ResourceLocation getModelResource(ScutimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ScutimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ScutimnesEntity animatable) {
        return ANIMATION;
    }
}
