package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedCreeper;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂したクリーパー」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル
 * ({@link ParasitizedZombieModel} と全く同じ構成)。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_creeper.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_creeper.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_creeper.png}</li>
 * </ul>
 *
 * <p>モデル形状・テクスチャはユーザーが Blockbench で作った実データをそのまま使用
 * (クリーパー骨格 {@code Body}/{@code Head}/{@code leg0}〜{@code leg3} に、体から生えたシダ束
 * {@code fern1}〜{@code fern6}(頭に3・胴に3)を接ぎ木したもの)。アニメーション(idle/walk)は
 * バニラのクリーパーに準じた「4本脚を対角でトロット」させるモーションとして Claude が作成、
 * 体から生えたシダには待機中・歩行中とも風で揺れているような緩やかなスウェイを付けている。
 * Blockbench でモデルを作り直すときは上の3ファイルを上書きするだけでよい。
 */
public class ParasitizedCreeperModel extends LodGeoModel<ParasitizedCreeper> {

    @Override
    public ResourceLocation getModelResource(ParasitizedCreeper animatable) {
        return OvergrownVegetation.id("geo/overgrown_creeper.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedCreeper animatable) {
        return MobVariantTextures.of(animatable, "overgrown_creeper"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedCreeper animatable) {
        return OvergrownVegetation.id("animations/overgrown_creeper.animation.json");
    }
}
