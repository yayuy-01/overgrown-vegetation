package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedSpider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂したクモ」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル
 * ({@link ParasitizedCreeperModel} と全く同じ構成)。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_spider.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_spider.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_spider.png}(128x128)</li>
 * </ul>
 *
 * <p>モデル形状・テクスチャはユーザーが Blockbench で作った実データをそのまま使用
 * (クモ骨格 {@code head}/{@code body0}/{@code body1}/{@code leg0}〜{@code leg7} に、頭付近から生えた
 * シダ束 {@code fern1}〜{@code fern3}(親ボーン {@code "fern grope"})を接ぎ木したもの)。アニメーション
 * (idle/walk)はバニラのクモに準じた「8本脚を4位相の波で掻くように歩く」モーションとして Claude が作成、
 * 体から生えたシダには待機中・歩行中とも風で揺れているような緩やかなスウェイを付けている。
 * Blockbench でモデルを作り直すときは上の3ファイルを上書きするだけでよい。
 */
public class ParasitizedSpiderModel extends LodGeoModel<ParasitizedSpider> {

    @Override
    public ResourceLocation getModelResource(ParasitizedSpider animatable) {
        return OvergrownVegetation.id("geo/overgrown_spider.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedSpider animatable) {
        return MobVariantTextures.of(animatable, "overgrown_spider"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedSpider animatable) {
        return OvergrownVegetation.id("animations/overgrown_spider.animation.json");
    }
}
