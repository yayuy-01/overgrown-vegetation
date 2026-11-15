package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
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
 * セミナリアの種を落とす間だけ光る筋(2026-11-16。能力の決定 11-15「種を落とす間だけ、筋を光らせる」)。同じ UV の発光マスク
 * (モデル制作が描いた 1 画素の筋、Vault「セミナリア」7 節)を、光る描き方でもう一度重ねる。強さは {@link SeminariaEntity#sowGlow}(0 なら描かない)。
 * 作りはストロビラの {@code StrobilaGlowLayer} と同じ(GeckoLib の {@code AutoGlowingGeoLayer} は強さを掛けられないので使わない)。
 */
public class SeminariaGlowLayer extends GeoRenderLayer<SeminariaEntity> {

    static final ResourceLocation GLOW = OvergrownVegetation.id("textures/entity/seminaria_glowmask.png");

    private Boolean hasGlow;

    public SeminariaGlowLayer(GeoRenderer<SeminariaEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, SeminariaEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.sowGlow(partialTick);
        if (glow <= 0.01F) return;
        if (this.hasGlow == null) {
            this.hasGlow = Minecraft.getInstance().getResourceManager().getResource(GLOW).isPresent();
            if (!this.hasGlow) OvergrownVegetation.LOGGER.info("セミナリアの発光マスクがまだ無いので光らせない: {}", GLOW);
        }
        if (!this.hasGlow) return;
        RenderType rt = RenderType.entityTranslucentEmissive(GLOW);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
