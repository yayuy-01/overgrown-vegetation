package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * アクムネスの棘の描画(2026-11-15)。【仮】バニラの矢の絵を矢と同じ十字の板で描く(狙撃・縫い止めは長く、散弾は短く)。
 * 本体の針の先を写した模型は旧版(parked)の絵に合わせたもので今の絵に合わないので使っていない。専用の見た目はモデル制作の番。
 */
public class AcumnesSpineRenderer extends EntityRenderer<AcumnesSpine> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/projectiles/arrow.png");

    public AcumnesSpineRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(AcumnesSpine e, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, e.yRotO, e.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, e.xRotO, e.getXRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        float len = e.kind() == AcumnesSpine.SCATTER ? 0.7F : 1.6F;
        poseStack.scale(0.05625F * len, 0.05625F * 0.8F, 0.05625F * 0.8F);
        poseStack.translate(-4.0F, 0.0F, 0.0F);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(TEXTURE));
        PoseStack.Pose pose = poseStack.last();
        for (int j = 0; j < 4; j++) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose = poseStack.last();
            vertex(pose, vc, -8, -2, 0, 0.0F, 0.0F, light);
            vertex(pose, vc, 8, -2, 0, 0.5F, 0.0F, light);
            vertex(pose, vc, 8, 2, 0, 0.5F, 0.15625F, light);
            vertex(pose, vc, -8, 2, 0, 0.0F, 0.15625F, light);
        }
        poseStack.popPose();
        super.render(e, entityYaw, partialTick, poseStack, buffer, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, int z, float u, float v, int light) {
        vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(0xFFC8E070).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public ResourceLocation getTextureLocation(AcumnesSpine entity) {
        return TEXTURE;
    }
}
