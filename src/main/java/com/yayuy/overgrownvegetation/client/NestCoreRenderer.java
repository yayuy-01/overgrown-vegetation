package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.NestCoreEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** 巣の核。ハイヴノードより小さい(大きさは {@code mobs.json} の {@code scale})。 */
public class NestCoreRenderer extends GeoEntityRenderer<NestCoreEntity> {

    public NestCoreRenderer(EntityRendererProvider.Context context) {
        super(context, new NestCoreModel());
        this.shadowRadius = 0.0F;
    }
}
