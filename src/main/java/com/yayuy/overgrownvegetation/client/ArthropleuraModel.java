package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class ArthropleuraModel extends LodGeoModel<ArthropleuraEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/arthropleura.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/arthropleura.animation.json");

    @Override
    public ResourceLocation getModelResource(ArthropleuraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ArthropleuraEntity animatable) {
        return MobVariantTextures.of(animatable, "arthropleura"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ArthropleuraEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(25, 15, "head", 1.0F)
            .body("seg1", 0.3F, 1.0F, 30)
            .roll("seg1", 0.3F, 5)
            .origin(-22)
            .chain(1.0F, 0.9F, "seg2", "seg3", "seg4", "seg5", "seg6", "seg7", "seg8", "seg9", "seg10")
            .antennae(1.0F, "antenna_l", "antenna_r")
            // 脚 8 対は後ろの対ほど先に踏み出す(波が後ろから前へ伝わる)。左右は同じ位相(ヤスデの歩き)
            .legs(new LegSet(0.47F, 0.35F, 16, 4.6F)
                    .leg("leg2_l", 0.00F).leg("leg2_r", 0.00F).leg("leg3_l", 0.11F).leg("leg3_r", 0.11F)
                    .leg("leg4_l", 0.22F).leg("leg4_r", 0.22F).leg("leg5_l", 0.33F).leg("leg5_r", 0.33F)
                    .leg("leg6_l", 0.44F).leg("leg6_r", 0.44F).leg("leg7_l", 0.55F).leg("leg7_r", 0.55F)
                    .leg("leg8_l", 0.66F).leg("leg8_r", 0.66F).leg("leg9_l", 0.77F).leg("leg9_r", 0.77F));

    @Override
    public void setCustomAnimations(ArthropleuraEntity animatable, long instanceId, AnimationState<ArthropleuraEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
