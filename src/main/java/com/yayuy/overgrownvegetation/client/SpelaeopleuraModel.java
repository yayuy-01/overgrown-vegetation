package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「スペラエオプレウラ」のモデル定義。
 *
 * <p>テクスチャは亜種分けしない(洞窟にしか居ないので、硬化・毒性・耐火の亜種を作る意味が薄い)。
 * 発光は {@code textures/entity/spelaeopleura_glowmask.png} を
 * {@link SpelaeopleuraGlowLayer} が重ねる。
 */
public class SpelaeopleuraModel extends LodGeoModel<SpelaeopleuraEntity> {

    @Override
    public ResourceLocation getModelResource(SpelaeopleuraEntity animatable) {
        return OvergrownVegetation.id("geo/spelaeopleura.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SpelaeopleuraEntity animatable) {
        return OvergrownVegetation.id("textures/entity/spelaeopleura.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SpelaeopleuraEntity animatable) {
        return OvergrownVegetation.id("animations/spelaeopleura.animation.json");
    }
}
