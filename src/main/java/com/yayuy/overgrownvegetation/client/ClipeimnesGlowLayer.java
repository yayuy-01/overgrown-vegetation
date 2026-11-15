package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
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
 * クリペイムネスの盾鱗の隙間の葉脈と T 字の口の光(2026-11-15 段 3。決定: 光るのは抱え込みと溜めの解放だけ。溜めの段は弱く灯る)。
 * 発光マスク {@code clipeimnes_glowmask.png}(練習モデル r7 の写し。透明の上に光る画素 229)を光る描き方でもう一度重ねる。明るさは
 * {@link ClipeimnesEntity#glow}(0 なら描かない)。作りはサルティムネス・ヴェナムネスの光の層と同じ。絵が MOD に無いときは描かない。
 */
public class ClipeimnesGlowLayer extends GeoRenderLayer<ClipeimnesEntity> {

    static final ResourceLocation MASK = OvergrownVegetation.id("textures/entity/clipeimnes_glowmask.png");

    private Boolean hasMask;

    public ClipeimnesGlowLayer(GeoRenderer<ClipeimnesEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, ClipeimnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.glow(partialTick);
        if (glow <= 0.01F) return;
        if (this.hasMask == null) {
            this.hasMask = Minecraft.getInstance().getResourceManager().getResource(MASK).isPresent();
            if (!this.hasMask) OvergrownVegetation.LOGGER.info("クリペイムネスの発光マスクがまだ無いので光らせない: {}", MASK);
        }
        if (!this.hasMask) return;
        RenderType rt = RenderType.entityTranslucentEmissive(MASK);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
