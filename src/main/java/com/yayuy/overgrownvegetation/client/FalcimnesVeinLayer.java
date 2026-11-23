package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import javax.annotation.Nullable;

/**
 * 鎌の光の筋(2026-11-05、ユーザー決定「行動のときだけ光る、血管・葉脈のよう、黄緑」)。同じ UV の別の絵 {@code falcimnes_veins.png}
 * (透明の上に筋だけ)を、光る描き方でもう一度重ねる。明るさは {@link FalcimnesEntity#veinGlow} —— 飛ぶ斬撃と地走りの光の帯の
 * 前触れで灯り始め、実行で一番明るく、隙で消える(ほかの技では光らない → 光ったら「光の技が来る」と読める)。
 * GeckoLib の {@code AutoGlowingGeoLayer} は常に光るので使わない(明るさを動かせない)。
 */
public class FalcimnesVeinLayer extends GeoRenderLayer<FalcimnesEntity> {

    static final ResourceLocation VEINS = OvergrownVegetation.id("textures/entity/falcimnes_veins.png");

    public FalcimnesVeinLayer(GeoRenderer<FalcimnesEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, FalcimnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.veinGlow(partialTick);
        if (glow <= 0.01F) return;
        RenderType rt = RenderType.entityTranslucentEmissive(VEINS);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, a / 255.0F);
    }
}
