package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VesicariaEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「ヴェシカリア」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\vesicaria.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_burster_r7} を写した。一覧の絵で選ばれた r7_kamae は同じ形の構えの姿)。
 * 脚は手付けの walk だけ(IK なし)。体の傾き・段のならしは描画({@link com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer})。
 * 発光マスク(構えの脈)は段 2 で入れる。
 */
public class VesicariaModel extends LodGeoModel<VesicariaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/vesicaria.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/vesicaria.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/vesicaria.animation.json");

    @Override
    public ResourceLocation getModelResource(VesicariaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(VesicariaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(VesicariaEntity animatable) {
        return ANIMATION;
    }
}
