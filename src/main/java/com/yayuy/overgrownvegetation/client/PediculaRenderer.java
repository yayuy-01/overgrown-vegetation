package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ペディキュラ」の描画(2026-11-15 段 1)。袋の膜が半透明の絵なので、半透明で描く。発光マスクは段 1 では重ねない。
 *
 * <p>体の向きは本体がゆっくり回す(正面の無い塊)。そこへ風に揺られる小さな傾き(前後・左右 ±{@link #SWAY_DEG} 度、
 * 個体ごとに位相の違う 2 つの波)を、塊の真ん中(足元から {@link #PIVOT_Y})を中心に足す。
 * 旧版(09-23 ユーザー指定)の 3 軸の転がりは、r6 が上下のある形(垂れた毛)なので入れていない【仮・聞く】。死んでも横倒しにしない。
 */
public class PediculaRenderer extends GeoEntityRenderer<PediculaEntity> {

    /** 回す中心(ブロック)。main の骨の付け根 y 29 単位。 */
    static final float PIVOT_Y = 29.0F / 16.0F;
    /** 揺れの幅(度)と周期(tick)【仮】。 */
    static final float SWAY_DEG = 4.0F;
    static final float SWAY_PERIOD_A = 190.0F, SWAY_PERIOD_B = 270.0F;

    public PediculaRenderer(EntityRendererProvider.Context context) {
        super(context, new PediculaModel());
        this.shadowRadius = 0.0F;   // 上空に浮いているので影は地面に届かない
    }

    @Override
    public RenderType getRenderType(PediculaEntity animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }

    @Override
    protected void applyRotations(PediculaEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - rotationYaw));
        float ph = (animatable.getUUID().getLeastSignificantBits() & 0xFFFF) / 65536.0F * (float) (Math.PI * 2.0D);
        float a = SWAY_DEG * (float) Math.sin(ageInTicks * (Math.PI * 2.0D) / SWAY_PERIOD_A + ph);
        float b = SWAY_DEG * (float) Math.sin(ageInTicks * (Math.PI * 2.0D) / SWAY_PERIOD_B + ph * 1.7F);
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(a));
        poseStack.mulPose(Axis.ZP.rotationDegrees(b));
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }
}
