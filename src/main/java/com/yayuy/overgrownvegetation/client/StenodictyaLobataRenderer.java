package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ステノディクティア」(成虫)の描画。倍率 1(1 単位 = ブロックの 1 画素)。飛んでいる間は体の傾き(ロール)と機首の上下(ピッチ)で
 * 全体を胴の付け根の高さを中心に回す({@code SkyFlyer#bank}・{@code #pitch})。ぶら下がる姿はアニメ(perch・rest)が体を逆さに回す。
 */
public class StenodictyaLobataRenderer extends GeoEntityRenderer<StenodictyaLobataEntity> {

    /** 回す中心(モデル単位)= 胴の骨の付け根 y 4.2。 */
    static final float PIVOT_Y = 4.2F;

    public StenodictyaLobataRenderer(EntityRendererProvider.Context context) {
        super(context, new StenodictyaLobataModel());
        this.shadowRadius = 0.3F;
    }

    @Override
    protected void applyRotations(StenodictyaLobataEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick) {
        float nativeScale = com.yayuy.overgrownvegetation.client.PortRenderScale.of(animatable); // 1.20.1: 4.8.4 は nativeScale を渡さない(PortRenderScale)
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y / 16.0F, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // セミナリア・ヴェロフィルと同じ向き
        poseStack.translate(0.0F, -PIVOT_Y / 16.0F, 0.0F);
    }
}
