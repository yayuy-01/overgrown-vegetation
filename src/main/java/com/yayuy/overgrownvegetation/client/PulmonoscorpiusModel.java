package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;

/** 「pulmonoscorpius」のモデル定義(G2・G3 の新しい種、2026-10-22。生成器 tools/g5-models/mobs/G23N*)。 */
public class PulmonoscorpiusModel extends LodGeoModel<PulmonoscorpiusEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/pulmonoscorpius.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/pulmonoscorpius.animation.json");

    @Override
    public ResourceLocation getModelResource(PulmonoscorpiusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PulmonoscorpiusEntity animatable) {
        return MobVariantTextures.of(animatable, "pulmonoscorpius");
    }

    @Override
    public ResourceLocation getAnimationResource(PulmonoscorpiusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・脚。G2・G3 共通)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .body("prosoma", 0.5F)
            .roll("prosoma", 0.4F, 6)
            .chain(0.8F, 0.6F, "meso1", "meso2", "meso3", "meso4", "meso5", "meso6")
            .legs(new LegSet(0.3F, 0.5F, 14, 6.0F)
                    .leg("leg1_l", 0.0F, "shin1_l", 0.6F).leg("leg1_r", 0.5F, "shin1_r", 0.6F)
                    .leg("leg2_l", 0.5F, "shin2_l", 0.6F).leg("leg2_r", 0.0F, "shin2_r", 0.6F)
                    .leg("leg3_l", 0.0F, "shin3_l", 0.6F).leg("leg3_r", 0.5F, "shin3_r", 0.6F)
                    .leg("leg4_l", 0.5F, "shin4_l", 0.6F).leg("leg4_r", 0.0F, "shin4_r", 0.6F));

    @Override
    public void setCustomAnimations(PulmonoscorpiusEntity animatable, long instanceId, AnimationState<PulmonoscorpiusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
