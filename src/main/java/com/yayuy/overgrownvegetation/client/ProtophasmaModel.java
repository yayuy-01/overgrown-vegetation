package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ProtophasmaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ProtophasmaModel extends LodGeoModel<ProtophasmaEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/protophasma.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/protophasma.animation.json");

    @Override
    public ResourceLocation getModelResource(ProtophasmaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ProtophasmaEntity animatable) {
        return MobVariantTextures.of(animatable, "protophasma"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ProtophasmaEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(35, 20, "head", 1.0F)
            .body("body", 0.8F)
            .roll("body", 0.6F, 8)
            .chain(0.8F, 0.5F, "abdomen")
            .antennae(1.2F, "antenna_l", "antenna_r");

    @Override
    public void setCustomAnimations(ProtophasmaEntity animatable, long instanceId, AnimationState<ProtophasmaEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
