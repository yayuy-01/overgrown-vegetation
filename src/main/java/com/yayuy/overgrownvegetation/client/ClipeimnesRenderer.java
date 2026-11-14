package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「クリペイムネス」の描画。描画倍率 2.0(geo に焼き込まない。テクスチャは 1 単位 2 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスク(盾鱗の隙間の葉脈・口)は段 1 では重ねない(構えで光る決まり。構えは次の段)。
 */
public class ClipeimnesRenderer extends GeoEntityRenderer<ClipeimnesEntity> {

    public ClipeimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new ClipeimnesModel());
        this.withScale(ClipeimnesEntity.RENDER_SCALE);
        this.shadowRadius = 1.2F;
    }

    @Override
    protected float getDeathMaxRotation(ClipeimnesEntity animatable) {
        return 0.0F;
    }
}
