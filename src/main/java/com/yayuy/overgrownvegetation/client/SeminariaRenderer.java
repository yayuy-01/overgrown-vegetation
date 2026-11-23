package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「セミナリア」の描画。描画倍率 2(フリマキ r7 は密度 2 で塗ってあるので 1 テクセル = ブロックの 1 画素)。発光マスクは段 1 では重ねない
 * (光るのは構え。次の段)。
 * <p>r7 は宙に浮いた形(体の下が y ≈ 19 単位 = 2.4 ブロック上)なので、{@link #SHIFT_Y} 単位下げて脚の先を足元(当たりの箱の底)に置く。
 * 飛んでいる間は体の傾き(ロール)と機首の上下(ピッチ)で全体を胸の高さを中心に回す({@code SkyFlyer#bank}・{@code #pitch})。
 * 拡大(preRender)の後に回すので、ここでの平行移動はモデル単位 ÷ 16。
 */
public class SeminariaRenderer extends GeoEntityRenderer<SeminariaEntity> {

    /** 描画倍率【仮】。 */
    static final float SCALE = 2.0F;
    /** 下げる量(モデル単位)= 静止の最下点 18.7 単位。 */
    static final float SHIFT_Y = 18.8F;
    /** 回す中心(モデル単位)= 胸の骨の付け根 y 24。 */
    static final float PIVOT_Y = 24.0F;

    public SeminariaRenderer(EntityRendererProvider.Context context) {
        super(context, new SeminariaModel());
        this.withScale(SCALE);
        this.shadowRadius = 0.5F;
    }

    @Override
    protected void applyRotations(SeminariaEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick) {
        float nativeScale = com.yayuy.overgrownvegetation.client.PortRenderScale.of(animatable); // 1.20.1: 4.8.4 は nativeScale を渡さない(PortRenderScale)
        super.applyRotations(animatable, poseStack, ageInTicks, rotationYaw, partialTick);
        poseStack.translate(0.0F, -SHIFT_Y / 16.0F, 0.0F);
        float bank = animatable.bank(partialTick), pitch = animatable.pitch(partialTick);
        if (Math.abs(bank) < 0.01F && Math.abs(pitch) < 0.01F) return;
        poseStack.translate(0.0F, PIVOT_Y / 16.0F, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-pitch));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));   // ヴェロフィルと同じ向き(左へ曲がると左の翅が下がる)
        poseStack.translate(0.0F, -PIVOT_Y / 16.0F, 0.0F);
    }
}
