package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.entity.DiplocaulusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「diplocaulus」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class DiplocaulusModel extends LodGeoModel<DiplocaulusEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/diplocaulus.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/diplocaulus.animation.json");

    @Override
    public ResourceLocation getModelResource(DiplocaulusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(DiplocaulusEntity animatable) {
        return MobVariantTextures.of(animatable, "diplocaulus");
    }

    @Override
    public ResourceLocation getAnimationResource(DiplocaulusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .look(20, 12, "head", 1.0F)
            .body("body", 0.8F)
            .climb(60, 25)
            .roll("body", 0.4F, 8)
            .chain(1.0F, 0.8F, "tail1", "tail2", "tail3");

    @Override
    public void setCustomAnimations(DiplocaulusEntity animatable, long instanceId, AnimationState<DiplocaulusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
