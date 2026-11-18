package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.RenderUtil;

import javax.annotation.Nullable;

/**
 * 大群のまとめ描き({@link CrowdDraw})に乗れる GeckoLib の描き手(2026-11-18)。{@link #crowd(boolean)} で入れた種だけ、
 * ワールドの Mob の段で、頂点を書く代わりに骨の行列だけを集める。アニメの計算・向き・傾き・大きさ・死の倒れ方は
 * GeckoLib のいつもの流れ(PoseStack)のまま通るので、描き手ごとの {@code applyRotations} の手直しもそのまま効く。
 *
 * <p>いつもの描き方に戻すとき({@link #crowdEligible}): 層がある種・GUI など Mob の段の外・光る輪郭(発光の効果)・透明・
 * 描き方が {@code entityCutoutNoCull} でない(半透明など)・汎用の感染体の絵・シェーダーパック使用中。</p>
 */
public class CrowdGeoEntityRenderer<T extends Entity & GeoAnimatable> extends GeoEntityRenderer<T> {

    private boolean crowd;
    @Nullable
    private CrowdDraw.Batch capture;

    public CrowdGeoEntityRenderer(EntityRendererProvider.Context context, GeoModel<T> model) {
        super(context, model);
    }

    /** この種をまとめ描きに乗せる(既定は乗せない)。 */
    public CrowdGeoEntityRenderer<T> crowd(boolean on) {
        this.crowd = on;
        return this;
    }

    protected boolean crowdEligible(T animatable, @Nullable RenderType renderType) {
        if (!this.crowd || renderType == null || !CrowdDraw.capturing() || !getRenderLayers().isEmpty()) return false;
        if (animatable.isInvisible() || Minecraft.getInstance().shouldEntityAppearGlowing(animatable)) return false;
        if (renderType != RenderType.entityCutoutNoCull(getTextureLocation(animatable))) return false;
        if (com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient.of(animatable) != null) return false;
        return !ShaderPacks.active();
    }

    @Override
    public void actuallyRender(PoseStack poseStack, T animatable, BakedGeoModel model, @Nullable RenderType renderType,
                               MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick,
                               int packedLight, int packedOverlay, int colour) {
        if (isReRender || buffer == null || this.capture != null || !crowdEligible(animatable, renderType)) {
            super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            return;
        }
        CrowdDraw.Batch batch = CrowdDraw.batch(model, renderType);
        if (batch == null) {
            super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            return;
        }
        batch.begin(packedLight, packedOverlay, colour);
        this.capture = batch;
        try {
            super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer, false, partialTick, packedLight, packedOverlay, colour);
        } finally {
            this.capture = null;
            batch.commit();
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        CrowdDraw.Batch batch = this.capture;
        if (batch == null) {
            super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            return;
        }
        // GeoEntityRenderer#renderRecursively と同じ行列の積み方(頂点を書く所だけを、骨の行列を集めるのに替える)
        poseStack.pushPose();
        RenderUtil.translateMatrixToBone(poseStack, bone);
        RenderUtil.translateToPivotPoint(poseStack, bone);
        RenderUtil.rotateMatrixAroundBone(poseStack, bone);
        RenderUtil.scaleMatrixForBone(poseStack, bone);
        if (bone.isTrackingMatrices()) {
            Matrix4f poseState = new Matrix4f(poseStack.last().pose());
            Matrix4f localMatrix = RenderUtil.invertAndMultiplyMatrices(poseState, this.entityRenderTranslations);
            bone.setModelSpaceMatrix(RenderUtil.invertAndMultiplyMatrices(poseState, this.modelRenderTranslations));
            bone.setLocalSpaceMatrix(RenderUtil.translateMatrix(localMatrix, getRenderOffset(this.animatable, 1).toVector3f()));
            bone.setWorldSpaceMatrix(RenderUtil.translateMatrix(new Matrix4f(localMatrix), this.animatable.position().toVector3f()));
        }
        RenderUtil.translateAwayFromPivotPoint(poseStack, bone);

        int index = batch.nextBone();
        batch.bone(poseStack.last(), !bone.isHidden());
        if (bone.isHidingChildren()) {
            batch.hideBones(batch.mesh.subtree[Math.min(index, batch.mesh.subtree.length - 1)] - 1);
        } else {
            for (GeoBone child : bone.getChildBones()) {
                renderRecursively(poseStack, animatable, child, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
            }
        }
        poseStack.popPose();
    }
}
