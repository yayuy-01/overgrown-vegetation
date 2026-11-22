package com.yayuy.overgrownvegetation.client.vent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * 地脈の換気機の模型(2026-11-22)。置いた機械とアイテムで同じ geo・絵・アニメを使う。正本は Blockbench の
 * {@code デスクトップ\blockbench\zyokyosouti_kai_B_yayuy.bbmodel}(書き出して名前を ley_ventilator にそろえた)。
 */
public final class VentModel<T extends GeoAnimatable> extends GeoModel<T> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/ley_ventilator.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/ley_ventilator.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/ley_ventilator.animation.json");

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
