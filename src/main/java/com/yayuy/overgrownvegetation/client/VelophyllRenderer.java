package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ヴェロフィル」の描画。
 *
 * <h2>全身の傾きはここで付ける</h2>
 * このモデルは<b>根ボーンが {@code head}</b> で、{@code head} を回すと全身が頭を支点に回ってしまう
 * ({@code velophyll.geo.json} は Blockbench の出力そのまま)。そのため
 * 「エリトラで飛ぶような動き」の要である<b>機首の上げ下げと旋回のバンク</b>は
 * アニメーション JSON ではなくここで、<b>体の中心を支点に</b>付ける。
 * 値は速度から作って本体側で平滑してある({@link VelophyllEntity#getPitchAmount} /
 * {@link VelophyllEntity#getBankAmount})ので、ここでは掛け算するだけ。
 *
 * <h2>地上では体を持ち上げる</h2>
 * モデルは飛んでいる姿で作られていて、<b>翼の膜が原点(足元の高さ)より 0.5 ブロックほど下へ
 * 垂れている</b>。そのまま地面に置くと膜が床に沈むので、地上にいる間だけ持ち上げる。
 * 飛行中は持ち上げない(当たり判定と体の位置がずれるため)。切り替えは
 * {@link VelophyllEntity#getGroundAmount} が補間してあるのでガクッと跳ねない。
 */
public class VelophyllRenderer extends GeoEntityRenderer<VelophyllEntity> {

    /** 機首の上げ下げの最大角(度)。 */
    private static final float MAX_PITCH = 45.0F;
    /** 旋回のバンクの最大角(度)。 */
    private static final float MAX_BANK = 35.0F;
    /** 傾きの支点にする高さ(ブロック)。だいたい胴の中心。 */
    private static final float PIVOT_HEIGHT = 0.35F;
    /** 地上にいる間、体を持ち上げる高さ(ブロック)。垂れている膜のぶん。 */
    private static final float GROUND_LIFT = 0.5F;

    public VelophyllRenderer(EntityRendererProvider.Context context) {
        super(context, new VelophyllModel());
        this.shadowRadius = 0.9F;
    }

    /**
     * {@code super.applyRotations} のあと、モデルローカルでは <b>-Z が進行方向・+X が右</b>
     * ({@code ArboraRenderer} と同じ前提)。そこから:
     * <ul>
     *     <li>X 軸まわりに正で回すと -Z 側(機首)が<b>上がる</b> → {@code pitchAmount} をそのまま掛ける
     *         ({@code pitchAmount} は上昇中が +)。</li>
     *     <li>Z 軸まわりに正で回すと +X 側(右翼)が<b>上がる</b> → 右へ旋回するときは右翼を下げたいので
     *         {@code bankAmount} に<b>マイナス</b>を掛ける。</li>
     * </ul>
     * 座標は {@code nativeScale} で割る({@code SCALE} 属性が入っていても支点がずれないように)。
     */
    @Override
    protected void applyRotations(VelophyllEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick, nativeScale);

        float ground = animatable.getGroundAmount(partialTick);
        if (ground > 0.001F) {
            poseStack.translate(0.0F, GROUND_LIFT * ground / nativeScale, 0.0F);
        }

        float pitch = animatable.getPitchAmount(partialTick) * MAX_PITCH;
        float bank = animatable.getBankAmount(partialTick) * -MAX_BANK;
        if (Math.abs(pitch) < 0.05F && Math.abs(bank) < 0.05F) {
            return;
        }
        float pivot = PIVOT_HEIGHT / nativeScale;
        poseStack.translate(0.0F, pivot, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(bank));
        poseStack.translate(0.0F, -pivot, 0.0F);
    }

    /** 影だけは {@code SCALE} 属性が掛からないので、ここで掛ける。 */
    @Override
    protected float getShadowRadius(VelophyllEntity entity) {
        return this.shadowRadius * entity.getScale();
    }
}
