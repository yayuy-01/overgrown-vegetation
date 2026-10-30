package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.PoddedCreeper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * 莢持ちのレンダラー(2026-10-23)。G1 の描画({@link G1GeoRenderer})に、{@link ParasitizedCreeperRenderer} と同じ
 * バニラの「爆発直前に膨張して白く光る」演出を足したもの。莢そのものの膨らみはアニメ「swell」(エンティティが導火の間だけ流す)。
 */
public class PoddedCreeperRenderer extends G1GeoRenderer<PoddedCreeper> {

    public PoddedCreeperRenderer(EntityRendererProvider.Context context) {
        super(context, "podded_creeper", 1.0F, 0.5F);
    }

    @Override
    public void preRender(PoseStack poseStack, PoddedCreeper animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
        if (!isReRender) {
            // バニラ CreeperRenderer#scale と同じ膨張演出。
            float f = animatable.getSwelling(partialTick);
            float f1 = 1.0F + Mth.sin(f * 100.0F) * f * 0.01F;
            f = Mth.clamp(f, 0.0F, 1.0F);
            f *= f;
            f *= f;
            float f2 = (1.0F + f * 0.4F) * f1;
            float f3 = (1.0F + f * 0.1F) / f1;
            poseStack.scale(f2, f3, f2);
        }
    }

    @Override
    public int getPackedOverlay(PoddedCreeper animatable, float u, float partialTick) {
        // バニラ CreeperRenderer#getWhiteOverlayProgress と同じ白フラッシュ。
        float swelling = animatable.getSwelling(partialTick);
        float white = (int) (swelling * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(swelling, 0.5F, 1.0F);
        return OverlayTexture.pack(OverlayTexture.u(white),
                OverlayTexture.v(animatable.hurtTime > 0 || animatable.deathTime > 0));
    }
}
