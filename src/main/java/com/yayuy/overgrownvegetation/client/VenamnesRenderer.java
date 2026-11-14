package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ヴェナムネス」の描画。描画倍率 2.5(geo に焼き込まない。テクスチャは 1 単位 2.5 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスク(腿の腱・口)は段 1 では重ねない(構えで光る決まり。構えは次の段)。
 */
public class VenamnesRenderer extends GeoEntityRenderer<VenamnesEntity> {

    public VenamnesRenderer(EntityRendererProvider.Context context) {
        super(context, new VenamnesModel());
        this.withScale(VenamnesEntity.RENDER_SCALE);
        this.shadowRadius = 1.0F;
    }

    @Override
    protected float getDeathMaxRotation(VenamnesEntity animatable) {
        return 0.0F;
    }
}
