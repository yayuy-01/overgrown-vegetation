package com.yayuy.overgrownvegetation.client.decon;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地脈の洗い場の模型(2026-11-22)。置いた洗い場とアイテムで同じ geo・絵・アニメを使う。正本は Blockbench の
 * {@code デスクトップ\blockbench\ley_washer_yayuy.bbmodel}(書き出して名前を ley_washer にそろえた)。
 *
 * <p><b>書き出し直すときの手順</b>: 模型の水の箱 {@code water}(-6,7,-6 → 6,8,6)は、書き出した geo で骨 {@code body} から抜き、
 * 自前の骨 {@code water} に入れる。置いた洗い場はこの骨を隠し、水は地形の模型(本物の水の絵・バイオームの色)で描く。アイテムは見せる。
 */
public final class WasherModel<T extends GeoAnimatable> extends GeoModel<T> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/ley_washer.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/ley_washer.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/ley_washer.animation.json");

    /**
     * 骨の見せ方を決める。焼いた模型は置いた洗い場とアイテムの全部で 1 つを使い回すので、描くたびに両方向へ決め直す。
     *
     * @param water 模型の水の箱を見せるか(アイテムだけ)
     * @param spray 噴き出し管の水の筋を見せるか(動いている間だけ。止まっていると筋が口の下に止まって見えるため【仮】)
     */
    static void showParts(BakedGeoModel model, boolean water, boolean spray) {
        model.getBone("water").ifPresent(bone -> bone.setHidden(!water));
        model.getBone("spray").ifPresent(bone -> bone.setHidden(!spray));
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return ANIMATION;
    }
}
