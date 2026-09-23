package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「防護マスク」のモデル定義。頭装備 1 部位だけなので、中身が入っているボーンは {@code armorHead} のみ。
 *
 * <p>アニメーションはしないが、{@code GeoModel} が参照先を要求するので空の animation.json を置いてある。
 */
public class ProtectiveMaskModel extends GeoModel<ProtectiveMaskItem> {

    @Override
    public ResourceLocation getModelResource(ProtectiveMaskItem animatable) {
        return OvergrownVegetation.id("geo/armor/protective_mask.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ProtectiveMaskItem animatable) {
        return OvergrownVegetation.id("textures/armor/protective_mask.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ProtectiveMaskItem animatable) {
        return OvergrownVegetation.id("animations/armor/protective_mask.animation.json");
    }
}
