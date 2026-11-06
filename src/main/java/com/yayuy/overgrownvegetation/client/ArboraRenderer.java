package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * アルボラの描画(2026-11-05 作り直し)。体を<b>張り付いている面に沿って回す</b>(上向き = 面の向き、天井では逆さま)。
 * 回し方は {@link com.yayuy.overgrownvegetation.entity.cling.ClingPose}(角で 3〜4 tick かけてなめらかに)。
 * 当たり判定は回らないので、体の箱の真ん中を軸に回す —— 箱は 0.98 の立方体なので、どの向きでも足の面が張り付いた面に来る。
 * 床に立っている間はバニラの向き(体の yaw)のまま。
 */
public class ArboraRenderer extends GeoEntityRenderer<ArboraEntity> {

    public ArboraRenderer(EntityRendererProvider.Context context) {
        super(context, new ArboraModel());
        this.shadowRadius = 0.6F;
    }

    @Override
    protected void applyRotations(ArboraEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        if (animatable.pose().upright(partialTick)) {
            super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
            return;
        }
        float pivot = animatable.getBbHeight() * 0.5F / nativeScale;
        poseStack.translate(0.0F, pivot, 0.0F);
        poseStack.mulPose(animatable.pose().rotation(partialTick, new Quaternionf()));
        poseStack.translate(0.0F, -pivot, 0.0F);
        if (animatable.deathTime > 0) {
            float t = (animatable.deathTime + partialTick - 1.0F) / 20.0F * 1.6F;
            poseStack.mulPose(Axis.ZP.rotationDegrees(Math.min(Mth.sqrt(t), 1.0F) * getDeathMaxRotation(animatable)));
        }
    }
}
