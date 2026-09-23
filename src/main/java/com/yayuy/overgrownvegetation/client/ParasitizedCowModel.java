package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedCow;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「寄生された牛」のモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/overgrown_cow.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_cow.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_cow.png}</li>
 * </ul>
 *
 * <p>Blockbench で作ったモデルを差し替えるときは、上の3ファイルを
 * Blockbench の GeckoLib 用エクスポート（Geo Model / Animation）で出力したもので
 * 上書きするだけでよい。ファイル名・パスを変えた場合のみ、このクラスの参照先を合わせて変更する。
 */
public class ParasitizedCowModel extends LodGeoModel<ParasitizedCow> {

    @Override
    public ResourceLocation getModelResource(ParasitizedCow animatable) {
        return OvergrownVegetation.id("geo/overgrown_cow.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedCow animatable) {
        return MobVariantTextures.of(animatable, "overgrown_cow"); // 亜種は接尾辞付きの png(2026-09-19)
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedCow animatable) {
        return OvergrownVegetation.id("animations/overgrown_cow.animation.json");
    }
}
