package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.HiveRitualEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import javax.annotation.Nullable;

/**
 * 儀式の蔦の描画。蔦のほかに、モデルの中の空の {@code camera} 骨(head の子、目の位置)の
 * ワールド行列を毎フレーム {@link HiveRitualClient} に渡す。乗っている本人の視点はそれに追従する
 * ({@code HiveRitualCameraMixin})。
 *
 * <p>視点が骨の中に入っていてもカリングで消えないよう、{@link #shouldRender} は常に true。
 * (行列は描画のときにしか更新されないので、描かれないとカメラが止まる。)
 */
public class HiveRitualRenderer extends GeoEntityRenderer<HiveRitualEntity> {

    public HiveRitualRenderer(EntityRendererProvider.Context context) {
        super(context, new HiveRitualModel());
        this.shadowRadius = 0.0F;
    }

    @Override
    public boolean shouldRender(HiveRitualEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return true;
    }

    /**
     * GeckoLib は {@code LivingEntity} でないエンティティの yaw を 0 として描く(体の向きが無いので)。
     * 儀式の蔦は「プレイヤーがノードを見る向き」に回っていないと、蔦もカメラ骨も別の方角を向いて
     * 壁に埋まる(2026-09-18 に実際に起きた)。ここで自分の yaw を渡す。
     */
    @Override
    protected void applyRotations(HiveRitualEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks,
                Mth.rotLerp(partialTick, animatable.yRotO, animatable.getYRot()), partialTick, nativeScale);
    }

    @Override
    public void preRender(PoseStack poseStack, HiveRitualEntity animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, colour);
        // 先に追跡を有効にしておくと、このフレームの描画で行列が入る(getWorldSpaceMatrix が有効化するだけだと 1 フレーム遅れる)。
        model.getBone("camera").ifPresent(bone -> bone.setTrackingMatrices(true));
        model.getBone("cam_ref").ifPresent(bone -> bone.setTrackingMatrices(true));
    }

    @Override
    public void postRender(PoseStack poseStack, HiveRitualEntity animatable, BakedGeoModel model,
                           MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender,
                           float partialTick, int packedLight, int packedOverlay, int colour) {
        super.postRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, colour);
        if (!isReRender) {
            model.getBone("camera").ifPresent(camera -> model.getBone("cam_ref").ifPresent(ref ->
                    HiveRitualClient.updateCamera(animatable, camera.getWorldSpaceMatrix(), ref.getWorldSpaceMatrix())));
        }
    }
}
