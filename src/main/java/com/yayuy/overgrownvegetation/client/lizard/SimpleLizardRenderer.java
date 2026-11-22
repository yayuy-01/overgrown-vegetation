package com.yayuy.overgrownvegetation.client.lizard;

import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 「トカゲ型」の小さな生き物の描画(2026-11-16)。描画倍率を掛けるだけ(geo に焼き込まない)。死んでも倒れない(長い体が横倒しにならない)。 */
public class SimpleLizardRenderer<T extends SimpleLizard> extends GeoEntityRenderer<T> {

    public SimpleLizardRenderer(EntityRendererProvider.Context context, SimpleLizardModel<T> model, float renderScale, float shadow) {
        super(context, model);
        this.withScale(renderScale);
        this.shadowRadius = shadow;
    }

    @Override
    protected float getDeathMaxRotation(T animatable) {
        return 0.0F;
    }
}
