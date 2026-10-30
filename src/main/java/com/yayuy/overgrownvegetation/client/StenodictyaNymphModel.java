package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「stenodictya_nymph」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class StenodictyaNymphModel extends LodGeoModel<StenodictyaNymphEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/stenodictya_nymph.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/stenodictya_nymph.animation.json");

    @Override
    public ResourceLocation getModelResource(StenodictyaNymphEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(StenodictyaNymphEntity animatable) {
        return MobVariantTextures.of(animatable, "stenodictya_nymph");
    }

    @Override
    public ResourceLocation getAnimationResource(StenodictyaNymphEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(30, 20, "head", 1.0F)
            .body("body", 0.4F)
            .roll("body", 0.4F, 6)
            .chain(1.0F, 0.8F, "abdomen1", "abdomen2", "abdomen3")
            .antennae(1.0F, "antenna_l", "antenna_r")
            .legs(new LegSet(0.18F, 0.5F, 18, 2.4F)
                    .leg("leg_f_l", 0.0F, "shin_f_l", 0.6F).leg("leg_m_r", 0.0F, "shin_m_r", 0.6F).leg("leg_h_l", 0.0F, "shin_h_l", 0.6F)
                    .leg("leg_f_r", 0.5F, "shin_f_r", 0.6F).leg("leg_m_l", 0.5F, "shin_m_l", 0.6F).leg("leg_h_r", 0.5F, "shin_h_r", 0.6F));

    @Override
    public void setCustomAnimations(StenodictyaNymphEntity animatable, long instanceId, AnimationState<StenodictyaNymphEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
