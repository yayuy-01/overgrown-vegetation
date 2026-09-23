package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ArboraRenderer extends GeoEntityRenderer<ArboraEntity> {

    public ArboraRenderer(EntityRendererProvider.Context context) {
        super(context, new ArboraModel());
        this.shadowRadius = 0.6F;
    }

    /**
     * 姿勢の補正({@code PhalangiotarbusRenderer} と同じ考え方):
     * <ul>
     *     <li>壁のぼり中({@code climbAmount})— モデル全体を X 軸まわりに 90 度立てて、体を壁面と平行にする。
     *         向きは {@code aiStep()} が壁の方向へ固定してあるので頭が上を向く。</li>
     *     <li>天井這い中({@code ceilingAmount})— モデルを前後軸(Z)まわりに 180 度回して逆さにし、
     *         当たり判定の上端(= 天井との接地面)まで持ち上げて張り付いて見せる。</li>
     * </ul>
     */
    @Override
    protected void applyRotations(ArboraEntity animatable, PoseStack poseStack, float ageInTicks,
                                 float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        float ceiling = animatable.getCeilingAmount(partialTick);
        if (ceiling > 0.001F) {
            float top = animatable.getBbHeight() / nativeScale;
            poseStack.translate(0.0F, top * ceiling, 0.0F);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F * ceiling));
        }

        float climb = animatable.getClimbAmount(partialTick);
        if (climb > 0.001F) {
            // super.applyRotations の YP(180 - yaw)回転後、モデルローカルの -Z が「壁の方向」になる。
            // 腹が壁に接するところまで寄せてから体を立てる。
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
