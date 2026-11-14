package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ヴェロフィル」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\velophyll.bbmodel}(11-15 に作り直し案
 * {@code tools/g5-models/out/unadopted/remake/re_velophyll_r5} を写した。ユーザーが一覧で了承した形)。
 * 体の傾き・機首の上下は骨ではなく描画({@link VelophyllRenderer})で全体を回す。
 */
public class VelophyllModel extends GeoModel<VelophyllEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/velophyll.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/velophyll.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/velophyll.animation.json");

    @Override
    public ResourceLocation getModelResource(VelophyllEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(VelophyllEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(VelophyllEntity animatable) {
        return ANIMATION;
    }
}
