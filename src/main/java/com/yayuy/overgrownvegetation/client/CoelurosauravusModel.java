package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「コエルロサウラヴス」のモデル定義。テクスチャはユーザー製(目と細部は手描き)。
 */
public class CoelurosauravusModel extends LodGeoModel<CoelurosauravusEntity> {

    @Override
    public ResourceLocation getModelResource(CoelurosauravusEntity animatable) {
        return OvergrownVegetation.id("geo/coelurosauravus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CoelurosauravusEntity animatable) {
        return OvergrownVegetation.id("textures/entity/coelurosauravus.png");
    }

    @Override
    public ResourceLocation getAnimationResource(CoelurosauravusEntity animatable) {
        return OvergrownVegetation.id("animations/coelurosauravus.animation.json");
    }
}
