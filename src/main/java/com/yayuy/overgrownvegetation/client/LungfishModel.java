package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.entity.LungfishEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「lungfish」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class LungfishModel extends LodGeoModel<LungfishEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/lungfish.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/lungfish.animation.json");

    @Override
    public ResourceLocation getModelResource(LungfishEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(LungfishEntity animatable) {
        return MobVariantTextures.of(animatable, "lungfish");
    }

    @Override
    public ResourceLocation getAnimationResource(LungfishEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .look(15, 10, "head", 1.0F)
            .body("body", 1.0F)
            .climb(60, 25)
            .roll("body", 0.4F, 10)
            .chain(1.0F, 1.0F, "tail1", "tail2", "tail3");

    @Override
    public void setCustomAnimations(LungfishEntity animatable, long instanceId, AnimationState<LungfishEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
