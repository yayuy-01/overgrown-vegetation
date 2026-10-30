package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BathymnesEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 「バティムネス」のモデル定義。モデル・テクスチャ・アニメは {@code tools/g5-models/out/bathymnes/} の低キューブ版
 * (生成物。手で json を直さない。直すなら生成器 {@code tools/g5-models/mobs/BathymnesAnims.java} などを直して作り直す)。
 *
 * <p>コードで動かす骨: {@code body} … 泳ぐ上下の向き(実体の {@code xRot}、+ で鼻先が下がる)をアニメの上に足す
 * (NOTES の「泳ぐ向きの上下(ピッチ)はここに掛けるのがよい」)。ファイルの空間の X+ が「前が下がる」向きで、GeckoLib は X を
 * 符号反転して持つので {@code アニメの値 − ピッチ}(アクムネスの針と同じ)。回す軸は {@code body} の骨の軸 (0, 11.5, 4) で、
 * 当たり判定の部位・掴んだ獲物の置き場所({@code BathymnesBody#toWorld})も同じ軸で回している。
 * ram・grab・drag・陸・死の間はサーバーがピッチを 0 へ戻す(それらのアニメは自分で体を傾ける)。
 */
public class BathymnesModel extends LodGeoModel<BathymnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/bathymnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/bathymnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/bathymnes.animation.json");

    @Override
    public ResourceLocation getModelResource(BathymnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(BathymnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(BathymnesEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(BathymnesEntity animatable, long instanceId, AnimationState<BathymnesEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        GeoBone body = getAnimationProcessor().getBone("body");
        if (body != null) {
            float pitch = animatable.getViewXRot(animationState.getPartialTick());
            if (pitch != 0.0F) {
                body.setRotX(body.getRotX() - pitch * Mth.DEG_TO_RAD);
            }
        }
    }
}
