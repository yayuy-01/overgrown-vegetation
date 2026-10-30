package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyMeterBlock;
import com.yayuy.overgrownvegetation.block.LeyMeterBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 地脈計の針(2026-10-24、地脈技術の 4 段目)。文字盤(模型の前の面、北向きで z = 6)の軸 (8, 6) から、
 * 流れの強さ 0% = 左 70°・100% = 右 70° へ針を回す。前兆の間(不安定)は、ゆっくりした揺れ(汲み上げ器と同じ ±40% の幅)と
 * 小刻みな震えを足す。値は急に跳ばないよう毎フレーム少しずつ寄せる。絵は仮({@code textures/block/ley_meter_needle.png} の左上 1×8)。
 */
public class LeyMeterRenderer implements BlockEntityRenderer<LeyMeterBlockEntity> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/ley_meter_needle.png");
    /** 針の振れ幅(片側、度)。 */
    private static final float SWING = 70.0F;
    private static final float LENGTH = 4.5F / 16.0F;
    private static final float TAIL = 1.0F / 16.0F;
    private static final float HALF_WIDTH = 0.5F / 16.0F;

    public LeyMeterRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(LeyMeterBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) return;
        float target = (float) be.flow();
        if (be.isUnstable()) {
            float t = be.getLevel().getGameTime() + partialTick;
            float phase = (be.getBlockPos().hashCode() & 255) * 0.1F;
            float wander = 0.6F * Mth.sin(t * 0.11F + phase) + 0.4F * Mth.sin(t * 0.37F + phase * 2.0F);
            target = target * (1.0F + 0.4F * wander) + 0.03F * Mth.sin(t * 2.7F);
        }
        target = Mth.clamp(target, 0.0F, 1.05F);
        if (Float.isNaN(be.shownNeedle)) be.shownNeedle = target;
        be.shownNeedle += (target - be.shownNeedle) * (be.isUnstable() ? 0.35F : 0.08F);
        float angle = Mth.lerp(be.shownNeedle, -SWING, SWING);

        float facingY = be.getBlockState().getValue(LeyMeterBlock.FACING).toYRot();
        pose.pushPose();
        pose.translate(0.5D, 0.0D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(-(facingY + 180.0F))); // 模型は北向きが前(blockstate の y と同じ回し方)
        pose.translate(0.0D, 6.0D / 16.0D, -2.1D / 16.0D);          // 文字盤の軸の少し手前
        pose.mulPose(Axis.ZP.rotationDegrees(angle));               // 見ている人の右(−x)へ振れるのが正
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        Matrix4f m = pose.last().pose();
        float u1 = 1.0F / 16.0F, v1 = 8.0F / 16.0F;
        vertex(vc, pose, m, -HALF_WIDTH, LENGTH, 0.0F, 0.0F, light);
        vertex(vc, pose, m, -HALF_WIDTH, -TAIL, 0.0F, v1, light);
        vertex(vc, pose, m, HALF_WIDTH, -TAIL, u1, v1, light);
        vertex(vc, pose, m, HALF_WIDTH, LENGTH, u1, 0.0F, light);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack pose, Matrix4f m, float x, float y, float u, float v, int light) {
        vc.addVertex(m, x, y, 0.0F).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose.last(), 0.0F, 0.0F, -1.0F);
    }

    @Override
    public boolean shouldRenderOffScreen(LeyMeterBlockEntity be) {
        return false;
    }
}
