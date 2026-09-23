package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ファランギオターブス」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/phalangiotarbus.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/phalangiotarbus.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/phalangiotarbus.png}</li>
 * </ul>
 */
public class PhalangiotarbusModel extends LodGeoModel<PhalangiotarbusEntity> {

    @Override
    public ResourceLocation getModelResource(PhalangiotarbusEntity animatable) {
        return OvergrownVegetation.id("geo/phalangiotarbus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PhalangiotarbusEntity animatable) {
        return MobVariantTextures.of(animatable, "phalangiotarbus"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(PhalangiotarbusEntity animatable) {
        return OvergrownVegetation.id("animations/phalangiotarbus.animation.json");
    }
}
