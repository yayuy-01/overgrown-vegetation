package com.yayuy.overgrownvegetation.client.ley;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地脈の柱の模型(2026-11-22)。置いた柱とアイテムで同じ geo・絵を使う。正本は Blockbench の
 * {@code デスクトップ\blockbench\ley_pylon_yayuy.bbmodel}(書き出して名前を ley_pylon にそろえた)。
 * 動きは無い(制御が 0 なのでアニメの json は読まれない。ファイルも置かない)。
 */
public final class LeyPylonModel<T extends GeoAnimatable> extends GeoModel<T> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/ley_pylon.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/ley_pylon.png");
    /** 使われない(アニメが無い)。GeoModel が名前を求めるので置くだけ。 */
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/ley_pylon.animation.json");

    /**
     * 電池の箱(骨 {@code battery})を見せるか決める。焼いた模型は全部の柱とアイテムで 1 つを使い回すので、描くたびに両方向へ決め直す
     * (前に描いた柱の状態が残らないように)。
     */
    static void showBattery(BakedGeoModel model, boolean show) {
        model.getBone("battery").ifPresent(bone -> bone.setHidden(!show));
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
