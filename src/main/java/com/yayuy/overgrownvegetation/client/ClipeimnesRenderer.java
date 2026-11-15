package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「クリペイムネス」の描画。描画倍率 2.0(geo に焼き込まない。テクスチャは 1 単位 2 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスク(盾鱗の隙間の葉脈・口)は {@link ClipeimnesGlowLayer} で、抱え込み・溜めの解放と溜めの段のときだけ重ねる(段 3、11-15)。
 */
public class ClipeimnesRenderer extends GeoEntityRenderer<ClipeimnesEntity> {

    public ClipeimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new ClipeimnesModel());
        this.withScale(ClipeimnesEntity.RENDER_SCALE);
        this.addRenderLayer(new ClipeimnesGlowLayer(this));
        this.shadowRadius = 1.2F;
    }

    @Override
    protected float getDeathMaxRotation(ClipeimnesEntity animatable) {
        return 0.0F;
    }
}
