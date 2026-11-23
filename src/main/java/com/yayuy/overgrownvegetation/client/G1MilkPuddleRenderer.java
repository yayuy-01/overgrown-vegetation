package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 胞子の乳だまり({@link G1MilkPuddle})の絵(2026-11-13、能力の第 2 回)。地面のすぐ上に、白っぽい汁の板を 1 枚。
 * 出てすぐ広がり(6 tick)、終わりの 1 秒で薄れる。向きは実体ごとにばらす(同じ絵が並んで見えないように)。影は付けない。
 */
public class G1MilkPuddleRenderer extends EntityRenderer<G1MilkPuddle> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/g1_milk_puddle.png");
    /** 広がりきるまで・薄れ始めてから消えるまで(tick)。【仮】 */
    private static final float GROW_TICKS = 6.0F;
    private static final float FADE_TICKS = 20.0F;

    public G1MilkPuddleRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(G1MilkPuddle entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float age = entity.tickCount + partialTick;
        float size = entity.radius() * Mth.clamp(0.35F + 0.65F * age / GROW_TICKS, 0.35F, 1.0F);
        int alpha = (int) (255.0F * Mth.clamp((entity.duration() - age) / FADE_TICKS, 0.0F, 1.0F));
        if (alpha <= 0) return;
        pose.pushPose();
        // 地面のすぐ上(実体ごとに少し高さを変えて、重なった乳だまり同士がちらつかないように)
        pose.translate(0.0F, 0.012F + (entity.getId() % 5) * 0.002F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees((entity.getId() * 73) % 360));
        PoseStack.Pose last = pose.last();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        vertex(vc, last, -size, -size, 0.0F, 0.0F, alpha, light);
        vertex(vc, last, -size, size, 0.0F, 1.0F, alpha, light);
        vertex(vc, last, size, size, 1.0F, 1.0F, alpha, light);
        vertex(vc, last, size, -size, 1.0F, 0.0F, alpha, light);
        pose.popPose();
        super.render(entity, yaw, partialTick, pose, buffers, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float z, float u, float v, int alpha, int light) {
        vc.vertex(pose.pose(), x, 0.0F, z).color(255, 255, 255, alpha).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(G1MilkPuddle entity) {
        return TEXTURE;
    }
}
