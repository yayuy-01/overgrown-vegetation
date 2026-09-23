package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂したスカトゥリウム」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_shielder.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_shielder.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_shielder.png}</li>
 * </ul>
 */
public class OvergrownShielderModel extends LodGeoModel<OvergrownShielderEntity> {

    @Override
    public ResourceLocation getModelResource(OvergrownShielderEntity animatable) {
        return OvergrownVegetation.id("geo/overgrown_shielder.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(OvergrownShielderEntity animatable) {
        return MobVariantTextures.of(animatable, "overgrown_shielder"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(OvergrownShielderEntity animatable) {
        return OvergrownVegetation.id("animations/overgrown_shielder.animation.json");
    }
}
