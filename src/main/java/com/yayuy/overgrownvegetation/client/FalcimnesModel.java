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

    /** 地形に合わせた足の置き方(2026-11-07)。キーのアニメの後に、胴の高さ・傾きと 4 本の脚の IK を重ねる。 */
    @Override
    public void setCustomAnimations(FalcimnesEntity animatable, long instanceId, software.bernie.geckolib.core.animation.AnimationState<FalcimnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        FalcimnesPostureRig.apply(this, animatable, animationState);   // 段 3: 性格の構え(鎌・首の先・翅)
        FalcimnesShapeRig.apply(this, animatable, animationState);     // 森の中を動く型 ② 段 3: 狭い所の構え・首を下げる構え
        FalcimnesLegRig.apply(this, animatable, animationState);
        FalcimnesBendRig.apply(this, animatable, animationState);   // 森の中を動く型 ② 段 2: 首は先の経路、腹は通った跡に沿って曲げる
    }
}
