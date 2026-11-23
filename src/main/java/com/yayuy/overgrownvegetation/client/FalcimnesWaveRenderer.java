package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.FalcimnesWaveEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * ファルキムネスの飛ぶ斬撃の見た目(2026-11-05)。前へ反った三日月を、自分で光る板で描く(加算。光の筋と同じ地脈の黄緑)。
 * 芯(明るく細い)と、少し後ろに広くて暗いにじみの 2 枚。出た直後 2 tick で現れ、最後の 3 tick で薄れる。
 * 当たり判定の形(横 {@code width} × 厚み 0.5)と同じ大きさで描く —— 見た目より大きく当たらない。
 */
public class FalcimnesWaveRenderer extends EntityRenderer<FalcimnesWaveEntity> {

    /** 芯と、にじみの色(地脈の黄緑。鎌の光の筋と同じ)。 */
    static final int CORE = 0xD4FF3A, GLOW = 0x7CB51E;
    static final int SEG = 12;

    public FalcimnesWaveRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(FalcimnesWaveEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float t = e.tickCount + partialTick;
        float a = Mth.clamp(t / 2.0F, 0.0F, 1.0F) * Mth.clamp((e.life() - t) / 3.0F, 0.0F, 1.0F);
        if (a <= 0.01F) return;
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-Mth.lerp(partialTick, e.yRotO, e.getYRot())));
        VertexConsumer vc = buffers.getBuffer(GlowRenderTypes.ADDITIVE);
        PoseStack.Pose p = pose.last();
        float w = e.width();
        // にじみ(広く、少し後ろ、暗く)→ 芯(細く、明るく)。
        arc(vc, p, w * 1.15F, 0.42F, -0.25F, 0.55F, GLOW, 0.35F * a);
        arc(vc, p, w, 0.25F, 0.0F, 0.6F, CORE, 0.9F * a);
        arc(vc, p, w * 0.85F, 0.08F, 0.08F, 0.62F, 0xF4FFC8, 0.8F * a);
        pose.popPose();
    }

    /**
     * 体の前(+Z)へ反った三日月を 1 枚。{@code width} 横幅・{@code half} 真ん中の厚みの半分・{@code back} 前後のずれ・{@code bow} 反りの深さ。
     */
    static void arc(VertexConsumer vc, PoseStack.Pose p, float width, float half, float back, float bow, int rgb, float alpha) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, al = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        for (int i = 0; i < SEG; i++) {
            float u0 = -1.0F + 2.0F * i / SEG, u1 = -1.0F + 2.0F * (i + 1) / SEG;
            float x0 = u0 * width * 0.5F, x1 = u1 * width * 0.5F;
            float z0 = back + bow * (1.0F - u0 * u0), z1 = back + bow * (1.0F - u1 * u1);
            float h0 = half * Mth.sqrt(Math.max(0.0F, 1.0F - u0 * u0)), h1 = half * Mth.sqrt(Math.max(0.0F, 1.0F - u1 * u1));
            quad(vc, p, x0, -h0, z0, x1, -h1, z1, x1, h1, z1, x0, h0, z0, r, g, b, al);
        }
    }

    /** 両面の四角(加算なので裏も描く)。 */
    static void quad(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float az, float bx, float by, float bz,
                     float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int b, int a) {
        vc.vertex(p.pose(), ax, ay, az).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), bx, by, bz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), cx, cy, cz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), dx, dy, dz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), dx, dy, dz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), cx, cy, cz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), bx, by, bz).color(r, g, b, a).endVertex();
        vc.vertex(p.pose(), ax, ay, az).color(r, g, b, a).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(FalcimnesWaveEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
