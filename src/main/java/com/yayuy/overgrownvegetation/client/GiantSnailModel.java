package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.entity.GiantSnailEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「giant_snail」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class GiantSnailModel extends LodGeoModel<GiantSnailEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/giant_snail.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/giant_snail.animation.json");

    @Override
    public ResourceLocation getModelResource(GiantSnailEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GiantSnailEntity animatable) {
        return MobVariantTextures.of(animatable, "giant_snail");
    }

    @Override
    public ResourceLocation getAnimationResource(GiantSnailEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(25, 15, "head", 1.0F)
            .body("foot", 0.6F)
            .antennae(1.0F, "stalk_l", "stalk_r");

    @Override
    public void setCustomAnimations(GiantSnailEntity animatable, long instanceId, AnimationState<GiantSnailEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
