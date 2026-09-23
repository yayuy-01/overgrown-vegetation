package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.PlantArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「寄生植物装備」のモデル定義。段階(1〜3)ごとに geo / テクスチャ / アニメーションを切り替える。
 *
 * <p>{@link GeoModel} にはアイテム({@link PlantArmorItem})しか渡ってこないので、描画側
 * ({@link PlantArmorRenderer} / {@link PlantArmorItemRenderer})が描く直前に {@link #setStage} で
 * その ItemStack の段階を教える。段階 1 はユーザー製の {@code plant_armor.*}、段階 2・3 は
 * {@code tools/plant-armor/run.ps1} が段階 1 から生成した {@code plant_armor_2.*} / {@code plant_armor_3.*}。
 *
 * <p>アニメーション名は段階ごとに {@code idle} / {@code idle2} / {@code idle3}。同じ名前だと GeckoLib の
 * コントローラが段階 1 の焼き上がりを持ち続け、昇格後もシダが揺れないため。
 */
public class PlantArmorModel extends GeoModel<PlantArmorItem> {

    private int stage = 1;

    /** 描く直前に、描こうとしている ItemStack の段階(1〜3)を教える。 */
    public void setStage(int stage) {
        this.stage = Math.max(1, Math.min(3, stage));
    }

    public int stage() {
        return this.stage;
    }

    private String suffix() {
        return this.stage == 1 ? "" : "_" + this.stage;
    }

    @Override
    public ResourceLocation getModelResource(PlantArmorItem animatable) {
        return OvergrownVegetation.id("geo/armor/plant_armor" + suffix() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PlantArmorItem animatable) {
        return OvergrownVegetation.id("textures/armor/plant_armor" + suffix() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(PlantArmorItem animatable) {
        return OvergrownVegetation.id("animations/armor/plant_armor" + suffix() + ".animation.json");
    }
}
