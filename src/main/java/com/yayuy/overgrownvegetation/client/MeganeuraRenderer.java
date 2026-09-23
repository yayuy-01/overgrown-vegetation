package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class MeganeuraRenderer extends GeoEntityRenderer<MeganeuraEntity> {

    public MeganeuraRenderer(EntityRendererProvider.Context context) {
        super(context, new MeganeuraModel());
        this.shadowRadius = 0.5F;
        // 仕留めた獲物を口元に咥えさせる(咀嚼が終わると同時に消える)。
        addRenderLayer(new MeganeuraPreyLayer(this));
    }
}
