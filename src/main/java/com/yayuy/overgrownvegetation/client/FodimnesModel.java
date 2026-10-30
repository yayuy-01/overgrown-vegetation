package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.FodimnesEntity;
import net.minecraft.resources.ResourceLocation;

/**
 * 「フォディムネス」の<b>頭</b>のモデル定義({@code fodimnes_head})。節({@code fodimnes_segment})と尾({@code fodimnes_tail})は
 * 別のモデルで、実装係がレンダラーに重ねて描く(部位の約束は {@code tools/g5-models/out/fodimnes/NOTES.md})。
 * 組み上げの {@code fodimnes} は見本なので MOD には入れない。
 *
 * <p>2026-10-01 足場。中身は実装係。
 */
public class FodimnesModel extends LodGeoModel<FodimnesEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/fodimnes_head.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/fodimnes_head.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/fodimnes_head.animation.json");

    @Override
    public ResourceLocation getModelResource(FodimnesEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(FodimnesEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(FodimnesEntity animatable) {
        return ANIMATION;
    }
}
