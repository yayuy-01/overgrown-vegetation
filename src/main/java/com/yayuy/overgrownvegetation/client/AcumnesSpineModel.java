package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * アクムネスの棘(弾)のモデル。本体の針先の棘({@code stinger_tip} の箱)を UV ごと写したもの
 * ({@code tools/g5-models/mobs/Acumnes.java#spine} の生成物)。テクスチャは本体と同じ。アニメは持たない
 * (GeckoLib がアニメのファイルを求めるので本体のものを指す)。
 */
public class AcumnesSpineModel extends GeoModel<AcumnesSpine> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/acumnes_spine.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/acumnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/acumnes.animation.json");

    @Override
    public ResourceLocation getModelResource(AcumnesSpine animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AcumnesSpine animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(AcumnesSpine animatable) {
        return ANIMATION;
    }
}
