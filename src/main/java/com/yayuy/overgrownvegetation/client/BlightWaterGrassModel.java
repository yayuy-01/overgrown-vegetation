package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.BlightWaterGrassEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 汚染水草のモデル定義。汚染シダ({@link BlightFernModel})と同じ作り。
 * 仮モデルは大きな水草。Blockbench で作ったモデルへ差し替えるときは下記3ファイルを上書きするだけでよい。
 *
 * <ul>
 *     <li>モデル形状: {@code assets/overgrown_vegetation/geo/blight_water_grass.geo.json}</li>
 *     <li>アニメーション: {@code assets/overgrown_vegetation/animations/blight_water_grass.animation.json}</li>
 *     <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/blight_water_grass.png}</li>
 * </ul>
 */
public class BlightWaterGrassModel extends LodGeoModel<BlightWaterGrassEntity> {

    @Override
    public ResourceLocation getModelResource(BlightWaterGrassEntity animatable) {
        return OvergrownVegetation.id("geo/blight_water_grass.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BlightWaterGrassEntity animatable) {
        return OvergrownVegetation.id("textures/entity/blight_water_grass.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BlightWaterGrassEntity animatable) {
        return OvergrownVegetation.id("animations/blight_water_grass.animation.json");
    }
}
