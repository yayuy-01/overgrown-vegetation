package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ペディキュラ」の描画(2026-11-15 段 1)。袋の膜が半透明の絵なので、半透明で描く。発光マスクは段 1 では重ねない。
 *
 * <p><b>3 軸の転がり</b>(ユーザー決定 11-15: 旧版 09-23 の指定「x,y,z の回転すべてがランダムに働き、風に流されていく
 * 巨大な胞子の塊のような感じに」を r6 にも付ける)。角度は {@link PediculaEntity#tumbleAngles}(旧版の作りのまま)、
 * 進み方は {@link PediculaEntity#tumbleClock} —— 風に流される速さで進むので、昼の風では転がり、凪の夜はゆっくりになる。
 * 支点は塊の真ん中(main の骨の付け根、足元から {@link #PIVOT_Y})。体の向き(yaw)は使わない(転がる塊に正面は無い)。
 * 死んでも横倒しにしない・赤くならない(破れて落ちる。段 2)。
 *
 * <p>口({@code lip} の骨)の世界の位置と向きを描画のたびに本体へ書く(胞子をこぼす粒の出どころ。GeckoLib の骨は同じ種類の全員で
 * 使い回しなので、その個体を描いている間に読む)。
 */
public class PediculaRenderer extends GeoEntityRenderer<PediculaEntity> {

    /** 回す中心(ブロック)。main の骨の付け根 y 29 単位 = 当たり(高さ 3.6)の真ん中とほぼ同じ。 */
    static final float PIVOT_Y = 29.0F / 16.0F;

    private final float[] angles = new float[3];
    static final String LIP = "lip";
    private static final boolean DEV_LIP = Boolean.getBoolean("ov.devbridge");

    public PediculaRenderer(EntityRendererProvider.Context context) {
        super(context, new PediculaModel());
        this.shadowRadius = 0.0F;   // 上空に浮いているので影は地面に届かない
    }

    @Override
    public RenderType getRenderType(PediculaEntity animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    public void preRender(PoseStack poseStack, PediculaEntity animatable, BakedGeoModel model, MultiBufferSource bufferSource, VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        model.getBone(LIP).ifPresent(b -> b.setTrackingMatrices(true));
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }

    @Override
    public void renderRecursively(PoseStack poseStack, PediculaEntity animatable, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        if (!isReRender && LIP.equals(bone.getName()) && bone.isTrackingMatrices()) {
            Vector3d p = bone.getWorldPosition();
            Vector3f up = bone.getWorldSpaceMatrix().transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
            if (up.lengthSquared() > 1.0E-6F) up.normalize();
            animatable.lipX = p.x;
            animatable.lipY = p.y;
            animatable.lipZ = p.z;
            animatable.lipDx = up.x;
            animatable.lipDy = up.y;
            animatable.lipDz = up.z;
            animatable.lipSeenTick = animatable.tickCount;
            if (DEV_LIP && animatable.tickCount % 100 == 0) {
                System.out.println("[pedicula-lip] 体 " + animatable.position() + " 口 " + p + " 向き " + up);
            }
        }
    }

    /** 破れて落ちる間に、バニラの「死ぬと赤くなる」を出さない。 */
    @Override
    public int getPackedOverlay(PediculaEntity animatable, float u, float partialTick) {
        return animatable.isDeadOrDying() ? OverlayTexture.NO_OVERLAY : super.getPackedOverlay(animatable, u, partialTick);
    }

    @Override
    protected void applyRotations(PediculaEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        PediculaEntity.tumbleAngles(animatable.getUUID(), animatable.tumbleClock(partialTick), this.angles);
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(new Quaternionf().rotationZYX(this.angles[2], this.angles[1], this.angles[0]));
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }
}
