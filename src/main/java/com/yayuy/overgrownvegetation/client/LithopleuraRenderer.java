package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 「リトプレウラ」の描画。殻の出し入れは {@link LithopleuraModel} が受け持つ。 */
public class LithopleuraRenderer extends GeoEntityRenderer<LithopleuraEntity> {

    public LithopleuraRenderer(EntityRendererProvider.Context context) {
        super(context, new LithopleuraModel());
        this.shadowRadius = 0.5F;
    }
}
