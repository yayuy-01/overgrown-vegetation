package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ペディキュラ」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\pedicula.bbmodel}(11-15 に作り直し案
 * {@code tools/g5-models/out/unadopted/remake/re_pedicula_r6} を写した)。描画倍率 1(1 単位 = 1 テクセル = ブロックの 1 画素)。
 */
public class PediculaModel extends GeoModel<PediculaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/pedicula.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/pedicula.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/pedicula.animation.json");

    @Override
    public ResourceLocation getModelResource(PediculaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PediculaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PediculaEntity animatable) {
        return ANIMATION;
    }
}
