package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 巨大シダのモデル定義。実体は GeckoLib が読み込む3つのリソースファイル。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/blight_fern.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/blight_fern.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/blight_fern.png}</li>
 * </ul>
 *
 * <p>Blockbench で作ったモデルに差し替えるときは、上の3ファイルを
 * Blockbench の GeckoLib 用エクスポート（Geo Model / Animation）で出力したもので
 * 上書きするだけでよい。ファイル名・パスを変えた場合のみ、このクラスの参照先を合わせて変更する。
 */
public class BlightFernModel extends LodGeoModel<BlightFernEntity> {

    @Override
    public ResourceLocation getModelResource(BlightFernEntity animatable) {
        return OvergrownVegetation.id("geo/blight_fern.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BlightFernEntity animatable) {
        return OvergrownVegetation.id("textures/entity/blight_fern.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BlightFernEntity animatable) {
        return OvergrownVegetation.id("animations/blight_fern.animation.json");
    }
}
