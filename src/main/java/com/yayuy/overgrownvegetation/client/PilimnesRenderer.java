package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ピリムネス」の描画。描画倍率 2(geo に焼き込まない。テクスチャは 1 単位 2 テクセルなので画素はブロックの 1 画素に揃う)。
 * 発光マスクは技の時刻だけ重ねる({@link PilimnesGlowLayer}。棘の列と T 字の口。脇腹の帯 pilimnes_glow_flank.png は使わない)。
 */
public class PilimnesRenderer extends GeoEntityRenderer<PilimnesEntity> {

    public PilimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new PilimnesModel());
        this.withScale(PilimnesEntity.RENDER_SCALE);
        this.shadowRadius = 1.2F;
        // 光(能力の段 11-16): 棘の列と T 字の口。技の時刻だけ灯る
        this.addRenderLayer(new PilimnesGlowLayer(this, PilimnesGlowLayer.SPINE, PilimnesEntity::spineGlow));
        this.addRenderLayer(new PilimnesGlowLayer(this, PilimnesGlowLayer.MOUTH, PilimnesEntity::mouthGlow));
    }

    @Override
    protected float getDeathMaxRotation(PilimnesEntity animatable) {
        return 0.0F;
    }
}
