package com.yayuy.overgrownvegetation.client.lizard;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * コエルロサウラウスの描画(2026-11-16 段 1)。歩く間は「トカゲ型」のまま。
 * <ul>
 *   <li>登る・休む: climb のアニメは胴を原点の周りに縦に立てる(尾の先が原点の 15.6 px 下)。尾の先が足の高さに来るまで持ち上げ、
 *       足先(原点から前へ 3.4 px)が幹の面に着くまで前へ寄せる(当たりの幅の半分だけ幹から離れて立っているぶん)。</li>
 *   <li>滑空・dive: 旋回の向きへ体を倒す(胴の高さを軸に)。</li>
 * </ul>
 * 平行移動・回転は {@code applyRotations} の中(体格を掛ける前)なので、ブロックの量は体格で割って渡す。
 */
public class CoelurosauravusRenderer extends SimpleLizardRenderer<CoelurosauravusEntity> {

    /** 体を倒す軸の高さ(体格 1 のブロック)。胴の付け根 y 3 px。 */
    static final float BANK_PIVOT = 3.0F / 16.0F;

    public CoelurosauravusRenderer(EntityRendererProvider.Context context, SimpleLizardModel<CoelurosauravusEntity> model, float renderScale, float shadow) {
        super(context, model, renderScale, shadow);
    }

    @Override
    protected void applyRotations(CoelurosauravusEntity e, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick) {
        float nativeScale = com.yayuy.overgrownvegetation.client.PortRenderScale.of(e); // 1.20.1: 4.8.4 は nativeScale を渡さない(PortRenderScale)
        super.applyRotations(e, poseStack, ageInTicks, rotationYaw, partialTick);
        if (e.climbing()) {
            float in = (float) (e.getBbWidth() * 0.5D / Math.max(nativeScale, 0.05F) - CoelurosauravusEntity.CLIMB_REACH);
            poseStack.translate(0.0F, (float) CoelurosauravusEntity.CLIMB_TAIL, -in);   // 回したモデルの前は −Z
            return;
        }
        float bank = e.bank(partialTick);
        if (Math.abs(bank) < 0.01F) return;
        poseStack.translate(0.0F, BANK_PIVOT, 0.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(-bank));
        poseStack.translate(0.0F, -BANK_PIVOT, 0.0F);
    }
}
