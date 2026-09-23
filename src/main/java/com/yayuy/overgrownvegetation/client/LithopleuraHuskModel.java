package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LithopleuraHuskBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 抜け殻ブロックのモデル定義。
 *
 * <p>geo はリトプレウラのモデルから {@code kara} フォルダだけを抜き出したもの、
 * <b>テクスチャは本体と共用</b>。脱ぐ前と脱いだ後が同じ絵になる。
 *
 * <p>動かないが、GeckoLib はアニメーションリソースの存在を前提にしているので、
 * 死体ブロックと同じ<b>中身が空のアニメーション</b>を指す。
 */
public class LithopleuraHuskModel extends GeoModel<LithopleuraHuskBlockEntity> {

    private static final ResourceLocation GEO = OvergrownVegetation.id("geo/lithopleura_husk.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/lithopleura.png");
    private static final ResourceLocation ANIMATION =
            OvergrownVegetation.id("animations/overgrown_corpse.animation.json");

    @Override
    public ResourceLocation getModelResource(LithopleuraHuskBlockEntity animatable) {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(LithopleuraHuskBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(LithopleuraHuskBlockEntity animatable) {
        return ANIMATION;
    }
}
