package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.WindVaneBlockEntity;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 風見の羽根(2026-10-23 胞子の場と風 第 2 段 H)。棒の上の縦の板 1 枚(両面)を、卓越風の吹いてくる方(風上)へ矢の先が向くよう回す。
 * 卓越風はサーバーと同じ式({@link ClientPrevailingWind})。受け取っていなければ近距離の風({@link CanopyWind})の向き。
 * 向きは急に跳ばないよう毎フレーム少しずつ寄せる。絵は仮({@code textures/block/wind_vane.png}、docs/TEXTURE_TODO.md)。
 */
public class WindVaneRenderer implements BlockEntityRenderer<WindVaneBlockEntity> {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/block/wind_vane.png");

    public WindVaneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WindVaneBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (be.getLevel() == null) return;
        PrevailingWind.Sample s = ClientPrevailingWind.sample(be.getLevel().getDayTime());
        double downwind = s != null ? Math.atan2(s.dirZ, s.dirX) : Math.atan2(CanopyWind.dirZ(), CanopyWind.dirX());
        float target = (float) (downwind + Math.PI); // 矢の先は風上
        if (Float.isNaN(be.shownAngle)) be.shownAngle = target;
        be.shownAngle += (float) Math.IEEEremainder(target - be.shownAngle, Math.PI * 2.0) * 0.05F;

        pose.pushPose();
        pose.translate(0.5D, 1.0D, 0.5D);
        pose.mulPose(Axis.YP.rotation(-be.shownAngle));
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        Matrix4f m = pose.last().pose();
        float x0 = -0.55F, x1 = 0.55F, y0 = 0.0F, y1 = 0.55F;
        // 板(x が矢の先の向き、テクスチャの右が矢の先)
        vertex(vc, pose, m, x0, y1, 0, 0, 0, light);
        vertex(vc, pose, m, x0, y0, 0, 0, 1, light);
        vertex(vc, pose, m, x1, y0, 0, 1, 1, light);
        vertex(vc, pose, m, x1, y1, 0, 1, 0, light);
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack pose, Matrix4f m, float x, float y, float z, float u, float v, int light) {
        vc.addVertex(m, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose.last(), 0.0F, 0.0F, 1.0F);
    }

    @Override
    public boolean shouldRenderOffScreen(WindVaneBlockEntity be) {
        return false;
    }
}
