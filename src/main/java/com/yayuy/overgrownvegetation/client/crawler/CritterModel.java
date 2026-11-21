package com.yayuy.overgrownvegetation.client.crawler;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.LodGeoModel;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.resources.ResourceLocation;

/**
 * 「小さな地這いの虫」の型で歩く環境生物のモデル(2026-11-16)。geo・絵・アニメを種の id から引くだけ(亜種の絵は用意しない = ユーザー決定)。
 * 脚は手付けの walk・run だけ(IK なし)。体の傾き・段のならしは描画({@link SmallCrawlerRenderer})。
 */
public class CritterModel<T extends CrawlerCritter> extends LodGeoModel<T> {

    private final ResourceLocation model, texture, animation;

    public CritterModel(String id) {
        this.model = OvergrownVegetation.id("geo/" + id + ".geo.json");
        this.texture = OvergrownVegetation.id("textures/entity/" + id + ".png");
        this.animation = OvergrownVegetation.id("animations/" + id + ".animation.json");
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return this.model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return this.texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return this.animation;
    }
}
