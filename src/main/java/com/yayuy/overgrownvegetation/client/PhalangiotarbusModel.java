package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.CritterMotion;
import com.yayuy.overgrownvegetation.client.critter.CritterRig;
import com.yayuy.overgrownvegetation.client.critter.LegSet;
import software.bernie.geckolib.animation.AnimationState;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ファランギオターブス」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/phalangiotarbus.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/phalangiotarbus.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/phalangiotarbus.png}</li>
 * </ul>
 */
public class PhalangiotarbusModel extends LodGeoModel<PhalangiotarbusEntity> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/phalangiotarbus.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/phalangiotarbus.animation.json");

    @Override
    public ResourceLocation getModelResource(PhalangiotarbusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PhalangiotarbusEntity animatable) {
        return MobVariantTextures.of(animatable, "phalangiotarbus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PhalangiotarbusEntity animatable) {
        return ANIMATION;
    }

    /** 動きの層(注視・坂と旋回で傾く・尾と体節が道をなぞる・触角の遅れ・脚。G2・G3 共通、2026-10-22)。 */
    static final CritterRig RIG = CritterRig.create()
            .terrain()
            .look(30, 20, "head", 1.0F)
            .body("root", 0.8F)
            .roll("root", 0.5F, 8)
            // 8 本の脚(leg〜leg4 = 右の前から、leg5〜leg8 = 左の前から)。クモの交互の四脚: 右1・左2・右3・左4 と 左1・右2・左3・右4 が半周ずつ。
            // 関節(bone*)は足を上げている間だけ曲げる: 腿を上げ、脛を下へ畳む(2026-10-22、40 個の未使用の関節を使う)
            .legs(new LegSet(0.5F, 0.42F, 14, 9.0F)
                    .leg("leg", 0.0F, "bone2", 0.6F, "bone3", 0.4F, "bone4", -0.9F)
                    .leg("leg2", 0.5F, "bone7", 0.6F, "bone8", 0.4F, "bone9", -0.9F)
                    .leg("leg3", 0.0F, "bone12", 0.6F, "bone13", 0.4F, "bone14", -0.9F)
                    .leg("leg4", 0.5F, "bone17", 0.6F, "bone18", 0.4F, "bone19", -0.9F)
                    .leg("leg5", 0.5F, "bone22", 0.6F, "bone23", 0.4F, "bone24", -0.9F)
                    .leg("leg6", 0.0F, "bone27", 0.6F, "bone28", 0.4F, "bone29", -0.9F)
                    .leg("leg7", 0.5F, "bone32", 0.6F, "bone33", 0.4F, "bone34", -0.9F)
                    .leg("leg8", 0.0F, "bone37", 0.6F, "bone38", 0.4F, "bone39", -0.9F));

    @Override
    public void setCustomAnimations(PhalangiotarbusEntity animatable, long instanceId, AnimationState<PhalangiotarbusEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        CritterMotion.apply(this, animatable, animationState, RIG, !animatable.isClimbing() && !animatable.isOnCeiling());
    }
}
