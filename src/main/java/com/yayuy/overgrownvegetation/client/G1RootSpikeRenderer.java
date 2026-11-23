package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSpike;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 汚染した地面から突き上がる根(2026-11-13、能力の第 4 回。エンダーマンの 1 段目の (c-2))。
 * 今ある気根の先の絵 {@code textures/block/aerial_root_single_tip.png}(16 × 16、先が下)を<b>逆さ</b>にして、×字の板 2 枚で描く。新しい絵は作らない。
 * 地面から {@link G1RootSpike#height} の割合だけ突き出る(下の方は地面の下に残して、絵は引き伸ばさない)。光らせない。
 */
public class G1RootSpikeRenderer extends EntityRenderer<G1RootSpike> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/aerial_root_single_tip.png");
    /** 突き出たときの高さ・幅(ブロック)。【仮】 */
    private static final float HEIGHT = 1.4F;
    private static final float WIDTH = 1.0F;

    public G1RootSpikeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(G1RootSpike entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float f = entity.height(partialTick);
        if (f <= 0.01F) return;
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-entity.getYRot()));
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        float h = HEIGHT * f, w = WIDTH * 0.5F;
        // 絵は逆さ(v = 1 が上 = 根の先)。突き出た分だけ、先の側から見せる
        float vTop = 1.0F, vBottom = 1.0F - f;
        for (int i = 0; i < 2; i++) {
            PoseStack.Pose last = pose.last();
            vertex(vc, last, -w, h, 0.0F, 0.0F, vTop, light);
            vertex(vc, last, w, h, 0.0F, 1.0F, vTop, light);
            vertex(vc, last, w, 0.0F, 0.0F, 1.0F, vBottom, light);
            vertex(vc, last, -w, 0.0F, 0.0F, 0.0F, vBottom, light);
            pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        }
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light) {
        vc.vertex(pose.pose(), x, y, z).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(G1RootSpike entity) {
        return TEXTURE;
    }
}
