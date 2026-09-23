package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class StenodictyaLobataRenderer extends GeoEntityRenderer<StenodictyaLobataEntity> {

    public StenodictyaLobataRenderer(EntityRendererProvider.Context context) {
        super(context, new StenodictyaLobataModel());
        this.shadowRadius = 0.3F;
    }
}
