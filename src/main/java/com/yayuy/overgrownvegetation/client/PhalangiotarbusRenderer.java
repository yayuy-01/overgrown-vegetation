package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PhalangiotarbusRenderer extends GeoEntityRenderer<PhalangiotarbusEntity> {

    public PhalangiotarbusRenderer(EntityRendererProvider.Context context) {
        super(context, new PhalangiotarbusModel());
        this.shadowRadius = 0.9F;
    }

    /**
     * 姿勢の補正:
     * <ul>
     *     <li>壁登り中({@code climbAmount})— モデル全体をX軸まわりに90度立てて体を壁面と平行にする。
     *         {@code aiStep()} で向きを壁の方向へ固定してあるので頭が上を向く。</li>
     *     <li>天井這い中({@code ceilingAmount})— モデルを前後軸(Z)まわりに180度回して逆さにし、
     *         天井の高さまで持ち上げて張り付いているように見せる。</li>
     * </ul>
     */
    @Override
    protected void applyRotations(PhalangiotarbusEntity animatable, PoseStack poseStack, float ageInTicks,
                                 float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        float ceiling = animatable.getCeilingAmount(partialTick);
        if (ceiling > 0.001F) {
            // 当たり判定の上端(＝天井との接地面)を軸に前後方向へ180度ひっくり返す。
            float top = animatable.getBbHeight() / nativeScale;
            poseStack.translate(0.0F, top * ceiling, 0.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F * ceiling));
        }

        float climb = animatable.getClimbAmount(partialTick);
        if (climb > 0.001F) {
            // super.applyRotations の YP(180 - yaw) 回転後、モデルローカルの -Z が「壁の方向」になる。
            // まず腹が樹皮に接するところまでモデル全体を壁へ寄せてから、体を立てる。
            float wallOffset = (animatable.getBbWidth() * 0.5F - 0.1F) * climb / nativeScale;
            if (wallOffset > 0.0F) {
                poseStack.translate(0.0F, 0.0F, -wallOffset);
            }
            float pivot = animatable.getBbHeight() * 0.5F / nativeScale;
            poseStack.translate(0.0F, pivot, 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F * climb));
            poseStack.translate(0.0F, -pivot, 0.0F);
        }
    }
}
