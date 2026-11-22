package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「ステノディクティア」(成虫)のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\stenodictya_lobata.bbmodel}
 * (10-10 製の形・骨・アニメのまま、11-16 に絵だけ 32×32 = 1/16 画素に塗り直した。geo は正本から書き出し直したもの)。
 * 体の傾き・機首の上下は描画({@link StenodictyaLobataRenderer})で全体を回す。
 */
public class StenodictyaLobataModel extends GeoModel<StenodictyaLobataEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/stenodictya_lobata.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/stenodictya_lobata.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/stenodictya_lobata.animation.json");

    @Override
    public ResourceLocation getModelResource(StenodictyaLobataEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(StenodictyaLobataEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(StenodictyaLobataEntity animatable) {
        return ANIMATION;
    }
}
