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

    @Override
    public ResourceLocation getModelResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return OvergrownVegetation.id("geo/armor/reinforced_chlorophyllite_alloy.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return OvergrownVegetation.id("textures/armor/reinforced_chlorophyllite_alloy.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ReinforcedChlorophylliteAlloyArmorItem animatable) {
        return OvergrownVegetation.id("animations/armor/reinforced_chlorophyllite_alloy.animation.json");
    }
}
