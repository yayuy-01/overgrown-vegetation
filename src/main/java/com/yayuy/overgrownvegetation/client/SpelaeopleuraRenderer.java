package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「スペラエオプレウラ」の描画。背の筋を光らせる {@link SpelaeopleuraGlowLayer} を重ねる。
 */
public class SpelaeopleuraRenderer extends GeoEntityRenderer<SpelaeopleuraEntity> {

    public SpelaeopleuraRenderer(EntityRendererProvider.Context context) {
        super(context, new SpelaeopleuraModel());
        this.shadowRadius = 0.5F;
        addRenderLayer(new SpelaeopleuraGlowLayer(this));
    }
}
