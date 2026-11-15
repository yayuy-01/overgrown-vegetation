package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import net.minecraft.client.Minecraft;
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
 * サルティムネスの脚の葉脈と T 字の口の光(2026-11-15 段 3。決定: 光るのは跳び抱えと連続跳躍だけ)。発光マスク {@code saltimnes_glowmask.png}
 * (練習モデル r6 の写し。透明の上に光る画素だけ)を光る描き方でもう一度重ねる。明るさは {@link SaltimnesEntity#glow}(0 なら描かない)。
 * GeckoLib の {@code AutoGlowingGeoLayer} は常に光るので使わない(ヴェナムネスと同じ作り)。絵が MOD に無いときは描かない。
 */
public class SaltimnesGlowLayer extends GeoRenderLayer<SaltimnesEntity> {

    static final ResourceLocation MASK = OvergrownVegetation.id("textures/entity/saltimnes_glowmask.png");

    private Boolean hasMask;

    public SaltimnesGlowLayer(GeoRenderer<SaltimnesEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, SaltimnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.glow(partialTick);
        if (glow <= 0.01F) return;
        if (this.hasMask == null) {
            this.hasMask = Minecraft.getInstance().getResourceManager().getResource(MASK).isPresent();
            if (!this.hasMask) OvergrownVegetation.LOGGER.info("サルティムネスの発光マスクがまだ無いので光らせない: {}", MASK);
        }
        if (!this.hasMask) return;
        RenderType rt = RenderType.entityTranslucentEmissive(MASK);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
