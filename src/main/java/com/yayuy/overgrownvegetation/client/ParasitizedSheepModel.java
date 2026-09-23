package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedSheep;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂した羊」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル
 * ({@link ParasitizedPigModel} と全く同じ構成)。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_sheep.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_sheep.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_sheep.png}</li>
 * </ul>
 *
 * <p>ユーザーが Blockbench で作った実モデル+実テクスチャをそのまま使用。アニメーション
 * (idle/walk)はバニラの羊に準じた歩行/待機モーションとして Claude が作成したもので、
 * 体から生えた {@code fern1}〜{@code fern3} ボーン(モデル内の "fern grope" グループにぶら下がる、
 * 体から生えたシダ)には待機中に風で揺れているような緩やかなスウェイを付けている。
 * Blockbench でモデルを作り直すときは、上の3ファイルをエクスポートしたもので
 * 上書きするだけでよい。ファイル名・パスを変えた場合のみ、このクラスの参照先を合わせて変更する。
 */
public class ParasitizedSheepModel extends LodGeoModel<ParasitizedSheep> {

    @Override
    public ResourceLocation getModelResource(ParasitizedSheep animatable) {
        return OvergrownVegetation.id("geo/overgrown_sheep.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedSheep animatable) {
        return MobVariantTextures.of(animatable, "overgrown_sheep"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedSheep animatable) {
        return OvergrownVegetation.id("animations/overgrown_sheep.animation.json");
    }
}
