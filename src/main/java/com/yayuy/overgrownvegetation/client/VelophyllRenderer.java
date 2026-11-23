package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ヴェロフィル」の描画。描画倍率 1(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。発光マスクは段 1 では重ねない(光るのは爆撃の構え。次の段)。
 * 飛んでいる間は、曲がり方から出した体の傾き(ロール)と機首の上下(ピッチ)で全体を回す({@code SkyFlyer#bank}・{@code #pitch})。
 * 回す中心は胴の高さ(足元から {@link #PIVOT_Y})。
 */
public class VelophyllRenderer extends GeoEntityRenderer<VelophyllEntity> {

    /** 回す中心の高さ(ブロック)。胴の骨の付け根 y 8 単位。 */
    static final float PIVOT_Y = 0.5F;

    /** 急降下の影の絵(バニラの影)・濃さ・大きさ(半径。遠いと小さく薄く、近づくと大きく濃く)【仮・Claude 11-15】。 */
    static final net.minecraft.resources.ResourceLocation SHADOW = net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/misc/shadow.png");
    static final float SHADOW_ALPHA_MIN = 0.25F, SHADOW_ALPHA_MAX = 0.75F, SHADOW_R_MIN = 1.2F, SHADOW_R_MAX = 3.0F;
    /** 影が一番濃く大きくなる高さの差(ブロック)。 */
    static final float SHADOW_NEAR = 6.0F, SHADOW_FAR = 40.0F;

    @Override
    public void render(VelophyllEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        net.minecraft.world.entity.Entity t = entity.shadowTarget();
        if (t != null) renderDiveShadow(entity, t, partialTick, poseStack, bufferSource);
    }

    /**
     * 急降下の前触れ・落ちる間、狙う相手の足元に影を描く(能力の決定 11-14「前触れ・地面に影」)。
     * 影は相手と一緒に動き、ヴェロフィルが近づくほど大きく濃くなる(地面の起伏には沿わない平らな円)。
     */
    private static void renderDiveShadow(VelophyllEntity e, net.minecraft.world.entity.Entity t, float pt, PoseStack ps,
                                         net.minecraft.client.renderer.MultiBufferSource buffers) {
        double ex = net.minecraft.util.Mth.lerp(pt, e.xo, e.getX()), ey = net.minecraft.util.Mth.lerp(pt, e.yo, e.getY()), ez = net.minecraft.util.Mth.lerp(pt, e.zo, e.getZ());
        double tx = net.minecraft.util.Mth.lerp(pt, t.xo, t.getX()), ty = net.minecraft.util.Mth.lerp(pt, t.yo, t.getY()), tz = net.minecraft.util.Mth.lerp(pt, t.zo, t.getZ());
        float near = (float) net.minecraft.util.Mth.clamp((SHADOW_FAR - (ey - ty)) / (SHADOW_FAR - SHADOW_NEAR), 0.0D, 1.0D);
        float r = net.minecraft.util.Mth.lerp(near, SHADOW_R_MIN, SHADOW_R_MAX);
        float a = net.minecraft.util.Mth.lerp(near, SHADOW_ALPHA_MIN, SHADOW_ALPHA_MAX);
        ps.pushPose();
        ps.translate(tx - ex, ty - ey + 0.03D, tz - ez);
        PoseStack.Pose pose = ps.last();
        com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityShadow(SHADOW));
        int alpha = (int) (a * 255.0F);
        vertex(vc, pose, -r, -r, 0.0F, 0.0F, alpha);
        vertex(vc, pose, -r, r, 0.0F, 1.0F, alpha);
        vertex(vc, pose, r, r, 1.0F, 1.0F, alpha);
        vertex(vc, pose, r, -r, 1.0F, 0.0F, alpha);
        ps.popPose();
    }

    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer vc, PoseStack.Pose pose, float x, float z, float u, float v, int alpha) {
        vc.vertex(pose.pose(), x, 0.0F, z).color(255, 255, 255, alpha).uv(u, v)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT).normal(pose.normal(), 0.0F, 1.0F, 0.0F).endVertex();
    }

    public VelophyllRenderer(EntityRendererProvider.Context context) {
        super(context, new VelophyllModel());
        this.shadowRadius = 0.8F;
    }

    @Override
    protected void applyRotations(VelophyllEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick) {
        float nativeScale = com.yayuy.overgrownvegetation.client.PortRenderScale.of(animatable); // 1.20.1: 4.8.4 は nativeScale を渡さない(PortRenderScale)
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // 11-15 テストクライアント: 回したモデルの前は −Z なので、左へ曲がる(bank 負)とき左の翼を下げるには符号を返す
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }
}
