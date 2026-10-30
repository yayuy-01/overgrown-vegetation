package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class MegarachneModel extends LodGeoModel<MegarachneEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/megarachne.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/megarachne.animation.json");

    @Override
    public ResourceLocation getModelResource(MegarachneEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MegarachneEntity animatable) {
        return MobVariantTextures.of(animatable, "megarachne"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(MegarachneEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .body("prosoma", 1.0F)
            .roll("prosoma", 0.4F, 6)
            .chain(1.0F, 0.8F, "abdomen", "tail", "telson");

    @Override
    public void setCustomAnimations(MegarachneEntity animatable, long instanceId, AnimationState<MegarachneEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
