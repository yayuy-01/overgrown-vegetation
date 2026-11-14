package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「アクムネス」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\acumnes.bbmodel}(11-15 に作り直し案
 * {@code tools/g5-models/out/unadopted/remake/re_acumnes_r8} を写した)。描画倍率 1(全長 10.6・翅の幅 5.5・高さ 3.4 ブロック)。
 */
public class AcumnesModel extends GeoModel<AcumnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/acumnes.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/acumnes.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/acumnes.animation.json");

    @Override
    public ResourceLocation getModelResource(AcumnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AcumnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(AcumnesEntity animatable) {
        return ANIMATION;
    }
}
