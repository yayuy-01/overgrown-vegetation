package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「コエルロサウラヴス」のモデル定義。テクスチャはユーザー製(目と細部は手描き)。
 */
public class CoelurosauravusModel extends LodGeoModel<CoelurosauravusEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/coelurosauravus.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/coelurosauravus.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/coelurosauravus.animation.json");

    @Override
    public ResourceLocation getModelResource(CoelurosauravusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(CoelurosauravusEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(CoelurosauravusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(40, 25, "neck", 0.4F, "head", 0.6F)
            .body("body", 0.6F)
            .roll("body", 0.4F, 8)
            .chain(1.0F, 0.7F, "tail1", "tail2", "tail3", "tail4");

    @Override
    public void setCustomAnimations(CoelurosauravusEntity animatable, long instanceId, AnimationState<CoelurosauravusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG, animatable.getFlightState() == CoelurosauravusEntity.STATE_GROUND);
    }
}
