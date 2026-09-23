package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.LithopleuraHuskItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 抜け殻アイテムを、ブロックと同じ立体モデルで手持ち/インベントリに描画する。
 *
 * <p>GUI での向き・大きさは {@code models/item/lithopleura_husk.json} の {@code display} で調整する
 * (再コンパイル不要、F3+T の再読み込みで反映)。
 */
public class LithopleuraHuskItemRenderer extends GeoItemRenderer<LithopleuraHuskItem> {

    public LithopleuraHuskItemRenderer() {
        super(new Model());
        useAlternateGuiLighting();
    }

    /** ブロック側({@link LithopleuraHuskModel})と同じ geo / テクスチャを指すだけ。 */
    private static final class Model extends GeoModel<LithopleuraHuskItem> {

        private static final ResourceLocation GEO = OvergrownVegetation.id("geo/lithopleura_husk.geo.json");
        private static final ResourceLocation TEXTURE =
                OvergrownVegetation.id("textures/entity/lithopleura.png");
        private static final ResourceLocation ANIMATION =
                OvergrownVegetation.id("animations/overgrown_corpse.animation.json");

        @Override
        public ResourceLocation getModelResource(LithopleuraHuskItem animatable) {
            return GEO;
        }

        @Override
        public ResourceLocation getTextureResource(LithopleuraHuskItem animatable) {
            return TEXTURE;
        }

        @Override
        public ResourceLocation getAnimationResource(LithopleuraHuskItem animatable) {
            return ANIMATION;
        }
    }
}
