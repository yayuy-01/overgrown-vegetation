package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
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
import java.util.function.BiFunction;

/**
 * ピリムネスの光(2026-11-16 能力の段)。発光マスク 1 枚を光る描き方でもう一度重ねる。明るさは技の時刻から({@link PilimnesEntity#spineGlow}・
 * {@link PilimnesEntity#mouthGlow}。0 なら描かない)。作りはクリペイムネス・サルティムネスの光の層と同じ。絵が MOD に無いときは描かない。
 * 層は 2 つ: 棘の列({@code pilimnes_glow_spine.png}。弾き・扇・雨)と T 字の口({@code pilimnes_glow_mouth.png}。噛みつき)。
 */
public class PilimnesGlowLayer extends GeoRenderLayer<PilimnesEntity> {

    static final ResourceLocation SPINE = OvergrownVegetation.id("textures/entity/pilimnes_glow_spine.png");
    static final ResourceLocation MOUTH = OvergrownVegetation.id("textures/entity/pilimnes_glow_mouth.png");

    private final ResourceLocation mask;
    private final BiFunction<PilimnesEntity, Float, Float> brightness;
    private Boolean hasMask;

    public PilimnesGlowLayer(GeoRenderer<PilimnesEntity> renderer, ResourceLocation mask, BiFunction<PilimnesEntity, Float, Float> brightness) {
        super(renderer);
        this.mask = mask;
        this.brightness = brightness;
    }

    @Override
    public void render(PoseStack poseStack, PilimnesEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = this.brightness.apply(animatable, partialTick);
        if (glow <= 0.01F) return;
        if (this.hasMask == null) {
            this.hasMask = Minecraft.getInstance().getResourceManager().getResource(this.mask).isPresent();
            if (!this.hasMask) OvergrownVegetation.LOGGER.info("ピリムネスの発光マスクが無いので光らせない: {}", this.mask);
        }
        if (!this.hasMask) return;
        RenderType rt = RenderType.entityTranslucentEmissive(this.mask);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
