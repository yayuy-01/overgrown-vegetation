package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class IanthasaurusRenderer extends GeoEntityRenderer<IanthasaurusEntity> {

    public IanthasaurusRenderer(EntityRendererProvider.Context context) {
        super(context, new IanthasaurusModel());
        this.shadowRadius = 0.45F;
    }
}
