package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「アクムネス」の描画。描画倍率 1(テクスチャは 1 単位 1 テクセル)。発光マスクは段 1 では重ねない(棘の先が光るのは狙撃の構え。段 2)。
 * 飛んでいる間の体の傾き・機首の上下は土台({@code SkyFlyer#bank}・{@code #pitch})。止まっては飛ぶ間はまっすぐ飛ぶのでほぼ傾かない。
 * 回す中心は胸の高さ(足元から {@link #PIVOT_Y})。
 */
public class AcumnesRenderer extends GeoEntityRenderer<AcumnesEntity> {

    /** 回す中心の高さ(ブロック)。胸の骨 body の付け根 y 39.6 単位。 */
    static final float PIVOT_Y = 2.475F;

    public AcumnesRenderer(EntityRendererProvider.Context context) {
        super(context, new AcumnesModel());
        this.shadowRadius = 1.0F;
    }

    @Override
    protected void applyRotations(AcumnesEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // ヴェロフィルと同じ向き(11-15 に確かめた符号)
        poseStack.translate(0.0F, -PIVOT_Y, 0.0F);
    }

    /** 死ぬときに赤く回って倒れるバニラの動きはしない(death のアニメがある)。 */
    @Override
    protected float getDeathMaxRotation(AcumnesEntity animatable) {
        return 0.0F;
    }
}
