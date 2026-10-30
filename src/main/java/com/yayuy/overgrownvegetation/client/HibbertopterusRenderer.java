package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.HibbertopterusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class HibbertopterusRenderer extends GeoEntityRenderer<HibbertopterusEntity> {

    public HibbertopterusRenderer(EntityRendererProvider.Context context) {
        super(context, new HibbertopterusModel());
        this.shadowRadius = 0.7F;
    }
}
