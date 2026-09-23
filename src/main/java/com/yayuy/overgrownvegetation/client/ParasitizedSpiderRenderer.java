package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedSpider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「過繁茂したクモ」のレンダラー。{@link ParasitizedCreeperRenderer} などと同じ GeckoLib レンダラー。
 * バニラの {@code SpiderRenderer} が必要としていた 180° 反転({@code getFlipDegrees})は、
 * Blockbench 製モデルが正しい向き(-Z が前)で作られているため不要になった。
 * 目の発光レイヤー({@code SpiderEyesLayer})はバニラの {@code SpiderModel} 型に固定されていて
 * 使えないため省略(見た目上の些末な差異)。
 */
public class ParasitizedSpiderRenderer extends GeoEntityRenderer<ParasitizedSpider> {

    public ParasitizedSpiderRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedSpiderModel());
        this.shadowRadius = 0.8F;
    }
}
