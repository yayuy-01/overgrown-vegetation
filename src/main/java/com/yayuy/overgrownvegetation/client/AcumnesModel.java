package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 「アクムネス」のモデル定義。モデル・テクスチャはユーザー製の低キューブ版(箱 79)、アニメは
 * {@code tools/g5-models/mobs/AcumnesAnims.java} の生成物をそのモデルに流し直したもの(手で直さない。直すなら生成器を直して作り直す)。
 *
 * <p>コードで動かす骨(アニメは触らない): {@code needle_aim} … 針の俯角(照準の後半から標的へ下向きに。
 * {@link AcumnesEntity#getNeedleDepression})。ファイルの空間の X+ が「先が下がる」向きで、GeckoLib は X を符号反転して持つので
 * {@code 初期値 − 俯角}。({@code sight}・{@code muzzle} は照準線の起点に使っていた目印。照準線を消したので読んでいない。)
 */
public class AcumnesModel extends LodGeoModel<AcumnesEntity> {

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

    @Override
    public void setCustomAnimations(AcumnesEntity animatable, long instanceId, AnimationState<AcumnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        GeoBone needle = getAnimationProcessor().getBone("needle_aim");
        if (needle != null) {
            float dep = animatable.getNeedleDepression(animationState.getPartialTick());
            needle.setRotX(needle.getInitialSnapshot().getRotX() - dep * Mth.DEG_TO_RAD);
        }
    }
}
