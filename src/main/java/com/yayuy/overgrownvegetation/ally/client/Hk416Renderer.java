package com.yayuy.overgrownvegetation.ally.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.Hk416Item;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * HK416 を geo のモデルで描く(2026-10-01)。向き・大きさは {@code models/item/hk416.json} の display(再コンパイル不要、F3+T で読み直し)。
 */
public class Hk416Renderer extends GeoItemRenderer<Hk416Item> {

    /** テクスチャ(兵士の手の銃の描画の種類 {@code entityCutoutNoCull} の鍵にもなる。{@link SoldierRenderer#registerBuffers})。 */
    static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/item/hk416.png");

    public Hk416Renderer() {
        super(new Model());
        useAlternateGuiLighting();
    }

    private static final class Model extends GeoModel<Hk416Item> {
        private static final ResourceLocation GEO = OvergrownVegetation.id("geo/hk416.geo.json");
        private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/hk416.animation.json");

        @Override
        public ResourceLocation getModelResource(Hk416Item animatable) {
            return GEO;
        }

        @Override
        public ResourceLocation getTextureResource(Hk416Item animatable) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(Hk416Item animatable) {
            return ANIMATION;
        }
    }
}
