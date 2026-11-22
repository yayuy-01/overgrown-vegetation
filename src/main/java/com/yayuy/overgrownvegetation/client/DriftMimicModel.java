package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.drift.DriftMimicEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 流れ木もどきのモデル(形 B 改 6、11-22 ユーザー決定)。正本は Blockbench の {@code デスクトップ\blockbench\mobs\drift_mimic\drift_mimic.bbmodel}
 * (モデル制作)。描画倍率 1(1 単位 = 1 テクセル = ブロックの 1 画素)。水面 = 形の y 0。
 */
public class DriftMimicModel extends GeoModel<DriftMimicEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/drift_mimic.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/drift_mimic.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/drift_mimic.animation.json");

    @Override
    public ResourceLocation getModelResource(DriftMimicEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(DriftMimicEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(DriftMimicEntity animatable) {
        return ANIMATION;
    }
}
