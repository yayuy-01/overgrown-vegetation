package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ArboraQueenEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 女王はアルボラの 2 倍(ユーザー指定 2026-09-19)。大きさは {@code mobs.json} の {@code scale}。 */
public class ArboraQueenRenderer extends GeoEntityRenderer<ArboraQueenEntity> {

    public ArboraQueenRenderer(EntityRendererProvider.Context context) {
        super(context, new ArboraQueenModel());
        this.shadowRadius = 1.2F;
    }
}
