package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedPig;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂した豚」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル
 * ({@link ParasitizedCowModel} と全く同じ構成)。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_pig.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_pig.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_pig.png}</li>
 * </ul>
 *
 * <p>ユーザーが Blockbench で作った実モデル+実テクスチャをそのまま使用。アニメーション
 * (idle/walk)はバニラの豚に準じた歩行/待機モーションとして Claude が作成したもので、
 * 体から生えた {@code fern1}〜{@code fern6} ボーンにも揺れを付けている。
 * Blockbench でモデルを作り直すときは、上の3ファイルをエクスポートしたもので
 * 上書きするだけでよい。ファイル名・パスを変えた場合のみ、このクラスの参照先を合わせて変更する。
 */
public class ParasitizedPigModel extends LodGeoModel<ParasitizedPig> {

    @Override
    public ResourceLocation getModelResource(ParasitizedPig animatable) {
        return OvergrownVegetation.id("geo/overgrown_pig.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedPig animatable) {
        return MobVariantTextures.of(animatable, "overgrown_pig"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedPig animatable) {
        return OvergrownVegetation.id("animations/overgrown_pig.animation.json");
    }
}
