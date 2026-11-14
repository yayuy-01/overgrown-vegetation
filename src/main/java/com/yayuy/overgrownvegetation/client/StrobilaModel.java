package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StrobilaEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ストロビラ」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\strobila.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_lobber_r9} を写した。中の名前を strobila にしただけ)。
 * 脚は手付けの walk だけ(IK なし)。体の傾き・段のならしは描画({@link com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer})。
 */
public class StrobilaModel extends LodGeoModel<StrobilaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/strobila.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/strobila.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/strobila.animation.json");

    @Override
    public ResourceLocation getModelResource(StrobilaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(StrobilaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(StrobilaEntity animatable) {
        return ANIMATION;
    }
}
