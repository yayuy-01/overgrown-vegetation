package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class PetrolacosaurusModel extends LodGeoModel<PetrolacosaurusEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/petrolacosaurus.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/petrolacosaurus.animation.json");

    @Override
    public ResourceLocation getModelResource(PetrolacosaurusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PetrolacosaurusEntity animatable) {
        return MobVariantTextures.of(animatable, "petrolacosaurus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PetrolacosaurusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(40, 25, "neck", 0.4F, "head", 0.6F)
            .body("body", 0.8F)
            .roll("body", 0.5F, 8)
            .chain(1.0F, 0.7F, "tail1", "tail2", "tail3", "tail4");

    @Override
    public void setCustomAnimations(PetrolacosaurusEntity animatable, long instanceId, AnimationState<PetrolacosaurusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
