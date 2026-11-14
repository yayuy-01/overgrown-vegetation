package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ピリムネス」の描画。描画倍率 2(geo に焼き込まない。テクスチャは 1 単位 2 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスク(棘の付け根の脈・口)は段 1 では重ねない(構えで光る決まり。構えは能力の段)。
 */
public class PilimnesRenderer extends GeoEntityRenderer<PilimnesEntity> {

    public PilimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new PilimnesModel());
        this.withScale(PilimnesEntity.RENDER_SCALE);
        this.shadowRadius = 1.2F;
    }

    @Override
    protected float getDeathMaxRotation(PilimnesEntity animatable) {
        return 0.0F;
    }
}
