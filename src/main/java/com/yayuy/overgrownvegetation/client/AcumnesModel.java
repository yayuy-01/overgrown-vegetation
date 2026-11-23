package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「アクムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\acumnes.bbmodel}(11-15 に作り直し案
 * {@code tools/g5-models/out/unadopted/remake/re_acumnes_r8} を写した)。描画倍率 1(全長 10.6・翅の幅 5.5・高さ 3.4 ブロック)。
 */
public class AcumnesModel extends GeoModel<AcumnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/acumnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/acumnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/acumnes.animation.json");

    @Override
    public ResourceLocation getModelResource(AcumnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AcumnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(AcumnesEntity animatable) {
        return ANIMATION;
    }

    /**
     * コードで動かす骨(2026-11-15 能力の段。アニメは触らない):
     * {@code needle_aim} = 針の俯角(狙撃・縫い止めの溜めの後半から相手へ下向きに。ファイルの X+ が「先が下がる」向きで、GeckoLib は X を符号反転して持つ)、
     * {@code stinger_charge} = 縫い止めの溜めの間だけ針の先の光を強めて脈打たせる(光は箱の大きさ)。
     * 書いた骨は resetStateChanges で印を下ろす(下ろさないと次のフレームで戻されず積み上がる。メモリ geckolib-custom-anim-and-easing-traps)。
     */
    @Override
    public void setCustomAnimations(AcumnesEntity animatable, long instanceId, software.bernie.geckolib.core.animation.AnimationState<AcumnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        float pt = animationState.getPartialTick();
        software.bernie.geckolib.cache.object.GeoBone needle = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone("needle_aim");
        if (needle != null) {
            float dep = animatable.needleDepression(pt);
            needle.setRotX(needle.getInitialSnapshot().getRotX() - dep * net.minecraft.util.Mth.DEG_TO_RAD);
            needle.resetStateChanges();
        }
        float glow = animatable.pinGlow(pt);
        if (glow != 1.0F) {
            software.bernie.geckolib.cache.object.GeoBone charge = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone("stinger_charge");
            if (charge != null) {
                charge.setScaleX(charge.getScaleX() * glow);
                charge.setScaleY(charge.getScaleY() * glow);
                charge.setScaleZ(charge.getScaleZ() * glow);
                charge.resetStateChanges();
            }
        }
    }
}
