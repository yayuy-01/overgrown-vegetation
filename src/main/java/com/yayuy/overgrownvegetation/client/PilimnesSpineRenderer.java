package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.PilimnesSpine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * ピリムネスの棘の描画(2026-11-16)。【仮】アクムネスの棘と同じく、バニラの矢の絵を矢と同じ十字の板で、樹皮の色を掛けて描く
 * (背の棘の形を写した専用の見た目はモデル制作の番)。棘の弾きは長く、扇・雨は少し短く。
 */
public class PilimnesSpineRenderer extends EntityRenderer<PilimnesSpine> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/projectiles/arrow.png");

    public PilimnesSpineRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PilimnesSpine e, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, e.yRotO, e.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, e.xRotO, e.getXRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        float len = e.kind() == PilimnesSpine.FLICK ? 1.8F : 1.3F;
        poseStack.scale(0.05625F * len, 0.05625F * 0.9F, 0.05625F * 0.9F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(TEXTURE));
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            PoseStack.Pose pose = poseStack.last();
            vertex(pose, vc, -8, -2, 0.0F, 0.0F, light);
            vertex(pose, vc, 8, -2, 0.5F, 0.0F, light);
            vertex(pose, vc, 8, 2, 0.5F, 0.15625F, light);
            vertex(pose, vc, -8, 2, 0.0F, 0.15625F, light);
        }
        poseStack.popPose();
        super.render(e, entityYaw, partialTick, poseStack, buffer, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, float u, float v, int light) {
        vc.addVertex(pose, (float) x, (float) y, 0.0F).setColor(0xFFB89A6A).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(PilimnesSpine entity) {
        return TEXTURE;
    }
}
