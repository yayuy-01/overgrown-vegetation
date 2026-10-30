package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ScaturiumExoskeletonArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「スカトゥリウムの外骨格」防具のモデル定義。4部位(兜・胸当て・レギンス・ブーツ)で
 * 同じ geo/テクスチャを共有し、部位ごとの出し分けは GeckoLib の
 * {@code GeoArmorRenderer} がボーン名({@code armorHead} など)で行う。
 *
 * <p>アニメーションはしないが、{@code GeoModel} が参照先を要求するので空の
 * animation.json を置いてある。
 */
public class ScaturiumExoskeletonArmorModel extends GeoModel<ScaturiumExoskeletonArmorItem> {

    // 2026-09-24 軽量化(監査 1318): 描画のたびに ResourceLocation を作り直さないよう定数にする(中身は同じ)。
    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/armor/scaturium_exoskeleton.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/armor/scaturium_exoskeleton.png");
    private static final ResourceLocation ANIMATION =
            OvergrownVegetation.id("animations/armor/scaturium_exoskeleton.animation.json");

    @Override
    public ResourceLocation getModelResource(ScaturiumExoskeletonArmorItem animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ScaturiumExoskeletonArmorItem animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ScaturiumExoskeletonArmorItem animatable) {
        return ANIMATION;
    }
}
