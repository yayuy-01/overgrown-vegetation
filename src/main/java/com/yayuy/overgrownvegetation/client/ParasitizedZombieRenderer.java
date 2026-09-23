package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.ParasitizedZombie;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「過繁茂したゾンビ」のレンダラー。{@link ParasitizedCowRenderer} などと同じ GeckoLib レンダラー。
 * バニラゾンビの防具レイヤーや子ゾンビの縮小表示は引き継がない(見た目は専用モデル一枚)。
 */
public class ParasitizedZombieRenderer extends GeoEntityRenderer<ParasitizedZombie> {

    public ParasitizedZombieRenderer(EntityRendererProvider.Context context) {
        super(context, new ParasitizedZombieModel());
        this.shadowRadius = 0.5F;
    }
}
