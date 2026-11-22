package com.yayuy.overgrownvegetation.client.crawler;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.LodGeoModel;
import com.yayuy.overgrownvegetation.entity.GestatrixEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * ゲスタトリクスのモデル(2026-11-17)。素材はモデル制作(Fable)の本物(11-17 夜。正本は Blockbench の gestatrix.bbmodel、骨とアニメの名前は共有ノート「ゲスタトリクス_骨とアニメの名前」)。
 */
public class GestatrixModel extends LodGeoModel<GestatrixEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/gestatrix.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/gestatrix.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/gestatrix.animation.json");

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
