package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「メガネウラ」の描画。描画倍率 1(1 単位 1 画素)。飛んでいる間は、曲がり方から出した体の傾き(ロール)と機首の上下(ピッチ)で全体を回す
 * ({@code SkyFlyer#bank}・{@code #pitch}。ヴェロフィルと同じ。アニメは翅 4 枚しか動かさないので、傾きはここで付ける)。
 */
public class MeganeuraRenderer extends GeoEntityRenderer<MeganeuraEntity> {

    /** 回す中心の高さ(ブロック)。胴の高さ。 */
    static final float PIVOT_Y = 0.25F;

    public MeganeuraRenderer(EntityRendererProvider.Context context) {
        super(context, new MeganeuraModel());
        this.shadowRadius = 0.4F;
    }

    @Override
    protected void applyRotations(MeganeuraEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // 回したモデルの前は −Z(ヴェロフィルで確かめた向き)
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }

    @Override
    protected float getDeathMaxRotation(MeganeuraEntity animatable) {
        return 0.0F;
    }
}
