package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import com.yayuy.overgrownvegetation.entity.PilimnesSpine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.function.ToDoubleFunction;

/**
 * 棘の弾の描画(2026-11-16 コードの見直し ①14 でアクムネス・ピリムネスの写しをまとめた。数値は元のまま)。
 * 【仮】バニラの矢の絵を矢と同じ十字の板で、色を掛けて描く(専用の見た目はモデル制作の番)。種ごとの違いは長さ・太さ・色だけ。
 * <ul>
 *   <li>アクムネス(2026-11-15): 狙撃・縫い止めは長く(1.6)、散弾は短く(0.7)。太さ 0.8・黄緑。本体の針の先を写した模型は旧版(parked)の絵に合わず使っていない。</li>
 *   <li>ピリムネス(2026-11-16): 棘の弾きは長く(1.8)、扇・雨は少し短く(1.3)。太さ 0.9・樹皮の色。</li>
 * </ul>
 */
public class SpineRenderer<T extends Projectile> extends EntityRenderer<T> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/projectiles/arrow.png");

    private final ToDoubleFunction<T> length;
    private final float thickness;
    private final int color;

    public SpineRenderer(EntityRendererProvider.Context context, ToDoubleFunction<T> length, float thickness, int color) {
        super(context);
        this.length = length;
        this.thickness = thickness;
        this.color = color;
    }

    public static SpineRenderer<AcumnesSpine> acumnes(EntityRendererProvider.Context context) {
        return new SpineRenderer<>(context, e -> e.kind() == AcumnesSpine.SCATTER ? 0.7D : 1.6D, 0.8F, 0xFFC8E070);
    }

    public static SpineRenderer<PilimnesSpine> pilimnes(EntityRendererProvider.Context context) {
        return new SpineRenderer<>(context, e -> e.kind() == PilimnesSpine.FLICK ? 1.8D : 1.3D, 0.9F, 0xFFB89A6A);
    }

    @Override
    public void render(T e, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, e.yRotO, e.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, e.xRotO, e.getXRot())));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        float len = (float) this.length.applyAsDouble(e);
        poseStack.scale(0.05625F * len, 0.05625F * this.thickness, 0.05625F * this.thickness);
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

    private void vertex(PoseStack.Pose pose, VertexConsumer vc, int x, int y, float u, float v, int light) {
        vc.vertex(pose.pose(), (float) x, (float) y, 0.0F).color(this.color).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.normal(), 0.0F, 1.0F, 0.0F).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return TEXTURE;
    }
}
