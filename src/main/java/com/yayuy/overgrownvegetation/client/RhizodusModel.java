package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class RhizodusModel extends LodGeoModel<RhizodusEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/rhizodus.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/rhizodus.animation.json");

    @Override
    public ResourceLocation getModelResource(RhizodusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(RhizodusEntity animatable) {
        return MobVariantTextures.of(animatable, "rhizodus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(RhizodusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .look(15, 10, "head", 1.0F)
            .body("body", 1.0F)
            .climb(70, 30)
            .roll("body", 0.4F, 10)
            .chain(1.0F, 1.0F, "tail1", "tail2", "tail3");

    @Override
    public void setCustomAnimations(RhizodusEntity animatable, long instanceId, AnimationState<RhizodusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
