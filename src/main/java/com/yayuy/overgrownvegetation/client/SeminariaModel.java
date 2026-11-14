package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「セミナリア」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\seminaria.bbmodel}(11-15 に練習モデル フリマキ r7
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_drifter_r7} を写した)。体の傾き・機首の上下は描画({@link SeminariaRenderer})で全体を回す。
 */
public class SeminariaModel extends GeoModel<SeminariaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/seminaria.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/seminaria.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/seminaria.animation.json");

    @Override
    public ResourceLocation getModelResource(SeminariaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SeminariaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SeminariaEntity animatable) {
        return ANIMATION;
    }
}
