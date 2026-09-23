package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class MegarachneRenderer extends GeoEntityRenderer<MegarachneEntity> {

    public MegarachneRenderer(EntityRendererProvider.Context context) {
        super(context, new MegarachneModel());
        this.shadowRadius = 0.7F;
    }
}
