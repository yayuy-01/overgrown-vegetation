package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import com.yayuy.overgrownvegetation.entity.HibbertopterusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「hibbertopterus」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class HibbertopterusModel extends LodGeoModel<HibbertopterusEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/hibbertopterus.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/hibbertopterus.animation.json");

    @Override
    public ResourceLocation getModelResource(HibbertopterusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(HibbertopterusEntity animatable) {
        return MobVariantTextures.of(animatable, "hibbertopterus");
    }

    @Override
    public ResourceLocation getAnimationResource(HibbertopterusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .body("prosoma", 0.6F)
            .roll("prosoma", 0.3F, 5)
            .chain(1.0F, 0.8F, "seg1", "seg2", "seg3", "seg4", "seg5", "seg6", "seg7", "seg8")
            .legs(new LegSet(0.35F, 0.5F, 10, 6.0F)
                    .leg("leg4_l", 0.0F, "leg4_shin_l", 0.6F).leg("leg4_r", 0.5F, "leg4_shin_r", 0.6F)
                    .leg("leg5_l", 0.5F, "leg5_shin_l", 0.6F).leg("leg5_r", 0.0F, "leg5_shin_r", 0.6F));

    @Override
    public void setCustomAnimations(HibbertopterusEntity animatable, long instanceId, AnimationState<HibbertopterusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG, animatable.onGround());
    }
}
