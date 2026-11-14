package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「グレガリア」のモデル。正本は Blockbench の {@code デスクトップ\blockbench\mobs\gregaria.bbmodel}(11-15 に練習モデル
 * {@code tools/g5-models/out/unadopted/practice/prac2_g4_swarm_r8} を写した。ユーザーが一覧の絵で「このままでよい」とした形)。
 * 脚は手付けの walk だけ(IK なし)。体の傾き・段のならしは描画({@link com.yayuy.overgrownvegetation.client.crawler.SmallCrawlerRenderer})。
 */
public class GregariaModel extends LodGeoModel<GregariaEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/gregaria.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/gregaria.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/gregaria.animation.json");

    @Override
    public ResourceLocation getModelResource(GregariaEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(GregariaEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(GregariaEntity animatable) {
        return ANIMATION;
    }
}
