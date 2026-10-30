package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedVillager;
import net.minecraft.resources.ResourceLocation;

/**
 * 「寄生された村人」のモデル(2026-10-09)。geo・アニメは生成器 {@code tools/g5-models/mobs/G1Villager.java} の生成物、
 * 絵はユーザーがシダを塗り直したもの({@code tools/g5-models/refs/villager/infected_villager_user.png})。
 * アニメ: idle・walk・rise(死体から起き上がる)。
 */
public class ParasitizedVillagerModel extends LodGeoModel<ParasitizedVillager> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/overgrown_villager.geo.json");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/overgrown_villager.animation.json");

    @Override
    public ResourceLocation getModelResource(ParasitizedVillager animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ParasitizedVillager animatable) {
        return MobVariantTextures.of(animatable, "overgrown_villager");
    }

    @Override
    public ResourceLocation getAnimationResource(ParasitizedVillager animatable) {
        return ANIMATION;
    }
}
