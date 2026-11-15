package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「サルティムネス」の描画。描画倍率 2.5(geo に焼き込まない。テクスチャは 1 単位 2.5 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスク(腿の葉脈・T 字の口)は跳び抱えと連続跳躍の間だけ重ねる({@link SaltimnesGlowLayer}、11-15 段 3)。
 */
public class SaltimnesRenderer extends GeoEntityRenderer<SaltimnesEntity> {

    public SaltimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new SaltimnesModel());
        this.withScale(SaltimnesEntity.RENDER_SCALE);
        this.shadowRadius = 1.4F;
        this.addRenderLayer(new SaltimnesGlowLayer(this));
    }

    @Override
    protected float getDeathMaxRotation(SaltimnesEntity animatable) {
        return 0.0F;
    }
}
