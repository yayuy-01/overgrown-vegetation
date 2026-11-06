package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 「ファルキムネス」の描画。 */
public class FalcimnesRenderer extends GeoEntityRenderer<FalcimnesEntity> {

    public FalcimnesRenderer(EntityRendererProvider.Context context) {
        super(context, new FalcimnesModel());
        this.shadowRadius = 1.7F; // 体格 1.8 倍(11-05 から geo に焼き込み)に合わせて
        // 鎌の光の筋(飛ぶ斬撃・地走りの光の帯の間だけ光る、11-05)。
        addRenderLayer(new FalcimnesVeinLayer(this));
    }

    /**
     * バニラの「倒れると横へ 90° 転がる」を切る(2026-09-24)。倒れる動きはアニメ {@code death} / {@code legs_death}
     * (膝が抜けて腹から崩れ落ち、頭が地面に着く)が持っている —— 両方掛かると 11 ブロックの体が崩れながら横倒しになって地面に埋まる。
     */
    @Override
    protected float getDeathMaxRotation(FalcimnesEntity animatable) {
        return 0.0F;
    }
}
