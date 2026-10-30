package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「スペラエオプレウラ」のモデル定義。
 *
 * <p>テクスチャは亜種分けしない(洞窟にしか居ないので、硬化・毒性・耐火の亜種を作る意味が薄い)。
 * 発光は {@code textures/entity/spelaeopleura_glowmask.png} を
 * {@link SpelaeopleuraGlowLayer} が重ねる。
 */
public class SpelaeopleuraModel extends LodGeoModel<SpelaeopleuraEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/spelaeopleura.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/spelaeopleura.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/spelaeopleura.animation.json");

    @Override
    public ResourceLocation getModelResource(SpelaeopleuraEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SpelaeopleuraEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SpelaeopleuraEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(25, 15, "head", 1.0F)
            .body("husi", 0.25F)
            .roll("husi", 0.3F, 5)
            .origin(-15)
            .chain(1.0F, 0.9F, "husi2", "husi3", "husi4", "husi5", "husi6", "husi7", "husi8")
            // 脚(奇数 = 左、偶数 = 右)は後ろの対ほど先に踏み出す
            .legs(new LegSet(0.3F, 0.4F, 18, 2.5F)
                    .leg("leg", 0.00F).leg("leg2", 0.00F).leg("leg3", 0.12F).leg("leg4", 0.12F)
                    .leg("leg5", 0.24F).leg("leg6", 0.24F).leg("leg7", 0.36F).leg("leg8", 0.36F)
                    .leg("leg9", 0.48F).leg("leg10", 0.48F).leg("leg11", 0.60F).leg("leg12", 0.60F)
                    .leg("leg13", 0.72F).leg("leg14", 0.72F).leg("leg15", 0.84F).leg("leg16", 0.84F))
            // 待機で頭が止まっていた(ユーザー製のアニメは頭を動かさない)→ 小さく探る
            .sway("head", 1, 5, 3.1F, 0.0F)
            .sway("head", 0, 3, 2.3F, 0.3F);

    @Override
    public void setCustomAnimations(SpelaeopleuraEntity animatable, long instanceId, AnimationState<SpelaeopleuraEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG);
    }
}
