package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StrobilaEntity;
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
 * ストロビラの尾の背の光の筋(2026-11-15。能力の決定「前触れ = 背板が開き、尾の背に光の筋 約 1 秒」)。同じ UV の発光マスク
 * (透明の上に光る画素だけ。アニメのセッションが描いた【仮の絵】)を、光る描き方でもう一度重ねる。強さは {@link StrobilaEntity#tailGlow}(0 なら描かない)。
 * GeckoLib の {@code AutoGlowingGeoLayer} は強さを掛けられないので使わない(ヴェナムネスの {@code VenamnesGlowLayer} と同じ作り)。
 * 絵が MOD に無いときは描かない(無い絵は紫と黒の格子になって全身に光る)。
 */
public class StrobilaGlowLayer extends GeoRenderLayer<StrobilaEntity> {

    static final ResourceLocation GLOW = OvergrownVegetation.id("textures/entity/strobila_glowmask.png");

    private Boolean hasGlow;

    public StrobilaGlowLayer(GeoRenderer<StrobilaEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, StrobilaEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.tailGlow(partialTick);
        if (glow <= 0.01F) return;
        if (this.hasGlow == null) {
            this.hasGlow = Minecraft.getInstance().getResourceManager().getResource(GLOW).isPresent();
            if (!this.hasGlow) OvergrownVegetation.LOGGER.info("ストロビラの発光マスクがまだ無いので光らせない: {}", GLOW);
        }
        if (!this.hasGlow) return;
        RenderType rt = RenderType.entityTranslucentEmissive(GLOW);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
