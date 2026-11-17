package com.yayuy.overgrownvegetation.client.crawler;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.LodGeoModel;
import com.yayuy.overgrownvegetation.entity.GestatrixEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * ゲスタトリクスのモデル(2026-11-17)。<b>今は仮</b>: モデル制作(Fable)の形が来るまで、グレガリアの geo・絵・アニメを大きくして使う
 * (描画倍率 {@link GestatrixEntity#RENDER_SCALE})。本物が来たら下の 3 つを {@code gestatrix} の素材へ替え、{@link GestatrixEntity#FINAL_CLIPS} を true にする。
 */
public class GestatrixModel extends LodGeoModel<GestatrixEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/gregaria.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/gregaria.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/gregaria.animation.json");

    @Override
    public ResourceLocation getModelResource(GestatrixEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GestatrixEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(GestatrixEntity animatable) {
        return ANIMATION;
    }
}
