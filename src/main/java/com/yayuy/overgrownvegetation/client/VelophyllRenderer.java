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

    public VelophyllRenderer(EntityRendererProvider.Context context) {
        super(context, new VelophyllModel());
        this.shadowRadius = 0.8F;
    }

    @Override
    protected void applyRotations(VelophyllEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // 11-15 テストクライアント: 回したモデルの前は −Z なので、左へ曲がる(bank 負)とき左の翼を下げるには符号を返す
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }
}
