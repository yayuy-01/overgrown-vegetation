package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedZombie;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繁茂したゾンビ」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル
 * ({@link ParasitizedSheepModel} と全く同じ構成)。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_zombie.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_zombie.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_zombie.png}</li>
 * </ul>
 *
 * <p>モデル形状・テクスチャはユーザーが Blockbench で作った実データをそのまま使用
 * (人型骨格 {@code Body}/{@code Head}/{@code RightArm}/{@code LeftArm}/{@code RightLeg}/{@code LeftLeg} に、
 * 体から生えたシダ束 {@code fern1}〜{@code fern6} を接ぎ木したもの)。アニメーション
 * (idle/walk)はバニラのゾンビに準じた「両腕を前に突き出したまま脚を振って歩く」モーションとして
 * Claude が作成、体から生えたシダには待機中・歩行中とも風で揺れているような緩やかなスウェイを
 * 付けている。Blockbench でモデルを作り直すときは上の3ファイルを上書きするだけでよい。
 */
public class ParasitizedZombieModel extends LodGeoModel<ParasitizedZombie> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/overgrown_zombie.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/overgrown_zombie.animation.json");

    @Override
    public ResourceLocation getModelResource(ParasitizedZombie animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedZombie animatable) {
        return MobVariantTextures.of(animatable, "overgrown_zombie"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedZombie animatable) {
        return ANIMATION;
    }
}
