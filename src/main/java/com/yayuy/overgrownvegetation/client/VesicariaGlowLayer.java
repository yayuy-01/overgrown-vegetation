package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VesicariaEntity;
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
 * ヴェシカリアの袋の脈の光(2026-11-15 段 2。能力の決定「突撃中、温めた袋が膨らんで黄緑に光る」)。同じ UV の発光マスク
 * (透明の上に 1 画素の筋だけ。アニメのセッションが描き直した)を光る描き方でもう一度重ねる。強さは {@link VesicariaEntity#sacGlow}(0 なら描かない)。
 * 脈は袋の地(鱗の下)にあるので、鱗が開くと見えてくる。作りは {@link StrobilaGlowLayer} と同じ。
 */
public class VesicariaGlowLayer extends GeoRenderLayer<VesicariaEntity> {

    static final ResourceLocation GLOW = OvergrownVegetation.id("textures/entity/vesicaria_glowmask.png");

    private Boolean hasGlow;

    public VesicariaGlowLayer(GeoRenderer<VesicariaEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, VesicariaEntity animatable, BakedGeoModel bakedModel, @Nullable RenderType renderType,
                       MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        float glow = animatable.sacGlow(partialTick);
        if (glow <= 0.01F) return;
        if (this.hasGlow == null) {
            this.hasGlow = Minecraft.getInstance().getResourceManager().getResource(GLOW).isPresent();
            if (!this.hasGlow) OvergrownVegetation.LOGGER.info("ヴェシカリアの発光マスクがまだ無いので光らせない: {}", GLOW);
        }
        if (!this.hasGlow) return;
        RenderType rt = RenderType.entityTranslucentEmissive(GLOW);
        int a = Math.round(Math.min(1.0F, glow) * 255.0F);
        getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, rt, bufferSource.getBuffer(rt), partialTick,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.color(a, 255, 255, 255));
    }
}
