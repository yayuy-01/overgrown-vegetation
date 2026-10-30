package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.BathymnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * 「バティムネス」の描画。目と殻の背の胞子嚢の光({@code bathymnes_glowmask.png})を重ねる。
 * 描画倍率 2 は mobs.json の {@code scale}(SCALE 属性)で掛かる。
 *
 * <p>2026-10-01 足場。中身は実装係(視錐台カリングを広げるのは実体の {@code getBoundingBoxForCulling})。
 */
public class BathymnesRenderer extends GeoEntityRenderer<BathymnesEntity> {

    public BathymnesRenderer(EntityRendererProvider.Context context) {
        super(context, new BathymnesModel());
        this.shadowRadius = 1.2F;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    /** 死の動き(death / death_beached)はアニメが持っているので、バニラの横転を切る(ほかの G5 と同じ)。 */
    @Override
    protected float getDeathMaxRotation(BathymnesEntity animatable) {
        return 0.0F;
    }
}
