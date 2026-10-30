package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ReinforcedChlorophylliteAlloyArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「強化クロロフィライト合金装備」のモデル定義。4 部位で同じ geo/テクスチャを共有し、
 * 部位ごとの出し分けは GeckoLib の {@code GeoArmorRenderer}(着用時)と
 * {@link GeoArmorIconRenderer}(アイコン)がボーン名({@code armorHead} など)で行う。
 *
 * <p>アニメーションはしないが、{@code GeoModel} が参照先を要求するので空の animation.json を置いてある。
 */
public class ReinforcedChlorophylliteAlloyArmorModel extends GeoModel<ReinforcedChlorophylliteAlloyArmorItem> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL =
            OvergrownVegetation.id("geo/armor/reinforced_chlorophyllite_alloy.geo.json");
    private static final ResourceLocation TEXTURE =
            OvergrownVegetation.id("textures/armor/reinforced_chlorophyllite_alloy.png");
    private static final ResourceLocation ANIMATION =
            OvergrownVegetation.id("animations/armor/reinforced_chlorophyllite_alloy.animation.json");

    @Override
    public ResourceLocation getModelResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return ANIMATION;
    }
}
