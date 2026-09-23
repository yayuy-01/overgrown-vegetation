package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class OvergrownShielderRenderer extends GeoEntityRenderer<OvergrownShielderEntity> {

    public OvergrownShielderRenderer(EntityRendererProvider.Context context) {
        super(context, new OvergrownShielderModel());
        this.shadowRadius = 1.8F;
    }
}
