package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.entity.EryopsEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「eryops」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class EryopsModel extends LodGeoModel<EryopsEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/eryops.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/eryops.animation.json");

    @Override
    public ResourceLocation getModelResource(EryopsEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(EryopsEntity animatable) {
        return MobVariantTextures.of(animatable, "eryops");
    }

    @Override
    public ResourceLocation getAnimationResource(EryopsEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(25, 12, "head", 1.0F)
            .body("body", 0.9F)
            .roll("body", 0.4F, 6)
            .chain(1.0F, 0.7F, "tail1", "tail2", "tail3", "tail4");

    @Override
    public void setCustomAnimations(EryopsEntity animatable, long instanceId, AnimationState<EryopsEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
