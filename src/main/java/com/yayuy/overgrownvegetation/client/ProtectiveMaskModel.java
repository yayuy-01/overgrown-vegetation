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

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/armor/protective_mask.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/armor/protective_mask.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/armor/protective_mask.animation.json");

    @Override
    public ResourceLocation getModelResource(ProtectiveMaskItem animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ProtectiveMaskItem animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ProtectiveMaskItem animatable) {
        return ANIMATION;
    }
}
